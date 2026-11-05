param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [string]$Serial = 'emulator-5580',
    [switch]$SkipApplicationBuild
)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
if (!$JavaHome) { $JavaHome=(Get-ChildItem -LiteralPath "$project\.tools\java" -Directory | Select-Object -First 1).FullName }
if (!$AndroidSdk) { $AndroidSdk="$project\.tools\android-sdk" }
$env:JAVA_HOME=$JavaHome
$bt=Join-Path $AndroidSdk 'build-tools\36.0.0'
$jar=Join-Path $AndroidSdk 'platforms\android-36\android.jar'
$adb=Join-Path $AndroidSdk 'platform-tools\adb.exe'
function Invoke-QATool([string]$Tool,[string[]]$Arguments) { & $Tool @Arguments; if($LASTEXITCODE -ne 0){throw "QA tool failed: $Tool ($LASTEXITCODE)"} }
$app="$project\build\android\NodeMonitor-UI-test.apk"
if(!$SkipApplicationBuild){ & "$PSScriptRoot\build-android.ps1" -JavaHome $JavaHome -AndroidSdk $AndroidSdk -ApplicationId cn.nodemon.serverdeck.test -WithoutNativeForTests -Output $app }
$compiled=Get-ChildItem "$project\.cache" -Directory -Filter 'android-build-*' | Where-Object {Test-Path -LiteralPath ($_.FullName+'\classes.jar')} | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if(!$compiled){throw 'No application classes.jar. Build the isolated test app first.'}
$work=Join-Path $project ('.cache\android-instrumentation-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path "$work\classes","$work\dex","$project\build\qa" | Out-Null
Invoke-QATool "$bt\aapt2.exe" @('link','-I',$jar,'--manifest',"$project\Android\tests\instrumentation\AndroidManifest.xml",'--min-sdk-version','26','--target-sdk-version','35','--debug-mode','-o',"$work\test-resources.apk")
$sources=@(Get-ChildItem "$project\Android\tests\instrumentation" -Filter *.java | ForEach-Object {$_.FullName})
Invoke-QATool "$JavaHome\bin\javac.exe" (@('-J-Duser.language=en','-encoding','UTF-8','-source','17','-target','17','-classpath',"$jar;$($compiled.FullName)\classes.jar",'-d',"$work\classes")+$sources)
Invoke-QATool "$JavaHome\bin\jar.exe" @('--create','--file',"$work\tests.jar",'-C',"$work\classes",'.')
Invoke-QATool "$bt\d8.bat" @('--min-api','26','--lib',$jar,'--classpath',"$($compiled.FullName)\classes.jar",'--output',"$work\dex","$work\tests.jar")
Copy-Item -LiteralPath "$work\test-resources.apk" -Destination "$work\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open("$work\unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$work\dex\classes.dex",'classes.dex')|Out-Null}finally{$zip.Dispose()}
Invoke-QATool "$bt\zipalign.exe" @('-f','4',"$work\unsigned.apk","$work\aligned.apk")
$testApk="$project\build\qa\NodeMonitor-instrumentation.apk"
Invoke-QATool "$bt\apksigner.bat" @('sign','--ks',"$project\.tools\android-signing\development.p12",'--ks-pass','pass:android','--key-pass','pass:android','--out',$testApk,"$work\aligned.apk")
$bootDeadline=(Get-Date).AddMinutes(3)
do {
    $boot=(& $adb -s $Serial shell getprop sys.boot_completed 2>$null) -join ''
    if($boot.Trim() -eq '1'){break}
    if((Get-Date) -gt $bootDeadline){throw 'Emulator did not finish booting within three minutes.'}
    Start-Sleep -Seconds 2
} while($true)
$emulated=((& $adb -s $Serial shell getprop ro.kernel.qemu) -join '').Trim()
if($emulated -ne '1'){throw 'This script only runs isolated fixture tests on an emulator.'}
# This isolated fixture package is reinstalled so prior launcher-owned test widgets cannot affect counts.
& $adb -s $Serial uninstall cn.nodemon.serverdeck.test 2>$null | Out-Null
Invoke-QATool $adb @('-s',$Serial,'install','-r',$app)
Invoke-QATool $adb @('-s',$Serial,'install','-r',$testApk)
Invoke-QATool $adb @('-s',$Serial,'shell','appwidget','grantbind','--package','cn.nodemon.serverdeck.test','--user','0')
try {$result=& $adb -s $Serial shell am instrument -w -r 'cn.nodemon.uitest/cn.nodemon.app.UiSmokeInstrumentation'}
finally { & $adb -s $Serial shell appwidget revokebind --package cn.nodemon.serverdeck.test --user 0 }
$result | Tee-Object -FilePath "$project\build\qa\android-instrumentation.txt"
if(($result -join "`n") -notmatch 'PASS:'){throw 'Device-side instrumentation failed. See build/qa/android-instrumentation.txt.'}
Invoke-QATool $adb @('-s',$Serial,'pull','/sdcard/Android/data/cn.nodemon.serverdeck.test/files/qa',"$project\build\qa")
Write-Output "Native Android visual QA: $project\build\qa\qa"
