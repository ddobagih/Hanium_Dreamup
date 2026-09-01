"""Isolated PostgreSQL/upload adapter for report tombstone restore drills."""

from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import stat
import time
from typing import Callable, Iterator, Literal
import uuid

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Connection, Engine

from backend.app.services import report_restore_tombstones as tombstones
from backend.app.services.report_restore_handoff import PostgreSQLRuntimeIdentity
from backend.app.services.report_storage import (
    REPORT_STORAGE_TRANSACTION_LOCK_KEY,
    _opened_flat_upload_inventory,
)
from backend.app.services.report_image_crypto import MAX_ENVELOPE_BYTES
from backend.app.uploads import descriptor_acl_is_absent, expected_upload_file_mode
from scripts.delete_reports import (
    ReportDeletionQuarantine,
    ReportDeletionWorkerError,
)
from scripts.walksafe_environment_identity import (
    database_identity_sha256,
    explicit_restore_database_url,
    postgresql_database_name,
    sqlalchemy_psycopg_url,
)


RESTORE_WORKER_ROLE = "walksafe_report_restore_worker"
RESTORE_AUTHORIZER_ROLE = "walksafe_report_restore_authorizer"
TARGET_ADVISORY_LOCK_KEY = "walksafe-report-restore-reapply-target-v1"
_DRILL_DATABASE_SEGMENT = re.compile(r"(^|[-_])(test|drill)([-_]|$)", re.IGNORECASE)
_DRILL_PATH_SEGMENT = re.compile(r"(^|[-_.])(test|drill)([-_.]|$)", re.IGNORECASE)
_STORAGE_NAME = re.compile(
    r"^(?P<report_id>[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12})\.wse$"
)
_CONTENT_SUFFIX = {
    "image/jpeg": "jpg",
    "image/png": "png",
    "image/webp": "webp",
}


def _fail(code: str, message: str) -> tombstones.ReportRestoreTombstoneError:
    return tombstones.ReportRestoreTombstoneError(code, message)


@dataclass(frozen=True, slots=True)
class RestoreInventoryContext:
    restore_run_id: uuid.UUID
    backup_run_id: str
    backup_manifest_sha256: str
    restore_receipt_sha256: str
    data_boundary_id: str
    privacy_hmac_key_version: int
    source_identity_sha256: str
    source_backup_created_at: datetime


@dataclass(frozen=True, slots=True)
class _ArtifactSnapshot:
    device: int
    inode: int
    mode: int
    uid: int
    gid: int
    link_count: int
    size: int
    sha256: str


def _directory_metadata(path: Path) -> os.stat_result:
    try:
        metadata = path.stat(follow_symlinks=False)
    except OSError as exc:
        raise _fail(
            "restore_artifact_target_unavailable",
            "A report artifact root is unavailable.",
        ) from exc
    if path.is_symlink() or not stat.S_ISDIR(metadata.st_mode):
        raise _fail(
            "restore_artifact_target_unsafe",
            "A report artifact root is not a real directory.",
        )
    return metadata


def _validated_artifact_roots(
    source: Path,
    target: Path,
) -> tuple[Path, Path, tuple[int, int], tuple[int, int]]:
    if not source.is_absolute() or not target.is_absolute():
        raise _fail(
            "restore_artifact_target_unsafe",
            "Source and target artifact roots must be absolute paths.",
        )
    try:
        source_resolved = source.resolve(strict=True)
        target_resolved = target.resolve(strict=True)
    except OSError as exc:
        raise _fail(
            "restore_artifact_target_unavailable",
            "Source or target artifact storage is unavailable.",
        ) from exc
    source_metadata = _directory_metadata(source_resolved)
    target_metadata = _directory_metadata(target_resolved)
    source_identity = source_metadata.st_dev, source_metadata.st_ino
    target_identity = target_metadata.st_dev, target_metadata.st_ino
    if (
        source_resolved == target_resolved
        or source_resolved in target_resolved.parents
        or target_resolved in source_resolved.parents
        or source_identity == target_identity
        or target_metadata.st_uid != os.geteuid()
        or stat.S_IMODE(target_metadata.st_mode) & 0o007
        or not any(_DRILL_PATH_SEGMENT.search(part) for part in target_resolved.parts)
    ):
        raise _fail(
            "restore_artifact_target_unsafe",
            "The artifact target is not an exact isolated test/drill directory.",
        )
    return source_resolved, target_resolved, source_identity, target_identity


def _open_pinned_artifact_root(
    path: Path,
    *,
    expected_identity: tuple[int, int],
) -> tuple[int, tuple[int, int, int, int, int]]:
    descriptor = os.open(
        path,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        opened = os.fstat(descriptor)
        current = path.stat(follow_symlinks=False)
        identity = (
            opened.st_dev,
            opened.st_ino,
            opened.st_mode,
            opened.st_uid,
            opened.st_gid,
        )
        if (
            not stat.S_ISDIR(opened.st_mode)
            or opened.st_uid != os.geteuid()
            or (opened.st_dev, opened.st_ino) != expected_identity
            or (opened.st_dev, opened.st_ino)
            != (current.st_dev, current.st_ino)
            or not descriptor_acl_is_absent(descriptor)
        ):
            raise OSError("artifact root is unsafe")
        expected_upload_file_mode(opened)
        return descriptor, identity
    except BaseException:
        os.close(descriptor)
        raise


def _capture_artifact_snapshot(path: Path) -> _ArtifactSnapshot:
    try:
        descriptor = os.open(
            path,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
            | getattr(os, "O_NONBLOCK", 0),
        )
    except OSError as exc:
        raise _fail(
            "restore_inventory_incomplete",
            "A report artifact cannot be opened safely.",
        ) from exc
    try:
        before = os.fstat(descriptor)
        path_before = path.stat(follow_symlinks=False)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_uid != os.geteuid()
            or before.st_nlink != 1
            or stat.S_IMODE(before.st_mode) & 0o007
            or not 0 < before.st_size <= MAX_ENVELOPE_BYTES
            or not descriptor_acl_is_absent(descriptor)
            or (before.st_dev, before.st_ino)
            != (path_before.st_dev, path_before.st_ino)
        ):
            raise _fail(
                "restore_inventory_incomplete",
                "A report artifact has unsafe metadata.",
            )
        digest = hashlib.sha256()
        remaining = before.st_size
        while remaining:
            chunk = os.read(descriptor, min(1024 * 1024, remaining))
            if not chunk:
                raise _fail(
                    "restore_inventory_incomplete",
                    "A report artifact was truncated while inventory was captured.",
                )
            digest.update(chunk)
            remaining -= len(chunk)
        if os.read(descriptor, 1):
            raise _fail(
                "restore_inventory_incomplete",
                "A report artifact grew while inventory was captured.",
            )
        after = os.fstat(descriptor)
        path_after = path.stat(follow_symlinks=False)
        acl_absent = descriptor_acl_is_absent(descriptor)
    finally:
        os.close(descriptor)
    if (
        not acl_absent
        or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
        or (before.st_dev, before.st_ino)
        != (path_after.st_dev, path_after.st_ino)
    ):
        raise _fail(
            "restore_inventory_incomplete",
            "A report artifact changed while inventory was captured.",
        )
    return _ArtifactSnapshot(
        device=before.st_dev,
        inode=before.st_ino,
        mode=before.st_mode,
        uid=before.st_uid,
        gid=before.st_gid,
        link_count=before.st_nlink,
        size=before.st_size,
        sha256=digest.hexdigest(),
    )


def _sha256_file(path: Path) -> tuple[int, str]:
    snapshot = _capture_artifact_snapshot(path)
    return snapshot.size, snapshot.sha256


class PostgresReportRestoreTarget(tombstones.ReportRestoreReapplyTarget):
    """One pinned, role-reduced connection to a disposable restored target."""

    def __init__(
        self,
        *,
        engine: Engine,
        connection: Connection,
        target_database_url: str,
        target_identity_sha256: str,
        runtime_identity: PostgreSQLRuntimeIdentity,
        source_upload_root: Path,
        upload_root: Path,
        source_root_descriptor: int,
        source_root_identity: tuple[int, int, int, int, int],
        target_root_descriptor: int,
        target_root_identity: tuple[int, int, int, int, int],
        backend_pid: int,
        target_advisory_key: int,
        storage_advisory_key: int,
        mutation_guard: Callable[[], None],
        target_admission_notifier: Callable[[PostgreSQLRuntimeIdentity, int], None],
        target_admission_timeout_seconds: int,
        inventory_context: RestoreInventoryContext,
    ) -> None:
        self.engine = engine
        self.connection = connection
        self.target_database_url = target_database_url
        self.target_identity_sha256 = target_identity_sha256
        self.runtime_identity = runtime_identity
        self.source_upload_root = source_upload_root
        self.upload_root = upload_root
        self.source_root_descriptor = source_root_descriptor
        self.source_root_identity = source_root_identity
        self.target_root_descriptor = target_root_descriptor
        self.target_root_identity = target_root_identity
        self.upload_root_identity = target_root_identity[:2]
        self.backend_pid = backend_pid
        self.target_advisory_key = target_advisory_key
        self.storage_advisory_key = storage_advisory_key
        self.mutation_guard = mutation_guard
        self.target_admission_notifier = target_admission_notifier
        self.target_admission_timeout_seconds = target_admission_timeout_seconds
        self.inventory_context = inventory_context
        self._transaction = None
        self._plan: tombstones.ReportRestoreReapplyPlan | None = None
        self._staged: list[ReportDeletionQuarantine] = []
        self._last_inventory: tombstones.RestoredReportInventory | None = None
        self.mutation_state: Literal[
            "NOT_STARTED",
            "IN_PROGRESS",
            "DB_COMMITTED_RECONCILIATION_REQUIRED",
            "DB_COMMITTED_POSTCHECK_REQUIRED",
            "DB_COMMITTED_POSTCHECK_FAILED",
            "COMPLETE",
        ] = "NOT_STARTED"
        self.reconciliation_error: tombstones.ReportRestoreTombstoneError | None = None
        self.postcheck_error: tombstones.ReportRestoreTombstoneError | None = None
        self._admission_fenced = False
        self._closed = False

    @staticmethod
    def _advisory_parts(value: int) -> tuple[int, int, int]:
        unsigned = value & ((1 << 64) - 1)
        return unsigned >> 32, unsigned & ((1 << 32) - 1), 1

    def _verify_artifact_roots(self) -> None:
        for path, descriptor, expected in (
            (
                self.source_upload_root,
                self.source_root_descriptor,
                self.source_root_identity,
            ),
            (self.upload_root, self.target_root_descriptor, self.target_root_identity),
        ):
            try:
                opened = os.fstat(descriptor)
                current = path.stat(follow_symlinks=False)
            except OSError as exc:
                raise _fail(
                    "restore_artifact_target_unavailable",
                    "A pinned artifact root is unavailable.",
                ) from exc
            observed = (
                opened.st_dev,
                opened.st_ino,
                opened.st_mode,
                opened.st_uid,
                opened.st_gid,
            )
            if (
                observed != expected
                or (opened.st_dev, opened.st_ino)
                != (current.st_dev, current.st_ino)
                or not descriptor_acl_is_absent(descriptor)
            ):
                raise _fail(
                    "restore_artifact_target_unsafe",
                    "A pinned artifact root changed during restore reapply.",
                )

    def _verify_mutation_guard(self) -> None:
        try:
            self.mutation_guard()
        except tombstones.ReportRestoreTombstoneError:
            raise
        except BaseException as exc:
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
            raise _fail(
                "restore_source_fence_not_held",
                "The source restore fence cannot be verified.",
            ) from exc
        self._verify_artifact_roots()
        self._assert_target_fence()

    def _ensure_target_admission_fence(self) -> None:
        if self._admission_fenced:
            self._assert_target_fence()
            return
        self.target_admission_notifier(self.runtime_identity, self.backend_pid)
        deadline = time.monotonic() + self.target_admission_timeout_seconds
        while True:
            try:
                status = self.connection.execute(
                    text(
                        "SELECT NOT database.datallowconn, "
                        "(SELECT count(*) FROM pg_catalog.pg_stat_activity "
                        " WHERE datid = database.oid AND pid <> pg_backend_pid() "
                        " AND backend_type <> 'autovacuum worker') = 0 "
                        "FROM pg_catalog.pg_database AS database "
                        "WHERE database.datname = current_database()"
                    )
                ).one()
                self.connection.rollback()
            except Exception as exc:
                self.connection.rollback()
                raise _fail(
                    "restore_target_isolation_unavailable",
                    "The restored-target admission fence cannot be verified.",
                ) from exc
            if status[0] is True and status[1] is True:
                self._admission_fenced = True
                self._assert_target_fence()
                return
            if time.monotonic() >= deadline:
                raise _fail(
                    "restore_target_admission_fence_required",
                    "The target database did not enter exclusive no-new-connections mode.",
                )
            time.sleep(0.1)

    def _assert_target_fence(self) -> None:
        try:
            status = self.connection.execute(
                text(
                    "SELECT pg_backend_pid(), current_user, session_user, "
                    "current_setting('session_replication_role'), "
                    "(SELECT datallowconn FROM pg_catalog.pg_database "
                    " WHERE datname = current_database()), "
                    "(SELECT count(*) FROM pg_catalog.pg_stat_activity "
                    " WHERE datid = (SELECT oid FROM pg_catalog.pg_database "
                    " WHERE datname = current_database()) "
                    " AND pid <> pg_backend_pid() "
                    " AND backend_type <> 'autovacuum worker'), "
                    "NOT EXISTS (SELECT 1 FROM pg_catalog.pg_subscription "
                    " WHERE subenabled)"
                )
            ).one()
            locks = self.connection.execute(
                text(
                    "SELECT classid::bigint, objid::bigint, objsubid "
                    "FROM pg_catalog.pg_locks "
                    "WHERE locktype = 'advisory' AND pid = pg_backend_pid() "
                    "AND mode = 'ExclusiveLock' AND granted"
                )
            ).all()
            if self._transaction is None:
                self.connection.rollback()
        except Exception as exc:
            if self._transaction is None:
                self.connection.rollback()
            raise _fail(
                "restore_target_isolation_unavailable",
                "The sustained restored-target fence cannot be verified.",
            ) from exc
        expected_locks = {
            self._advisory_parts(self.target_advisory_key),
            self._advisory_parts(self.storage_advisory_key),
        }
        actual_locks = {
            (int(row[0]), int(row[1]), int(row[2])) for row in locks
        }
        if (
            int(status[0]) != self.backend_pid
            or status[1] != RESTORE_WORKER_ROLE
            or status[1] == status[2]
            or status[3] != "origin"
            or (self._admission_fenced and status[4] is not False)
            or int(status[5]) != 0
            or status[6] is not True
            or not expected_locks.issubset(actual_locks)
        ):
            raise _fail(
                "restore_target_not_isolated",
                "The restored target no longer has its exclusive isolation fence.",
            )

    def _assert_isolated(self) -> None:
        self._assert_target_fence()

    def _assert_role(self) -> None:
        try:
            safe = bool(
                self.connection.execute(
                    text(
                        "SELECT current_user = :worker_role "
                        "AND current_user <> session_user "
                        "AND NOT pg_has_role(session_user, :authorizer_role, 'MEMBER') "
                        "AND NOT pg_has_role(current_user, :authorizer_role, 'MEMBER') "
                        "AND EXISTS (SELECT 1 FROM pg_catalog.pg_roles AS worker "
                        " WHERE worker.rolname = :worker_role "
                        " AND NOT worker.rolsuper AND NOT worker.rolinherit "
                        " AND NOT worker.rolcreaterole AND NOT worker.rolcreatedb "
                        " AND NOT worker.rolcanlogin AND NOT worker.rolreplication "
                        " AND NOT worker.rolbypassrls AND worker.rolconfig IS NULL) "
                        "AND EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members AS membership "
                        " JOIN pg_catalog.pg_roles AS worker ON worker.oid = membership.roleid "
                        " JOIN pg_catalog.pg_roles AS member ON member.oid = membership.member "
                        " WHERE worker.rolname = :worker_role AND member.rolname = session_user "
                        " AND NOT membership.admin_option "
                        " AND NOT membership.inherit_option AND membership.set_option) "
                        "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members AS membership "
                        " JOIN pg_catalog.pg_roles AS member ON member.oid = membership.member "
                        " JOIN pg_catalog.pg_roles AS worker ON worker.oid = membership.roleid "
                        " WHERE member.rolname = session_user AND worker.rolname <> :worker_role) "
                        "AND EXISTS (SELECT 1 FROM pg_catalog.pg_roles AS login "
                        " WHERE login.rolname = session_user AND NOT login.rolsuper "
                        " AND NOT login.rolcreaterole AND NOT login.rolcreatedb "
                        " AND login.rolcanlogin AND NOT login.rolreplication "
                        " AND NOT login.rolbypassrls AND login.rolconfig IS NULL) "
                        "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_database AS database "
                        " JOIN pg_catalog.pg_roles AS owner ON owner.oid = database.datdba "
                        " WHERE database.datname = current_database() "
                        " AND owner.rolname IN (session_user, :worker_role)) "
                        "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_class AS relation "
                        " JOIN pg_catalog.pg_roles AS owner ON owner.oid = relation.relowner "
                        " WHERE owner.rolname IN (session_user, :worker_role)) "
                        "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_proc AS routine "
                        " JOIN pg_catalog.pg_roles AS owner ON owner.oid = routine.proowner "
                        " WHERE owner.rolname IN (session_user, :worker_role)) "
                        "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_type AS data_type "
                        " JOIN pg_catalog.pg_roles AS owner ON owner.oid = data_type.typowner "
                        " WHERE owner.rolname IN (session_user, :worker_role)) "
                        "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace AS namespace "
                        " JOIN pg_catalog.pg_roles AS owner ON owner.oid = namespace.nspowner "
                        " WHERE owner.rolname IN (session_user, :worker_role)) "
                        "AND NOT EXISTS (SELECT 1 FROM information_schema.role_table_grants "
                        " WHERE grantee = session_user) "
                        "AND NOT EXISTS (SELECT 1 FROM information_schema.role_column_grants "
                        " WHERE grantee = session_user) "
                        "AND NOT EXISTS (SELECT 1 FROM information_schema.role_routine_grants "
                        " WHERE grantee = session_user) "
                        "AND NOT EXISTS (SELECT 1 FROM information_schema.role_usage_grants "
                        " WHERE grantee = session_user) "
                        "AND (SELECT count(*) FROM information_schema.role_table_grants "
                        " WHERE grantee = :worker_role) = 6 "
                        "AND NOT EXISTS (SELECT 1 FROM information_schema.role_table_grants "
                        " WHERE grantee = :worker_role AND (table_schema <> 'public' OR "
                        " (table_name, privilege_type) NOT IN ("
                        " ('report_deletion_tombstones', 'SELECT'), "
                        " ('report_restore_reapply_authorizations', 'SELECT'), "
                        " ('report_restore_reapply_authorized_actions', 'SELECT'), "
                        " ('report_restore_reapply_receipts', 'SELECT'), "
                        " ('report_restore_reapply_effects', 'SELECT'), "
                        " ('report_restore_reapply_postchecks', 'SELECT')))) "
                        "AND NOT EXISTS (SELECT 1 FROM information_schema.role_column_grants "
                        " WHERE grantee = :worker_role AND NOT (table_schema = 'public' AND ("
                        " (table_name = 'reports' AND privilege_type = 'SELECT' AND "
                        "  column_name IN ('id', 'privacy_subject_hmac', 'account_generation', "
                        "                  'image_path', 'image_content_type')) OR "
                        " (table_name = 'report_image_objects' AND privilege_type = 'SELECT' AND "
                        "  column_name IN ('report_id', 'storage_name', 'envelope_sha256', "
                        "                  'envelope_size', 'content_type')) OR "
                        " (table_name = 'report_deletion_legal_holds' AND privilege_type = 'SELECT' AND "
                        "  column_name IN ('report_id', 'expires_at')) OR "
                        " (table_name = 'report_deletion_tombstones' AND privilege_type = 'SELECT') OR "
                        " (table_name IN ('report_restore_reapply_authorizations', "
                        "                 'report_restore_reapply_authorized_actions', "
                        "                 'report_restore_reapply_receipts', "
                        "                 'report_restore_reapply_effects', "
                        "                 'report_restore_reapply_postchecks') "
                        "  AND privilege_type = 'SELECT')))) "
                        "AND (SELECT count(*) FROM information_schema.role_routine_grants "
                        " WHERE grantee = :worker_role) = 3 "
                        "AND NOT EXISTS (SELECT 1 FROM information_schema.role_routine_grants "
                        " WHERE grantee = :worker_role AND (routine_schema <> 'public' OR "
                        " routine_name NOT IN ('walksafe_execute_report_restore_action', "
                        "                      'walksafe_finish_report_restore_reapply', "
                        "                      'walksafe_record_report_restore_postcheck') OR "
                        " privilege_type <> 'EXECUTE')) "
                        "AND NOT EXISTS (SELECT 1 FROM information_schema.role_usage_grants "
                        " WHERE grantee = :worker_role) "
                        "AND has_schema_privilege(current_user, 'public', 'USAGE') "
                        "AND NOT has_schema_privilege(current_user, 'public', 'CREATE') "
                        "AND has_table_privilege(current_user, "
                        "'public.report_restore_reapply_authorizations', 'SELECT') "
                        "AND has_table_privilege(current_user, "
                        "'public.report_restore_reapply_authorized_actions', 'SELECT') "
                        "AND has_table_privilege(current_user, "
                        "'public.report_restore_reapply_receipts', 'SELECT') "
                        "AND has_table_privilege(current_user, "
                        "'public.report_restore_reapply_effects', 'SELECT') "
                        "AND has_table_privilege(current_user, "
                        "'public.report_restore_reapply_postchecks', 'SELECT') "
                        "AND has_function_privilege(current_user, "
                        "'public.walksafe_execute_report_restore_action(text,uuid)', 'EXECUTE') "
                        "AND has_function_privilege(current_user, "
                        "'public.walksafe_finish_report_restore_reapply(text,text,bytea,timestamptz)', "
                        "'EXECUTE') "
                        "AND has_function_privilege(current_user, "
                        "'public.walksafe_record_report_restore_postcheck(text,text,text)', "
                        "'EXECUTE') "
                        "AND NOT has_table_privilege(current_user, 'public.reports', 'UPDATE') "
                        "AND NOT has_table_privilege(current_user, "
                        "'public.reports', 'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES') "
                        "AND NOT has_table_privilege(current_user, "
                        "'public.report_image_objects', 'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES') "
                        "AND NOT has_table_privilege(current_user, "
                        "'public.report_deletion_legal_holds', 'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES') "
                        "AND NOT has_table_privilege(current_user, "
                        "'public.report_deletion_tombstones', 'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES') "
                        "AND NOT has_table_privilege(current_user, "
                        "'public.report_restore_reapply_authorizations', "
                        "'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES') "
                        "AND NOT has_table_privilege(current_user, "
                        "'public.report_restore_reapply_authorized_actions', "
                        "'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES') "
                        "AND NOT has_table_privilege(current_user, "
                        "'public.report_restore_reapply_receipts', "
                        "'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES') "
                        "AND NOT has_table_privilege(current_user, "
                        "'public.report_restore_reapply_effects', "
                        "'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES') "
                        "AND NOT has_table_privilege(current_user, "
                        "'public.report_restore_reapply_postchecks', "
                        "'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES')"
                    ),
                    {
                        "worker_role": RESTORE_WORKER_ROLE,
                        "authorizer_role": RESTORE_AUTHORIZER_ROLE,
                    },
                ).scalar_one()
            )
            self.connection.rollback()
        except Exception as exc:
            self.connection.rollback()
            raise _fail(
                "restore_target_role_unsafe",
                "The restore worker role cannot be verified.",
            ) from exc
        if not safe:
            raise _fail(
                "restore_target_role_unsafe",
                "The restore worker role violates its least-privilege contract.",
            )

    def _committed_quarantine_keys(self) -> set[tuple[uuid.UUID, uuid.UUID]]:
        rows = self.connection.execute(
            text(
                "SELECT effect.request_id, effect.report_id "
                "FROM public.report_restore_reapply_effects AS effect "
                "JOIN public.report_restore_reapply_receipts AS receipt "
                "ON receipt.plan_sha256 = effect.plan_sha256 "
                "UNION "
                "SELECT tombstone.request_id, tombstone.report_id "
                "FROM public.report_deletion_tombstones AS tombstone"
            )
        ).all()
        self.connection.rollback()
        return {(row[0], row[1]) for row in rows}

    def reconcile_quarantine(self) -> tuple[int, int]:
        self._ensure_target_admission_fence()
        self._verify_mutation_guard()
        try:
            committed = self._committed_quarantine_keys()
            result = ReportDeletionQuarantine.reconcile_all(
                self.upload_root,
                is_committed=lambda request_id, report_id: (
                    request_id,
                    report_id,
                )
                in committed,
                expected_upload_root_identity=self.upload_root_identity,
            )
            if self._transaction is None:
                self._staged = []
            self._verify_mutation_guard()
            return result
        except Exception as exc:
            if isinstance(exc, tombstones.ReportRestoreTombstoneError):
                raise
            raise _fail(
                "restore_artifact_reconciliation_required",
                "Pending report artifact quarantine cannot be reconciled.",
            ) from exc

    def _bound_inventory_item(
        self,
        *,
        report_id: uuid.UUID,
        report: object | None,
        image_object: object | None,
        storage_name: str,
        artifact_path: Path,
    ) -> tombstones.RestoredReportInventoryItem:
        snapshot = _capture_artifact_snapshot(artifact_path)
        if report is None:
            if image_object is not None:
                raise _fail(
                    "restore_inventory_incomplete",
                    "An orphan artifact still has image metadata.",
                )
            return tombstones.RestoredReportInventoryItem(
                report_id=report_id,
                privacy_subject_hmac=None,
                account_generation=None,
                report_row_present=False,
                bound_artifact_count=1,
                image_path=None,
                image_content_type=None,
                artifact_storage_name=storage_name,
                artifact_sha256=snapshot.sha256,
                artifact_size=snapshot.size,
                artifact_device_inode=f"{snapshot.device}:{snapshot.inode}",
            )
        subject_hmac, generation = report[1], report[2]  # type: ignore[index]
        if (subject_hmac is None) != (generation is None):
            raise _fail(
                "restore_inventory_incomplete",
                "A restored report has a partial owner binding.",
            )
        suffix = _CONTENT_SUFFIX.get(report[4])  # type: ignore[index]
        logical = PurePosixPath(report[3])  # type: ignore[index]
        if (
            image_object is None
            or suffix is None
            or logical.parent != PurePosixPath("/uploads")
            or logical.name != f"{report_id}.{suffix}"
            or image_object[1] != f"{report_id}.wse"  # type: ignore[index]
            or image_object[1] != storage_name  # type: ignore[index]
            or image_object[4] != report[4]  # type: ignore[index]
            or snapshot.size != image_object[3]  # type: ignore[index]
            or snapshot.sha256 != image_object[2]  # type: ignore[index]
        ):
            raise _fail(
                "restore_inventory_incomplete",
                "The restored report database and artifact bindings disagree.",
            )
        return tombstones.RestoredReportInventoryItem(
            report_id=report_id,
            privacy_subject_hmac=subject_hmac,
            account_generation=generation,
            report_row_present=True,
            bound_artifact_count=1,
            image_path=str(logical),
            image_content_type=report[4],  # type: ignore[index]
            artifact_storage_name=storage_name,
            artifact_sha256=snapshot.sha256,
            artifact_size=snapshot.size,
            artifact_device_inode=f"{snapshot.device}:{snapshot.inode}",
        )

    def _inventory_items(self) -> tuple[tombstones.RestoredReportInventoryItem, ...]:
        reports = {
            row[0]: row
            for row in self.connection.execute(
                text(
                    "SELECT id, privacy_subject_hmac, account_generation, "
                    "image_path, image_content_type FROM public.reports ORDER BY id"
                )
            ).all()
        }
        if self.connection.execute(
            text(
                "SELECT EXISTS ("
                "SELECT 1 FROM public.report_deletion_tombstones AS tombstone "
                "JOIN public.reports AS report ON report.id = tombstone.report_id)"
            )
        ).scalar_one():
            raise _fail(
                "restore_inventory_incomplete",
                "A live restored report conflicts with a durable local deletion tombstone.",
            )
        objects = {
            row[0]: row
            for row in self.connection.execute(
                text(
                    "SELECT report_id, storage_name, envelope_sha256, envelope_size, "
                    "content_type FROM public.report_image_objects ORDER BY report_id"
                )
            ).all()
        }
        if set(objects) - set(reports):
            raise _fail(
                "restore_inventory_incomplete",
                "The target has an image metadata row without its report.",
            )
        items: dict[uuid.UUID, tombstones.RestoredReportInventoryItem] = {}
        with _opened_flat_upload_inventory(self.upload_root) as actual:
            actual_by_report: dict[uuid.UUID, tuple[str, Path]] = {}
            for name, path in actual.items():
                match = _STORAGE_NAME.fullmatch(name)
                if match is None:
                    raise _fail(
                        "restore_inventory_incomplete",
                        "The target upload root contains an unknown artifact.",
                    )
                report_id = uuid.UUID(match.group("report_id"))
                if report_id in actual_by_report:
                    raise _fail(
                        "restore_inventory_incomplete",
                        "The target upload root repeats a report artifact.",
                    )
                actual_by_report[report_id] = (name, path)
            for report_id, report in reports.items():
                image_object = objects.get(report_id)
                actual_object = actual_by_report.get(report_id)
                if actual_object is None:
                    raise _fail(
                        "restore_inventory_incomplete",
                        "The restored report database and artifact bindings disagree.",
                    )
                items[report_id] = self._bound_inventory_item(
                    report_id=report_id,
                    report=report,
                    image_object=image_object,
                    storage_name=actual_object[0],
                    artifact_path=actual_object[1],
                )
            for report_id in set(actual_by_report) - set(reports):
                actual_object = actual_by_report[report_id]
                items[report_id] = self._bound_inventory_item(
                    report_id=report_id,
                    report=None,
                    image_object=None,
                    storage_name=actual_object[0],
                    artifact_path=actual_object[1],
                )
        return tuple(items[key] for key in sorted(items, key=str))

    def _current_inventory_item(
        self,
        report_id: uuid.UUID,
    ) -> tombstones.RestoredReportInventoryItem:
        report = self.connection.execute(
            text(
                "SELECT id, privacy_subject_hmac, account_generation, "
                "image_path, image_content_type FROM public.reports "
                "WHERE id = :report_id"
            ),
            {"report_id": report_id},
        ).one_or_none()
        image_object = self.connection.execute(
            text(
                "SELECT report_id, storage_name, envelope_sha256, envelope_size, "
                "content_type FROM public.report_image_objects "
                "WHERE report_id = :report_id"
            ),
            {"report_id": report_id},
        ).one_or_none()
        storage_name = f"{report_id}.wse"
        path = Path(f"/proc/self/fd/{self.target_root_descriptor}") / storage_name
        return self._bound_inventory_item(
            report_id=report_id,
            report=report,
            image_object=image_object,
            storage_name=storage_name,
            artifact_path=path,
        )

    def build_inventory(
        self,
        *,
        reconcile: bool = False,
    ) -> tombstones.RestoredReportInventory:
        if self._transaction is not None:
            raise RuntimeError("cannot inventory during a reapply transaction")
        self._verify_artifact_roots()
        self._assert_isolated()
        if reconcile:
            self.reconcile_quarantine()
        else:
            try:
                ReportDeletionQuarantine.assert_no_pending(
                    self.upload_root,
                    expected_upload_root_identity=self.upload_root_identity,
                )
            except ReportDeletionWorkerError as exc:
                raise _fail(
                    "restore_artifact_reconciliation_required",
                    "Pending artifact recovery requires an explicit apply run.",
                ) from exc
        transaction = self.connection.begin()
        try:
            self.connection.execute(
                text("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE READ ONLY")
            )
            self.connection.execute(
                text(
                    "SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"
                ),
                {"lock_key": REPORT_STORAGE_TRANSACTION_LOCK_KEY},
            )
            items = self._inventory_items()
            observed_at = self.connection.execute(
                text("SELECT clock_timestamp()")
            ).scalar_one()
            raw = tombstones.build_restored_report_inventory_bytes(
                restore_run_id=self.inventory_context.restore_run_id,
                backup_run_id=self.inventory_context.backup_run_id,
                backup_manifest_sha256=(
                    self.inventory_context.backup_manifest_sha256
                ),
                restore_receipt_sha256=(
                    self.inventory_context.restore_receipt_sha256
                ),
                data_boundary_id=self.inventory_context.data_boundary_id,
                privacy_hmac_key_version=(
                    self.inventory_context.privacy_hmac_key_version
                ),
                source_identity_sha256=(
                    self.inventory_context.source_identity_sha256
                ),
                target_identity_sha256=self.target_identity_sha256,
                source_backup_created_at=(
                    self.inventory_context.source_backup_created_at
                ),
                observed_at=observed_at,
                items=items,
            )
            transaction.commit()
        except BaseException as exc:
            if transaction.is_active:
                transaction.rollback()
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
            if isinstance(exc, tombstones.ReportRestoreTombstoneError):
                raise
            raise _fail(
                "restore_inventory_incomplete",
                "The restored report inventory could not be captured safely.",
            ) from exc
        self._assert_isolated()
        self._verify_artifact_roots()
        inventory = tombstones.parse_restored_report_inventory(raw)
        self._last_inventory = inventory
        return inventory

    def _record_postcheck(
        self,
        plan_sha256: str,
        *,
        outcome: Literal["PASSED", "FAILED"],
        failure_code: str | None,
    ) -> None:
        try:
            self.connection.execute(
                text(
                    "SELECT public.walksafe_record_report_restore_postcheck("
                    ":plan_sha256, :outcome, :failure_code)"
                ),
                {
                    "plan_sha256": plan_sha256,
                    "outcome": outcome,
                    "failure_code": failure_code,
                },
            )
            self.connection.commit()
        except BaseException as exc:
            self.connection.rollback()
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
            raise _fail(
                "restore_reapply_postcheck_record_failed",
                "The committed restore post-check outcome could not be recorded immutably.",
            ) from exc

    def load_receipt(self, plan_sha256: str) -> bytes | None:
        if self._transaction is not None:
            raise RuntimeError("cannot load a receipt during an active reapply")
        self._ensure_target_admission_fence()
        self._verify_mutation_guard()
        if self.connection.in_transaction():
            self.connection.rollback()
        sticky_postcheck_error = self.postcheck_error
        row = self.connection.execute(
            text(
                "SELECT receipt.receipt_bytes, postcheck.outcome, "
                "postcheck.failure_code "
                "FROM public.report_restore_reapply_receipts AS receipt "
                "LEFT JOIN public.report_restore_reapply_postchecks AS postcheck "
                "ON postcheck.plan_sha256 = receipt.plan_sha256 "
                "WHERE receipt.plan_sha256 = :plan_sha256"
            ),
            {"plan_sha256": plan_sha256},
        ).one_or_none()
        self.connection.rollback()
        receipt = bytes(row[0]) if row is not None else None
        outcome = row[1] if row is not None else None
        failure_code = row[2] if row is not None else None
        if receipt is not None:
            self.mutation_state = "DB_COMMITTED_RECONCILIATION_REQUIRED"
        try:
            self.reconcile_quarantine()
        except tombstones.ReportRestoreTombstoneError as exc:
            self.reconciliation_error = exc
            raise
        self.reconciliation_error = None
        if outcome == "FAILED":
            persistent_error = _fail(
                str(failure_code),
                "The committed restore reapply has an immutable failed post-check.",
            )
            self.postcheck_error = persistent_error
            self.mutation_state = "DB_COMMITTED_POSTCHECK_FAILED"
            raise tombstones.ReportRestorePostCommitError(
                persistent_error.code,
                persistent_error.message,
            ) from persistent_error
        if sticky_postcheck_error is not None:
            if receipt is not None and outcome is None:
                try:
                    self._record_postcheck(
                        plan_sha256,
                        outcome="FAILED",
                        failure_code=sticky_postcheck_error.code,
                    )
                except tombstones.ReportRestoreTombstoneError:
                    pass
            self.mutation_state = "DB_COMMITTED_POSTCHECK_FAILED"
            raise tombstones.ReportRestorePostCommitError(
                sticky_postcheck_error.code,
                sticky_postcheck_error.message,
            ) from sticky_postcheck_error
        if receipt is not None:
            if outcome is None:
                self.mutation_state = "DB_COMMITTED_POSTCHECK_REQUIRED"
                self._verify_mutation_guard()
                self._record_postcheck(
                    plan_sha256,
                    outcome="PASSED",
                    failure_code=None,
                )
            elif outcome != "PASSED":
                raise _fail(
                    "restore_reapply_postcheck_invalid",
                    "The committed restore post-check outcome is invalid.",
                )
            self.mutation_state = "COMPLETE"
        return receipt

    def _assert_authorized_plan(
        self,
        plan: tombstones.ReportRestoreReapplyPlan,
    ) -> None:
        authorization = self.connection.execute(
            text(
                "SELECT restore_run_id, data_boundary_id, target_identity_sha256, "
                "source_fence_sha256, trusted_head_sha256, inventory_sha256, "
                "action_count FROM public.report_restore_reapply_authorizations "
                "WHERE plan_sha256 = :plan_sha256"
            ),
            {"plan_sha256": plan.plan_sha256},
        ).one_or_none()
        if authorization is None:
            raise _fail(
                "restore_target_authorization_required",
                "A separate restore authorizer has not approved this exact plan.",
            )
        expected_header = (
            plan.restore_run_id,
            plan.data_boundary_id,
            plan.target_identity_sha256,
            tombstones.report_restore_source_fence_sha256(plan.source_fence),
            plan.trusted_head_sha256,
            plan.inventory_sha256,
            len(plan.actions),
        )
        actions = self.connection.execute(
            text(
                "SELECT report_id, request_id, tombstone_id, privacy_subject_hmac, "
                "account_generation, request_status_version, entry_sha256, "
                "report_row_present, bound_artifact_count "
                "FROM public.report_restore_reapply_authorized_actions "
                "WHERE plan_sha256 = :plan_sha256 ORDER BY report_id"
            ),
            {"plan_sha256": plan.plan_sha256},
        ).all()
        expected_actions = [
            (
                action.report_id,
                action.request_id,
                action.tombstone_id,
                action.privacy_subject_hmac,
                action.account_generation,
                action.request_status_version,
                action.entry_sha256,
                action.report_row_present,
                action.bound_artifact_count,
            )
            for action in plan.actions
        ]
        if tuple(authorization) != expected_header or [tuple(row) for row in actions] != (
            expected_actions
        ):
            raise _fail(
                "restore_target_authorization_invalid",
                "The stored restore authorization does not match the exact plan/actions.",
            )

    def begin(self, plan: tombstones.ReportRestoreReapplyPlan) -> None:
        if self._transaction is not None:
            raise RuntimeError("a report restore reapply is already active")
        self._ensure_target_admission_fence()
        if (
            plan.target_identity_sha256 != self.target_identity_sha256
            or plan.restore_run_id != self.inventory_context.restore_run_id
            or plan.backup_run_id != self.inventory_context.backup_run_id
            or plan.backup_manifest_sha256
            != self.inventory_context.backup_manifest_sha256
            or plan.restore_receipt_sha256
            != self.inventory_context.restore_receipt_sha256
            or plan.data_boundary_id != self.inventory_context.data_boundary_id
            or plan.privacy_hmac_key_version
            != self.inventory_context.privacy_hmac_key_version
            or plan.source_identity_sha256
            != self.inventory_context.source_identity_sha256
        ):
            raise _fail(
                "restore_target_identity_mismatch",
                "The reapply plan does not name this isolated restore target.",
            )
        if (
            self._last_inventory is None
            or self._last_inventory.inventory_sha256 != plan.inventory_sha256
        ):
            raise _fail(
                "restore_reapply_target_changed",
                "The reapply plan was not built from this pinned target snapshot.",
            )
        self._verify_mutation_guard()
        ReportDeletionQuarantine.assert_no_pending(
            self.upload_root,
            expected_upload_root_identity=self.upload_root_identity,
        )
        self._transaction = self.connection.begin()
        try:
            self.connection.execute(
                text("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE READ WRITE")
            )
            self.connection.execute(
                text(
                    "SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"
                ),
                {"lock_key": REPORT_STORAGE_TRANSACTION_LOCK_KEY},
            )
            self._verify_artifact_roots()
            if self._inventory_items() != self._last_inventory.items:
                raise _fail(
                    "restore_reapply_target_changed",
                    "The pinned target changed after its restore inventory was captured.",
                )
            self._assert_authorized_plan(plan)
            self._assert_target_fence()
            self._plan = plan
            self._staged = []
            self.mutation_state = "IN_PROGRESS"
        except BaseException:
            self._transaction.rollback()
            self._transaction = None
            self._plan = None
            raise

    def _storage_name_for_action(
        self,
        action: tombstones.ReportRestoreReapplyAction,
    ) -> str:
        expected_name = f"{action.report_id}.wse"
        self._verify_artifact_roots()
        try:
            expected = next(
                item
                for item in self._last_inventory.items  # type: ignore[union-attr]
                if item.report_id == action.report_id
            )
            current = self._current_inventory_item(action.report_id)
        except (OSError, StopIteration, tombstones.ReportRestoreTombstoneError) as exc:
            raise _fail(
                "restore_reapply_target_changed",
                "The report artifact changed after restore inventory capture.",
            ) from exc
        if (
            current != expected
            or current.report_row_present != action.report_row_present
            or current.bound_artifact_count != action.bound_artifact_count
            or current.artifact_storage_name != expected_name
        ):
            raise _fail(
                "restore_reapply_target_changed",
                "The report/artifact binding changed after inventory capture.",
            )
        self._verify_artifact_roots()
        return expected_name

    def reapply(
        self,
        action: tombstones.ReportRestoreReapplyAction,
    ) -> Literal["DELETED", "ALREADY_ABSENT"]:
        if self._transaction is None or self._plan is None:
            raise RuntimeError("report restore reapply has not begun")
        self._verify_mutation_guard()
        report = self.connection.execute(
            text(
                "SELECT privacy_subject_hmac, account_generation "
                "FROM public.reports WHERE id = :report_id"
            ),
            {"report_id": action.report_id},
        ).one_or_none()
        if (
            action.bound_artifact_count != 1
            or (report is None) == action.report_row_present
        ):
            raise _fail(
                "restore_reapply_target_changed",
                "The report target changed after inventory capture.",
            )
        if report is not None and (
            report[0] != action.privacy_subject_hmac
            or report[1] != action.account_generation
        ):
            raise _fail(
                "restore_reapply_target_changed",
                "The restored report owner binding changed before deletion.",
            )
        held = self.connection.execute(
            text(
                "SELECT 1 FROM public.report_deletion_legal_holds "
                "WHERE report_id = :report_id AND expires_at > clock_timestamp()"
            ),
            {"report_id": action.report_id},
        ).first()
        if held is not None:
            raise _fail(
                "restore_reapply_legal_hold",
                "The restored report is covered by an active legal hold.",
            )
        storage_name = self._storage_name_for_action(action)
        quarantine = ReportDeletionQuarantine(
            self.upload_root,
            request_id=action.request_id,
            report_id=action.report_id,
            expected_upload_root_identity=self.upload_root_identity,
        )
        try:
            quarantine.stage((storage_name,))
        except BaseException as exc:
            try:
                ReportDeletionQuarantine.reconcile_all(
                    self.upload_root,
                    is_committed=lambda _request_id, _report_id: False,
                    expected_upload_root_identity=self.upload_root_identity,
                )
                self._staged = []
            except Exception as recovery_exc:
                raise _fail(
                    "restore_artifact_reconciliation_required",
                    "A partially staged artifact requires explicit recovery.",
                ) from recovery_exc
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
            raise _fail(
                "restore_reapply_artifact_unavailable",
                "The bound report artifact could not be staged safely.",
            ) from exc
        self._staged.append(quarantine)
        result = self.connection.execute(
            text(
                "SELECT public.walksafe_execute_report_restore_action("
                ":plan_sha256, :report_id)"
            ),
            {
                "plan_sha256": self._plan.plan_sha256,
                "report_id": action.report_id,
            },
        ).scalar_one()
        if result != "DELETED":
            raise _fail(
                "restore_reapply_target_invalid",
                "The authorized restore executor returned an invalid result.",
            )
        return result

    def commit(self, receipt: bytes) -> None:
        if self._transaction is None or self._plan is None:
            raise RuntimeError("report restore reapply has not begun")
        parsed = tombstones.parse_report_restore_reapply_receipt(
            receipt,
            plan=self._plan,
        )
        if any(result.result != "DELETED" for result in parsed.results):
            raise _fail(
                "restore_reapply_target_invalid",
                "The PostgreSQL target cannot commit an unperformed action.",
            )
        self.connection.execute(
            text(
                "SELECT public.walksafe_finish_report_restore_reapply("
                ":plan_sha256, :receipt_sha256, :receipt_bytes, :applied_at)"
            ),
            {
                "plan_sha256": self._plan.plan_sha256,
                "receipt_sha256": parsed.receipt_sha256,
                "receipt_bytes": receipt,
                "applied_at": parsed.applied_at,
            },
        )
        try:
            self._verify_mutation_guard()
        except BaseException:
            self.rollback()
            raise
        transaction = self._transaction
        try:
            transaction.commit()
        finally:
            self._transaction = None
        self.mutation_state = "DB_COMMITTED_RECONCILIATION_REQUIRED"
        try:
            for quarantine in self._staged:
                quarantine.finalize()
        except BaseException as exc:
            error = _fail(
                "restore_artifact_reconciliation_required",
                "The database receipt committed, but report artifacts still require reconciliation.",
            )
            self.reconciliation_error = error
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
            raise error from exc
        self._staged = []
        self.reconciliation_error = None
        self.mutation_state = "DB_COMMITTED_POSTCHECK_REQUIRED"
        plan_sha256 = self._plan.plan_sha256
        try:
            self._verify_mutation_guard()
        except BaseException as exc:
            if isinstance(exc, tombstones.ReportRestoreTombstoneError):
                error = exc
            else:
                error = _fail(
                    "restore_reapply_postcommit_check_failed",
                    "The committed restore reapply failed its semantic post-check.",
                )
            self.postcheck_error = error
            self.mutation_state = "DB_COMMITTED_POSTCHECK_FAILED"
            self._plan = None
            try:
                self._record_postcheck(
                    plan_sha256,
                    outcome="FAILED",
                    failure_code=error.code,
                )
            except tombstones.ReportRestoreTombstoneError:
                pass
            raise tombstones.ReportRestorePostCommitError(
                error.code,
                error.message,
            ) from exc
        try:
            self._record_postcheck(
                plan_sha256,
                outcome="PASSED",
                failure_code=None,
            )
        except tombstones.ReportRestoreTombstoneError as exc:
            self._plan = None
            raise tombstones.ReportRestorePostCommitError(
                exc.code,
                exc.message,
            ) from exc
        self._plan = None
        self.postcheck_error = None
        self.mutation_state = "COMPLETE"

    def rollback(self) -> None:
        plan = self._plan
        transaction = self._transaction
        if transaction is not None and transaction.is_active:
            transaction.rollback()
        elif self.connection.in_transaction():
            self.connection.rollback()
        self._transaction = None
        committed = False
        postcheck_outcome: str | None = None
        if plan is not None:
            try:
                committed_row = self.connection.execute(
                    text(
                        "SELECT postcheck.outcome "
                        "FROM public.report_restore_reapply_receipts AS receipt "
                        "LEFT JOIN public.report_restore_reapply_postchecks AS postcheck "
                        "ON postcheck.plan_sha256 = receipt.plan_sha256 "
                        "WHERE receipt.plan_sha256 = :plan_sha256"
                    ),
                    {"plan_sha256": plan.plan_sha256},
                ).one_or_none()
                committed = committed_row is not None
                if committed_row is not None:
                    postcheck_outcome = committed_row[0]
                self.connection.rollback()
            except Exception as exc:
                self.connection.rollback()
                raise _fail(
                    "restore_reapply_commit_ambiguous",
                    "The database commit cannot be distinguished from rollback.",
                ) from exc
        try:
            for quarantine in reversed(self._staged):
                quarantine.finalize() if committed else quarantine.restore()
            if self.postcheck_error is not None or postcheck_outcome == "FAILED":
                self.mutation_state = "DB_COMMITTED_POSTCHECK_FAILED"
            elif postcheck_outcome == "PASSED":
                self.mutation_state = "COMPLETE"
            elif committed:
                self.mutation_state = "DB_COMMITTED_POSTCHECK_REQUIRED"
            else:
                self.mutation_state = "NOT_STARTED"
            self.reconciliation_error = None
        finally:
            self._staged = []
            self._plan = None

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            if self._transaction is not None or self._staged:
                self.rollback()
            if not self.connection.closed:
                try:
                    self.connection.execute(
                        text(
                            "SELECT "
                            "pg_advisory_unlock(hashtextextended(:target_lock_key, 0)), "
                            "pg_advisory_unlock(hashtextextended(:storage_lock_key, 0))"
                        ),
                        {
                            "target_lock_key": TARGET_ADVISORY_LOCK_KEY,
                            "storage_lock_key": REPORT_STORAGE_TRANSACTION_LOCK_KEY,
                        },
                    )
                    self.connection.commit()
                    self.connection.exec_driver_sql("RESET ROLE")
                    self.connection.commit()
                except Exception:
                    self.connection.invalidate()
        finally:
            try:
                self.connection.close()
            finally:
                try:
                    self.engine.dispose()
                finally:
                    try:
                        os.close(self.target_root_descriptor)
                    finally:
                        os.close(self.source_root_descriptor)


@contextmanager
def isolated_postgres_report_restore_target(
    *,
    target_database_url: str,
    source_runtime_identity: PostgreSQLRuntimeIdentity,
    source_upload_root: Path,
    target_upload_root: Path,
    inventory_context: RestoreInventoryContext,
    mutation_guard: Callable[[], None],
    target_admission_notifier: Callable[[PostgreSQLRuntimeIdentity, int], None],
    target_admission_timeout_seconds: int = 30,
) -> Iterator[PostgresReportRestoreTarget]:
    if not 1 <= target_admission_timeout_seconds <= 300:
        raise ValueError("target admission timeout must be between 1 and 300 seconds")
    normalized = explicit_restore_database_url(target_database_url)
    database_name = postgresql_database_name(normalized)
    if _DRILL_DATABASE_SEGMENT.search(database_name) is None:
        raise _fail(
            "restore_target_not_isolated",
            "The restore target database name must contain a test or drill segment.",
        )
    target_identity = database_identity_sha256(normalized)
    if target_identity == inventory_context.source_identity_sha256:
        raise _fail(
            "restore_target_identity_mismatch",
            "The restore target URL identity equals the backup source identity.",
        )
    (
        source_root,
        upload_root,
        expected_source_root_identity,
        expected_target_root_identity,
    ) = _validated_artifact_roots(
        source_upload_root,
        target_upload_root,
    )
    source_root_descriptor, source_root_identity = _open_pinned_artifact_root(
        source_root,
        expected_identity=expected_source_root_identity,
    )
    try:
        target_root_descriptor, target_root_identity = _open_pinned_artifact_root(
            upload_root,
            expected_identity=expected_target_root_identity,
        )
    except BaseException:
        os.close(source_root_descriptor)
        raise
    if source_root_identity[:2] == target_root_identity[:2]:
        os.close(target_root_descriptor)
        os.close(source_root_descriptor)
        raise _fail(
            "restore_artifact_target_unsafe",
            "The source and target artifact roots share one pinned identity.",
        )
    engine: Engine | None = None
    connection: Connection | None = None
    target: PostgresReportRestoreTarget | None = None
    try:
        engine = create_engine(
            sqlalchemy_psycopg_url(normalized),
            pool_pre_ping=True,
            hide_parameters=True,
            connect_args={
                "connect_timeout": 10,
                "options": "-c statement_timeout=10000",
            },
        )
        connection = engine.connect()
        from backend.app.services.report_restore_handoff import (
            postgresql_runtime_identity,
        )

        runtime_identity = postgresql_runtime_identity(connection)
        if (
            runtime_identity.system_identifier
            == source_runtime_identity.system_identifier
        ):
            raise _fail(
                "restore_target_cluster_not_isolated",
                "The restore target must use a PostgreSQL cluster distinct from the source.",
            )
        if runtime_identity.identity_sha256 == source_runtime_identity.identity_sha256:
            raise _fail(
                "restore_target_identity_mismatch",
                "The source and target connections resolve to the same PostgreSQL database.",
            )
        connection.exec_driver_sql(f"SET ROLE {RESTORE_WORKER_ROLE}")
        connection.commit()
        lock_row = connection.execute(
            text(
                "SELECT hashtextextended(:target_lock_key, 0)::bigint, "
                "hashtextextended(:storage_lock_key, 0)::bigint, "
                "pg_try_advisory_lock(hashtextextended(:target_lock_key, 0)), "
                "pg_try_advisory_lock(hashtextextended(:storage_lock_key, 0)), "
                "pg_backend_pid()"
            ),
            {
                "target_lock_key": TARGET_ADVISORY_LOCK_KEY,
                "storage_lock_key": REPORT_STORAGE_TRANSACTION_LOCK_KEY,
            },
        ).one()
        connection.rollback()
        if lock_row[2] is not True or lock_row[3] is not True:
            raise _fail(
                "restore_target_busy",
                "Another process already holds the restored-target storage fence.",
            )
        target = PostgresReportRestoreTarget(
            engine=engine,
            connection=connection,
            target_database_url=normalized,
            target_identity_sha256=target_identity,
            runtime_identity=runtime_identity,
            source_upload_root=source_root,
            upload_root=upload_root,
            source_root_descriptor=source_root_descriptor,
            source_root_identity=source_root_identity,
            target_root_descriptor=target_root_descriptor,
            target_root_identity=target_root_identity,
            backend_pid=int(lock_row[4]),
            target_advisory_key=int(lock_row[0]),
            storage_advisory_key=int(lock_row[1]),
            mutation_guard=mutation_guard,
            target_admission_notifier=target_admission_notifier,
            target_admission_timeout_seconds=target_admission_timeout_seconds,
            inventory_context=inventory_context,
        )
        target._assert_role()
        target._assert_isolated()
        yield target
    finally:
        if target is not None:
            target.close()
        else:
            try:
                if connection is not None:
                    connection.close()
            finally:
                try:
                    if engine is not None:
                        engine.dispose()
                finally:
                    try:
                        os.close(target_root_descriptor)
                    finally:
                        os.close(source_root_descriptor)


__all__ = [
    "PostgresReportRestoreTarget",
    "RESTORE_AUTHORIZER_ROLE",
    "RESTORE_WORKER_ROLE",
    "RestoreInventoryContext",
    "TARGET_ADVISORY_LOCK_KEY",
    "isolated_postgres_report_restore_target",
]
