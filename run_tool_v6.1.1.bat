@echo off
cd /d "%~dp0"
where py >nul 2>&1
if %errorlevel%==0 (
  py -3 "AppleSeed_Android_Service_Center_VIP_v6.1.1.py"
  goto :eof
)
where python >nul 2>&1
if %errorlevel%==0 (
  python "AppleSeed_Android_Service_Center_VIP_v6.1.1.py"
  goto :eof
)
echo Khong tim thay Python 3.
pause
