#!/usr/bin/env python3
"""Validate the final R008 completion-control review."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813 as r004
from scripts import build_walksafe_npc_single_admin_recovery_r005_followup_review_20260813 as r005
from scripts import build_walksafe_npc_single_admin_recovery_r006_followup_review_20260813 as r006
from scripts import build_walksafe_npc_single_admin_recovery_r007_followup_review_20260813 as r007


trace = r007.trace
ROOT = Path(__file__).resolve().parents[1]
ROUND_ID = "WS-NPC-SINGLE-ADMIN-RECOVERY-REVIEW-20260813-R008"
REVIEW_DIR_REL = trace.RESULT_DIR_REL / "review-rounds" / "R008"
REVIEW_ASSIGNMENT_REL = REVIEW_DIR_REL / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_DIR_REL / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_DIR_REL / "independent-review.json"
REVIEW_PATHS = (REVIEW_ASSIGNMENT_REL, REVIEW_RESULT_REL, INDEPENDENT_REVIEW_REL)
FINDING_IDS = (
    "NPC-R008-PROJECTED-LINEAGE-P1-001",
    "NPC-R008-TEST-LAYER-P1-002",
    "NPC-R008-FROZEN-R003-CONSUMER-P1-003",
    "NPC-R008-FINAL-MANAGED-SUCCESSOR-P1-004",
    "NPC-R008-CLOSURE-FIXTURE-P1-005",
)
SUPERSEDED_ASSIGNMENT_PINS = (
    *r007.SUPERSEDED_ASSIGNMENT_PINS,
    (
        r007.REVIEW_ASSIGNMENT_REL,
        "75249e9b695e48a77a5f1e1f16f13a45e531a4324243f17d3a9808aca7a0dd6e",
        7_407,
        "the exact closure omission fixture lacked follow-up authority",
    ),
)
CONTROL_PATHS = (
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py"),
    Path("tests/test_apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r005_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r005_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r006_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r006_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r007_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r007_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r008_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r008_followup_review_20260813.py"),
)
BOUNDARY = r007.BOUNDARY
ReviewError = r007.ReviewError
require = r007.require
_raw = r007._raw
_document = r007._document
_binding = r007._binding
_identity = r007._identity
prepare_frozen_r003_context = r004.prepare_frozen_r003_context


@dataclass(frozen=True)
class ReviewContext:
    predecessor_bindings: tuple[dict[str, Any], ...]
    superseded_assignment_bindings: tuple[dict[str, Any], ...]
    control_code_cohort: tuple[dict[str, Any], ...]
    control_code_cohort_sha256: str


def prepare_review_context(root: Path = ROOT) -> ReviewContext:
    root = root.resolve(strict=True)
    prepare_frozen_r003_context(root)
    predecessor = tuple(
        _binding(path, _raw(root, path)) for path in r004.R003_PINS
    )
    superseded: list[dict[str, Any]] = []
    for path, digest, byte_length, reason in SUPERSEDED_ASSIGNMENT_PINS:
        raw = _raw(root, path)
        require(
            trace.bytes_sha256(raw) == digest and len(raw) == byte_length,
            f"superseded assignment differs: {path}",
        )
        superseded.append(
            {
                **_binding(path, raw),
                "status": "SUPERSEDED_BEFORE_REVIEW",
                "reason": reason,
            }
        )
    require(
        all(
            not (root / path).exists()
            for path in (
                r004.REVIEW_RESULT_REL,
                r004.INDEPENDENT_REVIEW_REL,
                r005.REVIEW_RESULT_REL,
                r005.INDEPENDENT_REVIEW_REL,
                r006.REVIEW_RESULT_REL,
                r006.INDEPENDENT_REVIEW_REL,
                r007.REVIEW_RESULT_REL,
                r007.INDEPENDENT_REVIEW_REL,
            )
        ),
        "superseded round unexpectedly has reviewer publication",
    )
    controls = tuple(_binding(path, _raw(root, path)) for path in CONTROL_PATHS)
    return ReviewContext(
        predecessor_bindings=predecessor,
        superseded_assignment_bindings=tuple(superseded),
        control_code_cohort=controls,
        control_code_cohort_sha256=trace.object_sha256(list(controls)),
    )


def _scope(context: ReviewContext) -> dict[str, Any]:
    return {
        "trigger": {
            "checkpoint_sequence": 60,
            "failed_command": "apply seq61/62 --refresh-catalogs",
            "diagnostic": (
                "archived v2.2 NPC permission-session changed artifact 21 differs"
            ),
        },
        "predecessor_review_bindings": list(context.predecessor_bindings),
        "superseded_assignments": list(context.superseded_assignment_bindings),
        "reviewed_control_code_cohort": list(context.control_code_cohort),
        "reviewed_control_code_cohort_sha256": (
            context.control_code_cohort_sha256
        ),
        "required_finding_ids": list(FINDING_IDS),
        "acceptance": {
            "exact_fp047_snapshot_overlay_required": True,
            "overlay_must_end_at_fp046_final_digest": True,
            "unrelated_or_disconnected_edge_fails_closed": True,
            "current_test_layer_registry_must_pass": True,
            "frozen_r003_context_required_after_control_changes": True,
            "r008_controls_must_supersede_r003_in_final_managed_closure": True,
            "closure_omission_fixture_must_cover_followup_authority": True,
            "projected_seq62_goal_graph_pass_required": True,
        },
    }


def validate_assignment(
    assignment: Mapping[str, Any], raw: bytes, context: ReviewContext
) -> None:
    require(raw == trace.json_text(assignment).encode(), "R008 assignment is noncanonical")
    require(
        set(assignment)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "assigned_at",
            "assigner",
            "executor",
            "reviewer",
            "review_scope",
            "review_boundary",
        }
        and assignment.get("schema_version") == "1.0"
        and assignment.get("evidence_type")
        == "NPC_R008_FOLLOWUP_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == trace.GOAL_ID
        and assignment.get("round_id") == ROUND_ID,
        "R008 assignment identity differs",
    )
    trace._parse_time(assignment.get("assigned_at"), "R008 assigned_at")
    assigner = _identity(assignment.get("assigner"), "INTERNAL_REVIEW_ASSIGNER")
    executor = _identity(
        assignment.get("executor"), "INTERNAL_IMPLEMENTATION_EXECUTOR"
    )
    reviewer = _identity(assignment.get("reviewer"), "SEPARATE_INTERNAL_REVIEWER")
    require(
        reviewer["agent_instance_id"]
        not in {assigner["agent_instance_id"], executor["agent_instance_id"]}
        and reviewer["canonical_task"]
        not in {assigner["canonical_task"], executor["canonical_task"]},
        "R008 reviewer is not separate",
    )
    require(assignment.get("review_scope") == _scope(context), "R008 scope differs")
    require(assignment.get("review_boundary") == BOUNDARY, "R008 boundary differs")


def validate_review_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
) -> None:
    validate_assignment(assignment, assignment_raw, context)
    require(raw == trace.json_text(result).encode(), "R008 result is noncanonical")
    require(
        set(result)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "assignment_binding",
            "reviewer",
            "reviewed_at",
            "review_scope",
            "decision",
            "findings",
            "finding_dispositions",
            "review_boundary",
        }
        and result.get("schema_version") == "1.0"
        and result.get("evidence_type")
        == "NPC_R008_REVIEWER_AUTHORED_RESULT"
        and result.get("goal_id") == trace.GOAL_ID
        and result.get("round_id") == ROUND_ID,
        "R008 result identity differs",
    )
    require(
        result.get("assignment_binding")
        == {
            "path": REVIEW_ASSIGNMENT_REL.as_posix(),
            "sha256": trace.bytes_sha256(assignment_raw),
        }
        and result.get("reviewer") == assignment.get("reviewer"),
        "R008 assignment or reviewer binding differs",
    )
    require(
        trace._parse_time(result.get("reviewed_at"), "R008 reviewed_at")
        >= trace._parse_time(assignment.get("assigned_at"), "R008 assigned_at"),
        "R008 review predates assignment",
    )
    require(result.get("review_scope") == _scope(context), "R008 result scope differs")
    require(
        result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []},
        "R008 review is not finding-free approved",
    )
    dispositions = result.get("finding_dispositions")
    require(
        type(dispositions) is list
        and [row.get("finding_id") for row in dispositions] == list(FINDING_IDS),
        "R008 finding disposition inventory differs",
    )
    for row in dispositions:
        require(
            type(row) is dict
            and set(row)
            == {"finding_id", "disposition", "rationale", "retest_commands"}
            and row.get("disposition") == "CLOSED"
            and type(row.get("rationale")) is str
            and bool(row["rationale"].strip())
            and type(row.get("retest_commands")) is list
            and len(row["retest_commands"]) >= 2
            and all(type(item) is str and item for item in row["retest_commands"]),
            "R008 finding disposition differs",
        )
    require(result.get("review_boundary") == BOUNDARY, "R008 result boundary differs")


def build_independent_review(
    context: ReviewContext,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_review_result(result, result_raw, assignment, assignment_raw, context)
    return trace.json_text(
        {
            "schema_version": "1.0",
            "evidence_type": "NPC_R008_INDEPENDENT_INTERNAL_REVIEW",
            "goal_id": trace.GOAL_ID,
            "round_id": ROUND_ID,
            "assignment_provenance": {
                "path": REVIEW_ASSIGNMENT_REL.as_posix(),
                "sha256": trace.bytes_sha256(assignment_raw),
            },
            "review_result_provenance": {
                "path": REVIEW_RESULT_REL.as_posix(),
                "sha256": trace.bytes_sha256(result_raw),
            },
            "reviewer": result["reviewer"],
            "reviewed_at": result["reviewed_at"],
            "review_scope": _scope(context),
            "decision": result["decision"],
            "findings": result["findings"],
            "finding_dispositions": result["finding_dispositions"],
            "review_boundary": BOUNDARY,
            "status": "PASS",
        }
    )


def load_review_inputs(
    root: Path, context: ReviewContext
) -> tuple[dict[str, Any], bytes, dict[str, Any], bytes]:
    assignment, assignment_raw = _document(root, REVIEW_ASSIGNMENT_REL)
    result, result_raw = _document(root, REVIEW_RESULT_REL)
    validate_review_result(result, result_raw, assignment, assignment_raw, context)
    return assignment, assignment_raw, result, result_raw


def validate_followup_authority(root: Path = ROOT) -> None:
    context = prepare_review_context(root)
    assignment, assignment_raw, result, result_raw = load_review_inputs(root, context)
    require(
        _raw(root, INDEPENDENT_REVIEW_REL)
        == build_independent_review(
            context, assignment, assignment_raw, result, result_raw
        ).encode(),
        "R008 independent review differs",
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--check-assignment", action="store_true")
    group.add_argument("--check-review-result", action="store_true")
    group.add_argument("--check-post-review", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        context = prepare_review_context(args.root)
        assignment, assignment_raw = _document(args.root, REVIEW_ASSIGNMENT_REL)
        validate_assignment(assignment, assignment_raw, context)
        if not args.check_assignment:
            result, result_raw = _document(args.root, REVIEW_RESULT_REL)
            validate_review_result(
                result, result_raw, assignment, assignment_raw, context
            )
            if args.check_post_review:
                validate_followup_authority(args.root)
    except (OSError, ValueError, ReviewError, trace.BuildError) as exc:
        print(f"NPC R008 follow-up review: FAIL: {exc}")
        return 1
    print("NPC R008 follow-up review: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
