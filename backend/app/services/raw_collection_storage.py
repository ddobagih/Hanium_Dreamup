"""Durable raw storage and immutable commit receipt for the B1c slice."""

from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
import errno
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import stat
from typing import Any, Callable
import uuid

from sqlalchemy import func, select, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.config import DEPLOYMENT_ENVIRONMENTS
from backend.app.models import (
    RawCollection,
    RawCollectionChunk,
    RawCollectionObject,
)
from backend.app.schemas import (
    RawCollectionChunkAckV1,
    RawCollectionCommitV1,
    RawCollectionManifestV1,
    RawCollectionReceiptV1,
    RawCollectionReceiptV2,
    RawCollectionStatusV1,
    raw_collection_receipt_sha256,
    raw_collection_receipt_v2_sha256,
)
from backend.app.services.capacity_state import (
    CapacityLevel,
    CapacityState,
    CapacityStateUnavailable,
)
from backend.app.services.privacy_lifecycle import privacy_subject_hmac
from backend.app.services.raw_collection_crypto import (
    AAD_VERSION,
    ENVELOPE_VERSION,
    MAX_ENVELOPE_BYTES,
    MAX_PLAINTEXT_BYTES,
    RawCollectionChunkBinding,
    RawCollectionChunkCryptoError,
    decrypt_raw_collection_chunk,
    encrypt_raw_collection_chunk,
    parse_raw_collection_chunk_envelope,
)
from backend.app.services.raw_collection_ingest import (
    RawCollectionAdmission,
    RawCollectionStorageError,
)
from backend.app.services.report_image_keys import (
    ReportImageKeyError,
    ReportImageKeyManager,
)
from backend.app.uploads import (
    PRIVATE_UPLOAD_DIRECTORY_MODE,
    descriptor_acl_is_absent,
    expected_upload_file_mode,
    upload_file_metadata_is_safe,
    validate_upload_directory_descriptor,
)


_STORAGE_NAME_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\."
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\."
    r"[0-9]{1,4}\.wsrc$"
)
_DIRECTORY_FLAGS = (
    os.O_RDONLY
    | getattr(os, "O_CLOEXEC", 0)
    | getattr(os, "O_DIRECTORY", 0)
    | getattr(os, "O_NOFOLLOW", 0)
)
_READ_FLAGS = (
    os.O_RDONLY
    | getattr(os, "O_CLOEXEC", 0)
    | getattr(os, "O_NOFOLLOW", 0)
)
_WRITE_FLAGS = (
    os.O_WRONLY
    | os.O_CREAT
    | os.O_EXCL
    | getattr(os, "O_CLOEXEC", 0)
    | getattr(os, "O_NOFOLLOW", 0)
)
RAW_JOURNAL_DIRECTORY_NAME = ".raw-write-journal"
RAW_JOURNAL_SCHEMA = "walksafe.raw-chunk-write.v1"
MAX_PENDING_RAW_CHUNK_WRITES = 10_000
RAW_STORAGE_TRANSACTION_LOCK_KEY = "walksafe-raw-storage-reconciliation-v1"
RAW_CAPACITY_RESERVATION_LOCK_KEY = "walksafe-raw-capacity-reservation-v1"
RAW_LEGACY_RETENTION_CLASS = "RAW_ORIGINAL_180D"
RAW_QUARANTINE_CLASS = "RAW_QUARANTINE_14D"
# Kept as a compatibility alias for the legacy retention worker and imports.
RAW_RETENTION_CLASS = RAW_LEGACY_RETENTION_CLASS
RAW_ENVELOPE_RESERVATION_OVERHEAD = MAX_ENVELOPE_BYTES - MAX_PLAINTEXT_BYTES
RAW_JOURNAL_MAX_BYTES = 16_384
RAW_CAPACITY_FILE_INODES_PER_CHUNK = 2
RAW_CAPACITY_DIRECTORY_BLOCKS_PER_CHUNK = 2
_RAW_CAPACITY_HOLD_LEVELS = frozenset(
    {
        CapacityLevel.HOLD_NEW_RAW_COLLECTION_SESSIONS,
        CapacityLevel.HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES,
    }
)
_KEY_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_JOURNAL_TIME_PATTERN = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z$"
)


@dataclass(frozen=True, slots=True)
class PersistedRawChunkFile:
    storage_name: str
    envelope_sha256: str
    envelope_size: int


@dataclass(frozen=True, slots=True)
class RawChunkCommitMetadata:
    privacy_subject_hmac: str
    account_generation: int
    collection_id: uuid.UUID
    object_id: uuid.UUID
    chunk_index: int
    manifest_sha256: str
    consent_receipt_sha256: str
    purpose: str
    walk_id: uuid.UUID
    content_type: str
    plaintext_size: int
    plaintext_sha256: str
    storage_name: str
    envelope_version: int
    algorithm: str
    aad_version: int
    key_id: str
    nonce: bytes
    envelope_sha256: str
    envelope_size: int
    persisted_at: datetime


@dataclass(frozen=True, slots=True)
class PendingRawChunkWrite:
    metadata: RawChunkCommitMetadata
    destination: Path
    journal_path: Path


@dataclass(frozen=True, slots=True)
class RawChunkCommitState:
    chunk_exists: bool
    metadata: RawChunkCommitMetadata | None


@dataclass(frozen=True, slots=True)
class RawStorageReconciliation:
    retained_committed: int
    removed_orphans: int


def _storage_error(code: str, message: str) -> RawCollectionStorageError:
    return RawCollectionStorageError(
        code=code,
        message=message,
        status_code=503,
    )


def _not_found() -> RawCollectionStorageError:
    return RawCollectionStorageError(
        code="raw_collection_not_found",
        message="The raw collection is not visible to this actor.",
        status_code=404,
    )


def prepare_raw_object_directory(settings: Any) -> None:
    """Create only a local private root; deployment roots are pre-provisioned."""

    root = getattr(settings, "raw_object_dir", None)
    if root is None:
        return
    root = Path(root)
    deployment = (
        getattr(settings, "walksafe_environment", "development")
        in DEPLOYMENT_ENVIRONMENTS
    )
    if not deployment:
        try:
            root.mkdir(
                parents=True,
                exist_ok=False,
                mode=PRIVATE_UPLOAD_DIRECTORY_MODE,
            )
        except FileExistsError:
            pass
    _validate_raw_object_directory(settings)


def _validate_raw_object_directory(settings: Any) -> None:
    root = getattr(settings, "raw_object_dir", None)
    if root is None:
        raise RuntimeError("raw object root is not configured")
    root = Path(root)
    deployment = (
        getattr(settings, "walksafe_environment", "development")
        in DEPLOYMENT_ENVIRONMENTS
    )
    try:
        metadata = root.stat(follow_symlinks=False)
        descriptor = os.open(root, _DIRECTORY_FLAGS)
        try:
            descriptor_metadata = validate_upload_directory_descriptor(
                root,
                descriptor,
            )
        finally:
            os.close(descriptor)
    except (OSError, ValueError) as exc:
        raise RuntimeError(
            "raw object root must be a private real directory"
        ) from exc
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or root.is_symlink()
        or metadata.st_uid != os.geteuid()
        or (
            not deployment
            and stat.S_IMODE(metadata.st_mode) != PRIVATE_UPLOAD_DIRECTORY_MODE
        )
        or (metadata.st_dev, metadata.st_ino)
        != (descriptor_metadata.st_dev, descriptor_metadata.st_ino)
    ):
        raise RuntimeError("raw object root must be a private real directory")


def probe_raw_object_directory(settings: Any) -> None:
    """Prove raw-root metadata plus write, link, unlink, and fsync support."""

    _validate_raw_object_directory(settings)
    root = Path(settings.raw_object_dir)
    token = uuid.uuid4().hex
    temporary_name = f".readiness-{token}.tmp"
    linked_name = f".readiness-{token}.link"
    directory_descriptor: int | None = None
    file_descriptor: int | None = None
    temporary_created = False
    linked_created = False
    cleanup_changed_directory = False
    try:
        directory_descriptor = os.open(root, _DIRECTORY_FLAGS)
        directory_metadata = validate_upload_directory_descriptor(
            root,
            directory_descriptor,
        )
        file_mode = expected_upload_file_mode(directory_metadata)
        file_descriptor = os.open(
            temporary_name,
            _WRITE_FLAGS,
            file_mode,
            dir_fd=directory_descriptor,
        )
        temporary_created = True
        os.fchmod(file_descriptor, file_mode)
        if (
            not upload_file_metadata_is_safe(
                os.fstat(file_descriptor),
                directory_metadata,
            )
            or not descriptor_acl_is_absent(file_descriptor)
        ):
            raise OSError("raw readiness probe file metadata is unsafe")
        _write_all(file_descriptor, b"ready")
        os.fsync(file_descriptor)
        validate_upload_directory_descriptor(
            root,
            directory_descriptor,
            expected_metadata=directory_metadata,
        )
        os.link(
            temporary_name,
            linked_name,
            src_dir_fd=directory_descriptor,
            dst_dir_fd=directory_descriptor,
            follow_symlinks=False,
        )
        linked_created = True
        os.fsync(directory_descriptor)
        os.unlink(linked_name, dir_fd=directory_descriptor)
        linked_created = False
        os.unlink(temporary_name, dir_fd=directory_descriptor)
        temporary_created = False
        os.fsync(directory_descriptor)
        validate_upload_directory_descriptor(
            root,
            directory_descriptor,
            expected_metadata=directory_metadata,
        )
    finally:
        if file_descriptor is not None:
            os.close(file_descriptor)
        if directory_descriptor is not None:
            if linked_created:
                try:
                    os.unlink(linked_name, dir_fd=directory_descriptor)
                    cleanup_changed_directory = True
                except OSError:
                    pass
            if temporary_created:
                try:
                    os.unlink(temporary_name, dir_fd=directory_descriptor)
                    cleanup_changed_directory = True
                except OSError:
                    pass
            if cleanup_changed_directory:
                try:
                    os.fsync(directory_descriptor)
                except OSError:
                    pass
            os.close(directory_descriptor)


def raw_chunk_storage_name(
    collection_id: uuid.UUID,
    object_id: uuid.UUID,
    index: int,
) -> str:
    if type(index) is not int or not 0 <= index < 2_048:
        raise ValueError("raw chunk index is outside the contract")
    name = f"{collection_id}.{object_id}.{index}.wsrc"
    if _STORAGE_NAME_PATTERN.fullmatch(name) is None:
        raise ValueError("raw chunk storage identity is invalid")
    return name


def lock_raw_storage_write_transaction(db: Session) -> None:
    """Keep reconciliation outside the raw file/database commit boundary."""

    db.execute(
        text("SELECT pg_advisory_xact_lock_shared(hashtextextended(:lock_key, 0))"),
        {"lock_key": RAW_STORAGE_TRANSACTION_LOCK_KEY},
    )


def lock_raw_storage_reconciliation_transaction(db: Session) -> None:
    """Serialize startup reconciliation with writers and backend replicas."""

    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": RAW_STORAGE_TRANSACTION_LOCK_KEY},
    )


def lock_raw_capacity_reservation_transaction(db: Session) -> None:
    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": RAW_CAPACITY_RESERVATION_LOCK_KEY},
    )


def _raw_filesystem_capacity(root: Path) -> tuple[int, int, int]:
    descriptor = os.open(root, _DIRECTORY_FLAGS)
    try:
        opened = validate_upload_directory_descriptor(root, descriptor)
        filesystem = os.fstatvfs(descriptor)
        current = os.fstat(descriptor)
        anchored = os.stat(root, follow_symlinks=False)
        if (
            (current.st_dev, current.st_ino) != (opened.st_dev, opened.st_ino)
            or (anchored.st_dev, anchored.st_ino)
            != (opened.st_dev, opened.st_ino)
            or type(filesystem.f_bavail) is not int
            or type(filesystem.f_frsize) is not int
            or type(filesystem.f_favail) is not int
            or filesystem.f_bavail < 0
            or filesystem.f_frsize <= 0
            or filesystem.f_favail < 0
        ):
            raise OSError("raw capacity filesystem changed")
        return (
            filesystem.f_bavail * filesystem.f_frsize,
            filesystem.f_frsize,
            filesystem.f_favail,
        )
    finally:
        os.close(descriptor)


def _raw_capacity_reservation_bytes(
    plaintext_bytes: int,
    chunk_count: int,
    block_size: int,
) -> int:
    if plaintext_bytes < 0 or chunk_count < 0 or block_size <= 0:
        raise ValueError("raw capacity reservation inputs are invalid")
    journal_blocks = (
        RAW_JOURNAL_MAX_BYTES + block_size - 1
    ) // block_size
    per_chunk_overhead = (
        RAW_ENVELOPE_RESERVATION_OVERHEAD
        + block_size - 1
        + journal_blocks * block_size
        + RAW_CAPACITY_DIRECTORY_BLOCKS_PER_CHUNK * block_size
    )
    return plaintext_bytes + chunk_count * per_chunk_overhead


def pending_raw_journal_directory(root: Path) -> Path:
    return root / RAW_JOURNAL_DIRECTORY_NAME


def raw_storage_has_pending_writes(root: Path) -> bool:
    """Check the descriptor-anchored raw journal before exclusive maintenance."""

    try:
        root_descriptor, journal_descriptor = _open_raw_journal_directory(
            root,
            create=False,
        )
    except FileNotFoundError:
        return False
    try:
        return bool(os.listdir(journal_descriptor))
    finally:
        os.close(journal_descriptor)
        os.close(root_descriptor)


def _encode_base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _decode_nonce(value: object) -> bytes:
    if not isinstance(value, str) or "=" in value:
        raise ValueError("raw journal nonce is invalid")
    decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    if len(decoded) != 12 or _encode_base64url(decoded) != value:
        raise ValueError("raw journal nonce is invalid")
    return decoded


def _journal_name(storage_name: str) -> str:
    if _STORAGE_NAME_PATTERN.fullmatch(storage_name) is None:
        raise ValueError("raw journal storage name is invalid")
    return f"{storage_name}.json"


def _open_raw_journal_directory(root: Path, *, create: bool) -> tuple[int, int]:
    root_descriptor = os.open(root, _DIRECTORY_FLAGS)
    journal_descriptor: int | None = None
    try:
        validate_upload_directory_descriptor(root, root_descriptor)
        if create:
            try:
                os.mkdir(
                    RAW_JOURNAL_DIRECTORY_NAME,
                    PRIVATE_UPLOAD_DIRECTORY_MODE,
                    dir_fd=root_descriptor,
                )
                os.fsync(root_descriptor)
            except FileExistsError:
                pass
        journal_descriptor = os.open(
            RAW_JOURNAL_DIRECTORY_NAME,
            _DIRECTORY_FLAGS,
            dir_fd=root_descriptor,
        )
        journal_metadata = os.fstat(journal_descriptor)
        journal_path_metadata = os.stat(
            RAW_JOURNAL_DIRECTORY_NAME,
            dir_fd=root_descriptor,
            follow_symlinks=False,
        )
        if (
            not stat.S_ISDIR(journal_metadata.st_mode)
            or (journal_metadata.st_dev, journal_metadata.st_ino)
            != (journal_path_metadata.st_dev, journal_path_metadata.st_ino)
            or journal_metadata.st_uid != os.geteuid()
            or stat.S_IMODE(journal_metadata.st_mode)
            != PRIVATE_UPLOAD_DIRECTORY_MODE
            or not descriptor_acl_is_absent(journal_descriptor)
        ):
            raise OSError("raw journal directory is not private")
        validate_upload_directory_descriptor(
            root,
            root_descriptor,
        )
        return root_descriptor, journal_descriptor
    except BaseException:
        if journal_descriptor is not None:
            os.close(journal_descriptor)
        os.close(root_descriptor)
        raise


def _write_raw_journal(root: Path, name: str, content: bytes) -> Path:
    root_descriptor: int | None = None
    journal_descriptor: int | None = None
    file_descriptor: int | None = None
    temporary_name = f".{name}.{uuid.uuid4().hex}.tmp"
    temporary_created = False
    final_linked = False
    try:
        root_descriptor, journal_descriptor = _open_raw_journal_directory(
            root,
            create=True,
        )
        file_descriptor = os.open(
            temporary_name,
            _WRITE_FLAGS,
            0o600,
            dir_fd=journal_descriptor,
        )
        temporary_created = True
        os.fchmod(file_descriptor, 0o600)
        metadata = os.fstat(file_descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != os.geteuid()
            or stat.S_IMODE(metadata.st_mode) != 0o600
            or not descriptor_acl_is_absent(file_descriptor)
        ):
            raise OSError("raw journal temporary file metadata is unsafe")
        _write_all(file_descriptor, content)
        os.fsync(file_descriptor)
        if not descriptor_acl_is_absent(file_descriptor):
            raise OSError("raw journal temporary file ACL changed")
        os.link(
            temporary_name,
            name,
            src_dir_fd=journal_descriptor,
            dst_dir_fd=journal_descriptor,
            follow_symlinks=False,
        )
        final_linked = True
        os.unlink(temporary_name, dir_fd=journal_descriptor)
        temporary_created = False
        os.fsync(journal_descriptor)
        return pending_raw_journal_directory(root) / name
    except FileExistsError as exc:
        raise _storage_error(
            "raw_chunk_persistence_ambiguous",
            "A pending raw chunk write already exists and was preserved.",
        ) from exc
    except RawCollectionStorageError:
        raise
    except (OSError, ValueError) as exc:
        if final_linked:
            raise _storage_error(
                "raw_chunk_persistence_ambiguous",
                "Raw chunk journal persistence is ambiguous and was preserved.",
            ) from exc
        raise _storage_error(
            "raw_chunk_storage_unavailable",
            "Raw chunk journal storage is temporarily unavailable.",
        ) from exc
    finally:
        if file_descriptor is not None:
            os.close(file_descriptor)
        if temporary_created and journal_descriptor is not None:
            try:
                os.unlink(temporary_name, dir_fd=journal_descriptor)
                os.fsync(journal_descriptor)
            except OSError:
                pass
        if journal_descriptor is not None:
            os.close(journal_descriptor)
        if root_descriptor is not None:
            os.close(root_descriptor)


def stage_raw_chunk_write(
    root: Path,
    *,
    binding: RawCollectionChunkBinding,
    consent_receipt_sha256: str,
    storage_name: str,
    key_id: str,
    nonce: bytes,
    envelope: bytes,
    persisted_at: datetime,
) -> PendingRawChunkWrite:
    expected_name = raw_chunk_storage_name(
        binding.collection_id,
        binding.object_id,
        binding.chunk_index,
    )
    if storage_name != expected_name:
        raise ValueError("raw chunk journal is not bound to its storage identity")
    if _SHA256_PATTERN.fullmatch(consent_receipt_sha256) is None:
        raise ValueError("raw chunk journal consent receipt is invalid")
    if _KEY_ID_PATTERN.fullmatch(key_id) is None or len(nonce) != 12:
        raise ValueError("raw chunk journal key metadata is invalid")
    if not envelope or len(envelope) > MAX_ENVELOPE_BYTES:
        raise ValueError("raw chunk journal envelope size is invalid")
    if persisted_at.tzinfo is None or persisted_at.utcoffset() is None:
        raise ValueError("raw chunk journal persistence time must be timezone-aware")
    persisted_at = persisted_at.astimezone(UTC)
    metadata = RawChunkCommitMetadata(
        privacy_subject_hmac=binding.privacy_subject_hmac,
        account_generation=binding.account_generation,
        collection_id=binding.collection_id,
        object_id=binding.object_id,
        chunk_index=binding.chunk_index,
        manifest_sha256=binding.manifest_sha256,
        consent_receipt_sha256=consent_receipt_sha256,
        purpose=binding.purpose,
        walk_id=binding.walk_id,
        content_type=binding.content_type,
        plaintext_size=binding.plaintext_length,
        plaintext_sha256=binding.plaintext_sha256,
        storage_name=storage_name,
        envelope_version=ENVELOPE_VERSION,
        algorithm="AES-256-GCM",
        aad_version=AAD_VERSION,
        key_id=key_id,
        nonce=nonce,
        envelope_sha256=hashlib.sha256(envelope).hexdigest(),
        envelope_size=len(envelope),
        persisted_at=persisted_at,
    )
    payload = json.dumps(
        {
            "aad_version": metadata.aad_version,
            "account_generation": metadata.account_generation,
            "algorithm": metadata.algorithm,
            "chunk_index": metadata.chunk_index,
            "collection_id": str(metadata.collection_id),
            "consent_receipt_sha256": metadata.consent_receipt_sha256,
            "content_type": metadata.content_type,
            "envelope_sha256": metadata.envelope_sha256,
            "envelope_size": metadata.envelope_size,
            "envelope_version": metadata.envelope_version,
            "key_id": metadata.key_id,
            "manifest_sha256": metadata.manifest_sha256,
            "nonce_b64url": _encode_base64url(metadata.nonce),
            "object_id": str(metadata.object_id),
            "plaintext_sha256": metadata.plaintext_sha256,
            "plaintext_size": metadata.plaintext_size,
            "persisted_at": metadata.persisted_at.strftime(
                "%Y-%m-%dT%H:%M:%S.%fZ"
            ),
            "privacy_subject_hmac": metadata.privacy_subject_hmac,
            "purpose": metadata.purpose,
            "schema_version": RAW_JOURNAL_SCHEMA,
            "storage_name": metadata.storage_name,
            "walk_id": str(metadata.walk_id),
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    if len(payload) > RAW_JOURNAL_MAX_BYTES:
        raise ValueError("raw chunk journal exceeds its fixed capacity bound")
    journal_path = _write_raw_journal(
        root,
        _journal_name(storage_name),
        payload,
    )
    return PendingRawChunkWrite(
        metadata=metadata,
        destination=root / storage_name,
        journal_path=journal_path,
    )


def complete_raw_chunk_write(pending: PendingRawChunkWrite) -> None:
    try:
        pending.journal_path.unlink()
    except FileNotFoundError:
        return
    journal_dir = pending.journal_path.parent
    journal_descriptor = os.open(journal_dir, _DIRECTORY_FLAGS)
    try:
        os.fsync(journal_descriptor)
    finally:
        os.close(journal_descriptor)
    try:
        journal_dir.rmdir()
    except FileNotFoundError:
        return
    except OSError as exc:
        if exc.errno not in {errno.EEXIST, errno.ENOTEMPTY}:
            raise
        return
    root_descriptor = os.open(pending.destination.parent, _DIRECTORY_FLAGS)
    try:
        os.fsync(root_descriptor)
    finally:
        os.close(root_descriptor)


def _unique_json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("raw journal contains a duplicate key")
        value[key] = item
    return value


def _read_raw_journal(root: Path, journal_name: str) -> PendingRawChunkWrite:
    root_descriptor: int | None = None
    journal_descriptor: int | None = None
    file_descriptor: int | None = None
    try:
        root_descriptor, journal_descriptor = _open_raw_journal_directory(
            root,
            create=False,
        )
        file_descriptor = os.open(
            journal_name,
            _READ_FLAGS,
            dir_fd=journal_descriptor,
        )
        metadata = os.fstat(file_descriptor)
        path_metadata = os.stat(
            journal_name,
            dir_fd=journal_descriptor,
            follow_symlinks=False,
        )
        if (
            not stat.S_ISREG(metadata.st_mode)
            or (metadata.st_dev, metadata.st_ino)
            != (path_metadata.st_dev, path_metadata.st_ino)
            or metadata.st_uid != os.geteuid()
            or stat.S_IMODE(metadata.st_mode) != 0o600
            or not descriptor_acl_is_absent(file_descriptor)
            or not 0 < metadata.st_size <= RAW_JOURNAL_MAX_BYTES
        ):
            raise ValueError("raw journal file metadata is invalid")
        content = b""
        while len(content) <= metadata.st_size:
            chunk = os.read(file_descriptor, metadata.st_size + 1 - len(content))
            if not chunk:
                break
            content += chunk
        if len(content) != metadata.st_size:
            raise ValueError("raw journal file length changed")
        payload = json.loads(
            content.decode("utf-8"),
            object_pairs_hook=_unique_json_object,
        )
        if not isinstance(payload, dict) or content != json.dumps(
            payload,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8"):
            raise ValueError("raw journal is not canonical JSON")
        expected_keys = {
            "aad_version",
            "account_generation",
            "algorithm",
            "chunk_index",
            "collection_id",
            "consent_receipt_sha256",
            "content_type",
            "envelope_sha256",
            "envelope_size",
            "envelope_version",
            "key_id",
            "manifest_sha256",
            "nonce_b64url",
            "object_id",
            "plaintext_sha256",
            "plaintext_size",
            "persisted_at",
            "privacy_subject_hmac",
            "purpose",
            "schema_version",
            "storage_name",
            "walk_id",
        }
        if payload.get("schema_version") != RAW_JOURNAL_SCHEMA or set(payload) != expected_keys:
            raise ValueError("raw journal schema or shape changed")
        collection_id = uuid.UUID(str(payload["collection_id"]))
        object_id = uuid.UUID(str(payload["object_id"]))
        walk_id = uuid.UUID(str(payload["walk_id"]))
        if (
            str(collection_id) != payload["collection_id"]
            or str(object_id) != payload["object_id"]
            or str(walk_id) != payload["walk_id"]
        ):
            raise ValueError("raw journal UUID is not canonical")
        binding = RawCollectionChunkBinding(
            privacy_subject_hmac=payload["privacy_subject_hmac"],
            account_generation=payload["account_generation"],
            collection_id=collection_id,
            object_id=object_id,
            chunk_index=payload["chunk_index"],
            manifest_sha256=payload["manifest_sha256"],
            purpose=payload["purpose"],
            walk_id=walk_id,
            content_type=payload["content_type"],
            plaintext_length=payload["plaintext_size"],
            plaintext_sha256=payload["plaintext_sha256"],
        )
        consent_receipt_sha256 = payload["consent_receipt_sha256"]
        storage_name = payload["storage_name"]
        key_id = payload["key_id"]
        envelope_sha256 = payload["envelope_sha256"]
        envelope_size = payload["envelope_size"]
        persisted_at_text = payload["persisted_at"]
        if (
            not isinstance(consent_receipt_sha256, str)
            or _SHA256_PATTERN.fullmatch(consent_receipt_sha256) is None
            or not isinstance(storage_name, str)
            or storage_name
            != raw_chunk_storage_name(collection_id, object_id, binding.chunk_index)
            or journal_name != _journal_name(storage_name)
            or payload["envelope_version"] != ENVELOPE_VERSION
            or payload["algorithm"] != "AES-256-GCM"
            or payload["aad_version"] != AAD_VERSION
            or not isinstance(key_id, str)
            or _KEY_ID_PATTERN.fullmatch(key_id) is None
            or not isinstance(envelope_sha256, str)
            or _SHA256_PATTERN.fullmatch(envelope_sha256) is None
            or type(envelope_size) is not int
            or not 0 < envelope_size <= MAX_ENVELOPE_BYTES
            or not isinstance(persisted_at_text, str)
            or _JOURNAL_TIME_PATTERN.fullmatch(persisted_at_text) is None
        ):
            raise ValueError("raw journal metadata is invalid")
        persisted_at = datetime.strptime(
            persisted_at_text,
            "%Y-%m-%dT%H:%M:%S.%fZ",
        ).replace(tzinfo=UTC)
        commit_metadata = RawChunkCommitMetadata(
            privacy_subject_hmac=binding.privacy_subject_hmac,
            account_generation=binding.account_generation,
            collection_id=binding.collection_id,
            object_id=binding.object_id,
            chunk_index=binding.chunk_index,
            manifest_sha256=binding.manifest_sha256,
            consent_receipt_sha256=consent_receipt_sha256,
            purpose=binding.purpose,
            walk_id=binding.walk_id,
            content_type=binding.content_type,
            plaintext_size=binding.plaintext_length,
            plaintext_sha256=binding.plaintext_sha256,
            storage_name=storage_name,
            envelope_version=payload["envelope_version"],
            algorithm=payload["algorithm"],
            aad_version=payload["aad_version"],
            key_id=key_id,
            nonce=_decode_nonce(payload["nonce_b64url"]),
            envelope_sha256=envelope_sha256,
            envelope_size=envelope_size,
            persisted_at=persisted_at,
        )
        return PendingRawChunkWrite(
            metadata=commit_metadata,
            destination=root / storage_name,
            journal_path=pending_raw_journal_directory(root) / journal_name,
        )
    except FileNotFoundError:
        raise
    except (
        OSError,
        UnicodeError,
        ValueError,
        TypeError,
        json.JSONDecodeError,
    ) as exc:
        raise RuntimeError("invalid raw chunk write journal") from exc
    finally:
        if file_descriptor is not None:
            os.close(file_descriptor)
        if journal_descriptor is not None:
            os.close(journal_descriptor)
        if root_descriptor is not None:
            os.close(root_descriptor)


def _journal_temporary_target(name: str) -> str | None:
    if not name.startswith(".") or not name.endswith(".tmp"):
        return None
    body = name[1:-4]
    journal_name, separator, token = body.rpartition(".")
    if (
        separator != "."
        or len(token) != 32
        or any(character not in "0123456789abcdef" for character in token)
        or not journal_name.endswith(".json")
        or _STORAGE_NAME_PATTERN.fullmatch(journal_name[:-5]) is None
    ):
        return None
    return journal_name


def _remove_raw_journal_temporaries(
    journal_descriptor: int,
    names: list[str],
) -> list[str]:
    retained: list[str] = []
    changed = False
    for name in names:
        journal_name = _journal_temporary_target(name)
        if journal_name is None:
            retained.append(name)
            continue
        file_descriptor: int | None = None
        try:
            anchored = os.stat(
                name,
                dir_fd=journal_descriptor,
                follow_symlinks=False,
            )
            file_descriptor = os.open(
                name,
                _READ_FLAGS,
                dir_fd=journal_descriptor,
            )
            opened = os.fstat(file_descriptor)
            current = os.stat(
                name,
                dir_fd=journal_descriptor,
                follow_symlinks=False,
            )
            if (
                not stat.S_ISREG(opened.st_mode)
                or opened.st_nlink not in {1, 2}
                or opened.st_uid != os.geteuid()
                or stat.S_IMODE(opened.st_mode) != 0o600
                or (opened.st_dev, opened.st_ino)
                != (anchored.st_dev, anchored.st_ino)
                or (opened.st_dev, opened.st_ino)
                != (current.st_dev, current.st_ino)
                or not descriptor_acl_is_absent(file_descriptor)
            ):
                raise RuntimeError("invalid raw chunk write journal")
            if opened.st_nlink == 2:
                final = os.stat(
                    journal_name,
                    dir_fd=journal_descriptor,
                    follow_symlinks=False,
                )
                if (
                    not stat.S_ISREG(final.st_mode)
                    or (final.st_dev, final.st_ino)
                    != (opened.st_dev, opened.st_ino)
                ):
                    raise RuntimeError("invalid raw chunk write journal")
            os.unlink(name, dir_fd=journal_descriptor)
        except OSError as exc:
            raise RuntimeError("invalid raw chunk write journal") from exc
        finally:
            if file_descriptor is not None:
                os.close(file_descriptor)
        changed = True
    if changed:
        os.fsync(journal_descriptor)
    return retained


def _remove_interrupted_raw_chunk_files(
    pending: PendingRawChunkWrite,
    *,
    remove_final: bool,
) -> None:
    root = pending.destination.parent
    root_descriptor = os.open(root, _DIRECTORY_FLAGS)
    changed = False
    try:
        validate_upload_directory_descriptor(root, root_descriptor)
        if remove_final:
            try:
                os.unlink(pending.metadata.storage_name, dir_fd=root_descriptor)
                changed = True
            except FileNotFoundError:
                pass
        prefix = f".{pending.metadata.storage_name}."
        for name in os.listdir(root_descriptor):
            if not name.startswith(prefix) or not name.endswith(".tmp"):
                continue
            token = name[len(prefix) : -4]
            if len(token) != 32 or any(
                character not in "0123456789abcdef" for character in token
            ):
                continue
            os.unlink(name, dir_fd=root_descriptor)
            changed = True
        if changed:
            os.fsync(root_descriptor)
    finally:
        os.close(root_descriptor)


def reconcile_pending_raw_chunk_writes(
    root: Path,
    commit_state: Callable[[uuid.UUID, uuid.UUID, int], RawChunkCommitState],
) -> RawStorageReconciliation:
    root_descriptor = os.open(root, _DIRECTORY_FLAGS)
    try:
        validate_upload_directory_descriptor(root, root_descriptor)
    finally:
        os.close(root_descriptor)
    try:
        root_descriptor, journal_descriptor = _open_raw_journal_directory(
            root,
            create=False,
        )
    except FileNotFoundError:
        return RawStorageReconciliation(0, 0)
    try:
        journal_names = _remove_raw_journal_temporaries(
            journal_descriptor,
            sorted(os.listdir(journal_descriptor)),
        )
    finally:
        os.close(journal_descriptor)
        os.close(root_descriptor)
    if len(journal_names) > MAX_PENDING_RAW_CHUNK_WRITES:
        raise RuntimeError("too many pending raw chunk write journals")

    retained_committed = 0
    removed_orphans = 0
    for journal_name in journal_names:
        try:
            pending = _read_raw_journal(root, journal_name)
        except FileNotFoundError:
            # A writer may remove its journal immediately after committing and
            # releasing the shared transaction lock. The full inventory check
            # that follows reconciliation still validates its DB/file pair.
            continue
        state = commit_state(
            pending.metadata.collection_id,
            pending.metadata.object_id,
            pending.metadata.chunk_index,
        )
        if not state.chunk_exists or state.metadata is None:
            if not state.chunk_exists and state.metadata is not None:
                raise RuntimeError("raw chunk commit state is inconsistent")
            _remove_interrupted_raw_chunk_files(pending, remove_final=True)
            removed_orphans += 1
            complete_raw_chunk_write(pending)
            continue
        if state.metadata != pending.metadata:
            raise RuntimeError(
                "committed raw chunk metadata does not match its journal"
            )
        try:
            read_raw_chunk_envelope(
                root,
                pending.metadata.storage_name,
                expected_size=pending.metadata.envelope_size,
                expected_sha256=pending.metadata.envelope_sha256,
            )
        except RawCollectionStorageError as exc:
            raise RuntimeError(
                "committed raw chunk file does not match its journal"
            ) from exc
        _remove_interrupted_raw_chunk_files(pending, remove_final=False)
        retained_committed += 1
        complete_raw_chunk_write(pending)

    return RawStorageReconciliation(
        retained_committed=retained_committed,
        removed_orphans=removed_orphans,
    )


def _write_all(descriptor: int, content: bytes) -> None:
    remaining = memoryview(content)
    while remaining:
        written = os.write(descriptor, remaining)
        if written <= 0:
            raise OSError("raw chunk write made no progress")
        remaining = remaining[written:]


def persist_raw_chunk_envelope(
    root: Path,
    storage_name: str,
    envelope: bytes,
) -> PersistedRawChunkFile:
    """Publish one final name without replacement, then fsync its directory."""

    if _STORAGE_NAME_PATTERN.fullmatch(storage_name) is None:
        raise ValueError("raw chunk storage name is invalid")
    if not isinstance(envelope, bytes) or not envelope:
        raise ValueError("raw chunk envelope must be non-empty bytes")
    directory_descriptor: int | None = None
    file_descriptor: int | None = None
    temporary_name = f".{storage_name}.{uuid.uuid4().hex}.tmp"
    temporary_created = False
    final_linked = False
    try:
        directory_descriptor = os.open(root, _DIRECTORY_FLAGS)
        directory_metadata = validate_upload_directory_descriptor(
            root,
            directory_descriptor,
        )
        file_mode = expected_upload_file_mode(directory_metadata)
        file_descriptor = os.open(
            temporary_name,
            _WRITE_FLAGS,
            file_mode,
            dir_fd=directory_descriptor,
        )
        temporary_created = True
        os.fchmod(file_descriptor, file_mode)
        if (
            not upload_file_metadata_is_safe(
                os.fstat(file_descriptor),
                directory_metadata,
            )
            or not descriptor_acl_is_absent(file_descriptor)
        ):
            raise OSError("raw chunk temporary file metadata is unsafe")
        _write_all(file_descriptor, envelope)
        os.fsync(file_descriptor)
        validate_upload_directory_descriptor(
            root,
            directory_descriptor,
            expected_metadata=directory_metadata,
        )
        if not descriptor_acl_is_absent(file_descriptor):
            raise OSError("raw chunk temporary file ACL changed")
        try:
            os.link(
                temporary_name,
                storage_name,
                src_dir_fd=directory_descriptor,
                dst_dir_fd=directory_descriptor,
                follow_symlinks=False,
            )
        except FileExistsError as exc:
            raise _storage_error(
                "raw_chunk_persistence_ambiguous",
                "Raw chunk persistence is ambiguous; the existing final object was not changed.",
            ) from exc
        final_linked = True
        os.unlink(temporary_name, dir_fd=directory_descriptor)
        temporary_created = False
        os.fsync(directory_descriptor)
        validate_upload_directory_descriptor(
            root,
            directory_descriptor,
            expected_metadata=directory_metadata,
        )
        final_metadata = os.stat(
            storage_name,
            dir_fd=directory_descriptor,
            follow_symlinks=False,
        )
        if not upload_file_metadata_is_safe(final_metadata, directory_metadata):
            raise OSError("raw chunk final file metadata is unsafe")
        return PersistedRawChunkFile(
            storage_name=storage_name,
            envelope_sha256=hashlib.sha256(envelope).hexdigest(),
            envelope_size=len(envelope),
        )
    except RawCollectionStorageError:
        raise
    except (OSError, ValueError) as exc:
        if final_linked:
            raise _storage_error(
                "raw_chunk_persistence_ambiguous",
                "Raw chunk persistence is ambiguous; the final object was preserved.",
            ) from exc
        raise _storage_error(
            "raw_chunk_storage_unavailable",
            "Raw chunk encrypted storage is temporarily unavailable.",
        ) from exc
    finally:
        if file_descriptor is not None:
            os.close(file_descriptor)
        if temporary_created and directory_descriptor is not None:
            try:
                os.unlink(temporary_name, dir_fd=directory_descriptor)
                os.fsync(directory_descriptor)
            except OSError:
                pass
        if directory_descriptor is not None:
            os.close(directory_descriptor)


def read_raw_chunk_envelope(
    root: Path,
    storage_name: str,
    *,
    expected_size: int,
    expected_sha256: str,
) -> bytes:
    """Read through pinned descriptors and reject any DB/file divergence."""

    if _STORAGE_NAME_PATTERN.fullmatch(storage_name) is None:
        raise _storage_error(
            "raw_chunk_persistence_ambiguous",
            "Raw chunk persistence metadata does not match encrypted storage.",
        )
    directory_descriptor: int | None = None
    file_descriptor: int | None = None
    try:
        directory_descriptor = os.open(root, _DIRECTORY_FLAGS)
        directory_metadata = validate_upload_directory_descriptor(
            root,
            directory_descriptor,
        )
        file_descriptor = os.open(
            storage_name,
            _READ_FLAGS,
            dir_fd=directory_descriptor,
        )
        file_metadata = os.fstat(file_descriptor)
        path_metadata = os.stat(
            storage_name,
            dir_fd=directory_descriptor,
            follow_symlinks=False,
        )
        if (
            not upload_file_metadata_is_safe(file_metadata, directory_metadata)
            or not descriptor_acl_is_absent(file_descriptor)
            or (file_metadata.st_dev, file_metadata.st_ino)
            != (path_metadata.st_dev, path_metadata.st_ino)
            or file_metadata.st_size != expected_size
        ):
            raise OSError("raw chunk file metadata does not match")
        chunks: list[bytes] = []
        remaining = expected_size
        while remaining:
            part = os.read(file_descriptor, min(1024 * 1024, remaining))
            if not part:
                raise OSError("raw chunk file is truncated")
            chunks.append(part)
            remaining -= len(part)
        envelope = b"".join(chunks)
        if os.read(file_descriptor, 1):
            raise OSError("raw chunk file grew during read")
        validate_upload_directory_descriptor(
            root,
            directory_descriptor,
            expected_metadata=directory_metadata,
        )
        if (
            not descriptor_acl_is_absent(file_descriptor)
            or not hmac.compare_digest(
                hashlib.sha256(envelope).hexdigest(),
                expected_sha256,
            )
        ):
            raise OSError("raw chunk encrypted digest does not match")
        return envelope
    except RawCollectionStorageError:
        raise
    except (OSError, ValueError) as exc:
        raise _storage_error(
            "raw_chunk_persistence_ambiguous",
            "Raw chunk persistence metadata does not match encrypted storage.",
        ) from exc
    finally:
        if file_descriptor is not None:
            os.close(file_descriptor)
        if directory_descriptor is not None:
            os.close(directory_descriptor)


def _canonical_time(value: datetime) -> str:
    return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _canonical_receipt_time(value: datetime) -> str:
    if value.microsecond != 0:
        raise ValueError("raw receipt timestamps must use whole seconds")
    return _canonical_time(value)


def raw_chunk_commit_state(
    collection: RawCollection,
    item: RawCollectionObject,
    chunk: RawCollectionChunk,
) -> RawChunkCommitState:
    if (
        item.collection_id != collection.collection_id
        or chunk.collection_id != collection.collection_id
        or chunk.object_id != item.object_id
        or item.manifest_sha256 != collection.manifest_sha256
        or chunk.manifest_sha256 != collection.manifest_sha256
        or item.consent_receipt_sha256 != collection.consent_receipt_sha256
        or chunk.consent_receipt_sha256 != collection.consent_receipt_sha256
        or (
            collection.lifecycle_version == 1
            and collection.retention_class != RAW_LEGACY_RETENTION_CLASS
        )
        or (
            collection.lifecycle_version == 2
            and collection.retention_class != RAW_QUARANTINE_CLASS
        )
    ):
        raise RuntimeError("raw chunk database inventory is inconsistent")
    persistence_values = (
        chunk.storage_name,
        chunk.envelope_version,
        chunk.algorithm,
        chunk.aad_version,
        chunk.key_id,
        chunk.nonce,
        chunk.envelope_sha256,
        chunk.envelope_size,
        chunk.persisted_at,
    )
    if all(value is None for value in persistence_values):
        if collection.state not in {"MANIFEST_ACCEPTED", "RECEIVING"}:
            raise RuntimeError("raw chunk database persistence state is incomplete")
        return RawChunkCommitState(chunk_exists=True, metadata=None)
    if any(value is None for value in persistence_values) or collection.state not in {
        "MANIFEST_ACCEPTED",
        "RECEIVING",
        "READY_TO_COMMIT",
        "COMMITTED",
        "QUARANTINED",
    }:
        raise RuntimeError("raw chunk database persistence state is incomplete")
    assert chunk.storage_name is not None
    assert chunk.envelope_version is not None
    assert chunk.algorithm is not None
    assert chunk.aad_version is not None
    assert chunk.key_id is not None
    assert chunk.nonce is not None
    assert chunk.envelope_sha256 is not None
    assert chunk.envelope_size is not None
    assert chunk.persisted_at is not None
    binding = RawCollectionChunkBinding(
        privacy_subject_hmac=collection.privacy_subject_hmac,
        account_generation=collection.account_generation,
        collection_id=collection.collection_id,
        object_id=item.object_id,
        chunk_index=chunk.chunk_index,
        manifest_sha256=collection.manifest_sha256,
        purpose=collection.purpose,
        walk_id=collection.walk_id,
        content_type=item.content_type,
        plaintext_length=chunk.declared_size_bytes,
        plaintext_sha256=chunk.declared_sha256,
    )
    expected_name = raw_chunk_storage_name(
        binding.collection_id,
        binding.object_id,
        binding.chunk_index,
    )
    if (
        chunk.storage_name != expected_name
        or chunk.envelope_version != ENVELOPE_VERSION
        or chunk.algorithm != "AES-256-GCM"
        or chunk.aad_version != AAD_VERSION
        or _KEY_ID_PATTERN.fullmatch(chunk.key_id) is None
        or _SHA256_PATTERN.fullmatch(chunk.envelope_sha256) is None
        or not 0 < chunk.envelope_size <= MAX_ENVELOPE_BYTES
        or len(chunk.nonce) != 12
        or chunk.persisted_at.tzinfo is None
        or chunk.persisted_at.utcoffset() is None
    ):
        raise RuntimeError("raw chunk database persistence metadata is invalid")
    return RawChunkCommitState(
        chunk_exists=True,
        metadata=RawChunkCommitMetadata(
            privacy_subject_hmac=binding.privacy_subject_hmac,
            account_generation=binding.account_generation,
            collection_id=binding.collection_id,
            object_id=binding.object_id,
            chunk_index=binding.chunk_index,
            manifest_sha256=binding.manifest_sha256,
            consent_receipt_sha256=collection.consent_receipt_sha256,
            purpose=binding.purpose,
            walk_id=binding.walk_id,
            content_type=binding.content_type,
            plaintext_size=binding.plaintext_length,
            plaintext_sha256=binding.plaintext_sha256,
            storage_name=chunk.storage_name,
            envelope_version=chunk.envelope_version,
            algorithm=chunk.algorithm,
            aad_version=chunk.aad_version,
            key_id=chunk.key_id,
            nonce=chunk.nonce,
            envelope_sha256=chunk.envelope_sha256,
            envelope_size=chunk.envelope_size,
            persisted_at=chunk.persisted_at.astimezone(UTC),
        ),
    )


def _validate_empty_raw_journal_directory(
    root_descriptor: int,
    name: str,
) -> None:
    journal_descriptor = os.open(name, _DIRECTORY_FLAGS, dir_fd=root_descriptor)
    try:
        metadata = os.fstat(journal_descriptor)
        path_metadata = os.stat(name, dir_fd=root_descriptor, follow_symlinks=False)
        if (
            not stat.S_ISDIR(metadata.st_mode)
            or (metadata.st_dev, metadata.st_ino)
            != (path_metadata.st_dev, path_metadata.st_ino)
            or metadata.st_uid != os.geteuid()
            or stat.S_IMODE(metadata.st_mode) != PRIVATE_UPLOAD_DIRECTORY_MODE
            or not descriptor_acl_is_absent(journal_descriptor)
            or os.listdir(journal_descriptor)
        ):
            raise RuntimeError(
                "raw chunk write journal is not empty and private after reconciliation"
            )
    finally:
        os.close(journal_descriptor)


def validate_raw_storage_database_inventory(
    db: Session,
    root: Path,
    *,
    key_manager: ReportImageKeyManager,
) -> None:
    """Fail startup unless every persisted raw row and encrypted file agrees."""

    lock_raw_storage_reconciliation_transaction(db)
    collections = list(db.scalars(select(RawCollection)).all())
    objects = list(db.scalars(select(RawCollectionObject)).all())
    chunks = list(db.scalars(select(RawCollectionChunk)).all())
    collections_by_id = {collection.collection_id: collection for collection in collections}
    objects_by_collection: dict[uuid.UUID, list[RawCollectionObject]] = {}
    chunks_by_object: dict[tuple[uuid.UUID, uuid.UUID], list[RawCollectionChunk]] = {}
    for item in objects:
        if item.collection_id not in collections_by_id:
            raise RuntimeError("raw storage inventory contains an orphan object row")
        objects_by_collection.setdefault(item.collection_id, []).append(item)
    for chunk in chunks:
        key = (chunk.collection_id, chunk.object_id)
        chunks_by_object.setdefault(key, []).append(chunk)

    expected: dict[str, tuple[RawCollection, RawCollectionObject, RawCollectionChunk]] = {}
    seen_chunk_keys: set[tuple[uuid.UUID, uuid.UUID]] = set()
    for collection in collections:
        collection_objects = sorted(
            objects_by_collection.get(collection.collection_id, []),
            key=lambda item: str(item.object_id),
        )
        if len(collection_objects) != collection.object_count:
            raise RuntimeError("raw storage database inventory is inconsistent")
        observed_chunks = 0
        observed_bytes = 0
        persisted_chunks = 0
        for item in collection_objects:
            key = (collection.collection_id, item.object_id)
            collection_chunks = sorted(
                chunks_by_object.get(key, []),
                key=lambda chunk: chunk.chunk_index,
            )
            if (
                len(collection_chunks) != item.chunk_count
                or tuple(chunk.chunk_index for chunk in collection_chunks)
                != tuple(range(item.chunk_count))
                or sum(chunk.declared_size_bytes for chunk in collection_chunks)
                != item.size_bytes
            ):
                raise RuntimeError("raw storage database inventory is inconsistent")
            seen_chunk_keys.add(key)
            observed_chunks += len(collection_chunks)
            observed_bytes += item.size_bytes
            for chunk in collection_chunks:
                state = raw_chunk_commit_state(collection, item, chunk)
                if state.metadata is None:
                    continue
                persisted_chunks += 1
                if state.metadata.storage_name in expected:
                    raise RuntimeError(
                        "raw storage inventory reuses an encrypted storage name"
                    )
                expected[state.metadata.storage_name] = (collection, item, chunk)
        if (
            observed_chunks != collection.chunk_count
            or observed_bytes != collection.total_bytes
            or (
                collection.state == "MANIFEST_ACCEPTED"
                and persisted_chunks == collection.chunk_count
            )
            or (
                collection.state == "RECEIVING"
                and not 0 < persisted_chunks < collection.chunk_count
            )
            or (
                collection.state
                in {"READY_TO_COMMIT", "COMMITTED", "QUARANTINED"}
                and persisted_chunks != collection.chunk_count
            )
        ):
            raise RuntimeError("raw storage database inventory is inconsistent")
    if set(chunks_by_object) != seen_chunk_keys:
        raise RuntimeError("raw storage inventory contains an orphan chunk row")

    root_descriptor = os.open(root, _DIRECTORY_FLAGS)
    actual: set[str] = set()
    try:
        root_metadata = validate_upload_directory_descriptor(root, root_descriptor)
        for name in sorted(os.listdir(root_descriptor)):
            if name == RAW_JOURNAL_DIRECTORY_NAME:
                _validate_empty_raw_journal_directory(root_descriptor, name)
                continue
            if _STORAGE_NAME_PATTERN.fullmatch(name) is None:
                raise RuntimeError("raw storage root contains an unknown entry")
            file_descriptor = os.open(name, _READ_FLAGS, dir_fd=root_descriptor)
            try:
                metadata = os.fstat(file_descriptor)
                path_metadata = os.stat(
                    name,
                    dir_fd=root_descriptor,
                    follow_symlinks=False,
                )
                if (
                    not upload_file_metadata_is_safe(metadata, root_metadata)
                    or (metadata.st_dev, metadata.st_ino)
                    != (path_metadata.st_dev, path_metadata.st_ino)
                    or not descriptor_acl_is_absent(file_descriptor)
                ):
                    raise RuntimeError("raw storage root contains an unsafe object")
            finally:
                os.close(file_descriptor)
            actual.add(name)
    finally:
        os.close(root_descriptor)
    if actual != set(expected):
        raise RuntimeError("raw database and encrypted object inventory differ")

    validator = RawCollectionStorage(
        raw_object_dir=root,
        privacy_hmac_secret="inventory-validation-only",
        key_manager=key_manager,
    )
    for storage_name in sorted(expected):
        validator._verify_persisted_chunk(*expected[storage_name])


class RawCollectionStorage:
    """Durable encrypted raw inventory handler and immutable commit receipt."""

    def __init__(
        self,
        *,
        raw_object_dir: Path | None,
        privacy_hmac_secret: str,
        key_manager: ReportImageKeyManager,
        capacity_state: CapacityState | None = None,
    ) -> None:
        self._root = raw_object_dir
        self._privacy_hmac_secret = privacy_hmac_secret
        self._key_manager = key_manager
        self._capacity_state = capacity_state

    def _assert_new_collection_capacity(
        self,
        db: Session,
        manifest: RawCollectionManifestV1,
    ) -> None:
        if self._capacity_state is None:
            raise _storage_error(
                "raw_capacity_state_unavailable",
                "Raw collection capacity state is unavailable.",
            )
        try:
            snapshot = self._capacity_state.current()
        except CapacityStateUnavailable as exc:
            raise _storage_error(
                "raw_capacity_state_unavailable",
                "Raw collection capacity state is unavailable.",
            ) from exc
        if snapshot.level in _RAW_CAPACITY_HOLD_LEVELS:
            raise _storage_error(
                "raw_capacity_hold",
                "New raw collection sessions are temporarily held.",
            )
        if self._root is None:
            raise _storage_error(
                "raw_chunk_storage_unavailable",
                "Raw chunk encrypted storage is not configured.",
            )
        try:
            available_bytes, block_size, available_inodes = (
                _raw_filesystem_capacity(self._root)
            )
            outstanding = db.execute(
                select(
                    func.coalesce(func.sum(RawCollection.total_bytes), 0),
                    func.coalesce(func.sum(RawCollection.chunk_count), 0),
                ).where(
                    RawCollection.state.in_(("MANIFEST_ACCEPTED", "RECEIVING"))
                )
            ).one()
            outstanding_plaintext_bytes = int(outstanding[0] or 0)
            outstanding_chunks = int(outstanding[1] or 0)
            outstanding_bytes = _raw_capacity_reservation_bytes(
                outstanding_plaintext_bytes,
                outstanding_chunks,
                block_size,
            )
            requested_bytes = _raw_capacity_reservation_bytes(
                manifest.total_bytes,
                manifest.chunk_count,
                block_size,
            )
            required_inodes = (
                outstanding_chunks + manifest.chunk_count
            ) * RAW_CAPACITY_FILE_INODES_PER_CHUNK + 1
        except (OSError, TypeError, ValueError) as exc:
            raise _storage_error(
                "raw_capacity_reservation_unavailable",
                "Raw collection capacity reservation is unavailable.",
            ) from exc
        if (
            outstanding_bytes < 0
            or requested_bytes <= 0
            or outstanding_bytes + requested_bytes > available_bytes
            or required_inodes > available_inodes
        ):
            raise _storage_error(
                "raw_capacity_reservation_unavailable",
                "Raw collection capacity reservation is unavailable.",
            )

    @staticmethod
    def _receipt_payload_v1(
        collection: RawCollection,
        inventory: list[tuple[RawCollectionObject, list[RawCollectionChunk]]],
        *,
        committed_at: datetime,
        retention_expires_at: datetime,
    ) -> dict[str, object]:
        return {
            "schema_version": "walksafe.raw-collection-receipt.v1",
            "collection_id": str(collection.collection_id),
            "manifest_sha256": collection.manifest_sha256,
            "purpose": collection.purpose,
            "persistence_marker": "DATABASE_AND_ENCRYPTED_CHUNK_STORE",
            "object_count": collection.object_count,
            "chunk_count": collection.chunk_count,
            "total_bytes": collection.total_bytes,
            "objects": [
                {
                    "object_id": str(item.object_id),
                    "kind": item.kind,
                    "size_bytes": item.size_bytes,
                    "sha256": item.sha256,
                    "chunk_count": item.chunk_count,
                }
                for item, _chunks in inventory
            ],
            "retention_class": collection.retention_class,
            "committed_at": _canonical_receipt_time(committed_at),
            "retention_expires_at": _canonical_receipt_time(retention_expires_at),
        }

    @staticmethod
    def _receipt_payload_v2(
        collection: RawCollection,
        inventory: list[tuple[RawCollectionObject, list[RawCollectionChunk]]],
        *,
        committed_at: datetime,
        quarantine_expires_at: datetime,
    ) -> dict[str, object]:
        return {
            "schema_version": "walksafe.raw-collection-receipt.v2",
            "collection_id": str(collection.collection_id),
            "manifest_sha256": collection.manifest_sha256,
            "purpose": collection.purpose,
            "persistence_marker": "DATABASE_AND_ENCRYPTED_CHUNK_STORE",
            "object_count": collection.object_count,
            "chunk_count": collection.chunk_count,
            "total_bytes": collection.total_bytes,
            "objects": [
                {
                    "object_id": str(item.object_id),
                    "kind": item.kind,
                    "size_bytes": item.size_bytes,
                    "sha256": item.sha256,
                    "chunk_count": item.chunk_count,
                }
                for item, _chunks in inventory
            ],
            "retention_class": collection.retention_class,
            "committed_at": _canonical_receipt_time(committed_at),
            "quarantine_expires_at": _canonical_receipt_time(
                quarantine_expires_at
            ),
        }

    @classmethod
    def _receipt(
        cls,
        collection: RawCollection,
        inventory: list[tuple[RawCollectionObject, list[RawCollectionChunk]]],
    ) -> RawCollectionReceiptV1 | RawCollectionReceiptV2 | None:
        values = (
            collection.committed_at,
            collection.retention_expires_at,
            collection.quarantine_expires_at,
            collection.receipt_sha256,
        )
        if collection.state not in {"COMMITTED", "QUARANTINED"} and all(
            value is None for value in values
        ):
            return None
        if collection.state == "COMMITTED" and (
            collection.lifecycle_version != 1
            or collection.retention_expires_at is None
            or collection.quarantine_expires_at is not None
        ):
            raise _storage_error(
                "raw_receipt_persistence_ambiguous",
                "Raw collection receipt metadata is incomplete.",
            )
        if collection.state == "QUARANTINED" and (
            collection.lifecycle_version != 2
            or collection.quarantine_expires_at is None
            or collection.retention_expires_at is not None
        ):
            raise _storage_error(
                "raw_receipt_persistence_ambiguous",
                "Raw collection receipt metadata is incomplete.",
            )
        if collection.state not in {"COMMITTED", "QUARANTINED"}:
            raise _storage_error(
                "raw_receipt_persistence_ambiguous",
                "Raw collection receipt metadata is incomplete.",
            )
        assert collection.committed_at is not None
        assert collection.receipt_sha256 is not None
        try:
            if collection.lifecycle_version == 1:
                assert collection.retention_expires_at is not None
                return RawCollectionReceiptV1.model_validate({
                    **cls._receipt_payload_v1(
                        collection,
                        inventory,
                        committed_at=collection.committed_at,
                        retention_expires_at=collection.retention_expires_at,
                    ),
                    "receipt_sha256": collection.receipt_sha256,
                })
            assert collection.quarantine_expires_at is not None
            return RawCollectionReceiptV2.model_validate({
                **cls._receipt_payload_v2(
                    collection,
                    inventory,
                    committed_at=collection.committed_at,
                    quarantine_expires_at=collection.quarantine_expires_at,
                ),
                "receipt_sha256": collection.receipt_sha256,
            })
        except (TypeError, ValueError, OverflowError) as exc:
            raise _storage_error(
                "raw_receipt_persistence_ambiguous",
                "Raw collection receipt metadata does not match its inventory.",
            ) from exc

    @classmethod
    def _status(
        cls,
        collection: RawCollection,
        inventory: list[tuple[RawCollectionObject, list[RawCollectionChunk]]],
    ) -> RawCollectionStatusV1:
        received_chunk_count = sum(
            chunk.storage_name is not None
            for _item, chunks in inventory
            for chunk in chunks
        )
        received_bytes = sum(
            chunk.declared_size_bytes
            for _item, chunks in inventory
            for chunk in chunks
            if chunk.storage_name is not None
        )
        # The existing DB guard moves directly from MANIFEST_ACCEPTED to ready;
        # expose the contract's RECEIVING state while only part is persisted.
        projected_state = (
            "RECEIVING"
            if collection.state == "MANIFEST_ACCEPTED" and received_chunk_count > 0
            else collection.state
        )
        objects: list[dict[str, object]] = []
        for item, chunks in inventory:
            item_received = [chunk.storage_name is not None for chunk in chunks]
            missing_ranges: list[dict[str, int]] = []
            range_start: int | None = None
            for index, received in enumerate(item_received):
                if not received and range_start is None:
                    range_start = index
                if received and range_start is not None:
                    missing_ranges.append({"start": range_start, "end": index - 1})
                    range_start = None
            if range_start is not None:
                missing_ranges.append(
                    {"start": range_start, "end": len(item_received) - 1}
                )
            objects.append(
                {
                    "object_id": str(item.object_id),
                    "kind": item.kind,
                    "sha256": item.sha256,
                    "chunk_count": item.chunk_count,
                    "received_chunk_count": sum(item_received),
                    "size_bytes": item.size_bytes,
                    "received_bytes": sum(
                        chunk.declared_size_bytes
                        for chunk in chunks
                        if chunk.storage_name is not None
                    ),
                    "missing_ranges": missing_ranges,
                }
            )
        return RawCollectionStatusV1.model_validate(
            {
                "schema_version": "walksafe.raw-collection-status.v1",
                "collection_id": str(collection.collection_id),
                "manifest_sha256": collection.manifest_sha256,
                "purpose": collection.purpose,
                "state": projected_state,
                "object_count": collection.object_count,
                "chunk_count": collection.chunk_count,
                "total_bytes": collection.total_bytes,
                "received_chunk_count": received_chunk_count,
                "received_bytes": received_bytes,
                "objects": objects,
                "receipt": cls._receipt(collection, inventory),
            }
        )

    @staticmethod
    def _rollback(db: Session) -> None:
        try:
            db.rollback()
        except Exception:
            pass

    @staticmethod
    def _rows_for_collection(
        db: Session,
        collection_id: uuid.UUID,
        *,
        for_update: bool,
    ) -> tuple[
        RawCollection,
        list[tuple[RawCollectionObject, list[RawCollectionChunk]]],
    ] | None:
        statement = select(RawCollection).where(
            RawCollection.collection_id == collection_id
        )
        if for_update:
            statement = statement.with_for_update(of=RawCollection)
        collection = db.scalar(statement)
        if collection is None:
            return None
        objects = list(
            db.scalars(
                select(RawCollectionObject)
                .where(
                    RawCollectionObject.collection_id == collection_id,
                    RawCollectionObject.manifest_sha256
                    == collection.manifest_sha256,
                    RawCollectionObject.consent_receipt_sha256
                    == collection.consent_receipt_sha256,
                )
                .order_by(RawCollectionObject.object_id)
            ).all()
        )
        chunks = list(
            db.scalars(
                select(RawCollectionChunk)
                .where(
                    RawCollectionChunk.collection_id == collection_id,
                    RawCollectionChunk.manifest_sha256
                    == collection.manifest_sha256,
                    RawCollectionChunk.consent_receipt_sha256
                    == collection.consent_receipt_sha256,
                )
                .order_by(
                    RawCollectionChunk.object_id,
                    RawCollectionChunk.chunk_index,
                )
            ).all()
        )
        chunks_by_object: dict[uuid.UUID, list[RawCollectionChunk]] = {}
        for chunk in chunks:
            chunks_by_object.setdefault(chunk.object_id, []).append(chunk)
        inventory: list[
            tuple[RawCollectionObject, list[RawCollectionChunk]]
        ] = []
        observed_chunks = 0
        observed_bytes = 0
        for item in objects:
            item_chunks = chunks_by_object.pop(item.object_id, [])
            if (
                len(item_chunks) != item.chunk_count
                or tuple(chunk.chunk_index for chunk in item_chunks)
                != tuple(range(item.chunk_count))
                or sum(chunk.declared_size_bytes for chunk in item_chunks)
                != item.size_bytes
            ):
                raise _storage_error(
                    "raw_collection_inventory_ambiguous",
                    "The raw collection inventory is inconsistent.",
                )
            inventory.append((item, item_chunks))
            observed_chunks += len(item_chunks)
            observed_bytes += item.size_bytes
        if (
            chunks_by_object
            or len(inventory) != collection.object_count
            or observed_chunks != collection.chunk_count
            or observed_bytes != collection.total_bytes
        ):
            raise _storage_error(
                "raw_collection_inventory_ambiguous",
                "The raw collection inventory is inconsistent.",
            )
        return collection, inventory

    @staticmethod
    def _owned(
        collection: RawCollection,
        *,
        privacy_subject: str,
        account_generation: int,
    ) -> bool:
        return (
            hmac.compare_digest(
                collection.privacy_subject_hmac,
                privacy_subject,
            )
            and collection.account_generation == account_generation
        )

    def put_manifest(
        self,
        db: Session,
        admission: RawCollectionAdmission,
        manifest: RawCollectionManifestV1,
    ) -> tuple[RawCollectionStatusV1, bool]:
        collection_id = uuid.UUID(manifest.collection_id)
        lock_raw_storage_write_transaction(db)
        lock_raw_capacity_reservation_transaction(db)
        db.execute(
            text(
                "SELECT pg_advisory_xact_lock("
                "hashtextextended(:lock_key, 0))"
            ),
            {"lock_key": f"walksafe-raw-manifest-v1:{collection_id}"},
        )
        existing = self._rows_for_collection(
            db,
            collection_id,
            for_update=True,
        )
        if existing is not None:
            collection, inventory = existing
            if not self._owned(
                collection,
                privacy_subject=admission.privacy_subject_hmac,
                account_generation=admission.account_generation,
            ):
                raise _not_found()
            if not hmac.compare_digest(
                collection.manifest_sha256,
                manifest.manifest_sha256,
            ):
                raise RawCollectionStorageError(
                    code="raw_collection_manifest_conflict",
                    message=(
                        "The collection identifier is already bound to another manifest."
                    ),
                    status_code=409,
                )
            for item, chunks in inventory:
                for chunk in chunks:
                    if chunk.storage_name is not None:
                        self._verify_persisted_chunk(collection, item, chunk)
            status = self._status(collection, inventory)
            db.commit()
            return status, False

        self._assert_new_collection_capacity(db, manifest)
        collection = RawCollection(
            collection_id=collection_id,
            privacy_subject_hmac=admission.privacy_subject_hmac,
            account_generation=admission.account_generation,
            walk_id=uuid.UUID(manifest.walk_id),
            segment_id=uuid.UUID(manifest.segment_id),
            purpose=manifest.purpose,
            lifecycle_version=2,
            retention_class=RAW_QUARANTINE_CLASS,
            consent_receipt_sha256=admission.consent_receipt_sha256,
            manifest_sha256=manifest.manifest_sha256,
            captured_started_at=manifest.captured_started_at,
            captured_ended_at=manifest.captured_ended_at,
            object_count=manifest.object_count,
            chunk_count=manifest.chunk_count,
            total_bytes=manifest.total_bytes,
            state="MANIFEST_ACCEPTED",
        )
        inventory: list[
            tuple[RawCollectionObject, list[RawCollectionChunk]]
        ] = []
        for declared_object in manifest.objects:
            item = RawCollectionObject(
                collection_id=collection_id,
                object_id=uuid.UUID(declared_object.object_id),
                manifest_sha256=manifest.manifest_sha256,
                consent_receipt_sha256=admission.consent_receipt_sha256,
                kind=declared_object.kind,
                content_type=declared_object.content_type,
                size_bytes=declared_object.size_bytes,
                sha256=declared_object.sha256,
                chunk_count=len(declared_object.chunks),
            )
            chunks = [
                RawCollectionChunk(
                    collection_id=collection_id,
                    object_id=item.object_id,
                    chunk_index=declared_chunk.index,
                    manifest_sha256=manifest.manifest_sha256,
                    consent_receipt_sha256=admission.consent_receipt_sha256,
                    declared_size_bytes=declared_chunk.size_bytes,
                    declared_sha256=declared_chunk.sha256,
                )
                for declared_chunk in declared_object.chunks
            ]
            inventory.append((item, chunks))
        try:
            # No ORM relationships are declared for these write-only rows, so
            # make the composite-FK insert order explicit inside one transaction.
            db.add(collection)
            db.flush()
            db.add_all([item for item, _chunks in inventory])
            db.flush()
            db.add_all(
                [
                    chunk
                    for _item, chunks in inventory
                    for chunk in chunks
                ]
            )
            db.commit()
        except SQLAlchemyError as exc:
            self._rollback(db)
            raise _storage_error(
                "raw_manifest_storage_unavailable",
                "Raw manifest storage is temporarily unavailable.",
            ) from exc
        status = self._status(collection, inventory)
        return status, True

    @staticmethod
    def _binding(
        collection: RawCollection,
        item: RawCollectionObject,
        chunk: RawCollectionChunk,
    ) -> RawCollectionChunkBinding:
        return RawCollectionChunkBinding(
            privacy_subject_hmac=collection.privacy_subject_hmac,
            account_generation=collection.account_generation,
            collection_id=collection.collection_id,
            object_id=item.object_id,
            chunk_index=chunk.chunk_index,
            manifest_sha256=collection.manifest_sha256,
            purpose=collection.purpose,
            walk_id=collection.walk_id,
            content_type=item.content_type,
            plaintext_length=chunk.declared_size_bytes,
            plaintext_sha256=chunk.declared_sha256,
        )

    def _verify_persisted_chunk(
        self,
        collection: RawCollection,
        item: RawCollectionObject,
        chunk: RawCollectionChunk,
    ) -> bytes:
        if (
            self._root is None
            or chunk.storage_name is None
            or chunk.envelope_size is None
            or chunk.envelope_sha256 is None
            or chunk.key_id is None
            or chunk.nonce is None
            or chunk.envelope_version != ENVELOPE_VERSION
            or chunk.algorithm != "AES-256-GCM"
            or chunk.aad_version != AAD_VERSION
            or chunk.persisted_at is None
        ):
            raise _storage_error(
                "raw_chunk_persistence_ambiguous",
                "Raw chunk persistence metadata is incomplete.",
            )
        expected_name = raw_chunk_storage_name(
            collection.collection_id,
            item.object_id,
            chunk.chunk_index,
        )
        if chunk.storage_name != expected_name:
            raise _storage_error(
                "raw_chunk_persistence_ambiguous",
                "Raw chunk persistence metadata does not match encrypted storage.",
            )
        envelope = read_raw_chunk_envelope(
            self._root,
            chunk.storage_name,
            expected_size=chunk.envelope_size,
            expected_sha256=chunk.envelope_sha256,
        )
        binding = self._binding(collection, item, chunk)
        try:
            parsed = parse_raw_collection_chunk_envelope(
                envelope,
                expected_binding=binding,
            )
            if parsed.key_id != chunk.key_id or parsed.nonce != chunk.nonce:
                raise RawCollectionChunkCryptoError(
                    "raw chunk envelope metadata differs"
                )
            master_key = self._key_manager.decryption_key(chunk.key_id)
            decrypted = decrypt_raw_collection_chunk(
                envelope,
                expected_binding=binding,
                master_key=master_key,
            )
        except (RawCollectionChunkCryptoError, ReportImageKeyError) as exc:
            raise _storage_error(
                "raw_chunk_persistence_ambiguous",
                "Raw chunk persistence metadata does not match encrypted storage.",
            ) from exc
        return decrypted.content

    def put_chunk(
        self,
        db: Session,
        admission: RawCollectionAdmission,
        *,
        collection_id: str,
        object_id: str,
        index: int,
        walk_id: str,
        manifest_sha256: str,
        content: bytes,
        content_sha256: str,
    ) -> tuple[RawCollectionChunkAckV1, bool]:
        collection_uuid = uuid.UUID(collection_id)
        object_uuid = uuid.UUID(object_id)
        lock_raw_storage_write_transaction(db)
        lock_raw_capacity_reservation_transaction(db)
        rows = self._rows_for_collection(
            db,
            collection_uuid,
            for_update=True,
        )
        if rows is None:
            raise _not_found()
        collection, inventory = rows
        if not self._owned(
            collection,
            privacy_subject=admission.privacy_subject_hmac,
            account_generation=admission.account_generation,
        ):
            raise _not_found()
        target = next(
            (
                (item, chunk)
                for item, chunks in inventory
                for chunk in chunks
                if item.object_id == object_uuid and chunk.chunk_index == index
            ),
            None,
        )
        if (
            collection.purpose != admission.purpose
            or collection.walk_id != uuid.UUID(walk_id)
            or not hmac.compare_digest(
                collection.manifest_sha256,
                manifest_sha256,
            )
            or not hmac.compare_digest(
                collection.consent_receipt_sha256,
                admission.consent_receipt_sha256,
            )
            or target is None
        ):
            raise _not_found()
        item, chunk = target
        if (
            len(content) != chunk.declared_size_bytes
            or not hmac.compare_digest(
                content_sha256,
                chunk.declared_sha256,
            )
        ):
            raise RawCollectionStorageError(
                code="raw_chunk_manifest_mismatch",
                message="The raw chunk does not match its manifest declaration.",
                status_code=409,
            )
        if chunk.storage_name is not None:
            self._verify_persisted_chunk(collection, item, chunk)
            assert chunk.persisted_at is not None
            state = self._status(collection, inventory).state
            ack = RawCollectionChunkAckV1.model_validate(
                {
                    "schema_version": "walksafe.raw-collection-chunk-ack.v1",
                    "collection_id": str(collection.collection_id),
                    "object_id": str(item.object_id),
                    "index": chunk.chunk_index,
                    "size_bytes": chunk.declared_size_bytes,
                    "sha256": chunk.declared_sha256,
                    "state": state,
                    "stored_at": _canonical_time(chunk.persisted_at),
                }
            )
            db.commit()
            return ack, False
        if self._root is None:
            raise _storage_error(
                "raw_chunk_storage_unavailable",
                "Raw chunk encrypted storage is not configured.",
            )
        binding = self._binding(collection, item, chunk)
        try:
            slot = self._key_manager.encryption_slot()
            if slot.material is None:
                raise ReportImageKeyError("active report key material is unavailable")
            encrypted = encrypt_raw_collection_chunk(
                content,
                binding=binding,
                key_id=slot.key_id,
                master_key=slot.material,
            )
        except (RawCollectionChunkCryptoError, ReportImageKeyError, ValueError) as exc:
            raise _storage_error(
                "raw_chunk_encryption_unavailable",
                "Raw chunk encryption is temporarily unavailable.",
            ) from exc
        storage_name = raw_chunk_storage_name(
            collection.collection_id,
            item.object_id,
            chunk.chunk_index,
        )
        persisted_at = datetime.now(UTC)
        try:
            pending = stage_raw_chunk_write(
                self._root,
                binding=binding,
                consent_receipt_sha256=collection.consent_receipt_sha256,
                storage_name=storage_name,
                key_id=encrypted.key_id,
                nonce=encrypted.nonce,
                envelope=encrypted.envelope,
                persisted_at=persisted_at,
            )
            persisted = persist_raw_chunk_envelope(
                self._root,
                storage_name,
                encrypted.envelope,
            )
            if (
                persisted.storage_name != pending.metadata.storage_name
                or persisted.envelope_sha256
                != pending.metadata.envelope_sha256
                or persisted.envelope_size != pending.metadata.envelope_size
            ):
                raise _storage_error(
                    "raw_chunk_persistence_ambiguous",
                    "Raw chunk file metadata does not match its durable journal.",
                )
        except (RawCollectionStorageError, OSError, ValueError):
            self._rollback(db)
            raise
        chunk.storage_name = persisted.storage_name
        chunk.envelope_version = ENVELOPE_VERSION
        chunk.algorithm = "AES-256-GCM"
        chunk.aad_version = AAD_VERSION
        chunk.key_id = encrypted.key_id
        chunk.nonce = encrypted.nonce
        chunk.envelope_sha256 = persisted.envelope_sha256
        chunk.envelope_size = persisted.envelope_size
        chunk.persisted_at = persisted_at
        received_chunk_count = sum(
            candidate.storage_name is not None
            for _item, chunks in inventory
            for candidate in chunks
        )
        if received_chunk_count == collection.chunk_count:
            collection.state = "READY_TO_COMMIT"
        ack_state = (
            "READY_TO_COMMIT"
            if received_chunk_count == collection.chunk_count
            else "RECEIVING"
        )
        try:
            db.commit()
        except SQLAlchemyError as exc:
            self._rollback(db)
            raise _storage_error(
                "raw_chunk_persistence_ambiguous",
                "Raw chunk file exists but its database commit is ambiguous.",
            ) from exc
        try:
            complete_raw_chunk_write(pending)
        except OSError as exc:
            raise _storage_error(
                "raw_chunk_persistence_ambiguous",
                "Raw chunk commit succeeded but journal cleanup is ambiguous.",
            ) from exc
        return (
            RawCollectionChunkAckV1.model_validate(
                {
                    "schema_version": "walksafe.raw-collection-chunk-ack.v1",
                    "collection_id": str(collection_uuid),
                    "object_id": str(object_uuid),
                    "index": index,
                    "size_bytes": len(content),
                    "sha256": content_sha256,
                    "state": ack_state,
                    "stored_at": _canonical_time(persisted_at),
                }
            ),
            True,
        )

    def get_status(
        self,
        db: Session,
        *,
        actor_id: str,
        account_generation: int,
        collection_id: str,
        purpose: str,
        walk_id: str,
        manifest_sha256: str,
    ) -> RawCollectionStatusV1:
        privacy_subject = privacy_subject_hmac(
            actor_id,
            account_generation,
            self._privacy_hmac_secret,
        )
        rows = self._rows_for_collection(
            db,
            uuid.UUID(collection_id),
            for_update=False,
        )
        if rows is None:
            raise _not_found()
        collection, inventory = rows
        if (
            not self._owned(
                collection,
                privacy_subject=privacy_subject,
                account_generation=account_generation,
            )
            or collection.purpose != purpose
            or collection.walk_id != uuid.UUID(walk_id)
            or not hmac.compare_digest(
                collection.manifest_sha256,
                manifest_sha256,
            )
        ):
            raise _not_found()
        for item, chunks in inventory:
            for chunk in chunks:
                if chunk.storage_name is not None:
                    self._verify_persisted_chunk(collection, item, chunk)
        return self._status(collection, inventory)

    def commit(
        self,
        db: Session,
        admission: RawCollectionAdmission,
        payload: RawCollectionCommitV1,
        *,
        walk_id: str,
    ) -> RawCollectionReceiptV1 | RawCollectionReceiptV2:
        rows = self._rows_for_collection(
            db,
            uuid.UUID(payload.collection_id),
            for_update=True,
        )
        if rows is None:
            raise _not_found()
        collection, inventory = rows
        if (
            not self._owned(
                collection,
                privacy_subject=admission.privacy_subject_hmac,
                account_generation=admission.account_generation,
            )
            or collection.purpose != admission.purpose
            or collection.walk_id != uuid.UUID(walk_id)
            or not hmac.compare_digest(
                collection.manifest_sha256,
                payload.manifest_sha256,
            )
            or not hmac.compare_digest(
                collection.consent_receipt_sha256,
                admission.consent_receipt_sha256,
            )
        ):
            raise _not_found()
        if (
            payload.object_count != collection.object_count
            or payload.chunk_count != collection.chunk_count
            or payload.total_bytes != collection.total_bytes
        ):
            raise RawCollectionStorageError(
                code="raw_commit_inventory_mismatch",
                message="The commit inventory does not match the accepted manifest.",
                status_code=409,
            )
        if collection.state in {"COMMITTED", "QUARANTINED"}:
            for item, chunks in inventory:
                for chunk in chunks:
                    self._verify_persisted_chunk(collection, item, chunk)
            receipt = self._receipt(collection, inventory)
            assert receipt is not None
            try:
                db.commit()
            except SQLAlchemyError as exc:
                self._rollback(db)
                raise _storage_error(
                    "raw_commit_persistence_ambiguous",
                    "Raw collection commit persistence is temporarily ambiguous.",
                ) from exc
            return receipt
        if collection.state in {"MANIFEST_ACCEPTED", "RECEIVING"}:
            raise RawCollectionStorageError(
                code="raw_collection_incomplete",
                message="The raw collection still has missing chunks.",
                status_code=409,
            )
        if collection.state != "READY_TO_COMMIT":
            raise _storage_error(
                "raw_collection_state_ambiguous",
                "The raw collection cannot be committed from its current state.",
            )
        for item, chunks in inventory:
            digest = hashlib.sha256()
            for chunk in chunks:
                if chunk.storage_name is None:
                    raise _storage_error(
                        "raw_collection_inventory_ambiguous",
                        "The raw collection inventory is inconsistent.",
                    )
                digest.update(
                    self._verify_persisted_chunk(collection, item, chunk)
                )
            if not hmac.compare_digest(digest.hexdigest(), item.sha256):
                raise _storage_error(
                    "raw_collection_inventory_ambiguous",
                    "The raw collection inventory is inconsistent.",
                )
        committed_at = datetime.now(UTC).replace(microsecond=0)
        if collection.lifecycle_version == 1:
            retention_expires_at = committed_at + timedelta(days=180)
            receipt_payload = self._receipt_payload_v1(
                collection,
                inventory,
                committed_at=committed_at,
                retention_expires_at=retention_expires_at,
            )
            receipt: RawCollectionReceiptV1 | RawCollectionReceiptV2 = (
                RawCollectionReceiptV1.model_validate({
                    **receipt_payload,
                    "receipt_sha256": raw_collection_receipt_sha256(
                        receipt_payload
                    ),
                })
            )
            collection.retention_expires_at = retention_expires_at
            collection.state = "COMMITTED"
        else:
            quarantine_expires_at = committed_at + timedelta(days=14)
            receipt_payload = self._receipt_payload_v2(
                collection,
                inventory,
                committed_at=committed_at,
                quarantine_expires_at=quarantine_expires_at,
            )
            receipt = RawCollectionReceiptV2.model_validate({
                **receipt_payload,
                "receipt_sha256": raw_collection_receipt_v2_sha256(
                    receipt_payload
                ),
            })
            collection.quarantine_expires_at = quarantine_expires_at
            collection.state = "QUARANTINED"
        collection.committed_at = committed_at
        collection.receipt_sha256 = receipt.receipt_sha256
        try:
            db.commit()
        except SQLAlchemyError as exc:
            self._rollback(db)
            raise _storage_error(
                "raw_commit_persistence_ambiguous",
                "Raw collection commit persistence is temporarily ambiguous.",
            ) from exc
        return receipt


__all__ = [
    "PendingRawChunkWrite",
    "PersistedRawChunkFile",
    "RawChunkCommitMetadata",
    "RawChunkCommitState",
    "RawCollectionStorage",
    "RawStorageReconciliation",
    "complete_raw_chunk_write",
    "lock_raw_storage_reconciliation_transaction",
    "lock_raw_storage_write_transaction",
    "pending_raw_journal_directory",
    "persist_raw_chunk_envelope",
    "prepare_raw_object_directory",
    "probe_raw_object_directory",
    "raw_chunk_commit_state",
    "raw_chunk_storage_name",
    "raw_storage_has_pending_writes",
    "read_raw_chunk_envelope",
    "reconcile_pending_raw_chunk_writes",
    "stage_raw_chunk_write",
    "validate_raw_storage_database_inventory",
]
