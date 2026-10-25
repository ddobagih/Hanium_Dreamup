"""Both write paths retain their own CAS and audit contracts on shared decisions."""
from __future__ import annotations

from contextlib import nullcontext
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
import uuid

from fastapi import HTTPException
import pytest

from backend.app.api import reports
from backend.app.models import AdminOperationAudit, Report, ReportStatusAudit
from backend.app.schemas import AdminReportStatusUpdateV1, ReportStatusUpdate
from backend.app.services.admin_report_workflow import AdminReportWorkflowError, update_admin_report_status
from backend.app.services.report_status_transition import decide_report_status_transition
from backend.tests.test_admin_report_wave5 import _StatusSession, _identity


TRANSITIONS = [
    ("new", "new", "unchanged"), ("new", "reviewed", "allowed"), ("new", "resolved", "allowed"),
    ("reviewed", "new", "allowed"), ("reviewed", "reviewed", "unchanged"), ("reviewed", "resolved", "allowed"),
    ("resolved", "new", "invalid"), ("resolved", "reviewed", "allowed"), ("resolved", "resolved", "unchanged"),
]


class LegacySession(_StatusSession):
    def scalar(self, statement):
        self.statement = statement
        return self.report


def legacy_endpoint(monkeypatch):
    monkeypatch.setattr(reports, "_shared_report_write_lock", lambda *_args, **_kwargs: nullcontext())
    monkeypatch.setattr(reports, "_reauthorize_admin_high_risk_in_transaction", lambda *_args: None)
    monkeypatch.setattr(reports, "_agency_review_eligible", lambda *_args: False)
    monkeypatch.setattr(reports, "report_to_response", lambda report: report)
    router = reports.create_router(SimpleNamespace(
        field_test_security_enabled=False, admin_security_enabled=False,
        maintenance_lock_path=None, maintenance_lock_group_gid=None,
    ), object())
    return next(route.endpoint for route in router.routes if route.path == "/reports/{report_id}/status")


def report(status):
    return Report(id=uuid.uuid4(), status=status, status_version=4, payload={}, updated_at=datetime(2026, 1, 1, tzinfo=UTC))


@pytest.mark.parametrize("current,target,outcome", TRANSITIONS)
def test_each_status_pair_preserves_both_route_contracts(monkeypatch, current, target, outcome):
    legacy = legacy_endpoint(monkeypatch)
    first, second = report(current), report(current)
    first_db, second_db = LegacySession(first), _StatusSession(second)
    decision = decide_report_status_transition(current, 4, target)
    assert decision.outcome == outcome

    def legacy_write():
        return legacy(
            first.id, ReportStatusUpdate(status=target), SimpleNamespace(state=SimpleNamespace()),
            x_walksafe_actor_id="reviewer@example.com", db=first_db,
        )

    def admin_write():
        return update_admin_report_status(
            second_db, report_id=second.id,
            payload=AdminReportStatusUpdateV1(status=target, expected_version=4),
            identity=_identity(), correlation_id=uuid.uuid4(), query_sha256="a" * 64,
        )

    if outcome == "allowed":
        legacy_write()
        admin_write()
        assert (first.status, first.status_version) == (second.status, second.status_version) == (target, 5)
        assert first_db.commit_count == second_db.commit_count == 1
        assert {type(item) for item in first_db.added} == {ReportStatusAudit}
        assert {type(item) for item in second_db.added} == {ReportStatusAudit, AdminOperationAudit}
        for db in (first_db, second_db):
            assert db.statement._for_update_arg is not None
            audit = next(item for item in db.added if isinstance(item, ReportStatusAudit))
            assert (audit.previous_status, audit.next_status, audit.previous_version, audit.next_version) == (current, target, 4, 5)
        assert first.payload["status_history"][0]["from"] == current
        assert first.payload["status_history"][0]["to"] == target
        assert second.payload == {}
    else:
        with pytest.raises(HTTPException) as legacy_error:
            legacy_write()
        assert legacy_error.value.status_code == (409 if outcome == "unchanged" else 422)
        if outcome == "unchanged":
            assert legacy_error.value.detail["code"] == "report_status_unchanged"
        with pytest.raises(AdminReportWorkflowError) as admin_error:
            admin_write()
        assert admin_error.value.status_code == 422
        assert admin_error.value.code == "report_status_transition_invalid"
        assert first.status_version == second.status_version == 4
        assert first_db.commit_count == second_db.commit_count == 0
        assert first_db.added == second_db.added == []
        assert second_db.rollback_count == 1


def test_stale_legacy_timestamp_and_admin_version_precede_transition_errors(monkeypatch):
    legacy = legacy_endpoint(monkeypatch)
    current = report("new")
    db = LegacySession(current)
    with pytest.raises(HTTPException) as error:
        legacy(
            current.id, ReportStatusUpdate(status="new", expected_updated_at=current.updated_at - timedelta(seconds=1)),
            SimpleNamespace(state=SimpleNamespace()), x_walksafe_actor_id="reviewer@example.com", db=db,
        )
    assert error.value.status_code == 409
    assert error.value.detail == "report status was updated by another request"
    with pytest.raises(AdminReportWorkflowError) as error:
        update_admin_report_status(
            db, report_id=current.id,
            payload=AdminReportStatusUpdateV1(status="new", expected_version=3),
            identity=_identity(), correlation_id=uuid.uuid4(), query_sha256="a" * 64,
        )
    assert error.value.code == "report_status_version_conflict"
    assert current.status_version == 4 and db.commit_count == 0 and db.added == []
