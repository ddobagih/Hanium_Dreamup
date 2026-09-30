"""S18-27: an old custody retry cannot replace another device's confirmation."""

from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
from types import SimpleNamespace

import pyotp
import pytest
from sqlalchemy import create_engine, event, select
from sqlalchemy.orm import sessionmaker

from backend.app.models import AdminSecurityControl
from backend.app.services.admin_security import (
    ADMIN_APP_KIND,
    ADMIN_AUDIENCE,
    ADMIN_ROLE,
    AdminSecurityError,
    AdminSecurityService,
    authorize_admin_bearer,
    provision_admin_security,
    sha256_text,
)


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_custody_cas_serializes_two_devices_and_prioritizes_same_reference_replay(
    tmp_path: Path,
    clean_test_storage,
) -> None:
    clean_test_storage()
    database_url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
    engine = create_engine(database_url, pool_pre_ping=True)
    runtime_engine = create_engine(database_url, pool_pre_ping=True)

    @event.listens_for(runtime_engine, "connect")
    def _runtime_role(dbapi_connection, _record) -> None:
        with dbapi_connection.cursor() as cursor:
            cursor.execute("SET SESSION AUTHORIZATION walksafe_backend_runtime")
        dbapi_connection.commit()

    OwnerSession = sessionmaker(bind=engine, expire_on_commit=False)
    RuntimeSession = sessionmaker(bind=runtime_engine, expire_on_commit=False)
    secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    issuer_key = base64.urlsafe_b64encode(bytes(range(32))).decode("ascii").rstrip("=")
    issuer_file = tmp_path / "issuer.key"
    issuer_file.write_text(issuer_key, encoding="ascii")
    issuer_file.chmod(0o600)
    settings = SimpleNamespace(
        admin_id="walksafe.admin",
        admin_device_proof_enabled=False,
        admin_credential_issuer_key_file=issuer_file,
        admin_totp_secret=secret,
        admin_session_ttl_seconds=43_200,
        admin_step_up_ttl_seconds=300,
        admin_recovery_ttl_seconds=900,
        admin_auth_rate_limit_attempts=5,
        admin_auth_rate_limit_window_seconds=300,
        walksafe_environment="test",
    )
    at = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(minutes=3)
    old_reference = base64.urlsafe_b64encode(b"A" * 32).decode("ascii").rstrip("=")
    new_reference = base64.urlsafe_b64encode(b"B" * 32).decode("ascii").rstrip("=")

    def attest(identity, reference: str, expected: str | None):
        with RuntimeSession() as db:
            return AdminSecurityService(db, settings).attest_recovery_custody(
                identity,
                custody_reference=reference,
                material_kind="RECOVERY_CODE",
                storage_location="OFF_PHONE",
                separate_encrypted_backup_confirmed=True,
                expected_state_version=expected,
                now=at + timedelta(seconds=65),
            )

    try:
        with OwnerSession() as db:
            provision_admin_security(
                db,
                admin_id=settings.admin_id,
                password="custody cas correct horse",
                totp_secret=secret,
                recovery_codes=["RECOVERY-2026-S18-27-00001"],
                credential_issuer_key=issuer_key,
                now=at,
            )

        identities = []
        for device_id, seconds in (("android-device-0027-a", 0), ("android-device-0027-b", 30)):
            observed_at = at + timedelta(seconds=seconds)
            with OwnerSession() as db:
                grant = AdminSecurityService(db, settings).login(
                    admin_id=settings.admin_id,
                    password="custody cas correct horse",
                    totp_code=pyotp.TOTP(secret).at(observed_at),
                    device_id=device_id,
                    device_label=device_id,
                    source="198.51.100.27",
                    now=observed_at,
                )
            with OwnerSession() as db:
                identity = authorize_admin_bearer(
                    db,
                    grant.access_token,
                    runtime_totp_secret=secret,
                    credential_issuer_key=issuer_key,
                    device_id=device_id,
                    app_kind=ADMIN_APP_KIND,
                    role=ADMIN_ROLE,
                    audience=ADMIN_AUDIENCE,
                    now=observed_at,
                )
                db.commit()
                identities.append(identity)

        # The first request may have loaded this ORM row during authorization.
        # It must not compare against that cached object after another device commits.
        with RuntimeSession() as pending_db:
            cached_control = pending_db.execute(select(AdminSecurityControl)).scalar_one()
            initial_version = str(cached_control.state_version)
            first = attest(identities[1], new_reference, initial_version)
            assert first["state_version"] == str(int(initial_version) + 1)

            with pytest.raises(AdminSecurityError) as stale:
                AdminSecurityService(pending_db, settings).attest_recovery_custody(
                    identities[0],
                    custody_reference=old_reference,
                    material_kind="RECOVERY_CODE",
                    storage_location="OFF_PHONE",
                    separate_encrypted_backup_confirmed=True,
                    expected_state_version=initial_version,
                    now=at + timedelta(seconds=65),
                )
        assert (stale.value.status_code, stale.value.code) == (
            409, "admin_recovery_custody_state_version_conflict"
        ), (
            type(stale.value.__cause__).__name__,
            getattr(getattr(stale.value.__cause__, "orig", None), "sqlstate", None),
        )
        with OwnerSession() as db:
            control = db.execute(select(AdminSecurityControl)).scalar_one()
            assert control.state_version == int(first["state_version"])
            assert control.recovery_custody_reference_sha256 == sha256_text(new_reference)

        replay = attest(identities[1], new_reference, initial_version)
        assert replay["state_version"] == first["state_version"]
        with pytest.raises(AdminSecurityError) as legacy_stale:
            attest(identities[0], old_reference, None)
        assert legacy_stale.value.code == "admin_recovery_custody_state_version_conflict"

        accepted = attest(identities[0], old_reference, first["state_version"])
        assert accepted["state_version"] == str(int(first["state_version"]) + 1)
        with OwnerSession() as db:
            control = db.execute(select(AdminSecurityControl)).scalar_one()
            assert control.recovery_custody_reference_sha256 == sha256_text(old_reference)
    finally:
        clean_test_storage()
        runtime_engine.dispose()
        engine.dispose()
