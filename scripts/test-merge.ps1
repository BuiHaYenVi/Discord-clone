param(
    [switch]$SkipFrontend,
    [ValidateRange(5, 120)][int]$TestTimeoutSeconds = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $ProjectRoot ('Chatserver/build/merge-tests/' + [guid]::NewGuid().ToString('N'))
$JavaCommand = (Get-Command java -ErrorAction Stop).Source
$JavacCommand = (Get-Command javac -ErrorAction Stop).Source
$LibClasspath = Join-Path $ProjectRoot 'Chatserver/lib/*'
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null

function Compile-Suite([string]$ModuleName, [string]$Classpath) {
    $moduleDir = Join-Path $ProjectRoot $ModuleName
    $classes = Join-Path $RunDir $ModuleName
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $sources = @(
        Get-ChildItem -LiteralPath (Join-Path $moduleDir 'src'), (Join-Path $moduleDir 'test') -Recurse -Filter '*.java' |
            ForEach-Object { $_.FullName }
    )
    & $JavacCommand --release 21 -encoding UTF-8 -cp $Classpath -d $classes $sources
    if ($LASTEXITCODE -ne 0) { throw "$ModuleName compilation failed." }
    return $classes
}

function Run-Test([string]$ClassName, [string]$Classpath) {
    $logName = $ClassName.Replace('.', '_')
    $stdout = Join-Path $RunDir ($logName + '.out.log')
    $stderr = Join-Path $RunDir ($logName + '.err.log')
    $headless = if ($ClassName.Contains('.ui.')) { 'false' } else { 'true' }
    $testProcess = Start-Process -FilePath $JavaCommand -WindowStyle Hidden -PassThru `
        -ArgumentList @("-Djava.awt.headless=$headless", '-cp', ('"' + $Classpath + '"'), $ClassName) `
        -WorkingDirectory $ProjectRoot -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $null = $testProcess.Handle
    if (-not $testProcess.WaitForExit($TestTimeoutSeconds * 1000)) {
        $testProcess.Kill()
        $testProcess.WaitForExit()
        throw "Test timed out: $ClassName. Logs: $RunDir"
    }
    $testProcess.Refresh()
    if ($testProcess.ExitCode -ne 0) {
        Get-Content -LiteralPath $stdout, $stderr | Select-Object -Last 80 | Write-Host
        throw "Test failed: $ClassName. Logs: $RunDir"
    }
    Write-Host "[PASS] $ClassName"
}

Push-Location $ProjectRoot
try {
    Write-Host '[INFO] Compiling Java sources and tests for Java 21.'
    $serverClasses = Compile-Suite 'Chatserver' $LibClasspath
    $clientClasses = Compile-Suite 'ChatClient' '.'
    $testCount = 0
    foreach ($module in @('Chatserver', 'ChatClient')) {
        $testDir = Join-Path $ProjectRoot ($module + '/test')
        $classpath = if ($module -eq 'Chatserver') { "$serverClasses;$LibClasspath" } else { $clientClasses }
        $tests = @(Get-ChildItem -LiteralPath $testDir -Recurse -Filter '*Test.java' |
            Where-Object { $_.Name -ne 'EndToEndWebAuthTest.java' } | Sort-Object FullName)
        foreach ($test in $tests) {
            $relative = $test.FullName.Substring($testDir.Length + 1)
            $className = $relative.Substring(0, $relative.Length - 5).Replace('\', '.').Replace('/', '.')
            Run-Test $className $classpath
            $testCount++
        }
    }
    Write-Host "[PASS] $testCount Java test programs. Logs: $RunDir"
    Write-Host '[SKIP] EndToEndWebAuthTest requires an explicitly configured live server and database.'
    if (-not $SkipFrontend) {
        $frontend = Join-Path $ProjectRoot 'frontend'
        & npm --prefix $frontend run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
        & npm --prefix $frontend run lint
        if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed.' }
    }
} finally {
    Pop-Location
}
