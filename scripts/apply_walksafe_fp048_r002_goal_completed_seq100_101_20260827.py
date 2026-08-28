#!/usr/bin/env python3
"""Project FP-048 R002 internal evidence seq100 and completion seq101."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_r002_goal_completed_seq99_100_20260826 as frozen_completion
from scripts import apply_walksafe_fp048_r002_goal_completed_seq98_99_20260826 as completion_base
from scripts import apply_walksafe_fp048_r002_goal_started_seq99_20260827 as started
from scripts import apply_walksafe_fp048_r002_start_gate_execution_correction_seq98_20260827 as execution_correction
from scripts import apply_walksafe_fp046_goal_completed_seq54_55_20260810 as runtime_authority
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as continuation


CHECKPOINT_REL = started.CHECKPOINT_REL
SOURCE_SEQUENCE = 99
EVIDENCE_SEQUENCE = 100
COMPLETION_SEQUENCE = 101
SOURCE_EVENT_ID = started.EVENT_ID
EVIDENCE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-"
    "FP048-R002-20260827-003"
)
COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP048-R002-20260827-003"
)
GOAL_ID = started.TARGET_GOAL_ID
GOAL_SHA256 = started.TARGET_GOAL_SHA256
WORK_ITEM_ID = started.WORK_ITEM_ID
PARENT_GOAL_ID = frozen_completion.PARENT_GOAL_ID
PARENT_GOAL_PATH = frozen_completion.PARENT_GOAL_PATH
PARENT_GOAL_SHA256 = frozen_completion.PARENT_GOAL_SHA256
EPIC04_GOAL_ID = frozen_completion.EPIC04_GOAL_ID
EPIC12_GOAL_ID = frozen_completion.EPIC12_GOAL_ID
MANIFEST_SHA256 = started.MANIFEST_SHA256

SCRIPT_REL = Path("scripts/apply_walksafe_fp048_r002_goal_completed_seq100_101_20260827.py")
TEST_REL = Path("tests/test_apply_walksafe_fp048_r002_goal_completed_seq100_101_20260827.py")
STARTER_AUTHORITY_PINS: Mapping[Path, tuple[str, int] | None] = {
    started.SCRIPT_REL: (
        "b30f8c33094927f1e2baa4d6847f2acc55615182f77c09f7ebc0c637b63be9dc",
        45_725,
    ),
    started.TEST_REL: (
        "ddcdcfaf6d50c44b69d2a3c32932f559ce7a8455653627613f669c51ea933146",
        23_101,
    ),
}
PRODUCT_PINS = frozen_completion.PRODUCT_PINS
CONSUMER_PATHS = frozen_completion.CONSUMER_PATHS
COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
COMPLETION_DOCUMENT_ID = (
    "WS-FP048-R002-ENCRYPTION-CONNECTION-SECURITY-WORK-ITEM-"
    "COMPLETION-20260827-003"
)
COMPLETION_RECEIPT_REL = (
    Path("docs/control/execution/goal-results")
    / GOAL_ID
    / "completion-receipt-20260827-003.json"
)
REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq100-101/review-rounds/R001"
)
REVIEW_ASSIGNMENT_REL = REVIEW_ROOT / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_ROOT / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_ROOT / "independent-review.json"
REVIEW_PATHS = (REVIEW_ASSIGNMENT_REL, REVIEW_RESULT_REL, INDEPENDENT_REVIEW_REL)
REVIEW_ASSIGNMENT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ100-101-REVIEW-ASSIGNMENT-20260827-R001"
)
REVIEW_RESULT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ100-101-REVIEW-RESULT-20260827-R001"
)
INDEPENDENT_REVIEW_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ100-101-INDEPENDENT-REVIEW-20260827-R001"
)
PRODUCER_TASK_ID = "/root/seq96_review_r002_update"
PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq100-101-primary-reviewer-20260827",
    "task_id": "/root/seq96_goalgraph_terminal_dispatch_audit",
}
INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq100-101-independent-reviewer-20260827",
    "task_id": "/root/seq94_contract_correction_impl/r001_preflight_failure_audit",
}
ZERO_CREDIT_BOUNDARY = copy.deepcopy(frozen_completion.ZERO_CREDIT_BOUNDARY)
EVIDENCE_FIELDS = frozenset(frozen_completion.EVIDENCE_FIELDS)
COMPLETION_FIELDS = frozenset(frozen_completion.COMPLETION_FIELDS)
CHANGED_PATH_PREIMAGE_PATHS = frozen_completion.CHANGED_PATH_PREIMAGE_PATHS


class CompletionApplyError(RuntimeError):
    """The shifted internal-only FP048 completion authority differs."""


@dataclass(frozen=True)
class CompletionEvidence:
    verification: Mapping[str, Any]
    receipt: Mapping[str, Any]
    receipt_bytes: bytes
    receipt_binding: Mapping[str, Any]
    review_binding: Mapping[str, Mapping[str, Any]]
    latest_authority_at: str


@dataclass(frozen=True)
class ReviewAuthority:
    binding: Mapping[str, Mapping[str, Any]]
    latest_review_at: str


@dataclass(frozen=True)
class PreparedProjection:
    root: Path
    source_bytes: bytes
    source: Mapping[str, Any]
    projected: Mapping[str, Any]
    projected_bytes: bytes
    evidence_event: Mapping[str, Any]
    completion_event: Mapping[str, Any]
    evidence: CompletionEvidence
    source_identity: Any
    retained_inputs: Mapping[Path, Any]
    managed_inputs: Mapping[Path, Any]
    git_visible_inputs: Mapping[Path, Any]
    git_status_raw: bytes
    git_head: str
    git_branch: str


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CompletionApplyError(message)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def json_bytes(value: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")


def strict_json(raw: bytes, label: str) -> dict[str, Any]:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            require(key not in value, f"duplicate JSON key in {label}: {key}")
            value[key] = item
        return value

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicates)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise CompletionApplyError(f"invalid JSON: {label}") from exc
    require(type(value) is dict, f"JSON root differs: {label}")
    return value


def _private_read(root: Path, relative: Path) -> Any:
    result = started.seq90._stable_read(root, relative)
    require(
        stat.S_ISREG(result.identity.mode)
        and stat.S_IMODE(result.identity.mode) == 0o600
        and result.identity.uid == os.geteuid()
        and result.identity.links == 1,
        f"private authority mode differs: {relative}",
    )
    return result


def checkpoint_bytes(value: Mapping[str, Any]) -> bytes:
    return started.checkpoint_bytes(value)


def strict_equal(left: Any, right: Any) -> bool:
    try:
        return continuation.canonical_json_bytes(left) == continuation.canonical_json_bytes(right)
    except (TypeError, ValueError):
        return False


def _parse_time(value: Any, *, label: str) -> datetime:
    require(isinstance(value, str), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise CompletionApplyError(f"{label} is not ISO-8601") from exc
    require(parsed.utcoffset() is not None, f"{label} lacks timezone")
    require(parsed.microsecond == 0, f"{label} must use second precision")
    return parsed


def _binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
    }


def validate_starter_authority_handshake(
    root: Path,
    pins: Mapping[Path, tuple[str, int]] = STARTER_AUTHORITY_PINS,
) -> None:
    require(set(pins) == set(STARTER_AUTHORITY_PINS), "starter authority path set differs")
    for relative in STARTER_AUTHORITY_PINS:
        pin = pins[relative]
        require(
            type(pin) is tuple
            and len(pin) == 2
            and isinstance(pin[0], str)
            and re.fullmatch(r"[0-9a-f]{64}", pin[0]) is not None
            and type(pin[1]) is int
            and pin[1] > 0,
            f"starter authority pin differs: {relative}",
        )
        raw = (root / relative).read_bytes()
        require(
            (sha256_bytes(raw), len(raw)) == pin,
            f"starter authority drifted: {relative}",
        )


def _receipt_binding(raw: bytes) -> dict[str, Any]:
    return {
        "role": COMPLETION_ROLE,
        "document_id": COMPLETION_DOCUMENT_ID,
        "path": COMPLETION_RECEIPT_REL.as_posix(),
        "file_sha256": sha256_bytes(raw),
    }


def _require_zero_credit(checkpoint: Mapping[str, Any]) -> None:
    try:
        started._require_zero_credit(checkpoint)
    except started.StartApplyError as exc:
        raise CompletionApplyError(str(exc)) from exc


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
        and tail.get("event_type") == "GOAL_STARTED"
        and tail.get("subject_goal_id") == GOAL_ID
        and tail.get("from_status") == "READY"
        and tail.get("to_status") == "IN_PROGRESS"
        and tail.get("event_sha256") == continuation.event_sha256(tail)
        and isinstance(statuses, dict)
        and statuses.get(GOAL_ID) == "IN_PROGRESS"
        and list(statuses.values()).count("IN_PROGRESS") == 1
        and state.get("goal_status") == "IN_PROGRESS"
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("pending_producer_completion_goal_id") in {None, ""},
        "source FP048 R002 IN_PROGRESS authority differs",
    )
    current = source.get("current_work")
    require(
        isinstance(current, dict)
        and current.get("status") == "IN_PROGRESS"
        and current.get("release_completion_claimed") is False,
        "source current-work boundary differs",
    )
    _require_zero_credit(source)


def require_exact_source(root: Path, source: Mapping[str, Any]) -> None:
    """Validate the frozen physical seq97 authority behind this completion."""

    raw = checkpoint_bytes(source)
    try:
        execution_correction.require_exact_seq97_source(raw, source, root)
    except Exception as exc:
        raise CompletionApplyError(
            f"exact seq97 completion source differs: {exc}"
        ) from exc


def _require_verification_summary(
    value: Mapping[str, Any],
    *,
    root: Path = ROOT,
) -> tuple[datetime, datetime]:
    focused_counts = {
        "tests": 13,
        "pass": 13,
        "fail": 0,
        "cancelled": 0,
        "skipped": 0,
        "todo": 0,
    }
    full_counts = {**focused_counts, "tests": 109, "pass": 109}
    gateway = root / completion_base.GATEWAY_REL
    test_files = [
        path.relative_to(gateway).as_posix()
        for path in sorted((gateway / "dist/test").glob("*.test.js"))
    ]
    require(test_files, "compiled gateway tests are missing")
    expected = {
        "TYPECHECK": {
            "check_id": "TYPECHECK",
            "argv": [str(completion_base.LOCKED_NPM), "run", "typecheck", "--silent"],
            "status": "PASS",
        },
        "BUILD": {
            "check_id": "BUILD",
            "argv": [str(completion_base.LOCKED_NPM), "run", "build", "--silent"],
            "status": "PASS",
        },
        "FOCUSED_STATE_ENCRYPTION_MAINTENANCE": {
            "check_id": "FOCUSED_STATE_ENCRYPTION_MAINTENANCE",
            "argv": [
                str(completion_base.LOCKED_NODE),
                "--test",
                completion_base.FOCUSED_TEST_REL.as_posix(),
            ],
            "status": "PASS",
            "tap": focused_counts,
        },
        "ANDROID_GATEWAY_FULL": {
            "check_id": "ANDROID_GATEWAY_FULL",
            "argv": [str(completion_base.LOCKED_NODE), "--test", *test_files],
            "status": "PASS",
            "tap": full_counts,
        },
    }
    require(
        set(value) == {"claim_scope", "execution_window", "toolchain", "checks"}
        and value.get("claim_scope")
        == "REPOSITORY_INTERNAL_FRESH_VERIFICATION_ONLY",
        "verification claim scope differs",
    )
    toolchain = value.get("toolchain")
    require(
        type(toolchain) is dict
        and set(toolchain) == {"node_path", "node_version", "npm_path", "npm_version"}
        and toolchain
        == {
            "node_path": str(completion_base.LOCKED_NODE),
            "node_version": completion_base.LOCKED_NODE_VERSION,
            "npm_path": str(completion_base.LOCKED_NPM),
            "npm_version": completion_base.LOCKED_NPM_VERSION,
        },
        "verification toolchain differs",
    )
    window = value.get("execution_window")
    require(type(window) is dict and set(window) == {"started_at", "ended_at"}, "verification execution window differs")
    started_at = _parse_time(window.get("started_at"), label="verification started_at")
    ended_at = _parse_time(window.get("ended_at"), label="verification ended_at")
    require(ended_at >= started_at, "verification chronology differs")
    checks = value.get("checks")
    require(type(checks) is list and len(checks) == 4, "verification checks differ")
    by_id = {
        row.get("check_id"): row
        for row in checks
        if type(row) is dict and type(row.get("check_id")) is str
    }
    require(
        len(by_id) == len(checks)
        and set(by_id) == set(expected)
        and all(strict_equal(by_id[key], row) for key, row in expected.items()),
        "verification commands or counts differ",
    )
    return started_at, ended_at


def build_completion_receipt(
    root: Path,
    source: Mapping[str, Any],
    verification: Mapping[str, Any],
) -> dict[str, Any]:
    _require_source_shape(source)
    try:
        product_files = completion_base._verify_product_pins(root)
    except completion_base.CompletionApplyError as exc:
        raise CompletionApplyError(str(exc)) from exc
    started_at, ended_at = _require_verification_summary(verification, root=root)
    tail = source["goal_execution"]["transition_history"][-1]
    source_at = _parse_time(tail.get("occurred_at"), label="seq99 occurred_at")
    require(source_at < started_at, "verification does not follow seq99 start")
    return {
        "schema_version": "1.0",
        "document_id": COMPLETION_DOCUMENT_ID,
        "goal_id": GOAL_ID,
        "work_item_id": WORK_ITEM_ID,
        "status": "COMPLETE_AT_TARGET_INTERNAL_ONLY",
        "completion_level": "INTERNAL_REPOSITORY_IMPLEMENTATION_VERIFIED",
        "source_started_event_binding": {
            "sequence": SOURCE_SEQUENCE,
            "event_id": SOURCE_EVENT_ID,
            "event_sha256": tail["event_sha256"],
        },
        "implementation_evidence": {
            "feature": "SEVEN_STATE_ENCRYPTION_ROTATION_ALL_OR_NOTHING",
            "state_kind_count": 7,
            "preflight_before_mutation": True,
            "files": product_files,
        },
        "gap_reassessment": {
            "gap_id": "GAP-057",
            "finding_id": "FP048-SEVEN-STATE-ROTATION-ATOMICITY-GAP",
            "internal_finding_status": "CLOSED_AT_INTERNAL_TARGET",
            "policy_status": "PARTIAL_EXTERNAL_GATES_REMAIN",
            "remaining_gate_count": 5,
            "release_status": "NOT_ELIGIBLE",
        },
        "verification_evidence": copy.deepcopy(dict(verification)),
        "generated_at": ended_at.isoformat(),
        "completion_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
    }


def expected_review_assignment(
    root: Path,
    source_bytes: bytes,
    source: Mapping[str, Any],
    receipt_bytes: bytes,
) -> dict[str, Any]:
    root = root.resolve(strict=True)
    _require_source_shape(source)
    require(source_bytes == checkpoint_bytes(source), "review source bytes differ")
    tail = source["goal_execution"]["transition_history"][-1]

    def rows(paths: Sequence[Path]) -> list[dict[str, Any]]:
        return [
            _binding(relative, started.seq90._stable_read(root, relative).raw)
            for relative in paths
        ]

    return {
        "schema_version": "1.0",
        "document_id": REVIEW_ASSIGNMENT_DOCUMENT_ID,
        "goal_id": GOAL_ID,
        "round_id": "R001",
        "source_checkpoint_binding": {
            **_binding(CHECKPOINT_REL, source_bytes),
            "sequence": SOURCE_SEQUENCE,
            "tail_event_id": SOURCE_EVENT_ID,
            "tail_event_sha256": tail["event_sha256"],
        },
        "completion_receipt_binding": _binding(COMPLETION_RECEIPT_REL, receipt_bytes),
        "producer_bindings": rows((SCRIPT_REL, TEST_REL)),
        "consumer_bindings": rows(CONSUMER_PATHS),
        "product_bindings": rows(tuple(PRODUCT_PINS)),
        "projected_transition": {
            "evidence_sequence": EVIDENCE_SEQUENCE,
            "evidence_event_id": EVIDENCE_EVENT_ID,
            "completion_sequence": COMPLETION_SEQUENCE,
            "completion_event_id": COMPLETION_EVENT_ID,
            "status_change": {GOAL_ID: "IN_PROGRESS_TO_COMPLETE_AT_TARGET"},
        },
        "review_scope": {
            "scope": "INTERNAL_REPOSITORY_IMPLEMENTATION_ONLY",
            "fresh_verification_required": True,
            "two_distinct_reviewers_required": True,
            "external_formal_device_deployment_release_credit": "ZERO",
        },
        "required_decision": "APPROVE_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY",
        "claim_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
    }


def validate_review_authority(
    expected_assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
    independent: Mapping[str, Any],
    independent_raw: bytes,
    *,
    verification_ended_at: datetime,
) -> ReviewAuthority:
    require(
        PRIMARY_REVIEWER["task_id"] != PRODUCER_TASK_ID
        and INDEPENDENT_REVIEWER["task_id"] != PRODUCER_TASK_ID
        and PRIMARY_REVIEWER["id"] != INDEPENDENT_REVIEWER["id"]
        and PRIMARY_REVIEWER["task_id"] != INDEPENDENT_REVIEWER["task_id"],
        "reviewer independence contract differs",
    )
    require(assignment_raw == json_bytes(expected_assignment), "review assignment bytes differ")
    assignment_binding = _binding(REVIEW_ASSIGNMENT_REL, assignment_raw)
    require(result_raw == json_bytes(result), "review result is not canonical")
    require(
        set(result)
        == {
            "schema_version", "document_id", "goal_id", "round_id",
            "assignment_binding", "reviewer", "reviewed_at", "decision",
            "findings", "external_independence_claimed", "claim_boundary",
        }
        and result.get("schema_version") == "1.0"
        and result.get("document_id") == REVIEW_RESULT_DOCUMENT_ID
        and result.get("goal_id") == GOAL_ID
        and result.get("round_id") == "R001"
        and strict_equal(result.get("assignment_binding"), assignment_binding)
        and strict_equal(result.get("reviewer"), PRIMARY_REVIEWER)
        and result.get("decision") == "APPROVE_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY"
        and strict_equal(result.get("findings"), [])
        and result.get("external_independence_claimed") is False
        and strict_equal(result.get("claim_boundary"), ZERO_CREDIT_BOUNDARY),
        "primary review authority differs",
    )
    result_at = _parse_time(result.get("reviewed_at"), label="primary reviewed_at")
    require(result_at > verification_ended_at, "primary review does not follow verification")
    result_binding = _binding(REVIEW_RESULT_REL, result_raw)
    require(independent_raw == json_bytes(independent), "independent review is not canonical")
    require(
        set(independent)
        == {
            "schema_version", "document_id", "goal_id", "round_id",
            "assignment_binding", "review_result_binding", "reviewer",
            "reviewed_at", "decision", "findings",
            "external_independence_claimed", "claim_boundary",
        }
        and independent.get("schema_version") == "1.0"
        and independent.get("document_id") == INDEPENDENT_REVIEW_DOCUMENT_ID
        and independent.get("goal_id") == GOAL_ID
        and independent.get("round_id") == "R001"
        and strict_equal(independent.get("assignment_binding"), assignment_binding)
        and strict_equal(independent.get("review_result_binding"), result_binding)
        and strict_equal(independent.get("reviewer"), INDEPENDENT_REVIEWER)
        and independent.get("decision") == "CONCUR_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY"
        and strict_equal(independent.get("findings"), [])
        and independent.get("external_independence_claimed") is False
        and strict_equal(independent.get("claim_boundary"), ZERO_CREDIT_BOUNDARY),
        "independent review authority differs",
    )
    independent_at = _parse_time(
        independent.get("reviewed_at"), label="independent reviewed_at"
    )
    require(independent_at > result_at, "independent review does not follow primary")
    return ReviewAuthority(
        binding={
            "assignment": assignment_binding,
            "review_result": result_binding,
            "independent_review": _binding(INDEPENDENT_REVIEW_REL, independent_raw),
        },
        latest_review_at=independent_at.isoformat(),
    )


def _changed_path_preimage(source: Mapping[str, Any]) -> dict[str, Any]:
    return frozen_completion._changed_path_preimage(source)


def _source_history_preimage(source: Mapping[str, Any]) -> dict[str, Any]:
    history = source["goal_execution"]["transition_history"]
    return {
        "length": SOURCE_SEQUENCE,
        "tail_event_sha256": history[-1]["event_sha256"],
    }


def _require_completion_evidence(
    root: Path,
    source: Mapping[str, Any],
    evidence: CompletionEvidence,
) -> None:
    require(
        evidence.receipt_bytes == json_bytes(evidence.receipt)
        and strict_equal(evidence.receipt_binding, _receipt_binding(evidence.receipt_bytes))
        and evidence.receipt_bytes
        == json_bytes(build_completion_receipt(root, source, evidence.verification))
        and strict_equal(
            evidence.receipt.get("completion_boundary"), ZERO_CREDIT_BOUNDARY
        ),
        "completion receipt authority differs",
    )
    expected_paths = {
        "assignment": REVIEW_ASSIGNMENT_REL,
        "review_result": REVIEW_RESULT_REL,
        "independent_review": INDEPENDENT_REVIEW_REL,
    }
    require(
        set(evidence.review_binding) == set(expected_paths),
        "completion review binding role set differs",
    )
    for role, relative in expected_paths.items():
        row = evidence.review_binding[role]
        require(
            isinstance(row, Mapping)
            and set(row) == {"path", "sha256", "byte_length"}
            and row.get("path") == relative.as_posix()
            and isinstance(row.get("sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", row["sha256"]) is not None
            and type(row.get("byte_length")) is int
            and row["byte_length"] > 0,
            f"completion review binding differs: {role}",
        )
    _started_at, ended_at = _require_verification_summary(
        evidence.verification, root=root
    )
    require(
        _parse_time(evidence.latest_authority_at, label="latest review authority")
        >= ended_at,
        "completion review predates verification",
    )


def _restore_changed_path_preimage(
    projected: Mapping[str, Any], preimage: Mapping[str, Any]
) -> dict[str, Any]:
    restored = copy.deepcopy(dict(projected))
    for dotted_path in CHANGED_PATH_PREIMAGE_PATHS:
        current: Any = restored
        components = dotted_path.split(".")
        for component in components[:-1]:
            require(type(current) is dict and type(current.get(component)) is dict, "inverse parent differs")
            current = current[component]
        current[components[-1]] = copy.deepcopy(preimage[dotted_path])
    return restored


def reconstructed_seq99_checkpoint_bytes(
    root: Path,
    projected: Mapping[str, Any],
) -> bytes:
    root.resolve(strict=True)
    state = projected.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, Mapping) else None
    require(isinstance(history, list), "inverse history differs")
    if len(history) == SOURCE_SEQUENCE:
        source = copy.deepcopy(dict(projected))
        _require_source_shape(source)
        return checkpoint_bytes(source)
    require(
        len(history) != EVIDENCE_SEQUENCE,
        "seq100 producer transaction lacks adjacent seq101 completion",
    )
    require(len(history) == COMPLETION_SEQUENCE, "inverse requires exact seq99 or seq101")
    update, completion = history[-2:]
    require(
        isinstance(update, dict)
        and isinstance(completion, dict)
        and set(update) == EVIDENCE_FIELDS
        and set(completion) == COMPLETION_FIELDS
        and type(update.get("sequence")) is int
        and update.get("sequence") == EVIDENCE_SEQUENCE
        and update.get("event_id") == EVIDENCE_EVENT_ID
        and update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and type(completion.get("sequence")) is int
        and completion.get("sequence") == COMPLETION_SEQUENCE
        and completion.get("event_id") == COMPLETION_EVENT_ID
        and completion.get("event_type") == "GOAL_COMPLETED"
        and update.get("event_sha256") == continuation.event_sha256(update)
        and completion.get("event_sha256") == continuation.event_sha256(completion)
        and update.get("previous_event_sha256") == history[SOURCE_SEQUENCE - 1].get("event_sha256")
        and completion.get("previous_event_sha256") == update.get("event_sha256")
        and completion.get("canonical_update_event_sha256") == update.get("event_sha256")
        and state.get("transition_history_anchor_sha256") == completion.get("event_sha256")
        and state.get("validation_cutoff_at") == completion.get("occurred_at"),
        "seq100/101 inverse suffix differs",
    )
    expected_history = {
        "length": SOURCE_SEQUENCE,
        "tail_event_sha256": history[SOURCE_SEQUENCE - 1].get("event_sha256"),
    }
    require(
        strict_equal(update.get("source_history_preimage"), expected_history),
        "source history preimage differs",
    )
    preimage = update.get("changed_path_preimage")
    require(
        type(preimage) is dict and set(preimage) == set(CHANGED_PATH_PREIMAGE_PATHS),
        "changed path preimage differs",
    )
    binding = update.get("source_checkpoint_binding")
    require(
        type(binding) is dict
        and set(binding) == {"path", "sha256", "byte_length"}
        and binding.get("path") == CHECKPOINT_REL.as_posix()
        and isinstance(binding.get("sha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", binding["sha256"]) is not None
        and type(binding.get("byte_length")) is int,
        "source checkpoint binding differs",
    )
    restored = _restore_changed_path_preimage(projected, preimage)
    restored["goal_execution"]["transition_history"] = copy.deepcopy(
        history[:SOURCE_SEQUENCE]
    )
    raw = checkpoint_bytes(restored)
    require(strict_equal(binding, _binding(CHECKPOINT_REL, raw)), "inverse source CAS differs")
    _require_source_shape(restored)
    return raw


def reconstructed_seq97_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    """Recover frozen seq97 through the seq101/99→98→97 authority chain."""

    root = root.resolve(strict=True)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, Mapping) else None
    require(isinstance(history, list), "seq97 inverse history differs")
    if len(history) == execution_correction.SOURCE_SEQUENCE:
        source = copy.deepcopy(dict(checkpoint))
        raw = checkpoint_bytes(source)
        require_exact_source(root, source)
        return raw
    if len(history) == execution_correction.CORRECTION_SEQUENCE:
        corrected = copy.deepcopy(dict(checkpoint))
        corrected_raw = checkpoint_bytes(corrected)
    else:
        require(
            len(history) in {SOURCE_SEQUENCE, COMPLETION_SEQUENCE},
            "seq97 inverse requires exact seq97, seq98, seq99, or seq101",
        )
        started_raw = reconstructed_seq99_checkpoint_bytes(root, checkpoint)
        started_checkpoint = strict_json(started_raw, "reconstructed seq99 checkpoint")
        try:
            corrected_raw = started.reconstructed_seq98_checkpoint_bytes(
                root,
                started_checkpoint,
            )
        except Exception as exc:
            raise CompletionApplyError(
                f"seq99 inverse seq98 authority differs: {exc}"
            ) from exc
        corrected = strict_json(corrected_raw, "reconstructed seq98 checkpoint")
    try:
        execution_correction.require_start_gate_execution_corrected_checkpoint(
            root,
            corrected,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        source_raw = execution_correction.reconstructed_seq97_checkpoint_bytes(
            root,
            corrected,
        )
    except Exception as exc:
        raise CompletionApplyError(
            f"seq98 inverse seq97 authority differs: {exc}"
        ) from exc
    source = strict_json(source_raw, "reconstructed seq97 checkpoint")
    require_exact_source(root, source)
    return source_raw


def project_seq100_101(
    root: Path,
    source: Mapping[str, Any],
    evidence: CompletionEvidence,
    *,
    source_checkpoint_bytes: bytes,
    managed_paths: Sequence[str],
    snapshot_hashes: tuple[str, str],
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]], tuple[dict[str, Any], dict[str, Any]]
    ] = runtime_authority.derive_runtime,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    _require_source_shape(source)
    _require_completion_evidence(root, source, evidence)
    require(
        type(source_checkpoint_bytes) is bytes
        and source_checkpoint_bytes == checkpoint_bytes(source),
        "source checkpoint bytes differ",
    )
    source_state = source["goal_execution"]
    source_tail = source_state["transition_history"][-1]
    source_at = _parse_time(source_tail["occurred_at"], label="seq99 occurred_at")
    authority_at = _parse_time(evidence.latest_authority_at, label="review authority time")
    evidence_at = max(source_at, authority_at) + timedelta(seconds=1)
    completion_at = evidence_at + timedelta(seconds=1)
    checkpoint = copy.deepcopy(dict(source))
    checkpoint["canonical_bindings"] = completion_base._add_completion_binding(
        source, evidence.receipt_binding
    )
    canonical_after = continuation.canonical_binding_snapshot(checkpoint)
    state = checkpoint["goal_execution"]
    state["pending_producer_completion_goal_id"] = GOAL_ID
    queue99, boundary99 = runtime_deriver(
        root,
        checkpoint,
        [GOAL_ID, PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID],
    )
    state["artifact_work_queue"] = queue99
    state["completion_boundary"] = boundary99
    update: dict[str, Any] = {
        "sequence": EVIDENCE_SEQUENCE,
        "event_id": EVIDENCE_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "occurred_on": evidence_at.date().isoformat(),
        "occurred_at": evidence_at.isoformat(),
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "from_status": "IN_PROGRESS",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": completion_base._runtime_after(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [COMPLETION_ROLE],
        "previous_event_sha256": source_tail["event_sha256"],
        "produced_by_goal_id": GOAL_ID,
        "produced_binding_roles": [],
        "producer_completion_receipt_binding": copy.deepcopy(dict(evidence.receipt_binding)),
        "changed_binding_roles": [COMPLETION_ROLE],
        "changed_subject_ids_by_role": {COMPLETION_ROLE: [GOAL_ID]},
        "producer_output_subject_ids_by_role": {},
        "impact_closure_goal_ids": [PARENT_GOAL_ID],
        "impact_disposition_by_goal": {
            PARENT_GOAL_ID: {
                "result": "REVALIDATION_REFRESH_REQUIRED",
                "target_status": "READY",
            }
        },
        "reopened_completion_event_sha256_by_goal": {},
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_after),
        "transition_control_review_binding": copy.deepcopy(dict(evidence.review_binding)),
        "source_checkpoint_binding": _binding(CHECKPOINT_REL, source_checkpoint_bytes),
        "source_history_preimage": _source_history_preimage(source),
        "changed_path_preimage": _changed_path_preimage(source),
    }
    update["event_sha256"] = continuation.event_sha256(update)

    completion_by_goal = state["completion_evidence_by_goal"]
    require(GOAL_ID not in completion_by_goal, "source already has completion evidence")
    completion_by_goal[GOAL_ID] = [COMPLETION_ROLE]
    state["status_by_goal"][GOAL_ID] = "COMPLETE_AT_TARGET"
    state["goal_status"] = "READY"
    state["focus_goal_id"] = PARENT_GOAL_ID
    state["focus_goal_path"] = PARENT_GOAL_PATH
    state["focus_work_item_id"] = ""
    state["focus_source"] = "WORKSTREAM_GRAPH"
    state["ready_frontier_goal_ids"] = [
        goal for goal in source_state["ready_frontier_goal_ids"] if goal != GOAL_ID
    ]
    state["pending_producer_completion_goal_id"] = None
    queue100, boundary100 = runtime_deriver(
        root,
        checkpoint,
        [PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID],
    )
    state["artifact_work_queue"] = queue100
    state["completion_boundary"] = boundary100
    completion: dict[str, Any] = {
        "sequence": COMPLETION_SEQUENCE,
        "event_id": COMPLETION_EVENT_ID,
        "event_type": "GOAL_COMPLETED",
        "occurred_on": completion_at.date().isoformat(),
        "occurred_at": completion_at.isoformat(),
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": PARENT_GOAL_ID,
        "focus_goal_content_sha256": PARENT_GOAL_SHA256,
        "subject_goal_id": GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "COMPLETE_AT_TARGET",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {GOAL_ID: "COMPLETE_AT_TARGET"},
        "runtime_after": completion_base._runtime_after(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [COMPLETION_ROLE],
        "previous_event_sha256": update["event_sha256"],
        "canonical_update_event_sha256": update["event_sha256"],
        "completion_receipt_binding": copy.deepcopy(dict(evidence.receipt_binding)),
        "completion_evidence_bindings": {
            COMPLETION_ROLE: copy.deepcopy(dict(evidence.receipt_binding))
        },
        "completion_evidence_by_goal_after": copy.deepcopy(completion_by_goal),
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_after),
    }
    completion["event_sha256"] = continuation.event_sha256(completion)
    state["transition_history"].extend((update, completion))
    state["transition_history_anchor_sha256"] = completion["event_sha256"]
    state["validation_cutoff_at"] = completion["occurred_at"]

    current = checkpoint["current_work"]
    current.update(
        {
            "work_item_id": PARENT_GOAL_ID,
            "title": "EPIC-03 account/admin/security",
            "source_policy_ids": [],
            "gap_ids": [],
            "status": "READY",
            "current_focus": "FP-048 R002/GAP-057 COMPLETE_AT_TARGET; EPIC-03 READY",
            "next_action": "다음 READY 목표는 별도 start gate로 시작한다.",
            "release_completion_claimed": False,
        }
    )
    paths = sorted(set(managed_paths))
    require(
        {SCRIPT_REL.as_posix(), TEST_REL.as_posix()}.issubset(paths)
        and all(isinstance(path, str) and path for path in paths)
        and len(snapshot_hashes) == 2
        and all(re.fullmatch(r"[0-9a-f]{64}", value) for value in snapshot_hashes),
        "completion managed snapshot differs",
    )
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot.update(
        {
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": snapshot_hashes[0],
            "content_set_sha256": snapshot_hashes[1],
            "scope": (
                "Graph v2.4 through FP048 R002 seq100 evidence and seq101 internal "
                "completion; all external/release credit remains zero."
            ),
        }
    )
    handoff = checkpoint["session_handoff"]
    handoff.update(
        {
            "changed_files": paths,
            "current_epic": "EPIC-03 READY / FP-048 R002 COMPLETE_AT_TARGET",
            "last_updated_by_work_item": WORK_ITEM_ID,
            "last_verification_status": (
                "FP048_R002_INTERNAL_109_OF_109_PASS_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
            ),
            "next_single_action": current["next_action"],
        }
    )
    mirror = handoff["source_commit_or_snapshot"]
    mirror["file_count"] = len(paths)
    mirror["path_set_sha256"] = snapshot_hashes[0]
    mirror["content_set_sha256"] = snapshot_hashes[1]
    _require_zero_credit(checkpoint)
    return checkpoint, update, completion


def validate_projection(
    root: Path,
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    evidence: CompletionEvidence,
    *,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]], tuple[dict[str, Any], dict[str, Any]]
    ] = runtime_authority.derive_runtime,
) -> None:
    snapshot = projected.get("working_tree_snapshot")
    require(isinstance(snapshot, dict), "completion snapshot differs")
    expected, _update, _completion = project_seq100_101(
        root,
        source,
        evidence,
        source_checkpoint_bytes=checkpoint_bytes(source),
        managed_paths=snapshot.get("managed_changed_paths", []),
        snapshot_hashes=(snapshot.get("path_set_sha256"), snapshot.get("content_set_sha256")),
        runtime_deriver=runtime_deriver,
    )
    require(strict_equal(projected, expected), "seq100/101 projection differs")


def _verification_semantics(value: Mapping[str, Any]) -> dict[str, Any]:
    result = copy.deepcopy(dict(value))
    result.pop("execution_window", None)
    return result


def _load_review_authority(
    root: Path,
    expected_assignment: Mapping[str, Any],
    *,
    verification_ended_at: datetime,
) -> ReviewAuthority:
    assignment_read = _private_read(root, REVIEW_ASSIGNMENT_REL)
    result_read = _private_read(root, REVIEW_RESULT_REL)
    independent_read = _private_read(root, INDEPENDENT_REVIEW_REL)
    assignment = strict_json(assignment_read.raw, "completion review assignment")
    result = strict_json(result_read.raw, "completion review result")
    independent = strict_json(
        independent_read.raw, "completion independent review"
    )
    require(strict_equal(assignment, expected_assignment), "review assignment differs")
    return validate_review_authority(
        expected_assignment,
        assignment_read.raw,
        result,
        result_read.raw,
        independent,
        independent_read.raw,
        verification_ended_at=verification_ended_at,
    )


def _write_add_only(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    if target.exists() or target.is_symlink():
        observed = _private_read(root, relative)
        require(observed.raw == raw, f"existing add-only output differs: {relative}")
        return
    try:
        frozen_completion._write_add_only(root, relative, raw)
    except frozen_completion.CompletionApplyError as exc:
        raise CompletionApplyError(str(exc)) from exc
    observed = _private_read(root, relative)
    require(observed.raw == raw, f"add-only output differs: {relative}")


def prepare_review_inputs(
    root: Path = ROOT,
    *,
    verification_runner: Callable[[Path], Mapping[str, Any]] = (
        completion_base.run_fresh_verification
    ),
) -> dict[Path, bytes]:
    root = root.resolve(strict=True)
    validate_starter_authority_handshake(root)
    source_read = _private_read(root, CHECKPOINT_REL)
    source = strict_json(source_read.raw, "seq99 source checkpoint")
    require(source_read.raw == checkpoint_bytes(source), "seq99 source bytes differ")
    started.require_started_checkpoint(
        root,
        source,
        require_live_snapshot=True,
        run_external_validators=False,
    )
    product_before = completion_base._verify_product_pins(root)
    verification = copy.deepcopy(dict(verification_runner(root)))
    _require_verification_summary(verification, root=root)
    receipt = build_completion_receipt(root, source, verification)
    receipt_raw = json_bytes(receipt)
    assignment = expected_review_assignment(root, source_read.raw, source, receipt_raw)
    outputs = {
        COMPLETION_RECEIPT_REL: receipt_raw,
        REVIEW_ASSIGNMENT_REL: json_bytes(assignment),
    }
    current = _private_read(root, CHECKPOINT_REL)
    require(
        current.identity == source_read.identity and current.raw == source_read.raw,
        "seq99 source changed during review preparation",
    )
    validate_starter_authority_handshake(root)
    require(
        completion_base._verify_product_pins(root) == product_before,
        "product files changed during review preparation",
    )
    for relative, raw in outputs.items():
        _write_add_only(root, relative, raw)
    current = _private_read(root, CHECKPOINT_REL)
    require(
        current.identity == source_read.identity and current.raw == source_read.raw,
        "seq99 source changed while writing review inputs",
    )
    return outputs


def _capture_publication_inputs(
    root: Path,
    source: Mapping[str, Any],
) -> tuple[
    Mapping[Path, Any], Mapping[Path, Any], Mapping[Path, Any],
    bytes, str, str, list[str], tuple[str, str],
]:
    seq90 = started.seq90
    git_status_raw, git_paths_raw = seq90.capture_git_visible_paths(root)
    git_paths = tuple(path for path in git_paths_raw if path != CHECKPOINT_REL.as_posix())
    git_head, git_branch = seq90._capture_git_context(root)
    reconstructed_seq97_checkpoint_bytes(root, source)
    seq98_raw = started.reconstructed_seq98_checkpoint_bytes(root, source)
    seq98 = strict_json(seq98_raw, "reconstructed seq98 checkpoint")
    start_paths = started._publication_retained_paths(root, seq98)
    required_paths = tuple(
        dict.fromkeys(
            (
                SCRIPT_REL,
                TEST_REL,
                COMPLETION_RECEIPT_REL,
                REVIEW_ASSIGNMENT_REL,
                REVIEW_RESULT_REL,
                INDEPENDENT_REVIEW_REL,
                *STARTER_AUTHORITY_PINS,
                *started.AUTHORITY_PATHS,
                *frozen_completion.CONSUMER_PATHS,
                *PRODUCT_PINS,
                *start_paths,
            )
        )
    )
    retained = {path: seq90._stable_read(root, path) for path in required_paths}
    paths = sorted(
        (
            set(source["working_tree_snapshot"]["managed_changed_paths"])
            | set(git_paths)
            | {path.as_posix() for path in required_paths}
        )
        - {CHECKPOINT_REL.as_posix()}
    )
    managed = seq90._capture_managed_inputs(root, paths)
    git_visible = seq90._capture_managed_inputs(root, git_paths)
    return (
        retained,
        managed,
        git_visible,
        git_status_raw,
        git_head,
        git_branch,
        paths,
        seq90._managed_input_snapshot_hashes(managed),
    )


def _shared_projection_errors(root: Path, checkpoint: dict[str, Any]) -> list[str]:
    continuation_validator = getattr(
        continuation, "validate_fp048_r002_completion_seq100_101", None
    )
    goal_validator = getattr(
        goal_graph, "validate_fp048_r002_completion_seq100_101", None
    )
    require(callable(continuation_validator), "continuation seq100-101 hook is missing")
    require(callable(goal_validator), "GoalGraph seq100-101 hook is missing")
    return [
        *continuation_validator(checkpoint, root=root),
        *goal_validator(root, checkpoint),
    ]


def _require_noncheckpoint_inputs_unchanged(
    prepared: PreparedProjection,
    *,
    phase: str,
) -> None:
    seq90 = started.seq90
    try:
        seq90._require_inputs_unchanged(prepared.root, prepared.retained_inputs)
        seq90._require_managed_inputs_unchanged(
            prepared.root, prepared.managed_inputs, label=f"managed input {phase}"
        )
        seq90._require_managed_inputs_unchanged(
            prepared.root,
            prepared.git_visible_inputs,
            label=f"Git-visible input {phase}",
        )
        seq90._require_git_context(prepared.root, prepared.git_head, prepared.git_branch)
        status_raw, _paths = seq90.capture_git_visible_paths(prepared.root)
        require(status_raw == prepared.git_status_raw, f"Git-visible cohort changed {phase}")
    except Exception as exc:
        raise CompletionApplyError(str(exc)) from exc


def _require_prepared_exact(prepared: PreparedProjection, *, phase: str) -> None:
    seq90 = started.seq90
    source_read = seq90.ReadResult(prepared.source_bytes, prepared.source_identity)
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
        raise CompletionApplyError(str(exc)) from exc
    validate_starter_authority_handshake(prepared.root)
    started.require_started_checkpoint(
        prepared.root,
        prepared.source,
        require_live_snapshot=True,
        run_external_validators=False,
    )
    completion_base._verify_product_pins(prepared.root)
    receipt_read = _private_read(prepared.root, COMPLETION_RECEIPT_REL)
    require(
        receipt_read.raw == prepared.evidence.receipt_bytes,
        "completion receipt changed after preflight",
    )
    _verification_started, verification_ended = _require_verification_summary(
        prepared.evidence.verification, root=prepared.root
    )
    assignment = expected_review_assignment(
        prepared.root,
        prepared.source_bytes,
        prepared.source,
        prepared.evidence.receipt_bytes,
    )
    review = _load_review_authority(
        prepared.root,
        assignment,
        verification_ended_at=verification_ended,
    )
    require(
        strict_equal(review.binding, prepared.evidence.review_binding)
        and review.latest_review_at == prepared.evidence.latest_authority_at,
        "completion review changed after preflight",
    )
    snapshot = prepared.projected["working_tree_snapshot"]
    paths = sorted(path.as_posix() for path in prepared.managed_inputs)
    hashes = seq90._managed_input_snapshot_hashes(prepared.managed_inputs)
    require(
        paths == snapshot["managed_changed_paths"]
        and hashes == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
        "completion managed snapshot differs",
    )
    require(
        prepared.projected_bytes == checkpoint_bytes(prepared.projected),
        "prepared completion bytes differ",
    )
    validate_projection(
        prepared.root, prepared.source, prepared.projected, prepared.evidence
    )
    errors = _shared_projection_errors(prepared.root, dict(prepared.projected))
    require(not errors, "completion consumer validation failed: " + " | ".join(errors))


def prepare_projection(
    root: Path = ROOT,
    *,
    verification_runner: Callable[[Path], Mapping[str, Any]] = (
        completion_base.run_fresh_verification
    ),
) -> PreparedProjection:
    root = root.resolve(strict=True)
    validate_starter_authority_handshake(root)
    source_read = _private_read(root, CHECKPOINT_REL)
    source = strict_json(source_read.raw, "seq99 source checkpoint")
    require(source_read.raw == checkpoint_bytes(source), "seq99 source bytes differ")
    started.require_started_checkpoint(
        root,
        source,
        require_live_snapshot=True,
        run_external_validators=False,
    )
    product_before = completion_base._verify_product_pins(root)
    fresh = copy.deepcopy(dict(verification_runner(root)))
    _require_verification_summary(fresh, root=root)
    current = _private_read(root, CHECKPOINT_REL)
    require(
        current.identity == source_read.identity and current.raw == source_read.raw,
        "seq99 source changed during fresh verification",
    )
    require(
        completion_base._verify_product_pins(root) == product_before,
        "product files changed during fresh verification",
    )
    receipt_read = _private_read(root, COMPLETION_RECEIPT_REL)
    receipt = strict_json(receipt_read.raw, "completion receipt")
    require(receipt_read.raw == json_bytes(receipt), "completion receipt is not canonical")
    recorded = receipt.get("verification_evidence")
    require(type(recorded) is dict, "recorded verification evidence differs")
    _recorded_started, recorded_ended = _require_verification_summary(
        recorded, root=root
    )
    require(
        strict_equal(_verification_semantics(fresh), _verification_semantics(recorded)),
        "fresh verification semantics differ from reviewed receipt",
    )
    require(
        receipt_read.raw == json_bytes(build_completion_receipt(root, source, recorded)),
        "completion receipt bytes differ",
    )
    assignment = expected_review_assignment(
        root, source_read.raw, source, receipt_read.raw
    )
    review = _load_review_authority(
        root, assignment, verification_ended_at=recorded_ended
    )
    evidence = CompletionEvidence(
        verification=recorded,
        receipt=receipt,
        receipt_bytes=receipt_read.raw,
        receipt_binding=_receipt_binding(receipt_read.raw),
        review_binding=review.binding,
        latest_authority_at=review.latest_review_at,
    )
    (
        retained,
        managed,
        git_visible,
        git_status_raw,
        git_head,
        git_branch,
        paths,
        hashes,
    ) = _capture_publication_inputs(root, source)
    projected, update, completed = project_seq100_101(
        root,
        source,
        evidence,
        source_checkpoint_bytes=source_read.raw,
        managed_paths=paths,
        snapshot_hashes=hashes,
    )
    validate_projection(root, source, projected, evidence)
    errors = _shared_projection_errors(root, projected)
    require(not errors, "projected consumer validation failed: " + " | ".join(errors))
    prepared = PreparedProjection(
        root=root,
        source_bytes=source_read.raw,
        source=source,
        projected=projected,
        projected_bytes=checkpoint_bytes(projected),
        evidence_event=update,
        completion_event=completed,
        evidence=evidence,
        source_identity=source_read.identity,
        retained_inputs=retained,
        managed_inputs=managed,
        git_visible_inputs=git_visible,
        git_status_raw=git_status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    _require_prepared_exact(prepared, phase="during completion preflight")
    return prepared


def _terminal_validate(prepared: PreparedProjection) -> None:
    published_read = _private_read(prepared.root, CHECKPOINT_REL)
    require(
        published_read.raw == prepared.projected_bytes,
        "published completion bytes differ",
    )
    published = strict_json(published_read.raw, "published seq101 checkpoint")
    require(
        published_read.raw == checkpoint_bytes(published)
        and strict_equal(published, prepared.projected),
        "published completion object differs",
    )
    require(
        reconstructed_seq99_checkpoint_bytes(prepared.root, published)
        == prepared.source_bytes,
        "published completion inverse source differs",
    )
    validate_projection(
        prepared.root, prepared.source, published, prepared.evidence
    )
    errors = _shared_projection_errors(prepared.root, published)
    require(not errors, "terminal consumer validation failed: " + " | ".join(errors))
    _require_noncheckpoint_inputs_unchanged(prepared, phase="after publication")
    terminal = _private_read(prepared.root, CHECKPOINT_REL)
    require(
        terminal.identity == published_read.identity and terminal.raw == published_read.raw,
        "published completion changed during terminal validation",
    )


def write_projection(
    prepared: PreparedProjection,
    *,
    writer: Callable[..., None] = started.seq90.write_checkpoint,
) -> None:
    current = _private_read(prepared.root, CHECKPOINT_REL)
    if current.raw == prepared.projected_bytes:
        _terminal_validate(prepared)
        return
    require(
        current.identity == prepared.source_identity
        and current.raw == prepared.source_bytes,
        "seq99 source changed before completion publication",
    )
    _require_prepared_exact(prepared, phase="before completion publication")
    transport = started.seq90.Prepared(
        root=prepared.root,
        source_raw=prepared.source_bytes,
        source_identity=prepared.source_identity,
        source=prepared.source,
        projected=prepared.projected,
        projected_raw=prepared.projected_bytes,
        event=prepared.completion_event,
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
            commit_guard=lambda: _require_prepared_exact(
                prepared, phase="at completion commit point"
            ),
        )
    except started.seq90.PostcommitUncertain:
        raise
    except BaseException as exc:
        observed = _private_read(prepared.root, CHECKPOINT_REL)
        if observed.raw == prepared.projected_bytes:
            raise started.seq90.PostcommitUncertain(
                "completion writer failed after publishing exact bytes"
            ) from exc
        raise
    try:
        _terminal_validate(prepared)
    except BaseException as exc:
        raise started.seq90.PostcommitUncertain(
            "completion publication committed but terminal authority is uncertain"
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
            outputs = prepare_review_inputs(args.root)
            print(
                "FP048 R002 seq100-101 review preparation: PASS "
                + " ".join(
                    f"{path.as_posix()}={sha256_bytes(raw)}"
                    for path, raw in outputs.items()
                )
            )
            return 0
        prepared = prepare_projection(args.root)
        mode = "PREFLIGHT"
        if args.write:
            write_projection(prepared)
            mode = "WRITE"
    except started.seq90.PostcommitUncertain as exc:
        print(f"FP048 R002 seq100-101 completion: UNCERTAIN: {exc}", file=sys.stderr)
        return 2
    except (CompletionApplyError, started.StartApplyError, OSError, TypeError, ValueError) as exc:
        print(f"FP048 R002 seq100-101 completion: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "FP048 R002 seq100-101 completion: PASS "
        f"mode={mode} evidence_sha256={prepared.evidence_event['event_sha256']} "
        f"completion_sha256={prepared.completion_event['event_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
