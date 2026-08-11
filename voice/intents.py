from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Literal


@dataclass(frozen=True)
class IntentResult:
    intent: str
    score: float
    slots: dict[str, Any]
    normalized: str


@dataclass(frozen=True)
class IntentPolicy:
    action: Literal["execute", "reprompt"]
    should_execute: bool
    reason: str | None
    prompt: str | None


INTENT_EXECUTION_SCORE_THRESHOLD = 0.7
UNKNOWN_INTENT_PROMPT = "명령을 이해하지 못했습니다. 다시 말씀해 주세요."
LOW_CONFIDENCE_PROMPT = "명령 신뢰도가 낮습니다. 다시 말씀해 주세요."
MISSING_DESTINATION_PROMPT = "목적지를 다시 말씀해 주세요."
MISSING_CANDIDATE_INDEX_PROMPT = "몇 번째 목적지를 선택할지 다시 말씀해 주세요."
SAFE_NOOP_PROMPT = "실행하지 않았습니다. 필요한 명령을 다시 말씀해 주세요."

CANDIDATE_SELECTION_HINTS = ("선택", "고르", "골라", "목적지", "후보")
CANDIDATE_DESTINATION_SET_HINTS = ("설정", "지정")
KOREAN_CANDIDATE_INDEX_WORDS = {
    "첫": 1,
    "한": 1,
    "하나": 1,
    "두": 2,
    "둘": 2,
    "세": 3,
    "셋": 3,
    "네": 4,
    "넷": 4,
    "다섯": 5,
    "여섯": 6,
    "일곱": 7,
    "여덟": 8,
    "아홉": 9,
    "열": 10,
}
KOREAN_CANDIDATE_INDEX_PATTERN = "|".join(
    re.escape(word) for word in sorted(KOREAN_CANDIDATE_INDEX_WORDS, key=len, reverse=True)
)


def normalize_korean_command(text: str) -> str:
    normalized = text.strip().lower()
    normalized = re.sub(r"[\s\t\n\r]+", " ", normalized)
    normalized = re.sub(r"[.,!?~。？！]+", "", normalized)
    return normalized


def is_candidate_selection_command(compact: str, token: str) -> bool:
    if any(hint in compact for hint in CANDIDATE_DESTINATION_SET_HINTS):
        return False
    return compact == token or any(hint in compact for hint in CANDIDATE_SELECTION_HINTS)


def candidate_index_from_command(text: str) -> int | None:
    compact = text.replace(" ", "")
    digit_match = re.search(r"(?P<index>[1-9]\d*)(?:번째|번)", compact)
    if digit_match and is_candidate_selection_command(compact, digit_match.group(0)):
        return int(digit_match.group("index"))

    word_match = re.search(rf"(?P<word>{KOREAN_CANDIDATE_INDEX_PATTERN})(?:번째|째|번)", compact)
    if word_match and is_candidate_selection_command(compact, word_match.group(0)):
        return KOREAN_CANDIDATE_INDEX_WORDS[word_match.group("word")]

    return None


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

    if any(key in compact for key in ["신고하지마", "신고취소", "싱고취소", "신고하지말아줘", "신고멈춰"]):
        return IntentResult("unknown", 0.85, {"safe_noop": True}, text)

    # Destination must be checked before generic navigation/report terms.
    destination_patterns = [
        r"목적지\s*(?P<destination>.+?)(?:으로|로)?\s*(?P<action>설정해?|지정해?|가자|안내해?)?$",
        r"(?P<destination>.+?)(?:으로|로)\s*목적지\s*(?P<action>설정해?|지정해?)?$",
        r"(?P<destination>.+?)(?:으로|로)\s*(?P<action>안내해줘|안내해|가자)$",
    ]
    for pattern in destination_patterns:
        match = re.search(pattern, text)
        if match and match.groupdict().get("destination"):
            destination = match.group("destination").strip()
            destination = re.sub(r"(으로|로)$", "", destination).strip()
            destination = re.sub(r"(?:설정해?|지정해?|안내해?|가자)$", "", destination).strip()
            destination = re.sub(r"(으로|로)$", "", destination).strip()
            if destination:
                action = match.groupdict().get("action") or ""
                requested_start = "안내" in action or "가자" in action
                return IntentResult("set_destination", 0.92, {"destination": destination, "requested_start": requested_start}, text)

    candidate_index = candidate_index_from_command(text)
    if candidate_index is not None:
        return IntentResult("select_destination_candidate", 0.91, {"candidate_index": candidate_index}, text)

    if any(key in compact for key in ["신고해", "싱고해", "위험신고", "위험싱고", "현재위험신고", "현재위험싱고", "신고저장", "신고", "싱고"]):
        return IntentResult("create_report", 0.9, {}, text)

    location_keys = [
        "지금어디",
        "현재어디",
        "여기어디",
        "나어디",
        "내가어디",
        "어디야",
        "현재위치",
        "내위치",
        "나의위치",
        "제위치",
        "현위치",
    ]
    location_pattern = r"(?:내|제|나의|현재|지금|여기)?위치[를은이가]?(?:알려|말해|확인|보여|어디)"
    if any(key in compact for key in location_keys) or re.search(location_pattern, compact):
        return IntentResult("get_current_location", 0.88, {}, text)

    if any(key in compact for key in ["음성꺼", "음성꼭", "음성끅", "소리꺼", "말하지마", "음성끄", "음소거"]):
        return IntentResult("voice_off", 0.93, {}, text)

    if any(key in compact for key in ["음성켜", "소리켜", "음성키", "다시말해도돼"]):
        return IntentResult("voice_on", 0.93, {}, text)

    if any(key in compact for key in ["다시말해줘", "다시말해", "반복해", "한번더", "다시들려줘"]):
        return IntentResult("repeat_last", 0.9, {}, text)

    if any(key in compact for key in ["길안내중지", "안내중지", "길안내취소", "안내취소", "내비중지", "네비중지"]):
        return IntentResult("stop_navigation", 0.9, {}, text)

    if any(key in compact for key in ["재탐색", "다시경로", "다시안내"]):
        return IntentResult("reroute_navigation", 0.9, {}, text)

    if any(key in compact for key in ["길안내시작", "길었네시작", "길시작", "안내시작", "내비시작", "네비시작", "경로안내"]):
        return IntentResult("start_navigation", 0.9, {}, text)

    return IntentResult("unknown", 0.2, {}, text)


def evaluate_intent_policy(
    result: IntentResult,
    *,
    threshold: float = INTENT_EXECUTION_SCORE_THRESHOLD,
) -> IntentPolicy:
    """Return the safe execution/reprompt policy for a classified voice command."""
    if result.intent == "unknown":
        if result.slots.get("safe_noop"):
            return IntentPolicy("reprompt", False, "safe_noop", SAFE_NOOP_PROMPT)
        return IntentPolicy("reprompt", False, "unknown_intent", UNKNOWN_INTENT_PROMPT)

    if result.score < threshold:
        return IntentPolicy("reprompt", False, "low_confidence", LOW_CONFIDENCE_PROMPT)

    if result.intent == "set_destination" and not result.slots.get("destination"):
        return IntentPolicy("reprompt", False, "missing_destination", MISSING_DESTINATION_PROMPT)

    candidate_index = result.slots.get("candidate_index")
    if result.intent == "select_destination_candidate" and (type(candidate_index) is not int or candidate_index < 1):
        return IntentPolicy("reprompt", False, "missing_candidate_index", MISSING_CANDIDATE_INDEX_PROMPT)

    return IntentPolicy("execute", True, None, None)


def intent_response_payload(result: IntentResult) -> dict[str, Any]:
    policy = evaluate_intent_policy(result)
    return {
        "normalized": result.normalized,
        "intent": result.intent,
        "score": result.score,
        "confidence": result.score,
        "slots": result.slots,
        "action": policy.action,
        "should_execute": policy.should_execute,
        "reason": policy.reason,
        "prompt": policy.prompt,
    }


def intent_schema_payload() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.voice_intents.v1",
        "execution_threshold": INTENT_EXECUTION_SCORE_THRESHOLD,
        "intents": [
            {"name": "create_report", "should_execute": True, "slots": []},
            {"name": "voice_on", "should_execute": True, "slots": []},
            {"name": "voice_off", "should_execute": True, "slots": []},
            {"name": "repeat_last", "should_execute": True, "slots": []},
            {"name": "set_destination", "should_execute": True, "slots": ["destination", "requested_start"]},
            {"name": "select_destination_candidate", "should_execute": True, "slots": ["candidate_index"]},
            {"name": "start_navigation", "should_execute": True, "slots": []},
            {"name": "reroute_navigation", "should_execute": True, "slots": []},
            {"name": "stop_navigation", "should_execute": True, "slots": []},
            {"name": "get_current_location", "should_execute": True, "slots": []},
            {"name": "unknown", "should_execute": False, "slots": ["safe_noop"]},
        ],
        "safe_noop_examples": ["신고하지 마", "신고 취소"],
    }
