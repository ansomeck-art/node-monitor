#!/bin/bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$(basename "$1")" == "link" && -f "$PROJECT_ROOT/.tools/linker-msync.dylib" && -x "$PROJECT_ROOT/.tools/exfat-linker/link" ]]; then
    export DYLD_INSERT_LIBRARIES="$PROJECT_ROOT/.tools/linker-msync.dylib"
    shift
    exec "$PROJECT_ROOT/.tools/exfat-linker/link" "$@"
fi
exec "$@"
