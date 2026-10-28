#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
if [[ ! -x "$GO_BIN" ]]; then python3 "$PROJECT_ROOT/scripts/bootstrap-go.py"; fi
if diskutil info "$PROJECT_ROOT" 2>/dev/null | rg -q 'ExFAT'; then
    TOOL_DIR="$("$GO_BIN" env GOTOOLDIR)"
    STAMP="$(shasum -a 256 "$TOOL_DIR/link" "$PROJECT_ROOT/Tools/linker-msync.c")"
    if [[ ! -f "$PROJECT_ROOT/.tools/exfat-linker/source.sha256" ]] || [[ "$(cat "$PROJECT_ROOT/.tools/exfat-linker/source.sha256")" != "$STAMP" ]]; then
        mkdir -p "$PROJECT_ROOT/.tools/exfat-linker"
        clang -dynamiclib "$PROJECT_ROOT/Tools/linker-msync.c" -o "$PROJECT_ROOT/.tools/linker-msync.dylib"
        cp "$TOOL_DIR/link" "$PROJECT_ROOT/.tools/exfat-linker/link"
        codesign --force --sign - "$PROJECT_ROOT/.tools/exfat-linker/link"
        printf '%s\n' "$STAMP" > "$PROJECT_ROOT/.tools/exfat-linker/source.sha256"
    fi
fi
