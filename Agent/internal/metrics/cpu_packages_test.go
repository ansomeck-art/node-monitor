package metrics

import "testing"

func TestPhysicalPackagesUseTheirOwnInterleavedThreads(t *testing.T) {
	topology := parseCPUTopology("processor : 0\nphysical id : 0\nmodel name : Xeon A\n\nprocessor : 1\nphysical id : 1\nmodel name : Xeon B\n\nprocessor : 2\nphysical id : 0\nmodel name : Xeon A\n\nprocessor : 3\nphysical id : 1\nmodel name : Xeon B\n")
	if len(topology) != 2 || len(topology[0].CPUs) != 2 || topology[0].CPUs[1] != 2 {
		t.Fatalf("bad socket topology: %+v", topology)
	}
	before := parsePerCPU("cpu 0 0 0 400\ncpu0 0 0 0 100\ncpu1 0 0 0 100\ncpu2 0 0 0 100\ncpu3 0 0 0 100\n")
	after := parsePerCPU("cpu 220 0 0 580\ncpu0 10 0 0 190\ncpu1 80 0 0 120\ncpu2 30 0 0 170\ncpu3 100 0 0 100\n")
	sensors := []Sensor{{ID: "x:coretemp:Package id 1", Name: "Package id 1", Kind: "cpu", Celsius: 61}, {ID: "x:coretemp:Core 0", Name: "Core 0", Kind: "cpu", Celsius: 90}, {ID: "x:coretemp:Package id 0", Name: "Package id 0", Kind: "cpu", Celsius: 42}}
	readings := packageReadings(topology, before, after, sensors)
	if len(readings) != 2 || readings[0].Usage == nil || *readings[0].Usage != 20 || readings[1].Usage == nil || *readings[1].Usage != 90 {
		t.Fatalf("sockets must not share aggregate usage: %+v", readings)
	}
	if *readings[0].Temperature != 42 || *readings[1].Temperature != 61 || readings[0].LogicalCores != 2 {
		t.Fatal("temperature must match physical package, not hottest core")
	}
}

func TestPackageMissingTopologyAndWarmupStayUnknown(t *testing.T) {
	if len(parseCPUTopology("processor : 0\nmodel name : unknown\n\nprocessor : 1\nmodel name : unknown")) != 0 {
		t.Fatal("thread count cannot establish physical sockets")
	}
	topology := []cpuPackageTopology{{ID: 0, CPUs: []int{0, 2}}}
	after := map[int]cpuCounter{0: {Total: 200, Idle: 100}, 2: {Total: 200, Idle: 100}}
	if r := packageReadings(topology, nil, after, nil)[0]; r.Usage != nil || r.Temperature != nil {
		t.Fatal("first sample and unmapped temperatures must be null")
	}
	before := map[int]cpuCounter{0: {Total: 300, Idle: 100}, 2: {Total: 100, Idle: 100}}
	if r := packageReadings(topology, before, after, nil)[0]; r.Usage != nil {
		t.Fatal("a thread counter reset must not be hidden by summing")
	}
	delete(before, 0)
	if r := packageReadings(topology, before, after, nil)[0]; r.Usage != nil {
		t.Fatal("missing thread counter must not become zero usage")
	}
}
