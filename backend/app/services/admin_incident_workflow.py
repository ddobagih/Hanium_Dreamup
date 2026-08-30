"""Record-only CRITICAL incident opening and administrator status history."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
import hashlib
import json
import re
from typing import NoReturn
import uuid

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import (
    AdminOperationAudit,
    CriticalIncident,
    CriticalIncidentEvent,
)
from backend.app.schemas import AdminIncidentStatusUpdateV1
from backend.app.services.admin_incident_projection import (
    allowed_next_incident_states,
)
from backend.app.services.admin_security import AdminSessionIdentity


_PRODUCER_SOURCE = "WALKSAFE_BACKEND"
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_REASON_CODES = frozenset(
    {
        "USER_SAFETY_RISK",
        "PERSONAL_DATA_BREACH",
        "DELETION_INTEGRITY_FAILURE",
        "CORE_SERVICE_TOTAL_OUTAGE",
        "IRREVERSIBLE_DATA_LOSS",
    }
)


class CriticalIncidentWorkflowError(RuntimeError):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int,
        latest: dict[str, object] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.latest = latest


@dataclass(frozen=True, slots=True)
class CriticalIncidentStatusSnapshot:
    incident_id: uuid.UUID
    status: str
    status_version: int
    updated_at: datetime


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("critical incident timestamps must include a timezone")
    return value.astimezone(UTC)


def _utc_text(value: datetime) -> str:
    return _as_utc(value).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _normalized_text(value: str, *, minimum: int, maximum: int) -> str:
    if not isinstance(value, str):
        raise ValueError("critical incident text is required")
    normalized = value.strip()
    if (
        not minimum <= len(normalized) <= maximum
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in normalized)
    ):
        raise ValueError("critical incident text is outside the safe boundary")
    return normalized


def _sha256(payload: dict[str, object]) -> str:
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _opening_intent_sha256(
    *,
    incident_id: uuid.UUID,
    reason_code: str,
    summary: str,
    started_at: datetime,
    detected_at: datetime,
    reason: str,
    observation: str,
    evidence_sha256: str,
) -> str:
    return _sha256(
        {
            "evidence_sha256": evidence_sha256,
            "incident_id": str(incident_id),
            "observation": observation,
            "producer_source": _PRODUCER_SOURCE,
            "reason": reason,
            "reason_code": reason_code,
            "started_at": _utc_text(started_at),
            "detected_at": _utc_text(detected_at),
            "summary": summary,
            "v": 1,
        }
    )


def _status_intent_sha256(
    incident_id: uuid.UUID,
    payload: AdminIncidentStatusUpdateV1,
) -> str:
    return _sha256(
        {
            "evidence_sha256": payload.evidence_sha256,
            "expected_version": payload.expected_version,
            "idempotency_key": str(payload.idempotency_key),
            "incident_id": str(incident_id),
            "next_state": payload.next_state,
            "observation": payload.observation,
            "reason": payload.reason,
            "v": 1,
        }
    )


def _rollback(db: Session) -> None:
    try:
        db.rollback()
    except SQLAlchemyError as exc:
        raise CriticalIncidentWorkflowError(
            "critical_incident_store_unavailable",
            "The critical incident store is temporarily unavailable.",
            status_code=503,
        ) from exc


def _store_unavailable(db: Session, exc: SQLAlchemyError) -> NoReturn:
    try:
        db.rollback()
    except SQLAlchemyError:
        pass
    raise CriticalIncidentWorkflowError(
        "critical_incident_store_unavailable",
        "The critical incident store is temporarily unavailable.",
        status_code=503,
    ) from exc


def _locked_incident(db: Session, incident_id: uuid.UUID) -> CriticalIncident:
    try:
        incident = db.execute(
            select(CriticalIncident)
            .where(CriticalIncident.id == incident_id)
            .with_for_update()
        ).scalar_one_or_none()
    except SQLAlchemyError as exc:
        _store_unavailable(db, exc)
    if incident is None:
        _rollback(db)
        raise CriticalIncidentWorkflowError(
            "incident_not_found",
            "The critical incident was not found.",
            status_code=404,
        )
    return incident


def _latest(incident: CriticalIncident) -> dict[str, object]:
    return {
        "status": incident.status,
        "status_version": incident.status_version,
        "allowed_next_states": list(allowed_next_incident_states(incident.status)),
    }


def _status_snapshot(event: CriticalIncidentEvent) -> CriticalIncidentStatusSnapshot:
    return CriticalIncidentStatusSnapshot(
        incident_id=event.incident_id,
        status=event.next_state,
        status_version=event.revision,
        updated_at=event.recorded_at,
    )


def _status_intent_matches(
    event: CriticalIncidentEvent,
    payload: AdminIncidentStatusUpdateV1,
    *,
    intent_sha256: str,
) -> bool:
    return (
        event.revision > 1
        and event.expected_version == payload.expected_version
        and event.next_state == payload.next_state
        and event.idempotency_key == payload.idempotency_key
        and event.reason == payload.reason
        and event.observation == payload.observation
        and event.evidence_sha256 == payload.evidence_sha256
        and event.intent_sha256 == intent_sha256
    )


def _add_success_audit(
    db: Session,
    *,
    identity: AdminSessionIdentity,
    correlation_id: uuid.UUID,
    incident_id: uuid.UUID,
    query_sha256: str,
    recorded_at: datetime,
) -> None:
    db.add(
        AdminOperationAudit(
            actor_id=identity.admin_id,
            session_id=identity.session_id,
            device_id=identity.device_id,
            correlation_id=correlation_id,
            operation="admin.incident.status.update",
            outcome="SUCCEEDED",
            resource_type="critical_incident",
            resource_id=str(incident_id),
            query_sha256=query_sha256,
            result_count=1,
            error_code=None,
            created_at=recorded_at,
        )
    )


def _commit(db: Session) -> None:
    try:
        db.commit()
    except SQLAlchemyError as exc:
        _store_unavailable(db, exc)


def record_critical_incident(
    db: Session,
    *,
    incident_id: uuid.UUID,
    reason_code: str,
    summary: str,
    started_at: datetime,
    detected_at: datetime,
    reason: str,
    observation: str,
    evidence_sha256: str,
    now: datetime | None = None,
) -> tuple[CriticalIncident, bool]:
    """Record one trusted in-process opening event; no public route calls this."""

    if not isinstance(incident_id, uuid.UUID) or reason_code not in _REASON_CODES:
        raise ValueError("critical incident identity or reason code is invalid")
    normalized_summary = _normalized_text(summary, minimum=1, maximum=200)
    normalized_reason = _normalized_text(reason, minimum=8, maximum=500)
    normalized_observation = _normalized_text(
        observation,
        minimum=8,
        maximum=500,
    )
    if _SHA256_PATTERN.fullmatch(evidence_sha256) is None:
        raise ValueError("critical incident evidence must be lowercase SHA-256")
    normalized_started_at = _as_utc(started_at)
    normalized_detected_at = _as_utc(detected_at)
    recorded_at = _as_utc(now or datetime.now(UTC))
    if not normalized_started_at <= normalized_detected_at <= recorded_at:
        raise ValueError("critical incident timestamps are out of order")
    intent_sha256 = _opening_intent_sha256(
        incident_id=incident_id,
        reason_code=reason_code,
        summary=normalized_summary,
        started_at=normalized_started_at,
        detected_at=normalized_detected_at,
        reason=normalized_reason,
        observation=normalized_observation,
        evidence_sha256=evidence_sha256,
    )
    try:
        existing = db.execute(
            select(CriticalIncident)
            .where(CriticalIncident.id == incident_id)
            .with_for_update()
        ).scalar_one_or_none()
    except SQLAlchemyError as exc:
        _store_unavailable(db, exc)
    if existing is not None:
        if existing.opening_intent_sha256 == intent_sha256:
            try:
                db.expunge(existing)
            except SQLAlchemyError as exc:
                _store_unavailable(db, exc)
            _rollback(db)
            return existing, False
        _rollback(db)
        raise CriticalIncidentWorkflowError(
            "incident_opening_conflict",
            "The stable incident identifier is bound to another opening record.",
            status_code=409,
        )

    incident = CriticalIncident(
        id=incident_id,
        producer_source=_PRODUCER_SOURCE,
        opening_intent_sha256=intent_sha256,
        severity="CRITICAL",
        status="OPEN",
        status_version=1,
        reason_code=reason_code,
        summary=normalized_summary,
        started_at=normalized_started_at,
        detected_at=normalized_detected_at,
        created_at=recorded_at,
        updated_at=recorded_at,
    )
    event = CriticalIncidentEvent(
        id=uuid.uuid5(incident_id, "walksafe-critical-incident-opened-v1"),
        incident_id=incident_id,
        revision=1,
        event_type="OPENED",
        previous_state=None,
        next_state="OPEN",
        expected_version=0,
        idempotency_key=incident_id,
        intent_sha256=intent_sha256,
        reason=normalized_reason,
        observation=normalized_observation,
        evidence_sha256=evidence_sha256,
        observed_at=normalized_detected_at,
        recorded_at=recorded_at,
        actor_id=None,
        session_id=None,
        device_id=None,
        correlation_id=None,
    )
    db.add(incident)
    db.add(event)
    try:
        db.commit()
    except IntegrityError as exc:
        _rollback(db)
        try:
            existing = db.get(CriticalIncident, incident_id)
        except SQLAlchemyError as query_exc:
            _store_unavailable(db, query_exc)
        if existing is not None and existing.opening_intent_sha256 == intent_sha256:
            try:
                db.expunge(existing)
            except SQLAlchemyError as query_exc:
                _store_unavailable(db, query_exc)
            _rollback(db)
            return existing, False
        _rollback(db)
        raise CriticalIncidentWorkflowError(
            "incident_opening_conflict",
            "The stable incident identifier is bound to another opening record.",
            status_code=409,
        ) from exc
    except SQLAlchemyError as exc:
        _store_unavailable(db, exc)
    return incident, True


def update_critical_incident_status(
    db: Session,
    *,
    incident_id: uuid.UUID,
    payload: AdminIncidentStatusUpdateV1,
    identity: AdminSessionIdentity,
    correlation_id: uuid.UUID,
    query_sha256: str,
    now: datetime | None = None,
) -> CriticalIncidentStatusSnapshot:
    """Append one operator observation; it never performs recovery or control."""

    incident = _locked_incident(db, incident_id)
    intent_sha256 = _status_intent_sha256(incident_id, payload)
    try:
        existing = db.execute(
            select(CriticalIncidentEvent).where(
                CriticalIncidentEvent.incident_id == incident_id,
                CriticalIncidentEvent.idempotency_key == payload.idempotency_key,
            )
        ).scalar_one_or_none()
    except SQLAlchemyError as exc:
        _store_unavailable(db, exc)
    if existing is not None:
        if not _status_intent_matches(
            existing,
            payload,
            intent_sha256=intent_sha256,
        ):
            latest = _latest(incident)
            _rollback(db)
            raise CriticalIncidentWorkflowError(
                "incident_status_version_conflict",
                "The incident state or idempotency binding conflicts with this update.",
                status_code=409,
                latest=latest,
            )
        _add_success_audit(
            db,
            identity=identity,
            correlation_id=correlation_id,
            incident_id=incident_id,
            query_sha256=query_sha256,
            recorded_at=_as_utc(now or datetime.now(UTC)),
        )
        _commit(db)
        return _status_snapshot(existing)

    latest = _latest(incident)
    if payload.expected_version != incident.status_version:
        _rollback(db)
        raise CriticalIncidentWorkflowError(
            "incident_status_version_conflict",
            "The critical incident changed before this update was applied.",
            status_code=409,
            latest=latest,
        )
    if payload.next_state not in allowed_next_incident_states(incident.status):
        _rollback(db)
        raise CriticalIncidentWorkflowError(
            "incident_status_transition_invalid",
            "The critical incident status transition is not allowed.",
            status_code=422,
            latest=latest,
        )
    if incident.status_version >= 256:
        _rollback(db)
        raise CriticalIncidentWorkflowError(
            "incident_status_version_conflict",
            "The v1 incident event history cannot accept another update.",
            status_code=409,
            latest=latest,
        )

    changed_at = _as_utc(now or datetime.now(UTC))
    current_updated_at = _as_utc(incident.updated_at)
    if changed_at <= current_updated_at:
        changed_at = current_updated_at + timedelta(microseconds=1)
    previous_state = incident.status
    previous_version = incident.status_version
    incident.status = payload.next_state
    incident.status_version = previous_version + 1
    incident.updated_at = changed_at
    event = CriticalIncidentEvent(
        incident_id=incident_id,
        revision=incident.status_version,
        event_type=payload.next_state,
        previous_state=previous_state,
        next_state=payload.next_state,
        expected_version=payload.expected_version,
        idempotency_key=payload.idempotency_key,
        intent_sha256=intent_sha256,
        reason=payload.reason,
        observation=payload.observation,
        evidence_sha256=payload.evidence_sha256,
        observed_at=changed_at,
        recorded_at=changed_at,
        actor_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=correlation_id,
    )
    db.add(event)
    _add_success_audit(
        db,
        identity=identity,
        correlation_id=correlation_id,
        incident_id=incident_id,
        query_sha256=query_sha256,
        recorded_at=changed_at,
    )
    try:
        db.commit()
    except IntegrityError as exc:
        _rollback(db)
        try:
            existing_after_conflict = db.execute(
                select(CriticalIncidentEvent).where(
                    CriticalIncidentEvent.incident_id == incident_id,
                    CriticalIncidentEvent.idempotency_key == payload.idempotency_key,
                )
            ).scalar_one_or_none()
            current = db.get(CriticalIncident, incident_id)
        except SQLAlchemyError as query_exc:
            _store_unavailable(db, query_exc)
        if existing_after_conflict is not None and _status_intent_matches(
            existing_after_conflict,
            payload,
            intent_sha256=intent_sha256,
        ):
            _add_success_audit(
                db,
                identity=identity,
                correlation_id=correlation_id,
                incident_id=incident_id,
                query_sha256=query_sha256,
                recorded_at=_as_utc(now or datetime.now(UTC)),
            )
            _commit(db)
            return _status_snapshot(existing_after_conflict)
        latest_after_conflict = _latest(current) if current is not None else None
        _rollback(db)
        raise CriticalIncidentWorkflowError(
            "incident_status_version_conflict",
            "The critical incident changed before this update was applied.",
            status_code=409,
            latest=latest_after_conflict,
        ) from exc
    except SQLAlchemyError as exc:
        _store_unavailable(db, exc)
    return _status_snapshot(event)


__all__ = [
    "CriticalIncidentStatusSnapshot",
    "CriticalIncidentWorkflowError",
    "record_critical_incident",
    "update_critical_incident_status",
]
