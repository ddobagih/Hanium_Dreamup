"""Direct caller coverage only; storage, ORM rows, and response conversion are spies."""

from __future__ import annotations

from datetime import UTC, datetime
import importlib
from pathlib import Path
import socket
from types import SimpleNamespace
from uuid import UUID

import pytest
from fastapi import HTTPException
from sqlalchemy.engine import Engine


_EXPECTED_IDS = [
    "00000000-0000-4000-8000-000000000001",
    "00000000-0000-4000-8000-000000000002",
]


class _StorageFailure(RuntimeError):
    pass


@pytest.fixture(params=("legacy", "v2"))
def caller_case(request, monkeypatch, tmp_path):
    """Run real callers with owned dependencies and no database/network access."""
    guard_calls = []

    def forbid_io(*args, **kwargs):
        guard_calls.append("forbidden_io")
        raise AssertionError("This direct caller test must not access DB or network")

    monkeypatch.setattr(Engine, "connect", forbid_io)
    monkeypatch.setattr(Engine, "raw_connection", forbid_io)
    for name in ("connect", "connect_ex", "bind", "listen"):
        monkeypatch.setattr(socket.socket, name, forbid_io)
    monkeypatch.setattr(socket, "create_connection", forbid_io)
    monkeypatch.setattr(socket, "getaddrinfo", forbid_io)
    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[2]))
    reports = importlib.import_module("backend.app.api.reports")

    case = SimpleNamespace(
        variant=request.param,
        guard_calls=guard_calls,
        events=[],
        identity_reads=[],
        storage_calls=[],
        response_calls=[],
        response_error=None,
        rows=[],
        reject_late_identity=False,
        storage_started=False,
        storage_returned=False,
        storage_error=None,
        db=object(),
        key_manager=object(),
    )

    class Candidate:
        def __init__(self, identifier):
            self.identifier = UUID(identifier)

        @property
        def id(self):
            phase = "after_storage" if case.storage_started else "before_storage"
            case.identity_reads.append((str(self.identifier), phase))
            case.events.append("candidate_id")
            if case.reject_late_identity and case.storage_started:
                raise RuntimeError("Candidate identity is unavailable after storage")
            return self.identifier

    candidates = [Candidate(identifier) for identifier in _EXPECTED_IDS]
    case.candidates = candidates
    parsed = SimpleNamespace(
        class_id=0,
        model_class_id=0,
        class_name="damaged_tactile_block",
        confidence=0.9,
        bbox=SimpleNamespace(x=0.1, y=0.2, width=0.3, height=0.4),
        captured_at=datetime(2026, 9, 22, tzinfo=UTC),
        source="fake",
        gps=None,
        heading=None,
        auto_reported=False,
        model_dump=lambda *, mode: {"fixture": "owned-legacy-payload"},
    )
    settings = SimpleNamespace(
        upload_dir=tmp_path,
        maintenance_lock_path=None,
        maintenance_lock_group_gid=None,
        field_test_security_enabled=False,
    )

    def bind_privacy(**kwargs):
        case.events.append("privacy_binding")
        return None, None

    def enrich_payload(payload, **kwargs):
        case.events.append("enrich_payload")
        return {
            "image_sha256": "a" * 64,
            "payload_sha256": "b" * 64,
        }

    def attach_duplicates(payload, selected_candidates):
        case.events.append("duplicate_payload")
        return dict(payload)

    def lock_candidates(db, **kwargs):
        case.events.append("duplicate_lock")

    def find_candidates(db, metadata, **kwargs):
        case.events.append("duplicate_lookup")
        return candidates

    def no_replay(db, **kwargs):
        case.events.append("replay_lookup")
        return None

    def new_row(**kwargs):
        row = SimpleNamespace(**kwargs)
        case.rows.append(row)
        case.events.append("row_created")
        return row

    def storage_boundary(db, report, **kwargs):
        case.storage_calls.append((db, report, kwargs))
        case.events.append("storage_enter")
        case.storage_started = True
        if case.storage_error is not None:
            raise case.storage_error
        case.storage_returned = True
        case.events.append("storage_return")

    def response_boundary(report, duplicate_ids=None, *, creation_outcome):
        assert creation_outcome == "CREATED"
        phase = "after_storage_return" if case.storage_returned else "before_storage_return"
        case.response_calls.append((report, duplicate_ids, phase))
        case.events.append("response")
        if case.response_error is not None:
            raise case.response_error
        return {"duplicate_candidate_ids": duplicate_ids}

    monkeypatch.setattr(reports, "_privacy_report_binding", bind_privacy)
    monkeypatch.setattr(reports, "_enrich_legacy_payload", enrich_payload)
    monkeypatch.setattr(reports, "_sanitized_v2_payload", lambda metadata: {"fixture": "owned-v2-payload"})
    monkeypatch.setattr(reports, "_enrich_v2_payload", enrich_payload)
    monkeypatch.setattr(reports, "report_v2_source", lambda metadata: "fake")
    monkeypatch.setattr(reports, "_is_fake_payload", lambda payload, source: True)
    monkeypatch.setattr(reports, "_enforce_secured_auto_report_gate", lambda *args, **kwargs: None)
    monkeypatch.setattr(reports, "lock_duplicate_candidate_window", lock_candidates)
    monkeypatch.setattr(reports, "find_duplicate_candidates", find_candidates)
    monkeypatch.setattr(reports, "find_duplicate_candidates_v2", find_candidates)
    monkeypatch.setattr(reports, "_with_duplicate_candidate_payload", attach_duplicates)
    monkeypatch.setattr(reports, "_idempotent_report_replay", no_replay)
    monkeypatch.setattr(reports, "Report", new_row)
    monkeypatch.setattr(reports, "_commit_new_report_with_image", storage_boundary)
    monkeypatch.setattr(reports, "_field_creation_response", response_boundary)
    caller = (
        reports._persist_legacy_report
        if request.param == "legacy"
        else reports._persist_v2_report
    )

    def invoke():
        return caller(
            case.db,
            parsed=parsed,
            content=b"owned-caller-input",
            content_type="image/jpeg",
            actor_id="owned-candidate-test",
            account_generation=None,
            settings=settings,
            key_manager=case.key_manager,
        )

    case.invoke = invoke
    yield case
    assert guard_calls == [], "No DB or network operation is allowed, including during import"


def test_postcommit_candidate_identity_is_not_accessed_after_storage(caller_case):
    case = caller_case
    case.reject_late_identity = True

    response = case.invoke()

    assert response == {"duplicate_candidate_ids": _EXPECTED_IDS}
    assert case.identity_reads == [(identifier, "before_storage") for identifier in _EXPECTED_IDS]
    assert len(case.rows) == len(case.storage_calls) == len(case.response_calls) == 1
    assert case.storage_calls[0][0] is case.db
    assert case.storage_calls[0][1] is case.rows[0]
    assert case.response_calls == [(case.rows[0], _EXPECTED_IDS, "after_storage_return")]
    assert case.events[-5:] == [
        "candidate_id", "candidate_id", "storage_enter", "storage_return", "response",
    ]
    assert case.guard_calls == []


def test_postcommit_candidate_snapshot_preserves_unchanged_ids(caller_case):
    case = caller_case

    response = case.invoke()

    assert response == {"duplicate_candidate_ids": _EXPECTED_IDS}
    assert isinstance(response["duplicate_candidate_ids"], list)
    assert case.identity_reads == [(identifier, "before_storage") for identifier in _EXPECTED_IDS]
    assert len(case.rows) == len(case.storage_calls) == len(case.response_calls) == 1
    assert case.storage_returned is True
    assert case.response_calls == [(case.rows[0], _EXPECTED_IDS, "after_storage_return")]
    assert case.guard_calls == []


def test_storage_failure_preserves_exception_without_response_conversion(caller_case):
    case = caller_case
    error = _StorageFailure("Owned storage boundary failure")
    case.storage_error = error
    case.reject_late_identity = True

    with pytest.raises(_StorageFailure) as raised:
        case.invoke()

    assert raised.value is error
    assert case.identity_reads == [(identifier, "before_storage") for identifier in _EXPECTED_IDS]
    assert len(case.rows) == len(case.storage_calls) == 1
    assert case.storage_started is True
    assert case.storage_returned is False
    assert case.response_calls == []
    assert case.events[-3:] == ["candidate_id", "candidate_id", "storage_enter"]
    assert case.guard_calls == []


@pytest.mark.parametrize("logger_fails", [False, True])
def test_response_failure_after_confirmed_storage_has_snapshot_identity(
    caller_case, monkeypatch, logger_fails,
):
    case = caller_case
    error = RuntimeError("private-response-failure")
    case.response_error = error
    events = []

    def warning(message):
        events.append(message)
        if logger_fails:
            raise OSError("private-logger-failure")

    reports = importlib.import_module("backend.app.api.reports")
    monkeypatch.setattr(
        reports, "logging",
        SimpleNamespace(getLogger=lambda _name: SimpleNamespace(warning=warning)),
    )
    with pytest.raises(HTTPException) as raised:
        case.invoke()

    assert case.storage_returned
    assert raised.value.status_code == 503
    assert raised.value.detail == {
        "code": "report_postcommit_response_failed",
        "message": "The report was stored, but its confirmation response is unavailable.",
        "report_id": str(case.rows[0].id),
    }
    assert raised.value.__cause__ is error
    assert events == ["report_postcommit_response_failed"]
    assert len(case.storage_calls) == len(case.response_calls) == 1
