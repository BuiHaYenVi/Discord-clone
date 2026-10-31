@echo off
setlocal
echo ============================================================
echo DISCORD WEB CLONE - CHATSERVER LIBRARY DOWNLOADER
echo Downloading required JARs from Maven Central:
echo   - postgresql-42.7.3.jar
echo   - Java-WebSocket-1.5.7.jar
echo   - gson-2.11.0.jar
echo   - slf4j-api-2.0.13.jar
echo   - slf4j-nop-2.0.13.jar
echo ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0download_driver.ps1"
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Tai thu vien that bai! Ma loi: %ERRORLEVEL%
    exit /b %ERRORLEVEL%
)
echo [OK] Hoan tat tai thu vien cho Chatserver.
if /I "%1"=="--no-pause" exit /b 0
pause
