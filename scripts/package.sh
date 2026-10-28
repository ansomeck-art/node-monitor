#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"
if [[ ! -d build/节点监控.app ]] || [[ ! -f build/nodemon-agent-linux-amd64 ]] || [[ ! -f build/nodemon-agent-linux-arm64 ]]; then
    bash scripts/build.sh
fi
mkdir -p dist
python3 - <<'PY'
from pathlib import Path
import shutil

root = Path.cwd()
stage = root / '.cache/package'
# This is a generated staging directory. Start clean so removed or private
# files from a previous packaging run cannot leak into the release.
if stage.exists():
    shutil.rmtree(stage)
mac = stage / 'NodeMonitor-macOS-arm64'
(mac / 'build').mkdir(parents=True, exist_ok=True)
shutil.copytree(root / 'build/节点监控.app', mac / 'build/节点监控.app', dirs_exist_ok=True)
shutil.copy2(root / '.nodemon-project', mac / '.nodemon-project')
shutil.copy2(root / 'packaging/Mac-README.md', mac / 'README.md')
public_docs = ['部署与使用.md', '实现范围与协议.md']
public_screenshots = ['06-device-dashboard.jpg', '07-device-widget.jpg', '08-device-popover.jpg', '09-device-widget-compact.jpg']
(mac / 'docs/screenshots').mkdir(parents=True)
for name in public_docs:
    shutil.copy2(root / 'docs' / name, mac / 'docs' / name)
for name in public_screenshots:
    shutil.copy2(root / 'docs/screenshots' / name, mac / 'docs/screenshots' / name)
for arch in ('amd64', 'arm64'):
    dest = stage / ('nodemon-agent-linux-' + arch)
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(root / ('build/nodemon-agent-linux-' + arch), dest / 'nodemon-agent')
    shutil.copy2(root / '.nodemon-project', dest / '.nodemon-project')
    for file in (root / 'deploy').iterdir():
        if file.is_file(): shutil.copy2(file, dest / file.name)
    shutil.copy2(root / 'docs/部署与使用.md', dest / 'README.md')
    (dest / 'nodemon-agent').chmod(0o755)
    (dest / 'run-agent.sh').chmod(0o755)
PY
# Remove compiler debug paths from the distributed executable, then restore its
# ad hoc signature. The app in build/ is left untouched.
xcrun strip -S .cache/package/NodeMonitor-macOS-arm64/build/节点监控.app/Contents/MacOS/NodeMonitor
codesign --force --deep --sign - .cache/package/NodeMonitor-macOS-arm64/build/节点监控.app
codesign --verify --deep --strict .cache/package/NodeMonitor-macOS-arm64/build/节点监控.app
ditto -c -k --sequesterRsrc --keepParent .cache/package/NodeMonitor-macOS-arm64 dist/NodeMonitor-macOS-arm64.zip
for arch in amd64 arm64; do
    COPYFILE_DISABLE=1 tar -czf "dist/nodemon-agent-linux-$arch.tar.gz" -C .cache/package "nodemon-agent-linux-$arch"
done
python3 - <<'PY'
from pathlib import Path
import hashlib
import zipfile

root = Path.cwd()
public_docs = {
    Path('docs/部署与使用.md'), Path('docs/实现范围与协议.md'),
    *(Path('docs/screenshots') / name for name in (
        '06-device-dashboard.jpg', '07-device-widget.jpg',
        '08-device-popover.jpg', '09-device-widget-compact.jpg',
    )),
}
with zipfile.ZipFile(root / 'dist/NodeMonitor-source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in ('Sources', 'Agent', 'Tests', 'scripts', 'Tools', 'packaging', 'deploy', 'docs', 'design', 'README.md', 'LICENSE', 'Package.swift', '.nodemon-project', '.gitignore'):
        base = root / name
        files = sorted(base.rglob('*')) if base.is_dir() else [base]
        for path in files:
            relative = path.relative_to(root)
            if (path.is_file() and not path.name.startswith('._')
                    and (relative.parts[0] != 'docs' or relative in public_docs)):
                archive.write(path, Path('NodeMonitor-source') / relative)
files = sorted((root / 'dist').glob('*.zip')) + sorted((root / 'dist').glob('*.tar.gz'))
lines = [hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name for path in files]
(root / 'dist/SHA256SUMS').write_text('\n'.join(lines) + '\n')
print('Created portable macOS, Linux amd64 / arm64 and source packages in dist/')
PY
