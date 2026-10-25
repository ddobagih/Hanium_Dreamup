"""S19-52/S20-41: request-bound v4 actor assertions and the v3 parallel period."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
from time import time
from types import SimpleNamespace
import uuid

import httpx
import pytest
from fastapi import FastAPI, Request
from starlette.responses import JSONResponse

from backend.app import database
from backend.app import field_test_security as security
from backend.app.api import accounts
from backend.app.config import Settings

SECRET = "actor-assertion-v4-contract-secret-0123456789abcdef"
TOKEN = "v4-internal-token-1234567890123456789012"
FIELD = security.FieldTestAccess.FIELD
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
# Shared with apps/android-gateway/test/backend-actor-assertion-v4.test.ts.
GOLDEN_JTI = "AAECAwQFBgcICQoLDA0ODw"
GOLDEN_BODY = b'{"a":1}'
GOLDEN_TOKEN = (
    "v4.2000000000.2000000030.4.5cJ6Zd4ib6A6VfBn.AAECAwQFBgcICQoLDA0ODw."
    "JeJYNRh50Ir9V5hTp7lD1DhtNKyZb0-a4ilMcalpwiw"
)


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def _golden_binding(**overrides):
    binding = dict(
        actor_id="v4-contract-actor",
        access=FIELD,
        secret=SECRET,
        account_generation=7,
        method="POST",
        target="/navigation/walking?probe=1",
        body_sha256=hashlib.sha256(GOLDEN_BODY).hexdigest(),
        now=2_000_000_000,
    )
    binding.update(overrides)
    return binding


def _signed(message_lines: list[str], *, iat: int, exp: int, epoch: int, kid: str, jti: str) -> str:
    signature = _b64(hmac.new(SECRET.encode(), "\n".join(message_lines).encode(), hashlib.sha256).digest())
    return f"v4.{iat}.{exp}.{epoch}.{kid}.{jti}.{signature}"


def _message(*, aud="walksafe-backend", iat=2_000_000_000, exp=2_000_000_030, jti=GOLDEN_JTI):
    kid = security.actor_assertion_key_id(SECRET)
    return [
        "walksafe-backend-actor-v4",
        f"aud:{aud}",
        f"kid:{kid}",
        "access:field",
        "actor:v4-contract-actor",
        "generation:7",
        "epoch:4",
        f"iat:{iat}",
        f"exp:{exp}",
        f"jti:{jti}",
        "method:POST",
        "target:/navigation/walking?probe=1",
        f"body-sha256:{hashlib.sha256(GOLDEN_BODY).hexdigest()}",
    ]


def test_v4_matches_the_cross_language_wire_contract():
    kid = security.actor_assertion_key_id(SECRET)
    assert kid == "5cJ6Zd4ib6A6VfBn"
    assert _signed(_message(), iat=2_000_000_000, exp=2_000_000_030, epoch=4, kid=kid, jti=GOLDEN_JTI) == GOLDEN_TOKEN
    created = security.create_actor_assertion_v4(
        "v4-contract-actor", FIELD, SECRET,
        account_generation=7, auth_epoch=4, method="POST",
        target="/navigation/walking?probe=1",
        body_sha256=hashlib.sha256(GOLDEN_BODY).hexdigest(),
        issued_at=2_000_000_000, jti=GOLDEN_JTI,
    )
    assert created == GOLDEN_TOKEN
    verified = security.verify_actor_assertion_v4(GOLDEN_TOKEN, **_golden_binding())
    assert verified is not None
    assert verified.auth_epoch == 4
    assert verified.jti == GOLDEN_JTI
    assert verified.expires_at == 2_000_000_030
    assert security.actor_assertion_version(GOLDEN_TOKEN) == "v4"


@pytest.mark.parametrize("change", [
    {"method": "PUT"},
    {"method": "GET"},
    {"target": "/navigation/walking"},
    {"target": "/navigation/walking?probe=2"},
    {"target": "/reports/v2?probe=1"},
    {"target": "/Navigation/walking?probe=1"},
    {"body_sha256": hashlib.sha256(b'{"a":2}').hexdigest()},
    {"body_sha256": EMPTY_SHA256},
    {"actor_id": "other-actor"},
    {"account_generation": 8},
    {"access": security.FieldTestAccess.ADMIN},
    {"secret": SECRET + "-rotated"},
])
def test_v4_rejects_a_different_request_or_identity(change):
    assert security.verify_actor_assertion_v4(GOLDEN_TOKEN, **_golden_binding(**change)) is None


def test_v4_rejects_other_audience_and_key_id():
    kid = security.actor_assertion_key_id(SECRET)
    other_aud = _signed(_message(aud="walksafe-speech"), iat=2_000_000_000, exp=2_000_000_030, epoch=4, kid=kid, jti=GOLDEN_JTI)
    assert security.verify_actor_assertion_v4(other_aud, **_golden_binding()) is None
    parts = GOLDEN_TOKEN.split(".")
    parts[4] = "A" * 16
    assert security.verify_actor_assertion_v4(".".join(parts), **_golden_binding()) is None
    parts = GOLDEN_TOKEN.split(".")
    parts[3] = "5"
    assert security.verify_actor_assertion_v4(".".join(parts), **_golden_binding()) is None


def test_v4_lifetime_is_short_and_enforced():
    assert security.verify_actor_assertion_v4(GOLDEN_TOKEN, **_golden_binding(now=2_000_000_030)) is not None
    assert security.verify_actor_assertion_v4(GOLDEN_TOKEN, **_golden_binding(now=2_000_000_031)) is None
    assert security.verify_actor_assertion_v4(GOLDEN_TOKEN, **_golden_binding(now=1_999_999_995)) is not None
    assert security.verify_actor_assertion_v4(GOLDEN_TOKEN, **_golden_binding(now=1_999_999_994)) is None
    kid = security.actor_assertion_key_id(SECRET)
    too_long = _signed(_message(exp=2_000_000_031), iat=2_000_000_000, exp=2_000_000_031, epoch=4, kid=kid, jti=GOLDEN_JTI)
    assert security.verify_actor_assertion_v4(too_long, **_golden_binding()) is None
    inverted = _signed(_message(exp=2_000_000_000), iat=2_000_000_000, exp=2_000_000_000, epoch=4, kid=kid, jti=GOLDEN_JTI)
    assert security.verify_actor_assertion_v4(inverted, **_golden_binding()) is None
    with pytest.raises(ValueError):
        security.create_actor_assertion_v4(
            "v4-contract-actor", FIELD, SECRET, account_generation=7, auth_epoch=4,
            method="POST", target="/navigation/walking", body_sha256=EMPTY_SHA256,
            issued_at=2_000_000_000, expires_at=2_000_000_031,
        )


@pytest.mark.parametrize("token", [
    "", "v4", "v4.2000000000.2000000030.4.5cJ6Zd4ib6A6VfBn.AAECAwQFBgcICQoLDA0ODw",
    GOLDEN_TOKEN + ".extra", GOLDEN_TOKEN.replace("v4.", "v5.", 1),
    GOLDEN_TOKEN.replace(".4.", ".04.", 1), GOLDEN_TOKEN.replace(".4.", ".-4.", 1),
    GOLDEN_TOKEN.replace("AAECAwQFBgcICQoLDA0ODw", "AAECAwQFBgcICQoLDA0OD", 1),
    GOLDEN_TOKEN.replace("2000000030", "2e9", 1),
])
def test_v4_malformed_tokens_fail_closed(token):
    assert security.verify_actor_assertion_v4(token, **_golden_binding()) is None


def test_replay_cache_is_bounded_and_expires():
    cache = security.ActorAssertionReplayCache(max_entries=2)
    assert cache.claim("a" * 22, expires_at=100, now=90) is True
    assert cache.claim("a" * 22, expires_at=100, now=91) is False
    assert cache.claim("b" * 22, expires_at=101, now=91) is True
    with pytest.raises(security.ActorAssertionReplayCacheFull):
        cache.claim("c" * 22, expires_at=102, now=92)
    # Identifiers stay registered through exp + 5 s skew, then are released.
    with pytest.raises(security.ActorAssertionReplayCacheFull):
        cache.claim("c" * 22, expires_at=140, now=105)
    assert cache.claim("c" * 22, expires_at=140, now=106) is True
    assert cache.claim("b" * 22, expires_at=140, now=106) is False
    assert cache.claim("a" * 22, expires_at=140, now=107) is True


@pytest.fixture
def authority(monkeypatch):
    state = SimpleNamespace(
        account=SimpleNamespace(account_generation=7, auth_epoch=4, status="ACTIVE"),
        queries=0, reached=0, bodies=[], actor=f"v4-{uuid.uuid4().hex[:24]}",
    )

    class DB:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def execute(self, _query):
            state.queries += 1
            return SimpleNamespace(one_or_none=lambda: state.account)

    monkeypatch.setattr(database, "SessionLocal", DB)
    return state


def _settings(policy: str | None = None, *, secret: str = SECRET):
    values = dict(
        field_test_security_enabled=True, field_test_token=TOKEN, admin_token="",
        gateway_session_secret=secret, walksafe_environment="test",
        allow_insecure_local_dev=False, actor_rate_limit_store="memory",
        max_upload_bytes=1024 * 1024, max_report_metadata_bytes=64 * 1024,
    )
    if policy is not None:
        values["actor_assertion_v3_policy"] = policy
    return SimpleNamespace(**values)


def _v4(authority, method: str, target: str, body: bytes = b"", **overrides) -> str:
    values = dict(
        account_generation=7, auth_epoch=4, method=method, target=target,
        body_sha256=hashlib.sha256(body).hexdigest(),
    )
    values.update(overrides)
    return security.create_actor_assertion_v4(authority.actor, FIELD, SECRET, **values)


def _v3(authority) -> str:
    return security.create_actor_assertion(
        authority.actor, FIELD, SECRET, account_generation=7, auth_epoch=4,
    )


def _app(authority, settings):
    async def endpoint(request: Request):
        authority.reached += 1
        body = await request.body()
        authority.bodies.append(body)
        verified = request.scope["state"]["verified_actor_assertion"]
        return JSONResponse({"epoch": verified.auth_epoch, "sha": hashlib.sha256(body).hexdigest()})

    app = FastAPI()
    for method, route in [
        ("POST", "/navigation/walking"), ("POST", "/reports/v2"),
        ("GET", "/reports/mine"), ("POST", "/privacy/consent-events"),
    ]:
        app.add_api_route(route, endpoint, methods=[method])
    app.add_middleware(security.FieldTestSecurityMiddleware, settings=settings)
    return app


def _send(app, authority, method: str, target: str, assertion: str, body: bytes = b""):
    async def exercise():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://testserver",
        ) as client:
            return await client.request(
                method, target, content=body if method != "GET" else None,
                headers={
                    "x-walksafe-field-test-token": TOKEN,
                    "x-walksafe-actor-id": authority.actor,
                    "x-walksafe-account-generation": "7",
                    "x-walksafe-actor-assertion": assertion,
                    "content-type": "application/json",
                },
            )

    return asyncio.run(exercise())


@pytest.mark.parametrize("target", ["/navigation/walking", "/reports/v2"])
def test_middleware_accepts_v4_and_replays_the_exact_body(authority, target):
    app = _app(authority, _settings())
    body = b'{"origin":{"lat":37.5,"lng":127.0}}'
    response = _send(app, authority, "POST", target, _v4(authority, "POST", target, body), body)
    assert response.status_code == 200, response.text
    assert response.json() == {"epoch": 4, "sha": hashlib.sha256(body).hexdigest()}
    assert authority.bodies == [body]
    target = "/reports/mine?limit=10&cursor=a%2Fb"
    response = _send(app, authority, "GET", target, _v4(authority, "GET", target))
    assert response.status_code == 200, response.text


def test_signed_destination_search_keeps_provider_alive_after_body_replay(authority, monkeypatch):
    from backend.app.api import navigation
    from backend.app.schemas import DestinationSearchResponse, DestinationSearchResult, RoutePoint

    provider_calls = []
    response_body = DestinationSearchResponse(
        schema_version="walksafe.destination_search.v1", provider="tmap_poi", query="서울역",
        results=[DestinationSearchResult(
            id="test-poi", name="서울역", point=RoutePoint(latitude=37.55, longitude=126.97),
        )],
    )

    async def provider(query, _settings, **kwargs):
        provider_calls.append(query)
        # Keep work pending while the route's disconnect watcher drains replay.
        await asyncio.sleep(0.02)
        return response_body

    monkeypatch.setattr(navigation, "fetch_tmap_poi_search", provider)
    settings = _settings()
    app = FastAPI()
    app.include_router(navigation.create_router(settings))
    app.add_middleware(security.FieldTestSecurityMiddleware, settings=settings)
    target = "/navigation/destinations/search?query=%EC%84%9C%EC%9A%B8%EC%97%AD"
    response = _send(app, authority, "GET", target, _v4(authority, "GET", target))
    assert response.status_code == 200, response.text
    assert response.json() == response_body.model_dump(mode="json")
    assert provider_calls == ["서울역"]
    assert authority.queries == 1


def test_middleware_rejects_v4_reused_for_another_request(authority):
    app = _app(authority, _settings())
    body = b'{"origin":1}'
    assertion = _v4(authority, "POST", "/navigation/walking", body)
    for method, target, sent in [
        ("POST", "/navigation/walking", b'{"origin":2}'),
        ("POST", "/navigation/walking", body + b" "),
        ("POST", "/reports/v2", body),
        ("POST", "/navigation/walking?x=1", body),
        ("POST", "/privacy/consent-events", body),
    ]:
        response = _send(app, authority, method, target, assertion, sent)
        assert response.status_code in {401, 404}, (target, sent, response.text)
    listed = _v4(authority, "GET", "/reports/mine?limit=10")
    assert _send(app, authority, "GET", "/reports/mine?limit=11", listed).status_code in {401, 404}
    assert authority.reached == 0
    assert authority.queries == 0


def test_middleware_rejects_reused_jti_and_expired_v4(authority):
    app = _app(authority, _settings())
    body = b'{"origin":1}'
    assertion = _v4(authority, "POST", "/navigation/walking", body)
    assert _send(app, authority, "POST", "/navigation/walking", assertion, body).status_code == 200
    replay = _send(app, authority, "POST", "/navigation/walking", assertion, body)
    assert replay.status_code == 401
    assert replay.json()["detail"]["code"] == "actor_assertion_replayed"
    now = int(time())
    expired = _v4(
        authority, "POST", "/navigation/walking", body,
        issued_at=now - 40, expires_at=now - 10,
    )
    response = _send(app, authority, "POST", "/navigation/walking", expired, body)
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "actor_assertion_invalid"
    assert authority.reached == 1


def test_forged_v4_does_not_consume_the_genuine_jti(authority):
    app = _app(authority, _settings())
    body = b'{"origin":1}'
    genuine = _v4(authority, "POST", "/navigation/walking", body)
    forged_parts = genuine.split(".")
    forged_parts[6] = "A" * 43
    assert _send(app, authority, "POST", "/navigation/walking", ".".join(forged_parts), body).status_code == 401
    assert _send(app, authority, "POST", "/navigation/walking", genuine, body).status_code == 200


@pytest.mark.parametrize(("policy", "post_status", "get_status"), [
    # w24 S20-41 / owner decision 7: no attribute means the safe-methods default.
    (None, 401, 200),
    ("accept", 200, 200),
    ("safe-methods", 401, 200),
    ("reject", 401, 401),
    ("unknown-value", 401, 401),
])
def test_v3_parallel_period_policy(authority, policy, post_status, get_status):
    app = _app(authority, _settings(policy))
    post = _send(app, authority, "POST", "/navigation/walking", _v3(authority), b"{}")
    assert post.status_code == post_status
    get = _send(app, authority, "GET", "/reports/mine", _v3(authority))
    assert get.status_code in ({get_status} if get_status == 200 else {401, 404})
    body = b"{}"
    assert _send(
        app, authority, "POST", "/navigation/walking",
        _v4(authority, "POST", "/navigation/walking", body), body,
    ).status_code == 200


def _session_current(authority, assertion, *, policy=None, body=None):
    settings = _settings(policy)
    app = FastAPI()
    app.include_router(accounts.create_router(
        settings, sender=object(), service=object(), rate_limiter=object(),
    ))
    app.add_middleware(security.FieldTestSecurityMiddleware, settings=settings)

    async def exercise():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://testserver",
        ) as client:
            return await client.post("/accounts/session-current", content=body, headers={
                "x-walksafe-field-test-token": TOKEN,
                "x-walksafe-actor-id": authority.actor,
                "x-walksafe-account-generation": "7",
                "x-walksafe-actor-assertion": assertion,
            })

    return asyncio.run(exercise())


def test_session_current_accepts_bound_v4_once(authority):
    assertion = _v4(authority, "POST", "/accounts/session-current")
    assert _session_current(authority, assertion).status_code == 204
    assert _session_current(authority, assertion).status_code == 401
    assert authority.queries == 1


@pytest.mark.parametrize("change", ["method", "path", "body", "legacy_epoch", "sent_body"])
def test_session_current_rejects_unbound_v4(authority, change):
    body = None
    if change == "method":
        assertion = _v4(authority, "GET", "/accounts/session-current")
    elif change == "path":
        assertion = _v4(authority, "POST", "/accounts/authenticate")
    elif change == "body":
        assertion = _v4(authority, "POST", "/accounts/session-current", b"{}")
    elif change == "legacy_epoch":
        assertion = _v4(authority, "POST", "/accounts/session-current", auth_epoch=None)
    else:
        assertion = _v4(authority, "POST", "/accounts/session-current")
        body = b"{}"
    response = _session_current(authority, assertion, body=body)
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "account_authentication_failed"
    assert authority.queries == 0


@pytest.mark.parametrize(("policy", "status"), [
    (None, 401), ("accept", 204), ("safe-methods", 401), ("reject", 401),
])
def test_session_current_v3_follows_parallel_policy(authority, policy, status):
    assert _session_current(authority, _v3(authority), policy=policy).status_code == status


def test_settings_v3_policy_default_and_validation(monkeypatch):
    monkeypatch.delenv("WALKSAFE_ACTOR_ASSERTION_V3_POLICY", raising=False)
    # w24 S20-41 / owner decision 7: every write requires v4 by default.
    assert Settings().actor_assertion_v3_policy == "safe-methods"
    assert security.legacy_actor_assertion_allowed(SimpleNamespace(), "POST") is False
    assert security.legacy_actor_assertion_allowed(SimpleNamespace(), "GET") is True
    for value in ["accept", "safe-methods", "reject"]:
        monkeypatch.setenv("WALKSAFE_ACTOR_ASSERTION_V3_POLICY", value)
        assert Settings().actor_assertion_v3_policy == value
    monkeypatch.setenv("WALKSAFE_ACTOR_ASSERTION_V3_POLICY", "off")
    with pytest.raises(ValueError):
        Settings()


def _deployment_environment(monkeypatch, environment):
    # Same minimal deployment settings as test_field_test_security's
    # rate-limit-store check; the V3 policy check follows that check directly.
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", environment)
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "a" * 40)
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", "field-token-for-v3-policy-deployment-0123")
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", "admin-token-for-v3-policy-deployment-0123")
    monkeypatch.setenv(
        "WALKSAFE_GATEWAY_SESSION_SECRET", "gateway-session-secret-for-tests-1234567890"
    )
    monkeypatch.setenv("WALKSAFE_ACTOR_RATE_LIMIT_STORE", "postgresql")


@pytest.mark.parametrize("environment", ["field", "staging", "production"])
def test_deployment_environments_refuse_the_accept_v3_policy(monkeypatch, environment):
    # w24 misc-F3 / owner decision 7: one variable must not reopen request-unbound
    # v1-v3 writes in a deployment; "accept" stays a legacy compatibility-test value.
    _deployment_environment(monkeypatch, environment)
    monkeypatch.setenv("WALKSAFE_ACTOR_ASSERTION_V3_POLICY", "accept")
    with pytest.raises(ValueError, match="V3_POLICY=safe-methods or reject"):
        Settings()
    # Positive control: with the safe values the same environment gets past the
    # V3 policy check (a later, unrelated deployment check may still stop it).
    for value in ("safe-methods", "reject", None):
        if value is None:
            monkeypatch.delenv("WALKSAFE_ACTOR_ASSERTION_V3_POLICY", raising=False)
        else:
            monkeypatch.setenv("WALKSAFE_ACTOR_ASSERTION_V3_POLICY", value)
        try:
            settings = Settings()
        except ValueError as error:
            assert "V3_POLICY" not in str(error), (value, str(error))
        else:
            assert settings.actor_assertion_v3_policy == (value or "safe-methods")
    # Outside deployments the legacy compatibility value still parses.
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.setenv("WALKSAFE_ACTOR_ASSERTION_V3_POLICY", "accept")
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "false")
    assert Settings().actor_assertion_v3_policy == "accept"


def test_env_example_documents_the_v3_policy_and_its_default(monkeypatch):
    # S20-41-F2: the operator template names the variable, its values, the
    # switch order and a default that the Settings parser accepts.
    from pathlib import Path

    example = (Path(__file__).resolve().parents[1] / ".env.example").read_text(encoding="utf-8")
    assignments = [
        line for line in example.splitlines()
        if line.startswith("WALKSAFE_ACTOR_ASSERTION_V3_POLICY=")
    ]
    assert assignments == ["WALKSAFE_ACTOR_ASSERTION_V3_POLICY=safe-methods"]
    for value in ("accept", "safe-methods", "reject"):
        assert f"#   {value}" in example
    assert "#   safe-methods (default)" in example
    assert "safe-methods -> reject" in example
    # The legacy PWA v1 runner is given up, not silently kept (owner decision 7).
    assert "LEGACY_REFERENCE_ONLY" in example
    monkeypatch.setenv("WALKSAFE_ACTOR_ASSERTION_V3_POLICY", assignments[0].split("=", 1)[1])
    assert Settings().actor_assertion_v3_policy == "safe-methods"


# --- w23 fix_assertion: S20-41-F1 (no body buffering before the v4 pre-check) ---

RAW_CHUNK_TARGET = (
    "/raw-collections/11111111-1111-4111-8111-111111111111"
    "/objects/22222222-2222-4222-8222-222222222222/chunks/0"
)


def _asgi_exchange(authority, settings, method, target, assertion, *, chunks, content_length=None):
    """Drive the middleware directly and count the body bytes it pulls."""
    reached = []

    async def inner(scope, receive, send):
        body = b""
        while True:
            message = await receive()
            body += message.get("body", b"")
            if not message.get("more_body", False):
                break
        reached.append(body)
        await JSONResponse({"ok": True})(scope, receive, send)

    middleware = security.FieldTestSecurityMiddleware(inner, settings=settings)
    # Route policy resolution reads scope["app"].routes; handlers here are unused.
    routes = FastAPI()
    routes.add_api_route("/navigation/walking", inner, methods=["POST"])
    routes.add_api_route(
        "/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}", inner, methods=["PUT"],
    )
    path, _, query = target.partition("?")
    headers = [
        (b"x-walksafe-field-test-token", TOKEN.encode()),
        (b"x-walksafe-actor-id", authority.actor.encode()),
        (b"x-walksafe-account-generation", b"7"),
        (b"x-walksafe-actor-assertion", assertion.encode()),
        (b"content-type", b"application/octet-stream"),
    ]
    if content_length is not None:
        headers.append((b"content-length", str(content_length).encode()))
    scope = {
        "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
        "method": method, "scheme": "http", "path": path, "raw_path": path.encode(),
        "query_string": query.encode(), "root_path": "", "headers": headers,
        "client": ("127.0.0.1", 50000), "server": ("testserver", 80), "state": {},
        "app": routes,
    }
    pending = list(chunks)
    pulled = {"bytes": 0, "messages": 0}

    async def receive():
        if not pending:
            return {"type": "http.disconnect"}
        chunk = pending.pop(0)
        pulled["bytes"] += len(chunk)
        pulled["messages"] += 1
        return {"type": "http.request", "body": chunk, "more_body": bool(pending)}

    sent = []

    async def send(message):
        sent.append(message)

    asyncio.run(middleware(scope, receive, send))
    start = next(message for message in sent if message["type"] == "http.response.start")
    body = b"".join(message.get("body", b"") for message in sent if message["type"] == "http.response.body")
    headers_out = {name.decode().lower(): value.decode() for name, value in start.get("headers", [])}
    return start["status"], json.loads(body), headers_out, pulled, reached


def _bad_v4(authority, kind, method, target, body):
    now = int(time())
    if kind == "pending":
        return "v4.pending"
    if kind == "garbage":
        return "v4.x"
    if kind == "kid_mismatch":
        parts = _v4(authority, method, target, body).split(".")
        parts[4] = "A" * 16
        return ".".join(parts)
    if kind == "expired":
        return _v4(authority, method, target, body, issued_at=now - 40, expires_at=now - 10)
    if kind == "future_iat":
        return _v4(authority, method, target, body, issued_at=now + 60)
    if kind == "generation_mismatch":
        return _v4(authority, method, target, body, account_generation=8)
    raise AssertionError(kind)


@pytest.mark.parametrize("target", ["/navigation/walking", RAW_CHUNK_TARGET])
@pytest.mark.parametrize("kind", ["pending", "garbage", "kid_mismatch", "expired", "future_iat"])
def test_invalid_v4_is_rejected_before_any_body_byte_is_read(authority, kind, target):
    method = "PUT" if target == RAW_CHUNK_TARGET else "POST"
    body = b"x" * (256 * 1024)
    chunks = [body[i:i + 65536] for i in range(0, len(body), 65536)]
    status, payload, _headers, pulled, reached = _asgi_exchange(
        authority, _settings(), method, target, _bad_v4(authority, kind, method, target, body),
        chunks=chunks, content_length=len(body),
    )
    assert status == 401, payload
    assert payload["detail"]["code"] == "actor_assertion_invalid"
    assert pulled == {"bytes": 0, "messages": 0}
    assert reached == []
    assert authority.queries == 0


def test_generation_mismatch_v4_is_rejected_before_the_body_is_read(authority):
    # The header generation (7) is not what the token was signed for (8): the
    # body is still read only after the pre-check, and the signature then fails.
    body = b'{"origin":1}'
    status, payload, _headers, pulled, reached = _asgi_exchange(
        authority, _settings(), "POST", "/navigation/walking",
        _bad_v4(authority, "generation_mismatch", "POST", "/navigation/walking", body),
        chunks=[body], content_length=len(body),
    )
    assert status == 401 and payload["detail"]["code"] == "actor_assertion_invalid"
    assert pulled["bytes"] == len(body)
    assert reached == []


def test_valid_v4_body_is_read_once_after_the_pre_check(authority):
    body = b"y" * 200_000
    chunks = [body[i:i + 65536] for i in range(0, len(body), 65536)]
    status, payload, _headers, pulled, reached = _asgi_exchange(
        authority, _settings(), "POST", "/navigation/walking",
        _v4(authority, "POST", "/navigation/walking", body), chunks=chunks, content_length=len(body),
    )
    assert status == 200, payload
    assert pulled["bytes"] == len(body)
    assert reached == [body]


@pytest.mark.parametrize("declared", [True, False], ids=["content-length", "streamed"])
def test_raw_v4_body_limit_keeps_the_raw_collection_code(authority, declared):
    limit = security.max_request_body_bytes(_settings(), path=RAW_CHUNK_TARGET)
    body = b"z" * (limit + 1)
    chunks = [body[i:i + 1024 * 1024] for i in range(0, len(body), 1024 * 1024)]
    status, payload, headers, _pulled, reached = _asgi_exchange(
        authority, _settings(), "PUT", RAW_CHUNK_TARGET,
        _v4(authority, "PUT", RAW_CHUNK_TARGET, body),
        chunks=chunks, content_length=len(body) if declared else None,
    )
    assert status == 413, payload
    assert payload["detail"]["code"] == "raw_collection_request_body_too_large"
    assert payload["detail"]["max_bytes"] == limit
    assert headers.get("cache-control") == "no-store"
    assert reached == []


def test_field_v4_body_limit_keeps_the_generic_code(authority):
    settings = _settings()
    limit = security.max_request_body_bytes(settings, path="/navigation/walking")
    body = b"w" * (limit + 1)
    status, payload, _headers, pulled, reached = _asgi_exchange(
        authority, settings, "POST", "/navigation/walking",
        _v4(authority, "POST", "/navigation/walking", body),
        chunks=[body], content_length=len(body),
    )
    assert status == 413
    assert payload["detail"] == {"code": "request_body_too_large", "max_bytes": limit}
    assert pulled["bytes"] == 0
    assert reached == []


@pytest.mark.parametrize("target", ["/navigation/walking", RAW_CHUNK_TARGET])
def test_incomplete_v4_body_uses_a_non_admin_code(authority, target):
    method = "PUT" if target == RAW_CHUNK_TARGET else "POST"
    body = b'{"origin":1}'
    status, payload, _headers, _pulled, reached = _asgi_exchange(
        authority, _settings(), method, target, _v4(authority, method, target, body),
        chunks=[body[:5]], content_length=len(body),
    )
    assert status == 400, payload
    assert payload["detail"]["code"] == "actor_assertion_body_incomplete"
    assert "admin" not in payload["detail"]["code"]
    assert reached == []


# --- w23 fix_assertion: S20-41-F3 (replay register accounting and 503 code) ---

def test_replay_cache_releases_expired_entries_behind_a_long_lived_head():
    cache = security.ActorAssertionReplayCache(max_entries=3)
    assert cache.claim("a" * 22, expires_at=1_000, now=90) is True  # long-lived head
    assert cache.claim("b" * 22, expires_at=100, now=90) is True
    assert cache.claim("c" * 22, expires_at=100, now=90) is True
    with pytest.raises(security.ActorAssertionReplayCacheFull):
        cache.claim("d" * 22, expires_at=120, now=105)  # b, c retained through 105
    assert cache.claim("d" * 22, expires_at=120, now=106) is True
    assert cache.claim("e" * 22, expires_at=120, now=106) is True
    with pytest.raises(security.ActorAssertionReplayCacheFull):
        cache.claim("f" * 22, expires_at=120, now=106)
    assert cache.claim("a" * 22, expires_at=1_000, now=106) is False
    assert cache.claim("d" * 22, expires_at=120, now=125) is False
    assert cache.claim("d" * 22, expires_at=140, now=126) is True


def test_full_replay_register_maps_to_the_registered_unavailable_code(authority, monkeypatch):
    cache = security.ActorAssertionReplayCache(max_entries=1)
    monkeypatch.setattr(security, "_ACTOR_ASSERTION_REPLAY_CACHE", cache)
    app = _app(authority, _settings())
    body = b'{"origin":1}'
    first = _send(app, authority, "POST", "/navigation/walking", _v4(authority, "POST", "/navigation/walking", body), body)
    assert first.status_code == 200
    full = _send(app, authority, "POST", "/navigation/walking", _v4(authority, "POST", "/navigation/walking", body), body)
    assert full.status_code == 503
    assert full.json()["detail"]["code"] == "account_authentication_unavailable"
    assert full.headers["cache-control"] == "no-store"
    assert full.headers["retry-after"] == "1"
    session = _session_current(authority, _v4(authority, "POST", "/accounts/session-current"))
    assert session.status_code == 503
    assert session.json()["detail"]["code"] == "account_authentication_unavailable"
    assert authority.reached == 1
