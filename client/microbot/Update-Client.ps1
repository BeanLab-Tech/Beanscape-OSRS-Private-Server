<#
.SYNOPSIS
    Installs or updates the BeanLab Microbot client. One double-click, no Python.

.DESCRIPTION
    Downloads the ALREADY-patched Microbot jar from the server (the server does the patching),
    verifies its SHA-256, installs Play-Microbot.bat and clears the client's stale HTTP cache.
    The laptop therefore needs only Java 21 and Tailscale - no Python, no original Microbot jar,
    no local patch step. Close Microbot first (the jar is locked while it runs), or pass -CloseRunning.

    The server publishes the current build number, version and the jar's SHA-256 at:
        <server>/runelite/client/CURRENT            build number (e.g. 4)
        <server>/runelite/client/<build>.txt        the real version (e.g. 2.6.65)
        <server>/runelite/microbot-jar.sha256       expected SHA-256 of the jar
        <server>/runelite/microbot-<version>-beanlab.jar   the patched jar itself

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File "$env:USERPROFILE\Desktop\OSRS-Microbot-Client\Update-Client.ps1"
#>
param(
    [string]$ClientDir = "$env:USERPROFILE\Desktop\OSRS-Microbot-Client",
    [string]$Server = "https://runescape-server.tail5475a4.ts.net",
    [switch]$CloseRunning
)
$ErrorActionPreference = "Stop"
$Server = $Server.TrimEnd('/')

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Fetch($url, $out) {
    Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
}
function FetchText($url) {
    (Invoke-WebRequest -Uri $url -UseBasicParsing).Content.Trim()
}

New-Item -ItemType Directory -Force $ClientDir | Out-Null

# 0. Microbot must not be running (Windows locks the jar). Find it by the jar name in the command line.
$running = Get-CimInstance Win32_Process -Filter "Name like 'java%'" |
    Where-Object { $_.CommandLine -like "*-beanlab.jar*" }
if ($running) {
    if (-not $CloseRunning) {
        Write-Host "Microbot is running. Close it (or rerun with -CloseRunning), then run this again." -ForegroundColor Yellow
        exit 1
    }
    Step "Closing Microbot"
    $running | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Start-Sleep 3
}

# 1. Ask the server what the current client is.
Step "Asking server for current client ($Server)"
$build   = (FetchText "$Server/runelite/client/CURRENT")
$version = (FetchText "$Server/runelite/client/$build.txt")
$sha     = (FetchText "$Server/runelite/microbot-jar.sha256").Split(' ')[0].ToUpper()
$jarName = "microbot-$version-beanlab.jar"
Write-Host "    current client: Microbot $version (build $build)"

# 2. Download the patched jar from the server and verify it.
Step "Downloading $jarName from server"
$jar = "$ClientDir\$jarName"
if (Test-Path $jar) { Remove-Item -Force $jar }
Fetch "$Server/runelite/$jarName" $jar
$actual = (Get-FileHash $jar -Algorithm SHA256).Hash
if ($actual -ne $sha) {
    Remove-Item -Force $jar
    throw "SHA-256 mismatch: server expected $sha but got $actual. Delete $jarName and rerun."
}
Write-Host "    verified: $actual"

# 3. Install the launcher and this updater (fetched from the repo so they track main).
Step "Installing launcher"
$raw = "https://raw.githubusercontent.com/BeanLab-Tech/Beanscape-OSRS-Private-Server/main/client/microbot"
Copy-Item -Force "$ClientDir\Play-Microbot.bat" "$ClientDir\Play-Microbot.bat.bak" -ErrorAction SilentlyContinue
try { Fetch "$raw/Play-Microbot.bat" "$ClientDir\Play-Microbot.bat" }
catch { if (-not (Test-Path "$ClientDir\Play-Microbot.bat")) { throw "launcher missing and repo fetch failed" } }
Set-Content -Path "$ClientDir\CLIENT_BUILD" -Value $build -NoNewline
Set-Content -Path "$ClientDir\CLIENT_VERSION" -Value $version -NoNewline
Copy-Item -Force "$PSCommandPath" "$ClientDir\Update-Client.ps1" -ErrorAction SilentlyContinue

# 4. Drop cached server config so the next launch fetches the current jav_config/world list.
Remove-Item -Recurse -Force "$ClientDir\home\.runelite\cache\okhttp" -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "BeanLab client is up to date (Microbot $version, build $build). Start it with Play-Microbot.bat." -ForegroundColor Green
