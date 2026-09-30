"""S19-42: actor ID lifetime across signup, deletion and re-signup (FP-015-DETAIL-03).

Re-signup after a completed deletion is a new account: a new actor ID
(uuid4) with generation 1, a new pseudonymous subject, no link to the deleted
actor's consent, and the deleted actor generation stays tombstoned. While the
deletion is still pending the e-mail cannot be re-registered. The Backend has
no password-reset path that changes an account's actor or generation (the
generation is fixed at 1 and ``auth_epoch`` only fences credentials).
"""

from __future__ import annotations

from datetime import timedelta
from pathlib import Path
import os
import uuid

import pytest
from sqlalchemy import select

from backend.app.account_schemas import (
    AccountAuthenticateRequestV1,
    AccountCreateRequestV1,
    EmailOtpEnrollmentRequestV1,
)
from backend.app.main import settings as app_settings
from backend.app.models import PrivacyConsentEvent, UserAccount
from backend.app.services.accounts import AccountService, AccountServiceError, utc_now
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    accept_account_deletion,
    get_consent_bootstrap,
    lock_report_ingest_transaction,
    privacy_subject_hmac,
)
from backend.app.schemas import PRIVACY_CONSENT_POLICY_VERSION
from scripts.account_deletion_worker import (
    _prepare_roots as prepare_account_deletion_worker_roots,
    process_request as process_account_deletion_request,
)
from test_fp046_postgres_integration import (
    PRIVACY_SECRET,
    _record_consent,
    _request,
    _session_factory,
    _worker_session_factory,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

_PASSWORD = "correct horse battery"


def _signup(SessionFactory, service: AccountService, email: str, request_id: str, now):
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
    assert issued.delivery is not None
    with SessionFactory() as db:
        service.mark_delivery_sent(db, issued.delivery, now=now)
    payload = AccountCreateRequestV1.model_validate(
        {
            "schema_version": "walksafe.account-create.v1",
            "enrollment_handle": issued.response.enrollment_handle,
            "otp_code": issued.delivery.code,
            "password": _PASSWORD,
            "consent": {
                "schema_version": "walksafe.signup-consent.v1",
                "document_versions": dict(app_settings.account_signup_document_versions),
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


def test_s19_42_resignup_after_deletion_is_a_new_actor_and_old_actor_stays_blocked(
    tmp_path: Path,
) -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(app_settings)
    suffix = uuid.uuid4().hex[:12]
    email = f"actor-lifecycle-{suffix}@example.com"
    cooldown = timedelta(seconds=app_settings.account_otp_resend_cooldown_seconds + 1)
    first_at = utc_now()
    first = _signup(SessionFactory, service, email, f"lifecycle_first_{suffix}", first_at)
    assert first.account_generation == 1
    old_subject = privacy_subject_hmac(first.actor_id, 1, PRIVACY_SECRET)
    installation = f"install_lifecycle_{suffix}"
    old_consent = _record_consent(
        SessionFactory, actor_id=first.actor_id, installation_id=installation
    ).event

    # While the account exists (and while its deletion is pending) the e-mail
    # cannot be registered again.
    with pytest.raises(AccountServiceError) as taken:
        _signup(SessionFactory, service, email, f"lifecycle_taken_{suffix}", first_at + cooldown)
    assert taken.value.code == "account_enrollment_conflict"
    request_id = f"delete_lifecycle_{suffix}"
    with SessionFactory() as db:
        accepted = accept_account_deletion(
            db,
            payload=_request(request_id),
            actor_id=first.actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=None,
            secret=PRIVACY_SECRET,
        )
    assert accepted.created is True
    with pytest.raises(AccountServiceError) as pending:
        _signup(
            SessionFactory, service, email, f"lifecycle_pending_{suffix}", first_at + 2 * cooldown
        )
    assert pending.value.code == "account_enrollment_conflict"

    # The worker deletes the credential account; the ledger stays.
    tmp_path.chmod(0o700)
    upload_dir = tmp_path / "uploads"
    raw_object_dir = tmp_path / "raw-objects"
    upload_dir.mkdir(mode=0o700)
    raw_object_dir.mkdir(mode=0o700)
    journal_root = (tmp_path / "worker").resolve()
    prepare_account_deletion_worker_roots(journal_root)
    try:
        with _worker_session_factory(engine, suffix) as WorkerSessionFactory:
            process_account_deletion_request(
                WorkerSessionFactory,
                request_id=request_id,
                upload_dir=upload_dir.resolve(),
                raw_object_dir=raw_object_dir.resolve(),
                root=journal_root,
            )
        with SessionFactory() as db:
            assert db.scalar(
                select(UserAccount).where(UserAccount.actor_id == first.actor_id)
            ) is None

        # Re-signup with the same e-mail is a brand-new actor, generation 1.
        second = _signup(
            SessionFactory, service, email, f"lifecycle_second_{suffix}", first_at + 3 * cooldown
        )
        assert second.actor_id != first.actor_id
        assert str(uuid.UUID(second.actor_id)) == second.actor_id
        assert second.account_generation == 1
        new_subject = privacy_subject_hmac(second.actor_id, 1, PRIVACY_SECRET)
        assert new_subject != old_subject
        with SessionFactory() as db:
            account = db.scalar(
                select(UserAccount).where(UserAccount.actor_id == second.actor_id)
            )
            assert account is not None
            assert account.privacy_subject_hmac == new_subject
            assert (account.account_generation, account.auth_epoch) == (1, 1)
            # The old consent chain is not linked to the new subject.
            assert db.scalar(
                select(PrivacyConsentEvent.id).where(
                    PrivacyConsentEvent.privacy_subject_hmac == new_subject
                )
            ) is None
            assert db.get(PrivacyConsentEvent, old_consent.id) is not None
            bootstrap = get_consent_bootstrap(
                db,
                actor_id=second.actor_id,
                account_generation=1,
                installation_id=installation,
                policy_version=PRIVACY_CONSENT_POLICY_VERSION,
                signup_document_versions=app_settings.account_signup_document_versions,
                secret=PRIVACY_SECRET,
            )
        assert bootstrap.source != "CURRENT_CONSENT"
        assert bootstrap.expected_previous_backend_receipt_sha256 is None
        assert bootstrap.client_revision_floor == 0

        # Logging in with the same e-mail/password reaches only the new actor.
        with SessionFactory() as db:
            login = service.authenticate(
                db,
                AccountAuthenticateRequestV1(
                    schema_version="walksafe.account-authenticate.v1",
                    email=email,
                    password=_PASSWORD,
                ),
            )
        assert (login.actor_id, login.account_generation) == (second.actor_id, 1)

        # The deleted actor generation is never re-admitted; the new one is free.
        with pytest.raises(PrivacyLifecycleError) as reaccept:
            with SessionFactory() as db:
                accept_account_deletion(
                    db,
                    payload=_request(f"delete_lifecycle_again_{suffix}"),
                    actor_id=first.actor_id,
                    account_generation=1,
                    access_pre_digest="a" * 64,
                    tombstone_id=None,
                    secret=PRIVACY_SECRET,
                )
        assert reaccept.value.code == "account_generation_tombstoned"
        with pytest.raises(PrivacyLifecycleError) as old_ingest:
            with SessionFactory() as db:
                lock_report_ingest_transaction(db, old_subject, 1, enforce_consent=False)
        assert old_ingest.value.code == "account_generation_tombstoned"
        with SessionFactory() as db:
            lock_report_ingest_transaction(db, new_subject, 1, enforce_consent=False)
            db.rollback()
    finally:
        engine.dispose()
