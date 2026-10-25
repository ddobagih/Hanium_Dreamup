"""S06-24: conservative nearby intent, preserving query identity and proper names."""
import asyncio
from types import SimpleNamespace

import httpx
import pytest

from backend.app.services.tmap_pedestrian import _destination_search_intent, fetch_tmap_poi_search
from backend.app.services.tmap_runtime import TmapRuntime


@pytest.mark.parametrize("with_origin", [True, False])
def test_mock_category_search_uses_the_same_intent_rule(monkeypatch, with_origin):
    from backend.app.services import tmap_poi_normalization as service
    monkeypatch.setattr(service, "MOCK_POI_ITEMS", ({
        "id": "fixture", "name": "시험 편의점", "latitude": 37.5, "longitude": 127.0,
        "address": "시험로 12-3", "road_address": "시험로 12-3", "category": "편의점",
    },))
    result = service._mock_poi_search_response(
        "가까운 편의점", limit=3, origin_lat=37.5 if with_origin else None,
        origin_lng=127.0 if with_origin else None,
    )
    assert result.query == "가까운 편의점"
    assert len(result.results) == (1 if with_origin else 0)


@pytest.mark.parametrize("query,keyword", [
    ("가까운 편의점", "편의점"), ("가장 가까운 약국", "약국"),
    ("근처 카페", "카페"), ("주변 화장실", "화장실"), ("인근 지하철역", "지하철역"),
    ("가까운약국", "가까운약국"), ("근처카페", "근처카페"),
    ("서울역 근처 편의점", "서울역 근처 편의점"),
    ("가까운 약국 강남점", "가까운 약국 강남점"),
    ("근처 서울역", "근처 서울역"), ("가까운", "가까운"),
])
@pytest.mark.parametrize("with_origin", [True, False])
def test_nearby_provider_request_preserves_names_and_response_query(query, keyword, with_origin):
    assert _destination_search_intent(query) == (keyword, query != keyword)

    def provider(request):
        assert request.url.params["searchKeyword"] == (keyword if with_origin else query)
        assert request.url.params["searchtypCd"] == ("R" if with_origin else "A")
        assert ("centerLat" in request.url.params) == with_origin
        return httpx.Response(200, json={"searchPoiInfo": {"totalCount": "1", "pois": {"poi": [{
            "id": "fixture", "name": keyword, "frontLat": "37.5", "frontLon": "127.0",
        }]}}})

    async def scenario():
        settings = SimpleNamespace(
            tmap_poi_provider="live", tmap_app_key="fixture-key",
            tmap_pedestrian_api_version="1", tmap_poi_search_url="https://provider.invalid/pois",
            tmap_timeout_seconds=5,
        )
        runtime = TmapRuntime(timeout_seconds=5)
        await runtime.start()
        try:
            async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as client:
                return await fetch_tmap_poi_search(
                    query, settings, runtime=runtime, http_client=client,
                    origin_lat=37.5 if with_origin else None, origin_lng=127.0 if with_origin else None,
                )
        finally:
            await runtime.aclose()

    response = asyncio.run(scenario())
    assert response.query == query  # Android matches the response to its original request.
    assert len(response.results) == 1
