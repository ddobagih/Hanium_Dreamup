"""Separate malformed provider envelopes from a successful empty POI search."""

import asyncio
from copy import deepcopy
from types import SimpleNamespace

from fastapi import FastAPI
import httpx
import pytest

from backend.app.api import navigation
from backend.app.services.tmap_pedestrian import (
    TmapPoiSearchError,
    _normalize_poi_search_response,
    fetch_tmap_poi_search,
)
from backend.app.services.tmap_runtime import TmapRuntime


POI = {"id": "shape-fixture", "name": "Fixture Station", "noorLat": "37.5", "noorLon": "127.1"}


def normalize(search_info):
    return _normalize_poi_search_response({"searchPoiInfo": search_info}, "fixture")


@pytest.mark.parametrize("search_info", [
    {"totalCount": "1", "pois": {"poi": "broken"}},
    {"totalCount": "1", "pois": "broken"},
    {"totalCount": "1", "pois": []},
    {"totalCount": "1", "pois": 1},
    {"totalCount": "1", "pois": True},
    {"totalCount": "1", "pois": None},
    {"totalCount": "1"},
    {"totalCount": "1", "pois": {}},
    {"totalCount": "1", "pois": {"poi": None}},
    {"totalCount": "1", "pois": {"poi": 1}},
    {"totalCount": "1", "pois": {"poi": True}},
    {"totalCount": "1", "pois": {"poi": []}},
    {"totalCount": "0", "pois": {"poi": "broken"}},
    {"totalCount": "0", "pois": "broken"},
    {"totalCount": "0", "pois": {"poi": [POI]}},
    {"totalCount": "0", "pois": {"poi": POI}},
    {"pois": {"poi": "broken"}},
    {},
])
def test_malformed_poi_envelope_is_provider_error(search_info):
    with pytest.raises(TmapPoiSearchError) as caught:
        normalize(search_info)
    assert caught.value.code == "invalid_tmap_response"
    assert caught.value.http_status == 502


@pytest.mark.parametrize("total_count", [
    None, True, False, -1, "-1", "", "broken", "1.5", "NaN", "Infinity",
    1.5, 1.0, {}, [],
    pytest.param("9" * 5000, id="count-5000"),
    pytest.param("\u001c1", id="count-control-character"),
])
def test_invalid_total_count_is_provider_error(total_count):
    with pytest.raises(TmapPoiSearchError) as caught:
        normalize({"totalCount": total_count, "pois": {"poi": []}})
    assert caught.value.code == "invalid_tmap_response"
    assert caught.value.http_status == 502


@pytest.mark.parametrize("search_info", [
    {"totalCount": "0", "pois": {"poi": []}},
    {"totalCount": 0, "pois": {"poi": []}},
    {"totalCount": "0", "pois": {"poi": None}},
    {"totalCount": "0", "pois": {}},
    {"totalCount": "0", "pois": None},
    {"totalCount": "0"},
    {"pois": {"poi": []}},
])
def test_legitimate_empty_search_remains_success(search_info):
    result = normalize(search_info)
    assert result.query == "fixture"
    assert result.provider == "tmap_poi"
    assert result.results == []


@pytest.mark.parametrize("items", [POI, [POI]])
@pytest.mark.parametrize("total_count", [None, "1", 1, "100"])
def test_single_object_and_list_keep_existing_contract(items, total_count):
    search_info = {"pois": {"poi": deepcopy(items)}}
    if total_count is not None:
        search_info["totalCount"] = total_count
    result = normalize(search_info)
    assert [candidate.name for candidate in result.results] == ["Fixture Station"]
    assert result.results[0].point.latitude == 37.5
    assert result.results[0].point.longitude == 127.1


def test_valid_page_keeps_candidate_filtering_and_duplicate_handling():
    result = normalize({"totalCount": "10", "pois": {"poi": [
        {"name": "No Coordinates"}, POI, deepcopy(POI),
    ]}})
    assert [candidate.name for candidate in result.results] == ["Fixture Station"]


@pytest.mark.parametrize("search_info, expected_status", [
    ({"totalCount": "1", "pois": {"poi": "broken"}}, 502),
    ({"totalCount": "1", "pois": {"poi": []}}, 502),
    ({"totalCount": None, "pois": {"poi": []}}, 502),
    ({"totalCount": "0", "pois": {"poi": []}}, 200),
    pytest.param({"totalCount": "9" * 5000, "pois": {"poi": [POI]}}, 502, id="count-5000"),
    pytest.param({"totalCount": "\u001c1", "pois": {"poi": [POI]}}, 502, id="count-control-character"),
])
def test_live_fetch_worker_and_navigation_api_preserve_error_boundary(monkeypatch, search_info, expected_status):
    async def scenario():
        calls = []

        def provider(request):
            assert str(request.url).startswith("https://provider.invalid/pois?")
            calls.append(request)
            payload = search_info if len(calls) == 1 else {"totalCount": "0", "pois": {"poi": []}}
            return httpx.Response(200, json={"searchPoiInfo": payload})

        settings = SimpleNamespace(
            tmap_poi_provider="live", tmap_app_key="fixture-key", tmap_pedestrian_api_version="1",
            tmap_poi_search_url="https://provider.invalid/pois", tmap_timeout_seconds=5,
        )
        runtime = TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds)
        await runtime.start()
        try:
            async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as provider_client:
                async def fetch_with_controlled_transport(query, settings, **kwargs):
                    return await fetch_tmap_poi_search(query, settings, http_client=provider_client, **kwargs)

                monkeypatch.setattr(navigation, "fetch_tmap_poi_search", fetch_with_controlled_transport)
                app = FastAPI()
                app.include_router(navigation.create_router(settings, runtime_provider=lambda: runtime))
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://testserver") as client:
                    response = await client.get("/navigation/destinations/search", params={"query": "fixture"})
                    recovered = await client.get("/navigation/destinations/search", params={"query": "fixture"})
            assert response.status_code == expected_status, response.text
            assert recovered.status_code == 200, recovered.text
            assert recovered.json()["results"] == []
            assert len(calls) == 2
            if expected_status == 502:
                assert response.json()["detail"]["code"] == "invalid_tmap_response"
                assert "results" not in response.json()
            else:
                assert response.json()["results"] == []
        finally:
            await runtime.aclose()

    asyncio.run(scenario())
