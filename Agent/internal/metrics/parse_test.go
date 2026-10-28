package metrics

import (
	"testing"
	"time"
)

func TestCPUExcludesGuestAndResets(t *testing.T) {
	a, err := parseCPU("cpu 100 20 30 400 10 3 4 5 90 10\ncpu0 1 2 3")
	if err != nil {
		t.Fatal(err)
	}
	if a.Total != 572 || a.Idle != 410 {
		t.Fatalf("guest double counted: %+v", a)
	}
	b := cpuCounter{Total: 672, Idle: 450}
	if v := cpuUsage(a, b); v == nil || *v != 60 {
		t.Fatalf("expected 60%%, got %v", v)
	}
	if cpuUsage(b, a) != nil || cpuUsage(a, a) != nil {
		t.Fatal("reset/zero counter must be unavailable")
	}
}
func TestMemoryUsesAvailableNotFree(t *testing.T) {
	m, err := parseMemory("MemTotal: 1000 kB\nMemFree: 50 kB\nMemAvailable: 400 kB\nCached: 300 kB\nSwapTotal: 200 kB\nSwapFree: 150 kB")
	if err != nil || m.Used != 600*1024 || *m.Usage != 60 || m.SwapUsed != 50*1024 {
		t.Fatalf("bad memory: %+v, %v", m, err)
	}
	if _, err := parseMemory("MemTotal: 0 kB"); err == nil {
		t.Fatal("missing memory must not appear as zero usage")
	}
}
func TestCounterResetIsGapNotSpike(t *testing.T) {
	if rate(200, 10, 5) != nil || rate(1, 2, 0) != nil {
		t.Fatal("reset/time gap should be null")
	}
	if r := rate(100, 1100, 5); r == nil || *r != 200 {
		t.Fatal("incorrect byte rate")
	}
}
func TestNVIDIAUnsupportedFieldsStayNull(t *testing.T) {
	g := parseNVIDIA("GPU-abcd, RTX 4090, 0, 45, 0, 24564, 12.3, [N/A]\nGPU-efgh, A100, 95, N/A, 1024, 40960, 200, [Not Supported]\n", time.Now())
	if len(g) != 2 || g[0].Usage == nil || *g[0].Usage != 0 || g[0].Fan != nil || g[1].Temperature != nil || g[1].MemoryUsed == nil || *g[1].MemoryUsed != 1<<30 {
		t.Fatalf("bad GPU parsing: %+v", g)
	}
}
func TestUnknownSensorIsNotCPUTemperature(t *testing.T) {
	if sensorKind("acpitz", "temp1") != "other" || sensorKind("coretemp", "Package id 0") != "cpu" || sensorKind("nvme", "Composite") != "disk" || sensorKind("jc42", "DIMM 0") != "memory" {
		t.Fatal("sensor misclassified")
	}
}
