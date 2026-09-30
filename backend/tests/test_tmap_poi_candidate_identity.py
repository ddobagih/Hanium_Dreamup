"""Synthetic regression for distinct POIs sharing a provider ID in live data."""

import asyncio
from copy import deepcopy
from types import SimpleNamespace

import httpx
import pytest

from backend.app.schemas import RoutePoint
from backend.app.services.tmap_pedestrian import (
    _normalize_poi_search_response,
    fetch_tmap_poi_search,
)
from backend.app.services.tmap_runtime import TmapRuntime


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
        runtime = TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds)
        await runtime.start()
        try:
            async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
                first = await fetch_tmap_poi_search(
                    "campus fixture", settings, limit=1, http_client=client, runtime=runtime,
                )
                expanded = await fetch_tmap_poi_search(
                    "campus fixture", settings, limit=2,
                    origin_lat=36.2, origin_lng=128.4, http_client=client, runtime=runtime,
                )
        finally:
            await runtime.aclose()
        assert requested_limits == [1, 2]
        assert len(first.results) == 1
        assert len(expanded.results) == 2
        assert first.results[0].id == expanded.results[0].id
        assert first.results[0].distance_m is None
        assert expanded.results[0].distance_m == 99_000
        assert len({result.id for result in expanded.results}) == 2

    asyncio.run(scenario())


@pytest.mark.parametrize("partial_primary", [
    {"noorLat": "37.1"},
    {"noorLon": "127.1"},
])
def test_incomplete_primary_coordinates_use_complete_fallback_pair(partial_primary):
    item = {
        "id": "pair-fixture",
        "name": "Pair Fixture",
        **partial_primary,
        "frontLat": "37.2",
        "frontLon": "127.2",
    }
    before = deepcopy(item)

    results = _normalize([item])

    assert len(results) == 1
    assert results[0].point.latitude == 37.2
    assert results[0].point.longitude == 127.2
    assert item == before


@pytest.mark.parametrize("coordinates", [
    {"noorLat": "37.1", "frontLon": "127.2"},
    {"noorLon": "127.1", "frontLat": "37.2"},
    {"frontLat": "37.1", "lon": "127.2"},
    {"frontLon": "127.1", "lat": "37.2"},
    {"lat": "37.1", "newLon": "127.2"},
    {"lon": "127.1", "newLat": "37.2"},
    {"noorLat": "37.1", "frontLon": "127.2", "lat": "37.3", "newLon": "127.4"},
    {"noorLon": "127.1", "frontLat": "37.2", "lon": "127.3", "newLat": "37.4"},
])
def test_incomplete_coordinate_families_are_not_combined(coordinates):
    item = {"id": "partial-fixture", "name": "Partial Fixture", **coordinates}
    before = deepcopy(item)

    assert _normalize([item]) == []
    assert item == before


@pytest.mark.parametrize("selected_index", [0, 1, 2, 3])
def test_complete_coordinate_pair_uses_existing_family_priority(selected_index):
    pairs = [
        ("noorLat", "noorLon", 36.1, 126.1),
        ("frontLat", "frontLon", 36.2, 126.2),
        ("lat", "lon", 36.3, 126.3),
        ("newLat", "newLon", 36.4, 126.4),
    ]
    item = {"id": "priority-fixture", "name": "Priority Fixture"}
    for latitude_key, longitude_key, latitude, longitude in pairs[selected_index:]:
        item[latitude_key] = str(latitude)
        item[longitude_key] = str(longitude)
    before = deepcopy(item)

    results = _normalize([item])

    assert len(results) == 1
    assert results[0].point.latitude == pairs[selected_index][2]
    assert results[0].point.longitude == pairs[selected_index][3]
    assert item == before


@pytest.mark.parametrize("selected_index", [0, 1, 2, 3])
def test_selected_point_keeps_provider_coordinate_kind_and_all_valid_candidates(selected_index):
    # S07-38 (originals/07.txt:350): the selected point keeps its provider meaning
    # and front/other families are preserved instead of being discarded.
    kinds = ["noor", "front", "lat", "new"]
    pairs = [
        ("noorLat", "noorLon", 36.1, 126.1),
        ("frontLat", "frontLon", 36.2, 126.2),
        ("lat", "lon", 36.3, 126.3),
        ("newLat", "newLon", 36.4, 126.4),
    ]
    item = {"id": "kind-fixture", "name": "Kind Fixture"}
    for latitude_key, longitude_key, latitude, longitude in pairs[selected_index:]:
        item[latitude_key] = str(latitude)
        item[longitude_key] = str(longitude)
    before = deepcopy(item)

    result = _normalize([item])[0]

    assert result.coordinate_kind == kinds[selected_index]
    assert (result.point.latitude, result.point.longitude) == pairs[selected_index][2:]
    assert [
        (candidate.kind, candidate.point.latitude, candidate.point.longitude)
        for candidate in result.coordinate_candidates
    ] == [
        (kind, latitude, longitude)
        for kind, (_, _, latitude, longitude) in zip(kinds[selected_index:], pairs[selected_index:])
    ]
    assert result.coordinate_candidates[0].point == RoutePoint(
        latitude=result.point.latitude, longitude=result.point.longitude,
    )
    assert item == before


def test_noor_and_front_pair_keeps_noor_route_target_and_front_candidate():
    item = {
        "id": "front-fixture", "name": "Front Fixture",
        "noorLat": "37.1", "noorLon": "127.1", "frontLat": "37.2", "frontLon": "127.2",
        "lat": "NaN", "lon": "127.3", "newLat": "37.4",
    }
    identity_without_front = _normalize([{
        "id": "front-fixture", "name": "Front Fixture", "noorLat": "37.1", "noorLon": "127.1",
    }])[0]

    result = _normalize([item])[0]

    # Route/arrival target is unchanged; the extra data is additive only.
    assert result.point == RoutePoint(latitude=37.1, longitude=127.1, name="Front Fixture")
    assert result.id == identity_without_front.id
    assert result.coordinate_kind == "noor"
    assert [(candidate.kind, candidate.point) for candidate in result.coordinate_candidates] == [
        ("noor", RoutePoint(latitude=37.1, longitude=127.1)),
        ("front", RoutePoint(latitude=37.2, longitude=127.2)),
    ]
    dumped = result.model_dump(mode="json")
    assert dumped["coordinate_kind"] == "noor"
    assert dumped["coordinate_candidates"][1] == {
        "kind": "front", "point": {"latitude": 37.2, "longitude": 127.2, "name": None},
    }


def test_mock_destination_results_do_not_invent_provider_coordinate_kinds():
    from backend.app.services.tmap_pedestrian import MOCK_POI_ITEMS, _mock_poi_search_response

    response = _mock_poi_search_response(str(MOCK_POI_ITEMS[0]["name"]), limit=5)
    assert response.results
    assert all(result.coordinate_kind is None for result in response.results)
    assert all(result.coordinate_candidates == [] for result in response.results)


@pytest.mark.parametrize("invalid_key", ["noorLat", "noorLon"])
@pytest.mark.parametrize("invalid_value", [
    None, "", "invalid", True, False, "NaN", "Infinity", "-Infinity", 999, -999,
])
def test_invalid_coordinate_pair_falls_back_without_mixing(invalid_key, invalid_value):
    item = {
        "id": "invalid-pair-fixture",
        "name": "Invalid Pair Fixture",
        "noorLat": "37.1",
        "noorLon": "127.1",
        "frontLat": "37.2",
        "frontLon": "127.2",
    }
    item[invalid_key] = invalid_value
    before = deepcopy(item)

    results = _normalize([item])

    assert len(results) == 1
    assert results[0].point.latitude == 37.2
    assert results[0].point.longitude == 127.2
    assert item == before


@pytest.mark.parametrize(("latitude", "longitude"), [
    ("0", "0"),
    (0, 0),
    (-90, -180),
    (90, 180),
    (37.5657, 126.9769),
])
def test_coordinate_pair_accepts_zero_existing_endpoints_and_domestic_values(latitude, longitude):
    item = {
        "id": "boundary-fixture",
        "name": "Boundary Fixture",
        "noorLat": latitude,
        "noorLon": longitude,
    }
    before = deepcopy(item)

    results = _normalize([item])

    assert len(results) == 1
    assert results[0].point.latitude == float(latitude)
    assert results[0].point.longitude == float(longitude)
    assert item == before


def test_no_valid_pair_and_missing_name_skip_without_reordering_valid_neighbors():
    campus, gate = _items()
    items = [
        campus,
        {"name": "Partial Fixture", "noorLat": "37.1", "frontLon": "127.2"},
        {"name": "Out Of Range Fixture", "noorLat": "91", "noorLon": "127.2"},
        {"id": "nameless-fixture", "noorLat": "0", "noorLon": "0"},
        gate,
    ]
    before = deepcopy(items)

    results = _normalize(items)

    assert [result.model_dump() for result in results] == [
        result.model_dump() for result in _normalize([campus, gate])
    ]
    assert [result.name for result in results] == ["Campus Fixture", "Gate Fixture"]
    assert items == before


def test_same_correct_pair_preserves_identity_metadata_order_and_deduplication():
    canonical = _items()
    canonical[0].update(
        upperAddrName="Region",
        middleAddrName="District",
        lowerAddrName="Block",
        firstNo="10",
        secondNo="0",
        upperRoadAddrName="Region",
        middleRoadAddrName="District",
        roadName="Fixture Road",
        firstBuildNo="20",
        secondBuildNo="0",
    )
    fallback = deepcopy(canonical)
    for index, item in enumerate(fallback):
        item["frontLat"] = item.pop("noorLat")
        item["frontLon"] = item.pop("noorLon")
        if index == 0:
            item["noorLat"] = "35.0"
        else:
            item["noorLon"] = "129.0"
    items = [fallback[0], deepcopy(canonical[0]), fallback[1], deepcopy(canonical[1])]
    before = deepcopy(items)

    results = _normalize(items)

    # S07-38 provenance is additive: the first-seen (front-family) candidate keeps
    # its own coordinate kind while identity, metadata and dedupe stay identical.
    provenance = {"coordinate_kind", "coordinate_candidates"}
    assert [result.model_dump(exclude=provenance) for result in results] == [
        result.model_dump(exclude=provenance) for result in _normalize(canonical)
    ]
    assert [result.coordinate_kind for result in results] == ["front", "front"]
    assert [result.coordinate_kind for result in _normalize(canonical)] == ["noor", "noor"]
    assert [result.name for result in results] == ["Campus Fixture", "Gate Fixture"]
    assert results[0].address == "Region District Block 10"
    assert results[0].road_address == "Region District Fixture Road 20"
    assert results[0].category == "education"
    assert [result.distance_m for result in results] == [10, 20]
    assert items == before


def test_invalid_lower_priority_pairs_can_use_complete_new_pair():
    item = {
        "id": "last-pair-fixture",
        "name": "Last Pair Fixture",
        "noorLat": "91",
        "noorLon": "127.1",
        "frontLat": "NaN",
        "frontLon": "127.2",
        "lat": "37.3",
        "newLat": "36.4",
        "newLon": "128.4",
    }
    before = deepcopy(item)

    results = _normalize([item])

    assert len(results) == 1
    assert results[0].point.latitude == 36.4
    assert results[0].point.longitude == 128.4
    assert item == before


@pytest.mark.parametrize("overflow_value", [10**400, -(10**400)], ids=["positive", "negative"])
@pytest.mark.parametrize("overflow_axis", ["latitude", "longitude"])
def test_large_integer_coordinate_pair_skips_to_complete_lower_pair(overflow_value, overflow_axis):
    item = {
        "id": "overflow-pair-fixture",
        "name": "Overflow Pair Fixture",
        "frontLat": "37.2",
        "frontLon": "127.2",
        "lat": "37.3",
        "lon": "127.3",
    }
    if overflow_axis == "latitude":
        item.update(noorLat="37.1", frontLat=overflow_value)
    else:
        item.update(noorLon="127.1", frontLon=overflow_value)
    before = deepcopy(item)

    results = _normalize([item])

    assert len(results) == 1
    assert results[0].point.latitude == 37.3
    assert results[0].point.longitude == 127.3
    assert item == before


@pytest.mark.parametrize("overflow_value", [10**400, -(10**400)], ids=["positive", "negative"])
@pytest.mark.parametrize("overflow_axis", ["latitude", "longitude"])
def test_large_integer_coordinate_pair_without_fallback_skips_only_that_item(overflow_value, overflow_axis):
    item = {
        "id": "overflow-no-fallback-fixture",
        "name": "Overflow No Fallback Fixture",
        "frontLat": "37.2",
        "frontLon": "127.2",
        "newLat": "37.4",
    }
    if overflow_axis == "latitude":
        item.update(noorLat="37.1", frontLat=overflow_value)
    else:
        item.update(noorLon="127.1", frontLon=overflow_value)
    campus, gate = _items()
    items = [campus, item, gate]
    before = deepcopy(items)

    results = _normalize(items)

    assert [result.model_dump() for result in results] == [
        result.model_dump() for result in _normalize([campus, gate])
    ]
    assert [result.name for result in results] == ["Campus Fixture", "Gate Fixture"]
    assert items == before
