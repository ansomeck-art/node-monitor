param([string]$Dotnet = '', [string]$Go = '')
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (!$Dotnet) { $Dotnet = Join-Path $project '.tools\dotnet\dotnet.exe' }
if (!$Go) { $Go = Join-Path $project '.tools\go\bin\go.exe' }
foreach ($tool in @($Dotnet,$Go)) { if (!(Test-Path -LiteralPath $tool)) { throw "Missing build tool: $tool" } }
$env:DOTNET_ROOT = Split-Path -Parent $Dotnet
$env:DOTNET_CLI_HOME = Join-Path $project '.cache\dotnet-home'
$env:NUGET_PACKAGES = Join-Path $project '.cache\nuget'
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:GOTOOLCHAIN = 'local'
$env:GOCACHE = Join-Path $project '.cache\go-build'
$env:GOMODCACHE = Join-Path $project '.cache\go-mod'
$env:GOOS = 'windows'
$env:GOARCH = 'amd64'
$env:CGO_ENABLED = '0'
$client = Join-Path $project 'build\windows-client'
$agent = Join-Path $project 'build\windows-agent'
New-Item -ItemType Directory -Force -Path $client,$agent,$env:DOTNET_CLI_HOME | Out-Null
Push-Location (Join-Path $project 'Agent')
try {
    & $Go build -tags=ts_omit_logtail -trimpath '-ldflags=-s -w' -o "$agent\nodemon-agent.exe" ./cmd/nodemon-agent
    if ($LASTEXITCODE -ne 0) { throw 'Windows agent build failed' }
    & $Go build -tags=ts_omit_logtail -trimpath '-ldflags=-s -w' -o "$client\nodemon-tailscale-bridge.exe" ./cmd/nodemon-tailscale-bridge
    if ($LASTEXITCODE -ne 0) { throw 'Windows remote bridge build failed' }
} finally { Pop-Location }
& $Dotnet publish "$project\Windows\NodeMonitor\NodeMonitor.csproj" -c Release -r win-x64 --self-contained true -o $client "/p:BaseIntermediateOutputPath=$project\.cache\windows-publish-obj\" "/p:BaseOutputPath=$project\.cache\windows-bin\"
if ($LASTEXITCODE -ne 0) { throw 'Windows client publish failed' }
Copy-Item -LiteralPath "$project\Windows\README.md" -Destination "$client\README.md"
Copy-Item -LiteralPath "$project\Windows\README.md" -Destination "$agent\README.md"
Copy-Item -LiteralPath "$project\LICENSE" -Destination "$client\LICENSE"
Copy-Item -LiteralPath "$project\LICENSE" -Destination "$agent\LICENSE"
Write-Output "Windows client: $client\NodeMonitor.exe"
Write-Output "Windows agent: $agent\nodemon-agent.exe"
