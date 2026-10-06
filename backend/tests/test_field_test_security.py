from __future__ import annotations

import asyncio
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import threading
import time
from types import SimpleNamespace

from fastapi import FastAPI
import httpx
import pytest
from starlette.middleware.cors import CORSMiddleware


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

import backend.app.config as config_module  # noqa: E402
import backend.app.field_test_security as field_security  # noqa: E402
from backend.app.config import Settings, migration_database_url  # noqa: E402
from backend.app.field_test_security import (  # noqa: E402
    ACTOR_RATE_LIMITS,
    FieldTestAccess,
    _ACTOR_RATE_LIMITER,
    _POSTGRES_ACTOR_RATE_LIMITER,
    _RequestBodyIncomplete,
    _request_body_error_response,
    _strict_json_object,
    create_actor_assertion,
    create_actor_assertion_v4,
    required_field_test_access,
    requires_account_generation,
    requires_actor_identity,
    verify_actor_assertion,
)
from backend.app.main import app, settings as app_settings  # noqa: E402
from backend.app.request_limits import (  # noqa: E402
    PrivacyExceptionMiddleware,
    PrivacyNoStoreMiddleware,
)
from backend.app.services.actor_rate_limit import ActorRateLimitStoreUnavailable  # noqa: E402
from backend.app.services.admin_security import AdminSecurityError  # noqa: E402
from model.two_model_runtime import DEFAULT_RUNTIME_CONFIG  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402


FIELD_TOKEN = "field-token-for-tests-1234567890"
ADMIN_TOKEN = "admin-token-for-tests-1234567890"


@pytest.mark.parametrize(
    "raw_body",
    [
        b'\xef\xbb\xbf{"reason":"review evidence"}',
        '{"reason":"review evidence"}'.encode("utf-16"),
        b'{"reason":"first","reason":"second"}',
        b'{"reason":"review\\u0000evidence"}',
        b'{"reason":"review\\ud800evidence"}',
        b'{"expected_content_revision":9223372036854775808}',
        b'{"expected_content_revision":1.0}',
    ],
)
def test_admin_report_mutation_json_rejects_noncanonical_database_input(
    raw_body: bytes,
) -> None:
    with pytest.raises(AdminSecurityError) as rejected:
        _strict_json_object(raw_body)
    assert rejected.value.status_code == 422


@pytest.fixture
def secured_client(monkeypatch: pytest.MonkeyPatch) -> ASGITestClient:
    # This module tests transport authorization for legacy configured actors.
    # Account authority and store failures have dedicated auth-epoch tests.
    monkeypatch.setattr(
        "backend.app.field_test_security._actor_account_session_is_current",
        lambda _actor, _generation, epoch: epoch is None,
    )
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", FIELD_TOKEN)
    monkeypatch.setattr(app_settings, "admin_token", ADMIN_TOKEN)
    monkeypatch.setattr(app_settings, "walksafe_environment", "development")
    monkeypatch.setattr(app_settings, "allow_insecure_local_dev", True)
    monkeypatch.setattr(app_settings, "actor_rate_limit_store", "memory")
    return ASGITestClient(app)


def test_route_access_matrix_separates_field_and_admin_operations() -> None:
    assert required_field_test_access("/detect/v2", "POST") is FieldTestAccess.FIELD
    assert required_field_test_access("/ready", "GET") is FieldTestAccess.FIELD
    assert required_field_test_access("/detect/v2/health", "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access("/navigation/walking", "POST") is FieldTestAccess.FIELD
    assert required_field_test_access("/reports/v2", "POST") is FieldTestAccess.FIELD
    deletion_status_path = (
        "/reports/mine/deletions/00000000-0000-0000-0000-000000000001"
    )
    assert (
        required_field_test_access(deletion_status_path, "GET")
        is FieldTestAccess.FIELD
    )
    assert requires_actor_identity(deletion_status_path, "GET")
    assert requires_account_generation(deletion_status_path, "GET")
    request_status_path = (
        "/reports/mine/00000000-0000-0000-0000-000000000001/requests/"
        "00000000-0000-0000-0000-000000000002"
    )
    assert (
        required_field_test_access(request_status_path, "GET")
        is FieldTestAccess.FIELD
    )
    assert requires_actor_identity(request_status_path, "GET")
    assert requires_account_generation(request_status_path, "GET")
    assert (
        required_field_test_access(
            "/reports/mine/{report_id}/requests/{request_id}", "GET"
        )
        is FieldTestAccess.FIELD
    )
    assert (
        required_field_test_access(
            "/reports/mine/deletions/{request_id}",
            "GET",
        )
        is FieldTestAccess.FIELD
    )
    assert (
        required_field_test_access(
            "/privacy/account-deletions/{request_id}/status",
            "GET",
        )
        is FieldTestAccess.FIELD
    )
    assert (
        required_field_test_access(
            "/privacy/account-deletions/{request_id}/device-evidence",
            "POST",
        )
        is FieldTestAccess.FIELD
    )
    assert required_field_test_access("/privacy/malformed", "GET") is field_security._RoutePolicyFallback.UNREGISTERED
    assert required_field_test_access("/reports/duplicate-check", "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access("/reports", "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access("/reports/export", "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access("/uploads/example.jpg", "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access("/reports/example/status", "PATCH") is FieldTestAccess.ADMIN
    assert required_field_test_access("/docs", "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access("/detect/v2", "OPTIONS") is field_security._RoutePolicyFallback.UNREGISTERED
    assert required_field_test_access("/unknown", "GET") is field_security._RoutePolicyFallback.UNREGISTERED



def test_s20_40_every_registered_http_route_has_one_explicit_policy() -> None:
    registered = []
    for route in app.routes:
        assert route.methods, "A new routing type needs an explicit HTTP policy decision."
        registered.extend((method, route.path) for method in route.methods)
    declared = [(method, template) for method, template, _ in field_security.FIELD_TEST_ROUTE_POLICIES]
    assert len(registered) == len(set(registered))
    assert len(declared) == len(set(declared))
    assert set(registered) == set(declared)
    for method, template, access in field_security.FIELD_TEST_ROUTE_POLICIES:
        assert required_field_test_access(template, method.lower()) is access


def test_s20_40_explicit_roles_preserve_field_and_public_boundaries() -> None:
    expected_field = {
        ("GET", "/health"),
        ("GET", "/ready"),
        ("GET", "/detect/health"),
        ("POST", "/detect"),
        ("POST", "/detect/v2"),
        ("GET", "/navigation/walking/health"),
        ("GET", "/navigation/destinations/search"),
        ("GET", "/navigation/destinations/search/health"),
        ("POST", "/navigation/walking"),
        ("GET", "/privacy/consent-bootstrap"),
        ("POST", "/privacy/consent-events"),
        ("POST", "/privacy/account-deletions"),
        ("GET", "/privacy/account-deletions/{request_id}/status"),
        ("POST", "/privacy/account-deletions/{request_id}/device-evidence"),
        ("POST", "/account-enrollments/email-otp"),
        ("POST", "/accounts"),
        ("POST", "/accounts/authenticate"),
        ("POST", "/accounts/session-current"),
        ("HEAD", "/raw-collections/{collection_id}/manifest"),
        ("HEAD", "/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}"),
        ("HEAD", "/raw-collections/{collection_id}/commit"),
        ("PUT", "/raw-collections/{collection_id}/manifest"),
        ("PUT", "/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}"),
        ("GET", "/raw-collections/{collection_id}"),
        ("POST", "/raw-collections/{collection_id}/commit"),
        ("GET", "/reports/mine"),
        ("GET", "/reports/mine/requests/history"),
        ("GET", "/reports/mine/deletions/{request_id}"),
        ("GET", "/reports/mine/{report_id}"),
        ("POST", "/reports/mine/{report_id}/requests"),
        ("GET", "/reports/mine/{report_id}/requests/{request_id}"),
        ("GET", "/reports/mine/{report_id}/content"),
        ("POST", "/reports/mine/{report_id}/corrections"),
        ("POST", "/reports"),
        ("POST", "/reports/v2"),
        ("GET", "/reports/v2/{report_id}/status"),
        ("GET", "/internal/capacity"),
    }
    expected_public = {
        ("POST", "/admin/security/device-proof/challenges"),
        ("POST", "/admin/security/sessions"),
        ("POST", "/admin/security/recovery/start"),
        ("POST", "/admin/security/recovery/complete"),
    }
    entries = field_security.FIELD_TEST_ROUTE_POLICIES
    assert {(method, path) for method, path, role in entries if role is FieldTestAccess.FIELD} == expected_field
    assert {(method, path) for method, path, role in entries if role is None} == expected_public
    assert all(
        role is FieldTestAccess.ADMIN
        for method, path, role in entries
        if (method, path) not in expected_field | expected_public
    )
    probe = _RoutePolicyProbe((("HEAD", "/docs"), ("GET", "/health")))
    assert probe.request("HEAD", "/docs", role="admin").status_code == 204
    assert probe.request("HEAD", "/health", role="field").status_code == 403
    assert probe.events["downstream"] == 1


@pytest.mark.parametrize("role", ("field", "admin"))
@pytest.mark.parametrize(("method", "path"), (
    ("GET", "/future"),
    ("GET", "/navigation/future"),
    ("POST", "/detect/future"),
    ("GET", "/privacy/future"),
    ("GET", "/admin/future"),
    ("GET", "/docs/future"),
    ("GET", "/detect/v2"),
    ("GET", "/raw-collections/future/extra"),
))
def test_s20_40_registered_but_unapproved_routes_reject_valid_credentials(
    method: str, path: str, role: str,
) -> None:
    probe = _RoutePolicyProbe(((method, path), ("GET", "/health"), ("GET", "/detect/v2/health")))
    denied = probe.request(method, path, role=role)
    assert denied.status_code == 403
    assert denied.json()["detail"]["code"] == "route_policy_not_registered"
    assert probe.events == {"downstream": 0, "receive": 0, "authorize": 0, "audit": 0, "proof": 0}
    positive = "/health" if role == "field" else "/detect/v2/health"
    assert probe.request("GET", positive, role=role).status_code == 204
    assert probe.events["downstream"] == 1
    assert probe.events["receive"] == 0


@pytest.mark.parametrize(("template", "shadow", "ordinary", "role", "access"), (
    (
        "/reports/{report_id}", "/reports/new-read",
        "/reports/00000000-0000-4000-8000-000000000001", "admin", FieldTestAccess.ADMIN,
    ),
    (
        "/reports/mine/{report_id}",
        "/reports/mine/00000000-0000-4000-8000-000000000002",
        "/reports/mine/00000000-0000-4000-8000-000000000001", "field", FieldTestAccess.FIELD,
    ),
))
def test_s20_40_first_selected_route_cannot_inherit_a_later_dynamic_policy(
    template: str, shadow: str, ordinary: str, role: str, access: FieldTestAccess,
) -> None:
    assert required_field_test_access(shadow, "GET") is access
    probe = _RoutePolicyProbe((("GET", shadow), ("GET", template)))
    denied = probe.request("GET", shadow, role=role, identity=True)
    assert denied.status_code == 403
    assert denied.json()["detail"]["code"] == "route_policy_not_registered"
    assert probe.events["downstream"] == probe.events["receive"] == 0
    assert probe.request("GET", ordinary, role=role, identity=True).status_code == 204
    assert probe.events["downstream"] == 1
    # The router's real order also wins when the unapproved static route is hidden.
    reversed_probe = _RoutePolicyProbe((("GET", template), ("GET", shadow)))
    assert reversed_probe.request("GET", shadow, role=role, identity=True).status_code == 204
    assert reversed_probe.events["downstream"] == 1


def test_s20_40_modern_admin_read_denies_before_authorizer_but_known_read_passes() -> None:
    probe = _RoutePolicyProbe(
        (("GET", "/admin/future"), ("GET", "/detect/v2/health")),
        secured=False, modern=True,
    )
    denied = probe.request("GET", "/admin/future", role="admin")
    assert denied.status_code == 403
    assert denied.json()["detail"]["code"] == "route_policy_not_registered"
    assert probe.events == {"downstream": 0, "receive": 0, "authorize": 0, "audit": 0, "proof": 0}
    assert probe.request("GET", "/detect/v2/health", role="admin").status_code == 204
    assert probe.events["authorize"] == probe.events["downstream"] == 1


@pytest.mark.parametrize("audit_unavailable", (False, True))
def test_s20_40_unregistered_admin_write_keeps_its_existing_denial_audit(
    audit_unavailable: bool,
) -> None:
    probe = _RoutePolicyProbe(
        (("POST", "/admin/future"), ("POST", "/detect/future")),
        modern=True, audit_unavailable=audit_unavailable,
    )
    denied = probe.request("POST", "/admin/future", role="admin")
    assert denied.status_code == (503 if audit_unavailable else 403)
    assert denied.json()["detail"]["code"] == (
        "audit_unavailable" if audit_unavailable else "admin_operation_not_registered"
    )
    assert probe.events == {"downstream": 0, "receive": 0, "authorize": 0, "audit": 1, "proof": 0}
    assert probe.audit_rows[0]["action"] == "admin.write.unregistered"
    assert probe.audit_rows[0]["path"] == "/admin/future"
    # A former FIELD-prefix denial must not become a new admin audit policy.
    field_denial = probe.request("POST", "/detect/future", role="field")
    assert field_denial.status_code == 403
    assert field_denial.json()["detail"]["code"] == "route_policy_not_registered"
    assert probe.events["audit"] == 1
    assert probe.events["downstream"] == probe.events["receive"] == probe.events["authorize"] == 0


def test_s20_40_disabled_local_access_does_not_remove_the_raw_bypass_prohibition() -> None:
    probe = _RoutePolicyProbe(
        (("GET", "/local/future"), ("PUT", "/raw-collections/{collection_id}/manifest")),
        secured=False,
    )
    assert probe.request("GET", "/local/future", include_app=False).status_code == 204
    raw = probe.request("PUT", "/raw-collections/collection/manifest")
    assert raw.status_code == 401
    assert raw.json()["detail"]["code"] == "raw_gateway_assertion_required"
    assert raw.headers["cache-control"] == "no-store"
    assert raw.headers["pragma"] == "no-cache"
    assert probe.events["downstream"] == 1
    assert probe.events["receive"] == 0


def test_s20_40_options_405_and_cors_are_not_authorized_handler_fallbacks() -> None:
    probe = _RoutePolicyProbe((("GET", "/health"),))
    response = probe.request("OPTIONS", "/health")
    assert response.status_code == 405
    assert response.json() == {"detail": "Method Not Allowed"}
    assert response.headers["allow"] == "GET"
    assert probe.events["downstream"] == probe.events["receive"] == 0
    assert probe.request("OPTIONS", "/future").status_code == 403
    explicit_options = _RoutePolicyProbe((("GET", "/health"), ("OPTIONS", "/health")))
    assert explicit_options.request("OPTIONS", "/health", role="field").status_code == 403
    assert explicit_options.events["downstream"] == 0
    cors = _RoutePolicyProbe((("POST", "/future"),), cors=True)
    preflight = cors.request("OPTIONS", "/future", headers=[
        (b"origin", b"https://client.invalid"),
        (b"access-control-request-method", b"POST"),
    ])
    assert preflight.status_code == 200
    assert cors.events["downstream"] == cors.events["receive"] == 0
    assert cors.request("POST", "/future", role="field").status_code == 403
    assert cors.events["downstream"] == 0


def test_s20_40_missing_router_context_cannot_authorize_a_secured_scope() -> None:
    probe = _RoutePolicyProbe((("GET", "/health"),))
    response = probe.request("GET", "/health", role="field", include_app=False)
    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "route_policy_not_registered"
    assert probe.events["downstream"] == probe.events["receive"] == 0
    assert probe.request("GET", "/health", role="field").status_code == 204


@pytest.mark.parametrize(("template", "path", "status"), (
    ("/reports/mine/{report_id}", "/reports/mine/not-a-uuid", 404),
    ("/reports/v2/{report_id}/status", "/reports/v2/not-a-uuid/status", 404),
    ("/privacy/account-deletions/{request_id}/status", "/privacy/account-deletions/short/status", 422),
))
def test_s20_40_selected_field_template_keeps_generation_required_for_malformed_ids(
    template: str, path: str, status: int,
) -> None:
    assert requires_actor_identity(path, "GET", route_template=template)
    assert requires_account_generation(path, "GET", route_template=template)
    probe = _RoutePolicyProbe((("GET", template),))
    response = probe.request("GET", path, role="field", headers=[
        (b"x-walksafe-actor-id", b"probe.actor"),
    ])
    assert response.status_code == status
    assert response.json()["detail"]["code"] != "route_policy_not_registered"
    assert probe.events["downstream"] == probe.events["receive"] == 0


@pytest.mark.parametrize("path", (
    "/admin/security/device-proof/challenges",
    "/admin/security/sessions",
    "/admin/security/recovery/start",
    "/admin/security/recovery/complete",
))
def test_s20_40_public_routes_still_require_the_existing_admin_client_contract(path: str) -> None:
    probe = _RoutePolicyProbe((("POST", path),), modern=True)
    missing = probe.request("POST", path)
    assert missing.status_code == 403
    assert missing.json()["detail"]["code"] == "admin_client_contract_invalid"
    assert probe.events["downstream"] == 0
    accepted = probe.request("POST", path, headers=probe.public_headers())
    assert accepted.status_code == 204
    assert probe.events == {"downstream": 1, "receive": 0, "authorize": 0, "audit": 0, "proof": 0}


def test_s20_40_selected_privacy_workflow_does_not_take_a_missing_url_id_from_body(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(field_security, "time", lambda: 1)
    verified = []
    original = field_security.verify_privacy_deletion_assertion
    def observe(assertion, **kwargs):
        verified.append((kwargs["request_id"], kwargs["path"]))
        return original(assertion, **kwargs)
    monkeypatch.setattr(field_security, "verify_privacy_deletion_assertion", observe)
    probe = _RoutePolicyProbe((
        ("POST", "/privacy/account-deletions/{request_id}/device-evidence"),
        ("GET", "/privacy/account-deletions/{request_id}/status"),
    ))
    binding_headers = [
        (b"x-walksafe-deletion-access-pre-digest", b"a" * 64),
        (b"x-walksafe-deletion-tombstone-id", b"tombstone_0001"),
    ]
    rejected = probe.request(
        "POST", "/privacy/account-deletions/short/device-evidence",
        role="field", identity=True, headers=binding_headers,
        body=b'{"request_id":"delete_request_0001"}',
    )
    assert rejected.status_code == 422
    assert rejected.json()["detail"]["code"] == "account_deletion_request_invalid"
    assert verified == []
    request_id = "delete_request_0001"
    path = f"/privacy/account-deletions/{request_id}/status"
    proof = field_security.create_privacy_deletion_assertion(
        actor_id="probe.actor", account_generation=1, request_id=request_id,
        tombstone_id="tombstone_0001", access_pre_digest="a" * 64,
        method="GET", path=path, body_sha256=hashlib.sha256(b"").hexdigest(),
        secret="s" * 32, issued_at=1,
    )
    accepted = probe.request(
        "GET", path, role="field", identity=True,
        headers=[*binding_headers, (b"x-walksafe-actor-assertion", proof.encode("ascii"))],
    )
    assert accepted.status_code == 204
    assert verified == [(request_id, path)]
    assert probe.events["downstream"] == 1


def test_s20_40_public_proof_classification_keeps_the_actual_signed_path_and_body() -> None:
    probe = _RoutePolicyProbe((("POST", "/admin/security/sessions"),), modern=True, device_proof=True)
    body = b'{"admin_id":"administrator","device_id":"device-test-1234"}'
    path = "/gateway/admin/security/sessions"
    response = probe.request(
        "POST", path, root_path="/gateway", body=body, query=b"mode=login",
        headers=[
            *probe.public_headers(),
            (b"x-walksafe-device-challenge-id", b"00000000-0000-4000-8000-000000000001"),
            (b"x-walksafe-device-signature", b"A" * 86),
            (b"x-walksafe-correlation-id", b"00000000-0000-4000-8000-000000000002"),
        ],
    )
    assert response.status_code == 204
    assert probe.events == {"downstream": 1, "receive": 1, "authorize": 0, "audit": 0, "proof": 1}
    proof_call = probe.proof_rows[0]
    assert proof_call["expected_purpose"] == "LOGIN"
    assert proof_call["expected_path"] == path
    assert proof_call["expected_method"] == "POST"
    assert proof_call["raw_body"] == body
    assert proof_call["raw_query_string"] == b"mode=login"
    assert probe.last_scope["path"] == path
    assert "path_params" not in probe.last_scope


class _RoutePolicyProbe:
    """Real route matching and middleware; observe only external boundaries."""

    def __init__(
        self, routes, *, secured=True, modern=False, audit_unavailable=False,
        device_proof=False, cors=False,
    ):
        self.router = FastAPI(openapi_url=None, docs_url=None, redoc_url=None)
        async def endpoint():
            return None
        for method, path in routes:
            self.router.add_api_route(path, endpoint, methods=[method])
        class SettingsOverride(SimpleNamespace):
            def __getattr__(self, name):
                return getattr(app_settings, name)

        self.settings = SettingsOverride(
            field_test_security_enabled=secured,
            field_test_token=FIELD_TOKEN, admin_token=ADMIN_TOKEN,
            admin_security_enabled=modern, admin_device_proof_enabled=device_proof,
            walksafe_environment="development", allow_insecure_local_dev=True,
            actor_rate_limit_store="memory", gateway_session_secret="s" * 32,
            raw_ingest_enabled=True,
        )
        self.events = {"downstream": 0, "receive": 0, "authorize": 0, "audit": 0, "proof": 0}
        self.audit_rows = []
        self.proof_rows = []
        self.last_scope = None
        async def downstream(scope, receive, send):
            self.events["downstream"] += 1
            await send({"type": "http.response.start", "status": 204, "headers": []})
            await send({"type": "http.response.body", "body": b""})
        def authorize(_settings, **kwargs):
            self.events["authorize"] += 1
            return SimpleNamespace(admin_id="probe.admin", device_id="device-test-1234", session_id="session-1")
        def audit(**kwargs):
            self.events["audit"] += 1
            self.audit_rows.append(kwargs)
            if audit_unavailable:
                raise AdminSecurityError("audit_unavailable", "Audit is unavailable.", status_code=503)
        def proof(_settings, **kwargs):
            self.events["proof"] += 1
            self.proof_rows.append(kwargs)
            return SimpleNamespace(verified=True)
        self.target = field_security.FieldTestSecurityMiddleware(
            downstream, self.settings, admin_session_authorizer=authorize,
            admin_security_denial_recorder=audit, admin_device_proof_verifier=proof,
        )
        if cors:
            self.target = CORSMiddleware(
                self.target, allow_origins=["https://client.invalid"],
                allow_methods=["GET", "POST"], allow_headers=["*"],
            )

    @staticmethod
    def public_headers():
        return [
            (field_security.ADMIN_APP_KIND_HEADER, field_security.ADMIN_APP_KIND.encode("ascii")),
            (field_security.ADMIN_ROLE_HEADER, field_security.ADMIN_ROLE.encode("ascii")),
            (field_security.ADMIN_AUDIENCE_HEADER, field_security.ADMIN_AUDIENCE.encode("ascii")),
            (field_security.ADMIN_DEVICE_ID_HEADER, b"device-test-1234"),
        ]

    def request(
        self, method, path, *, role=None, identity=False, headers=(),
        body=b"{}", include_app=True, root_path="", query=b"",
    ):
        request_headers = list(headers)
        if role == "field":
            request_headers.append((field_security.FIELD_TOKEN_HEADER, FIELD_TOKEN.encode("ascii")))
        elif role == "admin":
            request_headers.append((field_security.ADMIN_TOKEN_HEADER, ADMIN_TOKEN.encode("ascii")))
            request_headers.append((b"authorization", b"Bearer probe-access-token"))
        if identity:
            request_headers.extend([
                (field_security.ACTOR_ID_HEADER, b"probe.actor"),
                (field_security.ACCOUNT_GENERATION_HEADER, b"1"),
            ])
        scope = {
            "type": "http", "http_version": "1.1", "method": method, "path": path,
            "raw_path": path.encode("utf-8"), "root_path": root_path,
            "query_string": query, "headers": request_headers, "scheme": "http",
            "server": ("testserver", 80), "client": ("127.0.0.1", 1234),
        }
        if include_app:
            scope["app"] = self.router
        self.last_scope = scope
        messages = []
        async def receive():
            self.events["receive"] += 1
            return {"type": "http.request", "body": body, "more_body": False}
        async def send(message):
            messages.append(message)
        asyncio.run(self.target(scope, receive, send))
        start = next(item for item in messages if item["type"] == "http.response.start")
        content = b"".join(item.get("body", b"") for item in messages if item["type"] == "http.response.body")
        return httpx.Response(start["status"], headers=start.get("headers", []), content=content)


def test_privacy_early_errors_are_field_scoped_and_never_cacheable(
    secured_client: ASGITestClient,
) -> None:
    missing = secured_client.get("/privacy/malformed")
    admin = secured_client.get(
        "/privacy/malformed",
        headers={"x-walksafe-admin-token": ADMIN_TOKEN},
    )
    field = secured_client.get(
        "/privacy/malformed",
        headers={"x-walksafe-field-test-token": FIELD_TOKEN},
    )
    assert (missing.status_code, admin.status_code, field.status_code) == (403, 403, 403)
    for response in (missing, admin, field):
        assert response.json()["detail"]["code"] == "route_policy_not_registered"
        assert response.headers["cache-control"] == "no-store"
        assert response.headers["pragma"] == "no-cache"

    invalid_tombstone = secured_client.post(
        "/privacy/account-deletions",
        headers={
            "x-walksafe-field-test-token": FIELD_TOKEN,
            "x-walksafe-actor-id": "privacy.field",
            "x-walksafe-account-generation": "1",
            "x-walksafe-deletion-access-pre-digest": "a" * 64,
            "x-walksafe-deletion-tombstone-id": "non-null-tombstone",
        },
        json={"request_id": "delete_privacy_early_0001"},
    )
    assert invalid_tombstone.status_code == 422
    assert invalid_tombstone.json()["detail"]["code"] == "account_deletion_binding_invalid"
    assert invalid_tombstone.headers["cache-control"] == "no-store"

    invalid_contract = secured_client.post(
        "/privacy/consent-events",
        headers={
            "x-walksafe-field-test-token": FIELD_TOKEN,
            "x-walksafe-actor-id": "privacy.field",
            "x-walksafe-account-generation": "1",
        },
        json={},
    )
    assert invalid_contract.status_code == 422
    assert invalid_contract.json()["detail"]["code"] == "privacy_request_validation_failed"
    assert invalid_contract.headers["cache-control"] == "no-store"

    oversized = secured_client.post(
        "/privacy/consent-events",
        headers={
            "content-type": "application/json",
            "origin": app_settings.cors_origins[0],
        },
        content=b"x" * (32 * 1024 + 1),
    )
    assert oversized.status_code == 413
    assert oversized.json()["detail"]["code"] == "request_body_too_large"
    assert oversized.headers["cache-control"] == "no-store"
    assert oversized.headers["access-control-allow-origin"] == (
        app_settings.cors_origins[0]
    )

    preflight = secured_client.request(
        "OPTIONS",
        "/privacy/consent-events",
        headers={
            "origin": app_settings.cors_origins[0],
            "access-control-request-method": "POST",
        },
    )
    assert preflight.status_code == 200
    assert preflight.headers["cache-control"] == "no-store"
    assert preflight.headers["pragma"] == "no-cache"

    incomplete = _request_body_error_response(
        _RequestBodyIncomplete(),
        max_bytes=32 * 1024,
        privacy=True,
    )
    assert json.loads(incomplete.body)["detail"]["code"] == (
        "account_deletion_request_body_incomplete"
    )


def test_privacy_no_store_middleware_normalizes_unhandled_early_failure() -> None:
    async def failing_app(_scope, _receive, _send) -> None:
        raise RuntimeError("synthetic privacy failure")

    allowed_origin = "https://privacy-client.example.invalid"
    privacy_stack = PrivacyNoStoreMiddleware(
        CORSMiddleware(
            PrivacyExceptionMiddleware(failing_app),
            allow_origins=[allowed_origin],
            allow_credentials=True,
            allow_methods=["*"],
            allow_headers=["*"],
        )
    )
    response = ASGITestClient(privacy_stack).get(
        "/privacy/boom",
        headers={"origin": allowed_origin},
    )

    assert response.status_code == 503
    assert response.json() == {
        "detail": {
            "code": "privacy_lifecycle_store_unavailable",
            "message": "The privacy lifecycle store is temporarily unavailable.",
        }
    }
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"
    assert response.headers["retry-after"] == "5"
    assert response.headers["access-control-allow-origin"] == allowed_origin


def test_privacy_no_store_middleware_does_not_retry_a_failed_response_start() -> None:
    send_attempts = 0

    async def starting_app(_scope, _receive, send) -> None:
        await send(
            {
                "type": "http.response.start",
                "status": 200,
                "headers": [],
            }
        )

    async def receive() -> dict[str, object]:
        return {"type": "http.request", "body": b"", "more_body": False}

    async def broken_send(_message) -> None:
        nonlocal send_attempts
        send_attempts += 1
        raise RuntimeError("synthetic response transport failure")

    with pytest.raises(RuntimeError, match="synthetic response transport failure"):
        asyncio.run(
            PrivacyNoStoreMiddleware(PrivacyExceptionMiddleware(starting_app))(
                {"type": "http", "path": "/privacy/boom"},
                receive,
                broken_send,
            )
        )
    assert send_attempts == 1


def test_field_route_requires_valid_token_and_admin_token_also_grants_access(
    secured_client: ASGITestClient,
) -> None:
    missing = secured_client.get("/health")
    wrong = secured_client.get(
        "/health",
        headers={"x-walksafe-field-test-token": "wrong"},
    )
    field = secured_client.get(
        "/health",
        headers={"x-walksafe-field-test-token": FIELD_TOKEN},
    )
    admin = secured_client.get(
        "/health",
        headers={"x-walksafe-admin-token": ADMIN_TOKEN},
    )

    assert missing.status_code == 401
    assert wrong.status_code == 401
    assert field.status_code == 200
    assert admin.status_code == 403


def test_admin_health_route_rejects_field_role(secured_client: ASGITestClient) -> None:
    field = secured_client.get(
        "/detect/v2/health",
        headers={"x-walksafe-field-test-token": FIELD_TOKEN},
    )
    admin = secured_client.get(
        "/detect/v2/health",
        headers={"x-walksafe-admin-token": ADMIN_TOKEN},
    )

    assert field.status_code == 403
    assert admin.status_code == 200


def test_readiness_is_available_to_field_role_without_sensitive_details(
    secured_client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr("backend.app.api.health._database_readiness", lambda _url: {"ready": True})
    monkeypatch.setattr("backend.app.api.health._upload_readiness", lambda _path: {"ready": True})
    monkeypatch.setattr(
        "backend.app.api.health._report_storage_inventory_readiness",
        lambda _settings, _manager: {"ready": True},
    )
    monkeypatch.setattr(
        "backend.app.api.health._privacy_hmac_binding_readiness",
        lambda _settings: {"ready": True, "binding": "matched"},
    )
    monkeypatch.setattr(
        "backend.app.api.health._account_crypto_binding_readiness",
        lambda _settings: {"ready": True, "binding": "matched"},
    )
    async def navigation_ready(_settings, *, runtime_provider=None):
        return {"ready": True, "provider": "tmap_pedestrian", "evidence": "recent_success"}

    monkeypatch.setattr("backend.app.api.health._navigation_readiness", navigation_ready)
    monkeypatch.setattr(
        "backend.app.api.health.detect_v2_health",
        lambda _settings: {"status": "ready", "mode": "fake", "reason": None, "configured_runtime": None},
    )

    response = secured_client.get(
        "/ready",
        headers={"x-walksafe-field-test-token": FIELD_TOKEN},
    )

    assert response.status_code == 200
    assert response.json()["status"] == "ready"
    assert "database_url" not in response.text
    assert "upload_dir" not in response.text


def test_field_token_cannot_open_admin_routes(secured_client: ASGITestClient) -> None:
    field = secured_client.get(
        "/docs",
        headers={"x-walksafe-field-test-token": FIELD_TOKEN},
    )
    admin = secured_client.get(
        "/docs",
        headers={"x-walksafe-admin-token": ADMIN_TOKEN},
    )

    assert field.status_code == 403
    assert field.json()["detail"]["code"] == "field_test_forbidden"
    assert admin.status_code == 200


def test_secured_rate_limited_operation_requires_named_actor(
    secured_client: ASGITestClient,
) -> None:
    response = secured_client.post(
        "/detect/v2",
        headers={"x-walksafe-field-test-token": FIELD_TOKEN},
    )

    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "missing_actor_id"


def test_actor_assertion_is_bound_to_role_actor_and_short_lifetime() -> None:
    secret = "gateway-session-secret-for-tests-1234567890"
    assertion = create_actor_assertion("field.user", FieldTestAccess.FIELD, secret, issued_at=1_000)

    assert verify_actor_assertion(
        assertion,
        actor_id="field.user",
        access=FieldTestAccess.FIELD,
        secret=secret,
        now=1_025,
    ) is True
    assert verify_actor_assertion(
        assertion,
        actor_id="spoofed.user",
        access=FieldTestAccess.FIELD,
        secret=secret,
        now=1_025,
    ) is False
    assert verify_actor_assertion(
        assertion,
        actor_id="field.user",
        access=FieldTestAccess.ADMIN,
        secret=secret,
        now=1_025,
    ) is False
    assert verify_actor_assertion(
        assertion,
        actor_id="field.user",
        access=FieldTestAccess.FIELD,
        secret=secret,
        now=1_031,
    ) is False


def test_deployment_rejects_unbound_actor_but_accepts_gateway_assertion(
    secured_client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    actor_id = "field.user@example.com"
    secret = "gateway-session-secret-for-tests-1234567890"
    monkeypatch.setattr(app_settings, "walksafe_environment", "field")
    monkeypatch.setattr(app_settings, "gateway_session_secret", secret)
    _ACTOR_RATE_LIMITER.reset()

    unbound = secured_client.post(
        "/detect/v2",
        headers={
            "x-walksafe-field-test-token": FIELD_TOKEN,
            "x-walksafe-actor-id": actor_id,
        },
    )
    spoofed = secured_client.post(
        "/detect/v2",
        headers={
            "x-walksafe-field-test-token": FIELD_TOKEN,
            "x-walksafe-actor-id": "spoofed.user@example.com",
            "x-walksafe-actor-assertion": create_actor_assertion(
                actor_id,
                FieldTestAccess.FIELD,
                secret,
            ),
        },
    )
    # w24 S20-41 / owner decision 7: an unbound v1 write is refused by default.
    legacy_write = secured_client.post(
        "/detect/v2",
        headers={
            "x-walksafe-field-test-token": FIELD_TOKEN,
            "x-walksafe-actor-id": actor_id,
            "x-walksafe-actor-assertion": create_actor_assertion(
                actor_id,
                FieldTestAccess.FIELD,
                secret,
            ),
        },
    )
    bound = secured_client.post(
        "/detect/v2",
        headers={
            "x-walksafe-field-test-token": FIELD_TOKEN,
            "x-walksafe-actor-id": actor_id,
            "x-walksafe-account-generation": "1",
            "x-walksafe-actor-assertion": create_actor_assertion_v4(
                actor_id,
                FieldTestAccess.FIELD,
                secret,
                account_generation=1,
                auth_epoch=None,
                method="POST",
                target="/detect/v2",
                body_sha256=hashlib.sha256(b"").hexdigest(),
            ),
        },
    )

    assert unbound.status_code == 401
    assert unbound.json()["detail"]["code"] == "actor_assertion_required"
    assert spoofed.status_code == 401
    assert spoofed.json()["detail"]["code"] == "actor_assertion_invalid"
    assert legacy_write.status_code == 401
    assert legacy_write.json()["detail"]["code"] == "actor_assertion_invalid"
    assert bound.status_code == 422
    assert bound.json()["detail"][0]["type"] == "missing"


@pytest.mark.parametrize("path", ["/reports", "/reports/summary", "/uploads/missing.jpg"])
def test_sensitive_admin_reads_require_a_gateway_bound_actor(
    secured_client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
    path: str,
) -> None:
    actor_id = "admin.reader@example.com"
    secret = "gateway-session-secret-for-admin-read-tests-123456"
    monkeypatch.setattr(app_settings, "walksafe_environment", "field")
    monkeypatch.setattr(app_settings, "gateway_session_secret", secret)

    missing_actor = secured_client.get(
        path,
        headers={"x-walksafe-admin-token": ADMIN_TOKEN},
    )
    unbound_actor = secured_client.get(
        path,
        headers={
            "x-walksafe-admin-token": ADMIN_TOKEN,
            "x-walksafe-actor-id": actor_id,
        },
    )

    assert missing_actor.status_code == 422
    assert missing_actor.json()["detail"]["code"] == "missing_actor_id"
    assert unbound_actor.status_code == 401
    assert unbound_actor.json()["detail"]["code"] == "actor_assertion_required"

    if path.startswith("/uploads/"):
        bound = secured_client.get(
            path,
            headers={
                "x-walksafe-admin-token": ADMIN_TOKEN,
                "x-walksafe-actor-id": actor_id,
                "x-walksafe-actor-assertion": create_actor_assertion(
                    actor_id,
                    FieldTestAccess.ADMIN,
                    secret,
                    ),
                    "x-walksafe-read-purpose": "admin_report_image",
                    "x-walksafe-original-access-grant": "invalid",
                },
        )
        assert bound.status_code == 503
        assert bound.json()["detail"]["code"] == "admin_security_required"


@pytest.mark.parametrize("path", ["/reports", "/reports/summary"])
def test_sensitive_report_read_requires_declared_purpose(
    secured_client: ASGITestClient,
    path: str,
) -> None:
    response = secured_client.get(
        path,
        headers={
            "x-walksafe-admin-token": ADMIN_TOKEN,
            "x-walksafe-actor-id": "admin.reader@example.com",
        },
    )

    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "missing_read_purpose"


def test_duplicate_check_requires_declared_read_purpose(
    secured_client: ASGITestClient,
) -> None:
    response = secured_client.get(
        "/reports/duplicate-check",
        params={
            "class_name": "pothole",
            "captured_at": "2026-07-16T00:00:00Z",
            "lat": 37.5,
            "lng": 127.0,
        },
        headers={
            "x-walksafe-admin-token": ADMIN_TOKEN,
            "x-walksafe-actor-id": "admin.reader@example.com",
        },
    )

    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "missing_read_purpose"


def test_actor_assertion_bypass_is_rejected_outside_explicit_development(
    secured_client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(app_settings, "walksafe_environment", "test")
    monkeypatch.setattr(app_settings, "allow_insecure_local_dev", True)

    response = secured_client.post(
        "/detect/v2",
        headers={
            "x-walksafe-field-test-token": FIELD_TOKEN,
            "x-walksafe-actor-id": "field.user@example.com",
        },
    )

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "actor_assertion_required"


@pytest.mark.parametrize(
    ("group", "method", "path", "token_header", "token"),
    [
        ("report", "post", "/reports/v2", "x-walksafe-field-test-token", FIELD_TOKEN),
        ("navigation", "get", "/navigation/destinations/search?query=test", "x-walksafe-field-test-token", FIELD_TOKEN),
        ("detect", "post", "/detect/v2", "x-walksafe-field-test-token", FIELD_TOKEN),
        ("export", "get", "/reports/export", "x-walksafe-admin-token", ADMIN_TOKEN),
        ("admin_read", "get", "/uploads/missing.jpg", "x-walksafe-admin-token", ADMIN_TOKEN),
    ],
)
def test_actor_rate_limits_fail_with_429_and_retry_after(
    secured_client: ASGITestClient,
    group: str,
    method: str,
    path: str,
    token_header: str,
    token: str,
) -> None:
    actor_id = f"rate-{group}@example.com"
    _ACTOR_RATE_LIMITER.reset()
    for _ in range(ACTOR_RATE_LIMITS[group]):
        assert _ACTOR_RATE_LIMITER.check(actor_id, group) is None

    response = getattr(secured_client, method)(
        path,
        headers={
            token_header: token,
            "x-walksafe-actor-id": actor_id,
            **(
                {"x-walksafe-account-generation": "1"}
                if group == "report"
                else {}
            ),
        },
    )

    assert response.status_code == 429
    assert response.json()["detail"]["code"] == "actor_rate_limit_exceeded"
    assert int(response.headers["retry-after"]) >= 1
    _ACTOR_RATE_LIMITER.reset()


def test_security_disabled_preserves_local_development(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(app_settings, "field_test_security_enabled", False)
    assert ASGITestClient(app).get("/detect/v2/health").status_code == 200


def test_health_exposes_only_the_bound_source_commit(monkeypatch: pytest.MonkeyPatch) -> None:
    source_commit = "a" * 40
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", source_commit)

    response = ASGITestClient(app).get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "source_commit": source_commit}


def test_security_defaults_to_enabled_and_deployments_cannot_disable_it(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", raising=False)
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", FIELD_TOKEN)
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv("WALKSAFE_GATEWAY_SESSION_SECRET", "gateway-session-secret-for-tests-1234567890")
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "development")
    assert Settings().field_test_security_enabled is True

    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "false")
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    with pytest.raises(ValueError, match="explicit insecure"):
        Settings()

    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "development")
    monkeypatch.setenv("WALKSAFE_ALLOW_INSECURE_LOCAL_DEV", "false")
    with pytest.raises(ValueError, match="explicit insecure"):
        Settings()

    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "prodution")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    with pytest.raises(ValueError, match="unsupported"):
        Settings()


def test_report_image_key_provider_and_grant_ttl_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("WALKSAFE_REPORT_IMAGE_KEY_PROVIDER", raising=False)
    with pytest.raises(ValueError, match="REPORT_IMAGE_KEY_PROVIDER"):
        Settings()

    monkeypatch.setenv("WALKSAFE_REPORT_IMAGE_KEY_PROVIDER", "secret_file")
    monkeypatch.setenv("WALKSAFE_REPORT_IMAGE_KMS_AGENT_SOCKET", "/run/forbidden.sock")
    with pytest.raises(ValueError, match="require only"):
        Settings()

    monkeypatch.setenv("WALKSAFE_REPORT_IMAGE_KMS_AGENT_SOCKET", "")
    monkeypatch.setenv("WALKSAFE_REPORT_ORIGINAL_GRANT_TTL_SECONDS", "301")
    with pytest.raises(ValueError, match="at most 300"):
        Settings()


def test_production_requires_database_encryption_transport_and_distinct_key_boundaries(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", FIELD_TOKEN)
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv(
        "DATABASE_URL",
        "postgresql+psycopg://walksafe:test@db.example.invalid/walksafe"
        "?sslmode=verify-full&gssencmode=disable",
    )
    monkeypatch.setenv("WALKSAFE_DATABASE_AT_REST_ENCRYPTION_CONFIRMED", "false")
    with pytest.raises(ValueError, match="at-rest encryption"):
        Settings()

    monkeypatch.setenv("WALKSAFE_DATABASE_AT_REST_ENCRYPTION_CONFIRMED", "true")
    monkeypatch.setenv("WALKSAFE_DATABASE_TRANSPORT_SECURITY_CONFIRMED", "false")
    with pytest.raises(ValueError, match="TLS or protected local transport"):
        Settings()

    monkeypatch.setenv("WALKSAFE_DATABASE_TRANSPORT_SECURITY_CONFIRMED", "true")
    monkeypatch.setenv("WALKSAFE_DATABASE_ENCRYPTION_KEY_BOUNDARY", "same-boundary")
    monkeypatch.setenv("WALKSAFE_REPORT_IMAGE_KEY_BOUNDARY", "same-boundary")
    with pytest.raises(ValueError, match="distinct non-secret key boundaries"):
        Settings()

def test_alembic_database_configuration_does_not_require_runtime_tokens(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_url = "postgresql+psycopg://walksafe_test:test@127.0.0.1:5432/walksafe_test"
    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.delenv("WALKSAFE_FIELD_TEST_TOKEN", raising=False)
    monkeypatch.delenv("WALKSAFE_ADMIN_TOKEN", raising=False)

    assert migration_database_url() == database_url


def test_alembic_rejects_unsupported_environment_before_remote_transport(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "prodution")
    monkeypatch.setenv(
        "DATABASE_URL",
        (
            "postgresql+psycopg://walksafe:test@db.example.invalid/walksafe"
            "?sslmode=disable&gssencmode=disable"
        ),
    )

    with pytest.raises(ValueError, match="unsupported"):
        migration_database_url()


def test_deployment_alembic_requires_a_distinct_migration_role(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime_url = (
        "postgresql+psycopg://walksafe_backend_app:runtime@db.example.invalid/walksafe"
        "?sslmode=verify-full&gssencmode=disable"
    )
    migration_url = (
        "postgresql+psycopg://walksafe_migrator:migrate@db.example.invalid/walksafe"
        "?sslmode=verify-full&gssencmode=disable"
    )
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("DATABASE_URL", runtime_url)
    monkeypatch.setenv("WALKSAFE_RUNTIME_DATABASE_ROLE", "walksafe_backend_app")
    monkeypatch.delenv("WALKSAFE_MIGRATION_DATABASE_URL", raising=False)
    with pytest.raises(ValueError, match="WALKSAFE_MIGRATION_DATABASE_URL"):
        migration_database_url()

    monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", runtime_url)
    with pytest.raises(ValueError, match="must be distinct"):
        migration_database_url()

    monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", migration_url)
    assert migration_database_url() == migration_url

    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("WALKSAFE_RUNTIME_DATABASE_ROLE", "walksafe_backend_app")
    assert migration_database_url() == migration_url

    monkeypatch.delenv("WALKSAFE_RUNTIME_DATABASE_ROLE", raising=False)
    with pytest.raises(ValueError, match="WALKSAFE_RUNTIME_DATABASE_ROLE"):
        migration_database_url()


def test_deployment_alembic_rejects_ambiguous_runtime_role(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime_url = (
        "postgresql+psycopg://walksafe_backend_app:runtime@db.example.invalid/walksafe"
        "?sslmode=verify-full&gssencmode=disable"
    )
    migration_url = (
        "postgresql+psycopg://walksafe_migrator:migrate@db.example.invalid/walksafe"
        "?sslmode=verify-full&gssencmode=disable"
    )
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("DATABASE_URL", runtime_url)
    monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", migration_url)
    monkeypatch.setenv("WALKSAFE_RUNTIME_DATABASE_ROLE", "another_runtime")

    with pytest.raises(ValueError, match="must match the DATABASE_URL role"):
        migration_database_url()

    monkeypatch.setenv("WALKSAFE_RUNTIME_DATABASE_ROLE", "role with spaces")
    with pytest.raises(ValueError, match="canonical database role"):
        migration_database_url()


def test_deployment_settings_reject_migration_database_credential(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", FIELD_TOKEN)
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv(
        "WALKSAFE_MIGRATION_DATABASE_URL",
        (
            "postgresql+psycopg://walksafe_migrator:migrate@db.example.invalid/walksafe"
            "?sslmode=verify-full&gssencmode=disable"
        ),
    )

    with pytest.raises(ValueError, match="must not expose"):
        Settings()


def test_deployment_config_does_not_load_repository_dotenv(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    loaded: list[Path] = []
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setattr(
        config_module,
        "load_dotenv",
        lambda path: loaded.append(Path(path)),
    )

    with pytest.raises(ValueError):
        Settings()
    assert loaded == []

    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("WALKSAFE_RUNTIME_DATABASE_ROLE", "walksafe_backend_app")
    monkeypatch.setenv(
        "WALKSAFE_MIGRATION_DATABASE_URL",
        (
            "postgresql+psycopg://walksafe_migrator:migrate@db.example.invalid/walksafe"
            "?sslmode=verify-full&gssencmode=disable"
        ),
    )
    assert "walksafe_migrator" in migration_database_url()
    assert loaded == []


def test_repository_dotenv_cannot_select_a_deployment_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("WALKSAFE_ENVIRONMENT", raising=False)

    def inject_production(_path: Path) -> None:
        monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")

    monkeypatch.setattr(config_module, "load_dotenv", inject_production)

    with pytest.raises(ValueError, match="must not be selected"):
        Settings()

    monkeypatch.delenv("WALKSAFE_ENVIRONMENT", raising=False)
    with pytest.raises(ValueError, match="must not be selected"):
        migration_database_url()


def test_settings_require_distinct_long_tokens_when_security_enabled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", "short")
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    with pytest.raises(ValueError, match="WALKSAFE_FIELD_TEST_TOKEN"):
        Settings()

    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", FIELD_TOKEN)
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", FIELD_TOKEN)
    with pytest.raises(ValueError, match="must differ"):
        Settings()

    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    settings = Settings()
    assert settings.field_test_security_enabled is True
    assert settings.field_test_token == FIELD_TOKEN
    assert settings.admin_token == ADMIN_TOKEN


def test_detect_v2_image_size_defaults_to_768_and_accepts_explicit_768(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Empty process values take precedence over a developer's backend/.env and
    # exercise the parser defaults without rewriting local field-test config.
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "")
    monkeypatch.setenv("DETECT_V2_IMAGE_SIZE", "")
    assert Settings().detect_v2_image_size == 768

    monkeypatch.setenv("DETECT_V2_IMAGE_SIZE", "768")
    assert Settings().detect_v2_image_size == 768


def test_settings_reject_provider_urls_outside_official_https_hosts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("TMAP_PEDESTRIAN_ROUTE_URL", "http://127.0.0.1/steal-key")
    with pytest.raises(ValueError, match="official HTTPS"):
        Settings()


def test_deployment_requires_postgres_actor_rate_limit_store(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "field")
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "a" * 40)
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", FIELD_TOKEN)
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv("WALKSAFE_GATEWAY_SESSION_SECRET", "gateway-session-secret-for-tests-1234567890")
    monkeypatch.setenv("WALKSAFE_ACTOR_RATE_LIMIT_STORE", "memory")

    with pytest.raises(ValueError, match="RATE_LIMIT_STORE=postgresql"):
        Settings()


def test_postgres_actor_rate_limit_store_does_not_force_one_process(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "development")
    monkeypatch.setenv("WALKSAFE_ACTOR_RATE_LIMIT_STORE", "postgresql")
    monkeypatch.setenv("WALKSAFE_BACKEND_WORKERS", "3")
    monkeypatch.setenv("WALKSAFE_BACKEND_REPLICAS", "2")

    settings = Settings()

    assert settings.actor_rate_limit_store == "postgresql"
    assert settings.backend_workers == 3
    assert settings.backend_replicas == 2


def test_rate_limit_store_failure_fails_closed(
    secured_client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(app_settings, "actor_rate_limit_store", "postgresql")

    def unavailable(*_args, **_kwargs):
        raise ActorRateLimitStoreUnavailable

    monkeypatch.setattr(_POSTGRES_ACTOR_RATE_LIMITER, "_check_in_db", unavailable)

    response = secured_client.get(
        "/uploads/missing.jpg",
        headers={
            "x-walksafe-admin-token": ADMIN_TOKEN,
            "x-walksafe-actor-id": "admin.reader@example.com",
        },
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "actor_rate_limit_store_unavailable"


def test_slow_shared_rate_limit_store_does_not_block_liveness(
    secured_client: ASGITestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del secured_client
    started = threading.Event()
    release = threading.Event()
    monkeypatch.setattr(app_settings, "actor_rate_limit_store", "postgresql")

    def blocking_check(*_args, **_kwargs):
        started.set()
        assert release.wait(timeout=2), "rate-limit store was not released"
        return None

    monkeypatch.setattr(_POSTGRES_ACTOR_RATE_LIMITER, "_check_in_db", blocking_check)

    async def exercise() -> tuple[httpx.Response, httpx.Response, float]:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            limited_task = asyncio.create_task(
                client.post(
                    "/detect/v2",
                    headers={
                        "x-walksafe-field-test-token": FIELD_TOKEN,
                        "x-walksafe-actor-id": "field.user@example.com",
                    },
                )
            )
            assert await asyncio.to_thread(started.wait, 1), "rate-limit worker did not start"
            started_at = time.monotonic()
            health = await client.get(
                "/health",
                headers={"x-walksafe-field-test-token": FIELD_TOKEN},
            )
            health_elapsed = time.monotonic() - started_at
            release.set()
            return await limited_task, health, health_elapsed

    try:
        limited, health, health_elapsed = asyncio.run(exercise())
    finally:
        release.set()

    assert limited.status_code == 422
    assert health.status_code == 200
    assert health_elapsed < 0.1



def test_rate_deadline_and_saturation_keep_liveness_and_real_429_recovery(
    secured_client: ASGITestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    from contextlib import contextmanager
    from types import SimpleNamespace
    from backend.app.services.actor_rate_limit import ActorRateLimitExecution, PostgresActorRateLimiter

    del secured_client
    started, release, closed = threading.Event(), threading.Event(), threading.Event()
    entries = []
    execution = ActorRateLimitExecution(1, 50, 50)

    @contextmanager
    def begin():
        entries.append(True)
        if not release.is_set():
            started.set()
            if not release.wait(2):
                raise RuntimeError("HTTP rate worker was not released")
        try:
            yield SimpleNamespace(execute=lambda *_args, **_kwargs: SimpleNamespace(
                mappings=lambda: SimpleNamespace(one=lambda: {
                    "event_count": 1000, "retry_after": 7,
                }),
            ))
        finally:
            closed.set()

    monkeypatch.setattr(app_settings, "actor_rate_limit_store", "postgresql")
    monkeypatch.setattr(
        "backend.app.field_test_security._POSTGRES_ACTOR_RATE_LIMITER",
        PostgresActorRateLimiter(ACTOR_RATE_LIMITS, session_factory=SimpleNamespace(begin=begin),
                                 execution=execution),
    )

    async def exercise():
        transport = httpx.ASGITransport(app=app)
        headers = {
            "x-walksafe-field-test-token": FIELD_TOKEN,
            "x-walksafe-actor-id": "field.user@example.com",
        }
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            pending = asyncio.create_task(client.post("/detect/v2", headers=headers))
            assert await asyncio.to_thread(started.wait, 1)
            health = await client.get("/health", headers={"x-walksafe-field-test-token": FIELD_TOKEN})
            assert health.status_code == 200
            expired = await asyncio.wait_for(pending, 1)
            assert expired.status_code == 503
            assert expired.json()["detail"]["code"] == "actor_rate_limit_store_unavailable"
            assert not closed.is_set()
            saturated = await client.post("/detect/v2", headers=headers)
            assert saturated.status_code == 503
            assert len(entries) == 1
            release.set()
            assert await asyncio.to_thread(closed.wait, 1)
            deadline = time.monotonic() + 1
            while True:
                recovered = await client.post("/detect/v2", headers=headers)
                if recovered.status_code != 503 or time.monotonic() >= deadline:
                    break
                await asyncio.sleep(0.001)
            assert recovered.status_code == 429
            assert recovered.headers["retry-after"] == "7"

    try:
        asyncio.run(exercise())
    finally:
        release.set()
        execution.shutdown()


@pytest.mark.parametrize("sslmode", [None, "require", "verify-ca"])
def test_production_remote_database_requires_hostname_verified_tls(
    monkeypatch: pytest.MonkeyPatch,
    sslmode: str | None,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "a" * 40)
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", FIELD_TOKEN)
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv("WALKSAFE_GATEWAY_SESSION_SECRET", "gateway-session-secret-for-tests-1234567890")
    monkeypatch.setenv("TMAP_APP_KEY", "test-tmap-key")
    suffix = "" if sslmode is None else f"?sslmode={sslmode}"
    monkeypatch.setenv(
        "DATABASE_URL",
        f"postgresql+psycopg://walksafe:test@db.example.invalid/walksafe{suffix}",
    )

    with pytest.raises(ValueError, match="sslmode=verify-full"):
        Settings()


def test_production_database_allows_verified_remote_or_local_transport(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "a" * 40)
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", FIELD_TOKEN)
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv("WALKSAFE_GATEWAY_SESSION_SECRET", "gateway-session-secret-for-tests-1234567890")
    monkeypatch.setenv("TMAP_APP_KEY", "test-tmap-key")

    monkeypatch.setenv(
        "DATABASE_URL",
        (
            "postgresql+psycopg://walksafe:test@db.example.invalid/walksafe"
            "?sslmode=verify-full&gssencmode=disable"
        ),
    )
    with pytest.raises(ValueError, match="real detector"):
        Settings()

    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://walksafe:test@127.0.0.1/walksafe")
    with pytest.raises(ValueError, match="real detector"):
        Settings()

    monkeypatch.setenv(
        "DATABASE_URL",
        "postgresql+psycopg://walksafe:test@/walksafe?host=%2Frun%2Fpostgresql",
    )
    with pytest.raises(ValueError, match="real detector"):
        Settings()


@pytest.mark.parametrize(
    "database_url",
    [
        (
            "postgresql+psycopg://walksafe:test@localhost/walksafe"
            "?hostaddr=203.0.113.10&sslmode=disable"
        ),
        (
            "postgresql+psycopg://walksafe:test@/walksafe"
            "?host=%2Frun%2Fpostgresql%2Cdb.example.invalid&sslmode=disable"
        ),
        (
            "postgresql+psycopg://walksafe:test@/walksafe"
            "?service=walksafe-production&sslmode=disable"
        ),
        (
            "postgresql+psycopg://walksafe:test@db.example.invalid/walksafe"
            "?sslmode=verify-full&gssencmode=require"
        ),
        (
            "postgresql+psycopg://walksafe:test@db.example.invalid%2Clocalhost/walksafe"
            "?sslmode=verify-full&gssencmode=disable"
        ),
        (
            "postgresql+psycopg://walksafe:test@%2Frun%2Fpostgresql/walksafe"
            "?sslmode=verify-full&gssencmode=disable"
        ),
    ],
)
def test_production_database_rejects_hidden_remote_transport_parameters(
    monkeypatch: pytest.MonkeyPatch,
    database_url: str,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "a" * 40)
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", FIELD_TOKEN)
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv("WALKSAFE_GATEWAY_SESSION_SECRET", "gateway-session-secret-for-tests-1234567890")
    monkeypatch.setenv("TMAP_APP_KEY", "test-tmap-key")
    monkeypatch.setenv("DATABASE_URL", database_url)

    with pytest.raises(ValueError, match="database transport|authority host|sslmode=verify-full|gssencmode=disable"):
        Settings()


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("PGHOST", "db.example.invalid"),
        ("PGHOSTADDR", "203.0.113.10"),
        ("PGSERVICE", "walksafe-production"),
        ("PGSERVICEFILE", "/operator/private/pg_service.conf"),
        ("PGGSSENCMODE", "require"),
    ],
)
def test_production_database_rejects_ambient_libpq_transport(
    monkeypatch: pytest.MonkeyPatch,
    name: str,
    value: str,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "a" * 40)
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", FIELD_TOKEN)
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv("WALKSAFE_GATEWAY_SESSION_SECRET", "gateway-session-secret-for-tests-1234567890")
    monkeypatch.setenv("TMAP_APP_KEY", "test-tmap-key")
    monkeypatch.setenv(
        "DATABASE_URL",
        "postgresql+psycopg://walksafe:test@127.0.0.1/walksafe",
    )
    monkeypatch.setenv(name, value)

    with pytest.raises(ValueError, match="ambient libpq"):
        Settings()


@pytest.mark.parametrize(
    ("name", "value", "message"),
    [
        ("DATABASE_URL", "postgresql://walksafe@localhost/walksafe", r"postgresql\+psycopg"),
        ("DATABASE_CONNECT_TIMEOUT_SECONDS", "31", "at most 30"),
        ("DATABASE_STATEMENT_TIMEOUT_MS", "120001", "at most 120000"),
        ("MAX_UPLOAD_BYTES", "33554433", "at most 33554432"),
        ("MAX_REPORT_METADATA_BYTES", "1048577", "at most 1048576"),
        ("ALLOWED_IMAGE_CONTENT_TYPES", "application/octet-stream", "supported image MIME"),
        ("TMAP_POI_PROVIDER", "kakao", "TMAP_POI_PROVIDER"),
        ("TMAP_TIMEOUT_SECONDS", "4.001", "at most 4"),
        ("TMAP_PEDESTRIAN_SPEED_KMH", "21", "at most 20"),
    ],
)
def test_settings_reject_unbounded_or_non_tmap_navigation_configuration(
    monkeypatch: pytest.MonkeyPatch,
    name: str,
    value: str,
    message: str,
) -> None:
    monkeypatch.setenv(name, value)

    with pytest.raises(ValueError, match=message):
        Settings()


@pytest.mark.parametrize("value", ["0.1", "4.0"])
def test_tmap_timeout_stays_within_navigation_upstream_budget(
    monkeypatch: pytest.MonkeyPatch,
    value: str,
) -> None:
    monkeypatch.setenv("TMAP_TIMEOUT_SECONDS", value)

    assert Settings().tmap_timeout_seconds == float(value)


@pytest.mark.parametrize(
    "name",
    ["MODEL_CONFIDENCE_THRESHOLD", "TMAP_TIMEOUT_SECONDS", "INFERENCE_TIMEOUT_SECONDS"],
)
@pytest.mark.parametrize("value", ["nan", "inf", "-inf"])
def test_settings_reject_nonfinite_float_configuration(
    monkeypatch: pytest.MonkeyPatch,
    name: str,
    value: str,
) -> None:
    monkeypatch.setenv(name, value)

    with pytest.raises(ValueError, match="finite"):
        Settings()


def test_deployment_requires_tmap_app_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "field")
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "a" * 40)
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", FIELD_TOKEN)
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv("WALKSAFE_GATEWAY_SESSION_SECRET", "gateway-session-secret-for-tests-1234567890")
    monkeypatch.setenv("TMAP_APP_KEY", "")

    with pytest.raises(ValueError, match="TMAP_APP_KEY"):
        Settings()


def test_config_lock_authority_rejects_user_owned_higher_ancestor(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service_directory = tmp_path / "service"
    trusted_immediate_ancestor = service_directory / "trusted"
    lock_parent = trusted_immediate_ancestor / "maintenance"
    lock_parent.mkdir(parents=True, mode=0o700)
    service_directory.chmod(0o700)
    trusted_immediate_ancestor.chmod(0o755)
    higher_ancestor_identity = (service_directory.stat().st_dev, service_directory.stat().st_ino)
    authority_paths = [Path("/")]
    for component in trusted_immediate_ancestor.relative_to("/").parts:
        authority_paths.append(authority_paths[-1] / component)
    simulated_safe_paths = set(authority_paths) - {service_directory}
    simulated_safe_identities = {
        (path.stat().st_dev, path.stat().st_ino) for path in simulated_safe_paths
    }
    visited_identities: list[tuple[int, int]] = []
    real_fstat = os.fstat
    real_access = os.access

    def only_higher_ancestor_remains_user_owned(descriptor: int) -> os.stat_result:
        metadata = real_fstat(descriptor)
        identity = (metadata.st_dev, metadata.st_ino)
        visited_identities.append(identity)
        if identity not in simulated_safe_identities:
            return metadata
        fields = list(metadata)
        fields[0] = (fields[0] & ~0o7777) | 0o755
        fields[4] = 0
        return os.stat_result(fields)

    def simulated_safe_ancestors_appear_non_writable(
        path: str,
        mode: int,
        *,
        dir_fd: int | None = None,
        effective_ids: bool = False,
        follow_symlinks: bool = True,
    ) -> bool:
        if dir_fd is not None:
            metadata = real_fstat(dir_fd)
            if (metadata.st_dev, metadata.st_ino) in simulated_safe_identities:
                return False
        if dir_fd is None and Path(path) in simulated_safe_paths:
            return False
        return real_access(
            path,
            mode,
            dir_fd=dir_fd,
            effective_ids=effective_ids,
            follow_symlinks=follow_symlinks,
        )

    monkeypatch.setattr(config_module.os, "fstat", only_higher_ancestor_remains_user_owned)
    monkeypatch.setattr(config_module.os, "access", simulated_safe_ancestors_appear_non_writable)

    assert config_module._trusted_maintenance_lock_parent(lock_parent) is False
    assert higher_ancestor_identity in visited_identities


def test_deployment_environment_requires_real_detector_artifacts(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "field")
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "a" * 40)
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", FIELD_TOKEN)
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv("WALKSAFE_GATEWAY_SESSION_SECRET", "gateway-session-secret-for-tests-1234567890")
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_ADMIN_ID", "walksafe.admin")
    monkeypatch.setenv("WALKSAFE_ADMIN_TOTP_SECRET", "A" * 32)
    monkeypatch.setenv("TMAP_APP_KEY", "test-tmap-key")
    monkeypatch.setenv("DETECT_V2_MODE", "fake")

    with pytest.raises(ValueError, match="real detector"):
        Settings()

    model = tmp_path / "unified.pt"
    runtime_config = tmp_path / "runtime.json"
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o750)
    upload_dir.chmod(0o2750)
    model.write_bytes(b"model")
    runtime_payload = json.loads(json.dumps(DEFAULT_RUNTIME_CONFIG))
    runtime_payload["models"]["unified_walksafe"]["artifact_sha256"] = hashlib.sha256(b"model").hexdigest()
    runtime_config.write_text(json.dumps(runtime_payload), encoding="utf-8")
    monkeypatch.setenv("DETECT_V2_MODE", "real")
    monkeypatch.setenv("DETECT_V2_UNIFIED_MODEL_PATH", str(model))
    monkeypatch.setenv("DETECT_V2_RUNTIME_CONFIG_PATH", str(runtime_config))
    monkeypatch.setenv("UPLOAD_DIR", str(upload_dir.resolve()))
    monkeypatch.setenv(
        "WALKSAFE_MAINTENANCE_LOCK_GROUP",
        config_module.MAINTENANCE_LOCK_GROUP,
    )
    monkeypatch.setenv(
        "WALKSAFE_UPLOAD_BACKUP_READER_GROUP",
        config_module.UPLOAD_BACKUP_READER_GROUP,
    )
    maintenance_gid = next(
        (gid for gid in os.getgroups() if gid not in {0, os.getegid()}),
        os.getegid() + 1,
    )
    monkeypatch.setattr(
        config_module.grp,
        "getgrnam",
        lambda name: type(
            "Group",
            (),
            {
                "gr_gid": (
                    maintenance_gid
                    if name == config_module.MAINTENANCE_LOCK_GROUP
                    else os.getegid()
                )
            },
        )(),
    )
    monkeypatch.setattr(
        config_module.os,
        "getgroups",
        lambda: [maintenance_gid],
    )
    monkeypatch.setattr(
        Settings,
        "_validate_deployment_maintenance_lock",
        lambda _settings: None,
    )

    settings = Settings()
    assert settings.detect_v2_unified_model_path == model

    monkeypatch.setenv("TMAP_POI_PROVIDER", "mock")
    with pytest.raises(ValueError, match="must be live"):
        Settings()
    monkeypatch.setenv("TMAP_POI_PROVIDER", "live")

    monkeypatch.setenv("UPLOAD_DIR", "relative-uploads")
    with pytest.raises(ValueError, match="UPLOAD_DIR must be absolute"):
        Settings()
    monkeypatch.setenv("UPLOAD_DIR", str(upload_dir.resolve()))

    upload_dir.chmod(0o755)
    with pytest.raises(ValueError, match="dedicated backup-reader group"):
        Settings()
    upload_dir.chmod(0o2750)

    real_listxattr = config_module.os.listxattr
    monkeypatch.setattr(
        config_module.os,
        "listxattr",
        lambda _descriptor: [b"system.posix_acl_default"],
    )
    with pytest.raises(ValueError, match="ACL-free"):
        Settings()
    monkeypatch.setattr(config_module.os, "listxattr", real_listxattr)

    upload_link = tmp_path / "uploads-link"
    upload_link.symlink_to(upload_dir, target_is_directory=True)
    monkeypatch.setenv("UPLOAD_DIR", str(upload_link))
    with pytest.raises(ValueError, match="real directory"):
        Settings()
    monkeypatch.setenv("UPLOAD_DIR", str(upload_dir))

    monkeypatch.setenv("ANDROID_DEBUG_LOG_ENABLED", "true")
    with pytest.raises(ValueError, match="must be false"):
        Settings()
    monkeypatch.setenv("ANDROID_DEBUG_LOG_ENABLED", "false")

    symlink_model = tmp_path / "unified-link.pt"
    symlink_model.symlink_to(model)
    monkeypatch.setenv("DETECT_V2_UNIFIED_MODEL_PATH", str(symlink_model))
    with pytest.raises(ValueError, match="checkpoint"):
        Settings()

    monkeypatch.setenv("DETECT_V2_UNIFIED_MODEL_PATH", str(model))
    model.write_bytes(b"different-model")
    with pytest.raises(ValueError, match="RUNTIME_CONFIG_PATH is invalid"):
        Settings()

    model.write_bytes(b"model")
    monkeypatch.setenv("DETECT_V2_RUNTIME_CONFIG_PATH", str(tmp_path / "missing.json"))
    with pytest.raises(ValueError, match="RUNTIME_CONFIG_PATH"):
        Settings()


def test_shared_maintenance_lock_metadata_contract_is_exact(tmp_path: Path) -> None:
    base = list(tmp_path.stat())
    expected_gid = os.getegid()

    parent_fields = base.copy()
    parent_fields[0] = (parent_fields[0] & ~0o7777) | 0o750
    parent_fields[4] = 0
    parent_fields[5] = expected_gid
    lock_fields = base.copy()
    lock_fields[0] = stat.S_IFREG | 0o440
    lock_fields[3] = 1
    lock_fields[4] = 0
    lock_fields[5] = expected_gid

    parent = os.stat_result(parent_fields)
    lock = os.stat_result(lock_fields)
    assert config_module._shared_maintenance_lock_metadata_is_safe(
        parent,
        lock,
        expected_gid,
    )

    for index, replacement in (
        (3, 2),
        (4, 1),
        (5, expected_gid + 1),
    ):
        changed = lock_fields.copy()
        changed[index] = replacement
        assert not config_module._shared_maintenance_lock_metadata_is_safe(
            parent,
            os.stat_result(changed),
            expected_gid,
        )

    for unsafe_mode in (0o400, 0o460, 0o640):
        changed = lock_fields.copy()
        changed[0] = stat.S_IFREG | unsafe_mode
        assert not config_module._shared_maintenance_lock_metadata_is_safe(
            parent,
            os.stat_result(changed),
            expected_gid,
        )


# --- S21-43 / S21-41: route-by-route negative HTTP sweeps --------------------
# The literal role table above (test_s20_40_explicit_roles_...) is the reviewed
# expectation. These sweeps send real unauthorized HTTP requests for every
# registered method+path, so a new route that is published (or given the wrong
# role) fails here instead of relying on the policy function alone.

_S21_PATH_VALUES = {
    "{report_id}": "00000000-0000-4000-8000-000000000001",
    "{request_id}": "00000000-0000-4000-8000-000000000002",
    "{collection_id}": "00000000-0000-4000-8000-000000000003",
    "{object_id}": "00000000-0000-4000-8000-000000000004",
    "{index}": "0",
    "{copy_id}": "00000000-0000-4000-8000-000000000005",
    "{decision_id}": "00000000-0000-4000-8000-000000000006",
    "{device_id}": "device-test-1234",
    "{filename}": "example.jpg",
    "{incident_id}": "00000000-0000-4000-8000-000000000007",
    "{package_revision}": "1",
    "{session_id}": "00000000-0000-4000-8000-000000000008",
}
_S21_MIDDLEWARE_DENIALS = {
    "field_test_auth_required", "field_test_forbidden", "account_authentication_failed",
}


def _s21_concrete_path(template: str) -> str:
    path = template
    for placeholder, value in _S21_PATH_VALUES.items():
        path = path.replace(placeholder, value)
    assert "{" not in path, f"add a sample value for a new path parameter in {template}"
    return path


def _s21_literal_role(method: str, template: str) -> str:
    if (method, template) in _S21_LITERAL_PUBLIC:
        return "public"
    if (method, template) in _S21_LITERAL_FIELD:
        return "field"
    return "admin"


# Same reviewed literals as test_s20_40_explicit_roles_preserve_field_and_public_boundaries.
_S21_LITERAL_PUBLIC = frozenset({
    ("POST", "/admin/security/device-proof/challenges"),
    ("POST", "/admin/security/sessions"),
    ("POST", "/admin/security/recovery/start"),
    ("POST", "/admin/security/recovery/complete"),
})
_S21_LITERAL_FIELD = frozenset({
    ("GET", "/health"), ("GET", "/ready"), ("GET", "/detect/health"),
    ("POST", "/detect"), ("POST", "/detect/v2"),
    ("GET", "/navigation/walking/health"), ("GET", "/navigation/destinations/search"),
    ("GET", "/navigation/destinations/search/health"), ("POST", "/navigation/walking"),
    ("GET", "/privacy/consent-bootstrap"), ("POST", "/privacy/consent-events"),
    ("POST", "/privacy/account-deletions"),
    ("GET", "/privacy/account-deletions/{request_id}/status"),
    ("POST", "/privacy/account-deletions/{request_id}/device-evidence"),
    ("POST", "/account-enrollments/email-otp"), ("POST", "/accounts"),
    ("POST", "/accounts/authenticate"), ("POST", "/accounts/session-current"),
    ("HEAD", "/raw-collections/{collection_id}/manifest"),
    ("HEAD", "/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}"),
    ("HEAD", "/raw-collections/{collection_id}/commit"),
    ("PUT", "/raw-collections/{collection_id}/manifest"),
    ("PUT", "/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}"),
    ("GET", "/raw-collections/{collection_id}"),
    ("POST", "/raw-collections/{collection_id}/commit"),
    ("GET", "/reports/mine"), ("GET", "/reports/mine/requests/history"),
    ("GET", "/reports/mine/deletions/{request_id}"), ("GET", "/reports/mine/{report_id}"),
    ("POST", "/reports/mine/{report_id}/requests"),
    ("GET", "/reports/mine/{report_id}/requests/{request_id}"),
    ("GET", "/reports/mine/{report_id}/content"),
    ("POST", "/reports/mine/{report_id}/corrections"),
    ("POST", "/reports"), ("POST", "/reports/v2"),
    ("GET", "/reports/v2/{report_id}/status"), ("GET", "/internal/capacity"),
})


def _s21_registered_routes() -> list[tuple[str, str]]:
    return sorted(
        (method, route.path) for route in app.routes for method in route.methods
    )


def test_s21_43_literal_role_table_covers_every_registered_route() -> None:
    policies = {
        (method, template): access
        for method, template, access in field_security.FIELD_TEST_ROUTE_POLICIES
    }
    assert set(_s21_registered_routes()) == set(policies)
    for key, access in policies.items():
        expected = _s21_literal_role(*key)
        actual = {None: "public", FieldTestAccess.FIELD: "field", FieldTestAccess.ADMIN: "admin"}[access]
        assert actual == expected, key


def test_s21_43_every_protected_route_rejects_unauthorized_http(
    secured_client: ASGITestClient,
) -> None:
    checked = {"field": 0, "admin": 0}
    for method, template in _s21_registered_routes():
        role = _s21_literal_role(method, template)
        if role == "public":
            continue
        path = _s21_concrete_path(template)
        wrong = (
            {"x-walksafe-admin-token": ADMIN_TOKEN}
            if role == "field"
            else {"x-walksafe-field-test-token": FIELD_TOKEN}
        )
        for headers, allowed in (({}, {401}), (wrong, {403})):
            if (method, template) == ("POST", "/accounts/session-current"):
                # The account session check answers every non-field caller the same.
                allowed = {401}
            response = secured_client.request(method, path, headers=headers)
            assert response.status_code in allowed, (method, template, headers, response.status_code)
            if method != "HEAD":
                assert response.json()["detail"]["code"] in _S21_MIDDLEWARE_DENIALS, (
                    method, template, response.text,
                )
        checked[role] += 1
    assert checked["field"] == len(_S21_LITERAL_FIELD)
    assert checked["field"] + checked["admin"] + len(_S21_LITERAL_PUBLIC) == len(
        _s21_registered_routes()
    )


def test_s21_43_public_routes_pass_the_field_boundary_without_tokens() -> None:
    probe = _RoutePolicyProbe(tuple(_s21_registered_routes()))
    for method, template in sorted(_S21_LITERAL_PUBLIC):
        before = probe.events["downstream"]
        response = probe.request(method, template, headers=_RoutePolicyProbe.public_headers())
        assert response.status_code == 204, (method, template, response.text)
        assert probe.events["downstream"] == before + 1


def test_s21_43_production_admin_mode_keeps_every_protected_route_closed() -> None:
    """be-F7: the same literal table with admin_security_enabled=true (DB session path)."""
    routes = tuple(_s21_registered_routes())
    probe = _RoutePolicyProbe(routes, modern=True)
    authorized: list[str] = []

    def db_session_authorizer(_settings, *, raw_token, **_kwargs):
        # Stands in for the DB session check: only the probe's bearer is a live session.
        if raw_token != "probe-access-token":
            raise AdminSecurityError(
                "admin_authentication_required", "Administrator session is required.",
                status_code=401,
            )
        authorized.append(raw_token)
        return SimpleNamespace(
            admin_id="probe.admin", device_id="device-test-1234", session_id="session-1",
        )

    probe.target.admin_session_authorizer = db_session_authorizer
    checked = {"field": 0, "admin": 0}
    for method, template in routes:
        role = _s21_literal_role(method, template)
        if role == "public":
            continue
        path = _s21_concrete_path(template)
        # A live administrator session must not open a FIELD route either.
        wrong = "admin" if role == "field" else "field"
        for credentials in ({}, {"role": wrong}):
            reached = probe.events["downstream"]
            response = probe.request(method, path, **credentials)
            assert response.status_code in {401, 403}, (
                method, template, credentials, response.status_code,
            )
            assert probe.events["downstream"] == reached, (method, template, credentials)
        checked[role] += 1
    assert checked["field"] == len(_S21_LITERAL_FIELD)
    assert checked["field"] + checked["admin"] + len(_S21_LITERAL_PUBLIC) == len(routes)
    # Positive controls: a live administrator session reaches an admin read,
    # and a public route passes without credentials.
    admin_read = next(
        (method, template) for method, template in routes
        if method == "GET" and _s21_literal_role(method, template) == "admin"
    )
    reached = probe.events["downstream"]
    response = probe.request(admin_read[0], _s21_concrete_path(admin_read[1]), role="admin")
    assert response.status_code == 204, (admin_read, response.text)
    assert probe.events["downstream"] == reached + 1
    public_method, public_template = sorted(_S21_LITERAL_PUBLIC)[0]
    response = probe.request(
        public_method, public_template, headers=_RoutePolicyProbe.public_headers(),
    )
    assert response.status_code == 204, response.text
    assert probe.events["downstream"] == reached + 2


def _s21_actor_headers(actor: str, *, assertion: str | None) -> list[tuple[bytes, bytes]]:
    headers = [
        (field_security.FIELD_TOKEN_HEADER, FIELD_TOKEN.encode("ascii")),
        (field_security.ACTOR_ID_HEADER, actor.encode("ascii")),
        (field_security.ACCOUNT_GENERATION_HEADER, b"1"),
    ]
    if assertion is not None:
        headers.append((field_security.ACTOR_ASSERTION_HEADER, assertion.encode("ascii")))
    return headers


def _s21_field_actor_routes() -> list[tuple[str, str]]:
    routes = []
    for method, template in _s21_registered_routes():
        if _s21_literal_role(method, template) != "field":
            continue
        path = _s21_concrete_path(template)
        if field_security.requires_actor_identity(path, method):
            routes.append((method, template))
    return routes


# Privacy deletion routes use their own request-bound deletion assertion and
# check the tombstone/session in the handler, not the actor-session lookup.
_S21_PRIVACY_DELETION_ROUTES = frozenset({
    ("POST", "/privacy/account-deletions"),
    ("GET", "/privacy/account-deletions/{request_id}/status"),
    ("POST", "/privacy/account-deletions/{request_id}/device-evidence"),
})


def test_s21_41_every_field_actor_route_rejects_missing_and_revoked_sessions(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    routes = _s21_field_actor_routes()
    assert routes, "field actor routes must exist"
    probe = _RoutePolicyProbe(tuple(_s21_registered_routes()))
    # Production-like: no insecure local actor bypass; assertions are verified.
    probe.settings.walksafe_environment = "test"
    probe.settings.allow_insecure_local_dev = False
    secret = probe.settings.gateway_session_secret
    session_current = {"value": False}
    monkeypatch.setattr(
        field_security, "_actor_account_session_is_current",
        lambda _actor, _generation, _epoch: session_current["value"],
    )
    concealed = {"report_not_found", "report_transport_status_not_found"}
    session_checked = 0
    for index, (method, template) in enumerate(routes):
        path = _s21_concrete_path(template)
        actor = f"s21.actor.{index}"
        body = b"{}" if method in {"POST", "PUT"} else b""

        # (1) No credentials at all.
        start = dict(probe.events)
        missing = probe.request(method, path, body=body)
        assert missing.status_code == 401, (method, template, missing.status_code)
        assert probe.events["downstream"] == start["downstream"]

        if (method, template) in _S21_PRIVACY_DELETION_ROUTES:
            # Missing deletion binding is rejected before any handler.
            unbound = probe.request(method, path, body=body,
                                    headers=_s21_actor_headers(actor, assertion=None))
            assert unbound.status_code == 422, (method, template, unbound.status_code)
            assert unbound.json()["detail"]["code"] == "account_deletion_binding_invalid"
            assert probe.events["downstream"] == start["downstream"]
            continue

        # (2) Field token and actor headers, but no Gateway-bound assertion.
        unsigned = probe.request(method, path, body=body,
                                 headers=_s21_actor_headers(actor, assertion=None))
        assert unsigned.status_code in {401, 404}, (method, template, unsigned.status_code)
        if method != "HEAD":
            assert unsigned.json()["detail"]["code"] in {"actor_assertion_required"} | concealed
        assert probe.events["downstream"] == start["downstream"]

        def signed() -> list[tuple[bytes, bytes]]:
            assertion = field_security.create_actor_assertion_v4(
                actor, FieldTestAccess.FIELD, secret, account_generation=1,
                auth_epoch=3, method=method, target=path,
                body_sha256=hashlib.sha256(body).hexdigest(),
            )
            return _s21_actor_headers(actor, assertion=assertion)

        # (3) A validly signed assertion whose account session was revoked
        # (logout, generation or auth-epoch change) is rejected.
        session_current["value"] = False
        revoked = probe.request(method, path, body=body, headers=signed())
        assert revoked.status_code in {401, 404}, (method, template, revoked.status_code)
        if method != "HEAD":
            assert revoked.json()["detail"]["code"] in {"account_authentication_stale"} | concealed
        assert probe.events["downstream"] == start["downstream"]

        # Positive control: the same signed request with a current session
        # passes the session boundary (raw routes then need their raw proof).
        session_current["value"] = True
        current = probe.request(method, path, body=body, headers=signed())
        if template.startswith("/raw-collections/"):
            assert current.status_code == 401, (method, template, current.status_code)
            if method != "HEAD":
                assert current.json()["detail"]["code"] == "raw_request_proof_required"
            assert probe.events["downstream"] == start["downstream"]
        else:
            assert current.status_code == 204, (method, template, current.status_code, current.text)
            assert probe.events["downstream"] == start["downstream"] + 1
        session_checked += 1
    assert session_checked == len(routes) - len(_S21_PRIVACY_DELETION_ROUTES & set(routes))


# S21-41 other-user coverage. Every owner-scoped FIELD route (a path with a
# resource ID) must be listed with the direct test that proves another actor's
# resource is concealed, or be one of the recorded gaps below. A new resource
# route therefore fails until someone makes that decision explicitly.
_S21_OWNER_SCOPED_ROUTE_TESTS = {
    ("GET", "/reports/mine/{report_id}"):
        ("test_report_user_requests", "test_foreign_generation_and_tombstone_are_concealed_as_not_found"),
    ("GET", "/reports/mine/{report_id}/requests/{request_id}"):
        ("test_report_user_requests", "test_user_request_lookup_conceals_owner_generation_report_and_id_mismatch"),
    ("GET", "/reports/mine/{report_id}/content"):
        ("test_report_content_corrections", "test_foreign_actor_and_deletion_fence_are_concealed_before_mutation"),
    ("GET", "/reports/mine/deletions/{request_id}"):
        ("test_report_physical_deletion", "test_user_deletion_status_route_is_no_store_and_owner_bound"),
}
# Real PostgreSQL route tests below start with a verified Gateway identity. The
# middleware suite above independently checks missing/invalid/revoked authentication.
_S21_OWNER_SCOPED_ROUTE_TESTS.update({
    ('POST', '/reports/mine/{report_id}/requests'): ("test_owner_routes_postgres", 'test_foreign_report_routes_conceal_and_do_not_mutate'),
    ('POST', '/reports/mine/{report_id}/corrections'): ("test_owner_routes_postgres", 'test_foreign_report_routes_conceal_and_do_not_mutate'),
    ('GET', '/reports/v2/{report_id}/status'): ("test_owner_routes_postgres", 'test_foreign_report_routes_conceal_and_do_not_mutate'),
    ('GET', '/privacy/account-deletions/{request_id}/status'): ("test_owner_routes_postgres", 'test_foreign_deletion_routes_conceal_even_with_the_owners_capability'),
    ('POST', '/privacy/account-deletions/{request_id}/device-evidence'): ("test_owner_routes_postgres", 'test_foreign_deletion_routes_conceal_even_with_the_owners_capability'),
    ('HEAD', '/raw-collections/{collection_id}/manifest'): ("test_owner_routes_postgres", 'test_foreign_raw_routes_hide_resources_and_preflight_has_no_existence_oracle'),
    ('HEAD', '/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}'): ("test_owner_routes_postgres", 'test_foreign_raw_routes_hide_resources_and_preflight_has_no_existence_oracle'),
    ('HEAD', '/raw-collections/{collection_id}/commit'): ("test_owner_routes_postgres", 'test_foreign_raw_routes_hide_resources_and_preflight_has_no_existence_oracle'),
    ('PUT', '/raw-collections/{collection_id}/manifest'): ("test_owner_routes_postgres", 'test_foreign_raw_routes_hide_resources_and_preflight_has_no_existence_oracle'),
    ('PUT', '/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}'): ("test_owner_routes_postgres", 'test_foreign_raw_routes_hide_resources_and_preflight_has_no_existence_oracle'),
    ('GET', '/raw-collections/{collection_id}'): ("test_owner_routes_postgres", 'test_foreign_raw_routes_hide_resources_and_preflight_has_no_existence_oracle'),
    ('POST', '/raw-collections/{collection_id}/commit'): ("test_owner_routes_postgres", 'test_foreign_raw_routes_hide_resources_and_preflight_has_no_existence_oracle'),
})
_S21_OWNER_SCOPED_ROUTE_GAPS = frozenset()


def test_s21_41_owner_scoped_field_routes_have_other_user_decisions() -> None:
    import importlib

    owner_scoped = {
        (method, template)
        for method, template in _s21_field_actor_routes()
        if "{" in template
    }
    listed = set(_S21_OWNER_SCOPED_ROUTE_TESTS) | _S21_OWNER_SCOPED_ROUTE_GAPS
    assert not set(_S21_OWNER_SCOPED_ROUTE_TESTS) & _S21_OWNER_SCOPED_ROUTE_GAPS
    assert owner_scoped == listed
    for module_name, test_name in _S21_OWNER_SCOPED_ROUTE_TESTS.values():
        module = importlib.import_module(module_name)
        assert callable(getattr(module, test_name, None)), (module_name, test_name)
