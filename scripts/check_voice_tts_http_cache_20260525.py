#!/usr/bin/env python3
"""Check local Voice TTS HTTP cache headers without calling external services.

Usage:
  VOICE_SERVICE_TOKEN='<dedicated-24+-character-token>' \
    VOICE_TTS_CACHE_CHECK_URL=http://127.0.0.1:9001/speech/tts \
    python scripts/check_voice_tts_http_cache_20260525.py

The script intentionally accepts only loopback URLs. If no local server URL is
provided or the server is not reachable, it exits as BLOCKED with a hint rather
than making an external request.
"""
from __future__ import annotations

import argparse
from http.client import HTTPConnection
import json
import os
from urllib.parse import urlparse

ALLOWED_HOSTS = {"127.0.0.1", "localhost", "::1"}
REQUIRED_HEADERS = ("x-voice-cached", "x-voice-fallback")
DEFAULT_TEXTS = [
    "전방 장애물이 있습니다. 멈추세요.",
    "전방 객체가 다가옵니다. 피하세요.",
    "음성 요청 신고가 저장되었습니다.",
    "음성 요청 신고에 실패했습니다.",
    "현재 위치를 확인했습니다.",
    "목적지를 저장했습니다.",
    "길안내를 시작할 준비가 됐습니다.",
]


def blocked(message: str) -> int:
    print(f"BLOCKED: {message}")
    print("hint: start the local voice/TTS server and pass a loopback URL with --url or VOICE_TTS_CACHE_CHECK_URL")
    return 2


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Check local /speech/tts cache/fallback headers.")
    parser.add_argument("--url", default=os.getenv("VOICE_TTS_CACHE_CHECK_URL"), help="Loopback TTS URL to check")
    parser.add_argument("--text", default="길라잡이 음성 캐시 점검입니다.", help="Local TTS smoke text")
    parser.add_argument("--batch", action="store_true", help="Check the default WalkSafe phrase batch")
    parser.add_argument("--json", action="store_true", help="Print machine-readable JSON")
    parser.add_argument("--blocked-exit-zero", action="store_true", help="Return 0 instead of 2 for BLOCKED outcomes")
    parser.add_argument("--timeout", type=float, default=3.0)
    return parser


def response_headers(response) -> dict[str, str]:
    return {key.lower(): value for key, value in response.getheaders()}


def post_tts(url_parts, text: str, timeout: float, service_token: str) -> tuple[int, dict[str, str], int]:
    port = url_parts.port or 80
    path = url_parts.path if url_parts.path and url_parts.path != "/" else "/speech/tts"
    if parsed_query := url_parts.query:
        path = f"{path}?{parsed_query}"

    body = json.dumps({"text": text, "use_cache": True, "allow_fallback": True}, ensure_ascii=False).encode("utf-8")
    connection = HTTPConnection(url_parts.hostname, port, timeout=timeout)
    try:
        connection.request(
            "POST",
            path,
            body=body,
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Accept": "audio/wav",
                "x-walksafe-voice-service-token": service_token,
            },
        )
        response = connection.getresponse()
        headers = response_headers(response)
        body = response.read()
        return response.status, headers, len(body)
    finally:
        connection.close()


def main() -> int:
    args = build_parser().parse_args()
    rows = []

    def finish_blocked(message: str) -> int:
        if args.json:
            print(json.dumps({"status": "BLOCKED", "message": message, "rows": rows}, ensure_ascii=False))
        else:
            blocked(message)
        return 0 if args.blocked_exit_zero else 2

    if not args.url:
        return finish_blocked("no local TTS URL provided")
    service_token = os.getenv("VOICE_SERVICE_TOKEN", "").strip()
    if len(service_token) < 24:
        return finish_blocked("VOICE_SERVICE_TOKEN must contain at least 24 characters")

    parsed = urlparse(args.url)
    if parsed.scheme != "http" or parsed.hostname not in ALLOWED_HOSTS:
        return finish_blocked("only http://localhost or http://127.0.0.1 URLs are allowed")

    texts = DEFAULT_TEXTS if args.batch else [args.text]
    for text in texts:
        try:
            first_status, first_headers, first_bytes = post_tts(parsed, text, args.timeout, service_token)
            second_status, second_headers, second_bytes = post_tts(parsed, text, args.timeout, service_token)
        except OSError as exc:
            return finish_blocked(f"local TTS server not reachable: {exc}")

        row = {
            "text": text,
            "first_status": first_status,
            "second_status": second_status,
            "first_cached": first_headers.get("x-voice-cached"),
            "second_cached": second_headers.get("x-voice-cached"),
            "first_fallback": first_headers.get("x-voice-fallback"),
            "second_fallback": second_headers.get("x-voice-fallback"),
            "content_type": second_headers.get("content-type"),
            "second_bytes": second_bytes,
        }
        rows.append(row)

        if first_status >= 400 or second_status >= 400:
            print(f"FAIL: TTS endpoint returned HTTP {first_status} then {second_status}")
            return 1
        missing = [name for name in REQUIRED_HEADERS if name not in second_headers]
        if missing:
            print(f"FAIL: missing required TTS response headers: {', '.join(missing)}")
            return 1
        if not (second_headers.get("content-type") or "").startswith("audio/wav"):
            print(f"FAIL: expected audio/wav response, got {second_headers.get('content-type')!r}")
            return 1
        if first_bytes <= 0 or second_bytes <= 0:
            print("FAIL: expected non-empty audio response bytes")
            return 1
        if first_headers.get("x-voice-fallback") == "true" or second_headers.get("x-voice-fallback") == "true":
            return finish_blocked("TTS fallback response was used; cache hit cannot be verified")
        if second_headers.get("x-voice-cached") != "true":
            print(
                "FAIL: expected second /speech/tts call to return X-Voice-Cached: true; "
                f"got {second_headers.get('x-voice-cached')!r}"
            )
            return 1

    if args.json:
        print(json.dumps({"status": "PASS", "rows": rows}, ensure_ascii=False))
    else:
        print(f"OK: /speech/tts cache headers verified for {len(rows)} phrase(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
