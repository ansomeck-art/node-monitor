#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
"$PROJECT_ROOT/scripts/prepare-build.sh"
DOTNET="${DOTNET:-$PROJECT_ROOT/.tools/dotnet/dotnet}"
if [[ ! -x "$DOTNET" ]]; then printf 'Install the official .NET 10 SDK inside .tools/dotnet or set DOTNET.\n' >&2; exit 1; fi
export DOTNET_CLI_HOME="$PROJECT_ROOT/.cache/dotnet-home" NUGET_PACKAGES="$PROJECT_ROOT/.cache/nuget" DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 DOTNET_CLI_TELEMETRY_OPTOUT=1
mkdir -p "$DOTNET_CLI_HOME" "$NUGET_PACKAGES" "$PROJECT_ROOT/build/windows-agent" "$PROJECT_ROOT/build/windows-client"
(
 cd "$PROJECT_ROOT/Agent"
 GOOS=windows GOARCH=amd64 CGO_ENABLED=0 "$GO_BIN" build "${GO_TOOL_FLAGS[@]}" -trimpath -o "$PROJECT_ROOT/build/windows-agent/nodemon-agent.exe" ./cmd/nodemon-agent
 GOOS=windows GOARCH=amd64 CGO_ENABLED=0 "$GO_BIN" build "${GO_TOOL_FLAGS[@]}" -trimpath -o "$PROJECT_ROOT/build/windows-client/nodemon-tailscale-bridge.exe" ./cmd/nodemon-tailscale-bridge
)
"$DOTNET" publish "$PROJECT_ROOT/Windows/NodeMonitor/NodeMonitor.csproj" -c Release -r win-x64 --self-contained true -o "$PROJECT_ROOT/build/windows-client" /p:BaseIntermediateOutputPath="$PROJECT_ROOT/.cache/windows-publish-obj/" /p:BaseOutputPath="$PROJECT_ROOT/.cache/windows-bin/"
cp "$PROJECT_ROOT/Windows/README.md" "$PROJECT_ROOT/build/windows-agent/README.md"
cp "$PROJECT_ROOT/Windows/README.md" "$PROJECT_ROOT/build/windows-client/README.md"
printf 'Windows agent: %s\nWindows client: %s\n' "$PROJECT_ROOT/build/windows-agent" "$PROJECT_ROOT/build/windows-client"
