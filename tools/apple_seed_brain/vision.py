from __future__ import annotations

import base64
import json
import os
import urllib.error
import urllib.request
from pathlib import Path

OLLAMA_URL = os.getenv("APPLE_SEED_OLLAMA_URL", "http://127.0.0.1:11434/api/generate")
VISION_MODEL = os.getenv("APPLE_SEED_VISION_MODEL", "qwen3-vl:4b")


def analyze_image(image_path: str, context: str = "") -> str:
    data = base64.b64encode(Path(image_path).read_bytes()).decode("ascii")
    prompt = (
        "You are Apple Seed Brain, a phone-repair diagnostic assistant. "
        "Analyze this photo as evidence, especially panic logs, sensor errors, I2C/SDA/SCL, "
        "component names, addresses, boot/reset clues and visible text. "
        "Transcribe important text exactly when readable. Separate OBSERVED text from INFERENCE. "
        "Do not invent missing characters. Give likely diagnostic paths and the next measurement. "
        "If the image is not a panic/log screenshot, say what is actually visible.\n\n"
        f"Technician context: {context or 'none'}"
    )
    body = json.dumps({
        "model": VISION_MODEL,
        "prompt": prompt,
        "images": [data],
        "stream": False,
        "options": {"temperature": 0.1},
    }).encode("utf-8")
    request = urllib.request.Request(
        OLLAMA_URL,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            result = json.loads(response.read().decode("utf-8"))
        return str(result.get("response", "")).strip() or "Local vision model returned no analysis."
    except urllib.error.URLError as exc:
        raise RuntimeError(
            "Chưa kết nối được Local Vision. Cài Ollama, chạy model vision rồi thử lại. "
            f"Chi tiết: {exc.reason}"
        ) from exc


def vision_available() -> bool:
    try:
        with urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=2) as response:
            payload = json.loads(response.read().decode("utf-8"))
        names = {str(x.get("name", "")) for x in payload.get("models", [])}
        return VISION_MODEL in names
    except Exception:
        return False
