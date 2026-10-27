"""Sweep wave22 admin regressions (S18-2, S18-3, S18-31, S18-36).

Non-PostgreSQL tests use scripted sessions; PostgreSQL tests run only when
WALKSAFE_TEST_DATABASE_URL points at an isolated migrated test database.
"""

from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace
from typing import Any
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI
import pyotp
import pytest
from sqlalchemy import create_engine, select, text
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import sessionmaker

from asgi_client import ASGITestClient
from backend.app import database as database_api
from backend.app.api import admin_security as admin_security_api
from backend.app.field_test_security import (
    FIELD_TEST_ROUTE_POLICIES,
    requires_admin_device_proof,
)
from backend.app.models import (
    AdminDeviceKey,
    AdminSecurityAudit,
    AdminSecurityControl,
    AdminSecuritySession,
    ReportReviewDecision,
)
from backend.app.services import admin_report_workflow
from backend.app.services.admin_device_proof import (
    ADMIN_DEVICE_KEY_ROTATION_SCHEMA_VERSION,
    AdminDeviceProofService,
    VerifiedAdminDeviceProof,
    admin_device_key_rotation_payload,
    canonical_admin_query_sha256,
    provision_admin_device_key,
    raw_body_sha256,
    validate_device_proof_challenge_binding,
    verify_admin_device_key_rotation_signature,
)
from backend.app.services.admin_report_workflow import (
    AdminReportWorkflowError,
    get_report_review_decision,
)
from backend.app.services.admin_security import (
    ADMIN_APP_KIND,
    ADMIN_AUDIENCE,
    ADMIN_ROLE,
    AdminSecurityError,
    AdminSecurityService,
    AdminSessionIdentity,
    authorize_admin_bearer,
    classify_admin_operation,
    provision_admin_security,
)
from scripts import provision_walksafe_admin_device_key as provision_cli


ADMIN_ID = "walksafe.admin"
DEVICE_ID = "android-device-0001"
SESSION_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
CORRELATION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
REPORT_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")
DECISION_ID = uuid.UUID("5555aaaa-5555-4555-8555-55555555bbbb")
ROTATION_PATH = "/admin/security/device-keys/rotation/confirm"
OBSERVED_AT = datetime(2026, 9, 29, 3, 0, 0, tzinfo=timezone.utc)
_PG = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _private_key() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


def _spki(private_key: ec.EllipticCurvePrivateKey) -> bytes:
    return private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _sign(private_key: ec.EllipticCurvePrivateKey, payload: str) -> str:
    return _b64(private_key.sign(payload.encode("utf-8"), ec.ECDSA(hashes.SHA256())))


def _identity(session_id: uuid.UUID = SESSION_ID) -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id=ADMIN_ID,
        session_id=session_id,
        device_id=DEVICE_ID,
        device_label="관리자 휴대전화",
        expires_at=OBSERVED_AT + timedelta(hours=1),
        step_up_verified_at=OBSERVED_AT,
    )


class _Result:
    def __init__(self, values: list[Any] | None = None) -> None:
        self.values = [] if values is None else values

    def scalar_one_or_none(self) -> Any:
        return self.values[0] if self.values else None

    def scalars(self) -> "_Result":
        return self

    def all(self) -> list[Any]:
        return list(self.values)


class _ScriptedSession:
    def __init__(self, results: list[_Result]) -> None:
        self.results = list(results)
        self.added: list[Any] = []
        self.commits = 0
        self.rollbacks = 0
        self.flushes = 0
        self.closed = False

    def get_bind(self) -> Any:
        return None

    def execute(self, _statement: Any, _parameters: Any = None) -> _Result:
        return self.results.pop(0)

    def add(self, value: Any) -> None:
        if getattr(value, "id", None) is None:
            value.id = uuid.uuid4()
        self.added.append(value)

    def flush(self) -> None:
        self.flushes += 1

    def commit(self) -> None:
        self.commits += 1

    def rollback(self) -> None:
        self.rollbacks += 1

    def close(self) -> None:
        self.closed = True


def _control(state: str = "NORMAL") -> AdminSecurityControl:
    return AdminSecurityControl(admin_id=ADMIN_ID, singleton_scope=True, security_state=state)


def _active_key(der: bytes, version: int = 1) -> AdminDeviceKey:
    return AdminDeviceKey(
        id=uuid.uuid4(),
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=version,
        public_key_spki_der=der,
        key_marker=hashlib.sha256(der).hexdigest(),
        status="ACTIVE",
        created_at=OBSERVED_AT,
    )


# --- S18-3: two-phase provisioning ------------------------------------------


def test_s18_3_rotation_keeps_active_key_and_stages_new_version_as_pending() -> None:
    first = _active_key(_spki(_private_key()))
    db = _ScriptedSession([_Result([_control()]), _Result([first])])

    staged = provision_admin_device_key(
        db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=2,
        public_key_spki_der=_spki(_private_key()),
        security_level="TRUSTED_ENVIRONMENT",
    )

    assert staged.status == "PENDING" and staged.idempotent is False
    assert first.status == "ACTIVE" and first.revoked_at is None
    assert db.added[0].status == "PENDING"
    assert db.added[0].security_level == "TRUSTED_ENVIRONMENT"


def test_s18_3_newer_pending_supersedes_stale_pending_and_replace_is_explicit() -> None:
    first = _active_key(_spki(_private_key()))
    stale = _active_key(_spki(_private_key()), version=2)
    stale.status = "PENDING"
    db = _ScriptedSession([_Result([_control()]), _Result([stale, first])])
    staged = provision_admin_device_key(
        db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=3,
        public_key_spki_der=_spki(_private_key()),
        security_level="STRONGBOX",
    )
    assert staged.status == "PENDING"
    assert stale.status == "REVOKED" and stale.revoked_at is not None
    assert first.status == "ACTIVE"

    replaced_control = _control()
    replaced_control.state_version = 1
    # S18-3-F2(b): the emergency path also reads the device sessions and the
    # previous audit row (see the dedicated emergency test below).
    replaced_db = _ScriptedSession(
        [_Result([replaced_control]), _Result([first]), _Result([]), _Result([])]
    )
    replaced = provision_admin_device_key(
        replaced_db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=4,
        public_key_spki_der=_spki(_private_key()),
        security_level="TRUSTED_ENVIRONMENT",
        replace_active=True,
    )
    assert replaced.status == "ACTIVE"
    assert first.status == "REVOKED"


def test_s18_3_repeated_pending_registration_is_idempotent() -> None:
    first = _active_key(_spki(_private_key()))
    pending_der = _spki(_private_key())
    pending = _active_key(pending_der, version=2)
    pending.status = "PENDING"
    pending.security_level = "TRUSTED_ENVIRONMENT"
    db = _ScriptedSession([_Result([_control()]), _Result([pending, first])])
    repeated = provision_admin_device_key(
        db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=2,
        public_key_spki_der=pending_der,
        security_level="TRUSTED_ENVIRONMENT",
    )
    assert (repeated.status, repeated.idempotent) == ("PENDING", True)
    assert db.added == []


def _session_row(device_id: str = DEVICE_ID) -> AdminSecuritySession:
    return AdminSecuritySession(
        id=uuid.uuid4(),
        admin_id=ADMIN_ID,
        device_id=device_id,
        device_label="관리자 휴대전화",
        token_sha256="a" * 64,
        issued_at=OBSERVED_AT - timedelta(hours=1),
        expires_at=OBSERVED_AT + timedelta(hours=1),
        last_seen_at=OBSERVED_AT - timedelta(minutes=5),
    )


def test_s18_3_emergency_replace_active_revokes_device_sessions_and_is_audited() -> None:
    """S18-3-F2(b): the explicit emergency path leaves a trace and ends old sessions."""

    first = _active_key(_spki(_private_key()))
    stale = _active_key(_spki(_private_key()), version=2)
    stale.status = "PENDING"
    control = _control()
    control.state_version = 7
    device_session = _session_row()
    db = _ScriptedSession(
        [
            _Result([control]),
            _Result([stale, first]),
            _Result([device_session]),
            _Result([]),  # previous audit row for the hash chain
        ]
    )
    replaced = provision_admin_device_key(
        db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=3,
        public_key_spki_der=_spki(_private_key()),
        security_level="TRUSTED_ENVIRONMENT",
        replace_active=True,
        now=OBSERVED_AT,
    )

    assert (replaced.status, replaced.idempotent) == ("ACTIVE", False)
    assert first.status == "REVOKED" and stale.status == "REVOKED"
    assert device_session.revoked_at == OBSERVED_AT
    assert device_session.revoked_reason == "administrator_revoked"
    assert control.state_version == 8
    audits = [item for item in db.added if isinstance(item, AdminSecurityAudit)]
    assert len(audits) == 1
    assert (audits[0].action, audits[0].outcome) == ("device_key.replace_active", "SUCCESS")
    assert audits[0].device_id == DEVICE_ID
    assert audits[0].details == {
        "key_version": 3,
        "revoked_active_key_count": 1,
        "revoked_pending_key_count": 1,
        "revoked_session_count": 1,
        "security_level": "TRUSTED_ENVIRONMENT",
    }
    assert db.results == [] and db.commits == 1


def test_s18_3_ordinary_rotation_and_first_registration_do_not_touch_sessions() -> None:
    first = _active_key(_spki(_private_key()))
    control = _control()
    staged_db = _ScriptedSession([_Result([control]), _Result([first])])
    provision_admin_device_key(
        staged_db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=2,
        public_key_spki_der=_spki(_private_key()),
        security_level="STRONGBOX",
        replace_active=False,
    )
    first_db = _ScriptedSession([_Result([_control()]), _Result([])])
    provision_admin_device_key(
        first_db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=1,
        public_key_spki_der=_spki(_private_key()),
        security_level="STRONGBOX",
        replace_active=True,
    )
    for db in (staged_db, first_db):
        assert not [item for item in db.added if isinstance(item, AdminSecurityAudit)]
        assert db.results == []
    assert control.state_version in (None, 1)


# --- S18-2: recorded security level ------------------------------------------


def test_s18_2_new_key_versions_require_a_recorded_security_level() -> None:
    """S18-2-F1(b): NULL security_level remains only on pre-migration rows."""

    db = _ScriptedSession([_Result([_control()]), _Result([])])
    with pytest.raises(ValueError, match="security_level is required"):
        provision_admin_device_key(
            db,
            admin_id=ADMIN_ID,
            device_id=DEVICE_ID,
            key_version=1,
            public_key_spki_der=_spki(_private_key()),
        )
    assert db.added == [] and db.rollbacks == 1 and db.commits == 0

    first = _active_key(_spki(_private_key()))
    rotate_db = _ScriptedSession([_Result([_control()]), _Result([first])])
    with pytest.raises(ValueError, match="security_level is required"):
        provision_admin_device_key(
            rotate_db,
            admin_id=ADMIN_ID,
            device_id=DEVICE_ID,
            key_version=2,
            public_key_spki_der=_spki(_private_key()),
            replace_active=True,
        )
    assert first.status == "ACTIVE" and first.revoked_at is None
    assert rotate_db.added == [] and rotate_db.commits == 0

    legacy_der = _spki(_private_key())
    legacy = _active_key(legacy_der)
    assert legacy.security_level is None
    legacy_db = _ScriptedSession([_Result([_control()]), _Result([legacy])])
    repeated = provision_admin_device_key(
        legacy_db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=1,
        public_key_spki_der=legacy_der,
    )
    assert (repeated.idempotent, repeated.security_level) == (True, None)


@pytest.mark.parametrize("level", ["SOFTWARE", "UNKNOWN", "trusted_environment", ""])
def test_s18_2_provisioning_rejects_non_hardware_security_levels(level: str) -> None:
    db = _ScriptedSession([])
    with pytest.raises(ValueError, match="security_level"):
        provision_admin_device_key(
            db,
            admin_id=ADMIN_ID,
            device_id=DEVICE_ID,
            key_version=1,
            public_key_spki_der=_spki(_private_key()),
            security_level=level,
        )
    assert db.commits == 0


def _cli_argv(der: bytes, *extra: str) -> list[str]:
    return [
        "--admin-id", ADMIN_ID,
        "--device-id", DEVICE_ID,
        "--key-version", "1",
        "--expected-key-marker", hashlib.sha256(der).hexdigest(),
        "--public-key-spki-base64url", _b64(der),
        *extra,
    ]


def test_s18_2_cli_requires_hardware_security_level_before_database(
    capsys: pytest.CaptureFixture[str],
) -> None:
    der = _spki(_private_key())
    for extra in ((), ("--security-level", "SOFTWARE")):
        with pytest.raises(SystemExit) as rejected:
            provision_cli.main(
                _cli_argv(der, *extra),
                session_factory=lambda: pytest.fail("database must not be opened"),
            )
        assert rejected.value.code == 2
        assert "--security-level" in capsys.readouterr().err


def test_s18_2_cli_records_security_level(capsys: pytest.CaptureFixture[str]) -> None:
    der = _spki(_private_key())
    db = _ScriptedSession([_Result([_control()]), _Result([])])
    status = provision_cli.main(
        _cli_argv(der, "--security-level", "STRONGBOX"),
        session_factory=lambda: db,
    )
    printed = json.loads(capsys.readouterr().out)
    assert status == 0
    assert printed["security_level"] == "STRONGBOX" and printed["status"] == "ACTIVE"
    assert db.added[0].security_level == "STRONGBOX"


# --- S18-3: rotation payload, route registration and route binding ------------


def test_s18_3_rotation_payload_is_canonical_and_only_the_pending_key_verifies() -> None:
    old_key, new_key = _private_key(), _private_key()
    new_der = _spki(new_key)
    payload = admin_device_key_rotation_payload(
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        session_id=SESSION_ID,
        correlation_id=CORRELATION_ID,
        previous_key_version=1,
        previous_key_marker=hashlib.sha256(_spki(old_key)).hexdigest(),
        key_version=2,
        key_marker=hashlib.sha256(new_der).hexdigest(),
    )
    decoded = json.loads(payload)
    assert decoded["schema_version"] == ADMIN_DEVICE_KEY_ROTATION_SCHEMA_VERSION
    assert payload == json.dumps(decoded, separators=(",", ":"), sort_keys=True)
    verify_admin_device_key_rotation_signature(new_der, payload, _sign(new_key, payload))
    with pytest.raises(AdminSecurityError) as wrong_key:
        verify_admin_device_key_rotation_signature(new_der, payload, _sign(old_key, payload))
    assert wrong_key.value.code == "admin_device_key_rotation_signature_invalid"
    tampered = payload.replace('"key_version":2', '"key_version":3')
    with pytest.raises(AdminSecurityError):
        verify_admin_device_key_rotation_signature(new_der, tampered, _sign(new_key, payload))


def test_s18_3_rotation_payload_matches_the_android_canonical_bytes() -> None:
    # Same inputs and expected bytes as AdminDeviceKeySweepW22Test (admin app).
    device_id = "admin-device-12345678-1234-1234-1234-123456789abc"
    payload = admin_device_key_rotation_payload(
        admin_id="admin-01",
        device_id=device_id,
        session_id=SESSION_ID,
        correlation_id=uuid.UUID("22222222-2222-4222-8222-222222222222"),
        previous_key_version=1,
        previous_key_marker="a" * 64,
        key_version=2,
        key_marker="b" * 64,
    )
    assert payload == (
        '{"admin_id":"admin-01","correlation_id":"22222222-2222-4222-8222-222222222222",'
        f'"device_id":"{device_id}","key_marker":"{"b" * 64}","key_version":2,'
        f'"previous_key_marker":"{"a" * 64}","previous_key_version":1,'
        '"schema_version":"walksafe.admin-device-key-rotation.v1",'
        f'"session_id":"{SESSION_ID}"' + "}"
    )


def test_s18_3_rotation_confirm_is_a_registered_device_proof_write() -> None:
    operation = classify_admin_operation("POST", ROTATION_PATH)
    assert operation is not None
    assert (operation.action, operation.risk) == ("device_key.rotation.confirm", "STANDARD")
    assert requires_admin_device_proof(ROTATION_PATH, "POST") is True
    assert requires_admin_device_proof(ROTATION_PATH, "GET") is False
    assert ("POST", ROTATION_PATH, admin_security_api_access()) in FIELD_TEST_ROUTE_POLICIES
    validate_device_proof_challenge_binding(
        purpose="ACTION",
        action="device_key.rotation.confirm",
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        session_id=str(SESSION_ID),
        method="POST",
        path=ROTATION_PATH,
        read_purpose=None,
        identity=_identity(),
    )


def admin_security_api_access():
    from backend.app.field_test_security import FieldTestAccess

    return FieldTestAccess.ADMIN


class _RecordingRotationService:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    def confirm_device_key_rotation(self, identity, **kwargs):
        self.calls.append({"identity": identity, **kwargs})
        return {
            "device_id": identity.device_id,
            "key_version": kwargs["key_version"],
            "key_marker": kwargs["key_marker"],
            "previous_key_version": kwargs["previous_key_version"],
            "status": "ACTIVE",
            "idempotent": False,
        }


def _rotation_app(service: _RecordingRotationService, proof: Any) -> FastAPI:
    app = FastAPI()
    settings = SimpleNamespace(admin_security_enabled=True, admin_id=ADMIN_ID)
    app.include_router(
        admin_security_api.create_router(settings, service_factory=lambda: service)
    )

    @app.middleware("http")
    async def inject(request, call_next):
        request.state.admin_security_identity = _identity()
        if proof is not None:
            request.state.admin_device_proof = proof
        return await call_next(request)

    return app


def _proof(**overrides: Any) -> VerifiedAdminDeviceProof:
    fields: dict[str, Any] = {
        "admin_id": ADMIN_ID,
        "device_id": DEVICE_ID,
        "session_id": SESSION_ID,
        "challenge_id": uuid.uuid4(),
        "correlation_id": CORRELATION_ID,
        "action": "device_key.rotation.confirm",
        "purpose": "ACTION",
        "read_purpose": None,
        "method": "POST",
        "path": ROTATION_PATH,
        "body_sha256": raw_body_sha256(b"{}"),
        "query_sha256": canonical_admin_query_sha256(b""),
        "device_key_marker": "a" * 64,
        "device_key_version": 1,
    }
    fields.update(overrides)
    return VerifiedAdminDeviceProof(**fields)


def _rotation_body(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "previous_key_version": 1,
        "previous_key_marker": "a" * 64,
        "key_version": 2,
        "key_marker": "b" * 64,
        "rotation_signature": "A" * 96,
    }
    body.update(overrides)
    return body


def test_s18_3_rotation_route_passes_the_old_key_proof_binding_to_the_service() -> None:
    service = _RecordingRotationService()
    client = ASGITestClient(_rotation_app(service, _proof()))
    response = client.post(ROTATION_PATH, json=_rotation_body())
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "ACTIVE"
    call = service.calls[0]
    assert call["proof_key_version"] == 1 and call["proof_key_marker"] == "a" * 64
    assert call["proof_correlation_id"] == CORRELATION_ID


@pytest.mark.parametrize(
    "proof",
    [
        None,
        _proof(action="recovery.custody.attest"),
        _proof(path="/admin/security/recovery-custody/attest"),
        _proof(session_id=uuid.UUID("99999999-9999-4999-8999-999999999999")),
    ],
)
def test_s18_3_rotation_route_requires_its_exact_device_proof(proof: Any) -> None:
    service = _RecordingRotationService()
    response = ASGITestClient(_rotation_app(service, proof)).post(
        ROTATION_PATH, json=_rotation_body()
    )
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "admin_device_proof_required"
    assert service.calls == []


@pytest.mark.parametrize(
    "body",
    [
        _rotation_body(extra=True),
        _rotation_body(key_version=1),
        _rotation_body(key_marker="B" * 64),
        _rotation_body(rotation_signature="short"),
    ],
)
def test_s18_3_rotation_request_is_strict(body: dict[str, Any]) -> None:
    service = _RecordingRotationService()
    response = ASGITestClient(_rotation_app(service, _proof())).post(ROTATION_PATH, json=body)
    assert response.status_code == 422
    assert service.calls == []


def test_s18_3_service_rejects_a_rotation_not_signed_by_the_current_key() -> None:
    service = AdminSecurityService(_ScriptedSession([]), SimpleNamespace(admin_id=ADMIN_ID))
    with pytest.raises(AdminSecurityError) as rejected:
        service.confirm_device_key_rotation(
            _identity(),
            proof_key_version=2,
            proof_key_marker="b" * 64,
            proof_correlation_id=CORRELATION_ID,
            previous_key_version=1,
            previous_key_marker="a" * 64,
            key_version=2,
            key_marker="b" * 64,
            rotation_signature="A" * 96,
        )
    assert rejected.value.code == "admin_device_key_rotation_binding_invalid"


# --- S18-36: decision lookup -----------------------------------------------------


class _LookupDb:
    def __init__(self, report: Any, decision: Any) -> None:
        self.report = report
        self.decision = decision

    def scalar(self, statement):
        description, = statement.column_descriptions
        assert description["expr"] is admin_report_workflow.Report
        assert statement.compile().params["id_1"] == REPORT_ID
        sql = str(statement)
        assert "NOT (EXISTS (" in sql and "account_deletion_tombstones" in sql
        return self.report

    def get(self, model: Any, key: Any) -> Any:
        if model is admin_report_workflow.Report:
            return self.report if key == REPORT_ID else None
        if model is ReportReviewDecision:
            return self.decision if key == DECISION_ID else None
        raise AssertionError(model)


def test_s18_36_lookup_distinguishes_recorded_absent_and_foreign_decisions() -> None:
    decision = SimpleNamespace(id=DECISION_ID, report_id=REPORT_ID, admin_id=ADMIN_ID)
    report = SimpleNamespace(id=REPORT_ID)
    found = get_report_review_decision(
        _LookupDb(report, decision),
        report_id=REPORT_ID,
        decision_id=DECISION_ID,
        admin_id=ADMIN_ID,
    )
    assert found is decision
    cases = [
        (_LookupDb(report, None), ADMIN_ID, "review_decision_not_found"),
        (_LookupDb(report, decision), "other.admin", "review_decision_not_found"),
        (
            _LookupDb(
                report,
                SimpleNamespace(id=DECISION_ID, report_id=uuid.uuid4(), admin_id=ADMIN_ID),
            ),
            ADMIN_ID,
            "review_decision_not_found",
        ),
        (_LookupDb(None, decision), ADMIN_ID, "report_not_found"),
    ]
    for db, admin_id, code in cases:
        with pytest.raises(AdminReportWorkflowError) as missing:
            get_report_review_decision(
                db, report_id=REPORT_ID, decision_id=DECISION_ID, admin_id=admin_id
            )
        assert (missing.value.code, missing.value.status_code) == (code, 404)


def test_s18_36_lookup_route_is_a_read_proof_route_after_history() -> None:
    from backend.app.main import app

    path = f"/reports/{REPORT_ID}/review-decisions/{DECISION_ID}"
    assert requires_admin_device_proof(path, "GET") is True
    assert requires_admin_device_proof(path, "POST") is False
    assert requires_admin_device_proof(
        f"/reports/{REPORT_ID}/review-decisions/{str(DECISION_ID).upper()}", "GET"
    ) is False
    # S18-36-F1: the proof path accepts exactly the route's canonical v1-5 UUIDs,
    # so a non-canonical decision_id is not issued a challenge only to hit 422.
    for non_canonical in (
        "0f8fad5b-d9cb-069f-a165-70867728950e",  # version nibble 0
        "0f8fad5b-d9cb-669f-a165-70867728950e",  # version nibble 6
        "0f8fad5b-d9cb-469f-c165-70867728950e",  # variant nibble c
    ):
        assert requires_admin_device_proof(
            f"/reports/{REPORT_ID}/review-decisions/{non_canonical}", "GET"
        ) is False
    templates = [getattr(route, "path", None) for route in app.routes]
    history = templates.index("/reports/{report_id}/review-decisions/history")
    lookup = templates.index("/reports/{report_id}/review-decisions/{decision_id}")
    assert history < lookup
    validate_device_proof_challenge_binding(
        purpose="ACTION",
        action=None,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        session_id=str(SESSION_ID),
        method="GET",
        path=path,
        read_purpose="report.review_decisions",
        identity=_identity(),
    )


# --- PostgreSQL: S18-3 end to end and S18-31 regression ---------------------------


def _pg_environment(tmp_path: Path, *, device_proof_enabled: bool):
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True)
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)

    def run_as_runtime(callback):
        with engine.connect() as runtime_connection:
            runtime_connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
            runtime_connection.commit()
            try:
                with sessionmaker(bind=runtime_connection, expire_on_commit=False)() as runtime_db:
                    return callback(runtime_db)
            finally:
                if runtime_connection.in_transaction():
                    runtime_connection.rollback()
                runtime_connection.execute(text("RESET SESSION AUTHORIZATION"))
                runtime_connection.commit()

    totp_secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    issuer_key = _b64(bytes(range(32)))
    issuer_directory = tmp_path / "admin-issuer-authority"
    issuer_directory.mkdir(mode=0o700)
    issuer_key_file = issuer_directory / "issuer.key"
    issuer_key_file.write_text(issuer_key, encoding="ascii")
    issuer_key_file.chmod(0o600)
    settings = SimpleNamespace(
        admin_id=ADMIN_ID,
        admin_device_proof_enabled=device_proof_enabled,
        admin_credential_issuer_key_file=issuer_key_file,
        admin_totp_secret=totp_secret,
        admin_session_ttl_seconds=43_200,
        admin_step_up_ttl_seconds=300,
        admin_recovery_ttl_seconds=900,
        admin_auth_rate_limit_attempts=5,
        admin_auth_rate_limit_window_seconds=300,
        walksafe_environment="test",
    )
    return engine, SessionFactory, run_as_runtime, settings, totp_secret, issuer_key


def _challenge(run_as_runtime, settings, identity, *, version: int, marker: str, at: datetime):
    return run_as_runtime(
        lambda db: AdminDeviceProofService(db, settings).issue_challenge(
            purpose="ACTION",
            action="device_key.rotation.confirm",
            admin_id=ADMIN_ID,
            body_sha256=raw_body_sha256(b"{}"),
            correlation_id=str(uuid.uuid4()),
            device_id=DEVICE_ID,
            device_key_marker=marker,
            device_key_version=version,
            method="POST",
            path=ROTATION_PATH,
            query_sha256=canonical_admin_query_sha256(b""),
            read_purpose=None,
            session_id=str(identity.session_id),
            identity=identity,
            now=at,
        )
    )


@_PG
def test_s18_3_postgres_rotation_revokes_old_key_only_after_new_key_proves_possession(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    clean_test_storage,
) -> None:
    clean_test_storage()
    engine, SessionFactory, run_as_runtime, settings, totp_secret, issuer_key = _pg_environment(
        tmp_path, device_proof_enabled=True
    )
    monkeypatch.setattr(database_api, "SessionLocal", SessionFactory)
    started_at = datetime.now(timezone.utc).replace(microsecond=0)
    old_key, new_key = _private_key(), _private_key()
    old_der, new_der = _spki(old_key), _spki(new_key)
    old_marker, new_marker = (hashlib.sha256(value).hexdigest() for value in (old_der, new_der))
    try:
        with SessionFactory() as db:
            provision_admin_security(
                db,
                admin_id=ADMIN_ID,
                password="rotation correct horse",
                totp_secret=totp_secret,
                recovery_codes=["RECOVERY-2026-ROTATION-0001"],
                credential_issuer_key=issuer_key,
                now=started_at,
            )
        with SessionFactory() as db:
            first = provision_admin_device_key(
                db, admin_id=ADMIN_ID, device_id=DEVICE_ID, key_version=1,
                public_key_spki_der=old_der, security_level="TRUSTED_ENVIRONMENT",
                now=started_at,
            )
        assert first.status == "ACTIVE"
        grant = run_as_runtime(
            lambda db: AdminSecurityService(db, settings).login(
                admin_id=ADMIN_ID,
                password="rotation correct horse",
                totp_code=pyotp.TOTP(totp_secret).at(started_at),
                device_id=DEVICE_ID,
                device_label="관리자 휴대전화",
                source="198.51.100.30",
                now=started_at,
            )
        )
        with SessionFactory() as db:
            identity = authorize_admin_bearer(
                db, grant.access_token, runtime_totp_secret=totp_secret,
                credential_issuer_key=issuer_key, device_id=DEVICE_ID,
                app_kind=ADMIN_APP_KIND, role=ADMIN_ROLE, audience=ADMIN_AUDIENCE,
                now=started_at,
            )
            db.commit()

        def confirm(signature: str, at: datetime):
            return run_as_runtime(
                lambda db: AdminSecurityService(db, settings).confirm_device_key_rotation(
                    identity,
                    proof_key_version=1,
                    proof_key_marker=old_marker,
                    proof_correlation_id=CORRELATION_ID,
                    previous_key_version=1,
                    previous_key_marker=old_marker,
                    key_version=2,
                    key_marker=new_marker,
                    rotation_signature=signature,
                    now=at,
                )
            )

        payload = admin_device_key_rotation_payload(
            admin_id=ADMIN_ID, device_id=DEVICE_ID, session_id=identity.session_id,
            correlation_id=CORRELATION_ID, previous_key_version=1,
            previous_key_marker=old_marker, key_version=2, key_marker=new_marker,
        )

        def statuses() -> dict[int, str]:
            with SessionFactory() as db:
                return {
                    row.key_version: row.status
                    for row in db.execute(
                        select(AdminDeviceKey).where(AdminDeviceKey.admin_id == ADMIN_ID)
                    ).scalars()
                }

        # Failure 1: the server registration step never happened.
        t1 = started_at + timedelta(seconds=10)
        with pytest.raises(AdminSecurityError) as not_registered:
            confirm(_sign(new_key, payload), t1)
        assert not_registered.value.code == "admin_device_key_rotation_not_pending"
        assert statuses() == {1: "ACTIVE"}
        assert _challenge(run_as_runtime, settings, identity, version=1, marker=old_marker, at=t1)

        # Registration: the new key is PENDING and cannot sign any proof yet.
        with SessionFactory() as db:
            staged = provision_admin_device_key(
                db, admin_id=ADMIN_ID, device_id=DEVICE_ID, key_version=2,
                public_key_spki_der=new_der, security_level="STRONGBOX", now=t1,
            )
        assert staged.status == "PENDING"
        assert statuses() == {1: "ACTIVE", 2: "PENDING"}
        with pytest.raises(AdminSecurityError) as pending_proof:
            _challenge(run_as_runtime, settings, identity, version=2, marker=new_marker, at=t1)
        assert pending_proof.value.code == "admin_device_key_not_registered"

        # Failure 2: the rotation signature is not by the new key.
        t2 = started_at + timedelta(seconds=20)
        with pytest.raises(AdminSecurityError) as wrong_signer:
            confirm(_sign(old_key, payload), t2)
        assert wrong_signer.value.code == "admin_device_key_rotation_signature_invalid"
        assert statuses() == {1: "ACTIVE", 2: "PENDING"}
        assert _challenge(run_as_runtime, settings, identity, version=1, marker=old_marker, at=t2)

        # Success: one transaction revokes the old key and activates the new one.
        t3 = started_at + timedelta(seconds=30)
        confirmed = confirm(_sign(new_key, payload), t3)
        assert confirmed["status"] == "ACTIVE" and confirmed["idempotent"] is False
        assert statuses() == {1: "REVOKED", 2: "ACTIVE"}
        with pytest.raises(AdminSecurityError) as old_after:
            _challenge(run_as_runtime, settings, identity, version=1, marker=old_marker, at=t3)
        assert old_after.value.code == "admin_device_key_not_registered"
        assert _challenge(run_as_runtime, settings, identity, version=2, marker=new_marker, at=t3)
        with SessionFactory() as db:
            audits = db.execute(
                select(AdminSecurityAudit).where(
                    AdminSecurityAudit.action == "device_key.rotation.confirm"
                ).order_by(AdminSecurityAudit.sequence)
            ).scalars().all()
            assert [audit.outcome for audit in audits] == ["DENIED", "DENIED", "SUCCESS"]
            assert audits[-1].details["security_level"] == "STRONGBOX"
            session_row = db.get(AdminSecuritySession, identity.session_id)
            assert session_row.revoked_at is None
        # The trigger still forbids any other transition, e.g. ACTIVE -> PENDING.
        with pytest.raises(DBAPIError) as illegal:
            with SessionFactory.begin() as db:
                db.execute(
                    text(
                        "UPDATE admin_device_keys SET status = 'PENDING' "
                        "WHERE admin_id = :admin_id AND key_version = 2"
                    ),
                    {"admin_id": ADMIN_ID},
                )
        assert getattr(illegal.value.orig, "sqlstate", None) == "23514"
    finally:
        clean_test_storage()
        engine.dispose()


@_PG
def test_s18_31_postgres_recovery_start_is_rate_limited_and_attributable(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    clean_test_storage,
) -> None:
    clean_test_storage()
    engine, SessionFactory, run_as_runtime, settings, totp_secret, issuer_key = _pg_environment(
        tmp_path, device_proof_enabled=True
    )
    monkeypatch.setattr(database_api, "SessionLocal", SessionFactory)
    started_at = datetime.now(timezone.utc).replace(microsecond=0)
    recovery_code = "RECOVERY-2026-S18-31-000001"
    other_device = "android-device-0002"
    new_phone = "android-device-0003"
    try:
        with SessionFactory() as db:
            provision_admin_security(
                db, admin_id=ADMIN_ID, password="s18-31 correct horse",
                totp_secret=totp_secret, recovery_codes=[recovery_code],
                credential_issuer_key=issuer_key, now=started_at,
            )
        for device_id in (DEVICE_ID, other_device):
            with SessionFactory() as db:
                provision_admin_device_key(
                    db, admin_id=ADMIN_ID, device_id=device_id, key_version=1,
                    public_key_spki_der=_spki(_private_key()),
                    security_level="TRUSTED_ENVIRONMENT", now=started_at,
                )
        grant = run_as_runtime(
            lambda db: AdminSecurityService(db, settings).login(
                admin_id=ADMIN_ID, password="s18-31 correct horse",
                totp_code=pyotp.TOTP(totp_secret).at(started_at),
                device_id=other_device, device_label="다른 관리자 휴대전화",
                source="198.51.100.40", now=started_at,
            )
        )

        def start(code: str, device_id: str, source: str, at: datetime):
            return run_as_runtime(
                lambda db: AdminSecurityService(db, settings).start_recovery(
                    admin_id=ADMIN_ID, recovery_code=code, device_id=device_id,
                    device_label="복구 휴대전화", source=source, now=at,
                )
            )

        codes = []
        for index in range(6):
            with pytest.raises(AdminSecurityError) as denied:
                start("RECOVERY-2026-WRONG-CODE-000", DEVICE_ID, "203.0.113.9",
                      started_at + timedelta(seconds=index + 1))
            codes.append(denied.value.code)
        assert codes == ["admin_recovery_credentials_invalid"] * 5 + ["admin_auth_rate_limited"]
        with SessionFactory() as db:
            assert db.execute(select(AdminSecurityControl)).scalar_one().security_state == "NORMAL"

        # Without a CLI-registered key a brand-new phone cannot start (device proof on).
        with pytest.raises(AdminSecurityError) as unregistered:
            start(recovery_code, new_phone, "198.51.100.41", started_at + timedelta(seconds=10))
        assert unregistered.value.code == "admin_recovery_device_key_required"

        at = started_at + timedelta(seconds=20)
        started = start(recovery_code, DEVICE_ID, "198.51.100.42", at)
        assert started.security_state == "RECOVERY_IN_PROGRESS"
        with SessionFactory() as db:
            keys = {
                row.device_id: row.status
                for row in db.execute(select(AdminDeviceKey)).scalars()
            }
            assert keys == {DEVICE_ID: "ACTIVE", other_device: "REVOKED"}
            other_session = db.execute(
                select(AdminSecuritySession).where(
                    AdminSecuritySession.device_id == other_device
                )
            ).scalar_one()
            assert other_session.revoked_reason == "recovery_started"
            audit = db.execute(
                select(AdminSecurityAudit).where(
                    AdminSecurityAudit.action == "recovery.start",
                    AdminSecurityAudit.outcome == "SUCCESS",
                )
            ).scalar_one()
            assert audit.device_id == DEVICE_ID
            assert audit.details["device_label"] == "복구 휴대전화"
            assert audit.details["source_sha256"] == hashlib.sha256(b"198.51.100.42").hexdigest()
            assert audit.details["revoked_device_key_count"] == 1
        with pytest.raises(AdminSecurityError):
            with SessionFactory() as db:
                authorize_admin_bearer(
                    db, grant.access_token, runtime_totp_secret=totp_secret,
                    credential_issuer_key=issuer_key, device_id=other_device,
                    app_kind=ADMIN_APP_KIND, role=ADMIN_ROLE, audience=ADMIN_AUDIENCE,
                    now=at,
                )
    finally:
        clean_test_storage()
        engine.dispose()


def _pg_key_rows(SessionFactory) -> dict[tuple[str, int], str]:
    with SessionFactory() as db:
        return {
            (row.device_id, row.key_version): row.status
            for row in db.execute(select(AdminDeviceKey)).scalars()
        }


def _pg_login(run_as_runtime, SessionFactory, settings, totp_secret, issuer_key, *,
              password: str, device_id: str, at: datetime):
    grant = run_as_runtime(
        lambda db: AdminSecurityService(db, settings).login(
            admin_id=ADMIN_ID, password=password,
            totp_code=pyotp.TOTP(totp_secret).at(at),
            device_id=device_id, device_label="관리자 휴대전화",
            source="198.51.100.50", now=at,
        )
    )
    with SessionFactory() as db:
        identity = authorize_admin_bearer(
            db, grant.access_token, runtime_totp_secret=totp_secret,
            credential_issuer_key=issuer_key, device_id=device_id,
            app_kind=ADMIN_APP_KIND, role=ADMIN_ROLE, audience=ADMIN_AUDIENCE,
            now=at,
        )
        db.commit()
    return grant, identity


@_PG
def test_s18_3_postgres_report_lost_and_recovery_start_revoke_pending_keys(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    clean_test_storage,
) -> None:
    """S18-3-F1: no PENDING key survives report-lost or a recovery start."""

    clean_test_storage()
    engine, SessionFactory, run_as_runtime, settings, totp_secret, issuer_key = _pg_environment(
        tmp_path, device_proof_enabled=True
    )
    monkeypatch.setattr(database_api, "SessionLocal", SessionFactory)
    started_at = datetime.now(timezone.utc).replace(microsecond=0)
    recovery_code = "RECOVERY-2026-S18-3-F1-0001"
    lost_device = "android-device-0002"
    third_device = "android-device-0003"
    password = "s18-3-f1 correct horse"
    try:
        with SessionFactory() as db:
            provision_admin_security(
                db, admin_id=ADMIN_ID, password=password,
                totp_secret=totp_secret, recovery_codes=[recovery_code],
                credential_issuer_key=issuer_key, now=started_at,
            )
        for device_id in (DEVICE_ID, lost_device, third_device):
            for version in (1, 2):
                with SessionFactory() as db:
                    provision_admin_device_key(
                        db, admin_id=ADMIN_ID, device_id=device_id, key_version=version,
                        public_key_spki_der=_spki(_private_key()),
                        security_level="TRUSTED_ENVIRONMENT", now=started_at,
                    )
        assert set(_pg_key_rows(SessionFactory).values()) == {"ACTIVE", "PENDING"}
        _grant, identity = _pg_login(
            run_as_runtime, SessionFactory, settings, totp_secret, issuer_key,
            password=password, device_id=DEVICE_ID, at=started_at,
        )

        lost_at = started_at + timedelta(seconds=10)
        run_as_runtime(
            lambda db: AdminSecurityService(db, settings).report_lost_device(
                identity, lost_device, now=lost_at
            )
        )
        keys = _pg_key_rows(SessionFactory)
        assert (keys[(lost_device, 1)], keys[(lost_device, 2)]) == ("REVOKED", "REVOKED")
        assert keys[(DEVICE_ID, 2)] == "PENDING" and keys[(third_device, 2)] == "PENDING"
        with SessionFactory() as db:
            lost_audit = db.execute(
                select(AdminSecurityAudit).where(
                    AdminSecurityAudit.action == "device.report_lost",
                    AdminSecurityAudit.outcome == "SUCCESS",
                )
            ).scalar_one()
            assert lost_audit.details["revoked_device_key_count"] == 2
            assert lost_audit.details["revoked_pending_device_key_count"] == 1
        # Repeating the report finds nothing left to revoke.
        run_as_runtime(
            lambda db: AdminSecurityService(db, settings).report_lost_device(
                identity, lost_device, now=lost_at + timedelta(seconds=1)
            )
        )
        with SessionFactory() as db:
            repeated = db.execute(
                select(AdminSecurityAudit).where(
                    AdminSecurityAudit.action == "device.report_lost",
                    AdminSecurityAudit.outcome == "SUCCESS",
                ).order_by(AdminSecurityAudit.sequence.desc()).limit(1)
            ).scalar_one()
            assert repeated.details["idempotent"] is True
            assert repeated.details["revoked_pending_device_key_count"] == 0

        recovery_at = started_at + timedelta(seconds=20)
        started = run_as_runtime(
            lambda db: AdminSecurityService(db, settings).start_recovery(
                admin_id=ADMIN_ID, recovery_code=recovery_code, device_id=DEVICE_ID,
                device_label="복구 휴대전화", source="198.51.100.51", now=recovery_at,
            )
        )
        assert started.security_state == "RECOVERY_IN_PROGRESS"
        keys = _pg_key_rows(SessionFactory)
        # Only the recovery device's ACTIVE key survives; its own PENDING key and
        # every other device's PENDING key are revoked with the other keys.
        assert keys == {
            (DEVICE_ID, 1): "ACTIVE",
            (DEVICE_ID, 2): "REVOKED",
            (lost_device, 1): "REVOKED",
            (lost_device, 2): "REVOKED",
            (third_device, 1): "REVOKED",
            (third_device, 2): "REVOKED",
        }
        with SessionFactory() as db:
            recovery_audit = db.execute(
                select(AdminSecurityAudit).where(
                    AdminSecurityAudit.action == "recovery.start",
                    AdminSecurityAudit.outcome == "SUCCESS",
                )
            ).scalar_one()
            assert recovery_audit.details["revoked_device_key_count"] == 1
            assert recovery_audit.details["revoked_pending_device_key_count"] == 2
    finally:
        clean_test_storage()
        engine.dispose()


@_PG
def test_s18_3_postgres_emergency_replace_active_ends_device_sessions(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    clean_test_storage,
) -> None:
    """S18-3-F2(b): CLI --replace-active leaves an audit row and ends sessions."""

    clean_test_storage()
    engine, SessionFactory, run_as_runtime, settings, totp_secret, issuer_key = _pg_environment(
        tmp_path, device_proof_enabled=True
    )
    monkeypatch.setattr(database_api, "SessionLocal", SessionFactory)
    started_at = datetime.now(timezone.utc).replace(microsecond=0)
    password = "s18-3-f2 correct horse"
    try:
        with SessionFactory() as db:
            provision_admin_security(
                db, admin_id=ADMIN_ID, password=password,
                totp_secret=totp_secret, recovery_codes=["RECOVERY-2026-S18-3-F2-0001"],
                credential_issuer_key=issuer_key, now=started_at,
            )
        with SessionFactory() as db:
            provision_admin_device_key(
                db, admin_id=ADMIN_ID, device_id=DEVICE_ID, key_version=1,
                public_key_spki_der=_spki(_private_key()),
                security_level="TRUSTED_ENVIRONMENT", now=started_at,
            )
        grant, identity = _pg_login(
            run_as_runtime, SessionFactory, settings, totp_secret, issuer_key,
            password=password, device_id=DEVICE_ID, at=started_at,
        )
        with SessionFactory() as db:
            version_before = db.execute(select(AdminSecurityControl)).scalar_one().state_version
        replaced_at = started_at + timedelta(seconds=10)
        with SessionFactory() as db:
            replaced = provision_admin_device_key(
                db, admin_id=ADMIN_ID, device_id=DEVICE_ID, key_version=2,
                public_key_spki_der=_spki(_private_key()),
                security_level="STRONGBOX", replace_active=True, now=replaced_at,
            )
        assert (replaced.status, replaced.idempotent) == ("ACTIVE", False)
        assert _pg_key_rows(SessionFactory) == {
            (DEVICE_ID, 1): "REVOKED", (DEVICE_ID, 2): "ACTIVE",
        }
        with SessionFactory() as db:
            session_row = db.get(AdminSecuritySession, identity.session_id)
            assert session_row.revoked_reason == "administrator_revoked"
            assert session_row.revoked_at == replaced_at
            control = db.execute(select(AdminSecurityControl)).scalar_one()
            assert control.state_version == version_before + 1
            audit = db.execute(
                select(AdminSecurityAudit).where(
                    AdminSecurityAudit.action == "device_key.replace_active"
                )
            ).scalar_one()
            assert (audit.outcome, audit.device_id) == ("SUCCESS", DEVICE_ID)
            assert audit.details == {
                "key_version": 2,
                "revoked_active_key_count": 1,
                "revoked_pending_key_count": 0,
                "revoked_session_count": 1,
                "security_level": "STRONGBOX",
            }
        with pytest.raises(AdminSecurityError):
            with SessionFactory() as db:
                authorize_admin_bearer(
                    db, grant.access_token, runtime_totp_secret=totp_secret,
                    credential_issuer_key=issuer_key, device_id=DEVICE_ID,
                    app_kind=ADMIN_APP_KIND, role=ADMIN_ROLE, audience=ADMIN_AUDIENCE,
                    now=replaced_at,
                )
    finally:
        clean_test_storage()
        engine.dispose()


# --- S18-7: reconfirmation bound to the approved request body -------------------------


def test_s18_7_bound_digest_depends_on_nonce_and_exact_body() -> None:
    from backend.app.services.admin_security import (
        current_admin_request_body_sha256,
        reconfirmation_nonce_sha256,
        sha256_text,
        _reconfirmation_nonce_candidates,
    )

    nonce = "MDEyMzQ1Njc4OWFiY2RlZg"
    empty = raw_body_sha256(b"{}")
    bound = reconfirmation_nonce_sha256(nonce, empty)
    assert bound != sha256_text(nonce)
    assert bound != reconfirmation_nonce_sha256(nonce, raw_body_sha256(b'{"x":1}'))
    assert reconfirmation_nonce_sha256(nonce) == sha256_text(nonce)
    with pytest.raises(ValueError):
        reconfirmation_nonce_sha256(nonce, "A" * 64)
    assert _reconfirmation_nonce_candidates(nonce) == [sha256_text(nonce)]
    token = current_admin_request_body_sha256.set(empty)
    try:
        assert _reconfirmation_nonce_candidates(nonce) == [bound, sha256_text(nonce)]
    finally:
        current_admin_request_body_sha256.reset(token)


@pytest.mark.parametrize(
    ("action", "path", "intent"),
    [
        ("device.report_lost", "/admin/security/devices/android-device-0002/report-lost", "A" * 64),
        (
            "report.original.grant",
            f"/reports/{REPORT_ID}/original-access-grants",
            raw_body_sha256(b"{}"),
        ),
        # S18-7-F1: the package route's business PG function re-selects the
        # reconfirmation by the plain nonce digest, so a bound one cannot work.
        (
            "admin.report.delivery_package.create",
            f"/admin/reports/{REPORT_ID}/delivery-packages",
            raw_body_sha256(b"{}"),
        ),
    ],
)
def test_s18_7_reauthentication_rejects_unusable_intent_bindings(
    monkeypatch: pytest.MonkeyPatch, action: str, path: str, intent: str
) -> None:
    denied: list[dict[str, Any]] = []
    service = AdminSecurityService(SimpleNamespace(), SimpleNamespace(admin_id=ADMIN_ID))

    def deny_and_commit(**kwargs):
        denied.append(kwargs)
        raise AdminSecurityError(kwargs["code"], kwargs["message"], status_code=kwargs["status_code"])

    monkeypatch.setattr(service, "_deny_and_commit", deny_and_commit)
    with pytest.raises(AdminSecurityError) as rejected:
        service.reauthenticate(
            _identity(),
            password="correct horse battery",
            totp_code="123456",
            action=action,
            method="POST",
            path=path,
            nonce="MDEyMzQ1Njc4OWFiY2RlZg",
            intent_sha256=intent,
        )
    assert rejected.value.code == "admin_reconfirmation_binding_invalid"
    assert denied and denied[0]["action"] == "session.reauthenticate"


def test_s18_7_every_plain_digest_business_action_rejects_intent() -> None:
    """S18-7-F1: actions the claim function re-selects by plain digest are unsupported."""

    import re

    from backend.app.services.admin_security import (
        _RECONFIRMATION_INTENT_UNSUPPORTED_ACTIONS,
    )

    versions = Path(__file__).resolve().parents[1] / "alembic" / "versions"
    definitions = [
        path
        for path in sorted(versions.glob("*.py"))
        if "CREATE OR REPLACE FUNCTION public.walksafe_claim_admin_report_mutation("
        in path.read_text(encoding="utf-8")
        or "CREATE FUNCTION public.walksafe_claim_admin_report_mutation("
        in path.read_text(encoding="utf-8")
    ]
    assert definitions
    latest = definitions[-1].read_text(encoding="utf-8")
    body = latest.split("FUNCTION public.walksafe_claim_admin_report_mutation(", 1)[1]
    listed = re.search(r"IF p_action IN \(([^)]*)\)", body)
    assert listed is not None
    plain_digest_actions = set(re.findall(r"'([a-z_.]+)'", listed.group(1)))
    assert plain_digest_actions == {
        "report.original.grant",
        "admin.report.delivery_package.create",
    }
    assert plain_digest_actions <= _RECONFIRMATION_INTENT_UNSUPPORTED_ACTIONS


def test_s18_7_reauthenticate_request_accepts_only_a_lowercase_body_digest() -> None:
    fields = {
        "password": "correct horse battery",
        "totp_code": "123456",
        "action": "device.report_lost",
        "method": "POST",
        "path": "/admin/security/devices/android-device-0002/report-lost",
        "nonce": "MDEyMzQ1Njc4OWFiY2RlZg",
    }
    assert admin_security_api.ReauthenticateRequest(**fields).intent_sha256 is None
    bound = admin_security_api.ReauthenticateRequest(**fields, intent_sha256="a" * 64)
    assert bound.intent_sha256 == "a" * 64
    from pydantic import ValidationError

    with pytest.raises(ValidationError):
        admin_security_api.ReauthenticateRequest(**fields, intent_sha256="A" * 64)


def test_s18_7_middleware_exposes_the_high_risk_body_digest_to_the_authorizer() -> None:
    import asyncio

    from backend.app.field_test_security import FieldTestSecurityMiddleware
    from backend.app.services.admin_security import current_admin_request_body_sha256

    body = b'{"reason":"lost"}'
    seen: list[str | None] = []

    def authorize(_settings: Any, **kwargs: Any) -> AdminSessionIdentity:
        seen.append(current_admin_request_body_sha256.get())
        raise AdminSecurityError("admin_reconfirmation_required", "stop", status_code=403)

    async def downstream(scope, receive, send):  # pragma: no cover - never reached
        raise AssertionError("downstream must not run")

    route_app = FastAPI()
    route_app.add_api_route(
        "/admin/security/devices/{device_id}/report-lost", lambda: None, methods=["POST"]
    )
    settings = SimpleNamespace(
        admin_security_enabled=True,
        admin_device_proof_enabled=True,
        admin_id=ADMIN_ID,
        admin_totp_secret="A" * 32,
        field_test_security_enabled=True,
        field_test_token="field-token-for-proof-tests-123456",
        admin_token="admin-token-for-proof-tests-123456",
        walksafe_environment="test",
        allow_insecure_local_dev=False,
        gateway_session_secret="",
        actor_rate_limit_store="memory",
        max_upload_bytes=1024,
        max_report_metadata_bytes=1024,
    )
    middleware = FieldTestSecurityMiddleware(
        downstream,
        settings,
        admin_session_authorizer=authorize,
        admin_device_proof_verifier=lambda *_a, **_k: pytest.fail("proof not expected"),
        admin_security_denial_recorder=lambda **_kwargs: None,
    )
    path = "/admin/security/devices/android-device-0002/report-lost"
    scope = {
        "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
        "method": "POST", "scheme": "https", "path": path, "raw_path": path.encode(),
        "query_string": b"",
        "headers": [
            (b"x-walksafe-app-kind", ADMIN_APP_KIND.encode()),
            (b"x-walksafe-role", ADMIN_ROLE.encode()),
            (b"x-walksafe-audience", ADMIN_AUDIENCE.encode()),
            (b"x-walksafe-device-id", DEVICE_ID.encode()),
            (b"authorization", b"Bearer valid-token"),
            (b"x-walksafe-reconfirm-nonce", b"MDEyMzQ1Njc4OWFiY2RlZg"),
            (b"content-length", str(len(body)).encode()),
        ],
        "client": ("127.0.0.1", 1), "server": ("test", 443), "root_path": "",
        "app": route_app,
    }
    messages = [{"type": "http.request", "body": body, "more_body": False}]
    sent: list[dict[str, Any]] = []

    async def receive():
        return messages.pop(0)

    async def send(message):
        sent.append(message)

    asyncio.run(middleware(scope, receive, send))
    assert seen == [raw_body_sha256(body)]
    assert current_admin_request_body_sha256.get() is None
    assert sent[0]["status"] == 403


@_PG
@pytest.mark.parametrize(("action", "method", "path"), [
    ("device.report_lost", "POST", "/admin/security/devices/android-device-0002/report-lost"),
    ("admin.report.status.update", "PATCH", f"/admin/reports/{REPORT_ID}/status"),
    ("admin.report_request.status.update", "PATCH", f"/admin/report-requests/{REPORT_ID}/status"),
    ("admin.incident.status.update", "PATCH", f"/admin/incidents/{REPORT_ID}/status"),
    ("admin.raw_collection.list", "GET", "/admin/raw-collections/quarantine"),
    ("admin.raw_collection.purpose_decide", "POST", f"/admin/raw-collections/{REPORT_ID}/decisions"),
    ("admin.raw_collection.legal_hold", "POST", f"/admin/raw-collections/{REPORT_ID}/legal-holds"),
])
def test_s18_7_postgres_bound_reconfirmation_is_consumed_only_by_its_exact_body(
    action: str, method: str, path: str,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    clean_test_storage,
) -> None:
    from backend.app.models import AdminSecurityReconfirmation
    from backend.app.services.admin_security import (
        authorize_database_bound_high_risk_bearer,
        current_admin_request_body_sha256,
        reconfirmation_nonce_sha256,
        sha256_text,
    )

    clean_test_storage()
    engine, SessionFactory, run_as_runtime, settings, totp_secret, issuer_key = _pg_environment(
        tmp_path, device_proof_enabled=False
    )
    monkeypatch.setattr(database_api, "SessionLocal", SessionFactory)
    started_at = datetime.now(timezone.utc).replace(microsecond=0)
    approved_body = b"" if method == "GET" else b"{}"
    try:
        with SessionFactory() as db:
            provision_admin_security(
                db, admin_id=ADMIN_ID, password="s18-7 correct horse",
                totp_secret=totp_secret, recovery_codes=["RECOVERY-2026-S18-7-00001"],
                credential_issuer_key=issuer_key, now=started_at,
            )
        grant = run_as_runtime(
            lambda db: AdminSecurityService(db, settings).login(
                admin_id=ADMIN_ID, password="s18-7 correct horse",
                totp_code=pyotp.TOTP(totp_secret).at(started_at), device_id=DEVICE_ID,
                device_label="관리자 휴대전화", source="198.51.100.50", now=started_at,
            )
        )
        with SessionFactory() as db:
            identity = authorize_admin_bearer(
                db, grant.access_token, runtime_totp_secret=totp_secret,
                credential_issuer_key=issuer_key, device_id=DEVICE_ID,
                app_kind=ADMIN_APP_KIND, role=ADMIN_ROLE, audience=ADMIN_AUDIENCE,
                now=started_at,
            )
            db.commit()
        run_as_runtime(
            lambda db: AdminSecurityService(db, settings).attest_recovery_custody(
                identity,
                custody_reference="MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY",
                material_kind="RECOVERY_CODE",
                storage_location="OFF_PHONE",
                separate_encrypted_backup_confirmed=True,
                now=started_at,
            )
        )

        def reauth(nonce: str, at: datetime, intent: str | None):
            return run_as_runtime(
                lambda db: AdminSecurityService(db, settings).reauthenticate(
                    identity, password="s18-7 correct horse",
                    totp_code=pyotp.TOTP(totp_secret).at(at), action=action,
                    method=method, path=path, nonce=nonce, source="198.51.100.50",
                    now=at, intent_sha256=intent,
                )
            )

        def consume(nonce: str, at: datetime, body: bytes | None):
            def run(db):
                token = current_admin_request_body_sha256.set(
                    None if body is None else raw_body_sha256(body)
                )
                try:
                    consumed = authorize_database_bound_high_risk_bearer(
                        db, grant.access_token, action, at, method=method, path=path,
                        nonce=nonce, runtime_totp_secret=totp_secret,
                        credential_issuer_key=issuer_key, device_id=DEVICE_ID,
                    )
                    db.commit()
                    return consumed
                finally:
                    current_admin_request_body_sha256.reset(token)

            return run_as_runtime(run)

        def consumed_at(digest: str):
            with SessionFactory() as db:
                return db.execute(
                    select(AdminSecurityReconfirmation.consumed_at).where(
                        AdminSecurityReconfirmation.nonce_sha256 == digest
                    )
                ).scalar_one()

        bound_nonce = "AAECAwQFBgcICQoLDA0ODw"
        t1 = started_at + timedelta(seconds=30)
        reauth(bound_nonce, t1, raw_body_sha256(approved_body))
        bound_digest = reconfirmation_nonce_sha256(bound_nonce, raw_body_sha256(approved_body))
        # A different body (or no body digest at all) cannot use the bound approval.
        for other in (b'{"device_id":"android-device-0009"}', None):
            with pytest.raises(AdminSecurityError) as mismatch:
                consume(bound_nonce, t1 + timedelta(seconds=1), other)
            assert mismatch.value.code == "admin_reconfirmation_required"
            assert consumed_at(bound_digest) is None
        assert consume(bound_nonce, t1 + timedelta(seconds=2), approved_body).session_id == identity.session_id
        assert consumed_at(bound_digest) is not None
        with pytest.raises(AdminSecurityError):
            consume(bound_nonce, t1 + timedelta(seconds=3), approved_body)

        # An unbound (legacy) reconfirmation keeps working for the exact path.
        legacy_nonce = "EBESExQVFhcYGRobHB0eHw"
        t2 = started_at + timedelta(seconds=60)
        reauth(legacy_nonce, t2, None)
        consume(legacy_nonce, t2 + timedelta(seconds=1), approved_body)
        assert consumed_at(sha256_text(legacy_nonce)) is not None
    finally:
        clean_test_storage()
        engine.dispose()
