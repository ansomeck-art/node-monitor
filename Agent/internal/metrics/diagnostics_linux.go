//go:build linux

package metrics

import (
	"bytes"
	"context"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"time"
)

type diagnosticsCollector struct {
	processes    bool
	units        []string
	previous     map[int]processCounter
	total        uint64
	services     []Service
	lastServices time.Time
}

func (c *Collector) ConfigureDiagnostics(processes bool, units []string) {
	c.diagnostics.processes = processes
	c.diagnostics.units = append([]string(nil), units...)
}
func (d *diagnosticsCollector) collectProcesses(root string, total uint64) []Process {
	if !d.processes {
		return nil
	}
	directory, err := os.Open(root)
	if err != nil {
		d.previous = nil
		d.total = 0
		return nil
	}
	defer directory.Close()
	// Bounded traversal and stat reads; command lines and environments are never read.
	names, _ := directory.Readdirnames(32768)
	current := map[int]processCounter{}
	out := []Process{}
	for _, name := range names {
		if _, err := strconv.Atoi(name); err != nil {
			continue
		}
		file, err := os.Open(filepath.Join(root, name, "stat"))
		if err != nil {
			continue
		}
		data, err := io.ReadAll(io.LimitReader(file, 16384))
		file.Close()
		if err != nil {
			continue
		}
		counter, err := parseProcessStat(string(data), uint64(os.Getpagesize()))
		if err != nil {
			continue
		}
		current[counter.pid] = counter
		p := Process{PID: counter.pid, Name: counter.name, Memory: counter.memory}
		if previous, ok := d.previous[counter.pid]; ok {
			p.CPU = processUsage(previous, counter, d.total, total)
		}
		out = append(out, p)
	}
	d.previous = current
	d.total = total
	return topProcesses(out)
}

type diagnosticOutput struct{ bytes.Buffer }

func (b *diagnosticOutput) Write(p []byte) (int, error) {
	if b.Len()+len(p) > 65536 {
		return 0, io.ErrShortBuffer
	}
	return b.Buffer.Write(p)
}
func (d *diagnosticsCollector) collectServices(now time.Time) []Service {
	if len(d.units) == 0 {
		return nil
	}
	if !d.lastServices.IsZero() && now.Sub(d.lastServices) < 30*time.Second {
		return d.services
	}
	d.lastServices = now
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()
	args := append([]string{"show", "--no-pager", "--property=Id,LoadState,ActiveState", "--"}, d.units...)
	cmd := exec.CommandContext(ctx, "systemctl", args...)
	cmd.WaitDelay = 250 * time.Millisecond
	var output diagnosticOutput
	cmd.Stdout = &output
	cmd.Stderr = io.Discard
	// systemctl may return nonzero for a missing unit while still returning other states.
	_ = cmd.Run()
	d.services = parseServiceStates(output.String(), d.units)
	return d.services
}
