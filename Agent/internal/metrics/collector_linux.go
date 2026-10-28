//go:build linux

package metrics

import (
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type Collector struct {
	host           Host
	previousCPU    cpuCounter
	previousPerCPU map[int]cpuCounter
	cpuTopology    []cpuPackageTopology
	hasCPU         bool
	previousNet    map[string][2]uint64
	previousIO     [2]uint64
	lastTime       time.Time
	disks          []Disk
	lastDisks      time.Time
	gpus           []GPU
	gpuStatus      string
	lastGPU        time.Time
	GPUInterval    time.Duration
	GPUEnabled     bool
}

func NewCollector(gpuInterval time.Duration, gpuEnabled bool) *Collector {
	hostname, _ := os.Hostname()
	id := readFile("/etc/machine-id")
	if id == "" {
		id = hostname
	}
	digest := sha256.Sum256([]byte(id))
	osName := "Linux"
	for _, line := range strings.Split(readFile("/etc/os-release"), "\n") {
		if strings.HasPrefix(line, "PRETTY_NAME=") {
			osName = strings.Trim(strings.TrimPrefix(line, "PRETTY_NAME="), "\"")
			break
		}
	}
	return &Collector{host: Host{ID: fmt.Sprintf("%x", digest[:8]), Name: hostname, OS: osName, Arch: runtime.GOARCH, Kernel: strings.TrimSpace(readFile("/proc/sys/kernel/osrelease"))},
		previousNet: map[string][2]uint64{}, cpuTopology: parseCPUTopology(readFile("/proc/cpuinfo")), GPUInterval: gpuInterval, GPUEnabled: gpuEnabled, gpus: []GPU{}}
}
func readFile(path string) string { b, _ := os.ReadFile(path); return strings.TrimSpace(string(b)) }
func (c *Collector) Collect(now time.Time, interval float64) Snapshot {
	s := Snapshot{SchemaVersion: 1, AgentVersion: Version, SampledAt: now.UTC(), Interval: interval, GPUInterval: c.GPUInterval.Seconds(), Host: c.host, GPUs: []GPU{}, Disks: []Disk{}, Sensors: []Sensor{}, Warnings: []string{}, Capabilities: map[string]string{}}
	seconds := now.Sub(c.lastTime).Seconds()
	stat := readFile("/proc/stat")
	if counter, err := parseCPU(stat); err == nil {
		if c.hasCPU {
			s.CPU.Usage = cpuUsage(c.previousCPU, counter)
		}
		c.previousCPU = counter
		c.hasCPU = true
		s.Capabilities["cpu"] = "ok"
		if s.CPU.Usage == nil {
			s.Capabilities["cpu"] = "warming_up"
		}
	} else {
		s.Capabilities["cpu"] = "error"
		s.Warnings = append(s.Warnings, "无法读取 CPU 计数器")
	}
	for _, line := range strings.Split(stat, "\n") {
		f := strings.Fields(line)
		if len(f) > 0 && strings.HasPrefix(f[0], "cpu") && f[0] != "cpu" {
			s.CPU.Cores++
		}
	}
	if s.CPU.Cores == 0 {
		s.CPU.Cores = runtime.NumCPU()
	}
	for i, value := range strings.Fields(readFile("/proc/loadavg")) {
		if i >= 3 {
			break
		}
		n, err := strconv.ParseFloat(value, 64)
		if err == nil {
			s.CPU.Load = append(s.CPU.Load, n)
		}
	}
	if mem, err := parseMemory(readFile("/proc/meminfo")); err == nil {
		s.Memory = mem
		s.Capabilities["memory"] = "ok"
	} else {
		s.Capabilities["memory"] = "error"
		s.Warnings = append(s.Warnings, "无法读取内存统计")
	}
	if values := strings.Fields(readFile("/proc/uptime")); len(values) > 0 {
		s.Host.Uptime, _ = strconv.ParseFloat(values[0], 64)
	}
	if c.lastDisks.IsZero() || now.Sub(c.lastDisks) >= 30*time.Second {
		c.disks = collectDisks()
		c.lastDisks = now
	}
	s.Disks = c.disks
	s.Capabilities["disk"] = "ok"
	if len(s.Disks) == 0 {
		s.Disks = []Disk{}
		s.Capabilities["disk"] = "unavailable"
	}
	s.Network = c.collectNetwork(seconds)
	io := collectDiskCounters()
	if !c.lastTime.IsZero() {
		s.Network.Read = rate(c.previousIO[0], io[0], seconds)
		s.Network.Write = rate(c.previousIO[1], io[1], seconds)
	}
	c.previousIO = io
	c.lastTime = now
	s.Capabilities["network"] = "ok"
	s.Sensors = collectSensors()
	perCPU := parsePerCPU(stat)
	s.CPU.Packages = packageReadings(c.cpuTopology, c.previousPerCPU, perCPU, s.Sensors)
	c.previousPerCPU = perCPU
	s.Capabilities["temperature"] = "ok"
	if len(s.Sensors) == 0 {
		s.Capabilities["temperature"] = "unsupported"
	}
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
		s.Warnings = append(s.Warnings, "GPU 查询失败；检查驱动和采集账号权限")
	}
	return s
}

func collectDisks() []Disk {
	result := []Disk{}
	seen := map[string]bool{}
	for _, line := range strings.Split(readFile("/proc/self/mountinfo"), "\n") {
		parts := strings.SplitN(line, " - ", 2)
		if len(parts) != 2 {
			continue
		}
		left, right := strings.Fields(parts[0]), strings.Fields(parts[1])
		if len(left) < 5 || len(right) < 2 {
			continue
		}
		mount := strings.NewReplacer("\\040", " ", "\\011", "\t", "\\134", "\\").Replace(left[4])
		fs := right[0]
		allowed := fs == "ext4" || fs == "ext3" || fs == "xfs" || fs == "btrfs" || fs == "zfs" || fs == "f2fs" || fs == "nfs" || fs == "nfs4" || mount == "/"
		if !allowed || seen[left[2]] {
			continue
		}
		var stat syscall.Statfs_t
		if syscall.Statfs(mount, &stat) != nil || stat.Blocks == 0 {
			continue
		}
		seen[left[2]] = true
		result = append(result, Disk{ID: left[2], Mount: mount, Filesystem: fs, Total: stat.Blocks * uint64(stat.Bsize), Used: (stat.Blocks - stat.Bfree) * uint64(stat.Bsize), Available: stat.Bavail * uint64(stat.Bsize)})
	}
	return result
}
func (c *Collector) collectNetwork(seconds float64) Network {
	n := Network{Interfaces: []NetworkInterface{}}
	var physicalRX, physicalTX, otherRX, otherTX float64
	physical, other := 0, 0
	for _, line := range strings.Split(readFile("/proc/net/dev"), "\n") {
		parts := strings.SplitN(line, ":", 2)
		if len(parts) != 2 {
			continue
		}
		name := strings.TrimSpace(parts[0])
		if name == "lo" {
			continue
		}
		f := strings.Fields(parts[1])
		if len(f) < 9 {
			continue
		}
		rx, e1 := strconv.ParseUint(f[0], 10, 64)
		tx, e2 := strconv.ParseUint(f[8], 10, 64)
		if e1 != nil || e2 != nil {
			continue
		}
		path, _ := filepath.EvalSymlinks("/sys/class/net/" + name)
		virtual := strings.Contains(path, "/virtual/")
		i := NetworkInterface{Name: name, Received: rx, Sent: tx, Virtual: virtual}
		if previous, ok := c.previousNet[name]; ok {
			i.Receive = rate(previous[0], rx, seconds)
			i.Send = rate(previous[1], tx, seconds)
		}
		c.previousNet[name] = [2]uint64{rx, tx}
		n.Interfaces = append(n.Interfaces, i)
		if i.Receive != nil && i.Send != nil {
			if !virtual {
				physical++
				physicalRX += *i.Receive
				physicalTX += *i.Send
			} else if !strings.HasPrefix(name, "veth") && !strings.HasPrefix(name, "docker") && !strings.HasPrefix(name, "br-") {
				other++
				otherRX += *i.Receive
				otherTX += *i.Send
			}
		}
	}
	if physical > 0 {
		n.Receive = Number(physicalRX)
		n.Send = Number(physicalTX)
	} else if other > 0 {
		n.Receive = Number(otherRX)
		n.Send = Number(otherTX)
	}
	return n
}
func collectDiskCounters() [2]uint64 {
	var counters [2]uint64
	for _, line := range strings.Split(readFile("/proc/diskstats"), "\n") {
		f := strings.Fields(line)
		if len(f) < 10 {
			continue
		}
		name := f[2]
		if strings.HasPrefix(name, "loop") || strings.HasPrefix(name, "ram") || strings.HasPrefix(name, "zram") {
			continue
		}
		if _, err := os.Stat("/sys/block/" + name); err != nil {
			continue
		}
		if slaves, _ := os.ReadDir("/sys/block/" + name + "/slaves"); len(slaves) > 0 {
			continue
		}
		r, e1 := strconv.ParseUint(f[5], 10, 64)
		w, e2 := strconv.ParseUint(f[9], 10, 64)
		if e1 == nil && e2 == nil {
			counters[0] += r * 512
			counters[1] += w * 512
		}
	}
	return counters
}
func collectSensors() []Sensor {
	result := []Sensor{}
	files, _ := filepath.Glob("/sys/class/hwmon/hwmon*/temp*_input")
	for _, path := range files {
		dir := filepath.Dir(path)
		driver := readFile(filepath.Join(dir, "name"))
		label := readFile(strings.TrimSuffix(path, "_input") + "_label")
		if label == "" {
			label = driver + " " + strings.TrimSuffix(filepath.Base(path), "_input")
		}
		n, err := strconv.ParseFloat(readFile(path), 64)
		if err != nil {
			continue
		}
		n /= 1000
		if n < -20 || n > 150 {
			continue
		}
		device, _ := filepath.EvalSymlinks(filepath.Join(dir, "device"))
		id := device + ":" + driver + ":" + label
		result = append(result, Sensor{ID: id, Name: label, Kind: sensorKind(driver, label), Celsius: n, Source: path})
	}
	return result
}
