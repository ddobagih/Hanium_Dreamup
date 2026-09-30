"""A lost navigation caller must stop provider I/O and retain cleanup ownership."""

from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
import json
import multiprocessing
import os
import threading
from time import monotonic
import time

from fastapi import FastAPI
import httpx
import pytest

from backend.app.api import navigation
from backend.app.config import get_settings
from backend.app.schemas import DestinationSearchResponse
from backend.app.services.tmap_pedestrian import fetch_tmap_poi_search
from backend.app.services.tmap_runtime import TmapRuntime
from backend.app.services import tmap_runtime as runtime_module


def _settings(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "walking_route_provider", "tmap_pedestrian")
    monkeypatch.setattr(settings, "tmap_poi_provider", "live")
    monkeypatch.setattr(settings, "tmap_app_key", "disconnect-fixture-key")
    monkeypatch.setattr(settings, "tmap_poi_search_url", "https://example.test/pois")
    monkeypatch.setattr(settings, "tmap_timeout_seconds", 4.0)
    return settings


async def _eventually(predicate, *, seconds=2.0):
    async with asyncio.timeout(seconds):
        while not predicate():
            await asyncio.sleep(0.001)


def _partial_ipc_worker(sender, operation, arguments, entered):
    if arguments[2] == b"partial":
        os.write(sender.fileno(), b"\x00\x00\x10\x00" + b"first fragment")
        entered.set()
        time.sleep(30)
    else:
        # Spawn imports the unpatched production module in this child.
        from backend.app.services.tmap_runtime import _run_isolated_response_job

        _run_isolated_response_job(sender, operation, arguments, None)


@pytest.mark.parametrize("path", ["/navigation/destinations/search", "/navigation/walking"])
def test_asgi_disconnect_cancels_running_navigation_provider(monkeypatch, path):
    settings = _settings(monkeypatch)
    entered, cancelled = asyncio.Event(), asyncio.Event()

    async def provider(*args, **kwargs):
        entered.set()
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.set()

    if path.endswith("search"):
        monkeypatch.setattr(navigation, "fetch_tmap_poi_search", provider)
        method, body, query = "GET", b"", b"query=Station"
    else:
        monkeypatch.setattr(navigation, "fetch_tmap_pedestrian_route", provider)
        method, query = "POST", b""
        body = json.dumps({
            "origin": {"latitude": 37.0, "longitude": 127.0},
            "destination": {"latitude": 37.0, "longitude": 127.001},
            "priority": "RECOMMEND",
        }).encode()
    application = FastAPI()
    application.include_router(navigation.create_router(settings))

    async def exercise():
        incoming = asyncio.Queue()
        incoming.put_nowait({"type": "http.request", "body": body, "more_body": False})
        outgoing = []

        async def send(message):
            outgoing.append(message)

        scope = {
            "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
            "method": method, "scheme": "http", "path": path, "raw_path": path.encode(),
            "query_string": query, "root_path": "", "headers": [(b"host", b"testserver")],
            "client": ("127.0.0.1", 12345), "server": ("testserver", 80),
        }
        serving = asyncio.create_task(application(scope, incoming.get, send))
        try:
            await asyncio.wait_for(entered.wait(), 2)
            assert not serving.done()
            incoming.put_nowait({"type": "http.disconnect"})
            await asyncio.wait_for(cancelled.wait(), 2)
            result = await asyncio.wait_for(asyncio.gather(serving, return_exceptions=True), 2)
            assert isinstance(result[0], asyncio.CancelledError)
            assert outgoing == []
        finally:
            serving.cancel()
            await asyncio.gather(serving, return_exceptions=True)

    asyncio.run(exercise())


def test_delayed_navigation_response_completes_while_caller_remains_connected(monkeypatch):
    settings = _settings(monkeypatch)
    entered, release, cancelled = asyncio.Event(), asyncio.Event(), asyncio.Event()

    async def provider(*args, **kwargs):
        entered.set()
        try:
            await release.wait()
            return DestinationSearchResponse(
                schema_version="walksafe.destination_search.v1",
                provider="tmap_poi", query="Station", results=[],
            )
        finally:
            if not release.is_set():
                cancelled.set()

    monkeypatch.setattr(navigation, "fetch_tmap_poi_search", provider)
    application = FastAPI()
    application.include_router(navigation.create_router(settings))

    async def exercise():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=application), base_url="http://testserver",
        ) as client:
            call = asyncio.create_task(client.get("/navigation/destinations/search", params={"query": "Station"}))
            await asyncio.wait_for(entered.wait(), 2)
            await asyncio.sleep(0.02)
            assert not call.done() and not cancelled.is_set()
            release.set()
            response = await asyncio.wait_for(call, 2)
            assert response.status_code == 200
            assert response.json()["query"] == "Station"
            assert not cancelled.is_set()

    asyncio.run(exercise())


def test_cancelled_tmap_http_stops_body_and_releases_lease_after_close(monkeypatch):
    settings = _settings(monkeypatch)

    async def exercise():
        reading, closing, close_release, closed = (
            asyncio.Event(), asyncio.Event(), asyncio.Event(), asyncio.Event()
        )

        class SlowStream(httpx.AsyncByteStream):
            async def __aiter__(self):
                reading.set()
                await asyncio.Event().wait()
                yield b"never delivered"

            async def aclose(self):
                closing.set()
                await close_release.wait()
                closed.set()

        owner = TmapRuntime(timeout_seconds=4.0)
        await owner.start()
        original_client = owner.client
        owner._client = httpx.AsyncClient(
            transport=httpx.MockTransport(lambda request: httpx.Response(200, stream=SlowStream())),
            trust_env=False,
        )
        await original_client.aclose()
        call = asyncio.create_task(fetch_tmap_poi_search("Station", settings, runtime=owner))
        try:
            await asyncio.wait_for(reading.wait(), 2)
            call.cancel()
            with pytest.raises(asyncio.CancelledError):
                await asyncio.wait_for(call, 2)
            await asyncio.wait_for(closing.wait(), 2)
            assert len(owner._leases) == 1
            assert not closed.is_set()
            close_release.set()
            await asyncio.wait_for(closed.wait(), 2)
            await _eventually(lambda: not owner._leases)
            assert not owner.client.is_closed
        finally:
            close_release.set()
            call.cancel()
            await asyncio.gather(call, return_exceptions=True)
            await owner.aclose()

    asyncio.run(exercise())


def test_asgi_disconnect_closes_real_tmap_stream_before_reusing_capacity(monkeypatch):
    settings = _settings(monkeypatch)

    async def exercise():
        reading, closing, close_release, closed = (
            asyncio.Event(), asyncio.Event(), asyncio.Event(), asyncio.Event()
        )

        class SlowStream(httpx.AsyncByteStream):
            async def __aiter__(self):
                reading.set()
                await asyncio.Event().wait()
                yield b"never delivered"

            async def aclose(self):
                closing.set()
                await close_release.wait()
                closed.set()

        owner = TmapRuntime(timeout_seconds=4.0)
        await owner.start()
        original_client = owner.client
        owner._client = httpx.AsyncClient(
            transport=httpx.MockTransport(lambda request: httpx.Response(200, stream=SlowStream())),
            trust_env=False,
        )
        await original_client.aclose()
        application = FastAPI()
        application.include_router(navigation.create_router(settings, runtime_provider=lambda: owner))
        incoming = asyncio.Queue()
        incoming.put_nowait({"type": "http.request", "body": b"", "more_body": False})
        outgoing = []

        async def send(message):
            outgoing.append(message)

        scope = {
            "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
            "method": "GET", "scheme": "http", "path": "/navigation/destinations/search",
            "raw_path": b"/navigation/destinations/search", "query_string": b"query=Station",
            "root_path": "", "headers": [(b"host", b"testserver")],
            "client": ("127.0.0.1", 12345), "server": ("testserver", 80),
        }
        serving = asyncio.create_task(application(scope, incoming.get, send))
        try:
            await asyncio.wait_for(reading.wait(), 2)
            incoming.put_nowait({"type": "http.disconnect"})
            result = await asyncio.wait_for(asyncio.gather(serving, return_exceptions=True), 2)
            assert isinstance(result[0], asyncio.CancelledError)
            await asyncio.wait_for(closing.wait(), 2)
            assert len(owner._leases) == 1
            assert not closed.is_set()
            assert outgoing == []
            close_release.set()
            await asyncio.wait_for(closed.wait(), 2)
            await _eventually(lambda: not owner._leases)
        finally:
            close_release.set()
            serving.cancel()
            await asyncio.gather(serving, return_exceptions=True)
            await owner.aclose()

    asyncio.run(exercise())


def test_asgi_disconnect_closes_local_provider_socket_before_headers(monkeypatch):
    settings = _settings(monkeypatch)

    async def exercise():
        connected, provider_closed = asyncio.Event(), asyncio.Event()
        writers = []

        async def provider(reader, writer):
            writers.append(writer)
            try:
                await reader.readuntil(b"\r\n\r\n")
                if len(writers) == 1:
                    connected.set()
                    assert await reader.read() == b""
                    provider_closed.set()
                else:
                    body = b'{"searchPoiInfo":{"pois":{"poi":[]}}}'
                    writer.write(
                        b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + str(len(body)).encode() + b"\r\nConnection: close\r\n\r\n" + body
                    )
                    await writer.drain()
            finally:
                writer.close()
                await writer.wait_closed()

        server = await asyncio.start_server(provider, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        monkeypatch.setattr(settings, "tmap_poi_search_url", f"http://127.0.0.1:{port}/pois")
        owner = TmapRuntime(timeout_seconds=4.0)
        await owner.start()
        original_client = owner.client
        owner._client = httpx.AsyncClient(timeout=4.0, trust_env=False)
        await original_client.aclose()
        application = FastAPI()
        application.include_router(navigation.create_router(settings, runtime_provider=lambda: owner))
        incoming = asyncio.Queue()
        incoming.put_nowait({"type": "http.request", "body": b"", "more_body": False})
        outgoing = []

        async def send(message):
            outgoing.append(message)

        scope = {
            "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
            "method": "GET", "scheme": "http", "path": "/navigation/destinations/search",
            "raw_path": b"/navigation/destinations/search", "query_string": b"query=Station",
            "root_path": "", "headers": [(b"host", b"testserver")],
            "client": ("127.0.0.1", 12345), "server": ("testserver", 80),
        }
        serving = asyncio.create_task(application(scope, incoming.get, send))
        try:
            await asyncio.wait_for(connected.wait(), 2)
            incoming.put_nowait({"type": "http.disconnect"})
            result = await asyncio.wait_for(asyncio.gather(serving, return_exceptions=True), 2)
            assert isinstance(result[0], asyncio.CancelledError)
            await asyncio.wait_for(provider_closed.wait(), 2)
            await _eventually(lambda: not owner._leases)
            assert outgoing == []
            followup = await asyncio.wait_for(
                fetch_tmap_poi_search("Again", settings, runtime=owner), 4,
            )
            assert followup.results == []
            assert len(writers) == 2
        finally:
            serving.cancel()
            await asyncio.gather(serving, return_exceptions=True)
            for writer in writers:
                writer.close()
            await owner.aclose()
            server.close()
            await server.wait_closed()

    asyncio.run(exercise())


def test_cancelled_unstarted_owner_does_not_leak_capacity():
    async def exercise():
        owner = TmapRuntime(timeout_seconds=4.0)
        await owner.start()

        async def never_run():
            raise AssertionError("unstarted operation ran")

        current = asyncio.current_task()
        assert current is not None
        loop = asyncio.get_running_loop()
        loop.call_soon(current.cancel)
        try:
            with pytest.raises(asyncio.CancelledError):
                await owner.execute(never_run, deadline_at=monotonic() + 4.0)
            await _eventually(lambda: not owner._leases)
        finally:
            await owner.aclose()

    asyncio.run(exercise())


@pytest.mark.parametrize("interruption", ["deadline", "cancel"])
def test_partial_ipc_frame_does_not_block_loop_peer_or_interruption(monkeypatch, interruption):
    entered = multiprocessing.get_context("spawn").Event()
    monkeypatch.setattr(runtime_module, "_run_isolated_response_job", _partial_ipc_worker)

    async def exercise():
        owner = TmapRuntime(timeout_seconds=4.0, worker_test_hooks=entered)
        await owner.start()
        deadline = monotonic() + 3.0
        arguments = {"query": "Peer", "limit": 5, "origin_lat": None, "origin_lng": None, "mock": False}

        async def process(body, current_deadline):
            return await owner.process_response(
                "poi", 200, body, arguments, deadline_at=current_deadline,
            )

        blocked = asyncio.create_task(owner.execute(
            lambda: process(b"partial", deadline), deadline_at=deadline,
        ))
        try:
            await _eventually(entered.is_set)
            job = next(iter(owner._executor._jobs.values()))
            pid = job.process.pid
            assert pid is not None and job.process.is_alive()
            heartbeat = asyncio.Event()

            async def beat():
                await asyncio.sleep(0.02)
                heartbeat.set()

            await asyncio.wait_for(beat(), 0.3)
            assert heartbeat.is_set()
            peer_deadline = monotonic() + 2.0
            peer = await asyncio.wait_for(owner.execute(
                lambda: process(b'{"searchPoiInfo":{"pois":{"poi":[]}}}', peer_deadline),
                deadline_at=peer_deadline,
            ), 2.0)
            assert peer.results == []
            if interruption == "cancel":
                blocked.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await asyncio.wait_for(blocked, 2.0)
            else:
                with pytest.raises(TimeoutError):
                    await asyncio.wait_for(blocked, 3.0)
            await _eventually(lambda: not owner._leases)
            assert not job.process.is_alive()
            assert job.receiver.closed
            assert job.reader.done()
            assert owner._executor._jobs == {}
        finally:
            blocked.cancel()
            await asyncio.gather(blocked, return_exceptions=True)
            await owner.aclose()

    asyncio.run(exercise())


def test_default_thread_pool_saturation_does_not_starve_tmap_readers(monkeypatch):
    entered = multiprocessing.get_context("spawn").Event()
    monkeypatch.setattr(runtime_module, "_run_isolated_response_job", _partial_ipc_worker)

    async def exercise():
        loop = asyncio.get_running_loop()
        default_pool = ThreadPoolExecutor(max_workers=1)
        loop.set_default_executor(default_pool)
        default_started = threading.Event()
        default_release = threading.Event()

        def hold_default_pool():
            default_started.set()
            default_release.wait()

        occupied = loop.run_in_executor(None, hold_default_pool)
        await _eventually(default_started.is_set)
        owner = TmapRuntime(timeout_seconds=4.0, worker_test_hooks=entered)
        await owner.start()
        arguments = {"query": "Peer", "limit": 5, "origin_lat": None, "origin_lng": None, "mock": False}

        async def process(body, deadline):
            return await owner.process_response(
                "poi", 200, body, arguments, deadline_at=deadline,
            )

        blocked = None
        try:
            for _ in range(2):
                deadline = monotonic() + 1.5
                result = await asyncio.wait_for(owner.execute(
                    lambda: process(b'{"searchPoiInfo":{"pois":{"poi":[]}}}', deadline),
                    deadline_at=deadline,
                ), 2.0)
                assert result.results == []
                assert not default_release.is_set()
            deadline = monotonic() + 3.0
            blocked = asyncio.create_task(owner.execute(
                lambda: process(b"partial", deadline), deadline_at=deadline,
            ))
            await _eventually(entered.is_set)
            job = next(iter(owner._executor._jobs.values()))
            blocked.cancel()
            with pytest.raises(asyncio.CancelledError):
                await asyncio.wait_for(blocked, 2.0)
            await _eventually(lambda: not owner._leases)
            assert job.reader.done() and job.receiver.closed and not job.process.is_alive()
            reader_threads = [
                thread for thread in threading.enumerate()
                if thread.name.startswith("tmap-response-reader")
            ]
            assert reader_threads
            await owner.aclose()
            assert all(not thread.is_alive() for thread in reader_threads)
        finally:
            default_release.set()
            if blocked is not None:
                blocked.cancel()
                await asyncio.gather(blocked, return_exceptions=True)
            await occupied
            if not owner._closed:
                try:
                    await owner.aclose()
                except Exception:
                    pass

    asyncio.run(exercise())
