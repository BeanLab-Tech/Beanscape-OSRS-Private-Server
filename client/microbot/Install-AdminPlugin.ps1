<#
.SYNOPSIS
    Builds the Beanscape Admin plugin and installs it into the BeanLab Microbot client (owners only).

.DESCRIPTION
    - Builds client\microbot\admin-plugin with its Gradle wrapper (needs JDK 17+, the one you play with
      is fine) against the original Microbot 2.0.20 jar that Update-Client.ps1 downloads.
    - Copies BeanscapeAdminPlugin.jar into <ClientDir>\home\.runelite\microbot-plugins, where Microbot
      loads side-loaded plugins from.
    - Writes <ClientDir>\home\.runelite\beanscape-admin.json with the API address and your token.
      Get a token in game, as an owner, with  ::admintoken  (it is shown once).
    Close Microbot first, then start it with Play-Microbot.bat afterwards.
    Run it from a clone of the repo; it does not need Python.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File client\microbot\Install-AdminPlugin.ps1 -Token bsa_...
#>
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$ClientDir = "$env:USERPROFILE\Desktop\OSRS-Microbot-Client",
    [string]$ApiUrl = "https://runescape-server.tail5475a4.ts.net/adminapi",
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
$Token = $Token.Trim()
if ($Token -notmatch '^bsa_[0-9a-f]{64}$') {
    throw "That does not look like an admin token (bsa_ followed by 64 hex characters). Run ::admintoken in game as an owner."
}

$plugin = Join-Path $PSScriptRoot "admin-plugin"
$jar = Join-Path $plugin "build\libs\BeanscapeAdminPlugin.jar"
$microbot = Join-Path $ClientDir "original\microbot-2.0.20.jar"

# A full JDK 17+ is needed to compile. `java` on PATH is often an old JRE, so look for the Temurin
# JDK the README installs (newest first) unless JAVA_HOME already points at one.
function Find-Jdk {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { return $env:JAVA_HOME }
    $roots = @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft")
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $found = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^(jdk|microsoft)?-?(\d+)' -and [int]$Matches[2] -ge 17 -and (Test-Path "$($_.FullName)\bin\javac.exe") } |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

if (-not $SkipBuild) {
    if (-not (Test-Path $microbot)) {
        throw "Microbot 2.0.20 not found at $microbot. Run Update-Client.ps1 first (it downloads and checks it)."
    }
    $jdk = Find-Jdk
    if (-not $jdk) { throw "A JDK 17+ is required to build: winget install EclipseAdoptium.Temurin.21.JDK" }
    Write-Host "==> Building the plugin (JDK: $jdk)" -ForegroundColor Cyan
    $oldJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $jdk
    Push-Location $plugin
    try {
        & .\gradlew.bat --no-daemon -q jar "-PclientDir=$ClientDir"
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    } finally {
        Pop-Location
        $env:JAVA_HOME = $oldJavaHome
    }
}
if (-not (Test-Path $jar)) { throw "Plugin jar not found at $jar" }

$runelite = Join-Path $ClientDir "home\.runelite"
$plugins = Join-Path $runelite "microbot-plugins"
New-Item -ItemType Directory -Force $plugins | Out-Null
Copy-Item -Force $jar (Join-Path $plugins "BeanscapeAdminPlugin.jar")

$settings = @{ apiUrl = $ApiUrl.TrimEnd('/'); token = $Token } | ConvertTo-Json -Compress
Set-Content -Path (Join-Path $runelite "beanscape-admin.json") -Value $settings -Encoding UTF8 -NoNewline

Write-Host ""
Write-Host "Beanscape Admin installed. Start the client with Play-Microbot.bat and open the" -ForegroundColor Green
Write-Host "Beanscape Admin button in the side toolbar." -ForegroundColor Green
