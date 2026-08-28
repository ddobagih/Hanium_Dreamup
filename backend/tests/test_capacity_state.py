from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from inspect import Parameter, signature
import os
from pathlib import Path
import stat
from threading import Event
from types import SimpleNamespace

from fastapi import FastAPI
import pytest

from backend.app.api.capacity import create_router
from backend.app.config import Settings
from backend.app.field_test_security import (
    FieldTestAccess,
    FieldTestSecurityMiddleware,
    required_field_test_access,
)
import backend.app.services.capacity_state as capacity_service
from backend.app.services.capacity_state import (
    CAPACITY_ENV_NAMES,
    CAPACITY_REASON,
    MAX_JS_SAFE_INTEGER,
    CapacityLevel,
    CapacityMeasurementError,
    CapacityState,
    CapacityStateUnavailable,
    FilesystemCapacityMonitor,
    capacity_state_from_environment,
)
from asgi_client import ASGITestClient


BASE_TIME = datetime(2026, 8, 25, 0, 0, tzinfo=UTC)
LIVE_CAPACITY_ENV_NAMES = (
    "WALKSAFE_CAPACITY_MEASUREMENT_INTERVAL_SECONDS",
    "WALKSAFE_CAPACITY_STATE_TTL_SECONDS",
    "WALKSAFE_CAPACITY_VERSION_STATE_PATH",
)


def _capacity_paths(tmp_path: Path) -> tuple[Path, Path]:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    state_dir = tmp_path / "capacity"
    state_dir.mkdir(mode=0o700)
    return upload_dir, state_dir / "version"


def _filesystem_monitor(
    tmp_path: Path,
    *,
    state: CapacityState | None = None,
    clock=lambda: BASE_TIME,
    interval_seconds: float = 1,
    ttl_seconds: float = 10,
) -> tuple[FilesystemCapacityMonitor, CapacityState, Path, Path]:
    upload_dir, version_path = _capacity_paths(tmp_path)
    capacity_state = state or CapacityState(clock=clock)
    return (
        FilesystemCapacityMonitor(
            state=capacity_state,
            upload_dir=upload_dir,
            version_state_path=version_path,
            interval_seconds=interval_seconds,
            ttl_seconds=ttl_seconds,
            clock=clock,
        ),
        capacity_state,
        upload_dir,
        version_path,
    )


def test_capacity_threshold_transitions_include_boundaries_and_recovery() -> None:
    state = CapacityState()
    transitions = (
        (69, CapacityLevel.NORMAL),
        (70, CapacityLevel.ADMIN_ONLY_WARNING),
        (85, CapacityLevel.PAUSE_NEW_FIELD_TEST_PARTICIPANTS),
        (95, CapacityLevel.HOLD_NEW_RAW_COLLECTION_SESSIONS),
        (100, CapacityLevel.HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES),
        (69, CapacityLevel.NORMAL),
    )

    for version, (used_percent, expected_level) in enumerate(transitions, start=1):
        observed_at = BASE_TIME + timedelta(seconds=version)
        snapshot = state.publish(
            version=version,
            observed_at=observed_at,
            expires_at=observed_at + timedelta(minutes=5),
            used_percent=used_percent,
        )

        assert snapshot.level is expected_level
        assert snapshot.reason == CAPACITY_REASON
        assert set(snapshot.to_wire()) == {
            "version",
            "observed_at",
            "expires_at",
            "level",
            "reason",
        }
        assert not hasattr(snapshot, "used_percent")


def test_capacity_rejects_equal_lower_version_and_observed_time_rollback() -> None:
    state = CapacityState()
    accepted = state.publish(
        version=2,
        observed_at=BASE_TIME + timedelta(seconds=2),
        expires_at=BASE_TIME + timedelta(minutes=2),
        used_percent=70,
    )

    for version in (2, 1):
        with pytest.raises(ValueError, match="version"):
            state.publish(
                version=version,
                observed_at=BASE_TIME + timedelta(seconds=3),
                expires_at=BASE_TIME + timedelta(hours=1),
                used_percent=100,
            )

    with pytest.raises(ValueError, match="observed_at"):
        state.publish(
            version=3,
            observed_at=BASE_TIME + timedelta(seconds=1),
            expires_at=BASE_TIME + timedelta(hours=1),
            used_percent=100,
        )

    assert state.current(now=BASE_TIME + timedelta(seconds=30)) == accepted


def test_capacity_expiry_is_explicit_exact_and_rejection_does_not_extend_it() -> None:
    state = CapacityState()
    expires_at = BASE_TIME + timedelta(seconds=10)
    accepted = state.publish(
        version=1,
        observed_at=BASE_TIME,
        expires_at=expires_at,
        used_percent=69,
    )

    assert signature(CapacityState.publish).parameters["expires_at"].default is Parameter.empty
    assert state.current(now=expires_at - timedelta(microseconds=1)) == accepted

    with pytest.raises(ValueError, match="version"):
        state.publish(
            version=1,
            observed_at=BASE_TIME + timedelta(seconds=1),
            expires_at=BASE_TIME + timedelta(days=1),
            used_percent=69,
        )

    with pytest.raises(CapacityStateUnavailable):
        state.current(now=expires_at)
    with pytest.raises(CapacityStateUnavailable):
        state.current(now=expires_at + timedelta(seconds=1))


def test_capacity_wire_validation_and_local_synthetic_bootstrap() -> None:
    values = {
        "WALKSAFE_CAPACITY_VERSION": str(MAX_JS_SAFE_INTEGER),
        "WALKSAFE_CAPACITY_OBSERVED_AT": "2026-08-25T00:00:00Z",
        "WALKSAFE_CAPACITY_EXPIRES_AT": "2026-08-25T00:05:00Z",
        "WALKSAFE_CAPACITY_USED_PERCENT": "85",
    }
    state = capacity_state_from_environment("test", values)

    assert state.current(now=BASE_TIME).to_wire() == {
        "version": MAX_JS_SAFE_INTEGER,
        "observed_at": "2026-08-25T00:00:00Z",
        "expires_at": "2026-08-25T00:05:00Z",
        "level": "PAUSE_NEW_FIELD_TEST_PARTICIPANTS",
        "reason": "STORAGE_UTILIZATION",
    }

    with pytest.raises(ValueError, match="all be set"):
        capacity_state_from_environment(
            "development",
            {"WALKSAFE_CAPACITY_VERSION": "1"},
        )
    with pytest.raises(ValueError, match="not allowed"):
        capacity_state_from_environment("production", values)
    with pytest.raises(ValueError, match="invalid"):
        capacity_state_from_environment(
            "test",
            {**values, "WALKSAFE_CAPACITY_VERSION": str(MAX_JS_SAFE_INTEGER + 1)},
        )
    with pytest.raises(ValueError, match="invalid"):
        capacity_state_from_environment(
            "test",
            {**values, "WALKSAFE_CAPACITY_OBSERVED_AT": "2026-08-25T09:00:00+09:00"},
        )
    with pytest.raises(ValueError, match="invalid"):
        capacity_state_from_environment(
            "test",
            {**values, "WALKSAFE_CAPACITY_EXPIRES_AT": "2026-08-25T00:05:00+00:00"},
        )

    empty = capacity_state_from_environment("test", {})
    with pytest.raises(CapacityStateUnavailable):
        empty.current(now=BASE_TIME)
    assert CAPACITY_ENV_NAMES == tuple(values)


def test_live_capacity_settings_are_all_or_none_and_disabled_by_default(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    for name in LIVE_CAPACITY_ENV_NAMES:
        monkeypatch.delenv(name, raising=False)

    disabled = Settings()

    assert disabled.capacity_measurement_interval_seconds is None
    assert disabled.capacity_state_ttl_seconds is None
    assert disabled.capacity_version_state_path is None

    monkeypatch.setenv(LIVE_CAPACITY_ENV_NAMES[0], "1")
    with pytest.raises(ValueError, match="all be set or all be absent"):
        Settings()

    monkeypatch.setenv(LIVE_CAPACITY_ENV_NAMES[1], "2")
    monkeypatch.setenv(LIVE_CAPACITY_ENV_NAMES[2], "relative-version")
    with pytest.raises(ValueError, match="canonical absolute"):
        Settings()

    upload_dir = Path(os.environ["UPLOAD_DIR"]).resolve()
    monkeypatch.setenv(LIVE_CAPACITY_ENV_NAMES[2], str(upload_dir / "capacity-version"))
    with pytest.raises(ValueError, match="outside UPLOAD_DIR"):
        Settings()

    state_dir = tmp_path / "capacity"
    state_dir.mkdir(mode=0o700)
    state_path = state_dir / "version"
    monkeypatch.setenv(LIVE_CAPACITY_ENV_NAMES[2], str(state_path))
    monkeypatch.setenv(LIVE_CAPACITY_ENV_NAMES[1], "1")
    with pytest.raises(ValueError, match="must be greater"):
        Settings()

    monkeypatch.setenv(LIVE_CAPACITY_ENV_NAMES[1], "2")
    monkeypatch.setenv("WALKSAFE_BACKEND_WORKERS", "2")
    with pytest.raises(ValueError, match="one backend worker and one replica"):
        Settings()

    monkeypatch.setenv("WALKSAFE_BACKEND_WORKERS", "1")
    configured = Settings()

    assert configured.capacity_measurement_interval_seconds == 1
    assert configured.capacity_state_ttl_seconds == 2
    assert configured.capacity_version_state_path == state_path


@pytest.mark.parametrize("invalid", ["0", "-1", "nan", "inf", "1e9999"])
def test_live_capacity_seconds_must_be_finite_and_positive(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    invalid: str,
) -> None:
    state_dir = tmp_path / "capacity"
    state_dir.mkdir(mode=0o700)
    monkeypatch.setenv(LIVE_CAPACITY_ENV_NAMES[0], invalid)
    monkeypatch.setenv(LIVE_CAPACITY_ENV_NAMES[1], "2")
    monkeypatch.setenv(LIVE_CAPACITY_ENV_NAMES[2], str(state_dir / "version"))

    with pytest.raises(ValueError, match="finite positive"):
        Settings()


def test_synthetic_and_live_capacity_inputs_are_mutually_exclusive() -> None:
    values = {
        "WALKSAFE_CAPACITY_VERSION": "1",
        "WALKSAFE_CAPACITY_OBSERVED_AT": "2026-08-25T00:00:00Z",
        "WALKSAFE_CAPACITY_EXPIRES_AT": "2026-08-25T00:05:00Z",
        "WALKSAFE_CAPACITY_USED_PERCENT": "69",
    }

    with pytest.raises(ValueError, match="cannot be combined"):
        capacity_state_from_environment(
            "test",
            values,
            live_monitor_enabled=True,
        )


def test_filesystem_monitor_maps_available_blocks_to_thresholds_without_exposure(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    now = [BASE_TIME]
    monitor, state, upload_dir, version_path = _filesystem_monitor(
        tmp_path,
        clock=lambda: now[0],
    )
    available_blocks = iter((31, 30, 15, 5, 0, 31))
    monkeypatch.setattr(
        capacity_service.os,
        "fstatvfs",
        lambda _descriptor: SimpleNamespace(
            f_blocks=100,
            f_bavail=next(available_blocks),
        ),
    )
    expected_levels = (
        CapacityLevel.NORMAL,
        CapacityLevel.ADMIN_ONLY_WARNING,
        CapacityLevel.PAUSE_NEW_FIELD_TEST_PARTICIPANTS,
        CapacityLevel.HOLD_NEW_RAW_COLLECTION_SESSIONS,
        CapacityLevel.HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES,
        CapacityLevel.NORMAL,
    )

    for version, expected_level in enumerate(expected_levels, start=1):
        now[0] = BASE_TIME + timedelta(seconds=version)
        snapshot = monitor.measure_once()
        assert snapshot.version == version
        assert snapshot.level is expected_level
        assert snapshot.expires_at == now[0] + timedelta(seconds=10)
        assert not hasattr(snapshot, "used_percent")

    assert state.current(now=now[0]).level is CapacityLevel.NORMAL
    assert version_path.read_bytes() == b"6\n"
    assert stat.S_IMODE(version_path.stat().st_mode) == 0o600
    assert sorted(path.name for path in version_path.parent.iterdir()) == ["version"]
    assert str(upload_dir) not in str(state.current(now=now[0]).to_wire())


def test_filesystem_monitor_uses_the_real_upload_filesystem_once(tmp_path: Path) -> None:
    monitor, _state, _upload_dir, version_path = _filesystem_monitor(tmp_path)

    snapshot = monitor.measure_once()

    assert snapshot.version == 1
    assert snapshot.level in CapacityLevel
    assert version_path.read_bytes() == b"1\n"


def test_filesystem_monitor_version_increases_after_restart(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    upload_dir, version_path = _capacity_paths(tmp_path)
    monkeypatch.setattr(
        capacity_service.os,
        "fstatvfs",
        lambda _descriptor: SimpleNamespace(f_blocks=100, f_bavail=30),
    )
    first_state = CapacityState(clock=lambda: BASE_TIME)
    first = FilesystemCapacityMonitor(
        state=first_state,
        upload_dir=upload_dir,
        version_state_path=version_path,
        interval_seconds=1,
        ttl_seconds=10,
        clock=lambda: BASE_TIME,
    )
    assert first.measure_once().version == 1

    restarted_at = BASE_TIME + timedelta(seconds=1)
    restarted_state = CapacityState(clock=lambda: restarted_at)
    restarted = FilesystemCapacityMonitor(
        state=restarted_state,
        upload_dir=upload_dir,
        version_state_path=version_path,
        interval_seconds=1,
        ttl_seconds=10,
        clock=lambda: restarted_at,
    )

    assert restarted.measure_once().version == 2
    assert version_path.read_bytes() == b"2\n"


def test_measurement_failure_preserves_snapshot_only_until_original_expiry_and_recovers(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    now = [BASE_TIME]
    monitor, state, upload_dir, version_path = _filesystem_monitor(
        tmp_path,
        clock=lambda: now[0],
    )
    outcomes: list[SimpleNamespace | OSError] = [
        SimpleNamespace(f_blocks=100, f_bavail=30),
        OSError(f"raw failure {upload_dir} 97 percent"),
        SimpleNamespace(f_blocks=100, f_bavail=31),
    ]

    def fstatvfs(_descriptor: int) -> SimpleNamespace:
        outcome = outcomes.pop(0)
        if isinstance(outcome, OSError):
            raise outcome
        return outcome

    monkeypatch.setattr(capacity_service.os, "fstatvfs", fstatvfs)
    accepted = monitor.measure_once()
    assert accepted.version == 1

    now[0] = BASE_TIME + timedelta(seconds=5)
    with pytest.raises(CapacityMeasurementError) as failure:
        monitor.measure_once()
    assert str(failure.value) == "capacity measurement failed"
    assert str(upload_dir) not in str(failure.value)
    assert "percent" not in str(failure.value)
    assert state.current(now=now[0]) == accepted
    assert version_path.read_bytes() == b"1\n"

    now[0] = BASE_TIME + timedelta(seconds=10)
    with pytest.raises(CapacityStateUnavailable):
        state.current(now=now[0])

    now[0] = BASE_TIME + timedelta(seconds=11)
    recovered = monitor.measure_once()
    assert recovered.version == 2
    assert recovered.level is CapacityLevel.NORMAL
    assert version_path.read_bytes() == b"2\n"


def test_atomic_counter_failure_keeps_previous_version_and_snapshot(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    now = [BASE_TIME]
    monitor, state, _upload_dir, version_path = _filesystem_monitor(
        tmp_path,
        clock=lambda: now[0],
    )
    monkeypatch.setattr(
        capacity_service.os,
        "fstatvfs",
        lambda _descriptor: SimpleNamespace(f_blocks=100, f_bavail=30),
    )
    accepted = monitor.measure_once()
    real_replace = capacity_service.os.replace

    def fail_replace(*_args, **_kwargs) -> None:
        raise OSError(f"replace failed at {version_path}")

    monkeypatch.setattr(capacity_service.os, "replace", fail_replace)
    now[0] = BASE_TIME + timedelta(seconds=1)
    with pytest.raises(CapacityMeasurementError, match="capacity measurement failed"):
        monitor.measure_once()

    assert version_path.read_bytes() == b"1\n"
    assert state.current(now=now[0]) == accepted
    assert sorted(path.name for path in version_path.parent.iterdir()) == ["version"]

    monkeypatch.setattr(capacity_service.os, "replace", real_replace)
    now[0] = BASE_TIME + timedelta(seconds=2)
    assert monitor.measure_once().version == 2


def test_counter_fsyncs_file_then_parent_directory(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monitor, _state, _upload_dir, _version_path = _filesystem_monitor(tmp_path)
    monkeypatch.setattr(
        capacity_service.os,
        "fstatvfs",
        lambda _descriptor: SimpleNamespace(f_blocks=100, f_bavail=30),
    )
    real_fsync = capacity_service.os.fsync
    fsync_kinds: list[str] = []

    def recording_fsync(descriptor: int) -> None:
        metadata = os.fstat(descriptor)
        fsync_kinds.append("directory" if stat.S_ISDIR(metadata.st_mode) else "file")
        real_fsync(descriptor)

    monkeypatch.setattr(capacity_service.os, "fsync", recording_fsync)

    monitor.measure_once()

    assert fsync_kinds == ["file", "directory"]


def test_counter_lock_serializes_concurrent_measurements(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monitor, _state, _upload_dir, version_path = _filesystem_monitor(tmp_path)
    monkeypatch.setattr(
        capacity_service.os,
        "fstatvfs",
        lambda _descriptor: SimpleNamespace(f_blocks=100, f_bavail=30),
    )

    with ThreadPoolExecutor(max_workers=8) as executor:
        versions = sorted(executor.map(lambda _index: monitor.measure_once().version, range(8)))

    assert versions == list(range(1, 9))
    assert version_path.read_bytes() == b"8\n"


@pytest.mark.parametrize("kind", ["corrupt", "wrong_mode", "symlink", "parent_mode"])
def test_counter_corruption_and_metadata_fail_closed_without_details(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    kind: str,
) -> None:
    upload_dir, version_path = _capacity_paths(tmp_path)
    if kind == "corrupt":
        version_path.write_text("01\n", encoding="ascii")
        version_path.chmod(0o600)
    elif kind == "wrong_mode":
        version_path.write_text("1\n", encoding="ascii")
        version_path.chmod(0o644)
    elif kind == "symlink":
        target = tmp_path / "target-version"
        target.write_text("1\n", encoding="ascii")
        target.chmod(0o600)
        version_path.symlink_to(target)
    else:
        version_path.parent.chmod(0o755)
    monkeypatch.setattr(
        capacity_service.os,
        "fstatvfs",
        lambda _descriptor: SimpleNamespace(f_blocks=100, f_bavail=30),
    )
    monitor = FilesystemCapacityMonitor(
        state=CapacityState(clock=lambda: BASE_TIME),
        upload_dir=upload_dir,
        version_state_path=version_path,
        interval_seconds=1,
        ttl_seconds=10,
        clock=lambda: BASE_TIME,
    )

    with pytest.raises(CapacityMeasurementError) as failure:
        monitor.measure_once()

    assert str(failure.value) == "capacity measurement failed"
    assert str(version_path) not in str(failure.value)


def test_capacity_endpoint_has_exact_success_body_and_no_store_on_200_and_503() -> None:
    now = [BASE_TIME]
    state = CapacityState(clock=lambda: now[0])
    state.publish(
        version=7,
        observed_at=BASE_TIME,
        expires_at=BASE_TIME + timedelta(minutes=5),
        used_percent=95,
    )
    app = FastAPI()
    app.include_router(create_router(state))
    client = ASGITestClient(app)

    available = client.get("/internal/capacity")

    assert available.status_code == 200
    assert available.headers["cache-control"] == "no-store"
    assert available.json() == {
        "version": 7,
        "observed_at": "2026-08-25T00:00:00Z",
        "expires_at": "2026-08-25T00:05:00Z",
        "level": "HOLD_NEW_RAW_COLLECTION_SESSIONS",
        "reason": "STORAGE_UTILIZATION",
    }
    assert "percent" not in available.text.lower()

    now[0] = BASE_TIME + timedelta(minutes=5)
    unavailable = client.get("/internal/capacity")

    assert unavailable.status_code == 503
    assert unavailable.headers["cache-control"] == "no-store"
    assert unavailable.json() == {
        "detail": {"code": "capacity_state_unavailable"}
    }
    assert client.post("/internal/capacity", json={}).status_code == 405


def test_capacity_route_uses_exact_field_token_classification() -> None:
    assert required_field_test_access(
        "/internal/capacity", "GET"
    ) is FieldTestAccess.FIELD
    assert required_field_test_access(
        "/internal/capacity", "POST"
    ) is FieldTestAccess.ADMIN
    assert required_field_test_access(
        "/internal/capacity/extra", "GET"
    ) is FieldTestAccess.ADMIN

    state = CapacityState(clock=lambda: BASE_TIME)
    state.publish(
        version=1,
        observed_at=BASE_TIME,
        expires_at=BASE_TIME + timedelta(minutes=1),
        used_percent=69,
    )
    settings = SimpleNamespace(
        field_test_security_enabled=True,
        field_test_token="field-capacity-token",
        admin_token="admin-capacity-token",
        admin_security_enabled=False,
        admin_device_proof_enabled=False,
    )
    app = FastAPI()
    app.include_router(create_router(state))
    app.add_middleware(FieldTestSecurityMiddleware, settings=settings)
    client = ASGITestClient(app)

    assert client.get("/internal/capacity").status_code == 401
    assert client.get(
        "/internal/capacity",
        headers={"x-walksafe-admin-token": settings.admin_token},
    ).status_code == 403
    assert client.get(
        "/internal/capacity",
        headers={"x-walksafe-field-test-token": settings.field_test_token},
    ).status_code == 200


def test_main_wires_one_capacity_state_singleton(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in LIVE_CAPACITY_ENV_NAMES:
        monkeypatch.delenv(name, raising=False)
    for name in CAPACITY_ENV_NAMES:
        monkeypatch.delenv(name, raising=False)

    import backend.app.main as main_app

    routes = [
        route
        for route in main_app.app.routes
        if getattr(route, "path", None) == "/internal/capacity"
        and "GET" in getattr(route, "methods", set())
    ]

    assert len(routes) == 1
    assert main_app.app.state.capacity_state is main_app.capacity_state
    assert main_app.capacity_monitor is None
    assert main_app.app.state.capacity_monitor is None
    assert any(
        cell.cell_contents is main_app.capacity_state
        for cell in (routes[0].endpoint.__closure__ or ())
    )


def test_main_lifespan_takes_first_sample_retries_and_cancels_without_error_details(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    import backend.app.main as main_app

    class FakeMonitor:
        interval_seconds = 0.001

        def __init__(self) -> None:
            self.calls = 0

        def measure_once(self) -> None:
            self.calls += 1
            if self.calls == 1:
                raise CapacityMeasurementError(
                    "raw OSError /private/upload 97 percent"
                )

    monitor = FakeMonitor()
    monkeypatch.setattr(main_app, "capacity_monitor", monitor)
    monkeypatch.setattr(main_app, "validate_admin_credential_issuer_binding", lambda: None)
    monkeypatch.setattr(main_app, "bind_privacy_hmac_key", lambda: None)
    monkeypatch.setattr(main_app, "reconcile_report_storage", lambda: None)
    monkeypatch.setattr(main_app, "inference_runner", None)
    caplog.set_level("WARNING", logger="backend.app.main")

    async def exercise() -> None:
        async with main_app.lifespan(main_app.app):
            assert monitor.calls >= 1
            for _ in range(100):
                if monitor.calls >= 2:
                    break
                await asyncio.sleep(0.001)
            assert monitor.calls >= 2
        calls_after_shutdown = monitor.calls
        await asyncio.sleep(0.01)
        assert monitor.calls == calls_after_shutdown

    asyncio.run(exercise())

    messages = [record.getMessage() for record in caplog.records]
    assert messages == ["capacity filesystem measurement failed"]
    assert "/private/upload" not in caplog.text
    assert "97 percent" not in caplog.text
    assert "OSError" not in caplog.text


def test_main_lifespan_waits_for_an_inflight_capacity_measurement(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import backend.app.main as main_app

    measurement_started = Event()
    release_measurement = Event()

    class FakeMonitor:
        interval_seconds = 0.001

        def __init__(self) -> None:
            self.calls = 0

        def measure_once(self) -> None:
            self.calls += 1
            if self.calls == 2:
                measurement_started.set()
                assert release_measurement.wait(timeout=2)

    monitor = FakeMonitor()
    monkeypatch.setattr(main_app, "capacity_monitor", monitor)
    monkeypatch.setattr(main_app, "validate_admin_credential_issuer_binding", lambda: None)
    monkeypatch.setattr(main_app, "bind_privacy_hmac_key", lambda: None)
    monkeypatch.setattr(main_app, "reconcile_report_storage", lambda: None)
    monkeypatch.setattr(main_app, "inference_runner", None)

    async def exercise() -> None:
        manager = main_app.lifespan(main_app.app)
        await manager.__aenter__()
        assert await asyncio.to_thread(measurement_started.wait, 2)

        shutdown = asyncio.create_task(manager.__aexit__(None, None, None))
        await asyncio.sleep(0.01)
        assert not shutdown.done()

        release_measurement.set()
        await asyncio.wait_for(shutdown, timeout=2)
        calls_after_shutdown = monitor.calls
        await asyncio.sleep(0.01)
        assert monitor.calls == calls_after_shutdown

    asyncio.run(exercise())
