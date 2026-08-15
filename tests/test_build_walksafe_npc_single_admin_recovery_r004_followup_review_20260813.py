from __future__ import annotations

import copy
from pathlib import Path

import pytest

from scripts import build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813 as subject


ROOT = Path(__file__).resolve().parents[1]


def identity(agent: str, task: str, role: str) -> dict[str, str]:
    return {"agent_instance_id": agent, "canonical_task": task, "role": role}


def assignment(context: subject.ReviewContext) -> dict:
    return {
        "schema_version": "1.0",
        "evidence_type": "NPC_R004_FOLLOWUP_REVIEW_ASSIGNMENT",
        "goal_id": subject.trace.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "assigned_at": "2026-08-13T20:00:00+09:00",
        "assigner": identity("codex-r004-assigner", "/root", "INTERNAL_REVIEW_ASSIGNER"),
        "executor": identity("codex-r004-executor", "/root", "INTERNAL_IMPLEMENTATION_EXECUTOR"),
        "reviewer": identity("codex-r004-reviewer", "/root/r004_reviewer", "SEPARATE_INTERNAL_REVIEWER"),
        "review_scope": subject._scope(context),
        "review_boundary": subject.BOUNDARY,
    }


def result(context: subject.ReviewContext, assigned: dict, assigned_raw: bytes) -> dict:
    return {
        "schema_version": "1.0",
        "evidence_type": "NPC_R004_REVIEWER_AUTHORED_RESULT",
        "goal_id": subject.trace.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "assignment_binding": {
            "path": subject.REVIEW_ASSIGNMENT_REL.as_posix(),
            "sha256": subject.trace.bytes_sha256(assigned_raw),
        },
        "reviewer": assigned["reviewer"],
        "reviewed_at": "2026-08-13T20:01:00+09:00",
        "review_scope": subject._scope(context),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_disposition": {
            "finding_id": subject.FINDING_ID,
            "disposition": "CLOSED",
            "rationale": "The exact overlay and disconnected-edge regressions pass.",
            "retest_commands": ["pytest targeted", "projected seq62 goal graph"],
        },
        "review_boundary": subject.BOUNDARY,
    }


def test_frozen_r003_replay_and_current_control_cohort() -> None:
    assert subject.prepare_frozen_r003_context(ROOT).control_code_cohort_sha256
    context = subject.prepare_review_context(ROOT)
    assert [row["path"] for row in context.control_code_cohort] == [
        path.as_posix() for path in subject.CONTROL_PATHS
    ]


def test_assignment_result_and_independent_review_are_canonical() -> None:
    context = subject.prepare_review_context(ROOT)
    assigned = assignment(context)
    assigned_raw = subject.trace.json_text(assigned).encode()
    reviewed = result(context, assigned, assigned_raw)
    reviewed_raw = subject.trace.json_text(reviewed).encode()
    subject.validate_review_result(
        reviewed, reviewed_raw, assigned, assigned_raw, context
    )
    rebuilt = subject.build_independent_review(
        context, assigned, assigned_raw, reviewed, reviewed_raw
    )
    assert rebuilt.endswith("\n")


def test_control_or_disposition_tamper_is_rejected() -> None:
    context = subject.prepare_review_context(ROOT)
    assigned = assignment(context)
    assigned_raw = subject.trace.json_text(assigned).encode()
    reviewed = result(context, assigned, assigned_raw)
    tampered = copy.deepcopy(reviewed)
    tampered["finding_disposition"]["disposition"] = "OPEN"
    with pytest.raises(subject.ReviewError):
        subject.validate_review_result(
            tampered,
            subject.trace.json_text(tampered).encode(),
            assigned,
            assigned_raw,
            context,
        )
