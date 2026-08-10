from __future__ import annotations

import asyncio
from types import SimpleNamespace

from backend.app.main import app, settings
from backend.app.request_limits import REQUEST_BODY_OVERHEAD_BYTES, RequestBodyLimitMiddleware
from asgi_client import ASGITestClient


def test_main_rejects_oversized_body_before_multipart_parsing(monkeypatch) -> None:
    monkeypatch.setattr(settings, "field_test_security_enabled", False)
    monkeypatch.setattr(settings, "max_upload_bytes", 1)
    monkeypatch.setattr(settings, "max_report_metadata_bytes", 1)
    payload = b"x" * (REQUEST_BODY_OVERHEAD_BYTES + 3)

    response = ASGITestClient(app).post(
        "/detect/v2",
        content=payload,
        headers={"content-type": "application/octet-stream"},
    )

    assert response.status_code == 413
    assert response.json()["detail"] == {
        "code": "request_body_too_large",
        "max_bytes": REQUEST_BODY_OVERHEAD_BYTES + 2,
    }


def test_streaming_body_without_content_length_is_still_bounded() -> None:
    downstream_called = False

    async def downstream(scope, receive, send):  # noqa: ANN001
        nonlocal downstream_called
        downstream_called = True
        while (await receive()).get("more_body"):
            pass

    middleware = RequestBodyLimitMiddleware(
        downstream,
        SimpleNamespace(max_upload_bytes=1, max_report_metadata_bytes=1),
    )
    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/detect/v2",
        "raw_path": b"/detect/v2",
        "query_string": b"",
        "headers": [],
        "client": ("127.0.0.1", 1),
        "server": ("test", 80),
        "root_path": "",
    }
    chunks = [
        {
            "type": "http.request",
            "body": b"x" * (REQUEST_BODY_OVERHEAD_BYTES + 2),
            "more_body": True,
        },
        {"type": "http.request", "body": b"x", "more_body": False},
    ]
    sent: list[dict[str, object]] = []

    async def receive():
        return chunks.pop(0)

    async def send(message):  # noqa: ANN001
        sent.append(message)

    asyncio.run(middleware(scope, receive, send))  # type: ignore[arg-type]

    assert downstream_called is True
    assert sent[0]["status"] == 413
