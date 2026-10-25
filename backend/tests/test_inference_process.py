from __future__ import annotations

import asyncio
from contextlib import contextmanager
from datetime import datetime, timezone
import inspect
import json
import multiprocessing
import pickle
import socket
import sys
import time
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from backend.app.api import detect as detect_api
from backend.app import detector
from backend.app.services import detect_v2
from backend.app.services import inference_process as ipc

from backend.app.config import Settings, get_settings
from backend.app import main as main_app
from backend.app.schemas import DetectContext, DetectResponse, DetectV2Response
from backend.app.services.inference_process import (
    InferenceDeadlineExceeded,
    InferenceProcessRunner,
    InferenceQueueDeadlineExceeded,
)


class HungConnection:
    closed = False

    def exchange(self, _payload: object, _deadline: float) -> object:
        raise InferenceDeadlineExceeded("detector execution deadline exceeded")

    def close(self) -> None:
        self.closed = True


class HungProcess:
    def __init__(self) -> None:
        self.terminated = False
        self.killed = False
        self.alive = True
        self.closed = False
        self.calls: list[str] = []

    def _observe(self, name: str) -> None:
        assert not self.closed, "closed Process must not be queried"
        self.calls.append(name)

    @property
    def pid(self) -> int:
        self._observe("pid")
        return 123

    @property
    def exitcode(self) -> int | None:
        self._observe("exitcode")
        return None if self.alive else -15

    def is_alive(self) -> bool:
        self._observe("is_alive")
        return self.alive

    def terminate(self) -> None:
        self._observe("terminate")
        self.terminated = True
        self.alive = False

    def kill(self) -> None:
        self._observe("kill")
        self.killed = True
        self.alive = False

    def join(self, timeout: float) -> None:
        self._observe("join")
        assert timeout <= 0.5

    def close(self) -> None:
        self._observe("close")
        assert not self.alive
        self.closed = True


def _valid_worker_payload(kind: object) -> dict[str, object]:
    if kind == "legacy":
        return {"model_status": "ready", "model_version": "fixture", "detections": []}
    return {"schema_version": "detect.v2", "detections": []}
def _is_valid_worker_result(result: object, kind: str) -> bool:
    expected = DetectResponse if kind == "legacy" else DetectV2Response
    return isinstance(result, expected) and result.model_dump(mode="json", exclude_unset=True) == _valid_worker_payload(kind)
class SuccessfulConnection:
    closed = False

    def __init__(self) -> None:
        self.last_request: tuple[object, ...] | None = None
        self.poll_timeouts: list[float] = []

    def exchange(self, payload: object, deadline: float) -> tuple[object, ...]:
        assert isinstance(payload, tuple)
        self.last_request = payload
        self.poll_timeouts.append(deadline - time.monotonic())
        return self.response()

    def response(self) -> tuple[object, ...]:
        assert self.last_request is not None
        # The parent validates the child payload against the public response model.
        return (self.last_request[0], "ok", _valid_worker_payload(self.last_request[1]))

    def close(self) -> None:
        self.closed = True


class ErrorConnection(SuccessfulConnection):
    def response(self) -> tuple[object, ...]:
        assert self.last_request is not None
        return (self.last_request[0], "error", "RuntimeError", "/srv/private/model.pt")


class ProviderPreconditionConnection(SuccessfulConnection):
    def response(self) -> tuple[object, ...]:
        assert self.last_request is not None
        return (
            self.last_request[0], "error", "RuntimeError",
            detect_v2.DETECT_V2_MODEL_NOT_CONFIGURED_REASON,
            ipc._V2_PROVIDER_UNAVAILABLE_CODE,
        )


class ForgedImageErrorConnection(SuccessfulConnection):
    def response(self) -> tuple[object, ...]:
        assert self.last_request is not None
        return (
            self.last_request[0], "error", "RuntimeError",
            "/srv/private/model.pt", ipc._MALFORMED_IMAGE_INPUT_CODE,
        )


def _install_worker(runner, connection, process):
    runner._connection = connection
    runner._process = process
    runner._process_started = True
    runner._usable = True


def test_hung_inference_process_is_terminated_and_runner_capacity_is_released() -> None:
    runner = InferenceProcessRunner(timeout_seconds=0.01, startup_timeout_seconds=0.01)
    connection = HungConnection()
    process = HungProcess()
    _install_worker(runner, connection, process)

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
    _install_worker(runner, connection, process)

    assert _is_valid_worker_result(runner._run("legacy", b"image", "image/jpeg", DetectContext(), object()), "legacy")
    assert runner._warmed_kinds == {"legacy"}
    assert _is_valid_worker_result(runner._run("legacy", b"image", "image/jpeg", DetectContext(), object()), "legacy")

    assert connection.poll_timeouts[0] > 0.9
    assert 0 < connection.poll_timeouts[1] <= 0.02


def test_each_inference_contract_gets_its_own_cold_start_deadline() -> None:
    runner = InferenceProcessRunner(timeout_seconds=0.02, startup_timeout_seconds=1.0)
    connection = SuccessfulConnection()
    process = HungProcess()
    _install_worker(runner, connection, process)

    assert _is_valid_worker_result(runner._run("legacy", b"image", "image/jpeg", DetectContext(), object()), "legacy")
    assert _is_valid_worker_result(runner._run("v2", b"image", "image/jpeg", DetectContext(), object()), "v2")
    assert runner._warmed_kinds == {"legacy", "v2"}
    assert connection.poll_timeouts[0] > 0.9
    assert connection.poll_timeouts[1] > 0.9


def test_worker_runtime_error_restarts_the_isolated_process() -> None:
    runner = InferenceProcessRunner(timeout_seconds=1)
    connection = ErrorConnection()
    process = HungProcess()
    _install_worker(runner, connection, process)

    with pytest.raises(RuntimeError, match="RuntimeError"):
        runner._run("v2", b"image", "image/jpeg", DetectContext(), object())

    assert process.terminated is True
    assert connection.closed is True
    assert runner._process is None
    assert runner._connection is None


def test_provider_precondition_preserves_existing_worker() -> None:
    runner = InferenceProcessRunner(timeout_seconds=1)
    connection = ProviderPreconditionConnection()
    process = HungProcess()
    _install_worker(runner, connection, process)

    with pytest.raises(RuntimeError, match="detect_v2_model_not_configured"):
        runner._run("v2", b"image", "image/jpeg", DetectContext(), object())

    assert runner._process is process
    assert runner._connection is connection
    assert not process.terminated
    assert not runner.is_warmed("v2")
    runner.close()


def test_forged_image_error_with_private_message_retires_worker() -> None:
    runner = InferenceProcessRunner(timeout_seconds=1)
    connection = ForgedImageErrorConnection()
    process = HungProcess()
    _install_worker(runner, connection, process)

    with pytest.raises(RuntimeError, match="invalid response"):
        runner._run("legacy", b"image", "image/jpeg", DetectContext(), object())

    assert process.terminated
    assert connection.closed
    assert runner._process is None


def test_readiness_does_not_block_behind_a_busy_warmed_worker() -> None:
    runner = InferenceProcessRunner(timeout_seconds=1)
    _install_worker(runner, SuccessfulConnection(), HungProcess())
    runner._warmed_kinds.add("v2")
    runner._lock.acquire()
    try:
        assert runner.is_warmed("v2") is True
        runner._warmed_kinds.clear()
        assert runner.is_warmed("v2") is False
    finally:
        runner._lock.release()


def test_spawned_inference_worker_returns_fake_v2_response(monkeypatch) -> None:
    settings = get_settings()
    with _owned_runner(monkeypatch, timeout_seconds=5, startup_timeout_seconds=30) as (runner, _records):
        response = runner.run_v2(
            image_bytes=b"fake-provider-does-not-read-image",
            content_type="image/jpeg",
            context=DetectContext(
                captured_at=datetime.now(timezone.utc),
                gps={"latitude": 37.5, "longitude": 127.0, "accuracy_m": 5.0},
            ),
            settings=settings,
        )

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
    owned_app = main_app.create_app(main_app.settings)
    owner = owned_app.state.runtime
    owner.initialize_resources()
    monkeypatch.setattr(owner, "initialize_resources", lambda: None)
    from backend.app import application_runtime as runtime_module
    class FakeRunner:
        closed = False

        def close(self) -> None:
            self.closed = True

    runner = FakeRunner()
    monkeypatch.setattr(owner, "inference_runner", runner)
    monkeypatch.setattr(
        owner,
        "validate_admin_credential_issuer_binding",
        lambda: None,
    )
    monkeypatch.setattr(owner, "bind_privacy_hmac_key", lambda: None)
    monkeypatch.setattr(owner, "bind_account_crypto_keys", lambda: None)
    monkeypatch.setattr(owner, "reconcile_report_storage", lambda: None)
    monkeypatch.setattr(owner, "reconcile_raw_collection_storage", lambda: None)
    monkeypatch.setattr(owner, "capacity_monitor", None)

    async def exercise_lifespan() -> None:
        async with owner.lifespan(owned_app):
            assert runner.closed is False

    asyncio.run(exercise_lifespan())

    assert runner.closed is True



# These records contain only test-owned local resources, not application data.
IPC_RESOURCES: list[dict[str, object]] = []
_OWNED_SOCKETS: list[tuple[socket.socket, dict[str, object]]] = []
_REAL_SOCKETPAIR = socket.socketpair


def _new_owned_pair():
    pair = _REAL_SOCKETPAIR()
    for channel in pair:
        record = {"kind": "socket", "fd": channel.fileno(), "closed": False}
        IPC_RESOURCES.append(record)
        _OWNED_SOCKETS.append((channel, record))
    return pair


def _close_owned_sockets(sockets):
    for channel, record in sockets:
        record["closed_by_runner"] = channel.fileno() == -1
        channel.close()
        record["closed"] = channel.fileno() == -1


@contextmanager
def _local_pair():
    before = len(_OWNED_SOCKETS)
    pair = _new_owned_pair()
    try:
        yield pair
    finally:
        _close_owned_sockets(_OWNED_SOCKETS[before:])


class _ObservedProcess:
    def __init__(self, process):
        self.inner = process
        self.closed = False
        self.record = {
            "kind": "process", "pid": None, "exitcode": None,
            "closed": False, "events": [], "cleanup_owner": None,
        }
        IPC_RESOURCES.append(self.record)

    def _event(self, name):
        assert not self.closed, "closed real Process queried"
        self.record["events"].append(name)

    def start(self):
        self._event("start")
        try:
            self.inner.start()
        finally:
            self.record["pid"] = self.inner.pid

    @property
    def pid(self):
        self._event("pid")
        return self.inner.pid

    @property
    def exitcode(self):
        self._event("exitcode")
        return self.inner.exitcode

    def is_alive(self):
        self._event("is_alive")
        return self.inner.is_alive()

    def terminate(self):
        self._event("terminate")
        self.inner.terminate()

    def kill(self):
        self._event("kill")
        self.inner.kill()

    def join(self, timeout):
        self._event("join")
        assert timeout <= 0.5
        self.inner.join(timeout)

    def close(self):
        self._event("close")
        if self.record["pid"] is not None:
            self.record["alive_at_close"] = self.inner.is_alive()
            self.record["exitcode"] = self.inner.exitcode
            assert self.record["alive_at_close"] is False
            assert self.record["exitcode"] is not None
        self.inner.close()
        self.closed = True
        self.record["closed"] = True
        if self.record["cleanup_owner"] is None:
            self.record["cleanup_owner"] = "runner"


class _RecordingContext:
    def __init__(self, mode, start_method="spawn"):
        self.context = multiprocessing.get_context(start_method)
        self.mode = mode
        self.processes = []

    def Process(self, *, target, args, daemon):
        assert target is ipc._inference_worker
        assert len(args) == 1 and daemon is True
        process = _ObservedProcess(self.context.Process(
            target=_guarded_peer_entry, args=(args[0], self.mode), daemon=daemon,
        ))
        self.processes.append(process)
        return process


def _peer_read_exact(channel, size):
    result = bytearray()
    while len(result) < size:
        part = channel.recv(size - len(result))
        if not part:
            raise EOFError("test peer closed")
        result.extend(part)
    return bytes(result)


def _guarded_peer_entry(channel, mode):
    from sqlalchemy.engine import Engine

    def deny_connection(*_args, **_kwargs):
        raise AssertionError("database connection forbidden in IPC test child")

    Engine.connect = deny_connection
    Engine.raw_connection = deny_connection
    if mode is None:
        ipc._inference_worker(channel)
    else:
        _fault_peer(channel, mode)


def _fault_peer(channel, mode):
    # This safety exit is not a product deadline or a recovery success.
    channel.settimeout(5)
    try:
        channel.sendall(b"R")
        if mode == "blocked_send":
            time.sleep(5)
            return
        size = int.from_bytes(_peer_read_exact(channel, 8), "big")
        request = pickle.loads(_peer_read_exact(channel, size))
        response = pickle.dumps((request[0], "ok", "response"), protocol=pickle.DEFAULT_PROTOCOL)
        header = len(response).to_bytes(8, "big")
        if mode == "partial_header":
            channel.sendall(header[:3])
        elif mode == "partial_body":
            channel.sendall(header + response[:3])
        else:
            raise AssertionError("unknown test peer mode")
        time.sleep(5)
    finally:
        channel.close()


@contextmanager
def _owned_runner(monkeypatch, mode=None, *, timeout_seconds=0.02, startup_timeout_seconds=0.02,
                  start_method="spawn"):
    before = len(_OWNED_SOCKETS)
    record_start = len(IPC_RESOURCES)
    context = _RecordingContext(mode, start_method=start_method)
    runner = InferenceProcessRunner(
        timeout_seconds=timeout_seconds, startup_timeout_seconds=startup_timeout_seconds,
    )
    runner._context = context
    monkeypatch.setattr(ipc.socket, "socketpair", _new_owned_pair)
    errors = []
    try:
        yield runner, IPC_RESOURCES
    finally:
        try:
            runner.close()
        except BaseException as exc:
            errors.append(type(exc).__name__)
        # Emergency cleanup touches only Processes allocated by this fixture.
        for process in context.processes:
            if process.closed:
                continue
            process.record["cleanup_owner"] = "fixture"
            try:
                if process.record["pid"] is not None:
                    if process.is_alive():
                        process.terminate()
                    process.join(0.5)
                    if process.is_alive():
                        process.kill()
                        process.join(0.5)
                    assert not process.is_alive()
                    assert process.exitcode is not None
                process.close()
            except BaseException as exc:
                errors.append(type(exc).__name__)
        for channel, record in _OWNED_SOCKETS[before:]:
            try:
                _close_owned_sockets([(channel, record)])
            except BaseException as exc:
                errors.append(type(exc).__name__)
        print("IPC_RESOURCES " + json.dumps(IPC_RESOURCES[record_start:], sort_keys=True))
        assert not errors, "owned IPC cleanup failed: " + ",".join(errors)


def _prepare_fault_peer(runner):
    runner._ensure_worker()
    channel = runner._connection._socket
    # A bounded setup handshake distinguishes partial-frame tests from a child
    # which has not even started. It is outside the product inference budget.
    channel.settimeout(5)
    try:
        assert channel.recv(1) == b"R"
    finally:
        channel.setblocking(False)


def test_malformed_legacy_image_then_valid_request_reuses_same_worker(monkeypatch):
    class Adapter:
        def predict_image(self, image, settings, context):
            assert image.mode == "RGB"
            return DetectResponse(model_status="ready", model_version="fixture", detections=[])

    monkeypatch.setattr(detector, "_adapter_load_result", lambda _settings: detector.AdapterLoadResult(adapter=Adapter()))
    with _owned_runner(monkeypatch, timeout_seconds=5, startup_timeout_seconds=5,
                       start_method="fork") as (runner, _records):
        with pytest.raises(RuntimeError, match="^RuntimeError: image_decode_failed$") as caught:
            runner.run_legacy(
                image_bytes=b"/srv/private/model.pt is not an image",
                content_type="image/png", context=DetectContext(), settings=get_settings(),
            )
        assert "/srv/private/model.pt" not in str(caught.value)
        pid = runner._process.pid
        assert not runner.is_warmed("legacy")
        response = runner.run_legacy(
            image_bytes=detect_v2.WARMUP_PNG_BYTES,
            content_type="image/png", context=DetectContext(), settings=get_settings(),
        )
        assert response.model_status == "ready"
        assert runner._process.pid == pid
        assert len(runner._worker_creation_times) == 1


def test_malformed_real_v2_image_then_valid_request_reuses_same_worker(monkeypatch):
    runtime = detect_v2.LazyYoloDetectV2Runtime(unified_model_path=detector.Path("fixture.pt"))
    monkeypatch.setattr(runtime, "_detect_unified", lambda _image: [])
    provider = detect_v2.YoloDetectV2Provider(runtime=runtime)
    monkeypatch.setattr(
        detect_v2, "get_detect_v2_provider_state",
        lambda _settings: detect_v2.DetectV2ProviderState(provider=provider, status="ready"),
    )
    with _owned_runner(monkeypatch, timeout_seconds=5, startup_timeout_seconds=5,
                       start_method="fork") as (runner, _records):
        with pytest.raises(RuntimeError, match="^RuntimeError: image_decode_failed$"):
            runner.run_v2(
                image_bytes=b"not an image", content_type="image/png",
                context=DetectContext(), settings=get_settings(),
            )
        pid = runner._process.pid
        assert not runner.is_warmed("v2")
        response = runner.run_v2(
            image_bytes=detect_v2.WARMUP_PNG_BYTES, content_type="image/png",
            context=DetectContext(), settings=get_settings(),
        )
        assert response.schema_version == "detect.v2"
        assert runner._process.pid == pid
        assert len(runner._worker_creation_times) == 1


class _ScriptedSocket:
    def __init__(self, incoming=b"", sends=()):
        self.incoming = bytearray(incoming)
        self.sends = list(sends)
        self.sent = bytearray()
        self.closed = False
        self.close_failure = False
        self.blocking = None

    def setblocking(self, value):
        self.blocking = value

    def send(self, data):
        step = self.sends.pop(0) if self.sends else len(data)
        if isinstance(step, BaseException):
            raise step
        count = min(step, len(data))
        self.sent.extend(data[:count])
        return count

    def recv(self, size):
        if not self.incoming:
            raise BlockingIOError()
        part = bytes(self.incoming[:size])
        del self.incoming[:size]
        return part

    def close(self):
        if self.close_failure:
            raise OSError("test endpoint cleanup failure")
        self.closed = True


class _ScriptedSelector:
    def __init__(self, clock=None, step=0.5):
        self.clock = clock
        self.step = step
        self.timeouts = []
        self.closed = False
        self.unregister_failure = False
        self.close_failure = False
        self.unregister_calls = 0

    def register(self, _channel, _event):
        return None

    def modify(self, _channel, _event):
        return None

    def select(self, timeout):
        self.timeouts.append(timeout)
        if self.clock is not None:
            self.clock[0] += self.step
        return [object()]

    def unregister(self, _channel):
        self.unregister_calls += 1
        if self.unregister_failure:
            raise OSError("test unregister failure")

    def close(self):
        if self.close_failure:
            raise OSError("test selector cleanup failure")
        self.closed = True


def _frame(value):
    payload = pickle.dumps(value, protocol=pickle.DEFAULT_PROTOCOL)
    return len(payload).to_bytes(8, "big") + payload


def _public_settings_names(value):
    found = set()
    unsupported = set()
    instance_dict = getattr(value, "__dict__", None)
    if isinstance(instance_dict, dict):
        found.update(name for name in instance_dict if not name.startswith("_"))
    for cls in type(value).__mro__:
        if cls is object:
            continue
        for name, member in vars(cls).items():
            if name.startswith("_") or isinstance(member, (staticmethod, classmethod)) or inspect.isroutine(member):
                continue
            if isinstance(member, property):
                unsupported.add(name)
            elif inspect.isdatadescriptor(member):
                if inspect.ismemberdescriptor(member) or inspect.isgetsetdescriptor(member):
                    found.add(name)
                else:
                    unsupported.add(name)
            elif not callable(member):
                found.add(name)
    return found, unsupported


def test_ipc_frame_round_trip_preserves_objects():
    request = ("job", "v2", b"image", "image/png", {"gps": None}, get_settings())
    response = ("job", "ok", {"bytes": b"result", "values": [None, 1, 2.0]})
    with _local_pair() as (parent, peer):
        parent.setblocking(False)
        peer.settimeout(5)
        peer.sendall(_frame(response))
        channel = ipc._FramedSocket(parent)
        try:
            assert channel.exchange(request, time.monotonic() + 1) == response
            size = int.from_bytes(_peer_read_exact(peer, 8), "big")
            received = pickle.loads(_peer_read_exact(peer, size))
            prefix_matches = received[:5] == request[:5]
            assert prefix_matches
            original_settings, restored_settings = request[5], received[5]
            same_settings_type = type(restored_settings) is type(original_settings) is Settings
            distinct_settings_objects = restored_settings is not original_settings
            assert same_settings_type
            assert distinct_settings_objects
            original_names, original_unsupported = _public_settings_names(original_settings)
            restored_names, restored_unsupported = _public_settings_names(restored_settings)
            has_public_fields = bool(original_names)
            missing_names = sorted(original_names - restored_names)
            unexpected_names = sorted(restored_names - original_names)
            assert has_public_fields
            assert not missing_names
            assert not unexpected_names
            assert not original_unsupported
            assert not restored_unsupported
            consumers = {
                "inference_timeout_seconds",
                "inference_startup_timeout_seconds",
                "inference_process_isolation_enabled",
            }
            missing_consumers = sorted(consumers - original_names)
            assert not missing_consumers
            comparison_errors = []
            type_mismatches = []
            value_mismatches = []
            for name in sorted(original_names):
                try:
                    before = getattr(original_settings, name)
                    after = getattr(restored_settings, name)
                    same_type = type(before) is type(after)
                    equality = before == after
                    values_match = isinstance(equality, bool) and equality
                except Exception:
                    comparison_errors.append(name)
                    continue
                if not same_type:
                    type_mismatches.append(name)
                if not values_match:
                    value_mismatches.append(name)
            consumer_mismatches = sorted(consumers.intersection(
                comparison_errors + type_mismatches + value_mismatches
            ))
            assert not comparison_errors
            assert not type_mismatches
            assert not value_mismatches
            assert not consumer_mismatches
            print("SETTINGS_PUBLIC_CONTRACT " + json.dumps({
                "field_count": len(original_names),
                "same_type": True, "distinct_object": True,
                "public_names_types_values_equal": True,
                "known_consumer_count": len(consumers),
            }, sort_keys=True))
        finally:
            channel.close()


def test_parent_send_deadline_covers_partial_writes(monkeypatch):
    clock = [0.0]
    selector = _ScriptedSelector(clock)
    channel_socket = _ScriptedSocket(sends=[2, BlockingIOError(), 1, BlockingIOError()])
    channel = ipc._FramedSocket(channel_socket)
    monkeypatch.setattr(ipc.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(ipc.selectors, "DefaultSelector", lambda: selector)
    try:
        with pytest.raises(InferenceDeadlineExceeded):
            channel.exchange(("request",), 1.0)
        assert selector.timeouts == [1.0, 0.5]
        assert len(channel_socket.sent) == 3
        assert selector.closed
    finally:
        channel.close()


@pytest.mark.parametrize("part", ["header", "body"])
def test_parent_receive_deadline_covers_complete_frame(monkeypatch, part):
    response = _frame(("job", "ok", "response"))
    prefix = response[:3] if part == "header" else response[:11]
    clock = [0.0]
    selector = _ScriptedSelector(clock)
    channel = ipc._FramedSocket(_ScriptedSocket(incoming=prefix))
    loads_calls = []
    monkeypatch.setattr(ipc.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(ipc.selectors, "DefaultSelector", lambda: selector)
    monkeypatch.setattr(ipc.pickle, "loads", lambda data: loads_calls.append(data))
    try:
        with pytest.raises(InferenceDeadlineExceeded):
            channel.exchange(("request",), 1.0)
        assert selector.timeouts == [1.0, 0.5]
        assert loads_calls == []
    finally:
        channel.close()


@pytest.mark.parametrize("phase", ["serialize", "deserialize", "accept"])
def test_late_result_is_not_accepted(monkeypatch, phase):
    clock = [0.0]
    response_frame = _frame(("job", "ok", "response"))
    original_dumps, original_loads = pickle.dumps, pickle.loads
    monkeypatch.setattr(ipc.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(ipc.uuid, "uuid4", lambda: SimpleNamespace(hex="job"))
    runner = InferenceProcessRunner(timeout_seconds=1, startup_timeout_seconds=1)
    process = HungProcess()
    if phase == "accept":
        connection = SuccessfulConnection()
        exchange = connection.exchange

        def late_exchange(request, deadline):
            result = exchange(request, deadline)
            clock[0] = 2.0
            return result

        monkeypatch.setattr(connection, "exchange", late_exchange)
    else:
        connection = ipc._FramedSocket(_ScriptedSocket(incoming=response_frame))
        monkeypatch.setattr(ipc.selectors, "DefaultSelector", _ScriptedSelector)
        if phase == "serialize":
            def late_dumps(*args, **kwargs):
                result = original_dumps(*args, **kwargs)
                clock[0] = 2.0
                return result

            monkeypatch.setattr(ipc.pickle, "dumps", late_dumps)
        else:
            def late_loads(*args, **kwargs):
                result = original_loads(*args, **kwargs)
                clock[0] = 2.0
                return result

            monkeypatch.setattr(ipc.pickle, "loads", late_loads)
    _install_worker(runner, connection, process)
    with pytest.raises(InferenceDeadlineExceeded):
        runner._run("v2", b"image", "image/png", DetectContext(), object())
    assert not runner._warmed_kinds
    assert process.closed
    assert runner._connection is None


@pytest.mark.parametrize("kind", ["deadline", "transport_error"])
def test_selector_cleanup_preserves_primary_error(monkeypatch, kind):
    failure = InferenceDeadlineExceeded("fixture deadline") if kind == "deadline" else OSError("fixture transport")
    selector = _ScriptedSelector()
    selector.unregister_failure = True
    channel = ipc._FramedSocket(_ScriptedSocket(sends=[failure]))
    monkeypatch.setattr(ipc.selectors, "DefaultSelector", lambda: selector)
    try:
        with pytest.raises(type(failure)) as caught:
            channel.exchange(("request",), time.monotonic() + 1)
        assert caught.value is failure
        assert "detector transport cleanup failed" in failure.__notes__
        assert selector.closed
        assert channel._selector is None
    finally:
        channel.close()


def test_fatal_selector_cleanup_is_not_suppressed_by_a_fatal_primary_error(monkeypatch):
    selector = _ScriptedSelector()
    channel = ipc._FramedSocket(_ScriptedSocket())
    monkeypatch.setattr(ipc.selectors, "DefaultSelector", lambda: selector)

    def fail_send(*_args, **_kwargs):
        raise KeyboardInterrupt("primary")

    def fail_cleanup():
        raise SystemExit("fatal cleanup")

    monkeypatch.setattr(channel, "_send_bytes", fail_send)
    monkeypatch.setattr(channel, "_close_selector", fail_cleanup)
    with pytest.raises(SystemExit, match="fatal cleanup"):
        channel.exchange(("request",), time.monotonic() + 1)


def test_selector_cleanup_failure_retains_selector(monkeypatch):
    selector = _ScriptedSelector()
    selector.close_failure = True
    channel_socket = _ScriptedSocket(incoming=_frame(("job", "ok", "response")))
    channel = ipc._FramedSocket(channel_socket)
    monkeypatch.setattr(ipc.selectors, "DefaultSelector", lambda: selector)
    with pytest.raises(RuntimeError, match="transport cleanup failed"):
        channel.exchange(("request",), time.monotonic() + 1)
    assert channel._selector is selector
    with pytest.raises(RuntimeError, match="transport cleanup failed"):
        channel.close()
    assert not channel_socket.closed
    selector.close_failure = False
    channel.close()
    assert selector.closed
    assert channel_socket.closed
    assert selector.unregister_calls == 1


class _StubbornProcess(HungProcess):
    def terminate(self):
        self._observe("terminate")
        self.terminated = True

    def kill(self):
        self._observe("kill")
        self.killed = True


def test_cleanup_retains_alive_worker_generation(monkeypatch):
    runner = InferenceProcessRunner(timeout_seconds=0.01, startup_timeout_seconds=0.01)
    process = _StubbornProcess()
    _install_worker(runner, HungConnection(), process)
    creates = []
    monkeypatch.setattr(ipc.socket, "socketpair", lambda: creates.append("new"))
    try:
        with pytest.raises(RuntimeError, match="worker cleanup failed") as caught:
            runner._run("legacy", b"image", "image/jpeg", DetectContext(), object())
        assert isinstance(caught.value.__cause__, InferenceDeadlineExceeded)
        assert runner._process is process
        assert not runner._usable
        assert not runner.is_warmed("legacy")
        with pytest.raises(RuntimeError, match="worker cleanup failed"):
            runner._run("legacy", b"image", "image/jpeg", DetectContext(), object())
        assert creates == []
        assert process.terminated and process.killed
        assert runner._lock.acquire(blocking=False)
        runner._lock.release()
    finally:
        process.alive = False
        runner.close()


def test_unknown_exit_status_blocks_replacement(monkeypatch):
    class UnknownExit(HungProcess):
        exit_known = False

        @property
        def exitcode(self):
            self._observe("exitcode")
            return -15 if self.exit_known else None

    process = UnknownExit()
    process.alive = False
    runner = InferenceProcessRunner(timeout_seconds=1)
    _install_worker(runner, SuccessfulConnection(), process)
    creates = []
    monkeypatch.setattr(ipc.socket, "socketpair", lambda: creates.append("new"))
    try:
        with pytest.raises(RuntimeError, match="worker cleanup failed"):
            runner._run("v2", b"image", "image/png", DetectContext(), object())
        assert runner._process is process
        assert not process.closed
        assert creates == []
    finally:
        process.exit_known = True
        runner.close()


@pytest.mark.parametrize("endpoint", ["parent_endpoint", "child_endpoint_copy"])
def test_closed_process_is_not_requeried_when_endpoint_cleanup_fails(endpoint):
    runner = InferenceProcessRunner(timeout_seconds=1)
    process = HungProcess()
    broken = _ScriptedSocket()
    broken.close_failure = True
    connection = broken if endpoint == "parent_endpoint" else SuccessfulConnection()
    _install_worker(runner, connection, process)
    if endpoint == "child_endpoint_copy":
        runner._child_connection = broken
    with pytest.raises(RuntimeError, match="worker cleanup failed"):
        runner.close()
    assert process.closed
    assert runner._process is None
    calls = list(process.calls)
    assert not runner.is_warmed("v2")
    broken.close_failure = False
    runner.close()
    assert process.calls == calls
    assert runner._connection is None
    assert runner._child_connection is None


@pytest.mark.parametrize("partial_start", [False, True])
def test_start_failure_preserves_process_ownership_and_unstarted_contract(monkeypatch, partial_start):
    failure = RuntimeError("start fixture failure")

    class StartFailure(HungProcess):
        def __init__(self):
            super().__init__()
            self.alive = partial_start

        def start(self):
            raise failure

        @property
        def pid(self):
            self._observe("pid")
            return 123 if partial_start else None

        @property
        def exitcode(self):
            assert partial_start, "unstarted Process exitcode queried"
            return super().exitcode

        def join(self, timeout):
            assert partial_start, "unstarted Process joined"
            super().join(timeout)

    process = StartFailure()
    parent, child = _ScriptedSocket(), _ScriptedSocket()
    runner = InferenceProcessRunner(timeout_seconds=1)
    runner._context = SimpleNamespace(Process=lambda **_kwargs: process)
    monkeypatch.setattr(ipc.socket, "socketpair", lambda: (parent, child))
    with pytest.raises(RuntimeError) as caught:
        runner._run("legacy", b"image", "image/jpeg", DetectContext(), object())
    assert caught.value is failure
    assert process.closed and parent.closed and child.closed
    assert runner._process is None
    assert runner._child_connection is None
    if partial_start:
        assert process.terminated
        assert "join" in process.calls
    else:
        assert process.calls == ["pid", "close"]


def test_selector_and_endpoint_failures_do_not_skip_other_cleanup():
    selector = _ScriptedSelector()
    selector.close_failure = True
    parent = _ScriptedSocket()
    connection = ipc._FramedSocket(parent)
    connection._selector = selector
    connection._registered = True
    child = _ScriptedSocket()
    child.close_failure = True
    process = HungProcess()
    runner = InferenceProcessRunner(timeout_seconds=1)
    _install_worker(runner, connection, process)
    runner._child_connection = child
    with pytest.raises(RuntimeError, match="worker cleanup failed"):
        runner.close()
    assert process.closed
    assert runner._process is None
    assert runner._connection is connection
    assert runner._child_connection is child
    assert not parent.closed
    assert not child.closed
    selector.close_failure = False
    child.close_failure = False
    calls = list(process.calls)
    runner.close()
    assert parent.closed and child.closed and selector.closed
    assert process.calls == calls


def test_close_uses_no_sentinel_and_readiness_never_queries_closed_handle():
    runner = InferenceProcessRunner(timeout_seconds=1)
    connection, process = SuccessfulConnection(), HungProcess()
    _install_worker(runner, connection, process)
    runner._warmed_kinds.add("v2")
    runner._state_lock.acquire()
    try:
        assert not runner.is_warmed("v2")
    finally:
        runner._state_lock.release()
    assert runner.is_warmed("v2")
    runner.close()
    calls = list(process.calls)
    assert not runner.is_warmed("v2")
    runner.close()
    assert process.calls == calls
    assert connection.last_request is None


@pytest.mark.parametrize("path,code", [
    ("/detect", "model_unavailable"),
    ("/detect/v2", "detect_v2_unavailable"),
])
def test_cleanup_failure_uses_existing_generic_503(monkeypatch, path, code):
    runner = InferenceProcessRunner(timeout_seconds=0.01, startup_timeout_seconds=0.01)
    process = _StubbornProcess()
    _install_worker(runner, HungConnection(), process)

    async def image_upload(_image, _settings):
        return b"image", "image/jpeg"

    async def directly(call):
        return call()

    monkeypatch.setattr(detect_api, "_read_detection_image_upload", image_upload)
    monkeypatch.setattr(detect_api.to_thread, "run_sync", directly)
    router = detect_api.create_router(get_settings(), inference_runner=runner)
    endpoint = next(route.endpoint for route in router.routes if route.path == path)
    try:
        with pytest.raises(HTTPException) as caught:
            asyncio.run(endpoint(context="{}", image=object()))
        assert caught.value.status_code == 503
        assert caught.value.detail["code"] == code
        assert caught.value.detail["reason"] == "detector_runtime_error"
        assert "restarted" not in caught.value.detail["message"]
        assert runner._process is process
        assert not runner._usable
    finally:
        process.alive = False
        runner.close()


class _ObservedSocket:
    def __init__(self, channel):
        self.channel = channel
        self.sent = 0
        self.would_block = 0

    def fileno(self):
        return self.channel.fileno()

    def send(self, data):
        try:
            sent = self.channel.send(data)
        except BlockingIOError:
            self.would_block += 1
            raise
        self.sent += sent
        return sent

    def recv(self, size):
        return self.channel.recv(size)

    def close(self):
        self.channel.close()


def test_actual_blocked_send_expires_before_peer_safety_exit(monkeypatch):
    with _owned_runner(monkeypatch, "blocked_send") as (runner, records):
        _prepare_fault_peer(runner)
        runner._connection._socket.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
        observed = _ObservedSocket(runner._connection._socket)
        runner._connection._socket = observed
        started = time.monotonic()
        with pytest.raises(InferenceDeadlineExceeded):
            runner._run("legacy", b"x" * (8 * 1024 * 1024), "image/jpeg", DetectContext(), object())
        elapsed = time.monotonic() - started
        assert elapsed < 5
        assert observed.sent > 0
        assert observed.would_block > 0
        process_record = next(record for record in reversed(records) if record["kind"] == "process")
        process_record["observed_sent_bytes"] = observed.sent
        process_record["observed_would_block"] = observed.would_block
        process_record["timeout_elapsed_seconds"] = elapsed
        assert process_record["exitcode"] is not None
        assert process_record["exitcode"] != 0
        assert process_record["closed"]
        assert process_record["cleanup_owner"] == "runner"


@pytest.mark.parametrize("mode", ["partial_header", "partial_body"])
def test_actual_partial_response_expires_before_peer_safety_exit(monkeypatch, mode):
    with _owned_runner(monkeypatch, mode) as (runner, records):
        _prepare_fault_peer(runner)
        received = []
        original_recv = runner._connection._socket.recv

        class ReceivingSocket(_ObservedSocket):
            def recv(self, size):
                part = original_recv(size)
                received.append(part)
                return part

        runner._connection._socket = ReceivingSocket(runner._connection._socket)
        started = time.monotonic()
        with pytest.raises(InferenceDeadlineExceeded):
            runner._run("legacy", b"image", "image/jpeg", DetectContext(), object())
        elapsed = time.monotonic() - started
        assert elapsed < 5
        assert len(b"".join(received)) == (3 if mode == "partial_header" else 11)
        process_record = next(record for record in reversed(records) if record["kind"] == "process")
        process_record["partial_frame_bytes"] = len(b"".join(received))
        process_record["timeout_elapsed_seconds"] = elapsed
        assert process_record["exitcode"] is not None
        assert process_record["exitcode"] != 0
        assert process_record["closed"]
        assert process_record["cleanup_owner"] == "runner"


def test_actual_next_generation_succeeds_after_timeout(monkeypatch):
    with _owned_runner(monkeypatch, "partial_body", startup_timeout_seconds=1) as (runner, records):
        _prepare_fault_peer(runner)
        first_process = runner._process
        first_pid = first_process.pid
        with pytest.raises(InferenceDeadlineExceeded):
            runner._run("legacy", b"image", "image/jpeg", DetectContext(), object())
        assert first_process.closed
        assert first_process.record["alive_at_close"] is False
        assert first_process.record["exitcode"] is not None
        assert runner._process is None
        assert runner._connection is None
        runner._context.mode = None
        runner.startup_timeout_seconds = 5
        request = dict(
            image_bytes=b"fake-provider-does-not-read-image",
            content_type="image/jpeg",
            context=DetectContext(
                captured_at=datetime.now(timezone.utc),
                gps={"latitude": 37.5, "longitude": 127.0, "accuracy_m": 5.0},
            ),
            settings=get_settings(),
        )
        resource_count = len(records)
        with pytest.raises(ipc.InferenceRestartAdmissionLimited):
            runner.run_v2(**request)
        assert len(records) == resource_count
        assert len(runner._worker_creation_times) == 1
        # Model the first permitted restart without a five-second wall-clock wait.
        runner._worker_creation_times[-1] = (
            time.monotonic() - ipc._MIN_WORKER_CREATION_INTERVAL_SECONDS
        )
        response = runner.run_v2(**request)
        assert response.schema_version == "detect.v2"
        assert response.detections
        assert runner._process.pid != first_pid
        assert runner.is_warmed("v2")


@pytest.mark.parametrize("acquired_at,expired", [(0.5, False), (2.0, True)])
def test_state_lock_acquisition_rechecks_deadline_before_warming(monkeypatch, acquired_at, expired):
    clock = [0.0]
    events = []
    runner = InferenceProcessRunner(timeout_seconds=1, startup_timeout_seconds=1)
    connection, process = SuccessfulConnection(), HungProcess()
    _install_worker(runner, connection, process)
    actual_lock = runner._state_lock
    original_exchange = connection.exchange
    original_check = ipc._check_deadline

    def exchange(request, deadline):
        result = original_exchange(request, deadline)
        events.append(("exchange", clock[0]))
        return result

    def check_deadline(deadline):
        events.append(("deadline_check", clock[0]))
        return original_check(deadline)

    class AcquisitionClock:
        advanced = False

        def acquire(self, *args, **kwargs):
            return actual_lock.acquire(*args, **kwargs)

        def release(self):
            actual_lock.release()

        def __enter__(self):
            actual_lock.acquire()
            if not self.advanced:
                self.advanced = True
                clock[0] = acquired_at
                events.append(("state_lock_acquired", clock[0]))
            return self

        def __exit__(self, *_args):
            actual_lock.release()

    runner._state_lock = AcquisitionClock()
    monkeypatch.setattr(ipc.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(connection, "exchange", exchange)
    monkeypatch.setattr(ipc, "_check_deadline", check_deadline)
    try:
        if expired:
            with pytest.raises(InferenceDeadlineExceeded):
                runner._run("v2", b"image", "image/png", DetectContext(), object())
            assert not runner._warmed_kinds
            assert process.closed
            assert runner._process is None
            assert runner._connection is None
        else:
            assert _is_valid_worker_result(runner._run("v2", b"image", "image/png", DetectContext(), object()), "v2")
            assert runner._warmed_kinds == {"v2"}
            assert runner._process is process
            assert runner._connection is connection
            assert not process.terminated
        acquisition = next(i for i, event in enumerate(events) if event[0] == "state_lock_acquired")
        assert events[0] == ("exchange", 0.0)
        assert events[1:acquisition]
        assert all(event == ("deadline_check", 0.0) for event in events[1:acquisition])
        assert events[acquisition + 1] == ("deadline_check", acquired_at)
        assert runner._lock.acquire(blocking=False)
        runner._lock.release()
    finally:
        runner.close()


@pytest.mark.parametrize("primary_kind", ["deadline", "transport_error"])
@pytest.mark.parametrize("fatal_type", [KeyboardInterrupt, SystemExit])
def test_selector_fatal_cleanup_propagates_over_ordinary_primary(monkeypatch, primary_kind, fatal_type):
    primary = (
        InferenceDeadlineExceeded("fixture deadline")
        if primary_kind == "deadline"
        else OSError("fixture transport")
    )
    fatal = fatal_type("fixture cleanup interruption")
    selector = _ScriptedSelector()
    channel_socket = _ScriptedSocket(sends=[primary])
    channel = ipc._FramedSocket(channel_socket)
    original_close = selector.close

    def interrupt_close():
        raise fatal

    monkeypatch.setattr(ipc.selectors, "DefaultSelector", lambda: selector)
    monkeypatch.setattr(selector, "close", interrupt_close)
    try:
        with pytest.raises(fatal_type) as caught:
            channel.exchange(("request",), time.monotonic() + 1)
        assert caught.value is fatal
        assert channel._selector is selector
        assert not channel_socket.closed
    finally:
        monkeypatch.setattr(selector, "close", original_close)
        channel.close()
    assert selector.closed
    assert channel_socket.closed
