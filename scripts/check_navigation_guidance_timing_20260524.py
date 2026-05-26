#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import os
from dataclasses import dataclass
from pathlib import Path
import sys
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.config import get_settings  # noqa: E402
from backend.app.schemas import RoutePoint, WalkingRouteGuidePoint, WalkingRouteRequest  # noqa: E402
from backend.app.services.tmap_pedestrian import (  # noqa: E402
    TmapPedestrianRouteError,
    fetch_tmap_pedestrian_route,
)


TURN_RADIUS_M = 4.0
SOON_RADIUS_M = 8.0
SOON_SECONDS = 3.0
EARLY_SECONDS = 10.0
DEFAULT_STEP_LENGTH_M = 0.65


@dataclass(frozen=True)
class GuideAction:
    kind: str
    label: str


def env_float(name: str, default: float) -> float:
    raw_value = os.getenv(name)
    if not raw_value:
        return default
    return float(raw_value)


def action_from_turn_type(turn_type: int | None) -> GuideAction | None:
    mapping = {
        12: GuideAction("left", "좌회전"),
        13: GuideAction("right", "우회전"),
        14: GuideAction("uturn", "유턴"),
        16: GuideAction("direction", "왼쪽 8시 방향"),
        17: GuideAction("direction", "왼쪽 10시 방향"),
        18: GuideAction("direction", "오른쪽 2시 방향"),
        19: GuideAction("direction", "오른쪽 4시 방향"),
        125: GuideAction("stairs", "육교"),
        126: GuideAction("stairs", "지하보도"),
        127: GuideAction("stairs", "계단"),
        201: GuideAction("destination", "목적지"),
        211: GuideAction("crosswalk", "횡단보도"),
        212: GuideAction("crosswalk", "횡단보도"),
        213: GuideAction("crosswalk", "횡단보도"),
    }
    return mapping.get(turn_type)


def action_from_instruction(instruction: str | None) -> GuideAction | None:
    normalized = " ".join((instruction or "").split())
    if not normalized:
        return None
    if "좌회전" in normalized:
        return GuideAction("left", "좌회전")
    if "우회전" in normalized:
        return GuideAction("right", "우회전")
    if "유턴" in normalized:
        return GuideAction("uturn", "유턴")
    if "횡단보도" in normalized:
        return GuideAction("crosswalk", "횡단보도")
    if "도착" in normalized:
        return GuideAction("destination", "목적지")
    if "직진" in normalized:
        return GuideAction("straight", "직진")
    return None


def action_from_guide(guide: WalkingRouteGuidePoint) -> GuideAction | None:
    return action_from_turn_type(guide.turn_type) or action_from_instruction(guide.instruction)


def steps(distance_m: float, step_length_m: float) -> int:
    return max(1, round(distance_m / step_length_m))


def prompt(action: GuideAction, stage: str, distance_m: float, step_length_m: float) -> str:
    step_count = steps(distance_m, step_length_m)
    if stage == "now":
        if action.kind == "destination":
            return "목적지에 도착했습니다."
        if action.kind in {"crosswalk", "stairs"}:
            return f"지금 {action.label}입니다."
        if action.kind == "straight":
            return "계속 직진하세요."
        if action.kind == "direction":
            return f"지금 {action.label}으로 이동하세요."
        return f"지금 {action.label}하세요."
    if stage == "soon3":
        if action.kind == "destination":
            return f"곧 목적지입니다. 약 {step_count}보 앞입니다."
        if action.kind in {"crosswalk", "stairs"}:
            return f"곧 {action.label}입니다. 약 {step_count}보 앞입니다."
        return f"곧 {action.label}입니다. 약 {step_count}보 앞입니다."
    if stage == "prepare10":
        if action.kind == "destination":
            return f"10초 뒤 목적지입니다. 약 {step_count}보 앞입니다."
        if action.kind in {"crosswalk", "stairs"}:
            return f"10초 뒤 {action.label}입니다. 약 {step_count}보 앞입니다."
        return f"10초 뒤 {action.label} 준비. 약 {step_count}보 앞입니다."
    if action.kind == "destination":
        return f"약 {step_count}보 앞에 목적지입니다."
    if action.kind in {"crosswalk", "stairs"}:
        return f"약 {step_count}보 앞에 {action.label}입니다."
    return f"약 {step_count}보 앞에서 {action.label}."


def prompt_for_distance(action: GuideAction, distance_m: float, speed_mps: float | None, step_length_m: float) -> tuple[str, str] | None:
    eta_s = None if speed_mps is None or speed_mps <= 0 else distance_m / speed_mps
    if distance_m <= TURN_RADIUS_M:
        return "now", prompt(action, "now", distance_m, step_length_m)
    if (eta_s is not None and eta_s <= SOON_SECONDS) or distance_m <= SOON_RADIUS_M:
        return "soon3", prompt(action, "soon3", distance_m, step_length_m)
    if eta_s is not None and eta_s <= EARLY_SECONDS:
        return "prepare10", prompt(action, "prepare10", distance_m, step_length_m)
    if distance_m <= step_length_m * 20:
        return "prepare10", prompt(action, "step_fallback", distance_m, step_length_m)
    return None


def sample_rows(action: GuideAction, speed_mps: float, step_length_m: float) -> list[tuple[str, float, str]]:
    distances = [
        ("prepare_sample", speed_mps * EARLY_SECONDS),
        ("soon_sample", max(speed_mps * SOON_SECONDS, SOON_RADIUS_M)),
        ("now_sample", TURN_RADIUS_M),
    ]
    rows: list[tuple[str, float, str]] = []
    for label, distance_m in distances:
        result = prompt_for_distance(action, distance_m, speed_mps, step_length_m)
        if result is None:
            continue
        stage, text = result
        rows.append((label, distance_m, f"{stage}: {text}"))
    return rows


def build_parser() -> argparse.ArgumentParser:
    settings = get_settings()
    default_speed_mps = settings.tmap_pedestrian_speed_kmh / 3.6
    parser = argparse.ArgumentParser(description="TMAP route guide point timing table for WalkSafe navigation TTS.")
    parser.add_argument("--origin-lat", type=float, default=env_float("TMAP_SMOKE_ORIGIN_LAT", 37.39472714688412))
    parser.add_argument("--origin-lng", type=float, default=env_float("TMAP_SMOKE_ORIGIN_LNG", 127.11015314141542))
    parser.add_argument("--destination-lat", type=float, default=env_float("TMAP_SMOKE_DESTINATION_LAT", 37.401937080111644))
    parser.add_argument("--destination-lng", type=float, default=env_float("TMAP_SMOKE_DESTINATION_LNG", 127.10824367964793))
    parser.add_argument("--origin-name", default=os.getenv("TMAP_SMOKE_ORIGIN_NAME", "현재 위치"))
    parser.add_argument("--destination-name", default=os.getenv("TMAP_SMOKE_DESTINATION_NAME", "테스트 목적지"))
    parser.add_argument("--speed-mps", type=float, default=default_speed_mps, help="Inspection speed only. Frontend uses live GPS speed.")
    parser.add_argument(
        "--step-length-m",
        type=float,
        default=env_float("NEXT_PUBLIC_WALKSAFE_STEP_LENGTH_M", DEFAULT_STEP_LENGTH_M),
    )
    parser.add_argument("--timeout-seconds", type=float, help="Override TMAP request timeout for this smoke run.")
    parser.add_argument("--show-skipped", action="store_true")
    parser.add_argument(
        "--i-understand-live-tmap",
        action="store_true",
        help="Send a real TMAP route request. Defaults to dry-run to avoid quota/cost surprises.",
    )
    return parser


async def main() -> int:
    args = build_parser().parse_args()
    if args.speed_mps <= 0:
        raise SystemExit("--speed-mps must be positive")
    if not 0.3 <= args.step_length_m <= 1.2:
        raise SystemExit("--step-length-m must be between 0.3 and 1.2")

    if not args.i_understand_live_tmap:
        print(
            "DRY-RUN: live TMAP timing request was not sent. "
            "Re-run with --i-understand-live-tmap after quota/terms approval."
        )
        fallback_action = GuideAction("left", "좌회전")
        print(f"speed_mps={args.speed_mps:.2f} step_length_m={args.step_length_m:.2f}")
        for label, distance_m, text in sample_rows(fallback_action, args.speed_mps, args.step_length_m):
            print(f"{label} at {distance_m:.1f}m / {steps(distance_m, args.step_length_m)}보 -> {text}")
        return 0

    settings = get_settings()
    if args.timeout_seconds is not None:
        if args.timeout_seconds <= 0:
            raise SystemExit("--timeout-seconds must be positive")
        settings.tmap_timeout_seconds = args.timeout_seconds
    request = WalkingRouteRequest(
        origin=RoutePoint(latitude=args.origin_lat, longitude=args.origin_lng, name=args.origin_name),
        destination=RoutePoint(latitude=args.destination_lat, longitude=args.destination_lng, name=args.destination_name),
        priority="STAIR_AVOID",
    )
    try:
        route = await fetch_tmap_pedestrian_route(request, settings)
    except TmapPedestrianRouteError as exc:
        print(f"navigation timing check failed: code={exc.code} http_status={exc.http_status} message={exc.message}")
        return 1

    print(
        "route "
        f"distance_m={route.summary.distance_m} duration_s={route.summary.duration_s} "
        f"guide_points={len(route.guide_points)} speed_mps={args.speed_mps:.2f} step_length_m={args.step_length_m:.2f}"
    )
    actionable = 0
    for guide in route.guide_points:
        action = action_from_guide(guide)
        if action is None:
            if args.show_skipped:
                print(f"- guide#{guide.index} skipped turn_type={guide.turn_type} instruction={guide.instruction!r}")
            continue
        actionable += 1
        distance_from_start = guide.distance_from_start_m if guide.distance_from_start_m is not None else "?"
        print(
            f"- guide#{guide.index} action={action.label} turn_type={guide.turn_type} "
            f"distance_from_start_m={distance_from_start} instruction={guide.instruction!r}"
        )
        for label, distance_m, text in sample_rows(action, args.speed_mps, args.step_length_m):
            print(f"  {label} at {distance_m:.1f}m / {steps(distance_m, args.step_length_m)}보 -> {text}")

    print(f"actionable_guides={actionable}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
