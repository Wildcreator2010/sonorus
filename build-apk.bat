@echo off
chcp 65001 >nul
set JAVA_HOME=C:\Users\Wildcreator\.workbuddy\binaries\jdk\jdk-17.0.20.1+1
echo ========================================
echo Sonorus Debug APK Build Script
echo ========================================
echo.
echo Building with --no-build-cache --no-daemon...
echo.

gradlew.bat assembleDebug --no-build-cache --no-daemon

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo BUILD SUCCESSFUL!
    echo ========================================
    echo APK location: app\build\outputs\apk\debug\app-debug.apk
    pause
) else (
    echo.
    echo ========================================
    echo BUILD FAILED!
    echo Error code: %ERRORLEVEL%
    echo ========================================
    pause
)
