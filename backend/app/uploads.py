from __future__ import annotations

from pathlib import Path

from fastapi import HTTPException, UploadFile, status

from backend.app.config import Settings


IMAGE_SUFFIXES = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
}


def image_suffix(content_type: str) -> str:
    return IMAGE_SUFFIXES.get(content_type, ".jpg")


async def read_image_upload(upload: UploadFile, settings: Settings) -> tuple[bytes, str]:
    content_type = upload.content_type or "application/octet-stream"
    if content_type not in settings.allowed_image_content_types:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={
                "code": "unsupported_image_type",
                "allowed": sorted(settings.allowed_image_content_types),
            },
        )

    content = await upload.read(settings.max_upload_bytes + 1)
    if not content:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": "empty_image"},
        )
    if len(content) > settings.max_upload_bytes:
        raise HTTPException(
            status_code=status.HTTP_413_CONTENT_TOO_LARGE,
            detail={
                "code": "upload_too_large",
                "max_bytes": settings.max_upload_bytes,
            },
        )

    return content, content_type


def write_image_file(destination: Path, content: bytes) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(content)
