#!/usr/bin/env python3
"""Build and validate the seq63-65 Workstream aggregate review."""

from __future__ import annotations

import argparse
import copy
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import build_walksafe_npc_single_admin_recovery_r011_followup_review_20260813 as r011


RESULT_DIR = Path(
    "docs/control/execution/workstream-transitions/seq63-65/review-rounds/R003"
)
ASSIGNMENT_REL = RESULT_DIR / "review-assignment.json"
RESULT_REL = RESULT_DIR / "review-result.json"
INDEPENDENT_REL = RESULT_DIR / "independent-review.json"
ROUND_ID = "WS-WORKSTREAM-AGGREGATE-SEQ63-65-REVIEW-20260813-R003"
SUPERSEDED_ASSIGNMENTS = (
    {
        "path": (
            "docs/control/execution/workstream-transitions/seq63-65/"
            "review-rounds/R001/review-assignment.json"
        ),
        "sha256": "d955c7d183e6500ed3c4b01835058bc47edf54d67342444d26838f6950897822",
        "byte_length": 17_015,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "must_remain_absent": [
            "docs/control/execution/workstream-transitions/seq63-65/"
            "review-rounds/R001/review-result.json",
            "docs/control/execution/workstream-transitions/seq63-65/"
            "review-rounds/R001/independent-review.json",
        ],
    },
    {
        "path": (
            "docs/control/execution/workstream-transitions/seq63-65/"
            "review-rounds/R002/review-assignment.json"
        ),
        "sha256": "5a704a40db048d1b22814795c25fd3b03b35d3e15cfb406d32ecd28e08b8987f",
        "byte_length": 18_321,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "must_remain_absent": [
            "docs/control/execution/workstream-transitions/seq63-65/"
            "review-rounds/R002/review-result.json",
            "docs/control/execution/workstream-transitions/seq63-65/"
            "review-rounds/R002/independent-review.json",
        ],
    },
)
SOURCE_CHECKPOINT = {
    "path": "docs/control/walksafe-project-continuation-checkpoint.json",
    "sequence": 62,
    "sha256": "250d94d68329e1ce30083697726e2312ba57140eeb33a58c0b7501c4594fd408",
    "byte_length": 1_842_320,
}
R011_PINS = {
    r011.REVIEW_ASSIGNMENT_REL: (
        "b158738d372d665cd73c944f4c1633bb2d8c3d80d51fb000f295704c49a52f69",
        11_683,
    ),
    r011.REVIEW_RESULT_REL: (
        "3a417664a6a81be049bfba7a1db71d2b3db0a965d3490ad861286ebb042b9a09",
        15_428,
    ),
    r011.INDEPENDENT_REVIEW_REL: (
        "455b010ce8f3f8d5e760a697471026faef1603e991a1b54221fde29ac912a2a3",
        15_708,
    ),
}
CONTROL_PATHS = (
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("tests/test_repository_catalogs.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    Path("scripts/apply_walksafe_workstream_aggregate_seq63_65_20260813.py"),
    Path("tests/test_apply_walksafe_workstream_aggregate_seq63_65_20260813.py"),
    Path("scripts/build_walksafe_workstream_aggregate_review_20260813.py"),
    Path("tests/test_build_walksafe_workstream_aggregate_review_20260813.py"),
    Path("scripts/apply_walksafe_fp048_goal_completed_seq43_44_20260802.py"),
    Path("tests/test_walksafe_fp048_goal_completed_seq43_44_20260802.py"),
)
FINDING_IDS = (
    "WS-AGG-R001-HISTORICAL-NPC-CLOSURE-P1-001",
    "WS-AGG-R001-FULL-EVIDENCE-P1-002",
    "WS-AGG-R001-EPIC04-DEPENDENCY-P1-003",
    "WS-AGG-R001-ATOMIC-PUBLICATION-P1-004",
    "WS-AGG-R002-FINAL-GUARD-P1-001",
    "WS-AGG-R002-SUPERSEDED-PROVENANCE-P1-002",
    "WS-AGG-R002-ATOMIC-TEST-EXPECTATION-P1-003",
)
BOUNDARY = {
    "formal_test_credit_added": 0,
    "actual_device_credit_added": 0,
    "external_review_credit_added": 0,
    "deployment_credit_added": 0,
    "release_credit_added": 0,
    "release_status": "NOT_ELIGIBLE",
}
ReviewError = r011.ReviewError
require = r011.require
trace = r011.trace


@dataclass(frozen=True)
class ReviewContext:
    root: Path
    frozen_r011: r011.ReviewContext
    control_code_cohort: tuple[dict[str, Any], ...]
    control_code_cohort_sha256: str
    r011_control_transitions: tuple[dict[str, Any], ...]
    unchanged_r011_controls: tuple[dict[str, Any], ...]
    added_control_bindings: tuple[dict[str, Any], ...]


def _raw(root: Path, relative: Path) -> bytes:
    return r011._raw(root, relative)


def _document(root: Path, relative: Path) -> tuple[dict[str, Any], bytes]:
    return r011._document(root, relative)


def _binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": trace.bytes_sha256(raw),
        "byte_length": len(raw),
    }


def _require_superseded_outputs_absent(
    root: Path, superseded: Sequence[Mapping[str, Any]] = SUPERSEDED_ASSIGNMENTS
) -> None:
    for row in superseded:
        for path_value in row["must_remain_absent"]:
            path = root / path_value
            require(
                not path.exists() and not path.is_symlink(),
                f"superseded review output unexpectedly exists: {path_value}",
            )


def prepare_frozen_r011_context(root: Path = ROOT) -> r011.ReviewContext:
    """Replay R011 from its assignment-frozen cohort after later changes."""

    root = root.resolve(strict=True)
    r011.prepare_frozen_r010_context(root)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative, (digest, byte_length) in R011_PINS.items():
        document, raw = _document(root, relative)
        require(
            len(raw) == byte_length and trace.bytes_sha256(raw) == digest,
            f"R011 frozen binding differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw
    assignment = documents[r011.REVIEW_ASSIGNMENT_REL]
    result = documents[r011.REVIEW_RESULT_REL]
    scope = assignment.get("review_scope")
    require(type(scope) is dict, "R011 frozen scope is missing")
    context = r011.ReviewContext(
        predecessor_bindings=tuple(scope["predecessor_review_bindings"]),
        superseded_assignment_bindings=tuple(scope["superseded_assignments"]),
        control_code_cohort=tuple(scope["reviewed_control_code_cohort"]),
        control_code_cohort_sha256=scope["reviewed_control_code_cohort_sha256"],
    )
    for row in (*context.predecessor_bindings, *context.superseded_assignment_bindings):
        relative = Path(row["path"])
        raw = _raw(root, relative)
        require(
            trace.bytes_sha256(raw) == row["sha256"]
            and len(raw) == row["byte_length"],
            f"R011 frozen scope binding differs: {relative}",
        )
    r011.validate_review_result(
        result,
        raw_by_path[r011.REVIEW_RESULT_REL],
        assignment,
        raw_by_path[r011.REVIEW_ASSIGNMENT_REL],
        context,
    )
    require(
        r011.build_independent_review(
            context,
            assignment,
            raw_by_path[r011.REVIEW_ASSIGNMENT_REL],
            result,
            raw_by_path[r011.REVIEW_RESULT_REL],
        ).encode("utf-8")
        == raw_by_path[r011.INDEPENDENT_REVIEW_REL],
        "R011 frozen independent review differs",
    )
    return context


def prepare_review_context(
    root: Path = ROOT, *, require_exact_source: bool = False
) -> ReviewContext:
    root = root.resolve(strict=True)
    frozen = prepare_frozen_r011_context(root)
    controls = tuple(_binding(path, _raw(root, path)) for path in CONTROL_PATHS)
    old_by_path = {row["path"]: dict(row) for row in frozen.control_code_cohort}
    require(
        len(old_by_path) == len(frozen.control_code_cohort) == 23,
        "R011 frozen control inventory differs",
    )
    current_by_path = {row["path"]: row for row in controls}
    transitions: list[dict[str, Any]] = []
    unchanged: list[dict[str, Any]] = []
    for path, before in old_by_path.items():
        after = _binding(Path(path), _raw(root, Path(path)))
        if after == before:
            unchanged.append(after)
        else:
            transitions.append(
                {
                    "path": path,
                    "before_sha256": before["sha256"],
                    "before_byte_length": before["byte_length"],
                    "after_sha256": after["sha256"],
                    "after_byte_length": after["byte_length"],
                    "change_kind": "MODIFIED",
                }
            )
        if path in current_by_path:
            require(current_by_path[path] == after, f"current control differs: {path}")
    added = tuple(sorted(set(current_by_path) - set(old_by_path)))
    require(
        set(added)
        == {
            "scripts/apply_walksafe_workstream_aggregate_seq63_65_20260813.py",
            "tests/test_apply_walksafe_workstream_aggregate_seq63_65_20260813.py",
            "scripts/build_walksafe_workstream_aggregate_review_20260813.py",
            "tests/test_build_walksafe_workstream_aggregate_review_20260813.py",
            "scripts/apply_walksafe_fp048_goal_completed_seq43_44_20260802.py",
            "tests/test_walksafe_fp048_goal_completed_seq43_44_20260802.py",
        },
        "aggregate added control inventory differs",
    )
    reviewed_old_paths = set(current_by_path) & set(old_by_path)
    require(
        {row["path"] for row in transitions} == reviewed_old_paths,
        "unexpected R011 control changed outside the aggregate review cohort",
    )
    checkpoint_raw = _raw(root, Path(SOURCE_CHECKPOINT["path"]))
    for superseded in SUPERSEDED_ASSIGNMENTS:
        superseded_raw = _raw(root, Path(superseded["path"]))
        require(
            len(superseded_raw) == superseded["byte_length"]
            and trace.bytes_sha256(superseded_raw) == superseded["sha256"],
            f"superseded assignment differs: {superseded['path']}",
        )
    _require_superseded_outputs_absent(root)
    if require_exact_source:
        require(
            len(checkpoint_raw) == SOURCE_CHECKPOINT["byte_length"]
            and trace.bytes_sha256(checkpoint_raw) == SOURCE_CHECKPOINT["sha256"],
            "aggregate source checkpoint differs",
        )
    return ReviewContext(
        root=root,
        frozen_r011=frozen,
        control_code_cohort=controls,
        control_code_cohort_sha256=trace.object_sha256(list(controls)),
        r011_control_transitions=tuple(transitions),
        unchanged_r011_controls=tuple(unchanged),
        added_control_bindings=tuple(current_by_path[path] for path in added),
    )


def _scope(context: ReviewContext) -> dict[str, Any]:
    return {
        "source_checkpoint": SOURCE_CHECKPOINT,
        "superseded_review_assignments": copy.deepcopy(
            list(SUPERSEDED_ASSIGNMENTS)
        ),
        "predecessor_review_bindings": [
            _binding(path, _raw(context.root, path)) for path in R011_PINS
        ],
        "frozen_r011_control_code_cohort": copy.deepcopy(
            list(context.frozen_r011.control_code_cohort)
        ),
        "r011_control_transitions": copy.deepcopy(
            list(context.r011_control_transitions)
        ),
        "unchanged_r011_controls": copy.deepcopy(
            list(context.unchanged_r011_controls)
        ),
        "added_control_bindings": copy.deepcopy(
            list(context.added_control_bindings)
        ),
        "reviewed_control_code_cohort": copy.deepcopy(
            list(context.control_code_cohort)
        ),
        "reviewed_control_code_cohort_sha256": context.control_code_cohort_sha256,
        "required_finding_ids": list(FINDING_IDS),
        "acceptance": {
            "frozen_r011_review_replays_without_current_control_bytes": True,
            "r011_changed_controls_are_exactly_superseded": True,
            "epic02_and_epic03_use_full_existing_completion_evidence": True,
            "epic04_ready_depends_only_on_epic02_completion_event": True,
            "projected_seq65_passes_continuation_and_goal_graph": True,
            "atomic_checkpoint_publication_is_fail_closed": True,
            "checker_time_drift_is_revalidated_before_pass_or_commit": True,
            "rollback_failure_is_postcommit_uncertain": True,
        },
    }


def _identity(value: Any, role: str) -> dict[str, str]:
    require(
        type(value) is dict
        and set(value) == {"role", "agent_instance_id", "canonical_task"}
        and value.get("role") == role
        and all(type(value.get(key)) is str and value.get(key) for key in value),
        f"{role} identity differs",
    )
    return dict(value)


def validate_assignment(
    assignment: Mapping[str, Any], raw: bytes, context: ReviewContext
) -> None:
    require(raw == trace.json_text(assignment).encode(), "assignment is noncanonical")
    require(
        set(assignment)
        == {
            "schema_version", "evidence_type", "round_id", "assigned_at",
            "assigner", "executor", "reviewer", "review_scope", "review_boundary",
        }
        and assignment.get("schema_version") == "1.0"
        and assignment.get("evidence_type") == "WORKSTREAM_AGGREGATE_REVIEW_ASSIGNMENT"
        and assignment.get("round_id") == ROUND_ID,
        "assignment identity differs",
    )
    trace._parse_time(assignment.get("assigned_at"), "assigned_at")
    assigner = _identity(assignment.get("assigner"), "INTERNAL_REVIEW_ASSIGNER")
    executor = _identity(assignment.get("executor"), "INTERNAL_IMPLEMENTATION_EXECUTOR")
    reviewer = _identity(assignment.get("reviewer"), "SEPARATE_INTERNAL_REVIEWER")
    require(
        reviewer["agent_instance_id"] not in {
            assigner["agent_instance_id"], executor["agent_instance_id"]
        }
        and reviewer["canonical_task"] not in {
            assigner["canonical_task"], executor["canonical_task"]
        },
        "reviewer is not separate",
    )
    require(assignment.get("review_scope") == _scope(context), "assignment scope differs")
    require(assignment.get("review_boundary") == BOUNDARY, "assignment boundary differs")


def validate_review_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
) -> None:
    validate_assignment(assignment, assignment_raw, context)
    require(raw == trace.json_text(result).encode(), "review result is noncanonical")
    require(
        set(result)
        == {
            "schema_version", "evidence_type", "round_id", "reviewed_at",
            "reviewer", "assignment_binding", "review_scope", "decision",
            "findings", "finding_dispositions", "review_boundary",
        }
        and result.get("schema_version") == "1.0"
        and result.get("evidence_type") == "WORKSTREAM_AGGREGATE_REVIEW_RESULT"
        and result.get("round_id") == ROUND_ID,
        "review result identity differs",
    )
    require(result.get("reviewer") == assignment.get("reviewer"), "reviewer binding differs")
    require(
        result.get("assignment_binding") == _binding(ASSIGNMENT_REL, assignment_raw),
        "assignment binding differs",
    )
    require(result.get("review_scope") == _scope(context), "review result scope differs")
    require(
        result.get("decision") == "APPROVED"
        and result.get("findings") == {
            "blocking": [], "major_open": [], "minor_open": []
        },
        "review is not finding-free approved",
    )
    dispositions = result.get("finding_dispositions")
    require(
        isinstance(dispositions, list)
        and [row.get("finding_id") for row in dispositions] == list(FINDING_IDS)
        and all(
            isinstance(row, dict)
            and set(row)
            == {"finding_id", "disposition", "rationale", "retest_commands"}
            and row.get("disposition") == "CLOSED"
            and isinstance(row.get("rationale"), str)
            and row["rationale"]
            and isinstance(row.get("retest_commands"), list)
            and row["retest_commands"]
            and all(isinstance(command, str) and command for command in row["retest_commands"])
            for row in dispositions
        ),
        "finding disposition differs",
    )
    require(result.get("review_boundary") == BOUNDARY, "review result boundary differs")
    require(
        trace._parse_time(result.get("reviewed_at"), "reviewed_at")
        >= trace._parse_time(assignment.get("assigned_at"), "assigned_at"),
        "review predates assignment",
    )


def build_independent_review(
    result: Mapping[str, Any], result_raw: bytes, assignment: Mapping[str, Any], assignment_raw: bytes
) -> str:
    document = {
        "schema_version": "1.0",
        "evidence_type": "WORKSTREAM_AGGREGATE_INDEPENDENT_REVIEW",
        "round_id": ROUND_ID,
        "status": "PASS",
        "decision": result["decision"],
        "reviewed_at": result["reviewed_at"],
        "reviewer": result["reviewer"],
        "assignment_provenance": _binding(ASSIGNMENT_REL, assignment_raw),
        "review_result_provenance": _binding(RESULT_REL, result_raw),
        "review_scope": result["review_scope"],
        "findings": result["findings"],
        "finding_dispositions": result["finding_dispositions"],
        "review_boundary": result["review_boundary"],
    }
    return trace.json_text(document)


def validate_post_review(root: Path = ROOT) -> ReviewContext:
    context = prepare_review_context(root)
    assignment, assignment_raw = _document(root, ASSIGNMENT_REL)
    result, result_raw = _document(root, RESULT_REL)
    validate_review_result(result, result_raw, assignment, assignment_raw, context)
    require(
        _raw(root, INDEPENDENT_REL)
        == build_independent_review(result, result_raw, assignment, assignment_raw).encode(),
        "independent review differs",
    )
    return context


def transition_review_binding(root: Path = ROOT) -> dict[str, dict[str, Any]]:
    validate_post_review(root)
    return {
        "assignment": _binding(ASSIGNMENT_REL, _raw(root, ASSIGNMENT_REL)),
        "review_result": _binding(RESULT_REL, _raw(root, RESULT_REL)),
        "independent_review": _binding(
            INDEPENDENT_REL, _raw(root, INDEPENDENT_REL)
        ),
    }


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _write_add_only(root: Path, relative: Path, text: str) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("x", encoding="utf-8", newline="") as handle:
        handle.write(text)


def write_assignment(root: Path) -> None:
    context = prepare_review_context(root, require_exact_source=True)
    assignment = {
        "schema_version": "1.0",
        "evidence_type": "WORKSTREAM_AGGREGATE_REVIEW_ASSIGNMENT",
        "round_id": ROUND_ID,
        "assigned_at": _now(),
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": "codex-root-workstream-aggregate-assigner-20260813",
            "canonical_task": "/root",
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": "codex-root-workstream-aggregate-executor-20260813",
            "canonical_task": "/root",
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": "codex-workstream-aggregate-reviewer-20260813",
            "canonical_task": "/root/workstream_aggregate_reviewer",
        },
        "review_scope": _scope(context),
        "review_boundary": BOUNDARY,
    }
    text = trace.json_text(assignment)
    validate_assignment(assignment, text.encode(), context)
    _write_add_only(root, ASSIGNMENT_REL, text)


def write_independent(root: Path) -> None:
    context = prepare_review_context(root)
    assignment, assignment_raw = _document(root, ASSIGNMENT_REL)
    result, result_raw = _document(root, RESULT_REL)
    validate_review_result(result, result_raw, assignment, assignment_raw, context)
    _write_add_only(
        root,
        INDEPENDENT_REL,
        build_independent_review(result, result_raw, assignment, assignment_raw),
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write-assignment", action="store_true")
    mode.add_argument("--check-assignment", action="store_true")
    mode.add_argument("--check-review-result", action="store_true")
    mode.add_argument("--write-independent", action="store_true")
    mode.add_argument("--check-post-review", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    root = args.root.resolve(strict=True)
    try:
        if args.write_assignment:
            write_assignment(root)
        elif args.check_assignment:
            context = prepare_review_context(root)
            assignment, raw = _document(root, ASSIGNMENT_REL)
            validate_assignment(assignment, raw, context)
        elif args.check_review_result:
            context = prepare_review_context(root)
            assignment, assignment_raw = _document(root, ASSIGNMENT_REL)
            result, result_raw = _document(root, RESULT_REL)
            validate_review_result(result, result_raw, assignment, assignment_raw, context)
        elif args.write_independent:
            write_independent(root)
        else:
            validate_post_review(root)
    except (OSError, ValueError, TypeError, ReviewError, trace.BuildError) as exc:
        print(f"Workstream aggregate review: FAIL: {exc}")
        return 1
    print("Workstream aggregate review: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
