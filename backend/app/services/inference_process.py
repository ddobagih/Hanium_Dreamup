"""Persistent detector subprocess with deadline-bounded parent socket I/O."""

from __future__ import annotations

from collections import deque
from concurrent.futures import Future, ThreadPoolExecutor
import logging
import multiprocessing
import os
import pickle
import selectors
import signal
import socket
import sys
import threading
import time
from typing import Any, Literal
import uuid

from pydantic import ValidationError

from backend.app.schemas import DetectContext, DetectResponse, DetectV2Response
from backend.app.services.detect_v2 import WARMUP_PNG_BYTES


InferenceKind = Literal["legacy", "v2"]

# Internal resource policy: include initial cold creation and failed starts.
_WORKER_CREATION_WINDOW_SECONDS = 60.0
_MAX_WORKER_CREATIONS_PER_WINDOW = 3
_MIN_WORKER_CREATION_INTERVAL_SECONDS = 5.0
_SHUTDOWN_GRACE_SECONDS = 1.0
_SHUTDOWN_DEADLINE_SECONDS = 4.0
logger = logging.getLogger(__name__)
_V2_PROVIDER_UNAVAILABLE_CODE = "detect_v2_provider_unavailable"
_MALFORMED_IMAGE_INPUT_CODE = "malformed_image_input"
_MALFORMED_CONTEXT_INPUT_CODE = "malformed_context_input"


class InferenceDeadlineExceeded(TimeoutError):
    pass


class InferenceQueueDeadlineExceeded(TimeoutError):
    pass


class InferenceRestartAdmissionLimited(RuntimeError):
    def __init__(self) -> None:
        super().__init__("inference_worker_restart_admission_limited")


class _RecoverableProviderUnavailable(RuntimeError):
    pass


class _RecoverableMalformedInput(RuntimeError):
    pass


class _OwnedPosixProcess:
    """Own one direct child and its isolated process group until it is reaped."""

    def __init__(self, pid: int) -> None:
        self.pid = pid
        self._exitcode: int | None = None
        self._closed = False

    def _reap(self) -> None:
        if self._exitcode is not None:
            return
        waited, status = os.waitpid(self.pid, os.WNOHANG)
        if waited:
            self._exitcode = os.waitstatus_to_exitcode(status)

    @property
    def exitcode(self) -> int | None:
        self._reap()
        return self._exitcode

    def is_alive(self) -> bool:
        if self._closed:
            raise ValueError("closed detector process queried")
        self._reap()
        return self._exitcode is None

    def _signal_group(self, sig: int) -> None:
        if not self.is_alive():
            return
        try:
            os.killpg(self.pid, sig)
        except ProcessLookupError:
            self._reap()

    def terminate(self) -> None:
        self._signal_group(signal.SIGTERM)

    def kill(self) -> None:
        self._signal_group(signal.SIGKILL)

    def join(self, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while self.is_alive() and time.monotonic() < deadline:
            time.sleep(min(0.01, max(0.0, deadline - time.monotonic())))
        self._reap()

    def close(self) -> None:
        if self.is_alive():
            raise RuntimeError("live detector process cannot close")
        self._closed = True


def _spawn_posix_worker(child_connection: socket.socket) -> _OwnedPosixProcess:
    # POSIX_SPAWN_DUP2 makes the child endpoint inheritable only in the child.
    source_fd = child_connection.fileno()
    child_fd = 100 if source_fd != 100 else 101
    script = (
        "import socket; "
        "from backend.app.services.inference_process import _inference_worker; "
        f"_inference_worker(socket.socket(fileno={child_fd}), ready=True)"
    )
    environment = os.environ.copy()
    environment["PYTHONPATH"] = os.pathsep.join(
        os.path.abspath(path or os.getcwd()) for path in sys.path
    )
    pid = os.posix_spawn(
        sys.executable,
        (sys.executable, "-c", script),
        environment,
        file_actions=((os.POSIX_SPAWN_DUP2, source_fd, child_fd),),
        setpgroup=0,
    )
    return _OwnedPosixProcess(pid)



def _check_deadline(deadline: float) -> float:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise InferenceDeadlineExceeded("detector execution deadline exceeded")
    return remaining


def _raise_cleanup_errors(errors: list[BaseException], message: str) -> None:
    for error in errors:
        if not isinstance(error, Exception):
            raise error
    if errors:
        raise RuntimeError(message) from errors[0]


class _FramedSocket:
    """Own the parent socket and any selector whose cleanup is still pending."""

    def __init__(self, channel: socket.socket) -> None:
        self._socket = channel
        self._selector: selectors.BaseSelector | None = None
        self._registered = False
        self._socket_closed = False

    def _wait(self, event: int, deadline: float) -> None:
        assert self._selector is not None
        self._selector.modify(self._socket, event)
        while True:
            ready = self._selector.select(_check_deadline(deadline))
            _check_deadline(deadline)
            if ready:
                return

    def wait_ready(self, deadline: float) -> None:
        with selectors.DefaultSelector() as selector:
            selector.register(self._socket, selectors.EVENT_READ)
            while True:
                if not selector.select(_check_deadline(deadline)):
                    continue
                _check_deadline(deadline)
                try:
                    marker = self._socket.recv(1)
                except (BlockingIOError, InterruptedError):
                    continue
                if marker != b"R":
                    raise EOFError("detector worker failed to become ready")
                return

    def _send_bytes(self, data: bytes, deadline: float) -> None:
        view = memoryview(data)
        offset = 0
        while offset < len(view):
            _check_deadline(deadline)
            try:
                count = self._socket.send(view[offset:])
            except (BlockingIOError, InterruptedError):
                self._wait(selectors.EVENT_WRITE, deadline)
                continue
            if count == 0:
                raise EOFError("detector channel closed")
            offset += count
            _check_deadline(deadline)

    def _recv_bytes(self, size: int, deadline: float) -> bytes:
        # Grow only as bytes arrive, never allocate the declared frame up front.
        data = bytearray()
        while len(data) < size:
            _check_deadline(deadline)
            try:
                part = self._socket.recv(min(size - len(data), 65536))
            except (BlockingIOError, InterruptedError):
                self._wait(selectors.EVENT_READ, deadline)
                continue
            if not part:
                raise EOFError("detector channel closed")
            data.extend(part)
            _check_deadline(deadline)
        return bytes(data)

    def _close_selector(self) -> None:
        selector = self._selector
        if selector is None:
            return
        errors: list[BaseException] = []
        if self._registered:
            try:
                selector.unregister(self._socket)
            except BaseException as exc:
                errors.append(exc)
            else:
                self._registered = False
        try:
            selector.close()
        except BaseException as exc:
            errors.append(exc)
        else:
            self._selector = None
            self._registered = False
        _raise_cleanup_errors(errors, "detector transport cleanup failed")

    def exchange(self, request: object, deadline: float) -> object:
        primary: BaseException | None = None
        try:
            _check_deadline(deadline)
            payload = pickle.dumps(request, protocol=pickle.DEFAULT_PROTOCOL)
            _check_deadline(deadline)
            self._selector = selectors.DefaultSelector()
            self._selector.register(self._socket, selectors.EVENT_WRITE)
            self._registered = True
            self._send_bytes(len(payload).to_bytes(8, "big"), deadline)
            self._send_bytes(payload, deadline)
            size = int.from_bytes(self._recv_bytes(8, deadline), "big")
            response = self._recv_bytes(size, deadline)
            _check_deadline(deadline)
            result = pickle.loads(response)
            _check_deadline(deadline)
            return result
        except BaseException as exc:
            primary = exc
            raise
        finally:
            try:
                self._close_selector()
            except BaseException as cleanup:
                if primary is None:
                    raise
                if isinstance(cleanup, Exception):
                    primary.add_note("detector transport cleanup failed")
                else:
                    cleanup.add_note("detector transport cleanup superseded a primary error")
                    raise

    def close(self) -> None:
        errors: list[BaseException] = []
        try:
            self._close_selector()
        except BaseException as exc:
            errors.append(exc)
        # Keep the descriptor alive until a pending selector has been released.
        if self._selector is None and not self._socket_closed:
            try:
                self._socket.close()
            except BaseException as exc:
                errors.append(exc)
            else:
                self._socket_closed = True
        _raise_cleanup_errors(errors, "detector transport cleanup failed")


def _worker_recv_bytes(channel: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        part = channel.recv(min(size - len(data), 65536))
        if not part:
            raise EOFError("detector channel closed")
        data.extend(part)
    return bytes(data)


def _worker_receive(channel: socket.socket) -> object:
    size = int.from_bytes(_worker_recv_bytes(channel, 8), "big")
    return pickle.loads(_worker_recv_bytes(channel, size))


def _worker_send(channel: socket.socket, value: object) -> None:
    payload = pickle.dumps(value, protocol=pickle.DEFAULT_PROTOCOL)
    channel.sendall(len(payload).to_bytes(8, "big"))
    channel.sendall(payload)


def _inference_worker(connection: socket.socket, *, ready: bool = False) -> None:
    from backend.app.detector import InvalidDetectionImage, _run_detection_sync
    from backend.app.services.detect_v2 import _DetectV2ProviderPreconditionUnavailable, run_detect_v2

    try:
        if ready:
            connection.sendall(b"R")
        while True:
            request = _worker_receive(connection)
            if request is None:
                return
            job_id, kind, image_bytes, content_type, context_payload, settings = request
            try:
                context = DetectContext.model_validate(context_payload)
            except ValidationError:
                # Only request validation failed; no detector/provider code ran.
                _worker_send(
                    connection,
                    (job_id, "error", "RuntimeError", "invalid_detection_context", _MALFORMED_CONTEXT_INPUT_CODE),
                )
                continue
            try:
                if kind == "legacy":
                    result = _run_detection_sync(image_bytes, content_type, context)
                elif kind == "v2":
                    result = run_detect_v2(
                        image_bytes=image_bytes,
                        content_type=content_type,
                        context=context,
                        settings=settings,
                    )
                else:
                    raise RuntimeError("unsupported inference operation")
                _worker_send(connection, (job_id, "ok", result))
            except _DetectV2ProviderPreconditionUnavailable as exc:
                _worker_send(
                    connection,
                    (job_id, "error", "RuntimeError", str(exc), _V2_PROVIDER_UNAVAILABLE_CODE),
                )
            except InvalidDetectionImage:
                _worker_send(
                    connection,
                    (job_id, "error", "RuntimeError", "image_decode_failed", _MALFORMED_IMAGE_INPUT_CODE),
                )
            except Exception as exc:
                _worker_send(connection, (job_id, "error", type(exc).__name__, str(exc)))
    except (EOFError, BrokenPipeError, OSError):
        return
    finally:
        connection.close()


class _InferenceSubmission:
    def __init__(self, executor: ThreadPoolExecutor) -> None:
        self.executor = executor
        self.future: Future[DetectResponse | DetectV2Response] | None = None
        self.revoked = False
        self.uncertain = False


class InferenceProcessRunner:
    """Serialize model access in a child that can be killed when inference hangs."""

    def __init__(
        self,
        *,
        timeout_seconds: float,
        startup_timeout_seconds: float = 30.0,
        queue_timeout_seconds: float = 2.0,
    ) -> None:
        self.timeout_seconds = timeout_seconds
        self.startup_timeout_seconds = startup_timeout_seconds
        self.queue_timeout_seconds = queue_timeout_seconds
        self._context = multiprocessing.get_context("spawn")
        self._direct_context = self._context
        self._lock = threading.Lock()
        self._state_lock = threading.Lock()
        self._usable = False
        self._process_started: bool | None = False
        self._process: multiprocessing.Process | _OwnedPosixProcess | None = None
        self._connection: _FramedSocket | None = None
        self._child_connection: socket.socket | None = None
        self._warmed_kinds: set[InferenceKind] = set()
        # Guarded by the request lock; retirement/close must not erase history.
        self._worker_creation_times: deque[float] = deque()
        self._restart_admission_limited = False
        self._submission_lock = threading.Lock()
        self._close_lock = threading.Lock()
        self._process_lock = threading.Lock()
        self._shutdown_requested = threading.Event()
        self._closing_count = 0
        self._executor: ThreadPoolExecutor | None = None
        self._executor_retiring = False
        self._submission: _InferenceSubmission | None = None

    def submit_inference(
        self,
        kind: InferenceKind,
        *,
        image_bytes: bytes,
        content_type: str,
        context: DetectContext,
        settings: Any,
        deadline: float,
    ) -> Future[DetectResponse | DetectV2Response]:
        with self._submission_lock:
            if (
                self._closing_count
                or self._executor_retiring
                or self._submission is not None
                or deadline <= time.monotonic()
            ):
                raise InferenceQueueDeadlineExceeded("detector queue deadline exceeded")
            if self._executor is None:
                self._executor = ThreadPoolExecutor(
                    max_workers=1, thread_name_prefix="detect-inference",
                )
            submission = _InferenceSubmission(self._executor)
            self._submission = submission
            try:
                future = self._executor.submit(
                    self._execute_submission,
                    submission,
                    kind,
                    image_bytes,
                    content_type,
                    context,
                    settings,
                    deadline,
                )
            except BaseException as exc:
                # submit may have queued the wrapper before raising. The same
                # lock prevents that wrapper from obtaining execution authority.
                submission.revoked = True
                submission.uncertain = True
                if not isinstance(exc, Exception):
                    raise
                raise RuntimeError("detector submission failed") from exc
            submission.future = future
        # A completed Future invokes callbacks inline; never register under the
        # submission lock. The runner retains the lease across this small gap.
        future.add_done_callback(
            lambda completed: self._submission_finished(submission, completed),
        )
        return future

    def _execute_submission(
        self,
        submission: _InferenceSubmission,
        kind: InferenceKind,
        image_bytes: bytes,
        content_type: str,
        context: DetectContext,
        settings: Any,
        deadline: float,
    ) -> Any:
        with self._submission_lock:
            if submission.revoked:
                # This is acknowledgement of revoked authority, NOT evidence
                # that an unavailable executor Future was observed completing.
                if submission.uncertain and self._submission is submission:
                    self._submission = None
                return None
        return self._run(
            kind, image_bytes, content_type, context, settings,
            request_deadline=deadline,
        )

    def _submission_finished(
        self,
        submission: _InferenceSubmission,
        future: Future[DetectResponse | DetectV2Response],
    ) -> None:
        if not future.cancelled():
            future.exception()
        with self._submission_lock:
            if self._submission is submission and submission.future is future:
                self._submission = None

    def run_legacy(
        self,
        *,
        image_bytes: bytes,
        content_type: str,
        context: DetectContext,
        settings: Any,
    ) -> DetectResponse:
        return self._run("legacy", image_bytes, content_type, context, settings)

    def run_v2(
        self,
        *,
        image_bytes: bytes,
        content_type: str,
        context: DetectContext,
        settings: Any,
    ) -> DetectV2Response:
        return self._run("v2", image_bytes, content_type, context, settings)

    def warmup_v2(self, settings: Any) -> None:
        if self.is_warmed("v2"):
            return
        self.run_v2(
            image_bytes=WARMUP_PNG_BYTES,
            content_type="image/png",
            context=DetectContext(),
            settings=settings,
        )

    def is_warmed(self, kind: InferenceKind) -> bool:
        # This short lock fences observation from Process.close without waiting
        # for inference, joins, or IPC. A busy warmed worker can remain ready.
        if not self._state_lock.acquire(blocking=False):
            return False
        try:
            return (
                self._usable
                and kind in self._warmed_kinds
                and self._process is not None
                and self._process.is_alive()
                and self._connection is not None
            )
        finally:
            self._state_lock.release()

    def _run(
        self,
        kind: InferenceKind,
        image_bytes: bytes,
        content_type: str,
        context: DetectContext,
        settings: Any,
        *,
        request_deadline: float | None = None,
    ) -> Any:
        queue_timeout = self.queue_timeout_seconds
        if request_deadline is not None:
            remaining = request_deadline - time.monotonic()
            if remaining <= 0:
                raise InferenceQueueDeadlineExceeded("detector queue deadline exceeded")
            queue_timeout = min(queue_timeout, remaining)
        if not self._lock.acquire(timeout=queue_timeout):
            raise InferenceQueueDeadlineExceeded("detector queue deadline exceeded")
        if request_deadline is not None and request_deadline <= time.monotonic():
            self._lock.release()
            raise InferenceQueueDeadlineExceeded("detector queue deadline exceeded")
        try:
            started_at = time.monotonic()
            start_deadline = started_at + self.startup_timeout_seconds
            if request_deadline is not None:
                start_deadline = min(start_deadline, request_deadline)
            self._ensure_worker(start_deadline)
            assert self._connection is not None
            execution_timeout = self.timeout_seconds if kind in self._warmed_kinds else self.startup_timeout_seconds
            deadline = time.monotonic() + execution_timeout
            if self._context is self._direct_context and kind not in self._warmed_kinds:
                deadline = min(deadline, start_deadline)
            if request_deadline is not None:
                deadline = min(deadline, request_deadline)
            job_id = uuid.uuid4().hex
            response = self._connection.exchange(
                (job_id, kind, image_bytes, content_type, context.model_dump(mode="json"), settings),
                deadline,
            )
            _check_deadline(deadline)
            if not isinstance(response, tuple) or len(response) < 3 or response[0] != job_id:
                raise RuntimeError("detector worker returned an invalid response")
            if response[1] == "error":
                if (
                    kind == "v2"
                    and len(response) == 5
                    and response[2] == "RuntimeError"
                    and isinstance(response[3], str)
                    and response[4] == _V2_PROVIDER_UNAVAILABLE_CODE
                ):
                    raise _RecoverableProviderUnavailable(f"RuntimeError: {response[3]}")
                if (
                    len(response) == 5
                    and response[2] == "RuntimeError"
                    and (
                        (response[3] == "image_decode_failed" and response[4] == _MALFORMED_IMAGE_INPUT_CODE)
                        or (response[3] == "invalid_detection_context" and response[4] == _MALFORMED_CONTEXT_INPUT_CODE)
                    )
                ):
                    raise _RecoverableMalformedInput(f"RuntimeError: {response[3]}")
                if len(response) != 4:
                    raise RuntimeError("detector worker returned an invalid response")
                error_type = str(response[2])
                error_message = str(response[3]) if len(response) > 3 else ""
                raise RuntimeError(f"{error_type}: {error_message}")
            if response[1] != "ok" or len(response) != 3:
                raise RuntimeError("detector worker returned an invalid response")
            try:
                response_model = DetectResponse if kind == "legacy" else DetectV2Response
                result = response_model.model_validate(response[2])
            except Exception as exc:
                raise RuntimeError("detector worker returned an invalid response") from exc
            _check_deadline(deadline)
            with self._state_lock:
                _check_deadline(deadline)
                self._warmed_kinds.add(kind)
            return result
        except _RecoverableProviderUnavailable as exc:
            # Only the typed provider precondition leaves the child trustworthy.
            raise RuntimeError(str(exc)) from exc
        except _RecoverableMalformedInput as exc:
            # A typed request error leaves the worker and model state trustworthy.
            raise RuntimeError(str(exc)) from exc
        except InferenceRestartAdmissionLimited:
            # No worker was created. Do not turn admission into fatal cleanup.
            raise
        except BaseException as exc:
            try:
                self._terminate_worker()
            except BaseException as cleanup:
                if not isinstance(exc, Exception):
                    exc.add_note("detector worker cleanup failed")
                    raise exc
                if not isinstance(cleanup, Exception):
                    raise
                raise RuntimeError("detector worker cleanup failed") from exc
            if isinstance(exc, InferenceDeadlineExceeded):
                raise
            if isinstance(exc, (EOFError, BrokenPipeError, OSError)):
                raise RuntimeError("detector worker exited unexpectedly") from exc
            raise
        finally:
            self._lock.release()

    def _ensure_worker(self, start_deadline: float | None = None) -> None:
        if self._shutdown_requested.is_set():
            raise RuntimeError("detector shutdown requested")
        if (
            self._usable
            and self._process is not None
            and self._process.is_alive()
            and self._connection is not None
        ):
            return
        # A failed retirement keeps its references and must finish before any
        # socketpair or new process is created.
        if (
            self._connection is not None
            or self._child_connection is not None
            or self._process is not None
        ):
            self._terminate_worker()
        self._reserve_worker_creation()
        parent_connection, child_connection = socket.socketpair()
        self._connection = _FramedSocket(parent_connection)
        self._child_connection = child_connection
        parent_connection.setblocking(False)
        if self._context is self._direct_context:
            if os.name != "posix" or not hasattr(os, "posix_spawn"):
                raise RuntimeError("direct detector spawn requires POSIX")
            if start_deadline is None:
                start_deadline = time.monotonic() + self.startup_timeout_seconds
            _check_deadline(start_deadline)
            process = _spawn_posix_worker(child_connection)
            with self._process_lock:
                self._process = process
                self._process_started = True
            child_connection.close()
            self._child_connection = None
            if self._shutdown_requested.is_set():
                raise RuntimeError("detector shutdown requested")
            self._connection.wait_ready(start_deadline)
            with self._state_lock:
                _check_deadline(start_deadline)
                if self._shutdown_requested.is_set():
                    raise RuntimeError("detector shutdown requested")
                self._usable = True
            return
        process = self._context.Process(target=_inference_worker, args=(child_connection,), daemon=True)
        with self._process_lock:
            self._process = process
            self._process_started = None
        try:
            process.start()
        except BaseException:
            try:
                with self._process_lock:
                    self._process_started = process.pid is not None
            except Exception:
                # Unknown ownership is not permission to discard the handle.
                pass
            raise
        with self._process_lock:
            self._process_started = True
        child_connection.close()
        self._child_connection = None
        with self._state_lock:
            if self._shutdown_requested.is_set():
                raise RuntimeError("detector shutdown requested")
            self._usable = True

    def _reserve_worker_creation(self) -> None:
        now = time.monotonic()
        while (
            self._worker_creation_times
            and now - self._worker_creation_times[0] >= _WORKER_CREATION_WINDOW_SECONDS
        ):
            self._worker_creation_times.popleft()
        if (
            len(self._worker_creation_times) >= _MAX_WORKER_CREATIONS_PER_WINDOW
            or (
                self._worker_creation_times
                and now - self._worker_creation_times[-1] < _MIN_WORKER_CREATION_INTERVAL_SECONDS
            )
        ):
            if not self._restart_admission_limited:
                self._restart_admission_limited = True
                try:
                    logger.warning("inference_worker_restart_admission_limited")
                except Exception:
                    # Logging must not turn an admission rejection into cleanup.
                    pass
            raise InferenceRestartAdmissionLimited()
        # Reserve before socketpair/Process/start, including unsuccessful effects.
        self._worker_creation_times.append(now)
        self._restart_admission_limited = False

    def _terminate_worker(self) -> None:
        with self._state_lock:
            self._usable = False
            self._warmed_kinds.clear()
        errors: list[BaseException] = []

        connection = self._connection
        if connection is not None:
            try:
                connection.close()
            except BaseException as exc:
                errors.append(exc)
            else:
                self._connection = None

        child_connection = self._child_connection
        if child_connection is not None:
            try:
                child_connection.close()
            except BaseException as exc:
                errors.append(exc)
            else:
                self._child_connection = None

        with self._process_lock:
            process = self._process
            if process is not None:
                if self._process_started is None:
                    errors.append(RuntimeError("detector worker start status unknown"))
                elif self._process_started is False:
                    # An unstarted Process cannot be joined or queried for exit.
                    try:
                        process.close()
                    except BaseException as exc:
                        errors.append(exc)
                    else:
                        self._process = None
                else:
                    for action in ("terminate", "kill"):
                        try:
                            alive = process.is_alive()
                        except BaseException as exc:
                            errors.append(exc)
                            break
                        if alive:
                            try:
                                getattr(process, action)()
                            except BaseException as exc:
                                errors.append(exc)
                        try:
                            process.join(timeout=0.5)
                        except BaseException as exc:
                            errors.append(exc)
                        try:
                            if not process.is_alive():
                                break
                        except BaseException as exc:
                            errors.append(exc)
                            break
                    try:
                        exited = not process.is_alive() and process.exitcode is not None
                    except BaseException as exc:
                        errors.append(exc)
                        exited = False
                    if exited:
                        try:
                            process.close()
                        except BaseException as exc:
                            errors.append(exc)
                        else:
                            # Do not inspect a successfully closed Process again,
                            # even when an endpoint still needs a cleanup retry.
                            self._process = None
                            self._process_started = False
                    else:
                        errors.append(RuntimeError("detector worker exit is unconfirmed"))
        _raise_cleanup_errors(errors, "detector worker cleanup failed")

    def _interrupt_worker_for_close(self, deadline: float) -> None:
        remaining = max(0.0, deadline - time.monotonic())
        if not self._process_lock.acquire(timeout=remaining):
            raise TimeoutError("detector shutdown deadline exceeded")
        try:
            process = self._process
            if process is not None and self._process_started is True and process.is_alive():
                process.kill()
                process.join(timeout=min(0.1, max(0.0, deadline - time.monotonic())))
        finally:
            self._process_lock.release()

    def close(self) -> None:
        # Count waiting closers too: one completed close must not reopen
        # admission while another close is still pending.
        deadline = time.monotonic() + _SHUTDOWN_DEADLINE_SECONDS
        with self._submission_lock:
            self._closing_count += 1
        completed = False
        try:
            if not self._close_lock.acquire(timeout=max(0.0, deadline - time.monotonic())):
                raise TimeoutError("detector shutdown deadline exceeded")
            try:
                errors: list[BaseException] = []
                with self._submission_lock:
                    executor = self._executor
                    if executor is not None:
                        self._executor_retiring = True
                if executor is not None:
                    drained = threading.Event()
                    drain_errors: list[BaseException] = []

                    def drain_executor() -> None:
                        try:
                            executor.shutdown(wait=True)
                        except BaseException as exc:
                            drain_errors.append(exc)
                        finally:
                            drained.set()

                    try:
                        # Never hold the runner lock here: an accepted job may
                        # still need it. Shutdown also cancels unstarted work.
                        executor.shutdown(wait=False, cancel_futures=True)
                        threading.Thread(target=drain_executor, daemon=True).start()
                        drained.wait(min(_SHUTDOWN_GRACE_SECONDS, max(0.0, deadline - time.monotonic())))
                        while not drained.is_set() and time.monotonic() < deadline:
                            self._shutdown_requested.set()
                            self._interrupt_worker_for_close(deadline)
                            drained.wait(min(0.05, max(0.0, deadline - time.monotonic())))
                        if not drained.is_set():
                            raise TimeoutError("detector shutdown deadline exceeded")
                        _raise_cleanup_errors(drain_errors, "detector executor shutdown failed")
                    except BaseException as exc:
                        # Keep ownership and block new submission until retry.
                        errors.append(exc)
                    else:
                        completed_submission = None
                        with self._submission_lock:
                            if self._executor is executor:
                                self._executor = None
                                self._executor_retiring = False
                            submission = self._submission
                            if submission is not None and submission.executor is executor:
                                if submission.uncertain:
                                    # The owner has joined/drained this executor;
                                    # any later revoked wrapper still cannot run.
                                    submission.revoked = True
                                    self._submission = None
                                elif submission.future is not None and submission.future.done():
                                    completed_submission = submission
                                else:
                                    errors.append(RuntimeError("detector submission exit is unconfirmed"))
                        if completed_submission is not None:
                            self._submission_finished(
                                completed_submission, completed_submission.future,
                            )
                lock_wait = min(
                    _SHUTDOWN_GRACE_SECONDS if executor is None else 0.0,
                    max(0.0, deadline - time.monotonic()),
                )
                acquired = self._lock.acquire(timeout=lock_wait)
                while not acquired and time.monotonic() < deadline:
                    self._shutdown_requested.set()
                    try:
                        self._interrupt_worker_for_close(deadline)
                    except BaseException as exc:
                        errors.append(exc)
                        break
                    acquired = self._lock.acquire(
                        timeout=min(0.05, max(0.0, deadline - time.monotonic())),
                    )
                if acquired:
                    try:
                        # No sentinel send: preserve existing worker retirement.
                        self._terminate_worker()
                    except BaseException as exc:
                        errors.append(exc)
                    finally:
                        self._lock.release()
                else:
                    errors.append(TimeoutError("detector shutdown deadline exceeded"))
                _raise_cleanup_errors(errors, "detector worker cleanup failed")
                completed = True
            finally:
                self._close_lock.release()
        finally:
            with self._submission_lock:
                self._closing_count -= 1
                if completed and self._closing_count == 0:
                    self._shutdown_requested.clear()
