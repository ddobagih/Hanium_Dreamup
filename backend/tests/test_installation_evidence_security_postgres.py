"""Synthetic P-256 registration through real request assertions and PostgreSQL."""

import base64
import copy
from datetime import UTC, datetime
import hashlib
import json
import os
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI
import pytest
from sqlalchemy import select

from asgi_client import ASGITestClient
from backend.app import database
from backend.app.api import privacy
from backend.app.config import get_settings
from backend.app.database import get_db
from backend.app.field_test_security import (
    FieldTestAccess, FieldTestSecurityMiddleware, create_actor_assertion_v4,
)
from backend.app.models import UserAccount
from backend.app.services.privacy_lifecycle import privacy_subject_hmac
from test_raw_collection_postgres_integration import (
    PRIVACY_SECRET, _record_admission, _session_factory,
)

pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL"), reason="isolated PostgreSQL required",
)

TOKEN = "installation-security-synthetic-field-token-20261011"
SECRET = "installation-security-synthetic-gateway-secret-20261011"
PATH = "/privacy/installation-evidence-key"


def _key() -> str:
    public = ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.urlsafe_b64encode(public).decode("ascii").rstrip("=")


@pytest.fixture
def installation_boundary(monkeypatch):
    engine, factory = _session_factory()
    actor = str(uuid.uuid4())
    installation = "install_" + uuid.uuid4().hex
    now = datetime.now(UTC)
    with factory() as db:
        db.add(UserAccount(
            id=uuid.uuid4(), actor_id=actor,
            privacy_subject_hmac=privacy_subject_hmac(actor, 1, PRIVACY_SECRET),
            email_lookup_hmac=hashlib.sha256(actor.encode()).hexdigest(),
            email_ciphertext=b"synthetic-encrypted-email", email_nonce=b"n" * 12,
            email_key_version=1, password_hash="synthetic-password-hash-for-test",
            status="ACTIVE", account_generation=1, auth_epoch=3,
            created_at=now, updated_at=now,
        ))
        db.commit()
    _record_admission(factory, actor_id=actor, installation_id=installation)
    settings = copy.copy(get_settings())
    settings.field_test_security_enabled = True
    settings.field_test_token = TOKEN
    settings.gateway_session_secret = SECRET
    settings.walksafe_environment = "test"
    settings.actor_rate_limit_store = "memory"
    settings.actor_assertion_v3_policy = "forbid"
    settings.privacy_hmac_secret = PRIVACY_SECRET
    app = FastAPI()
    app.include_router(privacy.create_router(settings))

    def db_dependency():
        with factory() as db:
            yield db

    app.dependency_overrides[get_db] = db_dependency
    monkeypatch.setattr(database, "SessionLocal", factory)
    app.add_middleware(FieldTestSecurityMiddleware, settings=settings)
    try:
        yield ASGITestClient(app), factory, actor, installation
    finally:
        engine.dispose()


def _post(client, actor, installation, key, *, generation=1, epoch=3, change_body=False):
    payload = {"installation_id": installation, "public_key": key}
    body = json.dumps(payload, separators=(",", ":")).encode()
    assertion = create_actor_assertion_v4(
        actor, FieldTestAccess.FIELD, SECRET,
        account_generation=generation, auth_epoch=epoch,
        method="POST", target=PATH, body_sha256=hashlib.sha256(body).hexdigest(),
    )
    if change_body:
        body = body.replace(installation.encode(), (installation + "changed").encode())
    return client.post(PATH, content=body, headers={
        "Content-Type": "application/json", "X-WalkSafe-Field-Test-Token": TOKEN,
        "X-WalkSafe-Actor-Id": actor,
        "X-WalkSafe-Account-Generation": str(generation),
        "X-WalkSafe-Actor-Assertion": assertion,
    })


def test_registration_actual_assertion_consent_and_first_key_wins(installation_boundary):
    client, factory, actor, installation = installation_boundary
    key = _key()
    accepted = _post(client, actor, installation, key)
    assert accepted.status_code == 204, accepted.text
    assert accepted.content == b""
    assert accepted.headers["cache-control"] == "no-store"
    assert _post(client, actor, installation, key).status_code == 204
    assert _post(client, actor, installation, _key()).status_code == 409
    # An authenticated actor cannot bind a different, unconfirmed installation.
    missing_consent = _post(client, actor, "install_" + uuid.uuid4().hex, _key())
    assert missing_consent.status_code == 403, missing_consent.text
    assert _post(client, actor, installation, key, generation=2).status_code == 401
    assert _post(client, actor, installation, key, epoch=2).status_code == 401
    assert _post(client, actor, installation, key, change_body=True).status_code == 401
    with factory() as db:
        account = db.scalar(select(UserAccount).where(UserAccount.actor_id == actor))
        account.auth_epoch = 4
        db.commit()
    assert _post(client, actor, installation, key).status_code == 401


def test_installation_registration_uses_privacy_actor_rate_budget(installation_boundary):
    client, _, actor, installation = installation_boundary
    key = _key()
    for _ in range(12):
        response = _post(client, actor, installation, key)
        assert response.status_code == 204, response.text
    limited = _post(client, actor, installation, key)
    assert limited.status_code == 429, limited.text
    assert limited.headers["cache-control"] == "no-store"
    assert "retry-after" in limited.headers
