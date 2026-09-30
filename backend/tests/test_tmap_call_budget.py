"""S20-29: Backend-wide TMAP call budget (per minute and per day) shared by route and POI."""
from __future__ import annotations

import asyncio
from types import SimpleNamespace

import httpx
import pytest

from backend.app.config import Settings
from backend.app.services import tmap_runtime as runtime_module
from backend.app.services.tmap_pedestrian import (
    TmapPoiSearchError,
    TmapPedestrianRouteError,
    fetch_tmap_poi_search,
    fetch_tmap_pedestrian_route,
)
from backend.app.services.tmap_runtime import TmapRuntime, TmapRuntimeError
from backend.app.schemas import RoutePoint, WalkingRouteRequest


class _Clock:
    def __init__(self) -> None:
        self.now = 1_000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def clock(monkeypatch: pytest.MonkeyPatch) -> _Clock:
    value = _Clock()
    monkeypatch.setattr(runtime_module, "monotonic", value)
    return value


def _budget(per_minute: int, per_day: int) -> SimpleNamespace:
    return SimpleNamespace(tmap_call_budget_per_minute=per_minute, tmap_call_budget_per_day=per_day)


def test_s20_29_defaults_are_conservative_and_bounded() -> None:
    assert runtime_module.TMAP_CALL_BUDGET_PER_MINUTE_DEFAULT == 60
    assert runtime_module.TMAP_CALL_BUDGET_PER_DAY_DEFAULT == 5_000
    settings = Settings()
    assert settings.tmap_call_budget_per_minute == 60
    assert settings.tmap_call_budget_per_day == 5_000


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("TMAP_CALL_BUDGET_PER_MINUTE", "0"),
        ("TMAP_CALL_BUDGET_PER_MINUTE", "-1"),
        ("TMAP_CALL_BUDGET_PER_MINUTE", "6001"),
        ("TMAP_CALL_BUDGET_PER_DAY", "0"),
        ("TMAP_CALL_BUDGET_PER_DAY", "5000001"),
        ("TMAP_CALL_BUDGET_PER_DAY", "59"),
    ],
)
def test_s20_29_invalid_budget_settings_refuse_startup(
    monkeypatch: pytest.MonkeyPatch, name: str, value: str,
) -> None:
    monkeypatch.setenv(name, value)
    with pytest.raises(ValueError, match=name):
        Settings()


def test_s20_29_minute_window_is_shared_by_route_and_poi(clock: _Clock) -> None:
    runtime = TmapRuntime(timeout_seconds=1.0)
    settings = _budget(3, 100)
    runtime.consume_call_budget("route", settings)
    runtime.consume_call_budget("poi", settings)
    runtime.consume_call_budget("route", settings)
    for mode, label in (("poi", "POI search"), ("route", "pedestrian route")):
        with pytest.raises(TmapRuntimeError) as exhausted:
            runtime.consume_call_budget(mode, settings)
        assert exhausted.value.code == "tmap_rate_limited"
        assert exhausted.value.http_status == 503
        assert exhausted.value.message == f"TMAP {label} request limit was reached."
    # A refused attempt does not consume budget; the window reopens after 60 s.
    clock.now += 59.5
    with pytest.raises(TmapRuntimeError):
        runtime.consume_call_budget("route", settings)
    clock.now += 0.5
    for _ in range(3):
        runtime.consume_call_budget("route", settings)
    with pytest.raises(TmapRuntimeError):
        runtime.consume_call_budget("poi", settings)


def test_s20_29_day_window_limits_across_minutes(clock: _Clock) -> None:
    runtime = TmapRuntime(timeout_seconds=1.0)
    settings = _budget(60, 120)
    for _minute in range(2):
        for _ in range(60):
            runtime.consume_call_budget("route", settings)
        clock.now += 60.0
    with pytest.raises(TmapRuntimeError) as exhausted:
        runtime.consume_call_budget("poi", settings)
    assert exhausted.value.code == "tmap_rate_limited"
    clock.now += 86_400.0 - 120.0 - 0.5
    with pytest.raises(TmapRuntimeError):
        runtime.consume_call_budget("route", settings)
    clock.now += 0.5
    runtime.consume_call_budget("route", settings)


@pytest.mark.parametrize("value", [0, -1, True, 1.5, None, "60"])
def test_s20_29_invalid_runtime_limit_fails_closed(clock: _Clock, value: object) -> None:
    runtime = TmapRuntime(timeout_seconds=1.0)
    with pytest.raises(TmapRuntimeError) as unavailable:
        runtime.consume_call_budget("route", _budget(value, 100))  # type: ignore[arg-type]
    assert unavailable.value.code == "tmap_processing_unavailable"


def test_s20_29_settings_double_without_budget_uses_the_defaults(clock: _Clock) -> None:
    runtime = TmapRuntime(timeout_seconds=1.0)
    for _ in range(runtime_module.TMAP_CALL_BUDGET_PER_MINUTE_DEFAULT):
        runtime.consume_call_budget("poi", SimpleNamespace())
    with pytest.raises(TmapRuntimeError):
        runtime.consume_call_budget("poi", SimpleNamespace())


def _route_request() -> WalkingRouteRequest:
    return WalkingRouteRequest(
        origin=RoutePoint(latitude=37.5662952, longitude=126.9779451, name="start"),
        destination=RoutePoint(latitude=37.5664852, longitude=126.9781451, name="end"),
        priority="STAIR_AVOID",
    )


def _provider_settings(per_minute: int) -> SimpleNamespace:
    return SimpleNamespace(
        walking_route_provider="tmap_pedestrian",
        tmap_app_key="fixture-key",
        tmap_pedestrian_api_version="1",
        tmap_pedestrian_speed_kmh=4.0,
        tmap_pedestrian_route_url="https://provider.invalid/pedestrian",
        tmap_poi_search_url="https://provider.invalid/pois",
        tmap_poi_provider="live",
        tmap_timeout_seconds=5.0,
        tmap_call_budget_per_minute=per_minute,
        tmap_call_budget_per_day=100,
    )


def test_s20_29_exhausted_budget_blocks_route_and_poi_before_provider_io() -> None:
    provider_calls: list[str] = []

    def transport(request: httpx.Request) -> httpx.Response:
        provider_calls.append(request.url.path)
        return httpx.Response(500, json={"error": {"code": "SYNTHETIC"}})

    async def run() -> None:
        runtime = TmapRuntime(timeout_seconds=5.0)
        await runtime.start()
        try:
            settings = _provider_settings(per_minute=1)
            async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
                # The first call spends the only unit and reaches the provider.
                with pytest.raises(TmapPedestrianRouteError) as provider_error:
                    await fetch_tmap_pedestrian_route(
                        _route_request(), settings, http_client=client, runtime=runtime,
                    )
                assert provider_error.value.code == "tmap_provider_error"
                assert provider_calls == ["/pedestrian"]
                with pytest.raises(TmapPedestrianRouteError) as route_limited:
                    await fetch_tmap_pedestrian_route(
                        _route_request(), settings, http_client=client, runtime=runtime,
                    )
                assert route_limited.value.code == "tmap_rate_limited"
                assert route_limited.value.http_status == 503
                with pytest.raises(TmapPoiSearchError) as poi_limited:
                    await fetch_tmap_poi_search(
                        "서울역", settings, limit=3, http_client=client, runtime=runtime,
                    )
                assert poi_limited.value.code == "tmap_rate_limited"
                assert poi_limited.value.http_status == 503
                # No new provider request was sent after the budget was exhausted.
                assert provider_calls == ["/pedestrian"]
        finally:
            runtime.stop_admission()
            await runtime.aclose()

    asyncio.run(run())


# --- S20-29 / R9 (be-F1): readiness probe allowance is separate from users ---

from time import monotonic as real_monotonic  # noqa: E402

from backend.app.api import health as health_api  # noqa: E402
from backend.app.services import tmap_pedestrian as pedestrian_module  # noqa: E402
from backend.app.services.tmap_runtime import (  # noqa: E402
    TmapCallBudgetExhausted,
    TmapReadinessProbeDeferred,
)


@pytest.fixture
def shared_clock(monkeypatch: pytest.MonkeyPatch) -> _Clock:
    value = _Clock()
    # Spawned response workers compare deadlines with their own real clock.
    # A whole-second base keeps the fake arithmetic exact.
    value.now = float(int(real_monotonic()))
    monkeypatch.setattr(runtime_module, "monotonic", value)
    monkeypatch.setattr(pedestrian_module, "monotonic", value)
    pedestrian_module.reset_tmap_success_cache()
    yield value
    pedestrian_module.reset_tmap_success_cache()


def _readiness_settings(per_minute: int, per_day: int) -> SimpleNamespace:
    settings = _provider_settings(per_minute)
    settings.tmap_call_budget_per_day = per_day
    settings.tmap_readiness_live_probe_enabled = True
    settings.tmap_readiness_probe_timeout_seconds = 3.0
    settings.tmap_readiness_success_max_age_seconds = 300.0
    return settings


def _untouched_user_budget(runtime: TmapRuntime) -> bool:
    return runtime._call_budget == {"minute": [None, 0], "day": [None, 0]}


def test_s20_29_probe_allowance_is_one_run_per_interval_and_ignores_user_windows(
    shared_clock: _Clock,
) -> None:
    assert runtime_module.TMAP_READINESS_PROBE_INTERVAL_SECONDS == 60.0
    runtime = TmapRuntime(timeout_seconds=1.0)
    settings = _budget(1, 1)
    runtime.consume_call_budget("route", settings)  # user windows now exhausted
    runtime.reserve_readiness_probe()  # not blocked by the user windows
    with pytest.raises(TmapReadinessProbeDeferred) as deferred:
        runtime.reserve_readiness_probe()
    assert deferred.value.code == "tmap_readiness_probe_deferred"
    assert deferred.value.next_probe_in_seconds == 60
    shared_clock.now += 59.5
    with pytest.raises(TmapReadinessProbeDeferred) as deferred:
        runtime.reserve_readiness_probe()
    assert deferred.value.next_probe_in_seconds == 1
    shared_clock.now += 0.5
    runtime.reserve_readiness_probe()
    # The probe allowance never spent the user windows.
    assert runtime._call_budget["minute"][1] == 1
    assert runtime._call_budget["day"][1] == 1


def test_s20_29_call_budget_exhaustion_view_is_read_only(shared_clock: _Clock) -> None:
    runtime = TmapRuntime(timeout_seconds=1.0)
    settings = _budget(2, 3)
    assert runtime.call_budget_exhaustion(settings) is None
    assert _untouched_user_budget(runtime)  # the view does not open windows
    runtime.consume_call_budget("route", settings)
    assert runtime.call_budget_exhaustion(settings) is None
    runtime.consume_call_budget("poi", settings)
    exhausted = runtime.call_budget_exhaustion(settings)
    assert isinstance(exhausted, TmapCallBudgetExhausted)
    assert exhausted.exhausted_windows == ("minute",)
    assert exhausted.resets_in_seconds == 60
    shared_clock.now += 60.0
    assert runtime.call_budget_exhaustion(settings) is None
    runtime.consume_call_budget("route", settings)
    shared_clock.now += 10.0
    exhausted = runtime.call_budget_exhaustion(settings)
    assert exhausted.exhausted_windows == ("day",)
    assert exhausted.resets_in_seconds == 86_400 - 70
    assert runtime._call_budget["minute"][1] == 1
    with pytest.raises(TmapRuntimeError) as invalid:
        runtime.call_budget_exhaustion(_budget(0, 3))
    assert invalid.value.code == "tmap_processing_unavailable"


def test_s20_29_outage_readiness_probes_do_not_reduce_the_user_budget(
    shared_clock: _Clock,
) -> None:
    provider_calls: list[str] = []
    outage = {"on": True}

    def transport(request: httpx.Request) -> httpx.Response:
        provider_calls.append(request.url.path)
        if outage["on"]:
            raise httpx.ConnectError("synthetic outage", request=request)
        return httpx.Response(500, json={"error": {"code": "SYNTHETIC"}})

    async def run() -> None:
        runtime = TmapRuntime(timeout_seconds=5.0)
        await runtime.start()
        real_client = runtime._client
        runtime._client = httpx.AsyncClient(transport=httpx.MockTransport(transport))
        try:
            # A tiny user budget: any probe spending would exhaust it at once.
            settings = _readiness_settings(per_minute=1, per_day=1)
            reasons: dict[str, int] = {}
            for _poll in range(120):  # 10 minutes of /ready at the 5 s cadence
                result = await health_api._navigation_readiness(
                    settings, runtime_provider=lambda: runtime,
                )
                assert result["ready"] is False
                reasons[result["reason"]] = reasons.get(result["reason"], 0) + 1
                shared_clock.now += 5.0
            # One admitted probe run per 60 s; the route call fails, POI is skipped.
            assert reasons == {"tmap_live_probe_failed": 10, "tmap_live_probe_deferred": 110}
            assert provider_calls == ["/pedestrian"] * 10
            assert _untouched_user_budget(runtime)
            # Users are not refused because of probe traffic: the user call
            # reaches the provider and spends exactly its own unit.
            outage["on"] = False
            with pytest.raises(TmapPedestrianRouteError) as user_error:
                await fetch_tmap_pedestrian_route(_route_request(), settings, runtime=runtime)
            assert user_error.value.code == "tmap_provider_error"
            assert provider_calls[-1] == "/pedestrian" and len(provider_calls) == 11
            assert runtime._call_budget["minute"][1] == 1
            assert runtime._call_budget["day"][1] == 1
            # Only the user's own exhausted budget refuses the next user call.
            with pytest.raises(TmapPedestrianRouteError) as limited:
                await fetch_tmap_pedestrian_route(_route_request(), settings, runtime=runtime)
            assert limited.value.code == "tmap_rate_limited"
            assert limited.value.http_status == 503
            assert len(provider_calls) == 11
        finally:
            await runtime._client.aclose()
            runtime._client = real_client
            runtime.stop_admission()
            await runtime.aclose()

    asyncio.run(run())


def test_s20_29_user_budget_exhaustion_is_a_distinct_navigation_reason(
    shared_clock: _Clock,
) -> None:
    provider_calls: list[str] = []

    def transport(request: httpx.Request) -> httpx.Response:
        provider_calls.append(request.url.path)
        return httpx.Response(500, json={"error": {"code": "SYNTHETIC"}})

    async def run() -> None:
        runtime = TmapRuntime(timeout_seconds=5.0)
        await runtime.start()
        real_client = runtime._client
        runtime._client = httpx.AsyncClient(transport=httpx.MockTransport(transport))
        try:
            settings = _readiness_settings(per_minute=1, per_day=2)
            with pytest.raises(TmapPedestrianRouteError):
                await fetch_tmap_pedestrian_route(_route_request(), settings, runtime=runtime)
            assert provider_calls == ["/pedestrian"]
            result = await health_api._navigation_readiness(
                settings, runtime_provider=lambda: runtime,
            )
            assert result["ready"] is False
            assert result["reason"] == "tmap_call_budget_exhausted"
            assert result["reason"] != "tmap_live_probe_failed"
            assert result["exhausted_windows"] == ["minute"]
            assert result["resets_in_seconds"] == 60
            assert result["live_probe_enabled"] is True
            # No provider I/O and no probe allowance spent while users are refused.
            assert provider_calls == ["/pedestrian"]
            assert runtime._readiness_probe_at is None
            shared_clock.now += 60.0
            with pytest.raises(TmapPedestrianRouteError):
                await fetch_tmap_pedestrian_route(_route_request(), settings, runtime=runtime)
            shared_clock.now += 60.0
            result = await health_api._navigation_readiness(
                settings, runtime_provider=lambda: runtime,
            )
            assert result["reason"] == "tmap_call_budget_exhausted"
            assert result["exhausted_windows"] == ["day"]
            assert result["resets_in_seconds"] == 86_400 - 120
            assert len(provider_calls) == 2
        finally:
            await runtime._client.aclose()
            runtime._client = real_client
            runtime.stop_admission()
            await runtime.aclose()

    asyncio.run(run())
