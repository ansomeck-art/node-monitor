#!/usr/bin/env python3
"""Read-only process measurement; includes reaped child CPU, never runs load generators."""
import argparse
import json
import os
import pathlib
import time

parser = argparse.ArgumentParser()
parser.add_argument("pid_file", type=pathlib.Path)
parser.add_argument("--duration", type=int, default=120)
args = parser.parse_args()
pid = int(args.pid_file.read_text().strip())
base = pathlib.Path("/proc") / str(pid)
ticks = os.sysconf("SC_CLK_TCK")

def sample():
    stat = (base / "stat").read_text().split(") ", 1)[1].split()
    cpu = sum(int(stat[index]) for index in (11, 12, 13, 14)) / ticks
    fields = dict(line.split(":", 1) for line in (base / "status").read_text().splitlines() if ":" in line)
    return time.monotonic(), cpu, int(fields["VmRSS"].split()[0])

start = sample()
memory = [start[2]]
deadline = time.monotonic() + args.duration
while time.monotonic() < deadline:
    time.sleep(min(5, max(0, deadline - time.monotonic())))
    current = sample()
    memory.append(current[2])
end = sample()
result = {"durationSeconds": round(end[0] - start[0], 2),
          "cpuPercentOfOneCoreIncludingReapedChildren": round(100 * (end[1] - start[1]) / (end[0] - start[0]), 3),
          "rssMiBMean": round(sum(memory) / len(memory) / 1024, 2), "rssMiBMax": round(max(memory) / 1024, 2),
          "conditions": "one real Ubuntu host, 5s base, 15s nvidia-smi, no synthetic workload; not a long-run energy benchmark"}
print(json.dumps(result, ensure_ascii=False, indent=2))
