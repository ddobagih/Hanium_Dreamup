"""Provider labels stay textual; explicit numeric identifiers retain identity."""

import asyncio
from copy import deepcopy
from types import SimpleNamespace

from fastapi import FastAPI
import httpx
import pytest

from backend.app.api import navigation
from backend.app.services.tmap_pedestrian import _normalize_poi_search_response, fetch_tmap_poi_search
from backend.app.services.tmap_runtime import TmapRuntime


NUMERIC_FIELDS = ("id", "pkey", "firstBuildNo", "secondBuildNo", "firstNo", "secondNo")
LABEL_FIELDS = (
    "upperRoadAddrName", "middleRoadAddrName", "lowerRoadAddrName", "roadName",
    "upperAddrName", "middleAddrName", "lowerAddrName", "detailAddrName",
    "bizCatName", "catName", "lowerBizName", "middleBizName", "upperBizName", "bizName",
)
CATEGORY_FIELDS = LABEL_FIELDS[8:]
BASE = {
    "id": 7, "pkey": 8.5, "name": "Fixture Station", "noorLat": "37.5", "noorLon": "127.1",
    "roadName": "Fixture Road", "firstBuildNo": 12, "secondBuildNo": 3,
    "upperAddrName": "Fixture City", "firstNo": 34.0, "secondNo": 5.0, "bizCatName": "Transit",
}


def normalize(items):
    return _normalize_poi_search_response({"searchPoiInfo": {"pois": {"poi": items}}}, "fixture").results


@pytest.mark.parametrize("bad_name", [{"bad": "label"}, ["bad label"], True, False, 7, 7.5, None, "", " \t "])
def test_invalid_required_name_excludes_candidate(bad_name):
    assert normalize([{**BASE, "name": bad_name}]) == []


@pytest.mark.parametrize("field", LABEL_FIELDS)
@pytest.mark.parametrize("bad_value", [{"bad": "label"}, ["bad label"], True, 7])
def test_nontext_optional_label_behaves_as_absent(field, bad_value):
    item = deepcopy(BASE)
    for category in CATEGORY_FIELDS:
        item.pop(category, None)
    item[field] = bad_value
    without_field = deepcopy(item)
    without_field.pop(field)
    assert normalize([item]) == normalize([without_field])


@pytest.mark.parametrize("field", NUMERIC_FIELDS)
@pytest.mark.parametrize("bad_value", [True, False, {"bad": "value"}, ["bad value"], float("inf"), float("-inf"), float("nan")])
def test_explicit_numeric_field_rejects_boolean_container_and_nonfinite_number(field, bad_value):
    item = deepcopy(BASE)
    item[field] = bad_value
    without_field = deepcopy(item)
    without_field.pop(field)
    assert normalize([item]) == normalize([without_field])


@pytest.mark.parametrize("field", NUMERIC_FIELDS)
@pytest.mark.parametrize("number", [0, 7, 7.5, -3])
def test_finite_numeric_field_preserves_equivalent_string_identity(field, number):
    item = deepcopy(BASE)
    item[field] = number
    equivalent = deepcopy(item)
    equivalent[field] = str(number)
    assert normalize([item]) == normalize([equivalent])


def test_numeric_dto_and_candidate_hash_use_normalized_address_format():
    result = normalize([BASE])[0]
    # S06-15: identity is ["7","8.5","Fixture Station",37.5,127.1]; address/category are display data.
    assert result.id == "tmap-poi:v1:711763e0d95a40ffd5a77cfc4b5bcefd2570331c18d6a5d3e0935be79e8bebf4"
    assert result.road_address == "Fixture Road 12-3"
    assert result.address == "Fixture City 34.0-5.0"
    assert result.category == "Transit"


def test_invalid_primary_category_uses_next_valid_text_and_name_is_trimmed():
    result = normalize([{**BASE, "name": " Fixture Station ", "bizCatName": {"bad": "label"}, "catName": " Fallback "}])[0]
    assert result.name == result.point.name == "Fixture Station"
    assert result.category == "Fallback"


def mixed_items(bad_value):
    valid = {**BASE, "roadName": bad_value, "upperAddrName": bad_value, "bizCatName": bad_value, "catName": "Fallback"}
    return [
        {**BASE, "name": bad_value}, valid, deepcopy(valid),
        {**BASE, "id": 9, "name": "Second Station"},
    ]


@pytest.mark.parametrize("bad_value", [{"bad": "label"}, ["bad label"], True])
def test_mixed_candidates_preserve_valid_order_and_remove_duplicates(bad_value):
    results = normalize(mixed_items(bad_value))
    assert [item.name for item in results] == ["Fixture Station", "Second Station"]
    assert results[0].road_address == "12-3"
    assert results[0].address == "34.0-5.0"
    assert results[0].category == "Fallback"


@pytest.mark.parametrize("bad_value", [{"bad": "label"}, ["bad label"], True])
def test_live_worker_and_api_do_not_return_stringified_bad_labels(monkeypatch, bad_value):
    async def scenario():
        calls = []

        def provider(request):
            assert str(request.url).startswith("https://provider.invalid/pois?")
            calls.append(request)
            return httpx.Response(200, json={"searchPoiInfo": {"totalCount": "4", "pois": {"poi": mixed_items(bad_value)}}})

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
            assert len(calls) == 1
            assert response.status_code == 200, response.text
            results = response.json()["results"]
            assert [item["name"] for item in results] == ["Fixture Station", "Second Station"]
            assert [item["point"]["name"] for item in results] == ["Fixture Station", "Second Station"]
            assert results[0]["road_address"] == "12-3"
            assert results[0]["address"] == "34.0-5.0"
            assert results[0]["category"] == "Fallback"
        finally:
            await runtime.aclose()

    asyncio.run(scenario())
