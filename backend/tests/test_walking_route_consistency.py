"""Backend half of the route consistency cases shared with Android."""

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from backend.app.schemas import WalkingRouteResponse


CASES_PATH = Path(__file__).resolve().parents[2] / "tests/fixtures/walking_route_consistency.json"
CASES = json.loads(CASES_PATH.read_text(encoding="utf-8"))["cases"]


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["name"])
def test_walking_route_cross_field_contract(case):
    if case["valid"]:
        assert WalkingRouteResponse.model_validate(case["route"]).schema_version == "walksafe.walking_route.v1"
    else:
        with pytest.raises(ValidationError):
            WalkingRouteResponse.model_validate(case["route"])
