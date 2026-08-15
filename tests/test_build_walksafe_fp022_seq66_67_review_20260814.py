from __future__ import annotations

import copy
from pathlib import Path

import pytest

from scripts import build_walksafe_fp022_seq66_67_review_20260814 as subject


ROOT = Path(__file__).resolve().parents[1]


def _identity(agent: str, task: str, role: str) -> dict[str, str]:
    return {"agent_instance_id": agent, "canonical_task": task, "role": role}


def _assignment(context: subject.ReviewContext) -> dict:
    return {
        "schema_version": "1.0",
        "evidence_type": "FP022_SEQ66_67_REVIEW_ASSIGNMENT",
        "goal_id": subject.fp022.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "assigned_at": "2026-08-14T00:00:00+09:00",
        "assigner": _identity("assigner", "/root", "INTERNAL_REVIEW_ASSIGNER"),
        "executor": _identity("executor", "/root", "INTERNAL_IMPLEMENTATION_EXECUTOR"),
        "reviewer": _identity(
            "reviewer",
            "/root/fp022_seq66_67_r002_reviewer",
            "SEPARATE_INTERNAL_REVIEWER",
        ),
        "review_scope": subject._scope(context),
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }


def _result(context: subject.ReviewContext, assignment: dict) -> dict:
    assignment_raw = subject.trace.json_text(assignment).encode()
    return {
        "schema_version": "1.0",
        "evidence_type": "FP022_SEQ66_67_REVIEWER_AUTHORED_RESULT",
        "goal_id": subject.fp022.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "reviewed_at": "2026-08-14T00:01:00+09:00",
        "reviewer": assignment["reviewer"],
        "assignment_binding": subject._binding(subject.ASSIGNMENT_REL, assignment_raw),
        "review_scope": subject._scope(context),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }


def test_frozen_aggregate_r003_and_exact_evidence_replay() -> None:
    bindings = subject.prepare_frozen_aggregate_review(ROOT)
    assert tuple(row["path"] for row in bindings) == tuple(
        path.as_posix() for path in subject.AGGREGATE_REVIEW_PINS
    )
    assert tuple((row["sha256"], row["byte_length"]) for row in bindings) == tuple(
        subject.AGGREGATE_REVIEW_PINS[path] for path in subject.AGGREGATE_REVIEW_PINS
    )

    context = subject.prepare_review_context(ROOT)
    assert context.goal_evidence == subject.GOAL_EVIDENCE
    assert context.contract_evidence == subject.CONTRACT_EVIDENCE
    assert subject._scope(context)["superseded_review_assignments"] == list(
        subject.SUPERSEDED_ASSIGNMENTS
    )


def test_control_cohort_is_exact_and_includes_builder_pair() -> None:
    context = subject.prepare_review_context(ROOT)
    paths = [row["path"] for row in context.control_code_cohort]
    assert paths == [path.as_posix() for path in subject.CONTROL_PATHS]
    assert len(paths) == len(set(paths)) == 14
    assert paths[-2:] == [subject.SCRIPT_REL.as_posix(), subject.TEST_REL.as_posix()]
    assert context.control_code_cohort_sha256 == subject.trace.object_sha256(
        list(context.control_code_cohort)
    )


def test_approved_r002_replays_from_its_frozen_control_cohort(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    current_raw = subject._raw

    def reject_live_control_read(root: Path, relative: Path) -> bytes:
        if relative in subject.CONTROL_PATHS:
            raise AssertionError(f"live control read: {relative}")
        return current_raw(root, relative)

    monkeypatch.setattr(subject, "_raw", reject_live_control_read)
    frozen = subject.prepare_frozen_review_context(ROOT)

    assert frozen.control_code_cohort_sha256 == subject.trace.object_sha256(
        list(frozen.control_code_cohort)
    )
    assert tuple(row["path"] for row in frozen.control_code_cohort) == tuple(
        path.as_posix() for path in subject.CONTROL_PATHS
    )


def test_assignment_rejects_same_actor_and_control_drift() -> None:
    context = subject.prepare_review_context(ROOT)
    assigned = _assignment(context)
    assigned["reviewer"] = _identity(
        assigned["executor"]["agent_instance_id"],
        assigned["executor"]["canonical_task"],
        "SEPARATE_INTERNAL_REVIEWER",
    )
    raw = subject.trace.json_text(assigned).encode()
    with pytest.raises(subject.ReviewError, match="reviewer is not separate"):
        subject.validate_assignment(assigned, raw, context)

    assigned = _assignment(context)
    assigned["review_scope"]["reviewed_control_code_cohort"][0]["sha256"] = "0" * 64
    raw = subject.trace.json_text(assigned).encode()
    with pytest.raises(subject.ReviewError, match="scope differs"):
        subject.validate_assignment(assigned, raw, context)


def test_reviewer_result_and_independent_are_canonical_and_deterministic() -> None:
    context = subject.prepare_review_context(ROOT)
    assigned = _assignment(context)
    assignment_raw = subject.trace.json_text(assigned).encode()
    reviewed = _result(context, assigned)
    result_raw = subject.trace.json_text(reviewed).encode()
    subject.validate_review_result(
        reviewed, result_raw, assigned, assignment_raw, context
    )
    first = subject.build_independent_review(
        context, assigned, assignment_raw, reviewed, result_raw
    )
    second = subject.build_independent_review(
        context, assigned, assignment_raw, reviewed, result_raw
    )
    assert first == second
    assert first.endswith("\n")


def test_result_rejects_open_findings_or_credit_boundary() -> None:
    context = subject.prepare_review_context(ROOT)
    assigned = _assignment(context)
    assignment_raw = subject.trace.json_text(assigned).encode()

    reviewed = _result(context, assigned)
    reviewed["findings"]["major_open"] = [{"finding_id": "OPEN"}]
    with pytest.raises(subject.ReviewError, match="not finding-free"):
        subject.validate_review_result(
            reviewed,
            subject.trace.json_text(reviewed).encode(),
            assigned,
            assignment_raw,
            context,
        )

    reviewed = _result(context, assigned)
    reviewed["review_boundary"]["actual_device_credit_added"] = 1
    with pytest.raises(subject.ReviewError, match="boundary differs"):
        subject.validate_review_result(
            reviewed,
            subject.trace.json_text(reviewed).encode(),
            assigned,
            assignment_raw,
            context,
        )


def test_tool_has_no_review_result_writer() -> None:
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-review-result"])
    assert not hasattr(subject, "write_review_result")


def test_exact_source_rejects_byte_drift(monkeypatch: pytest.MonkeyPatch) -> None:
    original = subject._raw

    def drift(root: Path, relative: Path) -> bytes:
        raw = original(root, relative)
        if relative.as_posix() == subject.SOURCE_CHECKPOINT["path"]:
            return raw[:-1] + b" "
        return raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="exact seq65"):
        subject._require_exact_source(ROOT)
