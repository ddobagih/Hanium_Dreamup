from __future__ import annotations

import hashlib
from pathlib import Path

from PIL import Image

from scripts.audit_walksafe_test_report_contamination_20260711 import _safe_fixture_image


def test_contamination_audit_requires_bound_four_pixel_fixture(tmp_path: Path) -> None:
    image = tmp_path / "fixture.jpg"
    Image.new("RGB", (4, 4), color=(180, 160, 40)).save(image, format="JPEG")
    digest = hashlib.sha256(image.read_bytes()).hexdigest()

    assert _safe_fixture_image(tmp_path, "/uploads/fixture.jpg", digest) == image
    assert _safe_fixture_image(tmp_path, "/uploads/fixture.jpg", "0" * 64) is None

    Image.new("RGB", (40, 40)).save(image, format="JPEG")
    digest = hashlib.sha256(image.read_bytes()).hexdigest()
    assert _safe_fixture_image(tmp_path, "/uploads/fixture.jpg", digest) is None


def test_contamination_audit_rejects_symlink_and_escaped_paths(tmp_path: Path) -> None:
    outside = tmp_path.parent / "outside-fixture.jpg"
    Image.new("RGB", (4, 4)).save(outside, format="JPEG")
    link = tmp_path / "link.jpg"
    link.symlink_to(outside)
    digest = hashlib.sha256(outside.read_bytes()).hexdigest()

    assert _safe_fixture_image(tmp_path, "/uploads/link.jpg", digest) is None
    assert _safe_fixture_image(tmp_path, "/uploads/../outside-fixture.jpg", digest) is None
