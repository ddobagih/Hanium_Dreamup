"""Execute the S20-39 summary SQL against isolated PostgreSQL/PostGIS rows."""
from __future__ import annotations

import json
import math
import os
import struct
from fractions import Fraction
from types import SimpleNamespace

import pytest
from sqlalchemy import create_engine, select, text

from backend.app.models import Report
from backend.app.api.reports import _report_grid_cell
from backend.app.services.report_serialization import review_flags
from backend.app.services.report_summary_aggregation import (
    aggregate_report_summary,
    build_report_summary_statement,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


@pytest.fixture
def report_db():
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip())
    try:
        assert engine.dialect.name == "postgresql"
        with engine.connect() as connection:
            transaction = connection.begin()
            try:
                connection.execute(text("SELECT PostGIS_Version()"))
                connection.execute(text("SET LOCAL search_path = pg_temp, public"))
                connection.execute(text("""
                    CREATE TEMP TABLE reports (
                        id integer PRIMARY KEY,
                        status text NOT NULL,
                        source text NOT NULL,
                        latitude double precision,
                        longitude double precision,
                        metadata jsonb,
                        payload jsonb,
                        location geometry(Point, 4326)
                    ) ON COMMIT DROP
                """))
                rows = [
                    (1, "new", "fake", -0.25, 0.25, {}),
                    (2, "reviewed", "server", -0.25, 0.25, {"demo": True}),
                    (3, "new", "server", 0.25, 0.25,
                     {"demo": "true", "review_flags": "fake_source"}),
                    (4, "resolved", "android", 0.25, 0.25,
                     {"source_model": "FAKE"}),
                    (5, "new", "server", None, None,
                     {"review_flags": ["fake_source"]}),
                    (6, "reviewed", "server", None, None, "fake"),
                    (7, "new", "onnx", 0.25, 0.25, {"is_fake": True}),
                ]
                connection.execute(text("""
                    INSERT INTO reports (
                        id, status, source, latitude, longitude,
                        metadata, payload, location
                    ) VALUES (
                        :id, :status, :source, :latitude, :longitude,
                        CAST(:payload AS jsonb), CAST(:payload AS jsonb),
                        ST_SetSRID(ST_MakePoint(
                            CAST(:longitude AS double precision),
                            CAST(:latitude AS double precision)
                        ), 4326)
                    )
                """), [
                    {
                        "id": report_id,
                        "status": status,
                        "source": source,
                        "latitude": latitude,
                        "longitude": longitude,
                        "payload": json.dumps(payload),
                    }
                    for report_id, status, source, latitude, longitude, payload in rows
                ])
                yield connection
            finally:
                transaction.rollback()
    finally:
        engine.dispose()


def _grid_cell(coordinate: float, grid: float) -> tuple[int, float, float]:
    cell = math.floor(coordinate / grid)
    return cell, cell * grid, (cell + 1) * grid


def test_actual_summary_counts_grid_order_and_representatives(report_db):
    filtered = select(Report).where(Report.source != "onnx")
    statement = build_report_summary_statement(
        filtered, grid_size_degrees=0.1, top_limit=2,
    )
    raw = report_db.execute(statement).mappings().one()

    assert (raw["total"], raw["fake"], raw["located"]) == (6, 4, 4)
    assert raw["status_counts"] == {"new": 3, "reviewed": 2, "resolved": 1}
    assert raw["source_counts"] == {
        "fake": 1, "server": 4, "android": 1,
    }
    assert [cluster["key"] for cluster in raw["top_clusters"]] == ["-3:2", "2:2"]
    assert [(cluster["count"], cluster["fake"]) for cluster in raw["top_clusters"]] == [
        (2, 2), (2, 1),
    ]
    assert [cluster["representative_latitude_bits"] for cluster in raw["top_clusters"]] == [
        struct.pack(">d", -0.25).hex(), struct.pack(">d", 0.25).hex(),
    ]
    assert all(
        cluster["representative_longitude_bits"] == struct.pack(">d", 0.25).hex()
        for cluster in raw["top_clusters"]
    )

    summary = aggregate_report_summary(
        report_db, filtered, grid_size_degrees=0.1, top_limit=2,
        grid_cell=_grid_cell,
    )
    assert (summary["total"], summary["fake"], summary["non_fake"]) == (6, 4, 2)
    assert (summary["located"], summary["missing_location"]) == (4, 2)
    assert summary["bounds"] == {
        "min_latitude": -0.25, "max_latitude": 0.25,
        "min_longitude": 0.25, "max_longitude": 0.25,
    }
    clusters = summary["top_clusters"]
    assert [cluster["key"] for cluster in clusters] == ["-3:2", "2:2"]
    assert [cluster["center_latitude"] for cluster in clusters] == [-0.25, 0.25]
    assert [cluster["center_longitude"] for cluster in clusters] == [0.25, 0.25]
    assert [cluster["status_counts"] for cluster in clusters] == [
        {"new": 1, "reviewed": 1, "resolved": 0},
        {"new": 1, "reviewed": 0, "resolved": 1},
    ]
    assert [cluster["source_counts"] for cluster in clusters] == [
        {"fake": 1, "onnx": 0, "server": 1, "android": 0},
        {"fake": 0, "onnx": 0, "server": 1, "android": 1},
    ]
    assert clusters[0]["bounds"]["min_latitude"] == pytest.approx(-0.3)
    assert clusters[0]["bounds"]["max_latitude"] == pytest.approx(-0.2)
    assert clusters[1]["bounds"]["min_latitude"] == pytest.approx(0.2)
    assert clusters[1]["bounds"]["max_latitude"] == pytest.approx(0.3)


def test_actual_postgis_filter_and_zero_top_limit(report_db):
    filtered = (
        select(Report)
        .where(Report.source != "onnx")
        .where(text(
            "location IS NOT NULL AND ST_DWithin(location::geography, "
            "ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius_m)"
        ))
        .params(lng=0.25, lat=0.25, radius_m=1000.0)
    )
    raw = report_db.execute(build_report_summary_statement(
        filtered, grid_size_degrees=0.1, top_limit=0,
    )).mappings().one()

    assert (raw["total"], raw["fake"], raw["located"]) == (2, 1, 2)
    assert raw["status_counts"] == {"new": 1, "resolved": 1}
    assert raw["source_counts"] == {"server": 1, "android": 1}
    assert raw["top_clusters"] == []


def test_negative_tiny_grid_collapsed_fast_bounds_uses_exact_cell(report_db):
    report_db.execute(text(
        "UPDATE reports SET latitude = -1.0, longitude = 0.0 WHERE id = 1"
    ))
    raw = report_db.execute(build_report_summary_statement(
        select(Report).where(Report.id == 1),
        grid_size_degrees=3 * 2 ** -56,
        top_limit=1,
    )).mappings().one()

    assert raw["total"] == 1
    assert [cluster["key"] for cluster in raw["top_clusters"]] == [
        "-24019198012642646:0",
    ]


def test_actual_summary_strict_json_fake_classification_matches_serializer(report_db):
    payloads = [
        None, [], "fake_source", True, 1, {},
        {"fake_source": True}, {"fake_source": 1}, {"fake_source": "true"},
        {"fake_source": False}, {"fake_source": None},
        {"demo": True}, {"is_demo": True}, {"is_fake": True},
        {"demo": "true", "is_demo": 1, "is_fake": "true"},
        {"data_origin": "demo"}, {"data_origin": "Demo"},
        {"review_flags": ["fake_source", "unrelated"]},
        {"review_flags": "fake_source"}, {"review_flags": [["fake_source"]]},
        {"metadata": {"fake_source": True, "review_flags": ["fake_source"]}},
        {"source_model": ["fake"]}, {"source_model": "owned-fa\u212ae-model"},
        {"source_model": "\u0130FAKE"}, {"source_model": "\uff26\uff21\uff2b\uff25"},
        {"source_model": "de\u0130mo"},
    ]
    vectors = [("server", payload) for payload in payloads] + [("fake", {}), ("Fake", {})]
    for source, payload in vectors:
        report_db.execute(text(
            "UPDATE reports SET source = :source, metadata = CAST(:payload AS jsonb) WHERE id = 1"
        ), {"source": source, "payload": json.dumps(payload)})
        expected_fake = "fake_source" in review_flags(SimpleNamespace(
            source=source, payload=payload, latitude=None, longitude=None,
            accuracy_m=None, confidence=1.0, heading=0.0,
        ))
        summary = aggregate_report_summary(
            report_db, select(Report).where(Report.id == 1),
            top_limit=0, grid_cell=_report_grid_cell,
        )
        assert (summary["total"], summary["fake"], summary["non_fake"]) == (
            1, int(expected_fake), int(not expected_fake),
        ), (source, payload)


@pytest.mark.parametrize("grid", [1.0, 2.0 ** -60, 2.0 ** -1022, 2.0 ** -1074])
def test_actual_summary_binary_grid_arithmetic_and_float_json_round_trip(report_db, grid):
    latitude, longitude = -37.125, 127.125
    report_db.execute(text(
        "UPDATE reports SET latitude = :lat, longitude = :lng WHERE id = 1"
    ), {"lat": latitude, "lng": longitude})
    summary = aggregate_report_summary(
        report_db, select(Report).where(Report.id == 1),
        grid_size_degrees=grid, top_limit=1, grid_cell=_report_grid_cell,
    )
    cluster = json.loads(json.dumps(summary, allow_nan=False))["top_clusters"][0]
    expected_cells = [math.floor(Fraction(value) / Fraction(grid)) for value in (latitude, longitude)]
    assert cluster["key"] == f"{expected_cells[0]}:{expected_cells[1]}"
    assert (cluster["center_latitude"], cluster["center_longitude"]) == (latitude, longitude)
    for axis, value in (("latitude", latitude), ("longitude", longitude)):
        _, lower, upper = _report_grid_cell(value, grid)
        assert cluster["bounds"][f"min_{axis}"] == lower
        assert cluster["bounds"][f"max_{axis}"] == upper


def test_actual_summary_adjacent_float_boundaries_and_text_tie_order(report_db):
    coordinates = [math.nextafter(-0.001, -math.inf), -0.001,
                   math.nextafter(0.001, 0.0), math.nextafter(0.001, math.inf)]
    for report_id, latitude in enumerate(coordinates, start=1):
        report_db.execute(text(
            "UPDATE reports SET latitude = :lat, longitude = 0.0 WHERE id = :id"
        ), {"lat": latitude, "id": report_id})
    summary = aggregate_report_summary(
        report_db, select(Report).where(Report.id <= 4),
        grid_size_degrees=0.001, top_limit=4, grid_cell=_report_grid_cell,
    )
    assert [cluster["key"] for cluster in summary["top_clusters"]] == [
        "-1:0", "-2:0", "0:0", "1:0",
    ]
    for cluster in summary["top_clusters"]:
        assert cluster["count"] == 1
        assert cluster["bounds"]["min_latitude"] <= cluster["center_latitude"]
        assert cluster["center_latitude"] < cluster["bounds"]["max_latitude"]
