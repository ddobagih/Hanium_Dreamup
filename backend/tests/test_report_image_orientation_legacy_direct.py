"""Keep legacy report bounding boxes aligned with stored image pixels."""

from __future__ import annotations

import io
import json

from fastapi import HTTPException
from PIL import Image
import pytest

from backend.app.main import app
from backend.tests.asgi_client import ASGITestClient


def _jpeg_with_orientation(orientation: int | None) -> bytes:
    output = io.BytesIO()
    exif = Image.Exif()
    if orientation is not None:
        exif[274] = orientation
    with Image.new("RGB", (20, 10), (180, 160, 40)) as image:
        image.save(output, format="JPEG", exif=exif)
    return output.getvalue()


def _submit_legacy_report(image_bytes: bytes):
    return ASGITestClient(app).post(
        "/reports",
        data={
            "metadata": json.dumps(
                {
                    "class_id": 0,
                    "class_name": "damaged_tactile_block",
                    "confidence": 0.91,
                    "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
                    "captured_at": "2026-05-12T12:00:00.000Z",
                    "source": "fake",
                    "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
                }
            )
        },
        files={"image": ("sample.jpg", image_bytes, "image/jpeg")},
    )


@pytest.mark.parametrize("orientation", [2, 3, 4, 5, 6, 7, 8])
def test_legacy_report_rejects_oriented_bbox_before_persistence(orientation: int) -> None:
    def unexpected_persistence(*_args, **_kwargs):  # noqa: ANN002, ANN003
        raise AssertionError("oriented legacy report reached persistence")

    with pytest.MonkeyPatch.context() as patch:
        patch.setattr("backend.app.api.reports._persist_legacy_report", unexpected_persistence)
        response = _submit_legacy_report(_jpeg_with_orientation(orientation))

    assert response.status_code == 422
    assert response.json() == {"detail": {"code": "report_bbox_exif_orientation_unsupported"}}


@pytest.mark.parametrize("orientation", [None, 1])
def test_legacy_report_normal_orientation_reaches_persistence(orientation: int | None) -> None:
    def persistence_reached(*_args, **_kwargs):  # noqa: ANN002, ANN003
        raise HTTPException(status_code=503, detail={"code": "persistence_reached"})

    with pytest.MonkeyPatch.context() as patch:
        patch.setattr("backend.app.api.reports._persist_legacy_report", persistence_reached)
        response = _submit_legacy_report(_jpeg_with_orientation(orientation))

    assert response.status_code == 503
    assert response.json() == {"detail": {"code": "persistence_reached"}}
