# Синхронизация хранилища на Windows. Параметры из .env в корне репо.
# Нужны: Java 17+ и dist\magic-sync.jar (или локальная сборка).
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $Root ".env"
$DistJar = Join-Path $Root "dist\magic-sync.jar"
$BuildJar = Join-Path $Root "sync-cli\build\libs\magic-sync.jar"

if (-not (Test-Path $EnvFile)) {
    throw "нет $EnvFile — скопируйте .env.example"
}

$Jar = if (Test-Path $DistJar) { $DistJar } elseif (Test-Path $BuildJar) { $BuildJar } else {
    throw "нет jar: положите dist\magic-sync.jar или соберите .\gradlew :sync-cli:jar"
}

Get-Command java -ErrorAction Stop | Out-Null

$vars = @{}
Get-Content $EnvFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    $eq = $line.IndexOf("=")
    if ($eq -lt 1) { return }
    $key = $line.Substring(0, $eq).Trim()
    $val = $line.Substring($eq + 1).Trim()
    if (($val.StartsWith('"') -and $val.EndsWith('"')) -or ($val.StartsWith("'") -and $val.EndsWith("'"))) {
        $val = $val.Substring(1, $val.Length - 2)
    }
    $vars[$key] = $val
}

function Require([string]$name) {
    if (-not $vars.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($vars[$name])) {
        throw "укажите $name в .env"
    }
    return $vars[$name]
}

$server = Require "SYNC_SERVER"
$vault = Require "SYNC_VAULT"
$keyPath = Require "SYNC_KEY"
$device = Require "SYNC_DEVICE"
$token = Require "MAGIC_SYNC_TOKEN"

if ($keyPath.StartsWith("~/") -or $keyPath.StartsWith("~\")) {
    $keyPath = Join-Path $HOME $keyPath.Substring(2)
} elseif ($keyPath -eq "~") {
    $keyPath = $HOME
}

if (-not (Test-Path $keyPath)) { throw "нет $keyPath" }

$extra = @()
if ($args -contains "--allow-mass-delete" -or $vars["SYNC_ALLOW_MASS_DELETE"] -eq "1") {
    $extra += "--allow-mass-delete"
}

& java -jar $Jar sync `
    --vault $vault `
    --server $server `
    --token $token `
    --key $keyPath `
    --device $device `
    @extra
exit $LASTEXITCODE