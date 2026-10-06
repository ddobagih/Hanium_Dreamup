from __future__ import annotations

from copy import deepcopy
from datetime import UTC, date, datetime, timedelta
from email.utils import parsedate_to_datetime
import importlib
import json
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError
from sqlalchemy.exc import IntegrityError

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
    FieldTestSecurityMiddleware,
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


def test_shared_android_gateway_email_wire_values_are_accepted_unchanged() -> None:
    fixture = Path(__file__).resolve().parents[2] / "contracts/test-vectors/account-email.json"
    for vector in json.loads(fixture.read_text())["vectors"]:
        canonical = vector["canonical"]
        if canonical is None:
            continue  # Raw Unicode validation belongs to Android/Gateway, not the ASCII wire schema.
        assert canonical_email(canonical) == canonical
        assert normalize_email(canonical) == canonical
        assert AccountAuthenticateRequestV1.model_validate({
            "schema_version": "walksafe.account-authenticate.v1",
            "email": canonical, "password": "long enough password",
        }).email == canonical
        assert EmailOtpEnrollmentRequestV1.model_validate({
            "schema_version": "walksafe.account-enrollment-email-otp.v1",
            "email": canonical, "date_of_birth": "2000-01-01",
            "request_id": "request_identifier_0001",
        }).email == canonical


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


def test_s01_12_password_verification_keeps_issued_cost_after_current_cost_changes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    password = "long enough password"
    issued = hash_account_password(password)
    assert issued.split("$")[1:4] == ["16384", "8", "1"]
    monkeypatch.setattr(accounts_service, "_SCRYPT_P", 5)
    assert verify_account_password(password, issued) is True
    assert verify_account_password("wrong password", issued) is False
    current = hash_account_password(password)
    assert current.split("$")[1:4] == ["16384", "8", "5"]
    assert verify_account_password(password, current) is True

    def reject_unlisted_cost(*_args: object, **_kwargs: object) -> bytes:
        raise AssertionError("unlisted scrypt cost must be rejected before derivation")

    monkeypatch.setattr(accounts_service.hashlib, "scrypt", reject_unlisted_cost)
    for forged in ("$8192$8$1$", "$1048576$8$1$", "$016384$8$1$", "$16384$8$2$"):
        assert verify_account_password(password, issued.replace("$16384$8$1$", forged, 1)) is False


def test_s01_12_login_rehashes_an_issued_legacy_cost_to_the_current_cost(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    password = "long enough password"
    legacy_hash = hash_account_password(password)
    monkeypatch.setattr(accounts_service, "_SCRYPT_P", 5)
    service = AccountService(_settings())
    account = SimpleNamespace(
        id=uuid.uuid4(),
        actor_id=str(uuid.uuid4()),
        account_generation=3,
        auth_epoch=7,
        password_hash=legacy_hash,
        status="ACTIVE",
    )
    refreshed = SimpleNamespace(
        actor_id=account.actor_id,
        account_generation=3,
        auth_epoch=7,
        status="ACTIVE",
    )
    updates: list[dict[str, object]] = []
    commits: list[str] = []

    class Result:
        rowcount = 1

        @staticmethod
        def one_or_none():
            return refreshed

    class UpgradingDb:
        @staticmethod
        def get(_model, _key, **_kwargs):
            return _crypto_binding(service)

        @staticmethod
        def scalar(_statement):
            return account

        @staticmethod
        def execute(statement):
            if getattr(statement, "is_update", False):
                updates.append(dict(statement.compile().params))
            return Result()

        @staticmethod
        def commit():
            commits.append("commit")

        @staticmethod
        def rollback():
            commits.append("rollback")

    result = service.authenticate(
        UpgradingDb(),
        AccountAuthenticateRequestV1(
            schema_version="walksafe.account-authenticate.v1",
            email="person@example.com",
            password=password,
        ),
    )
    assert (result.actor_id, result.account_generation, result.auth_epoch) == (
        account.actor_id,
        3,
        7,
    )
    assert len(updates) == 1 and commits == ["commit"]
    upgraded = str(updates[0]["password_hash"])
    assert upgraded != legacy_hash
    assert upgraded.split("$")[1:4] == ["16384", "8", "5"]
    assert verify_account_password(password, upgraded) is True
    assert password not in upgraded

    account.password_hash = upgraded
    updates.clear()
    commits.clear()
    service.authenticate(
        UpgradingDb(),
        AccountAuthenticateRequestV1(
            schema_version="walksafe.account-authenticate.v1",
            email="person@example.com",
            password=password,
        ),
    )
    assert updates == [] and commits == []


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


@pytest.mark.parametrize(
    "required_key", ("terms_of_service", "privacy_notice", "location_terms")
)
def test_stale_signup_document_version_fails_closed(required_key: str) -> None:
    # S01-3: a changed required document keeps failing closed, now with a dedicated
    # code the app can turn into "review the new documents / update the app".
    payload = _account_payload()
    payload["consent"]["document_versions"][required_key] = "stale.v0"
    request = AccountCreateRequestV1.model_validate(payload)
    with pytest.raises(AccountServiceError) as rejected:
        AccountService(_settings())._validate_consent(request)
    assert rejected.value.code == "signup_consent_outdated"
    assert rejected.value.status_code == 409


@pytest.mark.parametrize(
    "optional_key", ("raw_original", "automatic_reporting", "training_reuse")
)
def test_s01_3_stale_optional_document_never_blocks_and_old_grant_is_not_applied(
    optional_key: str,
) -> None:
    # FP-013 prohibited_behaviors[0] and [2]: an optional document change must not
    # block the required service, and a grant given for the old text is not applied.
    # GWBE-04: "stale.v0" is a server-known prior version of that document.
    settings = _settings(
        account_signup_prior_document_versions={optional_key: frozenset({"stale.v0"})}
    )
    payload = _account_payload()
    payload["consent"]["document_versions"][optional_key] = "stale.v0"
    payload["consent"]["selections"].update(
        raw_original=True, automatic_reporting=True, training_reuse=True
    )
    request = AccountCreateRequestV1.model_validate(payload)
    versions, selections = AccountService(settings)._validate_consent(request)
    assert versions[optional_key] == "stale.v0"
    assert selections[optional_key] is False
    assert all(
        selections[key] is True
        for key in ("raw_original", "automatic_reporting", "training_reuse")
        if key != optional_key
    )
    assert selections["terms_of_service"] and selections["privacy_notice"]


def test_s01_3_declared_equivalent_versions_are_current_and_keep_grants() -> None:
    settings = _settings(
        account_signup_equivalent_document_versions={
            "privacy_notice": frozenset({"walksafe.privacy-notice.v0-typo"}),
            "raw_original": frozenset({"FP-013-RAW-1.0.9"}),
        }
    )
    payload = _account_payload()
    payload["consent"]["document_versions"].update(
        privacy_notice="walksafe.privacy-notice.v0-typo",
        raw_original="FP-013-RAW-1.0.9",
    )
    payload["consent"]["selections"]["raw_original"] = True
    request = AccountCreateRequestV1.model_validate(payload)
    versions, selections = AccountService(settings)._validate_consent(request)
    assert versions["privacy_notice"] == "walksafe.privacy-notice.v0-typo"
    assert versions["raw_original"] == "FP-013-RAW-1.0.9"
    assert selections["raw_original"] is True
    # An equivalence declared for one document does not leak to another.
    other = _account_payload()
    other["consent"]["document_versions"]["terms_of_service"] = (
        "walksafe.privacy-notice.v0-typo"
    )
    with pytest.raises(AccountServiceError) as rejected:
        AccountService(settings)._validate_consent(
            AccountCreateRequestV1.model_validate(other)
        )
    assert rejected.value.code == "signup_consent_outdated"


def test_s01_3_signup_equivalent_versions_config_is_strict(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from backend.app import config as config_module

    parse = config_module._parse_signup_equivalent_document_versions
    name = "WALKSAFE_SIGNUP_PRIVACY_NOTICE_EQUIVALENT_VERSIONS"
    monkeypatch.delenv(name, raising=False)
    assert parse(name, "walksafe.privacy-notice.v2") == frozenset()
    # GWBE-05: the list is anchored to the current version it was declared for.
    monkeypatch.setenv(
        name,
        " walksafe.privacy-notice.v2 = walksafe.privacy-notice.v1 , walksafe.privacy-notice.v1a ",
    )
    assert parse(name, "walksafe.privacy-notice.v2") == frozenset(
        {"walksafe.privacy-notice.v1", "walksafe.privacy-notice.v1a"}
    )
    for invalid in (
        "walksafe.privacy-notice.v1,walksafe.privacy-notice.v1a",
        "walksafe.privacy-notice.v2=walksafe.privacy-notice.v2",
        "walksafe.privacy-notice.v2=a.v1,a.v1",
        "walksafe.privacy-notice.v2=bad version",
        "walksafe.privacy-notice.v2=a.v1,,b.v1",
        "walksafe.privacy-notice.v2=",
        "walksafe.privacy-notice.v2=a.v1=b.v1",
        "walksafe.privacy-notice.v2="
        + ",".join(f"v{index}" for index in range(9)),
    ):
        monkeypatch.setenv(name, invalid)
        with pytest.raises(ValueError):
            parse(name, "walksafe.privacy-notice.v2")


def test_gwbe_05_equivalents_left_over_after_a_version_bump_refuse_startup(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Probe P4: current AUTO-2.0.0 with the list declared for AUTO-1.1.0 must not grant
    # the old "equivalent" AUTO-1.0.0 for the new document; startup fails instead.
    from backend.app.config import Settings

    monkeypatch.setenv("WALKSAFE_SIGNUP_AUTOMATIC_REPORTING_VERSION", "FP-013-AUTO-1.1.0")
    monkeypatch.setenv(
        "WALKSAFE_SIGNUP_AUTOMATIC_REPORTING_EQUIVALENT_VERSIONS",
        "FP-013-AUTO-1.1.0=FP-013-AUTO-1.0.0",
    )
    declared = Settings()
    assert declared.account_signup_equivalent_document_versions["automatic_reporting"] == (
        frozenset({"FP-013-AUTO-1.0.0"})
    )
    monkeypatch.setenv("WALKSAFE_SIGNUP_AUTOMATIC_REPORTING_VERSION", "FP-013-AUTO-2.0.0")
    with pytest.raises(ValueError, match="EQUIVALENT_VERSIONS"):
        Settings()


def test_gwbe_04_prior_versions_config_is_strict_and_optional_only(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from backend.app.config import Settings

    for key in ("RAW_ORIGINAL", "AUTOMATIC_REPORTING", "TRAINING_REUSE"):
        monkeypatch.delenv(f"WALKSAFE_SIGNUP_{key}_PRIOR_VERSIONS", raising=False)
        monkeypatch.delenv(f"WALKSAFE_SIGNUP_{key}_EQUIVALENT_VERSIONS", raising=False)
    default = Settings()
    assert default.account_signup_prior_document_versions == {
        "raw_original": frozenset(),
        "automatic_reporting": frozenset(),
        "training_reuse": frozenset(),
    }
    monkeypatch.setenv(
        "WALKSAFE_SIGNUP_TRAINING_REUSE_PRIOR_VERSIONS",
        " FP-013-TRAINING-1.0.0 , FP-013-TRAINING-0.9.0 ",
    )
    assert Settings().account_signup_prior_document_versions["training_reuse"] == frozenset(
        {"FP-013-TRAINING-1.0.0", "FP-013-TRAINING-0.9.0"}
    )
    for invalid in (
        "FP-013-TRAINING-1.1.0",
        "a.v1,a.v1",
        "bad version",
        "a.v1,,b.v1",
        ",".join(f"v{index}" for index in range(9)),
    ):
        monkeypatch.setenv("WALKSAFE_SIGNUP_TRAINING_REUSE_PRIOR_VERSIONS", invalid)
        with pytest.raises(ValueError, match="PRIOR_VERSIONS"):
            Settings()
    # A version cannot be both a non-substantive equivalent and a stale prior.
    monkeypatch.setenv("WALKSAFE_SIGNUP_TRAINING_REUSE_PRIOR_VERSIONS", "FP-013-TRAINING-1.0.0")
    monkeypatch.setenv(
        "WALKSAFE_SIGNUP_TRAINING_REUSE_EQUIVALENT_VERSIONS",
        "FP-013-TRAINING-1.1.0=FP-013-TRAINING-1.0.0",
    )
    with pytest.raises(ValueError, match="PRIOR_VERSIONS"):
        Settings()
    # Required documents have no prior-version list: any other version is 409.
    assert "terms_of_service" not in default.account_signup_prior_document_versions


def test_s01_3_create_account_with_stale_optional_document_records_shown_version() -> None:
    service = AccountService(
        _settings(),
        password_verification_gate=AccountPasswordVerificationGate(1),
    )
    store = _AccountCreationReplayStore(service)
    service.settings.account_signup_document_versions = {
        **service.settings.account_signup_document_versions,
        "automatic_reporting": "FP-013-AUTO-2.0.0",
    }
    service.settings.account_signup_prior_document_versions = {
        "automatic_reporting": frozenset({"FP-013-AUTO-1.1.0"}),
    }
    payload = _account_payload()
    payload["consent"]["selections"].update(
        raw_original=True, automatic_reporting=True
    )
    request = AccountCreateRequestV1.model_validate(payload)
    created = service.create_account(store.session(), request, now=store.now)
    assert created.schema_version == "walksafe.account.v1"
    (receipt,) = store.rows[SignupConsentReceipt].values()
    assert receipt["document_versions"]["automatic_reporting"] == "FP-013-AUTO-1.1.0"
    assert receipt["selections"]["automatic_reporting"] is False
    assert receipt["selections"]["raw_original"] is True
    assert len(store.rows[UserAccount]) == 1
    # The exact lost-result replay still returns the same account.
    assert service.create_account(store.session(), request, now=store.now) == created


def test_s01_3_create_account_with_stale_required_document_writes_nothing() -> None:
    service = AccountService(
        _settings(),
        password_verification_gate=AccountPasswordVerificationGate(1),
    )
    store = _AccountCreationReplayStore(service)
    service.settings.account_signup_document_versions = {
        **service.settings.account_signup_document_versions,
        "terms_of_service": "walksafe.terms-of-service.v2",
    }
    before = store.snapshot()
    with pytest.raises(AccountServiceError) as rejected:
        service.create_account(
            store.session(),
            AccountCreateRequestV1.model_validate(_account_payload()),
            now=store.now,
        )
    assert rejected.value.code == "signup_consent_outdated"
    assert rejected.value.status_code == 409
    # No account, no receipt, and the OTP attempt budget is untouched.
    assert store.snapshot() == before


@pytest.mark.parametrize(
    "optional_key", ("raw_original", "automatic_reporting", "training_reuse")
)
def test_gwbe_04_unknown_optional_document_version_fails_closed(optional_key: str) -> None:
    # Probe P1: a version the server never published (or a future one from a newer app)
    # is not recorded as "shown"; signup fails closed with the dedicated 409.
    settings = _settings(
        account_signup_prior_document_versions={optional_key: frozenset({"stale.v0"})}
    )
    payload = _account_payload()
    payload["consent"]["document_versions"][optional_key] = "never.published.v999"
    payload["consent"]["selections"][optional_key] = True
    request = AccountCreateRequestV1.model_validate(payload)
    for service_settings in (_settings(), settings):
        with pytest.raises(AccountServiceError) as rejected:
            AccountService(service_settings)._validate_consent(request)
        assert rejected.value.code == "signup_consent_outdated"
        assert rejected.value.status_code == 409
    # A prior version of another optional document does not cover this one.
    other = _settings(
        account_signup_prior_document_versions={
            key: frozenset({"never.published.v999"})
            for key in ("raw_original", "automatic_reporting", "training_reuse")
            if key != optional_key
        }
    )
    with pytest.raises(AccountServiceError):
        AccountService(other)._validate_consent(request)


def test_gwbe_04_create_account_with_unknown_optional_version_writes_nothing() -> None:
    service = AccountService(
        _settings(),
        password_verification_gate=AccountPasswordVerificationGate(1),
    )
    store = _AccountCreationReplayStore(service)
    payload = _account_payload()
    payload["consent"]["document_versions"]["training_reuse"] = "never.published.v999"
    payload["consent"]["selections"]["training_reuse"] = True
    before = store.snapshot()
    with pytest.raises(AccountServiceError) as rejected:
        service.create_account(
            store.session(),
            AccountCreateRequestV1.model_validate(payload),
            now=store.now,
        )
    assert rejected.value.code == "signup_consent_outdated"
    assert store.snapshot() == before
    assert not store.rows[UserAccount] and not store.rows[SignupConsentReceipt]


def test_gwbe_06_settings_document_keys_must_equal_required_and_optional_keys() -> None:
    # Probe P5: an extra (or missing) settings document key is not silently ignored.
    request = AccountCreateRequestV1.model_validate(_account_payload())
    base = _settings().account_signup_document_versions
    for versions in (
        {**base, "guardian_notice": "walksafe.guardian-notice.v1"},
        {key: value for key, value in base.items() if key != "training_reuse"},
    ):
        with pytest.raises(AccountServiceError) as rejected:
            AccountService(
                _settings(account_signup_document_versions=versions)
            )._validate_consent(request)
        assert rejected.value.code == "account_enrollment_unavailable"
        assert rejected.value.status_code == 503
    assert AccountService(_settings())._validate_consent(request)[0] == base


def test_gwbe_08_version_mismatch_message_is_neutral() -> None:
    # A required version newer than the server (app ahead of server config) gets the
    # same code, but the text no longer claims the app is out of date.
    payload = _account_payload()
    payload["consent"]["document_versions"]["terms_of_service"] = (
        "walksafe.terms-of-service.v9"
    )
    with pytest.raises(AccountServiceError) as rejected:
        AccountService(_settings())._validate_consent(
            AccountCreateRequestV1.model_validate(payload)
        )
    assert rejected.value.code == "signup_consent_outdated"
    assert rejected.value.status_code == 409
    assert rejected.value.message == (
        "The signup document versions differ between the app and the server. "
        "Try again later or update the app."
    )


def test_s01_13_new_password_minimum_is_twelve_code_points_login_keeps_legacy_range() -> None:
    # FP-010-DETAIL-02: new passwords need 12+ characters (code points, same
    # counting as Gateway/Android). Existing 10-11 character accounts can log in.
    for accepted in (
        "x" * 11 + "y",
        "우리집강아지산책좋아해요",          # 12 Hangul syllables
        "🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮",          # 12 astral code points
        "e\u0301" * 6,                      # 12 code points with combining marks
        "long passphrase " * 8,             # 128 code points
    ):
        payload = _account_payload(password=accepted)
        assert AccountCreateRequestV1.model_validate(payload).password == accepted
    for rejected in ("x" * 10 + "y", "우리집강아지산책좋아해", "🐶" * 11, "x" * 129, "long\x00passphrase"):
        with pytest.raises(ValidationError):
            AccountCreateRequestV1.model_validate(_account_payload(password=rejected))
    legacy = AccountAuthenticateRequestV1(
        schema_version="walksafe.account-authenticate.v1",
        email="person@example.com",
        password="tenletters",
    )
    assert legacy.password == "tenletters"


@pytest.mark.parametrize("password", [
    "password1234",
    "PASSWORD1234",
    "Password1234",
    "ｐａｓｓｗｏｒｄ１２３４",
    "password 1234",
    "qwerty123456",
    "1q2w3e4r5t6y",
    "123456789012",
    "aaaaaaaaaaaa",
    "가가가가가가가가가가가가",
    "abcabcabcabc",
    "walksafe1234",
])
def test_s01_13_common_or_trivial_new_password_is_rejected(password: str) -> None:
    from backend.app.services.accounts import validate_new_account_password

    with pytest.raises(AccountServiceError) as rejected:
        validate_new_account_password(password)
    assert rejected.value.code == "account_password_too_common"
    assert rejected.value.status_code == 400
    assert password not in rejected.value.message


@pytest.mark.parametrize("password", [
    "long enough password",
    "우리집 강아지는 산책을 좋아해",
    "🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮",
    "blue kettle under maple",
])
def test_s01_13_long_passphrases_are_accepted_without_composition_rules(password: str) -> None:
    from backend.app.services.accounts import validate_new_account_password

    assert validate_new_account_password(password) is None


def test_s01_13_common_password_is_rejected_before_enrollment_otp_or_scrypt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = AccountService(_settings())
    db = _AccountCreateTransactionDb(service)
    hashed = []
    monkeypatch.setattr(accounts_service, "hash_account_password", hashed.append)
    request = AccountCreateRequestV1.model_validate(_account_payload(password="Password1234"))

    with pytest.raises(AccountServiceError) as rejected:
        service.create_account(db, request, now=db.now)

    assert rejected.value.code == "account_password_too_common"
    assert rejected.value.status_code == 400
    assert hashed == []
    assert db.commit_calls == 0
    assert db.add_attempts == []
    assert db.enrollment.state == "ACTIVE"
    assert db.enrollment.attempt_count == db.initial_enrollment["attempt_count"]
    assert db.enrollment.state_version == db.initial_enrollment["state_version"]


def test_s01_13_common_password_api_error_is_specific_and_does_not_echo_password(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = _settings()
    service = AccountService(settings)
    db = _AccountCreateTransactionDb(service)
    monkeypatch.setattr(accounts_service, "utc_now", lambda: db.now)

    class NoopLimiter:
        @staticmethod
        def check_source(**_kwargs):
            return None

        @staticmethod
        def check_email(**_kwargs):
            return None

    account_app = FastAPI()
    account_app.include_router(
        create_router(
            settings,
            sender=UnavailableOtpSender(),
            service=service,
            rate_limiter=NoopLimiter(),
        )
    )

    def dependency():
        yield db

    account_app.dependency_overrides[get_db] = dependency
    response = ASGITestClient(account_app).post(
        "/accounts", json=_account_payload(password="qwerty123456"),
    )
    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "account_password_too_common"
    assert "qwerty123456" not in response.text
    assert response.headers["cache-control"] == "no-store"
    assert db.commit_calls == 0
    assert db.enrollment.state == "ACTIVE"


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
    messages = []
    context = object()

    class FakeSmtp:
        def __init__(self, **kwargs):
            self.options = kwargs
            self.sock = None

        def connect(self, host, port):
            events.append(("connect", host, port, self.options))
            return 220, b"ready"

        def __enter__(self):
            return self

        def close(self):
            events.append("close")

        def ehlo(self):
            events.append("ehlo")

        def starttls(self, *, context):
            events.append(("starttls", context))

        def login(self, username, password):
            events.append(("login", username, password))

        def send_message(self, message):
            events.append(("send", message["To"]))
            messages.append(message)

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
    assert parsedate_to_datetime(messages[0]["Date"]).tzinfo is UTC
    assert messages[0]["Message-ID"].startswith("<")
    assert messages[0]["Message-ID"].endswith("@example.com>")


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
        def issue_email_otp(_db, _payload, *, send_quota_check=None):
            if send_quota_check is not None:
                send_quota_check()
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
    assert not any(
        getattr(route, "path", None) == "/accounts/nearby"
        and "POST" in (getattr(route, "methods", None) or ())
        for route in app.routes
    )
    assert required_field_test_access("/accounts/nearby", "POST").value == (
        "unregistered"
    )
    secured_app = FastAPI()
    secured_app.add_middleware(
        FieldTestSecurityMiddleware,
        settings=_settings(
            field_test_security_enabled=True,
            field_test_token="account-field-token-for-tests",
            admin_token="account-admin-token-for-tests",
            admin_security_enabled=False,
            admin_device_proof_enabled=False,
        ),
    )
    rejected = ASGITestClient(secured_app).post(
        "/accounts/nearby",
        headers={"x-walksafe-field-test-token": "account-field-token-for-tests"},
    )
    assert rejected.status_code == 403


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


# These diagnostics are synthetic public driver attributes, not PostgreSQL evidence.
_ACCOUNT_CREATE_EMAIL_DIAGNOSTIC = {
    "table_name": "user_accounts",
    "constraint_name": "uq_user_accounts_email_lookup_hmac",
}
_ACCOUNT_CREATE_INTEGRITY_CASES = (
    pytest.param(
        {"sqlstate": "23505", "diag": _ACCOUNT_CREATE_EMAIL_DIAGNOSTIC},
        True,
        id="named-email-unique",
    ),
    pytest.param(
        {
            "sqlstate": "23514",
            "diag": {
                "table_name": "signup_consent_receipts",
                "constraint_name": "ck_signup_consent_receipts_selections",
            },
        },
        False,
        id="receipt-check",
    ),
    pytest.param(
        {
            "sqlstate": "23505",
            "diag": {
                "table_name": "signup_consent_receipts",
                "constraint_name": "uq_signup_consent_receipts_sha256",
            },
        },
        False,
        id="receipt-unique",
    ),
    pytest.param(
        {
            "sqlstate": "23505",
            "diag": {
                "table_name": "user_accounts",
                "constraint_name": "uq_user_accounts_actor_id",
            },
        },
        False,
        id="other-account-unique",
    ),
    pytest.param(
        {
            "sqlstate": "23505",
            "diag": {
                "table_name": "account_enrollments",
                "constraint_name": "uq_user_accounts_email_lookup_hmac",
            },
        },
        False,
        id="wrong-table",
    ),
    pytest.param(
        {"sqlstate": "23503", "diag": _ACCOUNT_CREATE_EMAIL_DIAGNOSTIC},
        False,
        id="wrong-sqlstate",
    ),
    pytest.param(
        {"sqlstate": "23505"},
        False,
        id="missing-diag",
    ),
    pytest.param(
        {"diag": _ACCOUNT_CREATE_EMAIL_DIAGNOSTIC},
        False,
        id="missing-sqlstate",
    ),
    pytest.param(
        {"sqlstate": "23505", "diag": {"table_name": "user_accounts"}},
        False,
        id="missing-constraint",
    ),
    pytest.param(
        {
            "sqlstate": "23505",
            "diag": {"constraint_name": "uq_user_accounts_email_lookup_hmac"},
        },
        False,
        id="missing-table",
    ),
    pytest.param(
        {"sqlstate": "23505", "diag": None},
        False,
        id="null-diag",
    ),
    pytest.param(
        {"sqlstate": None, "diag": _ACCOUNT_CREATE_EMAIL_DIAGNOSTIC},
        False,
        id="null-sqlstate",
    ),
    pytest.param(
        {},
        False,
        id="duplicate-message-without-diagnostics",
    ),
)


def _create_account_integrity_error(
    driver_attributes: dict[str, object],
) -> IntegrityError:
    original = RuntimeError(
        "duplicate key value violates unique constraint "
        "uq_user_accounts_email_lookup_hmac; synthetic-driver-secret; "
        "person@example.com; 012345; correct horse battery staple"
    )
    for name, value in driver_attributes.items():
        if name == "diag" and isinstance(value, dict):
            value = SimpleNamespace(**value)
        setattr(original, name, value)
    return IntegrityError(
        "INSERT INTO user_accounts (...) VALUES (...)",
        {"private": "synthetic-driver-secret"},
        original,
    )


class _AccountCreateTransactionDb:
    """Observe service flow; fake rollback restoration is not a database guarantee."""

    def __init__(
        self,
        service: AccountService,
        *,
        integrity_error: IntegrityError | None = None,
        existing_account_id: uuid.UUID | None = None,
    ) -> None:
        self.service = service
        self.integrity_error = integrity_error
        self.existing_account_id = existing_account_id
        self.now = datetime(2026, 8, 29, 1, tzinfo=UTC)
        created_at = self.now - timedelta(seconds=60)
        crypto = service._crypto()
        enrollment_id = uuid.uuid4()
        email = "person@example.com"
        payload = _account_payload()
        nonce, ciphertext = crypto.encrypt(enrollment_id, email)
        self.enrollment = AccountEnrollment(
            id=enrollment_id,
            request_id="create_account_integrity_0001",
            request_hmac=crypto.request_hmac(
                email=email,
                date_of_birth=date(2000, 1, 1),
            ),
            enrollment_handle=payload["enrollment_handle"],
            email_lookup_hmac=crypto.email_lookup_hmac(email),
            email_ciphertext=ciphertext,
            email_nonce=nonce,
            email_key_version=crypto.key_version,
            otp_hmac=crypto.otp_hmac(
                handle=payload["enrollment_handle"],
                email=email,
                code=payload["otp_code"],
            ),
            state="ACTIVE",
            state_version=1,
            attempt_count=0,
            max_attempts=service.settings.account_otp_attempt_limit,
            issue_count=1,
            expires_at=self.now + timedelta(
                seconds=service.settings.account_otp_ttl_seconds,
            ),
            resend_not_before=self.now,
            consumed_at=None,
            created_at=created_at,
            updated_at=created_at,
        )
        self.initial_enrollment = self.snapshot()
        self.committed_enrollment = dict(self.initial_enrollment)
        self.binding_reads = 0
        self.scalar_calls = 0
        self.commit_calls = 0
        self.rollback_calls = 0
        self.events = []
        self.add_attempts = []
        self.pending = []
        self.committed_rows = []
        self.commit_snapshots = []
        self.rollback_snapshots = []
        self.executed = []
        self.injected_errors = []

    def snapshot(self):
        return {
            column.name: getattr(self.enrollment, column.name)
            for column in AccountEnrollment.__table__.columns
        }

    def get(self, _model, key, **_kwargs):
        assert key == 1
        self.binding_reads += 1
        return _crypto_binding(self.service)

    def scalar(self, _statement):
        self.scalar_calls += 1
        if self.scalar_calls == 1:
            return self.enrollment
        if self.scalar_calls == 2:
            return self.existing_account_id
        raise AssertionError("unexpected account creation query")

    def add(self, row):
        assert isinstance(row, (UserAccount, SignupConsentReceipt))
        self.events.append(f"add:{type(row).__name__}")
        self.add_attempts.append(row)
        self.pending.append(row)

    def commit(self):
        self.events.append("commit")
        self.commit_calls += 1
        self.commit_snapshots.append(self.snapshot())
        if self.commit_calls == 1 and self.integrity_error is not None:
            self.injected_errors.append(self.integrity_error)
            raise self.integrity_error
        self.committed_enrollment = self.snapshot()
        self.committed_rows.extend(self.pending)
        self.pending.clear()

    def rollback(self):
        self.events.append("rollback")
        self.rollback_calls += 1
        self.pending.clear()
        for name, value in self.committed_enrollment.items():
            setattr(self.enrollment, name, value)
        self.rollback_snapshots.append(self.snapshot())

    def execute(self, statement):
        self.events.append("execute")
        self.executed.append(statement)
        assert self.rollback_snapshots
        assert not self.pending
        assert self.enrollment.state == "ACTIVE"
        assert statement.table.name == "account_enrollments"
        parameters = statement.compile().params
        assert parameters["id_1"] == self.enrollment.id
        assert parameters["state_1"] == "ACTIVE"
        assert parameters["state"] == "CONSUMED"
        assert parameters["state_version_1"] == 1
        assert parameters["otp_hmac"] is None
        assert parameters["consumed_at"] == self.now
        assert parameters["updated_at"] == self.now
        self.enrollment.state = parameters["state"]
        self.enrollment.state_version += parameters["state_version_1"]
        self.enrollment.otp_hmac = parameters["otp_hmac"]
        self.enrollment.consumed_at = parameters["consumed_at"]
        self.enrollment.updated_at = parameters["updated_at"]
        return SimpleNamespace(rowcount=1)


def _assert_create_account_integrity_boundary(
    db: _AccountCreateTransactionDb,
    *,
    email_conflict: bool,
    api_rollback: bool = False,
) -> None:
    assert db.binding_reads == 1
    assert db.scalar_calls == 2
    assert len(db.add_attempts) == 2
    account, receipt = db.add_attempts
    assert isinstance(account, UserAccount)
    assert isinstance(receipt, SignupConsentReceipt)
    assert receipt.account_id == account.id
    assert db.pending == []
    assert db.committed_rows == []
    assert db.injected_errors == [db.integrity_error]
    assert db.commit_snapshots[0]["state"] == "CONSUMED"
    assert db.commit_snapshots[0]["otp_hmac"] is None
    assert db.rollback_snapshots[0] == db.initial_enrollment
    assert db.rollback_calls == 1 + int(api_rollback)
    expected_events = [
        "add:UserAccount",
        "add:SignupConsentReceipt",
        "commit",
        "rollback",
    ]
    if email_conflict:
        assert db.commit_calls == 2
        assert len(db.executed) == 1
        assert db.enrollment.state == "CONSUMED"
        assert db.enrollment.state_version == db.initial_enrollment["state_version"] + 1
        assert db.enrollment.otp_hmac is None
        assert db.enrollment.consumed_at == db.now
        assert db.enrollment.updated_at == db.now
        expected_events.extend(("execute", "commit"))
    else:
        assert db.commit_calls == 1
        assert db.executed == []
        assert db.snapshot() == db.initial_enrollment
        assert all(
            snapshot == db.initial_enrollment
            for snapshot in db.rollback_snapshots
        )
    if api_rollback:
        expected_events.append("rollback")
    assert db.events == expected_events


@pytest.mark.parametrize(
    ("driver_attributes", "email_conflict"),
    _ACCOUNT_CREATE_INTEGRITY_CASES,
)
def test_create_account_integrity_error_classification_preserves_otp_boundary(
    driver_attributes: dict[str, object],
    email_conflict: bool,
) -> None:
    service = AccountService(_settings())
    error = _create_account_integrity_error(driver_attributes)
    db = _AccountCreateTransactionDb(service, integrity_error=error)
    request = AccountCreateRequestV1.model_validate(_account_payload())

    if email_conflict:
        with pytest.raises(AccountServiceError) as rejected:
            service.create_account(db, request, now=db.now)
        assert rejected.value.status_code == 409
        assert rejected.value.code == "account_enrollment_conflict"
        assert rejected.value.message == "The enrollment cannot be used to create an account."
        assert rejected.value.__cause__ is error
    else:
        with pytest.raises(IntegrityError) as rejected:
            service.create_account(db, request, now=db.now)
        assert rejected.value is error

    _assert_create_account_integrity_boundary(db, email_conflict=email_conflict)


@pytest.mark.parametrize(
    ("driver_attributes", "email_conflict"),
    _ACCOUNT_CREATE_INTEGRITY_CASES,
)
def test_create_account_integrity_error_api_contract_is_generic_and_no_store(
    monkeypatch: pytest.MonkeyPatch,
    driver_attributes: dict[str, object],
    email_conflict: bool,
) -> None:
    settings = _settings()
    service = AccountService(settings)
    error = _create_account_integrity_error(driver_attributes)
    db = _AccountCreateTransactionDb(service, integrity_error=error)
    monkeypatch.setattr(accounts_service, "utc_now", lambda: db.now)

    class NoopLimiter:
        @staticmethod
        def check_source(**_kwargs):
            return None

        @staticmethod
        def check_email(**_kwargs):
            return None

    account_app = FastAPI()
    account_app.include_router(
        create_router(
            settings,
            sender=UnavailableOtpSender(),
            service=service,
            rate_limiter=NoopLimiter(),
        )
    )

    def dependency():
        yield db

    account_app.dependency_overrides[get_db] = dependency
    response = ASGITestClient(account_app).post("/accounts", json=_account_payload())

    if email_conflict:
        assert response.status_code == 409
        assert response.json() == {
            "detail": {
                "code": "account_enrollment_conflict",
                "message": "The enrollment cannot be used to create an account.",
            }
        }
        assert "retry-after" not in response.headers
    else:
        assert response.status_code == 503
        assert response.json() == {
            "detail": {
                "code": "account_enrollment_unavailable",
                "message": "The account service is temporarily unavailable.",
            }
        }
        assert response.headers["retry-after"] == "5"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"
    for private_value in (
        "synthetic-driver-secret",
        "person@example.com",
        "012345",
        "correct horse battery staple",
        "uq_user_accounts_email_lookup_hmac",
    ):
        assert private_value not in response.text
    _assert_create_account_integrity_boundary(
        db,
        email_conflict=email_conflict,
        api_rollback=True,
    )


def test_create_account_success_commits_account_receipt_and_otp_together() -> None:
    service = AccountService(_settings())
    db = _AccountCreateTransactionDb(service)
    request = AccountCreateRequestV1.model_validate(_account_payload())

    response = service.create_account(db, request, now=db.now)

    assert db.binding_reads == 1
    assert db.scalar_calls == 2
    assert db.commit_calls == 1
    assert db.rollback_calls == 0
    assert db.executed == []
    assert db.pending == []
    assert len(db.add_attempts) == 2
    assert db.committed_rows == db.add_attempts
    account, receipt = db.committed_rows
    assert isinstance(account, UserAccount)
    assert isinstance(receipt, SignupConsentReceipt)
    assert receipt.account_id == account.id
    assert response.actor_id == account.actor_id
    assert response.account_generation == account.account_generation == 1
    assert response.signup_receipt_sha256 == receipt.receipt_sha256
    assert db.enrollment.state == "CONSUMED"
    assert db.enrollment.state_version == db.initial_enrollment["state_version"] + 1
    assert db.enrollment.otp_hmac is None
    assert db.enrollment.consumed_at == db.now
    assert db.enrollment.updated_at == db.now
    assert db.events == ["add:UserAccount", "add:SignupConsentReceipt", "commit"]


def test_create_account_preexisting_email_conflict_keeps_existing_consumption() -> None:
    service = AccountService(_settings())
    db = _AccountCreateTransactionDb(service, existing_account_id=uuid.uuid4())
    request = AccountCreateRequestV1.model_validate(_account_payload())

    with pytest.raises(AccountServiceError) as rejected:
        service.create_account(db, request, now=db.now)

    assert rejected.value.status_code == 409
    assert rejected.value.code == "account_enrollment_conflict"
    assert db.binding_reads == 1
    assert db.scalar_calls == 2
    assert db.add_attempts == []
    assert db.committed_rows == []
    assert db.pending == []
    assert db.commit_calls == 1
    assert db.rollback_calls == 0
    assert db.executed == []
    assert db.enrollment.state == "CONSUMED"
    assert db.enrollment.state_version == db.initial_enrollment["state_version"] + 1
    assert db.enrollment.otp_hmac is None
    assert db.enrollment.consumed_at == db.now
    assert db.enrollment.updated_at == db.now
    assert db.events == ["commit"]


class _AccountCreationReplayStore:
    """Shared fake persistence, not a PostgreSQL transaction implementation."""

    def __init__(self, service: AccountService) -> None:
        seed = _AccountCreateTransactionDb(service)
        self.service = service
        self.now = seed.now
        self.binding = _crypto_binding(service)
        self.rows = {
            AccountEnrollment: {
                seed.enrollment.id: {
                    column.name: deepcopy(getattr(seed.enrollment, column.name))
                    for column in AccountEnrollment.__table__.columns
                }
            },
            UserAccount: {},
            SignupConsentReceipt: {},
        }
        self.writes = 0
        self.commits = 0

    def session(self):
        return _AccountCreationReplaySession(self)

    def snapshot(self):
        return deepcopy(self.rows)


class _AccountCreationReplaySession:
    def __init__(self, store: _AccountCreationReplayStore) -> None:
        self.store = store
        self.loaded = {}
        self.pending = []
        self.queries = []
        self.commit_error = None
        self.rollbacks = 0

    def get(self, _model, key, **_kwargs):
        assert key == 1
        return self.store.binding

    def scalar(self, statement):
        self.queries.append(statement)
        model = statement.column_descriptions[0]["entity"]
        params = statement.compile().params
        if model is AccountEnrollment:
            field, parameter = "enrollment_handle", "enrollment_handle_1"
        elif model is UserAccount:
            field, parameter = (
                ("id", "id_1")
                if "id_1" in params
                else ("email_lookup_hmac", "email_lookup_hmac_1")
            )
        elif model is SignupConsentReceipt:
            field, parameter = "account_id", "account_id_1"
        else:
            raise AssertionError("unexpected account replay query")
        assert set(params) == {parameter}
        matches = [
            row for row in self.store.rows[model].values()
            if row[field] == params[parameter]
        ]
        assert len(matches) <= 1
        if not matches:
            return None
        row = matches[0]
        if statement.column_descriptions[0]["expr"] is not model:
            assert model is UserAccount
            return row["id"]
        key = (model, row["id"])
        if key not in self.loaded or statement.get_execution_options().get(
            "populate_existing"
        ):
            self.loaded[key] = model(**deepcopy(row))
        return self.loaded[key]

    def add(self, row) -> None:
        assert isinstance(row, (UserAccount, SignupConsentReceipt))
        self.pending.append(row)

    def commit(self) -> None:
        self.store.commits += 1
        if self.commit_error is not None:
            raise self.commit_error
        for row in [*self.loaded.values(), *self.pending]:
            model = type(row)
            data = {
                column.name: deepcopy(getattr(row, column.name))
                for column in model.__table__.columns
            }
            if self.store.rows[model].get(row.id) != data:
                self.store.rows[model][row.id] = data
                self.store.writes += 1
        self.pending.clear()

    def rollback(self) -> None:
        self.rollbacks += 1
        self.loaded.clear()
        self.pending.clear()

    def execute(self, _statement):
        raise AssertionError("read-only replay must not issue an UPDATE")


def _account_creation_replay_fixture():
    service = AccountService(
        _settings(),
        password_verification_gate=AccountPasswordVerificationGate(1),
    )
    store = _AccountCreationReplayStore(service)
    payload = AccountCreateRequestV1.model_validate(_account_payload())
    created = service.create_account(store.session(), payload, now=store.now)
    return service, store, payload, created


def _assert_replay_conflict(service, store, payload) -> None:
    before = store.snapshot()
    writes, commits = store.writes, store.commits
    with pytest.raises(AccountServiceError) as rejected:
        service.create_account(store.session(), payload, now=store.now)
    assert rejected.value.code == "account_enrollment_conflict"
    assert rejected.value.message == "The enrollment cannot be used to create an account."
    assert rejected.value.status_code == 409
    assert store.snapshot() == before
    assert (store.writes, store.commits) == (writes, commits)


def test_account_creation_lost_result_replays_in_new_sessions_without_writes() -> None:
    service, store, payload, original = _account_creation_replay_fixture()
    # The first committed result is deliberately not delivered to a client.
    before = store.snapshot()
    assert store.writes == 3
    assert store.commits == 1
    for _ in range(2):
        db = store.session()
        replayed = service.create_account(db, payload, now=store.now)
        assert replayed.model_dump() == original.model_dump()
        assert set(replayed.model_dump()) == {
            "schema_version", "actor_id", "account_generation", "signup_receipt_sha256",
        }
        assert len(db.queries) == 3
        for query in db.queries[:2]:
            assert query.get_execution_options()["populate_existing"] is True
            assert "FOR UPDATE" in str(query.compile())
        assert db.queries[0].column_descriptions[0]["entity"] is AccountEnrollment
        assert db.queries[1].column_descriptions[0]["entity"] is UserAccount
        assert db.queries[2].column_descriptions[0]["entity"] is SignupConsentReceipt
    assert store.snapshot() == before
    assert store.writes == 3
    assert store.commits == 1
    assert len(store.rows[UserAccount]) == len(store.rows[SignupConsentReceipt]) == 1


def test_account_creation_replay_api_keeps_201_and_no_store(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service, store, payload, original = _account_creation_replay_fixture()
    monkeypatch.setattr(accounts_service, "utc_now", lambda: store.now)
    account_app = FastAPI()
    account_app.include_router(create_router(
        service.settings,
        service=service,
        sender=UnavailableOtpSender(),
        rate_limiter=SimpleNamespace(
            check_source=lambda **_: None,
            check_email=lambda **_: None,
        ),
    ))
    sessions = []

    def database():
        db = store.session()
        sessions.append(db)
        try:
            yield db
        finally:
            db.rollback()

    account_app.dependency_overrides[get_db] = database
    before = store.snapshot()
    for _ in range(2):
        response = ASGITestClient(account_app).post(
            "/accounts", json=payload.model_dump(mode="json"),
        )
        assert response.status_code == 201
        assert response.json() == original.model_dump(mode="json")
        assert response.headers["cache-control"] == "no-store"
        assert response.headers["pragma"] == "no-cache"
    assert len(sessions) == 2 and sessions[0] is not sessions[1]
    assert store.snapshot() == before
    assert store.writes == 3 and store.commits == 1


@pytest.mark.parametrize(
    ("section", "field"),
    (
        ("request", "otp_code"),
        ("request", "password"),
        *(("document_versions", name) for name in (
            "terms_of_service", "privacy_notice", "location_terms",
            "raw_original", "automatic_reporting", "training_reuse",
        )),
        *(("selections", name) for name in (
            "raw_original", "automatic_reporting", "training_reuse",
        )),
    ),
)
def test_account_creation_changed_request_checks_scrypt_and_binding_before_conflict(
    section: str,
    field: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service, store, payload, _ = _account_creation_replay_fixture()
    changed = payload.model_dump()
    if section == "request":
        changed[field] = "999999" if field == "otp_code" else "different valid password"
    elif section == "document_versions":
        changed["consent"][section][field] = "different.v2"
    else:
        changed["consent"][section][field] = True
    events = []
    original_verify = accounts_service.verify_account_password
    original_binding = AccountEmailCrypto.creation_binding_hmac

    def verify(*args):
        events.append("scrypt")
        return original_verify(*args)

    def binding(self, **kwargs):
        events.append("binding")
        return original_binding(self, **kwargs)

    monkeypatch.setattr(accounts_service, "verify_account_password", verify)
    monkeypatch.setattr(AccountEmailCrypto, "creation_binding_hmac", binding)
    _assert_replay_conflict(
        service, store, AccountCreateRequestV1.model_validate(changed),
    )
    assert events == ["scrypt", "binding"]


@pytest.mark.parametrize(
    "changed",
    (
        "disabled", "deleted", "account_absent", "generation", "epoch", "password_hash",
        "actor", "account_email", "receipt_missing", "receipt_sha", "receipt_schema",
        "receipt_versions", "receipt_selections", "receipt_time", "receipt_id",
        "binding_hmac", "created_account_id", "enrollment_id", "enrollment_email",
    ),
)
def test_account_creation_replay_rejects_changed_bound_state_without_writes(
    changed: str,
) -> None:
    service, store, payload, _ = _account_creation_replay_fixture()
    enrollment = next(iter(store.rows[AccountEnrollment].values()))
    account = next(iter(store.rows[UserAccount].values()))
    receipt = next(iter(store.rows[SignupConsentReceipt].values()))
    if changed in ("disabled", "deleted"):
        account["status"] = changed.upper()
    elif changed == "account_absent":
        store.rows[UserAccount].clear()
    elif changed == "generation":
        account["account_generation"] += 1
    elif changed == "epoch":
        account["auth_epoch"] += 1
    elif changed == "password_hash":
        account["password_hash"] = hash_account_password(payload.password)
    elif changed == "actor":
        account["actor_id"] = str(uuid.uuid4())
    elif changed == "account_email":
        account["email_lookup_hmac"] = "f" * 64
    elif changed == "receipt_missing":
        store.rows[SignupConsentReceipt].clear()
    elif changed == "receipt_sha":
        receipt["receipt_sha256"] = "f" * 64
    elif changed == "receipt_schema":
        receipt["schema_version"] = "different.v2"
    elif changed == "receipt_versions":
        receipt["document_versions"]["privacy_notice"] = "different.v2"
    elif changed == "receipt_selections":
        receipt["selections"]["raw_original"] = True
    elif changed == "receipt_time":
        receipt["recorded_at"] += timedelta(seconds=1)
    elif changed == "receipt_id":
        receipt["id"] = uuid.uuid4()
    elif changed == "binding_hmac":
        enrollment["creation_binding_hmac"] = "0" * 64
    elif changed == "created_account_id":
        enrollment["created_account_id"] = uuid.uuid4()
    elif changed == "enrollment_id":
        enrollment["id"] = uuid.uuid4()
    elif changed == "enrollment_email":
        enrollment["email_lookup_hmac"] = "e" * 64
    else:
        raise AssertionError("unknown bound-state fixture")
    _assert_replay_conflict(service, store, payload)


def test_account_creation_binding_cannot_be_swapped_between_successful_enrollments() -> None:
    service, store, payload, _ = _account_creation_replay_fixture()
    _, other_store, _, _ = _account_creation_replay_fixture()
    enrollment = next(iter(store.rows[AccountEnrollment].values()))
    other = next(iter(other_store.rows[AccountEnrollment].values()))
    assert enrollment["id"] != other["id"]
    enrollment["creation_binding_hmac"] = other["creation_binding_hmac"]
    _assert_replay_conflict(service, store, payload)


@pytest.mark.parametrize("consumed_kind", ("legacy", "failed_creation"))
def test_account_creation_unbound_consumed_rows_do_not_recover_by_email(
    consumed_kind: str,
) -> None:
    service, store, payload, _ = _account_creation_replay_fixture()
    enrollment = next(iter(store.rows[AccountEnrollment].values()))
    for field in (
        "created_account_id", "creation_binding_hmac", "creation_binding_key_version",
    ):
        enrollment[field] = None
    if consumed_kind == "failed_creation":
        store.rows[SignupConsentReceipt].clear()
    assert store.rows[UserAccount]
    _assert_replay_conflict(service, store, payload)


def test_account_creation_existing_email_conflict_consumption_cannot_replay() -> None:
    service, store, payload, _ = _account_creation_replay_fixture()
    seed = _AccountCreateTransactionDb(service)
    row = {
        column.name: deepcopy(getattr(seed.enrollment, column.name))
        for column in AccountEnrollment.__table__.columns
    }
    row["enrollment_handle"] = "B" * 43
    row["otp_hmac"] = service._crypto().otp_hmac(
        handle=row["enrollment_handle"], email="person@example.com", code=payload.otp_code,
    )
    store.rows[AccountEnrollment][row["id"]] = row
    duplicate = payload.model_copy(update={"enrollment_handle": row["enrollment_handle"]})
    with pytest.raises(AccountServiceError) as rejected:
        service.create_account(store.session(), duplicate, now=store.now)
    assert rejected.value.code == "account_enrollment_conflict"
    consumed = store.rows[AccountEnrollment][row["id"]]
    assert consumed["state"] == "CONSUMED"
    assert consumed["otp_hmac"] is None
    assert all(consumed[field] is None for field in (
        "created_account_id", "creation_binding_hmac", "creation_binding_key_version",
    ))
    _assert_replay_conflict(service, store, duplicate)
    assert len(store.rows[UserAccount]) == len(store.rows[SignupConsentReceipt]) == 1


@pytest.mark.parametrize("changed_key", ("binding_version", "configured_version", "fingerprint"))
def test_account_creation_replay_key_mismatch_is_existing_fail_closed_503(
    changed_key: str,
) -> None:
    service, store, payload, _ = _account_creation_replay_fixture()
    if changed_key == "binding_version":
        next(iter(store.rows[AccountEnrollment].values()))["creation_binding_key_version"] = 2
    elif changed_key == "configured_version":
        service.settings.account_email_key_version = 2
    else:
        service.settings.account_otp_hmac_key = b"P" * 32
    before = store.snapshot()
    with pytest.raises(AccountServiceError) as rejected:
        service.create_account(store.session(), payload, now=store.now)
    assert rejected.value.code == "account_crypto_key_mismatch"
    assert rejected.value.status_code == 503
    assert store.snapshot() == before
    assert store.writes == 3 and store.commits == 1


def test_account_creation_exact_replay_survives_expiry_and_policy_change_only() -> None:
    service, store, payload, original = _account_creation_replay_fixture()
    service.settings.account_signup_document_versions = {
        **service.settings.account_signup_document_versions,
        "privacy_notice": "new-policy.v2",
    }
    later = store.now + timedelta(seconds=service.settings.account_otp_ttl_seconds + 1)
    before = store.snapshot()
    assert service.create_account(store.session(), payload, now=later) == original
    _assert_replay_conflict(
        service, store, payload.model_copy(update={"otp_code": "999999"}),
    )
    assert store.snapshot() == before

    new_store = _AccountCreationReplayStore(service)
    with pytest.raises(AccountServiceError) as policy_rejected:
        service.create_account(new_store.session(), payload, now=new_store.now)
    assert policy_rejected.value.code == "signup_consent_outdated"
    changed = payload.model_dump()
    changed["consent"]["document_versions"] = dict(
        service.settings.account_signup_document_versions
    )
    with pytest.raises(AccountServiceError) as expired:
        service.create_account(
            new_store.session(),
            AccountCreateRequestV1.model_validate(changed),
            now=later,
        )
    assert expired.value.code == "account_enrollment_verification_failed"
    assert next(iter(new_store.rows[AccountEnrollment].values()))["state"] == "EXPIRED"
    assert not new_store.rows[UserAccount] and not new_store.rows[SignupConsentReceipt]


def test_account_creation_replay_reuses_existing_limiter_and_gate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service, store, payload, original = _account_creation_replay_fixture()
    events = []
    original_check = service._authentication_rate_limiter.check
    original_slot = service._password_verification_gate.slot

    def check(**kwargs):
        events.append(("rate", kwargs["email_lookup_hmac"]))
        return original_check(**kwargs)

    def slot():
        events.append(("gate", None))
        return original_slot()

    monkeypatch.setattr(service._authentication_rate_limiter, "check", check)
    monkeypatch.setattr(service._password_verification_gate, "slot", slot)
    assert service.create_account(store.session(), payload, now=store.now) == original
    enrollment = next(iter(store.rows[AccountEnrollment].values()))
    assert events == [("rate", enrollment["email_lookup_hmac"]), ("gate", None)]


@pytest.mark.parametrize("boundary", ("rate", "gate"))
def test_account_creation_replay_respects_existing_capacity_rejection_without_scrypt(
    boundary: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service, store, payload, _ = _account_creation_replay_fixture()
    before = store.snapshot()

    def unexpected_verify(*_args):
        raise AssertionError("rejected capacity must precede scrypt")

    monkeypatch.setattr(accounts_service, "verify_account_password", unexpected_verify)
    if boundary == "rate":
        def reject(**_kwargs):
            raise AccountServiceError(
                "account_authentication_rate_limited",
                "Too many account authentication requests.",
                status_code=429,
                retry_after=1,
            )
        monkeypatch.setattr(service._authentication_rate_limiter, "check", reject)
        with pytest.raises(AccountServiceError) as rejected:
            service.create_account(store.session(), payload, now=store.now)
        assert rejected.value.code == "account_authentication_rate_limited"
        assert rejected.value.status_code == 429
    else:
        with service._password_verification_gate.slot():
            with pytest.raises(AccountServiceError) as rejected:
                service.create_account(store.session(), payload, now=store.now)
        assert rejected.value.code == "account_authentication_busy"
        assert rejected.value.status_code == 503
    assert store.snapshot() == before


def test_account_creation_proof_failure_rolls_back_fake_store_and_allows_clean_retry() -> None:
    service = AccountService(_settings())
    store = _AccountCreationReplayStore(service)
    payload = AccountCreateRequestV1.model_validate(_account_payload())
    before = store.snapshot()
    db = store.session()
    error = _create_account_integrity_error({
        "sqlstate": "23514",
        "diag": {
            "table_name": "account_enrollments",
            "constraint_name": "ck_account_enrollments_creation_binding",
        },
    })
    db.commit_error = error
    with pytest.raises(IntegrityError) as rejected:
        service.create_account(db, payload, now=store.now)
    assert rejected.value is error
    assert db.rollbacks == 1
    assert store.snapshot() == before
    assert store.writes == 0 and store.commits == 1
    created = service.create_account(store.session(), payload, now=store.now)
    assert created.account_generation == 1
    assert store.writes == 3
    assert len(store.rows[UserAccount]) == len(store.rows[SignupConsentReceipt]) == 1


def test_account_creation_binding_metadata_adds_only_three_non_plaintext_columns() -> None:
    original_columns = {
        "id", "request_id", "request_hmac", "enrollment_handle", "email_lookup_hmac",
        "email_ciphertext", "email_nonce", "email_key_version", "otp_hmac", "state",
        "state_version", "attempt_count", "max_attempts", "issue_count", "expires_at",
        "resend_not_before", "consumed_at", "created_at", "updated_at",
    }
    added = {
        "created_account_id", "creation_binding_hmac", "creation_binding_key_version",
    }
    columns = AccountEnrollment.__table__.columns
    assert set(columns.keys()) == original_columns | added
    assert all(columns[name].nullable for name in added)
    assert columns["created_account_id"].type.python_type is uuid.UUID
    assert columns["creation_binding_hmac"].type.length == 64
    assert columns["creation_binding_key_version"].type.python_type is int
    constraints = {
        constraint.name: constraint for constraint in AccountEnrollment.__table__.constraints
    }
    assert list(constraints["uq_account_enrollments_created_account"].columns.keys()) == [
        "created_account_id",
    ]
    check = str(constraints["ck_account_enrollments_creation_binding"].sqltext)
    for fragment in (
        "created_account_id IS NULL", "creation_binding_hmac IS NULL",
        "creation_binding_key_version IS NULL", "created_account_id IS NOT NULL",
        "creation_binding_hmac IS NOT NULL", "creation_binding_key_version IS NOT NULL",
        "state = 'CONSUMED'", "^[0-9a-f]{64}$", "creation_binding_key_version > 0",
    ):
        assert fragment in check


def test_account_creation_replay_migration_is_additive_and_downgrade_refuses_binding_loss(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    migration = importlib.import_module(
        "backend.alembic.versions.202609210001_account_creation_replay"
    )
    assert migration.revision == "202609210001"
    assert migration.down_revision == "202609010003"
    calls = []

    def record(name):
        return lambda *args, **kwargs: calls.append((name, args, kwargs))

    monkeypatch.setattr(migration, "op", SimpleNamespace(**{
        name: record(name) for name in (
            "add_column", "create_check_constraint", "create_unique_constraint",
            "execute", "drop_constraint", "drop_column",
        )
    }))
    migration.upgrade()
    assert [name for name, _, _ in calls] == [
        "add_column", "add_column", "add_column",
        "create_check_constraint", "create_unique_constraint",
    ]
    assert [args[1].name for _, args, _ in calls[:3]] == [
        "created_account_id", "creation_binding_hmac", "creation_binding_key_version",
    ]
    assert all(args[0] == "account_enrollments" and args[1].nullable for _, args, _ in calls[:3])
    constraints = {
        constraint.name: constraint for constraint in AccountEnrollment.__table__.constraints
    }
    assert calls[3][1][2] == str(
        constraints["ck_account_enrollments_creation_binding"].sqltext
    )
    calls.clear()
    migration.downgrade()
    assert calls[0] == (
        "execute", ("LOCK TABLE public.account_enrollments IN ACCESS EXCLUSIVE MODE",), {},
    )
    assert calls[1][0] == "execute"
    guard = calls[1][1][0]
    assert "USING ERRCODE = '55000'" in guard
    for name in ("created_account_id", "creation_binding_hmac", "creation_binding_key_version"):
        assert f"{name} IS NOT NULL" in guard
    assert [name for name, _, _ in calls[2:]] == [
        "drop_constraint", "drop_constraint", "drop_column", "drop_column", "drop_column",
    ]



class _EmailOtpIssueSession(_AccountCreationReplaySession):
    """Observe query/transaction boundaries; OTP decisions remain in the service."""

    def __init__(self, store: _AccountCreationReplayStore) -> None:
        super().__init__(store)
        self.binding_reads = 0
        self.executed = []
        self.added = []
        self.rollback_observations = []

    def get(self, model, key, **kwargs):
        self.binding_reads += 1
        return super().get(model, key, **kwargs)

    def _load_enrollment(self, row, statement):
        key = (AccountEnrollment, row["id"])
        if key not in self.loaded or statement.get_execution_options().get(
            "populate_existing"
        ):
            self.loaded[key] = AccountEnrollment(**deepcopy(row))
        return self.loaded[key]

    def scalar(self, statement):
        model = statement.column_descriptions[0]["entity"]
        params = statement.compile().params
        if model is not AccountEnrollment or "enrollment_handle_1" in params:
            return super().scalar(statement)
        self.queries.append(statement)
        rows = self.store.rows[AccountEnrollment].values()
        if "request_id_1" in params:
            assert set(params) == {"request_id_1"}
            matches = [row for row in rows if row["request_id"] == params["request_id_1"]]
            assert len(matches) <= 1
        else:
            assert "email_lookup_hmac_1" in params
            assert "ORDER BY account_enrollments.created_at DESC" in str(statement)
            matches = sorted(
                (row for row in rows
                 if row["email_lookup_hmac"] == params["email_lookup_hmac_1"]),
                key=lambda row: row["created_at"],
                reverse=True,
            )[:1]
        return self._load_enrollment(matches[0], statement) if matches else None

    def scalars(self, statement):
        self.queries.append(statement)
        assert statement.column_descriptions[0]["entity"] is AccountEnrollment
        params = statement.compile().params
        assert set(params) == {"email_lookup_hmac_1", "state_1"}
        rows = sorted(
            (row for row in self.store.rows[AccountEnrollment].values()
             if row["email_lookup_hmac"] == params["email_lookup_hmac_1"]
             and row["state"] in params["state_1"]),
            key=lambda row: (row["created_at"], str(row["id"])),
        )
        loaded = [self._load_enrollment(row, statement) for row in rows]
        return SimpleNamespace(all=lambda: loaded)

    def execute(self, statement, parameters=None):
        sql = str(statement)
        assert sql == "SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"
        assert parameters is not None and set(parameters) == {"lock_key"}
        self.executed.append((sql, dict(parameters)))
        return SimpleNamespace()

    def add(self, row) -> None:
        self.added.append(row)
        if isinstance(row, AccountEnrollment):
            self.pending.append(row)
        else:
            super().add(row)

    def rollback(self) -> None:
        self.rollback_observations.append(_otp_issue_loaded_snapshot(self))
        super().rollback()


def _otp_issue_loaded_snapshot(db):
    return {
        (type(row), row.id): {
            column.name: deepcopy(getattr(row, column.name))
            for column in type(row).__table__.columns
        }
        for row in db.loaded.values()
    }


def _otp_issue_fixture(state: str):
    service = AccountService(_settings())
    store = _AccountCreationReplayStore(service)
    row = next(iter(store.rows[AccountEnrollment].values()))
    row["state"] = state
    row["updated_at"] = store.now
    row["resend_not_before"] = store.now
    row["consumed_at"] = store.now if state == "CONSUMED" else None
    row["attempt_count"] = row["max_attempts"] if state == "EXHAUSTED" else 0
    if state in {"EXPIRED", "EXHAUSTED", "CONSUMED", "DELIVERY_FAILED"}:
        row["otp_hmac"] = None
    payload = EmailOtpEnrollmentRequestV1.model_validate({
        "schema_version": "walksafe.account-enrollment-email-otp.v1",
        "email": "person@example.com",
        "date_of_birth": "2000-01-01",
        "request_id": row["request_id"],
    })
    assert row["request_hmac"] == service._crypto().request_hmac(
        email=payload.email, date_of_birth=payload.date_of_birth,
    )
    return service, store, payload, _EmailOtpIssueSession(store)


def _otp_issue_api_response(
    service, store, db, payload, monkeypatch, *, now=None, blocked_limiter=None,
):
    observed_at = now or store.now
    monkeypatch.setattr(accounts_service, "utc_now", lambda: observed_at)
    sent = []
    limit_checks = []

    def check_source(**_kwargs):
        limit_checks.append("source")
        if blocked_limiter == "source":
            raise AccountServiceError(
                "account_enrollment_rate_limited",
                "Too many account enrollment requests.",
                status_code=429,
                retry_after=1,
            )

    def check_email(**_kwargs):
        limit_checks.append("email")
        if blocked_limiter == "email":
            raise AccountServiceError(
                "account_enrollment_rate_limited",
                "Too many account enrollment requests.",
                status_code=429,
                retry_after=1,
            )

    def send(**kwargs):
        sent.append(kwargs)

    account_app = FastAPI()
    account_app.include_router(create_router(
        service.settings,
        service=service,
        sender=SimpleNamespace(send=send),
        rate_limiter=SimpleNamespace(check_source=check_source, check_email=check_email),
    ))

    def database():
        yield db

    account_app.dependency_overrides[get_db] = database
    response = ASGITestClient(account_app).post(
        "/account-enrollments/email-otp",
        json=payload.model_dump(mode="json"),
        headers={"x-walksafe-client-ip": "192.0.2.10"},
    )
    return response, sent, limit_checks


def test_active_otp_retry_does_not_spend_email_send_quota_but_sends_do(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service, store, payload, _ = _otp_issue_fixture("ACTIVE")
    observed_at = [store.now]
    monkeypatch.setattr(accounts_service, "utc_now", lambda: observed_at[0])
    # The synthetic issue session cannot execute the production delivery UPDATE.
    monkeypatch.setattr(service, "mark_delivery_sent", lambda _db, _delivery: None)
    limiter = AccountEnrollmentRateLimiter(SimpleNamespace(
        actor_rate_limit_store="memory",
        account_enrollment_global_limit=20,
        account_enrollment_global_window_seconds=3600,
        account_enrollment_ip_limit=20,
        account_enrollment_ip_window_seconds=3600,
        account_enrollment_email_limit=2,
        account_enrollment_email_window_seconds=3600,
    ))
    sent = []
    account_app = FastAPI()
    account_app.include_router(create_router(
        service.settings,
        service=service,
        sender=SimpleNamespace(send=lambda **kwargs: sent.append(kwargs)),
        rate_limiter=limiter,
    ))

    def database():
        yield _EmailOtpIssueSession(store)

    account_app.dependency_overrides[get_db] = database
    client = ASGITestClient(account_app)

    def post(request_payload):
        return client.post(
            "/account-enrollments/email-otp",
            json=request_payload.model_dump(mode="json"),
            headers={"x-walksafe-client-ip": "192.0.2.10"},
        )

    for _ in range(3):
        response = post(payload)
        assert response.status_code == 202
        assert response.json()["enrollment_handle"] == next(iter(
            store.rows[AccountEnrollment].values()
        ))["enrollment_handle"]
        assert response.headers["cache-control"] == "no-store"
    assert sent == []

    for index in (2, 3):
        observed_at[0] += timedelta(seconds=61)
        response = post(payload.model_copy(update={
            "request_id": f"request_identifier_{index:04d}",
        }))
        assert response.status_code == 202
    assert len(sent) == 2

    observed_at[0] += timedelta(seconds=61)
    limited = post(payload.model_copy(update={
        "request_id": "request_identifier_0004",
    }))
    assert limited.status_code == 429
    assert limited.json()["detail"]["code"] == "account_enrollment_rate_limited"
    assert limited.headers["cache-control"] == "no-store"
    assert len(sent) == 2
    assert len(store.rows[AccountEnrollment]) == 3

    other_email = post(payload.model_copy(update={
        "email": "other@example.com",
        "request_id": "request_identifier_0005",
    }))
    assert other_email.status_code == 202
    assert len(sent) == 3


def _assert_otp_issue_read_only(
    store, db, before, activity, *, commits=0, api=False,
) -> None:
    assert store.snapshot() == before
    assert (store.writes, store.commits) == (activity[0], activity[1] + commits)
    assert db.added == [] and db.pending == []
    assert db.binding_reads == 1
    assert len(db.executed) == 1  # Existing advisory lock, no extra UPDATE.
    assert db.executed[0][1] == {
        "lock_key": "walksafe-account-enrollment-email-v1:"
        + next(iter(before[AccountEnrollment].values()))["email_lookup_hmac"],
    }
    assert len(db.queries) == 1
    query = db.queries[0]
    assert query.get_execution_options()["populate_existing"] is True
    assert "FOR UPDATE" in str(query.compile())
    assert db.rollbacks == (1 if api else 0)
    observed = [*db.rollback_observations, _otp_issue_loaded_snapshot(db)]
    for snapshot in observed:
        for (model, row_id), row in snapshot.items():
            assert row == before[model][row_id]


@pytest.mark.parametrize("state", ("EXPIRED", "EXHAUSTED", "CONSUMED"))
@pytest.mark.parametrize("via_api", (False, True))
def test_terminal_otp_request_replay_is_fixed_conflict_without_mutation_or_delivery(
    state: str,
    via_api: bool,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service, store, payload, db = _otp_issue_fixture(state)
    row = next(iter(store.rows[AccountEnrollment].values()))
    assert row["expires_at"] > store.now  # Persisted EXPIRED wins over future expiry.
    before = store.snapshot()
    activity = (store.writes, store.commits)
    if via_api:
        response, sent, checks = _otp_issue_api_response(
            service, store, db, payload, monkeypatch,
        )
        assert response.status_code == 409
        assert response.json() == {"detail": {
            "code": "account_enrollment_idempotency_conflict",
            "message": "The enrollment request could not be accepted.",
        }}
        assert response.headers["cache-control"] == "no-store"
        assert response.headers["pragma"] == "no-cache"
        assert "retry-after" not in response.headers
        assert row["enrollment_handle"] not in response.text
        assert "enrollment_handle" not in response.text
        assert sent == [] and checks == ["source"]
    else:
        with pytest.raises(AccountServiceError) as rejected:
            service.issue_email_otp(db, payload, now=store.now)
        assert rejected.value.code == "account_enrollment_idempotency_conflict"
        assert rejected.value.message == "The enrollment request could not be accepted."
        assert rejected.value.status_code == 409
        assert rejected.value.retry_after is None
    _assert_otp_issue_read_only(store, db, before, activity, api=via_api)


@pytest.mark.parametrize("expiry_offset", (-1, 0, 1))
@pytest.mark.parametrize("via_api", (False, True))
def test_active_otp_request_replay_respects_exact_expiry_without_state_transition(
    expiry_offset: int,
    via_api: bool,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service, store, payload, db = _otp_issue_fixture("ACTIVE")
    row = next(iter(store.rows[AccountEnrollment].values()))
    row["expires_at"] = store.now + timedelta(seconds=expiry_offset)
    row["resend_not_before"] = row["created_at"]
    before = store.snapshot()
    activity = (store.writes, store.commits)
    expected = EmailOtpEnrollmentResponseV1(
        schema_version="walksafe.account-enrollment-email-otp-response.v1",
        enrollment_handle=row["enrollment_handle"],
        expires_at=row["expires_at"],
        resend_available_at=row["resend_not_before"],
    )
    if via_api:
        response, sent, checks = _otp_issue_api_response(
            service, store, db, payload, monkeypatch,
        )
        assert sent == [] and checks == ["source"]
        assert response.headers["cache-control"] == "no-store"
        assert response.headers["pragma"] == "no-cache"
        assert "retry-after" not in response.headers
        if expiry_offset > 0:
            assert response.status_code == 202
            assert response.json() == expected.model_dump(mode="json")
        else:
            assert response.status_code == 409
            assert response.json() == {"detail": {
                "code": "account_enrollment_idempotency_conflict",
                "message": "The enrollment request could not be accepted.",
            }}
            assert row["enrollment_handle"] not in response.text
    elif expiry_offset > 0:
        issued = service.issue_email_otp(db, payload, now=store.now)
        assert issued.response == expected
        assert issued.delivery is None
    else:
        with pytest.raises(AccountServiceError) as rejected:
            service.issue_email_otp(db, payload, now=store.now)
        assert rejected.value.code == "account_enrollment_idempotency_conflict"
        assert rejected.value.message == "The enrollment request could not be accepted."
        assert rejected.value.status_code == 409
        assert rejected.value.retry_after is None
    _assert_otp_issue_read_only(
        store, db, before, activity,
        commits=1 if expiry_offset > 0 else 0,
        api=via_api and expiry_offset <= 0,
    )
    assert store.rows[AccountEnrollment][row["id"]]["state"] == "ACTIVE"


@pytest.mark.parametrize("state", ("EXPIRED", "EXHAUSTED", "CONSUMED", "ACTIVE"))
def test_otp_request_payload_mismatch_keeps_precedence_over_terminal_or_expiry(
    state: str,
) -> None:
    service, store, payload, db = _otp_issue_fixture(state)
    row = next(iter(store.rows[AccountEnrollment].values()))
    row["expires_at"] = store.now
    changed = payload.model_copy(update={"date_of_birth": date(2000, 1, 2)})
    before = store.snapshot()
    activity = (store.writes, store.commits)
    with pytest.raises(AccountServiceError) as rejected:
        service.issue_email_otp(db, changed, now=store.now)
    assert rejected.value.code == "account_enrollment_idempotency_conflict"
    assert rejected.value.message == "The enrollment request identifier is already bound."
    assert rejected.value.status_code == 409
    assert rejected.value.retry_after is None
    _assert_otp_issue_read_only(store, db, before, activity)


@pytest.mark.parametrize("lease_elapsed", (False, True))
def test_pending_otp_request_keeps_delivery_lease_and_rejects_ambiguous_retry(
    lease_elapsed: bool,
) -> None:
    service, store, payload, db = _otp_issue_fixture("PENDING_DELIVERY")
    row = next(iter(store.rows[AccountEnrollment].values()))
    row["attempt_count"] = 2
    row["expires_at"] = store.now + timedelta(seconds=1)
    lease = service.settings.account_otp_delivery_lease_seconds
    observed_at = store.now + timedelta(seconds=lease if lease_elapsed else lease - 1)
    assert row["expires_at"] < observed_at
    before = store.snapshot()
    activity = (store.writes, store.commits)
    if not lease_elapsed:
        with pytest.raises(AccountServiceError) as rejected:
            service.issue_email_otp(db, payload, now=observed_at)
        assert rejected.value.code == "account_enrollment_in_progress"
        assert rejected.value.status_code == 409
        assert rejected.value.retry_after == 1
        _assert_otp_issue_read_only(store, db, before, activity)
        return
    with pytest.raises(AccountServiceError) as rejected:
        service.issue_email_otp(db, payload, now=observed_at)
    assert rejected.value.code == "account_enrollment_idempotency_conflict"
    assert rejected.value.status_code == 409
    assert rejected.value.retry_after is None
    _assert_otp_issue_read_only(store, db, before, activity)


@pytest.mark.parametrize("cooldown_elapsed", (False, True))
def test_failed_otp_delivery_keeps_cooldown_and_requires_new_request_id(
    cooldown_elapsed: bool,
) -> None:
    service, store, payload, db = _otp_issue_fixture("DELIVERY_FAILED")
    row = next(iter(store.rows[AccountEnrollment].values()))
    row["attempt_count"] = 2
    row["expires_at"] = store.now + timedelta(seconds=1)
    cooldown = service.settings.account_otp_resend_cooldown_seconds
    row["resend_not_before"] = store.now + timedelta(seconds=cooldown)
    observed_at = row["resend_not_before"] - timedelta(seconds=0 if cooldown_elapsed else 1)
    assert row["expires_at"] < observed_at
    before = store.snapshot()
    activity = (store.writes, store.commits)
    if not cooldown_elapsed:
        with pytest.raises(AccountServiceError) as rejected:
            service.issue_email_otp(db, payload, now=observed_at)
        assert rejected.value.code == "account_enrollment_rate_limited"
        assert rejected.value.status_code == 429
        assert rejected.value.retry_after == 1
        _assert_otp_issue_read_only(store, db, before, activity)
        return
    with pytest.raises(AccountServiceError) as rejected:
        service.issue_email_otp(db, payload, now=observed_at)
    assert rejected.value.code == "account_enrollment_idempotency_conflict"
    assert rejected.value.status_code == 409
    assert rejected.value.retry_after is None
    _assert_otp_issue_read_only(store, db, before, activity)


def test_post_smtp_mark_commit_failure_cannot_replace_otp_on_lease_retry() -> None:
    service, store, prior_payload, db = _otp_issue_fixture("DELIVERY_FAILED")
    payload = prior_payload.model_copy(update={"request_id": "request_identifier_0002"})
    assert payload.request_id != prior_payload.request_id
    issued = service.issue_email_otp(db, payload, now=store.now)
    delivery = issued.delivery
    assert delivery is not None
    accepted = []
    sender = SimpleNamespace(send=lambda **kwargs: accepted.append(kwargs))
    sender.send(
        email=delivery.email,
        code=delivery.code,
        expires_in_seconds=service.settings.account_otp_ttl_seconds,
    )

    class FailedMarkCommit:
        def __init__(self):
            self.before = deepcopy(store.rows[AccountEnrollment][delivery.enrollment_id])
            self.updated = False
            self.rolled_back = False

        def execute(self, statement):
            assert "UPDATE account_enrollments" in str(statement)
            row = store.rows[AccountEnrollment][delivery.enrollment_id]
            assert row["state"] == "PENDING_DELIVERY"
            assert row["issue_count"] == delivery.issue_count
            row["state"] = "ACTIVE"
            row["state_version"] += 1
            row["updated_at"] = store.now
            self.updated = True
            return SimpleNamespace(rowcount=1)

        def commit(self):
            assert self.updated
            raise RuntimeError("simulated mark-delivery commit failure")

        def rollback(self):
            assert self.updated
            store.rows[AccountEnrollment][delivery.enrollment_id] = deepcopy(self.before)
            self.rolled_back = True

    mark_db = FailedMarkCommit()
    with pytest.raises(RuntimeError, match="simulated mark-delivery commit failure"):
        service.mark_delivery_sent(mark_db, delivery, now=store.now)
    assert store.rows[AccountEnrollment][delivery.enrollment_id]["state"] == "ACTIVE"
    mark_db.rollback()
    assert mark_db.rolled_back
    assert store.rows[AccountEnrollment][delivery.enrollment_id] == mark_db.before
    assert accepted == [{
        "email": delivery.email,
        "code": delivery.code,
        "expires_in_seconds": service.settings.account_otp_ttl_seconds,
    }]

    before = store.snapshot()
    row = before[AccountEnrollment][delivery.enrollment_id]
    assert row["state"] == "PENDING_DELIVERY"
    assert row["otp_hmac"] == service._crypto().otp_hmac(
        handle=row["enrollment_handle"], email=payload.email, code=delivery.code,
    )
    activity = (store.writes, store.commits)
    retry_db = _EmailOtpIssueSession(store)
    send_checks = []
    lease_expired_at = store.now + timedelta(
        seconds=service.settings.account_otp_delivery_lease_seconds,
    )
    with pytest.raises(AccountServiceError) as rejected:
        service.issue_email_otp(
            retry_db, payload, now=lease_expired_at,
            send_quota_check=lambda: send_checks.append(True),
        )
    assert rejected.value.code == "account_enrollment_idempotency_conflict"
    assert rejected.value.status_code == 409
    assert rejected.value.retry_after is None
    assert send_checks == []
    _assert_otp_issue_read_only(store, retry_db, before, activity)


@pytest.mark.parametrize("state", ("EXPIRED", "EXHAUSTED", "CONSUMED"))
def test_terminal_otp_requires_explicit_new_request_id_for_normal_new_issue(
    state: str,
) -> None:
    service, store, payload, db = _otp_issue_fixture(state)
    before = store.snapshot()
    old = next(iter(before[AccountEnrollment].values()))
    activity = (store.writes, store.commits)
    with pytest.raises(AccountServiceError) as rejected:
        service.issue_email_otp(db, payload, now=store.now)
    assert rejected.value.code == "account_enrollment_idempotency_conflict"
    _assert_otp_issue_read_only(store, db, before, activity)
    assert payload.request_id == old["request_id"]

    # A separate caller action supplies the new wire ID; the error did not issue it.
    next_payload = payload.model_copy(update={"request_id": f"explicit_restart_{uuid.uuid4().hex}"})
    assert next_payload.request_id != payload.request_id
    next_db = _EmailOtpIssueSession(store)
    observed_at = store.now + timedelta(
        seconds=service.settings.account_otp_resend_cooldown_seconds + 1,
    )
    issued = service.issue_email_otp(next_db, next_payload, now=observed_at)
    assert issued.delivery is not None
    assert len(store.rows[AccountEnrollment]) == 2
    assert store.rows[AccountEnrollment][old["id"]] == old
    new = store.rows[AccountEnrollment][issued.delivery.enrollment_id]
    assert new["request_id"] == next_payload.request_id
    assert new["state"] == "PENDING_DELIVERY"
    assert new["enrollment_handle"] == issued.response.enrollment_handle
    assert new["enrollment_handle"] != old["enrollment_handle"]
    assert new["issue_count"] == 1 and new["attempt_count"] == 0
    assert new["otp_hmac"] == service._crypto().otp_hmac(
        handle=new["enrollment_handle"], email=payload.email, code=issued.delivery.code,
    )
    assert all(new[name] is None for name in (
        "created_account_id", "creation_binding_hmac", "creation_binding_key_version",
    ))
    assert len(next_db.added) == 1 and len(next_db.executed) == 1
    assert (store.writes, store.commits) == (activity[0] + 1, activity[1] + 1)


def test_consumed_otp_issue_conflict_preserves_successful_exact_account_replay() -> None:
    service, store, create_payload, original = _account_creation_replay_fixture()
    before = store.snapshot()
    row = next(iter(before[AccountEnrollment].values()))
    activity = (store.writes, store.commits)
    assert row["created_account_id"] is not None
    assert row["creation_binding_hmac"] is not None
    assert row["creation_binding_key_version"] is not None
    otp_payload = EmailOtpEnrollmentRequestV1.model_validate({
        "schema_version": "walksafe.account-enrollment-email-otp.v1",
        "email": "person@example.com",
        "date_of_birth": "2000-01-01",
        "request_id": row["request_id"],
    })
    db = _EmailOtpIssueSession(store)
    with pytest.raises(AccountServiceError) as rejected:
        service.issue_email_otp(db, otp_payload, now=store.now)
    assert rejected.value.code == "account_enrollment_idempotency_conflict"
    assert rejected.value.message == "The enrollment request could not be accepted."
    _assert_otp_issue_read_only(store, db, before, activity)
    replayed = service.create_account(store.session(), create_payload, now=store.now)
    assert replayed.model_dump() == original.model_dump()
    assert set(replayed.model_dump()) == {
        "schema_version", "actor_id", "account_generation", "signup_receipt_sha256",
    }
    assert store.snapshot() == before
    assert (store.writes, store.commits) == activity


@pytest.mark.parametrize("guard", ("age", "crypto"))
def test_terminal_otp_request_keeps_age_and_crypto_guards_before_status_lookup(
    guard: str,
) -> None:
    service, store, payload, db = _otp_issue_fixture("CONSUMED")
    if guard == "age":
        payload = payload.model_copy(update={"date_of_birth": date(2020, 1, 1)})
    else:
        service.settings.account_otp_hmac_key = b"P" * 32
    before = store.snapshot()
    activity = (store.writes, store.commits)
    with pytest.raises(AccountServiceError) as rejected:
        service.issue_email_otp(db, payload, now=store.now)
    assert rejected.value.code == (
        "account_enrollment_not_allowed" if guard == "age" else "account_crypto_key_mismatch"
    )
    assert rejected.value.status_code == (400 if guard == "age" else 503)
    assert db.binding_reads == (0 if guard == "age" else 1)
    assert db.queries == [] and db.executed == [] and db.added == []
    assert store.snapshot() == before
    assert (store.writes, store.commits) == activity


def test_terminal_otp_request_keeps_source_rate_limit_before_service(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service, store, payload, db = _otp_issue_fixture("CONSUMED")
    before = store.snapshot()
    activity = (store.writes, store.commits)
    response, sent, checks = _otp_issue_api_response(
        service, store, db, payload, monkeypatch, blocked_limiter="source",
    )
    assert response.status_code == 429
    assert response.json() == {"detail": {
        "code": "account_enrollment_rate_limited",
        "message": "Too many account enrollment requests.",
    }}
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"
    assert response.headers["retry-after"] == "1"
    assert checks == ["source"]
    assert sent == []
    assert db.binding_reads == 0
    assert db.queries == [] and db.executed == [] and db.added == []
    assert db.rollbacks == 1
    assert store.snapshot() == before
    assert (store.writes, store.commits) == activity



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
