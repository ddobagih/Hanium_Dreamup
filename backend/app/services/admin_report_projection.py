"""Minimum administrator report-list projection and opaque keyset cursors."""

from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import UTC, datetime
import hashlib
import json
import re
import uuid

from sqlalchemy import and_, case, func, or_

from backend.app.models import (
    Report,
    ReportInstitutionDeliveryEvent,
    ReportReviewDecision,
)
from backend.app.schemas import (
    AdminReportCapabilitiesV1,
    AdminReportDeliverySummaryV1,
    AdminReportDetailV1,
    AdminReportReviewSummaryV1,
    AdminReportSummaryV1,
)
from backend.app.services.report_serialization import (
    location_quality,
)
from backend.app.services.report_policy import (
    HIGH_LOCATION_ACCURACY_M,
    MEDIUM_LOCATION_ACCURACY_M,
)


_CURSOR_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,1024}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class AdminReportCursorError(ValueError):
    pass


@dataclass(frozen=True)
class AdminReportFilters:
    report_id: uuid.UUID | None
    status: str | None
    class_name: str | None
    created_from: datetime | None
    created_to: datetime | None


@dataclass(frozen=True)
class AdminReportCursor:
    created_at: datetime
    report_id: uuid.UUID


@dataclass(frozen=True)
class AdminReportListRow:
    id: uuid.UUID
    status: str
    status_version: int
    class_name: str
    confidence: float
    location_quality: str
    duplicate_count: int
    captured_at: datetime
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class AdminReportDetailRow:
    id: uuid.UUID
    status: str
    status_version: int
    content_revision: int
    class_name: str
    confidence: float
    latitude: float | None
    longitude: float | None
    accuracy_m: float | None
    captured_at: datetime
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class AdminReportReviewRow:
    revision: int
    decision: str
    user_visible_reason: str | None
    duplicate_of_report_id: uuid.UUID | None
    location_reviewed: bool
    photo_reviewed: bool
    privacy_reviewed: bool
    decided_at: datetime


@dataclass(frozen=True)
class AdminReportDeliveryRow:
    revision: int
    package_revision: int | None
    status: str
    external_receipt_id: str | None
    evidence_sha256: str | None
    observed_at: datetime
    recorded_at: datetime


def _utc_text(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must include a timezone")
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def admin_report_filter_sha256(filters: AdminReportFilters) -> str:
    payload = {
        "class_name": filters.class_name,
        "created_from": (
            _utc_text(filters.created_from) if filters.created_from is not None else None
        ),
        "created_to": (
            _utc_text(filters.created_to) if filters.created_to is not None else None
        ),
        "report_id": str(filters.report_id) if filters.report_id is not None else None,
        "status": filters.status,
    }
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def encode_admin_report_cursor(
    *,
    created_at: datetime,
    report_id: uuid.UUID,
    filter_sha256: str,
) -> str:
    if _SHA256_PATTERN.fullmatch(filter_sha256) is None:
        raise ValueError("filter_sha256 must be lowercase SHA-256")
    payload = {
        "created_at": _utc_text(created_at),
        "filter_sha256": filter_sha256,
        "id": str(report_id),
        "v": 1,
    }
    canonical = json.dumps(
        payload,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("ascii")
    return base64.urlsafe_b64encode(canonical).rstrip(b"=").decode("ascii")


def _strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise AdminReportCursorError("administrator report cursor is invalid")
        result[key] = value
    return result


def decode_admin_report_cursor(
    value: str,
    *,
    expected_filter_sha256: str,
) -> AdminReportCursor:
    if (
        not isinstance(value, str)
        or _CURSOR_PATTERN.fullmatch(value) is None
        or _SHA256_PATTERN.fullmatch(expected_filter_sha256) is None
    ):
        raise AdminReportCursorError("administrator report cursor is invalid")
    try:
        padded = value + "=" * ((4 - len(value) % 4) % 4)
        decoded = base64.b64decode(
            padded.encode("ascii"),
            altchars=b"-_",
            validate=True,
        )
        payload = json.loads(decoded.decode("utf-8"), object_pairs_hook=_strict_object)
    except (ValueError, UnicodeError, json.JSONDecodeError) as exc:
        raise AdminReportCursorError("administrator report cursor is invalid") from exc
    if not isinstance(payload, dict) or set(payload) != {
        "created_at",
        "filter_sha256",
        "id",
        "v",
    }:
        raise AdminReportCursorError("administrator report cursor is invalid")
    if payload["v"] != 1 or payload["filter_sha256"] != expected_filter_sha256:
        raise AdminReportCursorError(
            "administrator report cursor does not match the current filters"
        )
    if not isinstance(payload["created_at"], str) or not isinstance(payload["id"], str):
        raise AdminReportCursorError("administrator report cursor is invalid")
    try:
        created_at = datetime.fromisoformat(payload["created_at"].replace("Z", "+00:00"))
        report_id = uuid.UUID(payload["id"])
    except ValueError as exc:
        raise AdminReportCursorError("administrator report cursor is invalid") from exc
    if (
        _utc_text(created_at) != payload["created_at"]
        or str(report_id) != payload["id"]
        or encode_admin_report_cursor(
            created_at=created_at,
            report_id=report_id,
            filter_sha256=expected_filter_sha256,
        )
        != value
    ):
        raise AdminReportCursorError("administrator report cursor is invalid")
    return AdminReportCursor(created_at=created_at, report_id=report_id)


def admin_report_cursor_predicate(created_at: datetime, report_id: uuid.UUID):
    return or_(
        Report.created_at < created_at,
        and_(Report.created_at == created_at, Report.id < report_id),
    )


def admin_report_list_columns() -> tuple[object, ...]:
    duplicate_ids = Report.payload["duplicate_report_ids"]
    return (
        Report.id,
        Report.status,
        Report.status_version,
        Report.content_revision,
        Report.class_name,
        Report.confidence,
        case(
            (
                or_(Report.latitude.is_(None), Report.longitude.is_(None)),
                "missing",
            ),
            (
                or_(
                    Report.accuracy_m.is_(None),
                    Report.accuracy_m > MEDIUM_LOCATION_ACCURACY_M,
                ),
                "low",
            ),
            (Report.accuracy_m > HIGH_LOCATION_ACCURACY_M, "medium"),
            else_="high",
        ).label("location_quality"),
        case(
            (
                func.jsonb_typeof(duplicate_ids) == "array",
                func.jsonb_array_length(duplicate_ids),
            ),
            else_=0,
        ).label("duplicate_count"),
        Report.captured_at,
        Report.created_at,
        Report.updated_at,
    )


def coerce_admin_report_list_row(row: object) -> AdminReportListRow:
    return AdminReportListRow(
        id=row.id,
        status=row.status,
        status_version=row.status_version,
        class_name=row.class_name,
        confidence=row.confidence,
        location_quality=row.location_quality,
        duplicate_count=row.duplicate_count,
        captured_at=row.captured_at,
        created_at=row.created_at,
        updated_at=row.updated_at,
    )


def project_admin_report_summary(report: AdminReportListRow) -> AdminReportSummaryV1:
    return AdminReportSummaryV1(
        id=report.id,
        status=report.status,
        status_version=report.status_version,
        class_name=report.class_name,
        confidence=report.confidence,
        location_quality=report.location_quality,
        duplicate_count=report.duplicate_count,
        captured_at=report.captured_at,
        created_at=report.created_at,
        updated_at=report.updated_at,
    )


def admin_report_detail_columns() -> tuple[object, ...]:
    return (
        Report.id,
        Report.status,
        Report.status_version,
        Report.class_name,
        Report.confidence,
        Report.latitude,
        Report.longitude,
        Report.accuracy_m,
        Report.captured_at,
        Report.created_at,
        Report.updated_at,
    )


def coerce_admin_report_detail_row(row: object) -> AdminReportDetailRow:
    return AdminReportDetailRow(**{field: getattr(row, field) for field in AdminReportDetailRow.__dataclass_fields__})


def admin_report_review_columns() -> tuple[object, ...]:
    return tuple(getattr(ReportReviewDecision, field) for field in AdminReportReviewRow.__dataclass_fields__)


def coerce_admin_report_review_row(row: object) -> AdminReportReviewRow:
    return AdminReportReviewRow(**{field: getattr(row, field) for field in AdminReportReviewRow.__dataclass_fields__})


def admin_report_delivery_columns() -> tuple[object, ...]:
    return tuple(getattr(ReportInstitutionDeliveryEvent, field) for field in AdminReportDeliveryRow.__dataclass_fields__)


def coerce_admin_report_delivery_row(row: object) -> AdminReportDeliveryRow:
    return AdminReportDeliveryRow(**{field: getattr(row, field) for field in AdminReportDeliveryRow.__dataclass_fields__})


def project_admin_report_detail(
    report: AdminReportDetailRow | Report,
    review: AdminReportReviewRow | ReportReviewDecision | None,
    delivery: AdminReportDeliveryRow | ReportInstitutionDeliveryEvent | None,
) -> AdminReportDetailV1:
    from backend.app.services.admin_report_workflow import allowed_next_statuses

    report_id = str(report.id)
    return AdminReportDetailV1(
        schema_version="walksafe.admin-report-detail.v1",
        id=report.id,
        status=report.status,
        status_version=getattr(report, "status_version", None) or 1,
        allowed_next_statuses=list(allowed_next_statuses(report.status)),
        class_name=report.class_name,
        confidence=report.confidence,
        location_quality=location_quality(report),
        captured_at=report.captured_at,
        created_at=report.created_at,
        updated_at=report.updated_at,
        current_review=(
            AdminReportReviewSummaryV1(
                revision=review.revision,
                decision=review.decision,
                user_visible_reason=review.user_visible_reason,
                duplicate_of_report_id=review.duplicate_of_report_id,
                location_reviewed=review.location_reviewed,
                photo_reviewed=review.photo_reviewed,
                privacy_reviewed=review.privacy_reviewed,
                decided_at=review.decided_at,
            )
            if review is not None
            else None
        ),
        current_delivery=(
            AdminReportDeliverySummaryV1(
                revision=delivery.revision,
                package_revision=getattr(delivery, "package_revision", None),
                status=delivery.status,
                external_receipt_present=delivery.external_receipt_id is not None,
                evidence_present=delivery.evidence_sha256 is not None,
                observed_at=delivery.observed_at,
                recorded_at=delivery.recorded_at,
            )
            if delivery is not None
            else None
        ),
        capabilities=AdminReportCapabilitiesV1(
            review_decisions_path=f"/reports/{report_id}/review-decisions",
            deliveries_path=f"/reports/{report_id}/deliveries",
            original_access_grants_path=(
                f"/reports/{report_id}/original-access-grants"
            ),
            status_path=f"/admin/reports/{report_id}/status",
            delivery_packages_path=f"/admin/reports/{report_id}/delivery-packages",
        ),
    )
