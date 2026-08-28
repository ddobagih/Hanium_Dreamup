from __future__ import annotations

import copy
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from scripts import apply_walksafe_fp048_r002_goal_started_seq90_20260825 as start
from scripts import run_walksafe_fp048_r002_goal_start_gate_20260825 as gate


GOAL_SHA256 = "a" * 64


def _source() -> dict[str, Any]:
    history: list[dict[str, Any]] = []
    previous = "0" * 64
    for sequence in range(1, 89):
        event = {
            "sequence": sequence,
            "event_id": f"SYNTHETIC-{sequence:03d}",
            "event_type": "SYNTHETIC",
            "occurred_at": "2026-08-25T00:00:00+09:00",
            "previous_event_sha256": previous,
        }
        event["event_sha256"] = start.contract.event_sha256(event)
        history.append(event)
        previous = event["event_sha256"]
    ready = {
        "sequence": 89,
        "event_id": "SYNTHETIC-FP048-R002-READY",
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-25T01:29:00+09:00",
        "runtime_after": {
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_source": "IMPLEMENTATION_BACKLOG",
        },
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = start.contract.event_sha256(ready)
    history.append(ready)
    return {
        "schema_version": "1.25.0",
        "approved_state": {
            "formal_test_count": 279,
            "formal_test_not_run_count": 279,
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "authority_boundary": {"normative_policy_source": "synthetic"},
        "verification_boundary": {
            "actual_device_test_status": "NOT_RUN",
            "all_remaining_gate_status": "NOT_RUN",
            "formal_test_pass_claimed": False,
            "implementation_conformance_claimed": False,
            "release_eligible": False,
        },
        "canonical_bindings": [{"role": "SYNTHETIC"}],
        "current_work": {
            "work_item_id": "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT",
            "status": "READY",
            "current_focus": "FP048 R002 READY",
            "release_completion_claimed": False,
        },
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": ready["event_sha256"],
            "validation_cutoff_at": ready["occurred_at"],
            "status_by_goal": {
                gate.TARGET_GOAL_ID: "READY",
                gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
            },
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_path": "synthetic/fp048-r002.md",
            "focus_work_item_id": (
                "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
            ),
            "focus_source": "IMPLEMENTATION_BACKLOG",
            "ready_frontier_goal_ids": [gate.TARGET_GOAL_ID],
            "blockers_by_goal": {},
            "blocker_resolution_history": [],
            "blocked_goal_ids": [],
            "pending_questions": [],
            "open_question_count": 0,
            "completion_evidence_by_goal": {},
            "archived_completion_evidence_by_goal": {},
            "imported_predecessor_goal_bindings": {},
            "verification_evidence_refs": [],
            "artifact_work_queue": {},
            "completion_boundary": {},
            "dynamic_goal_inventory": {},
            "materialized_child_goal_ids_by_parent": {},
        },
        "working_tree_snapshot": {
            "scope": "synthetic seq89",
            "managed_changed_paths": ["synthetic/source.txt"],
            "managed_changed_path_count": 1,
            "path_set_sha256": "b" * 64,
            "content_set_sha256": "c" * 64,
        },
        "session_handoff": {
            "changed_files": ["synthetic/source.txt"],
            "source_commit_or_snapshot": {
                "file_count": 1,
                "path_set_sha256": "b" * 64,
                "content_set_sha256": "c" * 64,
            },
            "current_epic": "EPIC-03",
            "last_updated_by_work_item": "synthetic",
            "last_verification_status": "READY",
        },
    }


def _evidence() -> start.GateEvidence:
    receipt = {
        "repository_snapshot": {
            "gate_event_id": gate.STARTED_EVENT_ID,
            "snapshot_scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
        }
    }
    return start.GateEvidence(
        receipt=receipt,
        receipt_bytes=b"{}\n",
        receipt_binding={
            "document_id": gate._document_id(gate.STARTED_EVENT_ID),
            "path": (
                gate.GATE_ROOT_RELATIVE
                / gate.STARTED_EVENT_ID
                / gate.RECEIPT_NAME
            ).as_posix(),
            "file_sha256": "d" * 64,
        },
        repository_payload={"synthetic": True},
        event_occurred_at="2026-08-25T01:30:01+09:00",
    )


def _patch_runtime(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        gate,
        "_materializer",
        lambda: SimpleNamespace(
            WORK_ITEM_ID=(
                "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
            )
        ),
    )
    gate._impl.TARGET_GOAL_SHA256 = GOAL_SHA256
    monkeypatch.setattr(
        start,
        "require_exact_source",
        lambda *_args, **_kwargs: None,
    )


def test_seq90_projection_is_one_ready_to_in_progress_zero_credit_change(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_runtime(monkeypatch)
    source = _source()
    final_paths = {
        Path("synthetic/source.txt"): "1" * 64,
        start.SCRIPT_RELATIVE: "2" * 64,
        start.TEST_RELATIVE: "3" * 64,
    }
    projected, event = start.project_seq90(
        tmp_path,
        source,
        _evidence(),
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=final_paths,
    )
    assert set(event) == start.EVENT_FIELDS
    assert event["sequence"] == 90
    assert event["event_id"] == gate.STARTED_EVENT_ID
    assert event["previous_event_sha256"] == source["goal_execution"][
        "transition_history"
    ][-1]["event_sha256"]
    assert event["previous_focus_content_sha256"] == GOAL_SHA256
    assert event["status_changes"] == {gate.TARGET_GOAL_ID: "IN_PROGRESS"}
    assert projected["goal_execution"]["status_by_goal"] == {
        gate.TARGET_GOAL_ID: "IN_PROGRESS",
        gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
    }
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert projected["canonical_bindings"] == source["canonical_bindings"]
    assert projected["current_work"]["status"] == "IN_PROGRESS"
    assert "GAP-057" in projected["current_work"]["current_focus"]
    assert start.validate_history_suffix(tmp_path, projected) == []


def test_seq90_rejects_wrong_id_and_resealed_transition(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_runtime(monkeypatch)
    source = _source()
    final_paths = {
        Path("synthetic/source.txt"): "1" * 64,
        start.SCRIPT_RELATIVE: "2" * 64,
        start.TEST_RELATIVE: "3" * 64,
    }
    with pytest.raises(start.StartApplyError, match="event ID"):
        start.project_seq90(
            tmp_path,
            source,
            _evidence(),
            event_id=(
                "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260825-002"
            ),
            final_sha256_by_path=final_paths,
        )
    projected, _ = start.project_seq90(
        tmp_path,
        source,
        _evidence(),
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=final_paths,
    )
    tampered = copy.deepcopy(projected)
    event = tampered["goal_execution"]["transition_history"][89]
    event["to_status"] = "COMPLETE_AT_TARGET"
    event["event_sha256"] = start.contract.event_sha256(event)
    errors = start.validate_history_suffix(tmp_path, tampered)
    assert errors and "event authority" in errors[0]


def test_failed_preflight_never_calls_publication(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    writes: list[str] = []

    def fail(*_args: object, **_kwargs: object) -> Any:
        raise start.StartApplyError("synthetic missing PASS receipt")

    monkeypatch.setattr(start, "prepare_projection", fail)
    monkeypatch.setattr(
        start,
        "write_projection",
        lambda *_args, **_kwargs: writes.append("checkpoint"),
    )
    monkeypatch.setattr(
        start,
        "write_catalogs",
        lambda *_args, **_kwargs: writes.append("catalogs"),
    )
    result = start.main(
        [
            "--write",
            "--event-id",
            gate.STARTED_EVENT_ID,
            "--root",
            str(tmp_path),
        ]
    )
    assert result == 1
    assert writes == []


def test_zero_credit_boundary_distinguishes_bool_from_integer() -> None:
    source = _source()
    source["approved_state"]["formal_test_count"] = True
    source["approved_state"]["formal_test_not_run_count"] = True
    with pytest.raises(start.StartApplyError, match="approved zero-credit"):
        start._require_zero_credit_source(source)
