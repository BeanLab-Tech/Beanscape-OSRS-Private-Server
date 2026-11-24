@echo off
rem BeanLab OSRS - Microbot 2.6.65 (RuneLite 1.13.8) connecting straight to runescape-server.
rem The jar (microbot-2.6.65-beanlab.jar) is patched on the server with the server's RSA key,
rem an "allow any host" check and a BeanLab version check (client\microbot\patch-microbot.py).
rem Update-Client.ps1 downloads it from the server, so this folder needs no Python and no
rem original Microbot jar.
rem runelite.http-service.url points RuneLite's API (world list, item prices) at the server, so
rem Microbot sees world 1 as a members world instead of failing to reach api.runelite.net.
rem Profile, plugins and cache live in .\home so the normal RuneLite install is left alone.
cd /d "%~dp0"
set "SERVER=https://runescape-server.tail5475a4.ts.net"
set "JAR=microbot-2.6.65-beanlab.jar"
if not exist "%JAR%" (
    echo %JAR% not found. Run Update-Client.ps1 once to download it, then start here.
    pause
    exit /b 1
)
set "JAVAW=%ProgramFiles%\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\javaw.exe"
if not exist "%JAVAW%" set "JAVAW=%ProgramFiles%\Eclipse Adoptium\jre-21.0.12.101-hotspot\bin\javaw.exe"
if not exist "%JAVAW%" set "JAVAW=javaw"
if not exist "home" mkdir "home"
start "BeanLab Microbot" "%JAVAW%" -Xmx2g -Duser.home="%~dp0home" -Drunelite.http-service.url=%SERVER%/runelite -jar "%JAR%" --jav_config=%SERVER%/jav_config.ws --disable-telemetry
