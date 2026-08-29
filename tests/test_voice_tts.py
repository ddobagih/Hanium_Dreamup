import asyncio
from io import BytesIO
from pathlib import Path
from types import SimpleNamespace
import threading
import sys
import wave

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from pydantic import ValidationError

import voice.server as voice_server
from voice.server import TranscriptRequest, TTSRequest, speech_phrases, speech_tts, speech_tts_cache_status
from voice.tts import LocalTTSEngine, TTSResult


MODEL_REVISION = "a" * 40


def _wav_bytes(duration_seconds: float = 0.1, sample_rate: int = 8_000) -> bytes:
    output = BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(sample_rate)
        audio.writeframes(b"\0\0" * int(duration_seconds * sample_rate))
    return output.getvalue()


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
    voice_server.TTS_REQUEST_RATE_LIMITER.reset()


def test_tts_cache_hit_returns_existing_file_without_model_load(tmp_path: Path) -> None:
    engine = NoLoadTTSEngine(cache_dir=tmp_path, model_revision=MODEL_REVISION)
    cached_path = engine._cache_path("안전하게 이동하세요.", "custom")
    cached_path.write_bytes(b"cached wav")

    result = engine.synthesize("안전하게 이동하세요.", use_cache=True, mode="custom")

    assert result.output_path == cached_path
    assert result.cached is True
    assert result.duration_sec == 0.0


def test_tts_server_returns_configured_fallback_wav_on_runtime_error(monkeypatch, tmp_path: Path) -> None:
    fallback_path = tmp_path / "fallback.wav"
    fallback_path.write_bytes(_wav_bytes())

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
    cached_path.write_bytes(_wav_bytes())

    class CachedTTSEngine:
        def synthesize(self, *args, **kwargs):
            return TTSResult(
                cached_path,
                sample_rate=0,
                duration_sec=0.0,
                model="fake-tts",
                cached=True,
                mode="custom",
                model_revision=MODEL_REVISION,
            )

    monkeypatch.setattr("voice.server.get_tts_engine", lambda: CachedTTSEngine())

    response = asyncio.run(
        speech_tts(
            _ConnectedRequest(),
            TTSRequest(phrase_id="navigation.ready", use_cache=True),
        )
    )

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
    engine = NoLoadTTSEngine(cache_dir=tmp_path, model_revision=MODEL_REVISION)
    cached_path = engine._cache_path("길안내를 시작할 준비가 됐습니다.", "custom")
    cached_path.write_bytes(b"cached wav")
    monkeypatch.setattr("voice.server.get_tts_engine", lambda: engine)

    response = speech_tts_cache_status(TTSRequest(phrase_id="navigation.ready"))

    assert response["schema_version"] == "walksafe.tts_cache_status.v1"
    assert response["cached"] is True
    assert response["phrase_id"] == "navigation.ready"
    assert response["path_suffix"] == ".wav"


def test_tts_cache_key_changes_when_voice_inputs_change(tmp_path: Path) -> None:
    engine = NoLoadTTSEngine(cache_dir=tmp_path, speaker="Sohee", model_revision=MODEL_REVISION)

    base = engine._cache_path("안전하게 이동하세요.", "custom")
    speaker_changed = NoLoadTTSEngine(
        cache_dir=tmp_path,
        speaker="Ryan",
        model_revision=MODEL_REVISION,
    )._cache_path("안전하게 이동하세요.", "custom")
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
    output.write_bytes(_wav_bytes())

    class Engine:
        def synthesize(self, *_args, **_kwargs) -> TTSResult:
            inference_threads.append(threading.get_ident())
            return TTSResult(
                output,
                24_000,
                0.01,
                "fake",
                False,
                "custom",
                model_revision=MODEL_REVISION,
            )

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


def test_tts_phrase_cache_is_bounded_and_arbitrary_text_is_transient(monkeypatch, tmp_path: Path) -> None:
    class FakeModel:
        def generate_custom_voice(self, **_kwargs):
            return [[0.0, 0.1]], 24_000

    class FakeEngine(LocalTTSEngine):
        def load(self) -> None:
            self._model = FakeModel()
            self._loaded_revision = self.model_revision

    def fake_write(path: str, _samples, _sample_rate: int) -> None:
        Path(path).write_bytes(b"generated-wav")

    monkeypatch.setitem(sys.modules, "soundfile", SimpleNamespace(write=fake_write))
    engine = FakeEngine(
        cache_dir=tmp_path,
        speaker="Sohee",
        cache_max_files=2,
        model_revision=MODEL_REVISION,
    )

    for text in ("첫 번째", "두 번째", "세 번째"):
        engine.synthesize(text, use_cache=True)

    cached = list(tmp_path.glob("qwen3_tts_*.wav"))
    assert len(cached) == 2
    assert all(path.read_bytes() == b"generated-wav" for path in cached)
    assert list(tmp_path.glob("*.tmp.wav")) == []

    transient = engine.synthesize("사용자 임의 문장", use_cache=False)
    assert transient.transient is True
    assert transient.output_path.name.startswith(".tts-request-")
    assert len(list(tmp_path.glob("qwen3_tts_*.wav"))) == 2
    transient.output_path.unlink()


def test_tts_defaults_to_korean_sohee_and_rejects_unlisted_speaker(tmp_path: Path) -> None:
    assert LocalTTSEngine(cache_dir=tmp_path).speaker == "Sohee"
    with pytest.raises(ValueError, match="supported Qwen3-TTS presets"):
        LocalTTSEngine(cache_dir=tmp_path, speaker="arbitrary-user-speaker")


def test_tts_arbitrary_text_cache_and_unicode_controls_are_rejected(monkeypatch) -> None:
    with pytest.raises(ValidationError):
        TTSRequest(text="안내\u0000문장")
    monkeypatch.setattr(
        "voice.server.get_tts_engine",
        lambda: pytest.fail("cache policy must reject before model inference"),
    )
    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(
            speech_tts(
                _ConnectedRequest(),
                TTSRequest(text="사용자 임의 문장", use_cache=True),
            )
        )
    assert exc_info.value.status_code == 422
    assert exc_info.value.detail["code"] == "tts_cache_not_allowed"


def test_tts_success_is_no_store_and_duration_is_bounded(monkeypatch, tmp_path: Path) -> None:
    output = tmp_path / "generated.wav"
    output.write_bytes(_wav_bytes(duration_seconds=2.0))

    class Engine:
        def synthesize(self, *_args, **_kwargs) -> TTSResult:
            return TTSResult(
                output,
                8_000,
                0.01,
                "fake",
                False,
                "custom",
                model_revision=MODEL_REVISION,
            )

    monkeypatch.setenv("VOICE_TTS_MODEL_REVISION", "b" * 40)
    monkeypatch.setattr("voice.server.get_tts_engine", lambda: Engine())
    response = asyncio.run(speech_tts(_ConnectedRequest(), TTSRequest(text="테스트")))
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["x-voice-model-revision"] == MODEL_REVISION

    monkeypatch.setenv("VOICE_TTS_MAX_AUDIO_DURATION_SECONDS", "1")
    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(speech_tts(_ConnectedRequest(), TTSRequest(text="테스트")))
    assert exc_info.value.status_code == 502
    assert exc_info.value.detail["code"] == "tts_response_invalid"


def test_tts_missing_worker_model_revision_fails_closed_and_removes_transient_file(
    monkeypatch,
    tmp_path: Path,
) -> None:
    output = tmp_path / ".tts-request-unbound.wav"
    output.write_bytes(_wav_bytes())

    class Engine:
        def synthesize(self, *_args, **_kwargs) -> TTSResult:
            return TTSResult(
                output,
                8_000,
                0.01,
                "fake",
                False,
                "custom",
                transient=True,
            )

    monkeypatch.setenv("VOICE_TTS_MODEL_REVISION", MODEL_REVISION)
    monkeypatch.setattr("voice.server.get_tts_engine", lambda: Engine())

    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(speech_tts(_ConnectedRequest(), TTSRequest(text="임시 안내")))

    assert exc_info.value.status_code == 503
    assert exc_info.value.detail["code"] == "tts_model_revision_unavailable"
    assert output.exists() is False


def test_tts_transient_user_audio_is_unlinked_before_response_returns(monkeypatch, tmp_path: Path) -> None:
    output = tmp_path / ".tts-request-test.wav"
    wav = _wav_bytes()
    output.write_bytes(wav)

    class Engine:
        def synthesize(self, *_args, **_kwargs) -> TTSResult:
            return TTSResult(
                output,
                8_000,
                0.01,
                "fake",
                False,
                "custom",
                model_revision=MODEL_REVISION,
                transient=True,
            )

    monkeypatch.setattr("voice.server.get_tts_engine", lambda: Engine())
    response = asyncio.run(speech_tts(_ConnectedRequest(), TTSRequest(text="임시 안내")))

    assert response.body == wav
    assert output.exists() is False


def test_tts_http_errors_are_no_store_and_body_and_rate_limits_precede_inference(monkeypatch) -> None:
    token = "voice-service-token-for-tts-http-tests-12345"
    monkeypatch.setenv("VOICE_SERVICE_TOKEN", token)
    monkeypatch.setenv("VOICE_TTS_GLOBAL_RATE_LIMIT", "1")
    client = TestClient(voice_server.app)
    headers = {
        "x-walksafe-voice-service-token": token,
        "x-walksafe-actor-id": "tts-test-actor",
        "x-walksafe-voice-client-ip": "203.0.113.20",
    }

    invalid = client.post("/speech/tts", headers=headers, json={"text": "안내\u0000문장"})
    limited = client.post("/speech/tts", headers=headers, json={"text": "두 번째 문장"})

    assert invalid.status_code == 422
    assert invalid.headers["cache-control"] == "no-store"
    assert limited.status_code == 429
    assert limited.headers["cache-control"] == "no-store"

    voice_server.TTS_REQUEST_RATE_LIMITER.reset()
    monkeypatch.delenv("VOICE_TTS_GLOBAL_RATE_LIMIT")
    oversized = client.post(
        "/speech/tts",
        headers={**headers, "content-type": "application/json"},
        content=b"{" + b"x" * 5000 + b"}",
    )
    assert oversized.status_code == 413
    assert oversized.headers["cache-control"] == "no-store"
