# Собирает sync-cli и кладёт jar в dist\ для коммита в репозиторий.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

& .\gradlew :sync-cli:jar
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

New-Item -ItemType Directory -Force -Path dist | Out-Null
Copy-Item -Force "sync-cli\build\libs\magic-sync.jar" "dist\magic-sync.jar"
Get-Item "dist\magic-sync.jar" | Format-List FullName, Length, LastWriteTime
Write-Host "Готово: dist\magic-sync.jar — можно коммитить."