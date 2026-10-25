"""S18-40 (originals/18.txt:276-280): append-only delivery event corrections.

A wrongly recorded delivery observation is never updated or deleted; a separate
correction names the corrected event with a reason code and memo, and every
consumer skips corrected events. PostgreSQL tests run only when
WALKSAFE_TEST_DATABASE_URL points at an isolated migrated test database.
New symbols are imported inside the tests so each one fails on its own before
the change.
"""

from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import Response
import pyotp
import pytest
from pydantic import ValidationError
from sqlalchemy import create_engine, select, text
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import sessionmaker

from backend.app.field_test_security import (
    FIELD_TEST_ROUTE_POLICIES,
    requires_admin_device_proof,
)
from backend.app.services.admin_device_proof import validate_device_proof_challenge_binding
from backend.app.services.admin_security import (
    AdminSessionIdentity,
    classify_admin_operation,
)


ADMIN_ID = "walksafe.admin"
DEVICE_ID = "android-device-0040"
REPORT_ID = uuid.UUID("40404040-4040-4040-8040-404040404040")
CORRECTION_PATH = f"/reports/{REPORT_ID}/delivery-corrections"
_PG = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _identity(session_id: uuid.UUID | None = None) -> AdminSessionIdentity:
    now = datetime.now(timezone.utc)
    return AdminSessionIdentity(
        admin_id=ADMIN_ID,
        session_id=session_id or uuid.uuid4(),
        device_id=DEVICE_ID,
        device_label="S18-40",
        expires_at=now + timedelta(hours=1),
        step_up_verified_at=None,
    )


def _correction_body(**overrides: object) -> dict[str, object]:
    body: dict[str, object] = {
        "correction_id": "0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f",
        "corrects_event_id": "1e1e1e1e-1e1e-4e1e-8e1e-1e1e1e1e1e1e",
        "expected_revision": 3,
        "reason_code": "WRONG_REPORT",
        "memo": "다른 신고의 해결 회신을 이 건에 잘못 기록함",
    }
    body.update(overrides)
    return body


# --- contract (no database) -------------------------------------------------------


def test_s18_40_correction_is_a_registered_high_risk_device_proof_write() -> None:
    operation = classify_admin_operation("POST", CORRECTION_PATH)
    assert operation is not None
    assert (operation.action, operation.risk) == ("report.delivery.correct", "HIGH")
    assert classify_admin_operation("GET", CORRECTION_PATH) is None
    assert requires_admin_device_proof(CORRECTION_PATH, "POST") is True
    assert requires_admin_device_proof(CORRECTION_PATH, "GET") is False
    assert any(
        method == "POST" and template == "/reports/{report_id}/delivery-corrections"
        for method, template, *_ in FIELD_TEST_ROUTE_POLICIES
    )
    identity = _identity()
    assert validate_device_proof_challenge_binding(
        purpose="ACTION",
        action="report.delivery.correct",
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        session_id=str(identity.session_id),
        method="POST",
        path=CORRECTION_PATH,
        read_purpose=None,
        identity=identity,
    ) == identity.session_id
    with pytest.raises(ValueError):
        validate_device_proof_challenge_binding(
            purpose="ACTION",
            action="report.delivery.create",
            admin_id=ADMIN_ID,
            device_id=DEVICE_ID,
            session_id=str(identity.session_id),
            method="POST",
            path=CORRECTION_PATH,
            read_purpose=None,
            identity=identity,
        )


def test_s18_40_reconfirmation_for_the_correction_is_plain_digest_only() -> None:
    from backend.app.services.admin_security import (
        _RECONFIRMATION_INTENT_UNSUPPORTED_ACTIONS,
    )

    assert "report.delivery.correct" in _RECONFIRMATION_INTENT_UNSUPPORTED_ACTIONS


@pytest.mark.parametrize(
    ("overrides", "missing"),
    [
        ({}, "memo"),
        ({"memo": "   "}, None),
        ({"memo": ""}, None),
        ({"reason_code": "TYPO"}, None),
        ({"reason_code": None}, None),
        ({"correction_id": "0F0F0F0F-0F0F-4F0F-8F0F-0F0F0F0F0F0F"}, None),
        ({"expected_revision": "3"}, None),
        ({"expected_revision": -1}, None),
        ({"extra": True}, None),
        ({"memo": "x" * 501}, None),
    ],
)
def test_s18_40_correction_request_requires_reason_code_and_memo(
    overrides: dict[str, object], missing: str | None
) -> None:
    from backend.app.schemas import ReportInstitutionDeliveryCorrectionRequest

    body = _correction_body(**overrides)
    if missing is not None:
        body.pop(missing)
    with pytest.raises(ValidationError):
        ReportInstitutionDeliveryCorrectionRequest.model_validate(body)


def test_s18_40_correction_request_accepts_the_exact_body_and_strips_memo() -> None:
    from backend.app.schemas import ReportInstitutionDeliveryCorrectionRequest

    parsed = ReportInstitutionDeliveryCorrectionRequest.model_validate(
        _correction_body(memo="  중복 입력  ", reason_code="DUPLICATE_ENTRY")
    )
    assert parsed.memo == "중복 입력"
    assert parsed.reason_code == "DUPLICATE_ENTRY"
    assert parsed.expected_revision == 3


def test_s18_40_delivery_items_expose_the_correction_field() -> None:
    from backend.app.schemas import ReportInstitutionDeliveryResponse

    assert "correction" in ReportInstitutionDeliveryResponse.model_fields


def test_s18_40_head_integrity_boundary_moves_correction_unaware_functions_to_owner_only() -> None:
    from backend.app.services import admin_report_integrity as integrity

    sql = integrity._DELIVERY_CORRECTION_BOUNDARY_SQL.text
    target = sql.split("    target_functions(signature) AS (", 1)[1].split(
        "    owner_only_functions(signature) AS (", 1
    )[0]
    old_runtime = sql.split("    old_runtime_functions(signature) AS (", 1)[1].split(
        "    whitespace_functions(signature) AS (", 1
    )[0]
    assert "walksafe_append_report_delivery_event_v5(" in target
    assert "walksafe_correct_report_delivery_event(" in target
    assert "walksafe_append_report_delivery_event_v4(" not in target
    assert "walksafe_append_report_delivery_event_v3(" not in target
    assert "walksafe_append_report_delivery_event_v3(" in old_runtime
    assert "walksafe_append_report_delivery_event_v4(" in old_runtime
    assert "count(*) = 9\n             FROM resolved_functions\n" in sql
    assert "count(*) = 6\n             FROM resolved_old_runtime_functions\n" in sql
    assert "count(*) = 21\n             FROM resolved_all_functions\n" in sql
    # target, migration-owned, owner SELECT and owner INSERT table sets.
    assert sql.count("('public.report_institution_delivery_corrections')") == 4
    assert "202609300005" in integrity.ADMIN_REPORT_INTEGRITY_COMPATIBLE_REVISIONS
    assert "202609300005" in integrity.ADMIN_REPORT_DELIVERY_CORRECTION_REVISIONS


# --- PostgreSQL end to end ---------------------------------------------------------


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _pg_environment(tmp_path: Path):
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True)
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)

    def run_as_runtime(callback):
        with engine.connect() as runtime_connection:
            runtime_connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
            runtime_connection.commit()
            try:
                with sessionmaker(bind=runtime_connection, expire_on_commit=False)() as runtime_db:
                    return callback(runtime_db)
            finally:
                if runtime_connection.in_transaction():
                    runtime_connection.rollback()
                runtime_connection.execute(text("RESET SESSION AUTHORIZATION"))
                runtime_connection.commit()

    totp_secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    issuer_key = _b64(bytes(range(32)))
    issuer_directory = tmp_path / "admin-issuer-authority"
    issuer_directory.mkdir(mode=0o700)
    issuer_key_file = issuer_directory / "issuer.key"
    issuer_key_file.write_text(issuer_key, encoding="ascii")
    issuer_key_file.chmod(0o600)
    settings = SimpleNamespace(
        admin_id=ADMIN_ID,
        admin_device_proof_enabled=True,
        admin_credential_issuer_key_file=issuer_key_file,
        admin_totp_secret=totp_secret,
        admin_session_ttl_seconds=43_200,
        admin_step_up_ttl_seconds=300,
        admin_recovery_ttl_seconds=900,
        admin_auth_rate_limit_attempts=5,
        admin_auth_rate_limit_window_seconds=300,
        walksafe_environment="test",
    )
    return engine, SessionFactory, run_as_runtime, settings, totp_secret, issuer_key


def _seed_approved_report(SessionFactory, identity: AdminSessionIdentity, at: datetime):
    """Superuser seeding of an approved report and its v2 delivery package."""

    from backend.app.models import (
        Report,
        ReportOriginalAccessAudit,
        ReportOriginalAccessGrant,
    )
    from backend.app.schemas import ReportReviewDecisionRequest
    from backend.app.services.admin_report_delivery_package import (
        create_admin_report_delivery_package,
    )
    from backend.app.services.admin_report_workflow import append_report_review_decision

    report_id = uuid.uuid4()
    grant_id = uuid.uuid4()
    with SessionFactory.begin() as db:
        db.add(Report(
            id=report_id, status="new", class_id=0, class_name="damaged_tactile_block",
            confidence=0.9, bbox_x=0.1, bbox_y=0.1, bbox_width=0.5, bbox_height=0.5,
            captured_at=at, source="android", image_path=f"{report_id}.wse",
            image_content_type="image/jpeg", payload={"agency_review_verified": True},
        ))
        db.add(ReportOriginalAccessGrant(
            id=grant_id, report_id=report_id, admin_id=identity.admin_id,
            session_id=identity.session_id, device_id=identity.device_id,
            purpose="report_review", reason="Review the original report evidence.",
            token_sha256=hashlib.sha256(uuid.uuid4().bytes).hexdigest(),
            issued_at=at, expires_at=at + timedelta(minutes=5), content_revision=0,
            location_disclosed_at=at, consumed_at=at + timedelta(milliseconds=100),
            access_granted_at=at + timedelta(milliseconds=100),
        ))
        for action, reason_code in (
            ("LOCATION_DISCLOSED", "exact_location_disclosed"),
            ("ACCESS_GRANTED", "approved_grant_consumed"),
        ):
            db.add(ReportOriginalAccessAudit(
                grant_id=grant_id, report_id=report_id, admin_id=identity.admin_id,
                session_id=identity.session_id, device_id=identity.device_id,
                purpose="report_review", action=action, outcome="SUCCESS",
                reason_code=reason_code, created_at=at + timedelta(milliseconds=200),
            ))
    with SessionFactory() as db:
        approved = append_report_review_decision(
            db, report_id=report_id,
            payload=ReportReviewDecisionRequest(
                decision_id=uuid.uuid4(), decision="APPROVED",
                reason="위치·사진·개인정보 검수를 완료함", duplicate_of_report_id=None,
                location_reviewed=True, photo_reviewed=True, privacy_reviewed=True,
                evidence_grant_id=grant_id,
            ),
            identity=identity, correlation_id=uuid.uuid4(), now=at + timedelta(seconds=1),
        )
    with SessionFactory() as db:
        package = create_admin_report_delivery_package(
            db, report_id=report_id, expected_content_revision=0,
            expected_review_revision=approved.revision, identity=identity,
            correlation_id=uuid.uuid4(), query_sha256=hashlib.sha256(b"").hexdigest(),
        )
    return report_id, package.record, approved


def _delivery_request(status: str, package_revision: int, expected_revision: int, at: datetime):
    from backend.app.schemas import ReportInstitutionDeliveryRequest

    return ReportInstitutionDeliveryRequest(
        institution="보행환경 담당 기관",
        channel="official_document",
        recipient="안전관리 담당자",
        status=status,
        external_receipt_id=None if status in {"SUBMITTED", "FAILED"} else "receipt-s18-40",
        reason=f"{status} 관찰을 수동 기록함",
        evidence_sha256=None,
        observed_at=at.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        package_revision=package_revision,
        expected_revision=expected_revision,
        idempotency_key=uuid.uuid4(),
    )


def _canonical_body(payload: dict[str, object]) -> bytes:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


@_PG
def test_s18_40_postgres_correction_restores_effective_state_and_keeps_every_event(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    clean_test_storage,
) -> None:
    from backend.app import database as database_api
    from backend.app.api import admin_reports as admin_reports_api
    from backend.app.models import (
        AdminOperationAudit,
        AdminSecurityReconfirmation,
        Report,
        ReportInstitutionDeliveryCorrection,
        ReportInstitutionDeliveryEvent,
    )
    from backend.app.schemas import ReportInstitutionDeliveryCorrectionRequest
    from backend.app.services import admin_report_delivery_package as package_service
    from backend.app.services import admin_report_workflow as workflow
    from backend.app.services.admin_device_proof import (
        AdminDeviceProofService,
        canonical_admin_query_sha256,
        provision_admin_device_key,
        raw_body_sha256,
        verify_admin_device_proof,
    )
    from backend.app.services.admin_security import (
        ADMIN_APP_KIND,
        ADMIN_AUDIENCE,
        ADMIN_ROLE,
        AdminSecurityService,
        authorize_admin_bearer,
        authorize_database_bound_high_risk_bearer,
        provision_admin_security,
        sha256_text,
    )
    from backend.app.services.report_user_requests import _user_status_expression

    clean_test_storage()
    engine, SessionFactory, run_as_runtime, settings, totp_secret, issuer_key = _pg_environment(
        tmp_path
    )
    monkeypatch.setattr(database_api, "SessionLocal", SessionFactory)
    real_now = datetime.now(timezone.utc).replace(microsecond=0)
    started_at = real_now - timedelta(seconds=150)
    private_key = ec.generate_private_key(ec.SECP256R1())
    spki = private_key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    try:
        with SessionFactory() as db:
            provision_admin_security(
                db, admin_id=ADMIN_ID, password="s18-40 correct horse",
                totp_secret=totp_secret, recovery_codes=["RECOVERY-2026-S18-40-00001"],
                credential_issuer_key=issuer_key, now=started_at,
            )
        with SessionFactory() as db:
            key = provision_admin_device_key(
                db, admin_id=ADMIN_ID, device_id=DEVICE_ID, key_version=1,
                public_key_spki_der=spki, security_level="TRUSTED_ENVIRONMENT",
                now=started_at,
            )
        grant = run_as_runtime(
            lambda db: AdminSecurityService(db, settings).login(
                admin_id=ADMIN_ID, password="s18-40 correct horse",
                totp_code=pyotp.TOTP(totp_secret).at(started_at), device_id=DEVICE_ID,
                device_label="관리자 휴대전화", source="198.51.100.40", now=started_at,
            )
        )
        with SessionFactory() as db:
            identity = authorize_admin_bearer(
                db, grant.access_token, runtime_totp_secret=totp_secret,
                credential_issuer_key=issuer_key, device_id=DEVICE_ID,
                app_kind=ADMIN_APP_KIND, role=ADMIN_ROLE, audience=ADMIN_AUDIENCE,
                now=started_at,
            )
            db.commit()
        run_as_runtime(
            lambda db: AdminSecurityService(db, settings).attest_recovery_custody(
                identity,
                custody_reference="MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY",
                material_kind="RECOVERY_CODE", storage_location="OFF_PHONE",
                separate_encrypted_backup_confirmed=True, now=started_at,
            )
        )

        # Superuser seeding runs on the predecessor ORM path (no runtime proof).
        monkeypatch.setattr(workflow, "admin_report_integrity_boundary_state", lambda _db: None)
        monkeypatch.setattr(package_service, "admin_report_integrity_boundary_state", lambda _db: None)
        report_a, package_a, approval_a = _seed_approved_report(SessionFactory, identity, started_at)
        for revision, status in enumerate(("SUBMITTED", "ACKNOWLEDGED", "RESOLVED")):
            with SessionFactory() as db:
                workflow.append_report_institution_delivery_event(
                    db, report_id=report_a,
                    payload=_delivery_request(
                        status, package_a.revision, revision,
                        started_at + timedelta(seconds=2 + revision),
                    ),
                    identity=identity, correlation_id=uuid.uuid4(),
                )
        # Report B: a RESOLVED recorded without any SUBMITTED (legacy/erroneous row).
        report_b, package_b, approval_b = _seed_approved_report(SessionFactory, identity, started_at)
        wrong_b = uuid.uuid4()
        with SessionFactory.begin() as db:
            db.add(ReportInstitutionDeliveryEvent(
                id=wrong_b, report_id=report_b, review_decision_id=approval_b.id,
                package_id=package_b.id, package_revision=package_b.revision, revision=1,
                institution="보행환경 담당 기관", channel="official_document",
                recipient="안전관리 담당자", status="RESOLVED",
                external_receipt_id="receipt-other-case", reason="다른 건 해결을 잘못 입력",
                evidence_sha256=None, observed_at=started_at + timedelta(seconds=5),
                expected_revision=0, idempotency_key=uuid.uuid4(), admin_id=ADMIN_ID,
                session_id=identity.session_id, device_id=DEVICE_ID,
                correlation_id=uuid.uuid4(),
            ))
        monkeypatch.undo()
        monkeypatch.setattr(database_api, "SessionLocal", SessionFactory)

        def events(report_id: uuid.UUID) -> list[tuple[object, ...]]:
            with SessionFactory() as db:
                return [
                    tuple(row)
                    for row in db.execute(
                        text(
                            "SELECT * FROM public.report_institution_delivery_events "
                            "WHERE report_id = :report_id ORDER BY revision"
                        ),
                        {"report_id": report_id},
                    ).all()
                ]

        def user_status(report_id: uuid.UUID) -> str:
            with SessionFactory() as db:
                return db.execute(
                    select(_user_status_expression()).where(Report.id == report_id)
                ).scalar_one()

        def detail_delivery(report_id: uuid.UUID):
            monkeypatch.setattr(
                admin_reports_api,
                "require_admin_report_detail_context",
                lambda _request: (identity, SimpleNamespace(correlation_id=uuid.uuid4())),
            )
            monkeypatch.setattr(admin_reports_api, "_persist_detail_audit", lambda *a, **k: None)
            with SessionFactory() as db:
                detail = admin_reports_api.get_admin_report_detail(
                    request=SimpleNamespace(query_params={}), response=Response(),
                    report_id=str(report_id), db=db,
                )
            return detail.latest_delivery_revision, detail.current_delivery

        a_events_before = events(report_a)
        b_events_before = events(report_b)
        with SessionFactory() as db:
            a_ids, a_statuses = zip(*db.execute(
                select(ReportInstitutionDeliveryEvent.id, ReportInstitutionDeliveryEvent.status)
                .where(ReportInstitutionDeliveryEvent.report_id == report_a)
                .order_by(ReportInstitutionDeliveryEvent.revision)
            ).all())
        assert list(a_statuses) == ["SUBMITTED", "ACKNOWLEDGED", "RESOLVED"]
        assert user_status(report_a) == "RESOLVED"
        # The erroneous RESOLVED is shown to the user as resolved (FP-033 violation) ...
        assert user_status(report_b) == "RESOLVED"

        totp_step = [started_at]

        def reconfirm(path: str) -> str:
            totp_step[0] += timedelta(seconds=30)
            at = totp_step[0]
            nonce = _b64(os.urandom(16))
            run_as_runtime(
                lambda db: AdminSecurityService(db, settings).reauthenticate(
                    identity, password="s18-40 correct horse",
                    totp_code=pyotp.TOTP(totp_secret).at(at), action="report.delivery.correct",
                    method="POST", path=path, nonce=nonce, source="198.51.100.40", now=at,
                )
            )

            def consume(db):
                consumed = authorize_database_bound_high_risk_bearer(
                    db, grant.access_token, "report.delivery.correct", at + timedelta(seconds=1),
                    method="POST", path=path, nonce=nonce, runtime_totp_secret=totp_secret,
                    credential_issuer_key=issuer_key, device_id=DEVICE_ID,
                )
                db.commit()
                return consumed

            run_as_runtime(consume)
            return sha256_text(nonce)

        def proof(action: str, path: str, body: bytes):
            correlation_id = uuid.uuid4()
            issued_at = datetime.now(timezone.utc) - timedelta(seconds=2)
            challenge = run_as_runtime(
                lambda db: AdminDeviceProofService(db, settings).issue_challenge(
                    purpose="ACTION", action=action, admin_id=ADMIN_ID,
                    body_sha256=raw_body_sha256(body), correlation_id=str(correlation_id),
                    device_id=DEVICE_ID, device_key_marker=key.key_marker,
                    device_key_version=1, method="POST", path=path,
                    query_sha256=canonical_admin_query_sha256(b""), read_purpose=None,
                    session_id=str(identity.session_id), identity=identity, now=issued_at,
                )
            )
            signature = _b64(private_key.sign(
                challenge["signing_payload"].encode("utf-8"), ec.ECDSA(hashes.SHA256())
            ))

            def verify(db):
                verified = verify_admin_device_proof(
                    db, challenge_id=challenge["challenge_id"], signature=signature,
                    correlation_id=str(correlation_id), expected_purpose="ACTION",
                    expected_action=action, expected_admin_id=ADMIN_ID,
                    expected_device_id=DEVICE_ID, expected_session_id=identity.session_id,
                    expected_method="POST", expected_path=path, expected_read_purpose=None,
                    raw_body=body, raw_query_string=b"", runtime_totp_secret=totp_secret,
                    credential_issuer_key=issuer_key,
                    now=issued_at + timedelta(seconds=1),
                )
                db.commit()
                return verified

            return run_as_runtime(verify)

        def correct(report_id, target, expected_revision, nonce_sha, **overrides):
            body_fields = {
                "correction_id": str(overrides.pop("correction_id", uuid.uuid4())),
                "corrects_event_id": str(target),
                "expected_revision": expected_revision,
                "reason_code": overrides.pop("reason_code", "WRONG_REPORT"),
                "memo": overrides.pop("memo", "다른 신고의 기관 회신을 잘못 기록함"),
            }
            body = _canonical_body(body_fields)
            path = f"/reports/{report_id}/delivery-corrections"
            verified = proof("report.delivery.correct", path, body)
            payload = ReportInstitutionDeliveryCorrectionRequest.model_validate(body_fields)
            return run_as_runtime(
                lambda db: workflow.correct_report_institution_delivery_event(
                    db, report_id=report_id, payload=payload, identity=identity,
                    correlation_id=verified.correlation_id,
                    proof_challenge_id=verified.challenge_id,
                    proof_request_body=verified.request_body,
                    reconfirmation_nonce_sha256=nonce_sha,
                    runtime_totp_secret=totp_secret,
                    credential_issuer_key=issuer_key,
                )
            )

        path_a = f"/reports/{report_a}/delivery-corrections"
        nonce_a1 = reconfirm(path_a)

        # Rejections (no row is written, so the approval stays unattached).
        with pytest.raises(workflow.AdminReportWorkflowError) as not_latest:
            correct(report_a, a_ids[1], 3, nonce_a1)
        assert not_latest.value.code == "delivery_correction_target_not_latest"
        with pytest.raises(workflow.AdminReportWorkflowError) as other_report:
            correct(report_a, wrong_b, 3, nonce_a1)
        assert other_report.value.code == "delivery_correction_target_not_found"
        with pytest.raises(workflow.AdminReportWorkflowError) as stale:
            correct(report_a, a_ids[2], 2, nonce_a1)
        assert stale.value.code == "delivery_revision_conflict"
        with pytest.raises(workflow.AdminReportWorkflowError) as no_reconfirmation:
            correct(report_a, a_ids[2], 3, "0" * 64)
        assert no_reconfirmation.value.code == "admin_device_proof_invalid"

        # Success: the wrong RESOLVED (revision 3) is corrected, nothing is rewritten.
        correction_id = uuid.uuid4()
        created = correct(report_a, a_ids[2], 3, nonce_a1, correction_id=correction_id)
        assert (created.corrects_event_id, created.corrected_revision) == (a_ids[2], 3)
        assert (created.reason_code, created.expected_revision) == ("WRONG_REPORT", 3)
        assert created.admin_id == ADMIN_ID and created.session_id == identity.session_id
        assert events(report_a) == a_events_before
        assert user_status(report_a) == "INSTITUTION_SUBMITTED"
        latest_revision, current = detail_delivery(report_a)
        assert latest_revision == 3
        assert (current.revision, current.status) == (2, "ACKNOWLEDGED")
        with SessionFactory() as db:
            assert workflow._latest_delivery_event(db, report_a).revision == 2
            listed = workflow.delivery_items_with_corrections(
                db, workflow.list_report_institution_delivery_events(db, report_id=report_a)
            )
            page = workflow.list_report_institution_delivery_history_page(
                db, report_id=report_a, limit=25, cursor=None
            )
            stored = db.get(ReportInstitutionDeliveryCorrection, a_ids[2])
            reconfirmation_id = db.execute(
                select(AdminSecurityReconfirmation.id).where(
                    AdminSecurityReconfirmation.nonce_sha256 == nonce_a1
                )
            ).scalar_one()
        assert [item.correction is not None for item in listed] == [False, False, True]
        assert [item.correction is not None for item in page.items] == [False, False, True]
        assert page.items[2].correction.id == correction_id
        assert stored.id == correction_id
        assert stored.reconfirmation_id == reconfirmation_id
        assert stored.proof_challenge_id is not None

        # Exact replay returns the stored correction; re-correction and a
        # different intent under the same id are refused.
        replay = correct(report_a, a_ids[2], 3, nonce_a1, correction_id=correction_id)
        assert replay.id == correction_id
        # S18-40-F1: the runtime role records one success operation audit per correction.
        with SessionFactory() as db:
            success_audits = db.execute(
                select(
                    AdminOperationAudit.outcome,
                    AdminOperationAudit.resource_type,
                    AdminOperationAudit.correlation_id,
                ).where(
                    AdminOperationAudit.operation == "report.delivery.correct",
                    AdminOperationAudit.resource_id == str(correction_id),
                )
            ).all()
        assert [tuple(row) for row in success_audits] == [
            ("SUCCEEDED", "delivery_correction", created.correlation_id)
        ]
        with pytest.raises(workflow.AdminReportWorkflowError) as conflict:
            correct(report_a, a_ids[2], 3, nonce_a1, correction_id=correction_id, memo="다른 사유")
        assert conflict.value.code == "delivery_correction_idempotency_conflict"
        nonce_a2 = reconfirm(path_a)
        with pytest.raises(workflow.AdminReportWorkflowError) as again:
            correct(report_a, a_ids[2], 3, nonce_a2)
        assert again.value.code == "delivery_event_already_corrected"
        with pytest.raises(workflow.AdminReportWorkflowError) as reused:
            correct(report_a, a_ids[1], 3, nonce_a1)
        assert reused.value.code == "delivery_correction_conflict"

        # Missing reason is refused by the function itself, before any lookup.
        blank_body = _canonical_body({
            "correction_id": str(uuid.uuid4()), "corrects_event_id": str(a_ids[1]),
            "expected_revision": 3, "reason_code": "WRONG_STATUS", "memo": "",
        })
        blank = run_as_runtime(lambda db: db.execute(
            text(
                "SELECT result_status FROM public.walksafe_correct_report_delivery_event("
                "CAST(:cid AS uuid), CAST(:rid AS uuid), CAST(:eid AS uuid), 3, "
                "'WRONG_STATUS', '', :admin, CAST(:sid AS uuid), :device, "
                "CAST(:corr AS uuid), CAST(:chal AS uuid), :body, NULL, NULL, NULL)"
            ),
            {
                "cid": json.loads(blank_body)["correction_id"], "rid": report_a,
                "eid": a_ids[1], "admin": ADMIN_ID, "sid": identity.session_id,
                "device": DEVICE_ID, "corr": uuid.uuid4(), "chal": uuid.uuid4(),
                "body": blank_body,
            },
        ).scalar_one())
        assert blank == "REASON_INVALID"

        # Stepping further back: correct ACKNOWLEDGED (2), then the v5 append
        # uses SUBMITTED (1) as its base while the CAS stays at revision 3.
        second = correct(report_a, a_ids[1], 3, nonce_a2, reason_code="WRONG_STATUS",
                         memo="기관 접수 확인을 잘못 입력함")
        assert second.corrected_revision == 2
        assert user_status(report_a) == "INSTITUTION_SUBMITTED"
        assert detail_delivery(report_a)[1].status == "SUBMITTED"
        append_payload = _delivery_request(
            "ACKNOWLEDGED", package_a.revision, 3, datetime.now(timezone.utc) - timedelta(seconds=1)
        )
        append_body = append_payload.model_dump_json().encode("utf-8")
        append_path = f"/reports/{report_a}/deliveries"
        delivery_proof = proof("report.delivery.create", append_path, append_body)
        appended = run_as_runtime(
            lambda db: workflow.append_report_institution_delivery_event(
                db, report_id=report_a, payload=append_payload, identity=identity,
                correlation_id=delivery_proof.correlation_id,
                proof_challenge_id=delivery_proof.challenge_id,
                proof_request_body=delivery_proof.request_body,
                runtime_totp_secret=totp_secret,
                credential_issuer_key=issuer_key,
            )
        )
        assert (appended.revision, appended.status) == (4, "ACKNOWLEDGED")
        assert detail_delivery(report_a) [1].revision == 4
        assert events(report_a)[:3] == a_events_before

        # Report B: SUBMITTED-less RESOLVED -> correction -> no effective delivery.
        nonce_b = reconfirm(f"/reports/{report_b}/delivery-corrections")
        corrected_b = correct(report_b, wrong_b, 1, nonce_b, reason_code="WRONG_REPORT")
        assert corrected_b.corrected_revision == 1
        assert events(report_b) == b_events_before
        assert user_status(report_b) == "RECEIVED"
        assert detail_delivery(report_b) == (1, None)

        # The downgrade refuses while corrections exist (it would discard evidence).
        from alembic import command
        from alembic.config import Config

        migration_url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
        monkeypatch.setenv("DATABASE_URL", migration_url)
        monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", migration_url)
        with pytest.raises(DBAPIError, match="delivery corrections exist"):
            command.downgrade(
                Config(str(Path(__file__).resolve().parents[1] / "alembic.ini")),
                "202609300004",
            )
        with SessionFactory() as db:
            assert db.execute(text("SELECT version_num FROM alembic_version")).scalar_one() == (
                "202610110001"
            )

        # Correction-unaware predecessors are no longer runtime-callable, and
        # the correction ledger itself is append-only.
        with pytest.raises(DBAPIError) as v4_call:
            run_as_runtime(lambda db: db.execute(text(
                "SELECT public.walksafe_append_report_delivery_event_v4("
                "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, "
                "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)"
            )).all())
        assert getattr(v4_call.value.orig, "sqlstate", None) == "42501"
        for statement in (
            "UPDATE public.report_institution_delivery_corrections SET memo = 'x' "
            "WHERE id = :cid",
            "DELETE FROM public.report_institution_delivery_corrections WHERE id = :cid",
        ):
            with pytest.raises(DBAPIError):
                with SessionFactory.begin() as db:
                    db.execute(text(statement), {"cid": correction_id})
        with pytest.raises(DBAPIError) as runtime_insert:
            run_as_runtime(lambda db: db.execute(text(
                "INSERT INTO public.report_institution_delivery_corrections "
                "(id, report_id, corrects_event_id, corrected_revision, expected_revision, "
                "reason_code, memo, admin_id, session_id, device_id, correlation_id) "
                "VALUES (gen_random_uuid(), :rid, :eid, 4, 4, 'OTHER', 'direct', 'x', "
                "gen_random_uuid(), 'android-device-0040', gen_random_uuid())"
            ), {"rid": report_a, "eid": appended.id}))
        assert getattr(runtime_insert.value.orig, "sqlstate", None) == "42501"
    finally:
        clean_test_storage()
        engine.dispose()


def _route_request(path: str, identity: AdminSessionIdentity, proof: object, nonce: str | None):
    from starlette.requests import Request

    headers = [] if nonce is None else [(b"x-walksafe-reconfirm-nonce", nonce.encode("ascii"))]
    request = Request({
        "type": "http", "http_version": "1.1", "method": "POST", "scheme": "https",
        "path": path, "raw_path": path.encode("ascii"), "query_string": b"",
        "headers": headers, "client": ("127.0.0.1", 12345), "server": ("testserver", 443),
        "state": {},
    })
    request.state.admin_security_identity = identity
    request.state.admin_device_proof = proof
    return request


def test_s18_40_route_binds_the_exact_proof_and_forwards_the_nonce_digest(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from fastapi import HTTPException

    from backend.app.api import reports as reports_api
    from backend.app.schemas import ReportInstitutionDeliveryCorrectionRequest
    from backend.app.services.admin_device_proof import VerifiedAdminDeviceProof

    identity = _identity()
    captured: dict[str, object] = {}
    sentinel = object()

    def correct(_db: object, **kwargs: object) -> object:
        captured.update(kwargs)
        return sentinel

    monkeypatch.setattr(reports_api, "correct_report_institution_delivery_event", correct)
    router = reports_api.create_router(
        SimpleNamespace(admin_security_enabled=True, report_original_grant_ttl_seconds=120),
        SimpleNamespace(),
    )
    routes = {
        (route.path, method): route for route in router.routes for method in route.methods
    }
    route = routes[("/reports/{report_id}/delivery-corrections", "POST")]
    assert route.status_code == 201

    def proof(action: str) -> VerifiedAdminDeviceProof:
        return VerifiedAdminDeviceProof(
            admin_id=identity.admin_id, device_id=identity.device_id,
            session_id=identity.session_id,
            challenge_id=uuid.UUID("55555555-5555-4555-8555-555555555555"),
            correlation_id=uuid.UUID("33333333-3333-4333-8333-333333333333"),
            action=action, purpose="ACTION", read_purpose=None, method="POST",
            path=CORRECTION_PATH, body_sha256="0" * 64, query_sha256="0" * 64,
            device_key_marker="1" * 64, device_key_version=1, request_body=b"{}",
        )

    payload = ReportInstitutionDeliveryCorrectionRequest.model_validate(_correction_body())
    response = Response()
    nonce = "AAECAwQFBgcICQoLDA0ODw"
    observed = route.endpoint(
        report_id=REPORT_ID, payload=payload,
        request=_route_request(CORRECTION_PATH, identity, proof("report.delivery.correct"), nonce),
        response=response, db=object(),
    )
    assert observed is sentinel
    assert captured["reconfirmation_nonce_sha256"] == hashlib.sha256(nonce.encode()).hexdigest()
    assert captured["identity"] is identity and captured["payload"] is payload
    assert captured["proof_challenge_id"] == uuid.UUID("55555555-5555-4555-8555-555555555555")
    assert captured["proof_request_body"] == b"{}"
    assert response.headers["cache-control"] == "no-store"
    # A proof for the plain delivery action cannot be used for a correction.
    captured.clear()
    with pytest.raises(HTTPException) as mismatch:
        route.endpoint(
            report_id=REPORT_ID, payload=payload,
            request=_route_request(CORRECTION_PATH, identity, proof("report.delivery.create"), nonce),
            response=Response(), db=object(),
        )
    assert mismatch.value.status_code == 403 and captured == {}


# --- review follow-up (w23 fix_admin_delivery: S18-40-F1/F5/F7) -----------------------


def _verified_proof(identity: AdminSessionIdentity, action: str, path: str):
    from backend.app.services.admin_device_proof import VerifiedAdminDeviceProof

    return VerifiedAdminDeviceProof(
        admin_id=identity.admin_id, device_id=identity.device_id,
        session_id=identity.session_id,
        challenge_id=uuid.UUID("55555555-5555-4555-8555-555555555555"),
        correlation_id=uuid.UUID("33333333-3333-4333-8333-333333333333"),
        action=action, purpose="ACTION", read_purpose=None, method="POST",
        path=path, body_sha256="0" * 64, query_sha256="0" * 64,
        device_key_marker="1" * 64, device_key_version=1, request_body=b"{}",
    )


def test_s18_40_f5_head_boundary_pins_correction_keys_and_append_only_trigger() -> None:
    from backend.app.services import admin_report_integrity as integrity

    sql = integrity._DELIVERY_CORRECTION_BOUNDARY_SQL.text
    for name in (
        "pk_report_delivery_corrections",
        "uq_report_delivery_correction_challenge",
        "uq_report_delivery_correction_reconfirmation",
        "report_institution_delivery_corrections_append_only",
    ):
        assert f"'{name}'" in sql, name
    for column in ("corrects_event_id", "proof_challenge_id", "reconfirmation_id"):
        assert f"attribute.attname = '{column}'" in sql, column
    assert "'public.walksafe_reject_audit_mutation()'" in sql
    # The predecessor (202609280001..202609300004) boundary is unchanged.
    assert "report_institution_delivery_corrections" not in (
        integrity._REVIEW_DELIVERY_V4_BOUNDARY_SQL.text
    )


def test_s18_40_f7_delivery_replay_response_carries_the_correction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from backend.app.api import reports as reports_api
    from backend.app.models import (
        ReportInstitutionDeliveryCorrection,
        ReportInstitutionDeliveryEvent,
    )

    identity = _identity()
    at = datetime(2026, 9, 30, 1, 2, 3, tzinfo=timezone.utc)
    event = ReportInstitutionDeliveryEvent(
        id=uuid.UUID("1e1e1e1e-1e1e-4e1e-8e1e-1e1e1e1e1e1e"), report_id=REPORT_ID,
        review_decision_id=uuid.uuid4(), package_id=None, package_revision=None,
        revision=1, institution="보행환경 담당 기관", channel="official_document",
        recipient="안전관리 담당자", status="SUBMITTED", external_receipt_id=None,
        reason="SUBMITTED 관찰을 수동 기록함", evidence_sha256=None, observed_at=at,
        expected_revision=0, idempotency_key=uuid.uuid4(), admin_id=ADMIN_ID,
        session_id=identity.session_id, device_id=DEVICE_ID,
        correlation_id=uuid.uuid4(), recorded_at=at,
    )
    correction = ReportInstitutionDeliveryCorrection(
        id=uuid.UUID("0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f"), report_id=REPORT_ID,
        corrects_event_id=event.id, corrected_revision=1, expected_revision=1,
        reason_code="DUPLICATE_ENTRY", memo="같은 제출을 두 번 기록함", admin_id=ADMIN_ID,
        session_id=identity.session_id, device_id=DEVICE_ID,
        correlation_id=uuid.uuid4(), proof_challenge_id=None, reconfirmation_id=None,
        created_at=at + timedelta(minutes=1),
    )
    monkeypatch.setattr(
        reports_api, "append_report_institution_delivery_event", lambda _db, **_kwargs: event
    )

    class _CorrectionLookup:
        def get(self, model: object, key: object) -> object:
            if model is ReportInstitutionDeliveryCorrection and key == event.id:
                return correction
            return None

    router = reports_api.create_router(
        SimpleNamespace(admin_security_enabled=True, report_original_grant_ttl_seconds=120),
        SimpleNamespace(),
    )
    route = next(
        route for route in router.routes
        if route.path == "/reports/{report_id}/deliveries" and "POST" in route.methods
    )
    path = f"/reports/{REPORT_ID}/deliveries"
    observed = route.endpoint(
        report_id=REPORT_ID,
        payload=_delivery_request("SUBMITTED", 1, 0, at),
        request=_route_request(
            path, identity, _verified_proof(identity, "report.delivery.create", path), None
        ),
        response=Response(),
        db=_CorrectionLookup(),
    )
    assert observed.id == event.id
    assert observed.correction is not None
    assert (observed.correction.id, observed.correction.reason_code) == (
        correction.id,
        "DUPLICATE_ENTRY",
    )


def _seed_plain_delivery_history(SessionFactory, at: datetime):
    """Superuser rows: SUBMITTED -> ACKNOWLEDGED -> RESOLVED without a package."""

    from backend.app.models import Report, ReportInstitutionDeliveryEvent

    report_id = uuid.uuid4()
    event_ids = [uuid.uuid4() for _ in range(3)]
    with SessionFactory.begin() as db:
        db.add(Report(
            id=report_id, status="new", class_id=0, class_name="damaged_tactile_block",
            confidence=0.9, bbox_x=0.1, bbox_y=0.1, bbox_width=0.5, bbox_height=0.5,
            captured_at=at, source="android", image_path=f"{report_id}.wse",
            image_content_type="image/jpeg", payload={"agency_review_verified": True},
        ))
    for revision, (event_id, status) in enumerate(
        zip(event_ids, ("SUBMITTED", "ACKNOWLEDGED", "RESOLVED")), start=1
    ):
        with SessionFactory.begin() as db:
            db.add(ReportInstitutionDeliveryEvent(
                id=event_id, report_id=report_id, review_decision_id=uuid.uuid4(),
                package_id=None, package_revision=None, revision=revision,
                institution="보행환경 담당 기관", channel="official_document",
                recipient="안전관리 담당자", status=status,
                external_receipt_id=None if status == "SUBMITTED" else f"receipt-{revision}",
                reason=f"{status} 관찰을 수동 기록함", evidence_sha256=None,
                observed_at=at + timedelta(seconds=revision), expected_revision=revision - 1,
                idempotency_key=uuid.uuid4(), admin_id=ADMIN_ID, session_id=uuid.uuid4(),
                device_id=DEVICE_ID, correlation_id=uuid.uuid4(),
            ))
    return report_id, event_ids


@_PG
def test_s18_40_f1_audit_list_shows_the_correction_and_marks_the_corrected_event(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    clean_test_storage,
) -> None:
    from backend.app.models import AdminOperationAudit
    from backend.app.schemas import ReportInstitutionDeliveryCorrectionRequest
    from backend.app.services import admin_report_workflow as workflow
    from backend.app.services.admin_audit_projection import (
        admin_audit_filter_sha256,
        decode_admin_audit_cursor,
        encode_admin_audit_cursor,
        project_admin_audit_event,
        query_admin_audit_events,
    )

    clean_test_storage()
    engine, SessionFactory, run_as_runtime, *_ = _pg_environment(tmp_path)
    at = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(minutes=5)
    identity = _identity()
    try:
        report_id, event_ids = _seed_plain_delivery_history(SessionFactory, at)
        # Predecessor ORM path (superuser, no runtime proof): same checks as the PG function.
        monkeypatch.setattr(workflow, "admin_report_integrity_boundary_state", lambda _db: None)
        payload = ReportInstitutionDeliveryCorrectionRequest.model_validate(_correction_body(
            correction_id=str(uuid.uuid4()), corrects_event_id=str(event_ids[2]),
            expected_revision=3, reason_code="WRONG_REPORT",
        ))
        correlation_id = uuid.uuid4()
        with SessionFactory() as db:
            created = workflow.correct_report_institution_delivery_event(
                db, report_id=report_id, payload=payload, identity=identity,
                correlation_id=correlation_id,
            )
        with SessionFactory() as db:
            replay = workflow.correct_report_institution_delivery_event(
                db, report_id=report_id, payload=payload, identity=identity,
                correlation_id=uuid.uuid4(),
            )
        assert replay.id == created.id

        # Success operation audit, once (an exact replay changes nothing).
        with SessionFactory() as db:
            audits = db.execute(
                select(
                    AdminOperationAudit.outcome, AdminOperationAudit.resource_type,
                    AdminOperationAudit.result_count, AdminOperationAudit.error_code,
                    AdminOperationAudit.actor_id, AdminOperationAudit.session_id,
                    AdminOperationAudit.device_id, AdminOperationAudit.correlation_id,
                ).where(
                    AdminOperationAudit.operation == "report.delivery.correct",
                    AdminOperationAudit.resource_id == str(created.id),
                )
            ).all()
        assert [tuple(row) for row in audits] == [(
            "SUCCEEDED", "delivery_correction", 1, None, ADMIN_ID,
            identity.session_id, DEVICE_ID, correlation_id,
        )]

        def audit_rows(**overrides: object):
            arguments: dict[str, object] = {
                "limit": 25, "cursor": None, "event_type": "DELIVERY", "actor_id": None,
            }
            arguments.update(overrides)
            return run_as_runtime(lambda db: query_admin_audit_events(db, **arguments))

        rows = audit_rows()
        by_id = {row.event_id: row for row in rows}
        assert set(by_id) == {str(event_id) for event_id in event_ids} | {str(created.id)}
        shown = by_id[str(created.id)]
        assert (
            shown.event_type, shown.action, shown.outcome, shown.actor_id,
            shown.resource_type, shown.resource_id, shown.occurred_at,
            shown.correlation_id, shown.source_rank,
        ) == (
            "DELIVERY", "report.delivery.correct", "SUCCEEDED", ADMIN_ID,
            "report", str(report_id), created.created_at, correlation_id, 2,
        )
        assert [by_id[str(event_id)].action for event_id in event_ids] == [
            "report.delivery.SUBMITTED",
            "report.delivery.ACKNOWLEDGED",
            "report.delivery.RESOLVED.corrected",
        ]
        assert project_admin_audit_event(shown).action == "report.delivery.correct"
        assert str(created.id) in {row.event_id for row in audit_rows(event_type=None)}
        assert audit_rows(actor_id="other.admin") == []

        # Paging one row at a time visits every row exactly once, in order.
        digest = admin_audit_filter_sha256(event_type="DELIVERY", actor_id=None)
        seen: list[str] = []
        cursor = None
        while True:
            page = audit_rows(limit=1, cursor=cursor)
            seen.append(page[0].event_id)
            if len(page) < 2:
                break
            cursor = decode_admin_audit_cursor(
                encode_admin_audit_cursor(row=page[0], filter_sha256=digest),
                expected_filter_sha256=digest,
            )
        assert seen == [row.event_id for row in rows]
    finally:
        clean_test_storage()
        engine.dispose()


@_PG
def test_s18_40_f5_boundary_detects_correction_key_and_trigger_drift(
    clean_test_storage,
) -> None:
    from backend.app.services.admin_report_integrity import (
        admin_report_integrity_boundary_state,
    )

    clean_test_storage()
    table = "public.report_institution_delivery_corrections"
    trigger = "report_institution_delivery_corrections_append_only"
    drop_pk = f"ALTER TABLE {table} DROP CONSTRAINT pk_report_delivery_corrections"
    drop_challenge = (
        f"ALTER TABLE {table} DROP CONSTRAINT uq_report_delivery_correction_challenge"
    )
    drop_reconfirmation = (
        f"ALTER TABLE {table} DROP CONSTRAINT uq_report_delivery_correction_reconfirmation"
    )
    drifts = {
        "pk dropped": [drop_pk],
        "pk on another column": [
            drop_pk,
            f"ALTER TABLE {table} ADD CONSTRAINT pk_report_delivery_corrections "
            "PRIMARY KEY (id)",
        ],
        "challenge unique dropped": [drop_challenge],
        "challenge unique widened": [
            drop_challenge,
            f"ALTER TABLE {table} ADD CONSTRAINT uq_report_delivery_correction_challenge "
            "UNIQUE (proof_challenge_id, id)",
        ],
        "reconfirmation unique dropped": [drop_reconfirmation],
        "reconfirmation unique deferrable": [
            drop_reconfirmation,
            f"ALTER TABLE {table} ADD CONSTRAINT "
            "uq_report_delivery_correction_reconfirmation UNIQUE (reconfirmation_id) "
            "DEFERRABLE INITIALLY IMMEDIATE",
        ],
        "append-only trigger dropped": [f"DROP TRIGGER {trigger} ON {table}"],
        "append-only trigger disabled": [f"ALTER TABLE {table} DISABLE TRIGGER {trigger}"],
        "append-only trigger narrowed": [
            f"DROP TRIGGER {trigger} ON {table}",
            f"CREATE TRIGGER {trigger} BEFORE DELETE ON {table} "
            "FOR EACH STATEMENT EXECUTE FUNCTION public.walksafe_reject_audit_mutation()",
        ],
    }
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            outer = connection.begin()
            try:
                assert admin_report_integrity_boundary_state(connection) is True
                for name, statements in drifts.items():
                    savepoint = connection.begin_nested()
                    for statement in statements:
                        connection.execute(text(statement))
                    assert admin_report_integrity_boundary_state(connection) is False, name
                    savepoint.rollback()
                assert admin_report_integrity_boundary_state(connection) is True
            finally:
                outer.rollback()
        with engine.connect() as connection:
            assert admin_report_integrity_boundary_state(connection) is True
    finally:
        engine.dispose()
