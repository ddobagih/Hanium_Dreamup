"""Direct, fixed-order regression for one report image at the write boundary."""

from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest

from backend.app.services import report_commit as report_api
from backend.app.models import ReportImageObject
from backend.app.services import report_storage


REPORT_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
KEY = bytes(range(32))
IMAGE = b"fixed-incremental-report-image"
EXPECTED_SUCCESS = (
    "lock_enter", "transaction_lock", "stage", "add_report", "add_image",
    "precheck", "validate", "commit", "refresh", "lock_exit",
)
EXPECTED_REJECTION = (
    "lock_enter", "transaction_lock", "stage", "add_report", "add_image",
    "precheck", "validate", "rollback", "lock_exit",
)


class RecordingSession:
    def __init__(self, report: SimpleNamespace) -> None:
        self.report = report
        self.events: list[str] = []
        self.image_object: ReportImageObject | None = None

    def add(self, value: object) -> None:
        if value is self.report:
            self.events.append("add_report")
        elif isinstance(value, ReportImageObject):
            self.image_object = value
            self.events.append("add_image")
        else:
            raise AssertionError("unexpected database row")

    def commit(self) -> None:
        self.events.append("commit")

    def rollback(self) -> None:
        self.events.append("rollback")

    def refresh(self, value: object) -> None:
        assert value is self.report
        self.events.append("refresh")


class StaticKeyManager:
    def synchronize(self, _db: RecordingSession) -> None:
        pass

    def encryption_slot(self) -> SimpleNamespace:
        return SimpleNamespace(key_id="fixed-test-key", material=KEY)


def _arrange(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> tuple[RecordingSession, SimpleNamespace, Path]:
    report = SimpleNamespace(
        id=REPORT_ID,
        image_path=f"/uploads/{REPORT_ID}.jpg",
        image_content_type="image/jpeg",
    )
    db = RecordingSession(report)
    destination = tmp_path / f"{REPORT_ID}.wse"

    @contextmanager
    def write_lock(*_args: object, **_kwargs: object):
        db.events.append("lock_enter")
        try:
            yield
        finally:
            db.events.append("lock_exit")

    def transaction_lock(_db: RecordingSession) -> None:
        assert _db is db
        db.events.append("transaction_lock")

    original_stage = report_api.stage_report_image

    def stage(path: Path, encrypted):
        db.events.append("stage")
        return original_stage(path, encrypted)

    original_validate = report_api.validate_report_image_write

    def validate(pending, metadata) -> None:
        db.events.append("validate")
        original_validate(pending, metadata)

    def forbidden_inventory(*_args: object, **_kwargs: object) -> None:
        pytest.fail("full report storage inventory was called by the writer")

    monkeypatch.setattr(report_api, "_shared_report_write_lock", write_lock)
    monkeypatch.setattr(report_api, "lock_report_storage_write_transaction", transaction_lock)
    monkeypatch.setattr(report_api, "stage_report_image", stage)
    monkeypatch.setattr(report_api, "validate_report_image_write", validate)
    monkeypatch.setattr(report_storage, "validate_report_storage_inventory", forbidden_inventory)
    monkeypatch.setattr(report_storage, "validate_report_storage_database_inventory", forbidden_inventory)
    return db, report, destination


def _write(
    db: RecordingSession, report: SimpleNamespace, destination: Path, precheck
) -> None:
    report_api._commit_new_report_with_image(
        db,
        report,
        destination=destination,
        content=IMAGE,
        content_type="image/jpeg",
        key_manager=StaticKeyManager(),
        precommit_check=precheck,
    )


def test_one_object_is_validated_once_after_precheck_before_commit(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    db, report, destination = _arrange(tmp_path, monkeypatch)

    def precheck() -> None:
        db.events.append("precheck")

    _write(db, report, destination, precheck)

    assert tuple(db.events) == EXPECTED_SUCCESS
    assert db.events.count("validate") == 1
    assert destination.read_bytes().startswith(b"WSRI")
    assert not (report_storage.pending_journal_directory(tmp_path) / f"{REPORT_ID}.json").exists()


@pytest.mark.parametrize(
    ("change", "message"),
    [("tamper_file", "digest"), ("mismatch_row", "metadata")],
)
def test_changed_staged_object_is_rejected_before_commit_without_unlink(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    change: str,
    message: str,
) -> None:
    db, report, destination = _arrange(tmp_path, monkeypatch)

    def precheck() -> None:
        db.events.append("precheck")
        if change == "tamper_file":
            tampered = bytearray(destination.read_bytes())
            tampered[-1] ^= 1
            destination.write_bytes(tampered)
        else:
            assert db.image_object is not None
            db.image_object.envelope_sha256 = "0" * 64

    with pytest.raises(RuntimeError, match=message):
        _write(db, report, destination, precheck)

    assert tuple(db.events) == EXPECTED_REJECTION
    assert db.events.count("validate") == 1
    assert destination.exists()
    assert (report_storage.pending_journal_directory(tmp_path) / f"{REPORT_ID}.json").exists()


@pytest.mark.parametrize("outcome", ["success", "prestage_abort", "ambiguous_commit", "cleanup_deferred"])
def test_writer_publishes_only_proven_clean_storage_outcomes(tmp_path, monkeypatch, outcome):
    from backend.app.services.storage_readiness_state import StorageReadinessState
    state = StorageReadinessState()
    state.publish_report_inventory_success(state.report_inventory_revision(), 100.0)
    db, report, destination = _arrange(tmp_path, monkeypatch)
    manager = StaticKeyManager()

    def fail(*_args):
        raise OSError("owned injected failure")

    if outcome == "prestage_abort":
        monkeypatch.setattr(manager, "encryption_slot", fail)
    elif outcome == "ambiguous_commit":
        monkeypatch.setattr(db, "commit", fail)
    elif outcome == "cleanup_deferred":
        monkeypatch.setattr(report_api, "complete_report_image_write", fail)

    def precheck():
        assert state.report_readiness(now=101, max_age_seconds=30)["reason"] == "report_storage_write_pending"

    def write():
        report_api._commit_new_report_with_image(
            db, report, destination=destination, content=IMAGE, content_type="image/jpeg",
            key_manager=manager, precommit_check=precheck, storage_readiness=state,
        )

    if outcome in {"prestage_abort", "ambiguous_commit"}:
        with pytest.raises(OSError, match="owned injected failure"):
            write()
    else:
        write()
    snapshot = state.snapshot().report
    assert not snapshot.pending_commits
    assert snapshot.ready is (outcome in {"success", "prestage_abort"})
    if not snapshot.ready:
        assert state.report_readiness(now=101, max_age_seconds=30)["reason"] == "report_storage_recovery_pending"
    assert state.report_readiness(now=131, max_age_seconds=30)["ready"] is False
