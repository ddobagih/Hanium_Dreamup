import asyncio
from pathlib import Path
from types import SimpleNamespace
import threading
import sys

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

import voice.server as voice_server
from voice.server import TranscriptRequest, TTSRequest, speech_phrases, speech_tts, speech_tts_cache_status
from voice.tts import LocalTTSEngine, TTSResult


class NoLoadTTSEngine(LocalTTSEngine):
    def load(self) -> None:  # pragma: no cover - test fails if this is called
        raise AssertionError("cache hit must not load the TTS model")


class _DirectTTSPool:
    ready = True

    async def run(self, action, *arguments, timeout):
        if action == "ready":
            return {"ready": True, "model": "test"}
        return await asyncio.to_thread(
            voice_server.get_tts_engine().synthesize,
            arguments[0],
            use_cache=arguments[1],
        )


class _ConnectedRequest:
    async def is_disconnected(self) -> bool:
        return False


@pytest.fixture(autouse=True)
def direct_tts_pool(monkeypatch):
    monkeypatch.setattr(voice_server, "TTS_INFERENCE_POOL", _DirectTTSPool())


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

    response = asyncio.run(speech_tts(_ConnectedRequest(), TTSRequest(text="테스트", allow_fallback=True)))

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

    response = asyncio.run(speech_tts(_ConnectedRequest(), TTSRequest(phrase_id="navigation.ready")))

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
        asyncio.run(speech_tts(_ConnectedRequest(), TTSRequest(text="테스트", allow_fallback=False)))

    assert exc_info.value.status_code == 503
    assert exc_info.value.detail["code"] == "tts_unavailable"


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


def test_voice_request_text_fields_have_bounded_lengths() -> None:
    with pytest.raises(ValidationError):
        TranscriptRequest(transcript="가" * 201)
    with pytest.raises(ValidationError):
        TTSRequest(phrase_id="x" * 81)


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


def test_tts_inference_runs_off_the_event_loop_thread(monkeypatch, tmp_path: Path) -> None:
    caller_thread = threading.get_ident()
    inference_threads: list[int] = []
    output = tmp_path / "generated.wav"
    output.write_bytes(b"wav")

    class Engine:
        def synthesize(self, *_args, **_kwargs) -> TTSResult:
            inference_threads.append(threading.get_ident())
            return TTSResult(output, 24_000, 0.01, "fake", False, "custom")

    monkeypatch.setattr("voice.server.get_tts_engine", lambda: Engine())

    response = asyncio.run(speech_tts(_ConnectedRequest(), TTSRequest(text="테스트")))

    assert Path(response.path) == output
    assert inference_threads and inference_threads[0] != caller_thread


def test_tts_queue_timeout_fails_closed_without_model_call(monkeypatch) -> None:
    semaphore = asyncio.Semaphore(0)
    monkeypatch.setattr("voice.server.TTS_INFERENCE_SEMAPHORE", semaphore)
    monkeypatch.setenv("VOICE_TTS_QUEUE_TIMEOUT_SECONDS", "0.1")
    monkeypatch.setattr(
        "voice.server.get_tts_engine",
        lambda: pytest.fail("busy queue must not call the TTS engine"),
    )

    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(speech_tts(_ConnectedRequest(), TTSRequest(text="테스트")))

    assert exc_info.value.status_code == 503
    assert exc_info.value.detail["code"] == "tts_busy"


@pytest.mark.parametrize(("mode", "dtype"), [("invalid", "bfloat16"), ("custom", "not-a-dtype")])
def test_tts_engine_rejects_invalid_runtime_configuration(tmp_path: Path, mode: str, dtype: str) -> None:
    with pytest.raises(ValueError):
        LocalTTSEngine(cache_dir=tmp_path, mode=mode, dtype=dtype)


def test_tts_cache_write_is_atomic_and_retention_is_bounded(monkeypatch, tmp_path: Path) -> None:
    class FakeModel:
        def generate_custom_voice(self, **_kwargs):
            return [[0.0, 0.1]], 24_000

    class FakeEngine(LocalTTSEngine):
        def load(self) -> None:
            self._model = FakeModel()

    def fake_write(path: str, _samples, _sample_rate: int) -> None:
        Path(path).write_bytes(b"generated-wav")

    monkeypatch.setitem(sys.modules, "soundfile", SimpleNamespace(write=fake_write))
    engine = FakeEngine(cache_dir=tmp_path, speaker="test", cache_max_files=2)

    for text in ("첫 번째", "두 번째", "세 번째"):
        engine.synthesize(text, use_cache=False)

    cached = list(tmp_path.glob("qwen3_tts_*.wav"))
    assert len(cached) == 2
    assert all(path.read_bytes() == b"generated-wav" for path in cached)
    assert list(tmp_path.glob("*.tmp.wav")) == []
