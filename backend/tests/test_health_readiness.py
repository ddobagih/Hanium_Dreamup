from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from types import SimpleNamespace

import backend.app.api.health as health_api
import httpx
from fastapi import FastAPI
import pytest
from backend.app.main import app, settings as app_settings
from asgi_client import ASGITestClient


_REAL_REPORT_STORAGE_INVENTORY_READINESS = (
    health_api._report_storage_inventory_readiness
)


@pytest.fixture(autouse=True)
def isolate_report_storage_inventory_probe(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        health_api,
        "_report_storage_inventory_readiness",
        lambda _settings, _manager: {"ready": True},
    )
    monkeypatch.setattr(
        health_api,
        "_privacy_hmac_binding_readiness",
        lambda _settings: {"ready": True, "binding": "matched"},
    )


async def _navigation_ready(_settings) -> dict[str, object]:
    return {"ready": True, "provider": "tmap_pedestrian", "evidence": "recent_success"}


def test_readiness_expected_migration_matches_the_single_alembic_head() -> None:
    repository_root = Path(__file__).resolve().parents[2]
    result = subprocess.run(
        [sys.executable, "-m", "alembic", "-c", "backend/alembic.ini", "heads"],
        cwd=repository_root,
        check=True,
        capture_output=True,
        text=True,
    )
    heads = [line.split()[0] for line in result.stdout.splitlines() if line.strip()]

    assert heads == [health_api.EXPECTED_ALEMBIC_HEAD]


def test_readiness_requires_database_migration_upload_and_detector(monkeypatch) -> None:
    source_commit = "a" * 40
    monkeypatch.setattr(app_settings, "walksafe_source_commit", source_commit)
    monkeypatch.setattr(app_settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(app_settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(health_api, "_database_readiness", lambda _url: {"ready": True})
    monkeypatch.setattr(health_api, "_upload_readiness", lambda _path: {"ready": True})
    monkeypatch.setattr(health_api, "_navigation_readiness", _navigation_ready)
    monkeypatch.setattr(
        health_api,
        "detect_v2_health",
        lambda _settings: {"status": "ready", "mode": "fake", "reason": None, "configured_runtime": None},
    )

    response = ASGITestClient(app).get("/ready")

    assert response.status_code == 200
    assert response.json()["status"] == "ready"
    assert response.json()["source_commit"] == source_commit


def test_database_readiness_fails_when_enabled_replica_totp_binding_is_stale(
    monkeypatch,
) -> None:
    monkeypatch.setattr(
        health_api,
        "_database_readiness",
        lambda _url: {"ready": True, "current_revision": health_api.EXPECTED_ALEMBIC_HEAD},
    )
    monkeypatch.setattr(
        health_api,
        "_admin_totp_binding_readiness",
        lambda _settings: {
            "ready": False,
            "reason": "admin_totp_configuration_mismatch",
        },
    )
    monkeypatch.setattr(app_settings, "admin_security_enabled", True)

    assert health_api._database_and_admin_readiness(app_settings) == {
        "ready": False,
        "current_revision": health_api.EXPECTED_ALEMBIC_HEAD,
        "reason": "admin_totp_configuration_mismatch",
    }


def test_readiness_returns_503_when_database_or_detector_is_unavailable(monkeypatch) -> None:
    monkeypatch.setattr(app_settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(app_settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(
        health_api,
        "_database_readiness",
        lambda _url: {"ready": False, "reason": "migration_not_at_head"},
    )
    monkeypatch.setattr(health_api, "_upload_readiness", lambda _path: {"ready": True})
    monkeypatch.setattr(health_api, "_navigation_readiness", _navigation_ready)
    monkeypatch.setattr(
        health_api,
        "detect_v2_health",
        lambda _settings: {
            "status": "unavailable",
            "mode": "yolo",
            "reason": "model_missing",
            "configured_runtime": None,
        },
    )

    response = ASGITestClient(app).get("/ready")

    assert response.status_code == 503
    assert response.json()["status"] == "not_ready"
    assert response.json()["checks"]["database"]["ready"] is False
    assert response.json()["checks"]["detector"]["ready"] is False


def test_slow_readiness_probe_does_not_block_liveness(monkeypatch) -> None:
    monkeypatch.setattr(app_settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(app_settings, "walking_route_provider", "tmap_pedestrian")

    def slow_database(_url: str) -> dict[str, object]:
        time.sleep(0.25)
        return {"ready": True}

    monkeypatch.setattr(health_api, "_database_readiness", slow_database)
    monkeypatch.setattr(health_api, "_upload_readiness", lambda _path: {"ready": True})
    monkeypatch.setattr(health_api, "_navigation_readiness", _navigation_ready)
    monkeypatch.setattr(
        health_api,
        "detect_v2_health",
        lambda _settings: {"status": "ready", "mode": "fake", "reason": None, "configured_runtime": None},
    )

    async def exercise() -> tuple[httpx.Response, float]:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            started_at = time.monotonic()
            ready_task = asyncio.create_task(client.get("/ready"))

            async def delayed_health() -> tuple[httpx.Response, float]:
                await asyncio.sleep(0.02)
                response = await client.get("/health")
                return response, time.monotonic() - started_at

            health_response, health_elapsed = await delayed_health()
            await ready_task
            return health_response, health_elapsed

    health_response, health_elapsed = asyncio.run(exercise())

    assert health_response.status_code == 200
    assert health_elapsed < 0.1


def test_readiness_local_probe_timeout_fails_closed(monkeypatch) -> None:
    monkeypatch.setattr(app_settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(app_settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(health_api, "READINESS_LOCAL_CHECK_TIMEOUT_SECONDS", 0.02)
    finished = threading.Event()

    def stalled_database(_url: str) -> dict[str, object]:
        try:
            time.sleep(0.08)
            return {"ready": True}
        finally:
            finished.set()

    monkeypatch.setattr(health_api, "_database_readiness", stalled_database)
    monkeypatch.setattr(health_api, "_upload_readiness", lambda _path: {"ready": True})
    monkeypatch.setattr(health_api, "_navigation_readiness", _navigation_ready)
    monkeypatch.setattr(
        health_api,
        "detect_v2_health",
        lambda _settings: {"status": "ready", "mode": "fake", "reason": None, "configured_runtime": None},
    )

    response = ASGITestClient(app).get("/ready")

    assert response.status_code == 503
    assert response.json()["checks"]["database"] == {"ready": False, "reason": "check_timeout"}
    assert finished.wait(timeout=1), "timed-out readiness probe did not finish"


def test_repeated_readiness_timeouts_reuse_probe_and_preserve_default_executor(monkeypatch) -> None:
    monkeypatch.setattr(app_settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(app_settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(health_api, "READINESS_LOCAL_CHECK_TIMEOUT_SECONDS", 0.03)
    release = threading.Event()
    started = threading.Event()
    invocation_lock = threading.Lock()
    invocation_count = 0

    def stalled_database(_url: str) -> dict[str, object]:
        nonlocal invocation_count
        with invocation_lock:
            invocation_count += 1
        started.set()
        assert release.wait(timeout=2), "readiness database probe was not released"
        return {"ready": True}

    monkeypatch.setattr(health_api, "_database_readiness", stalled_database)
    monkeypatch.setattr(health_api, "_upload_readiness", lambda _path: {"ready": True})
    monkeypatch.setattr(health_api, "_navigation_readiness", _navigation_ready)
    monkeypatch.setattr(
        health_api,
        "detect_v2_health",
        lambda _settings: {"status": "ready", "mode": "fake", "reason": None, "configured_runtime": None},
    )

    async def exercise() -> tuple[list[httpx.Response], str]:
        loop = asyncio.get_running_loop()
        executor = ThreadPoolExecutor(max_workers=2)
        loop.set_default_executor(executor)
        try:
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
                requests = [asyncio.create_task(client.get("/ready")) for _ in range(8)]
                deadline = loop.time() + 1
                while not started.is_set() and loop.time() < deadline:
                    await asyncio.sleep(0.005)
                assert started.is_set(), "readiness database probe did not start"
                responses = await asyncio.gather(*requests)
                unrelated = await asyncio.wait_for(
                    asyncio.to_thread(lambda: "default-executor-available"),
                    timeout=0.1,
                )
                return responses, unrelated
        finally:
            release.set()
            await asyncio.sleep(0.05)
            executor.shutdown(wait=True, cancel_futures=True)

    try:
        responses, unrelated = asyncio.run(exercise())
    finally:
        release.set()

    assert all(response.status_code == 503 for response in responses)
    assert invocation_count == 1
    assert unrelated == "default-executor-available"


def test_stalled_readiness_timeouts_do_not_accumulate_future_callbacks(monkeypatch) -> None:
    monkeypatch.setattr(health_api, "READINESS_LOCAL_CHECK_TIMEOUT_SECONDS", 0.002)
    release = threading.Event()
    started = threading.Event()

    def stalled_check() -> dict[str, object]:
        started.set()
        release.wait()
        return {"ready": True}

    probe = health_api._OffloadedReadinessProbe(stalled_check)

    async def exercise() -> tuple[int, int]:
        for _ in range(50):
            result = await probe.check()
            assert result == {"ready": False, "reason": "check_timeout"}
        assert started.is_set()
        future = probe._inflight
        assert future is not None
        return len(future._done_callbacks), int(future.done())

    try:
        callback_count, completed = asyncio.run(exercise())
    finally:
        release.set()

    assert callback_count == 0
    assert completed == 0


def test_uncooperative_readiness_probe_does_not_block_process_exit() -> None:
    script = """
import asyncio
import threading
import backend.app.api.health as health

health.READINESS_LOCAL_CHECK_TIMEOUT_SECONDS = 0.01
never = threading.Event()
probe = health._OffloadedReadinessProbe(lambda: (never.wait(), {"ready": True})[1])
print(asyncio.run(probe.check()), flush=True)
print("main-returned", flush=True)
"""
    repo_root = Path(__file__).resolve().parents[2]

    completed = subprocess.run(
        [sys.executable, "-c", script],
        cwd=repo_root,
        env={**os.environ, "PYTHONPATH": str(repo_root)},
        capture_output=True,
        text=True,
        timeout=3,
        check=False,
    )

    assert completed.returncode == 0, completed.stderr
    assert "'reason': 'check_timeout'" in completed.stdout
    assert "main-returned" in completed.stdout


def test_concurrent_readiness_requests_share_one_tmap_live_probe_and_failure_cooldown(monkeypatch) -> None:
    monkeypatch.setattr(app_settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(app_settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(app_settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(app_settings, "tmap_readiness_live_probe_enabled", True)
    monkeypatch.setattr(app_settings, "tmap_readiness_probe_timeout_seconds", 1.0)
    monkeypatch.setattr(health_api, "TMAP_READINESS_FAILURE_COOLDOWN_SECONDS", 1.0, raising=False)
    monkeypatch.setattr(health_api, "_database_readiness", lambda _url: {"ready": True})
    monkeypatch.setattr(health_api, "_upload_readiness", lambda _path: {"ready": True})
    monkeypatch.setattr(
        health_api,
        "detect_v2_health",
        lambda _settings: {"status": "ready", "mode": "fake", "reason": None, "configured_runtime": None},
    )
    monkeypatch.setattr(
        health_api,
        "tmap_dependency_readiness",
        lambda _max_age: {"ready": False, "reason": "no_recent_success"},
    )
    started = asyncio.Event()
    release = asyncio.Event()
    invocation_count = 0

    async def stalled_tmap_probe(_settings) -> dict[str, object]:
        nonlocal invocation_count
        invocation_count += 1
        started.set()
        await release.wait()
        return {"ready": False, "reason": "provider_unavailable"}

    monkeypatch.setattr(health_api, "probe_tmap_dependencies", stalled_tmap_probe)
    isolated_app = FastAPI()
    isolated_app.include_router(health_api.create_router(app_settings, lambda: None))

    async def exercise() -> tuple[list[httpx.Response], httpx.Response]:
        transport = httpx.ASGITransport(app=isolated_app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            requests = [asyncio.create_task(client.get("/ready")) for _ in range(20)]
            await asyncio.wait_for(started.wait(), timeout=1)
            await asyncio.sleep(0.02)
            assert invocation_count == 1
            release.set()
            responses = await asyncio.gather(*requests)
            cooldown_response = await client.get("/ready")
            return responses, cooldown_response

    responses, cooldown_response = asyncio.run(exercise())

    assert all(response.status_code == 503 for response in responses)
    assert cooldown_response.status_code == 503
    assert invocation_count == 1


def test_readiness_returns_503_when_tmap_key_is_missing(monkeypatch) -> None:
    monkeypatch.setattr(health_api, "_database_readiness", lambda _url: {"ready": True})
    monkeypatch.setattr(health_api, "_upload_readiness", lambda _path: {"ready": True})
    monkeypatch.setattr(
        health_api,
        "detect_v2_health",
        lambda _settings: {"status": "ready", "mode": "fake", "reason": None, "configured_runtime": None},
    )
    monkeypatch.setattr(app_settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(app_settings, "tmap_app_key", "")

    response = ASGITestClient(app).get("/ready")

    assert response.status_code == 503
    assert response.json()["checks"]["navigation"] == {
        "ready": False,
        "reason": "tmap_app_key_missing",
        "provider": "tmap_pedestrian",
    }


def test_navigation_readiness_rejects_mock_poi_provider(monkeypatch) -> None:
    monkeypatch.setattr(app_settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(app_settings, "tmap_poi_provider", "mock")
    monkeypatch.setattr(app_settings, "tmap_app_key", "test-tmap-key")

    assert asyncio.run(health_api._navigation_readiness(app_settings)) == {
        "ready": False,
        "reason": "tmap_poi_provider_not_live",
        "provider": "tmap_pedestrian",
    }


def test_navigation_readiness_requires_recent_route_and_poi_success(monkeypatch) -> None:
    monkeypatch.setattr(app_settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(app_settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(app_settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(app_settings, "tmap_readiness_live_probe_enabled", False)
    monkeypatch.setattr(app_settings, "tmap_readiness_success_max_age_seconds", 300.0)
    monkeypatch.setattr(
        health_api,
        "tmap_dependency_readiness",
        lambda _max_age: {
            "ready": False,
            "provider": "tmap_pedestrian",
            "evidence": "none",
            "route_recent": True,
            "poi_recent": False,
        },
    )

    result = asyncio.run(health_api._navigation_readiness(app_settings))

    assert result["ready"] is False
    assert result["reason"] == "tmap_recent_success_unavailable"


def test_navigation_readiness_opt_in_live_probe_is_bounded_and_mockable(monkeypatch) -> None:
    monkeypatch.setattr(app_settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(app_settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(app_settings, "tmap_app_key", "test-tmap-key")
    monkeypatch.setattr(app_settings, "tmap_readiness_live_probe_enabled", True)
    monkeypatch.setattr(app_settings, "tmap_readiness_probe_timeout_seconds", 1.0)
    monkeypatch.setattr(app_settings, "tmap_readiness_success_max_age_seconds", 300.0)
    monkeypatch.setattr(
        health_api,
        "tmap_dependency_readiness",
        lambda _max_age: {
            "ready": False,
            "provider": "tmap_pedestrian",
            "evidence": "none",
            "route_recent": False,
            "poi_recent": False,
        },
    )

    async def fake_probe(_settings) -> dict[str, object]:
        return {"ready": True, "provider": "tmap_pedestrian", "evidence": "live_probe"}

    monkeypatch.setattr(health_api, "probe_tmap_dependencies", fake_probe)

    result = asyncio.run(health_api._navigation_readiness(app_settings))

    assert result["ready"] is True
    assert result["evidence"] == "live_probe"


def test_upload_readiness_proves_file_and_directory_fsync(tmp_path: Path, monkeypatch) -> None:
    fsync_calls: list[int] = []
    real_fsync = health_api.os.fsync

    def record_fsync(descriptor: int) -> None:
        fsync_calls.append(descriptor)
        real_fsync(descriptor)

    monkeypatch.setattr(health_api.os, "fsync", record_fsync)

    assert health_api._upload_readiness(tmp_path) == {"ready": True}
    assert len(fsync_calls) == 3
    assert list(tmp_path.iterdir()) == []


@pytest.mark.parametrize(
    ("entry_name", "entry_kind"),
    [
        ("11111111-1111-4111-8111-111111111111.jpg", "file"),
        ("11111111-1111-4111-8111-111111111111.wse", "file"),
        (".report-write-journal", "nonempty_directory"),
        (".retention-quarantine", "nonempty_directory"),
    ],
)
def test_readiness_rejects_post_start_report_storage_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    entry_name: str,
    entry_kind: str,
) -> None:
    tmp_path.chmod(0o700)
    entry = tmp_path / entry_name
    if entry_kind == "file":
        entry.write_bytes(b"post-start-drift")
        entry.chmod(0o600)
    else:
        entry.mkdir(mode=0o700)
        evidence = entry / "pending.json"
        evidence.write_text("{}", encoding="utf-8")
        evidence.chmod(0o600)

    lock_statements: list[str] = []

    class FakeDatabaseSession:
        def execute(self, statement, _parameters) -> None:
            lock_statements.append(str(statement))

        def scalars(self, _statement):
            return SimpleNamespace(all=lambda: [])

    class FakeTransaction:
        def __enter__(self) -> FakeDatabaseSession:
            return FakeDatabaseSession()

        def __exit__(self, *_args) -> None:
            return None

    class FakeSessionFactory:
        @staticmethod
        def begin() -> FakeTransaction:
            return FakeTransaction()

    manager = SimpleNamespace(keyring=SimpleNamespace(slots=()))
    monkeypatch.setattr(health_api, "SessionLocal", FakeSessionFactory)
    monkeypatch.setattr(
        health_api,
        "_report_storage_inventory_readiness",
        _REAL_REPORT_STORAGE_INVENTORY_READINESS,
    )
    monkeypatch.setattr(
        health_api,
        "_database_and_admin_readiness",
        lambda _settings: {"ready": True},
    )
    monkeypatch.setattr(
        health_api,
        "_upload_readiness",
        lambda _path: {"ready": True},
    )
    monkeypatch.setattr(
        health_api,
        "_detector_readiness",
        lambda _settings, _warmup: {"ready": True},
    )
    monkeypatch.setattr(health_api, "_navigation_readiness", _navigation_ready)
    monkeypatch.setattr(
        health_api,
        "_report_image_encryption_readiness",
        lambda _settings, _manager: {"ready": True},
    )
    monkeypatch.setattr(app_settings, "upload_dir", tmp_path)

    isolated_app = FastAPI()
    isolated_app.include_router(
        health_api.create_router(
            app_settings,
            lambda: None,
            manager,  # type: ignore[arg-type]
        )
    )
    response = ASGITestClient(isolated_app).get("/ready")

    assert response.status_code == 503
    assert response.json()["checks"]["report_storage"] == {
        "ready": False,
        "reason": "report_storage_inventory_invalid",
    }
    assert len(lock_statements) == 1
    assert "pg_advisory_xact_lock(" in lock_statements[0]
    assert "pg_advisory_xact_lock_shared" not in lock_statements[0]


def test_real_detector_readiness_fails_closed_when_warmup_fails(monkeypatch) -> None:
    def fail_warmup() -> None:
        raise RuntimeError("broken checkpoint")

    monkeypatch.setattr(
        health_api,
        "detect_v2_health",
        lambda _settings: {
            "status": "ready",
            "mode": "real",
            "reason": None,
            "configured_runtime": "unified_walksafe",
        },
    )

    result = health_api._detector_readiness(app_settings, fail_warmup)

    assert result["ready"] is False
    assert result["reason"] == "detector_warmup_failed"
    assert result["error_type"] == "RuntimeError"


def test_real_detector_readiness_records_successful_inference_warmup(monkeypatch) -> None:
    calls = 0

    def warmup() -> None:
        nonlocal calls
        calls += 1

    monkeypatch.setattr(
        health_api,
        "detect_v2_health",
        lambda _settings: {
            "status": "ready",
            "mode": "real",
            "reason": None,
            "configured_runtime": "unified_walksafe",
        },
    )

    result = health_api._detector_readiness(app_settings, warmup)

    assert calls == 1
    assert result["ready"] is True
    assert result["inference_warmed"] is True


def test_detector_readiness_probe_rechecks_detector_and_worker(monkeypatch) -> None:
    calls = 0

    def warmup() -> None:
        nonlocal calls
        calls += 1
        if calls > 1:
            raise RuntimeError("worker exited")

    monkeypatch.setattr(
        health_api,
        "detect_v2_health",
        lambda _settings: {
            "status": "ready",
            "mode": "real",
            "reason": None,
            "configured_runtime": "unified_walksafe",
        },
    )
    probe = health_api._DetectorReadinessProbe(app_settings, warmup)

    assert probe.check()["ready"] is True
    second = probe.check()
    assert second["ready"] is False
    assert second["reason"] == "detector_warmup_failed"
    assert calls == 2


def test_report_image_readiness_requires_deployment_encryption_boundary_contract_without_leaking_ids(
    monkeypatch,
) -> None:
    manager = type(
        "ReadyManager",
        (),
        {
            "readiness": lambda self: {
                "ready": True,
                "generation": 3,
                "provider_authority": {
                    "ancestor_authority": "root_owned_non_writable",
                    "credential_boundary": "root_owned_service_group_readable",
                    "endpoint_identity": "fd_path_matched_after_read",
                    "provider": "secret_file",
                },
            }
        },
    )()
    monkeypatch.setattr(app_settings, "walksafe_environment", "production")
    monkeypatch.setattr(app_settings, "database_at_rest_encryption_confirmed", False)
    monkeypatch.setattr(app_settings, "database_transport_security_confirmed", True)
    monkeypatch.setattr(app_settings, "database_encryption_key_boundary", "private-db-boundary")
    monkeypatch.setattr(app_settings, "report_image_key_boundary", "private-image-boundary")

    missing = health_api._report_image_encryption_readiness(app_settings, manager)

    assert missing == {"ready": False, "reason": "encryption_boundary_contract_missing"}
    assert "private-db-boundary" not in str(missing)
    assert "private-image-boundary" not in str(missing)

    monkeypatch.setattr(app_settings, "database_at_rest_encryption_confirmed", True)
    ready = health_api._report_image_encryption_readiness(app_settings, manager)
    assert ready["ready"] is True
    assert ready["database_at_rest_contract"] == "operator_declared"
    assert ready["database_transport_contract"] == "operator_declared"
    assert ready["key_boundary_separation"] == "operator_declared"
    assert ready["provider_authority"]["ancestor_authority"] == "root_owned_non_writable"
    assert ready["provider_authority"]["endpoint_identity"] == "fd_path_matched_after_read"
    assert "private-db-boundary" not in str(ready)
    assert "private-image-boundary" not in str(ready)
