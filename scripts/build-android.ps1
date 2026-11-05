param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [string]$NativeLibrary = '',
    [string]$ApplicationId = 'cn.nodemon.app',
    [string]$Output = '',
    [switch]$WithoutNativeForTests
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (!$JavaHome) { $JavaHome = (Get-ChildItem -LiteralPath "$project\.tools\java" -Directory | Select-Object -First 1).FullName }
if (!$AndroidSdk) { $AndroidSdk = "$project\.tools\android-sdk" }
if (!$NativeLibrary) { $NativeLibrary = "$project\Android\app\src\main\jniLibs\arm64-v8a\libnodemon.so" }
if (!$Output) { $Output = "$project\build\android\NodeMonitor-ServerDeck-arm64.apk" }
if ($ApplicationId -notmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$') { throw 'Invalid application ID' }
$buildTools = Join-Path $AndroidSdk 'build-tools\36.0.0'
$androidJar = Join-Path $AndroidSdk 'platforms\android-36\android.jar'
foreach ($required in @("$JavaHome\bin\javac.exe", "$buildTools\aapt2.exe", "$buildTools\d8.bat", "$buildTools\apksigner.bat", $androidJar)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing tool: $required" }
}
if (!$WithoutNativeForTests -and !(Test-Path -LiteralPath $NativeLibrary)) { throw 'Build libnodemon.so using build-android.sh first, or pass -NativeLibrary with the unchanged ARM64 bridge library.' }
$env:JAVA_HOME = $JavaHome
function Invoke-BuildTool([string]$Tool, [string[]]$Arguments) {
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed: $Tool ($LASTEXITCODE)" }
}
# Unique workspace: builds never delete an existing checkout or another build's files.
$work = Join-Path $project ('.cache\android-build-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path "$work\generated", "$work\classes", "$work\dex", (Split-Path -Parent $Output) | Out-Null
Invoke-BuildTool "$buildTools\aapt2.exe" @('compile','--dir',"$project\Android\app\src\main\res",'-o',"$work\res.zip")
Invoke-BuildTool "$buildTools\aapt2.exe" @('link','-I',$androidJar,'--manifest',"$project\Android\app\src\main\AndroidManifest.xml",'--java',"$work\generated",'--custom-package','cn.nodemon.app','--rename-manifest-package',$ApplicationId,'--min-sdk-version','26','--target-sdk-version','35','--version-code','5','--version-name','0.3.2-widget-live-dev','--debug-mode','-o',"$work\resources.ap_","$work\res.zip")
$sources = @(Get-ChildItem "$project\Android\app\src\main\java", "$work\generated" -Recurse -Filter *.java | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
[IO.File]::WriteAllLines("$work\sources.txt", $sources, [Text.UTF8Encoding]::new($false))
Invoke-BuildTool "$JavaHome\bin\javac.exe" @('-J-Duser.language=en','-encoding','UTF-8','-source','17','-target','17','-classpath',$androidJar,'-d',"$work\classes", "@$work\sources.txt")
Invoke-BuildTool "$JavaHome\bin\jar.exe" @('--create','--file',"$work\classes.jar",'-C',"$work\classes",'.')
Invoke-BuildTool "$buildTools\d8.bat" @('--min-api','26','--lib',$androidJar,'--output',"$work\dex","$work\classes.jar")
Copy-Item -LiteralPath "$work\resources.ap_" -Destination "$work\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$work\unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$work\dex\classes.dex",'classes.dex') | Out-Null
    if (!$WithoutNativeForTests) { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$NativeLibrary,'lib/arm64-v8a/libnodemon.so') | Out-Null }
} finally { $zip.Dispose() }
Invoke-BuildTool "$buildTools\zipalign.exe" @('-f','-P','16','4',"$work\unsigned.apk","$work\aligned.apk")
$keyDir = Join-Path $project '.tools\android-signing'
New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
$key = "$keyDir\development.p12"
# Local development key only; never commit it or use it as a production signing key.
if (!(Test-Path -LiteralPath $key)) { Invoke-BuildTool "$JavaHome\bin\keytool.exe" @('-genkeypair','-keystore',$key,'-storetype','PKCS12','-storepass','android','-keypass','android','-alias','development','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Node Monitor Local Development','-noprompt') }
Invoke-BuildTool "$buildTools\apksigner.bat" @('sign','--ks',$key,'--ks-pass','pass:android','--key-pass','pass:android','--out',$Output,"$work\aligned.apk")
Invoke-BuildTool "$buildTools\apksigner.bat" @('verify','--verbose',$Output)
Write-Output "APK: $Output"
Write-Output "SHA256: $((Get-FileHash -LiteralPath $Output -Algorithm SHA256).Hash)"
