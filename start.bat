@echo off
setlocal EnableDelayedExpansion

:: ============================================================================
:: DISCORD WEB CLONE (OCEANIC PROTOCOL) - MULTI-OPTION LAUNCHER (start.bat)
:: ============================================================================

set "ROOT_DIR=%~dp0"
if "%ROOT_DIR:~-1%"=="\" set "ROOT_DIR=%ROOT_DIR:~0,-1%"

if not "%~1"=="" (
    set "ARG=%~1"
    if /I "!ARG!"=="1" goto :OPT_ALL
    if /I "!ARG!"=="all" goto :OPT_ALL
    if /I "!ARG!"=="dev" goto :OPT_ALL
    if /I "!ARG!"=="2" goto :OPT_ALL_MIGRATE
    if /I "!ARG!"=="all-migrate" goto :OPT_ALL_MIGRATE
    if /I "!ARG!"=="3" goto :OPT_BACKEND
    if /I "!ARG!"=="backend" goto :OPT_BACKEND
    if /I "!ARG!"=="server" goto :OPT_BACKEND
    if /I "!ARG!"=="4" goto :OPT_FRONTEND
    if /I "!ARG!"=="frontend" goto :OPT_FRONTEND
    if /I "!ARG!"=="web" goto :OPT_FRONTEND
    if /I "!ARG!"=="5" goto :OPT_SWING
    if /I "!ARG!"=="swing" goto :OPT_SWING
    if /I "!ARG!"=="client" goto :OPT_SWING
    if /I "!ARG!"=="6" goto :OPT_TEST
    if /I "!ARG!"=="test" goto :OPT_TEST
    if /I "!ARG!"=="7" goto :OPT_MIGRATE
    if /I "!ARG!"=="migrate" goto :OPT_MIGRATE
    if /I "!ARG!"=="8" goto :OPT_CHECK
    if /I "!ARG!"=="check" goto :OPT_CHECK
    if /I "!ARG!"=="9" goto :OPT_DEPS
    if /I "!ARG!"=="deps" goto :OPT_DEPS
    if /I "!ARG!"=="install" goto :OPT_DEPS
    if /I "!ARG!"=="install-deps" goto :OPT_DEPS
    if /I "!ARG!"=="dependencies" goto :OPT_DEPS
    if /I "!ARG!"=="help" goto :SHOW_HELP
    if /I "!ARG!"=="--help" goto :SHOW_HELP
    if /I "!ARG!"=="-h" goto :SHOW_HELP
    echo [ERROR] Invalid option: "%~1"
    goto :SHOW_HELP
)

:MENU
cls
echo ============================================================================
echo   DISCORD MINI / WEB CLONE (OCEANIC PROTOCOL) - STARTUP MENU
echo   TCP :5000 ^| UDP :5001 ^| HTTP REST :8080 ^| WebSocket :8081 ^| Web UI :5173
echo ============================================================================
echo.
echo   [1] Full Stack (Recommended: Java Backend + React Web UI :5173)
echo   [2] Full Stack + Auto Run SQL Migration (V003) before start
echo   [3] Java Backend Only (Chatserver: TCP :5000, UDP :5001, HTTP :8080, WS :8081)
echo   [4] React Web Frontend Only (Vite :5173 - Includes 4-Member Demo Mode)
echo   [5] Launch Java Swing Desktop Client (ChatClient -> TCP :5000)
echo   [6] Run Automated Test Suite (84 Assertions: Protocol v2, JWT, UDP, RFC 6455)
echo   [7] Run PostgreSQL / Neon Database Migration (scripts\migrate.ps1)
echo   [8] Download JARs ^& Verify Full System Build (Pre-flight Check)
echo   [9] Install All Dependencies (Frontend npm install + Backend Java JARs)
echo   [0] Exit
echo.
echo ============================================================================
set "CHOICE="
set /p "CHOICE=Select option (0-9, default [1]): "
if "%CHOICE%"=="" set "CHOICE=1"

if "%CHOICE%"=="1" goto :OPT_ALL
if "%CHOICE%"=="2" goto :OPT_ALL_MIGRATE
if "%CHOICE%"=="3" goto :OPT_BACKEND
if "%CHOICE%"=="4" goto :OPT_FRONTEND
if "%CHOICE%"=="5" goto :OPT_SWING
if "%CHOICE%"=="6" goto :OPT_TEST
if "%CHOICE%"=="7" goto :OPT_MIGRATE
if "%CHOICE%"=="8" goto :OPT_CHECK
if "%CHOICE%"=="9" goto :OPT_DEPS
if "%CHOICE%"=="0" exit /b 0

echo.
echo [WARN] Invalid choice "%CHOICE%". Please select 0-9.
timeout /t 2 >nul
goto :MENU

:OPT_ALL
echo.
echo [1] Starting Full Stack (Java Backend + React Web Frontend)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT_DIR%\scripts\dev-all.ps1"
goto :AFTER_RUN

:OPT_ALL_MIGRATE
echo.
echo [2] Running SQL Migration and starting Full Stack...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT_DIR%\scripts\dev-all.ps1" -RunMigrations
goto :AFTER_RUN

:OPT_BACKEND
echo.
echo [3] Starting Java Chatserver (TCP :5000 ^| UDP :5001 ^| HTTP :8080 ^| WS :8081)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT_DIR%\scripts\dev-all.ps1" -BackendOnly
goto :AFTER_RUN

:OPT_FRONTEND
echo.
echo [4] Starting React Web Frontend at http://localhost:5173 ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT_DIR%\scripts\dev-all.ps1" -FrontendOnly
goto :AFTER_RUN

:OPT_SWING
echo.
echo [5] Compiling and launching Java Swing Desktop Client (ChatClient)...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$root = '%ROOT_DIR%'; $out = Join-Path $root 'ChatClient\build\classes'; New-Item -ItemType Directory -Force $out | Out-Null; $files = Get-ChildItem -Path (Join-Path $root 'ChatClient\src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }; javac -encoding UTF-8 -d $out $files; if ($LASTEXITCODE -eq 0) { Write-Host '[OK] ChatClient compiled. Launching Swing window...' -ForegroundColor Green; Start-Process java -ArgumentList '-cp', $out, 'com.discordmini.client.ClientApplication' -WorkingDirectory (Join-Path $root 'ChatClient') } else { exit 1 }"
goto :AFTER_RUN

:OPT_TEST
echo.
echo [6] Compiling and running Automated Test Suite (ProtocolV2AndGatewayTest)...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$root = '%ROOT_DIR%'; $jars = (Get-ChildItem (Join-Path $root 'Chatserver\lib\*.jar') | ForEach-Object { $_.FullName }) -join ';'; $out = Join-Path $root 'Chatserver\build\classes'; New-Item -ItemType Directory -Force $out | Out-Null; $sources = Get-ChildItem -Recurse (Join-Path $root 'Chatserver\src'),(Join-Path $root 'Chatserver\test') -Filter *.java | ForEach-Object { $_.FullName }; javac -encoding UTF-8 -cp $jars -d $out $sources; if ($LASTEXITCODE -ne 0) { exit 1 }; java -cp ($jars + ';' + $out) com.discordmini.server.protocol.ProtocolV2AndGatewayTest"
goto :AFTER_RUN

:OPT_MIGRATE
echo.
echo [7] Running Database Migration (scripts\migrate.ps1)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT_DIR%\scripts\migrate.ps1"
goto :AFTER_RUN

:OPT_CHECK
echo.
echo [8] Verifying JAR libraries, Java Backend compilation, and Frontend dependencies...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT_DIR%\scripts\dev-all.ps1" -CheckOnly
goto :AFTER_RUN

:OPT_DEPS
echo.
echo [9] Installing all project dependencies (Frontend npm + Java Backend JARs)...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$root = '%ROOT_DIR%'; Write-Host '============================================================' -ForegroundColor Cyan; Write-Host ' [1/2] Downloading Backend Java JAR Libraries...' -ForegroundColor Cyan; Write-Host '============================================================' -ForegroundColor Cyan; $dl = Join-Path $root 'Chatserver\lib\download_driver.ps1'; if (Test-Path $dl) { & powershell -NoProfile -ExecutionPolicy Bypass -File $dl } else { Write-Host '[ERROR] Not found: ' $dl -ForegroundColor Red }; Write-Host ''; Write-Host '============================================================' -ForegroundColor Cyan; Write-Host ' [2/2] Installing Frontend npm Dependencies (npm install)...' -ForegroundColor Cyan; Write-Host '============================================================' -ForegroundColor Cyan; $fe = Join-Path $root 'frontend'; if (Test-Path $fe) { & npm --prefix $fe install } else { Write-Host '[ERROR] Not found frontend directory' -ForegroundColor Red }; if ($LASTEXITCODE -eq 0) { Write-Host ''; Write-Host '[OK] All dependencies installed successfully!' -ForegroundColor Green } else { Write-Host ''; Write-Host '[ERROR] Dependency installation encountered errors.' -ForegroundColor Red; exit $LASTEXITCODE }"
goto :AFTER_RUN

:SHOW_HELP
echo.
echo Usage:
echo   start.bat                  Open interactive menu (options 0-9)
echo   start.bat all              Start Full Stack (Java Backend + React Web UI :5173)
echo   start.bat all-migrate      Run SQL Migration + Start Full Stack
echo   start.bat backend          Start Java Chatserver only (:5000, :5001, :8080, :8081)
echo   start.bat frontend         Start React Vite Frontend only (:5173)
echo   start.bat swing            Compile and launch Java Swing Desktop Client (ChatClient)
echo   start.bat test             Run 84-assertion automated test suite
echo   start.bat migrate          Run PostgreSQL / Neon SQL migrations (schema.sql + V003)
echo   start.bat check            Download JARs and verify full system compilation
echo   start.bat deps             Install all dependencies (Frontend npm + Java JARs)
echo   start.bat install          Alias for deps
echo.
exit /b 0

:AFTER_RUN
set "EXIT_CODE=%ERRORLEVEL%"
if "%~1"=="" (
    echo.
    pause
    goto :MENU
)
exit /b %EXIT_CODE%
