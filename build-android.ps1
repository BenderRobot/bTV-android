<#
.SYNOPSIS
    Compile l'APK bTV, l'envoie sur GitHub (commit + push) et, avec -Publish,
    le met en ligne dans une Release GitHub.

.DESCRIPTION
    Sans option : produit dist\bTV-AAAAMMJJ-HHMM.apk (debug, signé, installable),
    puis, si la compilation a réussi, commit toutes les modifications du code
    et les pousse sur GitHub (message : -Notes, sinon "bTV <version>").
    -NoPush : compile seulement, sans commit ni push.

    -Publish : crée en plus une Release sur GitHub avec l'APK en pièce jointe
    (nommée bTV.apk). Le lien permanent vers la dernière version est alors :
        https://github.com/<compte>/<dépôt>/releases/latest/download/bTV.apk

    Au premier -Publish, le script demande un jeton GitHub (Personal Access
    Token) et le garde chiffré pour ta session Windows dans
    %APPDATA%\bTV\github-token.txt (jamais dans le dépôt). -ResetToken pour
    en saisir un nouveau.

.EXAMPLE
    .\build-android.ps1
    .\build-android.ps1 -Publish
    .\build-android.ps1 -Publish -Notes "Correction du focus des saisons"
    .\build-android.ps1 -NoPush
#>
param(
    [switch]$Publish,
    [switch]$NoPush,
    [string]$Notes = "",
    [switch]$ResetToken
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradlew = Join-Path $projectRoot "gradlew.bat"
$apkPath = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$distDir = Join-Path $projectRoot "dist"
$tokenFile = Join-Path $env:APPDATA "bTV\github-token.txt"

# Gradle 8.9 ne fonctionne pas avec le Java 25 d'Android Studio : JDK 21 requis.
$jdk21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"

# --------------------------------------------------------------- compilation
if (-not (Test-Path $jdk21)) {
    throw "JDK 21 introuvable ($jdk21). Installe Eclipse Temurin 21 ou corrige le chemin en haut du script."
}

Write-Host "Compilation de l'APK..." -ForegroundColor Cyan
$env:JAVA_HOME = $jdk21
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"

# Version de l'appli = date de compilation, même texte que le tag de la Release.
$now = Get-Date
$version = $now.ToString("yyyy.MM.dd-HHmm")

Push-Location $projectRoot
try {
    & $gradlew assembleDebug "-Pbtv.version=$version" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "La compilation Gradle a échoué (voir les messages ci-dessus)." }
} finally {
    Pop-Location
}

if (-not (Test-Path $apkPath)) {
    throw "APK introuvable : $apkPath"
}

New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$distApk = Join-Path $distDir ("bTV-" + $now.ToString("yyyyMMdd-HHmm") + ".apk")
Copy-Item $apkPath $distApk -Force
$sizeMb = [math]::Round((Get-Item $distApk).Length / 1MB, 1)

Write-Host ""
Write-Host "APK prêt : $distApk ($sizeMb Mo) - version $version" -ForegroundColor Green

# --------------------------------------------------------------- commit + push
# Seulement après une compilation réussie : du code cassé ne part jamais sur GitHub.
# local.properties, dist\ et les clés de signature sont exclus par .gitignore.
if (-not $NoPush) {
    Write-Host ""
    Write-Host "Envoi du code sur GitHub..." -ForegroundColor Cyan
    $branch = (& git -C $projectRoot branch --show-current).Trim()
    if (-not $branch) { throw "Le dépôt n'est sur aucune branche (HEAD détaché) : rien n'a été poussé." }
    $pending = & git -C $projectRoot status --porcelain
    if ($pending) {
        $message = if ($Notes) { $Notes } else { "bTV $version" }
        & git -C $projectRoot add -A
        if ($LASTEXITCODE -ne 0) { throw "git add a échoué." }
        & git -C $projectRoot commit -m $message
        if ($LASTEXITCODE -ne 0) { throw "git commit a échoué (voir ci-dessus)." }
    } else {
        Write-Host "Aucune modification à commiter."
    }
    & git -C $projectRoot push origin $branch
    if ($LASTEXITCODE -ne 0) {
        if ($Publish) { throw "git push a échoué : la Release n'est pas publiée (elle doit correspondre au code en ligne)." }
        Write-Host "git push a échoué (voir ci-dessus) : le commit est fait, relance le script ou 'git push'." -ForegroundColor Yellow
    } else {
        Write-Host "Code poussé sur $branch." -ForegroundColor Green
    }
}

if (-not $Publish) { return }

# --------------------------------------------------------------- publication
Write-Host ""
Write-Host "Publication sur GitHub..." -ForegroundColor Cyan
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# Dépôt GitHub déduit du remote "origin" (https://github.com/<compte>/<dépôt>.git).
$origin = (& git -C $projectRoot remote get-url origin).Trim()
if ($origin -notmatch "github\.com[:/]([^/]+)/([^/]+?)(\.git)?$") {
    throw "Le remote origin ($origin) n'est pas un dépôt GitHub."
}
$owner = $Matches[1]
$repo = $Matches[2]

function Get-GitHubToken {
    if ($ResetToken -and (Test-Path $tokenFile)) { Remove-Item $tokenFile -Force }
    if (Test-Path $tokenFile) {
        $secure = Get-Content $tokenFile | ConvertTo-SecureString
    } else {
        Write-Host ""
        Write-Host "Jeton GitHub requis (une seule fois). Pour le créer, connecté à ton compte GitHub :" -ForegroundColor Yellow
        Write-Host "  https://github.com/settings/personal-access-tokens/new"
        Write-Host "  - Repository access : Only select repositories -> $owner/$repo"
        Write-Host "  - Permissions > Repository permissions > Contents : Read and write"
        Write-Host "  - Generate token, puis colle-le ci-dessous."
        Write-Host ""
        $secure = Read-Host "Jeton GitHub" -AsSecureString
        New-Item -ItemType Directory -Force -Path (Split-Path $tokenFile) | Out-Null
        # Chiffré avec ta session Windows (DPAPI) : illisible par un autre compte ou une autre machine.
        $secure | ConvertFrom-SecureString | Set-Content $tokenFile
    }
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$token = Get-GitHubToken
$headers = @{
    Authorization = "Bearer $token"
    Accept = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent" = "bTV-build-script"
}

$tag = "v$version"
$commit = (& git -C $projectRoot rev-parse --short HEAD).Trim()
$commitFull = (& git -C $projectRoot rev-parse HEAD).Trim()
$body = "APK Android de bTV (debug), compilé le " + $now.ToString("dd/MM/yyyy à HH:mm") + " depuis le commit $commit."
if ($Notes) { $body = "$Notes`n`n$body" }
$releaseRequest = @{
    tag_name = $tag
    # Le tag pointe sur le commit compilé (poussé juste avant), pas sur la tête de la branche.
    target_commitish = $commitFull
    name = "bTV " + $now.ToString("dd/MM/yyyy HH:mm")
    body = $body
    make_latest = "true"
} | ConvertTo-Json

try {
    $release = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$owner/$repo/releases" `
        -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($releaseRequest)) -ContentType "application/json; charset=utf-8"
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status -eq 401 -or $status -eq 403) {
        throw "GitHub refuse le jeton ($status). Vérifie ses droits (Contents : Read and write sur $owner/$repo) ou relance avec -ResetToken."
    }
    throw "Création de la Release impossible : $($_.Exception.Message)"
}

# L'APK est toujours nommé bTV.apk : le lien "latest" reste le même d'une version à l'autre.
$uploadUrl = $release.upload_url -replace "\{.*\}$", ""
$null = Invoke-RestMethod -Method Post -Uri "${uploadUrl}?name=bTV.apk" -Headers $headers `
    -InFile $distApk -ContentType "application/vnd.android.package-archive"

Write-Host ""
Write-Host "Publié : $($release.html_url)" -ForegroundColor Green
Write-Host "Lien permanent (toujours la dernière version) :" -ForegroundColor Green
Write-Host "  https://github.com/$owner/$repo/releases/latest/download/bTV.apk"
