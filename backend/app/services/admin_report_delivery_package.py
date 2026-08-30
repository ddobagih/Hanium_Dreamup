"""Deterministic, single-report package generation for manual delivery."""

from __future__ import annotations

import csv
from dataclasses import dataclass
from datetime import UTC, datetime
import hashlib
import io
import json
import uuid
import zipfile

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import (
    AdminOperationAudit,
    Report,
    ReportDeliveryPackage,
    ReportExportAudit,
    ReportReviewDecision,
)
from backend.app.services.admin_report_workflow import AdminReportWorkflowError
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.report_content_corrections import (
    ReportContentCorrectionError,
    ReportContentState,
    current_content_for_locked_report,
)


DELIVERY_PACKAGE_SCHEMA_VERSION = "walksafe.admin-report-delivery-package.v1"
DELIVERY_PACKAGE_SCHEMA_VERSION_V2 = "walksafe.admin-report-delivery-package.v2"
_CSV_NAME = "report.csv"
_MANIFEST_NAME = "manifest.json"
_CSV_FIELDS = (
    "report_id",
    "status",
    "class_name",
    "confidence",
    "latitude",
    "longitude",
    "accuracy_m",
    "captured_at",
    "created_at",
)
_CSV_FIELDS_V2 = _CSV_FIELDS + (
    "content_revision",
    "content_sha256",
    "user_description",
    "category_hint",
)


@dataclass(frozen=True)
class BuiltAdminReportDeliveryPackage:
    csv_bytes: bytes
    manifest_bytes: bytes
    package_bytes: bytes
    csv_sha256: str
    manifest_sha256: str
    package_sha256: str


@dataclass(frozen=True)
class CreatedAdminReportDeliveryPackage:
    record: ReportDeliveryPackage
    package_bytes: bytes


def _utc_text(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("package timestamp must include a timezone")
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def _number_text(value: float | None) -> str:
    return "" if value is None else format(value, ".15g")


def _zip_entry(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3
    info.external_attr = 0o100600 << 16
    return info


def build_admin_report_delivery_package(
    *,
    report: object,
    review_decision_id: uuid.UUID,
    review_revision: int,
    export_audit_id: uuid.UUID,
    package_revision: int,
) -> BuiltAdminReportDeliveryPackage:
    """Build canonical bytes without I/O or external delivery side effects."""

    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=_CSV_FIELDS, lineterminator="\n")
    writer.writeheader()
    writer.writerow(
        {
            "report_id": str(report.id),
            "status": report.status,
            "class_name": report.class_name,
            "confidence": _number_text(report.confidence),
            "latitude": _number_text(report.latitude),
            "longitude": _number_text(report.longitude),
            "accuracy_m": _number_text(report.accuracy_m),
            "captured_at": _utc_text(report.captured_at),
            "created_at": _utc_text(report.created_at),
        }
    )
    csv_bytes = output.getvalue().encode("utf-8")
    csv_sha256 = hashlib.sha256(csv_bytes).hexdigest()
    manifest = {
        "csv_bytes": len(csv_bytes),
        "csv_name": _CSV_NAME,
        "csv_sha256": csv_sha256,
        "export_audit_id": str(export_audit_id),
        "package_revision": package_revision,
        "package_version": 1,
        "report_id": str(report.id),
        "review_decision_id": str(review_decision_id),
        "review_revision": review_revision,
        "row_count": 1,
        "schema_version": DELIVERY_PACKAGE_SCHEMA_VERSION,
    }
    manifest_bytes = json.dumps(
        manifest,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("ascii")
    manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest()
    package_buffer = io.BytesIO()
    with zipfile.ZipFile(package_buffer, mode="w") as archive:
        archive.writestr(_zip_entry(_CSV_NAME), csv_bytes)
        archive.writestr(_zip_entry(_MANIFEST_NAME), manifest_bytes)
    package_bytes = package_buffer.getvalue()
    return BuiltAdminReportDeliveryPackage(
        csv_bytes=csv_bytes,
        manifest_bytes=manifest_bytes,
        package_bytes=package_bytes,
        csv_sha256=csv_sha256,
        manifest_sha256=manifest_sha256,
        package_sha256=hashlib.sha256(package_bytes).hexdigest(),
    )


def build_admin_report_delivery_package_v2(
    *,
    report: object,
    review_decision_id: uuid.UUID,
    review_revision: int,
    export_audit_id: uuid.UUID,
    package_revision: int,
    content: ReportContentState,
    supersedes_package_id: uuid.UUID | None,
) -> BuiltAdminReportDeliveryPackage:
    """Build correction-aware bytes while leaving every v1 fixture unchanged."""

    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=_CSV_FIELDS_V2, lineterminator="\n")
    writer.writeheader()
    writer.writerow(
        {
            "report_id": str(report.id),
            "status": report.status,
            "class_name": report.class_name,
            "confidence": _number_text(report.confidence),
            "latitude": _number_text(report.latitude),
            "longitude": _number_text(report.longitude),
            "accuracy_m": _number_text(report.accuracy_m),
            "captured_at": _utc_text(report.captured_at),
            "created_at": _utc_text(report.created_at),
            "content_revision": content.revision,
            "content_sha256": content.content_sha256,
            "user_description": content.user_description or "",
            "category_hint": content.category_hint or "",
        }
    )
    csv_bytes = output.getvalue().encode("utf-8")
    csv_sha256 = hashlib.sha256(csv_bytes).hexdigest()
    manifest = {
        "category_hint": content.category_hint,
        "content_revision": content.revision,
        "content_sha256": content.content_sha256,
        "csv_bytes": len(csv_bytes),
        "csv_name": _CSV_NAME,
        "csv_sha256": csv_sha256,
        "export_audit_id": str(export_audit_id),
        "package_revision": package_revision,
        "package_version": 2,
        "report_id": str(report.id),
        "review_decision_id": str(review_decision_id),
        "review_revision": review_revision,
        "row_count": 1,
        "schema_version": DELIVERY_PACKAGE_SCHEMA_VERSION_V2,
        "supersedes_package_id": (
            str(supersedes_package_id)
            if supersedes_package_id is not None
            else None
        ),
        "user_description": content.user_description,
    }
    manifest_bytes = json.dumps(
        manifest,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("ascii")
    manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest()
    package_buffer = io.BytesIO()
    with zipfile.ZipFile(package_buffer, mode="w") as archive:
        archive.writestr(_zip_entry(_CSV_NAME), csv_bytes)
        archive.writestr(_zip_entry(_MANIFEST_NAME), manifest_bytes)
    package_bytes = package_buffer.getvalue()
    return BuiltAdminReportDeliveryPackage(
        csv_bytes=csv_bytes,
        manifest_bytes=manifest_bytes,
        package_bytes=package_bytes,
        csv_sha256=csv_sha256,
        manifest_sha256=manifest_sha256,
        package_sha256=hashlib.sha256(package_bytes).hexdigest(),
    )


def create_admin_report_delivery_package(
    db: Session,
    *,
    report_id: uuid.UUID,
    identity: AdminSessionIdentity,
    correlation_id: uuid.UUID,
    query_sha256: str,
) -> CreatedAdminReportDeliveryPackage:
    """Persist one package and export audit; never contact an institution."""

    try:
        report = db.execute(
            select(Report).where(Report.id == report_id).with_for_update()
        ).scalar_one_or_none()
        review = db.execute(
            select(ReportReviewDecision)
            .where(ReportReviewDecision.report_id == report_id)
            .order_by(ReportReviewDecision.revision.desc())
            .limit(1)
        ).scalar_one_or_none()
        previous = db.execute(
            select(ReportDeliveryPackage)
            .where(ReportDeliveryPackage.report_id == report_id)
            .order_by(ReportDeliveryPackage.revision.desc())
            .limit(1)
        ).scalar_one_or_none()
    except SQLAlchemyError as exc:
        db.rollback()
        raise AdminReportWorkflowError(
            "delivery_package_store_unavailable",
            "The delivery package store is temporarily unavailable.",
            status_code=503,
        ) from exc
    if report is None:
        db.rollback()
        raise AdminReportWorkflowError(
            "report_not_found", "Report was not found.", status_code=404
        )
    try:
        content = current_content_for_locked_report(db, report)
    except (SQLAlchemyError, ReportContentCorrectionError) as exc:
        db.rollback()
        raise AdminReportWorkflowError(
            "delivery_package_store_unavailable",
            "The delivery package store is temporarily unavailable.",
            status_code=503,
        ) from exc
    if (
        review is None
        or review.decision != "APPROVED"
        or not review.location_reviewed
        or not review.photo_reviewed
        or not review.privacy_reviewed
        or review.duplicate_of_report_id is not None
        or int(review.content_revision or 0) != content.revision
    ):
        db.rollback()
        raise AdminReportWorkflowError(
            "latest_review_approval_required",
            "Package generation requires the latest typed decision to be fully reviewed and APPROVED.",
            status_code=409,
        )

    revision = 1 if previous is None else previous.revision + 1
    export_audit_id = uuid.uuid4()
    built = build_admin_report_delivery_package_v2(
        report=report,
        review_decision_id=review.id,
        review_revision=review.revision,
        export_audit_id=export_audit_id,
        package_revision=revision,
        content=content,
        supersedes_package_id=(previous.id if previous is not None else None),
    )
    export_audit = ReportExportAudit(
        audit_id=export_audit_id,
        requested_audit_id=None,
        actor_id=identity.admin_id,
        export_format="csv",
        profile="agency",
        aggregate=None,
        row_count=1,
        location_precision="exact-single-report",
        rows_sha256=built.csv_sha256,
        filters={
            "report_id": str(report_id),
            "review_decision_id": str(review.id),
            "schema_version": DELIVERY_PACKAGE_SCHEMA_VERSION_V2,
            "content_revision": content.revision,
            "content_sha256": content.content_sha256,
        },
    )
    package_id = uuid.uuid4()
    generated_at = datetime.now(UTC)
    package = ReportDeliveryPackage(
        id=package_id,
        report_id=report_id,
        review_decision_id=review.id,
        revision=revision,
        content_revision=content.revision,
        content_sha256=content.content_sha256,
        supersedes_package_id=(previous.id if previous is not None else None),
        export_audit_id=export_audit_id,
        schema_version=DELIVERY_PACKAGE_SCHEMA_VERSION_V2,
        package_version=2,
        csv_sha256=built.csv_sha256,
        manifest_sha256=built.manifest_sha256,
        package_sha256=built.package_sha256,
        csv_byte_count=len(built.csv_bytes),
        manifest_byte_count=len(built.manifest_bytes),
        package_byte_count=len(built.package_bytes),
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=correlation_id,
        generated_at=generated_at,
    )
    operation_audit = AdminOperationAudit(
        actor_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=correlation_id,
        operation="admin.report.delivery_package.create",
        outcome="SUCCEEDED",
        resource_type="delivery_package",
        resource_id=str(package_id),
        query_sha256=query_sha256,
        result_count=1,
        error_code=None,
        created_at=generated_at,
    )
    try:
        db.add(export_audit)
        db.flush()
        db.add(package)
        db.add(operation_audit)
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise AdminReportWorkflowError(
            "delivery_package_revision_conflict",
            "The package revision changed concurrently.",
            status_code=409,
        ) from exc
    except SQLAlchemyError as exc:
        db.rollback()
        raise AdminReportWorkflowError(
            "delivery_package_store_unavailable",
            "The delivery package store is temporarily unavailable.",
            status_code=503,
        ) from exc
    return CreatedAdminReportDeliveryPackage(
        record=package,
        package_bytes=built.package_bytes,
    )


__all__ = [
    "BuiltAdminReportDeliveryPackage",
    "CreatedAdminReportDeliveryPackage",
    "DELIVERY_PACKAGE_SCHEMA_VERSION",
    "DELIVERY_PACKAGE_SCHEMA_VERSION_V2",
    "build_admin_report_delivery_package",
    "build_admin_report_delivery_package_v2",
    "create_admin_report_delivery_package",
]
