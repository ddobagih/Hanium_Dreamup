"""Bounded report export application operation and serialization; transport preserves its public signature."""
from __future__ import annotations

from backend.app.services.report_application import ACTOR_ID_PATTERN, UNKNOWN_ACTOR_ID, _agency_review_was_named, _filtered_reports_statement, _is_named_human_actor, _reauthorize_admin_high_risk_in_transaction, _reports_summary, _resolved_actor_id
import csv
from contextlib import closing
import hashlib
import json
import logging
import uuid
import zipfile
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Iterable, Literal, Optional
from fastapi import HTTPException, Request, Response, status
from pydantic import AwareDatetime
from sqlalchemy import Text, case, func, not_
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from backend.app.config import Settings
from backend.app.models import Report, ReportExportAudit
from backend.app.schemas import DetectorSource, ReportDemoFilter, ReportStatus
from backend.app.services.report_serialization import report_to_response
from backend.app.services.report_export_limits import MAX_REPORT_EXPORT_BYTES, BoundedBytesIO, BoundedUtf8TextBuffer, ExportCapacityLimit, ExportSizeLimit, ExportWorkGate, bounded_json_bytes, iter_bounded_json_bytes


_REPORT_EXPORT_GATE = ExportWorkGate()


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


MAX_REPORT_EXPORT_ROWS = 10_000


MAX_REPORT_AGENCY_EXPORT_SCAN_ROWS = 20_000


logger = logging.getLogger(__name__)


def _csv_safe(value: object) -> object:
    if not isinstance(value, str) or not value:
        return value
    stripped = value.lstrip()
    if stripped and stripped[0] in {"=", "+", "-", "@"}:
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
        "class_name": report.class_name,
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
        "source_model": _csv_metadata_value(metadata.get("source_model")),
        "threshold_used": _csv_metadata_value(metadata.get("threshold_used")),
        "trigger": _csv_metadata_value(metadata.get("trigger")),
        "auto_reported": _csv_metadata_value(metadata.get("auto_reported")),
        "reporter_user_id": _csv_metadata_value(metadata.get("reporter_user_id")),
        "distance_m": _csv_metadata_value(metadata.get("distance_m")),
        "trace_id": _csv_metadata_value(metadata.get("trace_id")),
        "payload_sha256": _csv_metadata_value(metadata.get("payload_sha256")),
        "image_sha256": _csv_metadata_value(metadata.get("image_sha256")),
        "data_origin": _csv_metadata_value(metadata.get("data_origin")),
        "runtime_mode": _csv_metadata_value(metadata.get("runtime_mode")),
        "performance_excluded": _csv_metadata_value(metadata.get("performance_excluded")),
        "performance_exclusion_reason": _csv_metadata_value(metadata.get("performance_exclusion_reason")),
        "location_quality": response.location_quality,
        "review_flags": ",".join(response.review_flags),
        "duplicate_report_ids": ",".join(str(value) for value in metadata.get("duplicate_report_ids", []) if value),
        "status_history_count": len(status_history),
        "review_note": _csv_metadata_value(metadata.get("review_note")),
        "resolution_reason": _csv_metadata_value(metadata.get("resolution_reason")),
        "image_path": report.image_path,
    }


def _iter_bounded_export_reports(db: Session, statement) -> Iterable[Report]:
    # Project only columns used by export/summary. The CASE prevents an
    # oversized JSONB value from reaching the driver before Python can reject
    # it; yield_per keeps the remaining result out of the ORM identity map.
    payload_bytes = func.octet_length(Report.payload.cast(Text))
    bounded_payload = case(
        (payload_bytes <= MAX_REPORT_EXPORT_BYTES, Report.payload),
        else_=None,
    ).label("payload")
    projected = statement.with_only_columns(
        Report.id, Report.status, Report.class_id, Report.class_name,
        Report.confidence, Report.source, Report.latitude, Report.longitude,
        Report.accuracy_m, Report.heading, Report.bbox_x, Report.bbox_y,
        Report.bbox_width, Report.bbox_height, Report.captured_at,
        Report.created_at, Report.updated_at, Report.image_path,
        Report.image_content_type, bounded_payload,
    ).execution_options(yield_per=1)
    result = db.execute(projected)
    try:
        for record in result.mappings():
            if record["payload"] is None:
                raise ExportSizeLimit()
            yield SimpleNamespace(**record)
    finally:
        result.close()


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
            "location_precision": "exact-source-grid",
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


def _minimum_export_row(row: dict[str, object]) -> dict[str, object]:
    minimum: dict[str, object] = {field: "" for field in REPORT_EXPORT_FIELDS}
    for field in {"status", "class_name", "location_quality"}:
        minimum[field] = row.get(field, "")
    confidence = row.get("confidence")
    if isinstance(confidence, (int, float)) and not isinstance(confidence, bool):
        minimum["confidence"] = round(float(confidence), 2)
    for field in {"latitude", "longitude"}:
        coordinate = row.get(field)
        if isinstance(coordinate, (int, float)) and not isinstance(coordinate, bool):
            minimum[field] = round(float(coordinate), 2)
    for field in {"captured_at", "created_at"}:
        timestamp = row.get(field)
        minimum[field] = str(timestamp)[:10] if timestamp else ""
    return minimum


def _agency_export_row(row: dict[str, object]) -> dict[str, object]:
    agency_fields = {
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
    return {
        field: row.get(field, "") if field in agency_fields else ""
        for field in REPORT_EXPORT_FIELDS
    }


def _demo_filename_part(demo_filter: ReportDemoFilter) -> str:
    if demo_filter == "exclude_fake":
        return "demo-excluded"
    if demo_filter == "only_fake":
        return "demo-only"
    return "demo-included"


def _export_filename(
    export_format: str,
    demo_filter: ReportDemoFilter,
    *,
    aggregate: str | None = None,
    profile: str = "internal",
) -> str:
    aggregate_part = "-grid" if aggregate == "grid" else ""
    profile_part = "" if profile == "internal" else f"-{profile}"
    extension = "geojson" if export_format == "geojson" else export_format
    if profile == "minimum":
        return f"walksafe-reports-minimum.{extension}"
    return f"walksafe-reports-{_demo_filename_part(demo_filter)}{profile_part}{aggregate_part}.{extension}"


def _export_manifest(
    rows: list[dict[str, object]],
    *,
    filters: dict[str, object],
    export_format: str,
    generated_at: datetime,
    actor_id: str,
    audit_id: str,
) -> dict[str, object]:
    if filters.get("profile") == "minimum":
        return {
            "schema_version": "walksafe.reports.export_manifest.v1",
            "profile": "minimum",
            "format": export_format,
            "count": len(rows),
            "rows": rows,
        }
    rows_sha256 = _export_rows_sha256(rows)
    manifest_filters = dict(filters)
    if filters.get("profile") == "minimum" or filters.get("redacted"):
        location_filter_applied = all(
            filters.get(field) is not None for field in ("lat", "lng", "radius_m")
        )
        for field in ("lat", "lng", "radius_m"):
            manifest_filters.pop(field, None)
        manifest_filters["location_filter_applied"] = location_filter_applied
        manifest_filters["location_filter_type"] = "radius" if location_filter_applied else None
    return {
        "schema_version": "walksafe.reports.export_manifest.v1",
        "generated_at": generated_at.isoformat().replace("+00:00", "Z"),
        "audit_id": audit_id,
        "actor_id": actor_id,
        "filters": manifest_filters,
        "count": len(rows),
        "rows_sha256": rows_sha256,
        "rows": rows,
    }


def _export_rows_sha256(rows: list[dict[str, object]]) -> str:
    """Hash UTF-8, compact, key-sorted JSON in the supplied row order.

    Exports select created_at DESC, id DESC; profile projection/filtering keeps
    that relative order. JSON uses ensure_ascii=False and default=str. This
    hashes the projected rows, not CSV/ZIP bytes or per-request manifest metadata.
    """
    digest = hashlib.sha256()
    for chunk in iter_bounded_json_bytes(
        rows, max_bytes=MAX_REPORT_EXPORT_BYTES, ensure_ascii=False,
        sort_keys=True, separators=(",", ":"), default=str,
    ):
        digest.update(chunk)
    return digest.hexdigest()


def _csv_export_text(rows: list[dict[str, object]], *, bom: bool) -> str:
    output = BoundedUtf8TextBuffer(MAX_REPORT_EXPORT_BYTES)
    if bom:
        output.write("\ufeff")
    writer = csv.DictWriter(output, fieldnames=REPORT_EXPORT_FIELDS)
    writer.writeheader()
    writer.writerows(
        {field: _csv_safe(value) for field, value in row.items()}
        for row in rows
    )
    return output.getvalue()


def _persist_export_audit(
    db: Session,
    *,
    audit_id: str,
    requested_audit_id: str | None,
    actor_id: str,
    export_format: str,
    profile: str,
    aggregate: str | None,
    row_count: int,
    location_precision: str,
    rows_sha256: str,
    filters: dict[str, object],
) -> None:
    serialized_filters = _durable_export_audit_filters(filters)
    db.add(
        ReportExportAudit(
            audit_id=uuid.UUID(audit_id),
            requested_audit_id=uuid.UUID(requested_audit_id) if requested_audit_id else None,
            actor_id=actor_id,
            export_format=export_format,
            profile=profile,
            aggregate=aggregate,
            row_count=row_count,
            location_precision=location_precision,
            rows_sha256=rows_sha256,
            filters=serialized_filters,
        )
    )
    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        constraint_name = getattr(getattr(exc.orig, "diag", None), "constraint_name", "")
        if constraint_name != "uq_report_export_audits_audit_id":
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "export_audit_unavailable",
                    "message": "Export was withheld because its durable audit record could not be stored.",
                },
            ) from exc
        raise HTTPException(
            status_code=409,
            detail={
                "code": "export_audit_id_conflict",
                "message": "This export audit id has already been used.",
            },
        ) from exc
    except BaseException as exc:
        db.rollback()
        raise HTTPException(
            status_code=503,
            detail={
                "code": "export_audit_unavailable",
                "message": "Export was withheld because its durable audit record could not be stored.",
            },
        ) from exc


def _durable_export_audit_filters(filters: dict[str, object]) -> dict[str, object]:
    """Retain useful audit scope without persisting an exact query coordinate."""

    serialized = json.loads(json.dumps(filters, default=str))
    exact_location_filter_applied = serialized.get("lat") is not None or serialized.get("lng") is not None
    serialized.pop("lat", None)
    serialized.pop("lng", None)
    serialized["exact_location_filter_redacted"] = exact_location_filter_applied
    return serialized


def build_report_export(*, settings: Settings, request: Request, db: Session, format: str, status: Optional[ReportStatus], class_name: Optional[str], source: Optional[DetectorSource], demo_filter: ReportDemoFilter, model_key: Optional[str], trigger: Optional[str], auto_reported: Optional[bool], performance_excluded: Optional[bool], created_from: Optional[AwareDatetime], created_to: Optional[AwareDatetime], lat: Optional[float], lng: Optional[float], radius_m: Optional[float], redacted: bool, profile: Literal['internal', 'minimum', 'agency'], bom: bool, manifest: bool, bundle: bool, aggregate: Optional[str], requested_audit_id: Optional[uuid.UUID], x_walksafe_actor_id: Optional[str]):
    _reauthorize_admin_high_risk_in_transaction(
        request,
        db,
        settings,
        "report.export",
    )
    actor_id = _resolved_actor_id(x_walksafe_actor_id, required=settings.field_test_security_enabled)
    audit_id = str(requested_audit_id or uuid.uuid4())
    export_format = format.lower()
    if export_format not in {"csv", "json", "geojson"}:
        raise HTTPException(status_code=400, detail="format must be csv, json, or geojson")
    if bundle and (export_format != "csv" or not manifest):
        raise HTTPException(status_code=400, detail="bundle requires format=csv and manifest=true")
    if aggregate not in {None, "grid"}:
        raise HTTPException(status_code=400, detail="aggregate must be grid when provided")
    if aggregate is not None and export_format != "geojson":
        raise HTTPException(status_code=400, detail="aggregate is only supported for geojson export")
    if aggregate == "grid" and (redacted or profile != "internal"):
        raise HTTPException(
            status_code=400,
            detail=(
                "grid export is an admin exact-location aggregate; "
                "redacted/non-internal profiles are not supported"
            ),
        )
    if profile == "agency" and redacted:
        raise HTTPException(status_code=400, detail="agency export requires exact report coordinates")
    if profile == "agency":
        if not _is_named_human_actor(actor_id):
            raise HTTPException(status_code=403, detail="agency export requires a named admin actor")
        if demo_filter == "only_fake":
            raise HTTPException(
                status_code=400,
                detail="agency export excludes demo reports",
            )
        if status not in {None, "reviewed"} or class_name not in {None, "damaged_tactile_block"}:
            raise HTTPException(
                status_code=400,
                detail="agency export only supports reviewed damaged_tactile_block reports",
            )
        status = "reviewed"
        class_name = "damaged_tactile_block"
        demo_filter = "exclude_fake"
        # Client-asserted detections remain excluded from model performance
        # metrics. Institution export is a separate, named human-review
        # decision recorded in agency_review_verified.
        performance_excluded = None
        if not bundle:
            raise HTTPException(
                status_code=400,
                detail={
                    "code": "agency_export_requires_bundle",
                    "message": "Agency export requires one CSV+manifest bundle snapshot.",
                },
            )
    if aggregate == "grid":
        location_precision = "exact-source-grid"
    elif profile == "agency":
        location_precision = "exact-report"
    elif profile == "minimum" or redacted:
        location_precision = "rounded"
    else:
        location_precision = "exact-internal"
    try:
        with _REPORT_EXPORT_GATE.slot():
            statement = _filtered_reports_statement(
                status=status,
                class_name=class_name,
                source=source,
                demo_filter=demo_filter,
                model_key=model_key,
                trigger=trigger,
                auto_reported=auto_reported,
                performance_excluded=performance_excluded,
                created_from=created_from,
                created_to=created_to,
                lat=lat,
                lng=lng,
                radius_m=radius_m,
            )
            if profile == "agency":
                reviewer_actor = Report.payload["agency_reviewed_by_actor_id"].astext
                normalized_reviewer_actor = func.lower(reviewer_actor)
                statement = statement.where(
                    Report.payload.contains({"agency_review_verified": True}),
                    func.jsonb_typeof(Report.payload["agency_reviewed_by_actor_id"]) == "string",
                    reviewer_actor.op("~")(ACTOR_ID_PATTERN.pattern),
                    normalized_reviewer_actor.notin_([UNKNOWN_ACTOR_ID, "system", "anonymous"]),
                    not_(normalized_reviewer_actor.like("%-shared")),
                )
            statement = statement.order_by(Report.created_at.desc(), Report.id.desc())
            statement = statement.limit(
                (MAX_REPORT_AGENCY_EXPORT_SCAN_ROWS if profile == "agency" else MAX_REPORT_EXPORT_ROWS) + 1
            )
            rows: list[dict[str, object]] = []
            serialized_rows_bytes = 2  # JSON array brackets in the existing audit hash.
            selected_count = 0
            scanned_count = 0

            def selected_reports() -> Iterable[Report]:
                nonlocal selected_count, scanned_count, serialized_rows_bytes
                for report in report_stream:
                    if profile == "agency":
                        scanned_count += 1
                        if scanned_count > MAX_REPORT_AGENCY_EXPORT_SCAN_ROWS:
                            raise HTTPException(
                                status_code=413,
                                detail={
                                    "code": "report_export_too_large",
                                    "message": "narrow the export filters before downloading",
                                    "max_scanned_rows": MAX_REPORT_AGENCY_EXPORT_SCAN_ROWS,
                                },
                            )
                        if not _agency_review_was_named(dict(report.payload or {})):
                            continue
                    row = _report_export_row(report)
                    if profile == "agency":
                        if (
                            not isinstance(row.get("latitude"), (int, float))
                            or not isinstance(row.get("longitude"), (int, float))
                            or row.get("location_quality") != "high"
                        ):
                            continue
                        row = _agency_export_row(row)
                    elif profile == "minimum":
                        row = _minimum_export_row(row)
                    elif redacted:
                        row = _redact_export_row(row)
                    selected_count += 1
                    if selected_count > MAX_REPORT_EXPORT_ROWS:
                        raise HTTPException(
                            status_code=413,
                            detail={
                                "code": "report_export_too_large",
                                "message": "narrow the export filters before downloading",
                                "max_rows": MAX_REPORT_EXPORT_ROWS,
                            },
                        )
                    remaining = MAX_REPORT_EXPORT_BYTES - serialized_rows_bytes - (1 if rows else 0)
                    if remaining < 1:
                        raise ExportSizeLimit()
                    row_bytes = sum(len(chunk) for chunk in iter_bounded_json_bytes(
                        row, max_bytes=remaining, ensure_ascii=False,
                        sort_keys=True, separators=(",", ":"), default=str,
                    ))
                    serialized_rows_bytes += row_bytes + (1 if rows else 0)
                    rows.append(row)
                    yield report

            with closing(_iter_bounded_export_reports(db, statement)) as report_stream:
                if aggregate == "grid":
                    grid_summary = _reports_summary(selected_reports())
                else:
                    for _ in selected_reports():
                        pass

            export_filters: dict[str, object] = {
                "status": status,
                "class_name": class_name,
                "source": source,
                "demo_filter": demo_filter,
                "model_key": model_key,
                "trigger": trigger,
                "auto_reported": auto_reported,
                "performance_excluded": performance_excluded,
                "created_from": created_from,
                "created_to": created_to,
                "lat": lat,
                "lng": lng,
                "radius_m": radius_m,
                "redacted": redacted,
                "profile": profile,
                "agency_review_verified": True if profile == "agency" else None,
                "bundle": bundle,
            }
            filename = _export_filename(export_format, demo_filter, aggregate=aggregate, profile=profile)
            content_disposition = f'attachment; filename="{filename}"'
            rows_sha256 = _export_rows_sha256(rows)
            private_export_headers = {} if profile == "minimum" else {
                "X-WalkSafe-Audit-Id": audit_id,
                "X-WalkSafe-Actor-Id": actor_id,
                "X-WalkSafe-Demo-Filter": demo_filter,
            }
            if bundle:
                csv_text = _csv_export_text(rows, bom=bom)
                manifest_payload = _export_manifest(
                    rows,
                    filters=export_filters,
                    export_format=export_format,
                    generated_at=datetime.now(UTC),
                    actor_id=actor_id,
                    audit_id=audit_id,
                )
                csv_bytes = csv_text.encode("utf-8")
                manifest_bytes = bounded_json_bytes(
                    manifest_payload, max_bytes=MAX_REPORT_EXPORT_BYTES,
                    ensure_ascii=False, default=str, indent=2,
                )
                if len(csv_bytes) + len(manifest_bytes) > MAX_REPORT_EXPORT_BYTES:
                    raise ExportSizeLimit()
                archive_output = BoundedBytesIO(MAX_REPORT_EXPORT_BYTES)
                with zipfile.ZipFile(archive_output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                    for member_name, content in (("reports.csv", csv_bytes), ("manifest.json", manifest_bytes)):
                        if profile == "minimum":
                            member = zipfile.ZipInfo(member_name, date_time=(1980, 1, 1, 0, 0, 0))
                            member.compress_type = zipfile.ZIP_DEFLATED
                            archive.writestr(member, content)
                        else:
                            archive.writestr(member_name, content)
                bundle_filename = filename.removesuffix(".csv") + ".bundle.zip"
                response = Response(
                    content=archive_output.getvalue(),
                    media_type="application/zip",
                    headers={
                        "Content-Disposition": f'attachment; filename="{bundle_filename}"',
                        "Cache-Control": "no-store",
                        "X-WalkSafe-Export-Profile": profile,
                        **private_export_headers,
                        "X-WalkSafe-Location-Precision": location_precision,
                        "X-WalkSafe-Export-Bundle": "csv+manifest",
                    },
                )
            elif export_format == "json":
                content: object = rows
                if manifest:
                    content = _export_manifest(
                        rows,
                        filters=export_filters,
                        export_format=export_format,
                        generated_at=datetime.now(UTC),
                        actor_id=actor_id,
                        audit_id=audit_id,
                    )
                response = Response(
                    content=bounded_json_bytes(
                        content, max_bytes=MAX_REPORT_EXPORT_BYTES, ensure_ascii=False, default=str,
                    ),
                    media_type="application/json",
                    headers={
                        "Content-Disposition": content_disposition,
                        "Cache-Control": "no-store",
                        "X-WalkSafe-Export-Profile": profile,
                        **private_export_headers,
                        "X-WalkSafe-Location-Precision": location_precision,
                    },
                )
            elif export_format == "geojson":
                body = (
                    _reports_cluster_geojson(grid_summary)
                    if aggregate == "grid"
                    else _reports_geojson(rows)
                )
                properties = body.setdefault("properties", {})
                if isinstance(properties, dict):
                    if profile != "minimum":
                        properties["audit_id"] = audit_id
                        properties["actor_id"] = actor_id
                    properties["location_precision"] = location_precision
                response = Response(
                    content=bounded_json_bytes(
                        body, max_bytes=MAX_REPORT_EXPORT_BYTES, ensure_ascii=False, default=str,
                    ),
                    media_type="application/geo+json",
                    headers={
                        "Content-Disposition": content_disposition,
                        "Cache-Control": "no-store",
                        "X-WalkSafe-Export-Profile": profile,
                        "X-WalkSafe-Location-Precision": location_precision,
                        **private_export_headers,
                    },
                )
            else:
                csv_text = _csv_export_text(rows, bom=bom)
                response = Response(
                    content=csv_text,
                    media_type="text/csv",
                    headers={
                        "Content-Disposition": content_disposition,
                        "Cache-Control": "no-store",
                        "X-WalkSafe-Export-Profile": profile,
                        **private_export_headers,
                        "X-WalkSafe-Location-Precision": location_precision,
                    },
                )
            # Finish every representation and response byte check before
            # consuming the durable audit ID. An audit failure still
            # withholds the already prepared response.
            _persist_export_audit(
                db,
                audit_id=audit_id,
                requested_audit_id=str(requested_audit_id) if requested_audit_id else None,
                actor_id=actor_id,
                export_format=export_format,
                profile=profile,
                aggregate=aggregate,
                row_count=len(rows),
                location_precision=location_precision,
                rows_sha256=rows_sha256,
                filters=export_filters,
            )
            logger.info(
                "report_export",
                extra={
                    "walksafe_audit": {
                        "audit_id": audit_id,
                        "actor_id": actor_id,
                        "format": export_format,
                        "profile": profile,
                        "aggregate": aggregate,
                        "row_count": len(rows),
                        "rows_sha256": rows_sha256,
                    }
                },
            )
            return response
    except ExportCapacityLimit:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "report_export_capacity_exceeded",
                "message": "Report export capacity is temporarily unavailable.",
            },
        ) from None
    except ExportSizeLimit:
        raise HTTPException(
            status_code=413,
            detail={
                "code": "report_export_too_large",
                "message": "Narrow the export filters before downloading.",
                "max_bytes": MAX_REPORT_EXPORT_BYTES,
            },
        ) from None

