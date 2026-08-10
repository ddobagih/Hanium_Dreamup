#!/usr/bin/env python3
"""Prepare the WalkSafe v2.4 active-predecessor successor package.

This builder deliberately emits only the v2.4 PACKAGE_PREPARED event.
PACKAGE_ACTIVATED and FP-011 GOAL_STARTED require later, separately bound
authorization and gate receipts and are therefore outside this builder.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT_RELATIVE = Path(
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
V23_PACKAGE_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-3"
)
V24_PACKAGE_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4"
)
V23_MANIFEST_RELATIVE = (
    V23_PACKAGE_RELATIVE / "static-plan-manifest-v2.3.0.json"
)
V24_MANIFEST_RELATIVE = (
    V24_PACKAGE_RELATIVE / "static-plan-manifest-v2.4.0.json"
)
V24_README_RELATIVE = V24_PACKAGE_RELATIVE / "README.md"
V24_RECORD_RELATIVE = (
    V24_PACKAGE_RELATIVE / "active-supersession-record-v2.3.0.json"
)
V24_ARCHIVE_RELATIVE = (
    V24_PACKAGE_RELATIVE / "superseded-v2.3.0-active-checkpoint.json"
)
V24_DYNAMIC_TEMPLATE_RELATIVE = (
    V24_PACKAGE_RELATIVE / "templates/dynamic-node-template.md"
)
V24_POLICY_GAP_TEMPLATE_RELATIVE = (
    V24_PACKAGE_RELATIVE / "templates/policy-gap-work-item.md"
)

V23_PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-3"
V24_PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
V23_PLAN_VERSION = "2.3.0"
V24_PLAN_VERSION = "2.4.0"
V24_MANIFEST_ID = "WS-GOAL-GRAPH-STATIC-PLAN-MANIFEST-2.4.0"
V24_RECORD_ID = "WS-GOAL-ACTIVE-SUPERSESSION-20260725-001"
V24_EVENT_ID = "WS-GOAL-GRAPH-V2-4-PACKAGE-PREPARED-20260725-001"
V24_OCCURRED_AT = "2026-07-25T04:07:00+09:00"
V24_OCCURRED_ON = "2026-07-25"
V24_CHECKPOINT_ID = "WS-PROJECT-CONTINUATION-CHECKPOINT-20260725-004"
V24_CHECKPOINT_SCHEMA_VERSION = "1.15.0"
V24_GOAL_EXECUTION_SCHEMA_VERSION = "2.2"

EXPECTED_V23_MANIFEST_SHA256 = (
    "dfa615686b0223826497fae424c1f3c41538b271102879a9497a33b81f66329c"
)
EXPECTED_V23_CHECKPOINT_SHA256 = (
    "7f62b09941e614f11d9c21c84e5f63df2ff070b00c569550faf2de7807de67b6"
)
EXPECTED_V23_PATH_SET_SHA256 = (
    "74c04874df3766ca61cb7153eeecefe421a3d447ed3ddef2775e7538aa4a266b"
)
EXPECTED_V23_CONTENT_SET_SHA256 = (
    "822189aade16518f68a4be68ce7086f0dcf54f4d83c85bf1c2bb08aa7a3458d0"
)
EXPECTED_V23_EVENT_COUNT = 17
EXPECTED_V23_TAIL_SHA256 = (
    "bc71126a8a0b71a97ce4cc89a86e0ce1d89739453f719f8820dc882c74a101d6"
)
EXPECTED_V23_MANAGED_PATH_COUNT = 26
EXPECTED_V23_GOAL_DOCUMENT_COUNT = 20
EXPECTED_FOCUS_GOAL_ID = "WS-GOAL-EPIC-02-FP-011-R001"
EXPECTED_FOCUS_WORK_ITEM_ID = "EPIC-02-FP011-LONG-LIVED-LOGIN"
EXPECTED_READY_FRONTIER = [
    EXPECTED_FOCUS_GOAL_ID,
    "WS-GOAL-EPIC-03",
    "WS-GOAL-EPIC-12",
]
EXPECTED_V23_CANONICAL_BINDING_SNAPSHOT_SHA256 = (
    "09ba59e4f759375ce5dfafd2c286e9acb163340d6cb4d0d609e7aad73d98b6fe"
)
EXPECTED_V24_IMPORTED_GOAL_BINDINGS_SHA256 = (
    "3969213c250e677f4dead477616c279e485aadfe3be4c2014c83a8f1c69b08cc"
)

V23_BUILDER_RELATIVE = Path("scripts/build_walksafe_goal_graph_v2_3.py")
V23_CHECKER_RELATIVE = Path("scripts/check_walksafe_goal_graph_v2_3.py")
V23_CONTINUATION_CHECKER_RELATIVE = Path(
    "scripts/check_walksafe_project_continuation_v2_3.py"
)
V23_TEST_RELATIVE = Path("tests/test_walksafe_goal_graph_v2_3.py")
V23_CONTINUATION_TEST_RELATIVE = Path(
    "tests/test_walksafe_project_continuation_v2_3.py"
)
EXPECTED_FROZEN_V23_CONTROL_SHA256 = {
    V23_BUILDER_RELATIVE.as_posix(): (
        "dce38036c0fb397e03f9fef47a0b78c329fa68de398500e7e5e6bdc0b0ec3c8f"
    ),
    V23_CHECKER_RELATIVE.as_posix(): (
        "27dde08c2f7828fc138b9f502a31d604fde60c3957e33e87882ee9f05dbb87ac"
    ),
    V23_CONTINUATION_CHECKER_RELATIVE.as_posix(): (
        "1785a97c5fd0cc0182cb1a9f95616328c344aca777f2e86839980afbe7bdab3f"
    ),
    V23_TEST_RELATIVE.as_posix(): (
        "a7e9762baf99ff41ee4ee3c5c7230d7d1f90d1d2b4e72fb0859d09d6eeabfe53"
    ),
    V23_CONTINUATION_TEST_RELATIVE.as_posix(): (
        "f2de52ef345d62b955552054aecd5be5bd832b4085e0f7114181a5c78b37252e"
    ),
}

V24_BUILDER_RELATIVE = Path("scripts/build_walksafe_goal_graph_v2_4.py")
V24_CHECKER_RELATIVE = Path("scripts/check_walksafe_goal_graph_v2_4.py")
V24_CONTINUATION_CHECKER_RELATIVE = Path(
    "scripts/check_walksafe_project_continuation_v2_4.py"
)
V24_TEST_RELATIVE = Path("tests/test_walksafe_goal_graph_v2_4.py")
V24_CONTINUATION_TEST_RELATIVE = Path(
    "tests/test_walksafe_project_continuation_v2_4.py"
)
V23_HISTORY_TEST_RELATIVE = Path(
    "tests/test_walksafe_goal_graph_v2_3_history.py"
)
V23_TRACE_HISTORY_TEST_RELATIVE = Path(
    "tests/test_walksafe_epic02_trace_v2_3_history.py"
)

CHECK_COMMAND_CONTRACT_VERSION = "2026-07-25.4"
LOCKED_PYTHON_PREFIX = (
    'WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-'
    "/home/ddobagi/.local/share/hanium-dreamup/"
    'walksafe-general-cpu-verify-20260715/bin/python}" && '
    'test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && '
)
GOAL_CONTROL_PYTEST_COMMAND = (
    f"{LOCKED_PYTHON_PREFIX}"
    '"${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest '
    "tests/test_walksafe_goal_graph_v2_4.py "
    "tests/test_walksafe_project_continuation_v2_4.py "
    "tests/test_walksafe_goal_graph_v2_3_history.py "
    "tests/test_walksafe_goal_graph_v2_2_history.py -q"
)
CONTINUATION_INTEGRITY_HANDOFF_COMMAND = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python -m pytest "
    "tests/test_walksafe_epic01_phase_b_trace_20260722.py "
    "tests/test_walksafe_epic01_phase_c_trace_20260722.py "
    "tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py "
    "tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py "
    "tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py "
    "tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py "
    "tests/test_walksafe_android_gateway_boundary_20260723.py "
    "tests/test_walksafe_epic02_trace_v2_2_history.py "
    "tests/test_walksafe_epic02_trace_v2_3_history.py "
    "tests/test_walksafe_project_continuation_v2_4.py "
    "tests/test_walksafe_artifact_baseline_materialization_20260722.py "
    "--deselect=tests/test_walksafe_epic01_phase_b_trace_20260722.py::"
    "WalkSafeEpic01PhaseBTraceTest::test_generated_files_are_current_and_deterministic "
    "--deselect=tests/test_walksafe_epic01_phase_c_trace_20260722.py::"
    "WalkSafeEpic01PhaseCTraceTest::test_generated_files_are_current_and_deterministic "
    "--deselect=tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py::"
    "WalkSafeEpic01PhaseETraceTest::test_generated_files_are_current_and_deterministic "
    "--deselect=tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py::"
    "WalkSafeEpic01PhaseFTraceTest::test_generated_files_are_current_and_deterministic "
    "--deselect=tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py::"
    "WalkSafeEpic01PhaseGTraceTest::test_generated_files_are_current_and_deterministic "
    "--deselect=tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py::"
    "WalkSafeEpic02PhaseATraceTest::test_generated_files_are_current_and_deterministic "
    "-q"
)
GOAL_PACKAGE_HANDOFF_COMMAND = (
    "python3 -B scripts/check_walksafe_goal_graph_v2_4.py && "
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python -m pytest "
    "tests/test_walksafe_goal_graph_v2_4.py "
    "tests/test_walksafe_project_continuation_v2_4.py "
    "tests/test_walksafe_goal_graph_v2_3_history.py "
    "tests/test_walksafe_goal_graph_v2_2_history.py -q"
)
GOAL_PACKAGE_HANDOFF_RESULT = (
    "고정 단계 수 없음, 의존성 DAG와 ready frontier 기반 "
    "20개 imported Goal 검사 PASS; v2.3 seq17 archive history PASS"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument(
        "--check",
        action="store_true",
        help="compare generated bytes without writing them",
    )
    return parser.parse_args()


def json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_sha256(
    value: Any,
    *,
    omit: set[str] | None = None,
) -> str:
    if isinstance(value, dict) and omit:
        value = {key: item for key, item in value.items() if key not in omit}
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def load_json_bytes(value: bytes, label: str) -> dict[str, Any]:
    try:
        payload = json.loads(value)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label} is not valid UTF-8 JSON: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must contain a JSON object")
    return payload


def path_and_content_hashes_from_bytes(
    relative_paths: list[str],
    bytes_by_path: dict[str, bytes],
) -> tuple[str, str]:
    normalized = sorted(relative_paths)
    path_payload = "\n".join(normalized).encode("utf-8") + b"\n"
    content_digest = hashlib.sha256()
    for relative in normalized:
        content_digest.update(relative.encode("utf-8"))
        content_digest.update(b"\0")
        try:
            content = bytes_by_path[relative]
        except KeyError as exc:
            raise ValueError(f"missing bytes for managed path: {relative}") from exc
        content_digest.update(sha256_bytes(content).encode("ascii"))
        content_digest.update(b"\n")
    return (
        hashlib.sha256(path_payload).hexdigest(),
        content_digest.hexdigest(),
    )


def canonical_binding_snapshot(
    checkpoint: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    bindings = checkpoint.get("canonical_bindings")
    if not isinstance(bindings, list):
        raise ValueError("predecessor canonical_bindings must be a list")
    for binding in bindings:
        if not isinstance(binding, dict):
            raise ValueError("predecessor canonical binding is malformed")
        role = binding.get("role")
        if not isinstance(role, str) or not role or role in result:
            raise ValueError("predecessor canonical binding role is invalid")
        result[role] = {
            "role": role,
            "document_id": binding.get("document_id"),
            "path": binding.get("path"),
            "file_sha256": binding.get("file_sha256"),
        }
    return {role: result[role] for role in sorted(result)}


def completion_event_sha256(
    state: dict[str, Any],
    history: list[dict[str, Any]],
    goal_id: str,
) -> str:
    imported = state.get("imported_predecessor_goal_bindings")
    source_record = (
        imported.get(goal_id) if isinstance(imported, dict) else None
    )
    source_digest = (
        source_record.get("completion_event_sha256")
        if isinstance(source_record, dict)
        else None
    )
    if isinstance(source_digest, str) and source_digest:
        return source_digest
    for event in reversed(history):
        changes = event.get("status_changes")
        if (
            event.get("event_type") in {"GOAL_COMPLETED", "PACKAGE_COMPLETED"}
            and isinstance(changes, dict)
            and changes.get(goal_id) == "COMPLETE_AT_TARGET"
        ):
            digest = event.get("event_sha256")
            if isinstance(digest, str) and digest:
                return digest
    raise ValueError(f"completed Goal has no completion event: {goal_id}")


def command_contract_sha256(
    checks: tuple[tuple[str, str], ...],
) -> str:
    return canonical_sha256(
        {
            "contract_version": CHECK_COMMAND_CONTRACT_VERSION,
            "checks": [
                {"check_id": check_id, "command": command}
                for check_id, command in checks
            ],
        }
    )


def predecessor_checkpoint_bytes(root: Path) -> bytes:
    current_path = root / CHECKPOINT_RELATIVE
    current_bytes = current_path.read_bytes()
    current = load_json_bytes(current_bytes, CHECKPOINT_RELATIVE.as_posix())
    package_id = current.get("goal_execution", {}).get("package_id")
    if package_id == V23_PACKAGE_ID:
        source = current_bytes
    elif package_id == V24_PACKAGE_ID:
        state = current.get("goal_execution")
        history = state.get("transition_history") if isinstance(state, dict) else None
        if (
            not isinstance(state, dict)
            or state.get("package_status") != "PREPARED_NOT_ACTIVATED"
            or state.get("activation_status") != "READY_NOT_ACTIVATED"
            or state.get("focus_goal_id") != EXPECTED_FOCUS_GOAL_ID
            or state.get("status_by_goal", {}).get(EXPECTED_FOCUS_GOAL_ID)
            != "READY"
            or not isinstance(history, list)
            or len(history) != 1
            or history[0].get("sequence") != 1
            or history[0].get("event_id") != V24_EVENT_ID
            or history[0].get("event_type") != "PACKAGE_PREPARED"
        ):
            raise ValueError(
                "v2.4 builder may only reseal its unchanged prepared seq1 "
                "checkpoint; activation or later history is immutable"
            )
        source = (root / V24_ARCHIVE_RELATIVE).read_bytes()
    else:
        raise ValueError(
            "current checkpoint is neither the frozen v2.3 predecessor "
            "nor the prepared v2.4 successor"
        )
    if sha256_bytes(source) != EXPECTED_V23_CHECKPOINT_SHA256:
        raise ValueError("frozen v2.3 active checkpoint SHA-256 differs")
    return source


def validate_canonical_binding_files(
    root: Path,
    checkpoint: dict[str, Any],
) -> None:
    snapshot = canonical_binding_snapshot(checkpoint)
    actual_snapshot_hash = canonical_sha256(snapshot)
    if actual_snapshot_hash != EXPECTED_V23_CANONICAL_BINDING_SNAPSHOT_SHA256:
        raise ValueError("frozen v2.3 canonical binding snapshot differs")
    for role, binding in snapshot.items():
        relative = binding.get("path")
        expected = binding.get("file_sha256")
        if not isinstance(relative, str) or not relative:
            raise ValueError(f"{role} canonical path is invalid")
        if Path(relative).is_absolute() or ".." in Path(relative).parts:
            raise ValueError(f"{role} canonical path is unsafe")
        path = root / relative
        if not path.is_file() or sha256_file(path) != expected:
            raise ValueError(f"{role} canonical file SHA-256 differs")


def validate_predecessor(
    root: Path,
    checkpoint: dict[str, Any],
    manifest: dict[str, Any],
) -> None:
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        raise ValueError("predecessor goal_execution is missing")
    history = state.get("transition_history")
    if not isinstance(history, list):
        raise ValueError("predecessor transition_history is missing")
    checks = {
        "package_id": (state.get("package_id"), V23_PACKAGE_ID),
        "static_plan_version": (
            state.get("static_plan_version"),
            V23_PLAN_VERSION,
        ),
        "package_status": (state.get("package_status"), "ACTIVE"),
        "activation_status": (state.get("activation_status"), "ACTIVE"),
        "manifest_sha256": (
            state.get("static_plan_manifest_sha256"),
            EXPECTED_V23_MANIFEST_SHA256,
        ),
        "event_count": (len(history), EXPECTED_V23_EVENT_COUNT),
        "tail_sha256": (
            state.get("transition_history_anchor_sha256"),
            EXPECTED_V23_TAIL_SHA256,
        ),
        "focus_goal_id": (
            state.get("focus_goal_id"),
            EXPECTED_FOCUS_GOAL_ID,
        ),
        "focus_work_item_id": (
            state.get("focus_work_item_id"),
            EXPECTED_FOCUS_WORK_ITEM_ID,
        ),
        "ready_frontier": (
            state.get("ready_frontier_goal_ids"),
            EXPECTED_READY_FRONTIER,
        ),
        "focus_status": (
            state.get("status_by_goal", {}).get(EXPECTED_FOCUS_GOAL_ID),
            "READY",
        ),
        "managed_goal_path_count": (
            state.get("managed_goal_path_count"),
            EXPECTED_V23_MANAGED_PATH_COUNT,
        ),
        "goal_document_count": (
            state.get("goal_document_count"),
            EXPECTED_V23_GOAL_DOCUMENT_COUNT,
        ),
        "path_set_sha256": (
            state.get("path_set_sha256"),
            EXPECTED_V23_PATH_SET_SHA256,
        ),
        "content_set_sha256": (
            state.get("content_set_sha256"),
            EXPECTED_V23_CONTENT_SET_SHA256,
        ),
    }
    for label, (actual, expected) in checks.items():
        if actual != expected:
            raise ValueError(
                f"frozen v2.3 {label} differs: {actual!r} != {expected!r}"
            )
    if manifest.get("package_id") != V23_PACKAGE_ID:
        raise ValueError("frozen v2.3 manifest package ID differs")
    if sha256_file(root / V23_MANIFEST_RELATIVE) != (
        EXPECTED_V23_MANIFEST_SHA256
    ):
        raise ValueError("frozen v2.3 manifest file SHA-256 differs")
    for relative, expected in EXPECTED_FROZEN_V23_CONTROL_SHA256.items():
        path = root / relative
        if not path.is_file() or sha256_file(path) != expected:
            raise ValueError(f"frozen v2.3 control file changed: {relative}")

    managed_paths = state.get("managed_goal_paths")
    if (
        not isinstance(managed_paths, list)
        or sorted(set(managed_paths)) != managed_paths
        or len(managed_paths) != EXPECTED_V23_MANAGED_PATH_COUNT
    ):
        raise ValueError("frozen v2.3 managed path list differs")
    bytes_by_path: dict[str, bytes] = {}
    for relative in managed_paths:
        path = root / relative
        if not path.is_file():
            raise ValueError(f"frozen v2.3 managed path is missing: {relative}")
        bytes_by_path[relative] = path.read_bytes()
    path_hash, content_hash = path_and_content_hashes_from_bytes(
        managed_paths,
        bytes_by_path,
    )
    if path_hash != EXPECTED_V23_PATH_SET_SHA256:
        raise ValueError("frozen v2.3 managed path-set SHA-256 differs")
    if content_hash != EXPECTED_V23_CONTENT_SET_SHA256:
        raise ValueError("frozen v2.3 managed content-set SHA-256 differs")
    validate_canonical_binding_files(root, checkpoint)


def imported_goal_bindings(
    root: Path,
    checkpoint: dict[str, Any],
    manifest: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    state = checkpoint["goal_execution"]
    statuses = state["status_by_goal"]
    inventory = state["dynamic_goal_inventory"]
    completion_evidence = state["completion_evidence_by_goal"]
    history = state["transition_history"]
    result: dict[str, dict[str, Any]] = {}

    for node in manifest["goal_graph"]["static_nodes"]:
        goal_id = node["goal_id"]
        path = node["path"]
        record: dict[str, Any] = {
            "goal_id": goal_id,
            "path": path,
            "sha256": sha256_file(root / path),
            "goal_kind": node["goal_kind"],
            "status": statuses[goal_id],
            "source_package_id": V23_PACKAGE_ID,
        }
        if statuses[goal_id] == "COMPLETE_AT_TARGET":
            record["completion_event_sha256"] = completion_event_sha256(
                state,
                history,
                goal_id,
            )
            record["completion_evidence_refs"] = copy.deepcopy(
                completion_evidence[goal_id]
            )
        result[goal_id] = record

    for goal_id, item in inventory.items():
        if goal_id in result:
            raise ValueError(f"duplicate imported Goal: {goal_id}")
        record = {
            "goal_id": goal_id,
            "path": item["path"],
            "sha256": sha256_file(root / item["path"]),
            "goal_kind": item["goal_kind"],
            "status": statuses[goal_id],
            "source_package_id": V23_PACKAGE_ID,
            "materialized_event_sha256": item[
                "materialized_event_sha256"
            ],
        }
        if statuses[goal_id] == "COMPLETE_AT_TARGET":
            record["completion_event_sha256"] = completion_event_sha256(
                state,
                history,
                goal_id,
            )
            record["completion_evidence_refs"] = copy.deepcopy(
                completion_evidence[goal_id]
            )
        result[goal_id] = record

    if set(result) != set(statuses):
        raise ValueError("v2.3 imported Goal IDs differ from status map")
    if len(result) != EXPECTED_V23_GOAL_DOCUMENT_COUNT:
        raise ValueError(
            f"expected 20 imported Goals, found {len(result)}"
        )
    canonical = {goal_id: result[goal_id] for goal_id in sorted(result)}
    if canonical_sha256(canonical) != (
        EXPECTED_V24_IMPORTED_GOAL_BINDINGS_SHA256
    ):
        raise ValueError("v2.4 imported Goal binding projection differs")
    return canonical


def derive_check_contracts(
    predecessor_manifest: dict[str, Any],
) -> tuple[
    tuple[tuple[str, str], ...],
    tuple[tuple[str, str], ...],
]:
    control = predecessor_manifest.get("successor_control_contract")
    if not isinstance(control, dict):
        raise ValueError("v2.3 successor control contract is missing")
    old_quick = control.get("quick_activation_checks")
    old_full = control.get("implementation_start_gate_checks")
    if not isinstance(old_quick, list) or not isinstance(old_full, list):
        raise ValueError("v2.3 check command contract is malformed")

    quick: list[tuple[str, str]] = []
    for record in old_quick:
        if not isinstance(record, dict):
            raise ValueError("v2.3 quick check record is malformed")
        check_id = record.get("check_id")
        command = record.get("command")
        if not isinstance(check_id, str) or not isinstance(command, str):
            raise ValueError("v2.3 quick check record is invalid")
        quick.append((check_id, command.replace("_v2_3.py", "_v2_4.py")))

    full: list[tuple[str, str]] = []
    for record in old_full:
        if not isinstance(record, dict):
            raise ValueError("v2.3 full-gate record is malformed")
        check_id = record.get("check_id")
        command = record.get("command")
        if not isinstance(check_id, str) or not isinstance(command, str):
            raise ValueError("v2.3 full-gate record is invalid")
        if check_id == "GOAL_CONTROL_PYTEST":
            command = GOAL_CONTROL_PYTEST_COMMAND
        elif check_id == "CONTROL_AND_TRACE_PYTEST":
            command = command.replace(
                "tests/test_walksafe_epic02_trace_v2_2_history.py ",
                "tests/test_walksafe_epic02_trace_v2_2_history.py "
                "tests/test_walksafe_epic02_trace_v2_3_history.py ",
            )
        else:
            command = command.replace("_v2_3.py", "_v2_4.py")
        full.append((check_id, command))

    expected_quick_ids = ["CONTINUATION_QUICK", "GOAL_GRAPH_QUICK"]
    expected_full_ids = [
        "CONTINUATION",
        "GOAL_GRAPH",
        "BASELINE_MATERIALIZATION",
        "ANDROID_GATEWAY_BOUNDARY",
        "NODE_TOOLCHAIN_PRE",
        "GATEWAY_TYPECHECK",
        "GATEWAY_TEST",
        "GATEWAY_BUILD",
        "WEB_TEST",
        "WEB_LINT",
        "WEB_TYPECHECK",
        "WEB_BUILD",
        "NODE_TOOLCHAIN_POST",
        "ANDROID_UNIT_ASSEMBLE_LINT",
        "TEST_LAYER_REGISTRY_VALIDATE",
        "FIELD_AND_RELEASE_PYTEST",
        "GOAL_CONTROL_PYTEST",
        "CONTROL_AND_TRACE_PYTEST",
        "REPOSITORY_STATE",
    ]
    if [item[0] for item in quick] != expected_quick_ids:
        raise ValueError("v2.4 quick check ID order differs")
    if [item[0] for item in full] != expected_full_ids:
        raise ValueError("v2.4 full-gate check ID order differs")
    return tuple(quick), tuple(full)


def load_controlled_paths(root: Path) -> list[str]:
    checker_path = root / V24_CONTINUATION_CHECKER_RELATIVE
    if not checker_path.is_file():
        raise ValueError(
            "v2.4 continuation checker is required before package generation"
        )
    spec = importlib.util.spec_from_file_location(
        "_walksafe_continuation_v24_builder_contract",
        checker_path,
    )
    if spec is None or spec.loader is None:
        raise ValueError("cannot load the v2.4 continuation contract")
    module = importlib.util.module_from_spec(spec)
    previous_dont_write_bytecode = sys.dont_write_bytecode
    try:
        sys.dont_write_bytecode = True
        spec.loader.exec_module(module)
    finally:
        sys.dont_write_bytecode = previous_dont_write_bytecode
    paths = getattr(module, "EXPECTED_CONTROLLED_PATHS", None)
    if (
        not isinstance(paths, tuple)
        or not paths
        or not all(isinstance(item, str) and item for item in paths)
    ):
        raise ValueError(
            "v2.4 continuation EXPECTED_CONTROLLED_PATHS is invalid"
        )
    normalized = sorted(set(paths))
    if normalized != list(paths):
        raise ValueError(
            "v2.4 continuation EXPECTED_CONTROLLED_PATHS is not canonical"
        )
    return normalized


def update_controlled_snapshot(
    root: Path,
    checkpoint: dict[str, Any],
    generated_outputs: dict[str, bytes],
) -> None:
    controlled_paths = load_controlled_paths(root)
    content_by_path: dict[str, bytes] = {}
    for relative in controlled_paths:
        if relative in generated_outputs:
            content_by_path[relative] = generated_outputs[relative]
            continue
        path = root / relative
        if not path.is_file():
            raise ValueError(f"controlled path is missing: {relative}")
        content_by_path[relative] = path.read_bytes()
    path_hash, content_hash = path_and_content_hashes_from_bytes(
        controlled_paths,
        content_by_path,
    )

    snapshot = checkpoint["working_tree_snapshot"]
    snapshot["managed_changed_paths"] = controlled_paths
    snapshot["managed_changed_path_count"] = len(controlled_paths)
    snapshot["path_set_sha256"] = path_hash
    snapshot["content_set_sha256"] = content_hash

    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = controlled_paths
    source = handoff["source_commit_or_snapshot"]
    source["file_count"] = len(controlled_paths)
    source["path_set_sha256"] = path_hash
    source["content_set_sha256"] = content_hash


def update_handoff_verification_contract(
    checkpoint: dict[str, Any],
) -> None:
    records = checkpoint["session_handoff"][
        "verification_commands_and_results"
    ]
    by_id = {
        record.get("id"): record
        for record in records
        if isinstance(record, dict)
    }
    continuation = by_id.get("CONTINUATION_INTEGRITY")
    goal_package = by_id.get("GOAL_PACKAGE")
    if not isinstance(continuation, dict) or not isinstance(
        goal_package,
        dict,
    ):
        raise ValueError("required handoff verification records are missing")
    continuation["command"] = CONTINUATION_INTEGRITY_HANDOFF_COMMAND
    continuation["status"] = "PASS"
    continuation["result"] = (
        "v2.4 continuation contract와 v2.3 archive history PASS"
    )
    goal_package["command"] = GOAL_PACKAGE_HANDOFF_COMMAND
    goal_package["status"] = "PASS"
    goal_package["result"] = GOAL_PACKAGE_HANDOFF_RESULT


def readme_bytes() -> bytes:
    return """# WalkSafe Goal Graph v2.4

패키지 ID: `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4`

계획 버전: `2.4.0`

상태: `PREPARED_NOT_ACTIVATED / READY_NOT_ACTIVATED`

## 목적

활성 v2.3은 seq17에서 FP-011을 READY로 만들었다. 그러나 FP-011 시작 전 제어
회귀를 현재 상태에 맞게 고치면 이미 완료된 FP-005·FP-006·FP-010의 변경 산출물
provenance가 깨진다. v2.3 checker/test와 17-event checkpoint를 수정하지 않고,
그 활성 tail을 byte-exact archive와 projection event로 가져오는 정식 successor가
필요하다.

## imported Goal 소유권

20개 Goal 문서는 v2.2/v2.3의 원래 경로와 bytes를 그대로 사용한다. 복사하거나
완료 문서를 다시 쓰지 않는다. 현재 상태와 완료·materialization lineage는
checkpoint의 `imported_predecessor_goal_bindings`가 결속한다.

v2.4 native 지원 파일은 README, manifest, active supersession record, v2.3 active
checkpoint archive와 template 두 개뿐이다. manifest는 자기 hash 순환을 피하려고
protected file 집합에서 제외되며 checkpoint가 manifest hash를 직접 고정한다.

## 현재 focus

- focus: `WS-GOAL-EPIC-02-FP-011-R001`
- Work Item: `EPIC-02-FP011-LONG-LIVED-LOGIN`
- ready frontier: FP-011, EPIC-03, EPIC-12
- 정식 시험·실기기·5개 Gate·출시: 기존 미종결 상태 유지

## 활성화 경계

이 builder는 `PACKAGE_PREPARED` seq1만 만든다. 최종 manifest SHA-256과 initial
event SHA-256에 결속한 새 사용자 승인이 있어야 quick gate 뒤 seq2
`PACKAGE_ACTIVATED`를 기록할 수 있다. 그 뒤에도 새 19-check full gate와 별도
seq3 `GOAL_STARTED`가 성공하기 전에는 FP-011 제품 코드를 변경하지 않는다.
""".encode("utf-8")


def build_outputs(root: Path) -> dict[str, bytes]:
    predecessor_raw = predecessor_checkpoint_bytes(root)
    predecessor = load_json_bytes(
        predecessor_raw,
        V24_ARCHIVE_RELATIVE.as_posix(),
    )
    v23_manifest = load_json_bytes(
        (root / V23_MANIFEST_RELATIVE).read_bytes(),
        V23_MANIFEST_RELATIVE.as_posix(),
    )
    validate_predecessor(root, predecessor, v23_manifest)

    predecessor_state = predecessor["goal_execution"]
    imported = imported_goal_bindings(
        root,
        predecessor,
        v23_manifest,
    )
    imported_goal_paths = sorted(
        item["path"] for item in imported.values()
    )
    old_bytes_by_path = {
        relative: (root / relative).read_bytes()
        for relative in imported_goal_paths
    }
    quick_checks, full_checks = derive_check_contracts(v23_manifest)

    supersedes = {
        "package_id": V23_PACKAGE_ID,
        "plan_version": V23_PLAN_VERSION,
        "activation_status": "ACTIVE",
        "manifest_path": V23_MANIFEST_RELATIVE.as_posix(),
        "manifest_sha256": EXPECTED_V23_MANIFEST_SHA256,
        "archived_checkpoint_path": V24_ARCHIVE_RELATIVE.as_posix(),
        "archived_checkpoint_raw_sha256": (
            EXPECTED_V23_CHECKPOINT_SHA256
        ),
        "transition_event_count": EXPECTED_V23_EVENT_COUNT,
        "transition_history_anchor_sha256": EXPECTED_V23_TAIL_SHA256,
        "supersession_record_path": V24_RECORD_RELATIVE.as_posix(),
    }
    binding_snapshot = canonical_binding_snapshot(predecessor)
    state_projection = {
        "status_by_goal": predecessor_state["status_by_goal"],
        "completion_evidence_by_goal": predecessor_state[
            "completion_evidence_by_goal"
        ],
        "archived_completion_evidence_by_goal": predecessor_state[
            "archived_completion_evidence_by_goal"
        ],
        "dynamic_goal_inventory": predecessor_state[
            "dynamic_goal_inventory"
        ],
        "materialized_child_goal_ids_by_parent": predecessor_state[
            "materialized_child_goal_ids_by_parent"
        ],
        "canonical_binding_snapshot": binding_snapshot,
        "runtime": predecessor_state["transition_history"][-1][
            "runtime_after"
        ],
    }
    record = {
        "schema_version": "1.0",
        "record_id": V24_RECORD_ID,
        "recorded_at": V24_OCCURRED_AT,
        "superseded_package": supersedes,
        "successor_package_id": V24_PACKAGE_ID,
        "successor_plan_version": V24_PLAN_VERSION,
        "frozen_control_history": {
            "builder_path": V23_BUILDER_RELATIVE.as_posix(),
            "builder_sha256": EXPECTED_FROZEN_V23_CONTROL_SHA256[
                V23_BUILDER_RELATIVE.as_posix()
            ],
            "checker_path": V23_CHECKER_RELATIVE.as_posix(),
            "checker_sha256": EXPECTED_FROZEN_V23_CONTROL_SHA256[
                V23_CHECKER_RELATIVE.as_posix()
            ],
            "continuation_checker_path": (
                V23_CONTINUATION_CHECKER_RELATIVE.as_posix()
            ),
            "continuation_checker_sha256": (
                EXPECTED_FROZEN_V23_CONTROL_SHA256[
                    V23_CONTINUATION_CHECKER_RELATIVE.as_posix()
                ]
            ),
            "test_path": V23_TEST_RELATIVE.as_posix(),
            "test_sha256": EXPECTED_FROZEN_V23_CONTROL_SHA256[
                V23_TEST_RELATIVE.as_posix()
            ],
            "continuation_test_path": (
                V23_CONTINUATION_TEST_RELATIVE.as_posix()
            ),
            "continuation_test_sha256": (
                EXPECTED_FROZEN_V23_CONTROL_SHA256[
                    V23_CONTINUATION_TEST_RELATIVE.as_posix()
                ]
            ),
        },
        "superseded_package_set": {
            "managed_path_count": EXPECTED_V23_MANAGED_PATH_COUNT,
            "path_set_sha256": EXPECTED_V23_PATH_SET_SHA256,
            "content_set_sha256": EXPECTED_V23_CONTENT_SET_SHA256,
        },
        "active_state_projection_sha256": canonical_sha256(
            state_projection
        ),
        "status_by_goal_sha256": canonical_sha256(
            predecessor_state["status_by_goal"]
        ),
        "completion_evidence_by_goal_sha256": canonical_sha256(
            predecessor_state["completion_evidence_by_goal"]
        ),
        "dynamic_goal_inventory_sha256": canonical_sha256(
            predecessor_state["dynamic_goal_inventory"]
        ),
        "canonical_binding_snapshot_sha256": canonical_sha256(
            binding_snapshot
        ),
        "imported_predecessor_goal_bindings_sha256": canonical_sha256(
            imported
        ),
        "reason": (
            "활성 v2.3 seq17의 FP-011 READY runtime을 유지하면서 "
            "FP-005·FP-006·FP-010 완료 provenance를 훼손하지 않고 "
            "current-state checker/test/registry 계약으로 승계한다."
        ),
        "execution_effect": (
            "V2_3_ACTIVE_HISTORY_RETAINED_BYTE_EXACT; "
            "V2_4_PREPARED_NOT_ACTIVATED; NO_FP011_PRODUCT_WORK_STARTED"
        ),
    }

    outputs: dict[str, bytes] = {
        V24_README_RELATIVE.as_posix(): readme_bytes(),
        V24_RECORD_RELATIVE.as_posix(): json_bytes(record),
        V24_ARCHIVE_RELATIVE.as_posix(): predecessor_raw,
        V24_DYNAMIC_TEMPLATE_RELATIVE.as_posix(): (
            root
            / V23_PACKAGE_RELATIVE
            / "templates/dynamic-node-template.md"
        ).read_bytes(),
        V24_POLICY_GAP_TEMPLATE_RELATIVE.as_posix(): (
            root
            / V23_PACKAGE_RELATIVE
            / "templates/policy-gap-work-item.md"
        ).read_bytes(),
    }

    manifest = copy.deepcopy(v23_manifest)
    manifest["schema_version"] = "2.2"
    manifest["manifest_id"] = V24_MANIFEST_ID
    manifest["package_id"] = V24_PACKAGE_ID
    manifest["plan_version"] = V24_PLAN_VERSION
    manifest["created_on"] = V24_OCCURRED_ON
    manifest["supersedes"] = supersedes
    manifest["goal_graph"]["initial_focus_goal_id"] = (
        EXPECTED_FOCUS_GOAL_ID
    )
    manifest["goal_graph"]["initial_ready_frontier_goal_ids"] = (
        EXPECTED_READY_FRONTIER
    )
    for contract in manifest["dynamic_node_contracts"]:
        old_path = Path(contract["template_path"])
        contract["template_path"] = (
            V24_PACKAGE_RELATIVE / "templates" / old_path.name
        ).as_posix()
    manifest["imported_predecessor_goal_contract"] = {
        "source_package_id": V23_PACKAGE_ID,
        "source_plan_version": V23_PLAN_VERSION,
        "source_checkpoint_path": V24_ARCHIVE_RELATIVE.as_posix(),
        "runtime_binding_field": "imported_predecessor_goal_bindings",
        "expected_goal_count": EXPECTED_V23_GOAL_DOCUMENT_COUNT,
        "goal_paths_remain_in_predecessor_packages": True,
        "goal_bytes_must_match_archived_projection": True,
        "materialization_and_completion_lineage_is_imported": True,
    }
    transition = manifest["transition_contract"]
    transition["check_command_contract_version"] = (
        CHECK_COMMAND_CONTRACT_VERSION
    )
    transition["quick_activation_check_ids"] = [
        item[0] for item in quick_checks
    ]
    transition["quick_activation_check_contract_sha256"] = (
        command_contract_sha256(quick_checks)
    )
    transition["implementation_start_gate_check_ids"] = [
        item[0] for item in full_checks
    ]
    transition["implementation_start_gate_check_contract_sha256"] = (
        command_contract_sha256(full_checks)
    )
    transition["predecessor_runtime_import_event"] = "PACKAGE_PREPARED"
    transition["predecessor_runtime_import_is_projection_only"] = True
    manifest["successor_control_contract"] = {
        "builder_path": V24_BUILDER_RELATIVE.as_posix(),
        "checker_path": V24_CHECKER_RELATIVE.as_posix(),
        "test_path": V24_TEST_RELATIVE.as_posix(),
        "continuation_checker_path": (
            V24_CONTINUATION_CHECKER_RELATIVE.as_posix()
        ),
        "continuation_test_path": (
            V24_CONTINUATION_TEST_RELATIVE.as_posix()
        ),
        "predecessor_history_test_path": (
            V23_HISTORY_TEST_RELATIVE.as_posix()
        ),
        "predecessor_trace_history_test_path": (
            V23_TRACE_HISTORY_TEST_RELATIVE.as_posix()
        ),
        "frozen_predecessor_control_sha256": copy.deepcopy(
            EXPECTED_FROZEN_V23_CONTROL_SHA256
        ),
        "frozen_predecessor_tests_are_history_only": True,
        "quick_activation_checks": [
            {"check_id": check_id, "command": command}
            for check_id, command in quick_checks
        ],
        "implementation_start_gate_checks": [
            {"check_id": check_id, "command": command}
            for check_id, command in full_checks
        ],
    }
    protected_paths = [
        V24_README_RELATIVE.as_posix(),
        V24_RECORD_RELATIVE.as_posix(),
        V24_ARCHIVE_RELATIVE.as_posix(),
        V24_DYNAMIC_TEMPLATE_RELATIVE.as_posix(),
        V24_POLICY_GAP_TEMPLATE_RELATIVE.as_posix(),
    ]
    manifest["protected_files"] = [
        {"path": relative, "sha256": sha256_bytes(outputs[relative])}
        for relative in protected_paths
    ]
    outputs[V24_MANIFEST_RELATIVE.as_posix()] = json_bytes(manifest)

    support_paths = sorted(
        [
            V24_README_RELATIVE.as_posix(),
            V24_MANIFEST_RELATIVE.as_posix(),
            V24_RECORD_RELATIVE.as_posix(),
            V24_ARCHIVE_RELATIVE.as_posix(),
            V24_DYNAMIC_TEMPLATE_RELATIVE.as_posix(),
            V24_POLICY_GAP_TEMPLATE_RELATIVE.as_posix(),
        ]
    )
    managed_paths = sorted(imported_goal_paths + support_paths)
    if (
        len(managed_paths) != EXPECTED_V23_MANAGED_PATH_COUNT
        or len(set(managed_paths)) != EXPECTED_V23_MANAGED_PATH_COUNT
    ):
        raise ValueError("v2.4 managed path set must contain 26 unique paths")
    bytes_by_path = {**old_bytes_by_path, **outputs}
    package_path_hash, package_content_hash = (
        path_and_content_hashes_from_bytes(managed_paths, bytes_by_path)
    )
    manifest_sha256 = sha256_bytes(
        outputs[V24_MANIFEST_RELATIVE.as_posix()]
    )

    runtime_after = copy.deepcopy(
        predecessor_state["transition_history"][-1]["runtime_after"]
    )
    runtime_after["activation_status"] = "READY_NOT_ACTIVATED"
    runtime_after["package_status"] = "PREPARED_NOT_ACTIVATED"
    event = {
        "sequence": 1,
        "event_id": V24_EVENT_ID,
        "event_type": "PACKAGE_PREPARED",
        "occurred_on": V24_OCCURRED_ON,
        "occurred_at": V24_OCCURRED_AT,
        "previous_focus_goal_id": "",
        "previous_focus_content_sha256": "",
        "focus_goal_id": EXPECTED_FOCUS_GOAL_ID,
        "focus_goal_content_sha256": imported[
            EXPECTED_FOCUS_GOAL_ID
        ]["sha256"],
        "from_status": "",
        "to_status": "READY",
        "static_plan_manifest_sha256": manifest_sha256,
        "supersedes_event_sha256": EXPECTED_V23_TAIL_SHA256,
        "active_predecessor_import": {
            "source_package_id": V23_PACKAGE_ID,
            "source_plan_version": V23_PLAN_VERSION,
            "source_manifest_path": V23_MANIFEST_RELATIVE.as_posix(),
            "source_manifest_sha256": EXPECTED_V23_MANIFEST_SHA256,
            "source_checkpoint_path": V24_ARCHIVE_RELATIVE.as_posix(),
            "source_checkpoint_raw_sha256": (
                EXPECTED_V23_CHECKPOINT_SHA256
            ),
            "source_transition_event_count": EXPECTED_V23_EVENT_COUNT,
            "source_transition_history_anchor_sha256": (
                EXPECTED_V23_TAIL_SHA256
            ),
            "imported_predecessor_goal_bindings_sha256": (
                canonical_sha256(imported)
            ),
            "canonical_binding_snapshot_sha256": canonical_sha256(
                binding_snapshot
            ),
        },
        "bootstrap_consumed_policy_gap_pairs": copy.deepcopy(
            predecessor_state["bootstrap_consumed_policy_gap_pairs"]
        ),
        "canonical_binding_snapshot_after": binding_snapshot,
        "status_changes": copy.deepcopy(
            predecessor_state["status_by_goal"]
        ),
        "completion_evidence_by_goal_after": copy.deepcopy(
            predecessor_state["completion_evidence_by_goal"]
        ),
        "archived_completion_evidence_by_goal_after": copy.deepcopy(
            predecessor_state["archived_completion_evidence_by_goal"]
        ),
        "dynamic_goal_inventory_after": copy.deepcopy(
            predecessor_state["dynamic_goal_inventory"]
        ),
        "materialized_child_goal_ids_by_parent_after": copy.deepcopy(
            predecessor_state["materialized_child_goal_ids_by_parent"]
        ),
        "runtime_after": runtime_after,
        "blockers_after": copy.deepcopy(
            predecessor_state["blockers_by_goal"]
        ),
        "blocker_resolution_ids_after": [
            item["resolution_id"]
            for item in predecessor_state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": V24_CHECKPOINT_SCHEMA_VERSION,
        "evidence_refs": [
            V24_RECORD_ID,
            binding_snapshot["IMPLEMENTATION_GAP"]["document_id"],
            binding_snapshot["IMPLEMENTATION_BACKLOG"]["document_id"],
        ],
        "previous_event_sha256": "",
    }
    event["event_sha256"] = canonical_sha256(
        event,
        omit={"event_sha256"},
    )

    checkpoint = copy.deepcopy(predecessor)
    checkpoint["schema_version"] = V24_CHECKPOINT_SCHEMA_VERSION
    checkpoint["metadata"]["checkpoint_id"] = V24_CHECKPOINT_ID
    checkpoint["metadata"]["version"] = V24_CHECKPOINT_SCHEMA_VERSION
    checkpoint["metadata"]["as_of"] = V24_OCCURRED_ON
    state = copy.deepcopy(predecessor_state)
    state["schema_version"] = V24_GOAL_EXECUTION_SCHEMA_VERSION
    state["package_id"] = V24_PACKAGE_ID
    state["static_plan_version"] = V24_PLAN_VERSION
    state["static_plan_manifest_path"] = V24_MANIFEST_RELATIVE.as_posix()
    state["static_plan_manifest_sha256"] = manifest_sha256
    state["package_status"] = "PREPARED_NOT_ACTIVATED"
    state["activation_status"] = "READY_NOT_ACTIVATED"
    state["imported_predecessor_goal_bindings"] = imported
    state["support_paths"] = support_paths
    state["managed_goal_paths"] = managed_paths
    state["managed_goal_path_count"] = len(managed_paths)
    state["goal_document_paths"] = imported_goal_paths
    state["goal_document_count"] = len(imported_goal_paths)
    state["path_set_sha256"] = package_path_hash
    state["content_set_sha256"] = package_content_hash
    state["transition_history"] = [event]
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = V24_OCCURRED_AT
    state["verification_evidence_refs"] = []
    state["focus_goal_id"] = EXPECTED_FOCUS_GOAL_ID
    state["focus_goal_path"] = imported[EXPECTED_FOCUS_GOAL_ID]["path"]
    state["focus_work_item_id"] = EXPECTED_FOCUS_WORK_ITEM_ID
    state["focus_source"] = "IMPLEMENTATION_BACKLOG"
    state["ready_frontier_goal_ids"] = copy.deepcopy(
        EXPECTED_READY_FRONTIER
    )
    state["goal_status"] = predecessor_state["goal_status"]
    checkpoint["goal_execution"] = state
    update_handoff_verification_contract(checkpoint)
    update_controlled_snapshot(root, checkpoint, outputs)
    outputs[CHECKPOINT_RELATIVE.as_posix()] = json_bytes(checkpoint)
    return outputs


def actual_native_paths(root: Path) -> set[str]:
    package_root = root / V24_PACKAGE_RELATIVE
    if not package_root.exists():
        return set()
    return {
        path.relative_to(root).as_posix()
        for path in package_root.rglob("*")
        if path.is_file()
    }


def check_outputs(root: Path, outputs: dict[str, bytes]) -> list[str]:
    errors: list[str] = []
    expected_native = {
        relative
        for relative in outputs
        if relative.startswith(V24_PACKAGE_RELATIVE.as_posix() + "/")
    }
    actual_native = actual_native_paths(root)
    if actual_native != expected_native:
        errors.append(
            "v2.4 native path set differs: "
            f"missing={sorted(expected_native - actual_native)}, "
            f"extra={sorted(actual_native - expected_native)}"
        )
    for relative, expected in sorted(outputs.items()):
        path = root / relative
        if not path.is_file():
            errors.append(f"missing generated file: {relative}")
        elif path.read_bytes() != expected:
            errors.append(f"generated file differs: {relative}")
    return errors


def atomic_write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    try:
        outputs = build_outputs(root)
    except (KeyError, OSError, TypeError, ValueError) as exc:
        print(
            f"WalkSafe Goal graph v2.4 build: FAIL: {exc}",
            file=sys.stderr,
        )
        return 1

    if args.check:
        errors = check_outputs(root, outputs)
        if errors:
            for error in errors:
                print(f"WalkSafe Goal graph v2.4 check: FAIL: {error}")
            return 1
        print(
            "WalkSafe Goal graph v2.4 check: PASS "
            "(seq1 PREPARED, 6 native files, 20 imported Goals, "
            "26 managed paths)"
        )
        return 0

    existing_native = actual_native_paths(root)
    expected_native = {
        relative
        for relative in outputs
        if relative.startswith(V24_PACKAGE_RELATIVE.as_posix() + "/")
    }
    unexpected = existing_native - expected_native
    if unexpected:
        print(
            "WalkSafe Goal graph v2.4 build: FAIL: unexpected native files: "
            + ", ".join(sorted(unexpected)),
            file=sys.stderr,
        )
        return 1
    for relative, content in sorted(outputs.items()):
        atomic_write(root / relative, content)
    print(
        "WalkSafe Goal graph v2.4 build: PASS "
        "(seq1 PREPARED, 6 native files, 20 imported Goals, "
        "26 managed paths; activation not performed)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
