"""Isolated regression for the real report-list route's read-audit payload."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]


_ROUTE_CHILD = r"""
import inspect
import json
import socket
from types import SimpleNamespace
import uuid

attempts = {"database": 0, "network": 0}

def deny_database(*args, **kwargs):
    attempts["database"] += 1
    raise AssertionError("report-list test forbids database connections")

def deny_network(*args, **kwargs):
    attempts["network"] += 1
    raise AssertionError("report-list test forbids network connections")

socket.socket.connect = deny_network
socket.socket.connect_ex = deny_network
socket.create_connection = deny_network
socket.getaddrinfo = deny_network

from sqlalchemy.engine import Engine
Engine.connect = deny_database
Engine.raw_connection = deny_database

from backend.app import config
config.get_settings = lambda: SimpleNamespace(
    database_url="postgresql+psycopg://walksafe_test:disabled@127.0.0.1:1/walksafe_pytest_unconfigured",
    database_connect_timeout_seconds=1,
    database_statement_timeout_ms=1000,
)

from fastapi.params import Param
from backend.app.api import reports

router = reports.create_router(
    SimpleNamespace(admin_security_enabled=False, field_test_security_enabled=True),
    object(),
)
endpoint = next(
    route.endpoint for route in router.routes
    if route.path == "/reports" and "GET" in route.methods
)
arguments = {
    name: parameter.default.default if isinstance(parameter.default, Param) else parameter.default
    for name, parameter in inspect.signature(endpoint).parameters.items()
}
arguments.update(
    limit=1,
    offset=0,
    model_key="isolated-list-audit",
    lat=37.123456789,
    lng=127.987654321,
    radius_m=20.0,
    x_walksafe_actor_id="audit.list.fingerprint@example.com",
    x_walksafe_read_purpose="admin_report_list",
)

first_id = uuid.UUID("00000000-0000-4000-8000-000000000001")
second_id = uuid.UUID("00000000-0000-4000-8000-000000000002")
first = SimpleNamespace(id=first_id, content_revision=1, status_version=1)
second = SimpleNamespace(id=second_id, content_revision=1, status_version=1)
revised_second = SimpleNamespace(id=second_id, content_revision=2, status_version=1)
pages = [[first], [second], [revised_second], [first, second], [second, first]]

class Database:
    def __init__(self):
        self.calls = 0
        self.expire_on_commit = []

    def scalars(self, statement):
        self.calls += 1
        assert self.calls <= len(pages)
        return SimpleNamespace(all=lambda: pages[self.calls - 1])

    def commit(self):
        for row in self.expire_on_commit:
            row.expired = True

database = Database()
arguments["db"] = database
audits = []

def capture_audit(db, *, actor_id, purpose, resource_type, resource_id, details):
    assert db is database
    assert actor_id == "audit.list.fingerprint@example.com"
    assert purpose == "admin_report_list"
    assert resource_type == "report_list"
    assert resource_id == "reports"
    audits.append(dict(details))

reports.persist_report_read_audit = capture_audit
reports.report_to_response = lambda report: {"id": str(report.id)}

def invoke(**changes):
    call_arguments = {**arguments, **changes}
    return endpoint(**call_arguments)

assert [row["id"] for row in invoke()] == [str(first_id)]
assert [row["id"] for row in invoke()] == [str(second_id)]
assert [row["id"] for row in invoke()] == [str(second_id)]
assert [row["id"] for row in invoke(limit=2)] == [str(first_id), str(second_id)]
assert [row["id"] for row in invoke(limit=2)] == [str(second_id), str(first_id)]
assert database.calls == 5 and len(audits) == 5

fingerprints = [audit["result_fingerprint_sha256"] for audit in audits]
assert all(len(value) == 64 and all(char in "0123456789abcdef" for char in value)
           for value in fingerprints)
assert fingerprints[0] != fingerprints[1]  # Same filters and count, different returned ID.
assert fingerprints[1] != fingerprints[2]  # Same ID, changed content revision.
assert fingerprints[3] == fingerprints[4]  # Same two rows in the opposite order.

assert all(audit["result_count"] == 1 and audit["limit"] == 1 and audit["offset"] == 0
           for audit in audits[:3])
assert all(audit["result_count"] == 2 and audit["limit"] == 2 and audit["offset"] == 0
           for audit in audits[3:])
assert all(audit["exact_location_filter_applied"] is True for audit in audits)
for audit in audits:
    detail_text = json.dumps(audit, sort_keys=True)
    assert str(first_id) not in detail_text and str(second_id) not in detail_text
    assert "37.123456789" not in detail_text and "127.987654321" not in detail_text
    assert "lat" not in audit and "lng" not in audit

expiring_id = uuid.UUID("00000000-0000-4000-8000-000000000003")

class ExpiringReport:
    expired = False

    @property
    def id(self):
        assert not self.expired, "report was reloaded after audit commit"
        return expiring_id

    @property
    def content_revision(self):
        assert not self.expired, "report was reloaded after audit commit"
        return 7

    @property
    def status_version(self):
        assert not self.expired, "report was reloaded after audit commit"
        return 2

expiring = ExpiringReport()
pages.append([expiring])
database.expire_on_commit.append(expiring)
expected_fingerprint = reports._report_list_fingerprint([
    SimpleNamespace(id=expiring_id, content_revision=7, status_version=2)
])

def capture_and_commit_audit(db, **kwargs):
    capture_audit(db, **kwargs)
    db.commit()

reports.persist_report_read_audit = capture_and_commit_audit
assert [row["id"] for row in invoke()] == [str(expiring_id)]
assert expiring.expired
assert audits[-1]["result_fingerprint_sha256"] == expected_fingerprint
assert audits[-1]["result_count"] == 1
assert str(expiring_id) not in json.dumps(audits[-1])
assert database.calls == 6 and len(audits) == 6

assert attempts == {"database": 0, "network": 0}
print(json.dumps({"ok": True, "attempts": attempts}))
"""


def test_report_list_route_audit_fingerprint_without_database() -> None:
    completed = subprocess.run(
        [sys.executable, "-B", "-c", _ROUTE_CHILD],
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
        "ok": True,
        "attempts": {"database": 0, "network": 0},
    }
