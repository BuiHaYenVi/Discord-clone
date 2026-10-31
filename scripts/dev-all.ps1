<#
.SYNOPSIS
    One-command development launcher for Discord Web Clone (Oceanic Protocol Edition).
.DESCRIPTION
    Orchestrates the full multi-protocol stack:
    1. Verifies and downloads required pure-Java JARs via Chatserver/lib/download_driver.ps1
    2. Loads environment variables from .env (if present in project root)
    3. Compiles Chatserver Java sources into Chatserver/build/classes using javac
    4. Starts com.discordmini.server.ServerApplication (TCP :5000, UDP :5001, HTTP :8080, WS :8081)
    5. Starts Vite React frontend dev server (http://localhost:5173)
.PARAMETER FrontendOnly
    Start only the React Vite frontend on :5173.
.PARAMETER BackendOnly
    Compile and start only the Java Chatserver backend (:5000, :5001, :8080, :8081).
.PARAMETER CheckOnly
    Verify JARs, .env, Java compilation, and frontend dependencies without starting long-running servers.
.PARAMETER RunMigrations
    Optionally execute scripts/migrate.ps1 before launching the backend.
#>

param(
    [switch]$FrontendOnly = $false,
    [switch]$BackendOnly = $false,
    [switch]$CheckOnly = $false,
    [switch]$RunMigrations = $false
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$ChatServerDir = Join-Path $ProjectRoot "Chatserver"
$LibDir = Join-Path $ChatServerDir "lib"
$SrcDir = Join-Path $ChatServerDir "src"
$BuildClassesDir = Join-Path $ChatServerDir "build\classes"
$FrontendDir = Join-Path $ProjectRoot "frontend"
$EnvFile = Join-Path $ProjectRoot ".env"
$EnvExampleFile = Join-Path $ProjectRoot ".env.example"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Discord Web Clone (Oceanic Protocol) - Dev Stack Launcher" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# ------------------------------------------------------------
# 1. Load .env variables if present
# ------------------------------------------------------------
if (Test-Path $EnvFile) {
    Write-Host "[INFO] Loading environment variables from .env ..." -ForegroundColor Cyan
    $envLines = Get-Content $EnvFile
    foreach ($rawLine in $envLines) {
        if (-not $rawLine) { continue }
        $line = $rawLine.Trim()
        if (($line.Length -gt 0) -and (-not $line.StartsWith("#")) -and ($line.Contains("="))) {
            $parts = $line.Split("=", 2)
            $key = $parts[0].Trim()
            $val = $parts[1].Trim().Trim('"').Trim("'")
            if ($key.Length -gt 0) {
                [System.Environment]::SetEnvironmentVariable($key, $val, "Process")
            }
        }
    }
    Write-Host "[OK] Environment variables loaded from .env" -ForegroundColor Green
} else {
    Write-Host "[WARN] No .env file found at $EnvFile." -ForegroundColor Yellow
    if (Test-Path $EnvExampleFile) {
        Write-Host "[INFO] Tip: Copy .env.example to .env and configure your Neon PostgreSQL credentials." -ForegroundColor Yellow
    }
}

# ------------------------------------------------------------
# 2. Check & Download Required JARs + Compile Java Backend
# ------------------------------------------------------------
if (-not $FrontendOnly) {
    $requiredJarPatterns = @(
        "postgresql-*.jar",
        "Java-WebSocket-*.jar",
        "gson-*.jar",
        "slf4j-api-*.jar",
        "slf4j-nop-*.jar"
    )

    $missingJars = $false
    foreach ($pattern in $requiredJarPatterns) {
        $found = Get-ChildItem -Path $LibDir -Filter $pattern -ErrorAction SilentlyContinue
        if (-not $found) {
            $missingJars = $true
            break
        }
    }

    if ($missingJars) {
        $downloadScript = Join-Path $LibDir "download_driver.ps1"
        if (-not (Test-Path $downloadScript)) {
            Write-Host "[ERROR] Missing library downloader script: $downloadScript" -ForegroundColor Red
            exit 1
        }
        Write-Host "[...] Downloading missing JAR dependencies via Chatserver/lib/download_driver.ps1 ..." -ForegroundColor Cyan
        & powershell -ExecutionPolicy Bypass -File $downloadScript
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[ERROR] Failed to download required JAR files." -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "[OK] All required JAR libraries verified in Chatserver/lib/" -ForegroundColor Green
    }

    # Verify javac is available
    $javacCmd = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $javacCmd) {
        Write-Host "[ERROR] JDK 'javac' not found in PATH. Please install JDK 21+ and add it to PATH." -ForegroundColor Red
        exit 1
    }

    if (-not (Test-Path $BuildClassesDir)) {
        New-Item -ItemType Directory -Force -Path $BuildClassesDir | Out-Null
    }

    Write-Host "[...] Compiling Chatserver Java sources into Chatserver/build/classes ..." -ForegroundColor Cyan
    $javaFiles = Get-ChildItem -Path $SrcDir -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
    if ((-not $javaFiles) -or ($javaFiles.Count -eq 0)) {
        Write-Host "[ERROR] No Java source files found under $SrcDir" -ForegroundColor Red
        exit 1
    }

    $libWildcard = Join-Path $LibDir "*"
    & javac -encoding UTF-8 -cp "$libWildcard" -d "$BuildClassesDir" $javaFiles
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Java compilation failed." -ForegroundColor Red
        exit 1
    }
    Write-Host "[OK] Compiled $($javaFiles.Count) Java source files into Chatserver/build/classes" -ForegroundColor Green

    if ($RunMigrations) {
        $migrateScript = Join-Path $ScriptDir "migrate.ps1"
        if (Test-Path $migrateScript) {
            Write-Host "[...] Running database migrations via scripts/migrate.ps1 ..." -ForegroundColor Cyan
            & powershell -ExecutionPolicy Bypass -File $migrateScript
        }
    }
}

# ------------------------------------------------------------
# 3. Verify Frontend Dependencies
# ------------------------------------------------------------
if (-not $BackendOnly) {
    $packageJson = Join-Path $FrontendDir "package.json"
    if (-not (Test-Path $packageJson)) {
        Write-Host "[ERROR] Frontend package.json not found at $packageJson" -ForegroundColor Red
        exit 1
    }

    $nodeModules = Join-Path $FrontendDir "node_modules"
    if (-not (Test-Path $nodeModules)) {
        Write-Host "[...] Installing frontend npm dependencies ..." -ForegroundColor Cyan
        & npm --prefix $FrontendDir install
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[ERROR] npm install failed in frontend/" -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "[OK] Frontend node_modules verified" -ForegroundColor Green
    }
}

# ------------------------------------------------------------
# 4. Exit early if -CheckOnly
# ------------------------------------------------------------
if ($CheckOnly) {
    Write-Host "------------------------------------------------------------" -ForegroundColor Green
    Write-Host "[OK] Pre-flight check (-CheckOnly) passed! Stack is ready." -ForegroundColor Green
    Write-Host "  - TCP Server Port  : :5000 (Application Protocol v2)" -ForegroundColor Gray
    Write-Host "  - UDP Server Port  : :5001 (Typing / Presence / RTT Probe)" -ForegroundColor Gray
    Write-Host "  - HTTP REST Port   : :8080 (JWT Auth / History / Files)" -ForegroundColor Gray
    Write-Host "  - WebSocket Port   : :8081 (Real-time JSON Event Stream)" -ForegroundColor Gray
    Write-Host "  - Vite Web UI Port : :5173 (http://localhost:5173)" -ForegroundColor Gray
    Write-Host "------------------------------------------------------------" -ForegroundColor Green
    exit 0
}

# ------------------------------------------------------------
# 5. Launch Services
# ------------------------------------------------------------
$libClasspath = Join-Path $LibDir "*"
$fullClasspath = "$BuildClassesDir;$libClasspath"

if ($BackendOnly) {
    Write-Host "[INFO] Starting Chatserver in foreground (TCP :5000 | UDP :5001 | HTTP :8080 | WS :8081) ..." -ForegroundColor Green
    Push-Location $ProjectRoot
    try {
        & java -cp "$fullClasspath" com.discordmini.server.ServerApplication
    } finally {
        Pop-Location
    }
    exit 0
}

if ($FrontendOnly) {
    Write-Host "[INFO] Starting Vite Frontend dev server on http://localhost:5173 ..." -ForegroundColor Green
    & npm --prefix $FrontendDir run dev
    exit 0
}

# Full-stack mode: Start Java Backend in background process, then run Vite Frontend
Write-Host "[...] Starting Java Chatserver background process (TCP :5000, UDP :5001, HTTP :8080, WS :8081) ..." -ForegroundColor Cyan
$backendProc = Start-Process -FilePath "java" `
    -ArgumentList @("-cp", "`"$fullClasspath`"", "com.discordmini.server.ServerApplication") `
    -WorkingDirectory $ProjectRoot `
    -NoNewWindow `
    -PassThru

Start-Sleep -Seconds 2

if ($backendProc.HasExited) {
    Write-Host "[ERROR] Chatserver process exited prematurely (ExitCode: $($backendProc.ExitCode))." -ForegroundColor Red
    exit 1
}

Write-Host "[OK] Chatserver running (PID: $($backendProc.Id))" -ForegroundColor Green
Write-Host "[INFO] Starting Vite Frontend on http://localhost:5173 (Press Ctrl+C to stop both) ..." -ForegroundColor Green

try {
    & npm --prefix $FrontendDir run dev
} finally {
    if (($null -ne $backendProc) -and (-not $backendProc.HasExited)) {
        Write-Host "[INFO] Stopping Java Chatserver (PID: $($backendProc.Id)) ..." -ForegroundColor Yellow
        Stop-Process -Id $backendProc.Id -Force -ErrorAction SilentlyContinue
        Write-Host "[OK] Java Chatserver stopped." -ForegroundColor Green
    }
}
