"""Export output bounds and real route wiring without live DB or HTTP."""
from __future__ import annotations

from contextlib import ExitStack
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

import pytest

from backend.app.services.report_export_limits import (
    BoundedBytesIO, BoundedUtf8TextBuffer, ExportCapacityLimit,
    ExportSizeLimit, ExportWorkGate, bounded_json_bytes,
    iter_bounded_json_bytes,
)

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.parametrize(("value", "options"), [
    ([], {}),
    ({"note": "=2+2"}, {"ensure_ascii": False}),
    ({"note": "\uac00\uac01"}, {"ensure_ascii": False}),
    ({"note": "\\\n\""}, {}),
    ([{"z": 2, "a": 1}], {"sort_keys": True, "separators": (",", ":")}),
    ({"rows": [{"note": "+2"}]}, {"indent": 2, "ensure_ascii": False}),
])
def test_json_output_preserves_format_and_enforces_encoded_boundary(value, options):
    expected = json.dumps(value, **options).encode("utf-8")
    assert bounded_json_bytes(value, max_bytes=len(expected), **options) == expected
    assert b"".join(iter_bounded_json_bytes(
        value, max_bytes=len(expected), **options,
    )) == expected
    with pytest.raises(ExportSizeLimit):
        bounded_json_bytes(value, max_bytes=len(expected) - 1, **options)


@pytest.mark.parametrize("bom", [False, True])
def test_text_buffer_counts_utf8_and_bom_bytes(bom):
    output = BoundedUtf8TextBuffer(6 if bom else 3)
    if bom:
        output.write("\ufeff")
    output.write("\uac00")
    assert output.getvalue().encode("utf-8") == (
        b"\xef\xbb\xbf\xea\xb0\x80" if bom else b"\xea\xb0\x80"
    )
    with pytest.raises(ExportSizeLimit):
        output.write("x")


def test_binary_buffer_checks_high_water_position_and_zip_finalization():
    output = BoundedBytesIO(2)
    output.write(b"ab")
    output.seek(0)
    output.write(b"z")
    assert output.getvalue() == b"zb"
    output.seek(2)
    with pytest.raises(ExportSizeLimit):
        output.write(b"x")
    assert output.getvalue() == b"zb"
    with pytest.raises(ExportSizeLimit):
        output.truncate(3)
    with pytest.raises(ExportSizeLimit):
        output.writelines([b"x"])
    archive_output = BoundedBytesIO(64)
    with pytest.raises(ExportSizeLimit):
        with zipfile.ZipFile(archive_output, "w", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr("x", b"a" * 60)
    assert len(archive_output.getvalue()) <= 64


def test_work_gate_rejects_without_queue_and_releases_after_exception():
    gate = ExportWorkGate(2)
    with gate.slot():
        with gate.slot():
            with pytest.raises(ExportCapacityLimit):
                with gate.slot():
                    pytest.fail("saturated export gate admitted a third operation")
        with pytest.raises(RuntimeError, match="owned work failure"):
            with gate.slot():
                raise RuntimeError("owned work failure")
        with gate.slot():
            pass
    with ExitStack() as stack:
        stack.enter_context(gate.slot())
        stack.enter_context(gate.slot())


_ROUTE_CHILD = r"""
import csv
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
import inspect
import io
import json
import socket
import sys
from threading import Event, Lock
from time import monotonic, sleep
from types import SimpleNamespace
import uuid
import zipfile

mode = sys.argv[1]
phase = "guards"
attempts = {"database": 0, "network": 0}
def deny_database(*args, **kwargs):
    attempts["database"] += 1
    raise AssertionError("export test forbids database connections")
def deny_network(*args, **kwargs):
    attempts["network"] += 1
    raise AssertionError("export test forbids network connections")
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
    # Only the import-time engine configuration is supplied. No validated
    # deployment Settings, application startup, key or auth contract is claimed.
    config.get_settings = lambda: SimpleNamespace(
        database_url="postgresql+psycopg://walksafe_test:disabled@127.0.0.1:1/walksafe_pytest_unconfigured",
        database_connect_timeout_seconds=1,
        database_statement_timeout_ms=1000,
    )
    from fastapi import HTTPException
    from fastapi.params import Param
    from backend.app.api import reports as reports_api
    from backend.app.services import report_export as reports
    phase = "router"
    router = reports_api.create_router(
        SimpleNamespace(admin_security_enabled=False, field_test_security_enabled=False),
        object(),
    )
    endpoint = next(route.endpoint for route in router.routes if route.path == "/reports/export")
    arguments = {
        name: parameter.default.default if isinstance(parameter.default, Param) else parameter.default
        for name, parameter in inspect.signature(endpoint).parameters.items()
    }
    events = []
    audits = []
    lock = Lock()
    release = Event()
    release.set()
    entered = Event()
    class Database:
        def __init__(self):
            self.calls = 0
            self.closed = 0
        def records(self):
            return [record]
        def close_result(self):
            self.closed += 1
        def execute(self, statement):
            assert statement.get_execution_options()["yield_per"] == 1
            assert "CASE WHEN" in str(statement)
            with lock:
                self.calls += 1
                if self.calls == 2:
                    entered.set()
            if not release.wait(4):
                raise RuntimeError("owned export fixture gate timed out")
            return SimpleNamespace(
                mappings=lambda: (vars(item) for item in self.records()),
                close=self.close_result,
            )
    database = Database()
    record = SimpleNamespace(
        id=uuid.UUID("00000000-0000-4000-8000-000000000001"),
        status="pending", class_name="damaged_tactile_block", confidence=0.9,
        source="custom", latitude=37.125, longitude=127.125,
        accuracy_m=3.0, heading=90.0, bbox_x=0.1, bbox_y=0.2,
        bbox_width=0.3, bbox_height=0.4,
        captured_at=datetime(2026, 1, 1, tzinfo=UTC),
        created_at=datetime(2026, 1, 1, tzinfo=UTC),
        image_path="owned-image.wse",
        payload={"review_note": "=2+2", "model_key": "owned-model"},
    )
    reports.report_to_response = lambda value: SimpleNamespace(
        location_quality="high", review_flags=[],
    )
    def authorize(*args, **kwargs):
        with lock:
            events.append("authorize")
    def audit(*args, **kwargs):
        with lock:
            audits.append(kwargs)
    reports._reauthorize_admin_high_risk_in_transaction = authorize
    reports._persist_export_audit_original = reports._persist_export_audit
    reports._persist_export_audit = audit
    arguments.update(
        request=object(), db=database, x_walksafe_actor_id="owned-export-actor",
        format="json",
    )
    def invoke(**changes):
        return endpoint(**{**arguments, **changes})
    def require_error(code, status, **changes):
        try:
            invoke(**changes)
        except HTTPException as error:
            assert error.status_code == status
            assert error.detail["code"] == code
        else:
            raise AssertionError("export did not reject")
    phase = "operation"
    if mode.startswith("manifest-location-"):
        selected = mode.removeprefix("manifest-location-")
        location = {"lat": 37.125, "lng": 127.125, "radius_m": 456.789}
        if selected == "minimum-json":
            changes = {"format": "json", "manifest": True, "profile": "minimum"}
        elif selected == "redacted-zip":
            changes = {"format": "csv", "manifest": True, "bundle": True, "redacted": True}
        else:
            assert selected == "internal-json"
            changes = {"format": "json", "manifest": True, "profile": "internal"}
        response = invoke(**location, **changes)
        assert response.status_code == 200
        if selected == "redacted-zip":
            with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
                manifest_payload = json.loads(archive.read("manifest.json"))
        else:
            manifest_payload = json.loads(response.body)
        if selected == "minimum-json":
            assert set(manifest_payload) == {
                "schema_version", "profile", "format", "count", "rows",
            }
            assert manifest_payload["schema_version"] == "walksafe.reports.export_manifest.v1"
            assert manifest_payload["profile"] == "minimum"
            assert manifest_payload["format"] == "json"
        else:
            public_filters = manifest_payload["filters"]
            if selected == "internal-json":
                assert all(public_filters[field] == value for field, value in location.items())
                assert "location_filter_applied" not in public_filters
                assert "location_filter_type" not in public_filters
            else:
                assert all(field not in public_filters for field in location)
                assert public_filters["location_filter_applied"] is True
                assert public_filters["location_filter_type"] == "radius"
        assert len(audits) == 1 and database.calls == 1
        assert all(audits[0]["filters"][field] == value for field, value in location.items())
    elif mode.startswith("audit-order-"):
        selected = mode.removeprefix("audit-order-")
        actual_audit = reports._persist_export_audit_original
        actual_response = reports.Response
        actual_hash = reports._export_rows_sha256
        original_limit = reports.MAX_REPORT_EXPORT_BYTES
        original_archive_buffer = reports.BoundedBytesIO
        audit_id = uuid.UUID("00000000-0000-4000-8000-000000000035")
        empty_rows = selected not in {"json", "storage-failure"}
        fail_storage = selected == "storage-failure"
        fail_archive_finish = selected == "bundle-finalize"
        hash_calls = []

        class DuplicateAudit(Exception):
            diag = SimpleNamespace(constraint_name="uq_report_export_audits_audit_id")

        class AuditDatabase(Database):
            def __init__(self):
                super().__init__()
                self.pending = None
                self.committed = {}
                self.rollbacks = 0

            def records(self):
                return [] if empty_rows else super().records()

            def add(self, value):
                events.append("audit_add")
                self.pending = value

            def commit(self):
                from sqlalchemy.exc import IntegrityError
                events.append("audit_commit")
                if fail_storage:
                    raise RuntimeError("owned storage failure")
                if self.pending.audit_id in self.committed:
                    raise IntegrityError("owned duplicate", {}, DuplicateAudit())
                self.committed[self.pending.audit_id] = self.pending
                self.pending = None

            def rollback(self):
                events.append("audit_rollback")
                self.rollbacks += 1
                self.pending = None

        class FixedDatetime(datetime):
            @classmethod
            def now(cls, tz=None):
                return datetime(2026, 1, 1, tzinfo=UTC)

        class ObservedArchiveBuffer(original_archive_buffer):
            def __init__(self, maximum):
                events.append("archive_created")
                super().__init__(maximum)

            def write(self, value):
                # Exercise a real ZIP writer failure at its final directory
                # record, after both members have already been serialized.
                if fail_archive_finish and value.startswith(b"PK\x05\x06"):
                    events.append("archive_finalization_failed")
                    raise reports.ExportSizeLimit()
                return super().write(value)

        def observed_hash(rows):
            value = actual_hash(rows)
            hash_calls.append(value)
            return value

        def observed_response(*args, **kwargs):
            value = actual_response(*args, **kwargs)
            events.append("response_built")
            return value

        database = AuditDatabase()
        arguments["db"] = database
        reports.datetime = FixedDatetime
        reports._persist_export_audit = actual_audit
        reports.Response = observed_response
        reports.BoundedBytesIO = ObservedArchiveBuffer
        reports.logger.info = lambda *args, **kwargs: events.append("audit_log")
        changes = {"requested_audit_id": audit_id}
        if selected in {"csv", "csv-bom", "bundle-members", "bundle-finalize"}:
            changes["format"] = "csv"
        elif selected == "geojson":
            changes["format"] = "geojson"
        if selected in {"json-manifest", "bundle-members", "bundle-finalize"}:
            changes["manifest"] = True
        if selected in {"bundle-members", "bundle-finalize"}:
            changes["bundle"] = True
        if selected == "csv-bom":
            changes["bom"] = True

        if selected == "json":
            # Select a final-JSON-only boundary with independent JSON encoding
            # of this fixture's projected row. The expected error/audit outcome
            # below is fixed; no endpoint result is reused as its expected value.
            rows = [reports._report_export_row(record)]
            canonical = json.dumps(
                rows, ensure_ascii=False, sort_keys=True,
                separators=(",", ":"), default=str,
            ).encode("utf-8")
            final_json = json.dumps(rows, ensure_ascii=False, default=str).encode("utf-8")
            assert len(canonical) < len(final_json)
            reports.MAX_REPORT_EXPORT_BYTES = len(canonical)
        elif selected == "bundle-members":
            # Calibrate only the byte cap on the known empty-row fixture.
            # Both real members individually fit, but their sum must not fit.
            csv_size = len(reports._csv_export_text([], bom=False).encode("utf-8"))
            filters = {
                "status": None, "class_name": None, "source": None,
                "demo_filter": "all", "model_key": None, "trigger": None,
                "auto_reported": None, "performance_excluded": None,
                "created_from": None, "created_to": None, "lat": None,
                "lng": None, "radius_m": None, "redacted": False,
                "profile": "internal", "agency_review_verified": None,
                "bundle": True,
            }
            manifest_fixture = reports._export_manifest(
                [], filters=filters, export_format="csv", generated_at=FixedDatetime.now(UTC),
                actor_id="owned-export-actor", audit_id=str(audit_id),
            )
            manifest_size = len(json.dumps(
                manifest_fixture, ensure_ascii=False, default=str, indent=2,
            ).encode("utf-8"))
            reports.MAX_REPORT_EXPORT_BYTES = max(csv_size, manifest_size)
            assert 2 <= reports.MAX_REPORT_EXPORT_BYTES < csv_size + manifest_size
        elif selected not in {"bundle-finalize", "storage-failure"}:
            # Empty rows hash as [] (two bytes); every selected wrapper or CSV
            # header is larger, so this cannot fail at the canonical row hash.
            reports.MAX_REPORT_EXPORT_BYTES = 2

        reports._export_rows_sha256 = observed_hash
        events.clear()
        if selected == "storage-failure":
            require_error("export_audit_unavailable", 503, **changes)
            assert events.index("response_built") < events.index("audit_add")
            assert events.index("audit_add") < events.index("audit_commit")
            assert database.rollbacks == 1
            assert "audit_log" not in events
        else:
            require_error("report_export_too_large", 413, **changes)
            assert "response_built" not in events
            assert "audit_add" not in events and "audit_commit" not in events
            assert database.rollbacks == 0
            assert "audit_log" not in events
            if selected == "bundle-members":
                assert "archive_created" not in events
            if selected == "bundle-finalize":
                assert "archive_created" in events
                assert "archive_finalization_failed" in events
        assert hash_calls
        assert database.committed == {}

        # Reuse the exact rejected audit ID. The real durable-audit helper
        # must commit once, and a genuine subsequent reuse must still be 409.
        reports.MAX_REPORT_EXPORT_BYTES = original_limit
        fail_archive_finish = False
        fail_storage = False
        events.clear()
        response = invoke(**changes)
        assert response.status_code == 200
        assert response.headers["x-walksafe-audit-id"] == str(audit_id)
        assert len(response.body) <= original_limit
        assert list(database.committed) == [audit_id]
        assert events.index("response_built") < events.index("audit_add")
        assert events.index("audit_add") < events.index("audit_commit")
        assert events.index("audit_commit") < events.index("audit_log")
        require_error("export_audit_id_conflict", 409, **changes)
        assert list(database.committed) == [audit_id]
    elif mode == "concurrency":
        release.clear()
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(invoke) for _ in range(2)]
            try:
                assert entered.wait(3)
                require_error("report_export_capacity_exceeded", 503)
                assert database.calls == 2
                assert audits == []
            finally:
                release.set()
            assert all(future.result(timeout=3).status_code == 200 for future in futures)
        assert invoke().status_code == 200
        assert database.calls == 3 and len(audits) == 3
    elif mode == "oversize":
        previous = reports.MAX_REPORT_EXPORT_BYTES
        reports.MAX_REPORT_EXPORT_BYTES = 64
        require_error("report_export_too_large", 413)
        assert audits == []
        reports.MAX_REPORT_EXPORT_BYTES = previous
        assert invoke().status_code == 200
        assert len(audits) == 1
    elif mode.startswith("agency-limit-"):
        record.status = "reviewed"
        record.accuracy_m = 1000.0
        record.payload.update({
            "agency_review_verified": True,
            "agency_reviewed_by_actor_id": "owned-review-actor",
        })
        selected = mode.removeprefix("agency-limit-")
        if selected == "discarded":
            count = reports.MAX_REPORT_EXPORT_ROWS + 1
            quality = "low"
        elif selected == "scanned":
            reports.MAX_REPORT_AGENCY_EXPORT_SCAN_ROWS = 2
            count = 3
            quality = "low"
        else:
            assert selected == "exported"
            reports.MAX_REPORT_EXPORT_ROWS = 1
            count = 2
            quality = "high"
        reports.report_to_response = lambda value: SimpleNamespace(
            location_quality=quality, review_flags=[],
        )

        class AgencyDatabase(Database):
            def execute(self, statement):
                assert statement._limit_clause.value == reports.MAX_REPORT_AGENCY_EXPORT_SCAN_ROWS + 1
                return super().execute(statement)

            def records(self):
                return (record for _ in range(count))

        database = AgencyDatabase()
        changes = {"db": database, "profile": "agency", "format": "csv", "manifest": True, "bundle": True}
        if selected == "discarded":
            response = invoke(**changes)
            assert response.status_code == 200
            with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
                assert json.loads(archive.read("manifest.json"))["rows"] == []
            assert len(audits) == 1
        else:
            require_error("report_export_too_large", 413, **changes)
            assert audits == []
        assert database.calls == 1
    elif mode == "audit-failure":
        def failing_audit(*args, **kwargs):
            raise HTTPException(status_code=503, detail={"code": "owned_audit_failure"})
        reports._persist_export_audit = failing_audit
        require_error("owned_audit_failure", 503)
        reports._persist_export_audit = audit
        assert invoke().status_code == 200
        assert len(audits) == 1
    else:
        changes = {}
        if mode in {"csv", "csv-bom", "bundle"}:
            changes["format"] = "csv"
        elif mode == "geojson":
            changes["format"] = "geojson"
        if mode in {"json-manifest", "bundle"}:
            changes["manifest"] = True
        if mode == "bundle":
            changes["bundle"] = True
        if mode == "csv-bom":
            changes["bom"] = True
        response = invoke(**changes)
        assert response.status_code == 200
        assert len(response.body) <= reports.MAX_REPORT_EXPORT_BYTES
        assert response.headers["cache-control"] == "no-store"
        assert len(audits) == 1 and database.calls == 1
        if mode == "bundle":
            with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
                csv_body = archive.read("reports.csv")
                manifest_body = archive.read("manifest.json")
                assert len(csv_body) + len(manifest_body) <= reports.MAX_REPORT_EXPORT_BYTES
                assert json.loads(manifest_body)["rows"][0]["review_note"] == "=2+2"
                assert list(csv.DictReader(io.StringIO(csv_body.decode("utf-8"))))[0]["review_note"] == "'=2+2"
        elif mode.startswith("csv"):
            assert response.body.startswith(b"\xef\xbb\xbf") is (mode == "csv-bom")
            assert list(csv.DictReader(io.StringIO(response.body.decode("utf-8-sig"))))[0]["review_note"] == "'=2+2"
        else:
            body = json.loads(response.body)
            row = (
                body["features"][0]["properties"] if mode == "geojson"
                else body["rows"][0] if mode == "json-manifest" else body[0]
            )
            assert row["review_note"] == "=2+2"
            if mode == "geojson":
                assert body["features"][0]["geometry"]["coordinates"] == [127.125, 37.125]
    assert database.closed == database.calls
    assert attempts == {"database": 0, "network": 0}
    print(json.dumps({"ok": True, "mode": mode, "attempts": attempts}))
except BaseException as error:
    print(json.dumps({"ok": False, "mode": mode, "phase": phase,
                      "error_type": type(error).__name__, "attempts": attempts}))
    raise SystemExit(1)
"""


@pytest.mark.parametrize("mode", [
    "json", "json-manifest", "geojson", "csv", "csv-bom", "bundle",
    "oversize", "audit-failure", "concurrency",
    "agency-limit-discarded", "agency-limit-scanned", "agency-limit-exported",
    "manifest-location-minimum-json", "manifest-location-redacted-zip",
    "manifest-location-internal-json",
])
def test_actual_export_route_bounds_and_format_contracts(mode):
    completed = subprocess.run(
        [sys.executable, "-B", "-c", _ROUTE_CHILD, mode],
        cwd=ROOT,
        env={
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "PYTHONPATH": str(ROOT),
            "PYTHONDONTWRITEBYTECODE": "1",
        },
        stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=20,
    )
    receipt = json.loads(completed.stdout)
    assert completed.returncode == 0, receipt
    assert receipt == {
        "ok": True, "mode": mode, "attempts": {"database": 0, "network": 0},
    }


@pytest.mark.parametrize("mode", [
    "audit-order-json", "audit-order-json-manifest", "audit-order-geojson",
    "audit-order-csv", "audit-order-csv-bom", "audit-order-bundle-members",
    "audit-order-bundle-finalize", "audit-order-storage-failure",
])
def test_actual_export_finishes_output_checks_before_consuming_durable_audit_id(mode):
    completed = subprocess.run(
        [sys.executable, "-B", "-c", _ROUTE_CHILD, mode],
        cwd=ROOT,
        env={
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "PYTHONPATH": str(ROOT),
            "PYTHONDONTWRITEBYTECODE": "1",
        },
        stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=20,
    )
    receipt = json.loads(completed.stdout)
    assert completed.returncode == 0, receipt
    assert receipt == {
        "ok": True, "mode": mode, "attempts": {"database": 0, "network": 0},
    }
