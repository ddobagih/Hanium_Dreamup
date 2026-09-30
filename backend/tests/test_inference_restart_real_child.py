"""S20-17 restart boundaries over the real direct child and socket IPC."""

from __future__ import annotations

import logging
import os
import time
from types import SimpleNamespace

import pytest

from backend.app.schemas import DetectContext, DetectV2Response
from backend.app.services import inference_process as ipc


pytestmark = pytest.mark.skipif(
    os.name != "posix" or not hasattr(os, "posix_spawn"),
    reason="direct inference child requires POSIX spawn",
)


class _InvalidWireContext(DetectContext):
    def model_dump(self, *args, **kwargs):
        return {"captured_at": "not-a-date"}


def test_typed_input_error_keeps_real_child_and_ipc_usable() -> None:
    runner = ipc.InferenceProcessRunner(timeout_seconds=2, startup_timeout_seconds=10)
    context = _InvalidWireContext()
    try:
        with pytest.raises(RuntimeError, match="^RuntimeError: invalid_detection_context$"):
            runner.run_legacy(
                image_bytes=b"unused", content_type="image/png",
                context=context, settings=None,
            )
        child = runner._process
        assert child is not None
        pid = child.pid
        assert pid > 0
        assert child.is_alive()
        assert runner._connection is not None

        with pytest.raises(RuntimeError, match="^RuntimeError: invalid_detection_context$"):
            runner.run_legacy(
                image_bytes=b"unused", content_type="image/png",
                context=context, settings=None,
            )
        assert runner._process is child
        assert child.pid == pid
        assert child.is_alive()
        assert len(runner._worker_creation_times) == 1
        response = runner.run_v2(
            image_bytes=ipc.WARMUP_PNG_BYTES, content_type="image/png",
            context=DetectContext(), settings=SimpleNamespace(detect_v2_mode="fake"),
        )
        assert isinstance(response, DetectV2Response)
        assert runner._process is child and child.pid == pid
        assert child.is_alive() and runner.is_warmed("v2")
        assert len(runner._worker_creation_times) == 1
    finally:
        runner.close()


def test_real_logging_handler_failure_keeps_child_budget_and_recovery(monkeypatch) -> None:
    """Exercise real children/logging; advance only parent time for window recovery."""
    class UnavailableLogSink(logging.Handler):
        def __init__(self):
            super().__init__()
            self.events = []

        def emit(self, record):
            self.events.append(record.getMessage())
            raise OSError("private test log sink unavailable")

    sink = UnavailableLogSink()
    local_logger = logging.Logger("walksafe-test-restart-admission", level=logging.WARNING)
    local_logger.addHandler(sink)
    monkeypatch.setattr(ipc, "logger", local_logger)
    # Real process/IPC calls remain in use; existing exact-5s unit cases cover interval policy.
    monkeypatch.setattr(ipc, "_MIN_WORKER_CREATION_INTERVAL_SECONDS", 0.0)
    offset = [0.0]
    monkeypatch.setattr(ipc, "time", SimpleNamespace(
        monotonic=lambda: time.monotonic() + offset[0], sleep=time.sleep,
    ))
    children = []
    actual_spawn = ipc._spawn_posix_worker

    def record_spawn(connection):
        child = actual_spawn(connection)
        children.append(child)
        return child

    monkeypatch.setattr(ipc, "_spawn_posix_worker", record_spawn)
    runner = ipc.InferenceProcessRunner(timeout_seconds=2, startup_timeout_seconds=10)
    retire_calls = []
    actual_retire = runner._terminate_worker

    def record_retire():
        retire_calls.append(True)
        return actual_retire()

    monkeypatch.setattr(runner, "_terminate_worker", record_retire)
    try:
        for _ in range(ipc._MAX_WORKER_CREATIONS_PER_WINDOW):
            with pytest.raises(RuntimeError, match="^RuntimeError: unsupported inference operation$"):
                runner._run("unsupported", b"unused", "image/png", DetectContext(), None)
            assert children[-1].exitcode is not None
        assert len(children) == 3
        retired_before_limit = len(retire_calls)
        for _ in range(2):
            with pytest.raises(ipc.InferenceRestartAdmissionLimited):
                runner.run_v2(image_bytes=ipc.WARMUP_PNG_BYTES, content_type="image/png",
                              context=DetectContext(), settings=SimpleNamespace(detect_v2_mode="fake"))
        assert len(retire_calls) == retired_before_limit
        assert len(children) == len(runner._worker_creation_times) == 3
        assert runner._process is None and runner._connection is None
        assert sink.events == ["inference_worker_restart_admission_limited"]

        offset[0] = ipc._WORKER_CREATION_WINDOW_SECONDS
        response = runner.run_v2(
            image_bytes=ipc.WARMUP_PNG_BYTES, content_type="image/png",
            context=DetectContext(), settings=SimpleNamespace(detect_v2_mode="fake"),
        )
        assert isinstance(response, DetectV2Response)
        assert len(children) == 4
        assert runner._process is children[-1] and children[-1].is_alive()
        assert len(runner._worker_creation_times) == 1
        assert not runner._restart_admission_limited
    finally:
        runner.close()
        local_logger.removeHandler(sink)
        sink.close()
    assert all(child.exitcode is not None for child in children)


def test_fatal_real_child_error_reaps_worker_and_logs_creation_cap(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture,
) -> None:
    # Remove only the interval wait; retain the production three-per-minute cap.
    monkeypatch.setattr(ipc, "_MIN_WORKER_CREATION_INTERVAL_SECONDS", 0.0)
    spawn_worker = ipc._spawn_posix_worker
    children = []

    def record_spawn(connection):
        child = spawn_worker(connection)
        children.append(child)
        return child

    monkeypatch.setattr(ipc, "_spawn_posix_worker", record_spawn)
    runner = ipc.InferenceProcessRunner(timeout_seconds=2, startup_timeout_seconds=10)
    try:
        for _ in range(ipc._MAX_WORKER_CREATIONS_PER_WINDOW):
            with pytest.raises(RuntimeError, match="^RuntimeError: unsupported inference operation$"):
                runner._run("unsupported", b"unused", "image/png", DetectContext(), None)
            assert runner._process is None
            assert runner._connection is None
            assert children[-1].exitcode is not None

        with caplog.at_level(logging.WARNING, logger=ipc.__name__):
            with pytest.raises(ipc.InferenceRestartAdmissionLimited):
                runner._run("unsupported", b"unused", "image/png", DetectContext(), None)

        assert len(children) == ipc._MAX_WORKER_CREATIONS_PER_WINDOW
        assert len(runner._worker_creation_times) == ipc._MAX_WORKER_CREATIONS_PER_WINDOW
        assert [
            record.getMessage() for record in caplog.records
            if record.name == ipc.__name__
            and record.getMessage() == "inference_worker_restart_admission_limited"
        ] == ["inference_worker_restart_admission_limited"]
    finally:
        runner.close()
