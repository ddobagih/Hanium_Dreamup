#!/usr/bin/env python3
"""Build the static FP-046 R002 seq77/78 controls and separated review."""

from __future__ import annotations

import argparse
import copy
from contextvars import ContextVar
import ctypes
from dataclasses import dataclass
from datetime import datetime, timezone
import errno
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

class ReviewError(RuntimeError):
    """The local review authority rejected an input or transition."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReviewError(message)


def bytes_sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )


def object_sha256(value: Any) -> str:
    return bytes_sha256(_canonical_json(value).encode("utf-8"))


def json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n"


def _reject_duplicate_json_members(
    pairs: list[tuple[str, Any]],
) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON member: {key}")
        result[key] = value
    return result


def _reject_nonfinite_json_number(value: str) -> None:
    raise ReviewError(f"non-finite JSON number is forbidden: {value}")


def strict_json_bytes(content: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(
            content.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_json_members,
            parse_constant=_reject_nonfinite_json_number,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ReviewError(f"invalid JSON for {label}: {exc}") from exc
    require(type(value) is dict, f"{label} must be a JSON object")
    return value


class PostcommitUncertain(ReviewError):
    """The final name was published but durability/verification is uncertain."""


MAXIMUM_EVIDENCE_BYTES = 8 * 1024 * 1024
MAXIMUM_CHECKPOINT_BYTES = 16 * 1024 * 1024
# Review reads accept the transaction-owned private mode and the Git checkout
# mode.  The seq77/78 CAS writers keep their stricter 0600 publication contract.
CHECKPOINT_REVIEW_SOURCE_MODES = frozenset({0o600, 0o644})
# Git materializes non-executable/executable sources as 0644/0755; this
# collaborative worktree uses group-writable 0664/0775.  No other mode is valid.
NONEXECUTABLE_REVIEW_SOURCE_MODES = frozenset({0o644, 0o664})
EXECUTABLE_REVIEW_SOURCE_MODES = frozenset({0o755, 0o775})
PHYSICAL_SNAPSHOT_SOURCE_MODES = frozenset(
    {0o600, 0o644, 0o664, 0o755, 0o775}
)
# Repository ancestors are 0755 in a Git checkout and 0775 in the shared
# umask-0002 worktree.  Other directory modes are outside this review boundary.
REVIEW_DIRECTORY_MODES = frozenset({0o755, 0o775})
_SOURCE_VALIDATION_ACTIVE: ContextVar[bool] = ContextVar(
    "fp046_r002_seq77_78_source_validation_active", default=False
)
_PROVISIONAL_REVIEW_BINDING: ContextVar[
    tuple[
        tuple[int, int, int, int],
        dict[str, dict[str, Any]],
        dict[Path, bytes],
    ]
    | None
] = ContextVar("fp046_r002_seq77_78_provisional_review_binding", default=None)
_RETAINED_REVIEW_INPUT_COHORT: ContextVar[Any | None] = ContextVar(
    "fp046_r002_seq77_78_retained_review_input_cohort", default=None
)
_PUBLICATION_COMMIT_CALLBACK: ContextVar[Callable[[], None] | None] = (
    ContextVar(
        "fp046_r002_seq77_78_publication_commit_callback", default=None
    )
)

GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R002"
GOAL_REL = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp046-consent-withdrawal-deletion-r002.md"
)
GOAL_SHA256 = "4627c19b421f626323778fcdfd01cc2edbc36644c48c7d65c2b4847e698ac429"
GOAL_BYTE_LENGTH = 4_963

SOURCE_CHECKPOINT = {
    "path": "docs/control/walksafe-project-continuation-checkpoint.json",
    "sequence": 76,
    "sha256": "bf0f44f0ed2a777c2a3d62b81fa995d26e3b725e398a37034b7d70fa1d3a1a36",
    "byte_length": 2_217_805,
    "tail_event_id": "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP046-R002-20260815-001",
    "tail_event_sha256": (
        "f2a529bae5aa808fccb64191a42902d7e717d0340531410213486bdcfa279070"
    ),
}

PREDECESSOR_AUTHORIZATION_REL = Path(
    "docs/control/execution/workstream-transitions/seq72-76/authorization.json"
)
PREDECESSOR_AUTHORIZATION_EVIDENCE = {
    "document_id": "WS-FP046-NPC-R002-REOPEN-AUTHORIZATION-20260815-001",
    "authorization_status": "AUTHORIZED_FOR_SEQ72_76_ONLY",
    "path": PREDECESSOR_AUTHORIZATION_REL.as_posix(),
    "file_sha256": (
        "60c7d6220d4a6de832747d28019a9b6b55a6982d45d1571746968300394445d1"
    ),
    "byte_length": 828,
}
AUTHORIZATION_REL = Path(
    "docs/control/execution/workstream-transitions/seq77-78/authorization.json"
)
CONTRACT_R001_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-046-R002/"
    "initial-start-gate-contract-r001.json"
)
CONTRACT_R001_EVIDENCE = {
    "document_id": "WS-FP046-R002-INITIAL-START-GATE-CONTRACT-20260815-001",
    "contract_id": "WS-FP046-R002-INTERNAL-START-GATE-R001",
    "contract_version": "2026-08-15.1",
    "path": CONTRACT_R001_REL.as_posix(),
    "file_sha256": (
        "506d6afb202b63e4e30f3bb5ba1a560b18b8f76679a37a18a144a442a4a0fe5e"
    ),
    "canonical_sha256": (
        "a8295f1a693b41bbc3864f882fdd8ba9f17e0ddba49562517fcfc3bb26679214"
    ),
    "byte_length": 1_011,
    "source_ready_event_sequence": 76,
    "source_ready_event_id": SOURCE_CHECKPOINT["tail_event_id"],
    "source_ready_event_sha256": SOURCE_CHECKPOINT["tail_event_sha256"],
}
CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-046-R002/"
    "initial-start-gate-contract-r002.json"
)

REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq77-78/review-rounds"
)
R001_REVIEW_DIR = REVIEW_ROOT / "R001"
R001_ASSIGNMENT_REL = R001_REVIEW_DIR / "review-assignment.json"
R001_RESULT_REL = R001_REVIEW_DIR / "review-result.json"
R001_INDEPENDENT_REL = R001_REVIEW_DIR / "independent-review.json"
R001_ROUND_ID = "WS-FP046-R002-SEQ77-78-REVIEW-20260823-R001"
R001_ASSIGNMENT_SHA256 = (
    "e1b3807d7de0d3473d271e8aa36cd73d0b2e5bde3ecc00f0c8652642efc376dd"
)
R001_ASSIGNMENT_BYTE_LENGTH = 22_114
R001_SUPERSESSION_REASON_CODE = (
    "R001_BLOCKING_CONTROL_BOUNDARY_FINDINGS_REQUIRE_REVIEWED_COHORT_RESEAL"
)

R002_REVIEW_DIR = REVIEW_ROOT / "R002"
R002_ASSIGNMENT_REL = R002_REVIEW_DIR / "review-assignment.json"
R002_RESULT_REL = R002_REVIEW_DIR / "review-result.json"
R002_INDEPENDENT_REL = R002_REVIEW_DIR / "independent-review.json"
R002_ROUND_ID = "WS-FP046-R002-SEQ77-78-REVIEW-20260823-R002"
R002_ASSIGNMENT_SHA256 = (
    "1fbb07eb4fe16e943a5ca13f9d78ad10f51952d01dbbc1ca6d698b91ed0c5885"
)
R002_ASSIGNMENT_BYTE_LENGTH = 22_838
R002_SUPERSESSION_REASON_CODE = (
    "R002_SIX_CONFIRMED_FINDINGS_REQUIRE_R003_RESEAL"
)
R002_CONFIRMED_REJECTION_FINDINGS = (
    "GATE_CONTRACT_REFERENCES_MISSING_PYTEST_SELECTOR",
    "VALID_SEQ79_PLUS_INVALIDATES_REVIEW_AUTHORITY",
    "SEQ77_OCCURRED_AT_PREDATES_BOUND_REVIEW",
    "SEQ78_MANAGED_COHORT_TOCTOU",
    "SEQ77_CLAIM_BOUNDARY_CONFUSES_JSON_ZERO_AND_FALSE",
    "POSTCOMMIT_INTERRUPT_ESCAPES_UNCERTAIN_CLASSIFICATION",
)

R003_REVIEW_DIR = REVIEW_ROOT / "R003"
R003_ASSIGNMENT_REL = R003_REVIEW_DIR / "review-assignment.json"
R003_RESULT_REL = R003_REVIEW_DIR / "review-result.json"
R003_INDEPENDENT_REL = R003_REVIEW_DIR / "independent-review.json"
R003_ROUND_ID = "WS-FP046-R002-SEQ77-78-REVIEW-20260823-R003"
R003_ASSIGNMENT_SHA256 = (
    "fb3bf40e349c4edf6cabdd7a8afde5c491802f3d943f8b2b4beda5b84d54658c"
)
R003_ASSIGNMENT_BYTE_LENGTH = 24_009
R003_SUPERSESSION_REASON_CODE = (
    "R003_REVIEW_INPUT_COHORT_ABA_RETAINED_DESCRIPTOR_REQUIRED"
)
R003_CONFIRMED_REJECTION_FINDINGS = (
    "REVIEW_INPUT_COHORT_ABA_CAN_PUBLISH_SELF_INVALID_EVIDENCE",
)

R004_REVIEW_DIR = REVIEW_ROOT / "R004"
R004_ASSIGNMENT_REL = R004_REVIEW_DIR / "review-assignment.json"
R004_RESULT_REL = R004_REVIEW_DIR / "review-result.json"
R004_INDEPENDENT_REL = R004_REVIEW_DIR / "independent-review.json"
R004_ROUND_ID = "WS-FP046-R002-SEQ77-78-REVIEW-20260823-R004"
R004_ASSIGNMENT_SHA256 = (
    "810013bf9aab371a7cdd899e617afdfa9c3fda9979a387f20784277174e56ee7"
)
R004_ASSIGNMENT_BYTE_LENGTH = 27_865
R004_SUPERSESSION_REASON_CODE = (
    "R004_POST_PUBLICATION_REGRESSION_NOT_RERUNNABLE"
)

PRESERVED_REVIEW_PATHS = (
    R001_ASSIGNMENT_REL,
    R002_ASSIGNMENT_REL,
    R003_ASSIGNMENT_REL,
    R004_ASSIGNMENT_REL,
)

R005_REVIEW_DIR = REVIEW_ROOT / "R005"
R005_ASSIGNMENT_REL = R005_REVIEW_DIR / "review-assignment.json"
R005_RESULT_REL = R005_REVIEW_DIR / "review-result.json"
R005_INDEPENDENT_REL = R005_REVIEW_DIR / "independent-review.json"
R005_REVIEW_PATHS = (
    R005_ASSIGNMENT_REL,
    R005_RESULT_REL,
    R005_INDEPENDENT_REL,
)
R005_REVIEW_PINS = {
    R005_ASSIGNMENT_REL: (
        "5c9927f92934b5ee6586a0b966a180824cae722cc389d38e031bf04eb6650d37",
        28_536,
    ),
    R005_RESULT_REL: (
        "f7bd94cb53e521c37f442982c7fb1f29df6a9f17bc3342f51fc7593748dc015c",
        28_548,
    ),
    R005_INDEPENDENT_REL: (
        "9ed52ee716b8101e82938b909a2b1ba046a2f240f15b584d9577d09932a768ee",
        28_831,
    ),
}
R005_SUPERSESSION_REASON_CODE = (
    "R005_POST_APPROVAL_SESSION_ARTIFACT_INVENTORY_GROWTH_REQUIRES_R006_RESEAL"
)

SESSION_ARTIFACT_PATHS = (
    Path("daylog/2026-08-23.md"),
    Path("daylog/2026-08-24.md"),
    Path("docs/planning/walksafe-fp046-r002-resumption-plan-20260824.html"),
    Path(
        "docs/planning/"
        "walksafe-security-server-operations-feature-first-plan-20260824.html"
    ),
)
SESSION_ARTIFACT_PINS = {
    SESSION_ARTIFACT_PATHS[0]: (
        "96885bff8f5486493e04ae089d9ebb80ea1d5c677aa37e3b36fe4a2bd6707432",
        5_982,
    ),
    SESSION_ARTIFACT_PATHS[1]: (
        "6bccb6222b2292446f20f48d54171850873f86c515547a74aefadab3fc89667f",
        13_479,
    ),
    SESSION_ARTIFACT_PATHS[2]: (
        "1bc7662049d938c821f769278713af3dadb84e4c250450becb78dbb7edaaa742",
        37_293,
    ),
    SESSION_ARTIFACT_PATHS[3]: (
        "1a0baf026f86bd1b6624b70e2cda54c9c5547edacb7ec4459a45d3d64e193d14",
        53_718,
    ),
}

REVIEW_DIR = REVIEW_ROOT / "R006"
ASSIGNMENT_REL = REVIEW_DIR / "review-assignment.json"
RESULT_REL = REVIEW_DIR / "review-result.json"
INDEPENDENT_REL = REVIEW_DIR / "independent-review.json"
REVIEW_PATHS = (ASSIGNMENT_REL, RESULT_REL, INDEPENDENT_REL)
ROUND_ID = "WS-FP046-R002-SEQ77-78-REVIEW-20260823-R006"
ASSIGNMENT_DOCUMENT_ID = (
    "WS-FP046-R002-SEQ77-78-REVIEW-ASSIGNMENT-20260823-R006"
)
RESULT_DOCUMENT_ID = "WS-FP046-R002-SEQ77-78-REVIEW-RESULT-20260823-R006"
INDEPENDENT_DOCUMENT_ID = (
    "WS-FP046-R002-SEQ77-78-INDEPENDENT-REVIEW-20260823-R006"
)

FROZEN_CONTROL_SUCCESSOR_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq70-71/"
    "control-successor-reviews/20260815"
)
FROZEN_R011_PATHS = (
    FROZEN_CONTROL_SUCCESSOR_ROOT / "R011/assignment.json",
    FROZEN_CONTROL_SUCCESSOR_ROOT / "R011/review-result.json",
    FROZEN_CONTROL_SUCCESSOR_ROOT / "R011/independent-review.json",
)
FROZEN_R011_PINS = {
    FROZEN_R011_PATHS[0]: (
        "aa2a5341346fb383d681f42216e4bb8642be4f6d46fa1fded6040032c0f5403f",
        13_846,
    ),
    FROZEN_R011_PATHS[1]: (
        "bc768f559ae645972df09b7f05ab46cb8e5bbadb723d642a23c217962f2bd000",
        13_864,
    ),
    FROZEN_R011_PATHS[2]: (
        "98b9575e681e118be12773e05aad7c7f408a71c94ba673edcd91e8eea6357794",
        14_165,
    ),
}
FROZEN_R011_COHORT_PATHS = tuple(
    Path(value)
    for value in (
        "scripts/check_walksafe_project_continuation_v2_4.py",
        "tests/test_walksafe_project_continuation_v2_4.py",
        "scripts/check_walksafe_goal_graph_v2_4.py",
        "tests/test_walksafe_goal_graph_v2_4.py",
        "scripts/generate_repository_catalogs.py",
        "tests/test_repository_catalogs.py",
        "scripts/run_walksafe_test_layers_current.sh",
        "scripts/build_walksafe_fp022_navigation_internal_evidence_20260814.py",
        "tests/test_build_walksafe_fp022_navigation_internal_evidence_20260814.py",
        "scripts/build_walksafe_fp022_gap_backlog_r028_20260814.py",
        "tests/test_build_walksafe_fp022_gap_backlog_r028_20260814.py",
        "scripts/apply_walksafe_fp022_goal_completed_seq70_71_20260814.py",
        "tests/test_apply_walksafe_fp022_goal_completed_seq70_71_20260814.py",
        "scripts/build_walksafe_fp022_completion_seq70_71_review_20260814.py",
        "tests/test_build_walksafe_fp022_completion_seq70_71_review_20260814.py",
        "scripts/build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py",
        "tests/test_build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py",
        "scripts/apply_walksafe_fp046_npc_r002_reopen_20260815.py",
        "tests/test_apply_walksafe_fp046_npc_r002_reopen_20260815.py",
        "scripts/build_walksafe_fp046_gap_backlog_r029_20260815.py",
        "tests/test_build_walksafe_fp046_gap_backlog_r029_20260815.py",
        "scripts/apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py",
        "tests/test_apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py",
    )
)
FROZEN_LINEAGE_ROUND_PINS = {
    "R002": {
        FROZEN_CONTROL_SUCCESSOR_ROOT / "R002/assignment.json": (
            "e3a84b7608e4c1a6f3c94201ff78627d9f4d0686b5105b470de49ada1562b501",
            10_620,
        ),
        FROZEN_CONTROL_SUCCESSOR_ROOT / "R002/review-result.json": (
            "6e2f9b27ab3d8a5de78a01aa6ce4f703a0e6eadbe15000042f4a0b6a3225e6e9",
            10_659,
        ),
        FROZEN_CONTROL_SUCCESSOR_ROOT / "R002/independent-review.json": (
            "c7dff21f81533fe83ab168fae64eae2a76cc039b528df0a43be1eb1b6b32d7b0",
            10_960,
        ),
    },
    "R003": {
        FROZEN_CONTROL_SUCCESSOR_ROOT / "R003/assignment.json": (
            "51c0350e884ecc386619c0c0072fc88b95b3141a3fe6a9e35faa5ea57061ff19",
            13_074,
        ),
        FROZEN_CONTROL_SUCCESSOR_ROOT / "R003/review-result.json": (
            "178b53e19022631320c208d245e0b058e7d8536830baf761f5196f25135844a9",
            13_113,
        ),
        FROZEN_CONTROL_SUCCESSOR_ROOT / "R003/independent-review.json": (
            "8babb6664c7cb4b18fe2bc5e56af7d032d21ce33d6b7072846b4670d3e36daa6",
            13_414,
        ),
    },
    "R004": {
        FROZEN_CONTROL_SUCCESSOR_ROOT / "R004/assignment.json": (
            "6e7e0b2e3d356307c84f5126766c67cd5d3ca39f585385180e27a93d242d314f",
            9_191,
        ),
        FROZEN_CONTROL_SUCCESSOR_ROOT / "R004/review-result.json": (
            "8271a124974f0a87412afda30c3c04cd5f7f20693bfe2b78038953fb1f766d9b",
            9_208,
        ),
        FROZEN_CONTROL_SUCCESSOR_ROOT / "R004/independent-review.json": (
            "364c83ab29a8ff96fba1c3a7c5b9340115bb1d2f62a94d30188ebff2da03fd90",
            9_508,
        ),
    },
}

SCRIPT_REL = Path(
    "scripts/build_walksafe_fp046_r002_seq77_78_review_20260823.py"
)
TEST_REL = Path(
    "tests/test_build_walksafe_fp046_r002_seq77_78_review_20260823.py"
)
REANCHOR_SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py"
)
REANCHOR_TEST_REL = Path(
    "tests/test_apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py"
)
GATE_SCRIPT_REL = Path(
    "scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py"
)
GATE_TEST_REL = Path(
    "tests/test_walksafe_fp046_r002_goal_start_gate_20260823.py"
)
STARTED_SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp046_r002_goal_started_seq78_20260823.py"
)
STARTED_TEST_REL = Path(
    "tests/test_apply_walksafe_fp046_r002_goal_started_seq78_20260823.py"
)
MODIFIED_CONTROL_PATHS = {
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("tests/test_repository_catalogs.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
}
ADDED_CONTROL_PATHS = (
    SCRIPT_REL,
    TEST_REL,
    REANCHOR_SCRIPT_REL,
    REANCHOR_TEST_REL,
    GATE_SCRIPT_REL,
    GATE_TEST_REL,
    STARTED_SCRIPT_REL,
    STARTED_TEST_REL,
)
CURRENT_CONTROL_PATHS = (*FROZEN_R011_COHORT_PATHS, *ADDED_CONTROL_PATHS)
PUBLICATION_CONSISTENCY_LEASE_PATHS = (
    *CURRENT_CONTROL_PATHS,
    *R005_REVIEW_PATHS,
    *SESSION_ARTIFACT_PATHS,
)

USER_AUTHORIZATION_QUOTE = "계획 세워서 단계적으로 진행해 어떻게 진행해야 하는지 알지?"
REANCHOR_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-20260823-001"
)
STARTED_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-001"
PROJECTED_TRANSITION = {
    "control_reanchor": {
        "sequence": 77,
        "event_id": REANCHOR_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "subject_goal_id": GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "contract_id": "WS-FP046-R002-INTERNAL-START-GATE-R002",
    },
    "goal_started": {
        "sequence": 78,
        "event_id": STARTED_EVENT_ID,
        "event_type": "GOAL_STARTED",
        "subject_goal_id": GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "status_changes": {GOAL_ID: "IN_PROGRESS"},
        "start_gate_required_status": "PASS",
    },
}

ASSIGNER_ID = "codex-root-fp046-r002-seq77-78-r006-assigner-20260823"
ASSIGNER_TASK = "/root"
EXECUTOR_ID = "codex-fp046-r002-seq77-78-r006-control-executor-20260823"
EXECUTOR_TASK = "/root/seq77_78_control_review"
REVIEWER_ID = "codex-fp046-r002-seq77-78-r006-independent-reviewer-20260823"
REVIEWER_TASK = "/root/seq77_78_final_review"

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

LOCKED_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
POST_SEQ77_REGRESSION_TARGETS = (
    (
        "tests/test_walksafe_project_continuation_v2_4.py::"
        "WalkSafeFp046R002Seq77Seq78BoundaryTest::"
        "test_exact_seq77_78_prefix_survives_later_suffix"
    ),
    (
        "tests/test_walksafe_project_continuation_v2_4.py::"
        "WalkSafeFp046R002Seq77Seq78BoundaryTest::"
        "test_frozen_r011_predecessor_is_byte_exact"
    ),
    (
        "tests/test_walksafe_project_continuation_v2_4.py::"
        "WalkSafeFp046R002Seq77Seq78BoundaryTest::"
        "test_later_suffix_cannot_hide_seq77_authority_tampering"
    ),
    (
        "tests/test_walksafe_project_continuation_v2_4.py::"
        "WalkSafeFp046R002Seq77Seq78BoundaryTest::"
        "test_private_gate_contract_has_exact_five_control_checks"
    ),
    (
        "tests/test_walksafe_project_continuation_v2_4.py::"
        "WalkSafeFp046R002Seq77Seq78BoundaryTest::"
        "test_review_cohort_reanchors_frozen_r011_to_current_control"
    ),
    (
        "tests/test_walksafe_project_continuation_v2_4.py::"
        "WalkSafeFp046R002Seq77Seq78BoundaryTest::"
        "test_reviewed_seq78_fixture_uses_frozen_seq76_bytes"
    ),
    (
        "tests/test_walksafe_project_continuation_v2_4.py::"
        "WalkSafeFp046R002Seq77Seq78BoundaryTest::"
        "test_seq79_replay_and_unknown_type_remain_fail_closed"
    ),
    (
        "tests/test_walksafe_project_continuation_v2_4.py::"
        "WalkSafeFp046R002Seq77Seq78BoundaryTest::"
        "test_validate_start_gate_uses_the_runner_receipt_path"
    ),
    (
        "tests/test_walksafe_goal_graph_v2_4.py::"
        "WalkSafeFp046R002StartGraphTest"
    ),
)
CHECKS = (
    {
        "check_id": "CONTINUATION",
        "command": (
            f"PYTHONPATH=. {LOCKED_PYTHON} -B "
            "scripts/check_walksafe_project_continuation_v2_4.py --root . "
            "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json"
        ),
    },
    {
        "check_id": "GOAL_GRAPH",
        "command": (
            f"PYTHONPATH=. {LOCKED_PYTHON} -B "
            "scripts/check_walksafe_goal_graph_v2_4.py --root . --checkpoint "
            "docs/control/walksafe-project-continuation-checkpoint.json"
        ),
    },
    {
        "check_id": "TEST_LAYER_REGISTRY_VALIDATE",
        "command": (
            f"PYTHON_BIN={LOCKED_PYTHON} bash "
            "scripts/run_walksafe_test_layers_current.sh validate"
        ),
    },
    {
        "check_id": "ROOT_FP046_R002_CONTROL_REGRESSION",
        "command": (
            f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {LOCKED_PYTHON} -B -m "
            "pytest -p no:cacheprovider -q "
            "tests/test_build_walksafe_fp046_r002_seq77_78_review_20260823.py "
            "tests/test_apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py "
            "tests/test_walksafe_fp046_r002_goal_start_gate_20260823.py "
            "tests/test_apply_walksafe_fp046_r002_goal_started_seq78_20260823.py "
            "tests/test_repository_catalogs.py && "
            f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {LOCKED_PYTHON} -B -m "
            "pytest -p no:cacheprovider -q "
            + " ".join(POST_SEQ77_REGRESSION_TARGETS)
        ),
    },
    {
        "check_id": "REPOSITORY_STATE",
        "command": (
            ': "${WALKSAFE_GATE_EVENT_ID:?required}" && '
            f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {LOCKED_PYTHON} -B "
            "scripts/check_walksafe_project_continuation_v2_4.py --root . "
            "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json "
            '--print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"'
        ),
    },
)


@dataclass(frozen=True)
class FrozenControlContext:
    control_code_cohort: tuple[dict[str, Any], ...]
    control_code_cohort_sha256: str


@dataclass(frozen=True)
class FrozenR011Context:
    root: Path
    current: FrozenControlContext
    review_bindings: tuple[dict[str, Any], ...]
    control_code_successors: tuple[dict[str, Any], ...]
    managed_sources_by_round: dict[str, tuple[dict[str, Any], ...]]


@dataclass(frozen=True)
class ReviewContext:
    root: Path
    frozen_r011_context: FrozenR011Context
    frozen_r011_review_bindings: tuple[dict[str, Any], ...]
    frozen_r011_cohort: tuple[dict[str, Any], ...]
    current_control_cohort: tuple[dict[str, Any], ...]
    current_control_cohort_sha256: str
    control_code_successors: tuple[dict[str, Any], ...]
    added_control_code_bindings: tuple[dict[str, Any], ...]
    authorization_binding: dict[str, Any]
    contract_binding: dict[str, Any]
    superseded_review_assignments: tuple[dict[str, Any], ...]
    approved_r005_review_bindings: tuple[dict[str, Any], ...]
    session_artifact_bindings: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class ValidatedReview:
    context: ReviewContext
    assignment_raw: bytes
    result_raw: bytes
    independent_raw: bytes
    reviewed_at: datetime


def _strict_json_equal(actual: Any, expected: Any) -> bool:
    if type(actual) is not type(expected):
        return False
    if type(expected) is dict:
        return set(actual) == set(expected) and all(
            _strict_json_equal(actual[key], expected[key]) for key in expected
        )
    if type(expected) is list:
        return len(actual) == len(expected) and all(
            _strict_json_equal(left, right)
            for left, right in zip(actual, expected, strict=True)
        )
    return bool(actual == expected)


def _require_exact_json(actual: Any, expected: Any, label: str) -> None:
    require(_strict_json_equal(actual, expected), f"{label} differs")


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


def _safe_relative(relative: Path, label: str) -> None:
    require(
        not relative.is_absolute()
        and bool(relative.parts)
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"unsafe {label} path: {relative}",
    )


def _trusted_root(root: Path) -> Path:
    candidate = Path(os.path.abspath(os.fspath(root)))
    try:
        info = candidate.lstat()
    except OSError as exc:
        raise ReviewError(f"review root is unavailable: {candidate}") from exc
    require(
        stat.S_ISDIR(info.st_mode)
        and not stat.S_ISLNK(info.st_mode)
        and info.st_uid == os.geteuid(),
        f"review root must be a current-owner non-symlink directory: {candidate}",
    )
    return candidate


def _directory_authority_identity(
    info: os.stat_result,
) -> tuple[int, int, int, int]:
    return info.st_dev, info.st_ino, info.st_mode, info.st_uid


def _directory_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_nlink,
        info.st_uid,
        info.st_gid,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _stable_directory_identity(
    identity: tuple[int, ...],
) -> tuple[int, ...]:
    return identity[:6]


def _close_owned_descriptor(descriptor: int) -> None:
    # A close error is ownership-ambiguous: the numeric descriptor may already
    # have been reused.  The one-shot CLI reports the error and never retries it.
    os.close(descriptor)


def _live_parent_walk_identity(
    root: Path,
    relative: Path,
    *,
    label: str,
) -> tuple[tuple[int, ...], ...]:
    _safe_relative(relative, label)
    trusted = _trusted_root(root)
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    cloexec = getattr(os, "O_CLOEXEC", 0)
    require(nofollow != 0, "O_NOFOLLOW support is required")
    root_info = trusted.lstat()
    descriptor = os.open(
        trusted, os.O_RDONLY | os.O_DIRECTORY | nofollow | cloexec
    )
    try:
        opened_root = os.fstat(descriptor)
        require(
            stat.S_ISDIR(opened_root.st_mode)
            and opened_root.st_uid == os.geteuid()
            and _directory_identity(opened_root)
            == _directory_identity(root_info),
            f"{label} root changed during live walk: {relative}",
        )
        identities = [_directory_identity(opened_root)]
        for part in relative.parent.parts:
            child: int | None = None
            try:
                child = os.open(
                    part,
                    os.O_RDONLY | os.O_DIRECTORY | nofollow | cloexec,
                    dir_fd=descriptor,
                )
                child_info = os.fstat(child)
                require(
                    stat.S_ISDIR(child_info.st_mode)
                    and child_info.st_uid == os.geteuid()
                    and stat.S_IMODE(child_info.st_mode)
                    in REVIEW_DIRECTORY_MODES,
                    f"unsafe {label} parent: {relative}",
                )
                identities.append(_directory_identity(child_info))
                previous = descriptor
                descriptor = child
                child = None
                _close_owned_descriptor(previous)
            finally:
                if child is not None:
                    try:
                        _close_owned_descriptor(child)
                    except OSError:
                        pass
        return tuple(identities)
    finally:
        try:
            _close_owned_descriptor(descriptor)
        except OSError:
            pass


def _open_parent_directory(
    root: Path,
    relative: Path,
    *,
    create: bool,
    label: str,
) -> int:
    _safe_relative(relative, label)
    trusted = _trusted_root(root)
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    cloexec = getattr(os, "O_CLOEXEC", 0)
    require(nofollow != 0, "O_NOFOLLOW support is required")
    root_info = trusted.lstat()
    descriptor = os.open(trusted, os.O_RDONLY | os.O_DIRECTORY | nofollow | cloexec)
    try:
        opened_root = os.fstat(descriptor)
        require(
            stat.S_ISDIR(root_info.st_mode)
            and root_info.st_uid == os.geteuid()
            and stat.S_ISDIR(opened_root.st_mode)
            and opened_root.st_uid == os.geteuid()
            and _directory_identity(opened_root)
            == _directory_identity(root_info),
            f"{label} root changed before open: {relative}",
        )
        for part in relative.parent.parts:
            created = False
            if create:
                try:
                    os.mkdir(part, 0o755, dir_fd=descriptor)
                    created = True
                except FileExistsError:
                    pass
                if created:
                    os.fsync(descriptor)
            child: int | None = None
            try:
                child = os.open(
                    part,
                    os.O_RDONLY | os.O_DIRECTORY | nofollow | cloexec,
                    dir_fd=descriptor,
                )
            except FileNotFoundError as exc:
                raise ReviewError(f"review input is missing: {relative}") from exc
            except OSError as exc:
                raise ReviewError(f"unsafe {label} parent: {relative}") from exc
            try:
                child_info = os.fstat(child)
                require(
                    stat.S_ISDIR(child_info.st_mode)
                    and child_info.st_uid == os.geteuid()
                    and stat.S_IMODE(child_info.st_mode)
                    in REVIEW_DIRECTORY_MODES,
                    f"unsafe {label} parent: {relative}",
                )
                if created:
                    os.fsync(child)
                previous = descriptor
                descriptor = child
                child = None
                _close_owned_descriptor(previous)
            except BaseException:
                if child is not None:
                    try:
                        _close_owned_descriptor(child)
                    except OSError:
                        pass
                raise
        return descriptor
    except BaseException:
        try:
            _close_owned_descriptor(descriptor)
        except OSError:
            pass
        raise


def _file_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_nlink,
        info.st_uid,
        info.st_gid,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


@dataclass
class _RetainedReviewInput:
    relative: Path
    descriptor: int
    parent_descriptor: int
    parent_authority: tuple[int, int, int, int]
    parent_identity: tuple[int, ...]
    parent_walk_identity: tuple[tuple[int, ...], ...]
    identity: tuple[int, ...]
    content: bytes


@dataclass
class _RetainedAbsentReviewInput:
    relative: Path
    parent_descriptor: int
    parent_identity: tuple[int, ...]
    parent_walk_identity: tuple[tuple[int, ...], ...]


def _pread_retained(descriptor: int, size: int, label: str) -> bytes:
    chunks: list[bytes] = []
    offset = 0
    while offset < size:
        chunk = os.pread(descriptor, min(64 * 1024, size - offset), offset)
        require(
            bool(chunk),
            f"{label} changed: retained descriptor became short",
        )
        chunks.append(chunk)
        offset += len(chunk)
    return b"".join(chunks)


class _RetainedReviewInputCohort:
    def __init__(self, root: Path, *, output_relative: Path) -> None:
        trusted = _trusted_root(root)
        self.root = trusted
        self.root_authority = _directory_authority_identity(trusted.lstat())
        self.root_identity = _directory_identity(trusted.lstat())
        self.output_relative = output_relative
        self.output_parent_walk_identity = _live_parent_walk_identity(
            trusted,
            output_relative,
            label="retained review output",
        )
        self.inputs: dict[Path, _RetainedReviewInput] = {}
        self.absent_inputs: dict[Path, _RetainedAbsentReviewInput] = {}
        self.sealed = False

    def _require_root(self, root: Path) -> None:
        candidate = _trusted_root(root)
        require(
            candidate == self.root
            and _directory_authority_identity(candidate.lstat())
            == self.root_authority,
            "retained review input cohort root differs",
        )
        require(
            _directory_identity(candidate.lstat()) == self.root_identity,
            "retained review input cohort root namespace changed",
        )

    @staticmethod
    def _expected_modes(
        expected_mode: int | frozenset[int],
    ) -> frozenset[int]:
        return (
            expected_mode
            if isinstance(expected_mode, frozenset)
            else frozenset({expected_mode})
        )

    def _capture_file(
        self,
        relative: Path,
        *,
        expected_mode: int | frozenset[int],
        maximum_bytes: int,
        label: str,
    ) -> _RetainedReviewInput:
        require(not self.sealed, f"retained review input inventory grew: {relative}")
        parent_fd = _open_parent_directory(
            self.root, relative, create=False, label=label
        )
        descriptor: int | None = None
        try:
            parent_identity = _directory_identity(os.fstat(parent_fd))
            parent_walk_identity = _live_parent_walk_identity(
                self.root, relative, label=label
            )
            require(
                parent_walk_identity[-1] == parent_identity,
                f"{label} parent changed before retained capture: {relative}",
            )
            try:
                before = os.stat(
                    relative.name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
                descriptor = os.open(
                    relative.name,
                    os.O_RDONLY
                    | getattr(os, "O_NOFOLLOW", 0)
                    | getattr(os, "O_NONBLOCK", 0)
                    | getattr(os, "O_CLOEXEC", 0),
                    dir_fd=parent_fd,
                )
            except FileNotFoundError as exc:
                raise ReviewError(
                    f"review input is missing: {relative}"
                ) from exc
            except OSError as exc:
                raise ReviewError(
                    f"review input cannot be read safely: {relative}"
                ) from exc
            opened = os.fstat(descriptor)
            modes = self._expected_modes(expected_mode)
            require(
                stat.S_ISREG(opened.st_mode)
                and not stat.S_ISLNK(before.st_mode)
                and opened.st_nlink == 1
                and opened.st_uid == os.geteuid(),
                f"{label} must be a current-owner single-link regular file: {relative}",
            )
            require(
                stat.S_IMODE(opened.st_mode) in modes,
                f"{label} mode differs: {relative}",
            )
            require(
                opened.st_size <= maximum_bytes,
                f"{label} exceeds the size limit: {relative}",
            )
            identity = _file_identity(opened)
            require(
                _file_identity(before) == identity,
                f"{label} changed before retained open: {relative}",
            )
            content = _pread_retained(descriptor, opened.st_size, label)
            after = os.fstat(descriptor)
            named = os.stat(
                relative.name, dir_fd=parent_fd, follow_symlinks=False
            )
            require(
                _file_identity(after) == identity
                and _file_identity(named) == identity,
                f"{label} changed during retained capture: {relative}",
            )
            require(
                _directory_identity(os.fstat(parent_fd)) == parent_identity
                and _live_parent_walk_identity(
                    self.root, relative, label=label
                )
                == parent_walk_identity,
                f"{label} parent changed during retained capture: {relative}",
            )
            retained = _RetainedReviewInput(
                relative=relative,
                descriptor=descriptor,
                parent_descriptor=parent_fd,
                parent_authority=_directory_authority_identity(
                    os.fstat(parent_fd)
                ),
                parent_identity=parent_identity,
                parent_walk_identity=parent_walk_identity,
                identity=identity,
                content=content,
            )
            try:
                self.inputs[relative] = retained
            finally:
                if dict.get(self.inputs, relative) is retained:
                    descriptor = None
                    parent_fd = -1
            return retained
        finally:
            if descriptor is not None:
                try:
                    _close_owned_descriptor(descriptor)
                except BaseException:
                    pass
            if parent_fd >= 0:
                try:
                    _close_owned_descriptor(parent_fd)
                except BaseException:
                    pass

    def _verify_file(self, retained: _RetainedReviewInput) -> None:
        relative = retained.relative
        current_walk = _live_parent_walk_identity(
            self.root,
            relative,
            label="retained review input",
        )
        require(
            _directory_identity(os.fstat(retained.parent_descriptor))
            == retained.parent_identity
            and current_walk == retained.parent_walk_identity,
            f"retained review input changed: {relative}",
        )
        current_parent = _open_parent_directory(
            self.root,
            relative,
            create=False,
            label="retained review input",
        )
        try:
            named = os.stat(
                relative.name,
                dir_fd=current_parent,
                follow_symlinks=False,
            )
            opened_before = os.fstat(retained.descriptor)
            content = _pread_retained(
                retained.descriptor,
                len(retained.content),
                "retained review input",
            )
            opened_after = os.fstat(retained.descriptor)
            named_after = os.stat(
                relative.name,
                dir_fd=current_parent,
                follow_symlinks=False,
            )
            final_walk = _live_parent_walk_identity(
                self.root,
                relative,
                label="retained review input",
            )
            require(
                _directory_identity(os.fstat(current_parent))
                == retained.parent_identity
                and _file_identity(named) == retained.identity
                and _file_identity(named_after) == retained.identity
                and _file_identity(opened_before) == retained.identity
                and _file_identity(opened_after) == retained.identity
                and content == retained.content,
                f"retained review input changed: {relative}",
            )
            require(
                final_walk == retained.parent_walk_identity,
                f"retained review input changed: {relative}",
            )
        finally:
            _close_owned_descriptor(current_parent)

    def accept_output_publication(self, root: Path, relative: Path) -> None:
        candidate = _trusted_root(root)
        require(
            candidate == self.root
            and relative == self.output_relative
            and _directory_authority_identity(candidate.lstat())
            == self.root_authority,
            "retained review output authority differs",
        )
        previous_walk = self.output_parent_walk_identity
        current_walk = _live_parent_walk_identity(
            self.root,
            relative,
            label="retained review output",
        )
        require(
            current_walk[:-1] == previous_walk[:-1]
            and _stable_directory_identity(current_walk[-1])
            == _stable_directory_identity(previous_walk[-1]),
            "retained review input changed before output publication",
        )
        previous_parent = previous_walk[-1]
        current_parent = current_walk[-1]
        for retained in self.absent_inputs.values():
            require(
                _stable_directory_identity(retained.parent_identity)
                != _stable_directory_identity(previous_parent),
                "retained absent review input shares the mutable output "
                f"parent: {retained.relative}",
            )

        def rebased_walk(
            saved: tuple[tuple[int, ...], ...],
            input_relative: Path,
        ) -> tuple[tuple[int, ...], ...]:
            expected = tuple(
                current_parent
                if _stable_directory_identity(identity)
                == _stable_directory_identity(previous_parent)
                else identity
                for identity in saved
            )
            require(
                _live_parent_walk_identity(
                    self.root,
                    input_relative,
                    label="retained review input",
                )
                == expected,
                f"retained review input changed: {input_relative}",
            )
            return expected

        for retained in self.inputs.values():
            retained.parent_walk_identity = rebased_walk(
                retained.parent_walk_identity,
                retained.relative,
            )
            retained.parent_identity = retained.parent_walk_identity[-1]
            retained.parent_authority = _directory_authority_identity(
                os.fstat(retained.parent_descriptor)
            )
            require(
                _directory_identity(os.fstat(retained.parent_descriptor))
                == retained.parent_identity,
                f"retained review input changed: {retained.relative}",
            )
        for retained in self.absent_inputs.values():
            retained.parent_walk_identity = rebased_walk(
                retained.parent_walk_identity,
                retained.relative,
            )
            retained.parent_identity = retained.parent_walk_identity[-1]
            require(
                _directory_identity(os.fstat(retained.parent_descriptor))
                == retained.parent_identity,
                "retained absent review input parent changed: "
                f"{retained.relative}",
            )
        self.output_parent_walk_identity = current_walk
        if len(current_walk) == 1:
            self.root_identity = current_walk[0]

    def snapshot(
        self,
        root: Path,
        relative: Path,
        *,
        expected_mode: int | frozenset[int],
        maximum_bytes: int,
        label: str,
    ) -> tuple[bytes, tuple[int, ...]]:
        self._require_root(root)
        retained = self.inputs.get(relative)
        if retained is None:
            require(
                relative not in self.absent_inputs,
                f"retained review input changed from absent: {relative}",
            )
            retained = self._capture_file(
                relative,
                expected_mode=expected_mode,
                maximum_bytes=maximum_bytes,
                label=label,
            )
        modes = self._expected_modes(expected_mode)
        require(
            stat.S_IMODE(retained.identity[2]) in modes
            and len(retained.content) <= maximum_bytes,
            f"retained review input contract differs: {relative}",
        )
        self._verify_file(retained)
        return retained.content, retained.identity

    def require_absent(self, root: Path, relative: Path) -> None:
        self._require_root(root)
        require(
            relative not in self.inputs,
            f"retained review input changed from present: {relative}",
        )
        retained = self.absent_inputs.get(relative)
        if retained is None:
            require(
                not self.sealed,
                f"retained review input inventory grew: {relative}",
            )
            parent_fd = _open_parent_directory(
                self.root,
                relative,
                create=False,
                label="retained absent review input",
            )
            try:
                parent_identity = _directory_identity(os.fstat(parent_fd))
                parent_walk_identity = _live_parent_walk_identity(
                    self.root,
                    relative,
                    label="retained absent review input",
                )
                require(
                    parent_walk_identity[-1] == parent_identity,
                    "retained absent review input parent changed before "
                    f"absence check: {relative}",
                )
                try:
                    os.stat(
                        relative.name,
                        dir_fd=parent_fd,
                        follow_symlinks=False,
                    )
                except FileNotFoundError:
                    pass
                else:
                    raise ReviewError(
                        f"retained review input must be absent: {relative}"
                    )
                require(
                    _directory_identity(os.fstat(parent_fd))
                    == parent_identity
                    and _live_parent_walk_identity(
                        self.root,
                        relative,
                        label="retained absent review input",
                    )
                    == parent_walk_identity,
                    "retained absent review input parent changed during "
                    f"absence check: {relative}",
                )
                retained = _RetainedAbsentReviewInput(
                    relative=relative,
                    parent_descriptor=parent_fd,
                    parent_identity=parent_identity,
                    parent_walk_identity=parent_walk_identity,
                )
                try:
                    self.absent_inputs[relative] = retained
                finally:
                    if dict.get(self.absent_inputs, relative) is retained:
                        parent_fd = -1
            finally:
                if parent_fd >= 0:
                    try:
                        _close_owned_descriptor(parent_fd)
                    except OSError:
                        pass
        before_parent = _directory_identity(
            os.fstat(retained.parent_descriptor)
        )
        before_walk = _live_parent_walk_identity(
            self.root,
            relative,
            label="retained absent review input",
        )
        require(
            before_parent == retained.parent_identity
            and before_walk == retained.parent_walk_identity,
            f"retained absent review input parent changed: {relative}",
        )
        try:
            os.stat(
                relative.name,
                dir_fd=retained.parent_descriptor,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            pass
        else:
            raise ReviewError(f"retained review input appeared: {relative}")
        require(
            _directory_identity(os.fstat(retained.parent_descriptor))
            == retained.parent_identity
            and _live_parent_walk_identity(
                self.root,
                relative,
                label="retained absent review input",
            )
            == retained.parent_walk_identity,
            f"retained absent review input changed: {relative}",
        )

    def seal(self) -> None:
        require(bool(self.inputs), "retained review input cohort is empty")
        self.sealed = True
        self.verify()

    def verify(self) -> None:
        self._require_root(self.root)
        for retained in self.inputs.values():
            self._verify_file(retained)
        for relative in tuple(self.absent_inputs):
            self.require_absent(self.root, relative)

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None

        def close_owned(owner: Any, attribute: str) -> None:
            nonlocal first
            descriptor = getattr(owner, attribute)
            if descriptor < 0:
                return
            setattr(owner, attribute, -1)
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc

        for retained in tuple(dict.values(self.inputs)):
            close_owned(retained, "descriptor")
            close_owned(retained, "parent_descriptor")
        for retained in tuple(dict.values(self.absent_inputs)):
            close_owned(retained, "parent_descriptor")
        for relative, retained in tuple(dict.items(self.inputs)):
            if retained.descriptor < 0 and retained.parent_descriptor < 0:
                dict.pop(self.inputs, relative, None)
        for relative, retained in tuple(dict.items(self.absent_inputs)):
            if retained.parent_descriptor < 0:
                dict.pop(self.absent_inputs, relative, None)
        if primary is None and first is not None:
            raise first

    def has_open_descriptors(self) -> bool:
        return any(
            retained.descriptor >= 0 or retained.parent_descriptor >= 0
            for retained in self.inputs.values()
        ) or any(
            retained.parent_descriptor >= 0
            for retained in self.absent_inputs.values()
        )


def _stable_regular_snapshot(
    root: Path,
    relative: Path,
    *,
    expected_mode: int | frozenset[int],
    maximum_bytes: int,
    label: str,
) -> tuple[bytes, tuple[int, ...]]:
    retained = _RETAINED_REVIEW_INPUT_COHORT.get()
    if isinstance(retained, _RetainedReviewInputCohort):
        retained._require_root(root)
        if relative != retained.output_relative:
            return retained.snapshot(
                root,
                relative,
                expected_mode=expected_mode,
                maximum_bytes=maximum_bytes,
                label=label,
            )
    parent_fd = _open_parent_directory(
        root, relative, create=False, label=label
    )
    descriptor: int | None = None
    try:
        try:
            before = os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
            descriptor = os.open(
                relative.name,
                os.O_RDONLY
                | getattr(os, "O_NOFOLLOW", 0)
                | getattr(os, "O_NONBLOCK", 0)
                | getattr(os, "O_CLOEXEC", 0),
                dir_fd=parent_fd,
            )
        except FileNotFoundError as exc:
            raise ReviewError(f"review input is missing: {relative}") from exc
        except OSError as exc:
            raise ReviewError(f"review input cannot be read safely: {relative}") from exc
        opened = os.fstat(descriptor)
        require(
            stat.S_ISREG(opened.st_mode)
            and opened.st_nlink == 1
            and not stat.S_ISLNK(before.st_mode),
            f"{label} must be a single-link regular file: {relative}",
        )
        require(
            opened.st_uid == os.geteuid(),
            f"{label} owner differs: {relative}",
        )
        expected_modes = (
            expected_mode
            if isinstance(expected_mode, frozenset)
            else frozenset({expected_mode})
        )
        mode_description = "/".join(
            f"{mode:04o}" for mode in sorted(expected_modes)
        )
        require(
            stat.S_IMODE(opened.st_mode) in expected_modes,
            f"{label} mode {mode_description} required: {relative}",
        )
        require(
            opened.st_size <= maximum_bytes,
            f"{label} exceeds the size limit: {relative}",
        )
        require(
            _file_identity(before) == _file_identity(opened),
            f"{label} changed before it was opened: {relative}",
        )
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(descriptor, min(64 * 1024, maximum_bytes + 1 - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            require(
                total <= maximum_bytes,
                f"{label} exceeds the size limit: {relative}",
            )
        after = os.fstat(descriptor)
        current_parent_fd = _open_parent_directory(
            root, relative, create=False, label=label
        )
        try:
            current = os.stat(
                relative.name,
                dir_fd=current_parent_fd,
                follow_symlinks=False,
            )
            require(
                total == opened.st_size
                and _file_identity(after) == _file_identity(opened)
                and _directory_identity(os.fstat(current_parent_fd))
                == _directory_identity(os.fstat(parent_fd))
                and _file_identity(current) == _file_identity(opened),
                f"{label} changed while being read: {relative}",
            )
        finally:
            _close_owned_descriptor(current_parent_fd)
        closing = descriptor
        descriptor = None
        try:
            _close_owned_descriptor(closing)
        except OSError as exc:
            raise ReviewError(f"{label} close failed: {relative}") from exc
        return b"".join(chunks), _file_identity(opened)
    finally:
        if descriptor is not None:
            try:
                _close_owned_descriptor(descriptor)
            except OSError:
                pass
        try:
            _close_owned_descriptor(parent_fd)
        except OSError:
            pass


def _stable_regular_bytes(
    root: Path,
    relative: Path,
    *,
    expected_mode: int | frozenset[int],
    maximum_bytes: int,
    label: str,
) -> bytes:
    return _stable_regular_snapshot(
        root,
        relative,
        expected_mode=expected_mode,
        maximum_bytes=maximum_bytes,
        label=label,
    )[0]


def _evidence_bytes(
    root: Path, relative: Path, *, expected_mode: int = 0o644
) -> bytes:
    return _stable_regular_bytes(
        root,
        relative,
        expected_mode=expected_mode,
        maximum_bytes=MAXIMUM_EVIDENCE_BYTES,
        label="review evidence",
    )


def _checkpoint_bytes(root: Path) -> bytes:
    return _stable_regular_bytes(
        root,
        Path(SOURCE_CHECKPOINT["path"]),
        expected_mode=CHECKPOINT_REVIEW_SOURCE_MODES,
        maximum_bytes=MAXIMUM_CHECKPOINT_BYTES,
        label="continuation checkpoint",
    )


def _require_absent_evidence(
    root: Path, relative: Path, *, round_name: str
) -> None:
    retained = _RETAINED_REVIEW_INPUT_COHORT.get()
    if isinstance(retained, _RetainedReviewInputCohort):
        retained.require_absent(root, relative)
        return
    parent_fd = _open_parent_directory(
        root,
        relative,
        create=False,
        label=f"abandoned {round_name} review output",
    )
    try:
        try:
            os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError:
            return
        raise ReviewError(
            f"abandoned {round_name} produced an output: {relative}"
        )
    finally:
        try:
            _close_owned_descriptor(parent_fd)
        except OSError:
            pass


def raw(root: Path, relative: Path) -> bytes:
    if relative == GOAL_REL:
        expected_mode = NONEXECUTABLE_REVIEW_SOURCE_MODES
    elif relative == Path("scripts/run_walksafe_test_layers_current.sh"):
        expected_mode = EXECUTABLE_REVIEW_SOURCE_MODES
    else:
        require(
            relative in CURRENT_CONTROL_PATHS,
            f"unclassified review source path: {relative}",
        )
        expected_mode = NONEXECUTABLE_REVIEW_SOURCE_MODES
    return _stable_regular_bytes(
        root,
        relative,
        expected_mode=expected_mode,
        maximum_bytes=MAXIMUM_EVIDENCE_BYTES,
        label="review source",
    )


def document(
    root: Path, relative: Path, *, expected_mode: int = 0o644
) -> tuple[dict[str, Any], bytes]:
    value = _evidence_bytes(root, relative, expected_mode=expected_mode)
    return strict_json_bytes(value, relative.as_posix()), value


def binding(relative: Path, value: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": bytes_sha256(value),
        "byte_length": len(value),
    }


def _bind_paths(root: Path, paths: Sequence[Path]) -> tuple[dict[str, Any], ...]:
    rows = tuple(binding(path, raw(root, path)) for path in paths)
    require(
        len(rows) == len(paths)
        and len({row["path"] for row in rows}) == len(paths),
        "current control cohort is not exact",
    )
    return rows


def _authorization_document() -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP046-R002-SEQ77-78-AUTHORIZATION-20260823-001",
        "evidence_type": "USER_CONTINUATION_AUTHORIZATION",
        "goal_id": GOAL_ID,
        "authorization_quote": USER_AUTHORIZATION_QUOTE,
        "authorization_status": "AUTHORIZED_FOR_CONDITIONAL_SEQ77_78_CONTINUATION",
        "supersedes": copy.deepcopy(PREDECESSOR_AUTHORIZATION_EVIDENCE),
        "authorization_scope": {
            "authorized_actions": [
                "SEQ77_GOAL_START_CONTROL_REANCHOR",
                "PRIVATE_INITIAL_START_GATE_EXECUTION",
                "SEQ78_GOAL_STARTED_AFTER_GATE_PASS",
                "LOCAL_PRODUCT_IMPLEMENTATION_AFTER_GOAL_STARTED",
            ],
            "conditions": [
                "SEQ77_REANCHOR_MUST_PRESERVE_READY_STATUS",
                "ALL_FIVE_EVENT_SCOPED_GATE_CHECKS_MUST_PASS",
                "SEQ78_MUST_BIND_THE_EXACT_GATE_RECEIPT",
                "PRODUCT_CHANGE_MUST_OCCUR_ONLY_AFTER_SEQ78",
            ],
            "excluded_actions": [
                "FORMAL_TEST_PASS_CLAIM",
                "ACTUAL_DEVICE_CREDIT",
                "EXTERNAL_REVIEW_CREDIT",
                "DEPLOYMENT",
                "RELEASE_APPROVAL",
            ],
        },
        "claim_boundary": {
            "goal_started": False,
            "start_gate_status": "NOT_RUN",
            "product_implementation_credit_delta": 0,
            "artifact_completion_credit_delta": 0,
            "test_credit_delta": 0,
            "formal_test_credit_delta": 0,
            "actual_device_credit_delta": 0,
            "external_review_credit_delta": 0,
            "deployment_credit_delta": 0,
            "approval_credit_delta": 0,
            "release_credit_delta": 0,
            "release_status": "NOT_ELIGIBLE",
        },
    }


def build_authorization() -> str:
    return json_text(_authorization_document())


def validate_authorization(value: Mapping[str, Any], value_raw: bytes) -> None:
    require(
        value_raw == json_text(value).encode(),
        "seq77/78 authorization is noncanonical",
    )
    _require_exact_json(
        value, _authorization_document(), "seq77/78 authorization"
    )


def _contract_document() -> dict[str, Any]:
    return {
        "schema_version": "1.1",
        "document_id": "WS-FP046-R002-INITIAL-START-GATE-CONTRACT-20260823-002",
        "contract_id": "WS-FP046-R002-INTERNAL-START-GATE-R002",
        "contract_version": "2026-08-23.1",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": GOAL_SHA256,
        "gate_purpose": "INITIAL_START",
        "successor_reason_code": (
            "SEQ76_READY_TRUST_ANCHOR_AND_CURRENT_CONTROL_COHORT_REQUIRED"
        ),
        "supersedes": copy.deepcopy(CONTRACT_R001_EVIDENCE),
        "ordered_checks": copy.deepcopy(list(CHECKS)),
        "claim_boundary": {
            "seq76_ready_event_modified": False,
            "binding_replacement_effective_without_reanchor_event": False,
            "append_only_reanchor_event_required": True,
            "goal_started": False,
            "start_gate_status": "NOT_RUN",
            "product_implementation_credit_delta": 0,
            "artifact_completion_credit_delta": 0,
            "test_credit_delta": 0,
            "formal_test_credit_delta": 0,
            "approval_credit_delta": 0,
            "actual_event_credit_delta": 0,
            "release_status": "NOT_ELIGIBLE",
            "intended_use": "ADD_ONLY_SEQ77_REANCHOR_THEN_PRIVATE_GATE_AND_SEQ78_START",
        },
    }


def build_contract() -> str:
    return json_text(_contract_document())


def validate_contract(value: Mapping[str, Any], value_raw: bytes) -> None:
    require(
        value_raw == json_text(value).encode(),
        "FP046 R002 start-gate contract is noncanonical",
    )
    _require_exact_json(
        value, _contract_document(), "FP046 R002 start-gate contract"
    )


def _require_pinned_authorities(root: Path) -> None:
    goal_raw = raw(root, GOAL_REL)
    require(
        binding(GOAL_REL, goal_raw)
        == {
            "path": GOAL_REL.as_posix(),
            "sha256": GOAL_SHA256,
            "byte_length": GOAL_BYTE_LENGTH,
        },
        "FP046 R002 Goal authority differs",
    )
    predecessor_auth, predecessor_auth_raw = document(
        root, PREDECESSOR_AUTHORIZATION_REL
    )
    require(
        binding(PREDECESSOR_AUTHORIZATION_REL, predecessor_auth_raw)
        == {
            "path": PREDECESSOR_AUTHORIZATION_EVIDENCE["path"],
            "sha256": PREDECESSOR_AUTHORIZATION_EVIDENCE["file_sha256"],
            "byte_length": PREDECESSOR_AUTHORIZATION_EVIDENCE["byte_length"],
        }
        and predecessor_auth.get("document_id")
        == PREDECESSOR_AUTHORIZATION_EVIDENCE["document_id"]
        and predecessor_auth.get("authorization_status")
        == PREDECESSOR_AUTHORIZATION_EVIDENCE["authorization_status"],
        "seq72/76 authorization predecessor differs",
    )
    contract_r001, contract_r001_raw = document(root, CONTRACT_R001_REL)
    require(
        binding(CONTRACT_R001_REL, contract_r001_raw)
        == {
            "path": CONTRACT_R001_EVIDENCE["path"],
            "sha256": CONTRACT_R001_EVIDENCE["file_sha256"],
            "byte_length": CONTRACT_R001_EVIDENCE["byte_length"],
        }
        and object_sha256(contract_r001)
        == CONTRACT_R001_EVIDENCE["canonical_sha256"]
        and contract_r001.get("document_id")
        == CONTRACT_R001_EVIDENCE["document_id"]
        and contract_r001.get("contract_id")
        == CONTRACT_R001_EVIDENCE["contract_id"]
        and contract_r001.get("contract_version")
        == CONTRACT_R001_EVIDENCE["contract_version"],
        "FP046 R002 R001 contract predecessor differs",
    )


def _abandoned_review(
    root: Path,
    *,
    round_name: str,
    round_id: str,
    assignment_rel: Path,
    result_rel: Path,
    independent_rel: Path,
    assignment_sha256: str,
    assignment_byte_length: int,
    supersession_reason_code: str,
    confirmed_rejection_findings: Sequence[str] = (),
) -> dict[str, Any]:
    assignment, assignment_raw = document(root, assignment_rel)
    require(
        assignment_raw == json_text(assignment).encode(),
        f"abandoned {round_name} assignment is noncanonical",
    )
    require(
        binding(assignment_rel, assignment_raw)
        == {
            "path": assignment_rel.as_posix(),
            "sha256": assignment_sha256,
            "byte_length": assignment_byte_length,
        },
        f"abandoned {round_name} assignment bytes differ",
    )
    require(
        type(assignment) is dict
        and assignment.get("schema_version") == "1.0"
        and assignment.get("evidence_type")
        == "FP046_R002_SEQ77_78_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("round_id") == round_id,
        f"abandoned {round_name} assignment identity differs",
    )
    _parse_time(assignment.get("assigned_at"), f"{round_name} assigned_at")
    for output in (result_rel, independent_rel):
        _require_absent_evidence(root, output, round_name=round_name)
    record: dict[str, Any] = {
        "round_id": round_id,
        "assignment_binding": binding(assignment_rel, assignment_raw),
        "disposition": "SUPERSEDED_WITHOUT_APPROVAL",
        "review_result_status": "NOT_CREATED",
        "independent_review_status": "NOT_CREATED",
        "supersession_reason_code": supersession_reason_code,
    }
    if confirmed_rejection_findings:
        require(
            len(set(confirmed_rejection_findings))
            == len(confirmed_rejection_findings),
            f"abandoned {round_name} confirmed rejection findings differ",
        )
        record["confirmed_rejection_findings"] = list(
            confirmed_rejection_findings
        )
    return record


def _abandoned_r001_review(root: Path) -> dict[str, Any]:
    return _abandoned_review(
        root,
        round_name="R001",
        round_id=R001_ROUND_ID,
        assignment_rel=R001_ASSIGNMENT_REL,
        result_rel=R001_RESULT_REL,
        independent_rel=R001_INDEPENDENT_REL,
        assignment_sha256=R001_ASSIGNMENT_SHA256,
        assignment_byte_length=R001_ASSIGNMENT_BYTE_LENGTH,
        supersession_reason_code=R001_SUPERSESSION_REASON_CODE,
    )


def _abandoned_r002_review(root: Path) -> dict[str, Any]:
    return _abandoned_review(
        root,
        round_name="R002",
        round_id=R002_ROUND_ID,
        assignment_rel=R002_ASSIGNMENT_REL,
        result_rel=R002_RESULT_REL,
        independent_rel=R002_INDEPENDENT_REL,
        assignment_sha256=R002_ASSIGNMENT_SHA256,
        assignment_byte_length=R002_ASSIGNMENT_BYTE_LENGTH,
        supersession_reason_code=R002_SUPERSESSION_REASON_CODE,
        confirmed_rejection_findings=R002_CONFIRMED_REJECTION_FINDINGS,
    )


def _abandoned_r003_review(root: Path) -> dict[str, Any]:
    return _abandoned_review(
        root,
        round_name="R003",
        round_id=R003_ROUND_ID,
        assignment_rel=R003_ASSIGNMENT_REL,
        result_rel=R003_RESULT_REL,
        independent_rel=R003_INDEPENDENT_REL,
        assignment_sha256=R003_ASSIGNMENT_SHA256,
        assignment_byte_length=R003_ASSIGNMENT_BYTE_LENGTH,
        supersession_reason_code=R003_SUPERSESSION_REASON_CODE,
        confirmed_rejection_findings=R003_CONFIRMED_REJECTION_FINDINGS,
    )


def _abandoned_r004_review(root: Path) -> dict[str, Any]:
    return _abandoned_review(
        root,
        round_name="R004",
        round_id=R004_ROUND_ID,
        assignment_rel=R004_ASSIGNMENT_REL,
        result_rel=R004_RESULT_REL,
        independent_rel=R004_INDEPENDENT_REL,
        assignment_sha256=R004_ASSIGNMENT_SHA256,
        assignment_byte_length=R004_ASSIGNMENT_BYTE_LENGTH,
        supersession_reason_code=R004_SUPERSESSION_REASON_CODE,
    )


def _require_exact_seq76(
    root: Path, checkpoint_raw: bytes | None = None
) -> None:
    relative = Path(SOURCE_CHECKPOINT["path"])
    if checkpoint_raw is None:
        checkpoint_raw = _checkpoint_bytes(root)
    require(
        len(checkpoint_raw) == SOURCE_CHECKPOINT["byte_length"]
        and bytes_sha256(checkpoint_raw) == SOURCE_CHECKPOINT["sha256"],
        "FP046 R002 seq77/78 source differs from exact seq76",
    )
    checkpoint = strict_json_bytes(checkpoint_raw, relative.as_posix())
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(
        type(history) is list
        and len(history) == SOURCE_CHECKPOINT["sequence"]
        and history[-1].get("sequence") == SOURCE_CHECKPOINT["sequence"]
        and history[-1].get("event_id") == SOURCE_CHECKPOINT["tail_event_id"]
        and history[-1].get("event_sha256")
        == SOURCE_CHECKPOINT["tail_event_sha256"]
        and state.get("transition_history_anchor_sha256")
        == SOURCE_CHECKPOINT["tail_event_sha256"],
        "FP046 R002 seq76 lineage differs",
    )


def _require_event_chain(checkpoint: Mapping[str, Any]) -> None:
    from scripts import check_walksafe_project_continuation_v2_4 as continuation

    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(
        type(history) is list and len(history) >= SOURCE_CHECKPOINT["sequence"],
        "checkpoint does not contain an allowed seq76/77/78 lineage",
    )
    previous = ""
    for sequence, event in enumerate(history, start=1):
        require(
            type(event) is dict
            and type(event.get("sequence")) is int
            and event.get("sequence") == sequence
            and type(event.get("previous_event_sha256")) is str
            and event.get("previous_event_sha256") == previous
            and type(event.get("event_sha256")) is str
            and event.get("event_sha256") == continuation.event_sha256(event),
            f"checkpoint event chain differs at sequence {sequence}",
        )
        previous = event["event_sha256"]
    require(
        state.get("transition_history_anchor_sha256") == previous,
        "checkpoint event-chain anchor differs",
    )
    source_tail = history[SOURCE_CHECKPOINT["sequence"] - 1]
    require(
        source_tail.get("sequence") == SOURCE_CHECKPOINT["sequence"]
        and source_tail.get("event_id") == SOURCE_CHECKPOINT["tail_event_id"]
        and source_tail.get("event_sha256")
        == SOURCE_CHECKPOINT["tail_event_sha256"],
        "checkpoint exact seq76 trust anchor differs",
    )


def _provisional_review_state(
    root: Path,
) -> tuple[dict[str, dict[str, Any]], dict[Path, bytes]] | None:
    provisional = _PROVISIONAL_REVIEW_BINDING.get()
    if not _SOURCE_VALIDATION_ACTIVE.get() or provisional is None:
        return None
    expected_root, review_binding, bytes_by_path = provisional
    candidate = _trusted_root(root)
    require(
        _directory_authority_identity(candidate.lstat()) == expected_root,
        "provisional review binding root differs",
    )
    require(
        set(bytes_by_path) == {INDEPENDENT_REL}
        and type(bytes_by_path[INDEPENDENT_REL]) is bytes,
        "provisional review byte inventory differs",
    )
    require(
        review_binding.get("independent_review")
        == binding(INDEPENDENT_REL, bytes_by_path[INDEPENDENT_REL]),
        "provisional independent review binding differs",
    )
    return review_binding, bytes_by_path


def _retained_physical_snapshot_digests(
    root: Path,
    source: Mapping[str, Any],
    reanchor: Any,
) -> dict[Path, str]:
    snapshot = source.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if type(snapshot) is dict else None
    require(
        type(paths) is list
        and all(type(path) is str and bool(path) for path in paths),
        "provisional physical snapshot path inventory differs",
    )
    expected_paths = (
        {Path(path) for path in paths}
        | reanchor.npc._git_visible_managed_paths(root)
        | set(reanchor.aggregate.CATALOG_PATHS)
    )
    retained_digests: dict[Path, str] = {}
    for relative in sorted(expected_paths, key=Path.as_posix):
        value = _stable_regular_bytes(
            root,
            relative,
            expected_mode=PHYSICAL_SNAPSHOT_SOURCE_MODES,
            maximum_bytes=MAXIMUM_EVIDENCE_BYTES,
            label="provisional physical snapshot input",
        )
        retained_digests[relative] = bytes_sha256(value)
    physical = reanchor.aggregate._snapshot_digests(root, source)
    require(
        physical == retained_digests,
        "provisional physical snapshot differs from retained inputs",
    )
    retained = _RETAINED_REVIEW_INPUT_COHORT.get()
    if isinstance(retained, _RetainedReviewInputCohort):
        retained.verify()
    return physical


def _provisional_snapshot_digests(
    root: Path,
    checkpoint: Mapping[str, Any],
    reanchor: Any,
) -> dict[Path, str] | None:
    state = _provisional_review_state(root)
    if state is None:
        return None
    _review_binding, bytes_by_path = state
    snapshot = checkpoint.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if type(snapshot) is dict else None
    require(
        type(paths) is list
        and all(type(path) is str and bool(path) for path in paths)
        and len(paths) == len(set(paths)),
        "provisional managed snapshot path inventory differs",
    )
    expected_paths = {Path(path) for path in paths}
    require(
        set(bytes_by_path).issubset(expected_paths),
        "provisional review path is outside the managed snapshot",
    )
    shadow = copy.deepcopy(dict(checkpoint))
    shadow["working_tree_snapshot"]["managed_changed_paths"] = [
        path for path in paths if Path(path) not in bytes_by_path
    ]
    physical = _retained_physical_snapshot_digests(root, shadow, reanchor)
    provisional_digests = {
        path: bytes_sha256(raw_value) for path, raw_value in bytes_by_path.items()
    }
    for path, digest in provisional_digests.items():
        if path in physical:
            require(
                physical[path] == digest,
                f"published provisional review bytes differ: {path}",
            )
            del physical[path]
    require(
        set(physical) == expected_paths - set(provisional_digests),
        "provisional physical snapshot inventory differs",
    )
    physical.update(provisional_digests)
    require(
        reanchor.npc._snapshot_hashes_from_digests(physical)
        == (snapshot.get("path_set_sha256"), snapshot.get("content_set_sha256")),
        "provisional managed snapshot hashes differ",
    )
    return physical


def _seq77_suffix_errors(
    root: Path, checkpoint: Mapping[str, Any]
) -> list[str]:
    from scripts import (
        apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823
        as reanchor,
    )

    errors = reanchor.validate_history_suffix(root, checkpoint)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    if errors or type(history) is not list or len(history) != 77:
        return errors
    try:
        expected = strict_json_bytes(
            reanchor.reconstructed_seq77_checkpoint_bytes(
                root, history[76]
            ),
            "reconstructed exact seq77 checkpoint",
        )
        _require_exact_json(
            dict(checkpoint), expected, "seq77 full checkpoint projection"
        )
        provisional_digests = _provisional_snapshot_digests(
            root, checkpoint, reanchor
        )
        reanchor.require_control_reanchored_checkpoint(
            root,
            checkpoint,
            run_external_validators=False,
            require_live_snapshot=provisional_digests is None,
        )
    except Exception as exc:
        return [str(exc)]
    return []


def _seq78_suffix_errors(
    root: Path, checkpoint: Mapping[str, Any]
) -> list[str]:
    from scripts import (
        apply_walksafe_fp046_r002_goal_started_seq78_20260823 as started,
    )

    errors = started.validate_history_suffix(root, checkpoint)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    if errors or type(history) is not list or len(history) != 78:
        return errors
    try:
        from scripts import (
            apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823
            as reanchor,
        )

        source_raw = reanchor.reconstructed_seq77_checkpoint_bytes(
            root, history[76]
        )
        source = strict_json_bytes(source_raw, "reconstructed exact seq77 source")
        event = history[77]
        require(type(event) is dict, "seq78 event is malformed")
        evidence = started.GateEvidence(
            receipt={
                "repository_snapshot": copy.deepcopy(
                    event.get("repository_snapshot_before")
                )
            },
            receipt_bytes=b"",
            receipt_binding=copy.deepcopy(
                event.get("implementation_start_gate_binding")
            ),
            repository_payload={},
            event_occurred_at=event.get("occurred_at"),
        )
        provisional_digests = _provisional_snapshot_digests(
            root, checkpoint, reanchor
        )
        expected, _ = started.project_seq78(
            root,
            source,
            evidence,
            event_id=started.EVENT_ID,
            final_sha256_by_path=provisional_digests,
        )
        _require_exact_json(
            dict(checkpoint), expected, "seq78 full checkpoint projection"
        )
    except Exception as exc:
        return [str(exc)]
    return []


def _require_allowed_checkpoint(root: Path) -> None:
    root = _trusted_root(root)
    checkpoint_raw = _checkpoint_bytes(root)
    if (
        len(checkpoint_raw) == SOURCE_CHECKPOINT["byte_length"]
        and bytes_sha256(checkpoint_raw) == SOURCE_CHECKPOINT["sha256"]
    ):
        _require_exact_seq76(root, checkpoint_raw)
        return
    checkpoint = strict_json_bytes(
        checkpoint_raw, Path(SOURCE_CHECKPOINT["path"]).as_posix()
    )
    _require_event_chain(checkpoint)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(type(history) is list, "checkpoint seq76/77/78 lineage is missing")
    sequence = len(history)
    require(
        sequence >= 77,
        "checkpoint is outside the allowed exact seq76/77/78 lineage",
    )
    token = _SOURCE_VALIDATION_ACTIVE.set(True)
    try:
        errors = (
            _seq77_suffix_errors(root, checkpoint)
            if sequence == 77
            else _seq78_suffix_errors(root, checkpoint)
        )
    finally:
        _SOURCE_VALIDATION_ACTIVE.reset(token)
    require(
        not errors,
        "checkpoint seq77/78 prefix differs: " + "; ".join(errors),
    )


def _valid_sha256(value: Any) -> bool:
    return (
        type(value) is str
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _validated_binding(value: Any, expected_path: str, label: str) -> dict[str, Any]:
    require(
        type(value) is dict
        and set(value) == {"path", "sha256", "byte_length"}
        and value.get("path") == expected_path
        and _valid_sha256(value.get("sha256"))
        and type(value.get("byte_length")) is int
        and value["byte_length"] >= 0,
        f"{label} binding differs: {expected_path}",
    )
    return copy.deepcopy(value)


def _validated_binding_cohort(
    value: Any, expected_paths: Sequence[Path], label: str
) -> tuple[dict[str, Any], ...]:
    require(type(value) is list, f"{label} binding cohort is missing")
    expected = tuple(path.as_posix() for path in expected_paths)
    actual = tuple(
        row.get("path") if type(row) is dict else None for row in value
    )
    require(actual == expected, f"{label} binding cohort path order differs")
    return tuple(
        _validated_binding(row, path, label)
        for row, path in zip(value, expected, strict=True)
    )


def _validated_managed_source_successors(
    value: Any, label: str
) -> tuple[dict[str, Any], ...]:
    require(type(value) is list and bool(value), f"{label} is missing")
    rows: list[dict[str, Any]] = []
    paths: list[str] = []
    for row in value:
        require(
            type(row) is dict
            and set(row) == {"path", "predecessor", "successor"}
            and type(row.get("path")) is str
            and bool(row["path"]),
            f"{label} row differs",
        )
        path = row["path"]
        predecessor_binding = _validated_binding(
            row.get("predecessor"), path, label
        )
        successor_binding = _validated_binding(row.get("successor"), path, label)
        rows.append(
            {
                "path": path,
                "predecessor": predecessor_binding,
                "successor": successor_binding,
            }
        )
        paths.append(path)
    require(len(paths) == len(set(paths)), f"{label} paths are not unique")
    return tuple(rows)


def _validate_frozen_approved_triad(
    round_name: str,
    paths: Sequence[Path],
    documents: Mapping[Path, Mapping[str, Any]],
    raw_by_path: Mapping[Path, bytes],
) -> Mapping[str, Any]:
    require(len(paths) == 3, f"frozen {round_name} review triad differs")
    assignment_path, result_path, independent_path = paths
    assignment = documents[assignment_path]
    result = documents[result_path]
    independent = documents[independent_path]
    assignment_raw = raw_by_path[assignment_path]
    result_raw = raw_by_path[result_path]
    round_id = assignment.get("round_id")
    scope = assignment.get("review_scope")
    reviewer = assignment.get("reviewer")
    boundary = assignment.get("review_boundary")
    require(
        type(round_id) is str
        and round_id.endswith(f"-{round_name}")
        and type(scope) is dict
        and type(reviewer) is dict
        and type(boundary) is dict,
        f"frozen {round_name} assignment authority differs",
    )
    _parse_time(assignment.get("assigned_at"), f"frozen {round_name} assigned_at")
    _require_exact_json(
        result.get("assignment_binding"),
        binding(assignment_path, assignment_raw),
        f"frozen {round_name} result assignment provenance",
    )
    _require_exact_json(
        {
            "round_id": result.get("round_id"),
            "goal_id": result.get("goal_id"),
            "reviewer": result.get("reviewer"),
            "review_scope": result.get("review_scope"),
            "decision": result.get("decision"),
            "findings": result.get("findings"),
            "finding_dispositions": result.get("finding_dispositions"),
            "review_boundary": result.get("review_boundary"),
        },
        {
            "round_id": round_id,
            "goal_id": assignment.get("goal_id"),
            "reviewer": reviewer,
            "review_scope": scope,
            "decision": "APPROVED",
            "findings": {"blocking": [], "major_open": [], "minor_open": []},
            "finding_dispositions": [],
            "review_boundary": boundary,
        },
        f"frozen {round_name} result authority",
    )
    reviewed_at = _parse_time(
        result.get("reviewed_at"), f"frozen {round_name} reviewed_at"
    )
    require(
        reviewed_at
        >= _parse_time(
            assignment.get("assigned_at"), f"frozen {round_name} assigned_at"
        ),
        f"frozen {round_name} review predates assignment",
    )
    _require_exact_json(
        independent.get("assignment_provenance"),
        binding(assignment_path, assignment_raw),
        f"frozen {round_name} independent assignment provenance",
    )
    _require_exact_json(
        independent.get("review_result_provenance"),
        binding(result_path, result_raw),
        f"frozen {round_name} independent result provenance",
    )
    _require_exact_json(
        {
            "round_id": independent.get("round_id"),
            "goal_id": independent.get("goal_id"),
            "reviewed_at": independent.get("reviewed_at"),
            "reviewer": independent.get("reviewer"),
            "review_scope": independent.get("review_scope"),
            "decision": independent.get("decision"),
            "findings": independent.get("findings"),
            "finding_dispositions": independent.get("finding_dispositions"),
            "review_boundary": independent.get("review_boundary"),
            "status": independent.get("status"),
        },
        {
            "round_id": round_id,
            "goal_id": assignment.get("goal_id"),
            "reviewed_at": result.get("reviewed_at"),
            "reviewer": reviewer,
            "review_scope": scope,
            "decision": "APPROVED",
            "findings": {"blocking": [], "major_open": [], "minor_open": []},
            "finding_dispositions": [],
            "review_boundary": boundary,
            "status": "PASS",
        },
        f"frozen {round_name} independent authority",
    )
    return scope


def validated_frozen_r011_context(root: Path = ROOT) -> FrozenR011Context:
    """Validate only exact, immutable JSON authorities; execute no historic code."""

    root = _trusted_root(root)
    pin_sets: dict[str, Mapping[Path, tuple[str, int]]] = {
        **FROZEN_LINEAGE_ROUND_PINS,
        "R011": FROZEN_R011_PINS,
    }
    snapshots: dict[Path, tuple[bytes, tuple[int, ...]]] = {}
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    scopes: dict[str, Mapping[str, Any]] = {}
    for round_name, pins in pin_sets.items():
        paths = tuple(pins)
        require(
            tuple(path.name for path in paths)
            == ("assignment.json", "review-result.json", "independent-review.json"),
            f"frozen {round_name} pin inventory differs",
        )
        for relative, (digest, byte_length) in pins.items():
            snapshot = _stable_regular_snapshot(
                root,
                relative,
                expected_mode=0o644,
                maximum_bytes=MAXIMUM_EVIDENCE_BYTES,
                label="frozen review evidence",
            )
            raw_value = snapshot[0]
            require(
                len(raw_value) == byte_length and bytes_sha256(raw_value) == digest,
                f"frozen {round_name} review differs: {relative}",
            )
            value = strict_json_bytes(raw_value, relative.as_posix())
            require(
                raw_value == json_text(value).encode(),
                f"frozen {round_name} review is noncanonical: {relative}",
            )
            snapshots[relative] = snapshot
            documents[relative] = value
            raw_by_path[relative] = raw_value
        scopes[round_name] = _validate_frozen_approved_triad(
            round_name, paths, documents, raw_by_path
        )

    managed_sources = {
        round_name: _validated_managed_source_successors(
            scopes[round_name].get("reviewed_managed_closure_source_successors"),
            f"frozen {round_name} managed closure",
        )
        for round_name in ("R002", "R003", "R004")
    }
    for previous_name, current_name in (("R002", "R003"), ("R003", "R004")):
        previous = {
            row["path"]: row["successor"]
            for row in managed_sources[previous_name]
        }
        require(
            all(
                previous.get(row["path"]) == row["predecessor"]
                for row in managed_sources[current_name]
            ),
            f"frozen {current_name} managed predecessor differs",
        )

    r011_scope = scopes["R011"]
    cohort = _validated_binding_cohort(
        r011_scope.get("reviewed_current_control_code_cohort"),
        FROZEN_R011_COHORT_PATHS,
        "frozen R011 control",
    )
    cohort_sha256 = r011_scope.get("reviewed_current_control_code_cohort_sha256")
    require(
        type(cohort_sha256) is str
        and r011_scope.get("reviewed_current_control_code_cohort_path_count") == 23
        and len(cohort) == 23
        and cohort_sha256 == object_sha256(list(cohort)),
        "frozen R011 control cohort aggregate differs",
    )
    successor_value = r011_scope.get("reviewed_control_code_successors")
    require(type(successor_value) is list, "frozen R011 control successors are missing")
    successors: list[dict[str, Any]] = []
    cohort_by_path = {row["path"]: row for row in cohort}
    for row in successor_value:
        require(
            type(row) is dict
            and set(row) == {"path", "predecessor", "successor"}
            and type(row.get("path")) is str,
            "frozen R011 control successor row differs",
        )
        path = row["path"]
        predecessor_binding = _validated_binding(
            row.get("predecessor"), path, "frozen R011 predecessor"
        )
        successor_binding = _validated_binding(
            row.get("successor"), path, "frozen R011 successor"
        )
        require(
            cohort_by_path.get(path) == successor_binding,
            f"frozen R011 successor cohort differs: {path}",
        )
        successors.append(
            {
                "path": path,
                "predecessor": predecessor_binding,
                "successor": successor_binding,
            }
        )
    require(
        len(successors) == len({row["path"] for row in successors})
        == r011_scope.get("reviewed_control_code_successor_path_count")
        == 10,
        "frozen R011 control successor inventory differs",
    )

    for relative, expected in snapshots.items():
        require(
            _stable_regular_snapshot(
                root,
                relative,
                expected_mode=0o644,
                maximum_bytes=MAXIMUM_EVIDENCE_BYTES,
                label="frozen review evidence",
            )
            == expected,
            f"frozen review evidence changed during validation: {relative}",
        )
    return FrozenR011Context(
        root=root,
        current=FrozenControlContext(
            control_code_cohort=cohort,
            control_code_cohort_sha256=cohort_sha256,
        ),
        review_bindings=tuple(binding(path, raw_by_path[path]) for path in FROZEN_R011_PATHS),
        control_code_successors=tuple(successors),
        managed_sources_by_round=managed_sources,
    )


def validated_frozen_r011_managed_sources_by_round(
    root: Path = ROOT,
) -> dict[str, tuple[dict[str, Any], ...]]:
    context = validated_frozen_r011_context(root)
    return copy.deepcopy(context.managed_sources_by_round)


def current_control_cohort(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    root = _trusted_root(root)
    return _bind_paths(root, CURRENT_CONTROL_PATHS)


def _exact_pinned_snapshots(
    root: Path,
    pins: Mapping[Path, tuple[str, int]],
    *,
    expected_mode: int | frozenset[int],
    label: str,
) -> dict[Path, tuple[bytes, tuple[int, ...]]]:
    snapshots: dict[Path, tuple[bytes, tuple[int, ...]]] = {}
    for relative, (digest, byte_length) in pins.items():
        snapshot = _stable_regular_snapshot(
            root,
            relative,
            expected_mode=expected_mode,
            maximum_bytes=MAXIMUM_EVIDENCE_BYTES,
            label=label,
        )
        raw_value = snapshot[0]
        require(
            len(raw_value) == byte_length
            and bytes_sha256(raw_value) == digest,
            f"{label} differs: {relative}",
        )
        snapshots[relative] = snapshot
    for relative, expected in snapshots.items():
        require(
            _stable_regular_snapshot(
                root,
                relative,
                expected_mode=expected_mode,
                maximum_bytes=MAXIMUM_EVIDENCE_BYTES,
                label=label,
            )
            == expected,
            f"{label} changed during validation: {relative}",
        )
    return snapshots


def approved_r005_review_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    root = _trusted_root(root)
    require(
        tuple(R005_REVIEW_PINS) == R005_REVIEW_PATHS,
        "approved R005 review pin inventory differs",
    )
    snapshots = _exact_pinned_snapshots(
        root,
        R005_REVIEW_PINS,
        expected_mode=0o644,
        label="approved R005 review evidence",
    )
    raw_by_path = {
        relative: snapshot[0] for relative, snapshot in snapshots.items()
    }
    documents = {
        relative: strict_json_bytes(raw_value, relative.as_posix())
        for relative, raw_value in raw_by_path.items()
    }
    require(
        all(
            raw_by_path[relative] == json_text(documents[relative]).encode()
            for relative in R005_REVIEW_PATHS
        ),
        "approved R005 review evidence is noncanonical",
    )
    _validate_frozen_approved_triad(
        "R005",
        R005_REVIEW_PATHS,
        documents,
        raw_by_path,
    )
    return tuple(
        binding(relative, raw_by_path[relative])
        for relative in R005_REVIEW_PATHS
    )


def session_artifact_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    root = _trusted_root(root)
    require(
        tuple(SESSION_ARTIFACT_PINS) == SESSION_ARTIFACT_PATHS,
        "session artifact pin inventory differs",
    )
    snapshots = _exact_pinned_snapshots(
        root,
        SESSION_ARTIFACT_PINS,
        expected_mode=NONEXECUTABLE_REVIEW_SOURCE_MODES,
        label="session artifact",
    )
    return tuple(
        binding(relative, snapshots[relative][0])
        for relative in SESSION_ARTIFACT_PATHS
    )


def prepare_review_context(
    root: Path = ROOT, *, require_exact_source: bool = True
) -> ReviewContext:
    root = _trusted_root(root)
    _require_pinned_authorities(root)
    if require_exact_source:
        _require_exact_seq76(root)
    frozen_r011 = validated_frozen_r011_context(root)
    frozen_bindings = tuple(
        {
            "path": relative.as_posix(),
            "sha256": FROZEN_R011_PINS[relative][0],
            "byte_length": FROZEN_R011_PINS[relative][1],
        }
        for relative in FROZEN_R011_PATHS
    )
    current = current_control_cohort(root)
    before_by_path = {row["path"]: row for row in frozen_r011.current.control_code_cohort}
    after_by_path = {row["path"]: row for row in current}
    successors = tuple(
        {
            "path": path.as_posix(),
            "predecessor": copy.deepcopy(before_by_path[path.as_posix()]),
            "successor": copy.deepcopy(after_by_path[path.as_posix()]),
        }
        for path in FROZEN_R011_COHORT_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    require(
        {row["path"] for row in successors}
        == {path.as_posix() for path in MODIFIED_CONTROL_PATHS},
        "seq77/78 modified predecessor control path set differs",
    )
    added = tuple(
        copy.deepcopy(after_by_path[path.as_posix()])
        for path in ADDED_CONTROL_PATHS
    )
    require(
        tuple(row["path"] for row in added)
        == tuple(path.as_posix() for path in ADDED_CONTROL_PATHS)
        and len(successors) == 7
        and len(added) == 8,
        "seq77/78 control delta inventory differs",
    )
    authorization_raw = build_authorization().encode()
    contract_raw = build_contract().encode()
    approved_r005 = approved_r005_review_bindings(root)
    artifacts = session_artifact_bindings(root)
    return ReviewContext(
        root=root,
        frozen_r011_context=frozen_r011,
        frozen_r011_review_bindings=frozen_bindings,
        frozen_r011_cohort=frozen_r011.current.control_code_cohort,
        current_control_cohort=current,
        current_control_cohort_sha256=object_sha256(list(current)),
        control_code_successors=successors,
        added_control_code_bindings=added,
        authorization_binding=binding(AUTHORIZATION_REL, authorization_raw),
        contract_binding=binding(CONTRACT_REL, contract_raw),
        superseded_review_assignments=(
            _abandoned_r001_review(root),
            _abandoned_r002_review(root),
            _abandoned_r003_review(root),
            _abandoned_r004_review(root),
        ),
        approved_r005_review_bindings=approved_r005,
        session_artifact_bindings=artifacts,
    )


def review_scope(context: ReviewContext) -> dict[str, Any]:
    require(
        len(context.frozen_r011_cohort) == 23
        and len(context.current_control_cohort) == 31
        and len(context.control_code_successors) == 7
        and len(context.added_control_code_bindings) == 8
        and len(context.superseded_review_assignments) == 4
        and len(PUBLICATION_CONSISTENCY_LEASE_PATHS)
        == len(set(PUBLICATION_CONSISTENCY_LEASE_PATHS))
        == 38
        and context.approved_r005_review_bindings
        == tuple(
            {
                "path": path.as_posix(),
                "sha256": R005_REVIEW_PINS[path][0],
                "byte_length": R005_REVIEW_PINS[path][1],
            }
            for path in R005_REVIEW_PATHS
        )
        and context.session_artifact_bindings
        == tuple(
            {
                "path": path.as_posix(),
                "sha256": SESSION_ARTIFACT_PINS[path][0],
                "byte_length": SESSION_ARTIFACT_PINS[path][1],
            }
            for path in SESSION_ARTIFACT_PATHS
        )
        and tuple(
            row.get("assignment_binding", {}).get("path")
            if type(row) is dict
            and type(row.get("assignment_binding")) is dict
            else None
            for row in context.superseded_review_assignments
        )
        == tuple(path.as_posix() for path in PRESERVED_REVIEW_PATHS),
        "seq77/78 review cohort counts differ",
    )
    return {
        "source_checkpoint": copy.deepcopy(SOURCE_CHECKPOINT),
        "superseded_review_assignments": copy.deepcopy(
            list(context.superseded_review_assignments)
        ),
        "approved_r005_review_bindings": copy.deepcopy(
            list(context.approved_r005_review_bindings)
        ),
        "approved_r005_supersession_reason_code": (
            R005_SUPERSESSION_REASON_CODE
        ),
        "session_artifact_bindings": copy.deepcopy(
            list(context.session_artifact_bindings)
        ),
        "session_artifact_path_count": len(context.session_artifact_bindings),
        "frozen_r011_review_bindings": copy.deepcopy(
            list(context.frozen_r011_review_bindings)
        ),
        "frozen_r011_control_cohort": copy.deepcopy(
            list(context.frozen_r011_cohort)
        ),
        "frozen_r011_control_cohort_sha256": (
            context.frozen_r011_context.current.control_code_cohort_sha256
        ),
        "reviewed_control_code_successors": copy.deepcopy(
            list(context.control_code_successors)
        ),
        "reviewed_control_code_successor_path_count": len(
            context.control_code_successors
        ),
        "reviewed_added_control_code_bindings": copy.deepcopy(
            list(context.added_control_code_bindings)
        ),
        "reviewed_added_control_code_path_count": len(
            context.added_control_code_bindings
        ),
        "reviewed_control_code_delta_path_count": (
            len(context.control_code_successors)
            + len(context.added_control_code_bindings)
        ),
        "authorization_binding": copy.deepcopy(context.authorization_binding),
        "initial_start_gate_contract_binding": copy.deepcopy(
            context.contract_binding
        ),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "reviewed_current_control_cohort": copy.deepcopy(
            list(context.current_control_cohort)
        ),
        "reviewed_current_control_cohort_sha256": (
            context.current_control_cohort_sha256
        ),
        "publication_consistency": {
            "linearization_point": (
                "SECOND_POSTPUBLISH_RETAINED_INPUT_GUARD_COMPLETED"
            ),
            "prelinearization_requirements": [
                "OUTPUT_LINK_IDENTITY_BYTES_PARENT_FSYNC_AND_LIVE_WALK_VERIFIED",
                "RETAINED_REVIEW_INPUT_COHORT_REVERIFIED",
            ],
            "exclusive_write_lease": {
                "holder": "ROOT_REVIEW_COORDINATOR",
                "paths": [
                    path.as_posix()
                    for path in PUBLICATION_CONSISTENCY_LEASE_PATHS
                ],
                "path_count": len(PUBLICATION_CONSISTENCY_LEASE_PATHS),
                "lifetime": (
                    "ASSIGNMENT_THROUGH_REVIEW_RESULT_INDEPENDENT_AND_"
                    "CHECK_POST_REVIEW"
                ),
            },
            "postlinearization_drift_rule": (
                "NEW_REPOSITORY_STATE_MUST_FAIL_SUBSEQUENT_VALIDATION"
            ),
            "return_time_permanent_immutability_claimed": False,
        },
        "acceptance": {
            "source_checkpoint_is_exact_seq76": True,
            "r001_through_r004_assignments_are_preserved_without_outputs": True,
            "r002_six_confirmed_findings_require_r003_reseal": True,
            "r003_input_cohort_aba_finding_requires_r004_reseal": True,
            "r004_post_publication_regression_requires_r005_reseal": True,
            "approved_r005_review_triad_is_preserved_exactly": True,
            "r005_post_approval_session_artifact_inventory_growth_requires_r006_reseal": True,
            "session_artifact_cohort_is_exactly_four_paths": True,
            "r011_is_replayed_from_exact_review_records_and_frozen_cohort": True,
            "r011_live_control_recomputation_is_forbidden": True,
            "authorization_is_current_and_add_only": True,
            "r002_start_gate_contract_is_schema_1_1_successor": True,
            "ordered_gate_checks_are_exactly_five": True,
            "current_control_cohort_is_exactly_thirty_one_paths": True,
            "only_declared_seven_predecessor_controls_changed": True,
            "eight_new_controls_are_add_only_without_fake_predecessors": True,
            "control_delta_is_exactly_fifteen_paths": True,
            "seq77_preserves_ready_status": True,
            "seq78_requires_exact_private_gate_pass_receipt": True,
            "product_change_before_seq78_is_excluded": True,
            "formal_external_device_deployment_and_release_credit_remain_zero": True,
            "publication_consistency_uses_the_declared_linearization_point": True,
        },
    }


def _identity(
    value: Any,
    role: str,
    agent_id: str,
    canonical_task: str,
) -> dict[str, str]:
    require(
        type(value) is dict
        and set(value) == {"role", "agent_instance_id", "canonical_task"}
        and value.get("role") == role
        and value.get("agent_instance_id") == agent_id
        and value.get("canonical_task") == canonical_task,
        f"{role} identity differs",
    )
    return dict(value)


def validate_assignment(
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
) -> None:
    require(
        assignment_raw == json_text(assignment).encode(),
        "seq77/78 assignment is noncanonical",
    )
    require(
        set(assignment)
        == {
            "schema_version",
            "document_id",
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
        and assignment.get("document_id") == ASSIGNMENT_DOCUMENT_ID
        and assignment.get("evidence_type")
        == "FP046_R002_SEQ77_78_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("round_id") == ROUND_ID,
        "seq77/78 assignment identity differs",
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
        "seq77/78 reviewer is not separate",
    )
    _require_exact_json(
        assignment.get("review_scope"),
        review_scope(context),
        "seq77/78 assignment scope",
    )
    _require_exact_json(
        assignment.get("review_boundary"),
        BOUNDARY,
        "seq77/78 assignment boundary",
    )


def build_assignment(context: ReviewContext, *, assigned_at: str) -> str:
    assignment = {
        "schema_version": "1.0",
        "document_id": ASSIGNMENT_DOCUMENT_ID,
        "evidence_type": "FP046_R002_SEQ77_78_REVIEW_ASSIGNMENT",
        "goal_id": GOAL_ID,
        "round_id": ROUND_ID,
        "assigned_at": assigned_at,
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
        "review_scope": review_scope(context),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    assignment_raw = json_text(assignment)
    validate_assignment(assignment, assignment_raw.encode(), context)
    return assignment_raw


def validate_review_result(
    result: Mapping[str, Any],
    result_raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
) -> None:
    validate_assignment(assignment, assignment_raw, context)
    require(
        result_raw == json_text(result).encode(),
        "seq77/78 review result is noncanonical",
    )
    require(
        set(result)
        == {
            "schema_version",
            "document_id",
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
        and result.get("document_id") == RESULT_DOCUMENT_ID
        and result.get("evidence_type")
        == "FP046_R002_SEQ77_78_REVIEWER_AUTHORED_RESULT"
        and result.get("goal_id") == GOAL_ID
        and result.get("round_id") == ROUND_ID,
        "seq77/78 review result identity differs",
    )
    _require_exact_json(
        result.get("reviewer"),
        assignment.get("reviewer"),
        "seq77/78 reviewer binding",
    )
    _require_exact_json(
        result.get("assignment_binding"),
        binding(ASSIGNMENT_REL, assignment_raw),
        "seq77/78 assignment binding",
    )
    _require_exact_json(
        result.get("review_scope"),
        review_scope(context),
        "seq77/78 review result scope",
    )
    _require_exact_json(
        {
            "decision": result.get("decision"),
            "findings": result.get("findings"),
            "finding_dispositions": result.get("finding_dispositions"),
        },
        {
            "decision": "APPROVED",
            "findings": {"blocking": [], "major_open": [], "minor_open": []},
            "finding_dispositions": [],
        },
        "seq77/78 review approval",
    )
    _require_exact_json(
        result.get("review_boundary"),
        BOUNDARY,
        "seq77/78 review result boundary",
    )
    require(
        _parse_time(result.get("reviewed_at"), "reviewed_at")
        >= _parse_time(assignment.get("assigned_at"), "assigned_at"),
        "seq77/78 review predates assignment",
    )


def build_independent_review(
    context: ReviewContext,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_review_result(
        result, result_raw, assignment, assignment_raw, context
    )
    return json_text(
        {
            "schema_version": "1.0",
            "document_id": INDEPENDENT_DOCUMENT_ID,
            "evidence_type": "FP046_R002_SEQ77_78_INDEPENDENT_INTERNAL_REVIEW",
            "goal_id": GOAL_ID,
            "round_id": ROUND_ID,
            "status": "PASS",
            "decision": result["decision"],
            "reviewed_at": result["reviewed_at"],
            "reviewer": result["reviewer"],
            "assignment_provenance": binding(ASSIGNMENT_REL, assignment_raw),
            "review_result_provenance": binding(RESULT_REL, result_raw),
            "review_scope": result["review_scope"],
            "findings": result["findings"],
            "finding_dispositions": result["finding_dispositions"],
            "review_boundary": result["review_boundary"],
        }
    )


def _require_static_documents(root: Path) -> None:
    authorization, authorization_raw = document(root, AUTHORIZATION_REL)
    contract, contract_raw = document(root, CONTRACT_REL)
    validate_authorization(authorization, authorization_raw)
    validate_contract(contract, contract_raw)


def _validated_review(root: Path = ROOT) -> ValidatedReview:
    root = _trusted_root(root)
    context = prepare_review_context(root, require_exact_source=False)
    _require_static_documents(root)
    assignment, assignment_raw = document(root, ASSIGNMENT_REL)
    result, result_raw = document(root, RESULT_REL)
    validate_review_result(
        result, result_raw, assignment, assignment_raw, context
    )
    independent_raw = _evidence_bytes(root, INDEPENDENT_REL)
    require(
        independent_raw
        == build_independent_review(
            context, assignment, assignment_raw, result, result_raw
        ).encode(),
        "seq77/78 independent review differs",
    )
    if not _SOURCE_VALIDATION_ACTIVE.get():
        _require_allowed_checkpoint(root)
    return ValidatedReview(
        context=context,
        assignment_raw=assignment_raw,
        result_raw=result_raw,
        independent_raw=independent_raw,
        reviewed_at=_parse_time(result.get("reviewed_at"), "reviewed_at"),
    )


def validate_post_review(root: Path = ROOT) -> ReviewContext:
    return _validated_review(root).context


def validated_reviewed_at(root: Path = ROOT) -> datetime:
    """Return the timestamp of the exact validated active R006 review."""

    return _validated_review(root).reviewed_at


def transition_review_binding(root: Path = ROOT) -> dict[str, dict[str, Any]]:
    provisional = _provisional_review_state(root)
    if provisional is not None:
        provisional_binding, _bytes_by_path = provisional
        return copy.deepcopy(provisional_binding)
    validated = _validated_review(root)
    return {
        "assignment": binding(ASSIGNMENT_REL, validated.assignment_raw),
        "review_result": binding(RESULT_REL, validated.result_raw),
        "independent_review": binding(
            INDEPENDENT_REL, validated.independent_raw
        ),
    }


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _link_fd_noreplace(
    descriptor: int,
    parent_fd: int,
    destination_name: str,
) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    linkat = getattr(libc, "linkat", None)
    require(linkat is not None, "linkat(AT_EMPTY_PATH) is unavailable")
    linkat.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
    ]
    linkat.restype = ctypes.c_int
    if (
        linkat(
            descriptor,
            b"",
            parent_fd,
            os.fsencode(destination_name),
            0x1000,
        )
        == 0
    ):
        try:
            callback = _PUBLICATION_COMMIT_CALLBACK.get()
            if callback is not None:
                callback()
        except BaseException as exc:
            raise PostcommitUncertain(
                "POSTCOMMIT-UNCERTAIN: publication commit latch is uncertain "
                f"after native publication: {destination_name}: {exc}"
            ) from exc
        return
    error = ctypes.get_errno()
    if error == errno.EEXIST:
        raise FileExistsError(error, os.strerror(error), destination_name)
    raise OSError(error, os.strerror(error), destination_name)


def _inode_identity(info: os.stat_result) -> tuple[int, int]:
    return info.st_dev, info.st_ino


@dataclass
class _PublicationState:
    committed: bool = False


def _descriptor_bytes(descriptor: int, maximum_bytes: int) -> bytes:
    os.lseek(descriptor, 0, os.SEEK_SET)
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = os.read(descriptor, min(64 * 1024, maximum_bytes + 1 - total))
        if not chunk:
            return b"".join(chunks)
        chunks.append(chunk)
        total += len(chunk)
        require(total <= maximum_bytes, "staged review evidence exceeds size limit")


def _write_add_only(
    root: Path,
    relative: Path,
    text: str,
    *,
    precommit_guard: Callable[
        [], Callable[[], None] | None
    ] | None = None,
    publication_state: _PublicationState | None = None,
) -> None:
    raw_value = text.encode("utf-8")
    require(
        len(raw_value) <= MAXIMUM_EVIDENCE_BYTES,
        "review output exceeds the size limit",
    )
    parent_fd = _open_parent_directory(
        root, relative, create=True, label="review output"
    )
    parent_walk_before = _live_parent_walk_identity(
        root,
        relative,
        label="review output",
    )
    require(
        parent_walk_before[-1]
        == _directory_identity(os.fstat(parent_fd)),
        f"review output parent changed before publication: {relative}",
    )
    state = publication_state or _PublicationState()
    stage_fd: int | None = None
    verification_fd: int | None = None
    committed_identity: tuple[int, ...] | None = None
    postpublish_guard: Callable[[], None] | None = None
    publish_started = False
    primary_error: BaseException | None = None
    try:
        try:
            os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise FileExistsError(
                errno.EEXIST,
                f"review output already exists: {relative}",
                relative.name,
            )
        temporary_flag = getattr(os, "O_TMPFILE", 0)
        require(temporary_flag != 0, "O_TMPFILE support is required")
        stage_fd = os.open(
            ".",
            os.O_RDWR | temporary_flag | getattr(os, "O_CLOEXEC", 0),
            0o600,
            dir_fd=parent_fd,
        )
        os.fchmod(stage_fd, 0o644)
        view = memoryview(raw_value)
        while view:
            written = os.write(stage_fd, view)
            if written <= 0:
                raise OSError(errno.EIO, "write returned no progress")
            view = view[written:]
        os.fsync(stage_fd)
        stage_info = os.fstat(stage_fd)
        require(
            stat.S_ISREG(stage_info.st_mode)
            and stat.S_IMODE(stage_info.st_mode) == 0o644
            and stage_info.st_uid == os.geteuid()
            and stage_info.st_nlink == 0
            and stage_info.st_size == len(raw_value),
            "staged review evidence identity differs",
        )
        require(
            _descriptor_bytes(stage_fd, MAXIMUM_EVIDENCE_BYTES) == raw_value
            and _file_identity(os.fstat(stage_fd)) == _file_identity(stage_info),
            "staged review evidence bytes differ",
        )
        verification_fd = os.dup(stage_fd)
        require(
            _file_identity(os.fstat(verification_fd)) == _file_identity(stage_info),
            "staged review evidence changed before publication",
        )
        closing = stage_fd
        stage_fd = None
        try:
            _close_owned_descriptor(closing)
        except BaseException:
            raise
        if precommit_guard is not None:
            postpublish_guard = precommit_guard()
            require(
                postpublish_guard is None or callable(postpublish_guard),
                "precommit guard returned an invalid verifier",
            )
        publish_started = True
        def mark_committed() -> None:
            state.committed = True

        commit_token = _PUBLICATION_COMMIT_CALLBACK.set(mark_committed)
        try:
            _link_fd_noreplace(
                verification_fd,
                parent_fd,
                relative.name,
            )
            if not state.committed:
                mark_committed()
        finally:
            _PUBLICATION_COMMIT_CALLBACK.reset(commit_token)
        committed_identity = _file_identity(os.fstat(verification_fd))
        retained = _RETAINED_REVIEW_INPUT_COHORT.get()
        if isinstance(retained, _RetainedReviewInputCohort):
            retained.accept_output_publication(root, relative)
        if postpublish_guard is not None:
            postpublish_guard()
        published_info = os.stat(
            relative.name, dir_fd=parent_fd, follow_symlinks=False
        )
        require(
            _file_identity(published_info)
            == committed_identity
            and _file_identity(os.fstat(verification_fd))
            == committed_identity,
            "published review evidence identity differs",
        )
        os.fsync(parent_fd)
        published_parent_identity = _directory_identity(os.fstat(parent_fd))
        published_parent_walk = (
            *parent_walk_before[:-1],
            published_parent_identity,
        )
        require(
            _live_parent_walk_identity(
                root,
                relative,
                label="published review evidence",
            )
            == published_parent_walk,
            "published review evidence bytes or identity differ",
        )
        published, published_identity = _stable_regular_snapshot(
            root,
            relative,
            expected_mode=0o644,
            maximum_bytes=MAXIMUM_EVIDENCE_BYTES,
            label="published review evidence",
        )
        require(
            published == raw_value
            and published_identity
            == committed_identity
            and _file_identity(os.fstat(verification_fd))
            == committed_identity,
            "published review evidence bytes or identity differ",
        )
        if postpublish_guard is not None:
            postpublish_guard()
        current_parent_fd = _open_parent_directory(
            root,
            relative,
            create=False,
            label="published review evidence",
        )
        try:
            current_named = os.stat(
                relative.name,
                dir_fd=current_parent_fd,
                follow_symlinks=False,
            )
            require(
                _directory_identity(os.fstat(current_parent_fd))
                == published_parent_identity
                and _file_identity(current_named)
                == committed_identity
                and _file_identity(os.fstat(verification_fd))
                == committed_identity,
                "published review evidence bytes or identity differ",
            )
            os.fsync(current_parent_fd)
            current_named_after = os.stat(
                relative.name,
                dir_fd=current_parent_fd,
                follow_symlinks=False,
            )
            require(
                _directory_identity(os.fstat(current_parent_fd))
                == published_parent_identity
                and _live_parent_walk_identity(
                    root,
                    relative,
                    label="published review evidence",
                )
                == published_parent_walk
                and _file_identity(current_named_after)
                == committed_identity
                and _file_identity(os.fstat(verification_fd))
                == committed_identity,
                "published review evidence bytes or identity differ",
            )
        finally:
            _close_owned_descriptor(current_parent_fd)
        closing = verification_fd
        verification_fd = None
        try:
            _close_owned_descriptor(closing)
        except BaseException:
            raise
    except BaseException as exc:
        primary_error = exc
        if isinstance(exc, PostcommitUncertain):
            raise
        if publish_started and not state.committed:
            try:
                candidate = os.stat(
                    relative.name, dir_fd=parent_fd, follow_symlinks=False
                )
            except FileNotFoundError:
                pass
            except OSError as probe_exc:
                raise PostcommitUncertain(
                    f"POSTCOMMIT-UNCERTAIN: {relative}: "
                    f"publication state probe failed: {probe_exc}"
                ) from exc
            else:
                try:
                    state.committed = (
                        verification_fd is not None
                        and _inode_identity(candidate)
                        == _inode_identity(os.fstat(verification_fd))
                    )
                except OSError as probe_exc:
                    raise PostcommitUncertain(
                        f"POSTCOMMIT-UNCERTAIN: {relative}: "
                        f"publication identity probe failed: {probe_exc}"
                    ) from exc
        if (
            publish_started
            and not state.committed
            and not isinstance(exc, Exception)
        ):
            raise PostcommitUncertain(
                f"POSTCOMMIT-UNCERTAIN: {relative}: "
                f"publication commit latch is uncertain: {exc}"
            ) from exc
        if state.committed:
            raise PostcommitUncertain(
                f"POSTCOMMIT-UNCERTAIN: {relative}: {exc}"
            ) from exc
        raise
    finally:
        cleanup_error: BaseException | None = None
        if stage_fd is not None:
            try:
                _close_owned_descriptor(stage_fd)
            except BaseException as exc:
                cleanup_error = exc
        if verification_fd is not None:
            try:
                _close_owned_descriptor(verification_fd)
            except BaseException as exc:
                if cleanup_error is None:
                    cleanup_error = exc
        try:
            _close_owned_descriptor(parent_fd)
        except BaseException as exc:
            if cleanup_error is None:
                cleanup_error = exc
        if cleanup_error is not None:
            if state.committed:
                raise PostcommitUncertain(
                    f"POSTCOMMIT-UNCERTAIN: {relative}: {cleanup_error}"
                ) from cleanup_error
            if primary_error is None:
                raise cleanup_error


def _prepare_output_parent(root: Path, relative: Path) -> None:
    parent_fd = _open_parent_directory(
        root,
        relative,
        create=True,
        label="review output",
    )
    try:
        os.fsync(parent_fd)
    finally:
        _close_owned_descriptor(parent_fd)


def _release_retained_cohort(
    token: Any,
    cohort: _RetainedReviewInputCohort,
    primary: BaseException | None,
) -> BaseException | None:
    cleanup_error: BaseException | None = None
    try:
        _RETAINED_REVIEW_INPUT_COHORT.reset(token)
    except BaseException as exc:
        cleanup_error = exc
    try:
        cohort.close(primary or cleanup_error)
    except BaseException as exc:
        if cleanup_error is None:
            cleanup_error = exc
    return cleanup_error


def _raise_retained_writer_failure(
    relative: Path,
    state: _PublicationState,
    failure: BaseException,
) -> None:
    if state.committed and not isinstance(failure, PostcommitUncertain):
        raise PostcommitUncertain(
            f"POSTCOMMIT-UNCERTAIN: {relative}: {failure}"
        ) from failure
    raise failure.with_traceback(failure.__traceback__)


def write_authorization(root: Path) -> None:
    _write_add_only(root, AUTHORIZATION_REL, build_authorization())


def write_contract(root: Path) -> None:
    _write_add_only(root, CONTRACT_REL, build_contract())


def write_assignment(root: Path) -> None:
    root = _trusted_root(root)
    _prepare_output_parent(root, ASSIGNMENT_REL)
    cohort = _RetainedReviewInputCohort(
        root, output_relative=ASSIGNMENT_REL
    )
    token = _RETAINED_REVIEW_INPUT_COHORT.set(cohort)
    state = _PublicationState()
    primary: BaseException | None = None
    cleanup_error: BaseException | None = None
    try:
        _require_static_documents(root)
        context = prepare_review_context(root)
        assigned_at = _now()
        text = build_assignment(context, assigned_at=assigned_at)
        cohort.seal()

        def verify_inputs() -> None:
            cohort.verify()
            _require_static_documents(root)
            fresh = prepare_review_context(root)
            require(
                build_assignment(fresh, assigned_at=assigned_at) == text,
                "assignment inputs changed before publication",
            )
            cohort.verify()

        def guard() -> Callable[[], None]:
            verify_inputs()
            return verify_inputs

        _write_add_only(
            root,
            ASSIGNMENT_REL,
            text,
            precommit_guard=guard,
            publication_state=state,
        )
    except BaseException as exc:
        primary = exc
    finally:
        cleanup_error = _release_retained_cohort(token, cohort, primary)
    failure = primary or cleanup_error
    if failure is not None:
        _raise_retained_writer_failure(ASSIGNMENT_REL, state, failure)


def write_independent(root: Path) -> None:
    root = _trusted_root(root)
    _prepare_output_parent(root, INDEPENDENT_REL)
    cohort = _RetainedReviewInputCohort(
        root, output_relative=INDEPENDENT_REL
    )
    token = _RETAINED_REVIEW_INPUT_COHORT.set(cohort)
    state = _PublicationState()
    primary: BaseException | None = None
    cleanup_error: BaseException | None = None

    def prepare_text() -> str:
        context = prepare_review_context(root, require_exact_source=False)
        _require_static_documents(root)
        assignment, assignment_raw = document(root, ASSIGNMENT_REL)
        result, result_raw = document(root, RESULT_REL)
        validate_review_result(
            result, result_raw, assignment, assignment_raw, context
        )
        text = build_independent_review(
            context, assignment, assignment_raw, result, result_raw
        )
        if not _SOURCE_VALIDATION_ACTIVE.get():
            provisional = {
                "assignment": binding(ASSIGNMENT_REL, assignment_raw),
                "review_result": binding(RESULT_REL, result_raw),
                "independent_review": binding(
                    INDEPENDENT_REL, text.encode()
                ),
            }
            token = _PROVISIONAL_REVIEW_BINDING.set(
                (
                    _directory_authority_identity(root.lstat()),
                    provisional,
                    {INDEPENDENT_REL: text.encode()},
                )
            )
            try:
                _require_allowed_checkpoint(root)
            finally:
                _PROVISIONAL_REVIEW_BINDING.reset(token)
        return text

    try:
        text = prepare_text()
        cohort.seal()

        def verify_inputs() -> None:
            cohort.verify()
            require(
                prepare_text() == text,
                "independent-review inputs changed before publication",
            )
            cohort.verify()

        def guard() -> Callable[[], None]:
            verify_inputs()
            return verify_inputs

        _write_add_only(
            root,
            INDEPENDENT_REL,
            text,
            precommit_guard=guard,
            publication_state=state,
        )
    except BaseException as exc:
        primary = exc
    finally:
        cleanup_error = _release_retained_cohort(token, cohort, primary)
    failure = primary or cleanup_error
    if failure is not None:
        _raise_retained_writer_failure(INDEPENDENT_REL, state, failure)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write-authorization", action="store_true")
    mode.add_argument("--check-authorization", action="store_true")
    mode.add_argument("--write-contract", action="store_true")
    mode.add_argument("--check-contract", action="store_true")
    mode.add_argument("--write-assignment", action="store_true")
    mode.add_argument("--check-assignment", action="store_true")
    mode.add_argument("--check-review-result", action="store_true")
    mode.add_argument("--write-independent", action="store_true")
    mode.add_argument("--check-post-review", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        root = _trusted_root(args.root)
        if args.write_authorization:
            write_authorization(root)
        elif args.check_authorization:
            value, value_raw = document(root, AUTHORIZATION_REL)
            validate_authorization(value, value_raw)
        elif args.write_contract:
            write_contract(root)
        elif args.check_contract:
            value, value_raw = document(root, CONTRACT_REL)
            validate_contract(value, value_raw)
        elif args.write_assignment:
            write_assignment(root)
        elif args.check_assignment:
            _require_static_documents(root)
            context = prepare_review_context(root)
            assignment, assignment_raw = document(root, ASSIGNMENT_REL)
            validate_assignment(assignment, assignment_raw, context)
        elif args.check_review_result:
            _require_static_documents(root)
            context = prepare_review_context(root, require_exact_source=False)
            assignment, assignment_raw = document(root, ASSIGNMENT_REL)
            result, result_raw = document(root, RESULT_REL)
            validate_review_result(
                result, result_raw, assignment, assignment_raw, context
            )
            if not _SOURCE_VALIDATION_ACTIVE.get():
                _require_allowed_checkpoint(root)
        elif args.write_independent:
            write_independent(root)
        else:
            validate_post_review(root)
    except PostcommitUncertain as exc:
        print(f"FP046 R002 seq77/78 review: {exc}")
        return 2
    except (OSError, ValueError, TypeError, ReviewError) as exc:
        print(f"FP046 R002 seq77/78 review: FAIL: {exc}")
        return 1
    print("FP046 R002 seq77/78 review: PASS")
    return 0


if __name__ == "__main__":
    # Direct-path execution otherwise creates a second __main__ module with a
    # different ContextVar from the canonical module imported by seq77/78.
    from scripts import (
        build_walksafe_fp046_r002_seq77_78_review_20260823 as canonical,
    )

    raise SystemExit(canonical.main())
