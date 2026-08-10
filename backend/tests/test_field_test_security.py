from __future__ import annotations

import asyncio
import hashlib
import json
import os
from pathlib import Path
import sys
import threading
import time

import httpx
import pytest
from starlette.middleware.cors import CORSMiddleware


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

import backend.app.config as config_module  # noqa: E402
from backend.app.config import Settings, migration_database_url  # noqa: E402
from backend.app.field_test_security import (  # noqa: E402
    ACTOR_RATE_LIMITS,
    FieldTestAccess,
    _ACTOR_RATE_LIMITER,
    _POSTGRES_ACTOR_RATE_LIMITER,
    _RequestBodyIncomplete,
    _request_body_error_response,
    create_actor_assertion,
    required_field_test_access,
    verify_actor_assertion,
)
from backend.app.main import app, settings as app_settings  # noqa: E402
from backend.app.request_limits import (  # noqa: E402
    PrivacyExceptionMiddleware,
    PrivacyNoStoreMiddleware,
)
from backend.app.services.actor_rate_limit import ActorRateLimitStoreUnavailable  # noqa: E402
from model.two_model_runtime import DEFAULT_RUNTIME_CONFIG  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402


FIELD_TOKEN = "field-token-for-tests-1234567890"
ADMIN_TOKEN = "admin-token-for-tests-1234567890"


@pytest.fixture
def secured_client(monkeypatch: pytest.MonkeyPatch) -> ASGITestClient:
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
    assert required_field_test_access("/privacy/malformed", "GET") is FieldTestAccess.FIELD
    assert required_field_test_access("/reports/duplicate-check", "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access("/reports", "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access("/reports/export", "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access("/uploads/example.jpg", "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access("/reports/example/status", "PATCH") is FieldTestAccess.ADMIN
    assert required_field_test_access("/docs", "GET") is FieldTestAccess.ADMIN
    assert required_field_test_access("/detect/v2", "OPTIONS") is None
    assert required_field_test_access("/unknown", "GET") is FieldTestAccess.ADMIN


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
    assert (missing.status_code, admin.status_code, field.status_code) == (401, 403, 404)
    for response in (missing, admin, field):
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
    async def navigation_ready(_settings):
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
    bound = secured_client.post(
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

    assert unbound.status_code == 401
    assert unbound.json()["detail"]["code"] == "actor_assertion_required"
    assert spoofed.status_code == 401
    assert spoofed.json()["detail"]["code"] == "actor_assertion_invalid"
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
    monkeypatch.delenv("WALKSAFE_MIGRATION_DATABASE_URL", raising=False)
    with pytest.raises(ValueError, match="WALKSAFE_MIGRATION_DATABASE_URL"):
        migration_database_url()

    monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", runtime_url)
    with pytest.raises(ValueError, match="must be distinct"):
        migration_database_url()

    monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", migration_url)
    assert migration_database_url() == migration_url


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

    monkeypatch.setattr(_POSTGRES_ACTOR_RATE_LIMITER, "check", unavailable)

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

    monkeypatch.setattr(_POSTGRES_ACTOR_RATE_LIMITER, "check", blocking_check)

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
        ("TMAP_TIMEOUT_SECONDS", "31", "at most 30"),
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
    lock_dir = tmp_path / "locks"
    lock_dir.mkdir(mode=0o700)
    model.write_bytes(b"model")
    runtime_payload = json.loads(json.dumps(DEFAULT_RUNTIME_CONFIG))
    runtime_payload["models"]["unified_walksafe"]["artifact_sha256"] = hashlib.sha256(b"model").hexdigest()
    runtime_config.write_text(json.dumps(runtime_payload), encoding="utf-8")
    monkeypatch.setenv("DETECT_V2_MODE", "real")
    monkeypatch.setenv("DETECT_V2_UNIFIED_MODEL_PATH", str(model))
    monkeypatch.setenv("DETECT_V2_RUNTIME_CONFIG_PATH", str(runtime_config))
    monkeypatch.setenv("WALKSAFE_MAINTENANCE_LOCK_PATH", str(lock_dir / "maintenance.lock"))

    with pytest.raises(ValueError, match="root-owned non-writable authority ancestry"):
        Settings()

    monkeypatch.setattr("backend.app.config._trusted_maintenance_lock_parent", lambda _parent: True)

    settings = Settings()
    assert settings.detect_v2_unified_model_path == model

    lock_dir.chmod(0o500)
    with pytest.raises(ValueError, match="mode 0700"):
        Settings()
    lock_dir.chmod(0o700)

    lock_path = lock_dir / "maintenance.lock"
    lock_path.touch(mode=0o644)
    with pytest.raises(ValueError, match="single-link 0600"):
        Settings()
    lock_path.chmod(0o600)
    second_link = lock_dir / "maintenance-second-link.lock"
    os.link(lock_path, second_link)
    with pytest.raises(ValueError, match="single-link 0600"):
        Settings()
    second_link.unlink()

    monkeypatch.setenv("TMAP_POI_PROVIDER", "mock")
    with pytest.raises(ValueError, match="must be live"):
        Settings()
    monkeypatch.setenv("TMAP_POI_PROVIDER", "live")

    monkeypatch.setenv("UPLOAD_DIR", "relative-uploads")
    with pytest.raises(ValueError, match="UPLOAD_DIR must be absolute"):
        Settings()
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    monkeypatch.setenv("UPLOAD_DIR", str(upload_dir.resolve()))

    upload_dir.chmod(0o755)
    with pytest.raises(ValueError, match="denies group/other"):
        Settings()
    upload_dir.chmod(0o700)

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
