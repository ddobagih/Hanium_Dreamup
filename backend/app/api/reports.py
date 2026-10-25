"""Report ingestion, review, query, summary, and internal export routes.

Legacy v1 and v2 reports share one table, but v2 metadata is allowlisted and
restricted to reportable tactile damage. Export endpoints support internal
operations only; this module does not submit reports to a public agency.
"""

from __future__ import annotations

from backend.app.services.privacy_visibility import account_subject_visible

from backend.app.services.report_export import (
    MAX_REPORT_AGENCY_EXPORT_SCAN_ROWS,
    MAX_REPORT_EXPORT_ROWS,
    REPORT_EXPORT_FIELDS,
    _REPORT_EXPORT_GATE,
    _agency_export_row,
    _csv_export_text,
    _csv_metadata_value,
    _csv_safe,
    _demo_filename_part,
    _durable_export_audit_filters,
    _export_filename,
    _export_manifest,
    _export_rows_sha256,
    _isoformat,
    _iter_bounded_export_reports,
    _minimum_export_row,
    _persist_export_audit,
    _redact_export_row,
    _report_export_row,
    _reports_cluster_geojson,
    _reports_geojson,
    build_report_export,
    logger,
)

from backend.app.services.report_application import (
    ACTOR_ID_PATTERN,
    AUTO_REPORT_MAX_AGE,
    AUTO_REPORT_MAX_FUTURE_SKEW,
    REPORT_TRANSPORT_BYTES_PATTERN,
    REPORT_TRANSPORT_MARKER,
    REPORT_TRANSPORT_PAYLOAD_DOMAIN,
    REPORT_TRANSPORT_SHA256_PATTERN,
    REPORT_TRANSPORT_USER_STATUS,
    REPORT_V2_METADATA_ALLOWLIST,
    ReportTransportContract,
    UNKNOWN_ACTOR_ID,
    _agency_review_eligible,
    _agency_review_was_named,
    _assert_report_precommit_active,
    _bytes_sha256,
    _coordinate_gate_exclusion_reason,
    _enforce_metadata_size,
    _enforce_secured_auto_report_gate,
    _enrich_legacy_payload,
    _enrich_v2_payload,
    _fake_demo_report_condition,
    _field_creation_response,
    _filtered_reports_statement,
    _idempotent_report_replay,
    _is_fake_payload,
    _is_named_human_actor,
    _json_sha256,
    _parse_report_transport_status_id,
    _persist_legacy_report,
    _persist_v2_report,
    _postcommit_field_creation_response,
    _privacy_report_binding,
    _privacy_report_candidates,
    _reauthorize_admin_high_risk_in_transaction,
    _reject_oriented_report_bbox,
    _report_grid_cell,
    _report_list_fingerprint,
    _report_transport_not_found,
    _report_transport_receipt,
    _report_transport_replay,
    _report_transport_status_actor,
    _report_transport_status_response,
    _report_transport_status_statement,
    _reports_summary,
    _resolve_report_transport_contract,
    _resolved_account_generation,
    _resolved_actor_id,
    _sanitized_v2_payload,
    _validate_report_transport_payload,
    _with_duplicate_candidate_payload,
    report_transport_payload_sha256,
)

from backend.app.services.report_commit import (
    _commit_new_report_with_image,
)

from backend.app.services.maintenance_lock import (
    _ReportWriteLockExitError,
    _descriptor_acl_is_absent,
    _shared_report_write_lock,
    _trusted_maintenance_lock_parent,
    shared_maintenance_write_lock,
)

import csv
from contextlib import closing, contextmanager
from dataclasses import dataclass
import errno
import fcntl
import hashlib
import io
import json
import logging
import math
import os
import re
import stat
import uuid
import zipfile
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from typing import Annotated, Any, Callable, Iterable, List, Literal, NoReturn, Optional

from anyio import to_thread
from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Path as ApiPath, Query, Request, Response, UploadFile, status
from geoalchemy2.elements import WKTElement
from pydantic import AwareDatetime, ValidationError
from sqlalchemy import Text, case, func, not_, or_, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from backend.app.api.request_validation import public_request_validation_detail
from backend.app.services.report_status_transition import decide_report_status_transition
from backend.app.config import Settings
from backend.app.database import get_db
from backend.app.field_test_security import (
    ADMIN_RECONFIRM_NONCE_HEADER_NAME,
    FieldTestAccess,
    VerifiedActorAssertion,
)
from backend.app.models import (
    Report,
    ReportExportAudit,
    ReportImageObject,
    ReportInstitutionDeliveryEvent,
    ReportStatusAudit,
)
from backend.app.schemas import (
    ClassName,
    DetectorSource,
    DuplicateCheckResponse,
    ReportCreationOutcome,
    ReportCreationResponse,
    ReportInstitutionDeliveryPageV1,
    ReportInstitutionDeliveryCorrectionRequest,
    ReportInstitutionDeliveryCorrectionV1,
    ReportInstitutionDeliveryRequest,
    ReportInstitutionDeliveryResponse,
    ReportMetadata,
    ReportOriginalAccessGrantRequest,
    ReportOriginalAccessGrantResponse,
    ReportDemoFilter,
    ReportResponse,
    ReportReviewDecisionRequest,
    ReportReviewDecisionPageV1,
    ReportReviewDecisionResponse,
    ReportStatus,
    ReportTransportReceiptV1,
    ReportTransportStatusV1,
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
from backend.app.services.android_release_allowlist import approved_android_release
from backend.app.services.report_policy import ensure_report_v2_allowed, report_v2_source
from backend.app.services.report_policy import HIGH_LOCATION_ACCURACY_M, LOW_CONFIDENCE_THRESHOLD
from backend.app.services.report_read_audit import persist_report_read_audit, resolve_read_audit_identity
from backend.app.services.report_serialization import location_quality, report_to_response
from backend.app.services.report_summary_aggregation import (
    REPORT_SUMMARY_MAX_GRID_SIZE_DEGREES,
    REPORT_SUMMARY_MIN_GRID_SIZE_DEGREES,
    aggregate_report_summary,
)
from backend.app.services.report_export_limits import (
    MAX_REPORT_EXPORT_BYTES,
    BoundedBytesIO,
    BoundedUtf8TextBuffer,
    ExportCapacityLimit,
    ExportSizeLimit,
    ExportWorkGate,
    bounded_json_bytes,
    iter_bounded_json_bytes,
)
from backend.app.services.storage_readiness_state import StorageReadinessState
from backend.app.services.report_storage import (
    ReportImageCommitMetadata,
    complete_report_image_write,
    lock_report_storage_write_transaction,
    stage_report_image,
    validate_report_image_write,
)
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    assert_privacy_hmac_key_bound,
    assert_report_ingest_active,
    bind_or_verify_privacy_hmac_key,
    configured_privacy_hmac_lookup_keys,
    lock_report_ingest_transaction,
    privacy_subject_candidates,
    privacy_subject_hmac,
    resolve_privacy_subject,
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
    correct_report_institution_delivery_event,
    delivery_items_with_corrections,
    append_report_review_decision,
    get_report_review_decision,
    list_report_institution_delivery_events,
    list_report_institution_delivery_history_page,
    list_report_review_decision_history_page,
    list_report_review_decisions,
)
from backend.app.services.admin_history_pagination import (
    HISTORY_PAGE_DEFAULT_LIMIT,
    HISTORY_PAGE_MAX_LIMIT,
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
        "pattern": "^[A-Za-z0-9_-]{21}[AQgw]$",
    },
}


_ADMIN_HISTORY_QUERY_FIELDS = frozenset({"limit", "cursor", "position"})
_ADMIN_HISTORY_NO_STORE_HEADERS = {
    "Cache-Control": "no-store",
    "Pragma": "no-cache",
}
_CANONICAL_HISTORY_UUID_PATTERN = (
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)


def _admin_history_query_shape_allowed(request: Request) -> bool:
    seen: set[str] = set()
    for key, _value in request.query_params.multi_items():
        if key not in _ADMIN_HISTORY_QUERY_FIELDS or key in seen:
            return False
        seen.add(key)
    return True


def _report_history_limit(limit: int | None, position: str | None) -> int:
    if limit is not None:
        return limit
    return HISTORY_PAGE_MAX_LIMIT if position == "last" else HISTORY_PAGE_DEFAULT_LIMIT


def _with_admin_history_no_store(exc: HTTPException) -> HTTPException:
    exc.headers = {**(exc.headers or {}), **_ADMIN_HISTORY_NO_STORE_HEADERS}
    return exc


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
    response_headers: dict[str, str] | None = None,
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
        headers = dict(response_headers or {})
        if audit_exc.retry_after is not None:
            headers["Retry-After"] = str(audit_exc.retry_after)
        raise HTTPException(
            status_code=audit_exc.status_code,
            detail={"code": audit_exc.code, "message": audit_exc.message},
            headers=headers or None,
        ) from audit_exc
    raise HTTPException(
        status_code=exc.status_code,
        detail={"code": exc.code, "message": exc.message},
        headers=response_headers,
    ) from exc


MAX_REPORT_SUMMARY_ROWS = 10_000

# Android metadata is client-supplied evidence, not an authenticated identity.
# Keep this allowlist explicit so arbitrary device fields are not persisted.


def _json_size_bytes(value: object) -> int:
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str).encode("utf-8"))


def _enforce_secured_android_provenance(parsed: ReportV2Metadata, settings: Settings) -> None:
    if not settings.field_test_security_enabled or parsed.source != "android":
        return
    # S20-7: approved release-bundle table (default: the current release only).
    # The report keeps source_commit as its audit field either way.
    if not approved_android_release(
        settings,
        source_commit=parsed.source_commit,
        apk_sha256=getattr(parsed, "apk_sha256", None),
        model_config_sha256=getattr(parsed, "model_config_sha256", None),
    ):
        raise HTTPException(
            status_code=422,
            detail={
                "code": "android_provenance_gate_failed",
                "message": "Android reports must come from an approved release bundle of the secured backend.",
            },
        )


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


_REPORT_SUMMARY_GRID_DESCRIPTION = (
    "Display grid size in degrees (0.0001 to 1). A coordinate x belongs to "
    "cell floor(x / size) computed in float64 when the returned float bounds "
    "satisfy lower <= x < upper; otherwise the exact rational value of the "
    "input floats decides the cell. Returned bounds are rounded display "
    "values, so x may equal max_* only in that exact fallback."
)


def create_router(
    settings: Settings, key_manager: ReportImageKeyManager | None = None, *,
    key_manager_provider=None,
    storage_readiness: StorageReadinessState | None = None,
) -> APIRouter:
    router = APIRouter()
    admin_reconfirmation_openapi_extra = (
        {"parameters": [_ADMIN_RECONFIRMATION_OPENAPI_PARAMETER]}
        if settings.admin_security_enabled
        else None
    )

    @router.post("/reports", response_model=ReportCreationResponse, status_code=status.HTTP_201_CREATED)
    async def create_report(
        metadata: str = Form(...),
        image: UploadFile = File(...),
        x_walksafe_actor_id: Optional[str] = Header(default=None, alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: Optional[str] = Header(
            default=None,
            alias="x-walksafe-account-generation",
        ),
        db: Session = Depends(get_db),
    ) -> ReportCreationResponse:
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
            raise HTTPException(
                status_code=422,
                detail=public_request_validation_detail(
                    exc, model=ReportMetadata, field="metadata", code="report_metadata_invalid",
                ),
            ) from None
        if settings.field_test_security_enabled and parsed.source == "android":
            # S20-7 (be-F5): secured Android reports use /reports/v2 and its
            # approved-release gate; the legacy endpoint must not bypass it.
            raise HTTPException(
                status_code=422,
                detail={
                    "code": "android_provenance_gate_failed",
                    "message": "Android reports must use /reports/v2 on the secured backend.",
                },
            )
        if parsed.gps is None:
            raise HTTPException(status_code=422, detail="legacy reports require gps; use reports/v2 when available")

        content, content_type = await read_image_upload(
            image,
            settings,
            validate_original=_reject_oriented_report_bbox,
        )
        return await to_thread.run_sync(
            lambda: _persist_legacy_report(
                db,
                parsed=parsed,
                content=content,
                content_type=content_type,
                actor_id=actor_id,
                account_generation=account_generation,
                settings=settings,
                key_manager=key_manager_provider() if key_manager_provider else key_manager,
                storage_readiness=storage_readiness,
            )
        )

    @router.post("/reports/v2", response_model=ReportCreationResponse, status_code=status.HTTP_201_CREATED)
    async def create_report_v2(
        metadata: str = Form(...),
        image: UploadFile = File(...),
        x_walksafe_actor_id: Optional[str] = Header(default=None, alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: Optional[str] = Header(
            default=None,
            alias="x-walksafe-account-generation",
        ),
        x_walksafe_report_id: Optional[str] = Header(
            default=None,
            alias="x-walksafe-report-id",
        ),
        x_walksafe_report_payload_sha256: Optional[str] = Header(
            default=None,
            alias="x-walksafe-report-payload-sha256",
        ),
        x_walksafe_report_payload_bytes: Optional[str] = Header(
            default=None,
            alias="x-walksafe-report-payload-bytes",
        ),
        db: Session = Depends(get_db),
    ) -> ReportCreationResponse:
        transport_contract = _resolve_report_transport_contract(
            x_walksafe_report_id,
            x_walksafe_report_payload_sha256,
            x_walksafe_report_payload_bytes,
        )
        transport_required = transport_contract is not None
        actor_id = _resolved_actor_id(
            x_walksafe_actor_id,
            required=settings.field_test_security_enabled or transport_required,
        )
        account_generation = _resolved_account_generation(
            x_walksafe_account_generation,
            actor_id=actor_id,
            required=settings.field_test_security_enabled or transport_required,
        )
        _enforce_metadata_size(metadata, settings)
        try:
            raw_payload = json.loads(metadata)
            parsed = ReportV2Metadata.model_validate(raw_payload)
        except (ValidationError, json.JSONDecodeError) as exc:
            raise HTTPException(
                status_code=422,
                detail=public_request_validation_detail(
                    exc, model=ReportV2Metadata, field="metadata", code="report_metadata_invalid",
                ),
            ) from None

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

        def validate_original(original: bytes) -> None:
            if transport_contract is not None:
                _validate_report_transport_payload(
                    transport_contract,
                    metadata_bytes=metadata.encode("utf-8"),
                    image_bytes=original,
                )
            if "bbox" in raw_payload:
                _reject_oriented_report_bbox(original)

        content, content_type = await read_image_upload(
            image,
            settings,
            validate_original=validate_original,
        )
        return await to_thread.run_sync(
            lambda: _persist_v2_report(
                db,
                parsed=parsed,
                content=content,
                content_type=content_type,
                actor_id=actor_id,
                account_generation=account_generation,
                settings=settings,
                key_manager=key_manager_provider() if key_manager_provider else key_manager,
                storage_readiness=storage_readiness,
                transport_contract=transport_contract,
            )
        )

    @router.get(
        "/reports/v2/{report_id}/status",
        response_model=ReportTransportStatusV1,
    )
    def get_report_transport_status(
        report_id: str,
        request: Request,
        response: Response,
        x_walksafe_actor_id: Optional[str] = Header(
            default=None,
            alias="x-walksafe-actor-id",
        ),
        x_walksafe_account_generation: Optional[str] = Header(
            default=None,
            alias="x-walksafe-account-generation",
        ),
        db: Session = Depends(get_db),
    ) -> ReportTransportStatusV1:
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        parsed_report_id = _parse_report_transport_status_id(report_id)
        actor_id, account_generation = _report_transport_status_actor(
            request,
            actor_header=x_walksafe_actor_id,
            account_generation_header=x_walksafe_account_generation,
            security_enabled=settings.field_test_security_enabled,
        )
        # S1617-50: a report accepted before a rotation stays under its subject.
        try:
            subject = resolve_privacy_subject(
                db,
                actor_id,
                account_generation,
                secret=settings.privacy_hmac_secret,
            ).subject
        except PrivacyLifecycleError as exc:
            raise HTTPException(
                status_code=exc.status_code,
                detail={"code": exc.code, "message": exc.message},
            ) from exc
        row = db.execute(
            _report_transport_status_statement(
                parsed_report_id,
                privacy_subject=subject,
                account_generation=account_generation,
            )
        ).one_or_none()
        if row is None:
            _report_transport_not_found()
        return _report_transport_status_response(row)

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
        result_fingerprint = _report_list_fingerprint(reports)
        response_snapshot = [report_to_response(report) for report in reports]
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
                    "result_count": len(response_snapshot),
                    "result_fingerprint_sha256": result_fingerprint,
                    "exact_location_filter_applied": lat is not None or lng is not None,
                },
            )
        return response_snapshot

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
        profile: Literal["internal", "minimum", "agency"] = Query(
            default="internal",
            description=(
                "internal is an admin export; redacted=true only rounds coordinates and clears image paths. "
                "minimum is a minimized public export, not an anonymized export."
            ),
        ),
        bom: bool = Query(default=False),
        manifest: bool = Query(default=False),
        bundle: bool = Query(default=False),
        aggregate: Optional[str] = Query(default=None),
        requested_audit_id: Optional[uuid.UUID] = Query(default=None, alias="audit_id"),
        x_walksafe_actor_id: Optional[str] = Header(default=None, alias="x-walksafe-actor-id"),
    ):
        return build_report_export(settings=settings,
            request=request,
            db=db,
            format=format,
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
            redacted=redacted,
            profile=profile,
            bom=bom,
            manifest=manifest,
            bundle=bundle,
            aggregate=aggregate,
            requested_audit_id=requested_audit_id,
            x_walksafe_actor_id=x_walksafe_actor_id,
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
        grid_size_degrees: float = Query(
            default=0.001,
            ge=REPORT_SUMMARY_MIN_GRID_SIZE_DEGREES,
            le=REPORT_SUMMARY_MAX_GRID_SIZE_DEGREES,
            description=_REPORT_SUMMARY_GRID_DESCRIPTION,
        ),
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
        summary = aggregate_report_summary(
            db,
            statement,
            grid_size_degrees=grid_size_degrees,
            top_limit=top_limit,
            grid_cell=_report_grid_cell,
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
        workflow_identity, proof = _require_admin_report_workflow_context(
            request,
            expected_action="report.original.grant",
            expected_read_purpose=None,
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
        if (
            identity.admin_id != workflow_identity.admin_id
            or identity.session_id != workflow_identity.session_id
            or identity.device_id != workflow_identity.device_id
        ):
            raise HTTPException(
                status_code=403,
                detail={
                    "code": "admin_device_proof_invalid",
                    "message": "The administrator device proof does not match this request.",
                },
            )
        try:
            grant = issue_report_original_access_grant(
                db,
                report_id=report_id,
                purpose=payload.purpose,
                reason=payload.reason,
                expected_content_revision=payload.expected_content_revision,
                identity=identity,
                ttl_seconds=settings.report_original_grant_ttl_seconds,
                key_manager=key_manager_provider() if key_manager_provider else key_manager,
                proof_challenge_id=proof.challenge_id,
                proof_request_body=proof.request_body,
                reconfirmation_nonce_sha256=hashlib.sha256(
                    request.headers[
                        ADMIN_RECONFIRM_NONCE_HEADER_NAME
                    ].strip().encode("ascii")
                ).hexdigest(),
                runtime_totp_secret=getattr(settings, "admin_totp_secret", None),
            )
        except ReportOriginalAccessError as exc:
            raise HTTPException(
                status_code=exc.status_code,
                detail={"code": exc.code, "message": exc.message},
            ) from exc
        return ReportOriginalAccessGrantResponse(
            schema_version="walksafe.report-original-access-grant.v2",
            grant_id=grant.grant_id,
            content_revision=grant.content_revision,
            expires_at=grant.expires_at,
            exact_location={
                "lat": grant.latitude,
                "lon": grant.longitude,
                "accuracy": grant.accuracy_m,
            },
            image={
                "resource_path": grant.resource_path,
                "content_type": grant.content_type,
                "sha256": grant.image_sha256,
                "byte_count": grant.image_byte_count,
                "access_token": grant.access_token,
            },
        )

    @router.post(
        "/reports/{report_id}/review-decisions",
        response_model=ReportReviewDecisionResponse,
        status_code=status.HTTP_201_CREATED,
        responses={
            status.HTTP_201_CREATED: {
                "description": (
                    "Created review decision or exact idempotent replay of the "
                    "original decision."
                )
            },
            status.HTTP_409_CONFLICT: {
                "description": (
                    "Review workflow conflict, including "
                    "review_decision_idempotency_conflict when decision_id is "
                    "reused for a different payload, report, or administrator."
                )
            },
        },
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
                proof_challenge_id=proof.challenge_id,
                proof_request_body=proof.request_body,
                runtime_totp_secret=getattr(settings, "admin_totp_secret", None),
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

    @router.get(
        "/reports/{report_id}/review-decisions/history",
        response_model=ReportReviewDecisionPageV1,
    )
    def get_report_review_decision_history(
        report_id: Annotated[str, ApiPath(
            min_length=36,
            max_length=36,
            pattern=_CANONICAL_HISTORY_UUID_PATTERN,
        )],
        request: Request,
        response: Response,
        limit: int | None = Query(
            default=None,
            ge=1,
            le=HISTORY_PAGE_MAX_LIMIT,
            description="Defaults to 10 for full history, or 25 with position=last.",
        ),
        cursor: str | None = Query(
            default=None,
            min_length=1,
            max_length=1024,
            pattern=r"^[A-Za-z0-9_-]+$",
        ),
        position: str | None = Query(default=None),
        db: Session = Depends(get_db),
    ) -> ReportReviewDecisionPageV1:
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        try:
            identity, proof = _require_admin_report_workflow_context(
                request,
                expected_action=None,
                expected_read_purpose="report.review_decisions",
            )
        except HTTPException as exc:
            raise _with_admin_history_no_store(exc) from exc
        parsed_report_id = uuid.UUID(report_id)
        if (
            not _admin_history_query_shape_allowed(request)
            or position not in (None, "last")
            or (position == "last" and cursor is not None)
        ):
            _raise_admin_report_workflow_error(
                AdminReportWorkflowError(
                    "report_review_history_query_invalid",
                    "The report review history query is invalid.",
                    status_code=422,
                ),
                request=request,
                identity=identity,
                proof=proof,
                audit_action="report.review_decisions.read",
                response_headers=_ADMIN_HISTORY_NO_STORE_HEADERS,
            )
        try:
            page = list_report_review_decision_history_page(
                db,
                report_id=parsed_report_id,
                limit=_report_history_limit(limit, position),
                cursor=cursor,
                position=position,
            )
        except AdminReportWorkflowError as exc:
            _raise_admin_report_workflow_error(
                exc,
                request=request,
                identity=identity,
                proof=proof,
                audit_action="report.review_decisions.read",
                response_headers=_ADMIN_HISTORY_NO_STORE_HEADERS,
            )
        try:
            persist_report_read_audit(
                db,
                actor_id=identity.admin_id,
                purpose="report.review_decisions",
                resource_type="report_detail",
                resource_id=report_id,
                details={
                    "view": "review_decision_history_page",
                    "result_count": len(page.items),
                    "total_count": page.total_count,
                    "snapshot_revision": page.snapshot_revision,
                    "position": position,
                    "session_id": str(identity.session_id),
                    "device_id": identity.device_id,
                    "correlation_id": str(proof.correlation_id),
                },
            )
        except HTTPException as exc:
            raise _with_admin_history_no_store(exc) from exc
        return page

    # S18-36: declared after /history so that literal segment keeps its route.
    @router.get(
        "/reports/{report_id}/review-decisions/{decision_id}",
        response_model=ReportReviewDecisionResponse,
        responses={
            status.HTTP_404_NOT_FOUND: {
                "description": (
                    "report_not_found, or review_decision_not_found when no "
                    "decision with this decision_id was recorded for the report "
                    "by this administrator."
                )
            },
        },
    )
    def get_report_review_decision_by_id(
        report_id: Annotated[str, ApiPath(
            min_length=36,
            max_length=36,
            pattern=_CANONICAL_HISTORY_UUID_PATTERN,
        )],
        decision_id: Annotated[str, ApiPath(
            min_length=36,
            max_length=36,
            pattern=_CANONICAL_HISTORY_UUID_PATTERN,
        )],
        request: Request,
        response: Response,
        db: Session = Depends(get_db),
    ) -> ReportReviewDecisionResponse:
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        identity, proof = _require_admin_report_workflow_context(
            request,
            expected_action=None,
            expected_read_purpose="report.review_decisions",
        )
        try:
            decision = get_report_review_decision(
                db,
                report_id=uuid.UUID(report_id),
                decision_id=uuid.UUID(decision_id),
                admin_id=identity.admin_id,
            )
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
            resource_id=report_id,
            details={
                "view": "review_decision_lookup",
                "decision_id": decision_id,
                "session_id": str(identity.session_id),
                "device_id": identity.device_id,
                "correlation_id": str(proof.correlation_id),
            },
        )
        return decision

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
            event = append_report_institution_delivery_event(
                db,
                report_id=report_id,
                payload=payload,
                identity=identity,
                correlation_id=proof.correlation_id,
                proof_challenge_id=proof.challenge_id,
                proof_request_body=proof.request_body,
                runtime_totp_secret=getattr(settings, "admin_totp_secret", None),
            )
            # S18-40-F7: an idempotent replay (EXISTING) may name an event that
            # was corrected since; the response carries that correction.
            if isinstance(event, ReportInstitutionDeliveryEvent):
                return delivery_items_with_corrections(db, [event])[0]
            return event
        except AdminReportWorkflowError as exc:
            _raise_admin_report_workflow_error(
                exc,
                request=request,
                identity=identity,
                proof=proof,
                audit_action="report.delivery.create",
            )

    @router.post(
        "/reports/{report_id}/delivery-corrections",
        response_model=ReportInstitutionDeliveryCorrectionV1,
        status_code=status.HTTP_201_CREATED,
        openapi_extra=admin_reconfirmation_openapi_extra,
    )
    def create_report_institution_delivery_correction(
        report_id: uuid.UUID,
        payload: ReportInstitutionDeliveryCorrectionRequest,
        request: Request,
        response: Response,
        db: Session = Depends(get_db),
    ) -> ReportInstitutionDeliveryCorrectionV1:
        """S18-40: HIGH-risk append-only correction of a delivery event."""

        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        identity, proof = _require_admin_report_workflow_context(
            request,
            expected_action="report.delivery.correct",
            expected_read_purpose=None,
        )
        nonce = request.headers.get(ADMIN_RECONFIRM_NONCE_HEADER_NAME, "").strip()
        try:
            return correct_report_institution_delivery_event(
                db,
                report_id=report_id,
                payload=payload,
                identity=identity,
                correlation_id=proof.correlation_id,
                proof_challenge_id=proof.challenge_id,
                proof_request_body=proof.request_body,
                reconfirmation_nonce_sha256=(
                    hashlib.sha256(nonce.encode("ascii", "replace")).hexdigest()
                    if nonce
                    else None
                ),
                runtime_totp_secret=getattr(settings, "admin_totp_secret", None),
            )
        except AdminReportWorkflowError as exc:
            _raise_admin_report_workflow_error(
                exc,
                request=request,
                identity=identity,
                proof=proof,
                audit_action="report.delivery.correct",
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
        # S18-40: each event carries the correction that names it, if any.
        return delivery_items_with_corrections(db, events)

    @router.get(
        "/reports/{report_id}/deliveries/history",
        response_model=ReportInstitutionDeliveryPageV1,
    )
    def get_report_institution_delivery_history(
        report_id: Annotated[str, ApiPath(
            min_length=36,
            max_length=36,
            pattern=_CANONICAL_HISTORY_UUID_PATTERN,
        )],
        request: Request,
        response: Response,
        limit: int | None = Query(
            default=None,
            ge=1,
            le=HISTORY_PAGE_MAX_LIMIT,
            description="Defaults to 10 for full history, or 25 with position=last.",
        ),
        cursor: str | None = Query(
            default=None,
            min_length=1,
            max_length=1024,
            pattern=r"^[A-Za-z0-9_-]+$",
        ),
        position: str | None = Query(default=None),
        db: Session = Depends(get_db),
    ) -> ReportInstitutionDeliveryPageV1:
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        try:
            identity, proof = _require_admin_report_workflow_context(
                request,
                expected_action=None,
                expected_read_purpose="report.delivery_events",
            )
        except HTTPException as exc:
            raise _with_admin_history_no_store(exc) from exc
        parsed_report_id = uuid.UUID(report_id)
        if (
            not _admin_history_query_shape_allowed(request)
            or position not in (None, "last")
            or (position == "last" and cursor is not None)
        ):
            _raise_admin_report_workflow_error(
                AdminReportWorkflowError(
                    "report_delivery_history_query_invalid",
                    "The report delivery history query is invalid.",
                    status_code=422,
                ),
                request=request,
                identity=identity,
                proof=proof,
                audit_action="report.delivery_events.read",
                response_headers=_ADMIN_HISTORY_NO_STORE_HEADERS,
            )
        try:
            page = list_report_institution_delivery_history_page(
                db,
                report_id=parsed_report_id,
                limit=_report_history_limit(limit, position),
                cursor=cursor,
                position=position,
            )
        except AdminReportWorkflowError as exc:
            _raise_admin_report_workflow_error(
                exc,
                request=request,
                identity=identity,
                proof=proof,
                audit_action="report.delivery_events.read",
                response_headers=_ADMIN_HISTORY_NO_STORE_HEADERS,
            )
        try:
            persist_report_read_audit(
                db,
                actor_id=identity.admin_id,
                purpose="report.delivery_events",
                resource_type="report_detail",
                resource_id=report_id,
                details={
                    "view": "delivery_history_page",
                    "result_count": len(page.items),
                    "total_count": page.total_count,
                    "snapshot_revision": page.snapshot_revision,
                    "position": position,
                    "session_id": str(identity.session_id),
                    "device_id": identity.device_id,
                    "correlation_id": str(proof.correlation_id),
                },
            )
        except HTTPException as exc:
            raise _with_admin_history_no_store(exc) from exc
        return page

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
        report = db.scalar(select(Report).where(
            Report.id == report_id, account_subject_visible(Report)
        ))
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
                select(Report).where(account_subject_visible(Report))
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

            transition = decide_report_status_transition(
                report.status, report.status_version, payload.status,
            )
            if transition.outcome == "unchanged":
                raise HTTPException(
                    status_code=409,
                    detail={
                        "code": "report_status_unchanged",
                        "message": "A status update must change the report status.",
                    },
                )

            if transition.outcome != "allowed":
                raise HTTPException(
                    status_code=422,
                    detail=f"status transition {report.status}->{payload.status} is not allowed",
                )

            previous_status = transition.previous_status
            previous_version = transition.previous_version
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
            report.status = transition.status
            report.status_version = transition.status_version
            report.payload = report_payload
            identity = getattr(request.state, "admin_security_identity", None)
            proof = getattr(request.state, "admin_device_proof", None)
            db.add(
                ReportStatusAudit(
                    report_id=report.id,
                    previous_status=previous_status,
                    next_status=payload.status,
                    previous_version=previous_version,
                    next_version=report.status_version,
                    actor_id=actor_id,
                    session_id=(
                        identity.session_id
                        if isinstance(identity, AdminSessionIdentity)
                        else None
                    ),
                    device_id=(
                        identity.device_id
                        if isinstance(identity, AdminSessionIdentity)
                        else None
                    ),
                    correlation_id=(
                        proof.correlation_id
                        if isinstance(proof, VerifiedAdminDeviceProof)
                        else None
                    ),
                    note=payload.note,
                    resolution_reason=payload.resolution_reason,
                )
            )
            db.commit()
            db.refresh(report)
            return report_to_response(report)

    return router
