"""Report ingestion, review, query, summary, and internal export routes.

Legacy v1 and v2 reports share one table, but v2 metadata is allowlisted and
restricted to reportable tactile damage. Export endpoints support internal
operations only; this module does not submit reports to a public agency.
"""

from __future__ import annotations

import csv
from contextlib import contextmanager
import errno
import fcntl
import hashlib
import io
import json
import logging
import os
import re
import stat
import uuid
import zipfile
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Callable, Iterable, List, Literal, NoReturn, Optional

from anyio import to_thread
from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Query, Request, Response, UploadFile, status
from geoalchemy2.elements import WKTElement
from pydantic import AwareDatetime, ValidationError
from sqlalchemy import func, not_, or_, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from backend.app.config import Settings
from backend.app.database import get_db
from backend.app.field_test_security import ADMIN_RECONFIRM_NONCE_HEADER_NAME
from backend.app.models import Report, ReportExportAudit, ReportImageObject, ReportStatusAudit
from backend.app.schemas import (
    ClassName,
    DetectorSource,
    DuplicateCheckResponse,
    ReportInstitutionDeliveryRequest,
    ReportInstitutionDeliveryResponse,
    ReportMetadata,
    ReportOriginalAccessGrantRequest,
    ReportOriginalAccessGrantResponse,
    ReportDemoFilter,
    ReportResponse,
    ReportReviewDecisionRequest,
    ReportReviewDecisionResponse,
    ReportStatus,
    ReportStatusUpdate,
    ReportV2Metadata,
)
from backend.app.services.duplicates import (
    DUPLICATE_RADIUS_M,
    DUPLICATE_WINDOW_MINUTES,
    duplicate_candidate_statement,
    find_active_auto_report_cooldown,
    find_duplicate_candidates,
    find_duplicate_candidates_v2,
    lock_auto_report_cooldown,
    lock_duplicate_candidate_window,
)
from backend.app.services.report_policy import ensure_report_v2_allowed, report_v2_source
from backend.app.services.report_policy import HIGH_LOCATION_ACCURACY_M, LOW_CONFIDENCE_THRESHOLD
from backend.app.services.report_read_audit import persist_report_read_audit, resolve_read_audit_identity
from backend.app.services.report_serialization import location_quality, report_to_response
from backend.app.services.report_storage import (
    complete_report_image_write,
    lock_report_storage_write_transaction,
    stage_report_image,
)
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    assert_privacy_hmac_key_bound,
    assert_report_ingest_active,
    bind_or_verify_privacy_hmac_key,
    lock_report_ingest_transaction,
    privacy_subject_hmac,
)
from backend.app.services.report_image_crypto import encrypt_report_image
from backend.app.services.report_image_keys import ReportImageKeyError, ReportImageKeyManager
from backend.app.services.report_original_access import (
    ReportOriginalAccessError,
    issue_report_original_access_grant,
)
from backend.app.services.admin_security import (
    AdminSessionIdentity,
    AdminSecurityError,
    authorize_admin_protected_work,
    record_admin_security_denial,
    record_admin_security_failure,
)
from backend.app.services.admin_device_proof import VerifiedAdminDeviceProof
from backend.app.services.admin_report_workflow import (
    AdminReportWorkflowError,
    append_report_institution_delivery_event,
    append_report_review_decision,
    list_report_institution_delivery_events,
    list_report_review_decisions,
)
from backend.app.uploads import image_suffix, read_image_upload


_ADMIN_RECONFIRMATION_OPENAPI_PARAMETER = {
    "name": ADMIN_RECONFIRM_NONCE_HEADER_NAME,
    "in": "header",
    "required": True,
    "description": (
        "One-time canonical unpadded Base64url encoding of exactly 16 random "
        "bytes, bound by administrator reauthentication to this operation."
    ),
    "schema": {
        "type": "string",
        "minLength": 22,
        "maxLength": 22,
        "pattern": "^[A-Za-z0-9_-]{22}$",
    },
}


def _reauthorize_admin_high_risk_in_transaction(
    request: Request,
    db: Session,
    settings: Settings,
    action: str,
) -> Any:
    """Hold the administrator control lock through the actual DB operation."""

    if not settings.admin_security_enabled:
        return
    authorization = request.headers.get("authorization", "").strip()
    scheme, separator, raw_token = authorization.partition(" ")
    if not separator or scheme.lower() != "bearer":
        raw_token = ""
    try:
        security_context = {
            "method": request.method,
            "path": request.url.path,
            "runtime_totp_secret": settings.admin_totp_secret,
            "device_id": request.headers.get("x-walksafe-device-id"),
            "app_kind": request.headers.get("x-walksafe-app-kind"),
            "role": request.headers.get("x-walksafe-role"),
            "audience": request.headers.get("x-walksafe-audience"),
        }
        return authorize_admin_protected_work(
            db,
            raw_token.strip(),
            action,
            **security_context,
        )
    except AdminSecurityError as exc:
        record_admin_security_denial(
            action=action,
            reason=exc.code,
            method=request.method,
            path=request.url.path,
            device_id=request.headers.get("x-walksafe-device-id"),
        )
        headers = (
            {"Retry-After": str(exc.retry_after)}
            if exc.retry_after is not None
            else None
        )
        raise HTTPException(
            status_code=exc.status_code,
            detail={"code": exc.code, "message": exc.message},
            headers=headers,
        ) from exc


def _require_admin_report_workflow_context(
    request: Request,
    *,
    expected_action: str | None,
    expected_read_purpose: str | None,
) -> tuple[AdminSessionIdentity, VerifiedAdminDeviceProof]:
    identity = getattr(request.state, "admin_security_identity", None)
    if not isinstance(identity, AdminSessionIdentity):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={
                "code": "admin_session_required",
                "message": "An authenticated administrator session is required.",
            },
        )

    proof = getattr(request.state, "admin_device_proof", None)
    if not isinstance(proof, VerifiedAdminDeviceProof):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "admin_device_proof_required",
                "message": "A verified administrator device proof is required.",
            },
        )

    if (
        proof.admin_id != identity.admin_id
        or proof.device_id != identity.device_id
        or proof.session_id != identity.session_id
        or proof.action != expected_action
        or proof.purpose != "ACTION"
        or proof.read_purpose != expected_read_purpose
        or proof.method != request.method.upper()
        or proof.path != request.url.path
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "admin_device_proof_invalid",
                "message": "The administrator device proof does not match this request.",
            },
        )
    return identity, proof


def _raise_admin_report_workflow_error(
    exc: AdminReportWorkflowError,
    *,
    request: Request,
    identity: AdminSessionIdentity,
    proof: VerifiedAdminDeviceProof,
    audit_action: str,
) -> NoReturn:
    try:
        record_admin_security_failure(
            action=audit_action,
            reason=exc.code,
            outcome="ERROR" if exc.status_code >= 500 else "DENIED",
            method=request.method,
            path=request.url.path,
            admin_id=identity.admin_id,
            session_id=identity.session_id,
            device_id=identity.device_id,
            correlation_id=proof.correlation_id,
        )
    except AdminSecurityError as audit_exc:
        headers = (
            {"Retry-After": str(audit_exc.retry_after)}
            if audit_exc.retry_after is not None
            else None
        )
        raise HTTPException(
            status_code=audit_exc.status_code,
            detail={"code": audit_exc.code, "message": audit_exc.message},
            headers=headers,
        ) from audit_exc
    raise HTTPException(
        status_code=exc.status_code,
        detail={"code": exc.code, "message": exc.message},
    ) from exc


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
MAX_REPORT_SUMMARY_ROWS = 10_000
AUTO_REPORT_MAX_AGE = timedelta(minutes=5)
AUTO_REPORT_MAX_FUTURE_SKEW = timedelta(seconds=30)

# Android metadata is client-supplied evidence, not an authenticated identity.
# Keep this allowlist explicit so arbitrary device fields are not persisted.
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
    "approach_state",
    "threshold_used",
    "captured_at",
    "gps",
    "heading",
    "trigger",
    "auto_reported",
    "review_flags",
    "fake_source",
    "trace_id",
    "apk_sha256",
    "source_commit",
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

ACTOR_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
UNKNOWN_ACTOR_ID = "unknown"
logger = logging.getLogger(__name__)

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
    performance_excluded: Optional[bool],
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
    if performance_excluded is not None:
        statement = statement.where(Report.payload.contains({"performance_excluded": performance_excluded}))
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


def _resolved_actor_id(header_value: str | None, *, required: bool = False) -> str:
    if header_value is None or not header_value:
        if required:
            raise HTTPException(
                status_code=422,
                detail={"code": "missing_actor_id", "message": "x-walksafe-actor-id is required"},
            )
        return UNKNOWN_ACTOR_ID
    if ACTOR_ID_PATTERN.fullmatch(header_value) is None:
        raise HTTPException(
            status_code=422,
            detail={"code": "invalid_actor_id", "message": "x-walksafe-actor-id has an invalid format"},
        )
    return header_value


def _resolved_account_generation(
    header_value: str | None,
    *,
    actor_id: str,
    required: bool,
) -> int | None:
    if actor_id == UNKNOWN_ACTOR_ID:
        return None
    if header_value is None or not header_value:
        if required:
            raise HTTPException(
                status_code=422,
                detail={
                    "code": "account_generation_invalid",
                    "message": "x-walksafe-account-generation is required",
                },
            )
        return 1
    if re.fullmatch(r"[1-9][0-9]{0,18}", header_value) is None:
        raise HTTPException(
            status_code=422,
            detail={"code": "account_generation_invalid"},
        )
    generation = int(header_value)
    if generation > 9_223_372_036_854_775_807:
        raise HTTPException(
            status_code=422,
            detail={"code": "account_generation_invalid"},
        )
    return generation


def _privacy_report_binding(
    *,
    db: Session,
    actor_id: str,
    account_generation: int | None,
    settings: Settings,
) -> tuple[str | None, int | None]:
    if actor_id == UNKNOWN_ACTOR_ID or account_generation is None:
        return None, None
    try:
        bind_or_verify_privacy_hmac_key(
            db,
            secret=settings.privacy_hmac_secret,
            key_version=settings.privacy_hmac_key_version,
        )
        return (
            privacy_subject_hmac(
                actor_id,
                account_generation,
                settings.privacy_hmac_secret,
            ),
            account_generation,
        )
    except PrivacyLifecycleError as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail={"code": exc.code, "message": exc.message},
        ) from exc


def _assert_report_precommit_active(
    db: Session,
    *,
    privacy_subject: str,
    account_generation: int,
    settings: Settings,
) -> None:
    assert_privacy_hmac_key_bound(
        db,
        secret=settings.privacy_hmac_secret,
        key_version=settings.privacy_hmac_key_version,
    )
    assert_report_ingest_active(db, privacy_subject, account_generation)


def _enforce_metadata_size(raw_metadata: str, settings: Settings) -> None:
    if len(raw_metadata.encode("utf-8")) > settings.max_report_metadata_bytes:
        raise HTTPException(
            status_code=413,
            detail={
                "code": "report_metadata_too_large",
                "max_bytes": settings.max_report_metadata_bytes,
            },
        )


def _sanitized_v2_payload(parsed: ReportV2Metadata) -> dict[str, Any]:
    validated = parsed.model_dump(mode="json", exclude_none=True)
    payload = {key: validated[key] for key in REPORT_V2_METADATA_ALLOWLIST if key in validated}
    # Client flags must never impersonate server review decisions. Declaring a
    # fake source is safe because it can only exclude evidence from evaluation.
    payload["review_flags"] = [flag for flag in parsed.review_flags if flag == "fake_source"]
    return payload


def _coordinate_gate_exclusion_reason(payload: dict[str, Any]) -> str | None:
    # A failed or missing coordinate gate excludes performance evidence. It does
    # not by itself turn a field candidate into fake/demo data.
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


def _enrich_legacy_payload(
    payload: dict[str, Any],
    *,
    source: str,
    content: bytes,
    actor_id: str,
) -> dict[str, Any]:
    enriched = dict(payload)
    fake_payload = source == "fake"
    enriched["data_origin"] = "demo" if fake_payload else "field_candidate"
    enriched["runtime_mode"] = source
    enriched["trigger"] = "manual_legacy"
    enriched["auto_reported"] = False
    enriched["coordinate_gate_status"] = "legacy_v1_unverified"
    enriched["performance_excluded"] = True
    enriched["performance_exclusion_reason"] = "legacy_v1_unverified_provenance"
    enriched["provenance_status"] = "client_asserted"
    enriched["actor_provenance"] = "gateway_forwarded" if actor_id != UNKNOWN_ACTOR_ID else "unavailable"
    enriched["received_at"] = datetime.now(UTC).isoformat().replace("+00:00", "Z")
    enriched["payload_sha256"] = _json_sha256(payload)
    enriched["image_sha256"] = _bytes_sha256(content)
    return enriched


def _enrich_v2_payload(
    payload: dict[str, Any],
    *,
    source: str,
    content: bytes,
    actor_id: str,
) -> dict[str, Any]:
    enriched = dict(payload)
    fake_payload = _is_fake_payload(enriched, source)
    coordinate_gate_exclusion_reason = _coordinate_gate_exclusion_reason(enriched)
    exclusion_reasons: list[str] = []
    # Detection/report metadata is asserted by the client and is never valid
    # model-performance evidence without a separate server attestation.
    exclusion_reasons.append("client_asserted_detection")
    if fake_payload:
        exclusion_reasons.append("fake_demo_source")
    if coordinate_gate_exclusion_reason:
        exclusion_reasons.append(coordinate_gate_exclusion_reason)
    enriched["data_origin"] = "demo" if fake_payload else "field_candidate"
    enriched["runtime_mode"] = source
    enriched["provenance_status"] = "client_asserted"
    enriched["actor_provenance"] = "gateway_forwarded" if actor_id != UNKNOWN_ACTOR_ID else "unavailable"
    enriched["received_at"] = datetime.now(UTC).isoformat().replace("+00:00", "Z")
    enriched["payload_sha256"] = _json_sha256(payload)
    enriched["image_sha256"] = _bytes_sha256(content)
    enriched["performance_excluded"] = bool(exclusion_reasons)
    if exclusion_reasons:
        enriched["performance_exclusion_reason"] = ";".join(exclusion_reasons)
    else:
        enriched.setdefault("performance_exclusion_reason", "")
    return enriched


def _enforce_secured_auto_report_gate(
    parsed: ReportV2Metadata,
    settings: Settings,
    *,
    actor_id: str,
) -> None:
    if not settings.field_test_security_enabled or not parsed.auto_reported:
        return

    failures: list[str] = []
    if not _is_named_human_actor(actor_id):
        failures.append("named_actor_required")
    if parsed.confidence < LOW_CONFIDENCE_THRESHOLD:
        failures.append(f"confidence<{LOW_CONFIDENCE_THRESHOLD:.2f}")
    if parsed.gps is None:
        failures.append("gps_missing")
    elif parsed.gps.accuracy_m is None or parsed.gps.accuracy_m > HIGH_LOCATION_ACCURACY_M:
        failures.append(f"gps_accuracy>{HIGH_LOCATION_ACCURACY_M:.0f}m")
    if parsed.coordinate_gate_status != "pass":
        failures.append("coordinate_gate_status!=pass")

    captured_at = parsed.captured_at
    if captured_at.tzinfo is None:
        captured_at = captured_at.replace(tzinfo=UTC)
    now = datetime.now(UTC)
    if captured_at < now - AUTO_REPORT_MAX_AGE:
        failures.append("captured_at_stale")
    elif captured_at > now + AUTO_REPORT_MAX_FUTURE_SKEW:
        failures.append("captured_at_in_future")

    if failures:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "auto_report_gate_failed",
                "reasons": failures,
            },
        )


def _enforce_secured_android_provenance(parsed: ReportV2Metadata, settings: Settings) -> None:
    if not settings.field_test_security_enabled or parsed.source != "android":
        return
    source_commit = (parsed.source_commit or "").lower()
    expected_commit = settings.walksafe_source_commit.lower()
    if not expected_commit or source_commit != expected_commit:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "android_provenance_gate_failed",
                "message": "Android reports must identify the same full source commit as the secured backend release.",
            },
        )


def _agency_review_eligible(report: Report, payload: dict[str, Any], actor_id: str) -> bool:
    return (
        _is_named_human_actor(actor_id)
        and report.class_name == "damaged_tactile_block"
        and location_quality(report) == "high"
        and not _is_fake_payload(payload, report.source)
    )


def _is_named_human_actor(actor_id: str) -> bool:
    normalized = actor_id.strip().lower()
    return (
        ACTOR_ID_PATTERN.fullmatch(actor_id.strip()) is not None
        and normalized not in {"", UNKNOWN_ACTOR_ID, "system", "anonymous"}
        and not normalized.endswith("-shared")
    )


def _agency_review_was_named(payload: dict[str, Any]) -> bool:
    reviewer = payload.get("agency_reviewed_by_actor_id")
    return (
        payload.get("agency_review_verified") is True
        and isinstance(reviewer, str)
        and _is_named_human_actor(reviewer)
    )


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


def _field_creation_response(
    report: Report,
    duplicate_report_ids: list[str] | None = None,
) -> ReportResponse:
    internal = report_to_response(report, duplicate_report_ids)
    sanitized_metadata = dict(internal.metadata)
    sanitized_metadata.pop("duplicate_report_ids", None)
    return internal.model_copy(
        update={
            "metadata": sanitized_metadata,
            "duplicate_report_ids": [],
        }
    )


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
        "review_flags": _csv_safe(",".join(response.review_flags)),
        "duplicate_report_ids": _csv_safe(
            ",".join(str(value) for value in metadata.get("duplicate_report_ids", []) if value)
        ),
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
    return f"walksafe-reports-{_demo_filename_part(demo_filter)}{profile_part}{aggregate_part}.{extension}"


def _export_manifest(
    rows: list[dict[str, object]],
    *,
    filters: dict[str, object],
    generated_at: datetime,
    actor_id: str,
    audit_id: str,
) -> dict[str, object]:
    rows_sha256 = _export_rows_sha256(rows)
    return {
        "schema_version": "walksafe.reports.export_manifest.v1",
        "generated_at": generated_at.isoformat().replace("+00:00", "Z"),
        "audit_id": audit_id,
        "actor_id": actor_id,
        "filters": filters,
        "count": len(rows),
        "rows_sha256": rows_sha256,
        "rows": rows,
    }


def _export_rows_sha256(rows: list[dict[str, object]]) -> str:
    rows_json = json.dumps(rows, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(rows_json.encode("utf-8")).hexdigest()


def _csv_export_text(rows: list[dict[str, object]], *, bom: bool) -> str:
    output = io.StringIO()
    writer = csv.DictWriter(output, fieldnames=REPORT_EXPORT_FIELDS)
    writer.writeheader()
    writer.writerows(rows)
    csv_text = output.getvalue()
    return f"\ufeff{csv_text}" if bom else csv_text


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


def _bounded_summary_reports(reports: Iterable[Report]) -> list[Report]:
    bounded: list[Report] = []
    for report in reports:
        if len(bounded) >= MAX_REPORT_SUMMARY_ROWS:
            raise HTTPException(
                status_code=413,
                detail={
                    "code": "report_summary_too_large",
                    "message": "narrow the summary filters before requesting aggregate data",
                    "max_rows": MAX_REPORT_SUMMARY_ROWS,
                },
            )
        bounded.append(report)
    return bounded


def _commit_new_report_with_image(
    db: Session,
    report: Report,
    *,
    destination: Path,
    content: bytes,
    content_type: str,
    key_manager: ReportImageKeyManager,
    maintenance_lock_path: Path | None = None,
    maintenance_lock_group_gid: int | None = None,
    precommit_check: Callable[[], None] | None = None,
) -> None:
    with _shared_report_write_lock(
        maintenance_lock_path,
        expected_group_gid=maintenance_lock_group_gid,
    ):
        lock_report_storage_write_transaction(db)
        try:
            key_manager.synchronize(db)
            active_key = key_manager.encryption_slot()
            assert active_key.material is not None
            encrypted = encrypt_report_image(
                content,
                report_id=report.id,
                content_type=content_type,
                key_id=active_key.key_id,
                key=active_key.material,
            )
            pending = stage_report_image(destination, encrypted)
            db.add(report)
            db.add(
                ReportImageObject(
                    report_id=report.id,
                    storage_name=destination.name,
                    envelope_version=1,
                    algorithm="AES-256-GCM",
                    aad_version=1,
                    key_id=encrypted.key_id,
                    nonce=encrypted.nonce,
                    plaintext_sha256=encrypted.plaintext_sha256,
                    plaintext_size=encrypted.plaintext_length,
                    envelope_sha256=encrypted.envelope_sha256,
                    envelope_size=len(encrypted.envelope),
                    content_type=content_type,
                )
            )
            if precommit_check is not None:
                precommit_check()
            db.commit()
        except ReportImageKeyError as exc:
            db.rollback()
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "report_image_encryption_unavailable",
                    "message": "The report was not stored because image encryption is unavailable.",
                },
            ) from exc
        except PrivacyLifecycleError as exc:
            db.rollback()
            raise HTTPException(
                status_code=exc.status_code,
                detail={"code": exc.code, "message": exc.message},
            ) from exc
        except BaseException:
            db.rollback()
            raise
        db.refresh(report)
        complete_report_image_write(pending)


def _descriptor_acl_is_absent(descriptor: int) -> bool:
    try:
        return not any(
            "acl" in os.fsdecode(name).casefold()
            for name in os.listxattr(descriptor)
        )
    except (AttributeError, OSError):
        return False


def _trusted_maintenance_lock_parent(parent: Path, parent_descriptor: int) -> bool:
    if os.geteuid() == 0 or not parent.is_absolute():
        return False
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | os.O_DIRECTORY
        | os.O_NOFOLLOW
    )
    authority_paths = [Path("/")]
    authority_descriptors: list[int] = []
    try:
        if parent.resolve(strict=True) != parent:
            return False
        authority_descriptors.append(os.open("/", flags))
        for component in parent.parent.relative_to("/").parts:
            authority_descriptors.append(
                os.open(component, flags, dir_fd=authority_descriptors[-1])
            )
            authority_paths.append(authority_paths[-1] / component)
        for index, (authority_path, descriptor) in enumerate(
            zip(authority_paths, authority_descriptors, strict=True)
        ):
            opened_authority = os.fstat(descriptor)
            path_authority = authority_path.stat(follow_symlinks=False)
            if (
                not stat.S_ISDIR(opened_authority.st_mode)
                or opened_authority.st_uid != 0
                or stat.S_IMODE(opened_authority.st_mode) & 0o022
                or os.access(
                    ".",
                    os.W_OK,
                    dir_fd=descriptor,
                    effective_ids=True,
                    follow_symlinks=False,
                )
                or (path_authority.st_dev, path_authority.st_ino)
                != (opened_authority.st_dev, opened_authority.st_ino)
            ):
                return False
            if index:
                anchored_authority = os.stat(
                    authority_path.name,
                    dir_fd=authority_descriptors[index - 1],
                    follow_symlinks=False,
                )
                if (anchored_authority.st_dev, anchored_authority.st_ino) != (
                    opened_authority.st_dev,
                    opened_authority.st_ino,
                ):
                    return False
        anchored_parent = os.stat(
            parent.name,
            dir_fd=authority_descriptors[-1],
            follow_symlinks=False,
        )
        opened_parent = os.fstat(parent_descriptor)
        path_parent = parent.stat(follow_symlinks=False)
        return (
            (path_parent.st_dev, path_parent.st_ino)
            == (opened_parent.st_dev, opened_parent.st_ino)
            == (anchored_parent.st_dev, anchored_parent.st_ino)
        )
    except (OSError, RuntimeError):
        return False
    finally:
        for descriptor in reversed(authority_descriptors):
            os.close(descriptor)


@contextmanager
def _shared_report_write_lock(
    path: Path | None,
    *,
    expected_group_gid: int | None = None,
):
    if path is None:
        yield
        return

    parent = path.parent
    parent_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    try:
        parent_descriptor = os.open(parent, parent_flags)
    except OSError as exc:
        raise HTTPException(
            status_code=503,
            detail={"code": "maintenance_lock_unavailable"},
        ) from exc
    try:
        parent_stat = os.fstat(parent_descriptor)
        path_parent_stat = parent.stat(follow_symlinks=False)
        expected_parent_owner = 0 if expected_group_gid is not None else os.geteuid()
        expected_parent_mode = 0o750 if expected_group_gid is not None else 0o700
        if (
            not stat.S_ISDIR(parent_stat.st_mode)
            or parent_stat.st_uid != expected_parent_owner
            or (
                expected_group_gid is not None
                and parent_stat.st_gid != expected_group_gid
            )
            or stat.S_IMODE(parent_stat.st_mode) != expected_parent_mode
            or not _descriptor_acl_is_absent(parent_descriptor)
            or (
                expected_group_gid is not None
                and os.access(
                    ".",
                    os.W_OK,
                    dir_fd=parent_descriptor,
                    effective_ids=True,
                    follow_symlinks=False,
                )
            )
            or (path_parent_stat.st_dev, path_parent_stat.st_ino)
            != (parent_stat.st_dev, parent_stat.st_ino)
            or not _trusted_maintenance_lock_parent(parent, parent_descriptor)
        ):
            raise HTTPException(
                status_code=503,
                detail={"code": "maintenance_lock_unavailable"},
            )
        parent_state = (
            parent_stat.st_dev,
            parent_stat.st_ino,
            parent_stat.st_mode,
            parent_stat.st_uid,
            parent_stat.st_gid,
            parent_stat.st_nlink,
            parent_stat.st_ctime_ns,
        )
        try:
            flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
            try:
                descriptor = os.open(path.name, flags, dir_fd=parent_descriptor)
            except OSError as exc:
                raise HTTPException(
                    status_code=503,
                    detail={"code": "maintenance_lock_unavailable"},
                ) from exc
            try:
                expected_file_owner = 0 if expected_group_gid is not None else os.geteuid()
                expected_file_mode = 0o440 if expected_group_gid is not None else 0o600

                def verified_file_state() -> tuple[int, ...]:
                    try:
                        current_parent = os.fstat(parent_descriptor)
                        current_path_parent = parent.stat(follow_symlinks=False)
                        current_file = os.fstat(descriptor)
                        current_entry = os.stat(
                            path.name,
                            dir_fd=parent_descriptor,
                            follow_symlinks=False,
                        )
                        current_path_file = path.stat(follow_symlinks=False)
                    except OSError as exc:
                        raise HTTPException(
                            status_code=503,
                            detail={"code": "maintenance_lock_unavailable"},
                        ) from exc
                    current_parent_state = (
                        current_parent.st_dev,
                        current_parent.st_ino,
                        current_parent.st_mode,
                        current_parent.st_uid,
                        current_parent.st_gid,
                        current_parent.st_nlink,
                        current_parent.st_ctime_ns,
                    )
                    current_file_state = (
                        current_file.st_dev,
                        current_file.st_ino,
                        current_file.st_mode,
                        current_file.st_uid,
                        current_file.st_gid,
                        current_file.st_nlink,
                        current_file.st_ctime_ns,
                    )
                    if (
                        current_parent_state != parent_state
                        or (current_path_parent.st_dev, current_path_parent.st_ino)
                        != (current_parent.st_dev, current_parent.st_ino)
                        or not _trusted_maintenance_lock_parent(parent, parent_descriptor)
                        or not _descriptor_acl_is_absent(parent_descriptor)
                        or not _descriptor_acl_is_absent(descriptor)
                        or (
                            expected_group_gid is not None
                            and os.access(
                                ".",
                                os.W_OK,
                                dir_fd=parent_descriptor,
                                effective_ids=True,
                                follow_symlinks=False,
                            )
                        )
                        or not stat.S_ISREG(current_file.st_mode)
                        or current_file.st_uid != expected_file_owner
                        or (
                            expected_group_gid is not None
                            and current_file.st_gid != expected_group_gid
                        )
                        or stat.S_IMODE(current_file.st_mode) != expected_file_mode
                        or current_file.st_nlink != 1
                        or (current_entry.st_dev, current_entry.st_ino)
                        != (current_file.st_dev, current_file.st_ino)
                        or (current_path_file.st_dev, current_path_file.st_ino)
                        != (current_file.st_dev, current_file.st_ino)
                    ):
                        raise HTTPException(
                            status_code=503,
                            detail={"code": "maintenance_lock_unavailable"},
                        )
                    return current_file_state

                file_state = verified_file_state()
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_SH | fcntl.LOCK_NB)
                except OSError as exc:
                    if exc.errno not in {errno.EACCES, errno.EAGAIN}:
                        raise
                    raise HTTPException(
                        status_code=503,
                        detail={
                            "code": "maintenance_in_progress",
                            "message": "report writes are temporarily paused for a consistent backup",
                        },
                        headers={"Retry-After": "5"},
                    ) from exc
                try:
                    if verified_file_state() != file_state:
                        raise HTTPException(
                            status_code=503,
                            detail={"code": "maintenance_lock_unavailable"},
                        )
                    yield
                finally:
                    try:
                        if verified_file_state() != file_state:
                            raise HTTPException(
                                status_code=503,
                                detail={"code": "maintenance_lock_unavailable"},
                            )
                    finally:
                        fcntl.flock(descriptor, fcntl.LOCK_UN)
            finally:
                os.close(descriptor)
        except HTTPException:
            raise
    finally:
        os.close(parent_descriptor)


def _idempotent_report_replay(
    db: Session,
    *,
    actor_id: str,
    privacy_subject: str | None,
    account_generation: int | None,
    class_name: str,
    captured_at: datetime,
    image_sha256: str,
) -> Report | None:
    if actor_id == UNKNOWN_ACTOR_ID:
        return None

    lock_key = (
        f"{privacy_subject or actor_id}\n{account_generation or 0}\n"
        f"{class_name}\n{captured_at.isoformat()}\n{image_sha256}"
    )
    # The transaction-scoped advisory lock makes the read-before-insert check
    # deterministic even when a browser retries the same multipart request in
    # parallel. Report storage is PostgreSQL/PostGIS-only, so this lock has the
    # same portability boundary as the location queries.
    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": lock_key},
    )
    return db.scalar(
        select(Report)
        .where(Report.class_name == class_name)
        .where(Report.captured_at == captured_at)
        .where(Report.privacy_subject_hmac == privacy_subject)
        .where(Report.account_generation == account_generation)
        .where(Report.payload.contains({"image_sha256": image_sha256}))
        .order_by(Report.created_at.desc())
        .limit(1)
    )


def _reports_summary(
    reports: Iterable[Report],
    *,
    grid_size_degrees: float = 0.001,
    top_limit: int = 5,
) -> dict[str, object]:
    # This is a deterministic display/export grid, not a geospatial clustering
    # algorithm or a replacement for raw PostGIS coordinates.
    clusters: dict[str, dict[str, Any]] = {}
    status_counts: dict[str, int] = {"new": 0, "reviewed": 0, "resolved": 0}
    source_counts: dict[str, int] = {"fake": 0, "onnx": 0, "server": 0, "android": 0}
    location_bounds: dict[str, float] | None = None
    located = 0
    fake = 0
    total = 0

    for report in reports:
        total += 1
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
        "total": total,
        "fake": fake,
        "non_fake": total - fake,
        "located": located,
        "missing_location": total - located,
        "bounds": location_bounds,
        "status_counts": status_counts,
        "source_counts": source_counts,
        "grid_size_degrees": grid_size_degrees,
        "top_clusters": top_clusters,
        "note": "Summary is computed from the current filtered report query, not from external map SDK data.",
    }


def _persist_legacy_report(
    db: Session,
    *,
    parsed: ReportMetadata,
    content: bytes,
    content_type: str,
    actor_id: str,
    account_generation: int | None,
    settings: Settings,
    key_manager: ReportImageKeyManager,
) -> ReportResponse:
    privacy_subject, bound_generation = _privacy_report_binding(
        db=db,
        actor_id=actor_id,
        account_generation=account_generation,
        settings=settings,
    )
    if privacy_subject is not None and bound_generation is not None:
        try:
            lock_report_ingest_transaction(
                db,
                privacy_subject,
                bound_generation,
                automatic_reporting=False,
                enforce_consent=settings.field_test_security_enabled,
            )
        except PrivacyLifecycleError as exc:
            db.rollback()
            raise HTTPException(
                status_code=exc.status_code,
                detail={"code": exc.code, "message": exc.message},
            ) from exc
    report_id = uuid.uuid4()
    filename = f"{report_id}{image_suffix(content_type)}"
    destination = settings.upload_dir / f"{report_id}.wse"
    gps = parsed.gps
    latitude = gps.latitude if gps else None
    longitude = gps.longitude if gps else None
    location = WKTElement(f"POINT({longitude} {latitude})", srid=4326) if gps else None
    lock_duplicate_candidate_window(
        db,
        class_name=parsed.class_name,
        captured_at=parsed.captured_at,
        is_fake=parsed.source == "fake",
    )
    duplicate_candidates = find_duplicate_candidates(db, parsed)
    stored_payload = _with_duplicate_candidate_payload(
        _enrich_legacy_payload(
            parsed.model_dump(mode="json"),
            source=parsed.source,
            content=content,
            actor_id=actor_id,
        ),
        duplicate_candidates,
    )
    replay = _idempotent_report_replay(
        db,
        actor_id=actor_id,
        privacy_subject=privacy_subject,
        account_generation=bound_generation,
        class_name=parsed.class_name,
        captured_at=parsed.captured_at,
        image_sha256=str(stored_payload["image_sha256"]),
    )
    if replay is not None:
        db.rollback()
        return _field_creation_response(replay)
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
        privacy_subject_hmac=privacy_subject,
        account_generation=bound_generation,
    )
    _commit_new_report_with_image(
        db,
        report,
        destination=destination,
        content=content,
        content_type=content_type,
        key_manager=key_manager,
        maintenance_lock_path=settings.maintenance_lock_path,
        maintenance_lock_group_gid=settings.maintenance_lock_group_gid,
        precommit_check=(
            lambda: _assert_report_precommit_active(
                db,
                privacy_subject=privacy_subject,
                account_generation=bound_generation,
                settings=settings,
            )
            if privacy_subject is not None and bound_generation is not None
            else None
        ),
    )
    return _field_creation_response(report, [str(candidate.id) for candidate in duplicate_candidates])


def _persist_v2_report(
    db: Session,
    *,
    parsed: ReportV2Metadata,
    content: bytes,
    content_type: str,
    actor_id: str,
    account_generation: int | None,
    settings: Settings,
    key_manager: ReportImageKeyManager,
) -> ReportResponse:
    privacy_subject, bound_generation = _privacy_report_binding(
        db=db,
        actor_id=actor_id,
        account_generation=account_generation,
        settings=settings,
    )
    if privacy_subject is not None and bound_generation is not None:
        try:
            lock_report_ingest_transaction(
                db,
                privacy_subject,
                bound_generation,
                automatic_reporting=parsed.auto_reported,
                enforce_consent=settings.field_test_security_enabled,
            )
        except PrivacyLifecycleError as exc:
            db.rollback()
            raise HTTPException(
                status_code=exc.status_code,
                detail={"code": exc.code, "message": exc.message},
            ) from exc
    report_source = report_v2_source(parsed)
    stored_payload = _enrich_v2_payload(
        _sanitized_v2_payload(parsed),
        source=report_source,
        content=content,
        actor_id=actor_id,
    )
    report_id = uuid.uuid4()
    filename = f"{report_id}{image_suffix(content_type)}"
    destination = settings.upload_dir / f"{report_id}.wse"
    gps = parsed.gps
    latitude = gps.latitude if gps else None
    longitude = gps.longitude if gps else None
    location = WKTElement(f"POINT({longitude} {latitude})", srid=4326) if gps else None
    is_fake = _is_fake_payload(stored_payload, report_source)
    if settings.field_test_security_enabled and parsed.auto_reported:
        assert gps is not None
        assert privacy_subject is not None and bound_generation is not None
        lock_auto_report_cooldown(
            db,
            privacy_subject=privacy_subject,
            account_generation=bound_generation,
            class_name=parsed.class_name,
        )
        active_cooldown = find_active_auto_report_cooldown(
            db,
            privacy_subject=privacy_subject,
            account_generation=bound_generation,
            class_name=parsed.class_name,
            lat=gps.latitude,
            lng=gps.longitude,
            now=datetime.now(UTC),
        )
        if active_cooldown is not None:
            # Treat the authoritative cooldown as an idempotent success. Both
            # clients then arm their local cooldown without retry storms, and
            # no second row or image is written.
            db.rollback()
            return _field_creation_response(active_cooldown)
    lock_duplicate_candidate_window(
        db,
        class_name=parsed.class_name,
        captured_at=parsed.captured_at,
        is_fake=is_fake,
    )
    duplicate_candidates = find_duplicate_candidates_v2(db, parsed, is_fake=is_fake)
    stored_payload = _with_duplicate_candidate_payload(stored_payload, duplicate_candidates)
    replay = _idempotent_report_replay(
        db,
        actor_id=actor_id,
        privacy_subject=privacy_subject,
        account_generation=bound_generation,
        class_name=parsed.class_name,
        captured_at=parsed.captured_at,
        image_sha256=str(stored_payload["image_sha256"]),
    )
    if replay is not None:
        db.rollback()
        return _field_creation_response(replay)
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
        privacy_subject_hmac=privacy_subject,
        account_generation=bound_generation,
    )
    _commit_new_report_with_image(
        db,
        report,
        destination=destination,
        content=content,
        content_type=content_type,
        key_manager=key_manager,
        maintenance_lock_path=settings.maintenance_lock_path,
        maintenance_lock_group_gid=settings.maintenance_lock_group_gid,
        precommit_check=(
            lambda: _assert_report_precommit_active(
                db,
                privacy_subject=privacy_subject,
                account_generation=bound_generation,
                settings=settings,
            )
            if privacy_subject is not None and bound_generation is not None
            else None
        ),
    )
    return _field_creation_response(report, [str(candidate.id) for candidate in duplicate_candidates])


def create_router(settings: Settings, key_manager: ReportImageKeyManager) -> APIRouter:
    router = APIRouter()
    admin_reconfirmation_openapi_extra = (
        {"parameters": [_ADMIN_RECONFIRMATION_OPENAPI_PARAMETER]}
        if settings.admin_security_enabled
        else None
    )

    @router.post("/reports", response_model=ReportResponse, status_code=status.HTTP_201_CREATED)
    async def create_report(
        metadata: str = Form(...),
        image: UploadFile = File(...),
        x_walksafe_actor_id: Optional[str] = Header(default=None, alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: Optional[str] = Header(
            default=None,
            alias="x-walksafe-account-generation",
        ),
        db: Session = Depends(get_db),
    ) -> ReportResponse:
        actor_id = _resolved_actor_id(x_walksafe_actor_id, required=settings.field_test_security_enabled)
        account_generation = _resolved_account_generation(
            x_walksafe_account_generation,
            actor_id=actor_id,
            required=settings.field_test_security_enabled,
        )
        _enforce_metadata_size(metadata, settings)
        try:
            parsed = ReportMetadata.model_validate_json(metadata)
        except (ValidationError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        if parsed.gps is None:
            raise HTTPException(status_code=422, detail="legacy reports require gps; use reports/v2 when available")

        content, content_type = await read_image_upload(image, settings)
        return await to_thread.run_sync(
            lambda: _persist_legacy_report(
                db,
                parsed=parsed,
                content=content,
                content_type=content_type,
                actor_id=actor_id,
                account_generation=account_generation,
                settings=settings,
                key_manager=key_manager,
            )
        )

    @router.post("/reports/v2", response_model=ReportResponse, status_code=status.HTTP_201_CREATED)
    async def create_report_v2(
        metadata: str = Form(...),
        image: UploadFile = File(...),
        x_walksafe_actor_id: Optional[str] = Header(default=None, alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: Optional[str] = Header(
            default=None,
            alias="x-walksafe-account-generation",
        ),
        db: Session = Depends(get_db),
    ) -> ReportResponse:
        actor_id = _resolved_actor_id(x_walksafe_actor_id, required=settings.field_test_security_enabled)
        account_generation = _resolved_account_generation(
            x_walksafe_account_generation,
            actor_id=actor_id,
            required=settings.field_test_security_enabled,
        )
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
        _enforce_secured_android_provenance(parsed, settings)
        _enforce_secured_auto_report_gate(parsed, settings, actor_id=actor_id)
        content, content_type = await read_image_upload(image, settings)
        return await to_thread.run_sync(
            lambda: _persist_v2_report(
                db,
                parsed=parsed,
                content=content,
                content_type=content_type,
                actor_id=actor_id,
                account_generation=account_generation,
                settings=settings,
                key_manager=key_manager,
            )
        )

    @router.get("/reports", response_model=List[ReportResponse])
    def list_reports(
        db: Session = Depends(get_db),
        limit: int = Query(default=25, ge=1, le=100),
        offset: int = Query(default=0, ge=0, le=1_000_000),
        status: Optional[ReportStatus] = Query(default=None),
        class_name: Optional[str] = Query(default=None),
        source: Optional[DetectorSource] = Query(default=None),
        demo_filter: ReportDemoFilter = Query(default="all"),
        model_key: Optional[str] = Query(default=None),
        trigger: Optional[str] = Query(default=None),
        auto_reported: Optional[bool] = Query(default=None),
        performance_excluded: Optional[bool] = Query(default=None),
        created_from: Optional[AwareDatetime] = Query(default=None),
        created_to: Optional[AwareDatetime] = Query(default=None),
        lat: Optional[float] = Query(default=None, ge=-90, le=90),
        lng: Optional[float] = Query(default=None, ge=-180, le=180),
        radius_m: Optional[float] = Query(default=None, gt=0),
        x_walksafe_actor_id: Optional[str] = Header(default=None, alias="x-walksafe-actor-id"),
        x_walksafe_read_purpose: Optional[str] = Header(default=None, alias="x-walksafe-read-purpose"),
    ) -> list[ReportResponse]:
        actor_id, read_purpose = resolve_read_audit_identity(
            x_walksafe_actor_id,
            x_walksafe_read_purpose,
            required=settings.field_test_security_enabled,
        )
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
        ).order_by(Report.created_at.desc(), Report.id.desc()).offset(offset).limit(limit)
        reports = db.scalars(statement).all()
        if settings.field_test_security_enabled:
            persist_report_read_audit(
                db,
                actor_id=actor_id,
                purpose=read_purpose,
                resource_type="report_list",
                resource_id="reports",
                details={
                    "limit": limit,
                    "offset": offset,
                    "result_count": len(reports),
                    "exact_location_filter_applied": lat is not None or lng is not None,
                },
            )
        return [report_to_response(report) for report in reports]

    @router.get(
        "/reports/export",
        openapi_extra=admin_reconfirmation_openapi_extra,
    )
    def export_reports(
        request: Request,
        db: Session = Depends(get_db),
        format: str = Query(default="csv"),
        status: Optional[ReportStatus] = Query(default=None),
        class_name: Optional[str] = Query(default=None),
        source: Optional[DetectorSource] = Query(default=None),
        demo_filter: ReportDemoFilter = Query(default="all"),
        model_key: Optional[str] = Query(default=None),
        trigger: Optional[str] = Query(default=None),
        auto_reported: Optional[bool] = Query(default=None),
        performance_excluded: Optional[bool] = Query(default=None),
        created_from: Optional[AwareDatetime] = Query(default=None),
        created_to: Optional[AwareDatetime] = Query(default=None),
        lat: Optional[float] = Query(default=None, ge=-90, le=90),
        lng: Optional[float] = Query(default=None, ge=-180, le=180),
        radius_m: Optional[float] = Query(default=None, gt=0),
        redacted: bool = Query(default=False),
        profile: Literal["internal", "minimum", "agency"] = Query(default="internal"),
        bom: bool = Query(default=False),
        manifest: bool = Query(default=False),
        bundle: bool = Query(default=False),
        aggregate: Optional[str] = Query(default=None),
        requested_audit_id: Optional[uuid.UUID] = Query(default=None, alias="audit_id"),
        x_walksafe_actor_id: Optional[str] = Header(default=None, alias="x-walksafe-actor-id"),
    ):
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
        statement = statement.order_by(Report.created_at.desc()).limit(MAX_REPORT_EXPORT_ROWS + 1)
        reports = list(db.scalars(statement).all())
        if profile == "agency":
            reports = [report for report in reports if _agency_review_was_named(dict(report.payload or {}))]
        if len(reports) > MAX_REPORT_EXPORT_ROWS:
            raise HTTPException(
                status_code=413,
                detail={
                    "code": "report_export_too_large",
                    "message": "narrow the export filters before downloading",
                    "max_rows": MAX_REPORT_EXPORT_ROWS,
                },
            )
        rows = [_report_export_row(report) for report in reports]
        if profile == "agency":
            rows = [
                _agency_export_row(row)
                for row in rows
                if isinstance(row.get("latitude"), (int, float))
                and isinstance(row.get("longitude"), (int, float))
                and row.get("location_quality") == "high"
            ]
        elif profile == "minimum":
            rows = [_minimum_export_row(row) for row in rows]
        elif redacted:
            rows = [_redact_export_row(row) for row in rows]

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
        if bundle:
            csv_text = _csv_export_text(rows, bom=bom)
            manifest_payload = _export_manifest(
                rows,
                filters=export_filters,
                generated_at=datetime.now(UTC),
                actor_id=actor_id,
                audit_id=audit_id,
            )
            archive_output = io.BytesIO()
            with zipfile.ZipFile(archive_output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("reports.csv", csv_text.encode("utf-8"))
                archive.writestr(
                    "manifest.json",
                    json.dumps(manifest_payload, ensure_ascii=False, default=str, indent=2).encode("utf-8"),
                )
            bundle_filename = filename.removesuffix(".csv") + ".bundle.zip"
            return Response(
                content=archive_output.getvalue(),
                media_type="application/zip",
                headers={
                    "Content-Disposition": f'attachment; filename="{bundle_filename}"',
                    "Cache-Control": "no-store",
                    "X-WalkSafe-Demo-Filter": demo_filter,
                    "X-WalkSafe-Export-Profile": profile,
                    "X-WalkSafe-Audit-Id": audit_id,
                    "X-WalkSafe-Actor-Id": actor_id,
                    "X-WalkSafe-Location-Precision": location_precision,
                    "X-WalkSafe-Export-Bundle": "csv+manifest",
                },
            )
        if export_format == "json":
            content: object = rows
            if manifest:
                content = _export_manifest(
                    rows,
                    filters=export_filters,
                    generated_at=datetime.now(UTC),
                    actor_id=actor_id,
                    audit_id=audit_id,
                )
            return Response(
                content=json.dumps(content, ensure_ascii=False, default=str),
                media_type="application/json",
                headers={
                    "Content-Disposition": content_disposition,
                    "Cache-Control": "no-store",
                    "X-WalkSafe-Demo-Filter": demo_filter,
                    "X-WalkSafe-Export-Profile": profile,
                    "X-WalkSafe-Audit-Id": audit_id,
                    "X-WalkSafe-Actor-Id": actor_id,
                    "X-WalkSafe-Location-Precision": location_precision,
                },
            )
        if export_format == "geojson":
            body = (
                _reports_cluster_geojson(_reports_summary(reports))
                if aggregate == "grid"
                else _reports_geojson(rows)
            )
            properties = body.setdefault("properties", {})
            if isinstance(properties, dict):
                properties["audit_id"] = audit_id
                properties["actor_id"] = actor_id
                properties["location_precision"] = location_precision
            return Response(
                content=json.dumps(body, ensure_ascii=False, default=str),
                media_type="application/geo+json",
                headers={
                    "Content-Disposition": content_disposition,
                    "Cache-Control": "no-store",
                    "X-WalkSafe-Demo-Filter": demo_filter,
                    "X-WalkSafe-Export-Profile": profile,
                    "X-WalkSafe-Location-Precision": location_precision,
                    "X-WalkSafe-Audit-Id": audit_id,
                    "X-WalkSafe-Actor-Id": actor_id,
                },
            )
        csv_text = _csv_export_text(rows, bom=bom)
        return Response(
            content=csv_text,
            media_type="text/csv",
            headers={
                "Content-Disposition": content_disposition,
                "Cache-Control": "no-store",
                "X-WalkSafe-Demo-Filter": demo_filter,
                "X-WalkSafe-Export-Profile": profile,
                "X-WalkSafe-Audit-Id": audit_id,
                "X-WalkSafe-Actor-Id": actor_id,
                "X-WalkSafe-Location-Precision": location_precision,
            },
        )

    @router.get("/reports/summary")
    def summarize_reports(
        db: Session = Depends(get_db),
        status: Optional[ReportStatus] = Query(default=None),
        class_name: Optional[str] = Query(default=None),
        source: Optional[DetectorSource] = Query(default=None),
        demo_filter: ReportDemoFilter = Query(default="all"),
        model_key: Optional[str] = Query(default=None),
        trigger: Optional[str] = Query(default=None),
        auto_reported: Optional[bool] = Query(default=None),
        performance_excluded: Optional[bool] = Query(default=None),
        created_from: Optional[AwareDatetime] = Query(default=None),
        created_to: Optional[AwareDatetime] = Query(default=None),
        lat: Optional[float] = Query(default=None, ge=-90, le=90),
        lng: Optional[float] = Query(default=None, ge=-180, le=180),
        radius_m: Optional[float] = Query(default=None, gt=0),
        grid_size_degrees: float = Query(default=0.001, gt=0, le=1),
        top_limit: int = Query(default=5, ge=0, le=50),
        x_walksafe_actor_id: Optional[str] = Header(default=None, alias="x-walksafe-actor-id"),
        x_walksafe_read_purpose: Optional[str] = Header(default=None, alias="x-walksafe-read-purpose"),
    ) -> dict[str, object]:
        actor_id, read_purpose = resolve_read_audit_identity(
            x_walksafe_actor_id,
            x_walksafe_read_purpose,
            required=settings.field_test_security_enabled,
        )
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
        summary = _reports_summary(
            _bounded_summary_reports(
                db.scalars(
                    statement.limit(MAX_REPORT_SUMMARY_ROWS + 1).execution_options(yield_per=500)
                )
            ),
            grid_size_degrees=grid_size_degrees,
            top_limit=top_limit,
        )
        if settings.field_test_security_enabled:
            persist_report_read_audit(
                db,
                actor_id=actor_id,
                purpose=read_purpose,
                resource_type="report_list",
                resource_id="reports/summary",
                details={
                    "result_count": summary["total"],
                    "located_count": summary["located"],
                    "exact_location_filter_applied": lat is not None or lng is not None,
                    "grid_size_degrees": grid_size_degrees,
                    "top_limit": top_limit,
                },
            )
        return summary

    @router.get("/reports/duplicate-check", response_model=DuplicateCheckResponse)
    def check_report_duplicates(
        db: Session = Depends(get_db),
        class_name: ClassName = Query(...),
        captured_at: AwareDatetime = Query(...),
        lat: float = Query(..., ge=-90, le=90),
        lng: float = Query(..., ge=-180, le=180),
        radius_m: float = Query(default=DUPLICATE_RADIUS_M, gt=0, le=200),
        minutes: int = Query(default=DUPLICATE_WINDOW_MINUTES, ge=1, le=60),
        fake_source: bool = Query(default=False),
        x_walksafe_actor_id: Optional[str] = Header(default=None, alias="x-walksafe-actor-id"),
        x_walksafe_read_purpose: Optional[str] = Header(default=None, alias="x-walksafe-read-purpose"),
    ) -> DuplicateCheckResponse:
        actor_id, read_purpose = resolve_read_audit_identity(
            x_walksafe_actor_id,
            x_walksafe_read_purpose,
            required=settings.field_test_security_enabled,
        )
        statement = duplicate_candidate_statement(
            class_name=class_name,
            captured_at=captured_at,
            lat=lat,
            lng=lng,
            radius_m=radius_m,
            minutes=minutes,
            is_fake=fake_source,
        )
        reports = list(db.scalars(statement).all())
        report_ids = [str(report.id) for report in reports]
        if settings.field_test_security_enabled:
            persist_report_read_audit(
                db,
                actor_id=actor_id,
                purpose=read_purpose,
                resource_type="report_duplicate_check",
                resource_id="reports/duplicate-check",
                details={
                    "class_name": class_name,
                    "result_count": len(report_ids),
                    "location_filter_applied": True,
                    "radius_m": radius_m,
                    "minutes": minutes,
                    "fake_source": fake_source,
                },
            )
        return DuplicateCheckResponse(
            duplicate_report_ids=report_ids,
            duplicate_count=len(report_ids),
        )

    @router.post(
        "/reports/{report_id}/original-access-grants",
        response_model=ReportOriginalAccessGrantResponse,
        status_code=status.HTTP_201_CREATED,
        openapi_extra=admin_reconfirmation_openapi_extra,
    )
    def create_report_original_access_grant(
        report_id: uuid.UUID,
        payload: ReportOriginalAccessGrantRequest,
        request: Request,
        response: Response,
        db: Session = Depends(get_db),
    ) -> ReportOriginalAccessGrantResponse:
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        if not settings.admin_security_enabled:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "admin_security_required",
                    "message": "Database-backed administrator security is required for original access.",
                },
            )
        identity = _reauthorize_admin_high_risk_in_transaction(
            request,
            db,
            settings,
            "report.original.grant",
        )
        if identity is None:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "admin_security_required",
                    "message": "Database-backed administrator security is required for original access.",
                },
            )
        try:
            grant = issue_report_original_access_grant(
                db,
                report_id=report_id,
                purpose=payload.purpose,
                reason=payload.reason,
                identity=identity,
                ttl_seconds=settings.report_original_grant_ttl_seconds,
                key_manager=key_manager,
            )
        except ReportOriginalAccessError as exc:
            raise HTTPException(
                status_code=exc.status_code,
                detail={"code": exc.code, "message": exc.message},
            ) from exc
        return ReportOriginalAccessGrantResponse(
            schema_version="walksafe.report-original-access-grant.v1",
            grant_id=grant.grant_id,
            report_id=grant.report_id,
            purpose=grant.purpose,
            access_token=grant.access_token,
            expires_at=grant.expires_at,
        )

    @router.post(
        "/reports/{report_id}/review-decisions",
        response_model=ReportReviewDecisionResponse,
        status_code=status.HTTP_201_CREATED,
    )
    def create_report_review_decision(
        report_id: uuid.UUID,
        payload: ReportReviewDecisionRequest,
        request: Request,
        response: Response,
        db: Session = Depends(get_db),
    ) -> ReportReviewDecisionResponse:
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        identity, proof = _require_admin_report_workflow_context(
            request,
            expected_action="report.review.decide",
            expected_read_purpose=None,
        )
        try:
            return append_report_review_decision(
                db,
                report_id=report_id,
                payload=payload,
                identity=identity,
                correlation_id=proof.correlation_id,
            )
        except AdminReportWorkflowError as exc:
            _raise_admin_report_workflow_error(
                exc,
                request=request,
                identity=identity,
                proof=proof,
                audit_action="report.review.decide",
            )

    @router.get(
        "/reports/{report_id}/review-decisions",
        response_model=list[ReportReviewDecisionResponse],
    )
    def get_report_review_decisions(
        report_id: uuid.UUID,
        request: Request,
        response: Response,
        db: Session = Depends(get_db),
    ) -> list[ReportReviewDecisionResponse]:
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        identity, proof = _require_admin_report_workflow_context(
            request,
            expected_action=None,
            expected_read_purpose="report.review_decisions",
        )
        try:
            decisions = list_report_review_decisions(db, report_id=report_id)
        except AdminReportWorkflowError as exc:
            _raise_admin_report_workflow_error(
                exc,
                request=request,
                identity=identity,
                proof=proof,
                audit_action="report.review_decisions.read",
            )
        persist_report_read_audit(
            db,
            actor_id=identity.admin_id,
            purpose="report.review_decisions",
            resource_type="report_detail",
            resource_id=str(report_id),
            details={
                "view": "review_decisions",
                "result_count": len(decisions),
                "session_id": str(identity.session_id),
                "device_id": identity.device_id,
                "correlation_id": str(proof.correlation_id),
            },
        )
        return decisions

    @router.post(
        "/reports/{report_id}/deliveries",
        response_model=ReportInstitutionDeliveryResponse,
        status_code=status.HTTP_201_CREATED,
    )
    def create_report_institution_delivery(
        report_id: uuid.UUID,
        payload: ReportInstitutionDeliveryRequest,
        request: Request,
        response: Response,
        db: Session = Depends(get_db),
    ) -> ReportInstitutionDeliveryResponse:
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        identity, proof = _require_admin_report_workflow_context(
            request,
            expected_action="report.delivery.create",
            expected_read_purpose=None,
        )
        try:
            return append_report_institution_delivery_event(
                db,
                report_id=report_id,
                payload=payload,
                identity=identity,
                correlation_id=proof.correlation_id,
            )
        except AdminReportWorkflowError as exc:
            _raise_admin_report_workflow_error(
                exc,
                request=request,
                identity=identity,
                proof=proof,
                audit_action="report.delivery.create",
            )

    @router.get(
        "/reports/{report_id}/deliveries",
        response_model=list[ReportInstitutionDeliveryResponse],
    )
    def get_report_institution_deliveries(
        report_id: uuid.UUID,
        request: Request,
        response: Response,
        db: Session = Depends(get_db),
    ) -> list[ReportInstitutionDeliveryResponse]:
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        identity, proof = _require_admin_report_workflow_context(
            request,
            expected_action=None,
            expected_read_purpose="report.delivery_events",
        )
        try:
            events = list_report_institution_delivery_events(db, report_id=report_id)
        except AdminReportWorkflowError as exc:
            _raise_admin_report_workflow_error(
                exc,
                request=request,
                identity=identity,
                proof=proof,
                audit_action="report.delivery_events.read",
            )
        persist_report_read_audit(
            db,
            actor_id=identity.admin_id,
            purpose="report.delivery_events",
            resource_type="report_detail",
            resource_id=str(report_id),
            details={
                "view": "delivery_events",
                "result_count": len(events),
                "session_id": str(identity.session_id),
                "device_id": identity.device_id,
                "correlation_id": str(proof.correlation_id),
            },
        )
        return events

    @router.get("/reports/{report_id}", response_model=ReportResponse)
    def get_report(
        report_id: uuid.UUID,
        x_walksafe_actor_id: Optional[str] = Header(default=None, alias="x-walksafe-actor-id"),
        x_walksafe_read_purpose: Optional[str] = Header(default=None, alias="x-walksafe-read-purpose"),
        db: Session = Depends(get_db),
    ) -> ReportResponse:
        actor_id, read_purpose = resolve_read_audit_identity(
            x_walksafe_actor_id,
            x_walksafe_read_purpose,
            required=settings.field_test_security_enabled,
        )
        report = db.get(Report, report_id)
        if report is None:
            raise HTTPException(status_code=404, detail="report not found")
        if settings.field_test_security_enabled:
            persist_report_read_audit(
                db,
                actor_id=actor_id,
                purpose=read_purpose,
                resource_type="report_detail",
                resource_id=str(report_id),
            )
        return report_to_response(report)

    @router.patch(
        "/reports/{report_id}/status",
        response_model=ReportResponse,
        openapi_extra=admin_reconfirmation_openapi_extra,
    )
    def update_report_status(
        report_id: uuid.UUID,
        payload: ReportStatusUpdate,
        request: Request,
        x_walksafe_actor_id: Optional[str] = Header(default=None, alias="x-walksafe-actor-id"),
        db: Session = Depends(get_db),
    ) -> ReportResponse:
        actor_id = _resolved_actor_id(x_walksafe_actor_id, required=settings.field_test_security_enabled)
        with _shared_report_write_lock(
            settings.maintenance_lock_path,
            expected_group_gid=settings.maintenance_lock_group_gid,
        ):
            _reauthorize_admin_high_risk_in_transaction(
                request,
                db,
                settings,
                "report.status.patch",
            )
            report = db.scalar(
                select(Report)
                .where(Report.id == report_id)
                .with_for_update()
            )
            if report is None:
                raise HTTPException(status_code=404, detail="report not found")

            if (
                payload.expected_updated_at is not None
                and report.updated_at is not None
                and report.updated_at.isoformat() != payload.expected_updated_at.isoformat()
            ):
                raise HTTPException(status_code=409, detail="report status was updated by another request")

            if payload.status == report.status:
                raise HTTPException(
                    status_code=409,
                    detail={
                        "code": "report_status_unchanged",
                        "message": "A status update must change the report status.",
                    },
                )

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
                    "actor_id": actor_id,
                }
            )
            report_payload["status_history"] = history[-20:]
            if payload.note:
                report_payload["review_note"] = payload.note
            if payload.resolution_reason:
                report_payload["resolution_reason"] = payload.resolution_reason
            if payload.status == "reviewed" and _agency_review_eligible(report, report_payload, actor_id):
                report_payload["agency_review_verified"] = True
                report_payload["agency_reviewed_by_actor_id"] = actor_id
                report_payload["agency_reviewed_at"] = datetime.now(UTC).isoformat().replace("+00:00", "Z")
            elif payload.status != "reviewed":
                report_payload.pop("agency_review_verified", None)
                report_payload.pop("agency_reviewed_by_actor_id", None)
                report_payload.pop("agency_reviewed_at", None)
            report.status = payload.status
            report.payload = report_payload
            db.add(
                ReportStatusAudit(
                    report_id=report.id,
                    previous_status=previous_status,
                    next_status=payload.status,
                    actor_id=actor_id,
                    note=payload.note,
                    resolution_reason=payload.resolution_reason,
                )
            )
            db.commit()
            db.refresh(report)
            return report_to_response(report)

    return router
