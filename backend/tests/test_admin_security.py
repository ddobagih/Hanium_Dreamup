from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
from threading import Barrier, Event
from time import monotonic
from types import SimpleNamespace
import uuid

from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from fastapi import FastAPI, HTTPException, Request
import pyotp
import pytest
from sqlalchemy import UniqueConstraint, create_engine, select, text
from sqlalchemy.orm import sessionmaker

from conftest import _ADMIN_SECURITY_CLEANUP_TABLES
from backend.app import database as database_api
from backend.app import field_test_security as field_test_security_api
from backend.app.api import admin_security as admin_security_api
from backend.app.api import reports as reports_api
from backend.app.config import Settings
from backend.app.field_test_security import (
    ADMIN_RECONFIRM_NONCE_HEADER_NAME,
    FieldTestSecurityMiddleware,
    _ACTOR_RATE_LIMITER,
    classify_admin_operation,
)
from backend.app.openapi_contract import install_walksafe_openapi_contract
from backend.app.services.admin_security import (
    ADMIN_APP_KIND,
    ADMIN_AUDIENCE,
    ADMIN_ROLE,
    AdminSecurityError,
    AdminSecurityService,
    AdminSecurityStoreUnavailable,
    AdminSessionIdentity,
    ReconfirmationGrant,
    RecoveryGrant,
    SessionGrant,
    admin_security_audit_entry_sha256,
    auth_rate_limit_principal,
    authorize_admin_bearer,
    authorize_admin_protected_work,
    consume_admin_high_risk_reconfirmation,
    hash_password,
    provision_admin_security,
    sha256_text,
    valid_reconfirmation_nonce,
    verify_password,
    verify_totp_timecode,
)
from backend.app.models import (
    AdminSecurityAudit,
    AdminSecurityAuthAttempt,
    AdminSecurityControl,
    AdminSecurityReconfirmation,
    AdminSecurityRecoveryCode,
    AdminSecurityRecoveryTransaction,
    AdminSecuritySession,
    Base,
)
from asgi_client import ASGITestClient


DEVICE_ID = "android-device-0001"
RECONFIRM_NONCE = "MDEyMzQ1Njc4OWFiY2RlZg"
VALID_HEADERS = {
    "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
    "X-WalkSafe-Role": ADMIN_ROLE,
    "X-WalkSafe-Audience": ADMIN_AUDIENCE,
    "X-WalkSafe-Device-Id": DEVICE_ID,
}


def _settings() -> SimpleNamespace:
    return SimpleNamespace(
        admin_security_enabled=True,
        admin_step_up_ttl_seconds=300,
        field_test_security_enabled=True,
        field_test_token="field-token-for-admin-security-tests",
        admin_token="legacy-static-admin-token-for-tests",
        walksafe_environment="test",
        allow_insecure_local_dev=False,
        gateway_session_secret="",
        actor_rate_limit_store="memory",
    )


def _identity() -> AdminSessionIdentity:
    now = datetime(2026, 7, 22, 12, 0, 0, tzinfo=timezone.utc)
    return AdminSessionIdentity(
        admin_id="walksafe.admin",
        session_id=uuid.UUID("11111111-1111-4111-8111-111111111111"),
        device_id=DEVICE_ID,
        device_label="관리자 휴대전화",
        expires_at=now + timedelta(hours=1),
        step_up_verified_at=now,
    )


def test_report_high_risk_endpoint_only_rechecks_protected_work(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[str, dict[str, object]]] = []
    database_session = object()

    def authorize(db, raw_token, action, **kwargs):
        calls.append(
            (
                "protected",
                {
                    "db": db,
                    "raw_token": raw_token,
                    "action": action,
                    **kwargs,
                },
            )
        )
        return _identity()

    monkeypatch.setattr(reports_api, "authorize_admin_protected_work", authorize)
    request = Request(
        {
            "type": "http",
            "method": "PATCH",
            "path": "/reports/11111111-1111-4111-8111-111111111111/status",
            "headers": [
                (b"authorization", b"Bearer endpoint-token"),
                (b"x-walksafe-device-id", DEVICE_ID.encode()),
                (b"x-walksafe-app-kind", ADMIN_APP_KIND.encode()),
                (b"x-walksafe-role", ADMIN_ROLE.encode()),
                (b"x-walksafe-audience", ADMIN_AUDIENCE.encode()),
                (b"x-walksafe-reconfirm-nonce", RECONFIRM_NONCE.encode()),
            ],
        }
    )
    settings = SimpleNamespace(
        admin_security_enabled=True,
        admin_totp_secret="JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
        admin_step_up_ttl_seconds=60,
    )

    identity = reports_api._reauthorize_admin_high_risk_in_transaction(
        request,
        database_session,
        settings,
        "report.status.patch",
    )

    security_context = {
        "raw_token": "endpoint-token",
        "action": "report.status.patch",
        "method": "PATCH",
        "path": "/reports/11111111-1111-4111-8111-111111111111/status",
        "runtime_totp_secret": settings.admin_totp_secret,
        "device_id": DEVICE_ID,
        "app_kind": ADMIN_APP_KIND,
        "role": ADMIN_ROLE,
        "audience": ADMIN_AUDIENCE,
    }
    assert identity == _identity()
    assert calls == [
        (
            "protected",
            {
                "db": database_session,
                **security_context,
            },
        ),
    ]


@pytest.mark.parametrize(
    ("failure_code", "status_code", "action", "method", "path"),
    [
        (
            "report_not_found",
            404,
            "report.status.patch",
            "PATCH",
            "/reports/11111111-1111-4111-8111-111111111111/status",
        ),
        (
            "report_status_conflict",
            409,
            "report.status.patch",
            "PATCH",
            "/reports/11111111-1111-4111-8111-111111111111/status",
        ),
        (
            "report_export_validation_failed",
            422,
            "report.export",
            "GET",
            "/reports/export",
        ),
    ],
)
def test_failed_report_work_cannot_reuse_centrally_consumed_reconfirmation(
    monkeypatch: pytest.MonkeyPatch,
    failure_code: str,
    status_code: int,
    action: str,
    method: str,
    path: str,
) -> None:
    consumed_nonces: set[str] = set()
    successful_consumptions = 0
    endpoint_calls = 0

    def authorize(
        _settings_value,
        *,
        high_risk_action,
        reconfirmation_nonce,
        **_kwargs,
    ):
        nonlocal successful_consumptions
        assert high_risk_action == action
        if reconfirmation_nonce in consumed_nonces:
            raise AdminSecurityError(
                "admin_reconfirmation_required",
                "A fresh administrator reconfirmation is required.",
                status_code=403,
            )
        consumed_nonces.add(reconfirmation_nonce)
        successful_consumptions += 1
        return _identity()

    settings = _settings()
    app = FastAPI()

    @app.api_route(path, methods=[method])
    def failed_endpoint():
        nonlocal endpoint_calls
        endpoint_calls += 1
        raise HTTPException(
            status_code=status_code,
            detail={"code": failure_code},
        )

    monkeypatch.setattr(
        field_test_security_api,
        "record_admin_security_denial",
        lambda **_kwargs: None,
    )
    app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=settings,
        admin_session_authorizer=authorize,
    )
    client = ASGITestClient(app)
    headers = {
        **VALID_HEADERS,
        "Authorization": "Bearer endpoint-token",
        "X-WalkSafe-Reconfirm-Nonce": RECONFIRM_NONCE,
    }

    failed_work = client.request(method, path, headers=headers)
    reused = client.request(method, path, headers=headers)

    assert failed_work.status_code == status_code
    assert failed_work.json()["detail"] == {"code": failure_code}
    assert reused.status_code == 403
    assert reused.json()["detail"]["code"] == "admin_reconfirmation_required"
    assert successful_consumptions == 1
    assert endpoint_calls == 1


def test_recovery_state_recheck_blocks_protected_report_work(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []
    protected_work: list[bool] = []

    def reject_recovered_session(*_args, **_kwargs):
        calls.append("protected")
        raise AdminSecurityError(
            "admin_session_invalid",
            "The administrator session is invalid or expired.",
            status_code=401,
        )

    monkeypatch.setattr(
        reports_api,
        "authorize_admin_protected_work",
        reject_recovered_session,
    )
    monkeypatch.setattr(
        reports_api,
        "record_admin_security_denial",
        lambda **_kwargs: None,
    )
    request = Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/reports/export",
            "headers": [
                (b"authorization", b"Bearer endpoint-token"),
                (b"x-walksafe-device-id", DEVICE_ID.encode()),
                (b"x-walksafe-app-kind", ADMIN_APP_KIND.encode()),
                (b"x-walksafe-role", ADMIN_ROLE.encode()),
                (b"x-walksafe-audience", ADMIN_AUDIENCE.encode()),
                (b"x-walksafe-reconfirm-nonce", RECONFIRM_NONCE.encode()),
            ],
        }
    )

    with pytest.raises(HTTPException) as recovered:
        reports_api._reauthorize_admin_high_risk_in_transaction(
            request,
            object(),
            SimpleNamespace(
                admin_security_enabled=True,
                admin_totp_secret="JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
                admin_step_up_ttl_seconds=60,
            ),
            "report.export",
        )
        protected_work.append(True)

    assert recovered.value.status_code == 401
    assert recovered.value.detail["code"] == "admin_session_invalid"
    assert calls == ["protected"]
    assert protected_work == []


class FakeAdminSecurityService:
    def __init__(self) -> None:
        self.login_calls: list[dict[str, object]] = []
        self.complete_calls: list[dict[str, object]] = []

    def login(self, **kwargs):
        self.login_calls.append(kwargs)
        return SessionGrant("opaque-login-token", "NORMAL", str(_identity().session_id))

    def get_state(self, identity):
        assert identity == _identity()
        return {
            "security_state": "NORMAL",
            "state_version": "7",
            "observed_at": "2026-07-22T12:00:00+00:00",
        }

    def list_sessions(self, identity):
        assert identity == _identity()
        return [
            {
                "session_id": str(identity.session_id),
                "device_id": identity.device_id,
                "device_label": identity.device_label,
                "current": True,
                "revoked": False,
                "last_seen_at": "2026-07-22T12:00:00+00:00",
            }
        ]

    def revoke_session(self, identity, session_id):
        assert identity.session_id == session_id
        return {
            "security_state": "NORMAL",
            "state_version": "7",
            "observed_at": "2026-07-22T12:00:00+00:00",
        }

    def reauthenticate(self, identity, **kwargs):
        assert identity == _identity()
        assert kwargs == {
            "password": "correct horse",
            "totp_code": "123456",
            "action": "report.export",
            "method": "GET",
            "path": "/reports/export",
            "nonce": RECONFIRM_NONCE,
            "source": "127.0.0.1",
        }
        return ReconfirmationGrant(
            reauthenticated_until_epoch_ms=1_785_000_000_000,
            action="report.export",
            method="GET",
            path="/reports/export",
        )

    def start_recovery(self, **kwargs):
        return RecoveryGrant("opaque-recovery-token", "RECOVERY_IN_PROGRESS")

    def complete_recovery(self, **kwargs):
        self.complete_calls.append(kwargs)
        return SessionGrant("new-opaque-token", "NORMAL", str(_identity().session_id))


def _authorized(
    captures: list[dict[str, object]],
    _settings_value,
    **kwargs,
) -> AdminSessionIdentity:
    captures.append(kwargs)
    if kwargs["raw_token"] != "valid-bearer-token":
        raise AdminSecurityError(
            "admin_session_invalid",
            "The administrator session is invalid or expired.",
            status_code=401,
        )
    return _identity()


def _api_client(service: FakeAdminSecurityService, captures: list[dict[str, object]]) -> ASGITestClient:
    settings = _settings()
    app = FastAPI()
    app.include_router(
        admin_security_api.create_router(settings, service_factory=lambda: service)
    )
    app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=settings,
        admin_session_authorizer=lambda settings_value, **kwargs: _authorized(
            captures, settings_value, **kwargs
        ),
    )
    return ASGITestClient(app)


def test_password_hash_uses_random_salt_and_constant_time_digest_verification() -> None:
    first = hash_password("correct horse battery")
    second = hash_password("correct horse battery")

    assert first != second
    assert verify_password("correct horse battery", first) is True
    assert verify_password("wrong password value", first) is False
    assert verify_password("correct horse battery", "malformed") is False


def test_totp_uses_pyotp_verified_timecode_and_rejects_reuse() -> None:
    secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    now = datetime(2026, 7, 22, 12, 0, 0, tzinfo=timezone.utc)
    code = pyotp.TOTP(secret).at(now)

    accepted, replayed = verify_totp_timecode(
        secret,
        code,
        last_accepted_timecode=None,
        now=now,
    )
    repeated, repeated_replayed = verify_totp_timecode(
        secret,
        code,
        last_accepted_timecode=accepted,
        now=now,
    )

    assert accepted is not None and replayed is False
    assert repeated == accepted and repeated_replayed is True


def test_totp_accepts_adjacent_window_and_rejects_that_timecode_replay() -> None:
    secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    now = datetime(2026, 7, 22, 12, 0, 0, tzinfo=timezone.utc)
    previous_code = pyotp.TOTP(secret).at(now - timedelta(seconds=30))

    accepted, replayed = verify_totp_timecode(
        secret,
        previous_code,
        last_accepted_timecode=None,
        now=now,
    )
    repeated, repeated_replayed = verify_totp_timecode(
        secret,
        previous_code,
        last_accepted_timecode=accepted,
        now=now,
    )

    assert accepted == pyotp.TOTP(secret).timecode(now - timedelta(seconds=30))
    assert replayed is False
    assert repeated == accepted and repeated_replayed is True


def test_reconfirmation_nonce_requires_canonical_unpadded_base64url_16_bytes() -> None:
    assert len(RECONFIRM_NONCE) == 22
    assert valid_reconfirmation_nonce(RECONFIRM_NONCE) is True

    assert valid_reconfirmation_nonce(RECONFIRM_NONCE[:-1]) is False
    assert valid_reconfirmation_nonce(RECONFIRM_NONCE + "A") is False
    assert valid_reconfirmation_nonce(RECONFIRM_NONCE + "==") is False
    assert valid_reconfirmation_nonce(RECONFIRM_NONCE[:-1] + "h") is False


def test_auth_rate_limit_principal_cannot_be_varied_with_submitted_admin_id() -> None:
    configured = "walksafe.admin"
    assert auth_rate_limit_principal(configured, "login") == auth_rate_limit_principal(
        configured, "login"
    )
    assert auth_rate_limit_principal(
        configured, "recovery_start"
    ) == auth_rate_limit_principal(configured, "login")
    assert auth_rate_limit_principal(
        configured, "recovery_complete", recovery_token="stolen-token"
    ) == auth_rate_limit_principal(
        configured, "recovery_complete", recovery_token="another-token"
    )
    assert auth_rate_limit_principal(
        configured, "reauthenticate"
    ) != auth_rate_limit_principal(
        configured, "recovery_complete", recovery_token="stolen-token"
    )


def test_short_recovery_code_is_rejected_before_database_use() -> None:
    class NoDatabaseUse:
        def execute(self, *_args, **_kwargs):
            raise AssertionError("database must not be used for a weak recovery code")

    with pytest.raises(ValueError, match="24 or more"):
        provision_admin_security(
            NoDatabaseUse(),
            admin_id="walksafe.admin",
            password="correct horse battery",
            totp_secret="JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
            recovery_codes=["R" * 23],
        )


def test_provision_rejects_short_totp_seed_before_database_use() -> None:
    class NoDatabaseUse:
        def execute(self, *_args, **_kwargs):
            raise AssertionError("database must not be used for a weak TOTP seed")

    with pytest.raises(ValueError, match="20 decoded bytes"):
        provision_admin_security(
            NoDatabaseUse(),
            admin_id="walksafe.admin",
            password="correct horse battery",
            totp_secret="A" * 24,
            recovery_codes=["R" * 24],
        )


def test_public_login_requires_exact_client_headers_and_returns_exact_contract() -> None:
    service = FakeAdminSecurityService()
    client = _api_client(service, [])
    payload = {
        "admin_id": "walksafe.admin",
        "password": "correct horse",
        "totp_code": "123456",
        "device_id": DEVICE_ID,
        "device_label": "관리자 휴대전화",
    }

    missing_headers = client.post("/admin/security/sessions", json=payload)
    wrong_device = client.post(
        "/admin/security/sessions",
        json=payload,
        headers={**VALID_HEADERS, "X-WalkSafe-Device-Id": "android-device-9999"},
    )
    accepted = client.post(
        "/admin/security/sessions",
        json=payload,
        headers=VALID_HEADERS,
    )

    assert missing_headers.status_code == 403
    assert wrong_device.status_code == 403
    assert len(service.login_calls) == 1
    assert accepted.status_code == 200
    assert accepted.headers["cache-control"] == "no-store"
    assert accepted.headers["pragma"] == "no-cache"
    assert accepted.json() == {
        "access_token": "opaque-login-token",
        "security_state": "NORMAL",
        "current_session_id": str(_identity().session_id),
    }


def test_recovery_complete_forwards_source_to_database_limited_service() -> None:
    service = FakeAdminSecurityService()
    client = _api_client(service, [])

    response = client.post(
        "/admin/security/recovery/complete",
        headers=VALID_HEADERS,
        json={
            "recovery_token": "r" * 48,
            "new_password": "new correct horse",
            "totp_code": "123456",
            "device_id": DEVICE_ID,
            "device_label": "관리자 휴대전화",
        },
    )

    assert response.status_code == 200
    assert service.complete_calls[0]["source"] == "127.0.0.1"
    assert set(response.json()) == {
        "access_token",
        "security_state",
        "current_session_id",
    }


def test_static_admin_token_cannot_bypass_database_session(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = FakeAdminSecurityService()
    captures: list[dict[str, object]] = []
    monkeypatch.setattr(
        "backend.app.field_test_security.record_admin_security_denial",
        lambda **_kwargs: None,
    )
    client = _api_client(service, captures)

    response = client.get(
        "/admin/security/state",
        headers={
            **VALID_HEADERS,
            "X-WalkSafe-Admin-Token": "legacy-static-admin-token-for-tests",
        },
    )

    assert response.status_code == 401
    assert captures[0]["raw_token"] == ""


def test_admin_auth_denial_audit_failure_overrides_original_with_503(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    endpoint_calls: list[bool] = []
    settings = _settings()
    app = FastAPI()

    @app.get("/protected")
    def protected():
        endpoint_calls.append(True)
        return {"unexpected": True}

    def deny(_settings_value, **_kwargs):
        raise AdminSecurityError(
            "admin_session_invalid",
            "The administrator session is invalid or expired.",
            status_code=401,
        )

    def audit_unavailable(**_kwargs):
        raise AdminSecurityStoreUnavailable()

    monkeypatch.setattr(
        "backend.app.field_test_security.record_admin_security_denial",
        audit_unavailable,
    )
    app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=settings,
        admin_session_authorizer=deny,
    )

    response = ASGITestClient(app).get(
        "/protected",
        headers={
            **VALID_HEADERS,
            "X-WalkSafe-Admin-Token": "legacy-static-admin-token-for-tests",
        },
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "admin_security_store_unavailable"
    assert endpoint_calls == []


def test_valid_bearer_injects_identity_and_preserves_exact_session_contracts() -> None:
    _ACTOR_RATE_LIMITER.reset()
    service = FakeAdminSecurityService()
    captures: list[dict[str, object]] = []
    client = _api_client(service, captures)
    headers = {**VALID_HEADERS, "Authorization": "Bearer valid-bearer-token"}

    state = client.get("/admin/security/state", headers=headers)
    sessions = client.get("/admin/security/sessions", headers=headers)

    assert state.status_code == 200
    assert state.json() == {
        "security_state": "NORMAL",
        "state_version": "7",
        "observed_at": "2026-07-22T12:00:00+00:00",
    }
    assert sessions.status_code == 200
    assert sessions.json() == {
        "sessions": [
            {
                "session_id": str(_identity().session_id),
                "device_id": DEVICE_ID,
                "device_label": "관리자 휴대전화",
                "current": True,
                "revoked": False,
                "last_seen_at": "2026-07-22T12:00:00+00:00",
            }
        ]
    }


def test_reauthentication_forwards_and_returns_exact_operation_binding() -> None:
    service = FakeAdminSecurityService()
    client = _api_client(service, [])

    response = client.post(
        "/admin/security/reauthenticate",
        headers={**VALID_HEADERS, "Authorization": "Bearer valid-bearer-token"},
        json={
            "password": "correct horse",
            "totp_code": "123456",
            "action": "report.export",
            "method": "GET",
            "path": "/reports/export",
            "nonce": RECONFIRM_NONCE,
        },
    )

    assert response.status_code == 200
    assert response.json() == {
        "reauthenticated_until_epoch_ms": 1_785_000_000_000,
        "action": "report.export",
        "method": "GET",
        "path": "/reports/export",
    }


def test_admin_operation_classifier_is_exact_and_unknown_writes_fail_closed() -> None:
    export = classify_admin_operation("GET", "/reports/export")
    status_patch = classify_admin_operation(
        "PATCH",
        "/reports/11111111-1111-4111-8111-111111111111/status",
    )
    original_grant = classify_admin_operation(
        "POST",
        "/reports/11111111-1111-4111-8111-111111111111/original-access-grants",
    )

    assert export is not None
    assert (export.action, export.method, export.path) == (
        "report.export",
        "GET",
        "/reports/export",
    )
    assert status_patch is not None
    assert (status_patch.action, status_patch.method) == (
        "report.status.patch",
        "PATCH",
    )
    assert original_grant is not None
    assert (original_grant.action, original_grant.method) == (
        "report.original.grant",
        "POST",
    )
    assert classify_admin_operation("POST", "/reports/export") is None
    assert classify_admin_operation("GET", "/reports/export/near") is None
    assert classify_admin_operation("PATCH", "/reports/not-a-uuid/status") is None
    assert classify_admin_operation("POST", "/reports/not-a-uuid/original-access-grants") is None


def test_default_admin_authorizer_uses_high_risk_bearer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_session = object()
    captured: list[dict[str, object]] = []

    class BeginTransaction:
        def __enter__(self):
            return database_session

        def __exit__(self, exc_type, exc, traceback):
            return False

    class FakeSessionLocal:
        @staticmethod
        def begin():
            return BeginTransaction()

    def authorize_high_risk(db, raw_token, action, **kwargs):
        captured.append(
            {
                "db": db,
                "raw_token": raw_token,
                "action": action,
                **kwargs,
            }
        )
        return _identity()

    monkeypatch.setattr("backend.app.database.SessionLocal", FakeSessionLocal)
    monkeypatch.setattr(
        field_test_security_api,
        "authorize_high_risk_bearer",
        authorize_high_risk,
    )
    monkeypatch.setattr(
        field_test_security_api,
        "authorize_admin_bearer",
        lambda *_args, **_kwargs: pytest.fail(
            "a HIGH operation must not use the standard bearer authorizer"
        ),
    )

    identity = field_test_security_api._authorize_admin_request(
        SimpleNamespace(
            admin_totp_secret="JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
        ),
        raw_token="endpoint-token",
        device_id=DEVICE_ID,
        app_kind=ADMIN_APP_KIND,
        role=ADMIN_ROLE,
        audience=ADMIN_AUDIENCE,
        high_risk_action="report.export",
        method="GET",
        path="/reports/export",
        reconfirmation_nonce=RECONFIRM_NONCE,
    )

    assert identity == _identity()
    assert captured == [
        {
            "db": database_session,
            "raw_token": "endpoint-token",
            "action": "report.export",
            "method": "GET",
            "path": "/reports/export",
            "nonce": RECONFIRM_NONCE,
            "runtime_totp_secret": "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
            "device_id": DEVICE_ID,
            "app_kind": ADMIN_APP_KIND,
            "role": ADMIN_ROLE,
            "audience": ADMIN_AUDIENCE,
        }
    ]


def test_valid_bearer_cannot_reach_unregistered_admin_write(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _ACTOR_RATE_LIMITER.reset()
    endpoint_calls: list[bool] = []
    denials: list[dict[str, object]] = []
    captures: list[dict[str, object]] = []
    settings = _settings()
    app = FastAPI()

    @app.post("/reports/unregistered-write")
    def unregistered_write():
        endpoint_calls.append(True)
        return {"unexpected": True}

    monkeypatch.setattr(
        "backend.app.field_test_security.record_admin_security_denial",
        lambda **kwargs: denials.append(kwargs),
    )
    app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=settings,
        admin_session_authorizer=lambda settings_value, **kwargs: _authorized(
            captures, settings_value, **kwargs
        ),
    )

    response = ASGITestClient(app).post(
        "/reports/unregistered-write",
        headers={**VALID_HEADERS, "Authorization": "Bearer valid-bearer-token"},
    )

    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "admin_operation_not_registered"
    assert endpoint_calls == []
    assert denials == [
        {
            "action": "admin.write.unregistered",
            "reason": "admin_operation_not_registered",
            "method": "POST",
            "path": "/reports/unregistered-write",
            "device_id": DEVICE_ID,
        }
    ]


def test_report_high_risk_denial_is_recorded_outside_the_endpoint_transaction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    denials: list[dict[str, object]] = []

    def deny(*_args, **_kwargs):
        raise AdminSecurityError(
            "admin_reconfirmation_invalid",
            "The administrator reconfirmation is invalid.",
            status_code=403,
        )

    monkeypatch.setattr(
        reports_api,
        "authorize_admin_protected_work",
        deny,
    )
    monkeypatch.setattr(
        reports_api,
        "record_admin_security_denial",
        lambda **kwargs: denials.append(kwargs),
    )
    request = Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/reports/export",
            "headers": [
                (b"x-walksafe-device-id", DEVICE_ID.encode()),
                (b"x-walksafe-reconfirm-nonce", RECONFIRM_NONCE.encode()),
            ],
        }
    )

    with pytest.raises(Exception) as denied:
        reports_api._reauthorize_admin_high_risk_in_transaction(
            request,
            object(),
            SimpleNamespace(
                admin_security_enabled=True,
                admin_totp_secret="A" * 32,
                admin_step_up_ttl_seconds=60,
            ),
            "report.export",
        )

    assert getattr(denied.value, "status_code", None) == 403
    assert denials == [
        {
            "action": "report.export",
            "reason": "admin_reconfirmation_invalid",
            "method": "GET",
            "path": "/reports/export",
            "device_id": DEVICE_ID,
        }
    ]


def test_reconfirmation_and_audit_models_expose_bound_and_chained_columns() -> None:
    assert {
        "action",
        "method",
        "path",
        "nonce_sha256",
        "consumed_at",
    }.issubset(AdminSecurityReconfirmation.__table__.columns.keys())
    assert {
        "sequence",
        "previous_entry_sha256",
        "entry_sha256",
    }.issubset(AdminSecurityAudit.__table__.columns.keys())
    sequence_constraints = [
        constraint
        for constraint in AdminSecurityAudit.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
        and tuple(column.name for column in constraint.columns) == ("sequence",)
    ]
    assert [constraint.name for constraint in sequence_constraints] == [
        "uq_admin_security_audits_sequence"
    ]


def test_admin_security_cleanup_covers_reconfirmations_before_parent_state() -> None:
    assert _ADMIN_SECURITY_CLEANUP_TABLES.count(
        "admin_security_reconfirmations"
    ) == 1
    reconfirmation_position = _ADMIN_SECURITY_CLEANUP_TABLES.index(
        "admin_security_reconfirmations"
    )
    assert reconfirmation_position < _ADMIN_SECURITY_CLEANUP_TABLES.index(
        "admin_security_sessions"
    )
    assert reconfirmation_position < _ADMIN_SECURITY_CLEANUP_TABLES.index(
        "admin_security_controls"
    )


def test_admin_audit_hash_chain_detects_tamper_and_order_changes() -> None:
    created_at = datetime(2026, 7, 26, 12, 0, 0, tzinfo=timezone.utc)
    common = {
        "admin_id": "walksafe.admin",
        "session_id": uuid.UUID("11111111-1111-4111-8111-111111111111"),
        "device_id": DEVICE_ID,
        "action": "report.export",
        "outcome": "ALLOWED",
        "created_at": created_at,
    }
    first = admin_security_audit_entry_sha256(
        event_id=uuid.UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        sequence=1,
        previous_entry_sha256=None,
        details={"method": "GET", "path": "/reports/export"},
        **common,
    )
    second = admin_security_audit_entry_sha256(
        event_id=uuid.UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        sequence=2,
        previous_entry_sha256=first,
        details={"method": "GET", "path": "/reports/export"},
        **common,
    )
    tampered = admin_security_audit_entry_sha256(
        event_id=uuid.UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        sequence=2,
        previous_entry_sha256=first,
        details={"method": "POST", "path": "/reports/export"},
        **common,
    )
    reordered = admin_security_audit_entry_sha256(
        event_id=uuid.UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        sequence=1,
        previous_entry_sha256=None,
        details={"method": "GET", "path": "/reports/export"},
        **common,
    )

    assert len(first) == 64
    assert second != first
    assert tampered != second
    assert reordered != second


def test_action_bound_audit_migration_preserves_append_only_constraints() -> None:
    migration = (
        Path(__file__).parents[1]
        / "alembic"
        / "versions"
        / "202607260001_admin_security_action_bound_audit.py"
    ).read_text(encoding="utf-8")
    normalized = " ".join(migration.lower().split())

    assert "admin_security_reconfirmations" in normalized
    assert "before update or delete or truncate" in normalized
    assert "sequence" in normalized
    assert "previous_entry_sha256" in normalized
    assert "entry_sha256" in normalized
    assert "checkconstraint" in normalized
    assert (
        'op.create_unique_constraint( "uq_admin_security_audits_sequence", '
        '"admin_security_audits", ["sequence"], )'
        in normalized
    )
    assert (
        'op.drop_constraint( "uq_admin_security_audits_sequence", '
        '"admin_security_audits", type_="unique", )'
        in normalized
    )


@pytest.mark.parametrize(
    ("method", "path", "expected_action"),
    [
        ("GET", "/reports/export", "report.export"),
        ("PATCH", "/reports/11111111-1111-4111-8111-111111111111/status", "report.status.patch"),
        (
            "POST",
            "/reports/11111111-1111-4111-8111-111111111111/original-access-grants",
            "report.original.grant",
        ),
    ],
)
def test_high_risk_routes_use_common_recent_step_up_authorizer(
    method: str,
    path: str,
    expected_action: str,
) -> None:
    _ACTOR_RATE_LIMITER.reset()
    captures: list[dict[str, object]] = []
    settings = _settings()
    app = FastAPI()

    @app.api_route(path, methods=[method])
    def high_risk_endpoint(request: Request):
        return {"actor": request.headers["x-walksafe-actor-id"]}

    app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=settings,
        admin_session_authorizer=lambda settings_value, **kwargs: _authorized(
            captures, settings_value, **kwargs
        ),
    )
    response = ASGITestClient(app).request(
        method,
        path,
        headers={
            **VALID_HEADERS,
            "Authorization": "Bearer valid-bearer-token",
            "X-WalkSafe-Reconfirm-Nonce": RECONFIRM_NONCE,
        },
    )

    assert response.status_code == 200
    assert response.json() == {"actor": "walksafe.admin"}
    assert captures[0]["high_risk_action"] == expected_action
    assert captures[0]["reconfirmation_nonce"] == RECONFIRM_NONCE


def test_admin_session_store_failure_fails_closed() -> None:
    settings = _settings()
    app = FastAPI()

    @app.get("/protected")
    def protected():
        return {"unexpected": True}

    def unavailable(_settings_value, **_kwargs):
        raise AdminSecurityStoreUnavailable()

    app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=settings,
        admin_session_authorizer=unavailable,
    )
    response = ASGITestClient(app).get(
        "/protected",
        headers={**VALID_HEADERS, "Authorization": "Bearer valid-bearer-token"},
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "admin_security_store_unavailable"


def test_enabled_openapi_documents_bearer_and_exact_admin_context() -> None:
    settings = _settings()
    app = FastAPI()
    app.include_router(reports_api.create_router(settings, SimpleNamespace()))

    @app.post("/admin/security/sessions")
    def login():
        return {}

    @app.get("/admin/security/state")
    def get_admin_security_state():
        return {}

    @app.get("/health")
    def health():
        return {}

    install_walksafe_openapi_contract(app, settings)
    schema = app.openapi()
    schemes = schema["components"]["securitySchemes"]

    assert "WalkSafeAdminBearer" in schemes
    assert "WalkSafeAdminToken" not in schemes
    assert schemes["WalkSafeAdminAppKind"]["x-walksafe-required-value"] == ADMIN_APP_KIND
    assert schemes["WalkSafeAdminRole"]["x-walksafe-required-value"] == ADMIN_ROLE
    assert schemes["WalkSafeAdminAudience"]["x-walksafe-required-value"] == ADMIN_AUDIENCE
    protected = schema["paths"]["/reports/export"]["get"]["security"][0]
    assert set(protected) == {
        "WalkSafeAdminBearer",
        "WalkSafeAdminAppKind",
        "WalkSafeAdminRole",
        "WalkSafeAdminAudience",
        "WalkSafeAdminDeviceId",
    }
    high_risk_operations = [
        schema["paths"]["/reports/export"]["get"],
        schema["paths"]["/reports/{report_id}/status"]["patch"],
    ]
    for operation in high_risk_operations:
        reconfirmation_parameters = [
            parameter
            for parameter in operation.get("parameters", [])
            if parameter.get("in") == "header"
            and parameter.get("name") == ADMIN_RECONFIRM_NONCE_HEADER_NAME
        ]
        assert len(reconfirmation_parameters) == 1
        assert reconfirmation_parameters[0]["required"] is True
        assert reconfirmation_parameters[0]["schema"] == {
            "type": "string",
            "minLength": 22,
            "maxLength": 22,
            "pattern": "^[A-Za-z0-9_-]{22}$",
        }
    public_login = schema["paths"]["/admin/security/sessions"]["post"]
    assert set(public_login["security"][0]) == set(protected) - {
        "WalkSafeAdminBearer"
    }
    assert public_login["x-walksafe-device-header-must-match-body"] is True
    standard_admin = schema["paths"]["/admin/security/state"]["get"]
    for operation in (public_login, standard_admin):
        assert all(
            parameter.get("name") != ADMIN_RECONFIRM_NONCE_HEADER_NAME
            for parameter in operation.get("parameters", [])
        )
    assert schema["paths"]["/health"]["get"]["security"] == [
        {"WalkSafeFieldToken": []}
    ]


def test_disabled_openapi_preserves_legacy_static_admin_scheme_only() -> None:
    settings = _settings()
    settings.admin_security_enabled = False
    app = FastAPI()
    app.include_router(reports_api.create_router(settings, SimpleNamespace()))

    @app.post("/admin/security/sessions")
    def login():
        return {}

    install_walksafe_openapi_contract(app, settings)
    schema = app.openapi()
    schemes = schema["components"]["securitySchemes"]

    assert "WalkSafeAdminToken" in schemes
    assert "WalkSafeAdminBearer" not in schemes
    assert schema["paths"]["/reports/export"]["get"]["security"] == [
        {
            "WalkSafeAdminToken": [],
            "WalkSafeActorId": [],
            "WalkSafeActorAssertion": [],
        }
    ]
    assert all(
        parameter.get("name") != ADMIN_RECONFIRM_NONCE_HEADER_NAME
        for parameter in schema["paths"]["/reports/export"]["get"].get(
            "parameters",
            [],
        )
    )
    assert schema["paths"]["/admin/security/sessions"]["post"]["security"] == []


def test_admin_security_seed_requires_160_bits(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_ADMIN_ID", "walksafe.admin")
    monkeypatch.setenv("WALKSAFE_ADMIN_TOTP_SECRET", "A" * 31)

    with pytest.raises(ValueError, match="at least 20 decoded bytes"):
        Settings()

    monkeypatch.setenv("WALKSAFE_ADMIN_TOTP_SECRET", "A" * 32)
    assert Settings().admin_security_enabled is True

    monkeypatch.setenv("WALKSAFE_ADMIN_TOTP_SECRET", "A" * 32 + "=")
    with pytest.raises(ValueError, match="canonical unpadded Base32"):
        Settings()


def test_deployment_requires_database_backed_admin_security(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    monkeypatch.setenv("UPLOAD_DIR", str(upload_dir.resolve()))
    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://walksafe:test@127.0.0.1/walksafe")
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "field")
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "a" * 40)
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", "field-token-for-admin-security-tests")
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", "static-admin-token-for-security-tests")
    monkeypatch.setenv("WALKSAFE_GATEWAY_SESSION_SECRET", "gateway-session-secret-for-tests-1234567890")
    monkeypatch.setenv("WALKSAFE_ACTOR_RATE_LIMIT_STORE", "postgresql")
    monkeypatch.setenv("TMAP_APP_KEY", "test-tmap-key")
    monkeypatch.setattr(Settings, "_validate_deployment_detector", lambda self: None)
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "false")

    with pytest.raises(ValueError, match="ADMIN_SECURITY_ENABLED must be true"):
        Settings()

    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_ADMIN_ID", "walksafe.admin")
    monkeypatch.setenv("WALKSAFE_ADMIN_TOTP_SECRET", "A" * 32)
    assert Settings().admin_security_enabled is True


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_admin_security_full_recovery_and_high_risk_path(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
    engine = create_engine(database_url, pool_pre_ping=True)
    with engine.connect() as connection:
        migrated = connection.execute(
            text("SELECT to_regclass('public.admin_security_controls')")
        ).scalar_one()
        def include_admin_security_objects(obj, name, type_, reflected, compare_to):
            del reflected, compare_to
            if type_ == "table":
                return name.startswith("admin_security_")
            table = getattr(obj, "table", None)
            return bool(
                table is not None and table.name.startswith("admin_security_")
            )

        migration_context = MigrationContext.configure(
            connection,
            opts={"include_object": include_admin_security_objects},
        )
        admin_schema_differences = compare_metadata(
            migration_context,
            Base.metadata,
        )
    assert migrated is not None, (
        "apply `python -m alembic -c backend/alembic.ini upgrade head` "
        "to WALKSAFE_TEST_DATABASE_URL before running integration tests"
    )
    assert admin_schema_differences == []

    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)

    def wait_for_postgres_blockers(
        blocked_backend_pid: int,
        *,
        timeout_seconds: float = 10.0,
    ) -> list[int]:
        deadline = monotonic() + timeout_seconds
        poll_wait = Event()
        with SessionFactory() as observer:
            while monotonic() < deadline:
                blockers = observer.execute(
                    text("SELECT pg_blocking_pids(:blocked_backend_pid)"),
                    {"blocked_backend_pid": blocked_backend_pid},
                ).scalar_one()
                if blockers:
                    return [int(blocker) for blocker in blockers]
                poll_wait.wait(0.01)
        raise AssertionError(
            f"PostgreSQL backend {blocked_backend_pid} did not block"
        )

    monkeypatch.setattr(database_api, "SessionLocal", SessionFactory)
    initial_secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    replacement_secret = "KRSXG5DSNFXGOIDBNZQW2ZLOMRSXG5DS"
    recovery_code = "RECOVERY-2026-ALPHA-0001"
    settings = SimpleNamespace(
        admin_id="walksafe.admin",
        admin_totp_secret=initial_secret,
        admin_session_ttl_seconds=43_200,
        admin_step_up_ttl_seconds=300,
        admin_recovery_ttl_seconds=900,
        admin_auth_rate_limit_attempts=5,
        admin_auth_rate_limit_window_seconds=300,
    )
    started_at = datetime(2026, 7, 22, 12, 0, 0, tzinfo=timezone.utc)

    with SessionFactory() as db:
        provision_admin_security(
            db,
            admin_id=settings.admin_id,
            password="initial correct horse",
            totp_secret=initial_secret,
            recovery_codes=[recovery_code],
            now=started_at,
        )

    first_code = pyotp.TOTP(initial_secret).at(started_at)
    login_barrier = Barrier(2)

    def concurrent_login():
        with SessionFactory() as db:
            login_barrier.wait(timeout=10)
            try:
                return AdminSecurityService(db, settings).login(
                    admin_id=settings.admin_id,
                    password="initial correct horse",
                    totp_code=first_code,
                    device_id="android-device-0001",
                    device_label="첫 번째 휴대전화",
                    source="198.51.100.10",
                    now=started_at,
                )
            except AdminSecurityError as exc:
                return exc

    with ThreadPoolExecutor(max_workers=2) as executor:
        login_futures = [executor.submit(concurrent_login) for _ in range(2)]
        login_results = [future.result(timeout=15) for future in login_futures]

    login_grants = [
        result for result in login_results if isinstance(result, SessionGrant)
    ]
    login_errors = [
        result for result in login_results if isinstance(result, AdminSecurityError)
    ]
    assert len(login_grants) == 1
    assert len(login_errors) == 1
    assert login_errors[0].code == "admin_totp_replayed"
    first_grant = login_grants[0]

    second_time = started_at + timedelta(seconds=30)
    with SessionFactory() as db:
        second_grant = AdminSecurityService(db, settings).login(
            admin_id=settings.admin_id,
            password="initial correct horse",
            totp_code=pyotp.TOTP(initial_secret).at(second_time),
            device_id="android-device-0002",
            device_label="두 번째 휴대전화",
            source="198.51.100.10",
            now=second_time,
        )
    with SessionFactory() as db:
        second_identity = authorize_admin_bearer(
            db,
            second_grant.access_token,
            runtime_totp_secret=initial_secret,
            device_id="android-device-0002",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=second_time,
        )
        db.commit()
    reauthentication_time = second_time + timedelta(seconds=30)
    for _ in range(settings.admin_auth_rate_limit_attempts):
        with SessionFactory() as db, pytest.raises(AdminSecurityError) as failed_reauth:
            AdminSecurityService(db, settings).reauthenticate(
                second_identity,
                password="wrong replacement password",
                totp_code=pyotp.TOTP(initial_secret).at(reauthentication_time),
                action="report.export",
                method="GET",
                path="/reports/export",
                nonce=RECONFIRM_NONCE,
                source="198.51.100.12",
                now=reauthentication_time,
            )
        assert failed_reauth.value.code == "admin_reauthentication_failed"
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as limited_reauth:
        AdminSecurityService(db, settings).reauthenticate(
            second_identity,
            password="initial correct horse",
            totp_code=pyotp.TOTP(initial_secret).at(reauthentication_time),
            action="report.export",
            method="GET",
            path="/reports/export",
            nonce=RECONFIRM_NONCE,
            source="198.51.100.12",
            now=reauthentication_time,
        )
    assert limited_reauth.value.code == "admin_auth_rate_limited"
    with SessionFactory() as db:
        AdminSecurityService(db, settings).revoke_session(
            second_identity,
            uuid.UUID(first_grant.current_session_id),
            now=second_time,
        )
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as revoked_error:
        authorize_admin_bearer(
            db,
            first_grant.access_token,
            runtime_totp_secret=initial_secret,
            device_id="android-device-0001",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=second_time,
        )
    assert revoked_error.value.code == "admin_session_invalid"

    recovery_time = second_time + timedelta(seconds=30)
    recovery_locked = Event()
    allow_recovery_commit = Event()
    revoke_started = Event()
    recovery_backend_pids: list[int] = []
    revoke_backend_pids: list[int] = []

    def start_recovery_while_holding_locks():
        with SessionFactory() as db:
            recovery_backend_pids.append(
                int(db.execute(text("SELECT pg_backend_pid()")).scalar_one())
            )
            service = AdminSecurityService(db, settings)
            original_audit = service._audit

            def blocking_audit(action, outcome, **kwargs):
                original_audit(action, outcome, **kwargs)
                if action == "recovery.start" and outcome == "SUCCESS":
                    recovery_locked.set()
                    if not allow_recovery_commit.wait(timeout=10):
                        raise AssertionError("timed out holding recovery transaction")

            service._audit = blocking_audit
            return service.start_recovery(
                admin_id=settings.admin_id,
                recovery_code=recovery_code,
                device_id="android-device-0003",
                device_label="복구 휴대전화",
                source="198.51.100.11",
                now=recovery_time,
            )

    def revoke_during_recovery():
        with SessionFactory() as db:
            revoke_backend_pids.append(
                int(db.execute(text("SELECT pg_backend_pid()")).scalar_one())
            )
            revoke_started.set()
            try:
                return AdminSecurityService(db, settings).revoke_session(
                    second_identity,
                    second_identity.session_id,
                    now=recovery_time,
                )
            except AdminSecurityError as exc:
                return exc

    with ThreadPoolExecutor(max_workers=2) as executor:
        recovery_future = executor.submit(start_recovery_while_holding_locks)
        assert recovery_locked.wait(timeout=10)
        revoke_future = executor.submit(revoke_during_recovery)
        try:
            assert revoke_started.wait(timeout=10)
            revoke_blockers = wait_for_postgres_blockers(revoke_backend_pids[0])
            assert recovery_backend_pids[0] in revoke_blockers
        finally:
            allow_recovery_commit.set()
        recovery_grant = recovery_future.result(timeout=15)
        recovery_revoke_result = revoke_future.result(timeout=15)

    assert isinstance(recovery_grant, RecoveryGrant)
    assert isinstance(recovery_revoke_result, AdminSecurityError)
    assert (
        recovery_revoke_result.code
        == "admin_security_state_blocks_operation"
    )
    with SessionFactory() as db:
        pending_code = db.execute(select(AdminSecurityRecoveryCode)).scalar_one()
        first_transaction = db.execute(
            select(AdminSecurityRecoveryTransaction)
        ).scalar_one()
        original_transaction_id = first_transaction.id
        original_expiry = first_transaction.expires_at
    assert pending_code.used_at is None

    with SessionFactory() as db:
        resumed_recovery = AdminSecurityService(db, settings).start_recovery(
            admin_id=settings.admin_id,
            recovery_code=recovery_code,
            device_id="android-device-0003",
            device_label="복구 휴대전화",
            source="198.51.100.11",
            now=recovery_time + timedelta(seconds=1),
        )
    assert resumed_recovery.recovery_token != recovery_grant.recovery_token
    with SessionFactory() as db:
        resumed_transactions = db.execute(
            select(AdminSecurityRecoveryTransaction)
        ).scalars().all()
        still_pending_code = db.execute(
            select(AdminSecurityRecoveryCode)
        ).scalar_one()
    assert len(resumed_transactions) == 1
    assert resumed_transactions[0].id == original_transaction_id
    assert resumed_transactions[0].expires_at == original_expiry
    assert resumed_transactions[0].recovery_token_sha256 == sha256_text(
        resumed_recovery.recovery_token
    )
    assert still_pending_code.used_at is None
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as frozen_error:
        authorize_admin_bearer(
            db,
            second_grant.access_token,
            runtime_totp_secret=initial_secret,
            device_id="android-device-0002",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=recovery_time,
        )
    assert frozen_error.value.code == "admin_session_invalid"

    restart_time = original_expiry + timedelta(seconds=1)
    with SessionFactory() as db:
        restarted_recovery = AdminSecurityService(db, settings).start_recovery(
            admin_id=settings.admin_id,
            recovery_code=recovery_code,
            device_id="android-device-0003",
            device_label="복구 휴대전화",
            source="198.51.100.11",
            now=restart_time,
        )
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as late_expired:
        AdminSecurityService(db, settings).complete_recovery(
            recovery_token=resumed_recovery.recovery_token,
            new_password="replacement correct horse",
            totp_code=pyotp.TOTP(initial_secret).at(restart_time),
            device_id="android-device-0003",
            device_label="복구 휴대전화",
            source="198.51.100.11",
            now=restart_time,
        )
    assert late_expired.value.code == "admin_recovery_expired"
    with SessionFactory() as db:
        recovery_control = db.execute(select(AdminSecurityControl)).scalar_one()
        active_transactions = db.execute(
            select(AdminSecurityRecoveryTransaction).where(
                AdminSecurityRecoveryTransaction.completed_at.is_(None),
                AdminSecurityRecoveryTransaction.expires_at > restart_time,
            )
        ).scalars().all()
    assert recovery_control.security_state == "RECOVERY_IN_PROGRESS"
    assert len(active_transactions) == 1
    assert active_transactions[0].recovery_token_sha256 == sha256_text(
        restarted_recovery.recovery_token
    )

    complete_time = restart_time + timedelta(seconds=30)
    settings.admin_totp_secret = replacement_secret
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as rotated_token_error:
        AdminSecurityService(db, settings).complete_recovery(
            recovery_token=recovery_grant.recovery_token,
            new_password="replacement correct horse",
            totp_code=pyotp.TOTP(replacement_secret).at(complete_time),
            device_id="android-device-0003",
            device_label="복구 휴대전화",
            source="198.51.100.11",
            now=complete_time,
        )
    assert rotated_token_error.value.code == "admin_recovery_invalid"

    for _ in range(settings.admin_auth_rate_limit_attempts):
        with SessionFactory() as db, pytest.raises(AdminSecurityError) as failed_factor:
            AdminSecurityService(db, settings).complete_recovery(
                recovery_token=restarted_recovery.recovery_token,
                new_password="replacement correct horse",
                totp_code="000000",
                device_id="android-device-0003",
                device_label="복구 휴대전화",
                source="198.51.100.99",
                now=complete_time,
            )
        assert failed_factor.value.code == "admin_recovery_verification_failed"
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as limited_factor:
        AdminSecurityService(db, settings).complete_recovery(
            recovery_token=restarted_recovery.recovery_token,
            new_password="replacement correct horse",
            totp_code="000000",
            device_id="android-device-0003",
            device_label="복구 휴대전화",
            source="198.51.100.99",
            now=complete_time,
        )
    assert limited_factor.value.code == "admin_auth_rate_limited"

    complete_barrier = Barrier(2)

    def concurrent_complete_recovery():
        with SessionFactory() as db:
            complete_barrier.wait(timeout=10)
            try:
                return AdminSecurityService(db, settings).complete_recovery(
                    recovery_token=restarted_recovery.recovery_token,
                    new_password="replacement correct horse",
                    totp_code=pyotp.TOTP(replacement_secret).at(complete_time),
                    device_id="android-device-0003",
                    device_label="복구 휴대전화",
                    source="198.51.100.11",
                    now=complete_time,
                )
            except AdminSecurityError as exc:
                return exc

    with ThreadPoolExecutor(max_workers=2) as executor:
        complete_futures = [
            executor.submit(concurrent_complete_recovery) for _ in range(2)
        ]
        complete_results = [
            future.result(timeout=15) for future in complete_futures
        ]

    complete_grants = [
        result for result in complete_results if isinstance(result, SessionGrant)
    ]
    complete_errors = [
        result
        for result in complete_results
        if isinstance(result, AdminSecurityError)
    ]
    assert len(complete_grants) == 1
    assert len(complete_errors) == 1
    assert complete_errors[0].code == "admin_recovery_invalid"
    recovered = complete_grants[0]
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as stale_replica:
        authorize_admin_bearer(
            db,
            recovered.access_token,
            runtime_totp_secret=initial_secret,
            device_id="android-device-0003",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=complete_time,
        )
    assert stale_replica.value.code == "admin_totp_configuration_mismatch"
    high_risk_time = complete_time + timedelta(seconds=30)
    high_risk_nonce = "cmVjb3ZlcmVkLXJlcG9ydA"
    with SessionFactory() as db:
        recovered_identity = authorize_admin_bearer(
            db,
            recovered.access_token,
            runtime_totp_secret=replacement_secret,
            device_id="android-device-0003",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=high_risk_time,
        )
        reconfirmation = AdminSecurityService(db, settings).reauthenticate(
            recovered_identity,
            password="replacement correct horse",
            totp_code=pyotp.TOTP(replacement_secret).at(high_risk_time),
            action="report.export",
            method="GET",
            path="/reports/export",
            nonce=high_risk_nonce,
            source="198.51.100.11",
            now=high_risk_time,
        )
    assert reconfirmation.action == "report.export"

    consumed_identity = consume_admin_high_risk_reconfirmation(
        recovered.access_token,
        "report.export",
        runtime_totp_secret=replacement_secret,
        now=high_risk_time,
        method="GET",
        path="/reports/export",
        nonce=high_risk_nonce,
        device_id="android-device-0003",
        app_kind=ADMIN_APP_KIND,
        role=ADMIN_ROLE,
        audience=ADMIN_AUDIENCE,
    )
    with SessionFactory() as db:
        consumed_reconfirmation = db.execute(
            select(AdminSecurityReconfirmation).where(
                AdminSecurityReconfirmation.nonce_sha256
                == sha256_text(high_risk_nonce)
            )
        ).scalar_one()
        successful_audit = db.execute(
            select(AdminSecurityAudit)
            .where(
                AdminSecurityAudit.action == "report.export",
                AdminSecurityAudit.outcome == "SUCCESS",
            )
            .order_by(AdminSecurityAudit.sequence.desc())
        ).scalars().first()
    assert consumed_identity.session_id == uuid.UUID(recovered.current_session_id)
    assert consumed_reconfirmation.consumed_at == high_risk_time
    assert successful_audit is not None

    with SessionFactory() as db:
        high_risk_identity = authorize_admin_protected_work(
            db,
            recovered.access_token,
            "report.export",
            runtime_totp_secret=replacement_secret,
            now=high_risk_time,
            method="GET",
            path="/reports/export",
            device_id="android-device-0003",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
        )
        db.commit()
        control = db.execute(select(AdminSecurityControl)).scalar_one()
        complete_attempts = db.execute(
            select(AdminSecurityAuthAttempt).where(
                AdminSecurityAuthAttempt.action == "recovery_complete"
            )
        ).scalars().all()
        consumed_code = db.execute(select(AdminSecurityRecoveryCode)).scalar_one()

    with pytest.raises(AdminSecurityError) as reused_reconfirmation:
        consume_admin_high_risk_reconfirmation(
            recovered.access_token,
            "report.export",
            runtime_totp_secret=replacement_secret,
            now=high_risk_time,
            method="GET",
            path="/reports/export",
            nonce=high_risk_nonce,
            device_id="android-device-0003",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
        )
    assert reused_reconfirmation.value.code == "admin_reconfirmation_required"

    race_time = high_risk_time + timedelta(seconds=30)
    race_nonce = "ZmVkY2JhOTg3NjU0MzIxMA"
    with SessionFactory() as db:
        race_identity = authorize_admin_bearer(
            db,
            recovered.access_token,
            runtime_totp_secret=replacement_secret,
            device_id="android-device-0003",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=race_time,
        )
        AdminSecurityService(db, settings).reauthenticate(
            race_identity,
            password="replacement correct horse",
            totp_code=pyotp.TOTP(replacement_secret).at(race_time),
            action="report.export",
            method="GET",
            path="/reports/export",
            nonce=race_nonce,
            source="198.51.100.11",
            now=race_time,
        )
    consume_barrier = Barrier(2)

    def concurrent_consume():
        consume_barrier.wait(timeout=10)
        try:
            return consume_admin_high_risk_reconfirmation(
                recovered.access_token,
                "report.export",
                runtime_totp_secret=replacement_secret,
                now=race_time,
                method="GET",
                path="/reports/export",
                nonce=race_nonce,
                device_id="android-device-0003",
                app_kind=ADMIN_APP_KIND,
                role=ADMIN_ROLE,
                audience=ADMIN_AUDIENCE,
            )
        except AdminSecurityError as exc:
            return exc

    with ThreadPoolExecutor(max_workers=2) as executor:
        consume_futures = [executor.submit(concurrent_consume) for _ in range(2)]
        consume_results = [
            future.result(timeout=15) for future in consume_futures
        ]

    consumed_identities = [
        result
        for result in consume_results
        if isinstance(result, AdminSessionIdentity)
    ]
    consume_errors = [
        result
        for result in consume_results
        if isinstance(result, AdminSecurityError)
    ]
    assert len(consumed_identities) == 1
    assert len(consume_errors) == 1
    assert consume_errors[0].code == "admin_reconfirmation_required"
    linearization_time = race_time + timedelta(seconds=30)
    recovered_session_id = uuid.UUID(recovered.current_session_id)
    with SessionFactory() as db:
        original_device_label = db.execute(
            select(AdminSecuritySession.device_label).where(
                AdminSecuritySession.id == recovered_session_id
            )
        ).scalar_one()

    recovery_first_locked = Event()
    allow_recovery_first_commit = Event()
    protected_after_recovery_started = Event()
    recovery_first_backend_pids: list[int] = []
    protected_after_recovery_backend_pids: list[int] = []
    blocked_protected_mutations: list[bool] = []

    def commit_recovery_transition_while_holding_control():
        with SessionFactory() as db:
            recovery_first_backend_pids.append(
                int(db.execute(text("SELECT pg_backend_pid()")).scalar_one())
            )
            locked_control = db.execute(
                select(AdminSecurityControl).with_for_update()
            ).scalar_one()
            locked_session = db.execute(
                select(AdminSecuritySession)
                .where(AdminSecuritySession.id == recovered_session_id)
                .with_for_update()
            ).scalar_one()
            locked_control.security_state = "RECOVERY_IN_PROGRESS"
            locked_control.state_version += 1
            locked_session.revoked_at = linearization_time
            locked_session.revoked_reason = "recovery_started"
            db.flush()
            recovery_first_locked.set()
            if not allow_recovery_first_commit.wait(timeout=10):
                raise AssertionError("timed out holding recovery-first transaction")
            db.commit()

    def authorize_protected_work_after_recovery_started():
        with SessionFactory() as db:
            protected_after_recovery_backend_pids.append(
                int(db.execute(text("SELECT pg_backend_pid()")).scalar_one())
            )
            protected_after_recovery_started.set()
            try:
                authorize_admin_protected_work(
                    db,
                    recovered.access_token,
                    "report.export",
                    runtime_totp_secret=replacement_secret,
                    now=linearization_time,
                    method="GET",
                    path="/reports/export",
                    device_id="android-device-0003",
                    app_kind=ADMIN_APP_KIND,
                    role=ADMIN_ROLE,
                    audience=ADMIN_AUDIENCE,
                )
                blocked_protected_mutations.append(True)
                locked_session = db.get(
                    AdminSecuritySession,
                    recovered_session_id,
                )
                locked_session.device_label = "unexpected protected mutation"
                db.commit()
                return None
            except AdminSecurityError as exc:
                db.rollback()
                return exc

    with ThreadPoolExecutor(max_workers=2) as executor:
        recovery_first_future = executor.submit(
            commit_recovery_transition_while_holding_control
        )
        assert recovery_first_locked.wait(timeout=10)
        protected_after_recovery_future = executor.submit(
            authorize_protected_work_after_recovery_started
        )
        try:
            assert protected_after_recovery_started.wait(timeout=10)
            protected_blockers = wait_for_postgres_blockers(
                protected_after_recovery_backend_pids[0]
            )
            assert recovery_first_backend_pids[0] in protected_blockers
        finally:
            allow_recovery_first_commit.set()
        recovery_first_future.result(timeout=15)
        recovery_first_result = protected_after_recovery_future.result(timeout=15)

    assert isinstance(recovery_first_result, AdminSecurityError)
    assert (
        recovery_first_result.code
        == "admin_security_state_blocks_operation"
    )
    assert blocked_protected_mutations == []
    with SessionFactory() as db:
        recovery_first_control = db.execute(
            select(AdminSecurityControl).with_for_update()
        ).scalar_one()
        recovery_first_session = db.execute(
            select(AdminSecuritySession)
            .where(AdminSecuritySession.id == recovered_session_id)
            .with_for_update()
        ).scalar_one()
        assert recovery_first_control.security_state == "RECOVERY_IN_PROGRESS"
        assert recovery_first_session.revoked_at == linearization_time
        assert recovery_first_session.device_label == original_device_label
        recovery_first_control.security_state = "NORMAL"
        recovery_first_control.state_version += 1
        recovery_first_session.revoked_at = None
        recovery_first_session.revoked_reason = None
        db.commit()

    protected_first_locked = Event()
    allow_protected_first_commit = Event()
    recovery_after_protected_started = Event()
    protected_first_backend_pids: list[int] = []
    recovery_after_protected_backend_pids: list[int] = []
    protected_linearized_label = "protected work linearized first"

    def commit_protected_work_while_holding_control():
        with SessionFactory() as db:
            protected_first_backend_pids.append(
                int(db.execute(text("SELECT pg_backend_pid()")).scalar_one())
            )
            identity = authorize_admin_protected_work(
                db,
                recovered.access_token,
                "report.export",
                runtime_totp_secret=replacement_secret,
                now=linearization_time,
                method="GET",
                path="/reports/export",
                device_id="android-device-0003",
                app_kind=ADMIN_APP_KIND,
                role=ADMIN_ROLE,
                audience=ADMIN_AUDIENCE,
            )
            locked_session = db.get(AdminSecuritySession, recovered_session_id)
            locked_session.device_label = protected_linearized_label
            db.flush()
            protected_first_locked.set()
            if not allow_protected_first_commit.wait(timeout=10):
                raise AssertionError("timed out holding protected-first transaction")
            db.commit()
            return identity

    def commit_recovery_after_protected_work():
        with SessionFactory() as db:
            recovery_after_protected_backend_pids.append(
                int(db.execute(text("SELECT pg_backend_pid()")).scalar_one())
            )
            recovery_after_protected_started.set()
            locked_control = db.execute(
                select(AdminSecurityControl).with_for_update()
            ).scalar_one()
            locked_session = db.execute(
                select(AdminSecuritySession)
                .where(AdminSecuritySession.id == recovered_session_id)
                .with_for_update()
            ).scalar_one()
            locked_control.security_state = "RECOVERY_IN_PROGRESS"
            locked_control.state_version += 1
            locked_session.revoked_at = linearization_time
            locked_session.revoked_reason = "recovery_started"
            db.commit()

    with ThreadPoolExecutor(max_workers=2) as executor:
        protected_first_future = executor.submit(
            commit_protected_work_while_holding_control
        )
        assert protected_first_locked.wait(timeout=10)
        recovery_after_protected_future = executor.submit(
            commit_recovery_after_protected_work
        )
        try:
            assert recovery_after_protected_started.wait(timeout=10)
            recovery_blockers = wait_for_postgres_blockers(
                recovery_after_protected_backend_pids[0]
            )
            assert protected_first_backend_pids[0] in recovery_blockers
        finally:
            allow_protected_first_commit.set()
        protected_first_identity = protected_first_future.result(timeout=15)
        recovery_after_protected_future.result(timeout=15)

    assert protected_first_identity.session_id == recovered_session_id
    with SessionFactory() as db:
        protected_first_control = db.execute(
            select(AdminSecurityControl).with_for_update()
        ).scalar_one()
        protected_first_session = db.execute(
            select(AdminSecuritySession)
            .where(AdminSecuritySession.id == recovered_session_id)
            .with_for_update()
        ).scalar_one()
        assert protected_first_control.security_state == "RECOVERY_IN_PROGRESS"
        assert protected_first_session.revoked_at == linearization_time
        assert protected_first_session.device_label == protected_linearized_label
        protected_first_control.security_state = "NORMAL"
        protected_first_control.state_version += 1
        protected_first_session.revoked_at = None
        protected_first_session.revoked_reason = None
        protected_first_session.device_label = original_device_label
        db.commit()

    assert high_risk_identity.session_id == uuid.UUID(recovered.current_session_id)
    assert control.security_state == "NORMAL"
    assert len(complete_attempts) == 10
    assert sum(attempt.success for attempt in complete_attempts) == 1
    assert consumed_code.used_at == complete_time
    engine.dispose()
