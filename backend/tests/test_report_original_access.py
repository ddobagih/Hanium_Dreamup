from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import hashlib
import importlib
import os
from pathlib import Path
import secrets
import threading
import time
from types import SimpleNamespace
import uuid

from alembic import command
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.operations import Operations
from geoalchemy2 import WKTElement
import pytest
from pydantic import ValidationError
from sqlalchemy import func, select, text
from sqlalchemy.exc import SQLAlchemyError

import backend.app.services.report_original_access as report_original_access
import backend.app.uploads as uploads
from backend.app.database import SessionLocal
from backend.app.main import report_image_key_manager
from backend.app.models import (
    AdminOperationAudit,
    AdminSecurityControl,
    AdminSecurityReconfirmation,
    AdminSecuritySession,
    AdminDeviceProofChallenge,
    Report,
    ReportExportAudit,
    ReportImageObject,
    ReportImageKeyringEvent,
    ReportOriginalAccessAudit,
    ReportOriginalAccessGrant,
    ReportReviewDecision,
)
from backend.app.schemas import (
    AdminReportPackageCreateRequest,
    ReportInstitutionDeliveryRequest,
    ReportOriginalAccessGrantRequest,
    ReportOriginalAccessGrantResponse,
    ReportReviewDecisionRequest,
)
from backend.app.services.admin_report_workflow import (
    AdminReportWorkflowError,
    append_report_institution_delivery_event,
    append_report_review_decision,
)
from backend.app.services.admin_report_delivery_package import (
    create_admin_report_delivery_package,
)
from backend.app.services.admin_security import (
    AdminSecurityService,
    AdminSessionIdentity,
    provision_admin_security,
)
from backend.app.services.report_image_crypto import ReportImageCryptoError, encrypt_report_image
from backend.app.services.report_image_keys import ReportImageKeyUnavailable
from backend.app.services.privacy_lifecycle import assert_privacy_runtime_database_role
from backend.app.services.report_original_access import (
    ReportOriginalAccessError,
    _lock_and_revalidate_admin_session,
    _read_envelope,
    access_report_original,
    issue_report_original_access_grant,
)
from backend.app.uploads import write_image_file


ROOT = Path(__file__).resolve().parents[2]
TEST_DATABASE_CONFIGURED = bool(os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip())
TEST_CREDENTIAL_ISSUER_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
TEST_TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"


def test_original_access_uri_is_intentionally_v2_only_and_response_is_nested() -> None:
    legacy_request = {
        "purpose": "report_review",
        "reason": "Review the original report evidence.",
    }
    with pytest.raises(ValidationError):
        ReportOriginalAccessGrantRequest.model_validate(legacy_request)

    request = ReportOriginalAccessGrantRequest.model_validate(
        {**legacy_request, "expected_content_revision": 3}
    )
    assert request.expected_content_revision == 3

    response = ReportOriginalAccessGrantResponse.model_validate(
        {
            "schema_version": "walksafe.report-original-access-grant.v2",
            "grant_id": str(uuid.uuid4()),
            "content_revision": 3,
            "expires_at": "2026-08-30T12:00:00Z",
            "exact_location": {
                "lat": 37.5665,
                "lon": 126.978,
                "accuracy": None,
            },
            "image": {
                "resource_path": f"/uploads/{uuid.uuid4()}.jpg",
                "content_type": "image/jpeg",
                "sha256": "a" * 64,
                "byte_count": 123,
                "access_token": "A" * 43,
            },
        }
    )
    assert set(response.model_dump()) == {
        "schema_version",
        "grant_id",
        "content_revision",
        "expires_at",
        "exact_location",
        "image",
    }
    assert "access_token" not in response.model_dump()


@pytest.mark.parametrize("whitespace", ["\u00a0", "\u2003", "\u202f"])
def test_high_risk_evidence_requests_preserve_existing_whitespace_normalization(
    whitespace: str,
) -> None:
    grant = ReportOriginalAccessGrantRequest.model_validate(
        {
            "purpose": "report_review",
            "reason": f"{whitespace}Review{whitespace}original evidence.",
            "expected_content_revision": 0,
        }
    )
    assert grant.reason == "Review original evidence."
    review = ReportReviewDecisionRequest.model_validate(
        {
            "decision_id": str(uuid.uuid4()),
            "decision": "REJECTED",
            "reason": f"{whitespace}Review rejected.{whitespace}",
            "user_visible_reason": "기관 전달 대상이 아닙니다.",
            "duplicate_of_report_id": None,
            "location_reviewed": True,
            "photo_reviewed": True,
            "privacy_reviewed": True,
            "content_revision": 0,
            "evidence_grant_id": None,
        }
    )
    assert review.reason == "Review rejected."
    delivery = ReportInstitutionDeliveryRequest.model_validate(
        {
            "institution": f"{whitespace}보행환경 담당 기관{whitespace}",
            "channel": "official_document",
            "recipient": "안전관리 담당자",
            "status": "FAILED",
            "external_receipt_id": None,
            "reason": "수동 제출 실패 사실을 기록함",
            "evidence_sha256": None,
            "observed_at": "2026-08-30T12:00:00Z",
            "package_revision": 1,
            "expected_revision": 0,
            "idempotency_key": str(uuid.uuid4()),
        }
    )
    assert delivery.institution == "보행환경 담당 기관"


def test_review_request_rejects_noncanonical_uuid_text() -> None:
    with pytest.raises(ValidationError):
        ReportReviewDecisionRequest.model_validate(
            {
                "decision_id": str(uuid.uuid4()),
                "decision": "DUPLICATE",
                "reason": "Duplicate report was reviewed.",
                "user_visible_reason": "중복 신고입니다.",
                "duplicate_of_report_id": (
                    "urn:uuid:11111111-1111-4111-8111-111111111111"
                ),
                "location_reviewed": True,
                "photo_reviewed": True,
                "privacy_reviewed": True,
                "content_revision": 0,
                "evidence_grant_id": None,
            }
        )


@pytest.mark.skipif(
    not TEST_DATABASE_CONFIGURED,
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_database_text_normalizers_match_python_and_pydantic_whitespace() -> None:
    split_codepoints = [
        *range(0x09, 0x0E),
        *range(0x1C, 0x20),
        0x20,
        0x85,
        0xA0,
        0x1680,
        *range(0x2000, 0x200B),
        0x2028,
        0x2029,
        0x202F,
        0x205F,
        0x3000,
    ]
    strip_codepoints = [
        codepoint
        for codepoint in split_codepoints
        if codepoint not in {0x0B, 0x0C} and codepoint not in range(0x1C, 0x20)
    ]
    with SessionLocal() as db:
        for codepoint in split_codepoints:
            whitespace = chr(codepoint)
            raw = f"{whitespace}Review{whitespace}original evidence.{whitespace}"
            payload = ReportOriginalAccessGrantRequest(
                purpose="report_review",
                reason=raw,
                expected_content_revision=0,
            )
            normalized = db.scalar(
                text("SELECT public.walksafe_python_split_join(:value)"),
                {"value": raw},
            )
            assert normalized == payload.reason == "Review original evidence."
        for codepoint in strip_codepoints:
            whitespace = chr(codepoint)
            raw = f"{whitespace}Review rejected.{whitespace}"
            payload = ReportReviewDecisionRequest(
                decision_id=uuid.uuid4(),
                decision="REJECTED",
                reason=raw,
                user_visible_reason="기관 전달 대상이 아닙니다.",
                duplicate_of_report_id=None,
                location_reviewed=True,
                photo_reviewed=True,
                privacy_reviewed=True,
                content_revision=0,
                evidence_grant_id=None,
            )
            normalized = db.scalar(
                text("SELECT public.walksafe_python_strip(:value)"),
                {"value": raw},
            )
            assert normalized == payload.reason == "Review rejected."


def test_original_evidence_storage_has_bindings_but_no_sensitive_audit_fields() -> None:
    grant_columns = set(ReportOriginalAccessGrant.__table__.columns.keys())
    assert {
        "content_revision",
        "location_disclosed_at",
        "access_granted_at",
        "review_decision_id",
        "review_bound_at",
    } <= grant_columns
    assert "evidence_grant_id" in ReportReviewDecision.__table__.columns
    audit_columns = set(ReportOriginalAccessAudit.__table__.columns.keys())
    assert audit_columns.isdisjoint(
        {
            "latitude",
            "longitude",
            "accuracy_m",
            "access_token",
            "resource_path",
            "image_sha256",
            "image_bytes",
        }
    )


def test_grant_response_is_withheld_when_location_disclosure_audit_commit_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    report_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    report = Report(
        id=report_id,
        content_revision=2,
        latitude=37.5665,
        longitude=126.978,
        accuracy_m=3.5,
        image_path=f"/uploads/{report_id}.jpg",
        image_content_type="image/jpeg",
    )
    image_object = ReportImageObject(
        report_id=report_id,
        storage_name=f"{report_id}.wse",
        envelope_version=1,
        algorithm="AES-256-GCM",
        aad_version=1,
        key_id="test-key",
        nonce=b"0" * 12,
        plaintext_sha256="a" * 64,
        plaintext_size=123,
        envelope_sha256="b" * 64,
        envelope_size=256,
        content_type="image/jpeg",
    )

    class Result:
        def scalar_one_or_none(self) -> Report:
            return report

    class FakeSession:
        def __init__(self) -> None:
            self.added: list[object] = []
            self.rollback_count = 0

        def execute(self, _statement: object) -> Result:
            return Result()

        def get(self, model: object, _key: object) -> object | None:
            return image_object if model is ReportImageObject else None

        def add(self, value: object) -> None:
            self.added.append(value)

        def flush(self) -> None:
            grant = next(
                item
                for item in self.added
                if isinstance(item, ReportOriginalAccessGrant)
            )
            grant.id = uuid.UUID("22222222-2222-4222-8222-222222222222")

        def commit(self) -> None:
            raise RuntimeError("simulated audit failure")

        def rollback(self) -> None:
            self.rollback_count += 1

    class KeyManager:
        def synchronize(self, _db: object) -> None:
            return None

        def decryption_key(self, _key_id: str) -> bytes:
            return b"k" * 32

    raw_token = "A" * 43
    monkeypatch.setattr(report_original_access.secrets, "token_urlsafe", lambda _size: raw_token)
    db = FakeSession()

    with pytest.raises(ReportOriginalAccessError) as captured:
        issue_report_original_access_grant(
            db,  # type: ignore[arg-type]
            report_id=report_id,
            purpose="report_review",
            reason="Review the original report evidence.",
            expected_content_revision=2,
            identity=_identity("grant-audit"),
            ttl_seconds=120,
            key_manager=KeyManager(),  # type: ignore[arg-type]
        )

    assert captured.value.code == "report_original_access_audit_unavailable"
    assert db.rollback_count == 1
    stored_grant = next(
        item for item in db.added if isinstance(item, ReportOriginalAccessGrant)
    )
    assert stored_grant.token_sha256 == hashlib.sha256(raw_token.encode()).hexdigest()
    assert raw_token not in repr(vars(stored_grant))
    audits = [item for item in db.added if isinstance(item, ReportOriginalAccessAudit)]
    assert [audit.action for audit in audits] == [
        "GRANT_ISSUED",
        "LOCATION_DISCLOSED",
    ]
    assert all(raw_token not in repr(vars(audit)) for audit in audits)


def test_original_evidence_v2_migration_follows_deletion_lock_head() -> None:
    migration = (
        ROOT
        / "backend/alembic/versions/202608300002_report_original_evidence_v2.py"
    ).read_text(encoding="utf-8")
    assert 'revision = "202608300002"' in migration
    assert 'down_revision = "202608300001"' in migration
    assert "evidence_grant_id" in migration
    assert "access_granted_at" in migration
    assert "location_disclosed_at" in migration
    assert "NOT VALID" in migration


def test_original_evidence_acl_migration_follows_restore_tombstone_head() -> None:
    migration = (
        ROOT
        / "backend/alembic/versions/202608300004_admin_original_evidence_acl.py"
    ).read_text(encoding="utf-8")
    assert 'revision = "202608300004"' in migration
    assert 'down_revision = "202608300003"' in migration
    assert "walksafe_report_evidence_owner" in migration
    assert "SECURITY DEFINER" in migration
    assert "SET search_path = pg_catalog, pg_temp" in migration
    assert "REVOKE INSERT, UPDATE, DELETE, TRUNCATE" in migration
    assert "walksafe_prepare_report_original_evidence_access" in migration
    assert "walksafe_complete_report_original_evidence_access" in migration
    assert "walksafe_append_report_review_decision" in migration


def test_original_evidence_v2_downgrade_guard_precedes_destructive_ddl() -> None:
    migration = (
        ROOT
        / "backend/alembic/versions/202608300002_report_original_evidence_v2.py"
    ).read_text(encoding="utf-8")
    downgrade = migration.split("def downgrade() -> None:", 1)[1]
    assert "USING ERRCODE = '55000'" in downgrade
    assert downgrade.index("USING ERRCODE = '55000'") < downgrade.index(
        "op.drop_constraint("
    )
    assert "LOCATION_DISCLOSED" in downgrade
    assert "evidence_grant_id IS NOT NULL" in downgrade


def test_envelope_reader_accepts_read_only_backup_group_mode(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o2750)
    monkeypatch.setattr(
        uploads.grp,
        "getgrnam",
        lambda _name: type("Group", (), {"gr_gid": tmp_path.stat().st_gid})(),
    )
    path = tmp_path / "report.wse"
    write_image_file(path, b"encrypted-envelope")

    assert _read_envelope(path, len(b"encrypted-envelope")) == b"encrypted-envelope"
    assert path.stat().st_mode & 0o777 == 0o640


def test_envelope_reader_rejects_object_acl(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = tmp_path / "report.wse"
    write_image_file(path, b"encrypted-envelope")
    identity = (path.stat().st_dev, path.stat().st_ino)
    monkeypatch.setattr(
        report_original_access.os,
        "listxattr",
        lambda descriptor: (
            [b"system.posix_acl_access"]
            if (os.fstat(descriptor).st_dev, os.fstat(descriptor).st_ino)
            == identity
            else []
        ),
    )

    with pytest.raises(ReportImageCryptoError, match="metadata changed"):
        _read_envelope(path, len(b"encrypted-envelope"))


def test_envelope_reader_rejects_upload_root_acl(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = tmp_path / "report.wse"
    write_image_file(path, b"encrypted-envelope")
    identity = (tmp_path.stat().st_dev, tmp_path.stat().st_ino)
    monkeypatch.setattr(
        report_original_access.os,
        "listxattr",
        lambda descriptor: (
            [b"system.posix_acl_default"]
            if (os.fstat(descriptor).st_dev, os.fstat(descriptor).st_ino)
            == identity
            else []
        ),
    )

    with pytest.raises(ReportImageCryptoError, match="upload root"):
        _read_envelope(path, len(b"encrypted-envelope"))


def test_envelope_reader_revalidates_upload_root_after_read(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = tmp_path / "report.wse"
    write_image_file(path, b"encrypted-envelope")
    real_read = report_original_access.os.read
    drifted = False

    def read_and_drift(descriptor: int, size: int) -> bytes:
        nonlocal drifted
        content = real_read(descriptor, size)
        if not drifted:
            drifted = True
            tmp_path.chmod(0o755)
        return content

    monkeypatch.setattr(report_original_access.os, "read", read_and_drift)

    with pytest.raises(ReportImageCryptoError, match="upload root"):
        _read_envelope(path, len(b"encrypted-envelope"))

    assert drifted is True


@pytest.fixture(scope="module", autouse=True)
def migrated_database() -> None:
    if not TEST_DATABASE_CONFIGURED:
        return
    command.upgrade(Config(str(ROOT / "backend" / "alembic.ini")), "head")


def _identity(label: str = "one") -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id="walksafe.admin",
        session_id=uuid.uuid5(uuid.NAMESPACE_URL, f"walksafe-test-session-{label}"),
        device_id=f"android-admin-{label}",
        device_label=f"Admin device {label}",
        expires_at=datetime.now(timezone.utc) + timedelta(hours=1),
        step_up_verified_at=datetime.now(timezone.utc),
    )


def _record_consumed_action_proof(
    *,
    identity: AdminSessionIdentity,
    report_id: uuid.UUID,
    action: str,
    request_body: bytes,
    correlation_id: uuid.UUID | None = None,
    reconfirmation: bool = False,
) -> tuple[uuid.UUID, uuid.UUID, str | None]:
    observed_at = datetime.now(timezone.utc)
    challenge_id = uuid.uuid4()
    resolved_correlation_id = correlation_id or uuid.uuid4()
    path = {
        "report.original.grant": f"/reports/{report_id}/original-access-grants",
        "report.review.decide": f"/reports/{report_id}/review-decisions",
        "admin.report.delivery_package.create": (
            f"/admin/reports/{report_id}/delivery-packages"
        ),
        "report.delivery.create": f"/reports/{report_id}/deliveries",
    }[action]
    nonce_sha256 = hashlib.sha256(
        f"reconfirm-{uuid.uuid4()}".encode("ascii")
    ).hexdigest()
    with SessionLocal.begin() as db:
        db.add(
            AdminDeviceProofChallenge(
                id=challenge_id,
                challenge_type="ACTION",
                action=action,
                admin_id=identity.admin_id,
                body_sha256=hashlib.sha256(request_body).hexdigest(),
                correlation_id=resolved_correlation_id,
                device_id=identity.device_id,
                device_key_marker="b" * 64,
                device_key_version=1,
                expires_at=observed_at + timedelta(minutes=2),
                issued_at=observed_at - timedelta(seconds=1),
                method="POST",
                nonce=secrets.token_urlsafe(32),
                purpose="ACTION",
                path=path,
                query_sha256=hashlib.sha256(b"").hexdigest(),
                read_purpose=None,
                schema_version="walksafe.admin-device-proof.v2",
                session_id=identity.session_id,
                signing_payload="{}",
                consumed_at=observed_at,
            )
        )
        if reconfirmation:
            db.add(
                AdminSecurityReconfirmation(
                    admin_id=identity.admin_id,
                    session_id=identity.session_id,
                    device_id=identity.device_id,
                    action=action,
                    method="POST",
                    path=path,
                    nonce_sha256=nonce_sha256,
                    verified_at=observed_at - timedelta(seconds=1),
                    expires_at=observed_at + timedelta(minutes=2),
                    consumed_at=observed_at,
                )
            )
    return (
        challenge_id,
        resolved_correlation_id,
        nonce_sha256 if reconfirmation else None,
    )


def test_original_access_revalidates_control_before_session_row_lock() -> None:
    identity = _identity("lock-order")
    control = AdminSecurityControl(
        admin_id=identity.admin_id,
        password_hash="test-password-hash",
        totp_secret_fingerprint="0" * 64,
        security_state="NORMAL",
        state_version=1,
    )
    session = AdminSecuritySession(
        id=identity.session_id,
        admin_id=identity.admin_id,
        device_id=identity.device_id,
        device_label=identity.device_label,
        token_sha256="1" * 64,
        issued_at=datetime.now(timezone.utc) - timedelta(minutes=1),
        expires_at=identity.expires_at,
        last_seen_at=datetime.now(timezone.utc),
    )
    statements: list[str] = []

    class Result:
        def __init__(self, value) -> None:
            self.value = value

        def scalars(self):
            return self

        def all(self):
            return self.value

        def scalar_one_or_none(self):
            return self.value

    class FakeSession:
        def get_bind(self):
            return type("Bind", (), {"dialect": type("Dialect", (), {"name": "sqlite"})()})()

        def execute(self, statement):
            statements.append(str(statement))
            if len(statements) == 1:
                return Result([control])
            return Result(session)

    _lock_and_revalidate_admin_session(  # type: ignore[arg-type]
        FakeSession(),
        identity=identity,
        report_id=uuid.uuid4(),
        observed_at=datetime.now(timezone.utc),
    )

    assert "admin_security_controls" in statements[0]
    assert "FOR UPDATE" in statements[0]
    assert "admin_security_sessions" in statements[1]
    assert "FOR UPDATE" in statements[1]


def _store_encrypted_report(upload_root: Path, plaintext: bytes = b"private-report-image") -> uuid.UUID:
    report_id = uuid.uuid4()
    slot = report_image_key_manager.encryption_slot()
    assert slot.material is not None
    encrypted = encrypt_report_image(
        plaintext,
        report_id=report_id,
        content_type="image/jpeg",
        key_id=slot.key_id,
        key=slot.material,
    )
    storage_name = f"{report_id}.wse"
    write_image_file(upload_root / storage_name, encrypted.envelope)
    report = Report(
        id=report_id,
        status="new",
        class_id=0,
        class_name="damaged_tactile_block",
        confidence=0.9,
        bbox_x=0.1,
        bbox_y=0.1,
        bbox_width=0.2,
        bbox_height=0.2,
        captured_at=datetime.now(timezone.utc),
        source="fake",
        latitude=37.5665,
        longitude=126.978,
        accuracy_m=4.5,
        location=WKTElement("POINT(126.978 37.5665)", srid=4326),
        image_path=f"/uploads/{report_id}.jpg",
        image_content_type="image/jpeg",
        payload={"image_sha256": encrypted.plaintext_sha256},
    )
    with SessionLocal() as db:
        report_image_key_manager.synchronize(db)
        db.add(report)
        db.add(
            ReportImageObject(
                report_id=report_id,
                storage_name=storage_name,
                envelope_version=1,
                algorithm="AES-256-GCM",
                aad_version=1,
                key_id=encrypted.key_id,
                nonce=encrypted.nonce,
                plaintext_sha256=encrypted.plaintext_sha256,
                plaintext_size=encrypted.plaintext_length,
                envelope_sha256=encrypted.envelope_sha256,
                envelope_size=len(encrypted.envelope),
                content_type="image/jpeg",
            )
        )
        db.commit()
    return report_id


def _prepare_identity(
    identity: AdminSessionIdentity,
    *,
    observed_at: datetime,
) -> None:
    with SessionLocal() as db:
        if db.get(AdminSecurityControl, identity.admin_id) is None:
            provision_admin_security(
                db,
                admin_id=identity.admin_id,
                password="report original test password",
                totp_secret=TEST_TOTP_SECRET,
                recovery_codes=["REPORT-ORIGINAL-RECOVERY-0001"],
                credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                now=observed_at,
            )
    with SessionLocal.begin() as db:
        if db.get(AdminSecuritySession, identity.session_id) is None:
            db.add(
                AdminSecuritySession(
                    id=identity.session_id,
                    admin_id=identity.admin_id,
                    device_id=identity.device_id,
                    device_label=identity.device_label,
                    token_sha256=hashlib.sha256(
                        f"test-token-{identity.session_id}".encode()
                    ).hexdigest(),
                    issued_at=observed_at - timedelta(minutes=1),
                    expires_at=identity.expires_at,
                    step_up_verified_at=identity.step_up_verified_at,
                    last_seen_at=observed_at,
                )
            )
    with SessionLocal() as db:
        control = db.get(AdminSecurityControl, identity.admin_id)
        assert control is not None
        if control.recovery_custody_state != "ATTESTED":
            service = AdminSecurityService(
                db,
                SimpleNamespace(admin_totp_secret=TEST_TOTP_SECRET),
            )
            service._cached_credential_issuer_key = TEST_CREDENTIAL_ISSUER_KEY
            service.attest_recovery_custody(
                identity,
                custody_reference=TEST_CREDENTIAL_ISSUER_KEY,
                material_kind="RECOVERY_CODE",
                storage_location="OFF_PHONE",
                separate_encrypted_backup_confirmed=True,
                now=observed_at,
            )


def _issue(
    report_id: uuid.UUID,
    identity: AdminSessionIdentity,
    *,
    now: datetime | None = None,
    ttl_seconds: int = 120,
):
    observed_at = now or datetime.now(timezone.utc)
    _prepare_identity(identity, observed_at=observed_at)
    payload = ReportOriginalAccessGrantRequest(
        purpose="report_review",
        reason="Review the reported tactile-block damage.",
        expected_content_revision=0,
    )
    request_body = payload.model_dump_json().encode("utf-8")
    proof_challenge_id, _, reconfirmation_nonce_sha256 = (
        _record_consumed_action_proof(
            identity=identity,
            report_id=report_id,
            action="report.original.grant",
            request_body=request_body,
            reconfirmation=True,
        )
    )
    with SessionLocal() as db:
        return issue_report_original_access_grant(
            db,
            report_id=report_id,
            purpose=payload.purpose,
            reason=payload.reason,
            expected_content_revision=payload.expected_content_revision,
            identity=identity,
            ttl_seconds=ttl_seconds,
            key_manager=report_image_key_manager,
            proof_challenge_id=proof_challenge_id,
            proof_request_body=request_body,
            reconfirmation_nonce_sha256=reconfirmation_nonce_sha256,
            runtime_totp_secret=TEST_TOTP_SECRET,
            credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
            now=observed_at,
        )


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_purpose_bound_grant_returns_original_once_and_audits_before_release(tmp_path: Path) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identity = _identity()
    grant = _issue(report_id, identity)

    with SessionLocal() as db:
        accessed = access_report_original(
            db,
            upload_root=tmp_path,
            filename=f"{report_id}.jpg",
            raw_access_token=grant.access_token,
            identity=identity,
            key_manager=report_image_key_manager,
            runtime_totp_secret=TEST_TOTP_SECRET,
            credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
        )
    assert accessed.content == b"private-report-image"
    assert grant.content_revision == 0
    assert grant.latitude == 37.5665
    assert grant.longitude == 126.978
    assert grant.accuracy_m == 4.5
    assert grant.resource_path == f"/uploads/{report_id}.jpg"
    assert grant.content_type == "image/jpeg"
    assert grant.image_sha256 == hashlib.sha256(b"private-report-image").hexdigest()
    assert grant.image_byte_count == len(b"private-report-image")

    with SessionLocal() as db:
        with pytest.raises(ReportOriginalAccessError) as reused:
            access_report_original(
                db,
                upload_root=tmp_path,
                filename=f"{report_id}.jpg",
                raw_access_token=grant.access_token,
                identity=identity,
                key_manager=report_image_key_manager,
                runtime_totp_secret=TEST_TOTP_SECRET,
                credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
            )
        audits = list(
            db.scalars(
                select(ReportOriginalAccessAudit)
                .where(ReportOriginalAccessAudit.grant_id == grant.grant_id)
                .order_by(ReportOriginalAccessAudit.created_at)
            )
        )
    assert reused.value.status_code == 403
    assert [audit.action for audit in audits] == [
        "GRANT_ISSUED",
        "LOCATION_DISCLOSED",
        "ACCESS_GRANTED",
        "ACCESS_DENIED",
    ]


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_runtime_role_cannot_forge_original_evidence_direct_dml() -> None:
    engine = SessionLocal.kw["bind"]
    statements = (
        "INSERT INTO report_original_access_grants DEFAULT VALUES",
        "UPDATE report_original_access_grants SET reason = reason",
        "INSERT INTO report_original_access_audits DEFAULT VALUES",
        "UPDATE report_original_access_audits SET reason_code = reason_code",
        "INSERT INTO report_review_decisions DEFAULT VALUES",
        "UPDATE report_review_decisions SET reason = reason",
        "INSERT INTO report_delivery_packages DEFAULT VALUES",
        "UPDATE report_delivery_packages SET revision = revision",
        "INSERT INTO report_institution_delivery_events DEFAULT VALUES",
        "UPDATE report_institution_delivery_events SET revision = revision",
    )

    with engine.connect() as connection:
        connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
        connection.commit()
        try:
            for statement in statements:
                transaction = connection.begin()
                try:
                    with pytest.raises(SQLAlchemyError) as rejected:
                        connection.execute(text(statement))
                    assert getattr(rejected.value.orig, "sqlstate", None) == "42501"
                finally:
                    transaction.rollback()
            with SessionLocal(bind=connection) as runtime_db:
                assert_privacy_runtime_database_role(runtime_db)
        finally:
            if connection.in_transaction():
                connection.rollback()
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            connection.commit()

    with engine.connect() as connection:
        public_dml_acl_count = connection.execute(
            text(
                "SELECT ("
                "SELECT count(*) FROM pg_catalog.pg_class AS relation "
                "CROSS JOIN LATERAL pg_catalog.aclexplode(coalesce("
                "relation.relacl, pg_catalog.acldefault('r', relation.relowner)"
                ")) AS acl WHERE relation.oid IN ("
                "'public.report_original_access_grants'::regclass, "
                "'public.report_original_access_audits'::regclass, "
                "'public.report_review_decisions'::regclass, "
                "'public.report_delivery_packages'::regclass, "
                "'public.report_institution_delivery_events'::regclass) "
                "AND acl.grantee = 0 AND acl.privilege_type IN ("
                "'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')) + ("
                "SELECT count(*) FROM pg_catalog.pg_attribute AS attribute "
                "CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) AS acl "
                "WHERE attribute.attrelid IN ("
                "'public.report_original_access_grants'::regclass, "
                "'public.report_original_access_audits'::regclass, "
                "'public.report_review_decisions'::regclass, "
                "'public.report_delivery_packages'::regclass, "
                "'public.report_institution_delivery_events'::regclass) "
                "AND attribute.attnum > 0 AND NOT attribute.attisdropped "
                "AND acl.grantee = 0 AND acl.privilege_type IN ("
                "'INSERT', 'UPDATE'))"
            )
        ).scalar_one()
    assert public_dml_acl_count == 0


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_v2_downgrade_refuses_before_ddl_and_preserves_evidence(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    report_id = _store_encrypted_report(tmp_path)
    grant = _issue(report_id, _identity("downgrade-guard"))
    migration = importlib.import_module(
        "backend.alembic.versions.202608300002_report_original_evidence_v2"
    )
    engine = SessionLocal.kw["bind"]

    with engine.connect() as connection:
        transaction = connection.begin()
        monkeypatch.setattr(
            migration,
            "op",
            Operations(MigrationContext.configure(connection)),
        )
        try:
            with pytest.raises(SQLAlchemyError) as rejected:
                migration.downgrade()
            assert getattr(rejected.value.orig, "sqlstate", None) == "55000"
        finally:
            transaction.rollback()

    with engine.connect() as connection:
        assert connection.execute(
            text(
                "SELECT count(*) FROM report_original_access_grants "
                "WHERE id = :grant_id AND content_revision IS NOT NULL"
            ),
            {"grant_id": grant.grant_id},
        ).scalar_one() == 1
        assert connection.execute(
            text(
                "SELECT count(*) FROM information_schema.columns "
                "WHERE table_schema = 'public' "
                "AND table_name = 'report_review_decisions' "
                "AND column_name = 'evidence_grant_id'"
            )
        ).scalar_one() == 1


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_runtime_role_accesses_original_without_direct_admin_update_privilege(
    tmp_path: Path,
) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identity = _identity("runtime-role")
    grant = _issue(report_id, identity)
    engine = SessionLocal.kw["bind"]

    with engine.connect() as connection:
        connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
        connection.commit()
        try:
            with SessionLocal(bind=connection) as db:
                accessed = access_report_original(
                    db,
                    upload_root=tmp_path,
                    filename=f"{report_id}.jpg",
                    raw_access_token=grant.access_token,
                    identity=identity,
                    key_manager=report_image_key_manager,
                    runtime_totp_secret=TEST_TOTP_SECRET,
                    credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                )
        finally:
            if connection.in_transaction():
                connection.rollback()
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            connection.commit()

    assert accessed.content == b"private-report-image"
    with engine.connect() as privilege_connection:
        runtime_direct_update_privileges = privilege_connection.execute(
            text(
                "SELECT "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_controls', 'UPDATE'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_sessions', 'UPDATE'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.report_image_keyring_events', 'UPDATE')"
            )
        ).one()
    assert tuple(runtime_direct_update_privileges) == (False, False, False)
    with SessionLocal() as db:
        stored_grant = db.get(ReportOriginalAccessGrant, grant.grant_id)
        access_audit = db.scalar(
            select(ReportOriginalAccessAudit).where(
                ReportOriginalAccessAudit.grant_id == grant.grant_id,
                ReportOriginalAccessAudit.action == "ACCESS_GRANTED",
            )
        )
    assert stored_grant is not None and stored_grant.consumed_at is not None
    assert stored_grant.access_granted_at is not None
    assert access_audit is not None
    assert access_audit.plaintext_size is None


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_runtime_functions_issue_access_and_bind_one_approval(tmp_path: Path) -> None:
    report_id = _store_encrypted_report(tmp_path)
    duplicate_report_id = _store_encrypted_report(tmp_path)
    identity = _identity("runtime-full-path")
    _issue(report_id, identity)
    grant_payload = ReportOriginalAccessGrantRequest(
        purpose="report_review",
        reason="Review original evidence through the secured runtime path.",
        expected_content_revision=0,
    )
    grant_body = grant_payload.model_dump_json().encode("utf-8")
    grant_proof_id, _, grant_reconfirmation_sha256 = _record_consumed_action_proof(
        identity=identity,
        report_id=report_id,
        action="report.original.grant",
        request_body=grant_body,
        reconfirmation=True,
    )
    rejected_payload = ReportReviewDecisionRequest(
        decision_id=uuid.uuid4(),
        decision="REJECTED",
        reason="A later typed review rejected institution delivery.",
        user_visible_reason="기관 전달 대상이 아닙니다.",
        duplicate_of_report_id=None,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
        content_revision=0,
        evidence_grant_id=None,
    )
    duplicate_payload = ReportReviewDecisionRequest(
        decision_id=uuid.uuid4(),
        decision="DUPLICATE",
        reason="A later typed review linked the duplicate report.",
        user_visible_reason="중복 신고로 확인됐습니다.",
        duplicate_of_report_id=duplicate_report_id,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
        content_revision=0,
        evidence_grant_id=None,
    )
    engine = SessionLocal.kw["bind"]

    with engine.connect() as connection:
        connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
        connection.commit()
        try:
            with SessionLocal(bind=connection) as db:
                grant = issue_report_original_access_grant(
                    db,
                    report_id=report_id,
                    purpose=grant_payload.purpose,
                    reason=grant_payload.reason,
                    expected_content_revision=grant_payload.expected_content_revision,
                    identity=identity,
                    ttl_seconds=120,
                    key_manager=report_image_key_manager,
                    proof_challenge_id=grant_proof_id,
                    proof_request_body=grant_body,
                    reconfirmation_nonce_sha256=grant_reconfirmation_sha256,
                    runtime_totp_secret=TEST_TOTP_SECRET,
                    credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                )
            with SessionLocal(bind=connection) as db:
                accessed = access_report_original(
                    db,
                    upload_root=tmp_path,
                    filename=f"{report_id}.jpg",
                    raw_access_token=grant.access_token,
                    identity=identity,
                    key_manager=report_image_key_manager,
                    runtime_totp_secret=TEST_TOTP_SECRET,
                    credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                )
            approval_payload = ReportReviewDecisionRequest(
                decision_id=uuid.uuid4(),
                decision="APPROVED",
                reason="Original image and exact location were reviewed.",
                user_visible_reason=None,
                duplicate_of_report_id=None,
                location_reviewed=True,
                photo_reviewed=True,
                privacy_reviewed=True,
                content_revision=0,
                evidence_grant_id=grant.grant_id,
            )
            approval_body = approval_payload.model_dump_json().encode("utf-8")
            approval_proof_id, approval_correlation_id, _ = (
                _record_consumed_action_proof(
                    identity=identity,
                    report_id=report_id,
                    action="report.review.decide",
                    request_body=approval_body,
                )
            )
            with SessionLocal(bind=connection) as db:
                decision = append_report_review_decision(
                    db,
                    report_id=report_id,
                    payload=approval_payload,
                    identity=identity,
                    correlation_id=approval_correlation_id,
                    proof_challenge_id=approval_proof_id,
                    proof_request_body=approval_body,
                    runtime_totp_secret=TEST_TOTP_SECRET,
                    credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                )
            package_payload = AdminReportPackageCreateRequest(
                expected_content_revision=0,
                expected_review_revision=decision.revision,
            )
            package_body = package_payload.model_dump_json().encode("utf-8")
            (
                package_proof_id,
                package_correlation_id,
                package_reconfirmation_sha256,
            ) = (
                _record_consumed_action_proof(
                    identity=identity,
                    report_id=report_id,
                    action="admin.report.delivery_package.create",
                    request_body=package_body,
                    reconfirmation=True,
                )
            )
            with SessionLocal(bind=connection) as db:
                created_package = create_admin_report_delivery_package(
                    db,
                    report_id=report_id,
                    expected_content_revision=0,
                    expected_review_revision=decision.revision,
                    identity=identity,
                    correlation_id=package_correlation_id,
                    query_sha256=hashlib.sha256(b"").hexdigest(),
                    proof_challenge_id=package_proof_id,
                    proof_request_body=package_body,
                    reconfirmation_nonce_sha256=package_reconfirmation_sha256,
                    runtime_totp_secret=TEST_TOTP_SECRET,
                    credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                )
                package_revision = created_package.record.revision
                package_review_decision_id = (
                    created_package.record.review_decision_id
                )
                package_id = created_package.record.id
                package_export_audit_id = created_package.record.export_audit_id
                package_generated_at = created_package.record.generated_at
            delivery_payload = ReportInstitutionDeliveryRequest(
                institution="보행환경 담당 기관",
                channel="official_document",
                recipient="안전관리 담당자",
                status="FAILED",
                external_receipt_id=None,
                reason="수동 제출 시도 전 안전 경계를 검증함",
                evidence_sha256=None,
                observed_at=datetime.now(timezone.utc).isoformat().replace(
                    "+00:00", "Z"
                ),
                package_revision=package_revision,
                expected_revision=0,
                idempotency_key=uuid.uuid4(),
            )
            delivery_body = delivery_payload.model_dump_json().encode("utf-8")
            delivery_proof_id, delivery_correlation_id, _ = (
                _record_consumed_action_proof(
                    identity=identity,
                    report_id=report_id,
                    action="report.delivery.create",
                    request_body=delivery_body,
                )
            )
            mismatched_delivery_payload = delivery_payload.model_copy(
                update={"reason": "서명된 본문과 다른 전달 사유"}
            )
            with SessionLocal(bind=connection) as db:
                with pytest.raises(AdminReportWorkflowError) as proof_rejected:
                    append_report_institution_delivery_event(
                        db,
                        report_id=report_id,
                        payload=mismatched_delivery_payload,
                        identity=identity,
                        correlation_id=delivery_correlation_id,
                        proof_challenge_id=delivery_proof_id,
                        proof_request_body=delivery_body,
                        runtime_totp_secret=TEST_TOTP_SECRET,
                        credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                    )
            assert proof_rejected.value.status_code == 403
            assert proof_rejected.value.code == "admin_device_proof_invalid"
            with SessionLocal(bind=connection) as db:
                delivery = append_report_institution_delivery_event(
                    db,
                    report_id=report_id,
                    payload=delivery_payload,
                    identity=identity,
                    correlation_id=delivery_correlation_id,
                    proof_challenge_id=delivery_proof_id,
                    proof_request_body=delivery_body,
                    runtime_totp_secret=TEST_TOTP_SECRET,
                    credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                )
                delivery_id = delivery.id
                delivery_revision = delivery.revision
            delivery_v3_sql = text(
                "SELECT * FROM public.walksafe_append_report_delivery_event_v3("
                "CAST(:event_id AS uuid), CAST(:report_id AS uuid), "
                "CAST(:package_revision AS bigint), "
                "CAST(:expected_revision AS bigint), "
                "CAST(:idempotency_key AS uuid), CAST(:institution AS text), "
                "CAST(:channel AS text), CAST(:recipient AS text), "
                "CAST(:status AS text), CAST(:external_receipt_id AS text), "
                "CAST(:reason AS text), CAST(:evidence_sha256 AS text), "
                "CAST(:observed_at AS timestamptz), CAST(:admin_id AS text), "
                "CAST(:session_id AS uuid), CAST(:device_id AS text), "
                "CAST(:correlation_id AS uuid), "
                "CAST(:proof_challenge_id AS uuid), "
                "CAST(:proof_request_body AS bytea), "
                "CAST(:runtime_totp_secret AS text), "
                "CAST(:credential_issuer_key AS text))"
            )
            delivery_v3_parameters = {
                "event_id": delivery_id,
                "report_id": report_id,
                "package_revision": package_revision,
                "expected_revision": delivery_payload.expected_revision,
                "idempotency_key": delivery_payload.idempotency_key,
                "institution": delivery_payload.institution,
                "channel": delivery_payload.channel,
                "recipient": delivery_payload.recipient,
                "status": delivery_payload.status,
                "external_receipt_id": delivery_payload.external_receipt_id,
                "reason": delivery_payload.reason,
                "evidence_sha256": delivery_payload.evidence_sha256,
                "observed_at": delivery_payload.observed_at,
                "admin_id": identity.admin_id,
                "session_id": identity.session_id,
                "device_id": identity.device_id,
                "correlation_id": delivery_correlation_id,
                "proof_challenge_id": delivery_proof_id,
                "proof_request_body": delivery_body,
                "runtime_totp_secret": TEST_TOTP_SECRET,
                "credential_issuer_key": TEST_CREDENTIAL_ISSUER_KEY,
            }
            with SessionLocal(bind=connection) as db:
                replayed_delivery = db.execute(
                    delivery_v3_sql, delivery_v3_parameters
                ).mappings().one()
                db.commit()
            changed_body = delivery_body.replace(
                "안전 경계를".encode("utf-8"),
                "다른 요청을".encode("utf-8"),
            )
            with SessionLocal(bind=connection) as db:
                with pytest.raises(SQLAlchemyError) as changed_body_rejected:
                    db.execute(
                        delivery_v3_sql,
                        {**delivery_v3_parameters, "proof_request_body": changed_body},
                    )
                db.rollback()
            assert getattr(changed_body_rejected.value.orig, "sqlstate", None) == "42501"
            rejected_body = rejected_payload.model_dump_json().encode("utf-8")
            rejected_proof_id, rejected_correlation_id, _ = (
                _record_consumed_action_proof(
                    identity=identity,
                    report_id=report_id,
                    action="report.review.decide",
                    request_body=rejected_body,
                )
            )
            with SessionLocal(bind=connection) as db:
                rejected = append_report_review_decision(
                    db,
                    report_id=report_id,
                    payload=rejected_payload,
                    identity=identity,
                    correlation_id=rejected_correlation_id,
                    proof_challenge_id=rejected_proof_id,
                    proof_request_body=rejected_body,
                    runtime_totp_secret=TEST_TOTP_SECRET,
                    credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                )
            duplicate_body = duplicate_payload.model_dump_json().encode("utf-8")
            duplicate_proof_id, duplicate_correlation_id, _ = (
                _record_consumed_action_proof(
                    identity=identity,
                    report_id=report_id,
                    action="report.review.decide",
                    request_body=duplicate_body,
                )
            )
            with SessionLocal(bind=connection) as db:
                duplicate = append_report_review_decision(
                    db,
                    report_id=report_id,
                    payload=duplicate_payload,
                    identity=identity,
                    correlation_id=duplicate_correlation_id,
                    proof_challenge_id=duplicate_proof_id,
                    proof_request_body=duplicate_body,
                    runtime_totp_secret=TEST_TOTP_SECRET,
                    credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                )
        finally:
            if connection.in_transaction():
                connection.rollback()
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            connection.commit()

    assert accessed.content == b"private-report-image"
    assert decision.evidence_grant_id == grant.grant_id
    assert package_review_decision_id == decision.id
    assert delivery.status == "FAILED"
    assert replayed_delivery["result_status"] == "EXISTING"
    assert replayed_delivery["event_id"] == delivery_id
    assert int(replayed_delivery["event_revision"]) == delivery_revision
    with SessionLocal() as db:
        stored_package = db.get(type(created_package.record), package_id)
        export_audit = db.scalar(
            select(ReportExportAudit).where(
                ReportExportAudit.audit_id == package_export_audit_id
            )
        )
        operation_audit = db.scalar(
            select(AdminOperationAudit).where(
                AdminOperationAudit.operation
                == "admin.report.delivery_package.create",
                AdminOperationAudit.resource_id == str(package_id),
            )
        )
    assert stored_package is not None
    assert export_audit is not None
    assert operation_audit is not None
    assert stored_package.generated_at == package_generated_at
    assert export_audit.created_at == package_generated_at
    assert operation_audit.created_at == package_generated_at
    assert rejected.decision == "REJECTED"
    assert rejected.revision == decision.revision + 1
    assert duplicate.decision == "DUPLICATE"
    assert duplicate.revision == rejected.revision + 1
    with SessionLocal() as db:
        stored_grant = db.get(ReportOriginalAccessGrant, grant.grant_id)
    assert stored_grant is not None
    assert stored_grant.review_decision_id == decision.id
    assert stored_grant.review_bound_at is not None


@pytest.mark.parametrize(
    ("runtime_totp_secret", "credential_issuer_key"),
    (
        (TEST_TOTP_SECRET, "_" * 43),
        ("A" * 32, TEST_CREDENTIAL_ISSUER_KEY),
    ),
)
@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_runtime_role_original_access_rejects_wrong_capability_without_locking(
    tmp_path: Path,
    runtime_totp_secret: str,
    credential_issuer_key: str,
) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identity = _identity("runtime-wrong-issuer")
    grant = _issue(report_id, identity)
    engine = SessionLocal.kw["bind"]

    with engine.connect() as connection, engine.connect() as legitimate_connection:
        for candidate in (connection, legitimate_connection):
            candidate.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            candidate.commit()
        attack = connection.begin()
        try:
            with pytest.raises(SQLAlchemyError):
                connection.execute(
                    text(
                        "SELECT public."
                        "walksafe_lock_admin_original_access_session("
                        "CAST(:admin_id AS text), CAST(:session_id AS uuid), "
                        "CAST(:device_id AS text), "
                        "CAST(:observed_at AS timestamptz), "
                        "CAST(:runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": identity.admin_id,
                        "session_id": identity.session_id,
                        "device_id": identity.device_id,
                        "observed_at": datetime.now(timezone.utc),
                        "runtime_totp_secret": runtime_totp_secret,
                        "credential_issuer_key": credential_issuer_key,
                    },
                )
            # Leave the failed transaction open deliberately. A mismatched issuer
            # must not acquire control/session tuple locks before it is rejected.
            with SessionLocal(bind=legitimate_connection) as db:
                db.execute(text("SET LOCAL lock_timeout = '250ms'"))
                accessed = access_report_original(
                    db,
                    upload_root=tmp_path,
                    filename=f"{report_id}.jpg",
                    raw_access_token=grant.access_token,
                    identity=identity,
                    key_manager=report_image_key_manager,
                    runtime_totp_secret=TEST_TOTP_SECRET,
                    credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                )
        finally:
            if attack.is_active:
                attack.rollback()
            for candidate in (connection, legitimate_connection):
                if candidate.in_transaction():
                    candidate.rollback()
                candidate.execute(text("RESET SESSION AUTHORIZATION"))
                candidate.commit()

    assert accessed.content == b"private-report-image"
    with SessionLocal() as db:
        stored_grant = db.get(ReportOriginalAccessGrant, grant.grant_id)
    assert stored_grant is not None and stored_grant.consumed_at is not None
    assert stored_grant.access_granted_at is not None


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_grant_is_bound_to_report_admin_session_device_and_expiry(tmp_path: Path) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identity = _identity("owner")
    issued_at = datetime.now(timezone.utc)
    grant = _issue(report_id, identity, now=issued_at, ttl_seconds=1)

    with SessionLocal() as db:
        with pytest.raises(ReportOriginalAccessError) as mismatch:
            access_report_original(
                db,
                upload_root=tmp_path,
                filename=f"{report_id}.jpg",
                raw_access_token=grant.access_token,
                identity=_identity("other"),
                key_manager=report_image_key_manager,
                runtime_totp_secret=TEST_TOTP_SECRET,
                credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                now=issued_at + timedelta(seconds=1),
            )
    assert mismatch.value.status_code == 403

    time.sleep(1.2)
    with SessionLocal() as db:
        with pytest.raises(ReportOriginalAccessError) as expired:
            access_report_original(
                db,
                upload_root=tmp_path,
                filename=f"{report_id}.jpg",
                raw_access_token=grant.access_token,
                identity=identity,
                key_manager=report_image_key_manager,
                runtime_totp_secret=TEST_TOTP_SECRET,
                credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                now=issued_at + timedelta(seconds=121),
            )
    assert expired.value.status_code == 403


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_tamper_or_key_loss_consumes_grant_and_withholds_plaintext(tmp_path: Path) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identity = _identity("tamper")
    grant = _issue(report_id, identity)
    path = tmp_path / f"{report_id}.wse"
    tampered = bytearray(path.read_bytes())
    tampered[-1] ^= 1
    path.write_bytes(tampered)
    path.chmod(0o600)

    with SessionLocal() as db:
        with pytest.raises(ReportOriginalAccessError) as failure:
            access_report_original(
                db,
                upload_root=tmp_path,
                filename=f"{report_id}.jpg",
                raw_access_token=grant.access_token,
                identity=identity,
                key_manager=report_image_key_manager,
                runtime_totp_secret=TEST_TOTP_SECRET,
                credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
            )
    assert failure.value.status_code == 503
    with SessionLocal() as db:
        stored_grant = db.get(ReportOriginalAccessGrant, grant.grant_id)
        error_audit = db.scalar(
            select(ReportOriginalAccessAudit).where(
                ReportOriginalAccessAudit.grant_id == grant.grant_id,
                ReportOriginalAccessAudit.action == "ACCESS_ERROR",
            )
        )
    assert stored_grant is not None and stored_grant.consumed_at is not None
    assert stored_grant.access_granted_at is None
    assert error_audit is not None

    second_report_id = _store_encrypted_report(tmp_path)
    second_grant = _issue(second_report_id, identity)

    class UnavailableKeyManager:
        def synchronize(self, _db) -> None:
            return None

        def decryption_key(self, _key_id: str) -> bytes:
            raise ReportImageKeyUnavailable("key compromised")

    with SessionLocal() as db:
        with pytest.raises(ReportOriginalAccessError) as missing_key:
            access_report_original(
                db,
                upload_root=tmp_path,
                filename=f"{second_report_id}.jpg",
                raw_access_token=second_grant.access_token,
                identity=identity,
                key_manager=UnavailableKeyManager(),  # type: ignore[arg-type]
                runtime_totp_secret=TEST_TOTP_SECRET,
                credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
            )
    assert missing_key.value.status_code == 503


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_concurrent_one_time_grant_has_exactly_one_success(tmp_path: Path) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identity = _identity("race")
    grant = _issue(report_id, identity)
    engine = SessionLocal.kw["bind"]

    def access_once() -> str:
        with engine.connect() as connection:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            connection.commit()
            try:
                with SessionLocal(bind=connection) as db:
                    try:
                        access_report_original(
                            db,
                            upload_root=tmp_path,
                            filename=f"{report_id}.jpg",
                            raw_access_token=grant.access_token,
                            identity=identity,
                            key_manager=report_image_key_manager,
                            runtime_totp_secret=TEST_TOTP_SECRET,
                            credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                        )
                        return "success"
                    except ReportOriginalAccessError as exc:
                        return f"denied:{exc.status_code}"
            finally:
                if connection.in_transaction():
                    connection.rollback()
                connection.execute(text("RESET SESSION AUTHORIZATION"))
                connection.commit()

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(lambda _index: access_once(), range(2)))

    assert outcomes.count("success") == 1
    assert outcomes.count("denied:403") == 1


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_fresh_proof_sequential_review_replay_returns_one_postgres_row(
    tmp_path: Path,
) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identities = [
        _identity("approval-sequential-replay-original"),
        _identity("approval-sequential-replay-new-session"),
    ]
    grant = _issue(report_id, identities[0])
    with SessionLocal() as db:
        access_report_original(
            db,
            upload_root=tmp_path,
            filename=f"{report_id}.jpg",
            raw_access_token=grant.access_token,
            identity=identities[0],
            key_manager=report_image_key_manager,
            runtime_totp_secret=TEST_TOTP_SECRET,
            credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
        )
    _prepare_identity(identities[1], observed_at=datetime.now(timezone.utc))
    decision_id = uuid.uuid4()
    payload = ReportReviewDecisionRequest(
        decision_id=decision_id,
        decision="APPROVED",
        reason="Original image and exact location were reviewed.",
        user_visible_reason=None,
        duplicate_of_report_id=None,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
        content_revision=0,
        evidence_grant_id=grant.grant_id,
    )
    canonical_body = payload.model_dump_json()
    request_bodies = [
        canonical_body.replace(
            '"reason":"Original image and exact location were reviewed."',
            '"reason":"  Original image and exact location were reviewed.  "',
        ).encode("utf-8"),
        canonical_body.replace(
            '"reason":"Original image and exact location were reviewed."',
            '"reason":"\u00a0Original image and exact location were reviewed.\u00a0"',
        ).encode("utf-8"),
    ]
    assert identities[0].session_id != identities[1].session_id
    assert identities[0].device_id != identities[1].device_id
    assert request_bodies[0] != request_bodies[1]
    assert [
        ReportReviewDecisionRequest.model_validate_json(body)
        for body in request_bodies
    ] == [payload, payload]
    proof_bindings = [
        _record_consumed_action_proof(
            identity=identities[index],
            report_id=report_id,
            action="report.review.decide",
            request_body=request_bodies[index],
        )
        for index in range(2)
    ]
    engine = SessionLocal.kw["bind"]
    results: list[
        tuple[uuid.UUID, int, uuid.UUID, str, uuid.UUID, datetime, datetime]
    ] = []

    for index, (proof_challenge_id, correlation_id, _) in enumerate(proof_bindings):
        with engine.connect() as connection:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            connection.commit()
            try:
                with SessionLocal(bind=connection) as db:
                    decision = append_report_review_decision(
                        db,
                        report_id=report_id,
                        payload=payload,
                        identity=identities[index],
                        correlation_id=correlation_id,
                        proof_challenge_id=proof_challenge_id,
                        proof_request_body=request_bodies[index],
                        runtime_totp_secret=TEST_TOTP_SECRET,
                        credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                    )
                    results.append(
                        (
                            decision.id,
                            decision.revision,
                            decision.session_id,
                            decision.device_id,
                            decision.correlation_id,
                            decision.decided_at,
                            decision.created_at,
                        )
                    )
            finally:
                if connection.in_transaction():
                    connection.rollback()
                connection.execute(text("RESET SESSION AUTHORIZATION"))
                connection.commit()

    with SessionLocal() as db:
        row_count = db.execute(
            select(func.count(ReportReviewDecision.id)).where(
                ReportReviewDecision.id == decision_id
            )
        ).scalar_one()
        claim_count = db.execute(
            text(
                "SELECT count(*) FROM public.admin_report_mutation_claims "
                "WHERE resource_type = 'review_decision' "
                "AND resource_id = :decision_id"
            ),
            {"decision_id": decision_id},
        ).scalar_one()
        canonical_claim = db.execute(
            text(
                "SELECT challenge_id, session_id, device_id "
                "FROM public.admin_report_mutation_claims "
                "WHERE resource_type = 'review_decision' "
                "AND resource_id = :decision_id"
            ),
            {"decision_id": decision_id},
        ).one()
        consumed_proof_count = db.execute(
            select(func.count(AdminDeviceProofChallenge.id)).where(
                AdminDeviceProofChallenge.id.in_(
                    [binding[0] for binding in proof_bindings]
                ),
                AdminDeviceProofChallenge.consumed_at.is_not(None),
            )
        ).scalar_one()

    assert results[0] == results[1]
    assert results[0][0] == decision_id
    assert results[0][1] == 1
    assert results[0][2] == identities[0].session_id
    assert results[0][3] == identities[0].device_id
    assert results[0][4] == proof_bindings[0][1]
    assert row_count == 1
    assert claim_count == 1
    assert tuple(canonical_claim) == (
        proof_bindings[0][0],
        identities[0].session_id,
        identities[0].device_id,
    )
    assert consumed_proof_count == 2


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
@pytest.mark.parametrize("conflict_kind", ["report", "admin"])
def test_postgres_review_decision_id_reuse_by_other_report_or_admin_conflicts(
    tmp_path: Path,
    conflict_kind: str,
) -> None:
    report_id = _store_encrypted_report(tmp_path)
    conflicting_report_id = (
        _store_encrypted_report(tmp_path) if conflict_kind == "report" else report_id
    )
    identity = _identity(f"decision-id-{conflict_kind}-original")
    _prepare_identity(identity, observed_at=datetime.now(timezone.utc))
    conflicting_identity = (
        replace(identity, admin_id="different-admin@example.com")
        if conflict_kind == "admin"
        else identity
    )
    decision_id = uuid.uuid4()
    payload = ReportReviewDecisionRequest(
        decision_id=decision_id,
        decision="REJECTED",
        reason="The report was rejected after administrator review.",
        user_visible_reason="기관 전달 대상이 아닙니다.",
        duplicate_of_report_id=None,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
        content_revision=0,
        evidence_grant_id=None,
    )
    request_body = payload.model_dump_json().encode("utf-8")
    requests = [
        (
            report_id,
            identity,
            _record_consumed_action_proof(
                identity=identity,
                report_id=report_id,
                action="report.review.decide",
                request_body=request_body,
            ),
        ),
        (
            conflicting_report_id,
            conflicting_identity,
            _record_consumed_action_proof(
                identity=conflicting_identity,
                report_id=conflicting_report_id,
                action="report.review.decide",
                request_body=request_body,
            ),
        ),
    ]
    engine = SessionLocal.kw["bind"]

    def decide(index: int) -> ReportReviewDecision:
        request_report_id, request_identity, proof_binding = requests[index]
        proof_challenge_id, correlation_id, _ = proof_binding
        with engine.connect() as connection:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            connection.commit()
            try:
                with SessionLocal(bind=connection) as db:
                    return append_report_review_decision(
                        db,
                        report_id=request_report_id,
                        payload=payload,
                        identity=request_identity,
                        correlation_id=correlation_id,
                        proof_challenge_id=proof_challenge_id,
                        proof_request_body=request_body,
                        runtime_totp_secret=TEST_TOTP_SECRET,
                        credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                    )
            finally:
                if connection.in_transaction():
                    connection.rollback()
                connection.execute(text("RESET SESSION AUTHORIZATION"))
                connection.commit()

    created = decide(0)
    original_snapshot = (
        created.id,
        created.report_id,
        created.revision,
        created.admin_id,
        created.session_id,
        created.device_id,
        created.correlation_id,
        created.decided_at,
        created.created_at,
    )
    with pytest.raises(AdminReportWorkflowError) as captured:
        decide(1)

    with SessionLocal() as db:
        stored = db.get(ReportReviewDecision, decision_id)
        claim_count = db.execute(
            text(
                "SELECT count(*) FROM public.admin_report_mutation_claims "
                "WHERE resource_type = 'review_decision' "
                "AND resource_id = :decision_id"
            ),
            {"decision_id": decision_id},
        ).scalar_one()
        consumed_proof_count = db.execute(
            select(func.count(AdminDeviceProofChallenge.id)).where(
                AdminDeviceProofChallenge.id.in_(
                    [request[2][0] for request in requests]
                ),
                AdminDeviceProofChallenge.consumed_at.is_not(None),
            )
        ).scalar_one()

    assert captured.value.code == "review_decision_idempotency_conflict"
    assert captured.value.status_code == 409
    assert stored is not None
    assert (
        stored.id,
        stored.report_id,
        stored.revision,
        stored.admin_id,
        stored.session_id,
        stored.device_id,
        stored.correlation_id,
        stored.decided_at,
        stored.created_at,
    ) == original_snapshot
    assert claim_count == 1
    assert consumed_proof_count == 2


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
@pytest.mark.parametrize("divergent_reuse", [False, True])
def test_concurrent_review_decision_id_reuse_is_replayed_or_conflicts(
    tmp_path: Path,
    divergent_reuse: bool,
) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identity = _identity("approval-race")
    grant = _issue(report_id, identity)
    with SessionLocal() as db:
        access_report_original(
            db,
            upload_root=tmp_path,
            filename=f"{report_id}.jpg",
            raw_access_token=grant.access_token,
            identity=identity,
            key_manager=report_image_key_manager,
            runtime_totp_secret=TEST_TOTP_SECRET,
            credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
        )
    decision_id = uuid.uuid4()
    payload = ReportReviewDecisionRequest(
        decision_id=decision_id,
        decision="APPROVED",
        reason="Original image and exact location were reviewed.",
        user_visible_reason=None,
        duplicate_of_report_id=None,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
        content_revision=0,
        evidence_grant_id=grant.grant_id,
    )
    payloads = [payload, payload]
    if divergent_reuse:
        payloads[1] = payload.model_copy(
            update={"reason": "The same decision ID was reused differently."}
        )
    request_bodies = [
        candidate.model_dump_json().encode("utf-8") for candidate in payloads
    ]
    proof_bindings = [
        _record_consumed_action_proof(
            identity=identity,
            report_id=report_id,
            action="report.review.decide",
            request_body=request_bodies[index],
        )
        for index in range(2)
    ]
    engine = SessionLocal.kw["bind"]
    barrier = threading.Barrier(2)

    def approve_once(index: int) -> tuple[str, uuid.UUID | None, int | None]:
        proof_challenge_id, correlation_id, _ = proof_bindings[index]
        with engine.connect() as connection:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            connection.commit()
            try:
                barrier.wait(timeout=5)
                with SessionLocal(bind=connection) as db:
                    try:
                        decision = append_report_review_decision(
                            db,
                            report_id=report_id,
                            payload=payloads[index],
                            identity=identity,
                            correlation_id=correlation_id,
                            proof_challenge_id=proof_challenge_id,
                            proof_request_body=request_bodies[index],
                            runtime_totp_secret=TEST_TOTP_SECRET,
                            credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                        )
                        return "success", decision.id, decision.revision
                    except AdminReportWorkflowError as exc:
                        return exc.code, None, None
            finally:
                if connection.in_transaction():
                    connection.rollback()
                connection.execute(text("RESET SESSION AUTHORIZATION"))
                connection.commit()

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(approve_once, range(2)))

    success_results = [result[1:] for result in outcomes if result[0] == "success"]
    if divergent_reuse:
        assert len(success_results) == 1
        assert [result[0] for result in outcomes].count(
            "review_decision_idempotency_conflict"
        ) == 1
    else:
        assert len(success_results) == 2
        assert len(set(success_results)) == 1
        assert success_results[0][0] == decision_id
    with SessionLocal() as db:
        decisions = list(
            db.scalars(
                select(ReportReviewDecision).where(
                    ReportReviewDecision.report_id == report_id,
                    ReportReviewDecision.evidence_grant_id == grant.grant_id,
                )
            )
        )
        stored_grant = db.get(ReportOriginalAccessGrant, grant.grant_id)
        claim_count = db.execute(
            text(
                "SELECT count(*) FROM public.admin_report_mutation_claims "
                "WHERE resource_type = 'review_decision' "
                "AND resource_id = :decision_id"
            ),
            {"decision_id": decision_id},
        ).scalar_one()
    assert len(decisions) == 1
    assert decisions[0].id == decision_id
    assert claim_count == 1
    assert stored_grant is not None
    assert stored_grant.review_decision_id == decisions[0].id
    assert stored_grant.review_bound_at is not None


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_runtime_original_access_and_review_share_one_lock_order(
    tmp_path: Path,
) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identity = _identity("access-review-lock-order")
    grant = _issue(report_id, identity)
    payload = ReportReviewDecisionRequest(
        decision_id=uuid.uuid4(),
        decision="REJECTED",
        reason="Concurrent review lock ordering was verified.",
        user_visible_reason="기관 전달 대상이 아닙니다.",
        duplicate_of_report_id=None,
        location_reviewed=True,
        photo_reviewed=True,
        privacy_reviewed=True,
        content_revision=0,
        evidence_grant_id=None,
    )
    request_body = payload.model_dump_json().encode("utf-8")
    proof_challenge_id, correlation_id, _ = _record_consumed_action_proof(
        identity=identity,
        report_id=report_id,
        action="report.review.decide",
        request_body=request_body,
    )
    barrier = threading.Barrier(2)
    engine = SessionLocal.kw["bind"]

    def access_once() -> str:
        with engine.connect() as connection:
            connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
            connection.commit()
            try:
                barrier.wait(timeout=5)
                with SessionLocal(bind=connection) as db:
                    db.execute(text("SET LOCAL lock_timeout = '5s'"))
                    access_report_original(
                        db,
                        upload_root=tmp_path,
                        filename=f"{report_id}.jpg",
                        raw_access_token=grant.access_token,
                        identity=identity,
                        key_manager=report_image_key_manager,
                        runtime_totp_secret=TEST_TOTP_SECRET,
                        credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                    )
                return "accessed"
            finally:
                if connection.in_transaction():
                    connection.rollback()
                connection.execute(text("RESET SESSION AUTHORIZATION"))
                connection.commit()

    def review_once() -> str:
        with engine.connect() as connection:
            connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
            connection.commit()
            try:
                barrier.wait(timeout=5)
                with SessionLocal(bind=connection) as db:
                    db.execute(text("SET LOCAL lock_timeout = '5s'"))
                    append_report_review_decision(
                        db,
                        report_id=report_id,
                        payload=payload,
                        identity=identity,
                        correlation_id=correlation_id,
                        proof_challenge_id=proof_challenge_id,
                        proof_request_body=request_body,
                        runtime_totp_secret=TEST_TOTP_SECRET,
                        credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                    )
                return "reviewed"
            finally:
                if connection.in_transaction():
                    connection.rollback()
                connection.execute(text("RESET SESSION AUTHORIZATION"))
                connection.commit()

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(access_once), executor.submit(review_once)]
        outcomes = {future.result(timeout=10) for future in futures}

    assert outcomes == {"accessed", "reviewed"}


@pytest.mark.parametrize("security_change", ["revoke", "recovery"])
@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_revoke_or_recovery_committed_first_withholds_original_plaintext(
    tmp_path: Path,
    security_change: str,
) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identity = _identity(f"race-{security_change}")
    grant = _issue(report_id, identity)
    locks_held = threading.Event()
    release_security_change = threading.Event()
    plaintext_returned: list[bytes] = []

    def change_security_state() -> None:
        with SessionLocal.begin() as db:
            control = db.execute(
                select(AdminSecurityControl).with_for_update()
            ).scalar_one()
            session = db.execute(
                select(AdminSecuritySession)
                .where(AdminSecuritySession.id == identity.session_id)
                .with_for_update()
            ).scalar_one()
            if security_change == "revoke":
                session.revoked_at = datetime.now(timezone.utc)
                session.revoked_reason = "administrator_revoked"
            else:
                control.security_state = "RECOVERY_IN_PROGRESS"
                control.state_version += 1
            locks_held.set()
            assert release_security_change.wait(timeout=2)

    def access() -> str:
        with SessionLocal() as db:
            try:
                result = access_report_original(
                    db,
                    upload_root=tmp_path,
                    filename=f"{report_id}.jpg",
                    raw_access_token=grant.access_token,
                    identity=identity,
                    key_manager=report_image_key_manager,
                    runtime_totp_secret=TEST_TOTP_SECRET,
                    credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
                )
            except ReportOriginalAccessError as exc:
                return f"denied:{exc.status_code}"
            plaintext_returned.append(result.content)
            return "success"

    with ThreadPoolExecutor(max_workers=2) as executor:
        security_future = executor.submit(change_security_state)
        assert locks_held.wait(timeout=2)
        access_future = executor.submit(access)
        try:
            assert not access_future.done()
        finally:
            release_security_change.set()
        security_future.result(timeout=2)
        outcome = access_future.result(timeout=2)

    assert outcome == "denied:403"
    assert plaintext_returned == []
    if security_change == "recovery":
        with SessionLocal.begin() as db:
            control = db.execute(
                select(AdminSecurityControl).with_for_update()
            ).scalar_one()
            control.security_state = "NORMAL"
            control.state_version += 1


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_audit_commit_failure_withholds_decrypted_original(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identity = _identity("audit-failure")
    grant = _issue(report_id, identity)

    with SessionLocal() as db:
        def fail_commit() -> None:
            raise RuntimeError("simulated audit store failure")

        monkeypatch.setattr(db, "commit", fail_commit)
        with pytest.raises(ReportOriginalAccessError) as withheld:
            access_report_original(
                db,
                upload_root=tmp_path,
                filename=f"{report_id}.jpg",
                raw_access_token=grant.access_token,
                identity=identity,
                key_manager=report_image_key_manager,
                runtime_totp_secret=TEST_TOTP_SECRET,
                credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
            )

    assert withheld.value.status_code == 503
    assert withheld.value.code == "report_original_access_audit_unavailable"


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_legacy_plaintext_has_no_grant_or_runtime_migration(tmp_path: Path) -> None:
    report_id = uuid.uuid4()
    plaintext_path = tmp_path / f"{report_id}.jpg"
    plaintext_path.write_bytes(b"legacy-plaintext")
    with SessionLocal() as db:
        db.add(
            Report(
                id=report_id,
                status="new",
                class_id=0,
                class_name="damaged_tactile_block",
                confidence=0.9,
                bbox_x=0.1,
                bbox_y=0.1,
                bbox_width=0.2,
                bbox_height=0.2,
                captured_at=datetime.now(timezone.utc),
                source="fake",
                latitude=37.5665,
                    longitude=126.978,
                    accuracy_m=4.5,
                    location=WKTElement("POINT(126.978 37.5665)", srid=4326),
                    image_path=f"/uploads/{report_id}.jpg",
                image_content_type="image/jpeg",
                payload={"image_sha256": hashlib.sha256(b"legacy-plaintext").hexdigest()},
            )
        )
        db.commit()

    identity = _identity("legacy")
    _prepare_identity(identity, observed_at=datetime.now(timezone.utc))
    with SessionLocal() as db:
        with pytest.raises(ReportOriginalAccessError) as unavailable:
            issue_report_original_access_grant(
                db,
                report_id=report_id,
                purpose="report_review",
                reason="Review this historical report original.",
                expected_content_revision=0,
                identity=identity,
                ttl_seconds=120,
                key_manager=report_image_key_manager,
                runtime_totp_secret=TEST_TOTP_SECRET,
                credential_issuer_key=TEST_CREDENTIAL_ISSUER_KEY,
            )
    assert unavailable.value.status_code == 410
    assert plaintext_path.read_bytes() == b"legacy-plaintext"
    assert not (tmp_path / f"{report_id}.wse").exists()


def test_report_image_database_schema_has_metadata_only_and_no_secret_or_original_bytes() -> None:
    columns = ReportImageObject.__table__.columns
    assert set(columns.keys()) == {
        "report_id",
        "storage_name",
        "envelope_version",
        "algorithm",
        "aad_version",
        "key_id",
        "nonce",
        "plaintext_sha256",
        "plaintext_size",
        "envelope_sha256",
        "envelope_size",
        "content_type",
        "created_at",
    }
    assert "key_material" not in columns
    assert "plaintext" not in columns
    assert "image_bytes" not in columns
    keyring_checks = {
        constraint.name: str(constraint.sqltext)
        for constraint in ReportImageKeyringEvent.__table__.constraints
        if hasattr(constraint, "sqltext")
    }
    assert "$.**.material" in keyring_checks["ck_report_image_keyring_events_no_material"]


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_original_access_audit_and_keyring_history_are_append_only() -> None:
    with SessionLocal() as db:
        for table_name in (
            "report_original_access_audits",
            "report_image_keyring_events",
        ):
            with pytest.raises(Exception, match="append-only"):
                db.execute(text(f"TRUNCATE TABLE {table_name}"))
            db.rollback()
