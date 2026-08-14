from __future__ import annotations

import copy
from pathlib import Path

import pytest

from scripts import build_walksafe_fp022_completion_seq70_71_review_20260814 as subject


ROOT = Path(__file__).resolve().parents[1]


def _identity(agent: str, task: str, role: str) -> dict[str, str]:
    return {
        "role": role,
        "agent_instance_id": agent,
        "canonical_task": task,
    }


def _binding(path: Path, marker: str) -> dict[str, object]:
    raw = (path.as_posix() + marker).encode()
    return {
        "path": path.as_posix(),
        "sha256": subject.bytes_sha256(raw),
        "byte_length": len(raw),
    }


def _context(tmp_path: Path) -> subject.ReviewContext:
    start = tuple(_binding(path, "start") for path in subject.START_REVIEW_PATHS)
    evidence = tuple(
        _binding(path, "evidence") for path in subject.EVIDENCE_PATHS
    )
    controls = tuple(
        _binding(path, "control") for path in subject.CONTROL_PATHS
    )
    return subject.ReviewContext(
        root=tmp_path,
        start_review_bindings=start,
        completion_evidence_bindings=evidence,
        superseded_assignment_bindings=tuple(
            copy.deepcopy(subject.SUPERSEDED_ASSIGNMENTS)
        ),
        control_code_cohort=controls,
        control_code_cohort_sha256=subject.object_sha256(list(controls)),
    )


def _assignment(context: subject.ReviewContext) -> dict:
    return {
        "schema_version": "1.0",
        "evidence_type": "FP022_SEQ70_71_COMPLETION_REVIEW_ASSIGNMENT",
        "goal_id": subject.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "assigned_at": "2026-08-14T05:00:00+09:00",
        "assigner": _identity(
            subject.ASSIGNER_ID,
            subject.ASSIGNER_TASK,
            "INTERNAL_REVIEW_ASSIGNER",
        ),
        "executor": _identity(
            subject.EXECUTOR_ID,
            subject.EXECUTOR_TASK,
            "INTERNAL_IMPLEMENTATION_EXECUTOR",
        ),
        "reviewer": _identity(
            subject.REVIEWER_ID,
            subject.REVIEWER_TASK,
            "SEPARATE_INTERNAL_REVIEWER",
        ),
        "review_scope": subject._scope(context),
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }


def _result(context: subject.ReviewContext, assignment: dict) -> dict:
    assignment_raw = subject.json_text(assignment).encode()
    return {
        "schema_version": "1.0",
        "evidence_type": "FP022_SEQ70_71_COMPLETION_REVIEWER_AUTHORED_RESULT",
        "goal_id": subject.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "reviewed_at": "2026-08-14T05:01:00+09:00",
        "reviewer": copy.deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(
            subject.ASSIGNMENT_REL,
            assignment_raw,
        ),
        "review_scope": subject._scope(context),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }


def test_exact_seq69_source_and_frozen_start_review() -> None:
    assert subject.SOURCE_CHECKPOINT == {
        "path": "docs/control/walksafe-project-continuation-checkpoint.json",
        "sequence": 69,
        "sha256": "5f260789269a5936517620b55262215b77797e4b2ec83220ec94d20cf951c25f",
        "byte_length": 2_032_842,
        "tail_event_id": "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001",
        "tail_event_sha256": "91cec421d1fdd2f0f0d3ec57e282f7dceb7ae1a6fe51c9db9f9ffb7bba3e38a6",
    }
    checkpoint = subject.strict_json_bytes(
        (ROOT / subject.CHECKPOINT_REL).read_bytes(),
        subject.CHECKPOINT_REL.as_posix(),
    )
    history = checkpoint["goal_execution"]["transition_history"]
    subject._require_source(
        ROOT,
        require_exact=len(history) == subject.SOURCE_CHECKPOINT["sequence"],
    )
    bindings = subject.prepare_frozen_start_review(ROOT)
    assert tuple(row["path"] for row in bindings) == tuple(
        path.as_posix() for path in subject.START_REVIEW_PATHS
    )
    assert tuple((row["sha256"], row["byte_length"]) for row in bindings) == tuple(
        subject.START_REVIEW_PINS[path] for path in subject.START_REVIEW_PATHS
    )
    frozen = subject.prepare_frozen_start_review_context(ROOT)
    assert frozen.control_code_cohort_sha256 == subject.strict_json_bytes(
        (ROOT / subject.start_review.ASSIGNMENT_REL).read_bytes(),
        subject.start_review.ASSIGNMENT_REL.as_posix(),
    )["review_scope"]["reviewed_control_code_cohort_sha256"]


def test_source_byte_drift_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    original = subject._raw

    def drift(root: Path, relative: Path) -> bytes:
        raw = original(root, relative)
        if relative == subject.CHECKPOINT_REL:
            return raw[:-1] + b" "
        return raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="exact seq69"):
        subject._require_source(ROOT, require_exact=True)


def test_evidence_and_control_cohorts_are_exact() -> None:
    assert subject.EVIDENCE_PATHS == (
        subject.evidence.IMPLEMENTATION_REL,
        subject.evidence.OBSERVATIONS_REL,
        *(lane.log_rel for lane in subject.evidence.LANES),
        subject.evidence.VERIFICATION_REL,
        *subject.evidence.R028_PATHS,
        subject.evidence.SUCCESSOR_REL,
        subject.evidence.REVIEW_SUBJECT_REL,
        subject.evidence.INDEPENDENT_REVIEW_REL,
        subject.evidence.COMPLETION_REL,
    )
    assert len(subject.EVIDENCE_PATHS) == len(set(subject.EVIDENCE_PATHS)) == 14
    assert subject.UNMANAGED_EVIDENCE_PATHS == tuple(
        lane.log_rel for lane in subject.evidence.LANES
    )
    assert len(subject.CONTROL_PATHS) == len(set(subject.CONTROL_PATHS)) == 15
    assert subject.CONTROL_PATHS[-2:] == (subject.SCRIPT_REL, subject.TEST_REL)
    assert subject.CONTROL_PATHS[7:13] == (
        subject.EVIDENCE_SCRIPT_REL,
        subject.EVIDENCE_TEST_REL,
        subject.R028_SCRIPT_REL,
        subject.R028_TEST_REL,
        subject.completion.SCRIPT_REL,
        subject.completion.TEST_REL,
    )


def test_live_completion_chain_freezes_all_required_inputs() -> None:
    context = subject.prepare_review_context(ROOT)
    assert context.superseded_assignment_bindings == subject.SUPERSEDED_ASSIGNMENTS
    assert tuple(row["path"] for row in context.completion_evidence_bindings) == tuple(
        path.as_posix() for path in subject.EVIDENCE_PATHS
    )
    assert tuple(row["path"] for row in context.control_code_cohort) == tuple(
        path.as_posix() for path in subject.CONTROL_PATHS
    )
    assert context.control_code_cohort_sha256 == subject.object_sha256(
        list(context.control_code_cohort)
    )


def test_superseded_rounds_are_exact_and_have_no_late_review_output() -> None:
    assert subject.REVIEW_DIR.name == "R014"
    assert subject.ROUND_ID.endswith("-R014")
    assert len(subject.SUPERSEDED_ASSIGNMENTS) == 13
    assert subject.SUPERSEDED_ASSIGNMENTS[0]["path"] == subject.R001_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[0]["sha256"] == (
        "ff8f90f87ae184423a6af793a651fb47cbef7056cdef1ce5ab4b4f2d89e52ff4"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[1]["path"] == subject.R002_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[1]["sha256"] == (
        "51a141e80d0a6dd63a23ecfe381167cb40af3c76b359193abc02f3f61f0e4a79"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[2]["path"] == subject.R003_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[2]["sha256"] == (
        "4146230011874697d4b72222a0697981b490c1e70af5da26fafdad3aa23c3212"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[3]["path"] == subject.R004_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[3]["sha256"] == (
        "e021af8c11ff6b50648d5b1f36a41386c27eed2e27ce68c6f69020d11fb4d850"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[4]["path"] == subject.R005_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[4]["sha256"] == (
        "d1b21d6548a73ed01c503ac671adb6e63197c004d8a7176b8ab33090656c01ba"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[5]["path"] == subject.R006_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[5]["sha256"] == (
        "16070d016fb8f01910ef9a07f7c5369b2950d4c73e8351a67c8249675a625152"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[6]["path"] == subject.R007_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[6]["sha256"] == (
        "4e372c5c36c01f7416742e1ed411bae54561ca796814da364c67009fe89d5d2f"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[7]["path"] == subject.R008_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[7]["sha256"] == (
        "bcfaa4f23c7858173bc0c396e405e7ff5ebe98e5508288daf7c65569dfd117ce"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[8]["path"] == subject.R009_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[8]["sha256"] == (
        "9d3075a1c5cbcb75025acaf060e443c24e95da57cdf8635e2965cdf64a982cb4"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[9]["path"] == subject.R010_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[9]["sha256"] == (
        "06b803513d79ac0da6ee8bc18d4ae067a828a9a2af355ef60ced995384174664"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[10]["path"] == subject.R011_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[10]["sha256"] == (
        "9ef4c4574e171d1f3ba79bce01f0578772689382f73684855f1704f5aa34dd33"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[11]["path"] == subject.R012_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[11]["sha256"] == (
        "b0ddf7ce9d9970e6111f03d6b681ad06683a1f84602896562907c071373598e0"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[12]["path"] == subject.R013_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[12]["sha256"] == (
        "d8e415055f89f318565fb181a6c44336eba899899f1f4376c2da6e80af73fbe5"
    )
    for relative in (
        subject.R001_RESULT_REL,
        subject.R001_INDEPENDENT_REL,
        subject.R002_RESULT_REL,
        subject.R002_INDEPENDENT_REL,
        subject.R003_RESULT_REL,
        subject.R003_INDEPENDENT_REL,
        subject.R004_RESULT_REL,
        subject.R004_INDEPENDENT_REL,
        subject.R005_RESULT_REL,
        subject.R005_INDEPENDENT_REL,
        subject.R006_RESULT_REL,
        subject.R006_INDEPENDENT_REL,
        subject.R007_RESULT_REL,
        subject.R007_INDEPENDENT_REL,
        subject.R008_RESULT_REL,
        subject.R008_INDEPENDENT_REL,
        subject.R009_RESULT_REL,
        subject.R009_INDEPENDENT_REL,
        subject.R010_RESULT_REL,
        subject.R010_INDEPENDENT_REL,
        subject.R011_RESULT_REL,
        subject.R011_INDEPENDENT_REL,
        subject.R012_RESULT_REL,
        subject.R012_INDEPENDENT_REL,
        subject.R013_RESULT_REL,
        subject.R013_INDEPENDENT_REL,
    ):
        assert not subject.os.path.lexists(ROOT / relative)


def test_assignment_cannot_prepare_before_follow_on_evidence(
    tmp_path: Path,
) -> None:
    with pytest.raises((OSError, subject.ReviewError)):
        subject._bind_paths(tmp_path, subject.EVIDENCE_PATHS)


def test_projected_seq70_71_acceptance_and_zero_credit_boundary() -> None:
    subject._require_transition_constants()
    assert subject.PROJECTED_TRANSITION == {
        "canonical_bindings_updated": {
            "sequence": 70,
            "event_id": subject.completion.UPDATE_EVENT_ID,
            "event_type": "CANONICAL_BINDINGS_UPDATED",
            "from_status": "IN_PROGRESS",
            "to_status": "IN_PROGRESS",
            "status_changes": {},
            "transition_review_binding_required": True,
        },
        "goal_completed": {
            "sequence": 71,
            "event_id": subject.completion.COMPLETION_EVENT_ID,
            "event_type": "GOAL_COMPLETED",
            "subject_goal_id": subject.GOAL_ID,
            "from_status": "IN_PROGRESS",
            "to_status": "COMPLETE_AT_TARGET",
            "status_changes": {subject.GOAL_ID: "COMPLETE_AT_TARGET"},
        },
        "final_focus_goal_id": subject.completion.PARENT_GOAL_ID,
        "final_status": "COMPLETE_AT_TARGET",
        "next_policy_id": "FP-023",
        "next_priority_rank": 25,
    }
    assert subject.BOUNDARY["release_status"] == "NOT_ELIGIBLE"
    assert subject.BOUNDARY["external_independence_claimed"] is False
    assert all(
        value == 0
        for key, value in subject.BOUNDARY.items()
        if key.endswith("_credit_added")
    )


def test_assignment_requires_exact_actor_separation(tmp_path: Path) -> None:
    context = _context(tmp_path)
    assignment = _assignment(context)
    raw = subject.json_text(assignment).encode()
    subject.validate_assignment(assignment, raw, context)

    wrong = copy.deepcopy(assignment)
    wrong["reviewer"]["agent_instance_id"] = subject.EXECUTOR_ID
    with pytest.raises(subject.ReviewError, match="reviewer identity differs"):
        subject.validate_assignment(wrong, subject.json_text(wrong).encode(), context)

    wrong = copy.deepcopy(assignment)
    wrong["assigner"]["canonical_task"] = "/root/not-root"
    with pytest.raises(subject.ReviewError, match="assigner identity differs"):
        subject.validate_assignment(wrong, subject.json_text(wrong).encode(), context)


def test_result_requires_approved_empty_findings_exact_scope_and_boundary(
    tmp_path: Path,
) -> None:
    context = _context(tmp_path)
    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    subject.validate_review_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )

    mutations = (
        ("decision", "REJECTED", "finding-free approved"),
        ("findings", {"blocking": ["B1"], "major_open": [], "minor_open": []}, "finding-free approved"),
        ("review_scope", {}, "scope differs"),
        ("review_boundary", {}, "boundary differs"),
    )
    for field, value, message in mutations:
        changed = copy.deepcopy(result)
        changed[field] = value
        with pytest.raises(subject.ReviewError, match=message):
            subject.validate_review_result(
                changed,
                subject.json_text(changed).encode(),
                assignment,
                assignment_raw,
                context,
            )


def test_independent_review_is_deterministic_and_result_follows_assignment(
    tmp_path: Path,
) -> None:
    context = _context(tmp_path)
    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    first = subject.build_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    second = subject.build_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    assert first == second
    assert first.endswith("\n")

    changed = copy.deepcopy(result)
    changed["reviewed_at"] = "2026-08-14T04:59:59+09:00"
    with pytest.raises(subject.ReviewError, match="predates assignment"):
        subject.validate_review_result(
            changed,
            subject.json_text(changed).encode(),
            assignment,
            assignment_raw,
            context,
        )


def test_tool_has_no_reviewer_result_writer() -> None:
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-review-result"])
    assert not hasattr(subject, "write_review_result")
