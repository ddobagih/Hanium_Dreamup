"""Signup scrypt and password login share one bounded in-flight budget."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from threading import Event, Thread
from types import SimpleNamespace
import uuid

from fastapi import FastAPI

from asgi_client import ASGITestClient
from backend.app.account_schemas import AccountCreateRequestV1
from backend.app.api.accounts import create_router
from backend.app.database import get_db
from backend.app.models import AccountEnrollment
import backend.app.services.accounts as accounts


def test_signup_hash_shares_login_capacity_without_consuming_otp_on_overload(
    monkeypatch,
) -> None:
    versions = {
        "terms_of_service": "walksafe.terms-of-service.v1",
        "privacy_notice": "walksafe.privacy-notice.v1",
        "location_terms": "walksafe.location-terms.v1",
        "raw_original": "FP-013-RAW-1.1.0",
        "automatic_reporting": "FP-013-AUTO-1.1.0",
        "training_reuse": "FP-013-TRAINING-1.1.0",
    }
    settings = SimpleNamespace(
        account_email_encryption_key=b"E" * 32,
        account_email_lookup_hmac_key=b"L" * 32,
        account_otp_hmac_key=b"O" * 32,
        account_email_key_version=1,
        account_signup_document_versions=versions,
        privacy_hmac_secret="walksafe-test-privacy-secret-at-least-32-bytes",
    )
    gate = accounts.AccountPasswordVerificationGate(1)
    service = accounts.AccountService(
        settings,
        authentication_rate_limiter=SimpleNamespace(check=lambda **_kwargs: None),
        password_verification_gate=gate,
    )
    payload = {
        "schema_version": "walksafe.account-create.v1",
        "enrollment_handle": "A" * 43,
        "otp_code": "012345",
        "password": "correct horse battery staple",
        "consent": {
            "schema_version": "walksafe.signup-consent.v1",
            "document_versions": versions,
            "selections": {
                "terms_of_service": True,
                "privacy_notice": True,
                "location_terms": True,
            },
        },
    }
    now = datetime.now(UTC).replace(microsecond=0)
    crypto = service._crypto()
    enrollment_id = uuid.uuid4()
    email = "person@example.com"
    nonce, ciphertext = crypto.encrypt(enrollment_id, email)
    enrollment = AccountEnrollment(
        id=enrollment_id,
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
        max_attempts=5,
        expires_at=now + timedelta(minutes=10),
        consumed_at=None,
        updated_at=now,
    )
    fingerprints = crypto.key_fingerprints
    binding = SimpleNamespace(
        key_version=crypto.key_version,
        encryption_key_fingerprint=fingerprints[0],
        lookup_hmac_key_fingerprint=fingerprints[1],
        otp_hmac_key_fingerprint=fingerprints[2],
    )

    class SignupDb:
        def __init__(self) -> None:
            self.scalar_calls = 0
            self.commits = 0
            self.rollbacks = 0
            self.added = []

        def get(self, _model, _key, **_kwargs):
            return binding

        def scalar(self, _statement):
            self.scalar_calls += 1
            return enrollment if self.scalar_calls % 2 else None

        def add(self, row):
            self.added.append(row)

        def commit(self):
            self.commits += 1

        def rollback(self):
            self.rollbacks += 1

    class LoginDb:
        def __init__(self) -> None:
            self.rollbacks = 0

        def get(self, _model, _key, **_kwargs):
            return binding

        def scalar(self, _statement):
            return None

        def rollback(self):
            self.rollbacks += 1

    signup_db = SignupDb()
    login_db = LoginDb()
    current_db = [signup_db]
    app = FastAPI()
    app.include_router(
        create_router(
            settings,
            sender=SimpleNamespace(),
            service=service,
            rate_limiter=SimpleNamespace(),
        )
    )

    def dependency():
        yield current_db[0]

    app.dependency_overrides[get_db] = dependency
    client = ASGITestClient(app)
    original_otp_hmac = enrollment.otp_hmac

    with gate.slot():
        signup_response = client.post("/accounts", json=payload)
    assert signup_response.status_code == 503
    assert signup_response.json() == {
        "detail": {
            "code": "account_enrollment_unavailable",
            "message": "Account enrollment is temporarily unavailable.",
        }
    }
    assert signup_response.headers["retry-after"] == "1"
    assert signup_response.headers["cache-control"] == "no-store"
    assert signup_response.headers["pragma"] == "no-cache"
    assert signup_db.scalar_calls == 2
    assert signup_db.rollbacks == 1
    assert signup_db.commits == 0 and signup_db.added == []
    assert enrollment.state == "ACTIVE"
    assert enrollment.otp_hmac == original_otp_hmac
    assert enrollment.attempt_count == 0 and enrollment.consumed_at is None

    entered_hash = Event()
    release_hash = Event()
    real_hash = accounts.hash_account_password

    def paused_hash(password: str) -> str:
        entered_hash.set()
        if not release_hash.wait(timeout=5):
            raise AssertionError("signup hash was not released")
        return real_hash(password)

    monkeypatch.setattr(accounts, "hash_account_password", paused_hash)
    outcome = []

    def create_account() -> None:
        try:
            outcome.append(
                service.create_account(
                    signup_db,
                    AccountCreateRequestV1.model_validate(payload),
                    now=now,
                )
            )
        except BaseException as exc:
            outcome.append(exc)

    worker = Thread(target=create_account)
    worker.start()
    try:
        assert entered_hash.wait(timeout=5)
        assert enrollment.state == "ACTIVE" and enrollment.otp_hmac == original_otp_hmac
        current_db[0] = login_db
        login_response = client.post(
            "/accounts/authenticate",
            json={
                "schema_version": "walksafe.account-authenticate.v1",
                "email": email,
                "password": payload["password"],
            },
        )
        assert login_response.status_code == 503
        assert login_response.json() == {
            "detail": {
                "code": "account_authentication_busy",
                "message": "Account authentication is temporarily unavailable.",
            }
        }
        assert login_response.headers["retry-after"] == "1"
        assert login_response.headers["cache-control"] == "no-store"
        assert login_response.headers["pragma"] == "no-cache"
        assert login_db.rollbacks == 1
    finally:
        release_hash.set()
        worker.join(timeout=5)

    assert not worker.is_alive()
    assert len(outcome) == 1 and not isinstance(outcome[0], BaseException)
    assert signup_db.commits == 1
    assert enrollment.state == "CONSUMED"
    assert enrollment.otp_hmac is None and enrollment.consumed_at == now
