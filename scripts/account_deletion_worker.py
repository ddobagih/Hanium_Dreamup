#!/usr/bin/env python3
"""Delete one bounded batch of server-owned account data in a guarded one-shot."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import ctypes
from datetime import UTC, datetime
import errno
import fcntl
import grp
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import time
from typing import Callable, Iterator, Mapping

from sqlalchemy import create_engine, delete, func, select
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session, sessionmaker


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.models import (  # noqa: E402
    AccountDeletionItem,
    AccountDeletionRequest,
    AccountDeletionTombstone,
    PrivacyConsentEvent,
    Report,
    ReportImageObject,
)
from backend.app.config import _validate_deployment_database_transport  # noqa: E402
from backend.app.services.privacy_lifecycle import (  # noqa: E402
    TERMINAL_ITEM_STATES,
    assert_account_deletion_worker_database_role,
    complete_server_deletion_inventory_from_manifest,
    lock_report_deletion_transaction,
)
from backend.app.services.report_storage import (  # noqa: E402
    lock_report_storage_reconciliation_transaction,
)


JOURNAL_SCHEMA = "walksafe.account-deletion-worker-journal.v1"
MANIFEST_SCHEMA = "walksafe.account-deletion-server-manifest.v1"
REQUEST_ID = re.compile(r"^[A-Za-z0-9_-]{16,128}$")
STORAGE_NAME = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}\.wse$"
)
SHA256 = re.compile(r"^[0-9a-f]{64}$")
MAX_BATCH_SIZE = 100
MAX_LOCK_TIMEOUT_SECONDS = 300
SINGLE_HOST_MARKER = "single-host-local-filesystem"
INVOCATION_ID = re.compile(r"^[0-9a-f]{32}$")
FORBIDDEN_MANUAL_ENVIRONMENT_NAMES = frozenset(
    {
        "DATABASE_URL",
        "WALKSAFE_MIGRATION_DATABASE_URL",
        "WALKSAFE_FIELD_TEST_TOKEN",
        "WALKSAFE_PRIVACY_HMAC_SECRET",
        "WALKSAFE_GATEWAY_SESSION_SECRET",
        "WALKSAFE_REPORT_IMAGE_KEY_FILE",
        "WALKSAFE_GATEWAY_STATE_KEYRING_FILE",
    }
)
_AT_FDCWD = -100
_RENAME_NOREPLACE = 1


class AccountDeletionWorkerError(RuntimeError):
    pass


def _canonical_json(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _iso(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _parse_time(value: object) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise AccountDeletionWorkerError("journal time is not canonical UTC")
    parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise AccountDeletionWorkerError("journal time lacks a timezone")
    return parsed.astimezone(UTC)


def _owned_by_current_process(uid: int) -> bool:
    return not hasattr(os, "geteuid") or uid == os.geteuid()


def _directory_binding(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
    )


def _upload_directory(path: Path, *, expected_group_gid: int | None) -> Path:
    if not path.is_absolute() or Path(os.path.abspath(path)) != path:
        raise AccountDeletionWorkerError("upload directory must be canonical and absolute")
    if expected_group_gid is not None and expected_group_gid <= 0:
        raise AccountDeletionWorkerError("upload backup reader group must not be root")
    descriptor = os.open(
        path,
        os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0),
    )
    try:
        opened = os.fstat(descriptor)
        anchored = path.stat(follow_symlinks=False)
        expected_mode = 0o2750 if expected_group_gid is not None else 0o700
        if (
            not stat.S_ISDIR(opened.st_mode)
            or opened.st_uid != os.geteuid()
            or (
                expected_group_gid is not None
                and opened.st_gid != expected_group_gid
            )
            or stat.S_IMODE(opened.st_mode) != expected_mode
            or _directory_binding(opened) != _directory_binding(anchored)
            or not _descriptor_acl_is_absent(descriptor)
        ):
            raise AccountDeletionWorkerError("upload directory contract or ACL differs")
    finally:
        os.close(descriptor)
    return path


def _secure_directory(path: Path, *, create: bool) -> Path:
    if not path.is_absolute() or Path(os.path.abspath(path)) != path:
        raise AccountDeletionWorkerError("worker directories must be canonical absolute paths")
    created = False
    if create:
        try:
            path.mkdir(parents=True, mode=0o700)
            created = True
        except FileExistsError:
            pass
        if created:
            path.chmod(0o700)
    metadata = path.lstat()
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or path.is_symlink()
        or path.resolve(strict=True) != path
        or not _owned_by_current_process(metadata.st_uid)
        or stat.S_IMODE(metadata.st_mode) != 0o700
    ):
        raise AccountDeletionWorkerError("worker directory must be real, owned, and mode 0700")
    return path


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _sha256_descriptor(descriptor: int) -> str:
    digest = hashlib.sha256()
    while chunk := os.read(descriptor, 1024 * 1024):
        digest.update(chunk)
    return digest.hexdigest()


def _object_binding(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _object_rename_binding(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
    )


def _assert_bound_directory(
    path: Path,
    descriptor: int,
    *,
    expected_mode: int,
    expected_gid: int | None,
) -> os.stat_result:
    try:
        opened = os.fstat(descriptor)
        anchored = path.stat(follow_symlinks=False)
    except OSError as exc:
        raise AccountDeletionWorkerError(
            "encrypted report object parent differs"
        ) from exc
    if (
        not stat.S_ISDIR(opened.st_mode)
        or opened.st_uid != os.geteuid()
        or (expected_gid is not None and opened.st_gid != expected_gid)
        or stat.S_IMODE(opened.st_mode) != expected_mode
        or _directory_binding(opened) != _directory_binding(anchored)
        or not _descriptor_acl_is_absent(descriptor)
    ):
        raise AccountDeletionWorkerError("encrypted report object parent differs")
    return opened


@contextmanager
def _validated_directory_descriptor(
    path: Path,
    *,
    expected_mode: int,
    expected_gid: int | None,
) -> Iterator[int]:
    try:
        descriptor = os.open(
            path,
            os.O_RDONLY
            | os.O_DIRECTORY
            | os.O_NOFOLLOW
            | getattr(os, "O_CLOEXEC", 0),
        )
    except OSError as exc:
        raise AccountDeletionWorkerError(
            "encrypted report object parent differs"
        ) from exc
    try:
        _assert_bound_directory(
            path,
            descriptor,
            expected_mode=expected_mode,
            expected_gid=expected_gid,
        )
        yield descriptor
    finally:
        os.close(descriptor)


def _assert_bound_object(
    path: Path,
    parent_descriptor: int,
    descriptor: int,
    opened: os.stat_result,
    *,
    sha256: str,
    expected_parent_mode: int,
    expected_parent_gid: int | None,
) -> None:
    _assert_bound_directory(
        path.parent,
        parent_descriptor,
        expected_mode=expected_parent_mode,
        expected_gid=expected_parent_gid,
    )
    try:
        current = os.fstat(descriptor)
        anchored = os.stat(
            path.name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
        os.lseek(descriptor, 0, os.SEEK_SET)
        current_sha256 = _sha256_descriptor(descriptor)
        current_after_read = os.fstat(descriptor)
        anchored_after_read = os.stat(
            path.name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
    except OSError as exc:
        raise AccountDeletionWorkerError(
            "encrypted report object identity differs"
        ) from exc
    if (
        _object_binding(current) != _object_binding(opened)
        or _object_binding(anchored) != _object_binding(opened)
        or _object_binding(current_after_read) != _object_binding(opened)
        or _object_binding(anchored_after_read) != _object_binding(opened)
        or not _descriptor_acl_is_absent(parent_descriptor)
        or not _descriptor_acl_is_absent(descriptor)
        or current_sha256 != sha256
    ):
        raise AccountDeletionWorkerError("encrypted report object identity differs")
    _assert_bound_directory(
        path.parent,
        parent_descriptor,
        expected_mode=expected_parent_mode,
        expected_gid=expected_parent_gid,
    )


@contextmanager
def _bound_validated_object(
    path: Path,
    *,
    size: int,
    sha256: str,
    expected_mode: int,
    expected_gid: int | None,
    expected_parent_mode: int,
    expected_parent_gid: int | None,
) -> Iterator[tuple[int, int, os.stat_result]]:
    with _validated_directory_descriptor(
        path.parent,
        expected_mode=expected_parent_mode,
        expected_gid=expected_parent_gid,
    ) as parent_descriptor:
        try:
            anchored = os.stat(
                path.name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
            descriptor = os.open(
                path.name,
                os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0),
                dir_fd=parent_descriptor,
            )
        except OSError as exc:
            raise AccountDeletionWorkerError(
                "encrypted report object identity differs"
            ) from exc
        try:
            opened = os.fstat(descriptor)
            if (
                not stat.S_ISREG(opened.st_mode)
                or opened.st_uid != os.geteuid()
                or (expected_gid is not None and opened.st_gid != expected_gid)
                or opened.st_nlink != 1
                or stat.S_IMODE(opened.st_mode) != expected_mode
                or opened.st_size != size
                or _object_binding(opened) != _object_binding(anchored)
            ):
                raise AccountDeletionWorkerError(
                    "encrypted report object identity differs"
                )
            _assert_bound_object(
                path,
                parent_descriptor,
                descriptor,
                opened,
                sha256=sha256,
                expected_parent_mode=expected_parent_mode,
                expected_parent_gid=expected_parent_gid,
            )
            yield parent_descriptor, descriptor, opened
        finally:
            os.close(descriptor)


def _validated_object(
    path: Path,
    *,
    size: int,
    sha256: str,
    expected_mode: int = 0o600,
    expected_gid: int | None = None,
    expected_parent_mode: int = 0o700,
    expected_parent_gid: int | None = None,
) -> Mapping[str, int]:
    with _bound_validated_object(
        path,
        size=size,
        sha256=sha256,
        expected_mode=expected_mode,
        expected_gid=expected_gid,
        expected_parent_mode=expected_parent_mode,
        expected_parent_gid=expected_parent_gid,
    ) as (_parent_descriptor, _descriptor, opened):
        return {
            "st_dev": opened.st_dev,
            "st_ino": opened.st_ino,
            "st_size": opened.st_size,
            "st_mtime_ns": opened.st_mtime_ns,
        }


def _change_object_mode(
    path: Path,
    *,
    size: int,
    sha256: str,
    expected_mode: int,
    target_mode: int,
    expected_gid: int | None,
    expected_parent_mode: int,
    expected_parent_gid: int | None,
) -> None:
    _validated_object(
        path,
        size=size,
        sha256=sha256,
        expected_mode=expected_mode,
        expected_gid=expected_gid,
        expected_parent_mode=expected_parent_mode,
        expected_parent_gid=expected_parent_gid,
    )
    with _bound_validated_object(
        path,
        size=size,
        sha256=sha256,
        expected_mode=expected_mode,
        expected_gid=expected_gid,
        expected_parent_mode=expected_parent_mode,
        expected_parent_gid=expected_parent_gid,
    ) as (parent_descriptor, descriptor, opened):
        _assert_bound_object(
            path,
            parent_descriptor,
            descriptor,
            opened,
            sha256=sha256,
            expected_parent_mode=expected_parent_mode,
            expected_parent_gid=expected_parent_gid,
        )
        os.fchmod(descriptor, target_mode)
        os.fsync(descriptor)
    _validated_object(
        path,
        size=size,
        sha256=sha256,
        expected_mode=target_mode,
        expected_gid=expected_gid,
        expected_parent_mode=expected_parent_mode,
        expected_parent_gid=expected_parent_gid,
    )


def _atomic_write(path: Path, payload: Mapping[str, object]) -> None:
    data = _canonical_json(payload)
    temporary = path.with_name(
        f".{path.name}.{os.getpid()}.{os.urandom(8).hex()}.tmp"
    )
    descriptor: int | None = None
    committed = False
    try:
        descriptor = os.open(
            temporary,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
            0o600,
        )
        view = memoryview(data)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise OSError(errno.EIO, "journal write made no progress")
            view = view[written:]
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = None
        os.replace(temporary, path)
        committed = True
        _fsync_directory(path.parent)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if not committed:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass


def _rename_noreplace_at(
    source: str | bytes | Path,
    target: str | bytes | Path,
    *,
    source_dir_fd: int = _AT_FDCWD,
    target_dir_fd: int = _AT_FDCWD,
) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        raise AccountDeletionWorkerError("atomic create-only rename is unavailable")
    renameat2.argtypes = (
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    )
    renameat2.restype = ctypes.c_int
    if renameat2(
        source_dir_fd,
        os.fsencode(source),
        target_dir_fd,
        os.fsencode(target),
        _RENAME_NOREPLACE,
    ) != 0:
        error_number = ctypes.get_errno()
        raise OSError(error_number, os.strerror(error_number), str(target))


def _rename_noreplace(source: Path, target: Path) -> None:
    _rename_noreplace_at(source, target)


def _rename_validated_object_noreplace(
    source: Path,
    target: Path,
    *,
    size: int,
    sha256: str,
    expected_mode: int,
    expected_gid: int | None,
    expected_parent_mode: int,
    expected_parent_gid: int | None,
    target_parent_mode: int,
    target_parent_gid: int | None,
) -> None:
    with _bound_validated_object(
        source,
        size=size,
        sha256=sha256,
        expected_mode=expected_mode,
        expected_gid=expected_gid,
        expected_parent_mode=expected_parent_mode,
        expected_parent_gid=expected_parent_gid,
    ) as (source_parent_descriptor, descriptor, opened):
        with _validated_directory_descriptor(
            target.parent,
            expected_mode=target_parent_mode,
            expected_gid=target_parent_gid,
        ) as target_parent_descriptor:
            _assert_bound_object(
                source,
                source_parent_descriptor,
                descriptor,
                opened,
                sha256=sha256,
                expected_parent_mode=expected_parent_mode,
                expected_parent_gid=expected_parent_gid,
            )
            _assert_bound_directory(
                target.parent,
                target_parent_descriptor,
                expected_mode=target_parent_mode,
                expected_gid=target_parent_gid,
            )
            try:
                _rename_noreplace_at(
                    source.name,
                    target.name,
                    source_dir_fd=source_parent_descriptor,
                    target_dir_fd=target_parent_descriptor,
                )
            except OSError as exc:
                raise AccountDeletionWorkerError(
                    "encrypted report object destination is not empty"
                ) from exc
            try:
                source_entry_missing = False
                try:
                    os.stat(
                        source.name,
                        dir_fd=source_parent_descriptor,
                        follow_symlinks=False,
                    )
                except FileNotFoundError:
                    source_entry_missing = True
                target_entry = os.stat(
                    target.name,
                    dir_fd=target_parent_descriptor,
                    follow_symlinks=False,
                )
                current = os.fstat(descriptor)
                os.lseek(descriptor, 0, os.SEEK_SET)
                current_sha256 = _sha256_descriptor(descriptor)
                current_after_read = os.fstat(descriptor)
                target_after_read = os.stat(
                    target.name,
                    dir_fd=target_parent_descriptor,
                    follow_symlinks=False,
                )
            except OSError as exc:
                raise AccountDeletionWorkerError(
                    "encrypted report object rename binding differs"
                ) from exc
            _assert_bound_directory(
                source.parent,
                source_parent_descriptor,
                expected_mode=expected_parent_mode,
                expected_gid=expected_parent_gid,
            )
            _assert_bound_directory(
                target.parent,
                target_parent_descriptor,
                expected_mode=target_parent_mode,
                expected_gid=target_parent_gid,
            )
            if (
                not source_entry_missing
                or _object_rename_binding(current)
                != _object_rename_binding(opened)
                or _object_binding(target_entry) != _object_binding(current)
                or _object_binding(current_after_read) != _object_binding(current)
                or _object_binding(target_after_read) != _object_binding(current)
                or not _descriptor_acl_is_absent(descriptor)
                or current_sha256 != sha256
            ):
                raise AccountDeletionWorkerError(
                    "encrypted report object rename binding differs"
                )


def _unlink_validated_object(
    path: Path,
    *,
    size: int,
    sha256: str,
    expected_mode: int,
    expected_gid: int | None,
    expected_parent_mode: int,
    expected_parent_gid: int | None,
) -> None:
    with _bound_validated_object(
        path,
        size=size,
        sha256=sha256,
        expected_mode=expected_mode,
        expected_gid=expected_gid,
        expected_parent_mode=expected_parent_mode,
        expected_parent_gid=expected_parent_gid,
    ) as (parent_descriptor, descriptor, opened):
        _assert_bound_object(
            path,
            parent_descriptor,
            descriptor,
            opened,
            sha256=sha256,
            expected_parent_mode=expected_parent_mode,
            expected_parent_gid=expected_parent_gid,
        )
        try:
            os.unlink(path.name, dir_fd=parent_descriptor)
        except OSError as exc:
            raise AccountDeletionWorkerError(
                "encrypted report object removal failed"
            ) from exc
        if os.fstat(descriptor).st_nlink != 0:
            raise AccountDeletionWorkerError(
                "encrypted report object removal was not bound"
            )


def _publish_manifest(path: Path, payload: Mapping[str, object]) -> tuple[bytes, str]:
    data = _canonical_json(payload)
    temporary = path.with_name(
        f".{path.name}.{os.getpid()}.{os.urandom(8).hex()}.publish"
    )
    descriptor: int | None = None
    published = False
    try:
        descriptor = os.open(
            temporary,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
            0o600,
        )
        os.fchmod(descriptor, 0o600)
        view = memoryview(data)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise OSError(errno.EIO, "manifest write made no progress")
            view = view[written:]
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = None
        try:
            _rename_noreplace(temporary, path)
            published = True
            _fsync_directory(path.parent)
        except OSError as error:
            if error.errno != errno.EEXIST:
                raise
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if not published:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass
    if not published:
        metadata = path.lstat()
        if (
            not stat.S_ISREG(metadata.st_mode)
            or path.is_symlink()
            or metadata.st_nlink != 1
            or not _owned_by_current_process(metadata.st_uid)
            or stat.S_IMODE(metadata.st_mode) != 0o600
            or metadata.st_size > 8 * 1024 * 1024
        ):
            raise AccountDeletionWorkerError("existing deletion manifest is unsafe")
        existing = path.read_bytes()
        if existing != data:
            raise AccountDeletionWorkerError("existing deletion manifest differs")
        return existing, hashlib.sha256(existing).hexdigest()
    return data, hashlib.sha256(data).hexdigest()


def _read_journal(path: Path) -> dict[str, object]:
    metadata = path.lstat()
    if (
        not stat.S_ISREG(metadata.st_mode)
        or path.is_symlink()
        or metadata.st_nlink != 1
        or not _owned_by_current_process(metadata.st_uid)
        or stat.S_IMODE(metadata.st_mode) != 0o600
        or metadata.st_size > 8 * 1024 * 1024
    ):
        raise AccountDeletionWorkerError("worker journal metadata is unsafe")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("schema_version") != JOURNAL_SCHEMA:
        raise AccountDeletionWorkerError("worker journal contract differs")
    request_id = value.get("request_id")
    if not isinstance(request_id, str) or REQUEST_ID.fullmatch(request_id) is None:
        raise AccountDeletionWorkerError("worker journal request id is invalid")
    if value.get("state") not in {"PREPARED", "DATABASE_COMMITTED"}:
        raise AccountDeletionWorkerError("worker journal state is invalid")
    objects = value.get("objects")
    if not isinstance(objects, list) or len(objects) > 10_000:
        raise AccountDeletionWorkerError("worker journal object inventory is invalid")
    return value


def _journal_paths(root: Path, request_id: str) -> tuple[Path, Path, Path]:
    digest = hashlib.sha256(request_id.encode("ascii")).hexdigest()
    return (
        root / "journals" / f"{digest}.json",
        root / "quarantine" / digest,
        root / "manifests" / f"{request_id}.json",
    )


def _prepare_roots(root: Path, *, create: bool = True) -> None:
    _secure_directory(root, create=create)
    for name in ("journals", "quarantine", "manifests"):
        _secure_directory(root / name, create=create)


def _safe_environment_file_metadata(metadata: os.stat_result) -> bool:
    return (
        stat.S_ISREG(metadata.st_mode)
        and metadata.st_uid == 0
        and metadata.st_gid == 0
        and metadata.st_nlink == 1
        and stat.S_IMODE(metadata.st_mode) == 0o600
    )


def _validated_operational_environment_file(path: Path) -> Path:
    if not path.is_absolute() or Path(os.path.abspath(path)) != path:
        raise AccountDeletionWorkerError("worker environment file path must be canonical and absolute")
    try:
        metadata = path.lstat()
    except OSError as exc:
        raise AccountDeletionWorkerError("worker environment file is unavailable") from exc
    if (
        path.is_symlink()
        or path.resolve(strict=True) != path
        or not _safe_environment_file_metadata(metadata)
    ):
        raise AccountDeletionWorkerError(
            "worker environment file must be root-owned root-group single-link mode 0600 regular file"
        )
    return path


def _forbidden_manual_environment_name(name: str) -> bool:
    return (
        name in FORBIDDEN_MANUAL_ENVIRONMENT_NAMES
        or name.startswith("WALKSAFE_ADMIN_")
        or (
            name.startswith("WALKSAFE_")
            and any(
                marker in name
                for marker in (
                    "_SECRET",
                    "_TOKEN",
                    "_KEY_FILE",
                    "_KEYRING",
                    "_PRIVATE_KEY",
                    "_SIGNING_KEY",
                )
            )
        )
    )


def _descriptor_acl_is_absent(descriptor: int) -> bool:
    try:
        return not any(
            "acl" in os.fsdecode(name).casefold()
            for name in os.listxattr(descriptor)
        )
    except (AttributeError, OSError):
        return False


def _trusted_shared_lock_parent(parent: Path, parent_descriptor: int) -> bool:
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
    authority_paths = [Path("/")]
    authority_descriptors: list[int] = []
    try:
        if parent.resolve(strict=True) != parent:
            return False
        authority_descriptors.append(os.open("/", flags))
        for component in parent.parent.relative_to("/").parts:
            authority_descriptors.append(
                os.open(component, flags, dir_fd=authority_descriptors[-1])
            )
            authority_paths.append(authority_paths[-1] / component)
        for index, (authority_path, descriptor) in enumerate(
            zip(authority_paths, authority_descriptors, strict=True)
        ):
            opened = os.fstat(descriptor)
            pathname = authority_path.stat(follow_symlinks=False)
            if (
                not stat.S_ISDIR(opened.st_mode)
                or opened.st_uid != 0
                or stat.S_IMODE(opened.st_mode) & 0o022
                or os.access(
                    ".",
                    os.W_OK,
                    dir_fd=descriptor,
                    effective_ids=True,
                    follow_symlinks=False,
                )
                or (pathname.st_dev, pathname.st_ino) != (opened.st_dev, opened.st_ino)
            ):
                return False
            if index:
                anchored = os.stat(
                    authority_path.name,
                    dir_fd=authority_descriptors[index - 1],
                    follow_symlinks=False,
                )
                if (anchored.st_dev, anchored.st_ino) != (opened.st_dev, opened.st_ino):
                    return False
        anchored_parent = os.stat(
            parent.name,
            dir_fd=authority_descriptors[-1],
            follow_symlinks=False,
        )
        opened_parent = os.fstat(parent_descriptor)
        return (anchored_parent.st_dev, anchored_parent.st_ino) == (
            opened_parent.st_dev,
            opened_parent.st_ino,
        )
    except (OSError, RuntimeError):
        return False
    finally:
        for descriptor in reversed(authority_descriptors):
            os.close(descriptor)


@contextmanager
def _maintenance_lock(
    path: Path,
    *,
    timeout_seconds: int,
    expected_group_gid: int | None = None,
) -> Iterator[None]:
    if not path.is_absolute() or Path(os.path.abspath(path)) != path:
        raise AccountDeletionWorkerError("maintenance lock path must be absolute")
    if not 1 <= timeout_seconds <= MAX_LOCK_TIMEOUT_SECONDS:
        raise AccountDeletionWorkerError("maintenance lock timeout must be between 1 and 300 seconds")
    parent = path.parent
    if parent.resolve(strict=True) != parent:
        raise AccountDeletionWorkerError("maintenance lock parent must be a canonical real directory")
    parent_descriptor = os.open(
        parent,
        os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0),
    )
    descriptor: int | None = None
    file_locked = False
    try:
        parent_metadata = os.fstat(parent_descriptor)
        path_parent_metadata = parent.stat(follow_symlinks=False)
        expected_parent_owner = 0 if expected_group_gid is not None else os.geteuid()
        expected_parent_mode = 0o750 if expected_group_gid is not None else 0o700
        if (
            not stat.S_ISDIR(parent_metadata.st_mode)
            or parent_metadata.st_uid != expected_parent_owner
            or (
                expected_group_gid is not None
                and parent_metadata.st_gid != expected_group_gid
            )
            or stat.S_IMODE(parent_metadata.st_mode) != expected_parent_mode
            or not _descriptor_acl_is_absent(parent_descriptor)
            or (
                expected_group_gid is not None
                and os.access(
                    ".",
                    os.W_OK,
                    dir_fd=parent_descriptor,
                    effective_ids=True,
                    follow_symlinks=False,
                )
            )
            or (parent_metadata.st_dev, parent_metadata.st_ino)
            != (path_parent_metadata.st_dev, path_parent_metadata.st_ino)
            or (
                expected_group_gid is not None
                and not _trusted_shared_lock_parent(parent, parent_descriptor)
            )
        ):
            raise AccountDeletionWorkerError("maintenance lock parent is unsafe")
        parent_state = (
            parent_metadata.st_dev,
            parent_metadata.st_ino,
            parent_metadata.st_mode,
            parent_metadata.st_uid,
            parent_metadata.st_gid,
            parent_metadata.st_nlink,
            parent_metadata.st_ctime_ns,
        )
        flags = os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
        try:
            descriptor = os.open(path.name, flags, dir_fd=parent_descriptor)
        except FileNotFoundError as exc:
            raise AccountDeletionWorkerError("maintenance lock must already exist") from exc
        expected_file_owner = 0 if expected_group_gid is not None else os.geteuid()
        expected_file_mode = 0o440 if expected_group_gid is not None else 0o600

        def verified_file_state() -> tuple[int, ...]:
            try:
                current_parent = os.fstat(parent_descriptor)
                current_path_parent = parent.stat(follow_symlinks=False)
                current_file = os.fstat(descriptor)
                current_entry = os.stat(
                    path.name,
                    dir_fd=parent_descriptor,
                    follow_symlinks=False,
                )
                current_path_file = path.stat(follow_symlinks=False)
            except OSError as exc:
                raise AccountDeletionWorkerError(
                    "maintenance lock path or parent changed"
                ) from exc
            current_parent_state = (
                current_parent.st_dev,
                current_parent.st_ino,
                current_parent.st_mode,
                current_parent.st_uid,
                current_parent.st_gid,
                current_parent.st_nlink,
                current_parent.st_ctime_ns,
            )
            current_file_state = (
                current_file.st_dev,
                current_file.st_ino,
                current_file.st_mode,
                current_file.st_uid,
                current_file.st_gid,
                current_file.st_nlink,
                current_file.st_ctime_ns,
            )
            if (
                current_parent_state != parent_state
                or (current_path_parent.st_dev, current_path_parent.st_ino)
                != (current_parent.st_dev, current_parent.st_ino)
                or (
                    expected_group_gid is not None
                    and not _trusted_shared_lock_parent(parent, parent_descriptor)
                )
                or not _descriptor_acl_is_absent(parent_descriptor)
                or not _descriptor_acl_is_absent(descriptor)
                or (
                    expected_group_gid is not None
                    and os.access(
                        ".",
                        os.W_OK,
                        dir_fd=parent_descriptor,
                        effective_ids=True,
                        follow_symlinks=False,
                    )
                )
                or not stat.S_ISREG(current_file.st_mode)
                or current_file.st_uid != expected_file_owner
                or (
                    expected_group_gid is not None
                    and current_file.st_gid != expected_group_gid
                )
                or stat.S_IMODE(current_file.st_mode) != expected_file_mode
                or current_file.st_nlink != 1
                or (current_entry.st_dev, current_entry.st_ino)
                != (current_file.st_dev, current_file.st_ino)
                or (current_path_file.st_dev, current_path_file.st_ino)
                != (current_file.st_dev, current_file.st_ino)
            ):
                raise AccountDeletionWorkerError("maintenance lock is unsafe")
            return current_file_state

        file_state = verified_file_state()
        deadline = time.monotonic() + timeout_seconds

        def acquire(descriptor_to_lock: int) -> None:
            while True:
                try:
                    fcntl.flock(descriptor_to_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    return
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise AccountDeletionWorkerError(
                            "maintenance lock acquisition timed out"
                        )
                    time.sleep(0.05)

        acquire(descriptor)
        file_locked = True
        if verified_file_state() != file_state:
            raise AccountDeletionWorkerError("maintenance lock identity changed")
        try:
            yield
        finally:
            try:
                if verified_file_state() != file_state:
                    raise AccountDeletionWorkerError("maintenance lock identity changed")
            finally:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
                file_locked = False
    finally:
        if file_locked and descriptor is not None:
            fcntl.flock(descriptor, fcntl.LOCK_UN)
        if descriptor is not None:
            os.close(descriptor)
        os.close(parent_descriptor)


def _validated_worker_database_url(
    raw: str,
    runtime_raw: str | None,
    *,
    deployment: bool = False,
) -> str:
    value = raw.strip()
    if not value:
        raise AccountDeletionWorkerError("WALKSAFE_ACCOUNT_DELETION_DATABASE_URL is required")
    try:
        worker = make_url(value)
    except Exception as exc:
        raise AccountDeletionWorkerError("worker database URL is invalid") from exc
    if worker.drivername != "postgresql+psycopg" or not worker.database or not worker.username:
        raise AccountDeletionWorkerError("worker database URL must use postgresql+psycopg")
    if runtime_raw and make_url(runtime_raw.strip()) == worker:
        raise AccountDeletionWorkerError("worker and runtime database URLs must differ")
    if deployment:
        try:
            _validate_deployment_database_transport(value)
        except ValueError as exc:
            raise AccountDeletionWorkerError(
                "worker deployment database transport is unsafe"
            ) from exc
    else:
        host = worker.host or ""
        if host not in {"127.0.0.1", "localhost", "::1"}:
            raise AccountDeletionWorkerError("local isolated worker database must be loopback")
    return value


def _object_inventory(
    db: Session,
    request: AccountDeletionRequest,
    upload_dir: Path,
    quarantine_dir: Path,
    *,
    upload_group_gid: int | None,
) -> tuple[list[Report], list[dict[str, object]]]:
    reports = db.scalars(
        select(Report)
        .where(
            Report.privacy_subject_hmac == request.privacy_subject_hmac,
            Report.account_generation == request.account_generation,
        )
        .order_by(Report.id)
    ).all()
    if len(reports) > 10_000:
        raise AccountDeletionWorkerError("one request exceeds the local report ceiling")
    report_ids = [report.id for report in reports]
    image_objects = (
        db.scalars(
            select(ReportImageObject)
            .where(ReportImageObject.report_id.in_(report_ids))
            .order_by(ReportImageObject.report_id)
        ).all()
        if report_ids
        else []
    )
    by_report = {image.report_id: image for image in image_objects}
    if set(by_report) != set(report_ids):
        raise AccountDeletionWorkerError("report image inventory is incomplete")
    objects: list[dict[str, object]] = []
    for report in reports:
        image = by_report[report.id]
        if (
            STORAGE_NAME.fullmatch(image.storage_name) is None
            or report.image_path != f"/uploads/{image.storage_name}"
            or SHA256.fullmatch(image.envelope_sha256) is None
        ):
            raise AccountDeletionWorkerError("report storage binding is invalid")
        source = upload_dir / image.storage_name
        quarantine = quarantine_dir / image.storage_name
        source_mode = 0o640 if upload_group_gid is not None else 0o600
        source_parent_mode = 0o2750 if upload_group_gid is not None else 0o700
        identity = _validated_object(
            source,
            size=image.envelope_size,
            sha256=image.envelope_sha256,
            expected_mode=source_mode,
            expected_gid=upload_group_gid,
            expected_parent_mode=source_parent_mode,
            expected_parent_gid=upload_group_gid,
        )
        objects.append(
            {
                "report_id": str(report.id),
                "storage_name": image.storage_name,
                "envelope_sha256": image.envelope_sha256,
                "envelope_size": image.envelope_size,
                "source_path": str(source),
                "quarantine_path": str(quarantine),
                "source_identity": dict(identity),
                "source_mode": source_mode,
                "source_gid": upload_group_gid,
                "source_parent_mode": source_parent_mode,
            }
        )
    return reports, objects


def _entry_exists(path: Path) -> bool:
    try:
        path.lstat()
    except FileNotFoundError:
        return False
    except OSError as exc:
        raise AccountDeletionWorkerError(
            "encrypted report object location is unreadable"
        ) from exc
    return True


def _move_to_quarantine(objects: list[dict[str, object]]) -> None:
    for item in objects:
        source = Path(str(item["source_path"]))
        quarantine = Path(str(item["quarantine_path"]))
        source_mode = int(item.get("source_mode", 0o600))
        source_gid_value = item.get("source_gid")
        source_gid = int(source_gid_value) if source_gid_value is not None else None
        source_parent_mode = int(item.get("source_parent_mode", 0o700))
        _validated_object(
            source,
            size=int(item["envelope_size"]),
            sha256=str(item["envelope_sha256"]),
            expected_mode=source_mode,
            expected_gid=source_gid,
            expected_parent_mode=source_parent_mode,
            expected_parent_gid=source_gid,
        )
        _rename_validated_object_noreplace(
            source,
            quarantine,
            size=int(item["envelope_size"]),
            sha256=str(item["envelope_sha256"]),
            expected_mode=source_mode,
            expected_gid=source_gid,
            expected_parent_mode=source_parent_mode,
            expected_parent_gid=source_gid,
            target_parent_mode=0o700,
            target_parent_gid=None,
        )
        try:
            _change_object_mode(
                quarantine,
                size=int(item["envelope_size"]),
                sha256=str(item["envelope_sha256"]),
                expected_mode=source_mode,
                target_mode=0o600,
                expected_gid=source_gid,
                expected_parent_mode=0o700,
                expected_parent_gid=None,
            )
        except BaseException:
            try:
                _validated_object(
                    quarantine,
                    size=int(item["envelope_size"]),
                    sha256=str(item["envelope_sha256"]),
                    expected_mode=source_mode,
                    expected_gid=source_gid,
                    expected_parent_mode=0o700,
                    expected_parent_gid=None,
                )
            except AccountDeletionWorkerError:
                pass
            else:
                _rename_validated_object_noreplace(
                    quarantine,
                    source,
                    size=int(item["envelope_size"]),
                    sha256=str(item["envelope_sha256"]),
                    expected_mode=source_mode,
                    expected_gid=source_gid,
                    expected_parent_mode=0o700,
                    expected_parent_gid=None,
                    target_parent_mode=source_parent_mode,
                    target_parent_gid=source_gid,
                )
                _fsync_directory(source.parent)
                _fsync_directory(quarantine.parent)
            raise
        _fsync_directory(source.parent)
        _fsync_directory(quarantine.parent)


def _restore_precommit(objects: list[dict[str, object]]) -> None:
    for item in objects:
        source = Path(str(item["source_path"]))
        quarantine = Path(str(item["quarantine_path"]))
        source_mode = int(item.get("source_mode", 0o600))
        source_gid_value = item.get("source_gid")
        source_gid = int(source_gid_value) if source_gid_value is not None else None
        source_parent_mode = int(item.get("source_parent_mode", 0o700))
        quarantine_exists = _entry_exists(quarantine)
        source_exists = _entry_exists(source)
        if quarantine_exists and not source_exists:
            quarantine_mode = source_mode
            try:
                _validated_object(
                    quarantine,
                    size=int(item["envelope_size"]),
                    sha256=str(item["envelope_sha256"]),
                    expected_mode=source_mode,
                    expected_gid=source_gid,
                    expected_parent_mode=0o700,
                    expected_parent_gid=None,
                )
            except AccountDeletionWorkerError:
                if source_mode == 0o600:
                    raise
                try:
                    _validated_object(
                        quarantine,
                        size=int(item["envelope_size"]),
                        sha256=str(item["envelope_sha256"]),
                        expected_mode=0o600,
                        expected_gid=source_gid,
                        expected_parent_mode=0o700,
                        expected_parent_gid=None,
                    )
                except AccountDeletionWorkerError as private_mode_error:
                    raise AccountDeletionWorkerError(
                        "precommit quarantine mode or identity differs"
                    ) from private_mode_error
                quarantine_mode = 0o600
            if quarantine_mode != source_mode:
                _change_object_mode(
                    quarantine,
                    size=int(item["envelope_size"]),
                    sha256=str(item["envelope_sha256"]),
                    expected_mode=quarantine_mode,
                    target_mode=source_mode,
                    expected_gid=source_gid,
                    expected_parent_mode=0o700,
                    expected_parent_gid=None,
                )
            _rename_validated_object_noreplace(
                quarantine,
                source,
                size=int(item["envelope_size"]),
                sha256=str(item["envelope_sha256"]),
                expected_mode=source_mode,
                expected_gid=source_gid,
                expected_parent_mode=0o700,
                expected_parent_gid=None,
                target_parent_mode=source_parent_mode,
                target_parent_gid=source_gid,
            )
            _validated_object(
                source,
                size=int(item["envelope_size"]),
                sha256=str(item["envelope_sha256"]),
                expected_mode=source_mode,
                expected_gid=source_gid,
                expected_parent_mode=source_parent_mode,
                expected_parent_gid=source_gid,
            )
            _fsync_directory(source.parent)
            _fsync_directory(quarantine.parent)
        elif source_exists and not quarantine_exists:
            _validated_object(
                source,
                size=int(item["envelope_size"]),
                sha256=str(item["envelope_sha256"]),
                expected_mode=source_mode,
                expected_gid=source_gid,
                expected_parent_mode=source_parent_mode,
                expected_parent_gid=source_gid,
            )
            _fsync_directory(source.parent)
            _fsync_directory(quarantine.parent)
        else:
            raise AccountDeletionWorkerError("precommit object recovery is ambiguous")


def _remove_committed_objects(objects: list[dict[str, object]]) -> None:
    for item in objects:
        source = Path(str(item["source_path"]))
        quarantine = Path(str(item["quarantine_path"]))
        source_mode = int(item.get("source_mode", 0o600))
        source_gid_value = item.get("source_gid")
        source_gid = int(source_gid_value) if source_gid_value is not None else None
        source_parent_mode = int(item.get("source_parent_mode", 0o700))
        existing = [
            candidate
            for candidate in (source, quarantine)
            if _entry_exists(candidate)
        ]
        if len(existing) > 1:
            raise AccountDeletionWorkerError("postcommit object locations are ambiguous")
        if existing:
            candidate = existing[0]
            _unlink_validated_object(
                candidate,
                size=int(item["envelope_size"]),
                sha256=str(item["envelope_sha256"]),
                expected_mode=source_mode if candidate == source else 0o600,
                expected_gid=source_gid,
                expected_parent_mode=(
                    source_parent_mode if candidate == source else 0o700
                ),
                expected_parent_gid=source_gid if candidate == source else None,
            )
        _fsync_directory(source.parent)
        _fsync_directory(quarantine.parent)


def _request_report_count(db: Session, journal: Mapping[str, object]) -> int:
    return int(
        db.scalar(
            select(func.count(Report.id)).where(
                Report.privacy_subject_hmac == str(journal["privacy_subject_hmac"]),
                Report.account_generation == int(journal["account_generation"]),
            )
        )
        or 0
    )


def _final_manifest(journal: Mapping[str, object]) -> dict[str, object]:
    objects = [
        {
            "envelope_sha256": str(item["envelope_sha256"]),
            "envelope_size": int(item["envelope_size"]),
            "report_id": str(item["report_id"]),
            "storage_name": str(item["storage_name"]),
        }
        for item in journal["objects"]  # type: ignore[index]
    ]
    return {
        "schema_version": MANIFEST_SCHEMA,
        "request_id": str(journal["request_id"]),
        "privacy_subject_hmac": str(journal["privacy_subject_hmac"]),
        "account_generation": int(journal["account_generation"]),
        "deleted_at": str(journal["deleted_at"]),
        "report_count": int(journal["report_count"]),
        "objects": objects,
        "server_inventory": {
            "report_records": "COMPLETED",
            "server_copies": "NOT_APPLICABLE",
            "server_originals": "COMPLETED",
            "server_quarantine": "NOT_APPLICABLE",
        },
    }


def _finish_committed_journal(
    SessionFactory: sessionmaker[Session],
    root: Path,
    journal_path: Path,
    journal: dict[str, object],
) -> str:
    objects = journal["objects"]
    assert isinstance(objects, list)
    _remove_committed_objects(objects)
    with SessionFactory() as db:
        assert_account_deletion_worker_database_role(db)
        if _request_report_count(db, journal) != 0:
            raise AccountDeletionWorkerError("server report inventory is not empty after commit")
        tombstone_count = db.scalar(
            select(func.count(AccountDeletionTombstone.tombstone_id)).where(
                AccountDeletionTombstone.privacy_subject_hmac
                == str(journal["privacy_subject_hmac"]),
                AccountDeletionTombstone.account_generation
                == int(journal["account_generation"]),
            )
        )
        item_count = db.scalar(
            select(func.count(AccountDeletionItem.item_key)).where(
                AccountDeletionItem.request_id == str(journal["request_id"])
            )
        )
        if tombstone_count != 1 or item_count != 9:
            raise AccountDeletionWorkerError("retained deletion ledger is incomplete")
        db.rollback()
    _journal_path, quarantine_dir, manifest_path = _journal_paths(
        root, str(journal["request_id"])
    )
    _manifest_bytes, manifest_sha256 = _publish_manifest(
        manifest_path,
        _final_manifest(journal),
    )
    with SessionFactory() as db:
        status = complete_server_deletion_inventory_from_manifest(
            db,
            request_id=str(journal["request_id"]),
            manifest_sha256=manifest_sha256,
            terminal_at=_parse_time(journal["deleted_at"]),
        )
        completed = status.overall_status == "COMPLETED"
        has_receipt = status.completion_receipt_sha256 is not None
        if completed != has_receipt:
            raise AccountDeletionWorkerError("server deletion receipt state is inconsistent")
        consent_count = db.scalar(
            select(func.count(PrivacyConsentEvent.id)).where(
                PrivacyConsentEvent.privacy_subject_hmac
                == str(journal["privacy_subject_hmac"]),
                PrivacyConsentEvent.account_generation
                == int(journal["account_generation"]),
            )
        )
        if consent_count is None:
            raise AccountDeletionWorkerError("privacy consent ledger verification failed")
        db.rollback()
    journal_path.unlink()
    _fsync_directory(journal_path.parent)
    try:
        quarantine_dir.rmdir()
        _fsync_directory(quarantine_dir.parent)
    except FileNotFoundError:
        pass
    return manifest_sha256


def reconcile_journal(
    SessionFactory: sessionmaker[Session],
    root: Path,
    journal_path: Path,
) -> str:
    journal = _read_journal(journal_path)
    objects = journal["objects"]
    assert isinstance(objects, list)
    with SessionFactory() as db:
        assert_account_deletion_worker_database_role(db)
        report_count = _request_report_count(db, journal)
        db.rollback()
    if report_count:
        if report_count != int(journal["report_count"]) or journal["state"] != "PREPARED":
            raise AccountDeletionWorkerError("partial database deletion cannot be reconciled")
        _restore_precommit(objects)
        journal_path.unlink()
        _fsync_directory(journal_path.parent)
        _journal_path, quarantine_dir, _manifest = _journal_paths(
            root, str(journal["request_id"])
        )
        try:
            quarantine_dir.rmdir()
            _fsync_directory(quarantine_dir.parent)
        except FileNotFoundError:
            pass
        return "RESTORED"
    if journal["state"] == "PREPARED":
        journal["state"] = "DATABASE_COMMITTED"
        journal["deleted_at"] = _iso(datetime.now(UTC))
        _atomic_write(journal_path, journal)
    return _finish_committed_journal(SessionFactory, root, journal_path, journal)


def process_request(
    SessionFactory: sessionmaker[Session],
    *,
    request_id: str,
    upload_dir: Path,
    upload_group_gid: int | None = None,
    root: Path,
    fault: Callable[[str], None] | None = None,
) -> str:
    journal_path, quarantine_dir, _manifest_path = _journal_paths(root, request_id)
    if journal_path.exists():
        return reconcile_journal(SessionFactory, root, journal_path)
    _secure_directory(quarantine_dir, create=True)
    committed = False
    journal: dict[str, object] | None = None
    try:
        with SessionFactory() as db:
            assert_account_deletion_worker_database_role(db)
            request = db.scalar(
                select(AccountDeletionRequest)
                .where(AccountDeletionRequest.request_id == request_id)
                .with_for_update()
            )
            if request is None:
                raise AccountDeletionWorkerError("deletion request does not exist")
            lock_report_deletion_transaction(
                db,
                request.privacy_subject_hmac,
                request.account_generation,
            )
            lock_report_storage_reconciliation_transaction(db)
            reports, objects = _object_inventory(
                db,
                request,
                upload_dir,
                quarantine_dir,
                upload_group_gid=upload_group_gid,
            )
            journal = {
                "schema_version": JOURNAL_SCHEMA,
                "request_id": request.request_id,
                "privacy_subject_hmac": request.privacy_subject_hmac,
                "account_generation": request.account_generation,
                "state": "PREPARED",
                "prepared_at": _iso(datetime.now(UTC)),
                "deleted_at": None,
                "report_count": len(reports),
                "objects": objects,
            }
            _atomic_write(journal_path, journal)
            _move_to_quarantine(objects)
            if fault is not None:
                fault("after_quarantine")
            if reports:
                db.execute(delete(Report).where(Report.id.in_([report.id for report in reports])))
            if _request_report_count(db, journal) != 0:
                raise AccountDeletionWorkerError("database report deletion did not reach zero")
            if fault is not None:
                fault("before_database_commit")
            db.commit()
            committed = True
        if fault is not None:
            fault("after_database_commit")
        assert journal is not None
        journal["state"] = "DATABASE_COMMITTED"
        journal["deleted_at"] = _iso(datetime.now(UTC))
        _atomic_write(journal_path, journal)
        if fault is not None:
            fault("before_object_removal")
        return _finish_committed_journal(SessionFactory, root, journal_path, journal)
    except Exception:
        if not committed and journal is not None:
            _restore_precommit(journal["objects"])  # type: ignore[arg-type]
            try:
                journal_path.unlink()
                _fsync_directory(journal_path.parent)
            except FileNotFoundError:
                pass
            try:
                quarantine_dir.rmdir()
                _fsync_directory(quarantine_dir.parent)
            except FileNotFoundError:
                pass
        raise


def _pending_request_ids(
    SessionFactory: sessionmaker[Session],
    batch_size: int,
) -> list[str]:
    with SessionFactory() as db:
        assert_account_deletion_worker_database_role(db)
        request_ids = db.scalars(
            select(AccountDeletionRequest.request_id)
            .join(AccountDeletionItem)
            .where(
                AccountDeletionItem.item_key.in_(
                    ("server_originals", "server_quarantine", "server_copies", "report_records")
                ),
                AccountDeletionItem.state.not_in(TERMINAL_ITEM_STATES),
            )
            .distinct()
            .order_by(AccountDeletionRequest.request_id)
            .limit(batch_size)
        ).all()
        db.rollback()
        return list(request_ids)


def run_once(
    *,
    database_url: str,
    upload_dir: Path,
    journal_root: Path,
    maintenance_lock_path: Path,
    maintenance_lock_group_gid: int | None = None,
    upload_backup_reader_group_gid: int | None = None,
    batch_size: int,
    lock_timeout_seconds: int = 30,
    create_runtime_paths: bool = True,
) -> list[str]:
    if not 1 <= batch_size <= MAX_BATCH_SIZE:
        raise AccountDeletionWorkerError("batch size must be between 1 and 100")
    if not 1 <= lock_timeout_seconds <= MAX_LOCK_TIMEOUT_SECONDS:
        raise AccountDeletionWorkerError("maintenance lock timeout must be between 1 and 300 seconds")
    upload_dir = _upload_directory(
        upload_dir,
        expected_group_gid=upload_backup_reader_group_gid,
    )
    _prepare_roots(journal_root, create=create_runtime_paths)
    if upload_dir.stat().st_dev != journal_root.stat().st_dev:
        raise AccountDeletionWorkerError("upload and worker journal directories must share a filesystem")
    engine = create_engine(database_url, pool_pre_ping=True)
    SessionFactory = sessionmaker(engine, expire_on_commit=False)
    completed: list[str] = []
    try:
        with _maintenance_lock(
            maintenance_lock_path,
            timeout_seconds=lock_timeout_seconds,
            expected_group_gid=maintenance_lock_group_gid,
        ):
            with SessionFactory() as db:
                assert_account_deletion_worker_database_role(db)
                db.rollback()
            for journal_path in sorted((journal_root / "journals").glob("*.json")):
                completed.append(reconcile_journal(SessionFactory, journal_root, journal_path))
            for request_id in _pending_request_ids(SessionFactory, batch_size):
                completed.append(
                    process_request(
                        SessionFactory,
                        request_id=request_id,
                        upload_dir=upload_dir,
                        upload_group_gid=upload_backup_reader_group_gid,
                        root=journal_root,
                    )
                )
    finally:
        engine.dispose()
    return completed


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--local-isolated", action="store_true")
    mode.add_argument("--manual-one-shot", action="store_true")
    parser.add_argument("--environment-file", type=Path)
    parser.add_argument("--upload-dir", type=Path, required=True)
    parser.add_argument("--journal-root", type=Path, required=True)
    parser.add_argument("--maintenance-lock-path", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=10)
    parser.add_argument("--lock-timeout-seconds", type=int, default=30)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        environment = os.environ.get("WALKSAFE_ENVIRONMENT", "").strip().lower()
        if args.local_isolated:
            if environment not in {"development", "test"}:
                raise AccountDeletionWorkerError(
                    "local isolated mode requires a development or test environment"
                )
            runtime_url = os.environ.get("DATABASE_URL")
            create_runtime_paths = True
            maintenance_lock_group_gid = None
            upload_backup_reader_group_gid = None
        else:
            if environment not in {"staging", "production"}:
                raise AccountDeletionWorkerError(
                    "manual one-shot mode requires a staging or production environment"
                )
            if os.geteuid() == 0:
                raise AccountDeletionWorkerError("manual one-shot mode must not run as root")
            if INVOCATION_ID.fullmatch(os.environ.get("INVOCATION_ID", "")) is None:
                raise AccountDeletionWorkerError("manual one-shot mode requires systemd invocation authority")
            if (
                os.environ.get("WALKSAFE_ACCOUNT_DELETION_TOPOLOGY", "").strip()
                != SINGLE_HOST_MARKER
            ):
                raise AccountDeletionWorkerError("manual one-shot topology marker differs")
            if args.environment_file is None:
                raise AccountDeletionWorkerError("manual one-shot environment file is required")
            _validated_operational_environment_file(args.environment_file)
            if any(
                value.strip() and _forbidden_manual_environment_name(name)
                for name, value in os.environ.items()
            ):
                raise AccountDeletionWorkerError(
                    "unrelated runtime, migration, administrator, or key credentials must not enter the worker"
                )
            runtime_url = None
            create_runtime_paths = False
            maintenance_lock_group = os.environ.get(
                "WALKSAFE_MAINTENANCE_LOCK_GROUP",
                "",
            ).strip()
            if not maintenance_lock_group:
                raise AccountDeletionWorkerError(
                    "WALKSAFE_MAINTENANCE_LOCK_GROUP is required"
                )
            try:
                maintenance_lock_group_gid = grp.getgrnam(
                    maintenance_lock_group
                ).gr_gid
            except KeyError as exc:
                raise AccountDeletionWorkerError(
                    "WALKSAFE_MAINTENANCE_LOCK_GROUP does not exist"
                ) from exc
            if maintenance_lock_group_gid == 0:
                raise AccountDeletionWorkerError(
                    "WALKSAFE_MAINTENANCE_LOCK_GROUP must not be root"
                )
            upload_backup_reader_group = os.environ.get(
                "WALKSAFE_UPLOAD_BACKUP_READER_GROUP",
                "",
            ).strip()
            if not upload_backup_reader_group:
                raise AccountDeletionWorkerError(
                    "WALKSAFE_UPLOAD_BACKUP_READER_GROUP is required"
                )
            try:
                upload_group = grp.getgrnam(upload_backup_reader_group)
            except KeyError as exc:
                raise AccountDeletionWorkerError(
                    "WALKSAFE_UPLOAD_BACKUP_READER_GROUP does not exist"
                ) from exc
            upload_backup_reader_group_gid = upload_group.gr_gid
            if upload_backup_reader_group_gid == 0:
                raise AccountDeletionWorkerError(
                    "WALKSAFE_UPLOAD_BACKUP_READER_GROUP must not be root"
                )
        database_url = _validated_worker_database_url(
            os.environ.get("WALKSAFE_ACCOUNT_DELETION_DATABASE_URL", ""),
            runtime_url,
            deployment=args.manual_one_shot,
        )
        results = run_once(
            database_url=database_url,
            upload_dir=args.upload_dir,
            journal_root=args.journal_root,
            maintenance_lock_path=args.maintenance_lock_path,
            maintenance_lock_group_gid=maintenance_lock_group_gid,
            upload_backup_reader_group_gid=upload_backup_reader_group_gid,
            batch_size=args.batch_size,
            lock_timeout_seconds=args.lock_timeout_seconds,
            create_runtime_paths=create_runtime_paths,
        )
    except Exception:
        print(json.dumps({"processed": 0, "status": "failed"}, sort_keys=True), file=sys.stderr)
        return 1
    print(json.dumps({"processed": len(results)}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
