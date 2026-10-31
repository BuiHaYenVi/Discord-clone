# Script tu dong tai cac thu vien Pure-Java tu Maven Central cho Chatserver
# Bao gom:
# 1. postgresql-42.7.3.jar (JDBC Driver cho PostgreSQL / Neon)
# 2. Java-WebSocket-1.5.7.jar (RFC 6455 WebSocket Server)
# 3. gson-2.11.0.jar (JSON Serialization / Deserialization)
# 4. slf4j-api-2.0.13.jar (Required by Java-WebSocket)
# 5. slf4j-nop-2.0.13.jar (NOP Logger binding cho SLF4J)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$libraries = @(
    @{
        Name = "postgresql-42.7.3.jar"
        Url  = "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.3/postgresql-42.7.3.jar"
    },
    @{
        Name = "Java-WebSocket-1.5.7.jar"
        Url  = "https://repo1.maven.org/maven2/org/java-websocket/Java-WebSocket/1.5.7/Java-WebSocket-1.5.7.jar"
    },
    @{
        Name = "gson-2.11.0.jar"
        Url  = "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"
    },
    @{
        Name = "slf4j-api-2.0.13.jar"
        Url  = "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar"
    },
    @{
        Name = "slf4j-nop-2.0.13.jar"
        Url  = "https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar"
    }
)

$allSuccess = $true

foreach ($lib in $libraries) {
    $targetJar = Join-Path $scriptDir $lib.Name
    if (Test-Path $targetJar) {
        $size = (Get-Item $targetJar).Length
        if ($size -gt 1024) {
            Write-Host "[OK] Da ton tai: $($lib.Name) ($size bytes)" -ForegroundColor Green
            continue
        }
    }

    Write-Host "[...] Dang tai $($lib.Name) tu $($lib.Url) ..." -ForegroundColor Cyan
    try {
        Invoke-WebRequest -Uri $lib.Url -OutFile $targetJar -UseBasicParsing
        if (Test-Path $targetJar) {
            $size = (Get-Item $targetJar).Length
            Write-Host "[SUCCESS] Da tai thanh cong: $($lib.Name) ($size bytes)" -ForegroundColor Green
        } else {
            Write-Host "[ERROR] Khong the tai file: $($lib.Name)" -ForegroundColor Red
            $allSuccess = $false
        }
    } catch {
        Write-Host "[ERROR] Loi khi tai $($lib.Name): $_" -ForegroundColor Red
        $allSuccess = $false
    }
}

if (-not $allSuccess) {
    Write-Host "[FAILED] Mot so thu vien khong the tai ve. Vui long kiem tra ket noi mang!" -ForegroundColor Red
    exit 1
}

Write-Host "[DONE] Tat ca 5 thu vien JAR da san sang trong $scriptDir" -ForegroundColor Green
exit 0
