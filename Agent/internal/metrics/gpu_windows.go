//go:build windows

package metrics

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
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
	executable, err := exec.LookPath("nvidia-smi.exe")
	if err != nil {
		for _, candidate := range []string{filepath.Join(os.Getenv("ProgramFiles"), "NVIDIA Corporation", "NVSMI", "nvidia-smi.exe"), filepath.Join(os.Getenv("SystemRoot"), "System32", "nvidia-smi.exe")} {
			if info, statErr := os.Stat(candidate); statErr == nil && !info.IsDir() {
				executable = candidate
				break
			}
		}
	}
	if executable == "" {
		return []GPU{}, "unsupported"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, executable, "--query-gpu=uuid,name,utilization.gpu,temperature.gpu,memory.used,memory.total,power.draw,fan.speed", "--format=csv,noheader,nounits")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	var output boundedBuffer
	cmd.Stdout = &output
	if cmd.Run() != nil {
		return []GPU{}, "error"
	}
	gpus := parseNVIDIA(output.String(), now)
	if len(gpus) == 0 {
		return []GPU{}, "unavailable"
	}
	return gpus, "ok"
}
