from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import hashlib
import os
from pathlib import Path
from types import SimpleNamespace
import uuid

from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
import pytest
from sqlalchemy import create_engine, delete, func, select, text, update
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import sessionmaker

import backend.app.services.admin_report_delivery_package as delivery_package_service
import backend.app.services.admin_report_workflow as report_workflow_service
from backend.app.models import (
    AdminOperationAudit,
    AdminDeviceKey,
    AdminSecurityAudit,
    AdminSecurityControl,
    AdminSecurityRecoveryCode,
    Base,
    Report,
    ReportDeliveryPackage,
    ReportInstitutionDeliveryEvent,
    ReportOriginalAccessAudit,
    ReportOriginalAccessGrant,
    ReportReviewDecision,
    ReportStatusAudit,
)
from backend.app.schemas import (
    AdminReportStatusUpdateV1,
    ReportInstitutionDeliveryRequest,
    ReportReviewDecisionRequest,
)
from backend.app.services.admin_device_proof import (
    AdminDeviceProofService,
    canonical_admin_query_sha256,
    provision_admin_device_key,
    raw_body_sha256,
    verify_admin_device_proof,
)
from backend.app.services.admin_report_workflow import (
    AdminReportWorkflowError,
    append_report_institution_delivery_event,
    append_report_review_decision,
    list_report_institution_delivery_events,
    list_report_review_decisions,
    update_admin_report_status,
)
from backend.app.services.admin_report_delivery_package import (
    create_admin_report_delivery_package,
)
from backend.app.services.admin_security import (
    AdminSecurityError,
    AdminSessionIdentity,
    provision_admin_security,
    record_admin_security_failure,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _session_factory():
    engine = create_engine(
        os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(),
        pool_pre_ping=True,
    )
    return engine, sessionmaker(bind=engine, expire_on_commit=False)


def _identity(admin_id: str, device_id: str) -> AdminSessionIdentity:
    now = datetime.now(timezone.utc)
    return AdminSessionIdentity(
        admin_id=admin_id,
        session_id=uuid.uuid4(),
        device_id=device_id,
        device_label="FP-008 PostgreSQL integration",
        expires_at=now + timedelta(hours=1),
        step_up_verified_at=None,
    )


def _spki(private_key: ec.EllipticCurvePrivateKey) -> bytes:
    return private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )


def _rfc3339_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def test_fp008_postgres_schema_device_key_and_single_use_proof(tmp_path: Path) -> None:
    engine, SessionFactory = _session_factory()
    table_names = {
        "admin_device_keys",
        "admin_device_proof_challenges",
        "report_review_decisions",
        "report_institution_delivery_events",
    }

    def include_fp008(obj, name, type_, reflected, compare_to):
        del reflected, compare_to
        if type_ == "table":
            return name in table_names
        table = getattr(obj, "table", None)
        return table is not None and table.name in table_names

    with engine.connect() as connection:
        differences = compare_metadata(
            MigrationContext.configure(
                connection,
                opts={"include_object": include_fp008},
            ),
            Base.metadata,
        )
    assert differences == []

    def run_as_runtime(callback):
        with engine.connect() as connection:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            connection.commit()
            try:
                with SessionFactory(bind=connection) as db:
                    return callback(db)
            finally:
                connection.rollback()
                connection.execute(text("RESET SESSION AUTHORIZATION"))
                connection.commit()

    suffix = uuid.uuid4().hex[:12]
    requested_admin_id = f"fp008.admin.{suffix}"
    device_id = f"fp008-device-{suffix}"
    private_key = ec.generate_private_key(ec.SECP256R1())
    spki = _spki(private_key)
    provision_time = datetime.now(timezone.utc)
    with SessionFactory() as db:
        controls = db.execute(select(AdminSecurityControl)).scalars().all()
    created_control = not controls
    if created_control:
        admin_id = requested_admin_id
        credential_issuer_key = base64.urlsafe_b64encode(
            hashlib.sha256(b"fp008-integration-issuer-key").digest()
        ).decode("ascii").rstrip("=")
        with SessionFactory() as db:
            provision_admin_security(
                db,
                admin_id=admin_id,
                password="fp008 integration password",
                totp_secret="JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
                recovery_codes=[f"FP008-RECOVERY-{suffix}-0001"],
                credential_issuer_key=credential_issuer_key,
                now=provision_time,
            )
    else:
        assert len(controls) == 1
        admin_id = controls[0].admin_id
        pytest.skip("the shared database already has another administrator control")
    issuer_key_file = tmp_path / "issuer.key"
    issuer_key_file.write_text(credential_issuer_key, encoding="ascii")
    issuer_key_file.chmod(0o600)
    proof_settings = SimpleNamespace(
        admin_totp_secret="JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
        admin_credential_issuer_key_file=issuer_key_file,
    )
    identity = _identity(admin_id, device_id)
    with SessionFactory() as db:
        provisioned = provision_admin_device_key(
            db,
            admin_id=admin_id,
            device_id=device_id,
            key_version=1,
            public_key_spki_der=spki,
        )

    report_id = uuid.uuid4()
    correlation_id = uuid.uuid4()
    body = b'{"decision":"APPROVED"}'
    now = provision_time + timedelta(seconds=1)
    response = run_as_runtime(
        lambda db: AdminDeviceProofService(db, proof_settings).issue_challenge(
            purpose="ACTION",
            action="report.review.decide",
            admin_id=admin_id,
            body_sha256=raw_body_sha256(body),
            correlation_id=str(correlation_id),
            device_id=device_id,
            device_key_marker=provisioned.key_marker,
            device_key_version=1,
            method="POST",
            path=f"/reports/{report_id}/review-decisions",
            query_sha256=canonical_admin_query_sha256(b""),
            read_purpose=None,
            session_id=str(identity.session_id),
            identity=identity,
            now=now,
        )
    )
    signature = base64.urlsafe_b64encode(
        private_key.sign(
            response["signing_payload"].encode("utf-8"),
            ec.ECDSA(hashes.SHA256()),
        )
    ).decode("ascii").rstrip("=")

    def verify_action(db):
        result = verify_admin_device_proof(
            db,
            challenge_id=response["challenge_id"],
            signature=signature,
            correlation_id=str(correlation_id),
            expected_purpose="ACTION",
            expected_action="report.review.decide",
            expected_admin_id=admin_id,
            expected_device_id=device_id,
            expected_session_id=identity.session_id,
            expected_method="POST",
            expected_path=f"/reports/{report_id}/review-decisions",
            expected_read_purpose=None,
            raw_body=body,
            raw_query_string=b"",
            runtime_totp_secret=proof_settings.admin_totp_secret,
            credential_issuer_key=credential_issuer_key,
            now=now + timedelta(seconds=1),
        )
        db.commit()
        return result

    verified = run_as_runtime(verify_action)
    assert verified.challenge_id == uuid.UUID(response["challenge_id"])

    with pytest.raises(AdminSecurityError, match="already consumed"):
        run_as_runtime(
            lambda db: verify_admin_device_proof(
                db,
                challenge_id=response["challenge_id"],
                signature=signature,
                correlation_id=str(correlation_id),
                expected_purpose="ACTION",
                expected_action="report.review.decide",
                expected_admin_id=admin_id,
                expected_device_id=device_id,
                expected_session_id=identity.session_id,
                expected_method="POST",
                expected_path=f"/reports/{report_id}/review-decisions",
                expected_read_purpose=None,
                raw_body=body,
                raw_query_string=b"",
                runtime_totp_secret=proof_settings.admin_totp_secret,
                credential_issuer_key=credential_issuer_key,
                now=now + timedelta(seconds=2),
            )
        )

    for proof_case in (
        {
            "purpose": "LOGIN",
            "action": None,
            "method": "POST",
            "path": "/admin/security/sessions",
            "read_purpose": None,
            "session_id": None,
            "identity": None,
            "body": b'{"login":true}',
            "query": b"",
        },
        {
            "purpose": "ACTION",
            "action": None,
            "method": "GET",
            "path": f"/reports/{report_id}/review-decisions",
            "read_purpose": "report.review_decisions",
            "session_id": str(identity.session_id),
            "identity": identity,
            "body": b"",
            "query": b"view=full",
        },
    ):
        case_correlation_id = uuid.uuid4()
        case_response = run_as_runtime(
            lambda db, case=proof_case: AdminDeviceProofService(
                db,
                proof_settings,
            ).issue_challenge(
                purpose=case["purpose"],
                action=case["action"],
                admin_id=admin_id,
                body_sha256=raw_body_sha256(case["body"]),
                correlation_id=str(case_correlation_id),
                device_id=device_id,
                device_key_marker=provisioned.key_marker,
                device_key_version=1,
                method=case["method"],
                path=case["path"],
                query_sha256=canonical_admin_query_sha256(case["query"]),
                read_purpose=case["read_purpose"],
                session_id=case["session_id"],
                identity=case["identity"],
                now=now,
            )
        )
        case_signature = base64.urlsafe_b64encode(
            private_key.sign(
                case_response["signing_payload"].encode("utf-8"),
                ec.ECDSA(hashes.SHA256()),
            )
        ).decode("ascii").rstrip("=")

        def verify_case(db, case=proof_case):
            result = verify_admin_device_proof(
                db,
                challenge_id=case_response["challenge_id"],
                signature=case_signature,
                correlation_id=str(case_correlation_id),
                expected_purpose=case["purpose"],
                expected_action=case["action"],
                expected_admin_id=admin_id,
                expected_device_id=device_id,
                expected_session_id=(
                    identity.session_id if case["session_id"] is not None else None
                ),
                expected_method=case["method"],
                expected_path=case["path"],
                expected_read_purpose=case["read_purpose"],
                raw_body=case["body"],
                raw_query_string=case["query"],
                runtime_totp_secret=proof_settings.admin_totp_secret,
                credential_issuer_key=credential_issuer_key,
                now=now + timedelta(seconds=1),
            )
            db.commit()
            return result

        case_verified = run_as_runtime(verify_case)
        assert case_verified.challenge_id == uuid.UUID(case_response["challenge_id"])

    second_private_key = ec.generate_private_key(ec.SECP256R1())
    second_spki = _spki(second_private_key)
    with pytest.raises(IntegrityError):
        with SessionFactory.begin() as db:
            db.add(
                AdminDeviceKey(
                    admin_id=admin_id,
                    device_id=device_id,
                    key_version=2,
                    public_key_spki_der=second_spki,
                    key_marker=hashlib.sha256(second_spki).hexdigest(),
                    status="ACTIVE",
                )
            )
    with SessionFactory() as db:
        active_count = db.scalar(
            select(func.count())
            .select_from(AdminDeviceKey)
            .where(
                AdminDeviceKey.admin_id == admin_id,
                AdminDeviceKey.device_id == device_id,
                AdminDeviceKey.status == "ACTIVE",
            )
        )
    assert active_count == 1
    with SessionFactory.begin() as db:
        db.execute(delete(AdminDeviceKey).where(AdminDeviceKey.admin_id == admin_id))
        if created_control:
            db.execute(
                delete(AdminSecurityRecoveryCode).where(
                    AdminSecurityRecoveryCode.admin_id == admin_id
                )
            )
            db.execute(
                delete(AdminSecurityControl).where(
                    AdminSecurityControl.admin_id == admin_id
                )
            )
    engine.dispose()


def test_fp008_postgres_review_delivery_authority_and_append_only_history(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # This predecessor contract test intentionally uses the migration/superuser
    # connection to seed append-only evidence rows directly. Head005 proof-bound
    # Runtime proof behavior is covered by the original-access v3 integration test.
    monkeypatch.setattr(
        report_workflow_service,
        "admin_report_integrity_boundary_state",
        lambda _db: None,
    )
    monkeypatch.setattr(
        delivery_package_service,
        "admin_report_integrity_boundary_state",
        lambda _db: None,
    )
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    identity = _identity(f"fp008.admin.{suffix}", f"fp008-device-{suffix}")
    report_id = uuid.uuid4()
    evidence_grant_id = uuid.uuid4()
    captured_at = datetime.now(timezone.utc)
    with SessionFactory.begin() as db:
        db.add(
            Report(
                id=report_id,
                status="resolved",
                class_id=0,
                class_name="damaged_tactile_block",
                confidence=0.9,
                bbox_x=0.1,
                bbox_y=0.1,
                bbox_width=0.5,
                bbox_height=0.5,
                captured_at=captured_at,
                source="android",
                image_path=f"{report_id}.wse",
                image_content_type="image/jpeg",
                payload={"agency_review_verified": True},
            )
        )
        db.add(
            ReportOriginalAccessGrant(
                id=evidence_grant_id,
                report_id=report_id,
                admin_id=identity.admin_id,
                session_id=identity.session_id,
                device_id=identity.device_id,
                purpose="report_review",
                reason="Review the original report evidence.",
                token_sha256=hashlib.sha256(uuid.uuid4().bytes).hexdigest(),
                issued_at=captured_at,
                expires_at=captured_at + timedelta(minutes=5),
                content_revision=0,
                location_disclosed_at=captured_at,
                consumed_at=captured_at + timedelta(milliseconds=100),
                access_granted_at=captured_at + timedelta(milliseconds=100),
            )
        )
        for action, reason_code in (
            ("LOCATION_DISCLOSED", "exact_location_disclosed"),
            ("ACCESS_GRANTED", "approved_grant_consumed"),
        ):
            db.add(
                ReportOriginalAccessAudit(
                    grant_id=evidence_grant_id,
                    report_id=report_id,
                    admin_id=identity.admin_id,
                    session_id=identity.session_id,
                    device_id=identity.device_id,
                    purpose="report_review",
                    action=action,
                    outcome="SUCCESS",
                    reason_code=reason_code,
                    created_at=captured_at + timedelta(milliseconds=200),
                )
            )

    approved_request = ReportReviewDecisionRequest(
        decision_id=uuid.uuid4(),
        decision="APPROVED",
        reason="위치·사진·개인정보 검수를 완료함",
        duplicate_of_report_id=None,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
        evidence_grant_id=evidence_grant_id,
    )
    with SessionFactory() as db:
        approved = append_report_review_decision(
            db,
            report_id=report_id,
            payload=approved_request,
            identity=identity,
            correlation_id=uuid.uuid4(),
            now=captured_at + timedelta(seconds=1),
        )
    with SessionFactory() as db:
        created_package = create_admin_report_delivery_package(
            db,
            report_id=report_id,
            expected_content_revision=0,
            expected_review_revision=approved.revision,
            identity=identity,
            correlation_id=uuid.uuid4(),
            query_sha256=hashlib.sha256(b"").hexdigest(),
        )
    assert hashlib.sha256(created_package.package_bytes).hexdigest() == (
        created_package.record.package_sha256
    )
    assert created_package.record.package_byte_count == len(
        created_package.package_bytes
    )
    assert not hasattr(created_package.record, "package_bytes")

    first_key = uuid.uuid4()
    submitted_request = ReportInstitutionDeliveryRequest(
        institution="보행환경 담당 기관",
        channel="official_document",
        recipient="안전관리 담당자",
        status="SUBMITTED",
        external_receipt_id=None,
        reason="관리자 외부 수동 전달 사실을 기록함",
        evidence_sha256=None,
        observed_at=_rfc3339_utc(captured_at + timedelta(seconds=2)),
        package_revision=created_package.record.revision,
        expected_revision=0,
        idempotency_key=first_key,
    )
    with SessionFactory() as db:
        submitted = append_report_institution_delivery_event(
            db,
            report_id=report_id,
            payload=submitted_request,
            identity=identity,
            correlation_id=uuid.uuid4(),
        )
    with SessionFactory() as db:
        retried = append_report_institution_delivery_event(
            db,
            report_id=report_id,
            payload=submitted_request,
            identity=identity,
            correlation_id=uuid.uuid4(),
        )
    assert retried.id == submitted.id

    acknowledged_request = ReportInstitutionDeliveryRequest(
        institution="보행환경 담당 기관",
        channel="official_document",
        recipient="안전관리 담당자",
        status="ACKNOWLEDGED",
        external_receipt_id="receipt-fp008-001",
        reason="기관 접수번호를 수동 확인해 상태를 기록함",
        evidence_sha256="a" * 64,
        observed_at=_rfc3339_utc(captured_at + timedelta(seconds=3)),
        package_revision=created_package.record.revision,
        expected_revision=1,
        idempotency_key=uuid.uuid4(),
    )
    with SessionFactory() as db:
        acknowledged = append_report_institution_delivery_event(
            db,
            report_id=report_id,
            payload=acknowledged_request,
            identity=identity,
            correlation_id=uuid.uuid4(),
        )
    assert acknowledged.revision == 2
    assert acknowledged.review_decision_id == approved.id

    rejected_request = ReportReviewDecisionRequest(
        decision_id=uuid.uuid4(),
        decision="REJECTED",
        reason="추가 확인 결과 기관 전달 승인을 철회함",
        user_visible_reason="검토 결과 이 신고를 처리할 수 없습니다",
        duplicate_of_report_id=None,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
    )
    with SessionFactory() as db:
        append_report_review_decision(
            db,
            report_id=report_id,
            payload=rejected_request,
            identity=identity,
            correlation_id=uuid.uuid4(),
        )
    blocked_request = ReportInstitutionDeliveryRequest(
        institution="보행환경 담당 기관",
        channel="official_document",
        recipient="안전관리 담당자",
        status="RESOLVED",
        external_receipt_id="receipt-fp008-001",
        reason="최신 승인 철회 후에는 전달 이력을 추가할 수 없음",
        evidence_sha256=None,
        observed_at=_rfc3339_utc(captured_at + timedelta(seconds=4)),
        package_revision=created_package.record.revision,
        expected_revision=2,
        idempotency_key=uuid.uuid4(),
    )
    with pytest.raises(AdminReportWorkflowError) as blocked:
        with SessionFactory() as db:
            append_report_institution_delivery_event(
                db,
                report_id=report_id,
                payload=blocked_request,
                identity=identity,
                correlation_id=uuid.uuid4(),
            )
    assert blocked.value.code == "latest_review_approval_required"

    with pytest.raises(SQLAlchemyError):
        with SessionFactory.begin() as db:
            db.execute(
                update(ReportReviewDecision)
                .where(ReportReviewDecision.id == approved.id)
                .values(reason="append-only violation")
            )
    with pytest.raises(SQLAlchemyError):
        with SessionFactory.begin() as db:
            event = db.get(ReportInstitutionDeliveryEvent, submitted.id)
            assert event is not None
            db.delete(event)
            db.flush()
    with pytest.raises(SQLAlchemyError):
        with SessionFactory.begin() as db:
            package = db.get(ReportDeliveryPackage, created_package.record.id)
            assert package is not None
            package.package_sha256 = "f" * 64
            db.flush()

    with SessionFactory() as db:
        decisions = list_report_review_decisions(db, report_id=report_id)
        deliveries = list_report_institution_delivery_events(db, report_id=report_id)
    assert [item.revision for item in decisions] == [1, 2]
    assert decisions[0].reason == approved_request.reason
    assert [item.status for item in deliveries] == ["SUBMITTED", "ACKNOWLEDGED"]

    failure_correlation_id = uuid.uuid4()
    record_admin_security_failure(
        action="report.delivery.create",
        reason="latest_review_approval_required",
        outcome="DENIED",
        method="POST",
        path=f"/reports/{report_id}/deliveries",
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=failure_correlation_id,
    )
    with SessionFactory() as db:
        failure_audit = db.execute(
            select(AdminSecurityAudit)
            .where(
                AdminSecurityAudit.action == "report.delivery.create",
                AdminSecurityAudit.admin_id == identity.admin_id,
            )
            .order_by(AdminSecurityAudit.sequence.desc())
        ).scalars().first()
    assert failure_audit is not None
    assert failure_audit.outcome == "DENIED"
    assert failure_audit.session_id == identity.session_id
    assert failure_audit.device_id == identity.device_id
    assert failure_audit.details["admin_alert_required"] is True
    assert failure_audit.details["alert_channel"] == "ADMIN_API_RESPONSE"
    assert failure_audit.details["correlation_id"] == str(failure_correlation_id)
    engine.dispose()


def test_wave5_postgres_status_version_cas_and_atomic_audit() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    identity = _identity(f"wave5.admin.{suffix}", f"wave5-device-{suffix}")
    report_id = uuid.uuid4()
    captured_at = datetime.now(timezone.utc)
    with SessionFactory.begin() as db:
        db.add(
            Report(
                id=report_id,
                status="new",
                class_id=0,
                class_name="person",
                confidence=0.9,
                bbox_x=0.1,
                bbox_y=0.1,
                bbox_width=0.5,
                bbox_height=0.5,
                captured_at=captured_at,
                source="android",
                image_path=f"{report_id}.wse",
                image_content_type="image/jpeg",
                payload={},
            )
        )

    correlation_id = uuid.uuid4()
    with SessionFactory() as db:
        updated = update_admin_report_status(
            db,
            report_id=report_id,
            payload=AdminReportStatusUpdateV1(
                status="reviewed",
                expected_version=1,
            ),
            identity=identity,
            correlation_id=correlation_id,
            query_sha256=hashlib.sha256(b"").hexdigest(),
        )
    assert (updated.status, updated.status_version) == ("reviewed", 2)

    with pytest.raises(AdminReportWorkflowError) as stale:
        with SessionFactory() as db:
            update_admin_report_status(
                db,
                report_id=report_id,
                payload=AdminReportStatusUpdateV1(
                    status="resolved",
                    expected_version=1,
                ),
                identity=identity,
                correlation_id=uuid.uuid4(),
                query_sha256=hashlib.sha256(b"").hexdigest(),
            )
    assert stale.value.code == "report_status_version_conflict"
    assert stale.value.latest_status["status_version"] == 2

    with SessionFactory() as db:
        status_audit = db.scalar(
            select(ReportStatusAudit).where(
                ReportStatusAudit.report_id == report_id,
                ReportStatusAudit.correlation_id == correlation_id,
            )
        )
        operation_audit = db.scalar(
            select(AdminOperationAudit).where(
                AdminOperationAudit.correlation_id == correlation_id,
                AdminOperationAudit.operation == "admin.report.status.update",
            )
        )
    assert status_audit is not None
    assert (status_audit.previous_version, status_audit.next_version) == (1, 2)
    assert operation_audit is not None
    assert operation_audit.outcome == "SUCCEEDED"
    engine.dispose()
