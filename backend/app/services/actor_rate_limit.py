"""Atomic actor rate limiting shared by all backend workers and replicas."""

from __future__ import annotations

import asyncio
from collections import deque
from concurrent.futures import CancelledError as FutureCancelledError, ThreadPoolExecutor
import hashlib
import math
from threading import BoundedSemaphore, Event, Lock
from time import monotonic
from typing import Any, Callable

from sqlalchemy import text


class ActorRateLimitStoreUnavailable(RuntimeError):
    """Raised when a deployment cannot safely account for a request."""


class InMemoryActorRateLimiter:
    def __init__(self, limits: dict[str, int], window_seconds: float) -> None:
        self._limits = limits
        self._window_seconds = window_seconds
        self._events: dict[tuple[str, str], deque[float]] = {}
        self._lock = Lock()

    def check(self, actor_id: str, group: str, *, now: float | None = None) -> int | None:
        current = monotonic() if now is None else now
        starts_at = current - self._window_seconds
        key = (actor_id, group)
        with self._lock:
            events = self._events.setdefault(key, deque())
            while events and events[0] <= starts_at:
                events.popleft()
            if len(events) >= self._limits[group]:
                return max(1, math.ceil(self._window_seconds - (current - events[0])))
            events.append(current)
            if len(self._events) > 10_000:
                self._events = {
                    event_key: event_values
                    for event_key, event_values in self._events.items()
                    if event_values and event_values[-1] > starts_at
                }
        return None

    def reset(self) -> None:
        with self._lock:
            self._events.clear()



class _DecisionBudget:
    def __init__(
        self, budget_ms: int, statement_timeout_ms: int, clock: Callable[[], float],
        *, started_at: float | None = None,
    ) -> None:
        self._clock = clock
        self._deadline = (clock() if started_at is None else started_at) + budget_ms / 1000.0
        self._statement_timeout_ms = statement_timeout_ms
        self._cancelled = Event()

    def cancel(self) -> None:
        self._cancelled.set()

    def remaining_seconds(self) -> float:
        remaining = self._deadline - self._clock()
        if self._cancelled.is_set() or remaining <= 0:
            raise ActorRateLimitStoreUnavailable
        return remaining

    def statement_timeout_ms(self) -> int:
        return min(
            self._statement_timeout_ms,
            max(1, math.floor(self.remaining_seconds() * 1000)),
        )


class _ReleaseOnce:
    def __init__(self, slots: BoundedSemaphore) -> None:
        self._slots = slots
        self._lock = Lock()
        self._released = False

    def __call__(self, _future: Any = None) -> None:
        with self._lock:
            if not self._released:
                self._released = True
                self._slots.release()


class ActorRateLimitExecution:
    """Bound caller wait and outstanding DB actions, not OS/DB cancellation."""

    def __init__(
        self,
        max_in_flight: int,
        budget_ms: int,
        statement_timeout_ms: int,
        *,
        clock: Callable[[], float] = monotonic,
        executor_factory: Callable[..., Any] = ThreadPoolExecutor,
    ) -> None:
        for value, upper in (
            (max_in_flight, 64), (budget_ms, 120_000), (statement_timeout_ms, 120_000),
        ):
            if type(value) is not int or not 1 <= value <= upper:
                raise ValueError("invalid actor rate-limit execution bounds")
        self._budget_ms = budget_ms
        self._statement_timeout_ms = statement_timeout_ms
        self._clock = clock
        self._slots = BoundedSemaphore(max_in_flight)
        self._state_lock = Lock()
        self._closed = False
        self._quarantined = False
        self._executor = executor_factory(
            max_workers=max_in_flight, thread_name_prefix="actor-rate-limit",
        )

    def now(self) -> float:
        return self._clock()

    def _submit(
        self, action: Callable[[_DecisionBudget], Any], *, started_at: float | None = None,
    ) -> tuple[Any, _DecisionBudget]:
        budget = _DecisionBudget(
            self._budget_ms, self._statement_timeout_ms, self._clock, started_at=started_at,
        )
        # Do not introduce another waiting queue around submission.
        if not self._state_lock.acquire(blocking=False):
            raise ActorRateLimitStoreUnavailable
        try:
            if self._closed or self._quarantined:
                raise ActorRateLimitStoreUnavailable
            budget.remaining_seconds()
            if not self._slots.acquire(blocking=False):
                raise ActorRateLimitStoreUnavailable
            release = _ReleaseOnce(self._slots)

            def work() -> Any:
                try:
                    budget.remaining_seconds()
                    return action(budget)
                finally:
                    # The action owns its Session, including commit/rollback/close.
                    release()

            try:
                future = self._executor.submit(work)
            except Exception as exc:
                budget.cancel()
                # submit() can fail after enqueueing work. Do not reuse uncertain
                # capacity or submit more work to a potentially unhealthy pool.
                self._quarantined = True
                raise ActorRateLimitStoreUnavailable from exc
            # Handles cancellation before work() starts; normal release is once.
            future.add_done_callback(release)
            return future, budget
        finally:
            self._state_lock.release()

    def run(
        self, action: Callable[[_DecisionBudget], Any], *, started_at: float | None = None,
    ) -> Any:
        future, budget = self._submit(action, started_at=started_at)
        try:
            result = future.result(timeout=budget.remaining_seconds())
            budget.remaining_seconds()
            return result
        except (TimeoutError, FutureCancelledError) as exc:
            budget.cancel()
            future.cancel()
            raise ActorRateLimitStoreUnavailable from exc
        except BaseException:
            budget.cancel()
            future.cancel()
            raise

    async def run_async(
        self, action: Callable[[_DecisionBudget], Any], *, started_at: float | None = None,
    ) -> Any:
        future, budget = self._submit(action, started_at=started_at)
        wrapped = asyncio.wrap_future(future)

        def consume_late_exception(done: asyncio.Future[Any]) -> None:
            if not done.cancelled():
                done.exception()

        wrapped.add_done_callback(consume_late_exception)
        try:
            result = await asyncio.wait_for(
                asyncio.shield(wrapped), timeout=budget.remaining_seconds(),
            )
            budget.remaining_seconds()
            return result
        except asyncio.CancelledError:
            budget.cancel()
            future.cancel()
            raise
        except TimeoutError as exc:
            budget.cancel()
            future.cancel()
            raise ActorRateLimitStoreUnavailable from exc
        except BaseException:
            budget.cancel()
            future.cancel()
            raise

    def shutdown(self) -> None:
        with self._state_lock:
            self._closed = True
        self._executor.shutdown(wait=True, cancel_futures=True)


_DEFAULT_EXECUTION: ActorRateLimitExecution | None = None
_DEFAULT_EXECUTION_LOCK = Lock()


def _default_execution() -> ActorRateLimitExecution:
    global _DEFAULT_EXECUTION
    if not _DEFAULT_EXECUTION_LOCK.acquire(blocking=False):
        raise ActorRateLimitStoreUnavailable
    try:
        if _DEFAULT_EXECUTION is None:
            from backend.app.config import get_settings

            settings = get_settings()
            _DEFAULT_EXECUTION = ActorRateLimitExecution(
                settings.actor_rate_limit_max_in_flight,
                settings.actor_rate_limit_budget_ms,
                settings.database_statement_timeout_ms,
            )
        return _DEFAULT_EXECUTION
    finally:
        _DEFAULT_EXECUTION_LOCK.release()


class PostgresActorRateLimiter:
    """Serialize one actor/group key inside shared, bounded DB execution."""

    def __init__(
        self,
        limits: dict[str, int],
        window_seconds: int = 60,
        session_factory: Callable[[], Any] | None = None,
        *,
        execution: ActorRateLimitExecution | None = None,
    ) -> None:
        self._limits = limits
        self._window_seconds = window_seconds
        self._session_factory = session_factory
        self._execution = execution

    def _sessions(self) -> Callable[[], Any]:
        if self._session_factory is None:
            from backend.app.database import SessionLocal

            return SessionLocal
        return self._session_factory

    def check(self, actor_id: str, group: str, *, now: float | None = None) -> int | None:
        started_at = self._execution.now() if self._execution is not None else monotonic()
        if now is not None:
            raise ValueError("PostgreSQL rate limiting uses the database clock")
        execution = self._execution if self._execution is not None else _default_execution()
        return execution.run(
            lambda budget: self._check_in_db(actor_id, group, budget), started_at=started_at,
        )

    async def check_async(
        self, actor_id: str, group: str, *, now: float | None = None,
    ) -> int | None:
        started_at = self._execution.now() if self._execution is not None else monotonic()
        if now is not None:
            raise ValueError("PostgreSQL rate limiting uses the database clock")
        execution = self._execution if self._execution is not None else _default_execution()
        return await execution.run_async(
            lambda budget: self._check_in_db(actor_id, group, budget), started_at=started_at,
        )

    @staticmethod
    def _prepare_statement(db: Any, budget: _DecisionBudget) -> None:
        db.execute(
            text("SELECT set_config('statement_timeout', :timeout_ms, true)"),
            {"timeout_ms": str(budget.statement_timeout_ms())},
        )
        budget.remaining_seconds()

    def _execute(
        self, db: Any, budget: _DecisionBudget, statement: Any, parameters: dict[str, Any],
    ) -> Any:
        self._prepare_statement(db, budget)
        result = db.execute(statement, parameters)
        budget.remaining_seconds()
        return result

    def _check_in_db(self, actor_id: str, group: str, budget: _DecisionBudget) -> int | None:
        budget.remaining_seconds()
        actor_digest = hashlib.sha256(actor_id.encode("utf-8")).hexdigest()
        lock_key = f"walksafe-actor-rate-v1:{actor_digest}:{group}"
        budget.remaining_seconds()
        retry_after = None
        try:
            with self._sessions().begin() as db:
                budget.remaining_seconds()
                self._execute(
                    db, budget,
                    text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
                    {"lock_key": lock_key},
                )
                self._execute(
                    db, budget,
                    text(
                        "DELETE FROM actor_rate_limit_events "
                        "WHERE actor_digest = :actor_digest AND rate_group = :group "
                        "AND observed_at <= clock_timestamp() - "
                        "make_interval(secs => :window_seconds)"
                    ),
                    {
                        "actor_digest": actor_digest,
                        "group": group,
                        "window_seconds": self._window_seconds,
                    },
                )
                state = self._execute(
                    db, budget,
                    text(
                        "SELECT count(*) AS event_count, "
                        "GREATEST(1, CEIL(EXTRACT(EPOCH FROM ("
                        "MIN(observed_at) + make_interval(secs => :window_seconds) - clock_timestamp()"
                        "))))::integer AS retry_after "
                        "FROM actor_rate_limit_events "
                        "WHERE actor_digest = :actor_digest AND rate_group = :group"
                    ),
                    {
                        "actor_digest": actor_digest,
                        "group": group,
                        "window_seconds": self._window_seconds,
                    },
                ).mappings().one()
                if state["event_count"] >= self._limits[group]:
                    retry_after = int(state["retry_after"])
                else:
                    self._execute(
                        db, budget,
                        text(
                            "INSERT INTO actor_rate_limit_events (actor_digest, rate_group) "
                            "VALUES (:actor_digest, :group)"
                        ),
                        {"actor_digest": actor_digest, "group": group},
                    )
                # Refresh the remaining SQL budget before transaction completion.
                self._prepare_statement(db, budget)
            budget.remaining_seconds()
        except (KeyError, ValueError):
            raise
        except ActorRateLimitStoreUnavailable:
            raise
        except Exception as exc:
            raise ActorRateLimitStoreUnavailable from exc
        return retry_after
