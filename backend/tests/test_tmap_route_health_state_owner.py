"""Readiness ownership preserves time, reset and completion-order boundaries."""

from concurrent.futures import ThreadPoolExecutor

import pytest

from backend.app.services.tmap_route_health_state import TmapRouteHealthState


def test_evidence_age_uses_injected_clock_and_inclusive_freshness_boundary():
    now = [100.0]
    state = TmapRouteHealthState(clock=lambda: now[0])
    state.record_success("route")
    state.record_success("poi")
    now[0] = 130.0
    assert state.readiness(30.0) == {
        "ready": True,
        "provider": "tmap_pedestrian",
        "evidence": "recent_success",
        "route_recent": True,
        "poi_recent": True,
        "route_state": "recent_success",
    }
    now[0] = 130.001
    assert state.readiness(30.0)["route_state"] == "unverified"
    assert state.readiness(30.0)["ready"] is False
    # The existing clock-rewind guard clamps negative ages to zero.
    assert state.readiness(0.0, now=99.0)["ready"] is True


def test_late_success_refreshes_evidence_without_replacing_newer_failure():
    now = [100.0]
    state = TmapRouteHealthState(clock=lambda: now[0])
    older, newer = state.begin_route_check(), state.begin_route_check()
    state.record_route_failure(newer)
    now[0] = 120.0
    state.record_success("route", route_check=older)
    state.record_success("poi")
    assert state.readiness(30.0)["route_state"] == "recent_failure"
    assert state.readiness(30.0)["ready"] is True
    now[0] = 131.0
    # Observation expiry must use its own timestamp, not the last success.
    assert state.readiness(30.0)["route_state"] == "unverified"
    assert state.readiness(30.0)["ready"] is True


@pytest.mark.parametrize("late_success", [False, True])
def test_reset_fences_old_generation_even_after_check_order_is_reused(late_success):
    state = TmapRouteHealthState(clock=lambda: 100.0)
    stale = state.begin_route_check()
    state.record_success("poi")
    state.reset()
    current = state.begin_route_check()
    assert stale[1] == current[1]
    assert stale[0] != current[0]
    state.record_route_failure(current)
    if late_success:
        state.record_success("route", route_check=stale)
    else:
        state.record_route_failure(stale)
    snapshot = state.readiness(30.0)
    assert snapshot["route_state"] == "recent_failure"
    assert snapshot["route_recent"] is False
    assert snapshot["poi_recent"] is False


def test_state_instances_and_returned_snapshots_do_not_share_mutable_evidence():
    first = TmapRouteHealthState(clock=lambda: 10.0)
    second = TmapRouteHealthState(clock=lambda: 10.0)
    first.record_success("route")
    snapshot = first.readiness(30.0)
    snapshot["route_state"] = "recent_failure"
    assert first.readiness(30.0)["route_state"] == "recent_success"
    assert second.readiness(30.0)["route_state"] == "unverified"
    second.reset()
    assert first.readiness(30.0)["route_recent"] is True


def test_concurrent_checks_have_unique_order_and_ignore_older_failure():
    state = TmapRouteHealthState(clock=lambda: 100.0)
    with ThreadPoolExecutor(max_workers=8) as executor:
        checks = list(executor.map(lambda _: state.begin_route_check(), range(200)))
        assert sorted(order for _, order in checks) == list(range(1, 201))
        assert len({generation for generation, _ in checks}) == 1
        state.record_success("route", route_check=max(checks))
        list(executor.map(state.record_route_failure, sorted(checks)[:-1]))
    assert state.readiness(30.0)["route_state"] == "recent_success"


def test_unsupported_dependency_does_not_change_readiness():
    state = TmapRouteHealthState(clock=lambda: 100.0)
    before = state.readiness(30.0)
    with pytest.raises(ValueError, match="unsupported TMAP dependency"):
        state.record_success("unknown")
    assert state.readiness(30.0) == before
