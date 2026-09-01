"""Bound request bodies before multipart parsing can spool them to disk."""

from __future__ import annotations

import logging
from typing import Any

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send


REQUEST_BODY_OVERHEAD_BYTES = 256 * 1024
PRIVACY_REQUEST_BODY_LIMIT_BYTES = 32 * 1024
RAW_MANIFEST_BODY_LIMIT_BYTES = 512 * 1024
RAW_CHUNK_BODY_LIMIT_BYTES = 8 * 1024 * 1024
RAW_COMMIT_BODY_LIMIT_BYTES = 16 * 1024
ACCOUNT_REQUEST_BODY_LIMIT_BYTES = 16 * 1024
REPORT_CORRECTION_BODY_LIMIT_BYTES = 4 * 1024
_PRIVACY_NO_STORE_HEADERS = {
    "Cache-Control": "no-store",
    "Pragma": "no-cache",
}
_RAW_NO_STORE_HEADERS = {
    "Cache-Control": "no-store",
    "Pragma": "no-cache",
}
_ACCOUNT_PATHS = frozenset(
    {
        "/account-enrollments/email-otp",
        "/accounts",
        "/accounts/authenticate",
    }
)


logger = logging.getLogger(__name__)


def _is_raw_collection_path(path: str) -> bool:
    return path.startswith("/raw-collections/") or path.startswith(
        "/admin/raw-collections/"
    )


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


class RawCollectionNoStoreMiddleware:
    """Prevent every raw collection response from being cached."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not _is_raw_collection_path(
            scope.get("path", "")
        ):
            await self.app(scope, receive, send)
            return

        async def raw_send(message: Message) -> None:
            if message["type"] == "http.response.start":
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

        await self.app(scope, receive, raw_send)


class RawCollectionExceptionMiddleware:
    """Return a stable fail-closed envelope for unhandled raw failures."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not _is_raw_collection_path(
            scope.get("path", "")
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
            logger.exception("Unhandled raw collection request failure")
            response = JSONResponse(
                status_code=503,
                headers={**_RAW_NO_STORE_HEADERS, "Retry-After": "5"},
                content={
                    "detail": {
                        "code": "raw_collection_service_unavailable",
                        "message": (
                            "The raw collection service is temporarily unavailable."
                        ),
                    }
                },
            )
            await response(scope, receive, tracked_send)


def raw_collection_validation_error_response() -> JSONResponse:
    return JSONResponse(
        status_code=422,
        headers=_RAW_NO_STORE_HEADERS,
        content={
            "detail": {
                "code": "raw_collection_request_validation_failed",
                "message": (
                    "The raw collection request does not match the required contract."
                ),
            }
        },
    )


def max_request_body_bytes(settings: Any, *, path: str | None = None) -> int:
    if path in _ACCOUNT_PATHS:
        return ACCOUNT_REQUEST_BODY_LIMIT_BYTES
    if (
        path is not None
        and path.startswith("/reports/mine/")
        and path.endswith("/corrections")
    ):
        return REPORT_CORRECTION_BODY_LIMIT_BYTES
    if path is not None and path.startswith("/privacy/"):
        return PRIVACY_REQUEST_BODY_LIMIT_BYTES
    if path is not None and path.startswith("/raw-collections/"):
        if path.endswith("/manifest"):
            return RAW_MANIFEST_BODY_LIMIT_BYTES
        if "/chunks/" in path:
            return RAW_CHUNK_BODY_LIMIT_BYTES
        if path.endswith("/commit"):
            return RAW_COMMIT_BODY_LIMIT_BYTES
    return (
        int(settings.max_upload_bytes)
        + int(settings.max_report_metadata_bytes)
        + REQUEST_BODY_OVERHEAD_BYTES
    )


class RequestBodyLimitMiddleware:
    def __init__(
        self,
        app: ASGIApp,
        settings: Any,
        *,
        skip_raw_collections: bool = False,
    ) -> None:
        self.app = app
        self.settings = settings
        self.skip_raw_collections = skip_raw_collections

    def _max_bytes(self) -> int:
        return max_request_body_bytes(self.settings)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if self.skip_raw_collections and scope.get("path", "").startswith(
            "/raw-collections/"
        ):
            await self.app(scope, receive, send)
            return
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
        raw_collection_request = scope.get("path", "").startswith(
            "/raw-collections/"
        )
        account_request = scope.get("path", "") in _ACCOUNT_PATHS
        report_correction_request = (
            scope.get("path", "").startswith("/reports/mine/")
            and scope.get("path", "").endswith("/corrections")
        )
        content_length = request_content_length(scope)
        if content_length is not None and content_length > max_bytes:
            await _too_large_response(
                max_bytes,
                privacy=privacy_request,
                raw_collection=raw_collection_request,
                account=account_request,
                report_correction=report_correction_request,
            )(
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
            await _too_large_response(
                max_bytes,
                privacy=privacy_request,
                raw_collection=raw_collection_request,
                account=account_request,
                report_correction=report_correction_request,
            )(
                scope, receive, send
            )


class RawCollectionBodyLimitMiddleware(RequestBodyLimitMiddleware):
    """Apply the raw limit after request-bound Gateway authentication."""

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not scope.get("path", "").startswith(
            "/raw-collections/"
        ):
            await self.app(scope, receive, send)
            return
        await super().__call__(scope, receive, send)


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


def _too_large_response(
    max_bytes: int,
    *,
    privacy: bool = False,
    raw_collection: bool = False,
    account: bool = False,
    report_correction: bool = False,
) -> JSONResponse:
    code = (
        "raw_collection_request_body_too_large"
        if raw_collection
        else "account_request_body_too_large"
        if account
        else "report_content_correction_body_too_large"
        if report_correction
        else "request_body_too_large"
    )
    detail: dict[str, Any]
    if account or report_correction:
        detail = {
            "code": code,
            "message": (
                "The account request body exceeds the fixed byte limit."
                if account
                else "The report correction body exceeds the fixed byte limit."
            ),
        }
    else:
        detail = {"code": code, "max_bytes": max_bytes}
    if raw_collection:
        detail["message"] = "The request body exceeds the fixed byte limit."
    return JSONResponse(
        status_code=413,
        headers=(
            _RAW_NO_STORE_HEADERS
            if raw_collection
            else _PRIVACY_NO_STORE_HEADERS
            if account or privacy or report_correction
            else None
        ),
        content={"detail": detail},
    )


__all__ = [
    "REQUEST_BODY_OVERHEAD_BYTES",
    "PRIVACY_REQUEST_BODY_LIMIT_BYTES",
    "RAW_CHUNK_BODY_LIMIT_BYTES",
    "RAW_COMMIT_BODY_LIMIT_BYTES",
    "RAW_MANIFEST_BODY_LIMIT_BYTES",
    "REPORT_CORRECTION_BODY_LIMIT_BYTES",
    "PrivacyExceptionMiddleware",
    "PrivacyNoStoreMiddleware",
    "RawCollectionExceptionMiddleware",
    "RawCollectionBodyLimitMiddleware",
    "RawCollectionNoStoreMiddleware",
    "RequestBodyLimitMiddleware",
    "RequestBodyTooLarge",
    "max_request_body_bytes",
    "request_content_length",
    "raw_collection_validation_error_response",
]
