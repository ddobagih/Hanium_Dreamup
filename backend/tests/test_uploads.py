from __future__ import annotations

import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

from backend.app.config import get_settings  # noqa: E402
from backend.app.main import app  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402


def test_serves_uploaded_image_without_staticfiles_threadpool(tmp_path: Path) -> None:
    settings = get_settings()
    previous_upload_dir = settings.upload_dir
    settings.upload_dir = tmp_path
    (tmp_path / "sample.jpg").write_bytes(b"\xff\xd8\xff\xe0" + (b"0" * 16))

    try:
        response = ASGITestClient(app).get("/uploads/sample.jpg")
    finally:
        settings.upload_dir = previous_upload_dir

    assert response.status_code == 200
    assert response.headers["content-type"] == "image/jpeg"
    assert response.content.startswith(b"\xff\xd8\xff")


def test_upload_route_rejects_path_traversal(tmp_path: Path) -> None:
    settings = get_settings()
    previous_upload_dir = settings.upload_dir
    settings.upload_dir = tmp_path

    try:
        response = ASGITestClient(app).get("/uploads/../secret.jpg")
    finally:
        settings.upload_dir = previous_upload_dir

    assert response.status_code == 404
