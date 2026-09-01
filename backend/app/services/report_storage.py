"""Durable journal for the encrypted report-image/database commit boundary."""

from __future__ import annotations

import base64
from contextlib import contextmanager
from dataclasses import dataclass
import errno
import hashlib
import json
import os
from pathlib import Path
import re
import stat
from typing import Callable, Iterable, Iterator, Mapping
import uuid

from sqlalchemy import select, text
from sqlalchemy.orm import Session

from backend.app.services.report_image_crypto import (
    EncryptedReportImage,
    MAX_ENVELOPE_BYTES,
    ReportImageCryptoError,
    parse_report_image_envelope,
)
from backend.app.uploads import (
    PRIVATE_UPLOAD_DIRECTORY_MODE,
    descriptor_acl_is_absent,
    expected_upload_file_mode,
    remove_image_file,
    upload_file_metadata_is_safe,
    validate_upload_directory_descriptor,
    write_image_file,
)


JOURNAL_DIRECTORY_NAME = ".report-write-journal"
JOURNAL_SCHEMA = "walksafe.report-write.v2"
LEGACY_JOURNAL_SCHEMA = "walksafe.report-write.v1"
MAX_PENDING_REPORT_WRITES = 10_000
REPORT_STORAGE_TRANSACTION_LOCK_KEY = "walksafe-report-storage-reconciliation-v1"
_ENCRYPTED_IMAGE_NAME_PATTERN = re.compile(
    r"^(?P<report_id>[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\.wse$"
)
_LEGACY_IMAGE_NAME_PATTERN = re.compile(
    r"^(?P<report_id>[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\.(?:jpg|png|webp)$"
)
_KEY_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_CONTENT_TYPES = frozenset({"image/jpeg", "image/png", "image/webp"})
_CONTENT_TYPE_SUFFIXES = {
    "image/jpeg": "jpg",
    "image/png": "png",
    "image/webp": "webp",
}
_LOGICAL_IMAGE_PATH_PATTERN = re.compile(
    r"^/uploads/(?P<report_id>[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12})\.(?P<suffix>jpg|png|webp)$"
)
RETENTION_QUARANTINE_DIRECTORY_NAME = ".retention-quarantine"
REPORT_DELETION_QUARANTINE_DIRECTORY_NAME = ".report-deletion-quarantine"
_INVENTORY_SET_MISMATCH_HASH_DOMAIN = (
    b"walksafe/report-storage-inventory-set-mismatch/v1\0"
)


class ReportStorageInventorySetMismatch(RuntimeError):
    """Exact database/object name-set mismatch with stable, non-secret evidence."""

    def __init__(
        self,
        *,
        expected_storage_names: Iterable[str],
        actual_storage_names: Iterable[str],
    ) -> None:
        expected = set(expected_storage_names)
        actual = set(actual_storage_names)
        evidence = json.dumps(
            {
                "missing_storage_names": sorted(expected - actual),
                "schema": "walksafe.report-storage-inventory-set-mismatch.v1",
                "unexpected_storage_names": sorted(actual - expected),
            },
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        self.evidence_sha256 = hashlib.sha256(
            _INVENTORY_SET_MISMATCH_HASH_DOMAIN + evidence
        ).hexdigest()
        super().__init__("report database and encrypted object inventory differ")


@dataclass(frozen=True)
class PendingReportWrite:
    report_id: uuid.UUID
    destination: Path
    journal_path: Path
    schema_version: str
    key_id: str | None = None
    nonce: bytes | None = None
    envelope_sha256: str | None = None
    envelope_size: int | None = None
    plaintext_sha256: str | None = None
    plaintext_size: int | None = None
    content_type: str | None = None


@dataclass(frozen=True)
class ReportImageCommitMetadata:
    storage_name: str
    envelope_version: int
    algorithm: str
    aad_version: int
    key_id: str
    nonce: bytes
    envelope_sha256: str
    envelope_size: int
    plaintext_sha256: str
    plaintext_size: int
    content_type: str


@dataclass(frozen=True)
class ReportStorageCommitState:
    report_exists: bool
    image_object: ReportImageCommitMetadata | None


@dataclass(frozen=True)
class ReportStorageReconciliation:
    retained_committed: int
    removed_orphans: int


@dataclass(frozen=True)
class ReportStorageInventoryEntry:
    report_id: uuid.UUID
    logical_image_path: str | None
    report_content_type: str | None
    image_object: ReportImageCommitMetadata | None


def lock_report_storage_write_transaction(db: Session) -> None:
    """Keep startup reconciliation outside the image/DB commit boundary."""

    db.execute(
        text("SELECT pg_advisory_xact_lock_shared(hashtextextended(:lock_key, 0))"),
        {"lock_key": REPORT_STORAGE_TRANSACTION_LOCK_KEY},
    )


def lock_report_storage_reconciliation_transaction(db: Session) -> None:
    """Serialize reconciliation with writers and other backend replicas."""

    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": REPORT_STORAGE_TRANSACTION_LOCK_KEY},
    )


def pending_journal_directory(upload_root: Path) -> Path:
    return upload_root / JOURNAL_DIRECTORY_NAME


def _validated_name(report_id: uuid.UUID, image_name: str, pattern: re.Pattern[str]) -> str:
    match = pattern.fullmatch(image_name)
    if match is None or match.group("report_id") != str(report_id):
        raise ValueError("report image filename must be bound to its report UUID")
    return image_name


def _encode_base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _decode_nonce(value: object) -> bytes:
    if not isinstance(value, str) or "=" in value:
        raise ValueError("journal nonce is invalid")
    decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    if len(decoded) != 12 or _encode_base64url(decoded) != value:
        raise ValueError("journal nonce is invalid")
    return decoded


def stage_report_image(destination: Path, encrypted: EncryptedReportImage) -> PendingReportWrite:
    image_name = _validated_name(encrypted.report_id, destination.name, _ENCRYPTED_IMAGE_NAME_PATTERN)
    journal_path = pending_journal_directory(destination.parent) / f"{encrypted.report_id}.json"
    payload = json.dumps(
        {
            "aad_version": 1,
            "content_type": encrypted.content_type,
            "envelope_sha256": encrypted.envelope_sha256,
            "envelope_size": len(encrypted.envelope),
            "envelope_version": 1,
            "key_id": encrypted.key_id,
            "nonce_b64url": _encode_base64url(encrypted.nonce),
            "plaintext_sha256": encrypted.plaintext_sha256,
            "plaintext_size": encrypted.plaintext_length,
            "report_id": str(encrypted.report_id),
            "schema_version": JOURNAL_SCHEMA,
            "storage_name": image_name,
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    write_image_file(journal_path, payload)
    # Keep the journal even if the durable rename fails. Reconciliation can
    # distinguish an absent DB row from an ambiguous commit safely.
    write_image_file(destination, encrypted.envelope)
    return PendingReportWrite(
        encrypted.report_id,
        destination,
        journal_path,
        JOURNAL_SCHEMA,
        key_id=encrypted.key_id,
        nonce=encrypted.nonce,
        envelope_sha256=encrypted.envelope_sha256,
        envelope_size=len(encrypted.envelope),
        plaintext_sha256=encrypted.plaintext_sha256,
        plaintext_size=encrypted.plaintext_length,
        content_type=encrypted.content_type,
    )


def complete_report_image_write(pending: PendingReportWrite) -> None:
    remove_image_file(pending.journal_path)
    try:
        pending.journal_path.parent.rmdir()
    except FileNotFoundError:
        pass
    except OSError as exc:
        if exc.errno not in {errno.EEXIST, errno.ENOTEMPTY}:
            raise


def _remove_interrupted_temporary_files(destination: Path) -> None:
    for candidate in destination.parent.glob(f".{destination.name}.*.tmp"):
        candidate.unlink(missing_ok=True)


def _validate_journal_directory(journal_dir: Path) -> None:
    metadata = journal_dir.stat(follow_symlinks=False)
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or journal_dir.is_symlink()
        or metadata.st_uid != os.geteuid()
        or stat.S_IMODE(metadata.st_mode) != PRIVATE_UPLOAD_DIRECTORY_MODE
    ):
        raise RuntimeError("report write journal directory is not private")


def _read_pending_write(upload_root: Path, journal_path: Path) -> PendingReportWrite:
    try:
        if journal_path.is_symlink() or not stat.S_ISREG(journal_path.stat(follow_symlinks=False).st_mode):
            raise ValueError("journal is not a regular file")
        payload = json.loads(journal_path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("journal is not an object")
        report_id = uuid.UUID(str(payload.get("report_id")))
        if str(report_id) != payload.get("report_id") or journal_path.name != f"{report_id}.json":
            raise ValueError("journal report id mismatch")
        schema_version = payload.get("schema_version")
        if schema_version == LEGACY_JOURNAL_SCHEMA:
            if set(payload) != {"schema_version", "report_id", "image_filename"}:
                raise ValueError("legacy journal shape changed")
            image_name = _validated_name(
                report_id,
                str(payload["image_filename"]),
                _LEGACY_IMAGE_NAME_PATTERN,
            )
            return PendingReportWrite(
                report_id,
                upload_root / image_name,
                journal_path,
                LEGACY_JOURNAL_SCHEMA,
            )
        expected_keys = {
            "aad_version",
            "content_type",
            "envelope_sha256",
            "envelope_size",
            "envelope_version",
            "key_id",
            "nonce_b64url",
            "plaintext_sha256",
            "plaintext_size",
            "report_id",
            "schema_version",
            "storage_name",
        }
        if schema_version != JOURNAL_SCHEMA or set(payload) != expected_keys:
            raise ValueError("journal schema or shape changed")
        if payload["envelope_version"] != 1 or payload["aad_version"] != 1:
            raise ValueError("journal envelope version changed")
        image_name = _validated_name(
            report_id,
            str(payload["storage_name"]),
            _ENCRYPTED_IMAGE_NAME_PATTERN,
        )
        nonce = _decode_nonce(payload["nonce_b64url"])
        key_id = payload["key_id"]
        envelope_sha256 = payload["envelope_sha256"]
        plaintext_sha256 = payload["plaintext_sha256"]
        envelope_size = payload["envelope_size"]
        plaintext_size = payload["plaintext_size"]
        content_type = payload["content_type"]
        if (
            not isinstance(key_id, str)
            or _KEY_ID_PATTERN.fullmatch(key_id) is None
            or not isinstance(envelope_sha256, str)
            or _SHA256_PATTERN.fullmatch(envelope_sha256) is None
            or not isinstance(plaintext_sha256, str)
            or _SHA256_PATTERN.fullmatch(plaintext_sha256) is None
            or not isinstance(envelope_size, int)
            or isinstance(envelope_size, bool)
            or not 0 < envelope_size <= MAX_ENVELOPE_BYTES
            or not isinstance(plaintext_size, int)
            or isinstance(plaintext_size, bool)
            or plaintext_size <= 0
            or envelope_size <= plaintext_size + 16
            or content_type not in _CONTENT_TYPES
        ):
            raise ValueError("journal metadata types changed")
        return PendingReportWrite(
            report_id,
            upload_root / image_name,
            journal_path,
            JOURNAL_SCHEMA,
            key_id=key_id,
            nonce=nonce,
            envelope_sha256=envelope_sha256,
            envelope_size=envelope_size,
            plaintext_sha256=plaintext_sha256,
            plaintext_size=plaintext_size,
            content_type=content_type,
        )
    except (OSError, UnicodeError, ValueError, TypeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"invalid report write journal: {journal_path.name}") from exc


def _read_committed_envelope(pending: PendingReportWrite) -> bytes:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(pending.destination, flags)
        try:
            metadata = os.fstat(descriptor)
            path_metadata = pending.destination.stat(follow_symlinks=False)
            # Inventory paths are intentionally anchored through /proc/self/fd.
            directory_metadata = pending.destination.parent.stat()
            if (
                not upload_file_metadata_is_safe(metadata, directory_metadata)
                or not descriptor_acl_is_absent(descriptor)
                or (metadata.st_dev, metadata.st_ino) != (path_metadata.st_dev, path_metadata.st_ino)
                or metadata.st_size != pending.envelope_size
                or metadata.st_size > MAX_ENVELOPE_BYTES
            ):
                raise RuntimeError("committed report image file metadata changed")
            chunks: list[bytes] = []
            remaining = metadata.st_size
            while remaining:
                chunk = os.read(descriptor, min(1024 * 1024, remaining))
                if not chunk:
                    raise RuntimeError("committed report image is truncated")
                chunks.append(chunk)
                remaining -= len(chunk)
            if not descriptor_acl_is_absent(descriptor):
                raise RuntimeError("committed report image ACL changed")
            return b"".join(chunks)
        finally:
            os.close(descriptor)
    except OSError as exc:
        raise RuntimeError("committed report image is missing during reconciliation") from exc


def _validate_committed(pending: PendingReportWrite, metadata: ReportImageCommitMetadata) -> None:
    expected = (
        pending.destination.name,
        1,
        "AES-256-GCM",
        1,
        pending.key_id,
        pending.nonce,
        pending.envelope_sha256,
        pending.envelope_size,
        pending.plaintext_sha256,
        pending.plaintext_size,
        pending.content_type,
    )
    observed = (
        metadata.storage_name,
        metadata.envelope_version,
        metadata.algorithm,
        metadata.aad_version,
        metadata.key_id,
        metadata.nonce,
        metadata.envelope_sha256,
        metadata.envelope_size,
        metadata.plaintext_sha256,
        metadata.plaintext_size,
        metadata.content_type,
    )
    if observed != expected:
        raise RuntimeError("committed report image metadata does not match its journal")
    envelope = _read_committed_envelope(pending)
    if hashlib.sha256(envelope).hexdigest() != pending.envelope_sha256:
        raise RuntimeError("committed report image digest does not match its journal")
    try:
        parsed = parse_report_image_envelope(envelope, expected_report_id=pending.report_id)
    except ReportImageCryptoError as exc:
        raise RuntimeError("committed report image envelope is invalid") from exc
    if (
        parsed.key_id != pending.key_id
        or parsed.nonce != pending.nonce
        or parsed.content_type != pending.content_type
        or parsed.plaintext_length != pending.plaintext_size
        or parsed.plaintext_sha256 != pending.plaintext_sha256
    ):
        raise RuntimeError("committed report image header does not match its journal")


def reconcile_pending_report_writes(
    upload_root: Path,
    commit_state: Callable[[uuid.UUID], ReportStorageCommitState],
) -> ReportStorageReconciliation:
    root_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    try:
        root_descriptor = os.open(upload_root, root_flags)
        try:
            validate_upload_directory_descriptor(upload_root, root_descriptor)
        finally:
            os.close(root_descriptor)
    except (OSError, ValueError) as exc:
        raise RuntimeError(
            "report upload root does not satisfy the private/read-only-backup contract"
        ) from exc

    journal_dir = pending_journal_directory(upload_root)
    if not journal_dir.exists():
        return ReportStorageReconciliation(retained_committed=0, removed_orphans=0)
    try:
        _validate_journal_directory(journal_dir)
    except FileNotFoundError:
        return ReportStorageReconciliation(retained_committed=0, removed_orphans=0)
    journal_paths = sorted(journal_dir.glob("*.json"))
    if len(journal_paths) > MAX_PENDING_REPORT_WRITES:
        raise RuntimeError("too many pending report write journals")

    retained_committed = 0
    removed_orphans = 0
    for journal_path in journal_paths:
        pending = _read_pending_write(upload_root, journal_path)
        state = commit_state(pending.report_id)
        if pending.schema_version == LEGACY_JOURNAL_SCHEMA:
            raise RuntimeError(
                "legacy plaintext report image requires an offline migration"
            )
        if not state.report_exists:
            remove_image_file(pending.destination)
            _remove_interrupted_temporary_files(pending.destination)
            removed_orphans += 1
            complete_report_image_write(pending)
            continue
        if state.image_object is None:
            raise RuntimeError("committed report has no encrypted image metadata")
        _validate_committed(pending, state.image_object)
        retained_committed += 1
        complete_report_image_write(pending)

    try:
        journal_dir.rmdir()
    except FileNotFoundError:
        pass
    except OSError as exc:
        if exc.errno not in {errno.EEXIST, errno.ENOTEMPTY}:
            raise

    return ReportStorageReconciliation(
        retained_committed=retained_committed,
        removed_orphans=removed_orphans,
    )


def _directory_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _validate_empty_private_directory_at(
    root_descriptor: int,
    name: str,
    *,
    label: str,
) -> None:
    before = os.stat(name, dir_fd=root_descriptor, follow_symlinks=False)
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    descriptor = os.open(name, flags, dir_fd=root_descriptor)
    try:
        opened = os.fstat(descriptor)
        if (
            _directory_identity(opened) != _directory_identity(before)
            or not stat.S_ISDIR(opened.st_mode)
            or opened.st_uid != os.geteuid()
            or stat.S_IMODE(opened.st_mode) != 0o700
            or not descriptor_acl_is_absent(descriptor)
        ):
            raise RuntimeError(f"{label} directory is not private")
        if os.listdir(descriptor):
            raise RuntimeError(f"{label} directory is not empty after reconciliation")
        after = os.fstat(descriptor)
        path_after = os.stat(name, dir_fd=root_descriptor, follow_symlinks=False)
        if (
            _directory_identity(after) != _directory_identity(opened)
            or _directory_identity(path_after) != _directory_identity(opened)
            or not descriptor_acl_is_absent(descriptor)
        ):
            raise RuntimeError(f"{label} directory identity changed during preflight")
    finally:
        os.close(descriptor)


@contextmanager
def _opened_flat_upload_inventory(upload_root: Path) -> Iterator[dict[str, Path]]:
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    descriptor = os.open(upload_root, flags)
    try:
        before = os.fstat(descriptor)
        path_before = upload_root.stat(follow_symlinks=False)
        if (
            _directory_identity(before) != _directory_identity(path_before)
            or not stat.S_ISDIR(before.st_mode)
            or before.st_uid != os.geteuid()
            or not descriptor_acl_is_absent(descriptor)
        ):
            raise RuntimeError("report upload root is not a private real directory")
        try:
            expected_upload_file_mode(before)
        except ValueError as exc:
            raise RuntimeError(
                "report upload root does not satisfy the private/read-only-backup contract"
            ) from exc
        actual_objects: dict[str, Path] = {}
        for name in sorted(os.listdir(descriptor)):
            if name == JOURNAL_DIRECTORY_NAME:
                _validate_empty_private_directory_at(
                    descriptor,
                    name,
                    label="report write journal",
                )
                continue
            if name == RETENTION_QUARANTINE_DIRECTORY_NAME:
                _validate_empty_private_directory_at(
                    descriptor,
                    name,
                    label="report retention quarantine",
                )
                continue
            if name == REPORT_DELETION_QUARANTINE_DIRECTORY_NAME:
                _validate_empty_private_directory_at(
                    descriptor,
                    name,
                    label="report deletion quarantine",
                )
                continue
            metadata = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
            object_descriptor = os.open(
                name,
                os.O_RDONLY
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0)
                | getattr(os, "O_NONBLOCK", 0),
                dir_fd=descriptor,
            )
            try:
                opened_object = os.fstat(object_descriptor)
                if (
                    not upload_file_metadata_is_safe(opened_object, before)
                    or (opened_object.st_dev, opened_object.st_ino)
                    != (metadata.st_dev, metadata.st_ino)
                    or not descriptor_acl_is_absent(object_descriptor)
                    or _ENCRYPTED_IMAGE_NAME_PATTERN.fullmatch(name) is None
                ):
                    raise RuntimeError(
                        "report upload root contains a legacy or unknown entry, or an ACL-bearing object"
                    )
            finally:
                os.close(object_descriptor)
            actual_objects[name] = Path(f"/proc/self/fd/{descriptor}") / name
        yield actual_objects
        after = os.fstat(descriptor)
        path_after = upload_root.stat(follow_symlinks=False)
        if (
            _directory_identity(after) != _directory_identity(before)
            or (path_after.st_dev, path_after.st_ino) != (after.st_dev, after.st_ino)
            or not descriptor_acl_is_absent(descriptor)
        ):
            raise RuntimeError("report upload root identity changed during preflight")
    finally:
        os.close(descriptor)


def validate_report_storage_inventory(
    upload_root: Path,
    entries: Iterable[ReportStorageInventoryEntry],
    *,
    known_key_states: Mapping[str, str],
) -> None:
    """Fail startup unless database rows and encrypted flat objects agree exactly."""

    with _opened_flat_upload_inventory(upload_root) as actual_objects:
        expected_objects: dict[str, ReportStorageInventoryEntry] = {}
        seen_report_ids: set[uuid.UUID] = set()
        for entry in entries:
            if entry.report_id in seen_report_ids:
                raise RuntimeError("report storage inventory contains duplicate database identities")
            seen_report_ids.add(entry.report_id)
            image_object = entry.image_object
            if (
                entry.logical_image_path is None
                or entry.report_content_type not in _CONTENT_TYPE_SUFFIXES
                or image_object is None
            ):
                raise RuntimeError("report storage inventory contains a legacy or orphan database row")
            logical_match = _LOGICAL_IMAGE_PATH_PATTERN.fullmatch(entry.logical_image_path)
            if (
                logical_match is None
                or logical_match.group("report_id") != str(entry.report_id)
                or logical_match.group("suffix")
                != _CONTENT_TYPE_SUFFIXES[entry.report_content_type]
                or image_object.storage_name != f"{entry.report_id}.wse"
                or image_object.content_type != entry.report_content_type
            ):
                raise RuntimeError("report logical image path and encrypted object metadata disagree")
            key_state = known_key_states.get(image_object.key_id)
            if key_state is None or key_state in {
                "retired",
                "destroyed",
                "compromised",
            }:
                raise RuntimeError(
                    "report storage inventory references an unavailable key lifecycle state"
                )
            if image_object.storage_name in expected_objects:
                raise RuntimeError("report storage inventory reuses an encrypted storage name")
            expected_objects[image_object.storage_name] = entry

        if set(actual_objects) != set(expected_objects):
            raise ReportStorageInventorySetMismatch(
                expected_storage_names=expected_objects,
                actual_storage_names=actual_objects,
            )

        for storage_name, entry in expected_objects.items():
            image_object = entry.image_object
            assert image_object is not None
            pending = PendingReportWrite(
                report_id=entry.report_id,
                destination=actual_objects[storage_name],
                journal_path=(
                    upload_root / JOURNAL_DIRECTORY_NAME / f"{entry.report_id}.json"
                ),
                schema_version=JOURNAL_SCHEMA,
                key_id=image_object.key_id,
                nonce=image_object.nonce,
                envelope_sha256=image_object.envelope_sha256,
                envelope_size=image_object.envelope_size,
                plaintext_sha256=image_object.plaintext_sha256,
                plaintext_size=image_object.plaintext_size,
                content_type=image_object.content_type,
            )
            _validate_committed(pending, image_object)


def validate_report_storage_database_inventory(
    db: Session,
    upload_root: Path,
    *,
    known_key_states: Mapping[str, str],
) -> None:
    """Validate one locked database/filesystem inventory snapshot."""

    from backend.app.models import Report, ReportImageObject

    lock_report_storage_reconciliation_transaction(db)
    reports_by_id = {
        report.id: report for report in db.scalars(select(Report)).all()
    }
    objects_by_id = {
        image_object.report_id: image_object
        for image_object in db.scalars(select(ReportImageObject)).all()
    }
    validate_report_storage_inventory(
        upload_root,
        (
            ReportStorageInventoryEntry(
                report_id=report_id,
                logical_image_path=(
                    reports_by_id[report_id].image_path
                    if report_id in reports_by_id
                    else None
                ),
                report_content_type=(
                    reports_by_id[report_id].image_content_type
                    if report_id in reports_by_id
                    else None
                ),
                image_object=(
                    ReportImageCommitMetadata(
                        storage_name=objects_by_id[report_id].storage_name,
                        envelope_version=objects_by_id[report_id].envelope_version,
                        algorithm=objects_by_id[report_id].algorithm,
                        aad_version=objects_by_id[report_id].aad_version,
                        key_id=objects_by_id[report_id].key_id,
                        nonce=objects_by_id[report_id].nonce,
                        envelope_sha256=objects_by_id[report_id].envelope_sha256,
                        envelope_size=objects_by_id[report_id].envelope_size,
                        plaintext_sha256=objects_by_id[report_id].plaintext_sha256,
                        plaintext_size=objects_by_id[report_id].plaintext_size,
                        content_type=objects_by_id[report_id].content_type,
                    )
                    if report_id in objects_by_id
                    else None
                ),
            )
            for report_id in sorted(
                reports_by_id.keys() | objects_by_id.keys(),
                key=str,
            )
        ),
        known_key_states=known_key_states,
    )
