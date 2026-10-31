<#
.SYNOPSIS
    Applies PostgreSQL / Neon Serverless database migrations for Discord Mini Web Clone.
.DESCRIPTION
    Reads .env (or environment variables NEON_JDBC_URL / DB_URL, NEON_DB_USER / DB_USER, NEON_DB_PASSWORD / DB_PASSWORD)
    and executes Base Schema + Chatserver/sql/V003__guilds_channels.sql idempotently
    via com.discordmini.server.config.DatabaseConnection.
#>

param(
    [switch]$IncludeBaseSchema = $false
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $ProjectRoot ".env"

if (Test-Path $EnvFile) {
    Write-Host "[MIGRATE] Reading environment variables from $EnvFile ..." -ForegroundColor Cyan
    Get-Content $EnvFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $parts = $line.Split("=", 2)
            $key = $parts[0].Trim()
            $val = $parts[1].Trim().Trim('"').Trim("'")
            [System.Environment]::SetEnvironmentVariable($key, $val, "Process")
        }
    }
}

$V003File = Join-Path $ProjectRoot "Chatserver\sql\V003__guilds_channels.sql"
if (-not (Test-Path $V003File)) {
    throw "Migration file not found: $V003File"
}

Write-Host "[MIGRATE] Running Base Schema + V003__guilds_channels.sql via Java JDBC DatabaseConnection ..." -ForegroundColor Green
$ChatServerDir = Join-Path $ProjectRoot "Chatserver"
$BuildClasses  = Join-Path $ChatServerDir "build\classes"
$LibPath       = Join-Path $ChatServerDir "lib\*"

if (-not (Test-Path $BuildClasses)) {
    New-Item -ItemType Directory -Force -Path $BuildClasses | Out-Null
}

& javac -encoding UTF-8 -cp "$LibPath" -d "$BuildClasses" `
    (Join-Path $ChatServerDir "src\com\discordmini\server\config\DatabaseConnection.java")
if ($LASTEXITCODE -ne 0) {
    Write-Host "[MIGRATE ERROR] Failed to compile DatabaseConnection.java" -ForegroundColor Red
    exit 1
}

Push-Location $ProjectRoot
try {
    & java -cp "$BuildClasses;$LibPath" com.discordmini.server.config.DatabaseConnection
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}
