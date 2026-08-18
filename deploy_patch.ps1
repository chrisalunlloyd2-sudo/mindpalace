# Ship a live patch to a RUNNING game (no restart, no rebuild)
# Usage:  powershell -ExecutionPolicy Bypass -File deploy_patch.ps1 -PatchFile C:\path\patch.json
param([string]$PatchFile = "patch.json", [string]$GameDir = (Get-Location))
$dest = Join-Path $GameDir "patches\patch.json"
New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
Copy-Item $PatchFile $dest -Force
Write-Host "Patch dropped -> $dest" -ForegroundColor Green
Write-Host "The game will show GAME PATCH LOADING within 8 seconds." -ForegroundColor Cyan
