"""Named stations must survive the provider's bounded page with an origin."""
import asyncio
from types import SimpleNamespace

import httpx
import pytest

from backend.app.services.tmap_pedestrian import fetch_tmap_poi_search


@pytest.mark.parametrize("query,expected_sort", [
    ("서울역", "A"), ("서울 역", "A"), ("부산역", "A"), ("판교역", "A"),
    ("근처 지하철역", "R"), ("가까운역", "R"), ("지하철역", "R"),
    ("기차역", "R"), ("서울역 근처 편의점", "R"), ("편의점", "R"),
])
@pytest.mark.parametrize("with_origin", [False, True])
def test_station_relevance_before_provider_page(query, expected_sort, with_origin):
    station = {"id": "station", "name": "서울역", "noorLat": "37.55", "noorLon": "126.97", "radius": "200"}
    unrelated = {"id": "shop", "name": "서울산 골프장", "noorLat": "35.56", "noorLon": "129.11", "radius": "90"}
    sort = expected_sort if with_origin else "A"

    def handler(request):
        assert request.url.params["searchKeyword"] == query
        assert request.url.params["searchtypCd"] == sort
        if with_origin:
            assert request.url.params["centerLat"] == "36.2"
            assert request.url.params["centerLon"] == "128.4"
        else:
            assert "centerLat" not in request.url.params
        ordered = [station, unrelated] if sort == "A" else [unrelated, station]
        return httpx.Response(200, json={"searchPoiInfo": {"pois": {
            "poi": ordered[:int(request.url.params["count"])]
        }}})

    async def scenario():
        settings = SimpleNamespace(
            tmap_poi_provider="live", tmap_app_key="test-key",
            tmap_pedestrian_api_version="1",
            tmap_poi_search_url="https://provider.invalid/pois", tmap_timeout_seconds=2,
        )
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            return await fetch_tmap_poi_search(
                query, settings, limit=1,
                origin_lat=36.2 if with_origin else None,
                origin_lng=128.4 if with_origin else None, http_client=client,
            )

    result = asyncio.run(scenario())
    assert len(result.results) == 1
    assert result.results[0].name == ("서울역" if sort == "A" else "서울산 골프장")
    assert result.results[0].distance_m == (
        (200_000 if sort == "A" else 90_000) if with_origin else None
    )
