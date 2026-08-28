"""Validate and sanitize untrusted image uploads before persistence.

MIME type, filename extension, size, and file signature are checked at the
HTTP trust boundary. Pillow decode and metadata-free re-encoding are mandatory;
signature-looking but undecodable input is rejected instead of stored raw.
"""

from __future__ import annotations

import io
import grp
import os
from pathlib import Path
import stat
import uuid

from fastapi import HTTPException, UploadFile, status
from starlette.concurrency import run_in_threadpool

from backend.app.config import (
    DEPLOYMENT_ENVIRONMENTS,
    Settings,
    UPLOAD_BACKUP_READER_GROUP,
)


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
PRIVATE_UPLOAD_DIRECTORY_MODE = 0o700
BACKUP_READABLE_UPLOAD_DIRECTORY_MODE = 0o2750
PRIVATE_UPLOAD_FILE_MODE = 0o600
BACKUP_READABLE_UPLOAD_FILE_MODE = 0o640


class ImageDimensionsTooLarge(ValueError):
    """Raised before decode when an image header exceeds the memory safety boundary."""


def descriptor_acl_is_absent(descriptor: int) -> bool:
    try:
        return not any(
            "acl" in os.fsdecode(name).casefold()
            for name in os.listxattr(descriptor)
        )
    except (AttributeError, OSError):
        return False


def expected_upload_file_mode(directory_metadata: os.stat_result) -> int:
    if directory_metadata.st_uid != os.geteuid():
        raise ValueError("upload directory owner differs")
    directory_mode = stat.S_IMODE(directory_metadata.st_mode)
    if directory_mode == PRIVATE_UPLOAD_DIRECTORY_MODE:
        environment = os.environ.get("WALKSAFE_ENVIRONMENT", "development").strip().lower()
        if environment in DEPLOYMENT_ENVIRONMENTS:
            raise ValueError("deployment upload directory must use the backup-reader group")
        return PRIVATE_UPLOAD_FILE_MODE
    if directory_mode == BACKUP_READABLE_UPLOAD_DIRECTORY_MODE:
        try:
            reader_gid = grp.getgrnam(UPLOAD_BACKUP_READER_GROUP).gr_gid
        except KeyError as exc:
            raise ValueError("upload backup-reader group does not exist") from exc
        if reader_gid <= 0 or directory_metadata.st_gid != reader_gid:
            raise ValueError("upload directory backup-reader group differs")
        return BACKUP_READABLE_UPLOAD_FILE_MODE
    raise ValueError("upload directory mode differs")


def _upload_directory_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
    )


def validate_upload_directory_descriptor(
    directory: Path,
    descriptor: int,
    *,
    expected_metadata: os.stat_result | None = None,
) -> os.stat_result:
    metadata = os.fstat(descriptor)
    path_metadata = directory.stat(follow_symlinks=False)
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or _upload_directory_identity(metadata)
        != _upload_directory_identity(path_metadata)
        or not descriptor_acl_is_absent(descriptor)
    ):
        raise ValueError("upload directory identity or ACL differs")
    expected_upload_file_mode(metadata)
    if (
        expected_metadata is not None
        and _upload_directory_identity(metadata)
        != _upload_directory_identity(expected_metadata)
    ):
        raise ValueError("upload directory metadata changed")
    return metadata


def upload_file_metadata_is_safe(
    file_metadata: os.stat_result,
    directory_metadata: os.stat_result,
) -> bool:
    try:
        expected_mode = expected_upload_file_mode(directory_metadata)
    except ValueError:
        return False
    return (
        stat.S_ISREG(file_metadata.st_mode)
        and file_metadata.st_uid == directory_metadata.st_uid
        and file_metadata.st_nlink == 1
        and stat.S_IMODE(file_metadata.st_mode) == expected_mode
        and (
            expected_mode == PRIVATE_UPLOAD_FILE_MODE
            or file_metadata.st_gid == directory_metadata.st_gid
        )
    )


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
    try:
        destination.parent.mkdir(parents=True, mode=PRIVATE_UPLOAD_DIRECTORY_MODE)
    except FileExistsError:
        pass
    else:
        destination.parent.chmod(PRIVATE_UPLOAD_DIRECTORY_MODE)
    directory_descriptor = os.open(
        destination.parent,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        directory_metadata = os.fstat(directory_descriptor)
        anchored_directory = destination.parent.stat(follow_symlinks=False)
        if (
            (directory_metadata.st_dev, directory_metadata.st_ino)
            != (anchored_directory.st_dev, anchored_directory.st_ino)
            or not descriptor_acl_is_absent(directory_descriptor)
        ):
            raise OSError("upload directory identity or ACL differs")
        file_mode = expected_upload_file_mode(directory_metadata)
        temporary_name = f".{destination.name}.{uuid.uuid4().hex}.tmp"
        flags = (
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        temporary_created = False
        try:
            descriptor = os.open(
                temporary_name,
                flags,
                file_mode,
                dir_fd=directory_descriptor,
            )
            temporary_created = True
            try:
                os.fchmod(descriptor, file_mode)
                if not upload_file_metadata_is_safe(
                    os.fstat(descriptor),
                    directory_metadata,
                ) or not descriptor_acl_is_absent(descriptor):
                    raise OSError("upload temporary file permissions or ACL differ")
                remaining = memoryview(content)
                while remaining:
                    written = os.write(descriptor, remaining)
                    if written <= 0:
                        raise OSError("upload write made no progress")
                    remaining = remaining[written:]
                os.fsync(descriptor)
                final_file_metadata = os.fstat(descriptor)
                final_directory_metadata = os.fstat(directory_descriptor)
                final_anchored_directory = destination.parent.stat(
                    follow_symlinks=False
                )
                if (
                    not upload_file_metadata_is_safe(
                        final_file_metadata,
                        final_directory_metadata,
                    )
                    or (
                        final_directory_metadata.st_dev,
                        final_directory_metadata.st_ino,
                        final_directory_metadata.st_mode,
                        final_directory_metadata.st_uid,
                        final_directory_metadata.st_gid,
                        final_directory_metadata.st_nlink,
                    )
                    != (
                        directory_metadata.st_dev,
                        directory_metadata.st_ino,
                        directory_metadata.st_mode,
                        directory_metadata.st_uid,
                        directory_metadata.st_gid,
                        directory_metadata.st_nlink,
                    )
                    or (
                        final_anchored_directory.st_dev,
                        final_anchored_directory.st_ino,
                    )
                    != (
                        final_directory_metadata.st_dev,
                        final_directory_metadata.st_ino,
                    )
                    or not descriptor_acl_is_absent(descriptor)
                    or not descriptor_acl_is_absent(directory_descriptor)
                ):
                    raise OSError("upload file or directory metadata changed")
                os.replace(
                    temporary_name,
                    destination.name,
                    src_dir_fd=directory_descriptor,
                    dst_dir_fd=directory_descriptor,
                )
                temporary_created = False
                os.fsync(directory_descriptor)
            finally:
                os.close(descriptor)
        except BaseException as original_error:
            if temporary_created:
                try:
                    os.unlink(temporary_name, dir_fd=directory_descriptor)
                except FileNotFoundError:
                    pass
                except OSError as cleanup_error:
                    raise RuntimeError("upload temporary file cleanup failed") from original_error
            raise
    finally:
        os.close(directory_descriptor)


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
