@echo off
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
if "%~1"=="" (
  echo Usage: "%~nx0" PHONE_IP:ADB_PORT
  exit /b 1
)
"%ADB%" connect %~1
"%ADB%" install -r "%~dp0output\app-release.apk"
pause
