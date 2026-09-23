@echo off
setlocal
where ollama >nul 2>nul
if errorlevel 1 (
  echo Ollama is not installed. Install Ollama for Windows first.
  pause
  exit /b 1
)
echo Starting local vision model...
ollama pull qwen3-vl:4b
set APPLE_SEED_VISION_MODEL=qwen3-vl:4b
python server.py
