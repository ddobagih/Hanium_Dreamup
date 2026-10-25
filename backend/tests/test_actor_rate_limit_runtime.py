"""Real app assembly and lifespan with fake database transactions only."""
from __future__ import annotations

import asyncio
from threading import Event

import httpx
import pytest

from backend.app import field_test_security
from backend.app.main import create_app
from backend.app.services import actor_rate_limit as rates
from backend.app.services.accounts import AccountAuthenticationRateLimiter, AccountServiceError
from backend.tests.test_actor_rate_limit_execution import RecordingSessions
from backend.tests.test_application_factory import OwnedDatabase, isolate_startup, settings_for


@pytest.fixture
def isolated_process_budget(monkeypatch):
    # Preserve any standalone execution owned by another test in this process.
    monkeypatch.setattr(rates, "_DEFAULT_EXECUTION", None)
    monkeypatch.setattr(rates, "_DEFAULT_EXECUTION_CONFIG", None)
    monkeypatch.setattr(rates, "_DEFAULT_EXECUTION_OWNERS", set())
    yield
    assert not rates._DEFAULT_EXECUTION_OWNERS
    assert rates._DEFAULT_EXECUTION is None


def rate_app(tmp_path, monkeypatch, store, *, capacity=1):
    settings = settings_for(tmp_path)
    settings.actor_rate_limit_store = "postgresql"
    settings.actor_rate_limit_max_in_flight = capacity
    settings.actor_rate_limit_budget_ms = 80
    settings.database_statement_timeout_ms = 43
    settings.field_test_security_enabled = True
    settings.field_test_token = "owned-field-token-for-runtime-tests"
    settings.walksafe_environment = "development"
    settings.allow_insecure_local_dev = True
    db = OwnedDatabase("owned")
    db.session_factory = store
    app = create_app(settings, database=db)
    isolate_startup(app.state.runtime, monkeypatch)
    monkeypatch.setattr(field_test_security, "_actor_account_session_is_current", lambda *_args: True)
    return app, db


@pytest.mark.parametrize("cancel_shutdown", [False, True])
def test_production_lifespan_shares_budget_uses_app_settings_and_drains_only_owned_work(
    tmp_path, monkeypatch, isolated_process_budget, cancel_shutdown,
):
    entered, release = Event(), Event()

    def block(stage, _store):
        if stage == "connect":
            entered.set()
            assert release.wait(3)

    first_store, second_store = RecordingSessions(block), RecordingSessions(count=1000)
    one, first_db = rate_app(tmp_path / "one", monkeypatch, first_store)
    two, second_db = rate_app(tmp_path / "two", monkeypatch, second_store)
    headers = {
        "x-walksafe-field-test-token": one.state.runtime.settings.field_test_token,
        "x-walksafe-actor-id": "owned.runtime@example.com",
    }

    async def run():
        first_life = one.router.lifespan_context(one)
        async with two.router.lifespan_context(two):
            await first_life.__aenter__()
            shared = rates._DEFAULT_EXECUTION
            assert one.state.runtime.actor_rate_limits.execution() is shared
            assert two.state.runtime.actor_rate_limits.execution() is shared
            assert rates._DEFAULT_EXECUTION_CONFIG == (1, 80, 43)
            closing = None
            try:
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=one), base_url="http://test") as first:
                    pending = asyncio.create_task(first.post("/detect/v2", headers=headers))
                    assert await asyncio.to_thread(entered.wait, 1)
                    assert (await first.get("/health", headers=headers)).status_code == 200
                    expired = await asyncio.wait_for(pending, 1)
                    assert expired.status_code == 503
                    assert expired.json()["detail"]["code"] == "actor_rate_limit_store_unavailable"
                with two.state.runtime.context():
                    with pytest.raises(rates.ActorRateLimitStoreUnavailable):
                        await rates.PostgresActorRateLimiter({"report": 2}).check_async("actor", "report")
                    with pytest.raises(AccountServiceError) as rejected:
                        await asyncio.to_thread(
                            AccountAuthenticationRateLimiter(two.state.runtime.settings).check,
                            email_lookup_hmac="a" * 64,
                        )
                    assert rejected.value.code == "account_authentication_unavailable"
                assert second_store.entries == 0
                closing = asyncio.create_task(first_life.__aexit__(None, None, None))
                await asyncio.sleep(0.03)
                assert not closing.done()
                assert first_db.closed == 0
                assert not first_store.closed.is_set()
                if cancel_shutdown:
                    closing.cancel()
                    with pytest.raises(TimeoutError):
                        await asyncio.wait_for(asyncio.shield(closing), 0.05)
                    assert first_db.closed == 0
                release.set()
                if cancel_shutdown:
                    with pytest.raises(asyncio.CancelledError):
                        await asyncio.wait_for(closing, 2)
                else:
                    await asyncio.wait_for(closing, 2)
                assert first_db.closed == 1
                assert first_store.closes == first_store.rollbacks == 1
                assert second_db.closed == 0
                assert not shared._closed
                with one.state.runtime.context(), pytest.raises(rates.ActorRateLimitStoreUnavailable):
                    await rates.PostgresActorRateLimiter({"report": 2}).check_async("late", "report")
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=two), base_url="http://test") as second:
                    limited = await second.post("/detect/v2", headers=headers)
                    assert limited.status_code == 429
                    assert limited.headers["retry-after"] == "7"
                assert second_store.entries == second_store.closes == 1
                timeouts = [int(params["timeout_ms"]) for stage, _, params in second_store.calls if stage == "timeout"]
                assert timeouts and all(1 <= timeout <= 43 for timeout in timeouts)
            finally:
                release.set()
                if closing is None:
                    await first_life.__aexit__(None, None, None)
                elif not closing.cancelled():
                    await closing
        assert shared._closed
        assert second_db.closed == 1
        assert rates._DEFAULT_EXECUTION is None
        async with one.router.lifespan_context(one):
            assert rates._DEFAULT_EXECUTION is not shared
            assert not rates._DEFAULT_EXECUTION._closed

    asyncio.run(run())


def test_incompatible_app_budget_fails_startup_without_closing_active_owner(
    tmp_path, monkeypatch, isolated_process_budget,
):
    one, first_db = rate_app(tmp_path / "one", monkeypatch, RecordingSessions())
    two, second_db = rate_app(tmp_path / "two", monkeypatch, RecordingSessions(), capacity=2)

    async def run():
        async with one.router.lifespan_context(one):
            shared = rates._DEFAULT_EXECUTION
            with pytest.raises(ValueError, match="matching execution settings"):
                async with two.router.lifespan_context(two):
                    pytest.fail("conflicting process budget must not start")
            assert second_db.closed == 1
            assert first_db.closed == 0
            assert one.state.runtime.actor_rate_limits.execution() is shared
            assert not shared._closed
        assert shared._closed

    asyncio.run(run())


def test_quarantined_submission_drains_untracked_work_before_app_database_close(
    tmp_path, monkeypatch, isolated_process_budget,
):
    entered, release = Event(), Event()

    def block(stage, _store):
        if stage == "connect":
            entered.set()
            assert release.wait(3)

    one, first_db = rate_app(tmp_path / "one", monkeypatch, RecordingSessions(block))
    two, _ = rate_app(tmp_path / "two", monkeypatch, RecordingSessions())

    async def run():
        first_life = one.router.lifespan_context(one)
        async with two.router.lifespan_context(two):
            await first_life.__aenter__()
            execution = rates._DEFAULT_EXECUTION
            submit = execution._executor.submit

            def uncertain_submit(*args, **kwargs):
                submit(*args, **kwargs)
                assert entered.wait(1)
                raise RuntimeError("submission failed after queueing")

            monkeypatch.setattr(execution._executor, "submit", uncertain_submit)
            closing = None
            try:
                with one.state.runtime.context(), pytest.raises(rates.ActorRateLimitStoreUnavailable):
                    await rates.PostgresActorRateLimiter({"report": 2}).check_async("actor", "report")
                assert execution._quarantined
                assert not one.state.runtime.actor_rate_limits._pending
                closing = asyncio.create_task(first_life.__aexit__(None, None, None))
                with pytest.raises(TimeoutError):
                    await asyncio.wait_for(asyncio.shield(closing), 0.05)
                assert first_db.closed == 0
                release.set()
                await asyncio.wait_for(closing, 2)
                assert first_db.closed == 1
                assert execution._closed
            finally:
                release.set()
                if closing is None:
                    await first_life.__aexit__(None, None, None)
                else:
                    await closing

    asyncio.run(run())
