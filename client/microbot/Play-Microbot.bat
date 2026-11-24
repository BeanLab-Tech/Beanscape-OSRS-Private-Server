@echo off
rem BeanLab OSRS - Microbot 2.0.20 (RuneLite 1.11.19, rev 233) connecting straight to runescape-server.
rem The jar is patched with the server's RSA key and an "allow any host" check (see README.txt).
rem Profile, plugins and cache live in .\home so the normal RuneLite install is left alone.
cd /d "%~dp0"
set "JAVAW=%ProgramFiles%\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\javaw.exe"
if not exist "%JAVAW%" set "JAVAW=%ProgramFiles%\Eclipse Adoptium\jre-21.0.12.101-hotspot\bin\javaw.exe"
if not exist "%JAVAW%" set "JAVAW=javaw"
if not exist "home" mkdir "home"
start "BeanLab Microbot" "%JAVAW%" -Xmx2g -Duser.home="%~dp0home" -jar "microbot-2.0.20-beanlab.jar" --jav_config=https://runescape-server.tail5475a4.ts.net/jav_config.ws --disable-telemetry
