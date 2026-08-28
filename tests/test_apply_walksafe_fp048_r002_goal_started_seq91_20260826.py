from __future__ import annotations

import copy
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from scripts import apply_walksafe_fp048_r002_goal_started_seq91_20260826 as start
from scripts import run_walksafe_fp048_r002_goal_start_gate_r002_20260826 as gate


GOAL_SHA256 = "a" * 64


def _source() -> dict[str, Any]:
    history: list[dict[str, Any]] = []
    previous = "0" * 64
    for sequence in range(1, 89):
        event = {
            "sequence": sequence,
            "event_id": f"SYNTHETIC-{sequence:03d}",
            "event_type": "SYNTHETIC",
            "occurred_at": "2026-08-26T00:00:00+09:00",
            "previous_event_sha256": previous,
        }
        event["event_sha256"] = start.contract.event_sha256(event)
        history.append(event)
        previous = event["event_sha256"]
    ready = {
        "sequence": 89,
        "event_id": "SYNTHETIC-FP048-R002-READY",
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-26T01:29:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = start.contract.event_sha256(ready)
    binding = {
        "schema_version": "1.1",
        "document_id": "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-002",
        "path": gate.R002_CONTRACT_RELATIVE.as_posix(),
        "file_sha256": "b" * 64,
        "contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R002",
        "contract_version": "2026-08-26.1",
        "canonical_contract_sha256": "c" * 64,
    }
    control = {
        "sequence": 90,
        "event_id": (
            "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
            "FP048-R002-20260826-001"
        ),
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_at": "2026-08-26T01:30:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "runtime_after": {
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_source": "IMPLEMENTATION_BACKLOG",
        },
        "contract_supersession": {"replacement_contract_binding": binding},
        "previous_event_sha256": ready["event_sha256"],
    }
    control["event_sha256"] = start.contract.event_sha256(control)
    history.extend((ready, control))
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
            "transition_history_anchor_sha256": control["event_sha256"],
            "validation_cutoff_at": control["occurred_at"],
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
            "scope": "synthetic seq90",
            "managed_changed_paths": ["synthetic/source.txt"],
            "managed_changed_path_count": 1,
            "path_set_sha256": "d" * 64,
            "content_set_sha256": "e" * 64,
        },
        "session_handoff": {
            "changed_files": ["synthetic/source.txt"],
            "source_commit_or_snapshot": {
                "file_count": 1,
                "path_set_sha256": "d" * 64,
                "content_set_sha256": "e" * 64,
            },
            "current_epic": "EPIC-03",
            "last_updated_by_work_item": "synthetic",
            "last_verification_status": "READY",
        },
    }


def _evidence(root: Path, source: dict[str, Any]) -> start.GateEvidence:
    receipt = {
        "source_checkpoint_sha256": start.sha256_bytes(start.json_bytes(source)),
        "repository_snapshot": {
            "gate_event_id": gate.STARTED_EVENT_ID,
            "snapshot_scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
        }
    }
    receipt_bytes = start.json_bytes(receipt)
    receipt_relative = (
        gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID / gate.RECEIPT_NAME
    )
    receipt_path = root / receipt_relative
    receipt_path.parent.mkdir(parents=True, mode=0o700)
    receipt_path.parent.chmod(0o700)
    receipt_path.write_bytes(receipt_bytes)
    receipt_path.chmod(0o600)
    return start.GateEvidence(
        receipt=receipt,
        receipt_bytes=receipt_bytes,
        receipt_binding={
            "document_id": gate._document_id(gate.STARTED_EVENT_ID),
            "path": receipt_relative.as_posix(),
            "file_sha256": start.sha256_bytes(receipt_bytes),
        },
        repository_payload={"synthetic": True},
        event_occurred_at="2026-08-26T01:31:01+09:00",
    )


def _patch_runtime(
    source: dict[str, Any],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    history = source["goal_execution"]["transition_history"]
    ready = history[88]
    control = history[89]
    binding = control["contract_supersession"]["replacement_contract_binding"]
    materializer = SimpleNamespace(
        WORK_ITEM_ID="EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT",
        READY_EVENT_ID=ready["event_id"],
    )
    reanchor = SimpleNamespace(
        CONTROL_REANCHOR_SEQUENCE=90,
        CONTROL_REANCHOR_EVENT_ID=control["event_id"],
        require_control_reanchored_checkpoint=lambda _root, value: (
            None
            if value is source
            else (_ for _ in ()).throw(ValueError("wrong checkpoint"))
        ),
        reconstructed_seq90_checkpoint_bytes=lambda _root, _value: (
            start.json_bytes(source)
        ),
    )
    authority = SimpleNamespace(
        ready_event_sha256=ready["event_sha256"],
        reanchor_event_sha256=control["event_sha256"],
    )
    monkeypatch.setattr(start, "_reanchor", lambda: reanchor)
    monkeypatch.setattr(gate, "_materializer", lambda: materializer)
    monkeypatch.setattr(gate, "bind_reanchored_source", lambda _root, value: (
        authority
        if value is source
        else (_ for _ in ()).throw(ValueError("wrong checkpoint"))
    ))
    monkeypatch.setattr(gate, "expected_contract_binding", lambda: binding)
    gate._impl.TARGET_GOAL_SHA256 = GOAL_SHA256


def test_seq91_projection_is_exact_one_ready_to_in_progress_zero_credit_change(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    final_paths = {
        Path("synthetic/source.txt"): "1" * 64,
        start.SCRIPT_RELATIVE: "2" * 64,
        start.TEST_RELATIVE: "3" * 64,
    }
    projected, event = start.project_seq91(
        tmp_path,
        source,
        _evidence(tmp_path, source),
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=final_paths,
    )
    assert set(event) == start.EVENT_FIELDS
    assert event["sequence"] == 91
    assert event["event_id"] == gate.STARTED_EVENT_ID
    assert event["previous_event_sha256"] == source["goal_execution"][
        "transition_history"
    ][-1]["event_sha256"]
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


def test_seq91_rejects_old_event_id_and_changed_seq90_authority(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    final_paths = {
        Path("synthetic/source.txt"): "1" * 64,
        start.SCRIPT_RELATIVE: "2" * 64,
        start.TEST_RELATIVE: "3" * 64,
    }
    with pytest.raises(start.StartApplyError, match="event ID"):
        start.project_seq91(
            tmp_path,
            source,
            _evidence(tmp_path, source),
            event_id=(
                "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260825-001"
            ),
            final_sha256_by_path=final_paths,
        )
    source["goal_execution"]["transition_history"][-1]["status_changes"] = {
        gate.TARGET_GOAL_ID: "IN_PROGRESS"
    }
    with pytest.raises(start.StartApplyError, match="seq90"):
        start.require_exact_source(tmp_path, source)


def test_seq91_post_validator_rejects_resealed_wrong_source_checkpoint_sha256(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    evidence = _evidence(tmp_path, source)
    final_paths = {
        Path("synthetic/source.txt"): "1" * 64,
        start.SCRIPT_RELATIVE: "2" * 64,
        start.TEST_RELATIVE: "3" * 64,
    }
    projected, _ = start.project_seq91(
        tmp_path,
        source,
        evidence,
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=final_paths,
    )
    assert start.validate_history_suffix(tmp_path, projected) == []

    tampered_receipt = copy.deepcopy(evidence.receipt)
    tampered_receipt["source_checkpoint_sha256"] = "f" * 64
    tampered_bytes = start.json_bytes(tampered_receipt)
    receipt_path = tmp_path / evidence.receipt_binding["path"]
    receipt_path.write_bytes(tampered_bytes)
    receipt_path.chmod(0o600)
    event = projected["goal_execution"]["transition_history"][90]
    event["implementation_start_gate_binding"]["file_sha256"] = (
        start.sha256_bytes(tampered_bytes)
    )
    event["event_sha256"] = start.contract.event_sha256(event)

    errors = start.validate_history_suffix(tmp_path, projected)
    assert errors == ["seq91 gate receipt source checkpoint SHA-256 differs"]


def test_failed_preflight_never_calls_publication(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    writes: list[str] = []

    def fail(*_args: object, **_kwargs: object) -> Any:
        raise start.StartApplyError("synthetic missing fresh PASS receipt")

    monkeypatch.setattr(start, "prepare_projection", fail)
    monkeypatch.setattr(
        start, "write_projection", lambda *_args, **_kwargs: writes.append("checkpoint")
    )
    monkeypatch.setattr(
        start, "write_catalogs", lambda *_args, **_kwargs: writes.append("catalogs")
    )
    result = start.main(
        ["--write", "--event-id", gate.STARTED_EVENT_ID, "--root", str(tmp_path)]
    )
    assert result == 1
    assert writes == []


def test_checkpoint_transport_uses_source_cas_and_commit_guard(
    tmp_path: Path,
) -> None:
    target = tmp_path / "checkpoint.json"
    source = b"source\n"
    projected = b"projected\n"
    target.write_bytes(source)
    target.chmod(0o600)
    calls: list[str] = []

    def guard() -> None:
        calls.append("guard")

    def writer(
        path: Path,
        content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Any,
    ) -> None:
        calls.append("writer")
        assert path.read_bytes() == expected_source == source
        commit_guard()
        path.write_bytes(content)
        path.chmod(0o600)

    start._write_projection_transport(
        writer,
        target,
        projected,
        expected_source=source,
        commit_guard=guard,
    )
    assert target.read_bytes() == projected
    assert calls == ["writer", "guard"]


def test_zero_credit_boundary_distinguishes_bool_from_integer() -> None:
    source = _source()
    source["approved_state"]["formal_test_count"] = True
    source["approved_state"]["formal_test_not_run_count"] = True
    with pytest.raises(start.StartApplyError, match="approved zero-credit"):
        start._require_zero_credit_source(source)
