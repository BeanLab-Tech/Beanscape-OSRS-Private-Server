@echo off
rem BeanLab OSRS - Microbot 2.0.20 (RuneLite 1.11.19, rev 233) connecting straight to runescape-server.
rem The jar is patched with the server's RSA key, an "allow any host" check and a BeanLab version
rem check (see client\microbot\patch-microbot.py in the repo).
rem runelite.http-service.url points RuneLite's API (world list, item prices) at the server, so
rem Microbot sees world 1 as a members world instead of failing to reach api.runelite.net.
rem Profile, plugins and cache live in .\home so the normal RuneLite install is left alone.
cd /d "%~dp0"
set "SERVER=https://runescape-server.tail5475a4.ts.net"
set "JAVAW=%ProgramFiles%\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\javaw.exe"
if not exist "%JAVAW%" set "JAVAW=%ProgramFiles%\Eclipse Adoptium\jre-21.0.12.101-hotspot\bin\javaw.exe"
if not exist "%JAVAW%" set "JAVAW=javaw"
if not exist "home" mkdir "home"
start "BeanLab Microbot" "%JAVAW%" -Xmx2g -Duser.home="%~dp0home" -Drunelite.http-service.url=%SERVER%/runelite -jar "microbot-2.0.20-beanlab.jar" --jav_config=%SERVER%/jav_config.ws --disable-telemetry
