"""Process-isolated image sanitization for detector uploads."""

from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar

import asyncio
from concurrent.futures import Executor, Future, InvalidStateError
import multiprocessing
import socket
import threading
import time
from typing import Callable

from fastapi import HTTPException, UploadFile

from backend.app.config import Settings
from backend.app.uploads import (
    ImageDimensionsTooLarge,
    read_image_upload,
)

_CLEANUP_JOIN_SECONDS = 0.25
_MAX_FRAME_BYTES = 64 * 1024 * 1024
_MAX_CONTENT_TYPE_BYTES = 255
_FRAME_HEADER_BYTES = 5
_TAG_REQUEST = b"Q"
_TAG_OK = b"O"
_TAG_DIMENSIONS = b"D"
_TAG_RUNTIME = b"R"
_TAG_INVALID = b"I"
_FRAME_TAGS = frozenset(
    (_TAG_REQUEST, _TAG_OK, _TAG_DIMENSIONS, _TAG_RUNTIME, _TAG_INVALID)
)
_ERROR_TAGS = frozenset((_TAG_DIMENSIONS, _TAG_RUNTIME, _TAG_INVALID))


class _FrameProtocolError(Exception):
    pass


class DetectionPreprocessingDeadlineExceeded(TimeoutError):
    pass


class DetectionPreprocessingCleanupError(RuntimeError):
    pass


class DetectionPreprocessingUnavailable(HTTPException):
    def __init__(self) -> None:
        super().__init__(
            status_code=503,
            detail={
                "code": "detect_preprocessing_unavailable",
                "message": "Image preprocessing is unavailable. Retry with a fresh frame.",
                "retry_after_seconds": 1,
            },
            headers={"Retry-After": "1"},
        )


def _send_frame(
    channel: socket.socket,
    tag: bytes,
    *parts: bytes,
    deadline: float | None,
) -> None:
    if tag not in _FRAME_TAGS or any(not isinstance(part, bytes) for part in parts):
        raise _FrameProtocolError("invalid preprocessing frame")
    size = sum(len(part) for part in parts)
    if size > _MAX_FRAME_BYTES or (tag in _ERROR_TAGS and size != 0):
        raise _FrameProtocolError("invalid preprocessing frame size")
    header = tag + size.to_bytes(_FRAME_HEADER_BYTES - 1, "big")
    for part in (header, *parts):
        if deadline is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise DetectionPreprocessingDeadlineExceeded(
                    "image preprocessing deadline exceeded"
                )
            channel.settimeout(remaining)
        channel.sendall(part)


def _receive_exact(
    channel: socket.socket,
    size: int,
    *,
    deadline: float | None,
) -> bytes:
    data = bytearray()
    while len(data) < size:
        if deadline is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise DetectionPreprocessingDeadlineExceeded(
                    "image preprocessing deadline exceeded"
                )
            channel.settimeout(remaining)
        part = channel.recv(min(size - len(data), 65536))
        if not part:
            raise EOFError("preprocessing channel closed")
        data.extend(part)
    return bytes(data)


def _receive_frame(
    channel: socket.socket,
    *,
    deadline: float | None,
) -> tuple[bytes, bytes]:
    header = _receive_exact(channel, _FRAME_HEADER_BYTES, deadline=deadline)
    tag = header[:1]
    size = int.from_bytes(header[1:], "big")
    if tag not in _FRAME_TAGS or size > _MAX_FRAME_BYTES:
        raise _FrameProtocolError("invalid preprocessing frame header")
    if tag in _ERROR_TAGS and size != 0:
        raise _FrameProtocolError("invalid preprocessing error frame")
    payload = _receive_exact(channel, size, deadline=deadline)
    return tag, payload


def _preprocessing_worker(connection: socket.socket) -> None:
    from backend.app.uploads import strip_image_metadata

    try:
        tag, payload = _receive_frame(connection, deadline=None)
        if tag != _TAG_REQUEST or not payload:
            raise _FrameProtocolError("invalid preprocessing request")
        content_type_size = payload[0]
        if content_type_size == 0 or len(payload) <= content_type_size + 1:
            raise _FrameProtocolError("invalid preprocessing request")
        content_type = payload[1 : content_type_size + 1].decode("ascii")
        content = payload[content_type_size + 1 :]
        try:
            sanitized = strip_image_metadata(content, content_type)
        except ImageDimensionsTooLarge:
            response = (_TAG_DIMENSIONS, b"")
        except MemoryError:
            raise
        except RuntimeError:
            response = (_TAG_RUNTIME, b"")
        except Exception:
            response = (_TAG_INVALID, b"")
        else:
            response = (_TAG_OK, sanitized)
        _send_frame(connection, *response, deadline=None)
    except (EOFError, BrokenPipeError, OSError, UnicodeError, _FrameProtocolError):
        pass
    finally:
        connection.close()


class _ProcessSanitizationExecutor(Executor):
    """One request-owned child that can be terminated on cancellation."""

    def __init__(
        self,
        *,
        deadline: float,
        on_closed: Callable[[_ProcessSanitizationExecutor], None],
    ) -> None:
        self._deadline = deadline
        self._on_closed = on_closed
        self._context = multiprocessing.get_context("spawn")
        self._state_lock = threading.Lock()
        self._cleanup_lock = threading.Lock()
        self._future: Future[bytes] | None = None
        self._parent: socket.socket | None = None
        self._child: socket.socket | None = None
        self._process: multiprocessing.Process | None = None
        self._process_started: bool | None = False
        self._thread: threading.Thread | None = None
        self._reaper: threading.Thread | None = None
        self._abort_requested = False
        self._closed = False
        self._finished = False
        self._future_reported = False

    def submit(self, fn, /, *args, **kwargs) -> Future[bytes]:
        if kwargs or len(args) != 2:
            raise TypeError("image sanitization requires content and content type")
        content, content_type = args
        if not isinstance(content, bytes) or not isinstance(content_type, str):
            raise TypeError("image sanitization arguments have invalid types")

        future: Future[bytes] = Future()
        parent, child = socket.socketpair()
        process: multiprocessing.Process | None = None
        try:
            process = self._context.Process(
                target=_preprocessing_worker,
                args=(child,),
                daemon=True,
            )
            worker = threading.Thread(
                target=self._start_and_exchange,
                args=(process, content, content_type),
                name="detect-preprocessing-ipc",
                daemon=True,
            )
        except BaseException as creation_error:
            for resource in (parent, child, process):
                if resource is None:
                    continue
                try:
                    resource.close()
                except BaseException as cleanup_error:
                    creation_error.add_note(
                        "preprocessing construction cleanup failed: "
                        f"{type(cleanup_error).__name__}"
                    )
            raise
        with self._state_lock:
            if self._closed or self._future is not None:
                parent.close()
                child.close()
                process.close()
                raise RuntimeError("preprocessing executor is no longer available")
            self._future = future
            self._parent = parent
            self._child = child
            self._process = process
            self._process_started = None
            self._thread = worker
            try:
                worker.start()
            except BaseException as exc:
                self._thread = None
                self._process_started = False
                start_error = exc
            else:
                start_error = None
        if start_error is not None:
            cleanup_error: BaseException | None = None
            try:
                self._close_child()
            except BaseException as exc:
                cleanup_error = exc
            try:
                self.close()
            except BaseException as exc:
                cleanup_error = exc
            if isinstance(start_error, Exception):
                raise DetectionPreprocessingUnavailable() from (cleanup_error or start_error)
            raise start_error
        return future

    def _start_and_exchange(
        self,
        process: multiprocessing.Process,
        content: bytes,
        content_type: str,
    ) -> None:
        try:
            with self._state_lock:
                aborted = self._abort_requested
            if aborted:
                with self._cleanup_lock:
                    self._process_started = False
                raise DetectionPreprocessingUnavailable()
            process.start()
            with self._cleanup_lock:
                self._process_started = True
        except BaseException as exc:
            with self._cleanup_lock:
                if self._process_started is None:
                    self._process_started = process.pid is not None
            close_error: BaseException | None = None
            try:
                self._close_child()
            except BaseException as close:
                close_error = close
            failure: BaseException = DetectionPreprocessingUnavailable()
            failure.__cause__ = close_error or exc
            try:
                self._cleanup_process()
            except BaseException as cleanup:
                failure = DetectionPreprocessingUnavailable()
                failure.__cause__ = cleanup
            self._complete(None, failure)
            return

        try:
            self._close_child()
        except BaseException as exc:
            failure = DetectionPreprocessingUnavailable()
            failure.__cause__ = exc
            try:
                self._cleanup_process()
            except BaseException as cleanup:
                failure = DetectionPreprocessingUnavailable()
                failure.__cause__ = cleanup
            self._complete(None, failure)
            return
        with self._state_lock:
            aborted = self._abort_requested
        if aborted:
            try:
                self._cleanup_process()
            except BaseException as cleanup:
                failure = DetectionPreprocessingUnavailable()
                failure.__cause__ = cleanup
            else:
                failure = DetectionPreprocessingUnavailable()
            self._complete(None, failure)
            return
        self._exchange(content, content_type)

    def _close_child(self) -> None:
        with self._cleanup_lock:
            child = self._child
            if child is not None:
                child.close()
                self._child = None

    def _exchange(self, content: bytes, content_type: str) -> None:
        result: bytes | None = None
        failure: BaseException | None = None
        try:
            parent = self._parent
            if parent is None:
                raise DetectionPreprocessingUnavailable()
            content_type_bytes = content_type.encode("ascii")
            if not 0 < len(content_type_bytes) <= _MAX_CONTENT_TYPE_BYTES:
                raise _FrameProtocolError("invalid preprocessing content type")
            _send_frame(
                parent,
                _TAG_REQUEST,
                bytes((len(content_type_bytes),)),
                content_type_bytes,
                content,
                deadline=self._deadline,
            )
            status, payload = _receive_frame(parent, deadline=self._deadline)
            if status == _TAG_OK:
                result = payload
            elif status == _TAG_DIMENSIONS:
                raise ImageDimensionsTooLarge(
                    "image dimensions exceed the configured safety boundary"
                )
            elif status == _TAG_RUNTIME:
                raise RuntimeError("Pillow is required for privacy-safe image uploads")
            elif status == _TAG_INVALID:
                raise ValueError("image could not be decoded and sanitized")
            else:
                raise DetectionPreprocessingUnavailable()
        except socket.timeout as exc:
            failure = DetectionPreprocessingDeadlineExceeded(
                "image preprocessing deadline exceeded"
            )
            failure.__cause__ = exc
        except DetectionPreprocessingDeadlineExceeded as exc:
            failure = exc
        except (EOFError, BrokenPipeError, OSError) as exc:
            with self._state_lock:
                aborted = self._abort_requested
            failure = (
                DetectionPreprocessingDeadlineExceeded("image preprocessing deadline exceeded")
                if aborted else DetectionPreprocessingUnavailable()
            )
            failure.__cause__ = exc
        except BaseException as exc:
            if isinstance(exc, (HTTPException, ImageDimensionsTooLarge, ValueError)):
                failure = exc
            elif isinstance(exc, RuntimeError) and str(exc) == (
                "Pillow is required for privacy-safe image uploads"
            ):
                failure = exc
            elif isinstance(exc, Exception):
                failure = DetectionPreprocessingUnavailable()
                failure.__cause__ = exc
            else:
                failure = exc

        try:
            self._cleanup_process()
        except BaseException as cleanup:
            if isinstance(cleanup, Exception):
                failure = DetectionPreprocessingUnavailable()
                failure.__cause__ = cleanup
            else:
                failure = cleanup
        self._complete(result, failure)

    def _cleanup_process(self) -> None:
        errors: list[BaseException] = []
        with self._cleanup_lock:
            if self._process_started is not None and self._child is not None:
                try:
                    self._child.close()
                except BaseException as exc:
                    errors.append(exc)
                else:
                    self._child = None
            parent = self._parent
            if parent is not None:
                try:
                    parent.close()
                except BaseException as exc:
                    errors.append(exc)
                else:
                    self._parent = None

            process = self._process
            if process is not None:
                started = self._process_started
                if started is None:
                    errors.append(RuntimeError("preprocessing worker start status unknown"))
                elif started is False:
                    try:
                        process.close()
                    except BaseException as exc:
                        errors.append(exc)
                    else:
                        self._process = None
                else:
                    for action in ("terminate", "kill"):
                        try:
                            if process.is_alive():
                                getattr(process, action)()
                                process.join(timeout=_CLEANUP_JOIN_SECONDS)
                            if not process.is_alive():
                                break
                        except BaseException as exc:
                            errors.append(exc)
                    try:
                        exited = not process.is_alive()
                    except BaseException as exc:
                        errors.append(exc)
                        exited = False
                    if exited:
                        try:
                            process.close()
                        except BaseException as exc:
                            errors.append(exc)
                        else:
                            self._process = None
                            self._process_started = False
                    else:
                        errors.append(RuntimeError("preprocessing worker exit is unconfirmed"))
        if errors:
            raise DetectionPreprocessingCleanupError(
                "preprocessing worker cleanup failed"
            ) from errors[0]

    def _complete(self, result: bytes | None, failure: BaseException | None) -> None:
        with self._cleanup_lock:
            worker = self._thread
            cleaned = (
                self._parent is None
                and self._child is None
                and self._process is None
                and (
                    worker is None
                    or worker is threading.current_thread()
                    or not worker.is_alive()
                )
            )
        with self._state_lock:
            if self._finished:
                return
            if cleaned:
                self._finished = True
                self._closed = True
            future = self._future
            deliver = not self._future_reported
            self._future_reported = True
        if cleaned:
            try:
                self._on_closed(self)
            except BaseException as cleanup:
                if failure is None:
                    failure = cleanup
                elif isinstance(failure, Exception) and isinstance(cleanup, Exception):
                    failure.add_note("preprocessing runtime cleanup failed")
        elif failure is None or isinstance(failure, Exception):
            failure = DetectionPreprocessingUnavailable()
        if not cleaned:
            try:
                self._start_reaper()
            except Exception:
                # Report the failed request; a later close may retry reaping.
                pass
        if not deliver or future is None or future.cancelled() or future.done():
            return
        try:
            if failure is not None:
                future.set_exception(failure)
            else:
                assert result is not None
                future.set_result(result)
        except InvalidStateError:
            # An external cancellation may win after the state check.
            pass

    def _start_reaper(self) -> None:
        with self._state_lock:
            if self._finished or self._reaper is not None:
                return
            reaper = threading.Thread(
                target=self._reap_until_closed,
                name="detect-preprocessing-reaper",
                daemon=True,
            )
            self._reaper = reaper
            try:
                reaper.start()
            except BaseException:
                self._reaper = None
                raise

    def _reap_until_closed(self) -> None:
        while True:
            try:
                self.close()
            except DetectionPreprocessingCleanupError:
                pass
            with self._state_lock:
                if self._finished:
                    return
            time.sleep(_CLEANUP_JOIN_SECONDS)

    def request_close(self) -> None:
        """Close through this admitted job's single reaper without joining on the event loop."""
        with self._state_lock:
            if self._finished:
                return
            self._closed = True
            self._abort_requested = True
            submitted = self._future is not None
        if not submitted:
            # No process or IPC thread was registered, so there is nothing to join.
            self.close()
            return
        try:
            self._start_reaper()
        except Exception as exc:
            # Keep the job and capacity owned until a later cleanup confirms closure.
            raise DetectionPreprocessingCleanupError(
                "preprocessing background cleanup could not start"
            ) from exc

    def close(self) -> None:
        with self._state_lock:
            if self._finished:
                return
            self._closed = True
            self._abort_requested = True
            worker = self._thread
        cleanup_error: BaseException | None = None
        try:
            self._cleanup_process()
        except BaseException as exc:
            cleanup_error = exc
        start_pending = self._process_started is None
        if worker is not None and worker is not threading.current_thread() and not start_pending:
            worker.join(timeout=_CLEANUP_JOIN_SECONDS)
        if worker is not None and worker.is_alive():
            cleanup_error = cleanup_error or DetectionPreprocessingCleanupError(
                "preprocessing IPC thread did not exit"
            )
        unavailable = DetectionPreprocessingUnavailable()
        unavailable.__cause__ = cleanup_error
        self._complete(None, unavailable)
        if cleanup_error is not None:
            raise cleanup_error

    def shutdown(self, wait: bool = True, *, cancel_futures: bool = False) -> None:
        self.close()


class _DetectionPreprocessingRuntime:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._jobs: set[_ProcessSanitizationExecutor] = set()
        self._closed = False

    def open(self, deadline: float, on_closed: Callable[[], None]) -> _ProcessSanitizationExecutor:
        def finished(job: _ProcessSanitizationExecutor) -> None:
            try:
                self._forget(job)
            finally:
                on_closed()

        job = _ProcessSanitizationExecutor(
            deadline=deadline,
            on_closed=finished,
        )
        with self._lock:
            if self._closed:
                raise RuntimeError("preprocessing runtime is closed")
            self._jobs.add(job)
        return job

    def _forget(self, job: _ProcessSanitizationExecutor) -> None:
        with self._lock:
            self._jobs.discard(job)

    def close(self) -> None:
        with self._lock:
            self._closed = True
            jobs = list(self._jobs)
        errors: list[BaseException] = []
        for job in jobs:
            try:
                job.close()
            except BaseException as exc:
                errors.append(exc)
        if errors:
            raise RuntimeError("preprocessing runtime cleanup failed") from errors[0]


DetectionPreprocessingRuntime = _DetectionPreprocessingRuntime
_APP_RUNTIME: ContextVar[_DetectionPreprocessingRuntime | None] = ContextVar("walksafe_preprocessing", default=None)


@contextmanager
def use_detection_preprocessing(runtime):
    token = _APP_RUNTIME.set(runtime)
    try:
        yield
    finally:
        _APP_RUNTIME.reset(token)


_RUNTIME_LOCK = threading.Lock()
_RUNTIME = _DetectionPreprocessingRuntime()


def _deadline_http_exception(cause: BaseException) -> HTTPException:
    return HTTPException(
        status_code=503,
        detail={
            "code": "detect_preprocessing_deadline_exceeded",
            "message": "Image preprocessing exceeded its time budget. Retry with a fresh frame.",
            "retry_after_seconds": 1,
        },
        headers={"Retry-After": "1"},
    )


async def read_detection_image_upload(
    image: UploadFile,
    settings: Settings,
    *,
    capacity,
) -> tuple[bytes, str]:
    if not capacity.acquire(blocking=False):
        raise HTTPException(
            status_code=503,
            detail={
                "code": "detect_preprocessing_capacity_exceeded",
                "message": "Image preprocessing is busy. Retry with a fresh image.",
            },
        )

    deadline = time.monotonic() + settings.inference_timeout_seconds
    deadline_or_cancelled = False
    try:
        with _RUNTIME_LOCK:
            job = (_APP_RUNTIME.get() or _RUNTIME).open(deadline, capacity.release)
    except BaseException as exc:
        capacity.release()
        if isinstance(exc, Exception):
            raise DetectionPreprocessingUnavailable() from exc
        raise
    try:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise DetectionPreprocessingDeadlineExceeded(
                "image preprocessing deadline exceeded"
            )
        try:
            return await asyncio.wait_for(
                read_image_upload(
                    image,
                    settings,
                    sanitization_executor=job,
                ),
                timeout=remaining,
            )
        except (asyncio.TimeoutError, DetectionPreprocessingDeadlineExceeded) as exc:
            deadline_or_cancelled = True
            raise _deadline_http_exception(exc) from exc
        except asyncio.CancelledError:
            deadline_or_cancelled = True
            raise
    finally:
        try:
            # A busy default executor must not delay the deadline/cancellation
            # response. The job retains its slot until its own reaper confirms cleanup.
            job.request_close()
        except DetectionPreprocessingCleanupError as exc:
            # The runtime retains this job and its slot until cleanup is confirmed.
            if not deadline_or_cancelled:
                raise DetectionPreprocessingUnavailable() from exc


def close_detection_preprocessing() -> None:
    scoped = _APP_RUNTIME.get()
    if scoped is not None:
        scoped.close()
        return
    global _RUNTIME
    with _RUNTIME_LOCK:
        _RUNTIME.close()
        _RUNTIME = _DetectionPreprocessingRuntime()
