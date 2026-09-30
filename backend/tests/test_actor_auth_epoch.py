from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
from types import SimpleNamespace
import uuid

import httpx
import pytest
from fastapi import FastAPI, Request
from sqlalchemy.exc import SQLAlchemyError
from starlette.responses import JSONResponse

from backend.app import database
from backend.app import field_test_security as security
from backend.app.api import accounts

SECRET = "auth-epoch-contract-secret-123456789012345678901234"
TOKEN = "epoch-internal-token-12345678901234567890"
ADMIN_TOKEN = "epoch-admin-token-12345678901234567890"


def test_v3_matches_the_cross_language_wire_contract():
    digest = base64.urlsafe_b64encode(hmac.new(
        SECRET.encode(),
        b"walksafe-backend-actor-v3:field:auth-epoch-actor:7:4:2000000000",
        hashlib.sha256,
    ).digest()).decode().rstrip("=")
    proof = security.create_actor_assertion(
        "auth-epoch-actor", security.FieldTestAccess.FIELD, SECRET,
        issued_at=2_000_000_000, account_generation=7, auth_epoch=4,
    )
    assert proof == f"v3.2000000000.4.{digest}"
    assert security.verify_actor_assertion(
        proof, actor_id="auth-epoch-actor", access=security.FieldTestAccess.FIELD,
        secret=SECRET, now=2_000_000_000, account_generation=7, auth_epoch=4,
    )
    for actor, generation, epoch in [
        ("other-actor", 7, 4), ("auth-epoch-actor", 8, 4), ("auth-epoch-actor", 7, 5),
        ("auth-epoch-actor", None, 4), ("auth-epoch-actor", 7, None),
        ("auth-epoch-actor", 7, True),
    ]:
        assert not security.verify_actor_assertion(
            proof, actor_id=actor, access=security.FieldTestAccess.FIELD,
            secret=SECRET, now=2_000_000_000, account_generation=generation, auth_epoch=epoch,
        )


@pytest.mark.parametrize("epoch", ["0", "-1", "01", "1.0", "NaN", "9007199254740992", "9" * 2000, "\u0661"])
def test_noncanonical_epoch_fails_closed(epoch):
    proof = f"v3.2000000000.{epoch}." + "a" * 43
    assert security._actor_assertion_auth_epoch(proof) is None
    assert not security.verify_actor_assertion(
        proof, actor_id="auth-epoch-actor", access=security.FieldTestAccess.FIELD,
        secret=SECRET, now=2_000_000_000, account_generation=7,
    )


@pytest.fixture
def authority(monkeypatch):
    state = SimpleNamespace(
        account=SimpleNamespace(account_generation=7, auth_epoch=4, status="ACTIVE"),
        error=None, queries=0, reached=0, actor=f"epoch-{uuid.uuid4().hex}",
    )

    class DB:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def execute(self, _query):
            state.queries += 1
            if state.error:
                raise state.error
            return SimpleNamespace(one_or_none=lambda: state.account)

    monkeypatch.setattr(database, "SessionLocal", DB)
    return state


def request(authority, *, epoch=4, generation=7, path="/navigation/walking", proof=None):
    async def endpoint(request: Request):
        authority.reached += 1
        verified = request.scope["state"]["verified_actor_assertion"]
        return JSONResponse({"epoch": verified.auth_epoch})

    # w24 S20-41: this file checks the legacy v3 epoch format itself, so it opts
    # into the explicit "accept" compatibility policy (the default is safe-methods).
    settings = SimpleNamespace(
        field_test_security_enabled=True, field_test_token=TOKEN, admin_token="",
        gateway_session_secret=SECRET, walksafe_environment="test",
        allow_insecure_local_dev=False, actor_rate_limit_store="memory",
        actor_assertion_v3_policy="accept",
    )
    assertion = proof or security.create_actor_assertion(
        authority.actor, security.FieldTestAccess.FIELD, SECRET,
        account_generation=generation, auth_epoch=epoch,
    )
    app = FastAPI()
    for method, route in [
        ("POST", "/navigation/walking"), ("POST", "/reports/v2"),
        ("GET", "/privacy/consent-bootstrap"), ("GET", "/reports/mine"),
    ]:
        app.add_api_route(route, endpoint, methods=[method])
    app.add_middleware(security.FieldTestSecurityMiddleware, settings=settings)

    async def exercise():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                     base_url="http://testserver") as client:
            return await client.request(
                "GET" if path in {"/privacy/consent-bootstrap", "/reports/mine"} else "POST",
                path, headers={
                    "x-walksafe-field-test-token": TOKEN,
                    "x-walksafe-actor-id": authority.actor,
                    "x-walksafe-account-generation": str(generation),
                    "x-walksafe-actor-assertion": assertion,
                },
            )
    return asyncio.run(exercise())


@pytest.mark.parametrize("path", [
    "/navigation/walking", "/reports/v2", "/privacy/consent-bootstrap", "/reports/mine",
])
def test_backend_only_epoch_change_blocks_next_request(authority, path):
    assert request(authority, path=path).status_code == 200
    authority.account.auth_epoch = 5
    assert request(authority, path=path).status_code in {401, 404}
    assert authority.reached == 1
    assert authority.queries == 2
    assert request(authority, epoch=5, path=path).status_code == 200


@pytest.mark.parametrize("change", ["epoch_missing", "generation", "locked", "account_missing"])
def test_registered_account_cannot_downgrade_or_cross_account_state(authority, change):
    epoch = 4
    if change == "epoch_missing":
        epoch = None
    elif change == "generation":
        authority.account.account_generation = 8
    elif change == "locked":
        authority.account.status = "LOCKED"
    else:
        authority.account = None
    assert request(authority, epoch=epoch).status_code == 401
    assert authority.reached == 0


def test_legacy_configured_actor_compatibility_requires_absent_backend_account(authority):
    authority.account = None
    assert request(authority, epoch=None).status_code == 200
    assert authority.queries == 1


def test_database_failure_is_closed_and_does_not_expose_error(authority):
    authority.error = SQLAlchemyError("private database detail")
    response = request(authority)
    assert response.status_code == 503
    assert response.headers["cache-control"] == "no-store"
    assert response.json()["detail"]["code"] == "account_authentication_unavailable"
    assert "private" not in response.text
    assert authority.reached == 0


def test_invalid_signature_never_queries_current_account(authority):
    response = request(authority, proof="v3.2000000000.4." + "a" * 43)
    assert response.status_code == 401
    assert authority.queries == 0


def session_current_request(
    authority, *, proof=None, generation=7, token=TOKEN,
    actor=None, admin_header=None, security_enabled=True,
):
    settings = SimpleNamespace(
        field_test_security_enabled=security_enabled, field_test_token=TOKEN,
        admin_token=ADMIN_TOKEN, gateway_session_secret=SECRET,
        walksafe_environment="test", allow_insecure_local_dev=False,
        actor_rate_limit_store="memory", actor_assertion_v3_policy="accept",
    )
    app = FastAPI()
    app.include_router(accounts.create_router(
        settings, sender=object(), service=object(), rate_limiter=object(),
    ))
    app.add_middleware(security.FieldTestSecurityMiddleware, settings=settings)
    if proof is None:
        proof = security.create_actor_assertion(
            authority.actor, security.FieldTestAccess.FIELD, SECRET,
            account_generation=generation, auth_epoch=4,
        )
    headers = {
        "x-walksafe-field-test-token": token,
        "x-walksafe-actor-id": actor or authority.actor,
        "x-walksafe-account-generation": str(generation),
    }
    if proof:
        headers["x-walksafe-actor-assertion"] = proof
    if admin_header:
        headers["x-walksafe-admin-token"] = admin_header

    async def exercise():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://testserver",
        ) as client:
            return await client.post("/accounts/session-current", headers=headers)

    return asyncio.run(exercise())


def test_session_current_accepts_only_current_active_v3_account(authority):
    response = session_current_request(authority)
    assert response.status_code == 204
    assert response.content == b""
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"
    assert authority.queries == 1


@pytest.mark.parametrize("change", [
    "revoked_epoch", "generation", "locked", "deleted", "missing_account",
])
def test_session_current_denies_stale_or_inactive_account(authority, change):
    if change == "revoked_epoch":
        authority.account.auth_epoch = 5
    elif change == "generation":
        authority.account.account_generation = 8
    elif change == "locked":
        authority.account.status = "LOCKED"
    elif change == "deleted":
        authority.account.status = "DELETED"
    else:
        authority.account = None
    response = session_current_request(authority)
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "account_authentication_failed"
    assert response.headers["cache-control"] == "no-store"
    assert authority.queries == 1


@pytest.mark.parametrize("version", ["v1", "v2", "missing", "wrong_actor"])
def test_session_current_denies_legacy_or_unbound_assertion(authority, version):
    proof = None
    actor = None
    if version == "v1":
        proof = security.create_actor_assertion(
            authority.actor, security.FieldTestAccess.FIELD, SECRET,
        )
    elif version == "v2":
        proof = security.create_actor_assertion(
            authority.actor, security.FieldTestAccess.FIELD, SECRET,
            account_generation=7,
        )
    elif version == "missing":
        proof = ""
    else:
        actor = "another-actor"
    response = session_current_request(authority, proof=proof, actor=actor)
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "account_authentication_failed"
    assert response.headers["cache-control"] == "no-store"
    assert authority.queries == 0


@pytest.mark.parametrize("security_enabled", [True, False])
@pytest.mark.parametrize("token", ["", "not-the-service-token"])
def test_session_current_requires_service_token_even_without_middleware_enforcement(
    authority, security_enabled, token,
):
    response = session_current_request(
        authority, token=token, security_enabled=security_enabled,
    )
    assert response.status_code == 401
    assert response.headers["cache-control"] == "no-store"
    assert authority.queries == 0


@pytest.mark.parametrize("security_enabled", [True, False])
def test_session_current_auth_denials_have_one_response(authority, security_enabled):
    responses = [
        session_current_request(
            authority, token="", admin_header=ADMIN_TOKEN,
            security_enabled=security_enabled,
        ),
        session_current_request(authority, token="", security_enabled=security_enabled),
        session_current_request(
            authority, token="wrong-service-token", security_enabled=security_enabled,
        ),
        session_current_request(
            authority, proof="invalid-assertion", security_enabled=security_enabled,
        ),
        session_current_request(
            authority, actor="another-actor", security_enabled=security_enabled,
        ),
    ]
    expected_body = {
        "detail": {
            "code": "account_authentication_failed",
            "message": "The account session is not authenticated.",
        }
    }
    for response in responses:
        assert response.status_code == 401
        assert response.json() == expected_body
        assert response.headers["cache-control"] == "no-store"
    assert authority.queries == 0


def test_session_current_database_failure_is_503_without_detail(authority):
    authority.error = SQLAlchemyError("private database detail")
    response = session_current_request(authority)
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "account_authentication_unavailable"
    assert "private" not in response.text
    assert response.headers["cache-control"] == "no-store"
    assert authority.queries == 1
