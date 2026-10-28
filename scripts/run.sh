#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"
APP="$PROJECT_ROOT/build/节点监控.app"
if [[ ! -x "$APP/Contents/MacOS/NodeMonitor" ]]; then "$PROJECT_ROOT/scripts/build.sh"; fi
open "$APP" --args --show-window "$@"
