@echo off
setlocal
cd /d "%~dp0"

echo ==========================================
echo APPLE SEED - SYNC ANDROID SERVICE TO GITHUB
echo ==========================================

where git >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Chua cai Git.
  pause
  exit /b 1
)

git rev-parse --is-inside-work-tree >nul 2>&1
if errorlevel 1 (
<<<<<<< HEAD
  echo [1/4] Khoi tao Git repository...
=======
  echo [1/5] Khoi tao Git repository...
>>>>>>> 2f7bf23 (Update Apple Seed Android Service Center)
  git init
)

git remote get-url origin >nul 2>&1
if errorlevel 1 (
<<<<<<< HEAD
  echo [2/4] Them remote GitHub...
  git remote add origin https://github.com/Totrungtv/AppleSeed-IMS.git
) else (
  echo [2/4] Remote hien tai:
  git remote get-url origin
)

echo [3/4] Dong bo file...
git add .
git status

echo [4/4] Commit va push...
git diff --cached --quiet
if errorlevel 1 (
  git commit -m "Update Apple Seed Android Service Center"
)

git branch -M main
git push -u origin main

=======
  echo [2/5] Them remote GitHub...
  git remote add origin https://github.com/Totrungtv/AppleSeed-IMS.git
) else (
  echo [2/5] Remote hien tai:
  git remote get-url origin
)

echo [3/5] Nap thay doi local...
git add .
git status

echo [4/5] Commit local...
git diff --cached --quiet
if errorlevel 1 git commit -m "Update Apple Seed Android Service Center"

echo [5/5] Dong bo remote truoc khi push...
git fetch origin main
if errorlevel 1 (
  echo [ERROR] Khong fetch duoc GitHub. Kiem tra mang/Dang nhap.
  pause
  exit /b 1
)
git pull --rebase origin main
if errorlevel 1 (
  echo.
  echo [ERROR] Rebase bi xung dot. KHONG force push.
  echo Hay gui anh man hinh nay de xu ly tiep.
  pause
  exit /b 1
)
git push -u origin main
>>>>>>> 2f7bf23 (Update Apple Seed Android Service Center)
if errorlevel 1 (
  echo.
  echo [ERROR] Push that bai. Kiem tra dang nhap GitHub/PAT va remote.
  pause
  exit /b 1
)

echo.
echo [OK] DA DONG BO LEN GITHUB:
echo https://github.com/Totrungtv/AppleSeed-IMS
pause
