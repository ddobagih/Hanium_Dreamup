#!/usr/bin/env python3
"""Validate the add-only R004 review for the post-R003 transition fix."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_trace_20260812 as trace
from scripts import build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812 as r003


ROOT = Path(__file__).resolve().parents[1]
ROUND_ID = "WS-NPC-SINGLE-ADMIN-RECOVERY-REVIEW-20260813-R004"
REVIEW_DIR_REL = trace.RESULT_DIR_REL / "review-rounds" / "R004"
REVIEW_ASSIGNMENT_REL = REVIEW_DIR_REL / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_DIR_REL / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_DIR_REL / "independent-review.json"
REVIEW_PATHS = (REVIEW_ASSIGNMENT_REL, REVIEW_RESULT_REL, INDEPENDENT_REVIEW_REL)
FINDING_ID = "NPC-R004-PROJECTED-LINEAGE-P1-001"

R003_PINS = {
    r003.REVIEW_ASSIGNMENT_REL: (
        "86404b78e0424bc75a3f01fcb5cacc27347ca1c3f284af2a1be600f19de770e2",
        57_544,
    ),
    r003.REVIEW_RESULT_REL: (
        "bb733e12d6db46b366d187f4c3276635a3a6b470951e7089f9c02d2d38fc898c",
        74_252,
    ),
    r003.INDEPENDENT_REVIEW_REL: (
        "48775fc51138f2564ca2bbf77a2dcfe470a4e6e635b2338ae6dae7757f33afb9",
        74_539,
    ),
    r003.COMPLETION_RECEIPT_REL: (
        "c021a2fad05315c5a31e3726ed4f4b8717977030e95e4f9616d336c346ae5281",
        113_655,
    ),
}
CONTROL_PATHS = (
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py"),
    Path("tests/test_apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813.py"),
)
BOUNDARY = {
    "scope": "POST_R003_COMPLETION_CONTROL_ONLY",
    "product_execution_rerun_claimed": False,
    "formal_test_status": "NOT_RUN",
    "actual_device_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "release_status": "NOT_ELIGIBLE",
}


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
    control_code_cohort: tuple[dict[str, Any], ...]
    control_code_cohort_sha256: str


def prepare_frozen_r003_context(root: Path = ROOT) -> r003.ReviewContext:
    """Replay R003 against its frozen cohort, not mutable post-review controls."""
    root = root.resolve(strict=True)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for path, (digest, byte_length) in R003_PINS.items():
        document, raw = _document(root, path)
        require(
            len(raw) == byte_length and trace.bytes_sha256(raw) == digest,
            f"R003 predecessor binding differs: {path}",
        )
        documents[path] = document
        raw_by_path[path] = raw
    assignment = documents[r003.REVIEW_ASSIGNMENT_REL]
    result = documents[r003.REVIEW_RESULT_REL]
    completion = documents[r003.COMPLETION_RECEIPT_REL]
    scope = assignment.get("review_scope")
    require(type(scope) is dict, "R003 frozen review scope is missing")
    execution_window = completion.get("execution_window")
    require(type(execution_window) is dict, "R003 execution window is missing")
    result_raw = {path: _raw(root, path) for path in r003._result_paths()}
    context = r003.ReviewContext(
        result_raw=result_raw,
        consumer_bindings=tuple(scope["reviewed_consumer_bindings"]),
        evidence_manifest=tuple(scope["reviewed_evidence_manifest"]),
        review_subject_sha256=scope["review_subject"]["sha256"],
        reviewed_result_sha256_by_kind=dict(
            scope["reviewed_result_sha256_by_kind"]
        ),
        implementation_started_at=execution_window["started_at"],
        implementation_ended_at=execution_window["ended_at"],
        chronology=tuple(scope["reviewed_chronology"]),
        latest_reviewable_at=scope["latest_reviewable_at"],
        control_code_cohort=tuple(scope["reviewed_control_code_cohort"]),
        control_code_cohort_sha256=scope[
            "reviewed_control_code_cohort_sha256"
        ],
        prior_control_code_cohort_sha256=scope[
            "prior_control_code_cohort"
        ]["sha256"],
        control_code_changes_from_r002=tuple(
            scope["control_code_changes_from_r002"]
        ),
        predecessor_review=r003._load_predecessor_review(root),
        prior_approved_review=r003._load_prior_approved_review(root),
    )
    r003.validate_review_result(
        result,
        raw_by_path[r003.REVIEW_RESULT_REL],
        assignment,
        raw_by_path[r003.REVIEW_ASSIGNMENT_REL],
        context,
    )
    rebuilt = r003.build_post_review_outputs(
        context,
        assignment,
        raw_by_path[r003.REVIEW_ASSIGNMENT_REL],
        result,
        raw_by_path[r003.REVIEW_RESULT_REL],
    )
    for path in r003.POST_REVIEW_OUTPUT_PATHS:
        require(
            rebuilt[path].encode() == raw_by_path[path],
            f"R003 frozen post-review replay differs: {path}",
        )
    return context


def prepare_review_context(root: Path = ROOT) -> ReviewContext:
    root = root.resolve(strict=True)
    prepare_frozen_r003_context(root)
    predecessor = tuple(
        _binding(path, _raw(root, path)) for path in R003_PINS
    )
    controls = tuple(_binding(path, _raw(root, path)) for path in CONTROL_PATHS)
    return ReviewContext(
        predecessor_bindings=predecessor,
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
        "reviewed_control_code_cohort": list(context.control_code_cohort),
        "reviewed_control_code_cohort_sha256": (
            context.control_code_cohort_sha256
        ),
        "required_finding_id": FINDING_ID,
        "acceptance": {
            "exact_fp047_snapshot_overlay_required": True,
            "overlay_must_end_at_fp046_final_digest": True,
            "unrelated_or_disconnected_edge_fails_closed": True,
            "projected_seq62_goal_graph_pass_required": True,
        },
    }


def validate_assignment(
    assignment: Mapping[str, Any], raw: bytes, context: ReviewContext
) -> None:
    require(raw == trace.json_text(assignment).encode(), "R004 assignment is noncanonical")
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
        == "NPC_R004_FOLLOWUP_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == trace.GOAL_ID
        and assignment.get("round_id") == ROUND_ID,
        "R004 assignment identity differs",
    )
    trace._parse_time(assignment.get("assigned_at"), "R004 assigned_at")
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
        "R004 reviewer is not separate",
    )
    require(assignment.get("review_scope") == _scope(context), "R004 scope differs")
    require(assignment.get("review_boundary") == BOUNDARY, "R004 boundary differs")


def validate_review_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
) -> None:
    validate_assignment(assignment, assignment_raw, context)
    require(raw == trace.json_text(result).encode(), "R004 result is noncanonical")
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
            "finding_disposition",
            "review_boundary",
        }
        and result.get("schema_version") == "1.0"
        and result.get("evidence_type")
        == "NPC_R004_REVIEWER_AUTHORED_RESULT"
        and result.get("goal_id") == trace.GOAL_ID
        and result.get("round_id") == ROUND_ID,
        "R004 result identity differs",
    )
    require(
        result.get("assignment_binding")
        == {
            "path": REVIEW_ASSIGNMENT_REL.as_posix(),
            "sha256": trace.bytes_sha256(assignment_raw),
        },
        "R004 result assignment binding differs",
    )
    require(result.get("reviewer") == assignment.get("reviewer"), "R004 reviewer differs")
    require(
        trace._parse_time(result.get("reviewed_at"), "R004 reviewed_at")
        >= trace._parse_time(assignment.get("assigned_at"), "R004 assigned_at"),
        "R004 review predates assignment",
    )
    require(result.get("review_scope") == _scope(context), "R004 result scope differs")
    findings = result.get("findings")
    require(
        result.get("decision") == "APPROVED"
        and findings == {"blocking": [], "major_open": [], "minor_open": []},
        "R004 review is not finding-free approved",
    )
    disposition = result.get("finding_disposition")
    require(
        type(disposition) is dict
        and set(disposition)
        == {"finding_id", "disposition", "rationale", "retest_commands"}
        and disposition.get("finding_id") == FINDING_ID
        and disposition.get("disposition") == "CLOSED"
        and type(disposition.get("rationale")) is str
        and bool(disposition["rationale"].strip())
        and type(disposition.get("retest_commands")) is list
        and len(disposition["retest_commands"]) >= 2
        and all(type(item) is str and item for item in disposition["retest_commands"]),
        "R004 finding disposition differs",
    )
    require(result.get("review_boundary") == BOUNDARY, "R004 result boundary differs")


def build_independent_review(
    context: ReviewContext,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_review_result(result, result_raw, assignment, assignment_raw, context)
    document = {
        "schema_version": "1.0",
        "evidence_type": "NPC_R004_INDEPENDENT_INTERNAL_REVIEW",
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
        "finding_disposition": result["finding_disposition"],
        "review_boundary": BOUNDARY,
        "status": "PASS",
    }
    return trace.json_text(document)


def load_review_inputs(
    root: Path, context: ReviewContext
) -> tuple[dict[str, Any], bytes, dict[str, Any], bytes]:
    assignment, assignment_raw = _document(root, REVIEW_ASSIGNMENT_REL)
    result, result_raw = _document(root, REVIEW_RESULT_REL)
    validate_review_result(
        result, result_raw, assignment, assignment_raw, context
    )
    return assignment, assignment_raw, result, result_raw


def validate_followup_authority(root: Path = ROOT) -> None:
    context = prepare_review_context(root)
    assignment, assignment_raw, result, result_raw = load_review_inputs(
        root, context
    )
    independent_raw = _raw(root, INDEPENDENT_REVIEW_REL)
    require(
        independent_raw
        == build_independent_review(
            context, assignment, assignment_raw, result, result_raw
        ).encode(),
        "R004 independent review differs",
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "action",
        choices=("--check-assignment", "--check-review-result", "--check-post-review"),
    )
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        context = prepare_review_context(args.root)
        assignment, assignment_raw = _document(args.root, REVIEW_ASSIGNMENT_REL)
        validate_assignment(assignment, assignment_raw, context)
        if args.action != "--check-assignment":
            result, result_raw = _document(args.root, REVIEW_RESULT_REL)
            validate_review_result(
                result, result_raw, assignment, assignment_raw, context
            )
            if args.action == "--check-post-review":
                validate_followup_authority(args.root)
    except (OSError, ValueError, ReviewError, trace.BuildError) as exc:
        print(f"NPC R004 follow-up review: FAIL: {exc}")
        return 1
    print("NPC R004 follow-up review: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
