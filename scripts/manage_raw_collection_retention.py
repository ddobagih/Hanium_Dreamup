#!/usr/bin/env python3
"""Preview, apply, or reconcile legacy 180-day and v2 14-day raw retention."""

from __future__ import annotations

import argparse
import ctypes
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
from typing import Any, Iterable
import uuid


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from sqlalchemy import delete, desc, exists, func, or_, select, text  # noqa: E402
from sqlalchemy.exc import SQLAlchemyError  # noqa: E402
from sqlalchemy.orm import Session, sessionmaker  # noqa: E402
from sqlalchemy import create_engine  # noqa: E402

from backend.app.models import (  # noqa: E402
    ApprovedTrainingArtifact,
    RawCollection,
    RawCollectionChunk,
    RawCollectionDeletionReceipt,
    RawCollectionLegalHoldEvent,
    RawCollectionPurposeDecision,
)
from backend.app.services.raw_collection_storage import (  # noqa: E402
    RAW_RETENTION_CLASS,
    RAW_QUARANTINE_CLASS,
    lock_raw_capacity_reservation_transaction,
    lock_raw_storage_reconciliation_transaction,
    raw_chunk_storage_name,
    raw_storage_has_pending_writes,
)
from backend.app.uploads import (  # noqa: E402
    descriptor_acl_is_absent,
    upload_file_metadata_is_safe,
    validate_upload_directory_descriptor,
)
from scripts.check_report_retention_dry_run import (  # noqa: E402
    exclusive_maintenance_lock,
)
from scripts.walksafe_admin_high_risk_gate import (  # noqa: E402
    walksafe_admin_high_risk_operation,
)


DATABASE_URL_ENV = "WALKSAFE_RAW_RETENTION_DATABASE_URL"
APPLY_CONFIRMATION = "DELETE-EXPIRED-RAW-COLLECTIONS"
JOURNAL_SCHEMA = "walksafe.raw-retention-journal.v1"
RECEIPT_SCHEMA = "walksafe.raw-retention-receipt.v1"
RECOVERY_DIRECTORY_NAME = ".raw-retention-recovery"
JOURNAL_DIRECTORY_NAME = "journals"
RECEIPT_DIRECTORY_NAME = "receipts"
DEFAULT_LIMIT = 25
MAX_LIMIT = 500
MAX_CONTROL_FILE_BYTES = 8 * 1024 * 1024
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_DIRECTORY_FLAGS = (
    os.O_RDONLY
    | getattr(os, "O_CLOEXEC", 0)
    | getattr(os, "O_DIRECTORY", 0)
    | getattr(os, "O_NOFOLLOW", 0)
)
_READ_FLAGS = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
_CREATE_FLAGS = (
    os.O_WRONLY
    | os.O_CREAT
    | os.O_EXCL
    | getattr(os, "O_CLOEXEC", 0)
    | getattr(os, "O_NOFOLLOW", 0)
)


class RawRetentionError(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class RawRetentionCandidate:
    collection_id: uuid.UUID
    retention_expires_at: datetime
    manifest_sha256: str
    receipt_sha256: str
    chunk_count: int
    total_bytes: int
    lifecycle_version: int = 1
    retention_class: str = RAW_RETENTION_CLASS
    state: str = "COMMITTED"
    deletion_reason: str = "LEGACY_RETENTION_EXPIRED"


@dataclass(frozen=True, slots=True)
class RawRetentionChunk:
    collection_id: uuid.UUID
    object_id: uuid.UUID
    chunk_index: int
    storage_name: str
    envelope_size: int
    envelope_sha256: str


def _canonical_time(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise RawRetentionError("timestamp must be timezone-aware")
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def parse_as_of(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("--as-of must be RFC3339 UTC") from exc
    if parsed.utcoffset() != timedelta(0):
        raise argparse.ArgumentTypeError("--as-of must use UTC offset zero")
    return parsed.astimezone(UTC)


def canonical_operation_id(value: str) -> uuid.UUID:
    try:
        parsed = uuid.UUID(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("operation-id must be a canonical UUID") from exc
    if str(parsed) != value:
        raise argparse.ArgumentTypeError("operation-id must be a canonical UUID")
    return parsed


def bounded_limit(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("limit must be an integer") from exc
    if not 1 <= parsed <= MAX_LIMIT:
        raise argparse.ArgumentTypeError(f"limit must be between 1 and {MAX_LIMIT}")
    return parsed


def _canonical_json(payload: dict[str, Any]) -> bytes:
    return json.dumps(payload, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("ascii")


def _digest(payload: object) -> str:
    return hashlib.sha256(
        json.dumps(payload, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("ascii")
    ).hexdigest()


def _candidate_payload(
    candidates: Iterable[RawRetentionCandidate],
    chunks: Iterable[RawRetentionChunk],
) -> list[dict[str, Any]]:
    chunks_by_collection: dict[uuid.UUID, list[RawRetentionChunk]] = {}
    for chunk in chunks:
        chunks_by_collection.setdefault(chunk.collection_id, []).append(chunk)
    result: list[dict[str, Any]] = []
    for candidate in candidates:
        bound_chunks = sorted(
            chunks_by_collection.get(candidate.collection_id, []),
            key=lambda item: (item.object_id, item.chunk_index),
        )
        if len(bound_chunks) != candidate.chunk_count:
            raise RawRetentionError("raw retention chunk inventory does not match its parent")
        result.append(
            {
                "chunk_count": candidate.chunk_count,
                "chunks": [
                    {
                        "chunk_index": item.chunk_index,
                        "envelope_sha256": item.envelope_sha256,
                        "envelope_size": item.envelope_size,
                        "object_id": str(item.object_id),
                        "storage_name": item.storage_name,
                    }
                    for item in bound_chunks
                ],
                "collection_id": str(candidate.collection_id),
                "manifest_sha256": candidate.manifest_sha256,
                "receipt_sha256": candidate.receipt_sha256,
                "retention_expires_at": _canonical_time(candidate.retention_expires_at),
                "lifecycle_version": candidate.lifecycle_version,
                "retention_class": candidate.retention_class,
                "state": candidate.state,
                "deletion_reason": candidate.deletion_reason,
                "total_bytes": candidate.total_bytes,
            }
        )
    if set(chunks_by_collection) != {item.collection_id for item in candidates}:
        raise RawRetentionError("raw retention chunk inventory contains an unexpected parent")
    return result


def candidate_digest(
    candidates: Iterable[RawRetentionCandidate],
    chunks: Iterable[RawRetentionChunk],
) -> str:
    return _digest(_candidate_payload(candidates, chunks))


def _candidate_statement(*, as_of: datetime, limit: int, for_update: bool):
    latest_hold_revision = (
        select(func.max(RawCollectionLegalHoldEvent.revision))
        .where(
            RawCollectionLegalHoldEvent.collection_id
            == RawCollection.collection_id
        )
        .correlate(RawCollection)
        .scalar_subquery()
    )
    active_hold = exists(
        select(RawCollectionLegalHoldEvent.id).where(
            RawCollectionLegalHoldEvent.collection_id
            == RawCollection.collection_id,
            RawCollectionLegalHoldEvent.revision == latest_hold_revision,
            RawCollectionLegalHoldEvent.action == "APPLY",
            RawCollectionLegalHoldEvent.expires_at > as_of,
        )
    )
    effective_expiry = func.coalesce(
        RawCollection.retention_expires_at,
        RawCollection.quarantine_expires_at,
    ).label("retention_expires_at")
    statement = (
        select(
            RawCollection.collection_id,
            effective_expiry,
            RawCollection.manifest_sha256,
            RawCollection.receipt_sha256,
            RawCollection.chunk_count,
            RawCollection.total_bytes,
            RawCollection.lifecycle_version,
            RawCollection.retention_class,
            RawCollection.state,
        )
        .where(
            or_(
                (
                    (RawCollection.lifecycle_version == 1)
                    & (RawCollection.state == "COMMITTED")
                    & (RawCollection.retention_class == RAW_RETENTION_CLASS)
                    & (RawCollection.retention_expires_at <= as_of)
                ),
                (
                    (RawCollection.lifecycle_version == 2)
                    & (RawCollection.state == "QUARANTINED")
                    & (RawCollection.retention_class == RAW_QUARANTINE_CLASS)
                    & (RawCollection.quarantine_expires_at <= as_of)
                ),
            ),
            ~active_hold,
        )
        .order_by(
            effective_expiry,
            RawCollection.collection_id,
        )
        .limit(limit)
    )
    return statement.with_for_update() if for_update else statement


def _load_candidates(
    db: Session,
    *,
    as_of: datetime,
    limit: int,
    for_update: bool,
) -> tuple[list[RawRetentionCandidate], list[RawRetentionChunk]]:
    rows = db.execute(
        _candidate_statement(as_of=as_of, limit=limit, for_update=for_update)
    ).all()
    candidates: list[RawRetentionCandidate] = []
    for row in rows:
        values = dict(row._mapping)
        reason = "LEGACY_RETENTION_EXPIRED"
        if values["lifecycle_version"] == 2:
            decisions = {}
            for scope in ("REPORT", "TRAINING"):
                decisions[scope] = db.scalar(
                    select(RawCollectionPurposeDecision)
                    .where(
                        RawCollectionPurposeDecision.collection_id
                        == values["collection_id"],
                        RawCollectionPurposeDecision.scope == scope,
                    )
                    .order_by(desc(RawCollectionPurposeDecision.revision))
                    .limit(1)
                )
            latest_values = [
                item.decision for item in decisions.values() if item is not None
            ]
            promoted = db.scalar(
                select(ApprovedTrainingArtifact.id)
                .where(
                    ApprovedTrainingArtifact.source_collection_id
                    == values["collection_id"]
                )
                .limit(1)
            ) is not None
            if promoted:
                reason = "PROMOTED_SOURCE_EXPIRED"
            elif "APPROVED" in latest_values:
                reason = "PROMOTION_INCOMPLETE_EXPIRED"
            elif latest_values and all(value == "REJECTED" for value in latest_values):
                reason = "REJECTED"
            else:
                reason = "UNAPPROVED_EXPIRED"
        values["deletion_reason"] = reason
        candidates.append(RawRetentionCandidate(**values))
    if not candidates:
        return [], []
    collection_ids = [item.collection_id for item in candidates]
    chunk_rows = db.execute(
        select(
            RawCollectionChunk.collection_id,
            RawCollectionChunk.object_id,
            RawCollectionChunk.chunk_index,
            RawCollectionChunk.storage_name,
            RawCollectionChunk.envelope_size,
            RawCollectionChunk.envelope_sha256,
        )
        .where(RawCollectionChunk.collection_id.in_(collection_ids))
        .order_by(
            RawCollectionChunk.collection_id,
            RawCollectionChunk.object_id,
            RawCollectionChunk.chunk_index,
        )
    ).all()
    chunks: list[RawRetentionChunk] = []
    for row in chunk_rows:
        values = dict(row._mapping)
        if (
            not isinstance(values["storage_name"], str)
            or values["storage_name"]
            != raw_chunk_storage_name(
                values["collection_id"],
                values["object_id"],
                values["chunk_index"],
            )
            or type(values["envelope_size"]) is not int
            or values["envelope_size"] <= 0
            or not isinstance(values["envelope_sha256"], str)
            or SHA256_PATTERN.fullmatch(values["envelope_sha256"]) is None
        ):
            raise RawRetentionError("committed raw chunk persistence metadata is incomplete")
        chunks.append(RawRetentionChunk(**values))
    candidate_digest(candidates, chunks)
    return candidates, chunks


def preview_raw_collection_retention(
    db: Session,
    *,
    as_of: datetime,
    limit: int,
) -> dict[str, Any]:
    candidates, chunks = _load_candidates(
        db, as_of=as_of, limit=limit, for_update=False
    )
    payload = _candidate_payload(candidates, chunks)
    return {
        "as_of": _canonical_time(as_of),
        "candidate_count": len(candidates),
        "candidate_digest": _digest(payload),
        "candidates": payload,
        "limit": limit,
        "mode": "PREVIEW",
        "schema_version": "walksafe.raw-retention-preview.v1",
    }


def _validate_private_directory(path: Path, *, create: bool) -> int:
    if not path.is_absolute() or path.resolve(strict=False) != path:
        raise RawRetentionError("retention directory must be absolute and canonical")
    if create:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(path, _DIRECTORY_FLAGS)
    metadata = os.fstat(descriptor)
    anchored = os.stat(path, follow_symlinks=False)
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or (metadata.st_dev, metadata.st_ino) != (anchored.st_dev, anchored.st_ino)
        or metadata.st_uid != os.geteuid()
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or not descriptor_acl_is_absent(descriptor)
    ):
        os.close(descriptor)
        raise RawRetentionError("retention directory metadata is unsafe")
    return descriptor


def _open_private_child(parent_fd: int, name: str) -> int:
    descriptor = os.open(name, _DIRECTORY_FLAGS, dir_fd=parent_fd)
    metadata = os.fstat(descriptor)
    anchored = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or (metadata.st_dev, metadata.st_ino) != (anchored.st_dev, anchored.st_ino)
        or metadata.st_uid != os.geteuid()
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or not descriptor_acl_is_absent(descriptor)
    ):
        os.close(descriptor)
        raise RawRetentionError("retention child directory metadata is unsafe")
    return descriptor


def _ensure_private_child(parent_fd: int, name: str) -> int:
    try:
        os.mkdir(name, 0o700, dir_fd=parent_fd)
        os.fsync(parent_fd)
    except FileExistsError:
        pass
    return _open_private_child(parent_fd, name)


def _write_all(descriptor: int, content: bytes) -> None:
    remaining = memoryview(content)
    while remaining:
        written = os.write(descriptor, remaining)
        if written <= 0:
            raise OSError("retention control write made no progress")
        remaining = remaining[written:]


def _write_create_only(parent_fd: int, name: str, payload: dict[str, Any]) -> None:
    content = _canonical_json(payload)
    if not 0 < len(content) <= MAX_CONTROL_FILE_BYTES:
        raise RawRetentionError("retention control document size is invalid")
    temporary_name = f".{name}.{uuid.uuid4().hex}.tmp"
    descriptor: int | None = None
    temporary_exists = False
    linked = False
    try:
        descriptor = os.open(temporary_name, _CREATE_FLAGS, 0o600, dir_fd=parent_fd)
        temporary_exists = True
        os.fchmod(descriptor, 0o600)
        _write_all(descriptor, content)
        os.fsync(descriptor)
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != os.geteuid()
            or metadata.st_nlink != 1
            or stat.S_IMODE(metadata.st_mode) != 0o600
            or not descriptor_acl_is_absent(descriptor)
        ):
            raise RawRetentionError("retention control document metadata is unsafe")
        os.link(
            temporary_name,
            name,
            src_dir_fd=parent_fd,
            dst_dir_fd=parent_fd,
            follow_symlinks=False,
        )
        linked = True
        os.unlink(temporary_name, dir_fd=parent_fd)
        temporary_exists = False
        os.fsync(parent_fd)
    except FileExistsError as exc:
        raise RawRetentionError("retention control document already exists") from exc
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if temporary_exists:
            try:
                os.unlink(temporary_name, dir_fd=parent_fd)
                os.fsync(parent_fd)
            except OSError:
                pass
        if linked and temporary_exists:
            raise RawRetentionError("retention control publication is ambiguous")


def _read_control(parent_fd: int, name: str, *, schema: str) -> dict[str, Any]:
    descriptor = os.open(name, _READ_FLAGS, dir_fd=parent_fd)
    try:
        metadata = os.fstat(descriptor)
        anchored = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or (metadata.st_dev, metadata.st_ino) != (anchored.st_dev, anchored.st_ino)
            or metadata.st_uid != os.geteuid()
            or metadata.st_nlink != 1
            or stat.S_IMODE(metadata.st_mode) != 0o600
            or not descriptor_acl_is_absent(descriptor)
            or not 0 < metadata.st_size <= MAX_CONTROL_FILE_BYTES
        ):
            raise RawRetentionError("retention control document metadata is unsafe")
        content = os.read(descriptor, metadata.st_size + 1)
        if len(content) != metadata.st_size:
            raise RawRetentionError("retention control document length changed")
        payload = json.loads(content.decode("ascii"))
        if not isinstance(payload, dict) or content != _canonical_json(payload):
            raise RawRetentionError("retention control document is not canonical")
        if payload.get("schema_version") != schema:
            raise RawRetentionError("retention control document schema differs")
        try:
            operation_id = uuid.UUID(payload["operation_id"])
        except (KeyError, TypeError, ValueError) as exc:
            raise RawRetentionError("retention control operation-id is invalid") from exc
        if str(operation_id) != payload["operation_id"]:
            raise RawRetentionError("retention control operation-id is not canonical")
        for field in ("candidate_digest", "intent_digest"):
            if (
                not isinstance(payload.get(field), str)
                or SHA256_PATTERN.fullmatch(payload[field]) is None
            ):
                raise RawRetentionError("retention control digest is invalid")
        if schema == JOURNAL_SCHEMA:
            if set(payload) != {
                "candidate_digest", "candidates", "chunks", "intent",
                "intent_digest", "operation_id", "state", "schema_version",
            } or payload["state"] != "PREPARED":
                raise RawRetentionError("retention journal shape is invalid")
            if (
                not isinstance(payload["candidates"], list)
                or not isinstance(payload["chunks"], list)
                or not isinstance(payload["intent"], dict)
                or _digest(payload["candidates"]) != payload["candidate_digest"]
                or _digest(payload["intent"]) != payload["intent_digest"]
            ):
                raise RawRetentionError("retention journal binding is invalid")
        elif set(payload) != {
            "candidate_digest", "collection_ids", "completed_at",
            "intent_digest", "operation_id", "result", "schema_version",
        } or payload["result"] not in {"DELETED", "RECONCILED_PRECOMMIT_ABORTED"}:
            raise RawRetentionError("retention receipt shape is invalid")
        return payload
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError) as exc:
        if isinstance(exc, RawRetentionError):
            raise
        raise RawRetentionError("retention control document is invalid") from exc
    finally:
        os.close(descriptor)


def _rename_noreplace(
    source_fd: int,
    source_name: str,
    target_fd: int,
    target_name: str,
) -> None:
    renameat2 = getattr(ctypes.CDLL(None, use_errno=True), "renameat2", None)
    if renameat2 is None:
        raise RawRetentionError("renameat2(RENAME_NOREPLACE) is unavailable")
    renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    renameat2.restype = ctypes.c_int
    if renameat2(source_fd, os.fsencode(source_name), target_fd, os.fsencode(target_name), 1) != 0:
        number = ctypes.get_errno()
        raise OSError(number, os.strerror(number), source_name)


def _file_identity(metadata: os.stat_result) -> dict[str, int]:
    return {
        "st_dev": metadata.st_dev,
        "st_ino": metadata.st_ino,
        "st_mode": metadata.st_mode,
        "st_uid": metadata.st_uid,
        "st_gid": metadata.st_gid,
        "st_size": metadata.st_size,
        "st_nlink": metadata.st_nlink,
    }


def _validate_chunk_file(root_fd: int, root_metadata: os.stat_result, chunk: RawRetentionChunk) -> dict[str, int]:
    descriptor = os.open(chunk.storage_name, _READ_FLAGS, dir_fd=root_fd)
    try:
        opened = os.fstat(descriptor)
        anchored = os.stat(chunk.storage_name, dir_fd=root_fd, follow_symlinks=False)
        if (
            not upload_file_metadata_is_safe(opened, root_metadata)
            or (opened.st_dev, opened.st_ino) != (anchored.st_dev, anchored.st_ino)
            or not descriptor_acl_is_absent(descriptor)
            or opened.st_size != chunk.envelope_size
        ):
            raise RawRetentionError("raw retention file metadata differs from the database")
        digest = hashlib.sha256()
        remaining = chunk.envelope_size
        while remaining:
            part = os.read(descriptor, min(1024 * 1024, remaining))
            if not part:
                raise RawRetentionError("raw retention file is truncated")
            digest.update(part)
            remaining -= len(part)
        if os.read(descriptor, 1) or digest.hexdigest() != chunk.envelope_sha256:
            raise RawRetentionError("raw retention file digest differs from the database")
        return _file_identity(opened)
    finally:
        os.close(descriptor)


def _validate_recovery_file(
    recovery_fd: int,
    name: str,
    item: dict[str, Any],
) -> None:
    descriptor = os.open(name, _READ_FLAGS, dir_fd=recovery_fd)
    try:
        metadata = os.fstat(descriptor)
        anchored = os.stat(name, dir_fd=recovery_fd, follow_symlinks=False)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or (metadata.st_dev, metadata.st_ino)
            != (anchored.st_dev, anchored.st_ino)
            or metadata.st_nlink != 1
            or _file_identity(metadata) != item["file_identity"]
            or not descriptor_acl_is_absent(descriptor)
        ):
            raise RawRetentionError("raw retention recovery file identity changed")
        digest = hashlib.sha256()
        remaining = item["envelope_size"]
        while remaining:
            part = os.read(descriptor, min(1024 * 1024, remaining))
            if not part:
                raise RawRetentionError("raw retention recovery file is truncated")
            digest.update(part)
            remaining -= len(part)
        if os.read(descriptor, 1) or digest.hexdigest() != item["envelope_sha256"]:
            raise RawRetentionError("raw retention recovery digest changed")
    finally:
        os.close(descriptor)


def _recovery_name(chunk: RawRetentionChunk) -> str:
    return f"{chunk.collection_id}.{chunk.object_id}.{chunk.chunk_index}.wsrc"


def _intent_payload(*, as_of: datetime, limit: int) -> dict[str, Any]:
    return {"as_of": _canonical_time(as_of), "limit": limit}


def _receipt_payload(journal: dict[str, Any], *, result: str) -> dict[str, Any]:
    return {
        "candidate_digest": journal["candidate_digest"],
        "collection_ids": [item["collection_id"] for item in journal["candidates"]],
        "completed_at": _canonical_time(datetime.now(UTC)),
        "intent_digest": journal["intent_digest"],
        "operation_id": journal["operation_id"],
        "result": result,
        "schema_version": RECEIPT_SCHEMA,
    }


def _validate_operation_binding(
    payload: dict[str, Any],
    *,
    operation_id: uuid.UUID,
    intent_digest: str | None,
    expected_candidate_digest: str | None,
) -> None:
    if (
        payload.get("operation_id") != str(operation_id)
        or (intent_digest is not None and payload.get("intent_digest") != intent_digest)
        or (
            expected_candidate_digest is not None
            and payload.get("candidate_digest") != expected_candidate_digest
        )
    ):
        raise RawRetentionError("operation-id is bound to a different retention intent")


def _remove_control(parent_fd: int, name: str) -> None:
    os.unlink(name, dir_fd=parent_fd)
    os.fsync(parent_fd)


def _open_operation_directories(root: Path, *, create: bool) -> tuple[int, int, int]:
    root_fd = _validate_private_directory(root, create=create)
    try:
        journals_fd = _ensure_private_child(root_fd, JOURNAL_DIRECTORY_NAME)
        try:
            receipts_fd = _ensure_private_child(root_fd, RECEIPT_DIRECTORY_NAME)
        except BaseException:
            os.close(journals_fd)
            raise
        return root_fd, journals_fd, receipts_fd
    except BaseException:
        os.close(root_fd)
        raise


def _pending_journal_names(journals_fd: int) -> list[str]:
    names = sorted(os.listdir(journals_fd))
    if any(re.fullmatch(r"[0-9a-f-]{36}\.json", name) is None for name in names):
        raise RawRetentionError("raw retention journal directory contains an unknown entry")
    return names


def _assert_worker_database_role(db: Session) -> None:
    row = db.execute(
        text(
            "SELECT "
            "has_schema_privilege(current_user, 'public', 'USAGE') AS schema_usage, "
            "has_schema_privilege(current_user, 'public', 'CREATE') AS schema_create, "
            "has_table_privilege(current_user, 'public.raw_collections', 'SELECT') AS parent_select, "
            "has_table_privilege(current_user, 'public.raw_collections', 'DELETE') AS parent_delete, "
            "has_table_privilege(current_user, 'public.raw_collections', 'INSERT,UPDATE,TRUNCATE') AS parent_mutate, "
            "has_table_privilege(current_user, 'public.raw_collection_objects', 'SELECT') AS object_select, "
            "has_table_privilege(current_user, 'public.raw_collection_objects', 'DELETE,INSERT,UPDATE,TRUNCATE') AS object_mutate, "
            "has_table_privilege(current_user, 'public.raw_collection_chunks', 'SELECT') AS chunk_select, "
            "has_table_privilege(current_user, 'public.raw_collection_chunks', 'DELETE,INSERT,UPDATE,TRUNCATE') AS chunk_mutate, "
            "has_table_privilege(current_user, 'public.raw_collection_purpose_decisions', 'SELECT') AS decision_select, "
            "has_table_privilege(current_user, 'public.raw_collection_legal_hold_events', 'SELECT') AS hold_select, "
            "has_table_privilege(current_user, 'public.raw_collection_deletion_receipts', 'INSERT') AS receipt_insert, "
            "has_table_privilege(current_user, 'public.approved_training_artifacts', 'SELECT') AS artifact_select, "
            "has_table_privilege(current_user, 'public.reports', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE') AS report_access, "
            "has_table_privilege(current_user, 'public.privacy_consent_events', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE') AS privacy_access, "
            "has_table_privilege(current_user, 'public.account_deletion_requests', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE') AS account_access"
        )
    ).mappings().one()
    if (
        not row["schema_usage"]
        or row["schema_create"]
        or not row["parent_select"]
        or not row["parent_delete"]
        or row["parent_mutate"]
        or not row["object_select"]
        or row["object_mutate"]
        or not row["chunk_select"]
        or row["chunk_mutate"]
        or not row["decision_select"]
        or not row["hold_select"]
        or not row["receipt_insert"]
        or not row["artifact_select"]
        or row["report_access"]
        or row["privacy_access"]
        or row["account_access"]
    ):
        raise RawRetentionError("raw retention worker database privileges differ")


def _reconcile_journal(
    db: Session,
    *,
    raw_root: Path,
    operation_root: Path,
    operation_id: uuid.UUID,
    expected_intent_digest: str | None = None,
    expected_candidate_digest: str | None = None,
) -> dict[str, Any]:
    operation_fd, journals_fd, receipts_fd = _open_operation_directories(operation_root, create=False)
    raw_fd = os.open(raw_root, _DIRECTORY_FLAGS)
    recovery_base_fd: int | None = None
    recovery_fd: int | None = None
    name = f"{operation_id}.json"
    try:
        journal = _read_control(journals_fd, name, schema=JOURNAL_SCHEMA)
        _validate_operation_binding(
            journal,
            operation_id=operation_id,
            intent_digest=expected_intent_digest,
            expected_candidate_digest=expected_candidate_digest,
        )
        collection_ids = [uuid.UUID(item["collection_id"]) for item in journal["candidates"]]
        existing = set(
            db.scalars(
                select(RawCollection.collection_id).where(
                    RawCollection.collection_id.in_(collection_ids)
                ).with_for_update()
            ).all()
        )
        if existing and existing != set(collection_ids):
            raise RawRetentionError("mixed raw retention database state requires manual recovery")
        try:
            recovery_base_fd = _open_private_child(
                raw_fd, RECOVERY_DIRECTORY_NAME
            )
            try:
                recovery_fd = _open_private_child(
                    recovery_base_fd, str(operation_id)
                )
            except FileNotFoundError:
                recovery_fd = None
        except FileNotFoundError:
            recovery_base_fd = None
            recovery_fd = None
        root_metadata = validate_upload_directory_descriptor(raw_root, raw_fd)
        if existing:
            for item in journal["chunks"]:
                original = item["storage_name"]
                recovery = item["recovery_name"]
                original_exists = _entry_exists(raw_fd, original)
                recovery_exists = (
                    recovery_fd is not None
                    and _entry_exists(recovery_fd, recovery)
                )
                if original_exists == recovery_exists:
                    raise RawRetentionError("ambiguous precommit raw retention file state")
                if recovery_exists:
                    assert recovery_fd is not None
                    _validate_recovery_file(recovery_fd, recovery, item)
                    _rename_noreplace(recovery_fd, recovery, raw_fd, original)
                    os.fsync(recovery_fd)
                    os.fsync(raw_fd)
                chunk = RawRetentionChunk(
                    collection_id=uuid.UUID(item["collection_id"]),
                    object_id=uuid.UUID(item["object_id"]),
                    chunk_index=item["chunk_index"],
                    storage_name=original,
                    envelope_size=item["envelope_size"],
                    envelope_sha256=item["envelope_sha256"],
                )
                _validate_chunk_file(raw_fd, root_metadata, chunk)
            result = "RECONCILED_PRECOMMIT_ABORTED"
        else:
            for item in journal["chunks"]:
                if _entry_exists(raw_fd, item["storage_name"]):
                    raise RawRetentionError("postcommit raw retention restored an original unexpectedly")
                if recovery_fd is not None and _entry_exists(
                    recovery_fd, item["recovery_name"]
                ):
                    _validate_recovery_file(
                        recovery_fd, item["recovery_name"], item
                    )
                    os.unlink(item["recovery_name"], dir_fd=recovery_fd)
            if recovery_fd is not None:
                os.fsync(recovery_fd)
            result = "DELETED"
        if recovery_fd is not None:
            os.close(recovery_fd)
            recovery_fd = None
            assert recovery_base_fd is not None
            os.rmdir(str(operation_id), dir_fd=recovery_base_fd)
            os.fsync(recovery_base_fd)
        if recovery_base_fd is not None:
            os.close(recovery_base_fd)
            recovery_base_fd = None
            try:
                os.rmdir(RECOVERY_DIRECTORY_NAME, dir_fd=raw_fd)
                os.fsync(raw_fd)
            except OSError:
                pass
        receipt = _receipt_payload(journal, result=result)
        try:
            _write_create_only(receipts_fd, name, receipt)
        except RawRetentionError:
            existing_receipt = _read_control(receipts_fd, name, schema=RECEIPT_SCHEMA)
            _validate_operation_binding(
                existing_receipt,
                operation_id=operation_id,
                intent_digest=journal["intent_digest"],
                expected_candidate_digest=journal["candidate_digest"],
            )
            receipt = existing_receipt
        _remove_control(journals_fd, name)
        return receipt
    finally:
        if recovery_fd is not None:
            os.close(recovery_fd)
        if recovery_base_fd is not None:
            os.close(recovery_base_fd)
        os.close(raw_fd)
        os.close(receipts_fd)
        os.close(journals_fd)
        os.close(operation_fd)


def _entry_exists(directory_fd: int, name: str) -> bool:
    try:
        os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        return True
    except FileNotFoundError:
        return False


def _recovery_operation_exists(raw_root: Path, operation_id: uuid.UUID) -> bool:
    root_fd = os.open(raw_root, _DIRECTORY_FLAGS)
    try:
        validate_upload_directory_descriptor(raw_root, root_fd)
        try:
            recovery_fd = _open_private_child(
                root_fd, RECOVERY_DIRECTORY_NAME
            )
        except FileNotFoundError:
            return False
        try:
            return _entry_exists(recovery_fd, str(operation_id))
        finally:
            os.close(recovery_fd)
    finally:
        os.close(root_fd)


def apply_raw_collection_retention(
    db: Session,
    *,
    raw_root: Path,
    operation_root: Path,
    as_of: datetime,
    limit: int,
    operation_id: uuid.UUID,
    expected_candidate_digest: str,
    create_operation_root: bool,
) -> dict[str, Any]:
    if raw_storage_has_pending_writes(raw_root):
        raise RawRetentionError("pending raw write journal blocks retention")
    lock_raw_storage_reconciliation_transaction(db)
    lock_raw_capacity_reservation_transaction(db)
    operation_fd, journals_fd, receipts_fd = _open_operation_directories(
        operation_root, create=create_operation_root
    )
    name = f"{operation_id}.json"
    intent = _intent_payload(as_of=as_of, limit=limit)
    intent_digest = _digest(intent)
    try:
        if name in os.listdir(receipts_fd):
            receipt = _read_control(receipts_fd, name, schema=RECEIPT_SCHEMA)
            _validate_operation_binding(
                receipt,
                operation_id=operation_id,
                intent_digest=intent_digest,
                expected_candidate_digest=expected_candidate_digest,
            )
            if name in os.listdir(journals_fd):
                if _recovery_operation_exists(raw_root, operation_id):
                    raise RawRetentionError(
                        "completed receipt still has recovery state; reconcile is required"
                    )
                _remove_control(journals_fd, name)
            return receipt
        pending = _pending_journal_names(journals_fd)
        if pending:
            raise RawRetentionError("pending raw retention journal requires reconcile")

        candidates, chunks = _load_candidates(
            db, as_of=as_of, limit=limit, for_update=True
        )
        observed_digest = candidate_digest(candidates, chunks)
        if observed_digest != expected_candidate_digest:
            raise RawRetentionError("raw retention candidate digest changed")
        if not candidates:
            raise RawRetentionError("raw retention apply requires at least one candidate")

        raw_fd = os.open(raw_root, _DIRECTORY_FLAGS)
        recovery_base_fd: int | None = None
        recovery_fd: int | None = None
        journal_created = False
        try:
            root_metadata = validate_upload_directory_descriptor(raw_root, raw_fd)
            chunk_journal: list[dict[str, Any]] = []
            for chunk in chunks:
                identity = _validate_chunk_file(raw_fd, root_metadata, chunk)
                chunk_journal.append(
                    {
                        "chunk_index": chunk.chunk_index,
                        "collection_id": str(chunk.collection_id),
                        "envelope_sha256": chunk.envelope_sha256,
                        "envelope_size": chunk.envelope_size,
                        "file_identity": identity,
                        "object_id": str(chunk.object_id),
                        "recovery_name": _recovery_name(chunk),
                        "storage_name": chunk.storage_name,
                    }
                )
            journal = {
                "candidate_digest": observed_digest,
                "candidates": _candidate_payload(candidates, chunks),
                "chunks": chunk_journal,
                "intent": intent,
                "intent_digest": intent_digest,
                "operation_id": str(operation_id),
                "state": "PREPARED",
                "schema_version": JOURNAL_SCHEMA,
            }
            _write_create_only(journals_fd, name, journal)
            journal_created = True

            recovery_base_fd = _ensure_private_child(raw_fd, RECOVERY_DIRECTORY_NAME)
            try:
                os.mkdir(str(operation_id), 0o700, dir_fd=recovery_base_fd)
                os.fsync(recovery_base_fd)
            except FileExistsError as exc:
                raise RawRetentionError("raw retention recovery operation already exists") from exc
            recovery_fd = _open_private_child(recovery_base_fd, str(operation_id))
            for item in chunk_journal:
                _rename_noreplace(
                    raw_fd,
                    item["storage_name"],
                    recovery_fd,
                    item["recovery_name"],
                )
            os.fsync(raw_fd)
            os.fsync(recovery_fd)
            deleted_at = datetime.now(UTC).replace(microsecond=0)
            chunks_by_collection: dict[uuid.UUID, list[RawRetentionChunk]] = {}
            for chunk in chunks:
                chunks_by_collection.setdefault(chunk.collection_id, []).append(chunk)
            for candidate in candidates:
                inventory_sha256 = _digest(
                    [
                        {
                            "chunk_index": chunk.chunk_index,
                            "envelope_sha256": chunk.envelope_sha256,
                            "envelope_size": chunk.envelope_size,
                            "object_id": str(chunk.object_id),
                            "storage_name": chunk.storage_name,
                        }
                        for chunk in chunks_by_collection[candidate.collection_id]
                    ]
                )
                receipt_payload = {
                    "collection_id": str(candidate.collection_id),
                    "deleted_at": _canonical_time(deleted_at),
                    "inventory_sha256": inventory_sha256,
                    "reason": candidate.deletion_reason,
                    "source_manifest_sha256": candidate.manifest_sha256,
                    "source_receipt_sha256": candidate.receipt_sha256,
                }
                db.add(
                    RawCollectionDeletionReceipt(
                        collection_id=candidate.collection_id,
                        reason=candidate.deletion_reason,
                        source_manifest_sha256=candidate.manifest_sha256,
                        source_receipt_sha256=candidate.receipt_sha256,
                        inventory_sha256=inventory_sha256,
                        chunk_count=candidate.chunk_count,
                        total_bytes=candidate.total_bytes,
                        receipt_sha256=_digest(receipt_payload),
                        deleted_at=deleted_at,
                    )
                )
            db.flush()
            result = db.execute(
                delete(RawCollection).where(
                    RawCollection.collection_id.in_(
                        [item.collection_id for item in candidates]
                    )
                )
            )
            if result.rowcount != len(candidates):
                raise RawRetentionError("raw retention parent delete count changed")
            db.commit()
        except BaseException:
            db.rollback()
            if journal_created:
                # Preserve journal and recovery evidence. Reconcile determines
                # precommit/postcommit state without guessing mixed outcomes.
                pass
            raise
        finally:
            if recovery_fd is not None:
                os.close(recovery_fd)
            if recovery_base_fd is not None:
                os.close(recovery_base_fd)
            os.close(raw_fd)
    finally:
        os.close(receipts_fd)
        os.close(journals_fd)
        os.close(operation_fd)
    return reconcile_raw_collection_retention(
        db,
        raw_root=raw_root,
        operation_root=operation_root,
        operation_id=operation_id,
        expected_intent_digest=intent_digest,
        expected_candidate_digest=expected_candidate_digest,
    )


def reconcile_raw_collection_retention(
    db: Session,
    *,
    raw_root: Path,
    operation_root: Path,
    operation_id: uuid.UUID,
    expected_intent_digest: str | None = None,
    expected_candidate_digest: str | None = None,
) -> dict[str, Any]:
    if raw_storage_has_pending_writes(raw_root):
        raise RawRetentionError("pending raw write journal blocks retention reconcile")
    lock_raw_storage_reconciliation_transaction(db)
    lock_raw_capacity_reservation_transaction(db)
    return _reconcile_journal(
        db,
        raw_root=raw_root,
        operation_root=operation_root,
        operation_id=operation_id,
        expected_intent_digest=expected_intent_digest,
        expected_candidate_digest=expected_candidate_digest,
    )


def _database_url() -> str:
    value = os.environ.get(DATABASE_URL_ENV, "").strip()
    if not value:
        raise RawRetentionError(f"{DATABASE_URL_ENV} is required")
    return value


def _canonical_existing_directory(path: Path, *, label: str) -> Path:
    if not path.is_absolute():
        raise RawRetentionError(f"{label} must be absolute")
    try:
        resolved = path.resolve(strict=True)
    except OSError as exc:
        raise RawRetentionError(f"{label} must already exist") from exc
    if resolved != path or not resolved.is_dir():
        raise RawRetentionError(f"{label} must be a canonical directory")
    return path


def _canonical_operation_root(path: Path, *, create: bool) -> Path:
    if not path.is_absolute():
        raise RawRetentionError("operation root must be absolute")
    if path.exists():
        return _canonical_existing_directory(path, label="operation root")
    if not create:
        raise RawRetentionError("operation root must already exist")
    try:
        parent = path.parent.resolve(strict=True)
    except OSError as exc:
        raise RawRetentionError("operation root parent must already exist") from exc
    if parent != path.parent or not parent.is_dir():
        raise RawRetentionError("operation root parent must be canonical")
    return path


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--local-isolated", action="store_true")
    mode.add_argument("--manual-one-shot", action="store_true")
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--apply", action="store_true")
    action.add_argument("--reconcile", action="store_true")
    parser.add_argument("--as-of", type=parse_as_of)
    parser.add_argument("--limit", type=bounded_limit, default=DEFAULT_LIMIT)
    parser.add_argument("--operation-id", type=canonical_operation_id)
    parser.add_argument("--candidate-digest")
    parser.add_argument("--confirm")
    parser.add_argument("--raw-object-dir", type=Path, required=True)
    parser.add_argument("--operation-root", type=Path, required=True)
    parser.add_argument("--maintenance-lock-path", type=Path, required=True)
    parser.add_argument("--maintenance-lock-group-gid", type=int)
    return parser


def _validate_mode(
    args: argparse.Namespace,
    *,
    now: datetime | None = None,
) -> None:
    environment = os.environ.get("WALKSAFE_ENVIRONMENT", "").strip().lower()
    if args.local_isolated and environment not in {"development", "test"}:
        raise RawRetentionError("local isolated mode requires development or test")
    if args.manual_one_shot and environment not in {"staging", "production"}:
        raise RawRetentionError("manual one-shot mode requires staging or production")
    if args.manual_one_shot and os.geteuid() == 0:
        raise RawRetentionError("manual one-shot mode must not run as root")
    if (args.apply or args.reconcile) and args.confirm != APPLY_CONFIRMATION:
        raise RawRetentionError(f"mutation requires --confirm {APPLY_CONFIRMATION}")
    observed_at = (now or datetime.now(UTC)).astimezone(UTC)
    if args.as_of is not None and args.as_of > observed_at:
        raise RawRetentionError("--as-of must not be in the future")
    if args.apply:
        if args.as_of is None or args.operation_id is None:
            raise RawRetentionError("apply requires --as-of and --operation-id")
        if not isinstance(args.candidate_digest, str) or SHA256_PATTERN.fullmatch(args.candidate_digest) is None:
            raise RawRetentionError("apply requires the preview candidate digest")
    elif args.reconcile:
        if args.operation_id is None:
            raise RawRetentionError("reconcile requires --operation-id")
        if args.as_of is not None or args.candidate_digest is not None:
            raise RawRetentionError("reconcile reads intent from its immutable journal")
    elif args.as_of is None:
        raise RawRetentionError("preview requires --as-of")


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    engine = None
    try:
        _validate_mode(args)
        database_url = _database_url()
        raw_root = _canonical_existing_directory(
            args.raw_object_dir, label="raw object directory"
        )
        operation_root = _canonical_operation_root(
            args.operation_root, create=args.local_isolated
        )
        engine = create_engine(database_url, pool_pre_ping=True)
        SessionFactory = sessionmaker(engine, expire_on_commit=False)
        if not args.apply and not args.reconcile:
            with SessionFactory() as db:
                result = preview_raw_collection_retention(
                    db, as_of=args.as_of, limit=args.limit
                )
                db.rollback()
        else:
            authority = walksafe_admin_high_risk_operation(
                "DATA_DELETE", database_url=database_url
            )
            with authority:
                with exclusive_maintenance_lock(
                    args.maintenance_lock_path,
                    expected_group_gid=args.maintenance_lock_group_gid,
                ):
                    with SessionFactory() as db:
                        _assert_worker_database_role(db)
                        db.rollback()
                        if args.apply:
                            result = apply_raw_collection_retention(
                                db,
                                raw_root=raw_root,
                                operation_root=operation_root,
                                as_of=args.as_of,
                                limit=args.limit,
                                operation_id=args.operation_id,
                                expected_candidate_digest=args.candidate_digest,
                                create_operation_root=args.local_isolated,
                            )
                        else:
                            result = reconcile_raw_collection_retention(
                                db,
                                raw_root=raw_root,
                                operation_root=operation_root,
                                operation_id=args.operation_id,
                            )
        print(_canonical_json(result).decode("ascii"))
        return 0
    except (OSError, RawRetentionError, SQLAlchemyError, ValueError) as exc:
        print(f"raw retention failed: {exc}", file=sys.stderr)
        return 1
    finally:
        if engine is not None:
            engine.dispose()


if __name__ == "__main__":
    raise SystemExit(main())
