from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import os
from pathlib import Path
import threading
from types import SimpleNamespace
import uuid

from alembic import command
from alembic.config import Config
import pytest
from sqlalchemy import select, text
from sqlalchemy.exc import SQLAlchemyError

from backend.app.database import SessionLocal
from backend.app.main import report_image_key_manager
from backend.app.models import (
    AdminSecurityControl,
    AdminSecuritySession,
    Report,
    ReportImageObject,
    ReportImageKeyringEvent,
    ReportOriginalAccessAudit,
    ReportOriginalAccessGrant,
)
from backend.app.services.admin_security import (
    AdminSecurityService,
    AdminSessionIdentity,
    provision_admin_security,
)
from backend.app.services.report_image_crypto import encrypt_report_image
from backend.app.services.report_image_keys import ReportImageKeyUnavailable
from backend.app.services.report_original_access import (
    ReportOriginalAccessError,
    _lock_and_revalidate_admin_session,
    access_report_original,
    issue_report_original_access_grant,
)
from backend.app.uploads import write_image_file


ROOT = Path(__file__).resolve().parents[2]
TEST_DATABASE_CONFIGURED = bool(os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip())
TEST_CREDENTIAL_ISSUER_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
TEST_TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"


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


def _issue(report_id: uuid.UUID, identity: AdminSessionIdentity, *, now: datetime | None = None):
    observed_at = now or datetime.now(timezone.utc)
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
    with SessionLocal() as db:
        return issue_report_original_access_grant(
            db,
            report_id=report_id,
            purpose="report_review",
            reason="Review the reported tactile-block damage.",
            identity=identity,
            ttl_seconds=120,
            key_manager=report_image_key_manager,
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
        "ACCESS_GRANTED",
        "ACCESS_DENIED",
    ]


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
    assert access_audit is not None


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


@pytest.mark.skipif(not TEST_DATABASE_CONFIGURED, reason="WALKSAFE_TEST_DATABASE_URL is not configured")
def test_grant_is_bound_to_report_admin_session_device_and_expiry(tmp_path: Path) -> None:
    report_id = _store_encrypted_report(tmp_path)
    identity = _identity("owner")
    issued_at = datetime.now(timezone.utc)
    grant = _issue(report_id, identity, now=issued_at)

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

    def access_once() -> str:
        with SessionLocal() as db:
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

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(lambda _index: access_once(), range(2)))

    assert outcomes.count("success") == 1
    assert outcomes.count("denied:403") == 1


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
                image_path=f"/uploads/{report_id}.jpg",
                image_content_type="image/jpeg",
                payload={"image_sha256": hashlib.sha256(b"legacy-plaintext").hexdigest()},
            )
        )
        db.commit()

    with SessionLocal() as db:
        with pytest.raises(ReportOriginalAccessError) as unavailable:
            issue_report_original_access_grant(
                db,
                report_id=report_id,
                purpose="report_review",
                reason="Review this historical report original.",
                identity=_identity("legacy"),
                ttl_seconds=120,
                key_manager=report_image_key_manager,
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
