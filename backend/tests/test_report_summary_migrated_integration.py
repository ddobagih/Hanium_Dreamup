"""S20-39 summary SQL on the Alembic-head reports table, not a temp stand-in."""
from __future__ import annotations

from datetime import UTC, datetime
import json
import math
import os
from pathlib import Path
import uuid

from alembic.config import Config
from alembic.script import ScriptDirectory
import pytest
from sqlalchemy import create_engine, text

from backend.app.api.reports import _filtered_reports_statement
from backend.app.database import get_db
from backend.app.main import app
from backend.app.services.report_summary_aggregation import aggregate_report_summary
from asgi_client import ASGITestClient


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


@pytest.fixture
def migrated_reports():
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip())
    marker = f"s20-39-{uuid.uuid4().hex}"
    head = ScriptDirectory.from_config(
        Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    ).get_current_head()
    try:
        assert engine.dialect.name == "postgresql"
        with engine.connect() as connection:
            transaction = connection.begin()
            try:
                current = connection.execute(
                    text("SELECT version_num FROM public.alembic_version")
                ).scalar_one()
                assert current == head, f"test database must be at Alembic head {head}"
                rows = [
                    ("new", "fake", -0.25, 0.25, {}),
                    ("reviewed", "server", -0.25, 0.25,
                     {"demo": True, "trigger": "auto", "auto_reported": True,
                      "performance_excluded": False}),
                    ("resolved", "server", 0.25, 0.25, {"demo": "true"}),
                    ("new", "android", None, None,
                     {"review_flags": ["fake_source"]}),
                    ("new", "fake", 0.25, 0.25,
                     {"model_key": marker + "-outside"}),
                ]
                captured_at = datetime.now(UTC)
                connection.execute(text("""
                    INSERT INTO public.reports (
                        id, status, class_id, class_name, confidence,
                        bbox_x, bbox_y, bbox_width, bbox_height, captured_at,
                        source, latitude, longitude, location,
                        image_path, image_content_type, metadata
                    ) VALUES (
                        :id, :status, 1, 'damaged_tactile_block', 0.9,
                        0.1, 0.1, 0.5, 0.5, :captured_at,
                        :source, :latitude, :longitude,
                        ST_SetSRID(ST_MakePoint(
                            CAST(:longitude AS double precision),
                            CAST(:latitude AS double precision)
                        ), 4326),
                        :image_path, 'image/jpeg', CAST(:payload AS jsonb)
                    )
                """), [
                    {
                        "id": report_id,
                        "status": status,
                        "captured_at": captured_at,
                        "source": source,
                        "latitude": latitude,
                        "longitude": longitude,
                        "image_path": f"summary-migrated-{report_id}.jpg.enc",
                        "payload": json.dumps({
                            "schema_version": "detect.v2",
                            "model_key": marker,
                            **extra_payload,
                        }),
                    }
                    for report_id, (status, source, latitude, longitude, extra_payload)
                    in ((uuid.uuid4(), row) for row in rows)
                ])
                yield connection, marker
            finally:
                transaction.rollback()
    finally:
        engine.dispose()


def _grid_cell(coordinate: float, grid: float) -> tuple[int, float, float]:
    cell = math.floor(coordinate / grid)
    return cell, cell * grid, (cell + 1) * grid


def _api_filtered_summary(
    connection, marker: str, *, status=None,
    lat=None, lng=None, radius_m=None,
):
    filtered = _filtered_reports_statement(
        status=status,
        class_name=None,
        source=None,
        demo_filter="all",
        model_key=marker,
        trigger=None,
        auto_reported=None,
        performance_excluded=None,
        created_from=None,
        created_to=None,
        lat=lat,
        lng=lng,
        radius_m=radius_m,
    )
    return aggregate_report_summary(
        connection, filtered, grid_size_degrees=0.1,
        top_limit=2, grid_cell=_grid_cell,
    )


def test_migrated_reports_api_model_key_filter_and_summary_response(migrated_reports):
    connection, marker = migrated_reports
    summary = _api_filtered_summary(connection, marker)

    assert (summary["total"], summary["fake"], summary["non_fake"]) == (4, 3, 1)
    assert (summary["located"], summary["missing_location"]) == (3, 1)
    assert summary["status_counts"] == {"new": 2, "reviewed": 1, "resolved": 1}
    assert summary["source_counts"] == {
        "fake": 1, "onnx": 0, "server": 2, "android": 1,
    }
    assert summary["bounds"] == {
        "min_latitude": -0.25, "max_latitude": 0.25,
        "min_longitude": 0.25, "max_longitude": 0.25,
    }
    clusters = summary["top_clusters"]
    assert [cluster["key"] for cluster in clusters] == ["-3:2", "2:2"]
    assert [(cluster["count"], cluster["fake"]) for cluster in clusters] == [
        (2, 2), (1, 0),
    ]
    assert [cluster["center_latitude"] for cluster in clusters] == [-0.25, 0.25]
    assert [cluster["center_longitude"] for cluster in clusters] == [0.25, 0.25]


def test_migrated_reports_api_status_and_postgis_radius_filter(migrated_reports):
    connection, marker = migrated_reports
    summary = _api_filtered_summary(
        connection, marker, status="resolved",
        lat=0.25, lng=0.25, radius_m=100.0,
    )

    assert (summary["total"], summary["fake"], summary["located"]) == (1, 0, 1)
    assert summary["status_counts"] == {"new": 0, "reviewed": 0, "resolved": 1}
    assert summary["source_counts"] == {
        "fake": 0, "onnx": 0, "server": 1, "android": 0,
    }
    assert [(cluster["key"], cluster["count"]) for cluster in summary["top_clusters"]] == [
        ("2:2", 1),
    ]


@pytest.mark.parametrize(
    ("filters", "expected_total", "expected_fake", "expected_keys"),
    [
        ({}, 4, 3, ["-3:2", "2:2"]),
        ({"status": "resolved", "lat": 0.25, "lng": 0.25, "radius_m": 100},
         1, 0, ["2:2"]),
        ({"source": "onnx"}, 0, 0, []),
        ({"top_limit": 0}, 4, 3, []),
        ({"top_limit": 1}, 4, 3, ["-3:2"]),
        ({"demo_filter": "only_fake"}, 2, 2, ["-3:2"]),
        # Existing demo filtering is broader/different from strict summary
        # classification; SQL aggregation must preserve both contracts.
        ({"demo_filter": "exclude_fake"}, 2, 1, ["-3:2", "2:2"]),
        ({"status": "reviewed", "source": "server", "class_name": "damaged_tactile_block",
          "trigger": "auto", "auto_reported": True, "performance_excluded": False,
          "created_from": "2000-01-01T00:00:00Z", "created_to": "2100-01-01T00:00:00Z",
          "lat": -0.25, "lng": 0.25, "radius_m": 100},
         1, 1, ["-3:2"]),
        ({"created_to": "2000-01-01T00:00:00Z"}, 0, 0, []),
    ],
)
def test_migrated_summary_http_json_filters_and_empty_result(
    migrated_reports, monkeypatch, filters, expected_total, expected_fake, expected_keys,
):
    connection, marker = migrated_reports
    monkeypatch.setitem(app.dependency_overrides, get_db, lambda: connection)
    response = ASGITestClient(app).get(
        "/reports/summary",
        params={"model_key": marker, "grid_size_degrees": 0.1, **filters},
    )

    assert response.status_code == 200, response.text
    summary = response.json()
    assert (summary["total"], summary["fake"], summary["non_fake"]) == (
        expected_total, expected_fake, expected_total - expected_fake,
    )
    assert sum(summary["status_counts"].values()) == expected_total
    assert sum(summary["source_counts"].values()) == expected_total
    assert [cluster["key"] for cluster in summary["top_clusters"]] == expected_keys
    assert summary["located"] + summary["missing_location"] == expected_total
    if expected_total == 0:
        assert summary["bounds"] is None
        assert summary["status_counts"] == {"new": 0, "reviewed": 0, "resolved": 0}
        assert summary["source_counts"] == {"fake": 0, "onnx": 0, "server": 0, "android": 0}
    for cluster in summary["top_clusters"]:
        assert cluster["fake"] + cluster["non_fake"] == cluster["count"]
        assert sum(cluster["status_counts"].values()) == cluster["count"]
        assert sum(cluster["source_counts"].values()) == cluster["count"]
        for axis in ("latitude", "longitude"):
            assert cluster["bounds"][f"min_{axis}"] <= cluster[f"center_{axis}"]
            assert cluster[f"center_{axis}"] < cluster["bounds"][f"max_{axis}"]
