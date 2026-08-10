"""Persistent detector subprocess with a hard, restartable execution deadline."""

from __future__ import annotations

import multiprocessing
from multiprocessing.connection import Connection
import threading
import time
from typing import Any, Literal
import uuid

from backend.app.schemas import DetectContext, DetectResponse, DetectV2Response
from backend.app.services.detect_v2 import WARMUP_PNG_BYTES


InferenceKind = Literal["legacy", "v2"]


class InferenceDeadlineExceeded(TimeoutError):
    pass


class InferenceQueueDeadlineExceeded(TimeoutError):
    pass


def _inference_worker(connection: Connection) -> None:
    from backend.app.detector import _run_detection_sync
    from backend.app.services.detect_v2 import run_detect_v2

    try:
        while True:
            request = connection.recv()
            if request is None:
                return
            job_id, kind, image_bytes, content_type, context_payload, settings = request
            try:
                context = DetectContext.model_validate(context_payload)
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
                connection.send((job_id, "ok", result))
            except Exception as exc:
                connection.send((job_id, "error", type(exc).__name__, str(exc)))
    except (EOFError, BrokenPipeError, OSError):
        return
    finally:
        connection.close()


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
        self._lock = threading.Lock()
        self._process: multiprocessing.Process | None = None
        self._connection: Connection | None = None
        self._warmed_kinds: set[InferenceKind] = set()

    def run_legacy(
        self,
        *,
        image_bytes: bytes,
        content_type: str,
        context: DetectContext,
        settings: Any,
    ) -> DetectResponse:
        result = self._run("legacy", image_bytes, content_type, context, settings)
        return DetectResponse.model_validate(result)

    def run_v2(
        self,
        *,
        image_bytes: bytes,
        content_type: str,
        context: DetectContext,
        settings: Any,
    ) -> DetectV2Response:
        result = self._run("v2", image_bytes, content_type, context, settings)
        return DetectV2Response.model_validate(result)

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
        # Readiness must not wait behind an already-running inference. A warmed,
        # live worker is ready while it is busy; a cold busy worker remains
        # unready and the bounded queue path below decides whether to retry.
        acquired = self._lock.acquire(blocking=False)
        if not acquired:
            return (
                kind in self._warmed_kinds
                and self._process is not None
                and self._process.is_alive()
                and self._connection is not None
            )
        try:
            return (
                kind in self._warmed_kinds
                and self._process is not None
                and self._process.is_alive()
                and self._connection is not None
            )
        finally:
            self._lock.release()

    def _run(
        self,
        kind: InferenceKind,
        image_bytes: bytes,
        content_type: str,
        context: DetectContext,
        settings: Any,
    ) -> Any:
        if not self._lock.acquire(timeout=self.queue_timeout_seconds):
            raise InferenceQueueDeadlineExceeded("detector queue deadline exceeded")
        try:
            self._ensure_worker()
            assert self._connection is not None
            execution_timeout = self.timeout_seconds if kind in self._warmed_kinds else self.startup_timeout_seconds
            deadline = time.monotonic() + execution_timeout
            job_id = uuid.uuid4().hex
            self._connection.send(
                (job_id, kind, image_bytes, content_type, context.model_dump(mode="json"), settings)
            )
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not self._connection.poll(remaining):
                self._terminate_worker()
                raise InferenceDeadlineExceeded("detector execution deadline exceeded")
            response = self._connection.recv()
            if not isinstance(response, tuple) or len(response) < 3 or response[0] != job_id:
                self._terminate_worker()
                raise RuntimeError("detector worker returned an invalid response")
            if response[1] == "error":
                error_type = str(response[2])
                error_message = str(response[3]) if len(response) > 3 else ""
                self._terminate_worker()
                raise RuntimeError(f"{error_type}: {error_message}")
            self._warmed_kinds.add(kind)
            return response[2]
        except InferenceDeadlineExceeded:
            raise
        except (EOFError, BrokenPipeError, OSError) as exc:
            self._terminate_worker()
            raise RuntimeError("detector worker exited unexpectedly") from exc
        finally:
            self._lock.release()

    def _ensure_worker(self) -> None:
        if self._process is not None and self._process.is_alive() and self._connection is not None:
            return
        self._terminate_worker()
        parent_connection, child_connection = self._context.Pipe(duplex=True)
        process = self._context.Process(target=_inference_worker, args=(child_connection,), daemon=True)
        process.start()
        child_connection.close()
        self._process = process
        self._connection = parent_connection

    def _terminate_worker(self) -> None:
        connection, process = self._connection, self._process
        self._connection = None
        self._process = None
        self._warmed_kinds.clear()
        if connection is not None:
            connection.close()
        if process is None:
            return
        if process.is_alive():
            process.terminate()
            process.join(timeout=0.5)
        if process.is_alive():
            process.kill()
            process.join(timeout=0.5)

    def close(self) -> None:
        with self._lock:
            if self._connection is not None:
                try:
                    self._connection.send(None)
                except (BrokenPipeError, OSError):
                    pass
            self._terminate_worker()
