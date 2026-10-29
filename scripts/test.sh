#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"
bash scripts/prepare-build.sh
mkdir -p .cache/integration
cd Agent
"$GO_BIN" test "${GO_TOOL_FLAGS[@]}" ./...
CGO_ENABLED=0 "$GO_BIN" build "${GO_TOOL_FLAGS[@]}" -trimpath -ldflags='-X main.buildStamp=portable-msync-v1' -o "$PROJECT_ROOT/.cache/integration/agent" ./cmd/nodemon-agent
cd "$PROJECT_ROOT"
READY="$PROJECT_ROOT/.cache/integration/ready.json"
TOKEN_PATH="$PROJECT_ROOT/.cache/integration/agent.token"
python3 - "$READY" <<'PY'
import pathlib, sys
pathlib.Path(sys.argv[1]).unlink(missing_ok=True)
PY
.cache/integration/agent --demo --listen 127.0.0.1:0 --loopback-listen 127.0.0.1:0 --ready-file "$READY" --token-file "$TOKEN_PATH" --interval 30s --gpu-interval 30s >.cache/integration/agent.log 2>&1 &
TEST_AGENT_PID=$!
trap 'kill "$TEST_AGENT_PID" 2>/dev/null || true; wait "$TEST_AGENT_PID" 2>/dev/null || true' EXIT
for attempt in {1..50}; do
    if [[ -f "$READY" ]]; then break; fi
    if ! kill -0 "$TEST_AGENT_PID" 2>/dev/null; then cat .cache/integration/agent.log; exit 1; fi
    sleep 0.1
done
export NODEMON_TEST_URL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["url"])' "$READY")"
export NODEMON_TEST_LOOPBACK_URL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["loopbackURL"])' "$READY")"
export NODEMON_TEST_TOKEN_FILE="$TOKEN_PATH"
swift run "${SWIFT_FLAGS[@]}" NodeMonitorChecks
