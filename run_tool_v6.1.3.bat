@echo off
cd /d "%~dp0"
python "%~dp0AppleSeed_Android_Service_Center_VIP_v6.1.3.py"
if errorlevel 1 pause
