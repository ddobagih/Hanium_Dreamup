from __future__ import annotations

import io
from pathlib import Path

from fastapi import HTTPException, UploadFile, status

from backend.app.config import Settings


IMAGE_SUFFIXES = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
}

IMAGE_EXTENSIONS = {
    "image/jpeg": {".jpg", ".jpeg"},
    "image/png": {".png"},
    "image/webp": {".webp"},
}

IMAGE_SIGNATURES = {
    "image/jpeg": (b"\xff\xd8\xff",),
    "image/png": (b"\x89PNG\r\n\x1a\n",),
}


def image_suffix(content_type: str) -> str:
    return IMAGE_SUFFIXES.get(content_type, ".jpg")


def validate_image_extension(filename: str | None, content_type: str) -> None:
    suffix = Path(filename or "").suffix.lower()
    if not suffix:
        return

    expected = IMAGE_EXTENSIONS.get(content_type, set())
    if suffix not in expected:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={
                "code": "image_extension_mismatch",
                "content_type": content_type,
                "received_extension": suffix,
                "expected_extensions": sorted(expected),
            },
        )


def matches_image_signature(content: bytes, content_type: str) -> bool:
    if content_type == "image/webp":
        return len(content) >= 12 and content[:4] == b"RIFF" and content[8:12] == b"WEBP"
    return any(content.startswith(signature) for signature in IMAGE_SIGNATURES.get(content_type, ()))


def strip_image_metadata(content: bytes, content_type: str) -> bytes:
    try:
        from PIL import Image  # type: ignore[import-not-found]
    except ModuleNotFoundError:
        return content

    try:
        with Image.open(io.BytesIO(content)) as image:
            image.load()
            output = io.BytesIO()
            if content_type == "image/jpeg":
                image.convert("RGB").save(output, format="JPEG", quality=92, optimize=True)
            elif content_type == "image/png":
                image.save(output, format="PNG", optimize=True)
            elif content_type == "image/webp":
                image.save(output, format="WEBP", quality=90, method=4)
            else:
                return content
            sanitized = output.getvalue()
    except Exception:
        return content

    return sanitized if matches_image_signature(sanitized, content_type) else content


async def read_image_upload(upload: UploadFile, settings: Settings) -> tuple[bytes, str]:
    content_type = upload.content_type or "application/octet-stream"
    supported_content_types = set(IMAGE_SUFFIXES)
    allowed_content_types = settings.allowed_image_content_types & supported_content_types
    if content_type not in allowed_content_types:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={
                "code": "unsupported_image_type",
                "allowed": sorted(allowed_content_types),
            },
        )

    validate_image_extension(upload.filename, content_type)
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
    if not matches_image_signature(content, content_type):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={
                "code": "image_content_mismatch",
                "content_type": content_type,
            },
        )

    return strip_image_metadata(content, content_type), content_type


def write_image_file(destination: Path, content: bytes) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(content)
