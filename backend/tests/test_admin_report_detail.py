from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import UTC, datetime
import json
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from fastapi import FastAPI, HTTPException
from pydantic import ValidationError
from sqlalchemy.exc import SQLAlchemyError
from starlette.datastructures import QueryParams

from backend.app.api import admin_reports
import backend.app.field_test_security as field_test_security
from backend.app.field_test_security import (
    FieldTestAccess,
    FieldTestSecurityMiddleware,
    required_field_test_access,
)
from backend.app.models import (
    AdminOperationAudit,
    Report,
    ReportInstitutionDeliveryEvent,
    ReportReviewDecision,
)
from backend.app.openapi_contract import install_walksafe_openapi_contract
from backend.app.schemas import AdminReportDetailV2
from backend.app.services.admin_device_proof import (
    VerifiedAdminDeviceProof,
    canonical_admin_query_sha256,
    is_admin_device_proof_workflow_request,
)
from backend.app.services.admin_report_projection import (
    AdminReportDeliveryRow,
    AdminReportDetailRow,
    admin_report_delivery_columns,
    admin_report_detail_columns,
    project_admin_report_detail,
)
from backend.app.services.admin_security import (
    AdminSecurityError,
    AdminSessionIdentity,
)


REPORT_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
SESSION_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
CORRELATION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
REVIEW_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")
PACKAGE_ID = uuid.UUID("88888888-8888-4888-8888-888888888888")
EXPORT_AUDIT_ID = uuid.UUID("99999999-9999-4999-8999-999999999999")
DETAIL_PATH = f"/admin/reports/{REPORT_ID}"
OBSERVED_AT = datetime(2026, 8, 29, 1, 3, tzinfo=UTC)


def _report() -> Report:
    report = Report(
        id=REPORT_ID,
        status="reviewed",
        status_version=1,
        content_revision=3,
        class_id=0,
        class_name="damaged_tactile_block",
        confidence=0.75,
        bbox_x=0.1,
        bbox_y=0.2,
        bbox_width=0.3,
        bbox_height=0.4,
        captured_at=datetime(2026, 8, 29, 1, 0, tzinfo=UTC),
        source="android",
        latitude=37.5665,
        longitude=126.978,
        accuracy_m=4.0,
        heading=10.0,
        image_path="/private/report-original.wse",
        image_content_type="image/jpeg",
        payload={"private_note": "must-not-leak", "account": "private-user"},
        privacy_subject_hmac="a" * 64,
        account_generation=7,
        created_at=datetime(2026, 8, 29, 1, 1, tzinfo=UTC),
        updated_at=datetime(2026, 8, 29, 1, 2, tzinfo=UTC),
    )
    report.latest_delivery_revision = 0
    return report


def _review() -> ReportReviewDecision:
    return ReportReviewDecision(
        id=REVIEW_ID,
        report_id=REPORT_ID,
        revision=2,
        content_revision=3,
        decision="APPROVED",
        reason="private reviewer narrative",
        duplicate_of_report_id=None,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
        admin_id="reviewer@example.com",
        session_id=SESSION_ID,
        device_id="admin-device-0001",
        correlation_id=CORRELATION_ID,
        decided_at=OBSERVED_AT,
        created_at=OBSERVED_AT,
    )


def _delivery() -> ReportInstitutionDeliveryEvent:
    delivery = ReportInstitutionDeliveryEvent(
        id=uuid.UUID("55555555-5555-4555-8555-555555555555"),
        report_id=REPORT_ID,
        review_decision_id=REVIEW_ID,
        package_id=PACKAGE_ID,
        revision=3,
        package_revision=1,
        institution="private institution detail",
        channel="private-channel",
        recipient="private recipient",
        status="ACKNOWLEDGED",
        external_receipt_id="private-receipt",
        reason="private delivery narrative",
        evidence_sha256="b" * 64,
        observed_at=OBSERVED_AT,
        expected_revision=2,
        idempotency_key=uuid.UUID("66666666-6666-4666-8666-666666666666"),
        admin_id="reviewer@example.com",
        session_id=SESSION_ID,
        device_id="admin-device-0001",
        correlation_id=CORRELATION_ID,
        recorded_at=datetime(2026, 8, 29, 1, 4, tzinfo=UTC),
    )
    delivery.package_content_revision = 3
    delivery.package_schema_version = "walksafe.admin-report-delivery-package.v2"
    delivery.package_version = 2
    delivery.export_audit_id = EXPORT_AUDIT_ID
    delivery.package_sha256 = "c" * 64
    delivery.csv_sha256 = "d" * 64
    delivery.manifest_sha256 = "e" * 64
    delivery.package_byte_count = 4096
    return delivery


def _identity() -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id="reviewer@example.com",
        session_id=SESSION_ID,
        device_id="admin-device-0001",
        device_label="review tablet",
        expires_at=datetime(2026, 8, 29, 2, tzinfo=UTC),
        step_up_verified_at=None,
    )


def _proof(*, path: str = DETAIL_PATH, query: bytes = b"") -> VerifiedAdminDeviceProof:
    return VerifiedAdminDeviceProof(
        admin_id="reviewer@example.com",
        device_id="admin-device-0001",
        session_id=SESSION_ID,
        challenge_id=uuid.UUID("77777777-7777-4777-8777-777777777777"),
        correlation_id=CORRELATION_ID,
        action=None,
        purpose="ACTION",
        read_purpose="admin.report.detail",
        method="GET",
        path=path,
        body_sha256="0" * 64,
        query_sha256=canonical_admin_query_sha256(query),
        device_key_marker="1" * 64,
        device_key_version=1,
    )


def _request(*, path: str = DETAIL_PATH, query: bytes = b"") -> SimpleNamespace:
    return SimpleNamespace(
        method="GET",
        url=SimpleNamespace(path=path),
        query_params=QueryParams(query.decode("ascii")),
        state=SimpleNamespace(
            admin_security_identity=_identity(),
            admin_device_proof=_proof(path=path, query=query),
        ),
    )


def test_detail_projection_is_strict_minimum_and_uses_capability_links() -> None:
    detail = project_admin_report_detail(_report(), _review(), _delivery())
    body = detail.model_dump(mode="json")

    assert set(AdminReportDetailV2.model_fields) == {
        "schema_version",
        "id",
        "status",
        "status_version",
        "content_revision",
        "latest_delivery_revision",
        "allowed_next_statuses",
        "class_name",
        "confidence",
        "location_quality",
        "captured_at",
        "created_at",
        "updated_at",
        "current_review",
        "current_delivery",
        "capabilities",
    }
    assert body["content_revision"] == 3
    assert body["latest_delivery_revision"] == 3
    assert body["current_review"] == {
        "revision": 2,
        "decision": "APPROVED",
        "user_visible_reason": None,
        "duplicate_of_report_id": None,
        "location_reviewed": True,
        "photo_reviewed": True,
        "privacy_reviewed": True,
        "decided_at": "2026-08-29T01:03:00Z",
    }
    assert body["current_delivery"] == {
        "revision": 3,
        "package_id": str(PACKAGE_ID),
        "package_revision": 1,
        "package_content_revision": 3,
        "package_schema_version": "walksafe.admin-report-delivery-package.v2",
        "package_version": 2,
        "export_audit_id": str(EXPORT_AUDIT_ID),
        "package_sha256": "c" * 64,
        "csv_sha256": "d" * 64,
        "manifest_sha256": "e" * 64,
        "package_byte_count": 4096,
        "status": "ACKNOWLEDGED",
        "external_receipt_present": True,
        "evidence_present": True,
        "observed_at": "2026-08-29T01:03:00Z",
        "recorded_at": "2026-08-29T01:04:00Z",
    }
    assert body["capabilities"] == {
        "review_decisions_path": f"/reports/{REPORT_ID}/review-decisions",
        "deliveries_path": f"/reports/{REPORT_ID}/deliveries",
        "original_access_grants_path": f"/reports/{REPORT_ID}/original-access-grants",
        "status_path": f"/admin/reports/{REPORT_ID}/status",
        "delivery_packages_path": f"/admin/reports/{REPORT_ID}/delivery-packages",
    }
    forbidden = {
        "gps", "latitude", "longitude", "bbox", "image_path", "metadata",
        "payload", "storage_name", "privacy_subject_hmac", "account_generation",
        "reason", "admin_id", "session_id", "device_id", "correlation_id",
        "institution", "channel", "recipient", "external_receipt_id", "evidence_sha256",
    }
    serialized = json.dumps(body)
    assert forbidden.isdisjoint(body)
    assert "must-not-leak" not in serialized
    assert "private" not in serialized


def test_detail_query_selects_every_required_projection_field() -> None:
    assert tuple(column.key for column in admin_report_detail_columns()) == tuple(
        AdminReportDetailRow.__dataclass_fields__
    )
    assert tuple(column.key for column in admin_report_delivery_columns()) == tuple(
        AdminReportDeliveryRow.__dataclass_fields__
    )


def test_detail_fixture_has_the_exact_contract() -> None:
    fixture_path = Path(__file__).parents[2] / "contracts/fixtures/admin-report-detail-v2.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    parsed = AdminReportDetailV2.model_validate(fixture)

    assert set(fixture) == {
        "schema_version",
        "id",
        "status",
        "status_version",
        "content_revision",
        "latest_delivery_revision",
        "allowed_next_statuses",
        "class_name",
        "confidence",
        "location_quality",
        "captured_at",
        "created_at",
        "updated_at",
        "current_review",
        "current_delivery",
        "capabilities",
    }
    assert parsed.id == REPORT_ID
    assert parsed.schema_version == "walksafe.admin-report-detail.v2"
    assert parsed.content_revision == 0
    assert parsed.latest_delivery_revision == 3
    assert "latitude" not in fixture
    assert "metadata" not in fixture
    assert set(fixture["current_review"]) == {
        "revision",
        "decision",
        "user_visible_reason",
        "duplicate_of_report_id",
        "location_reviewed",
        "photo_reviewed",
        "privacy_reviewed",
        "decided_at",
    }
    assert set(fixture["capabilities"]) == {
        "review_decisions_path",
        "deliveries_path",
        "original_access_grants_path",
        "status_path",
        "delivery_packages_path",
    }


@pytest.mark.parametrize(
    "missing_key",
    [
        "status_version",
        "allowed_next_statuses",
    ],
)
def test_detail_v2_rejects_missing_required_top_level_keys(missing_key: str) -> None:
    fixture_path = Path(__file__).parents[2] / "contracts/fixtures/admin-report-detail-v2.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    fixture.pop(missing_key)

    with pytest.raises(ValidationError):
        AdminReportDetailV2.model_validate(fixture)


@pytest.mark.parametrize(
    ("container_key", "missing_key"),
    [
        ("current_review", "user_visible_reason"),
        ("capabilities", "status_path"),
        ("capabilities", "delivery_packages_path"),
    ],
)
def test_detail_v2_rejects_missing_required_nested_keys(
    container_key: str,
    missing_key: str,
) -> None:
    fixture_path = Path(__file__).parents[2] / "contracts/fixtures/admin-report-detail-v2.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    fixture[container_key].pop(missing_key)

    with pytest.raises(ValidationError):
        AdminReportDetailV2.model_validate(fixture)


def test_detail_route_has_admin_proof_and_openapi_contract() -> None:
    proof_path = f"/admin/reports/{REPORT_ID}/delivery-packages/7/proof"

    assert required_field_test_access(DETAIL_PATH, "GET") is FieldTestAccess.ADMIN
    assert is_admin_device_proof_workflow_request("GET", DETAIL_PATH) is True
    assert is_admin_device_proof_workflow_request("POST", DETAIL_PATH) is False
    assert is_admin_device_proof_workflow_request("GET", proof_path) is True
    assert is_admin_device_proof_workflow_request(
        "GET", "/admin/reports/11111111-1111-4111-8111-11111111111A"
    ) is True

    app = FastAPI()
    app.include_router(admin_reports.create_router())
    install_walksafe_openapi_contract(
        app,
        SimpleNamespace(admin_security_enabled=True, admin_device_proof_enabled=True),
    )
    operation = app.openapi()["paths"]["/admin/reports/{report_id}"]["get"]
    proof_operation = app.openapi()["paths"][
        "/admin/reports/{report_id}/delivery-packages/{package_revision}/proof"
    ]["get"]
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
    assert operation["x-walksafe-admin-device-proof"]["read_purpose"] == "admin.report.detail"
    assert proof_operation["x-walksafe-admin-device-proof"]["read_purpose"] == (
        "admin.report.delivery_package.proof"
    )


def test_verified_session_proof_failure_is_security_audited_without_header_correlation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    denials: list[dict[str, object]] = []

    def reject_proof(*_args: object, **_kwargs: object) -> None:
        raise AdminSecurityError(
            "admin_device_proof_invalid",
            "invalid proof",
            status_code=403,
        )

    monkeypatch.setattr(
        field_test_security,
        "record_admin_security_denial",
        lambda **kwargs: denials.append(kwargs),
    )
    middleware = FieldTestSecurityMiddleware(
        lambda _scope, _receive, _send: None,
        SimpleNamespace(
            admin_security_enabled=True,
            admin_device_proof_enabled=True,
            field_test_security_enabled=True,
            max_upload_bytes=1024,
            max_report_metadata_bytes=1024,
        ),
        admin_session_authorizer=lambda *_args, **_kwargs: _identity(),
        admin_device_proof_verifier=reject_proof,
    )
    messages: list[dict[str, object]] = []

    async def receive() -> dict[str, object]:
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message: dict[str, object]) -> None:
        messages.append(message)

    asyncio.run(
        middleware(
            {
                "type": "http",
                "method": "GET",
                "path": DETAIL_PATH,
                "query_string": b"",
                "headers": [
                    (b"x-walksafe-device-challenge-id", b"challenge"),
                    (b"x-walksafe-device-signature", b"signature"),
                    (
                        b"x-walksafe-correlation-id",
                        b"99999999-9999-4999-8999-999999999999",
                    ),
                    (b"x-walksafe-read-purpose", b"admin.report.detail"),
                ],
            },
            receive,
            send,
        )
    )

    assert messages[0]["status"] == 403
    assert denials == [
        {
            "action": "admin.device_proof.verify",
            "reason": "admin_device_proof_invalid",
            "method": "GET",
            "path": DETAIL_PATH,
            "device_id": "admin-device-0001",
        }
    ]


@pytest.mark.parametrize(
    "proof",
    [
        replace(_proof(), read_purpose="admin.report.list"),
        replace(_proof(), method="POST"),
        replace(_proof(), path="/admin/reports"),
    ],
)
def test_detail_context_rejects_wrong_purpose_method_or_path(
    proof: VerifiedAdminDeviceProof,
) -> None:
    request = _request()
    request.state.admin_device_proof = proof

    with pytest.raises(HTTPException) as captured:
        admin_reports.require_admin_report_detail_context(request)
    assert captured.value.status_code == 403


class _FakeSession:
    def __init__(
        self,
        report: Report | None,
        review: ReportReviewDecision | None = None,
        delivery: ReportInstitutionDeliveryEvent | None = None,
        *,
        fail_query: bool = False,
        fail_commit: bool = False,
    ) -> None:
        self.execute_results = [report, review, delivery]
        self.fail_query = fail_query
        self.fail_commit = fail_commit
        self.added: list[object] = []
        self.commit_count = 0
        self.rollback_count = 0

    def execute(self, _statement: object) -> object:
        if self.fail_query:
            raise SQLAlchemyError("private database detail")
        value = self.execute_results.pop(0)
        return SimpleNamespace(one_or_none=lambda: value)

    def add(self, value: object) -> None:
        self.added.append(value)

    def commit(self) -> None:
        self.commit_count += 1
        if self.fail_commit:
            raise RuntimeError("private audit detail")

    def rollback(self) -> None:
        self.rollback_count += 1


def _call_detail(db: _FakeSession, *, path: str = DETAIL_PATH, query: bytes = b""):
    return admin_reports.get_admin_report_detail(
        report_id=path.rsplit("/", 1)[-1],
        request=_request(path=path, query=query),
        response=SimpleNamespace(headers={}),
        db=db,  # type: ignore[arg-type]
    )


def test_detail_success_is_audited_before_response() -> None:
    report = _report()
    report.latest_delivery_revision = 3
    db = _FakeSession(report, _review(), _delivery())

    detail = _call_detail(db)

    assert detail.id == REPORT_ID
    assert db.commit_count == 1
    audit = db.added[0]
    assert isinstance(audit, AdminOperationAudit)
    assert audit.operation == "admin.report.detail"
    assert audit.outcome == "SUCCEEDED"
    assert audit.resource_type == "admin_report_detail"
    assert audit.resource_id == str(REPORT_ID)
    assert audit.query_sha256 == canonical_admin_query_sha256(b"")
    assert audit.result_count == 1
    assert audit.error_code is None


def test_detail_without_review_or_delivery_returns_null_summaries() -> None:
    report = _report()
    report.latest_delivery_revision = 0
    detail = _call_detail(_FakeSession(report))

    assert detail.current_review is None
    assert detail.current_delivery is None


def test_detail_keeps_latest_delivery_revision_when_current_content_has_no_delivery() -> None:
    report = _report()
    report.latest_delivery_revision = 4

    detail = _call_detail(_FakeSession(report, None, None))

    assert detail.latest_delivery_revision == 4
    assert detail.current_delivery is None


def test_missing_detail_is_audited_as_404() -> None:
    db = _FakeSession(None)

    with pytest.raises(HTTPException) as captured:
        _call_detail(db)

    assert captured.value.status_code == 404
    assert captured.value.detail["code"] == "admin_report_not_found"
    audit = db.added[0]
    assert isinstance(audit, AdminOperationAudit)
    assert audit.outcome == "DENIED"
    assert audit.resource_id == str(REPORT_ID)
    assert audit.error_code == "admin_report_not_found"


@pytest.mark.parametrize(
    ("path", "query", "expected_code"),
    [
        ("/admin/reports/11111111-1111-4111-8111-11111111111A", b"", "admin_report_id_invalid"),
        (DETAIL_PATH, b"view=full", "admin_report_query_invalid"),
    ],
)
def test_noncanonical_id_or_any_query_is_audited_and_rejected(
    path: str,
    query: bytes,
    expected_code: str,
) -> None:
    db = _FakeSession(_report())

    with pytest.raises(HTTPException) as captured:
        _call_detail(db, path=path, query=query)

    assert captured.value.status_code == 422
    audit = db.added[0]
    assert isinstance(audit, AdminOperationAudit)
    assert audit.outcome == "DENIED"
    assert audit.error_code == expected_code


def test_pre_route_validation_boundary_uses_only_verified_proof_binding() -> None:
    invalid_path = "/admin/reports/11111111-1111-4111-8111-11111111111A"
    db = _FakeSession(None)

    admin_reports.persist_admin_report_request_validation_audit(
        _request(path=invalid_path),
        db,  # type: ignore[arg-type]
    )

    audit = db.added[0]
    assert isinstance(audit, AdminOperationAudit)
    assert audit.correlation_id == CORRELATION_ID
    assert audit.resource_id == "invalid-report-id"
    assert audit.outcome == "DENIED"
    assert audit.error_code == "admin_report_id_invalid"


def test_detail_database_failure_is_audited_and_sanitized() -> None:
    db = _FakeSession(_report(), fail_query=True)

    with pytest.raises(HTTPException) as captured:
        _call_detail(db)

    assert captured.value.status_code == 503
    assert captured.value.detail["code"] == "admin_report_detail_unavailable"
    assert "private" not in str(captured.value.detail)
    assert db.rollback_count == 1
    audit = db.added[0]
    assert isinstance(audit, AdminOperationAudit)
    assert audit.outcome == "ERROR"
    assert audit.error_code == "admin_report_detail_unavailable"


def test_detail_audit_commit_failure_withholds_the_response() -> None:
    db = _FakeSession(_report(), _review(), _delivery(), fail_commit=True)

    with pytest.raises(HTTPException) as captured:
        _call_detail(db)

    assert captured.value.status_code == 503
    assert captured.value.detail["code"] == "admin_operation_audit_unavailable"
    assert db.rollback_count == 1
