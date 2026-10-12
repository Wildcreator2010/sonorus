@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
rem JDK 路径来自 local.properties 的 jdk.home（该文件已被 .gitignore 忽略），仓库里不留本机绝对路径。
if exist "%~dp0local.properties" for /f "usebackq tokens=1,* delims==" %%a in ("%~dp0local.properties") do if "%%a"=="jdk.home" set "JAVA_HOME=%%b"
cd /d "%~dp0"

echo ========================================
echo Sonorus Debug APK Build Script
echo ========================================
echo.
echo Building with --no-build-cache --no-daemon...
echo.

call gradlew.bat assembleDebug --no-build-cache --no-daemon
set "RC=!ERRORLEVEL!"

if !RC! EQU 0 (
    echo.
    echo ========================================
    echo BUILD SUCCESSFUL!
    echo ========================================
    echo APK location: app\build\outputs\apk\debug\app-debug.apk
) else (
    echo.
    echo ========================================
    echo BUILD FAILED!
    echo Error code: !RC!
    echo ========================================
)
pause
