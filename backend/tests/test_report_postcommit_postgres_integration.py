"""Real committed rows, expired ORM instances, HTTP receipts and journal recovery."""
from __future__ import annotations

import asyncio
from contextlib import contextmanager
from copy import copy
from datetime import UTC, datetime
import errno
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import uuid

from alembic.config import Config
from alembic.script import ScriptDirectory
from fastapi import FastAPI
import httpx
from PIL import Image
import pytest
from sqlalchemy import create_engine, func, inspect, select, text
from sqlalchemy.orm import Session, sessionmaker

from backend.app.api import reports
from backend.app.config import get_settings
from backend.app.models import Report, ReportImageObject
from backend.app.services import report_storage
from backend.app.services.report_image_keys import create_report_image_key_manager
from backend.app.services.report_storage_recovery import (
    locked_report_storage_session,
    recover_pending_report_writes,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


@pytest.fixture
def postcommit_database():
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"])
    head = ScriptDirectory.from_config(
        Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    ).get_current_head()
    try:
        with engine.connect() as connection:
            assert connection.dialect.name == "postgresql"
            assert connection.scalar(text("SELECT version_num FROM alembic_version")) == head
        yield engine
    finally:
        engine.dispose()


@pytest.mark.parametrize("failure_point", [
    "success", "refresh", "journal-unlink", "journal-logger",
    "cleanup-runtimeerror", "response-detached", "lock-exit",
    "commit-failure", "commit-uncertain",
])
def test_postcommit_real_rows_status_replay_and_recovery(
    postcommit_database, tmp_path, monkeypatch, failure_point,
):
    engine = postcommit_database
    report_id = uuid.uuid4()
    actor = f"postcommit-{report_id}@example.com"
    destination = tmp_path / f"{report_id}.wse"
    journal = report_storage.pending_journal_directory(tmp_path) / f"{report_id}.json"
    active_fault = True
    events = []
    sessions = []
    warnings = []
    expired_after_commit = []
    commit_count = 0
    rollback_count = 0
    private_error = "private-postcommit-integration-fault"

    class FaultSession(Session):
        def commit(self):
            nonlocal commit_count
            commit_count += 1
            events.append("commit-enter")
            new_reports = [value for value in self.new if isinstance(value, Report)]
            if active_fault and failure_point == "commit-failure":
                raise RuntimeError(private_error)
            super().commit()
            expired_after_commit.extend(inspect(value).expired for value in new_reports)
            events.append("database-committed")
            if active_fault and failure_point == "commit-uncertain":
                # The transaction is real; loss of its acknowledgement is injected.
                raise RuntimeError(private_error)
            events.append("commit-return")

        def refresh(self, value, *args, **kwargs):
            if isinstance(value, Report):
                events.append("refresh")
                if active_fault and failure_point == "refresh":
                    assert inspect(value).expired
                    raise RuntimeError(private_error)
            return super().refresh(value, *args, **kwargs)

        def rollback(self):
            nonlocal rollback_count
            rollback_count += 1
            return super().rollback()

    settings = copy(get_settings())
    settings.upload_dir = tmp_path
    settings.maintenance_lock_path = None
    settings.maintenance_lock_group_gid = None
    settings.field_test_security_enabled = False
    key_manager = create_report_image_key_manager(settings)
    app = FastAPI()
    app.include_router(reports.create_router(settings, key_manager))

    def request_database():
        with FaultSession(engine, autoflush=False) as db:
            assert db.expire_on_commit is True
            sessions.append(db)
            yield db

    app.dependency_overrides[reports.get_db] = request_database
    original_remove = report_storage.remove_image_file
    original_complete = reports.complete_report_image_write
    original_response = reports._field_creation_response

    def remove(path):
        if path == journal and active_fault and failure_point in {"journal-unlink", "journal-logger"}:
            raise OSError(errno.EIO, private_error)
        return original_remove(path)

    def complete(pending):
        events.append("cleanup")
        if active_fault and failure_point == "cleanup-runtimeerror":
            raise RuntimeError(private_error)
        return original_complete(pending)

    def response(report, *args, **kwargs):
        events.append("response")
        if active_fault and failure_point == "response-detached":
            db = inspect(report).session
            db.expire(report)
            db.expunge(report)
            assert inspect(report).detached and inspect(report).expired
        return original_response(report, *args, **kwargs)

    def warning(message):
        warnings.append(message)
        if active_fault and failure_point == "journal-logger":
            raise OSError(private_error)

    @contextmanager
    def maintenance_lock(*_args, **_kwargs):
        yield
        if active_fault and failure_point == "lock-exit":
            raise reports._ReportWriteLockExitError()

    monkeypatch.setattr(report_storage, "remove_image_file", remove)
    monkeypatch.setattr(reports, "complete_report_image_write", complete)
    monkeypatch.setattr(reports, "_field_creation_response", response)
    monkeypatch.setattr(reports, "_shared_report_write_lock", maintenance_lock)
    monkeypatch.setattr(reports, "logging", SimpleNamespace(
        getLogger=lambda _name: SimpleNamespace(warning=warning),
    ))

    image = io.BytesIO()
    Image.new("RGB", (4, 4), color=(70, 90, 120)).save(image, format="JPEG")
    content = image.getvalue()
    metadata = json.dumps({
        "schema_version": "detect.v2", "model_key": "custom_tactile",
        "source_model": "fake/postcommit-integration", "model_class_id": 1,
        "class_name": "damaged_tactile_block", "category": "tactile",
        "confidence": 0.91, "threshold_used": 0.25,
        "bbox": {"x": 0.2, "y": 0.3, "width": 0.4, "height": 0.2},
        "captured_at": datetime.now(UTC).isoformat(),
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "trigger": "on_screen", "auto_reported": False,
    }, separators=(",", ":"))
    reports.ReportV2Metadata.model_validate_json(metadata)
    headers = {
        "x-walksafe-actor-id": actor,
        "x-walksafe-account-generation": "1",
        "x-walksafe-report-id": str(report_id),
        "x-walksafe-report-payload-sha256": reports.report_transport_payload_sha256(
            report_id, metadata.encode(), content,
        ),
        "x-walksafe-report-payload-bytes": str(len(metadata.encode()) + len(content)),
    }

    async def request(method, path, **kwargs):
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            return await client.request(method, path, **kwargs)

    def submit():
        return asyncio.run(request("POST", "/reports/v2", headers=headers,
            data={"metadata": metadata}, files={"image": ("sample.jpg", content, "image/jpeg")}))

    first = submit()
    first_events = list(events)
    first_rollbacks = rollback_count
    active_fault = False
    commit_raises = failure_point in {"commit-failure", "commit-uncertain"}
    persisted = failure_point != "commit-failure"
    successful = failure_point in {"success", "journal-unlink", "journal-logger"}
    assert first.status_code == (201 if successful else 500 if commit_raises else 503), first.text
    if successful:
        assert first.json()["creation_outcome"] == "CREATED"
    else:
        assert "creation_outcome" not in first.text
    assert private_error not in first.text
    assert commit_count == 1
    assert first_rollbacks == int(commit_raises)
    assert expired_after_commit == ([True] if persisted else [])
    if not successful and not commit_raises:
        code = {
            "refresh": "report_postcommit_refresh_failed",
            "cleanup-runtimeerror": "report_postcommit_journal_cleanup_failed",
            "response-detached": "report_postcommit_response_failed",
            "lock-exit": "report_postcommit_lock_exit_failed",
        }[failure_point]
        assert first.json()["detail"]["code"] == code
        assert first.json()["detail"]["report_id"] == str(report_id)
        assert warnings == [code]
    if commit_raises:
        assert first.text == "Internal Server Error"
        assert "response" not in first_events
        assert "cleanup" not in first_events
        assert warnings == []

    envelope = destination.read_bytes()
    assert envelope.startswith(b"WSRI")
    journal_remains = failure_point in {
        "refresh", "journal-unlink", "journal-logger", "cleanup-runtimeerror",
        "commit-failure", "commit-uncertain",
    }
    assert journal.exists() is journal_remains
    with Session(engine) as probe:
        row = probe.get(Report, report_id)
        image_row = probe.get(ReportImageObject, report_id)
        assert (row is not None) is persisted
        assert (image_row is not None) is persisted
        if persisted:
            receipt = reports._report_transport_receipt(row).model_dump(mode="json")
            assert row.created_at is not None
            assert row.updated_at is not None
    status_path = f"/reports/v2/{report_id}/status"
    status_response = asyncio.run(request("GET", status_path, headers=headers))
    assert status_response.status_code == (200 if persisted else 404), status_response.text
    if persisted:
        assert status_response.json()["persistence_state"] == "PERSISTED"
        assert set(status_response.json()) == {"persistence_state", "user_status", "transport_receipt"}
        assert status_response.json()["transport_receipt"] == receipt
        replay = submit()
        assert replay.status_code == 201, replay.text
        assert replay.json()["creation_outcome"] == "REPLAYED"
        assert replay.json()["transport_receipt"] == receipt
        assert commit_count == 1
        assert journal.exists() is journal_remains
        assert destination.read_bytes() == envelope
        for changed_header, changed_value in [
            ("x-walksafe-actor-id", "different@example.com"),
            ("x-walksafe-account-generation", "2"),
        ]:
            hidden = asyncio.run(request("GET", status_path,
                headers={**headers, changed_header: changed_value}))
            assert hidden.status_code == 404
        with Session(engine) as probe:
            assert probe.scalar(select(func.count()).select_from(Report).where(Report.id == report_id)) == 1
            assert probe.scalar(select(func.count()).select_from(ReportImageObject).where(ReportImageObject.report_id == report_id)) == 1
    assert len({id(value) for value in sessions}) == len(sessions), "HTTP recovery must use fresh sessions"

    factory = sessionmaker(engine, autoflush=False)
    with locked_report_storage_session(factory, maintenance_lock, None, None) as db:
        recovery = recover_pending_report_writes(db, tmp_path)
    assert recovery.retained_committed == int(journal_remains and persisted)
    assert recovery.removed_orphans == int(not persisted)
    assert not journal.exists()
    assert destination.exists() is persisted
    if persisted:
        assert destination.read_bytes() == envelope
    print(json.dumps({
        "failure_point": failure_point, "first_status": first.status_code,
        "status_lookup": status_response.status_code, "commit_count": commit_count,
        "expired_after_commit": expired_after_commit, "persisted": persisted,
        "journal_before_recovery": journal_remains,
        "recovery_retained": recovery.retained_committed,
        "recovery_removed_orphans": recovery.removed_orphans,
    }, sort_keys=True))
