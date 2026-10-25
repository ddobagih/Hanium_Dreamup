"""Body replay must preserve both bytes and the caller's connection lifetime."""

from __future__ import annotations

import asyncio
import hashlib

import pytest
from starlette.requests import Request

from backend.app import field_test_security as security
from backend.app.api.navigation import _until_disconnect
from backend.app.request_limits import RequestBodyTooLarge


@pytest.fixture(params=["_capture_request_body", "_capture_request_body_sha256"])
def capture(request):
    return getattr(security, request.param)


@pytest.mark.parametrize("chunks", [
    [b""],
    [b'{"name":"\xec', b"", b'\x84\x9c\xec\x9a\xb8"}\r\n\x00\xff'],
], ids=["empty", "chunked-bytes"])
def test_replay_preserves_body_then_waits_for_the_actual_disconnect(capture, chunks):
    async def exercise():
        body = b"".join(chunks)
        messages = [
            {"type": "http.request", "body": chunk, "more_body": index < len(chunks) - 1}
            for index, chunk in enumerate(chunks)
        ]
        incoming = asyncio.Queue()
        for message in messages:
            incoming.put_nowait(message)
        scope = {"type": "http", "headers": [(b"content-length", str(len(body)).encode())]}
        captured, receive = await capture(scope, incoming.get, max_bytes=len(body))
        expected = body if capture is security._capture_request_body else hashlib.sha256(body).hexdigest()
        assert captured == expected
        for message in messages:
            replayed = await receive()
            assert replayed == message
            assert replayed is not message

        waiting = asyncio.create_task(receive())
        try:
            await asyncio.sleep(0)
            assert not waiting.done(), "Body exhaustion must not manufacture a disconnect"
            disconnect = {"type": "http.disconnect"}
            incoming.put_nowait(disconnect)
            assert await asyncio.wait_for(waiting, timeout=1) == disconnect
            assert incoming.empty(), "The disconnect must be consumed from the original receive"
        finally:
            waiting.cancel()
            await asyncio.gather(waiting, return_exceptions=True)

    asyncio.run(exercise())


@pytest.mark.parametrize("disconnect", [False, True], ids=["connected", "disconnected"])
def test_navigation_provider_is_cancelled_only_for_an_actual_disconnect(capture, disconnect):
    async def exercise():
        incoming = asyncio.Queue()
        incoming.put_nowait({"type": "http.request", "body": b"", "more_body": False})
        scope = {"type": "http", "headers": []}
        _, receive = await capture(scope, incoming.get, max_bytes=0)
        cancelled = False
        started = asyncio.Event()

        async def provider():
            nonlocal cancelled
            started.set()
            try:
                await asyncio.sleep(0.02)
                return "provider-result"
            except asyncio.CancelledError:
                cancelled = True
                raise

        request = asyncio.create_task(_until_disconnect(Request(scope, receive), provider()))
        try:
            await asyncio.wait_for(started.wait(), timeout=1)
            if disconnect:
                incoming.put_nowait({"type": "http.disconnect"})
                with pytest.raises(asyncio.CancelledError):
                    await asyncio.wait_for(request, timeout=1)
                assert incoming.empty()
                assert cancelled
            else:
                assert await asyncio.wait_for(request, timeout=1) == "provider-result"
                assert not cancelled
        finally:
            request.cancel()
            await asyncio.gather(request, return_exceptions=True)

    asyncio.run(exercise())


@pytest.mark.parametrize("declared", [True, False], ids=["content-length", "streamed"])
def test_capture_still_rejects_oversized_bodies(capture, declared):
    async def exercise():
        messages = [
            {"type": "http.request", "body": b"ab", "more_body": True},
            {"type": "http.request", "body": b"c", "more_body": False},
        ]
        headers = [(b"content-length", b"3")] if declared else []
        reads = 0

        async def receive():
            nonlocal reads
            message = messages[reads]
            reads += 1
            return message

        with pytest.raises(RequestBodyTooLarge):
            await capture({"type": "http", "headers": headers}, receive, max_bytes=2)
        assert reads == (0 if declared else 2)

    asyncio.run(exercise())


@pytest.mark.parametrize("messages,headers", [
    ([{"type": "http.request", "body": b"a", "more_body": False}], [(b"content-length", b"2")]),
    ([{"type": "http.request", "body": b"ab", "more_body": False}], [(b"content-length", b"1")]),
    ([{"type": "http.request", "body": b"a", "more_body": True}, {"type": "http.disconnect"}], []),
    ([{"type": "http.request", "body": "not-bytes", "more_body": False}], []),
], ids=["short-body", "long-body", "disconnect-during-body", "invalid-body-type"])
def test_capture_still_rejects_incomplete_or_invalid_bodies(capture, messages, headers):
    async def exercise():
        incoming = asyncio.Queue()
        for message in messages:
            incoming.put_nowait(message)
        with pytest.raises(security._RequestBodyIncomplete):
            await capture({"type": "http", "headers": headers}, incoming.get, max_bytes=10)

    asyncio.run(exercise())
