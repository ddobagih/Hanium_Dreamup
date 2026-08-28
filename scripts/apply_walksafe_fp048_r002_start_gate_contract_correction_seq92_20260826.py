#!/usr/bin/env python3
"""Prepare the reviewed zero-credit FP048 R002 seq92 gate-contract correction."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (
    apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826
    as correction,
)
from scripts import (
    apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826 as seq90,
)
from scripts import check_walksafe_project_continuation_v2_4 as continuation


class ContractCorrectionError(seq90.ControlReanchorError):
    """The seq92 contract correction cannot be proven or published."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractCorrectionError(message)


CHECKPOINT_REL = seq90.CHECKPOINT_REL
checkpoint_json_bytes = seq90.checkpoint_json_bytes
GOAL_ID = correction.GOAL_ID
GOAL_PATH = correction.GOAL_PATH
GOAL_SHA256 = correction.GOAL_SHA256
WORK_ITEM_ID = correction.WORK_ITEM_ID
MANIFEST_SHA256 = correction.MANIFEST_SHA256

SOURCE_SEQUENCE = 91
SOURCE_EVENT_ID = correction.CORRECTION_EVENT_ID
SOURCE_EVENT_SHA256 = (
    "6e3cbd8e36aa4f9a46c2ca5da83768908ec19a872534783e5e479ad57ef79dcf"
)
SOURCE_CHECKPOINT_SHA256 = (
    "0de40f9b0579c8928fd8e42ab2091765992be82ef44dd17f2f897968561c0271"
)
SOURCE_CHECKPOINT_BYTE_LENGTH = 2_816_533

CORRECTION_SEQUENCE = 92
CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-CONTRACT-CORRECTED-"
    "FP048-R002-20260826-001"
)
CORRECTION_EVENT_TYPE = "GOAL_START_GATE_CONTRACT_CORRECTED"
STARTED_SEQUENCE = 93
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-003"
)

R009_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq91-92/review-rounds/R009"
)
R009_ASSIGNMENT_REL = R009_REVIEW_ROOT / "review-assignment.json"
R009_RESULT_REL = R009_REVIEW_ROOT / "review-result.json"
R009_INDEPENDENT_REL = R009_REVIEW_ROOT / "independent-review.json"
R009_BINDINGS = {
    "assignment": {
        "path": R009_ASSIGNMENT_REL.as_posix(),
        "sha256": "e7aa0379db6d013bc754c350617f08d14a2c4eeea7700cd477c78636d58eedca",
        "byte_length": 12_974,
    },
    "review_result": {
        "path": R009_RESULT_REL.as_posix(),
        "sha256": "c7c59129b3ba8a9944cbe7cdb06264f9f3cc229a3502c0f34d87334f54c077de",
        "byte_length": 2_840,
    },
    "independent_review": {
        "path": R009_INDEPENDENT_REL.as_posix(),
        "sha256": "56a0907f30bb9156d1d732627a0f3def3d5421520c12226d897ad420836dc781",
        "byte_length": 4_681,
    },
}

R003_CONTRACT_REL = correction.R003_CONTRACT_REL
R003_CONTRACT_BINDING = {
    "schema_version": "1.2",
    "document_id": "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-003",
    "path": R003_CONTRACT_REL.as_posix(),
    "file_sha256": "bce459dc4454b11638bc7d09320ba90671aea02c711a7b502db13e52d7b45e75",
    "contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R003",
    "contract_version": "2026-08-26.2",
    "canonical_contract_sha256": "e782541d53e7350da55eaef9d608d5c1a878db86bde0e7f28807527974607d9f",
}
R004_CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r004.json"
)
R004_RUNNER_REL = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r004_20260826.py"
)
R004_RUNNER_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r004_20260826.py"
)
SEQ93_STARTER_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq93_20260826.py"
)
SEQ93_STARTER_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq93_20260826.py"
)
R004_CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R004"
R004_CONTRACT_VERSION = "2026-08-26.3"
R004_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-004"
R004_FILE_SHA256 = (
    "f0b4dfaa18bed37a4b33b6b3520bb19261268cdc57244de23bf0815a10062485"
)
R004_CANONICAL_SHA256 = (
    "bf15183080b2ba0237109792a8c44ff2a7811c965f6155343f55523c543467b0"
)
R004_BYTE_LENGTH = 3_494
R004_SUCCESSOR_REASON_CODE = (
    "SEQ92_STAGE_AWARE_ROOT_REGRESSION_AND_CONTRACT_CORRECTION_REQUIRED"
)

TRANSITION_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq92-93"
)
AUTHORIZATION_REL = TRANSITION_ROOT / "authorization.json"
R001_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R001"
R001_REVIEW_ASSIGNMENT_REL = R001_REVIEW_ROOT / "review-assignment.json"
R001_REVIEW_RESULT_REL = R001_REVIEW_ROOT / "review-result.json"
R001_INDEPENDENT_REVIEW_REL = R001_REVIEW_ROOT / "independent-review.json"
R001_REVIEW_ASSIGNMENT_SHA256 = (
    "7a87a22cfe15b0c8ed17045a19bf1f7bdf94fef013c8ff7e8d5f9e5775f2120a"
)
R001_REVIEW_ASSIGNMENT_BYTE_LENGTH = 9_876
R001_STALE_REVIEW_REASON_CODE = "P1_PROJECTED_GOAL_GRAPH_VALIDATION_FAIL"
R002_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R002"
R002_REVIEW_ASSIGNMENT_REL = R002_REVIEW_ROOT / "review-assignment.json"
R002_REVIEW_RESULT_REL = R002_REVIEW_ROOT / "review-result.json"
R002_INDEPENDENT_REVIEW_REL = R002_REVIEW_ROOT / "independent-review.json"
R002_REVIEW_ASSIGNMENT_SHA256 = (
    "bd1cacbae67ee32052b8da99d7b50a16b18690bcdbf5ca30de7e1242f4edb1b0"
)
R002_REVIEW_ASSIGNMENT_BYTE_LENGTH = 10_447
R002_STALE_REVIEW_REASON_CODE = "P0_R004_GATE_ADAPTER_SYNC_RECURSION"
R003_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R003"
R003_REVIEW_ASSIGNMENT_REL = R003_REVIEW_ROOT / "review-assignment.json"
R003_REVIEW_RESULT_REL = R003_REVIEW_ROOT / "review-result.json"
R003_INDEPENDENT_REVIEW_REL = R003_REVIEW_ROOT / "independent-review.json"
R003_REVIEW_ASSIGNMENT_SHA256 = (
    "abe1a7777eede56c533ad470566736f0f8e2a5111ac4a4c345313cef26bfcf44"
)
R003_REVIEW_ASSIGNMENT_BYTE_LENGTH = 10_686
R003_STALE_REVIEW_REASON_CODE = "P1_R004_POSTPUBLICATION_ROOT_REGRESSION_FIXTURE_ORDER"
ROUND_ID = "R004"
REVIEW_ROOT = TRANSITION_ROOT / f"review-rounds/{ROUND_ID}"
REVIEW_ASSIGNMENT_REL = REVIEW_ROOT / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_ROOT / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_ROOT / "independent-review.json"
REVIEW_PATHS = (
    REVIEW_ASSIGNMENT_REL,
    REVIEW_RESULT_REL,
    INDEPENDENT_REVIEW_REL,
)

SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_start_gate_contract_correction_seq92_20260826.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_seq92_20260826.py"
)
REVIEWED_CONTROL_PATHS = (
    SCRIPT_REL,
    TEST_REL,
    correction.SCRIPT_REL,
    correction.TEST_REL,
    seq90.SCRIPT_REL,
    seq90.TEST_REL,
    R003_CONTRACT_REL,
    R004_CONTRACT_REL,
    R009_ASSIGNMENT_REL,
    R009_RESULT_REL,
    R009_INDEPENDENT_REL,
    AUTHORIZATION_REL,
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    R004_RUNNER_REL,
    R004_RUNNER_TEST_REL,
    SEQ93_STARTER_REL,
    SEQ93_STARTER_TEST_REL,
)

CORRECTION_REASON = {
    "failed_check_id": "ROOT_FP048_R002_CONTROL_REGRESSION",
    "failed_contract_id": R003_CONTRACT_BINDING["contract_id"],
    "failed_contract_version": R003_CONTRACT_BINDING["contract_version"],
    "observed_failed_test_count": 102,
    "reason_code": "R003_PREPUBLICATION_ROOT_REGRESSION_NOT_POSTPUBLICATION_SAFE",
    "remediation": "SUPERSEDE_R003_WITH_STAGE_AWARE_R004_BEFORE_SEQ93_START",
}
CLAIM_BOUNDARY = copy.deepcopy(correction.CLAIM_BOUNDARY)
PROJECTED_TRANSITION = {
    "contract_correction_event_id": CORRECTION_EVENT_ID,
    "contract_correction_sequence": CORRECTION_SEQUENCE,
    "contract_correction_transition": "READY_TO_READY",
    "started_event_id": STARTED_EVENT_ID,
    "started_sequence": STARTED_SEQUENCE,
    "started_transition": "READY_TO_IN_PROGRESS_AFTER_FRESH_R004_PASS",
}
REVIEWER = {
    "id": "codex-fp048-r002-seq92-contract-independent-reviewer-20260826",
    "task_id": "/root/fp048_seq90_reanchor_impl/seq92_contract_correction_independent_review_r004",
}
INDEPENDENT_CHECKS = [
    "EXACT_PUBLISHED_SEQ91_SOURCE_CAS_PASS",
    "R009_SOURCE_REVIEW_TRIAD_EXACT_PASS",
    "R003_TO_R004_CONTRACT_SUPERSESSION_PASS",
    "SEQ92_READY_TO_READY_ZERO_CREDIT_PASS",
    "SEQ92_TO_SEQ91_EXACT_BYTE_RECONSTRUCTION_PASS",
    "FINAL_MANAGED_AND_GIT_VISIBLE_COHORT_RESEAL_PASS",
    "ATOMIC_COMMIT_GUARD_AND_POSTCOMMIT_UNCERTAIN_PASS",
    "PRIVATE_0600_CHECKPOINT_MODE_PRECOMMIT_AND_POSTCOMMIT_PASS",
    "PROJECTED_CONTINUATION_AND_GOAL_GRAPH_PASS",
    "POSTPUBLICATION_SEQ92_ROOT_REGRESSION_ALL_PASS",
]
REVIEW_RESULT_FIELDS = frozenset(
    {
        "assignment_binding",
        "claim_boundary",
        "correction_reason",
        "decision",
        "document_id",
        "external_independence_claimed",
        "findings",
        "goal_id",
        "previous_contract_binding",
        "projected_transition",
        "replacement_contract_binding",
        "reviewed_at",
        "reviewer",
        "round_id",
        "schema_version",
    }
)
INDEPENDENT_REVIEW_FIELDS = REVIEW_RESULT_FIELDS | {
    "independent_checks",
    "review_result_binding",
}
EVENT_FIELDS = correction.EVENT_FIELDS
SCOPE = (
    "Graph v2.4 FP-048 R002 remains READY through zero-credit seq92 R003-to-R004 "
    "gate-contract correction; fresh R004 gate and seq93 start remain NOT_RUN."
)
CURRENT_FOCUS = (
    "FP-048 R002/GAP-057 READY; R004 gate contract armed; seq93 start NOT_RUN"
)
NEXT_ACTION = (
    "R004 event-scoped five-check gate를 실행하고 PASS receipt로 seq93 start를 검증한다."
)
HANDOFF_EPIC = "EPIC-03 / FP-048 R002/GAP-057 READY_R004_GATE_NOT_RUN"
VERIFICATION_STATUS = (
    "FP048_R002_SEQ92_ZERO_CREDIT_R004_CONTRACT_CORRECTED_GATE_NOT_RUN"
)
CORRECTION_CURRENT_FOCUS = CURRENT_FOCUS
CORRECTION_SCOPE = SCOPE
CORRECTION_HANDOFF_EPIC = HANDOFF_EPIC
CORRECTION_VERIFICATION_STATUS = VERIFICATION_STATUS
CORRECTION_NEXT_ACTION = NEXT_ACTION


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _strict_json_equal(left: Any, right: Any) -> bool:
    return seq90.canonical_json_bytes({"value": left}) == seq90.canonical_json_bytes(
        {"value": right}
    )


def _binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
    }


def _source_checkpoint_binding() -> dict[str, Any]:
    return {
        "path": CHECKPOINT_REL.as_posix(),
        "sha256": SOURCE_CHECKPOINT_SHA256,
        "byte_length": SOURCE_CHECKPOINT_BYTE_LENGTH,
        "sequence": SOURCE_SEQUENCE,
        "tail_event_id": SOURCE_EVENT_ID,
        "tail_event_sha256": SOURCE_EVENT_SHA256,
    }


def _parse_time(value: str, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except (TypeError, ValueError) as exc:
        raise ContractCorrectionError(f"{label} differs") from exc
    require(parsed.tzinfo is not None and parsed.microsecond == 0, f"{label} differs")
    return parsed


def _event_time(
    source: Mapping[str, Any],
    supplied: str | None,
    independent_reviewed_at: datetime,
) -> str:
    candidate = (
        _parse_time(supplied, "seq92 occurred_at")
        if supplied is not None
        else datetime.now().astimezone().replace(microsecond=0)
    )
    source_time = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq91 occurred_at",
    )
    return max(
        candidate,
        source_time + timedelta(seconds=1),
        independent_reviewed_at + timedelta(seconds=1),
    ).isoformat()


def _r009_review_binding(root: Path) -> dict[str, dict[str, Any]]:
    observed: dict[str, dict[str, Any]] = {}
    for role, relative in (
        ("assignment", R009_ASSIGNMENT_REL),
        ("review_result", R009_RESULT_REL),
        ("independent_review", R009_INDEPENDENT_REL),
    ):
        raw = seq90._stable_read(root, relative).raw
        expected = R009_BINDINGS[role]
        require(_binding(relative, raw) == expected, f"R009 {role} binding differs")
        document = seq90.strict_json(raw, relative.as_posix())
        require(raw == seq90.canonical_json_bytes(document), f"R009 {role} is noncanonical")
        observed[role] = expected
    return copy.deepcopy(observed)


def r003_contract_binding(root: Path = ROOT) -> dict[str, Any]:
    binding = correction.r003_contract_binding(root)
    require(binding == R003_CONTRACT_BINDING, "R003 contract binding differs")
    return copy.deepcopy(binding)


def load_r004_contract(
    root: Path = ROOT,
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R004_CONTRACT_REL).raw
    require(
        len(raw) == R004_BYTE_LENGTH and sha256_bytes(raw) == R004_FILE_SHA256,
        "R004 contract bytes differ",
    )
    document = seq90.strict_json(raw, R004_CONTRACT_REL.as_posix())
    require(
        raw == seq90.canonical_json_bytes(document)
        and continuation.canonical_json_sha256(document) == R004_CANONICAL_SHA256,
        "R004 contract canonical authority differs",
    )
    checks = document.get("ordered_checks")
    supersedes = document.get("supersedes")
    require(
        document.get("schema_version") == "1.2"
        and document.get("document_id") == R004_DOCUMENT_ID
        and document.get("contract_id") == R004_CONTRACT_ID
        and document.get("contract_version") == R004_CONTRACT_VERSION
        and document.get("gate_purpose") == "INITIAL_START"
        and document.get("target_goal_id") == GOAL_ID
        and document.get("target_goal_content_sha256") == GOAL_SHA256
        and document.get("successor_reason_code") == R004_SUCCESSOR_REASON_CODE
        and isinstance(checks, list)
        and [row.get("check_id") for row in checks]
        == list(correction.R003_CONTRACT_CHECK_IDS)
        and all(
            isinstance(row, dict)
            and set(row) == {"check_id", "command"}
            and isinstance(row["command"], str)
            and row["command"]
            for row in checks
        )
        and isinstance(supersedes, dict)
        and supersedes.get("path") == R003_CONTRACT_REL.as_posix()
        and supersedes.get("file_sha256") == R003_CONTRACT_BINDING["file_sha256"]
        and supersedes.get("canonical_sha256")
        == R003_CONTRACT_BINDING["canonical_contract_sha256"]
        and supersedes.get("contract_id") == R003_CONTRACT_BINDING["contract_id"]
        and supersedes.get("contract_version")
        == R003_CONTRACT_BINDING["contract_version"]
        and supersedes.get("source_correction_event_id") == CORRECTION_EVENT_ID
        and supersedes.get("source_correction_event_sequence") == CORRECTION_SEQUENCE,
        "R004 contract semantics differ",
    )
    claim = document.get("claim_boundary")
    require(
        isinstance(claim, dict)
        and claim.get("goal_started") is False
        and claim.get("start_gate_status") == "NOT_RUN"
        and claim.get("release_status") == "NOT_ELIGIBLE"
        and all(
            type(value) is int and value == 0
            for key, value in claim.items()
            if key.endswith("_credit_delta")
        ),
        "R004 contract claim boundary differs",
    )
    binding = {
        "schema_version": "1.2",
        "document_id": R004_DOCUMENT_ID,
        "path": R004_CONTRACT_REL.as_posix(),
        "file_sha256": R004_FILE_SHA256,
        "contract_id": R004_CONTRACT_ID,
        "contract_version": R004_CONTRACT_VERSION,
        "canonical_contract_sha256": R004_CANONICAL_SHA256,
    }
    return document, binding


def r004_contract_binding(root: Path = ROOT) -> dict[str, Any]:
    return load_r004_contract(root)[1]


def r004_runner_binding(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    return _binding(R004_RUNNER_REL, seq90._stable_read(root, R004_RUNNER_REL).raw)


def require_exact_seq91_source(
    raw: bytes,
    source: Mapping[str, Any],
    root: Path,
) -> None:
    require(len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH, "seq91 source length differs")
    require(sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256, "seq91 source CAS differs")
    require(raw == checkpoint_json_bytes(source), "seq91 source is noncanonical")
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list)
        and len(history) == SOURCE_SEQUENCE
        and history[-1].get("sequence") == SOURCE_SEQUENCE
        and history[-1].get("event_id") == SOURCE_EVENT_ID
        and history[-1].get("event_sha256") == SOURCE_EVENT_SHA256,
        "source is not exact seq91",
    )
    require(
        history[-1].get("transition_control_review_binding")
        == _r009_review_binding(root),
        "seq91 R009 review binding differs",
    )
    require(
        history[-1].get("contract_supersession", {}).get("replacement_contract_binding")
        == r003_contract_binding(root),
        "seq91 R003 contract binding differs",
    )
    require(
        state.get("goal_status") == "READY"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and source.get("current_work", {}).get("status") == "READY"
        and source.get("approved_state", {}).get("formal_test_not_run_count") == 279
        and source.get("verification_boundary", {}).get("release_eligible") is False,
        "seq91 source gained credit",
    )


def load_exact_seq91_source(
    root: Path = ROOT,
) -> tuple[bytes, dict[str, Any]]:
    root = seq90._safe_root(root)
    read = seq90._stable_read(root, CHECKPOINT_REL)
    source = seq90.strict_json(read.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq91_source(read.raw, source, root)
    return read.raw, source


def build_authorization(
    root: Path,
    source: Mapping[str, Any],
) -> bytes:
    source_raw = checkpoint_json_bytes(source)
    require(
        len(source_raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(source_raw) == SOURCE_CHECKPOINT_SHA256,
        "authorization source is not exact seq91",
    )
    value = {
        "authorization_scope": {
            "authorized_actions": [
                "SEQ92_ZERO_CREDIT_R003_TO_R004_CONTRACT_CORRECTION",
                "R004_PRIVATE_INITIAL_START_GATE_AFTER_SEQ92",
                "SEQ93_GOAL_STARTED_AFTER_FRESH_R004_FIVE_CHECK_PASS",
            ],
            "conditions": [
                "EXACT_PUBLISHED_SEQ91_SOURCE_CAS_REQUIRED",
                "SEQ92_MUST_PRESERVE_READY_STATUS_AND_ZERO_CREDIT",
                "R009_SOURCE_REVIEW_TRIAD_MUST_REMAIN_EXACT",
                "FINAL_MANAGED_AND_GIT_VISIBLE_COHORT_MUST_BE_RESEALED",
                "SEQ93_REQUIRES_A_FRESH_R004_FIVE_CHECK_PASS_RECEIPT",
            ],
            "excluded_actions": [
                "FORMAL_OR_ACTUAL_DEVICE_TEST_CREDIT",
                "EXTERNAL_REVIEW_OR_DEPLOYMENT_CREDIT",
                "RELEASE_OR_COMPLETION_CREDIT",
            ],
        },
        "authorization_status": "AUTHORIZED_FOR_CONDITIONAL_SEQ92_93_CONTRACT_CORRECTION_CONTINUATION",
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "contract_correction_event_id": CORRECTION_EVENT_ID,
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "document_id": "WS-FP048-R002-SEQ92-93-AUTHORIZATION-20260826-001",
        "evidence_type": "USER_CONTINUATION_AUTHORIZATION",
        "goal_id": GOAL_ID,
        "previous_contract_binding": r003_contract_binding(root),
        "replacement_contract_binding": r004_contract_binding(root),
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "source_transition_control_review_binding": _r009_review_binding(root),
        "target_started_event_id": STARTED_EVENT_ID,
    }
    return seq90.canonical_json_bytes(value)


def authorization_binding(
    root: Path,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    raw = seq90._stable_read(root, AUTHORIZATION_REL).raw
    require(raw == build_authorization(root, source), "seq92 authorization differs")
    return _binding(AUTHORIZATION_REL, raw)


def _r001_stale_assignment_binding(root: Path) -> dict[str, Any]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R001_REVIEW_ASSIGNMENT_REL).raw
    require(
        len(raw) == R001_REVIEW_ASSIGNMENT_BYTE_LENGTH
        and sha256_bytes(raw) == R001_REVIEW_ASSIGNMENT_SHA256,
        "stale R001 review assignment bytes differ",
    )
    document = seq90.strict_json(raw, R001_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ92-93-REVIEW-ASSIGNMENT-20260826-R001"
        and document.get("round_id") == "R001"
        and document.get("goal_id") == GOAL_ID,
        "stale R001 review assignment authority differs",
    )
    require(
        not os.path.lexists(root / R001_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R001_INDEPENDENT_REVIEW_REL),
        "stale R001 reviewer results must remain absent",
    )
    return {
        "byte_length": R001_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": R001_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": R001_STALE_REVIEW_REASON_CODE,
        "round_id": "R001",
        "sha256": R001_REVIEW_ASSIGNMENT_SHA256,
    }


def _r002_stale_assignment_binding(root: Path) -> dict[str, Any]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R002_REVIEW_ASSIGNMENT_REL).raw
    require(
        len(raw) == R002_REVIEW_ASSIGNMENT_BYTE_LENGTH
        and sha256_bytes(raw) == R002_REVIEW_ASSIGNMENT_SHA256,
        "stale R002 review assignment bytes differ",
    )
    document = seq90.strict_json(raw, R002_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ92-93-REVIEW-ASSIGNMENT-20260826-R002"
        and document.get("round_id") == "R002"
        and document.get("goal_id") == GOAL_ID
        and document.get("supersedes")
        == _r001_stale_assignment_binding(root),
        "stale R002 review assignment authority differs",
    )
    require(
        not os.path.lexists(root / R002_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R002_INDEPENDENT_REVIEW_REL),
        "stale R002 reviewer results must remain absent",
    )
    return {
        "byte_length": R002_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": R002_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": R002_STALE_REVIEW_REASON_CODE,
        "round_id": "R002",
        "sha256": R002_REVIEW_ASSIGNMENT_SHA256,
    }


def _r003_stale_assignment_binding(root: Path) -> dict[str, Any]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R003_REVIEW_ASSIGNMENT_REL).raw
    require(
        len(raw) == R003_REVIEW_ASSIGNMENT_BYTE_LENGTH
        and sha256_bytes(raw) == R003_REVIEW_ASSIGNMENT_SHA256,
        "stale R003 review assignment bytes differ",
    )
    document = seq90.strict_json(raw, R003_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ92-93-REVIEW-ASSIGNMENT-20260826-R003"
        and document.get("round_id") == "R003"
        and document.get("goal_id") == GOAL_ID
        and document.get("supersedes") == _r002_stale_assignment_binding(root),
        "stale R003 review assignment authority differs",
    )
    require(
        not os.path.lexists(root / R003_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R003_INDEPENDENT_REVIEW_REL),
        "stale R003 reviewer results must remain absent",
    )
    return {
        "byte_length": R003_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": R003_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": R003_STALE_REVIEW_REASON_CODE,
        "round_id": "R003",
        "sha256": R003_REVIEW_ASSIGNMENT_SHA256,
    }


def build_review_assignment(
    root: Path,
    source: Mapping[str, Any],
) -> bytes:
    reviewed = [
        _binding(path, seq90._stable_read(root, path).raw)
        for path in REVIEWED_CONTROL_PATHS
    ]
    value = {
        "assigner": {"id": "codex-root", "task_id": "/root"},
        "authorization_binding": authorization_binding(root, source),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "document_id": "WS-FP048-R002-SEQ92-93-REVIEW-ASSIGNMENT-20260826-R004",
        "executor": {
            "id": "codex-fp048-r002-seq92-contract-correction-executor-20260826",
            "task_id": "/root/fp048_seq90_reanchor_impl",
        },
        "goal_id": GOAL_ID,
        "previous_contract_binding": r003_contract_binding(root),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "replacement_contract_binding": r004_contract_binding(root),
        "required_reviewer": copy.deepcopy(REVIEWER),
        "review_scope": [
            "exact published seq91 source CAS and R009 source review binding",
            "exact stale R001 assignment binding and absence of R001 reviewer-authored approvals",
            "exact stale R002 assignment binding and absence of R002 reviewer-authored approvals",
            "exact stale R003 assignment binding and absence of R003 reviewer-authored approvals",
            "R001 projected Goal Graph failure is closed by the full checker without narrowing the contracted check",
            "actual R004 gate adapter and CLI complete without sync recursion",
            "fresh R004 five-check gate completes before any conditional seq93 start",
            "exact postpublication seq92 ROOT regression completes with all tests passing",
            "R003 prepublication-only regression failure classification",
            "R003-to-R004 append-only contract supersession",
            "seq92 READY-to-READY transition with every credit delta zero",
            "exact seq92-to-seq91 byte reconstruction",
            "preconsumer, postconsumer, and commit-point managed and Git-visible cohort reseal",
            "atomic replacement and postcommit-uncertain classification",
            "private 0600 checkpoint mode before and after publication",
            "conditional seq93 start only after a fresh R004 five-check PASS receipt",
        ],
        "reviewed_control_inputs": reviewed,
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "source_transition_control_review_binding": _r009_review_binding(root),
        "supersedes": _r003_stale_assignment_binding(root),
    }
    return seq90.canonical_json_bytes(value)


def _load_physical_review(
    root: Path,
    source: Mapping[str, Any],
) -> tuple[dict[str, dict[str, Any]], datetime]:
    assignment_raw = seq90._stable_read(root, REVIEW_ASSIGNMENT_REL).raw
    result_raw = seq90._stable_read(root, REVIEW_RESULT_REL).raw
    independent_raw = seq90._stable_read(root, INDEPENDENT_REVIEW_REL).raw
    require(
        assignment_raw == build_review_assignment(root, source),
        "seq92 review assignment differs",
    )
    assignment = seq90.strict_json(assignment_raw, REVIEW_ASSIGNMENT_REL.as_posix())
    result = seq90.strict_json(result_raw, REVIEW_RESULT_REL.as_posix())
    independent = seq90.strict_json(independent_raw, INDEPENDENT_REVIEW_REL.as_posix())
    require(
        set(result) == REVIEW_RESULT_FIELDS
        and set(independent) == INDEPENDENT_REVIEW_FIELDS
        and result_raw == seq90.canonical_json_bytes(result)
        and independent_raw == seq90.canonical_json_bytes(independent),
        "seq92 review documents are noncanonical",
    )
    assignment_binding_value = _binding(REVIEW_ASSIGNMENT_REL, assignment_raw)
    result_binding_value = _binding(REVIEW_RESULT_REL, result_raw)
    common = {
        "claim_boundary": CLAIM_BOUNDARY,
        "correction_reason": CORRECTION_REASON,
        "goal_id": GOAL_ID,
        "previous_contract_binding": r003_contract_binding(root),
        "projected_transition": PROJECTED_TRANSITION,
        "replacement_contract_binding": r004_contract_binding(root),
        "reviewer": REVIEWER,
        "round_id": ROUND_ID,
        "schema_version": "1.0",
    }
    require(
        assignment.get("document_id")
        == "WS-FP048-R002-SEQ92-93-REVIEW-ASSIGNMENT-20260826-R004"
        and assignment.get("round_id") == ROUND_ID
        and assignment.get("required_reviewer") == REVIEWER
        and assignment.get("supersedes")
        == _r003_stale_assignment_binding(root)
        and result.get("document_id")
        == "WS-FP048-R002-SEQ92-93-REVIEW-RESULT-20260826-R004"
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ92-93-INDEPENDENT-REVIEW-20260826-R004"
        and _strict_json_equal(
            result.get("assignment_binding"), assignment_binding_value
        )
        and _strict_json_equal(
            independent.get("assignment_binding"), assignment_binding_value
        )
        and _strict_json_equal(
            independent.get("review_result_binding"), result_binding_value
        )
        and result.get("decision") == "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and independent.get("decision") == "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and result.get("findings") == []
        and independent.get("findings") == []
        and result.get("external_independence_claimed") is False
        and independent.get("external_independence_claimed") is False
        and _strict_json_equal(
            independent.get("independent_checks"), INDEPENDENT_CHECKS
        )
        and all(
            _strict_json_equal(result.get(key), value)
            for key, value in common.items()
        )
        and all(
            _strict_json_equal(independent.get(key), value)
            for key, value in common.items()
        ),
        "seq92 review approval differs",
    )
    result_at = _parse_time(result.get("reviewed_at"), "seq92 result reviewed_at")
    independent_at = _parse_time(
        independent.get("reviewed_at"), "seq92 independent reviewed_at"
    )
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq91 occurred_at",
    )
    require(
        result_at > source_at and independent_at > result_at,
        "seq92 review time order differs",
    )
    return (
        {
            "assignment": assignment_binding_value,
            "review_result": result_binding_value,
            "independent_review": _binding(INDEPENDENT_REVIEW_REL, independent_raw),
        },
        independent_at,
    )


def _final_managed_paths(
    source: Mapping[str, Any], visible: Sequence[str]
) -> tuple[str, ...]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    paths.update(
        path
        for path in visible
        if not path.startswith("docs/control/execution/goal-gates/")
    )
    paths.discard(CHECKPOINT_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEWED_CONTROL_PATHS)
    paths.update(path.as_posix() for path in REVIEW_PATHS)
    return tuple(sorted(paths))


def project_seq92(
    root: Path,
    source: Mapping[str, Any],
    *,
    managed_paths: Sequence[str],
    path_set_sha256: str,
    content_set_sha256: str,
    occurred_at: str,
    authorization_binding_value: Mapping[str, Any],
    review_binding: Mapping[str, Any],
    replacement_contract_binding: Mapping[str, Any],
    replacement_runner_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    del root
    source_event = source["goal_execution"]["transition_history"][-1]
    require(
        all(
            type(path) is str
            and bool(path)
            and not Path(path).is_absolute()
            and ".." not in Path(path).parts
            for path in managed_paths
        ),
        "seq92 managed path schema differs",
    )
    paths = sorted(set(managed_paths))
    canonical_path_sha256 = hashlib.sha256(
        ("\n".join(paths) + "\n").encode("utf-8")
    ).hexdigest()
    require(
        paths == list(managed_paths)
        and type(path_set_sha256) is str
        and seq90.SHA256_RE.fullmatch(path_set_sha256) is not None
        and path_set_sha256 == canonical_path_sha256
        and type(content_set_sha256) is str
        and seq90.SHA256_RE.fullmatch(content_set_sha256) is not None,
        "seq92 managed paths differ",
    )
    projected = copy.deepcopy(source)
    current = projected["current_work"]
    current["status"] = "READY"
    current["current_focus"] = CURRENT_FOCUS
    current["next_action"] = NEXT_ACTION
    current["release_completion_claimed"] = False
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
    mirror["file_count"] = len(paths)
    mirror["path_set_sha256"] = path_set_sha256
    mirror["content_set_sha256"] = content_set_sha256
    handoff["current_epic"] = HANDOFF_EPIC
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = VERIFICATION_STATUS
    handoff["next_single_action"] = NEXT_ACTION
    event: dict[str, Any] = {
        "sequence": CORRECTION_SEQUENCE,
        "event_id": CORRECTION_EVENT_ID,
        "event_type": CORRECTION_EVENT_TYPE,
        "occurred_on": _parse_time(occurred_at, "seq92 occurred_at").date().isoformat(),
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
            "FP048-R002_SEQ91_R009_REVIEWED_SOURCE",
            "FP048-R002_R003_PREPUBLICATION_ROOT_REGRESSION_FAILURE",
            "FP048-R002_R004_STAGE_AWARE_GATE_CONTRACT",
            "FP048-R002_SEQ92_93_TRANSITION_CONTROL_REVIEW",
        ],
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "source_ready_event_binding": copy.deepcopy(
            source_event["source_ready_event_binding"]
        ),
        "authorization_binding": copy.deepcopy(dict(authorization_binding_value)),
        "contract_supersession": {
            "previous_contract_binding": copy.deepcopy(
                source_event["contract_supersession"]["replacement_contract_binding"]
            ),
            "reason_code": CORRECTION_REASON["reason_code"],
            "replacement_contract_binding": copy.deepcopy(
                dict(replacement_contract_binding)
            ),
        },
        "start_gate_runner_binding": copy.deepcopy(
            dict(replacement_runner_binding)
        ),
        "transition_control_review_binding": copy.deepcopy(dict(review_binding)),
        "repository_context_reanchor": {
            "before": {
                **_source_checkpoint_binding(),
                "current_work": copy.deepcopy(source["current_work"]),
                "working_tree_snapshot": copy.deepcopy(
                    source["working_tree_snapshot"]
                ),
                "session_handoff": copy.deepcopy(source["session_handoff"]),
            },
            "after": {
                "base_commit": source["working_tree_snapshot"]["base_head"],
                "branch": source["session_handoff"]["branch"],
                "current_head": source["session_handoff"]["source_commit_or_snapshot"]["current_head"],
                "managed_changed_path_count": len(paths),
                "path_set_sha256": path_set_sha256,
                "content_set_sha256": content_set_sha256,
            },
        },
        "noncredit_successor_edges": copy.deepcopy(
            source_event["noncredit_successor_edges"]
        ),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "unchanged_control_projection": correction._unchanged_projection(projected),
        "canonical_binding_snapshot_after": copy.deepcopy(
            source_event["canonical_binding_snapshot_after"]
        ),
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "previous_event_sha256": SOURCE_EVENT_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq92 correction event field set differs")
    state = projected["goal_execution"]
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    require(
        state["goal_status"] == "READY"
        and state["status_by_goal"][GOAL_ID] == "READY"
        and current["status"] == "READY",
        "seq92 READY state differs",
    )
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "verification_boundary",
    ):
        require(
            _strict_json_equal(projected[field], source[field]),
            f"seq92 changed {field}",
        )
    require(
        all(
            type(value) is int and value == 0
            for key, value in CLAIM_BOUNDARY.items()
            if key.endswith("_credit_delta") or key == "goal_status_change_count"
        )
        and CLAIM_BOUNDARY["implementation_start_authorized"] is False,
        "seq92 credit boundary differs",
    )
    return projected, event


def _repository_before(event: Mapping[str, Any]) -> dict[str, Any]:
    context = event.get("repository_context_reanchor")
    before = context.get("before") if isinstance(context, dict) else None
    after = context.get("after") if isinstance(context, dict) else None
    require(
        isinstance(context, dict)
        and set(context) == {"before", "after"}
        and isinstance(before, dict)
        and set(before)
        == set(_source_checkpoint_binding())
        | {"current_work", "working_tree_snapshot", "session_handoff"}
        and {
            key: before.get(key) for key in _source_checkpoint_binding()
        }
        == _source_checkpoint_binding()
        and isinstance(before.get("current_work"), dict)
        and isinstance(before.get("working_tree_snapshot"), dict)
        and isinstance(before.get("session_handoff"), dict)
        and isinstance(after, dict)
        and set(after)
        == {
            "base_commit",
            "branch",
            "current_head",
            "managed_changed_path_count",
            "path_set_sha256",
            "content_set_sha256",
        },
        "seq92 repository context differs",
    )
    return before


def _restored_seq91_checkpoint(
    checkpoint: Mapping[str, Any],
) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq91 inverse source is not exact seq92",
    )
    event = history[-1]
    before = _repository_before(event)
    restored = copy.deepcopy(checkpoint)
    restored_state = restored["goal_execution"]
    source_event = restored_state["transition_history"][SOURCE_SEQUENCE - 1]
    restored_state["transition_history"] = restored_state["transition_history"][:SOURCE_SEQUENCE]
    restored_state["transition_history_anchor_sha256"] = source_event["event_sha256"]
    restored_state["validation_cutoff_at"] = source_event["occurred_at"]
    restored["current_work"] = copy.deepcopy(before["current_work"])
    restored["working_tree_snapshot"] = copy.deepcopy(
        before["working_tree_snapshot"]
    )
    restored["session_handoff"] = copy.deepcopy(before["session_handoff"])
    return restored


def require_contract_corrected_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> None:
    root = seq90._safe_root(root)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "checkpoint is not exact seq92 contract correction",
    )
    source_event = history[SOURCE_SEQUENCE - 1]
    event = history[CORRECTION_SEQUENCE - 1]
    require(
        isinstance(event, dict)
        and set(event) == EVENT_FIELDS
        and source_event.get("event_id") == SOURCE_EVENT_ID
        and source_event.get("event_sha256") == SOURCE_EVENT_SHA256
        and event.get("sequence") == CORRECTION_SEQUENCE
        and event.get("event_id") == CORRECTION_EVENT_ID
        and event.get("event_type") == CORRECTION_EVENT_TYPE
        and event.get("subject_goal_id") == GOAL_ID
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("previous_event_sha256") == SOURCE_EVENT_SHA256
        and event.get("event_sha256") == continuation.event_sha256(event)
        and event.get("source_checkpoint_binding") == _source_checkpoint_binding()
        and event.get("correction_reason") == CORRECTION_REASON
        and event.get("claim_boundary") == CLAIM_BOUNDARY,
        "seq92 correction authority differs",
    )
    before = _repository_before(event)
    source_raw = checkpoint_json_bytes(_restored_seq91_checkpoint(checkpoint))
    require(
        len(source_raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(source_raw) == SOURCE_CHECKPOINT_SHA256,
        "restored seq91 source CAS differs",
    )
    source = seq90.strict_json(source_raw, "restored seq91 source")
    review_binding, _reviewed_at = _load_physical_review(root, source)
    snapshot = checkpoint.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    require(isinstance(paths, list), "seq92 managed paths differ")
    expected_checkpoint, expected_event = project_seq92(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=snapshot.get("path_set_sha256"),
        content_set_sha256=snapshot.get("content_set_sha256"),
        occurred_at=event.get("occurred_at"),
        authorization_binding_value=authorization_binding(root, source),
        review_binding=review_binding,
        replacement_contract_binding=r004_contract_binding(root),
        replacement_runner_binding=r004_runner_binding(root),
    )
    require(
        _strict_json_equal(event, expected_event)
        and _strict_json_equal(checkpoint, expected_checkpoint),
        "seq92 exact producer projection differs",
    )
    require(
        event.get("authorization_binding") == authorization_binding(root, source)
        and event.get("transition_control_review_binding") == review_binding
        and event.get("contract_supersession")
        == {
            "previous_contract_binding": r003_contract_binding(root),
            "reason_code": CORRECTION_REASON["reason_code"],
            "replacement_contract_binding": r004_contract_binding(root),
        }
        and event.get("source_ready_event_binding")
        == source_event.get("source_ready_event_binding")
        and event.get("start_gate_runner_binding") == r004_runner_binding(root)
        and event.get("noncredit_successor_edges")
        == source_event.get("noncredit_successor_edges")
        and event.get("runtime_after") == source_event.get("runtime_after")
        and event.get("blockers_after") == source_event.get("blockers_after")
        and event.get("blocker_resolution_ids_after")
        == source_event.get("blocker_resolution_ids_after"),
        "seq92 successor authority differs",
    )
    require(
        all(
            isinstance(item, dict)
            and item.get("sequence") == sequence
            and item.get("event_sha256") == continuation.event_sha256(item)
            and (
                sequence == 1
                or item.get("previous_event_sha256")
                == history[sequence - 2].get("event_sha256")
            )
            for sequence, item in enumerate(history, start=1)
        ),
        "seq1-92 lineage differs",
    )
    require(
        event.get("unchanged_control_projection")
        == correction._unchanged_projection(checkpoint),
        "seq92 unchanged control projection differs",
    )
    current = checkpoint.get("current_work")
    handoff = checkpoint.get("session_handoff")
    after = event["repository_context_reanchor"]["after"]
    mirror = handoff.get("source_commit_or_snapshot") if isinstance(handoff, dict) else None
    require(
        state.get("goal_status") == "READY"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and state.get("transition_history_anchor_sha256") == event["event_sha256"]
        and state.get("validation_cutoff_at") == event["occurred_at"]
        and isinstance(current, dict)
        and current.get("status") == "READY"
        and current.get("current_focus") == CURRENT_FOCUS
        and current.get("next_action") == NEXT_ACTION
        and current.get("release_completion_claimed") is False
        and isinstance(snapshot, dict)
        and isinstance(paths, list)
        and paths == sorted(set(paths))
        and snapshot.get("scope") == SCOPE
        and snapshot.get("managed_changed_path_count") == len(paths)
        and snapshot.get("managed_changed_path_count")
        == after.get("managed_changed_path_count")
        and snapshot.get("path_set_sha256") == after.get("path_set_sha256")
        and snapshot.get("content_set_sha256") == after.get("content_set_sha256")
        and isinstance(handoff, dict)
        and handoff.get("changed_files") == paths
        and handoff.get("current_epic") == HANDOFF_EPIC
        and handoff.get("last_updated_by_work_item") == WORK_ITEM_ID
        and handoff.get("last_verification_status") == VERIFICATION_STATUS
        and handoff.get("next_single_action") == NEXT_ACTION
        and isinstance(mirror, dict)
        and mirror.get("file_count") == len(paths)
        and mirror.get("path_set_sha256") == snapshot.get("path_set_sha256")
        and mirror.get("content_set_sha256") == snapshot.get("content_set_sha256"),
        "seq92 READY projection differs",
    )
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "verification_boundary",
    ):
        require(
            _strict_json_equal(checkpoint.get(field), source.get(field)),
            f"seq92 changed {field}",
        )
    require(
        checkpoint.get("approved_state", {}).get("formal_test_not_run_count") == 279
        and checkpoint.get("verification_boundary", {}).get("release_eligible") is False
        and before["current_work"]["status"] == "READY",
        "seq92 gained formal, deployment, or release credit",
    )
    if require_live_snapshot:
        require(
            continuation.working_snapshot_hashes(root, paths)
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "seq92 managed snapshot differs",
        )


def reconstructed_seq91_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(isinstance(history, list), "checkpoint history differs")
    if len(history) == SOURCE_SEQUENCE:
        raw = checkpoint_json_bytes(checkpoint)
        require(
            len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
            and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256,
            "checkpoint is not exact seq91 source",
        )
        return raw
    require_contract_corrected_checkpoint(
        root, checkpoint, require_live_snapshot=False
    )
    raw = checkpoint_json_bytes(_restored_seq91_checkpoint(checkpoint))
    require(
        len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256,
        "seq92 inverse does not reconstruct exact seq91",
    )
    return raw


def canonical_seq92_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    require_contract_corrected_checkpoint(
        root, checkpoint, require_live_snapshot=False
    )
    return checkpoint_json_bytes(checkpoint)


def reconstructed_seq92_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq93 inverse is owned by the seq93 starter",
    )
    return canonical_seq92_checkpoint_bytes(root, checkpoint)


@dataclass(frozen=True)
class Prepared:
    transport: seq90.Prepared

    @property
    def projected(self) -> Mapping[str, Any]:
        return self.transport.projected

    @property
    def event(self) -> Mapping[str, Any]:
        return self.transport.event


_AFTER_CAPTURE_HOOK: Callable[[Path], None] | None = None


def _require_private_checkpoint_mode(root: Path, phase: str) -> None:
    mode = stat.S_IMODE((root / CHECKPOINT_REL).lstat().st_mode)
    require(mode == 0o600, f"checkpoint mode differs {phase}")


def _require_prepared_exact(prepared: Prepared) -> None:
    transport = prepared.transport
    _require_private_checkpoint_mode(
        transport.root, "during seq92 preflight reseal"
    )
    seq90._require_preflight_cohort_unchanged(
        transport.root,
        source=seq90.ReadResult(transport.source_raw, transport.source_identity),
        retained=transport.retained_inputs,
        managed=transport.managed_inputs,
        git_visible=transport.git_visible_inputs,
        git_status_raw=transport.git_status_raw,
        git_head=transport.git_head,
        git_branch=transport.git_branch,
        phase="seq92 contract-correction commit guard",
    )


def _without_seq90_writer_temporary_status(
    observed: bytes,
    expected: bytes,
) -> bytes:
    if observed == expected:
        return observed
    prefix = (
        b"?? "
        + CHECKPOINT_REL.parent.as_posix().encode("utf-8")
        + b"/.walksafe-fp048-r002-seq90-write."
    )
    records = observed.split(b"\0")
    candidates: list[bytes] = []
    for index, record in enumerate(records):
        if record.startswith(prefix) and record.endswith(b".json"):
            reduced = records[:index] + records[index + 1 :]
            candidate = b"\0".join(reduced)
            if candidate == expected:
                candidates.append(candidate)
    require(
        len(candidates) == 1,
        "Git-visible cohort changed at seq92 commit guard",
    )
    return candidates[0]


def _require_commit_exact(prepared: Prepared) -> None:
    transport = prepared.transport
    root = transport.root
    _require_private_checkpoint_mode(root, "at seq92 commit guard")
    seq90._require_inputs_unchanged(root, transport.retained_inputs)
    seq90._require_managed_inputs_unchanged(root, transport.managed_inputs)
    seq90._require_managed_inputs_unchanged(
        root,
        transport.git_visible_inputs,
        label="full Git-visible input",
    )
    seq90._require_git_context(root, transport.git_head, transport.git_branch)
    source = seq90._stable_read(root, CHECKPOINT_REL)
    require(
        source.identity == transport.source_identity
        and source.raw == transport.source_raw,
        "source changed at seq92 commit guard",
    )
    status_raw, _visible = seq90.capture_git_visible_paths(root)
    require(
        _without_seq90_writer_temporary_status(
            status_raw, transport.git_status_raw
        )
        == transport.git_status_raw,
        "Git-visible cohort changed at seq92 commit guard",
    )


def prepare(
    root: Path = ROOT,
    *,
    occurred_at: str | None = None,
    validate_consumers: bool = True,
) -> Prepared:
    root = seq90._safe_root(root)
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    _require_private_checkpoint_mode(root, "before seq92 preflight")
    source = seq90.strict_json(source_read.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq91_source(source_read.raw, source, root)
    replacement = r004_contract_binding(root)
    git_head, git_branch = seq90._capture_git_context(root)
    expected_head = source["session_handoff"]["source_commit_or_snapshot"][
        "current_head"
    ]
    require(git_head == expected_head, "live Git HEAD differs from seq91 source")
    status_raw, visible = seq90.capture_git_visible_paths(root)
    paths = _final_managed_paths(source, visible)
    managed = seq90._capture_managed_inputs(root, paths)
    visible_inputs = seq90._capture_managed_inputs(root, visible)
    path_sha256, content_sha256 = seq90._managed_input_snapshot_hashes(managed)
    retained_paths = tuple(
        sorted(set(REVIEWED_CONTROL_PATHS) | set(REVIEW_PATHS))
    )
    retained = {
        path: seq90._stable_read(root, path) for path in retained_paths
    }
    authorization = authorization_binding(root, source)
    review, independent_at = _load_physical_review(root, source)
    projected, event = project_seq92(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at=_event_time(source, occurred_at, independent_at),
        authorization_binding_value=authorization,
        review_binding=review,
        replacement_contract_binding=replacement,
        replacement_runner_binding=r004_runner_binding(root),
    )
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
    if _AFTER_CAPTURE_HOOK is not None:
        _AFTER_CAPTURE_HOOK(root)
    _require_prepared_exact(prepared)
    if validate_consumers:
        seq90._validate_projected_with_consumers(root, projected)
        _require_prepared_exact(prepared)
    return prepared


def write_checkpoint(prepared: Prepared) -> None:
    seq90.write_checkpoint(
        prepared.transport,
        commit_guard=lambda: _require_commit_exact(prepared),
    )
    try:
        _require_private_checkpoint_mode(
            prepared.transport.root, "after seq92 publication"
        )
    except BaseException as exc:
        raise seq90.PostcommitUncertain(
            "published seq92 checkpoint mode is uncertain"
        ) from exc


def prepare_review_manifest(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    _raw, source = load_exact_seq91_source(root)
    assignment = build_review_assignment(root, source)
    return {
        "authorization": authorization_binding(root, source),
        "candidate_review_assignment": _binding(REVIEW_ASSIGNMENT_REL, assignment),
        "reviewer_authored_paths": [
            REVIEW_RESULT_REL.as_posix(),
            INDEPENDENT_REVIEW_REL.as_posix(),
        ],
        "round_id": ROUND_ID,
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "status": "PREPARED_NOT_PUBLISHED",
    }


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare-authorization", action="store_true")
    mode.add_argument("--prepare-review", action="store_true")
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--occurred-at")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    published = False
    try:
        if args.prepare_authorization:
            _raw, source = load_exact_seq91_source(args.root)
            sys.stdout.buffer.write(build_authorization(args.root, source))
            return 0
        if args.prepare_review:
            sys.stdout.buffer.write(
                seq90.canonical_json_bytes(prepare_review_manifest(args.root))
            )
            return 0
        prepared = prepare(args.root, occurred_at=args.occurred_at)
        if args.write:
            write_checkpoint(prepared)
            published = True
        message = (
            "FP048-R002 seq92 gate-contract correction: PASS "
            f"event_sha256={prepared.event['event_sha256']} "
            f"occurred_at={prepared.event['occurred_at']}\n"
        )
        try:
            sys.stdout.write(message)
            sys.stdout.flush()
        except BaseException as exc:
            if published:
                raise seq90.PostcommitUncertain(
                    "published seq92 PASS output delivery is uncertain"
                ) from exc
            raise
    except seq90.PostcommitUncertain as exc:
        print(f"FP048-R002 seq92 gate-contract correction: POSTCOMMIT-UNCERTAIN: {exc}", file=sys.stderr)
        return 2
    except (
        ContractCorrectionError,
        seq90.ControlReanchorError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exc:
        print(f"FP048-R002 seq92 gate-contract correction: FAIL: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
