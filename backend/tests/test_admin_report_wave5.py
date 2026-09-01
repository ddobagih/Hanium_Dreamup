from __future__ import annotations

from datetime import UTC, datetime
import hashlib
import json
from types import SimpleNamespace
import uuid

import pytest
from pydantic import ValidationError

from backend.app.api import admin_reports
from backend.app.schemas import AdminReportPackageCreateRequest, AdminReportStatusUpdateV1
from backend.app.models import (
    AdminOperationAudit,
    Report,
    ReportDeliveryPackage,
    ReportExportAudit,
    ReportReviewDecision,
    ReportStatusAudit,
)
from backend.app.services.admin_audit_projection import (
    AdminAuditCursorError,
    AdminAuditEventRow,
    admin_audit_filter_sha256,
    decode_admin_audit_cursor,
    encode_admin_audit_cursor,
    project_admin_audit_event,
)
from backend.app.services.admin_report_delivery_package import (
    DELIVERY_PACKAGE_SCHEMA_VERSION,
    build_admin_report_delivery_package,
    create_admin_report_delivery_package,
    get_admin_report_delivery_package_proof,
)
from backend.app.services.admin_report_workflow import (
    AdminReportWorkflowError,
    allowed_next_statuses,
    update_admin_report_status,
)
from backend.app.services.admin_security import AdminSessionIdentity


REPORT_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
REVIEW_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
EXPORT_AUDIT_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
CORRELATION_ID = uuid.UUID("55555555-5555-4555-8555-555555555555")
OCCURRED_AT = datetime(2026, 8, 29, 1, 2, 3, 456789, tzinfo=UTC)
SESSION_ID = uuid.UUID("66666666-6666-4666-8666-666666666666")


def test_status_contract_requires_integer_expected_version_and_explicit_transitions() -> None:
    with pytest.raises(ValidationError):
        AdminReportStatusUpdateV1.model_validate({"status": "reviewed"})
    with pytest.raises(ValidationError):
        AdminReportStatusUpdateV1.model_validate(
            {"status": "reviewed", "expected_version": "1"}
        )

    assert allowed_next_statuses("new") == ("reviewed", "resolved")
    assert allowed_next_statuses("reviewed") == ("new", "resolved")
    assert allowed_next_statuses("resolved") == ("reviewed",)


class _ScalarResult:
    def __init__(self, value: object) -> None:
        self.value = value

    def scalar_one_or_none(self) -> object:
        return self.value


class _StatusSession:
    def __init__(self, report: Report) -> None:
        self.report = report
        self.statement: object | None = None
        self.added: list[object] = []
        self.commit_count = 0
        self.rollback_count = 0

    def execute(self, statement: object) -> _ScalarResult:
        self.statement = statement
        return _ScalarResult(self.report)

    def add(self, value: object) -> None:
        self.added.append(value)

    def commit(self) -> None:
        self.commit_count += 1

    def rollback(self) -> None:
        self.rollback_count += 1

    def refresh(self, _value: object) -> None:
        return None


def _identity() -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id="reviewer@example.com",
        session_id=SESSION_ID,
        device_id="admin-device-0001",
        device_label="review tablet",
        expires_at=datetime(2026, 8, 29, 2, tzinfo=UTC),
        step_up_verified_at=datetime(2026, 8, 29, 1, tzinfo=UTC),
    )


def test_status_update_is_row_locked_cas_and_audited_atomically() -> None:
    report = Report(
        id=REPORT_ID,
        status="new",
        status_version=3,
        updated_at=OCCURRED_AT,
    )
    stale_db = _StatusSession(report)
    with pytest.raises(AdminReportWorkflowError) as captured:
        update_admin_report_status(
            stale_db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            payload=AdminReportStatusUpdateV1(
                status="reviewed", expected_version=2
            ),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
            query_sha256="0" * 64,
        )
    assert captured.value.status_code == 409
    assert captured.value.latest_status["status_version"] == 3
    assert stale_db.rollback_count == 1
    assert stale_db.commit_count == 0

    db = _StatusSession(report)
    updated = update_admin_report_status(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        payload=AdminReportStatusUpdateV1(
            status="reviewed", expected_version=3
        ),
        identity=_identity(),
        correlation_id=CORRELATION_ID,
        query_sha256="0" * 64,
        now=datetime(2026, 8, 29, 1, 5, tzinfo=UTC),
    )
    assert updated.status == "reviewed"
    assert updated.status_version == 4
    assert {type(item) for item in db.added} == {
        ReportStatusAudit,
        AdminOperationAudit,
    }
    assert db.commit_count == 1


def test_delivery_package_is_deterministic_and_binds_every_digest() -> None:
    report = SimpleNamespace(
        id=REPORT_ID,
        status="reviewed",
        class_name="damaged_tactile_block",
        confidence=0.75,
        latitude=37.5665,
        longitude=126.978,
        accuracy_m=4.0,
        captured_at=datetime(2026, 8, 29, 1, 0, tzinfo=UTC),
        created_at=datetime(2026, 8, 29, 1, 1, tzinfo=UTC),
    )

    first = build_admin_report_delivery_package(
        report=report,
        review_decision_id=REVIEW_ID,
        review_revision=4,
        export_audit_id=EXPORT_AUDIT_ID,
        package_revision=2,
    )
    second = build_admin_report_delivery_package(
        report=report,
        review_decision_id=REVIEW_ID,
        review_revision=4,
        export_audit_id=EXPORT_AUDIT_ID,
        package_revision=2,
    )

    assert first.package_bytes == second.package_bytes
    assert first.csv_sha256 == hashlib.sha256(first.csv_bytes).hexdigest()
    assert first.manifest_sha256 == hashlib.sha256(first.manifest_bytes).hexdigest()
    assert first.package_sha256 == hashlib.sha256(first.package_bytes).hexdigest()
    manifest = json.loads(first.manifest_bytes)
    assert manifest["schema_version"] == DELIVERY_PACKAGE_SCHEMA_VERSION
    assert manifest["export_audit_id"] == str(EXPORT_AUDIT_ID)
    assert manifest["csv_sha256"] == first.csv_sha256
    assert b"image_path" not in first.csv_bytes
    assert b"payload" not in first.csv_bytes


class _PackageSession:
    def __init__(self, results: list[object | None]) -> None:
        self.results = results
        self.added: list[object] = []
        self.commit_count = 0
        self.rollback_count = 0
        self.flush_count = 0
        self.statements: list[object] = []

    def execute(self, statement: object) -> _ScalarResult:
        self.statements.append(statement)
        return _ScalarResult(self.results.pop(0))

    def add(self, value: object) -> None:
        self.added.append(value)

    def commit(self) -> None:
        self.commit_count += 1

    def flush(self) -> None:
        self.flush_count += 1

    def rollback(self) -> None:
        self.rollback_count += 1


def test_package_export_record_and_success_action_audit_share_one_commit() -> None:
    report = Report(
        id=REPORT_ID,
        status="reviewed",
        class_name="damaged_tactile_block",
        confidence=0.75,
        latitude=37.5665,
        longitude=126.978,
        accuracy_m=4.0,
        captured_at=datetime(2026, 8, 29, 1, 0, tzinfo=UTC),
        created_at=datetime(2026, 8, 29, 1, 1, tzinfo=UTC),
    )
    review = ReportReviewDecision(
        id=REVIEW_ID,
        report_id=REPORT_ID,
        revision=4,
        decision="APPROVED",
        duplicate_of_report_id=None,
        evidence_grant_id=uuid.UUID("55555555-5555-4555-8555-555555555555"),
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    db = _PackageSession([report, None, review])

    created = create_admin_report_delivery_package(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        expected_content_revision=0,
        expected_review_revision=4,
        identity=_identity(),
        correlation_id=CORRELATION_ID,
        query_sha256="0" * 64,
    )

    assert created.record.review_decision_id == REVIEW_ID
    assert created.record.package_byte_count == len(created.package_bytes)
    assert not hasattr(created.record, "package_bytes")
    assert {type(item) for item in db.added} == {
        ReportExportAudit,
        ReportDeliveryPackage,
        AdminOperationAudit,
    }
    assert db.commit_count == 1
    assert db.flush_count == 1
    operation = next(
        item for item in db.added if isinstance(item, AdminOperationAudit)
    )
    assert operation.resource_id == str(created.record.id)


def test_delivery_package_http_response_is_201_zip(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    package_id = uuid.UUID("77777777-7777-4777-8777-777777777777")
    monkeypatch.setattr(
        admin_reports,
        "_require_admin_report_action_context",
        lambda *_args, **_kwargs: (
            _identity(),
            SimpleNamespace(
                correlation_id=CORRELATION_ID,
                query_sha256="0" * 64,
                request_body=b'{"expected_content_revision":3,"expected_review_revision":5}',
            ),
        ),
    )
    monkeypatch.setattr(
        admin_reports,
        "create_admin_report_delivery_package",
        lambda *_args, **_kwargs: SimpleNamespace(
            package_bytes=b"PK\x03\x04walksafe",
            review_revision=5,
            record=SimpleNamespace(
                id=package_id,
                revision=2,
                content_revision=3,
                package_byte_count=13,
                supersedes_package_id=None,
                export_audit_id=EXPORT_AUDIT_ID,
                package_sha256="a" * 64,
                csv_sha256="b" * 64,
                manifest_sha256="c" * 64,
            ),
        ),
    )

    response = admin_reports.create_delivery_package(
        REPORT_ID,
        AdminReportPackageCreateRequest(
            expected_content_revision=3,
            expected_review_revision=5,
        ),
        SimpleNamespace(
            headers={"X-WalkSafe-Reconfirm-Nonce": "A" * 22},
        ),
        db=SimpleNamespace(),  # type: ignore[arg-type]
    )

    assert response.status_code == 201
    assert response.media_type == "application/zip"
    assert response.body == b"PK\x03\x04walksafe"
    assert response.headers["x-walksafe-package-id"] == str(package_id)
    assert response.headers["x-walksafe-review-revision"] == "5"
    assert response.headers["x-walksafe-package-byte-count"] == "13"


def test_package_create_rejects_stale_approved_review_revision() -> None:
    report = Report(
        id=REPORT_ID,
        status="reviewed",
        content_revision=0,
        class_name="damaged_tactile_block",
        confidence=0.75,
        latitude=37.5665,
        longitude=126.978,
        accuracy_m=4.0,
        captured_at=datetime(2026, 8, 29, 1, 0, tzinfo=UTC),
        created_at=datetime(2026, 8, 29, 1, 1, tzinfo=UTC),
    )
    review = ReportReviewDecision(
        id=REVIEW_ID,
        report_id=REPORT_ID,
        revision=4,
        content_revision=0,
        decision="APPROVED",
        duplicate_of_report_id=None,
        evidence_grant_id=uuid.UUID("55555555-5555-4555-8555-555555555555"),
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    db = _PackageSession([report, None, review])

    with pytest.raises(AdminReportWorkflowError) as captured:
        create_admin_report_delivery_package(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            expected_content_revision=0,
            expected_review_revision=3,
            identity=_identity(),
            correlation_id=CORRELATION_ID,
            query_sha256="0" * 64,
        )

    assert captured.value.code == "delivery_package_review_revision_conflict"
    assert captured.value.status_code == 409
    assert db.rollback_count == 1


def test_package_create_uses_latest_typed_decision_not_latest_approval() -> None:
    report = Report(id=REPORT_ID, content_revision=0)
    rejected = ReportReviewDecision(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        revision=5,
        content_revision=0,
        decision="REJECTED",
        user_visible_reason="기관 전달 대상이 아닙니다",
        duplicate_of_report_id=None,
        evidence_grant_id=None,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    db = _PackageSession([report, None, rejected])

    with pytest.raises(AdminReportWorkflowError) as captured:
        create_admin_report_delivery_package(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            expected_content_revision=0,
            expected_review_revision=5,
            identity=_identity(),
            correlation_id=CORRELATION_ID,
            query_sha256="0" * 64,
        )

    assert captured.value.code == "latest_review_approval_required"
    review_sql = str(db.statements[2])
    assert "report_review_decisions.decision =" not in review_sql
    assert "report_review_decisions.content_revision =" not in review_sql


def test_package_proof_rejects_after_latest_typed_review_revokes_approval() -> None:
    rejected = ReportReviewDecision(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        revision=5,
        content_revision=0,
        decision="REJECTED",
        user_visible_reason="기관 전달 대상이 아닙니다",
        duplicate_of_report_id=None,
        evidence_grant_id=None,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    package = ReportDeliveryPackage(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        review_decision_id=REVIEW_ID,
        revision=2,
        content_revision=0,
        package_version=2,
        schema_version="walksafe.admin-report-delivery-package.v2",
        package_byte_count=2048,
        package_sha256="f" * 64,
    )
    db = _PackageSession(
        [Report(id=REPORT_ID, content_revision=0), None, rejected, package]
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        get_admin_report_delivery_package_proof(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            package_revision=2,
        )

    assert captured.value.code == "latest_review_approval_required"
    assert captured.value.status_code == 409


def test_package_proof_returns_minimum_v2_metadata_before_first_delivery_event() -> None:
    report = Report(id=REPORT_ID, content_revision=0)
    review = ReportReviewDecision(
        id=REVIEW_ID,
        report_id=REPORT_ID,
        revision=4,
        content_revision=0,
        decision="APPROVED",
        duplicate_of_report_id=None,
        evidence_grant_id=uuid.UUID("55555555-5555-4555-8555-555555555555"),
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    package_id = uuid.UUID("12121212-1212-4212-8212-121212121212")
    package = ReportDeliveryPackage(
        id=package_id,
        report_id=REPORT_ID,
        review_decision_id=REVIEW_ID,
        revision=2,
        content_revision=0,
        package_version=2,
        schema_version="walksafe.admin-report-delivery-package.v2",
        package_byte_count=2048,
        package_sha256="f" * 64,
    )
    db = _PackageSession([report, None, review, package])

    proof = get_admin_report_delivery_package_proof(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        package_revision=2,
    )

    assert package_id == package.id
    assert proof.package_revision == 2
    assert proof.content_revision == 0
    assert proof.review_revision == 4
    assert proof.package_schema_version == "walksafe.admin-report-delivery-package.v2"
    assert proof.package_byte_count == 2048
    assert proof.package_sha256 == "f" * 64
    assert "csv_sha256" not in proof.__dict__


@pytest.mark.parametrize(
    "status",
    ["SUBMITTED", "ACKNOWLEDGED"],
    ids=["SUBMITTED-to-ACKNOWLEDGED", "ACKNOWLEDGED-to-RESOLVED"],
)
def test_package_proof_reconnects_same_v2_package_for_next_transition(
    status: str,
) -> None:
    report = Report(id=REPORT_ID, content_revision=0)
    review = ReportReviewDecision(
        id=REVIEW_ID,
        report_id=REPORT_ID,
        revision=4,
        content_revision=0,
        decision="APPROVED",
        duplicate_of_report_id=None,
        evidence_grant_id=uuid.UUID("55555555-5555-4555-8555-555555555555"),
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    package = ReportDeliveryPackage(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        review_decision_id=REVIEW_ID,
        revision=2,
        content_revision=0,
        package_version=2,
        schema_version="walksafe.admin-report-delivery-package.v2",
        package_byte_count=2048,
        package_sha256="f" * 64,
    )
    latest_delivery = SimpleNamespace(
        status=status,
        package_id=package.id,
        package_revision=package.revision,
    )
    db = _PackageSession([report, latest_delivery, review, package])

    proof = get_admin_report_delivery_package_proof(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        package_revision=2,
    )

    assert proof.package_revision == package.revision
    assert proof.package_sha256 == package.package_sha256


@pytest.mark.parametrize(
    ("status", "package_version", "expected_code"),
    [
        ("RESOLVED", 2, "delivery_package_proof_resolved"),
        ("FAILED", 1, "delivery_package_proof_ineligible"),
    ],
)
def test_package_proof_rejects_resolved_or_legacy_package(
    status: str,
    package_version: int,
    expected_code: str,
) -> None:
    report = Report(id=REPORT_ID, content_revision=0)
    review = ReportReviewDecision(
        id=REVIEW_ID,
        report_id=REPORT_ID,
        revision=4,
        content_revision=0,
        decision="APPROVED",
        duplicate_of_report_id=None,
        evidence_grant_id=uuid.UUID("55555555-5555-4555-8555-555555555555"),
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    event = ReportDeliveryPackage(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        review_decision_id=REVIEW_ID,
        revision=2,
        content_revision=0,
        package_version=package_version,
        schema_version=(
            "walksafe.admin-report-delivery-package.v2"
            if package_version == 2
            else "walksafe.admin-report-delivery-package.v1"
        ),
        package_byte_count=2048,
        package_sha256="f" * 64,
    )
    latest_delivery = SimpleNamespace(
        status=status,
        package_id=event.id,
        package_revision=event.revision,
    )
    db = _PackageSession([report, latest_delivery, review, event])

    with pytest.raises(AdminReportWorkflowError) as captured:
        get_admin_report_delivery_package_proof(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            package_revision=2,
        )

    assert captured.value.code == expected_code
    assert captured.value.status_code == 409


def test_package_proof_allows_failed_same_v2_package_retry() -> None:
    review = ReportReviewDecision(
        id=REVIEW_ID,
        report_id=REPORT_ID,
        revision=4,
        content_revision=0,
        decision="APPROVED",
        duplicate_of_report_id=None,
        evidence_grant_id=uuid.uuid4(),
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    package = ReportDeliveryPackage(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        review_decision_id=REVIEW_ID,
        revision=2,
        content_revision=0,
        package_version=2,
        schema_version="walksafe.admin-report-delivery-package.v2",
        package_byte_count=2048,
        package_sha256="f" * 64,
    )
    latest_delivery = SimpleNamespace(
        status="FAILED",
        package_id=package.id,
        package_revision=package.revision,
    )
    db = _PackageSession(
        [Report(id=REPORT_ID, content_revision=0), latest_delivery, review, package]
    )

    proof = get_admin_report_delivery_package_proof(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        package_revision=2,
    )

    assert proof.package_revision == package.revision
    assert proof.package_sha256 == package.package_sha256


def test_package_proof_rejects_new_package_after_failed_same_content_workflow() -> None:
    review = ReportReviewDecision(
        id=REVIEW_ID,
        report_id=REPORT_ID,
        revision=4,
        content_revision=0,
        decision="APPROVED",
        duplicate_of_report_id=None,
        evidence_grant_id=uuid.uuid4(),
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    previous_package = ReportDeliveryPackage(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        review_decision_id=REVIEW_ID,
        revision=1,
        content_revision=0,
        package_version=2,
    )
    current_package = ReportDeliveryPackage(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        review_decision_id=REVIEW_ID,
        revision=2,
        content_revision=0,
        package_version=2,
        schema_version="walksafe.admin-report-delivery-package.v2",
        package_byte_count=2048,
        package_sha256="f" * 64,
    )
    latest_delivery = SimpleNamespace(
        status="FAILED",
        package_id=previous_package.id,
        package_revision=previous_package.revision,
    )
    db = _PackageSession(
        [
            Report(id=REPORT_ID, content_revision=0),
            latest_delivery,
            review,
            current_package,
            previous_package,
        ]
    )

    with pytest.raises(AdminReportWorkflowError) as captured:
        get_admin_report_delivery_package_proof(
            db,  # type: ignore[arg-type]
            report_id=REPORT_ID,
            package_revision=2,
        )

    assert captured.value.code == "delivery_package_revision_conflict"
    assert captured.value.status_code == 409


def test_package_proof_allows_new_content_after_resolved_prior_workflow() -> None:
    current_review = ReportReviewDecision(
        id=REVIEW_ID,
        report_id=REPORT_ID,
        revision=5,
        content_revision=1,
        decision="APPROVED",
        duplicate_of_report_id=None,
        evidence_grant_id=uuid.uuid4(),
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    previous_package = ReportDeliveryPackage(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        review_decision_id=uuid.uuid4(),
        revision=1,
        content_revision=0,
        package_version=2,
    )
    current_package = ReportDeliveryPackage(
        id=uuid.uuid4(),
        report_id=REPORT_ID,
        review_decision_id=REVIEW_ID,
        revision=2,
        content_revision=1,
        package_version=2,
        schema_version="walksafe.admin-report-delivery-package.v2",
        package_byte_count=2048,
        package_sha256="f" * 64,
    )
    latest_delivery = SimpleNamespace(
        status="RESOLVED",
        package_id=previous_package.id,
        package_revision=previous_package.revision,
    )
    db = _PackageSession(
        [
            Report(id=REPORT_ID, content_revision=1),
            latest_delivery,
            SimpleNamespace(
                report_id=REPORT_ID,
                revision=1,
                expected_revision=0,
                idempotency_key=uuid.uuid4(),
                content_sha256="e" * 64,
                user_description=None,
                category_hint=None,
                created_at=OCCURRED_AT,
            ),
            current_review,
            current_package,
            previous_package,
        ]
    )

    proof = get_admin_report_delivery_package_proof(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
        package_revision=2,
    )

    assert proof.content_revision == 1
    assert proof.review_revision == 5


def test_audit_projection_is_allowlisted_and_cursor_is_filter_bound() -> None:
    row = AdminAuditEventRow(
        event_id="44444444-4444-4444-8444-444444444444",
        event_type="DELIVERY",
        action="report.delivery.SUBMITTED",
        outcome="SUCCEEDED",
        actor_id="reviewer@example.com",
        resource_type="report",
        resource_id=str(REPORT_ID),
        occurred_at=OCCURRED_AT,
        correlation_id=CORRELATION_ID,
    )
    event = project_admin_audit_event(row)
    assert set(event.model_dump()) == {
        "event_id",
        "event_type",
        "action",
        "outcome",
        "actor_id",
        "resource_type",
        "resource_id",
        "occurred_at",
        "correlation_id",
    }

    digest = admin_audit_filter_sha256(event_type="DELIVERY", actor_id=None)
    cursor = encode_admin_audit_cursor(row=row, filter_sha256=digest)
    assert decode_admin_audit_cursor(
        cursor, expected_filter_sha256=digest
    ).event_id == row.event_id
    with pytest.raises(AdminAuditCursorError):
        decode_admin_audit_cursor(cursor, expected_filter_sha256="f" * 64)
