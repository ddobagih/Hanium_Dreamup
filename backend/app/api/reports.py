from __future__ import annotations

import csv
import hashlib
import io
import json
import uuid
from datetime import UTC, datetime
from typing import Any, List, Optional

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, Response, UploadFile, status
from geoalchemy2.elements import WKTElement
from pydantic import ValidationError
from sqlalchemy import not_, or_, select, text
from sqlalchemy.orm import Session

from backend.app.config import Settings
from backend.app.database import get_db
from backend.app.models import Report
from backend.app.schemas import (
    ClassName,
    DetectorSource,
    DuplicateCheckResponse,
    ReportMetadata,
    ReportDemoFilter,
    ReportResponse,
    ReportStatus,
    ReportStatusUpdate,
    ReportV2Metadata,
)
from backend.app.services.duplicates import (
    DUPLICATE_RADIUS_M,
    DUPLICATE_WINDOW_MINUTES,
    duplicate_candidate_statement,
    find_duplicate_candidates,
    find_duplicate_candidates_v2,
)
from backend.app.services.report_policy import ensure_report_v2_allowed, report_v2_source
from backend.app.services.report_serialization import report_to_response
from backend.app.uploads import image_suffix, read_image_upload, write_image_file


REPORT_EXPORT_FIELDS = [
    "id",
    "status",
    "class_name",
    "confidence",
    "source",
    "latitude",
    "longitude",
    "accuracy_m",
    "heading",
    "bbox_x",
    "bbox_y",
    "bbox_width",
    "bbox_height",
    "captured_at",
    "created_at",
    "model_key",
    "source_model",
    "threshold_used",
    "trigger",
    "auto_reported",
    "reporter_user_id",
    "distance_m",
    "trace_id",
    "payload_sha256",
    "image_sha256",
    "data_origin",
    "runtime_mode",
    "performance_excluded",
    "performance_exclusion_reason",
    "location_quality",
    "review_flags",
    "duplicate_report_ids",
    "status_history_count",
    "review_note",
    "resolution_reason",
    "image_path",
]

REPORT_V2_METADATA_ALLOWLIST = {
    "schema_version",
    "source",
    "model_key",
    "source_model",
    "model_class_id",
    "class_name",
    "category",
    "confidence",
    "bbox",
    "distance_m",
    "distance_source",
    "distance_confidence",
    "threshold_used",
    "captured_at",
    "gps",
    "heading",
    "trigger",
    "auto_reported",
    "reporter_user_id",
    "review_flags",
    "fake_source",
    "trace_id",
    "data_origin",
    "runtime_mode",
    "apk_sha256",
    "model_config_sha256",
    "android_model_version",
    "bbox_coordinate_space",
    "depth_coordinate_space",
    "depth_sample_count",
    "depth_valid_sample_ratio",
    "detection_age_ms",
    "coordinate_gate_status",
    "fallback_used",
    "loaded_model_key",
    "model_load_reason",
}

ALLOWED_STATUS_TRANSITIONS: dict[ReportStatus, set[ReportStatus]] = {
    "new": {"new", "reviewed", "resolved"},
    "reviewed": {"new", "reviewed", "resolved"},
    "resolved": {"reviewed", "resolved"},
}


def _filtered_reports_statement(
    *,
    status: Optional[ReportStatus],
    class_name: Optional[str],
    source: Optional[DetectorSource],
    demo_filter: ReportDemoFilter,
    model_key: Optional[str],
    trigger: Optional[str],
    auto_reported: Optional[bool],
    created_from: Optional[datetime],
    created_to: Optional[datetime],
    lat: Optional[float],
    lng: Optional[float],
    radius_m: Optional[float],
):
    has_radius_query = lat is not None or lng is not None or radius_m is not None
    if has_radius_query and (lat is None or lng is None or radius_m is None):
        raise HTTPException(status_code=400, detail="lat, lng, and radius_m must be provided together")

    statement = select(Report)
    if status is not None:
        statement = statement.where(Report.status == status)
    if class_name is not None:
        statement = statement.where(Report.class_name == class_name)
    if source is not None:
        statement = statement.where(Report.source == source)
    if demo_filter == "only_fake":
        statement = statement.where(_fake_demo_report_condition())
    elif demo_filter == "exclude_fake":
        statement = statement.where(not_(_fake_demo_report_condition()))
    if model_key is not None:
        statement = statement.where(Report.payload.contains({"model_key": model_key}))
    if trigger is not None:
        statement = statement.where(Report.payload.contains({"trigger": trigger}))
    if auto_reported is not None:
        statement = statement.where(Report.payload.contains({"auto_reported": auto_reported}))
    if created_from is not None:
        statement = statement.where(Report.created_at >= created_from)
    if created_to is not None:
        statement = statement.where(Report.created_at <= created_to)
    if has_radius_query:
        statement = statement.where(
            text(
                "location IS NOT NULL AND "
                "ST_DWithin(location::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius_m)"
            )
        ).params(lat=lat, lng=lng, radius_m=radius_m)

    return statement


def _fake_demo_report_condition():
    return or_(
        Report.source == "fake",
        Report.payload.contains({"fake_source": True}),
        Report.payload.contains({"fake_source": "true"}),
        Report.payload.contains({"data_origin": "demo"}),
        Report.payload.contains({"metadata": {"fake_source": True}}),
        Report.payload.contains({"metadata": {"fake_source": "true"}}),
        Report.payload.contains({"review_flags": ["fake_source"]}),
        Report.payload.contains({"metadata": {"review_flags": ["fake_source"]}}),
    )


def _json_size_bytes(value: object) -> int:
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str).encode("utf-8"))


def _json_sha256(value: object) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _bytes_sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _enforce_metadata_size(raw_metadata: str, settings: Settings) -> None:
    if len(raw_metadata.encode("utf-8")) > settings.max_report_metadata_bytes:
        raise HTTPException(
            status_code=413,
            detail={
                "code": "report_metadata_too_large",
                "max_bytes": settings.max_report_metadata_bytes,
            },
        )


def _sanitized_v2_payload(raw_payload: dict[str, Any], parsed: ReportV2Metadata) -> dict[str, Any]:
    payload = {key: raw_payload[key] for key in REPORT_V2_METADATA_ALLOWLIST if key in raw_payload}
    for key, value in parsed.model_dump(mode="json", exclude_none=True).items():
        payload.setdefault(key, value)
    return payload


def _coordinate_gate_exclusion_reason(payload: dict[str, Any]) -> str | None:
    coordinate_gate_status = payload.get("coordinate_gate_status")
    if coordinate_gate_status is None:
        return "coordinate_gate_status=pending"
    if not isinstance(coordinate_gate_status, str):
        return f"coordinate_gate_status={coordinate_gate_status}"
    normalized = coordinate_gate_status.strip().lower()
    if normalized == "pass":
        return None
    return f"coordinate_gate_status={normalized or 'pending'}"


def _is_fake_payload(payload: dict[str, Any], source: str) -> bool:
    flags = payload.get("review_flags") if isinstance(payload.get("review_flags"), list) else []
    source_model = payload.get("source_model")
    runtime_mode = payload.get("runtime_mode")
    return (
        source == "fake"
        or payload.get("fake_source") is True
        or payload.get("demo") is True
        or payload.get("is_demo") is True
        or payload.get("is_fake") is True
        or "fake_source" in flags
        or (isinstance(source_model, str) and ("fake" in source_model.lower() or "demo" in source_model.lower()))
        or (isinstance(runtime_mode, str) and ("fake" in runtime_mode.lower() or "demo" in runtime_mode.lower()))
    )


def _enrich_v2_payload(payload: dict[str, Any], *, source: str, content: bytes) -> dict[str, Any]:
    enriched = dict(payload)
    fake_payload = _is_fake_payload(enriched, source)
    coordinate_gate_exclusion_reason = _coordinate_gate_exclusion_reason(enriched)
    exclusion_reasons: list[str] = []
    if fake_payload:
        exclusion_reasons.append("fake_demo_source")
    if coordinate_gate_exclusion_reason:
        exclusion_reasons.append(coordinate_gate_exclusion_reason)
    enriched["data_origin"] = "demo" if fake_payload else str(enriched.get("data_origin") or "field_candidate")
    enriched.setdefault("runtime_mode", source)
    enriched["payload_sha256"] = _json_sha256(payload)
    enriched["image_sha256"] = _bytes_sha256(content)
    enriched["performance_excluded"] = bool(exclusion_reasons)
    if exclusion_reasons:
        enriched["performance_exclusion_reason"] = ";".join(exclusion_reasons)
    else:
        enriched.setdefault("performance_exclusion_reason", "")
    return enriched


def _with_duplicate_candidate_payload(payload: dict[str, Any], candidates: list[Report]) -> dict[str, Any]:
    if not candidates:
        return payload

    enriched = dict(payload)
    flags = enriched.get("review_flags") if isinstance(enriched.get("review_flags"), list) else []
    review_flags = [flag for flag in flags if isinstance(flag, str)]
    if "duplicate_candidate" not in review_flags:
        review_flags.append("duplicate_candidate")
    enriched["review_flags"] = review_flags
    enriched["duplicate_report_ids"] = [str(candidate.id) for candidate in candidates]
    return enriched


def _csv_safe(value: object) -> object:
    if not isinstance(value, str) or not value:
        return value
    if value[0] in {"=", "+", "-", "@"}:
        return f"'{value}"
    return value


def _isoformat(value: object) -> object:
    if isinstance(value, datetime):
        return value.isoformat()
    return value


def _csv_metadata_value(value: object) -> object:
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return ""
    return value


def _report_export_row(report: Report) -> dict[str, object]:
    metadata = report.payload or {}
    response = report_to_response(report)
    status_history = metadata.get("status_history") if isinstance(metadata.get("status_history"), list) else []
    return {
        "id": str(report.id),
        "status": report.status,
        "class_name": _csv_safe(report.class_name),
        "confidence": report.confidence,
        "source": report.source,
        "latitude": report.latitude,
        "longitude": report.longitude,
        "accuracy_m": report.accuracy_m,
        "heading": report.heading,
        "bbox_x": report.bbox_x,
        "bbox_y": report.bbox_y,
        "bbox_width": report.bbox_width,
        "bbox_height": report.bbox_height,
        "captured_at": _isoformat(report.captured_at),
        "created_at": _isoformat(report.created_at),
        "model_key": _csv_metadata_value(metadata.get("model_key")),
        "source_model": _csv_safe(_csv_metadata_value(metadata.get("source_model"))),
        "threshold_used": _csv_metadata_value(metadata.get("threshold_used")),
        "trigger": _csv_metadata_value(metadata.get("trigger")),
        "auto_reported": _csv_metadata_value(metadata.get("auto_reported")),
        "reporter_user_id": _csv_safe(_csv_metadata_value(metadata.get("reporter_user_id"))),
        "distance_m": _csv_metadata_value(metadata.get("distance_m")),
        "trace_id": _csv_safe(_csv_metadata_value(metadata.get("trace_id"))),
        "payload_sha256": _csv_safe(_csv_metadata_value(metadata.get("payload_sha256"))),
        "image_sha256": _csv_safe(_csv_metadata_value(metadata.get("image_sha256"))),
        "data_origin": _csv_safe(_csv_metadata_value(metadata.get("data_origin"))),
        "runtime_mode": _csv_safe(_csv_metadata_value(metadata.get("runtime_mode"))),
        "performance_excluded": _csv_metadata_value(metadata.get("performance_excluded")),
        "performance_exclusion_reason": _csv_safe(_csv_metadata_value(metadata.get("performance_exclusion_reason"))),
        "location_quality": response.location_quality,
        "review_flags": ",".join(response.review_flags),
        "duplicate_report_ids": ",".join(str(value) for value in metadata.get("duplicate_report_ids", []) if value),
        "status_history_count": len(status_history),
        "review_note": _csv_safe(_csv_metadata_value(metadata.get("review_note"))),
        "resolution_reason": _csv_safe(_csv_metadata_value(metadata.get("resolution_reason"))),
        "image_path": _csv_safe(report.image_path),
    }


def _reports_geojson(rows: list[dict[str, object]]) -> dict[str, object]:
    features = []
    for row in rows:
        latitude = row["latitude"]
        longitude = row["longitude"]
        geometry = None
        if latitude is not None and longitude is not None:
            geometry = {
                "type": "Point",
                "coordinates": [longitude, latitude],
            }

        features.append(
            {
                "type": "Feature",
                "id": row["id"],
                "geometry": geometry,
                "properties": {
                    key: value
                    for key, value in row.items()
                    if key not in {"latitude", "longitude"}
                },
            }
        )

    return {
        "type": "FeatureCollection",
        "features": features,
    }


def _reports_cluster_geojson(summary: dict[str, object]) -> dict[str, object]:
    features = []
    clusters = summary.get("top_clusters")
    if not isinstance(clusters, list):
        clusters = []
    for cluster in clusters:
        if not isinstance(cluster, dict):
            continue
        center_latitude = cluster.get("center_latitude")
        center_longitude = cluster.get("center_longitude")
        if not isinstance(center_latitude, (int, float)) or not isinstance(center_longitude, (int, float)):
            geometry = None
        else:
            geometry = {"type": "Point", "coordinates": [center_longitude, center_latitude]}
        features.append(
            {
                "type": "Feature",
                "id": cluster.get("key"),
                "geometry": geometry,
                "properties": {
                    "cluster_key": cluster.get("key"),
                    "count": cluster.get("count"),
                    "fake": cluster.get("fake"),
                    "non_fake": cluster.get("non_fake"),
                    "bounds": cluster.get("bounds"),
                    "status_counts": cluster.get("status_counts"),
                    "source_counts": cluster.get("source_counts"),
                    "aggregate": "grid",
                },
            }
        )

    return {
        "type": "FeatureCollection",
        "features": features,
        "properties": {
            "aggregate": "grid",
            "grid_size_degrees": summary.get("grid_size_degrees"),
            "total": summary.get("total"),
            "located": summary.get("located"),
            "missing_location": summary.get("missing_location"),
        },
    }


def _redact_export_row(row: dict[str, object]) -> dict[str, object]:
    redacted = dict(row)
    redacted["image_path"] = ""
    latitude = redacted.get("latitude")
    longitude = redacted.get("longitude")
    if isinstance(latitude, (int, float)):
        redacted["latitude"] = round(float(latitude), 4)
    if isinstance(longitude, (int, float)):
        redacted["longitude"] = round(float(longitude), 4)
    return redacted


def _demo_filename_part(demo_filter: ReportDemoFilter) -> str:
    if demo_filter == "exclude_fake":
        return "demo-excluded"
    if demo_filter == "only_fake":
        return "demo-only"
    return "demo-included"


def _export_filename(export_format: str, demo_filter: ReportDemoFilter, *, aggregate: str | None = None) -> str:
    aggregate_part = "-grid" if aggregate == "grid" else ""
    extension = "geojson" if export_format == "geojson" else export_format
    return f"walksafe-reports-{_demo_filename_part(demo_filter)}{aggregate_part}.{extension}"


def _export_manifest(rows: list[dict[str, object]], *, filters: dict[str, object], generated_at: datetime) -> dict[str, object]:
    rows_json = json.dumps(rows, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return {
        "schema_version": "walksafe.reports.export_manifest.v1",
        "generated_at": generated_at.isoformat().replace("+00:00", "Z"),
        "filters": filters,
        "count": len(rows),
        "rows_sha256": hashlib.sha256(rows_json.encode("utf-8")).hexdigest(),
        "rows": rows,
    }


def _reports_summary(reports: list[Report], *, grid_size_degrees: float = 0.001, top_limit: int = 5) -> dict[str, object]:
    clusters: dict[str, dict[str, Any]] = {}
    status_counts: dict[str, int] = {"new": 0, "reviewed": 0, "resolved": 0}
    source_counts: dict[str, int] = {"fake": 0, "onnx": 0, "server": 0, "android": 0}
    location_bounds: dict[str, float] | None = None
    located = 0
    fake = 0

    for report in reports:
        status_counts[report.status] = status_counts.get(report.status, 0) + 1
        source_counts[report.source] = source_counts.get(report.source, 0) + 1
        flags = report_to_response(report).review_flags
        is_fake = "fake_source" in flags
        if is_fake:
            fake += 1
        if report.latitude is None or report.longitude is None:
            continue
        located += 1
        if location_bounds is None:
            location_bounds = {
                "min_latitude": report.latitude,
                "max_latitude": report.latitude,
                "min_longitude": report.longitude,
                "max_longitude": report.longitude,
            }
        else:
            location_bounds["min_latitude"] = min(location_bounds["min_latitude"], report.latitude)
            location_bounds["max_latitude"] = max(location_bounds["max_latitude"], report.latitude)
            location_bounds["min_longitude"] = min(location_bounds["min_longitude"], report.longitude)
            location_bounds["max_longitude"] = max(location_bounds["max_longitude"], report.longitude)
        lat_cell = int(report.latitude / grid_size_degrees)
        lng_cell = int(report.longitude / grid_size_degrees)
        key = f"{lat_cell}:{lng_cell}"
        cluster = clusters.setdefault(
            key,
            {
                "key": key,
                "count": 0,
                "fake": 0,
                "non_fake": 0,
                "latitude_sum": 0.0,
                "longitude_sum": 0.0,
                "bounds": {
                    "min_latitude": lat_cell * grid_size_degrees,
                    "max_latitude": (lat_cell + 1) * grid_size_degrees,
                    "min_longitude": lng_cell * grid_size_degrees,
                    "max_longitude": (lng_cell + 1) * grid_size_degrees,
                },
                "status_counts": {"new": 0, "reviewed": 0, "resolved": 0},
                "source_counts": {"fake": 0, "onnx": 0, "server": 0, "android": 0},
            },
        )
        cluster["count"] += 1
        cluster["fake" if is_fake else "non_fake"] += 1
        cluster["latitude_sum"] += report.latitude
        cluster["longitude_sum"] += report.longitude
        cluster["status_counts"][report.status] = cluster["status_counts"].get(report.status, 0) + 1
        cluster["source_counts"][report.source] = cluster["source_counts"].get(report.source, 0) + 1

    top_clusters = []
    for cluster in sorted(clusters.values(), key=lambda item: (-item["count"], item["key"]))[:top_limit]:
        top_clusters.append(
            {
                "key": cluster["key"],
                "count": cluster["count"],
                "fake": cluster["fake"],
                "non_fake": cluster["non_fake"],
                "center_latitude": cluster["latitude_sum"] / cluster["count"],
                "center_longitude": cluster["longitude_sum"] / cluster["count"],
                "bounds": cluster["bounds"],
                "status_counts": cluster["status_counts"],
                "source_counts": cluster["source_counts"],
            }
        )

    return {
        "total": len(reports),
        "fake": fake,
        "non_fake": len(reports) - fake,
        "located": located,
        "missing_location": len(reports) - located,
        "bounds": location_bounds,
        "status_counts": status_counts,
        "source_counts": source_counts,
        "grid_size_degrees": grid_size_degrees,
        "top_clusters": top_clusters,
        "note": "Summary is computed from the current filtered report query, not from external map SDK data.",
    }


def create_router(settings: Settings) -> APIRouter:
    router = APIRouter()

    @router.post("/reports", response_model=ReportResponse, status_code=status.HTTP_201_CREATED)
    async def create_report(
        metadata: str = Form(...),
        image: UploadFile = File(...),
        db: Session = Depends(get_db),
    ) -> ReportResponse:
        _enforce_metadata_size(metadata, settings)
        try:
            parsed = ReportMetadata.model_validate_json(metadata)
        except (ValidationError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

        content, content_type = await read_image_upload(image, settings)

        report_id = uuid.uuid4()
        filename = f"{report_id}{image_suffix(content_type)}"
        destination = settings.upload_dir / filename
        write_image_file(destination, content)

        gps = parsed.gps
        latitude = gps.latitude if gps else None
        longitude = gps.longitude if gps else None
        location = WKTElement(f"POINT({longitude} {latitude})", srid=4326) if gps else None
        duplicate_candidates = find_duplicate_candidates(db, parsed)
        stored_payload = _with_duplicate_candidate_payload(parsed.model_dump(mode="json"), duplicate_candidates)

        report = Report(
            id=report_id,
            class_id=parsed.class_id,
            class_name=parsed.class_name,
            confidence=parsed.confidence,
            bbox_x=parsed.bbox.x,
            bbox_y=parsed.bbox.y,
            bbox_width=parsed.bbox.width,
            bbox_height=parsed.bbox.height,
            captured_at=parsed.captured_at,
            source=parsed.source,
            latitude=latitude,
            longitude=longitude,
            accuracy_m=gps.accuracy_m if gps else None,
            heading=parsed.heading,
            location=location,
            image_path=f"/uploads/{filename}",
            image_content_type=content_type,
            payload=stored_payload,
        )

        db.add(report)
        db.commit()
        db.refresh(report)
        return report_to_response(report, [str(candidate.id) for candidate in duplicate_candidates])

    @router.post("/reports/v2", response_model=ReportResponse, status_code=status.HTTP_201_CREATED)
    async def create_report_v2(
        metadata: str = Form(...),
        image: UploadFile = File(...),
        db: Session = Depends(get_db),
    ) -> ReportResponse:
        _enforce_metadata_size(metadata, settings)
        try:
            raw_payload = json.loads(metadata)
            parsed = ReportV2Metadata.model_validate(raw_payload)
        except (ValidationError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

        if not isinstance(raw_payload, dict) or _json_size_bytes(raw_payload) > settings.max_report_metadata_bytes:
            raise HTTPException(
                status_code=413,
                detail={
                    "code": "report_metadata_too_large",
                    "max_bytes": settings.max_report_metadata_bytes,
                },
            )

        ensure_report_v2_allowed(parsed)
        content, content_type = await read_image_upload(image, settings)
        report_source = report_v2_source(parsed)
        stored_payload = _enrich_v2_payload(
            _sanitized_v2_payload(raw_payload, parsed),
            source=report_source,
            content=content,
        )

        report_id = uuid.uuid4()
        filename = f"{report_id}{image_suffix(content_type)}"
        destination = settings.upload_dir / filename
        write_image_file(destination, content)

        gps = parsed.gps
        latitude = gps.latitude if gps else None
        longitude = gps.longitude if gps else None
        location = WKTElement(f"POINT({longitude} {latitude})", srid=4326) if gps else None
        duplicate_candidates = find_duplicate_candidates_v2(db, parsed)
        stored_payload = _with_duplicate_candidate_payload(stored_payload, duplicate_candidates)

        report = Report(
            id=report_id,
            class_id=parsed.model_class_id,
            class_name=parsed.class_name,
            confidence=parsed.confidence,
            bbox_x=parsed.bbox.x,
            bbox_y=parsed.bbox.y,
            bbox_width=parsed.bbox.width,
            bbox_height=parsed.bbox.height,
            captured_at=parsed.captured_at,
            source=report_source,
            latitude=latitude,
            longitude=longitude,
            accuracy_m=gps.accuracy_m if gps else None,
            heading=parsed.heading,
            location=location,
            image_path=f"/uploads/{filename}",
            image_content_type=content_type,
            payload=stored_payload,
        )

        db.add(report)
        db.commit()
        db.refresh(report)
        return report_to_response(report, [str(candidate.id) for candidate in duplicate_candidates])

    @router.get("/reports", response_model=List[ReportResponse])
    async def list_reports(
        db: Session = Depends(get_db),
        limit: int = Query(default=25, ge=1, le=100),
        status: Optional[ReportStatus] = Query(default=None),
        class_name: Optional[str] = Query(default=None),
        source: Optional[DetectorSource] = Query(default=None),
        demo_filter: ReportDemoFilter = Query(default="all"),
        model_key: Optional[str] = Query(default=None),
        trigger: Optional[str] = Query(default=None),
        auto_reported: Optional[bool] = Query(default=None),
        created_from: Optional[datetime] = Query(default=None),
        created_to: Optional[datetime] = Query(default=None),
        lat: Optional[float] = Query(default=None, ge=-90, le=90),
        lng: Optional[float] = Query(default=None, ge=-180, le=180),
        radius_m: Optional[float] = Query(default=None, gt=0),
    ) -> list[ReportResponse]:
        statement = _filtered_reports_statement(
            status=status,
            class_name=class_name,
            source=source,
            demo_filter=demo_filter,
            model_key=model_key,
            trigger=trigger,
            auto_reported=auto_reported,
            created_from=created_from,
            created_to=created_to,
            lat=lat,
            lng=lng,
            radius_m=radius_m,
        ).order_by(Report.created_at.desc()).limit(limit)
        reports = db.scalars(statement).all()
        return [report_to_response(report) for report in reports]

    @router.get("/reports/export")
    async def export_reports(
        db: Session = Depends(get_db),
        format: str = Query(default="csv"),
        status: Optional[ReportStatus] = Query(default=None),
        class_name: Optional[str] = Query(default=None),
        source: Optional[DetectorSource] = Query(default=None),
        demo_filter: ReportDemoFilter = Query(default="all"),
        model_key: Optional[str] = Query(default=None),
        trigger: Optional[str] = Query(default=None),
        auto_reported: Optional[bool] = Query(default=None),
        created_from: Optional[datetime] = Query(default=None),
        created_to: Optional[datetime] = Query(default=None),
        lat: Optional[float] = Query(default=None, ge=-90, le=90),
        lng: Optional[float] = Query(default=None, ge=-180, le=180),
        radius_m: Optional[float] = Query(default=None, gt=0),
        redacted: bool = Query(default=False),
        bom: bool = Query(default=False),
        manifest: bool = Query(default=False),
        aggregate: Optional[str] = Query(default=None),
    ):
        export_format = format.lower()
        if aggregate not in {None, "grid"}:
            raise HTTPException(status_code=400, detail="aggregate must be grid when provided")
        if aggregate is not None and export_format != "geojson":
            raise HTTPException(status_code=400, detail="aggregate is only supported for geojson export")
        statement = _filtered_reports_statement(
            status=status,
            class_name=class_name,
            source=source,
            demo_filter=demo_filter,
            model_key=model_key,
            trigger=trigger,
            auto_reported=auto_reported,
            created_from=created_from,
            created_to=created_to,
            lat=lat,
            lng=lng,
            radius_m=radius_m,
        ).order_by(Report.created_at.desc())
        reports = list(db.scalars(statement).all())
        rows = [_report_export_row(report) for report in reports]
        if redacted:
            rows = [_redact_export_row(row) for row in rows]

        filename = _export_filename(export_format, demo_filter, aggregate=aggregate)
        content_disposition = f'attachment; filename="{filename}"'
        if export_format == "json":
            content: object = rows
            if manifest:
                content = _export_manifest(
                    rows,
                    filters={
                        "status": status,
                        "class_name": class_name,
                        "source": source,
                        "demo_filter": demo_filter,
                        "model_key": model_key,
                        "trigger": trigger,
                        "auto_reported": auto_reported,
                        "created_from": created_from,
                        "created_to": created_to,
                        "lat": lat,
                        "lng": lng,
                        "radius_m": radius_m,
                        "redacted": redacted,
                    },
                    generated_at=datetime.now(UTC),
                )
            return Response(
                content=json.dumps(content, ensure_ascii=False, default=str),
                media_type="application/json",
                headers={
                    "Content-Disposition": content_disposition,
                    "Cache-Control": "no-store",
                    "X-WalkSafe-Demo-Filter": demo_filter,
                },
            )
        if export_format == "geojson":
            body = _reports_cluster_geojson(_reports_summary(reports)) if aggregate == "grid" else _reports_geojson(rows)
            return Response(
                content=json.dumps(body, ensure_ascii=False, default=str),
                media_type="application/geo+json",
                headers={
                    "Content-Disposition": content_disposition,
                    "Cache-Control": "no-store",
                    "X-WalkSafe-Demo-Filter": demo_filter,
                },
            )
        if export_format != "csv":
            raise HTTPException(status_code=400, detail="format must be csv, json, or geojson")

        output = io.StringIO()
        writer = csv.DictWriter(output, fieldnames=REPORT_EXPORT_FIELDS)
        writer.writeheader()
        writer.writerows(rows)
        csv_text = output.getvalue()
        if bom:
            csv_text = "\ufeff" + csv_text
        return Response(
            content=csv_text,
            media_type="text/csv",
            headers={
                "Content-Disposition": content_disposition,
                "Cache-Control": "no-store",
                "X-WalkSafe-Demo-Filter": demo_filter,
            },
        )

    @router.get("/reports/summary")
    async def summarize_reports(
        db: Session = Depends(get_db),
        status: Optional[ReportStatus] = Query(default=None),
        class_name: Optional[str] = Query(default=None),
        source: Optional[DetectorSource] = Query(default=None),
        demo_filter: ReportDemoFilter = Query(default="all"),
        model_key: Optional[str] = Query(default=None),
        trigger: Optional[str] = Query(default=None),
        auto_reported: Optional[bool] = Query(default=None),
        created_from: Optional[datetime] = Query(default=None),
        created_to: Optional[datetime] = Query(default=None),
        lat: Optional[float] = Query(default=None, ge=-90, le=90),
        lng: Optional[float] = Query(default=None, ge=-180, le=180),
        radius_m: Optional[float] = Query(default=None, gt=0),
        grid_size_degrees: float = Query(default=0.001, gt=0, le=1),
        top_limit: int = Query(default=5, ge=0, le=50),
    ) -> dict[str, object]:
        statement = _filtered_reports_statement(
            status=status,
            class_name=class_name,
            source=source,
            demo_filter=demo_filter,
            model_key=model_key,
            trigger=trigger,
            auto_reported=auto_reported,
            created_from=created_from,
            created_to=created_to,
            lat=lat,
            lng=lng,
            radius_m=radius_m,
        )
        return _reports_summary(
            list(db.scalars(statement).all()),
            grid_size_degrees=grid_size_degrees,
            top_limit=top_limit,
        )

    @router.get("/reports/duplicate-check", response_model=DuplicateCheckResponse)
    async def check_report_duplicates(
        db: Session = Depends(get_db),
        class_name: ClassName = Query(...),
        captured_at: datetime = Query(...),
        lat: float = Query(..., ge=-90, le=90),
        lng: float = Query(..., ge=-180, le=180),
        radius_m: float = Query(default=DUPLICATE_RADIUS_M, gt=0, le=200),
        minutes: int = Query(default=DUPLICATE_WINDOW_MINUTES, ge=1, le=60),
    ) -> DuplicateCheckResponse:
        statement = duplicate_candidate_statement(
            class_name=class_name,
            captured_at=captured_at,
            lat=lat,
            lng=lng,
            radius_m=radius_m,
            minutes=minutes,
        )
        reports = list(db.scalars(statement).all())
        report_ids = [str(report.id) for report in reports]
        return DuplicateCheckResponse(
            duplicate_report_ids=report_ids,
            reports=[report_to_response(report) for report in reports],
        )

    @router.get("/reports/{report_id}", response_model=ReportResponse)
    async def get_report(report_id: uuid.UUID, db: Session = Depends(get_db)) -> ReportResponse:
        report = db.get(Report, report_id)
        if report is None:
            raise HTTPException(status_code=404, detail="report not found")
        return report_to_response(report)

    @router.patch("/reports/{report_id}/status", response_model=ReportResponse)
    async def update_report_status(
        report_id: uuid.UUID,
        payload: ReportStatusUpdate,
        db: Session = Depends(get_db),
    ) -> ReportResponse:
        report = db.get(Report, report_id)
        if report is None:
            raise HTTPException(status_code=404, detail="report not found")

        if (
            payload.expected_updated_at is not None
            and report.updated_at is not None
            and report.updated_at.isoformat() != payload.expected_updated_at.isoformat()
        ):
            raise HTTPException(status_code=409, detail="report status was updated by another request")

        allowed_next = ALLOWED_STATUS_TRANSITIONS.get(report.status, {report.status})
        if payload.status not in allowed_next:
            raise HTTPException(
                status_code=422,
                detail=f"status transition {report.status}->{payload.status} is not allowed",
            )

        previous_status = report.status
        report_payload = dict(report.payload or {})
        history = list(report_payload.get("status_history") or [])
        history.append(
            {
                "from": previous_status,
                "to": payload.status,
                "at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
                "note": payload.note,
                "resolution_reason": payload.resolution_reason,
            }
        )
        report_payload["status_history"] = history[-20:]
        if payload.note:
            report_payload["review_note"] = payload.note
        if payload.resolution_reason:
            report_payload["resolution_reason"] = payload.resolution_reason
        report.status = payload.status
        report.payload = report_payload
        db.commit()
        db.refresh(report)
        return report_to_response(report)

    return router
