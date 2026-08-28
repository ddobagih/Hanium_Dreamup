from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import UTC, datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
from types import SimpleNamespace
import uuid

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import select

import backend.app.main as main_app
import backend.app.services.report_storage as report_storage
import backend.app.uploads as uploads
from backend.app.database import SessionLocal
from backend.app.api.reports import _commit_new_report_with_image
from backend.app.models import Report, ReportImageObject
from backend.app.services.report_image_crypto import encrypt_report_image
from backend.app.services.report_storage import (
    ReportImageCommitMetadata,
    ReportStorageCommitState,
    ReportStorageInventoryEntry,
    pending_journal_directory,
    reconcile_pending_report_writes,
    stage_report_image,
    lock_report_storage_reconciliation_transaction,
    validate_report_storage_inventory,
)


KEY = bytes(range(32))
ROOT = Path(__file__).resolve().parents[2]
TEST_DATABASE_CONFIGURED = bool(
    os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip()
)


@pytest.fixture(scope="module", autouse=True)
def migrated_database() -> None:
    if TEST_DATABASE_CONFIGURED:
        command.upgrade(Config(str(ROOT / "backend" / "alembic.ini")), "head")


class StaticKeyManager:
    def synchronize(self, _db) -> None:
        return None

    def encryption_slot(self):
        return SimpleNamespace(key_id="test-key-v1", material=KEY)


def _encrypted(report_id: uuid.UUID, content: bytes = b"report-image"):
    return encrypt_report_image(
        content,
        report_id=report_id,
        content_type="image/jpeg",
        key_id="test-key-v1",
        key=KEY,
    )


def _committed_metadata(encrypted) -> ReportImageCommitMetadata:
    return ReportImageCommitMetadata(
        storage_name=f"{encrypted.report_id}.wse",
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


def _absent(_report_id: uuid.UUID) -> ReportStorageCommitState:
    return ReportStorageCommitState(report_exists=False, image_object=None)


def test_process_death_after_durable_envelope_rename_is_reconciled(tmp_path: Path) -> None:
    report_id = uuid.uuid4()
    destination = tmp_path / f"{report_id}.wse"
    code = """
import os
from pathlib import Path
import sys
import uuid
from backend.app.services.report_image_crypto import encrypt_report_image
from backend.app.services.report_storage import stage_report_image
report_id = uuid.UUID(sys.argv[2])
encrypted = encrypt_report_image(
    b'crash-window-image', report_id=report_id, content_type='image/jpeg',
    key_id='test-key-v1', key=bytes(range(32)),
)
stage_report_image(Path(sys.argv[1]), encrypted)
os._exit(77)
"""
    environment = dict(os.environ)
    environment["PYTHONPATH"] = str(Path(__file__).resolve().parents[2])

    crashed = subprocess.run(
        [sys.executable, "-c", code, str(destination), str(report_id)],
        env=environment,
        check=False,
    )

    assert crashed.returncode == 77
    assert destination.read_bytes().startswith(b"WSRI")
    assert b"crash-window-image" not in destination.read_bytes()
    assert len(list(pending_journal_directory(tmp_path).glob("*.json"))) == 1

    result = reconcile_pending_report_writes(tmp_path, _absent)

    assert result.removed_orphans == 1
    assert result.retained_committed == 0
    assert not destination.exists()
    assert list(pending_journal_directory(tmp_path).glob("*.json")) == []


def test_restart_keeps_only_exact_committed_envelope(tmp_path: Path) -> None:
    report_id = uuid.uuid4()
    encrypted = _encrypted(report_id, b"committed-image")
    destination = tmp_path / f"{report_id}.wse"
    stage_report_image(destination, encrypted)

    result = reconcile_pending_report_writes(
        tmp_path,
        lambda candidate: ReportStorageCommitState(
            report_exists=candidate == report_id,
            image_object=_committed_metadata(encrypted),
        ),
    )

    assert result.retained_committed == 1
    assert result.removed_orphans == 0
    assert destination.read_bytes() == encrypted.envelope
    assert list(pending_journal_directory(tmp_path).glob("*.json")) == []


@pytest.mark.parametrize("field", ["envelope_sha256", "key_id", "plaintext_size"])
def test_committed_metadata_mismatch_fails_without_deleting_evidence(
    tmp_path: Path,
    field: str,
) -> None:
    report_id = uuid.uuid4()
    encrypted = _encrypted(report_id)
    destination = tmp_path / f"{report_id}.wse"
    stage_report_image(destination, encrypted)
    metadata = _committed_metadata(encrypted)
    replacement = {
        "envelope_sha256": "0" * 64,
        "key_id": "different-key",
        "plaintext_size": metadata.plaintext_size + 1,
    }[field]
    mismatched = ReportImageCommitMetadata(
        **{**metadata.__dict__, field: replacement}
    )

    with pytest.raises(RuntimeError, match="metadata"):
        reconcile_pending_report_writes(
            tmp_path,
            lambda _candidate: ReportStorageCommitState(True, mismatched),
        )

    assert destination.exists()
    assert len(list(pending_journal_directory(tmp_path).glob("*.json"))) == 1


def test_tampered_committed_envelope_fails_without_deleting_evidence(tmp_path: Path) -> None:
    report_id = uuid.uuid4()
    encrypted = _encrypted(report_id)
    destination = tmp_path / f"{report_id}.wse"
    stage_report_image(destination, encrypted)
    tampered = bytearray(destination.read_bytes())
    tampered[-1] ^= 1
    destination.write_bytes(tampered)
    destination.chmod(0o600)

    with pytest.raises(RuntimeError, match="digest"):
        reconcile_pending_report_writes(
            tmp_path,
            lambda _candidate: ReportStorageCommitState(True, _committed_metadata(encrypted)),
        )

    assert destination.exists()
    assert len(list(pending_journal_directory(tmp_path).glob("*.json"))) == 1


def test_committed_v1_plaintext_journal_requires_offline_migration(tmp_path: Path) -> None:
    report_id = uuid.uuid4()
    plaintext = tmp_path / f"{report_id}.jpg"
    plaintext.write_bytes(b"legacy-plaintext")
    journal_dir = pending_journal_directory(tmp_path)
    journal_dir.mkdir(mode=0o700)
    journal = journal_dir / f"{report_id}.json"
    journal.write_text(
        json.dumps(
            {
                "schema_version": "walksafe.report-write.v1",
                "report_id": str(report_id),
                "image_filename": plaintext.name,
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(RuntimeError, match="offline migration"):
        reconcile_pending_report_writes(
            tmp_path,
            lambda _candidate: ReportStorageCommitState(True, None),
        )

    assert plaintext.read_bytes() == b"legacy-plaintext"
    assert journal.exists()


def test_orphan_v1_plaintext_journal_requires_offline_migration(tmp_path: Path) -> None:
    report_id = uuid.uuid4()
    plaintext = tmp_path / f"{report_id}.jpg"
    plaintext.write_bytes(b"orphan")
    journal_dir = pending_journal_directory(tmp_path)
    journal_dir.mkdir(mode=0o700)
    (journal_dir / f"{report_id}.json").write_text(
        json.dumps(
            {
                "schema_version": "walksafe.report-write.v1",
                "report_id": str(report_id),
                "image_filename": plaintext.name,
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(RuntimeError, match="offline migration"):
        reconcile_pending_report_writes(tmp_path, _absent)

    assert plaintext.read_bytes() == b"orphan"
    assert (journal_dir / f"{report_id}.json").exists()


def test_corrupt_journal_fails_startup_without_deleting_evidence(tmp_path: Path) -> None:
    journal_dir = pending_journal_directory(tmp_path)
    journal_dir.mkdir(mode=0o700)
    corrupt = journal_dir / f"{uuid.uuid4()}.json"
    corrupt.write_text("not-json", encoding="utf-8")

    with pytest.raises(RuntimeError, match="invalid report write journal"):
        reconcile_pending_report_writes(tmp_path, _absent)

    assert corrupt.exists()


def test_reconciliation_validates_upload_root_before_deleting_orphan(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    report_id = uuid.uuid4()
    encrypted = _encrypted(report_id)
    destination = tmp_path / f"{report_id}.wse"
    pending = stage_report_image(destination, encrypted)
    root_identity = (tmp_path.stat().st_dev, tmp_path.stat().st_ino)
    commit_state_called = False

    def root_acl(descriptor: int) -> list[bytes]:
        metadata = os.fstat(descriptor)
        return (
            [b"system.posix_acl_default"]
            if (metadata.st_dev, metadata.st_ino) == root_identity
            else []
        )

    def unexpected_commit_state(_report_id: uuid.UUID) -> ReportStorageCommitState:
        nonlocal commit_state_called
        commit_state_called = True
        return _absent(_report_id)

    monkeypatch.setattr(report_storage.os, "listxattr", root_acl)

    with pytest.raises(RuntimeError, match="upload root"):
        reconcile_pending_report_writes(tmp_path, unexpected_commit_state)

    assert commit_state_called is False
    assert destination.exists()
    assert pending.journal_path.exists()


def test_reconciliation_rejects_wrong_upload_root_mode_before_deletion(
    tmp_path: Path,
) -> None:
    report_id = uuid.uuid4()
    encrypted = _encrypted(report_id)
    destination = tmp_path / f"{report_id}.wse"
    pending = stage_report_image(destination, encrypted)
    tmp_path.chmod(0o755)

    with pytest.raises(RuntimeError, match="upload root"):
        reconcile_pending_report_writes(tmp_path, _absent)

    assert destination.exists()
    assert pending.journal_path.exists()


def test_startup_inventory_accepts_only_exact_encrypted_database_mapping(
    tmp_path: Path,
) -> None:
    report_id = uuid.uuid4()
    encrypted = _encrypted(report_id)
    destination = tmp_path / f"{report_id}.wse"
    stage_report_image(destination, encrypted)
    metadata = _committed_metadata(encrypted)
    reconcile_pending_report_writes(
        tmp_path,
        lambda _report_id: ReportStorageCommitState(True, metadata),
    )
    entry = ReportStorageInventoryEntry(
        report_id=report_id,
        logical_image_path=f"/uploads/{report_id}.jpg",
        report_content_type="image/jpeg",
        image_object=metadata,
    )

    validate_report_storage_inventory(
        tmp_path,
        [entry],
        known_key_states={"test-key-v1": "active"},
    )

    with pytest.raises(RuntimeError, match="inventory differ"):
        validate_report_storage_inventory(
            tmp_path,
            [],
            known_key_states={"test-key-v1": "active"},
        )
    for unavailable_state in ("retired", "destroyed", "compromised"):
        with pytest.raises(RuntimeError, match="key lifecycle"):
            validate_report_storage_inventory(
                tmp_path,
                [entry],
                known_key_states={"test-key-v1": unavailable_state},
            )


def test_startup_inventory_accepts_backup_reader_group_files(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o2750)
    monkeypatch.setattr(
        uploads.grp,
        "getgrnam",
        lambda _name: type("Group", (), {"gr_gid": tmp_path.stat().st_gid})(),
    )
    report_id = uuid.uuid4()
    encrypted = _encrypted(report_id)
    destination = tmp_path / f"{report_id}.wse"
    pending = stage_report_image(destination, encrypted)
    metadata = _committed_metadata(encrypted)
    reconcile_pending_report_writes(
        tmp_path,
        lambda _report_id: ReportStorageCommitState(True, metadata),
    )
    entry = ReportStorageInventoryEntry(
        report_id=report_id,
        logical_image_path=f"/uploads/{report_id}.jpg",
        report_content_type="image/jpeg",
        image_object=metadata,
    )

    validate_report_storage_inventory(
        tmp_path,
        [entry],
        known_key_states={"test-key-v1": "active"},
    )

    assert destination.stat().st_mode & 0o777 == 0o640
    assert destination.stat().st_gid == tmp_path.stat().st_gid
    assert not pending.journal_path.parent.exists()


def test_startup_inventory_rejects_upload_root_and_object_acls(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    report_id = uuid.uuid4()
    encrypted = _encrypted(report_id)
    destination = tmp_path / f"{report_id}.wse"
    stage_report_image(destination, encrypted)
    metadata = _committed_metadata(encrypted)
    reconcile_pending_report_writes(
        tmp_path,
        lambda _report_id: ReportStorageCommitState(True, metadata),
    )
    entry = ReportStorageInventoryEntry(
        report_id=report_id,
        logical_image_path=f"/uploads/{report_id}.jpg",
        report_content_type="image/jpeg",
        image_object=metadata,
    )
    root_identity = (tmp_path.stat().st_dev, tmp_path.stat().st_ino)
    object_identity = (destination.stat().st_dev, destination.stat().st_ino)

    monkeypatch.setattr(
        report_storage.os,
        "listxattr",
        lambda descriptor: (
            [b"system.posix_acl_default"]
            if (os.fstat(descriptor).st_dev, os.fstat(descriptor).st_ino)
            == root_identity
            else []
        ),
    )
    with pytest.raises(RuntimeError, match="private real directory"):
        validate_report_storage_inventory(
            tmp_path,
            [entry],
            known_key_states={"test-key-v1": "active"},
        )

    monkeypatch.setattr(
        report_storage.os,
        "listxattr",
        lambda descriptor: (
            [b"system.posix_acl_access"]
            if (os.fstat(descriptor).st_dev, os.fstat(descriptor).st_ino)
            == object_identity
            else []
        ),
    )
    with pytest.raises(RuntimeError, match="ACL-bearing"):
        validate_report_storage_inventory(
            tmp_path,
            [entry],
            known_key_states={"test-key-v1": "active"},
        )


@pytest.mark.parametrize("directory_name", [".report-write-journal", ".retention-quarantine"])
def test_startup_inventory_rejects_nonempty_operational_directories(
    tmp_path: Path,
    directory_name: str,
) -> None:
    operational = tmp_path / directory_name
    operational.mkdir(mode=0o700)
    evidence = operational / "nonterminal.json"
    evidence.write_text("{}", encoding="utf-8")
    evidence.chmod(0o600)

    with pytest.raises(RuntimeError, match="not empty after reconciliation"):
        validate_report_storage_inventory(tmp_path, [], known_key_states={})

    assert evidence.exists()


@pytest.mark.parametrize("unknown_name", ["legacy.jpg", "orphan.wse", "unknown.tmp"])
def test_startup_inventory_rejects_legacy_or_unknown_flat_entries(
    tmp_path: Path,
    unknown_name: str,
) -> None:
    unknown = tmp_path / unknown_name
    unknown.write_bytes(b"not-an-authorized-envelope")
    unknown.chmod(0o600)

    with pytest.raises(RuntimeError, match="legacy or unknown|inventory differ"):
        validate_report_storage_inventory(tmp_path, [], known_key_states={})

    assert unknown.exists()


def test_startup_inventory_rejects_report_without_encrypted_metadata(
    tmp_path: Path,
) -> None:
    report_id = uuid.uuid4()
    with pytest.raises(RuntimeError, match="legacy or orphan database row"):
        validate_report_storage_inventory(
            tmp_path,
            [
                ReportStorageInventoryEntry(
                    report_id=report_id,
                    logical_image_path=f"/uploads/{report_id}.jpg",
                    report_content_type="image/jpeg",
                    image_object=None,
                )
            ],
            known_key_states={},
        )


def test_backend_lifespan_runs_storage_reconciliation_before_serving(monkeypatch) -> None:
    calls = 0

    def reconcile() -> None:
        nonlocal calls
        calls += 1

    monkeypatch.setattr(main_app, "reconcile_report_storage", reconcile)
    monkeypatch.setattr(
        main_app,
        "validate_admin_credential_issuer_binding",
        lambda: None,
    )
    monkeypatch.setattr(main_app, "bind_privacy_hmac_key", lambda: None)
    monkeypatch.setattr(main_app, "inference_runner", None)

    async def exercise() -> None:
        async with main_app.lifespan(main_app.app):
            assert calls == 1

    import asyncio

    asyncio.run(exercise())


def test_backend_serializes_reconciliation_across_replicas(monkeypatch) -> None:
    calls: list[str] = []

    @contextmanager
    def maintenance_lock(path, *, expected_group_gid):
        assert path == main_app.settings.maintenance_lock_path
        assert expected_group_gid == main_app.settings.maintenance_lock_group_gid
        calls.append("maintenance-lock")
        try:
            yield
        finally:
            calls.append("maintenance-unlock")

    class FakeDatabaseSession:
        def execute(self, statement, parameters) -> None:
            assert "pg_advisory_xact_lock" in str(statement)
            assert "pg_advisory_xact_lock_shared" not in str(statement)
            calls.append("lock")

        def get(self, _model, _report_id):
            return None

        def scalars(self, _statement):
            return SimpleNamespace(all=lambda: [])

    class FakeTransaction:
        def __enter__(self):
            return FakeDatabaseSession()

        def __exit__(self, *_args) -> None:
            return None

    class FakeSessionFactory:
        @staticmethod
        def begin() -> FakeTransaction:
            return FakeTransaction()

    def reconcile(_upload_dir, commit_state) -> None:
        assert commit_state(uuid.uuid4()) == ReportStorageCommitState(False, None)
        calls.append("reconcile")

    def preflight(_db, _upload_dir, *, known_key_states) -> None:
        assert known_key_states
        calls.append("preflight")

    monkeypatch.setattr(main_app, "SessionLocal", FakeSessionFactory)
    monkeypatch.setattr(
        main_app.reports,
        "_shared_report_write_lock",
        maintenance_lock,
    )
    monkeypatch.setattr(main_app.report_image_key_manager, "synchronize", lambda _db: None)
    monkeypatch.setattr(main_app, "reconcile_pending_report_writes", reconcile)
    monkeypatch.setattr(
        main_app,
        "validate_report_storage_database_inventory",
        preflight,
    )

    main_app.reconcile_report_storage()

    assert calls == [
        "maintenance-lock",
        "lock",
        "reconcile",
        "preflight",
        "maintenance-unlock",
    ]


def test_reconciliation_waits_for_inflight_encrypted_report_commit(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    transaction_lock = threading.Lock()
    commit_entered = threading.Event()
    allow_commit = threading.Event()
    committed = threading.Event()
    reconciliation_waiting = threading.Event()
    added: list[object] = []

    report_id = uuid.uuid4()
    destination = tmp_path / f"{report_id}.wse"
    writer_holds_lock = True

    def writer_execute(statement, parameters) -> None:
        assert "pg_advisory_xact_lock_shared" in str(statement)
        transaction_lock.acquire()

    def commit() -> None:
        nonlocal writer_holds_lock
        commit_entered.set()
        if not allow_commit.wait(timeout=2):
            raise AssertionError("test did not release the report commit")
        committed.set()
        transaction_lock.release()
        writer_holds_lock = False

    def rollback() -> None:
        nonlocal writer_holds_lock
        if writer_holds_lock:
            transaction_lock.release()
            writer_holds_lock = False

    report = SimpleNamespace(
        id=report_id,
        image_path=f"/uploads/{report_id}.jpg",
        image_content_type="image/jpeg",
    )
    writer_session = SimpleNamespace(
        execute=writer_execute,
        add=added.append,
        commit=commit,
        rollback=rollback,
        refresh=lambda _report: None,
    )

    def reconciliation_execute(statement, parameters) -> None:
        assert "pg_advisory_xact_lock(" in str(statement)
        reconciliation_waiting.set()
        transaction_lock.acquire()

    def get(model, _report_id):
        if not committed.is_set():
            return None
        if model is Report:
            return report
        if model is ReportImageObject:
            return next(item for item in added if isinstance(item, ReportImageObject))
        return None

    @contextmanager
    def reconciliation_transaction():
        try:
            def scalars(statement):
                entity = statement.column_descriptions[0].get("entity")
                if entity is Report:
                    values = [report] if committed.is_set() else []
                elif entity is ReportImageObject:
                    values = [
                        item for item in added if isinstance(item, ReportImageObject)
                    ] if committed.is_set() else []
                else:
                    values = []
                return SimpleNamespace(all=lambda: values)

            yield SimpleNamespace(
                execute=reconciliation_execute,
                get=get,
                scalars=scalars,
            )
        finally:
            transaction_lock.release()

    monkeypatch.setattr(main_app, "SessionLocal", SimpleNamespace(begin=reconciliation_transaction))
    monkeypatch.setattr(main_app.report_image_key_manager, "synchronize", lambda _db: None)
    monkeypatch.setattr(main_app.settings, "upload_dir", tmp_path)
    monkeypatch.setattr(
        main_app,
        "validate_report_storage_database_inventory",
        lambda _db, _upload_dir, **_kwargs: None,
    )

    with ThreadPoolExecutor(max_workers=2) as pool:
        writer = pool.submit(
            _commit_new_report_with_image,
            writer_session,
            report,
            destination=destination,
            content=b"inflight-image",
            content_type="image/jpeg",
            key_manager=StaticKeyManager(),
        )
        try:
            assert commit_entered.wait(timeout=2)
            assert destination.read_bytes().startswith(b"WSRI")
            assert b"inflight-image" not in destination.read_bytes()
            reconciler = pool.submit(main_app.reconcile_report_storage)
            assert reconciliation_waiting.wait(timeout=2)
            assert not reconciler.done()
        finally:
            allow_commit.set()
        writer.result(timeout=2)
        reconciler.result(timeout=2)

    assert committed.is_set()
    assert destination.exists()
    assert list(pending_journal_directory(tmp_path).glob("*.json")) == []


@pytest.mark.skipif(
    not TEST_DATABASE_CONFIGURED,
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_multi_candidate_retention_delete_is_all_or_none() -> None:
    report_ids = [uuid.uuid4(), uuid.uuid4()]
    encrypted_objects = [
        encrypt_report_image(
            f"retention-candidate-{index}".encode(),
            report_id=report_id,
            content_type="image/jpeg",
            key_id="test-key-v1",
            key=KEY,
            nonce=index.to_bytes(12, "big"),
        )
        for index, report_id in enumerate(report_ids, start=1)
    ]
    try:
        with SessionLocal.begin() as db:
            for report_id, encrypted in zip(
                report_ids,
                encrypted_objects,
                strict=True,
            ):
                db.add(
                    Report(
                        id=report_id,
                        status="new",
                        class_id=0,
                        class_name="damaged_tactile_block",
                        confidence=0.9,
                        bbox_x=0.1,
                        bbox_y=0.1,
                        bbox_width=0.2,
                        bbox_height=0.2,
                        captured_at=datetime(2026, 1, 1, tzinfo=UTC),
                        source="fake",
                        image_path=f"/uploads/{report_id}.jpg",
                        image_content_type="image/jpeg",
                        payload={"image_sha256": encrypted.plaintext_sha256},
                    )
                )
                db.add(
                    ReportImageObject(
                        report_id=report_id,
                        storage_name=f"{report_id}.wse",
                        envelope_version=1,
                        algorithm="AES-256-GCM",
                        aad_version=1,
                        key_id=encrypted.key_id,
                        nonce=encrypted.nonce,
                        plaintext_sha256=encrypted.plaintext_sha256,
                        plaintext_size=encrypted.plaintext_length,
                        envelope_sha256=encrypted.envelope_sha256,
                        envelope_size=len(encrypted.envelope),
                        content_type=encrypted.content_type,
                    )
                )

        with SessionLocal() as db:
            lock_report_storage_reconciliation_transaction(db)
            candidates = list(
                db.scalars(
                    select(Report)
                    .where(Report.id.in_(report_ids))
                    .order_by(Report.id)
                    .with_for_update()
                ).all()
            )
            assert len(candidates) == 2
            for report in candidates:
                db.delete(report)
            db.flush()
            db.rollback()

        with SessionLocal() as db:
            assert len(
                db.scalars(select(Report).where(Report.id.in_(report_ids))).all()
            ) == 2
            assert len(
                db.scalars(
                    select(ReportImageObject).where(
                        ReportImageObject.report_id.in_(report_ids)
                    )
                ).all()
            ) == 2

        with SessionLocal.begin() as db:
            lock_report_storage_reconciliation_transaction(db)
            candidates = list(
                db.scalars(
                    select(Report)
                    .where(Report.id.in_(report_ids))
                    .order_by(Report.id)
                    .with_for_update()
                ).all()
            )
            assert len(candidates) == 2
            for report in candidates:
                db.delete(report)

        with SessionLocal() as db:
            assert db.scalars(
                select(Report).where(Report.id.in_(report_ids))
            ).all() == []
            assert db.scalars(
                select(ReportImageObject).where(
                    ReportImageObject.report_id.in_(report_ids)
                )
            ).all() == []
    finally:
        with SessionLocal.begin() as db:
            for image_object in db.scalars(
                select(ReportImageObject).where(
                    ReportImageObject.report_id.in_(report_ids)
                )
            ).all():
                db.delete(image_object)
            for report in db.scalars(
                select(Report).where(Report.id.in_(report_ids))
            ).all():
                db.delete(report)
