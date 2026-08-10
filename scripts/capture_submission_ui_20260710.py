#!/usr/bin/env python3
"""Capture reproducible Web/PWA and Admin screenshots for submission documents.

The user screen receives a privacy-safe still image through a synthetic camera
stream. The admin screen uses in-browser fixture API responses, so neither
capture is evidence of a live model, database, or agency integration.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import shutil
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path
from typing import Any

import websockets


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURE = (
    REPO_ROOT
    / "datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627"
    / "images/val/aihub513_val_1eb94fb8b0161479.jpg"
)
DEFAULT_OUTPUT_DIR = REPO_ROOT / "docs/submission/form_materials/assets"


class CaptureError(RuntimeError):
    """Raised when the local browser capture cannot be completed."""


def wait_for_http(url: str, timeout: float = 30.0) -> None:
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2.0) as response:
                if response.status < 500:
                    return
        except Exception as exc:  # noqa: BLE001 - polling preserves the last error
            last_error = exc
        time.sleep(0.25)
    raise CaptureError(f"Timed out waiting for {url}: {last_error}")


def chromium_binary() -> str:
    for candidate in ("chromium", "chromium-browser", "google-chrome"):
        resolved = shutil.which(candidate)
        if resolved:
            return resolved
    snap_path = Path("/snap/bin/chromium")
    if snap_path.exists():
        return str(snap_path)
    raise CaptureError("Chromium was not found")


def fixture_camera_script(fixture_path: Path) -> str:
    encoded = base64.b64encode(fixture_path.read_bytes()).decode("ascii")
    mime_type = "image/png" if fixture_path.suffix.lower() == ".png" else "image/jpeg"
    data_url = f"data:{mime_type};base64,{encoded}"
    return f"""
(() => {{
  const fixtureUrl = {json.dumps(data_url)};
  const originalMediaDevices = navigator.mediaDevices || {{}};
  const originalFetch = window.fetch.bind(window);

  window.fetch = async (input, init) => {{
    const rawUrl = input instanceof Request ? input.url : String(input);
    const url = new URL(rawUrl, window.location.href);
    if (url.pathname === '/api/field-session' && (!init || !init.method || init.method === 'GET')) {{
      return new Response(JSON.stringify({{ required: true, authenticated: true, actor_id: 'documentation.fixture' }}), {{
        status: 200,
        headers: {{ 'content-type': 'application/json' }}
      }});
    }}
    return originalFetch(input, init);
  }};

  async function createFixtureStream() {{
    const image = new Image();
    image.src = fixtureUrl;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.naturalWidth || 720;
    canvas.height = image.naturalHeight || 1280;
    const context = canvas.getContext('2d');
    if (!context || typeof canvas.captureStream !== 'function') {{
      throw new DOMException('Synthetic camera is unavailable', 'NotReadableError');
    }}
    const draw = () => {{
      context.fillStyle = '#000';
      context.fillRect(0, 0, canvas.width, canvas.height);
      context.drawImage(image, 0, 0, canvas.width, canvas.height);
    }};
    draw();
    window.setInterval(draw, 250);
    return canvas.captureStream(5);
  }}

  Object.defineProperty(navigator, 'mediaDevices', {{
    configurable: true,
    value: {{
      ...originalMediaDevices,
      getUserMedia: async (constraints) => {{
        if (constraints && constraints.video) return createFixtureStream();
        throw new DOMException('Only fixture video is enabled', 'NotFoundError');
      }}
    }}
  }});
}})();
"""


def admin_fixture_script() -> str:
    reports = [
        {
            "id": "a11d8c41-7da1-4c1b-84f9-0083010a0001",
            "status": "new",
            "class_id": 8,
            "class_name": "damaged_tactile_block",
            "confidence": 0.88,
            "bbox": {"x": 0.18, "y": 0.56, "width": 0.43, "height": 0.19},
            "captured_at": "2026-07-10T00:12:00.000Z",
            "source": "server",
            "gps": {"latitude": 37.56652, "longitude": 126.97802, "accuracy_m": 8},
            "heading": 94.0,
            "image_path": "/uploads/submission-fixture-01.jpg",
            "image_content_type": "image/jpeg",
            "metadata": {
                "schema_version": "report.v2",
                "model_key": "unified_walksafe",
                "source_model": "submission-fixture",
                "trigger": "auto",
                "auto_reported": True,
                "data_origin": "documentation_fixture",
                "performance_excluded": True,
            },
            "location_quality": "high",
            "review_flags": [],
            "duplicate_report_ids": [],
            "created_at": "2026-07-10T00:12:03.000Z",
            "updated_at": "2026-07-10T00:12:03.000Z",
        },
        {
            "id": "a11d8c41-7da1-4c1b-84f9-0083010a0002",
            "status": "reviewed",
            "class_id": 8,
            "class_name": "damaged_tactile_block",
            "confidence": 0.83,
            "bbox": {"x": 0.24, "y": 0.51, "width": 0.38, "height": 0.22},
            "captured_at": "2026-07-09T07:34:00.000Z",
            "source": "server",
            "gps": {"latitude": 37.56657, "longitude": 126.97808, "accuracy_m": 11},
            "heading": 182.0,
            "image_path": "/uploads/submission-fixture-02.jpg",
            "image_content_type": "image/jpeg",
            "metadata": {
                "schema_version": "report.v2",
                "model_key": "unified_walksafe",
                "source_model": "submission-fixture",
                "trigger": "voice",
                "auto_reported": False,
                "review_note": "현장 확인 필요",
                "data_origin": "documentation_fixture",
                "performance_excluded": True,
            },
            "location_quality": "high",
            "review_flags": [],
            "duplicate_report_ids": [],
            "created_at": "2026-07-09T07:34:02.000Z",
            "updated_at": "2026-07-09T08:01:00.000Z",
        },
        {
            "id": "a11d8c41-7da1-4c1b-84f9-0083010a0003",
            "status": "resolved",
            "class_id": 8,
            "class_name": "damaged_tactile_block",
            "confidence": 0.79,
            "bbox": {"x": 0.31, "y": 0.59, "width": 0.34, "height": 0.17},
            "captured_at": "2026-07-08T03:20:00.000Z",
            "source": "android",
            "gps": {"latitude": 37.56710, "longitude": 126.97901, "accuracy_m": 14},
            "heading": None,
            "image_path": "/uploads/submission-fixture-03.jpg",
            "image_content_type": "image/jpeg",
            "metadata": {
                "schema_version": "report.v2",
                "model_key": "custom_tactile",
                "source_model": "submission-fixture",
                "trigger": "voice",
                "auto_reported": False,
                "resolution_reason": "검수 완료",
                "data_origin": "documentation_fixture",
                "performance_excluded": True,
            },
            "location_quality": "medium",
            "review_flags": [],
            "duplicate_report_ids": [],
            "created_at": "2026-07-08T03:20:02.000Z",
            "updated_at": "2026-07-08T05:05:00.000Z",
        },
        {
            "id": "a11d8c41-7da1-4c1b-84f9-0083010a0004",
            "status": "new",
            "class_id": 8,
            "class_name": "damaged_tactile_block",
            "confidence": 0.74,
            "bbox": {"x": 0.15, "y": 0.48, "width": 0.51, "height": 0.25},
            "captured_at": "2026-07-07T06:10:00.000Z",
            "source": "fake",
            "gps": None,
            "heading": None,
            "image_path": "/uploads/submission-fixture-04.jpg",
            "image_content_type": "image/jpeg",
            "metadata": {
                "schema_version": "report.v2",
                "model_key": "unified_walksafe",
                "source_model": "fake-v2",
                "trigger": "auto",
                "auto_reported": True,
                "data_origin": "demo",
                "performance_excluded": True,
            },
            "location_quality": "missing",
            "review_flags": ["fake_source", "missing_location"],
            "duplicate_report_ids": [],
            "created_at": "2026-07-07T06:10:02.000Z",
            "updated_at": "2026-07-07T06:10:02.000Z",
        },
    ]
    summary = {
        "total": 4,
        "fake": 1,
        "non_fake": 3,
        "located": 3,
        "missing_location": 1,
        "bounds": {
            "min_latitude": 37.56652,
            "max_latitude": 37.56710,
            "min_longitude": 126.97802,
            "max_longitude": 126.97901,
        },
        "status_counts": {"new": 2, "reviewed": 1, "resolved": 1},
        "source_counts": {"fake": 1, "onnx": 0, "server": 2, "android": 1},
        "grid_size_degrees": 0.001,
        "top_clusters": [
            {
                "key": "37566:126978",
                "count": 2,
                "fake": 0,
                "non_fake": 2,
                "center_latitude": 37.566545,
                "center_longitude": 126.97805,
                "bounds": {
                    "min_latitude": 37.566,
                    "max_latitude": 37.567,
                    "min_longitude": 126.978,
                    "max_longitude": 126.979,
                },
                "status_counts": {"new": 1, "reviewed": 1, "resolved": 0},
                "source_counts": {"fake": 0, "onnx": 0, "server": 2, "android": 0},
            },
            {
                "key": "37567:126979",
                "count": 1,
                "fake": 0,
                "non_fake": 1,
                "center_latitude": 37.56710,
                "center_longitude": 126.97901,
                "bounds": {
                    "min_latitude": 37.567,
                    "max_latitude": 37.568,
                    "min_longitude": 126.979,
                    "max_longitude": 126.980,
                },
                "status_counts": {"new": 0, "reviewed": 0, "resolved": 1},
                "source_counts": {"fake": 0, "onnx": 0, "server": 0, "android": 1},
            },
        ],
        "note": "문서 캡처용 fixture 요약이며 운영 DB 통계가 아닙니다.",
    }
    reports_json = json.dumps(reports, ensure_ascii=False)
    summary_json = json.dumps(summary, ensure_ascii=False)
    return f"""
(() => {{
  const reports = {reports_json};
  const summary = {summary_json};
  const originalFetch = window.fetch.bind(window);
  const jsonResponse = (value) => new Response(JSON.stringify(value), {{
    status: 200,
    headers: {{ 'content-type': 'application/json' }}
  }});

  window.fetch = async (input, init) => {{
    const rawUrl = input instanceof Request ? input.url : String(input);
    const url = new URL(rawUrl, window.location.href);
    if (url.pathname === '/api/admin-session' && (!init || !init.method || init.method === 'GET')) {{
      return jsonResponse({{ required: true, authenticated: true, actor_id: 'documentation.fixture' }});
    }}
    if (url.pathname === '/api/reports/summary') return jsonResponse(summary);
    if (url.pathname === '/api/detect/v2/health') {{
      return jsonResponse({{ mode: 'fixture', status: 'documentation-only' }});
    }}
    if (url.pathname === '/api/reports' && (!init || !init.method || init.method === 'GET')) {{
      return jsonResponse(reports);
    }}
    return originalFetch(input, init);
  }};
}})();
"""


async def cdp_call(
    websocket: Any,
    method: str,
    params: dict[str, Any] | None,
    message_id: int,
) -> dict[str, Any]:
    await websocket.send(json.dumps({"id": message_id, "method": method, "params": params or {}}))
    while True:
        message = json.loads(await websocket.recv())
        if message.get("id") != message_id:
            continue
        if "error" in message:
            raise CaptureError(f"CDP {method} failed: {message['error']}")
        return message.get("result", {})


async def evaluate(websocket: Any, expression: str, message_id: int) -> Any:
    result = await cdp_call(
        websocket,
        "Runtime.evaluate",
        {"expression": expression, "awaitPromise": True, "returnByValue": True},
        message_id,
    )
    if "exceptionDetails" in result:
        raise CaptureError(f"Browser expression failed: {result['exceptionDetails']}")
    return result.get("result", {}).get("value")


async def wait_for_expression(
    websocket: Any,
    expression: str,
    message_id: int,
    timeout: float = 30.0,
) -> int:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if await evaluate(websocket, expression, message_id):
            return message_id + 1
        message_id += 1
        await asyncio.sleep(0.25)
    raise CaptureError(f"Timed out waiting for browser expression: {expression}")


async def capture_png(websocket: Any, output_path: Path, message_id: int) -> int:
    result = await cdp_call(
        websocket,
        "Page.captureScreenshot",
        {"format": "png", "fromSurface": True, "captureBeyondViewport": False},
        message_id,
    )
    output_path.write_bytes(base64.b64decode(result["data"]))
    return message_id + 1


async def capture_pages(debug_port: int, web_url: str, fixture_path: Path, output_dir: Path) -> None:
    with urllib.request.urlopen(f"http://127.0.0.1:{debug_port}/json", timeout=5.0) as response:
        targets = json.loads(response.read().decode("utf-8"))
    page = next((target for target in targets if target.get("type") == "page"), None)
    if not page:
        raise CaptureError("No Chromium page target found")

    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=32 * 1024 * 1024) as websocket:
        message_id = 1
        for method, params in (("Page.enable", {}), ("Runtime.enable", {}), ("Network.enable", {})):
            await cdp_call(websocket, method, params, message_id)
            message_id += 1

        await cdp_call(
            websocket,
            "Page.addScriptToEvaluateOnNewDocument",
            {"source": fixture_camera_script(fixture_path)},
            message_id,
        )
        message_id += 1

        async def capture_main(name: str, width: int, height: int, device_scale_factor: int, mobile: bool) -> None:
            nonlocal message_id
            await cdp_call(
                websocket,
                "Emulation.setDeviceMetricsOverride",
                {
                    "width": width,
                    "height": height,
                    "deviceScaleFactor": device_scale_factor,
                    "mobile": mobile,
                },
                message_id,
            )
            message_id += 1
            await cdp_call(websocket, "Page.navigate", {"url": web_url.rstrip("/") + "/"}, message_id)
            message_id += 1
            message_id = await wait_for_expression(
                websocket,
                "document.readyState === 'complete' && document.body && document.body.innerText.includes('카메라 권한 요청')",
                message_id,
            )
            await evaluate(
                websocket,
                "[...document.querySelectorAll('button')].find((b) => b.innerText.includes('카메라 권한 요청'))?.click()",
                message_id,
            )
            message_id += 1
            message_id = await wait_for_expression(
                websocket,
                "(() => { const v = document.querySelector('video'); return v && v.videoWidth > 0 && document.body.innerText.includes('unified-v2 데모 모드'); })()",
                message_id,
            )
            await asyncio.sleep(1.0)
            message_id = await capture_png(websocket, output_dir / name, message_id)

        await capture_main("web_main_mobile.png", 390, 844, 2, True)
        await capture_main("web_main_desktop.png", 1440, 900, 1, False)

        await cdp_call(
            websocket,
            "Page.addScriptToEvaluateOnNewDocument",
            {"source": admin_fixture_script()},
            message_id,
        )
        message_id += 1
        await cdp_call(
            websocket,
            "Emulation.setDeviceMetricsOverride",
            {"width": 1440, "height": 1000, "deviceScaleFactor": 1, "mobile": False},
            message_id,
        )
        message_id += 1
        await cdp_call(websocket, "Page.navigate", {"url": web_url.rstrip("/") + "/admin"}, message_id)
        message_id += 1
        message_id = await wait_for_expression(
            websocket,
            "document.readyState === 'complete' && document.body && document.body.innerText.includes('신고 4건') && document.body.innerText.includes('documentation-only')",
            message_id,
        )
        await asyncio.sleep(0.5)
        await capture_png(websocket, output_dir / "admin_desktop.png", message_id)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--web-url", default="http://127.0.0.1:3000")
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--debug-port", type=int, default=9222)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    fixture_path = args.fixture.resolve()
    output_dir = args.output_dir.resolve()
    if not fixture_path.is_file():
        raise CaptureError(f"Fixture image does not exist: {fixture_path}")
    output_dir.mkdir(parents=True, exist_ok=True)
    wait_for_http(args.web_url)

    with tempfile.TemporaryDirectory(prefix="walksafe-submission-chrome-") as profile_dir:
        process = subprocess.Popen(
            [
                chromium_binary(),
                "--headless=new",
                f"--remote-debugging-port={args.debug_port}",
                f"--user-data-dir={profile_dir}",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-networking",
                "--disable-component-extensions-with-background-pages",
                "--disable-extensions",
                "--disable-gpu",
                "about:blank",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        try:
            wait_for_http(f"http://127.0.0.1:{args.debug_port}/json")
            asyncio.run(capture_pages(args.debug_port, args.web_url, fixture_path, output_dir))
        finally:
            process.terminate()
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=2)

    for name in ("web_main_mobile.png", "web_main_desktop.png", "admin_desktop.png"):
        print(output_dir / name)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CaptureError as exc:
        print(f"[FAIL] {exc}")
        raise SystemExit(1)
