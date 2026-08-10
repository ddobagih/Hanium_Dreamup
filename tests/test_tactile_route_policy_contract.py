from __future__ import annotations

import json
from pathlib import Path


FIXTURE = Path(__file__).parent / "fixtures/navigation/tactile_route_policy_cases.json"


def test_tactile_route_fixture_covers_override_and_fallback_boundaries() -> None:
    payload = json.loads(FIXTURE.read_text(encoding="utf-8"))
    assert payload["schema_version"] == "walksafe.tactile_route_policy_cases.v1"

    cases = payload["cases"]
    assert len({case["id"] for case in cases}) == len(cases)
    by_id = {case["id"]: case for case in cases}

    assert by_id["no-tactile-uses-tmap"]["expected"]["mode"] == "tmap"
    assert by_id["off-route-never-uses-local-tactile"]["expected"]["reason"] == "tmap_off_route"
    assert by_id["stable-aligned-center-uses-tactile"]["expected"]["mode"] == "tactile_local"
    assert by_id["stable-aligned-right-steers-right"]["expected"]["steering"] == "right"
    assert by_id["single-frame-stays-on-tmap"]["expected"]["mode"] == "tmap"
    assert by_id["misaligned-tactile-stays-on-tmap"]["expected"]["mode"] == "tmap"
    assert by_id["outside-route-corridor-stays-on-tmap"]["expected"]["mode"] == "tmap"
    assert by_id["poor-gps-stays-on-tmap"]["expected"]["mode"] == "tmap"
    assert by_id["damaged-tactile-is-never-a-local-path"]["expected"]["mode"] == "tmap"
    assert by_id["stale-tactile-falls-back-to-tmap"]["expected"]["mode"] == "tmap"


def test_tactile_route_fixture_thresholds_are_fail_closed() -> None:
    thresholds = json.loads(FIXTURE.read_text(encoding="utf-8"))["thresholds"]
    assert thresholds["minimum_confidence"] >= 0.5
    assert thresholds["minimum_stable_frames"] >= 3
    assert thresholds["minimum_stable_ms"] >= 700
    assert thresholds["maximum_detection_age_ms"] <= 1500
    assert thresholds["maximum_route_heading_delta_deg"] <= 35
    assert thresholds["maximum_gps_accuracy_m"] <= 25
