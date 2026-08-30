from __future__ import annotations

import asyncio
from concurrent.futures import Future
from concurrent.futures.process import BrokenProcessPool
from collections import deque
import hashlib
from io import BytesIO
import json
import threading
import wave
import time

import pytest
from fastapi import HTTPException, Response, status
from fastapi.testclient import TestClient

import voice.server as voice_server
from voice.inference_process import IsolatedInferencePool, VoiceInferenceTimeout
from voice.server import speech_stt
from voice.stt import STTAcousticEvidence, STTResult


CLEAR_ACOUSTIC_EVIDENCE = STTAcousticEvidence(
    avg_logprob=-0.2,
    no_speech_probability=0.05,
    confidence=0.76,
    execution_allowed=True,
)
MODEL_REVISION = "a" * 40


class _DirectSTTPool:
    ready = True

    async def run(self, action, *arguments, timeout):
        if action == "ready":
            return {"ready": True, "model": "test"}
        return await asyncio.to_thread(voice_server.get_stt_engine().transcribe_file, arguments[0])


@pytest.fixture(autouse=True)
def direct_stt_pool(monkeypatch):
    monkeypatch.setattr(voice_server, "STT_INFERENCE_POOL", _DirectSTTPool())


class _AudioUpload:
    filename = "sample.wav"
    content_type = "audio/wav"

    def __init__(self, content: bytes) -> None:
        self._content = content
        self._read = False
        self.size = len(content)

    async def read(self, _size: int) -> bytes:
        if self._read:
            return b""
        self._read = True
        return self._content

    async def close(self) -> None:
        return None


class _ConnectedRequest:
    async def is_disconnected(self) -> bool:
        return False


def _wav_bytes(duration_seconds: float = 0.1, sample_rate: int = 8_000) -> bytes:
    output = BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(sample_rate)
        audio.writeframes(b"\0\0" * int(duration_seconds * sample_rate))
    return output.getvalue()


def _stt_result(transcript: str = "주변 설명해줘") -> STTResult:
    return STTResult(
        transcript=transcript,
        intent="describe_surroundings",
        score=1.0,
        slots={},
        language="ko",
        duration_sec=0.01,
        segments=[],
        model="test",
        model_revision=MODEL_REVISION,
        acoustic=CLEAR_ACOUSTIC_EVIDENCE,
    )


def _service_headers(token: str) -> dict[str, str]:
    return {
        "x-walksafe-voice-service-token": token,
        "x-walksafe-actor-id": "tester.kim",
        "x-walksafe-voice-client-ip": "203.0.113.7",
    }


def test_stt_inference_runs_off_the_event_loop_thread(monkeypatch) -> None:
    caller_thread = threading.get_ident()
    inference_threads: list[int] = []

    class _Engine:
        def transcribe_file(self, _path) -> STTResult:
            inference_threads.append(threading.get_ident())
            return STTResult(
                transcript="주변 설명해줘",
                intent="describe_surroundings",
                score=1.0,
                slots={},
                language="ko",
                duration_sec=0.01,
                segments=[],
                model="test",
                model_revision=MODEL_REVISION,
                acoustic=CLEAR_ACOUSTIC_EVIDENCE,
            )

    monkeypatch.setattr("voice.server.get_stt_engine", lambda: _Engine())

    response = asyncio.run(speech_stt(_ConnectedRequest(), _AudioUpload(_wav_bytes())))

    assert response["transcript"] == "주변 설명해줘"
    assert inference_threads and inference_threads[0] != caller_thread


def test_stt_response_revision_is_bound_to_the_worker_result(monkeypatch) -> None:
    class _Engine:
        def transcribe_file(self, _path) -> STTResult:
            return _stt_result()

    monkeypatch.setenv("VOICE_STT_MODEL_REVISION", "b" * 40)
    monkeypatch.setattr("voice.server.get_stt_engine", lambda: _Engine())

    response = asyncio.run(speech_stt(_ConnectedRequest(), _AudioUpload(_wav_bytes())))

    assert response["model_revision"] == MODEL_REVISION


def test_stt_missing_model_integrity_configuration_returns_controlled_503(monkeypatch) -> None:
    class _Engine:
        def transcribe_file(self, _path) -> STTResult:
            raise ValueError("immutable revision and manifest are missing")

    monkeypatch.setattr("voice.server.get_stt_engine", lambda: _Engine())

    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(speech_stt(_ConnectedRequest(), _AudioUpload(_wav_bytes())))

    assert exc_info.value.status_code == 503
    assert exc_info.value.detail["code"] == "stt_unavailable"


def test_stt_destination_change_without_name_returns_safe_reprompt(monkeypatch) -> None:
    class _Engine:
        def transcribe_file(self, _path) -> STTResult:
            return STTResult(
                transcript="목적지 변경",
                intent="set_destination",
                score=0.9,
                slots={},
                language="ko",
                duration_sec=0.2,
                segments=[],
                model="test",
                model_revision=MODEL_REVISION,
                acoustic=CLEAR_ACOUSTIC_EVIDENCE,
            )

    monkeypatch.setattr("voice.server.get_stt_engine", lambda: _Engine())

    response = asyncio.run(speech_stt(_ConnectedRequest(), _AudioUpload(_wav_bytes())))

    assert response["intent"] == "set_destination"
    assert response["slots"] == {}
    assert response["action"] == "reprompt"
    assert response["should_execute"] is False
    assert "목적지" in response["prompt"]


def test_stt_low_acoustic_evidence_blocks_report_execution(monkeypatch) -> None:
    class _Engine:
        def transcribe_file(self, _path) -> STTResult:
            return STTResult(
                transcript="신고해",
                intent="create_report",
                score=0.9,
                slots={},
                language="ko",
                duration_sec=0.2,
                segments=[],
                model="test",
                model_revision=MODEL_REVISION,
                acoustic=STTAcousticEvidence(
                    avg_logprob=-1.2,
                    no_speech_probability=0.1,
                    confidence=0.0,
                    execution_allowed=False,
                ),
            )

    monkeypatch.setattr("voice.server.get_stt_engine", lambda: _Engine())

    response = asyncio.run(speech_stt(_ConnectedRequest(), _AudioUpload(_wav_bytes())))

    assert response["intent"] == "create_report"
    assert response["should_execute"] is False
    assert response["reason"] == "low_acoustic_confidence"
    assert response["acoustic_execution_allowed"] is False


def test_isolated_pool_timeout_aborts_hung_capacity() -> None:
    class HungExecutor:
        _processes = {}

        def __init__(self) -> None:
            self.future = Future()
            self.shutdown_called = False

        def submit(self, *_args):
            return self.future

        def shutdown(self, **_kwargs):
            self.shutdown_called = True

    executor = HungExecutor()
    pool = IsolatedInferencePool("stt", 1)
    pool._executor = executor

    with pytest.raises(VoiceInferenceTimeout):
        asyncio.run(pool.run("infer", "sample.wav", timeout=0.01))

    assert executor.shutdown_called is True
    assert pool.ready is False


def test_isolated_pool_aborts_when_dead_worker_rejects_submission() -> None:
    class DeadProcess:
        @staticmethod
        def is_alive() -> bool:
            return False

        @staticmethod
        def join(*_args, **_kwargs) -> None:
            return None

    class BrokenExecutor:
        _processes = {1: DeadProcess()}

        def __init__(self) -> None:
            self.shutdown_called = False

        def submit(self, *_args):
            raise BrokenProcessPool("worker exited")

        def shutdown(self, **_kwargs):
            self.shutdown_called = True

    executor = BrokenExecutor()
    pool = IsolatedInferencePool("stt", 1)
    pool._executor = executor
    pool._ready = True

    with pytest.raises(BrokenProcessPool, match="worker exited"):
        asyncio.run(pool.run("ready", timeout=0.1))

    assert executor.shutdown_called is True
    assert pool.ready is False
    assert pool._executor is None


def test_voice_readiness_requires_loaded_stt_and_tts_workers(monkeypatch) -> None:
    class ReadyPool:
        calls = 0

        async def run(self, action, *arguments, timeout):
            assert action == "ready"
            self.calls += 1
            return {"ready": True, "model": "test-model"}

    stt_pool = ReadyPool()
    tts_pool = ReadyPool()
    monkeypatch.setattr(voice_server, "STT_INFERENCE_POOL", stt_pool)
    monkeypatch.setattr(voice_server, "TTS_INFERENCE_POOL", tts_pool)
    response = Response()

    payload = asyncio.run(voice_server.readiness(response))

    assert response.status_code == 200
    assert payload["status"] == "ready"
    assert payload["checks"]["stt"]["evidence"] == "live_worker_probe"
    assert stt_pool.calls == 1
    assert tts_pool.calls == 1


def test_voice_readiness_reprobes_after_an_earlier_success(monkeypatch) -> None:
    class FailingSecondProbe:
        calls = 0

        async def run(self, action, *arguments, timeout):
            assert action == "ready"
            self.calls += 1
            if self.calls > 1:
                raise RuntimeError("worker exited")
            return {"ready": True, "model": "test-model"}

    stt_pool = FailingSecondProbe()
    tts_pool = FailingSecondProbe()
    monkeypatch.setattr(voice_server, "STT_INFERENCE_POOL", stt_pool)
    monkeypatch.setattr(voice_server, "TTS_INFERENCE_POOL", tts_pool)

    first_response = Response()
    assert asyncio.run(voice_server.readiness(first_response))["status"] == "ready"

    second_response = Response()
    payload = asyncio.run(voice_server.readiness(second_response))

    assert second_response.status_code == status.HTTP_503_SERVICE_UNAVAILABLE
    assert payload["status"] == "not_ready"
    assert payload["checks"]["stt"]["reason"] == "model_warmup_failed"


def test_non_loopback_voice_access_requires_internal_service_token(monkeypatch) -> None:
    monkeypatch.delenv("VOICE_SERVICE_TOKEN", raising=False)
    assert voice_server._voice_request_authorized("127.0.0.1", {}) == (
        False,
        "service_token_not_configured",
    )
    assert voice_server._voice_request_authorized("10.0.0.12", {}) == (
        False,
        "service_token_not_configured",
    )

    token = "voice-service-token-for-tests-12345"
    monkeypatch.setenv("VOICE_SERVICE_TOKEN", token)
    assert voice_server._voice_request_authorized(
        "10.0.0.12",
        {"x-walksafe-voice-service-token": token},
    ) == (True, "service_token")
    assert voice_server._voice_request_authorized(
        "127.0.0.1",
        {"x-walksafe-field-test-token": token},
    ) == (False, "service_token_invalid")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", token)
    assert voice_server._voice_request_authorized(
        "127.0.0.1",
        {"x-walksafe-voice-service-token": token},
    ) == (False, "service_token_not_configured")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", "different-field-service-token-123456")
    monkeypatch.setenv(
        "WALKSAFE_FIELD_ACCOUNTS_JSON",
        json.dumps([{"actor_id": "tester.kim", "token": token}]),
    )
    assert voice_server._voice_request_authorized(
        "127.0.0.1",
        {"x-walksafe-voice-service-token": token},
    ) == (False, "service_token_not_configured")


def test_loopback_http_access_requires_dedicated_service_token(monkeypatch) -> None:
    token = "voice-service-token-for-http-tests-12345"
    client = TestClient(voice_server.app, client=("127.0.0.1", 50_000))

    monkeypatch.delenv("VOICE_SERVICE_TOKEN", raising=False)
    unconfigured = client.get("/health")
    monkeypatch.setenv("VOICE_SERVICE_TOKEN", token)
    denied = client.get("/health")
    allowed = client.get("/health", headers={"x-walksafe-voice-service-token": token})

    assert unconfigured.status_code == 503
    assert unconfigured.json()["detail"]["code"] == "service_token_not_configured"
    assert denied.status_code == 401
    assert denied.json()["detail"]["code"] == "service_token_invalid"
    assert allowed.status_code == 200


def test_voice_openapi_matches_required_service_token_header(monkeypatch) -> None:
    token = "voice-service-token-for-openapi-tests-12345"
    monkeypatch.setenv("VOICE_SERVICE_TOKEN", token)
    client = TestClient(voice_server.app)

    response = client.get("/openapi.json", headers={"x-walksafe-voice-service-token": token})

    assert response.status_code == 200
    schema = response.json()
    scheme = schema["components"]["securitySchemes"]["VoiceServiceToken"]
    assert scheme["type"] == "apiKey"
    assert scheme["in"] == "header"
    assert scheme["name"] == "x-walksafe-voice-service-token"
    operations = [
        operation
        for path_item in schema["paths"].values()
        for method, operation in path_item.items()
        if method in {"get", "post", "put", "patch", "delete"}
    ]
    assert operations
    assert all(operation["security"] == [{"VoiceServiceToken": []}] for operation in operations)
    assert all("401" in operation["responses"] and "503" in operation["responses"] for operation in operations)


def test_deployed_voice_runtime_requires_one_worker_and_replica(monkeypatch, tmp_path) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "prodution")
    with pytest.raises(RuntimeError, match="unsupported value"):
        voice_server.validate_voice_runtime_configuration()

    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "field")
    monkeypatch.delenv("VOICE_SERVICE_TOKEN", raising=False)
    with pytest.raises(RuntimeError, match="VOICE_SERVICE_TOKEN"):
        voice_server.validate_voice_runtime_configuration()

    monkeypatch.setenv("VOICE_SERVICE_TOKEN", "voice-service-token-for-runtime-tests-12345")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", "voice-service-token-for-runtime-tests-12345")
    with pytest.raises(RuntimeError, match="must be dedicated"):
        voice_server.validate_voice_runtime_configuration()
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", "field-token-for-runtime-tests-123456")
    monkeypatch.delenv("VOICE_SERVICE_WORKERS", raising=False)
    monkeypatch.delenv("VOICE_SERVICE_REPLICAS", raising=False)
    with pytest.raises(RuntimeError, match="VOICE_SERVICE_WORKERS=1"):
        voice_server.validate_voice_runtime_configuration()

    monkeypatch.setenv("VOICE_SERVICE_WORKERS", "1")
    monkeypatch.setenv("VOICE_SERVICE_REPLICAS", "2")
    with pytest.raises(RuntimeError, match="VOICE_SERVICE_REPLICAS=1"):
        voice_server.validate_voice_runtime_configuration()

    monkeypatch.setenv("VOICE_SERVICE_REPLICAS", "1")
    monkeypatch.setenv("VOICE_SERVICE_PROCESS_LOCK_PATH", "relative.lock")
    with pytest.raises(RuntimeError, match="must be absolute"):
        voice_server.validate_voice_runtime_configuration()

    monkeypatch.setenv("VOICE_SERVICE_PROCESS_LOCK_PATH", str(tmp_path / "voice.lock"))
    with pytest.raises(RuntimeError, match="VOICE_STT_MODEL_MANIFEST_PATH"):
        voice_server.validate_voice_runtime_configuration()

    monkeypatch.setenv("VOICE_STT_MODEL", "owner/stt-model")
    monkeypatch.setenv("VOICE_STT_MODEL_REVISION", "main")
    monkeypatch.setenv("VOICE_STT_MODEL_MANIFEST_PATH", str(tmp_path / "stt.json"))
    monkeypatch.setenv("VOICE_STT_MODEL_MANIFEST_SHA256", "0" * 64)
    with pytest.raises(RuntimeError, match="40-character"):
        voice_server.validate_voice_runtime_configuration()

    def configure_model(prefix: str, kind: str, model_id: str, files: list[str]) -> None:
        manifest = tmp_path / f"{kind}.json"
        manifest.write_text(
            json.dumps(
                {
                    "schema_version": "walksafe.voice_model_files.v1",
                    "kind": kind,
                    "model_id": model_id,
                    "revision": "a" * 40,
                    "files": {name: "b" * 64 for name in files},
                },
                sort_keys=True,
            ),
            encoding="utf-8",
        )
        monkeypatch.setenv(prefix, model_id)
        monkeypatch.setenv(f"{prefix}_REVISION", "a" * 40)
        monkeypatch.setenv(f"{prefix}_MANIFEST_PATH", str(manifest))
        monkeypatch.setenv(f"{prefix}_MANIFEST_SHA256", hashlib.sha256(manifest.read_bytes()).hexdigest())

    configure_model(
        "VOICE_STT_MODEL",
        "stt",
        "owner/stt-model",
        ["config.json", "model.bin", "tokenizer.json", "vocabulary.json"],
    )
    configure_model(
        "VOICE_TTS_MODEL",
        "tts",
        "owner/tts-model",
        ["config.json", "model.safetensors"],
    )
    voice_server.validate_voice_runtime_configuration()


@pytest.mark.parametrize("value", ["nan", "inf", "-inf", "not-a-number"])
def test_deployed_voice_runtime_rejects_nonfinite_timeout_configuration(
    monkeypatch: pytest.MonkeyPatch,
    value: str,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("VOICE_SERVICE_TOKEN", "voice-service-token-for-runtime-tests-12345")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", "field-token-for-runtime-tests-123456")
    monkeypatch.setenv("VOICE_SERVICE_WORKERS", "1")
    monkeypatch.setenv("VOICE_SERVICE_REPLICAS", "1")
    monkeypatch.setenv("VOICE_STT_RATE_WINDOW_SECONDS", value)

    with pytest.raises(RuntimeError, match="VOICE_STT_RATE_WINDOW_SECONDS must be a finite number"):
        voice_server.validate_voice_runtime_configuration()


def test_nonfinite_development_timeout_uses_a_finite_default_for_real_gates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.setenv("VOICE_STT_RATE_WINDOW_SECONDS", "nan")
    window = voice_server.bounded_env_float(
        "VOICE_STT_RATE_WINDOW_SECONDS",
        1.0,
        minimum=0.01,
        maximum=10.0,
    )
    limiter = voice_server.ProcessLocalRateLimiter(clock=lambda: 0.0)
    assert limiter.check([("actor", 1)], window).allowed is True
    assert limiter.check([("actor", 1)], window).allowed is False

    monkeypatch.setenv("VOICE_STT_UPLOAD_QUEUE_TIMEOUT_SECONDS", "nan")
    timeout = voice_server.bounded_env_float(
        "VOICE_STT_UPLOAD_QUEUE_TIMEOUT_SECONDS",
        0.01,
        minimum=0.01,
        maximum=1.0,
    )

    async def exercise_busy_gate() -> tuple[bool, float]:
        gate = voice_server.UploadConcurrencyGate(1)
        assert await gate.acquire(timeout)
        started = time.monotonic()
        return await gate.acquire(timeout), time.monotonic() - started

    acquired, elapsed = asyncio.run(exercise_busy_gate())
    assert acquired is False
    assert elapsed < 0.5


def test_voice_process_lock_rejects_a_second_local_worker(tmp_path) -> None:
    lock_path = tmp_path / "voice.lock"
    first = voice_server.acquire_single_process_lock(lock_path)
    try:
        with pytest.raises(BlockingIOError):
            voice_server.acquire_single_process_lock(lock_path)
    finally:
        voice_server.release_single_process_lock(first)


def test_voice_process_lock_rejects_replaced_lock_file(tmp_path) -> None:
    lock_path = tmp_path / "voice.lock"
    first = voice_server.acquire_single_process_lock(lock_path)
    try:
        lock_path.rename(tmp_path / "displaced.lock")
        with pytest.raises(BlockingIOError):
            voice_server.acquire_single_process_lock(lock_path)
    finally:
        voice_server.release_single_process_lock(first)


def test_voice_process_lock_rejects_replaced_file_through_normalized_alias(tmp_path) -> None:
    lock_path = tmp_path / "voice.lock"
    alias_directory = tmp_path / "alias"
    alias_directory.mkdir()
    first = voice_server.acquire_single_process_lock(lock_path)
    try:
        lock_path.rename(tmp_path / "displaced.lock")
        with pytest.raises(BlockingIOError):
            voice_server.acquire_single_process_lock(alias_directory / ".." / "voice.lock")
    finally:
        voice_server.release_single_process_lock(first)


def test_voice_process_lock_does_not_follow_the_final_symlink(tmp_path) -> None:
    target = tmp_path / "target.lock"
    target.write_text("", encoding="utf-8")
    target.chmod(0o600)
    (tmp_path / "voice.lock").symlink_to(target)

    with pytest.raises(OSError):
        voice_server.acquire_single_process_lock(tmp_path / "voice.lock")


def test_busy_upload_gate_rejects_before_multipart_temp_copy(monkeypatch) -> None:
    token = "voice-service-token-for-queue-tests-12345"
    monkeypatch.setenv("VOICE_SERVICE_TOKEN", token)
    voice_server.STT_REQUEST_RATE_LIMITER.reset()

    class BusyGate:
        maximum = 1

        async def acquire(self, _timeout: float) -> bool:
            return False

        def release(self) -> None:
            raise AssertionError("an unacquired gate must not be released")

    monkeypatch.setattr(voice_server, "STT_UPLOAD_GATE", BusyGate())
    monkeypatch.setattr(
        "starlette.formparsers.SpooledTemporaryFile",
        lambda *args, **kwargs: pytest.fail("multipart parser must not copy a rejected upload"),
    )
    client = TestClient(voice_server.app)

    response = client.post(
        "/speech/stt",
        headers=_service_headers(token),
        files={"audio": ("sample.wav", _wav_bytes(), "audio/wav")},
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "stt_upload_busy"


def test_declared_request_limit_rejects_without_reading_body(monkeypatch) -> None:
    token = "voice-service-token-for-byte-tests-12345"
    monkeypatch.setenv("VOICE_SERVICE_TOKEN", token)
    monkeypatch.setenv("VOICE_STT_MAX_REQUEST_BYTES", str(64 * 1024))
    voice_server.STT_REQUEST_RATE_LIMITER.reset()
    body_read = False

    def body():
        nonlocal body_read
        body_read = True
        yield b"body-must-not-be-read"

    client = TestClient(voice_server.app)
    response = client.post(
        "/speech/stt",
        headers={
            **_service_headers(token),
            "content-type": "multipart/form-data; boundary=walksafe-test",
            "content-length": str(64 * 1024 + 1),
        },
        content=body(),
    )

    assert response.status_code == 413
    assert response.json()["detail"]["code"] == "voice_request_too_large"
    assert body_read is False


def test_streamed_request_limit_stops_multipart_parser(monkeypatch) -> None:
    token = "voice-service-token-for-stream-tests-12345"
    monkeypatch.setenv("VOICE_SERVICE_TOKEN", token)
    monkeypatch.setenv("VOICE_STT_MAX_REQUEST_BYTES", str(64 * 1024))
    voice_server.STT_REQUEST_RATE_LIMITER.reset()

    def body():
        yield b"x" * 40_000
        yield b"y" * 40_000

    client = TestClient(voice_server.app)
    response = client.post(
        "/speech/stt",
        headers={
            **_service_headers(token),
            "content-type": "multipart/form-data; boundary=walksafe-test",
        },
        content=body(),
    )

    assert response.status_code == 413
    assert response.json()["detail"]["code"] == "voice_request_too_large"


def test_http_actor_rate_and_decoded_audio_duration_limits(monkeypatch) -> None:
    token = "voice-service-token-for-rate-tests-12345"
    monkeypatch.setenv("VOICE_SERVICE_TOKEN", token)
    monkeypatch.setenv("VOICE_STT_ACTOR_RATE_LIMIT", "1")
    monkeypatch.setenv("VOICE_STT_IP_RATE_LIMIT", "10")
    monkeypatch.setenv("VOICE_STT_MAX_AUDIO_DURATION_SECONDS", "1")
    voice_server.STT_REQUEST_RATE_LIMITER.reset()

    class ImmediatePool:
        ready = True

        def __init__(self) -> None:
            self.calls = 0

        async def run(self, action, *_arguments, timeout):
            assert action == "infer"
            assert timeout > 0
            self.calls += 1
            return _stt_result()

    pool = ImmediatePool()
    monkeypatch.setattr(voice_server, "STT_INFERENCE_POOL", pool)
    client = TestClient(voice_server.app)
    first = client.post(
        "/speech/stt",
        headers=_service_headers(token),
        files={"audio": ("sample.wav", _wav_bytes(), "audio/wav")},
    )
    limited = client.post(
        "/speech/stt",
        headers=_service_headers(token),
        files={"audio": ("sample.wav", _wav_bytes(), "audio/wav")},
    )

    assert first.status_code == 200
    assert first.json()["audio_duration_sec"] == pytest.approx(0.1)
    assert limited.status_code == 429
    assert int(limited.headers["retry-after"]) > 0
    assert pool.calls == 1

    voice_server.STT_REQUEST_RATE_LIMITER.reset()
    too_long = client.post(
        "/speech/stt",
        headers=_service_headers(token),
        files={"audio": ("long.wav", _wav_bytes(2.0), "audio/wav")},
    )
    assert too_long.status_code == 422
    assert too_long.json()["detail"]["code"] == "audio_duration_exceeded"
    assert pool.calls == 1


def test_http_global_rate_limit_bounds_distinct_client_keys_before_body_parse(monkeypatch) -> None:
    token = "voice-service-token-for-global-rate-tests-12345"
    monkeypatch.setenv("VOICE_SERVICE_TOKEN", token)
    monkeypatch.setenv("VOICE_STT_GLOBAL_RATE_LIMIT", "2")
    monkeypatch.setenv("VOICE_STT_ACTOR_RATE_LIMIT", "10")
    monkeypatch.setenv("VOICE_STT_IP_RATE_LIMIT", "10")
    voice_server.STT_REQUEST_RATE_LIMITER.reset()
    client = TestClient(voice_server.app)

    responses = []
    for index in range(3):
        headers = _service_headers(token)
        headers["x-walksafe-actor-id"] = f"tester-{index}"
        headers["x-walksafe-voice-client-ip"] = f"203.0.113.{index + 10}"
        responses.append(client.post("/speech/stt", headers=headers, content=b""))

    assert all(response.status_code != 429 for response in responses[:2])
    assert responses[2].status_code == 429
    assert responses[2].json()["detail"]["code"] == "voice_rate_limited"


def test_asgi_disconnect_cancels_inference_and_removes_request_temp_file(monkeypatch) -> None:
    token = "voice-service-token-for-disconnect-tests-12345"
    monkeypatch.setenv("VOICE_SERVICE_TOKEN", token)
    voice_server.STT_REQUEST_RATE_LIMITER.reset()

    class HangingPool:
        ready = True

        def __init__(self) -> None:
            self.cancelled = False
            self.path = None
            self.started = asyncio.Event()

        async def run(self, action, path, timeout):
            assert action == "infer"
            assert timeout > 0
            self.path = path
            self.started.set()
            try:
                await asyncio.sleep(60)
            except asyncio.CancelledError:
                self.cancelled = True
                raise

    pool = HangingPool()
    monkeypatch.setattr(voice_server, "STT_INFERENCE_POOL", pool)
    boundary = "walksafe-disconnect"
    wav = _wav_bytes()
    body = (
        f"--{boundary}\r\n"
        'Content-Disposition: form-data; name="audio"; filename="sample.wav"\r\n'
        "Content-Type: audio/wav\r\n\r\n"
    ).encode() + wav + f"\r\n--{boundary}--\r\n".encode()
    messages = deque([{"type": "http.request", "body": body, "more_body": False}])
    disconnect_sent = False
    sent: list[dict] = []

    async def receive():
        nonlocal disconnect_sent
        if messages:
            return messages.popleft()
        if not disconnect_sent:
            await pool.started.wait()
            disconnect_sent = True
            return {"type": "http.disconnect"}
        await asyncio.sleep(60)
        raise AssertionError("unreachable")

    async def send(message):
        sent.append(message)

    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/speech/stt",
        "raw_path": b"/speech/stt",
        "query_string": b"",
        "server": ("127.0.0.1", 9001),
        "client": ("127.0.0.1", 51_000),
        "headers": [
            (b"content-type", f"multipart/form-data; boundary={boundary}".encode()),
            (b"content-length", str(len(body)).encode()),
            (b"x-walksafe-voice-service-token", token.encode()),
            (b"x-walksafe-actor-id", b"tester.kim"),
        ],
    }

    asyncio.run(voice_server.app(scope, receive, send))

    assert pool.cancelled is True
    assert pool.path is not None
    assert not voice_server.Path(pool.path).exists()
    assert any(message.get("status") == 499 for message in sent if message["type"] == "http.response.start")
