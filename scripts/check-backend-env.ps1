[CmdletBinding()]
param(
    [switch]$RequireDocker,
    [switch]$RunGradle
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$script:passed = 0
$script:warned = 0
$script:failed = 0

function Write-Check {
    param([string]$Level, [string]$Name, [string]$Detail)
    switch ($Level) {
        'PASS' { $script:passed++ }
        'WARN' { $script:warned++ }
        'FAIL' { $script:failed++ }
    }
    Write-Output ('[{0}] {1}: {2}' -f $Level, $Name, $Detail)
}

function Invoke-Probe {
    param([string]$Command, [string]$Arguments)
    $tool = Get-Command $Command -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $tool) {
        return [pscustomobject]@{ ExitCode = -1; Text = 'command not installed' }
    }
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $process.StartInfo.FileName = $tool.Source
    $process.StartInfo.Arguments = $Arguments
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        [void]$process.Start()
        # Version/info probes have small bounded output; never print environment or .env values.
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        return [pscustomobject]@{ ExitCode = $process.ExitCode; Text = ($stdout + "`n" + $stderr).Trim() }
    }
    catch {
        return [pscustomobject]@{ ExitCode = -1; Text = 'could not run command' }
    }
    finally {
        $process.Dispose()
    }
}

Write-Output 'Come-down backend environment check (does not change system settings)'

$java = Invoke-Probe 'java' '-version'
if ($java.ExitCode -eq 0 -and $java.Text -match 'version "21(?:\.|"|-)') {
    Write-Check 'PASS' 'JDK' (($java.Text -split "`r?`n")[0] + ' (required major: 21; compare vendor/patch with teammate)')
}
else {
    Write-Check 'FAIL' 'JDK' 'Java 21 is required. Set JAVA_HOME, PATH, IDE SDK and Gradle JVM consistently.'
}

foreach ($requiredFile in @('build.gradle', 'gradle/wrapper/gradle-wrapper.properties', 'docker-compose.yml')) {
    if (-not (Test-Path -LiteralPath (Join-Path $workspaceRoot $requiredFile))) {
        Write-Check 'FAIL' 'Project setup' ('Missing ' + $requiredFile + '. Generate the agreed backend project before this check.')
    }
}
if ($script:failed -gt 0) {
    Write-Output ('Summary: PASS={0} WARN={1} FAIL={2}' -f $script:passed, $script:warned, $script:failed)
    exit 1
}

$build = Get-Content -LiteralPath (Join-Path $workspaceRoot 'build.gradle') -Raw
$wrapper = Get-Content -LiteralPath (Join-Path $workspaceRoot 'gradle/wrapper/gradle-wrapper.properties') -Raw
$compose = Get-Content -LiteralPath (Join-Path $workspaceRoot 'docker-compose.yml') -Raw

$checks = @(
    @('Java toolchain', ($build -match 'JavaLanguageVersion\.of\(21\)'), '21'),
    @('Spring Boot', ($build -match "id 'org.springframework.boot' version '4\.1\.2'"), '4.1.2'),
    @('Dependency management plugin', ($build -match "id 'io.spring.dependency-management' version '1\.1\.7'"), '1.1.7'),
    @('Gradle Wrapper configuration', ($wrapper -match 'gradle-9\.8\.2-bin\.zip'), '9.8.2')
)
foreach ($check in $checks) {
    $level = if ($check[1]) { 'PASS' } else { 'FAIL' }
    Write-Check $level $check[0] ('expected ' + $check[2])
}

$composeImage = [regex]::Match($compose, '(?m)^\s*image:\s*["'']?([^\s"''#]+)').Groups[1].Value
if ($composeImage -eq 'mysql:8.4') {
    Write-Check 'PASS' 'MySQL configuration' 'compose uses mysql:8.4; configure the new DB test fixture with the same image'
    Write-Check 'WARN' 'MySQL image reproducibility' '8.4 is a floating tag; patch/digest equality is not verified.'
}
elseif ($composeImage.StartsWith('mysql@sha256:')) {
    Write-Check 'WARN' 'MySQL digest configuration' 'digest found; verify the agreed 8.4 image, test fixture and checker baseline in the same PR.'
}
else {
    Write-Check 'FAIL' 'MySQL configuration' 'compose image differs from the agreed baseline.'
}

$git = Invoke-Probe 'git' '--version'
if ($git.ExitCode -eq 0) { Write-Check 'PASS' 'Git' $git.Text }
else { Write-Check 'FAIL' 'Git' 'Install Git and add it to PATH.' }

$docker = Invoke-Probe 'docker' '--version'
if ($docker.ExitCode -eq 0) { Write-Check 'PASS' 'Docker client' $docker.Text }
else { Write-Check 'FAIL' 'Docker client' 'Install Docker with the Compose plugin.' }

$dockerCompose = Invoke-Probe 'docker' 'compose version'
if ($dockerCompose.ExitCode -eq 0) { Write-Check 'PASS' 'Docker Compose' $dockerCompose.Text }
else { Write-Check 'FAIL' 'Docker Compose' 'The docker compose command is required.' }

$engine = Invoke-Probe 'docker' 'info --format "{{.ServerVersion}}"'
if ($engine.ExitCode -eq 0) { Write-Check 'PASS' 'Docker engine' $engine.Text }
else {
    $level = if ($RequireDocker) { 'FAIL' } else { 'WARN' }
    Write-Check $level 'Docker engine' 'Not reachable. Start Docker before running DB/integration checks. Integration tests may skip.'
}

if (Test-Path -LiteralPath (Join-Path $workspaceRoot '.env')) {
    Write-Check 'PASS' 'Local configuration' '.env exists; contents were not read or validated.'
}
else {
    Write-Check 'WARN' 'Local configuration' 'Create .env from .env.example for real OAuth login (not required for unit tests).'
}

if ($RunGradle) {
    Push-Location $workspaceRoot
    try {
        # Explicit opt-in: this can populate the Gradle cache/download the wrapper distribution.
        if ($env:OS -eq 'Windows_NT') { $gradleOutput = & .\gradlew.bat --version 2>&1 }
        else { $gradleOutput = & ./gradlew --version 2>&1 }
        $gradleExit = $LASTEXITCODE
        $gradleText = ($gradleOutput | ForEach-Object { "$_" }) -join "`n"
        if ($gradleExit -eq 0 -and $gradleText -match '(?m)^Gradle 9\.8\.2\s*$' -and $gradleText -match 'Launcher JVM:\s+21(?:\.|\s)') {
            Write-Check 'PASS' 'Gradle runtime' 'Wrapper 9.8.2 launched with Java 21. Check daemon/toolchain settings separately in the IDE.'
        }
        else { Write-Check 'FAIL' 'Gradle runtime' 'Wrapper execution failed or version/JVM differs. Run gradlew --version manually.' }
    }
    catch { Write-Check 'FAIL' 'Gradle runtime' 'Could not execute wrapper. Run gradlew --version manually.' }
    finally { Pop-Location }
}

Write-Output ('Summary: PASS={0} WARN={1} FAIL={2}' -f $script:passed, $script:warned, $script:failed)
if ($script:failed -gt 0) { exit 1 }
exit 0
