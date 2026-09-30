"""Worker success payloads are trusted only after response contract validation."""

from __future__ import annotations

from collections import deque

import pytest

from backend.app.schemas import DetectContext, DetectV2Response
from backend.app.services import inference_process as ipc


class _Child:
    def __init__(self) -> None:
        self.alive = True
        self.terminated = False
        self.closed = False

    @property
    def exitcode(self) -> int | None:
        return None if self.alive else -15

    def is_alive(self) -> bool:
        assert not self.closed
        return self.alive

    def terminate(self) -> None:
        self.terminated = True
        self.alive = False

    def kill(self) -> None:
        self.alive = False

    def join(self, timeout: float) -> None:
        pass

    def close(self) -> None:
        assert not self.alive
        self.closed = True


class _Connection:
    def __init__(self, responses: deque[tuple[object, ...]]) -> None:
        self.responses = responses
        self.closed = False

    def exchange(self, request: tuple[object, ...], deadline: float) -> tuple[object, ...]:
        return (request[0], *self.responses.popleft())

    def close(self) -> None:
        self.closed = True


def _runner_with_responses(monkeypatch, responses: list[tuple[object, ...]]):
    runner = ipc.InferenceProcessRunner(timeout_seconds=1.0)
    queued = deque(responses)
    children: list[_Child] = []
    connections: list[_Connection] = []

    def ensure_worker(_deadline: float) -> None:
        if runner._process is not None:
            return
        child = _Child()
        connection = _Connection(queued)
        children.append(child)
        connections.append(connection)
        runner._process = child
        runner._process_started = True
        runner._connection = connection
        runner._usable = True

    monkeypatch.setattr(runner, "_ensure_worker", ensure_worker)
    return runner, children, connections


def _run_v2(runner: ipc.InferenceProcessRunner) -> DetectV2Response:
    return runner.run_v2(
        image_bytes=b"synthetic image",
        content_type="image/png",
        context=DetectContext(),
        settings=object(),
    )


def test_malformed_success_retires_worker_before_warmup_and_next_call_creates_child(monkeypatch) -> None:
    valid = {"schema_version": "detect.v2", "detections": []}
    runner, children, connections = _runner_with_responses(
        monkeypatch, [("ok", None), ("ok", valid), ("ok", valid)],
    )
    try:
        with pytest.raises(RuntimeError, match="detector worker returned an invalid response"):
            _run_v2(runner)
        assert len(children) == 1
        assert children[0].terminated and children[0].closed
        assert connections[0].closed
        assert not runner.is_warmed("v2")

        assert isinstance(_run_v2(runner), DetectV2Response)
        assert len(children) == 2
        assert children[1] is runner._process
        assert runner.is_warmed("v2")
        assert isinstance(_run_v2(runner), DetectV2Response)
        assert len(children) == 2
        assert not children[1].terminated
    finally:
        runner.close()


def test_input_and_provider_precondition_errors_reuse_trustworthy_child(monkeypatch) -> None:
    runner, children, connections = _runner_with_responses(
        monkeypatch,
        [
            ("error", "RuntimeError", "image_decode_failed", ipc._MALFORMED_IMAGE_INPUT_CODE),
            ("error", "RuntimeError", "provider unavailable", ipc._V2_PROVIDER_UNAVAILABLE_CODE),
            ("ok", {"schema_version": "detect.v2", "detections": []}),
        ],
    )
    try:
        with pytest.raises(RuntimeError, match="image_decode_failed"):
            _run_v2(runner)
        child = children[0]
        assert not child.terminated
        assert not runner.is_warmed("v2")

        with pytest.raises(RuntimeError, match="provider unavailable"):
            _run_v2(runner)
        assert runner._process is child
        assert not child.terminated
        assert not connections[0].closed

        assert isinstance(_run_v2(runner), DetectV2Response)
        assert runner._process is child
        assert len(children) == 1
        assert runner.is_warmed("v2")
    finally:
        runner.close()
