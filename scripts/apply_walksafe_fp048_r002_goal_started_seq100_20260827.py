#!/usr/bin/env python3
"""Publish FP-048 R002 GOAL_STARTED seq100 after the exact R011 PASS gate."""

from __future__ import annotations

import argparse
import copy
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import stat
import sys
from typing import Any, Callable, Iterator, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_r002_goal_started_seq99_20260827 as prior_start
from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as cas


# Only the hardened CAS/snapshot primitives are inherited.  No R010 identity,
# receipt loader, or projection function is reused by this successor.
seq90 = prior_start.seq90
continuation = prior_start.continuation

CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
SOURCE_SEQUENCE = 99
EVENT_SEQUENCE = 100
SOURCE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-EXECUTION-CORRECTED-"
    "FP048-R002-20260827-007"
)
EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260827-006"
STARTED_EVENT_ID = EVENT_ID
TARGET_GOAL_ID = prior_start.TARGET_GOAL_ID
TARGET_GOAL_SHA256 = prior_start.TARGET_GOAL_SHA256
MANIFEST_SHA256 = prior_start.MANIFEST_SHA256
WORK_ITEM_ID = prior_start.WORK_ITEM_ID
EVENT_FIELDS = frozenset(continuation.V24_FIRST_START_EVENT_FIELDS)

SCRIPT_REL = Path("scripts/apply_walksafe_fp048_r002_goal_started_seq100_20260827.py")
TEST_REL = Path("tests/test_apply_walksafe_fp048_r002_goal_started_seq100_20260827.py")
START_PROJECTION_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq100_"
    "projection_correction_20260828.py"
)
CORRECTION_MODULE = (
    "scripts.apply_walksafe_fp048_r002_start_gate_execution_"
    "correction_seq99_20260827"
)
GATE_MODULE = "scripts.run_walksafe_fp048_r002_goal_start_gate_r011_20260827"
GATE_DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-"
    "FP048-R002-20260827-006"
)
GATE_RECEIPT_REL = (
    Path("docs/control/execution/goal-gates")
    / EVENT_ID
    / "implementation-start-gate-receipt.json"
)
GATE_EVIDENCE_PREFIX = "docs/control/execution/goal-gates/"

TRANSITION_ROOT = Path("docs/control/execution/workstream-transitions/seq99-100")
START_PROJECTION_AUTHORIZATION_REL = (
    TRANSITION_ROOT / "authorization-start-projection-r001.json"
)
START_PROJECTION_REVIEW_ROOT = (
    TRANSITION_ROOT / "review-rounds/START-PROJECTION-R001"
)
START_PROJECTION_REVIEW_ASSIGNMENT_REL = (
    START_PROJECTION_REVIEW_ROOT / "review-assignment.json"
)
START_PROJECTION_REVIEW_RESULT_REL = (
    START_PROJECTION_REVIEW_ROOT / "review-result.json"
)
START_PROJECTION_INDEPENDENT_REVIEW_REL = (
    START_PROJECTION_REVIEW_ROOT / "independent-review.json"
)
START_PROJECTION_REVIEW_PATHS = (
    START_PROJECTION_REVIEW_ASSIGNMENT_REL,
    START_PROJECTION_REVIEW_RESULT_REL,
    START_PROJECTION_INDEPENDENT_REVIEW_REL,
)
START_PROJECTION_REVIEW_ROUND_ID = "START-PROJECTION-R001"

SOURCE_CHECKPOINT_SHA256 = (
    "2b9018f3cc043c5dffcd3a74de8a56e4cc9fca77ca51896e22a561c11ced3090"
)
SOURCE_CHECKPOINT_BYTE_LENGTH = 5_430_109
SOURCE_EVENT_SHA256 = (
    "b9d1b8e149b51614bc542d5439c5f45a78453fcd26a7b677182207e0162ccc91"
)
R011_RECEIPT_SHA256 = (
    "ee05c9dc059f12f4594314c2d23fe7570bfea72101f340197567249cfc8b53d0"
)
R011_RECEIPT_BYTE_LENGTH = 6_406
START_PROJECTION_CAS_TEMP_PREFIX = (
    f".{CHECKPOINT_REL.name}.fp048-seq43-44."
)
START_PROJECTION_CAS_TEMP_SUFFIX = ".tmp"

FROZEN_R010_STARTER_BINDINGS = {
    SCRIPT_REL: {
        "path": SCRIPT_REL.as_posix(),
        "sha256": "eff19f646f9e63eec4276a5c77709557f8d9076e2c15af26e50ca221e6401c34",
        "byte_length": 48_004,
    },
    TEST_REL: {
        "path": TEST_REL.as_posix(),
        "sha256": "57e578ec97ac380439c2a289ae540ceb7305f72b5ab75151c8b9b2953f8a4d02",
        "byte_length": 17_617,
    },
}

START_PROJECTION_PRODUCER = {
    "id": "codex-fp048-r002-seq100-start-projection-r001-producer-20260828",
    "task_id": "/root",
}
START_PROJECTION_PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq100-start-projection-r001-primary-20260828",
    "task_id": "/root/r007_preflight_perf_rootcause",
}
START_PROJECTION_INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq100-start-projection-r001-independent-20260828",
    "task_id": "/root/goalgraph_fp022_perf_test_design",
}

START_PROJECTION_AUTHORIZATION_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ100-START-PROJECTION-AUTHORIZATION-20260828-R001"
)
START_PROJECTION_REVIEW_ASSIGNMENT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ100-START-PROJECTION-REVIEW-ASSIGNMENT-20260828-R001"
)
START_PROJECTION_REVIEW_RESULT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ100-START-PROJECTION-REVIEW-RESULT-20260828-R001"
)
START_PROJECTION_INDEPENDENT_REVIEW_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ100-START-PROJECTION-INDEPENDENT-REVIEW-20260828-R001"
)

OLD_START_PROJECTION_PREFLIGHT_FAILURE = {
    "authority_status": (
        "NONAUTHORITY_POST_R011_SEQ100_PREFLIGHT_FAILED_PRE_CAS_SOURCE_RETAINED"
    ),
    "observed_cli_mode": "--preflight",
    "exit_code": 1,
    "wall_clock_display": "1:25.49",
    "wall_seconds": 85.49,
    "user_seconds": 77.38,
    "system_seconds": 8.10,
    "cpu_percent": 99,
    "maximum_resident_set_kb": 182_684,
    "first_controlling_errors": [
        "seq99 managed path preimage is ambiguous",
        "v2.4 working snapshot includes its checkpoint",
        "v2.4 working snapshot includes direct gate evidence",
    ],
    "termination_timestamp_available": False,
    "observed_at_estimate": "2026-08-28T08:31:00+09:00",
    "observed_at_estimate_basis": "APPROXIMATE_OPERATOR_OBSERVATION_NOT_EXACT",
    "checkpoint_write_attempted": False,
    "checkpoint_published": False,
    "cas_attempted": False,
}
EXPECTED_CHECK_IDS = (
    "CONTINUATION",
    "GOAL_GRAPH",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "ROOT_FP048_R002_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)
START_PROJECTION_REVIEWED_CONTROL_PATHS = tuple(
    sorted(
        {
            Path(".gitignore"),
            Path("configs/walksafe_node_toolchain_lock_20260715.json"),
            Path("scripts/apply_walksafe_fp048_goal_completed_seq43_44_20260802.py"),
            Path("scripts/apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py"),
            Path("scripts/apply_walksafe_fp048_r002_start_gate_execution_correction_seq98_20260827.py"),
            Path("scripts/apply_walksafe_fp048_r002_start_gate_execution_correction_seq99_20260827.py"),
            SCRIPT_REL,
            Path("scripts/check_walksafe_goal_graph_v2_4.py"),
            Path("scripts/check_walksafe_node_toolchain_20260715.py"),
            Path("scripts/check_walksafe_project_continuation_v2_4.py"),
            Path("scripts/run_walksafe_fp008_goal_start_gate_20260803.py"),
            Path("scripts/run_walksafe_fp048_r002_goal_start_gate_r011_20260827.py"),
            Path("scripts/run_walksafe_test_layers_current.sh"),
            TEST_REL,
            START_PROJECTION_TEST_REL,
            Path("tests/test_walksafe_fp048_goal_completed_seq43_44_20260802.py"),
            Path("tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py"),
            Path("tests/test_apply_walksafe_fp048_r002_start_gate_execution_correction_seq98_20260827.py"),
            Path("tests/test_apply_walksafe_fp048_r002_start_gate_execution_correction_seq99_20260827.py"),
            Path("tests/test_repository_catalogs.py"),
            Path("tests/test_walksafe_fp008_goal_start_gate_20260803.py"),
            Path("tests/test_walksafe_fp048_r002_goal_start_gate_r011_20260827.py"),
            Path("tests/test_walksafe_fp048_r002_post_seq99_stage_regression_20260827.py"),
            Path("tests/test_walksafe_goal_graph_v2_4.py"),
            Path("tests/test_walksafe_project_continuation_v2_4.py"),
            Path(
                "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
                "initial-start-gate-contract-r011.json"
            ),
        },
        key=lambda value: value.as_posix(),
    )
)
RECEIPT_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "gate_purpose",
    "status",
    "package_id",
    "target_transition_event_id",
    "target_goal_id",
    "target_goal_content_sha256",
    "static_plan_manifest_sha256",
    "source_activation_event_sha256",
    "source_checkpoint_sha256",
    "source_ready_event_sha256",
    "check_command_contract_version",
    "check_command_contract_sha256",
    "implementation_start_gate_contract_binding",
    "runtime_bindings",
    "execution_window",
    "check_runs",
    "repository_snapshot",
    "generated_at",
}

STARTED_CURRENT_FOCUS = (
    "FP-048 R002/GAP-057 GOAL_STARTED/IN_PROGRESS after exact seq99 "
    "R010 execution-failure correction and private R011 PASS"
)
STARTED_NEXT_ACTION = (
    "FP-048 R002의 seven-state encryption rotation을 내부 범위에서 완료한다."
)
STARTED_SCOPE = (
    "Graph v2.4 through FP048 R002 GOAL_STARTED seq100; formal, device, "
    "external, deployment and release credit remain zero."
)
STARTED_HANDOFF = "EPIC-03 / FP-048 R002/GAP-057 IN_PROGRESS"
STARTED_VERIFICATION = (
    "R011_PASS_FP048_R002_IN_PROGRESS_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
)


class StartApplyError(RuntimeError):
    """Exact seq99 correction and its private R011 PASS do not authorize seq100."""


@dataclass(frozen=True)
class GateEvidence:
    receipt: Mapping[str, Any]
    receipt_bytes: bytes
    receipt_binding: Mapping[str, Any]
    event_occurred_at: str
    log_sha256_by_check_id: Mapping[str, str]


@dataclass(frozen=True)
class PreparedProjection:
    root: Path
    source_bytes: bytes
    source: Mapping[str, Any]
    projected: Mapping[str, Any]
    projected_bytes: bytes
    event: Mapping[str, Any]
    evidence: GateEvidence
    source_identity: Any
    retained_inputs: Mapping[Path, Any]
    managed_inputs: Mapping[Path, Any]
    git_visible_inputs: Mapping[Path, Any]
    git_status_raw: bytes
    git_head: str
    git_branch: str


def require(condition: bool, message: str) -> None:
    if not condition:
        raise StartApplyError(message)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def checkpoint_bytes(value: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")


def gate_receipt_bytes(value: Mapping[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def strict_json(raw: bytes, label: str) -> dict[str, Any]:
    def reject(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            require(key not in result, f"duplicate JSON key: {label}: {key}")
            result[key] = value
        return result

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=reject)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise StartApplyError(f"invalid JSON: {label}") from exc
    require(type(value) is dict, f"JSON root differs: {label}")
    return value


def strict_equal(left: Any, right: Any) -> bool:
    try:
        return continuation.canonical_json_bytes(left) == continuation.canonical_json_bytes(right)
    except (TypeError, ValueError):
        return False


def _parse_time(value: Any, *, label: str, second_precision: bool) -> datetime:
    require(isinstance(value, str), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise StartApplyError(f"{label} is not ISO-8601") from exc
    require(parsed.utcoffset() is not None, f"{label} lacks timezone")
    if second_precision:
        require(parsed.microsecond == 0, f"{label} must use second precision")
    return parsed


def _correction() -> Any:
    try:
        return importlib.import_module(CORRECTION_MODULE)
    except ImportError as exc:
        raise StartApplyError("seq99 correction authority is unavailable") from exc


def _gate() -> Any:
    try:
        return importlib.import_module(GATE_MODULE)
    except ImportError as exc:
        raise StartApplyError("R011 gate authority is unavailable") from exc


@contextmanager
def _historical_seq99_validation_phase(root: Path) -> Iterator[None]:
    """Validate the sealed seq99 bytes without replaying its old projection."""

    correction = _correction()
    original = getattr(
        correction, "require_start_gate_execution_corrected_checkpoint", None
    )
    require(callable(original), "seq99 correction validator API differs")

    def historical(
        root: Path,
        checkpoint: Mapping[str, Any],
        *,
        require_live_snapshot: bool = True,
        run_external_validators: bool = False,
    ) -> None:
        del require_live_snapshot
        raw = checkpoint_bytes(checkpoint)
        if (
            len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
            and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256
        ):
            _require_exact_start_projection_source(raw, checkpoint)
            return
        original(
            root,
            checkpoint,
            require_live_snapshot=False,
            run_external_validators=run_external_validators,
        )

    correction.require_start_gate_execution_corrected_checkpoint = historical
    try:
        yield
    finally:
        correction.require_start_gate_execution_corrected_checkpoint = original


def _canonical_seq99_authority_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    raw = checkpoint_bytes(checkpoint)
    if (
        len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256
    ):
        _require_exact_start_projection_source(raw, checkpoint)
        return raw
    correction = _correction()
    validator = getattr(
        correction, "require_start_gate_execution_corrected_checkpoint", None
    )
    canonical = getattr(correction, "canonical_seq99_checkpoint_bytes", None)
    require(callable(validator) and callable(canonical), "seq99 correction API differs")
    try:
        with _historical_seq99_validation_phase(root):
            validator(
                root,
                checkpoint,
                require_live_snapshot=False,
                run_external_validators=False,
            )
            raw = canonical(root, checkpoint)
    except Exception as exc:
        raise StartApplyError(f"seq99 correction physical authority differs: {exc}") from exc
    require(type(raw) is bytes, "seq99 correction canonical bytes differ")
    return raw


def _require_zero_credit(checkpoint: Mapping[str, Any]) -> None:
    approved = checkpoint.get("approved_state")
    verification = checkpoint.get("verification_boundary")
    require(
        isinstance(approved, dict)
        and type(approved.get("formal_test_count")) is int
        and type(approved.get("formal_test_not_run_count")) is int
        and type(approved.get("remaining_gate_count")) is int
        and approved.get("formal_test_count")
        == approved.get("formal_test_not_run_count")
        == 279
        and approved.get("remaining_gate_count") == 5
        and approved.get("remaining_gates_waived") is False
        and approved.get("release_status") == "NOT_ELIGIBLE",
        "formal/release zero-credit boundary differs",
    )
    require(
        isinstance(verification, dict)
        and verification.get("actual_device_test_status") == "NOT_RUN"
        and verification.get("all_remaining_gate_status") == "NOT_RUN"
        and verification.get("formal_test_pass_claimed") is False
        and verification.get("implementation_conformance_claimed") is False
        and verification.get("release_eligible") is False,
        "device/deployment/release zero-credit boundary differs",
    )


def _require_source_shape(source: Mapping[str, Any]) -> None:
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        source.get("schema_version") == "1.25.0"
        and isinstance(history, list)
        and len(history) == SOURCE_SEQUENCE,
        "source is not exact seq99",
    )
    tail = history[-1]
    statuses = state.get("status_by_goal")
    require(
        isinstance(tail, dict)
        and type(tail.get("sequence")) is int
        and tail.get("sequence") == SOURCE_SEQUENCE
        and tail.get("event_id") == SOURCE_EVENT_ID
        and tail.get("event_type") == "GOAL_START_GATE_EXECUTION_CORRECTED"
        and tail.get("subject_goal_id") == TARGET_GOAL_ID
        and tail.get("from_status") == tail.get("to_status") == "READY"
        and strict_equal(tail.get("status_changes"), {})
        and tail.get("event_sha256") == continuation.event_sha256(tail)
        and isinstance(statuses, dict)
        and statuses.get(TARGET_GOAL_ID) == "READY"
        and "IN_PROGRESS" not in statuses.values()
        and state.get("goal_status") == "READY"
        and state.get("focus_goal_id") == TARGET_GOAL_ID
        and state.get("pending_producer_completion_goal_id") in {None, ""},
        "seq99 R010 execution-failure correction authority differs",
    )
    current = source.get("current_work")
    require(
        isinstance(current, dict)
        and current.get("status") == "READY"
        and current.get("release_completion_claimed") is False,
        "seq99 current-work boundary differs",
    )
    _require_zero_credit(source)


def _require_exact_start_projection_source(
    raw: bytes,
    source: Mapping[str, Any],
) -> None:
    _require_source_shape(source)
    tail = source["goal_execution"]["transition_history"][-1]
    require(
        raw == checkpoint_bytes(source)
        and len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256
        and tail.get("event_sha256") == SOURCE_EVENT_SHA256,
        "exact seq99 start-projection source differs",
    )


def validate_gate_evidence(source: Mapping[str, Any], evidence: GateEvidence) -> None:
    receipt = evidence.receipt
    binding = evidence.receipt_binding
    tail = source["goal_execution"]["transition_history"][-1]
    require(
        isinstance(receipt, Mapping)
        and set(receipt) == RECEIPT_FIELDS
        and type(evidence.receipt_bytes) is bytes
        and evidence.receipt_bytes == gate_receipt_bytes(receipt)
        and receipt.get("schema_version") == "1.1"
        and receipt.get("evidence_type") == "IMPLEMENTATION_START_OR_RESUME_GATE"
        and receipt.get("gate_purpose") == "INITIAL_START"
        and receipt.get("status") == "PASS"
        and receipt.get("document_id") == GATE_DOCUMENT_ID
        and receipt.get("target_transition_event_id") == EVENT_ID
        and receipt.get("target_goal_id") == TARGET_GOAL_ID
        and receipt.get("source_activation_event_sha256") == tail["event_sha256"]
        and receipt.get("source_checkpoint_sha256") == sha256_bytes(checkpoint_bytes(source))
        and receipt.get("static_plan_manifest_sha256") == MANIFEST_SHA256
        and isinstance(receipt.get("repository_snapshot"), Mapping),
        "private R011 PASS receipt differs",
    )
    require(
        isinstance(binding, Mapping)
        and set(binding) == {"document_id", "path", "file_sha256"}
        and binding.get("document_id") == GATE_DOCUMENT_ID
        and binding.get("path") == GATE_RECEIPT_REL.as_posix()
        and binding.get("file_sha256") == sha256_bytes(evidence.receipt_bytes),
        "private R011 receipt binding differs",
    )
    window = receipt.get("execution_window")
    runs = receipt.get("check_runs")
    require(
        isinstance(window, Mapping)
        and set(window) == {"started_at", "ended_at"}
        and isinstance(runs, list)
        and len(runs) == len(EXPECTED_CHECK_IDS)
        and set(evidence.log_sha256_by_check_id) == set(EXPECTED_CHECK_IDS),
        "private R011 five-check receipt differs",
    )
    for expected_id, run in zip(EXPECTED_CHECK_IDS, runs, strict=True):
        require(
            isinstance(run, Mapping)
            and set(run)
            == {
                "check_id",
                "command",
                "output_path",
                "output_sha256",
                "exit_code",
                "executed_at",
            }
            and run.get("check_id") == expected_id
            and run.get("output_sha256")
            == evidence.log_sha256_by_check_id.get(expected_id)
            and type(run.get("exit_code")) is int
            and run.get("exit_code") == 0,
            f"private R011 {expected_id} log binding differs",
        )
    source_at = _parse_time(tail.get("occurred_at"), label="seq99 occurred_at", second_precision=True)
    started_at = _parse_time(window.get("started_at"), label="R011 started_at", second_precision=True)
    ended_at = _parse_time(window.get("ended_at"), label="R011 ended_at", second_precision=True)
    generated_at = _parse_time(receipt.get("generated_at"), label="R011 generated_at", second_precision=True)
    occurred_at = _parse_time(evidence.event_occurred_at, label="seq100 occurred_at", second_precision=True)
    require(
        source_at < started_at <= ended_at <= generated_at < occurred_at
        and occurred_at >= max(source_at, generated_at) + timedelta(seconds=1),
        "seq99/R011/seq100 chronology differs",
    )


def _binding_for_path(rows: Sequence[Mapping[str, Any]], path: Path) -> Mapping[str, Any]:
    matches = [row for row in rows if row.get("path") == path.as_posix()]
    require(len(matches) == 1, f"reviewed authority binding missing: {path}")
    return matches[0]


def _require_reviewed_authority_files(
    root: Path,
    source: Mapping[str, Any],
    gate: Any,
) -> tuple[Path, ...]:
    source_raw = checkpoint_bytes(source)
    _require_exact_start_projection_source(source_raw, source)
    correction = _correction()
    tail = source["goal_execution"]["transition_history"][-1]
    review_binding = tail.get("transition_control_review_binding")
    require(
        isinstance(review_binding, Mapping)
        and set(review_binding)
        == {"assignment", "review_result", "independent_review"},
        "seq99 frozen review binding differs",
    )

    def bound_private_json(
        binding: Any,
        expected_path: Path,
        label: str,
    ) -> tuple[bytes, dict[str, Any]]:
        require(
            isinstance(binding, Mapping)
            and set(binding) == {"path", "sha256", "byte_length"}
            and binding.get("path") == expected_path.as_posix(),
            f"{label} binding differs",
        )
        raw, value = _start_projection_private_json(root, expected_path)
        require(
            binding == _start_projection_binding(expected_path, raw),
            f"{label} bytes differ",
        )
        return raw, value

    authorization_path = getattr(correction, "AUTHORIZATION_REL", None)
    assignment_path = getattr(correction, "REVIEW_ASSIGNMENT_REL", None)
    result_path = getattr(correction, "REVIEW_RESULT_REL", None)
    independent_path = getattr(correction, "INDEPENDENT_REVIEW_REL", None)
    reviewed_paths = getattr(correction, "REVIEWED_CONTROL_PATHS", None)
    require(
        all(
            isinstance(path, Path)
            for path in (
                authorization_path,
                assignment_path,
                result_path,
                independent_path,
            )
        )
        and isinstance(reviewed_paths, tuple)
        and all(isinstance(path, Path) for path in reviewed_paths),
        "seq99 frozen review path API differs",
    )
    authorization_raw, _authorization = bound_private_json(
        tail.get("authorization_binding"), authorization_path, "seq99 authorization"
    )
    assignment_raw, assignment = bound_private_json(
        review_binding.get("assignment"), assignment_path, "seq99 assignment"
    )
    result_raw, result = bound_private_json(
        review_binding.get("review_result"), result_path, "seq99 primary review"
    )
    _independent_raw, independent = bound_private_json(
        review_binding.get("independent_review"),
        independent_path,
        "seq99 independent review",
    )
    require(
        assignment.get("authorization_binding")
        == _start_projection_binding(authorization_path, authorization_raw)
        and result.get("assignment_binding")
        == _start_projection_binding(assignment_path, assignment_raw)
        and independent.get("assignment_binding")
        == _start_projection_binding(assignment_path, assignment_raw)
        and independent.get("review_result_binding")
        == _start_projection_binding(result_path, result_raw)
        and result.get("decision") == independent.get("decision") == "APPROVED"
        and result.get("findings") == independent.get("findings")
        == {"P0": 0, "P1": 0, "P2": 0},
        "seq99 frozen review chain differs",
    )
    rows = assignment.get("reviewed_control_inputs")
    require(
        isinstance(rows, list)
        and len(rows) == len(reviewed_paths)
        and all(
            isinstance(row, Mapping)
            and set(row) == {"path", "sha256", "byte_length"}
            for row in rows
        )
        and [row["path"] for row in rows]
        == [path.as_posix() for path in reviewed_paths],
        "seq99 reviewed control bindings differ",
    )
    correction_path = getattr(correction, "SCRIPT_REL", None)
    required_paths = (
        correction_path,
        getattr(gate, "CONTRACT_RELATIVE", None),
        getattr(gate, "RUNNER_RELATIVE", None),
        SCRIPT_REL,
        TEST_REL,
    )
    require(all(isinstance(path, Path) for path in required_paths), "R011 authority path API differs")
    for path in required_paths:
        row = _binding_for_path(rows, path)
        frozen = FROZEN_R010_STARTER_BINDINGS.get(path)
        if frozen is not None:
            require(row == frozen, f"frozen R010 starter binding differs: {path}")
            if path == SCRIPT_REL:
                continue
        observed = seq90._stable_read(root, path)
        require(
            stat.S_ISREG(observed.identity.mode)
            and not stat.S_ISLNK(observed.identity.mode)
            and observed.identity.links == 1
            and row.get("sha256") == sha256_bytes(observed.raw)
            and type(row.get("byte_length")) is int
            and row.get("byte_length") == len(observed.raw),
            f"reviewed authority bytes differ: {path}",
        )
    return tuple(Path(row["path"]) for row in rows)


def _published_r011_gate_evidence(
    root: Path,
    source: Mapping[str, Any],
    source_bytes: bytes,
    *,
    event_occurred_at: str | None,
) -> GateEvidence:
    gate = _gate()
    require(
        getattr(gate, "STARTED_EVENT_ID", None) == EVENT_ID
        and getattr(gate, "EVENT_SEQUENCE", None) == EVENT_SEQUENCE
        and tuple(getattr(gate, "EXPECTED_CHECK_IDS", ())) == EXPECTED_CHECK_IDS,
        "R011 runner identity differs",
    )
    _require_exact_start_projection_source(source_bytes, source)
    history = source["goal_execution"]["transition_history"]
    ready_sequence = getattr(gate, "SOURCE_READY_SEQUENCE", None)
    require(
        type(ready_sequence) is int and 0 < ready_sequence <= len(history),
        "R011 READY sequence differs",
    )
    ready = history[ready_sequence - 1]
    correction_event = history[-1]
    require(
        isinstance(ready, Mapping)
        and ready.get("sequence") == ready_sequence
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == TARGET_GOAL_ID
        and ready.get("event_sha256") == continuation.event_sha256(ready)
        and correction_event.get("event_sha256") == SOURCE_EVENT_SHA256,
        "R011 frozen source event authority differs",
    )
    _require_reviewed_authority_files(root, source, gate)

    event_rel = gate.GATE_ROOT_RELATIVE / EVENT_ID
    event_path = root / event_rel
    try:
        event_metadata = event_path.lstat()
    except OSError as exc:
        raise StartApplyError("R011 evidence namespace is missing") from exc
    require(
        stat.S_ISDIR(event_metadata.st_mode)
        and not stat.S_ISLNK(event_metadata.st_mode)
        and stat.S_IMODE(event_metadata.st_mode) == 0o700
        and event_metadata.st_uid == os.geteuid(),
        "R011 evidence directory authority differs",
    )
    receipt_read = seq90._stable_read(root, GATE_RECEIPT_REL)
    require(
        stat.S_ISREG(receipt_read.identity.mode)
        and not stat.S_ISLNK(receipt_read.identity.mode)
        and stat.S_IMODE(receipt_read.identity.mode) == 0o600
        and receipt_read.identity.uid == os.geteuid()
        and receipt_read.identity.links == 1,
        "R011 receipt file authority differs",
    )
    require(
        len(receipt_read.raw) == R011_RECEIPT_BYTE_LENGTH
        and sha256_bytes(receipt_read.raw) == R011_RECEIPT_SHA256,
        "R011 frozen receipt binding differs",
    )
    receipt = strict_json(receipt_read.raw, "R011 receipt")
    window = receipt.get("execution_window")
    runs = receipt.get("check_runs")
    require(
        set(receipt) == RECEIPT_FIELDS
        and isinstance(window, dict)
        and set(window) == {"started_at", "ended_at"}
        and isinstance(runs, list)
        and len(runs) == len(EXPECTED_CHECK_IDS),
        "R011 receipt schema differs",
    )
    started_at = _parse_time(window.get("started_at"), label="R011 started_at", second_precision=True)
    ended_at = _parse_time(window.get("ended_at"), label="R011 ended_at", second_precision=True)
    generated_at = _parse_time(receipt.get("generated_at"), label="R011 generated_at", second_precision=True)
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1].get("occurred_at"),
        label="seq99 occurred_at",
        second_precision=True,
    )
    require(source_at < started_at <= ended_at <= generated_at, "R011 receipt chronology differs")

    expected_names = {gate.RECEIPT_NAME}
    expected_runs: list[dict[str, Any]] = []
    log_hashes: dict[str, str] = {}
    repository_snapshot: Mapping[str, Any] | None = None
    previous_executed_at: datetime | None = None
    for index, (check_id, run) in enumerate(zip(EXPECTED_CHECK_IDS, runs, strict=True), start=1):
        require(isinstance(run, dict), f"R011 check {index} schema differs")
        output_rel = event_rel / f"{index:02d}-{check_id}.log"
        output_read = seq90._stable_read(root, output_rel)
        require(
            stat.S_ISREG(output_read.identity.mode)
            and not stat.S_ISLNK(output_read.identity.mode)
            and stat.S_IMODE(output_read.identity.mode) == 0o600
            and output_read.identity.uid == os.geteuid()
            and output_read.identity.links == 1
            and 0 < len(output_read.raw) <= gate._impl.LOG_MAX_BYTES,
            f"R011 check {index} log authority differs",
        )
        executed_at = _parse_time(
            run.get("executed_at"), label=f"R011 check {index} executed_at", second_precision=False
        )
        output_sha256 = sha256_bytes(output_read.raw)
        require(
            set(run)
            == {
                "check_id",
                "command",
                "output_path",
                "output_sha256",
                "exit_code",
                "executed_at",
            }
            and run.get("check_id") == check_id
            and run.get("command") == gate.CONTRACT_COMMANDS[check_id]
            and run.get("output_path") == output_rel.as_posix()
            and run.get("output_sha256") == output_sha256
            and type(run.get("exit_code")) is int
            and run.get("exit_code") == 0
            and started_at <= executed_at <= ended_at
            and (previous_executed_at is None or previous_executed_at < executed_at),
            f"R011 check {index} authority differs",
        )
        previous_executed_at = executed_at
        expected_names.add(output_rel.name)
        log_hashes[check_id] = output_sha256
        expected_runs.append(copy.deepcopy(run))
        if check_id == "REPOSITORY_STATE":
            payload = strict_json(output_read.raw, "R011 REPOSITORY_STATE")
            require(
                output_read.raw == gate._impl.canonical_json_bytes(payload) + b"\n",
                "R011 REPOSITORY_STATE bytes differ",
            )
            repository_snapshot = gate._impl.repository_snapshot_from_payload(
                payload, event_id=EVENT_ID, output_sha256=output_sha256
            )
    require(
        {entry.name for entry in event_path.iterdir()} == expected_names
        and repository_snapshot is not None,
        "R011 evidence namespace membership differs",
    )
    expected_contract = getattr(gate, "expected_r011_binding", None)
    require(callable(expected_contract), "R011 contract binding API is missing")
    contract_binding = expected_contract()
    require(
        strict_equal(
            contract_binding,
            correction_event.get("contract_supersession", {}).get(
                "replacement_contract_binding"
            ),
        ),
        "R011 reviewed contract/runner binding differs",
    )
    expected_receipt = {
        "schema_version": "1.1",
        "document_id": GATE_DOCUMENT_ID,
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": gate.GATE_PURPOSE,
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": EVENT_ID,
        "target_goal_id": TARGET_GOAL_ID,
        "target_goal_content_sha256": TARGET_GOAL_SHA256,
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "source_activation_event_sha256": correction_event["event_sha256"],
        "source_checkpoint_sha256": sha256_bytes(source_bytes),
        "source_ready_event_sha256": ready["event_sha256"],
        "check_command_contract_version": gate.CONTRACT_VERSION,
        "check_command_contract_sha256": gate.CONTRACT_CANONICAL_SHA256,
        "implementation_start_gate_contract_binding": copy.deepcopy(contract_binding),
        "runtime_bindings": [],
        "execution_window": copy.deepcopy(window),
        "check_runs": expected_runs,
        "repository_snapshot": copy.deepcopy(repository_snapshot),
        "generated_at": receipt["generated_at"],
    }
    require(
        receipt_read.raw == gate_receipt_bytes(expected_receipt),
        "R011 receipt bytes differ from exact producer order",
    )
    evidence = GateEvidence(
        receipt=receipt,
        receipt_bytes=receipt_read.raw,
        receipt_binding={
            "document_id": GATE_DOCUMENT_ID,
            "path": GATE_RECEIPT_REL.as_posix(),
            "file_sha256": sha256_bytes(receipt_read.raw),
        },
        event_occurred_at=(
            event_occurred_at
            if event_occurred_at is not None
            else (max(source_at, generated_at) + timedelta(seconds=1)).isoformat()
        ),
        log_sha256_by_check_id=log_hashes,
    )
    validate_gate_evidence(source, evidence)
    return evidence


def require_published_r011_gate_for_seq99(
    root: Path,
    source: Mapping[str, Any],
    source_bytes: bytes,
    *,
    gate_loader: Callable[[Path, Mapping[str, Any], bytes], GateEvidence] | None = None,
    event_occurred_at: str | None = None,
) -> GateEvidence:
    """Load only the exact private R011 PASS namespace for seq99."""

    evidence = (
        _published_r011_gate_evidence(
            root, source, source_bytes, event_occurred_at=event_occurred_at
        )
        if gate_loader is None
        else gate_loader(root, source, source_bytes)
    )
    require(isinstance(evidence, GateEvidence), "R011 gate loader result differs")
    validate_gate_evidence(source, evidence)
    return evidence


def project_seq100(
    root: Path,
    source: Mapping[str, Any],
    evidence: GateEvidence,
    *,
    managed_paths: Sequence[str],
    snapshot_hashes: tuple[str, str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    del root
    _require_source_shape(source)
    validate_gate_evidence(source, evidence)
    require(
        len(snapshot_hashes) == 2
        and all(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) for value in snapshot_hashes),
        "snapshot hashes differ",
    )
    checkpoint = copy.deepcopy(dict(source))
    state = checkpoint["goal_execution"]
    control = state["transition_history"][-1]
    event: dict[str, Any] = {
        "sequence": EVENT_SEQUENCE,
        "event_id": EVENT_ID,
        "event_type": "GOAL_STARTED",
        "occurred_on": datetime.fromisoformat(evidence.event_occurred_at).date().isoformat(),
        "occurred_at": evidence.event_occurred_at,
        "previous_focus_goal_id": TARGET_GOAL_ID,
        "previous_focus_content_sha256": TARGET_GOAL_SHA256,
        "focus_goal_id": TARGET_GOAL_ID,
        "focus_goal_content_sha256": TARGET_GOAL_SHA256,
        "subject_goal_id": TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {TARGET_GOAL_ID: "IN_PROGRESS"},
        "runtime_after": copy.deepcopy(control["runtime_after"]),
        "repository_snapshot_before": copy.deepcopy(evidence.receipt["repository_snapshot"]),
        "implementation_start_gate_binding": copy.deepcopy(dict(evidence.receipt_binding)),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [],
        "previous_event_sha256": control["event_sha256"],
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq100 GOAL_STARTED field set differs")
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = event["occurred_at"]
    state["status_by_goal"][TARGET_GOAL_ID] = "IN_PROGRESS"
    state["goal_status"] = "IN_PROGRESS"

    current = checkpoint["current_work"]
    current.update(
        {
            "status": "IN_PROGRESS",
            "current_focus": STARTED_CURRENT_FOCUS,
            "next_action": STARTED_NEXT_ACTION,
            "release_completion_claimed": False,
        }
    )
    paths = sorted(set(managed_paths) | {SCRIPT_REL.as_posix(), TEST_REL.as_posix()})
    require(paths and all(isinstance(path, str) and path for path in paths), "managed paths differ")
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot.update(
        {
            "scope": STARTED_SCOPE,
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": snapshot_hashes[0],
            "content_set_sha256": snapshot_hashes[1],
        }
    )
    handoff = checkpoint["session_handoff"]
    handoff.update(
        {
            "changed_files": copy.deepcopy(paths),
            "current_epic": STARTED_HANDOFF,
            "last_updated_by_work_item": WORK_ITEM_ID,
            "last_verification_status": STARTED_VERIFICATION,
            "next_single_action": STARTED_NEXT_ACTION,
        }
    )
    mirror = handoff["source_commit_or_snapshot"]
    mirror.update(
        {
            "file_count": len(paths),
            "path_set_sha256": snapshot_hashes[0],
            "content_set_sha256": snapshot_hashes[1],
        }
    )
    require(list(state["status_by_goal"].values()).count("IN_PROGRESS") == 1, "seq100 must have one IN_PROGRESS goal")
    require(state["focus_goal_id"] == TARGET_GOAL_ID, "seq100 focus changed")
    _require_zero_credit(checkpoint)
    return checkpoint, event


def validate_projection(
    root: Path,
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    evidence: GateEvidence,
) -> None:
    snapshot = projected.get("working_tree_snapshot")
    require(isinstance(snapshot, dict), "projected snapshot differs")
    expected, _event = project_seq100(
        root,
        source,
        evidence,
        managed_paths=snapshot.get("managed_changed_paths", []),
        snapshot_hashes=(snapshot.get("path_set_sha256"), snapshot.get("content_set_sha256")),
    )
    require(strict_equal(projected, expected), "seq100 projection differs")


def _publication_only_paths() -> tuple[Path, ...]:
    return (
        SCRIPT_REL,
        TEST_REL,
        START_PROJECTION_TEST_REL,
        START_PROJECTION_AUTHORIZATION_REL,
        *START_PROJECTION_REVIEW_PATHS,
    )


def _source_path_preimage(projected_paths: list[str], seal: Mapping[str, Any]) -> list[str]:
    additions = tuple(path.as_posix() for path in _publication_only_paths())
    candidates: list[list[str]] = []
    for mask in range(1 << len(additions)):
        removed = {path for index, path in enumerate(additions) if mask & (1 << index)}
        candidate = [path for path in projected_paths if path not in removed]
        digest = hashlib.sha256(("\n".join(candidate) + "\n").encode("utf-8")).hexdigest()
        if (
            len(candidate) == seal.get("managed_changed_path_count")
            and digest == seal.get("path_set_sha256")
            and candidate not in candidates
        ):
            candidates.append(candidate)
    require(len(candidates) == 1, "seq99 managed path preimage is ambiguous")
    return candidates[0]


def _restored_seq99_checkpoint(root: Path, checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    del root
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == EVENT_SEQUENCE,
        "seq99 inverse requires exact seq100",
    )
    event = history[-1]
    control = history[-2]
    require(
        isinstance(event, dict)
        and isinstance(control, dict)
        and set(event) == EVENT_FIELDS
        and type(event.get("sequence")) is int
        and event.get("sequence") == EVENT_SEQUENCE
        and event.get("event_id") == EVENT_ID
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("from_status") == "READY"
        and event.get("to_status") == "IN_PROGRESS"
        and strict_equal(event.get("status_changes"), {TARGET_GOAL_ID: "IN_PROGRESS"})
        and event.get("previous_event_sha256") == control.get("event_sha256")
        and event.get("event_sha256") == continuation.event_sha256(event),
        "seq100 inverse event differs",
    )
    reanchor = control.get("repository_context_reanchor")
    after = reanchor.get("after") if isinstance(reanchor, dict) else None
    require(
        isinstance(after, dict)
        and type(after.get("managed_changed_path_count")) is int
        and after["managed_changed_path_count"] > 0
        and isinstance(after.get("path_set_sha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", after["path_set_sha256"])
        and isinstance(after.get("content_set_sha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", after["content_set_sha256"]),
        "seq99 correction snapshot seal differs",
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    projected_paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    require(
        isinstance(projected_paths, list)
        and projected_paths == sorted(set(projected_paths))
        and all(isinstance(path, str) and path for path in projected_paths),
        "seq100 managed path inventory differs",
    )
    paths = _source_path_preimage(projected_paths, after)
    correction = _correction()
    for name in (
        "CURRENT_FOCUS",
        "NEXT_ACTION",
        "SCOPE",
        "WORK_ITEM_ID",
        "HANDOFF_CURRENT_EPIC",
        "HANDOFF_VERIFICATION_STATUS",
    ):
        require(hasattr(correction, name), f"seq99 inverse authority is missing: {name}")
    restored = copy.deepcopy(dict(checkpoint))
    restored_state = restored["goal_execution"]
    restored_state["transition_history"] = copy.deepcopy(history[:SOURCE_SEQUENCE])
    restored_state["transition_history_anchor_sha256"] = control["event_sha256"]
    restored_state["validation_cutoff_at"] = control["occurred_at"]
    restored_state["status_by_goal"][TARGET_GOAL_ID] = "READY"
    restored_state["goal_status"] = "READY"
    restored["current_work"].update(
        {
            "status": "READY",
            "current_focus": correction.CURRENT_FOCUS,
            "next_action": correction.NEXT_ACTION,
            "release_completion_claimed": False,
        }
    )
    restored["working_tree_snapshot"].update(
        {
            "scope": correction.SCOPE,
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": after["path_set_sha256"],
            "content_set_sha256": after["content_set_sha256"],
        }
    )
    handoff = restored["session_handoff"]
    handoff.update(
        {
            "changed_files": copy.deepcopy(paths),
            "current_epic": correction.HANDOFF_CURRENT_EPIC,
            "last_updated_by_work_item": correction.WORK_ITEM_ID,
            "last_verification_status": correction.HANDOFF_VERIFICATION_STATUS,
            "next_single_action": correction.NEXT_ACTION,
        }
    )
    handoff["source_commit_or_snapshot"].update(
        {
            "file_count": len(paths),
            "path_set_sha256": after["path_set_sha256"],
            "content_set_sha256": after["content_set_sha256"],
        }
    )
    return restored


def reconstructed_seq99_checkpoint_bytes(root: Path, checkpoint: Mapping[str, Any]) -> bytes:
    root = root.resolve(strict=True)
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(isinstance(history, list), "seq99 inverse history differs")
    candidate = dict(checkpoint) if len(history) == SOURCE_SEQUENCE else _restored_seq99_checkpoint(root, checkpoint)
    raw = checkpoint_bytes(candidate)
    require(
        _canonical_seq99_authority_bytes(root, candidate) == raw,
        "seq99 reconstructed checkpoint differs",
    )
    if len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH:
        _require_exact_start_projection_source(raw, candidate)
    return raw


def start_projection_reconstructed_seq99_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    raw = reconstructed_seq99_checkpoint_bytes(root, checkpoint)
    source = strict_json(raw, "start-projection reconstructed seq99")
    _require_exact_start_projection_source(raw, source)
    return raw


def require_started_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
    run_external_validators: bool = False,
) -> None:
    root = root.resolve(strict=True)
    source_raw = reconstructed_seq99_checkpoint_bytes(root, checkpoint)
    source = strict_json(source_raw, "reconstructed seq99 checkpoint")
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(
        isinstance(history, list) and len(history) == EVENT_SEQUENCE,
        "checkpoint is not exact seq100 GOAL_STARTED",
    )
    event = history[-1]
    require(isinstance(event, dict), "seq100 GOAL_STARTED event differs")
    evidence = require_published_r011_gate_for_seq99(
        root, source, source_raw, event_occurred_at=event.get("occurred_at")
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    require(isinstance(snapshot, dict), "seq100 working snapshot differs")
    expected, expected_event = project_seq100(
        root,
        source,
        evidence,
        managed_paths=snapshot.get("managed_changed_paths", []),
        snapshot_hashes=(snapshot.get("path_set_sha256"), snapshot.get("content_set_sha256")),
    )
    require(
        checkpoint_bytes(checkpoint) == checkpoint_bytes(expected)
        and strict_equal(event, expected_event),
        "seq100 started checkpoint projection differs",
    )
    paths = snapshot.get("managed_changed_paths")
    if require_live_snapshot:
        require(
            continuation.working_snapshot_hashes(root, paths)
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "seq100 live working snapshot differs",
        )
    if run_external_validators:
        live_raw = (root / CHECKPOINT_REL).read_bytes()
        require(live_raw == checkpoint_bytes(checkpoint), "live seq100 checkpoint differs")
        errors = continuation.validate(root, CHECKPOINT_REL)
        goal_graph = importlib.import_module("scripts.check_walksafe_goal_graph_v2_4")
        errors.extend(
            f"goal graph: {error}"
            for error in goal_graph.validate(root, CHECKPOINT_REL, check_continuation=False)
        )
        require(not errors, "seq100 external validators failed: " + "; ".join(errors))


def canonical_seq100_checkpoint_bytes(root: Path, checkpoint: Mapping[str, Any]) -> bytes:
    require_started_checkpoint(
        root, checkpoint, require_live_snapshot=False, run_external_validators=False
    )
    return checkpoint_bytes(checkpoint)


def goal_start_gate_receipt_binding(root: Path, checkpoint: Mapping[str, Any]) -> dict[str, str]:
    require_started_checkpoint(
        root, checkpoint, require_live_snapshot=False, run_external_validators=False
    )
    binding = checkpoint["goal_execution"]["transition_history"][-1].get(
        "implementation_start_gate_binding"
    )
    require(
        isinstance(binding, dict)
        and set(binding) == {"document_id", "path", "file_sha256"},
        "seq100 R011 receipt binding differs",
    )
    return copy.deepcopy(binding)


def _start_projection_binding(path: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
    }


def _start_projection_private_json(
    root: Path,
    path: Path,
) -> tuple[bytes, dict[str, Any]]:
    read = seq90._stable_read(root, path)
    info = (root / path).lstat()
    require(
        stat.S_ISREG(info.st_mode)
        and not stat.S_ISLNK(info.st_mode)
        and stat.S_IMODE(info.st_mode) == 0o600
        and info.st_uid == os.geteuid()
        and info.st_nlink == 1,
        f"private start-projection authority differs: {path}",
    )
    value = strict_json(read.raw, path.as_posix())
    require(
        read.raw == seq90.canonical_json_bytes(value),
        f"private start-projection canonical bytes differ: {path}",
    )
    return read.raw, value


def _start_projection_observed_control_binding(
    root: Path,
    path: Path,
) -> dict[str, Any]:
    read = seq90._stable_read(root, path)
    info = (root / path).lstat()
    require(
        stat.S_ISREG(info.st_mode)
        and not stat.S_ISLNK(info.st_mode)
        and info.st_uid == os.geteuid()
        and info.st_nlink == 1,
        f"start-projection reviewed control authority differs: {path}",
    )
    return {
        **_start_projection_binding(path, read.raw),
        "mode": stat.S_IMODE(info.st_mode),
        "uid": info.st_uid,
        "nlink": info.st_nlink,
    }


def _start_projection_reviewed_control_bindings(root: Path) -> list[dict[str, Any]]:
    return [
        _start_projection_observed_control_binding(root, path)
        for path in START_PROJECTION_REVIEWED_CONTROL_PATHS
    ]


def _start_projection_source_binding(raw: bytes) -> dict[str, Any]:
    try:
        source = strict_json(raw, "start-projection source binding")
        history = source.get("goal_execution", {}).get("transition_history")
        tail = history[-1] if isinstance(history, list) and history else {}
    except StartApplyError:
        history = []
        tail = {}
    return {
        "path": CHECKPOINT_REL.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
        "history_length": len(history),
        "tail_event_id": tail.get("event_id"),
        "tail_event_sha256": tail.get("event_sha256"),
    }


def _start_projection_frozen_r010_starter_bindings(root: Path) -> list[dict[str, Any]]:
    correction = _correction()
    assignment_path = getattr(correction, "REVIEW_ASSIGNMENT_REL", None)
    require(isinstance(assignment_path, Path), "R010 assignment path API differs")
    _raw, assignment = _start_projection_private_json(root, assignment_path)
    rows = assignment.get("reviewed_control_inputs")
    require(isinstance(rows, list), "R010 reviewed-control rows differ")
    result: list[dict[str, Any]] = []
    for path in (SCRIPT_REL, TEST_REL):
        matches = [row for row in rows if isinstance(row, dict) and row.get("path") == path.as_posix()]
        expected = FROZEN_R010_STARTER_BINDINGS[path]
        require(len(matches) == 1 and matches[0] == expected, f"frozen R010 starter row differs: {path}")
        result.append(copy.deepcopy(expected))
    return result


def _start_projection_r011_binding(
    root: Path,
    source: Mapping[str, Any],
    source_raw: bytes,
) -> dict[str, Any]:
    correction = _correction()
    scope = getattr(correction, "seq98_source_validation_call_scope", None)
    require(callable(scope), "seq99 validation call-scope API differs")
    with scope():
        evidence = require_published_r011_gate_for_seq99(root, source, source_raw)
    receipt_read = seq90._stable_read(root, GATE_RECEIPT_REL)
    receipt_info = (root / GATE_RECEIPT_REL).lstat()
    log_rows: list[dict[str, Any]] = []
    for expected_id, run in zip(
        EXPECTED_CHECK_IDS,
        evidence.receipt["check_runs"],
        strict=True,
    ):
        path = Path(run["output_path"])
        read = seq90._stable_read(root, path)
        info = (root / path).lstat()
        require(
            run.get("check_id") == expected_id
            and run.get("output_sha256") == sha256_bytes(read.raw)
            and stat.S_ISREG(info.st_mode)
            and stat.S_IMODE(info.st_mode) == 0o600
            and info.st_uid == os.geteuid()
            and info.st_nlink == 1,
            f"R011 retained log binding differs: {expected_id}",
        )
        log_rows.append(
            {
                **_start_projection_binding(path, read.raw),
                "check_id": expected_id,
                "mode": stat.S_IMODE(info.st_mode),
                "uid": info.st_uid,
                "nlink": info.st_nlink,
            }
        )
    return {
        "receipt": {
            **_start_projection_binding(GATE_RECEIPT_REL, receipt_read.raw),
            "document_id": GATE_DOCUMENT_ID,
            "mode": stat.S_IMODE(receipt_info.st_mode),
            "uid": receipt_info.st_uid,
            "nlink": receipt_info.st_nlink,
        },
        "contract": copy.deepcopy(
            evidence.receipt["implementation_start_gate_contract_binding"]
        ),
        "logs": log_rows,
        "generated_at": evidence.receipt["generated_at"],
        "event_occurred_at": evidence.event_occurred_at,
    }


def _start_projection_candidate() -> dict[str, Any]:
    return {
        "sequence": EVENT_SEQUENCE,
        "event_id": EVENT_ID,
        "event_type": "GOAL_STARTED",
        "transition": "READY_TO_IN_PROGRESS_AFTER_EXACT_R011_PASS",
        "reason": "MANAGED_RETAINED_BOUNDARY_CORRECTION",
    }


def build_start_projection_authorization(
    root: Path,
    source: Mapping[str, Any],
    *,
    authorized_at: str | None = None,
) -> bytes:
    source_raw = checkpoint_bytes(source)
    _require_source_shape(source)
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1].get("occurred_at"),
        label="seq99 occurred_at",
        second_precision=True,
    )
    authorization_time = _parse_time(
        authorized_at
        if authorized_at is not None
        else datetime.now().astimezone().replace(microsecond=0).isoformat(),
        label="start-projection authorized_at",
        second_precision=True,
    )
    require(source_at < authorization_time, "start-projection authorization chronology differs")
    roles = {
        (START_PROJECTION_PRODUCER["id"], START_PROJECTION_PRODUCER["task_id"]),
        (
            START_PROJECTION_PRIMARY_REVIEWER["id"],
            START_PROJECTION_PRIMARY_REVIEWER["task_id"],
        ),
        (
            START_PROJECTION_INDEPENDENT_REVIEWER["id"],
            START_PROJECTION_INDEPENDENT_REVIEWER["task_id"],
        ),
    }
    require(len(roles) == 3, "start-projection review roles are not distinct")
    exact_source = (
        len(source_raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(source_raw) == SOURCE_CHECKPOINT_SHA256
    )
    r011_binding = (
        _start_projection_r011_binding(root, source, source_raw)
        if exact_source
        else {
            "receipt": {
                "path": GATE_RECEIPT_REL.as_posix(),
                "sha256": R011_RECEIPT_SHA256,
                "byte_length": R011_RECEIPT_BYTE_LENGTH,
                "document_id": GATE_DOCUMENT_ID,
            },
            "generated_at": (source_at + timedelta(seconds=1)).isoformat(),
            "event_occurred_at": (source_at + timedelta(seconds=2)).isoformat(),
            "contract": {},
            "logs": [],
        }
    )
    r011_generated_at = _parse_time(
        r011_binding.get("generated_at"),
        label="R011 generated_at",
        second_precision=True,
    )
    require(
        r011_generated_at < authorization_time,
        "R011/start-projection authorization chronology differs",
    )
    if exact_source:
        require(
            _parse_time(
                OLD_START_PROJECTION_PREFLIGHT_FAILURE["observed_at_estimate"],
                label="old preflight observed_at estimate",
                second_precision=True,
            )
            < authorization_time,
            "failed-preflight/start-projection authorization chronology differs",
        )
    value = {
        "schema_version": "1.0",
        "document_id": START_PROJECTION_AUTHORIZATION_DOCUMENT_ID,
        "evidence_type": "USER_CONTINUATION_AUTHORIZATION",
        "round_id": START_PROJECTION_REVIEW_ROUND_ID,
        "authorized_at": authorization_time.isoformat(),
        "authorization_status": "AUTHORIZED_FOR_SEQ100_START_PROJECTION_RECOVERY_R001",
        "source_checkpoint_binding": _start_projection_source_binding(source_raw),
        "r011_gate_binding": r011_binding,
        "failed_preflight_observation": copy.deepcopy(
            OLD_START_PROJECTION_PREFLIGHT_FAILURE
        ),
        "frozen_r010_starter_bindings": (
            _start_projection_frozen_r010_starter_bindings(root)
        ),
        "candidate_reviewed_control_inputs": (
            _start_projection_reviewed_control_bindings(root)
        ),
        "projected_transition": _start_projection_candidate(),
        "authorized_producer": copy.deepcopy(START_PROJECTION_PRODUCER),
        "required_primary_reviewer": copy.deepcopy(
            START_PROJECTION_PRIMARY_REVIEWER
        ),
        "required_independent_reviewer": copy.deepcopy(
            START_PROJECTION_INDEPENDENT_REVIEWER
        ),
        "claim_boundary": {
            "formal_test_credit_delta": 0,
            "actual_device_test_credit_delta": 0,
            "external_review_credit_delta": 0,
            "deployment_credit_delta": 0,
            "implementation_completion_credit_delta": 0,
            "release_credit_delta": 0,
        },
    }
    return seq90.canonical_json_bytes(value)


def build_start_projection_review_assignment(
    root: Path,
    source: Mapping[str, Any],
    authorization_raw: bytes | None = None,
    *,
    assigned_at: str | None = None,
) -> bytes:
    source_raw = checkpoint_bytes(source)
    if authorization_raw is None:
        authorization_raw = build_start_projection_authorization(root, source)
    authorization_value = strict_json(
        authorization_raw, "start-projection authorization"
    )
    require(
        authorization_raw == seq90.canonical_json_bytes(authorization_value)
        and authorization_value.get("document_id")
        == START_PROJECTION_AUTHORIZATION_DOCUMENT_ID,
        "start-projection authorization bytes differ",
    )
    authorization_at = _parse_time(
        authorization_value.get("authorized_at"),
        label="start-projection authorized_at",
        second_precision=True,
    )
    assignment_time = _parse_time(
        assigned_at
        if assigned_at is not None
        else (authorization_at + timedelta(seconds=1)).isoformat(),
        label="start-projection assigned_at",
        second_precision=True,
    )
    require(authorization_at < assignment_time, "start-projection assignment chronology differs")
    value = {
        "schema_version": "1.0",
        "document_id": START_PROJECTION_REVIEW_ASSIGNMENT_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_REVIEW_ASSIGNMENT",
        "round_id": START_PROJECTION_REVIEW_ROUND_ID,
        "candidate_status": "FINAL_REVIEW_CANDIDATE",
        "assigned_at": assignment_time.isoformat(),
        "authorization_binding": _start_projection_binding(
            START_PROJECTION_AUTHORIZATION_REL, authorization_raw
        ),
        "source_checkpoint_binding": _start_projection_source_binding(source_raw),
        "r011_gate_binding": copy.deepcopy(
            authorization_value["r011_gate_binding"]
        ),
        "failed_preflight_observation": copy.deepcopy(
            OLD_START_PROJECTION_PREFLIGHT_FAILURE
        ),
        "frozen_r010_starter_bindings": (
            _start_projection_frozen_r010_starter_bindings(root)
        ),
        "reviewed_control_inputs": _start_projection_reviewed_control_bindings(root),
        "projected_transition": _start_projection_candidate(),
        "producer": copy.deepcopy(START_PROJECTION_PRODUCER),
        "required_primary_reviewer": copy.deepcopy(
            START_PROJECTION_PRIMARY_REVIEWER
        ),
        "required_independent_reviewer": copy.deepcopy(
            START_PROJECTION_INDEPENDENT_REVIEWER
        ),
        "required_findings": {"P0": 0, "P1": 0, "P2": 0},
    }
    return seq90.canonical_json_bytes(value)


def build_start_projection_review_result(
    assignment_raw: bytes,
    *,
    reviewed_at: str,
) -> bytes:
    assignment = strict_json(assignment_raw, "start-projection review assignment")
    require(
        assignment_raw == seq90.canonical_json_bytes(assignment)
        and assignment.get("document_id")
        == START_PROJECTION_REVIEW_ASSIGNMENT_DOCUMENT_ID,
        "start-projection assignment bytes differ",
    )
    lower = _parse_time(
        assignment.get("assigned_at"),
        label="start-projection assigned_at",
        second_precision=True,
    )
    reviewed = _parse_time(
        reviewed_at,
        label="primary reviewed_at",
        second_precision=True,
    )
    require(lower < reviewed, "start-projection primary chronology differs")
    value = {
        "schema_version": "1.0",
        "document_id": START_PROJECTION_REVIEW_RESULT_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_PRIMARY_REVIEW",
        "round_id": START_PROJECTION_REVIEW_ROUND_ID,
        "assignment_binding": _start_projection_binding(
            START_PROJECTION_REVIEW_ASSIGNMENT_REL, assignment_raw
        ),
        "reviewer": copy.deepcopy(START_PROJECTION_PRIMARY_REVIEWER),
        "decision": "APPROVED",
        "findings": {"P0": 0, "P1": 0, "P2": 0},
        "external_independence_claimed": False,
        "reviewed_at": reviewed_at,
    }
    return seq90.canonical_json_bytes(value)


def build_start_projection_independent_review(
    assignment_raw: bytes,
    review_result_raw: bytes,
    *,
    reviewed_at: str,
) -> bytes:
    result = strict_json(review_result_raw, "start-projection primary review")
    require(
        review_result_raw == seq90.canonical_json_bytes(result)
        and review_result_raw
        == build_start_projection_review_result(
            assignment_raw,
            reviewed_at=result.get("reviewed_at"),
        ),
        "start-projection primary review differs",
    )
    primary_at = _parse_time(
        result.get("reviewed_at"),
        label="primary reviewed_at",
        second_precision=True,
    )
    independent_at = _parse_time(
        reviewed_at,
        label="independent reviewed_at",
        second_precision=True,
    )
    require(primary_at < independent_at, "start-projection independent chronology differs")
    value = {
        "schema_version": "1.0",
        "document_id": START_PROJECTION_INDEPENDENT_REVIEW_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_INDEPENDENT_REVIEW",
        "round_id": START_PROJECTION_REVIEW_ROUND_ID,
        "assignment_binding": _start_projection_binding(
            START_PROJECTION_REVIEW_ASSIGNMENT_REL, assignment_raw
        ),
        "review_result_binding": _start_projection_binding(
            START_PROJECTION_REVIEW_RESULT_REL, review_result_raw
        ),
        "reviewer": copy.deepcopy(START_PROJECTION_INDEPENDENT_REVIEWER),
        "decision": "APPROVED",
        "findings": {"P0": 0, "P1": 0, "P2": 0},
        "external_independence_claimed": False,
        "reviewed_at": reviewed_at,
    }
    return seq90.canonical_json_bytes(value)


def _load_start_projection_review_chain(
    root: Path,
    source: Mapping[str, Any],
) -> tuple[dict[str, Any], datetime, datetime]:
    authorization_raw, _authorization = _start_projection_private_json(
        root, START_PROJECTION_AUTHORIZATION_REL
    )
    assignment_raw, assignment = _start_projection_private_json(
        root, START_PROJECTION_REVIEW_ASSIGNMENT_REL
    )
    result_raw, result = _start_projection_private_json(
        root, START_PROJECTION_REVIEW_RESULT_REL
    )
    independent_raw, independent = _start_projection_private_json(
        root, START_PROJECTION_INDEPENDENT_REVIEW_REL
    )
    require(
        authorization_raw
        == build_start_projection_authorization(
            root,
            source,
            authorized_at=_authorization.get("authorized_at"),
        )
        and assignment_raw
        == build_start_projection_review_assignment(
            root,
            source,
            authorization_raw,
            assigned_at=assignment.get("assigned_at"),
        )
        and assignment.get("authorization_binding")
        == _start_projection_binding(
            START_PROJECTION_AUTHORIZATION_REL, authorization_raw
        ),
        "start-projection authorization or assignment differs",
    )
    require(
        result_raw
        == build_start_projection_review_result(
            assignment_raw,
            reviewed_at=result.get("reviewed_at"),
        )
        and independent_raw
        == build_start_projection_independent_review(
            assignment_raw,
            result_raw,
            reviewed_at=independent.get("reviewed_at"),
        ),
        "start-projection review decision chain differs",
    )
    result_at = _parse_time(
        result.get("reviewed_at"),
        label="primary reviewed_at",
        second_precision=True,
    )
    independent_at = _parse_time(
        independent.get("reviewed_at"),
        label="independent reviewed_at",
        second_precision=True,
    )
    return (
        {
            "assignment": _start_projection_binding(
                START_PROJECTION_REVIEW_ASSIGNMENT_REL, assignment_raw
            ),
            "review_result": _start_projection_binding(
                START_PROJECTION_REVIEW_RESULT_REL, result_raw
            ),
            "independent_review": _start_projection_binding(
                START_PROJECTION_INDEPENDENT_REVIEW_REL, independent_raw
            ),
        },
        result_at,
        independent_at,
    )


def prepare_start_projection_review_inputs(
    root: Path = ROOT,
) -> dict[Path, bytes]:
    root = root.resolve(strict=True)
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    source_info = (root / CHECKPOINT_REL).lstat()
    require(
        stat.S_ISREG(source_info.st_mode)
        and not stat.S_ISLNK(source_info.st_mode)
        and stat.S_IMODE(source_info.st_mode) == 0o600
        and source_info.st_uid == os.geteuid()
        and source_info.st_nlink == 1,
        "seq99 start-projection source physical authority differs",
    )
    source = strict_json(source_read.raw, "seq99 checkpoint")
    _require_exact_start_projection_source(source_read.raw, source)
    require(
        not os.path.lexists(root / START_PROJECTION_REVIEW_RESULT_REL)
        and not os.path.lexists(root / START_PROJECTION_INDEPENDENT_REVIEW_REL),
        "start-projection review decision paths are already consumed",
    )
    if os.path.lexists(root / START_PROJECTION_AUTHORIZATION_REL):
        authorization_raw, authorization_value = _start_projection_private_json(
            root, START_PROJECTION_AUTHORIZATION_REL
        )
        authorization_at = authorization_value.get("authorized_at")
    else:
        authorization_at = datetime.now().astimezone().replace(microsecond=0).isoformat()
        authorization_raw = build_start_projection_authorization(
            root,
            source,
            authorized_at=authorization_at,
        )
    if os.path.lexists(root / START_PROJECTION_REVIEW_ASSIGNMENT_REL):
        _assignment_raw, assignment_value = _start_projection_private_json(
            root, START_PROJECTION_REVIEW_ASSIGNMENT_REL
        )
        assigned_at = assignment_value.get("assigned_at")
    else:
        assigned_at = (
            _parse_time(
                authorization_at,
                label="start-projection authorized_at",
                second_precision=True,
            )
            + timedelta(seconds=1)
        ).isoformat()
    authorization = build_start_projection_authorization(
        root,
        source,
        authorized_at=authorization_at,
    )
    require(
        authorization == authorization_raw,
        "existing start-projection authorization differs",
    )
    assignment = build_start_projection_review_assignment(
        root,
        source,
        authorization,
        assigned_at=assigned_at,
    )
    return {
        START_PROJECTION_AUTHORIZATION_REL: authorization,
        START_PROJECTION_REVIEW_ASSIGNMENT_REL: assignment,
    }


def _start_projection_fsync_directory(path: Path) -> None:
    descriptor = os.open(
        path,
        os.O_RDONLY
        | os.O_DIRECTORY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _start_projection_require_public_parent(root: Path, relative: Path) -> None:
    cursor = root
    for part in relative.parts:
        cursor /= part
        info = cursor.lstat()
        require(
            stat.S_ISDIR(info.st_mode)
            and not stat.S_ISLNK(info.st_mode)
            and info.st_uid == os.geteuid()
            and stat.S_IMODE(info.st_mode) in {0o755, 0o775},
            f"unsafe start-projection parent: {relative}",
        )


def _start_projection_ensure_review_directories(root: Path) -> None:
    for relative in (
        TRANSITION_ROOT,
        TRANSITION_ROOT / "review-rounds",
        START_PROJECTION_REVIEW_ROOT,
    ):
        target = root / relative
        if not os.path.lexists(target):
            target.mkdir(mode=0o755)
            _start_projection_fsync_directory(target.parent)
        _start_projection_require_public_parent(root, relative)


def _start_projection_existing_add_only(
    root: Path,
    path: Path,
    raw: bytes,
) -> bool:
    if not os.path.lexists(root / path):
        return False
    try:
        observed, _value = _start_projection_private_json(root, path)
    except StartApplyError:
        raise
    except Exception as exc:
        raise StartApplyError(
            f"start-projection add-only authority differs: {path}"
        ) from exc
    require(observed == raw, f"start-projection add-only collision differs: {path}")
    return True


def _start_projection_write_add_only(root: Path, path: Path, raw: bytes) -> None:
    if _start_projection_existing_add_only(root, path, raw):
        return
    descriptor = os.open(
        root / path,
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
        0o600,
    )
    try:
        offset = 0
        while offset < len(raw):
            written = os.write(descriptor, raw[offset:])
            require(written > 0, f"start-projection add-only short write: {path}")
            offset += written
        os.fsync(descriptor)
        info = os.fstat(descriptor)
        require(
            stat.S_ISREG(info.st_mode)
            and stat.S_IMODE(info.st_mode) == 0o600
            and info.st_uid == os.geteuid()
            and info.st_nlink == 1,
            f"start-projection add-only authority differs: {path}",
        )
    finally:
        os.close(descriptor)
    _start_projection_fsync_directory((root / path).parent)


def _write_start_projection_add_only(root: Path, path: Path, raw: bytes) -> None:
    """Testable add-only primitive; production creates the same safe parents first."""

    root = root.resolve(strict=True)
    parent = (root / path).parent
    if not parent.exists():
        parent.mkdir(mode=0o755, parents=True)
    _start_projection_write_add_only(root, path, raw)


def write_start_projection_review_inputs(
    root: Path,
    outputs: Mapping[Path, bytes],
) -> None:
    root = root.resolve(strict=True)
    require(
        set(outputs)
        == {
            START_PROJECTION_AUTHORIZATION_REL,
            START_PROJECTION_REVIEW_ASSIGNMENT_REL,
        },
        "prepare-review may write only start-projection authorization and assignment",
    )
    _start_projection_ensure_review_directories(root)
    for path, raw in outputs.items():
        _start_projection_write_add_only(root, path, raw)


def _publication_retained_paths(root: Path, source: Mapping[str, Any]) -> tuple[Path, ...]:
    gate = _gate()
    reviewed_paths = _require_reviewed_authority_files(root, source, gate)
    correction = _correction()
    paths: list[Path] = [SCRIPT_REL, TEST_REL, *reviewed_paths, GATE_RECEIPT_REL]
    for name in ("AUTHORIZATION_REL",):
        value = getattr(correction, name, None)
        if isinstance(value, Path):
            paths.append(value)
    for name in ("REVIEW_PATHS", "REVIEWED_CONTROL_PATHS"):
        values = getattr(correction, name, ())
        require(
            isinstance(values, (tuple, list)) and all(isinstance(value, Path) for value in values),
            f"seq99 reviewed-control path inventory differs: {name}",
        )
        paths.extend(values)
    event_root = gate.GATE_ROOT_RELATIVE / EVENT_ID
    paths.extend(
        event_root / f"{index:02d}-{check_id}.log"
        for index, check_id in enumerate(EXPECTED_CHECK_IDS, start=1)
    )
    return tuple(dict.fromkeys(paths))


def _start_projection_retained_paths(
    root: Path,
    source: Mapping[str, Any],
) -> tuple[Path, ...]:
    paths = {
        *_publication_retained_paths(root, source),
        START_PROJECTION_AUTHORIZATION_REL,
        *START_PROJECTION_REVIEW_PATHS,
        *START_PROJECTION_REVIEWED_CONTROL_PATHS,
        START_PROJECTION_TEST_REL,
    }
    return tuple(sorted(paths, key=lambda path: path.as_posix()))


def _start_projection_final_managed_paths(
    source: Mapping[str, Any],
    visible: Sequence[str],
    retained: Sequence[Path],
) -> tuple[str, ...]:
    candidates = set(source["working_tree_snapshot"]["managed_changed_paths"])
    candidates.update(visible)
    candidates.update(path.as_posix() for path in retained)
    paths = tuple(
        sorted(
            path
            for path in candidates
            if path != CHECKPOINT_REL.as_posix()
            and not path.startswith(GATE_EVIDENCE_PREFIX)
        )
    )
    require(
        CHECKPOINT_REL.as_posix() not in paths
        and not any(path.startswith(GATE_EVIDENCE_PREFIX) for path in paths),
        "seq100 corrected managed boundary differs",
    )
    return paths


def _validate_projected_with_consumers(root: Path, projected: Mapping[str, Any]) -> None:
    try:
        with _historical_seq99_validation_phase(root):
            seq90._validate_projected_with_consumers(root, projected)
    except Exception as exc:
        raise StartApplyError(f"seq100 projected consumer validation failed: {exc}") from exc


def _require_prepared_exact(prepared: PreparedProjection, *, phase: str) -> None:
    source_read = seq90.ReadResult(prepared.source_bytes, prepared.source_identity)
    if phase != "at seq100 start-projection CAS boundary":
        try:
            seq90._require_preflight_cohort_unchanged(
                prepared.root,
                source=source_read,
                retained=prepared.retained_inputs,
                managed=prepared.managed_inputs,
                git_visible=prepared.git_visible_inputs,
                git_status_raw=prepared.git_status_raw,
                git_head=prepared.git_head,
                git_branch=prepared.git_branch,
                phase=phase,
            )
        except Exception as exc:
            raise StartApplyError(str(exc)) from exc
    require(
        prepared.source_bytes == checkpoint_bytes(prepared.source)
        and _canonical_seq99_authority_bytes(prepared.root, prepared.source) == prepared.source_bytes,
        "prepared seq99 source authority differs",
    )
    _require_exact_start_projection_source(prepared.source_bytes, prepared.source)
    _load_start_projection_review_chain(prepared.root, prepared.source)
    current_evidence = require_published_r011_gate_for_seq99(
        prepared.root,
        prepared.source,
        prepared.source_bytes,
        event_occurred_at=prepared.evidence.event_occurred_at,
    )
    require(
        current_evidence.receipt_bytes == prepared.evidence.receipt_bytes
        and strict_equal(current_evidence.receipt_binding, prepared.evidence.receipt_binding)
        and strict_equal(current_evidence.log_sha256_by_check_id, prepared.evidence.log_sha256_by_check_id),
        "R011 evidence changed after preflight",
    )
    require(prepared.projected_bytes == checkpoint_bytes(prepared.projected), "prepared seq100 bytes differ")
    validate_projection(prepared.root, prepared.source, prepared.projected, prepared.evidence)
    snapshot = prepared.projected["working_tree_snapshot"]
    paths = sorted(path.as_posix() for path in prepared.managed_inputs)
    hashes = seq90._managed_input_snapshot_hashes(prepared.managed_inputs)
    require(
        paths == snapshot["managed_changed_paths"]
        and hashes == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
        "prepared seq100 managed snapshot differs",
    )


def _prepare_corrected_projection(
    root: Path = ROOT,
    *,
    gate_loader: Callable[[Path, Mapping[str, Any], bytes], GateEvidence] | None = None,
) -> PreparedProjection:
    root = root.resolve(strict=True)
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    require(
        stat.S_ISREG(source_read.identity.mode)
        and stat.S_IMODE(source_read.identity.mode) == 0o600
        and source_read.identity.uid == os.geteuid()
        and source_read.identity.links == 1,
        "seq99 checkpoint physical authority differs",
    )
    source_bytes = source_read.raw
    source = strict_json(source_bytes, "seq99 checkpoint")
    _require_exact_start_projection_source(source_bytes, source)
    require(
        _canonical_seq99_authority_bytes(root, source) == source_bytes,
        "exact seq99 correction CAS differs",
    )
    evidence = require_published_r011_gate_for_seq99(
        root, source, source_bytes, gate_loader=gate_loader
    )
    _review, _primary_at, independent_at = _load_start_projection_review_chain(
        root, source
    )
    if gate_loader is None:
        generated_at = _parse_time(
            evidence.receipt.get("generated_at"),
            label="R011 generated_at",
            second_precision=True,
        )
        event_at = max(generated_at, independent_at) + timedelta(seconds=1)
        evidence = require_published_r011_gate_for_seq99(
            root,
            source,
            source_bytes,
            event_occurred_at=event_at.isoformat(),
        )
    retained_paths = _start_projection_retained_paths(root, source)
    retained = {path: seq90._stable_read(root, path) for path in retained_paths}
    git_status_raw, git_visible_paths = seq90.capture_git_visible_paths(root)
    git_head, git_branch = seq90._capture_git_context(root)
    paths = _start_projection_final_managed_paths(
        source,
        git_visible_paths,
        retained_paths,
    )
    managed = seq90._capture_managed_inputs(root, paths)
    git_visible = seq90._capture_managed_inputs(root, git_visible_paths)
    hashes = seq90._managed_input_snapshot_hashes(managed)
    projected, event = project_seq100(
        root, source, evidence, managed_paths=paths, snapshot_hashes=hashes
    )
    validate_projection(root, source, projected, evidence)
    _validate_projected_with_consumers(root, projected)
    prepared = PreparedProjection(
        root=root,
        source_bytes=source_bytes,
        source=source,
        projected=projected,
        projected_bytes=checkpoint_bytes(projected),
        event=event,
        evidence=evidence,
        source_identity=source_read.identity,
        retained_inputs=retained,
        managed_inputs=managed,
        git_visible_inputs=git_visible,
        git_status_raw=git_status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    _require_prepared_exact(prepared, phase="during seq100 preflight")
    return prepared


def prepare_corrected_projection(
    root: Path = ROOT,
    *,
    gate_loader: Callable[[Path, Mapping[str, Any], bytes], GateEvidence] | None = None,
) -> PreparedProjection:
    correction = _correction()
    scope = getattr(correction, "seq98_source_validation_call_scope", None)
    if not callable(scope):
        return _prepare_corrected_projection(root, gate_loader=gate_loader)
    with scope():
        return _prepare_corrected_projection(root, gate_loader=gate_loader)


def prepare_projection(
    root: Path = ROOT,
    *,
    gate_loader: Callable[[Path, Mapping[str, Any], bytes], GateEvidence] | None = None,
) -> PreparedProjection:
    return prepare_corrected_projection(root, gate_loader=gate_loader)


def _start_projection_cas_temp_relatives(status_raw: bytes) -> tuple[Path, ...]:
    candidates: list[Path] = []
    for record in status_raw.split(b"\0"):
        if not record.startswith(b"?? "):
            continue
        try:
            relative = Path(record[3:].decode("utf-8"))
        except UnicodeDecodeError as exc:
            raise StartApplyError("seq100 CAS temporary status is not UTF-8") from exc
        name = relative.name
        token = name[
            len(START_PROJECTION_CAS_TEMP_PREFIX) : -len(
                START_PROJECTION_CAS_TEMP_SUFFIX
            )
        ]
        if (
            relative.parent == CHECKPOINT_REL.parent
            and name.startswith(START_PROJECTION_CAS_TEMP_PREFIX)
            and name.endswith(START_PROJECTION_CAS_TEMP_SUFFIX)
            and re.fullmatch(r"[0-9a-f]{24}", token) is not None
        ):
            candidates.append(relative)
    return tuple(candidates)


def _require_start_projection_atomic_boundary(
    prepared: PreparedProjection,
) -> str:
    _require_prepared_exact(
        prepared,
        phase="at seq100 start-projection CAS boundary",
    )
    current = seq90._stable_read(prepared.root, CHECKPOINT_REL)
    if current.raw == prepared.source_bytes:
        phase = "SOURCE"
        expected_temporary = prepared.projected_bytes
        require(
            current.identity == prepared.source_identity,
            "seq100 CAS source identity differs",
        )
    elif current.raw == prepared.projected_bytes:
        phase = "PROJECTED"
        expected_temporary = prepared.source_bytes
    else:
        raise StartApplyError("seq100 CAS live checkpoint phase differs")
    info = (prepared.root / CHECKPOINT_REL).lstat()
    require(
        stat.S_ISREG(info.st_mode)
        and not stat.S_ISLNK(info.st_mode)
        and stat.S_IMODE(info.st_mode) == 0o600
        and info.st_uid == os.geteuid()
        and info.st_nlink == 1,
        "seq100 CAS live checkpoint authority differs",
    )
    seq90._require_inputs_unchanged(prepared.root, prepared.retained_inputs)
    seq90._require_managed_inputs_unchanged(
        prepared.root, prepared.managed_inputs
    )
    seq90._require_managed_inputs_unchanged(
        prepared.root,
        {
            path: value
            for path, value in prepared.git_visible_inputs.items()
            if path != CHECKPOINT_REL
        },
        label="noncheckpoint Git-visible input",
    )
    seq90._require_git_context(
        prepared.root, prepared.git_head, prepared.git_branch
    )
    observed_status, observed_visible = seq90.capture_git_visible_paths(
        prepared.root
    )
    candidates = _start_projection_cas_temp_relatives(observed_status)
    require(len(candidates) == 1, "seq100 CAS temporary inventory differs")
    temporary_relative = candidates[0]
    require(
        seq90._without_ephemeral_status_record(
            observed_status, temporary_relative
        )
        == prepared.git_status_raw,
        "Git-visible cohort changed at seq100 CAS boundary",
    )
    expected_visible = tuple(
        sorted(
            {
                *(path.as_posix() for path in prepared.git_visible_inputs),
                temporary_relative.as_posix(),
            }
        )
    )
    require(
        observed_visible == expected_visible,
        "Git-visible inventory changed at seq100 CAS boundary",
    )
    temporary = seq90._stable_read(prepared.root, temporary_relative)
    require(
        temporary.raw == expected_temporary
        and stat.S_IMODE(temporary.identity.mode) == 0o600
        and temporary.identity.uid == os.geteuid()
        and temporary.identity.links == 1,
        "seq100 CAS temporary authority differs",
    )
    if phase == "SOURCE":
        require(
            _canonical_seq99_authority_bytes(
                prepared.root, prepared.source
            )
            == prepared.source_bytes,
            "seq100 CAS source authority differs",
        )
    else:
        require_started_checkpoint(
            prepared.root,
            prepared.projected,
            require_live_snapshot=False,
            run_external_validators=False,
        )
    return phase


def _write_start_projection_atomic(prepared: PreparedProjection) -> None:
    phases: list[str] = []

    def commit_guard() -> None:
        phases.append(_require_start_projection_atomic_boundary(prepared))

    try:
        correction = _correction()
        scope = getattr(correction, "seq98_source_validation_call_scope", None)
        if callable(scope):
            with scope():
                cas.atomic_write(
                    prepared.root / CHECKPOINT_REL,
                    prepared.projected_bytes,
                    expected_source=prepared.source_bytes,
                    commit_guard=commit_guard,
                )
        else:
            cas.atomic_write(
                prepared.root / CHECKPOINT_REL,
                prepared.projected_bytes,
                expected_source=prepared.source_bytes,
                commit_guard=commit_guard,
            )
    except cas.CompletionPostCommitError as exc:
        raise seq90.PostcommitUncertain(str(exc)) from exc
    except cas.CompletionApplyError as exc:
        raise StartApplyError(str(exc)) from exc
    require(
        phases == ["SOURCE", "PROJECTED", "PROJECTED"],
        "seq100 CAS commit guard sequence differs",
    )


def _require_start_projection_terminal_cohort(
    prepared: PreparedProjection,
) -> None:
    seq90._require_inputs_unchanged(prepared.root, prepared.retained_inputs)
    seq90._require_managed_inputs_unchanged(
        prepared.root, prepared.managed_inputs
    )
    seq90._require_managed_inputs_unchanged(
        prepared.root,
        {
            path: value
            for path, value in prepared.git_visible_inputs.items()
            if path != CHECKPOINT_REL
        },
        label="terminal noncheckpoint Git-visible input",
    )
    seq90._require_git_context(
        prepared.root, prepared.git_head, prepared.git_branch
    )
    observed_status, _visible = seq90.capture_git_visible_paths(prepared.root)
    require(
        observed_status == prepared.git_status_raw,
        "terminal Git-visible cohort changed",
    )
    _load_start_projection_review_chain(prepared.root, prepared.source)
    current_evidence = require_published_r011_gate_for_seq99(
        prepared.root,
        prepared.source,
        prepared.source_bytes,
        event_occurred_at=prepared.evidence.event_occurred_at,
    )
    require(
        current_evidence.receipt_bytes == prepared.evidence.receipt_bytes,
        "terminal R011 evidence changed",
    )


def write_corrected_projection(prepared: PreparedProjection) -> None:
    current = seq90._stable_read(prepared.root, CHECKPOINT_REL)
    if current.raw == prepared.projected_bytes:
        _require_start_projection_terminal_cohort(prepared)
        published = strict_json(current.raw, "published seq100 checkpoint")
        require_started_checkpoint(
            prepared.root,
            published,
            require_live_snapshot=True,
            run_external_validators=True,
        )
        terminal = seq90._stable_read(prepared.root, CHECKPOINT_REL)
        require(
            terminal.identity == current.identity and terminal.raw == current.raw,
            "published seq100 checkpoint changed during terminal validation",
        )
        _require_start_projection_terminal_cohort(prepared)
        return
    require(
        current.identity == prepared.source_identity
        and current.raw == prepared.source_bytes,
        "seq99 source changed before publication",
    )
    _require_prepared_exact(prepared, phase="before corrected seq100 publication")
    try:
        _write_start_projection_atomic(prepared)
    except seq90.PostcommitUncertain:
        raise
    except BaseException as exc:
        observed = seq90._stable_read(prepared.root, CHECKPOINT_REL)
        if observed.raw == prepared.projected_bytes:
            raise seq90.PostcommitUncertain(
                "seq100 writer failed after publishing exact bytes"
            ) from exc
        raise
    published_read = seq90._stable_read(prepared.root, CHECKPOINT_REL)
    if published_read.raw != prepared.projected_bytes:
        raise seq90.PostcommitUncertain(
            "published seq100 checkpoint bytes are uncertain"
        )
    try:
        _require_start_projection_terminal_cohort(prepared)
        published = strict_json(
            published_read.raw, "published seq100 checkpoint"
        )
        require_started_checkpoint(
            prepared.root,
            published,
            require_live_snapshot=True,
            run_external_validators=True,
        )
        terminal = seq90._stable_read(prepared.root, CHECKPOINT_REL)
        require(
            terminal.identity == published_read.identity
            and terminal.raw == published_read.raw,
            "published seq100 checkpoint changed during terminal validation",
        )
        _require_start_projection_terminal_cohort(prepared)
    except BaseException as exc:
        raise seq90.PostcommitUncertain(
            "seq100 publication completed but terminal authority is uncertain"
        ) from exc


def write_projection(
    prepared: PreparedProjection,
    *,
    writer: Callable[..., None] | None = None,
) -> None:
    if writer is None:
        write_corrected_projection(prepared)
        return
    current = seq90._stable_read(prepared.root, CHECKPOINT_REL)
    if current.raw == prepared.projected_bytes:
        require(
            stat.S_IMODE(current.identity.mode) == 0o600 and current.identity.links == 1,
            "published seq100 checkpoint mode differs",
        )
        published = strict_json(current.raw, "published seq100 checkpoint")
        require_started_checkpoint(
            prepared.root, published, require_live_snapshot=True, run_external_validators=True
        )
        terminal = seq90._stable_read(prepared.root, CHECKPOINT_REL)
        require(
            terminal.identity == current.identity and terminal.raw == current.raw,
            "published seq100 checkpoint changed during terminal validation",
        )
        return
    require(
        current.identity == prepared.source_identity and current.raw == prepared.source_bytes,
        "seq99 source changed before publication",
    )
    _require_prepared_exact(prepared, phase="before seq100 publication")
    transport = seq90.Prepared(
        root=prepared.root,
        source_raw=prepared.source_bytes,
        source_identity=prepared.source_identity,
        source=prepared.source,
        projected=prepared.projected,
        projected_raw=prepared.projected_bytes,
        event=prepared.event,
        retained_inputs=prepared.retained_inputs,
        managed_inputs=prepared.managed_inputs,
        git_visible_inputs=prepared.git_visible_inputs,
        git_status_raw=prepared.git_status_raw,
        git_head=prepared.git_head,
        git_branch=prepared.git_branch,
    )
    try:
        writer(
            transport,
            commit_guard=lambda: _require_prepared_exact(prepared, phase="at seq100 commit point"),
        )
    except seq90.PostcommitUncertain:
        raise
    except BaseException as exc:
        observed = seq90._stable_read(prepared.root, CHECKPOINT_REL)
        if observed.raw == prepared.projected_bytes:
            raise seq90.PostcommitUncertain("seq100 writer failed after publishing exact bytes") from exc
        raise
    published_read = seq90._stable_read(prepared.root, CHECKPOINT_REL)
    if published_read.raw != prepared.projected_bytes:
        raise seq90.PostcommitUncertain("published seq100 checkpoint bytes are uncertain")
    try:
        require(
            stat.S_IMODE(published_read.identity.mode) == 0o600
            and published_read.identity.links == 1,
            "published seq100 checkpoint mode differs",
        )
        published = strict_json(published_read.raw, "published seq100 checkpoint")
        require_started_checkpoint(
            prepared.root, published, require_live_snapshot=True, run_external_validators=True
        )
        terminal = seq90._stable_read(prepared.root, CHECKPOINT_REL)
        require(
            terminal.identity == published_read.identity and terminal.raw == published_read.raw,
            "published seq100 checkpoint changed during terminal validation",
        )
    except BaseException as exc:
        raise seq90.PostcommitUncertain(
            "seq100 publication completed but terminal authority is uncertain"
        ) from exc


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare-review", action="store_true")
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.prepare_review:
            outputs = prepare_start_projection_review_inputs(args.root)
            write_start_projection_review_inputs(args.root, outputs)
            print(
                "FP048 R002 GOAL_STARTED seq100: PASS mode=PREPARE_REVIEW "
                + " ".join(
                    f"{path.name}={sha256_bytes(raw)}"
                    for path, raw in outputs.items()
                )
            )
            return 0
        prepared = prepare_projection(args.root)
        mode = "PREFLIGHT"
        if args.write:
            write_projection(prepared)
            mode = "WRITE"
    except seq90.PostcommitUncertain as exc:
        print(f"FP048 R002 GOAL_STARTED seq100: UNCERTAIN: {exc}", file=sys.stderr)
        return 2
    except Exception as exc:
        print(f"FP048 R002 GOAL_STARTED seq100: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "FP048 R002 GOAL_STARTED seq100: PASS "
        f"mode={mode} event_sha256={prepared.event['event_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
