from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import base64
import hashlib
import importlib
import json
import os
from pathlib import Path
from threading import Barrier, Event
from time import monotonic
from types import SimpleNamespace
import uuid

from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory
from fastapi import FastAPI, HTTPException, Request
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
import pyotp
import pytest
from sqlalchemy import UniqueConstraint, create_engine, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import sessionmaker

from conftest import _ADMIN_SECURITY_CLEANUP_TABLES


def _repository_alembic_head() -> str:
    config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    head = ScriptDirectory.from_config(config).get_current_head()
    assert head is not None
    return head
from backend.app import database as database_api
from backend.app import field_test_security as field_test_security_api
from backend.app.api import admin_security as admin_security_api
from backend.app.api import reports as reports_api
import backend.app.config as config_module
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
    assert_high_risk_operation_allowed,
    auth_rate_limit_principal,
    authorize_admin_bearer,
    authorize_high_risk_bearer,
    authorize_admin_protected_work,
    authorize_database_bound_high_risk_bearer,
    consume_admin_high_risk_reconfirmation,
    hash_password,
    provision_admin_security,
    sha256_text,
    valid_recovery_custody_reference,
    valid_reconfirmation_nonce,
    verify_password,
    verify_totp_timecode,
)
from backend.app.services.admin_device_proof import (
    AdminDeviceProofService,
    admin_device_key_marker,
    canonical_admin_query_sha256,
    provision_admin_device_key,
    raw_body_sha256,
    verify_admin_device_proof,
)
from backend.app.models import (
    AdminDeviceKey,
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
RECOVERY_CUSTODY_REFERENCE = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"
ROTATED_CUSTODY_REFERENCE = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA"
VALID_HEADERS = {
    "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
    "X-WalkSafe-Role": ADMIN_ROLE,
    "X-WalkSafe-Audience": ADMIN_AUDIENCE,
    "X-WalkSafe-Device-Id": DEVICE_ID,
}


def _settings() -> SimpleNamespace:
    return SimpleNamespace(
        admin_security_enabled=True,
        admin_totp_secret="JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
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


class _ServiceResult:
    def __init__(self, *, one=None, values=None) -> None:
        self.one = one
        self.values = [] if values is None else values

    def scalar_one_or_none(self):
        return self.one

    def scalars(self):
        return self

    def all(self):
        return list(self.values)


class _ServiceDatabase:
    def __init__(self, results) -> None:
        self.results = list(results)
        self.commits = 0
        self.rollbacks = 0

    def execute(self, _statement, _parameters=None):
        return self.results.pop(0)

    def commit(self) -> None:
        self.commits += 1

    def rollback(self) -> None:
        self.rollbacks += 1


def _security_control(*, custody_state: str = "UNATTESTED") -> SimpleNamespace:
    attested = custody_state == "ATTESTED"
    return SimpleNamespace(
        admin_id="walksafe.admin",
        security_state="NORMAL",
        state_version=7,
        recovery_custody_state=custody_state,
        recovery_custody_attested_at=(
            datetime(2026, 7, 22, 12, 0, 0, tzinfo=timezone.utc)
            if attested
            else None
        ),
        recovery_custody_reference_sha256="a" * 64 if attested else None,
        recovery_custody_material_kind="RECOVERY_CODE" if attested else None,
        recovery_custody_storage_location="OFF_PHONE" if attested else None,
        recovery_custody_separate_backup_confirmed=attested,
    )


def _caller_session() -> SimpleNamespace:
    identity = _identity()
    return SimpleNamespace(
        id=identity.session_id,
        admin_id=identity.admin_id,
        device_id=identity.device_id,
        expires_at=identity.expires_at,
        revoked_at=None,
        revoked_reason=None,
    )


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_storage_cleanup_is_repeatable_and_restores_append_only_trigger(
    clean_test_storage,
) -> None:
    database_url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
    cleanup_engine = create_engine(database_url, pool_pre_ping=True)
    try:
        with cleanup_engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO admin_security_controls "
                    "(admin_id, password_hash, totp_secret_fingerprint, "
                    "security_state, state_version) VALUES "
                    "('cleanup-regression-admin', 'test-hash', :fingerprint, "
                    "'NORMAL', 1)"
                ),
                {"fingerprint": "a" * 64},
            )
            connection.execute(
                text(
                    "INSERT INTO walksafe_recovery_custody_capabilities "
                    "(admin_id, totp_secret_fingerprint, issuer_key_sha256) "
                    "VALUES ('cleanup-regression-admin', :fingerprint, "
                    ":issuer_key_sha256)"
                ),
                {"fingerprint": "a" * 64, "issuer_key_sha256": "c" * 64},
            )
            connection.execute(
                text(
                    "INSERT INTO admin_security_audits "
                    "(id, sequence, previous_entry_sha256, entry_sha256, "
                    "admin_id, action, outcome, details) VALUES "
                    "('11111111-1111-4111-8111-111111111111', 1, NULL, "
                    ":entry_sha256, 'cleanup-regression-admin', "
                    "'security.provision', 'SUCCESS', '{}')"
                ),
                {"entry_sha256": "b" * 64},
            )

        assert clean_test_storage is not None
        clean_test_storage()
        clean_test_storage()

        with cleanup_engine.connect() as connection:
            counts = connection.execute(
                text(
                    "SELECT "
                    "(SELECT count(*) FROM admin_security_controls), "
                    "(SELECT count(*) FROM "
                    "walksafe_recovery_custody_capabilities), "
                    "(SELECT count(*) FROM admin_security_audits)"
                )
            ).one()
            trigger_state = connection.execute(
                text(
                    "SELECT tgenabled FROM pg_trigger "
                    "WHERE tgrelid = "
                    "'public.admin_security_audits'::regclass "
                    "AND tgname = 'admin_security_audits_append_only'"
                )
            ).scalar_one()

        assert tuple(counts) == (0, 0, 0)
        assert trigger_state == "O"
        with cleanup_engine.connect() as connection:
            with pytest.raises(SQLAlchemyError, match="append-only"):
                connection.execute(text("TRUNCATE TABLE admin_security_audits"))
            connection.rollback()
    finally:
        cleanup_engine.dispose()


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
        self.custody_calls: list[dict[str, object]] = []
        self.lost_device_calls: list[tuple[AdminSessionIdentity, str]] = []

    def login(self, **kwargs):
        self.login_calls.append(kwargs)
        return SessionGrant("opaque-login-token", "NORMAL", str(_identity().session_id))

    def get_state(self, identity):
        assert identity == _identity()
        return {
            "security_state": "NORMAL",
            "state_version": "7",
            "observed_at": "2026-07-22T12:00:00+00:00",
            "recovery_custody_state": "UNATTESTED",
            "recovery_custody_attested_at": None,
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

    def list_active_devices(self, identity):
        assert identity == _identity()
        return [
            {"device_id": identity.device_id, "current": True},
            {"device_id": "android-device-0002", "current": False},
        ]

    def revoke_session(self, identity, session_id):
        assert identity.session_id == session_id
        return {
            "security_state": "NORMAL",
            "state_version": "7",
            "observed_at": "2026-07-22T12:00:00+00:00",
            "recovery_custody_state": "UNATTESTED",
            "recovery_custody_attested_at": None,
        }

    def attest_recovery_custody(self, identity, **kwargs):
        assert identity == _identity()
        self.custody_calls.append(kwargs)
        return {
            "security_state": "NORMAL",
            "state_version": "8",
            "observed_at": "2026-07-22T12:01:00+00:00",
            "recovery_custody_state": "ATTESTED",
            "recovery_custody_attested_at": "2026-07-22T12:01:00+00:00",
        }

    def report_lost_device(self, identity, device_id):
        self.lost_device_calls.append((identity, device_id))
        return {
            "security_state": "NORMAL",
            "state_version": "8",
            "observed_at": "2026-07-22T12:01:00+00:00",
            "recovery_custody_state": "UNATTESTED",
            "recovery_custody_attested_at": None,
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


def test_recovery_custody_reference_requires_canonical_unpadded_base64url_32_bytes() -> None:
    assert len(RECOVERY_CUSTODY_REFERENCE) == 43
    assert valid_recovery_custody_reference(RECOVERY_CUSTODY_REFERENCE) is True

    assert valid_recovery_custody_reference(RECOVERY_CUSTODY_REFERENCE[:-1]) is False
    assert valid_recovery_custody_reference(RECOVERY_CUSTODY_REFERENCE + "A") is False
    assert valid_recovery_custody_reference(RECOVERY_CUSTODY_REFERENCE + "=") is False
    assert valid_recovery_custody_reference(RECOVERY_CUSTODY_REFERENCE[:-1] + "Z") is False


def test_high_risk_guard_requires_consistent_attested_recovery_custody() -> None:
    unattested = _security_control()
    with pytest.raises(AdminSecurityError) as missing:
        assert_high_risk_operation_allowed(
            _ServiceDatabase([_ServiceResult(values=[unattested])]),
            "release.approval",
            "POST",
            "/admin/operations/release-approvals",
            datetime(2026, 7, 22, 12, 1, 0, tzinfo=timezone.utc),
        )
    assert missing.value.code == "admin_recovery_custody_required"
    assert missing.value.status_code == 409

    inconsistent = _security_control(custody_state="ATTESTED")
    inconsistent.recovery_custody_attested_at = None
    with pytest.raises(AdminSecurityStoreUnavailable):
        assert_high_risk_operation_allowed(
            _ServiceDatabase([_ServiceResult(values=[inconsistent])]),
            "data.delete",
            "POST",
            "/admin/operations/data-deletions",
            datetime(2026, 7, 22, 12, 1, 0, tzinfo=timezone.utc),
        )


@pytest.mark.parametrize(
    ("max_age_seconds", "age", "accepted"),
    [
        (60, timedelta(seconds=60), True),
        (60, timedelta(seconds=60, microseconds=1), False),
        (300, timedelta(seconds=300), True),
        (300, timedelta(seconds=300, microseconds=1), False),
    ],
)
def test_high_risk_reconfirmation_enforces_observed_at_age_boundary(
    monkeypatch: pytest.MonkeyPatch,
    max_age_seconds: int,
    age: timedelta,
    accepted: bool,
) -> None:
    observed_at = datetime(2026, 7, 22, 12, 10, 0, tzinfo=timezone.utc)
    reconfirmation = SimpleNamespace(
        verified_at=observed_at - age,
        consumed_at=None,
    )
    db = _ServiceDatabase([_ServiceResult(one=reconfirmation)])
    monkeypatch.setattr(
        "backend.app.services.admin_security.assert_high_risk_operation_allowed",
        lambda *_args, **_kwargs: _security_control(custody_state="ATTESTED"),
    )
    monkeypatch.setattr(
        "backend.app.services.admin_security._authorize_admin_bearer",
        lambda *_args, **_kwargs: _identity(),
    )
    monkeypatch.setattr(
        "backend.app.services.admin_security._append_admin_security_audit",
        lambda *_args, **_kwargs: None,
    )

    if accepted:
        identity = authorize_high_risk_bearer(
            db,
            "opaque-token",
            "data.delete",
            now=observed_at,
            method="POST",
            path="/admin/operations/data-deletions",
            nonce=RECONFIRM_NONCE,
            runtime_totp_secret=_settings().admin_totp_secret,
            device_id=DEVICE_ID,
            max_step_up_age_seconds=max_age_seconds,
        )
        assert identity.session_id == _identity().session_id
        assert reconfirmation.consumed_at == observed_at
    else:
        with pytest.raises(AdminSecurityError) as stale:
            authorize_high_risk_bearer(
                db,
                "opaque-token",
                "data.delete",
                now=observed_at,
                method="POST",
                path="/admin/operations/data-deletions",
                nonce=RECONFIRM_NONCE,
                runtime_totp_secret=_settings().admin_totp_secret,
                device_id=DEVICE_ID,
                max_step_up_age_seconds=max_age_seconds,
            )
        assert stale.value.code == "admin_reconfirmation_required"
        assert reconfirmation.consumed_at is None


def test_custody_attestation_stores_only_digest_and_exact_state() -> None:
    control = _security_control()
    db = _ServiceDatabase(
        [
            _ServiceResult(one=control),
            _ServiceResult(one=_caller_session()),
            _ServiceResult(),
        ]
    )
    service = AdminSecurityService(db, _settings())
    audits: list[dict[str, object]] = []
    service._audit = lambda action, outcome, **kwargs: audits.append(
        {"action": action, "outcome": outcome, **kwargs}
    )
    observed_at = datetime(2026, 7, 22, 12, 1, 0, tzinfo=timezone.utc)

    state = service.attest_recovery_custody(
        _identity(),
        custody_reference=RECOVERY_CUSTODY_REFERENCE,
        material_kind="RECOVERY_CODE",
        storage_location="OFF_PHONE",
        separate_encrypted_backup_confirmed=True,
        now=observed_at,
    )

    assert control.recovery_custody_reference_sha256 == sha256_text(
        RECOVERY_CUSTODY_REFERENCE
    )
    assert RECOVERY_CUSTODY_REFERENCE not in repr(audits)
    assert db.commits == 1
    assert state == {
        "security_state": "NORMAL",
        "state_version": "8",
        "observed_at": observed_at.isoformat(),
        "recovery_custody_state": "ATTESTED",
        "recovery_custody_attested_at": observed_at.isoformat(),
    }


def test_report_lost_device_revokes_only_target_key_and_sessions_in_one_commit() -> None:
    observed_at = datetime(2026, 7, 22, 12, 2, 0, tzinfo=timezone.utc)
    control = _security_control()
    caller = _caller_session()
    target_key = SimpleNamespace(status="ACTIVE", revoked_at=None)
    other_key = SimpleNamespace(status="ACTIVE", revoked_at=None)
    target_session = SimpleNamespace(revoked_at=None, revoked_reason=None)
    db = _ServiceDatabase(
        [
            _ServiceResult(one=control),
            _ServiceResult(one=caller),
            _ServiceResult(values=[target_key]),
            _ServiceResult(values=[target_session]),
        ]
    )
    service = AdminSecurityService(db, _settings())
    service._audit = lambda *_args, **_kwargs: None

    service.report_lost_device(
        _identity(),
        "android-device-0002",
        now=observed_at,
    )

    assert (target_key.status, target_key.revoked_at) == ("REVOKED", observed_at)
    assert (target_session.revoked_at, target_session.revoked_reason) == (
        observed_at,
        "device_reported_lost",
    )
    assert (caller.revoked_at, caller.revoked_reason) == (None, None)
    assert (other_key.status, other_key.revoked_at) == ("ACTIVE", None)
    assert db.commits == 1


def test_current_device_cannot_report_itself_lost() -> None:
    db = _ServiceDatabase(
        [_ServiceResult(one=_security_control()), _ServiceResult(one=_caller_session())]
    )
    service = AdminSecurityService(db, _settings())
    service._audit = lambda *_args, **_kwargs: None

    with pytest.raises(AdminSecurityError) as rejected:
        service.report_lost_device(
            _identity(),
            DEVICE_ID,
            now=datetime(2026, 7, 22, 12, 2, 0, tzinfo=timezone.utc),
        )

    assert rejected.value.status_code == 422
    assert rejected.value.code == "admin_current_device_cannot_report_lost"
    assert db.commits == 1


@pytest.mark.parametrize(
    "denial_code",
    [
        "admin_security_state_blocks_operation",
        "admin_recovery_custody_required",
    ],
)
def test_database_bound_freeze_denial_is_committed_once(
    monkeypatch: pytest.MonkeyPatch,
    denial_code: str,
) -> None:
    appended: list[dict[str, object]] = []
    db = _ServiceDatabase([])

    def deny(*_args, **_kwargs):
        raise AdminSecurityError(denial_code, "frozen", status_code=409)

    monkeypatch.setattr(
        "backend.app.services.admin_security._authorize_high_risk_bearer",
        deny,
    )
    monkeypatch.setattr(
        "backend.app.services.admin_security.append_admin_security_denial",
        lambda _db, **kwargs: appended.append(kwargs),
    )

    with pytest.raises(AdminSecurityError) as frozen:
        authorize_database_bound_high_risk_bearer(
            db,
            "opaque-token",
            "data.delete",
            method="POST",
            path="/admin/operations/data-deletions",
            nonce=RECONFIRM_NONCE,
            runtime_totp_secret=_settings().admin_totp_secret,
            device_id=DEVICE_ID,
        )

    assert frozen.value.code == denial_code
    assert db.commits == 1
    assert appended == [
        {
            "action": "data.delete",
            "reason": denial_code,
            "method": "POST",
            "path": "/admin/operations/data-deletions",
            "device_id": DEVICE_ID,
        }
    ]


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


def test_recovery_complete_returns_typed_expired_error() -> None:
    class ExpiredRecoveryService(FakeAdminSecurityService):
        def complete_recovery(self, **_kwargs):
            raise AdminSecurityError(
                "admin_recovery_expired",
                "The recovery transaction expired; start recovery again.",
                status_code=410,
            )

    response = _api_client(ExpiredRecoveryService(), []).post(
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

    assert response.status_code == 410
    assert response.json() == {
        "detail": {
            "code": "admin_recovery_expired",
            "message": "The recovery transaction expired; start recovery again.",
        }
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
        "recovery_custody_state": "UNATTESTED",
        "recovery_custody_attested_at": None,
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
        ],
        "devices": [
            {"device_id": DEVICE_ID, "current": True},
            {"device_id": "android-device-0002", "current": False},
        ],
    }


def test_recovery_custody_and_lost_device_api_contracts_are_strict() -> None:
    service = FakeAdminSecurityService()
    client = _api_client(service, [])
    headers = {**VALID_HEADERS, "Authorization": "Bearer valid-bearer-token"}

    attested = client.post(
        "/admin/security/recovery-custody/attest",
        headers=headers,
        json={
            "custody_reference": RECOVERY_CUSTODY_REFERENCE,
            "material_kind": "RECOVERY_CODE",
            "storage_location": "OFF_PHONE",
            "separate_encrypted_backup_confirmed": True,
        },
    )
    lost = client.post(
        "/admin/security/devices/android-device-0002/report-lost",
        headers=headers,
        json={},
    )
    nonempty = client.post(
        "/admin/security/devices/android-device-0002/report-lost",
        headers=headers,
        json={"reason": "lost"},
    )
    noncanonical = client.post(
        "/admin/security/recovery-custody/attest",
        headers=headers,
        json={
            "custody_reference": RECOVERY_CUSTODY_REFERENCE[:-1] + "Z",
            "material_kind": "RECOVERY_CODE",
            "storage_location": "OFF_PHONE",
            "separate_encrypted_backup_confirmed": True,
        },
    )

    assert attested.status_code == 200
    assert attested.json() == {
        "security_state": "NORMAL",
        "state_version": "8",
        "observed_at": "2026-07-22T12:01:00+00:00",
        "recovery_custody_state": "ATTESTED",
        "recovery_custody_attested_at": "2026-07-22T12:01:00Z",
    }
    assert service.custody_calls == [
        {
            "custody_reference": RECOVERY_CUSTODY_REFERENCE,
            "material_kind": "RECOVERY_CODE",
            "storage_location": "OFF_PHONE",
            "separate_encrypted_backup_confirmed": True,
        }
    ]
    assert lost.status_code == 200
    assert service.lost_device_calls == [(_identity(), "android-device-0002")]
    assert nonempty.status_code == 422
    assert noncanonical.status_code == 422
    assert len(service.lost_device_calls) == 1
    assert len(service.custody_calls) == 1


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
    admin_status_patch = classify_admin_operation(
        "PATCH",
        "/admin/reports/11111111-1111-4111-8111-111111111111/status",
    )
    delivery_package = classify_admin_operation(
        "POST",
        "/admin/reports/11111111-1111-4111-8111-111111111111/delivery-packages",
    )
    original_grant = classify_admin_operation(
        "POST",
        "/reports/11111111-1111-4111-8111-111111111111/original-access-grants",
    )
    custody_attest = classify_admin_operation(
        "POST",
        "/admin/security/recovery-custody/attest",
    )
    report_lost = classify_admin_operation(
        "POST",
        "/admin/security/devices/android-device-0002/report-lost",
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
    assert admin_status_patch is not None
    assert (admin_status_patch.action, admin_status_patch.risk) == (
        "admin.report.status.update",
        "HIGH",
    )
    assert delivery_package is not None
    assert (delivery_package.action, delivery_package.risk) == (
        "admin.report.delivery_package.create",
        "HIGH",
    )
    assert original_grant is not None
    assert (original_grant.action, original_grant.method) == (
        "report.original.grant",
        "POST",
    )
    assert custody_attest is not None
    assert (custody_attest.action, custody_attest.risk) == (
        "recovery.custody.attest",
        "STANDARD",
    )
    assert report_lost is not None
    assert (report_lost.action, report_lost.risk) == (
        "device.report_lost",
        "STANDARD",
    )
    for path, action in (
        ("/admin/operations/release-approvals", "release.approval"),
        ("/admin/operations/privilege-changes", "privilege.change"),
        ("/admin/operations/data-deletions", "data.delete"),
    ):
        high_risk = classify_admin_operation("POST", path)
        assert high_risk is not None
        assert (high_risk.action, high_risk.risk) == (action, "HIGH")
    assert classify_admin_operation("POST", "/reports/export") is None
    assert classify_admin_operation("GET", "/reports/export/near") is None
    assert classify_admin_operation("PATCH", "/reports/not-a-uuid/status") is None
    assert classify_admin_operation(
        "PATCH", "/admin/reports/not-a-uuid/status"
    ) is None
    assert classify_admin_operation(
        "POST", "/admin/reports/not-a-uuid/delivery-packages"
    ) is None
    assert classify_admin_operation("POST", "/reports/not-a-uuid/original-access-grants") is None
    assert classify_admin_operation(
        "POST", "/admin/security/devices/short/report-lost"
    ) is None
    assert classify_admin_operation(
        "POST", "/admin/operations/data-deletions/near"
    ) is None


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
    credential_issuer_key = "issuer-key-for-admin-security-tests-1234567890"
    monkeypatch.setattr(
        field_test_security_api,
        "load_admin_credential_issuer_key_for_settings",
        lambda _settings_value: credential_issuer_key,
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
            "credential_issuer_key": credential_issuer_key,
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


def test_admin_control_model_enforces_singleton_and_custody_invariants() -> None:
    columns = AdminSecurityControl.__table__.columns.keys()
    assert {
        "singleton_scope",
        "recovery_custody_state",
        "recovery_custody_attested_at",
        "recovery_custody_reference_sha256",
        "recovery_custody_material_kind",
        "recovery_custody_storage_location",
        "recovery_custody_separate_backup_confirmed",
    }.issubset(columns)
    named_constraints = {
        constraint.name for constraint in AdminSecurityControl.__table__.constraints
    }
    assert {
        "ck_admin_security_controls_singleton",
        "uq_admin_security_controls_singleton",
        "ck_admin_security_controls_custody_state",
        "ck_admin_security_controls_custody_reference",
        "ck_admin_security_controls_custody_attestation",
    }.issubset(named_constraints)


def test_recovery_custody_migration_is_single_head_and_uses_existing_acl_table() -> None:
    migration = (
        Path(__file__).parents[1]
        / "alembic"
        / "versions"
        / "202608120001_admin_recovery_custody.py"
    ).read_text(encoding="utf-8")
    normalized = " ".join(migration.lower().split())

    assert 'revision = "202608120001"' in normalized
    assert 'down_revision = "202608090001"' in normalized
    assert 'op.create_table( "admin_security_controls"' not in normalized
    assert "admin_security_controls" in normalized
    assert "uq_admin_security_controls_singleton" in normalized
    assert "ck_admin_security_controls_custody_attestation" in normalized
    assert "select count(*) from admin_security_controls" in normalized
    assert "refusing to choose or delete an administrator" in normalized
    assert "deferrable initially deferred" in normalized
    assert "audit.xmin = pg_catalog.pg_current_xact_id()::pg_catalog.xid" in normalized
    assert "other_device_keys_revoked" in normalized
    assert "walksafe_recovery_custody_markers" in normalized
    assert "walksafe_recovery_custody_capabilities" in normalized
    assert "revoke all privileges on table" in normalized
    assert '"from public, walksafe_backend_runtime"' in normalized
    assert "p_runtime_totp_secret text" in normalized
    assert "p_recovery_token_sha256 text" in normalized
    assert "pending_recovery_expires_at" in normalized
    assert "pending_next_totp_fingerprint" in normalized
    assert "ck_walksafe_recovery_custody_capability_pending_next_totp" in normalized
    assert (
        "ck_walksafe_recovery_custody_capability_pending_next_is_new" in normalized
    )
    assert "ck_walksafe_recovery_custody_capability_pending_next_binding" in normalized
    assert "issuer_key_sha256" in normalized
    assert "p_credential_issuer_key text" in normalized
    assert "walksafe_assert_admin_credential_issuer_key" in normalized
    assert "walksafe_bind_admin_credential_issuer_key" in normalized
    assert "walksafe_assert_admin_totp_capability" in normalized
    assert "walksafe_lock_admin_security_control" in normalized
    assert "walksafe_lock_admin_security_control()" not in normalized
    assert "walksafe_lock_admin_original_access_session" in normalized
    assert "walksafe_lock_admin_device_proof_context" in normalized
    assert "walksafe_touch_admin_session" in normalized


def test_admin_runtime_acl_hardening_migration_is_successor_head() -> None:
    migration = (
        Path(__file__).parents[1]
        / "alembic"
        / "versions"
        / "202608130001_admin_runtime_acl_hardening.py"
    ).read_text(encoding="utf-8")
    normalized = " ".join(migration.lower().split())

    assert 'revision = "202608130001"' in normalized
    assert 'down_revision = "202608120001"' in normalized
    assert "revoke update on table public.admin_device_proof_challenges" in normalized
    assert '"from walksafe_backend_runtime"' in normalized
    assert "grant update (consumed_at) on table" in normalized
    assert "public.admin_device_proof_challenges to walksafe_backend_runtime" in normalized
    assert "walksafe_require_admin_device_proof_consumption" in normalized
    assert "language plpgsql security invoker" in normalized
    assert "old.consumed_at is not null" in normalized
    assert "new.nonce" in normalized and "old.nonce" in normalized
    assert "walksafe_classify_admin_startup_totp_binding" in normalized
    assert "returns text" in normalized
    assert "return 'current'" in normalized
    assert "return 'recovery_candidate'" in normalized
    assert "pending_next_totp_fingerprint" in normalized
    assert "active_recovery_count <> 1" in normalized
    assert "exact_recovery_binding_count <> 1" in normalized
    assert "walksafe_require_initial_unattested_recovery_audit" in normalized
    assert "deferrable initially deferred" in normalized


def test_admin_recovery_expiry_candidate_migration_is_successor_head(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    migration = importlib.import_module(
        "backend.alembic.versions."
        "202608150001_admin_recovery_expiry_candidate"
    )
    statements: list[str] = []
    monkeypatch.setattr(migration.op, "execute", statements.append)
    migration.upgrade()
    normalized = " ".join("\n".join(statements).lower().split())

    assert migration.revision == "202608150001"
    assert migration.down_revision == "202608130001"
    assert (
        "create or replace function public.walksafe_expire_admin_recovery"
        in normalized
    )
    assert (
        "revoke all on function public.walksafe_expire_admin_recovery(text, "
        "uuid, text, timestamptz, text, text) from public, "
        "walksafe_backend_runtime"
    ) in normalized
    assert (
        "grant execute on function public.walksafe_expire_admin_recovery(text, "
        "uuid, text, timestamptz, text, text) to walksafe_backend_runtime"
    ) in normalized
    assert normalized.count("administrator recovery expiry authority differs") == 2
    assert "procedure.proowner" in normalized
    assert "acl.grantee = 0" in normalized
    assert "acl.grantee not in" in normalized
    assert "and not acl.is_grantable" in normalized
    assert "pending_next_totp_fingerprint" in normalized
    assert "pending_recovery_token_sha256" in normalized
    assert "previous_totp_secret_fingerprint" in normalized

    statements.clear()
    migration.downgrade()
    downgrade = " ".join("\n".join(statements).lower().split())
    advisory_index = downgrade.index("pg_catalog.pg_advisory_xact_lock")
    table_lock_index = downgrade.index(
        "lock table public.admin_security_controls, "
        "public.walksafe_recovery_custody_capabilities, "
        "public.admin_security_recovery_transactions "
        "in share row exclusive mode"
    )
    guard_index = downgrade.index(
        "active administrator recovery must be cleared before downgrade"
    )
    assert advisory_index < table_lock_index < guard_index
    assert "recovery.completed_at is null" in downgrade
    assert "active administrator recovery must be cleared before downgrade" in downgrade
    assert guard_index < (
        downgrade.index(
            "create or replace function public.walksafe_expire_admin_recovery"
        )
    )


def test_admin_recovery_expired_proof_migration_is_successor_head(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    migration = importlib.import_module(
        "backend.alembic.versions."
        "202608150002_admin_recovery_expired_proof"
    )
    statements: list[str] = []
    monkeypatch.setattr(migration.op, "execute", statements.append)
    migration.upgrade()
    normalized = " ".join("\n".join(statements).lower().split())

    assert migration.revision == "202608150002"
    assert migration.down_revision == "202608150001"
    assert (
        "create or replace function public.walksafe_lock_admin_device_proof_context"
        in normalized
    )
    assert migration._PREDECESSOR_DEFINITION_SHA256 in normalized
    assert migration._SUCCESSOR_DEFINITION_SHA256 in normalized
    assert migration._CLASSIFIER_PREDECESSOR_DEFINITION_SHA256 in normalized
    assert migration._CLASSIFIER_SUCCESSOR_DEFINITION_SHA256 in normalized
    assert normalized.count("administrator device proof authority differs") == 2
    assert normalized.count("administrator startup classifier authority differs") == 2
    assert normalized.count("and not acl.is_grantable") == 4
    assert "return 'recovery_expired_candidate'" in normalized
    assert "exact_expired_recovery_count = 1" in normalized
    assert "pending_recovery_token_sha256" in normalized
    assert "pending_recovery_expires_at" in normalized
    assert "pending_next_totp_fingerprint is not distinct from" in normalized
    assert "previous_totp_secret_fingerprint is not distinct from" in normalized
    assert "context_status := 'recovery_expired'" in normalized
    assert "public_key_spki_der := null" in normalized
    assert (
        "revoke all on function public.walksafe_lock_admin_device_proof_context("
        "text, text, bigint, text, timestamptz, text, text, text) from public, "
        "walksafe_backend_runtime"
    ) in normalized

    statements.clear()
    migration.downgrade()
    downgrade = " ".join("\n".join(statements).lower().split())
    advisory_index = downgrade.index("pg_catalog.pg_advisory_xact_lock")
    table_lock_index = downgrade.index(
        "lock table public.admin_security_controls, "
        "public.walksafe_recovery_custody_capabilities, "
        "public.admin_security_recovery_transactions "
        "in share row exclusive mode"
    )
    guard_index = downgrade.index(
        "active administrator recovery must be cleared before downgrade"
    )
    assert advisory_index < table_lock_index < guard_index
    assert "recovery.completed_at is null" in downgrade
    assert "active administrator recovery must be cleared before downgrade" in downgrade
    assert guard_index < (
        downgrade.index(
            "create or replace function public.walksafe_lock_admin_device_proof_context"
        )
    )


def test_admin_recovery_custody_applied_migration_is_immutable() -> None:
    migration = (
        Path(__file__).parents[1]
        / "alembic"
        / "versions"
        / "202608120001_admin_recovery_custody.py"
    ).read_bytes()

    assert len(migration) == 134_131
    assert hashlib.sha256(migration).hexdigest() == (
        "40a6e86fe965cd3d08dc10450c9bde353a3d665707763cd777a49243e82da6eb"
    )


def test_recovery_custody_rpc_capabilities_precede_database_locks() -> None:
    migration = (
        Path(__file__).parents[1]
        / "alembic"
        / "versions"
        / "202608120001_admin_recovery_custody.py"
    ).read_text(encoding="utf-8")
    normalized = " ".join(migration.lower().split())

    assert "walksafe_consume_admin_reconfirmation" in normalized
    assert "walksafe_revoke_admin_session" in normalized
    assert "revoke update (consumed_at)" in normalized
    assert "admin_security_reconfirmations_consume_once" in normalized
    assert "walksafe_issue_admin_session" in normalized
    assert "walksafe_issue_admin_reconfirmation" in normalized
    assert "walksafe_report_admin_lost_device" in normalized
    assert "walksafe_inspect_admin_recovery_start" in normalized
    assert "walksafe_issue_admin_recovery_transaction" in normalized
    assert "walksafe_resume_admin_recovery_transaction" in normalized
    assert "walksafe_inspect_admin_recovery_completion" in normalized
    assert "walksafe_complete_admin_recovery_transaction" in normalized
    assert "walksafe_expire_admin_recovery" in normalized
    assert "admin_security_sessions_transition_only" in normalized
    assert "revoke update on table public.admin_security_sessions" in normalized
    assert "revoke select on table public.admin_security_recovery_codes" in normalized
    assert "walksafe_rotate_recovery_custody_capability" in normalized
    assert "revoke insert on table public.admin_security_controls" in normalized
    assert "revoke update on table public.admin_security_controls" in normalized
    assert "revoke insert on table public.admin_device_keys" in normalized
    assert "revoke update on table public.admin_device_keys" in normalized
    assert "using errcode = '55000'" in normalized
    assert "admin_device_keys_revocation_only" in normalized
    assert "security definer" in normalized

    def function_sql(name: str) -> str:
        start = normalized.index(f"create function {name}(")
        end = normalized.index("$$ language plpgsql", start)
        return normalized[start:end]

    for function_name in (
        "walksafe_lock_admin_original_access_session",
        "walksafe_touch_admin_session",
        "walksafe_consume_admin_reconfirmation",
        "walksafe_revoke_admin_session",
        "walksafe_issue_admin_session",
        "walksafe_issue_admin_reconfirmation",
        "walksafe_report_admin_lost_device",
        "walksafe_inspect_admin_recovery_start",
        "walksafe_issue_admin_recovery_transaction",
        "walksafe_resume_admin_recovery_transaction",
        "walksafe_expire_admin_recovery",
        "walksafe_attest_recovery_custody",
        "walksafe_reset_recovery_custody",
    ):
        body = function_sql(function_name)
        first_lock = min(
            body.index(marker)
            for marker in (
                "for update",
                "walksafe_lock_admin_security_control(",
            )
            if marker in body
        )
        assert body.index("walksafe_assert_admin_totp_capability") < first_lock
    for function_name in (
        "walksafe_inspect_admin_recovery_completion",
        "walksafe_complete_admin_recovery_transaction",
        "walksafe_rotate_recovery_custody_capability",
    ):
        body = function_sql(function_name)
        assert body.index("if not exists") < body.index("for update")
    proof_body = function_sql("walksafe_lock_admin_device_proof_context")
    assert proof_body.index("if not exists") < proof_body.index(
        "pg_advisory_xact_lock"
    ) < proof_body.index("for update")


@pytest.mark.parametrize("control_count", [0, 1, 2])
@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_recovery_custody_migration_preflights_control_count(
    control_count: int,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    configured_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip())
    database_name = f"walksafe_custody_test_{uuid.uuid4().hex}"
    assert database_name.startswith("walksafe_custody_test_")
    disposable_url = configured_url.set(database=database_name)
    admin_engine = create_engine(configured_url, pool_pre_ping=True)
    engine = create_engine(disposable_url, pool_pre_ping=True)
    quoted_database_name = admin_engine.dialect.identifier_preparer.quote(
        database_name
    )
    created = False
    prior_revision = "202608090001"
    try:
        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            current_database = connection.execute(
                text("SELECT current_database()")
            ).scalar_one()
            assert "test" in current_database.lower()
            connection.execute(text(f"CREATE DATABASE {quoted_database_name}"))
            created = True

        migration_url = disposable_url.render_as_string(hide_password=False)
        monkeypatch.setenv("DATABASE_URL", migration_url)
        monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", migration_url)
        config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
        command.upgrade(config, prior_revision)
        with engine.begin() as connection:
            connection.execute(text("DELETE FROM admin_security_controls"))
            for index in range(control_count):
                connection.execute(
                    text(
                        "INSERT INTO admin_security_controls ("
                        "admin_id, password_hash, totp_secret_fingerprint, "
                        "security_state, state_version) VALUES ("
                        ":admin_id, 'test-hash', :fingerprint, 'NORMAL', 1)"
                    ),
                    {
                        "admin_id": f"walksafe.admin.{index}",
                        "fingerprint": f"{index}" * 64,
                    },
                )
        if control_count == 2:
            with pytest.raises(RuntimeError, match="multiple rows"):
                command.upgrade(config, "head")
        else:
            command.upgrade(config, "head")
            if control_count == 0:
                with engine.connect().execution_options(
                    isolation_level="AUTOCOMMIT"
                ) as connection:
                    connection.execute(
                        text(
                            "SET SESSION AUTHORIZATION "
                            "walksafe_backend_runtime"
                        )
                    )
                    try:
                        with pytest.raises(SQLAlchemyError):
                            connection.execute(
                                text(
                                    "INSERT INTO admin_security_controls ("
                                    "admin_id, password_hash, "
                                    "totp_secret_fingerprint, security_state, "
                                    "state_version) VALUES ("
                                    "'runtime-forged-admin', 'test-hash', "
                                    f"'{('f' * 64)}', 'NORMAL', 1)"
                                )
                            )
                        with pytest.raises(SQLAlchemyError):
                            connection.execute(
                                text(
                                    "INSERT INTO admin_device_keys ("
                                    "id, admin_id, device_id, key_version, "
                                    "public_key_spki_der, key_marker, status) "
                                    "VALUES (gen_random_uuid(), "
                                    "'runtime-forged-admin', "
                                    "'runtime-device-0001', 1, "
                                    "decode('00', 'hex'), "
                                    f"'{('e' * 64)}', 'ACTIVE')"
                                )
                            )
                    finally:
                        connection.execute(text("RESET SESSION AUTHORIZATION"))
            else:
                issuer_key = base64.urlsafe_b64encode(bytes(range(32))).decode(
                    "ascii"
                ).rstrip("=")
                different_key = base64.urlsafe_b64encode(
                    bytes(reversed(range(32)))
                ).decode("ascii").rstrip("=")
                with engine.begin() as connection:
                    assert connection.execute(
                        text(
                            "SELECT public."
                            "walksafe_bind_admin_credential_issuer_key("
                            ":admin_id, :issuer_key)"
                        ),
                        {
                            "admin_id": "walksafe.admin.0",
                            "issuer_key": issuer_key,
                        },
                    ).scalar_one() is True
                    assert connection.execute(
                        text(
                            "SELECT public."
                            "walksafe_bind_admin_credential_issuer_key("
                            ":admin_id, :issuer_key)"
                        ),
                        {
                            "admin_id": "walksafe.admin.0",
                            "issuer_key": issuer_key,
                        },
                    ).scalar_one() is False
                with engine.connect() as connection:
                    transaction = connection.begin()
                    try:
                        with pytest.raises(SQLAlchemyError) as replacement:
                            connection.execute(
                                text(
                                    "SELECT public."
                                    "walksafe_bind_admin_credential_issuer_key("
                                    ":admin_id, :issuer_key)"
                                ),
                                {
                                    "admin_id": "walksafe.admin.0",
                                    "issuer_key": different_key,
                                },
                            )
                        assert getattr(
                            replacement.value.orig, "sqlstate", None
                        ) == "42501"
                    finally:
                        transaction.rollback()
                with engine.connect().execution_options(
                    isolation_level="AUTOCOMMIT"
                ) as connection:
                    connection.execute(
                        text(
                            "SET SESSION AUTHORIZATION "
                            "walksafe_backend_runtime"
                        )
                    )
                    try:
                        with pytest.raises(SQLAlchemyError) as runtime_bind:
                            connection.execute(
                                text(
                                    "SELECT public."
                                    "walksafe_bind_admin_credential_issuer_key("
                                    ":admin_id, :issuer_key)"
                                ),
                                {
                                    "admin_id": "walksafe.admin.0",
                                    "issuer_key": issuer_key,
                                },
                            )
                        assert getattr(
                            runtime_bind.value.orig, "sqlstate", None
                        ) == "42501"
                    finally:
                        connection.execute(text("RESET SESSION AUTHORIZATION"))
        with engine.connect() as connection:
            revision = connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one()
            custody_column_count = connection.execute(
                text(
                    "SELECT count(*) FROM information_schema.columns "
                    "WHERE table_schema = 'public' "
                    "AND table_name = 'admin_security_controls' "
                    "AND column_name = 'recovery_custody_state'"
                )
            ).scalar_one()
            persisted_count = connection.execute(
                text("SELECT count(*) FROM admin_security_controls")
            ).scalar_one()
        assert persisted_count == control_count
        if control_count == 2:
            assert revision == prior_revision
            assert custody_column_count == 0
        else:
            assert revision == _repository_alembic_head()
            assert custody_column_count == 1
    finally:
        engine.dispose()
        if created:
            with admin_engine.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                connection.execute(
                    text(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                        "WHERE datname = :database_name "
                        "AND pid <> pg_backend_pid()"
                    ),
                    {"database_name": database_name},
                )
                connection.execute(
                    text(f"DROP DATABASE IF EXISTS {quoted_database_name}")
                )
        admin_engine.dispose()


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_session_and_active_device_lists_keep_current_beyond_limit() -> None:
    database_url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
    engine = create_engine(database_url, pool_pre_ping=True)
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)
    observed_at = datetime(2026, 7, 22, 14, 0, 0, tzinfo=timezone.utc)
    admin_id = f"pagination-{uuid.uuid4()}"
    current_session_id = uuid.uuid4()
    current_device_id = "zz-pagination-current"
    identity = AdminSessionIdentity(
        admin_id=admin_id,
        session_id=current_session_id,
        device_id=current_device_id,
        device_label="현재 기기",
        expires_at=observed_at + timedelta(hours=1),
        step_up_verified_at=observed_at,
    )
    try:
        with SessionFactory() as db:
            db.add(
                AdminSecuritySession(
                    id=current_session_id,
                    admin_id=admin_id,
                    device_id=current_device_id,
                    device_label="현재 기기",
                    token_sha256=sha256_text(f"{admin_id}-current-token"),
                    issued_at=observed_at - timedelta(days=1),
                    expires_at=observed_at + timedelta(hours=1),
                    step_up_verified_at=observed_at,
                    last_seen_at=observed_at,
                )
            )
            db.add(
                AdminDeviceKey(
                    admin_id=admin_id,
                    device_id=current_device_id,
                    key_version=1,
                    public_key_spki_der=b"pagination-current-key",
                    key_marker=sha256_text(f"{admin_id}-current-key"),
                    status="ACTIVE",
                    created_at=observed_at - timedelta(days=1),
                )
            )
            for index in range(100):
                device_id = f"pagination-device-{index:03d}"
                db.add(
                    AdminSecuritySession(
                        admin_id=admin_id,
                        device_id=device_id,
                        device_label=f"기기 {index:03d}",
                        token_sha256=sha256_text(f"{admin_id}-token-{index}"),
                        issued_at=observed_at + timedelta(seconds=index),
                        expires_at=observed_at + timedelta(hours=1, seconds=index),
                        step_up_verified_at=observed_at,
                        last_seen_at=observed_at,
                    )
                )
                db.add(
                    AdminDeviceKey(
                        admin_id=admin_id,
                        device_id=device_id,
                        key_version=1,
                        public_key_spki_der=f"pagination-key-{index}".encode(),
                        key_marker=sha256_text(f"{admin_id}-key-{index}"),
                        status="ACTIVE",
                        created_at=observed_at + timedelta(seconds=index),
                    )
                )
            db.flush()

            service = AdminSecurityService(db, _settings())
            sessions = service.list_sessions(identity, now=observed_at)
            devices = service.list_active_devices(identity)
            assert sessions == service.list_sessions(identity, now=observed_at)
            assert devices == service.list_active_devices(identity)

            assert len(sessions) == 100
            assert sum(item["current"] for item in sessions) == 1
            assert sessions[0]["session_id"] == str(current_session_id)
            assert sessions[0]["revoked"] is False
            assert len(devices) == 100
            assert len({item["device_id"] for item in devices}) == 100
            assert sum(item["current"] for item in devices) == 1
            assert devices[0] == {
                "device_id": current_device_id,
                "current": True,
            }
            db.rollback()
    finally:
        engine.dispose()


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
    upload_dir.mkdir(mode=0o750)
    upload_dir.chmod(0o2750)
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
    monkeypatch.setattr(Settings, "_validate_deployment_detector", lambda self: None)
    monkeypatch.setattr(
        Settings,
        "_validate_deployment_maintenance_lock",
        lambda self: None,
    )
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
    tmp_path: Path,
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

    def run_as_runtime(callback):
        with engine.connect() as runtime_connection:
            runtime_connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            runtime_connection.commit()
            try:
                with sessionmaker(
                    bind=runtime_connection,
                    expire_on_commit=False,
                )() as runtime_db:
                    return callback(runtime_db)
            finally:
                if runtime_connection.in_transaction():
                    runtime_connection.rollback()
                runtime_connection.execute(text("RESET SESSION AUTHORIZATION"))
                runtime_connection.commit()

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
    credential_issuer_key = base64.urlsafe_b64encode(bytes(range(32))).decode(
        "ascii"
    ).rstrip("=")
    wrong_credential_issuer_key = base64.urlsafe_b64encode(
        bytes(reversed(range(32)))
    ).decode("ascii").rstrip("=")
    issuer_directory = tmp_path / "admin-issuer-authority"
    issuer_directory.mkdir(mode=0o700)
    issuer_key_file = issuer_directory / "issuer.key"
    issuer_key_file.write_text(credential_issuer_key, encoding="ascii")
    issuer_key_file.chmod(0o600)
    settings = SimpleNamespace(
        admin_id="walksafe.admin",
        admin_device_proof_enabled=False,
        admin_credential_issuer_key_file=issuer_key_file,
        admin_totp_secret=initial_secret,
        admin_session_ttl_seconds=43_200,
        admin_step_up_ttl_seconds=300,
        admin_recovery_ttl_seconds=900,
        admin_auth_rate_limit_attempts=5,
        admin_auth_rate_limit_window_seconds=300,
        walksafe_environment="test",
    )
    started_at = datetime.now(timezone.utc).replace(microsecond=0)

    with SessionFactory() as db:
        provision_admin_security(
            db,
            admin_id=settings.admin_id,
            password="initial correct horse",
            totp_secret=initial_secret,
            recovery_codes=[recovery_code],
            credential_issuer_key=credential_issuer_key,
            now=started_at,
        )

    def assert_direct_session_mint_rejected(
        runtime_totp_secret: str,
        issuer_key: str,
    ) -> None:
        def attempt(db):
            with pytest.raises(SQLAlchemyError) as rejected:
                db.execute(
                    text(
                        "SELECT public.walksafe_issue_admin_session("
                        "CAST(:session_id AS uuid), CAST(:admin_id AS text), "
                        "'forged-device', 'forged-device', "
                        "CAST(:token_sha256 AS text), "
                        "CAST(:issued_at AS timestamptz), "
                        "CAST(:expires_at AS timestamptz), "
                        "CAST(:issued_at AS timestamptz), "
                        "CAST(:issued_at AS timestamptz), 'LOGIN', NULL, "
                        "CAST(:next_timecode AS bigint), "
                        "CAST(:runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "session_id": uuid.uuid4(),
                        "admin_id": settings.admin_id,
                        "token_sha256": sha256_text("forged-session-token"),
                        "issued_at": started_at,
                        "expires_at": started_at + timedelta(hours=1),
                        "next_timecode": pyotp.TOTP(initial_secret).timecode(
                            started_at
                        ),
                        "runtime_totp_secret": runtime_totp_secret,
                        "credential_issuer_key": issuer_key,
                    },
                )
            assert getattr(rejected.value.orig, "sqlstate", None) == "42501"

        run_as_runtime(attempt)

    assert_direct_session_mint_rejected(
        initial_secret,
        wrong_credential_issuer_key,
    )
    assert_direct_session_mint_rejected(
        "A" * 32,
        credential_issuer_key,
    )
    with SessionFactory() as db:
        assert db.execute(select(AdminSecuritySession)).scalars().all() == []

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
    second_grant = run_as_runtime(
        lambda db: AdminSecurityService(db, settings).login(
            admin_id=settings.admin_id,
            password="initial correct horse",
            totp_code=pyotp.TOTP(initial_secret).at(second_time),
            device_id="android-device-0002",
            device_label="두 번째 휴대전화",
            source="198.51.100.10",
            now=second_time,
        )
    )
    with SessionFactory() as db:
        second_identity = authorize_admin_bearer(
            db,
            second_grant.access_token,
            runtime_totp_secret=initial_secret,
            credential_issuer_key=credential_issuer_key,
            device_id="android-device-0002",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=second_time,
        )
        db.commit()
    with engine.connect() as runtime_connection:
        runtime_connection.execute(
            text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
        )
        runtime_connection.commit()
        try:
            with sessionmaker(
                bind=runtime_connection,
                expire_on_commit=False,
            )() as db:
                attested_state = AdminSecurityService(
                    db, settings
                ).attest_recovery_custody(
                    second_identity,
                    custody_reference=RECOVERY_CUSTODY_REFERENCE,
                    material_kind="RECOVERY_CODE",
                    storage_location="OFF_PHONE",
                    separate_encrypted_backup_confirmed=True,
                    now=second_time,
                )
        finally:
            runtime_connection.execute(text("RESET SESSION AUTHORIZATION"))
            runtime_connection.commit()
    assert attested_state["recovery_custody_state"] == "ATTESTED"
    runtime_gate_time = second_time + timedelta(seconds=30)
    runtime_gate_nonce = "cnVudGltZS1yb2xlLTAwMQ"
    run_as_runtime(
        lambda db: AdminSecurityService(db, settings).reauthenticate(
            second_identity,
            password="initial correct horse",
            totp_code=pyotp.TOTP(initial_secret).at(runtime_gate_time),
            action="data.delete",
            method="POST",
            path="/admin/operations/data-deletions",
            nonce=runtime_gate_nonce,
            source="198.51.100.10",
            now=runtime_gate_time,
        )
    )
    with engine.connect() as runtime_connection:
        runtime_connection.execute(
            text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
        )
        runtime_connection.commit()
        try:
            with sessionmaker(
                bind=runtime_connection,
                expire_on_commit=False,
            )() as db:
                runtime_identity = authorize_database_bound_high_risk_bearer(
                    db,
                    second_grant.access_token,
                    "data.delete",
                    now=runtime_gate_time,
                    method="POST",
                    path="/admin/operations/data-deletions",
                    nonce=runtime_gate_nonce,
                    runtime_totp_secret=initial_secret,
                    credential_issuer_key=credential_issuer_key,
                    device_id="android-device-0002",
                )
                db.commit()
        finally:
            runtime_connection.execute(text("RESET SESSION AUTHORIZATION"))
            runtime_connection.commit()
    assert runtime_identity.session_id == second_identity.session_id
    with SessionFactory() as db:
        consumed_runtime_reconfirmation = db.execute(
            select(AdminSecurityReconfirmation).where(
                AdminSecurityReconfirmation.nonce_sha256
                == sha256_text(runtime_gate_nonce)
            )
        ).scalar_one()
    assert consumed_runtime_reconfirmation.consumed_at == runtime_gate_time
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as connection:
        connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
        try:
            with pytest.raises(SQLAlchemyError) as consume_reset_rejected:
                connection.execute(
                    text(
                        "UPDATE admin_security_reconfirmations "
                        "SET consumed_at = NULL WHERE id = :id"
                    ),
                    {"id": consumed_runtime_reconfirmation.id},
                )
            assert getattr(
                consume_reset_rejected.value.orig,
                "sqlstate",
                None,
            ) == "42501"
            with pytest.raises(SQLAlchemyError) as binding_update_rejected:
                connection.execute(
                    text(
                        "UPDATE admin_security_reconfirmations "
                        "SET verified_at = verified_at WHERE id = :id"
                    ),
                    {"id": consumed_runtime_reconfirmation.id},
                )
            assert getattr(
                binding_update_rejected.value.orig,
                "sqlstate",
                None,
            ) == "42501"
        finally:
            connection.execute(text("RESET SESSION AUTHORIZATION"))

    forged_token = "runtime-role-forged-session-token"
    forged_nonce = "Zm9yZ2VkLXJvbGUtMDAwMQ"
    forged_session_id = uuid.uuid4()
    with engine.connect() as runtime_connection:
        runtime_connection.execute(
            text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
        )
        runtime_connection.commit()
        attack = runtime_connection.begin()
        try:
            with pytest.raises(SQLAlchemyError) as forged_session_rejected:
                with runtime_connection.begin_nested():
                    runtime_connection.execute(
                        text(
                            "INSERT INTO admin_security_sessions ("
                            "id, admin_id, device_id, device_label, "
                            "token_sha256, issued_at, expires_at, "
                            "step_up_verified_at, last_seen_at) VALUES ("
                            ":id, :admin_id, :device_id, 'forged', "
                            ":token_sha256, :observed_at, :expires_at, "
                            ":observed_at, :observed_at)"
                        ),
                        {
                            "id": forged_session_id,
                            "admin_id": settings.admin_id,
                            "device_id": "runtime-forged-device",
                            "token_sha256": sha256_text(forged_token),
                            "observed_at": runtime_gate_time,
                            "expires_at": runtime_gate_time
                            + timedelta(minutes=5),
                        },
                    )
            assert getattr(
                forged_session_rejected.value.orig, "sqlstate", None
            ) == "42501"
            with pytest.raises(SQLAlchemyError) as forged_reconfirmation_rejected:
                with runtime_connection.begin_nested():
                    runtime_connection.execute(
                        text(
                            "INSERT INTO admin_security_reconfirmations ("
                            "id, admin_id, session_id, device_id, action, "
                            "method, path, nonce_sha256, verified_at, "
                            "expires_at) VALUES (:id, :admin_id, :session_id, "
                            ":device_id, 'data.delete', 'POST', "
                            "'/admin/operations/data-deletions', "
                            ":nonce_sha256, :observed_at, :expires_at)"
                        ),
                        {
                            "id": uuid.uuid4(),
                            "admin_id": settings.admin_id,
                            "session_id": forged_session_id,
                            "device_id": "runtime-forged-device",
                            "nonce_sha256": sha256_text(forged_nonce),
                            "observed_at": runtime_gate_time,
                            "expires_at": runtime_gate_time
                            + timedelta(minutes=5),
                        },
                    )
            assert getattr(
                forged_reconfirmation_rejected.value.orig, "sqlstate", None
            ) == "42501"
            direct_recovery_statements = (
                (
                    "INSERT INTO admin_security_recovery_codes ("
                    "id, admin_id, code_sha256) VALUES ("
                    "gen_random_uuid(), :admin_id, repeat('a', 64))",
                    {"admin_id": settings.admin_id},
                ),
                (
                    "UPDATE admin_security_recovery_codes SET "
                    "code_sha256 = repeat('b', 64) WHERE false",
                    {},
                ),
                (
                    "INSERT INTO admin_security_recovery_transactions ("
                    "id, admin_id, recovery_code_id, recovery_token_sha256, "
                    "previous_totp_secret_fingerprint, device_id, "
                    "device_label, started_at, expires_at) VALUES ("
                    "gen_random_uuid(), :admin_id, gen_random_uuid(), "
                    "repeat('c', 64), repeat('d', 64), "
                    "'runtime-forged-device', 'forged', :observed_at, "
                    ":expires_at)",
                    {
                        "admin_id": settings.admin_id,
                        "observed_at": runtime_gate_time,
                        "expires_at": runtime_gate_time + timedelta(minutes=5),
                    },
                ),
                (
                    "UPDATE admin_security_recovery_transactions SET "
                    "recovery_token_sha256 = repeat('e', 64) WHERE false",
                    {},
                ),
            )
            for statement, parameters in direct_recovery_statements:
                with pytest.raises(SQLAlchemyError) as direct_recovery_rejected:
                    with runtime_connection.begin_nested():
                        runtime_connection.execute(text(statement), parameters)
                assert getattr(
                    direct_recovery_rejected.value.orig, "sqlstate", None
                ) == "42501"
            with pytest.raises(SQLAlchemyError) as session_identity_update_rejected:
                with runtime_connection.begin_nested():
                    runtime_connection.execute(
                        text(
                            "UPDATE admin_security_sessions SET "
                            "token_sha256 = :token_sha256, "
                            "device_id = 'runtime-forged-device', "
                            "expires_at = :expires_at, "
                            "step_up_verified_at = :step_up_verified_at "
                            "WHERE id = :id"
                        ),
                        {
                            "id": second_identity.session_id,
                            "token_sha256": sha256_text(forged_token),
                            "expires_at": runtime_gate_time
                            + timedelta(days=1),
                            "step_up_verified_at": runtime_gate_time,
                        },
                    )
            assert getattr(
                session_identity_update_rejected.value.orig, "sqlstate", None
            ) == "42501"
            with pytest.raises(SQLAlchemyError) as step_up_raise_rejected:
                with runtime_connection.begin_nested():
                    runtime_connection.execute(
                        text(
                            "UPDATE admin_security_sessions SET "
                            "step_up_verified_at = :at WHERE id = :id"
                        ),
                        {
                            "id": second_identity.session_id,
                            "at": runtime_gate_time,
                        },
                    )
            assert getattr(
                step_up_raise_rejected.value.orig, "sqlstate", None
            ) == "42501"
            with pytest.raises(SQLAlchemyError) as last_seen_rewind_rejected:
                with runtime_connection.begin_nested():
                    runtime_connection.execute(
                        text(
                            "UPDATE admin_security_sessions SET "
                            "last_seen_at = :at WHERE id = :id"
                        ),
                        {
                            "id": second_identity.session_id,
                            "at": runtime_gate_time - timedelta(seconds=1),
                        },
                    )
            assert getattr(
                last_seen_rewind_rejected.value.orig, "sqlstate", None
            ) == "42501"
            with pytest.raises(SQLAlchemyError) as direct_touch_rejected:
                with runtime_connection.begin_nested():
                    runtime_connection.execute(
                        text(
                            "UPDATE admin_security_sessions SET last_seen_at = :at "
                            "WHERE id = :id"
                        ),
                        {
                            "id": second_identity.session_id,
                            "at": runtime_gate_time + timedelta(seconds=1),
                        },
                    )
            assert getattr(
                direct_touch_rejected.value.orig, "sqlstate", None
            ) == "42501"
            with pytest.raises(SQLAlchemyError) as direct_revoke_rejected:
                with runtime_connection.begin_nested():
                    runtime_connection.execute(
                        text(
                            "UPDATE admin_security_sessions SET revoked_at = :at, "
                            "revoked_reason = 'administrator_revoked' WHERE id = :id"
                        ),
                        {
                            "id": second_identity.session_id,
                            "at": runtime_gate_time + timedelta(seconds=2),
                        },
                    )
            assert getattr(
                direct_revoke_rejected.value.orig, "sqlstate", None
            ) == "42501"
            with pytest.raises(SQLAlchemyError) as session_unrevoke_rejected:
                with runtime_connection.begin_nested():
                    runtime_connection.execute(
                        text(
                            "UPDATE admin_security_sessions SET "
                            "revoked_at = NULL, revoked_reason = NULL "
                            "WHERE id = :id"
                        ),
                        {"id": second_identity.session_id},
                    )
            assert getattr(
                session_unrevoke_rejected.value.orig, "sqlstate", None
            ) == "42501"
            direct_control_updates = (
                "password_hash = 'runtime-forged-password-hash'",
                "security_state = 'RECOVERY_REQUIRED'",
                "state_version = state_version + 1",
                "last_totp_timecode = 9223372036854775806",
            )
            for assignment in direct_control_updates:
                with pytest.raises(SQLAlchemyError) as control_update_rejected:
                    with runtime_connection.begin_nested():
                        runtime_connection.execute(
                            text(
                                "UPDATE admin_security_controls SET "
                                f"{assignment} WHERE admin_id = :admin_id"
                            ),
                            {"admin_id": settings.admin_id},
                        )
                assert getattr(
                    control_update_rejected.value.orig,
                    "sqlstate",
                    None,
                ) == "42501"
            assert runtime_connection.execute(text("SELECT 1")).scalar_one() == 1
        finally:
            attack.rollback()
            runtime_connection.execute(text("RESET SESSION AUTHORIZATION"))
            runtime_connection.commit()
    with SessionFactory() as db:
        unchanged_public_fingerprint = db.execute(
            select(AdminSecurityControl.totp_secret_fingerprint)
        ).scalar_one()
    assert unchanged_public_fingerprint == sha256_text(initial_secret)
    with pytest.raises(AdminSecurityError) as forged_http_gate:
        field_test_security_api._authorize_admin_request(
            settings,
            raw_token=forged_token,
            device_id="runtime-forged-device",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            high_risk_action="data.delete",
            method="POST",
            path="/admin/operations/data-deletions",
            reconfirmation_nonce=forged_nonce,
        )
    assert forged_http_gate.value.code == "admin_session_invalid"
    recovery_private_key = ec.generate_private_key(ec.SECP256R1())
    recovery_public_key = recovery_private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    recovery_key_marker = admin_device_key_marker(recovery_public_key)
    with SessionFactory() as db:
        db.add_all(
            [
                AdminDeviceKey(
                    admin_id=settings.admin_id,
                    device_id="android-device-0001",
                    key_version=1,
                    public_key_spki_der=b"first-device-public-key",
                    key_marker="a" * 64,
                    status="ACTIVE",
                    created_at=second_time,
                ),
                AdminDeviceKey(
                    admin_id=settings.admin_id,
                    device_id="android-device-0002",
                    key_version=1,
                    public_key_spki_der=b"second-device-public-key",
                    key_marker="b" * 64,
                    status="ACTIVE",
                    created_at=second_time,
                ),
                AdminDeviceKey(
                    admin_id=settings.admin_id,
                    device_id="android-device-0003",
                    key_version=1,
                    public_key_spki_der=recovery_public_key,
                    key_marker=recovery_key_marker,
                    status="ACTIVE",
                    created_at=second_time,
                ),
            ]
        )
        db.commit()
    settings.admin_device_proof_enabled = True
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as connection:
        connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
        try:
            with pytest.raises(SQLAlchemyError) as bypass_rejected:
                connection.execute(
                    text(
                        "UPDATE admin_security_controls SET "
                        "recovery_custody_reference_sha256 = :digest "
                        "WHERE admin_id = :admin_id"
                    ),
                    {"digest": "f" * 64, "admin_id": settings.admin_id},
                )
            assert getattr(bypass_rejected.value.orig, "sqlstate", None) == "42501"
            with pytest.raises(SQLAlchemyError) as forged_audit_rejected:
                connection.execute(
                    text(
                        "WITH forged_audit AS ("
                        "INSERT INTO admin_security_audits ("
                        "id, sequence, previous_entry_sha256, entry_sha256, "
                        "admin_id, action, outcome, details, created_at) "
                        "SELECT gen_random_uuid(), "
                        "COALESCE(max(sequence), 0) + 1, "
                        "CASE WHEN max(sequence) IS NULL THEN NULL "
                        "ELSE (array_agg(entry_sha256 ORDER BY sequence DESC))[1] END, "
                        "repeat('e', 64), :admin_id, "
                        "'recovery.custody.attest', 'SUCCESS', "
                        "CAST(:details AS jsonb), clock_timestamp() "
                        "FROM admin_security_audits RETURNING id) "
                        "UPDATE admin_security_controls SET "
                        "recovery_custody_reference_sha256 = :digest "
                        "WHERE admin_id = :admin_id "
                        "AND EXISTS (SELECT 1 FROM forged_audit)"
                    ),
                    {
                        "admin_id": settings.admin_id,
                        "digest": "f" * 64,
                        "details": (
                            '{"idempotent":false,"material_kind":'
                            '"RECOVERY_CODE","replaced_attestation":true,'
                            '"separate_backup_confirmed":true,'
                            '"storage_location":"OFF_PHONE"}'
                        ),
                    },
                )
            assert getattr(
                forged_audit_rejected.value.orig, "sqlstate", None
            ) == "42501"
            with pytest.raises(SQLAlchemyError) as key_rewrite_rejected:
                connection.execute(
                    text(
                        "UPDATE admin_device_keys SET key_marker = :marker "
                        "WHERE admin_id = :admin_id "
                        "AND device_id = 'android-device-0001'"
                    ),
                    {"admin_id": settings.admin_id, "marker": "f" * 64},
                )
            assert getattr(
                key_rewrite_rejected.value.orig, "sqlstate", None
            ) == "42501"
            with pytest.raises(SQLAlchemyError):
                connection.execute(
                    text(
                        "ALTER TABLE admin_security_controls DISABLE TRIGGER "
                        "admin_security_controls_custody_audited"
                    )
                )
        finally:
            connection.execute(text("RESET SESSION AUTHORIZATION"))
    attacker_totp_secret = "runtime-controlled-attacker-secret"
    attacker_reference_sha256 = "f" * 64
    with engine.connect() as runtime_connection:
        runtime_connection.execute(
            text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
        )
        runtime_connection.commit()
        attack = runtime_connection.begin()
        try:
            with pytest.raises(SQLAlchemyError) as fingerprint_update_rejected:
                with runtime_connection.begin_nested():
                    runtime_connection.execute(
                        text(
                            "UPDATE admin_security_controls SET "
                            "totp_secret_fingerprint = :fingerprint "
                            "WHERE admin_id = :admin_id"
                        ),
                        {
                            "admin_id": settings.admin_id,
                            "fingerprint": sha256_text(attacker_totp_secret),
                        },
                    )
            assert getattr(
                fingerprint_update_rejected.value.orig,
                "sqlstate",
                None,
            ) == "42501"
            runtime_connection.execute(
                text(
                    "INSERT INTO admin_security_audits ("
                    "id, sequence, previous_entry_sha256, entry_sha256, "
                    "admin_id, action, outcome, details, created_at) "
                    "SELECT gen_random_uuid(), "
                    "COALESCE(max(sequence), 0) + 1, "
                    "CASE WHEN max(sequence) IS NULL THEN NULL "
                    "ELSE (array_agg(entry_sha256 ORDER BY sequence DESC))[1] END, "
                    "repeat('e', 64), :admin_id, "
                    "'recovery.custody.attest', 'SUCCESS', "
                    "CAST(:details AS jsonb), clock_timestamp() "
                    "FROM admin_security_audits"
                ),
                {
                    "admin_id": settings.admin_id,
                    "details": (
                        '{"idempotent":false,"material_kind":'
                        '"RECOVERY_CODE","replaced_attestation":true,'
                        '"separate_backup_confirmed":true,'
                        '"storage_location":"OFF_PHONE"}'
                    ),
                },
            )
            with pytest.raises(SQLAlchemyError) as direct_helper_rejected:
                runtime_connection.execute(
                    text(
                        "SELECT public.walksafe_attest_recovery_custody("
                        "CAST(:admin_id AS text), clock_timestamp(), "
                        "CAST(:reference_sha256 AS text), 'RECOVERY_CODE', "
                        "'OFF_PHONE', true, CAST(:runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": settings.admin_id,
                        "reference_sha256": attacker_reference_sha256,
                        "runtime_totp_secret": initial_secret,
                        "credential_issuer_key": wrong_credential_issuer_key,
                    },
                )
            assert getattr(
                direct_helper_rejected.value.orig,
                "sqlstate",
                None,
            ) == "42501"
        finally:
            attack.rollback()
            runtime_connection.execute(text("RESET SESSION AUTHORIZATION"))
            runtime_connection.commit()
    with engine.connect() as privilege_connection:
        runtime_control_privileges = privilege_connection.execute(
            text(
                "SELECT "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_controls', 'UPDATE'), "
                "has_column_privilege('walksafe_backend_runtime', "
                "'public.admin_security_controls', 'password_hash', 'UPDATE'), "
                "has_column_privilege('walksafe_backend_runtime', "
                "'public.admin_security_controls', 'security_state', 'UPDATE'), "
                "has_column_privilege('walksafe_backend_runtime', "
                "'public.admin_security_controls', 'state_version', 'UPDATE'), "
                "has_column_privilege('walksafe_backend_runtime', "
                "'public.admin_security_controls', 'last_totp_timecode', 'UPDATE')"
            )
        ).one()
        runtime_private_privileges = privilege_connection.execute(
            text(
                "SELECT "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_reconfirmations', 'INSERT'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.walksafe_recovery_custody_capabilities', 'SELECT'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.walksafe_recovery_custody_capabilities', 'INSERT'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.walksafe_recovery_custody_capabilities', 'UPDATE'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.walksafe_recovery_custody_capabilities', 'DELETE')"
            )
        ).one()
        runtime_reconfirmation_privileges = privilege_connection.execute(
            text(
                "SELECT "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_reconfirmations', 'UPDATE'), "
                "has_column_privilege('walksafe_backend_runtime', "
                "'public.admin_security_reconfirmations', 'consumed_at', 'UPDATE'), "
                "has_column_privilege('walksafe_backend_runtime', "
                "'public.admin_security_reconfirmations', 'verified_at', 'UPDATE')"
            )
        ).one()
        runtime_session_privileges = privilege_connection.execute(
            text(
                "SELECT "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_sessions', 'INSERT'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_sessions', 'UPDATE'), "
                "has_column_privilege('walksafe_backend_runtime', "
                "'public.admin_security_sessions', 'last_seen_at', 'UPDATE'), "
                "has_column_privilege('walksafe_backend_runtime', "
                "'public.admin_security_sessions', 'revoked_at', 'UPDATE'), "
                "has_column_privilege('walksafe_backend_runtime', "
                "'public.admin_security_sessions', 'token_sha256', 'UPDATE')"
            )
        ).one()
        runtime_device_mutation_privileges = privilege_connection.execute(
            text(
                "SELECT "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_device_keys', 'UPDATE'), "
                "has_column_privilege('walksafe_backend_runtime', "
                "'public.admin_device_keys', 'status', 'UPDATE'), "
                "has_column_privilege('walksafe_backend_runtime', "
                "'public.admin_device_keys', 'revoked_at', 'UPDATE')"
            )
        ).one()
        runtime_recovery_mint_privileges = privilege_connection.execute(
            text(
                "SELECT "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_recovery_codes', 'SELECT'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_recovery_codes', 'INSERT'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_recovery_codes', 'UPDATE'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_recovery_transactions', 'SELECT'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_recovery_transactions', 'INSERT'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.admin_security_recovery_transactions', 'UPDATE')"
            )
        ).one()
        security_definer_contracts = privilege_connection.execute(
            text(
                "SELECT p.proname, p.pronargs, p.prosecdef, p.proconfig, "
                "EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE("
                "p.proacl, pg_catalog.acldefault('f', p.proowner))) AS acl "
                "WHERE acl.grantee = 0 AND acl.privilege_type = 'EXECUTE') "
                "AS public_execute, "
                "EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE("
                "p.proacl, pg_catalog.acldefault('f', p.proowner))) AS acl "
                "WHERE acl.grantee = "
                "'walksafe_backend_runtime'::regrole::oid "
                "AND acl.privilege_type = 'EXECUTE') AS runtime_execute, "
                "EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE("
                "p.proacl, pg_catalog.acldefault('f', p.proowner))) AS acl "
                "WHERE acl.grantee NOT IN (0, p.proowner, "
                "'walksafe_backend_runtime'::regrole::oid) "
                "AND NOT (p.proname = "
                "'walksafe_lock_admin_original_access_session' "
                "AND acl.grantee = "
                "'walksafe_report_evidence_owner'::regrole::oid) "
                "AND acl.privilege_type = 'EXECUTE') AS unexpected_execute "
                "FROM pg_catalog.pg_proc AS p "
                "JOIN pg_catalog.pg_namespace AS namespace "
                "ON namespace.oid = p.pronamespace "
                "WHERE namespace.nspname = 'public' AND p.proname IN ("
                "'walksafe_assert_admin_totp_capability', "
                "'walksafe_lock_admin_security_control', "
                "'walksafe_lock_admin_original_access_session', "
                "'walksafe_lock_admin_device_proof_context', "
                "'walksafe_touch_admin_session', "
                "'walksafe_consume_admin_reconfirmation', "
                "'walksafe_revoke_admin_session', "
                "'walksafe_assert_admin_credential_issuer_key', "
                "'walksafe_bind_admin_credential_issuer_key', "
                "'walksafe_issue_admin_session', "
                "'walksafe_issue_admin_reconfirmation', "
                "'walksafe_report_admin_lost_device', "
                "'walksafe_inspect_admin_recovery_start', "
                "'walksafe_issue_admin_recovery_transaction', "
                "'walksafe_resume_admin_recovery_transaction', "
                "'walksafe_inspect_admin_recovery_completion', "
                "'walksafe_expire_admin_recovery', "
                "'walksafe_complete_admin_recovery_transaction', "
                "'walksafe_attest_recovery_custody', "
                "'walksafe_reset_recovery_custody', "
                "'walksafe_rotate_recovery_custody_capability', "
                "'walksafe_require_recovery_custody_audit') "
                "ORDER BY p.proname"
            )
        ).mappings().all()
    assert tuple(runtime_control_privileges) == (
        False,
        False,
        False,
        False,
        False,
    )
    assert tuple(runtime_private_privileges) == (
        False,
        False,
        False,
        False,
        False,
    )
    assert tuple(runtime_reconfirmation_privileges) == (False, False, False)
    assert tuple(runtime_session_privileges) == (False, False, False, False, False)
    assert tuple(runtime_device_mutation_privileges) == (False, False, False)
    assert tuple(runtime_recovery_mint_privileges) == (
        False,
        False,
        False,
        False,
        False,
        False,
    )
    with engine.connect() as runtime_lock_connection:
        runtime_lock_connection.execute(
            text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
        )
        runtime_lock_connection.commit()
        try:
            for direct_lock_statement in (
                "SELECT admin_id FROM admin_security_controls FOR UPDATE",
                "SELECT id FROM admin_device_keys FOR UPDATE",
                "SELECT id FROM admin_security_recovery_transactions FOR UPDATE",
            ):
                direct_lock_transaction = runtime_lock_connection.begin()
                try:
                    with pytest.raises(SQLAlchemyError) as direct_lock_rejected:
                        runtime_lock_connection.execute(text(direct_lock_statement))
                    assert getattr(
                        direct_lock_rejected.value.orig,
                        "sqlstate",
                        None,
                    ) == "42501"
                finally:
                    direct_lock_transaction.rollback()
        finally:
            runtime_lock_connection.execute(text("RESET SESSION AUTHORIZATION"))
            runtime_lock_connection.commit()
    expected_security_definers = {
        "walksafe_assert_admin_credential_issuer_key": (2, True),
        "walksafe_assert_admin_totp_capability": (3, True),
        "walksafe_attest_recovery_custody": (8, True),
        "walksafe_bind_admin_credential_issuer_key": (2, False),
        "walksafe_complete_admin_recovery_transaction": (8, True),
        "walksafe_expire_admin_recovery": (6, True),
        "walksafe_inspect_admin_recovery_completion": (5, True),
        "walksafe_inspect_admin_recovery_start": (5, True),
        "walksafe_issue_admin_reconfirmation": (14, True),
        "walksafe_issue_admin_recovery_transaction": (11, True),
        "walksafe_issue_admin_session": (14, True),
        "walksafe_lock_admin_device_proof_context": (8, True),
        "walksafe_lock_admin_original_access_session": (6, True),
        "walksafe_lock_admin_security_control": (2, True),
        "walksafe_touch_admin_session": (6, True),
        "walksafe_consume_admin_reconfirmation": (11, True),
        "walksafe_revoke_admin_session": (7, True),
        "walksafe_report_admin_lost_device": (7, True),
        "walksafe_require_recovery_custody_audit": (0, False),
        "walksafe_reset_recovery_custody": (7, True),
        "walksafe_resume_admin_recovery_transaction": (5, True),
        "walksafe_rotate_recovery_custody_capability": (4, False),
    }
    assert {row["proname"] for row in security_definer_contracts} == set(
        expected_security_definers
    )
    for row in security_definer_contracts:
        expected_argument_count, expected_runtime_execute = (
            expected_security_definers[row["proname"]]
        )
        assert row["pronargs"] == expected_argument_count
        assert row["prosecdef"] is True
        assert tuple(row["proconfig"] or ()) == (
            "search_path=pg_catalog, pg_temp",
        )
        assert row["public_execute"] is False
        assert row["runtime_execute"] is expected_runtime_execute
        assert row["unexpected_execute"] is False
    with SessionFactory() as db:
        bypass_control = db.execute(select(AdminSecurityControl)).scalar_one()
        private_fingerprint, private_issuer_fingerprint = db.execute(
            text(
                "SELECT totp_secret_fingerprint, issuer_key_sha256 "
                "FROM walksafe_recovery_custody_capabilities "
                "WHERE admin_id = :admin_id"
            ),
            {"admin_id": settings.admin_id},
        ).one()
    assert bypass_control.recovery_custody_reference_sha256 == sha256_text(
        RECOVERY_CUSTODY_REFERENCE
    )
    assert bypass_control.totp_secret_fingerprint == sha256_text(initial_secret)
    assert private_fingerprint == sha256_text(initial_secret)
    assert private_issuer_fingerprint == sha256_text(credential_issuer_key)
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
            credential_issuer_key=credential_issuer_key,
            device_id="android-device-0001",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=second_time,
        )
    assert revoked_error.value.code == "admin_session_invalid"

    recovery_time = second_time + timedelta(seconds=30)
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as missing_key:
        AdminSecurityService(db, settings).start_recovery(
            admin_id=settings.admin_id,
            recovery_code=recovery_code,
            device_id="android-device-0004",
            device_label="키 없는 복구 휴대전화",
            source="198.51.100.11",
            now=recovery_time,
        )
    assert missing_key.value.code == "admin_recovery_device_key_required"
    recovery_locked = Event()
    allow_recovery_commit = Event()
    revoke_started = Event()
    provisioning_started = Event()
    recovery_backend_pids: list[int] = []
    revoke_backend_pids: list[int] = []
    provisioning_backend_pids: list[int] = []
    other_recovery_public_key = ec.generate_private_key(
        ec.SECP256R1()
    ).public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )

    def start_recovery_while_holding_locks():
        def start(db):
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
        return run_as_runtime(start)

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

    def provision_other_device_during_recovery():
        with SessionFactory() as db:
            provisioning_backend_pids.append(
                int(db.execute(text("SELECT pg_backend_pid()")).scalar_one())
            )
            provisioning_started.set()
            try:
                return provision_admin_device_key(
                    db,
                    admin_id=settings.admin_id,
                    device_id="android-device-0004",
                    key_version=1,
                    public_key_spki_der=other_recovery_public_key,
                    now=recovery_time,
                )
            except AdminSecurityError as exc:
                return exc

    with ThreadPoolExecutor(max_workers=3) as executor:
        recovery_future = executor.submit(start_recovery_while_holding_locks)
        if not recovery_locked.wait(timeout=10):
            recovery_future.result(timeout=1)
            pytest.fail("recovery completed without reaching its audit lock point")
        revoke_future = executor.submit(revoke_during_recovery)
        provisioning_future = executor.submit(
            provision_other_device_during_recovery
        )
        try:
            assert revoke_started.wait(timeout=10)
            assert provisioning_started.wait(timeout=10)
            revoke_blockers = wait_for_postgres_blockers(revoke_backend_pids[0])
            provisioning_blockers = wait_for_postgres_blockers(
                provisioning_backend_pids[0]
            )
            assert recovery_backend_pids[0] in revoke_blockers
            assert recovery_backend_pids[0] in provisioning_blockers
        finally:
            allow_recovery_commit.set()
        recovery_grant = recovery_future.result(timeout=15)
        recovery_revoke_result = revoke_future.result(timeout=15)
        recovery_provisioning_result = provisioning_future.result(timeout=15)

    assert isinstance(recovery_grant, RecoveryGrant)
    assert isinstance(recovery_revoke_result, AdminSecurityError)
    assert (
        recovery_revoke_result.code
        == "admin_security_state_blocks_operation"
    )
    assert isinstance(recovery_provisioning_result, AdminSecurityError)
    assert (
        recovery_provisioning_result.code
        == "admin_device_key_provisioning_not_allowed"
    )
    with SessionFactory() as db:
        pending_code = db.execute(select(AdminSecurityRecoveryCode)).scalar_one()
        first_transaction = db.execute(
            select(AdminSecurityRecoveryTransaction)
        ).scalar_one()
        reset_control = db.execute(select(AdminSecurityControl)).scalar_one()
        initial_private_recovery = db.execute(
            text(
                "SELECT pending_recovery_token_sha256, "
                "pending_recovery_expires_at "
                "FROM walksafe_recovery_custody_capabilities "
                "WHERE admin_id = :admin_id"
            ),
            {"admin_id": settings.admin_id},
        ).one()
        original_transaction_id = first_transaction.id
        original_expiry = first_transaction.expires_at
    assert pending_code.used_at is None
    assert reset_control.recovery_custody_state == "UNATTESTED"
    assert reset_control.recovery_custody_attested_at is None
    assert reset_control.recovery_custody_reference_sha256 is None
    assert tuple(initial_private_recovery) == (
        first_transaction.recovery_token_sha256,
        original_expiry,
    )
    with SessionFactory() as db:
        recovery_revoked_keys = db.execute(
            select(AdminDeviceKey).order_by(AdminDeviceKey.device_id)
        ).scalars().all()
    assert len(recovery_revoked_keys) == 3
    assert all(
        key.status == "REVOKED" and key.revoked_at == recovery_time
        for key in recovery_revoked_keys
        if key.device_id != "android-device-0003"
    )
    recovery_device_key = next(
        key
        for key in recovery_revoked_keys
        if key.device_id == "android-device-0003"
    )
    assert (recovery_device_key.status, recovery_device_key.revoked_at) == (
        "ACTIVE",
        None,
    )
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as state_frozen:
        authorize_database_bound_high_risk_bearer(
            db,
            second_grant.access_token,
            "data.delete",
            now=recovery_time,
            method="POST",
            path="/admin/operations/data-deletions",
            nonce=RECONFIRM_NONCE,
            runtime_totp_secret=initial_secret,
            credential_issuer_key=credential_issuer_key,
            device_id="android-device-0002",
        )
    assert state_frozen.value.code == "admin_security_state_blocks_operation"
    with SessionFactory() as db:
        state_denial = db.execute(
            select(AdminSecurityAudit)
            .where(
                AdminSecurityAudit.action == "data.delete",
                AdminSecurityAudit.outcome == "DENIED",
            )
            .order_by(AdminSecurityAudit.sequence.desc())
        ).scalars().first()
    assert state_denial is not None
    assert state_denial.details["reason"] == "admin_security_state_blocks_operation"

    resumed_recovery = run_as_runtime(
        lambda db: AdminSecurityService(db, settings).start_recovery(
            admin_id=settings.admin_id,
            recovery_code=recovery_code,
            device_id="android-device-0003",
            device_label="복구 휴대전화",
            source="198.51.100.11",
            now=recovery_time + timedelta(seconds=1),
        )
    )
    assert resumed_recovery.recovery_token != recovery_grant.recovery_token
    with SessionFactory() as db:
        resumed_transactions = db.execute(
            select(AdminSecurityRecoveryTransaction)
        ).scalars().all()
        still_pending_code = db.execute(
            select(AdminSecurityRecoveryCode)
        ).scalar_one()
        resumed_private_recovery = db.execute(
            text(
                "SELECT pending_recovery_token_sha256, "
                "pending_recovery_expires_at "
                "FROM walksafe_recovery_custody_capabilities "
                "WHERE admin_id = :admin_id"
            ),
            {"admin_id": settings.admin_id},
        ).one()
    assert len(resumed_transactions) == 1
    assert resumed_transactions[0].id == original_transaction_id
    assert resumed_transactions[0].expires_at == original_expiry
    assert resumed_transactions[0].recovery_token_sha256 == sha256_text(
        resumed_recovery.recovery_token
    )
    assert still_pending_code.used_at is None
    assert tuple(resumed_private_recovery) == (
        sha256_text(resumed_recovery.recovery_token),
        original_expiry,
    )
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as frozen_error:
        authorize_admin_bearer(
            db,
            second_grant.access_token,
            runtime_totp_secret=initial_secret,
            credential_issuer_key=credential_issuer_key,
            device_id="android-device-0002",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=recovery_time,
        )
    assert frozen_error.value.code == "admin_session_invalid"

    restart_time = original_expiry + timedelta(seconds=1)
    with engine.begin() as connection:
        connection.execute(
            text(
                "UPDATE walksafe_recovery_custody_capabilities "
                "SET pending_recovery_expires_at = "
                "statement_timestamp() - interval '1 second' "
                "WHERE admin_id = :admin_id"
            ),
            {"admin_id": settings.admin_id},
        )
    restarted_recovery = run_as_runtime(
        lambda db: AdminSecurityService(db, settings).start_recovery(
            admin_id=settings.admin_id,
            recovery_code=recovery_code,
            device_id="android-device-0003",
            device_label="복구 휴대전화",
            source="198.51.100.11",
            now=restart_time,
        )
    )
    # Recovery completion is unreachable until device proof binds a new TOTP
    # candidate, even when the supplied recovery token is already expired.
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
    assert late_expired.value.code == "admin_recovery_invalid"
    with SessionFactory() as db:
        recovery_control = db.execute(select(AdminSecurityControl)).scalar_one()
        active_transactions = db.execute(
            select(AdminSecurityRecoveryTransaction).where(
                AdminSecurityRecoveryTransaction.completed_at.is_(None),
                AdminSecurityRecoveryTransaction.expires_at > restart_time,
            )
        ).scalars().all()
        restarted_private_recovery = db.execute(
            text(
                "SELECT pending_recovery_token_sha256, "
                "pending_recovery_expires_at "
                "FROM walksafe_recovery_custody_capabilities "
                "WHERE admin_id = :admin_id"
            ),
            {"admin_id": settings.admin_id},
        ).one()
    assert recovery_control.security_state == "RECOVERY_IN_PROGRESS"
    assert len(active_transactions) == 1
    assert active_transactions[0].recovery_token_sha256 == sha256_text(
        restarted_recovery.recovery_token
    )
    assert tuple(restarted_private_recovery) == (
        sha256_text(restarted_recovery.recovery_token),
        active_transactions[0].expires_at,
    )

    old_seed_completion_time = restart_time + timedelta(seconds=10)
    # Reusing the current TOTP seed is rejected by the same pre-lock candidate gate.
    with pytest.raises(AdminSecurityError) as old_seed_completion:
        run_as_runtime(
            lambda db: AdminSecurityService(db, settings).complete_recovery(
                recovery_token=restarted_recovery.recovery_token,
                new_password="replacement correct horse",
                totp_code=pyotp.TOTP(initial_secret).at(
                    old_seed_completion_time
                ),
                device_id="android-device-0003",
                device_label="복구 휴대전화",
                source="198.51.100.13",
                now=old_seed_completion_time,
            )
        )
    assert old_seed_completion.value.code == "admin_recovery_invalid"
    with SessionFactory() as db:
        unchanged_active_transaction = db.execute(
            select(AdminSecurityRecoveryTransaction).where(
                AdminSecurityRecoveryTransaction.recovery_token_sha256
                == sha256_text(restarted_recovery.recovery_token)
            )
        ).scalar_one()
    assert unchanged_active_transaction.completed_at is None

    with engine.connect() as connection:
        expired_rotation_attack = connection.begin()
        try:
            connection.execute(
                text(
                    "UPDATE walksafe_recovery_custody_capabilities "
                    "SET pending_recovery_expires_at = "
                    "statement_timestamp() - interval '1 second' "
                    "WHERE admin_id = :admin_id"
                ),
                {"admin_id": settings.admin_id},
            )
            connection.execute(
                text(
                    "UPDATE admin_security_recovery_transactions "
                    "SET expires_at = statement_timestamp() + interval '1 day' "
                    "WHERE recovery_token_sha256 = :token_sha256"
                ),
                {"token_sha256": sha256_text(restarted_recovery.recovery_token)},
            )
            connection.execute(text("SET LOCAL ROLE walksafe_backend_runtime"))
            with pytest.raises(SQLAlchemyError) as expired_rotation_rejected:
                connection.execute(
                    text(
                        "SELECT public."
                        "walksafe_rotate_recovery_custody_capability("
                        "CAST(:admin_id AS text), CAST(:recovery_token AS text), "
                        "CAST(:next_runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": settings.admin_id,
                        "recovery_token": restarted_recovery.recovery_token,
                        "next_runtime_totp_secret": replacement_secret,
                        "credential_issuer_key": credential_issuer_key,
                    },
                )
            assert getattr(
                expired_rotation_rejected.value.orig,
                "sqlstate",
                None,
            ) == "42501"
        finally:
            expired_rotation_attack.rollback()

    complete_time = restart_time + timedelta(seconds=30)
    settings.admin_totp_secret = replacement_secret
    with SessionFactory() as db:
        incomplete_recovery_count, active_recovery_count = db.execute(
            text(
                "SELECT count(*), count(*) FILTER (WHERE expires_at > :observed_at) "
                "FROM admin_security_recovery_transactions "
                "WHERE admin_id = :admin_id AND completed_at IS NULL"
            ),
            {
                "admin_id": settings.admin_id,
                "observed_at": complete_time - timedelta(seconds=2),
            },
        ).one()
    assert incomplete_recovery_count >= 2
    assert active_recovery_count == 1

    candidate_binding = run_as_runtime(
        lambda db: AdminDeviceProofService(db, settings).issue_challenge(
            purpose="RECOVERY_COMPLETE",
            action=None,
            admin_id=settings.admin_id,
            body_sha256=raw_body_sha256(b'{"candidate":"binding"}'),
            correlation_id=str(uuid.uuid4()),
            device_id="android-device-0003",
            device_key_marker=recovery_key_marker,
            device_key_version=1,
            method="POST",
            path="/admin/security/recovery/complete",
            query_sha256=canonical_admin_query_sha256(b""),
            read_purpose=None,
            session_id=None,
            identity=None,
            now=complete_time - timedelta(seconds=2),
        )
    )
    assert candidate_binding["purpose"] == "RECOVERY_COMPLETE"
    with SessionFactory() as db:
        assert db.execute(
            text(
                "SELECT pending_next_totp_fingerprint "
                "FROM walksafe_recovery_custody_capabilities "
                "WHERE admin_id = :admin_id"
            ),
            {"admin_id": settings.admin_id},
        ).scalar_one() == sha256_text(replacement_secret)

    def assert_recovery_candidate_rejected_without_waiting(
        runtime_totp_secret: str,
        issuer_key: str,
    ) -> None:
        def reject(db):
            db.execute(text("SET LOCAL lock_timeout = '250ms'"))
            with pytest.raises(SQLAlchemyError) as rejected:
                db.execute(
                    text(
                        "SELECT * FROM public."
                        "walksafe_lock_admin_device_proof_context("
                        "CAST(:admin_id AS text), CAST(:device_id AS text), 1, "
                        "CAST(:device_key_marker AS text), "
                        "CAST(:observed_at AS timestamptz), 'RECOVERY_COMPLETE', "
                        "CAST(:runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": settings.admin_id,
                        "device_id": "android-device-0003",
                        "device_key_marker": recovery_key_marker,
                        "observed_at": complete_time,
                        "runtime_totp_secret": runtime_totp_secret,
                        "credential_issuer_key": issuer_key,
                    },
                )
            assert getattr(rejected.value.orig, "sqlstate", None) == "42501"

        run_as_runtime(reject)

    with SessionFactory() as lock_holder:
        lock_holder.execute(select(AdminSecurityControl).with_for_update()).scalar_one()
        assert_recovery_candidate_rejected_without_waiting(
            "A" * 32,
            credential_issuer_key,
        )
        assert_recovery_candidate_rejected_without_waiting(
            initial_secret,
            credential_issuer_key,
        )
        assert_recovery_candidate_rejected_without_waiting(
            replacement_secret,
            wrong_credential_issuer_key,
        )
        lock_holder.rollback()
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

    completion_body = json.dumps(
        {
            "device_id": "android-device-0003",
            "device_label": "복구 휴대전화",
            "new_password": "replacement correct horse",
            "recovery_token": restarted_recovery.recovery_token,
            "totp_code": pyotp.TOTP(replacement_secret).at(complete_time),
        },
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    completion_correlation_id = uuid.uuid4()
    completion_challenge = run_as_runtime(
        lambda db: AdminDeviceProofService(db, settings).issue_challenge(
            purpose="RECOVERY_COMPLETE",
            action=None,
            admin_id=settings.admin_id,
            body_sha256=raw_body_sha256(completion_body),
            correlation_id=str(completion_correlation_id),
            device_id="android-device-0003",
            device_key_marker=recovery_key_marker,
            device_key_version=1,
            method="POST",
            path="/admin/security/recovery/complete",
            query_sha256=canonical_admin_query_sha256(b""),
            read_purpose=None,
            session_id=None,
            identity=None,
            now=complete_time - timedelta(seconds=1),
        )
    )
    with SessionFactory() as db:
        assert db.execute(
            text(
                "SELECT pending_next_totp_fingerprint "
                "FROM walksafe_recovery_custody_capabilities "
                "WHERE admin_id = :admin_id"
            ),
            {"admin_id": settings.admin_id},
        ).scalar_one() == sha256_text(replacement_secret)
    completion_signature = base64.urlsafe_b64encode(
        recovery_private_key.sign(
            completion_challenge["signing_payload"].encode("utf-8"),
            ec.ECDSA(hashes.SHA256()),
        )
    ).decode("ascii").rstrip("=")

    complete_barrier = Barrier(2)

    def concurrent_complete_recovery():
        def complete(db):
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
        return run_as_runtime(complete)

    class RecoveryRouteService:
        def complete_recovery(self, **kwargs):
            def complete(db):
                complete_barrier.wait(timeout=10)
                return AdminSecurityService(db, settings).complete_recovery(
                    **kwargs,
                    now=complete_time,
                )
            return run_as_runtime(complete)

    settings.admin_security_enabled = True
    settings.field_test_security_enabled = True
    settings.field_test_token = "field-token-for-recovery-integration"
    settings.admin_token = "unused-static-admin-token"
    settings.walksafe_environment = "test"
    settings.allow_insecure_local_dev = False
    settings.gateway_session_secret = ""
    settings.actor_rate_limit_store = "memory"
    settings.max_upload_bytes = 4096
    settings.max_report_metadata_bytes = 4096
    completion_app = FastAPI()
    completion_app.include_router(
        admin_security_api.create_router(
            settings,
            service_factory=RecoveryRouteService,
        )
    )

    def verify_completion_proof(_settings_value, **kwargs):
        def verify(db):
            result = verify_admin_device_proof(
                db,
                **kwargs,
                runtime_totp_secret=replacement_secret,
                credential_issuer_key=credential_issuer_key,
                now=complete_time,
            )
            db.commit()
            return result

        return run_as_runtime(verify)

    completion_app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=settings,
        admin_device_proof_verifier=verify_completion_proof,
    )
    completion_client = ASGITestClient(completion_app)

    def signed_http_complete_recovery():
        response = completion_client.post(
            "/admin/security/recovery/complete",
            content=completion_body,
            headers={
                "Content-Type": "application/json",
                "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
                "X-WalkSafe-Role": ADMIN_ROLE,
                "X-WalkSafe-Audience": ADMIN_AUDIENCE,
                "X-WalkSafe-Device-Id": "android-device-0003",
                "X-WalkSafe-Device-Challenge-Id": completion_challenge[
                    "challenge_id"
                ],
                "X-WalkSafe-Device-Signature": completion_signature,
                "X-WalkSafe-Correlation-Id": str(completion_correlation_id),
            },
        )
        payload = response.json()
        if response.status_code == 200:
            return SessionGrant(
                payload["access_token"],
                payload["security_state"],
                payload["current_session_id"],
            )
        detail = payload["detail"]
        return AdminSecurityError(
            detail["code"],
            detail["message"],
            status_code=response.status_code,
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        complete_futures = [
            executor.submit(signed_http_complete_recovery),
            executor.submit(concurrent_complete_recovery),
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
    with SessionFactory() as db:
        rotated_private_capability = db.execute(
            text(
                "SELECT totp_secret_fingerprint, "
                "pending_recovery_token_sha256, pending_recovery_expires_at, "
                "pending_next_totp_fingerprint "
                "FROM walksafe_recovery_custody_capabilities "
                "WHERE admin_id = :admin_id"
            ),
            {"admin_id": settings.admin_id},
        ).one()
    assert tuple(rotated_private_capability) == (
        sha256_text(replacement_secret),
        None,
        None,
        None,
    )
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as stale_replica:
        authorize_admin_bearer(
            db,
            recovered.access_token,
            runtime_totp_secret=initial_secret,
            credential_issuer_key=credential_issuer_key,
            device_id="android-device-0003",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=complete_time,
        )
    assert stale_replica.value.code == "admin_totp_configuration_mismatch"
    lost_device_time = complete_time + timedelta(seconds=30)
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as old_key_login:
        AdminSecurityService(db, settings).login(
            admin_id=settings.admin_id,
            password="replacement correct horse",
            totp_code=pyotp.TOTP(replacement_secret).at(lost_device_time),
            device_id="android-device-0002",
            device_label="분실 휴대전화",
            source="198.51.100.12",
            now=lost_device_time,
        )
    assert old_key_login.value.code == "admin_device_key_not_active"
    with SessionFactory() as db:
        db.add(
            AdminDeviceKey(
                admin_id=settings.admin_id,
                device_id="android-device-0002",
                key_version=2,
                public_key_spki_der=b"lost-device-replacement-public-key",
                key_marker="d" * 64,
                status="ACTIVE",
                created_at=lost_device_time,
            )
        )
        db.commit()
    with SessionFactory() as db:
        lost_device_grant = AdminSecurityService(db, settings).login(
            admin_id=settings.admin_id,
            password="replacement correct horse",
            totp_code=pyotp.TOTP(replacement_secret).at(lost_device_time),
            device_id="android-device-0002",
            device_label="분실 휴대전화",
            source="198.51.100.12",
            now=lost_device_time,
        )
    with SessionFactory() as db:
        recovered_identity = authorize_admin_bearer(
            db,
            recovered.access_token,
            runtime_totp_secret=replacement_secret,
            credential_issuer_key=credential_issuer_key,
            device_id="android-device-0003",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=lost_device_time,
        )
        db.commit()
    with SessionFactory() as db:
        AdminSecurityService(db, settings).report_lost_device(
            recovered_identity,
            "android-device-0002",
            now=lost_device_time,
        )
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as lost_session:
        authorize_admin_bearer(
            db,
            lost_device_grant.access_token,
            runtime_totp_secret=replacement_secret,
            credential_issuer_key=credential_issuer_key,
            device_id="android-device-0002",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=lost_device_time,
        )
    assert lost_session.value.code == "admin_session_invalid"
    with SessionFactory() as db:
        lost_key = db.execute(
            select(AdminDeviceKey).where(
                AdminDeviceKey.device_id == "android-device-0002",
                AdminDeviceKey.key_version == 2,
            )
        ).scalar_one()
        current_key = db.execute(
            select(AdminDeviceKey).where(
                AdminDeviceKey.device_id == "android-device-0003"
            )
        ).scalar_one()
        lost_session_row = db.get(
            AdminSecuritySession,
            uuid.UUID(lost_device_grant.current_session_id),
        )
        current_session_row = db.get(
            AdminSecuritySession,
            uuid.UUID(recovered.current_session_id),
        )
    assert (lost_key.status, lost_key.revoked_at) == ("REVOKED", lost_device_time)
    assert (current_key.status, current_key.revoked_at) == ("ACTIVE", None)
    assert (lost_session_row.revoked_at, lost_session_row.revoked_reason) == (
        lost_device_time,
        "device_reported_lost",
    )
    assert current_session_row.revoked_at is None

    high_risk_time = lost_device_time + timedelta(seconds=30)
    high_risk_nonce = "cmVjb3ZlcmVkLXJlcG9ydA"
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as missing_custody:
        authorize_admin_protected_work(
            db,
            recovered.access_token,
            "report.export",
            runtime_totp_secret=replacement_secret,
            credential_issuer_key=credential_issuer_key,
            now=high_risk_time,
            method="GET",
            path="/reports/export",
            device_id="android-device-0003",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
        )
    assert missing_custody.value.code == "admin_recovery_custody_required"
    with SessionFactory() as db, pytest.raises(AdminSecurityError) as offline_frozen:
        authorize_database_bound_high_risk_bearer(
            db,
            recovered.access_token,
            "report.export",
            now=high_risk_time,
            method="GET",
            path="/reports/export",
            nonce=RECONFIRM_NONCE,
            runtime_totp_secret=replacement_secret,
            credential_issuer_key=credential_issuer_key,
            device_id="android-device-0003",
        )
    assert offline_frozen.value.code == "admin_recovery_custody_required"
    with SessionFactory() as db:
        custody_denial = db.execute(
            select(AdminSecurityAudit)
            .where(
                AdminSecurityAudit.action == "report.export",
                AdminSecurityAudit.outcome == "DENIED",
            )
            .order_by(AdminSecurityAudit.sequence.desc())
        ).scalars().first()
    assert custody_denial is not None
    assert custody_denial.details["reason"] == "admin_recovery_custody_required"
    assert recovered.access_token not in repr(custody_denial.details)
    assert RECONFIRM_NONCE not in repr(custody_denial.details)
    with SessionFactory() as db:
        recovered_identity = authorize_admin_bearer(
            db,
            recovered.access_token,
            runtime_totp_secret=replacement_secret,
            credential_issuer_key=credential_issuer_key,
            device_id="android-device-0003",
            app_kind=ADMIN_APP_KIND,
            role=ADMIN_ROLE,
            audience=ADMIN_AUDIENCE,
            now=high_risk_time,
        )
        custody_state = AdminSecurityService(db, settings).attest_recovery_custody(
            recovered_identity,
            custody_reference=ROTATED_CUSTODY_REFERENCE,
            material_kind="SECURITY_KEY",
            storage_location="OFF_PHONE",
            separate_encrypted_backup_confirmed=True,
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
    assert custody_state["recovery_custody_state"] == "ATTESTED"
    assert reconfirmation.action == "report.export"
    with SessionFactory() as db:
        custody_control = db.execute(select(AdminSecurityControl)).scalar_one()
        custody_audit = db.execute(
            select(AdminSecurityAudit)
            .where(AdminSecurityAudit.action == "recovery.custody.attest")
            .order_by(AdminSecurityAudit.sequence.desc())
        ).scalars().first()
    assert custody_control.recovery_custody_reference_sha256 == sha256_text(
        ROTATED_CUSTODY_REFERENCE
    )
    assert custody_audit is not None
    assert ROTATED_CUSTODY_REFERENCE not in repr(custody_audit.details)

    consumed_identity = consume_admin_high_risk_reconfirmation(
        recovered.access_token,
        "report.export",
        runtime_totp_secret=replacement_secret,
        credential_issuer_key=credential_issuer_key,
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
            credential_issuer_key=credential_issuer_key,
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
            credential_issuer_key=credential_issuer_key,
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
            credential_issuer_key=credential_issuer_key,
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
                credential_issuer_key=credential_issuer_key,
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
                    credential_issuer_key=credential_issuer_key,
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
        protected_first_access_token = f"protected-first-{uuid.uuid4()}"
        protected_first_session_id = uuid.uuid4()
        db.add(
            AdminSecuritySession(
                id=protected_first_session_id,
                admin_id=settings.admin_id,
                device_id="android-device-0003",
                device_label=original_device_label,
                token_sha256=sha256_text(protected_first_access_token),
                issued_at=linearization_time,
                expires_at=linearization_time + timedelta(hours=1),
                step_up_verified_at=linearization_time,
                last_seen_at=linearization_time,
            )
        )
        db.commit()

    protected_first_locked = Event()
    allow_protected_first_commit = Event()
    recovery_after_protected_started = Event()
    protected_first_backend_pids: list[int] = []
    recovery_after_protected_backend_pids: list[int] = []
    def commit_protected_work_while_holding_control():
        with SessionFactory() as db:
            protected_first_backend_pids.append(
                int(db.execute(text("SELECT pg_backend_pid()")).scalar_one())
            )
            identity = authorize_admin_protected_work(
                db,
                protected_first_access_token,
                "report.export",
                runtime_totp_secret=replacement_secret,
                credential_issuer_key=credential_issuer_key,
                now=linearization_time,
                method="GET",
                path="/reports/export",
                device_id="android-device-0003",
                app_kind=ADMIN_APP_KIND,
                role=ADMIN_ROLE,
                audience=ADMIN_AUDIENCE,
            )
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
                .where(AdminSecuritySession.id == protected_first_session_id)
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

    assert protected_first_identity.session_id == protected_first_session_id
    with SessionFactory() as db:
        protected_first_control = db.execute(
            select(AdminSecurityControl).with_for_update()
        ).scalar_one()
        protected_first_session = db.execute(
            select(AdminSecuritySession)
            .where(AdminSecuritySession.id == protected_first_session_id)
            .with_for_update()
        ).scalar_one()
        assert protected_first_control.security_state == "RECOVERY_IN_PROGRESS"
        assert protected_first_session.revoked_at == linearization_time
        assert protected_first_session.last_seen_at == linearization_time
        assert protected_first_session.device_label == original_device_label
        protected_first_control.security_state = "NORMAL"
        protected_first_control.state_version += 1
        db.commit()

    assert high_risk_identity.session_id == uuid.UUID(recovered.current_session_id)
    assert control.security_state == "NORMAL"
    # Three pre-boundary failures are rolled back before they can create an
    # authentication-attempt record: expired/unbound, current-seed/unbound,
    # and the concurrent loser after the winner clears the candidate binding.
    assert len(complete_attempts) == 8
    assert sum(attempt.success for attempt in complete_attempts) == 1
    assert consumed_code.used_at == complete_time
    engine.dispose()
