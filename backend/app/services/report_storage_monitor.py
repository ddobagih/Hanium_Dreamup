"""One lifespan-owned, bounded report inventory walk; readiness only reads snapshots."""
from __future__ import annotations

import asyncio
from contextlib import AbstractContextManager
from copy import deepcopy
from dataclasses import dataclass
import os
from pathlib import Path
from threading import Event, Lock
import time
from typing import Callable
import uuid

from sqlalchemy.orm import Session

from backend.app.services.report_storage import (
    JOURNAL_DIRECTORY_NAME,
    RETENTION_QUARANTINE_DIRECTORY_NAME,
    REPORT_DELETION_QUARANTINE_DIRECTORY_NAME,
    PendingReportWrite,
    ReportStorageInventoryEntry,
    ReportStorageInventorySetMismatch,
    ReportStorageJournalUnresolved,
    ReportStorageRecoveryDeferred,
    VerifiedReportStorageObject,
    open_report_storage_root,
    read_report_storage_inventory_page,
    report_storage_inventory_pending,
    report_storage_object_fingerprint,
    validate_report_image_write,
    validate_report_storage_namespace_entry,
    validate_report_storage_root,
)
from backend.app.services.report_storage_recovery import recover_pending_report_writes
from backend.app.services.storage_readiness_state import StorageReadinessState


class _CoverageChanged(Exception):
    """A concurrent mutation invalidated this walk; never publish its success."""


@dataclass
class _RowBatch:
    entries: list[ReportStorageInventoryEntry]
    candidates: list[tuple[PendingReportWrite, VerifiedReportStorageObject]]
    last_page: bool
    hashed: int = 0
    checked: int = 0


class ReportStorageMonitor:
    BATCH_SIZE = 16
    BATCH_SECONDS = 1.0

    def __init__(
        self, *, upload_root: Path, state: StorageReadinessState,
        session: Callable[[], AbstractContextManager[Session]],
        known_key_states: Callable[[], dict[str, str]],
        upload_check: Callable[[], dict[str, object]],
        encryption_check: Callable[[], dict[str, object]],
        record_incident: Callable[[Session, ReportStorageInventorySetMismatch], None],
        refresh_seconds: float, max_age_seconds: float,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.state = state
        self._root = upload_root
        self._session = session
        self._keys = known_key_states
        self._upload_check = upload_check
        self._encryption_check = encryption_check
        self._record_incident = record_incident
        self._refresh = refresh_seconds
        self._max_age = max_age_seconds
        self._clock = clock
        self._snapshot_lock = Lock()
        self._auxiliary: dict[str, dict[str, object]] = {}
        self._auxiliary_started: float | None = None
        self._stopped = Event()
        self._verified: dict[uuid.UUID, VerifiedReportStorageObject] = {}
        self._descriptor: int | None = None
        self._directory = None
        self._iterator = None
        self._phase = "start"

    def restart(self) -> None:
        self.close_cycle()
        self._stopped.clear()
        self._verified.clear()
        with self._snapshot_lock:
            self._auxiliary.clear()
            self._auxiliary_started = None
        self.state.restart_report_inventory()

    def seed(
        self, verified: dict[uuid.UUID, VerifiedReportStorageObject], *,
        coverage_started: float, upload: dict[str, object], encryption: dict[str, object],
        revision: int | None = None,
    ) -> None:
        """Only the successful locked startup full scan may seed this cache."""
        self._verified = dict(verified)
        self._publish_auxiliary(coverage_started, upload, encryption)
        self.state.publish_report_inventory_success(
            self.state.report_inventory_revision() if revision is None else revision, coverage_started,
        )

    def stop(self) -> None:
        # The owner awaits its worker before closing pinned descriptors.
        self._stopped.set()
        self.state.stop_report_inventory()

    def close_cycle(self) -> None:
        if self._iterator is not None:
            self._iterator.close()
            self._iterator = None
        if self._descriptor is not None:
            os.close(self._descriptor)
            self._descriptor = None
        self._phase = "start"

    def _publish_auxiliary(self, started, upload, encryption) -> None:
        with self._snapshot_lock:
            self._auxiliary = {"upload_root": deepcopy(upload), "report_image_encryption": deepcopy(encryption)}
            self._auxiliary_started = started

    def checks(self) -> dict[str, dict[str, object]]:
        """No DB, file, key-provider, or hashing work on the HTTP path."""
        now = self._clock()
        with self._snapshot_lock:
            started = self._auxiliary_started
            if self._stopped.is_set() or started is None or not 0 <= now - started < self._max_age:
                auxiliary = {
                    name: {"ready": False, "reason": "report_storage_monitor_unavailable"}
                    for name in ("upload_root", "report_image_encryption")
                }
            else:
                auxiliary = deepcopy(self._auxiliary)
        return {**auxiliary, "report_storage": self.state.report_readiness(now=now, max_age_seconds=self._max_age)}

    def _check_cycle(self) -> None:
        if self._stopped.is_set() or self.state.report_inventory_invalidated(self._revision):
            raise _CoverageChanged
        current = validate_report_storage_root(self._root, self._descriptor)
        if (current.st_dev, current.st_ino) != (self._directory.st_dev, self._directory.st_ino):
            raise RuntimeError("report upload root identity changed")

    def _start_cycle(self, deadline: float) -> None:
        self._started = self._clock()
        self._revision = self.state.report_inventory_revision()
        encryption = self._encryption_check()
        with self._session() as db:
            recover_pending_report_writes(db, self._root, max_items=self.BATCH_SIZE, deadline=deadline)
            upload = self._upload_check()
            self._publish_auxiliary(self._started, upload, encryption)
            if encryption.get("ready") is not True or upload.get("ready") is not True:
                raise RuntimeError("report storage background prerequisite unavailable")
            self._descriptor = open_report_storage_root(self._root)
            self._directory = os.fstat(self._descriptor)
            self._key_states = self._keys()
            self._after = None
            self._row_work = None
            self._finish_work = []
            self._expected: dict[str, uuid.UUID] = {}
            self._phase = "rows"
            self._check_cycle()

    def _rows_batch(self, deadline: float) -> None:
        if self._row_work is None:
            with self._session() as db:
                self._check_cycle()
                page = read_report_storage_inventory_page(db, after=self._after, limit=self.BATCH_SIZE)
                entries = []
                candidates = []
                for entry in page:
                    if entries and self._clock() >= deadline:
                        break
                    pending = report_storage_inventory_pending(
                        self._root, entry, known_key_states=self._key_states,
                        destination_root=Path(f"/proc/self/fd/{self._descriptor}"),
                    )
                    name = entry.image_object.storage_name
                    if name in self._expected:
                        raise RuntimeError("duplicate report storage identity")
                    self._expected[name] = entry.report_id
                    entries.append(entry)
                    if not os.path.lexists(pending.destination):
                        # The namespace phase records the existing exact-set incident.
                        continue
                    fingerprint = report_storage_object_fingerprint(pending)
                    candidate = VerifiedReportStorageObject(entry, fingerprint)
                    if self._verified.get(entry.report_id) != candidate:
                        candidates.append((pending, candidate))
                self._row_work = _RowBatch(
                    entries, candidates,
                    last_page=len(page) < self.BATCH_SIZE and len(entries) == len(page),
                )
        work = self._row_work
        # Ciphertext reads and hashes never hold the reconciliation DB lock.
        # Budgets are cooperative between entries, not forced I/O cancellation.
        while work.hashed < len(work.candidates):
            if self._clock() >= deadline:
                return
            pending, candidate = work.candidates[work.hashed]
            validate_report_image_write(pending, candidate.entry.image_object)
            work.hashed += 1
        if self._clock() >= deadline:
            return
        with self._session() as db:
            self._check_cycle()
            current = read_report_storage_inventory_page(
                db, report_ids=[entry.report_id for entry in work.entries], limit=self.BATCH_SIZE,
            ) if work.entries else []
            if current != work.entries:
                raise _CoverageChanged
            while work.checked < len(work.candidates):
                if self._clock() >= deadline:
                    return
                pending, candidate = work.candidates[work.checked]
                if report_storage_object_fingerprint(pending) != candidate.fingerprint:
                    raise _CoverageChanged
                self._verified[candidate.entry.report_id] = candidate
                work.checked += 1
            self._row_work = None
            if work.entries:
                self._after = work.entries[-1].report_id
            if work.last_page:
                self._iterator = os.scandir(self._descriptor)
                self._phase = "namespace"

    def _namespace_batch(self, deadline: float) -> bool:
        with self._session() as db:
            self._check_cycle()
            for _ in range(self.BATCH_SIZE):
                if self._clock() >= deadline:
                    break
                entry = next(self._iterator, None)
                if entry is None:
                    self._phase = "finish"
                    return False
                validate_report_storage_namespace_entry(self._descriptor, entry.name, self._directory)
            self._check_cycle()
        return False

    def _finish_cycle(self, deadline: float) -> bool:
        encryption = self._encryption_check()
        with self._snapshot_lock:
            upload = deepcopy(self._auxiliary["upload_root"])
        self._publish_auxiliary(self._started, upload, encryption)
        if encryption.get("ready") is not True:
            raise RuntimeError("report encryption provider changed")
        # Writers can add IDs behind the metadata cursor. Reconcile newly seen
        # names within this same batch so one healthy write between batches does
        # not force an endless restart. Only names are enumerated here; content
        # validation remains outside the DB lock and is bounded between entries.
        while self._clock() < deadline:
            if self._finish_work:
                pending, candidate = self._finish_work[0]
                if self._verified.get(candidate.entry.report_id) != candidate:
                    validate_report_image_write(pending, candidate.entry.image_object)
                with self._session() as db:
                    self._check_cycle()
                    current = read_report_storage_inventory_page(db, report_ids=[candidate.entry.report_id], limit=self.BATCH_SIZE)
                    if current != [candidate.entry] or report_storage_object_fingerprint(pending) != candidate.fingerprint:
                        raise _CoverageChanged
                    self._verified[candidate.entry.report_id] = candidate
                    self._expected[candidate.entry.image_object.storage_name] = candidate.entry.report_id
                self._finish_work.pop(0)
                continue
            with self._session() as db:
                self._check_cycle()
                before = os.fstat(self._descriptor)
                names = set(os.listdir(self._descriptor))
                after = os.fstat(self._descriptor)
                if (before.st_mtime_ns, before.st_ctime_ns) != (after.st_mtime_ns, after.st_ctime_ns):
                    return False
                for special in (JOURNAL_DIRECTORY_NAME, RETENTION_QUARANTINE_DIRECTORY_NAME, REPORT_DELETION_QUARANTINE_DIRECTORY_NAME):
                    if special in names:
                        validate_report_storage_namespace_entry(self._descriptor, special, after)
                        names.remove(special)
                missing = set(self._expected) - names
                extra = names - set(self._expected)
                if missing:
                    return self._mismatch(db, names)
                if not extra:
                    # Local confirmed writes do not invalidate covered objects;
                    # pending or uncertain outcomes still prevent publication.
                    published = self.state.publish_report_inventory_success(self._revision, self._started)
                    if published:
                        current_ids = set(self._expected.values())
                        self._verified = {identity: item for identity, item in self._verified.items() if identity in current_ids}
                    return published
                for name in sorted(extra)[:self.BATCH_SIZE]:
                    if self._clock() >= deadline:
                        break
                    validate_report_storage_namespace_entry(self._descriptor, name, after)
                    report_id = uuid.UUID(name.removesuffix(".wse"))
                    entries = read_report_storage_inventory_page(db, report_ids=[report_id], limit=self.BATCH_SIZE)
                    if len(entries) != 1:
                        return self._mismatch(db, names)
                    entry = entries[0]
                    pending = report_storage_inventory_pending(
                        self._root, entry, known_key_states=self._key_states,
                        destination_root=Path(f"/proc/self/fd/{self._descriptor}"),
                    )
                    self._finish_work.append((pending, VerifiedReportStorageObject(entry, report_storage_object_fingerprint(pending))))
        return False

    def _mismatch(self, db: Session, names: set[str]) -> bool:
        mismatch = ReportStorageInventorySetMismatch(expected_storage_names=self._expected, actual_storage_names=names)
        try:
            self._record_incident(db, mismatch)
        except Exception:
            reason = "report_storage_inventory_incident_record_failed"
        else:
            reason = "report_storage_inventory_invalid"
        self.state.publish_report_inventory_failure(reason)
        return True

    def run_batch(self) -> bool:
        """Return True only when the cycle ended; False continues without refresh sleep."""
        if self._stopped.is_set():
            return True
        deadline = self._clock() + self.BATCH_SECONDS
        try:
            if self._phase == "start":
                self._start_cycle(deadline)
            elif self._phase == "rows":
                self._rows_batch(deadline)
            elif self._phase == "namespace":
                if self._namespace_batch(deadline):
                    self.close_cycle()
                    return True
            elif self._phase == "finish":
                if self._finish_cycle(deadline):
                    self.close_cycle()
                    return True
                return False
            return False
        except _CoverageChanged:
            self.close_cycle()
            return False
        except ReportStorageRecoveryDeferred:
            self.state.publish_report_inventory_failure("report_storage_recovery_deferred")
            self.close_cycle()
            return False
        except ReportStorageJournalUnresolved:
            reason = "report_storage_journal_unresolved"
        except OSError:
            reason = "report_storage_recovery_retryable" if self._phase == "start" else "report_storage_inventory_invalid"
        except Exception:
            reason = "report_storage_recovery_unavailable" if self._phase == "start" else "report_storage_inventory_invalid"
        self.state.publish_report_inventory_failure(reason)
        self.close_cycle()
        return True

    async def run(self, stop_requested: asyncio.Event) -> None:
        work: asyncio.Task[bool] | None = None

        def owned_batch() -> bool:
            try:
                return self.run_batch()
            finally:
                if self._stopped.is_set():
                    self.close_cycle()

        try:
            while not stop_requested.is_set():
                work = asyncio.create_task(asyncio.to_thread(owned_batch))
                try:
                    complete = await asyncio.shield(work)
                except asyncio.CancelledError:
                    self.stop()
                    # Cancellation fences publication, but does not close a FD
                    # still used by the worker. Permanent I/O is a separate limit.
                    await asyncio.shield(work)
                    raise
                if not complete:
                    await asyncio.sleep(0)
                    continue
                try:
                    await asyncio.wait_for(stop_requested.wait(), timeout=self._refresh)
                except asyncio.TimeoutError:
                    pass
        finally:
            self.stop()
            if work is None or work.done():
                self.close_cycle()
