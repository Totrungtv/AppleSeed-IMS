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
  git init
  git remote add origin https://github.com/Totrungtv/AppleSeed-IMS.git
) else (
  git remote get-url origin >nul 2>&1
  if errorlevel 1 git remote add origin https://github.com/Totrungtv/AppleSeed-IMS.git
)
git fetch origin main
if errorlevel 1 (
  echo [ERROR] Fetch GitHub that bai.
  pause
  exit /b 1
)
echo [SYNC] Xoa file local chua theo doi de tranh bi ghi de...
git clean -fd
if errorlevel 1 (
  echo [ERROR] Khong the clean file local.
  pause
  exit /b 1
)
git reset --hard origin/main
if errorlevel 1 (
  echo [ERROR] Khong the reset theo GitHub.
  pause
  exit /b 1
)
git checkout -B main origin/main
if errorlevel 1 (
  echo [ERROR] Khong the checkout main.
  pause
  exit /b 1
)
echo.
echo [OK] MAY TINH DA DONG BO VOI GITHUB.
echo [OK] Dung GitHub main lam ban chuan.
echo.
pause
