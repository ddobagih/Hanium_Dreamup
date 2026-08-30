from __future__ import annotations

from datetime import UTC, datetime
import json
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from fastapi import FastAPI, HTTPException
from sqlalchemy.dialects import postgresql
from sqlalchemy.exc import SQLAlchemyError
from starlette.datastructures import QueryParams

from backend.app.api import admin_reports
from backend.app.field_test_security import FieldTestAccess, required_field_test_access
from backend.app.models import AdminOperationAudit
from backend.app.openapi_contract import install_walksafe_openapi_contract
from backend.app.schemas import AdminReportListPageV1, AdminReportSummaryV1
from backend.app.services.admin_device_proof import (
    VerifiedAdminDeviceProof,
    canonical_admin_query_sha256,
    is_admin_device_proof_workflow_request,
)
from backend.app.services.admin_report_projection import (
    AdminReportCursorError,
    AdminReportFilters,
    AdminReportListRow,
    admin_report_cursor_predicate,
    admin_report_filter_sha256,
    decode_admin_report_cursor,
    encode_admin_report_cursor,
    project_admin_report_summary,
)
from backend.app.services.admin_security import AdminSessionIdentity


REPORT_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
SESSION_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
CORRELATION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
CREATED_AT = datetime(2026, 8, 29, 1, 2, 3, 456789, tzinfo=UTC)


def _list_row(**overrides: object) -> AdminReportListRow:
    values: dict[str, object] = {
        "id": REPORT_ID,
        "status": "new",
        "status_version": 1,
        "class_name": "damaged_tactile_block",
        "confidence": 0.75,
        "location_quality": "high",
        "duplicate_count": 1,
        "captured_at": datetime(2026, 8, 29, 1, 0, tzinfo=UTC),
        "created_at": CREATED_AT,
        "updated_at": CREATED_AT,
    }
    values.update(overrides)
    return AdminReportListRow(**values)  # type: ignore[arg-type]


def _identity() -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id="reviewer@example.com",
        session_id=SESSION_ID,
        device_id="admin-device-0001",
        device_label="review tablet",
        expires_at=datetime(2026, 8, 29, 2, tzinfo=UTC),
        step_up_verified_at=None,
    )


def _proof(*, query: bytes = b"") -> VerifiedAdminDeviceProof:
    return VerifiedAdminDeviceProof(
        admin_id="reviewer@example.com",
        device_id="admin-device-0001",
        session_id=SESSION_ID,
        challenge_id=uuid.UUID("44444444-4444-4444-8444-444444444444"),
        correlation_id=CORRELATION_ID,
        action=None,
        purpose="ACTION",
        read_purpose="admin.report.list",
        method="GET",
        path="/admin/reports",
        body_sha256="0" * 64,
        query_sha256=canonical_admin_query_sha256(query),
        device_key_marker="1" * 64,
        device_key_version=1,
    )


def test_admin_report_summary_is_an_exact_minimum_projection() -> None:
    summary = project_admin_report_summary(_list_row())

    assert set(AdminReportSummaryV1.model_fields) == {
        "id",
        "status",
        "status_version",
        "class_name",
        "confidence",
        "location_quality",
        "duplicate_count",
        "captured_at",
        "created_at",
        "updated_at",
    }
    body = summary.model_dump(mode="json")
    assert set(body) == set(AdminReportSummaryV1.model_fields)
    assert body["location_quality"] == "high"
    assert body["duplicate_count"] == 1
    forbidden = {
        "gps", "latitude", "longitude", "bbox", "image_path", "metadata",
        "payload", "storage_name", "recovery", "privacy_subject_hmac",
    }
    assert forbidden.isdisjoint(body)
    assert "must-not-leak" not in json.dumps(body)


def test_cursor_is_canonical_and_bound_to_the_exact_filters() -> None:
    filters = AdminReportFilters(
        report_id=REPORT_ID,
        status="new",
        class_name="damaged_tactile_block",
        created_from=datetime(2026, 8, 1, tzinfo=UTC),
        created_to=datetime(2026, 9, 1, tzinfo=UTC),
    )
    digest = admin_report_filter_sha256(filters)
    cursor = encode_admin_report_cursor(
        created_at=CREATED_AT,
        report_id=REPORT_ID,
        filter_sha256=digest,
    )

    decoded = decode_admin_report_cursor(cursor, expected_filter_sha256=digest)
    assert decoded.created_at == CREATED_AT
    assert decoded.report_id == REPORT_ID
    assert encode_admin_report_cursor(
        created_at=decoded.created_at,
        report_id=decoded.report_id,
        filter_sha256=digest,
    ) == cursor

    with pytest.raises(AdminReportCursorError, match="filters"):
        decode_admin_report_cursor(cursor, expected_filter_sha256="f" * 64)
    with pytest.raises(AdminReportCursorError):
        decode_admin_report_cursor(cursor + "=", expected_filter_sha256=digest)


def test_cursor_predicate_is_descending_created_at_then_id() -> None:
    predicate = admin_report_cursor_predicate(CREATED_AT, REPORT_ID)
    sql = str(predicate.compile(dialect=postgresql.dialect()))

    assert "reports.created_at <" in sql
    assert "reports.created_at =" in sql
    assert "reports.id <" in sql


def test_admin_report_page_and_fixture_have_exact_contract() -> None:
    fixture_path = Path(__file__).parents[2] / "contracts/fixtures/admin-report-list-v1.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    parsed = AdminReportListPageV1.model_validate(fixture)

    assert set(AdminReportListPageV1.model_fields) == {
        "schema_version", "items", "next_cursor"
    }
    assert parsed.schema_version == "walksafe.admin-report-list.v1"
    assert len(parsed.items) == 1
    assert "latitude" not in fixture["items"][0]


def test_admin_report_list_is_in_the_device_proof_scope() -> None:
    assert required_field_test_access("/admin/reports", "GET") is FieldTestAccess.ADMIN
    assert is_admin_device_proof_workflow_request("GET", "/admin/reports") is True
    assert is_admin_device_proof_workflow_request("POST", "/admin/reports") is False


def test_openapi_requires_the_full_admin_read_context() -> None:
    app = FastAPI()
    app.include_router(admin_reports.create_router())
    install_walksafe_openapi_contract(
        app,
        SimpleNamespace(
            admin_security_enabled=True,
            admin_device_proof_enabled=True,
        ),
    )

    operation = app.openapi()["paths"]["/admin/reports"]["get"]
    assert set(operation["security"][0]) == {
        "WalkSafeAdminBearer",
        "WalkSafeAdminAppKind",
        "WalkSafeAdminRole",
        "WalkSafeAdminAudience",
        "WalkSafeAdminDeviceId",
        "WalkSafeAdminDeviceChallengeId",
        "WalkSafeAdminDeviceSignature",
        "WalkSafeCorrelationId",
        "WalkSafeReadPurpose",
    }
    assert operation["x-walksafe-admin-device-proof"] == {
        "purpose": "ACTION",
        "action": None,
        "read_purpose": "admin.report.list",
        "session_id": "authenticated-admin-session",
    }


def test_route_context_requires_exact_identity_proof_and_read_purpose() -> None:
    request = SimpleNamespace(
        method="GET",
        url=SimpleNamespace(path="/admin/reports"),
        state=SimpleNamespace(
            admin_security_identity=_identity(),
            admin_device_proof=_proof(),
        ),
    )
    identity, proof = admin_reports.require_admin_report_list_context(request)
    assert identity.admin_id == "reviewer@example.com"
    assert proof.correlation_id == CORRELATION_ID

    request.state.admin_device_proof = SimpleNamespace()
    with pytest.raises(HTTPException) as captured:
        admin_reports.require_admin_report_list_context(request)
    assert captured.value.status_code == 403


class _Rows:
    def __init__(self, rows: list[AdminReportListRow]) -> None:
        self._rows = rows

    def all(self) -> list[AdminReportListRow]:
        return list(self._rows)


class _FakeSession:
    def __init__(
        self,
        rows: list[AdminReportListRow],
        *,
        fail_commit: bool = False,
    ) -> None:
        self.rows = rows
        self.fail_commit = fail_commit
        self.added: list[object] = []
        self.statement: object | None = None
        self.commit_count = 0
        self.rollback_count = 0

    def execute(self, statement: object) -> _Rows:
        self.statement = statement
        return _Rows(self.rows)

    def add(self, value: object) -> None:
        self.added.append(value)

    def commit(self) -> None:
        self.commit_count += 1
        if self.fail_commit:
            raise RuntimeError("private database detail")

    def rollback(self) -> None:
        self.rollback_count += 1


class _FailingQuerySession(_FakeSession):
    def execute(self, _statement: object) -> _Rows:
        raise SQLAlchemyError("private database detail")


def _request(*, query: bytes = b"") -> SimpleNamespace:
    return SimpleNamespace(
        method="GET",
        url=SimpleNamespace(path="/admin/reports"),
        query_params=QueryParams(query.decode("ascii")),
        state=SimpleNamespace(
            admin_security_identity=_identity(),
            admin_device_proof=_proof(query=query),
        ),
    )


def test_route_returns_minimum_page_only_after_success_audit_commit() -> None:
    db = _FakeSession([_list_row()])
    response = SimpleNamespace(headers={})

    page = admin_reports.list_admin_reports(
        request=_request(),
        response=response,
        db=db,  # type: ignore[arg-type]
        limit=25,
        cursor=None,
        report_id=None,
        status=None,
        class_name=None,
        created_from=None,
        created_to=None,
    )

    assert len(page.items) == 1
    assert page.next_cursor is None
    assert db.commit_count == 1
    assert len(db.added) == 1
    audit = db.added[0]
    assert isinstance(audit, AdminOperationAudit)
    assert audit.actor_id == "reviewer@example.com"
    assert audit.session_id == SESSION_ID
    assert audit.device_id == "admin-device-0001"
    assert audit.correlation_id == CORRELATION_ID
    assert audit.operation == "admin.report.list"
    assert audit.outcome == "SUCCEEDED"
    assert audit.resource_type == "admin_report_list"
    assert audit.resource_id == "admin/reports"
    assert audit.query_sha256 == canonical_admin_query_sha256(b"")
    assert audit.result_count == 1
    assert audit.error_code is None
    assert response.headers == {"Cache-Control": "no-store", "Pragma": "no-cache"}


def test_list_query_loads_only_the_allowlisted_projection() -> None:
    db = _FakeSession([_list_row()])

    admin_reports.list_admin_reports(
        request=_request(),
        response=SimpleNamespace(headers={}),
        db=db,  # type: ignore[arg-type]
        limit=25,
        cursor=None,
        report_id=None,
        status=None,
        class_name=None,
        created_from=None,
        created_to=None,
    )

    assert db.statement is not None
    assert list(db.statement.selected_columns.keys()) == [
        "id",
        "status",
        "status_version",
        "content_revision",
        "class_name",
        "confidence",
        "location_quality",
        "duplicate_count",
        "captured_at",
        "created_at",
        "updated_at",
    ]


def test_empty_page_is_a_success_with_zero_result_audit() -> None:
    db = _FakeSession([])

    page = admin_reports.list_admin_reports(
        request=_request(),
        response=SimpleNamespace(headers={}),
        db=db,  # type: ignore[arg-type]
        limit=25,
        cursor=None,
        report_id=None,
        status=None,
        class_name=None,
        created_from=None,
        created_to=None,
    )

    assert page.items == []
    assert page.next_cursor is None
    audit = db.added[0]
    assert isinstance(audit, AdminOperationAudit)
    assert audit.outcome == "SUCCEEDED"
    assert audit.result_count == 0


def test_page_cursor_uses_the_last_returned_row_not_the_lookahead() -> None:
    older = _list_row(
        id=uuid.UUID("00000000-0000-4000-8000-000000000001"),
        created_at=datetime(2026, 8, 28, tzinfo=UTC),
        updated_at=datetime(2026, 8, 28, tzinfo=UTC),
    )
    db = _FakeSession([_list_row(), older])

    page = admin_reports.list_admin_reports(
        request=_request(),
        response=SimpleNamespace(headers={}),
        db=db,  # type: ignore[arg-type]
        limit=1,
        cursor=None,
        report_id=None,
        status=None,
        class_name=None,
        created_from=None,
        created_to=None,
    )

    digest = admin_report_filter_sha256(
        AdminReportFilters(None, None, None, None, None)
    )
    decoded = decode_admin_report_cursor(
        page.next_cursor or "",
        expected_filter_sha256=digest,
    )
    assert decoded.report_id == REPORT_ID
    assert decoded.created_at == CREATED_AT


def test_invalid_cursor_is_audited_before_sanitized_failure() -> None:
    db = _FakeSession([])

    with pytest.raises(HTTPException) as captured:
        admin_reports.list_admin_reports(
            request=_request(query=b"cursor=invalid"),
            response=SimpleNamespace(headers={}),
            db=db,  # type: ignore[arg-type]
            limit=25,
            cursor="invalid",
            report_id=None,
            status=None,
            class_name=None,
            created_from=None,
            created_to=None,
        )

    assert captured.value.status_code == 422
    assert captured.value.detail["code"] == "admin_report_cursor_invalid"
    assert db.commit_count == 1
    audit = db.added[0]
    assert isinstance(audit, AdminOperationAudit)
    assert audit.outcome == "DENIED"
    assert audit.result_count is None
    assert audit.error_code == "admin_report_cursor_invalid"


def test_noncanonical_exact_report_id_is_audited_and_rejected() -> None:
    db = _FakeSession([])

    with pytest.raises(HTTPException) as captured:
        admin_reports.list_admin_reports(
            request=_request(query=b"report_id=11111111-1111-4111-8111-111111111111"),
            response=SimpleNamespace(headers={}),
            db=db,  # type: ignore[arg-type]
            limit=25,
            cursor=None,
            report_id="11111111-1111-4111-8111-11111111111A",
            status=None,
            class_name=None,
            created_from=None,
            created_to=None,
        )

    assert captured.value.status_code == 422
    audit = db.added[0]
    assert isinstance(audit, AdminOperationAudit)
    assert audit.outcome == "DENIED"
    assert audit.error_code == "admin_report_filter_invalid"


@pytest.mark.parametrize(
    "query",
    [
        b"metadata=private_note",
        b"status=new&status=resolved",
        b"q=damaged_tactile_block",
    ],
)
def test_unknown_free_text_or_duplicate_query_is_audited_and_rejected(
    query: bytes,
) -> None:
    db = _FakeSession([])

    with pytest.raises(HTTPException) as captured:
        admin_reports.list_admin_reports(
            request=_request(query=query),
            response=SimpleNamespace(headers={}),
            db=db,  # type: ignore[arg-type]
            limit=25,
            cursor=None,
            report_id=None,
            status=None,
            class_name=None,
            created_from=None,
            created_to=None,
        )

    assert captured.value.status_code == 422
    audit = db.added[0]
    assert isinstance(audit, AdminOperationAudit)
    assert audit.outcome == "DENIED"
    assert audit.error_code == "admin_report_filter_invalid"


def test_database_failure_is_audited_and_private_details_are_withheld() -> None:
    db = _FailingQuerySession([])

    with pytest.raises(HTTPException) as captured:
        admin_reports.list_admin_reports(
            request=_request(),
            response=SimpleNamespace(headers={}),
            db=db,  # type: ignore[arg-type]
            limit=25,
            cursor=None,
            report_id=None,
            status=None,
            class_name=None,
            created_from=None,
            created_to=None,
        )

    assert captured.value.status_code == 503
    assert captured.value.detail["code"] == "admin_report_list_unavailable"
    assert "private" not in str(captured.value.detail)
    assert db.rollback_count == 1
    audit = db.added[0]
    assert isinstance(audit, AdminOperationAudit)
    assert audit.outcome == "ERROR"
    assert audit.error_code == "admin_report_list_unavailable"


def test_audit_commit_failure_withholds_the_page() -> None:
    db = _FakeSession([_list_row()], fail_commit=True)

    with pytest.raises(HTTPException) as captured:
        admin_reports.list_admin_reports(
            request=_request(),
            response=SimpleNamespace(headers={}),
            db=db,  # type: ignore[arg-type]
            limit=25,
            cursor=None,
            report_id=None,
            status=None,
            class_name=None,
            created_from=None,
            created_to=None,
        )

    assert captured.value.status_code == 503
    assert captured.value.detail["code"] == "admin_operation_audit_unavailable"
    assert "private" not in str(captured.value.detail)
    assert db.rollback_count == 1


def test_admin_operation_audit_shape_is_typed_and_not_a_json_details_bucket() -> None:
    assert set(AdminOperationAudit.__table__.columns.keys()) == {
        "id",
        "actor_id",
        "session_id",
        "device_id",
        "correlation_id",
        "operation",
        "outcome",
        "resource_type",
        "resource_id",
        "query_sha256",
        "result_count",
        "error_code",
        "created_at",
    }


def test_admin_operation_audit_migration_grants_runtime_append_only_access() -> None:
    migration_path = (
        Path(__file__).parents[1]
        / "alembic/versions/202608290004_admin_operation_audit.py"
    )
    migration = migration_path.read_text(encoding="utf-8")
    normalized = " ".join(migration.replace('"', "").split())

    assert (
        "REVOKE ALL PRIVILEGES ON TABLE public.admin_operation_audits "
        "FROM PUBLIC, walksafe_backend_runtime"
    ) in normalized
    assert (
        "GRANT SELECT, INSERT ON TABLE public.admin_operation_audits "
        "TO walksafe_backend_runtime"
    ) in normalized
    assert "GRANT UPDATE" not in migration
    assert "GRANT DELETE" not in migration
    assert "GRANT TRUNCATE" not in migration
