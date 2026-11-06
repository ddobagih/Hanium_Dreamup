"""Application-owned TMAP I/O and bounded, spawn-based response processing."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from concurrent.futures import Future, ThreadPoolExecutor
from concurrent.futures.process import BrokenProcessPool
from dataclasses import dataclass, field
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import importlib.abc
import math
import multiprocessing
from multiprocessing.connection import Connection
import os
import socket
import sys
from time import monotonic
from typing import Any

import httpx


TMAP_RUNTIME_CAPACITY = 2
TMAP_RATE_LIMIT_DEFAULT_SECONDS = 30
TMAP_RATE_LIMIT_MAX_SECONDS = 300
# S20-29: Backend-wide common call budget shared by route and POI provider
# calls. Conservative interim values until the TMAP contract quota is known;
# operators tune them with TMAP_CALL_BUDGET_PER_MINUTE/PER_DAY. The counters are
# process-local (one worker today); several workers need a shared store.
TMAP_CALL_BUDGET_PER_MINUTE_DEFAULT = 60
TMAP_CALL_BUDGET_PER_DAY_DEFAULT = 5_000
TMAP_CALL_BUDGET_PER_MINUTE_MAX = 6_000
TMAP_CALL_BUDGET_PER_DAY_MAX = 5_000_000
_CALL_BUDGET_WINDOWS = (("minute", 60.0), ("day", 86_400.0))
# S20-29/R9: the readiness live probe has its own small allowance outside the
# user windows above: at most one probe run (one route + one POI provider call)
# per 60 s. It neither spends nor is blocked by the user minute/day budget, so
# /ready polling during an outage cannot drain the budget users need.
TMAP_READINESS_PROBE_INTERVAL_SECONDS = 60.0
_CAPACITY_MESSAGE = "TMAP request processing capacity is currently exhausted."
_UNAVAILABLE_MESSAGE = "TMAP request processing is unavailable."


class TmapRuntimeError(RuntimeError):
    def __init__(
        self,
        *,
        code: str,
        message: str,
        http_status: int = 503,
        provider_status: int | None = None,
        provider_result_code: int | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status
        self.provider_status = provider_status
        self.provider_result_code = provider_result_code


class TmapCallBudgetExhausted(TmapRuntimeError):
    """Readiness-only signal: the user route+POI budget is exhausted right now."""

    def __init__(self, *, exhausted_windows: tuple[str, ...], resets_in_seconds: int) -> None:
        super().__init__(
            code="tmap_call_budget_exhausted",
            message="TMAP route and POI call budget is exhausted.",
        )
        self.exhausted_windows = exhausted_windows
        self.resets_in_seconds = resets_in_seconds


class TmapReadinessProbeDeferred(TmapRuntimeError):
    """Readiness-only signal: the probe allowance was already used this interval."""

    def __init__(self, *, next_probe_in_seconds: int) -> None:
        super().__init__(
            code="tmap_readiness_probe_deferred",
            message="TMAP readiness probe allowance is used for this interval.",
        )
        self.next_probe_in_seconds = next_probe_in_seconds


def _unavailable() -> TmapRuntimeError:
    return TmapRuntimeError(code="tmap_processing_unavailable", message=_UNAVAILABLE_MESSAGE)


@dataclass(frozen=True)
class TmapWorkerTestHooks:
    """Explicit spawn-safe observations; never supplied by application startup."""

    body: bytes
    entered: Any
    release: Any
    child_pid: Any
    guard_checks: Any
    entered_count: Any


class _WorkerDatabaseImportGuard(importlib.abc.MetaPathFinder):
    _prefixes = (
        "psycopg", "psycopg2", "asyncpg", "pg8000", "sqlalchemy",
        "backend.app.database", "backend.app.db", "backend.app.main",
    )

    @classmethod
    def forbidden(cls, name: str) -> bool:
        return any(name == prefix or name.startswith(prefix + ".") for prefix in cls._prefixes)

    def find_spec(self, fullname, path=None, target=None):
        if self.forbidden(fullname):
            raise PermissionError("TMAP response worker cannot import database/application resources.")
        return None


def _deny_worker_network(*args, **kwargs):
    raise PermissionError("TMAP response worker cannot open network connections.")


def _initialize_worker(hooks: TmapWorkerTestHooks | None) -> None:
    # Spawn workers receive no application objects. Install their own guard,
    # rather than relying on parent monkeypatches surviving process creation.
    if any(_WorkerDatabaseImportGuard.forbidden(name) for name in sys.modules):
        raise RuntimeError("TMAP response worker inherited forbidden application resources.")
    sys.meta_path.insert(0, _WorkerDatabaseImportGuard())
    socket.socket.connect = _deny_worker_network
    socket.socket.connect_ex = _deny_worker_network
    socket.socket.sendto = _deny_worker_network
    socket.create_connection = _deny_worker_network
    socket.getaddrinfo = _deny_worker_network
    socket.gethostbyname = _deny_worker_network
    socket.gethostbyname_ex = _deny_worker_network
    if hooks is not None:
        import importlib
        import json

        checks = 0
        with socket.socket() as probe:
            try:
                probe.connect(("127.0.0.1", 0))
            except PermissionError:
                checks += 1
        try:
            importlib.import_module("psycopg")
        except PermissionError:
            checks += 1
        with hooks.guard_checks.get_lock():
            hooks.guard_checks.value = checks
        original_loads = json.loads

        def observed_loads(value, *args, **kwargs):
            if value == hooks.body:
                with hooks.child_pid.get_lock():
                    hooks.child_pid.value = os.getpid()
                with hooks.entered_count.get_lock():
                    hooks.entered_count.value += 1
                hooks.entered.set()
                hooks.release.wait()
            return original_loads(value, *args, **kwargs)

        json.loads = observed_loads


# Only fixed, already-public adapter errors may cross the worker boundary.
# Exceptions themselves (including their tracebacks/provider text) never cross.
_ERROR_MESSAGES = {
    ("route", "invalid_tmap_response", 502): frozenset({
        "TMAP pedestrian route polyline exceeds the safe point limit.",
        "TMAP pedestrian route response is not JSON object.",
        "TMAP pedestrian route response is not GeoJSON FeatureCollection.",
        "TMAP pedestrian route response exceeds the safe feature limit.",
        "TMAP pedestrian route response contains an oversized string.",
        "TMAP pedestrian route response exceeds the safe guide limit.",
        "TMAP pedestrian route response exceeds the safe polyline limit.",
        "TMAP pedestrian route LineString has invalid coordinates.",
        "TMAP pedestrian route LineString distance is missing or invalid.",
        "TMAP pedestrian route guide distance exceeds the route summary.",
        "TMAP pedestrian route guide position is ambiguous or out of order.",
        "TMAP pedestrian route guide coordinates are invalid.",
        "TMAP pedestrian route response exceeds the safe step limit.",
        "TMAP pedestrian route response exceeds the safe resource limit.",
        "TMAP pedestrian route response is not valid JSON.",
        "TMAP pedestrian route response violates the WalkSafe schema.",
    }),
    ("poi", "invalid_tmap_response", 502): frozenset({
        "TMAP POI search response is not JSON object.",
        "TMAP POI search response contains an oversized string.",
        "TMAP POI search response has no searchPoiInfo.",
        "TMAP POI search response exceeds the safe result limit.",
        "TMAP POI search response exceeds the safe resource limit.",
        "TMAP POI search response is not valid JSON.",
        "TMAP POI search response violates the WalkSafe schema.",
    }),
    ("route", "route_waypoint_mismatch", 502): frozenset({
        "TMAP pedestrian route geometry could not be verified against the requested waypoint order.",
    }),
    ("route", "route_unavailable", 422): frozenset({
        "TMAP pedestrian route response has no route features.",
    }),
    ("route", "route_stairs_present", 422): frozenset({
        "TMAP returned stairs despite the stair-exclusion request.",
    }),
    ("route", "route_safety_code_unknown", 502): frozenset({
        "TMAP returned a route code whose stair safety is unknown.",
    }),
    ("route", "route_geometry_missing", 502): frozenset({
        "TMAP Walking route has no usable geometry.",
        "TMAP Walking route geometry has no measurable length.",
    }),
    ("route", "route_geometry_disconnected", 502): frozenset({
        "TMAP pedestrian route LineString segments are disconnected.",
    }),
    ("route", "route_guidance_incomplete", 502): frozenset({
        "TMAP pedestrian route lacks required guide points for voice guidance.",
    }),
    ("route", "route_endpoint_mismatch", 502): frozenset({
        "TMAP Walking route geometry does not connect to the requested endpoints.",
    }),
    ("route", "route_summary_invalid", 502): frozenset({
        "TMAP Walking route distance and duration must be positive.",
    }),
    ("route", "route_geometry_summary_mismatch", 502): frozenset({
        "TMAP Walking route summary distance is inconsistent with its geometry.",
    }),
    ("route", "route_summary_conflict", 502): frozenset({
        "TMAP pedestrian route summary totals conflict across features.",
    }),
    ("route", "route_duration_implausible", 502): frozenset({
        "TMAP Walking route duration is implausibly short for its distance.",
    }),
}
for _mode, _label, _api in (
    ("route", "pedestrian route", "pedestrian route"),
    ("poi", "POI search", "POI search"),
):
    _ERROR_MESSAGES[(_mode, "tmap_timeout", 504)] = frozenset({
        f"TMAP {_label} request timed out.",
    })
    _ERROR_MESSAGES[(_mode, "tmap_provider_error", 502)] = frozenset({
        f"TMAP {_label} provider returned an error.",
    })
    _ERROR_MESSAGES[(_mode, "tmap_invalid_api_key", 502)] = frozenset({
        f"TMAP appKey is invalid or not allowed to use {_api} API.",
    })
    _ERROR_MESSAGES[(_mode, "tmap_rate_limited", 503)] = frozenset({
        f"TMAP {_label} request limit was reached.",
    })


def _process_response_job(
    mode: str,
    provider_status: int,
    body: bytes,
    arguments: dict[str, Any],
    deadline_at: float,
) -> dict[str, Any]:
    try:
        from backend.app.services.tmap_pedestrian import (
            TmapPedestrianRouteError,
            TmapPoiSearchError,
            _process_tmap_response,
        )
        try:
            result = _process_tmap_response(
                mode, provider_status, body, arguments, deadline_at=deadline_at,
            )
            return {"kind": "success", "value": result}
        except (TmapPedestrianRouteError, TmapPoiSearchError) as exc:
            return {
                "kind": "domain_error",
                "code": exc.code,
                "message": exc.message,
                "http_status": exc.http_status,
                "provider_status": exc.provider_status,
                "provider_result_code": exc.provider_result_code,
            }
        except TimeoutError:
            label = "pedestrian route" if mode == "route" else "POI search"
            return {
                "kind": "domain_error", "code": "tmap_timeout",
                "message": f"TMAP {label} request timed out.",
                "http_status": 504, "provider_status": None, "provider_result_code": None,
            }
    except Exception:
        # Local processing failures are deliberately not provider failures.
        return {"kind": "unavailable"}


def _run_isolated_response_job(
    sender: Connection,
    operation: Callable[..., Any],
    arguments: tuple[Any, ...],
    hooks: TmapWorkerTestHooks | None,
) -> None:
    try:
        _initialize_worker(hooks)
        try:
            result = operation(*arguments)
        except Exception:
            result = {"kind": "unavailable"}
        sender.send(result)
    finally:
        sender.close()


@dataclass
class _IsolatedJob:
    process: multiprocessing.Process
    receiver: Connection
    future: Future
    reader: asyncio.Future


class _IsolatedProcessExecutor:
    """One owned spawn process per job, so cancellation never kills a peer."""

    _EXIT_GRACE_SECONDS = 0.25

    def __init__(self, loop: asyncio.AbstractEventLoop, hooks: TmapWorkerTestHooks | None) -> None:
        self._loop = loop
        self._hooks = hooks
        self._context = multiprocessing.get_context("spawn")
        self._reader_executor = ThreadPoolExecutor(
            max_workers=TMAP_RUNTIME_CAPACITY, thread_name_prefix="tmap-response-reader",
        )
        self._jobs: dict[Future, _IsolatedJob] = {}
        self._unconfirmed_children: list[tuple[multiprocessing.Process, Connection]] = []
        self._closed = False

    @property
    def closed(self) -> bool:
        return self._closed

    @property
    def _processes(self) -> dict[int, multiprocessing.Process]:
        # Runtime tests observe live children without relying on CPython pool internals.
        return {
            job.process.pid: job.process for job in self._jobs.values()
            if job.process.pid is not None
        }

    def submit(self, operation: Callable[..., Any], *arguments: Any) -> Future:
        if self._closed:
            raise RuntimeError("TMAP response executor is closed")
        receiver, sender = self._context.Pipe(duplex=False)
        process = self._context.Process(
            target=_run_isolated_response_job,
            args=(sender, operation, arguments, self._hooks),
            name="tmap-response-worker",
        )
        try:
            process.start()
        except Exception:
            receiver.close()
            sender.close()
            raise
        sender.close()
        future: Future = Future()
        future.set_running_or_notify_cancel()
        try:
            # Connection.recv() may block after receiving only a frame header.
            # Its thread must finish (EOF or reply) before the lease is freed.
            reader = self._loop.run_in_executor(self._reader_executor, receiver.recv)
        except Exception:
            self._stop_process(process)
            process.join(timeout=self._EXIT_GRACE_SECONDS)
            if process.is_alive():
                self._stop_process(process, force=True)
                process.join(timeout=self._EXIT_GRACE_SECONDS)
            if not process.is_alive():
                receiver.close()
            else:
                self._unconfirmed_children.append((process, receiver))
            # An unconfirmed child keeps its handle; the caller stops
            # admission and shutdown reports the remaining resource.
            raise
        job = _IsolatedJob(process=process, receiver=receiver, future=future, reader=reader)
        self._jobs[future] = job
        reader.add_done_callback(lambda completed: self._read_result(job, completed))
        return future

    def _read_result(self, job: _IsolatedJob, completed: asyncio.Future) -> None:
        try:
            result = completed.result()
        except asyncio.CancelledError:
            error = BrokenProcessPool("TMAP response reader was cancelled")
        except (EOFError, OSError):
            error = BrokenProcessPool("TMAP response worker exited unexpectedly")
        except Exception:
            error = BrokenProcessPool("TMAP response worker reply was invalid")
        else:
            if not job.future.done():
                job.future.set_result(result)
            return
        if not job.future.done():
            job.future.set_exception(error)

    @staticmethod
    async def _wait_exit(process: multiprocessing.Process, seconds: float) -> bool:
        deadline = monotonic() + seconds
        while process.is_alive() and monotonic() < deadline:
            await asyncio.sleep(0.005)
        process.join(timeout=0)
        return not process.is_alive()

    @staticmethod
    def _stop_process(process: multiprocessing.Process, *, force: bool = False) -> None:
        try:
            if process.is_alive():
                (process.kill if force else process.terminate)()
        except ProcessLookupError:
            # Exit can race the signal after is_alive() checks it.
            pass

    async def finish(self, future: Future, *, abort: bool) -> None:
        job = self._jobs.get(future)
        if job is None:
            return
        process = job.process
        try:
            if abort:
                self._stop_process(process)
            if not await self._wait_exit(process, self._EXIT_GRACE_SECONDS):
                self._stop_process(process)
                if not await self._wait_exit(process, self._EXIT_GRACE_SECONDS):
                    self._stop_process(process, force=True)
                    if not await self._wait_exit(process, self._EXIT_GRACE_SECONDS):
                        raise RuntimeError("TMAP response worker termination unconfirmed")
            try:
                await asyncio.wait_for(asyncio.shield(job.reader), self._EXIT_GRACE_SECONDS)
            except asyncio.CancelledError:
                # A cancelled proxy is not proof that recv() left its thread.
                raise RuntimeError("TMAP response reader termination unconfirmed") from None
            except Exception:
                if not job.reader.done():
                    raise RuntimeError("TMAP response reader termination unconfirmed") from None
            job.receiver.close()
            if not future.done():
                future.set_exception(BrokenProcessPool("TMAP response worker was cancelled"))
            self._jobs.pop(future, None)
        except Exception:
            # Retain the process/IPC handle for shutdown diagnostics. A failed
            # termination must never make admission capacity look free.
            raise

    def shutdown(self, *, wait: bool = True, cancel_futures: bool = False) -> None:
        if not wait:
            raise ValueError("TMAP response shutdown requires confirmed cleanup")
        self._closed = True
        if self._jobs or self._unconfirmed_children:
            raise RuntimeError("TMAP response jobs are still owned")
        self._reader_executor.shutdown(wait=True, cancel_futures=True)


def _decode_response(mode: str, envelope: object) -> Any:
    from backend.app.schemas import DestinationSearchResponse, WalkingRouteResponse

    if not isinstance(envelope, dict):
        raise _unavailable()
    if envelope.get("kind") == "success" and set(envelope) == {"kind", "value"}:
        expected = WalkingRouteResponse if mode == "route" else DestinationSearchResponse
        if type(envelope["value"]) is expected:
            return envelope["value"]
        raise _unavailable()
    if envelope.get("kind") != "domain_error" or set(envelope) != {
        "kind", "code", "message", "http_status", "provider_status", "provider_result_code",
    }:
        raise _unavailable()
    code, message, status = envelope["code"], envelope["message"], envelope["http_status"]
    if not isinstance(code, str) or not isinstance(message, str) or type(status) is not int:
        raise _unavailable()
    if message not in _ERROR_MESSAGES.get((mode, code, status), ()):
        raise _unavailable()
    for key in ("provider_status", "provider_result_code"):
        if envelope[key] is not None and type(envelope[key]) is not int:
            raise _unavailable()
    raise TmapRuntimeError(
        code=code,
        message=message,
        http_status=status,
        provider_status=envelope["provider_status"],
        provider_result_code=envelope["provider_result_code"],
    )


@dataclass
class _Lease:
    deadline_at: float
    started: bool = False
    abandoned: bool = False
    future: Future | None = None
    http_response: httpx.Response | None = None
    http_cleanup_tasks: list[asyncio.Task] = field(default_factory=list)
    http_cleanup_failed: bool = False
    process_cleanup_failed: bool = False


class _OwnedHttpStream(httpx.AsyncByteStream):
    """Own the actual stream close, including HTTPX's implicit iterator close."""

    def __init__(self, owner: TmapRuntime, lease: _Lease, stream: httpx.AsyncByteStream) -> None:
        self._owner = owner
        self._lease = lease
        self._stream = stream
        self._close_task: asyncio.Task | None = None

    async def __aiter__(self):
        async for chunk in self._stream:
            yield chunk

    async def aclose(self) -> None:
        if self._close_task is None:
            self._close_task = self._owner._own_http_cleanup(self._lease, self._stream.aclose)
        await self._owner._await_http_cleanup(self._lease, self._close_task)


class TmapRuntime:
    """Two accepted requests, no admission queue; cleanup confirms free capacity."""

    def __init__(
        self,
        *,
        timeout_seconds: float,
        worker_test_hooks: TmapWorkerTestHooks | None = None,
    ) -> None:
        self._timeout_seconds = timeout_seconds
        self._worker_test_hooks = worker_test_hooks
        self._loop: asyncio.AbstractEventLoop | None = None
        self._client: httpx.AsyncClient | None = None
        self._executor: _IsolatedProcessExecutor | None = None
        self._leases: dict[asyncio.Task, _Lease] = {}
        self._rate_limited_until = 0.0
        # Fixed windows: name -> [window start (monotonic) or None, calls spent].
        self._call_budget: dict[str, list[Any]] = {
            name: [None, 0] for name, _length in _CALL_BUDGET_WINDOWS
        }
        # Start (monotonic) of the last admitted readiness probe run, or None.
        self._readiness_probe_at: float | None = None
        self._accepting = False
        self._stopped = False
        self._closed = False
        self._close_task: asyncio.Task | None = None
        self._close_failures: tuple[str, ...] = ()

    async def start(self) -> None:
        loop = asyncio.get_running_loop()
        if self._stopped or self._closed or (self._loop is not None and self._loop is not loop):
            raise _unavailable()
        if self._accepting:
            return
        self._loop = loop
        try:
            self._client = httpx.AsyncClient(
                timeout=self._timeout_seconds,
                limits=httpx.Limits(
                    max_connections=TMAP_RUNTIME_CAPACITY,
                    max_keepalive_connections=TMAP_RUNTIME_CAPACITY,
                ),
            )
            self._executor = _IsolatedProcessExecutor(loop, self._worker_test_hooks)
            self._accepting = True
        except Exception:
            self.stop_admission()
            await self.aclose()
            raise _unavailable() from None

    def stop_admission(self) -> None:
        self._accepting = False
        self._stopped = True

    def ensure_available(self) -> None:
        if (
            not self._accepting
            or self._closed
            or self._loop is not asyncio.get_running_loop()
            or self._executor is None
            or self._executor.closed
            or self._client is None
            or self._client.is_closed
        ):
            raise _unavailable()

    def ensure_rate_limit_available(self, mode: str) -> None:
        if monotonic() < self._rate_limited_until:
            label = "pedestrian route" if mode == "route" else "POI search"
            raise TmapRuntimeError(
                code="tmap_rate_limited",
                message=f"TMAP {label} request limit was reached.",
                http_status=503,
            )

    def consume_call_budget(self, mode: str, settings: Any) -> None:
        """Spend one unit of the shared route+POI budget before provider I/O.

        No await happens here, so check-and-spend is atomic on the owner loop.
        A refused call does not spend budget and is never retried automatically.
        """
        limits = self._call_budget_limits(settings)
        now = monotonic()
        for name, length in _CALL_BUDGET_WINDOWS:
            window = self._call_budget[name]
            if window[0] is None or now - window[0] >= length:
                window[0], window[1] = now, 0
        if any(
            self._call_budget[name][1] >= limits[name] for name, _length in _CALL_BUDGET_WINDOWS
        ):
            label = "pedestrian route" if mode == "route" else "POI search"
            raise TmapRuntimeError(
                code="tmap_rate_limited",
                message=f"TMAP {label} request limit was reached.",
                http_status=503,
            )
        for name, _length in _CALL_BUDGET_WINDOWS:
            self._call_budget[name][1] += 1

    @staticmethod
    def _call_budget_limits(settings: Any) -> dict[str, int]:
        limits = {
            "minute": getattr(
                settings, "tmap_call_budget_per_minute", TMAP_CALL_BUDGET_PER_MINUTE_DEFAULT,
            ),
            "day": getattr(
                settings, "tmap_call_budget_per_day", TMAP_CALL_BUDGET_PER_DAY_DEFAULT,
            ),
        }
        if any(
            type(limit) is not int or limit <= 0 for limit in limits.values()
        ):
            raise _unavailable()
        return limits

    def call_budget_exhaustion(self, settings: Any) -> TmapCallBudgetExhausted | None:
        """Read-only view of the user windows: no spend and no window reset."""
        limits = self._call_budget_limits(settings)
        now = monotonic()
        exhausted: list[str] = []
        resets: list[float] = []
        for name, length in _CALL_BUDGET_WINDOWS:
            started, spent = self._call_budget[name]
            if started is None or now - started >= length or spent < limits[name]:
                continue
            exhausted.append(name)
            resets.append(length - (now - started))
        if not exhausted:
            return None
        # Users are refused until every exhausted window has reopened.
        return TmapCallBudgetExhausted(
            exhausted_windows=tuple(exhausted),
            resets_in_seconds=max(1, math.ceil(max(resets))),
        )

    def reserve_readiness_probe(self) -> None:
        """Admit one readiness live probe run from its own allowance (R9).

        No await happens here, so check-and-reserve is atomic on the owner loop.
        """
        now = monotonic()
        last = self._readiness_probe_at
        if last is not None and now - last < TMAP_READINESS_PROBE_INTERVAL_SECONDS:
            raise TmapReadinessProbeDeferred(
                next_probe_in_seconds=max(
                    1, math.ceil(TMAP_READINESS_PROBE_INTERVAL_SECONDS - (now - last)),
                ),
            )
        self._readiness_probe_at = now

    def record_rate_limit_response(self, status: int, retry_after: str | None) -> None:
        if status != 429:
            return
        seconds = TMAP_RATE_LIMIT_DEFAULT_SECONDS
        if retry_after:
            value = retry_after.strip()
            if value.isascii() and value.isdigit():
                significant = value.lstrip("0") or "0"
                maximum = str(TMAP_RATE_LIMIT_MAX_SECONDS)
                if len(significant) > len(maximum) or (
                    len(significant) == len(maximum) and significant > maximum
                ):
                    seconds = TMAP_RATE_LIMIT_MAX_SECONDS
                else:
                    seconds = max(seconds, int(significant))
            else:
                try:
                    retry_at = parsedate_to_datetime(value)
                    if retry_at.tzinfo is not None:
                        delay = (retry_at - datetime.now(timezone.utc)).total_seconds()
                        seconds = max(seconds, min(delay, TMAP_RATE_LIMIT_MAX_SECONDS))
                except (TypeError, ValueError, OverflowError):
                    pass
        self._rate_limited_until = max(self._rate_limited_until, monotonic() + seconds)

    @property
    def client(self) -> httpx.AsyncClient:
        # Already-admitted work can finish after stop_admission().
        if self._client is None or self._client.is_closed:
            raise _unavailable()
        return self._client

    async def execute(self, operation: Callable[[], Awaitable[Any]], *, deadline_at: float) -> Any:
        self.ensure_available()
        if len(self._leases) >= TMAP_RUNTIME_CAPACITY:
            raise TmapRuntimeError(code="tmap_capacity_exceeded", message=_CAPACITY_MESSAGE)
        if monotonic() >= deadline_at:
            raise TimeoutError("TMAP request exceeded its wall-clock deadline")
        lease = _Lease(deadline_at=deadline_at)
        task = asyncio.create_task(self._run_owned(operation, lease), name="tmap-owned-request")
        # No await between the capacity check and reservation.
        self._leases[task] = lease
        task.add_done_callback(self._consume_owned_result)
        try:
            async with asyncio.timeout(max(deadline_at - monotonic(), 0.0)):
                return await asyncio.shield(task)
        except (asyncio.CancelledError, TimeoutError):
            lease.abandoned = True
            # Shield only the cleanup owner, not obsolete provider I/O. The
            # lease remains reserved until its HTTP close and CPU future end.
            task.cancel()
            raise

    def _consume_owned_result(self, task: asyncio.Task) -> None:
        lease = self._leases.get(task)
        # A task cancelled before its first instruction cannot run its finally
        # block and has not acquired either an HTTP response or CPU future.
        if lease is not None and not lease.started and task.cancelled():
            self._leases.pop(task, None)
        if not task.cancelled():
            task.exception()

    def own_http_response(self, response: httpx.Response) -> None:
        lease = self._leases.get(asyncio.current_task())
        if lease is None or lease.http_response is not None:
            raise _unavailable()
        lease.http_response = response
        response.stream = _OwnedHttpStream(self, lease, response.stream)

    def _own_http_cleanup(self, lease: _Lease, close: Callable[[], Awaitable[Any]]) -> asyncio.Task:
        task = asyncio.create_task(close(), name="tmap-owned-http-cleanup")
        lease.http_cleanup_tasks.append(task)
        return task

    async def _await_http_cleanup(self, lease: _Lease, task: asyncio.Task) -> Any:
        try:
            return await asyncio.shield(task)
        except asyncio.CancelledError:
            # Cancelling a waiter is not cancelling the independently owned close.
            if task.cancelled():
                lease.http_cleanup_failed = True
                self.stop_admission()
            raise
        except Exception:
            lease.http_cleanup_failed = True
            self.stop_admission()
            raise _unavailable() from None

    async def finish_http_cleanup(self, close: Callable[[], Awaitable[Any]]) -> None:
        lease = self._leases.get(asyncio.current_task())
        if lease is None:
            raise _unavailable()
        task = self._own_http_cleanup(lease, close)
        await self._await_http_cleanup(lease, task)

    async def _run_owned(self, operation: Callable[[], Awaitable[Any]], lease: _Lease) -> Any:
        lease.started = True
        try:
            return await operation()
        finally:
            # Iteration also covers a stream-close task appended by context exit.
            for cleanup in lease.http_cleanup_tasks:
                while not cleanup.done():
                    try:
                        await asyncio.shield(cleanup)
                    except asyncio.CancelledError:
                        continue
                    except Exception:
                        break
                try:
                    cleanup.result()
                except (asyncio.CancelledError, Exception):
                    lease.http_cleanup_failed = True
                    self.stop_admission()
            if lease.future is not None and self._executor is not None:
                cleanup = asyncio.create_task(
                    self._executor.finish(lease.future, abort=lease.abandoned),
                    name="tmap-owned-process-cleanup",
                )
                while not cleanup.done():
                    try:
                        await asyncio.shield(cleanup)
                    except asyncio.CancelledError:
                        continue
                    except Exception:
                        break
                try:
                    cleanup.result()
                except (Exception, asyncio.CancelledError):
                    lease.process_cleanup_failed = True
                    self.stop_admission()
            if lease.future is not None and lease.future.done():
                try:
                    lease.future.result()
                except Exception:
                    pass
            if not lease.http_cleanup_failed and not lease.process_cleanup_failed:
                self._leases.pop(asyncio.current_task(), None)
            # Unconfirmed HTTP/process cleanup retains its lease and references.
            # Admission is stopped; failed cleanup is never recycled as capacity.

    async def process_response(
        self,
        mode: str,
        provider_status: int,
        body: bytes,
        arguments: dict[str, Any],
        *,
        deadline_at: float,
    ) -> Any:
        lease = self._leases.get(asyncio.current_task())
        if lease is None or lease.future is not None or deadline_at != lease.deadline_at:
            raise _unavailable()
        if lease.abandoned or monotonic() >= deadline_at:
            raise TimeoutError("TMAP request exceeded its wall-clock deadline")
        if self._executor is None:
            raise _unavailable()
        try:
            lease.future = self._executor.submit(
                _process_response_job, mode, provider_status, body, arguments, deadline_at,
            )
            wrapped = asyncio.wrap_future(lease.future)
            wrapped.add_done_callback(self._consume_owned_result)
            envelope = await asyncio.shield(wrapped)
        except (BrokenProcessPool, RuntimeError):
            self.stop_admission()
            raise _unavailable() from None
        except Exception:
            self.stop_admission()
            raise _unavailable() from None
        try:
            return _decode_response(mode, envelope)
        except TmapRuntimeError as exc:
            if exc.code == "tmap_processing_unavailable":
                self.stop_admission()
            raise

    async def _close_owned(self) -> None:
        if self._leases:
            await asyncio.gather(*tuple(self._leases), return_exceptions=True)
        failures = []
        if self._leases:
            if any(lease.http_cleanup_failed for lease in self._leases.values()):
                failures.append("http_cleanup")
            if any(lease.process_cleanup_failed for lease in self._leases.values()):
                failures.append("process_cleanup")
        # Keep each unconfirmed resource reference. A failure in one resource
        # must not prevent attempting the other resource's cleanup.
        if self._executor is not None:
            executor = self._executor
            try:
                # Every owned reader has already finished before its lease is
                # released, so this joins only idle dedicated threads. Using
                # the app's default executor here could deadlock shutdown when
                # unrelated to_thread work has saturated that executor.
                executor.shutdown(wait=True, cancel_futures=True)
            except Exception:
                failures.append("executor")
            else:
                self._executor = None
        if self._client is not None:
            try:
                await self._client.aclose()
            except Exception:
                failures.append("client")
        self._close_failures = tuple(failures)
        if failures:
            # aclose retains this failed task: later calls report the same
            # unconfirmed shutdown, rather than silently retrying or succeeding.
            raise _unavailable()
        self._closed = True

    async def aclose(self) -> None:
        self.stop_admission()
        if self._closed:
            return
        if self._loop is not None and self._loop is not asyncio.get_running_loop():
            raise _unavailable()
        if self._close_task is None:
            self._close_task = asyncio.create_task(self._close_owned(), name="tmap-runtime-close")
        cancellation = None
        while True:
            try:
                await asyncio.shield(self._close_task)
                break
            except asyncio.CancelledError as exc:
                cancellation = exc
                if self._close_task.done():
                    self._close_task.result()
                    break
        if cancellation is not None:
            raise cancellation
