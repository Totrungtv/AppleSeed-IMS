@echo off
cd /d "%~dp0"
where python >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Chua cai Python 3.x.
  pause
  exit /b 1
)
python "%~dp0AppleSeed_Android_Service_Center.py"
if errorlevel 1 pause
