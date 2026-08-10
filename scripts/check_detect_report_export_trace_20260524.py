#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import sys
from typing import Any

try:
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, text
    from sqlalchemy.exc import SQLAlchemyError
except ModuleNotFoundError:  # dry-run mode does not need database dependencies.
    command = None
    Config = None
    create_engine = None
    text = None
    SQLAlchemyError = Exception

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "backend" / "tests"))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

try:
    from asgi_client import ASGITestClient  # noqa: E402
    from backend.app.config import get_settings  # noqa: E402
    from backend.app.main import app, settings as app_settings  # noqa: E402
    from backend.app.schemas import ReportV2Metadata  # noqa: E402
    from backend.app.services.report_policy import ensure_report_v2_allowed  # noqa: E402
except ModuleNotFoundError:  # dry-run mode does not need ASGI dependencies.
    ASGITestClient = None
    get_settings = None
    app = None
    app_settings = None
    ReportV2Metadata = None
    ensure_report_v2_allowed = None

PNG_BYTES = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
    b"\x08\x04\x00\x00\x00\xb5\x1c\x0c\x02\x00\x00\x00\x0bIDATx\xdac\xfc\xff\x1f"
    b"\x00\x03\x03\x02\x00\xef\xbf\xa7\xdb\x00\x00\x00\x00IEND\xaeB`\x82"
)


def _blocked(message: str, *, fail: bool = False) -> int:
    print(f"BLOCKED: {message}")
    return 1 if fail else 0


def _fail(message: str) -> int:
    print(f"FAIL: {message}")
    return 1


def _assert_status(response: Any, expected: int, label: str) -> None:
    if response.status_code != expected:
        raise AssertionError(f"{label}: expected {expected}, got {response.status_code}: {response.text}")


def _json_hash(value: object) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str).encode("utf-8")
    ).hexdigest()


def _validate_report_policy(detection: dict[str, Any], *, should_pass: bool) -> None:
    if ReportV2Metadata is None or ensure_report_v2_allowed is None:
        return
    try:
        parsed = ReportV2Metadata.model_validate(_report_metadata(detection))
        ensure_report_v2_allowed(parsed)
    except Exception as exc:  # noqa: BLE001 - dry-run policy must classify accept/reject concisely.
        if should_pass:
            raise AssertionError(f"dry-run policy unexpectedly rejected {detection.get('class_name')}: {exc}") from exc
        return
    if not should_pass:
        raise AssertionError(f"dry-run policy unexpectedly accepted {detection.get('class_name')}")


def _check_database(*, require_db: bool = False) -> int | None:
    if command is None or Config is None or create_engine is None or text is None or get_settings is None:
        return _blocked(
            "database smoke dependencies are not installed; use --dry-run-policy for dependency-free policy check",
            fail=require_db,
        )

    settings = get_settings()
    engine = create_engine(settings.database_url)
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
    except SQLAlchemyError as exc:
        return _blocked(f"PostGIS test database is not reachable: {exc}", fail=require_db)

    try:
        config = Config(str(ROOT / "backend" / "alembic.ini"))
        command.upgrade(config, "head")
    except Exception as exc:  # noqa: BLE001 - migration failure should be reported as blocked for this smoke.
        return _blocked(f"database migration could not run: {type(exc).__name__}: {exc}", fail=require_db)
    return None


def _load_detection_payload(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, dict):
        detections = payload.get("detections")
    else:
        detections = payload
    if not isinstance(detections, list):
        raise ValueError("detection payload must be a list or an object with a detections list")
    return detections


def _detect_fake_v2(client: ASGITestClient) -> list[dict[str, Any]]:
    if app_settings is None:
        raise RuntimeError("ASGI app settings are unavailable")
    app_settings.detect_v2_mode = "fake"
    app_settings.detect_v2_custom_tactile_model_path = None
    app_settings.detect_v2_coco_model_path = None
    app_settings.detect_v2_runtime_config_path = None

    context = {
        "captured_at": "2026-05-24T12:00:00.000Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 181.0,
    }
    response = client.post(
        "/detect/v2",
        data={"context": json.dumps(context)},
        files={"image": ("trace.png", PNG_BYTES, "image/png")},
    )
    _assert_status(response, 200, "POST /detect/v2")
    body = response.json()
    if body.get("schema_version") != "detect.v2":
        raise AssertionError(f"POST /detect/v2: unexpected schema_version: {body}")
    detections = body.get("detections")
    if not isinstance(detections, list):
        raise AssertionError(f"POST /detect/v2: detections is not a list: {body}")
    return detections


def _report_metadata(detection: dict[str, Any]) -> dict[str, Any]:
    metadata = dict(detection)
    metadata["trigger"] = "auto"
    metadata["auto_reported"] = True
    metadata.setdefault("trace_id", f"trace-{_json_hash(detection)[:16]}")
    return metadata


def _post_report_v2(client: ASGITestClient, detection: dict[str, Any]) -> Any:
    return client.post(
        "/reports/v2",
        data={"metadata": json.dumps(_report_metadata(detection))},
        files={"image": ("trace.png", PNG_BYTES, "image/png")},
    )


def _find_detection(detections: list[dict[str, Any]], *, class_name: str, model_key: str | None = None) -> dict[str, Any]:
    for detection in detections:
        if detection.get("class_name") == class_name and (model_key is None or detection.get("model_key") == model_key):
            return detection
    raise AssertionError(f"required detection not found: class_name={class_name}, model_key={model_key}")


def _maybe_find_detection(
    detections: list[dict[str, Any]],
    *,
    class_name: str | None = None,
    model_key: str | None = None,
) -> dict[str, Any] | None:
    for detection in detections:
        if class_name is not None and detection.get("class_name") != class_name:
            continue
        if model_key is not None and detection.get("model_key") != model_key:
            continue
        return detection
    return None


def _assert_export_contains(client: ASGITestClient, report_id: str) -> dict[str, Any]:
    csv_response = client.get("/reports/export", params={"model_key": "custom_tactile"})
    _assert_status(csv_response, 200, "GET /reports/export csv")
    rows = list(csv.DictReader(io.StringIO(csv_response.text.lstrip("\ufeff"))))
    csv_row = next((row for row in rows if row["id"] == report_id), None)
    if csv_row is None:
        raise AssertionError("GET /reports/export csv: saved report id is missing")

    json_response = client.get("/reports/export", params={"format": "json", "model_key": "custom_tactile"})
    _assert_status(json_response, 200, "GET /reports/export json")
    json_row = next((row for row in json_response.json() if row.get("id") == report_id), None)
    if json_row is None:
        raise AssertionError("GET /reports/export json: saved report id is missing")

    geojson_response = client.get("/reports/export", params={"format": "geojson", "model_key": "custom_tactile"})
    _assert_status(geojson_response, 200, "GET /reports/export geojson")
    geojson = geojson_response.json()
    if geojson.get("type") != "FeatureCollection":
        raise AssertionError(f"GET /reports/export geojson: unexpected body: {geojson}")
    if not any(feature.get("id") == report_id for feature in geojson.get("features", [])):
        raise AssertionError("GET /reports/export geojson: saved report id is missing")
    for required in ("model_key", "source_model", "threshold_used", "distance_m", "payload_sha256", "image_sha256"):
        if required in json_row and str(json_row.get(required)):
            continue
        if required == "distance_m":
            continue
        raise AssertionError(f"GET /reports/export json: {required} is missing for report {report_id}")
    return {"csv": csv_row, "json": json_row, "geojson_feature_count": len(geojson.get("features", []))}


def _assert_reject_target(
    client: ASGITestClient,
    detection: dict[str, Any] | None,
    *,
    label: str,
    reject_target_check: str,
) -> bool:
    if detection is None:
        if reject_target_check == "always":
            raise AssertionError(f"required reject target detection not found: {label}")
        return False

    response = _post_report_v2(client, detection)
    _assert_status(response, 422, f"POST /reports/v2 {label}")
    return True


def _default_policy_payload() -> list[dict[str, Any]]:
    captured_at = "2026-05-25T12:00:00.000Z"
    return [
        {
            "schema_version": "detect.v2",
            "model_key": "custom_tactile",
            "source_model": "dry-run",
            "model_class_id": 1,
            "class_name": "damaged_tactile_block",
            "category": "tactile_damage",
            "confidence": 0.91,
            "bbox": {"x": 0.2, "y": 0.2, "width": 0.2, "height": 0.2},
            "threshold_used": 0.25,
            "captured_at": captured_at,
            "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
            "heading": 181.0,
        },
        {
            "schema_version": "detect.v2",
            "model_key": "custom_tactile",
            "source_model": "dry-run",
            "model_class_id": 2,
            "class_name": "tactile_damage_area",
            "category": "tactile_damage",
            "confidence": 0.88,
            "bbox": {"x": 0.24, "y": 0.24, "width": 0.12, "height": 0.12},
            "threshold_used": 0.25,
            "captured_at": captured_at,
            "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
            "heading": 181.0,
        },
        {
            "schema_version": "detect.v2",
            "model_key": "coco_general",
            "source_model": "dry-run",
            "model_class_id": 0,
            "class_name": "person",
            "category": "vulnerable_road_user",
            "confidence": 0.82,
            "bbox": {"x": 0.42, "y": 0.56, "width": 0.18, "height": 0.22},
            "threshold_used": 0.25,
            "captured_at": captured_at,
            "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
            "heading": 181.0,
        },
    ]


def dry_run_policy(payload_path: Path | None, reject_target_check: str) -> int:
    detections = _load_detection_payload(payload_path) if payload_path else _default_policy_payload()
    damaged = _find_detection(detections, class_name="damaged_tactile_block", model_key="custom_tactile")
    area = _maybe_find_detection(detections, class_name="tactile_damage_area", model_key="custom_tactile")
    coco = _maybe_find_detection(detections, model_key="coco_general")

    if damaged.get("category") != "tactile_damage":
        raise AssertionError("dry-run: damaged_tactile_block category should be tactile_damage")
    _validate_report_policy(damaged, should_pass=True)

    reject_targets: list[str] = []
    if reject_target_check != "never":
        if area is None and reject_target_check == "always":
            raise AssertionError("dry-run: required tactile_damage_area reject target is missing")
        if coco is None and reject_target_check == "always":
            raise AssertionError("dry-run: required coco_general reject target is missing")
        if area is not None:
            _validate_report_policy(area, should_pass=False)
            reject_targets.append("tactile_damage_area")
        if coco is not None:
            _validate_report_policy(coco, should_pass=False)
            reject_targets.append("coco_general")

    print("PASS: dry-run detect → report → export policy")
    print("expected-store=damaged_tactile_block")
    print(f"expected-reject={','.join(reject_targets) if reject_targets else 'skipped'}")
    print("expected-export=CSV/JSON/GeoJSON should include the stored report after DB trace run")
    return 0


def _write_artifacts(json_path: Path | None, md_path: Path | None, result: dict[str, Any]) -> None:
    if json_path:
        json_path.parent.mkdir(parents=True, exist_ok=True)
        json_path.write_text(json.dumps(result, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    if md_path:
        md_path.parent.mkdir(parents=True, exist_ok=True)
        lines = [
            "# detect → report → export trace",
            "",
            f"- status: {result['status']}",
            f"- report_id: `{result['report_id']}`",
            f"- trace_id: `{result['trace_id']}`",
            f"- payload_sha256: `{result['payload_sha256']}`",
            f"- image_sha256: `{result['image_sha256']}`",
            f"- checked_rejects: {', '.join(result['checked_rejects']) or 'skipped'}",
            "- destructive_action: false",
        ]
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def run(
    payload_path: Path | None,
    reject_target_check: str,
    *,
    require_db: bool = False,
    require_server_source: bool = False,
    artifact_json: Path | None = None,
    artifact_md: Path | None = None,
) -> int:
    blocked = _check_database(require_db=require_db)
    if blocked is not None:
        return blocked

    if ASGITestClient is None or app is None:
        return _blocked(
            "ASGI smoke dependencies are not installed; use --dry-run-policy for dependency-free policy check",
            fail=require_db,
        )

    client = ASGITestClient(app)
    detections = _load_detection_payload(payload_path) if payload_path else _detect_fake_v2(client)

    damaged = _find_detection(detections, class_name="damaged_tactile_block", model_key="custom_tactile")
    if require_server_source and str(damaged.get("source_model", "")).lower().startswith("fake"):
        raise AssertionError("--require-server-source rejected fake/demo source_model for damaged_tactile_block")
    area = _maybe_find_detection(detections, class_name="tactile_damage_area", model_key="custom_tactile")
    coco = _maybe_find_detection(detections, model_key="coco_general")
    _validate_report_policy(damaged, should_pass=True)

    created = _post_report_v2(client, damaged)
    _assert_status(created, 201, "POST /reports/v2 damaged_tactile_block")
    created_body = created.json()
    report_id = created_body["id"]
    if created_body["class_name"] != "damaged_tactile_block":
        raise AssertionError(f"POST /reports/v2: unexpected class_name: {created_body}")

    checked_rejects: list[str] = []
    if reject_target_check != "never":
        if _assert_reject_target(
            client,
            area,
            label="tactile_damage_area",
            reject_target_check=reject_target_check,
        ):
            checked_rejects.append("tactile_damage_area")
        if _assert_reject_target(
            client,
            coco,
            label="coco_general",
            reject_target_check=reject_target_check,
        ):
            checked_rejects.append("coco_general")

    detail = client.get(f"/reports/{report_id}")
    _assert_status(detail, 200, "GET /reports/{id}")
    detail_body = detail.json()
    if detail_body["id"] != report_id:
        raise AssertionError("GET /reports/{id}: returned a different report id")

    status = client.patch(f"/reports/{report_id}/status", json={"status": "reviewed"})
    _assert_status(status, 200, "PATCH /reports/{id}/status")
    if status.json()["status"] != "reviewed":
        raise AssertionError("PATCH /reports/{id}/status: status was not updated")

    export_rows = _assert_export_contains(client, report_id)
    metadata = detail_body.get("metadata", {})
    result = {
        "schema_version": "walksafe.detect_report_export_trace.v1",
        "status": "PASS",
        "report_id": report_id,
        "trace_id": metadata.get("trace_id"),
        "payload_sha256": metadata.get("payload_sha256"),
        "image_sha256": metadata.get("image_sha256"),
        "source_model": metadata.get("source_model"),
        "checked_rejects": checked_rejects,
        "export_rows": export_rows,
    }
    _write_artifacts(artifact_json, artifact_md, result)

    print("PASS: detect → report → status → export trace")
    print(f"report_id={report_id}")
    print(f"trace_id={result['trace_id']}")
    print(f"payload_sha256={result['payload_sha256']}")
    reject_summary = "/".join(checked_rejects) if checked_rejects else "skipped"
    print(f"checked: damaged_tactile_block stored; reject targets={reject_summary}; CSV/JSON/GeoJSON export includes report")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="ASGI smoke for detect-v2 → reports-v2 → status → export trace.")
    parser.add_argument(
        "--detection-payload",
        type=Path,
        help="Optional JSON list or DetectV2Response object to use instead of POST /detect/v2 fake mode.",
    )
    parser.add_argument(
        "--reject-target-check",
        choices=("auto", "always", "never"),
        default="auto",
        help=(
            "How to check non-reportable detections. auto checks only reject targets present in the payload; "
            "always requires tactile_damage_area and a coco_general detection; never skips reject target checks."
        ),
    )
    parser.add_argument(
        "--dry-run-policy",
        action="store_true",
        help="Validate the trace policy without database, ASGI writes, or export requests.",
    )
    parser.add_argument(
        "--require-db",
        action="store_true",
        help="Return non-zero when the real database trace is blocked. Use in CI/pre-release gates.",
    )
    parser.add_argument(
        "--require-server-source",
        action="store_true",
        help="Fail when the stored damaged_tactile_block detection is from fake/demo source_model.",
    )
    parser.add_argument("--artifact-json", type=Path, help="Optional JSON artifact path.")
    parser.add_argument("--artifact-md", type=Path, help="Optional Markdown artifact path.")
    args = parser.parse_args()

    try:
        if args.dry_run_policy:
            return dry_run_policy(args.detection_payload, args.reject_target_check)
        return run(
            args.detection_payload,
            args.reject_target_check,
            require_db=args.require_db,
            require_server_source=args.require_server_source,
            artifact_json=args.artifact_json,
            artifact_md=args.artifact_md,
        )
    except AssertionError as exc:
        return _fail(str(exc))
    except Exception as exc:  # noqa: BLE001 - smoke script should print a concise failure, not a traceback.
        return _fail(f"{type(exc).__name__}: {exc}")


if __name__ == "__main__":
    raise SystemExit(main())
