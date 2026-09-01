#!/usr/bin/env python3
"""Preview or apply acknowledged report deletions as a manual one-shot."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import stat
import sys
from typing import Callable
import uuid

from sqlalchemy import create_engine, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.config import _validate_deployment_database_transport  # noqa: E402
from backend.app.services.report_deletion import (  # noqa: E402
    ReportDeletionError,
    apply_report_deletion,
    list_report_deletion_candidates,
)
from backend.app.models import ReportDeletionTombstone  # noqa: E402
from backend.app.services.report_storage import (  # noqa: E402
    REPORT_DELETION_QUARANTINE_DIRECTORY_NAME,
    REPORT_STORAGE_TRANSACTION_LOCK_KEY,
)


CONFIRMATION = "DELETE-ACKNOWLEDGED-REPORTS"
MAX_BATCH_SIZE = 100
_STORAGE_NAME = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}\.(?:wse|jpg|png|webp)$"
)
_JOURNAL_NAME = re.compile(
    r"^(?P<request_id>[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12})\.json$"
)
QUARANTINE_DIRECTORY = REPORT_DELETION_QUARANTINE_DIRECTORY_NAME
JOURNAL_SCHEMA = "walksafe.report-deletion-quarantine.v1"


class ReportDeletionWorkerError(RuntimeError):
    pass


def _validated_database_url(raw: str, *, deployment: bool) -> str:
    value = raw.strip()
    if not value:
        raise ReportDeletionWorkerError(
            "WALKSAFE_REPORT_DELETION_DATABASE_URL is required"
        )
    try:
        url = make_url(value)
    except Exception as exc:
        raise ReportDeletionWorkerError("worker database URL is invalid") from exc
    if url.drivername != "postgresql+psycopg" or not url.database or not url.username:
        raise ReportDeletionWorkerError(
            "worker database URL must use postgresql+psycopg"
        )
    if deployment:
        try:
            _validate_deployment_database_transport(value)
        except ValueError as exc:
            raise ReportDeletionWorkerError(
                "worker database transport is unsafe"
            ) from exc
    elif (url.host or "") not in {"127.0.0.1", "localhost", "::1"}:
        raise ReportDeletionWorkerError(
            "local isolated worker database must use loopback"
        )
    return value


def _validated_upload_root(raw: str) -> Path:
    configured = Path(raw).expanduser()
    if not configured.is_absolute() or configured.is_symlink():
        raise ReportDeletionWorkerError("UPLOAD_DIR must be an absolute real directory")
    try:
        metadata = configured.stat(follow_symlinks=False)
    except OSError as exc:
        raise ReportDeletionWorkerError("UPLOAD_DIR is unavailable") from exc
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or metadata.st_uid != os.geteuid()
        or stat.S_IMODE(metadata.st_mode) & 0o007
    ):
        raise ReportDeletionWorkerError(
            "UPLOAD_DIR must be service-owned and inaccessible to other users"
        )
    return configured.resolve(strict=True)


def _assert_manual_role(db: Session) -> None:
    safe = bool(
        db.execute(
            text(
                "SELECT current_user = session_user "
                "AND NOT pg_has_role(current_user, 'walksafe_backend_runtime', 'USAGE') "
                "AND NOT pg_has_role(current_user, 'walksafe_account_deletion_worker', 'USAGE') "
                "AND pg_has_role(current_user, 'walksafe_report_deletion_worker', 'USAGE') "
                "AND has_function_privilege(current_user, "
                "'public.walksafe_lock_report_deletion_candidate(uuid,uuid,bigint,text,bigint)', "
                "'EXECUTE') "
                "AND has_table_privilege(current_user, 'public.reports', 'DELETE') "
                "AND has_column_privilege(current_user, 'public.reports', 'id', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.reports', 'privacy_subject_hmac', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.reports', 'account_generation', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.reports', 'image_path', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_image_objects', 'report_id', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_image_objects', 'storage_name', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_user_requests', 'id', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_user_requests', 'report_id', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_user_requests', 'request_type', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_user_requests', 'status', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_user_requests', 'status_version', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_user_requests', 'updated_at', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_deletion_legal_holds', 'report_id', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_deletion_legal_holds', 'expires_at', 'SELECT') "
                "AND has_table_privilege(current_user, 'public.report_deletion_tombstones', 'SELECT,INSERT') "
                "AND has_table_privilege(current_user, 'public.report_deletion_external_copy_states', 'INSERT') "
                "AND has_column_privilege(current_user, 'public.report_deletion_external_copy_states', 'deletion_tombstone_id', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_deletion_external_copy_states', 'source_delivery_event_id', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_institution_delivery_events', 'id', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_institution_delivery_events', 'report_id', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_institution_delivery_events', 'package_id', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_institution_delivery_events', 'revision', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_institution_delivery_events', 'institution', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_institution_delivery_events', 'status', 'SELECT') "
                "AND has_column_privilege(current_user, 'public.report_institution_delivery_events', 'observed_at', 'SELECT') "
                "AND NOT has_column_privilege(current_user, 'public.report_institution_delivery_events', 'recipient', 'SELECT') "
                "AND NOT has_table_privilege(current_user, 'public.reports', 'UPDATE') "
                "AND NOT has_table_privilege(current_user, 'public.report_user_requests', 'UPDATE') "
                "AND NOT has_table_privilege(current_user, 'public.report_deletion_tombstones', 'UPDATE,DELETE,TRUNCATE') "
                "AND NOT has_table_privilege(current_user, 'public.report_deletion_external_copy_states', 'UPDATE,DELETE,TRUNCATE') "
                "AND NOT has_table_privilege(current_user, 'public.report_institution_delivery_events', 'INSERT,UPDATE,DELETE,TRUNCATE')"
            )
        ).scalar_one()
    )
    if not safe:
        raise ReportDeletionWorkerError(
            "report deletion worker role violates least privilege"
        )


def _candidate_digest(candidates: list[object]) -> str:
    inventory = [
        {
            "account_generation": item.account_generation,
            "privacy_subject_hmac": item.privacy_subject_hmac,
            "report_id": str(item.report_id),
            "request_id": str(item.request_id),
            "request_status_version": item.request_status_version,
        }
        for item in candidates
    ]
    return hashlib.sha256(
        b"walksafe/report-deletion-candidates/v1\0"
        + json.dumps(
            inventory,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("ascii")
    ).hexdigest()


@dataclass(frozen=True, slots=True)
class QuarantineEntry:
    original_name: str
    quarantine_name: str


@dataclass(frozen=True, slots=True)
class QuarantineJournal:
    request_id: uuid.UUID
    report_id: uuid.UUID
    entries: tuple[QuarantineEntry, ...]


def _strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ReportDeletionWorkerError("quarantine journal has duplicate keys")
        result[key] = value
    return result


def _canonical_journal(journal: QuarantineJournal) -> bytes:
    return json.dumps(
        {
            "entries": [
                {
                    "original_name": entry.original_name,
                    "quarantine_name": entry.quarantine_name,
                }
                for entry in journal.entries
            ],
            "report_id": str(journal.report_id),
            "request_id": str(journal.request_id),
            "schema_version": JOURNAL_SCHEMA,
        },
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("ascii")


class ReportDeletionQuarantine:
    def __init__(
        self,
        upload_root: Path,
        *,
        request_id: uuid.UUID,
        report_id: uuid.UUID,
        expected_upload_root_identity: tuple[int, int] | None = None,
    ) -> None:
        self.upload_root = upload_root
        self.request_id = request_id
        self.report_id = report_id
        self.expected_upload_root_identity = expected_upload_root_identity
        self.quarantine_root = upload_root / QUARANTINE_DIRECTORY

    def _open_root(self) -> int:
        flags = os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_CLOEXEC", 0)
        flags |= getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(self.upload_root, flags)
        try:
            metadata = os.fstat(descriptor)
            path_metadata = self.upload_root.stat(follow_symlinks=False)
            identity = (metadata.st_dev, metadata.st_ino)
            if (
                not stat.S_ISDIR(metadata.st_mode)
                or self.upload_root.is_symlink()
                or identity != (path_metadata.st_dev, path_metadata.st_ino)
                or (
                    self.expected_upload_root_identity is not None
                    and identity != self.expected_upload_root_identity
                )
            ):
                raise ReportDeletionWorkerError("upload root identity changed")
            return descriptor
        except BaseException:
            os.close(descriptor)
            raise

    def _prepare_directory_at(self, root_descriptor: int) -> None:
        try:
            os.mkdir(QUARANTINE_DIRECTORY, mode=0o700, dir_fd=root_descriptor)
        except FileExistsError:
            pass
        metadata = os.stat(
            QUARANTINE_DIRECTORY,
            dir_fd=root_descriptor,
            follow_symlinks=False,
        )
        root_metadata = os.fstat(root_descriptor)
        if (
            not stat.S_ISDIR(metadata.st_mode)
            or metadata.st_uid != os.geteuid()
            or stat.S_IMODE(metadata.st_mode) != 0o700
            or metadata.st_dev != root_metadata.st_dev
        ):
            raise ReportDeletionWorkerError("quarantine directory is unsafe")

    def _prepare_directory(self) -> None:
        root_descriptor = self._open_root()
        try:
            self._prepare_directory_at(root_descriptor)
        finally:
            os.close(root_descriptor)

    @property
    def journal_name(self) -> str:
        return f"{self.request_id}.json"

    def _open_directories(self) -> tuple[int, int]:
        flags = os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_CLOEXEC", 0)
        flags |= getattr(os, "O_NOFOLLOW", 0)
        root_descriptor = self._open_root()
        try:
            self._prepare_directory_at(root_descriptor)
            quarantine_descriptor = os.open(
                QUARANTINE_DIRECTORY,
                flags,
                dir_fd=root_descriptor,
            )
            return root_descriptor, quarantine_descriptor
        except BaseException:
            os.close(root_descriptor)
            raise

    @staticmethod
    def _safe_regular(metadata: os.stat_result) -> bool:
        return (
            stat.S_ISREG(metadata.st_mode)
            and metadata.st_uid == os.geteuid()
            and metadata.st_nlink == 1
        )

    @staticmethod
    def _file_identity(metadata: os.stat_result) -> tuple[int, ...]:
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

    def _read_journal_bytes(
        self,
        quarantine_descriptor: int,
        journal_name: str,
    ) -> bytes:
        try:
            metadata = os.stat(
                journal_name,
                dir_fd=quarantine_descriptor,
                follow_symlinks=False,
            )
            if (
                not self._safe_regular(metadata)
                or stat.S_IMODE(metadata.st_mode) != 0o600
                or not 0 < metadata.st_size <= 65_536
            ):
                raise OSError("quarantine journal metadata is unsafe")
            descriptor = os.open(
                journal_name,
                os.O_RDONLY
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0)
                | getattr(os, "O_NONBLOCK", 0),
                dir_fd=quarantine_descriptor,
            )
            try:
                opened = os.fstat(descriptor)
                if self._file_identity(opened) != self._file_identity(metadata):
                    raise OSError("quarantine journal changed while opening")
                chunks: list[bytes] = []
                remaining = opened.st_size
                while remaining:
                    chunk = os.read(descriptor, remaining)
                    if not chunk:
                        raise OSError("quarantine journal was truncated")
                    chunks.append(chunk)
                    remaining -= len(chunk)
                if os.read(descriptor, 1):
                    raise OSError("quarantine journal grew while reading")
                after = os.fstat(descriptor)
            finally:
                os.close(descriptor)
            current = os.stat(
                journal_name,
                dir_fd=quarantine_descriptor,
                follow_symlinks=False,
            )
        except OSError as exc:
            raise ReportDeletionWorkerError("quarantine journal is unsafe") from exc
        if (
            self._file_identity(after) != self._file_identity(opened)
            or self._file_identity(current) != self._file_identity(opened)
        ):
            raise ReportDeletionWorkerError("quarantine journal changed while reading")
        return b"".join(chunks)

    def _load(self, quarantine_descriptor: int) -> QuarantineJournal:
        raw = self._read_journal_bytes(
            quarantine_descriptor,
            self.journal_name,
        )
        try:
            payload = json.loads(raw.decode("ascii"), object_pairs_hook=_strict_object)
            if not isinstance(payload, dict) or set(payload) != {
                "entries", "report_id", "request_id", "schema_version"
            }:
                raise ValueError
            request_id = uuid.UUID(str(payload["request_id"]))
            report_id = uuid.UUID(str(payload["report_id"]))
            raw_entries = payload["entries"]
            if (
                str(request_id) != payload["request_id"]
                or str(report_id) != payload["report_id"]
                or payload["schema_version"] != JOURNAL_SCHEMA
                or not isinstance(raw_entries, list)
                or len(raw_entries) > 2
            ):
                raise ValueError
            entries: list[QuarantineEntry] = []
            for item in raw_entries:
                if not isinstance(item, dict) or set(item) != {
                    "original_name", "quarantine_name"
                }:
                    raise ValueError
                original = item["original_name"]
                quarantine = item["quarantine_name"]
                if (
                    not isinstance(original, str)
                    or _STORAGE_NAME.fullmatch(original) is None
                    or not isinstance(quarantine, str)
                    or quarantine != f"{request_id}.{original}"
                ):
                    raise ValueError
                entries.append(QuarantineEntry(original, quarantine))
            if len({entry.original_name for entry in entries}) != len(entries):
                raise ValueError
            journal = QuarantineJournal(request_id, report_id, tuple(entries))
        except (UnicodeError, ValueError, json.JSONDecodeError) as exc:
            raise ReportDeletionWorkerError("quarantine journal is invalid") from exc
        if (
            journal.request_id != self.request_id
            or journal.report_id != self.report_id
            or raw != _canonical_journal(journal)
        ):
            raise ReportDeletionWorkerError("quarantine journal binding is invalid")
        return journal

    def stage(self, storage_names: tuple[str, ...]) -> None:
        root_descriptor, quarantine_descriptor = self._open_directories()
        try:
            if len(storage_names) > 2 or len(set(storage_names)) != len(storage_names):
                raise ReportDeletionWorkerError("report storage inventory is invalid")
            entries: list[QuarantineEntry] = []
            for storage_name in storage_names:
                if _STORAGE_NAME.fullmatch(storage_name) is None:
                    raise ReportDeletionWorkerError("report storage name is invalid")
                try:
                    metadata = os.stat(
                        storage_name, dir_fd=root_descriptor, follow_symlinks=False
                    )
                except FileNotFoundError:
                    continue
                if not self._safe_regular(metadata):
                    raise ReportDeletionWorkerError("report storage object is unsafe")
                entries.append(
                    QuarantineEntry(storage_name, f"{self.request_id}.{storage_name}")
                )
                try:
                    os.stat(
                        entries[-1].quarantine_name,
                        dir_fd=quarantine_descriptor,
                        follow_symlinks=False,
                    )
                except FileNotFoundError:
                    pass
                else:
                    raise ReportDeletionWorkerError(
                        "quarantine destination already exists"
                    )
            journal = QuarantineJournal(
                self.request_id, self.report_id, tuple(entries)
            )
            descriptor = os.open(
                self.journal_name,
                os.O_WRONLY
                | os.O_CREAT
                | os.O_EXCL
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                0o600,
                dir_fd=quarantine_descriptor,
            )
            try:
                payload = _canonical_journal(journal)
                written = 0
                while written < len(payload):
                    count = os.write(descriptor, payload[written:])
                    if count <= 0:
                        raise ReportDeletionWorkerError(
                            "quarantine journal write was incomplete"
                        )
                    written += count
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
            os.fsync(quarantine_descriptor)
            for entry in journal.entries:
                metadata = os.stat(
                    entry.original_name,
                    dir_fd=root_descriptor,
                    follow_symlinks=False,
                )
                if not self._safe_regular(metadata):
                    raise ReportDeletionWorkerError("report storage object changed")
                os.rename(
                    entry.original_name,
                    entry.quarantine_name,
                    src_dir_fd=root_descriptor,
                    dst_dir_fd=quarantine_descriptor,
                )
                os.fsync(root_descriptor)
                os.fsync(quarantine_descriptor)
        finally:
            os.close(quarantine_descriptor)
            os.close(root_descriptor)

    def restore(self) -> None:
        self._finish(committed=False)

    def finalize(self) -> None:
        self._finish(committed=True)

    def _finish(self, *, committed: bool) -> None:
        root_descriptor, quarantine_descriptor = self._open_directories()
        try:
            journal = self._load(quarantine_descriptor)
            for entry in journal.entries:
                original = self._object_state(root_descriptor, entry.original_name)
                quarantined = self._object_state(
                    quarantine_descriptor, entry.quarantine_name
                )
                if original and quarantined:
                    raise ReportDeletionWorkerError("quarantine object is duplicated")
                if committed:
                    if original:
                        raise ReportDeletionWorkerError(
                            "committed report object remained outside quarantine"
                        )
                    if quarantined:
                        os.unlink(entry.quarantine_name, dir_fd=quarantine_descriptor)
                        os.fsync(quarantine_descriptor)
                else:
                    if not original and not quarantined:
                        raise ReportDeletionWorkerError("quarantine object is missing")
                    if quarantined:
                        os.rename(
                            entry.quarantine_name,
                            entry.original_name,
                            src_dir_fd=quarantine_descriptor,
                            dst_dir_fd=root_descriptor,
                        )
                        os.fsync(quarantine_descriptor)
                        os.fsync(root_descriptor)
            os.unlink(self.journal_name, dir_fd=quarantine_descriptor)
            os.fsync(quarantine_descriptor)
        finally:
            os.close(quarantine_descriptor)
            os.close(root_descriptor)

    def _object_state(self, descriptor: int, name: str) -> bool:
        try:
            metadata = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
        except FileNotFoundError:
            return False
        if not self._safe_regular(metadata):
            raise ReportDeletionWorkerError("quarantine object is unsafe")
        return True

    @classmethod
    def assert_no_pending(
        cls,
        upload_root: Path,
        *,
        expected_upload_root_identity: tuple[int, int] | None = None,
    ) -> None:
        probe = cls(
            upload_root,
            request_id=uuid.uuid4(),
            report_id=uuid.uuid4(),
            expected_upload_root_identity=expected_upload_root_identity,
        )
        root_descriptor = probe._open_root()
        try:
            try:
                os.stat(
                    QUARANTINE_DIRECTORY,
                    dir_fd=root_descriptor,
                    follow_symlinks=False,
                )
            except FileNotFoundError:
                return
        finally:
            os.close(root_descriptor)
        root_descriptor, quarantine_descriptor = probe._open_directories()
        try:
            if os.listdir(quarantine_descriptor):
                raise ReportDeletionWorkerError(
                    "pending quarantine requires explicit --reconcile"
                )
        finally:
            os.close(quarantine_descriptor)
            os.close(root_descriptor)

    @classmethod
    def reconcile_all(
        cls,
        upload_root: Path,
        *,
        is_committed: Callable[[uuid.UUID, uuid.UUID], bool],
        expected_upload_root_identity: tuple[int, int] | None = None,
    ) -> tuple[int, int]:
        probe = cls(
            upload_root,
            request_id=uuid.uuid4(),
            report_id=uuid.uuid4(),
            expected_upload_root_identity=expected_upload_root_identity,
        )
        root_descriptor, quarantine_descriptor = probe._open_directories()
        try:
            names = sorted(os.listdir(quarantine_descriptor))
        finally:
            os.close(quarantine_descriptor)
            os.close(root_descriptor)
        journal_names = [name for name in names if _JOURNAL_NAME.fullmatch(name)]
        if not journal_names and names:
            raise ReportDeletionWorkerError("quarantine contains unknown entries")
        referenced: set[str] = set(journal_names)
        journals: list[QuarantineJournal] = []
        for journal_name in journal_names:
            request_id = uuid.UUID(_JOURNAL_NAME.fullmatch(journal_name).group("request_id"))
            bound = cls(
                upload_root,
                request_id=request_id,
                report_id=uuid.UUID(int=0),
                expected_upload_root_identity=expected_upload_root_identity,
            )
            root_descriptor, quarantine_descriptor = bound._open_directories()
            try:
                # Parse once without trusting the caller-provided report binding.
                raw = bound._read_journal_bytes(
                    quarantine_descriptor,
                    journal_name,
                )
            finally:
                os.close(quarantine_descriptor)
                os.close(root_descriptor)
            try:
                payload = json.loads(raw.decode("ascii"), object_pairs_hook=_strict_object)
                report_id = uuid.UUID(str(payload["report_id"]))
            except (
                KeyError,
                TypeError,
                UnicodeError,
                ValueError,
                json.JSONDecodeError,
            ) as exc:
                raise ReportDeletionWorkerError("quarantine journal is invalid") from exc
            bound = cls(
                upload_root,
                request_id=request_id,
                report_id=report_id,
                expected_upload_root_identity=expected_upload_root_identity,
            )
            root_descriptor, quarantine_descriptor = bound._open_directories()
            try:
                journal = bound._load(quarantine_descriptor)
            finally:
                os.close(quarantine_descriptor)
                os.close(root_descriptor)
            journals.append(journal)
            referenced.update(entry.quarantine_name for entry in journal.entries)
        if not set(names).issubset(referenced):
            raise ReportDeletionWorkerError("quarantine contains unknown entries")
        finalized = restored = 0
        for journal in journals:
            bound = cls(
                upload_root,
                request_id=journal.request_id,
                report_id=journal.report_id,
                expected_upload_root_identity=expected_upload_root_identity,
            )
            if is_committed(journal.request_id, journal.report_id):
                bound.finalize()
                finalized += 1
            else:
                bound.restore()
                restored += 1
        return finalized, restored


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--local-isolated", action="store_true")
    mode.add_argument("--manual-one-shot", action="store_true")
    parser.add_argument("--limit", type=int, default=25)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--apply", action="store_true")
    action.add_argument("--reconcile", action="store_true")
    parser.add_argument("--candidate-digest")
    parser.add_argument("--confirm")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.manual_one_shot and os.geteuid() == 0:
            raise ReportDeletionWorkerError(
                "manual report deletion must not run as root"
            )
        if not 1 <= args.limit <= MAX_BATCH_SIZE:
            raise ReportDeletionWorkerError("limit must be between 1 and 100")
        if args.apply and (
            args.confirm != CONFIRMATION
            or not isinstance(args.candidate_digest, str)
            or re.fullmatch(r"[0-9a-f]{64}", args.candidate_digest) is None
        ):
            raise ReportDeletionWorkerError(
                "apply requires exact confirmation and preview candidate digest"
            )
        if args.reconcile and (args.candidate_digest is not None or args.confirm is not None):
            raise ReportDeletionWorkerError(
                "reconcile does not accept candidate confirmation"
            )
        database_url = _validated_database_url(
            os.environ.get("WALKSAFE_REPORT_DELETION_DATABASE_URL", ""),
            deployment=args.manual_one_shot,
        )
        upload_root = _validated_upload_root(os.environ.get("UPLOAD_DIR", ""))
        engine = create_engine(database_url, pool_pre_ping=True, hide_parameters=True)
        deleted_count = 0
        legal_hold_count = 0
        reconciled_finalized_count = 0
        reconciled_restored_count = 0
        try:
            with Session(engine) as db:
                if args.manual_one_shot:
                    _assert_manual_role(db)
                locked = db.execute(
                    text(
                        "SELECT pg_try_advisory_lock("
                        "hashtextextended('walksafe-report-deletion-worker-v1', 0)), "
                        "pg_try_advisory_lock(hashtextextended(:storage_lock_key, 0))"
                    ),
                    {"storage_lock_key": REPORT_STORAGE_TRANSACTION_LOCK_KEY},
                ).one()
                db.rollback()
                if tuple(locked) != (True, True):
                    if locked[0] is True:
                        db.scalar(
                            text(
                                "SELECT pg_advisory_unlock("
                                "hashtextextended('walksafe-report-deletion-worker-v1', 0))"
                            )
                        )
                    if locked[1] is True:
                        db.scalar(
                            text(
                                "SELECT pg_advisory_unlock("
                                "hashtextextended(:storage_lock_key, 0))"
                            ),
                            {"storage_lock_key": REPORT_STORAGE_TRANSACTION_LOCK_KEY},
                        )
                    db.rollback()
                    raise ReportDeletionWorkerError(
                        "another report deletion or storage operation is active"
                    )
                try:
                    if args.reconcile:
                        (
                            reconciled_finalized_count,
                            reconciled_restored_count,
                        ) = ReportDeletionQuarantine.reconcile_all(
                            upload_root,
                            is_committed=lambda request_id, report_id: db.scalar(
                                select(ReportDeletionTombstone.id).where(
                                    ReportDeletionTombstone.request_id == request_id,
                                    ReportDeletionTombstone.report_id == report_id,
                                )
                            )
                            is not None,
                        )
                        candidates = []
                        digest = _candidate_digest(candidates)
                        db.rollback()
                    else:
                        ReportDeletionQuarantine.assert_no_pending(upload_root)
                        candidates = list_report_deletion_candidates(db, limit=args.limit)
                        digest = _candidate_digest(candidates)
                        db.rollback()
                        if args.apply:
                            if not hmac.compare_digest(args.candidate_digest, digest):
                                raise ReportDeletionWorkerError(
                                    "candidate inventory changed after preview"
                                )
                            for candidate in candidates:
                                state = apply_report_deletion(
                                    db,
                                    candidate=candidate,
                                    storage_effect=ReportDeletionQuarantine(
                                        upload_root,
                                        request_id=candidate.request_id,
                                        report_id=candidate.report_id,
                                    ),
                                )
                                if state.state == "DELETED":
                                    deleted_count += 1
                                elif state.state == "LEGAL_HOLD":
                                    legal_hold_count += 1
                finally:
                    db.rollback()
                    db.execute(
                        text(
                            "SELECT pg_advisory_unlock("
                            "hashtextextended(:storage_lock_key, 0)), "
                            "pg_advisory_unlock("
                            "hashtextextended('walksafe-report-deletion-worker-v1', 0))"
                        ),
                        {"storage_lock_key": REPORT_STORAGE_TRANSACTION_LOCK_KEY},
                    )
                    db.rollback()
        finally:
            engine.dispose()
        print(
            json.dumps(
                {
                    "applied": bool(args.apply),
                    "candidate_count": len(candidates),
                    "candidate_digest": digest,
                    "deleted_count": deleted_count,
                    "legal_hold_count": legal_hold_count,
                    "reconciled_finalized_count": reconciled_finalized_count,
                    "reconciled_restored_count": reconciled_restored_count,
                    "schema_version": "walksafe.report-deletion-result.v2",
                },
                sort_keys=True,
                separators=(",", ":"),
            )
        )
        return 0
    except (
        OSError,
        ReportDeletionError,
        ReportDeletionWorkerError,
        SQLAlchemyError,
        ValueError,
    ):
        print(
            json.dumps(
                {"applied": False, "status": "failed"},
                sort_keys=True,
                separators=(",", ":"),
            ),
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
