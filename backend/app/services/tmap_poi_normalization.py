"""Normalize TMAP destination candidates, addresses and query text without I/O."""

from __future__ import annotations

from hashlib import sha256
import json
from math import isfinite
import re
from typing import Any

from backend.app.schemas import (
    DestinationCoordinateCandidate,
    DestinationSearchResponse,
    DestinationSearchResult,
    RoutePoint,
)
from backend.app.services.tmap_response_normalization import (
    TmapPoiSearchError,
    _ensure_tmap_deadline,
    _haversine_m,
    _json_has_oversized_string,
)


KOREAN_DESTINATION_ACTION_RE = re.compile(
    r"\s*(?:안내해줘|안내해|가자|목적지\s*설정(?:해)?|설정(?:해)?|지정(?:해)?)\s*$"
)


TMAP_MAX_POI_RESULTS = 100


MOCK_POI_ITEMS: tuple[dict[str, Any], ...] = (
    {
        "id": "mock-seoul-station",
        "name": "서울역",
        "latitude": 37.5546788,
        "longitude": 126.9706069,
        "address": "서울 중구 봉래동2가",
        "road_address": "서울 중구 한강대로 405",
        "category": "교통",
        "aliases": ("서울역", "서울역으로", "서울역까지"),
    },
    {
        "id": "mock-city-hall",
        "name": "시청역",
        "latitude": 37.5657037,
        "longitude": 126.9768616,
        "address": "서울 중구 정동",
        "road_address": "서울 중구 세종대로 101",
        "category": "교통",
        "aliases": ("시청", "시청역", "서울시청"),
    },
    {
        "id": "mock-gangnam-station",
        "name": "강남역",
        "latitude": 37.497952,
        "longitude": 127.027619,
        "address": "서울 강남구 역삼동",
        "road_address": "서울 강남구 강남대로 396",
        "category": "교통",
        "aliases": ("강남", "강남역"),
    },
)


def normalize_destination_query(query: str) -> str:
    normalized = " ".join(query.split()).strip()
    action = KOREAN_DESTINATION_ACTION_RE.search(normalized)
    if action:
        normalized = normalized[: action.start()].strip()
        if normalized.endswith("까지"):
            normalized = normalized[:-2].strip()
        elif normalized.endswith("으로"):
            normalized = normalized[:-2].strip()
        elif normalized.endswith("로로"):
            normalized = normalized[:-1].strip()
        # Same prefix contract as the Android client, which re-checks the echoed query.
        normalized = re.sub(r"^목적지(?:를|을|로)?\s*", "", normalized).strip()
    return normalized


def _poi_text(payload: dict[str, Any], name: str) -> str | None:
    value = payload.get(name)
    if not isinstance(value, str):
        return None
    text = value.strip()
    return text or None


def _poi_text_or_number(payload: dict[str, Any], name: str) -> str | None:
    value = payload.get(name)
    if type(value) is int or (type(value) is float and isfinite(value)):
        return str(value)
    return _poi_text(payload, name)


def _poi_float(payload: dict[str, Any], *names: str) -> float | None:
    for name in names:
        value = payload.get(name)
        if value in (None, "") or isinstance(value, bool):
            continue
        try:
            number = float(value)
        except (TypeError, ValueError):
            continue
        if isfinite(number):
            return number
    return None


_POI_COORDINATE_FAMILIES = (
    ("noor", "noorLat", "noorLon"),
    ("front", "frontLat", "frontLon"),
    ("lat", "lat", "lon"),
    ("new", "newLat", "newLon"),
)


def _poi_coordinate_candidates(payload: dict[str, Any]) -> list[tuple[str, float, float]]:
    """Every complete, in-range provider coordinate family in selection priority."""
    candidates: list[tuple[str, float, float]] = []
    for kind, latitude_key, longitude_key in _POI_COORDINATE_FAMILIES:
        try:
            latitude = _poi_float(payload, latitude_key)
            longitude = _poi_float(payload, longitude_key)
        except OverflowError:
            continue
        if latitude is None or longitude is None:
            continue
        if latitude < -90 or latitude > 90 or longitude < -180 or longitude > 180:
            continue
        candidates.append((kind, latitude, longitude))
    return candidates


def _poi_coordinate_pair(payload: dict[str, Any]) -> tuple[float, float] | None:
    candidates = _poi_coordinate_candidates(payload)
    if not candidates:
        return None
    _, latitude, longitude = candidates[0]
    return latitude, longitude


def _poi_distance_m(payload: dict[str, Any]) -> int | None:
    radius_km = _poi_float(payload, "radius")
    if radius_km is None or radius_km < 0:
        return None
    try:
        return int(round(radius_km * 1000))
    except (ValueError, OverflowError):
        return None


def _join_address(*parts: str | None) -> str | None:
    text = " ".join(part for part in parts if part)
    return text or None


def _join_address_number(first: str | None, second: str | None) -> str | None:
    if not second or re.fullmatch(r"[+-]?0+(?:\.0+)?", second):
        return first
    if not first:
        return second
    return f"{first}-{second}"


def _poi_nested_road_address(
    payload: dict[str, Any],
    coordinates: tuple[float, float],
    *,
    deadline_at: float | None = None,
) -> str | None:
    container = payload.get("newAddressList")
    rows = container.get("newAddress") if isinstance(container, dict) else None
    if not isinstance(rows, list):
        return None
    addresses: set[str] = set()
    matching_addresses: set[str] = set()
    for index, row in enumerate(rows):
        if index % 32 == 0:
            _ensure_tmap_deadline(deadline_at)
        if not isinstance(row, dict):
            continue
        address = _poi_text(row, "fullAddressRoad")
        if not address:
            continue
        addresses.add(address)
        for latitude_key, longitude_key in (("centerLat", "centerLon"), ("frontLat", "frontLon")):
            try:
                pair = (_poi_float(row, latitude_key), _poi_float(row, longitude_key))
            except OverflowError:
                continue
            if pair == coordinates:
                matching_addresses.add(address)
    # One address in this POI is unambiguous. Different facilities/entrances
    # require a unique match to the selected point; never guess the nearest row.
    if len(matching_addresses) == 1:
        return next(iter(matching_addresses))
    if len(addresses) == 1:
        return next(iter(addresses))
    return None


def _normalize_poi_search_response(
    payload: Any,
    query: str,
    *,
    deadline_at: float | None = None,
    _strings_checked: bool = False,
) -> DestinationSearchResponse:
    if not isinstance(payload, dict):
        raise TmapPoiSearchError(code="invalid_tmap_response", message="TMAP POI search response is not JSON object.")
    if not _strings_checked and _json_has_oversized_string(payload, deadline_at=deadline_at):
        raise TmapPoiSearchError(
            code="invalid_tmap_response",
            message="TMAP POI search response contains an oversized string.",
        )

    search_info = payload.get("searchPoiInfo")
    if not isinstance(search_info, dict):
        raise TmapPoiSearchError(code="invalid_tmap_response", message="TMAP POI search response has no searchPoiInfo.")

    total_count = None
    if "totalCount" in search_info:
        raw_count = search_info["totalCount"]
        if type(raw_count) is int and raw_count >= 0:
            total_count = raw_count
        elif isinstance(raw_count, str) and re.fullmatch(r"[0-9]+", raw_count.strip()):
            try:
                total_count = int(raw_count)
            except ValueError as exc:
                raise TmapPoiSearchError(
                    code="invalid_tmap_response",
                    message="TMAP POI search response violates the WalkSafe schema.",
                ) from exc
        else:
            raise TmapPoiSearchError(
                code="invalid_tmap_response",
                message="TMAP POI search response violates the WalkSafe schema.",
            )

    pois = search_info.get("pois")
    if isinstance(pois, dict):
        raw_items = pois.get("poi")
    elif pois is None and total_count == 0:
        raw_items = []
    else:
        raise TmapPoiSearchError(
            code="invalid_tmap_response",
            message="TMAP POI search response violates the WalkSafe schema.",
        )
    if isinstance(raw_items, dict):
        items = [raw_items]
    elif isinstance(raw_items, list):
        items = raw_items
    elif raw_items is None and total_count == 0:
        items = []
    else:
        raise TmapPoiSearchError(
            code="invalid_tmap_response",
            message="TMAP POI search response violates the WalkSafe schema.",
        )
    # totalCount spans all pages; only empty/nonempty consistency is required.
    if total_count is not None and bool(items) != (total_count > 0):
        raise TmapPoiSearchError(
            code="invalid_tmap_response",
            message="TMAP POI search response violates the WalkSafe schema.",
        )
    if len(items) > TMAP_MAX_POI_RESULTS:
        raise TmapPoiSearchError(
            code="invalid_tmap_response",
            message="TMAP POI search response exceeds the safe result limit.",
        )

    results: list[DestinationSearchResult] = []
    seen_candidate_ids: set[str] = set()
    for item_index, item in enumerate(items):
        if item_index % 32 == 0:
            _ensure_tmap_deadline(deadline_at)
        if not isinstance(item, dict):
            continue

        name = _poi_text(item, "name")
        coordinate_candidates = _poi_coordinate_candidates(item)
        if not name or not coordinate_candidates:
            continue
        coordinate_kind, latitude, longitude = coordinate_candidates[0]
        coordinates = (latitude, longitude)

        road_address = _poi_nested_road_address(item, coordinates, deadline_at=deadline_at) or _join_address(
            _poi_text(item, "upperRoadAddrName"),
            _poi_text(item, "middleRoadAddrName"),
            _poi_text(item, "lowerRoadAddrName"),
            _poi_text(item, "roadName"),
            _join_address_number(_poi_text_or_number(item, "firstBuildNo"), _poi_text_or_number(item, "secondBuildNo")),
        )
        address = _join_address(
            _poi_text(item, "upperAddrName"),
            _poi_text(item, "middleAddrName"),
            _poi_text(item, "lowerAddrName"),
            _poi_text(item, "detailAddrName"),
            _join_address_number(_poi_text_or_number(item, "firstNo"), _poi_text_or_number(item, "secondNo")),
        )

        destination = DestinationSearchResult(
                id=_poi_text_or_number(item, "id") or _poi_text_or_number(item, "pkey") or f"{latitude},{longitude}",
                name=name,
                point=RoutePoint(latitude=latitude, longitude=longitude, name=name),
                # S07-38: keep coordinate meaning and front/other families; the
                # selected point and route target are unchanged.
                coordinate_kind=coordinate_kind,
                coordinate_candidates=[
                    DestinationCoordinateCandidate(
                        kind=kind,
                        point=RoutePoint(latitude=candidate_latitude, longitude=candidate_longitude),
                    )
                    for kind, candidate_latitude, candidate_longitude in coordinate_candidates
                ],
                address=address,
                road_address=road_address,
                category=(
                    _poi_text(item, "bizCatName")
                    or _poi_text(item, "catName")
                    or _poi_text(item, "lowerBizName")
                    or _poi_text(item, "middleBizName")
                    or _poi_text(item, "upperBizName")
                    or _poi_text(item, "bizName")
                ),
                result_type="poi",
                distance_m=_poi_distance_m(item),
        )
        if destination.distance_m is not None:
            destination.distance_source = "tmap_radius"
        # Provider IDs can be shared by distinct entrances or facilities, so the
        # provider keys stay paired with the facility name and selected point. Address
        # and category are display data: a new provider spelling is the same candidate.
        # This is an opaque key, independent of query, ordering and distance.
        identity = (
            destination.id,
            _poi_text_or_number(item, "pkey"),
            destination.name,
            destination.point.latitude,
            destination.point.longitude,
        )
        identity_bytes = json.dumps(identity, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        destination.id = "tmap-poi:v1:" + sha256(identity_bytes).hexdigest()
        if destination.id in seen_candidate_ids:
            continue
        seen_candidate_ids.add(destination.id)
        results.append(destination)
        if len(results) > TMAP_MAX_POI_RESULTS:
            raise TmapPoiSearchError(
                code="invalid_tmap_response",
                message="TMAP POI search response exceeds the safe result limit.",
            )

    _ensure_tmap_deadline(deadline_at)
    return DestinationSearchResponse(
        schema_version="walksafe.destination_search.v1",
        provider="tmap_poi",
        query=query,
        results=results,
        total_count=total_count,
    )


def _mock_poi_search_response(
    query: str,
    *,
    limit: int,
    origin_lat: float | None = None,
    origin_lng: float | None = None,
) -> DestinationSearchResponse:
    normalized_query = normalize_destination_query(query)
    keyword = _destination_search_intent(normalized_query)[0] if (
        origin_lat is not None and origin_lng is not None
    ) else normalized_query
    compact_query = keyword.replace(" ", "")
    results: list[DestinationSearchResult] = []

    for item in MOCK_POI_ITEMS:
        aliases = tuple(str(alias).replace(" ", "") for alias in item.get("aliases", ()))
        name = str(item["name"])
        name_compact = name.replace(" ", "")
        if compact_query not in aliases and compact_query not in name_compact and not any(compact_query in alias for alias in aliases):
            continue

        latitude = float(item["latitude"])
        longitude = float(item["longitude"])
        distance_m = (
            _haversine_m(origin_lat, origin_lng, latitude, longitude)
            if origin_lat is not None and origin_lng is not None
            else None
        )
        results.append(
            DestinationSearchResult(
                id=str(item["id"]),
                name=name,
                point=RoutePoint(latitude=latitude, longitude=longitude, name=name),
                address=str(item["address"]),
                road_address=str(item["road_address"]),
                category=str(item["category"]),
                result_type="alias" if compact_query in aliases else "poi",
                distance_m=distance_m,
                distance_source="straight_line" if distance_m is not None else None,
            )
        )

    return DestinationSearchResponse(
        schema_version="walksafe.destination_search.v1",
        provider="tmap_poi",
        query=normalized_query,
        results=sorted(results, key=lambda result: result.distance_m if result.distance_m is not None else 10**9)[:limit],
        total_count=len(results),
        more_results_available=len(results) > limit,
    )


def _is_named_university_query(query: str) -> bool:
    words = query.split()
    if len(words) > 1 and (len(words) != 2 or words[0] not in ("국립", "공립", "사립")):
        return False
    compact_query = "".join(words)
    if any(marker in compact_query for marker in ("주변", "근처", "인근", "가까운")):
        return False
    match = re.fullmatch(r"([가-힣A-Za-z0-9]+?)(?:대학교|대학|공대)", compact_query)
    return match is not None and match.group(1) not in ("국립", "공립", "사립")



def _destination_search_intent(query: str) -> tuple[str, bool]:
    # Only an explicit modifier + generic category is unambiguous. Preserve names
    # such as "가까운약국", branches and "서울역 근처 편의점" (a different search origin).
    match = re.fullmatch(
        r"(?:가장 가까운|가까운|근처|주변|인근)\s+"
        r"(편의점|약국|병원|카페|식당|음식점|화장실|지하철역|기차역|은행|마트|도서관|공원)",
        query,
    )
    return (match.group(1), True) if match else (query, False)


def _is_named_station_query(query: str) -> bool:
    compact_query = "".join(query.split())
    if any(marker in compact_query for marker in ("주변", "근처", "인근", "가까운")):
        return False
    match = re.fullmatch(r"([가-힣A-Za-z0-9]+?)역", compact_query)
    return match is not None and match.group(1) not in (
        "지하철", "전철", "기차", "철도", "고속철도",
    )
