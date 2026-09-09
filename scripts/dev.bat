@echo off
REM dev.bat — one-command dev loop for MindPalace (the mvn analog of npm run dev)
REM Usage: scripts\dev.bat            -> build + run live mode
REM        scripts\dev.bat demo       -> build + run --demo (zero auth/network)
REM        scripts\dev.bat selftest   -> build + selftest only (exit code = gate)

setlocal
cd /d "%~dp0.."

if "%JAVA_HOME%"=="" set JAVA_HOME=C:\Program Files\Java\jdk-17
set MVN=C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.16\bin\mvn.cmd
set FLAGS=-Dprism.order=sw -Dprism.vsync=false -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m

echo [dev] building...
call "%MVN%" -q -DskipTests package
if errorlevel 1 (
    echo [dev] BUILD FAILED - fix errors, retry.
    exit /b 1
)

REM frozen-jar rule: run the copy, not the target artifact
copy /Y target\mindpalace-1.0.0.jar mindpalace-live.jar >nul

if "%1"=="selftest" (
    "%JAVA_HOME%\bin\java" %FLAGS% -jar mindpalace-live.jar --demo --selftest
    exit /b %errorlevel%
)
if "%1"=="demo" (
    echo [dev] launching demo mode...
    "%JAVA_HOME%\bin\java" %FLAGS% -jar mindpalace-live.jar --demo
    exit /b %errorlevel%
)
echo [dev] launching live mode...
"%JAVA_HOME%\bin\java" %FLAGS% -jar mindpalace-live.jar
exit /b %errorlevel%