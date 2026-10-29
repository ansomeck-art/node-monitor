#!/usr/bin/python3
"""Offline fixture for the Swift/helper pipe protocol. Contains no credentials."""
import json
import sys
import threading
import time

write_lock = threading.Lock()
workers = []

def respond(req):
    # Deliberately reply out of order and in fragments to exercise framing.
    time.sleep((sum(req['id'].encode()) % 5) / 1000)
    out = {'id': req['id']}
    if req['action'] == 'status':
        out['state'] = 'Running'
    elif req['action'] == 'login':
        out['url'] = 'https://login.tailscale.com/a/fixture'
    elif req['action'] == 'fetch' and req.get('token') == 'fixture-valid':
        out['data'] = {
            'schemaVersion': 1, 'agentVersion': 'fixture', 'sampledAt': '2026-01-01T00:00:00Z',
            'sampleIntervalSeconds': 5, 'demo': True,
            'host': {'id': 'fixture', 'name': 'fixture', 'os': 'Ubuntu', 'arch': 'amd64', 'kernel': 'test', 'uptimeSeconds': 5},
            'cpu': {'usagePercent': 42, 'cores': 2, 'loadAverage': [0, 0, 0]},
            'memory': {'totalBytes': 100, 'usedBytes': 50, 'availableBytes': 50, 'swapTotalBytes': 0, 'swapUsedBytes': 0, 'usagePercent': 50},
            'gpus': [], 'disks': [], 'network': {'interfaces': []}, 'sensors': [], 'capabilities': {}, 'warnings': []
        }
    else:
        out['error'] = 'unauthorized'
    payload = (json.dumps(out, separators=(',', ':')) + '\n').encode()
    midpoint = len(payload) // 2
    with write_lock:
        sys.stdout.buffer.write(payload[:midpoint]); sys.stdout.buffer.flush()
        sys.stdout.buffer.write(payload[midpoint:]); sys.stdout.buffer.flush()

for line in sys.stdin:
    request = json.loads(line)
    worker = threading.Thread(target=respond, args=(request,))
    worker.start()
    workers.append(worker)
for worker in workers:
    worker.join()
