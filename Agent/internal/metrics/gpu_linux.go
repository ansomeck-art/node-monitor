//go:build linux

package metrics

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type boundedBuffer struct{ bytes.Buffer }

func (b *boundedBuffer) Write(p []byte) (int, error) {
	n := len(p)
	remaining := 65536 - b.Len()
	if remaining > 0 {
		if len(p) > remaining {
			p = p[:remaining]
		}
		_, _ = b.Buffer.Write(p)
	}
	return n, nil
}

func collectGPUs(now time.Time) ([]GPU, string) {
	result := []GPU{}
	status := "unsupported"
	if path, err := exec.LookPath("nvidia-smi"); err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, path, "--query-gpu=uuid,name,utilization.gpu,temperature.gpu,memory.used,memory.total,power.draw,fan.speed", "--format=csv,noheader,nounits")
		var output boundedBuffer
		cmd.Stdout = &output
		if err := cmd.Run(); err == nil {
			result = parseNVIDIA(output.String(), now)
			if len(result) > 0 {
				status = "ok"
			}
		} else {
			status = "error"
		}
	}
	cards, _ := filepath.Glob("/sys/class/drm/card[0-9]*")
	for _, card := range cards {
		if strings.Contains(filepath.Base(card), "-") {
			continue
		}
		device := filepath.Join(card, "device")
		vendor := readFile(filepath.Join(device, "vendor"))
		if vendor != "0x1002" && vendor != "0x8086" {
			continue
		}
		resolved, _ := filepath.EvalSymlinks(device)
		id := filepath.Base(resolved)
		name := "AMD GPU"
		if vendor == "0x8086" {
			name = "Intel GPU"
		}
		g := GPU{ID: id, Name: name + " (" + id + ")", Vendor: strings.Split(name, " ")[0], SampledAt: now.UTC(), Status: "partial"}
		if n := optionalNumber(readFile(filepath.Join(device, "gpu_busy_percent"))); n != nil {
			g.Usage = n
		}
		if n, err := strconv.ParseUint(readFile(filepath.Join(device, "mem_info_vram_used")), 10, 64); err == nil {
			g.MemoryUsed = Bytes(n)
		}
		if n, err := strconv.ParseUint(readFile(filepath.Join(device, "mem_info_vram_total")), 10, 64); err == nil && n > 0 {
			g.MemoryTotal = Bytes(n)
		}
		hwmons, _ := filepath.Glob(filepath.Join(device, "hwmon/hwmon*/temp1_input"))
		for _, path := range hwmons {
			if n := optionalNumber(readFile(path)); n != nil {
				g.Temperature = Number(*n / 1000)
				break
			}
		}
		if g.Usage != nil && g.Temperature != nil {
			g.Status = "ok"
		}
		result = append(result, g)
		if status != "error" {
			status = "ok"
		}
	}
	// A present but inaccessible NVIDIA device must not be reported as absent.
	if len(result) == 0 {
		if _, err := os.Stat("/dev/nvidia0"); err == nil && status == "unsupported" {
			status = "unavailable"
		}
	}
	return result, status
}
