"""Expose legacy v1 and configurable v2 detection HTTP contracts."""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from concurrent.futures import Executor, Future, ThreadPoolExecutor
from threading import BoundedSemaphore, Lock

from anyio import CapacityLimiter, fail_after, to_thread
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import ValidationError

from backend.app.api.request_validation import public_request_validation_detail
from backend.app.config import Settings
from backend.app.detector import detect_health, run_detection
from backend.app.schemas import DetectContext, DetectHealthResponse, DetectResponse, DetectV2Response
from backend.app.services._detection_preprocessing import (
    close_detection_preprocessing as _close_detection_preprocessing,
    read_detection_image_upload,
)
from backend.app.services.detect_v2 import detect_v2_health, run_detect_v2
from backend.app.services.inference_process import (
    InferenceKind,
    InferenceDeadlineExceeded,
    InferenceProcessRunner,
    InferenceQueueDeadlineExceeded,
)
from backend.app.uploads import read_image_upload


INFERENCE_QUEUE_TIMEOUT_SECONDS = 2.0
DETECT_PREPROCESSING_MAX_CONCURRENT = 2
_DETECT_PREPROCESSING_CAPACITY = BoundedSemaphore(DETECT_PREPROCESSING_MAX_CONCURRENT)
# Kept as a test-only override. Production uses the process runtime below.
_DETECT_PREPROCESSING_EXECUTOR: Executor | None = None
logger = logging.getLogger(__name__)
PUBLIC_V1_RUNTIME_REASONS = frozenset(
    {
        "image_decode_failed",
        "model_artifact_unsupported",
        "model_class_order_mismatch",
        "model_dependency_missing",
        "model_load_failed",
        "model_not_configured",
        "model_task_unsupported",
    }
)
PUBLIC_V2_RUNTIME_REASON = re.compile(
    r"(?:detect_v2_(?:mode_unsupported|model_not_configured)(?::[a-z0-9_,.-]+)?"
    r"|detect_v2_(?:runtime_config_not_configured|runtime_config_invalid|artifact_binding_invalid|model_class_order_mismatch))"
)


def _public_runtime_reason(error: RuntimeError, *, contract: str) -> str:
    reason = str(error).strip()
    worker_prefix = re.match(r"^[A-Za-z][A-Za-z0-9_.]*:\s+(.+)$", reason)
    if worker_prefix is not None:
        reason = worker_prefix.group(1).strip()
    if contract == "v1" and reason in PUBLIC_V1_RUNTIME_REASONS:
        return reason
    if contract == "v2" and PUBLIC_V2_RUNTIME_REASON.fullmatch(reason) is not None:
        return reason
    return "detector_runtime_error"


class _DetectionPreprocessingLease(Executor):
    """Keep one admission until its synchronous job finishes or never starts."""

    def __init__(self, capacity: BoundedSemaphore, executor: ThreadPoolExecutor) -> None:
        self._capacity = capacity
        self._executor = executor
        self._lock = Lock()
        self._submitted = False
        self._released = False
        self._future: Future[bytes] | None = None

    def submit(self, fn, /, *args, **kwargs) -> Future[bytes]:
        with self._lock:
            if self._submitted or self._released:
                raise RuntimeError("preprocessing lease is no longer available")
            self._submitted = True
        try:
            future = self._executor.submit(fn, *args, **kwargs)
        except BaseException:
            self._release()
            raise
        with self._lock:
            self._future = future
        # The executor owns the job and this callback owns the lease even after
        # its HTTP waiter is cancelled. A running Future cannot be cancelled.
        future.add_done_callback(self._work_finished)
        return future

    def _work_finished(self, future: Future[bytes]) -> None:
        self._release()

    def _release(self) -> None:
        with self._lock:
            if self._released:
                return
            self._released = True
            self._future = None
        self._capacity.release()

    def release_if_unsubmitted(self) -> None:
        with self._lock:
            if self._submitted or self._released:
                return
            self._released = True
        self._capacity.release()


async def _read_detection_image_upload(
    image: UploadFile,
    settings: Settings,
) -> tuple[bytes, str]:
    if _DETECT_PREPROCESSING_EXECUTOR is None:
        return await read_detection_image_upload(
            image,
            settings,
            capacity=_DETECT_PREPROCESSING_CAPACITY,
        )
    capacity = _DETECT_PREPROCESSING_CAPACITY
    if not capacity.acquire(blocking=False):
        raise HTTPException(
            status_code=503,
            detail={
                "code": "detect_preprocessing_capacity_exceeded",
                "message": "Image preprocessing is busy. Retry with a fresh image.",
            },
        )
    lease = _DetectionPreprocessingLease(capacity, _DETECT_PREPROCESSING_EXECUTOR)
    try:
        return await read_image_upload(image, settings, sanitization_executor=lease)
    finally:
        # Validation/read failures have no worker. Submitted jobs return their
        # admission only through the actual concurrent Future's completion.
        lease.release_if_unsubmitted()


def close_detection_preprocessing() -> None:
    _close_detection_preprocessing()


def _retrieve_inference_outcome(future: asyncio.Future) -> None:
    if not future.cancelled():
        future.exception()


async def _await_isolated_inference(
    runner: InferenceProcessRunner,
    kind: InferenceKind,
    *,
    image_bytes: bytes,
    content_type: str,
    context: DetectContext,
    settings: Settings,
) -> DetectResponse | DetectV2Response:
    # New post-sanitization budget, not an upload or physical cleanup deadline.
    deadline = time.monotonic() + runner.queue_timeout_seconds + runner.startup_timeout_seconds
    future = runner.submit_inference(
        kind,
        image_bytes=image_bytes,
        content_type=content_type,
        context=context,
        settings=settings,
        deadline=deadline,
    )
    result = asyncio.wrap_future(future)
    result.add_done_callback(_retrieve_inference_outcome)
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise InferenceQueueDeadlineExceeded("detector queue deadline exceeded")
    try:
        # The runner owns execution and capacity. Waiting must not cancel its
        # Future or create a cancelled shield that logs a later worker error.
        done, _ = await asyncio.wait({result}, timeout=remaining)
        if not done:
            raise InferenceQueueDeadlineExceeded("detector queue deadline exceeded")
        return result.result()
    except TimeoutError as exc:
        if isinstance(exc, (InferenceDeadlineExceeded, InferenceQueueDeadlineExceeded)):
            raise
        # Waiting expired; do not claim that native worker cleanup has finished.
        raise InferenceQueueDeadlineExceeded("detector queue deadline exceeded") from exc


def create_inference_runner(settings: Settings) -> InferenceProcessRunner | None:
    return (
        InferenceProcessRunner(
            timeout_seconds=settings.inference_timeout_seconds,
            startup_timeout_seconds=settings.inference_startup_timeout_seconds,
        )
        if settings.inference_process_isolation_enabled
        else None
    )


def create_router(
    settings: Settings,
    *,
    inference_runner: InferenceProcessRunner | None = None,
) -> APIRouter:
    router = APIRouter()
    # Ultralytics inference is synchronous and model objects are not guaranteed
    # to be thread-safe. Keep it off the event loop and serialize access to the
    # process-local model cache.
    inference_limiter = CapacityLimiter(1)
    if inference_runner is None:
        inference_runner = create_inference_runner(settings)

    @router.get("/detect/health", response_model=DetectHealthResponse)
    async def get_detect_health() -> DetectHealthResponse:
        return detect_health()

    @router.post("/detect", response_model=DetectResponse)
    async def detect_objects(
        context: str = Form(default="{}"),
        image: UploadFile = File(...),
    ) -> DetectResponse:
        try:
            parsed_context = DetectContext.model_validate_json(context)
        except (ValidationError, json.JSONDecodeError) as exc:
            raise HTTPException(
                status_code=422,
                detail=public_request_validation_detail(
                    exc, model=DetectContext, field="context", code="detect_context_invalid",
                ),
            ) from None

        image_bytes, content_type = await _read_detection_image_upload(image, settings)

        try:
            if inference_runner is not None:
                return await _await_isolated_inference(
                    inference_runner,
                    "legacy",
                    image_bytes=image_bytes,
                    content_type=content_type,
                    context=parsed_context,
                    settings=settings,
                )
            with fail_after(INFERENCE_QUEUE_TIMEOUT_SECONDS):
                return await run_detection(
                    image_bytes=image_bytes,
                    content_type=content_type,
                    context=parsed_context,
                    limiter=inference_limiter,
                )
        except InferenceQueueDeadlineExceeded as exc:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "detect_queue_timeout",
                    "message": "Detector is busy with a bounded inference window. Retry with a fresh frame.",
                    "retry_after_seconds": 1,
                },
                headers={"Retry-After": "1"},
            ) from exc
        except InferenceDeadlineExceeded as exc:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "detect_inference_deadline_exceeded",
                    "message": "Detector execution exceeded its hard process deadline and was restarted.",
                    "retry_after_seconds": 1,
                },
                headers={"Retry-After": "1"},
            ) from exc
        except TimeoutError as exc:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "detect_queue_timeout",
                    "message": "Detector is busy with a bounded inference window. Retry with a fresh frame.",
                    "retry_after_seconds": 1,
                },
                headers={"Retry-After": "1"},
            ) from exc
        except RuntimeError as exc:
            logger.warning("legacy detector runtime failed", exc_info=True)
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "model_unavailable",
                    "reason": _public_runtime_reason(exc, contract="v1"),
                    "message": "Server inference is unavailable. Check the model artifact, class order, task type, and runtime dependencies.",
                },
            ) from exc

    @router.get("/detect/v2/health")
    async def get_detect_v2_health() -> dict[str, object]:
        return detect_v2_health(settings)

    @router.post("/detect/v2", response_model=DetectV2Response)
    async def detect_objects_v2(
        context: str = Form(default="{}"),
        image: UploadFile = File(...),
    ) -> DetectV2Response:
        try:
            parsed_context = DetectContext.model_validate_json(context)
        except (ValidationError, json.JSONDecodeError) as exc:
            raise HTTPException(
                status_code=422,
                detail=public_request_validation_detail(
                    exc, model=DetectContext, field="context", code="detect_context_invalid",
                ),
            ) from None

        image_bytes, content_type = await _read_detection_image_upload(image, settings)
        try:
            if inference_runner is not None:
                return await _await_isolated_inference(
                    inference_runner,
                    "v2",
                    image_bytes=image_bytes,
                    content_type=content_type,
                    context=parsed_context,
                    settings=settings,
                )
            with fail_after(INFERENCE_QUEUE_TIMEOUT_SECONDS):
                return await to_thread.run_sync(
                    lambda: run_detect_v2(
                        image_bytes=image_bytes,
                        content_type=content_type,
                        context=parsed_context,
                        settings=settings,
                    ),
                    limiter=inference_limiter,
                )
        except InferenceQueueDeadlineExceeded as exc:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "detect_v2_queue_timeout",
                    "message": "Detector is busy with a newer bounded inference window. Retry with a fresh frame.",
                    "retry_after_seconds": 1,
                },
                headers={"Retry-After": "1"},
            ) from exc
        except InferenceDeadlineExceeded as exc:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "detect_v2_inference_deadline_exceeded",
                    "message": "Detector execution exceeded its hard process deadline and was restarted.",
                    "retry_after_seconds": 1,
                },
                headers={"Retry-After": "1"},
            ) from exc
        except TimeoutError as exc:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "detect_v2_queue_timeout",
                    "message": "Detector is busy with a newer bounded inference window. Retry with a fresh frame.",
                    "retry_after_seconds": 1,
                },
                headers={"Retry-After": "1"},
            ) from exc
        except RuntimeError as exc:
            logger.warning("detect v2 runtime failed", exc_info=True)
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "detect_v2_unavailable",
                    "reason": _public_runtime_reason(exc, contract="v2"),
                    "message": "Detect v2 runtime is unavailable. Retry only after service readiness recovers.",
                },
            ) from exc

    return router
