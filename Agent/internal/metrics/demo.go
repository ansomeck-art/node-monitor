package metrics

import (
	"math"
	"time"
)

func DemoSnapshot(now time.Time, interval float64) Snapshot {
	t := float64(now.UnixMilli()) / 1000
	cpu := 35 + 12*math.Sin(t/19)
	gpu := 68 + 17*math.Sin(t/27)
	total := uint64(64) << 30
	used := uint64(float64(total) * (0.44 + 0.03*math.Sin(t/60)))
	s := Snapshot{SchemaVersion: 1, AgentVersion: Version, SampledAt: now.UTC(), Interval: interval, GPUInterval: interval, Demo: true,
		Host:         Host{ID: "demo-linux-gpu", Name: "GPU 工作站 · 演示", OS: "Ubuntu 24.04 LTS (模拟)", Arch: "amd64", Kernel: "演示数据", Uptime: 360000 + t/1000},
		CPU:          CPU{Usage: Number(cpu), Cores: 16, Load: []float64{3.6, 3.1, 2.8}, Packages: []CPUPackage{{ID: "demo-package-0", PackageID: 0, Name: "CPU · 演示", LogicalCores: 16, Usage: Number(cpu), Temperature: Number(49 + cpu/6)}}},
		Memory:       Memory{Total: total, Used: used, Available: total - used, Usage: Number(100 * float64(used) / float64(total))},
		GPUs:         []GPU{{ID: "demo-gpu-0", Name: "NVIDIA RTX 4090 · 演示", Vendor: "NVIDIA", Usage: Number(gpu), Temperature: Number(61 + gpu/9), MemoryUsed: Bytes(17 << 30), MemoryTotal: Bytes(24 << 30), Power: Number(180 + gpu), Fan: Number(42), SampledAt: now.UTC(), Status: "ok"}},
		Disks:        []Disk{{ID: "demo-nvme", Mount: "/", Filesystem: "ext4", Total: 2 << 40, Used: 640 << 30, Available: 1408 << 30}},
		Network:      Network{Receive: Number(12e6 + 2e6*math.Sin(t/12)), Send: Number(2.1e6), Read: Number(34e6), Write: Number(8e6), Interfaces: []NetworkInterface{}},
		Sensors:      []Sensor{{ID: "demo-package", Name: "CPU Package", Kind: "cpu", Celsius: 49 + cpu/6, Source: "模拟传感器"}},
		Capabilities: map[string]string{"cpu": "ok", "memory": "ok", "gpu": "ok", "temperature": "ok", "network": "ok", "disk": "ok"}, Warnings: []string{"演示数据，不代表本机或远程主机的真实读数"}}
	return s
}
