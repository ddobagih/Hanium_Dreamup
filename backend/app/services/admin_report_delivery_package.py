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

from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import (
    AdminOperationAudit,
    Report,
    ReportDeliveryPackage,
    ReportExportAudit,
    ReportInstitutionDeliveryEvent,
    ReportReviewDecision,
)
from backend.app.services.admin_report_workflow import (
    AdminReportWorkflowError,
    allowed_delivery_statuses_for_package,
)
from backend.app.services.admin_security import (
    AdminSecurityError,
    AdminSessionIdentity,
    _is_postgresql_session,
    _resolve_admin_credential_issuer_key,
)
from backend.app.services.admin_report_integrity import (
    admin_report_integrity_boundary_state,
)
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
    review_revision: int


@dataclass(frozen=True)
class AdminReportDeliveryPackageProof:
    package_revision: int
    content_revision: int
    review_revision: int
    package_schema_version: str
    package_byte_count: int
    package_sha256: str


def _database_sqlstate(exc: BaseException) -> str | None:
    return getattr(getattr(exc, "orig", None), "sqlstate", None)


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


def _latest_approved_review_for_content(
    db: Session,
    *,
    report_id: uuid.UUID,
    content_revision: int,
) -> ReportReviewDecision | None:
    # Both callers hold the report row lock. Review appends take the same lock,
    # so this latest typed row is serialized without granting runtime UPDATE on
    # the append-only decision table merely to use a locking clause here.
    review = db.execute(
        select(ReportReviewDecision)
        .where(ReportReviewDecision.report_id == report_id)
        .order_by(ReportReviewDecision.revision.desc())
        .limit(1)
    ).scalar_one_or_none()
    if review is None:
        return None
    if (
        review.report_id != report_id
        or int(review.content_revision or 0) != content_revision
        or review.decision != "APPROVED"
        or not review.location_reviewed
        or not review.photo_reviewed
        or not review.privacy_reviewed
        or review.duplicate_of_report_id is not None
        or getattr(review, "evidence_grant_id", None) is None
    ):
        return None
    return review


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


def _secured_delivery_package_dml_required(db: Session) -> bool:
    if not _is_postgresql_session(db):
        return False
    boundary_ready = admin_report_integrity_boundary_state(db)
    if boundary_ready is not None:
        if not boundary_ready:
            raise AdminReportWorkflowError(
                "delivery_package_store_unavailable",
                "The administrator report integrity boundary is unavailable.",
                status_code=503,
            )
        return True
    return not bool(
        db.execute(
            text(
                "SELECT pg_catalog.has_table_privilege("
                "current_user, 'public.report_delivery_packages', 'INSERT')"
            )
        ).scalar_one()
    )


def create_admin_report_delivery_package(
    db: Session,
    *,
    report_id: uuid.UUID,
    expected_content_revision: int,
    expected_review_revision: int,
    identity: AdminSessionIdentity,
    correlation_id: uuid.UUID,
    query_sha256: str,
    proof_challenge_id: uuid.UUID | None = None,
    proof_request_body: bytes | None = None,
    reconfirmation_nonce_sha256: str | None = None,
    runtime_totp_secret: str | None = None,
    credential_issuer_key: str | None = None,
) -> CreatedAdminReportDeliveryPackage:
    """Persist one package and export audit; never contact an institution."""

    try:
        secured_dml = _secured_delivery_package_dml_required(db)
        report_query = select(Report).where(Report.id == report_id)
        if not secured_dml:
            report_query = report_query.with_for_update()
        report = db.execute(report_query).scalar_one_or_none()
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
        review = _latest_approved_review_for_content(
            db,
            report_id=report_id,
            content_revision=content.revision,
        )
    except (SQLAlchemyError, ReportContentCorrectionError) as exc:
        db.rollback()
        raise AdminReportWorkflowError(
            "delivery_package_store_unavailable",
            "The delivery package store is temporarily unavailable.",
            status_code=503,
        ) from exc
    if expected_content_revision != content.revision:
        db.rollback()
        raise AdminReportWorkflowError(
            "delivery_package_content_revision_conflict",
            "The report content changed before this package request was applied.",
            status_code=409,
        )
    if review is None:
        db.rollback()
        raise AdminReportWorkflowError(
            "latest_review_approval_required",
            "Package generation requires the latest APPROVED review for the current report content.",
            status_code=409,
        )
    if expected_review_revision != int(review.revision):
        db.rollback()
        raise AdminReportWorkflowError(
            "delivery_package_review_revision_conflict",
            "The latest approved review changed before this package request was applied.",
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
    generated_at = datetime.now(UTC)
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
        created_at=generated_at,
    )
    package_id = uuid.uuid4()
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
        if secured_dml:
            resolved_issuer_key = _resolve_admin_credential_issuer_key(
                db,
                credential_issuer_key,
            )
            stored = db.execute(
                text(
                    "SELECT * FROM public."
                    "walksafe_create_report_delivery_package_v3("
                    "CAST(:package_id AS uuid), CAST(:report_id AS uuid), "
                    "CAST(:expected_content_revision AS bigint), "
                    "CAST(:expected_content_sha256 AS text), "
                    "CAST(:review_decision_id AS uuid), "
                    "CAST(:expected_review_revision AS bigint), "
                    "CAST(:package_revision AS bigint), "
                    "CAST(:supersedes_package_id AS uuid), "
                    "CAST(:export_audit_id AS uuid), "
                    "CAST(:csv_sha256 AS text), "
                    "CAST(:manifest_sha256 AS text), "
                    "CAST(:package_sha256 AS text), "
                    "CAST(:csv_byte_count AS bigint), "
                    "CAST(:manifest_byte_count AS bigint), "
                    "CAST(:package_byte_count AS bigint), "
                    "CAST(:admin_id AS text), CAST(:session_id AS uuid), "
                    "CAST(:device_id AS text), CAST(:correlation_id AS uuid), "
                    "CAST(:generated_at AS timestamptz), "
                    "CAST(:proof_challenge_id AS uuid), "
                    "CAST(:proof_request_body AS bytea), "
                    "CAST(:reconfirmation_nonce_sha256 AS text), "
                    "CAST(:runtime_totp_secret AS text), "
                    "CAST(:credential_issuer_key AS text))"
                ),
                {
                    "package_id": package_id,
                    "report_id": report_id,
                    "expected_content_revision": content.revision,
                    "expected_content_sha256": content.content_sha256,
                    "review_decision_id": review.id,
                    "expected_review_revision": review.revision,
                    "package_revision": revision,
                    "supersedes_package_id": (
                        previous.id if previous is not None else None
                    ),
                    "export_audit_id": export_audit_id,
                    "csv_sha256": built.csv_sha256,
                    "manifest_sha256": built.manifest_sha256,
                    "package_sha256": built.package_sha256,
                    "csv_byte_count": len(built.csv_bytes),
                    "manifest_byte_count": len(built.manifest_bytes),
                    "package_byte_count": len(built.package_bytes),
                    "admin_id": identity.admin_id,
                    "session_id": identity.session_id,
                    "device_id": identity.device_id,
                    "correlation_id": correlation_id,
                    "generated_at": generated_at,
                    "proof_challenge_id": proof_challenge_id,
                    "proof_request_body": proof_request_body,
                    "reconfirmation_nonce_sha256": reconfirmation_nonce_sha256,
                    "runtime_totp_secret": runtime_totp_secret,
                    "credential_issuer_key": resolved_issuer_key,
                },
            ).mappings().one()
            result_status = stored["result_status"]
            if result_status not in {"CREATED", "EXISTING"}:
                error_contract = {
                    "REPORT_NOT_FOUND": (
                        "report_not_found",
                        "Report was not found.",
                        404,
                    ),
                    "CONTENT_CONFLICT": (
                        "delivery_package_content_revision_conflict",
                        "The report content changed before this package request was applied.",
                        409,
                    ),
                    "CONTENT_DIGEST_CONFLICT": (
                        "delivery_package_content_revision_conflict",
                        "The report content changed before this package request was applied.",
                        409,
                    ),
                    "REVIEW_INVALID": (
                        "latest_review_approval_required",
                        "Package generation requires the latest APPROVED review for the current report content.",
                        409,
                    ),
                    "REVIEW_CONFLICT": (
                        "delivery_package_review_revision_conflict",
                        "The latest approved review changed before this package request was applied.",
                        409,
                    ),
                    "REVISION_CONFLICT": (
                        "delivery_package_revision_conflict",
                        "The package revision changed concurrently.",
                        409,
                    ),
                    "AUTH_INVALID": (
                        "admin_device_proof_invalid",
                        "The administrator device proof is not valid for this package.",
                        403,
                    ),
                    "CLAIM_CONFLICT": (
                        "admin_device_proof_invalid",
                        "The administrator device proof is not valid for this package.",
                        403,
                    ),
                }
                code, message, status_code = error_contract.get(
                    result_status,
                    (
                        "delivery_package_store_unavailable",
                        "The delivery package store is temporarily unavailable.",
                        503,
                    ),
                )
                db.rollback()
                raise AdminReportWorkflowError(
                    code,
                    message,
                    status_code=status_code,
                )
            if (
                stored["package_id"] != package_id
                or int(stored["package_revision"]) != revision
            ):
                db.rollback()
                raise AdminReportWorkflowError(
                    "delivery_package_store_unavailable",
                    "The delivery package store is temporarily unavailable.",
                    status_code=503,
                )
            package = db.get(ReportDeliveryPackage, package_id)
            if package is None:
                db.rollback()
                raise AdminReportWorkflowError(
                    "delivery_package_store_unavailable",
                    "The delivery package store is temporarily unavailable.",
                    status_code=503,
                )
        else:
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
    except (AdminSecurityError, SQLAlchemyError) as exc:
        db.rollback()
        if _database_sqlstate(exc) == "42501":
            raise AdminReportWorkflowError(
                "admin_device_proof_invalid",
                "The administrator device proof is not valid for this package.",
                status_code=403,
            ) from exc
        raise AdminReportWorkflowError(
            "delivery_package_store_unavailable",
            "The delivery package store is temporarily unavailable.",
            status_code=503,
        ) from exc
    return CreatedAdminReportDeliveryPackage(
        record=package,
        package_bytes=built.package_bytes,
        review_revision=review.revision,
    )


def get_admin_report_delivery_package_proof(
    db: Session,
    *,
    report_id: uuid.UUID,
    package_revision: int,
) -> AdminReportDeliveryPackageProof:
    try:
        report = db.execute(
            select(Report).where(Report.id == report_id).with_for_update()
        ).scalar_one_or_none()
        latest_event = db.execute(
            select(ReportInstitutionDeliveryEvent)
            .where(ReportInstitutionDeliveryEvent.report_id == report_id)
            .order_by(ReportInstitutionDeliveryEvent.revision.desc())
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
        review = _latest_approved_review_for_content(
            db,
            report_id=report_id,
            content_revision=content.revision,
        )
        package = db.execute(
            select(ReportDeliveryPackage)
            .where(
                ReportDeliveryPackage.report_id == report_id,
                ReportDeliveryPackage.revision == package_revision,
            )
            .limit(1)
        ).scalar_one_or_none()
    except (SQLAlchemyError, ReportContentCorrectionError) as exc:
        db.rollback()
        raise AdminReportWorkflowError(
            "delivery_package_store_unavailable",
            "The delivery package store is temporarily unavailable.",
            status_code=503,
        ) from exc
    if review is None:
        db.rollback()
        raise AdminReportWorkflowError(
            "latest_review_approval_required",
            "Package proof requires the latest APPROVED review for the current report content.",
            status_code=409,
        )
    if (
        package is None
        or int(package.package_version or 0) != 2
        or package.schema_version != DELIVERY_PACKAGE_SCHEMA_VERSION_V2
        or int(package.content_revision or 0) != content.revision
        or package.review_decision_id != review.id
    ):
        db.rollback()
        raise AdminReportWorkflowError(
            "delivery_package_proof_ineligible",
            "The requested package is not eligible for recovery proof.",
            status_code=409,
        )
    allowed_statuses = allowed_delivery_statuses_for_package(
        db,
        report_id=report_id,
        previous=latest_event,
        package=package,
    )
    if not allowed_statuses:
        db.rollback()
        if (
            latest_event is not None
            and latest_event.status == "RESOLVED"
            and latest_event.package_id == package.id
            and latest_event.package_revision == package.revision
        ):
            raise AdminReportWorkflowError(
                "delivery_package_proof_resolved",
                "Resolved delivery workflows cannot be resumed from package proof metadata.",
                status_code=409,
            )
        raise AdminReportWorkflowError(
            "delivery_package_proof_ineligible",
            "The requested package cannot begin the next delivery transition.",
            status_code=409,
        )
    return AdminReportDeliveryPackageProof(
        package_revision=package.revision,
        content_revision=package.content_revision,
        review_revision=review.revision,
        package_schema_version=package.schema_version,
        package_byte_count=package.package_byte_count,
        package_sha256=package.package_sha256,
    )


__all__ = [
    "AdminReportDeliveryPackageProof",
    "BuiltAdminReportDeliveryPackage",
    "CreatedAdminReportDeliveryPackage",
    "DELIVERY_PACKAGE_SCHEMA_VERSION",
    "DELIVERY_PACKAGE_SCHEMA_VERSION_V2",
    "build_admin_report_delivery_package",
    "build_admin_report_delivery_package_v2",
    "create_admin_report_delivery_package",
    "get_admin_report_delivery_package_proof",
]
