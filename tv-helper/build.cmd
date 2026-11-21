@echo off
rem Builds the TV helper app with a JDK 21 / Android SDK / Gradle kept in a tools folder (no system
rem install). COUCHTV_TOOLS points at it; by default ..\..\android-tools, next to the repo.
setlocal
set "HERE=%~dp0"
if "%COUCHTV_TOOLS%"=="" set "COUCHTV_TOOLS=%HERE%..\..\android-tools"
set "JAVA_HOME=%COUCHTV_TOOLS%\jdk-21.0.13+9"
set "ANDROID_HOME=%COUCHTV_TOOLS%\android-sdk"
set "GRADLE_USER_HOME=%COUCHTV_TOOLS%\gradle-home"
set "ANDROID_USER_HOME=%COUCHTV_TOOLS%\android-user"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%HERE%"
call "%COUCHTV_TOOLS%\gradle-8.11.1\bin\gradle.bat" --no-daemon -q assembleRelease %*
if errorlevel 1 exit /b 1
echo APK: %HERE%app\build\outputs\apk\release\app-release.apk
