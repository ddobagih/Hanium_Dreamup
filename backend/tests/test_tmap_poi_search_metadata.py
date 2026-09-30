"""S06 destination search metadata: candidate identity, provider counts and distance kind.

Synthetic fixtures only; no live TMAP request is made.
"""

import asyncio
from copy import deepcopy
from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI

from backend.app.api import navigation
from backend.app.services.tmap_pedestrian import (
    _normalize_poi_search_response,
    fetch_tmap_poi_search,
)
from backend.app.services.tmap_runtime import TmapRuntime


PLACE = {
    "id": "provider-place",
    "pkey": "provider-place-entrance-1",
    "name": "Fixture Mart Station Branch",
    "noorLat": "37.5",
    "noorLon": "127.1",
    "upperAddrName": "Fixture City",
    "lowerAddrName": "Fixture Dong",
    "firstNo": "10",
    "roadName": "Fixture Road",
    "firstBuildNo": "15",
    "bizCatName": "Convenience",
}


def _normalize(items, total_count=None):
    search_info = {"pois": {"poi": items}}
    if total_count is not None:
        search_info["totalCount"] = total_count
    return _normalize_poi_search_response({"searchPoiInfo": search_info}, "fixture")


# S06-15: display fields must not create a new candidate identity.
@pytest.mark.parametrize("display_change", [
    {"roadName": "Fixture-ro"},
    {"newAddressList": {"newAddress": [{"fullAddressRoad": "Fixture City Fixture Road 15"}]}},
    {"upperAddrName": "Fixture Metropolitan City"},
    {"bizCatName": "Retail"},
    {"bizCatName": None, "catName": "Store"},
])
def test_display_only_changes_keep_candidate_identity(display_change):
    changed = {**deepcopy(PLACE), **display_change}

    original = _normalize([PLACE]).results[0]
    updated = _normalize([changed]).results[0]

    assert (original.address, original.road_address, original.category) != (
        updated.address, updated.road_address, updated.category,
    )
    assert updated.id == original.id


def test_same_place_with_new_address_format_is_one_candidate():
    changed = {**deepcopy(PLACE), "roadName": "Fixture-ro"}

    results = _normalize([PLACE, changed]).results

    assert len(results) == 1
    assert results[0].road_address == "Fixture Road 15"


@pytest.mark.parametrize("identity_change", [
    {"noorLat": "37.5001"},
    {"noorLon": "127.1001"},
    {"pkey": "provider-place-entrance-2"},
    {"id": "other-provider-place"},
    {"name": "Fixture Mart Station Gate"},
])
def test_entrance_facility_and_provider_key_changes_stay_distinct(identity_change):
    other = {**deepcopy(PLACE), **identity_change}

    results = _normalize([PLACE, other]).results

    assert len(results) == 2
    assert results[0].id != results[1].id


# S06-10 / S06-11: provider totals are preserved and drive the "more" signal.
def test_provider_total_count_is_preserved():
    response = _normalize([PLACE], total_count="25")

    assert response.total_count == 25


def test_missing_provider_total_count_is_unknown_not_guessed():
    response = _normalize([PLACE])

    assert response.total_count is None
    assert response.more_results_available is None


def _live_settings():
    return SimpleNamespace(
        tmap_poi_provider="live",
        tmap_app_key="non-secret-test-key",
        tmap_pedestrian_api_version="1",
        tmap_poi_search_url="https://provider.invalid/pois",
        tmap_timeout_seconds=5,
    )


def _fetch(search_info, *, limit, origin=None):
    async def scenario():
        requests = []

        def provider(request):
            requests.append(request)
            return httpx.Response(200, json={"searchPoiInfo": search_info})

        settings = _live_settings()
        runtime = TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds)
        await runtime.start()
        try:
            async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as client:
                kwargs = {}
                if origin is not None:
                    kwargs = {"origin_lat": origin[0], "origin_lng": origin[1]}
                response = await fetch_tmap_poi_search(
                    "fixture", settings, limit=limit, http_client=client, runtime=runtime, **kwargs,
                )
        finally:
            await runtime.aclose()
        return response, requests

    return asyncio.run(scenario())


def _places(count):
    return [
        {**deepcopy(PLACE), "id": f"place-{index}", "pkey": f"place-{index}-1", "name": f"Fixture {index}",
         "noorLat": str(37.5 + index / 1000)}
        for index in range(count)
    ]


def test_exactly_the_requested_count_with_no_more_provider_results_is_not_more():
    response, requests = _fetch({"totalCount": "3", "pois": {"poi": _places(3)}}, limit=3)

    assert requests[0].url.params["count"] == "3"
    assert requests[0].url.params["page"] == "1"
    assert len(response.results) == 3
    assert response.total_count == 3
    assert response.more_results_available is False


def test_more_provider_results_are_reported_from_provider_totals():
    response, _ = _fetch({"totalCount": "25", "pois": {"poi": _places(3)}}, limit=3)

    assert response.total_count == 25
    assert response.more_results_available is True


def test_dedup_and_invalid_items_do_not_end_the_search_early():
    places = _places(2)
    invalid = {"name": "No Coordinates"}
    response, _ = _fetch(
        {"totalCount": "25", "pois": {"poi": [places[0], deepcopy(places[0]), invalid]}}, limit=3,
    )

    assert len(response.results) == 1
    assert response.total_count == 25
    assert response.more_results_available is True


def test_unknown_provider_total_leaves_more_results_unknown():
    response, _ = _fetch({"pois": {"poi": _places(3)}}, limit=3)

    assert response.total_count is None
    assert response.more_results_available is None


# S06-30: every candidate distance carries its kind; neither kind is a walking-route distance.
def test_provider_radius_and_straight_line_fallback_are_labelled():
    with_radius, without_radius = _places(2)
    with_radius["radius"] = "0.32"
    response, _ = _fetch(
        {"totalCount": "2", "pois": {"poi": [with_radius, without_radius]}}, limit=3, origin=(37.5, 127.1),
    )

    radius_result, fallback_result = response.results
    assert radius_result.distance_m == 320
    assert radius_result.distance_source == "tmap_radius"
    assert fallback_result.distance_m is not None
    assert fallback_result.distance_source == "straight_line"


def test_distance_kind_is_absent_when_no_distance_is_reported():
    place = _places(1)[0]
    place["radius"] = "0.32"
    response, _ = _fetch({"totalCount": "1", "pois": {"poi": [place]}}, limit=3)

    assert response.results[0].distance_m is None
    assert response.results[0].distance_source is None


def test_mock_provider_reports_counts_and_straight_line_distance():
    async def scenario():
        settings = SimpleNamespace(
            tmap_poi_provider="mock",
            tmap_app_key="",
            tmap_pedestrian_api_version="1",
            tmap_poi_search_url="https://provider.invalid/pois",
            tmap_timeout_seconds=5,
        )
        runtime = TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds)
        await runtime.start()
        try:
            return await fetch_tmap_poi_search(
                "서울역", settings, limit=1, origin_lat=37.5547, origin_lng=126.9706, runtime=runtime,
            )
        finally:
            await runtime.aclose()

    response = asyncio.run(scenario())

    assert len(response.results) == 1
    assert response.total_count is not None and response.total_count >= 1
    assert response.more_results_available is (response.total_count > 1)
    assert response.results[0].distance_source == "straight_line"


def test_navigation_api_exposes_counts_and_distance_kind(monkeypatch):
    async def scenario():
        place = _places(1)[0]
        place["radius"] = "0.05"

        def provider(request):
            return httpx.Response(200, json={"searchPoiInfo": {"totalCount": "12", "pois": {"poi": [place]}}})

        settings = _live_settings()
        runtime = TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds)
        await runtime.start()
        try:
            async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as provider_client:
                async def controlled_fetch(query, settings, **kwargs):
                    return await fetch_tmap_poi_search(query, settings, http_client=provider_client, **kwargs)

                monkeypatch.setattr(navigation, "fetch_tmap_poi_search", controlled_fetch)
                app = FastAPI()
                app.include_router(navigation.create_router(settings, runtime_provider=lambda: runtime))
                async with httpx.AsyncClient(
                    transport=httpx.ASGITransport(app=app), base_url="http://testserver",
                ) as client:
                    return await client.get(
                        "/navigation/destinations/search",
                        params={"query": "fixture", "limit": 3, "origin_lat": 37.5, "origin_lng": 127.1},
                    )
        finally:
            await runtime.aclose()

    response = asyncio.run(scenario())

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["total_count"] == 12
    assert body["more_results_available"] is True
    assert body["results"][0]["distance_m"] == 50
    assert body["results"][0]["distance_source"] == "tmap_radius"
