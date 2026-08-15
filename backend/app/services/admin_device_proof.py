"""P-256 device possession proof for administrator HTTP requests."""

from __future__ import annotations

import base64
import binascii
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
import re
import secrets
from typing import Any, Literal
import uuid

from cryptography.exceptions import InvalidSignature, UnsupportedAlgorithm
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from sqlalchemy import select, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import (
    AdminDeviceKey,
    AdminDeviceProofChallenge,
    AdminSecurityControl,
    AdminSecurityRecoveryTransaction,
)
from backend.app.services.admin_security import (
    ACTION_PATTERN,
    ADMIN_UNSAFE_METHODS,
    AdminCredentialIssuerUnavailable,
    AdminSecurityError,
    AdminSecurityStoreUnavailable,
    AdminSessionIdentity,
    classify_admin_operation,
    load_admin_credential_issuer_key_for_settings,
    normalize_admin_operation_path,
)


ADMIN_DEVICE_PROOF_SCHEMA_VERSION = "walksafe.admin-device-proof.v2"
ADMIN_DEVICE_PROOF_TTL_MILLISECONDS = 120_000
ADMIN_DEVICE_PROOF_NONCE_BYTES = 32
ADMIN_DEVICE_PROOF_MAX_OUTSTANDING_CHALLENGES = 8
ADMIN_DEVICE_PROOF_CHALLENGE_PATH = "/admin/security/device-proof/challenges"
ADMIN_DEVICE_PROOF_CHALLENGE_TYPES = frozenset(
    {"LOGIN", "ACTION", "RECOVERY_COMPLETE"}
)
ADMIN_DEVICE_PROOF_SIGNED_FIELDS = (
    "action",
    "admin_id",
    "body_sha256",
    "challenge_id",
    "correlation_id",
    "device_id",
    "device_key_marker",
    "device_key_version",
    "expires_at_epoch_ms",
    "issued_at_epoch_ms",
    "method",
    "nonce",
    "path",
    "purpose",
    "query_sha256",
    "read_purpose",
    "schema_version",
    "session_id",
)

_LOGIN_PATH = "/admin/security/sessions"
_RECOVERY_COMPLETE_PATH = "/admin/security/recovery/complete"
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_ADMIN_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
_DEVICE_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
_READ_PURPOSE_PATTERN = re.compile(r"^[a-z][a-z0-9_.:-]{2,63}$")
_REPORT_WORKFLOW_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/"
    r"(?:review-decisions|deliveries)$"
)
_ADMIN_WRITE_WORKFLOW_PATH_PATTERN = re.compile(
    r"^(?:/admin/security/recovery-custody/attest|"
    r"/admin/security/devices/[A-Za-z0-9][A-Za-z0-9._:-]{7,127}/report-lost)$"
)
_CANONICAL_UUID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}$"
)
_BASE64URL_PATTERN = re.compile(r"^[A-Za-z0-9_-]+$")
_UNRESERVED = frozenset(
    b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
)

ChallengeType = Literal["LOGIN", "ACTION", "RECOVERY_COMPLETE"]


@dataclass(frozen=True)
class VerifiedAdminDeviceProof:
    admin_id: str
    device_id: str
    session_id: uuid.UUID | None
    challenge_id: uuid.UUID
    correlation_id: uuid.UUID
    action: str | None
    purpose: str
    read_purpose: str | None
    method: str
    path: str
    body_sha256: str
    query_sha256: str
    device_key_marker: str
    device_key_version: int


@dataclass(frozen=True)
class ProvisionedAdminDeviceKey:
    key_id: uuid.UUID
    admin_id: str
    device_id: str
    key_version: int
    key_marker: str
    status: str
    idempotent: bool


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _epoch_milliseconds(value: datetime) -> int:
    return int(_as_utc(value).timestamp() * 1000)


def _from_epoch_milliseconds(value: int) -> datetime:
    return datetime.fromtimestamp(value / 1000, tz=timezone.utc)


def _canonical_uuid(value: str, *, field: str) -> uuid.UUID:
    if not isinstance(value, str) or _CANONICAL_UUID_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{field} must be a canonical lowercase UUID")
    try:
        parsed = uuid.UUID(value)
    except ValueError as exc:
        raise ValueError(f"{field} must be a canonical lowercase UUID") from exc
    if str(parsed) != value:
        raise ValueError(f"{field} must be a canonical lowercase UUID")
    return parsed


def _require_sha256(value: str, *, field: str) -> str:
    if not isinstance(value, str) or _SHA256_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{field} must be a lowercase SHA-256 hex digest")
    return value


def _encode_base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _decode_base64url(value: str, *, field: str) -> bytes:
    if (
        not isinstance(value, str)
        or not value
        or _BASE64URL_PATTERN.fullmatch(value) is None
    ):
        raise ValueError(f"{field} must be canonical unpadded base64url")
    try:
        decoded = base64.b64decode(
            value + "=" * (-len(value) % 4),
            altchars=b"-_",
            validate=True,
        )
    except (binascii.Error, ValueError) as exc:
        raise ValueError(f"{field} must be canonical unpadded base64url") from exc
    if not secrets.compare_digest(_encode_base64url(decoded), value):
        raise ValueError(f"{field} must be canonical unpadded base64url")
    return decoded


def canonical_device_proof_json(value: dict[str, Any]) -> str:
    """Return the one permitted UTF-8 signing representation."""

    if set(value) != set(ADMIN_DEVICE_PROOF_SIGNED_FIELDS) or len(value) != len(
        ADMIN_DEVICE_PROOF_SIGNED_FIELDS
    ):
        raise ValueError("device proof payload must contain exactly the signed fields")
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    )


def _percent_decode(component: bytes) -> bytes:
    decoded = bytearray()
    index = 0
    while index < len(component):
        current = component[index]
        if current != 0x25:
            decoded.append(current)
            index += 1
            continue
        if index + 2 >= len(component):
            raise ValueError("query contains an incomplete percent escape")
        encoded = component[index + 1 : index + 3]
        if any(value not in b"0123456789ABCDEFabcdef" for value in encoded):
            raise ValueError("query contains an invalid percent escape")
        try:
            decoded.append(int(encoded.decode("ascii"), 16))
        except (UnicodeDecodeError, ValueError) as exc:
            raise ValueError("query contains an invalid percent escape") from exc
        index += 3
    try:
        decoded.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError("query components must be valid UTF-8") from exc
    return bytes(decoded)


def _percent_encode(component: bytes) -> bytes:
    encoded = bytearray()
    for value in component:
        if value in _UNRESERVED:
            encoded.append(value)
        else:
            encoded.extend(f"%{value:02X}".encode("ascii"))
    return bytes(encoded)


def canonical_admin_query(raw_query_string: bytes) -> bytes:
    """Canonicalize RFC3986 query pairs without losing duplicates or blanks."""

    if not isinstance(raw_query_string, bytes):
        raise TypeError("raw_query_string must be bytes")
    if raw_query_string == b"":
        return b""
    pairs: list[tuple[bytes, bytes]] = []
    for raw_pair in raw_query_string.split(b"&"):
        raw_name, separator, raw_value = raw_pair.partition(b"=")
        if not separator:
            raw_value = b""
        name = _percent_encode(_percent_decode(raw_name))
        value = _percent_encode(_percent_decode(raw_value))
        pairs.append((name, value))
    pairs.sort(key=lambda pair: (pair[0], pair[1]))
    return b"&".join(name + b"=" + value for name, value in pairs)


def canonical_admin_query_sha256(raw_query_string: bytes) -> str:
    return hashlib.sha256(canonical_admin_query(raw_query_string)).hexdigest()


def raw_body_sha256(raw_body: bytes) -> str:
    if not isinstance(raw_body, bytes):
        raise TypeError("raw_body must be bytes")
    return hashlib.sha256(raw_body).hexdigest()


def load_p256_spki_public_key(spki_der: bytes) -> ec.EllipticCurvePublicKey:
    if not isinstance(spki_der, bytes) or not 1 <= len(spki_der) <= 4096:
        raise ValueError("device public key must be 1..4096 bytes of SPKI DER")
    try:
        key = serialization.load_der_public_key(spki_der)
    except (ValueError, TypeError, UnsupportedAlgorithm) as exc:
        raise ValueError("device public key must be valid P-256 SPKI DER") from exc
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(
        key.curve, ec.SECP256R1
    ):
        raise ValueError("device public key must use P-256")
    canonical = key.public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    if not secrets.compare_digest(canonical, spki_der):
        raise ValueError("device public key must use canonical SPKI DER")
    return key


def admin_device_key_marker(spki_der: bytes) -> str:
    load_p256_spki_public_key(spki_der)
    return hashlib.sha256(spki_der).hexdigest()


def is_admin_device_proof_workflow_request(method: str, path: str) -> bool:
    if not isinstance(method, str) or not isinstance(path, str):
        return False
    normalized_method = method.upper()
    if _REPORT_WORKFLOW_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method in {"GET", "POST"}
    return (
        normalized_method == "POST"
        and _ADMIN_WRITE_WORKFLOW_PATH_PATTERN.fullmatch(path) is not None
    )


def validate_device_proof_challenge_binding(
    *,
    purpose: str,
    action: str | None,
    admin_id: str,
    device_id: str,
    session_id: str | None,
    method: str,
    path: str,
    read_purpose: str | None,
    identity: AdminSessionIdentity | None,
) -> uuid.UUID | None:
    """Validate the exact13 request's semantic and authorization binding."""

    normalized_method = method.upper()
    normalized_path = normalize_admin_operation_path(path)
    if purpose not in ADMIN_DEVICE_PROOF_CHALLENGE_TYPES:
        raise ValueError("unsupported device proof purpose")
    if (
        not isinstance(method, str)
        or method != normalized_method
        or normalized_method not in {"GET", "POST", "PUT", "PATCH", "DELETE"}
    ):
        raise ValueError("unsupported device proof method")
    if normalized_path is None or normalized_path != path:
        raise ValueError("device proof path is not canonical")
    if _ADMIN_ID_PATTERN.fullmatch(admin_id) is None:
        raise ValueError("admin_id is invalid")
    if _DEVICE_ID_PATTERN.fullmatch(device_id) is None:
        raise ValueError("device_id is invalid")

    if purpose == "LOGIN":
        if (
            normalized_method != "POST"
            or normalized_path != _LOGIN_PATH
            or action is not None
            or read_purpose is not None
            or session_id is not None
            or identity is not None
        ):
            raise ValueError("LOGIN challenge binding is invalid")
        return None

    if purpose == "RECOVERY_COMPLETE":
        if (
            normalized_method != "POST"
            or normalized_path != _RECOVERY_COMPLETE_PATH
            or action is not None
            or read_purpose is not None
            or session_id is not None
            or identity is not None
        ):
            raise ValueError("RECOVERY_COMPLETE challenge binding is invalid")
        return None

    if identity is None or session_id is None:
        raise ValueError("ACTION challenge requires an administrator session")
    parsed_session_id = _canonical_uuid(session_id, field="session_id")
    if (
        identity.admin_id != admin_id
        or identity.device_id != device_id
        or identity.session_id != parsed_session_id
    ):
        raise ValueError("ACTION challenge does not match the administrator session")
    if normalized_path in {
        ADMIN_DEVICE_PROOF_CHALLENGE_PATH,
        _LOGIN_PATH,
        _RECOVERY_COMPLETE_PATH,
    }:
        raise ValueError("the challenge endpoint and public authentication routes are excluded")
    if not is_admin_device_proof_workflow_request(normalized_method, normalized_path):
        raise ValueError("ACTION challenge is outside the administrator proof workflow")

    if normalized_method == "GET":
        if action is not None or not isinstance(read_purpose, str) or (
            _READ_PURPOSE_PATTERN.fullmatch(read_purpose) is None
        ):
            raise ValueError("protected GET challenges require a read purpose and null action")
    else:
        operation = classify_admin_operation(normalized_method, normalized_path)
        if (
            normalized_method in ADMIN_UNSAFE_METHODS
            and (
                operation is None
                or action is None
                or ACTION_PATTERN.fullmatch(action) is None
                or operation.action != action
            )
        ):
            raise ValueError("protected write challenge is not a registered action")
        if read_purpose is not None:
            raise ValueError("protected write challenges require null read_purpose")
    return parsed_session_id


def _database_device_proof_purpose(
    purpose: str,
    read_purpose: str | None,
) -> str:
    return "READ" if purpose == "ACTION" and read_purpose is not None else purpose


def _signed_fields_from_challenge(challenge: AdminDeviceProofChallenge) -> dict[str, Any]:
    return {
        "action": challenge.action,
        "admin_id": challenge.admin_id,
        "body_sha256": challenge.body_sha256,
        "challenge_id": str(challenge.id),
        "correlation_id": str(challenge.correlation_id),
        "device_id": challenge.device_id,
        "device_key_marker": challenge.device_key_marker,
        "device_key_version": challenge.device_key_version,
        "expires_at_epoch_ms": _epoch_milliseconds(challenge.expires_at),
        "issued_at_epoch_ms": _epoch_milliseconds(challenge.issued_at),
        "method": challenge.method,
        "nonce": challenge.nonce,
        "path": challenge.path,
        "purpose": challenge.purpose,
        "query_sha256": challenge.query_sha256,
        "read_purpose": challenge.read_purpose,
        "schema_version": challenge.schema_version,
        "session_id": str(challenge.session_id) if challenge.session_id is not None else None,
    }


def _active_device_key_query(
    *, admin_id: str, device_id: str, key_version: int, key_marker: str
):
    return select(AdminDeviceKey).where(
        AdminDeviceKey.admin_id == admin_id,
        AdminDeviceKey.device_id == device_id,
        AdminDeviceKey.key_version == key_version,
        AdminDeviceKey.key_marker == key_marker,
        AdminDeviceKey.status == "ACTIVE",
        AdminDeviceKey.revoked_at.is_(None),
    )


def _lock_recovery_complete_context(
    db: Session,
    *,
    admin_id: str,
    device_id: str,
    observed_at: datetime,
) -> None:
    """Lock and validate the recovery state bound to one recovery device."""

    bind = db.get_bind()
    if bind is not None and bind.dialect.name == "postgresql":
        digest = hashlib.sha256(f"recovery-state\0{admin_id}".encode()).hexdigest()
        lock_key = int.from_bytes(
            bytes.fromhex(digest[:16]),
            byteorder="big",
            signed=True,
        )
        db.execute(
            text("SELECT pg_advisory_xact_lock(:key)"),
            {"key": lock_key},
        )

    controls = db.execute(
        select(AdminSecurityControl).with_for_update()
    ).scalars().all()
    if (
        len(controls) != 1
        or controls[0].singleton_scope is not True
        or controls[0].admin_id != admin_id
        or controls[0].security_state != "RECOVERY_IN_PROGRESS"
    ):
        raise AdminSecurityError(
            "admin_device_proof_invalid",
            "The administrator device proof is invalid.",
            status_code=403,
        )

    transactions = db.execute(
        select(AdminSecurityRecoveryTransaction)
        .where(
            AdminSecurityRecoveryTransaction.admin_id == admin_id,
            AdminSecurityRecoveryTransaction.completed_at.is_(None),
        )
        .with_for_update()
    ).scalars().all()
    active_transactions = [
        transaction
        for transaction in transactions
        if transaction.admin_id == admin_id
        and transaction.completed_at is None
        and _as_utc(transaction.expires_at) > observed_at
    ]
    if not active_transactions and any(
        transaction.admin_id == admin_id
        and transaction.completed_at is None
        and _as_utc(transaction.expires_at) <= observed_at
        and secrets.compare_digest(transaction.device_id, device_id)
        for transaction in transactions
    ):
        raise AdminSecurityError(
            "admin_recovery_expired",
            "The recovery transaction expired; start recovery again.",
            status_code=410,
        )
    if (
        len(active_transactions) != 1
        or not secrets.compare_digest(
            active_transactions[0].device_id, device_id
        )
    ):
        raise AdminSecurityError(
            "admin_device_proof_invalid",
            "The administrator device proof is invalid.",
            status_code=403,
        )


def _postgresql_session(db: Session) -> bool:
    bind = db.get_bind()
    return bind is not None and bind.dialect.name == "postgresql"


def _lock_device_proof_key(
    db: Session,
    *,
    admin_id: str,
    device_id: str,
    device_key_version: int,
    device_key_marker: str,
    observed_at: datetime,
    purpose: str,
    runtime_totp_secret: str | None,
    credential_issuer_key: str | None,
    inactive_code: str,
    inactive_message: str,
) -> bytes:
    if _postgresql_session(db):
        if not runtime_totp_secret or not credential_issuer_key:
            raise AdminCredentialIssuerUnavailable()
        rows = db.execute(
            text(
                "SELECT * FROM public.walksafe_lock_admin_device_proof_context("
                "CAST(:admin_id AS text), CAST(:device_id AS text), "
                "CAST(:device_key_version AS bigint), "
                "CAST(:device_key_marker AS text), "
                "CAST(:observed_at AS timestamptz), CAST(:purpose AS text), "
                "CAST(:runtime_totp_secret AS text), "
                "CAST(:credential_issuer_key AS text))"
            ),
            {
                "admin_id": admin_id,
                "device_id": device_id,
                "device_key_version": device_key_version,
                "device_key_marker": device_key_marker,
                "observed_at": observed_at,
                "purpose": purpose,
                "runtime_totp_secret": runtime_totp_secret,
                "credential_issuer_key": credential_issuer_key,
            },
        ).all()
        if len(rows) != 1:
            raise AdminSecurityStoreUnavailable()
        status = rows[0].context_status
        if status == "RECOVERY_EXPIRED":
            if (
                purpose == "RECOVERY_COMPLETE"
                and rows[0].public_key_spki_der is not None
            ):
                return bytes(rows[0].public_key_spki_der)
            raise AdminSecurityError(
                "admin_recovery_expired",
                "The recovery transaction expired; start recovery again.",
                status_code=410,
            )
        if status == "INVALID":
            raise AdminSecurityError(
                "admin_device_proof_invalid",
                "The administrator device proof is invalid.",
                status_code=403,
            )
        if status == "KEY_INACTIVE":
            raise AdminSecurityError(
                inactive_code,
                inactive_message,
                status_code=403,
            )
        if status != "OK" or rows[0].public_key_spki_der is None:
            raise AdminSecurityStoreUnavailable()
        return bytes(rows[0].public_key_spki_der)

    if purpose == "RECOVERY_COMPLETE":
        _lock_recovery_complete_context(
            db,
            admin_id=admin_id,
            device_id=device_id,
            observed_at=observed_at,
        )
    device_key = db.execute(
        _active_device_key_query(
            admin_id=admin_id,
            device_id=device_id,
            key_version=device_key_version,
            key_marker=device_key_marker,
        ).with_for_update()
    ).scalar_one_or_none()
    if device_key is None:
        raise AdminSecurityError(
            inactive_code,
            inactive_message,
            status_code=403,
        )
    return bytes(device_key.public_key_spki_der)


class AdminDeviceProofService:
    def __init__(self, db: Session, settings: Any | None = None) -> None:
        self.db = db
        self.settings = settings

    def issue_challenge(
        self,
        *,
        purpose: str,
        action: str | None,
        admin_id: str,
        body_sha256: str,
        correlation_id: str,
        device_id: str,
        device_key_marker: str,
        device_key_version: int,
        method: str,
        path: str,
        query_sha256: str,
        read_purpose: str | None,
        session_id: str | None,
        identity: AdminSessionIdentity | None,
        now: datetime | None = None,
    ) -> dict[str, Any]:
        try:
            parsed_session_id = validate_device_proof_challenge_binding(
                purpose=purpose,
                action=action,
                admin_id=admin_id,
                device_id=device_id,
                session_id=session_id,
                method=method,
                path=path,
                read_purpose=read_purpose,
                identity=identity,
            )
            _require_sha256(body_sha256, field="body_sha256")
            _require_sha256(query_sha256, field="query_sha256")
            _require_sha256(device_key_marker, field="device_key_marker")
            parsed_correlation_id = _canonical_uuid(
                correlation_id, field="correlation_id"
            )
            if not isinstance(device_key_version, int) or isinstance(
                device_key_version, bool
            ) or device_key_version < 1:
                raise ValueError("device_key_version must be a positive integer")
        except ValueError as exc:
            raise AdminSecurityError(
                "admin_device_proof_binding_invalid",
                "The administrator device proof challenge binding is invalid.",
                status_code=422,
            ) from exc

        observed_at = _as_utc(now or datetime.now(timezone.utc))
        issued_at_epoch_ms = _epoch_milliseconds(observed_at)
        issued_at = _from_epoch_milliseconds(issued_at_epoch_ms)
        expires_at = issued_at + timedelta(
            milliseconds=ADMIN_DEVICE_PROOF_TTL_MILLISECONDS
        )
        try:
            runtime_totp_secret = None
            credential_issuer_key = None
            if _postgresql_session(self.db):
                if self.settings is None:
                    raise AdminCredentialIssuerUnavailable()
                runtime_totp_secret = getattr(
                    self.settings,
                    "admin_totp_secret",
                    None,
                )
                credential_issuer_key = load_admin_credential_issuer_key_for_settings(
                    self.settings
                )
            public_key_spki_der = _lock_device_proof_key(
                self.db,
                admin_id=admin_id,
                device_id=device_id,
                device_key_version=device_key_version,
                device_key_marker=device_key_marker,
                observed_at=issued_at,
                purpose=_database_device_proof_purpose(purpose, read_purpose),
                runtime_totp_secret=runtime_totp_secret,
                credential_issuer_key=credential_issuer_key,
                inactive_code="admin_device_key_not_registered",
                inactive_message=(
                    "The administrator device key is not registered and active."
                ),
            )
            try:
                actual_marker = admin_device_key_marker(public_key_spki_der)
            except ValueError as exc:
                raise AdminSecurityStoreUnavailable() from exc
            if not secrets.compare_digest(actual_marker, device_key_marker):
                raise AdminSecurityStoreUnavailable()

            outstanding_expiries = self.db.execute(
                select(AdminDeviceProofChallenge.expires_at).where(
                    AdminDeviceProofChallenge.admin_id == admin_id,
                    AdminDeviceProofChallenge.device_id == device_id,
                    AdminDeviceProofChallenge.device_key_version
                    == device_key_version,
                    AdminDeviceProofChallenge.device_key_marker
                    == device_key_marker,
                    AdminDeviceProofChallenge.consumed_at.is_(None),
                    AdminDeviceProofChallenge.expires_at > issued_at,
                )
            ).scalars().all()
            if (
                len(outstanding_expiries)
                >= ADMIN_DEVICE_PROOF_MAX_OUTSTANDING_CHALLENGES
            ):
                earliest_expiry = min(_as_utc(value) for value in outstanding_expiries)
                retry_after = max(
                    1,
                    math.ceil((earliest_expiry - issued_at).total_seconds()),
                )
                raise AdminSecurityError(
                    "admin_device_proof_challenge_rate_limited",
                    "Too many unconsumed administrator device proof challenges are active.",
                    status_code=429,
                    retry_after=retry_after,
                )

            challenge_id = uuid.uuid4()
            nonce = _encode_base64url(secrets.token_bytes(ADMIN_DEVICE_PROOF_NONCE_BYTES))
            challenge = AdminDeviceProofChallenge(
                id=challenge_id,
                challenge_type=purpose,
                action=action,
                admin_id=admin_id,
                body_sha256=body_sha256,
                correlation_id=parsed_correlation_id,
                device_id=device_id,
                device_key_marker=device_key_marker,
                device_key_version=device_key_version,
                expires_at=expires_at,
                issued_at=issued_at,
                method=method,
                nonce=nonce,
                purpose=purpose,
                path=path,
                query_sha256=query_sha256,
                read_purpose=read_purpose,
                schema_version=ADMIN_DEVICE_PROOF_SCHEMA_VERSION,
                session_id=parsed_session_id,
                signing_payload="",
                created_at=issued_at,
            )
            signed_fields = _signed_fields_from_challenge(challenge)
            signing_payload = canonical_device_proof_json(signed_fields)
            challenge.signing_payload = signing_payload
            self.db.add(challenge)
            self.db.commit()
            return {**signed_fields, "signing_payload": signing_payload}
        except AdminSecurityError:
            self.db.rollback()
            raise
        except SQLAlchemyError as exc:
            self.db.rollback()
            raise AdminSecurityStoreUnavailable() from exc


def verify_admin_device_proof(
    db: Session,
    *,
    challenge_id: str,
    signature: str,
    correlation_id: str,
    expected_purpose: ChallengeType,
    expected_action: str | None,
    expected_admin_id: str,
    expected_device_id: str,
    expected_session_id: uuid.UUID | None,
    expected_method: str,
    expected_path: str,
    expected_read_purpose: str | None,
    raw_body: bytes,
    raw_query_string: bytes,
    runtime_totp_secret: str | None = None,
    credential_issuer_key: str | None = None,
    now: datetime | None = None,
) -> VerifiedAdminDeviceProof:
    """Verify and consume one exact request-bound challenge in the caller transaction."""

    try:
        parsed_challenge_id = _canonical_uuid(challenge_id, field="challenge_id")
        parsed_correlation_id = _canonical_uuid(
            correlation_id, field="correlation_id"
        )
        if not isinstance(signature, str) or not 80 <= len(signature) <= 104:
            raise ValueError("signature length is invalid")
        signature_bytes = _decode_base64url(signature, field="signature")
        if not 64 <= len(signature_bytes) <= 80:
            raise ValueError("signature DER length is invalid")
        if expected_purpose not in ADMIN_DEVICE_PROOF_CHALLENGE_TYPES:
            raise ValueError("expected_purpose is invalid")
        if normalize_admin_operation_path(expected_path) != expected_path:
            raise ValueError("expected_path is invalid")
        expected_query_sha256 = canonical_admin_query_sha256(raw_query_string)
        expected_body_sha256 = raw_body_sha256(raw_body)
    except (TypeError, ValueError) as exc:
        raise AdminSecurityError(
            "admin_device_proof_invalid",
            "The administrator device proof is invalid.",
            status_code=403,
        ) from exc

    observed_at = _as_utc(now or datetime.now(timezone.utc))
    try:
        challenge = db.execute(
            select(AdminDeviceProofChallenge)
            .where(AdminDeviceProofChallenge.id == parsed_challenge_id)
            .with_for_update()
        ).scalar_one_or_none()
        if challenge is None:
            raise AdminSecurityError(
                "admin_device_proof_invalid",
                "The administrator device proof is invalid.",
                status_code=403,
            )
        issued_at = _as_utc(challenge.issued_at)
        expires_at = _as_utc(challenge.expires_at)
        if challenge.consumed_at is not None:
            raise AdminSecurityError(
                "admin_device_proof_replayed",
                "The administrator device proof challenge was already consumed.",
                status_code=409,
            )
        if observed_at < issued_at or expires_at <= observed_at:
            raise AdminSecurityError(
                "admin_device_proof_expired",
                "The administrator device proof challenge is expired.",
                status_code=403,
            )
        if (
            _epoch_milliseconds(expires_at) - _epoch_milliseconds(issued_at)
            != ADMIN_DEVICE_PROOF_TTL_MILLISECONDS
            or challenge.schema_version != ADMIN_DEVICE_PROOF_SCHEMA_VERSION
            or challenge.challenge_type != expected_purpose
            or challenge.purpose != expected_purpose
            or challenge.action != expected_action
            or challenge.admin_id != expected_admin_id
            or challenge.device_id != expected_device_id
            or challenge.session_id != expected_session_id
            or challenge.method != expected_method.upper()
            or challenge.path != expected_path
            or challenge.read_purpose != expected_read_purpose
            or not secrets.compare_digest(
                str(challenge.correlation_id), str(parsed_correlation_id)
            )
            or not secrets.compare_digest(challenge.body_sha256, expected_body_sha256)
            or not secrets.compare_digest(challenge.query_sha256, expected_query_sha256)
        ):
            raise AdminSecurityError(
                "admin_device_proof_binding_mismatch",
                "The administrator device proof does not match this request.",
                status_code=403,
            )
        try:
            nonce_bytes = _decode_base64url(challenge.nonce, field="nonce")
        except ValueError as exc:
            raise AdminSecurityStoreUnavailable() from exc
        if len(nonce_bytes) != ADMIN_DEVICE_PROOF_NONCE_BYTES:
            raise AdminSecurityStoreUnavailable()

        signed_fields = _signed_fields_from_challenge(challenge)
        canonical_payload = canonical_device_proof_json(signed_fields)
        if not secrets.compare_digest(challenge.signing_payload, canonical_payload):
            raise AdminSecurityStoreUnavailable()
        public_key_spki_der = _lock_device_proof_key(
            db,
            admin_id=challenge.admin_id,
            device_id=challenge.device_id,
            device_key_version=challenge.device_key_version,
            device_key_marker=challenge.device_key_marker,
            observed_at=observed_at,
            purpose=_database_device_proof_purpose(
                expected_purpose,
                expected_read_purpose,
            ),
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
            inactive_code="admin_device_key_not_active",
            inactive_message="The administrator device key is not active.",
        )
        try:
            public_key = load_p256_spki_public_key(public_key_spki_der)
        except ValueError as exc:
            raise AdminSecurityStoreUnavailable() from exc
        actual_marker = hashlib.sha256(public_key_spki_der).hexdigest()
        if not secrets.compare_digest(actual_marker, challenge.device_key_marker):
            raise AdminSecurityStoreUnavailable()
        try:
            public_key.verify(
                signature_bytes,
                canonical_payload.encode("utf-8"),
                ec.ECDSA(hashes.SHA256()),
            )
        except (InvalidSignature, ValueError) as exc:
            raise AdminSecurityError(
                "admin_device_proof_signature_invalid",
                "The administrator device proof signature is invalid.",
                status_code=403,
            ) from exc
        challenge.consumed_at = observed_at
        db.flush()
        return VerifiedAdminDeviceProof(
            admin_id=challenge.admin_id,
            device_id=challenge.device_id,
            session_id=challenge.session_id,
            challenge_id=challenge.id,
            correlation_id=challenge.correlation_id,
            action=challenge.action,
            purpose=challenge.purpose,
            read_purpose=challenge.read_purpose,
            method=challenge.method,
            path=challenge.path,
            body_sha256=challenge.body_sha256,
            query_sha256=challenge.query_sha256,
            device_key_marker=challenge.device_key_marker,
            device_key_version=challenge.device_key_version,
        )
    except AdminSecurityError:
        raise
    except SQLAlchemyError as exc:
        raise AdminSecurityStoreUnavailable() from exc


def provision_admin_device_key(
    db: Session,
    *,
    admin_id: str,
    device_id: str,
    key_version: int,
    public_key_spki_der: bytes,
    now: datetime | None = None,
) -> ProvisionedAdminDeviceKey:
    """Register or rotate a device key from a trusted local provisioning process."""

    if _ADMIN_ID_PATTERN.fullmatch(admin_id) is None:
        raise ValueError("admin_id is invalid")
    if _DEVICE_ID_PATTERN.fullmatch(device_id) is None:
        raise ValueError("device_id is invalid")
    if not isinstance(key_version, int) or isinstance(key_version, bool) or key_version < 1:
        raise ValueError("key_version must be a positive integer")
    marker = admin_device_key_marker(public_key_spki_der)
    observed_at = _as_utc(now or datetime.now(timezone.utc))
    try:
        bind = db.get_bind()
        if bind is not None and bind.dialect.name == "postgresql":
            digest = hashlib.sha256(
                f"recovery-state\0{admin_id}".encode()
            ).hexdigest()
            lock_key = int.from_bytes(
                bytes.fromhex(digest[:16]),
                byteorder="big",
                signed=True,
            )
            db.execute(
                text("SELECT pg_advisory_xact_lock(:key)"),
                {"key": lock_key},
            )
        controls = db.execute(
            select(AdminSecurityControl).with_for_update()
        ).scalars().all()
        if (
            len(controls) != 1
            or controls[0].singleton_scope is not True
            or controls[0].admin_id != admin_id
        ):
            raise AdminSecurityError(
                "admin_device_key_provisioning_not_allowed",
                "Administrator device key provisioning is not allowed.",
                status_code=409,
            )
        if controls[0].security_state == "RECOVERY_IN_PROGRESS":
            active_recoveries = db.execute(
                select(AdminSecurityRecoveryTransaction)
                .where(
                    AdminSecurityRecoveryTransaction.admin_id == admin_id,
                    AdminSecurityRecoveryTransaction.completed_at.is_(None),
                    AdminSecurityRecoveryTransaction.expires_at > observed_at,
                )
                .with_for_update()
            ).scalars().all()
            if (
                len(active_recoveries) != 1
                or not secrets.compare_digest(
                    active_recoveries[0].device_id, device_id
                )
            ):
                raise AdminSecurityError(
                    "admin_device_key_provisioning_not_allowed",
                    "Administrator device key provisioning is not allowed.",
                    status_code=409,
                )
        elif controls[0].security_state not in {"NORMAL", "RECOVERY_REQUIRED"}:
            raise AdminSecurityError(
                "admin_device_key_provisioning_not_allowed",
                "Administrator device key provisioning is not allowed.",
                status_code=409,
            )
        existing = db.execute(
            select(AdminDeviceKey)
            .where(
                AdminDeviceKey.admin_id == admin_id,
                AdminDeviceKey.device_id == device_id,
            )
            .order_by(AdminDeviceKey.key_version.desc())
            .with_for_update()
        ).scalars().all()
        same_version = next(
            (item for item in existing if item.key_version == key_version), None
        )
        if same_version is not None:
            if (
                same_version.status == "ACTIVE"
                and same_version.revoked_at is None
                and secrets.compare_digest(same_version.key_marker, marker)
                and secrets.compare_digest(
                    bytes(same_version.public_key_spki_der), public_key_spki_der
                )
            ):
                db.commit()
                return ProvisionedAdminDeviceKey(
                    key_id=same_version.id,
                    admin_id=admin_id,
                    device_id=device_id,
                    key_version=key_version,
                    key_marker=marker,
                    status="ACTIVE",
                    idempotent=True,
                )
            raise ValueError("key_version is already registered with different key state")
        if existing and key_version <= max(item.key_version for item in existing):
            raise ValueError("key_version must increase monotonically")
        for item in existing:
            if item.status == "ACTIVE":
                item.status = "REVOKED"
                item.revoked_at = observed_at
        device_key = AdminDeviceKey(
            admin_id=admin_id,
            device_id=device_id,
            key_version=key_version,
            public_key_spki_der=public_key_spki_der,
            key_marker=marker,
            status="ACTIVE",
            created_at=observed_at,
        )
        db.add(device_key)
        db.commit()
        return ProvisionedAdminDeviceKey(
            key_id=device_key.id,
            admin_id=admin_id,
            device_id=device_id,
            key_version=key_version,
            key_marker=marker,
            status="ACTIVE",
            idempotent=False,
        )
    except (AdminSecurityError, ValueError):
        db.rollback()
        raise
    except SQLAlchemyError as exc:
        db.rollback()
        raise AdminSecurityStoreUnavailable() from exc


__all__ = [
    "ADMIN_DEVICE_PROOF_CHALLENGE_PATH",
    "ADMIN_DEVICE_PROOF_MAX_OUTSTANDING_CHALLENGES",
    "ADMIN_DEVICE_PROOF_NONCE_BYTES",
    "ADMIN_DEVICE_PROOF_SCHEMA_VERSION",
    "ADMIN_DEVICE_PROOF_SIGNED_FIELDS",
    "ADMIN_DEVICE_PROOF_TTL_MILLISECONDS",
    "AdminDeviceProofService",
    "ProvisionedAdminDeviceKey",
    "VerifiedAdminDeviceProof",
    "admin_device_key_marker",
    "canonical_admin_query",
    "canonical_admin_query_sha256",
    "canonical_device_proof_json",
    "is_admin_device_proof_workflow_request",
    "load_p256_spki_public_key",
    "provision_admin_device_key",
    "raw_body_sha256",
    "validate_device_proof_challenge_binding",
    "verify_admin_device_proof",
]
