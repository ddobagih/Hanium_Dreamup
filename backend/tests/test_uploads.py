from __future__ import annotations

import io
import os
from pathlib import Path
import stat
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

from backend.app.config import get_settings  # noqa: E402
from backend.app.main import app  # noqa: E402
from backend.app.uploads import (  # noqa: E402
    MAX_IMAGE_DIMENSION,
    ImageDimensionsTooLarge,
    strip_image_metadata,
    write_image_file,
)
from asgi_client import ASGITestClient  # noqa: E402


def test_legacy_plaintext_upload_is_never_served_without_database_admin_grant(tmp_path: Path) -> None:
    settings = get_settings()
    previous_upload_dir = settings.upload_dir
    settings.upload_dir = tmp_path
    (tmp_path / "sample.jpg").write_bytes(b"\xff\xd8\xff\xe0" + (b"0" * 16))

    try:
        response = ASGITestClient(app).get("/uploads/sample.jpg")
    finally:
        settings.upload_dir = previous_upload_dir

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "admin_security_required"
    assert not response.content.startswith(b"\xff\xd8\xff")


def test_upload_route_rejects_path_traversal(tmp_path: Path) -> None:
    settings = get_settings()
    previous_upload_dir = settings.upload_dir
    settings.upload_dir = tmp_path

    try:
        response = ASGITestClient(app).get("/uploads/../secret.jpg")
    finally:
        settings.upload_dir = previous_upload_dir

    assert response.status_code == 404


def test_upload_route_never_falls_back_to_a_plaintext_symlink(tmp_path: Path) -> None:
    settings = get_settings()
    previous_upload_dir = settings.upload_dir
    outside = tmp_path / "outside.jpg"
    outside.write_bytes(b"private")
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir()
    (upload_dir / "linked.jpg").symlink_to(outside)
    settings.upload_dir = upload_dir

    try:
        response = ASGITestClient(app).get("/uploads/linked.jpg")
    finally:
        settings.upload_dir = previous_upload_dir

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "admin_security_required"
    assert response.content != b"private"


def test_strip_image_metadata_removes_jpeg_exif_when_decodable() -> None:
    from PIL import Image

    source = io.BytesIO()
    image = Image.new("RGB", (2, 2), color=(255, 0, 0))
    image.save(source, format="JPEG", exif=b"Exif\x00\x00TEST-EXIF")

    sanitized = strip_image_metadata(source.getvalue(), "image/jpeg")

    assert sanitized.startswith(b"\xff\xd8\xff")
    assert b"TEST-EXIF" not in sanitized


def test_strip_image_metadata_rejects_signature_only_malformed_image() -> None:
    import pytest

    with pytest.raises(ValueError, match="decoded and sanitized"):
        strip_image_metadata(b"\xff\xd8\xff\xe0" + (b"0" * 16), "image/jpeg")


def test_strip_image_metadata_rejects_oversized_dimensions_before_decode(monkeypatch) -> None:
    import pytest
    from PIL import Image

    class OversizedImage:
        size = (MAX_IMAGE_DIMENSION + 1, 1)

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def load(self) -> None:
            raise AssertionError("oversized image must be rejected before pixel decode")

    monkeypatch.setattr(Image, "open", lambda _source: OversizedImage())

    with pytest.raises(ImageDimensionsTooLarge, match="dimensions"):
        strip_image_metadata(b"\xff\xd8\xff\xe0", "image/jpeg")


def test_write_image_file_enforces_private_directory_and_file_modes(tmp_path: Path) -> None:
    upload_dir = tmp_path / "uploads"
    destination = upload_dir / "report.jpg"

    write_image_file(destination, b"private-image")

    assert upload_dir.stat().st_mode & 0o777 == 0o700
    assert destination.stat().st_mode & 0o777 == 0o600


def test_write_image_file_fsyncs_content_and_parent_directory(tmp_path: Path, monkeypatch) -> None:
    from backend.app import uploads

    fsync_kinds: list[str] = []
    real_fsync = os.fsync

    def recording_fsync(descriptor: int) -> None:
        metadata = os.fstat(descriptor)
        fsync_kinds.append("directory" if stat.S_ISDIR(metadata.st_mode) else "file")
        real_fsync(descriptor)

    monkeypatch.setattr(uploads.os, "fsync", recording_fsync)

    uploads.write_image_file(tmp_path / "uploads" / "report.jpg", b"durable-image")

    assert fsync_kinds == ["file", "directory"]
