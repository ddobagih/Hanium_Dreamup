"""Email OTP enrollment, account credentials, and SMTP delivery."""

from __future__ import annotations

import base64
import calendar
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from email.message import EmailMessage
from email.utils import formatdate, make_msgid
import hashlib
import hmac
import json
import re
import secrets
import smtplib
import ssl
from threading import BoundedSemaphore, Lock
from typing import Any, Protocol
import uuid
from zoneinfo import ZoneInfo

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from sqlalchemy import select, text, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from sqlalchemy.orm.attributes import flag_modified

from backend.app.account_schemas import (
    AccountAuthenticateRequestV1,
    AccountAuthenticationResponseV1,
    AccountCreateRequestV1,
    AccountResponseV1,
    EmailOtpEnrollmentRequestV1,
    EmailOtpEnrollmentResponseV1,
    require_canonical_email,
)
from backend.app.models import (
    AccountCryptoKeyBinding,
    AccountEnrollment,
    SignupConsentReceipt,
    UserAccount,
)
from backend.app.services.actor_rate_limit import (
    ActorRateLimitStoreUnavailable,
    InMemoryActorRateLimiter,
    PostgresActorRateLimiter,
)
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    privacy_subject_hmac,
)


SEOUL = ZoneInfo("Asia/Seoul")
_PASSWORD_PREFIX = "account-scrypt-v1"
_SCRYPT_N = 1 << 14
_SCRYPT_R = 8
_SCRYPT_P = 1
_SCRYPT_DKLEN = 32


class AccountServiceError(RuntimeError):
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


class OtpDeliveryError(RuntimeError):
    """Provider details are deliberately excluded from this public exception."""


class OtpSender(Protocol):
    def send(self, *, email: str, code: str, expires_in_seconds: int) -> None: ...


def utc_now() -> datetime:
    return datetime.now(UTC).replace(microsecond=0)


def normalize_email(raw_email: str) -> str:
    """Require the exact canonical ASCII email used by the account contract."""

    normalized = require_canonical_email(raw_email)
    if not isinstance(normalized, str):
        raise ValueError("invalid email address")
    return normalized


def minimum_birth_date_for_age(today: date, years: int) -> date:
    target_year = today.year - years
    if (
        today.month == 2
        and today.day == 28
        and not calendar.isleap(today.year)
        and calendar.isleap(target_year)
    ):
        return date(target_year, 2, 29)
    try:
        return today.replace(year=target_year)
    except ValueError:
        return today.replace(year=target_year, day=28)


def require_minimum_age(
    date_of_birth: date,
    *,
    today: date | None = None,
    minimum_age: int = 14,
) -> None:
    seoul_today = today or datetime.now(SEOUL).date()
    if date_of_birth > minimum_birth_date_for_age(seoul_today, minimum_age):
        raise AccountServiceError(
            "account_enrollment_not_allowed",
            "This enrollment cannot be accepted.",
            status_code=400,
        )


def _b64encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _b64decode(value: str) -> bytes:
    decoded = base64.b64decode(
        value + "=" * (-len(value) % 4),
        altchars=b"-_",
        validate=True,
    )
    if _b64encode(decoded) != value:
        raise ValueError("non-canonical Base64url value")
    return decoded


def hash_account_password(password: str) -> str:
    if not 10 <= len(password) <= 128:
        raise ValueError("password must contain between 10 and 128 characters")
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
            _PASSWORD_PREFIX,
            str(_SCRYPT_N),
            str(_SCRYPT_R),
            str(_SCRYPT_P),
            _b64encode(salt),
            _b64encode(derived),
        )
    )


def verify_account_password(password: str, encoded: str) -> bool:
    try:
        prefix, raw_n, raw_r, raw_p, raw_salt, raw_digest = encoded.split("$")
        n, r, p = int(raw_n), int(raw_r), int(raw_p)
        salt = _b64decode(raw_salt)
        expected = _b64decode(raw_digest)
        if (
            prefix != _PASSWORD_PREFIX
            or (n, r, p) != (_SCRYPT_N, _SCRYPT_R, _SCRYPT_P)
            or len(salt) != 16
            or len(expected) != _SCRYPT_DKLEN
        ):
            return False
        actual = hashlib.scrypt(
            password.encode("utf-8"),
            salt=salt,
            n=n,
            r=r,
            p=p,
            dklen=len(expected),
        )
    except (TypeError, ValueError, OverflowError):
        return False
    return secrets.compare_digest(actual, expected)


class AccountEmailCrypto:
    def __init__(
        self,
        *,
        encryption_key: bytes | None,
        lookup_hmac_key: bytes | None,
        otp_hmac_key: bytes | None,
        key_version: int,
    ) -> None:
        if (
            encryption_key is None
            or lookup_hmac_key is None
            or otp_hmac_key is None
            or any(
                len(key) != 32
                for key in (encryption_key, lookup_hmac_key, otp_hmac_key)
            )
            or len({encryption_key, lookup_hmac_key, otp_hmac_key}) != 3
            or key_version < 1
        ):
            raise AccountServiceError(
                "account_enrollment_unavailable",
                "Account enrollment is temporarily unavailable.",
                status_code=503,
            )
        self._aesgcm = AESGCM(encryption_key)
        self._lookup_key = lookup_hmac_key
        self._otp_key = otp_hmac_key
        self.key_version = key_version
        self.key_fingerprints = (
            hashlib.sha256(
                b"walksafe/account-email-encryption-key-fingerprint/v1\0"
                + encryption_key
            ).hexdigest(),
            hashlib.sha256(
                b"walksafe/account-email-lookup-key-fingerprint/v1\0"
                + lookup_hmac_key
            ).hexdigest(),
            hashlib.sha256(
                b"walksafe/account-otp-key-fingerprint/v1\0" + otp_hmac_key
            ).hexdigest(),
        )

    @staticmethod
    def _aad(record_id: uuid.UUID, key_version: int) -> bytes:
        return (
            b"walksafe/account-email/v1\0"
            + record_id.bytes
            + b"\0"
            + str(key_version).encode("ascii")
        )

    def encrypt(self, record_id: uuid.UUID, email: str) -> tuple[bytes, bytes]:
        nonce = secrets.token_bytes(12)
        ciphertext = self._aesgcm.encrypt(
            nonce,
            email.encode("utf-8"),
            self._aad(record_id, self.key_version),
        )
        return nonce, ciphertext

    def decrypt(
        self,
        record_id: uuid.UUID,
        *,
        nonce: bytes,
        ciphertext: bytes,
        key_version: int,
    ) -> str:
        if key_version != self.key_version:
            raise AccountServiceError(
                "account_enrollment_unavailable",
                "Account enrollment is temporarily unavailable.",
                status_code=503,
            )
        try:
            plaintext = self._aesgcm.decrypt(
                nonce,
                ciphertext,
                self._aad(record_id, key_version),
            )
            return plaintext.decode("utf-8")
        except (InvalidTag, UnicodeDecodeError, ValueError) as exc:
            raise AccountServiceError(
                "account_enrollment_unavailable",
                "Account enrollment is temporarily unavailable.",
                status_code=503,
            ) from exc

    def email_lookup_hmac(self, email: str) -> str:
        return hmac.new(
            self._lookup_key,
            b"walksafe/account-email-lookup/v1\0" + email.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()

    def request_hmac(self, *, email: str, date_of_birth: date) -> str:
        payload = json.dumps(
            {
                "date_of_birth": date_of_birth.isoformat(),
                "email": email,
                "schema_version": "walksafe.account-enrollment-email-otp.v1",
            },
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return hmac.new(
            self._otp_key,
            b"walksafe/account-enrollment-request/v1\0" + payload,
            hashlib.sha256,
        ).hexdigest()

    def otp_hmac(self, *, handle: str, email: str, code: str) -> str:
        return hmac.new(
            self._otp_key,
            b"walksafe/account-enrollment-otp/v1\0"
            + handle.encode("ascii")
            + b"\0"
            + email.encode("utf-8")
            + b"\0"
            + code.encode("ascii"),
            hashlib.sha256,
        ).hexdigest()


def _account_crypto_binding_matches(
    binding: AccountCryptoKeyBinding,
    crypto: AccountEmailCrypto,
) -> bool:
    encryption_fingerprint, lookup_fingerprint, otp_fingerprint = (
        crypto.key_fingerprints
    )
    return (
        binding.key_version == crypto.key_version
        and secrets.compare_digest(
            binding.encryption_key_fingerprint,
            encryption_fingerprint,
        )
        and secrets.compare_digest(
            binding.lookup_hmac_key_fingerprint,
            lookup_fingerprint,
        )
        and secrets.compare_digest(
            binding.otp_hmac_key_fingerprint,
            otp_fingerprint,
        )
    )


def bind_or_verify_account_crypto_keys(
    db: Session,
    crypto: AccountEmailCrypto,
) -> AccountCryptoKeyBinding:
    binding = db.get(AccountCryptoKeyBinding, 1, populate_existing=True)
    if binding is not None:
        if not _account_crypto_binding_matches(binding, crypto):
            raise AccountServiceError(
                "account_crypto_key_mismatch",
                "The account service is temporarily unavailable.",
                status_code=503,
            )
        return binding
    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": "walksafe/account-crypto-key-binding/v1"},
    )
    binding = db.get(AccountCryptoKeyBinding, 1, populate_existing=True)
    if binding is None:
        if any(
            db.scalar(select(model.id).limit(1)) is not None
            for model in (AccountEnrollment, UserAccount)
        ):
            raise AccountServiceError(
                "account_crypto_key_binding_missing",
                "The account service is temporarily unavailable.",
                status_code=503,
            )
        encryption_fingerprint, lookup_fingerprint, otp_fingerprint = (
            crypto.key_fingerprints
        )
        binding = AccountCryptoKeyBinding(
            binding_id=1,
            key_version=crypto.key_version,
            encryption_key_fingerprint=encryption_fingerprint,
            lookup_hmac_key_fingerprint=lookup_fingerprint,
            otp_hmac_key_fingerprint=otp_fingerprint,
        )
        db.add(binding)
        db.flush()
        return binding
    if not _account_crypto_binding_matches(binding, crypto):
        raise AccountServiceError(
            "account_crypto_key_mismatch",
            "The account service is temporarily unavailable.",
            status_code=503,
        )
    return binding


def assert_account_crypto_keys_bound(
    db: Session,
    crypto: AccountEmailCrypto,
) -> AccountCryptoKeyBinding:
    binding = db.get(AccountCryptoKeyBinding, 1, populate_existing=True)
    if binding is None or not _account_crypto_binding_matches(binding, crypto):
        raise AccountServiceError(
            "account_crypto_key_mismatch",
            "The account service is temporarily unavailable.",
            status_code=503,
        )
    return binding


class SmtpOtpSender:
    def __init__(
        self,
        *,
        host: str,
        port: int,
        security: str,
        username: str,
        password: str,
        from_address: str,
        timeout_seconds: float,
    ) -> None:
        if (
            not host
            or any(character in host for character in ("\r", "\n", "\x00"))
            or not 1 <= port <= 65_535
            or security not in {"implicit_tls", "starttls"}
            or not 0 < timeout_seconds <= 30
            or bool(username) != bool(password)
        ):
            raise ValueError("secure SMTP configuration is required")
        self.host = host
        self.port = port
        self.security = security
        self.username = username
        self.password = password
        self.from_address = normalize_email(from_address)
        self.timeout_seconds = timeout_seconds

    def send(self, *, email: str, code: str, expires_in_seconds: int) -> None:
        recipient = normalize_email(email)
        if re.fullmatch(r"[0-9]{6}", code) is None:
            raise ValueError("OTP code must contain six digits")
        message = EmailMessage()
        message["From"] = self.from_address
        message["To"] = recipient
        message["Subject"] = "WalkSafe account verification code"
        message["Date"] = formatdate(usegmt=True)
        message["Message-ID"] = make_msgid(domain=self.from_address.rsplit("@", 1)[1])
        minutes = max(1, (expires_in_seconds + 59) // 60)
        message.set_content(
            "Your WalkSafe verification code is "
            f"{code}. It expires in {minutes} minutes.\n"
            "If you did not request this, you can ignore this message."
        )
        context = ssl.create_default_context()
        try:
            if self.security == "implicit_tls":
                client_factory = smtplib.SMTP_SSL
            else:
                client_factory = smtplib.SMTP
            with client_factory(
                self.host,
                self.port,
                timeout=self.timeout_seconds,
                context=context,
            ) if self.security == "implicit_tls" else client_factory(
                self.host,
                self.port,
                timeout=self.timeout_seconds,
            ) as client:
                client.ehlo()
                if self.security == "starttls":
                    client.starttls(context=context)
                    client.ehlo()
                if self.username:
                    client.login(self.username, self.password)
                client.send_message(message)
        except (OSError, TimeoutError, smtplib.SMTPException, ssl.SSLError) as exc:
            raise OtpDeliveryError("secure SMTP delivery failed") from exc


class UnavailableOtpSender:
    def send(self, *, email: str, code: str, expires_in_seconds: int) -> None:
        del email, code, expires_in_seconds
        raise OtpDeliveryError("secure SMTP delivery is not configured")


def create_otp_sender(settings: Any) -> OtpSender:
    if not getattr(settings, "account_smtp_configured", False):
        return UnavailableOtpSender()
    try:
        return SmtpOtpSender(
            host=settings.account_smtp_host,
            port=settings.account_smtp_port,
            security=settings.account_smtp_security,
            username=settings.account_smtp_username,
            password=settings.account_smtp_password,
            from_address=settings.account_smtp_from,
            timeout_seconds=settings.account_smtp_timeout_seconds,
        )
    except ValueError:
        return UnavailableOtpSender()


class AccountEnrollmentRateLimiter:
    def __init__(self, settings: Any) -> None:
        limiter = (
            PostgresActorRateLimiter
            if settings.actor_rate_limit_store == "postgresql"
            else InMemoryActorRateLimiter
        )
        self._global = limiter(
            {"account_enrollment_global": settings.account_enrollment_global_limit},
            settings.account_enrollment_global_window_seconds,
        )
        self._ip = limiter(
            {"account_enrollment_ip": settings.account_enrollment_ip_limit},
            settings.account_enrollment_ip_window_seconds,
        )
        self._email = limiter(
            {"account_enrollment_email": settings.account_enrollment_email_limit},
            settings.account_enrollment_email_window_seconds,
        )

    @staticmethod
    def _check_many(checks: tuple[tuple[Any, str, str], ...]) -> None:
        try:
            for limiter, identity, group in checks:
                retry_after = limiter.check(identity, group)
                if retry_after is not None:
                    raise AccountServiceError(
                        "account_enrollment_rate_limited",
                        "Too many account enrollment requests.",
                        status_code=429,
                        retry_after=retry_after,
                    )
        except ActorRateLimitStoreUnavailable as exc:
            raise AccountServiceError(
                "account_enrollment_unavailable",
                "Account enrollment is temporarily unavailable.",
                status_code=503,
            ) from exc

    def check_source(self, *, source_ip: str) -> None:
        self._check_many(
            (
                (
                    self._global,
                    "walksafe-account-enrollment",
                    "account_enrollment_global",
                ),
                (self._ip, source_ip or "unknown", "account_enrollment_ip"),
            )
        )

    def check_email(self, *, email_lookup_hmac: str) -> None:
        self._check_many(
            ((self._email, email_lookup_hmac, "account_enrollment_email"),)
        )

    def check(self, *, source_ip: str, email_lookup_hmac: str) -> None:
        self.check_source(source_ip=source_ip)
        self.check_email(email_lookup_hmac=email_lookup_hmac)


class AccountAuthenticationRateLimiter:
    def __init__(self, settings: Any) -> None:
        limiter = (
            PostgresActorRateLimiter
            if settings.actor_rate_limit_store == "postgresql"
            else InMemoryActorRateLimiter
        )
        self._global = limiter(
            {
                "account_authentication_global": (
                    settings.account_authentication_global_limit
                )
            },
            settings.account_authentication_global_window_seconds,
        )
        self._email = limiter(
            {
                "account_authentication_email": (
                    settings.account_authentication_email_limit
                )
            },
            settings.account_authentication_email_window_seconds,
        )

    def check(self, *, email_lookup_hmac: str) -> None:
        try:
            for limiter, identity, group in (
                (
                    self._global,
                    "walksafe-account-authentication",
                    "account_authentication_global",
                ),
                (
                    self._email,
                    email_lookup_hmac,
                    "account_authentication_email",
                ),
            ):
                retry_after = limiter.check(identity, group)
                if retry_after is not None:
                    raise AccountServiceError(
                        "account_authentication_rate_limited",
                        "Too many account authentication requests.",
                        status_code=429,
                        retry_after=retry_after,
                    )
        except ActorRateLimitStoreUnavailable as exc:
            raise AccountServiceError(
                "account_authentication_unavailable",
                "Account authentication is temporarily unavailable.",
                status_code=503,
                retry_after=5,
            ) from exc


class AccountPasswordVerificationGate:
    def __init__(self, limit: int) -> None:
        if not 1 <= limit <= 16:
            raise ValueError("account scrypt in-flight limit must be between 1 and 16")
        self.limit = limit
        self._semaphore = BoundedSemaphore(limit)

    @contextmanager
    def slot(self) -> Iterator[None]:
        if not self._semaphore.acquire(blocking=False):
            raise AccountServiceError(
                "account_authentication_busy",
                "Account authentication is temporarily unavailable.",
                status_code=503,
                retry_after=1,
            )
        try:
            yield
        finally:
            self._semaphore.release()


_PASSWORD_VERIFICATION_GATE_LOCK = Lock()
_PASSWORD_VERIFICATION_GATE: AccountPasswordVerificationGate | None = None


def _process_password_verification_gate(
    limit: int,
) -> AccountPasswordVerificationGate:
    global _PASSWORD_VERIFICATION_GATE
    with _PASSWORD_VERIFICATION_GATE_LOCK:
        if _PASSWORD_VERIFICATION_GATE is None:
            _PASSWORD_VERIFICATION_GATE = AccountPasswordVerificationGate(limit)
        elif _PASSWORD_VERIFICATION_GATE.limit != limit:
            raise ValueError(
                "account scrypt in-flight limit changed within one process"
            )
        return _PASSWORD_VERIFICATION_GATE


@dataclass(frozen=True)
class OtpDelivery:
    enrollment_id: uuid.UUID
    issue_count: int
    email: str
    code: str


@dataclass(frozen=True)
class EnrollmentIssue:
    response: EmailOtpEnrollmentResponseV1
    delivery: OtpDelivery | None


def account_email_crypto_for_settings(settings: Any) -> AccountEmailCrypto:
    return AccountEmailCrypto(
        encryption_key=getattr(settings, "account_email_encryption_key", None),
        lookup_hmac_key=getattr(
            settings,
            "account_email_lookup_hmac_key",
            None,
        ),
        otp_hmac_key=getattr(settings, "account_otp_hmac_key", None),
        key_version=getattr(settings, "account_email_key_version", 0),
    )


class AccountService:
    def __init__(
        self,
        settings: Any,
        *,
        authentication_rate_limiter: (
            AccountAuthenticationRateLimiter | None
        ) = None,
        password_verification_gate: AccountPasswordVerificationGate | None = None,
    ) -> None:
        self.settings = settings
        self._authentication_rate_limiter = (
            authentication_rate_limiter
            if authentication_rate_limiter is not None
            else AccountAuthenticationRateLimiter(settings)
        )
        self._password_verification_gate = (
            password_verification_gate
            if password_verification_gate is not None
            else _process_password_verification_gate(
                settings.account_authentication_scrypt_inflight_limit
            )
        )
        self._dummy_password_hash = hash_account_password(
            secrets.token_urlsafe(24)
        )

    def _crypto(self) -> AccountEmailCrypto:
        return account_email_crypto_for_settings(self.settings)

    def normalized_email_lookup(self, raw_email: str) -> tuple[str, str]:
        normalized = normalize_email(raw_email)
        return normalized, self._crypto().email_lookup_hmac(normalized)

    @staticmethod
    def _response(enrollment: AccountEnrollment) -> EmailOtpEnrollmentResponseV1:
        return EmailOtpEnrollmentResponseV1(
            schema_version="walksafe.account-enrollment-email-otp-response.v1",
            enrollment_handle=enrollment.enrollment_handle,
            expires_at=enrollment.expires_at,
            resend_available_at=enrollment.resend_not_before,
        )

    def issue_email_otp(
        self,
        db: Session,
        payload: EmailOtpEnrollmentRequestV1,
        *,
        now: datetime | None = None,
    ) -> EnrollmentIssue:
        observed_at = (now or utc_now()).astimezone(UTC).replace(microsecond=0)
        require_minimum_age(
            payload.date_of_birth,
            today=observed_at.astimezone(SEOUL).date(),
        )
        try:
            email = normalize_email(payload.email)
        except ValueError as exc:
            raise AccountServiceError(
                "account_request_validation_failed",
                "The account request does not match the required contract.",
                status_code=422,
            ) from exc
        crypto = self._crypto()
        bind_or_verify_account_crypto_keys(db, crypto)
        lookup_hmac = crypto.email_lookup_hmac(email)
        request_hmac = crypto.request_hmac(
            email=email,
            date_of_birth=payload.date_of_birth,
        )
        db.execute(
            text(
                "SELECT pg_advisory_xact_lock("
                "hashtextextended(:lock_key, 0))"
            ),
            {
                "lock_key": (
                    "walksafe-account-enrollment-email-v1:"
                    f"{lookup_hmac}"
                )
            },
        )
        existing = db.scalar(
            select(AccountEnrollment)
            .where(AccountEnrollment.request_id == payload.request_id)
            .with_for_update()
        )
        if existing is not None:
            if not secrets.compare_digest(existing.request_hmac, request_hmac):
                raise AccountServiceError(
                    "account_enrollment_idempotency_conflict",
                    "The enrollment request identifier is already bound.",
                    status_code=409,
                )
            if existing.state == "PENDING_DELIVERY":
                lease_seconds = getattr(
                    self.settings,
                    "account_otp_delivery_lease_seconds",
                    30,
                )
                lease_until = existing.updated_at + timedelta(
                    seconds=lease_seconds
                )
                if observed_at < lease_until:
                    raise AccountServiceError(
                        "account_enrollment_in_progress",
                        "The enrollment request is still being delivered.",
                        status_code=409,
                        retry_after=max(
                            1,
                            int((lease_until - observed_at).total_seconds()),
                        ),
                    )
                prior_otp_hmac = existing.otp_hmac
                code = ""
                replacement_otp_hmac = prior_otp_hmac
                for _ in range(8):
                    code = f"{secrets.randbelow(1_000_000):06d}"
                    replacement_otp_hmac = crypto.otp_hmac(
                        handle=existing.enrollment_handle,
                        email=email,
                        code=code,
                    )
                    if replacement_otp_hmac != prior_otp_hmac:
                        break
                if replacement_otp_hmac == prior_otp_hmac:
                    raise AccountServiceError(
                        "account_enrollment_unavailable",
                        "Account enrollment is temporarily unavailable.",
                        status_code=503,
                    )
                existing.state_version += 1
                existing.issue_count += 1
                existing.attempt_count = 0
                existing.otp_hmac = replacement_otp_hmac
                existing.expires_at = observed_at + timedelta(
                    seconds=self.settings.account_otp_ttl_seconds
                )
                existing.resend_not_before = observed_at + timedelta(
                    seconds=self.settings.account_otp_resend_cooldown_seconds
                )
                existing.updated_at = observed_at
                response = self._response(existing)
                delivery = OtpDelivery(
                    enrollment_id=existing.id,
                    issue_count=existing.issue_count,
                    email=email,
                    code=code,
                )
                db.commit()
                return EnrollmentIssue(response, delivery)
            if existing.state != "DELIVERY_FAILED":
                response = self._response(existing)
                db.commit()
                return EnrollmentIssue(response, None)
            if observed_at < existing.resend_not_before:
                retry_after = max(
                    1,
                    int((existing.resend_not_before - observed_at).total_seconds()),
                )
                raise AccountServiceError(
                    "account_enrollment_rate_limited",
                    "A new verification code cannot be sent yet.",
                    status_code=429,
                    retry_after=retry_after,
                )
            code = f"{secrets.randbelow(1_000_000):06d}"
            existing.state = "PENDING_DELIVERY"
            existing.state_version += 1
            existing.issue_count += 1
            existing.attempt_count = 0
            existing.otp_hmac = crypto.otp_hmac(
                handle=existing.enrollment_handle,
                email=email,
                code=code,
            )
            existing.expires_at = observed_at + timedelta(
                seconds=self.settings.account_otp_ttl_seconds
            )
            existing.resend_not_before = observed_at + timedelta(
                seconds=self.settings.account_otp_resend_cooldown_seconds
            )
            existing.updated_at = observed_at
            response = self._response(existing)
            delivery = OtpDelivery(
                enrollment_id=existing.id,
                issue_count=existing.issue_count,
                email=email,
                code=code,
            )
            db.commit()
            return EnrollmentIssue(response, delivery)

        latest = db.scalar(
            select(AccountEnrollment)
            .where(AccountEnrollment.email_lookup_hmac == lookup_hmac)
            .order_by(AccountEnrollment.created_at.desc())
            .limit(1)
            .with_for_update()
        )
        if latest is not None and observed_at < latest.resend_not_before:
            raise AccountServiceError(
                "account_enrollment_rate_limited",
                "A new verification code cannot be sent yet.",
                status_code=429,
                retry_after=max(
                    1,
                    int((latest.resend_not_before - observed_at).total_seconds()),
                ),
            )

        live_enrollments = db.scalars(
            select(AccountEnrollment)
            .where(
                AccountEnrollment.email_lookup_hmac == lookup_hmac,
                AccountEnrollment.state.in_(
                    ("PENDING_DELIVERY", "ACTIVE", "DELIVERY_FAILED")
                ),
            )
            .order_by(AccountEnrollment.created_at, AccountEnrollment.id)
            .with_for_update()
        ).all()
        for live_enrollment in live_enrollments:
            live_enrollment.state = "EXPIRED"
            live_enrollment.state_version += 1
            live_enrollment.otp_hmac = None
            live_enrollment.updated_at = observed_at

        enrollment_id = uuid.uuid4()
        handle = secrets.token_urlsafe(32)
        code = f"{secrets.randbelow(1_000_000):06d}"
        nonce, ciphertext = crypto.encrypt(enrollment_id, email)
        enrollment = AccountEnrollment(
            id=enrollment_id,
            request_id=payload.request_id,
            request_hmac=request_hmac,
            enrollment_handle=handle,
            email_lookup_hmac=lookup_hmac,
            email_ciphertext=ciphertext,
            email_nonce=nonce,
            email_key_version=crypto.key_version,
            otp_hmac=crypto.otp_hmac(handle=handle, email=email, code=code),
            state="PENDING_DELIVERY",
            state_version=1,
            attempt_count=0,
            max_attempts=self.settings.account_otp_attempt_limit,
            issue_count=1,
            expires_at=observed_at
            + timedelta(seconds=self.settings.account_otp_ttl_seconds),
            resend_not_before=observed_at
            + timedelta(seconds=self.settings.account_otp_resend_cooldown_seconds),
            consumed_at=None,
            created_at=observed_at,
            updated_at=observed_at,
        )
        db.add(enrollment)
        response = self._response(enrollment)
        delivery = OtpDelivery(
            enrollment_id=enrollment_id,
            issue_count=1,
            email=email,
            code=code,
        )
        try:
            db.commit()
        except IntegrityError as exc:
            db.rollback()
            raise AccountServiceError(
                "account_enrollment_idempotency_conflict",
                "The enrollment request could not be accepted.",
                status_code=409,
            ) from exc
        return EnrollmentIssue(response, delivery)

    def mark_delivery_sent(
        self,
        db: Session,
        delivery: OtpDelivery,
        *,
        now: datetime | None = None,
    ) -> None:
        observed_at = (now or utc_now()).astimezone(UTC).replace(microsecond=0)
        result = db.execute(
            update(AccountEnrollment)
            .where(
                AccountEnrollment.id == delivery.enrollment_id,
                AccountEnrollment.state == "PENDING_DELIVERY",
                AccountEnrollment.issue_count == delivery.issue_count,
            )
            .values(
                state="ACTIVE",
                state_version=AccountEnrollment.state_version + 1,
                updated_at=observed_at,
            )
        )
        if result.rowcount != 1:
            db.rollback()
            raise AccountServiceError(
                "account_enrollment_unavailable",
                "Account enrollment is temporarily unavailable.",
                status_code=503,
            )
        db.commit()

    def mark_delivery_failed(
        self,
        db: Session,
        delivery: OtpDelivery,
        *,
        now: datetime | None = None,
    ) -> None:
        observed_at = (now or utc_now()).astimezone(UTC).replace(microsecond=0)
        db.execute(
            update(AccountEnrollment)
            .where(
                AccountEnrollment.id == delivery.enrollment_id,
                AccountEnrollment.state == "PENDING_DELIVERY",
                AccountEnrollment.issue_count == delivery.issue_count,
            )
            .values(
                state="DELIVERY_FAILED",
                otp_hmac=None,
                state_version=AccountEnrollment.state_version + 1,
                updated_at=observed_at,
            )
        )
        db.commit()

    def _validate_consent(
        self,
        payload: AccountCreateRequestV1,
    ) -> tuple[dict[str, str], dict[str, bool]]:
        versions = payload.consent.document_versions.model_dump()
        expected = dict(self.settings.account_signup_document_versions)
        if versions != expected:
            raise AccountServiceError(
                "signup_consent_invalid",
                "The signup consent documents are not current.",
                status_code=400,
            )
        selections = payload.consent.selections.model_dump()
        return versions, selections

    @staticmethod
    def _receipt_sha256(
        *,
        actor_id: str,
        account_generation: int,
        versions: dict[str, str],
        selections: dict[str, bool],
        recorded_at: datetime,
    ) -> str:
        canonical = json.dumps(
            {
                "account_generation": account_generation,
                "actor_id": actor_id,
                "document_versions": versions,
                "recorded_at": recorded_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "schema_version": "walksafe.signup-consent.v1",
                "selections": selections,
            },
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return hashlib.sha256(
            b"walksafe/signup-consent-receipt/v1\0" + canonical
        ).hexdigest()

    def create_account(
        self,
        db: Session,
        payload: AccountCreateRequestV1,
        *,
        now: datetime | None = None,
    ) -> AccountResponseV1:
        versions, selections = self._validate_consent(payload)
        observed_at = (now or utc_now()).astimezone(UTC).replace(microsecond=0)
        crypto = self._crypto()
        bind_or_verify_account_crypto_keys(db, crypto)
        enrollment = db.scalar(
            select(AccountEnrollment)
            .where(
                AccountEnrollment.enrollment_handle == payload.enrollment_handle
            )
            .with_for_update()
        )
        if enrollment is None:
            raise AccountServiceError(
                "account_enrollment_verification_failed",
                "The enrollment verification failed.",
                status_code=401,
            )
        if enrollment.state == "CONSUMED":
            raise AccountServiceError(
                "account_enrollment_conflict",
                "The enrollment cannot be used to create an account.",
                status_code=409,
            )
        if enrollment.state != "ACTIVE":
            raise AccountServiceError(
                "account_enrollment_verification_failed",
                "The enrollment verification failed.",
                status_code=401,
            )
        if enrollment.expires_at <= observed_at:
            enrollment.state = "EXPIRED"
            enrollment.state_version += 1
            enrollment.otp_hmac = None
            enrollment.updated_at = observed_at
            flag_modified(enrollment, "updated_at")
            db.commit()
            raise AccountServiceError(
                "account_enrollment_verification_failed",
                "The enrollment verification failed.",
                status_code=401,
            )
        email = crypto.decrypt(
            enrollment.id,
            nonce=enrollment.email_nonce,
            ciphertext=enrollment.email_ciphertext,
            key_version=enrollment.email_key_version,
        )
        actual_otp_hmac = crypto.otp_hmac(
            handle=enrollment.enrollment_handle,
            email=email,
            code=payload.otp_code,
        )
        if enrollment.otp_hmac is None or not secrets.compare_digest(
            actual_otp_hmac,
            enrollment.otp_hmac,
        ):
            enrollment.attempt_count += 1
            enrollment.state_version += 1
            enrollment.updated_at = observed_at
            flag_modified(enrollment, "updated_at")
            if enrollment.attempt_count >= enrollment.max_attempts:
                enrollment.state = "EXHAUSTED"
                enrollment.otp_hmac = None
            db.commit()
            raise AccountServiceError(
                "account_enrollment_verification_failed",
                "The enrollment verification failed.",
                status_code=401,
            )

        existing_account = db.scalar(
            select(UserAccount.id).where(
                UserAccount.email_lookup_hmac == enrollment.email_lookup_hmac
            )
        )
        if existing_account is not None:
            enrollment.state = "CONSUMED"
            enrollment.state_version += 1
            enrollment.otp_hmac = None
            enrollment.consumed_at = observed_at
            enrollment.updated_at = observed_at
            flag_modified(enrollment, "updated_at")
            db.commit()
            raise AccountServiceError(
                "account_enrollment_conflict",
                "The enrollment cannot be used to create an account.",
                status_code=409,
            )

        password_hash = hash_account_password(payload.password)
        account_id = uuid.uuid4()
        actor_id = str(uuid.uuid4())
        account_generation = 1
        account_nonce, account_ciphertext = crypto.encrypt(account_id, email)
        try:
            subject_hmac = privacy_subject_hmac(
                actor_id,
                account_generation,
                self.settings.privacy_hmac_secret,
            )
        except (ValueError, PrivacyLifecycleError) as exc:
            raise AccountServiceError(
                "account_enrollment_unavailable",
                "Account enrollment is temporarily unavailable.",
                status_code=503,
            ) from exc
        receipt_sha256 = self._receipt_sha256(
            actor_id=actor_id,
            account_generation=account_generation,
            versions=versions,
            selections=selections,
            recorded_at=observed_at,
        )
        account = UserAccount(
            id=account_id,
            actor_id=actor_id,
            privacy_subject_hmac=subject_hmac,
            email_lookup_hmac=enrollment.email_lookup_hmac,
            email_ciphertext=account_ciphertext,
            email_nonce=account_nonce,
            email_key_version=crypto.key_version,
            password_hash=password_hash,
            status="ACTIVE",
            account_generation=account_generation,
            auth_epoch=1,
            created_at=observed_at,
            updated_at=observed_at,
        )
        receipt = SignupConsentReceipt(
            id=uuid.uuid4(),
            account_id=account_id,
            schema_version="walksafe.signup-consent.v1",
            document_versions=versions,
            selections=selections,
            receipt_sha256=receipt_sha256,
            recorded_at=observed_at,
        )
        enrollment.state = "CONSUMED"
        enrollment.state_version += 1
        enrollment.otp_hmac = None
        enrollment.consumed_at = observed_at
        enrollment.updated_at = observed_at
        flag_modified(enrollment, "updated_at")
        db.add(account)
        db.add(receipt)
        try:
            db.commit()
        except IntegrityError as exc:
            db.rollback()
            db.execute(
                update(AccountEnrollment)
                .where(
                    AccountEnrollment.id == enrollment.id,
                    AccountEnrollment.state == "ACTIVE",
                )
                .values(
                    state="CONSUMED",
                    state_version=AccountEnrollment.state_version + 1,
                    otp_hmac=None,
                    consumed_at=observed_at,
                    updated_at=observed_at,
                )
            )
            db.commit()
            raise AccountServiceError(
                "account_enrollment_conflict",
                "The enrollment cannot be used to create an account.",
                status_code=409,
            ) from exc
        return AccountResponseV1(
            schema_version="walksafe.account.v1",
            actor_id=actor_id,
            account_generation=account_generation,
            signup_receipt_sha256=receipt_sha256,
        )

    def authenticate(
        self,
        db: Session,
        payload: AccountAuthenticateRequestV1,
    ) -> AccountAuthenticationResponseV1:
        account: UserAccount | None = None
        email: str | None = None
        crypto = self._crypto()
        bind_or_verify_account_crypto_keys(db, crypto)
        try:
            email = normalize_email(payload.email)
            lookup_hmac = crypto.email_lookup_hmac(email)
        except ValueError:
            lookup_hmac = crypto.email_lookup_hmac(
                "invalid-account-email@invalid.invalid"
            )
        self._authentication_rate_limiter.check(
            email_lookup_hmac=lookup_hmac,
        )
        if email is not None:
            account = db.scalar(
                select(UserAccount).where(
                    UserAccount.email_lookup_hmac == lookup_hmac
                )
            )
        encoded = (
            account.password_hash
            if account is not None and account.status == "ACTIVE"
            else self._dummy_password_hash
        )
        with self._password_verification_gate.slot():
            verified = verify_account_password(payload.password, encoded)
        if account is None or account.status != "ACTIVE" or not verified:
            raise AccountServiceError(
                "account_authentication_failed",
                "The email or password is invalid.",
                status_code=401,
            )
        observed_auth_epoch = account.auth_epoch
        refreshed = db.execute(
            select(
                UserAccount.actor_id,
                UserAccount.account_generation,
                UserAccount.auth_epoch,
                UserAccount.status,
            )
            .where(UserAccount.id == account.id)
            .with_for_update()
        ).one_or_none()
        if (
            refreshed is None
            or refreshed.status != "ACTIVE"
            or refreshed.auth_epoch != observed_auth_epoch
        ):
            raise AccountServiceError(
                "account_authentication_failed",
                "The email or password is invalid.",
                status_code=401,
            )
        return AccountAuthenticationResponseV1(
            schema_version="walksafe.account-authentication.v1",
            actor_id=refreshed.actor_id,
            account_generation=refreshed.account_generation,
            auth_epoch=refreshed.auth_epoch,
        )


__all__ = [
    "account_email_crypto_for_settings",
    "AccountAuthenticationRateLimiter",
    "AccountEmailCrypto",
    "AccountEnrollmentRateLimiter",
    "AccountPasswordVerificationGate",
    "AccountService",
    "AccountServiceError",
    "EnrollmentIssue",
    "OtpDeliveryError",
    "OtpSender",
    "SmtpOtpSender",
    "UnavailableOtpSender",
    "assert_account_crypto_keys_bound",
    "bind_or_verify_account_crypto_keys",
    "create_otp_sender",
    "hash_account_password",
    "minimum_birth_date_for_age",
    "normalize_email",
    "require_minimum_age",
    "verify_account_password",
]
