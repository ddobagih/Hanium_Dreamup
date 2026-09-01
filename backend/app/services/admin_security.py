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
import os
from pathlib import Path
import re
import secrets
from typing import Any, NoReturn
import uuid

import pyotp
from sqlalchemy import case, func, select, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.config import DEPLOYMENT_ENVIRONMENTS, validate_admin_totp_secret
from backend.app.models import (
    AdminDeviceKey,
    AdminSecurityAudit,
    AdminSecurityAuthAttempt,
    AdminSecurityControl,
    AdminSecurityReconfirmation,
    AdminSecurityRecoveryCode,
    AdminSecurityRecoveryTransaction,
    AdminSecuritySession,
)
from backend.app.services.admin_credential_issuer_key import (
    AdminCredentialIssuerKeyError,
    load_admin_credential_issuer_key,
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
RECOVERY_CUSTODY_REFERENCE_PATTERN = re.compile(
    r"^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$"
)
REPORT_STATUS_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/status$"
)
ADMIN_REPORT_STATUS_PATH_PATTERN = re.compile(
    r"^/admin/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/status$"
)
ADMIN_REPORT_DELIVERY_PACKAGE_PATH_PATTERN = re.compile(
    r"^/admin/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/delivery-packages$"
)
ADMIN_REPORT_REQUEST_STATUS_PATH_PATTERN = re.compile(
    r"^/admin/report-requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/status$"
)
ADMIN_REPORT_DELETION_EXTERNAL_COPY_EVENT_PATH_PATTERN = re.compile(
    r"^/admin/report-deletions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/external-copies/"
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/events$"
)
ADMIN_INCIDENT_STATUS_PATH_PATTERN = re.compile(
    r"^/admin/incidents/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/status$"
)
ADMIN_RAW_COLLECTION_DECISION_PATH_PATTERN = re.compile(
    r"^/admin/raw-collections/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/decisions$"
)
ADMIN_RAW_COLLECTION_HOLD_PATH_PATTERN = re.compile(
    r"^/admin/raw-collections/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/legal-holds$"
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
ADMIN_DEVICE_REPORT_LOST_PATH_PATTERN = re.compile(
    r"^/admin/security/devices/[A-Za-z0-9][A-Za-z0-9._:-]{7,127}/report-lost$"
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
ADMIN_RECOVERY_CUSTODY_ATTEST_PATH = "/admin/security/recovery-custody/attest"
RELEASE_APPROVAL_PATH = "/admin/operations/release-approvals"
PRIVILEGE_CHANGE_PATH = "/admin/operations/privilege-changes"
DATA_DELETE_PATH = "/admin/operations/data-deletions"
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


class RecoveryCustodyState(str, Enum):
    UNATTESTED = "UNATTESTED"
    ATTESTED = "ATTESTED"


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


class AdminCredentialIssuerUnavailable(AdminSecurityError):
    def __init__(self) -> None:
        super().__init__(
            "admin_credential_issuer_unavailable",
            "Administrator credential issuance is temporarily unavailable.",
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
    if (
        normalized_method == "GET"
        and normalized_path == "/admin/raw-collections/quarantine"
    ):
        return AdminOperation(
            "admin.raw_collection.list", normalized_method, normalized_path, "HIGH"
        )
    if (
        normalized_method == "POST"
        and ADMIN_RAW_COLLECTION_DECISION_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.raw_collection.purpose_decide",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "POST"
        and ADMIN_RAW_COLLECTION_HOLD_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.raw_collection.legal_hold",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if normalized_method == "GET" and normalized_path == "/reports/export":
        return AdminOperation("report.export", normalized_method, normalized_path, "HIGH")
    for operation_path, action in (
        (RELEASE_APPROVAL_PATH, "release.approval"),
        (PRIVILEGE_CHANGE_PATH, "privilege.change"),
        (DATA_DELETE_PATH, "data.delete"),
    ):
        if normalized_method == "POST" and normalized_path == operation_path:
            return AdminOperation(action, normalized_method, normalized_path, "HIGH")
    if normalized_method == "PATCH" and REPORT_STATUS_PATH_PATTERN.fullmatch(normalized_path):
        return AdminOperation(
            "report.status.patch",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "PATCH"
        and ADMIN_REPORT_STATUS_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.report.status.update",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "POST"
        and ADMIN_REPORT_DELIVERY_PACKAGE_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.report.delivery_package.create",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "PATCH"
        and ADMIN_REPORT_REQUEST_STATUS_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.report_request.status.update",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "PATCH"
        and ADMIN_INCIDENT_STATUS_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.incident.status.update",
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
        and ADMIN_REPORT_DELETION_EXTERNAL_COPY_EVENT_PATH_PATTERN.fullmatch(
            normalized_path
        )
    ):
        return AdminOperation(
            "report.external_copy_deletion.record",
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
    if (
        normalized_method == "POST"
        and normalized_path == ADMIN_RECOVERY_CUSTODY_ATTEST_PATH
    ):
        return AdminOperation(
            "recovery.custody.attest",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and ADMIN_DEVICE_REPORT_LOST_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "device.report_lost",
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


def valid_recovery_custody_reference(reference: str) -> bool:
    if (
        not isinstance(reference, str)
        or RECOVERY_CUSTODY_REFERENCE_PATTERN.fullmatch(reference) is None
    ):
        return False
    try:
        decoded = base64.urlsafe_b64decode(reference + "=")
    except (binascii.Error, ValueError):
        return False
    canonical = base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=")
    return len(decoded) == 32 and secrets.compare_digest(canonical, reference)


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def credential_issuer_key_sha256(credential_issuer_key: str) -> str:
    """Validate and fingerprint one canonical 256-bit issuer key."""

    if not isinstance(credential_issuer_key, str):
        raise ValueError("credential issuer key must be canonical base64url")
    try:
        encoded = credential_issuer_key.encode("ascii")
        decoded = base64.b64decode(
            encoded + b"=",
            altchars=b"-_",
            validate=True,
        )
    except (UnicodeEncodeError, binascii.Error, ValueError):
        raise ValueError(
            "credential issuer key must be canonical base64url"
        ) from None
    canonical = base64.urlsafe_b64encode(decoded).rstrip(b"=")
    if len(decoded) != 32 or canonical != encoded or len(encoded) != 43:
        raise ValueError("credential issuer key must be canonical base64url")
    return sha256_text(credential_issuer_key)


def load_admin_credential_issuer_key_for_settings(settings: Any) -> str:
    """Load the independent issuer authority without exposing its file path."""

    configured_path = getattr(settings, "admin_credential_issuer_key_file", None)
    if configured_path is None:
        raise AdminCredentialIssuerUnavailable()
    environment = getattr(settings, "walksafe_environment", "development")
    deployment = environment in DEPLOYMENT_ENVIRONMENTS
    expected_service_gid = (
        getattr(settings, "admin_credential_issuer_expected_service_gid", None)
        if deployment
        else None
    )
    if deployment and expected_service_gid is None:
        expected_service_gid = os.getegid()
    try:
        return load_admin_credential_issuer_key(
            Path(configured_path),
            require_root_authority=deployment,
            expected_service_gid=expected_service_gid,
        )
    except AdminCredentialIssuerKeyError:
        raise AdminCredentialIssuerUnavailable() from None


def _admin_recovery_advisory_key(admin_id: str) -> int:
    digest = sha256_text(f"recovery-state\0{admin_id}")
    return int.from_bytes(
        bytes.fromhex(digest[:16]),
        byteorder="big",
        signed=True,
    )


def _lock_admin_recovery_fence(db: Session, admin_id: str) -> None:
    bind = db.get_bind()
    if bind is not None and bind.dialect.name == "postgresql":
        db.execute(
            text("SELECT pg_advisory_xact_lock(:key)"),
            {"key": _admin_recovery_advisory_key(admin_id)},
        )


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
) -> str:
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
    return canonical_secret


def _is_postgresql_session(db: Session) -> bool:
    get_bind = getattr(db, "get_bind", None)
    if not callable(get_bind):
        return False
    bind = get_bind()
    return bind is not None and bind.dialect.name == "postgresql"


def _resolve_admin_credential_issuer_key(
    db: Session,
    credential_issuer_key: str | None,
) -> str | None:
    if not _is_postgresql_session(db):
        return credential_issuer_key
    if credential_issuer_key is None:
        from backend.app.config import get_settings

        try:
            return load_admin_credential_issuer_key_for_settings(get_settings())
        except AdminCredentialIssuerUnavailable:
            db.rollback()
            raise
    try:
        credential_issuer_key_sha256(credential_issuer_key)
    except ValueError:
        db.rollback()
        raise AdminCredentialIssuerUnavailable() from None
    return credential_issuer_key


def _lock_postgresql_admin_security_control(
    db: Session,
    *,
    admin_id: str,
    credential_issuer_key: str | None = None,
) -> str | None:
    """Lock and validate the singleton control and private capability first."""

    if not _is_postgresql_session(db):
        return None
    resolved_issuer_key = _resolve_admin_credential_issuer_key(
        db,
        credential_issuer_key,
    )
    try:
        locked_admin_id = db.execute(
            text(
                "SELECT public.walksafe_lock_admin_security_control("
                "CAST(:admin_id AS text), "
                "CAST(:credential_issuer_key AS text))"
            ),
            {
                "admin_id": admin_id,
                "credential_issuer_key": resolved_issuer_key,
            },
        ).scalar_one()
    except SQLAlchemyError as exc:
        raise AdminSecurityStoreUnavailable() from exc
    if not isinstance(locked_admin_id, str) or not 1 <= len(locked_admin_id) <= 64:
        raise AdminSecurityStoreUnavailable()
    return locked_admin_id


def bind_admin_credential_issuer_key(
    db: Session,
    *,
    admin_id: str,
    credential_issuer_key: str,
) -> bool:
    """Bind an unbound migrated control from a non-runtime database owner."""

    if not _is_postgresql_session(db):
        raise ValueError("credential issuer binding requires PostgreSQL")
    credential_issuer_key_sha256(credential_issuer_key)
    role = db.execute(
        text(
            "SELECT current_user AS current_role, "
            "session_user AS session_role, "
            "COALESCE((SELECT role.rolsuper FROM pg_catalog.pg_roles AS role "
            "WHERE role.rolname = current_user), false) AS is_superuser, "
            "(current_user = 'walksafe_backend_runtime' OR "
            "pg_catalog.pg_has_role(current_user, "
            "'walksafe_backend_runtime', 'MEMBER')) AS runtime_member"
        )
    ).mappings().one()
    if bool(role["runtime_member"]) and not bool(role["is_superuser"]):
        raise ValueError("runtime database role cannot bind issuer authority")
    return bool(
        db.execute(
            text(
                "SELECT public.walksafe_bind_admin_credential_issuer_key("
                "CAST(:admin_id AS text), "
                "CAST(:credential_issuer_key AS text))"
            ),
            {
                "admin_id": admin_id,
                "credential_issuer_key": credential_issuer_key,
            },
        ).scalar_one()
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


def append_admin_security_denial(
    db: Session,
    *,
    action: str,
    reason: str,
    method: str,
    path: str,
    device_id: str | None = None,
) -> None:
    normalized_method = method.upper()[:16]
    path_digest = sha256_text(path[:2048])
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


def record_admin_security_denial(
    *,
    action: str,
    reason: str,
    method: str,
    path: str,
    device_id: str | None = None,
) -> None:
    from backend.app.database import SessionLocal

    try:
        with SessionLocal.begin() as db:
            append_admin_security_denial(
                db,
                action=action,
                reason=reason,
                method=method,
                path=path,
                device_id=device_id,
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


def _mutation_row_query(db: Session, query):
    return query if _is_postgresql_session(db) else query.with_for_update()


def _mutation_control_query(
    db: Session,
    admin_id: str,
    *,
    credential_issuer_key: str | None = None,
):
    # PostgreSQL mutations are serialized and revalidated by SECURITY DEFINER
    # transition functions. A direct FOR UPDATE would require restoring the
    # runtime role's intentionally revoked table UPDATE privilege.
    if _is_postgresql_session(db):
        locked_admin_id = _lock_postgresql_admin_security_control(
            db,
            admin_id=admin_id,
            credential_issuer_key=credential_issuer_key,
        )
        if locked_admin_id is None or not secrets.compare_digest(
            locked_admin_id,
            admin_id,
        ):
            raise AdminSecurityStoreUnavailable()
        return _control_query(admin_id)
    return _control_query(admin_id, for_update=True)


def _recovery_custody_is_consistent(control: AdminSecurityControl) -> bool:
    if control.recovery_custody_state == RecoveryCustodyState.UNATTESTED.value:
        return (
            control.recovery_custody_attested_at is None
            and control.recovery_custody_reference_sha256 is None
            and control.recovery_custody_material_kind is None
            and control.recovery_custody_storage_location is None
            and control.recovery_custody_separate_backup_confirmed is False
        )
    if control.recovery_custody_state == RecoveryCustodyState.ATTESTED.value:
        return (
            control.recovery_custody_attested_at is not None
            and isinstance(control.recovery_custody_reference_sha256, str)
            and re.fullmatch(
                r"[0-9a-f]{64}", control.recovery_custody_reference_sha256
            )
            is not None
            and control.recovery_custody_material_kind
            in {"RECOVERY_CODE", "SECURITY_KEY"}
            and control.recovery_custody_storage_location == "OFF_PHONE"
            and control.recovery_custody_separate_backup_confirmed is True
        )
    return False


def _state_payload(
    control: AdminSecurityControl,
    observed_at: datetime,
) -> dict[str, Any]:
    if not _recovery_custody_is_consistent(control):
        raise AdminSecurityStoreUnavailable()
    return {
        "security_state": control.security_state,
        "state_version": str(control.state_version),
        "observed_at": observed_at.isoformat(),
        "recovery_custody_state": control.recovery_custody_state,
        "recovery_custody_attested_at": (
            _as_utc(control.recovery_custody_attested_at).isoformat()
            if control.recovery_custody_attested_at is not None
            else None
        ),
    }


def _clear_recovery_custody(control: AdminSecurityControl) -> None:
    control.recovery_custody_state = RecoveryCustodyState.UNATTESTED.value
    control.recovery_custody_attested_at = None
    control.recovery_custody_reference_sha256 = None
    control.recovery_custody_material_kind = None
    control.recovery_custody_storage_location = None
    control.recovery_custody_separate_backup_confirmed = False


def assert_high_risk_operation_allowed(
    db: Session,
    action: str,
    method: str,
    path: str,
    now: datetime | None = None,
    *,
    credential_issuer_key: str | None = None,
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
        if _is_postgresql_session(db):
            controls = db.execute(select(AdminSecurityControl)).scalars().all()
            if len(controls) != 1:
                raise AdminSecurityStoreUnavailable()
            locked_admin_id = _lock_postgresql_admin_security_control(
                db,
                admin_id=controls[0].admin_id,
                credential_issuer_key=credential_issuer_key,
            )
            if locked_admin_id is None or not secrets.compare_digest(
                locked_admin_id,
                controls[0].admin_id,
            ):
                raise AdminSecurityStoreUnavailable()
            db.refresh(controls[0])
        else:
            controls = db.execute(
                select(AdminSecurityControl).with_for_update()
            ).scalars().all()
    except SQLAlchemyError as exc:
        raise AdminSecurityStoreUnavailable() from exc
    if len(controls) != 1:
        raise AdminSecurityStoreUnavailable()
    control = controls[0]
    if not _recovery_custody_is_consistent(control):
        raise AdminSecurityStoreUnavailable()
    if control.security_state != SecurityState.NORMAL.value:
        raise AdminSecurityError(
            "admin_security_state_blocks_operation",
            "This operation is blocked until administrator recovery is complete.",
            status_code=409,
        )
    if control.recovery_custody_state != RecoveryCustodyState.ATTESTED.value:
        raise AdminSecurityError(
            "admin_recovery_custody_required",
            "Off-phone administrator recovery custody must be attested before this operation.",
            status_code=409,
        )
    return control


def _authorize_admin_bearer(
    db: Session,
    raw_token: str,
    *,
    runtime_totp_secret: str | None,
    credential_issuer_key: str | None = None,
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
        postgresql_session = _is_postgresql_session(db)
        session_query = select(AdminSecuritySession).where(
            AdminSecuritySession.token_sha256 == sha256_text(raw_token)
        )
        if not postgresql_session:
            session_query = session_query.with_for_update()
        session = db.execute(session_query).scalar_one_or_none()
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
        if postgresql_session:
            locked_admin_id = _lock_postgresql_admin_security_control(
                db,
                admin_id=session.admin_id,
                credential_issuer_key=credential_issuer_key,
            )
        else:
            locked_admin_id = None
        if locked_admin_id is not None and not secrets.compare_digest(
            locked_admin_id,
            session.admin_id,
        ):
            raise AdminSecurityStoreUnavailable()
        control = db.execute(_control_query(session.admin_id)).scalar_one_or_none()
        if control is None:
            raise AdminSecurityStoreUnavailable()
        if runtime_totp_secret is None:
            raise AdminSecurityError(
                "admin_totp_configuration_mismatch",
                "Administrator authentication configuration is unavailable.",
                status_code=503,
            )
        canonical_runtime_totp_secret = require_runtime_totp_secret_matches(
            control,
            runtime_totp_secret,
        )
        if control.security_state != SecurityState.NORMAL.value:
            raise AdminSecurityError(
                "admin_security_state_blocks_operation",
                "Administrator recovery must complete before this session can be used.",
                status_code=409,
            )
        if _is_postgresql_session(db):
            resolved_issuer_key = _resolve_admin_credential_issuer_key(
                db,
                credential_issuer_key,
            )
            touched = db.execute(
                text(
                    "SELECT public.walksafe_touch_admin_session("
                    "CAST(:admin_id AS text), CAST(:session_id AS uuid), "
                    "CAST(:device_id AS text), "
                    "CAST(:observed_at AS timestamptz), "
                    "CAST(:runtime_totp_secret AS text), "
                    "CAST(:credential_issuer_key AS text))"
                ),
                {
                    "admin_id": session.admin_id,
                    "session_id": session.id,
                    "device_id": session.device_id,
                    "observed_at": observed_at,
                    "runtime_totp_secret": canonical_runtime_totp_secret,
                    "credential_issuer_key": resolved_issuer_key,
                },
            ).scalar_one()
            if touched is not True:
                raise AdminSecurityStoreUnavailable()
        else:
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
    credential_issuer_key: str | None = None,
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
        credential_issuer_key=credential_issuer_key,
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
    credential_issuer_key: str | None = None,
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
        or not isinstance(max_step_up_age_seconds, int)
        or isinstance(max_step_up_age_seconds, bool)
        or max_step_up_age_seconds <= 0
        or max_step_up_age_seconds > 900
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
        credential_issuer_key=credential_issuer_key,
    )
    identity = _authorize_admin_bearer(
        db,
        raw_token,
        runtime_totp_secret=runtime_totp_secret,
        credential_issuer_key=credential_issuer_key,
        device_id=device_id,
        app_kind=app_kind,
        role=role,
        audience=audience,
        now=observed_at,
    )
    verified_after = observed_at - timedelta(seconds=max_step_up_age_seconds)
    if _is_postgresql_session(db):
        resolved_issuer_key = _resolve_admin_credential_issuer_key(
            db,
            credential_issuer_key,
        )
        matched_verified_at = db.execute(
            text(
                "SELECT public.walksafe_consume_admin_reconfirmation("
                "CAST(:admin_id AS text), CAST(:session_id AS uuid), "
                "CAST(:device_id AS text), CAST(:action AS text), "
                "CAST(:method AS text), CAST(:path AS text), "
                "CAST(:nonce_sha256 AS text), "
                "CAST(:verified_after AS timestamptz), "
                "CAST(:observed_at AS timestamptz), "
                "CAST(:runtime_totp_secret AS text), "
                "CAST(:credential_issuer_key AS text))"
            ),
            {
                "admin_id": identity.admin_id,
                "session_id": identity.session_id,
                "device_id": identity.device_id,
                "action": operation.action,
                "method": operation.method,
                "path": operation.path,
                "nonce_sha256": sha256_text(nonce),
                "verified_after": verified_after,
                "observed_at": observed_at,
                "runtime_totp_secret": runtime_totp_secret,
                "credential_issuer_key": resolved_issuer_key,
            },
        ).scalar_one_or_none()
        reconfirmation = None
    else:
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
        matched_verified_at = (
            reconfirmation.verified_at if reconfirmation is not None else None
        )
    if (
        matched_verified_at is None
        or _as_utc(matched_verified_at) < verified_after
        or _as_utc(matched_verified_at) > observed_at
    ):
        raise AdminSecurityError(
            "admin_reconfirmation_required",
            "A matching one-time administrator reconfirmation is required.",
            status_code=403,
        )
    if reconfirmation is not None:
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
    credential_issuer_key: str | None = None,
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
        credential_issuer_key=credential_issuer_key,
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
    credential_issuer_key: str | None = None,
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
                credential_issuer_key=credential_issuer_key,
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
    credential_issuer_key: str | None = None,
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
        credential_issuer_key=credential_issuer_key,
    )
    return _authorize_admin_bearer(
        db,
        raw_token,
        runtime_totp_secret=runtime_totp_secret,
        credential_issuer_key=credential_issuer_key,
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
    runtime_totp_secret: str,
    credential_issuer_key: str | None = None,
    device_id: str | None = None,
    max_step_up_age_seconds: int = DEFAULT_STEP_UP_TTL_SECONDS,
) -> AdminSessionIdentity:
    """Authorize an offline operation with the private TOTP capability."""

    try:
        identity = _authorize_high_risk_bearer(
            db,
            raw_token,
            action,
            now,
            method=method,
            path=path,
            nonce=nonce,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
            device_id=device_id,
            max_step_up_age_seconds=max_step_up_age_seconds,
        )
    except AdminSecurityError as exc:
        if exc.code in {
            "admin_security_state_blocks_operation",
            "admin_recovery_custody_required",
        }:
            try:
                append_admin_security_denial(
                    db,
                    action=action,
                    reason=exc.code,
                    method=method,
                    path=path,
                    device_id=device_id,
                )
                db.commit()
            except SQLAlchemyError as audit_exc:
                db.rollback()
                raise AdminSecurityStoreUnavailable() from audit_exc
        raise
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


def authorize_database_bound_protected_work(
    db: Session,
    raw_token: str,
    action: str,
    *,
    admin_id: str,
    method: str,
    path: str,
    device_id: str,
    runtime_totp_secret: str,
    credential_issuer_key: str | None = None,
    now: datetime | None = None,
) -> AdminSessionIdentity:
    """Hold the recovery fence while revalidating an offline mutation."""

    observed_at = _as_utc(now or utc_now())
    operation = classify_admin_operation(method, path)
    if operation is None or operation.risk != "HIGH" or operation.action != action:
        raise AdminSecurityError(
            "invalid_high_risk_action",
            "The high-risk operation binding is invalid.",
            status_code=403,
        )
    _lock_admin_recovery_fence(db, admin_id)
    assert_high_risk_operation_allowed(
        db,
        action,
        operation.method,
        operation.path,
        observed_at,
        credential_issuer_key=credential_issuer_key,
    )
    identity = _authorize_admin_bearer(
        db,
        raw_token,
        runtime_totp_secret=runtime_totp_secret,
        credential_issuer_key=credential_issuer_key,
        device_id=device_id,
        now=observed_at,
    )
    if not secrets.compare_digest(identity.admin_id, admin_id):
        raise AdminSecurityError(
            "admin_session_invalid",
            "The administrator session is invalid or expired.",
            status_code=401,
        )
    return identity


class AdminSecurityService:
    """Transactional administrator security use cases for the HTTP adapter."""

    def __init__(self, db: Session, settings: Any) -> None:
        self.db = db
        self.settings = settings
        self._cached_credential_issuer_key: str | None = None

    def _credential_issuer_key(self) -> str:
        if not _is_postgresql_session(self.db):
            raise AdminCredentialIssuerUnavailable()
        if self._cached_credential_issuer_key is None:
            try:
                self._cached_credential_issuer_key = (
                    load_admin_credential_issuer_key_for_settings(self.settings)
                )
            except AdminCredentialIssuerUnavailable:
                # Some callers have already locked or staged ORM state before
                # reaching the PostgreSQL mint RPC. Never let a caught key-file
                # failure leave that partial transaction committable.
                self.db.rollback()
                raise
        return self._cached_credential_issuer_key

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

        _lock_admin_recovery_fence(self.db, admin_id)
        if _is_postgresql_session(self.db):
            locked_admin_id = _lock_postgresql_admin_security_control(
                self.db,
                admin_id=admin_id,
                credential_issuer_key=self._credential_issuer_key(),
            )
            if locked_admin_id is None or not secrets.compare_digest(
                locked_admin_id,
                admin_id,
            ):
                raise AdminSecurityStoreUnavailable()

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
        *,
        authentication_kind: str,
        expected_last_totp_timecode: int | None,
        next_last_totp_timecode: int,
    ) -> tuple[str, AdminSecuritySession]:
        raw_token = secrets.token_urlsafe(SESSION_TOKEN_BYTES)
        expires_at = now + timedelta(
            seconds=int(self.settings.admin_session_ttl_seconds)
        )
        if _is_postgresql_session(self.db):
            session_id = uuid.uuid4()
            self.db.flush()
            self.db.execute(
                text(
                    "SELECT public.walksafe_issue_admin_session("
                    "CAST(:session_id AS uuid), CAST(:admin_id AS text), "
                    "CAST(:device_id AS text), CAST(:device_label AS text), "
                    "CAST(:token_sha256 AS text), CAST(:issued_at AS timestamptz), "
                    "CAST(:expires_at AS timestamptz), "
                    "CAST(:step_up_verified_at AS timestamptz), "
                    "CAST(:last_seen_at AS timestamptz), "
                    "CAST(:authentication_kind AS text), "
                    "CAST(:expected_last_totp_timecode AS bigint), "
                    "CAST(:next_last_totp_timecode AS bigint), "
                    "CAST(:runtime_totp_secret AS text), "
                    "CAST(:credential_issuer_key AS text))"
                ),
                {
                    "session_id": session_id,
                    "admin_id": control.admin_id,
                    "device_id": device_id,
                    "device_label": device_label,
                    "token_sha256": sha256_text(raw_token),
                    "issued_at": now,
                    "expires_at": expires_at,
                    "step_up_verified_at": now,
                    "last_seen_at": now,
                    "authentication_kind": authentication_kind,
                    "expected_last_totp_timecode": (
                        expected_last_totp_timecode
                    ),
                    "next_last_totp_timecode": next_last_totp_timecode,
                    "runtime_totp_secret": self.settings.admin_totp_secret,
                    "credential_issuer_key": self._credential_issuer_key(),
                },
            )
            session = self.db.execute(
                select(AdminSecuritySession).where(
                    AdminSecuritySession.id == session_id
                )
            ).scalar_one()
            return raw_token, session

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
        session = AdminSecuritySession(
            admin_id=control.admin_id,
            device_id=device_id,
            device_label=device_label,
            token_sha256=sha256_text(raw_token),
            issued_at=now,
            expires_at=expires_at,
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
                _mutation_control_query(
                    self.db,
                    self.settings.admin_id,
                    credential_issuer_key=(
                        self._credential_issuer_key()
                        if _is_postgresql_session(self.db)
                        else None
                    ),
                )
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
            if bool(getattr(self.settings, "admin_device_proof_enabled", False)):
                active_device_key = self.db.execute(_mutation_row_query(
                    self.db,
                    select(AdminDeviceKey)
                    .where(
                        AdminDeviceKey.admin_id == control.admin_id,
                        AdminDeviceKey.device_id == device_id,
                        AdminDeviceKey.status == "ACTIVE",
                        AdminDeviceKey.revoked_at.is_(None),
                    ),
                )).scalar_one_or_none()
                if active_device_key is None:
                    self._record_attempt(
                        "login", principal_digest, source_digest, False, observed_at
                    )
                    self._deny_and_commit(
                        code="admin_device_key_not_active",
                        message="The administrator device key is not active.",
                        status_code=403,
                        action="session.login",
                        admin_id=control.admin_id,
                        device_id=device_id,
                        details={"reason": "device_key_not_active"},
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
            previous_totp_timecode = control.last_totp_timecode
            if not _is_postgresql_session(self.db):
                control.last_totp_timecode = timecode
            raw_token, session = self._issue_session(
                control,
                device_id,
                device_label,
                observed_at,
                authentication_kind="LOGIN",
                expected_last_totp_timecode=previous_totp_timecode,
                next_last_totp_timecode=timecode,
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
    ) -> dict[str, Any]:
        observed_at = _as_utc(now or utc_now())
        try:
            control = self.db.execute(_control_query(identity.admin_id)).scalar_one_or_none()
            if control is None:
                raise AdminSecurityStoreUnavailable()
            return _state_payload(control, observed_at)
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
                .order_by(
                    case(
                        (AdminSecuritySession.id == identity.session_id, 0),
                        else_=1,
                    ),
                    AdminSecuritySession.issued_at.desc(),
                    AdminSecuritySession.id,
                )
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

    def list_active_devices(
        self,
        identity: AdminSessionIdentity,
    ) -> list[dict[str, Any]]:
        """List each active trusted device once in a stable order."""

        try:
            device_ids = self.db.execute(
                select(AdminDeviceKey.device_id)
                .where(
                    AdminDeviceKey.admin_id == identity.admin_id,
                    AdminDeviceKey.status == "ACTIVE",
                    AdminDeviceKey.revoked_at.is_(None),
                )
                .group_by(AdminDeviceKey.device_id)
                .order_by(
                    case(
                        (AdminDeviceKey.device_id == identity.device_id, 0),
                        else_=1,
                    ),
                    AdminDeviceKey.device_id,
                )
                .limit(100)
            ).scalars().all()
            return [
                {
                    "device_id": device_id,
                    "current": device_id == identity.device_id,
                }
                for device_id in device_ids
            ]
        except SQLAlchemyError as exc:
            self._rollback_store_error(exc)

    def revoke_session(
        self,
        identity: AdminSessionIdentity,
        session_id: uuid.UUID,
        *,
        now: datetime | None = None,
    ) -> dict[str, Any]:
        observed_at = _as_utc(now or utc_now())
        try:
            control = self.db.execute(
                _mutation_control_query(
                    self.db,
                    identity.admin_id,
                    credential_issuer_key=(
                        self._credential_issuer_key()
                        if _is_postgresql_session(self.db)
                        else None
                    ),
                )
            ).scalar_one_or_none()
            if control is None:
                raise AdminSecurityStoreUnavailable()
            if control.security_state != SecurityState.NORMAL.value:
                raise AdminSecurityError(
                    "admin_security_state_blocks_operation",
                    "Administrator recovery must complete before revoking a session.",
                    status_code=409,
                )
            if _is_postgresql_session(self.db):
                revoke_result = self.db.execute(
                    text(
                        "SELECT * FROM public.walksafe_revoke_admin_session("
                        "CAST(:admin_id AS text), "
                        "CAST(:current_session_id AS uuid), "
                        "CAST(:current_device_id AS text), "
                        "CAST(:target_session_id AS uuid), "
                        "CAST(:observed_at AS timestamptz), "
                        "CAST(:runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": identity.admin_id,
                        "current_session_id": identity.session_id,
                        "current_device_id": identity.device_id,
                        "target_session_id": session_id,
                        "observed_at": observed_at,
                        "runtime_totp_secret": self.settings.admin_totp_secret,
                        "credential_issuer_key": self._credential_issuer_key(),
                    },
                ).one()
                if revoke_result.session_found is not True:
                    raise AdminSecurityError(
                        "admin_session_not_found",
                        "The administrator session was not found.",
                        status_code=404,
                    )
                if bool(revoke_result.revoked):
                    self._audit(
                        "session.revoke",
                        "SUCCESS",
                        admin_id=identity.admin_id,
                        session_id=session_id,
                        device_id=revoke_result.revoked_device_id,
                        details={
                            "current_session": session_id == identity.session_id
                        },
                    )
                self._commit()
                return _state_payload(control, observed_at)
            caller = self.db.execute(_mutation_row_query(
                self.db,
                select(AdminSecuritySession)
                .where(
                    AdminSecuritySession.id == identity.session_id,
                    AdminSecuritySession.admin_id == identity.admin_id,
                ),
            )).scalar_one_or_none()
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
            session_was_revoked = session.revoked_at is None
            if session_was_revoked:
                session.revoked_at = observed_at
                session.revoked_reason = "administrator_revoked"
            if session_was_revoked:
                self._audit(
                    "session.revoke",
                    "SUCCESS",
                    admin_id=identity.admin_id,
                    session_id=session.id,
                    device_id=session.device_id,
                    details={"current_session": session.id == identity.session_id},
                )
            self._commit()
            return _state_payload(control, observed_at)
        except AdminSecurityError:
            self.db.rollback()
            raise
        except SQLAlchemyError as exc:
            self._rollback_store_error(exc)

    def attest_recovery_custody(
        self,
        identity: AdminSessionIdentity,
        *,
        custody_reference: str,
        material_kind: str,
        storage_location: str,
        separate_encrypted_backup_confirmed: bool,
        now: datetime | None = None,
    ) -> dict[str, Any]:
        """Persist only a digest and non-secret off-phone custody metadata."""

        if (
            not valid_recovery_custody_reference(custody_reference)
            or material_kind not in {"RECOVERY_CODE", "SECURITY_KEY"}
            or storage_location != "OFF_PHONE"
            or separate_encrypted_backup_confirmed is not True
        ):
            raise AdminSecurityError(
                "admin_recovery_custody_attestation_invalid",
                "The off-phone recovery custody attestation is invalid.",
                status_code=422,
            )
        observed_at = _as_utc(now or utc_now())
        reference_sha256 = sha256_text(custody_reference)
        try:
            control = self.db.execute(
                _mutation_control_query(
                    self.db,
                    identity.admin_id,
                    credential_issuer_key=(
                        self._credential_issuer_key()
                        if _is_postgresql_session(self.db)
                        else None
                    ),
                )
            ).scalar_one_or_none()
            if control is None or not _recovery_custody_is_consistent(control):
                raise AdminSecurityStoreUnavailable()
            if control.security_state != SecurityState.NORMAL.value:
                raise AdminSecurityError(
                    "admin_security_state_blocks_operation",
                    "Administrator recovery must complete before custody attestation.",
                    status_code=409,
                )
            caller = self.db.execute(_mutation_row_query(
                self.db,
                select(AdminSecuritySession)
                .where(
                    AdminSecuritySession.id == identity.session_id,
                    AdminSecuritySession.admin_id == identity.admin_id,
                ),
            )).scalar_one_or_none()
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
            idempotent = (
                control.recovery_custody_state
                == RecoveryCustodyState.ATTESTED.value
                and secrets.compare_digest(
                    control.recovery_custody_reference_sha256 or "",
                    reference_sha256,
                )
                and control.recovery_custody_material_kind == material_kind
                and control.recovery_custody_storage_location == storage_location
                and control.recovery_custody_separate_backup_confirmed is True
            )
            if not idempotent:
                replaced = (
                    control.recovery_custody_state
                    == RecoveryCustodyState.ATTESTED.value
                )
                if _is_postgresql_session(self.db):
                    self.db.execute(
                        text(
                            "SELECT public.walksafe_attest_recovery_custody("
                            "CAST(:admin_id AS text), "
                            "CAST(:attested_at AS timestamptz), "
                            "CAST(:reference_sha256 AS text), "
                            "CAST(:material_kind AS text), "
                            "CAST(:storage_location AS text), "
                            "CAST(:separate_backup_confirmed AS boolean), "
                            "CAST(:runtime_totp_secret AS text), "
                            "CAST(:credential_issuer_key AS text))"
                        ),
                        {
                            "admin_id": identity.admin_id,
                            "attested_at": observed_at,
                            "reference_sha256": reference_sha256,
                            "material_kind": material_kind,
                            "storage_location": storage_location,
                            "separate_backup_confirmed": True,
                            "runtime_totp_secret": (
                                self.settings.admin_totp_secret
                            ),
                            "credential_issuer_key": (
                                self._credential_issuer_key()
                            ),
                        },
                    )
                    self.db.refresh(control)
                else:
                    control.recovery_custody_state = (
                        RecoveryCustodyState.ATTESTED.value
                    )
                    control.recovery_custody_attested_at = observed_at
                    control.recovery_custody_reference_sha256 = reference_sha256
                    control.recovery_custody_material_kind = material_kind
                    control.recovery_custody_storage_location = storage_location
                    control.recovery_custody_separate_backup_confirmed = True
                    control.state_version += 1
            else:
                replaced = False
            self._audit(
                "recovery.custody.attest",
                "SUCCESS",
                admin_id=identity.admin_id,
                session_id=identity.session_id,
                device_id=identity.device_id,
                details={
                    "idempotent": idempotent,
                    "material_kind": material_kind,
                    "replaced_attestation": replaced,
                    "separate_backup_confirmed": True,
                    "storage_location": storage_location,
                },
            )
            self._commit()
            return _state_payload(control, observed_at)
        except AdminSecurityError:
            self.db.rollback()
            raise
        except SQLAlchemyError as exc:
            self._rollback_store_error(exc)

    def report_lost_device(
        self,
        identity: AdminSessionIdentity,
        target_device_id: str,
        *,
        now: datetime | None = None,
    ) -> dict[str, Any]:
        """Atomically revoke one lost device key set and its related sessions."""

        path = f"/admin/security/devices/{target_device_id}/report-lost"
        operation = classify_admin_operation("POST", path)
        if operation is None or operation.action != "device.report_lost":
            raise AdminSecurityError(
                "admin_device_id_invalid",
                "The administrator device identifier is invalid.",
                status_code=422,
            )
        observed_at = _as_utc(now or utc_now())
        try:
            control = self.db.execute(
                _mutation_control_query(
                    self.db,
                    identity.admin_id,
                    credential_issuer_key=(
                        self._credential_issuer_key()
                        if _is_postgresql_session(self.db)
                        else None
                    ),
                )
            ).scalar_one_or_none()
            if control is None or not _recovery_custody_is_consistent(control):
                raise AdminSecurityStoreUnavailable()
            if control.security_state != SecurityState.NORMAL.value:
                raise AdminSecurityError(
                    "admin_security_state_blocks_operation",
                    "Administrator recovery must complete before reporting a lost device.",
                    status_code=409,
                )
            caller = self.db.execute(_mutation_row_query(
                self.db,
                select(AdminSecuritySession)
                .where(
                    AdminSecuritySession.id == identity.session_id,
                    AdminSecuritySession.admin_id == identity.admin_id,
                ),
            )).scalar_one_or_none()
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
            if secrets.compare_digest(target_device_id, identity.device_id):
                self._deny_and_commit(
                    code="admin_current_device_cannot_report_lost",
                    message="Report a different administrator device as lost.",
                    status_code=422,
                    action="device.report_lost",
                    admin_id=identity.admin_id,
                    session_id=identity.session_id,
                    device_id=target_device_id,
                    details={"reason": "current_device_targeted"},
                )
            device_keys = self.db.execute(_mutation_row_query(
                self.db,
                select(AdminDeviceKey)
                .where(
                    AdminDeviceKey.admin_id == identity.admin_id,
                    AdminDeviceKey.device_id == target_device_id,
                ),
            )).scalars().all()
            sessions = self.db.execute(_mutation_row_query(
                self.db,
                select(AdminSecuritySession)
                .where(
                    AdminSecuritySession.admin_id == identity.admin_id,
                    AdminSecuritySession.device_id == target_device_id,
                ),
            )).scalars().all()
            if not device_keys and not sessions:
                self._deny_and_commit(
                    code="admin_device_not_found",
                    message="The administrator device was not found.",
                    status_code=404,
                    action="device.report_lost",
                    admin_id=identity.admin_id,
                    session_id=identity.session_id,
                    device_id=target_device_id,
                    details={"reason": "device_not_found"},
                )
            if _is_postgresql_session(self.db):
                result = self.db.execute(
                    text(
                        "SELECT * FROM "
                        "public.walksafe_report_admin_lost_device("
                        "CAST(:admin_id AS text), "
                        "CAST(:current_session_id AS uuid), "
                        "CAST(:current_device_id AS text), "
                        "CAST(:target_device_id AS text), "
                        "CAST(:observed_at AS timestamptz), "
                        "CAST(:runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": identity.admin_id,
                        "current_session_id": identity.session_id,
                        "current_device_id": identity.device_id,
                        "target_device_id": target_device_id,
                        "observed_at": observed_at,
                        "runtime_totp_secret": self.settings.admin_totp_secret,
                        "credential_issuer_key": self._credential_issuer_key(),
                    },
                ).one()
                revoked_key_count = int(result.revoked_device_key_count)
                revoked_session_count = int(result.revoked_session_count)
                self.db.refresh(control)
                self._audit(
                    "device.report_lost",
                    "SUCCESS",
                    admin_id=identity.admin_id,
                    session_id=identity.session_id,
                    device_id=target_device_id,
                    details={
                        "idempotent": not (
                            revoked_key_count or revoked_session_count
                        ),
                        "revoked_device_key_count": revoked_key_count,
                        "revoked_session_count": revoked_session_count,
                    },
                )
                self._commit()
                return _state_payload(control, observed_at)
            revoked_key_count = 0
            for device_key in device_keys:
                if device_key.status == "ACTIVE" and device_key.revoked_at is None:
                    device_key.status = "REVOKED"
                    device_key.revoked_at = observed_at
                    revoked_key_count += 1
            revoked_session_count = 0
            for session in sessions:
                if session.revoked_at is None:
                    session.revoked_at = observed_at
                    session.revoked_reason = "device_reported_lost"
                    revoked_session_count += 1
            if revoked_key_count or revoked_session_count:
                control.state_version += 1
            self._audit(
                "device.report_lost",
                "SUCCESS",
                admin_id=identity.admin_id,
                session_id=identity.session_id,
                device_id=target_device_id,
                details={
                    "idempotent": not (revoked_key_count or revoked_session_count),
                    "revoked_device_key_count": revoked_key_count,
                    "revoked_session_count": revoked_session_count,
                },
            )
            self._commit()
            return _state_payload(control, observed_at)
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
                _mutation_control_query(
                    self.db,
                    identity.admin_id,
                    credential_issuer_key=(
                        self._credential_issuer_key()
                        if _is_postgresql_session(self.db)
                        else None
                    ),
                )
            ).scalar_one_or_none()
            session = self.db.execute(_mutation_row_query(
                self.db,
                select(AdminSecuritySession)
                .where(AdminSecuritySession.id == identity.session_id)
            )).scalar_one_or_none()
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
            previous_totp_timecode = control.last_totp_timecode
            postgresql_session = _is_postgresql_session(self.db)
            if not postgresql_session:
                control.last_totp_timecode = timecode
                session.step_up_verified_at = None
                session.last_seen_at = observed_at
            until = observed_at + timedelta(
                seconds=int(self.settings.admin_step_up_ttl_seconds)
            )
            if postgresql_session:
                self.db.execute(
                    text(
                        "SELECT public.walksafe_issue_admin_reconfirmation("
                        "CAST(:reconfirmation_id AS uuid), "
                        "CAST(:admin_id AS text), CAST(:session_id AS uuid), "
                        "CAST(:device_id AS text), CAST(:action AS text), "
                        "CAST(:method AS text), CAST(:path AS text), "
                        "CAST(:nonce_sha256 AS text), "
                        "CAST(:verified_at AS timestamptz), "
                        "CAST(:expires_at AS timestamptz), "
                        "CAST(:expected_last_totp_timecode AS bigint), "
                        "CAST(:next_last_totp_timecode AS bigint), "
                        "CAST(:runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "reconfirmation_id": uuid.uuid4(),
                        "admin_id": identity.admin_id,
                        "session_id": identity.session_id,
                        "device_id": identity.device_id,
                        "action": operation.action,
                        "method": operation.method,
                        "path": operation.path,
                        "nonce_sha256": sha256_text(nonce),
                        "verified_at": observed_at,
                        "expires_at": until,
                        "expected_last_totp_timecode": (
                            previous_totp_timecode
                        ),
                        "next_last_totp_timecode": timecode,
                        "runtime_totp_secret": self.settings.admin_totp_secret,
                        "credential_issuer_key": self._credential_issuer_key(),
                    },
                )
            else:
                pending_reconfirmations = self.db.execute(
                    select(AdminSecurityReconfirmation)
                    .where(
                        AdminSecurityReconfirmation.session_id
                        == identity.session_id,
                        AdminSecurityReconfirmation.consumed_at.is_(None),
                    )
                    .with_for_update()
                ).scalars().all()
                for pending in pending_reconfirmations:
                    pending.consumed_at = observed_at
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
                _mutation_control_query(
                    self.db,
                    self.settings.admin_id,
                    credential_issuer_key=(
                        self._credential_issuer_key()
                        if _is_postgresql_session(self.db)
                        else None
                    ),
                )
            ).scalar_one_or_none()
            postgresql_session = _is_postgresql_session(self.db)
            code_record = None
            active = None
            if postgresql_session:
                recovery_inspection = self.db.execute(
                    text(
                        "SELECT * FROM "
                        "public.walksafe_inspect_admin_recovery_start("
                        "CAST(:admin_id AS text), "
                        "CAST(:recovery_code_sha256 AS text), "
                        "CAST(:observed_at AS timestamptz), "
                        "CAST(:runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": self.settings.admin_id,
                        "recovery_code_sha256": code_digest,
                        "observed_at": observed_at,
                        "runtime_totp_secret": self.settings.admin_totp_secret,
                        "credential_issuer_key": self._credential_issuer_key(),
                    },
                ).one()
                recovery_code_id = recovery_inspection.recovery_code_id
                active_transaction_id = (
                    recovery_inspection.active_transaction_id
                )
                active_recovery_code_id = (
                    recovery_inspection.active_recovery_code_id
                )
                active_device_id = recovery_inspection.active_device_id
                active_expires_at = recovery_inspection.active_expires_at
            else:
                code_record = self.db.execute(
                    select(AdminSecurityRecoveryCode)
                    .where(
                        AdminSecurityRecoveryCode.admin_id
                        == self.settings.admin_id,
                        AdminSecurityRecoveryCode.code_sha256 == code_digest,
                        AdminSecurityRecoveryCode.used_at.is_(None),
                    )
                    .with_for_update()
                ).scalar_one_or_none()
                active = self.db.execute(
                    select(AdminSecurityRecoveryTransaction)
                    .where(
                        AdminSecurityRecoveryTransaction.admin_id
                        == self.settings.admin_id,
                        AdminSecurityRecoveryTransaction.completed_at.is_(None),
                        AdminSecurityRecoveryTransaction.expires_at > observed_at,
                    )
                    .with_for_update()
                ).scalars().first()
                recovery_code_id = code_record.id if code_record is not None else None
                active_transaction_id = active.id if active is not None else None
                active_recovery_code_id = (
                    active.recovery_code_id if active is not None else None
                )
                active_device_id = active.device_id if active is not None else None
                active_expires_at = active.expires_at if active is not None else None
            if (
                control is None
                or recovery_code_id is None
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
            preserve_recovery_device_key = bool(
                getattr(self.settings, "admin_device_proof_enabled", False)
            )
            if active_transaction_id is not None:
                if (
                    active_recovery_code_id == recovery_code_id
                    and active_device_id is not None
                    and secrets.compare_digest(active_device_id, device_id)
                ):
                    if preserve_recovery_device_key:
                        resumed_device_keys = self.db.execute(_mutation_row_query(
                            self.db,
                            select(AdminDeviceKey)
                            .where(
                                AdminDeviceKey.admin_id == control.admin_id,
                                AdminDeviceKey.device_id == device_id,
                                AdminDeviceKey.status == "ACTIVE",
                                AdminDeviceKey.revoked_at.is_(None),
                            ),
                        )).scalars().all()
                        if len(resumed_device_keys) != 1:
                            self._record_attempt(
                                "recovery_start",
                                principal_digest,
                                source_digest,
                                False,
                                observed_at,
                            )
                            self._deny_and_commit(
                                code="admin_recovery_device_key_required",
                                message=(
                                    "Exactly one active trusted device key is required "
                                    "for the recovery device."
                                ),
                                status_code=403,
                                action="recovery.start",
                                admin_id=control.admin_id,
                                device_id=device_id,
                                details={
                                    "reason": (
                                        "recovery_device_key_missing_or_ambiguous"
                                    )
                                },
                            )
                    raw_token = secrets.token_urlsafe(RECOVERY_TOKEN_BYTES)
                    resumed_token_sha256 = sha256_text(raw_token)
                    if postgresql_session:
                        self.db.execute(
                            text(
                                "SELECT "
                                "public.walksafe_resume_admin_recovery_transaction("
                                "CAST(:transaction_id AS uuid), "
                                "CAST(:recovery_token_sha256 AS text), "
                                "CAST(:observed_at AS timestamptz), "
                                "CAST(:runtime_totp_secret AS text), "
                                "CAST(:credential_issuer_key AS text))"
                            ),
                            {
                                "transaction_id": active_transaction_id,
                                "recovery_token_sha256": resumed_token_sha256,
                                "observed_at": observed_at,
                                "runtime_totp_secret": (
                                    self.settings.admin_totp_secret
                                ),
                                "credential_issuer_key": (
                                    self._credential_issuer_key()
                                ),
                            },
                        )
                        self.db.execute(
                            text(
                                "SELECT public.walksafe_reset_recovery_custody("
                                "CAST(:admin_id AS text), "
                                "CAST(:recovery_device_key_preserved AS boolean), "
                                "CAST(:recovery_token_sha256 AS text), "
                                "CAST(:recovery_expires_at AS timestamptz), "
                                "true, "
                                "CAST(:runtime_totp_secret AS text), "
                                "CAST(:credential_issuer_key AS text))"
                            ),
                            {
                                "admin_id": control.admin_id,
                                "recovery_device_key_preserved": (
                                    preserve_recovery_device_key
                                ),
                                "recovery_token_sha256": resumed_token_sha256,
                                "recovery_expires_at": active_expires_at,
                                "runtime_totp_secret": (
                                    self.settings.admin_totp_secret
                                ),
                                "credential_issuer_key": (
                                    self._credential_issuer_key()
                                ),
                            },
                        )
                    else:
                        assert active is not None
                        active.recovery_token_sha256 = resumed_token_sha256
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
            active_device_keys = self.db.execute(_mutation_row_query(
                self.db,
                select(AdminDeviceKey)
                .where(
                    AdminDeviceKey.admin_id == control.admin_id,
                    AdminDeviceKey.status == "ACTIVE",
                    AdminDeviceKey.revoked_at.is_(None),
                ),
            )).scalars().all()
            recovery_device_keys = [
                key
                for key in active_device_keys
                if secrets.compare_digest(key.device_id, device_id)
            ]
            if preserve_recovery_device_key and len(recovery_device_keys) != 1:
                self._record_attempt(
                    "recovery_start",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                self._deny_and_commit(
                    code="admin_recovery_device_key_required",
                    message=(
                        "Exactly one active trusted device key is required for "
                        "the recovery device."
                    ),
                    status_code=403,
                    action="recovery.start",
                    admin_id=control.admin_id,
                    device_id=device_id,
                    details={"reason": "recovery_device_key_missing_or_ambiguous"},
                )
            revoked_device_key_count = sum(
                1
                for device_key in active_device_keys
                if not (
                    preserve_recovery_device_key
                    and secrets.compare_digest(device_key.device_id, device_id)
                )
            )
            if not postgresql_session:
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
                for device_key in active_device_keys:
                    if preserve_recovery_device_key and secrets.compare_digest(
                        device_key.device_id, device_id
                    ):
                        continue
                    device_key.status = "REVOKED"
                    device_key.revoked_at = observed_at
            raw_token = secrets.token_urlsafe(RECOVERY_TOKEN_BYTES)
            transaction_id = uuid.uuid4()
            transaction_expires_at = observed_at + timedelta(
                seconds=int(self.settings.admin_recovery_ttl_seconds)
            )
            transaction = AdminSecurityRecoveryTransaction(
                id=transaction_id,
                admin_id=control.admin_id,
                recovery_code_id=recovery_code_id,
                recovery_token_sha256=sha256_text(raw_token),
                previous_totp_secret_fingerprint=control.totp_secret_fingerprint,
                device_id=device_id,
                device_label=device_label,
                started_at=observed_at,
                expires_at=transaction_expires_at,
            )
            if postgresql_session:
                self.db.execute(
                    text(
                        "SELECT "
                        "public.walksafe_issue_admin_recovery_transaction("
                        "CAST(:transaction_id AS uuid), "
                        "CAST(:admin_id AS text), "
                        "CAST(:recovery_code_id AS uuid), "
                        "CAST(:recovery_token_sha256 AS text), "
                        "CAST(:previous_totp_secret_fingerprint AS text), "
                        "CAST(:device_id AS text), CAST(:device_label AS text), "
                        "CAST(:started_at AS timestamptz), "
                        "CAST(:expires_at AS timestamptz), "
                        "CAST(:runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "transaction_id": transaction_id,
                        "admin_id": control.admin_id,
                        "recovery_code_id": recovery_code_id,
                        "recovery_token_sha256": transaction.recovery_token_sha256,
                        "previous_totp_secret_fingerprint": (
                            transaction.previous_totp_secret_fingerprint
                        ),
                        "device_id": device_id,
                        "device_label": device_label,
                        "started_at": observed_at,
                        "expires_at": transaction_expires_at,
                        "runtime_totp_secret": self.settings.admin_totp_secret,
                        "credential_issuer_key": self._credential_issuer_key(),
                    },
                )
            else:
                self.db.add(transaction)
            previous_custody_state = control.recovery_custody_state
            custody_changed = (
                control.recovery_custody_state
                != RecoveryCustodyState.UNATTESTED.value
                or control.recovery_custody_attested_at is not None
                or control.recovery_custody_reference_sha256 is not None
                or control.recovery_custody_material_kind is not None
                or control.recovery_custody_storage_location is not None
                or control.recovery_custody_separate_backup_confirmed is not False
            )
            if postgresql_session:
                self.db.execute(
                    text(
                        "SELECT public.walksafe_reset_recovery_custody("
                        "CAST(:admin_id AS text), "
                        "CAST(:recovery_device_key_preserved AS boolean), "
                        "CAST(:recovery_token_sha256 AS text), "
                        "CAST(:recovery_expires_at AS timestamptz), "
                        "false, "
                        "CAST(:runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": control.admin_id,
                        "recovery_device_key_preserved": (
                            preserve_recovery_device_key
                        ),
                        "recovery_token_sha256": (
                            transaction.recovery_token_sha256
                        ),
                        "recovery_expires_at": transaction.expires_at,
                        "runtime_totp_secret": self.settings.admin_totp_secret,
                        "credential_issuer_key": self._credential_issuer_key(),
                    },
                )
                self.db.refresh(control)
            else:
                if custody_changed:
                    _clear_recovery_custody(control)
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
                details={
                    "all_sessions_revoked": True,
                    "custody_reset": True,
                    "other_device_keys_revoked": True,
                    "previous_custody_state": previous_custody_state,
                    "recovery_device_key_preserved": preserve_recovery_device_key,
                    "revoked_device_key_count": revoked_device_key_count,
                },
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
            self._lock_recovery_state(self.settings.admin_id)
            postgresql_session = _is_postgresql_session(self.db)
            transaction = None
            code_record = None
            if postgresql_session:
                completion_inspection = self.db.execute(
                    text(
                        "SELECT * FROM "
                        "public.walksafe_inspect_admin_recovery_completion("
                        "CAST(:admin_id AS text), "
                        "CAST(:recovery_token AS text), "
                        "CAST(:observed_at AS timestamptz), "
                        "CAST(:next_runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": self.settings.admin_id,
                        "recovery_token": recovery_token,
                        "observed_at": observed_at,
                        "next_runtime_totp_secret": (
                            self.settings.admin_totp_secret
                        ),
                        "credential_issuer_key": self._credential_issuer_key(),
                    },
                ).one_or_none()
                transaction_id = (
                    completion_inspection.transaction_id
                    if completion_inspection is not None
                    else None
                )
                transaction_admin_id = self.settings.admin_id
                transaction_device_id = (
                    completion_inspection.transaction_device_id
                    if completion_inspection is not None
                    else None
                )
                transaction_expires_at = (
                    completion_inspection.transaction_expires_at
                    if completion_inspection is not None
                    else None
                )
                transaction_completed_at = (
                    completion_inspection.transaction_completed_at
                    if completion_inspection is not None
                    else None
                )
                previous_totp_secret_fingerprint = (
                    completion_inspection.previous_totp_secret_fingerprint
                    if completion_inspection is not None
                    else None
                )
                recovery_code_exists = bool(
                    completion_inspection is not None
                    and completion_inspection.recovery_code_exists
                )
                recovery_code_used_at = (
                    completion_inspection.recovery_code_used_at
                    if completion_inspection is not None
                    else None
                )
                newer_active_transaction_exists = bool(
                    completion_inspection is not None
                    and completion_inspection.newer_active_transaction_exists
                )
                replacement_totp_secret_is_new = bool(
                    completion_inspection is not None
                    and completion_inspection.replacement_totp_secret_is_new
                )
            else:
                transaction = self.db.execute(
                    select(AdminSecurityRecoveryTransaction)
                    .where(
                        AdminSecurityRecoveryTransaction.recovery_token_sha256
                        == sha256_text(recovery_token)
                    )
                    .with_for_update()
                ).scalar_one_or_none()
                transaction_id = transaction.id if transaction is not None else None
                transaction_admin_id = (
                    transaction.admin_id
                    if transaction is not None
                    else self.settings.admin_id
                )
                transaction_device_id = (
                    transaction.device_id if transaction is not None else None
                )
                transaction_expires_at = (
                    transaction.expires_at if transaction is not None else None
                )
                transaction_completed_at = (
                    transaction.completed_at if transaction is not None else None
                )
                previous_totp_secret_fingerprint = (
                    transaction.previous_totp_secret_fingerprint
                    if transaction is not None
                    else None
                )
                replacement_totp_secret_is_new = True
                newer_active_transaction_exists = False
            if transaction_id is None or transaction_completed_at is not None:
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
                _mutation_control_query(
                    self.db,
                    transaction_admin_id,
                    credential_issuer_key=(
                        self._credential_issuer_key()
                        if _is_postgresql_session(self.db)
                        else None
                    ),
                )
            ).scalar_one_or_none()
            if control is None:
                raise AdminSecurityStoreUnavailable()
            if not postgresql_session:
                assert transaction is not None
                code_record = self.db.execute(
                    select(AdminSecurityRecoveryCode)
                    .where(
                        AdminSecurityRecoveryCode.id
                        == transaction.recovery_code_id,
                        AdminSecurityRecoveryCode.admin_id
                        == transaction.admin_id,
                    )
                    .with_for_update()
                ).scalar_one_or_none()
                recovery_code_exists = code_record is not None
                recovery_code_used_at = (
                    code_record.used_at if code_record is not None else None
                )
            if not recovery_code_exists or recovery_code_used_at is not None:
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
            assert transaction_expires_at is not None
            if _as_utc(transaction_expires_at) <= observed_at:
                self._record_attempt(
                    "recovery_complete",
                    principal_digest,
                    source_digest,
                    False,
                    observed_at,
                )
                if not postgresql_session:
                    newer_active = self.db.execute(
                        select(AdminSecurityRecoveryTransaction)
                        .where(
                            AdminSecurityRecoveryTransaction.admin_id
                            == transaction_admin_id,
                            AdminSecurityRecoveryTransaction.id
                            != transaction_id,
                            AdminSecurityRecoveryTransaction.completed_at.is_(None),
                            AdminSecurityRecoveryTransaction.expires_at > observed_at,
                        )
                        .with_for_update()
                    ).scalars().first()
                    newer_active_transaction_exists = newer_active is not None
                next_state = (
                    SecurityState.RECOVERY_IN_PROGRESS.value
                    if newer_active_transaction_exists
                    else SecurityState.RECOVERY_REQUIRED.value
                )
                if postgresql_session:
                    next_state = str(
                        self.db.execute(
                            text(
                                "SELECT public.walksafe_expire_admin_recovery("
                                "CAST(:admin_id AS text), "
                                "CAST(:transaction_id AS uuid), "
                                "CAST(:recovery_token AS text), "
                                "CAST(:observed_at AS timestamptz), "
                                "CAST(:runtime_totp_secret AS text), "
                                "CAST(:credential_issuer_key AS text))"
                            ),
                            {
                                "admin_id": control.admin_id,
                                "transaction_id": transaction_id,
                                "recovery_token": recovery_token,
                                "observed_at": observed_at,
                                "runtime_totp_secret": (
                                    self.settings.admin_totp_secret
                                ),
                                "credential_issuer_key": (
                                    self._credential_issuer_key()
                                ),
                            },
                        ).scalar_one()
                    )
                    self.db.refresh(control)
                elif control.security_state != next_state:
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
                or transaction_device_id is None
                or not secrets.compare_digest(transaction_device_id, device_id)
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
            if bool(getattr(self.settings, "admin_device_proof_enabled", False)):
                replacement_device_key = self.db.execute(_mutation_row_query(
                    self.db,
                    select(AdminDeviceKey)
                    .where(
                        AdminDeviceKey.admin_id == control.admin_id,
                        AdminDeviceKey.device_id == device_id,
                        AdminDeviceKey.status == "ACTIVE",
                        AdminDeviceKey.revoked_at.is_(None),
                    ),
                )).scalar_one_or_none()
                if replacement_device_key is None:
                    self._record_attempt(
                        "recovery_complete",
                        principal_digest,
                        source_digest,
                        False,
                        observed_at,
                    )
                    self._deny_and_commit(
                        code="admin_device_key_not_active",
                        message=(
                            "Register a replacement administrator device key before "
                            "completing recovery."
                        ),
                        status_code=403,
                        action="recovery.complete",
                        admin_id=control.admin_id,
                        device_id=device_id,
                        details={"reason": "replacement_device_key_not_active"},
                    )
            replacement_fingerprint = totp_secret_fingerprint(
                self.settings.admin_totp_secret
            )
            if (
                not replacement_totp_secret_is_new
                or previous_totp_secret_fingerprint is None
                or secrets.compare_digest(
                    replacement_fingerprint,
                    previous_totp_secret_fingerprint,
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
            if postgresql_session:
                self.db.execute(
                    text(
                        "SELECT "
                        "public.walksafe_complete_admin_recovery_transaction("
                        "CAST(:admin_id AS text), "
                        "CAST(:recovery_token AS text), "
                        "CAST(:device_id AS text), "
                        "CAST(:replacement_password_hash AS text), "
                        "CAST(:next_runtime_totp_secret AS text), "
                        "CAST(:next_last_totp_timecode AS bigint), "
                        "CAST(:completed_at AS timestamptz), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": control.admin_id,
                        "recovery_token": recovery_token,
                        "device_id": device_id,
                        "replacement_password_hash": (
                            replacement_password_hash
                        ),
                        "next_runtime_totp_secret": (
                            self.settings.admin_totp_secret
                        ),
                        "next_last_totp_timecode": timecode,
                        "completed_at": observed_at,
                        "credential_issuer_key": self._credential_issuer_key(),
                    },
                )
                self.db.refresh(control)
            else:
                assert code_record is not None
                assert transaction is not None
                control.password_hash = replacement_password_hash
                control.totp_secret_fingerprint = replacement_fingerprint
                control.last_totp_timecode = timecode
                control.security_state = SecurityState.NORMAL.value
                control.state_version += 1
                code_record.used_at = observed_at
                transaction.completed_at = observed_at
            raw_token, session = self._issue_session(
                control,
                device_id,
                device_label,
                observed_at,
                authentication_kind="RECOVERY_COMPLETE",
                expected_last_totp_timecode=timecode,
                next_last_totp_timecode=timecode,
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
            statement = str(getattr(exc, "statement", "") or "")
            sqlstate = getattr(getattr(exc, "orig", None), "sqlstate", None)
            if sqlstate == "42501" and (
                "walksafe_inspect_admin_recovery_completion" in statement
                or "walksafe_complete_admin_recovery_transaction" in statement
            ):
                self.db.rollback()
                raise AdminSecurityError(
                    "admin_recovery_invalid",
                    "The recovery transaction is invalid.",
                    status_code=401,
                ) from exc
            self._rollback_store_error(exc)


def provision_admin_security(
    db: Session,
    *,
    admin_id: str,
    password: str,
    totp_secret: str,
    recovery_codes: list[str],
    credential_issuer_key: str | None = None,
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
    postgresql_session = _is_postgresql_session(db)
    issuer_key_fingerprint: str | None = None
    if postgresql_session:
        if credential_issuer_key is None:
            raise ValueError(
                "credential issuer key is required for PostgreSQL provisioning"
            )
        issuer_key_fingerprint = credential_issuer_key_sha256(
            credential_issuer_key
        )
    try:
        if postgresql_session:
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
            singleton_scope=True,
            password_hash=hash_password(password),
            totp_secret_fingerprint=totp_secret_fingerprint(canonical_totp_secret),
            security_state=SecurityState.NORMAL.value,
            state_version=1,
            recovery_custody_state=RecoveryCustodyState.UNATTESTED.value,
            recovery_custody_separate_backup_confirmed=False,
            created_at=observed_at,
            updated_at=observed_at,
        )
        db.add(control)
        if postgresql_session:
            db.flush()
            db.execute(
                text(
                    "INSERT INTO public."
                    "walksafe_recovery_custody_capabilities "
                    "(admin_id, totp_secret_fingerprint, issuer_key_sha256) "
                    "VALUES (CAST(:admin_id AS text), "
                    "CAST(:totp_secret_fingerprint AS text), "
                    "CAST(:issuer_key_sha256 AS text))"
                ),
                {
                    "admin_id": admin_id,
                    "totp_secret_fingerprint": (
                        control.totp_secret_fingerprint
                    ),
                    "issuer_key_sha256": issuer_key_fingerprint,
                },
            )
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
    "AdminCredentialIssuerUnavailable",
    "AdminSessionIdentity",
    "RecoveryGrant",
    "RecoveryCustodyState",
    "SecurityState",
    "SessionGrant",
    "append_admin_security_denial",
    "assert_high_risk_operation_allowed",
    "auth_rate_limit_principal",
    "bind_admin_credential_issuer_key",
    "authorize_admin_bearer",
    "authorize_database_bound_high_risk_bearer",
    "authorize_database_bound_protected_work",
    "authorize_high_risk_bearer",
    "hash_password",
    "credential_issuer_key_sha256",
    "load_admin_credential_issuer_key_for_settings",
    "provision_admin_security",
    "record_admin_security_failure",
    "recovery_code_sha256",
    "require_runtime_totp_secret_matches",
    "sha256_text",
    "totp_secret_fingerprint",
    "valid_recovery_custody_reference",
    "verify_password",
    "verify_totp_timecode",
]
