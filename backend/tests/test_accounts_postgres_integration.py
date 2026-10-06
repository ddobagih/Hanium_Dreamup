from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import importlib
import os
from threading import Event
from types import SimpleNamespace
import uuid

from alembic.migration import MigrationContext
from alembic.operations import Operations
from fastapi import FastAPI
import pytest
from sqlalchemy import create_engine, func, inspect, select, text
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import sessionmaker

from backend.app.account_schemas import (
    AccountAuthenticateRequestV1,
    AccountCreateRequestV1,
    EmailOtpEnrollmentRequestV1,
)
from backend.app.api.accounts import create_router
from backend.app.database import get_db
from backend.app.main import settings
from backend.app.models import AccountEnrollment, SignupConsentReceipt, UserAccount
from backend.app.services import accounts as accounts_service
from backend.app.services.accounts import (
    AccountAuthenticationRateLimiter,
    AccountService,
    AccountServiceError,
    utc_now,
)
from asgi_client import ASGITestClient
from scripts.purge_account_enrollments import _candidate_ids


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _session_factory():
    engine = create_engine(
        os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(),
        pool_pre_ping=True,
    )
    return engine, sessionmaker(bind=engine)


_ACCOUNT_DOWNGRADE_TABLES = (
    "user_accounts",
    "account_enrollments",
    "signup_consent_receipts",
)


def _clear_account_downgrade_fixtures(engine) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                "ALTER TABLE public.signup_consent_receipts "
                "DISABLE TRIGGER USER"
            )
        )
        connection.execute(
            text(
                "TRUNCATE TABLE public.user_accounts, "
                "public.account_enrollments, public.signup_consent_receipts"
            )
        )
        connection.execute(
            text(
                "ALTER TABLE public.signup_consent_receipts "
                "ENABLE TRIGGER USER"
            )
        )
        connection.execute(text("TRUNCATE TABLE public.actor_rate_limit_events"))


def _insert_user_account_downgrade_fixture(connection) -> uuid.UUID:
    observed_at = utc_now()
    account_id = uuid.uuid4()
    connection.execute(
        UserAccount.__table__.insert().values(
            id=account_id,
            actor_id=str(uuid.uuid4()),
            privacy_subject_hmac="a" * 64,
            email_lookup_hmac="b" * 64,
            email_ciphertext=b"c" * 17,
            email_nonce=b"n" * 12,
            email_key_version=1,
            password_hash="p" * 32,
            status="ACTIVE",
            account_generation=1,
            auth_epoch=1,
            created_at=observed_at,
            updated_at=observed_at,
        )
    )
    return account_id


def _insert_account_downgrade_fixture(connection, table_name: str) -> None:
    observed_at = utc_now()
    if table_name == "user_accounts":
        _insert_user_account_downgrade_fixture(connection)
        return
    if table_name == "account_enrollments":
        connection.execute(
            AccountEnrollment.__table__.insert().values(
                id=uuid.uuid4(),
                request_id=f"downgrade_{uuid.uuid4().hex}",
                request_hmac="c" * 64,
                enrollment_handle="A" * 43,
                email_lookup_hmac="d" * 64,
                email_ciphertext=b"e" * 17,
                email_nonce=b"n" * 12,
                email_key_version=1,
                otp_hmac="f" * 64,
                state="PENDING_DELIVERY",
                state_version=1,
                attempt_count=0,
                max_attempts=5,
                issue_count=1,
                expires_at=observed_at + timedelta(minutes=10),
                resend_not_before=observed_at + timedelta(minutes=1),
                consumed_at=None,
                created_at=observed_at,
                updated_at=observed_at,
            )
        )
        return
    if table_name == "signup_consent_receipts":
        account_id = _insert_user_account_downgrade_fixture(connection)
        connection.execute(
            SignupConsentReceipt.__table__.insert().values(
                id=uuid.uuid4(),
                account_id=account_id,
                schema_version="walksafe.signup-consent.v1",
                document_versions={
                    "terms_of_service": "v1",
                    "privacy_notice": "v1",
                    "location_terms": "v1",
                    "raw_original": "v1",
                    "automatic_reporting": "v1",
                    "training_reuse": "v1",
                },
                selections={
                    "terms_of_service": True,
                    "privacy_notice": True,
                    "location_terms": True,
                    "raw_original": False,
                    "automatic_reporting": False,
                    "training_reuse": False,
                },
                receipt_sha256="e" * 64,
                recorded_at=observed_at,
            )
        )
        return
    raise AssertionError(f"unknown account downgrade fixture: {table_name}")


@pytest.mark.parametrize("table_name", _ACCOUNT_DOWNGRADE_TABLES)
def test_email_account_downgrade_refuses_and_preserves_nonempty_tables(
    table_name: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, _ = _session_factory()
    migration = importlib.import_module(
        "backend.alembic.versions.202608290009_email_account_enrollment"
    )
    try:
        _clear_account_downgrade_fixtures(engine)
        with engine.begin() as connection:
            _insert_account_downgrade_fixture(connection, table_name)

        with engine.connect() as connection:
            transaction = connection.begin()
            monkeypatch.setattr(
                migration,
                "op",
                Operations(MigrationContext.configure(connection)),
            )
            try:
                with pytest.raises(SQLAlchemyError) as rejected:
                    migration.downgrade()
                assert getattr(rejected.value.orig, "sqlstate", None) == "55000"
            finally:
                transaction.rollback()

        with engine.connect() as connection:
            assert all(
                connection.execute(
                    text("SELECT to_regclass(:table_name)"),
                    {"table_name": f"public.{candidate}"},
                ).scalar_one()
                is not None
                for candidate in _ACCOUNT_DOWNGRADE_TABLES
            )
            assert connection.execute(
                text(f"SELECT count(*) FROM public.{table_name}")
            ).scalar_one() == 1
    finally:
        _clear_account_downgrade_fixtures(engine)
        engine.dispose()


def test_email_account_downgrade_allows_empty_tables(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, _ = _session_factory()
    migration = importlib.import_module(
        "backend.alembic.versions.202608290009_email_account_enrollment"
    )
    try:
        _clear_account_downgrade_fixtures(engine)
        with engine.connect() as connection:
            transaction = connection.begin()
            monkeypatch.setattr(
                migration,
                "op",
                Operations(MigrationContext.configure(connection)),
            )
            try:
                migration.downgrade()
                assert all(
                    connection.execute(
                        text("SELECT to_regclass(:table_name)"),
                        {"table_name": f"public.{table_name}"},
                    ).scalar_one()
                    is None
                    for table_name in _ACCOUNT_DOWNGRADE_TABLES
                )
            finally:
                transaction.rollback()

        with engine.connect() as connection:
            assert all(
                connection.execute(
                    text("SELECT to_regclass(:table_name)"),
                    {"table_name": f"public.{table_name}"},
                ).scalar_one()
                is not None
                for table_name in _ACCOUNT_DOWNGRADE_TABLES
            )
    finally:
        _clear_account_downgrade_fixtures(engine)
        engine.dispose()


@pytest.mark.parametrize("extra_column", ("document_versions", "selections"))
def test_signup_consent_receipt_constraints_reject_extra_keys(
    extra_column: str,
) -> None:
    engine, _ = _session_factory()
    observed_at = utc_now()
    document_versions = {
        "terms_of_service": "v1",
        "privacy_notice": "v1",
        "location_terms": "v1",
        "raw_original": "v1",
        "automatic_reporting": "v1",
        "training_reuse": "v1",
    }
    selections = {
        "terms_of_service": True,
        "privacy_notice": True,
        "location_terms": True,
        "raw_original": False,
        "automatic_reporting": False,
        "training_reuse": False,
    }
    if extra_column == "document_versions":
        document_versions["unexpected"] = "v1"
    else:
        selections["unexpected"] = False

    try:
        with pytest.raises(IntegrityError):
            with engine.begin() as connection:
                connection.execute(
                    SignupConsentReceipt.__table__.insert().values(
                        id=uuid.uuid4(),
                        account_id=uuid.uuid4(),
                        schema_version="walksafe.signup-consent.v1",
                        document_versions=document_versions,
                        selections=selections,
                        receipt_sha256=uuid.uuid4().hex + uuid.uuid4().hex,
                        recorded_at=observed_at,
                    )
                )
    finally:
        engine.dispose()


def _request(*, email: str, request_id: str, birth_date: str = "2000-01-01"):
    return EmailOtpEnrollmentRequestV1.model_validate(
        {
            "schema_version": "walksafe.account-enrollment-email-otp.v1",
            "email": email,
            "date_of_birth": birth_date,
            "request_id": request_id,
        }
    )


def _create_payload(handle: str, code: str, *, password: str = "correct horse battery"):
    return AccountCreateRequestV1.model_validate(
        {
            "schema_version": "walksafe.account-create.v1",
            "enrollment_handle": handle,
            "otp_code": code,
            "password": password,
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


def _active_enrollment(service, SessionFactory, *, email: str, suffix: str, now=None):
    observed_at = now or utc_now()
    with SessionFactory() as db:
        issued = service.issue_email_otp(
            db,
            _request(email=email, request_id=f"enrollment_{suffix}_{uuid.uuid4().hex}"),
            now=observed_at,
        )
        assert not db.in_transaction(), "SMTP must run after the issue transaction"
        assert issued.delivery is not None
        service.mark_delivery_sent(db, issued.delivery, now=observed_at)
        return issued


def test_account_authentication_limits_are_shared_by_email_hmac_and_global() -> None:
    engine, SessionFactory = _session_factory()
    common = {
        "actor_rate_limit_store": "postgresql",
        "account_authentication_global_window_seconds": 60,
        "account_authentication_email_window_seconds": 60,
    }
    with SessionFactory.begin() as db:
        db.execute(
            text(
                "DELETE FROM actor_rate_limit_events WHERE rate_group IN ("
                "'account_authentication_global', "
                "'account_authentication_email')"
            )
        )

    first = AccountAuthenticationRateLimiter(
        SimpleNamespace(
            **common,
            account_authentication_global_limit=10,
            account_authentication_email_limit=1,
        )
    )
    second = AccountAuthenticationRateLimiter(
        SimpleNamespace(
            **common,
            account_authentication_global_limit=10,
            account_authentication_email_limit=1,
        )
    )
    first.check(email_lookup_hmac="a" * 64)
    with pytest.raises(AccountServiceError) as email_rejected:
        second.check(email_lookup_hmac="a" * 64)
    assert email_rejected.value.code == "account_authentication_rate_limited"

    with SessionFactory.begin() as db:
        db.execute(
            text(
                "DELETE FROM actor_rate_limit_events WHERE rate_group IN ("
                "'account_authentication_global', "
                "'account_authentication_email')"
            )
        )
    global_limiter = AccountAuthenticationRateLimiter(
        SimpleNamespace(
            **common,
            account_authentication_global_limit=1,
            account_authentication_email_limit=10,
        )
    )
    global_limiter.check(email_lookup_hmac="b" * 64)
    with pytest.raises(AccountServiceError) as global_rejected:
        global_limiter.check(email_lookup_hmac="c" * 64)
    assert global_rejected.value.code == "account_authentication_rate_limited"
    engine.dispose()


def test_account_enrollment_create_authenticate_and_receipt_are_atomic() -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    email = f"person-{suffix}@example.com"
    issued = _active_enrollment(
        service,
        SessionFactory,
        email=email,
        suffix=suffix,
    )
    assert issued.delivery is not None
    with SessionFactory() as db:
        created = service.create_account(
            db,
            _create_payload(
                issued.response.enrollment_handle,
                issued.delivery.code,
            ),
        )
    assert created.account_generation == 1
    assert len(created.signup_receipt_sha256) == 64

    with SessionFactory() as db:
        account = db.scalar(
            select(UserAccount).where(UserAccount.actor_id == created.actor_id)
        )
        assert account is not None
        assert email.encode("utf-8") not in account.email_ciphertext
        assert email not in account.password_hash
        receipt = db.scalar(
            select(SignupConsentReceipt).where(
                SignupConsentReceipt.account_id == account.id
            )
        )
        assert receipt is not None
        assert receipt.receipt_sha256 == created.signup_receipt_sha256
        assert receipt.selections == {
            "terms_of_service": True,
            "privacy_notice": True,
            "location_terms": True,
            "raw_original": False,
            "automatic_reporting": False,
            "training_reuse": False,
        }
        authenticated = service.authenticate(
            db,
            AccountAuthenticateRequestV1(
                schema_version="walksafe.account-authenticate.v1",
                email=email,
                password="correct horse battery",
            ),
        )
        assert authenticated.actor_id == created.actor_id
        assert authenticated.account_generation == 1
        assert authenticated.auth_epoch == 1

    with SessionFactory() as db:
        replayed = service.create_account(
            db,
            _create_payload(
                issued.response.enrollment_handle,
                issued.delivery.code,
            ),
        )
        assert replayed.model_dump() == created.model_dump()
    engine.dispose()


def test_wrong_otp_exhaustion_and_expiry_are_single_use_states() -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    issued = _active_enrollment(
        service,
        SessionFactory,
        email=f"wrong-{suffix}@example.com",
        suffix=suffix,
    )
    assert issued.delivery is not None
    wrong = "000000" if issued.delivery.code != "000000" else "000001"
    for attempt in range(settings.account_otp_attempt_limit):
        with SessionFactory() as db:
            with pytest.raises(AccountServiceError) as rejected:
                service.create_account(
                    db,
                    _create_payload(issued.response.enrollment_handle, wrong),
                )
            assert rejected.value.code == "account_enrollment_verification_failed"
            assert rejected.value.status_code == 401
    with SessionFactory() as db:
        exhausted = db.scalar(
            select(AccountEnrollment).where(
                AccountEnrollment.enrollment_handle
                == issued.response.enrollment_handle
            )
        )
        assert exhausted is not None
        assert exhausted.state == "EXHAUSTED"
        assert exhausted.otp_hmac is None
        assert exhausted.attempt_count == settings.account_otp_attempt_limit

    start = utc_now()
    expired_issue = _active_enrollment(
        service,
        SessionFactory,
        email=f"expired-{suffix}@example.com",
        suffix=f"expired_{suffix}",
        now=start,
    )
    assert expired_issue.delivery is not None
    with SessionFactory() as db:
        with pytest.raises(AccountServiceError) as expired:
            service.create_account(
                db,
                _create_payload(
                    expired_issue.response.enrollment_handle,
                    expired_issue.delivery.code,
                ),
                now=start + timedelta(seconds=settings.account_otp_ttl_seconds + 1),
            )
        assert expired.value.code == "account_enrollment_verification_failed"
        row = db.scalar(
            select(AccountEnrollment).where(
                AccountEnrollment.enrollment_handle
                == expired_issue.response.enrollment_handle
            )
        )
        assert row is not None
        assert row.state == "EXPIRED"
        assert row.otp_hmac is None
    engine.dispose()


def test_idempotency_resend_cooldown_and_existing_email_are_not_enumerated() -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    email = f"duplicate-{suffix}@example.com"
    request = _request(email=email, request_id=f"initial_{suffix}")
    start = utc_now()
    with SessionFactory() as db:
        issued = service.issue_email_otp(db, request, now=start)
        assert issued.delivery is not None
        service.mark_delivery_sent(db, issued.delivery, now=start)
    with SessionFactory() as db:
        replayed = service.issue_email_otp(db, request, now=start)
        assert replayed.delivery is None
        assert replayed.response == issued.response
    with SessionFactory() as db:
        with pytest.raises(AccountServiceError) as conflict:
            service.issue_email_otp(
                db,
                request.model_copy(update={"date_of_birth": request.date_of_birth.replace(day=2)}),
                now=start,
            )
        assert conflict.value.code == "account_enrollment_idempotency_conflict"
    with SessionFactory() as db:
        with pytest.raises(AccountServiceError) as cooldown:
            service.issue_email_otp(
                db,
                _request(email=email, request_id=f"cooldown_{suffix}"),
                now=start + timedelta(seconds=1),
            )
        assert cooldown.value.status_code == 429

    assert issued.delivery is not None
    with SessionFactory() as db:
        service.create_account(
            db,
            _create_payload(
                issued.response.enrollment_handle,
                issued.delivery.code,
            ),
            now=start + timedelta(seconds=2),
        )

    with SessionFactory() as db:
        existing_email_issue = service.issue_email_otp(
            db,
            _request(email=email, request_id=f"existing_{suffix}"),
            now=start
            + timedelta(seconds=settings.account_otp_resend_cooldown_seconds + 1),
        )
        assert existing_email_issue.delivery is not None
        service.mark_delivery_sent(
            db,
            existing_email_issue.delivery,
            now=start
            + timedelta(seconds=settings.account_otp_resend_cooldown_seconds + 1),
        )
    assert set(existing_email_issue.response.model_dump()) == {
        "schema_version",
        "enrollment_handle",
        "expires_at",
        "resend_available_at",
    }
    with SessionFactory() as db:
        with pytest.raises(AccountServiceError) as duplicate:
            service.create_account(
                db,
                _create_payload(
                    existing_email_issue.response.enrollment_handle,
                    existing_email_issue.delivery.code,
                ),
                now=start
                + timedelta(seconds=settings.account_otp_resend_cooldown_seconds + 2),
            )
        assert duplicate.value.code == "account_enrollment_conflict"
        assert duplicate.value.status_code == 409
    engine.dispose()


def test_new_and_concurrent_resends_leave_only_latest_email_handle_live() -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    email = f"supersede-{suffix}@example.com"
    started_at = utc_now()
    original = _active_enrollment(
        service,
        SessionFactory,
        email=email,
        suffix=f"original_{suffix}",
        now=started_at,
    )
    assert original.delivery is not None
    resend_at = started_at + timedelta(
        seconds=settings.account_otp_resend_cooldown_seconds + 1
    )

    def concurrent_resend(label: str):
        try:
            with SessionFactory() as db:
                issued = service.issue_email_otp(
                    db,
                    _request(
                        email=email,
                        request_id=f"concurrent_{label}_{suffix}",
                    ),
                    now=resend_at,
                )
            assert issued.delivery is not None
            with SessionFactory() as db:
                service.mark_delivery_sent(db, issued.delivery, now=resend_at)
            return issued
        except AccountServiceError as exc:
            return exc

    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(concurrent_resend, ("first", "second")))
    issued = [value for value in outcomes if not isinstance(value, AccountServiceError)]
    rejected = [value for value in outcomes if isinstance(value, AccountServiceError)]
    assert len(issued) == 1
    assert len(rejected) == 1
    assert rejected[0].code == "account_enrollment_rate_limited"
    assert rejected[0].status_code == 429

    latest = issued[0]
    assert latest.delivery is not None
    with SessionFactory() as db:
        live_count = db.scalar(
            select(func.count(AccountEnrollment.id)).where(
                AccountEnrollment.email_lookup_hmac
                == service.normalized_email_lookup(email)[1],
                AccountEnrollment.state.in_(
                    ("PENDING_DELIVERY", "ACTIVE", "DELIVERY_FAILED")
                ),
            )
        )
        original_row = db.scalar(
            select(AccountEnrollment).where(
                AccountEnrollment.enrollment_handle
                == original.response.enrollment_handle
            )
        )
        assert live_count == 1
        assert original_row is not None
        assert original_row.state == "EXPIRED"
        assert original_row.otp_hmac is None

    with SessionFactory() as db:
        with pytest.raises(AccountServiceError) as old_code:
            service.create_account(
                db,
                _create_payload(
                    original.response.enrollment_handle,
                    original.delivery.code,
                ),
                now=resend_at,
            )
        assert old_code.value.code == "account_enrollment_verification_failed"
    with SessionFactory() as db:
        created = service.create_account(
            db,
            _create_payload(
                latest.response.enrollment_handle,
                latest.delivery.code,
            ),
            now=resend_at,
        )
        assert created.account_generation == 1
    engine.dispose()


def test_same_otp_request_concurrently_returns_delivery_lease_without_duplicate_send(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    started_at = utc_now()
    monkeypatch.setattr(accounts_service, "utc_now", lambda: started_at)
    suffix = uuid.uuid4().hex
    payload = _request(
        email=f"concurrent-delivery-{suffix}@example.com",
        request_id=f"same_delivery_{suffix}",
    )
    delivery_started = Event()
    release_delivery = Event()
    send_count = 0

    def send(**_kwargs) -> None:
        nonlocal send_count
        with SessionFactory() as db:
            row = db.scalar(
                select(AccountEnrollment).where(AccountEnrollment.request_id == payload.request_id)
            )
            assert row is not None
            assert row.state == "PENDING_DELIVERY"
        send_count += 1
        delivery_started.set()
        assert release_delivery.wait(timeout=5), "test delivery was not released"

    def database():
        with SessionFactory() as db:
            yield db

    app = FastAPI()
    app.include_router(create_router(
        settings,
        sender=SimpleNamespace(send=send),
        service=AccountService(settings),
        rate_limiter=SimpleNamespace(check_source=lambda **_: None, check_email=lambda **_: None),
    ))
    app.dependency_overrides[get_db] = database

    def issue():
        return ASGITestClient(app).post(
            "/account-enrollments/email-otp",
            json=payload.model_dump(mode="json"),
        )

    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(issue)
            try:
                assert delivery_started.wait(timeout=5)
                pending = pool.submit(issue).result(timeout=5)
                assert pending.status_code == 409
                assert pending.json()["detail"]["code"] == "account_enrollment_in_progress"
                assert pending.headers["retry-after"] == str(settings.account_otp_delivery_lease_seconds)
                assert not first.done()
                assert send_count == 1
            finally:
                release_delivery.set()
            delivered = first.result(timeout=5)
        assert delivered.status_code == 202
        replayed = issue()
        assert replayed.status_code == 202
        assert replayed.json() == delivered.json()
        assert send_count == 1
        with SessionFactory() as db:
            rows = db.scalars(
                select(AccountEnrollment).where(AccountEnrollment.request_id == payload.request_id)
            ).all()
            assert len(rows) == 1
            assert rows[0].state == "ACTIVE"
            assert rows[0].issue_count == 1
    finally:
        release_delivery.set()
        engine.dispose()


def test_smtp_accepted_but_activation_commit_failed_does_not_resend_same_request(monkeypatch):
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    clock = utc_now()
    monkeypatch.setattr(accounts_service, "utc_now", lambda: clock)
    suffix = uuid.uuid4().hex
    payload = _request(email=f"activation-loss-{suffix}@example.com", request_id=f"activation_loss_{suffix}")
    sends = []
    original_mark_sent = service.mark_delivery_sent

    def fail_activation(db, delivery):
        def fail_commit():
            raise SQLAlchemyError("synthetic activation commit failure")
        with monkeypatch.context() as patch:
            patch.setattr(db, "commit", fail_commit)
            original_mark_sent(db, delivery)

    monkeypatch.setattr(service, "mark_delivery_sent", fail_activation)
    app = FastAPI()
    app.include_router(create_router(
        settings, service=service, sender=SimpleNamespace(send=lambda **_: sends.append(True)),
        rate_limiter=SimpleNamespace(check_source=lambda **_: None, check_email=lambda **_: None),
    ))

    def database():
        with SessionFactory() as db:
            yield db
    app.dependency_overrides[get_db] = database

    def issue():
        return ASGITestClient(app).post("/account-enrollments/email-otp", json=payload.model_dump(mode="json"))

    try:
        result = issue()
        assert result.status_code == 503
        assert len(sends) == 1
        with SessionFactory() as db:
            row = db.scalar(select(AccountEnrollment).where(AccountEnrollment.request_id == payload.request_id))
            assert row.state == "PENDING_DELIVERY"
            before = tuple(getattr(row, col.name) for col in AccountEnrollment.__table__.columns)
        assert issue().json()["detail"]["code"] == "account_enrollment_in_progress"
        clock += timedelta(seconds=settings.account_otp_delivery_lease_seconds + 1)
        retry = issue()
        assert retry.status_code == 409
        assert retry.json()["detail"]["code"] == "account_enrollment_idempotency_conflict"
        assert len(sends) == 1
        with SessionFactory() as db:
            row = db.scalar(select(AccountEnrollment).where(AccountEnrollment.request_id == payload.request_id))
            assert tuple(getattr(row, col.name) for col in AccountEnrollment.__table__.columns) == before
    finally:
        engine.dispose()


def test_otp_flush_failure_rolls_back_encrypted_row_and_same_request_can_retry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex
    payload = _request(
        email=f"rollback-delivery-{suffix}@example.com",
        request_id=f"rollback_delivery_{suffix}",
    )
    failure_injected = False
    send_count = 0

    def send(**_kwargs) -> None:
        nonlocal send_count
        send_count += 1

    def database():
        with SessionFactory() as db:
            original_commit = db.commit

            def commit() -> None:
                nonlocal failure_injected
                if not failure_injected and any(
                    isinstance(row, AccountEnrollment) for row in db.new
                ):
                    failure_injected = True
                    db.flush()
                    assert db.scalar(
                        select(func.count(AccountEnrollment.id)).where(
                            AccountEnrollment.request_id == payload.request_id
                        )
                    ) == 1
                    db.execute(text("SELECT 1 / 0"))
                original_commit()

            monkeypatch.setattr(db, "commit", commit)
            yield db

    app = FastAPI()
    app.include_router(create_router(
        settings,
        sender=SimpleNamespace(send=send),
        service=AccountService(settings),
        rate_limiter=SimpleNamespace(check_source=lambda **_: None, check_email=lambda **_: None),
    ))
    app.dependency_overrides[get_db] = database

    def issue():
        return ASGITestClient(app).post(
            "/account-enrollments/email-otp",
            json=payload.model_dump(mode="json"),
        )

    try:
        failed = issue()
        assert failure_injected
        assert failed.status_code == 503
        assert failed.json()["detail"]["code"] == "account_enrollment_unavailable"
        assert failed.headers["retry-after"] == "5"
        assert send_count == 0
        with SessionFactory() as db:
            assert db.scalar(
                select(func.count(AccountEnrollment.id)).where(
                    AccountEnrollment.request_id == payload.request_id
                )
            ) == 0

        delivered = issue()
        assert delivered.status_code == 202
        assert send_count == 1
        replayed = issue()
        assert replayed.status_code == 202
        assert replayed.json() == delivered.json()
        assert send_count == 1
        with SessionFactory() as db:
            rows = db.scalars(
                select(AccountEnrollment).where(AccountEnrollment.request_id == payload.request_id)
            ).all()
            assert len(rows) == 1
            assert rows[0].state == "ACTIVE"
            assert rows[0].issue_count == 1
            assert payload.email.encode() not in rows[0].email_ciphertext
            assert rows[0].enrollment_handle == delivered.json()["enrollment_handle"]
    finally:
        engine.dispose()


def test_stale_pending_delivery_preserves_code_and_requires_new_request() -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    started_at = utc_now()
    request = _request(
        email=f"delivery-lease-{suffix}@example.com",
        request_id=f"delivery_lease_{suffix}",
    )
    with SessionFactory() as db:
        original = service.issue_email_otp(db, request, now=started_at)
    assert original.delivery is not None

    with SessionFactory() as db:
        with pytest.raises(AccountServiceError) as in_progress:
            service.issue_email_otp(
                db,
                request,
                now=started_at
                + timedelta(
                    seconds=settings.account_otp_delivery_lease_seconds - 1
                ),
            )
        assert in_progress.value.code == "account_enrollment_in_progress"
        assert in_progress.value.retry_after is not None

    retry_at = started_at + timedelta(
        seconds=settings.account_otp_delivery_lease_seconds + 1
    )
    with SessionFactory() as db:
        before = db.get(AccountEnrollment, original.delivery.enrollment_id)
        original_state = tuple(getattr(before, col.name) for col in AccountEnrollment.__table__.columns)
    with SessionFactory() as db:
        with pytest.raises(AccountServiceError) as ambiguous:
            service.issue_email_otp(db, request, now=retry_at)
        assert ambiguous.value.code == "account_enrollment_idempotency_conflict"
        assert ambiguous.value.status_code == 409
    with SessionFactory() as db:
        unchanged = db.get(AccountEnrollment, original.delivery.enrollment_id)
        assert tuple(getattr(unchanged, col.name) for col in AccountEnrollment.__table__.columns) == original_state
    # A possibly delivered code is never silently replaced for the same request.
    # The user's new request after cooldown gets a new handle and fences old callbacks.
    retry_at = started_at + timedelta(seconds=max(
        settings.account_otp_resend_cooldown_seconds,
        settings.account_otp_delivery_lease_seconds,
    ) + 1)
    fresh_request = request.model_copy(update={"request_id": f"delivery_new_{suffix}"})
    with SessionFactory() as db:
        retried = service.issue_email_otp(db, fresh_request, now=retry_at)
    assert retried.delivery is not None
    assert retried.response.enrollment_handle != original.response.enrollment_handle

    with SessionFactory() as db:
        with pytest.raises(AccountServiceError) as stale_ack:
            service.mark_delivery_sent(db, original.delivery, now=retry_at)
        assert stale_ack.value.code == "account_enrollment_unavailable"
    with SessionFactory() as db:
        service.mark_delivery_failed(db, original.delivery, now=retry_at)
    with SessionFactory() as db:
        still_pending = db.get(AccountEnrollment, retried.delivery.enrollment_id)
        assert still_pending is not None
        assert still_pending.state == "PENDING_DELIVERY"
        assert still_pending.issue_count == retried.delivery.issue_count
    with SessionFactory() as db:
        service.mark_delivery_sent(db, retried.delivery, now=retry_at)
    with SessionFactory() as db:
        with pytest.raises(AccountServiceError) as old_code:
            service.create_account(
                db,
                _create_payload(
                    original.response.enrollment_handle,
                    original.delivery.code,
                ),
                now=retry_at,
            )
        assert old_code.value.code == "account_enrollment_verification_failed"
    with SessionFactory() as db:
        created = service.create_account(
            db,
            _create_payload(
                retried.response.enrollment_handle,
                retried.delivery.code,
            ),
            now=retry_at,
        )
        assert created.account_generation == 1
    engine.dispose()


def test_new_request_supersedes_pending_handle_and_fences_its_callbacks() -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    email = f"pending-supersede-{suffix}@example.com"
    started_at = utc_now()
    with SessionFactory() as db:
        original = service.issue_email_otp(
            db,
            _request(email=email, request_id=f"pending_original_{suffix}"),
            now=started_at,
        )
    assert original.delivery is not None

    superseded_at = started_at + timedelta(
        seconds=settings.account_otp_resend_cooldown_seconds + 1
    )
    with SessionFactory() as db:
        replacement = service.issue_email_otp(
            db,
            _request(email=email, request_id=f"pending_replacement_{suffix}"),
            now=superseded_at,
        )
    assert replacement.delivery is not None

    with SessionFactory() as db:
        with pytest.raises(AccountServiceError) as stale_sent:
            service.mark_delivery_sent(db, original.delivery, now=superseded_at)
        assert stale_sent.value.code == "account_enrollment_unavailable"
    with SessionFactory() as db:
        service.mark_delivery_failed(db, original.delivery, now=superseded_at)
    with SessionFactory() as db:
        original_row = db.get(AccountEnrollment, original.delivery.enrollment_id)
        replacement_row = db.get(AccountEnrollment, replacement.delivery.enrollment_id)
        assert original_row is not None
        assert original_row.state == "EXPIRED"
        assert original_row.otp_hmac is None
        assert replacement_row is not None
        assert replacement_row.state == "PENDING_DELIVERY"
        assert replacement_row.otp_hmac is not None

    with SessionFactory() as db:
        service.mark_delivery_sent(db, replacement.delivery, now=superseded_at)
    with SessionFactory() as db:
        with pytest.raises(AccountServiceError) as old_code:
            service.create_account(
                db,
                _create_payload(
                    original.response.enrollment_handle,
                    original.delivery.code,
                ),
                now=superseded_at,
            )
        assert old_code.value.code == "account_enrollment_verification_failed"
    engine.dispose()


def test_expired_enrollment_envelope_is_available_to_bounded_manual_purge() -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    issued_at = utc_now()
    with SessionFactory() as db:
        issued = service.issue_email_otp(
            db,
            _request(
                email=f"purge-{suffix}@example.com",
                request_id=f"purge_enrollment_{suffix}",
            ),
            now=issued_at,
        )
    assert issued.delivery is not None
    cutoff = issued_at + timedelta(seconds=settings.account_otp_ttl_seconds + 1)
    with SessionFactory() as db:
        candidates = _candidate_ids(
            db,
            before=cutoff,
            limit=10,
            lock=True,
        )
        assert issued.delivery.enrollment_id in candidates
        db.execute(
            AccountEnrollment.__table__.delete().where(
                AccountEnrollment.id.in_(candidates)
            )
        )
        db.commit()
    with SessionFactory() as db:
        assert db.get(AccountEnrollment, issued.delivery.enrollment_id) is None
    engine.dispose()


def _creation_replay_pg_snapshot(db, handle: str):
    enrollment = db.scalar(
        select(AccountEnrollment).where(AccountEnrollment.enrollment_handle == handle)
    )
    assert enrollment is not None
    account = db.get(UserAccount, enrollment.created_account_id)
    assert account is not None
    receipt = db.scalar(
        select(SignupConsentReceipt).where(SignupConsentReceipt.account_id == account.id)
    )
    assert receipt is not None
    return tuple({
        column.name: getattr(row, column.name)
        for column in type(row).__table__.columns
    } for row in (enrollment, account, receipt))


def _assert_creation_replay_pg_counts(db, *, email_hmac: str, account_id: uuid.UUID):
    assert db.scalar(
        select(func.count(UserAccount.id)).where(UserAccount.email_lookup_hmac == email_hmac)
    ) == 1
    assert db.scalar(
        select(func.count(SignupConsentReceipt.id)).where(
            SignupConsentReceipt.account_id == account_id
        )
    ) == 1


def test_committed_account_result_replays_in_new_session_without_rewriting_rows() -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    email = f"creation-replay-{suffix}@example.com"
    try:
        issued = _active_enrollment(service, SessionFactory, email=email, suffix=suffix)
        assert issued.delivery is not None
        payload = _create_payload(issued.response.enrollment_handle, issued.delivery.code)
        with SessionFactory() as db:
            original = service.create_account(db, payload)
            assert not db.in_transaction()
        # Model a committed result not delivered to the caller, not a socket fault.
        with SessionFactory() as db:
            before = _creation_replay_pg_snapshot(db, payload.enrollment_handle)
        for _ in range(2):
            with SessionFactory() as db:
                replayed = service.create_account(db, payload)
                assert replayed.model_dump() == original.model_dump()
            with SessionFactory() as db:
                assert _creation_replay_pg_snapshot(db, payload.enrollment_handle) == before
                _assert_creation_replay_pg_counts(
                    db,
                    email_hmac=service.normalized_email_lookup(email)[1],
                    account_id=before[1]["id"],
                )
    finally:
        engine.dispose()


@pytest.mark.parametrize("other_payload", ("same", "password", "consent"))
def test_concurrent_account_creation_has_one_result_or_one_different_payload_winner(
    other_payload: str,
) -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    email = f"creation-race-{suffix}@example.com"
    ready = (Event(), Event())
    release = Event()
    try:
        issued = _active_enrollment(service, SessionFactory, email=email, suffix=suffix)
        assert issued.delivery is not None
        first = _create_payload(issued.response.enrollment_handle, issued.delivery.code)
        second_body = first.model_dump()
        if other_payload == "password":
            second_body["password"] = "another correct horse battery"
        elif other_payload == "consent":
            second_body["consent"]["selections"]["raw_original"] = True
        payloads = (first, AccountCreateRequestV1.model_validate(second_body))

        def create(index: int):
            ready[index].set()
            assert release.wait(timeout=5), "account creation race was not released"
            try:
                with SessionFactory() as db:
                    return index, service.create_account(db, payloads[index])
            except AccountServiceError as exc:
                return index, exc

        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(create, index) for index in range(2)]
            try:
                assert all(event.wait(timeout=5) for event in ready)
            finally:
                release.set()
            outcomes = [future.result(timeout=5) for future in futures]
        accepted = [(index, value) for index, value in outcomes
                    if not isinstance(value, AccountServiceError)]
        rejected = [value for _, value in outcomes if isinstance(value, AccountServiceError)]
        if other_payload == "same":
            assert len(accepted) == 2 and not rejected
            assert accepted[0][1] == accepted[1][1]
        else:
            assert len(accepted) == len(rejected) == 1
            assert rejected[0].code == "account_enrollment_conflict"
            assert rejected[0].status_code == 409
        winner, result = accepted[0]
        with SessionFactory() as db:
            snapshot = _creation_replay_pg_snapshot(db, first.enrollment_handle)
            _assert_creation_replay_pg_counts(
                db,
                email_hmac=service.normalized_email_lookup(email)[1],
                account_id=snapshot[1]["id"],
            )
            assert snapshot[0]["state"] == "CONSUMED"
            assert snapshot[0]["attempt_count"] == 0
            assert snapshot[1]["actor_id"] == result.actor_id
            assert accounts_service.verify_account_password(
                payloads[winner].password, snapshot[1]["password_hash"],
            )
            assert snapshot[2]["selections"] == payloads[winner].consent.selections.model_dump()
    finally:
        release.set()
        engine.dispose()


@pytest.mark.parametrize("changed", ("none", "disabled", "password_hash", "auth_epoch"))
def test_creation_replay_refreshes_preloaded_enrollment_and_account_identity_map(
    changed: str,
) -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    email = f"creation-fresh-{suffix}@example.com"
    try:
        issued = _active_enrollment(service, SessionFactory, email=email, suffix=suffix)
        assert issued.delivery is not None
        payload = _create_payload(issued.response.enrollment_handle, issued.delivery.code)
        with SessionFactory() as stale:
            cached_enrollment = stale.get(AccountEnrollment, issued.delivery.enrollment_id)
            assert cached_enrollment is not None and cached_enrollment.state == "ACTIVE"
            with SessionFactory() as fresh:
                created = service.create_account(fresh, payload)
            assert cached_enrollment.state == "ACTIVE"
            cached_account = stale.scalar(
                select(UserAccount).where(UserAccount.actor_id == created.actor_id)
            )
            assert cached_account is not None and cached_account.status == "ACTIVE"
            old_hash = cached_account.password_hash
            if changed != "none":
                with SessionFactory.begin() as fresh:
                    current = fresh.get(UserAccount, cached_account.id)
                    assert current is not None
                    if changed == "disabled":
                        current.status = "DISABLED"
                        current.auth_epoch += 1
                    elif changed == "password_hash":
                        current.password_hash = accounts_service.hash_account_password(payload.password)
                    else:
                        current.auth_epoch += 1
                assert cached_account.status == "ACTIVE"
                assert cached_account.password_hash == old_hash
                assert cached_account.auth_epoch == 1
            with SessionFactory() as fresh:
                before = _creation_replay_pg_snapshot(fresh, payload.enrollment_handle)
            if changed == "none":
                assert service.create_account(stale, payload) == created
            else:
                with pytest.raises(AccountServiceError) as rejected:
                    service.create_account(stale, payload)
                assert rejected.value.code == "account_enrollment_conflict"
                assert rejected.value.status_code == 409
            assert cached_enrollment.state == "CONSUMED"
            stale.rollback()
        with SessionFactory() as fresh:
            assert _creation_replay_pg_snapshot(fresh, payload.enrollment_handle) == before
            _assert_creation_replay_pg_counts(
                fresh,
                email_hmac=service.normalized_email_lookup(email)[1],
                account_id=before[1]["id"],
            )
    finally:
        engine.dispose()


@pytest.mark.parametrize("missing", ("purged_enrollment", "unknown_handle", "deleted_account"))
def test_creation_replay_does_not_reconstruct_missing_rows(
    missing: str,
) -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    email = f"creation-missing-{suffix}@example.com"
    try:
        issued = _active_enrollment(service, SessionFactory, email=email, suffix=suffix)
        assert issued.delivery is not None
        payload = _create_payload(issued.response.enrollment_handle, issued.delivery.code)
        with SessionFactory() as db:
            created = service.create_account(db, payload)
        with SessionFactory() as db:
            account_id = db.scalar(select(UserAccount.id).where(
                UserAccount.actor_id == created.actor_id
            ))
            assert account_id is not None
        if missing == "unknown_handle":
            payload = payload.model_copy(update={
                "enrollment_handle": uuid.uuid4().hex + uuid.uuid4().hex[:11],
            })
        else:
            with SessionFactory.begin() as db:
                if missing == "purged_enrollment":
                    db.execute(AccountEnrollment.__table__.delete().where(
                        AccountEnrollment.id == issued.delivery.enrollment_id
                    ))
                else:
                    db.execute(UserAccount.__table__.delete().where(UserAccount.id == account_id))
        with SessionFactory() as db:
            with pytest.raises(AccountServiceError) as rejected:
                service.create_account(db, payload)
            assert rejected.value.code == (
                "account_enrollment_conflict" if missing == "deleted_account"
                else "account_enrollment_verification_failed"
            )
            assert rejected.value.status_code == (409 if missing == "deleted_account" else 401)
        with SessionFactory() as db:
            assert db.scalar(select(func.count(UserAccount.id)).where(
                UserAccount.email_lookup_hmac == service.normalized_email_lookup(email)[1]
            )) == (0 if missing == "deleted_account" else 1)
            assert db.scalar(select(func.count(SignupConsentReceipt.id)).where(
                SignupConsentReceipt.account_id == account_id
            )) == 1
            assert db.scalar(select(func.count(AccountEnrollment.id)).where(
                AccountEnrollment.id == issued.delivery.enrollment_id
            )) == (0 if missing == "purged_enrollment" else 1)
    finally:
        engine.dispose()


def test_creation_binding_check_failure_rolls_back_account_receipt_and_consumption(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    service = AccountService(settings)
    suffix = uuid.uuid4().hex
    email = f"creation-rollback-{suffix}@example.com"
    try:
        issued = _active_enrollment(service, SessionFactory, email=email, suffix=suffix)
        assert issued.delivery is not None
        payload = _create_payload(issued.response.enrollment_handle, issued.delivery.code)
        with SessionFactory() as db:
            enrollment = db.get(AccountEnrollment, issued.delivery.enrollment_id)
            assert enrollment is not None
            before = {
                column.name: getattr(enrollment, column.name)
                for column in AccountEnrollment.__table__.columns
            }
        attempted_accounts = []
        with monkeypatch.context() as patch:
            def invalid_binding(_self, **kwargs):
                attempted_accounts.append(kwargs["account"].id)
                return "not-a-valid-binding"
            patch.setattr(accounts_service.AccountEmailCrypto, "creation_binding_hmac", invalid_binding)
            with SessionFactory() as db:
                with pytest.raises(IntegrityError) as rejected:
                    service.create_account(db, payload)
                assert getattr(rejected.value.orig, "sqlstate", None) == "23514"
                assert rejected.value.orig.diag.constraint_name == "ck_account_enrollments_creation_binding"
                assert not db.in_transaction()
        assert len(attempted_accounts) == 1
        with SessionFactory() as db:
            enrollment = db.get(AccountEnrollment, issued.delivery.enrollment_id)
            assert enrollment is not None
            assert {
                column.name: getattr(enrollment, column.name)
                for column in AccountEnrollment.__table__.columns
            } == before
            assert db.scalar(select(func.count(UserAccount.id)).where(
                UserAccount.email_lookup_hmac == service.normalized_email_lookup(email)[1]
            )) == 0
            assert db.scalar(select(func.count(SignupConsentReceipt.id)).where(
                SignupConsentReceipt.account_id.in_(attempted_accounts)
            )) == 0
        with SessionFactory() as db:
            created = service.create_account(db, payload)
        with SessionFactory() as db:
            assert service.create_account(db, payload) == created
            snapshot = _creation_replay_pg_snapshot(db, payload.enrollment_handle)
            _assert_creation_replay_pg_counts(
                db,
                email_hmac=service.normalized_email_lookup(email)[1],
                account_id=snapshot[1]["id"],
            )
    finally:
        engine.dispose()


def _creation_binding_pg_row():
    now = utc_now()
    return {
        "id": uuid.uuid4(),
        "request_id": f"binding_{uuid.uuid4().hex}",
        "request_hmac": "a" * 64,
        "enrollment_handle": uuid.uuid4().hex + uuid.uuid4().hex[:11],
        "email_lookup_hmac": uuid.uuid4().hex + uuid.uuid4().hex,
        "email_ciphertext": b"e" * 17,
        "email_nonce": b"n" * 12,
        "email_key_version": 1,
        "otp_hmac": None,
        "state": "CONSUMED",
        "state_version": 2,
        "attempt_count": 0,
        "max_attempts": 5,
        "issue_count": 1,
        "expires_at": now + timedelta(minutes=10),
        "resend_not_before": now,
        "consumed_at": now,
        "created_at": now,
        "updated_at": now,
        "created_account_id": uuid.uuid4(),
        "creation_binding_hmac": "b" * 64,
        "creation_binding_key_version": 1,
    }


@pytest.mark.parametrize("invalid", (*range(1, 7), "uppercase", "short", "zero", "negative", "active"))
def test_creation_binding_postgres_check_rejects_partial_or_invalid_proofs(invalid) -> None:
    engine, _ = _session_factory()
    row = _creation_binding_pg_row()
    if isinstance(invalid, int):
        for bit, field in enumerate((
            "created_account_id", "creation_binding_hmac", "creation_binding_key_version",
        )):
            if not (invalid & (1 << bit)):
                row[field] = None
    elif invalid == "uppercase":
        row["creation_binding_hmac"] = "B" * 64
    elif invalid == "short":
        row["creation_binding_hmac"] = "b" * 63
    elif invalid == "zero":
        row["creation_binding_key_version"] = 0
    elif invalid == "negative":
        row["creation_binding_key_version"] = -1
    else:
        row.update(state="ACTIVE", consumed_at=None, otp_hmac="c" * 64)
    try:
        with pytest.raises(IntegrityError) as rejected:
            with engine.begin() as connection:
                connection.execute(AccountEnrollment.__table__.insert().values(**row))
        assert getattr(rejected.value.orig, "sqlstate", None) == "23514"
        assert rejected.value.orig.diag.constraint_name == "ck_account_enrollments_creation_binding"
    finally:
        engine.dispose()


@pytest.mark.parametrize("kind", ("legacy", "active_unbound", "bound"))
def test_creation_binding_postgres_check_preserves_legacy_and_accepts_complete_proof(kind: str) -> None:
    engine, _ = _session_factory()
    row = _creation_binding_pg_row()
    if kind != "bound":
        row.update(
            created_account_id=None,
            creation_binding_hmac=None,
            creation_binding_key_version=None,
        )
    if kind == "active_unbound":
        row.update(state="ACTIVE", consumed_at=None, otp_hmac="c" * 64)
    try:
        with engine.begin() as connection:
            connection.execute(AccountEnrollment.__table__.insert().values(**row))
        with engine.connect() as connection:
            assert connection.execute(
                select(func.count(AccountEnrollment.id)).where(AccountEnrollment.id == row["id"])
            ).scalar_one() == 1
    finally:
        engine.dispose()


def test_creation_binding_postgres_unique_prevents_two_enrollments_claiming_one_account() -> None:
    engine, _ = _session_factory()
    first = _creation_binding_pg_row()
    second = _creation_binding_pg_row()
    second["created_account_id"] = first["created_account_id"]
    try:
        with engine.begin() as connection:
            connection.execute(AccountEnrollment.__table__.insert().values(**first))
        with pytest.raises(IntegrityError) as rejected:
            with engine.begin() as connection:
                connection.execute(AccountEnrollment.__table__.insert().values(**second))
        assert getattr(rejected.value.orig, "sqlstate", None) == "23505"
        assert rejected.value.orig.diag.constraint_name == "uq_account_enrollments_created_account"
        with engine.connect() as connection:
            assert connection.execute(select(func.count(AccountEnrollment.id)).where(
                AccountEnrollment.created_account_id == first["created_account_id"]
            )).scalar_one() == 1
    finally:
        engine.dispose()


def test_creation_replay_downgrade_refuses_to_discard_success_binding(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, _ = _session_factory()
    row = _creation_binding_pg_row()
    migration = importlib.import_module(
        "backend.alembic.versions.202609210001_account_creation_replay"
    )
    try:
        with engine.begin() as connection:
            connection.execute(AccountEnrollment.__table__.insert().values(**row))
        with engine.connect() as connection:
            transaction = connection.begin()
            monkeypatch.setattr(migration, "op", Operations(MigrationContext.configure(connection)))
            try:
                with pytest.raises(SQLAlchemyError) as rejected:
                    migration.downgrade()
                assert getattr(rejected.value.orig, "sqlstate", None) == "55000"
            finally:
                transaction.rollback()
        with engine.connect() as connection:
            stored = connection.execute(select(
                AccountEnrollment.created_account_id,
                AccountEnrollment.creation_binding_hmac,
                AccountEnrollment.creation_binding_key_version,
            ).where(AccountEnrollment.id == row["id"])).one()
            assert tuple(stored) == (
                row["created_account_id"], row["creation_binding_hmac"],
                row["creation_binding_key_version"],
            )
    finally:
        engine.dispose()



def test_postgres_account_tables_never_define_raw_birth_or_email_columns() -> None:
    engine, _ = _session_factory()
    inspector = inspect(engine)
    for table_name in (
        "account_enrollments",
        "user_accounts",
        "signup_consent_receipts",
    ):
        columns = {column["name"] for column in inspector.get_columns(table_name)}
        assert "date_of_birth" not in columns
        assert "birth_date" not in columns
        assert "email" not in columns
    engine.dispose()
