"""S01-12: an issued legacy scrypt cost still logs in on PostgreSQL and moves to the current cost."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import os
from threading import Barrier
import uuid

import pytest
from sqlalchemy import select, text
from sqlalchemy.orm import Session

from backend.app.account_schemas import AccountAuthenticateRequestV1
from backend.app.main import settings
from backend.app.models import UserAccount
from backend.app.services import accounts as accounts_service
from backend.app.services.accounts import AccountService, verify_account_password
from backend.tests.test_accounts_postgres_integration import (
    _active_enrollment,
    _create_payload,
    _session_factory,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

_PASSWORD = "correct horse battery"


def _auth(email: str, password: str = _PASSWORD) -> AccountAuthenticateRequestV1:
    return AccountAuthenticateRequestV1(
        schema_version="walksafe.account-authenticate.v1",
        email=email,
        password=password,
    )


def _new_account(service: AccountService, session_factory) -> tuple[str, str]:
    suffix = uuid.uuid4().hex
    email = f"s01-12-{suffix}@example.com"
    issued = _active_enrollment(service, session_factory, email=email, suffix=suffix)
    with session_factory() as db:
        created = service.create_account(
            db,
            _create_payload(issued.response.enrollment_handle, issued.delivery.code),
        )
    return email, created.actor_id


def _stored_hash(session_factory, actor_id: str) -> str:
    with session_factory() as db:
        return db.scalar(
            select(UserAccount.password_hash).where(UserAccount.actor_id == actor_id)
        )


def test_legacy_cost_login_rehashes_and_releases_the_row_lock(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, session_factory = _session_factory()
    try:
        service = AccountService(settings)
        email, actor_id = _new_account(service, session_factory)
        legacy = _stored_hash(session_factory, actor_id)
        assert legacy.split("$")[1:4] == ["16384", "8", "1"]

        # Raising the current cost must not lock out the account issued at the old cost.
        monkeypatch.setattr(accounts_service, "_SCRYPT_P", 2)
        with session_factory() as db:
            result = service.authenticate(db, _auth(email))
            assert result.actor_id == actor_id
            assert not db.in_transaction()
            # The FOR UPDATE lock ends with the upgrade commit, not at session close.
            with engine.connect() as other:
                other.execute(text("SET lock_timeout = '200ms'"))
                other.execute(
                    select(UserAccount.id)
                    .where(UserAccount.actor_id == actor_id)
                    .with_for_update()
                ).one()
                other.rollback()
        upgraded = _stored_hash(session_factory, actor_id)
        assert upgraded.split("$")[1:4] == ["16384", "8", "2"]
        assert verify_account_password(_PASSWORD, upgraded)

        # A wrong password neither logs in nor rewrites the stored hash.
        with session_factory() as db:
            with pytest.raises(accounts_service.AccountServiceError):
                service.authenticate(db, _auth(email, "wrong password value"))
        assert _stored_hash(session_factory, actor_id) == upgraded
    finally:
        engine.dispose()


def test_concurrent_legacy_logins_upgrade_the_hash_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, session_factory = _session_factory()
    try:
        service = AccountService(settings)
        email, actor_id = _new_account(service, session_factory)
        monkeypatch.setattr(accounts_service, "_SCRYPT_P", 2)
        # authenticate() commits only when the compare-and-set upgrade wrote a row.
        commits: list[int] = []
        original_commit = Session.commit

        def counting_commit(self: Session) -> None:
            commits.append(1)
            original_commit(self)

        monkeypatch.setattr(Session, "commit", counting_commit)
        barrier = Barrier(2)

        def login() -> str:
            with session_factory() as db:
                barrier.wait()
                try:
                    return service.authenticate(db, _auth(email)).actor_id
                except accounts_service.AccountServiceError as exc:
                    return f"ERR:{exc.code}"

        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda _: login(), range(2)))
        # The default verification gate admits both logins; both succeed, one upgrade commits.
        assert results == [actor_id, actor_id]
        assert len(commits) == 1
        final = _stored_hash(session_factory, actor_id)
        assert final.split("$")[1:4] == ["16384", "8", "2"]
        assert verify_account_password(_PASSWORD, final)
    finally:
        engine.dispose()
