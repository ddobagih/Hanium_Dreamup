"""S18-5: per-source admission for session-less device proof challenges.

A requester that knows the public device identifiers can issue LOGIN or
RECOVERY_COMPLETE challenges before any session exists. One source may take
at most half of the shared eight-slot pool per challenge lifetime, so a
normal login from another source still gets a challenge; the limit clears
after the lifetime, and session-bound ACTION issuance is not source-limited.
"""

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
from fastapi import FastAPI
import pytest

from asgi_client import ASGITestClient
from backend.app.api import admin_security as admin_security_api
from backend.app.models import AdminDeviceKey, AdminSecurityControl
from backend.app.services.admin_device_proof import (
    ADMIN_DEVICE_PROOF_MAX_OUTSTANDING_CHALLENGES,
    ADMIN_DEVICE_PROOF_MAX_SESSIONLESS_PER_SOURCE,
    AdminDeviceProofService,
    canonical_admin_query_sha256,
    raw_body_sha256,
)
from backend.app.services.admin_security import (
    ADMIN_APP_KIND,
    ADMIN_AUDIENCE,
    ADMIN_ROLE,
    AdminSecurityError,
    AdminSessionIdentity,
    provision_admin_security,
)
from test_admin_device_proof import (
    ADMIN_ID as FAKE_ADMIN_ID,
    CORRELATION_ID,
    DEVICE_ID as FAKE_DEVICE_ID,
    _FakeProofService,
    _device_key,
    _private_key,
    _settings,
)


def test_s18_5_route_forwards_the_client_source_for_sessionless_issuance() -> None:
    assert ADMIN_DEVICE_PROOF_MAX_SESSIONLESS_PER_SOURCE * 2 == (
        ADMIN_DEVICE_PROOF_MAX_OUTSTANDING_CHALLENGES
    )
    service = _FakeProofService()
    app = FastAPI()
    app.include_router(
        admin_security_api.create_router(
            _settings(), device_proof_service_factory=lambda: service
        )
    )
    key = _device_key(_private_key())
    response = ASGITestClient(app).post(
        "/admin/security/device-proof/challenges",
        json={
            "action": None,
            "admin_id": FAKE_ADMIN_ID,
            "body_sha256": "0" * 64,
            "correlation_id": str(CORRELATION_ID),
            "device_id": FAKE_DEVICE_ID,
            "device_key_marker": key.key_marker,
            "device_key_version": 1,
            "method": "POST",
            "path": "/admin/security/sessions",
            "purpose": "LOGIN",
            "query_sha256": hashlib.sha256(b"").hexdigest(),
            "read_purpose": None,
            "session_id": None,
        },
        headers={
            "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
            "X-WalkSafe-Role": ADMIN_ROLE,
            "X-WalkSafe-Audience": ADMIN_AUDIENCE,
            "X-WalkSafe-Device-Id": FAKE_DEVICE_ID,
            "X-WalkSafe-Correlation-Id": str(CORRELATION_ID),
        },
    )
    assert response.status_code == 200
    call = service.calls[0]
    assert call["identity"] is None
    assert isinstance(call["source"], str) and call["source"]


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_s18_5_one_source_cannot_fill_the_login_pool_on_postgres(tmp_path: Path) -> None:
    from sqlalchemy import create_engine, delete, text
    from sqlalchemy.orm import Session

    from backend.app.models import AdminDeviceProofChallenge, AdminSecurityAuthAttempt
    from backend.app.services.admin_security import sha256_text

    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True)
    admin_id = f"proof.source.{uuid.uuid4().hex[:16]}"
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
        hashlib.sha256(b"s18-5-source-issuer").digest()
    ).decode("ascii").rstrip("=")
    issuer_key_file = tmp_path / "issuer.key"
    issuer_key_file.write_text(issuer_key, encoding="ascii")
    issuer_key_file.chmod(0o600)
    settings = SimpleNamespace(
        admin_totp_secret=totp_secret,
        admin_credential_issuer_key_file=issuer_key_file,
    )
    attacker = f"203.0.113.{uuid.uuid4().int % 200 + 1}"
    legitimate = f"198.51.100.{uuid.uuid4().int % 200 + 1}"

    def request(purpose: str, at: datetime, *, source: str | None) -> dict[str, Any]:
        sessionless = purpose != "ACTION"
        return {
            "admin_id": admin_id,
            "device_id": device_id,
            "device_key_marker": marker,
            "device_key_version": 1,
            "query_sha256": canonical_admin_query_sha256(b""),
            "read_purpose": None,
            "now": at,
            "purpose": purpose,
            "action": None if sessionless else "report.delivery.create",
            "body_sha256": raw_body_sha256(b"{}"),
            "correlation_id": str(uuid.uuid4()),
            "method": "POST",
            "path": (
                "/admin/security/sessions"
                if purpose == "LOGIN"
                else "/admin/security/recovery/complete"
                if purpose == "RECOVERY_COMPLETE"
                else f"/reports/{uuid.uuid4()}/deliveries"
            ),
            "session_id": None if sessionless else str(session_id),
            "identity": None if sessionless else AdminSessionIdentity(
                admin_id=admin_id,
                session_id=session_id,
                device_id=device_id,
                device_label="source test",
                expires_at=at + timedelta(hours=1),
                step_up_verified_at=at,
            ),
            "source": source,
        }

    def issue(arguments: dict[str, Any]) -> str:
        with Session(engine) as db:
            try:
                AdminDeviceProofService(db, settings).issue_challenge(**arguments)
            except AdminSecurityError as exc:
                return f"{exc.code}:{exc.retry_after}"
            return "issued"

    try:
        with Session(engine) as db:
            provision_admin_security(
                db, admin_id=admin_id, password="device proof source password",
                totp_secret=totp_secret, recovery_codes=["DEVICE-PROOF-SOURCE-RECOVERY-0001"],
                credential_issuer_key=issuer_key, now=now,
            )
        with Session(engine) as db:
            db.add(
                AdminDeviceKey(
                    id=key_id, admin_id=admin_id, device_id=device_id, key_version=1,
                    public_key_spki_der=der, key_marker=marker, status="ACTIVE",
                    created_at=now,
                )
            )
            db.commit()
        share = ADMIN_DEVICE_PROOF_MAX_SESSIONLESS_PER_SOURCE
        # The attacker source gets half the pool, then is refused. (RECOVERY_COMPLETE
        # shares the same per-source counter but is issuable only during recovery.)
        assert [issue(request("LOGIN", now, source=attacker)) for _ in range(share)] == [
            "issued"
        ] * share
        refused = issue(request("LOGIN", now, source=attacker))
        code, retry_after = refused.split(":")
        assert code == "admin_device_proof_challenge_rate_limited"
        assert 1 <= int(retry_after) <= 120
        # A normal login from its own source still gets a challenge.
        assert issue(request("LOGIN", now, source=legitimate)) == "issued"
        # Session-bound ACTION issuance is not source-limited.
        assert issue(request("ACTION", now, source=attacker)) == "issued"
        with engine.connect() as connection:
            recorded = connection.execute(
                text(
                    "SELECT action, count(*) FROM admin_security_auth_attempts "
                    "WHERE source_sha256 = :source GROUP BY action"
                ),
                {"source": sha256_text(attacker)},
            ).all()
        assert dict(recorded) == {"challenge_login": share}
        # After one challenge lifetime the attacker source is admitted again.
        later = now + timedelta(seconds=121)
        assert issue(request("LOGIN", later, source=attacker)) == "issued"
    finally:
        with engine.begin() as connection:
            connection.execute(
                delete(AdminDeviceProofChallenge).where(
                    AdminDeviceProofChallenge.admin_id == admin_id
                )
            )
            connection.execute(
                delete(AdminSecurityAuthAttempt).where(
                    AdminSecurityAuthAttempt.source_sha256.in_(
                        [sha256_text(attacker), sha256_text(legitimate)]
                    )
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
