from __future__ import annotations

import asyncio
import threading

import pytest

from backend.app.services import health_readiness as health
from backend.app.api import health as health_router
from backend.app.config import Settings


def test_inventory_cache_is_opt_in_and_invalid_durations_are_rejected(monkeypatch):
    monkeypatch.delenv("WALKSAFE_READINESS_INVENTORY_CACHE_SECONDS", raising=False)
    assert Settings().readiness_inventory_cache_seconds == 0
    monkeypatch.setenv("WALKSAFE_READINESS_INVENTORY_CACHE_SECONDS", "2.5")
    assert Settings().readiness_inventory_cache_seconds == 2.5


@pytest.mark.parametrize("value", ["-1", "nan", "inf", "-inf", "invalid"])
def test_inventory_cache_rejects_invalid_config(monkeypatch, value):
    monkeypatch.setenv("WALKSAFE_READINESS_INVENTORY_CACHE_SECONDS", value)
    with pytest.raises(ValueError):
        Settings()


def test_success_snapshot_expires_and_caller_cannot_mutate_cached_result():
    now = [100.0]
    calls = []

    def check():
        calls.append(1)
        return {"ready": True, "inventory": {"count": len(calls)}}

    probe = health._OffloadedReadinessProbe(check, cache_seconds=2, clock=lambda: now[0])

    async def exercise():
        first = await probe.check()
        first["inventory"]["count"] = 999
        assert (await probe.check())["inventory"]["count"] == 1
        now[0] = 102.0
        assert (await probe.check())["inventory"]["count"] == 2

    asyncio.run(exercise())
    assert len(calls) == 2


@pytest.mark.parametrize("raise_error", [False, True])
def test_failed_checks_are_never_cached(raise_error):
    calls = []

    def check():
        calls.append(1)
        if raise_error:
            raise RuntimeError("test probe failure")
        return {"ready": False, "reason": "test_failure"}

    probe = health._OffloadedReadinessProbe(check, cache_seconds=60)
    assert asyncio.run(probe.check())["ready"] is False
    assert asyncio.run(probe.check())["ready"] is False
    assert len(calls) == 2


def test_default_zero_cache_preserves_live_checks():
    calls = []
    probe = health._OffloadedReadinessProbe(
        lambda: calls.append(1) or {"ready": True}
    )
    asyncio.run(probe.check())
    asyncio.run(probe.check())
    assert len(calls) == 2


def test_timeout_keeps_one_worker_and_does_not_cache_late_success(monkeypatch):
    monkeypatch.setattr(health, "READINESS_LOCAL_CHECK_TIMEOUT_SECONDS", 0.02)
    release = threading.Event()
    completed = threading.Event()
    now = [100.0]
    calls = []

    def check():
        calls.append(1)
        try:
            assert release.wait(1)
            now[0] += 1
            return {"ready": True}
        finally:
            completed.set()

    probe = health._OffloadedReadinessProbe(check, cache_seconds=60, clock=lambda: now[0])

    async def exercise():
        first, second = await asyncio.gather(probe.check(), probe.check())
        assert first == second == {"ready": False, "reason": "check_timeout"}
        assert len(calls) == 1
        release.set()
        assert await asyncio.to_thread(completed.wait, 1)
        await asyncio.sleep(0.01)
        assert (await probe.check())["ready"] is True
        assert len(calls) == 2

    try:
        asyncio.run(exercise())
    finally:
        release.set()


@pytest.mark.parametrize("cancel_caller", [False, True])
def test_repeated_timeouts_and_caller_cancel_preserve_pending_worker(
    monkeypatch, cancel_caller,
):
    import json

    monkeypatch.setattr(health, "READINESS_LOCAL_CHECK_TIMEOUT_SECONDS", 0.02)
    release = threading.Event()
    now = [100.0]
    workers = []
    prior_worker_alive_at_creation = []
    callbacks = []
    activity = {"current": 0, "peak": 0}
    activity_lock = threading.Lock()
    real_thread = health.Thread

    def record_thread(*args, **kwargs):
        prior_worker_alive_at_creation.append(any(worker.is_alive() for worker in workers))
        worker = real_thread(*args, **kwargs)
        workers.append(worker)
        return worker

    monkeypatch.setattr(health, "Thread", record_thread)

    def check():
        with activity_lock:
            callbacks.append(threading.current_thread())
            generation = len(callbacks)
            activity["current"] += 1
            activity["peak"] = max(activity["peak"], activity["current"])
        try:
            if generation == 1:
                # Only the test's finally block releases this observation gate.
                # This is not cancellation or recovery of real blocked I/O.
                release.wait()
                now[0] += 1
            return {"ready": True, "generation": generation}
        finally:
            with activity_lock:
                activity["current"] -= 1

    probe = health._OffloadedReadinessProbe(check, cache_seconds=60, clock=lambda: now[0])
    original_future = None
    timeout_results = []

    def assert_original_worker_is_pending():
        assert probe._inflight is original_future
        assert original_future is not None
        assert not original_future.done()
        assert not original_future.cancelled()
        assert len(workers) == len(callbacks) == 1
        assert callbacks[0] is workers[0]
        assert workers[0].is_alive()
        assert activity == {"current": 1, "peak": 1}
        assert not release.is_set()

    def finish_owned_workers(phase):
        release.set()
        for worker in workers:
            if worker.ident is not None:
                worker.join(1)
            assert not worker.is_alive(), "the owned observation worker must exit"
        assert activity["current"] == 0
        print(json.dumps({
            "probe": "S20-48-bounded-observation",
            "phase": phase,
            "cancel_caller": cancel_caller,
            "gate_released": True,
            "owned_worker_count": len(workers),
            "owned_workers_terminated": True,
        }, sort_keys=True))

    async def observe_pending_worker():
        nonlocal original_future
        timeout_results.extend(await asyncio.gather(probe.check(), probe.check()))
        original_future = probe._inflight
        assert_original_worker_is_pending()

        for _ in range(2):
            timeout_results.append(await probe.check())
            assert_original_worker_is_pending()

        if cancel_caller:
            caller = asyncio.create_task(probe.check())
            await asyncio.sleep(0)
            assert not caller.done()
            assert_original_worker_is_pending()
            caller.cancel()
            with pytest.raises(asyncio.CancelledError):
                await caller
            assert caller.cancelled()
            assert_original_worker_is_pending()

        timeout_results.append(await probe.check())
        assert_original_worker_is_pending()
        assert timeout_results == [{"ready": False, "reason": "check_timeout"}] * 5

    try:
        asyncio.run(observe_pending_worker())
    finally:
        finish_owned_workers("pending-worker-cleanup")

    assert original_future is not None
    assert original_future.done() and not original_future.cancelled()
    assert original_future.result() == {"ready": True, "generation": 1}
    assert probe._inflight is original_future
    assert probe._cache_until == 0.0
    assert len(callbacks) == 1

    try:
        fresh = asyncio.run(probe.check())
        assert fresh == {"ready": True, "generation": 2}
        assert probe._inflight is not original_future
        assert len(workers) == len(callbacks) == 2
        assert callbacks[1] is workers[1]
        assert workers[1] is not workers[0]
        assert prior_worker_alive_at_creation == [False, False]
        assert activity["peak"] == 1

        # A timely new generation may be cached; the late old one was not.
        assert asyncio.run(probe.check()) == fresh
        assert len(workers) == len(callbacks) == 2
    finally:
        finish_owned_workers("fresh-worker-cleanup")

    print(json.dumps({
        "probe": "S20-48-bounded-observation",
        "cancel_caller": cancel_caller,
        "timeout_response_count": len(timeout_results),
        "same_pending_future_observed": True,
        "pending_callback_count": 1,
        "peak_active_callbacks": activity["peak"],
        "late_success_cached": False,
        "fresh_generation": fresh["generation"],
        "fresh_success_cache_reused": True,
        "new_worker_started_after_old_thread_exit": True,
        "real_io_cancellation_or_recovery_proven": False,
    }, sort_keys=True))


def test_s20_48_stuck_probe_is_reported_without_new_threads_and_next_generation_recovers(
    monkeypatch,
):
    monkeypatch.setattr(health, "READINESS_LOCAL_CHECK_TIMEOUT_SECONDS", 0.02)
    release = threading.Event()
    now = [100.0]
    workers = []
    generations = []
    real_thread = health.Thread

    def record_thread(*args, **kwargs):
        worker = real_thread(*args, **kwargs)
        workers.append(worker)
        return worker

    monkeypatch.setattr(health, "Thread", record_thread)

    def check():
        generations.append(1)
        if len(generations) == 1:
            # Stands in for a dependency call that never returns by itself.
            release.wait()
        return {"ready": True, "generation": len(generations)}

    probe = health._OffloadedReadinessProbe(check, cache_seconds=60, clock=lambda: now[0])
    stuck_after = health.READINESS_PROBE_STUCK_SECONDS

    async def observe():
        assert await probe.check() == {"ready": False, "reason": "check_timeout"}
        now[0] = 100.0 + stuck_after - 0.5
        assert await probe.check() == {"ready": False, "reason": "check_timeout"}
        now[0] = 100.0 + stuck_after + 1.25
        for _ in range(3):
            assert await probe.check() == {
                "ready": False, "reason": "probe_stuck", "stuck_seconds": int(stuck_after + 1.25),
            }
        # Stuck is still single-flight: no timeout spawned another worker.
        assert len(workers) == len(generations) == 1
        assert workers[0].is_alive()

    try:
        asyncio.run(observe())
    finally:
        release.set()
        for worker in workers:
            worker.join(1)
    assert not workers[0].is_alive()
    assert probe._stuck_seconds() is None
    # The late stuck result is not cached; the next generation checks again.
    fresh = asyncio.run(probe.check())
    assert fresh == {"ready": True, "generation": 2}
    assert len(workers) == 2
    assert asyncio.run(probe.check()) == fresh
    assert len(workers) == 2


# --- S20-48 (be-F3): the detector warm-up probe has its own stuck threshold ---


def test_s20_48_detector_stuck_threshold_follows_the_startup_timeout(monkeypatch):
    from types import SimpleNamespace

    monkeypatch.delenv("INFERENCE_STARTUP_TIMEOUT_SECONDS", raising=False)
    assert health.READINESS_PROBE_STUCK_SECONDS == 30.0
    assert health.detector_probe_stuck_seconds(Settings()) == 35.0  # default 30 + 5
    for startup, expected in ((2.0, 30.0), (25.0, 30.0), (30.0, 35.0), (120.0, 125.0)):
        settings = SimpleNamespace(inference_startup_timeout_seconds=startup)
        assert health.detector_probe_stuck_seconds(settings) == expected
    # Test doubles without the setting keep the general threshold.
    assert health.detector_probe_stuck_seconds(SimpleNamespace()) == 30.0
    for invalid in (0.0, -1.0, float("nan"), float("inf")):
        with pytest.raises(ValueError, match="stuck threshold"):
            health._OffloadedReadinessProbe(lambda: {"ready": True}, stuck_seconds=invalid)


def test_s20_48_create_router_gives_only_the_detector_probe_the_warmup_threshold(monkeypatch):
    from copy import copy

    from backend.app.config import get_settings

    settings = copy(get_settings())
    settings.inference_startup_timeout_seconds = 120.0
    thresholds: dict[str, float] = {}
    real_probe = health._OffloadedReadinessProbe

    class RecordingProbe(real_probe):
        def __init__(self, check, **kwargs):
            super().__init__(check, **kwargs)
            owner = getattr(check, "__self__", None)
            name = "detector" if isinstance(owner, health._DetectorReadinessProbe) else f"other{len(thresholds)}"
            thresholds[name] = self._stuck_threshold

    monkeypatch.setattr(health, "_OffloadedReadinessProbe", RecordingProbe)
    health_router.create_router(settings, lambda: None, object())  # type: ignore[arg-type]
    assert thresholds.pop("detector") == 125.0
    assert thresholds and set(thresholds.values()) == {health.READINESS_PROBE_STUCK_SECONDS}


def test_s20_48_slow_cold_warmup_is_not_reported_stuck_before_its_bound(monkeypatch):
    monkeypatch.setattr(health, "READINESS_LOCAL_CHECK_TIMEOUT_SECONDS", 0.02)
    release = threading.Event()
    now = [100.0]

    def slow_warmup():
        release.wait()
        return {"ready": True, "inference_warmed": True}

    probe = health._OffloadedReadinessProbe(slow_warmup, stuck_seconds=125.0, clock=lambda: now[0])

    async def observe():
        assert await probe.check() == {"ready": False, "reason": "check_timeout"}
        now[0] = 100.0 + 100.0  # a legitimate 100 s cold warm-up (startup timeout 120 s)
        assert await probe.check() == {"ready": False, "reason": "check_timeout"}
        now[0] = 100.0 + 126.0
        assert await probe.check() == {"ready": False, "reason": "probe_stuck", "stuck_seconds": 126}

    try:
        asyncio.run(observe())
    finally:
        release.set()
