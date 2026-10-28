package metrics

import (
	"bufio"
	"fmt"
	"io"
	"strconv"
	"strings"
)

type cpuCounter struct{ Total, Idle uint64 }

func parseCPU(text string) (cpuCounter, error) {
	line := strings.SplitN(text, "\n", 2)[0]
	f := strings.Fields(line)
	if len(f) < 5 || f[0] != "cpu" {
		return cpuCounter{}, fmt.Errorf("invalid /proc/stat")
	}
	return parseCPUFields(f)
}
func parseCPUFields(f []string) (cpuCounter, error) {
	if len(f) < 5 {
		return cpuCounter{}, fmt.Errorf("invalid CPU counter")
	}
	var c cpuCounter
	// guest and guest_nice already belong to user/nice: don't count twice.
	for i := 1; i < len(f) && i <= 8; i++ {
		v, err := strconv.ParseUint(f[i], 10, 64)
		if err != nil {
			return c, err
		}
		c.Total += v
		if i == 4 || i == 5 {
			c.Idle += v
		}
	}
	return c, nil
}
func cpuUsage(before, after cpuCounter) *float64 {
	if after.Total <= before.Total || after.Idle < before.Idle {
		return nil
	}
	delta, idle := after.Total-before.Total, after.Idle-before.Idle
	if idle > delta {
		return nil
	}
	return Number(100 * float64(delta-idle) / float64(delta))
}
func parseMemory(text string) (Memory, error) {
	values := map[string]uint64{}
	s := bufio.NewScanner(strings.NewReader(text))
	for s.Scan() {
		f := strings.Fields(s.Text())
		if len(f) < 2 {
			continue
		}
		v, err := strconv.ParseUint(f[1], 10, 64)
		if err != nil {
			continue
		}
		if len(f) > 2 && f[2] == "kB" {
			v *= 1024
		}
		values[strings.TrimSuffix(f[0], ":")] = v
	}
	total := values["MemTotal"]
	if total == 0 {
		return Memory{}, fmt.Errorf("missing MemTotal")
	}
	available, ok := values["MemAvailable"]
	if !ok {
		available = values["MemFree"] + values["Buffers"] + values["Cached"] + values["SReclaimable"]
		if available >= values["Shmem"] {
			available -= values["Shmem"]
		}
	}
	if available > total {
		available = total
	}
	swap := values["SwapTotal"]
	free := values["SwapFree"]
	if free > swap {
		free = swap
	}
	return Memory{Total: total, Used: total - available, Available: available, SwapTotal: swap, SwapUsed: swap - free, Usage: Number(100 * float64(total-available) / float64(total))}, nil
}
func rate(previous, current uint64, seconds float64) *float64 {
	if seconds <= 0 || current < previous {
		return nil
	}
	return Number(float64(current-previous) / seconds)
}
func readText(r io.Reader) string { b, _ := io.ReadAll(io.LimitReader(r, 4<<20)); return string(b) }

// Only known CPU drivers or CPU-specific labels are classified as CPU sensors.
func sensorKind(driver, label string) string {
	driver, label = strings.ToLower(driver), strings.ToLower(label)
	if driver == "coretemp" || driver == "k10temp" || driver == "zenpower" || strings.Contains(label, "cpu") || strings.Contains(label, "package id") {
		return "cpu"
	}
	if driver == "amdgpu" || driver == "nouveau" || strings.Contains(label, "gpu") {
		return "gpu"
	}
	if driver == "nvme" {
		return "disk"
	}
	if strings.Contains(label, "dimm") || strings.Contains(label, "dram") {
		return "memory"
	}
	return "other"
}
