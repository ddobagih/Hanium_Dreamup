"""Direct real-helper regressions with temporary encrypted fixtures, no live DB."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]

_HELPER_CHILD = r"""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import socket
import sys
from types import SimpleNamespace
import uuid

case = sys.argv[1]
phase = "guards"
attempts = {"database": 0, "network": 0}

def deny_database(*args, **kwargs):
    attempts["database"] += 1
    raise AssertionError("postcommit helper test forbids database connections")

def deny_network(*args, **kwargs):
    attempts["network"] += 1
    raise AssertionError("postcommit helper test forbids network connections")

socket.socket.connect = deny_network
socket.socket.connect_ex = deny_network
socket.create_connection = deny_network
socket.getaddrinfo = deny_network
from sqlalchemy.engine import Engine
Engine.connect = deny_database
Engine.raw_connection = deny_database

try:
    phase = "imports"
    from backend.app import config
    # Import-only engine configuration, not validated runtime Settings or auth.
    config.get_settings = lambda: SimpleNamespace(
        database_url="postgresql+psycopg://walksafe_test:disabled@127.0.0.1:1/walksafe_pytest_unconfigured",
        database_connect_timeout_seconds=1,
        database_statement_timeout_ms=1000,
    )
    from fastapi import HTTPException
    from backend.app.services import report_commit as reports
    from backend.app.services import report_storage

    events = []
    warnings = []
    deletions = []
    expected_id = uuid.UUID("00000000-0000-4000-8000-000000000010")
    refresh_error = RuntimeError("owned refresh failure")
    commit_error = RuntimeError("owned commit failure")
    precommit_error = RuntimeError("owned precommit failure")
    logger_error = RuntimeError("owned logger failure")
    cleanup_error = OSError("owned journal cleanup failure")
    content = b"owned synthetic image"
    content_type = "image/jpeg"
    destination = Path(sys.argv[2]) / f"{expected_id}.wse"
    staged = []
    encryption_calls = []
    cleanup_calls = []

    class ReportSentinel:
        image_path = f"/uploads/{expected_id}.jpg"
        image_content_type = content_type

        def __init__(self):
            self.expired = False
            self.expired_id_reads = 0

        @property
        def id(self):
            if self.expired:
                self.expired_id_reads += 1
                raise AssertionError("expired report identity was accessed")
            return expected_id

    report = ReportSentinel()

    class Database:
        def __init__(self):
            self.added = []
            self.commit_calls = 0
            self.refresh_calls = 0
            self.rollback_calls = 0

        def add(self, value):
            events.append("add")
            self.added.append(value)

        def commit(self):
            events.append("commit")
            self.commit_calls += 1
            if case == "commit-failure":
                raise commit_error
            report.expired = True
            events.append("commit_returned")

        def refresh(self, value):
            events.append("refresh")
            self.refresh_calls += 1
            if value is not report:
                raise AssertionError("unexpected refreshed report")
            if case in {"refresh-failure", "logger-failure"}:
                raise refresh_error
            report.expired = False
            events.append("refresh_returned")

        def rollback(self):
            events.append("rollback")
            self.rollback_calls += 1

    database = Database()
    active_key = SimpleNamespace(
        key_id="owned-synthetic-key", material=b"x" * 32,
    )

    class Keys:
        def synchronize(self, value):
            events.append("keys_synchronize")
            if value is not database:
                raise AssertionError("unexpected key synchronization database")

        def encryption_slot(self):
            events.append("encryption_slot")
            return active_key

    encrypted = reports.encrypt_report_image(
        content,
        report_id=expected_id,
        content_type=content_type,
        key_id=active_key.key_id,
        key=active_key.material,
        nonce=b"n" * 12,
    )
    pending = report_storage.stage_report_image(destination, encrypted)

    def encrypt(value, **kwargs):
        events.append("encrypt")
        encryption_calls.append((value, kwargs))
        return encrypted

    def stage(path, value):
        events.append("stage")
        staged.append((path, value))
        return pending

    def complete(value):
        events.append("cleanup")
        cleanup_calls.append(value)
        if case in {"journal-oserror", "journal-logger-failure"}:
            raise cleanup_error

    def storage_lock(value):
        events.append("storage_lock")
        if value is not database:
            raise AssertionError("unexpected storage lock database")

    @contextmanager
    def maintenance_lock(path, *, expected_group_gid=None):
        events.append("maintenance_enter")
        try:
            yield
        finally:
            events.append("maintenance_exit")

    def precommit_check():
        events.append("precommit_check")
        if case == "precommit-failure":
            raise precommit_error

    class Logger:
        def warning(self, message, *args, **kwargs):
            events.append("warning")
            warnings.append(message)
            if case in {"logger-failure", "journal-logger-failure"}:
                raise logger_error

    logger = Logger()

    def deny_delete(*args, **kwargs):
        deletions.append("delete_attempt")
        raise AssertionError("helper must not compensate by deleting an image or journal")

    reports._shared_report_write_lock = maintenance_lock
    reports.lock_report_storage_write_transaction = storage_lock
    reports.encrypt_report_image = encrypt
    reports.stage_report_image = stage
    reports.complete_report_image_write = complete
    reports.ReportImageObject = lambda **fields: SimpleNamespace(**fields)
    reports.logging.getLogger = lambda *args, **kwargs: logger
    Path.unlink = deny_delete
    os.unlink = deny_delete
    os.remove = deny_delete

    phase = "helper"
    outcome = object()
    caught = None
    try:
        outcome = reports._commit_new_report_with_image(
            database,
            report,
            destination=destination,
            content=content,
            content_type=content_type,
            key_manager=Keys(),
            maintenance_lock_path=None,
            precommit_check=precommit_check,
        )
    except BaseException as error:
        caught = error

    phase = "assertions"
    assert len(database.added) == 2
    assert database.added[0] is report
    assert database.added[1].report_id == expected_id
    assert staged == [(destination, encrypted)]
    assert len(encryption_calls) == 1
    assert encryption_calls[0][0] == content
    assert encryption_calls[0][1]["report_id"] == expected_id
    assert events[0] == "maintenance_enter"
    assert events[-1] == "maintenance_exit"
    assert report.expired_id_reads == 0
    assert deletions == []

    if case in {"refresh-failure", "logger-failure"}:
        assert isinstance(caught, HTTPException), type(caught).__name__
        assert caught.status_code == 503
        assert caught.detail == {
            "code": "report_postcommit_refresh_failed",
            "message": "The report was stored, but its confirmation response is unavailable.",
            "report_id": "00000000-0000-4000-8000-000000000010",
        }
        assert caught.__cause__ is refresh_error
        assert database.commit_calls == 1
        assert database.refresh_calls == 1
        assert events.index("commit_returned") < events.index("refresh")
        assert database.rollback_calls == 0
        assert cleanup_calls == []
        assert report.expired is True
        assert warnings == ["report_postcommit_refresh_failed"]
    elif case in {"commit-failure", "precommit-failure"}:
        expected_error = commit_error if case == "commit-failure" else precommit_error
        assert caught is expected_error
        assert not isinstance(caught, HTTPException)
        assert database.commit_calls == (1 if case == "commit-failure" else 0)
        assert "commit_returned" not in events
        assert database.refresh_calls == 0
        assert database.rollback_calls == 1
        assert cleanup_calls == []
        assert warnings == []
    else:
        assert case in {"normal", "journal-oserror", "journal-logger-failure"}
        assert caught is None, repr(caught)
        assert outcome is None
        assert database.commit_calls == 1
        assert database.refresh_calls == 1
        assert database.rollback_calls == 0
        assert events.index("commit_returned") < events.index("refresh")
        assert events.index("refresh_returned") < events.index("cleanup")
        assert cleanup_calls == [pending]
        assert report.expired is False
        assert warnings == (
            ["report_postcommit_journal_cleanup_deferred"]
            if case in {"journal-oserror", "journal-logger-failure"} else []
        )
    assert attempts == {"database": 0, "network": 0}
    print(json.dumps({"ok": True, "case": case, "attempts": attempts}))
except BaseException as error:
    print(json.dumps({
        "ok": False, "case": case, "phase": phase,
        "error_type": type(error).__name__, "error": str(error),
        "attempts": attempts,
    }))
    raise SystemExit(1)
"""


def _run_helper_case(case: str) -> None:
    with tempfile.TemporaryDirectory(prefix="walksafe-postcommit-refresh-") as upload_dir:
        completed = subprocess.run(
            [sys.executable, "-B", "-c", _HELPER_CHILD, case, upload_dir],
            cwd=ROOT,
            env={
                "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
                "PYTHONPATH": str(ROOT),
                "PYTHONDONTWRITEBYTECODE": "1",
            },
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=20,
        )
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout) == {
        "ok": True, "case": case, "attempts": {"database": 0, "network": 0},
    }


def test_normal_commit_refresh_and_journal_completion_remain_successful():
    _run_helper_case("normal")


def test_commit_failure_preserves_original_exception_and_rollback():
    _run_helper_case("commit-failure")


def test_precommit_failure_preserves_original_exception_and_rollback():
    _run_helper_case("precommit-failure")


def test_refresh_failure_returns_committed_identity_without_cleanup_or_rollback():
    _run_helper_case("refresh-failure")


def test_normal_refresh_keeps_journal_oserror_deferred():
    _run_helper_case("journal-oserror")


def test_raising_refresh_logger_cannot_replace_fixed_error_or_original_cause():
    _run_helper_case("logger-failure")


def test_raising_cleanup_logger_cannot_replace_confirmed_success():
    _run_helper_case("journal-logger-failure")
