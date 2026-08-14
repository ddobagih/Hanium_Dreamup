from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from scripts import apply_walksafe_fp022_goal_completed_seq70_71_20260814 as apply


ROOT = Path(__file__).resolve().parents[1]


def source() -> dict[str, object]:
    return json.loads((ROOT / apply.CHECKPOINT_REL).read_text(encoding="utf-8"))


def completion_is_published(checkpoint: dict[str, object]) -> bool:
    history = checkpoint["goal_execution"]["transition_history"]
    return (
        len(history) == 71
        and history[-2]["event_id"] == apply.UPDATE_EVENT_ID
        and history[-1]["event_id"] == apply.COMPLETION_EVENT_ID
    )


def runtime_deriver(
    _root: Path,
    checkpoint: dict[str, object],
    ready: list[str],
) -> tuple[dict[str, object], dict[str, object]]:
    state = checkpoint["goal_execution"]
    assert isinstance(state, dict)
    queue = copy.deepcopy(state["artifact_work_queue"])
    boundary = copy.deepcopy(state["completion_boundary"])
    assert isinstance(queue, dict) and isinstance(boundary, dict)
    boundary["internal_runnable_goal_ids"] = list(ready)
    boundary["internal_pending_goal_ids"] = [
        goal for goal in boundary["internal_pending_goal_ids"]
        if goal != apply.GOAL_ID
    ]
    return queue, boundary


def evidence(state: dict[str, object]) -> apply.CompletionEvidence:
    gap = {
        "metadata": {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260814-028",
            "version": "r028",
        },
        "assessments": [
            {"source_policy_id": "FP-022", "gap_id": "GAP-031", "status": "PARTIAL"}
        ],
        "summary": {"status_counts": {"PARTIAL": 1}},
        "implementation_snapshot": {"scope": "REPOSITORY_INTERNAL_ONLY"},
    }
    backlog = {
        "metadata": {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260814-028"
        },
        "epics": [{"current_status": "IN_PROGRESS"}],
        "next_single_action": {
            "epic_id": "EPIC-04",
            "source_policy_id": "FP-023",
            "gap_id": "GAP-032",
            "priority_rank": 25,
            "status": "PLANNED_NEXT",
            "action": "FP-023 next action",
        },
    }
    documents = {
        apply.GAP_JSON_REL: gap,
        apply.BACKLOG_JSON_REL: backlog,
    }
    bindings = {
        "IMPLEMENTATION_GAP": {
            "role": "IMPLEMENTATION_GAP",
            "document_id": gap["metadata"]["report_id"],
            "path": apply.GAP_JSON_REL.as_posix(),
            "file_sha256": "1" * 64,
        },
        "IMPLEMENTATION_BACKLOG": {
            "role": "IMPLEMENTATION_BACKLOG",
            "document_id": backlog["metadata"]["backlog_id"],
            "path": apply.BACKLOG_JSON_REL.as_posix(),
            "file_sha256": "2" * 64,
        },
        apply.COMPLETION_ROLE: {
            "role": apply.COMPLETION_ROLE,
            "document_id": apply.COMPLETION_DOCUMENT_ID,
            "path": apply.COMPLETION_REL.as_posix(),
            "file_sha256": "3" * 64,
        },
    }
    managed = {
        Path(path): "a" * 64
        for path in state["working_tree_snapshot"]["managed_changed_paths"]
    }
    managed[apply.GAP_JSON_REL] = "1" * 64
    managed[apply.BACKLOG_JSON_REL] = "2" * 64
    managed[apply.COMPLETION_REL] = "3" * 64
    for path in apply.CATALOG_PATHS:
        managed[path] = "c" * 64
    return apply.CompletionEvidence(
        documents_by_path=documents,
        bindings_by_role=bindings,
        update_occurred_at="2026-08-14T15:00:00+09:00",
        completion_occurred_at="2026-08-14T15:00:01+09:00",
        physical_sha256_by_path=managed,
        final_managed_sha256_by_path=managed,
        transition_review_binding={
            "assignment": {"path": apply.TRANSITION_REVIEW_PATHS[0].as_posix(), "sha256": "4" * 64, "byte_length": 1},
            "review_result": {"path": apply.TRANSITION_REVIEW_PATHS[1].as_posix(), "sha256": "5" * 64, "byte_length": 1},
            "independent_review": {"path": apply.TRANSITION_REVIEW_PATHS[2].as_posix(), "sha256": "6" * 64, "byte_length": 1},
        },
    )


def test_exact_source_is_byte_pinned_to_seq69() -> None:
    raw = (ROOT / apply.CHECKPOINT_REL).read_bytes()
    value = json.loads(raw)
    if completion_is_published(value):
        with pytest.raises(apply.CompletionApplyError, match="source"):
            apply.require_exact_source(raw, value)
    else:
        apply.require_exact_source(raw, value)
    forged = bytearray(raw)
    forged[-2] ^= 1
    with pytest.raises(apply.CompletionApplyError):
        apply.require_exact_source(bytes(forged), value)


def test_projection_is_atomic_exact_seq70_71_and_zero_credit() -> None:
    before = source()
    if completion_is_published(before):
        result = before
        update, completion = result["goal_execution"]["transition_history"][-2:]
        assert apply.continuation.validate_fp022_completion_seq70_71(
            ROOT, result
        ) == []
    else:
        result, update, completion = apply.project_seq70_71(
            ROOT,
            before,
            evidence(before),
            runtime_deriver=runtime_deriver,
        )
        apply.validate_projection(before, result, evidence(before))
    history = result["goal_execution"]["transition_history"]
    if not completion_is_published(before):
        assert history[:-2] == before["goal_execution"]["transition_history"]
    assert [event["sequence"] for event in history[-2:]] == [70, 71]
    assert [event["event_type"] for event in history[-2:]] == [
        "CANONICAL_BINDINGS_UPDATED",
        "GOAL_COMPLETED",
    ]
    assert completion["previous_event_sha256"] == update["event_sha256"]
    state = result["goal_execution"]
    assert state["status_by_goal"][apply.GOAL_ID] == "COMPLETE_AT_TARGET"
    assert state["focus_goal_id"] == apply.PARENT_GOAL_ID
    assert state["focus_work_item_id"] == ""
    assert state["ready_frontier_goal_ids"] == [
        apply.PARENT_GOAL_ID,
        apply.EPIC12_GOAL_ID,
    ]
    assert result["approved_state"]["release_status"] == "NOT_ELIGIBLE"
    assert result["verification_boundary"]["formal_test_pass_claimed"] is False


def test_projection_rejects_non_adjacent_or_resealed_role_expansion() -> None:
    before = source()
    if completion_is_published(before):
        forged = copy.deepcopy(before)
        completion = forged["goal_execution"]["transition_history"][-1]
        completion["previous_event_sha256"] = "f" * 64
        completion["event_sha256"] = apply.continuation.event_sha256(completion)
        errors = apply.continuation.validate_fp022_completion_seq70_71(
            ROOT, forged
        )
        assert "FP022 completion seq71 previous differs" in errors

        forged = copy.deepcopy(before)
        update = forged["goal_execution"]["transition_history"][-2]
        update["changed_binding_roles"].append("POLICY_BASELINE")
        update["event_sha256"] = apply.continuation.event_sha256(update)
        errors = apply.continuation.validate_fp022_completion_seq70_71(
            ROOT, forged
        )
        assert "FP022 completion seq70 changed roles differs" in errors
        return
    item = evidence(before)
    result, _, _ = apply.project_seq70_71(
        ROOT, before, item, runtime_deriver=runtime_deriver
    )
    forged = copy.deepcopy(result)
    forged["goal_execution"]["transition_history"][-1]["previous_event_sha256"] = "f" * 64
    forged["goal_execution"]["transition_history"][-1]["event_sha256"] = apply.continuation.event_sha256(
        forged["goal_execution"]["transition_history"][-1]
    )
    with pytest.raises(apply.CompletionApplyError, match="not adjacent"):
        apply.validate_projection(before, forged, item)

    forged = copy.deepcopy(result)
    policy = next(row for row in forged["canonical_bindings"] if row["role"] == "POLICY_BASELINE")
    policy["file_sha256"] = "f" * 64
    with pytest.raises(apply.CompletionApplyError, match="role delta"):
        apply.validate_projection(before, forged, item)


def test_r028_pointer_and_zero_credit_fail_closed() -> None:
    item = evidence(source())
    backlog = copy.deepcopy(item.documents_by_path[apply.BACKLOG_JSON_REL])
    backlog["next_single_action"]["priority_rank"] = 26
    with pytest.raises(apply.CompletionApplyError, match="rank25"):
        apply._validate_r028(item.documents_by_path[apply.GAP_JSON_REL], backlog)
    boundary = {
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "external_tmap_status": "NOT_RUN",
        "external_review_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
        "formal_test_credit_delta": 0,
        "device_credit_delta": 0,
        "external_credit_delta": 0,
        "deployment_credit_delta": 0,
        "release_credit_delta": 0,
    }
    assert apply._zero_credit_boundary(boundary)
    boundary["device_credit_delta"] = 1
    assert not apply._zero_credit_boundary(boundary)


def test_production_atomic_writer_is_reused() -> None:
    assert apply.atomic.atomic_write is not None
    assert apply.npc_atomic.retain_physical_pin_cohort is not None


def test_checkpoint_commit_phase_accepts_only_source_or_projected_bytes(
    tmp_path: Path,
) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    source_raw = b"source"
    projected_raw = b"projected"
    checkpoint.write_bytes(source_raw)
    apply._require_checkpoint_commit_phase(
        checkpoint,
        source_raw,
        projected_raw,
    )
    checkpoint.write_bytes(projected_raw)
    apply._require_checkpoint_commit_phase(
        checkpoint,
        source_raw,
        projected_raw,
    )
    checkpoint.write_bytes(b"foreign")
    with pytest.raises(
        apply.CompletionApplyError,
        match="left the source/projected commit phases",
    ):
        apply._require_checkpoint_commit_phase(
            checkpoint,
            source_raw,
            projected_raw,
        )


def test_candidate_catalogs_must_already_be_in_the_managed_closure() -> None:
    item = evidence(source())
    final = dict(item.final_managed_sha256_by_path)
    physical = dict(item.physical_sha256_by_path)
    missing = apply.CATALOG_PATHS[0]
    final.pop(missing)
    physical.pop(missing)
    incomplete = apply.CompletionEvidence(
        documents_by_path=item.documents_by_path,
        bindings_by_role=item.bindings_by_role,
        update_occurred_at=item.update_occurred_at,
        completion_occurred_at=item.completion_occurred_at,
        physical_sha256_by_path=physical,
        final_managed_sha256_by_path=final,
        transition_review_binding=item.transition_review_binding,
    )
    candidates = {path: path.as_posix().encode("utf-8") for path in apply.CATALOG_PATHS}
    with pytest.raises(apply.CompletionApplyError, match="outside the final managed closure"):
        apply._with_catalogs(incomplete, candidates)


def test_ignored_lane_logs_are_physical_evidence_not_managed_sources() -> None:
    git_visible = apply.npc_atomic._git_visible_managed_paths(ROOT)
    assert set(apply.LANE_LOG_RELS).isdisjoint(git_visible)
    assert set(apply.LANE_LOG_RELS).issubset(apply.PRODUCTION_PATHS)
    assert set(apply.LANE_LOG_RELS).isdisjoint(apply.SEQUENCE_PATHS)
    assert all(
        apply.safe_file(ROOT, relative).is_file()
        for relative in apply.LANE_LOG_RELS
    )
    managed_path = apply.SCRIPT_REL
    physical = {
        **{relative: "a" * 64 for relative in apply.LANE_LOG_RELS},
        managed_path: "b" * 64,
    }
    managed = apply._final_managed_sha256_by_path(
        physical,
        {managed_path},
    )
    assert managed == {managed_path: "b" * 64}
    assert set(apply.LANE_LOG_RELS).isdisjoint(managed)
    assert all(relative in physical for relative in apply.LANE_LOG_RELS)
    with pytest.raises(
        apply.CompletionApplyError,
        match="ignored lane log entered",
    ):
        apply._final_managed_sha256_by_path(
            physical,
            {managed_path, apply.LANE_LOG_RELS[0]},
        )


def test_final_product_manifest_contributes_all_twenty_two_managed_paths() -> None:
    implementation = json.loads(
        (ROOT / apply.IMPLEMENTATION_REL).read_text(encoding="utf-8")
    )
    paths = apply._product_manifest_paths(implementation)
    assert len(paths) == 22
    assert all((ROOT / relative).is_file() for relative in paths)

    tampered = copy.deepcopy(implementation)
    tampered["final_content_manifest"]["files"][1]["path"] = tampered[
        "final_content_manifest"
    ]["files"][0]["path"]
    with pytest.raises(
        apply.CompletionApplyError,
        match="final product path set differs",
    ):
        apply._product_manifest_paths(tampered)
