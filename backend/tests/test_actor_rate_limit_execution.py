"""Bounded rate admission without PostgreSQL or network connections."""

from __future__ import annotations

import asyncio
from concurrent.futures import Future, ThreadPoolExecutor
from contextlib import contextmanager
import hashlib
from threading import Event, Lock, get_ident
from time import monotonic, sleep
from types import SimpleNamespace

import pytest

from backend.app.config import Settings, get_settings
from backend.app.services import actor_rate_limit as rates
from backend.app.services.accounts import (
    AccountAuthenticationRateLimiter, AccountEnrollmentRateLimiter, AccountServiceError,
)


class Clock:
    def __init__(self) -> None:
        self.value = 0.0
        self.lock = Lock()

    def __call__(self) -> float:
        with self.lock:
            return self.value

    def advance(self, seconds: float) -> None:
        with self.lock:
            self.value += seconds


class RecordingSessions:
    """Supply DB outcomes and timing only; rate/deadline decisions stay in production."""

    def __init__(self, hook=None, *, count=0, retry=7) -> None:
        self.hook = hook or (lambda _stage, _store: None)
        self.count = count
        self.retry = retry
        self.calls = []
        self.entries = 0
        self.commits = 0
        self.rollbacks = 0
        self.closes = 0
        self.threads = []
        self.lock = Lock()
        self.closed = Event()

    @contextmanager
    def begin(self):
        with self.lock:
            self.entries += 1
            self.threads.append(get_ident())
        try:
            self.hook("connect", self)
            yield self
            self.hook("commit", self)
        except BaseException:
            self.rollbacks += 1
            self.hook("rollback", self)
            raise
        else:
            self.commits += 1
        finally:
            self.closes += 1
            self.closed.set()

    def execute(self, statement, parameters):
        sql = str(statement)
        if "set_config" in sql:
            stage = "timeout"
        elif "pg_advisory_xact_lock" in sql:
            stage = "lock"
        elif sql.startswith("DELETE"):
            stage = "delete"
        elif sql.startswith("SELECT count"):
            stage = "count"
        elif sql.startswith("INSERT"):
            stage = "insert"
        else:
            raise AssertionError("unexpected rate-limit SQL")
        self.calls.append((stage, sql, dict(parameters)))
        self.hook(stage, self)
        return SimpleNamespace(mappings=lambda: SimpleNamespace(
            one=lambda: {"event_count": self.count, "retry_after": self.retry},
        ))


@contextmanager
def runner(*, capacity=1, budget_ms=1000, statement_ms=500, clock=monotonic, factory=ThreadPoolExecutor):
    execution = rates.ActorRateLimitExecution(
        capacity, budget_ms, statement_ms, clock=clock, executor_factory=factory,
    )
    try:
        yield execution
    finally:
        execution.shutdown()


def limiter(store, execution):
    return rates.PostgresActorRateLimiter({"report": 2}, 60, store, execution=execution)


def eventually_allowed(check):
    deadline = monotonic() + 1.0
    while True:
        try:
            return check()
        except rates.ActorRateLimitStoreUnavailable:
            if monotonic() >= deadline:
                raise
            sleep(0.001)


@pytest.mark.parametrize("capacity,budget,statement", [
    (0, 100, 100), (65, 100, 100), (True, 100, 100),
    (1, 0, 100), (1, 120001, 100), (1, True, 100),
    (1, 100, 0), (1, 100, 120001),
])
def test_execution_rejects_invalid_bounds(capacity, budget, statement) -> None:
    with pytest.raises(ValueError):
        rates.ActorRateLimitExecution(capacity, budget, statement)


@pytest.mark.parametrize("name,value", [
    ("WALKSAFE_ACTOR_RATE_LIMIT_MAX_IN_FLIGHT", "0"),
    ("WALKSAFE_ACTOR_RATE_LIMIT_MAX_IN_FLIGHT", "65"),
    ("WALKSAFE_ACTOR_RATE_LIMIT_MAX_IN_FLIGHT", "bad"),
    ("WALKSAFE_ACTOR_RATE_LIMIT_BUDGET_MS", "0"),
    ("WALKSAFE_ACTOR_RATE_LIMIT_BUDGET_MS", "120001"),
    ("WALKSAFE_ACTOR_RATE_LIMIT_BUDGET_MS", "bad"),
])
def test_settings_reject_invalid_execution_bounds(monkeypatch, name, value) -> None:
    monkeypatch.setenv(name, value)
    with pytest.raises(ValueError):
        Settings()


def test_settings_expose_defaults_and_explicit_execution_bounds(monkeypatch) -> None:
    monkeypatch.delenv("WALKSAFE_ACTOR_RATE_LIMIT_MAX_IN_FLIGHT", raising=False)
    monkeypatch.delenv("WALKSAFE_ACTOR_RATE_LIMIT_BUDGET_MS", raising=False)
    default = Settings()
    assert default.actor_rate_limit_max_in_flight == 4
    assert default.actor_rate_limit_budget_ms == 5000
    monkeypatch.setenv("WALKSAFE_ACTOR_RATE_LIMIT_MAX_IN_FLIGHT", "2")
    monkeypatch.setenv("WALKSAFE_ACTOR_RATE_LIMIT_BUDGET_MS", "73")
    explicit = Settings()
    assert explicit.actor_rate_limit_max_in_flight == 2
    assert explicit.actor_rate_limit_budget_ms == 73


@pytest.mark.parametrize("count,expected", [(0, None), (2, 7)])
def test_allow_and_quota_keep_database_clock_and_transaction_contract(count, expected) -> None:
    clock = Clock()

    def advance(stage, _store):
        if stage in {"lock", "delete", "count", "insert"}:
            clock.advance(0.15)

    store = RecordingSessions(advance, count=count)
    caller = get_ident()
    with runner(clock=clock) as execution:
        assert limiter(store, execution).check("actor@example.com", "report") == expected
    statements = [stage for stage, _, _ in store.calls if stage != "timeout"]
    assert statements == ["lock", "delete", "count"] + (["insert"] if expected is None else [])
    timeouts = [int(parameters["timeout_ms"]) for stage, _, parameters in store.calls if stage == "timeout"]
    assert len(timeouts) == len(statements) + 1
    assert all(1 <= value <= 500 for value in timeouts)
    assert timeouts == sorted(timeouts, reverse=True)
    if expected is None:
        assert timeouts[-1] < timeouts[0]
    assert store.commits == store.closes == 1 and store.rollbacks == 0
    assert store.threads[0] != caller
    digest = hashlib.sha256(b"actor@example.com").hexdigest()
    for stage, sql, parameters in store.calls:
        assert "actor@example.com" not in str(parameters)
        if stage == "lock":
            assert parameters == {"lock_key": f"walksafe-actor-rate-v1:{digest}:report"}
        elif stage in {"delete", "count"}:
            assert parameters == {"actor_digest": digest, "group": "report", "window_seconds": 60}
            assert "clock_timestamp()" in sql
        elif stage == "timeout":
            assert sql == "SELECT set_config('statement_timeout', :timeout_ms, true)"


@pytest.mark.parametrize("stage", ["connect", "lock", "delete", "count", "insert", "precommit"])
def test_one_deadline_expires_at_each_database_boundary_and_rolls_back(stage) -> None:
    clock = Clock()

    def expire(observed, store):
        if observed == stage or (
            stage == "precommit" and observed == "timeout"
            and sum(item[0] == "timeout" for item in store.calls) == 5
        ):
            clock.advance(1.0)

    store = RecordingSessions(expire)
    with runner(clock=clock) as execution:
        with pytest.raises(rates.ActorRateLimitStoreUnavailable):
            limiter(store, execution).check("actor", "report")
    assert store.commits == 0
    assert store.rollbacks == store.closes == 1
    stages = [item[0] for item in store.calls if item[0] != "timeout"]
    expected = {
        "connect": [], "lock": ["lock"], "delete": ["lock", "delete"],
        "count": ["lock", "delete", "count"], "insert": ["lock", "delete", "count", "insert"],
        "precommit": ["lock", "delete", "count", "insert"],
    }
    assert stages == expected[stage]


def test_late_commit_cannot_return_an_admission_but_may_have_consumed_quota() -> None:
    clock = Clock()
    store = RecordingSessions(lambda stage, _store: clock.advance(1.0) if stage == "commit" else None)
    with runner(clock=clock) as execution:
        with pytest.raises(rates.ActorRateLimitStoreUnavailable):
            limiter(store, execution).check("actor", "report")
    assert store.commits == store.closes == 1
    assert store.rollbacks == 0


def test_sync_timeout_keeps_capacity_until_worker_cleanup_then_recovers() -> None:
    started, release = Event(), Event()

    def block(stage, _store):
        if stage == "connect":
            started.set()
            if not release.wait(2):
                raise RuntimeError("test DB boundary was not released")

    store = RecordingSessions(block)
    with runner(budget_ms=50) as execution:
        current = limiter(store, execution)
        other = limiter(RecordingSessions(), execution)
        try:
            with ThreadPoolExecutor(max_workers=1) as callers:
                pending = callers.submit(current.check, "actor", "report")
                assert started.wait(1)
                with pytest.raises(rates.ActorRateLimitStoreUnavailable):
                    pending.result(timeout=1)
                assert not store.closed.is_set()
                with pytest.raises(rates.ActorRateLimitStoreUnavailable):
                    other.check("other", "report")
                assert store.entries == 1
                release.set()
                assert store.closed.wait(1)
                assert eventually_allowed(lambda: other.check("other", "report")) is None
        finally:
            release.set()
    assert store.commits == 0
    assert store.rollbacks == store.closes == 1


@pytest.mark.parametrize("termination", ["timeout", "cancel"])
def test_async_timeout_or_cancel_keeps_capacity_until_real_cleanup(termination) -> None:
    started, release = Event(), Event()
    store = RecordingSessions(lambda stage, _store: (
        started.set(), release.wait(2)
    ) if stage == "connect" else None)

    async def exercise(execution):
        current = limiter(store, execution)
        other_store = RecordingSessions()
        other = limiter(other_store, execution)
        pending = asyncio.create_task(current.check_async("actor", "report"))
        assert await asyncio.to_thread(started.wait, 1)
        if termination == "cancel":
            pending.cancel()
            with pytest.raises(asyncio.CancelledError):
                await pending
        else:
            with pytest.raises(rates.ActorRateLimitStoreUnavailable):
                await asyncio.wait_for(pending, 1)
        assert not store.closed.is_set()
        with pytest.raises(rates.ActorRateLimitStoreUnavailable):
            await other.check_async("other", "report")
        assert other_store.entries == 0
        release.set()
        assert await asyncio.to_thread(store.closed.wait, 1)
        assert await asyncio.to_thread(
            eventually_allowed, lambda: other.check("other", "report"),
        ) is None

    with runner(budget_ms=50 if termination == "timeout" else 5000) as execution:
        try:
            asyncio.run(exercise(execution))
        finally:
            release.set()
    assert store.commits == 0
    assert store.rollbacks == store.closes == 1


@pytest.mark.parametrize("after_enqueue", [False, True])
def test_uncertain_submit_failure_quarantines_dispatcher_without_extra_work(after_enqueue) -> None:
    submissions = []
    action_finished = Event()

    class BrokenSubmission(ThreadPoolExecutor):
        def submit(self, fn, *args, **kwargs):
            submissions.append(True)
            if after_enqueue:
                def wrapped():
                    try:
                        return fn(*args, **kwargs)
                    finally:
                        action_finished.set()
                super().submit(wrapped)
            raise RuntimeError("submission failed")

    store = RecordingSessions()
    with runner(factory=BrokenSubmission) as execution:
        current = limiter(store, execution)
        with pytest.raises(rates.ActorRateLimitStoreUnavailable):
            current.check("actor", "report")
        if after_enqueue:
            assert action_finished.wait(1)
        before = store.entries
        with pytest.raises(rates.ActorRateLimitStoreUnavailable):
            current.check("other", "report")
        assert submissions == [True]
        assert store.entries == before
    if not after_enqueue:
        assert store.entries == 0
    assert store.entries <= 1


def test_closed_dispatcher_rejects_before_session_creation() -> None:
    execution = rates.ActorRateLimitExecution(1, 1000, 500)
    execution.shutdown()
    store = RecordingSessions()
    with pytest.raises(rates.ActorRateLimitStoreUnavailable):
        limiter(store, execution).check("actor", "report")
    assert store.entries == 0


@pytest.mark.parametrize("stage", ["lock", "delete", "count", "insert", "commit", "rollback"])
def test_worker_failures_close_session_and_leave_capacity_reusable(stage) -> None:
    failing = True

    def fail(observed, _store):
        if failing and (observed == stage or (stage == "rollback" and observed == "lock")):
            raise RuntimeError("synthetic database failure")

    store = RecordingSessions(fail)
    with runner() as execution:
        current = limiter(store, execution)
        with pytest.raises(rates.ActorRateLimitStoreUnavailable):
            current.check("actor", "report")
        assert store.closes == store.rollbacks == 1 and store.commits == 0
        failing = False
        assert current.check("other", "report") is None
    assert store.closes == 2 and store.commits == 1


def test_default_execution_is_shared_by_independent_and_account_bucket_limiters(monkeypatch) -> None:
    capacity = get_settings().actor_rate_limit_max_in_flight
    all_started, release = Event(), Event()

    def block(stage, store):
        if stage == "connect" and not release.is_set():
            if store.entries >= capacity:
                all_started.set()
            if not release.wait(3):
                raise RuntimeError("shared rate worker was not released")

    store = RecordingSessions(block)
    monkeypatch.setattr(rates.PostgresActorRateLimiter, "_sessions", lambda _self: store)
    settings = SimpleNamespace(actor_rate_limit_store="postgresql")
    groups = [
        "account_enrollment_global", "account_enrollment_ip", "account_enrollment_email",
        "account_authentication_global", "account_authentication_email",
    ]
    for group in groups:
        setattr(settings, group + "_limit", 2)
        setattr(settings, group + "_window_seconds", 60)
    enrollment = AccountEnrollmentRateLimiter(settings)
    authentication = AccountAuthenticationRateLimiter(settings)

    def occupy(index):
        current = rates.PostgresActorRateLimiter({"report": 2})
        # Submission-lock contention is also a non-waiting rejection. Fill the
        # actual DB capacity before testing the account callers' shared bound.
        return eventually_allowed(lambda: current.check(f"actor-{index}", "report"))

    with ThreadPoolExecutor(max_workers=capacity) as callers:
        pending = [
            callers.submit(occupy, i)
            for i in range(capacity)
        ]
        try:
            assert all_started.wait(1)
            for action, code in (
                (lambda: enrollment.check_source(source_ip="192.0.2.1"), "account_enrollment_unavailable"),
                (lambda: enrollment.check_email(email_lookup_hmac="a" * 64), "account_enrollment_unavailable"),
                (lambda: authentication.check(email_lookup_hmac="a" * 64), "account_authentication_unavailable"),
            ):
                with pytest.raises(AccountServiceError) as caught:
                    action()
                assert caught.value.status_code == 503
                assert caught.value.code == code
            assert store.entries == capacity
        finally:
            release.set()
        assert all(future.result(timeout=1) is None for future in pending)
    enrollment.check_source(source_ip="192.0.2.1")
    enrollment.check_email(email_lookup_hmac="a" * 64)
    authentication.check(email_lookup_hmac="a" * 64)
    selected = {params["group"] for stage, _, params in store.calls if stage == "count"}
    assert selected == {"report", *groups}


@pytest.mark.parametrize("asynchronous", [False, True])
def test_caller_rechecks_deadline_after_receiving_a_completed_result(asynchronous) -> None:
    clock = Clock()

    class ResultClockFuture(Future):
        def result(self, timeout=None):
            value = super().result(timeout=timeout)
            clock.advance(1.0)
            return value

    class ResultClockPool(ThreadPoolExecutor):
        def submit(self, fn, *args, **kwargs):
            original = super().submit(fn, *args, **kwargs)
            delivered = ResultClockFuture()

            def deliver(done):
                try:
                    delivered.set_result(done.result())
                except BaseException as error:
                    delivered.set_exception(error)

            original.add_done_callback(deliver)
            return delivered

    store = RecordingSessions()
    with runner(clock=clock, factory=ResultClockPool) as execution:
        current = limiter(store, execution)
        with pytest.raises(rates.ActorRateLimitStoreUnavailable):
            if asynchronous:
                asyncio.run(current.check_async("actor", "report"))
            else:
                current.check("actor", "report")
    assert store.commits == store.closes == 1 and store.rollbacks == 0


@pytest.mark.parametrize("asynchronous", [False, True])
def test_default_dispatcher_lock_contention_is_a_nonwaiting_rejection(asynchronous) -> None:
    store = RecordingSessions()
    current = rates.PostgresActorRateLimiter({"report": 2}, session_factory=store)

    def attempt():
        if asynchronous:
            return asyncio.run(current.check_async("actor", "report"))
        return current.check("actor", "report")

    # Contend the actual default-dispatcher lock, without replacing its decision.
    with ThreadPoolExecutor(max_workers=1) as callers:
        rates._DEFAULT_EXECUTION_LOCK.acquire()
        try:
            pending = callers.submit(attempt)
            with pytest.raises(rates.ActorRateLimitStoreUnavailable):
                pending.result(timeout=0.5)
            assert store.entries == 0
        finally:
            rates._DEFAULT_EXECUTION_LOCK.release()
        # The same default path remains usable after acquisition contention ends.
        assert pending.done()
        assert callers.submit(attempt).result(timeout=1) is None
    assert store.entries == store.commits == store.closes == 1


@pytest.mark.parametrize("asynchronous", [False, True])
def test_default_dispatcher_acquisition_cannot_restart_the_check_entry_budget(
    monkeypatch, asynchronous,
) -> None:
    clock = Clock()
    store = RecordingSessions()
    with runner(clock=clock) as execution:
        def delayed_default():
            clock.advance(1.0)
            return execution

        monkeypatch.setattr(rates, "monotonic", clock)
        monkeypatch.setattr(rates, "_default_execution", delayed_default)
        current = rates.PostgresActorRateLimiter({"report": 2}, session_factory=store)
        with pytest.raises(rates.ActorRateLimitStoreUnavailable):
            if asynchronous:
                asyncio.run(current.check_async("actor", "report"))
            else:
                current.check("actor", "report")
        assert store.entries == 0

        # No acquisition delay: the next independent check gets its own budget.
        monkeypatch.setattr(rates, "_default_execution", lambda: execution)
        assert current.check("actor", "report") is None
    assert store.entries == store.commits == store.closes == 1


@pytest.mark.parametrize("asynchronous", [False, True])
def test_postgres_still_rejects_a_caller_supplied_clock_before_database_work(asynchronous) -> None:
    store = RecordingSessions()
    with runner() as execution:
        current = limiter(store, execution)
        with pytest.raises(ValueError, match="database clock"):
            if asynchronous:
                asyncio.run(current.check_async("actor", "report", now=1.0))
            else:
                current.check("actor", "report", now=1.0)
    assert store.entries == 0
