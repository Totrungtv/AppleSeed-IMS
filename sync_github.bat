@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Apple Seed - Sync GitHub
echo ==========================================
echo APPLE SEED - DONG BO GITHUB
echo ==========================================

where git >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Chua cai Git.
  pause
  exit /b 1
)

if not exist ".git" (
  echo [SETUP] Khoi tao repo local...
  git init
  git remote add origin https://github.com/Totrungtv/AppleSeed-IMS.git
)

git remote set-url origin https://github.com/Totrungtv/AppleSeed-IMS.git
git fetch origin main
if errorlevel 1 (
  echo [ERROR] Khong fetch duoc GitHub.
  pause
  exit /b 1
)

echo [SYNC] Dua may tinh ve dung ban GitHub...
git reset --hard origin/main
if errorlevel 1 (
  echo [ERROR] Reset that bai.
  pause
  exit /b 1
)

git checkout -B main origin/main
if errorlevel 1 (
  echo [ERROR] Checkout that bai.
  pause
  exit /b 1
)

echo.
echo [OK] DONG BO XONG.
echo [OK] KHONG TU DONG XOA FILE LOCAL.
echo.
dir /b
echo.
pause
