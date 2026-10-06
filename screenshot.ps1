param(
    [string]$DeviceIp = "192.168.1.69",
    [int]$Port = 5555,
    [switch]$SkipConnect,
    [switch]$Open,
    [string]$Name
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $projectRoot "screenshots"

function Find-Adb {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
        "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) {
        return $found.Source
    }

    throw "adb.exe introuvable. Verifie Android SDK Platform Tools installe et dans PATH."
}

$adb = Find-Adb

if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

if (-not $SkipConnect) {
    Write-Host "[1/3] Connexion ADB sur Fire TV (${DeviceIp}:${Port})..."
    & $adb connect "${DeviceIp}:${Port}" | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$fileName = if ($Name) { "$Name.png" } else { "screen_$timestamp.png" }
$devicePath = "/sdcard/$fileName"
$localPath = Join-Path $outDir $fileName

Write-Host "[2/3] Capture de l'ecran..."
& $adb shell screencap -p $devicePath
if ($LASTEXITCODE -ne 0) {
    throw "Echec de la capture d'ecran. Verifie que l'appareil est connecte (adb devices)."
}

Write-Host "[3/3] Recuperation du fichier..."
& $adb pull $devicePath $localPath | Out-Null
& $adb shell rm $devicePath | Out-Null

if (-not (Test-Path $localPath)) {
    throw "La capture n'a pas pu etre recuperee."
}

Write-Host ""
Write-Host "TERMINE."
Write-Host "Screenshot: $localPath"

if ($Open) {
    Invoke-Item $localPath
}
