@echo off
set JAVA_HOME=C:\Users\Wildcreator\.workbuddy\binaries\jdk\jdk-17.0.20.1+1
cd /d C:\Users\Wildcreator\Desktop\WildProject\WildAdaptor
call gradlew.bat assembleDebug assembleRelease --no-build-cache > C:\Users\Wildcreator\Desktop\WildProject\WildAdaptor\apk-build.log 2>&1
echo BUILD_EXIT=%ERRORLEVEL% >> C:\Users\Wildcreator\Desktop\WildProject\WildAdaptor\apk-build.log
