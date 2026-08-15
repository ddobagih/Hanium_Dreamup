#!/usr/bin/env python3
"""Validate the final R010 completion-control review."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813 as r004
from scripts import build_walksafe_npc_single_admin_recovery_r005_followup_review_20260813 as r005
from scripts import build_walksafe_npc_single_admin_recovery_r006_followup_review_20260813 as r006
from scripts import build_walksafe_npc_single_admin_recovery_r007_followup_review_20260813 as r007
from scripts import build_walksafe_npc_single_admin_recovery_r009_followup_review_20260813 as r009


trace = r009.trace
ROOT = Path(__file__).resolve().parents[1]
ROUND_ID = "WS-NPC-SINGLE-ADMIN-RECOVERY-REVIEW-20260813-R010"
REVIEW_DIR_REL = trace.RESULT_DIR_REL / "review-rounds" / "R010"
REVIEW_ASSIGNMENT_REL = REVIEW_DIR_REL / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_DIR_REL / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_DIR_REL / "independent-review.json"
REVIEW_PATHS = (REVIEW_ASSIGNMENT_REL, REVIEW_RESULT_REL, INDEPENDENT_REVIEW_REL)
FINDING_IDS = (
    "NPC-R010-ATOMIC-STAGE-UNIVERSE-P1-001",
    "NPC-R010-ATOMIC-STAGE-TAMPER-P1-002",
    "NPC-R010-FROZEN-R009-P1-003",
)
R009_PINS = {
    r009.REVIEW_ASSIGNMENT_REL: (
        "37b3c8788300f45c24472f25a7a9a37bcc7cef4463069e34eefbd522db2e011e",
        8_806,
    ),
    r009.REVIEW_RESULT_REL: (
        "08fa1b96d4bb1e447b940a00a213f1224d83c473e8637aeafe9e88745b059f2a",
        11_929,
    ),
    r009.INDEPENDENT_REVIEW_REL: (
        "058cb857efd078f38b6b84c6746ddb299acd8c866f139f78482e1efeeade10f6",
        12_209,
    ),
}
PREDECESSOR_PINS = {**r009.R008_PINS, **R009_PINS}
CONTROL_PATHS = (
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py"),
    Path("tests/test_apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("tests/test_repository_catalogs.py"),
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
    Path("scripts/build_walksafe_npc_single_admin_recovery_r009_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r009_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r010_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r010_followup_review_20260813.py"),
)
BOUNDARY = r009.BOUNDARY
ReviewError = r009.ReviewError
require = r009.require
_raw = r009._raw
_document = r009._document
_binding = r009._binding
_identity = r009._identity
prepare_frozen_r003_context = r004.prepare_frozen_r003_context


@dataclass(frozen=True)
class ReviewContext:
    predecessor_bindings: tuple[dict[str, Any], ...]
    superseded_assignment_bindings: tuple[dict[str, Any], ...]
    control_code_cohort: tuple[dict[str, Any], ...]
    control_code_cohort_sha256: str


def prepare_frozen_r009_context(root: Path = ROOT) -> r009.ReviewContext:
    """Replay approved R009 against its assignment-frozen control cohort."""
    root = root.resolve(strict=True)
    r009.prepare_frozen_r008_context(root)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for path, (digest, byte_length) in R009_PINS.items():
        document, raw = _document(root, path)
        require(
            len(raw) == byte_length and trace.bytes_sha256(raw) == digest,
            f"R009 predecessor binding differs: {path}",
        )
        documents[path] = document
        raw_by_path[path] = raw
    assignment = documents[r009.REVIEW_ASSIGNMENT_REL]
    result = documents[r009.REVIEW_RESULT_REL]
    scope = assignment.get("review_scope")
    require(type(scope) is dict, "R009 frozen review scope is missing")
    context = r009.ReviewContext(
        predecessor_bindings=tuple(scope["predecessor_review_bindings"]),
        superseded_assignment_bindings=tuple(scope["superseded_assignments"]),
        control_code_cohort=tuple(scope["reviewed_control_code_cohort"]),
        control_code_cohort_sha256=scope[
            "reviewed_control_code_cohort_sha256"
        ],
    )
    for row in (
        *context.predecessor_bindings,
        *context.superseded_assignment_bindings,
    ):
        path = Path(row["path"])
        raw = _raw(root, path)
        require(
            trace.bytes_sha256(raw) == row["sha256"]
            and len(raw) == row["byte_length"],
            f"R009 frozen scope binding differs: {path}",
        )
    r009.validate_review_result(
        result,
        raw_by_path[r009.REVIEW_RESULT_REL],
        assignment,
        raw_by_path[r009.REVIEW_ASSIGNMENT_REL],
        context,
    )
    require(
        r009.build_independent_review(
            context,
            assignment,
            raw_by_path[r009.REVIEW_ASSIGNMENT_REL],
            result,
            raw_by_path[r009.REVIEW_RESULT_REL],
        ).encode()
        == raw_by_path[r009.INDEPENDENT_REVIEW_REL],
        "R009 frozen independent review differs",
    )
    return context


def prepare_review_context(root: Path = ROOT) -> ReviewContext:
    root = root.resolve(strict=True)
    frozen_r009 = prepare_frozen_r009_context(root)
    predecessor = tuple(
        _binding(path, _raw(root, path)) for path in PREDECESSOR_PINS
    )
    superseded = tuple(
        dict(row) for row in frozen_r009.superseded_assignment_bindings
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
        superseded_assignment_bindings=superseded,
        control_code_cohort=controls,
        control_code_cohort_sha256=trace.object_sha256(list(controls)),
    )


def _scope(context: ReviewContext) -> dict[str, Any]:
    return {
        "trigger": {
            "checkpoint_sequence": 60,
            "failed_command": "apply seq61/62 --write",
            "diagnostic": (
                "atomic checkpoint staging file changes the catalog source universe"
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
            "exact_transport_stage_may_be_excluded_only_during_commit": True,
            "transport_stage_name_owner_mode_link_and_bytes_must_match": True,
            "additional_or_tampered_stage_must_fail_closed": True,
            "current_test_layer_registry_must_pass": True,
            "frozen_r009_review_must_replay_after_control_changes": True,
            "r010_controls_must_supersede_r003_in_final_managed_closure": True,
            "projected_seq62_goal_graph_pass_required": True,
            "catalog_refresh_and_preflight_must_pass": True,
        },
    }


def validate_assignment(
    assignment: Mapping[str, Any], raw: bytes, context: ReviewContext
) -> None:
    require(raw == trace.json_text(assignment).encode(), "R010 assignment is noncanonical")
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
        == "NPC_R010_FOLLOWUP_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == trace.GOAL_ID
        and assignment.get("round_id") == ROUND_ID,
        "R010 assignment identity differs",
    )
    trace._parse_time(assignment.get("assigned_at"), "R010 assigned_at")
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
        "R010 reviewer is not separate",
    )
    require(assignment.get("review_scope") == _scope(context), "R010 scope differs")
    require(assignment.get("review_boundary") == BOUNDARY, "R010 boundary differs")


def validate_review_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
) -> None:
    validate_assignment(assignment, assignment_raw, context)
    require(raw == trace.json_text(result).encode(), "R010 result is noncanonical")
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
        == "NPC_R010_REVIEWER_AUTHORED_RESULT"
        and result.get("goal_id") == trace.GOAL_ID
        and result.get("round_id") == ROUND_ID,
        "R010 result identity differs",
    )
    require(
        result.get("assignment_binding")
        == {
            "path": REVIEW_ASSIGNMENT_REL.as_posix(),
            "sha256": trace.bytes_sha256(assignment_raw),
        }
        and result.get("reviewer") == assignment.get("reviewer"),
        "R010 assignment or reviewer binding differs",
    )
    require(
        trace._parse_time(result.get("reviewed_at"), "R010 reviewed_at")
        >= trace._parse_time(assignment.get("assigned_at"), "R010 assigned_at"),
        "R010 review predates assignment",
    )
    require(result.get("review_scope") == _scope(context), "R010 result scope differs")
    require(
        result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []},
        "R010 review is not finding-free approved",
    )
    dispositions = result.get("finding_dispositions")
    require(
        type(dispositions) is list
        and [row.get("finding_id") for row in dispositions] == list(FINDING_IDS),
        "R010 finding disposition inventory differs",
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
            "R010 finding disposition differs",
        )
    require(result.get("review_boundary") == BOUNDARY, "R010 result boundary differs")


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
            "evidence_type": "NPC_R010_INDEPENDENT_INTERNAL_REVIEW",
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
        "R010 independent review differs",
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
        print(f"NPC R010 follow-up review: FAIL: {exc}")
        return 1
    print("NPC R010 follow-up review: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
