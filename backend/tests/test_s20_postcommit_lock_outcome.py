"""Direct shared-lock exit regression with synthetic DB and temporary images."""

from __future__ import annotations

from pathlib import Path
import socket
from types import SimpleNamespace
import uuid

from fastapi import HTTPException
import pytest
from sqlalchemy.engine import Engine

from backend.app.services import report_commit as reports
from backend.app.services import report_application, maintenance_lock
from backend.app.models import Report
from backend.app.services import report_storage


REPORT_ID = uuid.UUID("00000000-0000-4000-8000-000000000010")
MARKER_ID = uuid.UUID("00000000-0000-4000-8000-000000000011")
PAYLOAD_SHA256 = "a" * 64
SUBJECT = "b" * 64
IMAGE_BYTES = b"synthetic encrypted image"


@pytest.mark.parametrize("case", ("normal", "lock-exit-failure", "lock-entry-failure"))
def test_shared_lock_outcome_after_confirmed_commit(
    case: str, monkeypatch: pytest.MonkeyPatch, tmp_path: Path,
) -> None:
    forbidden_io = []

    def deny_external_io(*_args, **_kwargs):
        forbidden_io.append(True)
        raise AssertionError("no database or network access is allowed")

    monkeypatch.setattr(Engine, "connect", deny_external_io)
    monkeypatch.setattr(Engine, "raw_connection", deny_external_io)
    for name in ("connect", "connect_ex", "bind", "listen"):
        monkeypatch.setattr(socket.socket, name, deny_external_io)
    monkeypatch.setattr(socket, "create_connection", deny_external_io)
    monkeypatch.setattr(socket, "getaddrinfo", deny_external_io)

    lock_directory = tmp_path / "maintenance"
    lock_directory.mkdir(mode=0o700)
    lock_directory.chmod(0o700)
    lock_path = lock_directory / "write.lock"
    if case != "lock-entry-failure":
        lock_path.write_bytes(b"")
        lock_path.chmod(0o600)
    upload_directory = tmp_path / "uploads"
    upload_directory.mkdir(mode=0o700)
    destination = upload_directory / f"{REPORT_ID}.wse"
    pending = None
    encrypted_image = reports.encrypt_report_image(
        IMAGE_BYTES,
        report_id=REPORT_ID,
        content_type="image/jpeg",
        key_id="synthetic-key",
        key=b"x" * 32,
    )
    events = []
    trusted_checks = []
    deleted_images = []

    def trusted_parent(*_args):
        trusted_checks.append(True)
        return not (case == "lock-exit-failure" and len(trusted_checks) == 4)

    monkeypatch.setattr(maintenance_lock, "_trusted_maintenance_lock_parent", trusted_parent)
    monkeypatch.setattr(
        reports, "lock_report_storage_write_transaction",
        lambda _db: events.append("storage-lock"),
    )
    monkeypatch.setattr(
        reports, "encrypt_report_image",
        lambda *_args, **_kwargs: encrypted_image,
    )

    def stage(path, encrypted):
        nonlocal pending
        events.append("stage")
        assert path == destination
        pending = report_storage.stage_report_image(path, encrypted)
        return pending

    monkeypatch.setattr(reports, "stage_report_image", stage)
    monkeypatch.setattr(
        reports, "complete_report_image_write",
        lambda value: events.append("journal-cleanup") if value is pending else None,
    )
    monkeypatch.setattr(reports, "ReportImageObject", lambda **fields: SimpleNamespace(**fields))
    original_unlink = Path.unlink

    def guarded_unlink(path, *args, **kwargs):
        if path == destination:
            deleted_images.append(path)
            raise AssertionError("committed image must not be deleted")
        return original_unlink(path, *args, **kwargs)

    monkeypatch.setattr(Path, "unlink", guarded_unlink)

    class NewReport:
        committed = False
        late_id_reads = 0
        image_path = f"/uploads/{REPORT_ID}.jpg"
        image_content_type = "image/jpeg"

        @property
        def id(self):
            if self.committed:
                self.late_id_reads += 1
                raise AssertionError("committed report identity must use its snapshot")
            return REPORT_ID

    new_report = NewReport()
    stored_report = Report(
        id=REPORT_ID,
        status="new",
        client_payload_sha256=PAYLOAD_SHA256,
        client_payload_bytes=123,
        persistence_marker=MARKER_ID,
        privacy_subject_hmac=SUBJECT,
        account_generation=7,
    )

    class Database:
        commit_calls = 0
        refresh_calls = 0
        rollback_calls = 0
        committed = None

        def add(self, _value):
            events.append("add")

        def commit(self):
            self.commit_calls += 1
            events.append("commit")
            new_report.committed = True
            self.committed = stored_report

        def refresh(self, value):
            assert value is new_report
            self.refresh_calls += 1
            events.append("refresh")

        def rollback(self):
            self.rollback_calls += 1
            events.append("rollback")

        def execute(self, *_args, **_kwargs):
            return object()

        def scalar(self, statement):
            description, = statement.column_descriptions
            assert description["expr"] is Report
            report_id = statement.compile().params["id_1"]
            sql = str(statement)
            assert "NOT (EXISTS (" in sql and "account_deletion_tombstones" in sql
            return self.get(Report, report_id)

        def get(self, model, identifier):
            assert model is Report and identifier == REPORT_ID
            return self.committed

    db = Database()
    keys = SimpleNamespace(
        synchronize=lambda _db: None,
        encryption_slot=lambda: SimpleNamespace(key_id="synthetic-key", material=b"x" * 32),
    )

    caught = None
    try:
        reports._commit_new_report_with_image(
            db, new_report,
            destination=destination,
            content=b"synthetic input",
            content_type="image/jpeg",
            key_manager=keys,
            maintenance_lock_path=lock_path,
        )
    except HTTPException as exc:
        caught = exc

    assert forbidden_io == []
    assert deleted_images == []
    assert new_report.late_id_reads == 0
    if case == "lock-entry-failure":
        assert isinstance(caught, HTTPException)
        assert caught.status_code == 503
        assert caught.detail == {"code": "maintenance_lock_unavailable"}
        assert db.commit_calls == db.refresh_calls == db.rollback_calls == 0
        assert events == []
        assert not destination.exists()
        return

    assert db.commit_calls == db.refresh_calls == 1
    assert db.rollback_calls == 0
    assert events == ["storage-lock", "stage", "add", "add", "commit", "refresh", "journal-cleanup"]
    assert destination.read_bytes() == encrypted_image.envelope
    if case == "normal":
        assert caught is None
    else:
        assert isinstance(caught, HTTPException)
        assert caught.status_code == 503
        assert caught.detail == {
            "code": "report_postcommit_lock_exit_failed",
            "message": "The report was stored, but the maintenance lock could not be verified after the write.",
            "report_id": str(REPORT_ID),
        }
        assert isinstance(caught.__cause__, reports._ReportWriteLockExitError)

    # Existing same-owner status projection and fixed-ID resend resolve the outcome.
    statement = report_application._report_transport_status_statement(
        REPORT_ID, privacy_subject=SUBJECT, account_generation=7,
    )
    assert "reports.privacy_subject_hmac =" in str(statement)
    assert "reports.account_generation =" in str(statement)
    status = report_application._report_transport_status_response(db.committed)
    assert status.persistence_state == "PERSISTED"
    assert status.transport_receipt.report_id == REPORT_ID
    assert report_application._report_transport_replay(
        db,
        contract=report_application.ReportTransportContract(REPORT_ID, PAYLOAD_SHA256, 123),
        privacy_subject=SUBJECT,
        account_generation=7,
    ) is db.committed
