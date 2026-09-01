from __future__ import annotations

import ast
from datetime import UTC, datetime, timedelta
import importlib
import inspect
import json
from pathlib import Path
from types import SimpleNamespace
from typing import get_args
import uuid

import pytest
from alembic.config import Config
from alembic.script import ScriptDirectory
from fastapi import FastAPI, HTTPException, Response
from pydantic import ValidationError
from sqlalchemy.dialects import postgresql
from starlette.datastructures import QueryParams

from backend.app.api import admin_incidents, health as health_api
from backend.app.field_test_security import (
    FieldTestAccess,
    _admin_security_error_response,
    required_field_test_access,
)
from backend.app.models import (
    AdminOperationAudit,
    CriticalIncident,
    CriticalIncidentEvent,
)
from backend.app.openapi_contract import install_walksafe_openapi_contract
from backend.app.schemas import (
    AdminIncidentDetailV1,
    AdminIncidentEventV1,
    AdminIncidentHistoryPageV1,
    AdminIncidentListPageV1,
    AdminIncidentStatusUpdateV1,
    AdminIncidentStatusV1,
    AdminIncidentSummaryV1,
    CriticalIncidentReasonCode,
)
from backend.app.services.admin_history_pagination import (
    INCIDENT_HISTORY_RESPONSE_BUDGET_BYTES,
    decode_admin_history_cursor,
    encode_admin_history_cursor,
)
from backend.app.services import admin_incident_workflow
from backend.app.services.admin_device_proof import (
    VerifiedAdminDeviceProof,
    is_admin_device_proof_workflow_request,
)
from backend.app.services.admin_incident_projection import (
    AdminIncidentCursorError,
    AdminIncidentFilters,
    admin_incident_cursor_predicate,
    admin_incident_filter_sha256,
    decode_admin_incident_cursor,
    encode_admin_incident_cursor,
    project_admin_incident_detail,
    project_admin_incident_summary,
)
from backend.app.services.admin_incident_workflow import (
    CriticalIncidentWorkflowError,
    record_critical_incident,
    update_critical_incident_status,
)
from backend.app.services.admin_security import (
    AdminSecurityError,
    AdminSessionIdentity,
    classify_admin_operation,
)


INCIDENT_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
SESSION_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
CORRELATION_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")
IDEMPOTENCY_KEY = uuid.UUID("44444444-4444-4444-8444-444444444444")
STARTED_AT = datetime(2026, 8, 29, 1, 0, tzinfo=UTC)
DETECTED_AT = datetime(2026, 8, 29, 1, 1, tzinfo=UTC)
RECORDED_AT = datetime(2026, 8, 29, 1, 2, tzinfo=UTC)
REASON_CODES = {
    "USER_SAFETY_RISK",
    "PERSONAL_DATA_BREACH",
    "DELETION_INTEGRITY_FAILURE",
    "CORE_SERVICE_TOTAL_OUTAGE",
    "IRREVERSIBLE_DATA_LOSS",
}


def _incident(**overrides: object) -> CriticalIncident:
    values: dict[str, object] = {
        "id": INCIDENT_ID,
        "producer_source": "WALKSAFE_BACKEND",
        "opening_intent_sha256": "1" * 64,
        "severity": "CRITICAL",
        "status": "OPEN",
        "status_version": 1,
        "reason_code": "USER_SAFETY_RISK",
        "summary": "보행자 안전 위험이 확인됨",
        "started_at": STARTED_AT,
        "detected_at": DETECTED_AT,
        "created_at": RECORDED_AT,
        "updated_at": RECORDED_AT,
    }
    values.update(overrides)
    return CriticalIncident(**values)


def _identity() -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id="reviewer@example.com",
        session_id=SESSION_ID,
        device_id="admin-device-0001",
        device_label="review tablet",
        expires_at=RECORDED_AT + timedelta(hours=1),
        step_up_verified_at=RECORDED_AT,
    )


def _status_payload(**overrides: object) -> AdminIncidentStatusUpdateV1:
    values: dict[str, object] = {
        "next_state": "ACKNOWLEDGED",
        "expected_version": 1,
        "idempotency_key": IDEMPOTENCY_KEY,
        "reason": "운영자가 중대사건을 확인했습니다",
        "observation": "현재 사실관계와 증거 해시를 검토했습니다",
        "evidence_sha256": "a" * 64,
    }
    values.update(overrides)
    return AdminIncidentStatusUpdateV1.model_validate(values)


def _event(
    revision: int,
    previous_state: str | None,
    next_state: str,
    *,
    recorded_at: datetime,
) -> CriticalIncidentEvent:
    opening = revision == 1
    return CriticalIncidentEvent(
        id=uuid.uuid5(INCIDENT_ID, f"event-{revision}"),
        incident_id=INCIDENT_ID,
        revision=revision,
        event_type="OPENED" if opening else next_state,
        previous_state=previous_state,
        next_state=next_state,
        expected_version=revision - 1,
        idempotency_key=(
            INCIDENT_ID if opening else uuid.uuid5(INCIDENT_ID, f"key-{revision}")
        ),
        intent_sha256=f"{revision % 16:x}" * 64,
        reason="시스템이 최초 중대사건 기록을 생성했습니다",
        observation="검증된 내부 근거를 바탕으로 사실을 기록했습니다",
        evidence_sha256="e" * 64,
        observed_at=recorded_at,
        recorded_at=recorded_at,
        actor_id=None if opening else "reviewer@example.com",
        session_id=None if opening else SESSION_ID,
        device_id=None if opening else "admin-device-0001",
        correlation_id=None if opening else CORRELATION_ID,
    )


def _event_history(size: int) -> list[CriticalIncidentEvent]:
    assert size >= 1
    events = [_event(1, None, "OPEN", recorded_at=RECORDED_AT)]
    transitions = {
        "OPEN": "ACKNOWLEDGED",
        "ACKNOWLEDGED": "RESOLVED",
        "RESOLVED": "REOPENED",
        "REOPENED": "ACKNOWLEDGED",
    }
    previous = "OPEN"
    for revision in range(2, size + 1):
        next_state = transitions[previous]
        events.append(
            _event(
                revision,
                previous,
                next_state,
                recorded_at=RECORDED_AT + timedelta(microseconds=revision - 1),
            )
        )
        previous = next_state
    return events


class _ScalarResult:
    def __init__(self, value: object | None) -> None:
        self.value = value

    def scalar_one_or_none(self) -> object | None:
        return self.value


class _FakeSession:
    def __init__(self, execute_results: list[object | None]) -> None:
        self.execute_results = list(execute_results)
        self.added: list[object] = []
        self.commit_count = 0
        self.rollback_count = 0
        self.expunge_count = 0

    def execute(self, _statement: object) -> _ScalarResult:
        assert self.execute_results, "unexpected database execute"
        return _ScalarResult(self.execute_results.pop(0))

    def add(self, value: object) -> None:
        self.added.append(value)

    def commit(self) -> None:
        self.commit_count += 1

    def rollback(self) -> None:
        self.rollback_count += 1

    def expunge(self, _value: object) -> None:
        self.expunge_count += 1

    def get(self, _model: type[object], _key: object) -> object | None:
        return None


class _IncidentHistoryAggregateResult:
    def __init__(
        self,
        value: tuple[CriticalIncident, int, int | None] | None,
    ) -> None:
        self.value = value

    def one_or_none(
        self,
    ) -> tuple[CriticalIncident, int, int | None] | None:
        return self.value


class _IncidentHistoryRowsResult:
    def __init__(self, rows: list[CriticalIncidentEvent]) -> None:
        self.rows = rows

    def all(self) -> list[CriticalIncidentEvent]:
        return self.rows


class _IncidentHistoryReadCommittedResult:
    def __init__(
        self,
        incident: CriticalIncident,
        count: int,
        maximum: int,
    ) -> None:
        self.incident = incident
        self.count = count
        self.maximum = maximum

    def one_or_none(self) -> tuple[CriticalIncident, int, int]:
        return self.incident, self.count, self.maximum

    def one(self) -> tuple[int, int]:
        """Shape used by the former split aggregate query."""

        return self.count, self.maximum


class _IncidentHistorySession:
    def __init__(
        self,
        *,
        incident: CriticalIncident | None,
        count: int,
        maximum: int | None,
        scalar_results: list[CriticalIncidentEvent | None],
        rows: list[CriticalIncidentEvent],
    ) -> None:
        self.incident = incident
        self.count = count
        self.maximum = maximum
        self.scalar_results = list(scalar_results)
        self.rows = rows
        self.rollback_count = 0

    def execute(self, _statement: object) -> _IncidentHistoryAggregateResult:
        return _IncidentHistoryAggregateResult(
            (
                (self.incident, self.count, self.maximum)
                if self.incident is not None
                else None
            )
        )

    def scalar(self, _statement: object) -> CriticalIncidentEvent | None:
        assert self.scalar_results, "unexpected incident history scalar query"
        return self.scalar_results.pop(0)

    def scalars(self, _statement: object) -> _IncidentHistoryRowsResult:
        return _IncidentHistoryRowsResult(self.rows)

    def rollback(self) -> None:
        self.rollback_count += 1


class _IncidentHistoryReadCommittedSession(_IncidentHistorySession):
    """Expose the stale-parent/new-aggregate result of a split read."""

    def __init__(
        self,
        *,
        stale_incident: CriticalIncident,
        incident: CriticalIncident,
        count: int,
        maximum: int,
        scalar_results: list[CriticalIncidentEvent | None],
        rows: list[CriticalIncidentEvent],
    ) -> None:
        super().__init__(
            incident=incident,
            count=count,
            maximum=maximum,
            scalar_results=scalar_results,
            rows=rows,
        )
        self.stale_incident = stale_incident
        self.get_count = 0
        self.execute_count = 0

    def get(self, _model: type[object], _key: object) -> CriticalIncident:
        self.get_count += 1
        return self.stale_incident

    def execute(self, _statement: object) -> _IncidentHistoryReadCommittedResult:
        self.execute_count += 1
        assert self.incident is not None
        assert self.maximum is not None
        return _IncidentHistoryReadCommittedResult(
            self.incident,
            self.count,
            self.maximum,
        )


def _incident_history_request(
    *,
    query: str = "limit=2",
    path: str | None = None,
    read_purpose: str = "admin.incident.history",
) -> SimpleNamespace:
    resolved_path = path or f"/admin/incidents/{INCIDENT_ID}/history"
    identity = _identity()
    proof = VerifiedAdminDeviceProof(
        admin_id=identity.admin_id,
        device_id=identity.device_id,
        session_id=identity.session_id,
        challenge_id=uuid.UUID("55555555-5555-4555-8555-555555555555"),
        correlation_id=CORRELATION_ID,
        action=None,
        purpose="ACTION",
        read_purpose=read_purpose,
        method="GET",
        path=resolved_path,
        body_sha256="0" * 64,
        query_sha256="1" * 64,
        device_key_marker="2" * 64,
        device_key_version=1,
    )
    return SimpleNamespace(
        state=SimpleNamespace(
            admin_security_identity=identity,
            admin_device_proof=proof,
        ),
        method="GET",
        url=SimpleNamespace(path=resolved_path),
        query_params=QueryParams(query),
    )


def test_android_v1_schema_is_exact_and_content_minimized() -> None:
    assert set(get_args(CriticalIncidentReasonCode)) == REASON_CODES
    assert set(AdminIncidentSummaryV1.model_fields) == {
        "incident_id",
        "severity",
        "status",
        "status_version",
        "reason_code",
        "summary",
        "started_at",
        "detected_at",
        "updated_at",
    }
    assert set(AdminIncidentListPageV1.model_fields) == {
        "schema_version",
        "items",
        "next_cursor",
    }
    assert set(AdminIncidentEventV1.model_fields) == {
        "event_id",
        "revision",
        "event_type",
        "previous_state",
        "next_state",
        "reason",
        "observation",
        "evidence_sha256",
        "observed_at",
        "recorded_at",
        "actor_id",
    }
    assert set(AdminIncidentDetailV1.model_fields) == {
        *AdminIncidentSummaryV1.model_fields,
        "schema_version",
        "allowed_next_states",
        "events",
    }
    assert set(AdminIncidentHistoryPageV1.model_fields) == {
        "schema_version",
        "incident",
        "allowed_next_states",
        "snapshot_revision",
        "total_count",
        "items",
        "next_cursor",
    }
    assert set(AdminIncidentStatusUpdateV1.model_fields) == {
        "next_state",
        "expected_version",
        "idempotency_key",
        "reason",
        "observation",
        "evidence_sha256",
    }
    assert set(AdminIncidentStatusV1.model_fields) == {
        "schema_version",
        "incident_id",
        "status",
        "status_version",
        "allowed_next_states",
        "updated_at",
    }

    summary = project_admin_incident_summary(_incident())
    body = summary.model_dump(mode="json")
    assert body["severity"] == "CRITICAL"
    assert set(body) == set(AdminIncidentSummaryV1.model_fields)
    assert {
        "producer_source",
        "opening_intent_sha256",
        "created_at",
        "session_id",
        "device_id",
        "correlation_id",
        "idempotency_key",
        "intent_sha256",
    }.isdisjoint(body)
    assert "must-not-leak" not in json.dumps(body)


@pytest.mark.parametrize("reason_code", sorted(REASON_CODES))
def test_each_android_reason_code_projects_as_critical(reason_code: str) -> None:
    body = project_admin_incident_summary(
        _incident(reason_code=reason_code)
    ).model_dump(mode="json")

    assert body["reason_code"] == reason_code
    assert body["severity"] == "CRITICAL"


def test_noncritical_severity_and_open_mutation_are_rejected_by_android_contract() -> None:
    with pytest.raises(ValidationError):
        AdminIncidentSummaryV1.model_validate(
            {
                **project_admin_incident_summary(_incident()).model_dump(),
                "severity": "HIGH",
            }
        )

    mutation_schema = AdminIncidentStatusUpdateV1.model_json_schema()
    next_state = mutation_schema["properties"]["next_state"]
    if "$ref" in next_state:
        next_state = mutation_schema["$defs"][next_state["$ref"].rsplit("/", 1)[-1]]
    assert set(next_state["enum"]) == {"ACKNOWLEDGED", "RESOLVED", "REOPENED"}
    with pytest.raises(ValidationError):
        _status_payload(next_state="OPEN")


def test_cursor_is_canonical_bound_to_status_and_descending_keyset() -> None:
    filters = AdminIncidentFilters(status="OPEN")
    digest = admin_incident_filter_sha256(filters)
    cursor = encode_admin_incident_cursor(
        detected_at=DETECTED_AT,
        incident_id=INCIDENT_ID,
        filter_sha256=digest,
    )

    decoded = decode_admin_incident_cursor(
        cursor,
        expected_filter_sha256=digest,
    )
    assert decoded.detected_at == DETECTED_AT
    assert decoded.incident_id == INCIDENT_ID
    assert encode_admin_incident_cursor(
        detected_at=decoded.detected_at,
        incident_id=decoded.incident_id,
        filter_sha256=digest,
    ) == cursor

    other_digest = admin_incident_filter_sha256(
        AdminIncidentFilters(status="RESOLVED")
    )
    with pytest.raises(AdminIncidentCursorError, match="filters"):
        decode_admin_incident_cursor(
            cursor,
            expected_filter_sha256=other_digest,
        )
    with pytest.raises(AdminIncidentCursorError):
        decode_admin_incident_cursor(
            cursor + "=",
            expected_filter_sha256=digest,
        )

    predicate = admin_incident_cursor_predicate(DETECTED_AT, INCIDENT_ID)
    sql = str(predicate.compile(dialect=postgresql.dialect()))
    assert "critical_incidents.detected_at <" in sql
    assert "critical_incidents.detected_at =" in sql
    assert "critical_incidents.id <" in sql


def test_detail_requires_contiguous_history_and_accepts_exactly_256_events() -> None:
    events = _event_history(256)
    latest = events[-1]
    incident = _incident(
        status=latest.next_state,
        status_version=latest.revision,
        updated_at=latest.recorded_at,
    )

    detail = project_admin_incident_detail(incident, events)
    assert detail.schema_version == "walksafe.admin-incident-detail.v1"
    assert len(detail.events) == 256
    assert detail.events[-1].revision == 256
    event_body = detail.events[-1].model_dump(mode="json")
    assert {
        "session_id",
        "device_id",
        "correlation_id",
        "idempotency_key",
        "intent_sha256",
    }.isdisjoint(event_body)

    with pytest.raises(ValueError, match="incomplete"):
        project_admin_incident_detail(
            incident,
            events
            + [
                _event(
                    257,
                    latest.next_state,
                    "ACKNOWLEDGED",
                    recorded_at=latest.recorded_at + timedelta(microseconds=1),
                )
            ],
        )

    broken = _event_history(3)
    broken[1].revision = 3
    with pytest.raises(ValueError, match="contiguous"):
        project_admin_incident_detail(
            _incident(
                status=broken[-1].next_state,
                status_version=broken[-1].revision,
                updated_at=broken[-1].recorded_at,
            ),
            broken,
        )


def test_incident_history_pages_keep_first_snapshot_and_exclude_new_appends(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audits: list[dict[str, object]] = []
    monkeypatch.setattr(
        admin_incidents,
        "_persist_audit",
        lambda _db, **kwargs: audits.append(kwargs),
    )
    first_events = _event_history(3)
    first_incident = _incident(
        status=first_events[-1].next_state,
        status_version=3,
        updated_at=first_events[-1].recorded_at,
    )
    first_response = Response()
    first_page = admin_incidents.get_admin_incident_history(
        request=_incident_history_request(),  # type: ignore[arg-type]
        response=first_response,
        incident_id=str(INCIDENT_ID),
        limit=2,
        cursor=None,
        db=_IncidentHistorySession(
            incident=first_incident,
            count=3,
            maximum=3,
            scalar_results=[first_events[2]],
            rows=first_events[:2],
        ),  # type: ignore[arg-type]
    )

    assert [event.revision for event in first_page.items] == [1, 2]
    assert first_page.snapshot_revision == first_page.total_count == 3
    assert first_page.incident.status_version == 3
    assert first_page.incident.status == "RESOLVED"
    assert first_page.next_cursor is not None
    decoded = decode_admin_history_cursor(
        first_page.next_cursor,
        expected_stream="incident_events",
        expected_resource_id=INCIDENT_ID,
    )
    assert (decoded.snapshot_revision, decoded.after_revision) == (3, 2)
    assert first_response.headers["cache-control"] == "no-store"
    assert first_response.headers["pragma"] == "no-cache"

    current_events = _event_history(4)
    current_incident = _incident(
        status=current_events[-1].next_state,
        status_version=4,
        updated_at=current_events[-1].recorded_at,
    )
    second_page = admin_incidents.get_admin_incident_history(
        request=_incident_history_request(
            query=f"limit=2&cursor={first_page.next_cursor}"
        ),  # type: ignore[arg-type]
        response=Response(),
        incident_id=str(INCIDENT_ID),
        limit=2,
        cursor=first_page.next_cursor,
        db=_IncidentHistorySession(
            incident=current_incident,
            count=4,
            maximum=4,
            scalar_results=[current_events[2], current_events[1]],
            rows=[current_events[2]],
        ),  # type: ignore[arg-type]
    )

    assert [event.revision for event in second_page.items] == [3]
    assert second_page.snapshot_revision == second_page.total_count == 3
    assert second_page.incident.status_version == 3
    assert second_page.incident.status == "RESOLVED"
    assert second_page.next_cursor is None
    assert [audit["result_count"] for audit in audits] == [2, 1]
    assert all(audit["outcome"] == "SUCCEEDED" for audit in audits)


def test_incident_history_read_committed_append_uses_one_statement_snapshot(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        admin_incidents,
        "_persist_audit",
        lambda *_args, **_kwargs: None,
    )
    events = _event_history(4)
    stale_incident = _incident(
        status=events[2].next_state,
        status_version=3,
        updated_at=events[2].recorded_at,
    )
    current_incident = _incident(
        status=events[3].next_state,
        status_version=4,
        updated_at=events[3].recorded_at,
    )
    db = _IncidentHistoryReadCommittedSession(
        stale_incident=stale_incident,
        incident=current_incident,
        count=4,
        maximum=4,
        scalar_results=[events[3]],
        rows=events[:2],
    )

    page = admin_incidents.get_admin_incident_history(
        request=_incident_history_request(),  # type: ignore[arg-type]
        response=Response(),
        incident_id=str(INCIDENT_ID),
        limit=2,
        cursor=None,
        db=db,  # type: ignore[arg-type]
    )

    assert db.get_count == 0
    assert db.execute_count == 1
    assert page.snapshot_revision == page.total_count == 4
    assert page.incident.status_version == 4
    assert [item.revision for item in page.items] == [1, 2]


def test_incident_history_past_cursor_survives_read_committed_append(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        admin_incidents,
        "_persist_audit",
        lambda *_args, **_kwargs: None,
    )
    cursor = encode_admin_history_cursor(
        stream="incident_events",
        resource_id=INCIDENT_ID,
        snapshot_revision=3,
        after_revision=2,
    )
    events = _event_history(5)
    stale_incident = _incident(
        status=events[3].next_state,
        status_version=4,
        updated_at=events[3].recorded_at,
    )
    current_incident = _incident(
        status=events[4].next_state,
        status_version=5,
        updated_at=events[4].recorded_at,
    )
    db = _IncidentHistoryReadCommittedSession(
        stale_incident=stale_incident,
        incident=current_incident,
        count=5,
        maximum=5,
        scalar_results=[events[2], events[1]],
        rows=[events[2]],
    )

    page = admin_incidents.get_admin_incident_history(
        request=_incident_history_request(
            query=f"limit=2&cursor={cursor}"
        ),  # type: ignore[arg-type]
        response=Response(),
        incident_id=str(INCIDENT_ID),
        limit=2,
        cursor=cursor,
        db=db,  # type: ignore[arg-type]
    )

    assert db.get_count == 0
    assert db.execute_count == 1
    assert page.snapshot_revision == page.total_count == 3
    assert page.incident.status_version == 3
    assert page.incident.status == "RESOLVED"
    assert [item.revision for item in page.items] == [3]
    assert page.next_cursor is None


def test_incident_history_same_statement_detects_projection_event_divergence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        admin_incidents,
        "_persist_audit",
        lambda *_args, **_kwargs: None,
    )
    events = _event_history(4)
    incident = _incident(
        status=events[3].next_state,
        status_version=4,
        updated_at=events[3].recorded_at,
    )

    with pytest.raises(HTTPException) as exc_info:
        admin_incidents.get_admin_incident_history(
            request=_incident_history_request(),  # type: ignore[arg-type]
            response=Response(),
            incident_id=str(INCIDENT_ID),
            limit=2,
            cursor=None,
            db=_IncidentHistorySession(
                incident=incident,
                count=3,
                maximum=3,
                scalar_results=[],
                rows=[],
            ),  # type: ignore[arg-type]
        )

    assert exc_info.value.status_code == 503
    assert exc_info.value.detail["code"] == "admin_incident_history_integrity_invalid"


def test_incident_history_enforces_utf8_budget_and_contiguous_prefix(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        admin_incidents,
        "_persist_audit",
        lambda *_args, **_kwargs: None,
    )
    events = _event_history(25)
    for event in events:
        event.reason = "\U0001f6b6" * 500
        event.observation = "\U0001f9af" * 500
    latest = events[-1]
    incident = _incident(
        status=latest.next_state,
        status_version=25,
        updated_at=latest.recorded_at,
    )

    page = admin_incidents.get_admin_incident_history(
        request=_incident_history_request(query="limit=25"),  # type: ignore[arg-type]
        response=Response(),
        incident_id=str(INCIDENT_ID),
        limit=25,
        cursor=None,
        db=_IncidentHistorySession(
            incident=incident,
            count=25,
            maximum=25,
            scalar_results=[latest],
            rows=events,
        ),  # type: ignore[arg-type]
    )

    assert 0 < len(page.items) < 25
    assert [item.revision for item in page.items] == list(
        range(1, len(page.items) + 1)
    )
    assert len(page.model_dump_json().encode("utf-8")) <= (
        INCIDENT_HISTORY_RESPONSE_BUDGET_BYTES
    )
    assert page.next_cursor is not None


def test_incident_history_rejects_integrity_gaps_and_duplicate_query_fields(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audits: list[dict[str, object]] = []
    monkeypatch.setattr(
        admin_incidents,
        "_persist_audit",
        lambda _db, **kwargs: audits.append(kwargs),
    )
    events = _event_history(3)
    incident = _incident(
        status=events[-1].next_state,
        status_version=3,
        updated_at=events[-1].recorded_at,
    )
    with pytest.raises(HTTPException) as gap:
        admin_incidents.get_admin_incident_history(
            request=_incident_history_request(),  # type: ignore[arg-type]
            response=Response(),
            incident_id=str(INCIDENT_ID),
            limit=2,
            cursor=None,
            db=_IncidentHistorySession(
                incident=incident,
                count=2,
                maximum=3,
                scalar_results=[],
                rows=[],
            ),  # type: ignore[arg-type]
        )
    assert gap.value.status_code == 503
    assert gap.value.detail["code"] == "admin_incident_history_integrity_invalid"
    assert gap.value.headers == {
        "Cache-Control": "no-store",
        "Pragma": "no-cache",
    }

    with pytest.raises(HTTPException) as duplicate:
        admin_incidents.get_admin_incident_history(
            request=_incident_history_request(query="limit=2&limit=3"),  # type: ignore[arg-type]
            response=Response(),
            incident_id=str(INCIDENT_ID),
            limit=2,
            cursor=None,
            db=_IncidentHistorySession(
                incident=incident,
                count=3,
                maximum=3,
                scalar_results=[],
                rows=[],
            ),  # type: ignore[arg-type]
        )
    assert duplicate.value.status_code == 422
    assert duplicate.value.detail["code"] == "admin_incident_history_query_invalid"
    assert duplicate.value.headers == {
        "Cache-Control": "no-store",
        "Pragma": "no-cache",
    }
    assert [(audit["outcome"], audit["error_code"]) for audit in audits] == [
        ("ERROR", "admin_incident_history_integrity_invalid"),
        ("DENIED", "admin_incident_history_query_invalid"),
    ]


def test_trusted_opening_is_exactly_idempotent_and_conflicts_on_changed_intent() -> None:
    arguments = {
        "incident_id": INCIDENT_ID,
        "reason_code": "USER_SAFETY_RISK",
        "summary": "보행자 안전 위험이 확인됨",
        "started_at": STARTED_AT,
        "detected_at": DETECTED_AT,
        "reason": "검증된 내부 감지기가 중대사건을 기록했습니다",
        "observation": "사실 기록만 생성하며 자동 복구는 실행하지 않습니다",
        "evidence_sha256": "a" * 64,
        "now": RECORDED_AT,
    }
    created_db = _FakeSession([None])

    incident, created = record_critical_incident(
        created_db,  # type: ignore[arg-type]
        **arguments,
    )

    assert created is True
    assert incident.severity == "CRITICAL"
    assert incident.producer_source == "WALKSAFE_BACKEND"
    assert incident.status == "OPEN" and incident.status_version == 1
    assert created_db.commit_count == 1
    assert [type(value) for value in created_db.added] == [
        CriticalIncident,
        CriticalIncidentEvent,
    ]
    opening = created_db.added[1]
    assert isinstance(opening, CriticalIncidentEvent)
    assert (opening.event_type, opening.previous_state, opening.next_state) == (
        "OPENED",
        None,
        "OPEN",
    )
    assert opening.actor_id is None

    retry_db = _FakeSession([incident])
    retried, retried_created = record_critical_incident(
        retry_db,  # type: ignore[arg-type]
        **arguments,
    )
    assert retried is incident
    assert retried_created is False
    assert retry_db.expunge_count == 1
    assert retry_db.rollback_count == 1
    assert retry_db.commit_count == 0

    conflict_db = _FakeSession([incident])
    with pytest.raises(CriticalIncidentWorkflowError) as captured:
        record_critical_incident(
            conflict_db,  # type: ignore[arg-type]
            **{**arguments, "summary": "서로 다른 중대사건 기록"},
        )
    assert captured.value.code == "incident_opening_conflict"
    assert captured.value.status_code == 409


def test_status_transition_appends_event_and_exact_retry_returns_same_snapshot() -> None:
    incident = _incident()
    payload = _status_payload()
    changed_at = RECORDED_AT + timedelta(seconds=1)
    db = _FakeSession([incident, None])

    snapshot = update_critical_incident_status(
        db,  # type: ignore[arg-type]
        incident_id=INCIDENT_ID,
        payload=payload,
        identity=_identity(),
        correlation_id=CORRELATION_ID,
        query_sha256="b" * 64,
        now=changed_at,
    )

    assert (snapshot.status, snapshot.status_version, snapshot.updated_at) == (
        "ACKNOWLEDGED",
        2,
        changed_at,
    )
    assert (incident.status, incident.status_version, incident.updated_at) == (
        "ACKNOWLEDGED",
        2,
        changed_at,
    )
    assert db.commit_count == 1
    assert [type(value) for value in db.added] == [
        CriticalIncidentEvent,
        AdminOperationAudit,
    ]
    event = db.added[0]
    assert isinstance(event, CriticalIncidentEvent)
    assert (event.revision, event.previous_state, event.next_state) == (
        2,
        "OPEN",
        "ACKNOWLEDGED",
    )
    assert event.actor_id == "reviewer@example.com"

    retry_db = _FakeSession([incident, event])
    retry = update_critical_incident_status(
        retry_db,  # type: ignore[arg-type]
        incident_id=INCIDENT_ID,
        payload=payload,
        identity=_identity(),
        correlation_id=CORRELATION_ID,
        query_sha256="b" * 64,
        now=changed_at + timedelta(seconds=1),
    )
    assert retry == snapshot
    assert retry_db.commit_count == 1
    assert len(retry_db.added) == 1
    assert isinstance(retry_db.added[0], AdminOperationAudit)

    conflict_db = _FakeSession([incident, event])
    with pytest.raises(CriticalIncidentWorkflowError) as captured:
        update_critical_incident_status(
            conflict_db,  # type: ignore[arg-type]
            incident_id=INCIDENT_ID,
            payload=_status_payload(observation="같은 키에 다른 관찰을 기록하려고 했습니다"),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
            query_sha256="b" * 64,
            now=changed_at + timedelta(seconds=2),
        )
    assert captured.value.code == "incident_status_version_conflict"
    assert captured.value.status_code == 409
    assert captured.value.latest == {
        "status": "ACKNOWLEDGED",
        "status_version": 2,
        "allowed_next_states": ["RESOLVED"],
    }


def test_status_transition_enforces_cas_and_transition_graph() -> None:
    stale_incident = _incident()
    stale_db = _FakeSession([stale_incident, None])
    with pytest.raises(CriticalIncidentWorkflowError) as stale:
        update_critical_incident_status(
            stale_db,  # type: ignore[arg-type]
            incident_id=INCIDENT_ID,
            payload=_status_payload(expected_version=2),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
            query_sha256="b" * 64,
            now=RECORDED_AT + timedelta(seconds=1),
        )
    assert stale.value.code == "incident_status_version_conflict"
    assert stale.value.status_code == 409
    assert stale.value.latest == {
        "status": "OPEN",
        "status_version": 1,
        "allowed_next_states": ["ACKNOWLEDGED"],
    }
    assert stale_db.commit_count == 0 and stale_db.rollback_count == 1

    invalid_db = _FakeSession([_incident(), None])
    with pytest.raises(CriticalIncidentWorkflowError) as invalid:
        update_critical_incident_status(
            invalid_db,  # type: ignore[arg-type]
            incident_id=INCIDENT_ID,
            payload=_status_payload(next_state="RESOLVED"),
            identity=_identity(),
            correlation_id=CORRELATION_ID,
            query_sha256="b" * 64,
            now=RECORDED_AT + timedelta(seconds=1),
        )
    assert invalid.value.code == "incident_status_transition_invalid"
    assert invalid.value.status_code == 422
    assert invalid_db.commit_count == 0 and invalid_db.rollback_count == 1


def test_admin_incident_routes_are_admin_proof_scoped_and_patch_is_high_risk() -> None:
    incident_path = f"/admin/incidents/{INCIDENT_ID}"
    history_path = incident_path + "/history"
    status_path = incident_path + "/status"
    for method, path in (
        ("GET", "/admin/incidents"),
        ("GET", incident_path),
        ("GET", history_path),
        ("PATCH", status_path),
    ):
        assert required_field_test_access(path, method) is FieldTestAccess.ADMIN
        assert is_admin_device_proof_workflow_request(method, path) is True

    operation = classify_admin_operation("PATCH", status_path)
    assert operation is not None
    assert (operation.action, operation.risk) == (
        "admin.incident.status.update",
        "HIGH",
    )
    assert classify_admin_operation("POST", status_path) is None
    assert classify_admin_operation(
        "PATCH",
        "/admin/incidents/not-a-uuid/status",
    ) is None
    assert is_admin_device_proof_workflow_request("POST", "/admin/incidents") is False


def test_legacy_detail_validation_path_named_history_is_not_misclassified(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audits: list[dict[str, object]] = []
    monkeypatch.setattr(
        admin_incidents,
        "_persist_audit",
        lambda _db, **kwargs: audits.append(kwargs),
    )

    admin_incidents.persist_admin_incident_validation_audit(
        _incident_history_request(
            query="",
            path="/admin/incidents/history",
            read_purpose="admin.incident.detail",
        ),  # type: ignore[arg-type]
        object(),  # type: ignore[arg-type]
    )

    assert len(audits) == 1
    observed = audits[0]
    assert isinstance(observed.pop("proof"), VerifiedAdminDeviceProof)
    assert observed == {
        "identity": _identity(),
        "operation": "admin.incident.detail",
        "resource_type": "critical_incident",
        "resource_id": "invalid-incident-id",
        "outcome": "DENIED",
        "result_count": None,
        "error_code": "admin_incident_request_invalid",
    }


def test_incident_patch_reserves_409_for_status_version_conflict() -> None:
    security_conflict = AdminSecurityError(
        "admin_device_proof_replayed",
        "The administrator device proof challenge was already consumed.",
        status_code=409,
    )
    incident_response = _admin_security_error_response(
        security_conflict,
        method="PATCH",
        path=f"/admin/incidents/{INCIDENT_ID}/status",
    )
    unrelated_response = _admin_security_error_response(
        security_conflict,
        method="PATCH",
        path=f"/admin/reports/{INCIDENT_ID}/status",
    )

    assert incident_response.status_code == 403
    assert unrelated_response.status_code == 409


def test_incident_patch_409_has_exact_android_latest_envelope(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = f"/admin/incidents/{INCIDENT_ID}/status"
    identity = _identity()
    proof = VerifiedAdminDeviceProof(
        admin_id=identity.admin_id,
        device_id=identity.device_id,
        session_id=identity.session_id,
        challenge_id=uuid.uuid4(),
        correlation_id=CORRELATION_ID,
        action="admin.incident.status.update",
        purpose="ACTION",
        read_purpose=None,
        method="PATCH",
        path=path,
        body_sha256="a" * 64,
        query_sha256="b" * 64,
        device_key_marker="c" * 64,
        device_key_version=1,
    )
    request = SimpleNamespace(
        state=SimpleNamespace(
            admin_security_identity=identity,
            admin_device_proof=proof,
        ),
        method="PATCH",
        url=SimpleNamespace(path=path),
    )
    latest = {
        "status": "OPEN",
        "status_version": 1,
        "allowed_next_states": ["ACKNOWLEDGED"],
    }

    def conflict(*_args: object, **_kwargs: object) -> object:
        raise CriticalIncidentWorkflowError(
            "incident_status_version_conflict",
            "The critical incident changed before this update was applied.",
            status_code=409,
            latest=latest,
        )

    monkeypatch.setattr(admin_incidents, "update_critical_incident_status", conflict)
    monkeypatch.setattr(admin_incidents, "_persist_audit", lambda *_args, **_kwargs: None)

    with pytest.raises(HTTPException) as captured:
        admin_incidents.patch_admin_incident_status(
            incident_id=INCIDENT_ID,
            payload=_status_payload(),
            request=request,  # type: ignore[arg-type]
            response=Response(),
            db=object(),  # type: ignore[arg-type]
        )

    assert captured.value.status_code == 409
    assert captured.value.detail == {
        "code": "incident_status_version_conflict",
        "message": "The critical incident changed before this update was applied.",
        "latest": latest,
    }


def test_openapi_matches_android_read_and_high_risk_status_contract() -> None:
    app = FastAPI()
    app.include_router(admin_incidents.create_router())
    install_walksafe_openapi_contract(
        app,
        SimpleNamespace(
            admin_security_enabled=True,
            admin_device_proof_enabled=True,
        ),
    )
    schema = app.openapi()
    proof_schemes = {
        "WalkSafeAdminDeviceChallengeId",
        "WalkSafeAdminDeviceSignature",
        "WalkSafeCorrelationId",
    }
    common_admin = {
        "WalkSafeAdminBearer",
        "WalkSafeAdminAppKind",
        "WalkSafeAdminRole",
        "WalkSafeAdminAudience",
        "WalkSafeAdminDeviceId",
    }
    for path, purpose in (
        ("/admin/incidents", "admin.incident.list"),
        ("/admin/incidents/{incident_id}", "admin.incident.detail"),
        (
            "/admin/incidents/{incident_id}/history",
            "admin.incident.history",
        ),
    ):
        operation = schema["paths"][path]["get"]
        assert set(operation["security"][0]) == {
            *common_admin,
            *proof_schemes,
            "WalkSafeReadPurpose",
        }
        assert operation["x-walksafe-admin-device-proof"] == {
            "purpose": "ACTION",
            "action": None,
            "read_purpose": purpose,
            "session_id": "authenticated-admin-session",
        }

    patch = schema["paths"]["/admin/incidents/{incident_id}/status"]["patch"]
    assert set(patch["security"][0]) == common_admin | proof_schemes
    assert patch["x-walksafe-admin-device-proof"] == {
        "purpose": "ACTION",
        "action": "admin.incident.status.update",
        "read_purpose": None,
        "session_id": "authenticated-admin-session",
    }
    assert patch["x-walksafe-high-risk-action"] == "admin.incident.status.update"
    reconfirmation = [
        parameter
        for parameter in patch["parameters"]
        if parameter.get("name") == "X-WalkSafe-Reconfirm-Nonce"
    ]
    assert len(reconfirmation) == 1 and reconfirmation[0]["required"] is True
    assert patch["requestBody"]["content"]["application/json"]["schema"][
        "$ref"
    ].endswith("/AdminIncidentStatusUpdateV1")
    assert patch["responses"]["200"]["content"]["application/json"]["schema"][
        "$ref"
    ].endswith("/AdminIncidentStatusV1")


def test_router_has_no_public_create_or_automatic_control_path() -> None:
    routes = {
        (route.path, frozenset(route.methods or set()))
        for route in admin_incidents.create_router().routes
    }
    assert routes == {
        ("/admin/incidents", frozenset({"GET"})),
        (
            "/admin/incidents/{incident_id}",
            frozenset({"GET"}),
        ),
        (
            "/admin/incidents/{incident_id}/status",
            frozenset({"PATCH"}),
        ),
        (
            "/admin/incidents/{incident_id}/history",
            frozenset({"GET"}),
        ),
    }
    api_source = inspect.getsource(admin_incidents)
    assert "record_critical_incident" not in api_source

    workflow_tree = ast.parse(inspect.getsource(admin_incident_workflow))
    imported_modules = {
        node.module
        for node in ast.walk(workflow_tree)
        if isinstance(node, ast.ImportFrom) and node.module is not None
    }
    assert not any(
        module.startswith(
            (
                "backend.app.api",
                "backend.app.services.accounts",
                "backend.app.services.capacity_state",
                "backend.app.services.privacy_lifecycle",
                "backend.app.services.report",
                "backend.app.services.raw_collection",
            )
        )
        for module in imported_modules
    )
    assert not {
        node.names[0].name.split(".", 1)[0]
        for node in ast.walk(workflow_tree)
        if isinstance(node, ast.Import) and node.names
    } & {"httpx", "requests", "socket", "subprocess"}
    called_names = {
        (
            node.func.id
            if isinstance(node.func, ast.Name)
            else node.func.attr
            if isinstance(node.func, ast.Attribute)
            else ""
        ).lower()
        for node in ast.walk(workflow_tree)
        if isinstance(node, ast.Call)
    }
    assert called_names.isdisjoint(
        {
            "delete",
            "deliver",
            "notify",
            "post",
            "publish",
            "recover",
            "revoke",
            "send",
            "shutdown",
            "terminate",
        }
    )


def test_migration_015_is_append_only_event_bound_and_least_privilege(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    migration = importlib.import_module(
        "backend.alembic.versions.202608290015_critical_incident_record_only"
    )
    consent_successor = importlib.import_module(
        "backend.alembic.versions.202608290016_integrated_consent_v11"
    )
    deletion_successor = importlib.import_module(
        "backend.alembic.versions.202608300001_report_deletion_candidate_lock"
    )
    evidence_successor = importlib.import_module(
        "backend.alembic.versions.202608300002_report_original_evidence_v2"
    )
    restore_successor = importlib.import_module(
        "backend.alembic.versions.202608300003_report_restore_reapply"
    )
    acl_successor = importlib.import_module(
        "backend.alembic.versions.202608300004_admin_original_evidence_acl"
    )
    integrity_successor = importlib.import_module(
        "backend.alembic.versions.202608300005_admin_report_integrity_boundary"
    )
    external_copy_successor = importlib.import_module(
        "backend.alembic.versions.202608300006_report_external_copy_deletion_events"
    )
    discovery_successor = importlib.import_module(
        "backend.alembic.versions.202609010001_report_user_request_discovery"
    )
    head = importlib.import_module(
        "backend.alembic.versions.202609010002_raw_collection_digest_rejection"
    )
    assert migration.revision == "202608290015"
    assert migration.down_revision == "202608290014"
    assert consent_successor.down_revision == migration.revision
    assert deletion_successor.down_revision == consent_successor.revision
    assert evidence_successor.down_revision == deletion_successor.revision
    assert restore_successor.down_revision == evidence_successor.revision
    assert acl_successor.down_revision == restore_successor.revision
    assert integrity_successor.down_revision == acl_successor.revision
    assert external_copy_successor.down_revision == integrity_successor.revision
    assert discovery_successor.down_revision == external_copy_successor.revision
    assert head.down_revision == discovery_successor.revision
    assert health_api.EXPECTED_ALEMBIC_HEAD == head.revision
    alembic_config = Config(
        str(Path(__file__).resolve().parents[1] / "alembic.ini")
    )
    alembic_config.set_main_option(
        "script_location",
        str(Path(__file__).resolve().parents[1] / "alembic"),
    )
    assert ScriptDirectory.from_config(alembic_config).get_heads() == [
        head.revision
    ]

    created_tables: list[str] = []
    executed: list[str] = []
    monkeypatch.setattr(
        migration.op,
        "create_table",
        lambda name, *_args, **_kwargs: created_tables.append(name),
    )
    monkeypatch.setattr(
        migration.op,
        "create_index",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        migration.op,
        "execute",
        lambda statement: executed.append(str(statement)),
    )

    migration.upgrade()

    assert created_tables == ["critical_incidents", "critical_incident_events"]
    sql = " ".join(" ".join(statement.split()) for statement in executed)
    assert (
        "CREATE TRIGGER critical_incident_events_append_only BEFORE UPDATE OR DELETE "
        "ON public.critical_incident_events"
    ) in sql
    assert (
        "CREATE TRIGGER critical_incident_events_no_truncate BEFORE TRUNCATE ON "
        "public.critical_incident_events"
    ) in sql
    assert "critical incident metadata is immutable" in sql
    assert "critical incident transition event is missing" in sql
    assert "critical incident event projection is missing" in sql
    assert "event.idempotency_key = NEW.id" in sql
    assert "event.intent_sha256 = NEW.opening_intent_sha256" in sql
    assert "event.previous_state = OLD.status" in sql
    assert "previous_event.revision = NEW.revision - 1" in sql
    assert "previous_event.next_state = NEW.previous_state" in sql
    assert "DEFERRABLE INITIALLY DEFERRED" in sql

    assert (
        "REVOKE ALL PRIVILEGES ON TABLE public.critical_incidents, "
        "public.critical_incident_events FROM PUBLIC, walksafe_backend_runtime"
    ) in sql
    assert (
        "GRANT SELECT, INSERT ON TABLE public.critical_incidents TO "
        "walksafe_backend_runtime"
    ) in sql
    assert (
        "GRANT UPDATE (status, status_version, updated_at) ON TABLE "
        "public.critical_incidents TO walksafe_backend_runtime"
    ) in sql
    assert (
        "GRANT SELECT, INSERT ON TABLE public.critical_incident_events TO "
        "walksafe_backend_runtime"
    ) in sql
    assert "GRANT DELETE" not in sql
    assert "GRANT TRUNCATE" not in sql
    assert "GRANT UPDATE ON TABLE public.critical_incident_events" not in sql
    assert "SECURITY DEFINER SET search_path = pg_catalog, public" in sql
    assert (
        "REVOKE ALL ON FUNCTION public.walksafe_guard_critical_incident_projection() "
        "FROM PUBLIC, walksafe_backend_runtime"
    ) in sql

    source_path = (
        Path(__file__).resolve().parents[1]
        / "alembic/versions/202608290015_critical_incident_record_only.py"
    )
    source = source_path.read_text(encoding="utf-8")
    assert 'revision = "202608290015"' in source
    assert 'down_revision = "202608290014"' in source
    assert "previous_state IS NOT NULL" in source
    assert "def downgrade() -> None:" in source
    assert "IN ACCESS EXCLUSIVE MODE" in source
    assert "cannot downgrade while critical incident evidence exists" in source
    assert 'op.drop_table("critical_incident_events")' in source
    assert 'op.drop_table("critical_incidents")' in source
