#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.config import get_settings  # noqa: E402
from backend.app.schemas import RoutePoint, WalkingRouteRequest  # noqa: E402
from backend.app.services.tmap_pedestrian import (  # noqa: E402
    TmapPedestrianRouteError,
    fetch_tmap_pedestrian_route,
)


def env_float(name: str, default: float) -> float:
    raw_value = os.getenv(name)
    if not raw_value:
        return default
    return float(raw_value)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "TMAP pedestrian route live smoke. By default this performs a safe dry-run only; "
            "pass --i-understand-live-tmap to send one real request that may consume quota."
        )
    )
    parser.add_argument("--origin-lat", type=float, default=env_float("TMAP_SMOKE_ORIGIN_LAT", 37.39472714688412))
    parser.add_argument("--origin-lng", type=float, default=env_float("TMAP_SMOKE_ORIGIN_LNG", 127.11015314141542))
    parser.add_argument("--origin-name", default=os.getenv("TMAP_SMOKE_ORIGIN_NAME", "현재 위치"))
    parser.add_argument("--destination-lat", type=float, default=env_float("TMAP_SMOKE_DESTINATION_LAT", 37.401937080111644))
    parser.add_argument("--destination-lng", type=float, default=env_float("TMAP_SMOKE_DESTINATION_LNG", 127.10824367964793))
    parser.add_argument("--destination-name", default=os.getenv("TMAP_SMOKE_DESTINATION_NAME", "테스트 목적지"))
    parser.add_argument("--timeout-seconds", type=float, default=None, help="Override TMAP request timeout for this smoke.")
    parser.add_argument(
        "--i-understand-live-tmap",
        action="store_true",
        help="Actually call the live TMAP API once. Requires TMAP_APP_KEY and may consume provider quota.",
    )
    return parser


async def main() -> int:
    args = build_parser().parse_args()
    settings = get_settings()
    if args.timeout_seconds is not None:
        settings.tmap_timeout_seconds = args.timeout_seconds

    origin = RoutePoint(latitude=args.origin_lat, longitude=args.origin_lng, name=args.origin_name)
    destination = RoutePoint(latitude=args.destination_lat, longitude=args.destination_lng, name=args.destination_name)
    request = WalkingRouteRequest(origin=origin, destination=destination, priority="STAIR_AVOID")

    print(f"TMAP appKey configured: {'yes' if settings.tmap_app_key else 'no'}")
    print(f"origin=({origin.latitude}, {origin.longitude}) destination=({destination.latitude}, {destination.longitude})")

    if not args.i_understand_live_tmap:
        print("DRY-RUN: live TMAP request was not sent. Re-run with --i-understand-live-tmap after quota/terms approval.")
        return 0

    if not settings.tmap_app_key:
        print("TMAP_APP_KEY is missing. Put it in backend/.env and rerun this smoke.")
        return 2

    try:
        route = await fetch_tmap_pedestrian_route(request, settings)
    except TmapPedestrianRouteError as exc:
        print(
            "TMAP smoke failed: "
            f"code={exc.code} http_status={exc.http_status} provider_status={exc.provider_status} "
            f"message={exc.message}"
        )
        return 1

    if route.provider != "tmap_pedestrian" or not route.polyline:
        print("TMAP smoke failed: invalid normalized route response")
        return 1

    print(
        "TMAP smoke OK: "
        f"distance_m={route.summary.distance_m} duration_s={route.summary.duration_s} "
        f"polyline_points={len(route.polyline)} steps={len(route.steps)}"
    )
    first_instruction = next((step.instruction for step in route.steps if step.instruction), None)
    if first_instruction:
        print(f"first_instruction={first_instruction}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
