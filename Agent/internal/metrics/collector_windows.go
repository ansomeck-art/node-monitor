//go:build windows

package metrics

import (
	"crypto/sha256"
	"fmt"
	"os"
	"runtime"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

var (
	kernel32                         = windows.NewLazySystemDLL("kernel32.dll")
	getSystemTimes                   = kernel32.NewProc("GetSystemTimes")
	globalMemoryStatusEx             = kernel32.NewProc("GlobalMemoryStatusEx")
	getLogicalProcessorInformationEx = kernel32.NewProc("GetLogicalProcessorInformationEx")
	getActiveProcessorGroupCount     = kernel32.NewProc("GetActiveProcessorGroupCount")
	getTickCount64                   = kernel32.NewProc("GetTickCount64")
)

type memoryStatusEx struct {
	Length, MemoryLoad                                                                                   uint32
	TotalPhys, AvailPhys, TotalPageFile, AvailPageFile, TotalVirtual, AvailVirtual, AvailExtendedVirtual uint64
}

type Collector struct {
	host        Host
	previousCPU cpuCounter
	hasCPU      bool
	packages    []CPUPackage
	previousNet map[uint32][2]uint64
	lastTime    time.Time
	disks       []Disk
	lastDisks   time.Time
	gpus        []GPU
	gpuStatus   string
	lastGPU     time.Time
	GPUInterval time.Duration
	GPUEnabled  bool
	multiGroup  bool
}

func NewCollector(gpuInterval time.Duration, gpuEnabled bool) *Collector {
	hostname, _ := os.Hostname()
	id := hostname
	if key, err := registry.OpenKey(registry.LOCAL_MACHINE, `SOFTWARE\Microsoft\Cryptography`, registry.QUERY_VALUE|registry.WOW64_64KEY); err == nil {
		if guid, _, err := key.GetStringValue("MachineGuid"); err == nil && guid != "" {
			id = guid
		}
		key.Close()
	}
	digest := sha256.Sum256([]byte(id))
	version := windows.RtlGetVersion()
	groups, _, _ := getActiveProcessorGroupCount.Call()
	return &Collector{
		host:     Host{ID: fmt.Sprintf("%x", digest[:8]), Name: hostname, OS: "Windows", Arch: runtime.GOARCH, Kernel: fmt.Sprintf("%d.%d.%d", version.MajorVersion, version.MinorVersion, version.BuildNumber)},
		packages: windowsCPUPackages(), previousNet: map[uint32][2]uint64{}, GPUInterval: gpuInterval, GPUEnabled: gpuEnabled, gpus: []GPU{}, multiGroup: groups > 1,
	}
}

func windowsCPUCounter() (cpuCounter, bool) {
	var idle, kernel, user windows.Filetime
	ok, _, _ := getSystemTimes.Call(uintptr(unsafe.Pointer(&idle)), uintptr(unsafe.Pointer(&kernel)), uintptr(unsafe.Pointer(&user)))
	if ok == 0 {
		return cpuCounter{}, false
	}
	value := func(v windows.Filetime) uint64 { return uint64(v.HighDateTime)<<32 | uint64(v.LowDateTime) }
	// Kernel time already includes idle time.
	return cpuCounter{Total: value(kernel) + value(user), Idle: value(idle)}, true
}

func windowsCPUPackages() []CPUPackage {
	var size uint32
	getLogicalProcessorInformationEx.Call(3, 0, uintptr(unsafe.Pointer(&size)))
	if size == 0 || size > 1<<20 {
		return nil
	}
	data := make([]byte, size)
	ok, _, _ := getLogicalProcessorInformationEx.Call(3, uintptr(unsafe.Pointer(&data[0])), uintptr(unsafe.Pointer(&size)))
	if ok == 0 {
		return nil
	}
	return parseWindowsPackages(data[:size])
}

func (c *Collector) Collect(now time.Time, interval float64) Snapshot {
	s := Snapshot{SchemaVersion: 1, AgentVersion: Version, SampledAt: now.UTC(), Interval: interval, GPUInterval: c.GPUInterval.Seconds(), Host: c.host, GPUs: []GPU{}, Disks: []Disk{}, Sensors: []Sensor{}, Warnings: []string{}, Capabilities: map[string]string{}}
	ticks, _, _ := getTickCount64.Call()
	s.Host.Uptime = float64(ticks) / 1000
	s.CPU.Cores = runtime.NumCPU()
	s.CPU.Load = []float64{}
	s.CPU.Packages = append([]CPUPackage{}, c.packages...)
	s.Capabilities["cpu"] = "warming_up"
	if c.multiGroup {
		s.Capabilities["cpu"] = "unsupported"
		s.Warnings = append(s.Warnings, "多处理器组的 CPU 使用率暂不可用")
	} else if current, ok := windowsCPUCounter(); ok {
		if c.hasCPU {
			s.CPU.Usage = cpuUsage(c.previousCPU, current)
		}
		c.previousCPU, c.hasCPU = current, true
		if s.CPU.Usage != nil {
			s.Capabilities["cpu"] = "ok"
		}
		if len(s.CPU.Packages) == 1 {
			s.CPU.Packages[0].Usage = s.CPU.Usage
		}
	} else {
		s.Capabilities["cpu"] = "unavailable"
	}
	if len(s.CPU.Packages) > 1 {
		s.Warnings = append(s.Warnings, "多个 CPU 的独立使用率暂不可用；显示总使用率")
	}
	var memory memoryStatusEx
	memory.Length = uint32(unsafe.Sizeof(memory))
	if ok, _, _ := globalMemoryStatusEx.Call(uintptr(unsafe.Pointer(&memory))); ok != 0 && memory.TotalPhys > 0 {
		s.Memory = Memory{Total: memory.TotalPhys, Available: memory.AvailPhys, Used: memory.TotalPhys - memory.AvailPhys, Usage: Number(100 * float64(memory.TotalPhys-memory.AvailPhys) / float64(memory.TotalPhys))}
		s.Capabilities["memory"] = "ok"
	} else {
		s.Capabilities["memory"] = "unavailable"
	}
	// Windows does not expose reliable CPU package temperature via this API.
	s.Capabilities["temperature"] = "unsupported"
	if c.lastDisks.IsZero() || now.Sub(c.lastDisks) >= 30*time.Second {
		c.disks = windowsDisks()
		c.lastDisks = now
	}
	s.Disks = c.disks
	if s.Disks == nil {
		s.Disks = []Disk{}
	}
	s.Capabilities["disk"] = "ok"
	if len(s.Disks) == 0 {
		s.Capabilities["disk"] = "unavailable"
	}
	s.Network = c.windowsNetwork(now.Sub(c.lastTime).Seconds())
	s.Capabilities["network"] = "ok"
	if s.Network.Interfaces == nil {
		s.Network.Interfaces = []NetworkInterface{}
		s.Capabilities["network"] = "unavailable"
	}
	c.lastTime = now
	if !c.GPUEnabled {
		c.gpuStatus = "disabled"
	} else if c.lastGPU.IsZero() || now.Sub(c.lastGPU) >= c.GPUInterval {
		c.gpus, c.gpuStatus = collectGPUs(now)
		c.lastGPU = now
	}
	s.GPUs = c.gpus
	if s.GPUs == nil {
		s.GPUs = []GPU{}
	}
	s.Capabilities["gpu"] = c.gpuStatus
	if c.gpuStatus == "error" {
		s.Warnings = append(s.Warnings, "NVIDIA GPU 查询失败；检查驱动和采集账号权限")
	}
	return s
}

func windowsDisks() []Disk {
	mask, err := windows.GetLogicalDrives()
	if err != nil {
		return nil
	}
	var result []Disk
	for i := 0; i < 26; i++ {
		if mask&(1<<i) == 0 {
			continue
		}
		mount := fmt.Sprintf("%c:\\", 'A'+i)
		root, _ := windows.UTF16PtrFromString(mount)
		if windows.GetDriveType(root) != 3 {
			continue
		}
		var available, total, free uint64
		if windows.GetDiskFreeSpaceEx(root, &available, &total, &free) != nil || total == 0 || free > total {
			continue
		}
		var fs [64]uint16
		_ = windows.GetVolumeInformation(root, nil, 0, nil, nil, nil, &fs[0], uint32(len(fs)))
		result = append(result, Disk{ID: mount, Mount: mount, Filesystem: windows.UTF16ToString(fs[:]), Total: total, Used: total - free, Available: available})
	}
	return result
}

func (c *Collector) windowsNetwork(seconds float64) Network {
	n := Network{}
	var table *windows.MibIfTable2
	if windows.GetIfTable2Ex(windows.MibIfTableNormal, &table) != nil || table == nil {
		return n
	}
	defer windows.FreeMibTable(unsafe.Pointer(table))
	if table.NumEntries > 65536 {
		return n
	}
	var physicalRX, physicalTX, otherRX, otherTX float64
	physical, other := 0, 0
	n.Interfaces = []NetworkInterface{}
	for _, row := range unsafe.Slice(&table.Table[0], int(table.NumEntries)) {
		if row.Type == 24 || row.OperStatus != 1 {
			continue
		}
		name, description := windows.UTF16ToString(row.Alias[:]), strings.ToLower(windows.UTF16ToString(row.Description[:]))
		virtual := row.TunnelType != 0 || strings.Contains(description, "virtual") || strings.Contains(description, "vpn") || strings.Contains(description, "tunnel")
		item := NetworkInterface{Name: name, Received: row.InOctets, Sent: row.OutOctets, Virtual: virtual}
		if previous, ok := c.previousNet[row.InterfaceIndex]; ok {
			item.Receive = rate(previous[0], row.InOctets, seconds)
			item.Send = rate(previous[1], row.OutOctets, seconds)
		}
		c.previousNet[row.InterfaceIndex] = [2]uint64{row.InOctets, row.OutOctets}
		n.Interfaces = append(n.Interfaces, item)
		if item.Receive != nil && item.Send != nil {
			if virtual {
				other++
				otherRX += *item.Receive
				otherTX += *item.Send
			} else {
				physical++
				physicalRX += *item.Receive
				physicalTX += *item.Send
			}
		}
	}
	if physical > 0 {
		n.Receive, n.Send = Number(physicalRX), Number(physicalTX)
	} else if other > 0 {
		n.Receive, n.Send = Number(otherRX), Number(otherTX)
	}
	return n
}
