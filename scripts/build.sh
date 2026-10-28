#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"
bash scripts/prepare-build.sh
mkdir -p build dist
cd Agent
"$GO_BIN" fmt ./...
CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 "$GO_BIN" build "${GO_TOOL_FLAGS[@]}" -trimpath -ldflags='-s -w -X main.buildStamp=portable-msync-v1' -o "$PROJECT_ROOT/build/nodemon-agent-demo" ./cmd/nodemon-agent
for arch in amd64 arm64; do
    CGO_ENABLED=0 GOOS=linux GOARCH="$arch" "$GO_BIN" build "${GO_TOOL_FLAGS[@]}" -trimpath -ldflags='-s -w -X main.buildStamp=portable-msync-v1' -o "$PROJECT_ROOT/build/nodemon-agent-linux-$arch" ./cmd/nodemon-agent
done
cd "$PROJECT_ROOT"
swift build "${SWIFT_FLAGS[@]}" -c release
BIN_DIR="$(swift build "${SWIFT_FLAGS[@]}" -c release --show-bin-path)"
APP="$PROJECT_ROOT/build/节点监控.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/NodeMonitor" "$APP/Contents/MacOS/NodeMonitor"
cp build/nodemon-agent-demo "$APP/Contents/Resources/nodemon-agent-demo"
cp packaging/Info.plist "$APP/Contents/Info.plist"
if [[ ! -f build/AppIcon.icns ]] || [[ Tools/MakeIcon.swift -nt build/AppIcon.icns ]]; then
    swift -module-cache-path "$SWIFT_MODULECACHE_PATH" Tools/MakeIcon.swift "$PROJECT_ROOT/build/AppIcon.iconset"
    iconutil -c icns build/AppIcon.iconset -o build/AppIcon.icns
fi
cp "$PROJECT_ROOT/build/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"
codesign --force --sign - "$APP/Contents/Resources/nodemon-agent-demo"
codesign --force --sign - "$APP"
printf 'Built: %s\n' "$APP"
