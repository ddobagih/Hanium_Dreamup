from __future__ import annotations

from pathlib import Path
import copy

import pytest

from scripts import build_walksafe_workstream_aggregate_review_20260813 as subject


ROOT = Path(__file__).resolve().parents[1]


def test_frozen_r011_replays_after_current_control_changes() -> None:
    context = subject.prepare_frozen_r011_context(ROOT)
    assert len(context.control_code_cohort) == 23


def test_review_context_is_exact_and_includes_transition_control() -> None:
    context = subject.prepare_review_context(ROOT)
    paths = [row["path"] for row in context.control_code_cohort]
    assert paths == [path.as_posix() for path in subject.CONTROL_PATHS]
    assert len(paths) == len(set(paths)) == 11
    assert len(context.frozen_r011.control_code_cohort) == 23
    assert len(context.r011_control_transitions) == 5
    assert len(context.unchanged_r011_controls) == 18
    assert len(context.added_control_bindings) == 6
    scope = subject._scope(context)
    assert len(scope["superseded_review_assignments"]) == 2
    assert all(
        row["status"] == "SUPERSEDED_BEFORE_REVIEW_RESULT"
        for row in scope["superseded_review_assignments"]
    )


def test_superseded_review_outputs_must_remain_absent(tmp_path: Path) -> None:
    relative = "review-round/result.json"
    superseded = ({"must_remain_absent": [relative]},)
    subject._require_superseded_outputs_absent(tmp_path, superseded)

    target = tmp_path / relative
    target.parent.mkdir(parents=True)
    target.symlink_to("missing-result.json")
    with pytest.raises(subject.ReviewError, match="unexpectedly exists"):
        subject._require_superseded_outputs_absent(tmp_path, superseded)


def test_assignment_rejects_control_hash_drift() -> None:
    context = subject.prepare_review_context(ROOT)
    assignment = {
        "schema_version": "1.0",
        "evidence_type": "WORKSTREAM_AGGREGATE_REVIEW_ASSIGNMENT",
        "round_id": subject.ROUND_ID,
        "assigned_at": "2026-08-13T00:00:00+00:00",
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": "assigner",
            "canonical_task": "/root",
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": "executor",
            "canonical_task": "/root",
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": "reviewer",
            "canonical_task": "/root/workstream_aggregate_reviewer",
        },
        "review_scope": subject._scope(context),
        "review_boundary": subject.BOUNDARY,
    }
    assignment["review_scope"]["reviewed_control_code_cohort"][0]["sha256"] = "0" * 64
    raw = subject.trace.json_text(assignment).encode()
    with pytest.raises(subject.ReviewError, match="scope differs"):
        subject.validate_assignment(assignment, raw, context)


def valid_assignment(context: subject.ReviewContext) -> dict:
    return {
        "schema_version": "1.0",
        "evidence_type": "WORKSTREAM_AGGREGATE_REVIEW_ASSIGNMENT",
        "round_id": subject.ROUND_ID,
        "assigned_at": "2026-08-13T00:00:00+00:00",
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": "assigner",
            "canonical_task": "/root",
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": "executor",
            "canonical_task": "/root",
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": "reviewer",
            "canonical_task": "/root/workstream_aggregate_reviewer",
        },
        "review_scope": subject._scope(context),
        "review_boundary": subject.BOUNDARY,
    }


def valid_result(context: subject.ReviewContext, assignment: dict) -> dict:
    assignment_raw = subject.trace.json_text(assignment).encode()
    return {
        "schema_version": "1.0",
        "evidence_type": "WORKSTREAM_AGGREGATE_REVIEW_RESULT",
        "round_id": subject.ROUND_ID,
        "reviewed_at": "2026-08-13T00:00:01+00:00",
        "reviewer": assignment["reviewer"],
        "assignment_binding": subject._binding(subject.ASSIGNMENT_REL, assignment_raw),
        "review_scope": subject._scope(context),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [
            {
                "finding_id": finding_id,
                "disposition": "CLOSED",
                "rationale": "verified",
                "retest_commands": ["locked review regression"],
            }
            for finding_id in subject.FINDING_IDS
        ],
        "review_boundary": subject.BOUNDARY,
    }


def test_assignment_rejects_omitted_or_third_digest_transition() -> None:
    context = subject.prepare_review_context(ROOT)
    for mutation in ("omit", "third"):
        assignment = valid_assignment(context)
        transitions = assignment["review_scope"]["r011_control_transitions"]
        if mutation == "omit":
            transitions.pop()
        else:
            transitions[0]["after_sha256"] = "f" * 64
        raw = subject.trace.json_text(assignment).encode()
        with pytest.raises(subject.ReviewError, match="scope differs"):
            subject.validate_assignment(assignment, raw, context)


def test_review_result_rejects_same_actor_or_open_finding() -> None:
    context = subject.prepare_review_context(ROOT)
    assignment = valid_assignment(context)
    assignment_raw = subject.trace.json_text(assignment).encode()
    subject.validate_assignment(assignment, assignment_raw, context)
    result = valid_result(context, assignment)
    result["reviewer"] = copy.deepcopy(assignment["executor"])
    raw = subject.trace.json_text(result).encode()
    with pytest.raises(subject.ReviewError, match="reviewer binding differs"):
        subject.validate_review_result(
            result, raw, assignment, assignment_raw, context
        )

    result = valid_result(context, assignment)
    result["findings"]["major_open"] = [{"finding_id": "open"}]
    raw = subject.trace.json_text(result).encode()
    with pytest.raises(subject.ReviewError, match="not finding-free"):
        subject.validate_review_result(
            result, raw, assignment, assignment_raw, context
        )
