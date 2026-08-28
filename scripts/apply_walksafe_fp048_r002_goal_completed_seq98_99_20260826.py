#!/usr/bin/env python3
"""Prepare or append the FP-048 R002 evidence/completion seq98-99 pair."""

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

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as cas
from scripts import apply_walksafe_fp046_goal_completed_seq54_55_20260810 as runtime_authority
from scripts import apply_walksafe_fp048_r002_goal_started_seq97_20260826 as started
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as continuation


CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
SOURCE_SEQUENCE = 97
EVIDENCE_SEQUENCE = 98
COMPLETION_SEQUENCE = 99
SOURCE_EVENT_ID = started.EVENT_ID
EVIDENCE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-"
    "FP048-R002-20260826-001"
)
COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP048-R002-20260826-001"
)

GOAL_ID = started.gate.TARGET_GOAL_ID
GOAL_PATH = started.correction.GOAL_PATH.as_posix()
GOAL_SHA256 = started.TARGET_GOAL_SHA256
WORK_ITEM_ID = started.correction.WORK_ITEM_ID
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PARENT_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
PARENT_GOAL_SHA256 = (
    "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
)
EPIC04_GOAL_ID = "WS-GOAL-EPIC-04"
EPIC12_GOAL_ID = "WS-GOAL-EPIC-12"
MANIFEST_SHA256 = started.gate.MANIFEST_SHA256

SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_completed_seq98_99_20260826.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_completed_seq98_99_20260826.py"
)
CONSUMER_PATHS = (
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
)
PRODUCT_SOURCE_REL = Path(
    "apps/android-gateway/src/state-encryption-maintenance.ts"
)
PRODUCT_TEST_REL = Path(
    "apps/android-gateway/test/state-encryption-maintenance.test.ts"
)
PRODUCT_PINS = {
    PRODUCT_SOURCE_REL: (
        "231a40b684a7fb3b5ab4a61b93fe2531552504b15448ed6840013f254ccb1184",
        18_311,
    ),
    PRODUCT_TEST_REL: (
        "eac9e46c24983c6df91d7b67ce8e3cbdde00797d2fc528fb54577610e27f3f52",
        17_531,
    ),
}

LOCKED_NODE_ROOT = Path(
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "node-v22.23.1-linux-x64"
)
LOCKED_NODE = LOCKED_NODE_ROOT / "bin/node"
LOCKED_NPM = LOCKED_NODE_ROOT / "bin/npm"
LOCKED_NODE_VERSION = "v22.23.1"
LOCKED_NPM_VERSION = "10.9.8"
GATEWAY_REL = Path("apps/android-gateway")
FOCUSED_TEST_REL = Path("dist/test/state-encryption-maintenance.test.js")

COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
COMPLETION_DOCUMENT_ID = (
    "WS-FP048-R002-ENCRYPTION-CONNECTION-SECURITY-WORK-ITEM-"
    "COMPLETION-20260826-001"
)
COMPLETION_RECEIPT_REL = (
    Path("docs/control/execution/goal-results")
    / GOAL_ID
    / "completion-receipt.json"
)
REVIEW_DIR = Path(
    "docs/control/execution/workstream-transitions/"
    "seq98-99/review-rounds/R001"
)
REVIEW_ASSIGNMENT_REL = REVIEW_DIR / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_DIR / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_DIR / "independent-review.json"
REVIEW_PATHS = (
    REVIEW_ASSIGNMENT_REL,
    REVIEW_RESULT_REL,
    INDEPENDENT_REVIEW_REL,
)

ZERO_CREDIT_BOUNDARY = {
    "formal_test_status": "NOT_RUN",
    "actual_device_status": "NOT_RUN",
    "external_review_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "release_status": "NOT_ELIGIBLE",
    "formal_test_credit_delta": 0,
    "device_credit_delta": 0,
    "external_credit_delta": 0,
    "deployment_credit_delta": 0,
    "release_credit_delta": 0,
}

EVIDENCE_FIELDS = {
    "sequence", "event_id", "event_type", "occurred_on", "occurred_at",
    "previous_focus_goal_id", "previous_focus_content_sha256", "focus_goal_id",
    "focus_goal_content_sha256", "from_status", "to_status",
    "static_plan_manifest_sha256", "status_changes", "runtime_after",
    "blockers_after", "blocker_resolution_ids_after", "source_checkpoint_version",
    "evidence_refs", "previous_event_sha256", "produced_by_goal_id",
    "produced_binding_roles", "producer_completion_receipt_binding",
    "changed_binding_roles", "changed_subject_ids_by_role",
    "producer_output_subject_ids_by_role", "impact_closure_goal_ids",
    "impact_disposition_by_goal", "reopened_completion_event_sha256_by_goal",
    "canonical_binding_snapshot_after", "transition_control_review_binding",
    "source_checkpoint_binding", "source_history_preimage",
    "changed_path_preimage",
    "event_sha256",
}
COMPLETION_FIELDS = {
    "sequence", "event_id", "event_type", "occurred_on", "occurred_at",
    "previous_focus_goal_id", "previous_focus_content_sha256", "focus_goal_id",
    "focus_goal_content_sha256", "subject_goal_id", "from_status", "to_status",
    "static_plan_manifest_sha256", "status_changes", "runtime_after",
    "blockers_after", "blocker_resolution_ids_after", "source_checkpoint_version",
    "evidence_refs", "previous_event_sha256", "canonical_update_event_sha256",
    "completion_receipt_binding", "completion_evidence_bindings",
    "completion_evidence_by_goal_after", "canonical_binding_snapshot_after",
    "event_sha256",
}

CHANGED_PATH_PREIMAGE_PATHS = (
    "canonical_bindings",
    "goal_execution.transition_history_anchor_sha256",
    "goal_execution.validation_cutoff_at",
    "goal_execution.goal_status",
    "goal_execution.status_by_goal",
    "goal_execution.focus_goal_id",
    "goal_execution.focus_goal_path",
    "goal_execution.focus_work_item_id",
    "goal_execution.focus_source",
    "goal_execution.ready_frontier_goal_ids",
    "goal_execution.pending_producer_completion_goal_id",
    "goal_execution.completion_evidence_by_goal",
    "goal_execution.artifact_work_queue",
    "goal_execution.completion_boundary",
    "current_work",
    "working_tree_snapshot",
    "session_handoff",
)


class CompletionApplyError(RuntimeError):
    """The reviewed internal-only FP-048 R002 completion is not exact."""


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
    evidence: CompletionEvidence
    evidence_event: Mapping[str, Any]
    completion_event: Mapping[str, Any]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CompletionApplyError(message)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def json_bytes(value: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")


def checkpoint_bytes(value: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")


def _strict_equal(left: Any, right: Any) -> bool:
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
        raise CompletionApplyError(f"invalid JSON: {label}") from exc
    require(type(value) is dict, f"JSON root differs: {label}")
    return value


def _safe_regular_bytes(
    root: Path,
    relative: Path,
    *,
    private: bool = False,
) -> bytes:
    require(not relative.is_absolute() and ".." not in relative.parts, "unsafe path")
    root = root.resolve(strict=True)
    current = root
    for part in relative.parts:
        current /= part
        require(not current.is_symlink(), f"symlink is not allowed: {relative}")
    require(current.is_file(), f"file is missing: {relative}")
    info = current.lstat()
    require(
        stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
        f"regular-file authority differs: {relative}",
    )
    if private:
        require(
            stat.S_IMODE(info.st_mode) == 0o600,
            f"private mode differs: {relative}",
        )
    return current.read_bytes()


def _binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
    }


def _receipt_binding(raw: bytes) -> dict[str, Any]:
    return {
        "role": COMPLETION_ROLE,
        "document_id": COMPLETION_DOCUMENT_ID,
        "path": COMPLETION_RECEIPT_REL.as_posix(),
        "file_sha256": sha256_bytes(raw),
    }


def _verify_product_pins(root: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for relative, expected in PRODUCT_PINS.items():
        raw = _safe_regular_bytes(root, relative)
        observed = (sha256_bytes(raw), len(raw))
        require(observed == expected, f"FP048 product pin differs: {relative}")
        rows.append(
            {
                "path": relative.as_posix(),
                "sha256": observed[0],
                "byte_length": observed[1],
            }
        )
    return rows


def _run_command(
    argv: Sequence[str],
    *,
    cwd: Path,
    env: Mapping[str, str],
    timeout: int = 180,
) -> bytes:
    completed = subprocess.run(
        list(argv),
        cwd=cwd,
        env=dict(env),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        timeout=timeout,
    )
    require(
        completed.returncode == 0,
        f"verification command failed ({completed.returncode}): {' '.join(argv)}",
    )
    return completed.stdout


def _version(argv: Sequence[str], *, cwd: Path, env: Mapping[str, str]) -> str:
    output = _run_command(argv, cwd=cwd, env=env, timeout=30)
    try:
        return output.decode("utf-8").strip()
    except UnicodeError as exc:
        raise CompletionApplyError("toolchain version output is not UTF-8") from exc


def _tap_counts(output: bytes, *, label: str) -> dict[str, int]:
    try:
        text = output.decode("utf-8")
    except UnicodeError as exc:
        raise CompletionApplyError(f"{label} TAP output is not UTF-8") from exc
    values: dict[str, int] = {}
    for key in ("tests", "pass", "fail", "cancelled", "skipped", "todo"):
        matches = re.findall(rf"(?m)^# {key} (\d+)$", text)
        require(len(matches) == 1, f"{label} TAP {key} count differs")
        values[key] = int(matches[0])
    return values


def run_fresh_verification(root: Path) -> dict[str, Any]:
    root = root.resolve(strict=True)
    gateway = root / GATEWAY_REL
    require(gateway.is_dir(), "android-gateway directory is missing")
    require(LOCKED_NODE.is_file() and LOCKED_NPM.exists(), "locked Node toolchain is missing")
    env = dict(os.environ)
    env["PATH"] = f"{LOCKED_NODE_ROOT / 'bin'}:{env.get('PATH', '')}"
    env["NO_COLOR"] = "1"
    started_at = datetime.now().astimezone().replace(microsecond=0)
    node_version = _version((str(LOCKED_NODE), "--version"), cwd=gateway, env=env)
    npm_version = _version((str(LOCKED_NPM), "--version"), cwd=gateway, env=env)
    require(node_version == LOCKED_NODE_VERSION, "locked Node version differs")
    require(npm_version == LOCKED_NPM_VERSION, "locked npm version differs")

    typecheck = (str(LOCKED_NPM), "run", "typecheck", "--silent")
    build = (str(LOCKED_NPM), "run", "build", "--silent")
    _run_command(typecheck, cwd=gateway, env=env)
    _run_command(build, cwd=gateway, env=env)
    focused = (str(LOCKED_NODE), "--test", FOCUSED_TEST_REL.as_posix())
    focused_counts = _tap_counts(
        _run_command(focused, cwd=gateway, env=env),
        label="focused",
    )
    test_files = tuple(
        path.relative_to(gateway).as_posix()
        for path in sorted((gateway / "dist/test").glob("*.test.js"))
    )
    require(test_files, "compiled gateway tests are missing")
    full = (str(LOCKED_NODE), "--test", *test_files)
    full_counts = _tap_counts(
        _run_command(full, cwd=gateway, env=env),
        label="gateway full",
    )
    require(
        focused_counts
        == {
            "tests": 13,
            "pass": 13,
            "fail": 0,
            "cancelled": 0,
            "skipped": 0,
            "todo": 0,
        },
        "focused result is not exact 13/13",
    )
    require(
        full_counts
        == {
            "tests": 109,
            "pass": 109,
            "fail": 0,
            "cancelled": 0,
            "skipped": 0,
            "todo": 0,
        },
        "gateway result is not exact 109/109",
    )
    ended_at = datetime.now().astimezone().replace(microsecond=0)
    return {
        "claim_scope": "REPOSITORY_INTERNAL_FRESH_VERIFICATION_ONLY",
        "execution_window": {
            "started_at": started_at.isoformat(),
            "ended_at": ended_at.isoformat(),
        },
        "toolchain": {
            "node_path": str(LOCKED_NODE),
            "node_version": node_version,
            "npm_path": str(LOCKED_NPM),
            "npm_version": npm_version,
        },
        "checks": [
            {"check_id": "TYPECHECK", "argv": list(typecheck), "status": "PASS"},
            {"check_id": "BUILD", "argv": list(build), "status": "PASS"},
            {
                "check_id": "FOCUSED_STATE_ENCRYPTION_MAINTENANCE",
                "argv": list(focused),
                "status": "PASS",
                "tap": focused_counts,
            },
            {
                "check_id": "ANDROID_GATEWAY_FULL",
                "argv": list(full),
                "status": "PASS",
                "tap": full_counts,
            },
        ],
    }


def _verification_semantics(value: Mapping[str, Any]) -> dict[str, Any]:
    result = copy.deepcopy(dict(value))
    result.pop("execution_window", None)
    return result


def _require_verification_summary(value: Mapping[str, Any]) -> tuple[datetime, datetime]:
    require(
        value.get("claim_scope")
        == "REPOSITORY_INTERNAL_FRESH_VERIFICATION_ONLY",
        "verification claim scope differs",
    )
    toolchain = value.get("toolchain")
    require(
        isinstance(toolchain, dict)
        and toolchain.get("node_path") == str(LOCKED_NODE)
        and toolchain.get("node_version") == LOCKED_NODE_VERSION
        and toolchain.get("npm_path") == str(LOCKED_NPM)
        and toolchain.get("npm_version") == LOCKED_NPM_VERSION,
        "verification toolchain differs",
    )
    window = value.get("execution_window")
    require(isinstance(window, dict), "verification execution window differs")
    started_at = _parse_time(window.get("started_at"), label="verification started_at")
    ended_at = _parse_time(window.get("ended_at"), label="verification ended_at")
    require(ended_at >= started_at, "verification chronology differs")
    checks = value.get("checks")
    require(isinstance(checks, list), "verification checks differ")
    by_id = {
        row.get("check_id"): row
        for row in checks
        if isinstance(row, dict) and isinstance(row.get("check_id"), str)
    }
    focused = {
        "tests": 13,
        "pass": 13,
        "fail": 0,
        "cancelled": 0,
        "skipped": 0,
        "todo": 0,
    }
    full = {**focused, "tests": 109, "pass": 109}
    require(
        len(by_id) == len(checks) == 4
        and set(by_id)
        == {
            "TYPECHECK",
            "BUILD",
            "FOCUSED_STATE_ENCRYPTION_MAINTENANCE",
            "ANDROID_GATEWAY_FULL",
        }
        and by_id["TYPECHECK"].get("status") == "PASS"
        and by_id["BUILD"].get("status") == "PASS"
        and _strict_equal(
            by_id["FOCUSED_STATE_ENCRYPTION_MAINTENANCE"].get("tap"),
            focused,
        )
        and _strict_equal(by_id["ANDROID_GATEWAY_FULL"].get("tap"), full),
        "verification counts differ",
    )
    return started_at, ended_at


def _require_source_shape(source: Mapping[str, Any]) -> None:
    require(source.get("schema_version") == "1.25.0", "source schema differs")
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == SOURCE_SEQUENCE,
        "source is not exact seq97",
    )
    event = history[-1]
    require(
        isinstance(event, dict)
        and type(event.get("sequence")) is int
        and event.get("sequence") == SOURCE_SEQUENCE
        and event.get("event_id") == SOURCE_EVENT_ID
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("subject_goal_id") == GOAL_ID
        and event.get("from_status") == "READY"
        and event.get("to_status") == "IN_PROGRESS"
        and event.get("event_sha256") == continuation.event_sha256(event),
        "source seq97 event differs",
    )
    statuses = state.get("status_by_goal")
    require(
        isinstance(statuses, dict)
        and statuses.get(GOAL_ID) == "IN_PROGRESS"
        and list(statuses.values()).count("IN_PROGRESS") == 1
        and state.get("goal_status") == "IN_PROGRESS"
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH
        and state.get("focus_work_item_id") == WORK_ITEM_ID
        and state.get("ready_frontier_goal_ids")
        == [GOAL_ID, PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID]
        and state.get("pending_producer_completion_goal_id") in {None, ""},
        "source FP048 R002 runtime differs",
    )
    current = source.get("current_work")
    approved = source.get("approved_state")
    verification = source.get("verification_boundary")
    require(
        isinstance(current, dict)
        and current.get("work_item_id") == WORK_ITEM_ID
        and current.get("status") == "IN_PROGRESS"
        and current.get("release_completion_claimed") is False,
        "source current-work boundary differs",
    )
    require(
        isinstance(approved, dict)
        and type(approved.get("formal_test_count")) is int
        and type(approved.get("formal_test_not_run_count")) is int
        and type(approved.get("remaining_gate_count")) is int
        and approved.get("formal_test_count")
        == approved.get("formal_test_not_run_count") == 279
        and approved.get("remaining_gate_count") == 5
        and approved.get("remaining_gates_waived") is False
        and approved.get("release_status") == "NOT_ELIGIBLE",
        "source approved zero-credit boundary differs",
    )
    require(
        isinstance(verification, dict)
        and verification.get("actual_device_test_status") == "NOT_RUN"
        and verification.get("all_remaining_gate_status") == "NOT_RUN"
        and verification.get("formal_test_pass_claimed") is False
        and verification.get("implementation_conformance_claimed") is False
        and verification.get("release_eligible") is False,
        "source verification zero-credit boundary differs",
    )


def require_exact_source(root: Path, source: dict[str, Any]) -> None:
    _require_source_shape(source)
    try:
        started.require_started_checkpoint(
            root,
            source,
            run_external_validators=False,
            require_live_snapshot=False,
        )
    except Exception as exc:
        raise CompletionApplyError(f"source seq97 authority differs: {exc}") from exc


def build_completion_receipt(
    root: Path,
    source: Mapping[str, Any],
    verification: Mapping[str, Any],
) -> dict[str, Any]:
    _require_source_shape(source)
    product_files = _verify_product_pins(root)
    _started_at, verification_ended_at = _require_verification_summary(verification)
    tail = source["goal_execution"]["transition_history"][-1]
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
        "generated_at": verification_ended_at.isoformat(),
        "completion_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
    }


def expected_review_assignment(
    root: Path,
    source_bytes: bytes,
    source: Mapping[str, Any],
    receipt_bytes: bytes,
) -> dict[str, Any]:
    _require_source_shape(source)
    tail = source["goal_execution"]["transition_history"][-1]
    producer_bindings = [
        _binding(relative, _safe_regular_bytes(root, relative))
        for relative in (SCRIPT_REL, TEST_REL)
    ]
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP048-R002-SEQ98-99-REVIEW-ASSIGNMENT-20260826-R001",
        "goal_id": GOAL_ID,
        "round_id": "R001",
        "source_checkpoint_binding": {
            **_binding(CHECKPOINT_REL, source_bytes),
            "sequence": SOURCE_SEQUENCE,
            "tail_event_id": tail["event_id"],
            "tail_event_sha256": tail["event_sha256"],
        },
        "completion_receipt_binding": _binding(
            COMPLETION_RECEIPT_REL, receipt_bytes
        ),
        "producer_bindings": producer_bindings,
        "consumer_bindings": [
            _binding(relative, _safe_regular_bytes(root, relative))
            for relative in CONSUMER_PATHS
        ],
        "product_bindings": _verify_product_pins(root),
        "projected_transition": {
            "evidence_sequence": EVIDENCE_SEQUENCE,
            "evidence_event_id": EVIDENCE_EVENT_ID,
            "completion_sequence": COMPLETION_SEQUENCE,
            "completion_event_id": COMPLETION_EVENT_ID,
            "status_change": {GOAL_ID: "IN_PROGRESS_TO_COMPLETE_AT_TARGET"},
        },
        "review_scope": [
            "exact seq97 GOAL_STARTED source authority",
            "two pinned FP048 product files",
            "fresh locked-Node typecheck, build, focused 13/13 and gateway 109/109",
            "seq98 canonical completion-evidence binding with zero credit",
            "adjacent seq99 internal GOAL_COMPLETED projection",
            "formal, device, external, deployment and release credit remain zero",
        ],
        "required_decision": "APPROVE_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY",
        "claim_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
    }


def _load_review_authority(
    root: Path,
    expected_assignment: Mapping[str, Any],
    *,
    not_before: datetime,
) -> ReviewAuthority:
    assignment_raw = _safe_regular_bytes(root, REVIEW_ASSIGNMENT_REL, private=True)
    require(
        assignment_raw == json_bytes(expected_assignment),
        "review assignment bytes differ",
    )
    assignment_binding = _binding(REVIEW_ASSIGNMENT_REL, assignment_raw)
    result_raw = _safe_regular_bytes(root, REVIEW_RESULT_REL, private=True)
    result = strict_json(result_raw, "review result")
    require(result_raw == json_bytes(result), "review result is not canonical")
    reviewer = result.get("reviewer")
    result_reviewed_at = _parse_time(
        result.get("reviewed_at"), label="review result reviewed_at"
    )
    require(
        set(result)
        == {
            "schema_version",
            "document_id",
            "goal_id",
            "round_id",
            "assignment_binding",
            "reviewer",
            "reviewed_at",
            "decision",
            "findings",
            "external_independence_claimed",
            "claim_boundary",
        }
        and result.get("schema_version") == "1.0"
        and result.get("document_id")
        == "WS-FP048-R002-SEQ98-99-REVIEW-RESULT-20260826-R001"
        and result.get("goal_id") == GOAL_ID
        and result.get("round_id") == "R001"
        and _strict_equal(result.get("assignment_binding"), assignment_binding)
        and result.get("decision")
        == "APPROVE_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY"
        and _strict_equal(result.get("findings"), [])
        and isinstance(reviewer, dict)
        and set(reviewer) == {"id", "task_id"}
        and isinstance(reviewer.get("id"), str)
        and isinstance(reviewer.get("task_id"), str)
        and reviewer["id"]
        and reviewer["task_id"]
        and result.get("external_independence_claimed") is False
        and _strict_equal(result.get("claim_boundary"), ZERO_CREDIT_BOUNDARY),
        "review result authority differs",
    )
    require(result_reviewed_at >= not_before, "review result predates verification")
    result_binding = _binding(REVIEW_RESULT_REL, result_raw)
    independent_raw = _safe_regular_bytes(
        root, INDEPENDENT_REVIEW_REL, private=True
    )
    independent = strict_json(independent_raw, "independent review")
    require(
        independent_raw == json_bytes(independent),
        "independent review is not canonical",
    )
    independent_reviewer = independent.get("reviewer")
    independent_reviewed_at = _parse_time(
        independent.get("reviewed_at"), label="independent review reviewed_at"
    )
    require(
        set(independent)
        == {
            "schema_version",
            "document_id",
            "goal_id",
            "round_id",
            "assignment_binding",
            "review_result_binding",
            "reviewer",
            "reviewed_at",
            "decision",
            "findings",
            "external_independence_claimed",
            "claim_boundary",
        }
        and independent.get("schema_version") == "1.0"
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ98-99-INDEPENDENT-REVIEW-20260826-R001"
        and independent.get("goal_id") == GOAL_ID
        and independent.get("round_id") == "R001"
        and _strict_equal(
            independent.get("assignment_binding"), assignment_binding
        )
        and _strict_equal(
            independent.get("review_result_binding"), result_binding
        )
        and independent.get("decision")
        == "CONCUR_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY"
        and _strict_equal(independent.get("findings"), [])
        and isinstance(independent_reviewer, dict)
        and set(independent_reviewer) == {"id", "task_id"}
        and isinstance(independent_reviewer.get("id"), str)
        and isinstance(independent_reviewer.get("task_id"), str)
        and independent_reviewer["id"]
        and independent_reviewer["task_id"]
        and independent_reviewer["id"] != reviewer["id"]
        and independent_reviewer["task_id"] != reviewer["task_id"]
        and independent.get("external_independence_claimed") is False
        and _strict_equal(
            independent.get("claim_boundary"), ZERO_CREDIT_BOUNDARY
        ),
        "independent review authority differs",
    )
    require(
        independent_reviewed_at >= result_reviewed_at,
        "independent review predates review result",
    )
    return ReviewAuthority(
        binding={
            "assignment": assignment_binding,
            "review_result": result_binding,
            "independent_review": _binding(
                INDEPENDENT_REVIEW_REL, independent_raw
            ),
        },
        latest_review_at=independent_reviewed_at.isoformat(),
    )


def _canonical_binding_snapshot(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    return continuation.canonical_binding_snapshot(checkpoint)


def _add_completion_binding(
    source: Mapping[str, Any], receipt_binding: Mapping[str, Any]
) -> list[dict[str, Any]]:
    bindings = copy.deepcopy(source.get("canonical_bindings"))
    require(isinstance(bindings, list), "source canonical bindings differ")
    require(
        all(
            isinstance(row, dict) and row.get("role") != COMPLETION_ROLE
            for row in bindings
        ),
        "source already has FP048 R002 completion binding",
    )
    bindings.append(
        {
            **copy.deepcopy(dict(receipt_binding)),
            "identity_json_path": "document_id",
            "mutable": False,
        }
    )
    return sorted(bindings, key=lambda row: str(row.get("role", "")))


def _runtime_after(state: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "focus_goal_id": state["focus_goal_id"],
        "focus_goal_path": state["focus_goal_path"],
        "focus_work_item_id": state["focus_work_item_id"],
        "focus_source": state["focus_source"],
        "ready_frontier_goal_ids": copy.deepcopy(state["ready_frontier_goal_ids"]),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": continuation.canonical_json_sha256(
            state["artifact_work_queue"]
        ),
        "completion_boundary_sha256": continuation.canonical_json_sha256(
            state["completion_boundary"]
        ),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def _path_value(source: Mapping[str, Any], dotted_path: str) -> Any:
    current: Any = source
    for component in dotted_path.split("."):
        require(
            isinstance(current, Mapping) and component in current,
            f"source preimage path is missing: {dotted_path}",
        )
        current = current[component]
    return current


def _changed_path_preimage(source: Mapping[str, Any]) -> dict[str, Any]:
    return {
        path: copy.deepcopy(_path_value(source, path))
        for path in CHANGED_PATH_PREIMAGE_PATHS
    }


def _source_history_preimage(source: Mapping[str, Any]) -> dict[str, Any]:
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, Mapping) else None
    require(
        isinstance(history, list)
        and len(history) == SOURCE_SEQUENCE
        and isinstance(history[-1], dict),
        "source history preimage differs",
    )
    return {
        "length": SOURCE_SEQUENCE,
        "tail_event_sha256": history[-1].get("event_sha256"),
    }


def _restore_changed_path_preimage(
    projected: Mapping[str, Any], preimage: Mapping[str, Any]
) -> dict[str, Any]:
    restored = copy.deepcopy(dict(projected))
    for dotted_path in CHANGED_PATH_PREIMAGE_PATHS:
        components = dotted_path.split(".")
        current: Any = restored
        for component in components[:-1]:
            require(
                type(current) is dict and type(current.get(component)) is dict,
                f"inverse parent path differs: {dotted_path}",
            )
            current = current[component]
        require(type(current) is dict, f"inverse target path differs: {dotted_path}")
        current[components[-1]] = copy.deepcopy(preimage[dotted_path])
    return restored


def reconstructed_seq97_checkpoint_bytes(
    root: Path,
    projected: Mapping[str, Any],
) -> bytes:
    """Return the canonical exact seq97 source restored from exact seq99."""

    root = root.resolve(strict=True)
    state = projected.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, Mapping) else None
    require(isinstance(history, list), "inverse transition history differs")
    if len(history) == SOURCE_SEQUENCE:
        source = copy.deepcopy(dict(projected))
        source_bytes = checkpoint_bytes(source)
        require_exact_source(root, source)
        return source_bytes
    require(
        len(history) != EVIDENCE_SEQUENCE,
        "seq98 producer transaction lacks adjacent seq99 completion",
    )
    require(
        len(history) == COMPLETION_SEQUENCE,
        "inverse source requires exact seq97 or seq99",
    )
    update, completion = history[-2:]
    require(
        isinstance(update, dict)
        and isinstance(completion, dict)
        and set(update) == EVIDENCE_FIELDS
        and set(completion) == COMPLETION_FIELDS,
        "seq98/99 inverse suffix field set differs",
    )
    source_history = history[:SOURCE_SEQUENCE]
    source_tail = source_history[-1]
    require(
        isinstance(source_tail, dict)
        and type(update.get("sequence")) is int
        and update.get("sequence") == EVIDENCE_SEQUENCE
        and update.get("event_id") == EVIDENCE_EVENT_ID
        and update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and type(completion.get("sequence")) is int
        and completion.get("sequence") == COMPLETION_SEQUENCE
        and completion.get("event_id") == COMPLETION_EVENT_ID
        and completion.get("event_type") == "GOAL_COMPLETED"
        and update.get("previous_event_sha256") == source_tail.get("event_sha256")
        and completion.get("previous_event_sha256") == update.get("event_sha256")
        and completion.get("canonical_update_event_sha256")
        == update.get("event_sha256")
        and update.get("event_sha256") == continuation.event_sha256(update)
        and completion.get("event_sha256")
        == continuation.event_sha256(completion)
        and state.get("transition_history_anchor_sha256")
        == completion.get("event_sha256")
        and state.get("validation_cutoff_at") == completion.get("occurred_at"),
        "seq98/99 inverse suffix authority differs",
    )
    expected_history_preimage = {
        "length": SOURCE_SEQUENCE,
        "tail_event_sha256": source_tail.get("event_sha256"),
    }
    require(
        _strict_equal(
            update.get("source_history_preimage"), expected_history_preimage
        ),
        "source history preimage differs",
    )
    preimage = update.get("changed_path_preimage")
    require(
        type(preimage) is dict
        and set(preimage) == set(CHANGED_PATH_PREIMAGE_PATHS),
        "changed path preimage field set differs",
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
    restored_state = restored["goal_execution"]
    restored_state["transition_history"] = copy.deepcopy(source_history)
    restored_bytes = checkpoint_bytes(restored)
    require(
        _strict_equal(binding, _binding(CHECKPOINT_REL, restored_bytes)),
        "reconstructed seq97 checkpoint CAS differs",
    )
    require_exact_source(root, restored)
    return restored_bytes


def project_seq98_99(
    root: Path,
    source: Mapping[str, Any],
    evidence: CompletionEvidence,
    *,
    source_checkpoint_bytes: bytes,
    managed_paths: Sequence[str],
    snapshot_hashes: tuple[str, str],
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]],
        tuple[dict[str, Any], dict[str, Any]],
    ] = runtime_authority.derive_runtime,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    _require_source_shape(source)
    require(
        type(source_checkpoint_bytes) is bytes
        and source_checkpoint_bytes == checkpoint_bytes(source),
        "source checkpoint bytes are not exact canonical seq97",
    )
    source_state = source["goal_execution"]
    source_tail = source_state["transition_history"][-1]
    source_time = _parse_time(source_tail["occurred_at"], label="seq97 occurred_at")
    authority_time = _parse_time(
        evidence.latest_authority_at,
        label="latest completion authority timestamp",
    )
    evidence_at = (max(source_time, authority_time) + timedelta(seconds=1)).isoformat()
    completion_at = (
        datetime.fromisoformat(evidence_at) + timedelta(seconds=1)
    ).isoformat()
    checkpoint = copy.deepcopy(source)
    checkpoint["canonical_bindings"] = _add_completion_binding(
        source, evidence.receipt_binding
    )
    canonical_after = _canonical_binding_snapshot(checkpoint)
    require(
        canonical_after.get(COMPLETION_ROLE) == evidence.receipt_binding,
        "completion canonical binding differs",
    )

    state = checkpoint["goal_execution"]
    state["pending_producer_completion_goal_id"] = GOAL_ID
    queue98, boundary98 = runtime_deriver(
        root,
        checkpoint,
        [GOAL_ID, PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID],
    )
    state["artifact_work_queue"] = queue98
    state["completion_boundary"] = boundary98
    evidence_event: dict[str, Any] = {
        "sequence": EVIDENCE_SEQUENCE,
        "event_id": EVIDENCE_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "occurred_on": datetime.fromisoformat(evidence_at).date().isoformat(),
        "occurred_at": evidence_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "from_status": "IN_PROGRESS",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime_after(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"]
            for row in state["blocker_resolution_history"]
            if isinstance(row, dict) and isinstance(row.get("resolution_id"), str)
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [COMPLETION_ROLE],
        "previous_event_sha256": source_tail["event_sha256"],
        "produced_by_goal_id": GOAL_ID,
        "produced_binding_roles": [],
        "producer_completion_receipt_binding": copy.deepcopy(
            dict(evidence.receipt_binding)
        ),
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
        "transition_control_review_binding": copy.deepcopy(
            dict(evidence.review_binding)
        ),
        "source_checkpoint_binding": _binding(
            CHECKPOINT_REL, source_checkpoint_bytes
        ),
        "source_history_preimage": _source_history_preimage(source),
        "changed_path_preimage": _changed_path_preimage(source),
    }
    evidence_event["event_sha256"] = continuation.event_sha256(evidence_event)

    completion_by_goal = state["completion_evidence_by_goal"]
    require(GOAL_ID not in completion_by_goal, "source already has completion evidence")
    completion_by_goal[GOAL_ID] = [COMPLETION_ROLE]
    statuses = state["status_by_goal"]
    statuses[GOAL_ID] = "COMPLETE_AT_TARGET"
    state["goal_status"] = "READY"
    state["focus_goal_id"] = PARENT_GOAL_ID
    state["focus_goal_path"] = PARENT_GOAL_PATH
    state["focus_work_item_id"] = ""
    state["focus_source"] = "WORKSTREAM_GRAPH"
    state["ready_frontier_goal_ids"] = [
        goal
        for goal in source_state["ready_frontier_goal_ids"]
        if goal != GOAL_ID
    ]
    state["pending_producer_completion_goal_id"] = None
    queue99, boundary99 = runtime_deriver(
        root,
        checkpoint,
        [PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID],
    )
    state["artifact_work_queue"] = queue99
    state["completion_boundary"] = boundary99
    completion_runtime = _runtime_after(state)
    completion_event: dict[str, Any] = {
        "sequence": COMPLETION_SEQUENCE,
        "event_id": COMPLETION_EVENT_ID,
        "event_type": "GOAL_COMPLETED",
        "occurred_on": datetime.fromisoformat(completion_at).date().isoformat(),
        "occurred_at": completion_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": PARENT_GOAL_ID,
        "focus_goal_content_sha256": PARENT_GOAL_SHA256,
        "subject_goal_id": GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "COMPLETE_AT_TARGET",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {GOAL_ID: "COMPLETE_AT_TARGET"},
        "runtime_after": completion_runtime,
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"]
            for row in state["blocker_resolution_history"]
            if isinstance(row, dict) and isinstance(row.get("resolution_id"), str)
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [COMPLETION_ROLE],
        "previous_event_sha256": evidence_event["event_sha256"],
        "canonical_update_event_sha256": evidence_event["event_sha256"],
        "completion_receipt_binding": copy.deepcopy(dict(evidence.receipt_binding)),
        "completion_evidence_bindings": {
            COMPLETION_ROLE: copy.deepcopy(dict(evidence.receipt_binding))
        },
        "completion_evidence_by_goal_after": copy.deepcopy(completion_by_goal),
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_after),
    }
    completion_event["event_sha256"] = continuation.event_sha256(completion_event)
    state["transition_history"].extend((evidence_event, completion_event))
    state["transition_history_anchor_sha256"] = completion_event["event_sha256"]
    state["validation_cutoff_at"] = completion_event["occurred_at"]

    current = checkpoint["current_work"]
    current["work_item_id"] = PARENT_GOAL_ID
    current["title"] = "EPIC-03 account/admin/security"
    current["source_policy_ids"] = []
    current["gap_ids"] = []
    current["status"] = "READY"
    current["current_focus"] = (
        "FP-048 R002/GAP-057 COMPLETE_AT_TARGET; EPIC-03 READY"
    )
    current["next_action"] = "다음 READY 목표는 별도 start gate로 시작한다."
    current["release_completion_claimed"] = False

    paths = sorted(set(managed_paths))
    require(paths and all(isinstance(path, str) and path for path in paths), "managed paths differ")
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot["managed_changed_paths"] = paths
    snapshot["managed_changed_path_count"] = len(paths)
    snapshot["path_set_sha256"], snapshot["content_set_sha256"] = snapshot_hashes
    snapshot["scope"] = (
        "Graph v2.4 through FP048 R002 seq98 internal evidence freeze and seq99 "
        "completion; formal, device, external, deployment and release credit remain zero."
    )
    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = paths
    handoff["current_epic"] = "EPIC-03 READY / FP-048 R002 COMPLETE_AT_TARGET"
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = (
        "FP048_R002_INTERNAL_109_OF_109_PASS_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
    )
    handoff["next_single_action"] = current["next_action"]
    mirror = handoff["source_commit_or_snapshot"]
    mirror["file_count"] = len(paths)
    mirror["path_set_sha256"], mirror["content_set_sha256"] = snapshot_hashes
    return checkpoint, evidence_event, completion_event


def validate_projection(
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    evidence: CompletionEvidence,
    *,
    root: Path = ROOT,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]],
        tuple[dict[str, Any], dict[str, Any]],
    ] = runtime_authority.derive_runtime,
) -> None:
    source_state = source["goal_execution"]
    source_checkpoint_bytes = checkpoint_bytes(source)
    state = projected["goal_execution"]
    history = state.get("transition_history")
    require(
        isinstance(history, list) and len(history) == COMPLETION_SEQUENCE,
        "projection is not exact seq99",
    )
    require(
        _strict_equal(
            history[:SOURCE_SEQUENCE], source_state["transition_history"]
        ),
        "seq1-97 history changed",
    )
    update, completion = history[-2:]
    require(set(update) == EVIDENCE_FIELDS, "seq98 field set differs")
    require(set(completion) == COMPLETION_FIELDS, "seq99 field set differs")
    source_tail = source_state["transition_history"][-1]
    source_time = _parse_time(source_tail["occurred_at"], label="seq97 occurred_at")
    authority_time = _parse_time(
        evidence.latest_authority_at,
        label="latest completion authority timestamp",
    )
    expected_update_time = max(source_time, authority_time) + timedelta(seconds=1)
    expected_completion_time = expected_update_time + timedelta(seconds=1)
    require(
        type(update.get("sequence")) is int
        and update.get("sequence") == EVIDENCE_SEQUENCE
        and update.get("event_id") == EVIDENCE_EVENT_ID
        and update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and update.get("occurred_at") == expected_update_time.isoformat()
        and update.get("occurred_on") == expected_update_time.date().isoformat()
        and update.get("previous_focus_goal_id") == GOAL_ID
        and update.get("previous_focus_content_sha256") == GOAL_SHA256
        and update.get("focus_goal_id") == GOAL_ID
        and update.get("focus_goal_content_sha256") == GOAL_SHA256
        and update.get("static_plan_manifest_sha256") == MANIFEST_SHA256
        and update.get("source_checkpoint_version") == source["schema_version"],
        "seq98 identity or chronology differs",
    )
    require(
        type(completion.get("sequence")) is int
        and completion.get("sequence") == COMPLETION_SEQUENCE
        and completion.get("event_id") == COMPLETION_EVENT_ID
        and completion.get("event_type") == "GOAL_COMPLETED"
        and completion.get("occurred_at") == expected_completion_time.isoformat()
        and completion.get("occurred_on")
        == expected_completion_time.date().isoformat()
        and completion.get("previous_focus_goal_id") == GOAL_ID
        and completion.get("previous_focus_content_sha256") == GOAL_SHA256
        and completion.get("focus_goal_id") == PARENT_GOAL_ID
        and completion.get("focus_goal_content_sha256") == PARENT_GOAL_SHA256
        and completion.get("static_plan_manifest_sha256") == MANIFEST_SHA256
        and completion.get("source_checkpoint_version") == source["schema_version"],
        "seq99 identity or chronology differs",
    )
    require(
        update["event_sha256"] == continuation.event_sha256(update)
        and completion["event_sha256"] == continuation.event_sha256(completion),
        "seq98/99 event seal differs",
    )
    require(
        update["previous_event_sha256"]
        == source_state["transition_history"][-1]["event_sha256"]
        and completion["previous_event_sha256"]
        == completion["canonical_update_event_sha256"]
        == update["event_sha256"],
        "seq97/98/99 adjacency differs",
    )
    require(
        update["from_status"] == update["to_status"] == "IN_PROGRESS"
        and _strict_equal(update["status_changes"], {})
        and _strict_equal(update["changed_binding_roles"], [COMPLETION_ROLE])
        and _strict_equal(update["produced_binding_roles"], [])
        and update["produced_by_goal_id"] == GOAL_ID
        and _strict_equal(update["evidence_refs"], [COMPLETION_ROLE])
        and _strict_equal(
            update["changed_subject_ids_by_role"],
            {COMPLETION_ROLE: [GOAL_ID]},
        )
        and _strict_equal(update["producer_output_subject_ids_by_role"], {})
        and _strict_equal(update["impact_closure_goal_ids"], [PARENT_GOAL_ID])
        and _strict_equal(
            update["impact_disposition_by_goal"],
            {
                PARENT_GOAL_ID: {
                    "result": "REVALIDATION_REFRESH_REQUIRED",
                    "target_status": "READY",
                }
            },
        )
        and _strict_equal(update["reopened_completion_event_sha256_by_goal"], {})
        and _strict_equal(
            update["producer_completion_receipt_binding"],
            evidence.receipt_binding,
        )
        and _strict_equal(
            update["transition_control_review_binding"],
            evidence.review_binding,
        )
        and _strict_equal(
            update["source_checkpoint_binding"],
            _binding(CHECKPOINT_REL, source_checkpoint_bytes),
        )
        and _strict_equal(
            update["source_history_preimage"],
            _source_history_preimage(source),
        )
        and _strict_equal(
            update["changed_path_preimage"],
            _changed_path_preimage(source),
        ),
        "seq98 is not a zero-status evidence freeze",
    )
    intermediate = copy.deepcopy(source)
    intermediate["canonical_bindings"] = _add_completion_binding(
        source, evidence.receipt_binding
    )
    intermediate_state = intermediate["goal_execution"]
    intermediate_state["pending_producer_completion_goal_id"] = GOAL_ID
    queue98, boundary98 = runtime_deriver(
        root,
        intermediate,
        [GOAL_ID, PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID],
    )
    intermediate_state["artifact_work_queue"] = queue98
    intermediate_state["completion_boundary"] = boundary98
    expected_resolution_ids = [
        row["resolution_id"]
        for row in intermediate_state["blocker_resolution_history"]
        if isinstance(row, dict) and isinstance(row.get("resolution_id"), str)
    ]
    require(
        _strict_equal(update["runtime_after"], _runtime_after(intermediate_state))
        and _strict_equal(
            update["blockers_after"], intermediate_state["blockers_by_goal"]
        )
        and _strict_equal(
            update["blocker_resolution_ids_after"], expected_resolution_ids
        ),
        "seq98 intermediate runtime seal differs",
    )
    require(
        completion["subject_goal_id"] == GOAL_ID
        and completion["from_status"] == "IN_PROGRESS"
        and completion["to_status"] == "COMPLETE_AT_TARGET"
        and _strict_equal(
            completion["status_changes"], {GOAL_ID: "COMPLETE_AT_TARGET"}
        )
        and _strict_equal(completion["evidence_refs"], [COMPLETION_ROLE]),
        "seq99 completion transition differs",
    )
    expected_statuses = copy.deepcopy(source_state["status_by_goal"])
    expected_statuses[GOAL_ID] = "COMPLETE_AT_TARGET"
    expected_completion_evidence = copy.deepcopy(
        source_state["completion_evidence_by_goal"]
    )
    expected_completion_evidence[GOAL_ID] = [COMPLETION_ROLE]
    expected_final = copy.deepcopy(intermediate)
    expected_final_state = expected_final["goal_execution"]
    expected_final_state["status_by_goal"] = copy.deepcopy(expected_statuses)
    expected_final_state["goal_status"] = "READY"
    expected_final_state["focus_goal_id"] = PARENT_GOAL_ID
    expected_final_state["focus_goal_path"] = PARENT_GOAL_PATH
    expected_final_state["focus_work_item_id"] = ""
    expected_final_state["focus_source"] = "WORKSTREAM_GRAPH"
    expected_final_state["ready_frontier_goal_ids"] = [
        PARENT_GOAL_ID,
        EPIC04_GOAL_ID,
        EPIC12_GOAL_ID,
    ]
    expected_final_state["pending_producer_completion_goal_id"] = None
    expected_final_state["completion_evidence_by_goal"] = copy.deepcopy(
        expected_completion_evidence
    )
    queue99, boundary99 = runtime_deriver(
        root,
        expected_final,
        [PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID],
    )
    expected_final_state["artifact_work_queue"] = queue99
    expected_final_state["completion_boundary"] = boundary99
    require(
        _strict_equal(state["status_by_goal"], expected_statuses)
        and state.get("goal_status") == "READY"
        and state["focus_goal_id"] == PARENT_GOAL_ID
        and state["focus_goal_path"] == PARENT_GOAL_PATH
        and state["focus_work_item_id"] == ""
        and state["focus_source"] == "WORKSTREAM_GRAPH"
        and _strict_equal(
            state["ready_frontier_goal_ids"],
            [PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID],
        )
        and state.get("pending_producer_completion_goal_id") is None
        and _strict_equal(
            state["completion_evidence_by_goal"], expected_completion_evidence
        ),
        "final FP048 R002 goal state differs",
    )
    require(
        state.get("transition_history_anchor_sha256")
        == completion["event_sha256"]
        and state.get("validation_cutoff_at") == completion["occurred_at"]
        and _strict_equal(
            state["artifact_work_queue"],
            expected_final_state["artifact_work_queue"],
        )
        and _strict_equal(
            state["completion_boundary"],
            expected_final_state["completion_boundary"],
        )
        and _strict_equal(
            completion["runtime_after"], _runtime_after(expected_final_state)
        )
        and _strict_equal(
            completion["blockers_after"],
            expected_final_state["blockers_by_goal"],
        )
        and _strict_equal(
            completion["blocker_resolution_ids_after"],
            [
                row["resolution_id"]
                for row in expected_final_state["blocker_resolution_history"]
                if isinstance(row, dict)
                and isinstance(row.get("resolution_id"), str)
            ],
        ),
        "seq99 runtime seal differs",
    )
    changed_state_fields = {
        "transition_history",
        "transition_history_anchor_sha256",
        "validation_cutoff_at",
        "goal_status",
        "status_by_goal",
        "focus_goal_id",
        "focus_goal_path",
        "focus_work_item_id",
        "focus_source",
        "ready_frontier_goal_ids",
        "pending_producer_completion_goal_id",
        "completion_evidence_by_goal",
        "artifact_work_queue",
        "completion_boundary",
    }
    for key in set(source_state) | set(state):
        if key not in changed_state_fields:
            require(
                _strict_equal(state.get(key), source_state.get(key)),
                f"unauthorized goal state mutation: {key}",
            )
    canonical = _canonical_binding_snapshot(projected)
    expected_canonical = _canonical_binding_snapshot(source)
    require(
        COMPLETION_ROLE not in expected_canonical,
        "source completion role already exists",
    )
    expected_canonical[COMPLETION_ROLE] = copy.deepcopy(
        dict(evidence.receipt_binding)
    )
    expected_canonical_bindings = _add_completion_binding(
        source, evidence.receipt_binding
    )
    require(
        _strict_equal(
            projected.get("canonical_bindings"), expected_canonical_bindings
        )
        and _strict_equal(canonical, expected_canonical)
        and _strict_equal(update["canonical_binding_snapshot_after"], canonical)
        and _strict_equal(completion["canonical_binding_snapshot_after"], canonical)
        and _strict_equal(
            completion["completion_receipt_binding"], evidence.receipt_binding
        )
        and _strict_equal(
            completion["completion_evidence_bindings"],
            {COMPLETION_ROLE: evidence.receipt_binding},
        )
        and _strict_equal(
            completion["completion_evidence_by_goal_after"],
            expected_completion_evidence,
        ),
        "completion canonical evidence differs",
    )
    require(
        _strict_equal(projected.get("approved_state"), source.get("approved_state"))
        and _strict_equal(
            projected.get("verification_boundary"),
            source.get("verification_boundary"),
        )
        and _strict_equal(
            projected.get("authority_boundary"), source.get("authority_boundary")
        ),
        "formal/release authority changed",
    )
    changed_top_fields = {
        "canonical_bindings",
        "current_work",
        "goal_execution",
        "working_tree_snapshot",
        "session_handoff",
    }
    for key in set(source) | set(projected):
        if key not in changed_top_fields:
            require(
                _strict_equal(projected.get(key), source.get(key)),
                f"unauthorized top-level mutation: {key}",
            )
    expected_current = copy.deepcopy(source["current_work"])
    expected_current["work_item_id"] = PARENT_GOAL_ID
    expected_current["title"] = "EPIC-03 account/admin/security"
    expected_current["source_policy_ids"] = []
    expected_current["gap_ids"] = []
    expected_current["status"] = "READY"
    expected_current["current_focus"] = (
        "FP-048 R002/GAP-057 COMPLETE_AT_TARGET; EPIC-03 READY"
    )
    expected_current["next_action"] = "다음 READY 목표는 별도 start gate로 시작한다."
    expected_current["release_completion_claimed"] = False
    require(
        _strict_equal(projected.get("current_work"), expected_current),
        "final current-work projection differs",
    )
    require(
        evidence.receipt_bytes == json_bytes(evidence.receipt)
        and _strict_equal(
            evidence.receipt_binding, _receipt_binding(evidence.receipt_bytes)
        )
        and _strict_equal(
            evidence.receipt.get("verification_evidence"), evidence.verification
        )
        and _strict_equal(
            evidence.receipt.get("completion_boundary"), ZERO_CREDIT_BOUNDARY
        )
        and projected["approved_state"]["release_status"] == "NOT_ELIGIBLE"
        and projected["verification_boundary"]["formal_test_pass_claimed"] is False
        and projected["verification_boundary"]["release_eligible"] is False,
        "zero-credit completion boundary differs",
    )
    files = evidence.receipt["implementation_evidence"]["files"]
    require(
        {(row["path"], row["sha256"], row["byte_length"]) for row in files}
        == {
            (path.as_posix(), pin[0], pin[1])
            for path, pin in PRODUCT_PINS.items()
        },
        "receipt product pins differ",
    )
    _verification_started_at, verification_ended_at = _require_verification_summary(
        evidence.verification
    )
    require(
        evidence.receipt.get("generated_at") == verification_ended_at.isoformat()
        and authority_time >= verification_ended_at,
        "receipt verification chronology differs",
    )
    require(
        _strict_equal(
            evidence.receipt.get("gap_reassessment"),
            {
            "gap_id": "GAP-057",
            "finding_id": "FP048-SEVEN-STATE-ROTATION-ATOMICITY-GAP",
            "internal_finding_status": "CLOSED_AT_INTERNAL_TARGET",
            "policy_status": "PARTIAL_EXTERNAL_GATES_REMAIN",
            "remaining_gate_count": 5,
            "release_status": "NOT_ELIGIBLE",
            },
        ),
        "receipt GAP-057 reassessment differs",
    )
    checks = evidence.receipt["verification_evidence"]["checks"]
    by_id = {row["check_id"]: row for row in checks}
    expected_focused = {
        "tests": 13,
        "pass": 13,
        "fail": 0,
        "cancelled": 0,
        "skipped": 0,
        "todo": 0,
    }
    expected_full = {
        "tests": 109,
        "pass": 109,
        "fail": 0,
        "cancelled": 0,
        "skipped": 0,
        "todo": 0,
    }
    require(
        set(by_id)
        == {
            "TYPECHECK",
            "BUILD",
            "FOCUSED_STATE_ENCRYPTION_MAINTENANCE",
            "ANDROID_GATEWAY_FULL",
        }
        and by_id["TYPECHECK"]["status"] == "PASS"
        and by_id["BUILD"]["status"] == "PASS"
        and _strict_equal(
            by_id["FOCUSED_STATE_ENCRYPTION_MAINTENANCE"]["tap"],
            expected_focused,
        )
        and _strict_equal(by_id["ANDROID_GATEWAY_FULL"]["tap"], expected_full),
        "receipt verification counts differ",
    )
    paths = projected["working_tree_snapshot"]["managed_changed_paths"]
    require(
        isinstance(paths, list)
        and all(isinstance(path, str) and path for path in paths)
        and paths == sorted(set(paths))
        and type(
            projected["working_tree_snapshot"]["managed_changed_path_count"]
        ) is int
        and projected["working_tree_snapshot"]["managed_changed_path_count"]
        == len(paths)
        and set(source["working_tree_snapshot"]["managed_changed_paths"])
        .issubset(paths),
        "managed snapshot closure differs",
    )
    snapshot = projected["working_tree_snapshot"]
    require(
        set(snapshot) == set(source["working_tree_snapshot"])
        and isinstance(snapshot.get("path_set_sha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", snapshot["path_set_sha256"])
        and isinstance(snapshot.get("content_set_sha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", snapshot["content_set_sha256"]),
        "working snapshot field authority differs",
    )
    expected_handoff = copy.deepcopy(source["session_handoff"])
    expected_handoff["changed_files"] = paths
    expected_handoff["current_epic"] = (
        "EPIC-03 READY / FP-048 R002 COMPLETE_AT_TARGET"
    )
    expected_handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    expected_handoff["last_verification_status"] = (
        "FP048_R002_INTERNAL_109_OF_109_PASS_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
    )
    expected_handoff["next_single_action"] = expected_current["next_action"]
    expected_mirror = expected_handoff["source_commit_or_snapshot"]
    expected_mirror["file_count"] = len(paths)
    expected_mirror["path_set_sha256"] = snapshot["path_set_sha256"]
    expected_mirror["content_set_sha256"] = snapshot["content_set_sha256"]
    require(
        _strict_equal(projected.get("session_handoff"), expected_handoff),
        "final session handoff differs",
    )


def _snapshot_inputs(root: Path, source: Mapping[str, Any]) -> tuple[list[str], tuple[str, str]]:
    _status, git_visible = started.seq90.capture_git_visible_paths(root)
    source_paths = source["working_tree_snapshot"]["managed_changed_paths"]
    checkpoint_temporary_prefix = (
        CHECKPOINT_REL.parent.as_posix()
        + "/."
        + CHECKPOINT_REL.name
        + ".fp048-seq43-44."
    )
    visible = {
        path
        for path in git_visible
        if path != CHECKPOINT_REL.as_posix()
        and not (
            path.startswith(checkpoint_temporary_prefix) and path.endswith(".tmp")
        )
    }
    paths = sorted(
        set(source_paths)
        | visible
        | {
            SCRIPT_REL.as_posix(),
            TEST_REL.as_posix(),
            COMPLETION_RECEIPT_REL.as_posix(),
            *(path.as_posix() for path in REVIEW_PATHS),
        }
    )
    return paths, continuation.working_snapshot_hashes(root, paths)


def _shared_projection_errors(root: Path, checkpoint: dict[str, Any]) -> list[str]:
    continuation_validator = getattr(
        continuation, "validate_fp048_r002_completion_seq98_99", None
    )
    goal_validator = getattr(
        goal_graph, "validate_fp048_r002_completion_seq98_99", None
    )
    require(callable(continuation_validator), "continuation seq98-99 hook is missing")
    require(callable(goal_validator), "GoalGraph seq98-99 hook is missing")
    return [
        *continuation_validator(checkpoint),
        *goal_validator(root, checkpoint),
    ]


def prepare_review_inputs(
    root: Path = ROOT,
    *,
    verification_runner: Callable[[Path], Mapping[str, Any]] = run_fresh_verification,
) -> dict[Path, bytes]:
    root = root.resolve(strict=True)
    source_bytes = _safe_regular_bytes(root, CHECKPOINT_REL, private=True)
    source = strict_json(source_bytes, "source checkpoint")
    require_exact_source(root, source)
    product_before = _verify_product_pins(root)
    verification = verification_runner(root)
    _require_verification_summary(verification)
    require(
        _safe_regular_bytes(root, CHECKPOINT_REL, private=True) == source_bytes,
        "checkpoint changed during review verification",
    )
    require_exact_source(root, source)
    require(
        _verify_product_pins(root) == product_before,
        "product files changed during review verification",
    )
    receipt = build_completion_receipt(root, source, verification)
    receipt_bytes = json_bytes(receipt)
    assignment = expected_review_assignment(
        root, source_bytes, source, receipt_bytes
    )
    outputs = {
        COMPLETION_RECEIPT_REL: receipt_bytes,
        REVIEW_ASSIGNMENT_REL: json_bytes(assignment),
    }
    require(
        _safe_regular_bytes(root, CHECKPOINT_REL, private=True) == source_bytes
        and _verify_product_pins(root) == product_before,
        "review source changed before add-only preparation",
    )
    for relative, raw in outputs.items():
        _write_add_only(root, relative, raw)
    require(
        _safe_regular_bytes(root, CHECKPOINT_REL, private=True) == source_bytes,
        "checkpoint changed while preparing review",
    )
    return outputs


def prepare_projection(
    root: Path = ROOT,
    *,
    verification_runner: Callable[[Path], Mapping[str, Any]] = run_fresh_verification,
    projected_validator: Callable[[Path, dict[str, Any]], Sequence[str]] = _shared_projection_errors,
) -> PreparedProjection:
    root = root.resolve(strict=True)
    source_bytes = _safe_regular_bytes(root, CHECKPOINT_REL, private=True)
    source = strict_json(source_bytes, "source checkpoint")
    require_exact_source(root, source)
    product_before = _verify_product_pins(root)
    fresh_verification = verification_runner(root)
    _require_verification_summary(fresh_verification)
    require(
        _safe_regular_bytes(root, CHECKPOINT_REL, private=True) == source_bytes,
        "checkpoint changed during fresh completion verification",
    )
    require(
        _verify_product_pins(root) == product_before,
        "product files changed during fresh completion verification",
    )
    receipt_bytes = _safe_regular_bytes(
        root, COMPLETION_RECEIPT_REL, private=True
    )
    receipt = strict_json(receipt_bytes, "completion receipt")
    require(receipt_bytes == json_bytes(receipt), "completion receipt is not canonical")
    recorded_verification = receipt.get("verification_evidence")
    require(
        isinstance(recorded_verification, dict),
        "recorded verification evidence differs",
    )
    _recorded_started_at, recorded_ended_at = _require_verification_summary(
        recorded_verification
    )
    require(
        _strict_equal(
            _verification_semantics(fresh_verification),
            _verification_semantics(recorded_verification),
        ),
        "fresh verification semantics differ from reviewed receipt",
    )
    require(
        receipt_bytes
        == json_bytes(
            build_completion_receipt(root, source, recorded_verification)
        ),
        "prepared completion receipt bytes differ",
    )
    assignment = expected_review_assignment(
        root, source_bytes, source, receipt_bytes
    )
    review = _load_review_authority(
        root,
        assignment,
        not_before=recorded_ended_at,
    )
    evidence = CompletionEvidence(
        verification=recorded_verification,
        receipt=receipt,
        receipt_bytes=receipt_bytes,
        receipt_binding=_receipt_binding(receipt_bytes),
        review_binding=review.binding,
        latest_authority_at=review.latest_review_at,
    )
    paths, hashes = _snapshot_inputs(root, source)
    projected, update, completion = project_seq98_99(
        root,
        source,
        evidence,
        source_checkpoint_bytes=source_bytes,
        managed_paths=paths,
        snapshot_hashes=hashes,
    )
    validate_projection(source, projected, evidence)
    errors = list(projected_validator(root, projected))
    require(not errors, "projected consumer validation failed: " + " | ".join(errors))
    require(
        _safe_regular_bytes(root, CHECKPOINT_REL, private=True) == source_bytes,
        "checkpoint changed during preflight",
    )
    return PreparedProjection(
        root=root,
        source_bytes=source_bytes,
        source=source,
        projected=projected,
        projected_bytes=checkpoint_bytes(projected),
        evidence=evidence,
        evidence_event=update,
        completion_event=completion,
    )


def _write_add_only(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    require(not target.parent.is_symlink(), f"output parent symlink differs: {relative}")
    if target.exists():
        require(
            target.is_file()
            and not target.is_symlink()
            and stat.S_IMODE(target.stat().st_mode) == 0o600
            and target.read_bytes() == raw,
            f"existing add-only output differs: {relative}",
        )
        return
    descriptor = os.open(
        target,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
        0o600,
    )
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        try:
            target.unlink()
        except OSError:
            pass
        raise


def write_projection(
    prepared: PreparedProjection,
    *,
    atomic_writer: Callable[..., None] = cas.atomic_write,
    projected_validator: Callable[[Path, dict[str, Any]], Sequence[str]] = _shared_projection_errors,
) -> None:
    checkpoint = prepared.root / CHECKPOINT_REL
    require(
        prepared.projected_bytes == checkpoint_bytes(prepared.projected),
        "prepared projected bytes differ from projected object",
    )
    prepared_parsed = strict_json(
        prepared.projected_bytes, "prepared projected checkpoint"
    )
    require(
        _strict_equal(prepared_parsed, prepared.projected),
        "prepared projected checkpoint parse differs",
    )
    _verification_started_at, verification_ended_at = _require_verification_summary(
        prepared.evidence.verification
    )

    def guard() -> None:
        current = checkpoint.read_bytes()
        require(
            current in {prepared.source_bytes, prepared.projected_bytes},
            "checkpoint changed at commit",
        )
        current_checkpoint = strict_json(current, "checkpoint at commit")
        require(
            current == checkpoint_bytes(current_checkpoint),
            "checkpoint at commit is not canonical",
        )
        if current == prepared.source_bytes:
            require(
                _strict_equal(current_checkpoint, prepared.source),
                "source checkpoint object changed at commit",
            )
        else:
            require(
                _strict_equal(current_checkpoint, prepared.projected),
                "published checkpoint object differs",
            )
            validate_projection(
                prepared.source,
                current_checkpoint,
                prepared.evidence,
            )
        _verify_product_pins(prepared.root)
        require(
            _safe_regular_bytes(
                prepared.root, COMPLETION_RECEIPT_REL, private=True
            )
            == prepared.evidence.receipt_bytes,
            "completion receipt changed at commit",
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
            not_before=verification_ended_at,
        )
        require(
            _strict_equal(review.binding, prepared.evidence.review_binding)
            and review.latest_review_at == prepared.evidence.latest_authority_at,
            "review authority changed at commit",
        )
        paths, hashes = _snapshot_inputs(prepared.root, prepared.source)
        snapshot = prepared.projected["working_tree_snapshot"]
        require(
            paths == snapshot["managed_changed_paths"]
            and hashes
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "managed snapshot changed at commit",
        )
        if current == prepared.projected_bytes:
            errors = list(projected_validator(prepared.root, current_checkpoint))
            require(
                not errors,
                "published consumer validation failed: " + " | ".join(errors),
            )

    atomic_writer(
        checkpoint,
        prepared.projected_bytes,
        expected_source=prepared.source_bytes,
        commit_guard=guard,
    )
    published_raw = checkpoint.read_bytes()
    require(
        published_raw == prepared.projected_bytes,
        "checkpoint publication bytes differ",
    )
    published = strict_json(published_raw, "published checkpoint")
    require(
        published_raw == checkpoint_bytes(published)
        and _strict_equal(published, prepared.projected),
        "published checkpoint object differs",
    )
    validate_projection(prepared.source, published, prepared.evidence)
    errors = list(projected_validator(prepared.root, published))
    require(
        not errors,
        "terminal consumer validation failed: " + " | ".join(errors),
    )


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
                "FP048 R002 seq98-99 review preparation: PASS "
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
        print(
            "FP048 R002 GOAL_COMPLETED seq98-99: PASS "
            f"mode={mode} evidence_sha256="
            f"{prepared.evidence_event['event_sha256']} completion_sha256="
            f"{prepared.completion_event['event_sha256']}"
        )
        return 0
    except (
        CompletionApplyError,
        cas.CompletionApplyError,
        KeyError,
        OSError,
        subprocess.SubprocessError,
        TypeError,
        ValueError,
    ) as exc:
        print(f"FP048 R002 GOAL_COMPLETED seq98-99: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
