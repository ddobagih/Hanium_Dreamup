"""One direct characterization of confirmed headers followed by a stalled body."""

import asyncio

import httpx
import pytest

from backend.tests.test_navigation_route_health_state import VALID, fetch, owned_runtime, settings
from backend.app.services.tmap_runtime import TmapRuntimeError
from backend.app.services import tmap_response_normalization as response_normalization
from backend.app.services import tmap_pedestrian as service


def test_confirmed_provider_headers_with_stalled_body_records_failure():
    async def run():
        received = False
        class StalledBody(httpx.AsyncByteStream):
            async def __aiter__(self):
                nonlocal received
                received = True
                await asyncio.Event().wait()
                yield b""
        service.reset_tmap_success_cache()
        try:
            async with owned_runtime(lambda _: httpx.Response(200, stream=StalledBody())) as (runtime, client):
                with pytest.raises(service.TmapPedestrianRouteError) as caught:
                    await fetch(settings(tmap_timeout_seconds=0.1), runtime, client)
                assert caught.value.code == "tmap_timeout" and received
                state = service.tmap_dependency_readiness(30.0)
                print("confirmed_headers_body_deadline", state)
                assert state["route_recent"] is False
                assert state["route_state"] == "recent_failure"
        finally:
            service.reset_tmap_success_cache()
    asyncio.run(run())


@pytest.mark.parametrize("error,expected", [
    (httpx.ConnectTimeout, "recent_failure"),
    (httpx.ReadTimeout, "recent_failure"),
    (httpx.RemoteProtocolError, "recent_failure"),
    (httpx.PoolTimeout, "unverified"),
    (httpx.LocalProtocolError, "unverified"),
    (httpx.UnsupportedProtocol, "unverified"),
])
def test_remote_transport_errors_and_local_transport_errors_remain_distinct(error, expected):
    async def run():
        def handler(request):
            raise error("synthetic transport boundary", request=request)
        service.reset_tmap_success_cache()
        try:
            async with owned_runtime(handler) as (runtime, client):
                with pytest.raises(service.TmapPedestrianRouteError):
                    await fetch(settings(), runtime, client)
                assert service.tmap_dependency_readiness(30.0)["route_state"] == expected
        finally:
            service.reset_tmap_success_cache()
    asyncio.run(run())


def test_completed_body_waiting_for_local_cleanup_does_not_forge_failure():
    async def run():
        entered, release = asyncio.Event(), asyncio.Event()
        class PendingClose(httpx.AsyncByteStream):
            async def __aiter__(self):
                yield VALID
            async def aclose(self):
                entered.set()
                await release.wait()
        service.reset_tmap_success_cache()
        try:
            async with owned_runtime(lambda _: httpx.Response(200, stream=PendingClose())) as (runtime, client):
                try:
                    with pytest.raises(service.TmapPedestrianRouteError) as caught:
                        await fetch(settings(tmap_timeout_seconds=0.1), runtime, client)
                    assert caught.value.code == "tmap_timeout" and entered.is_set()
                    assert service.tmap_dependency_readiness(30.0)["route_state"] == "unverified"
                finally:
                    release.set()
        finally:
            service.reset_tmap_success_cache()
    asyncio.run(run())


def test_capacity_rejection_does_not_start_a_new_provider_check():
    async def run():
        calls = 0
        both_entered = asyncio.Event()
        async def handler(_):
            nonlocal calls
            calls += 1
            if calls == 2:
                both_entered.set()
            await asyncio.Event().wait()
        service.reset_tmap_success_cache()
        try:
            async with owned_runtime(handler) as (runtime, client):
                pending = [asyncio.create_task(fetch(settings(), runtime, client)) for _ in range(2)]
                try:
                    await both_entered.wait()
                    with pytest.raises(service.TmapPedestrianRouteError) as caught:
                        await fetch(settings(), runtime, client)
                    assert caught.value.code == "tmap_capacity_exceeded" and calls == 2
                    assert service.tmap_dependency_readiness(30.0)["route_state"] == "unverified"
                finally:
                    for task in pending:
                        task.cancel()
                    await asyncio.gather(*pending, return_exceptions=True)
        finally:
            service.reset_tmap_success_cache()
    asyncio.run(run())


def test_confirmed_failure_expires_using_the_existing_observation_window(monkeypatch):
    async def run():
        def handler(request):
            raise httpx.ConnectError("synthetic transport boundary", request=request)
        service.reset_tmap_success_cache()
        try:
            async with owned_runtime(handler) as (runtime, client):
                with pytest.raises(service.TmapPedestrianRouteError):
                    await fetch(settings(), runtime, client)
                now = service.monotonic()
                assert service.tmap_dependency_readiness(30.0, now=now)["route_state"] == "recent_failure"
                assert service.tmap_dependency_readiness(30.0, now=now+31.0)["route_state"] == "unverified"
                assert service.tmap_dependency_readiness(30.0, now=now+31.0)["route_recent"] is False
        finally:
            service.reset_tmap_success_cache()
    asyncio.run(run())


def test_body_completed_before_synchronous_deadline_check_does_not_forge_failure(monkeypatch):
    async def run():
        clock = service.monotonic
        after_deadline = clock() + 10.0
        body_completed = False
        class CompleteBody(httpx.AsyncByteStream):
            async def __aiter__(self):
                nonlocal body_completed
                yield VALID
                body_completed = True
                # Reach the original service deadline check after raw EOF,
                # while the runtime's real wall-clock deadline is still live.
                monkeypatch.setattr(service, "monotonic", lambda: after_deadline)
                monkeypatch.setattr(response_normalization, "monotonic", lambda: after_deadline)
        service.reset_tmap_success_cache()
        try:
            async with owned_runtime(lambda _: httpx.Response(200, stream=CompleteBody())) as (runtime, client):
                with pytest.raises(service.TmapPedestrianRouteError) as caught:
                    await fetch(settings(), runtime, client)
                assert caught.value.code == "tmap_timeout" and body_completed
                state = service.tmap_dependency_readiness(30.0)
                print("raw_EOF_then_synchronous_deadline", state)
                assert state["route_recent"] is False
                assert state["route_state"] == "unverified"
        finally:
            service.reset_tmap_success_cache()
            monkeypatch.setattr(service, "monotonic", clock)
            monkeypatch.setattr(response_normalization, "monotonic", clock)
    asyncio.run(run())
