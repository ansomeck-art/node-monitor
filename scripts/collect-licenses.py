#!/usr/bin/env python3
"""Collect notices for Go modules actually linked into the shipped binaries."""
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
go = os.environ['GO_BIN']
decoder = json.JSONDecoder()
modules = {}

for platform, arch in [('darwin', 'arm64'), ('linux', 'amd64'), ('linux', 'arm64')]:
    env = dict(os.environ, GOOS=platform, GOARCH=arch, CGO_ENABLED='0')
    data = subprocess.check_output(
        [go, 'list', '-deps', '-json', './cmd/nodemon-agent', './cmd/nodemon-tailscale-bridge'],
        cwd=root / 'Agent', env=env, text=True,
    )
    pos = 0
    while pos < len(data):
        while pos < len(data) and data[pos].isspace():
            pos += 1
        if pos == len(data):
            break
        package, pos = decoder.raw_decode(data, pos)
        module = package.get('Module')
        if not module or module.get('Main') or not module.get('Dir') or not package.get('Dir'):
            continue
        module_root = Path(module['Dir']).resolve()
        package_dir = Path(package['Dir']).resolve()
        try:
            package_dir.relative_to(module_root)
        except ValueError:
            continue
        key = module['Path'] + '@' + module.get('Version', 'local')
        entry = modules.setdefault(key, (module_root, set()))
        for directory in [package_dir, *package_dir.parents]:
            if directory == module_root.parent:
                break
            entry[1].add(directory)

sections = []
missing = []
for name, (module_root, directories) in sorted(modules.items()):
    files = set()
    for directory in directories:
        for path in directory.iterdir():
            stem = path.name.lower().split('.')[0]
            if path.is_file() and stem in {'license', 'licence', 'copying', 'notice', 'copyright'}:
                files.add(path)
    if not files:
        missing.append(name)
        continue
    for path in sorted(files):
        sections.append(f'===== {name} / {path.relative_to(module_root)} =====\n\n' + path.read_text(encoding='utf-8', errors='replace'))

go_root = Path(subprocess.check_output([go, 'env', 'GOROOT'], text=True).strip())
sections.insert(0, '===== Go toolchain / LICENSE =====\n\n' + (go_root / 'LICENSE').read_text(encoding='utf-8'))
if missing:
    print('Missing license notices for: ' + ', '.join(missing), file=sys.stderr)
    sys.exit(1)
output = root / '.cache/package/THIRD_PARTY_NOTICES.txt'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text('\n\n'.join(sections) + '\n', encoding='utf-8')
print(f'Collected notices for {len(modules)} Go modules.')
