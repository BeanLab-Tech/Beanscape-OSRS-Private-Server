<#
.SYNOPSIS
    Installs or updates the BeanLab Microbot client from GitHub main.

.DESCRIPTION
    - Downloads Microbot 2.0.20 into <ClientDir>\original if it is missing, and verifies its SHA-256.
    - Fetches the latest patch-microbot.py, Play-Microbot.bat, CLIENT_BUILD and SERVER_MODULUS from
      the repo, re-patches the jar, installs the launcher and clears the client's stale HTTP cache.
    - Keeps a copy of itself in <ClientDir> so the next update is one double-click/command away.
    Close Microbot first (the jar is locked while it runs), or pass -CloseRunning.
    Requires Python 3.10+ (for the patch) and Java 21 (to play).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File "$env:USERPROFILE\Desktop\OSRS-Microbot-Client\Update-Client.ps1"
#>
param(
    [string]$ClientDir = "$env:USERPROFILE\Desktop\OSRS-Microbot-Client",
    [string]$Ref = "main",
    [switch]$CloseRunning
)
$ErrorActionPreference = "Stop"
$Repo = "https://raw.githubusercontent.com/BeanLab-Tech/Beanscape-OSRS-Private-Server/$Ref/client/microbot"
$MicrobotUrl = "https://github.com/chsami/Microbot/releases/download/2.0.20/microbot-2.0.20.jar"
$MicrobotSha = "95A652D44AFE867B5B18879EF5DDF56EFB0F0A43B005E26C7018EAFDE8587DD6"
$Jar = "microbot-2.0.20-beanlab.jar"

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

# 0. Microbot must not be running from this folder (Windows locks the jar).
$running = Get-CimInstance Win32_Process -Filter "Name like 'java%'" |
    Where-Object { $_.CommandLine -like "*$Jar*" }
if ($running) {
    if (-not $CloseRunning) {
        Write-Host "Microbot is running. Close it (or rerun with -CloseRunning), then run this again." -ForegroundColor Yellow
        exit 1
    }
    Step "Closing Microbot"
    $running | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Start-Sleep 3
}

# Works in Windows PowerShell 5.1 and PowerShell 7 (no ?? operator).
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) { throw "Python 3.10+ is required: winget install Python.Python.3.12" }

New-Item -ItemType Directory -Force "$ClientDir\original", "$ClientDir\updater" | Out-Null

# 1. Original Microbot 2.0.20 (download once, always verify).
$original = "$ClientDir\original\microbot-2.0.20.jar"
if (-not (Test-Path $original)) {
    Step "Downloading Microbot 2.0.20"
    Invoke-WebRequest -Uri $MicrobotUrl -OutFile $original -UseBasicParsing
}
if ((Get-FileHash $original -Algorithm SHA256).Hash -ne $MicrobotSha) {
    throw "$original is not the expected Microbot 2.0.20 (SHA-256 mismatch). Delete it and rerun."
}

# 2. Latest client files from the repo.
Step "Fetching client files from GitHub ($Ref)"
foreach ($f in "patch-microbot.py", "Play-Microbot.bat", "CLIENT_BUILD", "SERVER_MODULUS", "Update-Client.ps1") {
    Invoke-WebRequest -Uri "$Repo/$f" -OutFile "$ClientDir\updater\$f" -UseBasicParsing
}
$build = (Get-Content "$ClientDir\updater\CLIENT_BUILD" -Raw).Trim()
$modulus = (Get-Content "$ClientDir\updater\SERVER_MODULUS" -Raw).Trim()
$installed = if (Test-Path "$ClientDir\CLIENT_BUILD") { (Get-Content "$ClientDir\CLIENT_BUILD" -Raw).Trim() } else { "none" }

# 3. Patch, then swap the jar in.
Step "Patching client build $build (installed: $installed)"
& $python.Source -I "$ClientDir\updater\patch-microbot.py" $original "$ClientDir\updater\$Jar" $modulus
if ($LASTEXITCODE -ne 0) { throw "patch-microbot.py failed" }
Move-Item -Force "$ClientDir\updater\$Jar" "$ClientDir\$Jar"
Copy-Item -Force "$ClientDir\updater\Play-Microbot.bat" "$ClientDir\Play-Microbot.bat"
Copy-Item -Force "$ClientDir\updater\Update-Client.ps1" "$ClientDir\Update-Client.ps1"
Set-Content -Path "$ClientDir\CLIENT_BUILD" -Value $build -NoNewline

# 4. Drop cached server config so the next launch fetches the current jav_config/world list.
Remove-Item -Recurse -Force "$ClientDir\home\.runelite\cache\okhttp" -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "BeanLab client is up to date (build $build). Start it with Play-Microbot.bat." -ForegroundColor Green
