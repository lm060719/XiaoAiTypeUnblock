# Runs the module's DexKit fingerprint scan against target APKs on a connected
# Android device and prints what each fingerprint resolved to. This does not
# install APKs or change input-method settings.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string[]]$TargetApk,
    [string]$ModuleApk,
    [string]$SdkPath,
    [string]$AdbPath = 'adb',
    [string]$DeviceSerial,
    [string]$Platform = 'android-35',
    [string]$BuildTools = '35.0.0',
    [string]$Abi = 'arm64-v8a'
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
$targets = @($TargetApk | ForEach-Object { (Resolve-Path -LiteralPath $_).Path })
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
$output = Join-Path $projectRoot "work\fingerprint-probe-$taskId"
$remote = "/data/local/tmp/xatype-fingerprint-$taskId"
New-Item -ItemType Directory -Force "$output\classes", "$output\dex", "$output\lib" | Out-Null
function Invoke-Adb {
    # adb reports progress on stderr, which Windows PowerShell treats as an error.
    $ErrorActionPreference = 'Continue'
    $lines = @(& $adb -s $DeviceSerial @args 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $args`n$($lines -join "`n")" }
    $lines
}
& $javac --release 8 -nowarn -classpath $androidJar -d "$output\classes" (
    Join-Path $PSScriptRoot 'compat\HostSymbolsProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed.' }
& $d8 --lib $androidJar --output "$output\dex" "$output\classes\HostSymbolsProbe.class"
if ($LASTEXITCODE -ne 0) { throw 'd8 failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($moduleFile)
try {
    $libs = @($zip.Entries | Where-Object { $_.FullName -like "lib/$Abi/*.so" })
    if ($libs.Count -eq 0) { throw "Module APK has no native libraries for $Abi." }
    foreach ($entry in $libs) {
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path "$output\lib" $entry.Name), $true)
    }
} finally { $zip.Dispose() }

Invoke-Adb shell mkdir -p "$remote/lib" "$remote/work" | Out-Null
Invoke-Adb push "$output\dex\classes.dex" "$remote/probe.dex" | Out-Null
Invoke-Adb push $moduleFile "$remote/module.apk" | Out-Null
Get-ChildItem "$output\lib" | ForEach-Object { Invoke-Adb push $_.FullName "$remote/lib/$($_.Name)" | Out-Null }
$remoteTargets = @()
for ($i = 0; $i -lt $targets.Count; $i++) {
    $name = "target$i.apk"
    Invoke-Adb push $targets[$i] "$remote/$name" | Out-Null
    $remoteTargets += "$remote/$name"
    Write-Output "$name = $($targets[$i])"
}
Invoke-Adb shell chmod 444 "$remote/probe.dex" "$remote/module.apk" @remoteTargets | Out-Null
# Root is used solely to execute the probe and read these temporary files.
$command = "CLASSPATH=$remote/probe.dex:$remote/module.apk app_process /system/bin " +
    "HostSymbolsProbe $remote/lib $remote/work $($remoteTargets -join ' ')"
$result = @(Invoke-Adb shell su -c "'$command'")
# The probe ran as root, so its cache files need root to remove.
Invoke-Adb shell su -c "'rm -rf $remote'" | Out-Null
$result | Tee-Object -FilePath "$output\result.txt"
if ($result[-1] -ne 'DONE') { throw "Probe failed. See $output\result.txt" }
Write-Output "Evidence: $output"
