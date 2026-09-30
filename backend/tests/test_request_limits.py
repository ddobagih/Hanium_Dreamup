from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from types import SimpleNamespace

import pytest

from fastapi import FastAPI, HTTPException, Request, WebSocket
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

from backend.app.main import _PrivacyNoStoreFastAPI, app, settings
from backend.app.request_limits import (
    REQUEST_BODY_OVERHEAD_BYTES,
    PrivacyNoStoreMiddleware,
    RequestBodyLimitMiddleware,
)
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


@pytest.mark.parametrize("path", ["/reports", "/reports/summary", "/reports/fixture-id"])
@pytest.mark.parametrize("method", ["GET", "HEAD", "OPTIONS"])
@pytest.mark.parametrize("status_code", [200, 401, 403, 404, 422, 429, 500, 503])
def test_report_responses_override_cache_headers_without_changing_response(
    path, method, status_code,
) -> None:
    sent = []
    payload = b'{"detail":{"code":"fixture_response"}}'

    async def downstream(scope, receive, send):
        await send({
            "type": "http.response.start",
            "status": status_code,
            "headers": [
                (b"Cache-Control", b"public, max-age=3600"),
                (b"cache-control", b"public"),
                (b"Pragma", b"cache"),
                (b"retry-after", b"5"),
                (b"x-request-id", b"fixture"),
            ],
        })
        await send({"type": "http.response.body", "body": payload})

    async def receive():
        return {"type": "http.request", "body": b""}

    async def send(message):
        sent.append(message)

    asyncio.run(PrivacyNoStoreMiddleware(downstream)(
        {"type": "http", "method": method, "path": path}, receive, send,
    ))
    assert sent[0]["status"] == status_code
    assert sent[0]["headers"] == [
        (b"retry-after", b"5"),
        (b"x-request-id", b"fixture"),
        (b"cache-control", b"private, no-store"),
        (b"pragma", b"no-cache"),
    ]
    assert sent[1] == {"type": "http.response.body", "body": payload}


@pytest.mark.parametrize("path,method,expected", [
    ("/privacy/rights", "GET", b"no-store"),
    ("/privacy/consent-events", "POST", b"no-store"),
    ("/reports", "POST", b"private, no-store"),
    ("/reports/summary", "PUT", b"private, no-store"),
    ("/reports-other", "GET", b"public"),
    ("/reports/fixture-id", "GET", b"private, no-store"),
    ("/health", "GET", b"public"),
])
def test_report_cache_scope_preserves_other_contracts(path, method, expected) -> None:
    sent = []

    async def downstream(scope, receive, send):
        await send({
            "type": "http.response.start",
            "status": 200,
            "headers": [(b"cache-control", b"public")],
        })
        await send({"type": "http.response.body", "body": b""})

    async def receive():
        return {"type": "http.request", "body": b""}

    async def send(message):
        sent.append(message)

    asyncio.run(PrivacyNoStoreMiddleware(downstream)(
        {"type": "http", "method": method, "path": path}, receive, send,
    ))
    assert dict(sent[0]["headers"])[b"cache-control"] == expected


def test_report_read_cache_middleware_preserves_unhandled_exception_boundary() -> None:
    sent = []

    async def downstream(scope, receive, send):
        raise RuntimeError("fixture failure")

    async def receive():
        return {"type": "http.request", "body": b""}

    async def send(message):
        sent.append(message)

    with pytest.raises(RuntimeError, match="fixture failure"):
        asyncio.run(PrivacyNoStoreMiddleware(downstream)(
            {"type": "http", "method": "GET", "path": "/reports"}, receive, send,
        ))
    # The existing outer server-error handler still owns an unhandled 500.
    assert sent == []


def _capture_http_entry(application, path, method="GET"):
    scope = {
        "type": "http", "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1", "method": method, "scheme": "http",
        "path": path, "raw_path": path.encode("ascii"), "root_path": "",
        "query_string": b"", "headers": [(b"accept", b"text/plain")],
        "client": ("127.0.0.1", 1), "server": ("test", 80),
    }
    sent = []
    error = None

    async def invoke():
        nonlocal error
        delivered = False

        async def receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": b"", "more_body": False}
            await asyncio.Future()

        async def send(message):
            sent.append(message)

        try:
            await application(scope, receive, send)
        except Exception as exc:
            error = exc

    asyncio.run(invoke())
    return scope, sent, error


def _assert_single_cache_policy(headers, value):
    assert [v for k, v in headers if k.lower() == b"cache-control"] == [value]
    assert [v for k, v in headers if k.lower() == b"pragma"] == [b"no-cache"]


def _non_cache_headers(headers):
    return [(k, v) for k, v in headers if k.lower() not in {b"cache-control", b"pragma"}]


@pytest.mark.parametrize("path,method,policy", [
    ("/admin/reports", "GET", b"private, no-store"),
    ("/admin/reports/fixture-id", "HEAD", b"private, no-store"),
    ("/admin/report-requests", "POST", b"private, no-store"),
    ("/admin/report-requests/fixture-id", "PATCH", b"private, no-store"),
    ("/admin/report-deletions", "OPTIONS", b"private, no-store"),
    ("/admin/report-deletions/fixture-id", "DELETE", b"private, no-store"),
    ("/uploads/report.png", "GET", b"private, no-store"),
    ("/privacy/rights", "GET", b"no-store"),
    ("/reports-other", "GET", None),
    ("/admin/reports-other", "GET", None),
    ("/admin/report-requests-other", "GET", None),
    ("/admin/report-deletions-other", "GET", None),
    ("/uploads-other/report.png", "GET", None),
    ("/uploads", "GET", None),
])
@pytest.mark.parametrize("status_code", [200, 404, 422, 503])
def test_report_family_direct_asgi_cache_policy_and_scope(path, method, policy, status_code):
    original_headers = [
        (b"Cache-Control", b"public, max-age=3600"),
        (b"cache-control", b"public"),
        (b"Pragma", b"cache"),
        (b"retry-after", b"5"),
        (b"x-request-id", b"fixture"),
    ]
    payload = b'{"detail":{"code":"fixture_response"}}'

    async def downstream(scope, receive, send):
        await send({"type": "http.response.start", "status": status_code, "headers": original_headers})
        await send({"type": "http.response.body", "body": payload})

    _, sent, error = _capture_http_entry(PrivacyNoStoreMiddleware(downstream), path, method)
    assert error is None
    assert sent[0]["status"] == status_code
    assert sent[1] == {"type": "http.response.body", "body": payload}
    if policy is None:
        assert sent[0]["headers"] == original_headers
    else:
        assert _non_cache_headers(sent[0]["headers"]) == _non_cache_headers(original_headers)
        _assert_single_cache_policy(sent[0]["headers"], policy)


@pytest.mark.parametrize("path,status_code", [
    ("/admin/reports/fixture-id", 404),
    ("/admin/report-requests/fixture-id", 422),
    ("/admin/report-deletions/fixture-id", 503),
    ("/uploads/report.png", 404),
])
def test_report_family_preserves_handled_http_errors(path, status_code):
    results = []
    for application_type in (FastAPI, _PrivacyNoStoreFastAPI):
        application = application_type()

        async def fail():
            raise HTTPException(
                status_code=status_code,
                detail={"code": "fixture"},
                headers={"Cache-Control": "public", "Pragma": "cache", "Retry-After": "5"},
            )

        application.add_api_route(path, fail, methods=["GET"])
        _, sent, error = _capture_http_entry(application, path)
        assert error is None
        assert sent[0]["status"] == status_code
        results.append(sent)

    baseline, guarded = results
    assert guarded[1:] == baseline[1:]
    assert _non_cache_headers(guarded[0]["headers"]) == _non_cache_headers(baseline[0]["headers"])
    _assert_single_cache_policy(guarded[0]["headers"], b"private, no-store")


@pytest.mark.parametrize("path,method", [
    ("/admin/reports", "GET"),
    ("/admin/reports/fixture-id", "HEAD"),
    ("/admin/report-requests", "POST"),
    ("/admin/report-requests/fixture-id", "PATCH"),
    ("/admin/report-deletions", "OPTIONS"),
    ("/admin/report-deletions/fixture-id", "DELETE"),
    ("/uploads/report.png", "GET"),
])
@pytest.mark.parametrize("debug", [False, True])
def test_report_family_outer_500_preserves_framework_error(path, method, debug):
    results = []
    failure = RuntimeError("outer report family fixture")
    for application_type in (FastAPI, _PrivacyNoStoreFastAPI):
        application = application_type(debug=debug)

        async def fail():
            raise failure

        application.add_api_route(path, fail, methods=[method])
        _, sent, error = _capture_http_entry(application, path, method)
        assert error is failure
        assert len([message for message in sent if message["type"] == "http.response.start"]) == 1
        assert sent[0]["status"] == 500
        results.append(sent)

    baseline, guarded = results
    assert guarded[1:] == baseline[1:]
    assert _non_cache_headers(guarded[0]["headers"]) == _non_cache_headers(baseline[0]["headers"])
    _assert_single_cache_policy(guarded[0]["headers"], b"private, no-store")


@pytest.mark.parametrize("path", ["/reports", "/reports/summary", "/reports/fixture-id"])
@pytest.mark.parametrize("method", ["GET", "HEAD", "OPTIONS"])
@pytest.mark.parametrize("debug", [False, True])
def test_outer_cache_policy_covers_framework_errors_without_replacing_them(path, method, debug):
    results = []
    for application_type in (FastAPI, _PrivacyNoStoreFastAPI):
        application = application_type(debug=debug)
        application.add_middleware(PrivacyNoStoreMiddleware)
        failure = RuntimeError("outer cache boundary fixture")

        async def fail(request: Request):
            assert request.app is application
            raise failure

        application.add_api_route(path, fail, methods=[method])
        scope, sent, error = _capture_http_entry(application, path, method)
        assert error is failure
        assert scope["app"] is application
        assert len([m for m in sent if m["type"] == "http.response.start"]) == 1
        assert sent[0]["status"] == 500
        results.append(sent)

    baseline, guarded = results
    assert not any(k.lower() == b"cache-control" for k, _ in baseline[0]["headers"])
    _assert_single_cache_policy(guarded[0]["headers"], b"private, no-store")
    assert _non_cache_headers(guarded[0]["headers"]) == _non_cache_headers(baseline[0]["headers"])
    assert guarded[1:] == baseline[1:]


@pytest.mark.parametrize("debug", [False, True])
@pytest.mark.parametrize("async_handler", [False, True])
def test_outer_cache_policy_preserves_custom_handler_selection_and_single_invocation(debug, async_handler):
    results = []
    for application_type in (FastAPI, _PrivacyNoStoreFastAPI):
        calls = []
        failure = RuntimeError("custom error boundary fixture")

        def handle(request, exc):
            assert request.app is application
            calls.append(exc)
            response = JSONResponse(
                {"detail": "unchanged custom response"}, status_code=503,
                headers={"Cache-Control": "public", "Pragma": "cache", "Retry-After": "5"},
            )
            response.set_cookie("first", "1")
            response.set_cookie("second", "2")
            return response

        async def handle_async(request, exc):
            return handle(request, exc)

        application = application_type(
            debug=debug, exception_handlers={Exception: handle_async if async_handler else handle},
        )
        application.add_middleware(PrivacyNoStoreMiddleware)

        async def fail():
            raise failure

        application.add_api_route("/reports", fail, methods=["GET"])
        _, sent, error = _capture_http_entry(application, "/reports")
        assert error is failure
        assert calls == ([] if debug else [failure])
        assert len([m for m in sent if m["type"] == "http.response.start"]) == 1
        results.append((sent, len(calls)))

    (baseline, baseline_calls), (guarded, guarded_calls) = results
    assert guarded_calls == baseline_calls
    assert guarded[0]["status"] == baseline[0]["status"]
    assert guarded[1:] == baseline[1:]
    assert _non_cache_headers(guarded[0]["headers"]) == _non_cache_headers(baseline[0]["headers"])
    _assert_single_cache_policy(guarded[0]["headers"], b"private, no-store")
    if not debug:
        assert guarded[0]["status"] == 503
        assert len([v for k, v in guarded[0]["headers"] if k == b"set-cookie"]) == 2


@pytest.mark.parametrize("path,method,policy", [
    ("/reports", "GET", b"private, no-store"),
    ("/reports/summary", "HEAD", b"private, no-store"),
    ("/reports/summary", "OPTIONS", b"private, no-store"),
    ("/reports/fixture-id", "GET", b"private, no-store"),
    ("/privacy/rights", "GET", b"no-store"),
    ("/privacy/consent-events", "POST", b"no-store"),
    ("/reports", "POST", b"private, no-store"),
    ("/reports/summary", "PUT", b"private, no-store"),
    ("/reports-other", "GET", None),
    ("/health", "GET", None),
])
@pytest.mark.parametrize("handled_error", [False, True])
def test_outer_cache_policy_preserves_normal_handled_responses_and_scope(path, method, policy, handled_error):
    results = []
    for application_type in (FastAPI, _PrivacyNoStoreFastAPI):
        application = application_type()
        application.state.fixture = "state preserved"
        application.add_middleware(PrivacyNoStoreMiddleware)

        async def endpoint(request: Request):
            assert request.app is application
            assert request.app.state.fixture == "state preserved"
            headers = {"Cache-Control": "public", "Pragma": "cache", "Retry-After": "5"}
            if handled_error:
                raise HTTPException(status_code=429, detail={"code": "fixture"}, headers=headers)
            return JSONResponse({"code": "fixture"}, headers=headers)

        application.add_api_route(path, endpoint, methods=[method])
        scope, sent, error = _capture_http_entry(application, path, method)
        assert error is None
        assert scope["app"] is application
        results.append(sent)

    assert results[1] == results[0]
    if policy is not None:
        _assert_single_cache_policy(results[1][0]["headers"], policy)


@pytest.mark.parametrize("path,method,policy", [
    ("/privacy/rights", "GET", b"no-store"),
    ("/privacy/consent-events", "POST", b"no-store"),
    ("/reports", "POST", b"private, no-store"),
    ("/reports/summary", "PUT", b"private, no-store"),
    ("/reports-other", "GET", None),
    ("/reports/fixture-id", "GET", b"private, no-store"),
    ("/health", "GET", None),
])
def test_outer_cache_policy_does_not_expand_error_path_matching(path, method, policy):
    application = _PrivacyNoStoreFastAPI()
    failure = RuntimeError("scope failure fixture")

    async def fail():
        raise failure

    application.add_api_route(path, fail, methods=[method])
    _, sent, error = _capture_http_entry(application, path, method)
    assert error is failure
    assert sent[0]["status"] == 500
    if policy is None:
        assert not any(k.lower() in {b"cache-control", b"pragma"} for k, _ in sent[0]["headers"])
    else:
        _assert_single_cache_policy(sent[0]["headers"], policy)


def test_main_public_asgi_entry_protects_unhandled_report_errors(monkeypatch):
    assert isinstance(app, FastAPI)
    assert isinstance(app, _PrivacyNoStoreFastAPI)
    monkeypatch.setattr(settings, "field_test_security_enabled", False)
    failure = RuntimeError("production application entry fixture")

    async def fail(request):
        assert request.app is app
        raise failure

    monkeypatch.setattr(app.router, "routes", [Route("/reports", fail, methods=["GET"]), *app.router.routes])
    scope, sent, error = _capture_http_entry(app, "/reports")
    assert error is failure
    assert scope["app"] is app
    assert len([m for m in sent if m["type"] == "http.response.start"]) == 1
    assert sent[0]["status"] == 500
    _assert_single_cache_policy(sent[0]["headers"], b"private, no-store")


def test_outer_cache_policy_does_not_restart_an_already_started_response():
    results = []
    for application_type in (FastAPI, _PrivacyNoStoreFastAPI):
        application = application_type()
        application.add_middleware(PrivacyNoStoreMiddleware)
        failure = RuntimeError("after response start fixture")

        class PartialResponse(Response):
            async def __call__(self, scope, receive, send):
                await send({"type": "http.response.start", "status": 200, "headers": [(b"x-fixture", b"preserved")]})
                await send({"type": "http.response.body", "body": b"partial", "more_body": True})
                raise failure

        async def endpoint():
            return PartialResponse()

        application.add_api_route("/reports", endpoint, methods=["GET"])
        _, sent, error = _capture_http_entry(application, "/reports")
        assert error is failure
        assert len(sent) == 2
        assert sent[0]["status"] == 200
        assert sent[1] == {"type": "http.response.body", "body": b"partial", "more_body": True}
        results.append(sent)

    assert results[1] == results[0]
    _assert_single_cache_policy(results[1][0]["headers"], b"private, no-store")


def test_outer_cache_policy_preserves_lifespan_and_websocket_entry():
    results = []
    for application_type in (FastAPI, _PrivacyNoStoreFastAPI):
        lifecycle = []

        @asynccontextmanager
        async def lifespan(application):
            lifecycle.append(application)
            yield
            lifecycle.append(application)

        application = application_type(lifespan=lifespan)

        @application.websocket("/ws")
        async def websocket_endpoint(websocket: WebSocket):
            assert websocket.app is application
            await websocket.accept()
            await websocket.close(code=1000)

        async def invoke(scope, incoming):
            sent = []

            async def receive():
                if incoming:
                    return incoming.pop(0)
                await asyncio.Future()

            async def send(message):
                sent.append(message)

            await application(scope, receive, send)
            assert scope["app"] is application
            return sent

        lifespan_messages = asyncio.run(invoke(
            {"type": "lifespan", "asgi": {"version": "3.0"}, "state": {}},
            [{"type": "lifespan.startup"}, {"type": "lifespan.shutdown"}],
        ))
        assert lifecycle == [application, application]
        assert [message["type"] for message in lifespan_messages] == ["lifespan.startup.complete", "lifespan.shutdown.complete"]
        websocket_messages = asyncio.run(invoke(
            {"type": "websocket", "asgi": {"version": "3.0", "spec_version": "2.3"},
             "path": "/ws", "raw_path": b"/ws", "root_path": "", "scheme": "ws",
             "query_string": b"", "headers": [], "subprotocols": [],
             "client": ("127.0.0.1", 1), "server": ("test", 80)},
            [{"type": "websocket.connect"}],
        ))
        assert [message["type"] for message in websocket_messages] == ["websocket.accept", "websocket.close"]
        results.append((lifespan_messages, websocket_messages))

    assert results[1] == results[0]
