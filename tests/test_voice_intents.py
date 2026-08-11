import pytest

from voice.intents import IntentResult, classify_intent, evaluate_intent_policy, intent_response_payload, intent_schema_payload
from voice.telemetry import intent_telemetry_payload, intent_telemetry_schema


EXECUTION_SCORE_THRESHOLD = 0.5


@pytest.mark.parametrize(
    ("transcript", "expected_intent"),
    [
        ("신고해줘", "create_report"),
        ("위험 신고", "create_report"),
        ("이거 신고", "create_report"),
        ("현재 위험 신고", "create_report"),
        # Existing STT misrecognition candidates should keep working.
        ("싱고해", "create_report"),
        ("현재 위험 싱고", "create_report"),
    ],
)
def test_create_report_korean_variants(transcript, expected_intent):
    result = classify_intent(transcript)

    assert result.intent == expected_intent
    assert result.score >= EXECUTION_SCORE_THRESHOLD
    assert result.slots == {}


@pytest.mark.parametrize(
    ("transcript", "expected_intent"),
    [
        ("음성 켜", "voice_on"),
        ("음성 켜줘", "voice_on"),
        ("소리 켜", "voice_on"),
        ("다시 말해도 돼", "voice_on"),
        ("음성 꺼", "voice_off"),
        ("음성 꺼줘", "voice_off"),
        ("소리 꺼", "voice_off"),
        ("말하지 마", "voice_off"),
        ("음소거", "voice_off"),
        # Existing STT misrecognition candidates should keep working.
        ("음성 꼭", "voice_off"),
        ("음성 끅", "voice_off"),
    ],
)
def test_voice_toggle_korean_variants(transcript, expected_intent):
    result = classify_intent(transcript)

    assert result.intent == expected_intent
    assert result.score >= EXECUTION_SCORE_THRESHOLD
    assert result.slots == {}


@pytest.mark.parametrize(
    "transcript",
    [
        "다시 말해줘",
        "다시 말해",
        "반복해줘",
        "한번 더",
        "다시 들려줘",
    ],
)
def test_repeat_last_korean_variants(transcript):
    result = classify_intent(transcript)

    assert result.intent == "repeat_last"
    assert result.score >= EXECUTION_SCORE_THRESHOLD
    assert result.slots == {}


@pytest.mark.parametrize(
    "transcript",
    [
        "지금 어디야",
        "현재 어디야",
        "여기 어디야",
        "내 위치 알려줘",
        "현재 위치 확인",
        "제 위치 말해줘",
    ],
)
def test_get_current_location_korean_variants(transcript):
    result = classify_intent(transcript)

    assert result.intent == "get_current_location"
    assert result.score >= EXECUTION_SCORE_THRESHOLD
    assert result.slots == {}


@pytest.mark.parametrize(
    ("transcript", "destination"),
    [
        ("목적지 서울역으로 설정해", "서울역"),
        ("목적지 강남역 지정해", "강남역"),
        ("목적지 시청으로 가자", "시청"),
        ("홍대입구역으로 목적지 설정", "홍대입구역"),
        ("목적지 어린이대공원 안내해", "어린이대공원"),
    ],
)
def test_set_destination_extracts_destination_slot(transcript, destination):
    result = classify_intent(transcript)

    assert result.intent == "set_destination"
    assert result.score >= EXECUTION_SCORE_THRESHOLD
    assert result.slots["destination"] == destination


@pytest.mark.parametrize(
    ("transcript", "candidate_index"),
    [
        ("1번 선택", 1),
        ("첫 번째", 1),
        ("두번째 선택", 2),
        ("3번 목적지", 3),
        ("세 번째 후보 골라", 3),
    ],
)
def test_select_destination_candidate_extracts_1_based_index(transcript, candidate_index):
    result = classify_intent(transcript)

    assert result.intent == "select_destination_candidate"
    assert result.score >= EXECUTION_SCORE_THRESHOLD
    assert result.slots == {"candidate_index": candidate_index}


@pytest.mark.parametrize(
    "transcript",
    [
        "길 안내 시작",
        "안내 시작해줘",
        "내비 시작",
        "네비 시작",
        "경로 안내",
        # Existing STT misrecognition candidate should keep working.
        "길었네 시작",
    ],
)
def test_start_navigation_korean_variants(transcript):
    result = classify_intent(transcript)

    assert result.intent == "start_navigation"
    assert result.score >= EXECUTION_SCORE_THRESHOLD
    assert result.slots == {}


@pytest.mark.parametrize("transcript", ["재탐색", "재탐색해줘", "다시 경로", "다시 안내"])
def test_reroute_navigation_korean_variants(transcript):
    result = classify_intent(transcript)

    assert result.intent == "reroute_navigation"
    assert result.score >= EXECUTION_SCORE_THRESHOLD
    assert result.slots == {}


@pytest.mark.parametrize("transcript", ["길 안내 중지", "안내 취소", "내비 중지", "네비 중지"])
def test_stop_navigation_korean_variants(transcript):
    result = classify_intent(transcript)

    assert result.intent == "stop_navigation"
    assert result.score >= EXECUTION_SCORE_THRESHOLD
    assert result.slots == {}


@pytest.mark.parametrize("transcript", ["", "   \n\t  "])
def test_empty_transcript_is_unknown_and_not_executable(transcript):
    result = classify_intent(transcript)

    assert result.intent == "unknown"
    assert result.score < EXECUTION_SCORE_THRESHOLD
    assert result.slots == {}


@pytest.mark.parametrize(
    "transcript",
    [
        "오늘 날씨 알려줘",
        "카메라 켜줘",
        "그냥 걸어갈게",
    ],
)
def test_unknown_or_low_confidence_intent_is_not_executable(transcript):
    result = classify_intent(transcript)

    assert result.intent == "unknown"
    assert result.score < EXECUTION_SCORE_THRESHOLD
    assert result.slots == {}


def test_unknown_intent_policy_returns_reprompt_guidance():
    result = classify_intent("오늘 날씨 알려줘")
    policy = evaluate_intent_policy(result)
    payload = intent_response_payload(result)

    assert policy.action == "reprompt"
    assert policy.should_execute is False
    assert policy.reason == "unknown_intent"
    assert "다시 말씀" in (policy.prompt or "")
    assert payload["action"] == "reprompt"
    assert payload["should_execute"] is False
    assert payload["reason"] == "unknown_intent"
    assert "다시 말씀" in payload["prompt"]


def test_low_confidence_policy_returns_reprompt_guidance():
    result = IntentResult("create_report", 0.4, {}, "신고")
    policy = evaluate_intent_policy(result)

    assert policy.action == "reprompt"
    assert policy.should_execute is False
    assert policy.reason == "low_confidence"
    assert policy.prompt == "명령 신뢰도가 낮습니다. 다시 말씀해 주세요."


def test_known_destination_intent_remains_executable_with_destination_slot():
    result = classify_intent("목적지 서울역으로 설정해")
    policy = evaluate_intent_policy(result)
    payload = intent_response_payload(result)

    assert result.intent == "set_destination"
    assert result.slots["destination"] == "서울역"
    assert policy.action == "execute"
    assert policy.should_execute is True
    assert payload["should_execute"] is True
    assert payload["prompt"] is None


@pytest.mark.parametrize("transcript", ["신고하지 마", "신고 취소", "신고하지 말아줘"])
def test_negative_report_commands_are_safe_noop(transcript):
    result = classify_intent(transcript)
    policy = evaluate_intent_policy(result)

    assert result.intent == "unknown"
    assert result.slots == {"safe_noop": True}
    assert policy.should_execute is False
    assert policy.reason == "safe_noop"


def test_destination_name_containing_report_is_not_create_report():
    result = classify_intent("신고센터로 안내해줘")

    assert result.intent == "set_destination"
    assert result.slots["destination"] == "신고센터"
    assert result.slots["requested_start"] is True


def test_intent_schema_lists_safe_navigation_and_unknown_noop():
    schema = intent_schema_payload()
    names = {item["name"] for item in schema["intents"]}

    assert {"stop_navigation", "reroute_navigation", "unknown"} <= names
    unknown = next(item for item in schema["intents"] if item["name"] == "unknown")
    assert unknown["should_execute"] is False


def test_intent_telemetry_excludes_raw_audio_and_transcript_text():
    result = classify_intent("신고센터로 안내해줘")
    payload = intent_telemetry_payload(result)
    schema = intent_telemetry_schema()

    assert payload["schema_version"] == "walksafe.voice_intent_telemetry.v1"
    assert payload["intent"] == "set_destination"
    assert payload["slot_keys"] == ["destination", "requested_start"]
    assert "신고센터" not in str(payload)
    assert "normalized" not in payload
    assert schema["stores_raw_audio"] is False
    assert schema["stores_transcript_text"] is False


def test_candidate_selection_intent_remains_executable_with_candidate_index_slot():
    result = classify_intent("두번째 선택")
    policy = evaluate_intent_policy(result)
    payload = intent_response_payload(result)

    assert result.intent == "select_destination_candidate"
    assert result.slots == {"candidate_index": 2}
    assert policy.action == "execute"
    assert policy.should_execute is True
    assert payload["should_execute"] is True
    assert payload["prompt"] is None


def test_destination_intent_without_slot_asks_destination_again():
    result = IntentResult("set_destination", 0.92, {}, "목적지")
    policy = evaluate_intent_policy(result)

    assert policy.action == "reprompt"
    assert policy.should_execute is False
    assert policy.reason == "missing_destination"
    assert policy.prompt == "목적지를 다시 말씀해 주세요."


def test_candidate_selection_intent_without_index_asks_candidate_again():
    result = IntentResult("select_destination_candidate", 0.91, {}, "선택")
    policy = evaluate_intent_policy(result)

    assert policy.action == "reprompt"
    assert policy.should_execute is False
    assert policy.reason == "missing_candidate_index"
    assert policy.prompt == "몇 번째 목적지를 선택할지 다시 말씀해 주세요."
