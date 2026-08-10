from __future__ import annotations

from datetime import UTC, datetime
import csv
import hashlib
import json
from pathlib import Path
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scripts.record_walksafe_agency_submission_20260711 import build_receipt, canonical_rows_sha256  # noqa: E402
from scripts.check_walksafe_release_evidence_20260711 import (  # noqa: E402
    EvidenceFailure,
    validate_agency_submission_receipt,
)


def agency_rows() -> list[dict[str, object]]:
    return [
        {
            "id": "11111111-1111-4111-8111-111111111111",
            "class_name": "damaged_tactile_block",
            "confidence": 0.91,
            "latitude": 37.5,
            "longitude": 127.0,
            "accuracy_m": 7.5,
            "status": "reviewed",
            "location_quality": "high",
            "captured_at": "2026-07-11T11:55:00+00:00",
            "created_at": "2026-07-11T11:55:10+00:00",
        },
        {
            "id": "22222222-2222-4222-8222-222222222222",
            "class_name": "damaged_tactile_block",
            "confidence": 0.88,
            "latitude": 37.6,
            "longitude": 127.1,
            "accuracy_m": 12.0,
            "status": "reviewed",
            "location_quality": "high",
            "captured_at": "2026-07-11T11:56:00+00:00",
            "created_at": "2026-07-11T11:56:10+00:00",
        },
    ]


def write_manifest(path: Path, **overrides: object) -> None:
    rows = agency_rows()
    payload: dict[str, object] = {
        "schema_version": "walksafe.reports.export_manifest.v1",
        "audit_id": "33333333-3333-4333-8333-333333333333",
        "generated_at": "2026-07-11T12:00:00+00:00",
        "actor_id": "operator.kim",
        "count": len(rows),
        "rows": rows,
        "rows_sha256": canonical_rows_sha256(rows),
        "filters": {
            "profile": "agency",
            "status": "reviewed",
            "class_name": "damaged_tactile_block",
            "demo_filter": "exclude_fake",
            "performance_excluded": None,
            "agency_review_verified": True,
            "redacted": False,
        },
    }
    payload.update(overrides)
    path.write_text(json.dumps(payload), encoding="utf-8")


def test_receipt_records_external_acceptance_without_copying_coordinates(tmp_path: Path) -> None:
    rows = agency_rows()
    export = tmp_path / "agency.csv"
    with export.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    manifest = tmp_path / "manifest.json"
    write_manifest(manifest)

    receipt = build_receipt(
        export_path=export,
        export_manifest_path=manifest,
        institution="서울시 보도관리부서",
        channel="portal",
        external_receipt_id="SEOUL-2026-0001",
        actor_id="operator.kim",
        submission_status="received",
        submitted_at=datetime(2026, 7, 11, 12, tzinfo=UTC),
    )

    assert receipt["schema_version"] == "walksafe.agency-submission-receipt.v1"
    assert receipt["exact_location_included"] is True
    assert receipt["report_count"] == 2
    assert "latitude" not in receipt and "longitude" not in receipt
    assert len(str(receipt["export_sha256"])) == 64
    assert receipt["export_format"] == "csv"


@pytest.mark.parametrize("institution", ["", "TBD", "기관명"])
def test_receipt_rejects_placeholder_institution(tmp_path: Path, institution: str) -> None:
    export = tmp_path / "agency.json"
    export.write_text("{}", encoding="utf-8")
    manifest = tmp_path / "manifest.json"
    write_manifest(manifest)

    with pytest.raises(ValueError, match="institution"):
        build_receipt(
            export_path=export,
            export_manifest_path=manifest,
            institution=institution,
            channel="email",
            external_receipt_id="mail-1",
            actor_id="operator.kim",
            submission_status="accepted",
        )


@pytest.mark.parametrize(
    ("row_update", "error"),
    [
        ({"location_quality": "medium"}, "non-high-accuracy"),
        ({"accuracy_m": 15.1}, "non-high-accuracy"),
        ({"latitude": float("nan")}, "non-high-accuracy"),
        ({"confidence": "=HYPERLINK('https://example.invalid')"}, "sensitive"),
        ({"captured_at": "person@example.com"}, "sensitive"),
        ({"created_at": {"private": "payload"}}, "sensitive"),
        ({"image_path": "uploads/private.jpg"}, "sensitive"),
    ],
)
def test_receipt_rejects_rows_outside_agency_contract(
    tmp_path: Path,
    row_update: dict[str, object],
    error: str,
) -> None:
    rows = agency_rows()
    rows[0].update(row_update)
    export = tmp_path / "agency.json"
    export.write_text(json.dumps(rows), encoding="utf-8")
    manifest = tmp_path / "manifest.json"
    write_manifest(
        manifest,
        rows=rows,
        count=len(rows),
        rows_sha256=canonical_rows_sha256(rows),
    )

    with pytest.raises(ValueError, match=error):
        build_receipt(
            export_path=export,
            export_manifest_path=manifest,
            institution="서울시 보도관리부서",
            channel="portal",
            external_receipt_id="SEOUL-2026-0001",
            actor_id="operator.kim",
            submission_status="received",
        )


@pytest.mark.parametrize(
    "filter_update",
    [
        {"performance_excluded": False},
        {"agency_review_verified": False},
        {"redacted": True},
    ],
)
def test_receipt_rejects_manifest_that_does_not_match_agency_review_contract(
    tmp_path: Path,
    filter_update: dict[str, object],
) -> None:
    export = tmp_path / "agency.json"
    export.write_text(json.dumps(agency_rows()), encoding="utf-8")
    manifest = tmp_path / "manifest.json"
    write_manifest(manifest)
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    payload["filters"].update(filter_update)
    manifest.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(ValueError, match="performance|human review|non-redacted"):
        build_receipt(
            export_path=export,
            export_manifest_path=manifest,
            institution="서울시 보도관리부서",
            channel="portal",
            external_receipt_id="SEOUL-2026-0001",
            actor_id="operator.kim",
            submission_status="received",
        )


def test_receipt_rejects_shared_operator_identity(tmp_path: Path) -> None:
    export = tmp_path / "agency.json"
    export.write_text(json.dumps(agency_rows()), encoding="utf-8")
    manifest = tmp_path / "manifest.json"
    write_manifest(manifest, actor_id="admin-shared")

    with pytest.raises(ValueError, match="named operator"):
        build_receipt(
            export_path=export,
            export_manifest_path=manifest,
            institution="서울시 보도관리부서",
            channel="portal",
            external_receipt_id="SEOUL-2026-0001",
            actor_id="admin-shared",
            submission_status="received",
        )


def test_receipt_rejects_duplicate_report_ids(tmp_path: Path) -> None:
    rows = agency_rows()
    rows[1]["id"] = rows[0]["id"]
    export = tmp_path / "agency.json"
    export.write_text(json.dumps(rows), encoding="utf-8")
    manifest = tmp_path / "manifest.json"
    write_manifest(manifest, rows=rows, count=len(rows), rows_sha256=canonical_rows_sha256(rows))

    with pytest.raises(ValueError, match="duplicate report ids"):
        build_receipt(
            export_path=export,
            export_manifest_path=manifest,
            institution="서울시 보도관리부서",
            channel="portal",
            external_receipt_id="SEOUL-2026-0001",
            actor_id="operator.kim",
            submission_status="received",
        )


def test_receipt_rejects_geojson_with_a_different_export_audit_id(tmp_path: Path) -> None:
    rows = agency_rows()
    export = tmp_path / "agency.geojson"
    export.write_text(
        json.dumps(
            {
                "type": "FeatureCollection",
                "features": [
                    {
                        "type": "Feature",
                        "id": row["id"],
                        "geometry": {
                            "type": "Point",
                            "coordinates": [row["longitude"], row["latitude"]],
                        },
                        "properties": {
                            key: value for key, value in row.items() if key not in {"latitude", "longitude"}
                        },
                    }
                    for row in rows
                ],
                "properties": {
                    "actor_id": "operator.kim",
                    "location_precision": "exact-report",
                    "audit_id": "44444444-4444-4444-8444-444444444444",
                },
            }
        ),
        encoding="utf-8",
    )
    manifest = tmp_path / "manifest.json"
    write_manifest(manifest)

    with pytest.raises(ValueError, match="does not match"):
        build_receipt(
            export_path=export,
            export_manifest_path=manifest,
            institution="서울시 보도관리부서",
            channel="portal",
            external_receipt_id="SEOUL-2026-0001",
            actor_id="operator.kim",
            submission_status="received",
        )


def test_release_gate_independently_revalidates_agency_manifest_contract(tmp_path: Path) -> None:
    submitted_at = datetime(2026, 7, 11, 12, tzinfo=UTC)
    export = tmp_path / "agency.json"
    export.write_text(json.dumps(agency_rows()), encoding="utf-8")
    manifest = tmp_path / "manifest.json"
    write_manifest(manifest)
    receipt_payload = build_receipt(
        export_path=export,
        export_manifest_path=manifest,
        institution="서울시 보도관리부서",
        channel="portal",
        external_receipt_id="SEOUL-2026-0001",
        actor_id="operator.kim",
        submission_status="received",
        submitted_at=submitted_at,
    )
    receipt = tmp_path / "receipt.json"
    receipt.write_text(json.dumps(receipt_payload), encoding="utf-8")
    evidence = {"details": {"external_receipt_id": "SEOUL-2026-0001"}}

    assert validate_agency_submission_receipt(
        receipt,
        now=submitted_at,
        max_age_days=30,
        evidence_check=evidence,
        export_path=export,
        export_manifest_path=manifest,
    )["report_count"] == 2

    receipt_payload["report_count"] = 999
    receipt.write_text(json.dumps(receipt_payload), encoding="utf-8")
    with pytest.raises(EvidenceFailure, match="report count"):
        validate_agency_submission_receipt(
            receipt,
            now=submitted_at,
            max_age_days=30,
            evidence_check=evidence,
            export_path=export,
            export_manifest_path=manifest,
        )
    receipt_payload["report_count"] = 2

    manifest_payload = json.loads(manifest.read_text(encoding="utf-8"))
    manifest_payload["filters"]["performance_excluded"] = False
    manifest.write_text(json.dumps(manifest_payload), encoding="utf-8")
    receipt_payload["export_manifest_sha256"] = hashlib.sha256(manifest.read_bytes()).hexdigest()
    receipt.write_text(json.dumps(receipt_payload), encoding="utf-8")

    with pytest.raises(EvidenceFailure, match="agency export and manifest are not bound"):
        validate_agency_submission_receipt(
            receipt,
            now=submitted_at,
            max_age_days=30,
            evidence_check=evidence,
            export_path=export,
            export_manifest_path=manifest,
        )
