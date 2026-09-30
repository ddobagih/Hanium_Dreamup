"""S20-39: aggregate more than the former 10,000-report ceiling on real tables."""
from __future__ import annotations

from datetime import UTC, datetime
import json
import os
from pathlib import Path
import uuid

from alembic.config import Config
from alembic.script import ScriptDirectory
import pytest
from sqlalchemy import create_engine, event, text

from backend.app.api import reports
from backend.app.database import get_db
from backend.app.main import app
from asgi_client import ASGITestClient


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

_REPORT_COUNT = 10_001
_BATCH_SIZE = 500
_INSERT = text("""
    INSERT INTO public.reports (
        id, status, class_id, class_name, confidence,
        bbox_x, bbox_y, bbox_width, bbox_height, captured_at,
        source, latitude, longitude, location,
        image_path, image_content_type, metadata
    ) VALUES (
        :id, 'new', 1, 'damaged_tactile_block', 0.9,
        0.1, 0.1, 0.5, 0.5, :captured_at,
        :source, :latitude, :longitude,
        ST_SetSRID(ST_MakePoint(
            CAST(:longitude AS double precision),
            CAST(:latitude AS double precision)
        ), 4326),
        :image_path, 'image/jpeg', CAST(:payload AS jsonb)
    )
""")


def _report_row(index: int, marker: str, captured_at: datetime) -> dict[str, object]:
    report_id = uuid.uuid4()
    if index < 5_000:
        source, latitude, longitude = "fake", -0.25, 0.25
        payload = {"schema_version": "detect.v2", "model_key": marker}
    elif index < 10_000:
        source, latitude, longitude = "server", 0.25, 0.25
        payload = {
            "schema_version": "detect.v2", "model_key": marker,
            "demo": "true",
        }
    else:
        source, latitude, longitude = "server", None, None
        payload = {
            "schema_version": "detect.v2", "model_key": marker,
            "is_fake": True,
        }
    return {
        "id": report_id,
        "captured_at": captured_at,
        "source": source,
        "latitude": latitude,
        "longitude": longitude,
        "image_path": f"summary-large-{report_id}.jpg.enc",
        "payload": json.dumps(payload),
    }


def test_migrated_postgres_summary_aggregates_above_10000_without_orm_scan(monkeypatch):
    engine = create_engine(
        os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(),
        connect_args={"connect_timeout": 5},
    )
    marker = f"s20-39-large-{uuid.uuid4().hex}"
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
                # These limits are local to this test transaction, not service policy.
                connection.execute(text("SET LOCAL lock_timeout = '5s'"))
                connection.execute(text("SET LOCAL statement_timeout = '120s'"))

                captured_at = datetime.now(UTC)
                for start in range(0, _REPORT_COUNT, _BATCH_SIZE):
                    connection.execute(_INSERT, [
                        _report_row(index, marker, captured_at)
                        for index in range(start, min(start + _BATCH_SIZE, _REPORT_COUNT))
                    ])

                executed_sql: list[str] = []

                def capture_statement(
                    _connection, _cursor, statement, _parameters, _context, _executemany,
                ) -> None:
                    executed_sql.append(statement)

                event.listen(connection, "before_cursor_execute", capture_statement)
                try:
                    monkeypatch.setitem(app.dependency_overrides, get_db, lambda: connection)

                    def forbid_orm_scan(*_args, **_kwargs):
                        raise AssertionError("summary must not hydrate or scan Report objects")

                    monkeypatch.setattr(reports, "_reports_summary", forbid_orm_scan)
                    monkeypatch.setattr(reports, "_bounded_summary_reports", forbid_orm_scan)
                    monkeypatch.setattr(reports, "report_to_response", forbid_orm_scan)
                    response = ASGITestClient(app).get(
                        "/reports/summary",
                        params={"model_key": marker, "grid_size_degrees": 0.5, "top_limit": 2},
                    )
                finally:
                    event.remove(connection, "before_cursor_execute", capture_statement)

                assert response.status_code == 200, response.text
                assert response.headers["content-type"].startswith("application/json")
                summary = response.json()
                assert len(executed_sql) == 1
                sql = executed_sql[0]
                assert "s39_filtered AS" in sql and "s39_global AS" in sql
                assert "count(*)" in sql and "jsonb_agg(" in sql
                assert "reports.image_path" not in sql and "reports.bbox_x" not in sql
                assert "LIMIT 10000" not in sql

                assert (summary["total"], summary["fake"], summary["non_fake"]) == (
                    10_001, 5_001, 5_000,
                )
                assert (summary["located"], summary["missing_location"]) == (
                    10_000, 1,
                )
                assert summary["status_counts"] == {
                    "new": 10_001, "reviewed": 0, "resolved": 0,
                }
                assert summary["source_counts"] == {
                    "fake": 5_000, "onnx": 0, "server": 5_001, "android": 0,
                }
                clusters = summary["top_clusters"]
                assert [cluster["key"] for cluster in clusters] == ["-1:0", "0:0"]
                assert [(cluster["count"], cluster["fake"]) for cluster in clusters] == [
                    (5_000, 5_000), (5_000, 0),
                ]
                assert [cluster["center_latitude"] for cluster in clusters] == [
                    -0.25, 0.25,
                ]
                assert [cluster["center_longitude"] for cluster in clusters] == [
                    0.25, 0.25,
                ]
                assert [cluster["bounds"] for cluster in clusters] == [
                    {"min_latitude": -0.5, "max_latitude": 0.0,
                     "min_longitude": 0.0, "max_longitude": 0.5},
                    {"min_latitude": 0.0, "max_latitude": 0.5,
                     "min_longitude": 0.0, "max_longitude": 0.5},
                ]
            finally:
                transaction.rollback()
    finally:
        engine.dispose()
