from __future__ import annotations

import asyncio
from contextlib import contextmanager
from dataclasses import replace
from datetime import UTC, datetime
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
from types import SimpleNamespace
import uuid

import pytest

import backend.app.main as main_app
import backend.app.services.raw_collection_storage as raw_storage
from backend.app.models import RawCollection, RawCollectionChunk, RawCollectionObject
from backend.app.services.raw_collection_crypto import (
    RawCollectionChunkBinding,
    encrypt_raw_collection_chunk,
)
from backend.app.services.raw_collection_ingest import RawCollectionStorageError
from backend.app.services.raw_collection_storage import (
    RawCollectionStorage,
    RawChunkCommitState,
    complete_raw_chunk_write,
    lock_raw_storage_reconciliation_transaction,
    lock_raw_storage_write_transaction,
    pending_raw_journal_directory,
    persist_raw_chunk_envelope,
    raw_chunk_commit_state,
    raw_chunk_storage_name,
    reconcile_pending_raw_chunk_writes,
    stage_raw_chunk_write,
    validate_raw_storage_database_inventory,
)


ROOT = Path(__file__).resolve().parents[2]
KEY = bytes(range(32))
KEY_ID = "pytest-raw-master-v1"
COLLECTION_ID = uuid.UUID("123e4567-e89b-42d3-a456-426614174000")
OBJECT_ID = uuid.UUID("123e4567-e89b-42d3-a456-426614174001")
WALK_ID = uuid.UUID("123e4567-e89b-42d3-a456-426614174003")
PERSISTED_AT = datetime(2026, 8, 29, 1, 2, 3, 456789, tzinfo=UTC)


class _StaticKeyManager:
    keyring = SimpleNamespace(generation=1, manifest_sha256="e" * 64)

    def decryption_key(self, key_id: str) -> bytes:
        if key_id != KEY_ID:
            raise AssertionError("unexpected raw key id")
        return KEY


def _stage(root: Path, content: bytes = b"raw crash reconciliation fixture"):
    root.mkdir(mode=0o700, exist_ok=True)
    digest = hashlib.sha256(content).hexdigest()
    binding = RawCollectionChunkBinding(
        privacy_subject_hmac="b" * 64,
        account_generation=1,
        collection_id=COLLECTION_ID,
        object_id=OBJECT_ID,
        chunk_index=0,
        manifest_sha256="a" * 64,
        purpose="GENERAL_RAW",
        walk_id=WALK_ID,
        content_type="application/octet-stream",
        plaintext_length=len(content),
        plaintext_sha256=digest,
    )
    encrypted = encrypt_raw_collection_chunk(
        content,
        binding=binding,
        key_id=KEY_ID,
        master_key=KEY,
        nonce=bytes(range(12)),
    )
    storage_name = raw_chunk_storage_name(COLLECTION_ID, OBJECT_ID, 0)
    pending = stage_raw_chunk_write(
        root,
        binding=binding,
        consent_receipt_sha256="c" * 64,
        storage_name=storage_name,
        key_id=encrypted.key_id,
        nonce=encrypted.nonce,
        envelope=encrypted.envelope,
        persisted_at=PERSISTED_AT,
    )
    return pending, encrypted


def _persist(root: Path, pending, encrypted) -> None:
    persisted = persist_raw_chunk_envelope(
        root,
        pending.metadata.storage_name,
        encrypted.envelope,
    )
    assert persisted.envelope_sha256 == pending.metadata.envelope_sha256
    assert persisted.envelope_size == pending.metadata.envelope_size


def _database_rows(pending):
    metadata = pending.metadata
    collection = SimpleNamespace(
        collection_id=metadata.collection_id,
        privacy_subject_hmac=metadata.privacy_subject_hmac,
        account_generation=metadata.account_generation,
        walk_id=metadata.walk_id,
        purpose=metadata.purpose,
        lifecycle_version=1,
        retention_class="RAW_ORIGINAL_180D",
        consent_receipt_sha256=metadata.consent_receipt_sha256,
        manifest_sha256=metadata.manifest_sha256,
        state="READY_TO_COMMIT",
        committed_at=None,
        retention_expires_at=None,
        quarantine_expires_at=None,
        receipt_sha256=None,
        digest_rejected_at=None,
        digest_rejected_object_id=None,
        object_count=1,
        chunk_count=1,
        total_bytes=metadata.plaintext_size,
    )
    item = SimpleNamespace(
        collection_id=metadata.collection_id,
        object_id=metadata.object_id,
        manifest_sha256=metadata.manifest_sha256,
        consent_receipt_sha256=metadata.consent_receipt_sha256,
        kind="SENSOR",
        content_type=metadata.content_type,
        size_bytes=metadata.plaintext_size,
        sha256=metadata.plaintext_sha256,
        chunk_count=1,
    )
    chunk = SimpleNamespace(
        collection_id=metadata.collection_id,
        object_id=metadata.object_id,
        chunk_index=metadata.chunk_index,
        manifest_sha256=metadata.manifest_sha256,
        consent_receipt_sha256=metadata.consent_receipt_sha256,
        declared_size_bytes=metadata.plaintext_size,
        declared_sha256=metadata.plaintext_sha256,
        storage_name=metadata.storage_name,
        envelope_version=metadata.envelope_version,
        algorithm=metadata.algorithm,
        aad_version=metadata.aad_version,
        key_id=metadata.key_id,
        nonce=metadata.nonce,
        envelope_sha256=metadata.envelope_sha256,
        envelope_size=metadata.envelope_size,
        persisted_at=metadata.persisted_at,
    )
    return collection, item, chunk


class _InventorySession:
    def __init__(self, collection, item, chunk) -> None:
        self._rows = {
            RawCollection: [collection] if collection is not None else [],
            RawCollectionObject: [item] if item is not None else [],
            RawCollectionChunk: [chunk] if chunk is not None else [],
        }
        self.lock_statements: list[str] = []
        self.commits = 0
        self.rollbacks = 0

    def execute(self, statement, _parameters):
        self.lock_statements.append(str(statement))

    def scalars(self, statement):
        entity = statement.column_descriptions[0]["entity"]
        return SimpleNamespace(all=lambda: self._rows[entity])

    def flush(self) -> None:
        return None

    def commit(self) -> None:
        self.commits += 1

    def rollback(self) -> None:
        self.rollbacks += 1


def test_process_death_after_raw_final_publish_removes_orphan(tmp_path: Path) -> None:
    root = tmp_path / "raw-objects"
    code = """
from datetime import UTC, datetime
import hashlib
import os
from pathlib import Path
import sys
import uuid
from backend.app.services.raw_collection_crypto import RawCollectionChunkBinding, encrypt_raw_collection_chunk
from backend.app.services.raw_collection_storage import persist_raw_chunk_envelope, raw_chunk_storage_name, stage_raw_chunk_write
content = b'raw subprocess crash fixture'
collection_id = uuid.UUID('123e4567-e89b-42d3-a456-426614174000')
object_id = uuid.UUID('123e4567-e89b-42d3-a456-426614174001')
root = Path(sys.argv[1])
root.mkdir(mode=0o700)
binding = RawCollectionChunkBinding(
    privacy_subject_hmac='b' * 64, account_generation=1,
    collection_id=collection_id, object_id=object_id, chunk_index=0,
    manifest_sha256='a' * 64, purpose='GENERAL_RAW',
    walk_id=uuid.UUID('123e4567-e89b-42d3-a456-426614174003'),
    content_type='application/octet-stream', plaintext_length=len(content),
    plaintext_sha256=hashlib.sha256(content).hexdigest(),
)
encrypted = encrypt_raw_collection_chunk(
    content, binding=binding, key_id='pytest-raw-master-v1',
    master_key=bytes(range(32)), nonce=bytes(range(12)),
)
name = raw_chunk_storage_name(collection_id, object_id, 0)
stage_raw_chunk_write(
    root, binding=binding, consent_receipt_sha256='c' * 64,
    storage_name=name, key_id=encrypted.key_id, nonce=encrypted.nonce,
    envelope=encrypted.envelope,
    persisted_at=datetime(2026, 8, 29, 1, 2, 3, 456789, tzinfo=UTC),
)
persist_raw_chunk_envelope(root, name, encrypted.envelope)
os._exit(77)
"""
    environment = dict(os.environ)
    environment["PYTHONPATH"] = str(ROOT)

    crashed = subprocess.run(
        [sys.executable, "-c", code, str(root)],
        env=environment,
        check=False,
    )

    assert crashed.returncode == 77
    assert len(list(root.glob("*.wsrc"))) == 1
    assert len(list(pending_raw_journal_directory(root).glob("*.json"))) == 1

    result = reconcile_pending_raw_chunk_writes(
        root,
        lambda *_identity: RawChunkCommitState(False, None),
    )

    assert result.removed_orphans == 1
    assert result.retained_committed == 0
    assert list(root.iterdir()) == []


def test_restart_clears_journal_before_final_publish(tmp_path: Path) -> None:
    root = tmp_path / "raw-objects"
    pending, _encrypted = _stage(root)

    result = reconcile_pending_raw_chunk_writes(
        root,
        lambda *_identity: RawChunkCommitState(True, None),
    )

    assert result.removed_orphans == 1
    assert not pending.destination.exists()
    assert list(root.iterdir()) == []


def test_restart_keeps_only_exact_committed_raw_chunk(tmp_path: Path) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)

    result = reconcile_pending_raw_chunk_writes(
        root,
        lambda *_identity: RawChunkCommitState(True, pending.metadata),
    )

    assert result.retained_committed == 1
    assert result.removed_orphans == 0
    assert pending.destination.read_bytes() == encrypted.envelope
    assert not pending.journal_path.exists()


def test_committed_raw_mismatch_preserves_file_and_journal(tmp_path: Path) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)
    mismatched = replace(pending.metadata, envelope_sha256="d" * 64)

    with pytest.raises(RuntimeError, match="metadata"):
        reconcile_pending_raw_chunk_writes(
            root,
            lambda *_identity: RawChunkCommitState(True, mismatched),
        )

    assert pending.destination.exists()
    assert pending.journal_path.exists()


def test_tampered_committed_raw_file_preserves_evidence(tmp_path: Path) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)
    tampered = bytearray(pending.destination.read_bytes())
    tampered[-1] ^= 1
    pending.destination.write_bytes(tampered)
    pending.destination.chmod(0o600)

    with pytest.raises(RuntimeError, match="file does not match"):
        reconcile_pending_raw_chunk_writes(
            root,
            lambda *_identity: RawChunkCommitState(True, pending.metadata),
        )

    assert pending.destination.exists()
    assert pending.journal_path.exists()


def test_raw_journal_is_no_replace_and_strict_canonical_json(tmp_path: Path) -> None:
    root = tmp_path / "raw-objects"
    pending, _encrypted = _stage(root)
    original = pending.journal_path.read_bytes()
    assert stat.S_IMODE(pending.journal_path.stat().st_mode) == 0o600
    assert json.dumps(
        json.loads(original),
        sort_keys=True,
        separators=(",", ":"),
    ).encode() == original

    with pytest.raises(RawCollectionStorageError) as duplicate:
        _stage(root)

    assert duplicate.value.code == "raw_chunk_persistence_ambiguous"
    assert pending.journal_path.read_bytes() == original


def test_corrupt_raw_journal_fails_without_deleting_evidence(tmp_path: Path) -> None:
    root = tmp_path / "raw-objects"
    root.mkdir(mode=0o700)
    journal_dir = pending_raw_journal_directory(root)
    journal_dir.mkdir(mode=0o700)
    corrupt = journal_dir / "unknown.json"
    corrupt.write_text("not-json", encoding="utf-8")
    corrupt.chmod(0o600)

    with pytest.raises(RuntimeError, match="invalid raw chunk write journal"):
        reconcile_pending_raw_chunk_writes(
            root,
            lambda *_identity: RawChunkCommitState(False, None),
        )

    assert corrupt.exists()


def test_raw_journal_temporary_with_acl_is_preserved(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    root = tmp_path / "raw-objects"
    pending, _encrypted = _stage(root)
    pending.journal_path.unlink()
    temporary = pending.journal_path.parent / (
        f".{pending.journal_path.name}.{'1' * 32}.tmp"
    )
    temporary.write_bytes(b"partial")
    temporary.chmod(0o600)
    monkeypatch.setattr(
        raw_storage,
        "descriptor_acl_is_absent",
        lambda descriptor: not stat.S_ISREG(os.fstat(descriptor).st_mode),
    )

    with pytest.raises(RuntimeError, match="invalid raw chunk write journal"):
        reconcile_pending_raw_chunk_writes(
            root,
            lambda *_identity: RawChunkCommitState(False, None),
        )

    assert temporary.exists()


def test_raw_journal_link_crash_removes_only_temporary_alias(
    tmp_path: Path,
) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)
    temporary = pending.journal_path.parent / (
        f".{pending.journal_path.name}.{'2' * 32}.tmp"
    )
    os.link(pending.journal_path, temporary)
    assert pending.journal_path.stat().st_nlink == 2

    result = reconcile_pending_raw_chunk_writes(
        root,
        lambda *_identity: RawChunkCommitState(True, pending.metadata),
    )

    assert result == raw_storage.RawStorageReconciliation(1, 0)
    assert pending.destination.exists()
    assert not pending.journal_path.exists()
    assert not temporary.exists()


def test_raw_reconciler_tolerates_writer_cleanup_after_listing(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)
    original_read = raw_storage._read_raw_journal

    def remove_then_read(candidate_root: Path, journal_name: str):
        pending.journal_path.unlink()
        return original_read(candidate_root, journal_name)

    monkeypatch.setattr(raw_storage, "_read_raw_journal", remove_then_read)

    result = reconcile_pending_raw_chunk_writes(
        root,
        lambda *_identity: RawChunkCommitState(True, pending.metadata),
    )

    assert result == raw_storage.RawStorageReconciliation(0, 0)
    assert pending.destination.exists()


def test_raw_writer_and_reconciler_use_distinct_lock_modes() -> None:
    statements: list[str] = []
    database = SimpleNamespace(
        execute=lambda statement, _parameters: statements.append(str(statement))
    )

    lock_raw_storage_write_transaction(database)
    lock_raw_storage_reconciliation_transaction(database)

    assert "pg_advisory_xact_lock_shared" in statements[0]
    assert "pg_advisory_xact_lock(" in statements[1]
    assert "pg_advisory_xact_lock_shared" not in statements[1]


def test_raw_inventory_accepts_exact_mapping_and_rejects_orphans(
    tmp_path: Path,
) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)
    complete_raw_chunk_write(pending)
    collection, item, chunk = _database_rows(pending)
    database = _InventorySession(collection, item, chunk)

    validate_raw_storage_database_inventory(
        database,
        root,
        key_manager=_StaticKeyManager(),  # type: ignore[arg-type]
    )
    assert "pg_advisory_xact_lock(" in database.lock_statements[0]

    orphan_database = _InventorySession(None, None, None)
    with pytest.raises(RuntimeError, match="inventory differ"):
        validate_raw_storage_database_inventory(
            orphan_database,
            root,
            key_manager=_StaticKeyManager(),  # type: ignore[arg-type]
        )


def test_verified_chunk_cache_binds_full_commit_metadata(tmp_path: Path) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)
    complete_raw_chunk_write(pending)
    collection, item, chunk = _database_rows(pending)
    storage = RawCollectionStorage(
        raw_object_dir=root,
        privacy_hmac_secret="inventory-test-only",
        key_manager=_StaticKeyManager(),  # type: ignore[arg-type]
    )

    assert storage._verify_persisted_chunk(collection, item, chunk) == (
        b"raw crash reconciliation fixture"
    )
    chunk.nonce = b"x" * 12

    with pytest.raises(RawCollectionStorageError):
        storage._verify_persisted_chunk_metadata(collection, item, chunk)


def test_full_verification_seeds_without_a_second_probe(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)
    complete_raw_chunk_write(pending)
    collection, item, chunk = _database_rows(pending)
    storage = RawCollectionStorage(
        raw_object_dir=root,
        privacy_hmac_secret="inventory-test-only",
        key_manager=_StaticKeyManager(),  # type: ignore[arg-type]
    )
    monkeypatch.setattr(
        raw_storage,
        "probe_raw_chunk_envelope",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("full verification performed a second probe")
        ),
    )

    assert storage._verify_persisted_chunk(collection, item, chunk) == (
        b"raw crash reconciliation fixture"
    )


def test_raw_inventory_atomically_replaces_and_warms_chunk_cache(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)
    complete_raw_chunk_write(pending)
    collection, item, chunk = _database_rows(pending)
    key_manager = _StaticKeyManager()
    storage = RawCollectionStorage(
        raw_object_dir=root,
        privacy_hmac_secret="inventory-test-only",
        key_manager=key_manager,  # type: ignore[arg-type]
    )
    stale_metadata = replace(pending.metadata, nonce=b"z" * 12)
    storage._verified_chunk_files[(stale_metadata, 1, "e" * 64)] = (0, 0, 0, 0, 0)

    validate_raw_storage_database_inventory(
        _InventorySession(collection, item, chunk),
        root,
        key_manager=key_manager,  # type: ignore[arg-type]
        storage=storage,
    )

    assert len(storage._verified_chunk_files) == 1
    [(cache_key, _fingerprint)] = storage._verified_chunk_files.items()
    assert cache_key == (pending.metadata, 1, "e" * 64)
    monkeypatch.setattr(
        storage,
        "_verify_persisted_chunk",
        lambda *_args: (_ for _ in ()).throw(
            AssertionError("warm runtime cache bulk-decrypted content")
        ),
    )
    assert storage._verify_persisted_chunk_metadata(
        collection,
        item,
        chunk,
    ) == pending.metadata


def test_raw_chunk_commit_state_accepts_persisted_partial_multi_inventory(
    tmp_path: Path,
) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)
    collection, item, chunk = _database_rows(pending)
    collection.state = "MANIFEST_ACCEPTED"
    collection.object_count = 2
    collection.chunk_count = 3
    collection.total_bytes += 10
    item.chunk_count = 2
    item.size_bytes += 5
    item.sha256 = "d" * 64

    state = raw_chunk_commit_state(collection, item, chunk)

    assert state == RawChunkCommitState(True, pending.metadata)


def test_raw_inventory_rejects_database_only_persisted_chunk(tmp_path: Path) -> None:
    root = tmp_path / "raw-objects"
    pending, _encrypted = _stage(root)
    complete_raw_chunk_write(pending)
    collection, item, chunk = _database_rows(pending)

    with pytest.raises(RuntimeError, match="inventory differ"):
        validate_raw_storage_database_inventory(
            _InventorySession(collection, item, chunk),
            root,
            key_manager=_StaticKeyManager(),  # type: ignore[arg-type]
        )


@pytest.mark.parametrize("persisted", (False, True))
def test_raw_inventory_rejects_single_chunk_object_digest_disagreement(
    tmp_path: Path,
    persisted: bool,
) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    if persisted:
        _persist(root, pending, encrypted)
    complete_raw_chunk_write(pending)
    collection, item, chunk = _database_rows(pending)
    item.sha256 = "0" * 64
    if not persisted:
        collection.state = "MANIFEST_ACCEPTED"
        for field in (
            "storage_name",
            "envelope_version",
            "algorithm",
            "aad_version",
            "key_id",
            "nonce",
            "envelope_sha256",
            "envelope_size",
            "persisted_at",
        ):
            setattr(chunk, field, None)

    with pytest.raises(RuntimeError, match="database inventory is inconsistent"):
        validate_raw_storage_database_inventory(
            _InventorySession(collection, item, chunk),
            root,
            key_manager=_StaticKeyManager(),  # type: ignore[arg-type]
        )


@pytest.mark.parametrize(
    ("field", "value"),
    (("object_count", 2), ("chunk_count", 2), ("total_bytes", 1)),
)
def test_raw_inventory_rejects_collection_aggregate_mismatch(
    tmp_path: Path,
    field: str,
    value: int,
) -> None:
    root = tmp_path / "raw-objects"
    pending, encrypted = _stage(root)
    _persist(root, pending, encrypted)
    complete_raw_chunk_write(pending)
    collection, item, chunk = _database_rows(pending)
    setattr(collection, field, value)

    with pytest.raises(RuntimeError, match="database inventory is inconsistent"):
        validate_raw_storage_database_inventory(
            _InventorySession(collection, item, chunk),
            root,
            key_manager=_StaticKeyManager(),  # type: ignore[arg-type]
        )


def test_backend_lifespan_reconciles_raw_storage_before_serving(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []
    monkeypatch.setattr(
        main_app,
        "validate_admin_credential_issuer_binding",
        lambda: calls.append("admin"),
    )
    monkeypatch.setattr(
        main_app,
        "bind_privacy_hmac_key",
        lambda: calls.append("privacy"),
    )
    monkeypatch.setattr(
        main_app,
        "bind_account_crypto_keys",
        lambda: calls.append("account-crypto"),
    )
    monkeypatch.setattr(
        main_app,
        "reconcile_report_storage",
        lambda: calls.append("report"),
    )
    monkeypatch.setattr(
        main_app,
        "reconcile_raw_collection_storage",
        lambda: calls.append("raw"),
    )
    monkeypatch.setattr(main_app, "capacity_monitor", None)
    monkeypatch.setattr(main_app, "inference_runner", None)

    async def exercise() -> None:
        async with main_app.lifespan(main_app.app):
            assert calls == ["admin", "privacy", "account-crypto", "report", "raw"]

    asyncio.run(exercise())


def test_backend_raw_reconciliation_holds_shared_maintenance_lock(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []
    root = tmp_path / "raw-objects"
    root.mkdir(mode=0o700)

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
        def execute(self, statement, _parameters) -> None:
            assert "pg_advisory_xact_lock" in str(statement)
            calls.append("database-lock")

        def get(self, _model, _identity):
            return None

    class FakeTransaction:
        def __enter__(self):
            return FakeDatabaseSession()

        def __exit__(self, *_args) -> None:
            return None

    class FakeSessionFactory:
        @staticmethod
        def begin() -> FakeTransaction:
            return FakeTransaction()

    def reconcile(_root, commit_state) -> None:
        state = commit_state(COLLECTION_ID, OBJECT_ID, 0)
        assert state == RawChunkCommitState(False, None)
        calls.append("reconcile")

    def validate(_db, _root, *, key_manager, storage) -> None:
        assert key_manager is main_app.report_image_key_manager
        assert storage is main_app.raw_collection_storage
        calls.append("validate")

    monkeypatch.setattr(main_app.settings, "raw_object_dir", root)
    monkeypatch.setattr(main_app, "SessionLocal", FakeSessionFactory)
    monkeypatch.setattr(
        main_app.reports,
        "shared_maintenance_write_lock",
        maintenance_lock,
    )
    monkeypatch.setattr(
        main_app.report_image_key_manager,
        "synchronize",
        lambda _db: calls.append("keys"),
    )
    monkeypatch.setattr(main_app, "reconcile_pending_raw_chunk_writes", reconcile)
    monkeypatch.setattr(
        main_app,
        "validate_raw_storage_database_inventory",
        validate,
    )

    main_app.reconcile_raw_collection_storage()

    assert calls == [
        "maintenance-lock",
        "database-lock",
        "keys",
        "reconcile",
        "validate",
        "maintenance-unlock",
    ]
