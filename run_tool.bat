@echo off
setlocal
cd /d "%~dp0"
where python >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Chua cai Python 3.10+.
  pause
  exit /b 1
)
python -c "import PySide6" >nul 2>&1
if errorlevel 1 (
  echo [SETUP] Dang cai PySide6...
  python -m pip install --upgrade pip
  python -m pip install -r "%~dp0requirements.txt"
  if errorlevel 1 (
    echo [ERROR] Cai PySide6 that bai.
    pause
    exit /b 1
  )
)
python "%~dp0AppleSeed_Android_Service_Center.py"
if errorlevel 1 pause
