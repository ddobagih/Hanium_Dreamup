"""Report ingestion application operations and shared report query policy; HTTP routes delegate here."""
from __future__ import annotations

from backend.app.services.privacy_visibility import account_subject_visible

from backend.app.services.report_commit import _commit_new_report_with_image
from dataclasses import dataclass
import hashlib
import io
import json
import logging
import math
import re
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any, Iterable, NoReturn, Optional
from fastapi import HTTPException, Request, status
from geoalchemy2.elements import WKTElement
from sqlalchemy import not_, or_, select, text
from sqlalchemy.orm import Session
from backend.app.config import Settings
from backend.app.field_test_security import FieldTestAccess, VerifiedActorAssertion
from backend.app.models import Report
from backend.app.schemas import DetectorSource, ReportCreationOutcome, ReportCreationResponse, ReportMetadata, ReportDemoFilter, ReportStatus, ReportTransportReceiptV1, ReportTransportStatusV1, ReportV2Metadata
from backend.app.services.duplicates import find_active_auto_report_cooldown, find_duplicate_candidates, find_duplicate_candidates_v2, lock_auto_report_cooldown, lock_duplicate_candidate_window
from backend.app.services.report_policy import report_v2_source
from backend.app.services.report_policy import HIGH_LOCATION_ACCURACY_M, LOW_CONFIDENCE_THRESHOLD
from backend.app.services.report_serialization import location_quality, report_to_response
from backend.app.services.storage_readiness_state import StorageReadinessState
from backend.app.services.privacy_lifecycle import PrivacyLifecycleError, assert_privacy_hmac_key_bound, assert_report_ingest_active, bind_or_verify_privacy_hmac_key, configured_privacy_hmac_lookup_keys, lock_report_ingest_transaction, privacy_subject_candidates, resolve_privacy_subject
from backend.app.services.report_image_keys import ReportImageKeyManager
from backend.app.services.admin_security import AdminSecurityError, authorize_admin_protected_work, record_admin_security_denial
from backend.app.uploads import image_suffix


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


AUTO_REPORT_MAX_AGE = timedelta(minutes=5)


AUTO_REPORT_MAX_FUTURE_SKEW = timedelta(seconds=30)


REPORT_TRANSPORT_PAYLOAD_DOMAIN = b"walksafe-report-payload-v1\0"


REPORT_TRANSPORT_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


REPORT_TRANSPORT_BYTES_PATTERN = re.compile(r"^[1-9][0-9]{0,18}$")


REPORT_TRANSPORT_MARKER = "DATABASE_AND_ENCRYPTED_IMAGE_STORE"


REPORT_TRANSPORT_USER_STATUS = {
    "new": "RECEIVED",
    "reviewed": "IN_REVIEW",
    "resolved": "COMPLETED",
}


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
    "location_kind",
}


ACTOR_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")


UNKNOWN_ACTOR_ID = "unknown"


@dataclass(frozen=True)
class ReportTransportContract:
    report_id: uuid.UUID
    payload_sha256: str
    payload_bytes: int


def report_transport_payload_sha256(
    report_id: uuid.UUID,
    metadata_bytes: bytes,
    image_bytes: bytes,
) -> str:
    digest = hashlib.sha256()
    digest.update(REPORT_TRANSPORT_PAYLOAD_DOMAIN)
    digest.update(str(report_id).encode("ascii"))
    digest.update(len(metadata_bytes).to_bytes(8, byteorder="big", signed=False))
    digest.update(metadata_bytes)
    digest.update(len(image_bytes).to_bytes(8, byteorder="big", signed=False))
    digest.update(image_bytes)
    return digest.hexdigest()


def _resolve_report_transport_contract(
    report_id_header: str | None,
    payload_sha256_header: str | None,
    payload_bytes_header: str | None,
) -> ReportTransportContract | None:
    values = (report_id_header, payload_sha256_header, payload_bytes_header)
    present = sum(value is not None for value in values)
    if present == 0:
        return None
    if present != len(values):
        raise HTTPException(
            status_code=422,
            detail={"code": "report_transport_headers_incomplete"},
        )
    assert report_id_header is not None
    assert payload_sha256_header is not None
    assert payload_bytes_header is not None
    try:
        report_id = uuid.UUID(report_id_header)
    except ValueError as exc:
        raise HTTPException(
            status_code=422,
            detail={"code": "report_transport_id_invalid"},
        ) from exc
    if str(report_id) != report_id_header:
        raise HTTPException(
            status_code=422,
            detail={"code": "report_transport_id_invalid"},
        )
    if REPORT_TRANSPORT_SHA256_PATTERN.fullmatch(payload_sha256_header) is None:
        raise HTTPException(
            status_code=422,
            detail={"code": "report_transport_payload_sha256_invalid"},
        )
    if REPORT_TRANSPORT_BYTES_PATTERN.fullmatch(payload_bytes_header) is None:
        raise HTTPException(
            status_code=422,
            detail={"code": "report_transport_payload_bytes_invalid"},
        )
    payload_bytes = int(payload_bytes_header)
    if payload_bytes > 9_223_372_036_854_775_807:
        raise HTTPException(
            status_code=422,
            detail={"code": "report_transport_payload_bytes_invalid"},
        )
    return ReportTransportContract(
        report_id=report_id,
        payload_sha256=payload_sha256_header,
        payload_bytes=payload_bytes,
    )


def _validate_report_transport_payload(
    contract: ReportTransportContract,
    *,
    metadata_bytes: bytes,
    image_bytes: bytes,
) -> None:
    actual_bytes = len(metadata_bytes) + len(image_bytes)
    actual_sha256 = report_transport_payload_sha256(
        contract.report_id,
        metadata_bytes,
        image_bytes,
    )
    if (
        contract.payload_bytes != actual_bytes
        or contract.payload_sha256 != actual_sha256
    ):
        raise HTTPException(
            status_code=422,
            detail={"code": "report_transport_payload_mismatch"},
        )


def _reject_oriented_report_bbox(original: bytes) -> None:
    # Pillow opens the bounded upload header without decoding its pixels. The
    # shared sanitizer later applies this orientation but discards the EXIF.
    from PIL import Image

    try:
        with Image.open(io.BytesIO(original)) as image:
            orientation = image.getexif().get(274, 1)
    except Exception as exc:
        raise HTTPException(
            status_code=400,
            detail={"code": "invalid_image_content", "message": "image must be decodable and sanitizable"},
        ) from exc
    if orientation in (2, 3, 4, 5, 6, 7, 8):
        raise HTTPException(
            status_code=422,
            detail={"code": "report_bbox_exif_orientation_unsupported"},
        )


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

    statement = select(Report).where(account_subject_visible(Report))
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


def _json_sha256(value: object) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _report_list_fingerprint(reports: Iterable[Report]) -> str:
    """SHA-256 of v1 domain + compact UTF-8 JSON [id, content_revision, status_version] rows sorted by ID.

    Hash only the returned page, not the full matching set. Sorting makes the
    fingerprint independent of incidental row order; versions capture edits
    without storing report IDs or content in the read audit.
    """
    rows = sorted(
        ([str(report.id), report.content_revision, report.status_version] for report in reports),
        key=lambda row: row[0],
    )
    payload = json.dumps(rows, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(b"walksafe-report-list-fingerprint-v1\0" + payload).hexdigest()


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
        # S1617-50: after a rotation the report joins the subject that already
        # owns the actor generation; every candidate is fenced and checked.
        resolved = resolve_privacy_subject(
            db,
            actor_id,
            account_generation,
            secret=settings.privacy_hmac_secret,
            key_version=settings.privacy_hmac_key_version,
        )
        return resolved.subject, account_generation
    except PrivacyLifecycleError as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail={"code": exc.code, "message": exc.message},
        ) from exc


def _privacy_report_candidates(
    *,
    privacy_subject: str | None,
    actor_id: str,
    account_generation: int | None,
    settings: Settings,
) -> tuple[str, ...]:
    """S1617-50: every subject to fence/check once lookup-only keys exist."""

    if (
        privacy_subject is None
        or account_generation is None
        or not configured_privacy_hmac_lookup_keys()
    ):
        return ()
    return privacy_subject_candidates(
        actor_id, account_generation, settings.privacy_hmac_secret
    )


def _assert_report_precommit_active(
    db: Session,
    *,
    privacy_subject: str,
    account_generation: int,
    settings: Settings,
    lookup_subjects: tuple[str, ...] = (),
) -> None:
    assert_privacy_hmac_key_bound(
        db,
        secret=settings.privacy_hmac_secret,
        key_version=settings.privacy_hmac_key_version,
    )
    assert_report_ingest_active(
        db, privacy_subject, account_generation, lookup_subjects or None
    )


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
    allow_stale_persisted_replay: bool = False,
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
        if not allow_stale_persisted_replay:
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
    *,
    creation_outcome: ReportCreationOutcome,
) -> ReportCreationResponse:
    internal = report_to_response(report, duplicate_report_ids)
    sanitized_metadata = dict(internal.metadata)
    sanitized_metadata.pop("duplicate_report_ids", None)
    return ReportCreationResponse.model_validate(
        {
            **internal.model_dump(),
            "metadata": sanitized_metadata,
            "duplicate_report_ids": [],
            "transport_receipt": _report_transport_receipt(report),
            "creation_outcome": creation_outcome,
        }
    )


def _postcommit_field_creation_response(
    report: Report,
    duplicate_report_ids: list[str],
    *,
    report_id: uuid.UUID,
) -> ReportCreationResponse:
    # Only call after the storage helper returns from a confirmed commit.
    # The caller's UUID remains usable even if response conversion expires ORM fields.
    try:
        return _field_creation_response(report, duplicate_report_ids, creation_outcome="CREATED")
    except Exception as exc:
        try:
            logging.getLogger(__name__).warning("report_postcommit_response_failed")
        except Exception:
            pass
        raise HTTPException(
            status_code=503,
            detail={
                "code": "report_postcommit_response_failed",
                "message": "The report was stored, but its confirmation response is unavailable.",
                "report_id": str(report_id),
            },
        ) from exc


def _report_transport_receipt(
    report: Report,
) -> ReportTransportReceiptV1 | None:
    if (
        report.client_payload_sha256 is None
        or report.client_payload_bytes is None
        or report.persistence_marker is None
    ):
        return None
    return ReportTransportReceiptV1(
        marker=REPORT_TRANSPORT_MARKER,
        report_id=report.id,
        persistence_marker=report.persistence_marker,
        payload_sha256=report.client_payload_sha256,
        payload_bytes=report.client_payload_bytes,
    )


def _report_transport_not_found() -> NoReturn:
    raise HTTPException(
        status_code=404,
        detail={"code": "report_transport_status_not_found"},
    )


def _report_transport_status_actor(
    request: Request,
    *,
    actor_header: str | None,
    account_generation_header: str | None,
    security_enabled: bool,
) -> tuple[str, int]:
    try:
        actor_id = _resolved_actor_id(actor_header, required=True)
        account_generation = _resolved_account_generation(
            account_generation_header,
            actor_id=actor_id,
            required=True,
        )
    except HTTPException:
        _report_transport_not_found()
    assert account_generation is not None
    verified = getattr(request.state, "verified_actor_assertion", None)
    if security_enabled and (
        not isinstance(verified, VerifiedActorAssertion)
        or verified.access is not FieldTestAccess.FIELD
        or verified.actor_id != actor_id
        or verified.account_generation != account_generation
    ):
        _report_transport_not_found()
    return actor_id, account_generation


def _parse_report_transport_status_id(value: str) -> uuid.UUID:
    try:
        report_id = uuid.UUID(value)
    except ValueError:
        _report_transport_not_found()
    if str(report_id) != value:
        _report_transport_not_found()
    return report_id


def _report_transport_status_response(row: object) -> ReportTransportStatusV1:
    return ReportTransportStatusV1(
        persistence_state="PERSISTED",
        user_status=REPORT_TRANSPORT_USER_STATUS[row.status],
        transport_receipt=ReportTransportReceiptV1(
            marker=REPORT_TRANSPORT_MARKER,
            report_id=row.id,
            persistence_marker=row.persistence_marker,
            payload_sha256=row.client_payload_sha256,
            payload_bytes=row.client_payload_bytes,
        ),
    )


def _report_transport_status_statement(
    report_id: uuid.UUID,
    *,
    privacy_subject: str,
    account_generation: int,
):
    return (
        select(
            Report.id,
            Report.status,
            Report.client_payload_sha256,
            Report.client_payload_bytes,
            Report.persistence_marker,
        )
        .where(Report.id == report_id, account_subject_visible(Report))
        .where(Report.privacy_subject_hmac == privacy_subject)
        .where(Report.account_generation == account_generation)
        .where(Report.client_payload_sha256.is_not(None))
        .where(Report.client_payload_bytes.is_not(None))
        .where(Report.persistence_marker.is_not(None))
    )


def _idempotent_report_replay(
    db: Session,
    *,
    actor_id: str,
    privacy_subject: str | None,
    account_generation: int | None,
    class_name: str,
    captured_at: datetime,
    image_sha256: str,
    payload_sha256: str,
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
    existing = db.scalar(
        select(Report).where(account_subject_visible(Report))
        .where(Report.class_name == class_name)
        .where(Report.captured_at == captured_at)
        .where(Report.privacy_subject_hmac == privacy_subject)
        .where(Report.account_generation == account_generation)
        .where(Report.payload.contains({
            "image_sha256": image_sha256,
        }))
        .order_by(Report.created_at.desc())
        .limit(1)
    )

    if existing is None:
        return None
    stored_payload = existing.payload
    if (
        not isinstance(stored_payload, dict)
        or stored_payload.get("payload_sha256") != payload_sha256
    ):
        db.rollback()
        raise HTTPException(
            status_code=409,
            detail={"code": "report_idempotency_conflict"},
        )
    return existing


def _report_transport_replay(
    db: Session,
    *,
    contract: ReportTransportContract,
    privacy_subject: str,
    account_generation: int,
) -> Report | None:
    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": f"walksafe-report-transport-v1\n{contract.report_id}"},
    )
    existing = db.scalar(select(Report).where(
        Report.id == contract.report_id, account_subject_visible(Report)
    ))
    if existing is None:
        return None
    if (
        existing.client_payload_sha256 == contract.payload_sha256
        and existing.client_payload_bytes == contract.payload_bytes
        and existing.persistence_marker is not None
        and existing.privacy_subject_hmac == privacy_subject
        and existing.account_generation == account_generation
    ):
        return existing
    db.rollback()
    raise HTTPException(
        status_code=409,
        detail={"code": "report_transport_id_conflict"},
    )


def _report_grid_cell(coordinate: float, grid_size_degrees: float) -> tuple[int, float, float]:
    # S20-38 contract: float half-open [lower, upper) when representable;
    # on a rounding contradiction the exact rational floor decides.
    quotient = coordinate / grid_size_degrees
    if math.isfinite(quotient):
        cell = math.floor(quotient)
        lower = cell * grid_size_degrees
        upper = (cell + 1) * grid_size_degrees
        if not (lower < upper and (coordinate < lower or coordinate >= upper)):
            return cell, lower, upper

    numerator, denominator = coordinate.as_integer_ratio()
    grid_numerator, grid_denominator = grid_size_degrees.as_integer_ratio()
    cell = (numerator * grid_denominator) // (denominator * grid_numerator)
    # Tiny cells may still have equal float bounds; do not widen the grid.
    return (
        cell,
        (cell * grid_numerator) / grid_denominator,
        ((cell + 1) * grid_numerator) / grid_denominator,
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
        lat_cell, min_latitude, max_latitude = _report_grid_cell(
            report.latitude, grid_size_degrees,
        )
        lng_cell, min_longitude, max_longitude = _report_grid_cell(
            report.longitude, grid_size_degrees,
        )
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
                    "min_latitude": min_latitude,
                    "max_latitude": max_latitude,
                    "min_longitude": min_longitude,
                    "max_longitude": max_longitude,
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
    storage_readiness: StorageReadinessState | None = None,
) -> ReportCreationResponse:
    privacy_subject, bound_generation = _privacy_report_binding(
        db=db,
        actor_id=actor_id,
        account_generation=account_generation,
        settings=settings,
    )
    subject_candidates = _privacy_report_candidates(
        privacy_subject=privacy_subject,
        actor_id=actor_id,
        account_generation=bound_generation,
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
                lookup_subjects=subject_candidates,
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
    stored_payload = _enrich_legacy_payload(
        parsed.model_dump(mode="json"),
        source=parsed.source,
        content=content,
        actor_id=actor_id,
    )
    replay = _idempotent_report_replay(
        db,
        actor_id=actor_id,
        privacy_subject=privacy_subject,
        account_generation=bound_generation,
        class_name=parsed.class_name,
        captured_at=parsed.captured_at,
        image_sha256=str(stored_payload["image_sha256"]),
        payload_sha256=str(stored_payload["payload_sha256"]),
    )
    if replay is not None:
        db.rollback()
        return _field_creation_response(replay, creation_outcome="REPLAYED")
    lock_duplicate_candidate_window(
        db,
        class_name=parsed.class_name,
        captured_at=parsed.captured_at,
        is_fake=parsed.source == "fake",
        latitude=latitude,
        longitude=longitude,
    )
    duplicate_candidates = find_duplicate_candidates(db, parsed)
    stored_payload = _with_duplicate_candidate_payload(stored_payload, duplicate_candidates)
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
    duplicate_candidate_ids = [str(candidate.id) for candidate in duplicate_candidates]
    _commit_new_report_with_image(
        db,
        report,
        destination=destination,
        content=content,
        content_type=content_type,
        key_manager=key_manager,
        storage_readiness=storage_readiness,
        maintenance_lock_path=settings.maintenance_lock_path,
        maintenance_lock_group_gid=settings.maintenance_lock_group_gid,
        precommit_check=(
            lambda: _assert_report_precommit_active(
                db,
                privacy_subject=privacy_subject,
                account_generation=bound_generation,
                settings=settings,
                lookup_subjects=subject_candidates,
            )
            if privacy_subject is not None and bound_generation is not None
            else None
        ),
    )
    return _postcommit_field_creation_response(
        report, duplicate_candidate_ids, report_id=report_id,
    )


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
    transport_contract: ReportTransportContract | None = None,
    storage_readiness: StorageReadinessState | None = None,
) -> ReportCreationResponse:
    privacy_subject, bound_generation = _privacy_report_binding(
        db=db,
        actor_id=actor_id,
        account_generation=account_generation,
        settings=settings,
    )
    subject_candidates = _privacy_report_candidates(
        privacy_subject=privacy_subject,
        actor_id=actor_id,
        account_generation=bound_generation,
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
                lookup_subjects=subject_candidates,
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
    if transport_contract is not None:
        assert privacy_subject is not None and bound_generation is not None
        replay = _report_transport_replay(
            db,
            contract=transport_contract,
            privacy_subject=privacy_subject,
            account_generation=bound_generation,
        )
        if replay is not None:
            db.rollback()
            return _field_creation_response(replay, creation_outcome="REPLAYED")
    else:
        # A content conflict must not be hidden by a cooldown receipt.
        replay = _idempotent_report_replay(
            db,
            actor_id=actor_id,
            privacy_subject=privacy_subject,
            account_generation=bound_generation,
            class_name=parsed.class_name,
            captured_at=parsed.captured_at,
            image_sha256=str(stored_payload["image_sha256"]),
            payload_sha256=str(stored_payload["payload_sha256"]),
        )
    # Only a persisted exact replay may outlive capture freshness; all other
    # auto-report security checks still run after account/consent locking.
    _enforce_secured_auto_report_gate(
        parsed,
        settings,
        actor_id=actor_id,
        allow_stale_persisted_replay=transport_contract is None and replay is not None,
    )
    if transport_contract is None and replay is not None:
        db.rollback()
        return _field_creation_response(replay, creation_outcome="REPLAYED")
    report_id = (
        transport_contract.report_id
        if transport_contract is not None
        else uuid.uuid4()
    )
    filename = f"{report_id}{image_suffix(content_type)}"
    destination = settings.upload_dir / f"{report_id}.wse"
    gps = parsed.gps
    latitude = gps.latitude if gps else None
    longitude = gps.longitude if gps else None
    location = WKTElement(f"POINT({longitude} {latitude})", srid=4326) if gps else None
    is_fake = _is_fake_payload(stored_payload, report_source)
    if (
        settings.field_test_security_enabled
        and parsed.auto_reported
    ):
        assert gps is not None
        assert privacy_subject is not None and bound_generation is not None
        lock_auto_report_cooldown(
            db,
            privacy_subject=privacy_subject,
            account_generation=bound_generation,
            class_name=parsed.class_name,
            is_fake=is_fake,
        )
        active_cooldown = find_active_auto_report_cooldown(
            db,
            privacy_subject=privacy_subject,
            account_generation=bound_generation,
            class_name=parsed.class_name,
            is_fake=is_fake,
            lat=gps.latitude,
            lng=gps.longitude,
            now=datetime.now(UTC),
        )
        if active_cooldown is not None:
            if transport_contract is not None:
                db.rollback()
                raise HTTPException(
                    status_code=429,
                    detail={
                        "code": "automatic_report_cooldown_active",
                        "creation_outcome": "SUPPRESSED_BY_COOLDOWN",
                    },
                    headers={"Retry-After": "600"},
                )
            # Preserve the legacy success status while identifying that no
            # second row or image was created.
            db.rollback()
            return _field_creation_response(active_cooldown, creation_outcome="SUPPRESSED_BY_COOLDOWN")
    lock_duplicate_candidate_window(
        db,
        class_name=parsed.class_name,
        captured_at=parsed.captured_at,
        is_fake=is_fake,
        latitude=latitude,
        longitude=longitude,
    )
    duplicate_candidates = find_duplicate_candidates_v2(db, parsed, is_fake=is_fake)
    stored_payload = _with_duplicate_candidate_payload(stored_payload, duplicate_candidates)
    report = Report(
        id=report_id,
        status="new",
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
        client_payload_sha256=(
            transport_contract.payload_sha256
            if transport_contract is not None
            else None
        ),
        client_payload_bytes=(
            transport_contract.payload_bytes
            if transport_contract is not None
            else None
        ),
        persistence_marker=(uuid.uuid4() if transport_contract is not None else None),
    )
    duplicate_candidate_ids = [str(candidate.id) for candidate in duplicate_candidates]
    _commit_new_report_with_image(
        db,
        report,
        destination=destination,
        content=content,
        content_type=content_type,
        key_manager=key_manager,
        storage_readiness=storage_readiness,
        maintenance_lock_path=settings.maintenance_lock_path,
        maintenance_lock_group_gid=settings.maintenance_lock_group_gid,
        precommit_check=(
            lambda: _assert_report_precommit_active(
                db,
                privacy_subject=privacy_subject,
                account_generation=bound_generation,
                settings=settings,
                lookup_subjects=subject_candidates,
            )
            if privacy_subject is not None and bound_generation is not None
            else None
        ),
    )
    return _postcommit_field_creation_response(
        report, duplicate_candidate_ids, report_id=report_id,
    )

