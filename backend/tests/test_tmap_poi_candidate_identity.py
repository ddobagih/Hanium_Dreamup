"""Synthetic regression for distinct POIs sharing a provider ID in live data."""

import asyncio
from copy import deepcopy
from types import SimpleNamespace

import httpx

from backend.app.services.tmap_pedestrian import (
    _normalize_poi_search_response,
    fetch_tmap_poi_search,
)


def _items():
    return [
        {
            "id": "shared-provider-id",
            "pkey": "shared-provider-pkey",
            "name": "Campus Fixture",
            "noorLat": "36.1",
            "noorLon": "128.3",
            "bizCatName": "education",
            "radius": "0.01",
        },
        {
            "id": "shared-provider-id",
            "pkey": "shared-provider-pkey",
            "name": "Gate Fixture",
            "noorLat": "36.1001",
            "noorLon": "128.3001",
            "bizCatName": "education",
            "radius": "0.02",
        },
    ]


def _payload(items):
    return {"searchPoiInfo": {"pois": {"poi": items}}}


def _normalize(items, query="campus fixture"):
    return _normalize_poi_search_response(_payload(items), query).results


def test_shared_provider_id_preserves_distinct_candidates_with_unique_ids():
    results = _normalize(_items())

    assert [result.name for result in results] == ["Campus Fixture", "Gate Fixture"]
    assert results[0].point != results[1].point
    assert len({result.id for result in results}) == 2
    assert all(result.id.startswith("tmap-poi:v1:") and len(result.id) == 76 for result in results)


def test_identical_candidate_duplicates_are_removed_without_reordering():
    campus, gate = _items()
    results = _normalize([campus, deepcopy(campus), gate, deepcopy(gate)])

    assert [result.name for result in results] == ["Campus Fixture", "Gate Fixture"]
    assert [result.id for result in results] == [result.id for result in _normalize([campus, gate])]


def test_candidate_identity_is_independent_of_query_order_and_distance():
    items = _items()
    original = {result.name: result.id for result in _normalize(items)}
    changed = deepcopy(items[::-1])
    changed[0]["radius"] = "100"
    changed[1]["radius"] = "200"
    results = _normalize(changed, query="different fixture query")

    assert [result.name for result in results] == ["Gate Fixture", "Campus Fixture"]
    assert {result.name: result.id for result in results} == original
    assert [result.distance_m for result in results] == [100_000, 200_000]


def test_normalized_equivalent_coordinates_and_names_keep_candidate_identity():
    item = _items()[0]
    equivalent = deepcopy(item)
    equivalent.update(name=" Campus Fixture ", noorLat=36.100000, noorLon=128.300000)

    assert _normalize([item])[0].id == _normalize([equivalent])[0].id


def test_provider_pkey_is_part_of_candidate_identity():
    item = _items()[0]
    other_key = deepcopy(item)
    other_key["pkey"] = "different-provider-pkey"

    assert len({result.id for result in _normalize([item, other_key])}) == 2


def test_real_fetch_boundary_keeps_ids_stable_across_limit_and_origin_changes():
    async def scenario():
        requested_limits = []

        def handler(request):
            count = int(request.url.params["count"])
            requested_limits.append(count)
            items = _items()[:count]
            if count > 1:
                for item in items:
                    item["radius"] = "99"
            return httpx.Response(200, json=_payload(items))

        settings = SimpleNamespace(
            tmap_poi_provider="live",
            tmap_app_key="non-secret-test-key",
            tmap_pedestrian_api_version="1",
            tmap_poi_search_url="https://provider.invalid/pois",
            tmap_timeout_seconds=2,
        )
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            first = await fetch_tmap_poi_search(
                "campus fixture", settings, limit=1, http_client=client,
            )
            expanded = await fetch_tmap_poi_search(
                "campus fixture", settings, limit=2,
                origin_lat=36.2, origin_lng=128.4, http_client=client,
            )
        assert requested_limits == [1, 2]
        assert len(first.results) == 1
        assert len(expanded.results) == 2
        assert first.results[0].id == expanded.results[0].id
        assert first.results[0].distance_m is None
        assert expanded.results[0].distance_m == 99_000
        assert len({result.id for result in expanded.results}) == 2

    asyncio.run(scenario())
