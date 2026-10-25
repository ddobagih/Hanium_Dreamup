"""S01-14: account key binding is verified per purpose.

Changing only the OTP HMAC key must fail OTP issuance/verification closed but
must not turn password login into 503. Lookup-HMAC, encryption-key, and
key-version changes still fail login closed.
"""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from backend.app.account_schemas import (
    AccountAuthenticateRequestV1,
    AccountCreateRequestV1,
    EmailOtpEnrollmentRequestV1,
)
import backend.app.services.health_readiness as health_api
from backend.app.services.accounts import (
    AccountEmailCrypto,
    AccountService,
    AccountServiceError,
    account_crypto_binding_mismatched_purposes,
)
from test_accounts import _account_payload, _settings


def _binding_for(**keys: bytes) -> SimpleNamespace:
    crypto = AccountEmailCrypto(
        encryption_key=keys.get("encryption", b"E" * 32),
        lookup_hmac_key=keys.get("lookup", b"L" * 32),
        otp_hmac_key=keys.get("otp", b"O" * 32),
        key_version=int(keys.get("version", 1)),
    )
    encryption, lookup, otp = crypto.key_fingerprints
    return SimpleNamespace(
        key_version=crypto.key_version,
        encryption_key_fingerprint=encryption,
        lookup_hmac_key_fingerprint=lookup,
        otp_hmac_key_fingerprint=otp,
    )


class _BindingOnlyDb:
    """Returns the stored binding; any account lookup finds nothing."""

    def __init__(self, binding: SimpleNamespace) -> None:
        self.binding = binding

    def get(self, _model, _key, **_kwargs):
        return self.binding

    def scalar(self, *_args, **_kwargs):
        return None


def _authenticate(service: AccountService, db: _BindingOnlyDb) -> AccountServiceError:
    with pytest.raises(AccountServiceError) as rejected:
        service.authenticate(
            db,
            AccountAuthenticateRequestV1(
                schema_version="walksafe.account-authenticate.v1",
                email="person@example.com",
                password="long enough password",
            ),
        )
    return rejected.value


def _issue(service: AccountService, db: _BindingOnlyDb) -> AccountServiceError:
    with pytest.raises(AccountServiceError) as rejected:
        service.issue_email_otp(
            db,
            EmailOtpEnrollmentRequestV1(
                schema_version="walksafe.account-enrollment-email-otp.v1",
                email="person@example.com",
                date_of_birth="2000-01-01",
                request_id="request_identifier_0001",
            ),
        )
    return rejected.value


def _create(service: AccountService, db: _BindingOnlyDb) -> AccountServiceError:
    with pytest.raises(AccountServiceError) as rejected:
        service.create_account(
            db,
            AccountCreateRequestV1.model_validate(_account_payload()),
        )
    return rejected.value


def test_s01_14_otp_key_change_keeps_password_login_but_fails_otp_closed() -> None:
    # Binding was created with OTP key "Q"; the service now runs with OTP key "O".
    db = _BindingOnlyDb(_binding_for(otp=b"Q" * 32))
    service = AccountService(_settings())
    login = _authenticate(service, db)
    # Login reached credential verification (no such account) instead of 503.
    assert (login.code, login.status_code) == ("account_authentication_failed", 401)
    issue = _issue(service, db)
    assert (issue.code, issue.status_code) == ("account_crypto_key_mismatch", 503)
    create = _create(service, db)
    assert (create.code, create.status_code) == ("account_crypto_key_mismatch", 503)


@pytest.mark.parametrize(
    "changed",
    (
        {"lookup": b"M" * 32},
        {"encryption": b"F" * 32},
        {"version": 2},
    ),
    ids=("lookup_hmac", "encryption", "key_version"),
)
def test_s01_14_login_keys_still_fail_closed(changed: dict[str, object]) -> None:
    db = _BindingOnlyDb(_binding_for(**changed))
    service = AccountService(_settings())
    login = _authenticate(service, db)
    assert (login.code, login.status_code) == ("account_crypto_key_mismatch", 503)
    issue = _issue(service, db)
    assert (issue.code, issue.status_code) == ("account_crypto_key_mismatch", 503)


def test_s01_14_mismatched_purposes_are_reported_per_key() -> None:
    crypto = AccountService(_settings())._crypto()
    assert account_crypto_binding_mismatched_purposes(_binding_for(), crypto) == frozenset()
    assert account_crypto_binding_mismatched_purposes(
        _binding_for(otp=b"Q" * 32), crypto
    ) == frozenset({"otp_hmac"})
    assert account_crypto_binding_mismatched_purposes(
        _binding_for(lookup=b"M" * 32), crypto
    ) == frozenset({"email_lookup_hmac"})


class _BindingBegin:
    def __init__(self, binding: SimpleNamespace) -> None:
        self.db = _BindingOnlyDb(binding)

    def __enter__(self) -> _BindingOnlyDb:
        return self.db

    def __exit__(self, *_exc) -> bool:
        return False


def _stored_session_local(stored: SimpleNamespace) -> SimpleNamespace:
    return SimpleNamespace(begin=lambda: _BindingBegin(stored))


def run_production_startup(
    monkeypatch: pytest.MonkeyPatch,
    session_local: object,
    configured: SimpleNamespace | None = None,
) -> bool:
    """Run main.lifespan with the real account key startup check.

    Only unrelated startup steps are replaced; bind_account_crypto_keys runs
    against ``session_local`` with the account keys of ``configured``.
    Returns True when the application reached serving state.
    """

    import asyncio

    import backend.app.main as main_app
    owned_app = main_app.create_app(main_app.settings)
    owner = owned_app.state.runtime
    owner.initialize_resources()
    monkeypatch.setattr(owner, "initialize_resources", lambda: None)
    from backend.app import application_runtime as runtime_module

    configured = configured or _settings()
    for name in (
        "account_email_encryption_key",
        "account_email_lookup_hmac_key",
        "account_otp_hmac_key",
        "account_email_key_version",
    ):
        monkeypatch.setattr(owner.settings, name, getattr(configured, name))
    monkeypatch.setattr(owner.database, "_session_factory", session_local)
    for name in (
        "validate_admin_credential_issuer_binding",
        "bind_privacy_hmac_key",
        "reconcile_report_storage",
        "reconcile_raw_collection_storage",
    ):
        monkeypatch.setattr(owner, name, lambda: None)
    monkeypatch.setattr(owner, "capacity_monitor", None)
    monkeypatch.setattr(owner, "inference_runner", None)
    served: list[bool] = []

    async def exercise() -> None:
        async with owner.lifespan(owned_app):
            served.append(True)

    asyncio.run(exercise())
    return served == [True]



def test_s01_14_lifespan_starts_with_otp_only_key_change_and_logs_purpose(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    stored = _binding_for(otp=b"Q" * 32)
    caplog.set_level("WARNING", logger="backend.app.application_runtime")
    assert run_production_startup(monkeypatch, _stored_session_local(stored)) is True
    warnings = [
        record.getMessage()
        for record in caplog.records
        if record.name == "backend.app.application_runtime"
    ]
    assert any("otp_hmac" in message for message in warnings), warnings
    configured_fingerprints = AccountService(_settings())._crypto().key_fingerprints
    for secret_text in (
        stored.otp_hmac_key_fingerprint,
        *configured_fingerprints,
        "O" * 32,
        "Q" * 32,
    ):
        assert secret_text not in caplog.text
    # After startup the same store serves password login but keeps OTP
    # issuance and account creation closed.
    db = _BindingOnlyDb(stored)
    service = AccountService(_settings())
    login = _authenticate(service, db)
    assert (login.code, login.status_code) == ("account_authentication_failed", 401)
    issue = _issue(service, db)
    assert (issue.code, issue.status_code) == ("account_crypto_key_mismatch", 503)
    create = _create(service, db)
    assert (create.code, create.status_code) == ("account_crypto_key_mismatch", 503)


@pytest.mark.parametrize(
    "changed",
    (
        {"lookup": b"M" * 32},
        {"encryption": b"F" * 32},
        {"version": 2},
    ),
    ids=("lookup_hmac", "encryption", "key_version"),
)
def test_s01_14_lifespan_refuses_login_key_change(
    monkeypatch: pytest.MonkeyPatch,
    changed: dict[str, object],
) -> None:
    with pytest.raises(AccountServiceError) as rejected:
        run_production_startup(monkeypatch, _stored_session_local(_binding_for(**changed)))
    assert (rejected.value.code, rejected.value.status_code) == (
        "account_crypto_key_mismatch",
        503,
    )


def test_s01_14_readiness_stays_ready_for_otp_only_mismatch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = SimpleNamespace(
        account_email_encryption_key=b"E" * 32,
        account_email_lookup_hmac_key=b"L" * 32,
        account_otp_hmac_key=b"O" * 32,
        account_email_key_version=1,
        walksafe_environment="test",
    )

    class _Begin:
        def __init__(self, binding):
            self.db = _BindingOnlyDb(binding)

        def __enter__(self):
            return self.db

        def __exit__(self, *_exc):
            return False

    stored = {"binding": _binding_for(otp=b"Q" * 32)}
    monkeypatch.setattr(
        health_api,
        "SessionLocal",
        SimpleNamespace(begin=lambda: _Begin(stored["binding"])),
    )
    assert health_api._account_crypto_binding_readiness(settings) == {
        "ready": True,
        "binding": "matched",
        "otp_hmac_binding": "mismatch",
    }
    stored["binding"] = _binding_for(lookup=b"M" * 32)
    assert health_api._account_crypto_binding_readiness(settings) == {
        "ready": False,
        "reason": "account_crypto_binding_mismatch",
    }
    stored["binding"] = _binding_for()
    assert health_api._account_crypto_binding_readiness(settings) == {
        "ready": True,
        "binding": "matched",
    }
