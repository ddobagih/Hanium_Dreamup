#!/usr/bin/env python3
"""Record, but never fabricate, an external institution submission receipt."""

from __future__ import annotations

import argparse
import csv
from datetime import UTC, datetime
import hashlib
import json
import math
from pathlib import Path
import re
import uuid


ACTOR_ID = re.compile(r"^[A-Za-z0-9._@-]{1,64}$")
PLACEHOLDERS = {"placeholder", "tbd", "todo", "unknown", "sample", "미정", "예시", "기관명"}
HIGH_LOCATION_ACCURACY_M = 15.0
AGENCY_ALLOWED_ROW_FIELDS = {
    "id",
    "status",
    "class_name",
    "confidence",
    "latitude",
    "longitude",
    "accuracy_m",
    "captured_at",
    "created_at",
    "location_quality",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validated_text(value: str, field: str, *, max_length: int = 160) -> str:
    normalized = value.strip()
    if not normalized or len(normalized) > max_length or "\n" in normalized or normalized.casefold() in PLACEHOLDERS:
        raise ValueError(f"{field} must contain a real, non-placeholder value")
    return normalized


def canonical_rows_sha256(rows: list[dict[str, object]]) -> str:
    rendered = json.dumps(rows, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(rendered.encode("utf-8")).hexdigest()


def finite_number(value: object, *, minimum: float, maximum: float) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
        and minimum <= float(value) <= maximum
    )


def timezone_timestamp(value: object) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None and parsed.utcoffset() is not None


def canonical_uuid(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value.lower()
    except ValueError:
        return False


def valid_agency_row(row: dict[str, object]) -> bool:
    return (
        canonical_uuid(row.get("id"))
        and row.get("status") == "reviewed"
        and row.get("class_name") == "damaged_tactile_block"
        and finite_number(row.get("confidence"), minimum=0, maximum=1)
        and row.get("location_quality") == "high"
        and finite_number(row.get("latitude"), minimum=-90, maximum=90)
        and finite_number(row.get("longitude"), minimum=-180, maximum=180)
        and finite_number(row.get("accuracy_m"), minimum=0, maximum=HIGH_LOCATION_ACCURACY_M)
        and timezone_timestamp(row.get("captured_at"))
        and timezone_timestamp(row.get("created_at"))
        and all(value in ("", None) for key, value in row.items() if key not in AGENCY_ALLOWED_ROW_FIELDS)
    )


def validate_export_matches_manifest(export_path: Path, manifest: dict[str, object]) -> tuple[str, str]:
    rows = manifest.get("rows")
    if not isinstance(rows, list) or not rows or not all(isinstance(row, dict) for row in rows):
        raise ValueError("agency export manifest must include its non-empty row list")
    typed_rows: list[dict[str, object]] = [dict(row) for row in rows]
    rows_sha256 = canonical_rows_sha256(typed_rows)
    if manifest.get("rows_sha256") != rows_sha256 or manifest.get("count") != len(typed_rows):
        raise ValueError("agency export manifest row count or digest is invalid")

    suffix = export_path.suffix.lower()
    if suffix == ".json":
        export_payload = json.loads(export_path.read_text(encoding="utf-8"))
        matches = export_payload == typed_rows
        export_format = "json"
    elif suffix == ".csv":
        with export_path.open("r", encoding="utf-8-sig", newline="") as source:
            reader = csv.DictReader(source)
            csv_rows = list(reader)
            expected_fields = list(typed_rows[0])
            matches = reader.fieldnames == expected_fields and len(csv_rows) == len(typed_rows)
            if matches:
                expected_csv_rows = [
                    {field: "" if row.get(field) is None else str(row.get(field)) for field in expected_fields}
                    for row in typed_rows
                ]
                matches = csv_rows == expected_csv_rows
        export_format = "csv"
    elif suffix == ".geojson":
        export_payload = json.loads(export_path.read_text(encoding="utf-8"))
        expected_features = []
        for row in typed_rows:
            latitude = row.get("latitude")
            longitude = row.get("longitude")
            geometry = None if latitude is None or longitude is None else {
                "type": "Point",
                "coordinates": [longitude, latitude],
            }
            expected_features.append(
                {
                    "type": "Feature",
                    "id": row.get("id"),
                    "geometry": geometry,
                    "properties": {key: value for key, value in row.items() if key not in {"latitude", "longitude"}},
                }
            )
        properties = export_payload.get("properties") if isinstance(export_payload, dict) else None
        matches = (
            isinstance(export_payload, dict)
            and export_payload.get("type") == "FeatureCollection"
            and export_payload.get("features") == expected_features
            and isinstance(properties, dict)
            and properties.get("actor_id") == manifest.get("actor_id")
            and properties.get("location_precision") == "exact-report"
            and properties.get("audit_id") == manifest.get("audit_id")
        )
        export_format = "geojson"
    else:
        raise ValueError("agency export must be .csv, .json, or .geojson")
    if not matches:
        raise ValueError("agency export content does not match the manifest rows")
    return export_format, rows_sha256


def validate_agency_manifest_contract(
    manifest: dict[str, object],
    *,
    actor_id: str,
) -> list[dict[str, object]]:
    if manifest.get("schema_version") != "walksafe.reports.export_manifest.v1":
        raise ValueError("export manifest schema is invalid")
    if not canonical_uuid(manifest.get("audit_id")) or not timezone_timestamp(manifest.get("generated_at")):
        raise ValueError("agency export manifest audit id or generated timestamp is invalid")
    filters = manifest.get("filters")
    if not isinstance(filters, dict) or filters.get("profile") != "agency":
        raise ValueError("export manifest must use profile=agency")
    if filters.get("demo_filter") != "exclude_fake":
        raise ValueError("agency export manifest must exclude demo reports")
    if "performance_excluded" not in filters or filters.get("performance_excluded") is not None:
        raise ValueError("agency export must not filter the separate model-performance exclusion decision")
    if filters.get("agency_review_verified") is not True:
        raise ValueError("agency export manifest must require named human review")
    if filters.get("redacted") is not False:
        raise ValueError("agency export manifest must identify its non-redacted exact-location scope")
    if filters.get("status") != "reviewed" or filters.get("class_name") != "damaged_tactile_block":
        raise ValueError("agency export manifest must contain reviewed damaged_tactile_block reports only")

    normalized_actor_id = actor_id.strip().casefold()
    if (
        ACTOR_ID.fullmatch(actor_id.strip()) is None
        or normalized_actor_id in {"unknown", "system", "anonymous"}
        or normalized_actor_id.endswith("-shared")
    ):
        raise ValueError("submitted_by_actor_id must identify a named operator")
    if manifest.get("actor_id") != actor_id.strip():
        raise ValueError("export actor and submitting actor must match")

    rows = manifest.get("rows")
    if not isinstance(rows, list) or not rows or any(
        not isinstance(row, dict) or not valid_agency_row(row) for row in rows
    ):
        raise ValueError("agency export contains a non-reviewed, non-damage, non-high-accuracy, or sensitive row")
    row_ids = [str(row["id"]) for row in rows]
    if len(set(row_ids)) != len(row_ids):
        raise ValueError("agency export contains duplicate report ids")
    report_count = manifest.get("count")
    if not isinstance(report_count, int) or isinstance(report_count, bool) or report_count != len(rows):
        raise ValueError("agency export manifest report count is invalid")
    return [dict(row) for row in rows]


def build_receipt(
    *,
    export_path: Path,
    export_manifest_path: Path,
    institution: str,
    channel: str,
    external_receipt_id: str,
    actor_id: str,
    submission_status: str,
    submitted_at: datetime | None = None,
) -> dict[str, object]:
    if channel not in {"portal", "email", "api", "in_person"}:
        raise ValueError("channel must be portal, email, api, or in_person")
    if submission_status not in {"received", "accepted"}:
        raise ValueError("submission_status must be received or accepted")
    if ACTOR_ID.fullmatch(actor_id.strip()) is None:
        raise ValueError("submitted_by_actor_id has an invalid format")
    institution = validated_text(institution, "institution")
    external_receipt_id = validated_text(external_receipt_id, "external_receipt_id")
    manifest = json.loads(export_manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("export manifest schema is invalid")
    rows = validate_agency_manifest_contract(manifest, actor_id=actor_id)
    report_count = len(rows)
    export_format, rows_sha256 = validate_export_matches_manifest(export_path, manifest)
    audit_id = validated_text(str(manifest.get("audit_id", "")), "export audit_id")
    timestamp = (submitted_at or datetime.now(UTC)).astimezone(UTC)
    return {
        "schema_version": "walksafe.agency-submission-receipt.v1",
        "submitted_at": timestamp.isoformat(),
        "institution": institution,
        "channel": channel,
        "external_receipt_id": external_receipt_id,
        "submitted_by_actor_id": actor_id.strip(),
        "submission_status": submission_status,
        "access_scope": "admin_exact_location",
        "exact_location_included": True,
        "export_sha256": sha256(export_path),
        "export_manifest_sha256": sha256(export_manifest_path),
        "export_rows_sha256": rows_sha256,
        "export_audit_id": audit_id,
        "export_format": export_format,
        "report_count": report_count,
    }


def write_json_atomic(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", type=Path, required=True)
    parser.add_argument("--export-manifest", type=Path, required=True)
    parser.add_argument("--institution", required=True)
    parser.add_argument("--channel", choices=["portal", "email", "api", "in_person"], required=True)
    parser.add_argument("--external-receipt-id", required=True)
    parser.add_argument("--actor-id", required=True)
    parser.add_argument("--submission-status", choices=["received", "accepted"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    receipt = build_receipt(
        export_path=args.export,
        export_manifest_path=args.export_manifest,
        institution=args.institution,
        channel=args.channel,
        external_receipt_id=args.external_receipt_id,
        actor_id=args.actor_id,
        submission_status=args.submission_status,
    )
    write_json_atomic(args.output, receipt)
    print(f"agency_submission_receipt={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
