# Runs reflection checks on real target DEX using a connected Android device.
# This does not install APKs or change input-method settings.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$TargetApk,
    [Parameter(Mandatory = $true)]
    [ValidateSet('LEGACY', 'V209', 'V21053')][string]$ExpectedProfile,
    [string]$ModuleApk,
    [string]$SdkPath,
    [string]$AdbPath = 'adb',
    [string]$DeviceSerial,
    [string]$Platform = 'android-35',
    [string]$BuildTools = '35.0.0'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$ModuleApk) { $ModuleApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk' }
if (!$SdkPath) {
    $sdkLine = Get-Content (Join-Path $projectRoot 'local.properties') |
        Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (!$sdkLine) { throw 'Pass -SdkPath for an Android SDK with the requested platform and build tools.' }
    $SdkPath = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
}
$targetFile = (Resolve-Path -LiteralPath $TargetApk).Path
$moduleFile = (Resolve-Path -LiteralPath $ModuleApk).Path
$androidJar = Join-Path $SdkPath "platforms\$Platform\android.jar"
$d8 = Join-Path $SdkPath "build-tools\$BuildTools\d8.bat"
foreach ($file in @($androidJar, $d8)) {
    if (!(Test-Path -LiteralPath $file)) { throw "Missing Android SDK tool: $file" }
}
$javac = (Get-Command javac -ErrorAction Stop).Source
$adb = (Get-Command $AdbPath -ErrorAction Stop).Source
if (!$DeviceSerial) {
    $devices = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device$' })
    if ($devices.Count -ne 1) { throw 'Connect one authorized device or pass -DeviceSerial.' }
    $DeviceSerial = ($devices[0] -split '\s+')[0]
}
if ($DeviceSerial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Invalid device serial.' }
$taskId = [Guid]::NewGuid().ToString('N')
$output = Join-Path $projectRoot "work\compat-probe-$taskId"
$remote = "/data/local/tmp/xatype-compat-$taskId"
New-Item -ItemType Directory -Force "$output\classes", "$output\dex" | Out-Null
function Invoke-Adb {
    & $adb -s $DeviceSerial @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $args" }
}
& $javac --release 8 -classpath $androidJar -d "$output\classes" (
    Join-Path $PSScriptRoot 'compat\TargetCompatibilityProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed.' }
& $d8 --lib $androidJar --output "$output\dex" "$output\classes\TargetCompatibilityProbe.class"
if ($LASTEXITCODE -ne 0) { throw 'd8 failed.' }
Invoke-Adb shell mkdir -p $remote
Invoke-Adb push "$output\dex\classes.dex" "$remote/probe.dex"
Invoke-Adb push $moduleFile "$remote/module.apk"
Invoke-Adb push $targetFile "$remote/target.apk"
Invoke-Adb shell chmod 444 "$remote/probe.dex" "$remote/module.apk" "$remote/target.apk"
# Android requires dynamically loaded DEX to be read-only. Root is used solely
# to execute the probe and read these temporary APKs on the authorized device.
$command = "CLASSPATH=$remote/probe.dex:$remote/module.apk app_process /system/bin TargetCompatibilityProbe $remote/target.apk $remote $ExpectedProfile"
$result = @(Invoke-Adb shell su -c $command)
$result | Tee-Object -FilePath "$output\result.txt"
if (!($result -match "^PASS profile=$ExpectedProfile checks=\d+$")) {
    throw "Probe failed. See $output\result.txt"
}
Write-Output "Evidence: $output"
