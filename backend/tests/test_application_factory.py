"""Application ownership exercised without operator files, providers or databases."""
from __future__ import annotations

import asyncio
from copy import copy
from types import SimpleNamespace

import pytest

from backend.app import application_runtime, database
from backend.app.config import get_settings
from backend.app.main import create_app
from backend.app.services import health_readiness
from backend.app.services.privacy_lifecycle import configured_privacy_hmac_lookup_keys


def settings_for(tmp_path):
    settings = copy(get_settings())
    settings.upload_dir = tmp_path / "uploads"
    settings.raw_object_dir = None
    settings.capacity_measurement_interval_seconds = None
    settings.privacy_hmac_lookup_keys = {}
    return settings


class OwnedDatabase:
    def __init__(self, name):
        self.session_factory = lambda: name
        self.closed = 0

    def close(self):
        self.closed += 1


def test_factory_does_not_acquire_files_keys_or_engine_and_owns_distinct_state(tmp_path, monkeypatch):
    def forbidden(*_args, **_kwargs):
        raise AssertionError("factory construction must not acquire resources")

    monkeypatch.setattr(database, "create_engine", forbidden)
    monkeypatch.setattr(application_runtime, "create_report_image_key_manager", forbidden)
    from backend.app.services import actor_rate_limit
    monkeypatch.setattr(actor_rate_limit, "ActorRateLimitExecution", forbidden)
    first_settings = settings_for(tmp_path / "one")
    second_settings = settings_for(tmp_path / "two")
    first, second = create_app(first_settings), create_app(second_settings)
    for name in ("database", "capacity_state", "report_storage_state", "detector_health", "preprocessing"):
        assert getattr(first.state.runtime, name) is not getattr(second.state.runtime, name)
    assert first.state.runtime.settings is first_settings
    assert second.state.runtime.settings is second_settings
    assert not first_settings.upload_dir.exists()
    assert not second_settings.upload_dir.exists()
    assert first.state.runtime.report_image_key_manager is None
    assert second.state.runtime.report_image_key_manager is None


def test_concurrent_app_context_and_readiness_thread_use_own_database_and_lookup_keys(tmp_path):
    settings_one, settings_two = settings_for(tmp_path / "one"), settings_for(tmp_path / "two")
    settings_one.privacy_hmac_lookup_keys = {2: "owned-one" * 4}
    settings_two.privacy_hmac_lookup_keys = {3: "owned-two" * 4}
    one = create_app(settings_one, database=OwnedDatabase("one")).state.runtime
    two = create_app(settings_two, database=OwnedDatabase("two")).state.runtime

    async def exercise(owner, expected, lookup):
        with owner.context():
            await asyncio.sleep(0)
            assert database.SessionLocal() == expected
            assert await asyncio.to_thread(database.SessionLocal) == expected
            probe = health_readiness._OffloadedReadinessProbe(
                lambda: {"ready": True, "database": database.SessionLocal(), "lookup": configured_privacy_hmac_lookup_keys()}
            )
            assert await probe.check() == {"ready": True, "database": expected, "lookup": lookup}
        assert configured_privacy_hmac_lookup_keys() == ()

    async def run():
        await asyncio.gather(exercise(one, "one", ((2, "owned-one" * 4),)), exercise(two, "two", ((3, "owned-two" * 4),)))

    asyncio.run(run())


def isolate_startup(owner, monkeypatch):
    for name in ("validate_admin_credential_issuer_binding", "bind_privacy_hmac_key", "bind_account_crypto_keys", "reconcile_report_storage", "reconcile_raw_collection_storage"):
        monkeypatch.setattr(owner, name, lambda: None)

    class Tmap:
        def __init__(self, **_kwargs):
            pass
        async def start(self):
            pass
        def stop_admission(self):
            pass
        async def aclose(self):
            pass

    monkeypatch.setattr(application_runtime, "TmapRuntime", Tmap)
    # Do not start detector subprocesses; still exercise the actual scoped close.
    monkeypatch.setattr(application_runtime, "start_detect_v2_health", lambda _settings: None)

    async def idle_storage_monitor(_monitor, stop):
        await stop.wait()

    monkeypatch.setattr(application_runtime.ReportStorageMonitor, "run", idle_storage_monitor)
    # The assembled router lifespans run their housekeeping too. Replace only
    # those external I/O adapters, leaving lifecycle assembly intact.
    from contextlib import nullcontext
    from backend.app import field_session_logs
    from backend.app.android_debug_storage import AndroidDebugStorage
    monkeypatch.setattr(field_session_logs, "SessionLocal", lambda: nullcontext(None))
    monkeypatch.setattr(field_session_logs, "diagnostic_schema_present", lambda _db: False)
    monkeypatch.setattr(AndroidDebugStorage, "prune_existing", lambda _storage: None)


def test_reentered_lifespan_reacquires_closed_resources_without_closing_other_app(tmp_path, monkeypatch):
    db = OwnedDatabase("one")
    app = create_app(settings_for(tmp_path / "one"), database=db)
    other = create_app(settings_for(tmp_path / "two"))
    owner = app.state.runtime
    actual_start_health = application_runtime.start_detect_v2_health
    isolate_startup(owner, monkeypatch)
    monkeypatch.setattr(application_runtime, "start_detect_v2_health", actual_start_health)
    started = []

    def owned_health_start(manager, _settings):
        assert manager is owner.detector_health
        assert not manager._closed
        started.append(manager)

    monkeypatch.setattr(application_runtime.DetectV2HealthSnapshots, "start", owned_health_start)

    async def run():
        first_health = owner.detector_health
        first_preprocessing = owner.preprocessing
        async with owner.lifespan(app):
            assert not owner.detector_health._closed
        assert first_health._closed
        assert first_preprocessing._closed
        async with owner.lifespan(app):
            assert owner.detector_health is not first_health
            assert owner.preprocessing is not first_preprocessing
            assert not owner.detector_health._closed
        assert len(started) == 2
        assert started[0] is first_health
        assert started[1] is owner.detector_health
        assert db.closed == 2
        assert not other.state.runtime.detector_health._closed
        assert not other.state.runtime.preprocessing._closed
        assert app.state.tmap_runtime is None

    asyncio.run(run())


@pytest.mark.parametrize("failure", ["initialization", "storage_stop", "capacity_task"])
def test_partial_startup_and_shutdown_failures_still_release_owned_resources(tmp_path, monkeypatch, failure):
    db = OwnedDatabase("owned")
    app = create_app(settings_for(tmp_path), database=db)
    owner = app.state.runtime
    isolate_startup(owner, monkeypatch)
    events = []
    initialize = owner.initialize_resources

    def fail():
        raise RuntimeError("owned failure")

    def initialize_with_failure():
        initialize()
        owner.inference_runner = SimpleNamespace(close=lambda: events.append("inference"))
        if failure == "initialization":
            fail()
        if failure == "storage_stop":
            monkeypatch.setattr(owner.report_storage_monitor, "stop", fail)
        if failure == "capacity_task":
            owner.capacity_monitor = SimpleNamespace(interval_seconds=0.001, measure_once=lambda: None)
            async def failed_capacity(stop):
                await stop.wait()
                fail()
            monkeypatch.setattr(owner, "run_capacity_monitor", failed_capacity)

    monkeypatch.setattr(owner, "initialize_resources", initialize_with_failure)

    async def run():
        with pytest.raises(RuntimeError, match="owned failure"):
            async with owner.lifespan(app):
                assert failure != "initialization"
        assert events == ["inference"]
        assert owner.preprocessing._closed
        assert owner.detector_health._closed
        assert db.closed == 1
        assert app.state.tmap_runtime is None

    asyncio.run(run())


def test_cancelled_initialization_waits_for_owned_worker_before_cleanup(tmp_path, monkeypatch):
    import threading

    db = OwnedDatabase("owned")
    app = create_app(settings_for(tmp_path), database=db)
    owner = app.state.runtime
    isolate_startup(owner, monkeypatch)
    entered, release = threading.Event(), threading.Event()
    events = []
    initialize = owner.initialize_resources

    def blocked_initialize():
        entered.set()
        assert release.wait(3)
        initialize()
        owner.inference_runner = SimpleNamespace(close=lambda: events.append("inference"))
        events.append("initialized")

    monkeypatch.setattr(owner, "initialize_resources", blocked_initialize)

    async def run():
        async def startup():
            async with owner.lifespan(app):
                raise AssertionError("cancelled startup must not serve")
        task = asyncio.create_task(startup())
        try:
            assert await asyncio.to_thread(entered.wait, 2)
            task.cancel()
            await asyncio.sleep(0)
            assert not task.done()
            assert db.closed == 0
        finally:
            release.set()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 3)
        assert events == ["initialized", "inference"]
        assert db.closed == 1
        assert owner.preprocessing._closed

    asyncio.run(run())


@pytest.mark.skipif(not __import__("os").environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(), reason="dedicated test database required")
def test_two_app_database_pools_close_independently(tmp_path):
    from sqlalchemy import text

    one = create_app(settings_for(tmp_path / "one")).state.runtime
    two = create_app(settings_for(tmp_path / "two")).state.runtime
    try:
        assert one.database.engine is not two.database.engine
        with one.context(), database.SessionLocal() as first:
            first_pid = first.scalar(text("SELECT pg_backend_pid()"))
            with two.context(), database.SessionLocal() as second:
                second_pid = second.scalar(text("SELECT pg_backend_pid()"))
                assert first_pid != second_pid
                assert "test" in second.scalar(text("SELECT current_database()"))
        one.database.close()
        with two.context(), database.SessionLocal() as second:
            assert second.scalar(text("SELECT 1")) == 1
    finally:
        one.database.close()
        two.database.close()


@pytest.mark.parametrize("keys", [{0: "K" * 32}, {1: "short"}])
def test_app_lookup_keys_retain_startup_validation(keys, tmp_path):
    settings = settings_for(tmp_path)
    settings.privacy_hmac_lookup_keys = keys
    app = create_app(settings)
    from backend.app.services.privacy_lifecycle import PrivacyLifecycleError
    error = ValueError if 0 in keys else PrivacyLifecycleError
    with pytest.raises(error):
        with app.state.runtime.context():
            pass


@pytest.mark.parametrize("operation", [
    "validate_admin_credential_issuer_binding", "bind_privacy_hmac_key",
    "bind_account_crypto_keys", "reconcile_report_storage",
    "reconcile_raw_collection_storage",
])
def test_cancelled_startup_step_finishes_before_database_cleanup(tmp_path, monkeypatch, operation):
    from threading import Event

    db = OwnedDatabase("owned")
    app = create_app(settings_for(tmp_path), database=db)
    owner = app.state.runtime
    isolate_startup(owner, monkeypatch)
    entered, release = Event(), Event()
    events = []

    def blocked_step():
        entered.set()
        assert release.wait(3)
        assert db.closed == 0
        events.append("worker_finished")

    monkeypatch.setattr(owner, operation, blocked_step)

    async def run():
        async def startup():
            async with app.router.lifespan_context(app):
                pytest.fail("cancelled startup must not serve")
        task = asyncio.create_task(startup())
        try:
            assert await asyncio.to_thread(entered.wait, 2)
            task.cancel()
            with pytest.raises(TimeoutError):
                await asyncio.wait_for(asyncio.shield(task), 0.05)
            assert not task.done()
            assert db.closed == 0
        finally:
            release.set()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 3)
        assert events == ["worker_finished"]
        assert db.closed == 1

    asyncio.run(run())
