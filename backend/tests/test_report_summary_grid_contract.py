"""S20-38: public summary grid minimum and the cell membership contract."""
from __future__ import annotations

from fractions import Fraction
import json
import math
from pathlib import Path
import random

import pytest

from asgi_client import ASGITestClient
from backend.app.api import reports
from backend.app.database import get_db
from backend.app.main import app
from backend.app.services.report_summary_aggregation import (
    REPORT_SUMMARY_MAX_GRID_SIZE_DEGREES,
    REPORT_SUMMARY_MIN_GRID_SIZE_DEGREES,
)


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "contracts" / "walksafe.openapi.json"


@pytest.fixture
def summary_client(monkeypatch: pytest.MonkeyPatch):
    calls: list[float] = []

    def aggregate(_db, _statement, *, grid_size_degrees, top_limit, grid_cell):
        calls.append(grid_size_degrees)
        return {"total": 0, "located": 0, "grid_size_degrees": grid_size_degrees}

    def no_database():
        yield object()

    monkeypatch.setattr(reports, "aggregate_report_summary", aggregate)
    app.dependency_overrides[get_db] = no_database
    try:
        yield ASGITestClient(app), calls
    finally:
        app.dependency_overrides.pop(get_db, None)


def test_s20_38_public_minimum_is_about_eleven_metres() -> None:
    assert REPORT_SUMMARY_MIN_GRID_SIZE_DEGREES == 0.0001
    assert REPORT_SUMMARY_MAX_GRID_SIZE_DEGREES == 1.0


@pytest.mark.parametrize(
    "value",
    ["0", "-0.001", "0.00009999", "5e-324", "1.0000001", "nan", "inf", "-inf"],
)
def test_s20_38_summary_query_rejects_sizes_outside_the_public_range(
    summary_client, value: str,
) -> None:
    client, calls = summary_client
    response = client.get("/reports/summary", params={"grid_size_degrees": value})
    assert response.status_code == 422, response.text
    locations = [item["loc"] for item in response.json()["detail"]]
    assert ["query", "grid_size_degrees"] in locations
    assert calls == []


@pytest.mark.parametrize("value", ["0.0001", "0.001", "1"])
def test_s20_38_summary_query_accepts_the_public_range(summary_client, value: str) -> None:
    client, calls = summary_client
    response = client.get("/reports/summary", params={"grid_size_degrees": value})
    assert response.status_code == 200, response.text
    assert calls == [float(value)]


def _summary_grid_parameter(document: dict) -> dict:
    operation = document["paths"]["/reports/summary"]["get"]
    return next(
        item for item in operation["parameters"] if item["name"] == "grid_size_degrees"
    )


def test_s20_38_runtime_and_checked_contract_publish_the_same_grid_bounds() -> None:
    runtime = _summary_grid_parameter(app.openapi())
    checked = _summary_grid_parameter(json.loads(CONTRACT.read_text(encoding="utf-8")))
    assert runtime == checked
    schema = checked["schema"]
    assert schema["minimum"] == 0.0001
    assert schema["maximum"] == 1
    assert "exclusiveMinimum" not in schema
    assert "exact rational" in checked["description"]


def _exact_bounds(cell: int, grid: float) -> tuple[Fraction, Fraction]:
    exact_grid = Fraction(grid)
    return cell * exact_grid, (cell + 1) * exact_grid


def _assert_contract(coordinate: float, grid: float) -> None:
    cell, lower, upper = reports._report_grid_cell(coordinate, grid)
    # At the public minimum and valid coordinates the display cell never collapses.
    assert math.isfinite(lower) and math.isfinite(upper) and lower < upper
    assert lower <= coordinate <= upper
    float_cell = math.floor(coordinate / grid)
    float_lower, float_upper = float_cell * grid, (float_cell + 1) * grid
    if float_lower <= coordinate < float_upper:
        # Float half-open membership is kept when it is representable.
        assert (cell, lower, upper) == (float_cell, float_lower, float_upper)
        assert coordinate < upper
    else:
        # Rounding contradiction: the exact rational floor decides.
        exact_lower, exact_upper = _exact_bounds(cell, grid)
        assert cell == math.floor(Fraction(coordinate) / Fraction(grid))
        assert exact_lower <= Fraction(coordinate) < exact_upper
        assert (lower, upper) == (float(exact_lower), float(exact_upper))


@pytest.mark.parametrize("coordinate", [
    180.0, -180.0, 90.0, -90.0, 0.0, -0.0,
    math.nextafter(180.0, 0.0), math.nextafter(-180.0, 0.0),
    math.nextafter(90.0, 0.0), math.nextafter(-90.0, 0.0),
    37.5665, -37.5665, 127.0276, -127.0276, 0.29, -0.29, 56.7132, -139.1347,
])
@pytest.mark.parametrize("grid", [0.0001, 0.00015, 0.001, 0.01, 0.1, 1.0])
def test_s20_38_boundary_contract_at_and_above_the_public_minimum(
    coordinate: float, grid: float,
) -> None:
    _assert_contract(coordinate, grid)


def test_s20_38_boundary_contract_holds_for_sampled_cell_edges() -> None:
    generator = random.Random(3820)
    checked = fallback = 0
    for grid in (0.0001, 0.0003, 0.001, 0.003, 0.01, 0.07, 0.1, 0.3, 1.0):
        limit = int(180 / grid)
        for _ in range(2_000):
            coordinate = generator.randint(-limit, limit) * grid
            for _ in range(generator.randint(0, 2)):
                coordinate = math.nextafter(coordinate, generator.choice((-math.inf, math.inf)))
            if abs(coordinate) > 180:
                continue
            _assert_contract(coordinate, grid)
            checked += 1
            if coordinate == reports._report_grid_cell(coordinate, grid)[2]:
                fallback += 1
    assert checked > 17_000
    # The exact fallback is exercised, not only the float fast path.
    assert fallback > 0


def test_s20_38_rounded_upper_limit_is_the_documented_exact_fallback() -> None:
    cell, lower, upper = reports._report_grid_cell(0.29, 0.01)
    assert (cell, lower, upper) == (28, 0.28, 0.29)
    exact_lower, exact_upper = _exact_bounds(cell, 0.01)
    assert exact_lower <= Fraction(0.29) < exact_upper
    assert upper == 0.29 and float(exact_upper) == 0.29
