#!/usr/bin/env python3
"""Read CPU-time deltas and RSS for this project's macOS app, with no profiler injection."""
import argparse
import json
import pathlib
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('pid', type=int)
parser.add_argument('--duration', type=int, default=60)
parser.add_argument('--poll-interval', type=float, default=5)
parser.add_argument('--scenario', default='current UI')
parser.add_argument('--output', type=pathlib.Path)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
expected = str(root / 'build/节点监控.app/Contents/MacOS/NodeMonitor')
command = subprocess.check_output(['ps', '-p', str(args.pid), '-o', 'comm='], text=True).strip()
if command != expected:
    raise SystemExit('PID is not this project app')

def sample():
    fields = subprocess.check_output(['ps', '-p', str(args.pid), '-o', 'time=,rss='], text=True).split()
    parts = fields[0].split(':')
    seconds = sum(float(value) * 60 ** index for index, value in enumerate(reversed(parts)))
    return time.monotonic(), seconds, int(fields[1])

start = sample()
memory = [start[2]]
deadline = start[0] + args.duration
while time.monotonic() < deadline:
    time.sleep(min(5, max(0, deadline - time.monotonic())))
    memory.append(sample()[2])
end = sample()
result = {'durationSeconds': round(end[0] - start[0], 2),
          'cpuPercentOfOneCore': round(100 * (end[1] - start[1]) / (end[0] - start[0]), 3),
          'rssMiBMean': round(sum(memory) / len(memory) / 1024, 2),
          'rssMiBMax': round(max(memory) / 1024, 2),
          'scenario': args.scenario, 'pollingIntervalSeconds': args.poll_interval,
          'conditions': f'native app, one real host, {args.poll_interval:g}s polling; RSS includes shared framework pages; no long-run energy measurement'}
encoded = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
if args.output:
    output = args.output.resolve()
    if not output.is_relative_to(root): raise SystemExit('Output must stay inside the project')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(encoded)
print(encoded, end='')
