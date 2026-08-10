"""Rule-based Korean command classification and safe execution policy.

Scores are fixed heuristic values assigned by matched rules, not acoustic STT
confidence. Unknown, negated, low-score, or slot-incomplete commands are
returned as reprompts instead of executable actions.
"""

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
LOW_ACOUSTIC_CONFIDENCE_PROMPT = "음성이 명확하지 않습니다. 다시 말씀해 주세요."
MISSING_DESTINATION_PROMPT = "목적지를 다시 말씀해 주세요."
MISSING_CANDIDATE_INDEX_PROMPT = "몇 번째 목적지를 선택할지 다시 말씀해 주세요."
SAFE_NOOP_PROMPT = "실행하지 않았습니다. 필요한 명령을 다시 말씀해 주세요."
AMBIGUOUS_DESTINATION_MARKERS = ("말고", "아니고", "아니라", "또는", "혹은")

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

NEGATION_MARKERS = (
    "하지마",
    "하지말",
    "하지않",
    "안해",
    "안할",
    "할필요없",
    "하지않아도",
    "지마",
    "지말",
    "지않",
)
NEGATABLE_ACTION_PATTERNS = (
    r"(?:신고|싱고)(?:는|은|를|을)?",
    r"목적지(?:를|을)?(?:취소|삭제|없애|지워)",
    r"목적지(?:를|을)?.{0,80}(?:설정|지정|변경|바꿔|바꾸|안내|가)",
    r".{1,80}(?:으로|로)목적지(?:를|을)?(?:설정|지정|변경|바꿔|바꾸)",
    r".{1,80}(?:으로|로)(?:안내|가)",
    r"(?:길안내|안내|내비|네비)(?:를|을)?(?:중지|취소|멈춰)",
    r"(?:길안내|안내|내비|네비|경로안내)(?:를|을)?(?:시작)",
    r"(?:재탐색|다시경로|다시안내)",
    r"(?:음성|소리)(?:를|을)?(?:켜|키|끄|꺼|음소거)",
    r"(?:다시말|반복|한번더|다시들려)",
    r"(?:선택|고르|골라)",
    r"(?:현재|지금|여기|내|제)?(?:위치|어디)(?:를|을)?(?:알려|말해|확인|보여)?",
    r"(?:다음경로|다음길|다음안내|다음회전)(?:을|를)?(?:알려|말해)?",
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


def is_negated_execution_command(compact: str) -> bool:
    """Reject negated destructive actions before any positive substring rule runs."""
    for action_pattern in NEGATABLE_ACTION_PATTERNS:
        action = re.search(action_pattern, compact)
        if not action:
            continue
        tail = compact[action.start():]
        if any(marker in tail for marker in NEGATION_MARKERS):
            return True
    return False


def is_create_report_command(compact: str) -> bool:
    return bool(
        re.fullmatch(
            r"(?:(?:이거|여기|현재위험|위험)(?:을|를)?)?(?:신고|싱고)(?:해|해줘|저장)?",
            compact,
        )
    )


def normalize_destination_slot(value: str) -> str:
    destination = value.strip()
    if destination.endswith("까지"):
        return destination[:-2].strip()
    if destination.endswith("으로"):
        return destination[:-2].strip()
    if destination.endswith("로로"):
        return destination[:-1].strip()
    return destination


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

    if is_negated_execution_command(compact) or any(
        key in compact for key in ["신고취소", "싱고취소", "신고멈춰"]
    ):
        return IntentResult("unknown", 0.85, {"safe_noop": True}, text)

    if any(marker in compact for marker in AMBIGUOUS_DESTINATION_MARKERS) and any(
        marker in compact for marker in ("목적지", "안내", "가자", "설정", "지정", "변경", "바꿔")
    ):
        return IntentResult("unknown", 0.85, {"safe_noop": True}, text)

    if any(key in compact for key in ["목적지취소", "목적지삭제", "목적지없애", "목적지지워"]):
        return IntentResult("cancel_destination", 0.93, {}, text)

    if compact in {"목적지변경", "목적지변경해", "목적지바꿔", "목적지바꿔줘"}:
        return IntentResult("set_destination", 0.9, {}, text)

    if any(
        key in compact
        for key in [
            "다음경로뭐야",
            "다음길뭐야",
            "다음안내뭐야",
            "다음경로알려줘",
            "다음길알려줘",
            "다음안내알려줘",
            "다음회전뭐야",
        ]
    ):
        return IntentResult("next_navigation_instruction", 0.92, {}, text)

    # Destination must be checked before generic navigation/report terms.
    destination_prefix = re.match(r"목적지(?:를|을)?\s*(?P<body>.+)$", text)
    if destination_prefix:
        body = destination_prefix.group("body").strip()
        action_match = re.search(r"\s*(?P<action>설정해?|지정해?|변경해?|바꿔(?:줘)?|가자|안내해?)\s*$", body)
        action = action_match.group("action") if action_match else ""
        raw_destination = body[: action_match.start()].strip() if action_match else body
        destination = normalize_destination_slot(raw_destination)
        if destination:
            requested_start = "안내" in action or "가자" in action
            return IntentResult("set_destination", 0.92, {"destination": destination, "requested_start": requested_start}, text)

    destination_patterns = [
        r"(?P<destination>.+?)(?:으로|로)\s*목적지\s*(?P<action>설정해?|지정해?|변경해?|바꿔(?:줘)?)?$",
        r"(?P<destination>.+?)(?:으로|로)\s*(?P<action>안내해줘|안내해|가자)$",
    ]
    for pattern in destination_patterns:
        match = re.search(pattern, text)
        if match and match.groupdict().get("destination"):
            destination = normalize_destination_slot(match.group("destination"))
            destination = re.sub(r"(?:설정해?|지정해?|변경해?|바꿔(?:줘)?|안내해?|가자)$", "", destination).strip()
            if destination:
                action = match.groupdict().get("action") or ""
                requested_start = "안내" in action or "가자" in action
                return IntentResult("set_destination", 0.92, {"destination": destination, "requested_start": requested_start}, text)

    candidate_index = candidate_index_from_command(text)
    if candidate_index is not None:
        return IntentResult("select_destination_candidate", 0.91, {"candidate_index": candidate_index}, text)

    if is_create_report_command(compact):
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
    acoustic_allowed: bool | None = None,
) -> IntentPolicy:
    """Return the safe execution/reprompt policy for a classified voice command."""
    if result.intent == "unknown":
        if result.slots.get("safe_noop"):
            return IntentPolicy("reprompt", False, "safe_noop", SAFE_NOOP_PROMPT)
        return IntentPolicy("reprompt", False, "unknown_intent", UNKNOWN_INTENT_PROMPT)

    if result.score < threshold:
        return IntentPolicy("reprompt", False, "low_confidence", LOW_CONFIDENCE_PROMPT)

    if acoustic_allowed is False:
        return IntentPolicy("reprompt", False, "low_acoustic_confidence", LOW_ACOUSTIC_CONFIDENCE_PROMPT)

    if result.intent == "set_destination" and not result.slots.get("destination"):
        return IntentPolicy("reprompt", False, "missing_destination", MISSING_DESTINATION_PROMPT)

    candidate_index = result.slots.get("candidate_index")
    if result.intent == "select_destination_candidate" and (type(candidate_index) is not int or candidate_index < 1):
        return IntentPolicy("reprompt", False, "missing_candidate_index", MISSING_CANDIDATE_INDEX_PROMPT)

    return IntentPolicy("execute", True, None, None)


def intent_response_payload(
    result: IntentResult,
    *,
    acoustic_confidence: float | None = None,
    avg_logprob: float | None = None,
    no_speech_probability: float | None = None,
    acoustic_allowed: bool | None = None,
) -> dict[str, Any]:
    policy = evaluate_intent_policy(result, acoustic_allowed=acoustic_allowed)
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
        "acoustic_confidence": acoustic_confidence,
        "avg_logprob": avg_logprob,
        "no_speech_probability": no_speech_probability,
        "acoustic_execution_allowed": acoustic_allowed,
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
            {"name": "cancel_destination", "should_execute": True, "slots": []},
            {"name": "next_navigation_instruction", "should_execute": True, "slots": []},
            {"name": "get_current_location", "should_execute": True, "slots": []},
            {"name": "unknown", "should_execute": False, "slots": ["safe_noop"]},
        ],
        "safe_noop_examples": ["신고하지 마", "신고 취소"],
    }
