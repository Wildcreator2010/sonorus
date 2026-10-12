@echo off
set JAVA_HOME=C:\Users\Wildcreator\.workbuddy\binaries\jdk\jdk-17.0.20.1+1
cd /d C:\Users\Wildcreator\Desktop\WildProject\WildAdaptor
call gradlew.bat clean testDebugUnitTest --no-build-cache > C:\Users\Wildcreator\Desktop\WildProject\WildAdaptor\test-run.log 2>&1
echo CLEAN_TEST_EXIT=%ERRORLEVEL% >> C:\Users\Wildcreator\Desktop\WildProject\WildAdaptor\test-run.log
