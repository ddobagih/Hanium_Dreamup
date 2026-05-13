from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class IntentResult:
    intent: str
    score: float
    slots: dict[str, Any]
    normalized: str


def normalize_korean_command(text: str) -> str:
    normalized = text.strip().lower()
    normalized = re.sub(r"[\s\t\n\r]+", " ", normalized)
    normalized = re.sub(r"[.,!?~。？！]+", "", normalized)
    return normalized


def classify_intent(transcript: str) -> IntentResult:
    """Rule-based command intent classifier.

    The STT target is intent recognition, not exact transcript matching. Scores are
    conservative heuristic confidences so callers can decide when to ask the user
    to repeat a command.
    """
    text = normalize_korean_command(transcript)
    compact = text.replace(" ", "")

    if not compact:
        return IntentResult("unknown", 0.0, {}, text)

    # Destination must be checked before generic navigation/report terms.
    destination_patterns = [
        r"목적지\s*(?P<destination>.+?)(?:으로|로)?\s*(?:설정해?|지정해?|가자|안내해?)?$",
        r"(?P<destination>.+?)(?:으로|로)\s*목적지\s*(?:설정해?|지정해?)?$",
    ]
    for pattern in destination_patterns:
        match = re.search(pattern, text)
        if match and match.groupdict().get("destination"):
            destination = match.group("destination").strip()
            destination = re.sub(r"(으로|로)$", "", destination).strip()
            destination = re.sub(r"(?:설정해?|지정해?|안내해?|가자)$", "", destination).strip()
            destination = re.sub(r"(으로|로)$", "", destination).strip()
            if destination:
                return IntentResult("set_destination", 0.92, {"destination": destination}, text)

    if any(key in compact for key in ["신고해", "싱고해", "위험신고", "위험싱고", "현재위험신고", "현재위험싱고", "신고저장", "신고", "싱고"]):
        return IntentResult("create_report", 0.9, {}, text)

    if any(key in compact for key in ["음성꺼", "음성꼭", "음성끅", "소리꺼", "말하지마", "음성끄", "음소거"]):
        return IntentResult("voice_off", 0.93, {}, text)

    if any(key in compact for key in ["음성켜", "소리켜", "음성키", "다시말해도돼"]):
        return IntentResult("voice_on", 0.93, {}, text)

    if any(key in compact for key in ["다시말해줘", "다시말해", "반복해", "한번더", "다시들려줘"]):
        return IntentResult("repeat_last", 0.9, {}, text)

    if any(key in compact for key in ["길안내시작", "길었네시작", "길시작", "안내시작", "내비시작", "네비시작", "경로안내"]):
        return IntentResult("start_navigation", 0.9, {}, text)

    return IntentResult("unknown", 0.2, {}, text)
