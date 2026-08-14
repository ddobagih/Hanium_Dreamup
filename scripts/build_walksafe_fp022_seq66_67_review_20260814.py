#!/usr/bin/env python3
"""Build and validate the independent FP-022 seq66/67 transition review."""

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

from scripts import apply_walksafe_fp022_goal_seq66_67_20260813 as fp022
from scripts import build_walksafe_workstream_aggregate_review_20260813 as aggregate_review


trace = aggregate_review.trace
require = aggregate_review.require
ReviewError = aggregate_review.ReviewError

REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq66-67/review-rounds"
)
R001_DIR = REVIEW_ROOT / "R001"
R001_ASSIGNMENT_REL = R001_DIR / "review-assignment.json"
R001_RESULT_REL = R001_DIR / "review-result.json"
R001_INDEPENDENT_REL = R001_DIR / "independent-review.json"
SUPERSEDED_ASSIGNMENTS = (
    {
        "path": R001_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "65c7dd40c37293ef0331d2585f939665473c82f8fcbe84c6c800ff9154880bf0"
        ),
        "byte_length": 7_295,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "AGGREGATE_R003_CONTROL_SUCCESSOR_WAS_OMITTED",
    },
)
RESULT_DIR = REVIEW_ROOT / "R002"
ASSIGNMENT_REL = RESULT_DIR / "review-assignment.json"
RESULT_REL = RESULT_DIR / "review-result.json"
INDEPENDENT_REL = RESULT_DIR / "independent-review.json"
ROUND_ID = "WS-FP022-SEQ66-67-REVIEW-20260814-R002"
R002_REVIEW_PINS = {
    ASSIGNMENT_REL: (
        "106f22a96ee9cf976b74e2b33cf92b9f14fd9009e38e5463e8fc462bd3b0e523",
        7_889,
    ),
    RESULT_REL: (
        "734801f30e1d977806e5ec19751117b4d3c13a2467f8b3d5caea21a42e40246b",
        7_951,
    ),
    INDEPENDENT_REL: (
        "0a882fb03cb9918cdb153d9df5ce1c148bfbb9fd4442325d82226b9bf4b4f101",
        8_228,
    ),
}

SOURCE_CHECKPOINT = {
    "path": "docs/control/walksafe-project-continuation-checkpoint.json",
    "sequence": 65,
    "sha256": "25ff4d3f630ccbaffe1aa9e4eb5a1c922050d1705171bbaa162e6dad0d692644",
    "byte_length": 1_922_305,
    "tail_event_sha256": (
        "c8eb9b6b90f546af6960fa929c93b1c97b6115d690938c09a33dd9a4155f0a49"
    ),
}
AGGREGATE_REVIEW_PINS = {
    aggregate_review.ASSIGNMENT_REL: (
        "b5f5b512286ad70b9a0833126dadd71bb5a11fc0244d22acc27b82c289626b09",
        19_307,
    ),
    aggregate_review.RESULT_REL: (
        "92666a5536e9b1abce5b9f141d5438328b2859c321e9c681906207bf14876b81",
        25_434,
    ),
    aggregate_review.INDEPENDENT_REL: (
        "c4c54175acf47b8823d9d22fa5833b423a42ea3d6726c2133c45e8725c700e4e",
        25_714,
    ),
}

GOAL_EVIDENCE = {
    "goal_id": fp022.GOAL_ID,
    "path": fp022.GOAL_PATH.as_posix(),
    "sha256": fp022.GOAL_SHA256,
    "byte_length": fp022.GOAL_BYTE_COUNT,
}
CONTRACT_EVIDENCE = {
    "document_id": fp022.CONTRACT_DOCUMENT_ID,
    "contract_id": fp022.CONTRACT_ID,
    "contract_version": fp022.CONTRACT_VERSION,
    "path": fp022.CONTRACT_PATH.as_posix(),
    "file_sha256": fp022.CONTRACT_FILE_SHA256,
    "byte_length": fp022.CONTRACT_BYTE_COUNT,
    "canonical_contract_sha256": fp022.CONTRACT_CANONICAL_SHA256,
    "ordered_check_ids": list(fp022.CONTRACT_CHECK_IDS),
}
PROJECTED_TRANSITION = {
    "materialized": {
        "sequence": 66,
        "event_id": fp022.MATERIALIZED_EVENT_ID,
        "to_status": "PLANNED",
    },
    "ready": {
        "sequence": 67,
        "event_id": fp022.READY_EVENT_ID,
        "to_status": "READY",
    },
    "final_focus_goal_id": fp022.GOAL_ID,
    "final_ready_frontier_goal_ids": fp022.READY_FRONTIER,
}

SCRIPT_REL = Path("scripts/build_walksafe_fp022_seq66_67_review_20260814.py")
TEST_REL = Path("tests/test_build_walksafe_fp022_seq66_67_review_20260814.py")
CONTROL_PATHS = (
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("tests/test_repository_catalogs.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    Path("tests/test_apply_walksafe_workstream_aggregate_seq63_65_20260813.py"),
    fp022.SCRIPT_REL,
    fp022.TEST_REL,
    Path("scripts/run_walksafe_fp022_goal_start_gate_20260813.py"),
    Path("tests/test_walksafe_fp022_goal_start_gate_20260813.py"),
    SCRIPT_REL,
    TEST_REL,
)
FINDING_IDS: tuple[str, ...] = ()
BOUNDARY = {
    "formal_test_credit_added": 0,
    "actual_device_credit_added": 0,
    "external_review_credit_added": 0,
    "deployment_credit_added": 0,
    "release_credit_added": 0,
    "release_status": "NOT_ELIGIBLE",
}


@dataclass(frozen=True)
class ReviewContext:
    root: Path
    predecessor_review_bindings: tuple[dict[str, Any], ...]
    superseded_assignment_bindings: tuple[dict[str, Any], ...]
    goal_evidence: dict[str, Any]
    contract_evidence: dict[str, Any]
    control_code_cohort: tuple[dict[str, Any], ...]
    control_code_cohort_sha256: str


def _raw(root: Path, relative: Path) -> bytes:
    return aggregate_review._raw(root, relative)


def _document(root: Path, relative: Path) -> tuple[dict[str, Any], bytes]:
    return aggregate_review._document(root, relative)


def _binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": trace.bytes_sha256(raw),
        "byte_length": len(raw),
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


def prepare_frozen_aggregate_review(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    """Replay approved aggregate R003 from its assignment-frozen cohort."""

    root = root.resolve(strict=True)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative, (digest, byte_length) in AGGREGATE_REVIEW_PINS.items():
        document, raw = _document(root, relative)
        require(
            len(raw) == byte_length and trace.bytes_sha256(raw) == digest,
            f"aggregate R003 frozen binding differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[aggregate_review.ASSIGNMENT_REL]
    result = documents[aggregate_review.RESULT_REL]
    scope = assignment.get("review_scope")
    require(type(scope) is dict, "aggregate R003 frozen scope is missing")
    frozen_r011 = aggregate_review.prepare_frozen_r011_context(root)
    frozen_context = aggregate_review.ReviewContext(
        root=root,
        frozen_r011=frozen_r011,
        control_code_cohort=tuple(scope["reviewed_control_code_cohort"]),
        control_code_cohort_sha256=scope["reviewed_control_code_cohort_sha256"],
        r011_control_transitions=tuple(scope["r011_control_transitions"]),
        unchanged_r011_controls=tuple(scope["unchanged_r011_controls"]),
        added_control_bindings=tuple(scope["added_control_bindings"]),
    )
    aggregate_review._require_superseded_outputs_absent(root)
    aggregate_review.validate_review_result(
        result,
        raw_by_path[aggregate_review.RESULT_REL],
        assignment,
        raw_by_path[aggregate_review.ASSIGNMENT_REL],
        frozen_context,
    )
    require(
        aggregate_review.build_independent_review(
            result,
            raw_by_path[aggregate_review.RESULT_REL],
            assignment,
            raw_by_path[aggregate_review.ASSIGNMENT_REL],
        ).encode("utf-8")
        == raw_by_path[aggregate_review.INDEPENDENT_REL],
        "aggregate R003 frozen independent review differs",
    )
    return tuple(
        _binding(relative, raw_by_path[relative])
        for relative in AGGREGATE_REVIEW_PINS
    )


def _require_exact_source(root: Path) -> None:
    relative = Path(SOURCE_CHECKPOINT["path"])
    raw = _raw(root, relative)
    require(
        len(raw) == SOURCE_CHECKPOINT["byte_length"]
        and trace.bytes_sha256(raw) == SOURCE_CHECKPOINT["sha256"],
        "FP-022 review source checkpoint differs from exact seq65",
    )
    checkpoint = trace.strict_json_bytes(raw, relative.as_posix())
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(
        type(history) is list
        and len(history) == SOURCE_CHECKPOINT["sequence"]
        and type(history[-1]) is dict
        and history[-1].get("sequence") == SOURCE_CHECKPOINT["sequence"]
        and history[-1].get("event_sha256")
        == SOURCE_CHECKPOINT["tail_event_sha256"]
        and state.get("transition_history_anchor_sha256")
        == SOURCE_CHECKPOINT["tail_event_sha256"],
        "FP-022 review source seq65 lineage differs",
    )


def _require_goal_and_contract(root: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    goal_raw = _raw(root, fp022.GOAL_PATH)
    require(
        _binding(fp022.GOAL_PATH, goal_raw)
        == {
            "path": GOAL_EVIDENCE["path"],
            "sha256": GOAL_EVIDENCE["sha256"],
            "byte_length": GOAL_EVIDENCE["byte_length"],
        },
        "FP-022 Goal evidence differs",
    )
    goal_node, _ = fp022.graph.frozen_goal.parse_goal(root / fp022.GOAL_PATH)
    require(
        goal_node.get("goal_id") == fp022.GOAL_ID
        and goal_node.get("parent_goal_id") == fp022.PARENT_GOAL_ID
        and goal_node.get("initial_status") == "PLANNED"
        and goal_node.get("start_requires") == [fp022.READY_DEPENDENCY_ID],
        "FP-022 Goal authority differs",
    )

    contract_raw = _raw(root, fp022.CONTRACT_PATH)
    require(
        len(contract_raw) == CONTRACT_EVIDENCE["byte_length"]
        and trace.bytes_sha256(contract_raw) == CONTRACT_EVIDENCE["file_sha256"],
        "FP-022 start-gate contract file differs",
    )
    contract = trace.strict_json_bytes(contract_raw, fp022.CONTRACT_PATH.as_posix())
    require(
        trace.object_sha256(contract)
        == CONTRACT_EVIDENCE["canonical_contract_sha256"]
        and contract.get("document_id") == CONTRACT_EVIDENCE["document_id"]
        and contract.get("contract_id") == CONTRACT_EVIDENCE["contract_id"]
        and contract.get("contract_version") == CONTRACT_EVIDENCE["contract_version"]
        and contract.get("target_goal_id") == fp022.GOAL_ID
        and contract.get("target_goal_content_sha256") == fp022.GOAL_SHA256
        and [row.get("check_id") for row in contract.get("ordered_checks", [])]
        == CONTRACT_EVIDENCE["ordered_check_ids"],
        "FP-022 start-gate contract authority differs",
    )
    return copy.deepcopy(GOAL_EVIDENCE), copy.deepcopy(CONTRACT_EVIDENCE)


def _require_projected_transition_constants() -> None:
    require(
        fp022.SOURCE_SEQUENCE == SOURCE_CHECKPOINT["sequence"]
        and fp022.SOURCE_SHA256 == SOURCE_CHECKPOINT["sha256"]
        and fp022.SOURCE_BYTE_COUNT == SOURCE_CHECKPOINT["byte_length"]
        and fp022.SOURCE_TAIL_SHA256 == SOURCE_CHECKPOINT["tail_event_sha256"]
        and fp022.FINAL_SCOPE.endswith("release credit."),
        "FP-022 sealed transition constants differ",
    )


def prepare_review_context(
    root: Path = ROOT, *, require_exact_source: bool = False
) -> ReviewContext:
    root = root.resolve(strict=True)
    predecessor = prepare_frozen_aggregate_review(root)
    superseded = tuple(copy.deepcopy(SUPERSEDED_ASSIGNMENTS))
    for row in superseded:
        relative = Path(row["path"])
        raw = _raw(root, relative)
        require(
            _binding(relative, raw)
            == {
                key: row[key] for key in ("path", "sha256", "byte_length")
            },
            f"superseded FP-022 review assignment differs: {relative}",
        )
    require(
        not (root / R001_RESULT_REL).exists()
        and not (root / R001_INDEPENDENT_REL).exists(),
        "superseded FP-022 R001 produced a review output",
    )
    _require_projected_transition_constants()
    goal, contract = _require_goal_and_contract(root)
    controls = tuple(_binding(path, _raw(root, path)) for path in CONTROL_PATHS)
    require(
        len(controls) == len(CONTROL_PATHS)
        and len({row["path"] for row in controls}) == len(CONTROL_PATHS),
        "FP-022 review control cohort is not exact",
    )
    if require_exact_source:
        _require_exact_source(root)
    return ReviewContext(
        root=root,
        predecessor_review_bindings=predecessor,
        superseded_assignment_bindings=superseded,
        goal_evidence=goal,
        contract_evidence=contract,
        control_code_cohort=controls,
        control_code_cohort_sha256=trace.object_sha256(list(controls)),
    )


def _scope(context: ReviewContext) -> dict[str, Any]:
    return {
        "source_checkpoint": copy.deepcopy(SOURCE_CHECKPOINT),
        "predecessor_review_bindings": copy.deepcopy(
            list(context.predecessor_review_bindings)
        ),
        "goal_evidence": copy.deepcopy(context.goal_evidence),
        "start_gate_contract_evidence": copy.deepcopy(context.contract_evidence),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "superseded_review_assignments": copy.deepcopy(
            list(context.superseded_assignment_bindings)
        ),
        "reviewed_control_code_cohort": copy.deepcopy(
            list(context.control_code_cohort)
        ),
        "reviewed_control_code_cohort_sha256": context.control_code_cohort_sha256,
        "required_finding_ids": list(FINDING_IDS),
        "acceptance": {
            "source_checkpoint_is_exact_seq65": True,
            "goal_and_initial_start_gate_contract_are_exact": True,
            "seq66_materializes_only_fp022_as_planned": True,
            "seq67_makes_only_fp022_ready": True,
            "predecessor_and_ready_dependency_are_distinct_and_exact": True,
            "projected_seq67_passes_continuation_and_goal_graph": True,
            "atomic_checkpoint_publication_is_fail_closed": True,
            "private_start_gate_remains_unrun": True,
            "no_goal_started_or_completion_credit_is_added": True,
            "no_device_external_formal_deployment_or_release_credit_is_added": True,
        },
    }


def validate_assignment(
    assignment: Mapping[str, Any], raw: bytes, context: ReviewContext
) -> None:
    require(raw == trace.json_text(assignment).encode(), "assignment is noncanonical")
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
        == "FP022_SEQ66_67_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == fp022.GOAL_ID
        and assignment.get("round_id") == ROUND_ID,
        "assignment identity differs",
    )
    trace._parse_time(assignment.get("assigned_at"), "assigned_at")
    assigner = _identity(assignment.get("assigner"), "INTERNAL_REVIEW_ASSIGNER")
    executor = _identity(
        assignment.get("executor"), "INTERNAL_IMPLEMENTATION_EXECUTOR"
    )
    reviewer = _identity(
        assignment.get("reviewer"), "SEPARATE_INTERNAL_REVIEWER"
    )
    require(
        reviewer["agent_instance_id"]
        not in {assigner["agent_instance_id"], executor["agent_instance_id"]}
        and reviewer["canonical_task"]
        not in {assigner["canonical_task"], executor["canonical_task"]},
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
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "reviewed_at",
            "reviewer",
            "assignment_binding",
            "review_scope",
            "decision",
            "findings",
            "finding_dispositions",
            "review_boundary",
        }
        and result.get("schema_version") == "1.0"
        and result.get("evidence_type")
        == "FP022_SEQ66_67_REVIEWER_AUTHORED_RESULT"
        and result.get("goal_id") == fp022.GOAL_ID
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
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []},
        "review is not finding-free approved",
    )
    require(
        result.get("finding_dispositions") == list(FINDING_IDS),
        "finding disposition inventory differs",
    )
    require(result.get("review_boundary") == BOUNDARY, "review result boundary differs")
    require(
        trace._parse_time(result.get("reviewed_at"), "reviewed_at")
        >= trace._parse_time(assignment.get("assigned_at"), "assigned_at"),
        "review predates assignment",
    )


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
            "evidence_type": "FP022_SEQ66_67_INDEPENDENT_INTERNAL_REVIEW",
            "goal_id": fp022.GOAL_ID,
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
    )


def validate_post_review(root: Path = ROOT) -> ReviewContext:
    context = prepare_review_context(root)
    assignment, assignment_raw = _document(root, ASSIGNMENT_REL)
    result, result_raw = _document(root, RESULT_REL)
    validate_review_result(result, result_raw, assignment, assignment_raw, context)
    require(
        _raw(root, INDEPENDENT_REL)
        == build_independent_review(
            context, assignment, assignment_raw, result, result_raw
        ).encode(),
        "independent review differs",
    )
    return context


def prepare_frozen_review_context(root: Path = ROOT) -> ReviewContext:
    """Replay approved R002 from its assignment-frozen control cohort."""

    root = root.resolve(strict=True)
    prepare_frozen_aggregate_review(root)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative, (digest, byte_length) in R002_REVIEW_PINS.items():
        document, raw = _document(root, relative)
        require(
            len(raw) == byte_length and trace.bytes_sha256(raw) == digest,
            f"FP-022 seq66/67 frozen R002 binding differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw
    assignment = documents[ASSIGNMENT_REL]
    result = documents[RESULT_REL]
    scope = assignment.get("review_scope")
    require(type(scope) is dict, "FP-022 seq66/67 frozen R002 scope is missing")
    context = ReviewContext(
        root=root,
        predecessor_review_bindings=tuple(scope["predecessor_review_bindings"]),
        superseded_assignment_bindings=tuple(
            scope["superseded_review_assignments"]
        ),
        goal_evidence=dict(scope["goal_evidence"]),
        contract_evidence=dict(scope["start_gate_contract_evidence"]),
        control_code_cohort=tuple(scope["reviewed_control_code_cohort"]),
        control_code_cohort_sha256=scope[
            "reviewed_control_code_cohort_sha256"
        ],
    )
    for row in context.superseded_assignment_bindings:
        relative = Path(row["path"])
        raw = _raw(root, relative)
        require(
            trace.bytes_sha256(raw) == row["sha256"]
            and len(raw) == row["byte_length"],
            f"FP-022 seq66/67 frozen superseded binding differs: {relative}",
        )
    require(
        not (root / R001_RESULT_REL).exists()
        and not (root / R001_INDEPENDENT_REL).exists(),
        "superseded FP-022 seq66/67 R001 produced a review output",
    )
    validate_review_result(
        result,
        raw_by_path[RESULT_REL],
        assignment,
        raw_by_path[ASSIGNMENT_REL],
        context,
    )
    require(
        build_independent_review(
            context,
            assignment,
            raw_by_path[ASSIGNMENT_REL],
            result,
            raw_by_path[RESULT_REL],
        ).encode()
        == raw_by_path[INDEPENDENT_REL],
        "FP-022 seq66/67 frozen R002 independent review differs",
    )
    return context


def transition_review_binding(root: Path = ROOT) -> dict[str, dict[str, Any]]:
    validate_post_review(root)
    return {
        "assignment": _binding(ASSIGNMENT_REL, _raw(root, ASSIGNMENT_REL)),
        "review_result": _binding(RESULT_REL, _raw(root, RESULT_REL)),
        "independent_review": _binding(INDEPENDENT_REL, _raw(root, INDEPENDENT_REL)),
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
        "evidence_type": "FP022_SEQ66_67_REVIEW_ASSIGNMENT",
        "goal_id": fp022.GOAL_ID,
        "round_id": ROUND_ID,
        "assigned_at": _now(),
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": "codex-root-fp022-seq66-67-assigner-20260814",
            "canonical_task": "/root",
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": "codex-root-fp022-seq66-67-executor-20260814",
            "canonical_task": "/root",
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": "codex-fp022-seq66-67-reviewer-20260814",
            "canonical_task": "/root/fp022_seq66_67_r002_reviewer",
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
    _write_add_only(
        root,
        INDEPENDENT_REL,
        build_independent_review(
            context, assignment, assignment_raw, result, result_raw
        ),
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
            validate_review_result(
                result, result_raw, assignment, assignment_raw, context
            )
        elif args.write_independent:
            write_independent(root)
        else:
            validate_post_review(root)
    except (OSError, ValueError, TypeError, ReviewError, trace.BuildError) as exc:
        print(f"FP-022 seq66/67 review: FAIL: {exc}")
        return 1
    print("FP-022 seq66/67 review: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
