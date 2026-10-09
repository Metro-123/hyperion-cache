@echo off
title Hyperion Cache Console
cd /d "%~dp0"
echo.
echo   Starting Hyperion Cache Console...
echo   A browser window will open automatically.
echo   Keep this window open while using the dashboard; close it to stop.
echo.
if not exist "target\hyperion-cache.jar" (
    echo   JAR not found. Building it now with Maven...
    call mvn -q package
    if errorlevel 1 (
        echo.
        echo   Build failed. Make sure Java 21 and Maven are installed.
        pause
        exit /b 1
    )
)
java -jar "target\hyperion-cache.jar"
echo.
echo   Hyperion has stopped.
pause >nul
