from pathlib import Path

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from voice.server import TTSRequest, speech_phrases, speech_tts, speech_tts_cache_status
from voice.tts import LocalTTSEngine, TTSResult


class NoLoadTTSEngine(LocalTTSEngine):
    def load(self) -> None:  # pragma: no cover - test fails if this is called
        raise AssertionError("cache hit must not load the TTS model")


def test_tts_cache_hit_returns_existing_file_without_model_load(tmp_path: Path) -> None:
    engine = NoLoadTTSEngine(cache_dir=tmp_path)
    cached_path = engine._cache_path("안전하게 이동하세요.", "custom")
    cached_path.write_bytes(b"cached wav")

    result = engine.synthesize("안전하게 이동하세요.", use_cache=True, mode="custom")

    assert result.output_path == cached_path
    assert result.cached is True
    assert result.duration_sec == 0.0


def test_tts_server_returns_configured_fallback_wav_on_runtime_error(monkeypatch, tmp_path: Path) -> None:
    fallback_path = tmp_path / "fallback.wav"
    fallback_path.write_bytes(b"fallback wav")

    class BrokenTTSEngine:
        def synthesize(self, *args, **kwargs):
            raise RuntimeError("tts unavailable")

    monkeypatch.setenv("VOICE_TTS_FALLBACK_WAV", str(fallback_path))
    monkeypatch.setattr("voice.server.get_tts_engine", lambda: BrokenTTSEngine())

    response = speech_tts(TTSRequest(text="테스트", allow_fallback=True))

    assert Path(response.path) == fallback_path
    assert response.headers["x-voice-fallback"] == "true"
    assert response.headers["x-voice-model"] == "fallback"


def test_tts_server_reports_cache_hit_header(monkeypatch, tmp_path: Path) -> None:
    cached_path = tmp_path / "cached.wav"
    cached_path.write_bytes(b"cached wav")

    class CachedTTSEngine:
        def synthesize(self, *args, **kwargs):
            return TTSResult(cached_path, sample_rate=0, duration_sec=0.0, model="fake-tts", cached=True, mode="custom")

    monkeypatch.setattr("voice.server.get_tts_engine", lambda: CachedTTSEngine())

    response = speech_tts(TTSRequest(phrase_id="navigation.ready"))

    assert Path(response.path) == cached_path
    assert response.headers["x-voice-cached"] == "true"
    assert response.headers["x-voice-fallback"] == "false"
    assert response.headers["x-voice-model"] == "fake-tts"


def test_tts_server_respects_allow_fallback_false(monkeypatch, tmp_path: Path) -> None:
    fallback_path = tmp_path / "fallback.wav"
    fallback_path.write_bytes(b"fallback wav")

    class BrokenTTSEngine:
        def synthesize(self, *args, **kwargs):
            raise RuntimeError("tts unavailable")

    monkeypatch.setenv("VOICE_TTS_FALLBACK_WAV", str(fallback_path))
    monkeypatch.setattr("voice.server.get_tts_engine", lambda: BrokenTTSEngine())

    with pytest.raises(HTTPException) as exc_info:
        speech_tts(TTSRequest(text="테스트", allow_fallback=False))

    assert exc_info.value.status_code == 503
    assert "tts unavailable" in str(exc_info.value.detail)


def test_tts_cache_status_does_not_load_model(monkeypatch, tmp_path: Path) -> None:
    engine = NoLoadTTSEngine(cache_dir=tmp_path)
    cached_path = engine._cache_path("길안내를 시작할 준비가 됐습니다.", "custom")
    cached_path.write_bytes(b"cached wav")
    monkeypatch.setattr("voice.server.get_tts_engine", lambda: engine)

    response = speech_tts_cache_status(TTSRequest(phrase_id="navigation.ready"))

    assert response["schema_version"] == "walksafe.tts_cache_status.v1"
    assert response["cached"] is True
    assert response["phrase_id"] == "navigation.ready"
    assert response["path_suffix"] == ".wav"


def test_tts_cache_key_changes_when_voice_inputs_change(tmp_path: Path) -> None:
    engine = NoLoadTTSEngine(cache_dir=tmp_path, speaker="alice")

    base = engine._cache_path("안전하게 이동하세요.", "custom")
    speaker_changed = NoLoadTTSEngine(cache_dir=tmp_path, speaker="bob")._cache_path("안전하게 이동하세요.", "custom")
    instruct_changed = engine._cache_path("안전하게 이동하세요.", "custom", voice_instruct="더 느리게")
    clone_changed = engine._cache_path("안전하게 이동하세요.", "clone", ref_audio="ref-a.wav", ref_text="참조 문장")

    assert len({base.name, speaker_changed.name, instruct_changed.name, clone_changed.name}) == 4


def test_tts_text_max_length_is_validated() -> None:
    with pytest.raises(ValidationError):
        TTSRequest(text="가" * 181)


def test_tts_phrase_catalog_contains_core_walksafe_phrases() -> None:
    catalog = speech_phrases()
    phrase_ids = {phrase["id"] for phrase in catalog["phrases"]}

    assert {
        "risk.blocking.stop",
        "report.voice.saved",
        "report.voice.failed",
        "location.current.ready",
        "destination.saved",
        "navigation.ready",
    } <= phrase_ids
