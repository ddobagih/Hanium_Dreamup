"""Direct export-route regression for streamed, byte-bounded input."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys

import pytest


ROOT = Path(__file__).resolve().parents[2]

_CHILD = r'''
from datetime import UTC, datetime
import inspect
import json
import socket
import sys
from types import SimpleNamespace
import uuid

from sqlalchemy.engine import Engine

mode = sys.argv[1]
attempts = {"database": 0, "network": 0}
def deny_database(*args, **kwargs):
    attempts["database"] += 1
    raise AssertionError("live database forbidden")
def deny_network(*args, **kwargs):
    attempts["network"] += 1
    raise AssertionError("network forbidden")
Engine.connect = deny_database
Engine.raw_connection = deny_database
socket.socket.connect = deny_network
socket.socket.connect_ex = deny_network
socket.create_connection = deny_network
socket.getaddrinfo = deny_network

from backend.app import config
config.get_settings = lambda: SimpleNamespace(
    database_url="postgresql+psycopg://walksafe_test:disabled@127.0.0.1:1/walksafe_pytest_unconfigured",
    database_connect_timeout_seconds=1,
    database_statement_timeout_ms=1000,
)
from backend.app.api import reports
from fastapi import HTTPException
from fastapi.params import Param

now = datetime(2026, 1, 1, tzinfo=UTC)
def record(number, *, payload=None):
    return {
        "id": uuid.UUID(f"00000000-0000-4000-8000-{number:012d}"),
        "status": "new", "class_id": 0,
        "class_name": "damaged_tactile_block", "confidence": 0.9,
        "source": "server", "latitude": 37.125, "longitude": 127.125,
        "accuracy_m": 3.0, "heading": 90.0,
        "bbox_x": 0.1, "bbox_y": 0.2, "bbox_width": 0.3,
        "bbox_height": 0.4, "captured_at": now, "created_at": now,
        "updated_at": now, "image_path": "/uploads/owned.wse",
        "image_content_type": "image/jpeg",
        "payload": {"review_note": "=2+2"} if payload is ... else payload,
    }

class Stream:
    def __init__(self, records):
        self.records = records
        self.seen = 0
        self.closed = False
    def mappings(self):
        return self
    def __iter__(self):
        for item in self.records:
            self.seen += 1
            yield item
    def all(self):
        raise AssertionError("eager result materialization")
    def close(self):
        self.closed = True

class Database:
    def __init__(self, records):
        self.stream = Stream(records)
        self.calls = 0
    def execute(self, statement):
        self.calls += 1
        assert statement.get_execution_options()["yield_per"] == 1
        sql = str(statement)
        assert "CASE WHEN" in sql and "octet_length" in sql
        assert "reports.metadata" in sql
        assert "ORDER BY reports.created_at DESC, reports.id DESC" in sql
        return self.stream
    def scalars(self, statement):
        raise AssertionError("ORM scalar hydration")

if mode == "oversized-input":
    records = [record(1, payload=None), record(2, payload={"review_note": "next"})]
elif mode == "oversized-output":
    reports.MAX_REPORT_EXPORT_BYTES = 256
    records = [record(1, payload={"review_note": "x" * 500}), record(2, payload={"review_note": "next"})]
elif mode == "row-cap":
    reports.MAX_REPORT_EXPORT_ROWS = 1
    records = [record(1, payload={"review_note": "first"}), record(2, payload={"review_note": "second"})]
else:
    records = [record(1, payload={"review_note": "=2+2"})]
db = Database(records)
audits = []
reports._reauthorize_admin_high_risk_in_transaction = lambda *args: None
reports._persist_export_audit = lambda *args, **kwargs: audits.append(kwargs)
router = reports.create_router(SimpleNamespace(admin_security_enabled=False, field_test_security_enabled=False), object())
endpoint = next(route.endpoint for route in router.routes if route.path == "/reports/export")
arguments = {
    name: parameter.default.default if isinstance(parameter.default, Param) else parameter.default
    for name, parameter in inspect.signature(endpoint).parameters.items()
}
arguments.update(request=object(), db=db, x_walksafe_actor_id="owned-admin")
if mode == "grid":
    arguments.update(format="geojson", aggregate="grid")
elif mode != "csv":
    arguments["format"] = "json"

if mode in {"oversized-input", "oversized-output", "row-cap"}:
    try:
        endpoint(**arguments)
    except HTTPException as error:
        assert error.status_code == 413
        assert error.detail["code"] == "report_export_too_large"
        assert ("max_rows" in error.detail) is (mode == "row-cap")
    else:
        raise AssertionError("oversized export was returned")
    assert audits == []
    assert db.stream.seen == (2 if mode == "row-cap" else 1)
else:
    response = endpoint(**arguments)
    assert response.status_code == 200
    assert len(audits) == 1 and audits[0]["row_count"] == 1
    if mode == "grid":
        body = json.loads(response.body)
        assert body["properties"]["total"] == 1
        assert body["features"][0]["properties"]["count"] == 1
    elif mode == "csv":
        assert b"'=2+2" in response.body
    else:
        assert json.loads(response.body)[0]["review_note"] == "=2+2"
    assert db.stream.seen == 1
assert db.calls == 1 and db.stream.closed
assert attempts == {"database": 0, "network": 0}
print(json.dumps({"ok": True, "mode": mode, "attempts": attempts}))
'''


@pytest.mark.parametrize("mode", [
    "json", "csv", "grid", "oversized-input", "oversized-output", "row-cap",
])
def test_export_streams_projection_and_rejects_before_audit(mode):
    completed = subprocess.run(
        [sys.executable, "-B", "-c", _CHILD, mode],
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
    assert completed.returncode == 0, completed.stdout + completed.stderr
    assert json.loads(completed.stdout) == {
        "ok": True, "mode": mode,
        "attempts": {"database": 0, "network": 0},
    }
