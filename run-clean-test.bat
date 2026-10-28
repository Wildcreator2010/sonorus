@echo off
setlocal
rem JDK 路径来自 local.properties 的 jdk.home（该文件已被 .gitignore 忽略），仓库里不留本机绝对路径。
if exist "%~dp0local.properties" for /f "usebackq tokens=1,* delims==" %%a in ("%~dp0local.properties") do if "%%a"=="jdk.home" set "JAVA_HOME=%%b"
cd /d "%~dp0"
call gradlew.bat clean testDebugUnitTest --no-build-cache > "%~dp0test-run.log" 2>&1
set RC=%ERRORLEVEL%
echo CLEAN_TEST_EXIT=%RC% >> "%~dp0test-run.log"
exit /b %RC%
