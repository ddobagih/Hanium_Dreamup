from __future__ import annotations

import csv
from datetime import datetime, timedelta, timezone
import io
import json
import os
from pathlib import Path
import sys

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

from backend.app.config import get_settings  # noqa: E402
from backend.app.main import app  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402


JPEG_BYTES = b"\xff\xd8\xff\xe0" + (b"0" * 16)


@pytest.fixture(scope="session", autouse=True)
def migrated_database() -> None:
    settings = get_settings()
    engine = create_engine(settings.database_url)
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
    except SQLAlchemyError as exc:
        pytest.skip(f"PostGIS test database is not reachable: {exc}")

    config = Config(str(ROOT / "backend" / "alembic.ini"))
    command.upgrade(config, "head")


@pytest.fixture
def client() -> ASGITestClient:
    return ASGITestClient(app)


def sample_v2_metadata(**overrides: object) -> dict[str, object]:
    metadata = {
        "schema_version": "detect.v2",
        "model_key": "custom_tactile",
        "source_model": "fake/custom-tactile-contract",
        "model_class_id": 1,
        "class_name": "damaged_tactile_block",
        "category": "tactile",
        "confidence": 0.91,
        "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
        "threshold_used": 0.25,
        "captured_at": "2026-05-22T12:00:00.000Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 181.0,
        "trigger": "auto",
        "auto_reported": True,
    }
    metadata.update(overrides)
    return metadata


def ids(response: object) -> set[str]:
    assert isinstance(response, list)
    return {str(report["id"]) for report in response}


def csv_rows(response) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO(response.text.lstrip("\ufeff"))))


def fresh_created_from() -> str:
    return (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()


def post_report_v2(client: ASGITestClient, metadata: dict[str, object]):
    return client.post(
        "/reports/v2",
        data={"metadata": json.dumps(metadata)},
        files={"image": ("sample.jpg", JPEG_BYTES, "image/jpeg")},
    )


def test_create_report_v2_stores_allowed_tactile_damage(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(distance_m=1.4, trace_id="trace-policy-1")

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["class_id"] == metadata["model_class_id"]
    assert body["class_name"] == "damaged_tactile_block"
    assert body["source"] == "fake"
    assert body["metadata"]["distance_m"] == 1.4
    for key, value in metadata.items():
        assert body["metadata"][key] == value
    assert body["metadata"]["trace_id"] == "trace-policy-1"
    assert len(body["metadata"]["payload_sha256"]) == 64
    assert len(body["metadata"]["image_sha256"]) == 64
    assert body["metadata"]["data_origin"] == "demo"
    assert body["metadata"]["runtime_mode"] == "fake"
    assert body["metadata"]["performance_excluded"] is True
    assert "fake_source" in body["review_flags"]


def test_create_report_v2_drops_unknown_extra_metadata_but_keeps_review_flags(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(review_flags=["fake_source"], guardianPhone="010-1234-5678", unknown_extra="drop-me")

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert "guardianPhone" not in body["metadata"]
    assert "unknown_extra" not in body["metadata"]
    assert body["metadata"]["review_flags"] == ["fake_source"]
    assert "fake_source" in body["review_flags"]


def test_create_report_v2_maps_non_fake_source_to_server(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(
        source_model="YOLO26s custom",
        class_name="damaged_tactile_block",
        model_class_id=1,
        captured_at="2026-05-22T12:20:00.000Z",
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["class_id"] == 1
    assert body["class_name"] == "damaged_tactile_block"
    assert body["source"] == "server"


@pytest.mark.parametrize(
    "class_name,model_class_id",
    [
        ("damaged_tactile_block", 1),
    ],
)
def test_create_report_v2_stores_voice_damage_without_auto_report(
    client: ASGITestClient,
    class_name: str,
    model_class_id: int,
) -> None:
    metadata = sample_v2_metadata(
        class_name=class_name,
        model_class_id=model_class_id,
        captured_at="2026-05-22T12:40:00.000Z",
        trigger="voice",
        auto_reported=False,
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["class_id"] == model_class_id
    assert body["class_name"] == class_name
    assert body["metadata"]["trigger"] == "voice"
    assert body["metadata"]["auto_reported"] is False
    for key, value in metadata.items():
        assert body["metadata"][key] == value


@pytest.mark.parametrize(
    "metadata",
    [
        sample_v2_metadata(class_name="tactile_damage_area", model_class_id=2),
        sample_v2_metadata(class_name="normal_tactile_block", model_class_id=0),
        sample_v2_metadata(model_key="coco_general", class_name="person", model_class_id=0, category="general_obstacle"),
        sample_v2_metadata(model_key="coco_general", class_name="car", model_class_id=1, category="general_obstacle"),
    ],
)
def test_create_report_v2_rejects_non_damage_targets(client: ASGITestClient, metadata: dict[str, object]) -> None:
    response = post_report_v2(client, metadata)

    assert response.status_code == 422


def test_create_report_v2_rejects_voice_non_damage_target(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(
        class_name="normal_tactile_block",
        model_class_id=0,
        trigger="voice",
        auto_reported=False,
    )

    response = post_report_v2(client, metadata)

    assert response.status_code == 422


def test_create_report_v2_rejects_mismatched_damage_class_id(client: ASGITestClient) -> None:
    response = post_report_v2(client, sample_v2_metadata(model_class_id=2))

    assert response.status_code == 422
    assert "model_class_id=1" in response.text


def test_create_report_v2_rejects_missing_gps(client: ASGITestClient) -> None:
    metadata = sample_v2_metadata(gps=None)

    response = post_report_v2(client, metadata)

    assert response.status_code == 422


def test_create_report_v2_reuses_duplicate_policy(client: ASGITestClient) -> None:
    first = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T13:00:00.000Z",
            gps={"latitude": 37.5, "longitude": 127.0, "accuracy_m": 10.0},
        ),
    )
    assert first.status_code == 201, first.text

    second = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T13:04:00.000Z",
            gps={"latitude": 37.50003, "longitude": 127.00003, "accuracy_m": 10.0},
        ),
    )

    assert second.status_code == 201, second.text
    assert first.json()["id"] in second.json()["duplicate_report_ids"]


def test_list_reports_filters_v2_metadata(client: ASGITestClient) -> None:
    auto = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T14:00:00.000Z"),
    )
    assert auto.status_code == 201, auto.text
    auto_id = auto.json()["id"]

    manual = post_report_v2(
        client,
        sample_v2_metadata(
            class_name="damaged_tactile_block",
            model_class_id=1,
            captured_at="2026-05-22T14:10:00.000Z",
            trigger="voice",
            auto_reported=False,
        ),
    )
    assert manual.status_code == 201, manual.text
    manual_id = manual.json()["id"]

    by_class = client.get("/reports", params={"class_name": "damaged_tactile_block", "limit": 100})
    assert by_class.status_code == 200, by_class.text
    assert auto_id in ids(by_class.json())

    by_model = client.get("/reports", params={"model_key": "custom_tactile", "limit": 100})
    assert by_model.status_code == 200, by_model.text
    assert {auto_id, manual_id} <= ids(by_model.json())

    by_trigger = client.get("/reports", params={"trigger": "voice", "limit": 100})
    assert by_trigger.status_code == 200, by_trigger.text
    assert manual_id in ids(by_trigger.json())
    assert auto_id not in ids(by_trigger.json())

    by_manual = client.get("/reports", params={"auto_reported": "false", "limit": 100})
    assert by_manual.status_code == 200, by_manual.text
    assert manual_id in ids(by_manual.json())
    assert auto_id not in ids(by_manual.json())

    no_match = client.get(
        "/reports",
        params={"class_name": "not_a_class", "model_key": "unknown_model", "trigger": "unknown", "limit": 100},
    )
    assert no_match.status_code == 200, no_match.text
    assert no_match.json() == []


def test_list_reports_filters_demo_mode(client: ASGITestClient) -> None:
    created_from = fresh_created_from()
    source_fake = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T14:20:00.000Z", source_model="fake/demo-list"),
    )
    assert source_fake.status_code == 201, source_fake.text
    source_fake_id = source_fake.json()["id"]

    metadata_fake = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T14:21:00.000Z",
            source_model="YOLO26s custom",
            fake_source=True,
        ),
    )
    assert metadata_fake.status_code == 201, metadata_fake.text
    metadata_fake_id = metadata_fake.json()["id"]

    real = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T14:22:00.000Z", source_model="YOLO26s custom"),
    )
    assert real.status_code == 201, real.text
    real_id = real.json()["id"]

    explicit_non_fake = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T14:23:00.000Z",
            source_model="YOLO26s custom",
            fake_source=False,
        ),
    )
    assert explicit_non_fake.status_code == 201, explicit_non_fake.text
    explicit_non_fake_id = explicit_non_fake.json()["id"]

    exclude_fake = client.get(
        "/reports",
        params={"created_from": created_from, "demo_filter": "exclude_fake", "limit": 100},
    )
    assert exclude_fake.status_code == 200, exclude_fake.text
    exclude_fake_ids = ids(exclude_fake.json())
    assert real_id in exclude_fake_ids
    assert explicit_non_fake_id in exclude_fake_ids
    assert source_fake_id not in exclude_fake_ids
    assert metadata_fake_id not in exclude_fake_ids

    only_fake = client.get(
        "/reports",
        params={"created_from": created_from, "demo_filter": "only_fake", "limit": 100},
    )
    assert only_fake.status_code == 200, only_fake.text
    only_fake_ids = ids(only_fake.json())
    assert {source_fake_id, metadata_fake_id} <= only_fake_ids
    assert real_id not in only_fake_ids
    assert explicit_non_fake_id not in only_fake_ids


def test_export_reports_csv_filters_v2_metadata(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:00:00.000Z", distance_m=1.4),
    )
    assert created.status_code == 201, created.text
    created_body = created.json()
    missing_distance = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:00:30.000Z"),
    )
    assert missing_distance.status_code == 201, missing_distance.text
    missing_distance_body = missing_distance.json()

    response = client.get("/reports/export", params={"model_key": "custom_tactile"})

    assert response.status_code == 200, response.text
    assert response.headers["content-type"].startswith("text/csv")
    rows = csv_rows(response)
    row = next(row for row in rows if row["id"] == created_body["id"])
    missing_distance_row = next(row for row in rows if row["id"] == missing_distance_body["id"])
    assert row["model_key"] == "custom_tactile"
    assert row["source_model"] == "fake/custom-tactile-contract"
    assert row["trigger"] == "auto"
    assert row["auto_reported"] == "true"
    assert row["distance_m"] == "1.4"
    assert len(row["payload_sha256"]) == 64
    assert len(row["image_sha256"]) == 64
    assert row["data_origin"] == "demo"
    assert row["runtime_mode"] == "fake"
    assert row["performance_excluded"] == "true"
    assert missing_distance_row["distance_m"] == ""
    assert row["image_path"] == created_body["image_path"]


def test_export_reports_json_filters_v2_metadata(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:05:00.000Z", distance_m=2.5),
    )
    assert created.status_code == 201, created.text
    created_body = created.json()

    response = client.get("/reports/export", params={"format": "json", "model_key": "custom_tactile"})

    assert response.status_code == 200, response.text
    assert response.headers["content-disposition"] == 'attachment; filename="walksafe-reports-demo-included.json"'
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-walksafe-demo-filter"] == "all"
    row = next(row for row in response.json() if row["id"] == created_body["id"])
    assert row["latitude"] == 37.5665
    assert row["longitude"] == 126.978
    assert row["model_key"] == "custom_tactile"
    assert row["distance_m"] == 2.5
    assert len(row["payload_sha256"]) == 64
    assert len(row["image_sha256"]) == 64
    assert row["performance_excluded"] == "true"
    assert row["location_quality"] == "high"
    assert isinstance(row["review_flags"], str)


def test_export_reports_geojson_filters_v2_metadata(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:06:00.000Z", distance_m=3.75),
    )
    assert created.status_code == 201, created.text
    created_body = created.json()

    response = client.get("/reports/export", params={"format": "geojson", "model_key": "custom_tactile"})

    assert response.status_code == 200, response.text
    assert response.headers["content-type"].startswith("application/geo+json")
    assert response.headers["cache-control"] == "no-store"
    body = response.json()
    assert body["type"] == "FeatureCollection"
    feature = next(feature for feature in body["features"] if feature["id"] == created_body["id"])
    assert feature["type"] == "Feature"
    assert feature["geometry"] == {"type": "Point", "coordinates": [126.978, 37.5665]}
    assert feature["properties"]["model_key"] == "custom_tactile"
    assert feature["properties"]["source_model"] == "fake/custom-tactile-contract"
    assert feature["properties"]["trigger"] == "auto"
    assert feature["properties"]["distance_m"] == 3.75
    assert len(feature["properties"]["payload_sha256"]) == 64
    assert feature["properties"]["data_origin"] == "demo"
    assert "latitude" not in feature["properties"]
    assert "longitude" not in feature["properties"]


def test_export_reports_filters_demo_mode(client: ASGITestClient) -> None:
    created_from = fresh_created_from()
    source_fake = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:07:00.000Z", source_model="fake/demo-export"),
    )
    assert source_fake.status_code == 201, source_fake.text
    source_fake_id = source_fake.json()["id"]

    metadata_fake = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:08:00.000Z",
            source_model="YOLO26s custom",
            review_flags=["fake_source"],
        ),
    )
    assert metadata_fake.status_code == 201, metadata_fake.text
    metadata_fake_id = metadata_fake.json()["id"]

    real = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:09:00.000Z", source_model="YOLO26s custom"),
    )
    assert real.status_code == 201, real.text
    real_id = real.json()["id"]

    exclude_fake = client.get(
        "/reports/export",
        params={"format": "json", "created_from": created_from, "demo_filter": "exclude_fake"},
    )
    assert exclude_fake.status_code == 200, exclude_fake.text
    exclude_fake_ids = {row["id"] for row in exclude_fake.json()}
    assert real_id in exclude_fake_ids
    assert source_fake_id not in exclude_fake_ids
    assert metadata_fake_id not in exclude_fake_ids

    only_fake = client.get(
        "/reports/export",
        params={"format": "json", "created_from": created_from, "demo_filter": "only_fake"},
    )
    assert only_fake.status_code == 200, only_fake.text
    only_fake_ids = {row["id"] for row in only_fake.json()}
    assert {source_fake_id, metadata_fake_id} <= only_fake_ids
    assert real_id not in only_fake_ids


def test_export_reports_csv_no_match_writes_header_only(client: ASGITestClient) -> None:
    response = client.get("/reports/export", params={"model_key": "no_such_export_model"})

    assert response.status_code == 200, response.text
    header = response.text.splitlines()[0].split(",")
    for expected in [
        "id",
        "status",
        "class_name",
        "model_key",
        "payload_sha256",
        "image_sha256",
        "data_origin",
        "performance_excluded",
        "status_history_count",
        "image_path",
    ]:
        assert expected in header
    assert csv_rows(response) == []


def test_export_reports_csv_bom_and_redacted_mode(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:11:00.000Z"),
    )
    assert created.status_code == 201, created.text

    response = client.get("/reports/export", params={"format": "csv", "bom": "true", "redacted": "true"})

    assert response.status_code == 200, response.text
    assert response.text.startswith("\ufeff")
    row = next(row for row in csv_rows(response) if row["id"] == created.json()["id"])
    assert row["image_path"] == ""
    assert len(row["latitude"].split(".")[-1]) <= 4


def test_export_reports_route_does_not_conflict_with_report_detail(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:10:00.000Z"),
    )
    assert created.status_code == 201, created.text
    report_id = created.json()["id"]

    export_response = client.get("/reports/export", params={"model_key": "no_such_export_model"})
    detail_response = client.get(f"/reports/{report_id}")

    assert export_response.status_code == 200, export_response.text
    assert detail_response.status_code == 200, detail_response.text
    assert detail_response.json()["id"] == report_id


def test_export_reports_manifest_and_demo_filename(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:12:00.000Z", source_model="YOLO26s custom"),
    )
    assert created.status_code == 201, created.text

    response = client.get(
        "/reports/export",
        params={"format": "json", "manifest": "true", "demo_filter": "exclude_fake", "model_key": "custom_tactile"},
    )

    assert response.status_code == 200, response.text
    assert response.headers["content-disposition"] == 'attachment; filename="walksafe-reports-demo-excluded.json"'
    body = response.json()
    assert body["schema_version"] == "walksafe.reports.export_manifest.v1"
    assert body["filters"]["demo_filter"] == "exclude_fake"
    assert len(body["rows_sha256"]) == 64
    assert any(row["id"] == created.json()["id"] for row in body["rows"])


def test_export_reports_geojson_grid_aggregate(client: ASGITestClient) -> None:
    first = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:13:00.000Z",
            gps={"latitude": 37.56651, "longitude": 126.97801, "accuracy_m": 8.0},
        ),
    )
    second = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:13:30.000Z",
            gps={"latitude": 37.56654, "longitude": 126.97804, "accuracy_m": 8.0},
        ),
    )
    assert first.status_code == 201, first.text
    assert second.status_code == 201, second.text

    response = client.get("/reports/export", params={"format": "geojson", "aggregate": "grid", "model_key": "custom_tactile"})

    assert response.status_code == 200, response.text
    assert response.headers["content-disposition"] == 'attachment; filename="walksafe-reports-demo-included-grid.geojson"'
    body = response.json()
    assert body["type"] == "FeatureCollection"
    assert body["properties"]["aggregate"] == "grid"
    assert any(feature["properties"]["count"] >= 2 for feature in body["features"])


def test_reports_summary_uses_full_filtered_query_and_cluster_breakdown(client: ASGITestClient) -> None:
    created_from = fresh_created_from()
    created = post_report_v2(
        client,
        sample_v2_metadata(
            captured_at="2026-05-22T15:14:00.000Z",
            source_model="YOLO26s custom",
            gps={"latitude": 37.56651, "longitude": 126.97801, "accuracy_m": 8.0},
        ),
    )
    assert created.status_code == 201, created.text

    response = client.get(
        "/reports/summary",
        params={
            "created_from": created_from,
            "demo_filter": "exclude_fake",
            "grid_size_degrees": "0.001",
            "top_limit": "3",
        },
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["total"] >= 1
    assert body["fake"] == 0
    assert body["non_fake"] >= 1
    assert body["located"] >= 1
    assert body["bounds"]["min_latitude"] <= 37.56651 <= body["bounds"]["max_latitude"]
    cluster = body["top_clusters"][0]
    assert "status_counts" in cluster
    assert "source_counts" in cluster
    assert "bounds" in cluster


def test_export_reports_csv_escapes_formula_like_values(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:15:00.000Z", source_model="=IMPORTXML(\"https://example.test\")"),
    )
    assert created.status_code == 201, created.text

    response = client.get("/reports/export", params={"format": "csv", "model_key": "custom_tactile"})

    assert response.status_code == 200, response.text
    row = next(row for row in csv_rows(response) if row["id"] == created.json()["id"])
    assert row["source_model"].startswith("'=")


def test_report_status_history_note_reason_and_conflict(client: ASGITestClient) -> None:
    created = post_report_v2(
        client,
        sample_v2_metadata(captured_at="2026-05-22T15:16:00.000Z", source_model="YOLO26s custom"),
    )
    assert created.status_code == 201, created.text
    report_id = created.json()["id"]
    expected_updated_at = created.json()["updated_at"]

    reviewed = client.patch(
        f"/reports/{report_id}/status",
        json={"status": "reviewed", "note": "현장 사진 확인", "expected_updated_at": expected_updated_at},
    )
    assert reviewed.status_code == 200, reviewed.text
    reviewed_body = reviewed.json()
    assert reviewed_body["status"] == "reviewed"
    assert reviewed_body["metadata"]["review_note"] == "현장 사진 확인"
    assert reviewed_body["metadata"]["status_history"][-1]["from"] == "new"
    assert reviewed_body["metadata"]["status_history"][-1]["to"] == "reviewed"

    stale = client.patch(
        f"/reports/{report_id}/status",
        json={"status": "resolved", "expected_updated_at": expected_updated_at},
    )
    assert stale.status_code == 409, stale.text

    resolved = client.patch(
        f"/reports/{report_id}/status",
        json={
            "status": "resolved",
            "resolution_reason": "접수 후보로 정리",
            "expected_updated_at": reviewed_body["updated_at"],
        },
    )
    assert resolved.status_code == 200, resolved.text
    assert resolved.json()["metadata"]["resolution_reason"] == "접수 후보로 정리"
    assert len(resolved.json()["metadata"]["status_history"]) >= 2
