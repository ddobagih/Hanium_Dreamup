"""S01-14: old and new key-set instances coexisting on one account database.

Each account key purpose is changed on its own in a second instance while the
original instance keeps serving the same PostgreSQL store:
- OTP HMAC key only: the new instance still logs an existing account in, its
  OTP issuance and account creation fail closed (503), readiness stays ready
  and reports the OTP binding mismatch; the old instance keeps
  issuing OTPs and creating accounts meanwhile.
- lookup HMAC key, encryption key or key version: the new instance fails
  closed (503, not ready); the old instance is unaffected.
The binding row is never rewritten by a mismatching instance.
"""

from __future__ import annotations

from datetime import timedelta
import os
from types import SimpleNamespace

import pytest

from backend.app.account_schemas import (
    AccountAuthenticateRequestV1,
    AccountCreateRequestV1,
    EmailOtpEnrollmentRequestV1,
)
from backend.app.services import health_readiness
from backend.app.models import AccountCryptoKeyBinding
from backend.app.services.accounts import AccountService, AccountServiceError, utc_now
from test_accounts import _settings
from test_privacy_sweep_w23_postgres_integration import _disposable_database


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

_PASSWORD = "coexistence correct horse"


def _instance(**overrides) -> SimpleNamespace:
    return _settings(walksafe_environment="test", **overrides)


def _issue(service: AccountService, SessionFactory, email: str, request_id: str, now):
    enrollment = EmailOtpEnrollmentRequestV1.model_validate(
        {
            "schema_version": "walksafe.account-enrollment-email-otp.v1",
            "email": email,
            "date_of_birth": "2000-01-01",
            "request_id": request_id,
        }
    )
    with SessionFactory() as db:
        issued = service.issue_email_otp(db, enrollment, now=now)
    with SessionFactory() as db:
        service.mark_delivery_sent(db, issued.delivery, now=now)
    return issued


def _create(service: AccountService, SessionFactory, settings, issued, now):
    payload = AccountCreateRequestV1.model_validate(
        {
            "schema_version": "walksafe.account-create.v1",
            "enrollment_handle": issued.response.enrollment_handle,
            "otp_code": issued.delivery.code,
            "password": _PASSWORD,
            "consent": {
                "schema_version": "walksafe.signup-consent.v1",
                "document_versions": dict(settings.account_signup_document_versions),
                "selections": {
                    "terms_of_service": True,
                    "privacy_notice": True,
                    "location_terms": True,
                },
            },
        }
    )
    with SessionFactory() as db:
        return service.create_account(db, payload, now=now)


def _login(service: AccountService, SessionFactory, email: str):
    with SessionFactory() as db:
        return service.authenticate(
            db,
            AccountAuthenticateRequestV1(
                schema_version="walksafe.account-authenticate.v1",
                email=email,
                password=_PASSWORD,
            ),
        )


def _mismatch(callback) -> tuple[str, int]:
    with pytest.raises(AccountServiceError) as rejected:
        callback()
    return rejected.value.code, rejected.value.status_code


def test_s01_14_each_key_changed_alone_while_the_old_instance_keeps_serving(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    old_settings = _instance()
    new_otp_settings = _instance(account_otp_hmac_key=b"Q" * 32)
    changed_instances = {
        "lookup": _instance(account_email_lookup_hmac_key=b"M" * 32),
        "encryption": _instance(account_email_encryption_key=b"F" * 32),
        "version": _instance(account_email_key_version=2),
    }
    old = AccountService(old_settings)
    new_otp = AccountService(new_otp_settings)
    now = utc_now()
    step = timedelta(seconds=old_settings.account_otp_resend_cooldown_seconds + 1)
    with _disposable_database(monkeypatch) as (_engine, SessionFactory):
        monkeypatch.setattr(health_readiness, "SessionLocal", SessionFactory)
        first_email = "coexist-first@example.com"
        created = _create(
            old, SessionFactory, old_settings,
            _issue(old, SessionFactory, first_email, "coexist_first_0001", now), now,
        )
        with SessionFactory() as db:
            binding = db.get(AccountCryptoKeyBinding, 1)
            fingerprints = (
                binding.key_version,
                binding.encryption_key_fingerprint,
                binding.lookup_hmac_key_fingerprint,
                binding.otp_hmac_key_fingerprint,
            )

        # OTP-only rotation: login works on both instances.
        assert _login(new_otp, SessionFactory, first_email).actor_id == created.actor_id
        assert _login(old, SessionFactory, first_email).actor_id == created.actor_id
        assert _mismatch(
            lambda: _issue(new_otp, SessionFactory, "coexist-new@example.com", "coexist_new_0001", now)
        ) == ("account_crypto_key_mismatch", 503)
        # The old instance keeps enrolling while the new one is fail-closed.
        second_email = "coexist-second@example.com"
        issued_by_old = _issue(old, SessionFactory, second_email, "coexist_second_0001", now + step)
        assert _mismatch(
            lambda: _create(new_otp, SessionFactory, new_otp_settings, issued_by_old, now + step)
        ) == ("account_crypto_key_mismatch", 503)
        second = _create(old, SessionFactory, old_settings, issued_by_old, now + step)
        assert _login(new_otp, SessionFactory, second_email).actor_id == second.actor_id

        assert health_readiness._account_crypto_binding_readiness(old_settings)["ready"] is True
        new_otp_ready = health_readiness._account_crypto_binding_readiness(new_otp_settings)
        assert new_otp_ready["ready"] is True
        assert new_otp_ready.get("otp_hmac_binding") == "mismatch"

        # Lookup, encryption or version rotation alone: the new instance stops.
        for name, settings in changed_instances.items():
            service = AccountService(settings)
            assert _mismatch(lambda: _login(service, SessionFactory, first_email)) == (
                "account_crypto_key_mismatch", 503,
            ), name
            assert health_readiness._account_crypto_binding_readiness(settings) == {
                "ready": False,
                "reason": "account_crypto_binding_mismatch",
            }, name
            assert _login(old, SessionFactory, first_email).actor_id == created.actor_id

        with SessionFactory() as db:
            unchanged = db.get(AccountCryptoKeyBinding, 1)
            assert (
                unchanged.key_version,
                unchanged.encryption_key_fingerprint,
                unchanged.lookup_hmac_key_fingerprint,
                unchanged.otp_hmac_key_fingerprint,
            ) == fingerprints
