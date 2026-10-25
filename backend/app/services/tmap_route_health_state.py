"""Process-local TMAP evidence with reset generations and ordered route checks."""

from __future__ import annotations

from collections.abc import Callable
from threading import Lock
from time import monotonic


class TmapRouteHealthState:
    """Own readiness timestamps separately from the latest route observation.

    A failed route check does not erase earlier successful dependency evidence.
    Reset fences every in-flight check; within a generation, a late completion
    cannot replace a newer route observation.
    """

    def __init__(self, *, clock: Callable[[], float] = monotonic) -> None:
        self._clock = clock
        self._lock = Lock()
        self._success_at: dict[str, float] = {}
        self._generation = 0
        self._check_order = 0
        self._route_observation: tuple[int, float, bool] | None = None

    def begin_route_check(self) -> tuple[int, int]:
        with self._lock:
            self._check_order += 1
            return self._generation, self._check_order

    def _record_route_observation(
        self, check: tuple[int, int], *, success: bool, now: float,
    ) -> None:
        """Called under the lock after the check's generation was validated."""
        if self._route_observation is None or check[1] >= self._route_observation[0]:
            self._route_observation = (check[1], now, success)

    def record_route_failure(self, check: tuple[int, int]) -> None:
        with self._lock:
            if check[0] == self._generation:
                self._record_route_observation(check, success=False, now=self._clock())

    def record_success(
        self, dependency: str, *, now: float | None = None,
        route_check: tuple[int, int] | None = None,
    ) -> None:
        if dependency not in {"route", "poi"}:
            raise ValueError("unsupported TMAP dependency")
        with self._lock:
            if route_check is not None and route_check[0] != self._generation:
                return
            timestamp = self._clock() if now is None else now
            self._success_at[dependency] = timestamp
            if dependency == "route":
                if route_check is None:
                    self._check_order += 1
                    route_check = (self._generation, self._check_order)
                self._record_route_observation(route_check, success=True, now=timestamp)

    def reset(self) -> None:
        with self._lock:
            self._success_at.clear()
            self._generation += 1
            self._check_order = 0
            self._route_observation = None

    def readiness(
        self, max_age_seconds: float, *, now: float | None = None,
    ) -> dict[str, object]:
        current = self._clock() if now is None else now
        with self._lock:
            successes = dict(self._success_at)
            route_observation = self._route_observation
        ages = {
            dependency: max(0.0, current - timestamp)
            for dependency, timestamp in successes.items()
        }
        fresh = {
            dependency: dependency in ages and ages[dependency] <= max_age_seconds
            for dependency in ("route", "poi")
        }
        ready = all(fresh.values())
        route_state = "unverified"
        if route_observation is not None and max(0.0, current - route_observation[1]) <= max_age_seconds:
            route_state = "recent_success" if route_observation[2] else "recent_failure"
        return {
            "ready": ready,
            "provider": "tmap_pedestrian",
            "evidence": "recent_success" if ready else "none",
            "route_recent": fresh["route"],
            "poi_recent": fresh["poi"],
            "route_state": route_state,
        }
