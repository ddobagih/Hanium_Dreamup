from __future__ import annotations

from datetime import UTC, date, datetime
import importlib
from types import SimpleNamespace
import uuid

import pytest
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError

from asgi_client import ASGITestClient
from backend.app.account_schemas import (
    AccountAuthenticateRequestV1,
    AccountCreateRequestV1,
    canonical_email,
    EmailOtpEnrollmentRequestV1,
    EmailOtpEnrollmentResponseV1,
)
from backend.app.api.accounts import create_router
from backend.app.database import get_db
from backend.app.field_test_security import (
    FieldTestAccess,
    required_field_test_access,
    requires_actor_identity,
)
from backend.app.main import app, walksafe_request_validation_error
from backend.app.models import AccountEnrollment, SignupConsentReceipt, UserAccount
from backend.app.request_limits import (
    ACCOUNT_REQUEST_BODY_LIMIT_BYTES,
    max_request_body_bytes,
)
import backend.app.services.accounts as accounts_service
from backend.app.services.accounts import (
    AccountAuthenticationRateLimiter,
    AccountEmailCrypto,
    AccountEnrollmentRateLimiter,
    AccountPasswordVerificationGate,
    AccountService,
    AccountServiceError,
    EnrollmentIssue,
    OtpDelivery,
    OtpDeliveryError,
    SmtpOtpSender,
    UnavailableOtpSender,
    hash_account_password,
    normalize_email,
    require_minimum_age,
    verify_account_password,
)
from backend.app.services.actor_rate_limit import ActorRateLimitStoreUnavailable


def _settings(**overrides):
    values = {
        "account_email_encryption_key": b"E" * 32,
        "account_email_lookup_hmac_key": b"L" * 32,
        "account_otp_hmac_key": b"O" * 32,
        "account_email_key_version": 1,
        "account_otp_ttl_seconds": 600,
        "account_otp_attempt_limit": 5,
        "account_otp_resend_cooldown_seconds": 60,
        "account_otp_delivery_lease_seconds": 30,
        "actor_rate_limit_store": "memory",
        "account_authentication_global_limit": 120,
        "account_authentication_global_window_seconds": 60,
        "account_authentication_email_limit": 10,
        "account_authentication_email_window_seconds": 900,
        "account_authentication_scrypt_inflight_limit": 4,
        "account_signup_document_versions": {
            "terms_of_service": "walksafe.terms-of-service.v1",
            "privacy_notice": "walksafe.privacy-notice.v1",
            "location_terms": "walksafe.location-terms.v1",
            "raw_original": "FP-013-RAW-1.1.0",
            "automatic_reporting": "FP-013-AUTO-1.1.0",
            "training_reuse": "FP-013-TRAINING-1.1.0",
        },
        "privacy_hmac_secret": "walksafe-test-privacy-secret-at-least-32-bytes",
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def _account_payload(**overrides):
    payload = {
        "schema_version": "walksafe.account-create.v1",
        "enrollment_handle": "A" * 43,
        "otp_code": "012345",
        "password": "correct horse battery staple",
        "consent": {
            "schema_version": "walksafe.signup-consent.v1",
            "document_versions": _settings().account_signup_document_versions,
            "selections": {
                "terms_of_service": True,
                "privacy_notice": True,
                "location_terms": True,
            },
        },
    }
    payload.update(overrides)
    return payload


def _crypto_binding(service: AccountService) -> SimpleNamespace:
    encryption, lookup, otp = service._crypto().key_fingerprints
    return SimpleNamespace(
        key_version=1,
        encryption_key_fingerprint=encryption,
        lookup_hmac_key_fingerprint=lookup,
        otp_hmac_key_fingerprint=otp,
    )


@pytest.mark.parametrize(
    ("source_domain", "canonical_domain"),
    (
        ("faß.de", "xn--fa-hia.de"),
        ("ς.gr", "xn--3xa.gr"),
        ("example。com", "example.com"),
        ("xn--fa-hia.de", "xn--fa-hia.de"),
        ("☕.example", "xn--53h.example"),
    ),
)
def test_email_idna_golden_vectors_use_exact_canonical_ascii(
    source_domain: str,
    canonical_domain: str,
) -> None:
    source = f"User.Name+tag@{source_domain}"
    canonical = f"User.Name+tag@{canonical_domain}"
    assert canonical_email(canonical) == canonical
    assert normalize_email(canonical) == canonical
    assert (
        EmailOtpEnrollmentRequestV1.model_validate(
            {
                "schema_version": "walksafe.account-enrollment-email-otp.v1",
                "email": canonical,
                "date_of_birth": "2000-01-01",
                "request_id": "request_identifier_0001",
            }
        ).email
        == canonical
    )
    if source != canonical:
        with pytest.raises(ValueError):
            canonical_email(source)
        with pytest.raises(ValidationError):
            AccountAuthenticateRequestV1.model_validate(
                {
                    "schema_version": "walksafe.account-authenticate.v1",
                    "email": source,
                    "password": "long enough password",
                }
            )


def test_email_contract_rejects_noncanonical_or_invalid_values() -> None:
    for invalid in (
        " User.Name+tag@example.com ",
        "User.Name+tag@EXAMPLE.COM",
        "missing-at.example.com",
        "a..b@example.com",
        '"quoted"@example.com',
        "user@example",
        "user@-example.com",
        "사용자@example.com",
        "user@예시.한국",
        "user@a\u200db.com",
        "user@ab--cd.example",
    ):
        with pytest.raises(ValueError):
            normalize_email(invalid)


def test_age_boundary_uses_calendar_date() -> None:
    today = date(2026, 8, 29)
    require_minimum_age(date(2012, 8, 29), today=today)
    with pytest.raises(AccountServiceError) as rejected:
        require_minimum_age(date(2012, 8, 30), today=today)
    assert rejected.value.code == "account_enrollment_not_allowed"

    leap_day = date(2026, 2, 28)
    require_minimum_age(date(2012, 2, 29), today=leap_day)


def test_email_encryption_and_otp_hmac_are_bound_and_non_plaintext() -> None:
    crypto = AccountEmailCrypto(
        encryption_key=b"E" * 32,
        lookup_hmac_key=b"L" * 32,
        otp_hmac_key=b"O" * 32,
        key_version=1,
    )
    record_id = __import__("uuid").uuid4()
    email = "person@example.com"
    nonce, ciphertext = crypto.encrypt(record_id, email)
    assert email.encode() not in ciphertext
    assert crypto.decrypt(
        record_id,
        nonce=nonce,
        ciphertext=ciphertext,
        key_version=1,
    ) == email
    first = crypto.otp_hmac(handle="A" * 43, email=email, code="123456")
    assert first != crypto.otp_hmac(
        handle="B" * 43,
        email=email,
        code="123456",
    )
    assert first != crypto.otp_hmac(
        handle="A" * 43,
        email="other@example.com",
        code="123456",
    )
    assert "123456" not in first


def test_account_crypto_key_fingerprints_bind_all_three_keys_and_version() -> None:
    first = AccountEmailCrypto(
        encryption_key=b"E" * 32,
        lookup_hmac_key=b"L" * 32,
        otp_hmac_key=b"O" * 32,
        key_version=1,
    )
    changed_otp = AccountEmailCrypto(
        encryption_key=b"E" * 32,
        lookup_hmac_key=b"L" * 32,
        otp_hmac_key=b"P" * 32,
        key_version=1,
    )
    assert all(len(value) == 64 for value in first.key_fingerprints)
    assert first.key_fingerprints != changed_otp.key_fingerprints
    assert all(key.decode("ascii") not in first.key_fingerprints for key in (b"E", b"L", b"O"))


def test_account_crypto_binding_mismatch_fails_before_password_work(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = AccountService(_settings())

    class MismatchedDb:
        @staticmethod
        def get(_model, _key, **_kwargs):
            return SimpleNamespace(
                key_version=2,
                encryption_key_fingerprint="a" * 64,
                lookup_hmac_key_fingerprint="b" * 64,
                otp_hmac_key_fingerprint="c" * 64,
            )

    password_work = False

    def unexpected_password_work(*_args):
        nonlocal password_work
        password_work = True
        return False

    monkeypatch.setattr(
        accounts_service,
        "verify_account_password",
        unexpected_password_work,
    )
    with pytest.raises(AccountServiceError) as rejected:
        service.authenticate(
            MismatchedDb(),
            AccountAuthenticateRequestV1(
                schema_version="walksafe.account-authenticate.v1",
                email="person@example.com",
                password="long enough password",
            ),
        )
    assert rejected.value.code == "account_crypto_key_mismatch"
    assert rejected.value.status_code == 503
    assert password_work is False


def test_password_encoder_is_versioned_salted_and_constant_digest_checked() -> None:
    first = hash_account_password("long enough password")
    second = hash_account_password("long enough password")
    assert first.startswith("account-scrypt-v1$")
    assert first != second
    assert "long enough password" not in first
    assert verify_account_password("long enough password", first)
    assert not verify_account_password("wrong password", first)
    with pytest.raises(ValueError):
        hash_account_password("short")


def test_account_authentication_rate_limits_global_and_email_buckets() -> None:
    common = {
        "actor_rate_limit_store": "memory",
        "account_authentication_global_window_seconds": 60,
        "account_authentication_email_window_seconds": 60,
    }
    email_limiter = AccountAuthenticationRateLimiter(
        SimpleNamespace(
            **common,
            account_authentication_global_limit=10,
            account_authentication_email_limit=1,
        )
    )
    email_limiter.check(email_lookup_hmac="a" * 64)
    with pytest.raises(AccountServiceError) as email_rejected:
        email_limiter.check(email_lookup_hmac="a" * 64)
    assert email_rejected.value.code == "account_authentication_rate_limited"
    assert email_rejected.value.status_code == 429
    email_limiter.check(email_lookup_hmac="b" * 64)

    global_limiter = AccountAuthenticationRateLimiter(
        SimpleNamespace(
            **common,
            account_authentication_global_limit=1,
            account_authentication_email_limit=10,
        )
    )
    global_limiter.check(email_lookup_hmac="c" * 64)
    with pytest.raises(AccountServiceError) as global_rejected:
        global_limiter.check(email_lookup_hmac="d" * 64)
    assert global_rejected.value.code == "account_authentication_rate_limited"
    assert global_rejected.value.status_code == 429


def test_account_password_verification_gate_fails_closed_without_queueing() -> None:
    assert accounts_service._process_password_verification_gate(4) is (
        accounts_service._process_password_verification_gate(4)
    )
    with pytest.raises(ValueError):
        accounts_service._process_password_verification_gate(3)

    gate = AccountPasswordVerificationGate(1)
    with gate.slot():
        with pytest.raises(AccountServiceError) as rejected:
            with gate.slot():
                pass
    assert rejected.value.code == "account_authentication_busy"
    assert rejected.value.status_code == 503
    assert rejected.value.retry_after == 1


def test_account_authentication_rate_store_failure_is_generic_503(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    limiter = AccountAuthenticationRateLimiter(
        SimpleNamespace(
            actor_rate_limit_store="memory",
            account_authentication_global_limit=10,
            account_authentication_global_window_seconds=60,
            account_authentication_email_limit=10,
            account_authentication_email_window_seconds=60,
        )
    )

    def unavailable(*_args, **_kwargs):
        raise ActorRateLimitStoreUnavailable

    monkeypatch.setattr(limiter._global, "check", unavailable)
    with pytest.raises(AccountServiceError) as rejected:
        limiter.check(email_lookup_hmac="a" * 64)
    assert rejected.value.code == "account_authentication_unavailable"
    assert rejected.value.status_code == 503


def test_authentication_rate_limit_migration_adds_only_auth_groups() -> None:
    migration = importlib.import_module(
        "backend.alembic.versions.202608290011_account_authentication_limits"
    )
    assert migration.revision == "202608290011"
    assert migration.down_revision == "202608290010"
    assert set(migration._RATE_GROUPS) - set(migration._PREVIOUS_RATE_GROUPS) == {
        "account_authentication_global",
        "account_authentication_email",
    }


def test_unknown_account_runs_one_dummy_scrypt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = AccountService(_settings())
    real_scrypt = accounts_service.hashlib.scrypt
    calls = 0

    def tracked_scrypt(*args, **kwargs):
        nonlocal calls
        calls += 1
        return real_scrypt(*args, **kwargs)

    monkeypatch.setattr(accounts_service.hashlib, "scrypt", tracked_scrypt)

    class MissingAccountDb:
        @staticmethod
        def get(_model, _key, **_kwargs):
            return _crypto_binding(service)

        @staticmethod
        def scalar(_statement):
            return None

    with pytest.raises(AccountServiceError) as rejected:
        service.authenticate(
            MissingAccountDb(),
            AccountAuthenticateRequestV1(
                schema_version="walksafe.account-authenticate.v1",
                email="unknown@example.com",
                password="long enough password",
            ),
        )
    assert rejected.value.code == "account_authentication_failed"
    assert calls == 1


def test_authentication_rechecks_status_and_epoch_after_password_verification(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = AccountService(_settings())
    account = SimpleNamespace(
        id=uuid.uuid4(),
        actor_id=str(uuid.uuid4()),
        account_generation=1,
        auth_epoch=1,
        password_hash="encoded-password",
        status="ACTIVE",
    )
    refreshed = SimpleNamespace(
        actor_id=account.actor_id,
        account_generation=1,
        auth_epoch=2,
        status="DISABLED",
    )

    class Result:
        @staticmethod
        def one_or_none():
            return refreshed

    class RacingDb:
        @staticmethod
        def get(_model, _key, **_kwargs):
            return _crypto_binding(service)

        @staticmethod
        def scalar(_statement):
            return account

        @staticmethod
        def execute(_statement):
            return Result()

    monkeypatch.setattr(accounts_service, "verify_account_password", lambda *_args: True)
    with pytest.raises(AccountServiceError) as rejected:
        service.authenticate(
            RacingDb(),
            AccountAuthenticateRequestV1(
                schema_version="walksafe.account-authenticate.v1",
                email="person@example.com",
                password="long enough password",
            ),
        )
    assert rejected.value.code == "account_authentication_failed"
    assert rejected.value.status_code == 401


def test_signup_consent_is_exact_and_optional_choices_default_false() -> None:
    request = AccountCreateRequestV1.model_validate(_account_payload())
    assert request.consent.selections.model_dump() == {
        "terms_of_service": True,
        "privacy_notice": True,
        "location_terms": True,
        "raw_original": False,
        "automatic_reporting": False,
        "training_reuse": False,
    }

    required_false = _account_payload()
    required_false["consent"]["selections"]["privacy_notice"] = False
    with pytest.raises(ValidationError):
        AccountCreateRequestV1.model_validate(required_false)

    mobile_network = _account_payload()
    mobile_network["consent"]["selections"]["mobile_network_transfer"] = False
    with pytest.raises(ValidationError):
        AccountCreateRequestV1.model_validate(mobile_network)

    missing_version = _account_payload()
    del missing_version["consent"]["document_versions"]["training_reuse"]
    with pytest.raises(ValidationError):
        AccountCreateRequestV1.model_validate(missing_version)

    coerced_boolean = _account_payload()
    coerced_boolean["consent"]["selections"]["raw_original"] = "false"
    with pytest.raises(ValidationError):
        AccountCreateRequestV1.model_validate(coerced_boolean)


def test_stale_signup_document_version_fails_closed() -> None:
    payload = _account_payload()
    payload["consent"]["document_versions"]["privacy_notice"] = "stale.v0"
    request = AccountCreateRequestV1.model_validate(payload)
    with pytest.raises(AccountServiceError) as rejected:
        AccountService(_settings())._validate_consent(request)
    assert rejected.value.code == "signup_consent_invalid"
    assert rejected.value.status_code == 400


def test_unknown_enrollment_handle_is_rejected_before_password_scrypt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = AccountService(_settings())
    calls = 0

    def unexpected_password_hash(_password: str) -> str:
        nonlocal calls
        calls += 1
        return "unexpected"

    monkeypatch.setattr(
        accounts_service,
        "hash_account_password",
        unexpected_password_hash,
    )

    class MissingEnrollmentDb:
        @staticmethod
        def get(_model, _key, **_kwargs):
            return _crypto_binding(service)

        @staticmethod
        def scalar(_statement):
            return None

    with pytest.raises(AccountServiceError) as rejected:
        service.create_account(
            MissingEnrollmentDb(),
            AccountCreateRequestV1.model_validate(_account_payload()),
        )
    assert rejected.value.code == "account_enrollment_verification_failed"
    assert calls == 0


@pytest.mark.parametrize("security", ["implicit_tls", "starttls"])
def test_smtp_sender_requires_verified_tls_without_plaintext_fallback(
    monkeypatch: pytest.MonkeyPatch,
    security: str,
) -> None:
    events: list[object] = []
    context = object()

    class FakeSmtp:
        def __init__(self, host, port, **kwargs):
            events.append(("connect", host, port, kwargs))

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            events.append("close")

        def ehlo(self):
            events.append("ehlo")

        def starttls(self, *, context):
            events.append(("starttls", context))

        def login(self, username, password):
            events.append(("login", username, password))

        def send_message(self, message):
            events.append(("send", message["To"]))

    monkeypatch.setattr(accounts_service.ssl, "create_default_context", lambda: context)
    monkeypatch.setattr(accounts_service.smtplib, "SMTP", FakeSmtp)
    monkeypatch.setattr(accounts_service.smtplib, "SMTP_SSL", FakeSmtp)
    sender = SmtpOtpSender(
        host="smtp.example.com",
        port=465 if security == "implicit_tls" else 587,
        security=security,
        username="smtp-user",
        password="smtp-password",
        from_address="no-reply@example.com",
        timeout_seconds=5,
    )
    sender.send(
        email="recipient@example.com",
        code="123456",
        expires_in_seconds=600,
    )
    connect = events[0]
    assert connect[0] == "connect"
    if security == "implicit_tls":
        assert connect[3]["context"] is context
        assert not any(
            isinstance(event, tuple) and event[0] == "starttls" for event in events
        )
    else:
        assert "context" not in connect[3]
        assert ("starttls", context) in events
    assert ("send", "recipient@example.com") in events


def test_missing_smtp_fails_closed_without_network() -> None:
    with pytest.raises(OtpDeliveryError):
        UnavailableOtpSender().send(
            email="recipient@example.com",
            code="123456",
            expires_in_seconds=600,
        )


def test_email_otp_endpoint_returns_503_when_smtp_is_missing() -> None:
    delivery_failed: list[bool] = []
    source_ips: list[str] = []
    enrollment_id = uuid.uuid4()

    class FakeService:
        @staticmethod
        def normalized_email_lookup(raw_email: str):
            return raw_email, "a" * 64

        @staticmethod
        def issue_email_otp(_db, _payload):
            return EnrollmentIssue(
                response=EmailOtpEnrollmentResponseV1(
                    schema_version=(
                        "walksafe.account-enrollment-email-otp-response.v1"
                    ),
                    enrollment_handle="A" * 43,
                    expires_at=datetime(2026, 8, 29, 1, tzinfo=UTC),
                    resend_available_at=datetime(2026, 8, 29, 0, 51, tzinfo=UTC),
                ),
                delivery=OtpDelivery(
                    enrollment_id=enrollment_id,
                    issue_count=1,
                    email="person@example.com",
                    code="123456",
                ),
            )

        @staticmethod
        def mark_delivery_failed(_db, _delivery):
            delivery_failed.append(True)

    class NoopLimiter:
        @staticmethod
        def check_source(*, source_ip: str):
            source_ips.append(source_ip)
            return None

        @staticmethod
        def check_email(**_kwargs):
            return None

    account_app = FastAPI()
    account_app.include_router(
        create_router(
            SimpleNamespace(account_otp_ttl_seconds=600),
            sender=UnavailableOtpSender(),
            service=FakeService(),
            rate_limiter=NoopLimiter(),
        )
    )

    def fake_db():
        yield object()

    account_app.dependency_overrides[get_db] = fake_db
    response = ASGITestClient(account_app).post(
        "/account-enrollments/email-otp",
        json={
            "schema_version": "walksafe.account-enrollment-email-otp.v1",
            "email": "person@example.com",
            "date_of_birth": "2000-01-01",
            "request_id": "request_identifier_0001",
        },
        headers={"x-walksafe-client-ip": "2001:db8::1"},
    )
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "account_enrollment_unavailable"
    assert delivery_failed == [True]
    assert source_ips == ["2001:db8::1"]
    assert response.headers["cache-control"] == "no-store"


def test_email_lookup_value_error_is_stable_no_store_422() -> None:
    limiter_calls: list[str] = []

    class InvalidLookupService:
        @staticmethod
        def normalized_email_lookup(_raw_email: str):
            raise ValueError("invalid normalized email")

    class NoopLimiter:
        @staticmethod
        def check_source(**_kwargs):
            limiter_calls.append("source")
            return None

        @staticmethod
        def check_email(**_kwargs):
            limiter_calls.append("email")
            return None

    class FakeDb:
        rolled_back = False

        def rollback(self):
            self.rolled_back = True

    fake_db = FakeDb()
    account_app = FastAPI()
    account_app.include_router(
        create_router(
            SimpleNamespace(account_otp_ttl_seconds=600),
            sender=UnavailableOtpSender(),
            service=InvalidLookupService(),
            rate_limiter=NoopLimiter(),
        )
    )

    def dependency():
        yield fake_db

    account_app.dependency_overrides[get_db] = dependency
    response = ASGITestClient(account_app).post(
        "/account-enrollments/email-otp",
        json={
            "schema_version": "walksafe.account-enrollment-email-otp.v1",
            "email": "person@example.com",
            "date_of_birth": "2000-01-01",
            "request_id": "request_identifier_0001",
        },
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "account_request_validation_failed"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"
    assert fake_db.rolled_back is True
    assert limiter_calls == []


@pytest.mark.parametrize(
    "invalid_email",
    (
        "abc",
        "person@faß.de",
        "person@a\u200db.com",
    ),
)
def test_direct_backend_invalid_email_is_generic_no_store_without_state_change(
    invalid_email: str,
) -> None:
    calls: list[str] = []

    class TrackingService:
        @staticmethod
        def normalized_email_lookup(_raw_email: str):
            calls.append("lookup")
            return _raw_email, "a" * 64

        @staticmethod
        def issue_email_otp(_db, _payload):
            calls.append("issue")
            raise AssertionError("invalid email reached account service")

        @staticmethod
        def authenticate(_db, _payload):
            calls.append("authenticate")
            raise AssertionError("invalid email reached account service")

    class TrackingLimiter:
        @staticmethod
        def check_source(**_kwargs):
            calls.append("source-limit")

        @staticmethod
        def check_email(**_kwargs):
            calls.append("email-limit")

    account_app = FastAPI()
    account_app.add_exception_handler(
        RequestValidationError,
        walksafe_request_validation_error,
    )
    account_app.include_router(
        create_router(
            SimpleNamespace(account_otp_ttl_seconds=600),
            sender=UnavailableOtpSender(),
            service=TrackingService(),
            rate_limiter=TrackingLimiter(),
        )
    )

    def fake_db():
        yield object()

    account_app.dependency_overrides[get_db] = fake_db
    for path, payload in (
        (
            "/account-enrollments/email-otp",
            {
                "schema_version": "walksafe.account-enrollment-email-otp.v1",
                "email": invalid_email,
                "date_of_birth": "2000-01-01",
                "request_id": "request_identifier_0001",
            },
        ),
        (
            "/accounts/authenticate",
            {
                "schema_version": "walksafe.account-authenticate.v1",
                "email": invalid_email,
                "password": "long enough password",
            },
        ),
    ):
        response = ASGITestClient(account_app).post(path, json=payload)
        assert response.status_code == 422
        assert response.json() == {
            "detail": {
                "code": "account_request_validation_failed",
                "message": "The account request does not match the required contract.",
            }
        }
        assert invalid_email not in response.text
        assert response.headers["cache-control"] == "no-store"
        assert response.headers["pragma"] == "no-cache"
    assert calls == []


def test_authentication_capacity_error_is_stable_no_store_503() -> None:
    class BusyService:
        @staticmethod
        def authenticate(_db, _payload):
            raise AccountServiceError(
                "account_authentication_busy",
                "Account authentication is temporarily unavailable.",
                status_code=503,
                retry_after=1,
            )

    class NoopLimiter:
        @staticmethod
        def check_source(**_kwargs):
            return None

        @staticmethod
        def check_email(**_kwargs):
            return None

    class FakeDb:
        rolled_back = False

        def rollback(self):
            self.rolled_back = True

    fake_db = FakeDb()
    account_app = FastAPI()
    account_app.include_router(
        create_router(
            SimpleNamespace(account_otp_ttl_seconds=600),
            service=BusyService(),
            rate_limiter=NoopLimiter(),
        )
    )

    def dependency():
        yield fake_db

    account_app.dependency_overrides[get_db] = dependency
    response = ASGITestClient(account_app).post(
        "/accounts/authenticate",
        json={
            "schema_version": "walksafe.account-authenticate.v1",
            "email": "person@example.com",
            "password": "long enough password",
        },
    )
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "account_authentication_busy"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"
    assert response.headers["retry-after"] == "1"
    assert fake_db.rolled_back is True


def test_account_tables_have_no_birth_date_or_plain_email_columns() -> None:
    for model in (AccountEnrollment, UserAccount, SignupConsentReceipt):
        columns = set(model.__table__.columns.keys())
        assert "date_of_birth" not in columns
        assert "birth_date" not in columns
        assert "email" not in columns
    assert {"email_ciphertext", "email_nonce", "email_lookup_hmac"} <= set(
        UserAccount.__table__.columns.keys()
    )


def test_gateway_account_routes_are_field_scoped_without_actor_identity() -> None:
    for path in (
        "/account-enrollments/email-otp",
        "/accounts",
        "/accounts/authenticate",
    ):
        assert required_field_test_access(path, "POST") is FieldTestAccess.FIELD
        assert not requires_actor_identity(path, "POST")
        assert max_request_body_bytes(SimpleNamespace(), path=path) == (
            ACCOUNT_REQUEST_BODY_LIMIT_BYTES
        )
    assert required_field_test_access("/accounts/nearby", "POST") is (
        FieldTestAccess.ADMIN
    )


def test_account_enrollment_source_and_email_rate_limits_fail_closed() -> None:
    common = {
        "actor_rate_limit_store": "memory",
        "account_enrollment_global_window_seconds": 60,
        "account_enrollment_ip_window_seconds": 60,
        "account_enrollment_email_window_seconds": 60,
    }
    source_limiter = AccountEnrollmentRateLimiter(
        SimpleNamespace(
            **common,
            account_enrollment_global_limit=10,
            account_enrollment_ip_limit=1,
            account_enrollment_email_limit=10,
        )
    )
    source_limiter.check_source(source_ip="192.0.2.10")
    with pytest.raises(AccountServiceError) as source_rejected:
        source_limiter.check_source(source_ip="192.0.2.10")
    assert source_rejected.value.code == "account_enrollment_rate_limited"
    assert source_rejected.value.status_code == 429

    global_limiter = AccountEnrollmentRateLimiter(
        SimpleNamespace(
            **common,
            account_enrollment_global_limit=1,
            account_enrollment_ip_limit=10,
            account_enrollment_email_limit=10,
        )
    )
    global_limiter.check_source(source_ip="192.0.2.20")
    with pytest.raises(AccountServiceError) as global_rejected:
        global_limiter.check_source(source_ip="192.0.2.21")
    assert global_rejected.value.code == "account_enrollment_rate_limited"
    assert global_rejected.value.status_code == 429

    email_limiter = AccountEnrollmentRateLimiter(
        SimpleNamespace(
            **common,
            account_enrollment_global_limit=10,
            account_enrollment_ip_limit=10,
            account_enrollment_email_limit=1,
        )
    )
    email_limiter.check_email(email_lookup_hmac="a" * 64)
    with pytest.raises(AccountServiceError) as email_rejected:
        email_limiter.check_email(email_lookup_hmac="a" * 64)
    assert email_rejected.value.code == "account_enrollment_rate_limited"
    assert email_rejected.value.status_code == 429


def test_oversized_account_request_uses_stable_no_store_error() -> None:
    response = ASGITestClient(app).post(
        "/accounts",
        content=b"x" * (ACCOUNT_REQUEST_BODY_LIMIT_BYTES + 1),
        headers={"content-type": "application/json"},
    )
    assert response.status_code == 413
    assert response.json() == {
        "detail": {
            "code": "account_request_body_too_large",
            "message": "The account request body exceeds the fixed byte limit.",
        }
    }
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"


def test_sensitive_validation_error_does_not_echo_password_or_email() -> None:
    secret_password = "do-not-reflect-this-password"
    secret_email = "private-person@example.com"
    response = ASGITestClient(app).post(
        "/accounts",
        json={
            "schema_version": "walksafe.account-create.v1",
            "enrollment_handle": "too-short",
            "otp_code": "not-six-digits",
            "password": secret_password,
            "email": secret_email,
        },
    )
    assert response.status_code == 422
    assert response.json() == {
        "detail": {
            "code": "account_request_validation_failed",
            "message": "The account request does not match the required contract.",
        }
    }
    assert secret_password not in response.text
    assert secret_email not in response.text
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"


def test_enrollment_request_requires_canonical_date_text() -> None:
    valid = EmailOtpEnrollmentRequestV1.model_validate(
        {
            "schema_version": "walksafe.account-enrollment-email-otp.v1",
            "email": "person@example.com",
            "date_of_birth": "2010-01-02",
            "request_id": "request_identifier_0001",
        }
    )
    assert valid.date_of_birth == date(2010, 1, 2)
    with pytest.raises(ValidationError):
        EmailOtpEnrollmentRequestV1.model_validate(
            {
                "schema_version": "walksafe.account-enrollment-email-otp.v1",
                "email": "person@example.com",
                "date_of_birth": datetime(2010, 1, 2, tzinfo=UTC),
                "request_id": "request_identifier_0001",
            }
        )
