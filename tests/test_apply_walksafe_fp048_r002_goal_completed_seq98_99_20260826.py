from __future__ import annotations

import copy
from pathlib import Path
import shutil

import pytest

from scripts import (
    apply_walksafe_fp048_r002_goal_completed_seq98_99_20260826 as completion,
)


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
    runtime = {
        "focus_goal_id": completion.GOAL_ID,
        "focus_goal_path": completion.GOAL_PATH,
        "focus_work_item_id": completion.WORK_ITEM_ID,
        "focus_source": "IMPLEMENTATION_BACKLOG",
        "ready_frontier_goal_ids": [
            completion.GOAL_ID,
            completion.PARENT_GOAL_ID,
            completion.EPIC04_GOAL_ID,
            completion.EPIC12_GOAL_ID,
        ],
        "blocked_goal_ids": [],
        "pending_questions": [],
        "open_question_count": 0,
        "artifact_work_queue_sha256": "1" * 64,
        "completion_boundary_sha256": "2" * 64,
        "activation_status": "ACTIVE",
        "package_status": "ACTIVE",
    }
    started: dict[str, object] = {
        "sequence": completion.SOURCE_SEQUENCE,
        "event_id": completion.SOURCE_EVENT_ID,
        "event_type": "GOAL_STARTED",
        "occurred_on": "2026-08-26",
        "occurred_at": "2026-08-26T20:00:00+09:00",
        "previous_focus_goal_id": completion.GOAL_ID,
        "previous_focus_content_sha256": completion.GOAL_SHA256,
        "focus_goal_id": completion.GOAL_ID,
        "focus_goal_content_sha256": completion.GOAL_SHA256,
        "subject_goal_id": completion.GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "status_changes": {completion.GOAL_ID: "IN_PROGRESS"},
        "runtime_after": runtime,
        "blockers_after": {},
        "blocker_resolution_ids_after": [],
        "previous_event_sha256": previous,
    }
    started["event_sha256"] = completion.continuation.event_sha256(started)
    history.append(started)
    boundary = {
        "schema_version": "1.0",
        "repository_scope_status": "IN_PROGRESS",
        "project_status": "NOT_COMPLETE",
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
                "file_sha256": "3" * 64,
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
            "transition_history_anchor_sha256": started["event_sha256"],
            "validation_cutoff_at": started["occurred_at"],
            "goal_status": "IN_PROGRESS",
            "status_by_goal": {
                completion.GOAL_ID: "IN_PROGRESS",
                completion.PARENT_GOAL_ID: "READY",
                completion.EPIC04_GOAL_ID: "READY",
                completion.EPIC12_GOAL_ID: "READY",
            },
            "focus_goal_id": completion.GOAL_ID,
            "focus_goal_path": completion.GOAL_PATH,
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
            "scope": "synthetic seq97",
            "managed_changed_paths": ["synthetic/input.json"],
            "managed_changed_path_count": 1,
            "path_set_sha256": "4" * 64,
            "content_set_sha256": "5" * 64,
        },
        "session_handoff": {
            "changed_files": ["synthetic/input.json"],
            "current_epic": "EPIC-03 FP048 R002 IN_PROGRESS",
            "last_updated_by_work_item": completion.WORK_ITEM_ID,
            "last_verification_status": "IN_PROGRESS",
            "next_single_action": "finish product",
            "source_commit_or_snapshot": {
                "file_count": 1,
                "path_set_sha256": "4" * 64,
                "content_set_sha256": "5" * 64,
            },
        },
    }


def _verification() -> dict[str, object]:
    zero = {
        "tests": 13,
        "pass": 13,
        "fail": 0,
        "cancelled": 0,
        "skipped": 0,
        "todo": 0,
    }
    full = {**zero, "tests": 109, "pass": 109}
    return {
        "claim_scope": "REPOSITORY_INTERNAL_FRESH_VERIFICATION_ONLY",
        "execution_window": {
            "started_at": "2026-08-26T20:00:01+09:00",
            "ended_at": "2026-08-26T20:00:05+09:00",
        },
        "toolchain": {
            "node_path": str(completion.LOCKED_NODE),
            "node_version": completion.LOCKED_NODE_VERSION,
            "npm_path": str(completion.LOCKED_NPM),
            "npm_version": completion.LOCKED_NPM_VERSION,
        },
        "checks": [
            {"check_id": "TYPECHECK", "argv": ["npm", "typecheck"], "status": "PASS"},
            {"check_id": "BUILD", "argv": ["npm", "build"], "status": "PASS"},
            {
                "check_id": "FOCUSED_STATE_ENCRYPTION_MAINTENANCE",
                "argv": ["node", "focused"],
                "status": "PASS",
                "tap": zero,
            },
            {
                "check_id": "ANDROID_GATEWAY_FULL",
                "argv": ["node", "full"],
                "status": "PASS",
                "tap": full,
            },
        ],
    }


def _evidence(
    source: dict[str, object],
    verification: dict[str, object] | None = None,
) -> completion.CompletionEvidence:
    verification = _verification() if verification is None else verification
    receipt = completion.build_completion_receipt(ROOT, source, verification)
    raw = completion.json_bytes(receipt)
    return completion.CompletionEvidence(
        verification=verification,
        receipt=receipt,
        receipt_bytes=raw,
        receipt_binding=completion._receipt_binding(raw),
        review_binding={
            "assignment": {"path": "review/assignment", "sha256": "6" * 64, "byte_length": 1},
            "review_result": {"path": "review/result", "sha256": "7" * 64, "byte_length": 1},
            "independent_review": {"path": "review/independent", "sha256": "8" * 64, "byte_length": 1},
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
        goal_id
        for goal_id in boundary["internal_pending_goal_ids"]
        if state["status_by_goal"].get(goal_id) != "COMPLETE_AT_TARGET"
    ]
    return queue, boundary


def _projection() -> tuple[
    dict[str, object],
    completion.CompletionEvidence,
    dict[str, object],
    dict[str, object],
    dict[str, object],
]:
    source = _source()
    evidence = _evidence(source)
    projected, update, completed = completion.project_seq98_99(
        ROOT,
        source,
        evidence,
        source_checkpoint_bytes=completion.checkpoint_bytes(source),
        managed_paths=[
            "synthetic/input.json",
            completion.PRODUCT_SOURCE_REL.as_posix(),
            completion.PRODUCT_TEST_REL.as_posix(),
        ],
        snapshot_hashes=("9" * 64, "a" * 64),
        runtime_deriver=_runtime_deriver,
    )
    return source, evidence, projected, update, completed


def _validate_projection(
    source: dict[str, object],
    projected: dict[str, object],
    evidence: completion.CompletionEvidence,
) -> None:
    completion.validate_projection(
        source,
        projected,
        evidence,
        root=ROOT,
        runtime_deriver=_runtime_deriver,
    )


def _reseal_projection_suffix(projected: dict[str, object]) -> None:
    state = projected["goal_execution"]
    update, completed = state["transition_history"][-2:]
    update["event_sha256"] = completion.continuation.event_sha256(update)
    completed["previous_event_sha256"] = update["event_sha256"]
    completed["canonical_update_event_sha256"] = update["event_sha256"]
    completed["event_sha256"] = completion.continuation.event_sha256(completed)
    state["transition_history_anchor_sha256"] = completed["event_sha256"]


def _copy_review_inputs(root: Path) -> None:
    for relative in (
        completion.SCRIPT_REL,
        completion.TEST_REL,
        completion.PRODUCT_SOURCE_REL,
        completion.PRODUCT_TEST_REL,
        *completion.CONSUMER_PATHS,
    ):
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / relative, target)


def test_live_product_files_match_the_frozen_completion_pins() -> None:
    assert completion._verify_product_pins(ROOT) == [
        {
            "path": path.as_posix(),
            "sha256": pin[0],
            "byte_length": pin[1],
        }
        for path, pin in completion.PRODUCT_PINS.items()
    ]


def test_seq98_freezes_evidence_and_seq99_completes_only_fp048_r002() -> None:
    source, evidence, projected, update, completed = _projection()
    _validate_projection(source, projected, evidence)
    state = projected["goal_execution"]
    assert update["sequence"] == 98
    assert update["event_type"] == "CANONICAL_BINDINGS_UPDATED"
    assert update["from_status"] == update["to_status"] == "IN_PROGRESS"
    assert update["status_changes"] == {}
    assert update["changed_binding_roles"] == [completion.COMPLETION_ROLE]
    assert update["occurred_at"] == "2026-08-26T20:00:09+09:00"
    assert completed["sequence"] == 99
    assert completed["event_type"] == "GOAL_COMPLETED"
    assert completed["previous_event_sha256"] == update["event_sha256"]
    assert completed["occurred_at"] == "2026-08-26T20:00:10+09:00"
    assert state["status_by_goal"][completion.GOAL_ID] == "COMPLETE_AT_TARGET"
    assert state["status_by_goal"][completion.PARENT_GOAL_ID] == "READY"
    assert state["ready_frontier_goal_ids"] == [
        completion.PARENT_GOAL_ID,
        completion.EPIC04_GOAL_ID,
        completion.EPIC12_GOAL_ID,
    ]
    assert source["goal_execution"]["status_by_goal"][completion.GOAL_ID] == "IN_PROGRESS"


def test_public_inverse_restores_byte_exact_seq97_and_rejects_seq98(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, _evidence_value, projected, update, _completed = _projection()
    source_bytes = completion.checkpoint_bytes(source)
    observed: list[bytes] = []

    def require_synthetic_source(_root: Path, value: dict[str, object]) -> None:
        completion._require_source_shape(value)
        observed.append(completion.checkpoint_bytes(value))

    monkeypatch.setattr(completion, "require_exact_source", require_synthetic_source)
    assert update["source_checkpoint_binding"] == completion._binding(
        completion.CHECKPOINT_REL,
        source_bytes,
    )
    assert update["source_history_preimage"] == {
        "length": completion.SOURCE_SEQUENCE,
        "tail_event_sha256": source["goal_execution"]["transition_history"][-1][
            "event_sha256"
        ],
    }
    assert set(update["changed_path_preimage"]) == set(
        completion.CHANGED_PATH_PREIMAGE_PATHS
    )
    assert "goal_execution.transition_history" not in update["changed_path_preimage"]
    assert (
        completion.reconstructed_seq97_checkpoint_bytes(ROOT, projected)
        == source_bytes
    )
    assert observed == [source_bytes]
    assert completion.reconstructed_seq97_checkpoint_bytes(ROOT, source) == source_bytes

    seq98_only = copy.deepcopy(projected)
    seq98_state = seq98_only["goal_execution"]
    seq98_state["transition_history"].pop()
    with pytest.raises(
        completion.CompletionApplyError,
        match="seq98 producer transaction lacks adjacent seq99 completion",
    ):
        completion.reconstructed_seq97_checkpoint_bytes(ROOT, seq98_only)


def test_public_inverse_rejects_preimage_missing_extra_and_tamper(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        completion,
        "require_exact_source",
        lambda _root, value: completion._require_source_shape(value),
    )
    source, _evidence_value, projected, update, _completed = _projection()
    del update["changed_path_preimage"]["current_work"]
    _reseal_projection_suffix(projected)
    with pytest.raises(
        completion.CompletionApplyError,
        match="changed path preimage field set differs",
    ):
        completion.reconstructed_seq97_checkpoint_bytes(ROOT, projected)

    source, _evidence_value, projected, update, _completed = _projection()
    update["changed_path_preimage"]["forged.extra"] = None
    _reseal_projection_suffix(projected)
    with pytest.raises(
        completion.CompletionApplyError,
        match="changed path preimage field set differs",
    ):
        completion.reconstructed_seq97_checkpoint_bytes(ROOT, projected)

    source, _evidence_value, projected, update, _completed = _projection()
    update["changed_path_preimage"]["current_work"]["title"] = "forged"
    _reseal_projection_suffix(projected)
    with pytest.raises(
        completion.CompletionApplyError,
        match="reconstructed seq97 checkpoint CAS differs",
    ):
        completion.reconstructed_seq97_checkpoint_bytes(ROOT, projected)


def test_public_inverse_rejects_bool_int_and_history_type_confusion(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        completion,
        "require_exact_source",
        lambda _root, value: completion._require_source_shape(value),
    )
    _source_value, _evidence_value, projected, update, _completed = _projection()
    update["source_checkpoint_binding"]["byte_length"] = False
    _reseal_projection_suffix(projected)
    with pytest.raises(
        completion.CompletionApplyError,
        match="source checkpoint binding differs",
    ):
        completion.reconstructed_seq97_checkpoint_bytes(ROOT, projected)

    _source_value, _evidence_value, projected, update, _completed = _projection()
    update["source_history_preimage"]["length"] = float(completion.SOURCE_SEQUENCE)
    _reseal_projection_suffix(projected)
    with pytest.raises(
        completion.CompletionApplyError,
        match="source history preimage differs",
    ):
        completion.reconstructed_seq97_checkpoint_bytes(ROOT, projected)


def test_projection_rejects_resealed_source_preimage_tamper() -> None:
    source, evidence, projected, update, _completed = _projection()
    update["changed_path_preimage"]["current_work"]["title"] = "forged"
    _reseal_projection_suffix(projected)
    with pytest.raises(
        completion.CompletionApplyError,
        match="seq98 is not a zero-status evidence freeze",
    ):
        _validate_projection(source, projected, evidence)


def test_projection_preserves_all_release_authority_as_zero_credit() -> None:
    source, evidence, projected, _update, _completed = _projection()
    _validate_projection(source, projected, evidence)
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert evidence.receipt["completion_boundary"] == completion.ZERO_CREDIT_BOUNDARY
    assert projected["approved_state"]["formal_test_not_run_count"] == 279
    assert projected["approved_state"]["remaining_gate_count"] == 5
    assert projected["approved_state"]["release_status"] == "NOT_ELIGIBLE"


def test_receipt_freezes_exact_two_files_and_exact_test_counts() -> None:
    source = _source()
    receipt = completion.build_completion_receipt(ROOT, source, _verification())
    assert receipt["implementation_evidence"] == {
        "feature": "SEVEN_STATE_ENCRYPTION_ROTATION_ALL_OR_NOTHING",
        "state_kind_count": 7,
        "preflight_before_mutation": True,
        "files": completion._verify_product_pins(ROOT),
    }
    checks = {
        row["check_id"]: row
        for row in receipt["verification_evidence"]["checks"]
    }
    assert checks["FOCUSED_STATE_ENCRYPTION_MAINTENANCE"]["tap"]["pass"] == 13
    assert checks["ANDROID_GATEWAY_FULL"]["tap"]["pass"] == 109


def test_product_pin_drift_fails_before_receipt_creation(tmp_path: Path) -> None:
    for relative, (_digest, size) in completion.PRODUCT_PINS.items():
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"x" * size)
    with pytest.raises(completion.CompletionApplyError, match="product pin differs"):
        completion.build_completion_receipt(tmp_path, _source(), _verification())


def test_tap_parser_requires_one_exact_summary() -> None:
    output = b"""TAP version 13
# tests 13
# pass 13
# fail 0
# cancelled 0
# skipped 0
# todo 0
"""
    assert completion._tap_counts(output, label="focused") == {
        "tests": 13,
        "pass": 13,
        "fail": 0,
        "cancelled": 0,
        "skipped": 0,
        "todo": 0,
    }
    with pytest.raises(completion.CompletionApplyError, match="TAP tests count differs"):
        completion._tap_counts(output + b"# tests 13\n", label="focused")


def test_review_authority_is_an_exact_two_reviewer_binding_chain(tmp_path: Path) -> None:
    _copy_review_inputs(tmp_path)
    source = _source()
    source_raw = completion.checkpoint_bytes(source)
    receipt = completion.build_completion_receipt(tmp_path, source, _verification())
    receipt_raw = completion.json_bytes(receipt)
    assignment = completion.expected_review_assignment(
        tmp_path, source_raw, source, receipt_raw
    )
    assignment_raw = completion.json_bytes(assignment)
    assignment_binding = completion._binding(
        completion.REVIEW_ASSIGNMENT_REL, assignment_raw
    )
    result = {
        "schema_version": "1.0",
        "document_id": "WS-FP048-R002-SEQ98-99-REVIEW-RESULT-20260826-R001",
        "goal_id": completion.GOAL_ID,
        "round_id": "R001",
        "assignment_binding": assignment_binding,
        "reviewer": {"id": "reviewer-a", "task_id": "/root/reviewer-a"},
        "reviewed_at": "2026-08-26T20:00:07+09:00",
        "decision": "APPROVE_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY",
        "findings": [],
        "external_independence_claimed": False,
        "claim_boundary": copy.deepcopy(completion.ZERO_CREDIT_BOUNDARY),
    }
    result_raw = completion.json_bytes(result)
    independent = {
        "schema_version": "1.0",
        "document_id": "WS-FP048-R002-SEQ98-99-INDEPENDENT-REVIEW-20260826-R001",
        "goal_id": completion.GOAL_ID,
        "round_id": "R001",
        "assignment_binding": assignment_binding,
        "review_result_binding": completion._binding(
            completion.REVIEW_RESULT_REL, result_raw
        ),
        "reviewer": {"id": "reviewer-b", "task_id": "/root/reviewer-b"},
        "reviewed_at": "2026-08-26T20:00:08+09:00",
        "decision": "CONCUR_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY",
        "findings": [],
        "external_independence_claimed": False,
        "claim_boundary": copy.deepcopy(completion.ZERO_CREDIT_BOUNDARY),
    }
    for relative, raw in (
        (completion.REVIEW_ASSIGNMENT_REL, assignment_raw),
        (completion.REVIEW_RESULT_REL, result_raw),
        (completion.INDEPENDENT_REVIEW_REL, completion.json_bytes(independent)),
    ):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
        target.chmod(0o600)
    authority = completion._load_review_authority(
        tmp_path,
        assignment,
        not_before=completion.datetime.fromisoformat(
            "2026-08-26T20:00:05+09:00"
        ),
    )
    assert set(authority.binding) == {
        "assignment",
        "review_result",
        "independent_review",
    }
    assert authority.latest_review_at == "2026-08-26T20:00:08+09:00"

    forged_result_extra = copy.deepcopy(result)
    forged_result_extra["release_approved"] = True
    (tmp_path / completion.REVIEW_RESULT_REL).write_bytes(
        completion.json_bytes(forged_result_extra)
    )
    with pytest.raises(completion.CompletionApplyError, match="review result authority"):
        completion._load_review_authority(
            tmp_path,
            assignment,
            not_before=completion.datetime.fromisoformat(
                "2026-08-26T20:00:05+09:00"
            ),
        )
    (tmp_path / completion.REVIEW_RESULT_REL).write_bytes(result_raw)

    forged_independent_extra = copy.deepcopy(independent)
    forged_independent_extra["release_approved"] = True
    (tmp_path / completion.INDEPENDENT_REVIEW_REL).write_bytes(
        completion.json_bytes(forged_independent_extra)
    )
    with pytest.raises(
        completion.CompletionApplyError,
        match="independent review authority",
    ):
        completion._load_review_authority(
            tmp_path,
            assignment,
            not_before=completion.datetime.fromisoformat(
                "2026-08-26T20:00:05+09:00"
            ),
        )
    (tmp_path / completion.INDEPENDENT_REVIEW_REL).write_bytes(
        completion.json_bytes(independent)
    )

    forged = copy.deepcopy(independent)
    forged["reviewer"] = {
        "id": result["reviewer"]["id"],
        "task_id": "/root/reviewer-b-distinct-task",
    }
    (tmp_path / completion.INDEPENDENT_REVIEW_REL).write_bytes(
        completion.json_bytes(forged)
    )
    with pytest.raises(completion.CompletionApplyError, match="independent review authority"):
        completion._load_review_authority(
            tmp_path,
            assignment,
            not_before=completion.datetime.fromisoformat(
                "2026-08-26T20:00:05+09:00"
            ),
        )

    forged_result = copy.deepcopy(result)
    forged_result["claim_boundary"]["formal_test_credit_delta"] = False
    (tmp_path / completion.REVIEW_RESULT_REL).write_bytes(
        completion.json_bytes(forged_result)
    )
    with pytest.raises(completion.CompletionApplyError, match="review result authority"):
        completion._load_review_authority(
            tmp_path,
            assignment,
            not_before=completion.datetime.fromisoformat(
                "2026-08-26T20:00:05+09:00"
            ),
        )


def test_projection_rejects_a_resealed_release_credit_change() -> None:
    source, evidence, projected, _update, _completed = _projection()
    projected["approved_state"]["release_status"] = "ELIGIBLE"
    with pytest.raises(completion.CompletionApplyError, match="formal/release authority changed"):
        _validate_projection(source, projected, evidence)


def test_projection_rejects_nested_bool_and_float_type_confusion() -> None:
    source, evidence, projected, _update, _completed = _projection()
    projected["verification_boundary"]["implementation_conformance_claimed"] = 0
    projected["working_tree_snapshot"]["managed_changed_path_count"] = float(
        projected["working_tree_snapshot"]["managed_changed_path_count"]
    )
    with pytest.raises(completion.CompletionApplyError, match="formal/release authority"):
        _validate_projection(source, projected, evidence)

    source, evidence, projected, _update, _completed = _projection()
    projected["working_tree_snapshot"]["managed_changed_path_count"] = float(
        projected["working_tree_snapshot"]["managed_changed_path_count"]
    )
    with pytest.raises(completion.CompletionApplyError, match="managed snapshot closure"):
        _validate_projection(source, projected, evidence)


def test_projection_rejects_canonical_binding_authority_forgery() -> None:
    source, evidence, projected, _update, _completed = _projection()
    binding = next(
        row
        for row in projected["canonical_bindings"]
        if row["role"] == completion.COMPLETION_ROLE
    )
    binding["mutable"] = True
    binding["identity_json_path"] = "forged"
    with pytest.raises(
        completion.CompletionApplyError,
        match="completion canonical evidence",
    ):
        _validate_projection(source, projected, evidence)

    source, evidence, projected, _update, _completed = _projection()
    binding = next(
        row
        for row in projected["canonical_bindings"]
        if row["role"] == completion.COMPLETION_ROLE
    )
    projected["canonical_bindings"].append(copy.deepcopy(binding))
    with pytest.raises(
        completion.CompletionApplyError,
        match="completion canonical evidence",
    ):
        _validate_projection(source, projected, evidence)


def test_projection_rejects_resealed_seq98_and_seq99_runtime_forgery() -> None:
    source, evidence, projected, update, completed = _projection()
    update["runtime_after"]["open_question_count"] = 1
    update["event_sha256"] = completion.continuation.event_sha256(update)
    completed["previous_event_sha256"] = update["event_sha256"]
    completed["canonical_update_event_sha256"] = update["event_sha256"]
    completed["event_sha256"] = completion.continuation.event_sha256(completed)
    projected["goal_execution"]["transition_history_anchor_sha256"] = completed[
        "event_sha256"
    ]
    with pytest.raises(
        completion.CompletionApplyError,
        match="seq98 intermediate runtime seal differs",
    ):
        _validate_projection(source, projected, evidence)

    source, evidence, projected, _update, completed = _projection()
    state = projected["goal_execution"]
    state["artifact_work_queue"]["forged"] = True
    completed["runtime_after"]["artifact_work_queue_sha256"] = (
        completion.continuation.canonical_json_sha256(
            state["artifact_work_queue"]
        )
    )
    completed["event_sha256"] = completion.continuation.event_sha256(completed)
    state["transition_history_anchor_sha256"] = completed["event_sha256"]
    with pytest.raises(
        completion.CompletionApplyError,
        match="seq99 runtime seal differs",
    ):
        _validate_projection(source, projected, evidence)


def test_writer_rejects_forged_raw_even_when_projected_object_is_valid() -> None:
    source, evidence, projected, update, completed = _projection()
    prepared = completion.PreparedProjection(
        root=ROOT,
        source_bytes=completion.checkpoint_bytes(source),
        source=source,
        projected=projected,
        projected_bytes=completion.checkpoint_bytes(projected) + b" ",
        evidence=evidence,
        evidence_event=update,
        completion_event=completed,
    )
    called = False

    def writer(*_args, **_kwargs) -> None:
        nonlocal called
        called = True

    with pytest.raises(completion.CompletionApplyError, match="projected bytes differ"):
        completion.write_projection(
            prepared,
            atomic_writer=writer,
            projected_validator=lambda _root, _checkpoint: [],
        )
    assert called is False


def test_writer_cannot_report_success_when_transport_skips_commit_guard() -> None:
    source, evidence, projected, update, completed = _projection()
    prepared = completion.PreparedProjection(
        root=ROOT,
        source_bytes=completion.checkpoint_bytes(source),
        source=source,
        projected=projected,
        projected_bytes=completion.checkpoint_bytes(projected),
        evidence=evidence,
        evidence_event=update,
        completion_event=completed,
    )
    with pytest.raises(
        completion.CompletionApplyError,
        match="checkpoint publication bytes differ",
    ):
        completion.write_projection(
            prepared,
            atomic_writer=lambda *_args, **_kwargs: None,
            projected_validator=lambda _root, _checkpoint: [],
        )


def test_projection_rejects_resealed_seq98_identity_and_failed_full_count() -> None:
    source, evidence, projected, update, _completed = _projection()
    update["sequence"] = 98.0
    update["event_id"] = "FORGED"
    update["event_sha256"] = completion.continuation.event_sha256(update)
    with pytest.raises(completion.CompletionApplyError, match="seq98 identity"):
        _validate_projection(source, projected, evidence)

    source = _source()
    verification = _verification()
    verification["checks"][-1]["tap"]["fail"] = 1
    with pytest.raises(completion.CompletionApplyError, match="verification counts"):
        completion.build_completion_receipt(ROOT, source, verification)


def test_source_must_be_exact_seq97_in_progress() -> None:
    source = _source()
    completion._require_source_shape(source)
    source["goal_execution"]["status_by_goal"][completion.GOAL_ID] = "READY"
    with pytest.raises(completion.CompletionApplyError, match="runtime differs"):
        completion._require_source_shape(source)


def test_snapshot_excludes_checkpoint_and_atomic_transport_temporary(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    temporary = (
        "docs/control/.walksafe-project-continuation-checkpoint.json."
        "fp048-seq43-44.0123456789abcdef01234567.tmp"
    )
    monkeypatch.setattr(
        completion.started.seq90,
        "capture_git_visible_paths",
        lambda _root: (
            b"synthetic",
            (
                completion.CHECKPOINT_REL.as_posix(),
                temporary,
                "synthetic/input.json",
            ),
        ),
    )
    observed: list[str] = []

    def snapshot(_root: Path, paths: list[str]) -> tuple[str, str]:
        observed.extend(paths)
        return "b" * 64, "c" * 64

    monkeypatch.setattr(completion.continuation, "working_snapshot_hashes", snapshot)
    paths, hashes = completion._snapshot_inputs(ROOT, _source())
    assert completion.CHECKPOINT_REL.as_posix() not in paths
    assert temporary not in paths
    assert observed == paths
    assert hashes == ("b" * 64, "c" * 64)


def test_cli_exposes_only_review_preparation_preflight_and_write() -> None:
    assert completion.parse_args(["--prepare-review"]).prepare_review is True
    assert completion.parse_args(["--preflight"]).preflight is True
    assert completion.parse_args(["--write"]).write is True
    with pytest.raises(SystemExit):
        completion.parse_args([])
    with pytest.raises(SystemExit):
        completion.parse_args(["--preflight", "--write"])
