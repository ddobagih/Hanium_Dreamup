#!/usr/bin/env python3
"""Exercise the authenticated remote WalkSafe field UI with a fixture camera stream."""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import re
import tempfile
import time
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import websockets

from check_pwa_server_e2e import (
    CheckFailed,
    cdp_call,
    chrome_binary,
    evaluate,
    fixture_init_script,
    start_process,
    stop_process,
    wait_for_http,
)


REPO_ROOT = Path(__file__).resolve().parents[1]
PERSON_FIXTURE = (
    REPO_ROOT
    / "reports/walksafe_best_eval_20260708/inference_test/sample_images_flat/"
    "class00_person__02_person_coco_val_952b017821035f39.jpg"
)
DAMAGED_TACTILE_FIXTURE = (
    REPO_ROOT
    / "reports/walksafe_best_eval_20260708/inference_test/sample_images_flat/"
    "class08_damaged_tactile_block__02_damaged_tactile_block_aihub186_val_5406f2611850727e.jpg"
)
EXPECTED_SOURCE_MODEL = "walksafe_unified_yolo26n_epoch270_sha256_a38857e999e1"
SAFE_ACTOR_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
SAFE_SESSION_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


def read_dotenv_value(path: Path, key: str) -> str | None:
    if not path.is_file():
        return None
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        if name.strip() == key:
            return value.strip().strip("\"").strip("'")
    return None


def default_public_url() -> str | None:
    path = REPO_ROOT / "artifacts/cloudflare-field-test/current-public-url.txt"
    return path.read_text(encoding="utf-8").strip() if path.is_file() else None


def latest_telemetry_root() -> Path | None:
    roots = [
        path / "web-field-logs"
        for path in (REPO_ROOT / "artifacts/cloudflare-field-test").glob("20*")
        if (path / "web-field-logs").is_dir()
    ]
    return max(roots, key=lambda path: path.parent.stat().st_mtime) if roots else None


def parse_telemetry_session(raw_value: Any, expected_actor_id: str) -> tuple[str, str]:
    if not isinstance(raw_value, str):
        raise CheckFailed("Field telemetry sessionStorage value is missing")
    try:
        decoded = json.loads(raw_value)
    except json.JSONDecodeError as exc:
        raise CheckFailed("Field telemetry sessionStorage value is not valid JSON") from exc
    if not isinstance(decoded, dict):
        raise CheckFailed("Field telemetry sessionStorage value must be an object")
    actor_id = decoded.get("actor_id")
    session_id = decoded.get("session_id")
    if not isinstance(actor_id, str) or not SAFE_ACTOR_ID.fullmatch(actor_id):
        raise CheckFailed("Field telemetry session actor_id is invalid")
    if actor_id != expected_actor_id:
        raise CheckFailed("Field telemetry session actor_id does not match the authenticated actor")
    if not isinstance(session_id, str) or not SAFE_SESSION_ID.fullmatch(session_id):
        raise CheckFailed("Field telemetry session_id is invalid")
    return actor_id, session_id


def wait_for_telemetry(
    root: Path,
    session_id: str,
    scenario: str,
    timeout: float = 20.0,
) -> tuple[Path, list[dict[str, Any]]]:
    expected_class = "person" if scenario == "person-stop" else "damaged_tactile_block"

    def expected_state(payload: dict[str, Any]) -> bool:
        if scenario == "person-stop":
            return payload.get("risk_active") is True and "주의해서 피하세요" in payload.get("risk_text", "")
        return (
            payload.get("risk_active") is False
            and payload.get("report_status") == "sent"
            and "자동 신고" in payload.get("report_message", "")
        )

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        matches = list(root.glob(f"*/{session_id}.jsonl"))
        if matches:
            rows = [json.loads(line) for line in matches[0].read_text(encoding="utf-8").splitlines() if line]
            if any(
                row.get("payload", {}).get("camera_ready") is True
                and expected_state(row.get("payload", {}))
                and any(
                    item.get("class_name") == expected_class
                    and item.get("source_model") == EXPECTED_SOURCE_MODEL
                    for item in row.get("payload", {}).get("detections", [])
                    if isinstance(item, dict)
                )
                for row in rows
            ):
                return matches[0], rows
        time.sleep(0.5)
    raise CheckFailed(f"Field telemetry did not persist the {scenario} result for session {session_id}")


async def wait_for_expression(
    websocket: Any,
    expression: str,
    predicate: Any,
    *,
    message_id: int,
    timeout: float,
) -> tuple[Any, int]:
    deadline = time.monotonic() + timeout
    last_value: Any = None
    while time.monotonic() < deadline:
        last_value = await evaluate(websocket, expression, message_id=message_id)
        message_id += 1
        if predicate(last_value):
            return last_value, message_id
        await asyncio.sleep(0.5)
    raise CheckFailed(f"Browser condition timed out. Last value: {last_value}")


async def run_browser(
    debug_port: int,
    web_url: str,
    field_actor_id: str,
    field_account_token: str,
    fixture_path: Path,
    scenario: str,
    output_dir: Path,
    timeout: float,
) -> dict[str, Any]:
    def read_targets() -> bytes:
        with urllib.request.urlopen(f"http://127.0.0.1:{debug_port}/json", timeout=5) as response:
            return response.read()

    targets = json.loads((await asyncio.to_thread(read_targets)).decode("utf-8"))
    page = next((target for target in targets if target.get("type") == "page"), None)
    if page is None:
        raise CheckFailed(f"No debuggable page target found: {targets}")

    origin_parts = urlsplit(web_url)
    origin = f"{origin_parts.scheme}://{origin_parts.netloc}"
    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=32 * 1024 * 1024) as websocket:
        message_id = 1
        setup_calls = [
            ("Page.enable", {}),
            ("Runtime.enable", {}),
            ("Network.enable", {}),
            (
                "Emulation.setDeviceMetricsOverride",
                {"width": 390, "height": 844, "deviceScaleFactor": 2, "mobile": True},
            ),
            (
                "Browser.grantPermissions",
                {"origin": origin, "permissions": ["geolocation"]},
            ),
            (
                "Emulation.setGeolocationOverride",
                {"latitude": 37.5665, "longitude": 126.9780, "accuracy": 5},
            ),
            ("Page.addScriptToEvaluateOnNewDocument", {"source": fixture_init_script(fixture_path)}),
            ("Page.navigate", {"url": web_url}),
        ]
        for method, params in setup_calls:
            await cdp_call(websocket, method, params, message_id=message_id)
            message_id += 1

        _, message_id = await wait_for_expression(
            websocket,
            "document.body ? document.body.innerText : ''",
            lambda value: isinstance(value, str) and "현장 테스트 인증" in value,
            message_id=message_id,
            timeout=timeout,
        )
        login_expression = f"""
          fetch('/api/field-session', {{
            method: 'POST',
            headers: {{'content-type': 'application/json'}},
            body: JSON.stringify({{
              actor_id: {json.dumps(field_actor_id)},
              token: {json.dumps(field_account_token)}
            }}),
            cache: 'no-store'
          }}).then(async response => ({{status: response.status, body: await response.text()}}))
        """
        login = await evaluate(websocket, login_expression, message_id=message_id)
        message_id += 1
        if not isinstance(login, dict) or login.get("status") not in {200, 204}:
            raise CheckFailed(f"Field session login failed: {login}")

        await cdp_call(websocket, "Page.reload", {"ignoreCache": True}, message_id=message_id)
        message_id += 1
        _, message_id = await wait_for_expression(
            websocket,
            "document.body ? document.body.innerText : ''",
            lambda value: isinstance(value, str) and "카메라 권한 요청" in value and "unified-v2 서버 모드" in value,
            message_id=message_id,
            timeout=timeout,
        )

        processing_consent = await evaluate(
            websocket,
            """
              (() => {
                const button = [...document.querySelectorAll('button')]
                  .find(item => item.innerText.includes('전송 항목 확인·서버 탐지 동의'));
                if (!button || button.disabled) return false;
                button.click();
                return true;
              })()
            """,
            message_id=message_id,
        )
        message_id += 1
        if processing_consent is not True:
            raise CheckFailed("Server-v2 frame-processing consent button was not clickable")
        _, message_id = await wait_for_expression(
            websocket,
            "document.body ? document.body.innerText : ''",
            lambda value: isinstance(value, str) and "서버 탐지 처리: 프레임 전송 허용" in value,
            message_id=message_id,
            timeout=10,
        )

        if scenario == "damaged-auto-report":
            report_consent = await evaluate(
                websocket,
                """
                  (() => {
                    const button = [...document.querySelectorAll('button')]
                      .find(item => item.innerText.includes('저장 항목 확인·자동 신고 동의'));
                    if (!button || button.disabled) return false;
                    button.click();
                    return true;
                  })()
                """,
                message_id=message_id,
            )
            message_id += 1
            if report_consent is not True:
                raise CheckFailed("Server-v2 report-storage consent button was not clickable")
            _, message_id = await wait_for_expression(
                websocket,
                "document.body ? document.body.innerText : ''",
                lambda value: isinstance(value, str) and "신고 이미지 저장·자동 신고: 동의됨" in value,
                message_id=message_id,
                timeout=10,
            )

        telemetry_consent = await evaluate(
            websocket,
            """
              (() => {
                const button = [...document.querySelectorAll('button')]
                  .find(item => item.innerText.includes('고지 확인·수집 동의'));
                if (!button) return false;
                button.click();
                return true;
              })()
            """,
            message_id=message_id,
        )
        message_id += 1
        if telemetry_consent is not True:
            raise CheckFailed("Field telemetry consent button was not clickable")

        click_result = await evaluate(
            websocket,
            """
              (() => {
                const button = [...document.querySelectorAll('button')]
                  .find(item => item.innerText.includes('카메라 권한 요청'));
                if (!button) return false;
                button.click();
                return true;
              })()
            """,
            message_id=message_id,
        )
        message_id += 1
        if click_result is not True:
            raise CheckFailed("Camera permission button was not clickable")

        state_expression = """
          (() => {
            const video = document.querySelector('video');
            return {
              text: document.body ? document.body.innerText : '',
              video: video ? {
                width: video.videoWidth,
                height: video.videoHeight,
                readyState: video.readyState,
                paused: video.paused,
                hasSrcObject: Boolean(video.srcObject)
              } : null,
              boxes: [...document.querySelectorAll('.detection-box')].map(node => node.textContent),
              telemetrySession: sessionStorage.getItem('walksafe-field-session-id'),
              devOverlayPresent: Boolean(document.querySelector('nextjs-portal'))
            };
          })()
        """
        expected_label = "보행자" if scenario == "person-stop" else "파손 점자블록"

        def is_expected_state(value: Any) -> bool:
            if not isinstance(value, dict):
                return False
            try:
                parse_telemetry_session(value.get("telemetrySession"), field_actor_id)
            except CheckFailed:
                return False
            return (
                value.get("video", {}).get("readyState", 0) >= 2
                and value.get("video", {}).get("hasSrcObject") is True
                and "unified-v2 서버 모드" in value.get("text", "")
                and (
                    (scenario == "person-stop" and "주의해서 피하세요" in value.get("text", ""))
                    or (
                        scenario == "damaged-auto-report"
                        and "자동 신고 완료" in value.get("text", "")
                        and "멈추세요" not in value.get("text", "")
                    )
                )
                and any(expected_label in label for label in value.get("boxes", []))
            )

        state, message_id = await wait_for_expression(
            websocket,
            state_expression,
            is_expected_state,
            message_id=message_id,
            timeout=timeout,
        )
        telemetry_actor_id, telemetry_session_id = parse_telemetry_session(
            state.pop("telemetrySession", None), field_actor_id
        )
        state["telemetryActorId"] = telemetry_actor_id
        state["sessionId"] = telemetry_session_id
        if state.get("devOverlayPresent") is True:
            raise CheckFailed("Next.js development overlay is present; use the production field server")

        manual_capture: str | None = None
        if scenario == "person-stop":
            consent_clicked = await evaluate(
                websocket,
                """
                  (() => {
                    const button = [...document.querySelectorAll('button')]
                      .find(item => item.innerText.includes('동의 후 활성화'));
                    if (!button) return false;
                    button.click();
                    return true;
                  })()
                """,
                message_id=message_id,
            )
            message_id += 1
            if consent_clicked is not True:
                raise CheckFailed("Explicit test-capture consent control was not available")
            _, message_id = await wait_for_expression(
                websocket,
                "[...document.querySelectorAll('button')].some(item => item.innerText.includes('맞음 저장'))",
                lambda value: value is True,
                message_id=message_id,
                timeout=10,
            )
            capture_clicked = await evaluate(
                websocket,
                """
                  (() => {
                    const button = [...document.querySelectorAll('button')]
                      .find(item => item.innerText.includes('맞음 저장'));
                    if (!button || button.disabled) return false;
                    button.click();
                    return true;
                  })()
                """,
                message_id=message_id,
            )
            message_id += 1
            if capture_clicked is not True:
                raise CheckFailed("Manual correct-verdict capture was not clickable")
            manual_capture, message_id = await wait_for_expression(
                websocket,
                """
                  (() => [...document.querySelectorAll('.test-capture-panel small')]
                    .map(item => item.textContent || '')
                    .find(text => text.startsWith('저장됨')) || '')()
                """,
                lambda value: isinstance(value, str) and value.startswith("저장됨"),
                message_id=message_id,
                timeout=30,
            )

        await asyncio.sleep(1.0)
        screenshot = await cdp_call(
            websocket,
            "Page.captureScreenshot",
            {"format": "png", "fromSurface": True, "captureBeyondViewport": False},
            message_id=message_id,
        )
        (output_dir / f"mobile-field-ui-{scenario}.png").write_bytes(base64.b64decode(screenshot["data"]))
        return {
            "state": state,
            "expected_source_model": EXPECTED_SOURCE_MODEL,
            "manual_capture": manual_capture,
        }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Remote WalkSafe mobile browser field-gateway E2E")
    parser.add_argument("--web-url", default=default_public_url())
    parser.add_argument(
        "--scenario",
        choices=("person-stop", "damaged-auto-report"),
        default="person-stop",
    )
    parser.add_argument("--fixture", type=Path, default=None)
    parser.add_argument("--chrome-bin")
    parser.add_argument("--chrome-debug-port", type=int, default=9333)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--telemetry-root", type=Path, default=None)
    return parser.parse_args()


def configured_field_account() -> tuple[str, str]:
    dotenv = REPO_ROOT / "apps/web/.env.local"
    actor_id = os.environ.get("WALKSAFE_FIELD_ACTOR_ID") or read_dotenv_value(dotenv, "WALKSAFE_FIELD_ACTOR_ID")
    raw_accounts = os.environ.get("WALKSAFE_FIELD_ACCOUNTS_JSON") or read_dotenv_value(
        dotenv, "WALKSAFE_FIELD_ACCOUNTS_JSON"
    )
    if not actor_id or not raw_accounts:
        raise CheckFailed("WALKSAFE_FIELD_ACTOR_ID and WALKSAFE_FIELD_ACCOUNTS_JSON are required")
    try:
        accounts = json.loads(raw_accounts)
    except json.JSONDecodeError as exc:
        raise CheckFailed("WALKSAFE_FIELD_ACCOUNTS_JSON is invalid JSON") from exc
    if not isinstance(accounts, list):
        raise CheckFailed("WALKSAFE_FIELD_ACCOUNTS_JSON must be an array")
    matches = [
        account
        for account in accounts
        if isinstance(account, dict) and account.get("actor_id") == actor_id
    ]
    if len(matches) != 1:
        raise CheckFailed("WALKSAFE_FIELD_ACTOR_ID must match exactly one field account")
    token = matches[0].get("token")
    if not isinstance(token, str) or len(token.strip()) < 24:
        raise CheckFailed("the selected field account token is invalid")
    return actor_id, token.strip()


def main() -> int:
    args = parse_args()
    if not args.web_url:
        raise CheckFailed("--web-url is required until current-public-url.txt exists")
    if args.fixture is None:
        args.fixture = PERSON_FIXTURE if args.scenario == "person-stop" else DAMAGED_TACTILE_FIXTURE
    if not args.fixture.is_file():
        raise CheckFailed(f"Fixture image not found: {args.fixture}")
    field_actor_id, field_account_token = configured_field_account()

    output_dir = REPO_ROOT / "artifacts/cloudflare-field-test" / f"browser-e2e-{datetime.now():%Y%m%d-%H%M%S}"
    output_dir.mkdir(parents=True, exist_ok=False)
    profile_dir = Path(tempfile.mkdtemp(prefix="walksafe-remote-browser-"))
    chrome = None
    try:
        chrome = start_process(
            [
                chrome_binary(args.chrome_bin),
                "--headless=new",
                f"--remote-debugging-port={args.chrome_debug_port}",
                f"--user-data-dir={profile_dir}",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-networking",
                "--disable-extensions",
                "--disable-dev-shm-usage",
                "about:blank",
            ],
            cwd=REPO_ROOT,
            env=os.environ.copy(),
            log_path=output_dir / "chromium.log",
        )
        wait_for_http(f"http://127.0.0.1:{args.chrome_debug_port}/json", timeout=30)
        result = asyncio.run(
            run_browser(
                args.chrome_debug_port,
                args.web_url.rstrip("/"),
                field_actor_id,
                field_account_token,
                args.fixture.resolve(),
                args.scenario,
                output_dir,
                args.timeout,
            )
        )
        telemetry_root = args.telemetry_root or latest_telemetry_root()
        if telemetry_root is None:
            raise CheckFailed("No active web-field-logs directory was found")
        telemetry_path, telemetry_rows = wait_for_telemetry(
            telemetry_root.resolve(), result["state"]["sessionId"], args.scenario
        )
        summary = {
            **result,
            "field_actor_id": field_actor_id,
            "web_url": args.web_url,
            "scenario": args.scenario,
            "fixture": str(args.fixture.resolve()),
            "telemetry_path": str(telemetry_path),
            "telemetry_rows": len(telemetry_rows),
            "screenshot": str(output_dir / f"mobile-field-ui-{args.scenario}.png"),
        }
        (output_dir / "result.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print("Remote mobile browser field E2E passed.")
        print(f"url={args.web_url}")
        print(f"video={result['state']['video']} boxes={result['state']['boxes']}")
        if result["manual_capture"]:
            print(f"manual_capture={result['manual_capture']}")
        print(f"telemetry_rows={len(telemetry_rows)} telemetry={telemetry_path}")
        print(f"artifacts={output_dir}")
        return 0
    finally:
        stop_process(chrome)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CheckFailed as exc:
        print(f"[FAIL] {exc}", file=__import__("sys").stderr)
        raise SystemExit(1)
