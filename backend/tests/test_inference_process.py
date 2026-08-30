from __future__ import annotations

import asyncio
from datetime import datetime, timezone

import pytest

from backend.app.config import Settings, get_settings
from backend.app import main as main_app
from backend.app.schemas import DetectContext
from backend.app.services.inference_process import (
    InferenceDeadlineExceeded,
    InferenceProcessRunner,
    InferenceQueueDeadlineExceeded,
)


class HungConnection:
    closed = False

    def send(self, _payload: object) -> None:
        return None

    def poll(self, _timeout: float) -> bool:
        return False

    def close(self) -> None:
        self.closed = True


class HungProcess:
    terminated = False
    killed = False
    alive = True

    def is_alive(self) -> bool:
        return self.alive

    def terminate(self) -> None:
        self.terminated = True
        self.alive = False

    def kill(self) -> None:
        self.killed = True
        self.alive = False

    def join(self, timeout: float) -> None:
        assert timeout <= 0.5


class SuccessfulConnection:
    closed = False

    def __init__(self) -> None:
        self.last_request: tuple[object, ...] | None = None
        self.poll_timeouts: list[float] = []

    def send(self, payload: object) -> None:
        assert isinstance(payload, tuple)
        self.last_request = payload

    def poll(self, timeout: float) -> bool:
        self.poll_timeouts.append(timeout)
        return True

    def recv(self) -> tuple[object, ...]:
        assert self.last_request is not None
        return (self.last_request[0], "ok", "response")

    def close(self) -> None:
        self.closed = True


class ErrorConnection(SuccessfulConnection):
    def recv(self) -> tuple[object, ...]:
        assert self.last_request is not None
        return (self.last_request[0], "error", "RuntimeError", "/srv/private/model.pt")


def test_hung_inference_process_is_terminated_and_runner_capacity_is_released() -> None:
    runner = InferenceProcessRunner(timeout_seconds=0.01, startup_timeout_seconds=0.01)
    connection = HungConnection()
    process = HungProcess()
    runner._connection = connection  # type: ignore[assignment]
    runner._process = process  # type: ignore[assignment]

    with pytest.raises(InferenceDeadlineExceeded, match="execution deadline"):
        runner._run("legacy", b"image", "image/jpeg", DetectContext(), object())

    assert process.terminated is True
    assert connection.closed is True
    assert runner._process is None
    assert runner._connection is None
    assert runner._lock.acquire(blocking=False) is True
    runner._lock.release()


def test_busy_inference_queue_does_not_claim_that_the_worker_was_restarted() -> None:
    runner = InferenceProcessRunner(timeout_seconds=1, queue_timeout_seconds=0.001)
    runner._lock.acquire()
    try:
        with pytest.raises(InferenceQueueDeadlineExceeded, match="queue deadline"):
            runner._run("legacy", b"image", "image/jpeg", DetectContext(), object())
    finally:
        runner._lock.release()

    assert runner._process is None


def test_cold_start_has_a_separate_bounded_deadline_then_uses_execution_deadline() -> None:
    runner = InferenceProcessRunner(timeout_seconds=0.02, startup_timeout_seconds=1.0)
    connection = SuccessfulConnection()
    process = HungProcess()
    runner._connection = connection  # type: ignore[assignment]
    runner._process = process  # type: ignore[assignment]

    assert runner._run("legacy", b"image", "image/jpeg", DetectContext(), object()) == "response"
    assert runner._warmed_kinds == {"legacy"}
    assert runner._run("legacy", b"image", "image/jpeg", DetectContext(), object()) == "response"

    assert connection.poll_timeouts[0] > 0.9
    assert 0 < connection.poll_timeouts[1] <= 0.02


def test_each_inference_contract_gets_its_own_cold_start_deadline() -> None:
    runner = InferenceProcessRunner(timeout_seconds=0.02, startup_timeout_seconds=1.0)
    connection = SuccessfulConnection()
    process = HungProcess()
    runner._connection = connection  # type: ignore[assignment]
    runner._process = process  # type: ignore[assignment]

    assert runner._run("legacy", b"image", "image/jpeg", DetectContext(), object()) == "response"
    assert runner._run("v2", b"image", "image/jpeg", DetectContext(), object()) == "response"
    assert runner._warmed_kinds == {"legacy", "v2"}
    assert connection.poll_timeouts[0] > 0.9
    assert connection.poll_timeouts[1] > 0.9


def test_worker_runtime_error_restarts_the_isolated_process() -> None:
    runner = InferenceProcessRunner(timeout_seconds=1)
    connection = ErrorConnection()
    process = HungProcess()
    runner._connection = connection  # type: ignore[assignment]
    runner._process = process  # type: ignore[assignment]

    with pytest.raises(RuntimeError, match="RuntimeError"):
        runner._run("v2", b"image", "image/jpeg", DetectContext(), object())

    assert process.terminated is True
    assert connection.closed is True
    assert runner._process is None
    assert runner._connection is None


def test_readiness_does_not_block_behind_a_busy_warmed_worker() -> None:
    runner = InferenceProcessRunner(timeout_seconds=1)
    runner._connection = SuccessfulConnection()  # type: ignore[assignment]
    runner._process = HungProcess()  # type: ignore[assignment]
    runner._warmed_kinds.add("v2")
    runner._lock.acquire()
    try:
        assert runner.is_warmed("v2") is True
        runner._warmed_kinds.clear()
        assert runner.is_warmed("v2") is False
    finally:
        runner._lock.release()


def test_spawned_inference_worker_returns_fake_v2_response() -> None:
    settings = get_settings()
    runner = InferenceProcessRunner(timeout_seconds=5)
    try:
        response = runner.run_v2(
            image_bytes=b"fake-provider-does-not-read-image",
            content_type="image/jpeg",
            context=DetectContext(
                captured_at=datetime.now(timezone.utc),
                gps={"latitude": 37.5, "longitude": 127.0, "accuracy_m": 5.0},
            ),
            settings=settings,
        )
    finally:
        runner.close()

    assert response.schema_version == "detect.v2"
    assert response.detections


def test_v2_warmup_uses_a_valid_png_and_skips_an_alive_warmed_worker(monkeypatch) -> None:
    runner = InferenceProcessRunner(timeout_seconds=5)
    calls: list[tuple[bytes, str, DetectContext, object]] = []
    settings = object()
    monkeypatch.setattr(runner, "is_warmed", lambda _kind: False)
    monkeypatch.setattr(
        runner,
        "run_v2",
        lambda *, image_bytes, content_type, context, settings: calls.append(
            (image_bytes, content_type, context, settings)
        ),
    )

    runner.warmup_v2(settings)

    assert calls[0][0].startswith(b"\x89PNG\r\n\x1a\n")
    assert calls[0][1] == "image/png"
    assert calls[0][2] == DetectContext()
    assert calls[0][3] is settings

    monkeypatch.setattr(runner, "is_warmed", lambda _kind: True)
    runner.warmup_v2(settings)
    assert len(calls) == 1


def test_inference_startup_deadline_must_cover_the_normal_execution_deadline(monkeypatch) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "false")
    monkeypatch.setenv("WALKSAFE_ALLOW_INSECURE_LOCAL_DEV", "true")
    monkeypatch.setenv("INFERENCE_TIMEOUT_SECONDS", "2")
    monkeypatch.setenv("INFERENCE_STARTUP_TIMEOUT_SECONDS", "1")

    with pytest.raises(ValueError, match="at least INFERENCE_TIMEOUT_SECONDS"):
        Settings()


def test_application_lifespan_closes_the_inference_runner(monkeypatch) -> None:
    class FakeRunner:
        closed = False

        def close(self) -> None:
            self.closed = True

    runner = FakeRunner()
    monkeypatch.setattr(main_app, "inference_runner", runner)
    monkeypatch.setattr(
        main_app,
        "validate_admin_credential_issuer_binding",
        lambda: None,
    )
    monkeypatch.setattr(main_app, "bind_privacy_hmac_key", lambda: None)
    monkeypatch.setattr(main_app, "bind_account_crypto_keys", lambda: None)
    monkeypatch.setattr(main_app, "reconcile_report_storage", lambda: None)

    async def exercise_lifespan() -> None:
        async with main_app.lifespan(main_app.app):
            assert runner.closed is False

    asyncio.run(exercise_lifespan())

    assert runner.closed is True
