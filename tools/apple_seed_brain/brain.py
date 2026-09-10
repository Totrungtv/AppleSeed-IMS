from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "data"
KNOWLEDGE_FILE = DATA_DIR / "knowledge.json"
CASES_FILE = DATA_DIR / "cases.json"


def _load_json(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def _save_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def load_knowledge() -> list[dict[str, Any]]:
    return _load_json(KNOWLEDGE_FILE, [])


def load_cases() -> list[dict[str, Any]]:
    return _load_json(CASES_FILE, [])


def save_case(case: dict[str, Any]) -> None:
    cases = load_cases()
    cases.append(case)
    _save_json(CASES_FILE, cases)


def _tokens(text: str) -> set[str]:
    return {x.lower() for x in re.findall(r"[a-zA-Z0-9_+.-]+", text) if len(x) > 1}


def _score(text: str, item: dict[str, Any]) -> tuple[int, list[str]]:
    haystack = " ".join(str(item.get(k, "")) for k in ("title", "symptoms", "patterns", "diagnosis", "next_step"))
    wanted = _tokens(text)
    known = _tokens(haystack)
    hits = sorted(wanted & known)
    return len(hits), hits


def analyze(payload: dict[str, Any]) -> dict[str, Any]:
    model = str(payload.get("model", "")).strip()
    panic = str(payload.get("panic", "")).strip()
    boot = str(payload.get("boot_current", "")).strip()
    symptoms = str(payload.get("symptoms", "")).strip()
    measurements = str(payload.get("measurements", "")).strip()
    replaced = str(payload.get("replaced", "")).strip()
    evidence = " | ".join(x for x in (model, panic, boot, symptoms, measurements, replaced) if x)

    knowledge = load_knowledge()
    cases = load_cases()

    ranked = []
    for item in knowledge:
        score, hits = _score(evidence, item)
        if score:
            ranked.append({"score": score, "hits": hits, "item": item, "source": "knowledge"})

    for item in cases:
        score, hits = _score(evidence, item)
        if score:
            ranked.append({"score": score, "hits": hits, "item": item, "source": "confirmed_case"})

    ranked.sort(key=lambda x: x["score"], reverse=True)
    matches = ranked[:5]

    hypotheses: list[dict[str, Any]] = []
    seen: set[str] = set()
    for match in matches:
        item = match["item"]
        diagnosis = str(item.get("diagnosis", "")).strip()
        if diagnosis and diagnosis not in seen:
            seen.add(diagnosis)
            hypotheses.append({
                "diagnosis": diagnosis,
                "confidence": min(95, 40 + match["score"] * 10),
                "evidence": match["hits"],
                "next_step": item.get("next_step", "Collect another measurement before replacing a part."),
            })

    if not hypotheses:
        hypotheses.append({
            "diagnosis": "INSUFFICIENT EVIDENCE",
            "confidence": 0,
            "evidence": [],
            "next_step": "Add the exact panic/log text, boot-current sequence and at least one measured rail or known-good comparison.",
        })

    return {
        "engine": "Apple Seed Brain Local v1",
        "online_dependency": False,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "input": payload,
        "decision": {
            "model": model or "UNKNOWN",
            "evidence_quality": "STRONG" if len(_tokens(evidence)) >= 10 else "PARTIAL",
            "rule": "Evidence first; do not replace a component from a single symptom.",
        },
        "hypotheses": hypotheses,
        "matched_records": [
            {
                "source": m["source"],
                "title": m["item"].get("title", "Untitled"),
                "score": m["score"],
                "hits": m["hits"],
            }
            for m in matches
        ],
    }


def confirm_case(payload: dict[str, Any]) -> dict[str, Any]:
    now = datetime.now(timezone.utc).isoformat()
    case = {
        "title": payload.get("title") or "Confirmed Apple Seed repair case",
        "model": payload.get("model", ""),
        "symptoms": payload.get("symptoms", ""),
        "patterns": payload.get("patterns", ""),
        "diagnosis": payload.get("diagnosis", ""),
        "next_step": payload.get("next_step", ""),
        "result": payload.get("result", ""),
        "confirmed_at": now,
        "source": "Apple Seed technician",
    }
    save_case(case)
    return case
