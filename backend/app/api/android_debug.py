"""Opt-in local Android depth logs and debug frame captures.

The router is included in the application, but every endpoint is disabled by
default. These files are developer evidence and must not be treated as report
records or production telemetry.
"""

from __future__ import annotations

from collections import deque
from datetime import UTC, datetime, timedelta
import json
import os
from pathlib import Path
import stat
from threading import RLock
from typing import Any
import uuid

from fastapi import APIRouter, File, Form, HTTPException, Query, UploadFile

from backend.app.config import Settings
from backend.app.schemas import AndroidDebugDepthLogRequest
from backend.app.uploads import image_suffix, read_image_upload, write_image_file


DEBUG_DEPTH_LOG_FILENAME = "android_depth_debug.jsonl"
DEBUG_FRAME_CAPTURE_LOG_FILENAME = "android_frame_captures.jsonl"
_DEBUG_STORAGE_LOCK = RLock()


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
        with _DEBUG_STORAGE_LOCK:
            _prune_debug_storage(settings)
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
        with _DEBUG_STORAGE_LOCK:
            _prune_debug_storage(settings)
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
        with _DEBUG_STORAGE_LOCK:
            _prune_debug_storage(settings)
            write_image_file(capture_path, content)
            record = {
                "received_at": datetime.now(UTC).isoformat(),
                "capture_id": capture_id,
                "image_file": str(capture_path),
                "image_content_type": content_type,
                "image_bytes": len(content),
                "metadata": parsed_metadata,
            }
            _append_jsonl(_frame_capture_log_path(settings), [record])
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
        with _DEBUG_STORAGE_LOCK:
            _prune_debug_storage(settings)
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
    _ensure_private_directory(path.parent)
    content = "".join(
        json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
        for record in records
    ).encode("utf-8")
    flags = (
        os.O_WRONLY
        | os.O_APPEND
        | os.O_CREAT
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    descriptor = os.open(path, flags, 0o600)
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid():
            raise OSError("debug log must be a service-owned regular file")
        os.fchmod(descriptor, 0o600)
        remaining = memoryview(content)
        while remaining:
            written = os.write(descriptor, remaining)
            if written <= 0:
                raise OSError("debug log write made no progress")
            remaining = remaining[written:]
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    _fsync_directory(path.parent)


def _ensure_private_directory(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink() or not path.is_dir():
        raise OSError("debug log root must be a real directory")
    path.chmod(0o700)


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(
        path,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_DIRECTORY", 0),
    )
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _prune_debug_storage(settings: Settings, *, now: datetime | None = None) -> None:
    cutoff = (now or datetime.now(UTC)) - timedelta(days=settings.android_debug_log_retention_days)
    referenced_images: set[Path] = set()
    for log_path in (_depth_log_path(settings), _frame_capture_log_path(settings)):
        if not log_path.is_file() or log_path.is_symlink():
            continue
        retained: list[dict[str, Any]] = []
        with log_path.open("r", encoding="utf-8") as source:
            for line in source:
                try:
                    record = json.loads(line)
                    received_at = record.get("received_at") if isinstance(record, dict) else None
                    timestamp = datetime.fromisoformat(str(received_at).replace("Z", "+00:00"))
                except (AttributeError, TypeError, ValueError, json.JSONDecodeError):
                    continue
                if timestamp.tzinfo is None or timestamp.astimezone(UTC) < cutoff:
                    continue
                retained.append(record)
                image_file = record.get("image_file")
                if isinstance(image_file, str):
                    referenced_images.add(Path(image_file).resolve())
        temporary = log_path.with_name(f".{log_path.name}.{uuid.uuid4().hex}.tmp")
        try:
            temporary.parent.mkdir(parents=True, exist_ok=True)
            _append_jsonl(temporary, retained)
            temporary.replace(log_path)
        finally:
            temporary.unlink(missing_ok=True)

    capture_root = _frame_capture_dir(settings).resolve()
    if capture_root.is_dir() and not capture_root.is_symlink():
        for image_path in capture_root.iterdir():
            if (
                image_path.is_file()
                and not image_path.is_symlink()
                and image_path.resolve() not in referenced_images
            ):
                image_path.unlink(missing_ok=True)


def _read_jsonl_tail(path: Path, limit: int) -> list[dict[str, Any]]:
    lines: deque[str] = deque(maxlen=limit)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid():
            raise OSError("debug log must be a service-owned regular file")
        with os.fdopen(descriptor, "r", encoding="utf-8", closefd=False) as source:
            for line in source:
                stripped = line.strip()
                if stripped:
                    lines.append(stripped)
    finally:
        os.close(descriptor)
    return [json.loads(line) for line in lines]
