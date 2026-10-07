param(
    [string]$Message
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot

git -C $projectRoot rev-parse --show-toplevel *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Ce dossier n'est pas un dépôt Git."
}

$remoteUrl = git -C $projectRoot remote get-url origin 2>$null
if ($LASTEXITCODE -ne 0 -or -not $remoteUrl) {
    throw "Le dépôt distant 'origin' n'est pas configuré."
}

$gitUser = git -C $projectRoot config user.name
$gitEmail = git -C $projectRoot config user.email
if (-not $gitUser -or -not $gitEmail) {
    throw "Identité Git manquante. Configure-la avec git config --global user.name et git config --global user.email."
}

$branch = git -C $projectRoot branch --show-current
if ($LASTEXITCODE -ne 0 -or -not $branch) {
    throw "Impossible de déterminer la branche Git courante."
}

git -C $projectRoot add --all
if ($LASTEXITCODE -ne 0) {
    throw "Échec de l'ajout des modifications à l'index Git."
}

git -C $projectRoot diff --cached --quiet
if ($LASTEXITCODE -eq 1) {
    $Message = Read-Host "Titre du commit"
    if ([string]::IsNullOrWhiteSpace($Message)) {
        throw "Le titre du commit ne peut pas être vide."
    }

    git -C $projectRoot commit -m $Message
    if ($LASTEXITCODE -ne 0) {
        throw "Échec de la création du commit."
    }
}
elseif ($LASTEXITCODE -ne 0) {
    throw "Impossible de vérifier les modifications indexées."
}
else {
    Write-Host "Aucune nouvelle modification à committer."
}

git -C $projectRoot push --set-upstream origin $branch
if ($LASTEXITCODE -ne 0) {
    throw "Échec du push vers origin/$branch."
}
