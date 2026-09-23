@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Apple Seed - Dong bo may tinh voi GitHub
echo ==========================================
echo APPLE SEED - DONG BO MAY TINH ^<-> GITHUB
echo ==========================================
where git >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Chua cai Git.
  pause
  exit /b 1
)
if not exist ".git" (
  echo [SETUP] Khoi tao Git...
  git init
  git remote add origin https://github.com/Totrungtv/AppleSeed-IMS.git
) else (
  git remote get-url origin >nul 2>&1
  if errorlevel 1 git remote add origin https://github.com/Totrungtv/AppleSeed-IMS.git
)
echo [SYNC] Dang lay ban main moi nhat...
git fetch origin main
if errorlevel 1 (
  echo [ERROR] Fetch GitHub that bai.
  pause
  exit /b 1
)
git checkout -B main origin/main
if errorlevel 1 (
  echo [ERROR] Khong the dong bo main.
  pause
  exit /b 1
)
git reset --hard origin/main
if errorlevel 1 (
  echo [ERROR] Reset main that bai.
  pause
  exit /b 1
)
echo.
echo [OK] MAY TINH DA DONG BO VOI GITHUB.
echo [OK] Ban hien tai: PySide6 + ADB + VoLTE 1-CLICK
echo.
pause
