"""FP-010 stage 4~8 signup lifecycle.

Email stands in for phone verification until release; see product/decisions.md,
2026-08-30. RQ-FP-010-001 still requires 휴대전화 확인 and is unchanged, so this
service is a development substitute and not release evidence.

Two boundaries are load-bearing:

* The address is never stored. Only its keyed pseudonym is kept, so the table
  cannot re-derive who signed up.
* The opaque handles the app persists must be provider-issued randomness and
  must never derive from the address, a password or a birth date. They are
  generated here from `secrets`, never from request content.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy.orm import Session

from backend.app.models import FirstRunSignup

VERIFICATION_TTL = timedelta(minutes=30)
MAX_VERIFICATION_ATTEMPTS = 5
_VERIFICATION_CODE_DIGITS = 6


class FirstRunSignupError(Exception):
    """Raised with a stable code the router maps to an HTTP status."""

    def __init__(self, code: str, message: str, status_code: int = 400) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


@dataclass(frozen=True)
class SignupSubmission:
    submission_handle: str
    receipt_sha256: str
    verification_code: str


@dataclass(frozen=True)
class StageReceipt:
    receipt_sha256: str
    actor_binding: str | None = None


def _opaque(prefix: str) -> str:
    """A provider-issued 128-bit handle. Never derived from request content."""
    return f"{prefix}_{secrets.token_hex(16)}"


def _receipt() -> str:
    return hashlib.sha256(secrets.token_bytes(32)).hexdigest()


def normalized_email(raw: str) -> str:
    address = raw.strip()
    local, separator, domain = address.rpartition("@")
    if not separator or not local or not domain or "." not in domain:
        raise FirstRunSignupError(
            "first_run_email_invalid", "An email address is required."
        )
    if len(address) > 254 or any(character.isspace() for character in address):
        raise FirstRunSignupError(
            "first_run_email_invalid", "An email address is required."
        )
    # 도메인만 소문자로 정규화한다. local part 의 대소문자는 공급자마다 의미가 달라 건드리지 않는다.
    return f"{local}@{domain.lower()}"


def email_pseudonym(secret: str, address: str) -> str:
    if not secret:
        raise FirstRunSignupError(
            "first_run_signup_unavailable",
            "The privacy HMAC secret is not configured.",
            status_code=503,
        )
    return hmac.new(
        secret.encode("utf-8"),
        f"walksafe.first-run-email\0{address}".encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def _verification_code() -> str:
    upper = 10**_VERIFICATION_CODE_DIGITS
    return str(secrets.randbelow(upper)).zfill(_VERIFICATION_CODE_DIGITS)


def _code_digest(code: str) -> str:
    return hashlib.sha256(f"walksafe.first-run-code\0{code}".encode("utf-8")).hexdigest()


def _load(session: Session, submission_handle: str) -> FirstRunSignup:
    row = (
        session.query(FirstRunSignup)
        .filter(FirstRunSignup.submission_handle == submission_handle)
        .one_or_none()
    )
    if row is None:
        raise FirstRunSignupError(
            "first_run_signup_not_found", "No signup matches that handle.", 404
        )
    return row


def submit_email(session: Session, *, hmac_secret: str, raw_email: str, now: datetime) -> SignupSubmission:
    """Stage 4. Replaces an unverified prior attempt for the same address."""
    address = normalized_email(raw_email)
    pseudonym = email_pseudonym(hmac_secret, address)
    existing = (
        session.query(FirstRunSignup)
        .filter(FirstRunSignup.email_hmac == pseudonym)
        .one_or_none()
    )
    if existing is not None and existing.verified_at is not None:
        raise FirstRunSignupError(
            "first_run_signup_already_verified",
            "That address already completed verification.",
            409,
        )

    code = _verification_code()
    handle = _opaque("onb")
    if existing is None:
        session.add(
            FirstRunSignup(
                id=uuid.uuid4(),
                email_hmac=pseudonym,
                submission_handle=handle,
                verification_code_sha256=_code_digest(code),
                verification_expires_at=now + VERIFICATION_TTL,
                verification_attempts=0,
            )
        )
    else:
        # 재요청은 새 핸들과 새 코드로 갈아끼운다. 이전 코드는 더 이상 통하지 않는다.
        existing.submission_handle = handle
        existing.verification_code_sha256 = _code_digest(code)
        existing.verification_expires_at = now + VERIFICATION_TTL
        existing.verification_attempts = 0
    session.flush()
    return SignupSubmission(
        submission_handle=handle, receipt_sha256=_receipt(), verification_code=code
    )


def verify_email(
    session: Session, *, submission_handle: str, code: str, now: datetime
) -> StageReceipt:
    """Stage 5."""
    row = _load(session, submission_handle)
    if row.verified_at is not None:
        return StageReceipt(receipt_sha256=_receipt())
    if row.verification_expires_at <= now:
        raise FirstRunSignupError(
            "first_run_verification_expired", "The verification code expired.", 410
        )
    if row.verification_attempts >= MAX_VERIFICATION_ATTEMPTS:
        raise FirstRunSignupError(
            "first_run_verification_attempts_exhausted",
            "Too many attempts for this code.",
            429,
        )
    row.verification_attempts += 1
    session.flush()
    if not hmac.compare_digest(row.verification_code_sha256, _code_digest(code.strip())):
        raise FirstRunSignupError(
            "first_run_verification_code_invalid", "That code does not match.", 400
        )
    row.verified_at = now
    session.flush()
    return StageReceipt(receipt_sha256=_receipt())


def activate_account(session: Session, *, submission_handle: str, now: datetime) -> StageReceipt:
    """Stage 7."""
    row = _load(session, submission_handle)
    if row.verified_at is None:
        raise FirstRunSignupError(
            "first_run_verification_required",
            "Verify the address before activation.",
            409,
        )
    if row.activated_at is None:
        row.activated_at = now
        session.flush()
    return StageReceipt(receipt_sha256=_receipt())


def issue_login_binding(session: Session, *, submission_handle: str) -> StageReceipt:
    """Stage 8. The actor binding is issued once and then reused."""
    row = _load(session, submission_handle)
    if row.activated_at is None:
        raise FirstRunSignupError(
            "first_run_activation_required",
            "Activate the account before login.",
            409,
        )
    if row.actor_binding is None:
        row.actor_binding = _opaque("actor")
        session.flush()
    return StageReceipt(receipt_sha256=_receipt(), actor_binding=row.actor_binding)


def utc_now() -> datetime:
    return datetime.now(timezone.utc)
