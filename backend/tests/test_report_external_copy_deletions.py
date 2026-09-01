from __future__ import annotations

from datetime import UTC, datetime, timedelta
import json
from pathlib import Path
import uuid

import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from starlette.requests import Request

from backend.app.api import report_user_requests as api
from backend.app.field_test_security import (
    FieldTestAccess,
    required_field_test_access,
    requires_actor_identity,
)
from backend.app.main import app
from backend.app.models import (
    ReportDeletionExternalCopyEvent,
    ReportDeletionExternalCopyState,
    ReportDeletionTombstone,
)
from backend.app.openapi_contract import _DEVICE_PROOF_WORKFLOW_PATHS
from backend.app.schemas import (
    AdminReportDeletionExternalCopyEventCreateV1,
    ReportDeletionStatusV2,
)
from backend.app.services.admin_device_proof import (
    VerifiedAdminDeviceProof,
    is_admin_device_proof_workflow_request,
)
from backend.app.services.admin_security import (
    AdminSessionIdentity,
    classify_admin_operation,
)
from backend.app.services.report_external_copy_deletions import (
    ADMIN_EXTERNAL_COPY_LIST_OPERATION,
    ADMIN_EXTERNAL_COPY_RECORD_OPERATION,
    _latest_dict,
    admin_external_copy_filter_digest,
    allowed_external_copy_deletion_states,
)


REQUEST_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
COPY_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
SESSION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
CHALLENGE_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")
CORRELATION_ID = uuid.UUID("55555555-5555-4555-8555-555555555555")
NOW = datetime(2026, 9, 1, 1, 0, tzinfo=UTC)


def _request(path: str, *, method: str, action: str | None) -> Request:
    identity = AdminSessionIdentity(
        admin_id="admin@example.com",
        session_id=SESSION_ID,
        device_id="device-0001",
        device_label="관리자 기기",
        expires_at=NOW + timedelta(hours=1),
        step_up_verified_at=None,
    )
    proof = VerifiedAdminDeviceProof(
        admin_id=identity.admin_id,
        device_id=identity.device_id,
        session_id=identity.session_id,
        challenge_id=CHALLENGE_ID,
        correlation_id=CORRELATION_ID,
        action=action,
        purpose="ACTION",
        read_purpose=(
            ADMIN_EXTERNAL_COPY_LIST_OPERATION if method == "GET" else None
        ),
        method=method,
        path=path,
        body_sha256="a" * 64,
        query_sha256="b" * 64,
        device_key_marker="c" * 64,
        device_key_version=1,
    )
    return Request(
        {
            "type": "http",
            "method": method,
            "scheme": "https",
            "path": path,
            "raw_path": path.encode("ascii"),
            "query_string": b"",
            "headers": [],
            "state": {
                "admin_security_identity": identity,
                "admin_device_proof": proof,
            },
        }
    )


def test_event_model_is_minimum_append_only_fact_shape() -> None:
    assert set(ReportDeletionExternalCopyEvent.__table__.columns.keys()) == {
        "id",
        "external_copy_state_id",
        "revision",
        "expected_revision",
        "idempotency_key",
        "state",
        "institution_reference",
        "evidence_sha256",
        "observed_at",
        "admin_id",
        "session_id",
        "device_id",
        "correlation_id",
        "recorded_at",
    }
    assert set(ReportDeletionExternalCopyEvent.__table__.columns.keys()).isdisjoint(
        {"recipient", "reply_body", "report_content", "latitude", "longitude"}
    )


def test_external_copy_state_machine_is_manual_and_terminal() -> None:
    assert allowed_external_copy_deletion_states("NOT_REQUESTED") == (
        "REQUEST_SENT",
    )
    assert allowed_external_copy_deletion_states("REQUEST_SENT") == (
        "REPLY_ACKNOWLEDGED",
        "REPLY_DELETION_CONFIRMED",
        "REPLY_DECLINED",
    )
    assert allowed_external_copy_deletion_states("REPLY_ACKNOWLEDGED") == (
        "REPLY_DELETION_CONFIRMED",
        "REPLY_DECLINED",
    )
    assert allowed_external_copy_deletion_states("REPLY_DECLINED") == (
        "REQUEST_SENT",
    )
    assert allowed_external_copy_deletion_states("REPLY_DELETION_CONFIRMED") == ()


def test_event_schema_requires_reply_proof_and_confirmed_evidence() -> None:
    base = {
        "expected_revision": 1,
        "idempotency_key": str(uuid.uuid4()),
        "observed_at": "2026-09-01T00:00:00Z",
    }
    with pytest.raises(ValidationError):
        AdminReportDeletionExternalCopyEventCreateV1(
            state="REPLY_ACKNOWLEDGED",
            **base,
        )
    with pytest.raises(ValidationError):
        AdminReportDeletionExternalCopyEventCreateV1(
            state="REPLY_DELETION_CONFIRMED",
            institution_reference="case:12345678",
            **base,
        )
    confirmed = AdminReportDeletionExternalCopyEventCreateV1(
        state="REPLY_DELETION_CONFIRMED",
        institution_reference="case:12345678",
        evidence_sha256="d" * 64,
        **base,
    )
    assert confirmed.evidence_sha256 == "d" * 64
    with pytest.raises(ValidationError):
        AdminReportDeletionExternalCopyEventCreateV1(
            state="REQUEST_SENT",
            unexpected="value",
            **base,
        )


def test_user_v2_contract_is_minimum_and_count_bound() -> None:
    status = ReportDeletionStatusV2(
        schema_version="walksafe.report-deletion-status.v2",
        request_id=REQUEST_ID,
        report_id=uuid.uuid4(),
        state="DELETED",
        request_status_version=2,
        external_copy_count=1,
        external_copies=[
            {
                "institution": "서울시",
                "state": "NOT_REQUESTED",
                "status_recorded_at": None,
            }
        ],
        updated_at=NOW,
    )
    assert set(status.external_copies[0].model_dump()) == {
        "institution",
        "state",
        "status_recorded_at",
    }
    with pytest.raises(ValidationError):
        ReportDeletionStatusV2.model_validate(
            {**status.model_dump(), "external_copy_count": 2}
        )
    with pytest.raises(ValidationError):
        ReportDeletionStatusV2.model_validate(
            {**status.model_dump(), "state": "PENDING"}
        )


def test_admin_routes_are_standard_record_only_device_proof_workflows() -> None:
    list_path = "/admin/report-deletions/external-copies"
    event_path = (
        f"/admin/report-deletions/{REQUEST_ID}/external-copies/{COPY_ID}/events"
    )
    assert required_field_test_access(list_path, "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access(event_path, "POST") is FieldTestAccess.ADMIN
    assert requires_actor_identity(list_path, "GET")
    assert is_admin_device_proof_workflow_request("GET", list_path)
    assert is_admin_device_proof_workflow_request("POST", event_path)
    operation = classify_admin_operation("POST", event_path)
    assert operation is not None
    assert (operation.action, operation.risk) == (
        ADMIN_EXTERNAL_COPY_RECORD_OPERATION,
        "STANDARD",
    )
    assert _DEVICE_PROOF_WORKFLOW_PATHS[
        "/admin/report-deletions/external-copies"
    ]["get"] == (None, ADMIN_EXTERNAL_COPY_LIST_OPERATION)
    assert _DEVICE_PROOF_WORKFLOW_PATHS[
        "/admin/report-deletions/{request_id}/external-copies/{copy_id}/events"
    ]["post"] == (ADMIN_EXTERNAL_COPY_RECORD_OPERATION, None)


def test_record_context_reuses_manual_delivery_assurance_without_step_up() -> None:
    path = f"/admin/report-deletions/{REQUEST_ID}/external-copies/{COPY_ID}/events"
    identity, proof = api._admin_record_context(
        _request(path, method="POST", action=ADMIN_EXTERNAL_COPY_RECORD_OPERATION),
        operation=ADMIN_EXTERNAL_COPY_RECORD_OPERATION,
    )
    assert identity.step_up_verified_at is None
    assert proof.action == ADMIN_EXTERNAL_COPY_RECORD_OPERATION
    with pytest.raises(HTTPException):
        api._admin_record_context(
            _request(path, method="POST", action="report.delivery.create"),
            operation=ADMIN_EXTERNAL_COPY_RECORD_OPERATION,
        )


def test_cursor_is_bound_to_request_filter() -> None:
    assert admin_external_copy_filter_digest(REQUEST_ID) == (
        admin_external_copy_filter_digest(REQUEST_ID)
    )


def test_conflict_latest_payload_is_json_safe_rfc3339_utc() -> None:
    tombstone = ReportDeletionTombstone(
        id=uuid.uuid4(),
        request_id=REQUEST_ID,
        report_id=uuid.uuid4(),
        privacy_subject_hmac="a" * 64,
        account_generation=1,
        request_status_version=2,
        external_copy_count=1,
        deleted_at=NOW,
    )
    copy = ReportDeletionExternalCopyState(
        id=COPY_ID,
        deletion_tombstone_id=tombstone.id,
        source_delivery_event_id=uuid.uuid4(),
        package_id=None,
        institution="서울시",
        status="RESOLVED",
        observed_at=NOW,
        recorded_at=NOW,
    )
    event = ReportDeletionExternalCopyEvent(
        id=uuid.uuid4(),
        external_copy_state_id=COPY_ID,
        revision=1,
        expected_revision=0,
        idempotency_key=uuid.uuid4(),
        state="REQUEST_SENT",
        institution_reference=None,
        evidence_sha256=None,
        observed_at=NOW,
        admin_id="admin@example.com",
        session_id=SESSION_ID,
        device_id="device-0001",
        correlation_id=CORRELATION_ID,
        recorded_at=NOW,
    )

    latest = _latest_dict(tombstone=tombstone, copy=copy, event=event)

    assert json.loads(json.dumps(latest))["status_recorded_at"] == (
        "2026-09-01T01:00:00Z"
    )
    assert admin_external_copy_filter_digest(REQUEST_ID) != (
        admin_external_copy_filter_digest(uuid.uuid4())
    )


def test_openapi_exposes_v2_user_status_and_admin_routes() -> None:
    schema = app.openapi()
    user_response = schema["paths"][
        "/reports/mine/deletions/{request_id}"
    ]["get"]["responses"]["200"]["content"]["application/json"]["schema"]
    assert user_response["$ref"].endswith("/ReportDeletionStatusV2")
    list_operation = schema["paths"][
        "/admin/report-deletions/external-copies"
    ]["get"]
    assert list_operation["responses"]["200"]["content"]["application/json"][
        "schema"
    ]["$ref"].endswith("/AdminReportDeletionExternalCopyListPageV1")
    event_operation = schema["paths"][
        "/admin/report-deletions/{request_id}/external-copies/{copy_id}/events"
    ]["post"]
    assert event_operation["responses"]["201"]["content"]["application/json"][
        "schema"
    ]["$ref"].endswith("/AdminReportDeletionExternalCopyEventV1")
    assert event_operation["responses"]["200"]["content"]["application/json"][
        "schema"
    ]["$ref"].endswith("/AdminReportDeletionExternalCopyEventV1")


def test_migration_is_single_successor_with_no_worker_or_external_io() -> None:
    source = Path(
        "backend/alembic/versions/"
        "202608300006_report_external_copy_deletion_events.py"
    ).read_text(encoding="utf-8")
    service = Path(
        "backend/app/services/report_external_copy_deletions.py"
    ).read_text(encoding="utf-8")
    assert 'revision = "202608300006"' in source
    assert 'down_revision = "202608300005"' in source
    assert "walksafe_reject_audit_mutation" in source
    assert "GRANT SELECT, INSERT ON TABLE" in source
    assert "NEW.recorded_at := pg_catalog.clock_timestamp()" in source
    assert 'f"FROM PUBLIC, {_RUNTIME_ROLE}, {_DELETION_WORKER_ROLE}"' in source
    assert "GRANT" not in "\n".join(
        line for line in source.splitlines() if "walksafe_report_deletion_worker TO" in line
    )
    for forbidden in ("httpx", "requests.", "smtp", "scheduler", "worker"):
        assert forbidden not in service.lower()
    assert ".with_for_update" not in service
    assert "pg_advisory_xact_lock" in service
