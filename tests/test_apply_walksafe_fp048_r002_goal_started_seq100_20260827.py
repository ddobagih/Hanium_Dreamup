from __future__ import annotations

import copy
from datetime import datetime, timedelta
import hashlib
import os
from pathlib import Path
from types import SimpleNamespace

import pytest

from scripts import apply_walksafe_fp048_r002_goal_started_seq100_20260827 as start


ROOT = Path(__file__).resolve().parents[1]


def _source() -> dict[str, object]:
    history: list[dict[str, object]] = []
    previous = "0" * 64
    for sequence in range(1, start.SOURCE_SEQUENCE):
        event: dict[str, object] = {
            "sequence": sequence,
            "event_id": f"SYNTHETIC-{sequence:03d}",
            "event_type": "SYNTHETIC",
            "occurred_at": "2026-08-27T19:00:00+09:00",
            "previous_event_sha256": previous,
        }
        event["event_sha256"] = start.continuation.event_sha256(event)
        history.append(event)
        previous = str(event["event_sha256"])
    paths = sorted(
        {
            "synthetic/source.txt",
            start.SCRIPT_REL.as_posix(),
            start.TEST_REL.as_posix(),
        }
    )
    path_hash = hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
    runtime = {
        "focus_goal_id": start.TARGET_GOAL_ID,
        "focus_source": "IMPLEMENTATION_BACKLOG",
    }
    correction: dict[str, object] = {
        "sequence": start.SOURCE_SEQUENCE,
        "event_id": start.SOURCE_EVENT_ID,
        "event_type": "GOAL_START_GATE_EXECUTION_CORRECTED",
        "occurred_at": "2026-08-27T20:00:00+09:00",
        "subject_goal_id": start.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "runtime_after": runtime,
        "repository_context_reanchor": {
            "after": {
                "managed_changed_path_count": len(paths),
                "path_set_sha256": path_hash,
                "content_set_sha256": "2" * 64,
            }
        },
        "previous_event_sha256": previous,
    }
    correction["event_sha256"] = start.continuation.event_sha256(correction)
    history.append(correction)
    return {
        "schema_version": "1.25.0",
        "approved_state": {
            "formal_test_count": 279,
            "formal_test_not_run_count": 279,
            "remaining_gate_count": 5,
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
            "work_item_id": start.WORK_ITEM_ID,
            "status": "READY",
            "current_focus": _SyntheticCorrection.CURRENT_FOCUS,
            "next_action": _SyntheticCorrection.NEXT_ACTION,
            "release_completion_claimed": False,
        },
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": correction["event_sha256"],
            "validation_cutoff_at": correction["occurred_at"],
            "goal_status": "READY",
            "status_by_goal": {
                start.TARGET_GOAL_ID: "READY",
                "WS-GOAL-EPIC-04": "READY",
            },
            "focus_goal_id": start.TARGET_GOAL_ID,
            "focus_goal_path": "synthetic/fp048-r002.md",
            "focus_work_item_id": start.WORK_ITEM_ID,
            "focus_source": "IMPLEMENTATION_BACKLOG",
            "ready_frontier_goal_ids": [
                start.TARGET_GOAL_ID,
                "WS-GOAL-EPIC-04",
            ],
            "pending_producer_completion_goal_id": None,
            "completion_evidence_by_goal": {},
            "blockers_by_goal": {},
            "blocker_resolution_history": [],
            "blocked_goal_ids": [],
            "pending_questions": [],
            "open_question_count": 0,
            "artifact_work_queue": {},
            "completion_boundary": {},
            "activation_status": "ACTIVE",
            "package_status": "ACTIVE",
        },
        "working_tree_snapshot": {
            "scope": _SyntheticCorrection.SCOPE,
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": path_hash,
            "content_set_sha256": "2" * 64,
        },
        "session_handoff": {
            "changed_files": copy.deepcopy(paths),
            "current_epic": _SyntheticCorrection.HANDOFF_CURRENT_EPIC,
            "last_updated_by_work_item": start.WORK_ITEM_ID,
            "last_verification_status": (
                _SyntheticCorrection.HANDOFF_VERIFICATION_STATUS
            ),
            "next_single_action": _SyntheticCorrection.NEXT_ACTION,
            "source_commit_or_snapshot": {
                "file_count": len(paths),
                "path_set_sha256": path_hash,
                "content_set_sha256": "2" * 64,
            },
        },
    }


class _SyntheticCorrection:
    CURRENT_FOCUS = (
        "FP-048 R002 READY after seq99 R010 post-check failure correction; "
        "R010 -005 is consumed failed nonauthority and R011 is not yet run"
    )
    NEXT_ACTION = "run R011"
    SCOPE = "synthetic seq99 correction"
    WORK_ITEM_ID = start.WORK_ITEM_ID
    HANDOFF_CURRENT_EPIC = "EPIC-03 / FP-048 R002 R010 execution correction"
    HANDOFF_VERIFICATION_STATUS = "SEQ99_R011_CORRECTION_REVIEWED_ZERO_CREDIT"

    @staticmethod
    def require_start_gate_execution_corrected_checkpoint(
        _root: Path,
        _checkpoint: dict[str, object],
        *,
        require_live_snapshot: bool,
        run_external_validators: bool,
    ) -> None:
        assert require_live_snapshot is False
        assert run_external_validators is False

    @staticmethod
    def canonical_seq99_checkpoint_bytes(
        _root: Path, checkpoint: dict[str, object]
    ) -> bytes:
        return start.checkpoint_bytes(checkpoint)


def _evidence(source: dict[str, object] | None = None) -> start.GateEvidence:
    source = _source() if source is None else source
    start_time = datetime.fromisoformat("2026-08-27T20:00:01+09:00")
    log_hashes = {
        check_id: hashlib.sha256(check_id.encode()).hexdigest()
        for check_id in start.EXPECTED_CHECK_IDS
    }
    runs = []
    for index, check_id in enumerate(start.EXPECTED_CHECK_IDS, start=1):
        runs.append(
            {
                "check_id": check_id,
                "command": f"synthetic {check_id}",
                "output_path": (
                    start.GATE_RECEIPT_REL.parent
                    / f"{index:02d}-{check_id}.log"
                ).as_posix(),
                "output_sha256": log_hashes[check_id],
                "exit_code": 0,
                "executed_at": (
                    start_time + timedelta(microseconds=index)
                ).isoformat(),
            }
        )
    receipt = {
        "schema_version": "1.1",
        "document_id": start.GATE_DOCUMENT_ID,
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": "synthetic-package",
        "target_transition_event_id": start.EVENT_ID,
        "target_goal_id": start.TARGET_GOAL_ID,
        "target_goal_content_sha256": start.TARGET_GOAL_SHA256,
        "static_plan_manifest_sha256": start.MANIFEST_SHA256,
        "source_activation_event_sha256": source["goal_execution"][
            "transition_history"
        ][-1]["event_sha256"],
        "source_checkpoint_sha256": start.sha256_bytes(
            start.checkpoint_bytes(source)
        ),
        "source_ready_event_sha256": "3" * 64,
        "check_command_contract_version": "2026-08-27.2",
        "check_command_contract_sha256": "4" * 64,
        "implementation_start_gate_contract_binding": {
            "path": "synthetic/r011.json",
            "file_sha256": "5" * 64,
        },
        "runtime_bindings": [],
        "execution_window": {
            "started_at": start_time.isoformat(),
            "ended_at": (start_time + timedelta(seconds=1)).isoformat(),
        },
        "check_runs": runs,
        "repository_snapshot": {"status": "FROZEN_R011"},
        "generated_at": (start_time + timedelta(seconds=2)).isoformat(),
    }
    raw = start.gate_receipt_bytes(receipt)
    return start.GateEvidence(
        receipt=receipt,
        receipt_bytes=raw,
        receipt_binding={
            "document_id": start.GATE_DOCUMENT_ID,
            "path": start.GATE_RECEIPT_REL.as_posix(),
            "file_sha256": start.sha256_bytes(raw),
        },
        event_occurred_at=(start_time + timedelta(seconds=3)).isoformat(),
        log_sha256_by_check_id=log_hashes,
    )


def _projection() -> tuple[dict[str, object], start.GateEvidence, dict[str, object]]:
    source = _source()
    evidence = _evidence(source)
    managed = [
        *source["working_tree_snapshot"]["managed_changed_paths"],
        *(
            path.as_posix()
            for path in start._publication_only_paths()
            if path not in {start.SCRIPT_REL, start.TEST_REL}
        ),
    ]
    projected, _event = start.project_seq100(
        ROOT,
        source,
        evidence,
        managed_paths=managed,
        snapshot_hashes=("6" * 64, "7" * 64),
    )
    return source, evidence, projected


def _prepared_for_write(tmp_path: Path) -> start.PreparedProjection:
    source, evidence, projected = _projection()
    checkpoint = tmp_path / start.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(start.checkpoint_bytes(source))
    checkpoint.chmod(0o600)
    source_read = start.seq90._stable_read(tmp_path, start.CHECKPOINT_REL)
    return start.PreparedProjection(
        root=tmp_path,
        source_bytes=source_read.raw,
        source=source,
        projected=projected,
        projected_bytes=start.checkpoint_bytes(projected),
        event=projected["goal_execution"]["transition_history"][-1],
        evidence=evidence,
        source_identity=source_read.identity,
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=b"",
        git_head="0" * 40,
        git_branch="test",
    )


def _rebind(receipt: dict[str, object], evidence: start.GateEvidence) -> start.GateEvidence:
    raw = start.gate_receipt_bytes(receipt)
    return start.GateEvidence(
        receipt=receipt,
        receipt_bytes=raw,
        receipt_binding={
            "document_id": start.GATE_DOCUMENT_ID,
            "path": start.GATE_RECEIPT_REL.as_posix(),
            "file_sha256": start.sha256_bytes(raw),
        },
        event_occurred_at=evidence.event_occurred_at,
        log_sha256_by_check_id=evidence.log_sha256_by_check_id,
    )


def test_seq100_starts_only_target_and_preserves_zero_credit() -> None:
    source, evidence, projected = _projection()
    start.validate_projection(ROOT, source, projected, evidence)
    state = projected["goal_execution"]
    event = state["transition_history"][-1]
    assert event["sequence"] == 100
    assert event["event_id"] == start.EVENT_ID
    assert event["status_changes"] == {start.TARGET_GOAL_ID: "IN_PROGRESS"}
    assert state["status_by_goal"][start.TARGET_GOAL_ID] == "IN_PROGRESS"
    assert state["status_by_goal"]["WS-GOAL-EPIC-04"] == "READY"
    assert list(state["status_by_goal"].values()).count("IN_PROGRESS") == 1
    assert state["focus_goal_id"] == start.TARGET_GOAL_ID
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]


def test_source_rejects_boolean_sequence_and_wrong_correction_id() -> None:
    source = _source()
    source["goal_execution"]["transition_history"][-1]["sequence"] = True
    with pytest.raises(start.StartApplyError, match="execution-failure correction"):
        start._require_source_shape(source)

    source = _source()
    source["goal_execution"]["transition_history"][-1]["event_id"] = "FORGED"
    with pytest.raises(start.StartApplyError, match="execution-failure correction"):
        start._require_source_shape(source)


@pytest.mark.parametrize("kind", ["status", "source", "log", "chronology", "r010"])
def test_r011_receipt_tamper_and_r010_namespace_are_rejected(kind: str) -> None:
    source = _source()
    evidence = _evidence(source)
    receipt = copy.deepcopy(dict(evidence.receipt))
    if kind == "status":
        receipt["status"] = "FAIL"
    elif kind == "source":
        receipt["source_checkpoint_sha256"] = "f" * 64
    elif kind == "log":
        receipt["check_runs"][0]["output_sha256"] = "e" * 64
    elif kind == "chronology":
        receipt["execution_window"]["started_at"] = source["goal_execution"][
            "transition_history"
        ][-1]["occurred_at"]
    else:
        receipt["document_id"] = (
            "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-"
            "FP048-R002-20260827-005"
        )
        receipt["target_transition_event_id"] = (
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260827-005"
        )
    forged = _rebind(receipt, evidence)
    with pytest.raises(start.StartApplyError):
        start.validate_gate_evidence(source, forged)


def test_exact_seq100_inverse_and_public_apis(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, evidence, projected = _projection()
    monkeypatch.setattr(start, "_correction", lambda: _SyntheticCorrection)
    monkeypatch.setattr(
        start,
        "require_published_r011_gate_for_seq99",
        lambda *_args, **_kwargs: evidence,
    )
    assert start.reconstructed_seq99_checkpoint_bytes(ROOT, projected) == (
        start.checkpoint_bytes(source)
    )
    start.require_started_checkpoint(
        ROOT,
        projected,
        require_live_snapshot=False,
        run_external_validators=False,
    )
    assert start.canonical_seq100_checkpoint_bytes(ROOT, projected) == (
        start.checkpoint_bytes(projected)
    )
    assert start.goal_start_gate_receipt_binding(ROOT, projected) == (
        evidence.receipt_binding
    )


def test_inverse_rejects_boolean_event_and_ambiguous_preimage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _source_value, _evidence_value, projected = _projection()
    monkeypatch.setattr(start, "_correction", lambda: _SyntheticCorrection)
    projected["goal_execution"]["transition_history"][-1]["sequence"] = True
    with pytest.raises(start.StartApplyError, match="inverse event"):
        start.reconstructed_seq99_checkpoint_bytes(ROOT, projected)

    _source_value, _evidence_value, projected = _projection()
    projected["goal_execution"]["transition_history"][-2][
        "repository_context_reanchor"
    ]["after"]["managed_changed_path_count"] = True
    with pytest.raises(start.StartApplyError, match="snapshot seal"):
        start.reconstructed_seq99_checkpoint_bytes(ROOT, projected)


def test_source_cas_drift_is_rejected_before_writer(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = tmp_path / start.CHECKPOINT_REL
    replacement = checkpoint.with_name("same-bytes-new-inode.json")
    replacement.write_bytes(prepared.source_bytes)
    replacement.chmod(0o600)
    os.replace(replacement, checkpoint)
    called = False

    def writer(*_args: object, **_kwargs: object) -> None:
        nonlocal called
        called = True

    monkeypatch.setattr(start, "_require_prepared_exact", lambda *_a, **_k: None)
    with pytest.raises(start.StartApplyError, match="seq99 source changed"):
        start.write_projection(prepared, writer=writer)
    assert called is False


def test_precommit_failure_preserves_source_and_postcommit_is_uncertain(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = tmp_path / start.CHECKPOINT_REL
    monkeypatch.setattr(start, "_require_prepared_exact", lambda *_a, **_k: None)

    def before_commit(*_args: object, **_kwargs: object) -> None:
        raise start.StartApplyError("injected before replace")

    with pytest.raises(start.StartApplyError, match="injected"):
        start.write_projection(prepared, writer=before_commit)
    assert checkpoint.read_bytes() == prepared.source_bytes

    def after_commit(transport: object, *, commit_guard: object) -> None:
        commit_guard()
        checkpoint.write_bytes(transport.projected_raw)
        checkpoint.chmod(0o600)
        raise start.StartApplyError("injected after replace")

    with pytest.raises(start.seq90.PostcommitUncertain):
        start.write_projection(prepared, writer=after_commit)


def test_cli_modes_are_explicit_and_preflight_does_not_call_write(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = SimpleNamespace(event={"event_sha256": "a" * 64})
    monkeypatch.setattr(start, "prepare_projection", lambda _root: prepared)
    monkeypatch.setattr(
        start,
        "write_projection",
        lambda *_a, **_k: pytest.fail("preflight wrote checkpoint"),
    )
    assert start.main(["--preflight"]) == 0
    assert start.parse_args(["--write"]).write is True
    with pytest.raises(SystemExit):
        start.parse_args([])
    with pytest.raises(SystemExit):
        start.parse_args(["--preflight", "--write"])
