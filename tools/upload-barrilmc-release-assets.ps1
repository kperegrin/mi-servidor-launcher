param(
    [string]$Repo = "kperegrin/mi-servidor-launcher",
    [string]$Tag = "v1.0",
    [string]$ModsDir = "C:\Users\xboxk\AppData\Roaming\ModrinthApp\profiles\BarrilMC\mods",
    [string]$ResourcepacksDir = "C:\Users\xboxk\AppData\Roaming\ModrinthApp\profiles\BarrilMC\resourcepacks",
    [string]$LauncherExe = "G:\launcher\HMCL\HMCL\build\libs\BarrilMC-Launcher-1.0.0.exe"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "GitHub CLI no esta instalado. Instala con: winget install --id GitHub.cli -e"
}

gh auth status | Out-Host

if (-not (gh release view $Tag --repo $Repo 2>$null)) {
    gh release create $Tag --repo $Repo --title "BarrilMC assets v1.0" --notes "Assets del pack BarrilMC Fabric 1.21.1."
}

$assets = @()
$assets += Get-ChildItem -LiteralPath $ModsDir -File |
    Where-Object { $_.Name -notmatch '\.disabled$' -and $_.Extension -ieq '.jar' } |
    Sort-Object Name
$assets += Get-ChildItem -LiteralPath $ResourcepacksDir -File |
    Where-Object { $_.Extension -ieq '.zip' } |
    Sort-Object Name

if (Test-Path -LiteralPath $LauncherExe) {
    $assets += Get-Item -LiteralPath $LauncherExe
}

foreach ($asset in $assets) {
    Write-Host "Subiendo $($asset.Name)"
    gh release upload $Tag --repo $Repo --clobber -- "$($asset.FullName)"
}

Write-Host "Release subida: https://github.com/$Repo/releases/tag/$Tag"
