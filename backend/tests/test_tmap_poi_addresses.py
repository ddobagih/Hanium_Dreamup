"""POI road-address selection and main/sub-number presentation regressions."""

import asyncio
from copy import deepcopy
from types import SimpleNamespace

from fastapi import FastAPI
import httpx
import pytest

from backend.app.api import navigation
from backend.app.services.tmap_pedestrian import _normalize_poi_search_response, fetch_tmap_poi_search
from backend.app.services.tmap_runtime import TmapRuntime


BASE = {"id": "address-fixture", "name": "Fixture Station", "noorLat": "37.5", "noorLon": "127.1"}
FULL = "Fixture City Nested Road 12-3"
FALLBACK = {"roadName": "Fallback Road", "firstBuildNo": "20", "secondBuildNo": "0"}


def normalize(item):
    return _normalize_poi_search_response({"searchPoiInfo": {"pois": {"poi": [item]}}}, "fixture").results[0]


def nested(rows, **values):
    return {**BASE, **values, "newAddressList": {"newAddress": rows}}


def test_nested_only_full_road_address_is_not_lost():
    assert normalize(nested([{"fullAddressRoad": FULL}])).road_address == FULL


def test_nested_full_address_precedes_partial_top_level_address():
    assert normalize(nested([{"fullAddressRoad": " " + FULL + " "}], **FALLBACK)).road_address == FULL


@pytest.mark.parametrize("value", [None, "", " ", True, 7, ["bad"], {"bad": "label"}])
def test_invalid_nested_full_address_keeps_top_level_fallback(value):
    result = normalize(nested([{"fullAddressRoad": value}], **FALLBACK))
    assert result.road_address == "Fallback Road 20"


@pytest.mark.parametrize("container", [None, {}, [], "bad", {"newAddress": None}, {"newAddress": "bad"}, {"newAddress": {"fullAddressRoad": FULL}}, {"newAddress": []}])
def test_invalid_or_empty_nested_container_keeps_fallback(container):
    assert normalize({**BASE, **FALLBACK, "newAddressList": container}).road_address == "Fallback Road 20"


@pytest.mark.parametrize("coordinate_kind", ["center", "front"])
@pytest.mark.parametrize("reverse", [False, True])
def test_multiple_addresses_select_unique_matching_coordinate_pair(coordinate_kind, reverse):
    rows = [
        {"fullAddressRoad": "Different Facility Road 99", "centerLat": "38", "centerLon": "128"},
        {"fullAddressRoad": FULL, coordinate_kind + "Lat": "37.5", coordinate_kind + "Lon": "127.1"},
    ]
    if reverse:
        rows.reverse()
    assert normalize(nested(rows, **FALLBACK)).road_address == FULL


@pytest.mark.parametrize("rows", [
    [{"fullAddressRoad": "First Road 1"}, {"fullAddressRoad": "Second Road 2"}],
    [{"fullAddressRoad": "First Road 1", "centerLat": "38", "centerLon": "128"}, {"fullAddressRoad": "Second Road 2", "frontLat": "39", "frontLon": "129"}],
    [{"fullAddressRoad": "First Road 1", "centerLat": "37.5", "centerLon": "127.1"}, {"fullAddressRoad": "Second Road 2", "frontLat": "37.5", "frontLon": "127.1"}],
    [{"fullAddressRoad": "First Road 1", "centerLat": "37.5", "centerLon": "128", "frontLat": "38", "frontLon": "127.1"}, {"fullAddressRoad": "Second Road 2"}],
])
def test_ambiguous_or_mismatched_nested_addresses_do_not_mix_facilities(rows):
    assert normalize(nested(rows, **FALLBACK)).road_address == "Fallback Road 20"


def test_repeated_same_nested_address_is_unambiguous():
    assert normalize(nested([None, {"fullAddressRoad": FULL}, {"fullAddressRoad": FULL}])).road_address == FULL


def test_nested_selection_uses_the_already_validated_fallback_point():
    result = normalize(nested([
        {"fullAddressRoad": "Wrong Road 1", "centerLat": "37.5", "centerLon": "127.1"},
        {"fullAddressRoad": FULL, "frontLat": "37.6", "frontLon": "127.2"},
    ], noorLat="999", noorLon="127.1", frontLat="37.6", frontLon="127.2", **FALLBACK))
    assert result.point.latitude == 37.6
    assert result.point.longitude == 127.2
    assert result.road_address == FULL


@pytest.mark.parametrize("main, sub, expected", [
    ("12", "3", "12-3"), (12, 3, "12-3"), (12.0, 3.0, "12.0-3.0"),
    (" 12 ", " 3 ", "12-3"), ("12", None, "12"), ("12", "", "12"),
    ("12", "0", "12"), (12, 0, "12"), (12, 0.0, "12"), (12, -0.0, "12"),
    ("12", "00", "12"), ("12", "0.00", "12"),
    (None, "3", "3"), (None, "0", None), ("12", True, "12"),
    ({"bad": "number"}, 3, "3"), ("12", [3], "12"),
])
@pytest.mark.parametrize("first_field, second_field, output", [
    ("firstBuildNo", "secondBuildNo", "road_address"), ("firstNo", "secondNo", "address"),
])
def test_main_and_sub_numbers_use_hyphen_without_zero_suffix(main, sub, expected, first_field, second_field, output):
    result = normalize({**BASE, first_field: main, second_field: sub})
    assert getattr(result, output) == expected


def test_unchanged_address_keeps_existing_numeric_candidate_hash():
    result = normalize({
        "id": 7, "pkey": 8.5, "name": "Fixture Station", "noorLat": "37.5", "noorLon": "127.1",
        "roadName": "Fixture Road", "firstBuildNo": 12, "secondBuildNo": "0",
        "upperAddrName": "Fixture City", "firstNo": 34, "secondNo": "0", "bizCatName": "Transit",
    })
    # S06-15: the address spelling no longer enters the key; same numeric provider identity as field_types BASE.
    assert result.id == "tmap-poi:v1:711763e0d95a40ffd5a77cfc4b5bcefd2570331c18d6a5d3e0935be79e8bebf4"
    assert result.road_address == "Fixture Road 12"
    assert result.address == "Fixture City 34"


def test_nested_addresses_remain_local_to_each_candidate_and_stable_under_reordering():
    first = nested([{"fullAddressRoad": FULL}])
    second = nested([{"fullAddressRoad": "Other Road 9"}], name="Second Station")
    def results(items):
        return _normalize_poi_search_response({"searchPoiInfo": {"pois": {"poi": items}}}, "fixture").results
    forward = results([first, second, deepcopy(first)])
    reverse = results([second, first])
    assert [item.road_address for item in forward] == [FULL, "Other Road 9"]
    assert {item.name: item.id for item in forward} == {item.name: item.id for item in reverse}
    assert len({item.id for item in forward}) == 2


@pytest.mark.parametrize("item, road_address, address", [
    (nested([{"fullAddressRoad": FULL}]), FULL, None),
    ({**BASE, "roadName": "Fixture Road", "firstBuildNo": 12, "secondBuildNo": 3, "firstNo": "40", "secondNo": "5"}, "Fixture Road 12-3", "40-5"),
    ({**BASE, "roadName": "Fixture Road", "firstBuildNo": 12, "secondBuildNo": 0.0}, "Fixture Road 12", None),
])
def test_live_fetch_worker_and_api_preserve_full_and_numbered_addresses(monkeypatch, item, road_address, address):
    async def scenario():
        def provider(request):
            assert str(request.url).startswith("https://provider.invalid/pois?")
            return httpx.Response(200, json={"searchPoiInfo": {"pois": {"poi": [item]}}})
        settings = SimpleNamespace(
            tmap_poi_provider="live", tmap_app_key="fixture-key", tmap_pedestrian_api_version="1",
            tmap_poi_search_url="https://provider.invalid/pois", tmap_timeout_seconds=5,
        )
        runtime = TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds)
        await runtime.start()
        try:
            async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as provider_client:
                async def controlled_fetch(query, settings, **kwargs):
                    return await fetch_tmap_poi_search(query, settings, http_client=provider_client, **kwargs)
                monkeypatch.setattr(navigation, "fetch_tmap_poi_search", controlled_fetch)
                app = FastAPI()
                app.include_router(navigation.create_router(settings, runtime_provider=lambda: runtime))
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://testserver") as client:
                    response = await client.get("/navigation/destinations/search", params={"query": "fixture"})
            assert response.status_code == 200, response.text
            result = response.json()["results"][0]
            assert result["road_address"] == road_address
            assert result["address"] == address
        finally:
            await runtime.aclose()
    asyncio.run(scenario())
