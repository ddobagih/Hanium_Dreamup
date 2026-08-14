#!/usr/bin/env python3
"""Build and validate the actor-separated FP-022 seq70/71 completion review.

Only the assignment and the deterministic independent validation artifact may
be written by this tool.  The reviewer-authored result must be supplied by the
separate reviewer named in the assignment.
"""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timezone
import os
from pathlib import Path
import sys
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp022_goal_completed_seq70_71_20260814 as completion
from scripts import build_walksafe_fp022_gap_backlog_r028_20260814 as r028_builder
from scripts import build_walksafe_fp022_navigation_internal_evidence_20260814 as evidence
from scripts import build_walksafe_fp022_seq68_69_review_20260814 as start_review
from scripts import check_walksafe_project_continuation_v2_4 as continuation


ReviewError = evidence.BuildError
require = evidence.require
bytes_sha256 = evidence.bytes_sha256
object_sha256 = evidence.object_sha256
json_text = evidence.json_text
strict_json_bytes = evidence.strict_json_bytes

GOAL_ID = evidence.GOAL_ID
CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
SOURCE_CHECKPOINT = {
    "path": CHECKPOINT_REL.as_posix(),
    "sequence": 69,
    "sha256": "5f260789269a5936517620b55262215b77797e4b2ec83220ec94d20cf951c25f",
    "byte_length": 2_032_842,
    "tail_event_id": "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001",
    "tail_event_sha256": (
        "91cec421d1fdd2f0f0d3ec57e282f7dceb7ae1a6fe51c9db9f9ffb7bba3e38a6"
    ),
}

START_REVIEW_PATHS = (
    start_review.ASSIGNMENT_REL,
    start_review.RESULT_REL,
    start_review.INDEPENDENT_REL,
)
START_REVIEW_PINS = {
    start_review.ASSIGNMENT_REL: (
        "59ab697506c6583765dc601967c121bd37965b6fa2575d43d99d4f8b655723d0",
        21_453,
    ),
    start_review.RESULT_REL: (
        "7e56214469952ccdcf947689fdca820ffb35dbfc975c1743c1d956a72c0099c0",
        21_506,
    ),
    start_review.INDEPENDENT_REL: (
        "edd71466f9176697d1e640703c43d2dad404902c4a9abc741ad16fe96a07dd38",
        21_784,
    ),
}

EVIDENCE_PATHS = (
    evidence.IMPLEMENTATION_REL,
    evidence.OBSERVATIONS_REL,
    *(lane.log_rel for lane in evidence.LANES),
    evidence.VERIFICATION_REL,
    *evidence.R028_PATHS,
    evidence.SUCCESSOR_REL,
    evidence.REVIEW_SUBJECT_REL,
    evidence.INDEPENDENT_REVIEW_REL,
    evidence.COMPLETION_REL,
)
UNMANAGED_EVIDENCE_PATHS = tuple(lane.log_rel for lane in evidence.LANES)

SCRIPT_REL = Path(
    "scripts/build_walksafe_fp022_completion_seq70_71_review_20260814.py"
)
TEST_REL = Path(
    "tests/test_build_walksafe_fp022_completion_seq70_71_review_20260814.py"
)
EVIDENCE_SCRIPT_REL = Path(
    "scripts/build_walksafe_fp022_navigation_internal_evidence_20260814.py"
)
EVIDENCE_TEST_REL = Path(
    "tests/test_build_walksafe_fp022_navigation_internal_evidence_20260814.py"
)
R028_SCRIPT_REL = Path(
    "scripts/build_walksafe_fp022_gap_backlog_r028_20260814.py"
)
R028_TEST_REL = Path(
    "tests/test_build_walksafe_fp022_gap_backlog_r028_20260814.py"
)
CONTROL_PATHS = (
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("tests/test_repository_catalogs.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    EVIDENCE_SCRIPT_REL,
    EVIDENCE_TEST_REL,
    R028_SCRIPT_REL,
    R028_TEST_REL,
    completion.SCRIPT_REL,
    completion.TEST_REL,
    SCRIPT_REL,
    TEST_REL,
)

REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq70-71/review-rounds"
)
R001_DIR = REVIEW_ROOT / "R001"
R001_ASSIGNMENT_REL = R001_DIR / "assignment.json"
R001_RESULT_REL = R001_DIR / "review-result.json"
R001_INDEPENDENT_REL = R001_DIR / "independent-review.json"
R002_DIR = REVIEW_ROOT / "R002"
R002_ASSIGNMENT_REL = R002_DIR / "assignment.json"
R002_RESULT_REL = R002_DIR / "review-result.json"
R002_INDEPENDENT_REL = R002_DIR / "independent-review.json"
R003_DIR = REVIEW_ROOT / "R003"
R003_ASSIGNMENT_REL = R003_DIR / "assignment.json"
R003_RESULT_REL = R003_DIR / "review-result.json"
R003_INDEPENDENT_REL = R003_DIR / "independent-review.json"
R004_DIR = REVIEW_ROOT / "R004"
R004_ASSIGNMENT_REL = R004_DIR / "assignment.json"
R004_RESULT_REL = R004_DIR / "review-result.json"
R004_INDEPENDENT_REL = R004_DIR / "independent-review.json"
R005_DIR = REVIEW_ROOT / "R005"
R005_ASSIGNMENT_REL = R005_DIR / "assignment.json"
R005_RESULT_REL = R005_DIR / "review-result.json"
R005_INDEPENDENT_REL = R005_DIR / "independent-review.json"
R006_DIR = REVIEW_ROOT / "R006"
R006_ASSIGNMENT_REL = R006_DIR / "assignment.json"
R006_RESULT_REL = R006_DIR / "review-result.json"
R006_INDEPENDENT_REL = R006_DIR / "independent-review.json"
R007_DIR = REVIEW_ROOT / "R007"
R007_ASSIGNMENT_REL = R007_DIR / "assignment.json"
R007_RESULT_REL = R007_DIR / "review-result.json"
R007_INDEPENDENT_REL = R007_DIR / "independent-review.json"
R008_DIR = REVIEW_ROOT / "R008"
R008_ASSIGNMENT_REL = R008_DIR / "assignment.json"
R008_RESULT_REL = R008_DIR / "review-result.json"
R008_INDEPENDENT_REL = R008_DIR / "independent-review.json"
R009_DIR = REVIEW_ROOT / "R009"
R009_ASSIGNMENT_REL = R009_DIR / "assignment.json"
R009_RESULT_REL = R009_DIR / "review-result.json"
R009_INDEPENDENT_REL = R009_DIR / "independent-review.json"
R010_DIR = REVIEW_ROOT / "R010"
R010_ASSIGNMENT_REL = R010_DIR / "assignment.json"
R010_RESULT_REL = R010_DIR / "review-result.json"
R010_INDEPENDENT_REL = R010_DIR / "independent-review.json"
R011_DIR = REVIEW_ROOT / "R011"
R011_ASSIGNMENT_REL = R011_DIR / "assignment.json"
R011_RESULT_REL = R011_DIR / "review-result.json"
R011_INDEPENDENT_REL = R011_DIR / "independent-review.json"
R012_DIR = REVIEW_ROOT / "R012"
R012_ASSIGNMENT_REL = R012_DIR / "assignment.json"
R012_RESULT_REL = R012_DIR / "review-result.json"
R012_INDEPENDENT_REL = R012_DIR / "independent-review.json"
R013_DIR = REVIEW_ROOT / "R013"
R013_ASSIGNMENT_REL = R013_DIR / "assignment.json"
R013_RESULT_REL = R013_DIR / "review-result.json"
R013_INDEPENDENT_REL = R013_DIR / "independent-review.json"
SUPERSEDED_ASSIGNMENTS = (
    {
        "path": R001_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "ff8f90f87ae184423a6af793a651fb47cbef7056cdef1ce5ab4b4f2d89e52ff4"
        ),
        "byte_length": 10_872,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "CATALOGS_WERE_NOT_SEEDED_IN_INITIAL_MANAGED_CLOSURE",
    },
    {
        "path": R002_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "51a141e80d0a6dd63a23ecfe381167cb40af3c76b359193abc02f3f61f0e4a79"
        ),
        "byte_length": 11_284,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "COMPLETION_REVIEW_PATH_AND_HISTORICAL_SEQ68_SNAPSHOT_WERE_NOT_SUCCESSOR_AWARE",
    },
    {
        "path": R003_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "4146230011874697d4b72222a0697981b490c1e70af5da26fafdad3aa23c3212"
        ),
        "byte_length": 11_677,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "HISTORICAL_NPC_AND_FP022_PRODUCT_SUCCESSOR_OVERLAYS_WERE_NOT_COMPOSED",
    },
    {
        "path": R004_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "e021af8c11ff6b50648d5b1f36a41386c27eed2e27ce68c6f69020d11fb4d850"
        ),
        "byte_length": 12_062,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "HISTORICAL_SEQ55_REGRESSION_REPLAYED_MUTABLE_NPC_COMPLETION_AUTHORITY",
    },
    {
        "path": R005_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "d1b21d6548a73ed01c503ac671adb6e63197c004d8a7176b8ab33090656c01ba"
        ),
        "byte_length": 12_447,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "DEFAULT_PRODUCT_COMPATIBILITY_REPLAYED_MUTABLE_COMPLETION_AUTHORITY",
    },
    {
        "path": R006_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "16070d016fb8f01910ef9a07f7c5369b2950d4c73e8351a67c8249675a625152"
        ),
        "byte_length": 12_830,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "IGNORED_EVIDENCE_LOGS_WERE_INCLUDED_IN_THE_MANAGED_SOURCE_UNIVERSE",
    },
    {
        "path": R007_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "4e372c5c36c01f7416742e1ed411bae54561ca796814da364c67009fe89d5d2f"
        ),
        "byte_length": 13_212,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "MANAGED_SEQUENCE_PATHS_STILL_INCLUDED_IGNORED_EVIDENCE_LOGS",
    },
    {
        "path": R008_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "bcfaa4f23c7858173bc0c396e405e7ff5ebe98e5508288daf7c65569dfd117ce"
        ),
        "byte_length": 13_587,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "FORCED_GIT_VISIBLE_IGNORED_LOG_COULD_REENTER_MANAGED_CLOSURE",
    },
    {
        "path": R009_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "9d3075a1c5cbcb75025acaf060e443c24e95da57cdf8635e2965cdf64a982cb4"
        ),
        "byte_length": 13_963,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ71_DID_NOT_REWIND_SEQ67_AUTHORITY_OR_MANAGE_ALL_PRODUCT_PATHS",
    },
    {
        "path": R010_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "06b803513d79ac0da6ee8bc18d4ae067a828a9a2af355ef60ced995384174664"
        ),
        "byte_length": 14_344,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ71_REWIND_DID_NOT_RESTORE_SEQ67_CURRENT_WORK_ITEM_ID",
    },
    {
        "path": R011_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "9ef4c4574e171d1f3ba79bce01f0578772689382f73684855f1704f5aa34dd33"
        ),
        "byte_length": 14_715,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "ATOMIC_COMMIT_GUARD_REJECTED_THE_EXACT_PROJECTED_CHECKPOINT_PHASE",
    },
    {
        "path": R012_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "b0ddf7ce9d9970e6111f03d6b681ad06683a1f84602896562907c071373598e0"
        ),
        "byte_length": 15_096,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "POSTPUBLICATION_REGRESSIONS_RETAINED_PRECOMPLETION_FIXTURE_ASSUMPTIONS",
    },
    {
        "path": R013_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "d8e415055f89f318565fb181a6c44336eba899899f1f4376c2da6e80af73fbe5"
        ),
        "byte_length": 15_482,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ55_SUCCESSOR_REGRESSION_TRUNCATED_A_VALID_COMPLETION_SUFFIX",
    },
)
REVIEW_DIR = REVIEW_ROOT / "R014"
ASSIGNMENT_REL = REVIEW_DIR / "assignment.json"
RESULT_REL = REVIEW_DIR / "review-result.json"
INDEPENDENT_REL = REVIEW_DIR / "independent-review.json"
ROUND_ID = "WS-FP022-SEQ70-71-COMPLETION-REVIEW-20260814-R014"

ASSIGNER_ID = "codex-root-fp022-completion-transition-assigner-20260814"
ASSIGNER_TASK = "/root"
EXECUTOR_ID = "codex-root-fp022-completion-transition-executor-20260814"
EXECUTOR_TASK = "/root"
REVIEWER_ID = "codex-fp022-completion-transition-reviewer-20260814"
REVIEWER_TASK = "/root/fp022_completion_transition_reviewer"

PRODUCT_EXECUTOR_ID = "codex-fp022-navigation-implementer-20260814"
PRODUCT_EXECUTOR_TASK = "/root"
PRODUCT_REVIEWER_ID = "codex-fp022-navigation-internal-reviewer-20260814"
PRODUCT_REVIEWER_TASK = "/root/fp022_evidence_independent_reviewer"

PROJECTED_TRANSITION = {
    "canonical_bindings_updated": {
        "sequence": 70,
        "event_id": completion.UPDATE_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "from_status": "IN_PROGRESS",
        "to_status": "IN_PROGRESS",
        "status_changes": {},
        "transition_review_binding_required": True,
    },
    "goal_completed": {
        "sequence": 71,
        "event_id": completion.COMPLETION_EVENT_ID,
        "event_type": "GOAL_COMPLETED",
        "subject_goal_id": GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "COMPLETE_AT_TARGET",
        "status_changes": {GOAL_ID: "COMPLETE_AT_TARGET"},
    },
    "final_focus_goal_id": completion.PARENT_GOAL_ID,
    "final_status": "COMPLETE_AT_TARGET",
    "next_policy_id": "FP-023",
    "next_priority_rank": 25,
}

BOUNDARY = {
    "product_implementation_credit_added": 0,
    "artifact_completion_credit_added": 0,
    "formal_test_credit_added": 0,
    "actual_device_credit_added": 0,
    "external_review_credit_added": 0,
    "deployment_credit_added": 0,
    "approval_credit_added": 0,
    "release_credit_added": 0,
    "external_independence_claimed": False,
    "release_status": "NOT_ELIGIBLE",
}


@dataclass(frozen=True)
class ReviewContext:
    root: Path
    start_review_bindings: tuple[dict[str, Any], ...]
    completion_evidence_bindings: tuple[dict[str, Any], ...]
    superseded_assignment_bindings: tuple[dict[str, Any], ...]
    control_code_cohort: tuple[dict[str, Any], ...]
    control_code_cohort_sha256: str


def _safe_path(root: Path, relative: Path) -> Path:
    require(
        not relative.is_absolute() and ".." not in relative.parts,
        f"unsafe review path: {relative}",
    )
    root = root.resolve(strict=True)
    current = root
    for part in relative.parts:
        current = current / part
        require(not current.is_symlink(), f"symlink review path rejected: {relative}")
    require(current.is_file(), f"review input is missing: {relative}")
    return current


def _raw(root: Path, relative: Path) -> bytes:
    return _safe_path(root, relative).read_bytes()


def _document(root: Path, relative: Path) -> tuple[dict[str, Any], bytes]:
    raw = _raw(root, relative)
    return strict_json_bytes(raw, relative.as_posix()), raw


def _binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
    }


def _bind_paths(root: Path, paths: Sequence[Path]) -> tuple[dict[str, Any], ...]:
    bindings = tuple(_binding(path, _raw(root, path)) for path in paths)
    require(
        len(bindings) == len(paths)
        and len({row["path"] for row in bindings}) == len(paths),
        "review binding cohort is not exact",
    )
    return bindings


def _parse_time(value: Any, label: str) -> datetime:
    require(type(value) is str and bool(value), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ReviewError(f"{label} is not ISO-8601") from exc
    require(
        parsed.tzinfo is not None and parsed.isoformat() == value,
        f"{label} is not canonical offset ISO-8601",
    )
    return parsed


def _require_source(root: Path, *, require_exact: bool) -> None:
    raw = _raw(root, CHECKPOINT_REL)
    exact = (
        len(raw) == SOURCE_CHECKPOINT["byte_length"]
        and bytes_sha256(raw) == SOURCE_CHECKPOINT["sha256"]
    )
    if require_exact:
        require(exact, "FP-022 completion review source is not exact seq69")
    checkpoint = strict_json_bytes(raw, CHECKPOINT_REL.as_posix())
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(
        type(history) is list and len(history) >= SOURCE_CHECKPOINT["sequence"],
        "FP-022 completion review seq69 lineage is missing",
    )
    tail = history[SOURCE_CHECKPOINT["sequence"] - 1]
    require(
        type(tail) is dict
        and tail.get("sequence") == SOURCE_CHECKPOINT["sequence"]
        and tail.get("event_id") == SOURCE_CHECKPOINT["tail_event_id"]
        and tail.get("event_sha256") == SOURCE_CHECKPOINT["tail_event_sha256"]
        and continuation.event_sha256(tail)
        == SOURCE_CHECKPOINT["tail_event_sha256"],
        "FP-022 completion review seq69 event differs",
    )
    if len(history) == SOURCE_CHECKPOINT["sequence"]:
        require(exact, "live seq69 checkpoint bytes differ")


def _frozen_start_review(
    root: Path = ROOT,
) -> tuple[start_review.ReviewContext, dict[Path, bytes]]:
    root = root.resolve(strict=True)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative in START_REVIEW_PATHS:
        document, raw = _document(root, relative)
        expected_sha256, expected_length = START_REVIEW_PINS[relative]
        require(
            bytes_sha256(raw) == expected_sha256 and len(raw) == expected_length,
            f"exact FP-022 seq68/69 R031 review differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[start_review.ASSIGNMENT_REL]
    result = documents[start_review.RESULT_REL]
    scope = assignment.get("review_scope")
    require(type(scope) is dict, "FP-022 seq68/69 R031 scope is missing")
    frozen_context = start_review.ReviewContext(
        root=root,
        predecessor_review_bindings=tuple(scope["predecessor_review_bindings"]),
        superseded_assignment_bindings=tuple(
            scope["superseded_review_assignments"]
        ),
        contract_r001_evidence=copy.deepcopy(
            scope["superseded_start_gate_contract_evidence"]
        ),
        contract_r002_evidence=copy.deepcopy(
            scope["successor_start_gate_contract_evidence"]
        ),
        control_code_cohort=tuple(scope["reviewed_control_code_cohort"]),
        control_code_cohort_sha256=scope[
            "reviewed_control_code_cohort_sha256"
        ],
    )
    start_review.validate_review_result(
        result,
        raw_by_path[start_review.RESULT_REL],
        assignment,
        raw_by_path[start_review.ASSIGNMENT_REL],
        frozen_context,
    )
    require(
        start_review.build_independent_review(
            frozen_context,
            assignment,
            raw_by_path[start_review.ASSIGNMENT_REL],
            result,
            raw_by_path[start_review.RESULT_REL],
        ).encode()
        == raw_by_path[start_review.INDEPENDENT_REL],
        "exact FP-022 seq68/69 R031 independent review differs",
    )
    return frozen_context, raw_by_path


def prepare_frozen_start_review_context(
    root: Path = ROOT,
) -> start_review.ReviewContext:
    context, _ = _frozen_start_review(root)
    return context


def prepare_frozen_start_review(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    _, raw_by_path = _frozen_start_review(root)
    return tuple(_binding(path, raw_by_path[path]) for path in START_REVIEW_PATHS)


def _validate_product_chain(root: Path) -> tuple[dict[str, Any], ...]:
    initial_paths = (
        *(lane.log_rel for lane in evidence.LANES),
        evidence.IMPLEMENTATION_REL,
        evidence.OBSERVATIONS_REL,
        evidence.VERIFICATION_REL,
    )
    initial = {
        path: _raw(root, path).decode("utf-8") for path in initial_paths
    }
    evidence.validate_evidence_outputs(
        initial,
        root=root,
        source_groups=evidence.IMPLEMENTATION_SOURCE_GROUPS,
        authority=evidence.validate_authority(root),
        executor_actor_id=PRODUCT_EXECUTOR_ID,
        executor_task=PRODUCT_EXECUTOR_TASK,
    )

    r028 = {path: _raw(root, path) for path in evidence.R028_PATHS}
    expected_r028 = r028_builder.build_outputs(root)
    require(
        all(r028[path] == expected_r028[path].encode() for path in evidence.R028_PATHS),
        "FP-022 exact R028 builder outputs differ",
    )
    evidence.validate_r028_outputs(r028)

    successor = {
        path: _raw(root, path).decode("utf-8")
        for path in (evidence.SUCCESSOR_REL, evidence.REVIEW_SUBJECT_REL)
    }
    evidence.validate_successor_outputs(
        successor,
        evidence_outputs=initial,
        r028_outputs=r028,
        executor_actor_id=PRODUCT_EXECUTOR_ID,
        executor_task=PRODUCT_EXECUTOR_TASK,
        required_reviewer_actor_id=PRODUCT_REVIEWER_ID,
        required_reviewer_task=PRODUCT_REVIEWER_TASK,
    )
    independent_raw = _raw(root, evidence.INDEPENDENT_REVIEW_REL)
    evidence.validate_independent_review(
        independent_raw,
        subject_text=successor[evidence.REVIEW_SUBJECT_REL],
        executor_actor_id=PRODUCT_EXECUTOR_ID,
        executor_task=PRODUCT_EXECUTOR_TASK,
        reviewer_actor_id=PRODUCT_REVIEWER_ID,
        reviewer_task=PRODUCT_REVIEWER_TASK,
    )
    completion_output = {
        evidence.COMPLETION_REL: _raw(root, evidence.COMPLETION_REL).decode("utf-8")
    }
    evidence.validate_completion_output(
        completion_output,
        evidence_outputs=initial,
        successor_outputs=successor,
        r028_outputs=r028,
        independent_review_raw=independent_raw,
        executor_actor_id=PRODUCT_EXECUTOR_ID,
        executor_task=PRODUCT_EXECUTOR_TASK,
        reviewer_actor_id=PRODUCT_REVIEWER_ID,
        reviewer_task=PRODUCT_REVIEWER_TASK,
    )
    return _bind_paths(root, EVIDENCE_PATHS)


def _require_transition_constants() -> None:
    require(
        completion.SOURCE_CHECKPOINT_RAW_SHA256 == SOURCE_CHECKPOINT["sha256"]
        and completion.SOURCE_CHECKPOINT_BYTE_COUNT
        == SOURCE_CHECKPOINT["byte_length"]
        and completion.SOURCE_EVENT_ID == SOURCE_CHECKPOINT["tail_event_id"]
        and completion.SOURCE_EVENT_SHA256
        == SOURCE_CHECKPOINT["tail_event_sha256"]
        and completion.UPDATE_EVENT_ID
        == PROJECTED_TRANSITION["canonical_bindings_updated"]["event_id"]
        and completion.COMPLETION_EVENT_ID
        == PROJECTED_TRANSITION["goal_completed"]["event_id"]
        and completion.GOAL_ID == GOAL_ID,
        "FP-022 seq70/71 sealed transition constants differ",
    )
    require(
        completion.CHANGED_ROLES
        == ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP", completion.COMPLETION_ROLE]
        and completion.PRODUCED_ROLES
        == ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
        and completion.TRANSITION_REVIEW_PATHS
        == (ASSIGNMENT_REL, RESULT_REL, INDEPENDENT_REL),
        "FP-022 seq70/71 review or canonical-role contract differs",
    )


def prepare_review_context(
    root: Path = ROOT, *, require_exact_source: bool = False
) -> ReviewContext:
    root = root.resolve(strict=True)
    _require_source(root, require_exact=require_exact_source)
    start = prepare_frozen_start_review(root)
    evidence_bindings = _validate_product_chain(root)
    _require_transition_constants()
    superseded = tuple(copy.deepcopy(SUPERSEDED_ASSIGNMENTS))
    for row in superseded:
        relative = Path(row["path"])
        raw = _raw(root, relative)
        require(
            _binding(relative, raw)
            == {key: row[key] for key in ("path", "sha256", "byte_length")},
            f"superseded completion review assignment differs: {relative}",
        )
        for output_name in ("review-result.json", "independent-review.json"):
            require(
                not os.path.lexists(root / relative.parent / output_name),
                f"superseded completion review produced an output: {relative.parent / output_name}",
            )
    controls = _bind_paths(root, CONTROL_PATHS)
    return ReviewContext(
        root=root,
        start_review_bindings=start,
        completion_evidence_bindings=evidence_bindings,
        superseded_assignment_bindings=superseded,
        control_code_cohort=controls,
        control_code_cohort_sha256=object_sha256(list(controls)),
    )


def _scope(context: ReviewContext) -> dict[str, Any]:
    return {
        "source_checkpoint": copy.deepcopy(SOURCE_CHECKPOINT),
        "start_transition_review_bindings": copy.deepcopy(
            list(context.start_review_bindings)
        ),
        "completion_evidence_bindings": copy.deepcopy(
            list(context.completion_evidence_bindings)
        ),
        "superseded_review_assignments": copy.deepcopy(
            list(context.superseded_assignment_bindings)
        ),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "reviewed_control_code_cohort": copy.deepcopy(
            list(context.control_code_cohort)
        ),
        "reviewed_control_code_cohort_sha256": context.control_code_cohort_sha256,
        "acceptance": {
            "source_checkpoint_is_exact_seq69_at_assignment": True,
            "seq68_69_r031_start_transition_review_is_exact": True,
            "six_initial_product_evidence_files_are_validated": True,
            "four_r028_successor_files_are_exact": True,
            "successor_review_subject_product_independent_review_and_completion_receipt_are_validated": True,
            "seq70_is_adjacent_canonical_bindings_update_and_binds_this_review": True,
            "seq71_is_adjacent_fp022_goal_completion": True,
            "fp022_finishes_complete_at_target_and_focuses_epic04": True,
            "fp023_gap032_rank25_remains_the_semantic_next_pointer": True,
            "formal_device_external_deployment_approval_and_release_credit_remain_zero": True,
            "atomic_checkpoint_and_catalog_publication_remains_fail_closed": True,
        },
    }


def _identity(value: Any, role: str, actor_id: str, task: str) -> dict[str, str]:
    expected = {
        "role": role,
        "agent_instance_id": actor_id,
        "canonical_task": task,
    }
    label = {
        "INTERNAL_REVIEW_ASSIGNER": "assigner",
        "INTERNAL_IMPLEMENTATION_EXECUTOR": "executor",
        "SEPARATE_INTERNAL_REVIEWER": "reviewer",
    }[role]
    require(
        type(value) is dict and value == expected,
        f"{label} identity differs",
    )
    return dict(value)


def validate_assignment(
    assignment: Mapping[str, Any], raw: bytes, context: ReviewContext
) -> None:
    require(raw == json_text(assignment).encode(), "assignment is noncanonical")
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
        == "FP022_SEQ70_71_COMPLETION_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("round_id") == ROUND_ID,
        "assignment identity differs",
    )
    _parse_time(assignment.get("assigned_at"), "assigned_at")
    assigner = _identity(
        assignment.get("assigner"),
        "INTERNAL_REVIEW_ASSIGNER",
        ASSIGNER_ID,
        ASSIGNER_TASK,
    )
    executor = _identity(
        assignment.get("executor"),
        "INTERNAL_IMPLEMENTATION_EXECUTOR",
        EXECUTOR_ID,
        EXECUTOR_TASK,
    )
    reviewer = _identity(
        assignment.get("reviewer"),
        "SEPARATE_INTERNAL_REVIEWER",
        REVIEWER_ID,
        REVIEWER_TASK,
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
    require(raw == json_text(result).encode(), "review result is noncanonical")
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
        == "FP022_SEQ70_71_COMPLETION_REVIEWER_AUTHORED_RESULT"
        and result.get("goal_id") == GOAL_ID
        and result.get("round_id") == ROUND_ID,
        "review result identity differs",
    )
    require(result.get("reviewer") == assignment.get("reviewer"), "reviewer identity differs")
    require(
        result.get("assignment_binding") == _binding(ASSIGNMENT_REL, assignment_raw),
        "assignment binding differs",
    )
    require(result.get("review_scope") == _scope(context), "review result scope differs")
    require(
        result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and result.get("finding_dispositions") == [],
        "review is not finding-free approved",
    )
    require(result.get("review_boundary") == BOUNDARY, "review result boundary differs")
    require(
        _parse_time(result.get("reviewed_at"), "reviewed_at")
        >= _parse_time(assignment.get("assigned_at"), "assigned_at"),
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
    return json_text(
        {
            "schema_version": "1.0",
            "evidence_type": "FP022_SEQ70_71_COMPLETION_INDEPENDENT_INTERNAL_REVIEW",
            "goal_id": GOAL_ID,
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
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ).encode(),
        "independent completion-transition review differs",
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
    require(not relative.is_absolute() and ".." not in relative.parts, "unsafe output path")
    root = root.resolve(strict=True)
    target = root / relative
    current = root
    for part in relative.parent.parts:
        current = current / part
        require(not current.is_symlink(), f"symlink output path rejected: {relative}")
    target.parent.mkdir(parents=True, exist_ok=True)
    require(not os.path.lexists(target), f"review output already exists: {relative}")
    with target.open("x", encoding="utf-8", newline="") as handle:
        handle.write(text)


def write_assignment(root: Path) -> None:
    context = prepare_review_context(root, require_exact_source=True)
    assignment = {
        "schema_version": "1.0",
        "evidence_type": "FP022_SEQ70_71_COMPLETION_REVIEW_ASSIGNMENT",
        "goal_id": GOAL_ID,
        "round_id": ROUND_ID,
        "assigned_at": _now(),
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": ASSIGNER_ID,
            "canonical_task": ASSIGNER_TASK,
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": EXECUTOR_ID,
            "canonical_task": EXECUTOR_TASK,
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": REVIEWER_ID,
            "canonical_task": REVIEWER_TASK,
        },
        "review_scope": _scope(context),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    text = json_text(assignment)
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
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
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
                result,
                result_raw,
                assignment,
                assignment_raw,
                context,
            )
        elif args.write_independent:
            write_independent(root)
        else:
            validate_post_review(root)
    except (OSError, ValueError, TypeError, ReviewError) as exc:
        print(f"FP-022 seq70/71 completion review: FAIL: {exc}")
        return 1
    print("FP-022 seq70/71 completion review: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
