#!/usr/bin/env python3
"""CPU-only WalkSafe detect.v2/report.v2 contract smoke check.

This uses FastAPI's ASGI app directly. It does not start uvicorn, load YOLO,
or run GPU train/val/predict/inference. By default it forces DETECT_V2_MODE=fake
unless the environment already set another value.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import sys
from pathlib import Path
from typing import Any

import httpx

REPO_ROOT = Path(__file__).resolve().parents[1]
UPLOAD_DIR = REPO_ROOT / "backend" / "uploads" / "smoke"
PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
)


class SmokeFailure(RuntimeError):
    pass


class ASGISmokeClient:
    def __init__(self, app: Any) -> None:
        self._app = app

    def request(self, method: str, url: str, **kwargs: Any) -> httpx.Response:
        async def send() -> httpx.Response:
            transport = httpx.ASGITransport(app=self._app)
            async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
                response = await client.request(method, url, **kwargs)
                await response.aread()
                return response

        return asyncio.run(send())

    def get(self, url: str, **kwargs: Any) -> httpx.Response:
        return self.request("GET", url, **kwargs)

    def post(self, url: str, **kwargs: Any) -> httpx.Response:
        return self.request("POST", url, **kwargs)


def import_app() -> Any:
    sys.path.insert(0, str(REPO_ROOT))
    os.environ.setdefault("UPLOAD_DIR", str(UPLOAD_DIR))
    os.environ.setdefault("DETECT_V2_MODE", "fake")

    from backend.app.main import app  # noqa: PLC0415

    return app


def require_status(response: httpx.Response, status_code: int, context: str) -> dict[str, Any]:
    if response.status_code != status_code:
        raise SmokeFailure(f"{context} returned HTTP {response.status_code}, expected {status_code}: {response.text}")
    try:
        payload = response.json()
    except json.JSONDecodeError as exc:
        raise SmokeFailure(f"{context} returned non-JSON body: {response.text!r}") from exc
    if not isinstance(payload, dict):
        raise SmokeFailure(f"{context} returned non-object JSON: {payload!r}")
    return payload


def post_detect_v2(client: ASGISmokeClient) -> dict[str, Any]:
    context = {
        "captured_at": "2026-05-23T06:00:00.000Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 181.0,
    }
    response = client.post(
        "/detect/v2",
        data={"context": json.dumps(context)},
        files={"image": ("frame.png", PNG_BYTES, "image/png")},
    )
    body = require_status(response, 200, "POST /detect/v2")
    if body.get("schema_version") != "detect.v2":
        raise SmokeFailure(f"POST /detect/v2 returned unexpected schema_version: {body!r}")
    detections = body.get("detections")
    if not isinstance(detections, list):
        raise SmokeFailure(f"POST /detect/v2 returned invalid detections: {body!r}")

    class_names = {detection.get("class_name") for detection in detections if isinstance(detection, dict)}
    if "damaged_tactile_block" not in class_names:
        raise SmokeFailure(f"POST /detect/v2 did not include damaged_tactile_block: {class_names!r}")
    if "person" not in class_names:
        raise SmokeFailure(f"POST /detect/v2 did not include allowlisted COCO person: {class_names!r}")
    if "dog" in class_names:
        raise SmokeFailure("POST /detect/v2 leaked non-allowlisted COCO dog detection")

    print("[PASS] /detect/v2 fake contract returned damaged tactile block + allowlisted general object")
    return body


def check_health(client: ASGISmokeClient, expected_mode: str) -> None:
    response = client.get("/detect/v2/health")
    body = require_status(response, 200, "GET /detect/v2/health")
    if body.get("schema_version") != "detect.v2":
        raise SmokeFailure(f"Unexpected detect.v2 health schema: {body!r}")
    if body.get("mode") != expected_mode:
        raise SmokeFailure(f"Unexpected detect.v2 mode: {body.get('mode')!r}; expected {expected_mode!r}")
    if expected_mode == "fake" and body.get("status") != "ready":
        raise SmokeFailure(f"Fake detect.v2 health should be ready: {body!r}")
    print(f"[PASS] /detect/v2/health mode={body.get('mode')} status={body.get('status')}")


def metadata_for_report(detection: dict[str, Any], *, trigger: str = "auto", auto_reported: bool = True) -> dict[str, Any]:
    metadata = dict(detection)
    metadata["trigger"] = trigger
    metadata["auto_reported"] = auto_reported
    return metadata


def post_report_v2(client: ASGISmokeClient, metadata: dict[str, Any]) -> httpx.Response:
    return client.post(
        "/reports/v2",
        data={"metadata": json.dumps(metadata)},
        files={"image": ("report.png", PNG_BYTES, "image/png")},
    )


def check_reports_v2(client: ASGISmokeClient, detect_body: dict[str, Any]) -> None:
    detections = [detection for detection in detect_body["detections"] if isinstance(detection, dict)]
    damage = next(detection for detection in detections if detection["class_name"] == "damaged_tactile_block")
    general = next(detection for detection in detections if detection["model_key"] == "coco_general")

    allowed = post_report_v2(client, metadata_for_report(damage))
    allowed_body = require_status(allowed, 201, "POST /reports/v2 tactile damage")
    if allowed_body.get("class_name") != "damaged_tactile_block":
        raise SmokeFailure(f"Allowed /reports/v2 stored unexpected class: {allowed_body!r}")
    if allowed_body.get("metadata", {}).get("trigger") != "auto":
        raise SmokeFailure(f"Allowed /reports/v2 did not preserve trigger metadata: {allowed_body!r}")
    print("[PASS] /reports/v2 accepts damaged_tactile_block report")

    rejected = post_report_v2(client, metadata_for_report(general, trigger="voice", auto_reported=False))
    if rejected.status_code != 422:
        raise SmokeFailure(
            f"POST /reports/v2 general object returned HTTP {rejected.status_code}, expected 422: {rejected.text}"
        )
    print("[PASS] /reports/v2 rejects general object report target")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="CPU-only detect.v2/report.v2 ASGI contract smoke check.")
    parser.add_argument("--expected-mode", default=os.getenv("DETECT_V2_MODE", "fake"))
    parser.add_argument(
        "--skip-reports",
        action="store_true",
        help="Only check /detect/v2 health and fake detection contract; skip DB-backed /reports/v2 checks.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    app = import_app()
    client = ASGISmokeClient(app)
    try:
        check_health(client, args.expected_mode)
        detect_body = post_detect_v2(client)
        if not args.skip_reports:
            check_reports_v2(client, detect_body)
        print("detect.v2 contract smoke check passed.")
        return 0
    except SmokeFailure as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
