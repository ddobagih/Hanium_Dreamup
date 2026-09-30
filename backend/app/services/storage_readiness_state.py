"""Process-local readiness bookkeeping for report and raw storage."""

from dataclasses import dataclass
import math
from threading import Lock
from typing import Literal


Domain = Literal["report", "raw"]
_DOMAINS: tuple[Domain, ...] = ("report", "raw")


@dataclass(frozen=True)
class PendingCommit:
    token: int
    resource: str


@dataclass(frozen=True)
class ResourceFailure:
    resource: str
    token: int
    cause: str


@dataclass(frozen=True)
class DomainSnapshot:
    initialized: bool
    pending_commits: tuple[PendingCommit, ...]
    unresolved_failures: tuple[ResourceFailure, ...]

    @property
    def ready(self) -> bool:
        return self.initialized and not self.pending_commits and not self.unresolved_failures


@dataclass(frozen=True)
class StorageReadinessSnapshot:
    report: DomainSnapshot
    raw: DomainSnapshot

    @property
    def ready(self) -> bool:
        return self.report.ready and self.raw.ready


class StorageReadinessState:
    """Track commit outcomes without reading storage during health checks."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._next_token = 1
        self._initialized = {domain: False for domain in _DOMAINS}
        self._pending: dict[int, tuple[Domain, str]] = {}
        self._failures: dict[Domain, dict[str, ResourceFailure]] = {
            domain: {} for domain in _DOMAINS
        }
        self._latest_success: dict[Domain, dict[str, int]] = {
            domain: {} for domain in _DOMAINS
        }
        self._revision = {domain: 0 for domain in _DOMAINS}
        self._report_invalidating_revision = 0
        self._report_coverage_started: float | None = None
        self._report_inventory_failure: str | None = None
        self._report_monitor_stopped = False

    def seed_full_success(self, domain: Domain | None = None) -> None:
        """Mark a successful full scan; omit domain only after both scans succeed."""
        if domain is not None and domain not in _DOMAINS:
            raise ValueError(f"unknown storage domain: {domain}")
        with self._lock:
            for selected in _DOMAINS if domain is None else (domain,):
                self._initialized[selected] = True

    def begin_commit(self, domain: Domain, resource: str) -> int:
        if domain not in _DOMAINS:
            raise ValueError(f"unknown storage domain: {domain}")
        with self._lock:
            token = self._next_token
            self._next_token += 1
            self._pending[token] = (domain, resource)
            self._revision[domain] += 1
            return token

    def publish_success(self, token: int) -> None:
        with self._lock:
            domain, resource = self._pending.pop(token)
            self._revision[domain] += 1
            latest = self._latest_success[domain]
            latest[resource] = max(token, latest.get(resource, 0))
            failure = self._failures[domain].get(resource)
            if failure is not None and failure.token <= token:
                del self._failures[domain][resource]

    def publish_clean_abort(self, token: int) -> None:
        with self._lock:
            domain, _resource = self._pending.pop(token)
            self._revision[domain] += 1

    def publish_failure(self, token: int, cause: str) -> None:
        with self._lock:
            domain, resource = self._pending.pop(token)
            self._revision[domain] += 1
            if domain == "report":
                self._report_invalidating_revision = self._revision[domain]
            if token <= self._latest_success[domain].get(resource, 0):
                return
            failures = self._failures[domain]
            previous = failures.get(resource)
            if previous is None or token > previous.token:
                failures[resource] = ResourceFailure(resource, token, str(cause))

    def report_inventory_revision(self) -> int:
        with self._lock:
            return self._revision["report"]

    def report_inventory_invalidated(self, revision: int) -> bool:
        with self._lock:
            return self._report_monitor_stopped or self._report_invalidating_revision > revision

    def restart_report_inventory(self) -> None:
        """A new lifespan cannot inherit a previous process-local scan."""
        with self._lock:
            self._revision["report"] += 1
            self._report_invalidating_revision = self._revision["report"]
            self._initialized["report"] = False
            self._report_coverage_started = None
            self._report_inventory_failure = None
            self._report_monitor_stopped = False

    def stop_report_inventory(self) -> None:
        with self._lock:
            self._revision["report"] += 1
            self._report_invalidating_revision = self._revision["report"]
            self._report_monitor_stopped = True

    def publish_report_inventory_success(self, revision: int, coverage_started: float) -> bool:
        """Publish covered data unless an uncertain write or shutdown superseded it."""
        if not math.isfinite(coverage_started):
            raise ValueError("storage coverage start must be finite")
        with self._lock:
            if (
                self._report_monitor_stopped
                or self._report_invalidating_revision > revision
                or any(domain == "report" for domain, _resource in self._pending.values())
            ):
                return False
            self._initialized["report"] = True
            self._report_coverage_started = coverage_started
            self._report_inventory_failure = None
            self._failures["report"].clear()
            return True

    def publish_report_inventory_failure(self, reason: str) -> None:
        with self._lock:
            self._report_inventory_failure = reason

    def report_readiness(self, *, now: float, max_age_seconds: float) -> dict[str, object]:
        """Read report-only coverage; never perform DB or filesystem work here."""
        with self._lock:
            reason = None
            started = self._report_coverage_started
            if self._report_monitor_stopped:
                reason = "report_storage_monitor_stopped"
            elif self._report_inventory_failure is not None:
                reason = self._report_inventory_failure
            elif not self._initialized["report"] or started is None:
                reason = "report_storage_not_checked"
            elif any(domain == "report" for domain, _resource in self._pending.values()):
                reason = "report_storage_write_pending"
            elif self._failures["report"]:
                reason = "report_storage_recovery_pending"
            elif not math.isfinite(now - started) or not 0 <= now - started < max_age_seconds:
                reason = "report_storage_inventory_expired"
            if reason is not None:
                return {"ready": False, "reason": reason}
            return {
                "ready": True,
                "inventory": "database_and_encrypted_objects_matched",
                "coverage_age_seconds": now - started,
            }

    def snapshot(self) -> StorageReadinessSnapshot:
        """Return an independent immutable view without storage I/O."""
        with self._lock:
            pending: dict[Domain, list[PendingCommit]] = {
                domain: [] for domain in _DOMAINS
            }
            for token, (domain, resource) in self._pending.items():
                pending[domain].append(PendingCommit(token, resource))
            domains = {
                domain: DomainSnapshot(
                    initialized=self._initialized[domain],
                    pending_commits=tuple(pending[domain]),
                    unresolved_failures=tuple(self._failures[domain].values()),
                )
                for domain in _DOMAINS
            }
            return StorageReadinessSnapshot(report=domains["report"], raw=domains["raw"])
