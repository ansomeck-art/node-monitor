package metrics

import (
	"fmt"
	"sort"
	"strconv"
	"strings"
)

type cpuPackageTopology struct {
	ID   int
	Name string
	CPUs []int
}

// Physical IDs come from Linux, not thread count or assumed NUMA boundaries.
// This topology is read once; normal sampling reuses the existing /proc/stat read.
func parseCPUTopology(text string) []cpuPackageTopology {
	packages := map[int]*cpuPackageTopology{}
	for _, block := range strings.Split(strings.TrimSpace(text), "\n\n") {
		fields := map[string]string{}
		for _, line := range strings.Split(block, "\n") {
			if key, value, ok := strings.Cut(line, ":"); ok {
				fields[strings.TrimSpace(key)] = strings.TrimSpace(value)
			}
		}
		cpu, e1 := strconv.Atoi(fields["processor"])
		id, e2 := strconv.Atoi(fields["physical id"])
		if e1 != nil || e2 != nil || cpu < 0 || id < 0 {
			continue
		}
		p := packages[id]
		if p == nil {
			p = &cpuPackageTopology{ID: id, Name: fields["model name"]}
			packages[id] = p
		}
		p.CPUs = append(p.CPUs, cpu)
	}
	result := make([]cpuPackageTopology, 0, len(packages))
	for _, p := range packages {
		sort.Ints(p.CPUs)
		result = append(result, *p)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result
}

func parsePerCPU(text string) map[int]cpuCounter {
	result := map[int]cpuCounter{}
	for _, line := range strings.Split(text, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 5 || !strings.HasPrefix(fields[0], "cpu") {
			continue
		}
		id, err := strconv.Atoi(strings.TrimPrefix(fields[0], "cpu"))
		if err != nil || id < 0 {
			continue
		}
		if counter, err := parseCPUFields(fields); err == nil {
			result[id] = counter
		}
	}
	return result
}

func packageReadings(topology []cpuPackageTopology, before, after map[int]cpuCounter, sensors []Sensor) []CPUPackage {
	result := make([]CPUPackage, 0, len(topology))
	for _, p := range topology {
		reading := CPUPackage{ID: fmt.Sprintf("package-%d", p.ID), PackageID: p.ID, Name: p.Name, LogicalCores: len(p.CPUs)}
		var a, b cpuCounter
		valid := len(p.CPUs) > 0
		for _, id := range p.CPUs {
			previous, ok1 := before[id]
			current, ok2 := after[id]
			if !ok1 || !ok2 || current.Total < previous.Total || current.Idle < previous.Idle {
				valid = false
				break
			}
			a.Total += previous.Total
			a.Idle += previous.Idle
			b.Total += current.Total
			b.Idle += current.Idle
		}
		if valid {
			reading.Usage = cpuUsage(a, b)
		}
		for _, sensor := range sensors {
			// A package sensor's numeric label matches Linux physical_package_id.
			// Unknown sensor-to-socket mappings stay unavailable.
			if sensor.Kind == "cpu" && strings.Contains(sensor.ID, ":coretemp:") && sensor.Name == fmt.Sprintf("Package id %d", p.ID) {
				reading.Temperature = Number(sensor.Celsius)
				break
			}
		}
		result = append(result, reading)
	}
	return result
}
