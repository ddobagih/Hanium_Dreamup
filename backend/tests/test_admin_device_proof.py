from __future__ import annotations

import asyncio
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace
from typing import Any
import uuid

from alembic.config import Config
from alembic.script import ScriptDirectory
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI
import pytest

from backend.app.api import admin_security as admin_security_api
from backend.app.field_test_security import (
    FieldTestSecurityMiddleware,
    requires_admin_device_proof,
)
from backend.app.models import (
    AdminDeviceKey,
    AdminSecurityControl,
    AdminSecurityRecoveryTransaction,
)
from backend.app.openapi_contract import install_walksafe_openapi_contract
from backend.app.services import admin_device_proof as admin_device_proof_service
from backend.app.services.admin_device_proof import (
    ADMIN_DEVICE_PROOF_MAX_OUTSTANDING_CHALLENGES,
    ADMIN_DEVICE_PROOF_SCHEMA_VERSION,
    ADMIN_DEVICE_PROOF_SIGNED_FIELDS,
    ADMIN_DEVICE_PROOF_TTL_MILLISECONDS,
    AdminDeviceProofService,
    ProvisionedAdminDeviceKey,
    VerifiedAdminDeviceProof,
    admin_device_key_marker,
    canonical_admin_query,
    canonical_admin_query_sha256,
    canonical_device_proof_json,
    is_admin_device_proof_workflow_request,
    load_p256_spki_public_key,
    provision_admin_device_key,
    raw_body_sha256,
    validate_device_proof_challenge_binding,
    verify_admin_device_proof,
)
from backend.app.services.admin_security import (
    ADMIN_APP_KIND,
    ADMIN_AUDIENCE,
    ADMIN_ROLE,
    AdminSecurityError,
    AdminSessionIdentity,
    SessionGrant,
    classify_admin_operation,
    provision_admin_security,
)
from scripts import provision_walksafe_admin_device_key as provision_cli
from asgi_client import ASGITestClient


ADMIN_ID = "walksafe.admin"
DEVICE_ID = "android-device-0001"
SESSION_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
CHALLENGE_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
CORRELATION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
REPORT_ID = "44444444-4444-4444-8444-444444444444"
INCIDENT_ID = "55555555-5555-4555-8555-555555555555"


def _repository_alembic_head() -> str:
    config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    head = ScriptDirectory.from_config(config).get_current_head()
    assert head is not None
    return head


def _private_key() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


def _spki_der(private_key: ec.EllipticCurvePrivateKey) -> bytes:
    return private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )


def _base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _identity() -> AdminSessionIdentity:
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    return AdminSessionIdentity(
        admin_id=ADMIN_ID,
        session_id=SESSION_ID,
        device_id=DEVICE_ID,
        device_label="관리자 단말",
        expires_at=now + timedelta(hours=1),
        step_up_verified_at=now,
    )


def _device_key(private_key: ec.EllipticCurvePrivateKey) -> AdminDeviceKey:
    der = _spki_der(private_key)
    return AdminDeviceKey(
        id=uuid.uuid4(),
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=1,
        public_key_spki_der=der,
        key_marker=hashlib.sha256(der).hexdigest(),
        status="ACTIVE",
        created_at=datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc),
    )


class _Result:
    def __init__(self, *, one: Any = None, values: list[Any] | None = None) -> None:
        self.one = one
        self.values = [] if values is None else values

    def scalar_one_or_none(self) -> Any:
        return self.one

    def scalars(self) -> _Result:
        return self

    def all(self) -> list[Any]:
        return list(self.values)


class _FakeSession:
    def __init__(self, results: list[_Result], *, dialect: str | None = None) -> None:
        self.results = list(results)
        self.statements: list[Any] = []
        self.execution_parameters: list[dict[str, Any] | None] = []
        self.added: list[Any] = []
        self.commits = 0
        self.rollbacks = 0
        self.flushes = 0
        self.closed = False
        self.bind = (
            SimpleNamespace(dialect=SimpleNamespace(name=dialect))
            if dialect is not None
            else None
        )

    def get_bind(self) -> Any:
        return self.bind

    def execute(
        self,
        statement: Any,
        parameters: dict[str, Any] | None = None,
    ) -> _Result:
        self.statements.append(statement)
        self.execution_parameters.append(parameters)
        return self.results.pop(0)

    def add(self, value: Any) -> None:
        if getattr(value, "id", None) is None:
            value.id = uuid.uuid4()
        self.added.append(value)

    def commit(self) -> None:
        self.commits += 1

    def rollback(self) -> None:
        self.rollbacks += 1

    def flush(self) -> None:
        self.flushes += 1

    def close(self) -> None:
        self.closed = True


def _challenge_request(
    key: AdminDeviceKey,
    *,
    body: bytes = b'{"decision":"APPROVE"}',
    query: bytes = b"",
) -> dict[str, Any]:
    return {
        "purpose": "ACTION",
        "action": "report.review.decide",
        "admin_id": ADMIN_ID,
        "body_sha256": raw_body_sha256(body),
        "correlation_id": str(CORRELATION_ID),
        "device_id": DEVICE_ID,
        "device_key_marker": key.key_marker,
        "device_key_version": key.key_version,
        "method": "POST",
        "path": f"/reports/{REPORT_ID}/review-decisions",
        "query_sha256": canonical_admin_query_sha256(query),
        "read_purpose": None,
        "session_id": str(SESSION_ID),
        "identity": _identity(),
    }


def _recovery_challenge_request(
    key: AdminDeviceKey,
    *,
    body: bytes = b'{"recovery_token":"opaque"}',
) -> dict[str, Any]:
    return {
        "purpose": "RECOVERY_COMPLETE",
        "action": None,
        "admin_id": ADMIN_ID,
        "body_sha256": raw_body_sha256(body),
        "correlation_id": str(CORRELATION_ID),
        "device_id": DEVICE_ID,
        "device_key_marker": key.key_marker,
        "device_key_version": key.key_version,
        "method": "POST",
        "path": "/admin/security/recovery/complete",
        "query_sha256": canonical_admin_query_sha256(b""),
        "read_purpose": None,
        "session_id": None,
        "identity": None,
    }


def _recovery_control(*, state: str = "RECOVERY_IN_PROGRESS") -> AdminSecurityControl:
    return AdminSecurityControl(
        admin_id=ADMIN_ID,
        singleton_scope=True,
        security_state=state,
    )


def _recovery_transaction(
    now: datetime,
    *,
    device_id: str = DEVICE_ID,
) -> AdminSecurityRecoveryTransaction:
    return AdminSecurityRecoveryTransaction(
        admin_id=ADMIN_ID,
        device_id=device_id,
        completed_at=None,
        expires_at=now + timedelta(minutes=5),
    )


def test_canonical_payload_is_exact18_compact_sorted_and_utf8() -> None:
    payload = {
        "action": None,
        "admin_id": "관리자",
        "body_sha256": "0" * 64,
        "challenge_id": str(CHALLENGE_ID),
        "correlation_id": str(CORRELATION_ID),
        "device_id": DEVICE_ID,
        "device_key_marker": "1" * 64,
        "device_key_version": 1,
        "expires_at_epoch_ms": 2,
        "issued_at_epoch_ms": 1,
        "method": "GET",
        "nonce": "A" * 43,
        "path": f"/reports/{REPORT_ID}/review-decisions",
        "purpose": "ACTION",
        "query_sha256": "2" * 64,
        "read_purpose": "report.review_decisions",
        "schema_version": ADMIN_DEVICE_PROOF_SCHEMA_VERSION,
        "session_id": str(SESSION_ID),
    }

    canonical = canonical_device_proof_json(payload)

    assert tuple(payload) == ADMIN_DEVICE_PROOF_SIGNED_FIELDS
    assert canonical.encode("utf-8").decode("utf-8") == canonical
    assert "관리자" in canonical
    assert "\\u" not in canonical
    assert ": " not in canonical and ", " not in canonical
    assert list(json.loads(canonical)) == sorted(ADMIN_DEVICE_PROOF_SIGNED_FIELDS)
    with pytest.raises(ValueError, match="exactly"):
        canonical_device_proof_json({**payload, "extra": True})


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (b"", b""),
        (b"b=2&a=3&a=1&blank&empty=", b"a=1&a=3&b=2&blank=&empty="),
        (b"space=a+b&encoded=a%20b", b"encoded=a%20b&space=a%2Bb"),
        ("한글=값&한글=가".encode(), "%ED%95%9C%EA%B8%80=%EA%B0%80&%ED%95%9C%EA%B8%80=%EA%B0%92".encode()),
    ],
)
def test_query_canonicalization_preserves_pairs_and_rfc3986(
    raw: bytes,
    expected: bytes,
) -> None:
    assert canonical_admin_query(raw) == expected
    assert canonical_admin_query_sha256(raw) == hashlib.sha256(expected).hexdigest()


@pytest.mark.parametrize("raw", [b"a=%", b"a=%2", b"a=%+1", b"a=%GG", b"a=%FF"])
def test_query_canonicalization_rejects_malformed_percent_or_utf8(raw: bytes) -> None:
    with pytest.raises(ValueError):
        canonical_admin_query(raw)


def test_p256_spki_marker_is_lower_sha256_and_other_curve_is_rejected() -> None:
    der = _spki_der(_private_key())
    marker = admin_device_key_marker(der)

    assert marker == hashlib.sha256(der).hexdigest()
    assert load_p256_spki_public_key(der).curve.name == "secp256r1"
    p384 = ec.generate_private_key(ec.SECP384R1()).public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    with pytest.raises(ValueError, match="P-256"):
        load_p256_spki_public_key(p384)


def test_issue_sign_verify_consume_and_replay() -> None:
    private_key = _private_key()
    key = _device_key(private_key)
    now = datetime(2026, 8, 9, 12, 0, 0, 123456, tzinfo=timezone.utc)
    issue_db = _FakeSession([_Result(one=key), _Result(values=[])])

    response = AdminDeviceProofService(issue_db).issue_challenge(
        **_challenge_request(key),
        now=now,
    )

    assert set(response) == {*ADMIN_DEVICE_PROOF_SIGNED_FIELDS, "signing_payload"}
    assert response["signing_payload"] == canonical_device_proof_json(
        {name: response[name] for name in ADMIN_DEVICE_PROOF_SIGNED_FIELDS}
    )
    assert response["expires_at_epoch_ms"] - response["issued_at_epoch_ms"] == (
        ADMIN_DEVICE_PROOF_TTL_MILLISECONDS
    )
    assert len(response["nonce"]) == 43 and "=" not in response["nonce"]
    assert issue_db.commits == 1
    challenge = issue_db.added[0]
    signature = _base64url(
        private_key.sign(
            response["signing_payload"].encode("utf-8"),
            ec.ECDSA(hashes.SHA256()),
        )
    )
    verify_db = _FakeSession([_Result(one=challenge), _Result(one=key)])

    proof = verify_admin_device_proof(
        verify_db,
        challenge_id=response["challenge_id"],
        signature=signature,
        correlation_id=str(CORRELATION_ID),
        expected_purpose="ACTION",
        expected_action="report.review.decide",
        expected_admin_id=ADMIN_ID,
        expected_device_id=DEVICE_ID,
        expected_session_id=SESSION_ID,
        expected_method="POST",
        expected_path=f"/reports/{REPORT_ID}/review-decisions",
        expected_read_purpose=None,
        raw_body=b'{"decision":"APPROVE"}',
        raw_query_string=b"",
        now=now + timedelta(seconds=1),
    )

    assert isinstance(proof, VerifiedAdminDeviceProof)
    assert proof.challenge_id == challenge.id
    assert challenge.consumed_at == now + timedelta(seconds=1)
    assert verify_db.flushes == 1
    replay_db = _FakeSession([_Result(one=challenge)])
    with pytest.raises(AdminSecurityError) as replay:
        verify_admin_device_proof(
            replay_db,
            challenge_id=response["challenge_id"],
            signature=signature,
            correlation_id=str(CORRELATION_ID),
            expected_purpose="ACTION",
            expected_action="report.review.decide",
            expected_admin_id=ADMIN_ID,
            expected_device_id=DEVICE_ID,
            expected_session_id=SESSION_ID,
            expected_method="POST",
            expected_path=f"/reports/{REPORT_ID}/review-decisions",
            expected_read_purpose=None,
            raw_body=b'{"decision":"APPROVE"}',
            raw_query_string=b"",
            now=now + timedelta(seconds=2),
        )
    assert replay.value.code == "admin_device_proof_replayed"


def test_recovery_complete_issue_and_verify_use_guarded_postgresql_context_rpc(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    private_key = _private_key()
    key = _device_key(private_key)
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    body = b'{"recovery_token":"opaque"}'
    credential_issuer_key = "issuer-key-for-device-proof-tests-1234567890"
    monkeypatch.setattr(
        admin_device_proof_service,
        "load_admin_credential_issuer_key_for_settings",
        lambda _settings_value: credential_issuer_key,
    )
    issue_rpc_row = SimpleNamespace(
        context_status="RECOVERY_EXPIRED",
        public_key_spki_der=key.public_key_spki_der,
    )
    issue_db = _FakeSession(
        [
            _Result(values=[issue_rpc_row]),
            _Result(values=[]),
        ],
        dialect="postgresql",
    )

    response = AdminDeviceProofService(issue_db, _settings()).issue_challenge(
        **_recovery_challenge_request(key, body=body),
        now=now,
    )

    assert "walksafe_lock_admin_device_proof_context" in str(issue_db.statements[0])
    assert issue_db.execution_parameters[0] == {
        "admin_id": ADMIN_ID,
        "device_id": DEVICE_ID,
        "device_key_version": 1,
        "device_key_marker": key.key_marker,
        "observed_at": now,
        "purpose": "RECOVERY_COMPLETE",
        "runtime_totp_secret": _settings().admin_totp_secret,
        "credential_issuer_key": credential_issuer_key,
    }

    challenge = issue_db.added[0]
    signature = _base64url(
        private_key.sign(
            response["signing_payload"].encode("utf-8"),
            ec.ECDSA(hashes.SHA256()),
        )
    )
    expired_verify_rpc_row = SimpleNamespace(
        context_status="RECOVERY_EXPIRED",
        public_key_spki_der=key.public_key_spki_der,
    )
    verify_db = _FakeSession(
        [
            _Result(one=challenge),
            _Result(values=[expired_verify_rpc_row]),
        ],
        dialect="postgresql",
    )

    proof = verify_admin_device_proof(
        verify_db,
        challenge_id=response["challenge_id"],
        signature=signature,
        correlation_id=str(CORRELATION_ID),
        expected_purpose="RECOVERY_COMPLETE",
        expected_action=None,
        expected_admin_id=ADMIN_ID,
        expected_device_id=DEVICE_ID,
        expected_session_id=None,
        expected_method="POST",
        expected_path="/admin/security/recovery/complete",
        expected_read_purpose=None,
        raw_body=body,
        raw_query_string=b"",
        runtime_totp_secret=_settings().admin_totp_secret,
        credential_issuer_key=credential_issuer_key,
        now=now + timedelta(seconds=1),
    )

    assert proof.purpose == "RECOVERY_COMPLETE"
    assert proof.device_id == DEVICE_ID
    assert verify_db.statements[0]._for_update_arg is not None
    assert "walksafe_lock_admin_device_proof_context" in str(verify_db.statements[1])
    assert verify_db.execution_parameters[1]["purpose"] == "RECOVERY_COMPLETE"
    assert challenge.consumed_at == now + timedelta(seconds=1)


@pytest.mark.parametrize(
    ("purpose", "expose_public_key"),
    [
        ("RECOVERY_COMPLETE", False),
        ("ACTION", True),
    ],
)
def test_postgresql_expired_context_fails_closed_without_exact_cleanup_authority(
    monkeypatch: pytest.MonkeyPatch,
    purpose: str,
    expose_public_key: bool,
) -> None:
    key = _device_key(_private_key())
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    monkeypatch.setattr(
        admin_device_proof_service,
        "load_admin_credential_issuer_key_for_settings",
        lambda _settings_value: "issuer-key-for-device-proof-tests-1234567890",
    )
    db = _FakeSession(
        [
            _Result(
                values=[
                    SimpleNamespace(
                        context_status="RECOVERY_EXPIRED",
                        public_key_spki_der=(
                            key.public_key_spki_der if expose_public_key else None
                        ),
                    )
                ]
            )
        ],
        dialect="postgresql",
    )
    request = (
        _recovery_challenge_request(key)
        if purpose == "RECOVERY_COMPLETE"
        else _challenge_request(key)
    )

    with pytest.raises(AdminSecurityError) as rejected:
        AdminDeviceProofService(db, _settings()).issue_challenge(
            **request,
            now=now,
        )

    assert rejected.value.code == "admin_recovery_expired"
    assert rejected.value.status_code == 410
    assert db.added == []
    assert db.rollbacks == 1


def test_recovery_complete_issue_fails_closed_without_exact_context() -> None:
    key = _device_key(_private_key())
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    normal_control = _recovery_control(state="NORMAL")
    wrong_admin_control = _recovery_control()
    wrong_admin_control.admin_id = "different.admin"
    non_singleton_control = _recovery_control()
    non_singleton_control.singleton_scope = False
    wrong_device_transaction = _recovery_transaction(
        now,
        device_id="android-device-9999",
    )
    completed_transaction = _recovery_transaction(now)
    completed_transaction.completed_at = now
    invalid_results = [
        [_Result(values=[])],
        [_Result(values=[_recovery_control(), _recovery_control()])],
        [_Result(values=[normal_control])],
        [_Result(values=[wrong_admin_control])],
        [_Result(values=[non_singleton_control])],
        [_Result(values=[_recovery_control()]), _Result(values=[])],
        [
            _Result(values=[_recovery_control()]),
            _Result(values=[_recovery_transaction(now), _recovery_transaction(now)]),
        ],
        [
            _Result(values=[_recovery_control()]),
            _Result(values=[wrong_device_transaction]),
        ],
        [
            _Result(values=[_recovery_control()]),
            _Result(values=[completed_transaction]),
        ],
    ]

    for results in invalid_results:
        db = _FakeSession(results)
        with pytest.raises(AdminSecurityError) as rejected:
            AdminDeviceProofService(db).issue_challenge(
                **_recovery_challenge_request(key),
                now=now,
            )
        assert rejected.value.code == "admin_device_proof_invalid"
        assert rejected.value.status_code == 403
        assert db.added == []
        assert db.rollbacks == 1

def test_recovery_complete_issue_rejects_expired_recovery_device() -> None:
    key = _device_key(_private_key())
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    expired_transaction = _recovery_transaction(now)
    expired_transaction.expires_at = now
    db = _FakeSession(
        [
            _Result(values=[_recovery_control()]),
            _Result(values=[expired_transaction]),
        ]
    )

    with pytest.raises(AdminSecurityError) as rejected:
        AdminDeviceProofService(db).issue_challenge(
            **_recovery_challenge_request(key),
            now=now,
        )

    assert rejected.value.code == "admin_recovery_expired"
    assert rejected.value.status_code == 410
    assert db.added == []
    assert db.rollbacks == 1


def test_read_issue_and_verify_use_read_postgresql_context_purpose(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    private_key = _private_key()
    key = _device_key(private_key)
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    credential_issuer_key = "issuer-key-for-device-proof-tests-1234567890"
    monkeypatch.setattr(
        admin_device_proof_service,
        "load_admin_credential_issuer_key_for_settings",
        lambda _settings_value: credential_issuer_key,
    )
    rpc_row = SimpleNamespace(
        context_status="OK",
        public_key_spki_der=key.public_key_spki_der,
    )
    raw_query = b"view=full"
    issue_db = _FakeSession(
        [_Result(values=[rpc_row]), _Result(values=[])],
        dialect="postgresql",
    )
    response = AdminDeviceProofService(issue_db, _settings()).issue_challenge(
        **{
            **_challenge_request(key),
            "action": None,
            "body_sha256": raw_body_sha256(b""),
            "method": "GET",
            "query_sha256": canonical_admin_query_sha256(raw_query),
            "read_purpose": "report.review_decisions",
        },
        now=now,
    )
    assert issue_db.execution_parameters[0]["purpose"] == "READ"

    challenge = issue_db.added[0]
    signature = _base64url(
        private_key.sign(
            response["signing_payload"].encode("utf-8"),
            ec.ECDSA(hashes.SHA256()),
        )
    )
    verify_db = _FakeSession(
        [_Result(one=challenge), _Result(values=[rpc_row])],
        dialect="postgresql",
    )
    proof = verify_admin_device_proof(
        verify_db,
        challenge_id=response["challenge_id"],
        signature=signature,
        correlation_id=str(CORRELATION_ID),
        expected_purpose="ACTION",
        expected_action=None,
        expected_admin_id=ADMIN_ID,
        expected_device_id=DEVICE_ID,
        expected_session_id=SESSION_ID,
        expected_method="GET",
        expected_path=f"/reports/{REPORT_ID}/review-decisions",
        expected_read_purpose="report.review_decisions",
        raw_body=b"",
        raw_query_string=raw_query,
        runtime_totp_secret=_settings().admin_totp_secret,
        credential_issuer_key=credential_issuer_key,
        now=now + timedelta(seconds=1),
    )
    assert proof.read_purpose == "report.review_decisions"
    assert verify_db.execution_parameters[1]["purpose"] == "READ"


def test_recovery_complete_verify_rechecks_context_before_challenge_lock() -> None:
    private_key = _private_key()
    key = _device_key(private_key)
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    body = b'{"recovery_token":"opaque"}'
    issue_db = _FakeSession(
        [
            _Result(values=[_recovery_control()]),
            _Result(values=[_recovery_transaction(now)]),
            _Result(one=key),
            _Result(values=[]),
        ]
    )
    response = AdminDeviceProofService(issue_db).issue_challenge(
        **_recovery_challenge_request(key, body=body),
        now=now,
    )
    challenge = issue_db.added[0]
    signature = _base64url(
        private_key.sign(
            response["signing_payload"].encode("utf-8"),
            ec.ECDSA(hashes.SHA256()),
        )
    )
    verify_db = _FakeSession([_Result(values=[_recovery_control(state="NORMAL")])])

    with pytest.raises(AdminSecurityError) as rejected:
        verify_admin_device_proof(
            verify_db,
            challenge_id=response["challenge_id"],
            signature=signature,
            correlation_id=str(CORRELATION_ID),
            expected_purpose="RECOVERY_COMPLETE",
            expected_action=None,
            expected_admin_id=ADMIN_ID,
            expected_device_id=DEVICE_ID,
            expected_session_id=None,
            expected_method="POST",
            expected_path="/admin/security/recovery/complete",
            expected_read_purpose=None,
            raw_body=body,
            raw_query_string=b"",
            now=now + timedelta(seconds=1),
        )

    assert rejected.value.code == "admin_device_proof_invalid"
    assert len(verify_db.statements) == 1
    assert challenge.consumed_at is None
    assert verify_db.flushes == 0


def test_verify_rejects_body_change_expiry_revoked_key_and_wrong_signature() -> None:
    private_key = _private_key()
    key = _device_key(private_key)
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    issue_db = _FakeSession([_Result(one=key), _Result(values=[])])
    response = AdminDeviceProofService(issue_db).issue_challenge(
        **_challenge_request(key),
        now=now,
    )
    challenge = issue_db.added[0]
    signature = _base64url(
        private_key.sign(
            response["signing_payload"].encode("utf-8"),
            ec.ECDSA(hashes.SHA256()),
        )
    )
    arguments = {
        "challenge_id": response["challenge_id"],
        "signature": signature,
        "correlation_id": str(CORRELATION_ID),
        "expected_purpose": "ACTION",
        "expected_action": "report.review.decide",
        "expected_admin_id": ADMIN_ID,
        "expected_device_id": DEVICE_ID,
        "expected_session_id": SESSION_ID,
        "expected_method": "POST",
        "expected_path": f"/reports/{REPORT_ID}/review-decisions",
        "expected_read_purpose": None,
        "raw_body": b'{"decision":"APPROVE"}',
        "raw_query_string": b"",
    }

    with pytest.raises(AdminSecurityError) as changed:
        verify_admin_device_proof(
            _FakeSession([_Result(one=challenge)]),
            **{**arguments, "raw_body": b'{"decision":"REJECT"}'},
            now=now + timedelta(seconds=1),
        )
    assert changed.value.code == "admin_device_proof_binding_mismatch"
    assert challenge.consumed_at is None

    with pytest.raises(AdminSecurityError) as query_changed:
        verify_admin_device_proof(
            _FakeSession([_Result(one=challenge)]),
            **{**arguments, "raw_query_string": b"page=1"},
            now=now + timedelta(seconds=1),
        )
    assert query_changed.value.code == "admin_device_proof_binding_mismatch"
    assert challenge.consumed_at is None

    with pytest.raises(AdminSecurityError) as expired:
        verify_admin_device_proof(
            _FakeSession([_Result(one=challenge)]),
            **arguments,
            now=now + timedelta(milliseconds=ADMIN_DEVICE_PROOF_TTL_MILLISECONDS),
        )
    assert expired.value.code == "admin_device_proof_expired"

    with pytest.raises(AdminSecurityError) as revoked:
        verify_admin_device_proof(
            _FakeSession([_Result(one=challenge), _Result(one=None)]),
            **arguments,
            now=now + timedelta(seconds=1),
        )
    assert revoked.value.code == "admin_device_key_not_active"

    wrong_signature = _base64url(
        _private_key().sign(
            response["signing_payload"].encode("utf-8"),
            ec.ECDSA(hashes.SHA256()),
        )
    )
    with pytest.raises(AdminSecurityError) as invalid_signature:
        verify_admin_device_proof(
            _FakeSession([_Result(one=challenge), _Result(one=key)]),
            **{**arguments, "signature": wrong_signature},
            now=now + timedelta(seconds=1),
        )
    assert invalid_signature.value.code == "admin_device_proof_signature_invalid"
    assert challenge.consumed_at is None


def test_issue_rejects_lowercase_method_and_nonworkflow_action_path() -> None:
    key = _device_key(_private_key())
    request = _challenge_request(key)
    for override in (
        {"method": "post"},
        {"path": "/reports", "action": "report.review.decide"},
    ):
        with pytest.raises(AdminSecurityError) as rejected:
            AdminDeviceProofService(_FakeSession([])).issue_challenge(
                **{**request, **override},
            )
        assert rejected.value.code == "admin_device_proof_binding_invalid"


def test_issue_is_bounded_under_the_active_key_lock_without_deleting_history() -> None:
    key = _device_key(_private_key())
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    expiries = [now + timedelta(seconds=30 + index) for index in range(
        ADMIN_DEVICE_PROOF_MAX_OUTSTANDING_CHALLENGES
    )]
    db = _FakeSession([_Result(one=key), _Result(values=expiries)])

    with pytest.raises(AdminSecurityError) as rejected:
        AdminDeviceProofService(db).issue_challenge(
            **_challenge_request(key),
            now=now,
        )

    assert rejected.value.code == "admin_device_proof_challenge_rate_limited"
    assert rejected.value.status_code == 429
    assert rejected.value.retry_after == 30
    assert db.added == []
    assert db.rollbacks == 1
    assert db.statements[0]._for_update_arg is not None


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_two_session_issuance_serializes_at_the_outstanding_cap(
    tmp_path: Path,
) -> None:
    from concurrent.futures import ThreadPoolExecutor
    import threading

    from sqlalchemy import create_engine, delete, event, func, select, text
    from sqlalchemy.orm import Session

    from backend.app.models import AdminDeviceProofChallenge

    database_url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
    engine = create_engine(database_url, pool_pre_ping=True)
    admin_id = f"proof.concurrent.{uuid.uuid4().hex[:16]}"
    device_id = f"proof-device-{uuid.uuid4().hex}"
    private_key = _private_key()
    der = _spki_der(private_key)
    marker = hashlib.sha256(der).hexdigest()
    key_id = uuid.uuid4()
    report_id = uuid.uuid4()
    session_id = uuid.uuid4()
    now = datetime.now(timezone.utc)
    runtime_totp_secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    credential_issuer_key = _base64url(
        hashlib.sha256(b"device-proof-concurrency-issuer").digest()
    )
    issuer_key_file = tmp_path / "issuer.key"
    issuer_key_file.write_text(credential_issuer_key, encoding="ascii")
    issuer_key_file.chmod(0o600)
    proof_settings = SimpleNamespace(
        admin_totp_secret=runtime_totp_secret,
        admin_credential_issuer_key_file=issuer_key_file,
    )
    identity = AdminSessionIdentity(
        admin_id=admin_id,
        session_id=session_id,
        device_id=device_id,
        device_label="concurrent proof test",
        expires_at=now + timedelta(hours=1),
        step_up_verified_at=now,
    )

    with engine.connect() as connection:
        database_name = connection.execute(
            text("SELECT current_database()")
        ).scalar_one()
        assert "test" in database_name.lower()
        assert connection.execute(
            text("SELECT to_regclass('public.admin_device_keys')")
        ).scalar_one() is not None
        assert connection.execute(
            text("SELECT to_regclass('public.admin_device_proof_challenges')")
        ).scalar_one() is not None

    def arguments(correlation_id: uuid.UUID) -> dict[str, Any]:
        return {
            "purpose": "ACTION",
            "action": "report.delivery.create",
            "admin_id": admin_id,
            "body_sha256": raw_body_sha256(b"{}"),
            "correlation_id": str(correlation_id),
            "device_id": device_id,
            "device_key_marker": marker,
            "device_key_version": 1,
            "method": "POST",
            "path": f"/reports/{report_id}/deliveries",
            "query_sha256": canonical_admin_query_sha256(b""),
            "read_purpose": None,
            "session_id": str(session_id),
            "identity": identity,
            "now": now,
        }

    count_barrier = threading.Barrier(2)
    synchronize_competing_counts = threading.Event()

    def pause_after_outstanding_select(
        _connection: Any,
        _cursor: Any,
        statement: str,
        _parameters: Any,
        _context: Any,
        _executemany: bool,
    ) -> None:
        if (
            synchronize_competing_counts.is_set()
            and "SELECT admin_device_proof_challenges.expires_at" in statement
        ):
            try:
                count_barrier.wait(timeout=0.5)
            except threading.BrokenBarrierError:
                pass

    event.listen(engine, "after_cursor_execute", pause_after_outstanding_select)
    try:
        with Session(engine) as db:
            provision_admin_security(
                db,
                admin_id=admin_id,
                password="device proof concurrency password",
                totp_secret=runtime_totp_secret,
                recovery_codes=["DEVICE-PROOF-CONCURRENCY-RECOVERY-0001"],
                credential_issuer_key=credential_issuer_key,
                now=now,
            )
        with Session(engine) as db:
            db.add(
                AdminDeviceKey(
                    id=key_id,
                    admin_id=admin_id,
                    device_id=device_id,
                    key_version=1,
                    public_key_spki_der=der,
                    key_marker=marker,
                    status="ACTIVE",
                    created_at=now,
                )
            )
            db.commit()

        for _ in range(ADMIN_DEVICE_PROOF_MAX_OUTSTANDING_CHALLENGES - 1):
            with Session(engine) as db:
                AdminDeviceProofService(db, proof_settings).issue_challenge(
                    **arguments(uuid.uuid4())
                )

        synchronize_competing_counts.set()

        def issue(correlation_id: uuid.UUID) -> str:
            with Session(engine) as db:
                try:
                    AdminDeviceProofService(db, proof_settings).issue_challenge(
                        **arguments(correlation_id)
                    )
                except AdminSecurityError as exc:
                    return exc.code
                return "issued"

        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(issue, (uuid.uuid4(), uuid.uuid4())))

        assert sorted(results) == [
            "admin_device_proof_challenge_rate_limited",
            "issued",
        ]
        with Session(engine) as db:
            outstanding = db.scalar(
                select(func.count())
                .select_from(AdminDeviceProofChallenge)
                .where(
                    AdminDeviceProofChallenge.admin_id == admin_id,
                    AdminDeviceProofChallenge.consumed_at.is_(None),
                    AdminDeviceProofChallenge.expires_at > now,
                )
            )
        assert outstanding == ADMIN_DEVICE_PROOF_MAX_OUTSTANDING_CHALLENGES
    finally:
        event.remove(engine, "after_cursor_execute", pause_after_outstanding_select)
        with engine.begin() as connection:
            connection.execute(
                delete(AdminDeviceProofChallenge).where(
                    AdminDeviceProofChallenge.admin_id == admin_id
                )
            )
            connection.execute(
                delete(AdminDeviceKey).where(AdminDeviceKey.id == key_id)
            )
            connection.execute(
                text(
                    "DELETE FROM admin_security_recovery_codes "
                    "WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id},
            )
            connection.execute(
                text(
                    "DELETE FROM walksafe_recovery_custody_capabilities "
                    "WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id},
            )
            connection.execute(
                delete(AdminSecurityControl).where(
                    AdminSecurityControl.admin_id == admin_id
                )
            )
        engine.dispose()


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_expired_recovery_proof_reaches_real_cleanup_route(
    clean_test_storage: Any,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    import pyotp
    from sqlalchemy import create_engine, event, text
    from sqlalchemy.orm import sessionmaker

    from backend.app import database as database_api
    from backend.app.services.admin_security import AdminSecurityService

    assert callable(clean_test_storage)
    clean_test_storage()
    database_url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
    engine = create_engine(database_url, pool_pre_ping=True)
    owner_sessions = sessionmaker(bind=engine, expire_on_commit=False)
    runtime_sessions = sessionmaker(bind=engine, expire_on_commit=False)

    def assume_runtime_role(_session: Any, _transaction: Any, connection: Any) -> None:
        connection.exec_driver_sql("SET LOCAL ROLE walksafe_backend_runtime")

    event.listen(runtime_sessions, "after_begin", assume_runtime_role)
    admin_id = f"proof.expired.{uuid.uuid4().hex[:16]}"
    device_id = f"proof-device-{uuid.uuid4().hex}"
    private_key = _private_key()
    public_key_der = _spki_der(private_key)
    key_marker = hashlib.sha256(public_key_der).hexdigest()
    recovery_code = "EXPIRED-PROOF-RECOVERY-CODE-0001"
    initial_totp_secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    replacement_totp_secret = "KRSXG5DSNFXGOIDBNZQW2ZLOMRSXG5DS"
    credential_issuer_key = _base64url(
        hashlib.sha256(b"expired-proof-http-regression-issuer").digest()
    )
    issuer_key_file = tmp_path / "issuer.key"
    issuer_key_file.write_text(credential_issuer_key, encoding="ascii")
    issuer_key_file.chmod(0o600)
    settings = _settings()
    settings.admin_id = admin_id
    settings.admin_totp_secret = initial_totp_secret
    settings.admin_credential_issuer_key_file = issuer_key_file
    settings.admin_session_ttl_seconds = 43_200
    settings.admin_step_up_ttl_seconds = 300
    settings.admin_recovery_ttl_seconds = 900
    settings.admin_auth_rate_limit_attempts = 5
    settings.admin_auth_rate_limit_window_seconds = 300
    settings.max_upload_bytes = 4096
    settings.max_report_metadata_bytes = 4096
    started_at = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(
        minutes=10
    )

    try:
        with engine.connect() as connection:
            database_name = connection.execute(
                text("SELECT current_database()")
            ).scalar_one()
            assert "test" in database_name.lower()
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == _repository_alembic_head()

        with owner_sessions() as db:
            provision_admin_security(
                db,
                admin_id=admin_id,
                password="initial expired proof password",
                totp_secret=initial_totp_secret,
                recovery_codes=[recovery_code],
                credential_issuer_key=credential_issuer_key,
                now=started_at,
            )
        with owner_sessions() as db:
            provisioned_key = provision_admin_device_key(
                db,
                admin_id=admin_id,
                device_id=device_id,
                key_version=1,
                public_key_spki_der=public_key_der,
                now=started_at,
            )
        with runtime_sessions() as db:
            assert db.execute(text("SELECT current_user")).scalar_one() == (
                "walksafe_backend_runtime"
            )
            recovery = AdminSecurityService(db, settings).start_recovery(
                admin_id=admin_id,
                recovery_code=recovery_code,
                device_id=device_id,
                device_label="expired recovery device",
                source="127.0.0.1",
                now=started_at,
            )

        settings.admin_totp_secret = replacement_totp_secret
        with runtime_sessions() as db:
            AdminDeviceProofService(db, settings).issue_challenge(
                purpose="RECOVERY_COMPLETE",
                action=None,
                admin_id=admin_id,
                body_sha256=raw_body_sha256(b'{"candidate":"binding"}'),
                correlation_id=str(uuid.uuid4()),
                device_id=device_id,
                device_key_marker=provisioned_key.key_marker,
                device_key_version=1,
                method="POST",
                path="/admin/security/recovery/complete",
                query_sha256=canonical_admin_query_sha256(b""),
                read_purpose=None,
                session_id=None,
                identity=None,
            )

        expired_at = datetime.now(timezone.utc) - timedelta(seconds=1)
        with engine.begin() as connection:
            transaction_id = connection.execute(
                text(
                    "UPDATE admin_security_recovery_transactions "
                    "SET expires_at = :expired_at "
                    "WHERE admin_id = :admin_id AND completed_at IS NULL "
                    "RETURNING id"
                ),
                {"admin_id": admin_id, "expired_at": expired_at},
            ).scalar_one()
            connection.execute(
                text(
                    "UPDATE walksafe_recovery_custody_capabilities "
                    "SET pending_recovery_expires_at = :expired_at "
                    "WHERE admin_id = :admin_id"
                ),
                {"admin_id": admin_id, "expired_at": expired_at},
            )

        correlation_id = uuid.uuid4()
        body = json.dumps(
            {
                "device_id": device_id,
                "device_label": "expired recovery device",
                "new_password": "replacement expired proof password",
                "recovery_token": recovery.recovery_token,
                "totp_code": pyotp.TOTP(replacement_totp_secret).now(),
            },
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        with runtime_sessions() as db:
            challenge_response = AdminDeviceProofService(db, settings).issue_challenge(
                purpose="RECOVERY_COMPLETE",
                action=None,
                admin_id=admin_id,
                body_sha256=raw_body_sha256(body),
                correlation_id=str(correlation_id),
                device_id=device_id,
                device_key_marker=key_marker,
                device_key_version=1,
                method="POST",
                path="/admin/security/recovery/complete",
                query_sha256=canonical_admin_query_sha256(b""),
                read_purpose=None,
                session_id=None,
                identity=None,
            )
        signature = _base64url(
            private_key.sign(
                challenge_response["signing_payload"].encode("utf-8"),
                ec.ECDSA(hashes.SHA256()),
            )
        )

        state_sql = text(
            "SELECT control.password_hash, control.totp_secret_fingerprint, "
            "control.last_totp_timecode, control.security_state, "
            "control.state_version, code.code_sha256, code.used_at, "
            "recovery.expires_at, recovery.completed_at, "
            "capability.totp_secret_fingerprint AS private_totp_fingerprint, "
            "capability.pending_recovery_token_sha256, "
            "capability.pending_recovery_expires_at, "
            "capability.pending_next_totp_fingerprint "
            "FROM admin_security_controls AS control "
            "JOIN admin_security_recovery_transactions AS recovery "
            "ON recovery.admin_id = control.admin_id "
            "JOIN admin_security_recovery_codes AS code "
            "ON code.id = recovery.recovery_code_id "
            "JOIN walksafe_recovery_custody_capabilities AS capability "
            "ON capability.admin_id = control.admin_id "
            "WHERE control.admin_id = :admin_id AND recovery.id = :transaction_id"
        )
        state_parameters = {"admin_id": admin_id, "transaction_id": transaction_id}
        with engine.connect() as connection:
            before = connection.execute(
                state_sql,
                state_parameters,
            ).mappings().one()
        expected_candidate = hashlib.sha256(
            replacement_totp_secret.encode("utf-8")
        ).hexdigest()
        assert before["security_state"] == "RECOVERY_IN_PROGRESS"
        assert before["completed_at"] is None
        assert before["pending_recovery_token_sha256"] == hashlib.sha256(
            recovery.recovery_token.encode("utf-8")
        ).hexdigest()
        assert before["pending_recovery_expires_at"] == before["expires_at"]
        assert before["pending_next_totp_fingerprint"] == expected_candidate

        monkeypatch.setattr(database_api, "SessionLocal", runtime_sessions)
        app = FastAPI()
        app.include_router(admin_security_api.create_router(settings))
        app.add_middleware(FieldTestSecurityMiddleware, settings=settings)
        response = ASGITestClient(app).post(
            "/admin/security/recovery/complete",
            content=body,
            headers={
                "Content-Type": "application/json",
                "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
                "X-WalkSafe-Role": ADMIN_ROLE,
                "X-WalkSafe-Audience": ADMIN_AUDIENCE,
                "X-WalkSafe-Device-Id": device_id,
                "X-WalkSafe-Device-Challenge-Id": challenge_response[
                    "challenge_id"
                ],
                "X-WalkSafe-Device-Signature": signature,
                "X-WalkSafe-Correlation-Id": str(correlation_id),
            },
        )

        assert response.status_code == 410
        assert response.json()["detail"]["code"] == "admin_recovery_expired"
        with engine.connect() as connection:
            after = connection.execute(
                state_sql,
                state_parameters,
            ).mappings().one()
            consumed_at = connection.execute(
                text(
                    "SELECT consumed_at FROM admin_device_proof_challenges "
                    "WHERE id = :challenge_id"
                ),
                {"challenge_id": uuid.UUID(challenge_response["challenge_id"])},
            ).scalar_one()
            audit = connection.execute(
                text(
                    "SELECT action, outcome, admin_id, device_id, details "
                    "FROM admin_security_audits "
                    "WHERE admin_id = :admin_id AND action = 'recovery.complete' "
                    "ORDER BY sequence DESC LIMIT 1"
                ),
                {"admin_id": admin_id},
            ).mappings().one()

        assert consumed_at is not None
        assert after["security_state"] == "RECOVERY_REQUIRED"
        assert after["state_version"] == before["state_version"] + 1
        assert (
            after["pending_recovery_token_sha256"],
            after["pending_recovery_expires_at"],
            after["pending_next_totp_fingerprint"],
        ) == (None, None, None)
        for unchanged in (
            "password_hash",
            "totp_secret_fingerprint",
            "last_totp_timecode",
            "code_sha256",
            "used_at",
            "expires_at",
            "completed_at",
            "private_totp_fingerprint",
        ):
            assert after[unchanged] == before[unchanged]
        assert audit == {
            "action": "recovery.complete",
            "outcome": "DENIED",
            "admin_id": admin_id,
            "device_id": device_id,
            "details": {"reason": "expired", "next_state": "RECOVERY_REQUIRED"},
        }
    finally:
        event.remove(runtime_sessions, "after_begin", assume_runtime_role)
        engine.dispose()
        clean_test_storage()


def test_provision_register_rotate_and_idempotent_releases_row_lock() -> None:
    first_private_key = _private_key()
    first_der = _spki_der(first_private_key)
    register_db = _FakeSession(
        [_Result(values=[_recovery_control(state="NORMAL")]), _Result(values=[])]
    )

    first = provision_admin_device_key(
        register_db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=1,
        public_key_spki_der=first_der,
    )
    registered = register_db.added[0]

    assert first.status == "ACTIVE" and first.idempotent is False
    idempotent_db = _FakeSession(
        [
            _Result(values=[_recovery_control(state="NORMAL")]),
            _Result(values=[registered]),
        ]
    )
    repeated = provision_admin_device_key(
        idempotent_db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=1,
        public_key_spki_der=first_der,
    )
    assert repeated.idempotent is True
    assert idempotent_db.commits == 1

    second_der = _spki_der(_private_key())
    rotate_db = _FakeSession(
        [
            _Result(values=[_recovery_control(state="NORMAL")]),
            _Result(values=[registered]),
        ]
    )
    rotated = provision_admin_device_key(
        rotate_db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=2,
        public_key_spki_der=second_der,
    )
    assert rotated.key_version == 2
    assert registered.status == "REVOKED" and registered.revoked_at is not None
    assert rotate_db.added[0].status == "ACTIVE"


def test_provision_during_recovery_only_allows_active_recovery_device() -> None:
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    allowed = _FakeSession(
        [
            _Result(),
            _Result(values=[_recovery_control()]),
            _Result(values=[_recovery_transaction(now)]),
            _Result(values=[]),
        ],
        dialect="postgresql",
    )

    result = provision_admin_device_key(
        allowed,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=1,
        public_key_spki_der=_spki_der(_private_key()),
        now=now,
    )

    assert result.status == "ACTIVE"
    assert "pg_advisory_xact_lock" in str(allowed.statements[0])
    assert "admin_security_controls" in str(allowed.statements[1]).lower()
    assert "admin_security_recovery_transactions" in str(
        allowed.statements[2]
    ).lower()

    denied = _FakeSession(
        [
            _Result(values=[_recovery_control()]),
            _Result(values=[_recovery_transaction(now)]),
        ]
    )
    with pytest.raises(AdminSecurityError) as other_device:
        provision_admin_device_key(
            denied,
            admin_id=ADMIN_ID,
            device_id="android-device-9999",
            key_version=1,
            public_key_spki_der=_spki_der(_private_key()),
            now=now,
        )
    assert other_device.value.code == "admin_device_key_provisioning_not_allowed"
    assert denied.rollbacks == 1


def test_provision_allows_trusted_recovery_required_bootstrap() -> None:
    db = _FakeSession(
        [_Result(values=[_recovery_control(state="RECOVERY_REQUIRED")]), _Result(values=[])]
    )

    provisioned = provision_admin_device_key(
        db,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        key_version=1,
        public_key_spki_der=_spki_der(_private_key()),
    )

    assert provisioned.status == "ACTIVE"
    assert db.commits == 1


def test_local_cli_accepts_android_canonical_base64url_without_private_material(
    capsys: pytest.CaptureFixture[str],
) -> None:
    der = _spki_der(_private_key())
    db = _FakeSession(
        [_Result(values=[_recovery_control(state="NORMAL")]), _Result(values=[])]
    )

    status = provision_cli.main(
        [
            "--admin-id",
            ADMIN_ID,
            "--device-id",
            DEVICE_ID,
            "--key-version",
            "1",
            "--expected-key-marker",
            hashlib.sha256(der).hexdigest(),
            "--public-key-spki-base64url",
            _base64url(der),
        ],
        session_factory=lambda: db,
    )

    output = capsys.readouterr().out.strip()
    assert status == 0 and db.closed is True
    assert json.loads(output)["key_marker"] == hashlib.sha256(der).hexdigest()
    assert _base64url(der) not in output
    with pytest.raises(ValueError, match="canonical"):
        provision_cli.decode_canonical_base64url(_base64url(der) + "=")


def test_local_cli_rejects_key_marker_mismatch_before_service_or_database(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    der = _spki_der(_private_key())
    service_called = False
    marker_comparisons: list[tuple[str, str]] = []
    original_compare_digest = provision_cli.secrets.compare_digest

    def unexpected_service(*_args: Any, **_kwargs: Any) -> None:
        nonlocal service_called
        service_called = True
        raise AssertionError("provisioning service must not be called")

    monkeypatch.setattr(
        provision_cli,
        "provision_admin_device_key",
        unexpected_service,
    )
    def compare_digest(actual: Any, expected: Any) -> bool:
        if isinstance(actual, str) and isinstance(expected, str):
            marker_comparisons.append((actual, expected))
            return False
        return original_compare_digest(actual, expected)

    monkeypatch.setattr(provision_cli.secrets, "compare_digest", compare_digest)

    with pytest.raises(SystemExit) as rejected:
        provision_cli.main(
            [
                "--admin-id",
                ADMIN_ID,
                "--device-id",
                DEVICE_ID,
                "--key-version",
                "1",
                "--expected-key-marker",
                "0" * 64,
                "--public-key-spki-base64url",
                _base64url(der),
            ],
            session_factory=lambda: pytest.fail("database must not be opened"),
        )

    assert rejected.value.code == 2
    assert service_called is False
    assert marker_comparisons == [(hashlib.sha256(der).hexdigest(), "0" * 64)]
    assert "does not match --expected-key-marker" in capsys.readouterr().err


def test_local_cli_requires_expected_key_marker_before_database(
    capsys: pytest.CaptureFixture[str],
) -> None:
    der = _spki_der(_private_key())

    with pytest.raises(SystemExit) as rejected:
        provision_cli.main(
            [
                "--admin-id",
                ADMIN_ID,
                "--device-id",
                DEVICE_ID,
                "--key-version",
                "1",
                "--public-key-spki-base64url",
                _base64url(der),
            ],
            session_factory=lambda: pytest.fail("database must not be opened"),
        )

    assert rejected.value.code == 2
    assert "--expected-key-marker" in capsys.readouterr().err


def test_local_cli_sanitizes_device_key_policy_denial(
    capsys: pytest.CaptureFixture[str],
) -> None:
    der = _spki_der(_private_key())
    db = _FakeSession([_Result(values=[_recovery_control()]), _Result(values=[])])

    with pytest.raises(SystemExit) as denied:
        provision_cli.main(
            [
                "--admin-id",
                ADMIN_ID,
                "--device-id",
                "android-device-9999",
                "--key-version",
                "1",
                "--expected-key-marker",
                hashlib.sha256(der).hexdigest(),
                "--public-key-spki-base64url",
                _base64url(der),
            ],
            session_factory=lambda: db,
        )

    assert denied.value.code == 2
    assert "not allowed" in capsys.readouterr().err


def _settings() -> SimpleNamespace:
    return SimpleNamespace(
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
        max_upload_bytes=32,
        max_report_metadata_bytes=32,
    )


def _headers(*, read_purpose: str | None = None) -> list[tuple[bytes, bytes]]:
    values = [
        (b"x-walksafe-app-kind", ADMIN_APP_KIND.encode()),
        (b"x-walksafe-role", ADMIN_ROLE.encode()),
        (b"x-walksafe-audience", ADMIN_AUDIENCE.encode()),
        (b"x-walksafe-device-id", DEVICE_ID.encode()),
        (b"authorization", b"Bearer valid-token"),
        (b"x-walksafe-device-challenge-id", CHALLENGE_ID.hex.encode()),
        (b"x-walksafe-device-signature", b"signed-proof"),
        (b"x-walksafe-correlation-id", str(CORRELATION_ID).encode()),
    ]
    # UUID.hex is deliberately replaced with the canonical form below.
    values[5] = (values[5][0], str(CHALLENGE_ID).encode())
    if read_purpose is not None:
        values.append((b"x-walksafe-read-purpose", read_purpose.encode()))
    return values


def _scope(
    method: str,
    path: str,
    *,
    headers: list[tuple[bytes, bytes]],
    query: bytes = b"",
) -> dict[str, Any]:
    return {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": method,
        "scheme": "https",
        "path": path,
        "raw_path": path.encode(),
        "query_string": query,
        "headers": headers,
        "client": ("127.0.0.1", 1),
        "server": ("test", 443),
        "root_path": "",
    }


def _verified(kwargs: dict[str, Any]) -> VerifiedAdminDeviceProof:
    return VerifiedAdminDeviceProof(
        admin_id=kwargs["expected_admin_id"],
        device_id=kwargs["expected_device_id"],
        session_id=kwargs["expected_session_id"],
        challenge_id=CHALLENGE_ID,
        correlation_id=CORRELATION_ID,
        action=kwargs["expected_action"],
        purpose=kwargs["expected_purpose"],
        read_purpose=kwargs["expected_read_purpose"],
        method=kwargs["expected_method"],
        path=kwargs["expected_path"],
        body_sha256=raw_body_sha256(kwargs["raw_body"]),
        query_sha256=canonical_admin_query_sha256(kwargs["raw_query_string"]),
        device_key_marker="1" * 64,
        device_key_version=1,
    )


def _run_middleware(
    *,
    method: str,
    path: str,
    messages: list[dict[str, Any]],
    headers: list[tuple[bytes, bytes]],
    query: bytes = b"",
    settings: SimpleNamespace | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    verifier_calls: list[dict[str, Any]] = []
    downstream_calls: list[dict[str, Any]] = []

    def authorize(_settings: Any, **kwargs: Any) -> AdminSessionIdentity:
        downstream_calls.append({"authorization": kwargs})
        return _identity()

    def verify(_settings: Any, **kwargs: Any) -> VerifiedAdminDeviceProof:
        verifier_calls.append(kwargs)
        return _verified(kwargs)

    async def downstream(scope: dict[str, Any], receive: Any, send: Any) -> None:
        replayed: list[dict[str, Any]] = []
        raw = bytearray()
        while True:
            message = await receive()
            replayed.append(message)
            if message["type"] != "http.request":
                break
            raw.extend(message.get("body", b""))
            if not message.get("more_body", False):
                break
        downstream_calls.append(
            {
                "raw_body": bytes(raw),
                "messages": replayed,
                "state": dict(scope.get("state") or {}),
            }
        )
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    queue = list(messages)
    sent: list[dict[str, Any]] = []

    async def receive() -> dict[str, Any]:
        return queue.pop(0)

    async def send(message: dict[str, Any]) -> None:
        sent.append(message)

    middleware = FieldTestSecurityMiddleware(
        downstream,
        settings or _settings(),
        admin_session_authorizer=authorize,
        admin_device_proof_verifier=verify,
        admin_security_denial_recorder=lambda **_kwargs: None,
    )
    asyncio.run(
        middleware(
            _scope(method, path, headers=headers, query=query),
            receive,
            send,
        )
    )
    return verifier_calls, downstream_calls, sent


@pytest.mark.parametrize(
    ("suffix", "action"),
    [
        ("review-decisions", "report.review.decide"),
        ("deliveries", "report.delivery.create"),
    ],
)
def test_workflow_post_requires_standard_session_proof_and_replays_exact_chunks(
    suffix: str,
    action: str,
) -> None:
    body = b'{"reason":"\xed\x95\x84\xec\x88\x98"}'
    path = f"/reports/{REPORT_ID}/{suffix}"
    messages = [
        {"type": "http.request", "body": body[:7], "more_body": True},
        {"type": "http.request", "body": body[7:], "more_body": False},
    ]
    headers = _headers() + [(b"content-length", str(len(body)).encode())]

    verifier, downstream, sent = _run_middleware(
        method="POST",
        path=path,
        messages=messages,
        headers=headers,
        query=b"",
    )

    assert sent[0]["status"] == 204
    assert verifier[0]["expected_action"] == action
    assert verifier[0]["expected_read_purpose"] is None
    assert verifier[0]["raw_body"] == body
    assert verifier[0]["raw_query_string"] == b""
    assert downstream[0]["authorization"]["high_risk_action"] is None
    assert downstream[0]["authorization"]["reconfirmation_nonce"] == ""
    assert downstream[1]["raw_body"] == body
    assert downstream[1]["messages"] == messages
    assert isinstance(
        downstream[1]["state"]["admin_device_proof"],
        VerifiedAdminDeviceProof,
    )


@pytest.mark.parametrize(
    "path",
    [
        f"/reports/{REPORT_ID}/original-access-grants",
        f"/reports/{REPORT_ID}/review-decisions",
        f"/reports/{REPORT_ID}/deliveries",
        f"/admin/reports/{REPORT_ID}/delivery-packages",
    ],
)
def test_admin_report_mutations_reject_query_before_consuming_proof(path: str) -> None:
    verifier, _downstream, sent = _run_middleware(
        method="POST",
        path=path,
        messages=[{"type": "http.request", "body": b"{}", "more_body": False}],
        headers=_headers(),
        query=b"ignored=true",
    )

    assert verifier == []
    assert sent[0]["status"] == 422
    assert json.loads(sent[1]["body"])["detail"]["code"] == (
        "admin_device_proof_binding_invalid"
    )


@pytest.mark.parametrize(
    "path",
    [
        "/reports/ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF/original-access-grants",
        "/reports/ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF/review-decisions",
        "/reports/ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF/deliveries",
        "/admin/reports/ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF/delivery-packages",
    ],
)
def test_admin_report_mutations_reject_noncanonical_uuid_path(path: str) -> None:
    verifier, _downstream, sent = _run_middleware(
        method="POST",
        path=path,
        messages=[{"type": "http.request", "body": b"{}", "more_body": False}],
        headers=_headers(),
    )

    assert verifier == []
    assert sent[0]["status"] == 422
    assert json.loads(sent[1]["body"])["detail"]["code"] == (
        "admin_device_proof_binding_invalid"
    )


@pytest.mark.parametrize("size", [65_536, 65_537])
def test_admin_report_mutation_body_limit_matches_database_claim(size: int) -> None:
    body = b'{"reason":"ok"}' + b" " * (size - len(b'{"reason":"ok"}'))
    verifier, downstream, sent = _run_middleware(
        method="POST",
        path=f"/reports/{REPORT_ID}/review-decisions",
        messages=[{"type": "http.request", "body": body, "more_body": False}],
        headers=_headers() + [(b"content-length", str(size).encode())],
    )

    if size == 65_536:
        assert sent[0]["status"] == 204
        assert len(verifier) == 1
        assert downstream[-1]["raw_body"] == body
    else:
        assert verifier == []
        assert sent[0]["status"] == 413
        assert json.loads(sent[1]["body"])["detail"]["code"] == (
            "request_body_too_large"
        )


def test_signed_custody_request_reaches_middleware_route_and_service() -> None:
    private_key = _private_key()
    key = _device_key(private_key)
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    body = json.dumps(
        {
            "custody_reference": (
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"
            ),
            "material_kind": "SECURITY_KEY",
            "separate_encrypted_backup_confirmed": True,
            "storage_location": "OFF_PHONE",
        },
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    path = "/admin/security/recovery-custody/attest"
    issue_db = _FakeSession([_Result(one=key), _Result(values=[])])
    challenge_response = AdminDeviceProofService(issue_db).issue_challenge(
        purpose="ACTION",
        action="recovery.custody.attest",
        admin_id=ADMIN_ID,
        body_sha256=raw_body_sha256(body),
        correlation_id=str(CORRELATION_ID),
        device_id=DEVICE_ID,
        device_key_marker=key.key_marker,
        device_key_version=key.key_version,
        method="POST",
        path=path,
        query_sha256=canonical_admin_query_sha256(b""),
        read_purpose=None,
        session_id=str(SESSION_ID),
        identity=_identity(),
        now=now,
    )
    signature = _base64url(
        private_key.sign(
            challenge_response["signing_payload"].encode("utf-8"),
            ec.ECDSA(hashes.SHA256()),
        )
    )
    challenge = issue_db.added[0]

    class RecoveryService:
        def __init__(self) -> None:
            self.calls: list[dict[str, Any]] = []

        def attest_recovery_custody(self, identity, **kwargs):
            self.calls.append({"identity": identity, **kwargs})
            return {
                "security_state": "NORMAL",
                "state_version": "2",
                "observed_at": now.isoformat(),
                "recovery_custody_state": "ATTESTED",
                "recovery_custody_attested_at": now.isoformat(),
            }

    service = RecoveryService()
    settings = _settings()
    app = FastAPI()
    app.include_router(
        admin_security_api.create_router(
            settings,
            service_factory=lambda: service,
        )
    )

    def verify(_settings_value: Any, **kwargs: Any) -> VerifiedAdminDeviceProof:
        return verify_admin_device_proof(
            _FakeSession([_Result(one=challenge), _Result(one=key)]),
            **kwargs,
            now=now + timedelta(seconds=1),
        )

    app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=settings,
        admin_session_authorizer=lambda _settings_value, **_kwargs: _identity(),
        admin_device_proof_verifier=verify,
    )
    response = ASGITestClient(app).post(
        path,
        content=body,
        headers={
            "Authorization": "Bearer valid-token",
            "Content-Type": "application/json",
            "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
            "X-WalkSafe-Role": ADMIN_ROLE,
            "X-WalkSafe-Audience": ADMIN_AUDIENCE,
            "X-WalkSafe-Device-Id": DEVICE_ID,
            "X-WalkSafe-Device-Challenge-Id": challenge_response["challenge_id"],
            "X-WalkSafe-Device-Signature": signature,
            "X-WalkSafe-Correlation-Id": str(CORRELATION_ID),
        },
    )

    assert response.status_code == 200
    assert challenge.consumed_at == now + timedelta(seconds=1)
    assert service.calls == [
        {
            "identity": _identity(),
            "custody_reference": "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY",
            "material_kind": "SECURITY_KEY",
            "separate_encrypted_backup_confirmed": True,
            "storage_location": "OFF_PHONE",
        }
    ]


def test_workflow_get_binds_read_purpose_and_nonworkflow_admin_route_is_unchanged() -> None:
    path = f"/reports/{REPORT_ID}/review-decisions"
    verifier, downstream, sent = _run_middleware(
        method="GET",
        path=path,
        messages=[{"type": "http.request", "body": b"", "more_body": False}],
        headers=_headers(read_purpose="report.review_decisions"),
        query=b"b=&a=2&a=1",
    )
    assert sent[0]["status"] == 204
    assert verifier[0]["expected_action"] is None
    assert verifier[0]["expected_read_purpose"] == "report.review_decisions"
    assert downstream[-1]["state"]["admin_device_proof"].purpose == "ACTION"

    verifier, downstream, sent = _run_middleware(
        method="GET",
        path="/admin/security/state",
        messages=[{"type": "http.request", "body": b"", "more_body": False}],
        headers=_headers()[:5],
    )
    assert sent[0]["status"] == 204
    assert verifier == []
    assert "admin_device_proof" not in downstream[-1]["state"]


@pytest.mark.parametrize(
    ("path", "read_purpose"),
    [
        (
            f"/admin/incidents/{INCIDENT_ID}/history",
            "admin.incident.history",
        ),
        (
            f"/reports/{REPORT_ID}/review-decisions/history",
            "report.review_decisions",
        ),
        (
            f"/reports/{REPORT_ID}/deliveries/history",
            "report.delivery_events",
        ),
    ],
)
def test_history_get_binds_exact_read_purpose(
    path: str,
    read_purpose: str,
) -> None:
    verifier, downstream, sent = _run_middleware(
        method="GET",
        path=path,
        messages=[{"type": "http.request", "body": b"", "more_body": False}],
        headers=_headers(read_purpose=read_purpose),
        query=b"limit=25",
    )

    assert sent[0]["status"] == 204
    assert verifier[0]["expected_action"] is None
    assert verifier[0]["expected_path"] == path
    assert verifier[0]["expected_read_purpose"] == read_purpose
    assert verifier[0]["raw_query_string"] == b"limit=25"
    assert downstream[-1]["state"]["admin_device_proof"].purpose == "ACTION"


@pytest.mark.parametrize(
    ("path", "read_purpose"),
    [
        (
            f"/admin/incidents/{INCIDENT_ID}/history",
            "admin.incident.history",
        ),
        (
            f"/reports/{REPORT_ID}/review-decisions/history",
            "report.review_decisions",
        ),
        (
            f"/reports/{REPORT_ID}/deliveries/history",
            "report.delivery_events",
        ),
    ],
)
def test_history_challenge_accepts_canonical_uuid_path(
    path: str,
    read_purpose: str,
) -> None:
    assert validate_device_proof_challenge_binding(
        purpose="ACTION",
        action=None,
        admin_id=ADMIN_ID,
        device_id=DEVICE_ID,
        session_id=str(SESSION_ID),
        method="GET",
        path=path,
        read_purpose=read_purpose,
        identity=_identity(),
    ) == SESSION_ID


@pytest.mark.parametrize(
    ("path", "read_purpose"),
    [
        (
            "/admin/incidents/ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF/history",
            "admin.incident.history",
        ),
        (
            "/reports/not-a-uuid/review-decisions/history",
            "report.review_decisions",
        ),
        (
            "/reports/ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF/deliveries/history",
            "report.delivery_events",
        ),
        (
            f"/admin/incidents/{'a' * 129}/history",
            "admin.incident.history",
        ),
        (
            f"/reports/{'a' * 129}/review-decisions/history",
            "report.review_decisions",
        ),
        (
            "/reports/11111111-1111-0111-8111-111111111111/"
            "deliveries/history",
            "report.delivery_events",
        ),
    ],
)
def test_history_challenge_rejects_noncanonical_uuid_path(
    path: str,
    read_purpose: str,
) -> None:
    key = _device_key(_private_key())
    request = {
        **_challenge_request(key, body=b"", query=b"limit=25"),
        "action": None,
        "body_sha256": raw_body_sha256(b""),
        "method": "GET",
        "path": path,
        "query_sha256": canonical_admin_query_sha256(b"limit=25"),
        "read_purpose": read_purpose,
    }

    assert is_admin_device_proof_workflow_request("GET", path) is True
    with pytest.raises(AdminSecurityError) as rejected:
        AdminDeviceProofService(_FakeSession([])).issue_challenge(**request)
    assert rejected.value.code == "admin_device_proof_binding_invalid"


def test_proof_scope_predicate_is_closed_to_the_admin_workflow_routes() -> None:
    review = f"/reports/{REPORT_ID}/review-decisions"
    delivery = f"/reports/{REPORT_ID}/deliveries"
    custody = "/admin/security/recovery-custody/attest"
    report_lost = "/admin/security/devices/android-device-0002/report-lost"
    assert requires_admin_device_proof(review, "GET") is True
    assert requires_admin_device_proof(review, "POST") is True
    assert requires_admin_device_proof(delivery, "GET") is True
    assert requires_admin_device_proof(delivery, "POST") is True
    assert requires_admin_device_proof(
        f"/admin/incidents/{INCIDENT_ID}/history", "GET"
    ) is True
    assert requires_admin_device_proof(
        f"/reports/{REPORT_ID}/review-decisions/history", "GET"
    ) is True
    assert requires_admin_device_proof(
        f"/reports/{REPORT_ID}/deliveries/history", "GET"
    ) is True
    assert requires_admin_device_proof(custody, "POST") is True
    assert requires_admin_device_proof(report_lost, "POST") is True
    assert requires_admin_device_proof(review, "PATCH") is False
    assert requires_admin_device_proof(custody, "GET") is False
    assert requires_admin_device_proof(
        f"/admin/incidents/{INCIDENT_ID}/history", "POST"
    ) is False
    assert requires_admin_device_proof(
        f"/reports/{REPORT_ID}/review-decisions/history", "POST"
    ) is False
    assert requires_admin_device_proof(
        f"/reports/{REPORT_ID}/deliveries/history/extra", "GET"
    ) is False
    assert requires_admin_device_proof(
        "/admin/security/devices/short/report-lost", "POST"
    ) is False
    assert requires_admin_device_proof("/admin/security/state", "GET") is False
    assert requires_admin_device_proof("/reports", "GET") is False


def test_android_header_names_are_exact_and_old_proof_names_are_rejected() -> None:
    path = f"/reports/{REPORT_ID}/deliveries"
    old_headers = [
        item
        for item in _headers()
        if item[0]
        not in {b"x-walksafe-device-challenge-id", b"x-walksafe-device-signature"}
    ] + [
        (b"x-walksafe-device-proof-challenge-id", str(CHALLENGE_ID).encode()),
        (b"x-walksafe-device-proof-signature", b"signed-proof"),
    ]

    verifier, downstream, sent = _run_middleware(
        method="POST",
        path=path,
        messages=[{"type": "http.request", "body": b"{}", "more_body": False}],
        headers=old_headers,
    )
    assert verifier == []
    assert len(downstream) == 1  # bearer authorization only
    assert sent[0]["status"] == 401
    payload = json.loads(sent[1]["body"])
    assert payload["detail"]["code"] == "admin_device_proof_required"


def test_public_login_proof_binds_raw_body_and_challenge_endpoint_is_excluded() -> None:
    body = json.dumps(
        {
            "admin_id": ADMIN_ID,
            "password": "password-value",
            "totp_code": "123456",
            "device_id": DEVICE_ID,
            "device_label": "관리자 단말",
        },
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode()
    messages = [
        {"type": "http.request", "body": body[:10], "more_body": True},
        {"type": "http.request", "body": body[10:], "more_body": False},
    ]
    verifier, downstream, sent = _run_middleware(
        method="POST",
        path="/admin/security/sessions",
        messages=messages,
        headers=_headers() + [(b"content-length", str(len(body)).encode())],
    )
    assert sent[0]["status"] == 204
    assert verifier[0]["expected_purpose"] == "LOGIN"
    assert verifier[0]["expected_session_id"] is None
    assert verifier[0]["raw_body"] == body
    assert downstream[-1]["messages"] == messages

    verifier, downstream, sent = _run_middleware(
        method="POST",
        path="/admin/security/device-proof/challenges",
        messages=[{"type": "http.request", "body": b"{}", "more_body": False}],
        headers=_headers()[:5],
    )
    assert verifier == [] and sent[0]["status"] == 204


def test_recovery_complete_middleware_requires_registered_device_proof() -> None:
    body = json.dumps(
        {
            "recovery_token": "r" * 48,
            "new_password": "new-password-value",
            "totp_code": "123456",
            "device_id": DEVICE_ID,
            "device_label": "관리자 단말",
        },
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode()
    verifier, downstream, sent = _run_middleware(
        method="POST",
        path="/admin/security/recovery/complete",
        messages=[{"type": "http.request", "body": body, "more_body": False}],
        headers=_headers() + [(b"content-length", str(len(body)).encode())],
    )

    assert sent[0]["status"] == 204
    assert verifier[0]["expected_purpose"] == "RECOVERY_COMPLETE"
    assert verifier[0]["expected_admin_id"] == ADMIN_ID
    assert verifier[0]["expected_device_id"] == DEVICE_ID
    assert verifier[0]["expected_session_id"] is None
    assert downstream[-1]["raw_body"] == body


def test_signed_expired_recovery_complete_reaches_route_cleanup() -> None:
    private_key = _private_key()
    key = _device_key(private_key)
    now = datetime(2026, 8, 9, 12, 0, 0, tzinfo=timezone.utc)
    body = json.dumps(
        {
            "device_id": DEVICE_ID,
            "device_label": "관리자 단말",
            "new_password": "new-password-value",
            "recovery_token": "r" * 48,
            "totp_code": "123456",
        },
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    control = _recovery_control()
    transaction = _recovery_transaction(now)
    issue_db = _FakeSession(
        [
            _Result(values=[control]),
            _Result(values=[transaction]),
            _Result(one=key),
            _Result(values=[]),
        ]
    )
    challenge_response = AdminDeviceProofService(issue_db).issue_challenge(
        **_recovery_challenge_request(key, body=body),
        now=now,
    )
    challenge = issue_db.added[0]
    signature = _base64url(
        private_key.sign(
            challenge_response["signing_payload"].encode("utf-8"),
            ec.ECDSA(hashes.SHA256()),
        )
    )
    transaction.expires_at = now

    class RecoveryService:
        def __init__(self) -> None:
            self.calls: list[dict[str, Any]] = []

        def complete_recovery(self, **kwargs: Any) -> SessionGrant:
            self.calls.append(kwargs)
            return SessionGrant("new-access-token", "NORMAL", str(SESSION_ID))

    service = RecoveryService()
    settings = _settings()
    app = FastAPI()
    app.include_router(
        admin_security_api.create_router(
            settings,
            service_factory=lambda: service,
        )
    )

    def verify(_settings_value: Any, **kwargs: Any) -> VerifiedAdminDeviceProof:
        return verify_admin_device_proof(
            _FakeSession(
                [
                    _Result(one=challenge),
                    _Result(
                        values=[
                            SimpleNamespace(
                                context_status="RECOVERY_EXPIRED",
                                public_key_spki_der=key.public_key_spki_der,
                            )
                        ]
                    ),
                ],
                dialect="postgresql",
            ),
            **kwargs,
            runtime_totp_secret=settings.admin_totp_secret,
            credential_issuer_key="issuer-key-for-device-proof-tests-1234567890",
            now=now + timedelta(seconds=1),
        )

    app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=settings,
        admin_device_proof_verifier=verify,
    )
    response = ASGITestClient(app).post(
        "/admin/security/recovery/complete",
        content=body,
        headers={
            "Content-Type": "application/json",
            "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
            "X-WalkSafe-Role": ADMIN_ROLE,
            "X-WalkSafe-Audience": ADMIN_AUDIENCE,
            "X-WalkSafe-Device-Id": DEVICE_ID,
            "X-WalkSafe-Device-Challenge-Id": challenge_response["challenge_id"],
            "X-WalkSafe-Device-Signature": signature,
            "X-WalkSafe-Correlation-Id": str(CORRELATION_ID),
        },
    )

    assert response.status_code == 200
    assert response.json()["access_token"] == "new-access-token"
    assert challenge.consumed_at == now + timedelta(seconds=1)
    assert service.calls == [
        {
            "device_id": DEVICE_ID,
            "device_label": "관리자 단말",
            "new_password": "new-password-value",
            "recovery_token": "r" * 48,
            "source": "127.0.0.1",
            "totp_code": "123456",
        }
    ]


def test_delivery_get_binds_exact_purpose_and_ambiguous_headers_fail_closed() -> None:
    path = f"/reports/{REPORT_ID}/deliveries"
    verifier, _downstream, sent = _run_middleware(
        method="GET",
        path=path,
        messages=[{"type": "http.request", "body": b"", "more_body": False}],
        headers=_headers(read_purpose="report.delivery_events"),
    )
    assert sent[0]["status"] == 204
    assert verifier[0]["expected_read_purpose"] == "report.delivery_events"

    for headers, expected_status in (
        (_headers(), 401),
        (
            _headers(read_purpose="report.delivery_events")
            + [(b"x-walksafe-read-purpose", b"report.review_decisions")],
            403,
        ),
        (
            _headers(read_purpose="report.delivery_events")
            + [(b"x-walksafe-device-signature", b"second-signature")],
            403,
        ),
    ):
        verifier, downstream, sent = _run_middleware(
            method="GET",
            path=path,
            messages=[
                {"type": "http.request", "body": b"", "more_body": False}
            ],
            headers=headers,
        )
        assert verifier == []
        assert len(downstream) == 1
        assert sent[0]["status"] == expected_status


@pytest.mark.parametrize(
    ("messages", "content_length", "expected_status", "expected_code"),
    [
        (
            [{"type": "http.request", "body": b"x" * 65, "more_body": False}],
            None,
            413,
            "request_body_too_large",
        ),
        (
            [
                {"type": "http.request", "body": b"{}", "more_body": True},
                {"type": "http.disconnect"},
            ],
            None,
            400,
            "admin_device_proof_body_incomplete",
        ),
        (
            [{"type": "http.request", "body": b"{}", "more_body": False}],
            3,
            400,
            "admin_device_proof_body_incomplete",
        ),
    ],
)
def test_proof_capture_fails_closed_for_oversized_partial_or_length_mismatch(
    messages: list[dict[str, Any]],
    content_length: int | None,
    expected_status: int,
    expected_code: str,
) -> None:
    path = f"/reports/{REPORT_ID}/review-decisions"
    headers = _headers()
    if content_length is not None:
        headers.append((b"content-length", str(content_length).encode()))
    settings = _settings()
    settings.max_upload_bytes = -131040
    settings.max_report_metadata_bytes = -131040
    # 256 KiB overhead plus these values yields an exact 64-byte test cap.

    verifier, downstream, sent = _run_middleware(
        method="POST",
        path=path,
        messages=messages,
        headers=headers,
        settings=settings,
    )
    assert verifier == []
    assert len(downstream) == 1
    assert sent[0]["status"] == expected_status
    assert json.loads(sent[1]["body"])["detail"]["code"] == expected_code


def _challenge_response(payload: dict[str, Any]) -> dict[str, Any]:
    signed = {
        "action": payload["action"],
        "admin_id": payload["admin_id"],
        "body_sha256": payload["body_sha256"],
        "challenge_id": str(CHALLENGE_ID),
        "correlation_id": payload["correlation_id"],
        "device_id": payload["device_id"],
        "device_key_marker": payload["device_key_marker"],
        "device_key_version": payload["device_key_version"],
        "expires_at_epoch_ms": 120001,
        "issued_at_epoch_ms": 1,
        "method": payload["method"],
        "nonce": "A" * 43,
        "path": payload["path"],
        "purpose": payload["purpose"],
        "query_sha256": payload["query_sha256"],
        "read_purpose": payload["read_purpose"],
        "schema_version": ADMIN_DEVICE_PROOF_SCHEMA_VERSION,
        "session_id": payload["session_id"],
    }
    return {**signed, "signing_payload": canonical_device_proof_json(signed)}


class _FakeProofService:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    def issue_challenge(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append(kwargs)
        payload = {name: value for name, value in kwargs.items() if name != "identity"}
        return _challenge_response(payload)


def test_challenge_api_requires_exact13_physical_nulls_and_forbids_extra() -> None:
    settings = _settings()
    service = _FakeProofService()
    app = FastAPI()
    app.include_router(
        admin_security_api.create_router(
            settings,
            device_proof_service_factory=lambda: service,
        )
    )
    client = ASGITestClient(app)
    key = _device_key(_private_key())
    payload = {
        "action": None,
        "admin_id": ADMIN_ID,
        "body_sha256": "0" * 64,
        "correlation_id": str(CORRELATION_ID),
        "device_id": DEVICE_ID,
        "device_key_marker": key.key_marker,
        "device_key_version": 1,
        "method": "POST",
        "path": "/admin/security/sessions",
        "purpose": "LOGIN",
        "query_sha256": hashlib.sha256(b"").hexdigest(),
        "read_purpose": None,
        "session_id": None,
    }
    headers = {
        "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
        "X-WalkSafe-Role": ADMIN_ROLE,
        "X-WalkSafe-Audience": ADMIN_AUDIENCE,
        "X-WalkSafe-Device-Id": DEVICE_ID,
        "X-WalkSafe-Correlation-Id": str(CORRELATION_ID),
    }

    response = client.post(
        "/admin/security/device-proof/challenges",
        json=payload,
        headers=headers,
    )
    assert response.status_code == 200
    assert set(response.json()) == {*ADMIN_DEVICE_PROOF_SIGNED_FIELDS, "signing_payload"}
    assert service.calls[0]["identity"] is None

    missing_null = dict(payload)
    missing_null.pop("action")
    assert client.post(
        "/admin/security/device-proof/challenges",
        json=missing_null,
        headers=headers,
    ).status_code == 422
    with_extra = {**payload, "challenge_type": "LOGIN"}
    assert client.post(
        "/admin/security/device-proof/challenges",
        json=with_extra,
        headers=headers,
    ).status_code == 422
    for invalid_version in ("1", True):
        wrong_type = {**payload, "device_key_version": invalid_version}
        assert client.post(
            "/admin/security/device-proof/challenges",
            json=wrong_type,
            headers=headers,
        ).status_code == 422


def test_action_challenge_api_requires_and_forwards_bound_bearer_identity() -> None:
    settings = _settings()
    service = _FakeProofService()
    authorization_calls: list[Any] = []
    app = FastAPI()
    app.include_router(
        admin_security_api.create_router(
            settings,
            device_proof_service_factory=lambda: service,
            action_challenge_authorizer=lambda request: (
                authorization_calls.append(request),
                _identity(),
            )[1],
        )
    )
    key = _device_key(_private_key())
    payload = {
        "action": "report.delivery.create",
        "admin_id": ADMIN_ID,
        "body_sha256": "0" * 64,
        "correlation_id": str(CORRELATION_ID),
        "device_id": DEVICE_ID,
        "device_key_marker": key.key_marker,
        "device_key_version": 1,
        "method": "POST",
        "path": f"/reports/{REPORT_ID}/deliveries",
        "purpose": "ACTION",
        "query_sha256": hashlib.sha256(b"").hexdigest(),
        "read_purpose": None,
        "session_id": str(SESSION_ID),
    }
    response = ASGITestClient(app).post(
        "/admin/security/device-proof/challenges",
        json=payload,
        headers={
            "Authorization": "Bearer bound-session",
            "X-WalkSafe-App-Kind": ADMIN_APP_KIND,
            "X-WalkSafe-Role": ADMIN_ROLE,
            "X-WalkSafe-Audience": ADMIN_AUDIENCE,
            "X-WalkSafe-Device-Id": DEVICE_ID,
            "X-WalkSafe-Correlation-Id": str(CORRELATION_ID),
        },
    )

    assert response.status_code == 200
    assert len(authorization_calls) == 1
    assert service.calls[0]["identity"] == _identity()


def test_fp008_actions_are_registered_standard_operations() -> None:
    review = classify_admin_operation(
        "POST", f"/reports/{REPORT_ID}/review-decisions"
    )
    delivery = classify_admin_operation("POST", f"/reports/{REPORT_ID}/deliveries")
    assert (review.action, review.risk) == ("report.review.decide", "STANDARD")
    assert (delivery.action, delivery.risk) == ("report.delivery.create", "STANDARD")


def test_wave5_admin_report_actions_are_registered_high_risk_operations() -> None:
    status = classify_admin_operation(
        "PATCH", f"/admin/reports/{REPORT_ID}/status"
    )
    package = classify_admin_operation(
        "POST", f"/admin/reports/{REPORT_ID}/delivery-packages"
    )
    original = classify_admin_operation(
        "POST", f"/reports/{REPORT_ID}/original-access-grants"
    )

    assert status is not None
    assert (status.action, status.risk) == ("admin.report.status.update", "HIGH")
    assert package is not None
    assert (package.action, package.risk) == (
        "admin.report.delivery_package.create",
        "HIGH",
    )
    assert original is not None
    assert (original.action, original.risk) == ("report.original.grant", "HIGH")
    assert is_admin_device_proof_workflow_request(
        "PATCH", f"/admin/reports/{REPORT_ID}/status"
    )
    assert is_admin_device_proof_workflow_request(
        "POST", f"/admin/reports/{REPORT_ID}/delivery-packages"
    )
    assert is_admin_device_proof_workflow_request(
        "POST", f"/reports/{REPORT_ID}/original-access-grants"
    )
    assert not is_admin_device_proof_workflow_request(
        "GET", f"/reports/{REPORT_ID}/original-access-grants"
    )
    assert is_admin_device_proof_workflow_request("GET", "/admin/reports/audits")
    assert not is_admin_device_proof_workflow_request(
        "GET", f"/admin/reports/{REPORT_ID}/status"
    )


def test_openapi_exposes_exact_challenge_and_route_scoped_proof_headers() -> None:
    settings = _settings()
    app = FastAPI()
    app.include_router(admin_security_api.create_router(settings))

    @app.get("/reports/{report_id}/review-decisions")
    def get_review_decisions(report_id: uuid.UUID) -> list[Any]:
        del report_id
        return []

    @app.get("/reports/{report_id}/review-decisions/history")
    def get_review_decision_history(report_id: uuid.UUID) -> list[Any]:
        del report_id
        return []

    @app.post("/reports/{report_id}/review-decisions")
    def post_review_decision(report_id: uuid.UUID) -> dict[str, Any]:
        del report_id
        return {}

    @app.get("/reports/{report_id}/deliveries")
    def get_deliveries(report_id: uuid.UUID) -> list[Any]:
        del report_id
        return []

    @app.get("/reports/{report_id}/deliveries/history")
    def get_delivery_history(report_id: uuid.UUID) -> list[Any]:
        del report_id
        return []

    @app.get("/admin/incidents/{incident_id}/history")
    def get_incident_history(incident_id: uuid.UUID) -> list[Any]:
        del incident_id
        return []

    @app.post("/reports/{report_id}/deliveries")
    def post_delivery(report_id: uuid.UUID) -> dict[str, Any]:
        del report_id
        return {}

    install_walksafe_openapi_contract(app, settings)
    schema = app.openapi()
    schemes = schema["components"]["securitySchemes"]
    proof_schemes = {
        "WalkSafeAdminDeviceChallengeId",
        "WalkSafeAdminDeviceSignature",
        "WalkSafeCorrelationId",
    }

    assert schemes["WalkSafeAdminDeviceChallengeId"]["name"] == (
        "X-WalkSafe-Device-Challenge-Id"
    )
    assert schemes["WalkSafeAdminDeviceSignature"]["name"] == (
        "X-WalkSafe-Device-Signature"
    )
    assert schemes["WalkSafeCorrelationId"]["name"] == "X-WalkSafe-Correlation-Id"
    assert schemes["WalkSafeReadPurpose"]["name"] == "X-WalkSafe-Read-Purpose"

    for path, purpose in (
        ("/admin/security/sessions", "LOGIN"),
        ("/admin/security/recovery/complete", "RECOVERY_COMPLETE"),
    ):
        operation = schema["paths"][path]["post"]
        assert proof_schemes <= set(operation["security"][0])
        assert operation["x-walksafe-admin-device-proof"] == {
            "purpose": purpose,
            "action": None,
            "read_purpose": None,
            "session_id": None,
        }
    assert proof_schemes.isdisjoint(
        schema["paths"]["/admin/security/recovery/start"]["post"]["security"][0]
    )

    challenge = schema["paths"]["/admin/security/device-proof/challenges"]["post"]
    assert set(challenge["security"][0]) & proof_schemes == {
        "WalkSafeCorrelationId"
    }
    assert challenge["x-walksafe-device-proof-recursive"] is False
    assert challenge["x-walksafe-bearer-required-when-purpose"] == "ACTION"

    request_ref = challenge["requestBody"]["content"]["application/json"]["schema"][
        "$ref"
    ]
    request_schema = schema["components"]["schemas"][request_ref.rsplit("/", 1)[1]]
    exact13 = {
        "action",
        "admin_id",
        "body_sha256",
        "correlation_id",
        "device_id",
        "device_key_marker",
        "device_key_version",
        "method",
        "path",
        "purpose",
        "query_sha256",
        "read_purpose",
        "session_id",
    }
    assert set(request_schema["properties"]) == exact13
    assert set(request_schema["required"]) == exact13
    assert request_schema["additionalProperties"] is False

    response_ref = challenge["responses"]["200"]["content"]["application/json"][
        "schema"
    ]["$ref"]
    response_schema = schema["components"]["schemas"][response_ref.rsplit("/", 1)[1]]
    assert set(response_schema["properties"]) == {
        *ADMIN_DEVICE_PROOF_SIGNED_FIELDS,
        "signing_payload",
    }
    assert set(response_schema["required"]) == set(response_schema["properties"])
    assert response_schema["additionalProperties"] is False

    for path, method, action, read_purpose in (
        (
            "/admin/security/recovery-custody/attest",
            "post",
            "recovery.custody.attest",
            None,
        ),
        (
            "/admin/security/devices/{device_id}/report-lost",
            "post",
            "device.report_lost",
            None,
        ),
        (
            "/reports/{report_id}/review-decisions",
            "post",
            "report.review.decide",
            None,
        ),
        (
            "/reports/{report_id}/review-decisions",
            "get",
            None,
            "report.review_decisions",
        ),
        (
            "/reports/{report_id}/deliveries",
            "post",
            "report.delivery.create",
            None,
        ),
        (
            "/reports/{report_id}/deliveries",
            "get",
            None,
            "report.delivery_events",
        ),
        (
            "/admin/incidents/{incident_id}/history",
            "get",
            None,
            "admin.incident.history",
        ),
        (
            "/reports/{report_id}/review-decisions/history",
            "get",
            None,
            "report.review_decisions",
        ),
        (
            "/reports/{report_id}/deliveries/history",
            "get",
            None,
            "report.delivery_events",
        ),
    ):
        operation = schema["paths"][path][method]
        requirement = operation["security"][0]
        assert proof_schemes <= set(requirement)
        assert ("WalkSafeReadPurpose" in requirement) is (read_purpose is not None)
        assert operation["x-walksafe-admin-device-proof"] == {
            "purpose": "ACTION",
            "action": action,
            "read_purpose": read_purpose,
            "session_id": "authenticated-admin-session",
        }
