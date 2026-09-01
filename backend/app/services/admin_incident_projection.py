"""Minimum administrator projection for record-only CRITICAL incidents."""

from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import UTC, datetime
import hashlib
import json
import re
import uuid

from sqlalchemy import and_, or_

from backend.app.models import CriticalIncident, CriticalIncidentEvent
from backend.app.schemas import (
    AdminIncidentDetailV1,
    AdminIncidentEventV1,
    AdminIncidentSummaryV1,
)


_CURSOR_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,1024}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_TRANSITIONS: dict[str, tuple[str, ...]] = {
    "OPEN": ("ACKNOWLEDGED",),
    "ACKNOWLEDGED": ("RESOLVED",),
    "RESOLVED": ("REOPENED",),
    "REOPENED": ("ACKNOWLEDGED",),
}


class AdminIncidentCursorError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class AdminIncidentFilters:
    status: str | None


@dataclass(frozen=True, slots=True)
class AdminIncidentCursor:
    detected_at: datetime
    incident_id: uuid.UUID


def allowed_next_incident_states(status: str) -> tuple[str, ...]:
    try:
        return _TRANSITIONS[status]
    except KeyError as exc:
        raise ValueError("unknown critical incident status") from exc


def _utc_text(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must include a timezone")
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def admin_incident_filter_sha256(filters: AdminIncidentFilters) -> str:
    canonical = json.dumps(
        {"status": filters.status},
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("ascii")
    return hashlib.sha256(canonical).hexdigest()


def encode_admin_incident_cursor(
    *,
    detected_at: datetime,
    incident_id: uuid.UUID,
    filter_sha256: str,
) -> str:
    if _SHA256_PATTERN.fullmatch(filter_sha256) is None:
        raise ValueError("filter_sha256 must be lowercase SHA-256")
    payload = {
        "detected_at": _utc_text(detected_at),
        "filter_sha256": filter_sha256,
        "id": str(incident_id),
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
            raise AdminIncidentCursorError("administrator incident cursor is invalid")
        result[key] = value
    return result


def decode_admin_incident_cursor(
    value: str,
    *,
    expected_filter_sha256: str,
) -> AdminIncidentCursor:
    if (
        not isinstance(value, str)
        or _CURSOR_PATTERN.fullmatch(value) is None
        or _SHA256_PATTERN.fullmatch(expected_filter_sha256) is None
    ):
        raise AdminIncidentCursorError("administrator incident cursor is invalid")
    try:
        padded = value + "=" * ((4 - len(value) % 4) % 4)
        decoded = base64.b64decode(
            padded.encode("ascii"),
            altchars=b"-_",
            validate=True,
        )
        payload = json.loads(
            decoded.decode("ascii"),
            object_pairs_hook=_strict_object,
        )
    except (UnicodeError, ValueError, json.JSONDecodeError) as exc:
        raise AdminIncidentCursorError(
            "administrator incident cursor is invalid"
        ) from exc
    if not isinstance(payload, dict) or set(payload) != {
        "detected_at",
        "filter_sha256",
        "id",
        "v",
    }:
        raise AdminIncidentCursorError("administrator incident cursor is invalid")
    if payload["v"] != 1 or payload["filter_sha256"] != expected_filter_sha256:
        raise AdminIncidentCursorError(
            "administrator incident cursor does not match the current filters"
        )
    if not isinstance(payload["detected_at"], str) or not isinstance(
        payload["id"], str
    ):
        raise AdminIncidentCursorError("administrator incident cursor is invalid")
    try:
        detected_at = datetime.fromisoformat(
            payload["detected_at"].replace("Z", "+00:00")
        )
        incident_id = uuid.UUID(payload["id"])
    except ValueError as exc:
        raise AdminIncidentCursorError(
            "administrator incident cursor is invalid"
        ) from exc
    if (
        _utc_text(detected_at) != payload["detected_at"]
        or str(incident_id) != payload["id"]
        or encode_admin_incident_cursor(
            detected_at=detected_at,
            incident_id=incident_id,
            filter_sha256=expected_filter_sha256,
        )
        != value
    ):
        raise AdminIncidentCursorError("administrator incident cursor is invalid")
    return AdminIncidentCursor(detected_at=detected_at, incident_id=incident_id)


def admin_incident_cursor_predicate(
    detected_at: datetime,
    incident_id: uuid.UUID,
):
    return or_(
        CriticalIncident.detected_at < detected_at,
        and_(
            CriticalIncident.detected_at == detected_at,
            CriticalIncident.id < incident_id,
        ),
    )


def project_admin_incident_summary(
    incident: CriticalIncident,
) -> AdminIncidentSummaryV1:
    return AdminIncidentSummaryV1(
        incident_id=incident.id,
        severity=incident.severity,
        status=incident.status,
        status_version=incident.status_version,
        reason_code=incident.reason_code,
        summary=incident.summary,
        started_at=incident.started_at,
        detected_at=incident.detected_at,
        updated_at=incident.updated_at,
    )


def project_admin_incident_summary_at_event(
    incident: CriticalIncident,
    event: CriticalIncidentEvent,
) -> AdminIncidentSummaryV1:
    if (
        event.incident_id != incident.id
        or event.revision < 1
        or event.next_state not in _TRANSITIONS
    ):
        raise ValueError("critical incident history snapshot is invalid")
    return AdminIncidentSummaryV1(
        incident_id=incident.id,
        severity=incident.severity,
        status=event.next_state,
        status_version=event.revision,
        reason_code=incident.reason_code,
        summary=incident.summary,
        started_at=incident.started_at,
        detected_at=incident.detected_at,
        updated_at=event.recorded_at,
    )


def validate_admin_incident_event_page(
    incident: CriticalIncident,
    events: list[CriticalIncidentEvent],
    *,
    after_event: CriticalIncidentEvent | None,
) -> None:
    if not events:
        raise ValueError("critical incident history page is empty")
    expected_revision = 1 if after_event is None else after_event.revision + 1
    previous_state = None if after_event is None else after_event.next_state
    for event in events:
        if event.incident_id != incident.id or event.revision != expected_revision:
            raise ValueError("critical incident event revisions are not contiguous")
        if event.revision == 1:
            if (
                event.event_type != "OPENED"
                or event.previous_state is not None
                or event.next_state != "OPEN"
                or event.actor_id is not None
            ):
                raise ValueError("critical incident opening event is invalid")
        elif (
            event.previous_state != previous_state
            or event.next_state
            not in allowed_next_incident_states(event.previous_state)
            or event.event_type != event.next_state
            or event.actor_id is None
        ):
            raise ValueError("critical incident event transition is invalid")
        previous_state = event.next_state
        expected_revision += 1


def project_admin_incident_event(
    event: CriticalIncidentEvent,
) -> AdminIncidentEventV1:
    return AdminIncidentEventV1(
        event_id=event.id,
        revision=event.revision,
        event_type=event.event_type,
        previous_state=event.previous_state,
        next_state=event.next_state,
        reason=event.reason,
        observation=event.observation,
        evidence_sha256=event.evidence_sha256,
        observed_at=event.observed_at,
        recorded_at=event.recorded_at,
        actor_id=event.actor_id,
    )


def project_admin_incident_detail(
    incident: CriticalIncident,
    events: list[CriticalIncidentEvent],
) -> AdminIncidentDetailV1:
    if not 1 <= len(events) <= 256:
        raise ValueError("critical incident event history is incomplete")
    previous_state: str | None = None
    for index, event in enumerate(events, start=1):
        if event.incident_id != incident.id or event.revision != index:
            raise ValueError("critical incident event revisions are not contiguous")
        if index == 1:
            if (
                event.event_type != "OPENED"
                or event.previous_state is not None
                or event.next_state != "OPEN"
                or event.actor_id is not None
            ):
                raise ValueError("critical incident opening event is invalid")
        elif (
            event.previous_state != previous_state
            or event.next_state
            not in allowed_next_incident_states(event.previous_state)
            or event.event_type != event.next_state
            or event.actor_id is None
        ):
            raise ValueError("critical incident event transition is invalid")
        previous_state = event.next_state
    latest = events[-1]
    if (
        latest.revision != incident.status_version
        or latest.next_state != incident.status
        or latest.recorded_at != incident.updated_at
    ):
        raise ValueError("critical incident projection does not match its history")
    summary = project_admin_incident_summary(incident)
    return AdminIncidentDetailV1(
        schema_version="walksafe.admin-incident-detail.v1",
        **summary.model_dump(),
        allowed_next_states=list(allowed_next_incident_states(incident.status)),
        events=[project_admin_incident_event(event) for event in events],
    )


__all__ = [
    "AdminIncidentCursorError",
    "AdminIncidentFilters",
    "admin_incident_cursor_predicate",
    "admin_incident_filter_sha256",
    "allowed_next_incident_states",
    "decode_admin_incident_cursor",
    "encode_admin_incident_cursor",
    "project_admin_incident_detail",
    "project_admin_incident_event",
    "project_admin_incident_summary",
    "project_admin_incident_summary_at_event",
    "validate_admin_incident_event_page",
]
