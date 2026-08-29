from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime
import hashlib
import os
from pathlib import Path
import stat
import uuid

import pytest
from sqlalchemy.dialects import postgresql

import scripts.manage_raw_collection_retention as retention


COLLECTION_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
OBJECT_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
OPERATION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
AS_OF = datetime(2026, 8, 29, tzinfo=UTC)


def _candidate(*, expires_at: datetime = AS_OF) -> retention.RawRetentionCandidate:
    return retention.RawRetentionCandidate(
        collection_id=COLLECTION_ID,
        retention_expires_at=expires_at,
        manifest_sha256="a" * 64,
        receipt_sha256="b" * 64,
        chunk_count=1,
        total_bytes=4,
    )


def _chunk(content: bytes = b"test") -> retention.RawRetentionChunk:
    return retention.RawRetentionChunk(
        collection_id=COLLECTION_ID,
        object_id=OBJECT_ID,
        chunk_index=0,
        storage_name=f"{COLLECTION_ID}.{OBJECT_ID}.0.wsrc",
        envelope_size=len(content),
        envelope_sha256=hashlib.sha256(content).hexdigest(),
    )


def test_candidate_query_has_exact_boundary_state_class_order_limit_and_no_skip_locked() -> None:
    statement = retention._candidate_statement(
        as_of=AS_OF, limit=25, for_update=True
    )
    sql = str(
        statement.compile(
            dialect=postgresql.dialect(),
            compile_kwargs={"literal_binds": True},
        )
    )

    assert "raw_collections.state = 'COMMITTED'" in sql
    assert "raw_collections.retention_class = 'RAW_ORIGINAL_180D'" in sql
    assert "raw_collections.retention_expires_at <=" in sql
    assert "raw_collections.state = 'QUARANTINED'" in sql
    assert "raw_collections.retention_class = 'RAW_QUARANTINE_14D'" in sql
    assert "raw_collections.quarantine_expires_at <=" in sql
    assert "raw_collection_legal_hold_events" in sql
    assert "ORDER BY retention_expires_at, raw_collections.collection_id" in sql
    assert "LIMIT 25" in sql
    assert "FOR UPDATE" in sql
    assert "SKIP LOCKED" not in sql


def test_candidate_digest_is_stable_and_bound_to_exact_chunk_metadata() -> None:
    first = retention.candidate_digest([_candidate()], [_chunk()])
    second = retention.candidate_digest([_candidate()], [_chunk()])
    changed = retention.candidate_digest(
        [_candidate()],
        [
            replace(_chunk(), envelope_sha256="f" * 64)
        ],
    )
    assert first == second
    assert first != changed


def test_preview_is_read_only_projection(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        retention,
        "_load_candidates",
        lambda *_args, **_kwargs: ([_candidate()], [_chunk()]),
    )
    db = object()

    result = retention.preview_raw_collection_retention(
        db,  # type: ignore[arg-type]
        as_of=AS_OF,
        limit=1,
    )

    assert result["mode"] == "PREVIEW"
    assert result["candidate_count"] == 1
    assert result["candidates"][0]["retention_expires_at"] == "2026-08-29T00:00:00.000000Z"


def test_mutation_requires_exact_confirmation_and_preview_digest(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    args = retention._parser().parse_args(
        [
            "--local-isolated",
            "--apply",
            "--as-of", "2026-08-29T00:00:00Z",
            "--operation-id", str(OPERATION_ID),
            "--candidate-digest", "a" * 64,
            "--confirm", "DELETE-EXPIRED-RAW-COLLECTIONS",
            "--raw-object-dir", "/tmp/raw",
            "--operation-root", "/tmp/retention",
            "--maintenance-lock-path", "/tmp/maintenance.lock",
        ]
    )
    retention._validate_mode(args)
    args.confirm = "yes"
    with pytest.raises(retention.RawRetentionError, match="--confirm"):
        retention._validate_mode(args)


def test_preview_and_apply_reject_future_as_of_deterministically(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    args = retention._parser().parse_args(
        [
            "--local-isolated",
            "--as-of", "2026-08-29T00:00:00.000001Z",
            "--raw-object-dir", "/tmp/raw",
            "--operation-root", "/tmp/retention",
            "--maintenance-lock-path", "/tmp/maintenance.lock",
        ]
    )
    with pytest.raises(retention.RawRetentionError, match="future"):
        retention._validate_mode(args, now=AS_OF)

    args.apply = True
    args.operation_id = OPERATION_ID
    args.candidate_digest = "a" * 64
    args.confirm = retention.APPLY_CONFIRMATION
    with pytest.raises(retention.RawRetentionError, match="future"):
        retention._validate_mode(args, now=AS_OF)


def test_pending_raw_write_journal_fails_before_retention_locks(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []
    monkeypatch.setattr(retention, "raw_storage_has_pending_writes", lambda _root: True)
    monkeypatch.setattr(
        retention,
        "lock_raw_storage_reconciliation_transaction",
        lambda _db: calls.append("storage-lock"),
    )

    with pytest.raises(retention.RawRetentionError, match="pending raw write"):
        retention.apply_raw_collection_retention(
            object(),  # type: ignore[arg-type]
            raw_root=Path("/unused"),
            operation_root=Path("/unused"),
            as_of=AS_OF,
            limit=1,
            operation_id=OPERATION_ID,
            expected_candidate_digest="a" * 64,
            create_operation_root=False,
        )
    assert calls == []


def test_pending_retention_journal_fails_before_candidate_selection(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw_root = tmp_path / "raw"
    raw_root.mkdir(mode=0o700)
    operation_root = tmp_path / "operations"
    operation_root.mkdir(mode=0o700)
    (operation_root / "journals").mkdir(mode=0o700)
    (operation_root / "receipts").mkdir(mode=0o700)
    pending = operation_root / "journals" / f"{uuid.uuid4()}.json"
    pending.write_text("{}", encoding="ascii")
    pending.chmod(0o600)
    monkeypatch.setattr(retention, "raw_storage_has_pending_writes", lambda _root: False)
    monkeypatch.setattr(
        retention, "lock_raw_storage_reconciliation_transaction", lambda _db: None
    )
    monkeypatch.setattr(
        retention, "lock_raw_capacity_reservation_transaction", lambda _db: None
    )
    monkeypatch.setattr(
        retention,
        "_load_candidates",
        lambda *_args, **_kwargs: pytest.fail("candidate query must not run"),
    )

    with pytest.raises(retention.RawRetentionError, match="requires reconcile"):
        retention.apply_raw_collection_retention(
            object(),  # type: ignore[arg-type]
            raw_root=raw_root,
            operation_root=operation_root,
            as_of=AS_OF,
            limit=1,
            operation_id=OPERATION_ID,
            expected_candidate_digest="a" * 64,
            create_operation_root=False,
        )


def test_chunk_file_rejects_hardlink_and_digest_mismatch(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    root = tmp_path / "raw"
    root.mkdir(mode=0o700)
    chunk = _chunk()
    path = root / chunk.storage_name
    path.write_bytes(b"test")
    path.chmod(0o600)
    root_fd = os.open(root, retention._DIRECTORY_FLAGS)
    try:
        root_metadata = retention.validate_upload_directory_descriptor(root, root_fd)
        retention._validate_chunk_file(root_fd, root_metadata, chunk)
        alias = root / "alias"
        os.link(path, alias)
        with pytest.raises(retention.RawRetentionError, match="metadata"):
            retention._validate_chunk_file(root_fd, root_metadata, chunk)
        alias.unlink()
        path.write_bytes(b"fail")
        path.chmod(0o600)
        with pytest.raises(retention.RawRetentionError, match="digest"):
            retention._validate_chunk_file(root_fd, root_metadata, chunk)
    finally:
        os.close(root_fd)


def test_operation_id_replay_binding_rejects_different_intent_or_candidate() -> None:
    payload = {
        "operation_id": str(OPERATION_ID),
        "intent_digest": "a" * 64,
        "candidate_digest": "b" * 64,
    }
    retention._validate_operation_binding(
        payload,
        operation_id=OPERATION_ID,
        intent_digest="a" * 64,
        expected_candidate_digest="b" * 64,
    )
    with pytest.raises(retention.RawRetentionError, match="different"):
        retention._validate_operation_binding(
            payload,
            operation_id=OPERATION_ID,
            intent_digest="f" * 64,
            expected_candidate_digest="b" * 64,
        )


def test_control_file_is_create_only_private_and_canonical(tmp_path: Path) -> None:
    root = tmp_path / "control"
    root.mkdir(mode=0o700)
    descriptor = os.open(root, retention._DIRECTORY_FLAGS)
    payload = {
        "candidate_digest": hashlib.sha256(b"[]").hexdigest(),
        "candidates": [],
        "chunks": [],
        "intent": {"as_of": "2026-08-29T00:00:00.000000Z", "limit": 1},
        "intent_digest": retention._digest(
            {"as_of": "2026-08-29T00:00:00.000000Z", "limit": 1}
        ),
        "operation_id": str(OPERATION_ID),
        "state": "PREPARED",
        "schema_version": retention.JOURNAL_SCHEMA,
    }
    try:
        retention._write_create_only(descriptor, f"{OPERATION_ID}.json", payload)
        original = (root / f"{OPERATION_ID}.json").read_bytes()
        assert stat.S_IMODE((root / f"{OPERATION_ID}.json").stat().st_mode) == 0o600
        with pytest.raises(retention.RawRetentionError, match="already exists"):
            retention._write_create_only(descriptor, f"{OPERATION_ID}.json", payload)
        assert (root / f"{OPERATION_ID}.json").read_bytes() == original
    finally:
        os.close(descriptor)
