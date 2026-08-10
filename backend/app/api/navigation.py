"""TMAP destination-search and walking-route endpoints."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query

from backend.app.config import Settings
from backend.app.schemas import DestinationSearchResponse, WalkingRouteRequest, WalkingRouteResponse
from backend.app.services.tmap_pedestrian import (
    TmapPedestrianRouteError,
    TmapPoiSearchError,
    fetch_tmap_pedestrian_route,
    fetch_tmap_poi_search,
    tmap_dependency_readiness,
)


def create_router(settings: Settings) -> APIRouter:
    router = APIRouter()

    def invalid_walking_route_provider() -> HTTPException:
        return HTTPException(
            status_code=503,
            detail={
                "code": "walking_route_provider_invalid",
                "message": "Configured walking route provider is not supported.",
            },
        )

    @router.get("/navigation/walking/health")
    async def get_walking_navigation_health() -> dict[str, object]:
        if settings.walking_route_provider == "tmap_pedestrian":
            dependency = tmap_dependency_readiness(settings.tmap_readiness_success_max_age_seconds)
            configured = bool(settings.tmap_app_key) and dependency["route_recent"] is True
            return {
                "provider": "tmap_pedestrian",
                "status": "ready" if configured else "unavailable",
                "reason": None if configured else (
                    "tmap_route_recent_success_unavailable" if settings.tmap_app_key else "tmap_app_key_missing"
                ),
                "walking_directions_url": settings.tmap_pedestrian_route_url,
            }

        raise invalid_walking_route_provider()


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
        if provider != "live":
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "tmap_poi_provider_invalid",
                    "message": "Configured destination search provider is not supported.",
                },
            )

        dependency = tmap_dependency_readiness(settings.tmap_readiness_success_max_age_seconds)
        configured = bool(settings.tmap_app_key) and dependency["poi_recent"] is True
        return {
            "provider": "tmap_poi",
            "mode": "live",
            "status": "ready" if configured else "unavailable",
            "reason": None if configured else (
                "tmap_poi_recent_success_unavailable" if settings.tmap_app_key else "tmap_app_key_missing"
            ),
            "poi_search_url": settings.tmap_poi_search_url,
        }

    @router.post("/navigation/walking", response_model=WalkingRouteResponse)
    async def create_walking_navigation_route(request: WalkingRouteRequest) -> WalkingRouteResponse:
        try:
            if settings.walking_route_provider == "tmap_pedestrian":
                return await fetch_tmap_pedestrian_route(request, settings)
            raise invalid_walking_route_provider()
        except TmapPedestrianRouteError as exc:
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
