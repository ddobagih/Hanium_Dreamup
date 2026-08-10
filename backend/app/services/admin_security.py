"""Database-backed administrator password, TOTP, session, and recovery policy.

Credential inputs and opaque bearer values exist only for the duration of a
request. PostgreSQL stores scrypt encodings or SHA-256 digests, and audit
details are restricted to non-secret operational metadata.
"""

from __future__ import annotations

import base64
import binascii
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from enum import Enum
import hashlib
import json
import math
import re
import secrets
from typing import Any, NoReturn
import uuid

import pyotp
from sqlalchemy import func, select, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.config import validate_admin_totp_secret
from backend.app.models import (
    AdminSecurityAudit,
    AdminSecurityAuthAttempt,
    AdminSecurityControl,
    AdminSecurityReconfirmation,
    AdminSecurityRecoveryCode,
    AdminSecurityRecoveryTransaction,
    AdminSecuritySession,
)


ADMIN_APP_KIND = "ADMIN_ANDROID"
ADMIN_ROLE = "ADMIN"
ADMIN_AUDIENCE = "walksafe-admin-api"
DEFAULT_STEP_UP_TTL_SECONDS = 5 * 60
PASSWORD_MIN_LENGTH = 12
PASSWORD_MAX_LENGTH = 256
SESSION_TOKEN_BYTES = 48
RECOVERY_TOKEN_BYTES = 48
TOTP_CODE_PATTERN = re.compile(r"^[0-9]{6}$")
ACTION_PATTERN = re.compile(r"^[a-z][a-z0-9_.:-]{0,63}$")
RECONFIRMATION_NONCE_PATTERN = re.compile(r"^[A-Za-z0-9_-]{22}$")
REPORT_STATUS_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/status$"
)
REPORT_ORIGINAL_GRANT_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/original-access-grants$"
)
ADMIN_SESSION_REVOKE_PATH_PATTERN = re.compile(
    r"^/admin/security/sessions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/revoke$"
)
REPORT_REVIEW_DECISION_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/review-decisions$"
)
REPORT_DELIVERY_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/deliveries$"
)
ADMIN_DEVICE_PROOF_CHALLENGE_PATH = "/admin/security/device-proof/challenges"
ADMIN_UNSAFE_METHODS = frozenset({"POST", "PUT", "PATCH", "DELETE"})
_SCRYPT_N = 1 << 14
_SCRYPT_R = 8
_SCRYPT_P = 1
_SCRYPT_DKLEN = 32
_PASSWORD_ENCODING_PREFIX = "scrypt-v1"
_FORBIDDEN_AUDIT_KEY_PARTS = ("password", "token", "totp", "secret", "recovery_code")


class SecurityState(str, Enum):
    NORMAL = "NORMAL"
    RECOVERY_REQUIRED = "RECOVERY_REQUIRED"
    RECOVERY_IN_PROGRESS = "RECOVERY_IN_PROGRESS"


class AdminSecurityError(RuntimeError):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int,
        retry_after: int | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.retry_after = retry_after


class AdminSecurityStoreUnavailable(AdminSecurityError):
    def __init__(self) -> None:
        super().__init__(
            "admin_security_store_unavailable",
            "Administrator security state is temporarily unavailable.",
            status_code=503,
        )


@dataclass(frozen=True)
class AdminSessionIdentity:
    admin_id: str
    session_id: uuid.UUID
    device_id: str
    device_label: str
    expires_at: datetime
    step_up_verified_at: datetime | None


@dataclass(frozen=True)
class SessionGrant:
    access_token: str
    security_state: str
    current_session_id: str


@dataclass(frozen=True)
class RecoveryGrant:
    recovery_token: str
    security_state: str


@dataclass(frozen=True)
class AdminOperation:
    action: str
    method: str
    path: str
    risk: str


@dataclass(frozen=True)
class ReconfirmationGrant:
    reauthenticated_until_epoch_ms: int
    action: str
    method: str
    path: str


def normalize_admin_operation_path(path: str) -> str | None:
    if (
        not isinstance(path, str)
        or not path.startswith("/")
        or len(path) > 512
        or "//" in path
        or "?" in path
        or "#" in path
        or "%" in path
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in path)
    ):
        return None
    return path


def classify_admin_operation(method: str, path: str) -> AdminOperation | None:
    normalized_method = method.upper()
    normalized_path = normalize_admin_operation_path(path)
    if normalized_path is None:
        return None
    if normalized_method == "GET" and normalized_path == "/reports/export":
        return AdminOperation("report.export", normalized_method, normalized_path, "HIGH")
    if normalized_method == "PATCH" and REPORT_STATUS_PATH_PATTERN.fullmatch(normalized_path):
        return AdminOperation(
            "report.status.patch",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "POST"
        and REPORT_ORIGINAL_GRANT_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "report.original.grant",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "POST"
        and normalized_path == "/admin/security/reauthenticate"
    ):
        return AdminOperation(
            "session.reauthenticate",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and ADMIN_SESSION_REVOKE_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "session.revoke",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and REPORT_REVIEW_DECISION_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "report.review.decide",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and REPORT_DELIVERY_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "report.delivery.create",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and normalized_path == ADMIN_DEVICE_PROOF_CHALLENGE_PATH
    ):
        return AdminOperation(
            "device_proof.challenge",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    return None


def valid_reconfirmation_nonce(nonce: str) -> bool:
    if not isinstance(nonce, str) or RECONFIRMATION_NONCE_PATTERN.fullmatch(nonce) is None:
        return False
    try:
        decoded = base64.urlsafe_b64decode(nonce + "==")
    except (binascii.Error, ValueError):
        return False
    canonical = base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=")
    return len(decoded) == 16 and secrets.compare_digest(canonical, nonce)


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _encode_bytes(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _decode_bytes(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def hash_password(password: str) -> str:
    """Encode a password using stdlib scrypt and a fresh random salt."""

    if not PASSWORD_MIN_LENGTH <= len(password) <= PASSWORD_MAX_LENGTH:
        raise ValueError(
            f"password must contain between {PASSWORD_MIN_LENGTH} and {PASSWORD_MAX_LENGTH} characters"
        )
    salt = secrets.token_bytes(16)
    derived = hashlib.scrypt(
        password.encode("utf-8"),
        salt=salt,
        n=_SCRYPT_N,
        r=_SCRYPT_R,
        p=_SCRYPT_P,
        dklen=_SCRYPT_DKLEN,
    )
    return "$".join(
        (
            _PASSWORD_ENCODING_PREFIX,
            str(_SCRYPT_N),
            str(_SCRYPT_R),
            str(_SCRYPT_P),
            _encode_bytes(salt),
            _encode_bytes(derived),
        )
    )


def verify_password(password: str, encoded: str) -> bool:
    """Verify an encoded password without data-dependent digest comparison."""

    try:
        prefix, raw_n, raw_r, raw_p, raw_salt, raw_derived = encoded.split("$")
        if prefix != _PASSWORD_ENCODING_PREFIX:
            return False
        n, r, p = int(raw_n), int(raw_r), int(raw_p)
        if (n, r, p) != (_SCRYPT_N, _SCRYPT_R, _SCRYPT_P):
            return False
        salt = _decode_bytes(raw_salt)
        expected = _decode_bytes(raw_derived)
        if len(salt) != 16 or len(expected) != _SCRYPT_DKLEN:
            return False
        actual = hashlib.scrypt(
            password.encode("utf-8"),
            salt=salt,
            n=n,
            r=r,
            p=p,
            dklen=len(expected),
        )
    except (ValueError, TypeError, OverflowError):
        return False
    return secrets.compare_digest(actual, expected)


def totp_secret_fingerprint(secret: str) -> str:
    return sha256_text(secret.strip().replace(" ", "").upper())


def verify_totp_timecode(
    secret: str,
    code: str,
    *,
    last_accepted_timecode: int | None,
    now: datetime | None = None,
) -> tuple[int | None, bool]:
    """Return ``(timecode, replayed)`` using only PyOTP verification APIs."""

    if TOTP_CODE_PATTERN.fullmatch(code) is None:
        return None, False
    try:
        totp = pyotp.TOTP(secret)
        observed_at = _as_utc(now or utc_now())
        matching_timecodes = [
            int(totp.timecode(candidate_time))
            for offset in (-1, 0, 1)
            for candidate_time in (
                observed_at + timedelta(seconds=offset * totp.interval),
            )
            if totp.verify(code, for_time=candidate_time, valid_window=0)
        ]
    except (ValueError, TypeError):
        return None, False
    if not matching_timecodes:
        return None, False
    timecode = max(matching_timecodes)
    replayed = last_accepted_timecode is not None and timecode <= last_accepted_timecode
    return timecode, replayed


def _normalized_recovery_code(code: str) -> str:
    return code.strip().upper()


def recovery_code_sha256(code: str) -> str:
    return sha256_text(_normalized_recovery_code(code))


def require_runtime_totp_secret_matches(
    control: AdminSecurityControl,
    runtime_totp_secret: str,
) -> None:
    """Reject a malformed or stale replica factor without exposing its value."""

    try:
        canonical_secret = validate_admin_totp_secret(runtime_totp_secret)
        runtime_fingerprint = totp_secret_fingerprint(canonical_secret)
    except ValueError as exc:
        raise AdminSecurityError(
            "admin_totp_configuration_mismatch",
            "Administrator authentication configuration is unavailable.",
            status_code=503,
        ) from exc
    if not secrets.compare_digest(
        runtime_fingerprint,
        control.totp_secret_fingerprint,
    ):
        raise AdminSecurityError(
            "admin_totp_configuration_mismatch",
            "Administrator authentication configuration is unavailable.",
            status_code=503,
        )


def auth_rate_limit_principal(
    configured_admin_id: str,
    action: str,
    *,
    recovery_token: str | None = None,
) -> str:
    """Build a non-enumerable rate-limit key independent of submitted admin IDs."""

    if action in {"login", "recovery_start"}:
        return sha256_text(configured_admin_id)
    if action in {"recovery_complete", "reauthenticate"}:
        # Keep this key stable across response-loss recovery-token rotations.
        # The raw token is still validated below, but must not reset admission
        # history each time the same recovery transaction is resumed.
        del recovery_token
        return sha256_text(f"{configured_admin_id}\0{action}")
    raise ValueError("unsupported administrator authentication rate-limit action")


def _safe_audit_details(details: dict[str, Any]) -> dict[str, Any]:
    for key, value in details.items():
        lowered = key.lower()
        if any(part in lowered for part in _FORBIDDEN_AUDIT_KEY_PARTS):
            raise ValueError(f"secret-bearing audit key is forbidden: {key}")
        if isinstance(value, dict):
            _safe_audit_details(value)
        elif not isinstance(value, (str, int, float, bool, type(None), list)):
            raise ValueError(f"unsupported audit detail type for {key}")
    return details


def _audit_timestamp(value: datetime) -> str:
    return (
        _as_utc(value)
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )


def admin_security_audit_entry_sha256(
    *,
    event_id: uuid.UUID,
    sequence: int,
    previous_entry_sha256: str | None,
    admin_id: str | None,
    session_id: uuid.UUID | None,
    device_id: str | None,
    action: str,
    outcome: str,
    details: dict[str, Any],
    created_at: datetime,
) -> str:
    payload = {
        "action": action,
        "admin_id": admin_id,
        "created_at": _audit_timestamp(created_at),
        "details": details,
        "device_id": device_id,
        "event_id": str(event_id),
        "outcome": outcome,
        "previous_entry_sha256": previous_entry_sha256,
        "schema_version": "walksafe-admin-security-audit-chain-v1",
        "sequence": sequence,
        "session_id": str(session_id) if session_id is not None else None,
    }
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _append_admin_security_audit(
    db: Session,
    *,
    action: str,
    outcome: str,
    admin_id: str | None = None,
    session_id: uuid.UUID | None = None,
    device_id: str | None = None,
    details: dict[str, Any] | None = None,
    created_at: datetime | None = None,
) -> AdminSecurityAudit:
    safe_details = _safe_audit_details(details or {})
    observed_at = _as_utc(created_at or utc_now())
    bind = db.get_bind()
    if bind is not None and bind.dialect.name == "postgresql":
        digest = sha256_text("walksafe-admin-security-audit-chain-v1")
        lock_key = int.from_bytes(
            bytes.fromhex(digest[:16]),
            byteorder="big",
            signed=True,
        )
        db.execute(text("SELECT pg_advisory_xact_lock(:key)"), {"key": lock_key})
    previous = db.execute(
        select(AdminSecurityAudit)
        .order_by(AdminSecurityAudit.sequence.desc())
        .limit(1)
        .with_for_update()
    ).scalar_one_or_none()
    sequence = 1 if previous is None else int(previous.sequence) + 1
    previous_entry_sha256 = previous.entry_sha256 if previous is not None else None
    event_id = uuid.uuid4()
    entry_sha256 = admin_security_audit_entry_sha256(
        event_id=event_id,
        sequence=sequence,
        previous_entry_sha256=previous_entry_sha256,
        admin_id=admin_id,
        session_id=session_id,
        device_id=device_id,
        action=action,
        outcome=outcome,
        details=safe_details,
        created_at=observed_at,
    )
    audit = AdminSecurityAudit(
        id=event_id,
        sequence=sequence,
        previous_entry_sha256=previous_entry_sha256,
        entry_sha256=entry_sha256,
        admin_id=admin_id,
        session_id=session_id,
        device_id=device_id,
        action=action,
        outcome=outcome,
        details=safe_details,
        created_at=observed_at,
    )
    db.add(audit)
    db.flush()
    return audit


def record_admin_security_denial(
    *,
    action: str,
    reason: str,
    method: str,
    path: str,
    device_id: str | None = None,
) -> None:
    from backend.app.database import SessionLocal

    normalized_method = method.upper()[:16]
    path_digest = sha256_text(path[:2048])
    try:
        with SessionLocal.begin() as db:
            _append_admin_security_audit(
                db,
                action=action,
                outcome="DENIED",
                device_id=device_id,
                details={
                    "method": normalized_method,
                    "path_sha256": path_digest,
                    "reason": reason,
                },
            )
    except SQLAlchemyError as exc:
        raise AdminSecurityStoreUnavailable() from exc


def record_admin_security_failure(
    *,
    action: str,
    reason: str,
    outcome: str,
    method: str,
    path: str,
    admin_id: str,
    session_id: uuid.UUID,
    device_id: str,
    correlation_id: uuid.UUID,
) -> None:
    """Persist a verified-admin workflow failure and its in-app alert boundary."""

    if outcome not in {"DENIED", "ERROR"}:
        raise ValueError("administrator failure outcome must be DENIED or ERROR")
    from backend.app.database import SessionLocal

    try:
        with SessionLocal.begin() as db:
            _append_admin_security_audit(
                db,
                action=action,
                outcome=outcome,
                admin_id=admin_id,
                session_id=session_id,
                device_id=device_id,
                details={
                    "admin_alert_required": True,
                    "alert_channel": "ADMIN_API_RESPONSE",
                    "correlation_id": str(correlation_id),
                    "method": method.upper()[:16],
                    "path_sha256": sha256_text(path[:2048]),
                    "reason": reason,
                },
            )
    except SQLAlchemyError as exc:
        raise AdminSecurityStoreUnavailable() from exc


def _control_query(admin_id: str, *, for_update: bool = False):
    query = select(AdminSecurityControl).where(AdminSecurityControl.admin_id == admin_id)
    return query.with_for_update() if for_update else query


def assert_high_risk_operation_allowed(
    db: Session,
    action: str,
    method: str,
    path: str,
    now: datetime | None = None,
) -> AdminSecurityControl:
    """Fail closed unless the singleton administrator control is NORMAL."""

    del now  # The signature is shared with time-sensitive guards.
    operation = classify_admin_operation(method, path)
    if (
        ACTION_PATTERN.fullmatch(action) is None
        or operation is None
        or operation.risk != "HIGH"
        or operation.action != action
    ):
        raise AdminSecurityError(
            "invalid_high_risk_action",
            "The high-risk operation binding is invalid.",
            status_code=403,
        )
    try:
        controls = db.execute(
            select(AdminSecurityControl).with_for_update()
        ).scalars().all()
    except SQLAlchemyError as exc:
        raise AdminSecurityStoreUnavailable() from exc
    if len(controls) != 1:
        raise AdminSecurityStoreUnavailable()
    control = controls[0]
    if control.security_state != SecurityState.NORMAL.value:
        raise AdminSecurityError(
            "admin_security_state_blocks_operation",
            "This operation is blocked until administrator recovery is complete.",
            status_code=409,
        )
    return control


def _authorize_admin_bearer(
    db: Session,
    raw_token: str,
    *,
    runtime_totp_secret: str | None,
    device_id: str | None = None,
    app_kind: str | None = None,
    role: str | None = None,
    audience: str | None = None,
    now: datetime | None = None,
) -> AdminSessionIdentity:
    """Validate a database session and optional Android request binding."""

    observed_at = _as_utc(now or utc_now())
    if not raw_token or len(raw_token) > 512:
        raise AdminSecurityError(
            "admin_session_required",
            "A valid administrator session is required.",
            status_code=401,
        )
    if app_kind is not None and app_kind != ADMIN_APP_KIND:
        raise AdminSecurityError("admin_client_forbidden", "The client application is not authorized.", status_code=403)
    if role is not None and role != ADMIN_ROLE:
        raise AdminSecurityError("admin_client_forbidden", "The client role is not authorized.", status_code=403)
    if audience is not None and audience != ADMIN_AUDIENCE:
        raise AdminSecurityError("admin_client_forbidden", "The API audience is not authorized.", status_code=403)
    try:
        session = db.execute(
            select(AdminSecuritySession)
            .where(AdminSecuritySession.token_sha256 == sha256_text(raw_token))
            .with_for_update()
        ).scalar_one_or_none()
        if session is None or session.revoked_at is not None or _as_utc(session.expires_at) <= observed_at:
            raise AdminSecurityError(
                "admin_session_invalid",
                "The administrator session is invalid or expired.",
                status_code=401,
            )
        if device_id is not None and not secrets.compare_digest(session.device_id, device_id):
            raise AdminSecurityError(
                "admin_session_device_mismatch",
                "The administrator session is bound to another device.",
                status_code=403,
            )
        control = db.execute(_control_query(session.admin_id)).scalar_one_or_none()
        if control is None:
            raise AdminSecurityStoreUnavailable()
        if runtime_totp_secret is not None:
            require_runtime_totp_secret_matches(control, runtime_totp_secret)
        if control.security_state != SecurityState.NORMAL.value:
            raise AdminSecurityError(
                "admin_security_state_blocks_operation",
                "Administrator recovery must complete before this session can be used.",
                status_code=409,
            )
        session.last_seen_at = observed_at
        db.flush()
        return AdminSessionIdentity(
            admin_id=session.admin_id,
            session_id=session.id,
            device_id=session.device_id,
            device_label=session.device_label,
            expires_at=_as_utc(session.expires_at),
            step_up_verified_at=(
                _as_utc(session.step_up_verified_at)
                if session.step_up_verified_at is not None
                else None
            ),
        )
    except AdminSecurityError:
        raise
    except SQLAlchemyError as exc:
        raise AdminSecurityStoreUnavailable() from exc


def authorize_admin_bearer(
    db: Session,
    raw_token: str,
    *,
    runtime_totp_secret: str,
    device_id: str | None = None,
    app_kind: str | None = None,
    role: str | None = None,
    audience: str | None = None,
    now: datetime | None = None,
) -> AdminSessionIdentity:
    """Validate an API session and bind it to this replica's TOTP factor."""

    return _authorize_admin_bearer(
        db,
        raw_token,
        runtime_totp_secret=runtime_totp_secret,
        device_id=device_id,
        app_kind=app_kind,
        role=role,
        audience=audience,
        now=now,
    )


def _authorize_high_risk_bearer(
    db: Session,
    raw_token: str,
    action: str,
    now: datetime | None = None,
    *,
    method: str,
    path: str,
    nonce: str,
    runtime_totp_secret: str | None,
    device_id: str | None = None,
    app_kind: str | None = None,
    role: str | None = None,
    audience: str | None = None,
    max_step_up_age_seconds: int = DEFAULT_STEP_UP_TTL_SECONDS,
) -> AdminSessionIdentity:
    """Consume one exact, session-bound high-risk reconfirmation."""

    observed_at = _as_utc(now or utc_now())
    operation = classify_admin_operation(method, path)
    if (
        operation is None
        or operation.risk != "HIGH"
        or operation.action != action
        or not valid_reconfirmation_nonce(nonce)
    ):
        raise AdminSecurityError(
            "admin_reconfirmation_invalid",
            "The administrator reconfirmation binding is invalid.",
            status_code=403,
        )
    assert_high_risk_operation_allowed(
        db,
        action,
        operation.method,
        operation.path,
        observed_at,
    )
    identity = _authorize_admin_bearer(
        db,
        raw_token,
        runtime_totp_secret=runtime_totp_secret,
        device_id=device_id,
        app_kind=app_kind,
        role=role,
        audience=audience,
        now=observed_at,
    )
    del max_step_up_age_seconds
    reconfirmation = db.execute(
        select(AdminSecurityReconfirmation)
        .where(
            AdminSecurityReconfirmation.session_id == identity.session_id,
            AdminSecurityReconfirmation.admin_id == identity.admin_id,
            AdminSecurityReconfirmation.device_id == identity.device_id,
            AdminSecurityReconfirmation.action == operation.action,
            AdminSecurityReconfirmation.method == operation.method,
            AdminSecurityReconfirmation.path == operation.path,
            AdminSecurityReconfirmation.nonce_sha256 == sha256_text(nonce),
            AdminSecurityReconfirmation.consumed_at.is_(None),
            AdminSecurityReconfirmation.expires_at > observed_at,
        )
        .with_for_update()
    ).scalar_one_or_none()
    if reconfirmation is None:
        raise AdminSecurityError(
            "admin_reconfirmation_required",
            "A matching one-time administrator reconfirmation is required.",
            status_code=403,
        )
    reconfirmation.consumed_at = observed_at
    _append_admin_security_audit(
        db,
        action=operation.action,
        outcome="SUCCESS",
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        details={
            "method": operation.method,
            "path_sha256": sha256_text(operation.path),
            "reason": "reconfirmation_consumed",
        },
        created_at=observed_at,
    )
    return identity


def authorize_high_risk_bearer(
    db: Session,
    raw_token: str,
    action: str,
    now: datetime | None = None,
    *,
    method: str,
    path: str,
    nonce: str,
    runtime_totp_secret: str,
    device_id: str | None = None,
    app_kind: str | None = None,
    role: str | None = None,
    audience: str | None = None,
    max_step_up_age_seconds: int = DEFAULT_STEP_UP_TTL_SECONDS,
) -> AdminSessionIdentity:
    """Authorize an API high-risk operation with replica factor binding."""

    return _authorize_high_risk_bearer(
        db,
        raw_token,
        action,
        now,
        method=method,
        path=path,
        nonce=nonce,
        runtime_totp_secret=runtime_totp_secret,
        device_id=device_id,
        app_kind=app_kind,
        role=role,
        audience=audience,
        max_step_up_age_seconds=max_step_up_age_seconds,
    )


def consume_admin_high_risk_reconfirmation(
    raw_token: str,
    action: str,
    *,
    method: str,
    path: str,
    nonce: str,
    runtime_totp_secret: str,
    device_id: str | None = None,
    app_kind: str | None = None,
    role: str | None = None,
    audience: str | None = None,
    now: datetime | None = None,
) -> AdminSessionIdentity:
    """Commit one-time proof consumption before protected business work starts."""

    from backend.app.database import SessionLocal

    try:
        with SessionLocal.begin() as db:
            return _authorize_high_risk_bearer(
                db,
                raw_token,
                action,
                now,
                method=method,
                path=path,
                nonce=nonce,
                runtime_totp_secret=runtime_totp_secret,
                device_id=device_id,
                app_kind=app_kind,
                role=role,
                audience=audience,
            )
    except AdminSecurityError:
        raise
    except SQLAlchemyError as exc:
        raise AdminSecurityStoreUnavailable() from exc


def authorize_admin_protected_work(
    db: Session,
    raw_token: str,
    action: str,
    *,
    method: str,
    path: str,
    runtime_totp_secret: str,
    device_id: str | None = None,
    app_kind: str | None = None,
    role: str | None = None,
    audience: str | None = None,
    now: datetime | None = None,
) -> AdminSessionIdentity:
    """Lock control then session through the caller's protected transaction."""

    observed_at = _as_utc(now or utc_now())
    operation = classify_admin_operation(method, path)
    if operation is None or operation.risk != "HIGH" or operation.action != action:
        raise AdminSecurityError(
            "invalid_high_risk_action",
            "The high-risk operation binding is invalid.",
            status_code=403,
        )
    assert_high_risk_operation_allowed(
        db,
        action,
        operation.method,
        operation.path,
        observed_at,
    )
    return _authorize_admin_bearer(
        db,
        raw_token,
        runtime_totp_secret=runtime_totp_secret,
        device_id=device_id,
        app_kind=app_kind,
        role=role,
        audience=audience,
        now=observed_at,
    )


def authorize_database_bound_high_risk_bearer(
    db: Session,
    raw_token: str,
    action: str,
    now: datetime | None = None,
    *,
    method: str,
    path: str,
    nonce: str,
    device_id: str | None = None,
    max_step_up_age_seconds: int = DEFAULT_STEP_UP_TTL_SECONDS,
) -> AdminSessionIdentity:
    """Authorize an offline operation without distributing the TOTP seed.

    This adapter accepts only a database-issued, device-bound session whose
    control is NORMAL and whose password+TOTP step-up remains recent. It must
    not be used by HTTP request authentication, which additionally binds the
    running replica's configured TOTP factor.
    """

    identity = _authorize_high_risk_bearer(
        db,
        raw_token,
        action,
        now,
        method=method,
        path=path,
        nonce=nonce,
        runtime_totp_secret=None,
        device_id=device_id,
        max_step_up_age_seconds=max_step_up_age_seconds,
    )
    _append_admin_security_audit(
        db,
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        action="high_risk.authorize",
        outcome="SUCCESS",
        details={"operation": action, "adapter": "database_bound"},
    )
    return identity


class AdminSecurityService:
    """Transactional administrator security use cases for the HTTP adapter."""

    def __init__(self, db: Session, settings: Any) -> None:
        self.db = db
        self.settings = settings

    def _rollback_store_error(self, exc: BaseException) -> NoReturn:
        self.db.rollback()
        raise AdminSecurityStoreUnavailable() from exc

    def _commit(self) -> None:
        try:
            self.db.commit()
        except SQLAlchemyError as exc:
            self._rollback_store_error(exc)

    def _audit(
        self,
        action: str,
        outcome: str,
        *,
        admin_id: str | None = None,
        session_id: uuid.UUID | None = None,
        device_id: str | None = None,
        details: dict[str, Any] | None = None,
    ) -> None:
        _append_admin_security_audit(
            self.db,
            admin_id=admin_id,
            session_id=session_id,
            device_id=device_id,
            action=action,
            outcome=outcome,
            details=details,
        )

    def _lock_rate_limit_key(self, action: str, principal: str, source: str) -> None:
        del source
        # Serialize the account-wide bucket as well as requests from one source.
        # Otherwise rotating source addresses could race past the global limit.
        bind = self.db.get_bind()
        if bind is not None and bind.dialect.name == "postgresql":
            digest = sha256_text(f"{action}\0{principal}")
            key = int.from_bytes(
                bytes.fromhex(digest[:16]),
                byteorder="big",
                signed=True,
            )
            self.db.execute(
                text("SELECT pg_advisory_xact_lock(:key)"),
                {"key": key},
            )

    def _lock_recovery_state(self, admin_id: str) -> None:
        """Serialize start/resume/complete before taking recovery row locks."""

        bind = self.db.get_bind()
        if bind is not None and bind.dialect.name == "postgresql":
            digest = sha256_text(f"recovery-state\0{admin_id}")
            key = int.from_bytes(
                bytes.fromhex(digest[:16]),
                byteorder="big",
                signed=True,
            )
            self.db.execute(
                text("SELECT pg_advisory_xact_lock(:key)"),
                {"key": key},
            )

    def _rate_limit_retry_after(
        self,
        action: str,
        principal_sha256: str,
        source_sha256: str,
        now: datetime,
    ) -> int | None:
        window = int(self.settings.admin_auth_rate_limit_window_seconds)
        cutoff = now - timedelta(seconds=window)
        base_query = select(
            func.count(AdminSecurityAuthAttempt.id),
            func.min(AdminSecurityAuthAttempt.observed_at),
        ).where(
                AdminSecurityAuthAttempt.action == action,
                AdminSecurityAuthAttempt.principal_sha256 == principal_sha256,
                AdminSecurityAuthAttempt.success.is_(False),
                AdminSecurityAuthAttempt.observed_at >= cutoff,
            )
        global_count, global_first_seen = self.db.execute(base_query).one()
        source_count, source_first_seen = self.db.execute(
            base_query.where(
                AdminSecurityAuthAttempt.source_sha256 == source_sha256,
            )
        ).one()
        per_source_limit = int(self.settings.admin_auth_rate_limit_attempts)
        account_limit = per_source_limit * 4
        source_blocked = int(source_count) >= per_source_limit
        account_blocked = int(global_count) >= account_limit
        if not source_blocked and not account_blocked:
            return None
        first_seen = source_first_seen if source_blocked else global_first_seen
        if first_seen is None:
            return window
        return max(1, math.ceil(window - (now - _as_utc(first_seen)).total_seconds()))

    def _record_attempt(
        self,
        action: str,
        principal_sha256: str,
        source_sha256: str,
        success: bool,
        now: datetime,
    ) -> None:
        self.db.add(
            AdminSecurityAuthAttempt(
                action=action,
                principal_sha256=principal_sha256,
                source_sha256=source_sha256,
                success=success,
                observed_at=now,
            )
        )

    def _deny_and_commit(
        self,
        *,
        code: str,
        message: str,
        status_code: int,
        action: str,
        admin_id: str | None,
        device_id: str | None,
        details: dict[str, Any],
        session_id: uuid.UUID | None = None,
        retry_after: int | None = None,
    ) -> NoReturn:
        self._audit(
            action,
            "DENIED",
            admin_id=admin_id,
            session_id=session_id,
            device_id=device_id,
            details=details,
        )
        self._commit()
        raise AdminSecurityError(
            code,
            message,
            status_code=status_code,
            retry_after=retry_after,
        )

    def _issue_session(
        self,
        control: AdminSecurityControl,
        device_id: str,
        device_label: str,
        now: datetime,
    ) -> tuple[str, AdminSecuritySession]:
        existing = self.db.execute(
            select(AdminSecuritySession)
            .where(
                AdminSecuritySession.admin_id == control.admin_id,
                AdminSecuritySession.device_id == device_id,
                AdminSecuritySession.revoked_at.is_(None),
            )
            .with_for_update()
        ).scalars().all()
        for old_session in existing:
            old_session.revoked_at = now
            old_session.revoked_reason = "device_session_replaced"
        raw_token = secrets.token_urlsafe(SESSION_TOKEN_BYTES)
        session = AdminSecuritySession(
            admin_id=control.admin_id,
            device_id=device_id,
            device_label=device_label,
            token_sha256=sha256_text(raw_token),
            issued_at=now,
            expires_at=now + timedelta(seconds=int(self.settings.admin_session_ttl_seconds)),
            step_up_verified_at=now,
            last_seen_at=now,
        )
        self.db.add(session)
        self.db.flush()
        return raw_token, session

    def login(
        self,
        *,
        admin_id: str,
        password: str,
        totp_code: str,
        device_id: str,
        device_label: str,
        source: str,
        now: datetime | None = None,
    ) -> SessionGrant:
        observed_at = _as_utc(now or utc_now())
        principal_digest = auth_rate_limit_principal(
            self.settings.admin_id, "login"
        )
        source_digest = sha256_text(source or "unknown")
        try:
            self._lock_rate_limit_key("login", principal_digest, source_digest)
            retry_after = self._rate_limit_retry_after(
                "login", principal_digest, source_digest, observed_at
            )
            if retry_after is not None:
                self._record_attempt("login", principal_digest, source_digest, False, observed_at)
                self._deny_and_commit(
                    code="admin_auth_rate_limited",
                    message="Too many administrator login attempts.",
                    status_code=429,
                    action="session.login",
                    admin_id=None,
                    device_id=device_id,
                    details={"reason": "rate_limited"},
                    retry_after=retry_after,
                )
            control = self.db.execute(
                _control_query(self.settings.admin_id, for_update=True)
            ).scalar_one_or_none()
            if control is None:
                self._record_attempt("login", principal_digest, source_digest, False, observed_at)
                self._audit(
                    "session.login",
                    "ERROR",
                    device_id=device_id,
                    details={"reason": "not_provisioned"},
                )
                self._commit()
                raise AdminSecurityStoreUnavailable()
            try:
                require_runtime_totp_secret_matches(
                    control,
                    self.settings.admin_totp_secret,
                )
            except AdminSecurityError:
                self._audit(
                    "session.login",
                    "ERROR",
                    device_id=device_id,
                    details={"reason": "runtime_factor_mismatch"},
                )
                self._commit()
                raise
            admin_matches = secrets.compare_digest(admin_id, self.settings.admin_id)
            password_matches = verify_password(password, control.password_hash)
            if not admin_matches or not password_matches:
                self._record_attempt("login", principal_digest, source_digest, False, observed_at)
                self._deny_and_commit(
                    code="admin_credentials_invalid",
                    message="The administrator credentials are invalid.",
                    status_code=401,
                    action="session.login",
                    admin_id=control.admin_id if admin_matches else None,
                    device_id=device_id,
                    details={"reason": "credentials_invalid"},
                )
            if control.security_state != SecurityState.NORMAL.value:
                self._record_attempt("login", principal_digest, source_digest, False, observed_at)
                self._deny_and_commit(
                    code="admin_recovery_required",
                    message="Administrator recovery must complete before login.",
                    status_code=409,
                    action="session.login",
                    admin_id=control.admin_id,
                    device_id=device_id,
                    details={"reason": "security_state", "state": control.security_state},
                )
            timecode, replayed = verify_totp_timecode(
                self.settings.admin_totp_secret,
                totp_code,
                last_accepted_timecode=control.last_totp_timecode,
                now=observed_at,
            )
            if timecode is None or replayed:
                self._record_attempt("login", principal_digest, source_digest, False, observed_at)
                self._deny_and_commit(
                    code="admin_totp_replayed" if replayed else "admin_credentials_invalid",
                    message=(
                        "This TOTP time step was already used."
                        if replayed
                        else "The administrator credentials are invalid."
                    ),
                    status_code=401,
                    action="session.login",
                    admin_id=control.admin_id,
                    device_id=device_id,
                    details={"reason": "totp_replayed" if replayed else "credentials_invalid"},
                )
            control.last_totp_timecode = timecode
            raw_token, session = self._issue_session(
                control, device_id, device_label, observed_at
            )
            self._record_attempt("login", principal_digest, source_digest, True, observed_at)
            self._audit(
                "session.login",
                "SUCCESS",
                admin_id=control.admin_id,
                session_id=session.id,
                device_id=device_id,
                details={"replaced_device_sessions": True},
            )
            self._commit()
            return SessionGrant(raw_token, control.security_state, str(session.id))
        except AdminSecurityError:
            raise
        except SQLAlchemyError as exc:
            self._rollback_store_error(exc)

    def get_state(
        self,
        identity: AdminSessionIdentity,
        *,
        now: datetime | None = None,
    ) -> dict[str, str]:
        observed_at = _as_utc(now or utc_now())
        try:
            control = self.db.execute(_control_query(identity.admin_id)).scalar_one_or_none()
            if control is None:
                raise AdminSecurityStoreUnavailable()
            return {
                "security_state": control.security_state,
                "state_version": str(control.state_version),
                "observed_at": observed_at.isoformat(),
            }
        except AdminSecurityError:
            raise
        except SQLAlchemyError as exc:
            self._rollback_store_error(exc)

    def list_sessions(
        self,
        identity: AdminSessionIdentity,
        *,
        now: datetime | None = None,
    ) -> list[dict[str, Any]]:
        observed_at = _as_utc(now or utc_now())
        try:
            sessions = self.db.execute(
                select(AdminSecuritySession)
                .where(AdminSecuritySession.admin_id == identity.admin_id)
                .order_by(AdminSecuritySession.issued_at.desc())
                .limit(100)
            ).scalars().all()
            return [
                {
                    "session_id": str(session.id),
                    "device_id": session.device_id,
                    "device_label": session.device_label,
                    "current": session.id == identity.session_id,
                    "revoked": (
                        session.revoked_at is not None
                        or _as_utc(session.expires_at) <= observed_at
                    ),
                    "last_seen_at": _as_utc(session.last_seen_at).isoformat(),
                }
                for session in sessions
            ]
        except SQLAlchemyError as exc:
            self._rollback_store_error(exc)

    def revoke_session(
        self,
        identity: AdminSessionIdentity,
        session_id: uuid.UUID,
        *,
        now: datetime | None = None,
    ) -> dict[str, str]:
        observed_at = _as_utc(now or utc_now())
        try:
            control = self.db.execute(
                _control_query(identity.admin_id, for_update=True)
            ).scalar_one_or_none()
            if control is None:
                raise AdminSecurityStoreUnavailable()
            if control.security_state != SecurityState.NORMAL.value:
                raise AdminSecurityError(
                    "admin_security_state_blocks_operation",
                    "Administrator recovery must complete before revoking a session.",
                    status_code=409,
                )
            caller = self.db.execute(
                select(AdminSecuritySession)
                .where(
                    AdminSecuritySession.id == identity.session_id,
                    AdminSecuritySession.admin_id == identity.admin_id,
                )
                .with_for_update()
            ).scalar_one_or_none()
            if (
                caller is None
                or caller.revoked_at is not None
                or _as_utc(caller.expires_at) <= observed_at
                or not secrets.compare_digest(caller.device_id, identity.device_id)
            ):
                raise AdminSecurityError(
                    "admin_session_invalid",
                    "The administrator session is invalid or expired.",
                    status_code=401,
                )
            session = (
                caller
                if session_id == identity.session_id
                else self.db.execute(
                    select(AdminSecuritySession)
                    .where(
                        AdminSecuritySession.id == session_id,
                        AdminSecuritySession.admin_id == identity.admin_id,
                    )
                    .with_for_update()
                ).scalar_one_or_none()
            )
            if session is None:
                raise AdminSecurityError(
                    "admin_session_not_found",
                    "The administrator session was not found.",
                    status_code=404,
                )
            if session.revoked_at is None:
                session.revoked_at = observed_at
                session.revoked_reason = "administrator_revoked"
                self._audit(
                    "session.revoke",
                    "SUCCESS",
                    admin_id=identity.admin_id,
                    session_id=session.id,
                    device_id=session.device_id,
                    details={"current_session": session.id == identity.session_id},
                )
            self._commit()
            return {
                "security_state": control.security_state,
                "state_version": str(control.state_version),
                "observed_at": observed_at.isoformat(),
            }
        except AdminSecurityError:
            self.db.rollback()
            raise
        except SQLAlchemyError as exc:
            self._rollback_store_error(exc)

    def reauthenticate(
        self,
        identity: AdminSessionIdentity,
        *,
        password: str,
        totp_code: str,
        action: str,
        method: str,
        path: str,
        nonce: str,
        source: str = "unknown",
        now: datetime | None = None,
    ) -> ReconfirmationGrant:
        observed_at = _as_utc(now or utc_now())
        operation = classify_admin_operation(method, path)
        if (
            operation is None
            or operation.risk != "HIGH"
            or operation.action != action
            or not valid_reconfirmation_nonce(nonce)
        ):
            self._deny_and_commit(
                code="admin_reconfirmation_binding_invalid",
                message="The requested administrator operation cannot be reconfirmed.",
                status_code=422,
                action="session.reauthenticate",
                admin_id=identity.admin_id,
                session_id=identity.session_id,
                device_id=identity.device_id,
                details={
                    "method": method.upper()[:16],
                    "path_sha256": sha256_text(path[:2048]),
                    "reason": "operation_binding_invalid",
                },
            )
        principal_digest = auth_rate_limit_principal(
            self.settings.admin_id,
            "reauthenticate",
        )
        source_digest = sha256_text(source or "unknown")
        try:
            self._lock_rate_limit_key(
                "reauthenticate",
                principal_digest,
                source_digest,
            )
            retry_after = self._rate_limit_retry_after(
                "reauthenticate",
                principal_digest,
                source_digest,
                observed_at,
            )
            if retry_after is not None:
                self._record_attempt(
                    "reauthenticate",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                self._deny_and_commit(
                    code="admin_auth_rate_limited",
                    message="Too many administrator reauthentication attempts.",
                    status_code=429,
                    action="session.reauthenticate",
                    admin_id=identity.admin_id,
                    device_id=identity.device_id,
                    details={"reason": "rate_limited"},
                    retry_after=retry_after,
                )
            control = self.db.execute(
                _control_query(identity.admin_id, for_update=True)
            ).scalar_one_or_none()
            session = self.db.execute(
                select(AdminSecuritySession)
                .where(AdminSecuritySession.id == identity.session_id)
                .with_for_update()
            ).scalar_one_or_none()
            if control is None or session is None or session.revoked_at is not None:
                raise AdminSecurityError(
                    "admin_session_invalid",
                    "The administrator session is invalid or expired.",
                    status_code=401,
                )
            try:
                require_runtime_totp_secret_matches(
                    control,
                    self.settings.admin_totp_secret,
                )
            except AdminSecurityError:
                self._audit(
                    "session.reauthenticate",
                    "ERROR",
                    admin_id=identity.admin_id,
                    session_id=identity.session_id,
                    device_id=identity.device_id,
                    details={"reason": "runtime_factor_mismatch"},
                )
                self._commit()
                raise
            if control.security_state != SecurityState.NORMAL.value:
                raise AdminSecurityError(
                    "admin_security_state_blocks_operation",
                    "Administrator recovery must complete before reauthentication.",
                    status_code=409,
                )
            timecode, replayed = verify_totp_timecode(
                self.settings.admin_totp_secret,
                totp_code,
                last_accepted_timecode=control.last_totp_timecode,
                now=observed_at,
            )
            if not verify_password(password, control.password_hash) or timecode is None or replayed:
                self._record_attempt(
                    "reauthenticate",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                self._audit(
                    "session.reauthenticate",
                    "DENIED",
                    admin_id=identity.admin_id,
                    session_id=identity.session_id,
                    device_id=identity.device_id,
                    details={"reason": "credentials_invalid_or_replayed"},
                )
                self._commit()
                raise AdminSecurityError(
                    "admin_reauthentication_failed",
                    "Password and a fresh TOTP code are required.",
                    status_code=401,
                )
            control.last_totp_timecode = timecode
            session.step_up_verified_at = None
            session.last_seen_at = observed_at
            pending_reconfirmations = self.db.execute(
                select(AdminSecurityReconfirmation)
                .where(
                    AdminSecurityReconfirmation.session_id == identity.session_id,
                    AdminSecurityReconfirmation.consumed_at.is_(None),
                )
                .with_for_update()
            ).scalars().all()
            for pending in pending_reconfirmations:
                pending.consumed_at = observed_at
            until = observed_at + timedelta(
                seconds=int(self.settings.admin_step_up_ttl_seconds)
            )
            self.db.add(
                AdminSecurityReconfirmation(
                    admin_id=identity.admin_id,
                    session_id=identity.session_id,
                    device_id=identity.device_id,
                    action=operation.action,
                    method=operation.method,
                    path=operation.path,
                    nonce_sha256=sha256_text(nonce),
                    verified_at=observed_at,
                    expires_at=until,
                )
            )
            self._record_attempt(
                "reauthenticate",
                principal_digest,
                source_digest,
                True,
                observed_at,
            )
            self._audit(
                "session.reauthenticate",
                "SUCCESS",
                admin_id=identity.admin_id,
                session_id=identity.session_id,
                device_id=identity.device_id,
                details={
                    "action": operation.action,
                    "method": operation.method,
                    "path_sha256": sha256_text(operation.path),
                },
            )
            self._commit()
            return ReconfirmationGrant(
                reauthenticated_until_epoch_ms=int(until.timestamp() * 1000),
                action=operation.action,
                method=operation.method,
                path=operation.path,
            )
        except AdminSecurityError:
            raise
        except SQLAlchemyError as exc:
            self._rollback_store_error(exc)

    def start_recovery(
        self,
        *,
        admin_id: str,
        recovery_code: str,
        device_id: str,
        device_label: str,
        source: str,
        now: datetime | None = None,
    ) -> RecoveryGrant:
        observed_at = _as_utc(now or utc_now())
        principal_digest = auth_rate_limit_principal(
            self.settings.admin_id, "recovery_start"
        )
        source_digest = sha256_text(source or "unknown")
        code_digest = recovery_code_sha256(recovery_code)
        try:
            self._lock_rate_limit_key("recovery_start", principal_digest, source_digest)
            self._lock_recovery_state(self.settings.admin_id)
            retry_after = self._rate_limit_retry_after(
                "recovery_start", principal_digest, source_digest, observed_at
            )
            if retry_after is not None:
                self._record_attempt(
                    "recovery_start", principal_digest, source_digest, False, observed_at
                )
                self._deny_and_commit(
                    code="admin_auth_rate_limited",
                    message="Too many administrator recovery attempts.",
                    status_code=429,
                    action="recovery.start",
                    admin_id=None,
                    device_id=device_id,
                    details={"reason": "rate_limited"},
                    retry_after=retry_after,
                )
            control = self.db.execute(
                _control_query(self.settings.admin_id, for_update=True)
            ).scalar_one_or_none()
            code_record = self.db.execute(
                select(AdminSecurityRecoveryCode)
                .where(
                    AdminSecurityRecoveryCode.admin_id == self.settings.admin_id,
                    AdminSecurityRecoveryCode.code_sha256 == code_digest,
                    AdminSecurityRecoveryCode.used_at.is_(None),
                )
                .with_for_update()
            ).scalar_one_or_none()
            if (
                control is None
                or code_record is None
                or not secrets.compare_digest(admin_id, self.settings.admin_id)
            ):
                self._record_attempt(
                    "recovery_start", principal_digest, source_digest, False, observed_at
                )
                self._deny_and_commit(
                    code="admin_recovery_credentials_invalid",
                    message="The recovery credentials are invalid.",
                    status_code=401,
                    action="recovery.start",
                    admin_id=None,
                    device_id=device_id,
                    details={"reason": "credentials_invalid"},
                )
            active = self.db.execute(
                select(AdminSecurityRecoveryTransaction)
                .where(
                    AdminSecurityRecoveryTransaction.admin_id == control.admin_id,
                    AdminSecurityRecoveryTransaction.completed_at.is_(None),
                    AdminSecurityRecoveryTransaction.expires_at > observed_at,
                )
                .with_for_update()
            ).scalars().first()
            if active is not None:
                if (
                    active.recovery_code_id == code_record.id
                    and secrets.compare_digest(active.device_id, device_id)
                ):
                    raw_token = secrets.token_urlsafe(RECOVERY_TOKEN_BYTES)
                    active.recovery_token_sha256 = sha256_text(raw_token)
                    self._record_attempt(
                        "recovery_start",
                        principal_digest,
                        source_digest,
                        True,
                        observed_at,
                    )
                    self._audit(
                        "recovery.resume",
                        "SUCCESS",
                        admin_id=control.admin_id,
                        device_id=device_id,
                        details={"expiry_extended": False},
                    )
                    self._commit()
                    return RecoveryGrant(
                        raw_token,
                        SecurityState.RECOVERY_IN_PROGRESS.value,
                    )
                self._record_attempt(
                    "recovery_start", principal_digest, source_digest, False, observed_at
                )
                self._deny_and_commit(
                    code="admin_recovery_in_progress",
                    message="An administrator recovery transaction is already in progress.",
                    status_code=409,
                    action="recovery.start",
                    admin_id=control.admin_id,
                    device_id=device_id,
                    details={"reason": "already_in_progress"},
                )
            for session in self.db.execute(
                select(AdminSecuritySession)
                .where(
                    AdminSecuritySession.admin_id == control.admin_id,
                    AdminSecuritySession.revoked_at.is_(None),
                )
                .with_for_update()
            ).scalars().all():
                session.revoked_at = observed_at
                session.revoked_reason = "recovery_started"
            raw_token = secrets.token_urlsafe(RECOVERY_TOKEN_BYTES)
            transaction = AdminSecurityRecoveryTransaction(
                admin_id=control.admin_id,
                recovery_code_id=code_record.id,
                recovery_token_sha256=sha256_text(raw_token),
                previous_totp_secret_fingerprint=control.totp_secret_fingerprint,
                device_id=device_id,
                device_label=device_label,
                started_at=observed_at,
                expires_at=observed_at
                + timedelta(seconds=int(self.settings.admin_recovery_ttl_seconds)),
            )
            self.db.add(transaction)
            control.security_state = SecurityState.RECOVERY_IN_PROGRESS.value
            control.state_version += 1
            self._record_attempt(
                "recovery_start", principal_digest, source_digest, True, observed_at
            )
            self._audit(
                "recovery.start",
                "SUCCESS",
                admin_id=control.admin_id,
                device_id=device_id,
                details={"all_sessions_revoked": True},
            )
            self._commit()
            return RecoveryGrant(raw_token, SecurityState.RECOVERY_IN_PROGRESS.value)
        except AdminSecurityError:
            raise
        except SQLAlchemyError as exc:
            self._rollback_store_error(exc)

    def complete_recovery(
        self,
        *,
        recovery_token: str,
        new_password: str,
        totp_code: str,
        device_id: str,
        device_label: str,
        source: str,
        now: datetime | None = None,
    ) -> SessionGrant:
        observed_at = _as_utc(now or utc_now())
        principal_digest = auth_rate_limit_principal(
            self.settings.admin_id,
            "recovery_complete",
            recovery_token=recovery_token,
        )
        source_digest = sha256_text(source or "unknown")
        try:
            self._lock_rate_limit_key(
                "recovery_complete", principal_digest, source_digest
            )
            retry_after = self._rate_limit_retry_after(
                "recovery_complete", principal_digest, source_digest, observed_at
            )
            if retry_after is not None:
                self._record_attempt(
                    "recovery_complete",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                self._deny_and_commit(
                    code="admin_auth_rate_limited",
                    message="Too many administrator recovery verification attempts.",
                    status_code=429,
                    action="recovery.complete",
                    admin_id=None,
                    device_id=device_id,
                    details={"reason": "rate_limited"},
                    retry_after=retry_after,
                )
            transaction_admin_id = self.db.execute(
                select(AdminSecurityRecoveryTransaction.admin_id).where(
                    AdminSecurityRecoveryTransaction.recovery_token_sha256
                    == sha256_text(recovery_token)
                )
            ).scalar_one_or_none()
            self._lock_recovery_state(
                transaction_admin_id or self.settings.admin_id
            )
            transaction = self.db.execute(
                select(AdminSecurityRecoveryTransaction)
                .where(
                    AdminSecurityRecoveryTransaction.recovery_token_sha256
                    == sha256_text(recovery_token)
                )
                .with_for_update()
            ).scalar_one_or_none()
            if transaction is None or transaction.completed_at is not None:
                self._record_attempt(
                    "recovery_complete",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                self._deny_and_commit(
                    code="admin_recovery_invalid",
                    message="The recovery transaction is invalid.",
                    status_code=401,
                    action="recovery.complete",
                    admin_id=None,
                    device_id=device_id,
                    details={"reason": "transaction_invalid"},
                )
            control = self.db.execute(
                _control_query(transaction.admin_id, for_update=True)
            ).scalar_one_or_none()
            if control is None:
                raise AdminSecurityStoreUnavailable()
            code_record = self.db.execute(
                select(AdminSecurityRecoveryCode)
                .where(
                    AdminSecurityRecoveryCode.id == transaction.recovery_code_id,
                    AdminSecurityRecoveryCode.admin_id == transaction.admin_id,
                )
                .with_for_update()
            ).scalar_one_or_none()
            if code_record is None or code_record.used_at is not None:
                self._record_attempt(
                    "recovery_complete",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                self._deny_and_commit(
                    code="admin_recovery_invalid",
                    message="The recovery transaction is no longer usable.",
                    status_code=401,
                    action="recovery.complete",
                    admin_id=control.admin_id,
                    device_id=device_id,
                    details={"reason": "credential_consumed_or_missing"},
                )
            if _as_utc(transaction.expires_at) <= observed_at:
                self._record_attempt(
                    "recovery_complete",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                newer_active = self.db.execute(
                    select(AdminSecurityRecoveryTransaction)
                    .where(
                        AdminSecurityRecoveryTransaction.admin_id == transaction.admin_id,
                        AdminSecurityRecoveryTransaction.id != transaction.id,
                        AdminSecurityRecoveryTransaction.completed_at.is_(None),
                        AdminSecurityRecoveryTransaction.expires_at > observed_at,
                    )
                    .with_for_update()
                ).scalars().first()
                next_state = (
                    SecurityState.RECOVERY_IN_PROGRESS.value
                    if newer_active is not None
                    else SecurityState.RECOVERY_REQUIRED.value
                )
                if control.security_state != next_state:
                    control.security_state = next_state
                    control.state_version += 1
                self._audit(
                    "recovery.complete",
                    "DENIED",
                    admin_id=control.admin_id,
                    device_id=device_id,
                    details={"reason": "expired", "next_state": next_state},
                )
                self._commit()
                raise AdminSecurityError(
                    "admin_recovery_expired",
                    "The recovery transaction expired; start recovery again.",
                    status_code=410,
                )
            if (
                control.security_state != SecurityState.RECOVERY_IN_PROGRESS.value
                or not secrets.compare_digest(transaction.device_id, device_id)
            ):
                self._record_attempt(
                    "recovery_complete",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                self._deny_and_commit(
                    code="admin_recovery_invalid",
                    message="The recovery transaction is invalid for this device.",
                    status_code=403,
                    action="recovery.complete",
                    admin_id=control.admin_id,
                    device_id=device_id,
                    details={"reason": "device_or_state_invalid"},
                )
            replacement_fingerprint = totp_secret_fingerprint(
                self.settings.admin_totp_secret
            )
            if (
                secrets.compare_digest(
                    replacement_fingerprint,
                    transaction.previous_totp_secret_fingerprint,
                )
                or secrets.compare_digest(
                    replacement_fingerprint,
                    control.totp_secret_fingerprint,
                )
            ):
                self._record_attempt(
                    "recovery_complete",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                self._audit(
                    "recovery.complete",
                    "DENIED",
                    admin_id=control.admin_id,
                    device_id=device_id,
                    details={"reason": "replacement_factor_required"},
                )
                self._commit()
                raise AdminSecurityError(
                    "admin_totp_secret_not_replaced",
                    "Replace WALKSAFE_ADMIN_TOTP_SECRET before completing recovery.",
                    status_code=409,
                )
            timecode, _ = verify_totp_timecode(
                self.settings.admin_totp_secret,
                totp_code,
                last_accepted_timecode=None,
                now=observed_at,
            )
            if timecode is None:
                self._record_attempt(
                    "recovery_complete",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                self._audit(
                    "recovery.complete",
                    "DENIED",
                    admin_id=control.admin_id,
                    device_id=device_id,
                    details={"reason": "replacement_factor_invalid"},
                )
                self._commit()
                raise AdminSecurityError(
                    "admin_recovery_verification_failed",
                    "The replacement TOTP verification failed.",
                    status_code=401,
                )
            if verify_password(new_password, control.password_hash):
                self._record_attempt(
                    "recovery_complete",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                self._audit(
                    "recovery.complete",
                    "DENIED",
                    admin_id=control.admin_id,
                    device_id=device_id,
                    details={"reason": "password_reuse"},
                )
                self._commit()
                raise AdminSecurityError(
                    "admin_password_reuse_forbidden",
                    "The replacement password must differ from the previous password.",
                    status_code=422,
                )
            try:
                replacement_password_hash = hash_password(new_password)
            except ValueError as exc:
                self._record_attempt(
                    "recovery_complete",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                self._audit(
                    "recovery.complete",
                    "DENIED",
                    admin_id=control.admin_id,
                    device_id=device_id,
                    details={"reason": "password_policy"},
                )
                self._commit()
                raise AdminSecurityError(
                    "admin_password_policy_failed",
                    str(exc),
                    status_code=422,
                ) from exc
            control.password_hash = replacement_password_hash
            control.totp_secret_fingerprint = replacement_fingerprint
            control.last_totp_timecode = timecode
            control.security_state = SecurityState.NORMAL.value
            control.state_version += 1
            code_record.used_at = observed_at
            transaction.completed_at = observed_at
            raw_token, session = self._issue_session(
                control, device_id, device_label, observed_at
            )
            self._audit(
                "recovery.complete",
                "SUCCESS",
                admin_id=control.admin_id,
                session_id=session.id,
                device_id=device_id,
                details={"state": SecurityState.NORMAL.value},
            )
            self._record_attempt(
                "recovery_complete",
                principal_digest,
                source_digest,
                True,
                observed_at,
            )
            self._commit()
            return SessionGrant(raw_token, SecurityState.NORMAL.value, str(session.id))
        except AdminSecurityError:
            raise
        except SQLAlchemyError as exc:
            self._rollback_store_error(exc)


def provision_admin_security(
    db: Session,
    *,
    admin_id: str,
    password: str,
    totp_secret: str,
    recovery_codes: list[str],
    now: datetime | None = None,
) -> None:
    """Create the singleton control and recovery digests in a trusted process."""

    observed_at = _as_utc(now or utc_now())
    normalized_codes = [_normalized_recovery_code(code) for code in recovery_codes]
    if not normalized_codes or any(len(code) < 24 for code in normalized_codes):
        raise ValueError(
            "at least one generated high-entropy recovery code of 24 or more characters is required"
        )
    code_digests = {sha256_text(code) for code in normalized_codes}
    if len(code_digests) != len(normalized_codes):
        raise ValueError("recovery codes must be unique")
    canonical_totp_secret = validate_admin_totp_secret(totp_secret)
    try:
        bind = db.get_bind()
        if bind is not None and bind.dialect.name == "postgresql":
            singleton_digest = sha256_text("admin-security-provision-singleton")
            singleton_key = int.from_bytes(
                bytes.fromhex(singleton_digest[:16]),
                byteorder="big",
                signed=True,
            )
            db.execute(
                text("SELECT pg_advisory_xact_lock(:key)"),
                {"key": singleton_key},
            )
        existing = db.execute(select(AdminSecurityControl).with_for_update()).scalars().all()
        if existing:
            raise ValueError("administrator security is already provisioned")
        control = AdminSecurityControl(
            admin_id=admin_id,
            password_hash=hash_password(password),
            totp_secret_fingerprint=totp_secret_fingerprint(canonical_totp_secret),
            security_state=SecurityState.NORMAL.value,
            state_version=1,
            created_at=observed_at,
            updated_at=observed_at,
        )
        db.add(control)
        for digest in code_digests:
            db.add(
                AdminSecurityRecoveryCode(
                    admin_id=admin_id,
                    code_sha256=digest,
                    created_at=observed_at,
                )
            )
        _append_admin_security_audit(
            db,
            admin_id=admin_id,
            action="security.provision",
            outcome="SUCCESS",
            details={"recovery_count": len(code_digests)},
            created_at=observed_at,
        )
        db.commit()
    except SQLAlchemyError as exc:
        db.rollback()
        raise AdminSecurityStoreUnavailable() from exc


__all__ = [
    "ADMIN_APP_KIND",
    "ADMIN_AUDIENCE",
    "ADMIN_ROLE",
    "AdminSecurityError",
    "AdminSecurityService",
    "AdminSecurityStoreUnavailable",
    "AdminSessionIdentity",
    "RecoveryGrant",
    "SecurityState",
    "SessionGrant",
    "assert_high_risk_operation_allowed",
    "auth_rate_limit_principal",
    "authorize_admin_bearer",
    "authorize_database_bound_high_risk_bearer",
    "authorize_high_risk_bearer",
    "hash_password",
    "provision_admin_security",
    "record_admin_security_failure",
    "recovery_code_sha256",
    "require_runtime_totp_secret_matches",
    "sha256_text",
    "totp_secret_fingerprint",
    "verify_password",
    "verify_totp_timecode",
]
