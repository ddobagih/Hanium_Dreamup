"""S18-29: reporting another device lost needs recent reauthentication (PostgreSQL).

End to end on the migrated database as the runtime role: password + fresh
TOTP reauthentication issues a one-time reconfirmation bound to the exact
``/admin/security/devices/{id}/report-lost`` path; the middleware consumer
(consume_admin_high_risk_reconfirmation) refuses it for another device path,
accepts it once for the target, and the lost device's keys and sessions are
revoked. A reused or never-issued nonce and a wrong password are refused. The
emergency path (recovery-code recovery that revokes every session) stays
separate and is covered by test_admin_sweep_w22.
"""

from __future__ import annotations

import base64
import hashlib
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pyotp
import pytest
from sqlalchemy import create_engine, event, select, text
from sqlalchemy.orm import sessionmaker

from backend.app import database as database_api
from backend.app.models import AdminSecurityAudit, AdminSecurityReconfirmation, AdminSecuritySession
from backend.app.services.admin_device_proof import provision_admin_device_key
from backend.app.services.admin_security import (
    ADMIN_APP_KIND,
    ADMIN_AUDIENCE,
    ADMIN_ROLE,
    AdminSecurityError,
    AdminSecurityService,
    authorize_admin_bearer,
    consume_admin_high_risk_reconfirmation,
    provision_admin_security,
    sha256_text,
)
from test_admin_sweep_w22 import (
    ADMIN_ID,
    DEVICE_ID,
    _pg_environment,
    _pg_key_rows,
    _private_key,
    _spki,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

_PASSWORD = "s18-29 correct horse battery"
_LOST = "android-device-0002"
_OTHER = "android-device-0003"


def _nonce(label: str) -> str:
    return base64.urlsafe_b64encode(
        hashlib.sha256(f"s18-29-{label}".encode()).digest()[:16]
    ).decode("ascii").rstrip("=")


def _lost_path(device_id: str) -> str:
    return f"/admin/security/devices/{device_id}/report-lost"


def test_s18_29_report_lost_requires_exact_path_reconfirmation_on_postgres(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    clean_test_storage,
) -> None:
    clean_test_storage()
    engine, SessionFactory, _run_as_runtime, settings, totp_secret, issuer_key = _pg_environment(
        tmp_path, device_proof_enabled=True
    )
    runtime_engine = create_engine(engine.url, pool_pre_ping=True)

    @event.listens_for(runtime_engine, "connect")
    def _as_runtime(dbapi_connection, _record) -> None:
        with dbapi_connection.cursor() as cursor:
            cursor.execute("SET SESSION AUTHORIZATION walksafe_backend_runtime")
        dbapi_connection.commit()

    Runtime = sessionmaker(bind=runtime_engine, expire_on_commit=False)
    with Runtime() as db:
        assert db.scalar(text("SELECT current_user")) == "walksafe_backend_runtime"
    monkeypatch.setattr(database_api, "SessionLocal", Runtime)
    started_at = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        with SessionFactory() as db:
            provision_admin_security(
                db, admin_id=ADMIN_ID, password=_PASSWORD, totp_secret=totp_secret,
                recovery_codes=["RECOVERY-2026-S18-29-0001"],
                credential_issuer_key=issuer_key, now=started_at,
            )
        for device_id in (DEVICE_ID, _LOST, _OTHER):
            with SessionFactory() as db:
                provision_admin_device_key(
                    db, admin_id=ADMIN_ID, device_id=device_id, key_version=1,
                    public_key_spki_der=_spki(_private_key()),
                    security_level="TRUSTED_ENVIRONMENT", now=started_at,
                )
        with Runtime() as db:
            grant = AdminSecurityService(db, settings).login(
                admin_id=ADMIN_ID, password=_PASSWORD,
                totp_code=pyotp.TOTP(totp_secret).at(started_at),
                device_id=DEVICE_ID, device_label="관리자 휴대전화",
                source="198.51.100.29", now=started_at,
            )
        with Runtime() as db:
            identity = authorize_admin_bearer(
                db, grant.access_token, runtime_totp_secret=totp_secret,
                credential_issuer_key=issuer_key, device_id=DEVICE_ID,
                app_kind=ADMIN_APP_KIND, role=ADMIN_ROLE, audience=ADMIN_AUDIENCE,
                now=started_at,
            )
            db.commit()
        with Runtime() as db:
            attested = AdminSecurityService(db, settings).attest_recovery_custody(
                identity,
                custody_reference="MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY",
                material_kind="RECOVERY_CODE",
                storage_location="OFF_PHONE",
                separate_encrypted_backup_confirmed=True,
                now=started_at,
            )
        assert attested["recovery_custody_state"] == "ATTESTED"

        reauth_at = started_at + timedelta(seconds=30)
        # A wrong password never issues a reconfirmation.
        with pytest.raises(AdminSecurityError):
            with Runtime() as db:
                AdminSecurityService(db, settings).reauthenticate(
                    identity, password="wrong password value",
                    totp_code=pyotp.TOTP(totp_secret).at(reauth_at),
                    action="device.report_lost", method="POST",
                    path=_lost_path(_LOST), nonce=_nonce("wrong-password"),
                    source="198.51.100.29", now=reauth_at,
                )
        issued_at = reauth_at + timedelta(seconds=30)
        nonce = _nonce("lost")
        with Runtime() as db:
            reconfirmation = AdminSecurityService(db, settings).reauthenticate(
                identity, password=_PASSWORD,
                totp_code=pyotp.TOTP(totp_secret).at(issued_at),
                action="device.report_lost", method="POST",
                path=_lost_path(_LOST), nonce=nonce,
                source="198.51.100.29", now=issued_at,
            )
        assert (reconfirmation.action, reconfirmation.path) == (
            "device.report_lost", _lost_path(_LOST),
        )
        with SessionFactory() as db:
            assert db.execute(
                select(AdminSecurityReconfirmation.nonce_sha256).where(
                    AdminSecurityReconfirmation.nonce_sha256
                    == sha256_text(_nonce("wrong-password"))
                )
            ).first() is None

        def consume(path: str, used_nonce: str, at: datetime):
            return consume_admin_high_risk_reconfirmation(
                grant.access_token, "device.report_lost",
                method="POST", path=path, nonce=used_nonce,
                runtime_totp_secret=totp_secret, credential_issuer_key=issuer_key,
                device_id=DEVICE_ID, app_kind=ADMIN_APP_KIND, role=ADMIN_ROLE,
                audience=ADMIN_AUDIENCE, now=at,
            )

        consume_at = issued_at + timedelta(seconds=5)
        # Bound to the exact target: another device's path is refused.
        with pytest.raises(AdminSecurityError) as other_path:
            consume(_lost_path(_OTHER), nonce, consume_at)
        with pytest.raises(AdminSecurityError) as never_issued:
            consume(_lost_path(_LOST), _nonce("never-issued"), consume_at)
        consumed = consume(_lost_path(_LOST), nonce, consume_at)
        assert consumed.session_id == identity.session_id
        with pytest.raises(AdminSecurityError) as reused:
            consume(_lost_path(_LOST), nonce, consume_at + timedelta(seconds=1))
        assert {
            other_path.value.code, never_issued.value.code, reused.value.code
        } == {"admin_reconfirmation_required"}

        with Runtime() as db:
            AdminSecurityService(db, settings).report_lost_device(
                consumed, _LOST, now=consume_at
            )
        keys = _pg_key_rows(SessionFactory)
        assert keys[(_LOST, 1)] == "REVOKED"
        assert keys[(DEVICE_ID, 1)] == "ACTIVE" and keys[(_OTHER, 1)] == "ACTIVE"
        with SessionFactory() as db:
            stored = db.execute(
                select(AdminSecurityReconfirmation).where(
                    AdminSecurityReconfirmation.nonce_sha256 == sha256_text(nonce)
                )
            ).scalar_one()
            assert (stored.action, stored.method, stored.path) == (
                "device.report_lost", "POST", _lost_path(_LOST),
            )
            assert stored.consumed_at == consume_at
            audit = db.execute(
                select(AdminSecurityAudit)
                .where(
                    AdminSecurityAudit.action == "device.report_lost",
                    AdminSecurityAudit.outcome == "SUCCESS",
                )
                .order_by(AdminSecurityAudit.sequence.desc())
                .limit(1)
            ).scalar_one()
            assert audit.details["revoked_device_key_count"] == 1
            # The reporting device keeps its own session.
            session = db.get(AdminSecuritySession, identity.session_id)
            assert session is not None and session.revoked_at is None
    finally:
        runtime_engine.dispose()
        engine.dispose()
