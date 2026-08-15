#!/usr/bin/env python3
"""Validate the final R005 review after R004 was superseded pre-review."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_trace_20260812 as trace
from scripts import build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812 as r003
from scripts import build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813 as r004


ROOT = Path(__file__).resolve().parents[1]
ROUND_ID = "WS-NPC-SINGLE-ADMIN-RECOVERY-REVIEW-20260813-R005"
REVIEW_DIR_REL = trace.RESULT_DIR_REL / "review-rounds" / "R005"
REVIEW_ASSIGNMENT_REL = REVIEW_DIR_REL / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_DIR_REL / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_DIR_REL / "independent-review.json"
REVIEW_PATHS = (REVIEW_ASSIGNMENT_REL, REVIEW_RESULT_REL, INDEPENDENT_REVIEW_REL)
FINDING_IDS = (
    "NPC-R005-PROJECTED-LINEAGE-P1-001",
    "NPC-R005-TEST-LAYER-P1-002",
)
R004_ABORTED_ASSIGNMENT_PIN = (
    r004.REVIEW_ASSIGNMENT_REL,
    "094ba59f38837af29e84710ea8e5ca9b7f21c5cf03269a4f1d7a1362eab455fb",
    4_229,
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
)
BOUNDARY = r004.BOUNDARY


class ReviewError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReviewError(message)


def _raw(root: Path, relative: Path) -> bytes:
    return r003._read_review_bytes(root, relative)


def _document(root: Path, relative: Path) -> tuple[dict[str, Any], bytes]:
    raw = _raw(root, relative)
    return trace.strict_json_bytes(raw, relative.as_posix()), raw


def _binding(path: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": trace.bytes_sha256(raw),
        "byte_length": len(raw),
    }


def _identity(value: Any, role: str) -> dict[str, str]:
    require(
        type(value) is dict
        and set(value) == {"agent_instance_id", "canonical_task", "role"}
        and all(type(item) is str and item for item in value.values())
        and value["role"] == role,
        f"{role} identity differs",
    )
    return value


@dataclass(frozen=True)
class ReviewContext:
    predecessor_bindings: tuple[dict[str, Any], ...]
    aborted_assignment_binding: dict[str, Any]
    control_code_cohort: tuple[dict[str, Any], ...]
    control_code_cohort_sha256: str


prepare_frozen_r003_context = r004.prepare_frozen_r003_context


def prepare_review_context(root: Path = ROOT) -> ReviewContext:
    root = root.resolve(strict=True)
    prepare_frozen_r003_context(root)
    predecessor = tuple(
        _binding(path, _raw(root, path)) for path in r004.R003_PINS
    )
    aborted_path, aborted_sha, aborted_size = R004_ABORTED_ASSIGNMENT_PIN
    aborted_raw = _raw(root, aborted_path)
    require(
        trace.bytes_sha256(aborted_raw) == aborted_sha
        and len(aborted_raw) == aborted_size,
        "R004 superseded assignment binding differs",
    )
    require(
        not (root / r004.REVIEW_RESULT_REL).exists()
        and not (root / r004.INDEPENDENT_REVIEW_REL).exists(),
        "R004 was not superseded before reviewer publication",
    )
    controls = tuple(_binding(path, _raw(root, path)) for path in CONTROL_PATHS)
    return ReviewContext(
        predecessor_bindings=predecessor,
        aborted_assignment_binding={
            **_binding(aborted_path, aborted_raw),
            "status": "SUPERSEDED_BEFORE_REVIEW",
            "reason": "R004 test was absent from the current layer registry",
        },
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
        "superseded_r004_assignment": context.aborted_assignment_binding,
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
            "projected_seq62_goal_graph_pass_required": True,
        },
    }


def validate_assignment(
    assignment: Mapping[str, Any], raw: bytes, context: ReviewContext
) -> None:
    require(raw == trace.json_text(assignment).encode(), "R005 assignment is noncanonical")
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
        == "NPC_R005_FOLLOWUP_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == trace.GOAL_ID
        and assignment.get("round_id") == ROUND_ID,
        "R005 assignment identity differs",
    )
    trace._parse_time(assignment.get("assigned_at"), "R005 assigned_at")
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
        "R005 reviewer is not separate",
    )
    require(assignment.get("review_scope") == _scope(context), "R005 scope differs")
    require(assignment.get("review_boundary") == BOUNDARY, "R005 boundary differs")


def validate_review_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
) -> None:
    validate_assignment(assignment, assignment_raw, context)
    require(raw == trace.json_text(result).encode(), "R005 result is noncanonical")
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
        == "NPC_R005_REVIEWER_AUTHORED_RESULT"
        and result.get("goal_id") == trace.GOAL_ID
        and result.get("round_id") == ROUND_ID,
        "R005 result identity differs",
    )
    require(
        result.get("assignment_binding")
        == {
            "path": REVIEW_ASSIGNMENT_REL.as_posix(),
            "sha256": trace.bytes_sha256(assignment_raw),
        }
        and result.get("reviewer") == assignment.get("reviewer"),
        "R005 assignment or reviewer binding differs",
    )
    require(
        trace._parse_time(result.get("reviewed_at"), "R005 reviewed_at")
        >= trace._parse_time(assignment.get("assigned_at"), "R005 assigned_at"),
        "R005 review predates assignment",
    )
    require(result.get("review_scope") == _scope(context), "R005 result scope differs")
    require(
        result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []},
        "R005 review is not finding-free approved",
    )
    dispositions = result.get("finding_dispositions")
    require(
        type(dispositions) is list
        and [row.get("finding_id") for row in dispositions] == list(FINDING_IDS),
        "R005 finding disposition inventory differs",
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
            "R005 finding disposition differs",
        )
    require(result.get("review_boundary") == BOUNDARY, "R005 result boundary differs")


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
            "evidence_type": "NPC_R005_INDEPENDENT_INTERNAL_REVIEW",
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
        "R005 independent review differs",
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
        print(f"NPC R005 follow-up review: FAIL: {exc}")
        return 1
    print("NPC R005 follow-up review: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
