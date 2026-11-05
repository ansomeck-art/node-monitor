package metrics

import (
	"strings"
	"testing"
)

func TestServiceUnits(t *testing.T) {
	units, err := ServiceUnits("docker, nginx.service,docker")
	if err != nil || len(units) != 2 || units[0] != "docker.service" {
		t.Fatalf("%v %v", units, err)
	}
	for _, text := range []string{"--all", "nginx;reboot", "foo/bar", "a,,b", strings.Repeat("a,", 17)} {
		if _, err := ServiceUnits(text); err == nil {
			t.Fatalf("accepted %q", text)
		}
	}
}
func TestProcessStatNamesAndFields(t *testing.T) {
	f := make([]string, 22)
	for i := range f {
		f[i] = "0"
	}
	f[0] = "S"
	f[11] = "17"
	f[12] = "3"
	f[19] = "100"
	f[21] = "20"
	p, err := parseProcessStat("42 (worker (gpu) x) "+strings.Join(f, " "), 4096)
	if err != nil || p.pid != 42 || p.name != "worker (gpu) x" || p.ticks != 20 || p.start != 100 || p.memory != 81920 {
		t.Fatalf("%+v %v", p, err)
	}
	if _, err := parseProcessStat("42 (short) S 0", 4096); err == nil {
		t.Fatal("short stat accepted")
	}
	f[21] = "-1"
	if _, err := parseProcessStat("42 (x) "+strings.Join(f, " "), 4096); err == nil {
		t.Fatal("negative RSS accepted")
	}
}
func TestServiceStatesRemainUnknown(t *testing.T) {
	out := parseServiceStates("Id=docker.service\nLoadState=loaded\nActiveState=active\n\nId=nginx.service\nLoadState=not-found\nActiveState=inactive\n", []string{"docker.service", "nginx.service", "redis.service"})
	if out[0].State != "active" || out[1].State != "not-found" || out[2].State != "unknown" {
		t.Fatalf("%+v", out)
	}
}
func TestProcessUsageRejectsReuseAndCounterReset(t *testing.T) {
	before := processCounter{pid: 42, start: 100, ticks: 20}
	after := processCounter{pid: 42, start: 100, ticks: 40}
	if n := processUsage(before, after, 1000, 1200); n == nil || *n != 10 {
		t.Fatalf("normalized usage %v", n)
	}
	after.start = 200
	if processUsage(before, after, 1000, 1200) != nil {
		t.Fatal("PID reuse received old process usage")
	}
	after.start = 100
	after.ticks = 19
	if processUsage(before, after, 1000, 1200) != nil {
		t.Fatal("counter reset accepted")
	}
	after.ticks = 40
	if processUsage(before, after, 1000, 900) != nil {
		t.Fatal("system counter reset accepted")
	}
	if processUsage(before, after, 1000, 1000) != nil {
		t.Fatal("zero interval accepted")
	}
	after.ticks = 300
	if processUsage(before, after, 1000, 1200) != nil {
		t.Fatal("impossible usage accepted")
	}
}
func TestTopProcessesSortKnownUsageBeforeUnknown(t *testing.T) {
	processes := []Process{{PID: 1, CPU: nil, Memory: 9999}, {PID: 2, CPU: Number(0), Memory: 1}, {PID: 3, CPU: Number(20)}, {PID: 4, CPU: Number(10)}, {PID: 5, CPU: Number(10), Memory: 20}, {PID: 6, CPU: Number(9)}, {PID: 7, CPU: Number(1)}}
	out := topProcesses(processes)
	if len(out) != 5 {
		t.Fatal(len(out))
	}
	for i, pid := range []int{3, 5, 4, 6, 7} {
		if out[i].PID != pid {
			t.Fatalf("ranking %+v", out)
		}
	}
}
func TestMemoryCacheDoesNotUnderflow(t *testing.T) {
	m, err := parseMemory("MemTotal: 1000 kB\nMemAvailable: 200 kB\nBuffers: 10 kB\nCached: 20 kB\nSReclaimable: 5 kB\nShmem: 8 kB\n")
	if err != nil || m.Cached == nil || *m.Cached != 27*1024 {
		t.Fatalf("%+v %v", m, err)
	}
}
