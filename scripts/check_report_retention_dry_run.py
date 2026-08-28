#!/usr/bin/env python3
"""Plan or explicitly apply report retention with an auditable image quarantine."""

from __future__ import annotations

import argparse
import base64
from contextlib import contextmanager, nullcontext
import ctypes
from datetime import UTC, datetime, timedelta
import errno
import fcntl
import grp
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import stat
import sys
import threading
import time
from typing import Any, Callable
from urllib.parse import parse_qsl, unquote, urlsplit
import uuid


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scripts.walksafe_environment_identity import (  # noqa: E402
    database_identity_sha256,
    path_identity_sha256,
    private_restore_output_path,
    sqlalchemy_psycopg_url,
)
from scripts.walksafe_backup_integrity import (  # noqa: E402
    verify_signed_backup_manifest,
    verify_signed_json_document_with_digest,
)
from scripts.walksafe_admin_high_risk_gate import (  # noqa: E402
    walksafe_admin_high_risk_operation,
)

RETENTION_DAYS = {
    "fake_demo": 30,
    "active": 180,
    "resolved": 180,
}
APPLY_CONFIRMATION = "DELETE-EXPIRED-REPORTS"
ACTOR_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
BACKUP_MAX_AGE = timedelta(hours=26)
RESTORE_RECEIPT_SCHEMA = "walksafe.restore-drill.v1"
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
DEFAULT_APPLY_BATCH_SIZE = 500
MAX_APPLY_BATCH_SIZE = 5_000
DEFAULT_DATABASE_CONNECT_TIMEOUT_SECONDS = 5
MAX_DATABASE_CONNECT_TIMEOUT_SECONDS = 30
DEFAULT_DATABASE_STATEMENT_TIMEOUT_MS = 10_000
MAX_DATABASE_STATEMENT_TIMEOUT_MS = 120_000
FILE_IDENTITY_FIELDS = (
    "st_dev",
    "st_ino",
    "st_uid",
    "st_mode",
    "st_size",
    "st_mtime_ns",
    "st_ctime_ns",
    "st_nlink",
)
TERMINATION_SIGNALS = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)
RETENTION_RECOVERY_SCHEMA = "walksafe.report-retention-recovery.v2"
RETENTION_RECOVERY_MANIFEST_NAME = "manifest.json"
RETENTION_RECOVERY_TEMP_NAME = ".manifest.json.tmp"
RETENTION_RECOVERY_STATES = frozenset(
    {
        "PREPARING",
        "PRECOMMIT_READY",
        "DATABASE_COMMITTING",
        "RECONCILED_PRECOMMIT_ABORTED",
        "RECONCILED_POSTCOMMIT_COMPLETED",
    }
)
MAX_RETENTION_RECOVERY_RUNS = 10_000
MAX_RETENTION_RECOVERY_MANIFEST_BYTES = 8 * 1024 * 1024
MAX_ENCRYPTED_REPORT_OBJECT_BYTES = 32 * 1024 * 1024 + 4_096 + 8 + 4 + 16
ENCRYPTED_STORAGE_NAME_PATTERN = re.compile(
    r"^(?P<report_id>[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\.wse$"
)
RETENTION_RECOVERY_DATABASE_FIELDS = (
    "report_id",
    "report_created_at",
    "storage_name",
    "envelope_version",
    "algorithm",
    "aad_version",
    "key_id",
    "nonce_b64url",
    "plaintext_sha256",
    "plaintext_size",
    "envelope_sha256",
    "envelope_size",
    "content_type",
)
RETENTION_RECOVERY_OBJECT_FIELDS = frozenset(
    {
        *RETENTION_RECOVERY_DATABASE_FIELDS,
        "source_identity",
        "recovery_name",
        "recovery_identity",
        "recovery_sha256",
    }
)


class DeferredTerminationSignal(BaseException):
    def __init__(self, signal_number: int) -> None:
        super().__init__(f"deferred termination signal: {signal_number}")
        self.signal_number = signal_number


class RetentionSignalGuard:
    def __init__(self) -> None:
        self.installed = False
        self.first_signal_number: int | None = None
        self._cleanup_mode = False
        self._previous_handlers: dict[signal.Signals, Any] = {}

    def _defer(self, signal_number: int, _frame: Any) -> None:
        if self.first_signal_number is None:
            self.first_signal_number = signal_number
            if not self._cleanup_mode:
                raise DeferredTerminationSignal(signal_number)

    def install(self) -> None:
        if self.installed:
            raise RuntimeError("retention signal guard is already installed")
        if not hasattr(signal, "pthread_sigmask"):
            raise RuntimeError("retention signal guard requires pthread_sigmask")
        if threading.current_thread() is not threading.main_thread() or threading.active_count() != 1:
            raise RuntimeError("retention signal guard requires one main Python thread")
        previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, TERMINATION_SIGNALS)
        try:
            for signal_number in TERMINATION_SIGNALS:
                self._previous_handlers[signal_number] = signal.signal(
                    signal_number,
                    self._defer,
                )
            self.installed = True
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
        except BaseException:
            signal.pthread_sigmask(signal.SIG_BLOCK, TERMINATION_SIGNALS)
            for signal_number, previous_handler in self._previous_handlers.items():
                signal.signal(signal_number, previous_handler)
            self._previous_handlers.clear()
            self.installed = False
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
            raise

    @contextmanager
    def blocked(self) -> Any:
        previous_mask = self.block()
        try:
            yield
        except BaseException:
            self._cleanup_mode = True
            raise
        finally:
            self.restore_mask(previous_mask)

    def begin_cleanup(self) -> None:
        if self._cleanup_mode:
            return
        previous_mask = self.block()
        try:
            self._cleanup_mode = True
        finally:
            self.restore_mask(previous_mask)

    def block(self) -> set[signal.Signals]:
        if not self.installed:
            raise RuntimeError("retention signal guard is not installed")
        return signal.pthread_sigmask(signal.SIG_BLOCK, TERMINATION_SIGNALS)

    @staticmethod
    def restore_mask(previous_mask: set[signal.Signals]) -> None:
        signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)

    def close(self) -> None:
        if not self.installed:
            return
        previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, TERMINATION_SIGNALS)
        try:
            for signal_number, previous_handler in self._previous_handlers.items():
                signal.signal(signal_number, previous_handler)
            self._previous_handlers.clear()
            self._cleanup_mode = False
            self.installed = False
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)


def parse_datetime(value: str) -> datetime:
    normalized = value.replace("Z", "+00:00")
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def validated_as_of(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() != timedelta(0):
        raise ValueError("retention as-of must be timezone-aware UTC")
    return value.astimezone(UTC)


def parse_as_of(value: str) -> datetime:
    normalized = value.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise ValueError("retention as-of must be a valid timezone-aware UTC timestamp") from exc
    return validated_as_of(parsed)


def validated_actor_id(value: str) -> str:
    actor_id = value.strip()
    if ACTOR_ID_PATTERN.fullmatch(actor_id) is None:
        raise ValueError("actor id must start with an alphanumeric character and contain only [A-Za-z0-9._@-]")
    return actor_id


def _validated_bounded_environment_integer(
    name: str,
    *,
    default: int,
    maximum: int,
) -> int:
    raw_value = os.getenv(name, str(default))
    if re.fullmatch(r"[0-9]+", raw_value) is None:
        raise ValueError(f"{name} must be a positive integer")
    value = int(raw_value)
    if not 1 <= value <= maximum:
        raise ValueError(f"{name} must be between 1 and {maximum}")
    return value


def retention_database_timeouts() -> tuple[int, int]:
    return (
        _validated_bounded_environment_integer(
            "DATABASE_CONNECT_TIMEOUT_SECONDS",
            default=DEFAULT_DATABASE_CONNECT_TIMEOUT_SECONDS,
            maximum=MAX_DATABASE_CONNECT_TIMEOUT_SECONDS,
        ),
        _validated_bounded_environment_integer(
            "DATABASE_STATEMENT_TIMEOUT_MS",
            default=DEFAULT_DATABASE_STATEMENT_TIMEOUT_MS,
            maximum=MAX_DATABASE_STATEMENT_TIMEOUT_MS,
        ),
    )


def _loopback_database_host(host: str) -> bool:
    if host.casefold() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def validated_retention_database_url(database_url: str) -> str:
    environment = os.getenv("WALKSAFE_ENVIRONMENT", "").strip().lower()
    parsed = urlsplit(database_url.strip())
    try:
        port = parsed.port
    except ValueError as exc:
        raise ValueError("retention DATABASE_URL port is invalid") from exc
    raw_host = parsed.hostname or ""
    decoded_host = unquote(raw_host)
    username = unquote(parsed.username or "")
    password = unquote(parsed.password or "")
    database_name = unquote(parsed.path.lstrip("/"))
    query = parse_qsl(parsed.query, keep_blank_values=True)
    query_names = [name.casefold() for name, _value in query]
    parameters = {name.casefold(): value.casefold() for name, value in query}
    placeholder_values = (username, password, decoded_host, database_name)
    if (
        parsed.scheme != "postgresql+psycopg"
        or not username
        or not password
        or not decoded_host
        or port is None
        or not database_name
        or "/" in database_name
        or parsed.fragment
        or "%" in raw_host
        or decoded_host != raw_host
        or any(character in decoded_host for character in (",", "/", "\\"))
        or any(ord(character) < 0x21 or ord(character) == 0x7F for character in decoded_host)
        or len(query_names) != len(set(query_names))
        or {"host", "hostaddr", "service", "servicefile", "options", "connect_timeout"}
        .intersection(query_names)
        or any(
            marker in value.casefold()
            for value in placeholder_values
            for marker in ("change_me", "example.invalid", "not-used")
        )
    ):
        raise ValueError(
            "retention DATABASE_URL must bind one non-placeholder postgresql+psycopg user, password, host, port, and database"
        )
    if environment == "production":
        if parameters.get("sslmode") != "verify-full" or parameters.get("gssencmode") != "disable":
            raise ValueError(
                "production retention DATABASE_URL must use sslmode=verify-full and gssencmode=disable"
            )
    elif environment == "test":
        if (
            os.getenv("WALKSAFE_REPORT_RETENTION_ALLOW_TEST_LOOPBACK", "").strip().lower()
            != "true"
            or not _loopback_database_host(decoded_host)
            or "test" not in database_name.casefold()
            or parameters.get("sslmode") != "disable"
            or parameters.get("gssencmode") != "disable"
        ):
            raise ValueError(
                "test retention DATABASE_URL requires the explicit loopback test contract"
            )
    else:
        raise ValueError(
            "retention database access requires WALKSAFE_ENVIRONMENT=production or the explicit loopback test contract"
        )
    return database_url.strip()


def create_retention_database_engine(database_url: str) -> Any:
    from sqlalchemy import create_engine

    validated_url = validated_retention_database_url(database_url)
    connect_timeout_seconds, statement_timeout_ms = retention_database_timeouts()
    return create_engine(
        sqlalchemy_psycopg_url(validated_url),
        pool_pre_ping=True,
        pool_timeout=connect_timeout_seconds,
        connect_args={
            "connect_timeout": connect_timeout_seconds,
            "options": f"-c statement_timeout={statement_timeout_ms}",
        },
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_trusted_maintenance_lock_parent(parent: Path, parent_descriptor: int) -> None:
    if os.geteuid() == 0:
        raise ValueError("maintenance lock authority must not run as root")
    if not parent.is_absolute():
        raise ValueError("maintenance lock authority path must be absolute")
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | os.O_DIRECTORY
        | os.O_NOFOLLOW
    )
    authority_paths = [Path("/")]
    authority_descriptors: list[int] = []
    try:
        if parent.resolve(strict=True) != parent:
            raise ValueError(
                "maintenance lock authority ancestors must be canonical real directories"
            )
        authority_descriptors.append(os.open("/", flags))
        for component in parent.parent.relative_to("/").parts:
            authority_descriptors.append(
                os.open(component, flags, dir_fd=authority_descriptors[-1])
            )
            authority_paths.append(authority_paths[-1] / component)
        for index, (authority_path, descriptor) in enumerate(
            zip(authority_paths, authority_descriptors, strict=True)
        ):
            opened_authority = os.fstat(descriptor)
            path_authority = authority_path.stat(follow_symlinks=False)
            if (
                not stat.S_ISDIR(opened_authority.st_mode)
                or opened_authority.st_uid != 0
                or stat.S_IMODE(opened_authority.st_mode) & 0o022
                or os.access(
                    ".",
                    os.W_OK,
                    dir_fd=descriptor,
                    effective_ids=True,
                    follow_symlinks=False,
                )
            ):
                raise ValueError(
                    "maintenance lock authority ancestors must be root-owned and not writable by the service user"
                )
            if (path_authority.st_dev, path_authority.st_ino) != (
                opened_authority.st_dev,
                opened_authority.st_ino,
            ):
                raise ValueError("maintenance lock authority ancestry changed while opening")
            if index:
                anchored_authority = os.stat(
                    authority_path.name,
                    dir_fd=authority_descriptors[index - 1],
                    follow_symlinks=False,
                )
                if (anchored_authority.st_dev, anchored_authority.st_ino) != (
                    opened_authority.st_dev,
                    opened_authority.st_ino,
                ):
                    raise ValueError("maintenance lock authority ancestry changed while opening")
        anchored_parent = os.stat(
            parent.name,
            dir_fd=authority_descriptors[-1],
            follow_symlinks=False,
        )
        opened_parent = os.fstat(parent_descriptor)
        path_parent = parent.stat(follow_symlinks=False)
        if not (
            (path_parent.st_dev, path_parent.st_ino)
            == (opened_parent.st_dev, opened_parent.st_ino)
            == (anchored_parent.st_dev, anchored_parent.st_ino)
        ):
            raise ValueError("maintenance lock authority ancestry changed while opening")
    except ValueError:
        raise
    except (OSError, RuntimeError) as exc:
        raise ValueError("maintenance lock authority ancestry changed while opening") from exc
    finally:
        for descriptor in reversed(authority_descriptors):
            os.close(descriptor)


def _maintenance_lock_group_gid_from_environment() -> int | None:
    environment = os.getenv("WALKSAFE_ENVIRONMENT", "").strip().lower()
    group_name = os.getenv("WALKSAFE_MAINTENANCE_LOCK_GROUP", "").strip()
    if not group_name:
        if environment in {"staging", "production"}:
            raise ValueError("WALKSAFE_MAINTENANCE_LOCK_GROUP is required")
        return None
    try:
        group_gid = grp.getgrnam(group_name).gr_gid
    except KeyError as exc:
        raise ValueError("WALKSAFE_MAINTENANCE_LOCK_GROUP does not exist") from exc
    if group_gid == 0:
        raise ValueError("WALKSAFE_MAINTENANCE_LOCK_GROUP must not be root")
    return group_gid


def _upload_backup_reader_group_gid_from_environment() -> int | None:
    environment = os.getenv("WALKSAFE_ENVIRONMENT", "").strip().lower()
    if environment not in {"staging", "production"}:
        return None
    group_name = os.getenv("WALKSAFE_UPLOAD_BACKUP_READER_GROUP", "").strip()
    if not group_name:
        raise ValueError("WALKSAFE_UPLOAD_BACKUP_READER_GROUP is required")
    try:
        group_gid = grp.getgrnam(group_name).gr_gid
    except KeyError as exc:
        raise ValueError("WALKSAFE_UPLOAD_BACKUP_READER_GROUP does not exist") from exc
    if group_gid == 0:
        raise ValueError("WALKSAFE_UPLOAD_BACKUP_READER_GROUP must not be root")
    return group_gid


def _upload_file_contract(expected_group_gid: int | None) -> tuple[int, int]:
    if expected_group_gid is None:
        return 0o700, 0o600
    if expected_group_gid <= 0:
        raise ValueError("upload backup reader group must not be root")
    return 0o2750, 0o640


def _validate_upload_root_metadata(
    metadata: os.stat_result,
    *,
    expected_group_gid: int | None,
) -> None:
    directory_mode, _file_mode = _upload_file_contract(expected_group_gid)
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or metadata.st_uid != os.geteuid()
        or (
            expected_group_gid is not None
            and metadata.st_gid != expected_group_gid
        )
        or stat.S_IMODE(metadata.st_mode) != directory_mode
    ):
        raise ValueError("retention upload root contract differs")


def _descriptor_acl_is_absent(descriptor: int) -> bool:
    try:
        return not any(
            "acl" in os.fsdecode(name).casefold()
            for name in os.listxattr(descriptor)
        )
    except (AttributeError, OSError):
        return False


@contextmanager
def exclusive_maintenance_lock(
    path: Path,
    *,
    timeout_seconds: int = 30,
    expected_group_gid: int | None = None,
) -> Any:
    if not path.is_absolute() or Path(os.path.abspath(path)) != path:
        raise ValueError("maintenance lock path must be absolute")
    resolved = path
    parent = resolved.parent
    if expected_group_gid is None:
        private_restore_output_path(str(resolved))
    elif parent.resolve(strict=True) != parent:
        raise ValueError("maintenance lock parent must be a canonical real directory")
    parent_descriptor = os.open(
        parent,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        parent_metadata = os.fstat(parent_descriptor)
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
        ):
            raise ValueError("maintenance lock parent metadata is unsafe")
        _validate_trusted_maintenance_lock_parent(parent, parent_descriptor)
    except BaseException:
        os.close(parent_descriptor)
        raise
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(resolved.name, flags, dir_fd=parent_descriptor)
    except FileNotFoundError as exc:
        os.close(parent_descriptor)
        raise ValueError("maintenance lock must already exist") from exc
    except BaseException:
        os.close(parent_descriptor)
        raise
    try:
        opened_metadata = os.fstat(descriptor)
        expected_file_owner = 0 if expected_group_gid is not None else os.geteuid()
        expected_file_mode = 0o440 if expected_group_gid is not None else 0o600
        if (
            not stat.S_ISREG(opened_metadata.st_mode)
            or opened_metadata.st_uid != expected_file_owner
            or (
                expected_group_gid is not None
                and opened_metadata.st_gid != expected_group_gid
            )
            or stat.S_IMODE(opened_metadata.st_mode) != expected_file_mode
            or opened_metadata.st_nlink != 1
            or not _descriptor_acl_is_absent(descriptor)
        ):
            raise ValueError("existing maintenance lock metadata is unsafe")
        parent_stable_state = (
            parent_metadata.st_dev,
            parent_metadata.st_ino,
            parent_metadata.st_mode,
            parent_metadata.st_uid,
            parent_metadata.st_gid,
            parent_metadata.st_nlink,
            parent_metadata.st_ctime_ns,
        )
        lock_stable_state = (
            opened_metadata.st_dev,
            opened_metadata.st_ino,
            opened_metadata.st_mode,
            opened_metadata.st_uid,
            opened_metadata.st_gid,
            opened_metadata.st_nlink,
            opened_metadata.st_ctime_ns,
        )

        def verify_binding() -> None:
            try:
                anchored_parent = os.fstat(parent_descriptor)
                path_parent = parent.stat(follow_symlinks=False)
                anchored_lock = os.stat(
                    resolved.name,
                    dir_fd=parent_descriptor,
                    follow_symlinks=False,
                )
                path_lock = resolved.stat(follow_symlinks=False)
                current_lock = os.fstat(descriptor)
            except OSError as exc:
                raise ValueError("maintenance lock path or parent changed while held") from exc
            if (
                (
                    anchored_parent.st_dev,
                    anchored_parent.st_ino,
                    anchored_parent.st_mode,
                    anchored_parent.st_uid,
                    anchored_parent.st_gid,
                    anchored_parent.st_nlink,
                    anchored_parent.st_ctime_ns,
                )
                != parent_stable_state
                or (path_parent.st_dev, path_parent.st_ino)
                != (anchored_parent.st_dev, anchored_parent.st_ino)
                or not _descriptor_acl_is_absent(parent_descriptor)
                or not _descriptor_acl_is_absent(descriptor)
                or (anchored_lock.st_dev, anchored_lock.st_ino)
                != (current_lock.st_dev, current_lock.st_ino)
                or (path_lock.st_dev, path_lock.st_ino)
                != (current_lock.st_dev, current_lock.st_ino)
                or not stat.S_ISREG(current_lock.st_mode)
                or current_lock.st_uid != expected_file_owner
                or (
                    expected_group_gid is not None
                    and current_lock.st_gid != expected_group_gid
                )
                or stat.S_IMODE(current_lock.st_mode) != expected_file_mode
                or current_lock.st_nlink != 1
                or (
                    current_lock.st_dev,
                    current_lock.st_ino,
                    current_lock.st_mode,
                    current_lock.st_uid,
                    current_lock.st_gid,
                    current_lock.st_nlink,
                    current_lock.st_ctime_ns,
                )
                != lock_stable_state
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
            ):
                raise ValueError("maintenance lock path or parent changed while held")

        verify_binding()
        deadline = time.monotonic() + timeout_seconds
        while True:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise TimeoutError("timed out acquiring maintenance lock")
                time.sleep(0.1)
        try:
            verify_binding()
            authority_metadata = os.fstat(descriptor)
            try:
                yield {
                    "identity_sha256": path_identity_sha256(resolved),
                    "device_inode": (
                        f"{authority_metadata.st_dev}:{authority_metadata.st_ino}"
                    ),
                    "acquired_at": datetime.now(UTC),
                }
            finally:
                verify_binding()
        finally:
            fcntl.flock(descriptor, fcntl.LOCK_UN)
    finally:
        os.close(descriptor)
        os.close(parent_descriptor)


def validate_predelete_backup(
    manifest_path: Path,
    *,
    trusted_signer_fingerprint: str,
    database_identity: str,
    upload_root_identity: str,
    maintenance_lock_identity: str,
    maintenance_lock_device_inode: str,
    started_at: datetime,
) -> dict[str, Any]:
    payload = verify_signed_backup_manifest(
        manifest_path,
        trusted_signer_fingerprint=trusted_signer_fingerprint,
    )
    created_at = parse_datetime(str(payload.get("created_at", "")))
    if created_at > started_at or started_at - created_at > BACKUP_MAX_AGE:
        raise ValueError("pre-delete backup must precede retention apply by at most 26 hours")
    if payload.get("database_identity_sha256") != database_identity:
        raise ValueError("pre-delete backup database identity does not match retention target")
    if payload.get("upload_root_identity_sha256") != upload_root_identity:
        raise ValueError("pre-delete backup upload identity does not match retention target")
    snapshot = payload.get("snapshot_boundary")
    if not isinstance(snapshot, dict) or snapshot.get("writes_quiesced_by_operator") is not True:
        raise ValueError("pre-delete backup lacks an explicit write-quiesce snapshot boundary")
    if snapshot.get("maintenance_lock_identity_sha256") != maintenance_lock_identity:
        raise ValueError("pre-delete backup did not use the retention maintenance lock")
    lock_device_inode = snapshot.get("maintenance_lock_device_inode")
    if not isinstance(lock_device_inode, str) or re.fullmatch(r"[0-9]+:[0-9]+", lock_device_inode) is None:
        raise ValueError("pre-delete backup maintenance lock device/inode is invalid")
    if lock_device_inode != maintenance_lock_device_inode:
        raise ValueError("pre-delete backup did not use the retention maintenance lock authority")
    lock_acquired_at = parse_datetime(str(snapshot.get("lock_acquired_at", "")))
    snapshot_started_at = parse_datetime(str(snapshot.get("started_at", "")))
    snapshot_finished_at = parse_datetime(str(snapshot.get("finished_at", "")))
    if not lock_acquired_at <= snapshot_started_at <= snapshot_finished_at <= created_at:
        raise ValueError("pre-delete backup snapshot boundary timestamps are out of order")
    source_consistency = payload.get("source_consistency")
    source_count_names = (
        "report_image_count",
        "upload_file_count",
        "missing_count",
        "orphan_count",
        "missing_image_hash_count",
        "image_hash_mismatch_count",
    )
    if (
        not isinstance(source_consistency, dict)
        or source_consistency.get("ready") is not True
        or not isinstance(source_consistency.get("snapshot_content_sha256"), str)
        or SHA256_PATTERN.fullmatch(source_consistency["snapshot_content_sha256"].lower()) is None
        or any(
            not isinstance(source_consistency.get(name), int)
            or isinstance(source_consistency.get(name), bool)
            or source_consistency[name] < 0
            for name in source_count_names
        )
        or source_consistency["report_image_count"] != source_consistency["upload_file_count"]
        or any(
            source_consistency[name] != 0
            for name in (
                "missing_count",
                "orphan_count",
                "missing_image_hash_count",
                "image_hash_mismatch_count",
            )
        )
    ):
        raise ValueError("pre-delete backup did not prove report/upload content consistency")
    run_id = payload.get("run_id")
    if not isinstance(run_id, str) or not run_id.strip():
        raise ValueError("pre-delete backup run_id is required")
    artifacts = payload.get("artifacts_sha256")
    expected_names = {"reports.dump.gpg", "uploads.tar.gz.gpg"}
    if not isinstance(artifacts, dict) or set(artifacts) != expected_names:
        raise ValueError("pre-delete backup artifact hashes are invalid")
    for name in expected_names:
        digest = artifacts.get(name)
        artifact_path = manifest_path.parent / name
        if (
            not isinstance(digest, str)
            or not re.fullmatch(r"[0-9a-f]{64}", digest.lower())
            or not artifact_path.is_file()
            or artifact_path.is_symlink()
            or sha256_file(artifact_path) != digest.lower()
        ):
            raise ValueError(f"pre-delete backup artifact verification failed: {name}")
    return {
        "run_id": run_id,
        "created_at": created_at,
        "artifacts_sha256": {name: str(artifacts[name]).lower() for name in sorted(expected_names)},
        "maintenance_lock_identity_sha256": maintenance_lock_identity,
        "verified_signer_fingerprint": trusted_signer_fingerprint.lower(),
    }


def validate_predelete_restore_receipt(
    receipt_path: Path,
    *,
    trusted_signer_fingerprint: str,
    backup: dict[str, Any],
    database_identity: str,
    upload_root_identity: str,
    started_at: datetime,
) -> dict[str, Any]:
    if not receipt_path.is_file() or receipt_path.is_symlink():
        raise ValueError("pre-delete restore receipt must be a regular non-symlink file")
    signature_path = receipt_path.with_name(f"{receipt_path.name}.sig")
    payload, verified_signer, receipt_sha256 = verify_signed_json_document_with_digest(
        receipt_path,
        signature_path,
        trusted_signer_fingerprint,
    )
    if not isinstance(payload, dict) or payload.get("schema_version") != RESTORE_RECEIPT_SCHEMA:
        raise ValueError("pre-delete restore receipt schema is invalid")
    restored_at = parse_datetime(str(payload.get("restored_at", "")))
    if not backup["created_at"] <= restored_at <= started_at:
        raise ValueError("pre-delete restore drill must follow the current backup and precede retention")
    if payload.get("backup_run_id") != backup["run_id"]:
        raise ValueError("pre-delete restore drill does not reference the current backup")
    source_database_identity = payload.get("source_database_identity_sha256")
    source_upload_identity = payload.get("source_upload_root_identity_sha256")
    target_database_identity = payload.get("target_database_identity_sha256")
    target_upload_identity = payload.get("target_upload_root_identity_sha256")
    identities = {
        "source_database_identity_sha256": source_database_identity,
        "source_upload_root_identity_sha256": source_upload_identity,
        "target_database_identity_sha256": target_database_identity,
        "target_upload_root_identity_sha256": target_upload_identity,
    }
    if any(
        not isinstance(value, str) or SHA256_PATTERN.fullmatch(value.lower()) is None
        for value in identities.values()
    ):
        raise ValueError("pre-delete restore environment identities are invalid")
    normalized_identities = {name: str(value).lower() for name, value in identities.items()}
    if normalized_identities["source_database_identity_sha256"] != database_identity.lower():
        raise ValueError("pre-delete restore source database identity is invalid")
    if normalized_identities["source_upload_root_identity_sha256"] != upload_root_identity.lower():
        raise ValueError("pre-delete restore source upload identity is invalid")
    if (
        normalized_identities["source_database_identity_sha256"]
        == normalized_identities["target_database_identity_sha256"]
    ):
        raise ValueError("pre-delete restore target database must differ from its source")
    if (
        normalized_identities["source_upload_root_identity_sha256"]
        == normalized_identities["target_upload_root_identity_sha256"]
    ):
        raise ValueError("pre-delete restore target upload root must differ from its source")
    receipt_signer = payload.get("receipt_signer_fingerprint")
    if not isinstance(receipt_signer, str) or receipt_signer.lower() != verified_signer:
        raise ValueError("pre-delete restore receipt signer field does not match its signature")
    backup_signer = payload.get("trusted_signer_fingerprint")
    if not isinstance(backup_signer, str) or backup_signer.lower() != backup["verified_signer_fingerprint"]:
        raise ValueError("pre-delete restore receipt does not bind the trusted backup signer")
    for flag in (
        "hashes_verified",
        "encrypted_backup_verified",
        "manifest_signature_verified",
        "database_restore_completed",
        "uploads_restore_completed",
        "target_was_explicit",
        "report_upload_consistency_verified",
    ):
        if payload.get(flag) is not True:
            raise ValueError(f"pre-delete restore receipt requires {flag}=true")
    artifacts = payload.get("artifacts_sha256")
    normalized_artifacts = (
        {str(name): str(digest).lower() for name, digest in artifacts.items()}
        if isinstance(artifacts, dict)
        else {}
    )
    if normalized_artifacts != backup["artifacts_sha256"]:
        raise ValueError("pre-delete restore artifact hashes do not match the current backup")
    if any(SHA256_PATTERN.fullmatch(digest) is None for digest in normalized_artifacts.values()):
        raise ValueError("pre-delete restore artifact hashes are invalid")
    upload_snapshot = payload.get("restore_upload_snapshot_sha256")
    if (
        not isinstance(upload_snapshot, str)
        or SHA256_PATTERN.fullmatch(upload_snapshot.lower()) is None
    ):
        raise ValueError("pre-delete restore upload snapshot hash is invalid")
    upload_tree_device_inode = payload.get("restore_tree_device_inode")
    if (
        not isinstance(upload_tree_device_inode, str)
        or re.fullmatch(r"[0-9]+:[0-9]+", upload_tree_device_inode) is None
    ):
        raise ValueError("pre-delete restore upload tree identity is invalid")
    counts = payload.get("consistency_counts")
    required_counts = (
        "restored_report_count",
        "image_reference_count",
        "matched_image_count",
        "missing_image_count",
        "missing_image_hash_count",
        "image_hash_mismatch_count",
        "unsafe_image_path_count",
        "restored_upload_file_count",
        "orphan_upload_file_count",
    )
    if not isinstance(counts, dict) or any(
        not isinstance(counts.get(name), int) or isinstance(counts.get(name), bool) or counts[name] < 0
        for name in required_counts
    ):
        raise ValueError("pre-delete restore consistency counts are invalid")
    if not (
        counts["restored_report_count"]
        == counts["image_reference_count"]
        == counts["matched_image_count"]
        == counts["restored_upload_file_count"]
        and counts["missing_image_count"] == 0
        and counts["missing_image_hash_count"] == 0
        and counts["image_hash_mismatch_count"] == 0
        and counts["unsafe_image_path_count"] == 0
        and counts["orphan_upload_file_count"] == 0
    ):
        raise ValueError("pre-delete restore did not prove report/upload consistency")
    return {
        "restored_at": restored_at,
        "receipt_sha256": receipt_sha256,
        "artifacts_sha256": normalized_artifacts,
        "restore_upload_snapshot_sha256": upload_snapshot.lower(),
        "restore_tree_device_inode": upload_tree_device_inode,
        **normalized_identities,
        "verified_signer_fingerprint": verified_signer,
    }


def is_fake_demo(row: dict[str, Any]) -> bool:
    metadata = row.get("metadata")
    if not isinstance(metadata, dict):
        metadata = row.get("payload") if isinstance(row.get("payload"), dict) else {}
    review_flags = row.get("review_flags")
    if not isinstance(review_flags, list):
        review_flags = metadata.get("review_flags") if isinstance(metadata.get("review_flags"), list) else []
    source_model = metadata.get("source_model")

    # Performance exclusion also covers missing/failed coordinate gates and is
    # independent from fake/demo provenance. Do not shorten retention for it.
    return (
        row.get("source") == "fake"
        or metadata.get("fake_source") is True
        or metadata.get("data_origin") == "demo"
        or "fake_source" in review_flags
        or (isinstance(source_model, str) and ("fake" in source_model.lower() or "demo" in source_model.lower()))
    )


def retention_bucket(row: dict[str, Any]) -> tuple[str, int]:
    if is_fake_demo(row):
        return "fake_demo", RETENTION_DAYS["fake_demo"]
    if row.get("status") == "resolved":
        return "resolved", RETENTION_DAYS["resolved"]
    return "active", RETENTION_DAYS["active"]


def candidate_for_row(row: dict[str, Any], *, as_of: datetime) -> dict[str, Any] | None:
    created_at_value = row.get("created_at") or row.get("captured_at")
    if isinstance(created_at_value, datetime):
        created_at = (
            created_at_value.replace(tzinfo=UTC)
            if created_at_value.tzinfo is None
            else created_at_value.astimezone(UTC)
        )
    elif isinstance(created_at_value, str):
        created_at = parse_datetime(created_at_value)
    else:
        return None

    age_days = (as_of - created_at).days
    bucket, retention_days = retention_bucket(row)
    if age_days < retention_days:
        return None

    return {
        "id": str(row.get("id", "")),
        "status": row.get("status"),
        "source": "fake" if bucket == "fake_demo" else row.get("source"),
        "created_at": created_at.isoformat().replace("+00:00", "Z"),
        "age_days": age_days,
        "retention_days": retention_days,
        "reason": bucket,
        "image_path_present": bool(row.get("image_path")),
        "would_delete": False,
    }


def load_rows(path: Path | None) -> list[dict[str, Any]]:
    if path is None:
        return []
    payload = json.loads(path.read_text(encoding="utf-8"))
    rows = payload.get("rows") if isinstance(payload, dict) else payload
    if not isinstance(rows, list):
        raise ValueError("input JSON must be a list or an object with a rows list")
    return [row for row in rows if isinstance(row, dict)]


def report_row(report: Any) -> dict[str, Any]:
    return {
        "id": str(report.id),
        "status": report.status,
        "source": report.source,
        "created_at": report.created_at,
        "captured_at": report.captured_at,
        "image_path": report.image_path,
        "metadata": report.payload if isinstance(report.payload, dict) else {},
    }


def database_expired_statement(report_model: Any, *, as_of: datetime, limit: int, lock_rows: bool) -> Any:
    from sqlalchemy import and_, func, or_, select

    fake_demo = func.coalesce(
        or_(
            report_model.source == "fake",
            report_model.payload.contains({"fake_source": True}),
            report_model.payload.contains({"data_origin": "demo"}),
            report_model.payload.contains({"review_flags": ["fake_source"]}),
            report_model.payload["source_model"].as_string().ilike("%fake%"),
            report_model.payload["source_model"].as_string().ilike("%demo%"),
        ),
        False,
    )
    expired = or_(
        and_(fake_demo, report_model.created_at <= as_of - timedelta(days=RETENTION_DAYS["fake_demo"])),
        and_(~fake_demo, report_model.created_at <= as_of - timedelta(days=RETENTION_DAYS["active"])),
    )
    statement = (
        select(report_model)
        .where(expired)
        .order_by(report_model.created_at.asc(), report_model.id.asc())
        .limit(limit)
    )
    return statement.with_for_update() if lock_rows else statement


def load_database_retention_batch(
    database_url: str,
    *,
    as_of: datetime,
    batch_size: int,
) -> tuple[int, list[dict[str, Any]], bool]:
    from sqlalchemy import func, select
    from sqlalchemy.orm import Session

    from backend.app.models import Report

    engine = create_retention_database_engine(database_url)
    try:
        with Session(engine) as session:
            input_rows = int(session.scalar(select(func.count()).select_from(Report)) or 0)
            reports = list(
                session.scalars(
                    database_expired_statement(
                        Report,
                        as_of=as_of,
                        limit=batch_size + 1,
                        lock_rows=False,
                    )
                ).all()
            )
            batch_limit_reached = len(reports) > batch_size
            return input_rows, [report_row(report) for report in reports[:batch_size]], batch_limit_reached
    finally:
        engine.dispose()


def write_markdown(
    path: Path,
    *,
    as_of: datetime,
    candidates: list[dict[str, Any]],
    destructive_action: bool,
) -> None:
    lines = [
        "# Report retention result" if destructive_action else "# Report retention dry-run",
        "",
        f"- as_of: `{as_of.isoformat().replace('+00:00', 'Z')}`",
        f"- candidates: {len(candidates)}",
        f"- destructive_action: {str(destructive_action).lower()}",
        "",
        "| id | status | source | age_days | retention_days | reason | image_path_present |",
        "|---|---|---|---:|---:|---|---|",
    ]
    for item in candidates:
        lines.append(
            "| {id} | {status} | {source} | {age_days} | {retention_days} | {reason} | {image_path_present} |".format(
                **item
            )
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


class RetentionManifestPublisher:
    def __init__(
        self,
        path: Path,
        *,
        upload_dir: Path,
        backup_manifest_path: Path,
        restore_receipt_path: Path,
        maintenance_lock_path: Path,
        signal_guard: RetentionSignalGuard,
    ) -> None:
        if not path.is_absolute():
            raise ValueError("retention manifest path must be absolute")
        self.path = path.expanduser().absolute()
        self.parent = self.path.parent
        if self.parent.resolve(strict=True) != self.parent:
            raise ValueError("retention manifest parent must be a canonical real directory")
        upload_root = upload_dir.expanduser().resolve(strict=True)
        if (
            self.parent == upload_root
            or self.parent.is_relative_to(upload_root)
            or upload_root.is_relative_to(self.parent)
        ):
            raise ValueError("retention manifest directory must be disjoint from the upload root")
        for label, artifact_path in (
            ("backup manifest", backup_manifest_path),
            ("restore receipt", restore_receipt_path),
            ("maintenance lock", maintenance_lock_path),
        ):
            resolved_artifact = artifact_path.expanduser().resolve(strict=True)
            if resolved_artifact == self.path or resolved_artifact.is_relative_to(self.parent):
                raise ValueError(
                    f"retention manifest directory must be dedicated and distinct from the {label}"
                )
        parent_fd = _open_canonical_directory(self.parent)
        upload_fd = _open_canonical_directory(upload_root)
        try:
            parent_metadata = os.fstat(parent_fd)
            upload_metadata = os.fstat(upload_fd)
            anchored_parent = os.stat(self.parent, follow_symlinks=False)
            if (
                not stat.S_ISDIR(parent_metadata.st_mode)
                or parent_metadata.st_uid != os.geteuid()
                or stat.S_IMODE(parent_metadata.st_mode) != 0o700
                or (parent_metadata.st_dev, parent_metadata.st_ino)
                != (anchored_parent.st_dev, anchored_parent.st_ino)
                or (parent_metadata.st_dev, parent_metadata.st_ino)
                == (upload_metadata.st_dev, upload_metadata.st_ino)
            ):
                raise ValueError(
                    "retention manifest parent must be a dedicated current-user-owned 0700 real directory"
                )
            try:
                os.stat(self.path.name, dir_fd=parent_fd, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                raise ValueError("retention manifest path must be a new non-alias file")
            self._parent_identity = _directory_identity(parent_metadata)
        finally:
            os.close(upload_fd)
            os.close(parent_fd)
        self._signal_guard = signal_guard
        self._current_identity: tuple[int, ...] | None = None

    @staticmethod
    def _write_all(descriptor: int, content: bytes) -> None:
        remaining = memoryview(content)
        while remaining:
            written = os.write(descriptor, remaining)
            if written <= 0:
                raise OSError("retention manifest write made no progress")
            remaining = remaining[written:]

    def _remove_owned_name(
        self,
        parent_fd: int,
        name: str,
        identity: tuple[int, ...],
    ) -> None:
        unlink_file_with_identity(
            self.parent / name,
            identity,
            parent_fd=parent_fd,
        )

    def _remove_owned_binding_name(
        self,
        parent_fd: int,
        name: str,
        identity: tuple[int, int, int, int],
    ) -> None:
        unlink_file_with_binding_identity(
            self.parent / name,
            identity,
            parent_fd=parent_fd,
        )

    def _write_locked(self, payload: dict[str, Any]) -> None:
        parent_fd = _open_canonical_directory(self.parent)
        temporary_name = f".{self.path.name}.{uuid.uuid4().hex}.tmp"
        temporary_binding_identity: tuple[int, int, int, int] | None = None
        temporary_rename_identity: tuple[int, ...] | None = None
        temporary_exists = False
        initial_publication = False
        exchange_performed = False
        exchange_verified = False
        previous_rename_identity: tuple[int, ...] | None = None
        exchanged_previous_identity: tuple[int, ...] | None = None
        publication_committed = False
        try:
            parent_metadata = os.fstat(parent_fd)
            anchored_parent = os.stat(self.parent, follow_symlinks=False)
            if (
                _directory_identity(parent_metadata) != self._parent_identity
                or (parent_metadata.st_dev, parent_metadata.st_ino)
                != (anchored_parent.st_dev, anchored_parent.st_ino)
            ):
                raise ValueError("retention manifest parent identity changed")
            descriptor = os.open(
                temporary_name,
                os.O_WRONLY
                | os.O_CREAT
                | os.O_EXCL
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                0o600,
                dir_fd=parent_fd,
            )
            temporary_exists = True
            try:
                try:
                    temporary_binding_identity = file_binding_identity(
                        os.stat(descriptor)
                    )
                    self._write_all(
                        descriptor,
                        (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode(
                            "utf-8"
                        ),
                    )
                    os.fsync(descriptor)
                    temporary_metadata = os.fstat(descriptor)
                    if (
                        not stat.S_ISREG(temporary_metadata.st_mode)
                        or temporary_metadata.st_uid != os.geteuid()
                        or stat.S_IMODE(temporary_metadata.st_mode) != 0o600
                        or temporary_metadata.st_nlink != 1
                    ):
                        raise ValueError("retention manifest temporary file is invalid")
                    temporary_rename_identity = file_rename_stable_identity(
                        temporary_metadata
                    )
                except BaseException:
                    if temporary_binding_identity is None:
                        try:
                            opened_temporary = os.fstat(descriptor)
                            if (
                                not stat.S_ISREG(opened_temporary.st_mode)
                                or opened_temporary.st_uid != os.geteuid()
                                or stat.S_IMODE(opened_temporary.st_mode) != 0o600
                                or opened_temporary.st_nlink != 1
                            ):
                                raise ValueError(
                                    "retention manifest temporary descriptor is invalid"
                                )
                            temporary_binding_identity = file_binding_identity(
                                opened_temporary
                            )
                        except (OSError, ValueError):
                            pass
                    raise
            finally:
                os.close(descriptor)

            if self._current_identity is not None:
                current_metadata = os.stat(
                    self.path.name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
                if (
                    not stat.S_ISREG(current_metadata.st_mode)
                    or file_identity(current_metadata) != self._current_identity
                    or current_metadata.st_uid != os.geteuid()
                    or stat.S_IMODE(current_metadata.st_mode) != 0o600
                    or current_metadata.st_nlink != 1
                ):
                    raise ValueError("retention manifest identity changed")
                previous_rename_identity = file_rename_stable_identity(current_metadata)
                _rename_exchange(parent_fd, self.path.name, temporary_name)
                exchange_performed = True
                final_metadata = os.stat(
                    self.path.name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
                exchanged_previous = os.stat(
                    temporary_name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
                if (
                    temporary_rename_identity is None
                    or previous_rename_identity is None
                    or file_rename_stable_identity(final_metadata)
                    != temporary_rename_identity
                    or file_rename_stable_identity(exchanged_previous)
                    != previous_rename_identity
                ):
                    raise ValueError("retention manifest exchange identity changed")
                exchanged_previous_identity = file_identity(exchanged_previous)
                exchange_verified = True
            else:
                _rename_noreplace(parent_fd, temporary_name, self.path.name)
                temporary_exists = False
                initial_publication = True
                final_metadata = os.stat(
                    self.path.name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
                if (
                    temporary_rename_identity is None
                    or file_rename_stable_identity(final_metadata)
                    != temporary_rename_identity
                ):
                    raise ValueError("retention manifest publication identity changed")

            os.fsync(parent_fd)
            self._current_identity = file_identity(final_metadata)
            publication_committed = True
            if exchange_performed:
                if exchanged_previous_identity is None:
                    raise ValueError("retention manifest previous identity is unavailable")
                self._remove_owned_name(
                    parent_fd,
                    temporary_name,
                    exchanged_previous_identity,
                )
                temporary_exists = False
                os.fsync(parent_fd)
        except BaseException as original_error:
            cleanup_errors: list[BaseException] = []
            if initial_publication and not publication_committed:
                try:
                    published_metadata = os.stat(
                        self.path.name,
                        dir_fd=parent_fd,
                        follow_symlinks=False,
                    )
                    if (
                        temporary_rename_identity is None
                        or file_rename_stable_identity(published_metadata)
                        != temporary_rename_identity
                    ):
                        raise ValueError(
                            "retention manifest uncommitted publication identity changed"
                        )
                    self._remove_owned_name(
                        parent_fd,
                        self.path.name,
                        file_identity(published_metadata),
                    )
                    initial_publication = False
                except (OSError, ValueError) as exc:
                    cleanup_errors.append(exc)
            if exchange_performed and not publication_committed:
                try:
                    if not exchange_verified:
                        raise ValueError(
                            "retention manifest uncommitted exchange was not verified"
                        )
                    _rename_exchange(parent_fd, self.path.name, temporary_name)
                    restored_previous = os.stat(
                        self.path.name,
                        dir_fd=parent_fd,
                        follow_symlinks=False,
                    )
                    restored_new = os.stat(
                        temporary_name,
                        dir_fd=parent_fd,
                        follow_symlinks=False,
                    )
                    if (
                        previous_rename_identity is None
                        or temporary_rename_identity is None
                        or file_rename_stable_identity(restored_previous)
                        != previous_rename_identity
                        or file_rename_stable_identity(restored_new)
                        != temporary_rename_identity
                    ):
                        raise ValueError(
                            "retention manifest exchange rollback identity changed"
                        )
                    os.fsync(parent_fd)
                    self._current_identity = file_identity(restored_previous)
                    self._remove_owned_name(
                        parent_fd,
                        temporary_name,
                        file_identity(restored_new),
                    )
                    temporary_exists = False
                    os.fsync(parent_fd)
                except (OSError, ValueError) as exc:
                    cleanup_errors.append(exc)
            elif exchange_performed and publication_committed and temporary_exists:
                try:
                    if exchanged_previous_identity is None:
                        raise ValueError("retention manifest previous identity is unavailable")
                    self._remove_owned_name(
                        parent_fd,
                        temporary_name,
                        exchanged_previous_identity,
                    )
                    temporary_exists = False
                    os.fsync(parent_fd)
                except (OSError, ValueError) as exc:
                    cleanup_errors.append(exc)
            if (
                temporary_exists
                and not exchange_performed
            ):
                if temporary_binding_identity is None:
                    cleanup_errors.append(
                        ValueError(
                            "retention manifest temporary descriptor identity is unavailable"
                        )
                    )
                else:
                    try:
                        self._remove_owned_binding_name(
                            parent_fd,
                            temporary_name,
                            temporary_binding_identity,
                        )
                    except (OSError, ValueError) as exc:
                        cleanup_errors.append(exc)
            if cleanup_errors:
                raise RuntimeError("retention manifest publication cleanup failed") from original_error
            raise
        finally:
            os.close(parent_fd)

    def write(self, payload: dict[str, Any]) -> None:
        with self._signal_guard.blocked():
            self._write_locked(payload)


def write_json_atomic(
    path: Path,
    payload: dict[str, Any],
    *,
    publisher: RetentionManifestPublisher,
) -> None:
    if path != publisher.path:
        raise ValueError("retention manifest publisher path changed")
    publisher.write(payload)


def file_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return tuple(getattr(metadata, name) for name in FILE_IDENTITY_FIELDS)


def file_rename_stable_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_uid,
        metadata.st_mode,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_nlink,
    )


def file_binding_identity(metadata: os.stat_result) -> tuple[int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_uid,
        stat.S_IFMT(metadata.st_mode),
    )


def _rename_noreplace(directory_fd: int, source_name: str, target_name: str) -> None:
    renameat2 = getattr(ctypes.CDLL(None, use_errno=True), "renameat2", None)
    if renameat2 is None:
        raise OSError("renameat2(RENAME_NOREPLACE) is unavailable")
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    if (
        renameat2(
            directory_fd,
            os.fsencode(source_name),
            directory_fd,
            os.fsencode(target_name),
            1,
        )
        != 0
    ):
        error_number = ctypes.get_errno()
        raise OSError(error_number, os.strerror(error_number), source_name)


def _rename_exchange(directory_fd: int, left_name: str, right_name: str) -> None:
    renameat2 = getattr(ctypes.CDLL(None, use_errno=True), "renameat2", None)
    if renameat2 is None:
        raise OSError("renameat2(RENAME_EXCHANGE) is unavailable")
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    if (
        renameat2(
            directory_fd,
            os.fsencode(left_name),
            directory_fd,
            os.fsencode(right_name),
            2,
        )
        != 0
    ):
        error_number = ctypes.get_errno()
        raise OSError(error_number, os.strerror(error_number), left_name)


def _open_directory(path: Path) -> int:
    return os.open(
        path,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )


def _open_canonical_directory(path: Path) -> int:
    if not path.is_absolute():
        raise ValueError("retention directory must be an absolute canonical real path")
    try:
        resolved = path.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise ValueError("retention directory must be an absolute canonical real path") from exc
    if resolved != path:
        raise ValueError("retention directory must be an absolute canonical real path")
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    descriptor = os.open("/", flags)
    try:
        for component in path.relative_to("/").parts:
            child = os.open(component, flags, dir_fd=descriptor)
            try:
                anchored = os.stat(component, dir_fd=descriptor, follow_symlinks=False)
                opened = os.fstat(child)
                if (
                    not stat.S_ISDIR(anchored.st_mode)
                    or stat.S_ISLNK(anchored.st_mode)
                    or not stat.S_ISDIR(opened.st_mode)
                    or (anchored.st_dev, anchored.st_ino)
                    != (opened.st_dev, opened.st_ino)
                ):
                    raise ValueError("retention directory ancestry changed while opening")
            except BaseException:
                os.close(child)
                raise
            os.close(descriptor)
            descriptor = child
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def require_file_identity(
    path: Path,
    expected: tuple[int, ...],
    *,
    parent_fd: int | None = None,
) -> None:
    owned_parent_fd = _open_directory(path.parent) if parent_fd is None else -1
    directory_fd = owned_parent_fd if owned_parent_fd >= 0 else parent_fd
    assert directory_fd is not None
    try:
        metadata = os.stat(path.name, dir_fd=directory_fd, follow_symlinks=False)
        if not stat.S_ISREG(metadata.st_mode) or file_identity(metadata) != expected:
            raise ValueError(f"retention file identity changed: {path.name}")
    except OSError as exc:
        raise ValueError(f"retention file identity changed: {path.name}") from exc
    finally:
        if owned_parent_fd >= 0:
            os.close(owned_parent_fd)


def _restore_holding_name(
    directory_fd: int,
    holding_name: str,
    original_name: str,
) -> bool:
    try:
        _rename_noreplace(directory_fd, holding_name, original_name)
    except FileExistsError:
        os.fsync(directory_fd)
        return False
    except FileNotFoundError:
        os.fsync(directory_fd)
        return False
    os.fsync(directory_fd)
    return True


def _unlink_file_atomically(
    path: Path,
    expected: tuple[int, ...],
    *,
    full_identity: Any,
    rename_stable_identity: Any,
    parent_fd: int | None = None,
    signal_guard: RetentionSignalGuard | None = None,
) -> None:
    if signal_guard is not None:
        with signal_guard.blocked():
            return _unlink_file_atomically(
                path,
                expected,
                full_identity=full_identity,
                rename_stable_identity=rename_stable_identity,
                parent_fd=parent_fd,
            )
    owned_parent_fd = _open_directory(path.parent) if parent_fd is None else -1
    directory_fd = owned_parent_fd if owned_parent_fd >= 0 else parent_fd
    assert directory_fd is not None
    holding_name = ""
    holding_exists = False
    try:
        before = os.stat(path.name, dir_fd=directory_fd, follow_symlinks=False)
        if not stat.S_ISREG(before.st_mode) or full_identity(before) != expected:
            raise ValueError(f"retention file identity changed: {path.name}")
        expected_after_rename = rename_stable_identity(before)
        for _ in range(128):
            holding_name = f".walksafe-retention-delete-{uuid.uuid4().hex}"
            try:
                _rename_noreplace(directory_fd, path.name, holding_name)
            except FileExistsError:
                continue
            break
        else:
            raise ValueError(f"could not reserve a retention deletion holding name: {path.name}")
        holding_exists = True
        os.fsync(directory_fd)
        metadata = os.stat(holding_name, dir_fd=directory_fd, follow_symlinks=False)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or rename_stable_identity(metadata) != expected_after_rename
        ):
            holding_exists = not _restore_holding_name(
                directory_fd,
                holding_name,
                path.name,
            )
            raise ValueError(f"retention file identity changed: {path.name}")
        os.unlink(holding_name, dir_fd=directory_fd)
        holding_exists = False
        os.fsync(directory_fd)
    except OSError as exc:
        if holding_exists:
            _restore_holding_name(directory_fd, holding_name, path.name)
        if isinstance(exc, ValueError):
            raise
        raise ValueError(f"retention file identity changed: {path.name}") from exc
    except BaseException:
        if holding_exists:
            _restore_holding_name(directory_fd, holding_name, path.name)
        raise
    finally:
        if owned_parent_fd >= 0:
            os.close(owned_parent_fd)


def unlink_file_with_identity(
    path: Path,
    expected: tuple[int, ...],
    *,
    parent_fd: int | None = None,
    signal_guard: RetentionSignalGuard | None = None,
) -> None:
    _unlink_file_atomically(
        path,
        expected,
        full_identity=file_identity,
        rename_stable_identity=file_rename_stable_identity,
        parent_fd=parent_fd,
        signal_guard=signal_guard,
    )


def unlink_file_with_binding_identity(
    path: Path,
    expected: tuple[int, int, int, int],
    *,
    parent_fd: int | None = None,
    signal_guard: RetentionSignalGuard | None = None,
) -> None:
    _unlink_file_atomically(
        path,
        expected,
        full_identity=file_binding_identity,
        rename_stable_identity=file_binding_identity,
        parent_fd=parent_fd,
        signal_guard=signal_guard,
    )


def _open_private_child_directory(
    parent_fd: int,
    name: str,
    *,
    create: bool,
    exclusive: bool,
) -> int:
    created = False
    if create:
        try:
            os.mkdir(name, 0o700, dir_fd=parent_fd)
            created = True
            os.fsync(parent_fd)
        except FileExistsError:
            if exclusive:
                raise ValueError(f"retention quarantine directory already exists: {name}")
    descriptor = os.open(
        name,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0),
        dir_fd=parent_fd,
    )
    try:
        if created:
            os.fchmod(descriptor, 0o700)
        _validate_private_child_directory(parent_fd, name, descriptor)
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _validate_private_child_directory(
    parent_fd: int,
    name: str,
    descriptor: int,
    *,
    opened: os.stat_result | None = None,
) -> os.stat_result:
    opened = os.fstat(descriptor) if opened is None else opened
    anchored = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISDIR(opened.st_mode)
        or stat.S_ISLNK(anchored.st_mode)
        or opened.st_uid != os.geteuid()
        or stat.S_IMODE(opened.st_mode) != 0o700
        or (opened.st_dev, opened.st_ino) != (anchored.st_dev, anchored.st_ino)
    ):
        raise ValueError(f"retention quarantine directory is not private: {name}")
    return opened


def _directory_identity(metadata: os.stat_result) -> tuple[int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_uid,
        metadata.st_mode,
    )


def prepare_private_quarantine(
    upload_dir: Path,
    run_id: str,
    *,
    expected_upload_identity: tuple[int, int, int, int],
    expected_upload_group_gid: int | None = None,
) -> tuple[Path, int, int, int]:
    if re.fullmatch(r"[0-9a-f]{32}", run_id) is None:
        raise ValueError("retention run id is invalid")
    if not upload_dir.is_absolute():
        raise ValueError("retention upload root must be absolute")
    absolute_upload_dir = upload_dir.expanduser()
    resolved_upload_dir = absolute_upload_dir.resolve(strict=True)
    if resolved_upload_dir != absolute_upload_dir:
        raise ValueError("retention upload root must be a canonical real directory")
    upload_path_metadata = os.lstat(resolved_upload_dir)
    upload_fd = _open_canonical_directory(resolved_upload_dir)
    quarantine_root_fd = -1
    quarantine_run_fd = -1
    quarantine_root_created = False
    quarantine_run_created = False
    quarantine_root_identity: tuple[int, int, int, int] | None = None
    quarantine_run_identity: tuple[int, int, int, int] | None = None
    try:
        opened_upload = os.fstat(upload_fd)
        _validate_upload_root_metadata(
            opened_upload,
            expected_group_gid=expected_upload_group_gid,
        )
        if (
            not _descriptor_acl_is_absent(upload_fd)
            or (opened_upload.st_dev, opened_upload.st_ino)
            != (upload_path_metadata.st_dev, upload_path_metadata.st_ino)
            or (
                opened_upload.st_dev,
                opened_upload.st_ino,
                opened_upload.st_uid,
                opened_upload.st_mode,
            )
            != expected_upload_identity
            ):
                raise ValueError(
                    "retention upload root must remain the same private real directory with no ACL"
                )
        try:
            os.mkdir(".retention-quarantine", 0o700, dir_fd=upload_fd)
            quarantine_root_created = True
            quarantine_root_fd = os.open(
                ".retention-quarantine",
                os.O_RDONLY
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_DIRECTORY", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=upload_fd,
            )
            os.fchmod(quarantine_root_fd, 0o700)
            created_root = os.fstat(quarantine_root_fd)
            quarantine_root_identity = _directory_identity(created_root)
            _validate_private_child_directory(
                upload_fd,
                ".retention-quarantine",
                quarantine_root_fd,
                opened=created_root,
            )
            os.fsync(upload_fd)
        except FileExistsError:
            quarantine_root_fd = _open_private_child_directory(
                upload_fd,
                ".retention-quarantine",
                create=False,
                exclusive=False,
            )
        if quarantine_root_identity is not None:
            opened_root = os.fstat(quarantine_root_fd)
            if _directory_identity(opened_root) != quarantine_root_identity:
                raise ValueError("retention quarantine root directory identity changed")
        try:
            os.mkdir(run_id, 0o700, dir_fd=quarantine_root_fd)
        except FileExistsError as exc:
            raise ValueError(
                f"retention quarantine directory already exists: {run_id}"
            ) from exc
        quarantine_run_created = True
        quarantine_run_fd = os.open(
            run_id,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=quarantine_root_fd,
        )
        created_run = os.fstat(quarantine_run_fd)
        quarantine_run_identity = _directory_identity(created_run)
        _validate_private_child_directory(
            quarantine_root_fd,
            run_id,
            quarantine_run_fd,
            opened=created_run,
        )
        os.fsync(quarantine_root_fd)
        opened_run = os.fstat(quarantine_run_fd)
        if _directory_identity(opened_run) != quarantine_run_identity:
            raise ValueError("retention quarantine run directory identity changed")
        return (
            resolved_upload_dir / ".retention-quarantine" / run_id,
            upload_fd,
            quarantine_root_fd,
            quarantine_run_fd,
        )
    except BaseException as original_error:
        cleanup_errors: list[BaseException] = []
        try:
            if quarantine_run_created and quarantine_root_fd >= 0:
                try:
                    if quarantine_run_identity is None and quarantine_run_fd >= 0:
                        quarantine_run_identity = _directory_identity(
                            os.stat(quarantine_run_fd)
                        )
                    anchored_run = os.stat(
                        run_id,
                        dir_fd=quarantine_root_fd,
                        follow_symlinks=False,
                    )
                    if (
                        quarantine_run_identity is None
                        or _directory_identity(anchored_run) != quarantine_run_identity
                    ):
                        raise ValueError(
                            "retention quarantine run directory identity changed"
                        )
                    os.rmdir(run_id, dir_fd=quarantine_root_fd)
                    os.fsync(quarantine_root_fd)
                except FileNotFoundError:
                    pass
                except (OSError, ValueError) as exc:
                    cleanup_errors.append(exc)
            if quarantine_root_created:
                try:
                    if quarantine_root_identity is None and quarantine_root_fd >= 0:
                        quarantine_root_identity = _directory_identity(
                            os.stat(quarantine_root_fd)
                        )
                    anchored_root = os.stat(
                        ".retention-quarantine",
                        dir_fd=upload_fd,
                        follow_symlinks=False,
                    )
                    if (
                        quarantine_root_identity is None
                        or _directory_identity(anchored_root) != quarantine_root_identity
                    ):
                        raise ValueError(
                            "retention quarantine root directory identity changed"
                        )
                    os.rmdir(".retention-quarantine", dir_fd=upload_fd)
                    os.fsync(upload_fd)
                except FileNotFoundError:
                    pass
                except (OSError, ValueError) as exc:
                    cleanup_errors.append(exc)
        finally:
            if quarantine_run_fd >= 0:
                os.close(quarantine_run_fd)
            if quarantine_root_fd >= 0:
                os.close(quarantine_root_fd)
            os.close(upload_fd)
        if cleanup_errors:
            raise RuntimeError("retention quarantine acquisition cleanup failed") from original_error
        raise


def remove_private_quarantine_directories(
    upload_fd: int,
    quarantine_root_fd: int,
    quarantine_run_fd: int,
    run_id: str,
) -> None:
    run_metadata = os.fstat(quarantine_run_fd)
    anchored_run = os.stat(run_id, dir_fd=quarantine_root_fd, follow_symlinks=False)
    if (run_metadata.st_dev, run_metadata.st_ino) != (
        anchored_run.st_dev,
        anchored_run.st_ino,
    ):
        raise ValueError("retention quarantine run directory identity changed")
    os.rmdir(run_id, dir_fd=quarantine_root_fd)
    os.fsync(quarantine_root_fd)
    root_metadata = os.fstat(quarantine_root_fd)
    anchored_root = os.stat(
        ".retention-quarantine",
        dir_fd=upload_fd,
        follow_symlinks=False,
    )
    if (root_metadata.st_dev, root_metadata.st_ino) != (
        anchored_root.st_dev,
        anchored_root.st_ino,
    ):
        raise ValueError("retention quarantine root directory identity changed")
    try:
        os.rmdir(".retention-quarantine", dir_fd=upload_fd)
    except OSError as exc:
        if exc.errno != errno.ENOTEMPTY:
            raise
    else:
        os.fsync(upload_fd)


def _copy_recovery_file_descriptors(
    source_fd: int,
    target_fd: int,
    *,
    signal_guard: RetentionSignalGuard | None,
    fault_hook: Callable[[str], None] | None = None,
) -> str:
    digest = hashlib.sha256()
    partial_copy_fault_emitted = False
    while True:
        if signal_guard is None:
            chunk = os.read(source_fd, 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            remaining = memoryview(chunk)
            while remaining:
                written = os.write(target_fd, remaining)
                if written <= 0:
                    raise OSError("retention recovery copy made no write progress")
                remaining = remaining[written:]
                if fault_hook is not None and not partial_copy_fault_emitted:
                    partial_copy_fault_emitted = True
                    fault_hook("P2")
        else:
            with signal_guard.blocked():
                chunk = os.read(source_fd, 1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
                remaining = memoryview(chunk)
                while remaining:
                    written = os.write(target_fd, remaining)
                    if written <= 0:
                        raise OSError("retention recovery copy made no write progress")
                    remaining = remaining[written:]
                    if fault_hook is not None and not partial_copy_fault_emitted:
                        partial_copy_fault_emitted = True
                        fault_hook("P2")
    return digest.hexdigest()


def create_recovery_copy(
    source: Path,
    recovery: Path,
    *,
    source_parent_fd: int | None = None,
    recovery_parent_fd: int | None = None,
    signal_guard: RetentionSignalGuard | None = None,
    recovery_registry: list[
        tuple[Path, Path, tuple[int, ...], tuple[int, ...]]
    ] | None = None,
    expected_source_identity: tuple[int, ...] | None = None,
    expected_size: int | None = None,
    expected_sha256: str | None = None,
    expected_source_mode: int | None = None,
    expected_source_gid: int | None = None,
    fault_hook: Callable[[str], None] | None = None,
) -> tuple[
    tuple[int, ...],
    tuple[int, ...],
]:
    """Create a durable copy while leaving the DB-referenced original intact."""

    source_flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    target_flags = (
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    owned_source_parent_fd = -1
    owned_recovery_parent_fd = -1
    try:
        if source_parent_fd is None:
            owned_source_parent_fd = _open_directory(source.parent)
        if recovery_parent_fd is None:
            owned_recovery_parent_fd = _open_directory(recovery.parent)
    except BaseException:
        if owned_source_parent_fd >= 0:
            os.close(owned_source_parent_fd)
        raise
    source_directory_fd = (
        owned_source_parent_fd if owned_source_parent_fd >= 0 else source_parent_fd
    )
    recovery_directory_fd = (
        owned_recovery_parent_fd if owned_recovery_parent_fd >= 0 else recovery_parent_fd
    )
    assert source_directory_fd is not None and recovery_directory_fd is not None
    source_fd = -1
    target_fd = -1
    recovery_identity: tuple[int, ...] | None = None
    partial_recovery_identity: tuple[int, int, int, int] | None = None
    recovery_registered = False
    try:
        if (
            not _descriptor_acl_is_absent(source_directory_fd)
            or not _descriptor_acl_is_absent(recovery_directory_fd)
        ):
            raise ValueError("retention source or recovery directory has an ACL")
        with signal_guard.blocked() if signal_guard is not None else nullcontext():
            source_metadata = os.stat(
                source.name,
                dir_fd=source_directory_fd,
                follow_symlinks=False,
            )
            if (
                not stat.S_ISREG(source_metadata.st_mode)
                or source_metadata.st_uid != os.geteuid()
                or (
                    expected_source_gid is not None
                    and source_metadata.st_gid != expected_source_gid
                )
                or (
                    expected_source_mode is not None
                    and stat.S_IMODE(source_metadata.st_mode) != expected_source_mode
                )
                or source_metadata.st_nlink != 1
            ):
                raise ValueError(
                    f"retention image must be a regular non-symlink file: {source.name}"
                )
            source_identity = file_identity(source_metadata)
            if (
                (expected_source_identity is not None and source_identity != expected_source_identity)
                or (expected_size is not None and source_metadata.st_size != expected_size)
            ):
                raise ValueError(f"retention encrypted object metadata changed: {source.name}")
            source_fd = os.open(source.name, source_flags, dir_fd=source_directory_fd)
            opened_source = os.fstat(source_fd)
            if (
                file_identity(opened_source) != source_identity
                or opened_source.st_nlink != 1
                or opened_source.st_uid != os.geteuid()
                or (
                    expected_source_gid is not None
                    and opened_source.st_gid != expected_source_gid
                )
                or (
                    expected_source_mode is not None
                    and stat.S_IMODE(opened_source.st_mode) != expected_source_mode
                )
                or not _descriptor_acl_is_absent(source_fd)
            ):
                raise ValueError(f"retention image changed while opening: {source.name}")
        if signal_guard is None:
            target_fd = os.open(
                recovery.name,
                target_flags,
                0o600,
                dir_fd=recovery_directory_fd,
            )
            if not _descriptor_acl_is_absent(target_fd):
                raise ValueError("retention recovery copy has an ACL")
            partial_recovery_identity = file_binding_identity(os.fstat(target_fd))
        else:
            with signal_guard.blocked():
                target_fd = os.open(
                    recovery.name,
                    target_flags,
                    0o600,
                    dir_fd=recovery_directory_fd,
                )
                if not _descriptor_acl_is_absent(target_fd):
                    raise ValueError("retention recovery copy has an ACL")
                partial_recovery_identity = file_binding_identity(os.fstat(target_fd))
        copied_sha256 = _copy_recovery_file_descriptors(
            source_fd,
            target_fd,
            signal_guard=signal_guard,
            fault_hook=fault_hook,
        )
        if signal_guard is None:
            os.fsync(target_fd)
            final_source = os.fstat(source_fd)
            final_recovery = os.fstat(target_fd)
            recovery_identity = file_identity(final_recovery)
            if file_identity(final_source) != source_identity or final_source.st_nlink != 1:
                raise ValueError(f"retention image changed while copying: {source.name}")
            if (
                not stat.S_ISREG(final_recovery.st_mode)
                or final_recovery.st_uid != os.geteuid()
                or stat.S_IMODE(final_recovery.st_mode) != 0o600
                or final_recovery.st_nlink != 1
                or final_recovery.st_size != final_source.st_size
                or not _descriptor_acl_is_absent(source_fd)
                or not _descriptor_acl_is_absent(target_fd)
            ):
                raise ValueError(f"retention recovery copy is invalid: {recovery.name}")
            if expected_sha256 is not None and copied_sha256 != expected_sha256:
                raise ValueError(f"retention encrypted object digest changed: {source.name}")
            os.fsync(recovery_directory_fd)
        else:
            with signal_guard.blocked():
                os.fsync(target_fd)
                final_source = os.fstat(source_fd)
                final_recovery = os.fstat(target_fd)
                recovery_identity = file_identity(final_recovery)
                if file_identity(final_source) != source_identity or final_source.st_nlink != 1:
                    raise ValueError(f"retention image changed while copying: {source.name}")
                if (
                    not stat.S_ISREG(final_recovery.st_mode)
                    or final_recovery.st_uid != os.geteuid()
                    or stat.S_IMODE(final_recovery.st_mode) != 0o600
                    or final_recovery.st_nlink != 1
                    or final_recovery.st_size != final_source.st_size
                    or not _descriptor_acl_is_absent(source_fd)
                    or not _descriptor_acl_is_absent(target_fd)
                ):
                    raise ValueError(f"retention recovery copy is invalid: {recovery.name}")
                if expected_sha256 is not None and copied_sha256 != expected_sha256:
                    raise ValueError(f"retention encrypted object digest changed: {source.name}")
                os.fsync(recovery_directory_fd)
        if recovery_registry is not None:
            if signal_guard is None:
                recovery_registry.append(
                    (source, recovery, source_identity, recovery_identity)
                )
                recovery_registered = True
            else:
                with signal_guard.blocked():
                    recovery_registry.append(
                        (source, recovery, source_identity, recovery_identity)
                    )
                    recovery_registered = True
        return source_identity, recovery_identity
    except BaseException as original_error:
        if signal_guard is not None:
            signal_guard.begin_cleanup()
        cleanup_error: BaseException | None = None
        if partial_recovery_identity is None and target_fd >= 0:
            try:
                opened_partial = os.stat(target_fd)
                if (
                    not stat.S_ISREG(opened_partial.st_mode)
                    or opened_partial.st_uid != os.geteuid()
                    or stat.S_IMODE(opened_partial.st_mode) != 0o600
                    or opened_partial.st_nlink != 1
                ):
                    raise ValueError(
                        f"partial retention recovery descriptor is invalid: {recovery.name}"
                    )
                partial_recovery_identity = file_binding_identity(opened_partial)
            except (OSError, ValueError) as exc:
                cleanup_error = exc
        if partial_recovery_identity is not None and not recovery_registered:
            try:
                unlink_file_with_binding_identity(
                    recovery,
                    partial_recovery_identity,
                    parent_fd=recovery_directory_fd,
                    signal_guard=signal_guard,
                )
            except (OSError, ValueError) as exc:
                cleanup_error = exc
        if cleanup_error is not None:
            raise RuntimeError(
                f"partial retention recovery cleanup failed: {recovery.name}"
            ) from original_error
        raise
    finally:
        if signal_guard is None:
            if target_fd >= 0:
                os.close(target_fd)
            if source_fd >= 0:
                os.close(source_fd)
            if owned_recovery_parent_fd >= 0:
                os.close(owned_recovery_parent_fd)
            if owned_source_parent_fd >= 0:
                os.close(owned_source_parent_fd)
        else:
            with signal_guard.blocked():
                if target_fd >= 0:
                    os.close(target_fd)
                if source_fd >= 0:
                    os.close(source_fd)
                if owned_recovery_parent_fd >= 0:
                    os.close(owned_recovery_parent_fd)
                if owned_source_parent_fd >= 0:
                    os.close(owned_source_parent_fd)


def image_file_for_report(upload_dir: Path, image_path: str) -> Path:
    prefix = "/uploads/"
    if not image_path.startswith(prefix):
        raise ValueError(f"unsafe image_path outside {prefix}: {image_path!r}")
    relative = image_path.removeprefix(prefix)
    if not relative or Path(relative).name != relative:
        raise ValueError(f"unsafe nested image_path: {image_path!r}")
    resolved_upload_dir = upload_dir.resolve(strict=True)
    return resolved_upload_dir / relative


def _canonical_database_datetime(value: Any) -> str:
    if not isinstance(value, datetime):
        raise ValueError("retention database timestamp is unavailable")
    normalized = value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)
    return normalized.isoformat().replace("+00:00", "Z")


def _base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _identity_payload(metadata: os.stat_result) -> dict[str, int]:
    return {field: int(getattr(metadata, field)) for field in FILE_IDENTITY_FIELDS}


def _identity_tuple(payload: Any) -> tuple[int, ...]:
    if not isinstance(payload, dict) or set(payload) != set(FILE_IDENTITY_FIELDS):
        raise RuntimeError("retention recovery file identity shape changed")
    values: list[int] = []
    for field in FILE_IDENTITY_FIELDS:
        value = payload[field]
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise RuntimeError("retention recovery file identity value changed")
        values.append(value)
    return tuple(values)


def _database_binding(report: Any, image_object: Any) -> dict[str, Any]:
    report_id = str(report.id)
    match = ENCRYPTED_STORAGE_NAME_PATTERN.fullmatch(str(image_object.storage_name))
    nonce = image_object.nonce
    if (
        match is None
        or match.group("report_id") != report_id
        or image_object.envelope_version != 1
        or image_object.algorithm != "AES-256-GCM"
        or image_object.aad_version != 1
        or not isinstance(image_object.key_id, str)
        or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", image_object.key_id) is None
        or not isinstance(nonce, bytes)
        or len(nonce) != 12
        or not isinstance(image_object.plaintext_sha256, str)
        or SHA256_PATTERN.fullmatch(image_object.plaintext_sha256) is None
        or not isinstance(image_object.envelope_sha256, str)
        or SHA256_PATTERN.fullmatch(image_object.envelope_sha256) is None
        or not isinstance(image_object.plaintext_size, int)
        or isinstance(image_object.plaintext_size, bool)
        or image_object.plaintext_size <= 0
        or not isinstance(image_object.envelope_size, int)
        or isinstance(image_object.envelope_size, bool)
        or image_object.envelope_size > MAX_ENCRYPTED_REPORT_OBJECT_BYTES
        or image_object.envelope_size <= image_object.plaintext_size + 16
        or not isinstance(image_object.content_type, str)
        or image_object.content_type not in {"image/jpeg", "image/png", "image/webp"}
    ):
        raise RuntimeError(
            f"report {report_id} has invalid encrypted report_image_objects metadata"
        )
    return {
        "report_id": report_id,
        "report_created_at": _canonical_database_datetime(report.created_at),
        "storage_name": image_object.storage_name,
        "envelope_version": image_object.envelope_version,
        "algorithm": image_object.algorithm,
        "aad_version": image_object.aad_version,
        "key_id": image_object.key_id,
        "nonce_b64url": _base64url(nonce),
        "plaintext_sha256": image_object.plaintext_sha256,
        "plaintext_size": image_object.plaintext_size,
        "envelope_sha256": image_object.envelope_sha256,
        "envelope_size": image_object.envelope_size,
        "content_type": image_object.content_type,
    }


def _inspect_encrypted_object(
    upload_fd: int,
    storage_name: str,
    *,
    expected_size: int,
    expected_sha256: str,
    expected_binding: dict[str, Any] | None = None,
    expected_mode: int = 0o600,
    expected_gid: int | None = None,
) -> tuple[int, ...]:
    from backend.app.services.report_image_crypto import (
        ReportImageCryptoError,
        parse_report_image_envelope,
    )

    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        anchored = os.stat(storage_name, dir_fd=upload_fd, follow_symlinks=False)
        descriptor = os.open(storage_name, flags, dir_fd=upload_fd)
    except OSError as exc:
        raise RuntimeError(f"encrypted report object is unavailable: {storage_name}") from exc
    try:
        opened = os.fstat(descriptor)
        if (
            not _descriptor_acl_is_absent(upload_fd)
            or not _descriptor_acl_is_absent(descriptor)
            or not stat.S_ISREG(anchored.st_mode)
            or not stat.S_ISREG(opened.st_mode)
            or anchored.st_uid != os.geteuid()
            or opened.st_uid != os.geteuid()
            or (expected_gid is not None and anchored.st_gid != expected_gid)
            or (expected_gid is not None and opened.st_gid != expected_gid)
            or stat.S_IMODE(anchored.st_mode) != expected_mode
            or stat.S_IMODE(opened.st_mode) != expected_mode
            or anchored.st_nlink != 1
            or file_identity(anchored) != file_identity(opened)
            or opened.st_size != expected_size
        ):
            raise RuntimeError(f"encrypted report object identity changed: {storage_name}")
        digest = hashlib.sha256()
        chunks: list[bytes] = []
        remaining = expected_size
        while remaining:
            chunk = os.read(descriptor, min(1024 * 1024, remaining))
            if not chunk:
                raise RuntimeError(f"encrypted report object is truncated: {storage_name}")
            digest.update(chunk)
            chunks.append(chunk)
            remaining -= len(chunk)
        if os.read(descriptor, 1):
            raise RuntimeError(f"encrypted report object size changed: {storage_name}")
        final = os.fstat(descriptor)
        if (
            file_identity(final) != file_identity(opened)
            or not _descriptor_acl_is_absent(upload_fd)
            or not _descriptor_acl_is_absent(descriptor)
        ):
            raise RuntimeError(f"encrypted report object changed while hashing: {storage_name}")
        if digest.hexdigest() != expected_sha256:
            raise RuntimeError(f"encrypted report object digest changed: {storage_name}")
        try:
            parsed = parse_report_image_envelope(
                b"".join(chunks),
                expected_report_id=(
                    uuid.UUID(expected_binding["report_id"])
                    if expected_binding is not None
                    else None
                ),
            )
        except (ReportImageCryptoError, TypeError, ValueError) as exc:
            raise RuntimeError(f"encrypted report object envelope is invalid: {storage_name}") from exc
        if expected_binding is not None and (
            parsed.key_id != expected_binding["key_id"]
            or parsed.nonce != base64.urlsafe_b64decode(
                expected_binding["nonce_b64url"]
                + "=" * (-len(expected_binding["nonce_b64url"]) % 4)
            )
            or parsed.content_type != expected_binding["content_type"]
            or parsed.plaintext_length != expected_binding["plaintext_size"]
            or parsed.plaintext_sha256 != expected_binding["plaintext_sha256"]
        ):
            raise RuntimeError(
                f"encrypted report object header does not match database metadata: {storage_name}"
            )
        return file_identity(final)
    finally:
        os.close(descriptor)


def _recovery_objects_sha256(objects: list[dict[str, Any]]) -> str:
    encoded = json.dumps(objects, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _recovery_manifest(
    run_id: str,
    state: str,
    objects: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "schema_version": RETENTION_RECOVERY_SCHEMA,
        "run_id": run_id,
        "state": state,
        "objects": objects,
        "objects_sha256": _recovery_objects_sha256(objects),
    }


def _validate_recovery_object(
    entry: Any,
    *,
    expected_source_mode: int = 0o600,
    expected_source_gid: int | None = None,
) -> dict[str, Any]:
    if not isinstance(entry, dict) or set(entry) != RETENTION_RECOVERY_OBJECT_FIELDS:
        raise RuntimeError("retention recovery object shape changed")
    report_id = entry["report_id"]
    try:
        parsed_report_id = uuid.UUID(report_id)
    except (AttributeError, TypeError, ValueError) as exc:
        raise RuntimeError("retention recovery report id changed") from exc
    storage_name = entry["storage_name"]
    match = ENCRYPTED_STORAGE_NAME_PATTERN.fullmatch(storage_name) if isinstance(storage_name, str) else None
    if (
        str(parsed_report_id) != report_id
        or match is None
        or match.group("report_id") != report_id
        or entry["recovery_name"] != f"{storage_name}.recovery"
        or entry["envelope_version"] != 1
        or entry["algorithm"] != "AES-256-GCM"
        or entry["aad_version"] != 1
        or not isinstance(entry["key_id"], str)
        or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", entry["key_id"]) is None
        or not isinstance(entry["nonce_b64url"], str)
        or "=" in entry["nonce_b64url"]
        or not isinstance(entry["plaintext_sha256"], str)
        or SHA256_PATTERN.fullmatch(entry["plaintext_sha256"]) is None
        or not isinstance(entry["envelope_sha256"], str)
        or SHA256_PATTERN.fullmatch(entry["envelope_sha256"]) is None
        or not isinstance(entry["plaintext_size"], int)
        or isinstance(entry["plaintext_size"], bool)
        or entry["plaintext_size"] <= 0
        or not isinstance(entry["envelope_size"], int)
        or isinstance(entry["envelope_size"], bool)
        or entry["envelope_size"] > MAX_ENCRYPTED_REPORT_OBJECT_BYTES
        or entry["envelope_size"] <= entry["plaintext_size"] + 16
        or not isinstance(entry["content_type"], str)
        or entry["content_type"] not in {"image/jpeg", "image/png", "image/webp"}
    ):
        raise RuntimeError("retention recovery encrypted-object metadata changed")
    try:
        nonce = base64.urlsafe_b64decode(entry["nonce_b64url"] + "=" * (-len(entry["nonce_b64url"]) % 4))
    except (ValueError, TypeError) as exc:
        raise RuntimeError("retention recovery nonce changed") from exc
    if len(nonce) != 12 or _base64url(nonce) != entry["nonce_b64url"]:
        raise RuntimeError("retention recovery nonce changed")
    try:
        report_created_at = parse_datetime(entry["report_created_at"])
    except (AttributeError, TypeError, ValueError) as exc:
        raise RuntimeError("retention recovery report timestamp changed") from exc
    if _canonical_database_datetime(report_created_at) != entry["report_created_at"]:
        raise RuntimeError("retention recovery report timestamp is not canonical UTC")
    source_identity = _identity_tuple(entry["source_identity"])
    if (
        not stat.S_ISREG(source_identity[3])
        or source_identity[2] != os.geteuid()
        or stat.S_IMODE(source_identity[3]) != expected_source_mode
        or source_identity[4] != entry["envelope_size"]
        or source_identity[7] != 1
    ):
        raise RuntimeError("retention recovery source identity is unsafe")
    recovery_identity = entry["recovery_identity"]
    recovery_sha256 = entry["recovery_sha256"]
    if recovery_identity is None:
        if recovery_sha256 is not None:
            raise RuntimeError("retention recovery copy metadata is incomplete")
    else:
        parsed_recovery_identity = _identity_tuple(recovery_identity)
        if (
            not stat.S_ISREG(parsed_recovery_identity[3])
            or parsed_recovery_identity[2] != os.geteuid()
            or stat.S_IMODE(parsed_recovery_identity[3]) != 0o600
            or parsed_recovery_identity[4] != entry["envelope_size"]
            or parsed_recovery_identity[7] != 1
            or recovery_sha256 != entry["envelope_sha256"]
        ):
            raise RuntimeError("retention recovery copy identity is unsafe")
    return entry


def _validate_recovery_manifest(
    payload: Any,
    run_id: str,
    *,
    expected_source_mode: int = 0o600,
    expected_source_gid: int | None = None,
) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise RuntimeError("retention recovery manifest is not an object")
    if payload.get("schema_version") != RETENTION_RECOVERY_SCHEMA:
        raise RuntimeError("legacy retention recovery journal requires explicit offline recovery")
    if set(payload) != {"schema_version", "run_id", "state", "objects", "objects_sha256"}:
        raise RuntimeError("retention recovery manifest shape changed")
    objects = payload["objects"]
    if (
        payload["run_id"] != run_id
        or payload["state"] not in RETENTION_RECOVERY_STATES
        or not isinstance(objects, list)
        or not 1 <= len(objects) <= MAX_APPLY_BATCH_SIZE
        or payload["objects_sha256"] != _recovery_objects_sha256(objects)
    ):
        raise RuntimeError("retention recovery manifest binding changed")
    validated = [
        _validate_recovery_object(
            entry,
            expected_source_mode=expected_source_mode,
            expected_source_gid=expected_source_gid,
        )
        for entry in objects
    ]
    report_ids = [entry["report_id"] for entry in validated]
    storage_names = [entry["storage_name"] for entry in validated]
    recovery_names = [entry["recovery_name"] for entry in validated]
    if (
        report_ids != sorted(report_ids)
        or len(report_ids) != len(set(report_ids))
        or len(storage_names) != len(set(storage_names))
        or len(recovery_names) != len(set(recovery_names))
    ):
        raise RuntimeError("retention recovery object order or uniqueness changed")
    if payload["state"] in {"PRECOMMIT_READY", "DATABASE_COMMITTING"} and any(
        entry["recovery_identity"] is None for entry in validated
    ):
        raise RuntimeError("retention recovery manifest became commit-ready without durable copies")
    return payload


def _read_recovery_manifest(
    run_fd: int,
    run_id: str,
    *,
    expected_source_mode: int = 0o600,
    expected_source_gid: int | None = None,
) -> dict[str, Any]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        anchored = os.stat(
            RETENTION_RECOVERY_MANIFEST_NAME,
            dir_fd=run_fd,
            follow_symlinks=False,
        )
        descriptor = os.open(RETENTION_RECOVERY_MANIFEST_NAME, flags, dir_fd=run_fd)
    except OSError as exc:
        raise RuntimeError("retention recovery manifest is unavailable") from exc
    try:
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(anchored.st_mode)
            or file_identity(anchored) != file_identity(opened)
            or opened.st_uid != os.geteuid()
            or stat.S_IMODE(opened.st_mode) != 0o600
            or opened.st_nlink != 1
            or not 0 < opened.st_size <= MAX_RETENTION_RECOVERY_MANIFEST_BYTES
        ):
            raise RuntimeError("retention recovery manifest identity changed")
        chunks: list[bytes] = []
        remaining = opened.st_size
        while remaining:
            chunk = os.read(descriptor, min(1024 * 1024, remaining))
            if not chunk:
                raise RuntimeError("retention recovery manifest is truncated")
            chunks.append(chunk)
            remaining -= len(chunk)
        final = os.fstat(descriptor)
        if file_identity(final) != file_identity(opened):
            raise RuntimeError("retention recovery manifest changed while reading")
        try:
            payload = json.loads(b"".join(chunks).decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as exc:
            raise RuntimeError("retention recovery manifest is invalid JSON") from exc
        return _validate_recovery_manifest(
            payload,
            run_id,
            expected_source_mode=expected_source_mode,
            expected_source_gid=expected_source_gid,
        )
    finally:
        os.close(descriptor)


def _write_recovery_manifest(
    run_fd: int,
    payload: dict[str, Any],
    *,
    signal_guard: RetentionSignalGuard,
    expected_source_mode: int = 0o600,
    expected_source_gid: int | None = None,
) -> None:
    validated = _validate_recovery_manifest(
        payload,
        str(payload.get("run_id", "")),
        expected_source_mode=expected_source_mode,
        expected_source_gid=expected_source_gid,
    )
    content = (json.dumps(validated, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    if len(content) > MAX_RETENTION_RECOVERY_MANIFEST_BYTES:
        raise RuntimeError("retention recovery manifest is too large")
    flags = (
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    with signal_guard.blocked():
        try:
            descriptor = os.open(RETENTION_RECOVERY_TEMP_NAME, flags, 0o600, dir_fd=run_fd)
        except FileExistsError as exc:
            raise RuntimeError("retention recovery journal has an unresolved temporary file") from exc
        try:
            remaining = memoryview(content)
            while remaining:
                written = os.write(descriptor, remaining)
                if written <= 0:
                    raise OSError("retention recovery manifest write made no progress")
                remaining = remaining[written:]
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        try:
            current = os.stat(
                RETENTION_RECOVERY_MANIFEST_NAME,
                dir_fd=run_fd,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            _rename_noreplace(
                run_fd,
                RETENTION_RECOVERY_TEMP_NAME,
                RETENTION_RECOVERY_MANIFEST_NAME,
            )
        else:
            if (
                not stat.S_ISREG(current.st_mode)
                or current.st_uid != os.geteuid()
                or stat.S_IMODE(current.st_mode) != 0o600
                or current.st_nlink != 1
            ):
                raise RuntimeError("retention recovery manifest identity changed before update")
            os.replace(
                RETENTION_RECOVERY_TEMP_NAME,
                RETENTION_RECOVERY_MANIFEST_NAME,
                src_dir_fd=run_fd,
                dst_dir_fd=run_fd,
            )
        os.fsync(run_fd)


def _unlink_recovery_file(
    directory_fd: int,
    name: str,
    *,
    expected_identity: tuple[int, ...] | None,
    expected_size: int | None = None,
    expected_sha256: str | None = None,
    expected_binding: dict[str, Any] | None = None,
    expected_mode: int = 0o600,
    expected_gid: int | None = None,
) -> bool:
    try:
        metadata = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    except FileNotFoundError:
        return False
    if (
        not stat.S_ISREG(metadata.st_mode)
        or metadata.st_uid != os.geteuid()
        or (expected_gid is not None and metadata.st_gid != expected_gid)
        or stat.S_IMODE(metadata.st_mode) != expected_mode
        or metadata.st_nlink != 1
        or (expected_identity is not None and file_identity(metadata) != expected_identity)
        or (expected_size is not None and metadata.st_size > expected_size)
    ):
        raise RuntimeError(f"retention recovery file identity drifted: {name}")
    if expected_sha256 is not None:
        observed_identity = _inspect_encrypted_object(
            directory_fd,
            name,
            expected_size=metadata.st_size,
            expected_sha256=expected_sha256,
            expected_binding=expected_binding,
            expected_mode=expected_mode,
            expected_gid=expected_gid,
        )
        if expected_identity is not None and observed_identity != expected_identity:
            raise RuntimeError(f"retention recovery file identity drifted: {name}")
    os.unlink(name, dir_fd=directory_fd)
    os.fsync(directory_fd)
    return True


def _load_recovery_database_state(
    engine: Any,
    report_ids: list[str],
) -> dict[str, dict[str, Any]]:
    from sqlalchemy import select
    from sqlalchemy.orm import Session

    from backend.app.models import Report, ReportImageObject
    from backend.app.services.report_storage import (
        lock_report_storage_reconciliation_transaction,
    )

    parsed_ids = [uuid.UUID(report_id) for report_id in report_ids]
    state = {
        report_id: {"report_exists": False, "image_object": None}
        for report_id in report_ids
    }
    with Session(engine) as session:
        lock_report_storage_reconciliation_transaction(session)
        reports = list(session.scalars(select(Report).where(Report.id.in_(parsed_ids))).all())
        image_objects = list(
            session.scalars(
                select(ReportImageObject).where(ReportImageObject.report_id.in_(parsed_ids))
            ).all()
        )
    objects_by_id = {str(image_object.report_id): image_object for image_object in image_objects}
    for report in reports:
        report_id = str(report.id)
        image_object = objects_by_id.get(report_id)
        state[report_id] = {
            "report_exists": True,
            "image_object": (
                _database_binding(report, image_object) if image_object is not None else None
            ),
        }
    for report_id, image_object in objects_by_id.items():
        if not state[report_id]["report_exists"]:
            state[report_id]["image_object"] = {"orphaned": image_object.storage_name}
    return state


def _classify_recovery_database_state(
    objects: list[dict[str, Any]],
    database_state: dict[str, dict[str, Any]],
) -> str:
    outcomes: list[str] = []
    for entry in objects:
        report_id = entry["report_id"]
        state = database_state.get(report_id)
        expected = {field: entry[field] for field in RETENTION_RECOVERY_DATABASE_FIELDS}
        if state == {"report_exists": False, "image_object": None}:
            outcomes.append("absent")
        elif (
            isinstance(state, dict)
            and state.get("report_exists") is True
            and state.get("image_object") == expected
        ):
            outcomes.append("present")
        else:
            raise RuntimeError(
                f"retention recovery database row or encrypted metadata drifted: {report_id}"
            )
    if outcomes and all(outcome == "present" for outcome in outcomes):
        return "precommit"
    if outcomes and all(outcome == "absent" for outcome in outcomes):
        return "postcommit"
    raise RuntimeError("retention recovery database state is mixed; manual recovery is required")


def _remove_reconciled_run(
    upload_fd: int,
    quarantine_root_fd: int,
    run_fd: int,
    run_id: str,
) -> None:
    manifest_metadata = os.stat(
        RETENTION_RECOVERY_MANIFEST_NAME,
        dir_fd=run_fd,
        follow_symlinks=False,
    )
    _unlink_recovery_file(
        run_fd,
        RETENTION_RECOVERY_MANIFEST_NAME,
        expected_identity=file_identity(manifest_metadata),
    )
    remove_private_quarantine_directories(upload_fd, quarantine_root_fd, run_fd, run_id)


def _reconcile_retention_run(
    upload_fd: int,
    quarantine_root_fd: int,
    run_id: str,
    *,
    database_state_loader: Any,
    signal_guard: RetentionSignalGuard,
    expected_source_mode: int = 0o600,
    expected_source_gid: int | None = None,
    fault_hook: Callable[[str], None] | None = None,
) -> dict[str, Any] | None:
    run_fd = _open_private_child_directory(
        quarantine_root_fd,
        run_id,
        create=False,
        exclusive=False,
    )
    try:
        names = set(os.listdir(run_fd))
        if RETENTION_RECOVERY_MANIFEST_NAME not in names:
            if names == set():
                os.rmdir(run_id, dir_fd=quarantine_root_fd)
                os.fsync(quarantine_root_fd)
                return None
            if names == {RETENTION_RECOVERY_TEMP_NAME}:
                _unlink_recovery_file(
                    run_fd,
                    RETENTION_RECOVERY_TEMP_NAME,
                    expected_identity=None,
                    expected_size=MAX_RETENTION_RECOVERY_MANIFEST_BYTES,
                )
                os.rmdir(run_id, dir_fd=quarantine_root_fd)
                os.fsync(quarantine_root_fd)
                return None
            raise RuntimeError(
                f"legacy retention quarantine requires explicit offline recovery: {run_id}"
            )
        if RETENTION_RECOVERY_TEMP_NAME in names:
            _unlink_recovery_file(
                run_fd,
                RETENTION_RECOVERY_TEMP_NAME,
                expected_identity=None,
                expected_size=MAX_RETENTION_RECOVERY_MANIFEST_BYTES,
            )
            names.remove(RETENTION_RECOVERY_TEMP_NAME)
        manifest = _read_recovery_manifest(
            run_fd,
            run_id,
            expected_source_mode=expected_source_mode,
            expected_source_gid=expected_source_gid,
        )
        objects = manifest["objects"]
        expected_names = {
            RETENTION_RECOVERY_MANIFEST_NAME,
            *(entry["recovery_name"] for entry in objects),
        }
        if not names.issubset(expected_names):
            raise RuntimeError(f"retention quarantine contains an unbound file: {run_id}")
        database_state = database_state_loader([entry["report_id"] for entry in objects])
        classification = _classify_recovery_database_state(objects, database_state)
        if classification == "precommit":
            for entry in objects:
                observed = _inspect_encrypted_object(
                    upload_fd,
                    entry["storage_name"],
                    expected_size=entry["envelope_size"],
                    expected_sha256=entry["envelope_sha256"],
                    expected_binding=entry,
                    expected_mode=expected_source_mode,
                    expected_gid=expected_source_gid,
                )
                if observed != _identity_tuple(entry["source_identity"]):
                    raise RuntimeError(
                        f"retention source identity drifted before commit: {entry['storage_name']}"
                    )
                recovery_identity = (
                    _identity_tuple(entry["recovery_identity"])
                    if entry["recovery_identity"] is not None
                    else None
                )
                _unlink_recovery_file(
                    run_fd,
                    entry["recovery_name"],
                    expected_identity=recovery_identity,
                    expected_size=entry["envelope_size"],
                    expected_sha256=(
                        entry["recovery_sha256"] if recovery_identity is not None else None
                    ),
                    expected_binding=entry if recovery_identity is not None else None,
                )
            terminal_state = "RECONCILED_PRECOMMIT_ABORTED"
        else:
            for entry in objects:
                try:
                    observed = _inspect_encrypted_object(
                        upload_fd,
                        entry["storage_name"],
                        expected_size=entry["envelope_size"],
                        expected_sha256=entry["envelope_sha256"],
                        expected_binding=entry,
                        expected_mode=expected_source_mode,
                        expected_gid=expected_source_gid,
                    )
                except RuntimeError as exc:
                    try:
                        os.stat(entry["storage_name"], dir_fd=upload_fd, follow_symlinks=False)
                    except FileNotFoundError:
                        observed = None
                    else:
                        raise exc
                if observed is not None:
                    if observed != _identity_tuple(entry["source_identity"]):
                        raise RuntimeError(
                            f"retention source identity drifted after commit: {entry['storage_name']}"
                        )
                    _unlink_recovery_file(
                        upload_fd,
                        entry["storage_name"],
                        expected_identity=observed,
                        expected_size=entry["envelope_size"],
                        expected_sha256=entry["envelope_sha256"],
                        expected_binding=entry,
                        expected_mode=expected_source_mode,
                        expected_gid=expected_source_gid,
                    )
                recovery_identity = (
                    _identity_tuple(entry["recovery_identity"])
                    if entry["recovery_identity"] is not None
                    else None
                )
                _unlink_recovery_file(
                    run_fd,
                    entry["recovery_name"],
                    expected_identity=recovery_identity,
                    expected_size=entry["envelope_size"],
                    expected_sha256=(
                        entry["recovery_sha256"] if recovery_identity is not None else None
                    ),
                    expected_binding=entry if recovery_identity is not None else None,
                )
            terminal_state = "RECONCILED_POSTCOMMIT_COMPLETED"
        terminal = _recovery_manifest(run_id, terminal_state, objects)
        _write_recovery_manifest(
            run_fd,
            terminal,
            signal_guard=signal_guard,
            expected_source_mode=expected_source_mode,
            expected_source_gid=expected_source_gid,
        )
        if fault_hook is not None:
            fault_hook("P7")
        result = {
            "run_id": run_id,
            "status": terminal_state,
            "report_ids": [entry["report_id"] for entry in objects],
        }
        _remove_reconciled_run(upload_fd, quarantine_root_fd, run_fd, run_id)
        if fault_hook is not None:
            fault_hook("P7_AFTER_RUN_REMOVAL")
        return result
    finally:
        os.close(run_fd)


def reconcile_retention_quarantine(
    upload_dir: Path,
    *,
    database_state_loader: Any,
    signal_guard: RetentionSignalGuard,
    expected_upload_group_gid: int | None = None,
    fault_hook: Callable[[str], None] | None = None,
) -> list[dict[str, Any]]:
    upload_fd = _open_canonical_directory(upload_dir)
    quarantine_root_fd = -1
    try:
        _directory_mode, source_mode = _upload_file_contract(
            expected_upload_group_gid
        )
        opened_upload = os.fstat(upload_fd)
        anchored_upload = os.stat(upload_dir, follow_symlinks=False)
        _validate_upload_root_metadata(
            opened_upload,
            expected_group_gid=expected_upload_group_gid,
        )
        if (
            not _descriptor_acl_is_absent(upload_fd)
            or (opened_upload.st_dev, opened_upload.st_ino)
            != (anchored_upload.st_dev, anchored_upload.st_ino)
        ):
            raise ValueError("retention upload root identity changed")
        try:
            quarantine_root_fd = _open_private_child_directory(
                upload_fd,
                ".retention-quarantine",
                create=False,
                exclusive=False,
            )
        except FileNotFoundError:
            return []
        run_ids = sorted(os.listdir(quarantine_root_fd))
        if len(run_ids) > MAX_RETENTION_RECOVERY_RUNS:
            raise RuntimeError("too many pending retention recovery runs")
        if any(re.fullmatch(r"[0-9a-f]{32}", run_id) is None for run_id in run_ids):
            raise RuntimeError("retention quarantine contains an invalid run directory")
        results: list[dict[str, Any]] = []
        for run_id in run_ids:
            result = _reconcile_retention_run(
                upload_fd,
                quarantine_root_fd,
                run_id,
                database_state_loader=database_state_loader,
                signal_guard=signal_guard,
                expected_source_mode=source_mode,
                expected_source_gid=expected_upload_group_gid,
                fault_hook=fault_hook,
            )
            if result is not None:
                results.append(result)
        try:
            os.rmdir(".retention-quarantine", dir_fd=upload_fd)
        except OSError as exc:
            if exc.errno not in {errno.ENOENT, errno.ENOTEMPTY}:
                raise
        else:
            os.fsync(upload_fd)
        return results
    finally:
        if quarantine_root_fd >= 0:
            os.close(quarantine_root_fd)
        os.close(upload_fd)


def candidate_digest(candidates: list[dict[str, Any]]) -> str:
    candidate_ids = sorted(str(candidate["id"]) for candidate in candidates)
    encoded = json.dumps(candidate_ids, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _apply_database_retention_locked(
    *,
    database_url: str,
    upload_dir: Path,
    as_of: datetime,
    manifest_path: Path,
    backup_manifest_path: Path,
    restore_receipt_path: Path,
    trusted_backup_signer_fingerprint: str,
    trusted_restore_signer_fingerprint: str,
    maintenance_lock_path: Path,
    maintenance_lock: dict[str, Any],
    signal_guard: RetentionSignalGuard,
    upload_backup_reader_group_gid: int | None = None,
    actor_id: str = "system",
    authorized_admin_id: str | None = None,
    authorized_session_id: str | None = None,
    batch_size: int = DEFAULT_APPLY_BATCH_SIZE,
    fault_hook: Callable[[str], None] | None = None,
) -> dict[str, Any]:
    from sqlalchemy import select
    from sqlalchemy.orm import Session

    from backend.app.models import Report, ReportImageObject
    from backend.app.services.report_storage import (
        lock_report_storage_reconciliation_transaction,
    )

    run_id = uuid.uuid4().hex
    actor_id = validated_actor_id(actor_id)
    started_at = datetime.now(UTC)
    if not upload_dir.is_absolute():
        raise ValueError("retention upload root must be absolute")
    absolute_upload_dir = upload_dir.expanduser()
    resolved_upload_dir = absolute_upload_dir.resolve(strict=True)
    if resolved_upload_dir != absolute_upload_dir:
        raise ValueError("retention upload root must be a canonical real directory")
    initial_upload_metadata = os.lstat(resolved_upload_dir)
    _validate_upload_root_metadata(
        initial_upload_metadata,
        expected_group_gid=upload_backup_reader_group_gid,
    )
    initial_upload_fd = _open_canonical_directory(resolved_upload_dir)
    try:
        opened_initial_upload = os.fstat(initial_upload_fd)
        if (
            (opened_initial_upload.st_dev, opened_initial_upload.st_ino)
            != (initial_upload_metadata.st_dev, initial_upload_metadata.st_ino)
            or not _descriptor_acl_is_absent(initial_upload_fd)
        ):
            raise ValueError("retention upload root identity or ACL differs")
    finally:
        os.close(initial_upload_fd)
    _upload_directory_mode, source_file_mode = _upload_file_contract(
        upload_backup_reader_group_gid
    )
    expected_upload_identity = (
        initial_upload_metadata.st_dev,
        initial_upload_metadata.st_ino,
        initial_upload_metadata.st_uid,
        initial_upload_metadata.st_mode,
    )
    quarantine_dir = resolved_upload_dir / ".retention-quarantine" / run_id
    upload_fd = -1
    quarantine_root_fd = -1
    quarantine_run_fd = -1
    engine: Any | None = None
    manifest: dict[str, Any] = {}
    manifest_publisher: RetentionManifestPublisher | None = None
    database_committed = False
    recovery_journal_started = False
    reconciled_runs: list[dict[str, Any]] = []

    try:
        engine = create_retention_database_engine(database_url)
        database_state_loader = lambda report_ids: _load_recovery_database_state(
            engine,
            report_ids,
        )
        # The maintenance lock is already held. Finish every ambiguous prior
        # run before selecting a new batch, so two retention transactions can
        # never share or overwrite recovery state.
        reconciled_runs.extend(
            reconcile_retention_quarantine(
                resolved_upload_dir,
                database_state_loader=database_state_loader,
                signal_guard=signal_guard,
                expected_upload_group_gid=upload_backup_reader_group_gid,
            )
        )

        database_identity = database_identity_sha256(database_url)
        upload_root_identity = path_identity_sha256(resolved_upload_dir)
        backup = validate_predelete_backup(
            backup_manifest_path,
            trusted_signer_fingerprint=trusted_backup_signer_fingerprint,
            database_identity=database_identity,
            upload_root_identity=upload_root_identity,
            maintenance_lock_identity=maintenance_lock["identity_sha256"],
            maintenance_lock_device_inode=maintenance_lock["device_inode"],
            started_at=started_at,
        )
        restore = validate_predelete_restore_receipt(
            restore_receipt_path,
            trusted_signer_fingerprint=trusted_restore_signer_fingerprint,
            backup=backup,
            database_identity=database_identity,
            upload_root_identity=upload_root_identity,
            started_at=started_at,
        )
        manifest_publisher = RetentionManifestPublisher(
            manifest_path,
            upload_dir=resolved_upload_dir,
            backup_manifest_path=backup_manifest_path,
            restore_receipt_path=restore_receipt_path,
            maintenance_lock_path=maintenance_lock_path,
            signal_guard=signal_guard,
        )
        manifest = {
            "schema_version": "walksafe.report_retention_apply.v1",
            "run_id": run_id,
            "actor_id": actor_id,
            "authorized_admin_id": authorized_admin_id,
            "authorized_session_id": authorized_session_id,
            "started_at": started_at.isoformat().replace("+00:00", "Z"),
            "as_of": as_of.isoformat().replace("+00:00", "Z"),
            "database_identity_sha256": database_identity,
            "upload_root_identity_sha256": upload_root_identity,
            "predelete_backup_run_id": backup["run_id"],
            "predelete_backup_created_at": backup["created_at"].isoformat(),
            "predelete_backup_artifacts_sha256": backup["artifacts_sha256"],
            "predelete_backup_signer_fingerprint": backup["verified_signer_fingerprint"],
            "predelete_restore_restored_at": restore["restored_at"].isoformat(),
            "predelete_restore_receipt_sha256": restore["receipt_sha256"],
            "predelete_restore_signer_fingerprint": restore["verified_signer_fingerprint"],
            "maintenance_lock_identity_sha256": maintenance_lock["identity_sha256"],
            "maintenance_lock_device_inode": maintenance_lock["device_inode"],
            "maintenance_lock_acquired_at": maintenance_lock["acquired_at"].isoformat(),
            "batch_size": batch_size,
            "status": "planning",
            "destructive_action": True,
            "candidates": [],
            "candidate_ids_sha256": candidate_digest([]),
            "images": [],
            "reconciled_runs": list(reconciled_runs),
        }
        write_json_atomic(manifest_path, manifest, publisher=manifest_publisher)
        if fault_hook is not None:
            fault_hook("P0")

        with Session(engine) as session:
            lock_report_storage_reconciliation_transaction(session)
            candidate_statement = database_expired_statement(
                Report,
                as_of=as_of,
                limit=batch_size,
                lock_rows=True,
            )
            reports = list(session.scalars(candidate_statement).all())
            candidates_with_reports = [
                (candidate, report)
                for report in reports
                if (candidate := candidate_for_row(report_row(report), as_of=as_of)) is not None
            ]
            candidates = [dict(candidate, would_delete=True) for candidate, _report in candidates_with_reports]
            candidate_report_ids = [report.id for _candidate, report in candidates_with_reports]
            image_objects = list(
                session.scalars(
                    select(ReportImageObject)
                    .where(ReportImageObject.report_id.in_(candidate_report_ids))
                    .with_for_update()
                ).all()
            ) if candidate_report_ids else []
            image_objects_by_id = {
                str(image_object.report_id): image_object for image_object in image_objects
            }
            if len(image_objects_by_id) != len(candidates_with_reports):
                raise RuntimeError(
                    "expired report is missing authoritative encrypted report_image_objects metadata"
                )

            manifest["status"] = "planned"
            manifest["candidates"] = candidates
            manifest["batch_limit_reached"] = len(reports) == batch_size
            manifest["candidate_ids_sha256"] = candidate_digest(candidates)
            write_json_atomic(manifest_path, manifest, publisher=manifest_publisher)

            if candidates_with_reports:
                with signal_guard.blocked():
                    (
                        quarantine_dir,
                        upload_fd,
                        quarantine_root_fd,
                        quarantine_run_fd,
                    ) = prepare_private_quarantine(
                        upload_dir,
                        run_id,
                        expected_upload_identity=expected_upload_identity,
                        expected_upload_group_gid=upload_backup_reader_group_gid,
                    )
                if fault_hook is not None:
                    fault_hook("P1")
                recovery_objects: list[dict[str, Any]] = []
                for _candidate, report in sorted(
                    candidates_with_reports,
                    key=lambda item: str(item[1].id),
                ):
                    image_object = image_objects_by_id[str(report.id)]
                    entry = _database_binding(report, image_object)
                    source_identity = _inspect_encrypted_object(
                        upload_fd,
                        entry["storage_name"],
                        expected_size=entry["envelope_size"],
                        expected_sha256=entry["envelope_sha256"],
                        expected_binding=entry,
                        expected_mode=source_file_mode,
                        expected_gid=upload_backup_reader_group_gid,
                    )
                    entry.update(
                        {
                            "source_identity": {
                                field: value
                                for field, value in zip(FILE_IDENTITY_FIELDS, source_identity)
                            },
                            "recovery_name": f"{entry['storage_name']}.recovery",
                            "recovery_identity": None,
                            "recovery_sha256": None,
                        }
                    )
                    recovery_objects.append(entry)

                recovery_manifest = _recovery_manifest(run_id, "PREPARING", recovery_objects)
                _write_recovery_manifest(
                    quarantine_run_fd,
                    recovery_manifest,
                    signal_guard=signal_guard,
                    expected_source_mode=source_file_mode,
                    expected_source_gid=upload_backup_reader_group_gid,
                )
                recovery_journal_started = True
                if fault_hook is not None:
                    fault_hook("P2_BEFORE_COPY")

                for entry in recovery_objects:
                    source = resolved_upload_dir / entry["storage_name"]
                    recovery = quarantine_dir / entry["recovery_name"]
                    source_identity, recovery_identity = create_recovery_copy(
                        source,
                        recovery,
                        source_parent_fd=upload_fd,
                        recovery_parent_fd=quarantine_run_fd,
                        signal_guard=signal_guard,
                        expected_source_identity=_identity_tuple(entry["source_identity"]),
                        expected_size=entry["envelope_size"],
                        expected_sha256=entry["envelope_sha256"],
                        expected_source_mode=source_file_mode,
                        expected_source_gid=upload_backup_reader_group_gid,
                        fault_hook=fault_hook,
                    )
                    if source_identity != _identity_tuple(entry["source_identity"]):
                        raise RuntimeError(
                            f"encrypted report object changed while quarantining: {entry['storage_name']}"
                        )
                    entry["recovery_identity"] = {
                        field: value
                        for field, value in zip(FILE_IDENTITY_FIELDS, recovery_identity)
                    }
                    entry["recovery_sha256"] = entry["envelope_sha256"]
                    _write_recovery_manifest(
                        quarantine_run_fd,
                        _recovery_manifest(run_id, "PREPARING", recovery_objects),
                        signal_guard=signal_guard,
                        expected_source_mode=source_file_mode,
                        expected_source_gid=upload_backup_reader_group_gid,
                    )
                    if fault_hook is not None:
                        fault_hook("P3")
                    manifest["images"].append(
                        {
                            "report_id": entry["report_id"],
                            "storage_name": entry["storage_name"],
                            "envelope_sha256": entry["envelope_sha256"],
                            "envelope_size": entry["envelope_size"],
                            "key_id": entry["key_id"],
                            "state": "recovery_copy_created",
                        }
                    )

                _write_recovery_manifest(
                    quarantine_run_fd,
                    _recovery_manifest(run_id, "PRECOMMIT_READY", recovery_objects),
                    signal_guard=signal_guard,
                    expected_source_mode=source_file_mode,
                    expected_source_gid=upload_backup_reader_group_gid,
                )
                if fault_hook is not None:
                    fault_hook("P4")
                manifest["status"] = "recovery_copied"
                write_json_atomic(manifest_path, manifest, publisher=manifest_publisher)
                for _candidate, report in candidates_with_reports:
                    session.delete(report)
                _write_recovery_manifest(
                    quarantine_run_fd,
                    _recovery_manifest(run_id, "DATABASE_COMMITTING", recovery_objects),
                    signal_guard=signal_guard,
                    expected_source_mode=source_file_mode,
                    expected_source_gid=upload_backup_reader_group_gid,
                )
                if fault_hook is not None:
                    fault_hook("P5")
            session.commit()
            database_committed = True
            if fault_hook is not None:
                fault_hook("P6")

        with signal_guard.blocked():
            if quarantine_run_fd >= 0:
                os.close(quarantine_run_fd)
                quarantine_run_fd = -1
            if quarantine_root_fd >= 0:
                os.close(quarantine_root_fd)
                quarantine_root_fd = -1
            if upload_fd >= 0:
                os.close(upload_fd)
                upload_fd = -1

        manifest["status"] = "database_committed"
        manifest["deleted_count"] = len(manifest["candidates"])
        write_json_atomic(manifest_path, manifest, publisher=manifest_publisher)
        if recovery_journal_started:
            current_reconciliations = reconcile_retention_quarantine(
                resolved_upload_dir,
                database_state_loader=database_state_loader,
                signal_guard=signal_guard,
                expected_upload_group_gid=upload_backup_reader_group_gid,
                fault_hook=fault_hook,
            )
            if not any(
                item["run_id"] == run_id
                and item["status"] == "RECONCILED_POSTCOMMIT_COMPLETED"
                for item in current_reconciliations
            ):
                raise RuntimeError("committed retention journal was not reconciled")
            reconciled_runs.extend(current_reconciliations)

        manifest["status"] = "completed"
        manifest["deleted_count"] = len(manifest["candidates"])
        manifest["cleanup_errors"] = []
        manifest["reconciled_runs"] = reconciled_runs
        manifest["finished_at"] = datetime.now(UTC).isoformat().replace("+00:00", "Z")
        write_json_atomic(
            manifest_path,
            manifest,
            publisher=manifest_publisher,
        )
        return manifest
    except BaseException as exc:
        try:
            signal_guard.begin_cleanup()
        except DeferredTerminationSignal:
            signal_guard.begin_cleanup()
        with signal_guard.blocked():
            if quarantine_run_fd >= 0:
                os.close(quarantine_run_fd)
                quarantine_run_fd = -1
            if quarantine_root_fd >= 0:
                os.close(quarantine_root_fd)
                quarantine_root_fd = -1
            if upload_fd >= 0:
                os.close(upload_fd)
                upload_fd = -1
        recovery_errors: list[str] = []
        if engine is not None:
            try:
                recovered = reconcile_retention_quarantine(
                    resolved_upload_dir,
                    database_state_loader=lambda report_ids: _load_recovery_database_state(
                        engine,
                        report_ids,
                    ),
                    signal_guard=signal_guard,
                    expected_upload_group_gid=upload_backup_reader_group_gid,
                )
                reconciled_runs.extend(recovered)
                if any(
                    item["run_id"] == run_id
                    and item["status"] == "RECONCILED_POSTCOMMIT_COMPLETED"
                    for item in recovered
                ):
                    database_committed = True
            except BaseException as recovery_exc:
                recovery_errors.append(type(recovery_exc).__name__)
        if manifest_publisher is not None and manifest:
            manifest.pop("cleanup_errors", None)
            if manifest.get("status") == "planning":
                manifest["status"] = "failed_planning"
            elif database_committed:
                manifest["status"] = "failed_after_database_commit"
                manifest["deleted_count"] = len(manifest["candidates"])
            elif any(
                item["run_id"] == run_id
                and item["status"] == "RECONCILED_PRECOMMIT_ABORTED"
                for item in reconciled_runs
            ):
                manifest["status"] = "failed_before_commit"
            else:
                manifest["status"] = "failed"
            manifest["error_type"] = type(exc).__name__
            manifest["restore_errors"] = recovery_errors
            manifest["reconciled_runs"] = reconciled_runs
            manifest["finished_at"] = datetime.now(UTC).isoformat().replace("+00:00", "Z")
            write_json_atomic(
                manifest_path,
                manifest,
                publisher=manifest_publisher,
            )
        raise
    finally:
        if engine is not None:
            engine.dispose()


def apply_database_retention(
    *,
    database_url: str,
    upload_dir: Path,
    as_of: datetime,
    manifest_path: Path,
    backup_manifest_path: Path,
    restore_receipt_path: Path | None = None,
    trusted_backup_signer_fingerprint: str | None = None,
    trusted_restore_signer_fingerprint: str | None = None,
    maintenance_lock_path: Path | None = None,
    actor_id: str = "system",
    batch_size: int = DEFAULT_APPLY_BATCH_SIZE,
) -> dict[str, Any]:
    database_url = validated_retention_database_url(database_url)
    retention_database_timeouts()
    as_of = validated_as_of(as_of)
    if as_of > datetime.now(UTC):
        raise ValueError("retention apply rejects a future as-of value")
    if restore_receipt_path is None:
        raise ValueError("retention apply requires a restore receipt for the current backup")
    if not trusted_backup_signer_fingerprint:
        raise ValueError("retention apply requires a trusted backup signer fingerprint")
    if not trusted_restore_signer_fingerprint:
        raise ValueError("retention apply requires a trusted restore signer fingerprint")
    if maintenance_lock_path is None:
        raise ValueError("retention apply requires the shared maintenance lock")
    if not 1 <= batch_size <= MAX_APPLY_BATCH_SIZE:
        raise ValueError(f"retention batch size must be between 1 and {MAX_APPLY_BATCH_SIZE}")
    raw_timeout = os.getenv("WALKSAFE_MAINTENANCE_LOCK_TIMEOUT_SECONDS", "30")
    try:
        timeout_seconds = int(raw_timeout)
    except ValueError as exc:
        raise ValueError("maintenance lock timeout must be an integer") from exc
    if not 1 <= timeout_seconds <= 300:
        raise ValueError("maintenance lock timeout must be between 1 and 300 seconds")
    upload_backup_reader_group_gid = (
        _upload_backup_reader_group_gid_from_environment()
    )
    with walksafe_admin_high_risk_operation(
        "DATA_DELETE",
        database_url=database_url,
        device_id=os.getenv("WALKSAFE_ADMIN_HIGH_RISK_DEVICE_ID"),
    ) as admin_identity:
        signal_guard = RetentionSignalGuard()
        signal_guard.install()
        try:
            with exclusive_maintenance_lock(
                maintenance_lock_path,
                timeout_seconds=timeout_seconds,
                expected_group_gid=_maintenance_lock_group_gid_from_environment(),
            ) as maintenance_lock:
                return _apply_database_retention_locked(
                    database_url=database_url,
                    upload_dir=upload_dir,
                    as_of=as_of,
                    manifest_path=manifest_path,
                    backup_manifest_path=backup_manifest_path,
                    restore_receipt_path=restore_receipt_path,
                    trusted_backup_signer_fingerprint=trusted_backup_signer_fingerprint,
                    trusted_restore_signer_fingerprint=trusted_restore_signer_fingerprint,
                    maintenance_lock_path=maintenance_lock_path,
                    maintenance_lock=maintenance_lock,
                    signal_guard=signal_guard,
                    upload_backup_reader_group_gid=upload_backup_reader_group_gid,
                    actor_id=actor_id,
                    authorized_admin_id=(
                        admin_identity.admin_id
                        if admin_identity is not None
                        else None
                    ),
                    authorized_session_id=(
                        str(admin_identity.session_id)
                        if admin_identity is not None
                        else None
                    ),
                    batch_size=batch_size,
                )
        except BaseException:
            try:
                signal_guard.begin_cleanup()
            except DeferredTerminationSignal:
                signal_guard.begin_cleanup()
            raise
        finally:
            signal_guard.close()


def validation_error(message: str) -> int:
    print(json.dumps({"error": message, "destructive_action": False}, ensure_ascii=False))
    return 2


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Plan report retention by default; apply only with DB, upload, confirmation, and audit manifest."
    )
    parser.add_argument("--input-json", type=Path, help="JSON fixture/list for an offline dry-run.")
    parser.add_argument(
        "--database-url",
        help="Database URL for live dry-run or apply. With --apply only, defaults to DATABASE_URL. Never printed.",
    )
    parser.add_argument("--upload-dir", type=Path, help="Upload root required for --apply.")
    parser.add_argument("--as-of", default=datetime.now(UTC).isoformat().replace("+00:00", "Z"))
    parser.add_argument("--output-md", type=Path, help="Optional markdown artifact path.")
    parser.add_argument("--manifest-json", type=Path, help="Required durable audit manifest path for --apply.")
    parser.add_argument("--backup-manifest", type=Path, help="Fresh encrypted backup manifest required before --apply.")
    parser.add_argument("--restore-receipt", type=Path, help="Successful restore receipt for the same backup; required for --apply.")
    parser.add_argument(
        "--trusted-backup-signer-fingerprint",
        default=os.getenv("WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT"),
        help="Trusted 40-hex signer of manifest.json; defaults to WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT.",
    )
    parser.add_argument(
        "--trusted-restore-signer-fingerprint",
        default=os.getenv("WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT"),
        help="Trusted 40-hex signer of the restore receipt; defaults to WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT.",
    )
    parser.add_argument(
        "--maintenance-lock",
        type=Path,
        default=Path(os.environ["WALKSAFE_MAINTENANCE_LOCK_PATH"])
        if os.environ.get("WALKSAFE_MAINTENANCE_LOCK_PATH")
        else None,
        help="Shared absolute lock path also used by backup; defaults to WALKSAFE_MAINTENANCE_LOCK_PATH.",
    )
    parser.add_argument("--apply", action="store_true", help="Apply deletion after every safety requirement passes.")
    parser.add_argument("--confirm", help=f"With --apply, must equal {APPLY_CONFIRMATION!r}.")
    parser.add_argument(
        "--batch-size",
        type=int,
        default=DEFAULT_APPLY_BATCH_SIZE,
        help=f"Maximum rows deleted in one apply transaction (1..{MAX_APPLY_BATCH_SIZE}).",
    )
    parser.add_argument(
        "--actor-id",
        default=os.getenv("WALKSAFE_ACTOR_ID", "system"),
        help="Audited actor id; defaults to WALKSAFE_ACTOR_ID or system.",
    )
    parser.add_argument(
        "--execute-delete",
        action="store_true",
        help="Deprecated unsafe flag; rejected. Use the guarded --apply workflow.",
    )
    args = parser.parse_args()

    if args.execute_delete:
        return validation_error("--execute-delete is unsupported; use guarded --apply")
    if args.input_json and args.database_url:
        return validation_error("choose exactly one input source: --input-json or --database-url")
    if args.apply and args.database_url is None:
        args.database_url = os.getenv("DATABASE_URL")

    try:
        as_of = parse_as_of(args.as_of)
    except ValueError as exc:
        return validation_error(str(exc))
    try:
        actor_id = validated_actor_id(args.actor_id)
    except ValueError as exc:
        return validation_error(str(exc))
    if args.apply:
        if args.input_json:
            return validation_error("--apply never trusts exported JSON; use --database-url")
        if args.output_md:
            return validation_error("--output-md is dry-run only and is forbidden with --apply")
        if not args.database_url:
            return validation_error("--apply requires --database-url")
        if args.upload_dir is None:
            return validation_error("--apply requires --upload-dir")
        if args.manifest_json is None:
            return validation_error("--apply requires --manifest-json")
        if args.backup_manifest is None:
            return validation_error("--apply requires --backup-manifest")
        if args.restore_receipt is None:
            return validation_error("--apply requires --restore-receipt")
        if not args.trusted_backup_signer_fingerprint:
            return validation_error("--apply requires --trusted-backup-signer-fingerprint")
        if not args.trusted_restore_signer_fingerprint:
            return validation_error("--apply requires --trusted-restore-signer-fingerprint")
        if args.maintenance_lock is None:
            return validation_error("--apply requires --maintenance-lock or WALKSAFE_MAINTENANCE_LOCK_PATH")
        if args.confirm != APPLY_CONFIRMATION:
            return validation_error(f"--apply requires --confirm {APPLY_CONFIRMATION}")
        if not 1 <= args.batch_size <= MAX_APPLY_BATCH_SIZE:
            return validation_error(f"--batch-size must be between 1 and {MAX_APPLY_BATCH_SIZE}")
        if as_of > datetime.now(UTC):
            return validation_error("--apply rejects a future --as-of value")
        try:
            args.database_url = validated_retention_database_url(args.database_url)
            retention_database_timeouts()
        except ValueError as exc:
            return validation_error(str(exc))

        result = apply_database_retention(
            database_url=args.database_url,
            upload_dir=args.upload_dir,
            as_of=as_of,
            manifest_path=args.manifest_json,
            backup_manifest_path=args.backup_manifest,
            restore_receipt_path=args.restore_receipt,
            trusted_backup_signer_fingerprint=args.trusted_backup_signer_fingerprint,
            trusted_restore_signer_fingerprint=args.trusted_restore_signer_fingerprint,
            maintenance_lock_path=args.maintenance_lock,
            actor_id=actor_id,
            batch_size=args.batch_size,
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1 if result.get("cleanup_errors") else 0

    if args.database_url:
        input_rows, rows, batch_limit_reached = load_database_retention_batch(
            args.database_url,
            as_of=as_of,
            batch_size=args.batch_size,
        )
    else:
        rows = load_rows(args.input_json)
        input_rows = len(rows)
        batch_limit_reached = False
    candidates = [candidate for row in rows if (candidate := candidate_for_row(row, as_of=as_of)) is not None]
    result = {
        "schema_version": "walksafe.report_retention_dry_run.v2",
        "as_of": as_of.isoformat().replace("+00:00", "Z"),
        "actor_id": actor_id,
        "input_rows": input_rows,
        "candidate_count": len(candidates),
        "candidate_batch_limit_reached": batch_limit_reached,
        "candidate_ids_sha256": candidate_digest(candidates),
        "destructive_action": False,
        "candidates": candidates,
    }
    if args.output_md:
        write_markdown(
            args.output_md,
            as_of=as_of,
            candidates=candidates,
            destructive_action=False,
        )
        result["markdown_artifact"] = str(args.output_md)

    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
