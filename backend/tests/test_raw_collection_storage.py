from __future__ import annotations

import hashlib
import importlib
import os
from datetime import UTC, datetime, timedelta
from pathlib import Path
import stat
from types import SimpleNamespace
import uuid

import pytest

import backend.app.services.raw_collection_storage as raw_storage
from backend.app.services.raw_collection_ingest import RawCollectionStorageError
from backend.app.services.raw_collection_storage import (
    RawCollectionStorage,
    persist_raw_chunk_envelope,
    prepare_raw_object_directory,
    probe_raw_object_directory,
    raw_chunk_storage_name,
    read_raw_chunk_envelope,
)


COLLECTION_ID = uuid.UUID("123e4567-e89b-42d3-a456-426614174000")
OBJECT_ID = uuid.UUID("123e4567-e89b-42d3-a456-426614174001")
SECOND_OBJECT_ID = uuid.UUID("123e4567-e89b-42d3-a456-426614174002")


@pytest.mark.parametrize("block_size", (4096, 6000))
def test_raw_capacity_reservation_includes_durable_filesystem_overhead(
    block_size: int,
) -> None:
    plaintext_bytes = 123
    chunk_count = 2
    journal_blocks = (
        raw_storage.RAW_JOURNAL_MAX_BYTES + block_size - 1
    ) // block_size

    assert raw_storage._raw_capacity_reservation_bytes(
        plaintext_bytes,
        chunk_count,
        block_size,
    ) == plaintext_bytes + chunk_count * (
        raw_storage.RAW_ENVELOPE_RESERVATION_OVERHEAD
        + block_size - 1
        + journal_blocks * block_size
        + raw_storage.RAW_CAPACITY_DIRECTORY_BLOCKS_PER_CHUNK * block_size
    )


def _settings(root: Path | None) -> SimpleNamespace:
    return SimpleNamespace(
        raw_object_dir=root,
        walksafe_environment="test",
    )


def test_local_raw_root_is_created_private_and_revalidated(tmp_path: Path) -> None:
    root = tmp_path / "raw-objects"
    prepare_raw_object_directory(_settings(root))

    metadata = root.stat(follow_symlinks=False)
    assert stat.S_ISDIR(metadata.st_mode)
    assert stat.S_IMODE(metadata.st_mode) == 0o700

    root.chmod(0o755)
    with pytest.raises(RuntimeError, match="private real directory"):
        prepare_raw_object_directory(_settings(root))


def test_local_raw_root_rejects_a_symlink(tmp_path: Path) -> None:
    authority = tmp_path / "authority"
    authority.mkdir(mode=0o700)
    alias = tmp_path / "raw-alias"
    alias.symlink_to(authority, target_is_directory=True)

    with pytest.raises(RuntimeError, match="private real directory"):
        prepare_raw_object_directory(_settings(alias))


def test_raw_root_readiness_probe_proves_link_and_cleans_up(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    root = tmp_path / "raw-objects"
    prepare_raw_object_directory(_settings(root))
    link_calls: list[tuple[str, str]] = []
    fsync_calls: list[int] = []
    real_link = os.link
    real_fsync = os.fsync

    def record_link(source: str, destination: str, **kwargs) -> None:
        link_calls.append((source, destination))
        real_link(source, destination, **kwargs)

    def record_fsync(descriptor: int) -> None:
        fsync_calls.append(descriptor)
        real_fsync(descriptor)

    monkeypatch.setattr(os, "link", record_link)
    monkeypatch.setattr(os, "fsync", record_fsync)

    probe_raw_object_directory(_settings(root))

    assert len(link_calls) == 1
    assert len(fsync_calls) == 3
    assert list(root.iterdir()) == []


def test_no_replace_chunk_publish_is_durable_and_replay_preserves_inode(
    tmp_path: Path,
) -> None:
    root = tmp_path / "raw-objects"
    prepare_raw_object_directory(_settings(root))
    storage_name = raw_chunk_storage_name(COLLECTION_ID, OBJECT_ID, 0)
    envelope = b"WSRC-envelope-synthetic-ciphertext"

    persisted = persist_raw_chunk_envelope(root, storage_name, envelope)
    destination = root / storage_name
    before = destination.stat(follow_symlinks=False)

    assert persisted.storage_name == storage_name
    assert persisted.envelope_size == len(envelope)
    assert persisted.envelope_sha256 == hashlib.sha256(envelope).hexdigest()
    assert before.st_nlink == 1
    assert stat.S_IMODE(before.st_mode) == 0o600
    assert read_raw_chunk_envelope(
        root,
        storage_name,
        expected_size=len(envelope),
        expected_sha256=persisted.envelope_sha256,
    ) == envelope

    with pytest.raises(
        RawCollectionStorageError,
        match="ambiguous",
    ) as replay:
        persist_raw_chunk_envelope(root, storage_name, b"different-envelope")
    assert replay.value.status_code == 503
    after = destination.stat(follow_symlinks=False)
    assert (after.st_dev, after.st_ino) == (before.st_dev, before.st_ino)
    assert destination.read_bytes() == envelope
    assert list(root.glob(".*.tmp")) == []


def test_committed_raw_file_drift_is_fail_closed(tmp_path: Path) -> None:
    root = tmp_path / "raw-objects"
    prepare_raw_object_directory(_settings(root))
    storage_name = raw_chunk_storage_name(COLLECTION_ID, OBJECT_ID, 0)
    envelope = b"WSRC-envelope-synthetic-ciphertext"
    persisted = persist_raw_chunk_envelope(root, storage_name, envelope)
    (root / storage_name).write_bytes(b"tampered")

    with pytest.raises(RawCollectionStorageError, match="does not match"):
        read_raw_chunk_envelope(
            root,
            storage_name,
            expected_size=persisted.envelope_size,
            expected_sha256=persisted.envelope_sha256,
        )


def test_fractional_persisted_receipt_time_is_fail_closed() -> None:
    committed_at = datetime(2026, 8, 29, tzinfo=UTC, microsecond=500_000)
    collection = SimpleNamespace(
        collection_id=COLLECTION_ID,
        manifest_sha256="a" * 64,
        purpose="GENERAL_RAW",
        lifecycle_version=1,
        retention_class="RAW_ORIGINAL_180D",
        object_count=1,
        chunk_count=1,
        total_bytes=1,
        state="COMMITTED",
        committed_at=committed_at,
        retention_expires_at=committed_at + timedelta(days=180),
        quarantine_expires_at=None,
        receipt_sha256="b" * 64,
    )
    item = SimpleNamespace(
        object_id=OBJECT_ID,
        kind="SENSOR",
        size_bytes=1,
        sha256="c" * 64,
        chunk_count=1,
    )

    with pytest.raises(RawCollectionStorageError) as ambiguous:
        RawCollectionStorage._receipt(  # type: ignore[arg-type]
            collection,
            [(item, [])],
        )

    assert ambiguous.value.code == "raw_receipt_persistence_ambiguous"
    assert ambiguous.value.status_code == 503


def test_multi_inventory_status_projects_compact_missing_ranges() -> None:
    collection = SimpleNamespace(
        collection_id=COLLECTION_ID,
        manifest_sha256="a" * 64,
        purpose="GENERAL_RAW",
        lifecycle_version=2,
        object_count=2,
        chunk_count=3,
        total_bytes=9,
        state="MANIFEST_ACCEPTED",
        committed_at=None,
        retention_expires_at=None,
        quarantine_expires_at=None,
        receipt_sha256=None,
    )
    first = SimpleNamespace(
        object_id=OBJECT_ID,
        kind="SENSOR",
        size_bytes=5,
        sha256="b" * 64,
        chunk_count=2,
    )
    second = SimpleNamespace(
        object_id=SECOND_OBJECT_ID,
        kind="PERFORMANCE",
        size_bytes=4,
        sha256="c" * 64,
        chunk_count=1,
    )
    inventory = [
        (
            first,
            [
                SimpleNamespace(declared_size_bytes=2, storage_name="stored"),
                SimpleNamespace(declared_size_bytes=3, storage_name=None),
            ],
        ),
        (
            second,
            [SimpleNamespace(declared_size_bytes=4, storage_name=None)],
        ),
    ]

    status = RawCollectionStorage._status(  # type: ignore[arg-type]
        collection,
        inventory,
    )

    assert status.state == "RECEIVING"
    assert status.received_chunk_count == 1
    assert status.received_bytes == 2
    assert [
        [item.model_dump() for item in status_object.missing_ranges]
        for status_object in status.objects
    ] == [
        [{"start": 1, "end": 1}],
        [{"start": 0, "end": 0}],
    ]


def test_b1b_migration_adds_and_removes_only_the_raw_rate_group(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    migration = importlib.import_module(
        "backend.alembic.versions.202608290001_raw_collection_storage"
    )
    dropped: list[tuple[tuple[object, ...], dict[str, object]]] = []
    created: list[tuple[tuple[object, ...], dict[str, object]]] = []
    monkeypatch.setattr(
        migration.op,
        "drop_constraint",
        lambda *args, **kwargs: dropped.append((args, kwargs)),
    )
    monkeypatch.setattr(
        migration.op,
        "create_check_constraint",
        lambda *args, **kwargs: created.append((args, kwargs)),
    )

    migration._replace_rate_group_constraint(include_raw=True)
    migration._replace_rate_group_constraint(include_raw=False)

    assert dropped == [
        (
            ("ck_actor_rate_limit_events_group", "actor_rate_limit_events"),
            {"type_": "check"},
        ),
        (
            ("ck_actor_rate_limit_events_group", "actor_rate_limit_events"),
            {"type_": "check"},
        ),
    ]
    assert created[0][0][2].endswith("'privacy', 'raw_collection')")
    assert created[1][0][2].endswith("'admin_read', 'privacy')")


def test_b1c_migration_is_linear_and_refuses_receipt_data_loss(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    migration = importlib.import_module(
        "backend.alembic.versions.202608290002_raw_collection_commit_receipt"
    )

    assert migration.revision == "202608290002"
    assert migration.down_revision == "202608290001"
    assert "receipt_sha256 IS NOT NULL" in migration._UNSAFE_DOWNGRADE_SQL
    assert "ERRCODE = '55000'" in migration._UNSAFE_DOWNGRADE_SQL

    executed: list[object] = []
    monkeypatch.setattr(migration.op, "execute", executed.append)
    monkeypatch.setattr(migration.op, "drop_index", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(
        migration.op,
        "drop_constraint",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(migration.op, "drop_column", lambda *_args, **_kwargs: None)

    migration.downgrade()

    assert executed[:2] == [
        migration._DOWNGRADE_LOCK_SQL,
        migration._UNSAFE_DOWNGRADE_SQL,
    ]


def test_b1e_migration_binds_retention_and_least_privilege_deletion(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    migration = importlib.import_module(
        "backend.alembic.versions.202608290003_raw_capacity_retention_deletion"
    )
    assert migration.revision == "202608290003"
    assert migration.down_revision == "202608290002"
    assert "raw_collections" in migration._UNSAFE_DOWNGRADE_SQL
    assert "ERRCODE = '55000'" in migration._UNSAFE_DOWNGRADE_SQL

    executed: list[object] = []
    added: list[tuple[object, object]] = []
    monkeypatch.setattr(migration.op, "execute", executed.append)
    monkeypatch.setattr(
        migration.op,
        "add_column",
        lambda table, column: added.append((table, column)),
    )
    monkeypatch.setattr(migration.op, "alter_column", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(
        migration.op,
        "create_check_constraint",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        migration.op,
        "drop_constraint",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(migration.op, "drop_column", lambda *_args, **_kwargs: None)

    migration.upgrade()

    upgrade_sql = "\n".join(str(statement) for statement in executed)
    assert added[0][0] == "raw_collections"
    assert added[0][1].name == "retention_class"
    assert "SET retention_class = 'RAW_ORIGINAL_180D'" in upgrade_sql
    assert "NEW.retention_class" in upgrade_sql
    assert (
        "GRANT SELECT ON TABLE public.raw_collections, "
        "public.raw_collection_objects, public.raw_collection_chunks"
    ) in upgrade_sql
    assert "GRANT DELETE ON TABLE public.raw_collections" in upgrade_sql
    assert "GRANT DELETE ON TABLE public.raw_collection_objects" not in upgrade_sql
    assert "GRANT DELETE ON TABLE public.raw_collection_chunks" not in upgrade_sql

    executed.clear()
    migration.downgrade()

    assert executed[:2] == [
        migration._DOWNGRADE_LOCK_SQL,
        migration._UNSAFE_DOWNGRADE_SQL,
    ]
    downgrade_sql = "\n".join(str(statement) for statement in executed)
    assert "REVOKE DELETE ON TABLE public.raw_collections" in downgrade_sql
    assert "REVOKE SELECT ON TABLE public.raw_collections" in downgrade_sql
