from __future__ import annotations

from collections import deque
from datetime import UTC, datetime
import json
from pathlib import Path
from typing import Any
import uuid

from fastapi import APIRouter, File, Form, HTTPException, Query, UploadFile

from backend.app.config import Settings
from backend.app.schemas import AndroidDebugDepthLogRequest
from backend.app.uploads import image_suffix, read_image_upload, write_image_file


DEBUG_DEPTH_LOG_FILENAME = "android_depth_debug.jsonl"
DEBUG_FRAME_CAPTURE_LOG_FILENAME = "android_frame_captures.jsonl"


def create_router(settings: Settings) -> APIRouter:
    router = APIRouter(prefix="/android/debug", tags=["android-debug"])

    @router.post("/depth-logs")
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

        log_path = _depth_log_path(settings)
        log_path.parent.mkdir(parents=True, exist_ok=True)
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
        _append_jsonl(log_path, records)
        return {
            "ok": True,
            "stored": len(records),
            "log_file": str(log_path),
        }

    @router.get("/depth-logs/recent")
    async def recent_depth_logs(limit: int = Query(default=20, ge=1, le=200)) -> dict[str, Any]:
        if not settings.android_debug_log_enabled:
            raise HTTPException(
                status_code=404,
                detail={
                    "code": "android_debug_log_disabled",
                },
            )
        log_path = _depth_log_path(settings)
        if not log_path.exists():
            return {"ok": True, "items": []}
        return {
            "ok": True,
            "items": _read_jsonl_tail(log_path, limit),
        }

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

        content, content_type = await read_image_upload(image, settings)
        capture_id = str(uuid.uuid4())
        filename = f"{capture_id}{image_suffix(content_type)}"
        capture_path = _frame_capture_dir(settings) / filename
        write_image_file(capture_path, content)

        record = {
            "received_at": datetime.now(UTC).isoformat(),
            "capture_id": capture_id,
            "image_file": str(capture_path),
            "image_content_type": content_type,
            "image_bytes": len(content),
            "metadata": parsed_metadata,
        }
        log_path = _frame_capture_log_path(settings)
        log_path.parent.mkdir(parents=True, exist_ok=True)
        _append_jsonl(log_path, [record])
        return {
            "ok": True,
            "capture_id": capture_id,
            "image_file": str(capture_path),
        }

    @router.get("/frame-captures/recent")
    async def recent_frame_captures(limit: int = Query(default=20, ge=1, le=200)) -> dict[str, Any]:
        if not settings.android_debug_log_enabled:
            raise HTTPException(
                status_code=404,
                detail={
                    "code": "android_debug_log_disabled",
                },
            )
        log_path = _frame_capture_log_path(settings)
        if not log_path.exists():
            return {"ok": True, "items": []}
        return {
            "ok": True,
            "items": _read_jsonl_tail(log_path, limit),
        }

    return router


def _depth_log_path(settings: Settings) -> Path:
    return settings.android_debug_log_dir / DEBUG_DEPTH_LOG_FILENAME


def _frame_capture_log_path(settings: Settings) -> Path:
    return settings.android_debug_log_dir / DEBUG_FRAME_CAPTURE_LOG_FILENAME


def _frame_capture_dir(settings: Settings) -> Path:
    return settings.android_debug_log_dir / "frame_captures"


def _append_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    with path.open("a", encoding="utf-8") as output:
        for record in records:
            output.write(json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
            output.write("\n")


def _read_jsonl_tail(path: Path, limit: int) -> list[dict[str, Any]]:
    lines: deque[str] = deque(maxlen=limit)
    with path.open("r", encoding="utf-8") as source:
        for line in source:
            stripped = line.strip()
            if stripped:
                lines.append(stripped)
    return [json.loads(line) for line in lines]
