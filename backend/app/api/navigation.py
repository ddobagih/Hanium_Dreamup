from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query

from backend.app.config import Settings
from backend.app.schemas import DestinationSearchResponse, WalkingRouteRequest, WalkingRouteResponse
from backend.app.services.kakao_mobility import KakaoMobilityRouteError, fetch_kakao_walking_route
from backend.app.services.tmap_pedestrian import TmapPedestrianRouteError, TmapPoiSearchError, fetch_tmap_pedestrian_route, fetch_tmap_poi_search


def create_router(settings: Settings) -> APIRouter:
    router = APIRouter()

    @router.get("/navigation/walking/health")
    async def get_walking_navigation_health() -> dict[str, object]:
        if settings.walking_route_provider == "kakao_mobility":
            configured = bool(settings.kakao_mobility_rest_api_key)
            return {
                "provider": "kakao_mobility",
                "status": "ready" if configured else "unavailable",
                "reason": None if configured else "kakao_api_key_missing",
                "walking_directions_url": settings.kakao_mobility_walking_directions_url,
            }

        configured = bool(settings.tmap_app_key)
        return {
            "provider": "tmap_pedestrian",
            "status": "ready" if configured else "unavailable",
            "reason": None if configured else "tmap_app_key_missing",
            "walking_directions_url": settings.tmap_pedestrian_route_url,
        }


    @router.get("/navigation/destinations/search", response_model=DestinationSearchResponse)
    async def search_navigation_destinations(
        query: str = Query(min_length=1, max_length=80),
        limit: int = Query(default=5, ge=1, le=10),
        origin_lat: float | None = Query(default=None, ge=-90, le=90),
        origin_lng: float | None = Query(default=None, ge=-180, le=180),
    ) -> DestinationSearchResponse:
        try:
            return await fetch_tmap_poi_search(
                query,
                settings,
                limit=limit,
                origin_lat=origin_lat,
                origin_lng=origin_lng,
            )
        except TmapPoiSearchError as exc:
            raise HTTPException(
                status_code=exc.http_status,
                detail={
                    "code": exc.code,
                    "message": exc.message,
                    "provider_status": exc.provider_status,
                    "provider_result_code": exc.provider_result_code,
                },
            ) from exc

    @router.get("/navigation/destinations/search/health")
    async def get_destination_search_health() -> dict[str, object]:
        provider = getattr(settings, "tmap_poi_provider", "live")
        if provider == "mock":
            return {
                "provider": "tmap_poi",
                "mode": "mock",
                "status": "ready",
                "reason": None,
                "poi_search_url": None,
            }

        configured = bool(settings.tmap_app_key)
        return {
            "provider": "tmap_poi",
            "mode": "live",
            "status": "ready" if configured else "unavailable",
            "reason": None if configured else "tmap_app_key_missing",
            "poi_search_url": settings.tmap_poi_search_url,
        }

    @router.post("/navigation/walking", response_model=WalkingRouteResponse)
    async def create_walking_navigation_route(request: WalkingRouteRequest) -> WalkingRouteResponse:
        try:
            if settings.walking_route_provider == "kakao_mobility":
                return await fetch_kakao_walking_route(request, settings)
            return await fetch_tmap_pedestrian_route(request, settings)
        except (KakaoMobilityRouteError, TmapPedestrianRouteError) as exc:
            raise HTTPException(
                status_code=exc.http_status,
                detail={
                    "code": exc.code,
                    "message": exc.message,
                    "provider_status": exc.provider_status,
                    "provider_result_code": exc.provider_result_code,
                },
            ) from exc

    return router
