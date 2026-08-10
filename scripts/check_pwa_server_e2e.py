#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

try:
    import websockets
except ImportError as exc:  # pragma: no cover - environment guard
    raise SystemExit("websockets is required. Run this with the project .venv after installing backend requirements.") from exc


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL_PATH = REPO_ROOT / "runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt"
DEFAULT_FIXTURE_PATH = (
    REPO_ROOT
    / "datasets/walksafe_kr_v2/images/val/aihub513_tactile_2_09_1_1_1_2_20210820_0000315742.jpg"
)
DEFAULT_MODEL_VERSION = "walksafe-kr-tactile-v2-full-20260514-best-02a6be87"
REPORT_BUTTON_TEXT = "현재 위험 신고"
PWA_SERVER_E2E_FIELD_TOKEN = "pwa-server-e2e-field-token-20260711"


class CheckFailed(RuntimeError):
    pass


def http_json(url: str, *, method: str = "GET", timeout: float = 2.0) -> Any:
    request = urllib.request.Request(url, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        data = response.read()
    return json.loads(data.decode("utf-8")) if data else None


def wait_for_http(url: str, *, timeout: float = 30.0) -> None:
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2.0) as response:
                if response.status < 500:
                    return
        except Exception as exc:  # noqa: BLE001 - status polling
            last_error = exc
        time.sleep(0.5)
    raise CheckFailed(f"Timed out waiting for {url}: {last_error}")


def wait_for_detect_ready(base_url: str, *, timeout: float = 90.0) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    last_payload: Any = None
    while time.monotonic() < deadline:
        try:
            payload = http_json(f"{base_url}/detect/health", timeout=10.0)
            last_payload = payload
            if isinstance(payload, dict) and payload.get("model_status") == "ready":
                return payload
        except Exception as exc:  # noqa: BLE001 - status polling
            last_payload = repr(exc)
        time.sleep(1.0)
    raise CheckFailed(f"/detect/health did not become ready: {last_payload}")


def run_command(command: list[str], *, cwd: Path, env: dict[str, str], log_path: Path) -> None:
    with log_path.open("w", encoding="utf-8") as log_file:
        result = subprocess.run(
            command,
            cwd=str(cwd),
            env=env,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )
    if result.returncode != 0:
        raise CheckFailed(f"Command failed ({' '.join(command)}); see {log_path}")


def start_process(command: list[str], *, cwd: Path, env: dict[str, str], log_path: Path) -> subprocess.Popen[str]:
    log_file = log_path.open("w", encoding="utf-8")
    return subprocess.Popen(
        command,
        cwd=str(cwd),
        env=env,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )


def stop_process(process: subprocess.Popen[str] | None) -> None:
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    except Exception:  # noqa: BLE001 - best effort cleanup
        pass
    try:
        process.wait(timeout=8)
    except subprocess.TimeoutExpired:
        pass
    deadline = time.monotonic() + 1.0
    while time.monotonic() < deadline:
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            return
        except Exception:  # noqa: BLE001 - best effort cleanup
            break
        time.sleep(0.05)
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        return
    except Exception:  # noqa: BLE001 - best effort cleanup
        return
    try:
        process.wait(timeout=2)
    except Exception:  # noqa: BLE001 - best effort cleanup
        pass


def chrome_binary(explicit_path: str | None) -> str:
    if explicit_path:
        return explicit_path
    for candidate in ("chromium", "chromium-browser", "google-chrome"):
        path = shutil.which(candidate)
        if path:
            return path
    snap_chromium = Path("/snap/bin/chromium")
    if snap_chromium.exists():
        return str(snap_chromium)
    raise CheckFailed("Chromium/Chrome binary not found")


def fixture_init_script(fixture_path: Path) -> str:
    encoded = base64.b64encode(fixture_path.read_bytes()).decode("ascii")
    mime_type = "image/png" if fixture_path.suffix.lower() == ".png" else "image/jpeg"
    data_url = f"data:{mime_type};base64,{encoded}"
    return f"""
(() => {{
  const fixtureUrl = {json.dumps(data_url)};
  const originalMediaDevices = navigator.mediaDevices || {{}};

  async function createFixtureStream() {{
    const image = new Image();
    image.src = fixtureUrl;
    if (image.decode) {{
      await image.decode();
    }} else {{
      await new Promise((resolve, reject) => {{
        image.onload = resolve;
        image.onerror = reject;
      }});
    }}

    const canvas = document.createElement("canvas");
    canvas.width = image.naturalWidth || 720;
    canvas.height = image.naturalHeight || 1280;
    const context = canvas.getContext("2d");
    if (!context) {{
      throw new DOMException("Canvas is unavailable", "NotReadableError");
    }}

    const draw = () => {{
      context.fillStyle = "#000";
      context.fillRect(0, 0, canvas.width, canvas.height);
      context.drawImage(image, 0, 0, canvas.width, canvas.height);
    }};
    draw();
    window.setInterval(draw, 250);

    if (typeof canvas.captureStream !== "function") {{
      throw new DOMException("canvas.captureStream is unavailable", "NotReadableError");
    }}
    return canvas.captureStream(5);
  }}

  Object.defineProperty(navigator, "mediaDevices", {{
    configurable: true,
    value: {{
      ...originalMediaDevices,
      getUserMedia: async (constraints) => {{
        if (constraints && constraints.video) {{
          return createFixtureStream();
        }}
        throw new DOMException("Only fixture video is available in this E2E check", "NotFoundError");
      }}
    }}
  }});
}})();
"""


async def cdp_call(websocket: Any, method: str, params: dict[str, Any] | None = None, *, message_id: int) -> Any:
    await websocket.send(json.dumps({"id": message_id, "method": method, "params": params or {}}))
    while True:
        message = json.loads(await websocket.recv())
        if message.get("id") != message_id:
            continue
        if "error" in message:
            raise CheckFailed(f"CDP {method} failed: {message['error']}")
        return message.get("result")


async def evaluate(websocket: Any, expression: str, *, message_id: int) -> Any:
    result = await cdp_call(
        websocket,
        "Runtime.evaluate",
        {
            "expression": expression,
            "awaitPromise": True,
            "returnByValue": True,
        },
        message_id=message_id,
    )
    remote = result.get("result", {})
    if "exceptionDetails" in result:
        raise CheckFailed(f"Runtime exception: {result['exceptionDetails']}")
    return remote.get("value")


STATE_EXPRESSION = """
(() => {
  const reportButton = [...document.querySelectorAll('button')]
    .find((button) => button.innerText.includes('현재 위험 신고') || button.innerText.includes('다시 신고') || button.innerText.includes('전송 중'));
  const video = document.querySelector('video');
  return {
    text: document.body ? document.body.innerText : '',
    reportDisabled: reportButton ? reportButton.disabled : null,
    reportText: reportButton ? reportButton.innerText : null,
    video: video ? {
      width: video.videoWidth,
      height: video.videoHeight,
      paused: video.paused,
      readyState: video.readyState,
      hasSrcObject: Boolean(video.srcObject)
    } : null
  };
})()
"""

CLICK_REPORT_EXPRESSION = f"""
(() => {{
  const reportButton = [...document.querySelectorAll('button')]
    .find((button) => button.innerText.includes({json.dumps(REPORT_BUTTON_TEXT)}) || button.innerText.includes('다시 신고'));
  if (!reportButton) {{
    return {{ clicked: false, reason: 'button_not_found' }};
  }}
  if (reportButton.disabled) {{
    return {{ clicked: false, reason: 'button_disabled', text: reportButton.innerText }};
  }}
  reportButton.click();
  return {{ clicked: true, text: reportButton.innerText }};
}})()
"""


async def run_browser_check(args: argparse.Namespace, web_url: str) -> dict[str, Any]:
    browser_url = f"http://127.0.0.1:{args.chrome_debug_port}"
    targets = http_json(f"{browser_url}/json", timeout=5.0)
    page = next((target for target in targets if target.get("type") == "page"), None)
    if page is None:
        raise CheckFailed(f"No debuggable page target found: {targets}")
    websocket_url = page["webSocketDebuggerUrl"]

    async with websockets.connect(websocket_url, max_size=16 * 1024 * 1024) as websocket:
        message_id = 1
        for method, params in [
            ("Page.enable", {}),
            ("Runtime.enable", {}),
            ("Network.enable", {}),
            ("Page.addScriptToEvaluateOnNewDocument", {"source": fixture_init_script(args.fixture_path)}),
            ("Page.navigate", {"url": web_url}),
        ]:
            await cdp_call(websocket, method, params, message_id=message_id)
            message_id += 1

        origin_ready_deadline = time.monotonic() + 30.0
        while time.monotonic() < origin_ready_deadline:
            try:
                origin = await evaluate(websocket, "window.location.origin", message_id=message_id)
                message_id += 1
                if origin == web_url:
                    break
            except CheckFailed:
                pass
            await asyncio.sleep(0.25)
        else:
            raise CheckFailed("PWA page did not reach its same-origin gateway before authentication")

        login_result = await evaluate(
            websocket,
            """
            (async () => {
              const response = await fetch('/api/field-session', {
                method: 'POST',
                headers: {'content-type': 'application/json', 'x-real-ip': '127.0.0.1'},
                body: JSON.stringify({token: %s})
              });
              return {status: response.status, text: await response.text()};
            })()
            """ % json.dumps(PWA_SERVER_E2E_FIELD_TOKEN),
            message_id=message_id,
        )
        message_id += 1
        if not isinstance(login_result, dict) or login_result.get("status") != 204:
            raise CheckFailed(f"Same-origin field gateway login failed: {login_result}")
        await cdp_call(websocket, "Page.reload", {}, message_id=message_id)
        message_id += 1

        deadline = time.monotonic() + args.browser_timeout
        last_state: dict[str, Any] | None = None
        while time.monotonic() < deadline:
            state = await evaluate(websocket, STATE_EXPRESSION, message_id=message_id)
            message_id += 1
            last_state = state
            text = state.get("text", "") if isinstance(state, dict) else ""
            if state and state.get("reportDisabled") is False and "점자블록 파손" in text:
                break
            await asyncio.sleep(1.0)
        else:
            raise CheckFailed(f"Detection did not enable report button. Last state: {last_state}")

        click_result = await evaluate(websocket, CLICK_REPORT_EXPRESSION, message_id=message_id)
        message_id += 1
        if not isinstance(click_result, dict) or not click_result.get("clicked"):
            raise CheckFailed(f"Report click failed: {click_result}")

        deadline = time.monotonic() + args.report_timeout
        while time.monotonic() < deadline:
            state = await evaluate(websocket, STATE_EXPRESSION, message_id=message_id)
            message_id += 1
            last_state = state
            text = state.get("text", "") if isinstance(state, dict) else ""
            if "신고 저장 완료" in text:
                return state
            await asyncio.sleep(0.5)

        raise CheckFailed(f"Report was not saved from browser. Last state: {last_state}")


def latest_server_report(api_base_url: str) -> dict[str, Any]:
    reports = http_json(f"{api_base_url}/reports?source=server&limit=5", timeout=10.0)
    if not isinstance(reports, list) or not reports:
        raise CheckFailed("No server-source report found after browser E2E")
    report = reports[0]
    if report.get("source") != "server" or report.get("metadata", {}).get("source") != "server":
        raise CheckFailed(f"Latest report source mismatch: {report}")
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="PWA server-mode browser E2E with a known-positive fixture frame.")
    parser.add_argument("--model-path", type=Path, default=DEFAULT_MODEL_PATH)
    parser.add_argument("--model-version", default=DEFAULT_MODEL_VERSION)
    parser.add_argument("--fixture-path", type=Path, default=DEFAULT_FIXTURE_PATH)
    parser.add_argument("--backend-port", type=int, default=8000)
    parser.add_argument("--web-port", type=int, default=3000)
    parser.add_argument("--chrome-debug-port", type=int, default=9222)
    parser.add_argument("--chrome-bin", default=None)
    parser.add_argument("--web-mode", choices=("dev", "start"), default="dev", help="Run the PWA with next dev or next start.")
    parser.add_argument(
        "--skip-web-build",
        action="store_true",
        help="Skip npm run build before --web-mode start. Use only when .next was built for the same server-mode profile.",
    )
    parser.add_argument("--browser-timeout", type=float, default=90.0)
    parser.add_argument("--report-timeout", type=float, default=30.0)
    parser.add_argument("--keep-open", action="store_true", help="Leave spawned servers/browser running for debugging.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    args.model_path = args.model_path.resolve()
    args.fixture_path = args.fixture_path.resolve()
    if not args.model_path.is_file():
        raise CheckFailed(f"Model artifact not found: {args.model_path}")
    if not args.fixture_path.is_file():
        raise CheckFailed(f"Fixture image not found: {args.fixture_path}")

    api_base_url = f"http://127.0.0.1:{args.backend_port}"
    web_url = f"http://127.0.0.1:{args.web_port}"

    temp_dir = Path(tempfile.mkdtemp(prefix="walksafe-pwa-e2e-"))
    backend_process: subprocess.Popen[str] | None = None
    web_process: subprocess.Popen[str] | None = None
    chrome_process: subprocess.Popen[str] | None = None

    try:
        env = os.environ.copy()
        env.update(
            {
                "PYTHONPATH": str(REPO_ROOT),
                "MODEL_ARTIFACT_PATH": str(args.model_path),
                "MODEL_VERSION": args.model_version,
                "WALKSAFE_ENVIRONMENT": "test",
                "WALKSAFE_FIELD_TEST_SECURITY_ENABLED": "false",
                "WALKSAFE_ALLOW_INSECURE_LOCAL_DEV": "true",
            }
        )
        backend_process = start_process(
            [sys.executable, "-m", "uvicorn", "backend.app.main:app", "--host", "127.0.0.1", "--port", str(args.backend_port)],
            cwd=REPO_ROOT,
            env=env,
            log_path=temp_dir / "backend.log",
        )
        wait_for_http(f"{api_base_url}/health", timeout=60.0)
        health = wait_for_detect_ready(api_base_url)

        web_env = os.environ.copy()
        rate_limit_dir = temp_dir / "gateway-rate-limits"
        rate_limit_dir.mkdir(mode=0o700)
        web_env.update(
            {
                "NEXT_PUBLIC_DETECTOR_MODE": "server",
                "BACKEND_API_BASE_URL": api_base_url,
                "WALKSAFE_FIELD_TEST_TOKEN": PWA_SERVER_E2E_FIELD_TOKEN,
                "WALKSAFE_FIELD_ACCOUNTS_JSON": "",
                "WALKSAFE_ALLOW_INSECURE_LOCAL_DEV": "false",
                "WALKSAFE_GATEWAY_RATE_LIMIT_DIR": str(rate_limit_dir),
                "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER": "x-real-ip",
                "WALKSAFE_ENVIRONMENT": "test",
                "WALKSAFE_WEB_REPLICAS": "1",
                "WALKSAFE_WEB_PROCESS_LOCK_PATH": str(temp_dir / "web-process.lock"),
            }
        )
        web_root = REPO_ROOT / "apps/web"
        if args.web_mode == "start" and not args.skip_web_build:
            run_command(
                ["npm", "run", "build"],
                cwd=web_root,
                env=web_env,
                log_path=temp_dir / "web-build.log",
            )

        web_command = (
            ["npm", "run", "dev", "--", "--hostname", "127.0.0.1", "--port", str(args.web_port)]
            if args.web_mode == "dev"
            else ["npm", "run", "start", "--", "--hostname", "127.0.0.1", "--port", str(args.web_port)]
        )
        web_process = start_process(
            web_command,
            cwd=web_root,
            env=web_env,
            log_path=temp_dir / "web.log",
        )
        wait_for_http(web_url, timeout=60.0)

        profile_dir = temp_dir / "chrome-profile"
        chrome_process = start_process(
            [
                chrome_binary(args.chrome_bin),
                "--headless=new",
                f"--remote-debugging-port={args.chrome_debug_port}",
                f"--user-data-dir={profile_dir}",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-networking",
                "--disable-component-extensions-with-background-pages",
                "--disable-extensions",
                "--disable-gpu",
                "about:blank",
            ],
            cwd=REPO_ROOT,
            env=os.environ.copy(),
            log_path=temp_dir / "chrome.log",
        )
        wait_for_http(f"http://127.0.0.1:{args.chrome_debug_port}/json", timeout=30.0)

        browser_state = asyncio.run(run_browser_check(args, web_url))
        report = latest_server_report(api_base_url)

        print("PWA server-mode browser E2E passed.")
        print(f"web_mode={args.web_mode}")
        print(f"model_status={health.get('model_status')} model_version={health.get('model_version')}")
        print(
            "browser_video="
            f"{browser_state.get('video')} report_text={browser_state.get('reportText')!r}"
        )
        print(
            "report="
            f"id={report.get('id')} source={report.get('source')} "
            f"class={report.get('class_name')} confidence={report.get('confidence')}"
        )
        print(f"logs={temp_dir}")
        return 0
    finally:
        if not args.keep_open:
            stop_process(chrome_process)
            stop_process(web_process)
            stop_process(backend_process)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CheckFailed as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
