"""Runtime /ready and report writers against an isolated migrated PostgreSQL."""
from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from copy import copy
from datetime import UTC, datetime
import errno
import io
import json
import os
from pathlib import Path
import threading
import time
from types import SimpleNamespace
import uuid

from alembic.config import Config
from alembic.script import ScriptDirectory
from fastapi import FastAPI
import httpx
from PIL import Image
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session, sessionmaker

from backend.app import uploads
from backend.app.api import health, reports
from backend.app.config import get_settings
from backend.app.models import Report, ReportImageObject
from backend.app.services import report_storage
from backend.app.services.report_image_keys import create_report_image_key_manager
from backend.app.services.report_storage_monitor import ReportStorageMonitor
from backend.app.services.storage_readiness_state import StorageReadinessState
from backend.app.services import report_storage_monitor as monitor_module
from backend.app.services.report_storage_recovery import locked_report_storage_session


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _assert_recovery_blocked(state):
    deadline = time.monotonic() + 1
    while time.monotonic() < deadline:
        with state.engine.connect() as connection:
            waiting = connection.scalar(text("""
                SELECT count(*) FROM pg_locks
                WHERE pid = :pid AND locktype = 'advisory' AND NOT granted
            """), {"pid": state.recovery_pid})
        if waiting == 1:
            assert not state.inventory_entered.is_set()
            return
        assert not state.inventory_entered.is_set(), "inventory ran before the conflicting storage operation finished"
        time.sleep(0.005)
    pytest.fail("recovery did not wait on the owned storage transaction")


@pytest.fixture
def runtime_app(tmp_path, monkeypatch, clean_test_storage):
    assert clean_test_storage is not None
    clean_test_storage()
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"])
    head = ScriptDirectory.from_config(
        Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    ).get_current_head()
    with engine.connect() as connection:
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == head
    upload = tmp_path / "uploads"
    upload.mkdir(mode=0o700)
    settings = copy(get_settings())
    settings.upload_dir = upload
    settings.maintenance_lock_path = None
    settings.maintenance_lock_group_gid = None
    settings.field_test_security_enabled = False
    assert settings.readiness_inventory_cache_seconds == 0
    manager = create_report_image_key_manager(settings)
    state = SimpleNamespace(
        engine=engine, upload=upload, defer_cleanup=False, commit_gate=None,
        commit_entered=threading.Event(), fail_commit=False, recovery_attempted=threading.Event(),
        recovery_pid=None, inventory_entered=threading.Event(), settings=settings, manager=manager,
    )

    class WriterSession(Session):
        def commit(self):
            if state.commit_gate is not None:
                state.commit_entered.set()
                assert state.commit_gate.wait(3), "owned writer gate must be released"
            if state.fail_commit:
                raise RuntimeError("owned runtime commit failure")
            return super().commit()

    class RecoverySession(Session):
        def execute(self, statement, *args, **kwargs):
            if "pg_advisory_xact_lock(" in str(statement):
                state.recovery_pid = super().execute(text("SELECT pg_backend_pid()")).scalar_one()
                state.recovery_attempted.set()
            return super().execute(statement, *args, **kwargs)

    recovery_factory = sessionmaker(engine, class_=RecoverySession, autoflush=False)
    monkeypatch.setattr(health, "SessionLocal", recovery_factory)
    real_complete = reports.complete_report_image_write

    def complete(pending):
        if state.defer_cleanup:
            raise OSError(errno.EIO, "owned deferred journal cleanup")
        return real_complete(pending)

    monkeypatch.setattr(reports, "complete_report_image_write", complete)
    real_inventory = monitor_module.read_report_storage_inventory_page

    def inventory(*args, **kwargs):
        state.inventory_entered.set()
        return real_inventory(*args, **kwargs)

    monkeypatch.setattr(monitor_module, "read_report_storage_inventory_page", inventory)
    storage_state = StorageReadinessState()
    state.storage_state = storage_state
    monitor = ReportStorageMonitor(
        upload_root=upload, state=storage_state,
        session=lambda: locked_report_storage_session(recovery_factory, reports._shared_report_write_lock, None, None),
        known_key_states=lambda: {slot.key_id: slot.state for slot in manager.keyring.slots},
        upload_check=lambda: health._upload_readiness(upload),
        encryption_check=lambda: health._report_image_encryption_readiness(settings, manager),
        record_incident=health._record_report_storage_inventory_incident,
        refresh_seconds=1, max_age_seconds=30,
    )
    monitor.restart()
    state.monitor = monitor
    # These dependencies do not participate in report recovery. Keep the real
    # upload probe, encrypted inventory, recovery, DB and report writer paths.
    for name in (
        "_database_and_admin_readiness", "_privacy_hmac_binding_readiness",
        "_account_crypto_binding_readiness", "_account_enrollment_config_readiness",
        "_raw_object_readiness", "_detector_readiness",
    ):
        monkeypatch.setattr(health, name, lambda *_a, **_kw: {"ready": True})

    async def navigation(*_args, **_kwargs):
        return {"ready": True}

    monkeypatch.setattr(health, "_navigation_readiness", navigation)
    app = FastAPI()
    state.app = app
    app.include_router(reports.create_router(settings, manager, storage_readiness=storage_state))
    app.include_router(health.create_router(
        settings, detector_warmup=lambda: None, report_image_key_manager=manager,
        report_storage_monitor=monitor,
    ))

    def get_db():
        with WriterSession(engine, autoflush=False) as db:
            yield db

    app.dependency_overrides[reports.get_db] = get_db
    image = io.BytesIO()
    Image.new("RGB", (4, 4), color=(70, 90, 120)).save(image, format="JPEG")
    content = image.getvalue()

    async def request(method, path, **kwargs):
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            return await client.request(method, path, **kwargs)

    def write(report_id=None):
        report_id = report_id or uuid.uuid4()
        metadata = json.dumps({
            "schema_version": "detect.v2", "model_key": "custom_tactile",
            "source_model": "fake/runtime-recovery", "model_class_id": 1,
            "class_name": "damaged_tactile_block", "category": "tactile",
            "confidence": 0.91, "threshold_used": 0.25,
            "bbox": {"x": 0.2, "y": 0.3, "width": 0.4, "height": 0.2},
            "captured_at": datetime.now(UTC).isoformat(),
            "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
            "trigger": "on_screen", "auto_reported": False,
        }, separators=(",", ":"))
        headers = {
            "x-walksafe-actor-id": "runtime@example.com",
            "x-walksafe-account-generation": "1",
            "x-walksafe-report-id": str(report_id),
            "x-walksafe-report-payload-sha256": reports.report_transport_payload_sha256(
                report_id, metadata.encode(), content,
            ),
            "x-walksafe-report-payload-bytes": str(len(metadata.encode()) + len(content)),
        }
        return report_id, asyncio.run(request("POST", "/reports/v2", headers=headers,
            data={"metadata": metadata}, files={"image": ("sample.jpg", content, "image/jpeg")}))

    state.write = write
    state.ready = lambda: asyncio.run(request("GET", "/ready"))
    def advance_monitor_then_ready():
        # Explicitly drive background batches before querying the memory-only API.
        # Stop after deferred recovery so its bounded first-batch oracle remains.
        for _ in range(100):
            complete = monitor.run_batch()
            if complete or (monitor._phase == "start" and monitor.checks()["report_storage"].get("reason") == "report_storage_recovery_deferred"):
                return state.ready()
        raise AssertionError("owned monitor did not finish its bounded cycle")

    state.advance_monitor_then_ready = advance_monitor_then_ready
    state.journal = lambda report_id: report_storage.pending_journal_directory(upload) / f"{report_id}.json"
    try:
        yield state
    finally:
        if state.commit_gate is not None:
            state.commit_gate.set()
        monitor.stop()
        monitor.close_cycle()
        engine.dispose()


def test_runtime_ready_retries_cleanup_and_reenables_writer(runtime_app, monkeypatch):
    state = runtime_app
    state.defer_cleanup = True
    report_id, created = state.write()
    assert created.status_code == 201, created.text
    destination = state.upload / f"{report_id}.wse"
    image_before = destination.read_bytes()
    journal_before = state.journal(report_id).read_bytes()
    real_remove = report_storage.remove_image_file
    failures = 1

    def remove(path):
        nonlocal failures
        if path == state.journal(report_id) and failures:
            failures -= 1
            raise OSError(errno.EIO, "owned transient cleanup failure")
        return real_remove(path)

    monkeypatch.setattr(report_storage, "remove_image_file", remove)
    blocked = state.advance_monitor_then_ready()
    assert blocked.status_code == 503
    assert blocked.json()["checks"]["report_storage"]["reason"] == "report_storage_recovery_retryable"
    assert blocked.json()["checks"]["report_storage"]["recovery"] == {
        "action": "restore_storage_io_then_retry_readiness", "restart_required": False,
    }
    assert state.journal(report_id).read_bytes() == journal_before
    assert destination.read_bytes() == image_before
    ready = state.advance_monitor_then_ready()
    assert ready.status_code == 200, ready.text
    assert ready.json()["checks"]["report_storage"]["ready"] is True
    assert not state.journal(report_id).exists()
    assert destination.read_bytes() == image_before
    state.defer_cleanup = False
    new_id, created_again = state.write()
    assert created_again.status_code == 201, created_again.text
    assert state.advance_monitor_then_ready().status_code == 200
    with Session(state.engine) as db:
        assert db.get(Report, report_id) is not None
        assert db.get(ReportImageObject, report_id) is not None
        assert db.get(Report, new_id) is not None


def test_runtime_ready_finishes_more_than_one_real_batch(runtime_app):
    state = runtime_app
    state.defer_cleanup = True
    ids = []
    for _ in range(health.RUNTIME_REPORT_RECOVERY_MAX_ITEMS + 1):
        report_id, response = state.write()
        assert response.status_code == 201, response.text
        ids.append(report_id)
    deferred = state.advance_monitor_then_ready()
    assert deferred.status_code == 503
    assert deferred.json()["checks"]["report_storage"]["reason"] == "report_storage_recovery_deferred"
    assert deferred.json()["checks"]["report_storage"]["recovery"] == {
        "action": "retry_readiness", "restart_required": False,
    }
    assert sum(state.journal(value).exists() for value in ids) == 1
    ready = state.advance_monitor_then_ready()
    assert ready.status_code == 200, ready.text
    assert not any(state.journal(value).exists() for value in ids)
    assert all((state.upload / f"{value}.wse").exists() for value in ids)


@pytest.mark.parametrize("damage", ["invalid-journal", "ciphertext", "unknown-object"])
def test_runtime_ready_preserves_unverifiable_evidence(runtime_app, damage):
    state = runtime_app
    state.defer_cleanup = True
    report_id, response = state.write()
    assert response.status_code == 201, response.text
    journal = state.journal(report_id)
    destination = state.upload / f"{report_id}.wse"
    verified_journal = journal.read_bytes()
    verified_image = destination.read_bytes()
    if damage == "invalid-journal":
        journal.write_text('{"private-invalid-journal":true}')
    elif damage == "ciphertext":
        encrypted = destination.read_bytes()
        destination.write_bytes(encrypted[:-1] + bytes([encrypted[-1] ^ 1]))
    else:
        unknown = state.upload / "unknown-private-object"
        unknown.write_bytes(b"private-evidence")
        unknown.chmod(0o600)
    journal_before = journal.read_bytes()
    image_before = destination.read_bytes()
    for _ in range(2):
        blocked = state.advance_monitor_then_ready()
        assert blocked.status_code == 503
        storage = blocked.json()["checks"]["report_storage"]
        assert storage["reason"] == (
            "report_storage_inventory_invalid" if damage == "unknown-object"
            else "report_storage_journal_unresolved"
        ), blocked.text
        assert storage["recovery"] == {
            "action": "preserve_evidence_and_verify_storage_under_maintenance_lock",
            "isolation": "readiness_blocked_evidence_retained_in_place",
            "restart_required": None,
            "restart_alone_repairs": None if damage == "unknown-object" else False,
        }
        for private_value in ("private-invalid-journal", "private-evidence", "unknown-private-object", str(state.upload)):
            assert private_value not in blocked.text
        assert destination.read_bytes() == image_before
        if damage != "unknown-object":
            assert journal.read_bytes() == journal_before
        else:
            assert unknown.read_bytes() == b"private-evidence"
    # Model an operator restoring verified test evidence under the same lock.
    # Preserve the untrusted bytes outside the active root; this is not an
    # automatic product quarantine/repair path or production data operation.
    evidence = state.upload.parent / "owned-evidence"
    evidence.mkdir(mode=0o700)
    factory = sessionmaker(state.engine, autoflush=False)
    with locked_report_storage_session(factory, reports._shared_report_write_lock, None, None):
        if damage == "invalid-journal":
            (evidence / "journal").write_bytes(journal_before)
            journal.write_bytes(verified_journal)
        elif damage == "ciphertext":
            (evidence / "ciphertext").write_bytes(image_before)
            destination.write_bytes(verified_image)
        else:
            unknown.rename(evidence / "unknown")
    ready = state.advance_monitor_then_ready()
    assert ready.status_code == 200, ready.text
    assert destination.read_bytes() == verified_image
    assert not journal.exists()
    state.defer_cleanup = False
    _, next_write = state.write()
    assert next_write.status_code == 201, next_write.text


@pytest.mark.parametrize("commit_succeeds", [True, False])
def test_runtime_recovery_waits_for_real_writer_transaction(runtime_app, commit_succeeds):
    state = runtime_app
    state.commit_gate = threading.Event()
    state.fail_commit = not commit_succeeds
    state.defer_cleanup = True
    report_id = uuid.uuid4()
    with ThreadPoolExecutor(max_workers=2) as pool:
        writer = pool.submit(state.write, report_id)
        try:
            assert state.commit_entered.wait(3)
            destination = state.upload / f"{report_id}.wse"
            image_before = destination.read_bytes()
            journal_before = state.journal(report_id).read_bytes()
            ready_call = pool.submit(state.advance_monitor_then_ready)
            assert state.recovery_attempted.wait(3)
            _assert_recovery_blocked(state)
            assert destination.read_bytes() == image_before
            assert state.journal(report_id).read_bytes() == journal_before
        finally:
            state.commit_gate.set()
        _, write_response = writer.result(3)
        assert write_response.status_code == (201 if commit_succeeds else 500)
        ready = ready_call.result(3)
    assert ready.status_code == 200, ready.text
    assert not state.journal(report_id).exists()
    assert destination.exists() is commit_succeeds
    if commit_succeeds:
        assert destination.read_bytes() == image_before


def test_ready_upload_probe_cannot_be_mistaken_for_unknown_storage(runtime_app, monkeypatch):
    state = runtime_app
    probe_created = threading.Event()
    allow_unlink = threading.Event()
    real_unlink = Path.unlink
    held = False

    def unlink(path, *args, **kwargs):
        nonlocal held
        if path.parent == state.upload and path.name.startswith(".readiness-") and not held:
            held = True
            probe_created.set()
            assert allow_unlink.wait(3), "owned upload probe gate must be released"
        return real_unlink(path, *args, **kwargs)

    monkeypatch.setattr(Path, "unlink", unlink)
    with ThreadPoolExecutor(max_workers=2) as pool:
        call = pool.submit(state.advance_monitor_then_ready)
        try:
            assert probe_created.wait(3)
            # Another replica's existing inventory/recovery must wait until the
            # background probe's temporary object is removed under the DB lock.
            state.recovery_attempted.clear()
            peer = pool.submit(health._report_storage_inventory_readiness,
                SimpleNamespace(upload_dir=state.upload, maintenance_lock_path=None, maintenance_lock_group_gid=None),
                SimpleNamespace(keyring=SimpleNamespace(slots=())),
            )
            assert state.recovery_attempted.wait(3)
            _assert_recovery_blocked(state)
            assert not peer.done()
        finally:
            allow_unlink.set()
        response = call.result(3)
        assert peer.result(3)["ready"] is True
    assert response.status_code == 200, response.text
    assert not list(state.upload.glob(".readiness-*"))


@pytest.mark.parametrize("environment", ("field", "staging", "production"))
def test_deployment_storage_http_write_recovers_on_lifespan_reentry(
    runtime_app, monkeypatch, environment,
):
    """Storage permission policies with real JPEG/SQL and the actual lifespan.

    Auth, camera and RAW startup hooks are outside this storage test. The
    backup-group lookup and encryption declarations are synthetic; this is
    neither a full deployment configuration nor a real backup-reader test.
    """
    from backend.app import main

    state = runtime_app
    state.upload.chmod(0o2750)
    assert state.upload.stat().st_gid > 0, "run with a non-root test group"
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", environment)
    monkeypatch.setattr(
        uploads.grp, "getgrnam",
        lambda _name: SimpleNamespace(gr_gid=state.upload.stat().st_gid),
    )
    state.settings.walksafe_environment = environment
    monkeypatch.setattr(main, "settings", state.settings)
    monkeypatch.setattr(main, "SessionLocal", sessionmaker(state.engine, autoflush=False))
    monkeypatch.setattr(main, "report_image_key_manager", state.manager)
    monkeypatch.setattr(main, "report_storage_state", state.storage_state)
    monkeypatch.setattr(main, "report_storage_monitor", state.monitor)
    monkeypatch.setattr(main, "capacity_monitor", None)
    monkeypatch.setattr(main, "inference_runner", None)
    for name in (
        "validate_admin_credential_issuer_binding", "bind_privacy_hmac_key",
        "bind_account_crypto_keys", "reconcile_raw_collection_storage",
        "start_detect_v2_health", "stop_detect_v2_health",
    ):
        monkeypatch.setattr(main, name, lambda *_args: None)
    complete = report_storage.complete_report_image_write

    def cleanup(pending):
        if state.defer_cleanup:
            raise OSError(errno.EIO, "owned journal cleanup fault")
        return complete(pending)

    monkeypatch.setattr(report_storage, "complete_report_image_write", cleanup)

    async def exercise():
        async with main.lifespan(state.app):
            state.defer_cleanup = True
            report_id, response = await asyncio.to_thread(state.write)
            assert response.status_code == 201, response.text
            journal = state.journal(report_id)
            assert journal.parent.stat().st_mode & 0o7777 == 0o700
            assert journal.stat().st_mode & 0o7777 == 0o600
            destination = state.upload / f"{report_id}.wse"
            ciphertext = destination.read_bytes()
            assert destination.stat().st_mode & 0o7777 == 0o640
        assert journal.exists()
        state.defer_cleanup = False
        async with main.lifespan(state.app):
            # Startup reconciliation must finish before requests are served.
            assert not journal.exists()
            assert destination.read_bytes() == ciphertext
            assert destination.stat().st_mode & 0o7777 == 0o640
            with Session(state.engine) as db:
                assert db.get(Report, report_id) is not None
                assert db.get(ReportImageObject, report_id) is not None
            assert (await asyncio.to_thread(state.ready)).status_code == 200
            _, next_response = await asyncio.to_thread(state.write)
            assert next_response.status_code == 201, next_response.text
        assert not list(state.upload.glob(".readiness-*"))

    asyncio.run(exercise())
