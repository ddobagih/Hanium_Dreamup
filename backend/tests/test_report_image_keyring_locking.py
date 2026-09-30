"""Offline lock oracle for report-image keyring generation synchronization."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from threading import Condition, Event
from types import SimpleNamespace

import pytest

from backend.app.models import ReportImageKeyringEvent
from backend.app.services.report_image_keys import (
    REPORT_IMAGE_KEYRING_LOCK_KEY,
    ReportImageKeySlot,
    ReportImageKeyring,
    ReportImageKeyringInvalid,
    synchronize_report_image_keyring,
)


FIRST = ReportImageKeyring(
    generation=1,
    previous_manifest_sha256=None,
    manifest_sha256="a" * 64,
    slots=(ReportImageKeySlot("key-1", "active", b"1" * 32, "1" * 64),),
)
SECOND = ReportImageKeyring(
    generation=2,
    previous_manifest_sha256=FIRST.manifest_sha256,
    manifest_sha256="b" * 64,
    slots=(
        ReportImageKeySlot("key-1", "decrypt-only", b"1" * 32, "1" * 64),
        ReportImageKeySlot("key-2", "active", b"2" * 32, "2" * 64),
    ),
)


def _event(keyring: ReportImageKeyring) -> ReportImageKeyringEvent:
    return ReportImageKeyringEvent(
        generation=keyring.generation,
        manifest_sha256=keyring.manifest_sha256,
        previous_manifest_sha256=keyring.previous_manifest_sha256,
        active_key_id=keyring.active_slot.key_id,
        key_states=keyring.public_states(),
    )


class _LockStore:
    """Model transaction-scoped PostgreSQL shared/exclusive advisory locks."""

    def __init__(self) -> None:
        self.events = [_event(FIRST)]
        self.condition = Condition()
        self.shared: set[_Session] = set()
        self.exclusive: _Session | None = None
        self.lock_attempted = Event()

    def latest(self) -> ReportImageKeyringEvent | None:
        with self.condition:
            return self.events[-1] if self.events else None

    def acquire(self, session: _Session, mode: str) -> None:
        with self.condition:
            self.lock_attempted.set()
            available = lambda: self.exclusive is None and (
                mode == "shared" or not self.shared
            )
            if not self.condition.wait_for(available, timeout=3):
                raise TimeoutError("simulated advisory lock remained blocked")
            if mode == "shared":
                self.shared.add(session)
            else:
                self.exclusive = session

    def finish(self, session: _Session, pending: list[ReportImageKeyringEvent]) -> None:
        with self.condition:
            self.events.extend(pending)
            self.shared.discard(session)
            if self.exclusive is session:
                self.exclusive = None
            self.condition.notify_all()

    def publish_between_reads(self, keyring: ReportImageKeyring) -> None:
        with self.condition:
            assert not self.shared and self.exclusive is None
            self.events.append(_event(keyring))


class _Session:
    def __init__(self, store: _LockStore, *, before_lock=None) -> None:
        self.store = store
        self.before_lock = before_lock
        self.lock_modes: list[str] = []
        self.pending: list[ReportImageKeyringEvent] = []

    def execute(self, statement, parameters=None):
        if parameters is None:
            assert statement.column_descriptions[0]["entity"] is ReportImageKeyringEvent
            latest = self.store.latest()
            return SimpleNamespace(scalar_one_or_none=lambda: latest)
        assert parameters == {"lock_key": REPORT_IMAGE_KEYRING_LOCK_KEY}
        sql = str(statement)
        if sql == "SELECT pg_advisory_xact_lock_shared(hashtextextended(:lock_key, 0))":
            mode = "shared"
        else:
            assert sql == "SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"
            mode = "exclusive"
        self.lock_modes.append(mode)
        if self.before_lock is not None:
            callback = self.before_lock
            self.before_lock = None
            callback()
        self.store.acquire(self, mode)
        return SimpleNamespace()

    def add(self, event: ReportImageKeyringEvent) -> None:
        self.pending.append(event)

    def flush(self) -> None:
        pass

    def commit(self) -> None:
        self.store.finish(self, self.pending)
        self.pending.clear()

    def rollback(self) -> None:
        self.pending.clear()
        self.store.finish(self, [])


def test_same_generation_reports_share_lock_until_both_transactions_end() -> None:
    store = _LockStore()
    first = _Session(store)
    second = _Session(store)
    synchronize_report_image_keyring(first, FIRST)
    with ThreadPoolExecutor(max_workers=1) as pool:
        try:
            pool.submit(synchronize_report_image_keyring, second, FIRST).result(timeout=4)
            assert first.lock_modes == second.lock_modes == ["shared"]
            assert store.shared == {first, second}
            assert len(store.events) == 1
        finally:
            first.rollback()
            second.rollback()


def test_generation_append_waits_for_shared_report_transaction() -> None:
    store = _LockStore()
    reader = _Session(store)
    writer = _Session(store)
    synchronize_report_image_keyring(reader, FIRST)
    store.lock_attempted.clear()
    with ThreadPoolExecutor(max_workers=1) as pool:
        try:
            write = pool.submit(synchronize_report_image_keyring, writer, SECOND)
            assert store.lock_attempted.wait(timeout=4)
            assert not write.done()
            assert writer.pending == []
            reader.commit()
            write.result(timeout=4)
            assert writer.lock_modes == ["exclusive"]
            assert [event.generation for event in writer.pending] == [2]
            writer.commit()
            assert [event.generation for event in store.events] == [1, 2]
        finally:
            reader.rollback()
            writer.rollback()


def test_generation_change_between_read_and_shared_lock_fails_closed() -> None:
    store = _LockStore()
    session = _Session(store, before_lock=lambda: store.publish_between_reads(SECOND))
    try:
        with pytest.raises(ReportImageKeyringInvalid, match="changed while acquiring shared lock"):
            synchronize_report_image_keyring(session, FIRST)
        assert session.lock_modes == ["shared"]
        assert session.pending == []
        assert [event.generation for event in store.events] == [1, 2]
    finally:
        session.rollback()
