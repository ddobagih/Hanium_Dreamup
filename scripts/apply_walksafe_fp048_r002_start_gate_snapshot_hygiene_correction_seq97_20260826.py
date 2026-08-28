#!/usr/bin/env python3
"""Prepare the append-only FP048 R002 seq97 R008-to-R009 correction.

The published seq96 checkpoint and its review authority are immutable.  This
producer records the failed R008 preview as nonauthority, reanchors only the
repository context, and keeps FP048 R002 READY with zero implementation or
release credit.  Candidate review documents are never written by this file.
"""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import importlib
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (  # noqa: E402
    apply_walksafe_fp048_r002_start_gate_contract_correction_seq96_20260826
    as seq96,
)


class SnapshotHygieneCorrectionError(RuntimeError):
    """The seq97 snapshot-hygiene correction cannot be proven or published."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SnapshotHygieneCorrectionError(message)


seq90 = seq96.seq90
continuation = seq96.continuation
CHECKPOINT_REL = seq96.CHECKPOINT_REL
checkpoint_json_bytes = seq96.checkpoint_json_bytes
GOAL_ID = seq96.GOAL_ID
GOAL_PATH = seq96.GOAL_PATH
GOAL_SHA256 = seq96.GOAL_SHA256
WORK_ITEM_ID = seq96.WORK_ITEM_ID
MANIFEST_SHA256 = seq96.MANIFEST_SHA256
EVENT_FIELDS = seq96.EVENT_FIELDS

SOURCE_SEQUENCE = 96
SOURCE_EVENT_ID = seq96.CORRECTION_EVENT_ID
SOURCE_EVENT_SHA256 = (
    "ff0c91a5a98df66d6e44007c5c5fd51c50793fb6b896938418104aba05798dea"
)
SOURCE_CHECKPOINT_SHA256 = (
    "c64919299dea316339afd777c37a33575458a94a158acd4c04ceed8b3075d85f"
)
SOURCE_CHECKPOINT_BYTE_LENGTH = 4_414_443
SOURCE_CHECKPOINT_MODE = 0o600

CORRECTION_SEQUENCE = 97
CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-CONTRACT-CORRECTED-"
    "FP048-R002-20260826-005"
)
CORRECTION_EVENT_TYPE = "GOAL_START_GATE_CONTRACT_CORRECTED"
STARTED_SEQUENCE = 98
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-004"
)

R008_CONTRACT_BINDING = copy.deepcopy(seq96.R008_CONTRACT_BINDING)
R008_RUNNER_BINDING = copy.deepcopy(seq96.R008_RUNNER_BINDING)
R008_FAILURE_REASON_CODE = (
    "R008_ROOT_REGRESSION_MUTATED_RETAINED_SOURCE_ANCESTOR"
)
R008_PREFLIGHT_ATTEMPT_004 = {
    "event_id": STARTED_EVENT_ID,
    "contract_id": R008_CONTRACT_BINDING["contract_id"],
    "contract_version": R008_CONTRACT_BINDING["contract_version"],
    "directory": (
        "docs/control/execution/goal-gates/"
        + STARTED_EVENT_ID
    ),
    "receipt_path": (
        "docs/control/execution/goal-gates/"
        + STARTED_EVENT_ID
        + "/implementation-start-gate-receipt.json"
    ),
    "status": "PREVIEW_FAILED_BEFORE_NAMESPACE",
    "authority_status": "NONAUTHORITY",
    "event_identity_status": "REUSABLE_UNCONSUMED",
    "namespace_present": False,
    "receipt_present": False,
    "failed_check_id": "ROOT_FP048_R002_CONTROL_REGRESSION",
    "exit_code": 2,
    "error": "retained source ancestor identity changed: docs/control",
    "reason_code": R008_FAILURE_REASON_CODE,
}
require(len(R008_PREFLIGHT_ATTEMPT_004) == 14, "R008 observation field count differs")

R009_CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R009"
R009_CONTRACT_VERSION = "2026-08-26.8"
R009_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-009"

R009_CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r009.json"
)
R009_RUNNER_REL = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r009_20260826.py"
)
R009_RUNNER_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r009_20260826.py"
)
POST_SEQ96_STAGE_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_post_seq96_stage_regression_20260826.py"
)
CORRECTED_SEQ96_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_"
    "seq96_20260826.py"
)
SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_correction_"
    "seq97_20260826.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_"
    "correction_seq97_20260826.py"
)
SEQ98_STARTER_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq98_20260826.py"
)
SEQ98_STARTER_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq98_20260826.py"
)
CONTINUATION_SCRIPT_REL = Path("scripts/check_walksafe_project_continuation_v2_4.py")
CONTINUATION_TEST_REL = Path("tests/test_walksafe_project_continuation_v2_4.py")
GOAL_GRAPH_SCRIPT_REL = Path("scripts/check_walksafe_goal_graph_v2_4.py")
GOAL_GRAPH_TEST_REL = Path("tests/test_walksafe_goal_graph_v2_4.py")
LAYER_RUNNER_REL = Path("scripts/run_walksafe_test_layers_current.sh")

AUTHORIZATION_REL = Path(
    "docs/control/execution/workstream-transitions/seq97-98/authorization.json"
)
AUTHORIZATION_BINDING = {
    "path": AUTHORIZATION_REL.as_posix(),
    "sha256": "0a93b127e39b8dd2f796d530387c8c30a2600e2640dc72964c51e23541200d84",
    "byte_length": 5_128,
}
R001_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq97-98/"
    "review-rounds/R001"
)
R001_REVIEW_ASSIGNMENT_REL = R001_REVIEW_ROOT / "review-assignment.json"
R001_REVIEW_RESULT_REL = R001_REVIEW_ROOT / "review-result.json"
R001_INDEPENDENT_REVIEW_REL = R001_REVIEW_ROOT / "independent-review.json"
R001_REVIEW_ASSIGNMENT_BINDING = {
    "path": R001_REVIEW_ASSIGNMENT_REL.as_posix(),
    "sha256": "9cb5ce2eb22e7a1fb8dc189902da5badc732d1597e3b3241486b95575dba5e8d",
    "byte_length": 8_121,
}
R002_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq97-98/"
    "review-rounds/R002"
)
R002_REVIEW_ASSIGNMENT_REL = R002_REVIEW_ROOT / "review-assignment.json"
R002_REVIEW_RESULT_REL = R002_REVIEW_ROOT / "review-result.json"
R002_INDEPENDENT_REVIEW_REL = R002_REVIEW_ROOT / "independent-review.json"
R002_REVIEW_ASSIGNMENT_BINDING = {
    "path": R002_REVIEW_ASSIGNMENT_REL.as_posix(),
    "sha256": "0b3ec5e2aa4ccf2e37593c74fc33970cabaa2d57315850465cb1e37a55fd9944",
    "byte_length": 8_121,
}
R003_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq97-98/"
    "review-rounds/R003"
)
R003_REVIEW_ASSIGNMENT_REL = R003_REVIEW_ROOT / "review-assignment.json"
R003_REVIEW_RESULT_REL = R003_REVIEW_ROOT / "review-result.json"
R003_INDEPENDENT_REVIEW_REL = R003_REVIEW_ROOT / "independent-review.json"
R003_REVIEW_ASSIGNMENT_BINDING = {
    "path": R003_REVIEW_ASSIGNMENT_REL.as_posix(),
    "sha256": "8d3bccf408f21732d4219967a7830dee3a31f247ed439a78be41fa2af6b45e98",
    "byte_length": 8_121,
}
REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq97-98/"
    "review-rounds/R004"
)
REVIEW_ASSIGNMENT_REL = REVIEW_ROOT / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_ROOT / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_ROOT / "independent-review.json"
REVIEW_PATHS = (
    REVIEW_ASSIGNMENT_REL,
    REVIEW_RESULT_REL,
    INDEPENDENT_REVIEW_REL,
)
REVIEW_ROUND_ID = "R004"
REVIEW_ASSIGNMENT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ97-98-REVIEW-ASSIGNMENT-20260827-R004"
)
REVIEW_RESULT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ97-98-REVIEW-RESULT-20260827-R004"
)
INDEPENDENT_REVIEW_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ97-98-INDEPENDENT-REVIEW-20260827-R004"
)
REVIEWER = {
    "id": "codex-seq96-goalgraph-terminal-dispatch-auditor-20260826",
    "task_id": "/root/seq96_goalgraph_terminal_dispatch_audit",
}

AUTHORIZATION_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "authorization_status",
    "goal_id",
    "source_checkpoint_binding",
    "r008_preflight_attempt_004",
    "previous_contract_binding",
    "replacement_contract_binding",
    "contract_correction_event_id",
    "projected_transition",
    "claim_boundary",
    "authorized_executor",
    "required_independent_reviewer",
}
ASSIGNMENT_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "round_id",
    "candidate_status",
    "goal_id",
    "authorization_binding",
    "source_checkpoint_binding",
    "r008_preflight_attempt_004",
    "previous_contract_binding",
    "replacement_contract_binding",
    "replacement_runner_binding",
    "reviewed_control_inputs",
    "required_reviewer",
    "executor",
    "claim_boundary",
    "required_findings",
}
REVIEW_RESULT_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "round_id",
    "assignment_binding",
    "reviewer",
    "decision",
    "findings",
    "external_independence_claimed",
    "reviewed_at",
}
INDEPENDENT_REVIEW_FIELDS = REVIEW_RESULT_FIELDS | {"review_result_binding"}
EXECUTOR = {
    "id": "codex-fp048-r002-seq97-snapshot-hygiene-executor-20260826",
    "task_id": "/root/seq96_review_r002_update",
}

UNFROZEN_SHA256 = "0" * 64
UNFROZEN_BYTE_LENGTH = -1
FINAL_AUTHORIZATION_STATUS = (
    "AUTHORIZED_FOR_CONDITIONAL_SEQ97_98_SNAPSHOT_HYGIENE_CORRECTION_"
    "CONTINUATION"
)
FINAL_CANDIDATE_STATUS = "FINAL_REVIEW_CANDIDATE"
PROVISIONAL_STATUS = "PROVISIONAL_DO_NOT_PUBLISH"


def _provisional_binding(path: Path) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": UNFROZEN_SHA256,
        "byte_length": UNFROZEN_BYTE_LENGTH,
    }


REVIEWED_CONTROL_PATHS = tuple(
    sorted(
        {
            R009_CONTRACT_REL,
            R009_RUNNER_REL,
            R009_RUNNER_TEST_REL,
            POST_SEQ96_STAGE_TEST_REL,
            CORRECTED_SEQ96_TEST_REL,
            SCRIPT_REL,
            TEST_REL,
            SEQ98_STARTER_REL,
            SEQ98_STARTER_TEST_REL,
            CONTINUATION_SCRIPT_REL,
            CONTINUATION_TEST_REL,
            GOAL_GRAPH_SCRIPT_REL,
            GOAL_GRAPH_TEST_REL,
            LAYER_RUNNER_REL,
        },
        key=lambda value: value.as_posix(),
    )
)
DYNAMIC_REVIEWED_CONTROL_PATHS = frozenset(
    {
        SCRIPT_REL,
        TEST_REL,
        SEQ98_STARTER_REL,
        SEQ98_STARTER_TEST_REL,
    }
)

# Producing agents replace only these values after their files are frozen.
# Any zero digest or negative length is an explicit publication blocker.
FIXED_REVIEWED_CONTROL_BINDINGS: dict[Path, dict[str, Any]] = {
    path: _provisional_binding(path)
    for path in REVIEWED_CONTROL_PATHS
    if path not in DYNAMIC_REVIEWED_CONTROL_PATHS
}
FIXED_REVIEWED_CONTROL_BINDINGS.update(
    {
        R009_CONTRACT_REL: {
            "path": R009_CONTRACT_REL.as_posix(),
            "sha256": (
                "cd027dde66924a19e1a7942c347b4b4a0e2bea44a8e6e0752a1a42993f9d20ff"
            ),
            "byte_length": 3_579,
        },
        R009_RUNNER_REL: {
            "path": R009_RUNNER_REL.as_posix(),
            "sha256": (
                "f5ed6e22507e3e08413dbc089accd73e791bda02d20a88ff8b50c9914c1c1e24"
            ),
            "byte_length": 33_639,
        },
        R009_RUNNER_TEST_REL: {
            "path": R009_RUNNER_TEST_REL.as_posix(),
            "sha256": (
                "76402b549ff614dd54d86264a30011b54447183e76fc71d8ec1db0810a68b322"
            ),
            "byte_length": 19_398,
        },
        POST_SEQ96_STAGE_TEST_REL: {
            "path": POST_SEQ96_STAGE_TEST_REL.as_posix(),
            "sha256": (
                "4548221b28fc2fc0298d2edd9c38f49d7abb668ab9fc07d154de61331e042f44"
            ),
            "byte_length": 9_397,
        },
        CORRECTED_SEQ96_TEST_REL: {
            "path": CORRECTED_SEQ96_TEST_REL.as_posix(),
            "sha256": (
                "7ca08ff220e9b8151dae833ce39d8eb87f1899c4516a2b3c9529fc5f4445f15a"
            ),
            "byte_length": 37_247,
        },
        CONTINUATION_SCRIPT_REL: {
            "path": CONTINUATION_SCRIPT_REL.as_posix(),
            "sha256": (
                "4e2c0f8d66a9e3502ce3144506dad280e4791d33274361bbcd3a1396222cbfff"
            ),
            "byte_length": 677_695,
        },
        CONTINUATION_TEST_REL: {
            "path": CONTINUATION_TEST_REL.as_posix(),
            "sha256": (
                "15556538d9183d6d27ade36f4c28fc49625071b8cf15d3fd2b66b356de8dd1a1"
            ),
            "byte_length": 351_805,
        },
        GOAL_GRAPH_SCRIPT_REL: {
            "path": GOAL_GRAPH_SCRIPT_REL.as_posix(),
            "sha256": (
                "c3d9f1fdeedd0b623d061efddc7a7f6e02f8a3a76d0aa72d18c00ee98cfbfba4"
            ),
            "byte_length": 940_826,
        },
        GOAL_GRAPH_TEST_REL: {
            "path": GOAL_GRAPH_TEST_REL.as_posix(),
            "sha256": (
                "d9cfe408aab905e937047d04af2b5e1ebe24baa879cbcf42ac210939355447d1"
            ),
            "byte_length": 662_878,
        },
        LAYER_RUNNER_REL: {
            "path": LAYER_RUNNER_REL.as_posix(),
            "sha256": (
                "b4763a0fd7addee37c347f09ea6b445a7ccbe75d78f8c497af0faaccf999e480"
            ),
            "byte_length": 28_432,
        },
    }
)
R009_CANONICAL_SHA256 = (
    "9e82e6c2ef8a35b412efa44567025b0316e42f2f3840a4ef290f426aadbd531b"
)

CLAIM_BOUNDARY = copy.deepcopy(seq96.CLAIM_BOUNDARY)
CLAIM_BOUNDARY.update(
    {
        "goal_status_change_count": 0,
        "implementation_start_authorized": False,
    }
)
SNAPSHOT_HYGIENE_SUCCESSOR_AUTHORITY_LABEL = (
    "NONCREDIT_SNAPSHOT_HYGIENE_CONTROL_ONLY"
)
SNAPSHOT_HYGIENE_SUCCESSOR_CREDIT_BOUNDARY = {
    "implementation_completion_credit_delta": 0,
    "formal_test_credit_delta": 0,
    "actual_device_test_credit_delta": 0,
    "external_review_credit_delta": 0,
    "deployment_credit_delta": 0,
    "release_credit_delta": 0,
}
REVIEWED_CONTROL_SUCCESSOR_AUTHORITY_LABEL = (
    "NONCREDIT_REVIEWED_CONTROL_CONTEXT_ONLY"
)
REVIEWED_CONTROL_SUCCESSOR_CREDIT_BOUNDARY = copy.deepcopy(
    SNAPSHOT_HYGIENE_SUCCESSOR_CREDIT_BOUNDARY
)
CURRENT_FOCUS = (
    "FP-048 R002 READY after seq97 snapshot-hygiene correction; R008 remains "
    "failed nonauthority and R009 is not yet run"
)
NEXT_ACTION = (
    "독립 검토된 R009 five-check preflight를 -004 재사용 identity로 실행한다."
)
SCOPE = (
    "Add-only seq97 READY-to-READY snapshot-hygiene correction after exact "
    "R008 preview failure; no implementation, formal, device, external, "
    "deployment, approval, or release credit."
)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _binding(path: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
    }


def _strict_json_equal(left: Any, right: Any) -> bool:
    return seq90.canonical_json_bytes(left) == seq90.canonical_json_bytes(right)


def _parse_time(value: Any, label: str) -> datetime:
    require(isinstance(value, str), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise SnapshotHygieneCorrectionError(f"{label} is invalid") from exc
    require(parsed.tzinfo is not None, f"{label} lacks timezone")
    return parsed


def _is_final_binding(binding: Mapping[str, Any], path: Path) -> bool:
    return (
        set(binding) == {"path", "sha256", "byte_length"}
        and binding.get("path") == path.as_posix()
        and isinstance(binding.get("sha256"), str)
        and seq90.SHA256_RE.fullmatch(binding["sha256"]) is not None
        and binding["sha256"] != UNFROZEN_SHA256
        and type(binding.get("byte_length")) is int
        and binding["byte_length"] >= 0
    )


def _require_file_binding(
    binding: Any,
    path: Path,
    label: str,
) -> None:
    require(
        isinstance(binding, Mapping)
        and set(binding) == {"path", "sha256", "byte_length"}
        and binding.get("path") == path.as_posix()
        and isinstance(binding.get("sha256"), str)
        and seq90.SHA256_RE.fullmatch(binding["sha256"]) is not None
        and binding["sha256"] != UNFROZEN_SHA256
        and type(binding.get("byte_length")) is int
        and binding["byte_length"] >= 0,
        f"{label} differs",
    )


def _require_r009_contract_binding(binding: Any) -> None:
    require(
        isinstance(binding, Mapping)
        and set(binding)
        == {
            "schema_version",
            "document_id",
            "path",
            "file_sha256",
            "contract_id",
            "contract_version",
            "canonical_contract_sha256",
        }
        and binding.get("schema_version") == "1.2"
        and binding.get("document_id") == R009_DOCUMENT_ID
        and binding.get("path") == R009_CONTRACT_REL.as_posix()
        and binding.get("contract_id") == R009_CONTRACT_ID
        and binding.get("contract_version") == R009_CONTRACT_VERSION
        and isinstance(binding.get("file_sha256"), str)
        and seq90.SHA256_RE.fullmatch(binding["file_sha256"]) is not None
        and binding["file_sha256"] != UNFROZEN_SHA256
        and isinstance(binding.get("canonical_contract_sha256"), str)
        and seq90.SHA256_RE.fullmatch(binding["canonical_contract_sha256"])
        is not None
        and binding["canonical_contract_sha256"] != UNFROZEN_SHA256,
        "R009 contract binding differs",
    )


def _projected_transition() -> dict[str, Any]:
    return {
        "contract_correction_sequence": CORRECTION_SEQUENCE,
        "contract_correction_transition": "READY_TO_READY",
        "started_event_id": STARTED_EVENT_ID,
        "started_sequence": STARTED_SEQUENCE,
        "started_transition": "READY_TO_IN_PROGRESS_AFTER_FRESH_R009_PASS",
    }


def pins_are_final() -> bool:
    return (
        isinstance(R009_CANONICAL_SHA256, str)
        and seq90.SHA256_RE.fullmatch(R009_CANONICAL_SHA256) is not None
        and R009_CANONICAL_SHA256 != UNFROZEN_SHA256
        and all(
            _is_final_binding(binding, path)
            for path, binding in FIXED_REVIEWED_CONTROL_BINDINGS.items()
        )
    )


def _require_source_mode(root: Path) -> None:
    mode = stat.S_IMODE((root / CHECKPOINT_REL).lstat().st_mode)
    require(mode == SOURCE_CHECKPOINT_MODE, "seq96 checkpoint mode differs")


def require_exact_seq96_source(
    raw: bytes,
    checkpoint: Mapping[str, Any],
    root: Path = ROOT,
) -> None:
    root = seq90._safe_root(root)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256
        and raw == checkpoint_json_bytes(checkpoint)
        and isinstance(history, list)
        and len(history) == SOURCE_SEQUENCE
        and isinstance(history[-1], dict)
        and history[-1].get("event_id") == SOURCE_EVENT_ID
        and history[-1].get("event_sha256") == SOURCE_EVENT_SHA256
        and history[-1].get("event_sha256") == continuation.event_sha256(history[-1])
        and state.get("transition_history_anchor_sha256") == SOURCE_EVENT_SHA256
        and state.get("goal_status") == "READY"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values(),
        "exact published seq96 source differs",
    )
    canonical = seq96.canonical_seq96_checkpoint_bytes(root, checkpoint)
    require(canonical == raw, "seq96 historical/inverse authority differs")


def load_exact_seq96_source(root: Path = ROOT) -> tuple[bytes, dict[str, Any]]:
    root = seq90._safe_root(root)
    observed = seq90._stable_read(root, CHECKPOINT_REL)
    source = seq90.strict_json(observed.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq96_source(observed.raw, source, root)
    _require_source_mode(root)
    return observed.raw, source


def _stored_r008_preflight_attempt_004() -> dict[str, Any]:
    return copy.deepcopy(R008_PREFLIGHT_ATTEMPT_004)


def _require_r008_namespace_absent(root: Path) -> None:
    event_root = root / R008_PREFLIGHT_ATTEMPT_004["directory"]
    receipt = root / R008_PREFLIGHT_ATTEMPT_004["receipt_path"]
    require(
        not os.path.lexists(event_root)
        and not os.path.lexists(receipt),
        "R008 -004 namespace or receipt unexpectedly exists",
    )


def r008_preflight_attempt_004_observation(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    _require_r008_namespace_absent(root)
    raw = seq90._stable_read(root, CHECKPOINT_REL).raw
    require(
        len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256,
        "R008 preflight changed the seq96 checkpoint",
    )
    _require_source_mode(root)
    return _stored_r008_preflight_attempt_004()


def _observed_binding(root: Path, path: Path) -> dict[str, Any]:
    return _binding(path, seq90._stable_read(root, path).raw)


def _require_immutable_review_lineage(root: Path) -> None:
    root = seq90._safe_root(root)
    for path, expected, label in (
        (AUTHORIZATION_REL, AUTHORIZATION_BINDING, "authorization"),
        (
            R001_REVIEW_ASSIGNMENT_REL,
            R001_REVIEW_ASSIGNMENT_BINDING,
            "stale R001 assignment",
        ),
        (
            R002_REVIEW_ASSIGNMENT_REL,
            R002_REVIEW_ASSIGNMENT_BINDING,
            "stale R002 assignment",
        ),
        (
            R003_REVIEW_ASSIGNMENT_REL,
            R003_REVIEW_ASSIGNMENT_BINDING,
            "stale R003 assignment",
        ),
    ):
        observed = seq90._stable_read(root, path)
        metadata = (root / path).lstat()
        require(
            _binding(path, observed.raw) == expected
            and stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_nlink == 1,
            f"immutable seq97 {label} differs",
        )
    require(
        not os.path.lexists(root / R001_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R001_INDEPENDENT_REVIEW_REL),
        "stale R001 unexpectedly acquired review authority",
    )
    require(
        not os.path.lexists(root / R002_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R002_INDEPENDENT_REVIEW_REL),
        "stale R002 unexpectedly acquired review authority",
    )
    require(
        not os.path.lexists(root / R003_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R003_INDEPENDENT_REVIEW_REL),
        "stale R003 unexpectedly acquired review authority",
    )


def _reviewed_control_bindings(
    root: Path,
    *,
    allow_provisional: bool = False,
) -> list[dict[str, Any]]:
    root = seq90._safe_root(root)
    rows: list[dict[str, Any]] = []
    for path in REVIEWED_CONTROL_PATHS:
        if path in DYNAMIC_REVIEWED_CONTROL_PATHS:
            rows.append(_observed_binding(root, path))
            continue
        expected = FIXED_REVIEWED_CONTROL_BINDINGS[path]
        if not _is_final_binding(expected, path):
            require(allow_provisional, f"unfrozen reviewed pin: {path}")
            rows.append(copy.deepcopy(expected))
            continue
        observed = _observed_binding(root, path)
        require(observed == expected, f"reviewed pin differs: {path}")
        rows.append(observed)
    return rows


def noncredit_snapshot_hygiene_successor_binding(
    root: Path = ROOT,
) -> dict[str, Any]:
    """Bind the reviewed test fix as control context with no product credit."""

    root = seq90._safe_root(root)
    expected = FIXED_REVIEWED_CONTROL_BINDINGS[CORRECTED_SEQ96_TEST_REL]
    require(
        _is_final_binding(expected, CORRECTED_SEQ96_TEST_REL),
        "snapshot-hygiene successor pin is not final",
    )
    observed = _observed_binding(root, CORRECTED_SEQ96_TEST_REL)
    require(
        observed == expected
        and all(
            type(value) is int and value == 0
            for value in SNAPSHOT_HYGIENE_SUCCESSOR_CREDIT_BOUNDARY.values()
        ),
        "snapshot-hygiene successor binding differs",
    )
    return {
        "authority_label": SNAPSHOT_HYGIENE_SUCCESSOR_AUTHORITY_LABEL,
        "binding": copy.deepcopy(observed),
        "credit_boundary": copy.deepcopy(
            SNAPSHOT_HYGIENE_SUCCESSOR_CREDIT_BOUNDARY
        ),
    }


def _validated_reviewed_control_rows(value: Any) -> list[dict[str, Any]]:
    require(
        isinstance(value, list)
        and len(value) == len(REVIEWED_CONTROL_PATHS)
        and all(isinstance(row, Mapping) for row in value),
        "seq97 reviewed cohort differs",
    )
    rows = [dict(row) for row in value]
    expected_paths = [path.as_posix() for path in REVIEWED_CONTROL_PATHS]
    require(
        [row.get("path") for row in rows] == expected_paths
        and len({row.get("path") for row in rows}) == len(rows),
        "seq97 reviewed cohort differs",
    )
    for path, row in zip(REVIEWED_CONTROL_PATHS, rows, strict=True):
        _require_file_binding(row, path, f"seq97 reviewed input {path}")
        if path in FIXED_REVIEWED_CONTROL_BINDINGS:
            require(
                row == FIXED_REVIEWED_CONTROL_BINDINGS[path],
                f"seq97 fixed reviewed input differs: {path}",
            )
    return copy.deepcopy(rows)


def _require_reviewed_replacement_bindings(
    rows: Sequence[Mapping[str, Any]],
    replacement_contract_binding: Mapping[str, Any],
    replacement_runner_binding: Mapping[str, Any],
) -> None:
    """Bind the replacement authorities to the exact independently reviewed rows."""

    by_path = {row["path"]: row for row in rows}
    contract_row = by_path[R009_CONTRACT_REL.as_posix()]
    runner_row = by_path[R009_RUNNER_REL.as_posix()]
    _require_r009_contract_binding(replacement_contract_binding)
    require(
        replacement_contract_binding.get("file_sha256")
        == contract_row.get("sha256")
        and replacement_contract_binding.get("canonical_contract_sha256")
        == R009_CANONICAL_SHA256
        and isinstance(replacement_runner_binding, Mapping)
        and dict(replacement_runner_binding) == dict(runner_row),
        "seq97 replacement authority/review binding differs",
    )


def _exact_review_control_seq97_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool,
) -> dict[str, Any]:
    _require_immutable_review_lineage(root)
    require(
        type(require_live_snapshot) is bool,
        "reviewed control live-snapshot flag differs",
    )
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(isinstance(history, list), "reviewed control history differs")
    if len(history) == CORRECTION_SEQUENCE:
        candidate = copy.deepcopy(dict(checkpoint))
        tail = history[-1]
        require(
            isinstance(tail, dict)
            and type(tail.get("sequence")) is int
            and tail.get("sequence") == CORRECTION_SEQUENCE
            and tail.get("event_id") == CORRECTION_EVENT_ID
            and tail.get("event_type") == CORRECTION_EVENT_TYPE,
            "reviewed control seq97 correction differs",
        )
        require_snapshot_hygiene_corrected_checkpoint(
            root,
            candidate,
            require_live_snapshot=require_live_snapshot,
            run_external_validators=False,
        )
        return candidate
    require(
        len(history) == STARTED_SEQUENCE,
        "reviewed control authority requires exact seq97 or seq98",
    )
    started = importlib.import_module(
        "scripts.apply_walksafe_fp048_r002_goal_started_seq98_20260826"
    )
    validator = getattr(started, "require_started_checkpoint", None)
    inverse = getattr(started, "reconstructed_seq97_checkpoint_bytes", None)
    require(
        callable(validator) and callable(inverse),
        "seq98 reviewed control authority API differs",
    )
    validator(
        root,
        checkpoint,
        require_live_snapshot=require_live_snapshot,
        run_external_validators=False,
    )
    raw = inverse(root, checkpoint)
    candidate = seq90.strict_json(raw, "reconstructed seq97 review authority")
    require(
        raw == checkpoint_json_bytes(candidate),
        "reconstructed seq97 review authority bytes differ",
    )
    require_snapshot_hygiene_corrected_checkpoint(
        root,
        candidate,
        require_live_snapshot=False,
        run_external_validators=False,
    )
    return candidate


def noncredit_reviewed_control_successor_bindings(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool,
) -> dict[str, Any]:
    """Return the event-bound 14-file review cohort with zero product credit."""

    root = seq90._safe_root(root)
    require(isinstance(checkpoint, Mapping), "reviewed control checkpoint differs")
    seq97_checkpoint = _exact_review_control_seq97_checkpoint(
        root,
        checkpoint,
        require_live_snapshot=require_live_snapshot,
    )
    event = seq97_checkpoint["goal_execution"]["transition_history"][-1]
    review_binding = event.get("transition_control_review_binding")
    require(
        isinstance(review_binding, Mapping)
        and set(review_binding)
        == {"assignment", "review_result", "independent_review"},
        "reviewed control event binding differs",
    )
    assignment_read = seq90._stable_read(root, REVIEW_ASSIGNMENT_REL)
    require(
        _binding(REVIEW_ASSIGNMENT_REL, assignment_read.raw)
        == review_binding.get("assignment"),
        "reviewed control assignment bytes differ",
    )
    assignment = seq90.strict_json(
        assignment_read.raw,
        REVIEW_ASSIGNMENT_REL.as_posix(),
    )
    require(
        assignment_read.raw == seq90.canonical_json_bytes(assignment)
        and set(assignment) == ASSIGNMENT_FIELDS
        and assignment.get("document_id") == REVIEW_ASSIGNMENT_DOCUMENT_ID
        and assignment.get("round_id") == REVIEW_ROUND_ID
        and assignment.get("candidate_status") == FINAL_CANDIDATE_STATUS,
        "reviewed control assignment authority differs",
    )
    rows = _validated_reviewed_control_rows(
        assignment.get("reviewed_control_inputs")
    )
    if require_live_snapshot:
        for path, row in zip(REVIEWED_CONTROL_PATHS, rows, strict=True):
            require(
                _observed_binding(root, path) == row,
                f"reviewed control live binding differs: {path}",
            )
    require(
        set(REVIEWED_CONTROL_SUCCESSOR_CREDIT_BOUNDARY)
        == {
            "actual_device_test_credit_delta",
            "deployment_credit_delta",
            "external_review_credit_delta",
            "formal_test_credit_delta",
            "implementation_completion_credit_delta",
            "release_credit_delta",
        }
        and all(
            type(value) is int and value == 0
            for value in REVIEWED_CONTROL_SUCCESSOR_CREDIT_BOUNDARY.values()
        ),
        "reviewed control credit boundary differs",
    )
    return {
        "authority_label": REVIEWED_CONTROL_SUCCESSOR_AUTHORITY_LABEL,
        "bindings": rows,
        "credit_boundary": copy.deepcopy(
            REVIEWED_CONTROL_SUCCESSOR_CREDIT_BOUNDARY
        ),
    }


def _r009_file_binding(path: Path, root: Path, allow_provisional: bool) -> dict[str, Any]:
    expected = FIXED_REVIEWED_CONTROL_BINDINGS[path]
    if not _is_final_binding(expected, path):
        require(allow_provisional, f"R009 binding is unfrozen: {path}")
        return copy.deepcopy(expected)
    observed = _observed_binding(root, path)
    require(observed == expected, f"R009 binding differs: {path}")
    return observed


def r009_contract_binding(
    root: Path = ROOT,
    *,
    allow_provisional: bool = False,
) -> dict[str, Any]:
    root = seq90._safe_root(root)
    file_binding = _r009_file_binding(R009_CONTRACT_REL, root, allow_provisional)
    if not _is_final_binding(file_binding, R009_CONTRACT_REL):
        return {
            "schema_version": "1.2",
            "document_id": R009_DOCUMENT_ID,
            "path": R009_CONTRACT_REL.as_posix(),
            "file_sha256": UNFROZEN_SHA256,
            "contract_id": R009_CONTRACT_ID,
            "contract_version": R009_CONTRACT_VERSION,
            "canonical_contract_sha256": UNFROZEN_SHA256,
        }
    raw = seq90._stable_read(root, R009_CONTRACT_REL).raw
    document = seq90.strict_json(raw, R009_CONTRACT_REL.as_posix())
    supersedes = document.get("supersedes")
    observed = {
        "schema_version": document.get("schema_version"),
        "document_id": document.get("document_id"),
        "path": R009_CONTRACT_REL.as_posix(),
        "file_sha256": file_binding["sha256"],
        "contract_id": document.get("contract_id"),
        "contract_version": document.get("contract_version"),
        "canonical_contract_sha256": continuation.canonical_json_sha256(document),
    }
    require(
        raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.2"
        and document.get("document_id") == R009_DOCUMENT_ID
        and document.get("contract_id") == R009_CONTRACT_ID
        and document.get("contract_version") == R009_CONTRACT_VERSION
        and document.get("target_goal_id") == GOAL_ID
        and document.get("target_goal_content_sha256") == GOAL_SHA256
        and document.get("successor_reason_code") == R008_FAILURE_REASON_CODE
        and isinstance(supersedes, dict)
        and supersedes.get("contract_id") == R008_CONTRACT_BINDING["contract_id"]
        and supersedes.get("contract_version")
        == R008_CONTRACT_BINDING["contract_version"]
        and supersedes.get("source_correction_event_id") == CORRECTION_EVENT_ID
        and supersedes.get("source_correction_event_sequence") == CORRECTION_SEQUENCE
        and observed["canonical_contract_sha256"] == R009_CANONICAL_SHA256,
        "R009 contract authority differs",
    )
    return observed


def r009_runner_binding(
    root: Path = ROOT,
    *,
    allow_provisional: bool = False,
) -> dict[str, Any]:
    return _r009_file_binding(R009_RUNNER_REL, seq90._safe_root(root), allow_provisional)


def _source_checkpoint_binding(
    r008_preflight: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "path": CHECKPOINT_REL.as_posix(),
        "sha256": SOURCE_CHECKPOINT_SHA256,
        "byte_length": SOURCE_CHECKPOINT_BYTE_LENGTH,
        "sequence": SOURCE_SEQUENCE,
        "tail_event_id": SOURCE_EVENT_ID,
        "tail_event_sha256": SOURCE_EVENT_SHA256,
        "r008_preflight_attempt_004": copy.deepcopy(
            dict(r008_preflight)
            if r008_preflight is not None
            else R008_PREFLIGHT_ATTEMPT_004
        ),
    }


def _candidate_status(allow_provisional: bool) -> str:
    final = pins_are_final()
    require(final or allow_provisional, "seq97 reviewed pins are not final")
    return FINAL_CANDIDATE_STATUS if final else PROVISIONAL_STATUS


def _authorization_status(allow_provisional: bool) -> str:
    final = pins_are_final()
    require(final or allow_provisional, "seq97 reviewed pins are not final")
    return FINAL_AUTHORIZATION_STATUS if final else PROVISIONAL_STATUS


def build_authorization(
    root: Path,
    source: Mapping[str, Any],
    *,
    allow_provisional: bool = False,
) -> bytes:
    root = seq90._safe_root(root)
    require_exact_seq96_source(checkpoint_json_bytes(source), source, root)
    status = _authorization_status(allow_provisional)
    value = {
        "schema_version": "1.0",
        "document_id": "WS-FP048-R002-SEQ97-98-AUTHORIZATION-20260826-001",
        "evidence_type": "USER_CONTINUATION_AUTHORIZATION",
        "authorization_status": status,
        "goal_id": GOAL_ID,
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "r008_preflight_attempt_004": _stored_r008_preflight_attempt_004(),
        "previous_contract_binding": copy.deepcopy(R008_CONTRACT_BINDING),
        "replacement_contract_binding": r009_contract_binding(
            root, allow_provisional=allow_provisional
        ),
        "contract_correction_event_id": CORRECTION_EVENT_ID,
        "projected_transition": _projected_transition(),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "authorized_executor": copy.deepcopy(EXECUTOR),
        "required_independent_reviewer": copy.deepcopy(REVIEWER),
    }
    require(set(value) == AUTHORIZATION_FIELDS, "seq97 authorization fields differ")
    return seq90.canonical_json_bytes(value)


def build_review_assignment(
    root: Path,
    source: Mapping[str, Any],
    *,
    allow_provisional: bool = False,
) -> bytes:
    root = seq90._safe_root(root)
    _require_immutable_review_lineage(root)
    status = _candidate_status(allow_provisional)
    authorization_raw = build_authorization(
        root, source, allow_provisional=allow_provisional
    )
    value = {
        "schema_version": "1.0",
        "document_id": REVIEW_ASSIGNMENT_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_REVIEW_ASSIGNMENT",
        "round_id": REVIEW_ROUND_ID,
        "candidate_status": status,
        "goal_id": GOAL_ID,
        "authorization_binding": _binding(AUTHORIZATION_REL, authorization_raw),
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "r008_preflight_attempt_004": _stored_r008_preflight_attempt_004(),
        "previous_contract_binding": copy.deepcopy(R008_CONTRACT_BINDING),
        "replacement_contract_binding": r009_contract_binding(
            root, allow_provisional=allow_provisional
        ),
        "replacement_runner_binding": r009_runner_binding(
            root, allow_provisional=allow_provisional
        ),
        "reviewed_control_inputs": _reviewed_control_bindings(
            root, allow_provisional=allow_provisional
        ),
        "required_reviewer": copy.deepcopy(REVIEWER),
        "executor": copy.deepcopy(EXECUTOR),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "required_findings": {"P0": 0, "P1": 0, "P2": 0},
    }
    require(set(value) == ASSIGNMENT_FIELDS, "seq97 assignment fields differ")
    return seq90.canonical_json_bytes(value)


def prepare_review_manifest(
    root: Path = ROOT,
    *,
    allow_provisional: bool = False,
) -> dict[str, Any]:
    _raw, source = load_exact_seq96_source(root)
    authorization = build_authorization(
        root, source, allow_provisional=allow_provisional
    )
    assignment = build_review_assignment(
        root, source, allow_provisional=allow_provisional
    )
    return {
        "publishable": pins_are_final(),
        "candidate_status": (
            "FINAL_REVIEW_CANDIDATE"
            if pins_are_final()
            else "PROVISIONAL_DO_NOT_PUBLISH"
        ),
        "authorization": _binding(AUTHORIZATION_REL, authorization),
        "assignment": _binding(REVIEW_ASSIGNMENT_REL, assignment),
        "review_root": REVIEW_ROOT.as_posix(),
        "required_reviewer": copy.deepcopy(REVIEWER),
        "required_absent_before_review": [
            REVIEW_RESULT_REL.as_posix(),
            INDEPENDENT_REVIEW_REL.as_posix(),
        ],
    }


def _authorization_binding(root: Path, source: Mapping[str, Any]) -> dict[str, Any]:
    _require_immutable_review_lineage(root)
    require(pins_are_final(), "seq97 authorization cannot use provisional pins")
    raw = seq90._stable_read(root, AUTHORIZATION_REL).raw
    require(raw == build_authorization(root, source), "seq97 authorization differs")
    mode = stat.S_IMODE((root / AUTHORIZATION_REL).lstat().st_mode)
    require(mode == 0o600, "seq97 authorization mode differs")
    return _binding(AUTHORIZATION_REL, raw)


def _load_physical_authorization(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_inputs: bool,
    replacement_contract_binding: Mapping[str, Any],
) -> dict[str, Any]:
    _require_immutable_review_lineage(root)
    raw = seq90._stable_read(root, AUTHORIZATION_REL).raw
    document = seq90.strict_json(raw, AUTHORIZATION_REL.as_posix())
    require(
        raw == seq90.canonical_json_bytes(document)
        and stat.S_IMODE((root / AUTHORIZATION_REL).lstat().st_mode) == 0o600
        and set(document) == AUTHORIZATION_FIELDS,
        "seq97 authorization bytes, mode, or fields differ",
    )
    if require_live_inputs:
        require(
            raw == build_authorization(root, source),
            "seq97 authorization differs",
        )
    _require_r009_contract_binding(replacement_contract_binding)
    require(
        document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ97-98-AUTHORIZATION-20260826-001"
        and document.get("evidence_type") == "USER_CONTINUATION_AUTHORIZATION"
        and document.get("authorization_status") == FINAL_AUTHORIZATION_STATUS
        and document.get("goal_id") == GOAL_ID
        and document.get("source_checkpoint_binding") == _source_checkpoint_binding()
        and document.get("r008_preflight_attempt_004")
        == R008_PREFLIGHT_ATTEMPT_004
        and document.get("previous_contract_binding") == R008_CONTRACT_BINDING
        and document.get("replacement_contract_binding")
        == replacement_contract_binding
        and document.get("contract_correction_event_id") == CORRECTION_EVENT_ID
        and document.get("projected_transition") == _projected_transition()
        and document.get("claim_boundary") == CLAIM_BOUNDARY
        and document.get("authorized_executor") == EXECUTOR
        and document.get("required_independent_reviewer") == REVIEWER,
        "seq97 authorization authority differs",
    )
    return _binding(AUTHORIZATION_REL, raw)


def _load_physical_review(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_inputs: bool,
    authorization_binding_value: Mapping[str, Any],
    replacement_contract_binding: Mapping[str, Any],
    replacement_runner_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], datetime, datetime]:
    _require_immutable_review_lineage(root)
    raws = {path: seq90._stable_read(root, path).raw for path in REVIEW_PATHS}
    documents = {
        path: seq90.strict_json(raw, path.as_posix())
        for path, raw in raws.items()
    }
    assignment = documents[REVIEW_ASSIGNMENT_REL]
    result = documents[REVIEW_RESULT_REL]
    independent = documents[INDEPENDENT_REVIEW_REL]
    assignment_binding = _binding(REVIEW_ASSIGNMENT_REL, raws[REVIEW_ASSIGNMENT_REL])
    result_binding = _binding(REVIEW_RESULT_REL, raws[REVIEW_RESULT_REL])
    review_binding = {
        "assignment": assignment_binding,
        "review_result": result_binding,
        "independent_review": _binding(
            INDEPENDENT_REVIEW_REL, raws[INDEPENDENT_REVIEW_REL]
        ),
    }
    require(
        all(
            raw == seq90.canonical_json_bytes(documents[path])
            and stat.S_IMODE((root / path).lstat().st_mode) == 0o600
            for path, raw in raws.items()
        ),
        "seq97 review bytes or modes differ",
    )
    if require_live_inputs:
        require(
            raws[REVIEW_ASSIGNMENT_REL] == build_review_assignment(root, source),
            "seq97 review assignment differs",
        )
    require(
        set(assignment) == ASSIGNMENT_FIELDS
        and set(result) == REVIEW_RESULT_FIELDS
        and set(independent) == INDEPENDENT_REVIEW_FIELDS
        and assignment.get("schema_version") == "1.0"
        and assignment.get("document_id") == REVIEW_ASSIGNMENT_DOCUMENT_ID
        and assignment.get("evidence_type")
        == "TRANSITION_CONTROL_REVIEW_ASSIGNMENT"
        and assignment.get("round_id") == REVIEW_ROUND_ID
        and assignment.get("candidate_status") == "FINAL_REVIEW_CANDIDATE"
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("authorization_binding")
        == authorization_binding_value
        and assignment.get("required_reviewer") == REVIEWER
        and assignment.get("executor") == EXECUTOR
        and assignment.get("source_checkpoint_binding") == _source_checkpoint_binding()
        and assignment.get("r008_preflight_attempt_004")
        == R008_PREFLIGHT_ATTEMPT_004
        and assignment.get("previous_contract_binding") == R008_CONTRACT_BINDING
        and assignment.get("replacement_contract_binding")
        == replacement_contract_binding
        and assignment.get("replacement_runner_binding")
        == replacement_runner_binding
        and assignment.get("claim_boundary") == CLAIM_BOUNDARY
        and assignment.get("required_findings") == {"P0": 0, "P1": 0, "P2": 0}
        and result.get("schema_version") == "1.0"
        and result.get("document_id") == REVIEW_RESULT_DOCUMENT_ID
        and result.get("evidence_type") == "TRANSITION_CONTROL_REVIEW_RESULT"
        and result.get("round_id") == REVIEW_ROUND_ID
        and result.get("assignment_binding") == assignment_binding
        and result.get("reviewer") == REVIEWER
        and result.get("decision") == "APPROVED"
        and result.get("findings") == {"P0": 0, "P1": 0, "P2": 0}
        and result.get("external_independence_claimed") is False
        and independent.get("schema_version") == "1.0"
        and independent.get("document_id") == INDEPENDENT_REVIEW_DOCUMENT_ID
        and independent.get("evidence_type")
        == "TRANSITION_CONTROL_INDEPENDENT_REVIEW"
        and independent.get("round_id") == REVIEW_ROUND_ID
        and independent.get("assignment_binding") == assignment_binding
        and independent.get("review_result_binding") == result_binding
        and independent.get("reviewer") == REVIEWER
        and independent.get("decision") == "APPROVED"
        and independent.get("findings") == {"P0": 0, "P1": 0, "P2": 0}
        and independent.get("external_independence_claimed") is False,
        "seq97 independent review authority differs",
    )
    reviewed_rows = _validated_reviewed_control_rows(
        assignment.get("reviewed_control_inputs")
    )
    _require_reviewed_replacement_bindings(
        reviewed_rows,
        replacement_contract_binding,
        replacement_runner_binding,
    )
    result_at = _parse_time(result.get("reviewed_at"), "seq97 review result time")
    independent_at = _parse_time(
        independent.get("reviewed_at"), "seq97 independent review time"
    )
    require(result_at < independent_at, "seq97 review chronology differs")
    return review_binding, result_at, independent_at


def _final_managed_paths(
    source: Mapping[str, Any],
    visible: Sequence[str],
) -> tuple[str, ...]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    paths.update(
        path
        for path in visible
        if not path.startswith("docs/control/execution/goal-gates/")
    )
    paths.discard(CHECKPOINT_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEWED_CONTROL_PATHS)
    paths.add(AUTHORIZATION_REL.as_posix())
    paths.add(R001_REVIEW_ASSIGNMENT_REL.as_posix())
    paths.add(R002_REVIEW_ASSIGNMENT_REL.as_posix())
    paths.add(R003_REVIEW_ASSIGNMENT_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEW_PATHS)
    return tuple(sorted(paths))


def _event_time(
    source: Mapping[str, Any],
    supplied: str | None,
    independent_at: datetime,
) -> str:
    candidate = (
        _parse_time(supplied, "seq97 occurred_at")
        if supplied is not None
        else datetime.now().astimezone().replace(microsecond=0)
    )
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq96 occurred_at",
    )
    return max(candidate, source_at + timedelta(seconds=1), independent_at + timedelta(seconds=1)).isoformat()


def project_seq97(
    root: Path,
    source: Mapping[str, Any],
    *,
    managed_paths: Sequence[str],
    path_set_sha256: str,
    content_set_sha256: str,
    occurred_at: str,
    authorization_binding_value: Mapping[str, Any],
    review_binding: Mapping[str, Any],
    r008_preflight_binding: Mapping[str, Any],
    replacement_contract_binding: Mapping[str, Any],
    replacement_runner_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = seq90._safe_root(root)
    require_exact_seq96_source(checkpoint_json_bytes(source), source, root)
    paths = sorted(set(managed_paths))
    require(
        paths == list(managed_paths)
        and all(
            isinstance(path, str)
            and path
            and not Path(path).is_absolute()
            and ".." not in Path(path).parts
            for path in paths
        )
        and path_set_sha256
        == hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
        and isinstance(content_set_sha256, str)
        and seq90.SHA256_RE.fullmatch(content_set_sha256) is not None,
        "seq97 managed paths differ",
    )
    require(
        dict(r008_preflight_binding) == R008_PREFLIGHT_ATTEMPT_004,
        "seq97 R008 failure observation differs",
    )
    require(
        isinstance(review_binding, Mapping)
        and set(review_binding)
        == {"assignment", "review_result", "independent_review"},
        "seq97 review binding differs",
    )
    for binding, path, label in (
        (authorization_binding_value, AUTHORIZATION_REL, "authorization"),
        (review_binding["assignment"], REVIEW_ASSIGNMENT_REL, "assignment"),
        (review_binding["review_result"], REVIEW_RESULT_REL, "review result"),
        (
            review_binding["independent_review"],
            INDEPENDENT_REVIEW_REL,
            "independent review",
        ),
    ):
        _require_file_binding(binding, path, f"seq97 {label} binding")
    _require_r009_contract_binding(replacement_contract_binding)
    _require_file_binding(
        replacement_runner_binding,
        R009_RUNNER_REL,
        "seq97 R009 runner binding",
    )
    occurred = _parse_time(occurred_at, "seq97 occurred_at")
    source_event = source["goal_execution"]["transition_history"][-1]
    require(
        source_event.get("contract_supersession", {}).get(
            "replacement_contract_binding"
        )
        == R008_CONTRACT_BINDING
        and source_event.get("start_gate_runner_binding") == R008_RUNNER_BINDING,
        "seq97 R008 predecessor authority differs",
    )
    projected = copy.deepcopy(source)
    current = projected["current_work"]
    current.update(
        {
            "status": "READY",
            "current_focus": CURRENT_FOCUS,
            "next_action": NEXT_ACTION,
            "release_completion_claimed": False,
        }
    )
    snapshot = projected["working_tree_snapshot"]
    snapshot.update(
        {
            "scope": SCOPE,
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": path_set_sha256,
            "content_set_sha256": content_set_sha256,
        }
    )
    handoff = projected["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(paths)
    mirror = handoff["source_commit_or_snapshot"]
    mirror.update(
        {
            "file_count": len(paths),
            "path_set_sha256": path_set_sha256,
            "content_set_sha256": content_set_sha256,
        }
    )
    handoff.update(
        {
            "current_epic": "EPIC-03 / FP-048 R002 snapshot hygiene recovery",
            "last_updated_by_work_item": WORK_ITEM_ID,
            "last_verification_status": "SEQ97_R009_CORRECTION_REVIEWED_ZERO_CREDIT",
            "next_single_action": NEXT_ACTION,
        }
    )
    source_binding = _source_checkpoint_binding(r008_preflight_binding)
    event: dict[str, Any] = {
        "sequence": CORRECTION_SEQUENCE,
        "event_id": CORRECTION_EVENT_ID,
        "event_type": CORRECTION_EVENT_TYPE,
        "occurred_on": occurred.date().isoformat(),
        "occurred_at": occurred_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "subject_goal_id": GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": copy.deepcopy(source_event["runtime_after"]),
        "blockers_after": copy.deepcopy(source_event["blockers_after"]),
        "blocker_resolution_ids_after": copy.deepcopy(
            source_event["blocker_resolution_ids_after"]
        ),
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [
            "FP048-R002_EXACT_PUBLISHED_SEQ96_SOURCE",
            "FP048-R002_R008_SNAPSHOT_ANCESTOR_PREVIEW_NONAUTHORITY",
            "FP048-R002_R009_SNAPSHOT_SAFE_GATE_CONTRACT",
            "FP048-R002_SEQ97_98_TRANSITION_CONTROL_REVIEW",
        ],
        "source_checkpoint_binding": source_binding,
        "source_ready_event_binding": copy.deepcopy(
            source_event["source_ready_event_binding"]
        ),
        "authorization_binding": copy.deepcopy(dict(authorization_binding_value)),
        "contract_supersession": {
            "previous_contract_binding": copy.deepcopy(R008_CONTRACT_BINDING),
            "reason_code": R008_FAILURE_REASON_CODE,
            "replacement_contract_binding": copy.deepcopy(
                dict(replacement_contract_binding)
            ),
        },
        "start_gate_runner_binding": copy.deepcopy(dict(replacement_runner_binding)),
        "transition_control_review_binding": copy.deepcopy(dict(review_binding)),
        "repository_context_reanchor": {
            "before": {
                **copy.deepcopy(source_binding),
                "current_work": copy.deepcopy(source["current_work"]),
                "working_tree_snapshot": copy.deepcopy(
                    source["working_tree_snapshot"]
                ),
                "session_handoff": copy.deepcopy(source["session_handoff"]),
            },
            "after": {
                "base_commit": source["working_tree_snapshot"]["base_head"],
                "branch": source["session_handoff"]["branch"],
                "logical_branch": source["session_handoff"]["branch"],
                "logical_branch_semantics": source_event[
                    "repository_context_reanchor"
                ]["after"]["logical_branch_semantics"],
                "physical_git_branch": source_event[
                    "repository_context_reanchor"
                ]["after"]["physical_git_branch"],
                "branch_mismatch_reason_code": source_event[
                    "repository_context_reanchor"
                ]["after"]["branch_mismatch_reason_code"],
                "current_head": mirror["current_head"],
                "managed_changed_path_count": len(paths),
                "path_set_sha256": path_set_sha256,
                "content_set_sha256": content_set_sha256,
            },
        },
        "noncredit_successor_edges": copy.deepcopy(
            source_event["noncredit_successor_edges"]
        ),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "unchanged_control_projection": (
            seq96.seq95.seq94.seq93.seq92.correction._unchanged_projection(projected)
        ),
        "canonical_binding_snapshot_after": copy.deepcopy(
            source_event["canonical_binding_snapshot_after"]
        ),
        "correction_reason": {
            "failed_contract_id": R008_CONTRACT_BINDING["contract_id"],
            "failed_contract_version": R008_CONTRACT_BINDING["contract_version"],
            "r008_preflight_attempt_004": copy.deepcopy(
                dict(r008_preflight_binding)
            ),
            "reason_code": R008_FAILURE_REASON_CODE,
            "remediation": "SUPERSEDE_R008_WITH_SNAPSHOT_SAFE_R009_BEFORE_SEQ98_START",
        },
        "previous_event_sha256": SOURCE_EVENT_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq97 correction event field set differs")
    state = projected["goal_execution"]
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    require(
        state["goal_status"] == "READY"
        and state["status_by_goal"][GOAL_ID] == "READY"
        and current["status"] == "READY"
        and "IN_PROGRESS" not in state["status_by_goal"].values(),
        "seq97 READY state differs",
    )
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "verification_boundary",
    ):
        require(
            _strict_json_equal(projected[field], source[field]),
            f"seq97 changed {field}",
        )
    require(
        all(
            type(value) is int and value == 0
            for key, value in CLAIM_BOUNDARY.items()
            if key.endswith("_credit_delta") or key == "goal_status_change_count"
        )
        and CLAIM_BOUNDARY["implementation_start_authorized"] is False,
        "seq97 credit boundary differs",
    )
    return projected, event


def _repository_before(event: Mapping[str, Any]) -> dict[str, Any]:
    context = event.get("repository_context_reanchor")
    before = context.get("before") if isinstance(context, dict) else None
    require(
        isinstance(context, dict)
        and set(context) == {"before", "after"}
        and isinstance(before, dict)
        and all(key in before for key in _source_checkpoint_binding())
        and {
            key: before.get(key) for key in _source_checkpoint_binding()
        }
        == _source_checkpoint_binding()
        and all(
            key in before
            for key in ("current_work", "working_tree_snapshot", "session_handoff")
        ),
        "seq97 repository before-context differs",
    )
    return before


def _restored_seq96_checkpoint(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq96 inverse source is not exact seq97",
    )
    before = _repository_before(history[-1])
    restored = copy.deepcopy(checkpoint)
    restored_state = restored["goal_execution"]
    source_event = restored_state["transition_history"][SOURCE_SEQUENCE - 1]
    restored_state["transition_history"] = restored_state["transition_history"][
        :SOURCE_SEQUENCE
    ]
    restored_state["transition_history_anchor_sha256"] = source_event["event_sha256"]
    restored_state["validation_cutoff_at"] = source_event["occurred_at"]
    restored["current_work"] = copy.deepcopy(before["current_work"])
    restored["working_tree_snapshot"] = copy.deepcopy(
        before["working_tree_snapshot"]
    )
    restored["session_handoff"] = copy.deepcopy(before["session_handoff"])
    return restored


def require_snapshot_hygiene_corrected_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
    run_external_validators: bool = False,
) -> None:
    root = seq90._safe_root(root)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "checkpoint is not exact seq97 snapshot-hygiene correction",
    )
    event = history[-1]
    require(
        isinstance(event, dict)
        and set(event) == EVENT_FIELDS
        and type(event.get("sequence")) is int
        and event.get("sequence") == CORRECTION_SEQUENCE
        and event.get("event_id") == CORRECTION_EVENT_ID
        and event.get("event_type") == CORRECTION_EVENT_TYPE
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("previous_event_sha256") == SOURCE_EVENT_SHA256
        and event.get("event_sha256") == continuation.event_sha256(event)
        and event.get("source_checkpoint_binding") == _source_checkpoint_binding()
        and event.get("claim_boundary") == CLAIM_BOUNDARY
        and event.get("contract_supersession", {}).get("previous_contract_binding")
        == R008_CONTRACT_BINDING
        and event.get("contract_supersession", {}).get("reason_code")
        == R008_FAILURE_REASON_CODE,
        "seq97 correction authority differs",
    )
    if require_live_snapshot:
        _require_r008_namespace_absent(root)
    source = _restored_seq96_checkpoint(checkpoint)
    source_raw = checkpoint_json_bytes(source)
    require_exact_seq96_source(source_raw, source, root)
    replacement_contract = event["contract_supersession"][
        "replacement_contract_binding"
    ]
    replacement_runner = event["start_gate_runner_binding"]
    authorization = _load_physical_authorization(
        root,
        source,
        require_live_inputs=require_live_snapshot,
        replacement_contract_binding=replacement_contract,
    )
    require(
        event.get("authorization_binding") == authorization,
        "seq97 event authorization binding differs",
    )
    review, result_at, independent_at = _load_physical_review(
        root,
        source,
        require_live_inputs=require_live_snapshot,
        authorization_binding_value=authorization,
        replacement_contract_binding=replacement_contract,
        replacement_runner_binding=replacement_runner,
    )
    event_at = _parse_time(event.get("occurred_at"), "seq97 occurred_at")
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq96 occurred_at",
    )
    require(
        source_at < result_at < independent_at < event_at,
        "seq97 event/review chronology differs",
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    require(isinstance(paths, list), "seq97 managed paths differ")
    expected, expected_event = project_seq97(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=snapshot.get("path_set_sha256"),
        content_set_sha256=snapshot.get("content_set_sha256"),
        occurred_at=event.get("occurred_at"),
        authorization_binding_value=event["authorization_binding"],
        review_binding=review,
        r008_preflight_binding=R008_PREFLIGHT_ATTEMPT_004,
        replacement_contract_binding=replacement_contract,
        replacement_runner_binding=replacement_runner,
    )
    require(
        checkpoint_json_bytes(expected) == checkpoint_json_bytes(checkpoint)
        and expected_event == event,
        "seq97 projected checkpoint differs",
    )
    if require_live_snapshot:
        require(
            continuation.working_snapshot_hashes(root, paths)
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "seq97 managed snapshot differs",
        )
    if run_external_validators:
        seq90._validate_projected_with_consumers(root, checkpoint)


def reconstructed_seq96_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    require_snapshot_hygiene_corrected_checkpoint(
        root,
        checkpoint,
        require_live_snapshot=False,
        run_external_validators=False,
    )
    restored = _restored_seq96_checkpoint(checkpoint)
    raw = checkpoint_json_bytes(restored)
    require_exact_seq96_source(raw, restored, root)
    return raw


def canonical_seq97_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    require_snapshot_hygiene_corrected_checkpoint(
        root,
        checkpoint,
        require_live_snapshot=False,
        run_external_validators=False,
    )
    return checkpoint_json_bytes(checkpoint)


def reconstructed_seq97_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq98 inverse is owned by the seq98 starter",
    )
    return canonical_seq97_checkpoint_bytes(root, checkpoint)


@dataclass(frozen=True)
class Prepared:
    transport: Any

    @property
    def source(self) -> dict[str, Any]:
        return self.transport.source

    @property
    def projected(self) -> dict[str, Any]:
        return self.transport.projected

    @property
    def event(self) -> dict[str, Any]:
        return self.transport.event


def _require_prepared_exact(prepared: Prepared) -> None:
    transport = prepared.transport
    require(
        transport.source_raw == checkpoint_json_bytes(transport.source)
        and transport.projected_raw == checkpoint_json_bytes(transport.projected)
        and transport.event
        == transport.projected["goal_execution"]["transition_history"][-1],
        "prepared seq97 payload differs",
    )
    require_exact_seq96_source(transport.source_raw, transport.source, transport.root)


def _require_prepared_live_exact(
    prepared: Prepared,
    *,
    check_git_status: bool,
) -> None:
    transport = prepared.transport
    _require_prepared_exact(prepared)
    _require_immutable_review_lineage(transport.root)
    source = seq90._stable_read(transport.root, CHECKPOINT_REL)
    require(
        source.identity == transport.source_identity
        and source.raw == transport.source_raw,
        "live seq96 source changed during seq97 preparation",
    )
    _require_source_mode(transport.root)
    require(
        r008_preflight_attempt_004_observation(transport.root)
        == R008_PREFLIGHT_ATTEMPT_004,
        "R008 observation changed during seq97 preparation",
    )
    seq90._require_inputs_unchanged(transport.root, transport.retained_inputs)
    seq90._require_managed_inputs_unchanged(
        transport.root, transport.managed_inputs
    )
    seq90._require_managed_inputs_unchanged(
        transport.root,
        transport.git_visible_inputs,
        label="full Git-visible input",
    )
    seq90._require_git_context(
        transport.root, transport.git_head, transport.git_branch
    )
    if check_git_status:
        status_raw, _visible = seq90.capture_git_visible_paths(transport.root)
        require(
            status_raw == transport.git_status_raw,
            "Git-visible cohort changed during seq97 preparation",
        )


def prepare(
    root: Path = ROOT,
    *,
    occurred_at: str | None = None,
    validate_consumers: bool = True,
) -> Prepared:
    root = seq90._safe_root(root)
    require(pins_are_final(), "seq97 reviewed pins are not final")
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    source = seq90.strict_json(source_read.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq96_source(source_read.raw, source, root)
    _require_source_mode(root)
    r008 = r008_preflight_attempt_004_observation(root)
    git_head, git_branch = seq90._capture_git_context(root)
    expected_head = source["session_handoff"]["source_commit_or_snapshot"][
        "current_head"
    ]
    require(git_head == expected_head, "live Git HEAD differs from seq96 source")
    status_raw, visible = seq90.capture_git_visible_paths(root)
    paths = _final_managed_paths(source, visible)
    managed = seq90._capture_managed_inputs(root, paths)
    visible_inputs = seq90._capture_managed_inputs(root, visible)
    path_sha256, content_sha256 = seq90._managed_input_snapshot_hashes(managed)
    authorization = _authorization_binding(root, source)
    replacement_contract = r009_contract_binding(root)
    replacement_runner = r009_runner_binding(root)
    review, _result_at, independent_at = _load_physical_review(
        root,
        source,
        require_live_inputs=True,
        authorization_binding_value=authorization,
        replacement_contract_binding=replacement_contract,
        replacement_runner_binding=replacement_runner,
    )
    projected, event = project_seq97(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at=_event_time(source, occurred_at, independent_at),
        authorization_binding_value=authorization,
        review_binding=review,
        r008_preflight_binding=r008,
        replacement_contract_binding=replacement_contract,
        replacement_runner_binding=replacement_runner,
    )
    retained_paths = set(REVIEWED_CONTROL_PATHS) | set(REVIEW_PATHS) | {
        AUTHORIZATION_REL,
        R001_REVIEW_ASSIGNMENT_REL,
        R002_REVIEW_ASSIGNMENT_REL,
        R003_REVIEW_ASSIGNMENT_REL,
        seq96.R008_CONTRACT_REL,
        seq96.R008_RUNNER_REL,
    }
    retained = {
        path: seq90._stable_read(root, path)
        for path in sorted(retained_paths, key=lambda value: value.as_posix())
    }
    transport = seq90.Prepared(
        root=root,
        source_raw=source_read.raw,
        source_identity=source_read.identity,
        source=source,
        projected=projected,
        projected_raw=checkpoint_json_bytes(projected),
        event=event,
        retained_inputs=retained,
        managed_inputs=managed,
        git_visible_inputs=visible_inputs,
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    prepared = Prepared(transport)
    _require_prepared_live_exact(prepared, check_git_status=True)
    if validate_consumers:
        seq90._validate_projected_with_consumers(root, projected)
        _require_prepared_live_exact(prepared, check_git_status=True)
    return prepared


def write_checkpoint(prepared: Prepared) -> None:
    _require_prepared_live_exact(prepared, check_git_status=True)
    seq90.write_checkpoint(
        prepared.transport,
        commit_guard=lambda: _require_prepared_live_exact(
            prepared, check_git_status=False
        ),
    )
    try:
        observed = seq90._stable_read(prepared.transport.root, CHECKPOINT_REL).raw
        require(
            observed == prepared.transport.projected_raw,
            "published seq97 checkpoint bytes differ",
        )
        mode = stat.S_IMODE(
            (prepared.transport.root / CHECKPOINT_REL).lstat().st_mode
        )
        require(
            mode == SOURCE_CHECKPOINT_MODE,
            "published seq97 checkpoint mode differs",
        )
        published = seq90.strict_json(observed, CHECKPOINT_REL.as_posix())
        require_snapshot_hygiene_corrected_checkpoint(
            prepared.transport.root,
            published,
            require_live_snapshot=True,
            run_external_validators=False,
        )
    except seq90.PostcommitUncertain:
        raise
    except BaseException as exc:
        raise seq90.PostcommitUncertain(
            "published seq97 checkpoint terminal authority is uncertain"
        ) from exc


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare-authorization", action="store_true")
    mode.add_argument("--prepare-review", action="store_true")
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--occurred-at")
    parser.add_argument("--allow-provisional", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    published = False
    try:
        if args.prepare_authorization:
            _raw, source = load_exact_seq96_source(args.root)
            _require_immutable_review_lineage(args.root)
            authorization = build_authorization(
                args.root,
                source,
                allow_provisional=args.allow_provisional,
            )
            sys.stdout.buffer.write(authorization)
            return 0 if pins_are_final() else 1
        if args.prepare_review:
            manifest = prepare_review_manifest(
                args.root,
                allow_provisional=args.allow_provisional,
            )
            print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
            return 0 if manifest["publishable"] else 1
        prepared = prepare(args.root, occurred_at=args.occurred_at)
        if args.write:
            write_checkpoint(prepared)
            published = True
        try:
            print(
                "FP048-R002 seq97 snapshot-hygiene correction: PASS "
                f"event_sha256={prepared.event['event_sha256']} "
                f"published={str(published).lower()}"
            )
        except BaseException as exc:
            if published:
                raise seq90.PostcommitUncertain(
                    "seq97 was published but success reporting is uncertain"
                ) from exc
            raise
        return 0
    except seq90.PostcommitUncertain as exc:
        print(
            "FP048-R002 seq97 snapshot-hygiene correction: "
            "POSTCOMMIT-UNCERTAIN: " + str(exc),
            file=sys.stderr,
        )
        return 2
    except (OSError, RuntimeError, TypeError, ValueError) as exc:
        print(
            "FP048-R002 seq97 snapshot-hygiene correction: FAIL: " + str(exc),
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
