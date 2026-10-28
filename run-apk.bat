@echo off
setlocal
rem JDK 路径来自 local.properties 的 jdk.home（该文件已被 .gitignore 忽略），仓库里不留本机绝对路径。
rem 没配置时沿用外部环境里的 JAVA_HOME。
if exist "%~dp0local.properties" for /f "usebackq tokens=1,* delims==" %%a in ("%~dp0local.properties") do if "%%a"=="jdk.home" set "JAVA_HOME=%%b"
cd /d "%~dp0"
call gradlew.bat assembleDebug assembleRelease --no-build-cache > "%~dp0apk-build.log" 2>&1
set RC=%ERRORLEVEL%
echo BUILD_EXIT=%RC% >> "%~dp0apk-build.log"
exit /b %RC%
