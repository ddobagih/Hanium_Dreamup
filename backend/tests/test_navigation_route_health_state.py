"""S07-31: confirmed route dependency observations, without cold admission gates."""

import asyncio
from contextlib import asynccontextmanager
import json
from pathlib import Path
from types import SimpleNamespace

from fastapi import FastAPI
import httpx
import pytest

from backend.app.api.navigation import create_router
from backend.app.schemas import WalkingRouteRequest
from backend.app.services import tmap_pedestrian as service
from backend.app.services.tmap_runtime import TmapRuntime, TmapRuntimeError


CAPTURE = Path(__file__).resolve().parent / "fixtures/road_type_worker"
VALID = (CAPTURE / "road-16.provider-input.json").read_bytes()
INVALID = (CAPTURE / "raw-provider-null.provider-input.json").read_bytes()
REQUEST_BODY = json.loads((CAPTURE / "request.json").read_bytes())
REQUEST = WalkingRouteRequest.model_validate(REQUEST_BODY)


def settings(**changes):
    values = dict(walking_route_provider="tmap_pedestrian", tmap_app_key="local-test-key",
                  tmap_readiness_success_max_age_seconds=30.0, tmap_timeout_seconds=4.0,
                  tmap_pedestrian_speed_kmh=4, tmap_pedestrian_api_version="1",
                  tmap_pedestrian_route_url="http://provider.test/route")
    values.update(changes)
    return SimpleNamespace(**values)


@pytest.fixture(autouse=True)
def clean_observations():
    service.reset_tmap_success_cache()
    yield
    service.reset_tmap_success_cache()


@asynccontextmanager
async def owned_runtime(handler):
    runtime = TmapRuntime(timeout_seconds=4.0)
    await runtime.start()
    try:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            yield runtime, client
    finally:
        await runtime.aclose()


async def health(config, runtime=None):
    app = FastAPI()
    app.include_router(create_router(config, runtime_provider=lambda: runtime))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://app.test") as client:
        response = await client.get("/navigation/walking/health")
        assert response.status_code == 200
        return response.json()


async def fetch(config, runtime, client):
    return await service.fetch_tmap_pedestrian_route(REQUEST, config, runtime=runtime, http_client=client)


def assert_unverified():
    observed = service.tmap_dependency_readiness(30.0)
    assert observed["route_recent"] is False
    assert observed["route_state"] == "unverified"


@pytest.mark.parametrize("failure", ["invalid_response", "transport"])
def test_configured_actual_provider_failure_differs_from_never_checked(failure):
    async def run():
        def handler(request):
            if failure == "transport":
                raise httpx.ConnectError("synthetic provider transport failure", request=request)
            return httpx.Response(200, content=INVALID)
        config = settings()
        async with owned_runtime(handler) as (runtime, client):
            before = await health(config, runtime)
            with pytest.raises(service.TmapPedestrianRouteError) as caught:
                await fetch(config, runtime, client)
            expected = "invalid_tmap_response" if failure == "invalid_response" else "tmap_network_error"
            assert caught.value.code == expected
            after = await health(config, runtime)
            print(json.dumps({"case": failure, "before": before, "after": after}, ensure_ascii=False))
            # Existing public health is identical here before the fix.
            assert before != after, "confirmed provider failure still looks never checked"
            assert before["configured"] is True
            assert before["dependency_state"] == "unverified"
            assert after["dependency_state"] == "recent_failure"
            assert after["status"] == "unavailable"
            assert after["reason"] == "tmap_route_recent_success_unavailable"
    asyncio.run(run())


def test_validated_success_then_failure_keeps_the_previous_fresh_success():
    async def run():
        responses = iter((VALID, INVALID))
        async with owned_runtime(lambda _: httpx.Response(200, content=next(responses))) as (runtime, client):
            config = settings()
            route = await fetch(config, runtime, client)
            assert route.steps[0].road_type == 16
            success = await health(config, runtime)
            assert success["dependency_state"] == "recent_success"
            assert success["status"] == "ready"
            with pytest.raises(service.TmapPedestrianRouteError):
                await fetch(config, runtime, client)
            failure = await health(config, runtime)
            assert failure["dependency_state"] == "recent_failure"
            assert failure["status"] == "ready"
            assert failure["reason"] is None
            assert service.tmap_dependency_readiness(30.0)["route_recent"] is True
    asyncio.run(run())


@pytest.mark.parametrize("rejection", ["missing_key", "unstarted_runtime", "budget", "pool_timeout"])
def test_local_rejection_does_not_forge_provider_failure(rejection):
    async def run():
        calls = 0
        def handler(request):
            nonlocal calls
            calls += 1
            if rejection == "pool_timeout":
                raise httpx.PoolTimeout("local pool wait", request=request)
            return httpx.Response(200, content=VALID)
        config = settings(tmap_app_key="" if rejection == "missing_key" else "local-test-key")
        async with owned_runtime(handler) as (runtime, client):
            target = TmapRuntime(timeout_seconds=4.0) if rejection == "unstarted_runtime" else runtime
            if rejection == "budget":
                config.tmap_call_budget_per_minute = 1
                runtime.consume_call_budget("route", config)
            with pytest.raises(service.TmapPedestrianRouteError):
                await fetch(config, target, client)
            assert calls == (1 if rejection == "pool_timeout" else 0)
            assert_unverified()
            state = await health(config, runtime)
            assert state["configured"] is (rejection != "missing_key")
            assert state["dependency_state"] == "unverified"
            if rejection == "missing_key":
                assert state["reason"] == "tmap_app_key_missing"
    asyncio.run(run())


@pytest.mark.parametrize("local_error", ["worker", "worker_timeout"])
def test_http_200_before_local_processing_failure_is_not_provider_success_or_failure(local_error, monkeypatch):
    async def run():
        async with owned_runtime(lambda _: httpx.Response(200, content=VALID)) as (runtime, client):
            async def fail(*args, **kwargs):
                if local_error == "worker_timeout":
                    raise TimeoutError("local processing deadline")
                raise TmapRuntimeError(code="tmap_processing_unavailable", message="TMAP request processing is unavailable.")
            monkeypatch.setattr(runtime, "process_response", fail)
            with pytest.raises(service.TmapPedestrianRouteError):
                await fetch(settings(), runtime, client)
            assert_unverified()
    asyncio.run(run())


@pytest.mark.parametrize("body", [VALID, INVALID], ids=["late_success", "late_failure"])
def test_reset_fences_inflight_result_including_success_cache(body):
    async def run():
        entered, release = asyncio.Event(), asyncio.Event()
        async def handler(_):
            entered.set()
            await release.wait()
            return httpx.Response(200, content=body)
        async with owned_runtime(handler) as (runtime, client):
            pending = asyncio.create_task(fetch(settings(), runtime, client))
            await entered.wait()
            service.reset_tmap_success_cache()
            release.set()
            if body == INVALID:
                with pytest.raises(service.TmapPedestrianRouteError):
                    await pending
            else:
                assert (await pending).steps[0].road_type == 16
            assert_unverified()
    asyncio.run(run())


def test_stopped_runtime_does_not_repopulate_evidence_from_an_admitted_result():
    async def run():
        entered, release = asyncio.Event(), asyncio.Event()
        async def handler(_):
            entered.set()
            await release.wait()
            return httpx.Response(200, content=VALID)
        async with owned_runtime(handler) as (runtime, client):
            pending = asyncio.create_task(fetch(settings(), runtime, client))
            await entered.wait()
            runtime.stop_admission()
            release.set()
            assert (await pending).steps[0].road_type == 16
            assert_unverified()
    asyncio.run(run())


@pytest.mark.parametrize("newer_success", [True, False])
def test_older_completion_does_not_replace_newer_confirmed_observation(newer_success):
    async def run():
        entered, release = asyncio.Event(), asyncio.Event()
        calls = 0
        async def handler(_):
            nonlocal calls
            calls += 1
            if calls == 1:
                entered.set()
                await release.wait()
                return httpx.Response(200, content=INVALID if newer_success else VALID)
            return httpx.Response(200, content=VALID if newer_success else INVALID)
        async with owned_runtime(handler) as (runtime, client):
            older = asyncio.create_task(fetch(settings(), runtime, client))
            await entered.wait()
            if newer_success:
                await fetch(settings(), runtime, client)
            else:
                with pytest.raises(service.TmapPedestrianRouteError):
                    await fetch(settings(), runtime, client)
            expected = "recent_success" if newer_success else "recent_failure"
            assert service.tmap_dependency_readiness(30.0)["route_state"] == expected
            release.set()
            if newer_success:
                with pytest.raises(service.TmapPedestrianRouteError):
                    await older
            else:
                await older
            observed = service.tmap_dependency_readiness(30.0)
            assert observed["route_state"] == expected
            assert observed["route_recent"] is True
    asyncio.run(run())


@pytest.mark.parametrize("before_contact", [True, False])
def test_cancelled_request_does_not_confirm_a_provider_failure(before_contact):
    async def run():
        entered = asyncio.Event()
        calls = 0
        async def handler(_):
            nonlocal calls
            calls += 1
            entered.set()
            await asyncio.Event().wait()
        async with owned_runtime(handler) as (runtime, client):
            pending = asyncio.create_task(fetch(settings(), runtime, client))
            if not before_contact:
                await entered.wait()
            pending.cancel()
            with pytest.raises(asyncio.CancelledError):
                await pending
            assert calls == (0 if before_contact else 1)
            assert_unverified()
    asyncio.run(run())


def test_existing_tokenless_helper_freshness_and_reset_contract():
    service._record_tmap_success("route", now=100.0)
    service._record_tmap_success("poi", now=105.0)
    observed = service.tmap_dependency_readiness(30.0, now=110.0)
    assert observed["ready"] is True
    assert observed["route_state"] == "recent_success"
    assert service.tmap_dependency_readiness(30.0, now=131.0)["route_state"] == "unverified"
    service.reset_tmap_success_cache()
    assert_unverified()
    service._record_tmap_success("route")
    assert service.tmap_dependency_readiness(30.0)["route_state"] == "recent_success"


def test_invalid_caller_and_unsupported_provider_keep_their_existing_http_contract():
    async def run():
        config = settings()
        app = FastAPI()
        app.include_router(create_router(config))
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://app.test") as client:
            assert (await client.post("/navigation/walking", json={})).status_code == 422
            config.walking_route_provider = "unsupported"
            response = await client.get("/navigation/walking/health")
            assert response.status_code == 503
            assert response.json()["detail"]["code"] == "walking_route_provider_invalid"
        assert_unverified()
    asyncio.run(run())
