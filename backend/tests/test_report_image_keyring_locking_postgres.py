"""S20-9 on real PostgreSQL: same-generation writers share the keyring lock; rotation is exclusive."""

from __future__ import annotations

from copy import copy
import os

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

from backend.app.config import get_settings
from backend.app.services.report_image_keys import (
    REPORT_IMAGE_KEYRING_LOCK_KEY,
    ReportImageKeySlot,
    ReportImageKeyring,
    create_report_image_key_manager,
    synchronize_report_image_keyring,
)

pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


@pytest.fixture
def keyring_db(clean_test_storage):
    assert clean_test_storage is not None
    clean_test_storage()
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"])
    with engine.connect() as connection:
        assert "test" in connection.scalar(text("SELECT current_database()"))
    manager = create_report_image_key_manager(copy(get_settings()))
    with Session(engine) as setup:
        manager.synchronize(setup)
        setup.commit()
    try:
        yield engine, manager
    finally:
        engine.dispose()


def _keyring_lock_modes(engine) -> list[tuple[str, bool]]:
    with engine.connect() as connection:
        return sorted(
            (row.mode, row.granted)
            for row in connection.execute(text("""
                SELECT mode, granted FROM pg_locks
                WHERE locktype = 'advisory'
                  AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(:key, 0)
            """), {"key": REPORT_IMAGE_KEYRING_LOCK_KEY})
        )


def _blocked(db: Session, action) -> bool:
    db.execute(text("SET LOCAL lock_timeout = '200ms'"))
    try:
        action()
    except OperationalError as exc:
        assert type(exc.orig).__name__ == "LockNotAvailable"
        return True
    return False


def _next_generation(current: ReportImageKeyring) -> ReportImageKeyring:
    return ReportImageKeyring(
        generation=current.generation + 1,
        previous_manifest_sha256=current.manifest_sha256,
        manifest_sha256="f" * 64,
        slots=tuple(
            ReportImageKeySlot(
                slot.key_id,
                "decrypt-only" if slot.state == "active" else slot.state,
                slot.material,
                slot.material_sha256,
            )
            for slot in current.slots
        ) + (ReportImageKeySlot("s20-9-next", "active", b"n" * 32, "e" * 64),),
    )


def test_same_generation_writers_hold_the_keyring_lock_together(keyring_db) -> None:
    engine, manager = keyring_db
    with Session(engine) as first, Session(engine) as second:
        try:
            manager.synchronize(first)
            assert not _blocked(second, lambda: manager.synchronize(second))
            assert _keyring_lock_modes(engine) == [("ShareLock", True), ("ShareLock", True)]
        finally:
            second.rollback()
            first.rollback()


def test_generation_change_is_exclusive_against_running_writers(keyring_db) -> None:
    engine, manager = keyring_db
    rotated = _next_generation(manager.keyring)
    with Session(engine) as writer, Session(engine) as rotation:
        try:
            manager.synchronize(writer)
            assert _blocked(rotation, lambda: synchronize_report_image_keyring(rotation, rotated))
        finally:
            rotation.rollback()
            writer.rollback()
    with Session(engine) as rotation, Session(engine) as writer:
        try:
            synchronize_report_image_keyring(rotation, rotated)  # uncommitted append
            assert _keyring_lock_modes(engine) == [("ExclusiveLock", True)]
            assert _blocked(writer, lambda: manager.synchronize(writer))
        finally:
            writer.rollback()
            rotation.rollback()
    with engine.connect() as connection:
        assert connection.scalar(text("SELECT count(*) FROM report_image_keyring_events")) == 1
