#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Any


DEFAULT_VOICE_API_BASE = "http://127.0.0.1:9001"
SERVER_START_HINT = (
    "Start the local voice server with:\n"
    "  source .venv-voice/bin/activate\n"
    "  VOICE_SERVICE_TOKEN='<dedicated-24+-character-token>' PYTHONPATH=. "
    "python -m uvicorn voice.server:app --host 127.0.0.1 --port 9001"
)

INTENT_CASES = [
    ("신고해", "create_report"),
    ("음성 켜", "voice_on"),
    ("음성 꺼", "voice_off"),
    ("다시 말해줘", "repeat_last"),
    ("목적지 서울역으로 설정해", "set_destination"),
    ("길 안내 시작해", "start_navigation"),
    ("재탐색해줘", "reroute_navigation"),
    ("길 안내 중지", "stop_navigation"),
    ("지금 어디야", "get_current_location"),
]


class CheckFailure(Exception):
    pass


@dataclass(frozen=True)
class HttpResult:
    status: int
    payload: Any
    raw_body: str


def normalize_base_url(value: str) -> str:
    base_url = value.strip().rstrip("/")
    if not base_url:
        raise CheckFailure("VOICE_API_BASE is empty.")
    if not base_url.startswith(("http://", "https://")):
        raise CheckFailure(f"VOICE_API_BASE must start with http:// or https://, got: {value!r}")
    return base_url


def parse_json_body(raw_body: str) -> Any:
    if not raw_body:
        return None
    try:
        return json.loads(raw_body)
    except json.JSONDecodeError:
        return None


def request(
    method: str,
    url: str,
    *,
    body: bytes | None = None,
    headers: dict[str, str] | None = None,
    timeout: float,
) -> HttpResult:
    service_token = os.getenv("VOICE_SERVICE_TOKEN", "").strip()
    if len(service_token) < 24:
        raise CheckFailure("VOICE_SERVICE_TOKEN must contain at least 24 characters for the Voice contract check.")
    request_headers = {"Accept": "application/json", "User-Agent": "walksafe-voice-contract-smoke/1.0"}
    if headers:
        request_headers.update(headers)
    request_headers["x-walksafe-voice-service-token"] = service_token
    req = urllib.request.Request(url, data=body, headers=request_headers, method=method)

    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            return HttpResult(response.status, parse_json_body(raw), raw)
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        return HttpResult(exc.code, parse_json_body(raw), raw)
    except (urllib.error.URLError, TimeoutError, socket.timeout) as exc:
        raise CheckFailure(
            f"Voice API is not reachable at {url}.\n{SERVER_START_HINT}\n"
            f"Set VOICE_API_BASE or pass --base-url if the server is on another address.\n"
            f"Underlying error: {exc}"
        ) from exc


def request_json(method: str, url: str, payload: dict[str, Any], *, timeout: float) -> HttpResult:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    return request(
        method,
        url,
        body=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        timeout=timeout,
    )


def multipart_upload(
    url: str,
    *,
    filename: str,
    content_type: str,
    content: bytes,
    timeout: float,
) -> HttpResult:
    boundary = f"----voice-contract-{uuid.uuid4().hex}"
    body = b"".join(
        [
            f"--{boundary}\r\n".encode("ascii"),
            f'Content-Disposition: form-data; name="audio"; filename="{filename}"\r\n'.encode("utf-8"),
            f"Content-Type: {content_type}\r\n\r\n".encode("ascii"),
            content,
            b"\r\n",
            f"--{boundary}--\r\n".encode("ascii"),
        ]
    )
    return request(
        "POST",
        url,
        body=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        timeout=timeout,
    )


def require_record(payload: Any, context: str) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise CheckFailure(f"{context} returned non-JSON or non-object response: {payload!r}")
    return payload


def detail_code(payload: Any) -> str | None:
    if not isinstance(payload, dict):
        return None
    detail = payload.get("detail")
    if isinstance(detail, dict):
        code = detail.get("code")
        return code if isinstance(code, str) else None
    code = payload.get("code")
    return code if isinstance(code, str) else None


def check_health(base_url: str, timeout: float) -> None:
    result = request("GET", f"{base_url}/health", timeout=timeout)
    if result.status != 200:
        raise CheckFailure(f"/health returned HTTP {result.status}: {result.raw_body}")
    payload = require_record(result.payload, "/health")
    if payload.get("status") != "ok" or payload.get("server") != "voice":
        raise CheckFailure(f"/health returned unexpected payload: {payload!r}")
    print(f"[PASS] /health status=ok server=voice stt_model={payload.get('stt_model')}")


def check_intents(base_url: str, timeout: float) -> None:
    for transcript, expected_intent in INTENT_CASES:
        result = request_json("POST", f"{base_url}/speech/intent", {"transcript": transcript}, timeout=timeout)
        if result.status != 200:
            raise CheckFailure(f"/speech/intent for {transcript!r} returned HTTP {result.status}: {result.raw_body}")
        payload = require_record(result.payload, f"/speech/intent for {transcript!r}")
        actual_intent = payload.get("intent")
        if actual_intent != expected_intent:
            raise CheckFailure(
                f"/speech/intent for {transcript!r} returned intent={actual_intent!r}, "
                f"expected {expected_intent!r}. Payload: {payload!r}"
            )
        if not isinstance(payload.get("slots"), dict):
            raise CheckFailure(f"/speech/intent for {transcript!r} returned invalid slots: {payload!r}")
        if payload.get("action") != "execute" or payload.get("should_execute") is not True:
            raise CheckFailure(f"/speech/intent for {transcript!r} should be executable: {payload!r}")
        if expected_intent == "set_destination" and payload["slots"].get("destination") != "서울역":
            raise CheckFailure(f"/speech/intent did not extract destination='서울역': {payload!r}")
        print(f"[PASS] /speech/intent transcript={transcript!r} intent={expected_intent}")

    unknown = request_json("POST", f"{base_url}/speech/intent", {"transcript": "오늘 날씨 알려줘"}, timeout=timeout)
    if unknown.status != 200:
        raise CheckFailure(f"/speech/intent unknown fallback returned HTTP {unknown.status}: {unknown.raw_body}")
    payload = require_record(unknown.payload, "/speech/intent unknown fallback")
    if (
        payload.get("intent") != "unknown"
        or payload.get("action") != "reprompt"
        or payload.get("should_execute") is not False
        or payload.get("reason") != "unknown_intent"
        or not isinstance(payload.get("prompt"), str)
    ):
        raise CheckFailure(f"/speech/intent unknown fallback did not return reprompt policy: {payload!r}")
    print("[PASS] /speech/intent unknown fallback returns reprompt policy")


def check_voice_metadata_endpoints(base_url: str, timeout: float) -> None:
    intents = request("GET", f"{base_url}/speech/intents", timeout=timeout)
    if intents.status != 200:
        raise CheckFailure(f"/speech/intents returned HTTP {intents.status}: {intents.raw_body}")
    intent_payload = require_record(intents.payload, "/speech/intents")
    intent_names = {
        item.get("name")
        for item in intent_payload.get("intents", [])
        if isinstance(item, dict)
    }
    expected = {"create_report", "set_destination", "reroute_navigation", "stop_navigation", "unknown"}
    if not expected <= intent_names:
        raise CheckFailure(f"/speech/intents missing expected intents {expected - intent_names}: {intent_payload!r}")
    print("[PASS] /speech/intents returns executable schema")

    telemetry = request("GET", f"{base_url}/speech/intent-telemetry/schema", timeout=timeout)
    if telemetry.status != 200:
        raise CheckFailure(f"/speech/intent-telemetry/schema returned HTTP {telemetry.status}: {telemetry.raw_body}")
    telemetry_payload = require_record(telemetry.payload, "/speech/intent-telemetry/schema")
    if telemetry_payload.get("stores_raw_audio") is not False or telemetry_payload.get("stores_transcript_text") is not False:
        raise CheckFailure(f"/speech/intent-telemetry/schema must be privacy-minimized: {telemetry_payload!r}")
    print("[PASS] /speech/intent-telemetry/schema excludes raw audio and transcript text")

    phrases = request("GET", f"{base_url}/speech/phrases", timeout=timeout)
    if phrases.status != 200:
        raise CheckFailure(f"/speech/phrases returned HTTP {phrases.status}: {phrases.raw_body}")
    phrase_payload = require_record(phrases.payload, "/speech/phrases")
    phrase_ids = {
        item.get("id")
        for item in phrase_payload.get("phrases", [])
        if isinstance(item, dict)
    }
    if not {"navigation.ready", "risk.blocking.stop"} <= phrase_ids:
        raise CheckFailure(f"/speech/phrases missing core phrase ids: {phrase_payload!r}")
    print("[PASS] /speech/phrases returns core phrase catalog")


def check_cors_options(base_url: str, timeout: float) -> None:
    result = request(
        "OPTIONS",
        f"{base_url}/speech/tts",
        headers={
            "Origin": "http://localhost:3000",
            "Access-Control-Request-Method": "POST",
        },
        timeout=timeout,
    )
    if result.status not in {200, 204}:
        raise CheckFailure(f"OPTIONS /speech/tts returned HTTP {result.status}: {result.raw_body}")
    print("[PASS] OPTIONS /speech/tts CORS preflight accepted")


def check_stt_error_contract(base_url: str, timeout: float) -> None:
    stt_url = f"{base_url}/speech/stt"
    empty = multipart_upload(
        stt_url,
        filename="empty.webm",
        content_type="audio/webm",
        content=b"",
        timeout=timeout,
    )
    if empty.status != 400 or detail_code(empty.payload) != "empty_audio":
        raise CheckFailure(
            "Expected empty /speech/stt upload to return HTTP 400 detail.code=empty_audio, "
            f"got HTTP {empty.status}: {empty.raw_body}"
        )
    print("[PASS] /speech/stt empty upload returns detail.code=empty_audio")

    unsupported = multipart_upload(
        stt_url,
        filename="command.txt",
        content_type="text/plain",
        content=b"not audio",
        timeout=timeout,
    )
    if unsupported.status != 400 or detail_code(unsupported.payload) != "unsupported_audio_type":
        raise CheckFailure(
            "Expected unsupported /speech/stt upload to return HTTP 400 detail.code=unsupported_audio_type, "
            f"got HTTP {unsupported.status}: {unsupported.raw_body}"
        )
    print("[PASS] /speech/stt unsupported upload returns detail.code=unsupported_audio_type")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Lightweight local voice API contract smoke check. It verifies /health, "
            "/speech/intent, and /speech/stt validation errors without sending real audio "
            "through the STT model."
        )
    )
    parser.add_argument(
        "--base-url",
        default=os.getenv("VOICE_API_BASE", DEFAULT_VOICE_API_BASE),
        help=f"Voice API base URL. Defaults to VOICE_API_BASE or {DEFAULT_VOICE_API_BASE}.",
    )
    parser.add_argument("--timeout", type=float, default=3.0, help="HTTP timeout in seconds. Default: 3.0.")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        base_url = normalize_base_url(args.base_url)
        print(f"Voice API contract check: {base_url}", flush=True)
        check_health(base_url, args.timeout)
        check_intents(base_url, args.timeout)
        check_voice_metadata_endpoints(base_url, args.timeout)
        check_cors_options(base_url, args.timeout)
        check_stt_error_contract(base_url, args.timeout)
        print("Voice API contract smoke check passed.")
        return 0
    except CheckFailure as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
