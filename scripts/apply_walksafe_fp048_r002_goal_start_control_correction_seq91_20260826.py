#!/usr/bin/env python3
"""Prepare the reviewed zero-credit FP048 R002 seq91 start-control correction."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (
    apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826 as seq90,
)
from scripts import check_walksafe_project_continuation_v2_4 as continuation


class ControlCorrectionError(RuntimeError):
    """The exact seq91 correction cannot be proven or published."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ControlCorrectionError(message)


CHECKPOINT_REL = seq90.CHECKPOINT_REL
GOAL_ID = seq90.GOAL_ID
GOAL_PATH = seq90.GOAL_PATH
GOAL_SHA256 = seq90.GOAL_SHA256
WORK_ITEM_ID = seq90.WORK_ITEM_ID
PARENT_GOAL_ID = seq90.PARENT_GOAL_ID
PREDECESSOR_GOAL_ID = seq90.PREDECESSOR_GOAL_ID
READY_EVENT_ID = seq90.READY_EVENT_ID
READY_FRONTIER = seq90.READY_FRONTIER
MANIFEST_SHA256 = seq90.MANIFEST_SHA256

SOURCE_SEQUENCE = 90
SOURCE_SEQ90_EVENT_ID = seq90.CONTROL_REANCHOR_EVENT_ID
SOURCE_SEQ90_EVENT_SHA256 = (
    "ba64b564f388e8598db038e8d3e20e6bf3fdada8f2ec0e5649b6b5db02fe625c"
)
SOURCE_CHECKPOINT_SHA256 = (
    "33c691c307555a0a594356d40707ccb9862105d56cab39e7a26b49f44da778ad"
)
SOURCE_CHECKPOINT_BYTE_LENGTH = 2_654_910

CORRECTION_SEQUENCE = 91
CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP048-R002-CORRECTION-20260826-001"
)
STARTED_SEQUENCE = 92
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-002"
)
COMPLETION_UPDATE_SEQUENCE = 93
COMPLETION_SEQUENCE = 94

AUTHORIZATION_REL = Path(
    "docs/control/execution/workstream-transitions/seq91-92/authorization.json"
)
R001_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq91-92/review-rounds/R001"
)
R001_REVIEW_ASSIGNMENT_REL = R001_REVIEW_ROOT / "review-assignment.json"
R001_REVIEW_RESULT_REL = R001_REVIEW_ROOT / "review-result.json"
R001_INDEPENDENT_REVIEW_REL = R001_REVIEW_ROOT / "independent-review.json"
R001_REVIEW_ASSIGNMENT_SHA256 = (
    "23167e1dbb6576d38399a503c84eb8d829f25bdd88403e5ae8d5547689fa5ca3"
)
R001_REVIEW_ASSIGNMENT_BYTE_LENGTH = 6_527
R002_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq91-92/review-rounds/R002"
)
R002_REVIEW_ASSIGNMENT_REL = R002_REVIEW_ROOT / "review-assignment.json"
R002_REVIEW_RESULT_REL = R002_REVIEW_ROOT / "review-result.json"
R002_INDEPENDENT_REVIEW_REL = R002_REVIEW_ROOT / "independent-review.json"
R002_REVIEW_ASSIGNMENT_SHA256 = (
    "9935000be9291c74c769dde79ed0139ac02d6d1e26c9e0a723f7de4c435414b6"
)
R002_REVIEW_ASSIGNMENT_BYTE_LENGTH = 7_245
R003_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq91-92/review-rounds/R003"
)
R003_REVIEW_ASSIGNMENT_REL = R003_REVIEW_ROOT / "review-assignment.json"
R003_REVIEW_RESULT_REL = R003_REVIEW_ROOT / "review-result.json"
R003_INDEPENDENT_REVIEW_REL = R003_REVIEW_ROOT / "independent-review.json"
R003_REVIEW_ASSIGNMENT_SHA256 = (
    "59f175e32d056a9666d59cd0052a010db395181158c2579bb3a9952953c762f0"
)
R003_REVIEW_ASSIGNMENT_BYTE_LENGTH = 7_242
R004_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq91-92/review-rounds/R004"
)
R004_REVIEW_ASSIGNMENT_REL = R004_REVIEW_ROOT / "review-assignment.json"
R004_REVIEW_RESULT_REL = R004_REVIEW_ROOT / "review-result.json"
R004_INDEPENDENT_REVIEW_REL = R004_REVIEW_ROOT / "independent-review.json"
R004_REVIEW_ASSIGNMENT_SHA256 = (
    "e6cd082a9c595ef78f0d915b9ed4cfad9c922b12e1ee9ef916ce41b56835ccd7"
)
R004_REVIEW_ASSIGNMENT_BYTE_LENGTH = 7_756
R005_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq91-92/review-rounds/R005"
)
R005_REVIEW_ASSIGNMENT_REL = R005_REVIEW_ROOT / "review-assignment.json"
R005_REVIEW_RESULT_REL = R005_REVIEW_ROOT / "review-result.json"
R005_INDEPENDENT_REVIEW_REL = R005_REVIEW_ROOT / "independent-review.json"
R005_REVIEW_ASSIGNMENT_SHA256 = (
    "829b5548359109d0ad53a113916885968d12dce3705f9f31b2f45f56eecbb34b"
)
R005_REVIEW_ASSIGNMENT_BYTE_LENGTH = 12_414
R006_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq91-92/review-rounds/R006"
)
R006_REVIEW_ASSIGNMENT_REL = R006_REVIEW_ROOT / "review-assignment.json"
R006_REVIEW_RESULT_REL = R006_REVIEW_ROOT / "review-result.json"
R006_INDEPENDENT_REVIEW_REL = R006_REVIEW_ROOT / "independent-review.json"
R006_REVIEW_ASSIGNMENT_SHA256 = (
    "b85fa4c1ff46efb05054547a5ef17a6360234878c27490fab3f767371e99d310"
)
R006_REVIEW_ASSIGNMENT_BYTE_LENGTH = 11_904
R007_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq91-92/review-rounds/R007"
)
R007_REVIEW_ASSIGNMENT_REL = R007_REVIEW_ROOT / "review-assignment.json"
R007_REVIEW_RESULT_REL = R007_REVIEW_ROOT / "review-result.json"
R007_INDEPENDENT_REVIEW_REL = R007_REVIEW_ROOT / "independent-review.json"
R007_REVIEW_ASSIGNMENT_SHA256 = (
    "bfa80bb51924a2f8630aa47e28dd6611c24d00b544d020186076d325ce4833b8"
)
R007_REVIEW_ASSIGNMENT_BYTE_LENGTH = 12_508
R008_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq91-92/review-rounds/R008"
)
R008_REVIEW_ASSIGNMENT_REL = R008_REVIEW_ROOT / "review-assignment.json"
R008_REVIEW_RESULT_REL = R008_REVIEW_ROOT / "review-result.json"
R008_INDEPENDENT_REVIEW_REL = R008_REVIEW_ROOT / "independent-review.json"
R008_REVIEW_ASSIGNMENT_SHA256 = (
    "9fd22031040d3649124d7e63c39fbf47aca6f9c09d65e08f5a54ebe7e3ac9a41"
)
R008_REVIEW_ASSIGNMENT_BYTE_LENGTH = 12_805
REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq91-92/review-rounds/R009"
)
REVIEW_ASSIGNMENT_REL = REVIEW_ROOT / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_ROOT / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_ROOT / "independent-review.json"
REVIEW_PATHS = (
    REVIEW_ASSIGNMENT_REL,
    REVIEW_RESULT_REL,
    INDEPENDENT_REVIEW_REL,
)
SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826.py"
)
R003_CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r003.json"
)
R003_GATE_REL = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r003_20260826.py"
)
R003_GATE_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r003_20260826.py"
)
STARTER_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq92_20260826.py"
)
STARTER_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq92_20260826.py"
)
R002_GATE_RUNTIME_REL = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r002_20260826.py"
)
R002_GATE_RUNTIME_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r002_20260826.py"
)
SEQ88_89_RUNTIME_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_seq88_89_20260825.py"
)
SEQ88_89_RUNTIME_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_seq88_89_20260825.py"
)
GATE_ADAPTER_RUNTIME_CLOSURE_PATHS = (
    R002_GATE_RUNTIME_REL,
    R002_GATE_RUNTIME_TEST_REL,
    SEQ88_89_RUNTIME_REL,
    SEQ88_89_RUNTIME_TEST_REL,
)
IMPORT_TRUST_RUNTIME_CLOSURE_PATHS = tuple(
    Path(value)
    for value in (
        "scripts/apply_walksafe_fp046_goal_completed_seq54_55_20260810.py",
        "scripts/build_walksafe_fp008_admin_review_delivery_trace_20260803.py",
        "scripts/build_walksafe_fp008_artifact_trace_successor_20260803.py",
        "scripts/build_walksafe_fp008_gap_backlog_r024_20260803.py",
        "scripts/build_walksafe_fp046_artifact_trace_successor_20260810.py",
        "scripts/build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810.py",
        "scripts/build_walksafe_fp046_gap_backlog_r025_20260810.py",
        "scripts/build_walksafe_fp046_strict_review_gate_20260810.py",
        "scripts/build_walksafe_phase1_exact257_successor_r014_20260810.py",
        "scripts/materialize_walksafe_fp048_goal_20260802.py",
    )
)

R003_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-003"
R003_CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R003"
R003_CONTRACT_VERSION = "2026-08-26.2"
R003_FILE_SHA256 = (
    "bce459dc4454b11638bc7d09320ba90671aea02c711a7b502db13e52d7b45e75"
)
R003_CANONICAL_SHA256 = (
    "e782541d53e7350da55eaef9d608d5c1a878db86bde0e7f28807527974607d9f"
)
R003_BYTE_LENGTH = 3_882
R003_CONTRACT_CHECK_IDS = (
    "CONTINUATION",
    "GOAL_GRAPH",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "ROOT_FP048_R002_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)
_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
R003_CONTRACT_COMMANDS = {
    "CONTINUATION": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {_PYTHON} -B "
        "scripts/check_walksafe_project_continuation_v2_4.py --root . "
        "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json"
    ),
    "GOAL_GRAPH": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {_PYTHON} -B "
        "scripts/check_walksafe_goal_graph_v2_4.py --root . "
        "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json"
    ),
    "TEST_LAYER_REGISTRY_VALIDATE": (
        f"PYTHON_BIN={_PYTHON} bash scripts/run_walksafe_test_layers_current.sh validate"
    ),
    "ROOT_FP048_R002_CONTROL_REGRESSION": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {_PYTHON} -B -m pytest "
        "-p no:cacheprovider -q "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826.py "
        "tests/test_walksafe_fp048_r002_goal_start_gate_r003_20260826.py "
        "tests/test_apply_walksafe_fp048_r002_goal_started_seq92_20260826.py "
        "tests/test_walksafe_fp008_goal_start_gate_20260803.py "
        "tests/test_walksafe_project_continuation_v2_4.py::WalkSafeFp048R002Seq90Seq91BoundaryTest "
        "tests/test_walksafe_project_continuation_v2_4.py::WalkSafeFp048R002Seq91Seq92CorrectionBoundaryTest "
        "tests/test_walksafe_goal_graph_v2_4.py::Fp048R002ReviewedNoncreditSuccessorTests "
        "tests/test_walksafe_goal_graph_v2_4.py::Fp048R002Seq91CorrectionTests"
    ),
    "REPOSITORY_STATE": (
        ': "${WALKSAFE_GATE_EVENT_ID:?required}" && '
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {_PYTHON} -B "
        "scripts/check_walksafe_project_continuation_v2_4.py --root . "
        "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json "
        '--print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"'
    ),
}

CORRECTION_REASON = {
    "failed_check_id": "ROOT_FP048_R002_CONTROL_REGRESSION",
    "failed_contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R002",
    "failed_contract_version": "2026-08-26.1",
    "observed_failed_test_count": 2,
    "reason_code": "PUBLISHED_SEQ90_SYNTHETIC_REPLAY_DUPLICATED_SEQ90",
    "remediation": "RECONSTRUCT_EXACT_SEQ89_BEFORE_SYNTHETIC_SEQ90_BOUNDARY_TEST",
}
CLAIM_BOUNDARY = copy.deepcopy(seq90.CLAIM_BOUNDARY)
EVENT_FIELDS = seq90.EVENT_FIELDS | {"correction_reason"}
REVIEWER = {
    "id": "codex-fp048-r002-seq91-independent-reviewer-20260826",
    "task_id": "/root/fp048_seq90_reanchor_impl/seq90_independent_review",
}
ROUND_ID = "R009"
PROJECTED_TRANSITION = {
    "completion_sequences": [COMPLETION_UPDATE_SEQUENCE, COMPLETION_SEQUENCE],
    "correction_event_id": CORRECTION_EVENT_ID,
    "correction_sequence": CORRECTION_SEQUENCE,
    "correction_transition": "READY_TO_READY",
    "started_event_id": STARTED_EVENT_ID,
    "started_sequence": STARTED_SEQUENCE,
    "started_transition": "READY_TO_IN_PROGRESS_AFTER_FRESH_R003_PASS",
}
INDEPENDENT_CHECKS = [
    "EXACT_SEQ90_SOURCE_CAS_AND_SEQ89_READY_BINDING_PASS",
    "EXACT_SEQ90_SOURCE_GIT_HEAD_CAS_PASS",
    "SEQ91_READY_TO_READY_ZERO_CREDIT_PASS",
    "SEQ91_EVENT_TIME_AFTER_INDEPENDENT_REVIEW_PASS",
    "R002_FAILURE_CLASSIFICATION_AND_DETERMINISTIC_REPLAY_PASS",
    "R003_FIVE_CHECK_GATE_AND_SEQ92_START_AUTHORITY_PASS",
    "REVIEWED_RUNTIME_DEPENDENCY_CLOSURE_PASS",
    "RUNTIME_TRUST_ROOT_AND_GATE_ADAPTER_CLOSURE_PASS",
    "R003_RECEIPT_CONTRACT_IDENTITY_PASS",
    "R003_GATE_MODULE_SHADOW_REMOVAL_PASS",
    "SEQ92_GATE_EVIDENCE_MEMBERSHIP_MODE_CANONICAL_RESEAL_PASS",
    "SEQ92_INJECTED_WRITER_COMMIT_GUARD_PASS",
    "SEQ92_WRITER_POSTREPLACE_ERROR_CLASSIFICATION_PASS",
    "SEQ92_PASS_RECEIPT_PRODUCER_ORDER_RECONSTRUCTION_PASS",
    "SEQ92_IMPORT_TRUST_RUNTIME_CLOSURE_PASS",
    "SEQ92_RUNTIME_IMPORT_AFTER_COHORT_CAPTURE_PASS",
    "PRE_AND_POST_CONSUMER_COHORT_RESEAL_PASS",
    "SEQ92_TO_SEQ91_EXACT_BYTE_RECONSTRUCTION_PASS",
    "SEQ92_PRE_OVERWRITE_INVERSE_DELTA_STATE_VALIDATION_PASS",
    "SEQ92_INVERSE_RECEIPT_SOURCE_CHECKPOINT_BINDING_PASS",
    "SEQ92_POSTCOMMIT_TERMINAL_CONSUMER_RESEAL_PASS",
    "SEQ92_TERMINAL_CONSUMER_DEEP_COPY_AUTHORITY_ISOLATION_PASS",
    "SEQ92_PUBLISHED_CHECKPOINT_MODE_RESEAL_PASS",
    "SEQ92_CANONICAL_RAW_PROJECTED_TAIL_NESTED_JSON_TYPE_EQUALITY_PASS",
    "SEQ91_UNCHANGED_CONTROL_PROJECTION_EXACT_FIELD_AND_HASH_PASS",
    "SEQ91_PRESERVED_CHECKPOINT_PROJECTION_CANONICAL_SEAL_PASS",
    "SEQ91_TO_SEQ90_EXACT_SOURCE_CAS_RECONSTRUCTION_PASS",
    "PROJECTED_CONTINUATION_AND_GOAL_GRAPH_PASS",
]

CORRECTION_SCOPE = (
    "Graph v2.4 FP-048 R002 remains READY through zero-credit start-control "
    "correction seq91; R003 gate and seq92 start remain NOT_RUN."
)
CORRECTION_HANDOFF_EPIC = "EPIC-03 / FP-048 R002/GAP-057 READY_R003_GATE_NOT_RUN"
CORRECTION_VERIFICATION_STATUS = (
    "FP048_R002_SEQ91_ZERO_CREDIT_CONTROL_CORRECTED_R003_GATE_NOT_RUN"
)
CORRECTION_NEXT_ACTION = (
    "R003 event-scoped five-check start gate를 fresh event ID로 실행한다."
)
STARTED_CURRENT_FOCUS = (
    "FP-048 R002/GAP-057 GOAL_STARTED/IN_PROGRESS; repository-internal "
    "seven-state encryption rotation all-or-nothing fail-fast work authorized"
)
STARTED_NEXT_ACTION = "FP-048 R002에서 7종 state rotation을 all-or-nothing으로 재개한다."
STARTED_SCOPE = (
    "Graph v2.4 through FP048-R002/GAP-057 GOAL_STARTED seq92 after exact "
    "seq91 zero-credit control correction and fresh R003 five-check internal "
    "PASS gate; no product completion, artifact completion, formal-test, "
    "device, external, deployment, approval, or release credit."
)
STARTED_HANDOFF_EPIC = "EPIC-03 / FP-048 R002/GAP-057 IN_PROGRESS"
STARTED_GOAL_SHA256 = GOAL_SHA256
STARTED_VERIFICATION_STATUS = (
    "PASS_WITH_FP048-R002_IN_PROGRESS_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
)
STARTED_EVENT_FIELDS = frozenset(
    {
        "sequence",
        "event_id",
        "event_type",
        "occurred_on",
        "occurred_at",
        "previous_focus_goal_id",
        "previous_focus_content_sha256",
        "focus_goal_id",
        "focus_goal_content_sha256",
        "subject_goal_id",
        "from_status",
        "to_status",
        "static_plan_manifest_sha256",
        "status_changes",
        "runtime_after",
        "repository_snapshot_before",
        "implementation_start_gate_binding",
        "blockers_after",
        "blocker_resolution_ids_after",
        "source_checkpoint_version",
        "evidence_refs",
        "previous_event_sha256",
        "event_sha256",
    }
)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


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
        "tail_event_id": SOURCE_SEQ90_EVENT_ID,
        "tail_event_sha256": SOURCE_SEQ90_EVENT_SHA256,
    }


def _source_ready_event_binding() -> dict[str, Any]:
    return copy.deepcopy(seq90._source_ready_event_binding())


def _parse_time(value: str, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except (TypeError, ValueError) as exc:
        raise ControlCorrectionError(f"{label} differs") from exc
    require(parsed.tzinfo is not None and parsed.microsecond == 0, f"{label} differs")
    return parsed


def require_exact_seq90_source(raw: bytes, source: Mapping[str, Any], root: Path) -> None:
    require(len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH, "seq90 source length differs")
    require(sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256, "seq90 source CAS differs")
    require(raw == seq90.checkpoint_json_bytes(source), "seq90 source is noncanonical")
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(isinstance(history, list) and len(history) == SOURCE_SEQUENCE, "source is not exact seq90")
    previous = ""
    for sequence, event in enumerate(history, start=1):
        require(isinstance(event, dict) and event.get("sequence") == sequence, "seq1-90 order differs")
        require(event.get("event_sha256") == continuation.event_sha256(event), "seq1-90 seal differs")
        if sequence > 1:
            require(event.get("previous_event_sha256") == previous, "seq1-90 lineage differs")
        previous = str(event["event_sha256"])
    ready = history[88]
    control = history[89]
    seq90._require_seq90_event(control, ready)
    require(control.get("event_sha256") == SOURCE_SEQ90_EVENT_SHA256, "seq90 tail differs")
    require(
        seq90._validate_edge_shape(control.get("noncredit_successor_edges"))
        == control.get("noncredit_successor_edges"),
        "seq90 sealed noncredit edge shape differs",
    )
    _require_zero_credit(source, source)


def load_exact_seq90_source(root: Path = ROOT) -> tuple[bytes, dict[str, Any]]:
    root = seq90._safe_root(root)
    read = seq90._stable_read(root, CHECKPOINT_REL)
    source = seq90.strict_json(read.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq90_source(read.raw, source, root)
    return read.raw, source


def load_r003_contract(root: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    root = seq90._safe_root(root)
    require(
        len(R003_FILE_SHA256) == 64
        and len(R003_CANONICAL_SHA256) == 64
        and R003_BYTE_LENGTH > 0
        and set(R003_CONTRACT_COMMANDS) == set(R003_CONTRACT_CHECK_IDS),
        "R003 contract constants are not frozen",
    )
    raw = seq90._stable_read(root, R003_CONTRACT_REL).raw
    require(
        len(raw) == R003_BYTE_LENGTH and sha256_bytes(raw) == R003_FILE_SHA256,
        "R003 contract bytes differ",
    )
    document = seq90.strict_json(raw, R003_CONTRACT_REL.as_posix())
    require(
        document.get("document_id") == R003_DOCUMENT_ID
        and document.get("contract_id") == R003_CONTRACT_ID
        and document.get("contract_version") == R003_CONTRACT_VERSION
        and document.get("target_goal_id") == GOAL_ID
        and document.get("target_goal_content_sha256") == GOAL_SHA256
        and tuple(row.get("check_id") for row in document.get("ordered_checks", []))
        == R003_CONTRACT_CHECK_IDS
        and tuple(row.get("command") for row in document.get("ordered_checks", []))
        == tuple(R003_CONTRACT_COMMANDS[item] for item in R003_CONTRACT_CHECK_IDS)
        and continuation.canonical_json_sha256(document) == R003_CANONICAL_SHA256,
        "R003 contract authority differs",
    )
    return document, {
        "schema_version": document["schema_version"],
        "document_id": R003_DOCUMENT_ID,
        "path": R003_CONTRACT_REL.as_posix(),
        "file_sha256": R003_FILE_SHA256,
        "contract_id": R003_CONTRACT_ID,
        "contract_version": R003_CONTRACT_VERSION,
        "canonical_contract_sha256": R003_CANONICAL_SHA256,
    }


def r003_contract_binding(root: Path = ROOT) -> dict[str, Any]:
    return load_r003_contract(root)[1]


def correction_authorization_binding(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, AUTHORIZATION_REL).raw
    document = seq90.strict_json(raw, AUTHORIZATION_REL.as_posix())
    require(
        document.get("document_id") == "WS-FP048-R002-SEQ91-92-AUTHORIZATION-20260826-001"
        and document.get("goal_id") == GOAL_ID
        and document.get("source_checkpoint_binding") == _source_checkpoint_binding()
        and document.get("correction_event_id") == CORRECTION_EVENT_ID
        and document.get("target_started_event_id") == STARTED_EVENT_ID
        and document.get("claim_boundary") == CLAIM_BOUNDARY
        and document.get("correction_reason") == CORRECTION_REASON,
        "seq91 authorization differs",
    )
    return _binding(AUTHORIZATION_REL, raw)


REVIEWED_CONTROL_PATHS = (
    SCRIPT_REL,
    TEST_REL,
    R003_CONTRACT_REL,
    R003_GATE_REL,
    R003_GATE_TEST_REL,
    STARTER_REL,
    STARTER_TEST_REL,
    *GATE_ADAPTER_RUNTIME_CLOSURE_PATHS,
    *IMPORT_TRUST_RUNTIME_CLOSURE_PATHS,
    seq90.SCRIPT_REL,
    seq90.TEST_REL,
    Path("scripts/run_walksafe_fp008_goal_start_gate_20260803.py"),
    Path("tests/test_walksafe_fp008_goal_start_gate_20260803.py"),
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
)


def _r001_stale_assignment_binding(root: Path) -> dict[str, Any]:
    read = seq90._stable_read(root, R001_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == R001_REVIEW_ASSIGNMENT_BYTE_LENGTH
        and sha256_bytes(read.raw) == R001_REVIEW_ASSIGNMENT_SHA256,
        "stale R001 review assignment bytes differ",
    )
    document = seq90.strict_json(read.raw, R001_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        read.raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ91-92-REVIEW-ASSIGNMENT-20260826-R001"
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
        "reason_code": "P1_REVIEW_SCOPE_AND_TRIAD_SCHEMA_GAPS",
        "round_id": "R001",
        "sha256": R001_REVIEW_ASSIGNMENT_SHA256,
    }


def _r002_stale_assignment_binding(root: Path) -> dict[str, Any]:
    read = seq90._stable_read(root, R002_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == R002_REVIEW_ASSIGNMENT_BYTE_LENGTH
        and sha256_bytes(read.raw) == R002_REVIEW_ASSIGNMENT_SHA256,
        "stale R002 review assignment bytes differ",
    )
    document = seq90.strict_json(read.raw, R002_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        read.raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ91-92-REVIEW-ASSIGNMENT-20260826-R002"
        and document.get("round_id") == "R002"
        and document.get("goal_id") == GOAL_ID
        and document.get("supersedes") == _r001_stale_assignment_binding(root),
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
        "reason_code": "P1_MUTABLE_SEQ85_87_EDGE_AUTHORITY",
        "round_id": "R002",
        "sha256": R002_REVIEW_ASSIGNMENT_SHA256,
    }


def _r003_stale_assignment_binding(root: Path) -> dict[str, Any]:
    read = seq90._stable_read(root, R003_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == R003_REVIEW_ASSIGNMENT_BYTE_LENGTH
        and sha256_bytes(read.raw) == R003_REVIEW_ASSIGNMENT_SHA256,
        "stale R003 review assignment bytes differ",
    )
    document = seq90.strict_json(read.raw, R003_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        read.raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ91-92-REVIEW-ASSIGNMENT-20260826-R003"
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
        "reason_code": "P1_PREFLIGHT_POST_CONSUMER_COHORT_RESEAL_MISSING",
        "reason_codes": [
            "P1_PREFLIGHT_POST_CONSUMER_COHORT_RESEAL_MISSING",
            "P1_SEQ92_TO_SEQ91_GOAL_STATUS_RECONSTRUCTION_MISSING",
            "P1_SEQ90_SOURCE_HEAD_CAS_MISSING",
            "P1_SEQ91_EVENT_TIME_NOT_AFTER_INDEPENDENT_REVIEW",
        ],
        "round_id": "R003",
        "sha256": R003_REVIEW_ASSIGNMENT_SHA256,
    }


def _r004_stale_assignment_binding(root: Path) -> dict[str, Any]:
    read = seq90._stable_read(root, R004_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == R004_REVIEW_ASSIGNMENT_BYTE_LENGTH
        and sha256_bytes(read.raw) == R004_REVIEW_ASSIGNMENT_SHA256,
        "stale R004 review assignment bytes differ",
    )
    document = seq90.strict_json(read.raw, R004_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        read.raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ91-92-REVIEW-ASSIGNMENT-20260826-R004"
        and document.get("round_id") == "R004"
        and document.get("goal_id") == GOAL_ID
        and document.get("supersedes") == _r003_stale_assignment_binding(root),
        "stale R004 review assignment authority differs",
    )
    require(
        not os.path.lexists(root / R004_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R004_INDEPENDENT_REVIEW_REL),
        "stale R004 reviewer results must remain absent",
    )
    return {
        "byte_length": R004_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": R004_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P1_REVIEWED_RUNTIME_DEPENDENCY_CLOSURE_INCOMPLETE",
        "reason_codes": [
            "P1_REVIEWED_RUNTIME_DEPENDENCY_CLOSURE_INCOMPLETE",
            "P1_R003_RECEIPT_CONTRACT_IDENTITY_NOT_SYNCHRONIZED",
            "P0_GATE_EVIDENCE_NAMESPACE_MEMBERSHIP_NOT_RESEALED_PRE_AND_POST_PUBLICATION",
            "P1_GATE_EVIDENCE_FILE_MODE_NOT_EXACT_0600",
            "P1_PASS_RECEIPT_EXACT_CANONICAL_BYTES_NOT_ENFORCED",
            "P1_INJECTED_CHECKPOINT_WRITER_COMMIT_GUARD_NOT_PROVEN",
            "P1_SEQ92_WRITER_POSTREPLACE_ERROR_MISCLASSIFIED",
            "P1_SEQ92_RECEIPT_KEY_ORDER_NOT_RECONSTRUCTED",
            "P1_SEQ92_IMPORT_TRUST_RUNTIME_CLOSURE_INCOMPLETE",
            "P1_SEQ92_RUNTIME_IMPORTED_BEFORE_COHORT_CAPTURE",
        ],
        "round_id": "R004",
        "sha256": R004_REVIEW_ASSIGNMENT_SHA256,
    }


def _r005_stale_assignment_binding(root: Path) -> dict[str, Any]:
    read = seq90._stable_read(root, R005_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == R005_REVIEW_ASSIGNMENT_BYTE_LENGTH
        and sha256_bytes(read.raw) == R005_REVIEW_ASSIGNMENT_SHA256,
        "stale R005 review assignment bytes differ",
    )
    document = seq90.strict_json(read.raw, R005_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        read.raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ91-92-REVIEW-ASSIGNMENT-20260826-R005"
        and document.get("round_id") == "R005"
        and document.get("goal_id") == GOAL_ID
        and document.get("supersedes") == _r004_stale_assignment_binding(root),
        "stale R005 review assignment authority differs",
    )
    require(
        not os.path.lexists(root / R005_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R005_INDEPENDENT_REVIEW_REL),
        "stale R005 reviewer results must remain absent",
    )
    return {
        "byte_length": R005_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": R005_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P1_SEQ92_RECONSTRUCTION_ACCEPTS_INCONSISTENT_STATE",
        "round_id": "R005",
        "sha256": R005_REVIEW_ASSIGNMENT_SHA256,
    }


def _r006_stale_assignment_binding(root: Path) -> dict[str, Any]:
    read = seq90._stable_read(root, R006_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == R006_REVIEW_ASSIGNMENT_BYTE_LENGTH
        and sha256_bytes(read.raw) == R006_REVIEW_ASSIGNMENT_SHA256,
        "stale R006 review assignment bytes differ",
    )
    document = seq90.strict_json(read.raw, R006_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        read.raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ91-92-REVIEW-ASSIGNMENT-20260826-R006"
        and document.get("round_id") == "R006"
        and document.get("goal_id") == GOAL_ID
        and document.get("supersedes") == _r005_stale_assignment_binding(root),
        "stale R006 review assignment authority differs",
    )
    require(
        not os.path.lexists(root / R006_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R006_INDEPENDENT_REVIEW_REL),
        "stale R006 reviewer results must remain absent",
    )
    return {
        "byte_length": R006_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": R006_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P1_R003_GATE_MODULE_SHADOW_SURVIVES_PRODUCTION_BIND",
        "reason_codes": [
            "P1_R003_GATE_MODULE_SHADOW_SURVIVES_PRODUCTION_BIND",
            "P1_SEQ92_INVERSE_IGNORES_RECEIPT_SOURCE_CHECKPOINT_BINDING",
            "P1_SEQ92_POSTCOMMIT_TERMINAL_CONSUMER_RESEAL_MISSING",
        ],
        "round_id": "R006",
        "sha256": R006_REVIEW_ASSIGNMENT_SHA256,
    }


def _r007_stale_assignment_binding(root: Path) -> dict[str, Any]:
    read = seq90._stable_read(root, R007_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == R007_REVIEW_ASSIGNMENT_BYTE_LENGTH
        and sha256_bytes(read.raw) == R007_REVIEW_ASSIGNMENT_SHA256,
        "stale R007 review assignment bytes differ",
    )
    document = seq90.strict_json(read.raw, R007_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        read.raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ91-92-REVIEW-ASSIGNMENT-20260826-R007"
        and document.get("round_id") == "R007"
        and document.get("goal_id") == GOAL_ID
        and document.get("supersedes") == _r006_stale_assignment_binding(root),
        "stale R007 review assignment authority differs",
    )
    require(
        not os.path.lexists(root / R007_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R007_INDEPENDENT_REVIEW_REL),
        "stale R007 reviewer results must remain absent",
    )
    return {
        "byte_length": R007_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": R007_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P1_SEQ92_TERMINAL_CONSUMER_SHALLOW_COPY_MUTATES_AUTHORITY",
        "reason_codes": [
            "P1_SEQ92_TERMINAL_CONSUMER_SHALLOW_COPY_MUTATES_AUTHORITY",
            "P1_SEQ92_PUBLISHED_CHECKPOINT_MODE_NOT_RESEALED",
        ],
        "round_id": "R007",
        "sha256": R007_REVIEW_ASSIGNMENT_SHA256,
    }


def _r008_stale_assignment_binding(root: Path) -> dict[str, Any]:
    read = seq90._stable_read(root, R008_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == R008_REVIEW_ASSIGNMENT_BYTE_LENGTH
        and sha256_bytes(read.raw) == R008_REVIEW_ASSIGNMENT_SHA256,
        "stale R008 review assignment bytes differ",
    )
    document = seq90.strict_json(read.raw, R008_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        read.raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ91-92-REVIEW-ASSIGNMENT-20260826-R008"
        and document.get("round_id") == "R008"
        and document.get("goal_id") == GOAL_ID
        and document.get("supersedes") == _r007_stale_assignment_binding(root),
        "stale R008 review assignment authority differs",
    )
    require(
        not os.path.lexists(root / R008_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R008_INDEPENDENT_REVIEW_REL),
        "stale R008 reviewer results must remain absent",
    )
    return {
        "byte_length": R008_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": R008_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P1_SEQ92_INVERSE_ACCEPTS_AUTHORITY_BOUNDARY_DRIFT",
        "round_id": "R008",
        "sha256": R008_REVIEW_ASSIGNMENT_SHA256,
    }


def build_review_assignment(root: Path, source: Mapping[str, Any]) -> bytes:
    root = seq90._safe_root(root)
    reviewed = [
        _binding(path, seq90._stable_read(root, path).raw)
        for path in REVIEWED_CONTROL_PATHS
    ]
    value = {
        "assigner": {"id": "codex-root", "task_id": "/root"},
        "authorization_binding": correction_authorization_binding(root),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "document_id": "WS-FP048-R002-SEQ91-92-REVIEW-ASSIGNMENT-20260826-R009",
        "executor": {
            "id": "codex-fp048-r002-seq91-control-executor-20260826",
            "task_id": "/root/fp048_seq90_reanchor_impl",
        },
        "goal_id": GOAL_ID,
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "replacement_contract_binding": r003_contract_binding(root),
        "required_reviewer": copy.deepcopy(REVIEWER),
        "review_scope": [
            "exact published seq90 source CAS and seq91 adjacency",
            "R002 root-regression failure classification and deterministic source replay",
            "READY-to-READY status with all credit deltas zero",
            "runtime final Git-visible managed snapshot reseal",
            "R003 five-check private gate and conditional seq92 start",
            "exact imported seq90 runtime dependency binding",
            "canonical R009 review triad schema and stale R001-R008 result absence",
            "pre- and post-consumer Git-visible cohort reseal",
            "exact seq92-to-seq91 checkpoint byte reconstruction",
            "live Git HEAD equality with the exact seq90 source mirror",
            "seq91 event time strictly after the independent R009 review",
            "complete direct and transitive runtime dependency source guards",
            "runtime trust roots are the reviewed continuation checker, Goal Graph checker, FP008 hardened gate runtime/test, and seq90 correction transport",
            "R003 adapter closure is the reviewed R002 gate/test plus seq88-89 runtime/test; refactored seq92 uses only reviewed trust roots",
            "R003 receipt builder uses only the exact reviewed R003 contract ID, version, file SHA-256, and canonical SHA-256",
            "seq92 precommit and postcommit gate evidence membership, 0600 mode, and canonical receipt bytes are resealed",
            "seq92 injected checkpoint writer must execute and prove the exact commit guard",
            "seq92 writer errors after target replacement are classified as postcommit uncertain by exact target-byte CAS",
            "seq92 PASS receipt bytes are reconstructed in exact producer top-level and nested key order before comparison",
            "seq92, continuation, and Goal Graph imports are limited to the final managed cohort or the exact reviewed ten-path runtime trust closure",
            "seq92 captures the full managed and Git-visible cohort before importing Goal Graph or other reviewed runtime consumers",
            "seq92 inverse-delta state, current work, managed snapshot, and handoff are exact before any seq91 reconstruction overwrite",
            "R003 production bind removes any module-level target Goal SHA-256 shadow before seq92 consumes the exact authority",
            "seq92 inverse reconstruction binds the physical PASS receipt source checkpoint SHA-256 to the exact restored seq91 bytes",
            "seq92 terminal consumers are followed by checkpoint, receipt namespace and mode, managed input, and Git-visible cohort reseals",
            "seq92 terminal consumers receive deep-copied authority so mutations cannot alter the prepared checkpoint or receipt",
            "seq92 published checkpoint mode is resealed after terminal consumers and before successful return",
            "seq92 exact canonical raw, projected checkpoint, and tail event preserve nested JSON value types without equality coercion",
            "seq91 unchanged-control projection has the exact five control-field canonical hashes plus preserved_checkpoint and matches the exact seq91 checkpoint or restored seq91 inverse",
            "seq91 and restored seq92 inverse reconstruct the complete immutable seq90 checkpoint and match its exact canonical byte length and SHA-256",
        ],
        "reviewed_control_inputs": reviewed,
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "supersedes": _r008_stale_assignment_binding(root),
    }
    return seq90.canonical_json_bytes(value)


def _load_physical_review(root: Path) -> dict[str, dict[str, Any]]:
    assignment_raw = seq90._stable_read(root, REVIEW_ASSIGNMENT_REL).raw
    expected_assignment = build_review_assignment(
        root,
        seq90.strict_json(
            seq90._stable_read(root, CHECKPOINT_REL).raw,
            CHECKPOINT_REL.as_posix(),
        ),
    )
    require(assignment_raw == expected_assignment, "seq91 review assignment differs")
    result_raw = seq90._stable_read(root, REVIEW_RESULT_REL).raw
    independent_raw = seq90._stable_read(root, INDEPENDENT_REVIEW_REL).raw
    assignment = seq90.strict_json(assignment_raw, REVIEW_ASSIGNMENT_REL.as_posix())
    result = seq90.strict_json(result_raw, REVIEW_RESULT_REL.as_posix())
    independent = seq90.strict_json(independent_raw, INDEPENDENT_REVIEW_REL.as_posix())
    require(
        result_raw == seq90.canonical_json_bytes(result)
        and independent_raw == seq90.canonical_json_bytes(independent),
        "seq91 review documents are noncanonical",
    )
    require(
        set(assignment)
        == {
            "assigner",
            "authorization_binding",
            "claim_boundary",
            "correction_reason",
            "document_id",
            "executor",
            "goal_id",
            "projected_transition",
            "replacement_contract_binding",
            "required_reviewer",
            "review_scope",
            "reviewed_control_inputs",
            "round_id",
            "schema_version",
            "source_checkpoint_binding",
            "supersedes",
        }
        and set(result)
        == {
            "assignment_binding",
            "claim_boundary",
            "correction_reason",
            "decision",
            "document_id",
            "external_independence_claimed",
            "findings",
            "goal_id",
            "projected_transition",
            "replacement_contract_binding",
            "reviewed_at",
            "reviewer",
            "round_id",
            "schema_version",
        }
        and set(independent)
        == {
            "assignment_binding",
            "claim_boundary",
            "correction_reason",
            "decision",
            "document_id",
            "external_independence_claimed",
            "findings",
            "goal_id",
            "independent_checks",
            "projected_transition",
            "replacement_contract_binding",
            "review_result_binding",
            "reviewed_at",
            "reviewer",
            "round_id",
            "schema_version",
        },
        "seq91 review field set differs",
    )
    assignment_binding = _binding(REVIEW_ASSIGNMENT_REL, assignment_raw)
    result_binding = _binding(REVIEW_RESULT_REL, result_raw)
    replacement = r003_contract_binding(root)
    require(
        assignment.get("schema_version") == "1.0"
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ91-92-REVIEW-ASSIGNMENT-20260826-R009"
        and assignment.get("round_id") == ROUND_ID
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("required_reviewer") == REVIEWER
        and assignment.get("claim_boundary") == CLAIM_BOUNDARY
        and assignment.get("correction_reason") == CORRECTION_REASON
        and assignment.get("replacement_contract_binding") == replacement
        and assignment.get("projected_transition") == PROJECTED_TRANSITION
        and assignment.get("supersedes") == _r008_stale_assignment_binding(root)
        and result.get("schema_version") == "1.0"
        and result.get("document_id")
        == "WS-FP048-R002-SEQ91-92-REVIEW-RESULT-20260826-R009"
        and result.get("round_id") == ROUND_ID
        and result.get("goal_id") == GOAL_ID
        and result.get("assignment_binding") == assignment_binding
        and result.get("decision") == "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and result.get("findings") == []
        and result.get("reviewer") == REVIEWER
        and result.get("external_independence_claimed") is False
        and result.get("claim_boundary") == CLAIM_BOUNDARY
        and result.get("correction_reason") == CORRECTION_REASON
        and result.get("replacement_contract_binding") == replacement
        and result.get("projected_transition") == PROJECTED_TRANSITION
        and independent.get("schema_version") == "1.0"
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ91-92-INDEPENDENT-REVIEW-20260826-R009"
        and independent.get("round_id") == ROUND_ID
        and independent.get("goal_id") == GOAL_ID
        and independent.get("assignment_binding") == assignment_binding
        and independent.get("review_result_binding") == result_binding
        and independent.get("decision") == "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and independent.get("findings") == []
        and independent.get("reviewer") == REVIEWER
        and independent.get("external_independence_claimed") is False
        and independent.get("claim_boundary") == CLAIM_BOUNDARY
        and independent.get("correction_reason") == CORRECTION_REASON
        and independent.get("replacement_contract_binding") == replacement
        and independent.get("projected_transition") == PROJECTED_TRANSITION
        and independent.get("independent_checks") == INDEPENDENT_CHECKS,
        "seq91 review approval differs",
    )
    result_at = _parse_time(result.get("reviewed_at"), "seq91 result reviewed_at")
    independent_at = _parse_time(
        independent.get("reviewed_at"),
        "seq91 independent reviewed_at",
    )
    source_at = _parse_time(
        "2026-08-25T19:53:49+00:00",
        "seq90 source occurred_at",
    )
    require(
        result_at.utcoffset() is not None
        and independent_at.utcoffset() is not None
        and result_at > source_at
        and independent_at > result_at,
        "seq91 review time order differs",
    )
    return {
        "assignment": assignment_binding,
        "review_result": result_binding,
        "independent_review": _binding(INDEPENDENT_REVIEW_REL, independent_raw),
    }


def transition_review_binding(root: Path = ROOT) -> dict[str, dict[str, Any]]:
    return _load_physical_review(seq90._safe_root(root))


def _retained_independent_reviewed_at(
    retained: Mapping[Path, Any],
) -> datetime:
    read = retained.get(INDEPENDENT_REVIEW_REL)
    require(read is not None and type(read.raw) is bytes, "retained independent review differs")
    document = seq90.strict_json(read.raw, INDEPENDENT_REVIEW_REL.as_posix())
    require(
        read.raw == seq90.canonical_json_bytes(document)
        and document.get("round_id") == ROUND_ID
        and document.get("reviewer") == REVIEWER,
        "retained independent review authority differs",
    )
    parsed = _parse_time(
        document.get("reviewed_at"),
        "seq91 independent reviewed_at",
    )
    require(parsed.utcoffset() is not None, "seq91 independent reviewed_at differs")
    return parsed


def _event_time(
    source: Mapping[str, Any],
    supplied: str | None,
    independent_reviewed_at: datetime,
) -> str:
    candidate = (
        _parse_time(supplied, "seq91 occurred_at")
        if supplied is not None
        else datetime.now().astimezone().replace(microsecond=0)
    )
    source_time = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq90 occurred_at",
    )
    require(
        independent_reviewed_at.tzinfo is not None
        and independent_reviewed_at.microsecond == 0,
        "seq91 independent reviewed_at differs",
    )
    return max(
        candidate,
        source_time + timedelta(seconds=1),
        independent_reviewed_at + timedelta(seconds=1),
    ).isoformat()


UNCHANGED_CONTROL_PROJECTION_FIELDS = (
    "approved_state",
    "authority_boundary",
    "canonical_bindings",
    "current_work",
    "verification_boundary",
)
PRESERVED_GOAL_EXECUTION_DELTA_FIELDS = {
    "transition_history",
    "transition_history_anchor_sha256",
    "validation_cutoff_at",
}
PRESERVED_SNAPSHOT_DELTA_FIELDS = {
    "scope",
    "managed_changed_paths",
    "managed_changed_path_count",
    "path_set_sha256",
    "content_set_sha256",
}
PRESERVED_HANDOFF_DELTA_FIELDS = {
    "changed_files",
    "current_epic",
    "last_updated_by_work_item",
    "last_verification_status",
    "next_single_action",
}
PRESERVED_HANDOFF_MIRROR_DELTA_FIELDS = {
    "file_count",
    "path_set_sha256",
    "content_set_sha256",
}


def _unchanged_projection(source: Mapping[str, Any]) -> dict[str, str]:
    projection = {
        field: continuation.canonical_json_sha256(source[field])
        for field in UNCHANGED_CONTROL_PROJECTION_FIELDS
    }
    projection["preserved_checkpoint"] = _preserved_checkpoint_projection_sha256(
        source
    )
    return projection


def _require_exact_unchanged_control_projection(
    seq91_projection: Mapping[str, Any],
) -> None:
    state = seq91_projection.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "unchanged control projection source is not exact seq91",
    )
    event = history[CORRECTION_SEQUENCE - 1]
    sealed = event.get("unchanged_control_projection") if isinstance(event, dict) else None
    require(
        type(sealed) is dict
        and set(sealed)
        == set(UNCHANGED_CONTROL_PROJECTION_FIELDS) | {"preserved_checkpoint"}
        and all(field in seq91_projection for field in UNCHANGED_CONTROL_PROJECTION_FIELDS),
        "seq91 unchanged control projection field set differs",
    )
    require(
        sealed == _unchanged_projection(seq91_projection),
        "seq91 unchanged control projection hashes differ",
    )


def _preserved_checkpoint_projection(
    checkpoint: Mapping[str, Any],
) -> dict[str, Any]:
    preserved = copy.deepcopy(dict(checkpoint))
    state = preserved.get("goal_execution")
    snapshot = preserved.get("working_tree_snapshot")
    handoff = preserved.get("session_handoff")
    mirror = handoff.get("source_commit_or_snapshot") if isinstance(handoff, dict) else None
    require(
        isinstance(state, dict)
        and isinstance(snapshot, dict)
        and isinstance(handoff, dict)
        and isinstance(mirror, dict),
        "preserved checkpoint projection structure differs",
    )
    for field in PRESERVED_GOAL_EXECUTION_DELTA_FIELDS:
        state.pop(field, None)
    for field in PRESERVED_SNAPSHOT_DELTA_FIELDS:
        snapshot.pop(field, None)
    for field in PRESERVED_HANDOFF_DELTA_FIELDS:
        handoff.pop(field, None)
    for field in PRESERVED_HANDOFF_MIRROR_DELTA_FIELDS:
        mirror.pop(field, None)
    return preserved


def _preserved_checkpoint_projection_sha256(
    checkpoint: Mapping[str, Any],
) -> str:
    return continuation.canonical_json_sha256(
        _preserved_checkpoint_projection(checkpoint)
    )


def _require_exact_preserved_checkpoint_projection(
    seq91_projection: Mapping[str, Any],
) -> None:
    state = seq91_projection.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "preserved checkpoint projection source is not exact seq91",
    )
    event = history[CORRECTION_SEQUENCE - 1]
    unchanged = event.get("unchanged_control_projection") if isinstance(event, dict) else None
    sealed = unchanged.get("preserved_checkpoint") if isinstance(unchanged, dict) else None
    require(
        isinstance(sealed, str)
        and seq90.SHA256_RE.fullmatch(sealed) is not None
        and sealed == _preserved_checkpoint_projection_sha256(seq91_projection),
        "seq91 preserved checkpoint projection seal differs",
    )


def _require_zero_credit(source: Mapping[str, Any], projected: Mapping[str, Any]) -> None:
    for field in UNCHANGED_CONTROL_PROJECTION_FIELDS:
        require(projected.get(field) == source.get(field), f"seq91 changed {field}")
    source_state = source["goal_execution"]
    state = projected["goal_execution"]
    for field in (
        "artifact_work_queue",
        "completion_boundary",
        "completion_evidence_by_goal",
        "archived_completion_evidence_by_goal",
        "status_by_goal",
        "blockers_by_goal",
    ):
        require(state.get(field) == source_state.get(field), f"seq91 changed {field}")
    require(
        state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and state.get("goal_status") == "READY"
        and projected.get("current_work", {}).get("status") == "READY"
        and projected.get("approved_state", {}).get("formal_test_not_run_count") == 279
        and projected.get("approved_state", {}).get("release_status") == "NOT_ELIGIBLE"
        and projected.get("verification_boundary", {}).get("release_eligible") is False,
        "seq91 zero-credit boundary differs",
    )


def project_seq91(
    root: Path,
    source: Mapping[str, Any],
    *,
    managed_paths: Sequence[str],
    path_set_sha256: str,
    content_set_sha256: str,
    occurred_at: str,
    authorization_binding: Mapping[str, Any],
    review_binding: Mapping[str, Any],
    contract_binding: Mapping[str, Any],
    runner_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    history = source["goal_execution"]["transition_history"]
    control = history[-1]
    paths = sorted(set(managed_paths))
    require(len(paths) == len(managed_paths), "seq91 managed paths differ")
    event: dict[str, Any] = {
        "sequence": CORRECTION_SEQUENCE,
        "event_id": CORRECTION_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_on": _parse_time(occurred_at, "seq91 occurred_at").date().isoformat(),
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
        "runtime_after": copy.deepcopy(control["runtime_after"]),
        "blockers_after": copy.deepcopy(control["blockers_after"]),
        "blocker_resolution_ids_after": copy.deepcopy(control["blocker_resolution_ids_after"]),
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [
            "FP048-R002_R002_ROOT_REGRESSION_FAILURE_CORRECTION",
            "FP048-R002_SEQ91_92_AUTHORIZATION",
            "FP048-R002_SEQ91_92_TRANSITION_CONTROL_REVIEW",
            "FP048-R002_R003_PRIVATE_INITIAL_START_GATE",
        ],
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "source_ready_event_binding": _source_ready_event_binding(),
        "authorization_binding": copy.deepcopy(dict(authorization_binding)),
        "contract_supersession": {
            "previous_contract_binding": copy.deepcopy(
                control["contract_supersession"]["replacement_contract_binding"]
            ),
            "reason_code": CORRECTION_REASON["reason_code"],
            "replacement_contract_binding": copy.deepcopy(dict(contract_binding)),
        },
        "start_gate_runner_binding": copy.deepcopy(dict(runner_binding)),
        "transition_control_review_binding": copy.deepcopy(dict(review_binding)),
        "repository_context_reanchor": {
            "before": {
                **_source_checkpoint_binding(),
                "scope": source["working_tree_snapshot"]["scope"],
                "managed_changed_paths": copy.deepcopy(
                    source["working_tree_snapshot"]["managed_changed_paths"]
                ),
                "managed_changed_path_count": source["working_tree_snapshot"]["managed_changed_path_count"],
                "path_set_sha256": source["working_tree_snapshot"]["path_set_sha256"],
                "content_set_sha256": source["working_tree_snapshot"]["content_set_sha256"],
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
        "noncredit_successor_edges": copy.deepcopy(control["noncredit_successor_edges"]),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "unchanged_control_projection": _unchanged_projection(source),
        "canonical_binding_snapshot_after": copy.deepcopy(control["canonical_binding_snapshot_after"]),
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "previous_event_sha256": SOURCE_SEQ90_EVENT_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq91 correction event field set differs")
    projected = copy.deepcopy(source)
    state = projected["goal_execution"]
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    snapshot = projected["working_tree_snapshot"]
    snapshot.update(
        {
            "scope": CORRECTION_SCOPE,
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
    handoff["current_epic"] = CORRECTION_HANDOFF_EPIC
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = CORRECTION_VERIFICATION_STATUS
    handoff["next_single_action"] = CORRECTION_NEXT_ACTION
    _require_zero_credit(source, projected)
    return projected, event


def _require_exact_correction_reanchor_before(
    event: Mapping[str, Any],
) -> dict[str, Any]:
    context = event.get("repository_context_reanchor")
    before = context.get("before") if isinstance(context, dict) else None
    after = context.get("after") if isinstance(context, dict) else None
    source_binding = _source_checkpoint_binding()
    before_fields = set(source_binding) | {
        "scope",
        "managed_changed_paths",
        "managed_changed_path_count",
        "path_set_sha256",
        "content_set_sha256",
    }
    after_fields = {
        "base_commit",
        "branch",
        "current_head",
        "managed_changed_path_count",
        "path_set_sha256",
        "content_set_sha256",
    }
    paths = before.get("managed_changed_paths") if isinstance(before, dict) else None
    require(
        isinstance(context, dict)
        and set(context) == {"before", "after"}
        and isinstance(before, dict)
        and set(before) == before_fields
        and {field: before.get(field) for field in source_binding} == source_binding
        and before.get("scope") == seq90.FINAL_SCOPE
        and isinstance(paths, list)
        and all(type(path) is str and path for path in paths)
        and paths == sorted(set(paths))
        and before.get("managed_changed_path_count") == len(paths)
        and isinstance(before.get("path_set_sha256"), str)
        and seq90.SHA256_RE.fullmatch(before["path_set_sha256"]) is not None
        and isinstance(before.get("content_set_sha256"), str)
        and seq90.SHA256_RE.fullmatch(before["content_set_sha256"]) is not None
        and isinstance(after, dict)
        and set(after) == after_fields,
        "seq91 correction repository before structure differs",
    )
    return before


def _restored_seq90_checkpoint(
    seq91_projection: Mapping[str, Any],
) -> dict[str, Any]:
    state = seq91_projection.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq90 inverse source is not exact seq91",
    )
    correction = history[CORRECTION_SEQUENCE - 1]
    require(isinstance(correction, dict), "seq91 correction event differs")
    before = _require_exact_correction_reanchor_before(correction)
    restored = copy.deepcopy(seq91_projection)
    restored_state = restored["goal_execution"]
    source_control = restored_state["transition_history"][SOURCE_SEQUENCE - 1]
    restored_state["transition_history"] = restored_state["transition_history"][
        :SOURCE_SEQUENCE
    ]
    restored_state["transition_history_anchor_sha256"] = source_control[
        "event_sha256"
    ]
    restored_state["validation_cutoff_at"] = source_control["occurred_at"]
    snapshot = restored["working_tree_snapshot"]
    snapshot["scope"] = before["scope"]
    snapshot["managed_changed_paths"] = copy.deepcopy(
        before["managed_changed_paths"]
    )
    snapshot["managed_changed_path_count"] = before[
        "managed_changed_path_count"
    ]
    snapshot["path_set_sha256"] = before["path_set_sha256"]
    snapshot["content_set_sha256"] = before["content_set_sha256"]
    handoff = restored["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(before["managed_changed_paths"])
    mirror = handoff["source_commit_or_snapshot"]
    mirror["file_count"] = before["managed_changed_path_count"]
    mirror["path_set_sha256"] = before["path_set_sha256"]
    mirror["content_set_sha256"] = before["content_set_sha256"]
    handoff["current_epic"] = seq90.SEQ90_CURRENT_EPIC
    handoff["last_updated_by_work_item"] = seq90.SEQ90_LAST_UPDATED_BY_WORK_ITEM
    handoff["last_verification_status"] = seq90.SEQ90_VERIFICATION_STATUS
    handoff["next_single_action"] = seq90.SEQ90_NEXT_SINGLE_ACTION
    return restored


def _require_exact_restored_seq90_source(
    seq91_projection: Mapping[str, Any],
) -> None:
    restored_raw = seq90.checkpoint_json_bytes(
        _restored_seq90_checkpoint(seq91_projection)
    )
    require(
        len(restored_raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(restored_raw) == SOURCE_CHECKPOINT_SHA256,
        "restored seq90 source CAS differs",
    )


def _restored_seq91_checkpoint(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    restored = copy.deepcopy(checkpoint)
    state = restored["goal_execution"]
    correction = state["transition_history"][CORRECTION_SEQUENCE - 1]
    state["transition_history"] = state["transition_history"][:CORRECTION_SEQUENCE]
    state["transition_history_anchor_sha256"] = correction["event_sha256"]
    state["validation_cutoff_at"] = correction["occurred_at"]
    state["status_by_goal"][GOAL_ID] = "READY"
    state["goal_status"] = "READY"
    restored["current_work"]["status"] = "READY"
    restored["current_work"]["current_focus"] = (
        "FP-048 R002/GAP-057 READY; initial five-check start gate NOT_RUN"
    )
    restored["current_work"]["release_completion_claimed"] = False
    handoff = restored["session_handoff"]
    handoff["current_epic"] = CORRECTION_HANDOFF_EPIC
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = CORRECTION_VERIFICATION_STATUS
    handoff["next_single_action"] = CORRECTION_NEXT_ACTION
    restored["working_tree_snapshot"]["scope"] = CORRECTION_SCOPE
    return restored


def _require_exact_seq92_projection(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool,
) -> None:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == STARTED_SEQUENCE,
        "checkpoint is not exact seq92",
    )
    correction = history[CORRECTION_SEQUENCE - 1]
    started = history[STARTED_SEQUENCE - 1]
    require(
        isinstance(correction, dict)
        and isinstance(started, dict)
        and set(started) == STARTED_EVENT_FIELDS,
        "seq92 start field set differs",
    )
    occurred_at = _parse_time(started.get("occurred_at"), "seq92 occurred_at")
    binding = started.get("implementation_start_gate_binding")
    expected_receipt_path = (
        "docs/control/execution/goal-gates/"
        f"{STARTED_EVENT_ID}/implementation-start-gate-receipt.json"
    )
    expected_document_id = (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-"
        "FP048-R002-20260826-002"
    )
    require(
        isinstance(binding, dict)
        and set(binding) == {"document_id", "path", "file_sha256"}
        and binding.get("document_id") == expected_document_id
        and binding.get("path") == expected_receipt_path
        and isinstance(binding.get("file_sha256"), str)
        and seq90.SHA256_RE.fullmatch(binding["file_sha256"]) is not None,
        "seq92 gate binding differs",
    )
    receipt_relative = Path(expected_receipt_path)
    receipt_raw = seq90._stable_read(root, receipt_relative).raw
    receipt = seq90.strict_json(receipt_raw, expected_receipt_path)
    require(
        receipt_raw == seq90.checkpoint_json_bytes(receipt)
        and sha256_bytes(receipt_raw) == binding["file_sha256"],
        "seq92 gate receipt binding differs",
    )
    generated_at = _parse_time(receipt.get("generated_at"), "seq92 gate generated_at")
    correction_at = _parse_time(correction.get("occurred_at"), "seq91 occurred_at")
    expected_occurred_at = max(
        correction_at + timedelta(seconds=1),
        generated_at + timedelta(seconds=1),
    )
    resolution_history = state.get("blocker_resolution_history")
    require(
        isinstance(resolution_history, list)
        and all(isinstance(record, dict) for record in resolution_history),
        "seq92 blocker resolution history differs",
    )
    resolution_ids = [record.get("resolution_id") for record in resolution_history]
    require(
        started.get("sequence") == STARTED_SEQUENCE
        and started.get("event_id") == STARTED_EVENT_ID
        and started.get("event_type") == "GOAL_STARTED"
        and occurred_at == expected_occurred_at
        and started.get("occurred_on") == occurred_at.date().isoformat()
        and started.get("previous_focus_goal_id") == GOAL_ID
        and started.get("previous_focus_content_sha256") == STARTED_GOAL_SHA256
        and started.get("focus_goal_id") == GOAL_ID
        and started.get("focus_goal_content_sha256") == STARTED_GOAL_SHA256
        and started.get("subject_goal_id") == GOAL_ID
        and started.get("from_status") == "READY"
        and started.get("to_status") == "IN_PROGRESS"
        and started.get("static_plan_manifest_sha256") == MANIFEST_SHA256
        and started.get("status_changes") == {GOAL_ID: "IN_PROGRESS"}
        and started.get("runtime_after") == correction.get("runtime_after")
        and started.get("repository_snapshot_before")
        == receipt.get("repository_snapshot")
        and started.get("blockers_after") == correction.get("blockers_after")
        and started.get("blockers_after") == state.get("blockers_by_goal")
        and started.get("blocker_resolution_ids_after")
        == correction.get("blocker_resolution_ids_after")
        and started.get("blocker_resolution_ids_after")
        == resolution_ids
        and started.get("source_checkpoint_version") == checkpoint.get("schema_version")
        and started.get("evidence_refs") == []
        and started.get("previous_event_sha256") == correction.get("event_sha256")
        and started.get("event_sha256") == continuation.event_sha256(started),
        "seq92 start authority differs",
    )
    statuses = state.get("status_by_goal")
    current = checkpoint.get("current_work")
    require(
        isinstance(statuses, dict)
        and statuses.get(GOAL_ID) == "IN_PROGRESS"
        and list(statuses.values()).count("IN_PROGRESS") == 1
        and state.get("goal_status") == "IN_PROGRESS"
        and state.get("transition_history_anchor_sha256")
        == started.get("event_sha256")
        and state.get("validation_cutoff_at") == started.get("occurred_at"),
        "seq92 state projection differs",
    )
    require(
        isinstance(current, dict)
        and current.get("work_item_id") == WORK_ITEM_ID
        and current.get("status") == "IN_PROGRESS"
        and current.get("current_focus") == STARTED_CURRENT_FOCUS
        and current.get("next_action") == STARTED_NEXT_ACTION
        and current.get("release_completion_claimed") is False,
        "seq92 current-work projection differs",
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    repository_context = correction.get("repository_context_reanchor")
    after = repository_context.get("after") if isinstance(repository_context, dict) else None
    require(
        isinstance(paths, list)
        and all(isinstance(path, str) and path for path in paths)
        and paths == sorted(set(paths))
        and isinstance(after, dict)
        and snapshot.get("scope") == STARTED_SCOPE
        and snapshot.get("managed_changed_path_count") == len(paths)
        and snapshot.get("managed_changed_path_count")
        == after.get("managed_changed_path_count")
        and snapshot.get("path_set_sha256") == after.get("path_set_sha256")
        and snapshot.get("content_set_sha256") == after.get("content_set_sha256")
        and snapshot.get("path_set_sha256")
        == hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
        and isinstance(snapshot.get("content_set_sha256"), str)
        and seq90.SHA256_RE.fullmatch(snapshot["content_set_sha256"]) is not None,
        "seq92 working-tree snapshot projection differs",
    )
    if require_live_snapshot:
        require(
            continuation.working_snapshot_hashes(root, paths)
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "seq92 working-tree snapshot hashes differ",
        )
    handoff = checkpoint.get("session_handoff")
    mirror = handoff.get("source_commit_or_snapshot") if isinstance(handoff, dict) else None
    require(
        isinstance(handoff, dict)
        and handoff.get("changed_files") == paths
        and handoff.get("current_epic") == STARTED_HANDOFF_EPIC
        and handoff.get("last_updated_by_work_item") == WORK_ITEM_ID
        and handoff.get("last_verification_status") == STARTED_VERIFICATION_STATUS
        and handoff.get("next_single_action") == CORRECTION_NEXT_ACTION
        and isinstance(mirror, dict)
        and mirror.get("file_count") == len(paths)
        and mirror.get("path_set_sha256") == snapshot.get("path_set_sha256")
        and mirror.get("content_set_sha256") == snapshot.get("content_set_sha256"),
        "seq92 session handoff projection differs",
    )
    restored = _restored_seq91_checkpoint(checkpoint)
    _require_exact_unchanged_control_projection(restored)
    _require_exact_preserved_checkpoint_projection(restored)
    _require_exact_restored_seq90_source(restored)
    restored_raw = seq90.checkpoint_json_bytes(restored)
    require(
        receipt.get("source_checkpoint_sha256") == sha256_bytes(restored_raw),
        "seq92 gate receipt source checkpoint SHA-256 differs",
    )


def require_control_corrected_checkpoint(root: Path, checkpoint: Mapping[str, Any]) -> None:
    root = seq90._safe_root(root)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(isinstance(history, list) and len(history) >= CORRECTION_SEQUENCE, "seq91 correction is missing")
    source_control = history[89]
    event = history[90]
    require(
        source_control.get("event_id") == SOURCE_SEQ90_EVENT_ID
        and source_control.get("event_sha256") == SOURCE_SEQ90_EVENT_SHA256
        and isinstance(event, dict)
        and set(event) == EVENT_FIELDS
        and event.get("sequence") == CORRECTION_SEQUENCE
        and event.get("event_id") == CORRECTION_EVENT_ID
        and event.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and event.get("subject_goal_id") == GOAL_ID
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("previous_event_sha256") == SOURCE_SEQ90_EVENT_SHA256
        and event.get("event_sha256") == continuation.event_sha256(event)
        and event.get("source_checkpoint_binding") == _source_checkpoint_binding()
        and event.get("source_ready_event_binding") == _source_ready_event_binding()
        and event.get("correction_reason") == CORRECTION_REASON
        and event.get("claim_boundary") == CLAIM_BOUNDARY,
        "seq91 correction authority differs",
    )
    _require_exact_correction_reanchor_before(event)
    require(
        event.get("authorization_binding") == correction_authorization_binding(root)
        and event.get("transition_control_review_binding") == transition_review_binding(root)
        and event.get("start_gate_runner_binding")
        == _binding(R003_GATE_REL, seq90._stable_read(root, R003_GATE_REL).raw)
        and event.get("contract_supersession")
        == {
            "previous_contract_binding": seq90.r002_contract_binding(root),
            "reason_code": CORRECTION_REASON["reason_code"],
            "replacement_contract_binding": r003_contract_binding(root),
        }
        and event.get("noncredit_successor_edges")
        == source_control.get("noncredit_successor_edges"),
        "seq91 successor authority differs",
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
            for sequence, item in enumerate(history[:CORRECTION_SEQUENCE], start=1)
        ),
        "seq1-91 lineage differs",
    )
    require(
        checkpoint.get("approved_state", {}).get("formal_test_not_run_count") == 279
        and checkpoint.get("approved_state", {}).get("remaining_gate_count") == 5
        and checkpoint.get("approved_state", {}).get("remaining_gates_waived") is False
        and checkpoint.get("approved_state", {}).get("release_status") == "NOT_ELIGIBLE"
        and checkpoint.get("verification_boundary", {}).get("formal_test_pass_claimed") is False
        and checkpoint.get("verification_boundary", {}).get("release_eligible") is False,
        "seq91/92 gained verification or release credit",
    )
    if len(history) == CORRECTION_SEQUENCE:
        _require_exact_unchanged_control_projection(checkpoint)
        _require_exact_preserved_checkpoint_projection(checkpoint)
        _require_exact_restored_seq90_source(checkpoint)
        require(
            state.get("transition_history_anchor_sha256") == event["event_sha256"]
            and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
            and checkpoint.get("current_work", {}).get("status") == "READY",
            "seq91 READY state differs",
        )
        snapshot = checkpoint.get("working_tree_snapshot")
        after = event["repository_context_reanchor"]["after"]
        require(
            isinstance(snapshot, dict)
            and snapshot.get("managed_changed_path_count") == after["managed_changed_path_count"]
            and snapshot.get("path_set_sha256") == after["path_set_sha256"]
            and snapshot.get("content_set_sha256") == after["content_set_sha256"]
            and continuation.working_snapshot_hashes(root, snapshot["managed_changed_paths"])
            == (after["path_set_sha256"], after["content_set_sha256"]),
            "seq91 managed snapshot differs",
        )
    elif len(history) == STARTED_SEQUENCE:
        _require_exact_seq92_projection(
            root,
            checkpoint,
            require_live_snapshot=True,
        )
    else:
        require(False, "checkpoint is not exact seq91 or seq92")


def reconstructed_seq91_checkpoint_bytes(root: Path, checkpoint: Mapping[str, Any]) -> bytes:
    require_control_corrected_checkpoint(root, checkpoint)
    history = checkpoint["goal_execution"]["transition_history"]
    if len(history) == CORRECTION_SEQUENCE:
        return seq90.checkpoint_json_bytes(checkpoint)
    require(len(history) == STARTED_SEQUENCE, "checkpoint is not exact seq91 or seq92")
    _require_exact_seq92_projection(
        seq90._safe_root(root),
        checkpoint,
        require_live_snapshot=False,
    )
    started = history[91]
    correction = history[90]
    require(
        started.get("sequence") == STARTED_SEQUENCE
        and started.get("event_id") == STARTED_EVENT_ID
        and started.get("event_type") == "GOAL_STARTED"
        and started.get("subject_goal_id") == GOAL_ID
        and started.get("from_status") == "READY"
        and started.get("to_status") == "IN_PROGRESS"
        and started.get("status_changes") == {GOAL_ID: "IN_PROGRESS"}
        and started.get("previous_event_sha256") == correction.get("event_sha256")
        and started.get("event_sha256") == continuation.event_sha256(started),
        "seq92 start anchor differs",
    )
    return seq90.checkpoint_json_bytes(_restored_seq91_checkpoint(checkpoint))


@dataclass(frozen=True)
class Prepared:
    transport: seq90.Prepared

    @property
    def projected(self) -> Mapping[str, Any]:
        return self.transport.projected

    @property
    def event(self) -> Mapping[str, Any]:
        return self.transport.event


def _final_managed_paths(source: Mapping[str, Any], visible: Sequence[str]) -> tuple[str, ...]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    paths.update(path for path in visible if not path.startswith("docs/control/execution/goal-gates/"))
    paths.discard(CHECKPOINT_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEWED_CONTROL_PATHS)
    paths.add(AUTHORIZATION_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEW_PATHS)
    return tuple(sorted(paths))


# Tests may replace this with a deterministic mutation after capture.
_AFTER_CAPTURE_HOOK: Callable[[Path], None] | None = None


def prepare(root: Path = ROOT, *, occurred_at: str | None = None, validate_consumers: bool = True) -> Prepared:
    root = seq90._safe_root(root)
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    source = seq90.strict_json(source_read.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq90_source(source_read.raw, source, root)
    git_head, git_branch = seq90._capture_git_context(root)
    expected_head = source["session_handoff"]["source_commit_or_snapshot"][
        "current_head"
    ]
    require(git_head == expected_head, "live Git HEAD differs from exact seq90 source")
    status_raw, visible = seq90.capture_git_visible_paths(root)
    paths = _final_managed_paths(source, visible)
    managed = seq90._capture_managed_inputs(root, paths)
    path_sha256, content_sha256 = seq90._managed_input_snapshot_hashes(managed)
    retained_paths = tuple(sorted(set(REVIEWED_CONTROL_PATHS) | set(REVIEW_PATHS) | {AUTHORIZATION_REL}))
    retained = {path: seq90._stable_read(root, path) for path in retained_paths}
    review_binding = transition_review_binding(root)
    require(
        review_binding
        == {
            "assignment": _binding(
                REVIEW_ASSIGNMENT_REL,
                retained[REVIEW_ASSIGNMENT_REL].raw,
            ),
            "review_result": _binding(
                REVIEW_RESULT_REL,
                retained[REVIEW_RESULT_REL].raw,
            ),
            "independent_review": _binding(
                INDEPENDENT_REVIEW_REL,
                retained[INDEPENDENT_REVIEW_REL].raw,
            ),
        },
        "seq91 retained review binding differs",
    )
    event_time = _event_time(
        source,
        occurred_at,
        _retained_independent_reviewed_at(retained),
    )
    projected, event = project_seq91(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at=event_time,
        authorization_binding=correction_authorization_binding(root),
        review_binding=review_binding,
        contract_binding=r003_contract_binding(root),
        runner_binding=_binding(R003_GATE_REL, seq90._stable_read(root, R003_GATE_REL).raw),
    )
    visible_inputs = seq90._capture_managed_inputs(root, visible)
    transport = seq90.Prepared(
        root=root,
        source_raw=source_read.raw,
        source_identity=source_read.identity,
        source=source,
        projected=projected,
        projected_raw=seq90.checkpoint_json_bytes(projected),
        event=event,
        retained_inputs=retained,
        managed_inputs=managed,
        git_visible_inputs=visible_inputs,
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    if _AFTER_CAPTURE_HOOK is not None:
        _AFTER_CAPTURE_HOOK(root)
    seq90._require_preflight_cohort_unchanged(
        root,
        source=source_read,
        retained=retained,
        managed=managed,
        git_visible=visible_inputs,
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
        phase="before seq91 projected consumer validation",
    )
    if validate_consumers:
        seq90._validate_projected_with_consumers(root, projected)
        seq90._require_preflight_cohort_unchanged(
            root,
            source=source_read,
            retained=retained,
            managed=managed,
            git_visible=visible_inputs,
            git_status_raw=status_raw,
            git_head=git_head,
            git_branch=git_branch,
            phase="after seq91 projected consumer validation",
        )
    return Prepared(transport)


def write_checkpoint(prepared: Prepared) -> None:
    seq90.write_checkpoint(prepared.transport)


def prepare_review_manifest(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, CHECKPOINT_REL).raw
    source = seq90.strict_json(raw, CHECKPOINT_REL.as_posix())
    require_exact_seq90_source(raw, source, root)
    assignment = build_review_assignment(root, source)
    return {
        "authorization": correction_authorization_binding(root),
        "candidate_review_assignment": _binding(REVIEW_ASSIGNMENT_REL, assignment),
        "reviewer_authored_paths": [REVIEW_RESULT_REL.as_posix(), INDEPENDENT_REVIEW_REL.as_posix()],
        "round_id": ROUND_ID,
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "status": "PREPARED_NOT_PUBLISHED",
    }


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare-review", action="store_true")
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--occurred-at")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.prepare_review:
            sys.stdout.buffer.write(seq90.canonical_json_bytes(prepare_review_manifest(args.root)))
            return 0
        prepared = prepare(args.root, occurred_at=args.occurred_at)
        if args.write:
            write_checkpoint(prepared)
        print(
            "FP048-R002 start-control correction seq91: PASS "
            f"event_sha256={prepared.event['event_sha256']}"
        )
        return 0
    except (ControlCorrectionError, OSError, subprocess.SubprocessError, TypeError, ValueError) as exc:
        print(f"FP048-R002 start-control correction seq91: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
