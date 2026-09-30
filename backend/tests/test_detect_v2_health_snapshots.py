"""Local detect health contracts. Real child cases use Linux pipes, not a DB."""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import FrozenInstanceError
import json
import os
import signal
from pathlib import Path
import subprocess
import sys
from threading import Event, Lock, Thread, current_thread
import time
from types import SimpleNamespace

import pytest

from backend.app.services import detect_v2_health as health


def _settings(**changes):
    values = {
        "detect_v2_mode": "fake",
        "walksafe_environment": "test",
        "detect_v2_custom_tactile_model_path": None,
        "detect_v2_coco_model_path": None,
        "detect_v2_unified_model_path": None,
        "detect_v2_runtime_config_path": None,
        "detect_v2_image_size": 768,
    }
    values.update(changes)
    return SimpleNamespace(**values)


def _ready(projection):
    result = health._unavailable(projection, "not_checked")
    result.update(
        status="ready", reason=None,
        runtime_primary_model="unified_walksafe",
        runtime_fallback_model="legacy_two_model",
    )
    return result


def _wait(predicate, seconds=6.0):
    deadline = time.monotonic() + seconds
    while not predicate():
        if time.monotonic() >= deadline:
            pytest.fail("owned health test observation exceeded its safety deadline")
        time.sleep(0.005)


@pytest.mark.parametrize("field,value", [
    ("refresh_interval_seconds", 0),
    ("refresh_interval_seconds", float("nan")),
    ("max_age_seconds", -1),
    ("max_age_seconds", float("inf")),
    ("refresh_timeout_seconds", float("-inf")),
    ("refresh_timeout_seconds", True),
])
def test_health_policy_rejects_nonpositive_or_nonfinite_values(field, value):
    values = {
        "refresh_interval_seconds": 5.0,
        "max_age_seconds": 10.0,
        "refresh_timeout_seconds": 5.0,
    }
    values[field] = value
    with pytest.raises(ValueError):
        health.DetectV2HealthPolicy(**values)


@pytest.mark.parametrize("interval,age,timeout", [(11, 10, 5), (5, 10, 11)])
def test_health_policy_age_covers_interval_and_refresh(interval, age, timeout):
    with pytest.raises(ValueError):
        health.DetectV2HealthPolicy(interval, age, timeout)


def test_health_policy_defaults_are_dedicated_and_projection_is_immutable(tmp_path):
    policy = health.DetectV2HealthPolicy()
    assert (policy.refresh_interval_seconds, policy.max_age_seconds, policy.refresh_timeout_seconds) == (5, 10, 5)
    settings = _settings(
        detect_v2_unified_model_path=tmp_path / "model.pt",
        privacy_hmac_secret="synthetic-not-transmitted",
    )
    projection = health._SettingsProjection.capture(settings)
    assert isinstance(projection.detect_v2_unified_model_path, Path)
    assert not hasattr(projection, "privacy_hmac_secret")
    assert set(projection.wire()) == {
        "detect_v2_mode", "walksafe_environment", "detect_v2_image_size",
        *health._PATH_FIELDS,
    }
    with pytest.raises(FrozenInstanceError):
        projection.detect_v2_mode = "real"


def test_cold_and_warm_snapshot_are_detached_io_free_peeks(monkeypatch):
    calls = []
    settings = _settings()
    manager = health.DetectV2HealthSnapshots(
        health.DetectV2HealthPolicy(60, 60, 1),
        runner=lambda projection, timeout, stop: calls.append(1) or _ready(projection),
    )

    def forbidden(*args, **kwargs):
        raise AssertionError("snapshot must not inspect files or create work")

    try:
        with monkeypatch.context() as blocked:
            for name in ("stat", "lstat", "open", "exists", "is_file", "is_symlink", "resolve"):
                blocked.setattr(Path, name, forbidden)
            blocked.setattr(subprocess, "Popen", forbidden)
            assert manager.snapshot(settings)["reason"] == "detect_v2_health_not_checked"
            assert calls == []
        manager.start(settings)
        with monkeypatch.context() as blocked:
            for name in ("stat", "lstat", "open", "exists", "is_file", "is_symlink", "resolve"):
                blocked.setattr(Path, name, forbidden)
            blocked.setattr(subprocess, "Popen", forbidden)
            blocked.setattr(health.Thread, "start", forbidden)
            first = manager.snapshot(settings)
            assert first["status"] == "ready"
            first["reason"] = "caller mutation"
            for _ in range(25):
                assert manager.snapshot(settings)["reason"] is None
            assert len(calls) == 1
    finally:
        assert manager.close()


def test_snapshot_age_is_anchored_at_refresh_start_and_reads_do_not_extend_it():
    now = [100.0]
    settings = _settings()
    manager = health.DetectV2HealthSnapshots(
        health.DetectV2HealthPolicy(60, 60, 1),
        runner=lambda projection, timeout, stop: _ready(projection),
        clock=lambda: now[0],
    )
    try:
        manager.start(settings)
        now[0] = 159.999
        assert manager.snapshot(settings)["status"] == "ready"
        now[0] = 160.0
        assert manager.snapshot(settings)["reason"] == "detect_v2_health_expired"
        now[0] = 161.0
        assert manager.snapshot(settings)["reason"] == "detect_v2_health_expired"
    finally:
        assert manager.close()


def test_periodic_refresh_failure_does_not_hide_behind_previous_ready():
    settings = _settings()
    fail = Event()
    calls = []

    def run(projection, timeout, stop):
        calls.append(1)
        if fail.is_set():
            raise RuntimeError("synthetic internal error, not a public reason")
        return _ready(projection)

    manager = health.DetectV2HealthSnapshots(
        health.DetectV2HealthPolicy(0.02, 1, 0.5), runner=run,
    )
    try:
        manager.start(settings)
        assert manager.snapshot(settings)["status"] == "ready"
        fail.set()
        _wait(lambda: manager.snapshot(settings)["reason"] == "detect_v2_health_refresh_failed")
        assert len(calls) >= 2
        assert manager.snapshot(settings)["status"] == "unavailable"
    finally:
        assert manager.close()


def test_settings_generation_discards_old_success_and_never_overlaps_refreshes():
    settings = _settings()
    first_entered, first_release = Event(), Event()
    second_entered, second_release = Event(), Event()
    seen = []
    activity = {"active": 0, "peak": 0}
    lock = Lock()

    def run(projection, timeout, stop):
        with lock:
            seen.append(projection.detect_v2_image_size)
            generation = len(seen)
            activity["active"] += 1
            activity["peak"] = max(activity["peak"], activity["active"])
        try:
            if generation == 1:
                first_entered.set()
                assert first_release.wait(3), "owned first observation gate was not released"
            elif generation == 2:
                second_entered.set()
                assert second_release.wait(3), "owned second observation gate was not released"
            return _ready(projection)
        finally:
            with lock:
                activity["active"] -= 1

    manager = health.DetectV2HealthSnapshots(
        health.DetectV2HealthPolicy(60, 60, 4), runner=run,
    )
    starter = Thread(target=manager.start, args=(settings,))
    starter.start()
    try:
        assert first_entered.wait(2)
        settings.detect_v2_image_size = 640
        assert manager.snapshot(settings)["reason"] == "detect_v2_health_not_checked"
        first_release.set()
        assert second_entered.wait(2)
        assert manager.snapshot(settings)["status"] == "unavailable"
        second_release.set()
        _wait(lambda: manager.snapshot(settings)["status"] == "ready")
        assert manager.snapshot(settings)["image_size"] == 640
        assert seen == [768, 640]
        assert activity["peak"] == 1
    finally:
        first_release.set()
        second_release.set()
        starter.join(4)
        assert not starter.is_alive()
        assert manager.close()


def test_timed_out_cooperative_runner_is_retained_until_actual_thread_exit():
    settings = _settings()
    entered, release = Event(), Event()
    calls = []

    def run(projection, timeout, stop):
        calls.append(1)
        entered.set()
        release.wait()
        return _ready(projection)

    manager = health.DetectV2HealthSnapshots(
        health.DetectV2HealthPolicy(1, 1, 0.03), runner=run,
    )
    try:
        manager.start(settings)
        assert entered.is_set()
        for _ in range(5):
            assert manager.snapshot(settings)["reason"] == "detect_v2_health_refresh_timeout"
        assert len(calls) == 1
        assert manager.close() is False
        assert manager._thread is not None and manager._thread.is_alive()
        assert manager.snapshot(settings)["reason"] == "detect_v2_health_stopped"
    finally:
        release.set()
        assert manager.close()
    print("HEALTH_COOPERATIVE_OBSERVATION " + json.dumps({
        "owned_thread_exited": not manager._thread.is_alive(),
        "gate_released_by_fixture": True,
        "real_io_cancellation_proven": False,
    }, sort_keys=True))


def test_stop_wrapper_preserves_manager_on_unconfirmed_cleanup(monkeypatch):
    class Unconfirmed:
        def close(self):
            return False

    manager = Unconfirmed()
    monkeypatch.setattr(health, "health_snapshots", manager)
    with pytest.raises(RuntimeError, match="^detect v2 health refresh cleanup is unconfirmed$"):
        health.stop_detect_v2_health()
    assert health.health_snapshots is manager


_CHILD_GUARDS = r"""
import socket
from sqlalchemy.engine import Engine
def denied(*args, **kwargs):
    raise AssertionError("external access forbidden in owned health child")
Engine.connect = denied
Engine.raw_connection = denied
socket.socket.connect = denied
socket.socket.connect_ex = denied
socket.create_connection = denied
"""


def _command(body):
    return (sys.executable, "-c", _CHILD_GUARDS + "\n" + body)


def _worker_command(extra=""):
    return _command(
        extra
        + "\nfrom backend.app.services.detect_v2_health import _worker_main\n"
        + "raise SystemExit(_worker_main())\n"
    )


@contextmanager
def _owned_runner(command):
    processes = []
    overlap = []

    def spawn(*args, **kwargs):
        overlap.append(any(process.poll() is None for process in processes))
        process = subprocess.Popen(*args, **kwargs)
        processes.append(process)
        return process

    def observe(process):
        # Reading returncode does not poll/reap. Keep these values immutable
        # before any later fixture close, poll, kill, wait, or channel close.
        stdin_closed = process.stdin is None or process.stdin.closed
        stdout_closed = process.stdout is None or process.stdout.closed
        return {
            "returncode": process.returncode,
            "exit_observed": process.returncode is not None,
            "stdin_closed": stdin_closed,
            "stdout_closed": stdout_closed,
            "channels_closed": stdin_closed and stdout_closed,
        }

    runner = health._SubprocessRefreshRunner(command, popen_factory=spawn)
    try:
        yield runner, processes, overlap
    finally:
        before_close = [observe(process) for process in processes]
        runner_cleanup = runner.close()
        after_close = [observe(process) for process in processes]
        records = []
        for process, before, after in zip(processes, before_close, after_close):
            actions = {
                "polled": False, "kill_sent": False, "waited": False,
                "closed_stream_count": 0,
            }
            if process.returncode is None:
                actions["polled"] = True
                if process.poll() is None:
                    process.kill()
                    actions["kill_sent"] = True
                    process.wait(timeout=1)
                    actions["waited"] = True
            for stream in (process.stdin, process.stdout):
                if stream is not None and not stream.closed:
                    stream.close()
                    actions["closed_stream_count"] += 1
            final = observe(process)
            records.append({
                "pid": process.pid, "returncode": final["returncode"],
                "before_fixture_runner_close": before,
                "after_fixture_runner_close": after,
                "after_fixture_fallback": final,
                "fixture_fallback_actions": actions,
                "cleanup_owner": (
                    "health_runner_before_fixture"
                    if before["exit_observed"] and before["channels_closed"]
                    else "fixture_runner_close"
                    if after["exit_observed"] and after["channels_closed"]
                    else "fixture_fallback"
                ),
            })
        print("HEALTH_CHILD_RESOURCES " + json.dumps({
            "runner_cleanup_confirmed": runner_cleanup,
            "overlapping_children_at_spawn": overlap,
            "children": records,
            "pre_close_scope": "after test body cleanup, before fixture runner.close; no observation poll",
            "guard_scope": "after SQLAlchemy import, before worker/candidate import",
        }, sort_keys=True))
        assert all(process.returncode is not None for process in processes)
        assert all(
            stream is None or stream.closed
            for process in processes for stream in (process.stdin, process.stdout)
        )
        assert not any(overlap)


_LINUX = pytest.mark.skipif(sys.platform != "linux", reason="owned selector-pipe regression is Linux-specific")


@_LINUX
def test_production_child_candidate_and_manager_return_ready_then_close_owned_resources():
    settings = _settings()
    with _owned_runner(_worker_command()) as (runner, processes, overlap):
        manager = health.DetectV2HealthSnapshots(
            health.DetectV2HealthPolicy(60, 60, 5), runner=runner,
        )
        try:
            manager.start(settings)
            assert manager.snapshot(settings) == _ready(health._SettingsProjection.capture(settings))
            assert len(processes) == 1
            assert processes[0].returncode == 0
            assert runner.last_cleanup["complete"] is True
            assert runner.last_exchange["eof"] is True
        finally:
            assert manager.close()


@_LINUX
def test_actual_fifo_timeout_is_reaped_before_same_manager_next_refresh(tmp_path):
    fifo = tmp_path / "owned-refresh.json"
    os.mkfifo(fifo)
    attempts = []
    generation = [0]
    recovered = Event()
    marker = fifo.with_name(fifo.name + ".entered")
    blocked = _worker_command(r"""
import signal
signal.signal(signal.SIGTERM, signal.SIG_IGN)
from backend.app.services import detect_v2
def blocked_candidate(projection):
    path = projection.detect_v2_runtime_config_path
    path.with_name(path.name + ".entered").write_text("entered", encoding="ascii")
    with path.open("rb") as stream:
        stream.read(1)
    raise AssertionError("owned FIFO unexpectedly had a writer")
detect_v2.compute_detect_v2_health_candidate = blocked_candidate
""")
    healthy = _worker_command()

    def choose_command():
        generation[0] += 1
        if generation[0] == 1:
            return blocked
        assert recovered.wait(3), "owned FIFO recovery was not released"
        return healthy

    settings = _settings(detect_v2_runtime_config_path=fifo)
    with _owned_runner(choose_command) as (runner, processes, overlap):
        original_call = runner.__class__.__call__

        class ObservedRunner(health._SubprocessRefreshRunner):
            def __call__(self, projection, timeout, stop):
                try:
                    result = original_call(self, projection, timeout, stop)
                except health._RefreshProblem as exc:
                    attempts.append(exc.reason)
                    raise
                else:
                    attempts.append(result["status"])
                    return result

        runner.__class__ = ObservedRunner
        manager = health.DetectV2HealthSnapshots(
            health.DetectV2HealthPolicy(0.05, 5, 2), runner=runner,
        )
        try:
            manager.start(settings)
            _wait(lambda: processes and processes[0].poll() is not None)
            assert marker.is_file(), "child must reach the real FIFO open before timeout"
            assert processes[0].returncode == -signal.SIGKILL
            from model.two_model_runtime import DEFAULT_RUNTIME_CONFIG
            fifo.unlink()
            fifo.write_text(json.dumps(DEFAULT_RUNTIME_CONFIG), encoding="utf-8")
            recovered.set()
            _wait(lambda: len(attempts) >= 2 and manager.snapshot(settings)["status"] == "ready")
            assert attempts[0] == "refresh_timeout"
            assert attempts[1] == "ready"
            assert len(processes) >= 2
            assert processes[0].returncode is not None and processes[0].returncode != 0
            assert processes[1].returncode == 0
            assert not any(overlap)
        finally:
            recovered.set()
            assert manager.close()
    fifo.unlink()


_PROTOCOL_PREAMBLE = r"""
import json, os, sys
def exact(count):
    data = bytearray()
    while len(data) < count:
        chunk = os.read(0, count - len(data))
        if not chunk:
            raise SystemExit(3)
        data.extend(chunk)
    return bytes(data)
size = int.from_bytes(exact(8), "big")
request = json.loads(exact(size))
"""


@_LINUX
@pytest.mark.parametrize("body,reason,minimum_bytes", [
    ("os.write(1, b'\\x00\\x00\\x00'); r,w=os.pipe(); os.read(r,1)", "refresh_timeout", 3),
    ("os.write(1, (20).to_bytes(8,'big') + b'{}'); r,w=os.pipe(); os.read(r,1)", "refresh_timeout", 10),
    ("os.write(1, (65537).to_bytes(8,'big'))", "refresh_failed", 8),
    ("os.write(1, (3).to_bytes(8,'big') + b'bad')", "refresh_failed", 11),
    ("os.write(1, (2).to_bytes(8,'big') + b'{}')", "refresh_failed", 10),
])
def test_actual_partial_oversize_and_invalid_frames_fail_closed(body, reason, minimum_bytes):
    projection = health._SettingsProjection.capture(_settings())
    with _owned_runner(_command(_PROTOCOL_PREAMBLE + "\n" + body)) as (runner, processes, overlap):
        with pytest.raises(health._RefreshProblem) as raised:
            runner(projection, 2.0, Event())
        assert raised.value.reason == reason
        assert runner.last_exchange["received_bytes"] >= minimum_bytes
        assert processes[0].returncode is not None
        assert processes[0].stdin.closed and processes[0].stdout.closed
        assert runner.last_cleanup["exit_confirmed"] is True
        if reason == "refresh_timeout":
            assert processes[0].returncode != 0


@_LINUX
def test_valid_frame_with_nonzero_exit_is_not_accepted():
    body = _PROTOCOL_PREAMBLE + r"""
from backend.app.services.detect_v2_health import _SettingsProjection, _unavailable, _frame
projection = _SettingsProjection.from_wire(request["settings"])
result = _unavailable(projection, "not_checked")
result.update(status="ready", reason=None)
os.write(1, _frame(result))
raise SystemExit(7)
"""
    projection = health._SettingsProjection.capture(_settings())
    with _owned_runner(_command(body)) as (runner, processes, overlap):
        with pytest.raises(health._RefreshProblem):
            runner(projection, 3.0, Event())
        assert processes[0].returncode == 7


@_LINUX
def test_file_change_during_child_candidate_discards_success(tmp_path):
    config = tmp_path / "runtime.json"
    config.write_text("before", encoding="utf-8")
    extra = r"""
from backend.app.services import detect_v2
from backend.app.services.detect_v2_health import _unavailable
def changing_candidate(projection):
    projection.detect_v2_runtime_config_path.write_text("different-after", encoding="utf-8")
    result = _unavailable(projection, "not_checked")
    result.update(status="ready", reason=None)
    return result
detect_v2.compute_detect_v2_health_candidate = changing_candidate
"""
    settings = _settings(detect_v2_runtime_config_path=config)
    with _owned_runner(_worker_command(extra)) as (runner, processes, overlap):
        manager = health.DetectV2HealthSnapshots(
            health.DetectV2HealthPolicy(60, 60, 5), runner=runner,
        )
        try:
            manager.start(settings)
            assert manager.snapshot(settings)["reason"] == "detect_v2_health_artifacts_changed"
            assert processes[0].returncode == 0
        finally:
            assert manager.close()


@_LINUX
def test_close_interrupts_owned_child_and_does_not_target_other_processes():
    blocking = _command(_PROTOCOL_PREAMBLE + "\nr,w=os.pipe(); os.read(r,1)\n")
    settings = _settings()
    with _owned_runner(blocking) as (runner, processes, overlap):
        manager = health.DetectV2HealthSnapshots(
            health.DetectV2HealthPolicy(60, 60, 5), runner=runner,
        )
        starter = Thread(target=manager.start, args=(settings,))
        starter.start()
        try:
            _wait(lambda: bool(processes) and runner.last_exchange.get("sent_bytes", 0) > 0)
            assert manager.close()
            starter.join(2)
            assert not starter.is_alive()
            assert len(processes) == 1
            assert processes[0].returncode is not None and processes[0].returncode != 0
            assert runner.last_cleanup["complete"] is True
        finally:
            assert manager.close()
            starter.join(2)
            assert not starter.is_alive()


def test_quarantined_exit_prevents_replacement_and_retains_owned_reference():
    class UnreapableProcess:
        pid = 12345
        returncode = None

        def poll(self):
            return None

        def terminate(self):
            pass

        def kill(self):
            pass

        def wait(self, timeout):
            raise subprocess.TimeoutExpired("owned fake process", timeout)

    process = UnreapableProcess()
    runner = health._SubprocessRefreshRunner()
    runner._process = process
    assert runner.close() is False
    assert runner.quarantined is True
    assert runner._process is process
    with pytest.raises(health._RefreshProblem):
        runner(health._SettingsProjection.capture(_settings()), 1, Event())
    assert runner._process is process


def test_parent_candidate_is_lazy_and_does_not_read_or_publish_provider_cache(tmp_path, monkeypatch):
    from backend.app.services import detect_v2
    from model.two_model_runtime import DEFAULT_RUNTIME_CONFIG

    model = tmp_path / "unified.pt"
    model.write_bytes(b"owned-health-artifact")
    config = tmp_path / "runtime.json"
    config.write_text(json.dumps(DEFAULT_RUNTIME_CONFIG), encoding="utf-8")
    settings = _settings(
        detect_v2_mode="real",
        detect_v2_unified_model_path=model,
        detect_v2_runtime_config_path=config,
    )
    before = (
        detect_v2._PROVIDER_CACHE_STAT_KEY, detect_v2._PROVIDER_CACHE_KEY,
        detect_v2._PROVIDER_CACHE,
    )

    def forbidden(*args, **kwargs):
        raise AssertionError("health candidate must not load a model or use provider cache")

    monkeypatch.setattr(detect_v2, "_cached_yolo_provider", forbidden)
    monkeypatch.setattr(detect_v2, "get_detect_v2_provider_state", forbidden)
    monkeypatch.setattr(detect_v2, "load_yolo_model", forbidden)
    candidate = detect_v2.compute_detect_v2_health_candidate(
        health._SettingsProjection.capture(settings)
    )
    assert candidate["status"] == "ready"
    assert candidate["configured_runtime"] == "unified_walksafe"
    unchanged = (
        detect_v2._PROVIDER_CACHE_STAT_KEY is before[0]
        and detect_v2._PROVIDER_CACHE_KEY is before[1]
        and detect_v2._PROVIDER_CACHE is before[2]
    )
    assert unchanged


def test_request_frame_limit_rejects_before_spawning_any_child():
    calls = []

    def forbidden_spawn(*args, **kwargs):
        calls.append(1)
        raise AssertionError("oversize projection must not start a child")

    projection = health._SettingsProjection.capture(_settings(
        detect_v2_runtime_config_path=Path("x" * health.MAX_HEALTH_FRAME_BYTES),
    ))
    runner = health._SubprocessRefreshRunner(popen_factory=forbidden_spawn)
    with pytest.raises(ValueError, match="health frame exceeds the protocol limit"):
        runner(projection, 1, Event())
    assert calls == []
    assert runner.close()


def test_selector_cleanup_failure_retains_reference_but_closes_both_endpoints():
    class Resource:
        def __init__(self, fail=False):
            self.fail = fail
            self.closed = False

        def close(self):
            if self.fail:
                raise OSError("owned cleanup failure")
            self.closed = True

    selector = Resource(fail=True)
    input_channel, output_channel = Resource(), Resource()
    runner = health._SubprocessRefreshRunner()
    runner._selector = selector
    runner._input = input_channel
    runner._output = output_channel
    try:
        assert runner.close() is False
        assert runner._selector is selector
        assert runner.quarantined is True
        assert input_channel.closed and output_channel.closed
        with pytest.raises(health._RefreshProblem):
            runner(health._SettingsProjection.capture(_settings()), 1, Event())
    finally:
        selector.fail = False
        assert runner.close()
    assert runner._selector is None


class _LockEntryGate:
    """Delay one selected thread before acquiring the manager's real Lock."""

    def __init__(self, lock):
        self._lock = lock
        self.target = None
        self.entered = Event()
        self.release = Event()
        self.released_in_time = None
        self._used = False

    def __enter__(self):
        if current_thread() is self.target and not self._used:
            self._used = True
            self.entered.set()
            self.released_in_time = self.release.wait(3)
            if not self.released_in_time:
                raise TimeoutError("owned lock-entry observation gate was not released")
        self._lock.acquire()
        return self

    def __exit__(self, *exc):
        self._lock.release()


def _observed_call_thread(call):
    values, errors = [], []
    finished = Event()

    def invoke():
        try:
            values.append(call())
        except BaseException as exc:
            errors.append(type(exc).__name__)
        finally:
            finished.set()

    return Thread(target=invoke), values, errors, finished


@pytest.mark.parametrize("coordinator_phase", ["new_attempt", "new_publication"])
def test_snapshot_lock_entry_samples_time_with_current_state(coordinator_phase):
    now = [0.0]
    settings = _settings()
    second_entered, second_release = Event(), Event()
    released, seen = [], []

    def run(projection, timeout, stop):
        seen.append(projection.detect_v2_image_size)
        if len(seen) == 2:
            second_entered.set()
            released.append(second_release.wait(3))
        return _ready(projection)

    manager = health.DetectV2HealthSnapshots(
        health.DetectV2HealthPolicy(60, 60, 4), runner=run, clock=lambda: now[0],
    )
    gate = _LockEntryGate(manager._lock)
    manager._lock = gate
    reader, values, errors, finished = _observed_call_thread(
        lambda: manager.snapshot(settings),
    )

    def second_published():
        with manager._lock:
            return manager._active_attempt == 0 and manager._published_started == 10.0

    try:
        manager.start(settings)
        assert manager.snapshot(settings)["status"] == "ready"
        gate.target = reader
        reader.start()
        assert gate.entered.wait(2)
        now[0] = 10.0
        manager._wake.set()
        assert second_entered.wait(2)
        if coordinator_phase == "new_publication":
            second_release.set()
            _wait(second_published)
        gate.release.set()
        assert finished.wait(2)
        reader.join(2)
        assert not reader.is_alive()
        assert errors == []
        assert gate.released_in_time is True
        assert values[0]["status"] == "ready"
        with manager._lock:
            assert manager._reject_through_attempt == 0
        now[0] = 11.0
        second_release.set()
        _wait(second_published)
        assert released == [True]
        assert seen == [768, 768]
        assert manager.snapshot(settings)["status"] == "ready"
    finally:
        gate.release.set()
        second_release.set()
        if reader.ident is not None:
            reader.join(4)
        assert not reader.is_alive()
        assert manager.close()


def test_snapshot_lock_entry_captures_coordinator_adopted_same_object_projection():
    now = [0.0]
    settings = _settings()
    seen = []

    def run(projection, timeout, stop):
        seen.append(projection.detect_v2_image_size)
        return _ready(projection)

    manager = health.DetectV2HealthSnapshots(
        health.DetectV2HealthPolicy(60, 60, 4), runner=run, clock=lambda: now[0],
    )
    gate = _LockEntryGate(manager._lock)
    manager._lock = gate
    reader, values, errors, finished = _observed_call_thread(
        lambda: manager.snapshot(settings),
    )

    def changed_published():
        with manager._lock:
            return (
                manager._projection.detect_v2_image_size == 640
                and manager._published is not None
                and manager._published["image_size"] == 640
                and manager._active_attempt == 0
            )

    try:
        manager.start(settings)
        gate.target = reader
        reader.start()
        assert gate.entered.wait(2)
        settings.detect_v2_image_size = 640
        now[0] = 10.0
        manager._wake.set()
        _wait(changed_published)
        with manager._lock:
            generation = manager._generation
        gate.release.set()
        assert finished.wait(2)
        reader.join(2)
        assert errors == []
        assert gate.released_in_time is True
        assert values == [_ready(health._SettingsProjection.capture(settings))]
        with manager._lock:
            assert manager._generation == generation
            assert manager._projection.detect_v2_image_size == 640
        assert seen == [768, 640]
    finally:
        gate.release.set()
        if reader.ident is not None:
            reader.join(4)
        assert not reader.is_alive()
        assert manager.close()


def test_start_lock_entry_does_not_reapply_an_older_projection():
    now = [0.0]
    settings = _settings()
    seen = []

    def run(projection, timeout, stop):
        seen.append(projection.detect_v2_image_size)
        return _ready(projection)

    manager = health.DetectV2HealthSnapshots(
        health.DetectV2HealthPolicy(60, 60, 4), runner=run, clock=lambda: now[0],
    )
    gate = _LockEntryGate(manager._lock)
    manager._lock = gate
    starter, values, errors, finished = _observed_call_thread(
        lambda: manager.start(settings),
    )

    def changed_published():
        with manager._lock:
            return (
                manager._published is not None
                and manager._published["image_size"] == 640
                and manager._active_attempt == 0
            )

    try:
        manager.start(settings)
        gate.target = starter
        starter.start()
        assert gate.entered.wait(2)
        settings.detect_v2_image_size = 640
        now[0] = 10.0
        manager._wake.set()
        _wait(changed_published)
        with manager._lock:
            generation = manager._generation
        gate.release.set()
        assert finished.wait(2)
        starter.join(2)
        assert errors == []
        assert values == [None]
        assert gate.released_in_time is True
        with manager._lock:
            assert manager._generation == generation
            assert manager._projection.detect_v2_image_size == 640
        assert manager.snapshot(settings)["image_size"] == 640
        assert manager.snapshot(settings)["status"] == "ready"
        assert seen[0] == 768 and all(size == 640 for size in seen[1:])
    finally:
        gate.release.set()
        if starter.ident is not None:
            starter.join(4)
        assert not starter.is_alive()
        assert manager.close()


@pytest.mark.parametrize("same_projection", [False, True])
def test_replacement_start_wait_belongs_to_current_projection(same_projection):
    settings_a = _settings()
    settings_b = _settings(detect_v2_image_size=768 if same_projection else 640)
    expected_b = health._SettingsProjection.capture(settings_b)
    first_entered, first_release = Event(), Event()
    second_entered, second_release = Event(), Event()
    seen, released = [], []
    activity = {"active": 0, "peak": 0}
    observation_lock = Lock()

    def run(projection, timeout, stop):
        with observation_lock:
            seen.append(projection.detect_v2_image_size)
            number = len(seen)
            activity["active"] += 1
            activity["peak"] = max(activity["peak"], activity["active"])
        try:
            if number == 1:
                first_entered.set()
                released.append(first_release.wait(3))
            elif number == 2:
                second_entered.set()
                released.append(second_release.wait(3))
            return _ready(projection)
        finally:
            with observation_lock:
                activity["active"] -= 1

    manager = health.DetectV2HealthSnapshots(
        health.DetectV2HealthPolicy(60, 60, 4), runner=run,
        clock=lambda: 100.0,
    )
    starter_a, values_a, errors_a, finished_a = _observed_call_thread(
        lambda: manager.start(settings_a),
    )
    starter_b, values_b, errors_b, finished_b = _observed_call_thread(
        lambda: manager.start(settings_b),
    )

    def b_is_owner():
        with manager._lock:
            return manager._settings is settings_b and manager._projection == expected_b

    def b_refresh_completed():
        with manager._lock:
            return (
                manager._attempt >= 2 and manager._active_attempt == 0
                and manager._published is not None
                and manager._published["image_size"] == expected_b.detect_v2_image_size
            )

    try:
        starter_a.start()
        assert first_entered.wait(2)
        with manager._lock:
            initial_a = manager._initial
        starter_b.start()
        _wait(b_is_owner)
        with manager._lock:
            initial_b = manager._initial
            generation_b = manager._generation
            assert not initial_a.is_set()
            assert not initial_b.is_set()
            assert (initial_a is initial_b) is same_projection
        # A is no longer the owner, even if B has an equivalent projection.
        settings_a.detect_v2_image_size = 1024
        first_release.set()
        assert second_entered.wait(2)
        assert finished_a.wait(2)
        assert released == [True]
        with manager._lock:
            assert manager._settings is settings_b
            assert manager._projection == expected_b
            assert manager._generation == generation_b
            assert manager._initial is initial_b
            assert initial_a.is_set()
            assert initial_b.is_set() is same_projection
        if same_projection:
            assert finished_b.wait(2)
            assert manager.snapshot(settings_b) == _ready(expected_b)
        else:
            assert not finished_b.is_set()
            assert manager.snapshot(settings_b)["status"] == "unavailable"
        second_release.set()
        _wait(b_refresh_completed)
        assert finished_b.wait(2)
        starter_a.join(2)
        starter_b.join(2)
        assert errors_a == errors_b == []
        assert values_a == values_b == [None]
        assert released == [True, True]
        assert seen == [768, expected_b.detect_v2_image_size]
        assert activity == {"active": 0, "peak": 1}
        assert manager.snapshot(settings_b) == _ready(expected_b)
    finally:
        first_release.set()
        second_release.set()
        for starter in (starter_a, starter_b):
            if starter.ident is not None:
                starter.join(4)
            assert not starter.is_alive()
        assert manager.close()


@pytest.mark.parametrize("cleanup_phase", ["runner_close", "fallback"])
def test_owned_runner_receipt_keeps_pre_close_observations(monkeypatch, capsys, cleanup_phase):
    events = []

    class Channel:
        def __init__(self, name):
            self.name = name
            self.closed = False

        def close(self):
            events.append(self.name + ".close")
            self.closed = True

    class Process:
        pid = 12345
        returncode = None

        def __init__(self):
            self.stdin = Channel("stdin")
            self.stdout = Channel("stdout")

        def poll(self):
            events.append("poll")
            return self.returncode

        def kill(self):
            events.append("kill")

        def wait(self, timeout):
            events.append("wait")
            self.returncode = -9
            return self.returncode

    process = Process()

    class Runner:
        def __init__(self, command, *, popen_factory):
            self.process = popen_factory()

        def close(self):
            events.append("runner.close")
            if cleanup_phase == "fallback":
                return False
            self.process.returncode = 0
            self.process.stdin.close()
            self.process.stdout.close()
            return True

    monkeypatch.setattr(subprocess, "Popen", lambda *args, **kwargs: process)
    monkeypatch.setattr(health, "_SubprocessRefreshRunner", Runner)
    with _owned_runner(("owned-observation-only",)) as (runner, processes, overlap):
        assert processes == [process]
        assert overlap == [False]
        assert process.returncode is None
        assert not process.stdin.closed and not process.stdout.closed
        assert events == []
    lines = [
        line for line in capsys.readouterr().out.splitlines()
        if line.startswith("HEALTH_CHILD_RESOURCES ")
    ]
    assert len(lines) == 1
    receipt = json.loads(lines[0].split(" ", 1)[1])
    record = receipt["children"][0]
    before = record["before_fixture_runner_close"]
    after = record["after_fixture_runner_close"]
    final = record["after_fixture_fallback"]
    assert before["returncode"] is None
    assert before["exit_observed"] is False
    assert before["stdin_closed"] is before["stdout_closed"] is False
    assert final["exit_observed"] is True
    assert final["channels_closed"] is True
    assert events[0] == "runner.close"
    if cleanup_phase == "runner_close":
        assert after["returncode"] == 0 and after["channels_closed"] is True
        assert record["cleanup_owner"] == "fixture_runner_close"
        assert events == ["runner.close", "stdin.close", "stdout.close"]
        assert record["fixture_fallback_actions"] == {
            "polled": False, "kill_sent": False, "waited": False,
            "closed_stream_count": 0,
        }
    else:
        assert after["returncode"] is None and after["channels_closed"] is False
        assert record["cleanup_owner"] == "fixture_fallback"
        assert events == ["runner.close", "poll", "kill", "wait", "stdin.close", "stdout.close"]
        assert record["fixture_fallback_actions"] == {
            "polled": True, "kill_sent": True, "waited": True,
            "closed_stream_count": 2,
        }
