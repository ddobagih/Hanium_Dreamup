from __future__ import annotations

import copy
from datetime import datetime
import os
from pathlib import Path

import pytest

from scripts import apply_walksafe_fp048_r002_goal_completed_seq99_100_20260826 as completion


ROOT = Path(__file__).resolve().parents[1]


def _source() -> dict[str, object]:
    history: list[dict[str, object]] = []
    previous = "0" * 64
    for sequence in range(1, completion.SOURCE_SEQUENCE):
        event: dict[str, object] = {
            "sequence": sequence,
            "event_id": f"SYNTHETIC-{sequence:03d}",
            "event_type": "SYNTHETIC",
            "occurred_at": "2026-08-26T19:00:00+09:00",
            "previous_event_sha256": previous,
        }
        event["event_sha256"] = completion.continuation.event_sha256(event)
        history.append(event)
        previous = str(event["event_sha256"])
    started_event: dict[str, object] = {
        "sequence": completion.SOURCE_SEQUENCE,
        "event_id": completion.SOURCE_EVENT_ID,
        "event_type": "GOAL_STARTED",
        "occurred_on": "2026-08-26",
        "occurred_at": "2026-08-26T20:00:02+09:00",
        "previous_focus_goal_id": completion.GOAL_ID,
        "previous_focus_content_sha256": completion.GOAL_SHA256,
        "focus_goal_id": completion.GOAL_ID,
        "focus_goal_content_sha256": completion.GOAL_SHA256,
        "subject_goal_id": completion.GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": completion.MANIFEST_SHA256,
        "status_changes": {completion.GOAL_ID: "IN_PROGRESS"},
        "runtime_after": {"focus_goal_id": completion.GOAL_ID},
        "repository_snapshot_before": {"status": "FROZEN_R009"},
        "implementation_start_gate_binding": {
            "document_id": "WS-FP048-R002-R009-PASS",
            "path": "private/r009/receipt.json",
            "file_sha256": "1" * 64,
        },
        "blockers_after": {},
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": "1.25.0",
        "evidence_refs": [],
        "previous_event_sha256": previous,
    }
    started_event["event_sha256"] = completion.continuation.event_sha256(
        started_event
    )
    history.append(started_event)
    boundary = {
        "schema_version": "1.0",
        "internal_runnable_goal_ids": [
            completion.GOAL_ID,
            completion.PARENT_GOAL_ID,
            completion.EPIC04_GOAL_ID,
            completion.EPIC12_GOAL_ID,
        ],
        "internal_pending_goal_ids": [
            completion.GOAL_ID,
            completion.PARENT_GOAL_ID,
            completion.EPIC04_GOAL_ID,
            completion.EPIC12_GOAL_ID,
        ],
    }
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
        "canonical_bindings": [
            {
                "role": "SYNTHETIC",
                "document_id": "SYNTHETIC-001",
                "path": "synthetic/input.json",
                "file_sha256": "2" * 64,
                "identity_json_path": "document_id",
                "mutable": False,
            }
        ],
        "current_work": {
            "work_item_id": completion.WORK_ITEM_ID,
            "title": "FP048 R002",
            "source_policy_ids": ["FP-048"],
            "gap_ids": ["GAP-057"],
            "status": "IN_PROGRESS",
            "current_focus": "FP048 R002 in progress",
            "next_action": "finish product",
            "release_completion_claimed": False,
        },
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": started_event["event_sha256"],
            "validation_cutoff_at": started_event["occurred_at"],
            "goal_status": "IN_PROGRESS",
            "status_by_goal": {
                completion.GOAL_ID: "IN_PROGRESS",
                completion.PARENT_GOAL_ID: "READY",
                completion.EPIC04_GOAL_ID: "READY",
                completion.EPIC12_GOAL_ID: "READY",
            },
            "focus_goal_id": completion.GOAL_ID,
            "focus_goal_path": completion.frozen_completion.GOAL_PATH,
            "focus_work_item_id": completion.WORK_ITEM_ID,
            "focus_source": "IMPLEMENTATION_BACKLOG",
            "ready_frontier_goal_ids": [
                completion.GOAL_ID,
                completion.PARENT_GOAL_ID,
                completion.EPIC04_GOAL_ID,
                completion.EPIC12_GOAL_ID,
            ],
            "pending_producer_completion_goal_id": None,
            "completion_evidence_by_goal": {},
            "archived_completion_evidence_by_goal": {},
            "verification_evidence_refs": [],
            "blockers_by_goal": {},
            "blocker_resolution_history": [],
            "blocked_goal_ids": [],
            "pending_questions": [],
            "open_question_count": 0,
            "artifact_work_queue": {"schema_version": "1.0"},
            "completion_boundary": boundary,
            "activation_status": "ACTIVE",
            "package_status": "ACTIVE",
        },
        "working_tree_snapshot": {
            "scope": "synthetic seq98",
            "managed_changed_paths": ["synthetic/input.json"],
            "managed_changed_path_count": 1,
            "path_set_sha256": "3" * 64,
            "content_set_sha256": "4" * 64,
        },
        "session_handoff": {
            "changed_files": ["synthetic/input.json"],
            "current_epic": "EPIC-03 FP048 R002 IN_PROGRESS",
            "last_updated_by_work_item": completion.WORK_ITEM_ID,
            "last_verification_status": "IN_PROGRESS",
            "next_single_action": "finish product",
            "source_commit_or_snapshot": {
                "file_count": 1,
                "path_set_sha256": "3" * 64,
                "content_set_sha256": "4" * 64,
            },
        },
    }


def _verification() -> dict[str, object]:
    focused = {
        "tests": 13,
        "pass": 13,
        "fail": 0,
        "cancelled": 0,
        "skipped": 0,
        "todo": 0,
    }
    full = {**focused, "tests": 109, "pass": 109}
    gateway = ROOT / completion.frozen_completion.GATEWAY_REL
    test_files = [
        path.relative_to(gateway).as_posix()
        for path in sorted((gateway / "dist/test").glob("*.test.js"))
    ]
    return {
        "claim_scope": "REPOSITORY_INTERNAL_FRESH_VERIFICATION_ONLY",
        "execution_window": {
            "started_at": "2026-08-26T20:00:03+09:00",
            "ended_at": "2026-08-26T20:00:05+09:00",
        },
        "toolchain": {
            "node_path": str(completion.frozen_completion.LOCKED_NODE),
            "node_version": completion.frozen_completion.LOCKED_NODE_VERSION,
            "npm_path": str(completion.frozen_completion.LOCKED_NPM),
            "npm_version": completion.frozen_completion.LOCKED_NPM_VERSION,
        },
        "checks": [
            {
                "check_id": "TYPECHECK",
                "argv": [
                    str(completion.frozen_completion.LOCKED_NPM),
                    "run",
                    "typecheck",
                    "--silent",
                ],
                "status": "PASS",
            },
            {
                "check_id": "BUILD",
                "argv": [
                    str(completion.frozen_completion.LOCKED_NPM),
                    "run",
                    "build",
                    "--silent",
                ],
                "status": "PASS",
            },
            {
                "check_id": "FOCUSED_STATE_ENCRYPTION_MAINTENANCE",
                "argv": [
                    str(completion.frozen_completion.LOCKED_NODE),
                    "--test",
                    completion.frozen_completion.FOCUSED_TEST_REL.as_posix(),
                ],
                "status": "PASS",
                "tap": focused,
            },
            {
                "check_id": "ANDROID_GATEWAY_FULL",
                "argv": [
                    str(completion.frozen_completion.LOCKED_NODE),
                    "--test",
                    *test_files,
                ],
                "status": "PASS",
                "tap": full,
            },
        ],
    }


def _evidence(source: dict[str, object]) -> completion.CompletionEvidence:
    verification = _verification()
    receipt = completion.build_completion_receipt(ROOT, source, verification)
    raw = completion.json_bytes(receipt)
    return completion.CompletionEvidence(
        verification=verification,
        receipt=receipt,
        receipt_bytes=raw,
        receipt_binding=completion._receipt_binding(raw),
        review_binding={
            "assignment": {
                "path": completion.REVIEW_ASSIGNMENT_REL.as_posix(),
                "sha256": "5" * 64,
                "byte_length": 1,
            },
            "review_result": {
                "path": completion.REVIEW_RESULT_REL.as_posix(),
                "sha256": "6" * 64,
                "byte_length": 1,
            },
            "independent_review": {
                "path": completion.INDEPENDENT_REVIEW_REL.as_posix(),
                "sha256": "7" * 64,
                "byte_length": 1,
            },
        },
        latest_authority_at="2026-08-26T20:00:08+09:00",
    )


def _runtime_deriver(
    _root: Path,
    checkpoint: dict[str, object],
    frontier: list[str],
) -> tuple[dict[str, object], dict[str, object]]:
    state = checkpoint["goal_execution"]
    queue = copy.deepcopy(state["artifact_work_queue"])
    boundary = copy.deepcopy(state["completion_boundary"])
    boundary["internal_runnable_goal_ids"] = list(frontier)
    boundary["internal_pending_goal_ids"] = [
        goal
        for goal in boundary["internal_pending_goal_ids"]
        if state["status_by_goal"].get(goal) != "COMPLETE_AT_TARGET"
    ]
    return queue, boundary


def _projection() -> tuple[
    dict[str, object],
    completion.CompletionEvidence,
    dict[str, object],
]:
    source = _source()
    evidence = _evidence(source)
    projected, _update, _completed = completion.project_seq99_100(
        ROOT,
        source,
        evidence,
        source_checkpoint_bytes=completion.checkpoint_bytes(source),
        managed_paths=[
            "synthetic/input.json",
            completion.SCRIPT_REL.as_posix(),
            completion.TEST_REL.as_posix(),
        ],
        snapshot_hashes=("8" * 64, "9" * 64),
        runtime_deriver=_runtime_deriver,
    )
    return source, evidence, projected


def _prepared_for_write(tmp_path: Path) -> completion.PreparedProjection:
    source, evidence, projected = _projection()
    checkpoint = tmp_path / completion.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(completion.checkpoint_bytes(source))
    checkpoint.chmod(0o600)
    source_read = completion.started.frozen_start.seq90._stable_read(
        tmp_path, completion.CHECKPOINT_REL
    )
    return completion.PreparedProjection(
        root=tmp_path,
        source_bytes=source_read.raw,
        source=source,
        projected=projected,
        projected_bytes=completion.checkpoint_bytes(projected),
        evidence_event=projected["goal_execution"]["transition_history"][-2],
        completion_event=projected["goal_execution"]["transition_history"][-1],
        evidence=evidence,
        source_identity=source_read.identity,
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=b"",
        git_head="0" * 40,
        git_branch="test",
    )


def _validate(
    source: dict[str, object],
    projected: dict[str, object],
    evidence: completion.CompletionEvidence,
) -> None:
    completion.validate_projection(
        ROOT,
        source,
        projected,
        evidence,
        runtime_deriver=_runtime_deriver,
    )


def _reseal(projected: dict[str, object]) -> None:
    state = projected["goal_execution"]
    update, completed = state["transition_history"][-2:]
    update["event_sha256"] = completion.continuation.event_sha256(update)
    completed["previous_event_sha256"] = update["event_sha256"]
    completed["canonical_update_event_sha256"] = update["event_sha256"]
    completed["event_sha256"] = completion.continuation.event_sha256(completed)
    state["transition_history_anchor_sha256"] = completed["event_sha256"]


def test_seq99_evidence_and_seq100_completion_preserve_zero_credit() -> None:
    source, evidence, projected = _projection()
    _validate(source, projected, evidence)
    state = projected["goal_execution"]
    update, completed = state["transition_history"][-2:]
    assert update["sequence"] == 99
    assert update["from_status"] == update["to_status"] == "IN_PROGRESS"
    assert update["status_changes"] == {}
    assert completed["sequence"] == 100
    assert completed["to_status"] == "COMPLETE_AT_TARGET"
    assert state["status_by_goal"][completion.GOAL_ID] == "COMPLETE_AT_TARGET"
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert projected["approved_state"]["release_status"] == "NOT_ELIGIBLE"


def test_completion_receipt_keeps_exact_product_and_release_boundary() -> None:
    source = _source()
    receipt = completion.build_completion_receipt(ROOT, source, _verification())
    assert receipt["implementation_evidence"]["state_kind_count"] == 7
    assert receipt["implementation_evidence"]["files"] == (
        completion.frozen_completion._verify_product_pins(ROOT)
    )
    assert receipt["completion_boundary"] == completion.ZERO_CREDIT_BOUNDARY
    assert receipt["gap_reassessment"]["remaining_gate_count"] == 5
    assert receipt["gap_reassessment"]["release_status"] == "NOT_ELIGIBLE"


def test_review_authority_uses_fixed_distinct_existing_tasks() -> None:
    source = _source()
    evidence = _evidence(source)
    assignment = completion.expected_review_assignment(
        ROOT,
        completion.checkpoint_bytes(source),
        source,
        evidence.receipt_bytes,
    )
    assignment_raw = completion.json_bytes(assignment)
    assignment_binding = completion._binding(
        completion.REVIEW_ASSIGNMENT_REL, assignment_raw
    )
    result = {
        "schema_version": "1.0",
        "document_id": completion.REVIEW_RESULT_DOCUMENT_ID,
        "goal_id": completion.GOAL_ID,
        "round_id": "R001",
        "assignment_binding": assignment_binding,
        "reviewer": copy.deepcopy(completion.PRIMARY_REVIEWER),
        "reviewed_at": "2026-08-26T20:00:07+09:00",
        "decision": "APPROVE_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY",
        "findings": [],
        "external_independence_claimed": False,
        "claim_boundary": copy.deepcopy(completion.ZERO_CREDIT_BOUNDARY),
    }
    result_raw = completion.json_bytes(result)
    independent = {
        "schema_version": "1.0",
        "document_id": completion.INDEPENDENT_REVIEW_DOCUMENT_ID,
        "goal_id": completion.GOAL_ID,
        "round_id": "R001",
        "assignment_binding": assignment_binding,
        "review_result_binding": completion._binding(
            completion.REVIEW_RESULT_REL, result_raw
        ),
        "reviewer": copy.deepcopy(completion.INDEPENDENT_REVIEWER),
        "reviewed_at": "2026-08-26T20:00:08+09:00",
        "decision": "CONCUR_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY",
        "findings": [],
        "external_independence_claimed": False,
        "claim_boundary": copy.deepcopy(completion.ZERO_CREDIT_BOUNDARY),
    }
    authority = completion.validate_review_authority(
        assignment,
        assignment_raw,
        result,
        result_raw,
        independent,
        completion.json_bytes(independent),
        verification_ended_at=datetime.fromisoformat(
            "2026-08-26T20:00:05+09:00"
        ),
    )
    assert authority.latest_review_at == "2026-08-26T20:00:08+09:00"
    assert completion.PRIMARY_REVIEWER["task_id"] != completion.PRODUCER_TASK_ID
    assert completion.INDEPENDENT_REVIEWER["task_id"] != completion.PRODUCER_TASK_ID
    assert completion.PRIMARY_REVIEWER["task_id"] != completion.INDEPENDENT_REVIEWER["task_id"]

    forged = copy.deepcopy(independent)
    forged["reviewer"] = copy.deepcopy(completion.PRIMARY_REVIEWER)
    with pytest.raises(completion.CompletionApplyError, match="independent review authority"):
        completion.validate_review_authority(
            assignment,
            assignment_raw,
            result,
            result_raw,
            forged,
            completion.json_bytes(forged),
            verification_ended_at=datetime.fromisoformat(
                "2026-08-26T20:00:05+09:00"
            ),
        )


def test_projection_rejects_release_and_unrelated_state_mutation() -> None:
    source, evidence, projected = _projection()
    projected["approved_state"]["release_status"] = "ELIGIBLE"
    with pytest.raises(completion.CompletionApplyError, match="seq99/100 projection"):
        _validate(source, projected, evidence)

    source, evidence, projected = _projection()
    projected["goal_execution"]["status_by_goal"][completion.EPIC04_GOAL_ID] = "IN_PROGRESS"
    with pytest.raises(completion.CompletionApplyError, match="seq99/100 projection"):
        _validate(source, projected, evidence)


def test_source_sequence_and_review_binding_types_are_strict() -> None:
    source = _source()
    source["goal_execution"]["transition_history"][-1]["sequence"] = 98.0
    with pytest.raises(completion.CompletionApplyError, match="IN_PROGRESS authority"):
        completion._require_source_shape(source)

    source = _source()
    evidence = _evidence(source)
    forged = copy.deepcopy(dict(evidence.review_binding))
    forged["assignment"]["byte_length"] = True
    forged_evidence = completion.CompletionEvidence(
        verification=evidence.verification,
        receipt=evidence.receipt,
        receipt_bytes=evidence.receipt_bytes,
        receipt_binding=evidence.receipt_binding,
        review_binding=forged,
        latest_authority_at=evidence.latest_authority_at,
    )
    with pytest.raises(completion.CompletionApplyError, match="review binding differs"):
        completion.project_seq99_100(
            ROOT,
            source,
            forged_evidence,
            source_checkpoint_bytes=completion.checkpoint_bytes(source),
            managed_paths=[
                completion.SCRIPT_REL.as_posix(),
                completion.TEST_REL.as_posix(),
            ],
            snapshot_hashes=("8" * 64, "9" * 64),
            runtime_deriver=_runtime_deriver,
        )


def test_public_inverse_is_byte_exact_and_rejects_seq99() -> None:
    source, _evidence_value, projected = _projection()
    source_raw = completion.checkpoint_bytes(source)
    assert completion.reconstructed_seq98_checkpoint_bytes(ROOT, projected) == source_raw
    assert completion.reconstructed_seq98_checkpoint_bytes(ROOT, source) == source_raw

    seq99 = copy.deepcopy(projected)
    seq99["goal_execution"]["transition_history"].pop()
    with pytest.raises(
        completion.CompletionApplyError,
        match="seq99 producer transaction lacks adjacent seq100",
    ):
        completion.reconstructed_seq98_checkpoint_bytes(ROOT, seq99)


def test_public_inverse_rejects_missing_preimage_and_bool_size() -> None:
    _source_value, _evidence_value, projected = _projection()
    update = projected["goal_execution"]["transition_history"][-2]
    del update["changed_path_preimage"]["current_work"]
    _reseal(projected)
    with pytest.raises(completion.CompletionApplyError, match="changed path preimage"):
        completion.reconstructed_seq98_checkpoint_bytes(ROOT, projected)

    _source_value, _evidence_value, projected = _projection()
    update = projected["goal_execution"]["transition_history"][-2]
    update["source_checkpoint_binding"]["byte_length"] = False
    _reseal(projected)
    with pytest.raises(completion.CompletionApplyError, match="source checkpoint binding"):
        completion.reconstructed_seq98_checkpoint_bytes(ROOT, projected)


def test_preflight_is_fail_closed_while_authority_pins_move() -> None:
    completion.validate_starter_authority_handshake(ROOT)
    forged = dict(completion.STARTER_AUTHORITY_PINS)
    digest, _size = forged[completion.started.SCRIPT_REL]
    forged[completion.started.SCRIPT_REL] = (digest, True)
    with pytest.raises(completion.CompletionApplyError, match="starter authority pin"):
        completion.validate_starter_authority_handshake(ROOT, forged)

    with pytest.raises(completion.started.StartApplyError, match="exact seq98"):
        completion.prepare_projection(ROOT)


def test_verification_rejects_missing_argv_and_extra_authority() -> None:
    verification = _verification()
    del verification["checks"][0]["argv"]
    with pytest.raises(completion.CompletionApplyError, match="commands or counts"):
        completion._require_verification_summary(verification)

    verification = _verification()
    verification["checks"][1]["release_approved"] = True
    with pytest.raises(completion.CompletionApplyError, match="commands or counts"):
        completion._require_verification_summary(verification)


def test_atomic_completion_writer_runs_guard_and_publishes_pair(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    calls: list[str] = []
    monkeypatch.setattr(
        completion,
        "_require_prepared_exact",
        lambda *_a, **_k: calls.append("guard"),
    )
    monkeypatch.setattr(completion, "_terminal_validate", lambda *_a: calls.append("terminal"))

    def writer(transport: object, *, commit_guard: object) -> None:
        commit_guard()
        (prepared.root / completion.CHECKPOINT_REL).write_bytes(
            transport.projected_raw
        )

    completion.write_projection(prepared, writer=writer)
    assert calls == ["guard", "guard", "terminal"]
    published = completion.strict_json(
        (prepared.root / completion.CHECKPOINT_REL).read_bytes(), "published"
    )
    assert len(published["goal_execution"]["transition_history"]) == 100


def test_completion_rejects_stale_same_bytes_new_inode(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = prepared.root / completion.CHECKPOINT_REL
    replacement = checkpoint.with_name("replacement.json")
    replacement.write_bytes(prepared.source_bytes)
    replacement.chmod(0o600)
    os.replace(replacement, checkpoint)
    monkeypatch.setattr(completion, "_require_prepared_exact", lambda *_a, **_k: None)
    with pytest.raises(completion.CompletionApplyError, match="seq98 source changed"):
        completion.write_projection(prepared, writer=lambda *_a, **_k: None)


def test_completion_postreplace_failure_is_uncertain(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    monkeypatch.setattr(completion, "_require_prepared_exact", lambda *_a, **_k: None)
    monkeypatch.setattr(
        completion,
        "_terminal_validate",
        lambda *_a: (_ for _ in ()).throw(completion.CompletionApplyError("terminal")),
    )

    def writer(transport: object, *, commit_guard: object) -> None:
        commit_guard()
        (prepared.root / completion.CHECKPOINT_REL).write_bytes(
            transport.projected_raw
        )

    with pytest.raises(completion.started.frozen_start.seq90.PostcommitUncertain):
        completion.write_projection(prepared, writer=writer)


def test_cli_has_review_preflight_and_write_modes() -> None:
    assert completion.parse_args(["--preflight"]).preflight is True
    assert completion.parse_args(["--prepare-review"]).prepare_review is True
    assert completion.parse_args(["--write"]).write is True
    with pytest.raises(SystemExit):
        completion.parse_args([])
    with pytest.raises(SystemExit):
        completion.parse_args(["--preflight", "--write"])
