from __future__ import annotations

from datetime import UTC, datetime
import hashlib
import json
from types import SimpleNamespace
import uuid

import pytest
from pydantic import ValidationError

from backend.app.schemas import AdminReportStatusUpdateV1
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

    def execute(self, _statement: object) -> _ScalarResult:
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
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    db = _PackageSession([report, review, None])

    created = create_admin_report_delivery_package(
        db,  # type: ignore[arg-type]
        report_id=REPORT_ID,
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
