"""S18-41: proof for a submitted package survives a later content revision."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import os
import uuid

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from backend.app.models import (
    Report,
    ReportContentRevision,
    ReportDeliveryPackage,
    ReportInstitutionDeliveryEvent,
    ReportReviewDecision,
)
from backend.app.services import admin_report_delivery_package as package_service
from backend.app.services import admin_report_workflow as workflow
from backend.app.services.admin_report_workflow import AdminReportWorkflowError
from backend.app.services.report_content_corrections import report_content_sha256
from test_admin_delivery_correction_w23 import (
    _delivery_request,
    _identity,
    _seed_approved_report,
)


class _Row:
    def __init__(self, value: object) -> None:
        self.value = value

    def scalar_one_or_none(self) -> object:
        return self.value


class _ProofSession:
    def __init__(self, values: dict[str, object]) -> None:
        self.values = values
        self.rollback_count = 0

    def execute(self, statement: object) -> _Row:
        sql = str(statement)
        if "FROM report_institution_delivery_events" in sql:
            return _Row(self.values["event"])
        if "FROM report_content_revisions" in sql:
            return _Row(self.values["content"])
        if "FROM report_review_decisions" in sql:
            if "report_review_decisions.id =" in sql:
                return _Row(self.values["historical_review"])
            return _Row(self.values["current_review"])
        if "FROM report_delivery_packages" in sql:
            return _Row(self.values["package"])
        if "FROM reports" in sql:
            return _Row(self.values["report"])
        raise AssertionError(f"unexpected proof query: {sql}")

    def rollback(self) -> None:
        self.rollback_count += 1


def test_old_submitted_package_proof_uses_its_submission_review_and_digest() -> None:
    report_id = uuid.uuid4()
    review_id = uuid.uuid4()
    package_id = uuid.uuid4()
    old_review = ReportReviewDecision(
        id=review_id, report_id=report_id, revision=1, content_revision=0,
        decision="APPROVED", evidence_grant_id=uuid.uuid4(),
        location_reviewed=True, photo_reviewed=True, privacy_reviewed=True,
        duplicate_of_report_id=None,
    )
    rejected = ReportReviewDecision(
        id=uuid.uuid4(), report_id=report_id, revision=2, content_revision=1,
        decision="REJECTED", evidence_grant_id=None,
        location_reviewed=True, photo_reviewed=True, privacy_reviewed=True,
        duplicate_of_report_id=None,
    )
    package = ReportDeliveryPackage(
        id=package_id, report_id=report_id, review_decision_id=review_id,
        revision=1, content_revision=0, package_version=2,
        schema_version=package_service.DELIVERY_PACKAGE_SCHEMA_VERSION_V2,
        package_byte_count=2048, package_sha256="f" * 64,
    )
    event = ReportInstitutionDeliveryEvent(
        report_id=report_id, status="SUBMITTED", package_id=package_id,
        package_revision=1, review_decision_id=review_id,
    )
    content = ReportContentRevision(
        report_id=report_id, revision=1, content_sha256="a" * 64,
        user_description="수정된 설명", category_hint="OTHER",
    )
    db = _ProofSession({
        "report": Report(id=report_id, content_revision=1),
        "event": event,
        "content": content,
        "current_review": rejected,
        "historical_review": old_review,
        "package": package,
    })

    proof = package_service.get_admin_report_delivery_package_proof(
        db,  # type: ignore[arg-type]
        report_id=report_id,
        package_revision=1,
    )

    assert (proof.content_revision, proof.review_revision) == (0, 1)
    assert (proof.package_sha256, proof.package_byte_count) == ("f" * 64, 2048)
    assert db.rollback_count == 0

    event.status = "FAILED"
    with pytest.raises(AdminReportWorkflowError) as denied:
        package_service.get_admin_report_delivery_package_proof(
            db,  # type: ignore[arg-type]
            report_id=report_id,
            package_revision=1,
        )
    assert denied.value.code == "latest_review_approval_required"

    event.status = "SUBMITTED"
    event.review_decision_id = uuid.uuid4()
    with pytest.raises(AdminReportWorkflowError) as denied:
        package_service.get_admin_report_delivery_package_proof(
            db,  # type: ignore[arg-type]
            report_id=report_id,
            package_revision=1,
        )
    assert denied.value.code == "delivery_package_proof_ineligible"


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
@pytest.mark.parametrize("latest_status", ["SUBMITTED", "ACKNOWLEDGED"])
def test_submitted_package_proof_survives_current_content_change_but_new_submission_does_not(
    monkeypatch: pytest.MonkeyPatch,
    clean_test_storage,
    latest_status: str,
) -> None:
    clean_test_storage()
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"], pool_pre_ping=True)
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)
    at = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(minutes=5)
    identity = _identity()
    try:
        # Seed through the normal ORM service path; the test database owner has
        # no signed administrator device proof for these fixture writes.
        monkeypatch.setattr(workflow, "admin_report_integrity_boundary_state", lambda _db: None)
        monkeypatch.setattr(
            package_service, "admin_report_integrity_boundary_state", lambda _db: None
        )
        report_id, old_package, old_review = _seed_approved_report(
            SessionFactory, identity, at
        )
        with SessionFactory() as db:
            submitted = workflow.append_report_institution_delivery_event(
                db,
                report_id=report_id,
                payload=_delivery_request("SUBMITTED", old_package.revision, 0, at),
                identity=identity,
                correlation_id=uuid.uuid4(),
            )
        if latest_status == "ACKNOWLEDGED":
            with SessionFactory() as db:
                workflow.append_report_institution_delivery_event(
                    db,
                    report_id=report_id,
                    payload=_delivery_request(
                        "ACKNOWLEDGED", old_package.revision, 1,
                        at + timedelta(seconds=1),
                    ),
                    identity=identity,
                    correlation_id=uuid.uuid4(),
                )

        subject = "a" * 64
        with SessionFactory.begin() as db:
            report = db.get(Report, report_id)
            assert report is not None
            report.privacy_subject_hmac = subject
            report.account_generation = 1
            db.flush()
            db.add(
                ReportContentRevision(
                    id=uuid.uuid4(),
                    report_id=report_id,
                    revision=1,
                    expected_revision=0,
                    idempotency_key=uuid.uuid4(),
                    privacy_subject_hmac=subject,
                    account_generation=1,
                    user_description="기관 제출 이후 정정된 설명",
                    category_hint="OTHER",
                    intent_sha256="b" * 64,
                    content_sha256=report_content_sha256(
                        report_id=report_id,
                        revision=1,
                        user_description="기관 제출 이후 정정된 설명",
                        category_hint="OTHER",
                    ),
                    created_at=at + timedelta(seconds=1),
                )
            )
            report.content_revision = 1
            db.add(
                ReportReviewDecision(
                    id=uuid.uuid4(),
                    report_id=report_id,
                    revision=old_review.revision + 1,
                    content_revision=1,
                    decision="REJECTED",
                    reason="정정 내용을 재검수 중",
                    user_visible_reason="정정 내용의 확인이 필요합니다",
                    duplicate_of_report_id=None,
                    evidence_grant_id=None,
                    location_reviewed=True,
                    photo_reviewed=True,
                    privacy_reviewed=True,
                    admin_id=identity.admin_id,
                    session_id=identity.session_id,
                    device_id=identity.device_id,
                    correlation_id=uuid.uuid4(),
                    decided_at=at + timedelta(seconds=2),
                )
            )

        with SessionFactory() as db:
            proof = package_service.get_admin_report_delivery_package_proof(
                db, report_id=report_id, package_revision=old_package.revision
            )
            assert (
                proof.package_revision,
                proof.content_revision,
                proof.review_revision,
                proof.package_sha256,
            ) == (
                old_package.revision,
                old_package.content_revision,
                old_review.revision,
                old_package.package_sha256,
            )
            assert proof.package_byte_count == old_package.package_byte_count
            assert db.execute(
                select(ReportInstitutionDeliveryEvent).where(
                    ReportInstitutionDeliveryEvent.id == submitted.id
                )
            ).scalar_one().review_decision_id == old_review.id

        with SessionFactory() as db, pytest.raises(AdminReportWorkflowError) as denied:
            package_service.create_admin_report_delivery_package(
                db,
                report_id=report_id,
                expected_content_revision=1,
                expected_review_revision=old_review.revision + 1,
                identity=identity,
                correlation_id=uuid.uuid4(),
                query_sha256=hashlib.sha256(b"").hexdigest(),
            )
        assert denied.value.code == "latest_review_approval_required"

        with SessionFactory() as db, pytest.raises(AdminReportWorkflowError) as denied:
            workflow.append_report_institution_delivery_event(
                db,
                report_id=report_id,
                payload=_delivery_request(
                    "SUBMITTED", old_package.revision,
                    1 if latest_status == "SUBMITTED" else 2,
                    at + timedelta(seconds=3),
                ),
                identity=identity,
                correlation_id=uuid.uuid4(),
            )
        assert denied.value.code == "latest_review_approval_required"
    finally:
        clean_test_storage()
        engine.dispose()
