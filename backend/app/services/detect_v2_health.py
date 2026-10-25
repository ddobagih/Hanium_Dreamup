"""Cached detect.v2 health with an owned, bounded Linux subprocess refresh.

The HTTP reader performs no filesystem I/O. Refresh timeout and process cleanup
are separate budgets; an unconfirmed cleanup quarantines the owned resources.
"""
from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar

from dataclasses import dataclass
import json
import math
import os
from pathlib import Path
import selectors
import stat
import subprocess
import sys
from threading import Event, Lock, Thread, current_thread
import time
from typing import Any, Callable, Sequence

MAX_HEALTH_FRAME_BYTES = 65_536
FRAME_HEADER_BYTES = 8
CLEANUP_TIMEOUT_SECONDS = 1.0
_POLL_SECONDS = 0.01
_PREFIX = "detect_v2_health_"
_REASONS = frozenset({
    "not_checked", "expired", "refresh_timeout", "refresh_failed",
    "artifacts_changed", "stopped",
})
_PATH_FIELDS = (
    "detect_v2_custom_tactile_model_path",
    "detect_v2_coco_model_path",
    "detect_v2_unified_model_path",
    "detect_v2_runtime_config_path",
)
_RESPONSE_PATH_FIELDS = (
    "custom_tactile_model_path", "coco_model_path",
    "unified_model_path", "runtime_config_path",
)
_RESPONSE_FIELDS = frozenset({
    "schema_version", "mode", "status", "reason",
    "runtime_primary_model", "runtime_fallback_model", "configured_runtime",
    "image_size", *_RESPONSE_PATH_FIELDS,
})


@dataclass(frozen=True)
class DetectV2HealthPolicy:
    refresh_interval_seconds: float = 5.0
    max_age_seconds: float = 10.0
    refresh_timeout_seconds: float = 5.0

    def __post_init__(self) -> None:
        for name in (
            "refresh_interval_seconds", "max_age_seconds", "refresh_timeout_seconds",
        ):
            value = getattr(self, name)
            if (
                isinstance(value, bool) or not isinstance(value, (int, float))
                or not math.isfinite(value) or value <= 0
            ):
                raise ValueError("health policy durations must be finite and positive")
        if self.max_age_seconds < max(
            self.refresh_interval_seconds, self.refresh_timeout_seconds,
        ):
            raise ValueError("health maximum age must cover interval and timeout")


@dataclass(frozen=True)
class _SettingsProjection:
    detect_v2_mode: str
    walksafe_environment: str
    detect_v2_custom_tactile_model_path: Path | None
    detect_v2_coco_model_path: Path | None
    detect_v2_unified_model_path: Path | None
    detect_v2_runtime_config_path: Path | None
    detect_v2_image_size: int

    @classmethod
    def capture(cls, settings: Any) -> "_SettingsProjection":
        mode = getattr(settings, "detect_v2_mode", "fake")
        environment = getattr(settings, "walksafe_environment", "development")
        size = getattr(settings, "detect_v2_image_size", 768)
        if not isinstance(mode, str) or not isinstance(environment, str):
            raise ValueError("health projection mode and environment must be strings")
        if isinstance(size, bool) or not isinstance(size, int) or size <= 0:
            raise ValueError("health projection image size must be a positive integer")
        paths = []
        for name in _PATH_FIELDS:
            value = getattr(settings, name, None)
            paths.append(None if value is None else Path(value))
        return cls(mode, environment, *paths, size)

    def wire(self) -> dict[str, object]:
        return {
            "detect_v2_mode": self.detect_v2_mode,
            "walksafe_environment": self.walksafe_environment,
            **{
                name: None if getattr(self, name) is None else str(getattr(self, name))
                for name in _PATH_FIELDS
            },
            "detect_v2_image_size": self.detect_v2_image_size,
        }

    @classmethod
    def from_wire(cls, value: object) -> "_SettingsProjection":
        names = {"detect_v2_mode", "walksafe_environment", "detect_v2_image_size", *_PATH_FIELDS}
        if not isinstance(value, dict) or set(value) != names:
            raise ValueError("invalid health settings projection")
        for name in _PATH_FIELDS:
            if value[name] is not None and not isinstance(value[name], str):
                raise ValueError("invalid health projection path")
        from types import SimpleNamespace
        return cls.capture(SimpleNamespace(**value))


def _unavailable(projection: _SettingsProjection, reason: str) -> dict[str, object]:
    if reason not in _REASONS:
        reason = "refresh_failed"
    return {
        "schema_version": "detect.v2",
        "mode": projection.detect_v2_mode,
        "status": "unavailable",
        "reason": _PREFIX + reason,
        "runtime_primary_model": None,
        "runtime_fallback_model": None,
        "configured_runtime": None,
        "image_size": projection.detect_v2_image_size,
        **{
            field: None if getattr(projection, name) is None else getattr(projection, name).name
            for field, name in zip(_RESPONSE_PATH_FIELDS, _PATH_FIELDS)
        },
    }


def _validated_response(value: object, projection: _SettingsProjection) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != _RESPONSE_FIELDS:
        raise ValueError("invalid health response fields")
    if (
        value["schema_version"] != "detect.v2"
        or value["mode"] != projection.detect_v2_mode
        or value["status"] not in {"ready", "unavailable"}
        or type(value["image_size"]) is not int
        or value["image_size"] != projection.detect_v2_image_size
    ):
        raise ValueError("invalid health response identity")
    for name in _RESPONSE_FIELDS - {"image_size"}:
        if value[name] is not None and not isinstance(value[name], str):
            raise ValueError("health response must be flat")
    if value["status"] == "ready" and value["reason"] is not None:
        raise ValueError("ready health response cannot have a failure reason")
    if value["status"] == "unavailable" and not value["reason"]:
        raise ValueError("unavailable health response requires a reason")
    for response_name, name in zip(_RESPONSE_PATH_FIELDS, _PATH_FIELDS):
        expected = None if getattr(projection, name) is None else getattr(projection, name).name
        if value[response_name] != expected:
            raise ValueError("health response path must be the configured basename")
    return dict(value)


class _RefreshProblem(RuntimeError):
    def __init__(self, reason: str) -> None:
        self.reason = reason if reason in _REASONS else "refresh_failed"
        super().__init__(_PREFIX + self.reason)


def _remaining(deadline: float, stop: Event | None = None) -> float:
    if stop is not None and stop.is_set():
        raise _RefreshProblem("stopped")
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise _RefreshProblem("refresh_timeout")
    return remaining


def _json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _reject_constant(_value: str) -> None:
    raise ValueError("nonfinite JSON value")


def _decode_json(data: bytes) -> object:
    return json.loads(
        data.decode("utf-8"), object_pairs_hook=_json_object,
        parse_constant=_reject_constant,
    )


def _frame(value: object) -> bytes:
    payload = json.dumps(
        value, ensure_ascii=True, allow_nan=False, separators=(",", ":"),
    ).encode("utf-8")
    if not 0 < len(payload) <= MAX_HEALTH_FRAME_BYTES:
        raise ValueError("health frame exceeds the protocol limit")
    return len(payload).to_bytes(FRAME_HEADER_BYTES, "big") + payload


def _health_worker_command() -> tuple[str, ...]:
    """Test seam: a bootstrap can install child guards before importing the worker."""
    return (
        sys.executable, "-c",
        "from backend.app.services.detect_v2_health import _worker_main; "
        "raise SystemExit(_worker_main())",
    )


def _raise_cleanup_errors(errors: list[BaseException]) -> None:
    for error in errors:
        if not isinstance(error, Exception):
            raise error
    if errors:
        raise _RefreshProblem("refresh_failed") from None


class _SubprocessRefreshRunner:
    """One owned child and pipe pair. Never replace quarantined resources."""

    def __init__(
        self,
        command: Sequence[str] | Callable[[], Sequence[str]] | None = None,
        *,
        popen_factory: Callable[..., Any] | None = None,
    ) -> None:
        self._command = command
        self._popen_factory = popen_factory
        self._process: subprocess.Popen[bytes] | None = None
        self._input: Any = None
        self._output: Any = None
        self._selector: selectors.BaseSelector | None = None
        self._quarantined = False
        self.last_cleanup: dict[str, object] | None = None
        self.last_exchange: dict[str, object] = {}

    @property
    def quarantined(self) -> bool:
        return self._quarantined

    def __call__(
        self, projection: _SettingsProjection, timeout_seconds: float, stop_event: Event,
    ) -> dict[str, object]:
        if self._quarantined or self._process is not None:
            raise _RefreshProblem("refresh_failed")
        deadline = time.monotonic() + timeout_seconds
        primary: BaseException | None = None
        self.last_exchange = {"sent_bytes": 0, "received_bytes": 0, "eof": False}
        try:
            request = _frame({"settings": projection.wire(), "deadline": deadline})
            _remaining(deadline, stop_event)
            command = (
                self._command() if callable(self._command)
                else self._command if self._command is not None
                else _health_worker_command()
            )
            factory = self._popen_factory or subprocess.Popen
            process = factory(
                tuple(command), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, bufsize=0, close_fds=True,
            )
            self._process = process
            self._input, self._output = process.stdin, process.stdout
            _remaining(deadline, stop_event)
            if self._input is None or self._output is None:
                raise _RefreshProblem("refresh_failed")
            os.set_blocking(self._input.fileno(), False)
            os.set_blocking(self._output.fileno(), False)
            selector = selectors.DefaultSelector()
            self._selector = selector
            selector.register(self._input, selectors.EVENT_WRITE, "input")
            selector.register(self._output, selectors.EVENT_READ, "output")
            sent = 0
            received = bytearray()
            expected: int | None = None
            eof = False
            while not eof:
                remaining = _remaining(deadline, stop_event)
                events = selector.select(min(_POLL_SECONDS, remaining))
                _remaining(deadline, stop_event)
                for key, _mask in events:
                    if key.data == "input":
                        try:
                            count = os.write(self._input.fileno(), request[sent:])
                        except BlockingIOError:
                            continue
                        if count <= 0:
                            raise _RefreshProblem("refresh_failed")
                        sent += count
                        self.last_exchange["sent_bytes"] = sent
                        if sent == len(request):
                            selector.unregister(self._input)
                            self._input.close()
                            self._input = None
                    else:
                        room = FRAME_HEADER_BYTES + MAX_HEALTH_FRAME_BYTES + 1 - len(received)
                        try:
                            chunk = os.read(self._output.fileno(), min(65_536, max(1, room)))
                        except BlockingIOError:
                            continue
                        if not chunk:
                            eof = True
                            self.last_exchange["eof"] = True
                            selector.unregister(self._output)
                        else:
                            received.extend(chunk)
                            self.last_exchange["received_bytes"] = len(received)
                            if expected is None and len(received) >= FRAME_HEADER_BYTES:
                                expected = int.from_bytes(received[:FRAME_HEADER_BYTES], "big")
                                if not 0 < expected <= MAX_HEALTH_FRAME_BYTES:
                                    raise _RefreshProblem("refresh_failed")
                            if expected is not None and len(received) > FRAME_HEADER_BYTES + expected:
                                raise _RefreshProblem("refresh_failed")
                    _remaining(deadline, stop_event)
            if (
                sent != len(request) or expected is None
                or len(received) != FRAME_HEADER_BYTES + expected
            ):
                raise _RefreshProblem("refresh_failed")
            try:
                result = _validated_response(
                    _decode_json(bytes(received[FRAME_HEADER_BYTES:])), projection,
                )
            except (ValueError, TypeError):
                raise _RefreshProblem("refresh_failed") from None
            _remaining(deadline, stop_event)
            while process.poll() is None:
                stop_event.wait(min(_POLL_SECONDS, _remaining(deadline, stop_event)))
            _remaining(deadline, stop_event)
            if process.returncode != 0:
                raise _RefreshProblem("refresh_failed")
            return result
        except BaseException as exc:
            primary = exc
            raise
        finally:
            try:
                if not self.close():
                    raise _RefreshProblem("refresh_failed")
            except BaseException as cleanup:
                if primary is None or (
                    isinstance(primary, Exception) and not isinstance(cleanup, Exception)
                ):
                    raise
                primary.add_note("health refresh resource cleanup failed")

    def close(self, timeout_seconds: float = CLEANUP_TIMEOUT_SECONDS) -> bool:
        deadline = time.monotonic() + max(0.0, timeout_seconds)
        errors: list[BaseException] = []
        process = self._process
        exit_confirmed = process is None
        if process is not None:
            try:
                if process.poll() is None:
                    try:
                        process.terminate()
                    except ProcessLookupError:
                        pass
                    try:
                        process.wait(timeout=max(0.0, (deadline - time.monotonic()) / 2))
                    except subprocess.TimeoutExpired:
                        try:
                            process.kill()
                        except ProcessLookupError:
                            pass
                process.wait(timeout=max(0.0, deadline - time.monotonic()))
                exit_confirmed = process.returncode is not None
            except BaseException as exc:
                errors.append(exc)
            self.last_cleanup = {
                "pid": process.pid,
                "returncode": process.returncode,
                "exit_confirmed": exit_confirmed,
                "cleanup_owner": "health_runner",
            }
            if exit_confirmed:
                self._process = None
        for name in ("_selector", "_input", "_output"):
            resource = getattr(self, name)
            if resource is not None:
                try:
                    resource.close()
                except BaseException as exc:
                    errors.append(exc)
                else:
                    setattr(self, name, None)
        complete = (
            exit_confirmed and self._process is None and self._selector is None
            and self._input is None and self._output is None
        )
        self._quarantined = not complete
        if self.last_cleanup is not None:
            self.last_cleanup["channels_closed"] = self._input is None and self._output is None
            self.last_cleanup["selector_closed"] = self._selector is None
            self.last_cleanup["complete"] = complete
        # Ordinary cleanup failure is represented by false and retained refs.
        if any(not isinstance(exc, Exception) for exc in errors):
            _raise_cleanup_errors(errors)
        return complete


def _stat_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev, metadata.st_ino, metadata.st_mode,
        metadata.st_uid, metadata.st_gid, metadata.st_size,
        metadata.st_mtime_ns, metadata.st_ctime_ns,
    )


def _fingerprint(projection: _SettingsProjection, deadline: float) -> tuple[object, ...]:
    result: list[object] = []
    for name in _PATH_FIELDS:
        _remaining(deadline)
        path = getattr(projection, name)
        if path is None:
            result.append((name, None))
            continue
        try:
            metadata = path.lstat()
        except FileNotFoundError:
            result.append((name, str(path), "missing"))
            continue
        target: object = None
        if stat.S_ISLNK(metadata.st_mode):
            try:
                target = _stat_identity(path.stat())
            except FileNotFoundError:
                target = "missing"
        result.append((name, str(path), _stat_identity(metadata), target))
    _remaining(deadline)
    return tuple(result)


def _read_exact(descriptor: int, count: int) -> bytes:
    result = bytearray()
    while len(result) < count:
        chunk = os.read(descriptor, count - len(result))
        if not chunk:
            raise ValueError("incomplete health request")
        result.extend(chunk)
    return bytes(result)


def _worker_main() -> int:
    """Run only in the dedicated child; protocol stdout never carries diagnostics."""
    output_descriptor = os.dup(sys.stdout.fileno())
    projection: _SettingsProjection | None = None
    try:
        os.dup2(sys.stderr.fileno(), sys.stdout.fileno())
        size = int.from_bytes(_read_exact(sys.stdin.fileno(), FRAME_HEADER_BYTES), "big")
        if not 0 < size <= MAX_HEALTH_FRAME_BYTES:
            return 2
        request = _decode_json(_read_exact(sys.stdin.fileno(), size))
        if not isinstance(request, dict) or set(request) != {"settings", "deadline"}:
            return 2
        projection = _SettingsProjection.from_wire(request["settings"])
        deadline = request["deadline"]
        if (
            isinstance(deadline, bool) or not isinstance(deadline, (int, float))
            or not math.isfinite(deadline)
        ):
            return 2
        try:
            _remaining(deadline)
            before = _fingerprint(projection, deadline)
            from backend.app.services.detect_v2 import compute_detect_v2_health_candidate
            candidate = compute_detect_v2_health_candidate(projection)
            _remaining(deadline)
            after = _fingerprint(projection, deadline)
            if before != after:
                raise _RefreshProblem("artifacts_changed")
            result = _validated_response(candidate, projection)
            _remaining(deadline)
        except _RefreshProblem as exc:
            result = _unavailable(projection, exc.reason)
        except Exception:
            result = _unavailable(projection, "refresh_failed")
        packet = _frame(result)
        sent = 0
        while sent < len(packet):
            count = os.write(output_descriptor, packet[sent:])
            if count <= 0:
                return 2
            sent += count
        return 0
    except Exception:
        return 2
    finally:
        os.close(output_descriptor)


class DetectV2HealthSnapshots:
    """One coordinator, one in-flight refresh, and a memory-only public reader."""

    def __init__(
        self, policy: DetectV2HealthPolicy | None = None, *,
        runner: Callable[[_SettingsProjection, float, Event], dict[str, object]] | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.policy = policy or DetectV2HealthPolicy()
        self._runner = runner if runner is not None else _SubprocessRefreshRunner()
        self._clock = clock
        self._lock = Lock()
        self._stop = Event()
        self._wake = Event()
        self._initial = Event()
        self._thread: Thread | None = None
        self._settings: Any = None
        self._projection: _SettingsProjection | None = None
        self._generation = 0
        self._attempt = 0
        self._active_attempt = 0
        self._active_generation = 0
        self._active_started = 0.0
        self._reject_through_attempt = 0
        self._published: dict[str, object] | None = None
        self._published_started = 0.0
        self._reason = "not_checked"
        self._closed = False
        self._first_deadline: float | None = None

    def _sync_locked(self, projection: _SettingsProjection) -> None:
        if projection != self._projection:
            self._generation += 1
            self._projection = projection
            self._published = None
            self._reason = "not_checked"
            # A superseded attempt owns its captured event, not this new one.
            self._initial = Event()

    def start(self, settings: Any) -> None:
        started = time.monotonic()
        with self._lock:
            if self._closed:
                raise RuntimeError("health snapshot manager is closed")
            projection = _SettingsProjection.capture(settings)
            self._settings = settings
            self._sync_locked(projection)
            initial = self._initial
            generation = self._generation
            if self._thread is None:
                self._first_deadline = started + self.policy.refresh_timeout_seconds
                if initial.is_set():
                    self._initial = Event()
                    initial = self._initial
                self._thread = Thread(
                    target=self._coordinate, name="walksafe-detect-v2-health", daemon=True,
                )
                try:
                    self._thread.start()
                except Exception:
                    self._thread = None
                    self._reason = "refresh_failed"
                    initial.set()
            else:
                self._wake.set()
        budget = max(0.0, self.policy.refresh_timeout_seconds - (time.monotonic() - started))
        if not initial.wait(budget):
            with self._lock:
                if (
                    self._generation == generation
                    and self._initial is initial
                    and not initial.is_set()
                ):
                    self._reason = "refresh_timeout"
                    self._published = None
                    self._reject_through_attempt = max(
                        self._reject_through_attempt, self._attempt,
                    )
                    initial.set()

    def _coordinate(self) -> None:
        try:
            while not self._stop.is_set():
                self._refresh_once()
                if self._stop.is_set():
                    break
                self._wake.wait(self.policy.refresh_interval_seconds)
                self._wake.clear()
        finally:
            with self._lock:
                self._active_attempt = 0
                self._active_generation = 0
                if not self._stop.is_set():
                    # A terminated coordinator cannot complete a newer startup.
                    self._published = None
                    self._reason = "refresh_failed"
                self._initial.set()

    def _refresh_once(self) -> None:
        with self._lock:
            if self._stop.is_set():
                return
            try:
                projection = _SettingsProjection.capture(self._settings)
            except Exception:
                self._reason = "refresh_failed"
                self._published = None
                self._initial.set()
                return
            self._sync_locked(projection)
            generation = self._generation
            initial = self._initial
            self._attempt += 1
            attempt = self._attempt
            self._active_attempt = attempt
            self._active_generation = generation
            started = self._clock()
            self._active_started = started
            first_deadline = self._first_deadline
            self._first_deadline = None
        result: dict[str, object] | None = None
        failure: str | None = None
        try:
            if not math.isfinite(started):
                raise _RefreshProblem("refresh_failed")
            timeout = self.policy.refresh_timeout_seconds
            if first_deadline is not None:
                timeout = min(timeout, _remaining(first_deadline, self._stop))
            result = _validated_response(
                self._runner(projection, timeout, self._stop),
                projection,
            )
        except _RefreshProblem as exc:
            failure = exc.reason
        except Exception:
            failure = "refresh_failed"
        except BaseException:
            with self._lock:
                if self._active_attempt == attempt:
                    self._active_attempt = 0
                    self._active_generation = 0
                    if generation == self._generation:
                        self._reason = "refresh_failed"
                        self._published = None
                        initial.set()
                    elif initial is not self._initial:
                        initial.set()
            raise
        with self._lock:
            # Retire only this attempt; an obsolete completion cannot own a
            # newer configuration or complete its startup wait.
            if self._active_attempt != attempt:
                if initial is not self._initial:
                    initial.set()
                return
            self._active_attempt = 0
            self._active_generation = 0
            if self._stop.is_set():
                self._published = None
                self._reason = "stopped"
                initial.set()
                return
            if generation != self._generation:
                self._wake.set()
                if initial is not self._initial:
                    initial.set()
                return
            try:
                latest = _SettingsProjection.capture(self._settings)
            except Exception:
                latest = None
                failure = "refresh_failed"
            if latest is not None and latest != projection:
                self._sync_locked(latest)
                self._wake.set()
                initial.set()
                return
            completed = self._clock()
            if attempt <= self._reject_through_attempt:
                self._published = None
                self._reason = "refresh_timeout"
            else:
                elapsed = completed - started
                if not math.isfinite(elapsed) or elapsed < 0:
                    failure = "refresh_failed"
                elif elapsed >= self.policy.refresh_timeout_seconds:
                    failure = "refresh_timeout"
                if failure is not None:
                    self._published = None
                    self._reason = failure
                else:
                    self._published = result
                    self._published_started = started
            initial.set()

    def snapshot(self, settings: Any) -> dict[str, object]:
        with self._lock:
            projection = _SettingsProjection.capture(settings)
            now = self._clock()
            if self._closed or self._stop.is_set():
                return _unavailable(projection, "stopped")
            if settings is self._settings:
                self._sync_locked(projection)
            if projection != self._projection:
                return _unavailable(projection, "not_checked")
            if (
                self._active_attempt
                and self._active_generation == self._generation
            ):
                elapsed = now - self._active_started
                if not math.isfinite(elapsed) or elapsed < 0:
                    self._published = None
                    self._reason = "refresh_failed"
                    self._reject_through_attempt = self._active_attempt
                elif elapsed >= self.policy.refresh_timeout_seconds:
                    self._published = None
                    self._reason = "refresh_timeout"
                    self._reject_through_attempt = self._active_attempt
            if self._published is None:
                return _unavailable(projection, self._reason)
            age = now - self._published_started
            if not math.isfinite(age) or age < 0 or age >= self.policy.max_age_seconds:
                return _unavailable(projection, "expired")
            return dict(self._published)

    def close(self) -> bool:
        deadline = time.monotonic() + CLEANUP_TIMEOUT_SECONDS
        with self._lock:
            self._closed = True
            self._published = None
            self._reason = "stopped"
            self._stop.set()
            self._wake.set()
            self._initial.set()
            thread = self._thread
        if thread is current_thread():
            return False
        if thread is not None:
            if thread.ident is not None:
                thread.join(max(0.0, deadline - time.monotonic()))
            if thread.is_alive():
                return False
        if isinstance(self._runner, _SubprocessRefreshRunner):
            return self._runner.close(max(0.0, deadline - time.monotonic()))
        return True


_app_health_snapshots: ContextVar[DetectV2HealthSnapshots | None] = ContextVar("walksafe_detector_health", default=None)


@contextmanager
def use_detect_v2_health(manager):
    token = _app_health_snapshots.set(manager)
    try:
        yield
    finally:
        _app_health_snapshots.reset(token)


health_snapshots = DetectV2HealthSnapshots()
_singleton_lock = Lock()


def start_detect_v2_health(settings: Any) -> None:
    scoped_manager = _app_health_snapshots.get()
    if scoped_manager is not None:
        scoped_manager.start(settings)
        return
    policy = DetectV2HealthPolicy(
        refresh_interval_seconds=getattr(settings, "detect_v2_health_refresh_interval_seconds", 5.0),
        max_age_seconds=getattr(settings, "detect_v2_health_max_age_seconds", 10.0),
        refresh_timeout_seconds=getattr(settings, "detect_v2_health_refresh_timeout_seconds", 5.0),
    )
    global health_snapshots
    with _singleton_lock:
        manager = health_snapshots
        if manager.policy != policy or manager._closed:
            if not manager.close():
                raise RuntimeError("detect v2 health refresh cleanup is unconfirmed")
            manager = DetectV2HealthSnapshots(policy)
            health_snapshots = manager
    manager.start(settings)


def stop_detect_v2_health() -> None:
    if not (_app_health_snapshots.get() or health_snapshots).close():
        raise RuntimeError("detect v2 health refresh cleanup is unconfirmed")


def detect_v2_health_snapshot(settings: Any) -> dict[str, object]:
    return (_app_health_snapshots.get() or health_snapshots).snapshot(settings)
