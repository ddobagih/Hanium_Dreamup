"""Opt-in local Android depth logs and debug frame captures.

The router is included in the application, but every endpoint is disabled by
default. These files are developer evidence and must not be treated as report
records or production telemetry. Retention cleanup of already stored evidence
runs from the router lifespan even while collection is disabled.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from datetime import UTC, datetime
import json
import logging
from typing import Any, NoReturn
import uuid

from anyio import to_thread
from fastapi import APIRouter, FastAPI, File, Form, HTTPException, Query, Request, Response, UploadFile
from fastapi.routing import APIRoute
from pydantic import ValidationError
from starlette.types import Message

from backend.app.android_debug_storage import AndroidDebugQuotaExceeded, AndroidDebugStorage
from backend.app.config import Settings
from backend.app.schemas import AndroidDebugDepthLogRequest, AndroidDebugFrameCaptureMetadata
from backend.app.uploads import read_image_upload


logger = logging.getLogger(__name__)
# Retention is measured in days; an hourly sweep bounds the overrun without request traffic.
RETENTION_SWEEP_SECONDS = 3600.0


async def run_retention_sweeps(storage: AndroidDebugStorage, stop_requested: asyncio.Event) -> None:
    """Prune existing debug storage at startup and periodically, independent of the enable flag."""

    while True:
        try:
            await to_thread.run_sync(storage.prune_existing, abandon_on_cancel=False)
        except Exception as exc:  # noqa: BLE001 - a debug cleanup failure must not stop the API.
            logger.warning("android_debug_retention_sweep_failed error_type=%s", type(exc).__name__)
        try:
            await asyncio.wait_for(stop_requested.wait(), timeout=RETENTION_SWEEP_SECONDS)
            return
        except TimeoutError:
            pass


def _raise_quota_exceeded(exc: AndroidDebugQuotaExceeded) -> NoReturn:
    raise HTTPException(
        status_code=507,
        detail={"code": "android_debug_storage_quota_exceeded", "quota": exc.quota},
    ) from exc


def create_router(settings: Settings) -> APIRouter:
    storage = AndroidDebugStorage(settings)

    @asynccontextmanager
    async def retention_lifespan(_app: FastAPI) -> AsyncIterator[None]:
        stop_requested = asyncio.Event()
        task = asyncio.create_task(
            run_retention_sweeps(storage, stop_requested),
            name="walksafe-android-debug-retention",
        )
        try:
            yield
        finally:
            stop_requested.set()
            await task

    router = APIRouter(prefix="/android/debug", tags=["android-debug"], lifespan=retention_lifespan)

    class _DepthLogBodyLimitRoute(APIRoute):
        def get_route_handler(self) -> Callable[[Request], Awaitable[Response]]:
            original_handler = super().get_route_handler()

            async def limited_handler(request: Request) -> Response:
                received_bytes = 0

                async def limited_receive() -> Message:
                    nonlocal received_bytes
                    message = await request.receive()
                    if message["type"] == "http.request":
                        received_bytes += len(message.get("body", b""))
                        if received_bytes > settings.max_android_debug_log_bytes:
                            raise HTTPException(
                                status_code=413,
                                detail={
                                    "code": "android_debug_log_too_large",
                                    "max_bytes": settings.max_android_debug_log_bytes,
                                },
                            )
                    return message

                return await original_handler(Request(request.scope, limited_receive))

            return limited_handler

    async def create_depth_log(payload: AndroidDebugDepthLogRequest) -> dict[str, Any]:
        if not settings.android_debug_log_enabled:
            raise HTTPException(
                status_code=404,
                detail={
                    "code": "android_debug_log_disabled",
                },
            )
        raw_size = len(payload.model_dump_json(exclude_none=True).encode("utf-8"))
        if raw_size > settings.max_android_debug_log_bytes:
            raise HTTPException(
                status_code=413,
                detail={
                    "code": "android_debug_log_too_large",
                    "max_bytes": settings.max_android_debug_log_bytes,
                },
            )

        received_at = datetime.now(UTC).isoformat()
        records = [
            {
                "received_at": received_at,
                "schema_version": payload.schema_version,
                "session_id": payload.session_id,
                "device_model": payload.device_model,
                "android_version": payload.android_version,
                "app_version_name": payload.app_version_name,
                "entry": entry.model_dump(mode="json", exclude_none=True),
            }
            for entry in payload.entries
        ]
        try:
            stored = await to_thread.run_sync(storage.append_depth_logs, records, abandon_on_cancel=False)
        except AndroidDebugQuotaExceeded as exc:
            _raise_quota_exceeded(exc)
        return {"ok": True, "stored": stored}

    router.add_api_route(
        "/depth-logs",
        create_depth_log,
        methods=["POST"],
        route_class_override=_DepthLogBodyLimitRoute,
    )

    @router.get("/depth-logs/recent")
    async def recent_depth_logs(limit: int = Query(default=20, ge=1, le=200)) -> dict[str, Any]:
        if not settings.android_debug_log_enabled:
            raise HTTPException(
                status_code=404,
                detail={
                    "code": "android_debug_log_disabled",
                },
            )
        items = await to_thread.run_sync(storage.recent_depth_logs, limit, abandon_on_cancel=False)
        return {"ok": True, "items": items}

    @router.post("/frame-captures")
    async def create_frame_capture(
        metadata: str = Form(...),
        image: UploadFile = File(...),
    ) -> dict[str, Any]:
        if not settings.android_debug_log_enabled:
            raise HTTPException(
                status_code=404,
                detail={
                    "code": "android_debug_log_disabled",
                },
            )
        raw_size = len(metadata.encode("utf-8"))
        if raw_size > settings.max_android_debug_log_bytes:
            raise HTTPException(
                status_code=413,
                detail={
                    "code": "android_frame_capture_metadata_too_large",
                    "max_bytes": settings.max_android_debug_log_bytes,
                },
            )
        try:
            parsed_metadata = json.loads(metadata)
        except json.JSONDecodeError as exc:
            raise HTTPException(
                status_code=422,
                detail={
                    "code": "android_frame_capture_metadata_invalid",
                },
            ) from exc
        if not isinstance(parsed_metadata, dict):
            raise HTTPException(
                status_code=422,
                detail={
                    "code": "android_frame_capture_metadata_must_be_object",
                },
            )
        try:
            validated_metadata = AndroidDebugFrameCaptureMetadata.model_validate(parsed_metadata)
        except ValidationError as exc:
            # Do not echo rejected values: they may be exactly the data this allowlist keeps out.
            raise HTTPException(
                status_code=422,
                detail={
                    "code": "android_frame_capture_metadata_not_allowed",
                },
            ) from exc

        content, content_type = await read_image_upload(image, settings)
        capture_id = str(uuid.uuid4())
        try:
            await to_thread.run_sync(
                storage.append_frame_capture,
                capture_id,
                content,
                content_type,
                validated_metadata.model_dump(mode="json", exclude_none=True),
                abandon_on_cancel=False,
            )
        except AndroidDebugQuotaExceeded as exc:
            _raise_quota_exceeded(exc)
        return {"ok": True, "capture_id": capture_id}

    @router.get("/frame-captures/recent")
    async def recent_frame_captures(limit: int = Query(default=20, ge=1, le=200)) -> dict[str, Any]:
        if not settings.android_debug_log_enabled:
            raise HTTPException(
                status_code=404,
                detail={
                    "code": "android_debug_log_disabled",
                },
            )
        items = await to_thread.run_sync(storage.recent_frame_captures, limit, abandon_on_cancel=False)
        return {"ok": True, "items": items}

    return router
