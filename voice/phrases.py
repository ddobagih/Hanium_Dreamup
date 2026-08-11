from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class VoicePhrase:
    id: str
    text: str
    category: str


VOICE_PHRASES: tuple[VoicePhrase, ...] = (
    VoicePhrase("risk.blocking.stop", "전방 장애물이 있습니다. 멈추세요.", "risk"),
    VoicePhrase("risk.approaching.avoid", "전방 객체가 다가옵니다. 피하세요.", "risk"),
    VoicePhrase("report.voice.saved", "음성 요청 신고가 저장되었습니다.", "report"),
    VoicePhrase("report.voice.failed", "음성 요청 신고에 실패했습니다.", "report"),
    VoicePhrase("location.current.ready", "현재 위치를 확인했습니다.", "location"),
    VoicePhrase("destination.saved", "목적지를 저장했습니다. 길안내 시작이라고 말하면 시작합니다.", "navigation"),
    VoicePhrase("navigation.ready", "길안내를 시작할 준비가 됐습니다.", "navigation"),
)


def phrase_catalog_payload() -> dict[str, object]:
    return {
        "schema_version": "walksafe.voice_phrases.v1",
        "phrases": [phrase.__dict__ for phrase in VOICE_PHRASES],
    }


def phrase_text_by_id(phrase_id: str) -> str | None:
    for phrase in VOICE_PHRASES:
        if phrase.id == phrase_id:
            return phrase.text
    return None
