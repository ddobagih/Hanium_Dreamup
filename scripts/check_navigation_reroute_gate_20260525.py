#!/usr/bin/env python3
"""Static safety gate for WalkSafe reroute behavior.

The app may issue an automatic live walking-route provider request after confirmed
off-route detection, but it must stay behind GPS quality, GPS jump, in-flight,
cooldown, and max-count guards.
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NAV_HOOK = ROOT / "apps/web/app/_walksafe/hooks/useNavigationGuidance.ts"
REQUIRED_PROMPT = "경로를 벗어나 자동 재탐색을 시작합니다."


def fail(message: str) -> int:
    print(f"FAIL: {message}")
    return 1


def block_between(source: str, start_token: str, end_token: str) -> str | None:
    start = source.find(start_token)
    if start < 0:
        return None
    end = source.find(end_token, start)
    if end < 0:
        return None
    return source[start:end]


def main() -> int:
    if not NAV_HOOK.exists():
        return fail(f"navigation hook not found: {NAV_HOOK}")

    source = NAV_HOOK.read_text(encoding="utf-8")
    if REQUIRED_PROMPT not in source:
        return fail("reroute speech prompt is missing")
    if 'effectiveStatus === "rerouting"' not in source:
        return fail("rerouting effectiveStatus guard is missing")
    if "rerouteSpeechPrompt" not in source or "rerouteSpeechKey" not in source:
        return fail("reroute speech prompt/key state is missing")
    for token in (
        "WALKSAFE_ROUTE_REQUEST_COOLDOWN_MS",
        "WALKSAFE_AUTO_REROUTE_COOLDOWN_MS",
        "WALKSAFE_AUTO_REROUTE_MAX_COUNT",
        "WALKSAFE_REROUTE_MAX_ACCURACY_M",
        "WALKSAFE_REROUTE_MAX_GPS_JUMP_M",
        "resolveNavigationRouteRequestGate",
        "resolveAutoRerouteDecision",
        "NavigationRequestCoordinator",
        "requestCoordinator.routeInFlight",
        "lastRouteRequestStartedAtMsRef",
        "cancelPendingRouteRequest",
        "isCurrentRouteRequest",
        "previousGpsSampleRef",
    ):
        if token not in source:
            return fail(f"route request guard token is missing: {token}")

    start_navigation = block_between(source, "const startNavigation = useCallback", "const stopNavigation = useCallback")
    if start_navigation is None:
        return fail("startNavigation block could not be located")

    auto_reroute = block_between(source, 'if (effectiveStatus !== "rerouting" || !route)', "const effectiveMessage = useMemo")
    if auto_reroute is None:
        return fail("automatic reroute effect block could not be located")

    cancel_request = block_between(
        source,
        "const cancelPendingRouteRequest = useCallback",
        "const isCurrentRouteRequest = useCallback",
    )
    if cancel_request is None:
        return fail("route request cancellation block could not be located")
    for token in ("requestCoordinator.cancelRoute()",):
        if token not in cancel_request:
            return fail(f"route request cancellation is incomplete: {token}")

    total_route_fetches = source.count("fetchWalkingRoute(")
    start_route_fetches = start_navigation.count("fetchWalkingRoute(")
    auto_route_fetches = auto_reroute.count("fetchWalkingRoute(")
    if total_route_fetches != start_route_fetches + auto_route_fetches:
        return fail("fetchWalkingRoute is called outside approved start/auto-reroute blocks")
    if start_route_fetches != 1:
        return fail(f"expected exactly one explicit startNavigation route request; found {start_route_fetches}")
    if auto_route_fetches != 1:
        return fail(f"expected exactly one guarded automatic reroute request; found {auto_route_fetches}")

    gate_index = start_navigation.find("resolveNavigationRouteRequestGate({")
    fetch_index = start_navigation.find("fetchWalkingRoute(")
    in_flight_set_index = start_navigation.find("requestCoordinator.beginRoute()")
    cooldown_set_index = start_navigation.find("lastRouteRequestStartedAtMsRef.current = nowMs")
    in_flight_clear_index = start_navigation.find("requestCoordinator.finish(requestToken)")
    if gate_index < 0 or gate_index > fetch_index:
        return fail("route request gate must run before fetchWalkingRoute")
    if in_flight_set_index < gate_index or in_flight_set_index > fetch_index:
        return fail("in-flight guard must be set before fetchWalkingRoute")
    if cooldown_set_index < gate_index or cooldown_set_index > fetch_index:
        return fail("cooldown timestamp must be set before fetchWalkingRoute")
    if "finally" not in start_navigation or in_flight_clear_index < fetch_index:
        return fail("in-flight guard must be cleared in a finally block after fetchWalkingRoute")
    for token in (
        "{ signal: requestToken.controller.signal }",
        "if (!isCurrentRouteRequest(requestToken))",
    ):
        if token not in start_navigation:
            return fail(f"explicit route request stale-response guard is missing: {token}")

    decision_index = auto_reroute.find("resolveAutoRerouteDecision({")
    auto_fetch_index = auto_reroute.find("fetchWalkingRoute(")
    auto_in_flight_set_index = auto_reroute.find("requestCoordinator.beginRoute()")
    auto_cooldown_set_index = auto_reroute.find("lastRouteRequestStartedAtMsRef.current = nowMs")
    auto_count_index = auto_reroute.find("count: autoState.count + 1")
    auto_finally_index = auto_reroute.find("finally")
    auto_clear_index = auto_reroute.find("requestCoordinator.finish(requestToken)")
    if decision_index < 0 or decision_index > auto_fetch_index:
        return fail("auto reroute decision gate must run before fetchWalkingRoute")
    if auto_in_flight_set_index < decision_index or auto_in_flight_set_index > auto_fetch_index:
        return fail("auto reroute must set in-flight guard before fetchWalkingRoute")
    if auto_cooldown_set_index < decision_index or auto_cooldown_set_index > auto_fetch_index:
        return fail("auto reroute must set cooldown timestamp before fetchWalkingRoute")
    if auto_count_index < decision_index or auto_count_index > auto_fetch_index:
        return fail("auto reroute max-count state must increment before fetchWalkingRoute")
    if "autoRerouteStateRef.current = { count: 0 }" in auto_reroute:
        return fail("successful reroute must not reset the navigation-session auto-reroute count")
    if auto_finally_index < auto_fetch_index or auto_clear_index < auto_finally_index:
        return fail("auto reroute in-flight guard must be cleared in finally")
    for token in (
        "{ signal: requestToken.controller.signal }",
        "if (!isCurrentRouteRequest(requestToken))",
    ):
        if token not in auto_reroute:
            return fail(f"automatic route request stale-response guard is missing: {token}")

    stop_navigation = block_between(source, "const stopNavigation = useCallback", "useEffect(() => () =>")
    destination_search = block_between(
        source,
        "const setVoiceDestination = useCallback",
        "const selectDestinationCandidate = useCallback",
    )
    destination_selection = block_between(
        source,
        "const selectDestinationCandidate = useCallback",
        "const selectDestinationCandidateByIndex = useCallback",
    )
    arrival = block_between(
        source,
        "if (!route || !routeArrival?.reached",
        "const stabilizedRouteProgress = useMemo",
    )
    for label, block in (
        ("stopNavigation", stop_navigation),
        ("destination search", destination_search),
        ("destination selection", destination_selection),
        ("arrival", arrival),
    ):
        if block is None or "cancelPendingRouteRequest();" not in block:
            return fail(f"{label} must invalidate pending route requests")
    if arrival is not None and arrival.find("cancelPendingRouteRequest();") > arrival.find("const timer"):
        return fail("arrival must invalidate a pending reroute before scheduling route state changes")
    for token in (
        "gps_accuracy_poor",
        "gps_jump",
        "max_count",
        "request_in_flight",
        "cooldown",
    ):
        if token not in source:
            return fail(f"auto reroute decision reason missing: {token}")

    print("PASS: automatic reroute keeps the live provider request behind safety gates")
    print("checked: explicit startNavigation and automatic reroute are the only route request blocks")
    print("checked: GPS accuracy/jump, in-flight, cooldown, and session max-count guards are present")
    print("checked: stop, destination changes, and arrival invalidate stale route responses")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
