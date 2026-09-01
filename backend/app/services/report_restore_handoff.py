"""Held source locks and one-shot signed-evidence handoff for restore drills."""

from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
import hashlib
import os
from pathlib import Path
import stat
import time
from typing import Callable, Iterator, Mapping
import uuid

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Connection, Engine

from backend.app.uploads import descriptor_acl_is_absent
from backend.app.services import report_restore_tombstones as tombstones
from scripts.check_report_retention_dry_run import (
    _maintenance_lock_group_gid_from_environment,
    exclusive_maintenance_lock,
)
from scripts.walksafe_environment_identity import (
    database_identity_sha256,
    explicit_restore_database_url,
    private_restore_output_path,
    sqlalchemy_psycopg_url,
)


EVIDENCE_LIMITS = {
    "ledger": 64 * 1024 * 1024,
    "ledger_signature": 64 * 1024,
    "trusted_head": 64 * 1024,
    "head_signature": 64 * 1024,
    "fence_binding": 64 * 1024,
    "fence_binding_signature": 64 * 1024,
}
DEFAULT_EVIDENCE_WAIT_SECONDS = 30
MAX_EVIDENCE_WAIT_SECONDS = 300
SOURCE_CONNECT_TIMEOUT_SECONDS = 10
SOURCE_STATEMENT_TIMEOUT_MS = 10_000
_UINT32_MASK = (1 << 32) - 1


def _fail(code: str, message: str) -> tombstones.ReportRestoreTombstoneError:
    return tombstones.ReportRestoreTombstoneError(code, message)


@dataclass(frozen=True, slots=True)
class PostgreSQLRuntimeIdentity:
    system_identifier: str
    database_oid: int
    database_name: str
    identity_sha256: str


def postgresql_runtime_identity(connection: Connection) -> PostgreSQLRuntimeIdentity:
    try:
        row = connection.execute(
            text(
                "SELECT control.system_identifier::text, database.oid::bigint, "
                "current_database() "
                "FROM pg_catalog.pg_control_system() AS control "
                "JOIN pg_catalog.pg_database AS database "
                "ON database.datname = current_database()"
            )
        ).one()
        connection.rollback()
    except Exception as exc:
        connection.rollback()
        raise _fail(
            "restore_database_identity_unavailable",
            "The PostgreSQL database identity cannot be verified.",
        ) from exc
    payload = tombstones.canonical_json_bytes(
        {
            "database_name": row[2],
            "database_oid": int(row[1]),
            "system_identifier": row[0],
        }
    )
    return PostgreSQLRuntimeIdentity(
        system_identifier=row[0],
        database_oid=int(row[1]),
        database_name=row[2],
        identity_sha256=hashlib.sha256(
            b"walksafe/postgresql-runtime-database-identity/v1\0" + payload
        ).hexdigest(),
    )


def _advisory_parts(value: int) -> tuple[int, int, int]:
    unsigned = value & ((1 << 64) - 1)
    return unsigned >> 32, unsigned & _UINT32_MASK, 1


def _advisory_identity_sha256(
    *,
    runtime_identity: PostgreSQLRuntimeIdentity,
    backend_pid: int,
    numeric_key: int,
) -> str:
    return hashlib.sha256(
        b"walksafe/report-restore-source-advisory-lock/v1\0"
        + tombstones.canonical_json_bytes(
            {
                "backend_pid": backend_pid,
                "database_identity_sha256": runtime_identity.identity_sha256,
                "numeric_key": numeric_key,
            }
        )
    ).hexdigest()


class HeldReportRestoreSourceFence(tombstones.ReportRestoreSourceFenceVerifier):
    """Pinned source connection plus the already-held maintenance file lock."""

    def __init__(
        self,
        *,
        connection: Connection,
        engine: Engine,
        maintenance_authority: Mapping[str, object],
        request: tombstones.ReportRestoreSourceFenceRequest,
        runtime_identity: PostgreSQLRuntimeIdentity,
        numeric_advisory_key: int,
    ) -> None:
        self.connection = connection
        self.engine = engine
        self.maintenance_authority = maintenance_authority
        self.request = request
        self.runtime_identity = runtime_identity
        self.numeric_advisory_key = numeric_advisory_key
        self._active = True
        self._verified_fence_sha256: str | None = None

    def _bind_verified_fence(
        self,
        fence: tombstones.ReportRestoreSourceFence,
    ) -> None:
        if not self._request_matches(fence):
            raise _fail(
                "restore_source_fence_binding_invalid",
                "The verified fence does not match the held source request.",
            )
        digest = tombstones.report_restore_source_fence_sha256(fence)
        if (
            self._verified_fence_sha256 is not None
            and self._verified_fence_sha256 != digest
        ):
            raise _fail(
                "restore_source_fence_binding_invalid",
                "The held source request was already bound to another head.",
            )
        self._verified_fence_sha256 = digest

    def _request_matches(
        self,
        fence: tombstones.ReportRestoreSourceFence,
    ) -> bool:
        return all(
            getattr(fence, field) == getattr(self.request, field)
            for field in self.request.__dataclass_fields__
        ) and fence.source_fence_request_sha256 == (
            tombstones.report_restore_source_fence_request_sha256(self.request)
        )

    def verify_request_held(self) -> None:
        if not self._active or self.connection.closed:
            raise _fail(
                "restore_source_fence_not_held",
                "The source-write fence is no longer held.",
            )
        verify_file = self.maintenance_authority.get("verify_held")
        if not callable(verify_file):
            raise _fail(
                "restore_source_fence_unavailable",
                "The maintenance lock cannot be reverified.",
            )
        try:
            verify_file()
            rows = self.connection.execute(
                text(
                    "SELECT classid::bigint, objid::bigint, objsubid "
                    "FROM pg_catalog.pg_locks "
                    "WHERE locktype = 'advisory' AND pid = pg_backend_pid() "
                    "AND mode = 'ExclusiveLock' AND granted"
                )
            ).all()
            backend_pid = int(
                self.connection.execute(text("SELECT pg_backend_pid()"))
                .scalar_one()
            )
            self.connection.rollback()
        except Exception as exc:
            self.connection.rollback()
            if isinstance(exc, tombstones.ReportRestoreTombstoneError):
                raise
            raise _fail(
                "restore_source_fence_unavailable",
                "The source-write fence cannot be reverified.",
            ) from exc
        expected_parts = _advisory_parts(self.numeric_advisory_key)
        if (
            backend_pid != self.request.source_backend_pid
            or expected_parts
            not in {(int(row[0]), int(row[1]), int(row[2])) for row in rows}
            or _advisory_identity_sha256(
                runtime_identity=self.runtime_identity,
                backend_pid=backend_pid,
                numeric_key=self.numeric_advisory_key,
            )
            != self.request.report_deletion_lock_identity_sha256
            or self.maintenance_authority.get("identity_sha256")
            != self.request.maintenance_lock_identity_sha256
            or self.maintenance_authority.get("device_inode")
            != self.request.maintenance_lock_device_inode
        ):
            raise _fail(
                "restore_source_fence_not_held",
                "The held source locks no longer match their fence request.",
            )

    def is_held(self, fence: tombstones.ReportRestoreSourceFence) -> bool:
        if (
            self._verified_fence_sha256 is None
            or not self._request_matches(fence)
            or tombstones.report_restore_source_fence_sha256(fence)
            != self._verified_fence_sha256
        ):
            return False
        try:
            self.verify_request_held()
        except tombstones.ReportRestoreTombstoneError:
            return False
        return True

    def _release(self) -> None:
        if not self._active:
            return
        self._active = False
        try:
            if not self.connection.closed:
                unlocked = bool(
                    self.connection.execute(
                        text(
                            "SELECT pg_advisory_unlock(hashtextextended(:lock_key, 0))"
                        ),
                        {"lock_key": tombstones.REPORT_DELETION_ADVISORY_LOCK_KEY},
                    ).scalar_one()
                )
                self.connection.rollback()
                if not unlocked:
                    self.connection.invalidate()
        finally:
            try:
                self.connection.close()
            finally:
                self.engine.dispose()


@contextmanager
def held_report_restore_source_fence(
    *,
    source_database_url: str,
    maintenance_lock_path: Path,
    restore_run_id: uuid.UUID,
    backup_run_id: str,
    backup_manifest_sha256: str,
    source_identity_sha256: str,
    data_boundary_id: str,
    lock_timeout_seconds: int = 30,
) -> Iterator[HeldReportRestoreSourceFence]:
    """Acquire both source locks before emitting a non-secret fence request."""

    normalized = explicit_restore_database_url(source_database_url)
    if database_identity_sha256(normalized) != source_identity_sha256:
        raise _fail(
            "restore_source_identity_mismatch",
            "The source database URL differs from the restored backup identity.",
        )
    if not 0 <= lock_timeout_seconds <= 300:
        raise ValueError("source lock timeout must be between 0 and 300 seconds")
    with exclusive_maintenance_lock(
        maintenance_lock_path,
        timeout_seconds=lock_timeout_seconds,
        expected_group_gid=_maintenance_lock_group_gid_from_environment(),
    ) as authority:
        engine = create_engine(
            sqlalchemy_psycopg_url(normalized),
            pool_pre_ping=True,
            hide_parameters=True,
            connect_args={
                "connect_timeout": SOURCE_CONNECT_TIMEOUT_SECONDS,
                "options": f"-c statement_timeout={SOURCE_STATEMENT_TIMEOUT_MS}",
            },
        )
        connection: Connection | None = None
        lease: HeldReportRestoreSourceFence | None = None
        try:
            connection = engine.connect()
            locked_row = connection.execute(
                text(
                    "SELECT hashtextextended(:lock_key, 0)::bigint, "
                    "pg_try_advisory_lock(hashtextextended(:lock_key, 0))"
                ),
                {"lock_key": tombstones.REPORT_DELETION_ADVISORY_LOCK_KEY},
            ).one()
            connection.rollback()
            if locked_row[1] is not True:
                raise _fail(
                    "restore_source_fence_busy",
                    "The report deletion source lock is already held.",
                )
            runtime_identity = postgresql_runtime_identity(connection)
            acquired = connection.execute(
                text("SELECT pg_backend_pid(), clock_timestamp()")
            ).one()
            connection.rollback()
            backend_pid = int(acquired[0])
            numeric_key = int(locked_row[0])
            request = tombstones.ReportRestoreSourceFenceRequest(
                fence_id=uuid.uuid4(),
                restore_run_id=restore_run_id,
                backup_run_id=backup_run_id,
                backup_manifest_sha256=backup_manifest_sha256,
                source_identity_sha256=source_identity_sha256,
                data_boundary_id=data_boundary_id,
                source_backend_pid=backend_pid,
                maintenance_lock_identity_sha256=str(
                    authority["identity_sha256"]
                ),
                maintenance_lock_device_inode=str(authority["device_inode"]),
                report_deletion_lock_key=(
                    tombstones.REPORT_DELETION_ADVISORY_LOCK_KEY
                ),
                report_deletion_lock_identity_sha256=(
                    _advisory_identity_sha256(
                        runtime_identity=runtime_identity,
                        backend_pid=backend_pid,
                        numeric_key=numeric_key,
                    )
                ),
                acquired_at=acquired[1],
            )
            tombstones.report_restore_source_fence_request_bytes(request)
            lease = HeldReportRestoreSourceFence(
                connection=connection,
                engine=engine,
                maintenance_authority=authority,
                request=request,
                runtime_identity=runtime_identity,
                numeric_advisory_key=numeric_key,
            )
            lease.verify_request_held()
            yield lease
        finally:
            if lease is not None:
                lease._release()
            else:
                try:
                    if connection is not None:
                        connection.close()
                finally:
                    engine.dispose()


def _private_evidence_paths(paths: Mapping[str, Path]) -> tuple[Path, dict[str, str]]:
    if set(paths) != set(EVIDENCE_LIMITS):
        raise ValueError("the signed evidence path set is incomplete")
    parents: set[Path] = set()
    names: dict[str, str] = {}
    for label, path in paths.items():
        try:
            private_restore_output_path(str(path))
        except ValueError as exc:
            raise _fail(
                "restore_tombstone_evidence_path_unsafe",
                "A signed evidence output path is unsafe.",
            ) from exc
        parents.add(path.parent)
        names[label] = path.name
        try:
            os.lstat(path)
        except FileNotFoundError:
            pass
        else:
            raise _fail(
                "restore_tombstone_evidence_preexisting",
                "Signed evidence outputs must not exist when source fencing starts.",
            )
    if len(parents) != 1:
        raise _fail(
            "restore_tombstone_evidence_path_unsafe",
            "Signed evidence outputs must share one private parent.",
        )
    if len(set(names.values())) != len(names):
        raise _fail(
            "restore_tombstone_evidence_path_unsafe",
            "Signed evidence outputs must use distinct final names.",
        )
    return parents.pop(), names


def _stat_identity(metadata: os.stat_result) -> tuple[int, ...]:
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


def _parent_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
    )


class PreparedSignedEvidencePaths:
    """Private parent pinned while all final evidence leaf names are absent."""

    def __init__(
        self,
        *,
        parent: Path,
        names: Mapping[str, str],
        parent_descriptor: int,
        parent_identity: tuple[int, ...],
    ) -> None:
        self.parent = parent
        self.names = dict(names)
        self.parent_descriptor: int | None = parent_descriptor
        self.parent_identity = parent_identity

    def take_parent_descriptor(self) -> int:
        if self.parent_descriptor is None:
            raise RuntimeError("signed evidence paths were already consumed")
        descriptor = self.parent_descriptor
        self.parent_descriptor = None
        return descriptor

    def verify_absent_unchanged(self) -> None:
        if self.parent_descriptor is None:
            raise _fail(
                "restore_tombstone_evidence_path_unsafe",
                "Signed evidence paths were already consumed.",
            )
        try:
            metadata = os.fstat(self.parent_descriptor)
            current = self.parent.stat(follow_symlinks=False)
            if (
                _parent_identity(metadata) != self.parent_identity
                or (metadata.st_dev, metadata.st_ino)
                != (current.st_dev, current.st_ino)
                or not descriptor_acl_is_absent(self.parent_descriptor)
            ):
                raise OSError("signed evidence parent changed")
            for name in self.names.values():
                try:
                    os.stat(
                        name,
                        dir_fd=self.parent_descriptor,
                        follow_symlinks=False,
                    )
                except FileNotFoundError:
                    continue
                raise OSError("signed evidence appeared before request emission")
        except OSError as exc:
            raise _fail(
                "restore_tombstone_evidence_preexisting",
                "Signed evidence must remain absent until the source fence request is emitted.",
            ) from exc

    def close(self) -> None:
        if self.parent_descriptor is not None:
            os.close(self.parent_descriptor)
            self.parent_descriptor = None

    def __enter__(self) -> PreparedSignedEvidencePaths:
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()


def prepare_signed_evidence_paths(
    paths: Mapping[str, Path],
) -> PreparedSignedEvidencePaths:
    parent, names = _private_evidence_paths(paths)
    descriptor = os.open(
        parent,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        metadata = os.fstat(descriptor)
        current = parent.stat(follow_symlinks=False)
        if (
            not stat.S_ISDIR(metadata.st_mode)
            or metadata.st_uid != os.geteuid()
            or stat.S_IMODE(metadata.st_mode) != 0o700
            or not descriptor_acl_is_absent(descriptor)
            or (metadata.st_dev, metadata.st_ino)
            != (current.st_dev, current.st_ino)
        ):
            raise _fail(
                "restore_tombstone_evidence_path_unsafe",
                "The signed evidence parent is not a pinned private directory.",
            )
        for name in names.values():
            try:
                os.stat(name, dir_fd=descriptor, follow_symlinks=False)
            except FileNotFoundError:
                continue
            raise _fail(
                "restore_tombstone_evidence_preexisting",
                "Signed evidence outputs must remain absent until source fencing.",
            )
    except BaseException:
        os.close(descriptor)
        raise
    return PreparedSignedEvidencePaths(
        parent=parent,
        names=names,
        parent_descriptor=descriptor,
        parent_identity=_parent_identity(metadata),
    )


class StableSignedEvidence:
    def __init__(
        self,
        *,
        parent_path: Path,
        parent_descriptor: int,
        parent_identity: tuple[int, ...],
        names: Mapping[str, str],
        descriptors: Mapping[str, int],
        identities: Mapping[str, tuple[int, ...]],
        raw: Mapping[str, bytes],
    ) -> None:
        self.parent_path = parent_path
        self.parent_descriptor = parent_descriptor
        self.parent_identity = parent_identity
        self.names = dict(names)
        self.descriptors = dict(descriptors)
        self.identities = dict(identities)
        self.raw = dict(raw)
        self.closed = False

    def verify_unchanged(self) -> None:
        if self.closed:
            raise _fail(
                "restore_tombstone_evidence_unavailable",
                "The signed evidence is no longer held open.",
            )
        try:
            parent = os.fstat(self.parent_descriptor)
            parent_path = self.parent_path.stat(follow_symlinks=False)
            if (
                _parent_identity(parent) != self.parent_identity
                or (parent.st_dev, parent.st_ino)
                != (parent_path.st_dev, parent_path.st_ino)
                or not descriptor_acl_is_absent(self.parent_descriptor)
            ):
                raise OSError("evidence parent changed")
            for label, descriptor in self.descriptors.items():
                opened = os.fstat(descriptor)
                current = os.stat(
                    self.names[label],
                    dir_fd=self.parent_descriptor,
                    follow_symlinks=False,
                )
                if (
                    _stat_identity(opened) != self.identities[label]
                    or _stat_identity(current) != self.identities[label]
                    or not descriptor_acl_is_absent(descriptor)
                ):
                    raise OSError("evidence file changed")
        except OSError as exc:
            raise _fail(
                "restore_tombstone_evidence_unavailable",
                "Signed evidence was replaced or changed while held.",
            ) from exc

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        for descriptor in self.descriptors.values():
            os.close(descriptor)
        os.close(self.parent_descriptor)

    def __enter__(self) -> StableSignedEvidence:
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()


def wait_for_stable_signed_evidence(
    paths: Mapping[str, Path],
    *,
    timeout_seconds: int = DEFAULT_EVIDENCE_WAIT_SECONDS,
    verify_source_fence: Callable[[], None],
    prepared: PreparedSignedEvidencePaths | None = None,
) -> StableSignedEvidence:
    """Open each once-published evidence file and pin every descriptor."""

    if not 1 <= timeout_seconds <= MAX_EVIDENCE_WAIT_SECONDS:
        raise ValueError("evidence wait must be between 1 and 300 seconds")
    owned_preparation = prepared is None
    prepared_paths = prepared or prepare_signed_evidence_paths(paths)
    if prepared is not None and (
        prepared.names != {label: path.name for label, path in paths.items()}
        or any(path.parent != prepared.parent for path in paths.values())
    ):
        raise ValueError("prepared signed evidence paths differ")
    parent = prepared_paths.parent
    names = prepared_paths.names
    parent_descriptor = prepared_paths.take_parent_descriptor()
    descriptors: dict[str, int] = {}
    identities: dict[str, tuple[int, ...]] = {}
    raw: dict[str, bytes] = {}

    def verify_before_deadline() -> None:
        if time.monotonic() >= deadline:
            raise _fail(
                "restore_tombstone_evidence_timeout",
                "Signed evidence was not published before the bounded timeout.",
            )
        verify_source_fence()

    try:
        parent_metadata = os.fstat(parent_descriptor)
        parent_identity = prepared_paths.parent_identity
        if _parent_identity(parent_metadata) != parent_identity:
            raise _fail(
                "restore_tombstone_evidence_path_unsafe",
                "The signed evidence parent changed before publication.",
        )
        deadline = time.monotonic() + timeout_seconds
        while len(descriptors) != len(names):
            verify_before_deadline()
            for label, name in names.items():
                if label in descriptors:
                    continue
                try:
                    metadata = os.stat(
                        name,
                        dir_fd=parent_descriptor,
                        follow_symlinks=False,
                    )
                except FileNotFoundError:
                    continue
                maximum = EVIDENCE_LIMITS[label]
                if (
                    not stat.S_ISREG(metadata.st_mode)
                    or metadata.st_uid != os.geteuid()
                    or stat.S_IMODE(metadata.st_mode) != 0o600
                    or metadata.st_nlink != 1
                    or not 0 < metadata.st_size <= maximum
                ):
                    raise _fail(
                        "restore_tombstone_evidence_unavailable",
                        "A published evidence file has unsafe metadata.",
                    )
                descriptor = os.open(
                    name,
                    os.O_RDONLY
                    | getattr(os, "O_CLOEXEC", 0)
                    | getattr(os, "O_NOFOLLOW", 0)
                    | getattr(os, "O_NONBLOCK", 0),
                    dir_fd=parent_descriptor,
                )
                try:
                    opened = os.fstat(descriptor)
                    if (
                        _stat_identity(opened) != _stat_identity(metadata)
                        or not descriptor_acl_is_absent(descriptor)
                    ):
                        raise _fail(
                            "restore_tombstone_evidence_unavailable",
                            "A published evidence file changed while opening.",
                        )
                    chunks: list[bytes] = []
                    remaining = opened.st_size
                    while remaining:
                        chunk = os.read(descriptor, min(1024 * 1024, remaining))
                        if not chunk:
                            raise _fail(
                                "restore_tombstone_evidence_unavailable",
                                "A published evidence file is truncated.",
                            )
                        chunks.append(chunk)
                        remaining -= len(chunk)
                        if time.monotonic() >= deadline:
                            raise _fail(
                                "restore_tombstone_evidence_timeout",
                                "Signed evidence was not published before the bounded timeout.",
                            )
                    if os.read(descriptor, 1):
                        raise _fail(
                            "restore_tombstone_evidence_unavailable",
                            "A published evidence file exceeded its anchored size.",
                        )
                    after = os.fstat(descriptor)
                    current = os.stat(
                        name,
                        dir_fd=parent_descriptor,
                        follow_symlinks=False,
                    )
                    if (
                        _stat_identity(after) != _stat_identity(opened)
                        or _stat_identity(current) != _stat_identity(opened)
                    ):
                        raise _fail(
                            "restore_tombstone_evidence_unavailable",
                            "A published evidence file changed while reading.",
                        )
                except BaseException:
                    os.close(descriptor)
                    raise
                descriptors[label] = descriptor
                identities[label] = _stat_identity(opened)
                raw[label] = b"".join(chunks)
                verify_before_deadline()
            if len(descriptors) == len(names):
                break
            if time.monotonic() >= deadline:
                raise _fail(
                    "restore_tombstone_evidence_timeout",
                    "Signed evidence was not published before the bounded timeout.",
                )
            time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))
        held = StableSignedEvidence(
            parent_path=parent,
            parent_descriptor=parent_descriptor,
            parent_identity=parent_identity,
            names=names,
            descriptors=descriptors,
            identities=identities,
            raw=raw,
        )
        held.verify_unchanged()
        verify_before_deadline()
        return held
    except BaseException:
        for descriptor in descriptors.values():
            os.close(descriptor)
        os.close(parent_descriptor)
        if owned_preparation:
            prepared_paths.close()
        raise


def load_stable_trusted_key(path: Path) -> bytes:
    if not path.is_absolute() or path.resolve(strict=True) != path:
        raise _fail(
            "restore_tombstone_key_untrusted",
            "The trusted key path must be an existing canonical absolute file.",
        )
    try:
        before = path.stat(follow_symlinks=False)
        descriptor = os.open(
            path,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
            | getattr(os, "O_NONBLOCK", 0),
        )
        try:
            opened = os.fstat(descriptor)
            if (
                not stat.S_ISREG(opened.st_mode)
                or opened.st_uid not in {0, os.geteuid()}
                or stat.S_IMODE(opened.st_mode) & 0o022
                or opened.st_nlink != 1
                or not 0 < opened.st_size <= 64 * 1024
                or not descriptor_acl_is_absent(descriptor)
            ):
                raise OSError("trusted key metadata is unsafe")
            chunks: list[bytes] = []
            remaining = opened.st_size
            while remaining:
                chunk = os.read(descriptor, remaining)
                if not chunk:
                    raise OSError("trusted key is truncated")
                chunks.append(chunk)
                remaining -= len(chunk)
            if os.read(descriptor, 1):
                raise OSError("trusted key grew while reading")
            raw = b"".join(chunks)
            after = os.fstat(descriptor)
            acl_absent = descriptor_acl_is_absent(descriptor)
        finally:
            os.close(descriptor)
        current = path.stat(follow_symlinks=False)
    except OSError as exc:
        raise _fail(
            "restore_tombstone_key_untrusted",
            "The trusted verification key is unavailable.",
        ) from exc
    if (
        not acl_absent
        or len(raw) != opened.st_size
        or _stat_identity(before) != _stat_identity(opened)
        or _stat_identity(opened) != _stat_identity(after)
        or _stat_identity(after) != _stat_identity(current)
    ):
        raise _fail(
            "restore_tombstone_key_untrusted",
            "The trusted verification key file is unsafe or unstable.",
        )
    return raw


def verify_stable_handoff(
    *,
    held_source: HeldReportRestoreSourceFence,
    evidence: StableSignedEvidence,
    trusted_key_bytes: bytes,
    expected_key_id: str,
) -> tuple[
    tombstones.VerifiedReportTombstoneBundle,
    tombstones.ReportRestoreSourceFence,
]:
    evidence.verify_unchanged()
    held_source.verify_request_held()
    binding = tombstones.verify_report_restore_source_fence_binding(
        request=held_source.request,
        binding_bytes=evidence.raw["fence_binding"],
        binding_signature_bytes=evidence.raw["fence_binding_signature"],
        ledger_bytes=evidence.raw["ledger"],
        trusted_head_bytes=evidence.raw["trusted_head"],
        key_descriptor_bytes=trusted_key_bytes,
        expected_key_id=expected_key_id,
    )
    bundle = tombstones.verify_report_tombstone_bundle(
        ledger_bytes=evidence.raw["ledger"],
        ledger_signature_bytes=evidence.raw["ledger_signature"],
        trusted_head_bytes=evidence.raw["trusted_head"],
        head_signature_bytes=evidence.raw["head_signature"],
        key_descriptor_bytes=trusted_key_bytes,
        expected_key_id=expected_key_id,
        expected_head_sha256=binding.trusted_head_sha256,
    )
    fence = tombstones.bind_report_restore_source_fence(
        held_source.request,
        bundle=bundle,
        binding=binding,
    )
    held_source.verify_request_held()
    evidence.verify_unchanged()
    held_source._bind_verified_fence(fence)
    if not held_source.is_held(fence):
        held_source._verified_fence_sha256 = None
        raise _fail(
            "restore_source_fence_not_held",
            "The source-write fence changed while evidence was verified.",
        )
    try:
        evidence.verify_unchanged()
    except BaseException:
        held_source._verified_fence_sha256 = None
        raise
    return bundle, fence


__all__ = [
    "DEFAULT_EVIDENCE_WAIT_SECONDS",
    "EVIDENCE_LIMITS",
    "HeldReportRestoreSourceFence",
    "MAX_EVIDENCE_WAIT_SECONDS",
    "PostgreSQLRuntimeIdentity",
    "PreparedSignedEvidencePaths",
    "StableSignedEvidence",
    "held_report_restore_source_fence",
    "load_stable_trusted_key",
    "postgresql_runtime_identity",
    "prepare_signed_evidence_paths",
    "verify_stable_handoff",
    "wait_for_stable_signed_evidence",
]
