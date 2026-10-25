from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import errno
from pathlib import Path
import threading
from types import SimpleNamespace
import uuid

import pytest

from backend.app.services import health_readiness as health
from backend.app.models import Report, ReportImageObject
from backend.app.services import report_storage, report_commit
from backend.app.services.report_image_crypto import encrypt_report_image


def _stage(root: Path):
    report_id = uuid.uuid4()
    encrypted = encrypt_report_image(
        b"\xff\xd8\xffruntime-recovery",
        report_id=report_id,
        content_type="image/jpeg",
        key_id="test-key",
        key=bytes(range(32)),
    )
    pending = report_storage.stage_report_image(
        root / f"{report_id}.wse",
        encrypted,
    )
    image_object = SimpleNamespace(
        report_id=report_id,
        storage_name=pending.destination.name,
        envelope_version=1,
        algorithm="AES-256-GCM",
        aad_version=1,
        key_id=encrypted.key_id,
        nonce=encrypted.nonce,
        envelope_sha256=encrypted.envelope_sha256,
        envelope_size=len(encrypted.envelope),
        plaintext_sha256=encrypted.plaintext_sha256,
        plaintext_size=encrypted.plaintext_length,
        content_type=encrypted.content_type,
    )
    report = SimpleNamespace(
        id=report_id,
        image_path=f"/uploads/{report_id}.jpg",
        image_content_type="image/jpeg",
    )
    return pending, report, image_object


def _offline_readiness(
    monkeypatch: pytest.MonkeyPatch,
    root: Path,
    records: list[tuple[object, object]],
):
    held = False
    lookups = {
        (model, record.id if model is Report else image.report_id): record if model is Report else image
        for record, image in records
        for model in (Report, ReportImageObject)
    }

    class FakeSession:
        def execute(self, _statement, _parameters) -> None:
            assert held

        def get(self, model, report_id):
            assert held
            return lookups.get((model, report_id))

        def scalars(self, statement):
            assert held
            entity = statement.column_descriptions[0]["entity"]
            if entity is Report:
                rows = [record for record, _image in records]
            elif entity is ReportImageObject:
                rows = [image for _record, image in records]
            else:
                raise AssertionError("unexpected database query")
            return SimpleNamespace(all=lambda: rows)

    @contextmanager
    def transaction():
        yield FakeSession()

    @contextmanager
    def maintenance_lock(_path, *, expected_group_gid):
        nonlocal held
        assert not held
        held = True
        try:
            yield
        finally:
            held = False

    monkeypatch.setattr(health, "SessionLocal", SimpleNamespace(begin=transaction))
    monkeypatch.setattr(
        health,
        "shared_maintenance_write_lock",
        maintenance_lock,
    )
    settings = SimpleNamespace(
        upload_dir=root,
        maintenance_lock_path=None,
        maintenance_lock_group_gid=None,
    )
    manager = SimpleNamespace(
        keyring=SimpleNamespace(
            slots=(SimpleNamespace(key_id="test-key", state="active"),),
        )
    )
    return settings, manager


def test_next_readiness_recovers_transient_postcommit_unlink_without_restart(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    pending, report, image_object = _stage(tmp_path)
    settings, manager = _offline_readiness(
        monkeypatch, tmp_path, [(report, image_object)],
    )
    original_remove = report_storage.remove_image_file
    failures_remaining = 2

    def flaky_remove(path: Path) -> None:
        nonlocal failures_remaining
        if path == pending.journal_path and failures_remaining:
            failures_remaining -= 1
            raise OSError(errno.EIO, "transient journal unlink failure")
        original_remove(path)

    monkeypatch.setattr(report_storage, "remove_image_file", flaky_remove)
    with pytest.raises(OSError):
        report_storage.complete_report_image_write(pending)
    assert pending.journal_path.exists()

    first = health._report_storage_inventory_readiness(settings, manager)
    assert first == {
        "ready": False,
        "reason": "report_storage_recovery_retryable",
    }
    assert pending.journal_path.exists()

    second = health._report_storage_inventory_readiness(settings, manager)
    assert second == {
        "ready": True,
        "inventory": "database_and_encrypted_objects_matched",
    }
    assert not pending.journal_path.exists()
    assert pending.destination.exists()


def test_unverifiable_committed_journal_fails_closed_and_preserves_evidence(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    pending, report, image_object = _stage(tmp_path)
    image_object.envelope_sha256 = "0" * 64
    journal_before = pending.journal_path.read_bytes()
    image_before = pending.destination.read_bytes()
    settings, manager = _offline_readiness(
        monkeypatch, tmp_path, [(report, image_object)],
    )

    result = health._report_storage_inventory_readiness(settings, manager)

    assert result == {
        "ready": False,
        "reason": "report_storage_journal_unresolved",
    }
    assert pending.journal_path.read_bytes() == journal_before
    assert pending.destination.read_bytes() == image_before


def test_runtime_item_budget_defers_remaining_journals(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    staged = [_stage(tmp_path), _stage(tmp_path)]
    settings, manager = _offline_readiness(
        monkeypatch,
        tmp_path,
        [(report, image) for _pending, report, image in staged],
    )
    monkeypatch.setattr(health, "RUNTIME_REPORT_RECOVERY_MAX_ITEMS", 1)

    first = health._report_storage_inventory_readiness(settings, manager)
    assert first == {"ready": False, "reason": "report_storage_recovery_deferred"}
    assert sum(pending.journal_path.exists() for pending, _, _ in staged) == 1

    second = health._report_storage_inventory_readiness(settings, manager)
    assert second["ready"] is True
    assert all(not pending.journal_path.exists() for pending, _, _ in staged)


def test_broken_journal_symlink_fails_closed_without_discarding_evidence(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    journal_dir = report_storage.pending_journal_directory(tmp_path)
    journal_dir.symlink_to(tmp_path / "missing-journal", target_is_directory=True)
    settings, manager = _offline_readiness(monkeypatch, tmp_path, [])

    result = health._report_storage_inventory_readiness(settings, manager)

    assert result == {
        "ready": False,
        "reason": "report_storage_journal_unresolved",
    }
    assert journal_dir.is_symlink()


@pytest.mark.parametrize("commit_succeeds", [True, False])
def test_runtime_recovery_waits_for_inflight_write_transaction(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    commit_succeeds: bool,
) -> None:
    """Use real image I/O and write/recovery paths with a modeled DB lock."""
    tmp_path.chmod(0o700)
    transaction_lock = threading.Lock()
    maintenance_state = threading.local()
    commit_entered = threading.Event()
    allow_commit = threading.Event()
    recovery_waiting = threading.Event()
    committed = threading.Event()
    rows: list[object] = []
    report_id = uuid.uuid4()
    report = SimpleNamespace(
        id=report_id,
        image_path=f"/uploads/{report_id}.jpg",
        image_content_type="image/jpeg",
    )
    destination = tmp_path / f"{report_id}.wse"
    lock_path = tmp_path / "maintenance.lock"

    @contextmanager
    def maintenance_lock(path, *, expected_group_gid):
        assert path == lock_path
        assert expected_group_gid is None
        maintenance_state.held = True
        try:
            yield
        finally:
            maintenance_state.held = False

    def check_lock(statement, parameters, *, shared: bool) -> None:
        assert maintenance_state.held
        function = "pg_advisory_xact_lock_shared(" if shared else "pg_advisory_xact_lock("
        assert function in str(statement)
        assert parameters == {"lock_key": report_storage.REPORT_STORAGE_TRANSACTION_LOCK_KEY}

    def writer_execute(statement, parameters) -> None:
        check_lock(statement, parameters, shared=True)
        assert transaction_lock.acquire(timeout=3)

    def commit() -> None:
        commit_entered.set()
        assert allow_commit.wait(timeout=3)
        if not commit_succeeds:
            raise RuntimeError("injected commit failure")
        committed.set()
        transaction_lock.release()

    def deferred_cleanup(_pending) -> None:
        raise OSError(errno.EIO, "injected postcommit cleanup failure")

    writer_session = SimpleNamespace(
        execute=writer_execute,
        add=rows.append,
        commit=commit,
        rollback=transaction_lock.release,
        refresh=lambda _report: None,
    )

    class RecoverySession:
        locked = False

        def execute(self, statement, parameters) -> None:
            check_lock(statement, parameters, shared=False)
            if not self.locked:
                recovery_waiting.set()
                assert transaction_lock.acquire(timeout=3)
                self.locked = True

        def get(self, model, identifier):
            assert self.locked
            assert identifier == report_id
            if not committed.is_set():
                return None
            if model is Report:
                return report
            assert model is ReportImageObject
            return next(row for row in rows if isinstance(row, ReportImageObject))

        def scalars(self, statement):
            model = statement.column_descriptions[0]["entity"]
            row = self.get(model, report_id)
            return SimpleNamespace(all=lambda: [] if row is None else [row])

    @contextmanager
    def transaction():
        db = RecoverySession()
        try:
            yield db
        finally:
            if db.locked:
                transaction_lock.release()

    monkeypatch.setattr(health, "SessionLocal", SimpleNamespace(begin=transaction))
    monkeypatch.setattr(health, "shared_maintenance_write_lock", maintenance_lock)
    monkeypatch.setattr(report_commit, "_shared_report_write_lock", maintenance_lock)
    monkeypatch.setattr(report_commit, "complete_report_image_write", deferred_cleanup)
    settings = SimpleNamespace(
        upload_dir=tmp_path,
        maintenance_lock_path=lock_path,
        maintenance_lock_group_gid=None,
    )
    manager = SimpleNamespace(
        synchronize=lambda _db: None,
        encryption_slot=lambda: SimpleNamespace(key_id="test-key", material=bytes(range(32))),
        keyring=SimpleNamespace(slots=(SimpleNamespace(key_id="test-key", state="active"),)),
    )

    with ThreadPoolExecutor(max_workers=2) as pool:
        writer = pool.submit(
            report_commit._commit_new_report_with_image,
            writer_session,
            report,
            destination=destination,
            content=b"inflight-runtime-recovery",
            content_type="image/jpeg",
            key_manager=manager,
            maintenance_lock_path=lock_path,
        )
        try:
            if not commit_entered.wait(timeout=3):
                writer.result(timeout=3)
                pytest.fail("writer never reached the commit boundary")
            image_before = destination.read_bytes()
            journal = report_storage.pending_journal_directory(tmp_path) / f"{report_id}.json"
            journal_before = journal.read_bytes()
            recovery = pool.submit(health._report_storage_inventory_readiness, settings, manager)
            assert recovery_waiting.wait(timeout=3)
            assert not recovery.done()
            assert destination.read_bytes() == image_before
            assert journal.read_bytes() == journal_before
        finally:
            allow_commit.set()
        if commit_succeeds:
            writer.result(timeout=3)
        else:
            with pytest.raises(RuntimeError, match="injected commit failure"):
                writer.result(timeout=3)
        assert recovery.result(timeout=3) == {
            "ready": True,
            "inventory": "database_and_encrypted_objects_matched",
        }

    assert not journal.exists()
    if commit_succeeds:
        assert destination.read_bytes() == image_before
    else:
        assert not destination.exists()
