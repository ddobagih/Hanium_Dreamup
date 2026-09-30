from __future__ import annotations

import asyncio
from contextlib import contextmanager
from dataclasses import replace
import os
from pathlib import Path
import uuid

import pytest

from backend.app.services import report_storage as storage
from backend.app.services import report_storage_monitor as monitor_module
from backend.app.services.report_image_crypto import encrypt_report_image
from backend.app.services.storage_readiness_state import StorageReadinessState


class Harness:
    def __init__(self, root, monkeypatch):
        root.chmod(0o700)
        self.root = root
        self.rows = {}
        self.held = False
        self.now = 100.0
        self.encryption = {"ready": True}
        self.incidents = []
        self.hashes = []
        self.pages = []
        self.state = StorageReadinessState()

        @contextmanager
        def session():
            assert not self.held
            self.held = True
            try:
                yield object()
            finally:
                self.held = False

        def read(_db, *, after=None, limit=16, report_ids=None):
            assert self.held
            assert limit == 16
            self.pages.append((after, report_ids))
            rows = sorted(self.rows.values(), key=lambda entry: entry.report_id)
            return [entry for entry in rows if (after is None or entry.report_id > after)
                    and (report_ids is None or entry.report_id in report_ids)][:limit]

        actual_validate = storage.validate_report_image_write

        def hash_outside_lock(pending, metadata):
            assert not self.held, "ciphertext hashing must not hold the storage lock"
            self.hashes.append(pending.report_id)
            actual_validate(pending, metadata)

        monkeypatch.setattr(monitor_module, "read_report_storage_inventory_page", read)
        monkeypatch.setattr(monitor_module, "recover_pending_report_writes", lambda *_args, **_kwargs: None)
        monkeypatch.setattr(monitor_module, "validate_report_image_write", hash_outside_lock)
        self.monitor = monitor_module.ReportStorageMonitor(
            upload_root=root, state=self.state, session=session,
            known_key_states=lambda: {"test-key": "active"},
            upload_check=lambda: {"ready": True},
            encryption_check=lambda: dict(self.encryption),
            record_incident=lambda _db, mismatch: self.incidents.append(mismatch.evidence_sha256),
            refresh_seconds=1, max_age_seconds=30, clock=lambda: self.now,
        )
        self.monitor.restart()

    def add(self):
        report_id = uuid.uuid4()
        encrypted = encrypt_report_image(
            b"\xff\xd8\xffmonitor", report_id=report_id, content_type="image/jpeg",
            key_id="test-key", key=bytes(range(32)),
        )
        pending = storage.stage_report_image(self.root / f"{report_id}.wse", encrypted)
        storage.complete_report_image_write(pending)
        metadata = storage.ReportImageCommitMetadata(
            storage_name=pending.destination.name, envelope_version=1,
            algorithm="AES-256-GCM", aad_version=1, key_id=encrypted.key_id,
            nonce=encrypted.nonce, envelope_sha256=encrypted.envelope_sha256,
            envelope_size=len(encrypted.envelope), plaintext_sha256=encrypted.plaintext_sha256,
            plaintext_size=encrypted.plaintext_length, content_type=encrypted.content_type,
        )
        self.rows[report_id] = storage.ReportStorageInventoryEntry(report_id, f"/uploads/{report_id}.jpg", "image/jpeg", metadata)
        return pending

    def cycle(self):
        for _ in range(100):
            if self.monitor.run_batch():
                return self.monitor.checks()["report_storage"]
        raise AssertionError("inventory did not complete in bounded batches")


@pytest.fixture
def harness(tmp_path, monkeypatch):
    item = Harness(tmp_path, monkeypatch)
    yield item
    item.monitor.stop()
    item.monitor.close_cycle()


def test_unseeded_partial_and_stopped_fail_closed(harness):
    h = harness
    for _ in range(17):
        h.add()
    assert h.monitor.checks()["report_storage"]["reason"] == "report_storage_not_checked"
    assert not h.monitor.run_batch()  # recovery / prerequisites
    assert not h.monitor.run_batch()  # first sixteen rows
    assert len(h.hashes) == 16
    assert h.monitor.checks()["report_storage"]["ready"] is False
    assert h.cycle()["ready"] is True
    assert len(h.hashes) == 17
    h.monitor.stop()
    assert h.monitor.checks()["report_storage"]["reason"] == "report_storage_monitor_stopped"


def test_unchanged_cache_rechecks_metadata_namespace_without_rehash(harness):
    h = harness
    for _ in range(17):
        h.add()
    assert h.cycle()["ready"] is True
    h.hashes.clear()
    h.pages.clear()
    h.now += 1
    assert h.cycle()["ready"] is True
    assert h.hashes == []
    assert len(h.pages) == 4  # two bounded pages, each read again before acceptance
    assert not h.state.snapshot().raw.ready


def test_same_size_tamper_with_restored_mtime_is_detected_and_preserved(harness):
    h = harness
    pending = h.add()
    assert h.cycle()["ready"] is True
    before = pending.destination.stat()
    content = bytearray(pending.destination.read_bytes())
    content[-1] ^= 1
    pending.destination.write_bytes(content)
    os.utime(pending.destination, ns=(before.st_atime_ns, before.st_mtime_ns))
    h.hashes.clear()
    assert h.cycle()["reason"] == "report_storage_inventory_invalid"
    assert h.hashes == [pending.report_id]
    assert pending.destination.read_bytes() == content


@pytest.mark.parametrize("mutation", ["row_delete", "object_delete", "orphan_object", "metadata"])
def test_external_database_and_namespace_changes_are_not_hidden_by_cache(harness, mutation):
    h = harness
    pending = h.add()
    assert h.cycle()["ready"] is True
    if mutation == "row_delete":
        del h.rows[pending.report_id]
    elif mutation == "object_delete":
        pending.destination.unlink()
    elif mutation == "orphan_object":
        extra = h.add()
        del h.rows[extra.report_id]
    else:
        row = h.rows[pending.report_id]
        h.rows[pending.report_id] = replace(row, image_object=replace(row.image_object, envelope_sha256="0" * 64))
    assert h.cycle()["reason"] == "report_storage_inventory_invalid"
    assert bool(h.incidents) == (mutation != "metadata")


def test_partial_and_late_completion_do_not_extend_oldest_coverage(harness):
    h = harness
    h.add()
    assert h.cycle()["ready"] is True
    h.now += 29
    assert not h.monitor.run_batch()
    h.now += 2
    assert h.monitor.checks()["report_storage"]["reason"] == "report_storage_inventory_expired"
    h.now += 29
    assert h.cycle()["reason"] == "report_storage_inventory_expired"
    # Completion at 160 retains cycle start 129, never completion time.
    h.now += 1
    assert h.cycle()["ready"] is True


def test_key_provider_change_is_visible_without_http_provider_io(harness):
    h = harness
    h.add()
    assert h.cycle()["ready"] is True
    h.encryption = {"ready": False, "reason": "report_image_keyring_restart_required"}
    assert h.cycle()["ready"] is False
    assert h.monitor.checks()["report_image_encryption"] == h.encryption


def test_uncertain_writer_or_shutdown_during_hash_cannot_publish_success(harness, monkeypatch):
    h = harness
    h.add()
    actual = monitor_module.validate_report_image_write

    def write_while_hashing(*args):
        actual(*args)
        token = h.state.begin_commit("report", "concurrent")
        h.state.publish_failure(token, "unknown_commit")

    monkeypatch.setattr(monitor_module, "validate_report_image_write", write_while_hashing)
    assert not h.monitor.run_batch()
    assert not h.monitor.run_batch()
    assert h.monitor.checks()["report_storage"]["ready"] is False
    monkeypatch.setattr(monitor_module, "validate_report_image_write", actual)
    assert h.cycle()["ready"] is True
    h.state.stop_report_inventory()
    assert not h.state.publish_report_inventory_success(h.state.report_inventory_revision(), h.now)


def test_cooperative_budget_yields_between_hashes_without_freshening(harness, monkeypatch):
    h = harness
    for _ in range(3):
        h.add()
    actual = monitor_module.validate_report_image_write

    def slow_hash(*args):
        actual(*args)
        h.now += 1.1

    monkeypatch.setattr(monitor_module, "validate_report_image_write", slow_hash)
    assert not h.monitor.run_batch()
    assert not h.monitor.run_batch()
    assert len(h.hashes) == 1
    assert h.monitor.checks()["report_storage"]["ready"] is False
    assert not h.monitor.run_batch()
    assert len(h.hashes) == 2
    assert h.cycle()["ready"] is True


def test_run_continues_incomplete_batches_without_refresh_sleep(harness, monkeypatch):
    h = harness
    stop = asyncio.Event()
    called = []

    def batch():
        called.append(1)
        if len(called) == 4:
            stop.set()
        return False

    monkeypatch.setattr(h.monitor, "run_batch", batch)
    asyncio.run(asyncio.wait_for(h.monitor.run(stop), timeout=0.5))
    assert len(called) == 4


def test_http_reads_memory_and_unconfigured_router_fails_closed(harness, monkeypatch):
    from fastapi import FastAPI
    from types import SimpleNamespace
    from asgi_client import ASGITestClient
    from backend.app.api import health

    h = harness
    assert h.cycle()["ready"] is True
    settings = SimpleNamespace(readiness_inventory_cache_seconds=0, walksafe_source_commit=None)
    for name in ("_database_and_admin_readiness", "_privacy_hmac_binding_readiness", "_account_crypto_binding_readiness", "_raw_object_readiness", "_detector_readiness", "_account_enrollment_config_readiness"):
        monkeypatch.setattr(health, name, lambda *_args, **_kwargs: {"ready": True})

    async def navigation(*_args, **_kwargs):
        return {"ready": True}

    def forbidden(*_args, **_kwargs):
        raise AssertionError("HTTP readiness must not inspect report storage or key provider")

    monkeypatch.setattr(health, "_navigation_readiness", navigation)
    for name in ("_upload_readiness", "_locked_upload_readiness", "_report_image_encryption_readiness", "_report_storage_inventory_readiness"):
        monkeypatch.setattr(health, name, forbidden)
    monkeypatch.setattr(h.monitor, "_encryption_check", forbidden)
    monkeypatch.setattr(h.monitor, "_session", forbidden)
    app = FastAPI()
    app.include_router(health.create_router(settings, lambda: None, object(), report_storage_monitor=h.monitor))
    for _ in range(3):
        response = ASGITestClient(app).get("/ready")
        assert response.status_code == 200, response.text
    unconfigured = FastAPI()
    unconfigured.include_router(health.create_router(settings, lambda: None, object()))
    response = ASGITestClient(unconfigured).get("/ready")
    assert response.status_code == 503
    assert response.json()["checks"]["report_storage"]["reason"] == "report_storage_not_checked"


def test_cancelled_owner_waits_for_active_hash_before_closing_fd(harness, monkeypatch):
    import threading
    h = harness
    h.add()
    started = threading.Event()
    release = threading.Event()
    actual = monitor_module.validate_report_image_write
    seen = []

    def held_hash(pending, metadata):
        descriptor = h.monitor._descriptor
        started.set()
        assert release.wait(2)
        os.fstat(descriptor)
        actual(pending, metadata)
        seen.append(descriptor)

    monkeypatch.setattr(monitor_module, "validate_report_image_write", held_hash)

    async def exercise():
        stop = asyncio.Event()
        task = asyncio.create_task(h.monitor.run(stop))
        try:
            for _ in range(200):
                if started.is_set():
                    break
                await asyncio.sleep(0.005)
            assert started.is_set()
            task.cancel()
            await asyncio.sleep(0.02)
            assert not task.done()
            assert h.monitor.checks()["report_storage"]["reason"] == "report_storage_monitor_stopped"
        finally:
            release.set()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(exercise())
    assert len(seen) == 1
    with pytest.raises(OSError):
        os.fstat(seen[0])
    assert h.monitor._descriptor is None


def test_startup_seed_cannot_erase_write_failure_after_full_scan(harness):
    h = harness
    revision = h.state.report_inventory_revision()
    token = h.state.begin_commit("report", "late-writer")
    h.state.publish_failure(token, "unknown_commit")
    h.monitor.seed({}, coverage_started=h.now, upload={"ready": True}, encryption={"ready": True}, revision=revision)
    assert not h.monitor.checks()["report_storage"]["ready"]
    assert len(h.state.snapshot().report.unresolved_failures) == 1


def test_monitor_shutdown_failure_still_closes_other_lifespan_resources(monkeypatch):
    from backend.app import main
    events = []
    for name in ("validate_admin_credential_issuer_binding", "bind_privacy_hmac_key", "bind_account_crypto_keys", "reconcile_report_storage", "reconcile_raw_collection_storage", "start_detect_v2_health"):
        monkeypatch.setattr(main, name, lambda *_args: None)
    monkeypatch.setattr(main, "capacity_monitor", None)
    monkeypatch.setattr(main, "inference_runner", None)
    monkeypatch.setattr(main, "stop_detect_v2_health", lambda: events.append("detector-closed"))
    monkeypatch.setattr(main.detect, "close_detection_preprocessing", lambda: events.append("preprocessing-closed"))
    monkeypatch.setattr(main.report_storage_monitor, "stop", lambda: events.append("monitor-stopped"))

    async def failed_monitor(stop):
        await stop.wait()
        raise RuntimeError("owned monitor shutdown failure")

    class Runtime:
        def __init__(self, **_kwargs):
            pass
        async def start(self):
            pass
        def stop_admission(self):
            events.append("tmap-stopped")
        async def aclose(self):
            events.append("tmap-closed")

    monkeypatch.setattr(main.report_storage_monitor, "run", failed_monitor)
    monkeypatch.setattr(main, "TmapRuntime", Runtime)

    async def exercise():
        with pytest.raises(RuntimeError, match="owned monitor shutdown failure"):
            async with main.lifespan(main.app):
                pass

    asyncio.run(exercise())
    assert events == ["monitor-stopped", "tmap-stopped", "tmap-closed", "detector-closed", "preprocessing-closed"]
    assert main.app.state.tmap_runtime is None


@pytest.mark.parametrize("phase", ["metadata", "recheck", "namespace"])
def test_slow_returning_metadata_and_namespace_yield_inside_one_second_budget(harness, monkeypatch, phase):
    h = harness
    for _ in range(16):
        h.add()
    original_session = h.monitor._session
    held_seconds = []

    @contextmanager
    def measured_session():
        started = h.now
        with original_session() as db:
            yield db
        held_seconds.append(h.now - started)

    monkeypatch.setattr(h.monitor, "_session", measured_session)
    original_fingerprint = monitor_module.report_storage_object_fingerprint
    original_namespace = monitor_module.validate_report_storage_namespace_entry
    seen = []

    def fingerprint(*args):
        result = original_fingerprint(*args)
        rechecking = h.monitor._row_work is not None
        if (phase == "metadata" and not rechecking) or (phase == "recheck" and rechecking):
            h.now += 0.25
            seen.append(args[0].report_id)
        return result

    def namespace(*args):
        result = original_namespace(*args)
        h.now += 0.25
        seen.append(args[1])
        return result

    if phase == "namespace":
        monkeypatch.setattr(monitor_module, "validate_report_storage_namespace_entry", namespace)
    else:
        monkeypatch.setattr(monitor_module, "report_storage_object_fingerprint", fingerprint)
    assert h.cycle()["ready"] is True
    assert len(seen) == 16
    assert max(held_seconds) <= 1.0
    assert sum(value > 0 for value in held_seconds) >= 4


def test_report_monitor_defaults_are_explicit_and_do_not_change_raw_cache(monkeypatch):
    from backend.app.config import Settings
    monkeypatch.delenv("WALKSAFE_REPORT_STORAGE_REFRESH_SECONDS", raising=False)
    monkeypatch.delenv("WALKSAFE_REPORT_STORAGE_MAX_AGE_SECONDS", raising=False)
    monkeypatch.setenv("WALKSAFE_READINESS_INVENTORY_CACHE_SECONDS", "0")
    settings = Settings()
    assert settings.report_storage_refresh_seconds == 1
    assert settings.report_storage_max_age_seconds == 30
    assert settings.readiness_inventory_cache_seconds == 0


@pytest.mark.parametrize("refresh,maximum", [("0", "30"), ("nan", "30"), ("1", "inf"), ("31", "30")])
def test_report_monitor_rejects_unbounded_or_inconsistent_config(monkeypatch, refresh, maximum):
    from backend.app.config import Settings
    monkeypatch.setenv("WALKSAFE_REPORT_STORAGE_REFRESH_SECONDS", refresh)
    monkeypatch.setenv("WALKSAFE_REPORT_STORAGE_MAX_AGE_SECONDS", maximum)
    with pytest.raises(ValueError):
        Settings()


@pytest.mark.parametrize("local", [True, False])
def test_successful_write_between_every_batch_does_not_starve_coverage(harness, local):
    h = harness
    for _ in range(16):
        h.add()
    for batch in range(64):
        if h.monitor.run_batch():
            break
        pending = h.add()
        if local:
            token = h.state.begin_commit("report", str(pending.report_id))
            h.state.publish_success(token)
        h.now += 0.5
    else:
        pytest.fail("healthy writes starved the complete inventory walk")
    assert batch < 30
    assert h.monitor.checks()["report_storage"]["ready"] is True
    assert len(h.rows) == 16 + batch


def test_late_unknown_namespace_entry_cannot_hide_behind_healthy_writer(harness):
    h = harness
    h.add()
    while h.monitor._phase != "finish":
        assert not h.monitor.run_batch()
    unknown = h.add()
    del h.rows[unknown.report_id]
    healthy = h.add()
    token = h.state.begin_commit("report", str(healthy.report_id))
    h.state.publish_success(token)
    before = unknown.destination.read_bytes()
    assert h.cycle()["reason"] == "report_storage_inventory_invalid"
    assert h.incidents
    assert unknown.destination.read_bytes() == before


def test_changed_ciphertext_during_hash_never_publishes_success(harness, monkeypatch):
    h = harness
    pending = h.add()
    original = monitor_module.validate_report_image_write

    def tamper_after_hash(*args):
        original(*args)
        content = bytearray(pending.destination.read_bytes())
        content[-1] ^= 1
        pending.destination.write_bytes(content)

    monkeypatch.setattr(monitor_module, "validate_report_image_write", tamper_after_hash)
    assert not h.monitor.run_batch()
    assert not h.monitor.run_batch()
    assert h.monitor.checks()["report_storage"]["ready"] is False
    monkeypatch.setattr(monitor_module, "validate_report_image_write", original)
    assert h.cycle()["reason"] == "report_storage_inventory_invalid"


def test_new_names_behind_cursor_keep_budget_and_progress(harness, monkeypatch):
    h = harness
    h.add()
    while h.monitor._phase != "finish":
        assert not h.monitor.run_batch()
    for _ in range(16):
        h.add()
    original = monitor_module.report_storage_object_fingerprint
    session = h.monitor._session
    held = []

    def slow_metadata(*args):
        result = original(*args)
        h.now += 0.25
        return result

    @contextmanager
    def measured():
        start = h.now
        with session() as db:
            yield db
        held.append(h.now - start)

    monkeypatch.setattr(monitor_module, "report_storage_object_fingerprint", slow_metadata)
    monkeypatch.setattr(h.monitor, "_session", measured)
    assert h.cycle()["ready"] is True
    assert max(held) <= 1
    assert len(h.monitor._verified) == 17
