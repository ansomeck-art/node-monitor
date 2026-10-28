#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec ./nodemon-agent --listen 127.0.0.1:9274 --token-file ./data/agent.token "$@"
