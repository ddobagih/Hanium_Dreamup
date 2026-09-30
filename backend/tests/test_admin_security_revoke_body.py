"""Direct API regression for administrator session-revoke request bodies."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
import uuid

from fastapi import FastAPI
import pytest

from asgi_client import ASGITestClient
from backend.app.api.admin_security import create_router
from backend.app.field_test_security import FieldTestSecurityMiddleware
from backend.app.services.admin_device_proof import (
    AdminDeviceProofService,
    VerifiedAdminDeviceProof,
    canonical_admin_query_sha256,
    is_admin_device_proof_workflow_request,
    raw_body_sha256,
    validate_device_proof_challenge_binding,
)
from backend.app.services.admin_security import (
    ADMIN_APP_KIND,
    ADMIN_AUDIENCE,
    ADMIN_ROLE,
    AdminSecurityError,
    AdminSessionIdentity,
)


def test_revoke_accepts_empty_requests_and_rejects_other_bodies_before_service() -> None:
    session_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    now = datetime.now(timezone.utc)
    identity = AdminSessionIdentity(
        admin_id="walksafe.admin",
        session_id=session_id,
        device_id="android-device-0001",
        device_label="Admin phone",
        expires_at=now + timedelta(hours=1),
        step_up_verified_at=now,
    )
    service_calls: list[tuple[AdminSessionIdentity, uuid.UUID]] = []
    auth_calls: list[str] = []

    def revoke(caller: AdminSessionIdentity, target: uuid.UUID) -> dict[str, object]:
        service_calls.append((caller, target))
        return {
            "security_state": "NORMAL",
            "state_version": "7",
            "observed_at": "2026-07-22T12:00:00+00:00",
            "recovery_custody_state": "UNATTESTED",
            "recovery_custody_attested_at": None,
        }

    def authorize(_settings: object, **kwargs: object) -> AdminSessionIdentity:
        token = str(kwargs["raw_token"])
        auth_calls.append(token)
        if token != "valid-bearer-token":
            raise AdminSecurityError(
                "admin_session_invalid",
                "The administrator session is invalid or expired.",
                status_code=401,
            )
        return identity

    settings = SimpleNamespace(
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
    app = FastAPI()
    app.include_router(
        create_router(settings, service_factory=lambda: SimpleNamespace(revoke_session=revoke))
    )
    app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=settings,
        admin_session_authorizer=authorize,
        admin_security_denial_recorder=lambda **_kwargs: None,
    )
    client = ASGITestClient(app)
    path = f"/admin/security/sessions/{session_id}/revoke"
    headers = {
        "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
        "X-WalkSafe-Role": ADMIN_ROLE,
        "X-WalkSafe-Audience": ADMIN_AUDIENCE,
        "X-WalkSafe-Device-Id": identity.device_id,
        "Authorization": "Bearer valid-bearer-token",
    }

    no_body = client.post(path, headers=headers)
    empty_object = client.post(path, headers=headers, json={})
    assert no_body.status_code == 200
    assert empty_object.status_code == 200
    assert no_body.headers["Cache-Control"] == "no-store"
    assert empty_object.headers["Cache-Control"] == "no-store"
    assert service_calls == [(identity, session_id), (identity, session_id)]

    for request_kwargs in (
        {"json": {"unexpected": True}},
        {"content": b"{"},
        {"content": b"[]"},
        {"content": b"null"},
        {"content": b"x" * 4096},
    ):
        rejected = client.post(path, headers=headers, **request_kwargs)
        assert rejected.status_code == 422
        assert rejected.json()["detail"]["code"] == "admin_revoke_request_invalid"
        assert rejected.headers["Cache-Control"] == "no-store"
        assert service_calls == [(identity, session_id), (identity, session_id)]

    unauthorized = client.post(path, headers={
        key: value for key, value in headers.items() if key != "Authorization"
    })
    assert unauthorized.status_code == 401
    assert service_calls == [(identity, session_id), (identity, session_id)]
    assert auth_calls == ["valid-bearer-token"] * 7 + [""]


def test_other_session_revoke_requires_exact_device_proof_before_service() -> None:
    current_session_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    target_session_id = uuid.UUID("22222222-2222-4222-8222-222222222222")
    correlation_id = uuid.UUID("33333333-3333-4333-8333-333333333333")
    valid_challenge_id = uuid.UUID("44444444-4444-4444-8444-444444444444")
    wrong_challenge_id = uuid.UUID("55555555-5555-4555-8555-555555555555")
    now = datetime.now(timezone.utc)
    identity = AdminSessionIdentity(
        admin_id="walksafe.admin",
        session_id=current_session_id,
        device_id="android-device-0001",
        device_label="Admin phone",
        expires_at=now + timedelta(hours=1),
        step_up_verified_at=now,
    )
    settings = SimpleNamespace(
        admin_security_enabled=True,
        admin_device_proof_enabled=True,
        admin_totp_secret="JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
        admin_step_up_ttl_seconds=300,
        field_test_security_enabled=True,
        field_test_token="field-token-for-admin-security-tests",
        admin_token="legacy-static-admin-token-for-tests",
        walksafe_environment="test",
        allow_insecure_local_dev=False,
        gateway_session_secret="",
        actor_rate_limit_store="memory",
        max_upload_bytes=8 * 1024 * 1024,
        max_report_metadata_bytes=64 * 1024,
    )
    service_calls: list[tuple[AdminSessionIdentity, uuid.UUID]] = []
    verifier_calls: list[dict[str, object]] = []
    consumed: set[str] = set()

    def revoke(caller: AdminSessionIdentity, target: uuid.UUID) -> dict[str, object]:
        service_calls.append((caller, target))
        return {
            "security_state": "NORMAL",
            "state_version": "7",
            "observed_at": "2026-07-22T12:00:00+00:00",
            "recovery_custody_state": "UNATTESTED",
            "recovery_custody_attested_at": None,
        }

    def verify(_settings: object, **kwargs: object) -> VerifiedAdminDeviceProof:
        verifier_calls.append(kwargs)
        challenge_id = str(kwargs["challenge_id"])
        if challenge_id in consumed:
            raise AdminSecurityError(
                "admin_device_proof_replayed",
                "The administrator device proof challenge was already consumed.",
                status_code=409,
            )
        consumed.add(challenge_id)
        return VerifiedAdminDeviceProof(
            admin_id=str(kwargs["expected_admin_id"]),
            device_id=str(kwargs["expected_device_id"]),
            session_id=kwargs["expected_session_id"],
            challenge_id=uuid.UUID(challenge_id),
            correlation_id=uuid.UUID(str(kwargs["correlation_id"])),
            action=(
                "session.reauthenticate"
                if challenge_id == str(wrong_challenge_id)
                else str(kwargs["expected_action"])
            ),
            purpose=str(kwargs["expected_purpose"]),
            read_purpose=kwargs["expected_read_purpose"],
            method=str(kwargs["expected_method"]),
            path=str(kwargs["expected_path"]),
            body_sha256=raw_body_sha256(kwargs["raw_body"]),
            query_sha256=canonical_admin_query_sha256(kwargs["raw_query_string"]),
            device_key_marker="1" * 64,
            device_key_version=1,
            request_body=kwargs["raw_body"],
        )

    app = FastAPI()
    app.include_router(
        create_router(settings, service_factory=lambda: SimpleNamespace(revoke_session=revoke))
    )
    app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=settings,
        admin_session_authorizer=lambda _settings, **_kwargs: identity,
        admin_device_proof_verifier=verify,
        admin_security_denial_recorder=lambda **_kwargs: None,
    )
    client = ASGITestClient(app)
    path = f"/admin/security/sessions/{target_session_id}/revoke"
    headers = {
        "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
        "X-WalkSafe-Role": ADMIN_ROLE,
        "X-WalkSafe-Audience": ADMIN_AUDIENCE,
        "X-WalkSafe-Device-Id": identity.device_id,
        "Authorization": "Bearer valid-bearer-token",
    }

    missing = client.post(path, headers=headers)
    assert missing.status_code == 401
    assert missing.json()["detail"]["code"] == "admin_device_proof_required"
    assert missing.headers["Cache-Control"] == "no-store"
    assert service_calls == []
    assert verifier_calls == []

    def proof_headers(challenge_id: uuid.UUID) -> dict[str, str]:
        return {
            **headers,
            "X-WalkSafe-Device-Challenge-Id": str(challenge_id),
            "X-WalkSafe-Device-Signature": "signed-challenge",
            "X-WalkSafe-Correlation-Id": str(correlation_id),
        }

    valid = client.post(path, headers=proof_headers(valid_challenge_id))
    assert valid.status_code == 200
    assert service_calls == [(identity, target_session_id)]
    assert verifier_calls[0]["expected_purpose"] == "ACTION"
    assert verifier_calls[0]["expected_action"] == "session.revoke"
    assert verifier_calls[0]["expected_session_id"] == current_session_id
    assert verifier_calls[0]["expected_device_id"] == identity.device_id
    assert verifier_calls[0]["expected_path"] == path
    assert verifier_calls[0]["raw_body"] == b""
    assert verifier_calls[0]["raw_query_string"] == b""

    wrong = client.post(path, headers=proof_headers(wrong_challenge_id))
    assert wrong.status_code == 403
    assert wrong.json()["detail"]["code"] == "admin_device_proof_binding_mismatch"
    assert service_calls == [(identity, target_session_id)]

    replay = client.post(path, headers=proof_headers(valid_challenge_id))
    assert replay.status_code == 409
    assert replay.json()["detail"]["code"] == "admin_device_proof_replayed"
    assert replay.headers["Cache-Control"] == "no-store"
    assert service_calls == [(identity, target_session_id)]

    self_revoke = client.post(
        f"/admin/security/sessions/{current_session_id}/revoke",
        headers=headers,
        json={},
    )
    assert self_revoke.status_code == 200
    assert service_calls == [(identity, target_session_id), (identity, current_session_id)]
    assert len(verifier_calls) == 3


def test_revoke_action_challenge_rejects_wrong_binding_and_nonempty_body() -> None:
    session_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    target_session_id = uuid.UUID("22222222-2222-4222-8222-222222222222")
    now = datetime.now(timezone.utc)
    identity = AdminSessionIdentity(
        admin_id="walksafe.admin",
        session_id=session_id,
        device_id="android-device-0001",
        device_label="Admin phone",
        expires_at=now + timedelta(hours=1),
        step_up_verified_at=now,
    )
    path = f"/admin/security/sessions/{target_session_id}/revoke"
    binding = {
        "purpose": "ACTION",
        "action": "session.revoke",
        "admin_id": identity.admin_id,
        "device_id": identity.device_id,
        "session_id": str(session_id),
        "method": "POST",
        "path": path,
        "read_purpose": None,
        "identity": identity,
    }
    assert is_admin_device_proof_workflow_request("POST", path)
    assert validate_device_proof_challenge_binding(**binding) == session_id
    with pytest.raises(ValueError):
        validate_device_proof_challenge_binding(
            **{**binding, "action": "session.reauthenticate"}
        )
    with pytest.raises(ValueError):
        validate_device_proof_challenge_binding(
            **{**binding, "session_id": str(target_session_id)}
        )
    with pytest.raises(ValueError):
        validate_device_proof_challenge_binding(
            **{**binding, "path": f"/admin/security/sessions/{session_id}"}
        )
    with pytest.raises(AdminSecurityError) as error:
        AdminDeviceProofService(None).issue_challenge(
            **binding,
            body_sha256=raw_body_sha256(b"{}"),
            query_sha256=canonical_admin_query_sha256(b""),
            correlation_id="33333333-3333-4333-8333-333333333333",
            device_key_marker="1" * 64,
            device_key_version=1,
        )
    assert error.value.code == "admin_device_proof_binding_invalid"
