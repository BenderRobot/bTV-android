param(
    [string]$DeviceIp = "192.168.1.69",
    [int]$Port = 5555,
    [switch]$SkipBuild,
    [switch]$OpenApp,
    [switch]$ShowLogs
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradlew = Join-Path $projectRoot "gradlew.bat"
$apkPath = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"

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

    throw "adb.exe introuvable. Vérifie Android SDK Platform Tools installé et dans PATH."
}

$adb = Find-Adb

Write-Host "[1/5] Projet: $projectRoot"
Write-Host "[2/5] Device: ${DeviceIp}:${Port}"

if (-not $SkipBuild) {
    Write-Host "[3/5] Build debug APK..."
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
    $env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"

    Push-Location $projectRoot
    & $gradlew assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Build Gradle échoué."
    }
    Pop-Location
}

if (-not (Test-Path $apkPath)) {
    throw "APK non trouvé: $apkPath"
}

Write-Host "[4/5] Connexion ADB sur Fire TV..."
& $adb kill-server
& $adb tcpip $Port
& $adb connect "${DeviceIp}:${Port}"
if ($LASTEXITCODE -ne 0) {
    throw "Connexion adb impossible vers ${DeviceIp}:${Port}. Vérifie le debug ADB activé et l'IP correcte."
}

& $adb devices

Write-Host "[5/5] Installation sur la Fire TV..."
& $adb install -r $apkPath
if ($LASTEXITCODE -ne 0) {
    throw "Installation APK échouée sur la Fire TV."
}

if ($OpenApp) {
    Write-Host "Ouverture de l'application..."
    & $adb shell am start -n com.btv/.MainActivity
}

if ($ShowLogs) {
    Write-Host "Affichage des logs BTV (Ctrl+C pour arrêter)..."
    & $adb logcat -s "Btv" "MainActivity" "Xtream" "Auth" "Catalog"
}

Write-Host ""
Write-Host "TERMINE."
Write-Host "APK: $apkPath"
Write-Host "App package: com.btv"
Write-Host "IP cible: ${DeviceIp}:${Port}"
Write-Host "Pour relancer directement: .\deploy-firetv.ps1 -DeviceIp $DeviceIp -Port $Port"
