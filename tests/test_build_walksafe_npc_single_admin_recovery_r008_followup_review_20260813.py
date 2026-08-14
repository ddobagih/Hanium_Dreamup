from __future__ import annotations

import copy
from pathlib import Path

import pytest

from scripts import build_walksafe_npc_single_admin_recovery_r008_followup_review_20260813 as subject


ROOT = Path(__file__).resolve().parents[1]


def identity(agent: str, task: str, role: str) -> dict[str, str]:
    return {"agent_instance_id": agent, "canonical_task": task, "role": role}


def assignment(context: subject.ReviewContext) -> dict:
    return {
        "schema_version": "1.0",
        "evidence_type": "NPC_R008_FOLLOWUP_REVIEW_ASSIGNMENT",
        "goal_id": subject.trace.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "assigned_at": "2026-08-13T20:50:00+09:00",
        "assigner": identity("codex-r008-assigner", "/root", "INTERNAL_REVIEW_ASSIGNER"),
        "executor": identity("codex-r008-executor", "/root", "INTERNAL_IMPLEMENTATION_EXECUTOR"),
        "reviewer": identity("codex-r008-reviewer", "/root/r008_reviewer", "SEPARATE_INTERNAL_REVIEWER"),
        "review_scope": subject._scope(context),
        "review_boundary": subject.BOUNDARY,
    }


def result(context: subject.ReviewContext, assigned: dict, raw: bytes) -> dict:
    return {
        "schema_version": "1.0",
        "evidence_type": "NPC_R008_REVIEWER_AUTHORED_RESULT",
        "goal_id": subject.trace.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "assignment_binding": {
            "path": subject.REVIEW_ASSIGNMENT_REL.as_posix(),
            "sha256": subject.trace.bytes_sha256(raw),
        },
        "reviewer": assigned["reviewer"],
        "reviewed_at": "2026-08-13T20:51:00+09:00",
        "review_scope": subject._scope(context),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [
            {
                "finding_id": finding_id,
                "disposition": "CLOSED",
                "rationale": "The exact fail-closed regression is verified.",
                "retest_commands": ["pytest targeted", "projected seq62 checker"],
            }
            for finding_id in subject.FINDING_IDS
        ],
        "review_boundary": subject.BOUNDARY,
    }


def test_frozen_r003_and_superseded_assignments_are_exact() -> None:
    context = subject.prepare_review_context(ROOT)
    assert len(context.superseded_assignment_bindings) == 4
    assert all(
        row["status"] == "SUPERSEDED_BEFORE_REVIEW"
        for row in context.superseded_assignment_bindings
    )


def test_assignment_result_and_independent_review_are_canonical() -> None:
    context = subject.prepare_review_context(ROOT)
    assigned = assignment(context)
    assigned_raw = subject.trace.json_text(assigned).encode()
    reviewed = result(context, assigned, assigned_raw)
    reviewed_raw = subject.trace.json_text(reviewed).encode()
    subject.validate_review_result(
        reviewed, reviewed_raw, assigned, assigned_raw, context
    )
    assert subject.build_independent_review(
        context, assigned, assigned_raw, reviewed, reviewed_raw
    ).endswith("\n")


def test_disposition_tamper_is_rejected() -> None:
    context = subject.prepare_review_context(ROOT)
    assigned = assignment(context)
    assigned_raw = subject.trace.json_text(assigned).encode()
    reviewed = result(context, assigned, assigned_raw)
    tampered = copy.deepcopy(reviewed)
    tampered["finding_dispositions"][0]["disposition"] = "OPEN"
    with pytest.raises(subject.ReviewError):
        subject.validate_review_result(
            tampered,
            subject.trace.json_text(tampered).encode(),
            assigned,
            assigned_raw,
            context,
        )
