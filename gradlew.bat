@echo off
setlocal
set "PROJECT_DIR=%~dp0"
set "GRADLE_VERSION=9.6.1"
set "GRADLE_HOME=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-bin\apple-seed-bootstrap"
set "GRADLE_EXE=%GRADLE_HOME%\gradle-%GRADLE_VERSION%\bin\gradle.bat"

if exist "%GRADLE_EXE%" goto runGradle

echo Apple Seed: Gradle %GRADLE_VERSION% not found. Downloading...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip=Join-Path $env:TEMP 'gradle-9.6.1-bin.zip'; Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-9.6.1-bin.zip' -OutFile $zip; New-Item -ItemType Directory -Force -Path '%GRADLE_HOME%' | Out-Null; Expand-Archive -Force -Path $zip -DestinationPath '%GRADLE_HOME%'; Remove-Item $zip -Force"
if errorlevel 1 (
    echo Apple Seed: Failed to download Gradle 9.6.1.
    exit /b 1
)

if not exist "%GRADLE_EXE%" (
    echo Apple Seed: Gradle executable was not created.
    exit /b 1
)

:runGradle
call "%GRADLE_EXE%" -p "%PROJECT_DIR%" %*
exit /b %ERRORLEVEL%
