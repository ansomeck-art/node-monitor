#!/bin/bash
# Source this file from project scripts; every build/cache/temp path is local.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PROJECT_ROOT
mkdir -p "$PROJECT_ROOT/.cache/tmp" "$PROJECT_ROOT/.cache/go-build" "$PROJECT_ROOT/.cache/gomod" "$PROJECT_ROOT/.cache/swift-modules" "$PROJECT_ROOT/.tools/gopath"
export TMPDIR="$PROJECT_ROOT/.cache/tmp/"
export GOCACHE="$PROJECT_ROOT/.cache/go-build"
export GOMODCACHE="$PROJECT_ROOT/.cache/gomod"
export GOPATH="$PROJECT_ROOT/.tools/gopath"
export GOTOOLCHAIN=local
export GOENV=off
export GOPROXY=off
export GOTELEMETRY=off
export TEST_TELEMETRY_DIR="$PROJECT_ROOT/.cache/go-telemetry"
export CLANG_MODULE_CACHE_PATH="$PROJECT_ROOT/.cache/clang-modules"
export SWIFT_MODULECACHE_PATH="$PROJECT_ROOT/.cache/swift-modules"
GO_BIN="${GO_BIN:-$PROJECT_ROOT/.tools/go/bin/go}"
export GO_BIN
GO_TOOL_FLAGS=(-toolexec "$PROJECT_ROOT/scripts/go-tool.sh")
SWIFT_FLAGS=(--package-path "$PROJECT_ROOT" --scratch-path "$PROJECT_ROOT/.build/swift" --cache-path "$PROJECT_ROOT/.cache/swiftpm" --config-path "$PROJECT_ROOT/.cache/swift-config" --security-path "$PROJECT_ROOT/.cache/swift-security" --manifest-cache local --disable-sandbox --disable-index-store -Xswiftc -module-cache-path -Xswiftc "$SWIFT_MODULECACHE_PATH" -j 4)
