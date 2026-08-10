"""Bound request bodies before multipart parsing can spool them to disk."""

from __future__ import annotations

import logging
from typing import Any

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send


REQUEST_BODY_OVERHEAD_BYTES = 256 * 1024
PRIVACY_REQUEST_BODY_LIMIT_BYTES = 32 * 1024
_PRIVACY_NO_STORE_HEADERS = {
    "Cache-Control": "no-store",
    "Pragma": "no-cache",
}


logger = logging.getLogger(__name__)


class RequestBodyTooLarge(Exception):
    pass


class PrivacyNoStoreMiddleware:
    """Prevent every privacy response, including CORS preflight, from being cached."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not scope.get("path", "").startswith(
            "/privacy/"
        ):
            await self.app(scope, receive, send)
            return

        async def privacy_send(message: Message) -> None:
            is_start = message["type"] == "http.response.start"
            if is_start:
                headers = [
                    (name, value)
                    for name, value in message.get("headers", [])
                    if name.lower() not in {b"cache-control", b"pragma"}
                ]
                headers.extend(
                    [
                        (b"cache-control", b"no-store"),
                        (b"pragma", b"no-cache"),
                    ]
                )
                message = {**message, "headers": headers}
            await send(message)

        await self.app(scope, receive, privacy_send)


class PrivacyExceptionMiddleware:
    """Normalize privacy failures inside CORS so browser clients can read them."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not scope.get("path", "").startswith(
            "/privacy/"
        ):
            await self.app(scope, receive, send)
            return

        response_started = False

        async def tracked_send(message: Message) -> None:
            nonlocal response_started
            if message["type"] == "http.response.start":
                response_started = True
            await send(message)

        try:
            await self.app(scope, receive, tracked_send)
        except Exception:
            if response_started:
                raise
            logger.exception("Unhandled privacy request failure")
            response = JSONResponse(
                status_code=503,
                headers={**_PRIVACY_NO_STORE_HEADERS, "Retry-After": "5"},
                content={
                    "detail": {
                        "code": "privacy_lifecycle_store_unavailable",
                        "message": "The privacy lifecycle store is temporarily unavailable.",
                    }
                },
            )
            await response(scope, receive, tracked_send)


def max_request_body_bytes(settings: Any, *, path: str | None = None) -> int:
    if path is not None and path.startswith("/privacy/"):
        return PRIVACY_REQUEST_BODY_LIMIT_BYTES
    return (
        int(settings.max_upload_bytes)
        + int(settings.max_report_metadata_bytes)
        + REQUEST_BODY_OVERHEAD_BYTES
    )


class RequestBodyLimitMiddleware:
    def __init__(self, app: ASGIApp, settings: Any) -> None:
        self.app = app
        self.settings = settings

    def _max_bytes(self) -> int:
        return max_request_body_bytes(self.settings)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope.get("method", "GET").upper() not in {
            "POST",
            "PUT",
            "PATCH",
        }:
            await self.app(scope, receive, send)
            return

        max_bytes = max_request_body_bytes(
            self.settings,
            path=scope.get("path", ""),
        )
        privacy_request = scope.get("path", "").startswith("/privacy/")
        content_length = request_content_length(scope)
        if content_length is not None and content_length > max_bytes:
            await _too_large_response(max_bytes, privacy=privacy_request)(
                scope, receive, send
            )
            return

        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > max_bytes:
                    raise RequestBodyTooLarge
            return message

        try:
            await self.app(scope, limited_receive, send)
        except RequestBodyTooLarge:
            await _too_large_response(max_bytes, privacy=privacy_request)(
                scope, receive, send
            )


def request_content_length(scope: Scope) -> int | None:
    for name, value in scope.get("headers", []):
        if name.lower() != b"content-length":
            continue
        try:
            parsed = int(value.decode("ascii"))
        except (UnicodeDecodeError, ValueError):
            return None
        return parsed if parsed >= 0 else None
    return None


def _too_large_response(max_bytes: int, *, privacy: bool = False) -> JSONResponse:
    return JSONResponse(
        status_code=413,
        headers=(
            {"Cache-Control": "no-store", "Pragma": "no-cache"}
            if privacy
            else None
        ),
        content={
            "detail": {
                "code": "request_body_too_large",
                "max_bytes": max_bytes,
            }
        },
    )


__all__ = [
    "REQUEST_BODY_OVERHEAD_BYTES",
    "PRIVACY_REQUEST_BODY_LIMIT_BYTES",
    "PrivacyExceptionMiddleware",
    "PrivacyNoStoreMiddleware",
    "RequestBodyLimitMiddleware",
    "RequestBodyTooLarge",
    "max_request_body_bytes",
    "request_content_length",
]
