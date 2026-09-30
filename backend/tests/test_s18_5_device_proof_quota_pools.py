"""S18-5: unauthenticated challenge issuance must not drain ACTION capacity."""

from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import hashlib
import os
from pathlib import Path
from types import SimpleNamespace
from typing import Any
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
import pytest

from backend.app.models import AdminDeviceKey, AdminSecurityControl
from backend.app.services.admin_device_proof import (
    ADMIN_DEVICE_PROOF_CHALLENGE_TYPES,
    ADMIN_DEVICE_PROOF_MAX_OUTSTANDING_CHALLENGES,
    ADMIN_DEVICE_PROOF_QUOTA_POOLS,
    AdminDeviceProofService,
    canonical_admin_query_sha256,
    raw_body_sha256,
)
from backend.app.services.admin_security import (
    AdminSecurityError,
    AdminSessionIdentity,
    provision_admin_security,
)


def test_quota_pools_cover_every_purpose_and_isolate_action() -> None:
    assert set(ADMIN_DEVICE_PROOF_QUOTA_POOLS) == set(ADMIN_DEVICE_PROOF_CHALLENGE_TYPES)
    assert ADMIN_DEVICE_PROOF_QUOTA_POOLS["ACTION"] == frozenset({"ACTION"})
    for purpose in ("LOGIN", "RECOVERY_COMPLETE"):
        assert purpose in ADMIN_DEVICE_PROOF_QUOTA_POOLS[purpose]
        assert "ACTION" not in ADMIN_DEVICE_PROOF_QUOTA_POOLS[purpose]


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_login_challenges_cannot_exhaust_action_quota(tmp_path: Path) -> None:
    from sqlalchemy import create_engine, delete, text
    from sqlalchemy.orm import Session

    from backend.app.models import AdminDeviceProofChallenge

    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True)
    with engine.connect() as connection:
        assert "test" in connection.execute(text("SELECT current_database()")).scalar_one().lower()
    admin_id = f"proof.quota.{uuid.uuid4().hex[:16]}"
    device_id = f"proof-device-{uuid.uuid4().hex}"
    private_key = ec.generate_private_key(ec.SECP256R1())
    der = private_key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    marker = hashlib.sha256(der).hexdigest()
    key_id = uuid.uuid4()
    session_id = uuid.uuid4()
    now = datetime.now(timezone.utc)
    totp_secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    issuer_key = base64.urlsafe_b64encode(
        hashlib.sha256(b"s18-5-quota-issuer").digest()
    ).decode("ascii").rstrip("=")
    issuer_key_file = tmp_path / "issuer.key"
    issuer_key_file.write_text(issuer_key, encoding="ascii")
    issuer_key_file.chmod(0o600)
    settings = SimpleNamespace(
        admin_totp_secret=totp_secret,
        admin_credential_issuer_key_file=issuer_key_file,
    )
    identity = AdminSessionIdentity(
        admin_id=admin_id,
        session_id=session_id,
        device_id=device_id,
        device_label="quota test",
        expires_at=now + timedelta(hours=1),
        step_up_verified_at=now,
    )
    common = {
        "admin_id": admin_id,
        "device_id": device_id,
        "device_key_marker": marker,
        "device_key_version": 1,
        "query_sha256": canonical_admin_query_sha256(b""),
        "read_purpose": None,
        "now": now,
    }

    def login() -> dict[str, Any]:
        return {
            **common,
            "purpose": "LOGIN",
            "action": None,
            "body_sha256": raw_body_sha256(b"{}"),
            "correlation_id": str(uuid.uuid4()),
            "method": "POST",
            "path": "/admin/security/sessions",
            "session_id": None,
            "identity": None,
        }

    def action() -> dict[str, Any]:
        return {
            **common,
            "purpose": "ACTION",
            "action": "report.delivery.create",
            "body_sha256": raw_body_sha256(b"{}"),
            "correlation_id": str(uuid.uuid4()),
            "method": "POST",
            "path": f"/reports/{uuid.uuid4()}/deliveries",
            "session_id": str(session_id),
            "identity": identity,
        }

    def issue(arguments: dict[str, Any]) -> str:
        with Session(engine) as db:
            try:
                AdminDeviceProofService(db, settings).issue_challenge(**arguments)
            except AdminSecurityError as exc:
                return exc.code
            return "issued"

    try:
        with Session(engine) as db:
            provision_admin_security(
                db,
                admin_id=admin_id,
                password="device proof quota password",
                totp_secret=totp_secret,
                recovery_codes=["DEVICE-PROOF-QUOTA-RECOVERY-0001"],
                credential_issuer_key=issuer_key,
                now=now,
            )
        with Session(engine) as db:
            db.add(
                AdminDeviceKey(
                    id=key_id,
                    admin_id=admin_id,
                    device_id=device_id,
                    key_version=1,
                    public_key_spki_der=der,
                    key_marker=marker,
                    status="ACTIVE",
                    created_at=now,
                )
            )
            db.commit()

        cap = ADMIN_DEVICE_PROOF_MAX_OUTSTANDING_CHALLENGES
        # An unauthenticated caller who knows the public identifiers fills its pool.
        assert [issue(login()) for _ in range(cap)] == ["issued"] * cap
        assert issue(login()) == "admin_device_proof_challenge_rate_limited"
        # Session-bound work keeps its own capacity and is itself still bounded.
        assert [issue(action()) for _ in range(cap)] == ["issued"] * cap
        assert issue(action()) == "admin_device_proof_challenge_rate_limited"
        assert issue(login()) == "admin_device_proof_challenge_rate_limited"
    finally:
        with engine.begin() as connection:
            connection.execute(
                delete(AdminDeviceProofChallenge).where(
                    AdminDeviceProofChallenge.admin_id == admin_id
                )
            )
            connection.execute(delete(AdminDeviceKey).where(AdminDeviceKey.id == key_id))
            for table in (
                "admin_security_recovery_codes",
                "walksafe_recovery_custody_capabilities",
            ):
                connection.execute(
                    text(f"DELETE FROM {table} WHERE admin_id = :admin_id"),
                    {"admin_id": admin_id},
                )
            connection.execute(
                delete(AdminSecurityControl).where(AdminSecurityControl.admin_id == admin_id)
            )
        engine.dispose()
