"""Validate and sanitize untrusted image uploads before persistence.

MIME type, filename extension, size, and file signature are checked at the
HTTP trust boundary. Pillow decode and metadata-free re-encoding are mandatory;
signature-looking but undecodable input is rejected instead of stored raw.
"""

from __future__ import annotations

import io
import os
from pathlib import Path
import uuid

from fastapi import HTTPException, UploadFile, status
from starlette.concurrency import run_in_threadpool

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
MAX_IMAGE_DIMENSION = 8192
MAX_IMAGE_PIXELS = 20_000_000


class ImageDimensionsTooLarge(ValueError):
    """Raised before decode when an image header exceeds the memory safety boundary."""


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
    except ModuleNotFoundError as exc:
        raise RuntimeError("Pillow is required for privacy-safe image uploads") from exc

    try:
        with Image.open(io.BytesIO(content)) as image:
            width, height = image.size
            if (
                width < 1
                or height < 1
                or width > MAX_IMAGE_DIMENSION
                or height > MAX_IMAGE_DIMENSION
                or width * height > MAX_IMAGE_PIXELS
            ):
                raise ImageDimensionsTooLarge("image dimensions exceed the configured safety boundary")
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
    except ImageDimensionsTooLarge:
        raise
    except Exception as exc:
        raise ValueError("image could not be decoded and sanitized") from exc

    if not matches_image_signature(sanitized, content_type):
        raise ValueError("sanitized image signature is invalid")
    return sanitized


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

    try:
        sanitized = await run_in_threadpool(strip_image_metadata, content, content_type)
    except ImageDimensionsTooLarge as exc:
        raise HTTPException(
            status_code=status.HTTP_413_CONTENT_TOO_LARGE,
            detail={
                "code": "image_dimensions_too_large",
                "max_dimension": MAX_IMAGE_DIMENSION,
                "max_pixels": MAX_IMAGE_PIXELS,
            },
        ) from exc
    except (RuntimeError, ValueError) as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": "invalid_image_content", "message": "image must be decodable and sanitizable"},
        ) from exc
    if len(sanitized) > settings.max_upload_bytes:
        raise HTTPException(
            status_code=status.HTTP_413_CONTENT_TOO_LARGE,
            detail={
                "code": "sanitized_image_too_large",
                "max_bytes": settings.max_upload_bytes,
            },
        )
    return sanitized, content_type


def write_image_file(destination: Path, content: bytes) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    destination.parent.chmod(0o700)
    temporary = destination.with_name(f".{destination.name}.{uuid.uuid4().hex}.tmp")
    flags = (
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    try:
        descriptor = os.open(temporary, flags, 0o600)
        try:
            remaining = memoryview(content)
            while remaining:
                written = os.write(descriptor, remaining)
                if written <= 0:
                    raise OSError("upload write made no progress")
                remaining = remaining[written:]
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        os.replace(temporary, destination)
        _fsync_directory(destination.parent)
    finally:
        temporary.unlink(missing_ok=True)


def remove_image_file(destination: Path) -> None:
    try:
        destination.unlink()
    except FileNotFoundError:
        return
    _fsync_directory(destination.parent)


def _fsync_directory(directory: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_DIRECTORY", 0)
    descriptor = os.open(directory, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
