package metrics

import (
	"fmt"
	"math"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

var serviceName = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.@-]{0,127}$`)

func ServiceUnits(text string) ([]string, error) {
	if strings.TrimSpace(text) == "" {
		return nil, nil
	}
	parts := strings.Split(text, ",")
	if len(parts) > 16 {
		return nil, fmt.Errorf("at most 16 service units are supported")
	}
	out := []string{}
	seen := map[string]bool{}
	for _, name := range parts {
		name = strings.TrimSpace(name)
		if !serviceName.MatchString(name) {
			return nil, fmt.Errorf("invalid service name")
		}
		if !strings.HasSuffix(name, ".service") {
			name += ".service"
		}
		if !seen[name] {
			out = append(out, name)
			seen[name] = true
		}
	}
	return out, nil
}

type processCounter struct {
	pid                  int
	name                 string
	ticks, start, memory uint64
}

func parseProcessStat(text string, pageSize uint64) (processCounter, error) {
	left, right := strings.Index(text, "("), strings.LastIndex(text, ")")
	if left < 1 || right <= left {
		return processCounter{}, fmt.Errorf("invalid process stat")
	}
	pid, err := strconv.Atoi(strings.TrimSpace(text[:left]))
	if err != nil || pid <= 0 {
		return processCounter{}, fmt.Errorf("invalid pid")
	}
	f := strings.Fields(text[right+1:])
	if len(f) < 22 {
		return processCounter{}, fmt.Errorf("short process stat")
	}
	user, e1 := strconv.ParseUint(f[11], 10, 64)
	system, e2 := strconv.ParseUint(f[12], 10, 64)
	start, e3 := strconv.ParseUint(f[19], 10, 64)
	rss, e4 := strconv.ParseInt(f[21], 10, 64)
	if e1 != nil || e2 != nil || e3 != nil || e4 != nil || rss < 0 || user > math.MaxUint64-system || pageSize == 0 || uint64(rss) > math.MaxUint64/pageSize {
		return processCounter{}, fmt.Errorf("invalid process counters")
	}
	name := text[left+1 : right]
	if len(name) > 128 {
		name = name[:128]
	}
	return processCounter{pid: pid, name: name, ticks: user + system, start: start, memory: uint64(rss) * pageSize}, nil
}

// Process and whole-system counters use the same kernel clock ticks. Dividing
// their deltas avoids assumptions about USER_HZ and normalizes by all CPUs.
func processUsage(previous, current processCounter, previousTotal, total uint64) *float64 {
	if previous.pid != current.pid || previous.start != current.start || current.ticks < previous.ticks || total <= previousTotal {
		return nil
	}
	used, elapsed := current.ticks-previous.ticks, total-previousTotal
	if used > elapsed {
		return nil
	}
	return Number(100 * float64(used) / float64(elapsed))
}

func topProcesses(out []Process) []Process {
	sort.Slice(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if a.CPU != nil && b.CPU == nil {
			return true
		}
		if a.CPU == nil && b.CPU != nil {
			return false
		}
		if a.CPU != nil && b.CPU != nil && *a.CPU != *b.CPU {
			return *a.CPU > *b.CPU
		}
		if a.Memory != b.Memory {
			return a.Memory > b.Memory
		}
		return a.PID < b.PID
	})
	if len(out) > 5 {
		return out[:5]
	}
	return out
}

// Missing units and inaccessible systemd must remain unknown, never inactive.
func parseServiceStates(text string, units []string) []Service {
	states := map[string]string{}
	for _, block := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n\n") {
		values := map[string]string{}
		for _, line := range strings.Split(block, "\n") {
			key, value, ok := strings.Cut(line, "=")
			if ok {
				values[key] = value
			}
		}
		id := values["Id"]
		if id == "" {
			continue
		}
		state := values["ActiveState"]
		if values["LoadState"] == "not-found" {
			state = "not-found"
		}
		switch state {
		case "active", "inactive", "failed", "activating", "deactivating", "not-found":
		default:
			state = "unknown"
		}
		states[id] = state
	}
	out := make([]Service, 0, len(units))
	for _, unit := range units {
		state := states[unit]
		if state == "" {
			state = "unknown"
		}
		out = append(out, Service{Name: strings.TrimSuffix(unit, ".service"), State: state})
	}
	return out
}
