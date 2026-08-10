"""Atomic actor rate limiting shared by all backend workers and replicas."""

from __future__ import annotations

from collections import deque
import hashlib
import math
from threading import Lock
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


class PostgresActorRateLimiter:
    """Serialize one actor/group key with a transaction-scoped advisory lock."""

    def __init__(
        self,
        limits: dict[str, int],
        window_seconds: int = 60,
        session_factory: Callable[[], Any] | None = None,
    ) -> None:
        self._limits = limits
        self._window_seconds = window_seconds
        self._session_factory = session_factory

    def _sessions(self) -> Callable[[], Any]:
        if self._session_factory is None:
            from backend.app.database import SessionLocal

            return SessionLocal
        return self._session_factory

    def check(self, actor_id: str, group: str, *, now: float | None = None) -> int | None:
        if now is not None:
            raise ValueError("PostgreSQL rate limiting uses the database clock")
        actor_digest = hashlib.sha256(actor_id.encode("utf-8")).hexdigest()
        lock_key = f"walksafe-actor-rate-v1:{actor_digest}:{group}"
        try:
            with self._sessions().begin() as db:
                db.execute(
                    text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
                    {"lock_key": lock_key},
                )
                db.execute(
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
                state = db.execute(
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
                    return int(state["retry_after"])
                db.execute(
                    text(
                        "INSERT INTO actor_rate_limit_events (actor_digest, rate_group) "
                        "VALUES (:actor_digest, :group)"
                    ),
                    {"actor_digest": actor_digest, "group": group},
                )
        except (KeyError, ValueError):
            raise
        except Exception as exc:
            raise ActorRateLimitStoreUnavailable from exc
        return None
