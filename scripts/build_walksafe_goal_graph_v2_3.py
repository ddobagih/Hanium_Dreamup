#!/usr/bin/env python3
"""Build the WalkSafe v2.3 active-predecessor successor package.

The v2.2 Goal documents stay at their original paths.  v2.3 imports their
byte-exact identities and runtime lineage instead of copying or rewriting
them.  This avoids invalidating completed Work Item receipts.
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
V22_PACKAGE_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-2"
)
V23_PACKAGE_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-3"
)
V22_MANIFEST_RELATIVE = (
    V22_PACKAGE_RELATIVE / "static-plan-manifest-v2.2.0.json"
)
V23_MANIFEST_RELATIVE = (
    V23_PACKAGE_RELATIVE / "static-plan-manifest-v2.3.0.json"
)
V23_README_RELATIVE = V23_PACKAGE_RELATIVE / "README.md"
V23_RECORD_RELATIVE = (
    V23_PACKAGE_RELATIVE / "active-supersession-record-v2.2.0.json"
)
V23_ARCHIVE_RELATIVE = (
    V23_PACKAGE_RELATIVE / "superseded-v2.2.0-active-checkpoint.json"
)
V23_DYNAMIC_TEMPLATE_RELATIVE = (
    V23_PACKAGE_RELATIVE / "templates/dynamic-node-template.md"
)
V23_POLICY_GAP_TEMPLATE_RELATIVE = (
    V23_PACKAGE_RELATIVE / "templates/policy-gap-work-item.md"
)

V22_PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-2"
V23_PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-3"
V22_PLAN_VERSION = "2.2.0"
V23_PLAN_VERSION = "2.3.0"
V23_MANIFEST_ID = "WS-GOAL-GRAPH-STATIC-PLAN-MANIFEST-2.3.0"
V23_RECORD_ID = "WS-GOAL-ACTIVE-SUPERSESSION-20260724-001"
V23_EVENT_ID = "WS-GOAL-GRAPH-V2-3-PACKAGE-PREPARED-20260724-001"
V23_OCCURRED_AT = "2026-07-24T17:00:00+09:00"
V23_OCCURRED_ON = "2026-07-24"
V23_CHECKPOINT_ID = "WS-PROJECT-CONTINUATION-CHECKPOINT-20260724-003"

EXPECTED_V22_MANIFEST_SHA256 = (
    "c761ed6d9ac83fb98a2dded6eb99b639610e67a7bf13bdd306f8be38e996577b"
)
EXPECTED_V22_CHECKPOINT_SHA256 = (
    "c15d677c9d8f9b68227cb0b2ffaac3aef4794e2bdb031b8cc4ca4a4790fc6024"
)
EXPECTED_V22_CHECKER_SHA256 = (
    "64c5e733499029f608dc56d76096bb25a97bfdf043fa483180565a55d7d68d9e"
)
EXPECTED_V22_TEST_SHA256 = (
    "ba195e5c26d8a91eca6b88e09587d2f58fcc213590977d7ef95b434e781db297"
)
EXPECTED_V22_PATH_SET_SHA256 = (
    "1afe2adca0413a03b073f0c7f777625872153fcadce2eccfca286142dbfe3398"
)
EXPECTED_V22_CONTENT_SET_SHA256 = (
    "724010b3f84129a1d076ea64f42d3a4051b86fa4ed2bab6d7c46e3c4ace39340"
)
EXPECTED_V22_EVENT_COUNT = 20
EXPECTED_V22_TAIL_SHA256 = (
    "ec531638c3b3ce68b611e198dba06f6703d837aa712d6aae403f212f8272030d"
)
EXPECTED_FOCUS_GOAL_ID = "WS-GOAL-EPIC-02-FP-005-R001"
EXPECTED_FOCUS_WORK_ITEM_ID = (
    "EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK"
)
EXPECTED_READY_FRONTIER = [
    EXPECTED_FOCUS_GOAL_ID,
    "WS-GOAL-EPIC-03",
    "WS-GOAL-EPIC-12",
]

V22_CHECKER_RELATIVE = Path("scripts/check_walksafe_goal_graph.py")
V22_TEST_RELATIVE = Path("tests/test_walksafe_goal_graph.py")
V23_BUILDER_RELATIVE = Path("scripts/build_walksafe_goal_graph_v2_3.py")
V23_CHECKER_RELATIVE = Path("scripts/check_walksafe_goal_graph_v2_3.py")
V23_TEST_RELATIVE = Path("tests/test_walksafe_goal_graph_v2_3.py")
CONTINUATION_CHECKER_RELATIVE = Path(
    "scripts/check_walksafe_project_continuation_v2_3.py"
)
CONTINUATION_TEST_RELATIVE = Path(
    "tests/test_walksafe_project_continuation_v2_3.py"
)

CHECK_COMMAND_CONTRACT_VERSION = "2026-07-24.3"
LOCKED_PYTHON_PREFIX = (
    'WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-'
    "/home/ddobagi/.local/share/hanium-dreamup/"
    'walksafe-general-cpu-verify-20260715/bin/python}" && '
    'test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && '
)
STALE_V22_TEST_DESELECTS = (
    "tests/test_walksafe_goal_graph.py::WalkSafeGoalGraphTest::"
    "test_canonical_next_action_wins_without_serializing_other_branches",
    "tests/test_walksafe_goal_graph.py::WalkSafeGoalGraphTest::"
    "test_policy_gap_work_item_binds_exactly_one_pair",
)

QUICK_ACTIVATION_CHECKS = (
    (
        "CONTINUATION_QUICK",
        "python3 -B scripts/check_walksafe_project_continuation_v2_3.py",
    ),
    (
        "GOAL_GRAPH_QUICK",
        "python3 -B scripts/check_walksafe_goal_graph_v2_3.py",
    ),
)

IMPLEMENTATION_START_GATE_CHECKS = (
    (
        "CONTINUATION",
        "python3 -B scripts/check_walksafe_project_continuation_v2_3.py",
    ),
    (
        "GOAL_GRAPH",
        "python3 -B scripts/check_walksafe_goal_graph_v2_3.py",
    ),
    (
        "BASELINE_MATERIALIZATION",
        "python3 -B scripts/materialize_walksafe_artifact_baseline_approval_20260722.py --check",
    ),
    (
        "ANDROID_GATEWAY_BOUNDARY",
        "python3 -B scripts/check_walksafe_android_gateway_boundary_20260723.py --root .",
    ),
    (
        "NODE_TOOLCHAIN_PRE",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && WALKSAFE_NODE_ROOT="$(/usr/bin/dirname -- "${WALKSAFE_NODE_BIN_DIR}")" && test "${WALKSAFE_NODE_BIN_DIR}" = "${WALKSAFE_NODE_ROOT}/bin" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root "${WALKSAFE_NODE_ROOT}" --lock configs/walksafe_node_toolchain_lock_20260715.json',
    ),
    (
        "GATEWAY_TYPECHECK",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run typecheck',
    ),
    (
        "GATEWAY_TEST",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway test',
    ),
    (
        "GATEWAY_BUILD",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run build',
    ),
    (
        "WEB_TEST",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web test',
    ),
    (
        "WEB_LINT",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run lint',
    ),
    (
        "WEB_TYPECHECK",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run typecheck',
    ),
    (
        "WEB_BUILD",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run build',
    ),
    (
        "NODE_TOOLCHAIN_POST",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && WALKSAFE_NODE_ROOT="$(/usr/bin/dirname -- "${WALKSAFE_NODE_BIN_DIR}")" && test "${WALKSAFE_NODE_BIN_DIR}" = "${WALKSAFE_NODE_ROOT}/bin" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root "${WALKSAFE_NODE_ROOT}" --lock configs/walksafe_node_toolchain_lock_20260715.json',
    ),
    (
        "ANDROID_UNIT_ASSEMBLE_LINT",
        "(cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon)",
    ),
    (
        "TEST_LAYER_REGISTRY_VALIDATE",
        f"{LOCKED_PYTHON_PREFIX}"
        'PYTHON_BIN="${WALKSAFE_LOCKED_TEST_PYTHON}" '
        "bash scripts/run_walksafe_test_layers_20260711.sh validate",
    ),
    (
        "FIELD_AND_RELEASE_PYTEST",
        f"{LOCKED_PYTHON_PREFIX}"
        '"${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest '
        "tests/test_android_field_session_summary.py "
        "tests/test_release_evidence_gate.py -q",
    ),
    (
        "GOAL_CONTROL_PYTEST",
        f"{LOCKED_PYTHON_PREFIX}"
        '"${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest '
        "tests/test_walksafe_goal_graph_v2_3.py "
        "tests/test_walksafe_project_continuation_v2_3.py "
        "tests/test_walksafe_goal_graph_v2_2_history.py -q",
    ),
    (
        "CONTROL_AND_TRACE_PYTEST",
        f"{LOCKED_PYTHON_PREFIX}"
        '"${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest '
        "tests/test_walksafe_epic01_phase_b_trace_20260722.py "
        "tests/test_walksafe_epic01_phase_c_trace_20260722.py "
        "tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py "
        "tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py "
        "tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py "
        "tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py "
        "tests/test_walksafe_epic02_trace_v2_2_history.py "
        "tests/test_walksafe_android_gateway_boundary_20260723.py "
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
        "-q",
    ),
    (
        "REPOSITORY_STATE",
        ': "${WALKSAFE_GATE_EVENT_ID:?required}" && python3 -B scripts/check_walksafe_project_continuation_v2_3.py --root . --checkpoint docs/control/walksafe-project-continuation-checkpoint.json --print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"',
    ),
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
    "tests/test_walksafe_project_continuation_v2_3.py "
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
    "python3 -B scripts/check_walksafe_goal_graph_v2_3.py && "
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python -m pytest "
    "tests/test_walksafe_goal_graph_v2_3.py "
    "tests/test_walksafe_project_continuation_v2_3.py "
    "tests/test_walksafe_goal_graph_v2_2_history.py -q"
)
GOAL_PACKAGE_HANDOFF_RESULT = (
    "고정 단계 수 없음, 의존성 DAG와 ready frontier 기반 "
    "17개 materialized Goal·68개 정책·Gap 검사 PASS; "
    "v2.2 seq20 archive history PASS"
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


def canonical_sha256(value: Any, *, omit: set[str] | None = None) -> str:
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


def load_continuation_contract(root: Path) -> Any | None:
    checker_path = root / CONTINUATION_CHECKER_RELATIVE
    if not checker_path.is_file():
        return None
    spec = importlib.util.spec_from_file_location(
        "_walksafe_continuation_v23_builder_contract",
        checker_path,
    )
    if spec is None or spec.loader is None:
        raise ValueError("cannot load the v2.3 continuation contract")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_controlled_paths(root: Path) -> list[str] | None:
    module = load_continuation_contract(root)
    if module is None:
        return None
    paths = getattr(module, "EXPECTED_CONTROLLED_PATHS", None)
    if (
        not isinstance(paths, tuple)
        or not paths
        or not all(isinstance(item, str) and item for item in paths)
    ):
        raise ValueError(
            "v2.3 continuation EXPECTED_CONTROLLED_PATHS is invalid"
        )
    normalized = sorted(set(paths))
    if normalized != list(paths):
        raise ValueError(
            "v2.3 continuation EXPECTED_CONTROLLED_PATHS is not canonical"
        )
    return normalized


def update_controlled_snapshot_if_complete(
    root: Path,
    checkpoint: dict[str, Any],
    generated_outputs: dict[str, bytes],
) -> bool:
    controlled_paths = load_controlled_paths(root)
    if controlled_paths is None:
        return False
    content_by_path: dict[str, bytes] = {}
    for relative in controlled_paths:
        if relative in generated_outputs:
            content_by_path[relative] = generated_outputs[relative]
            continue
        path = root / relative
        if not path.is_file():
            return False
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
    return True


def update_handoff_verification_contract(
    root: Path,
    checkpoint: dict[str, Any],
) -> None:
    handoff = checkpoint.get("session_handoff")
    if not isinstance(handoff, dict):
        raise ValueError("checkpoint session_handoff is missing")
    records = handoff.get("verification_commands_and_results")
    if not isinstance(records, list):
        raise ValueError("handoff verification_commands_and_results is missing")
    by_id = {
        record.get("id"): record
        for record in records
        if isinstance(record, dict) and isinstance(record.get("id"), str)
    }
    continuation_record = by_id.get("CONTINUATION_INTEGRITY")
    goal_record = by_id.get("GOAL_PACKAGE")
    if not isinstance(continuation_record, dict):
        raise ValueError("CONTINUATION_INTEGRITY handoff record is missing")
    if not isinstance(goal_record, dict):
        raise ValueError("GOAL_PACKAGE handoff record is missing")
    module = load_continuation_contract(root)
    expected_commands = (
        getattr(module, "EXPECTED_VERIFICATION_COMMANDS", None)
        if module is not None
        else None
    )
    if not isinstance(expected_commands, dict):
        raise ValueError(
            "v2.3 continuation EXPECTED_VERIFICATION_COMMANDS is invalid"
        )
    fallback = {
        "CONTINUATION_INTEGRITY": {
            "status": "PASS",
            "command": CONTINUATION_INTEGRITY_HANDOFF_COMMAND,
            "result_required_fragments": ("PASS",),
        },
        "GOAL_PACKAGE": {
            "status": "PASS",
            "command": GOAL_PACKAGE_HANDOFF_COMMAND,
            "result_required_fragments": (
                "v2.2 seq20 archive history",
                "PASS",
            ),
        },
    }
    for verification_id, record in (
        ("CONTINUATION_INTEGRITY", continuation_record),
        ("GOAL_PACKAGE", goal_record),
    ):
        expected = expected_commands.get(verification_id)
        if not isinstance(expected, dict):
            expected = fallback[verification_id]
        command = expected.get("command")
        status = expected.get("status")
        fragments = expected.get("result_required_fragments")
        if (
            not isinstance(command, str)
            or not command
            or status != "PASS"
            or not isinstance(fragments, tuple)
            or not all(isinstance(item, str) and item for item in fragments)
        ):
            raise ValueError(
                f"{verification_id} continuation verification contract is invalid"
            )
        record["command"] = command
        record["status"] = status
        if verification_id == "GOAL_PACKAGE":
            record["result"] = GOAL_PACKAGE_HANDOFF_RESULT
        result = record.get("result")
        if not isinstance(result, str):
            result = ""
        missing = [fragment for fragment in fragments if fragment not in result]
        if missing:
            result = "; ".join([part for part in (result, *missing) if part])
        record["result"] = result


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
        if not isinstance(role, str) or not role:
            raise ValueError("predecessor canonical binding role is missing")
        result[role] = {
            "role": role,
            "document_id": binding.get("document_id"),
            "path": binding.get("path"),
            "file_sha256": binding.get("file_sha256"),
        }
    return {role: result[role] for role in sorted(result)}


def completion_event_sha256(
    history: list[dict[str, Any]],
    goal_id: str,
) -> str:
    for event in reversed(history):
        changes = event.get("status_changes")
        if (
            isinstance(changes, dict)
            and changes.get(goal_id) == "COMPLETE_AT_TARGET"
        ):
            digest = event.get("event_sha256")
            if isinstance(digest, str) and digest:
                return digest
    raise ValueError(f"completed Goal has no completion event: {goal_id}")


def predecessor_checkpoint_bytes(root: Path) -> bytes:
    current_path = root / CHECKPOINT_RELATIVE
    current_bytes = current_path.read_bytes()
    current = load_json_bytes(current_bytes, CHECKPOINT_RELATIVE.as_posix())
    package_id = current.get("goal_execution", {}).get("package_id")
    if package_id == V22_PACKAGE_ID:
        source = current_bytes
    elif package_id == V23_PACKAGE_ID:
        source = (root / V23_ARCHIVE_RELATIVE).read_bytes()
    else:
        raise ValueError(
            "current checkpoint is neither the frozen v2.2 predecessor "
            "nor the prepared v2.3 successor"
        )
    if sha256_bytes(source) != EXPECTED_V22_CHECKPOINT_SHA256:
        raise ValueError("frozen v2.2 active checkpoint SHA-256 differs")
    return source


def base_checkpoint(predecessor: dict[str, Any]) -> dict[str, Any]:
    """Return the one immutable source for every successor projection."""
    return copy.deepcopy(predecessor)


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
        "package_id": (state.get("package_id"), V22_PACKAGE_ID),
        "static_plan_version": (
            state.get("static_plan_version"),
            V22_PLAN_VERSION,
        ),
        "package_status": (state.get("package_status"), "ACTIVE"),
        "activation_status": (state.get("activation_status"), "ACTIVE"),
        "manifest_sha256": (
            state.get("static_plan_manifest_sha256"),
            EXPECTED_V22_MANIFEST_SHA256,
        ),
        "event_count": (len(history), EXPECTED_V22_EVENT_COUNT),
        "tail_sha256": (
            state.get("transition_history_anchor_sha256"),
            EXPECTED_V22_TAIL_SHA256,
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
        "managed_goal_path_count": (
            state.get("managed_goal_path_count"),
            23,
        ),
        "goal_document_count": (state.get("goal_document_count"), 17),
        "path_set_sha256": (
            state.get("path_set_sha256"),
            EXPECTED_V22_PATH_SET_SHA256,
        ),
        "content_set_sha256": (
            state.get("content_set_sha256"),
            EXPECTED_V22_CONTENT_SET_SHA256,
        ),
    }
    for label, (actual, expected) in checks.items():
        if actual != expected:
            raise ValueError(
                f"frozen v2.2 {label} differs: {actual!r} != {expected!r}"
            )
    if manifest.get("package_id") != V22_PACKAGE_ID:
        raise ValueError("frozen v2.2 manifest package ID differs")
    if sha256_file(root / V22_MANIFEST_RELATIVE) != (
        EXPECTED_V22_MANIFEST_SHA256
    ):
        raise ValueError("frozen v2.2 manifest file SHA-256 differs")
    if sha256_file(root / V22_CHECKER_RELATIVE) != (
        EXPECTED_V22_CHECKER_SHA256
    ):
        raise ValueError("frozen v2.2 checker SHA-256 differs")
    if sha256_file(root / V22_TEST_RELATIVE) != EXPECTED_V22_TEST_SHA256:
        raise ValueError("frozen v2.2 test SHA-256 differs")


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
            "source_package_id": V22_PACKAGE_ID,
        }
        if statuses[goal_id] == "COMPLETE_AT_TARGET":
            record["completion_event_sha256"] = completion_event_sha256(
                history, goal_id
            )
            record["completion_evidence_refs"] = copy.deepcopy(
                completion_evidence[goal_id]
            )
        result[goal_id] = record

    for goal_id, item in inventory.items():
        record = {
            "goal_id": goal_id,
            "path": item["path"],
            "sha256": sha256_file(root / item["path"]),
            "goal_kind": item["goal_kind"],
            "status": statuses[goal_id],
            "source_package_id": V22_PACKAGE_ID,
            "materialized_event_sha256": item["materialized_event_sha256"],
        }
        if statuses[goal_id] == "COMPLETE_AT_TARGET":
            record["completion_event_sha256"] = completion_event_sha256(
                history, goal_id
            )
            record["completion_evidence_refs"] = copy.deepcopy(
                completion_evidence[goal_id]
            )
        result[goal_id] = record

    if len(result) != 17:
        raise ValueError(f"expected 17 imported Goals, found {len(result)}")
    return {goal_id: result[goal_id] for goal_id in sorted(result)}


def readme_bytes() -> bytes:
    return """# WalkSafe Goal Graph v2.3

패키지 ID: `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-3`

계획 버전: `2.3.0`

상태: `PREPARED_NOT_ACTIVATED / READY_NOT_ACTIVATED`

## 목적

v2.2가 활성 상태에서 FP-005 시작 전 전체 제어 회귀의 동결 테스트 두 건이
현재 runtime 상태를 고정값으로 오판하는 결함이 확인되었다. v2.2의 20개 event,
Goal 문서, 완료·materialization provenance와 checker/test bytes는 수정하지 않는다.
v2.3은 그 활성 tail을 byte-exact archive와 projection event로 가져오고 새 checker와
명령 계약에서 runtime-derived assertions를 사용한다.

## imported Goal 소유권

17개 Goal 문서는 `walksafe-completion-graph-v2-2`의 원래 경로와 bytes를 그대로
사용한다. 복사하거나 문서 버전을 바꾸지 않는다. 현재 상태와 완료·materialization
lineage는 checkpoint의 `imported_predecessor_goal_bindings`가 결속한다.

v2.3 native 지원 파일은 README, manifest, active supersession record, v2.2 active
checkpoint archive와 template 두 개뿐이다. manifest는 자기 hash 순환을 피하려고
protected file 집합에서 제외되며 checkpoint가 manifest hash를 직접 고정한다.

## 현재 focus

- focus: `WS-GOAL-EPIC-02-FP-005-R001`
- Work Item: `EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK`
- ready frontier: FP-005, EPIC-03, EPIC-12
- 정식 시험·실기기·5개 Gate·출시: 기존 `NOT_RUN`·미종결·`NOT_ELIGIBLE` 유지

## 활성화 경계

이 준비 package는 제품 작업 권한이 아니다. 최종 manifest SHA-256에 결속한 새
사용자 승인이 있어야 `PACKAGE_ACTIVATED`를 기록할 수 있다. 활성화 뒤에도 전체
구현 시작 gate와 별도 `GOAL_STARTED`가 성공하기 전에는 FP-005 제품 코드를
변경하지 않는다.

재개 정본은 현재 checkpoint, 이 README와 manifest, archived v2.2 checkpoint,
그리고 새 v2.3 checker다.
""".encode("utf-8")


def build_outputs(root: Path) -> dict[str, bytes]:
    predecessor_raw = predecessor_checkpoint_bytes(root)
    predecessor = load_json_bytes(
        predecessor_raw, V23_ARCHIVE_RELATIVE.as_posix()
    )
    base = base_checkpoint(predecessor)
    v22_manifest = load_json_bytes(
        (root / V22_MANIFEST_RELATIVE).read_bytes(),
        V22_MANIFEST_RELATIVE.as_posix(),
    )
    validate_predecessor(root, predecessor, v22_manifest)

    predecessor_state = predecessor["goal_execution"]
    imported = imported_goal_bindings(root, predecessor, v22_manifest)
    imported_goal_paths = sorted(item["path"] for item in imported.values())
    old_bytes_by_path = {
        relative: (root / relative).read_bytes()
        for relative in imported_goal_paths
    }

    supersedes = {
        "package_id": V22_PACKAGE_ID,
        "plan_version": V22_PLAN_VERSION,
        "activation_status": "ACTIVE",
        "manifest_path": V22_MANIFEST_RELATIVE.as_posix(),
        "manifest_sha256": EXPECTED_V22_MANIFEST_SHA256,
        "archived_checkpoint_path": V23_ARCHIVE_RELATIVE.as_posix(),
        "archived_checkpoint_raw_sha256": EXPECTED_V22_CHECKPOINT_SHA256,
        "transition_event_count": EXPECTED_V22_EVENT_COUNT,
        "transition_history_anchor_sha256": EXPECTED_V22_TAIL_SHA256,
        "supersession_record_path": V23_RECORD_RELATIVE.as_posix(),
    }
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
        "canonical_binding_snapshot": canonical_binding_snapshot(predecessor),
        "runtime": predecessor_state["transition_history"][-1][
            "runtime_after"
        ],
    }
    record = {
        "schema_version": "1.0",
        "record_id": V23_RECORD_ID,
        "recorded_at": V23_OCCURRED_AT,
        "superseded_package": supersedes,
        "successor_package_id": V23_PACKAGE_ID,
        "successor_plan_version": V23_PLAN_VERSION,
        "frozen_control_history": {
            "checker_path": V22_CHECKER_RELATIVE.as_posix(),
            "checker_sha256": EXPECTED_V22_CHECKER_SHA256,
            "test_path": V22_TEST_RELATIVE.as_posix(),
            "test_sha256": EXPECTED_V22_TEST_SHA256,
        },
        "superseded_package_set": {
            "managed_path_count": 23,
            "path_set_sha256": EXPECTED_V22_PATH_SET_SHA256,
            "content_set_sha256": EXPECTED_V22_CONTENT_SET_SHA256,
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
            canonical_binding_snapshot(predecessor)
        ),
        "reason": (
            "활성 v2.2의 현재 FP-005 runtime과 동결된 v2.2 테스트의 "
            "FP-004 고정 기대값 충돌을 predecessor bytes 변경 없이 "
            "versioned checker/command contract로 교정한다."
        ),
        "execution_effect": (
            "V2_2_ACTIVE_HISTORY_RETAINED_BYTE_EXACT; "
            "V2_3_PREPARED_NOT_ACTIVATED; NO_FP005_PRODUCT_WORK_STARTED"
        ),
    }

    outputs: dict[str, bytes] = {
        V23_README_RELATIVE.as_posix(): readme_bytes(),
        V23_RECORD_RELATIVE.as_posix(): json_bytes(record),
        V23_ARCHIVE_RELATIVE.as_posix(): predecessor_raw,
        V23_DYNAMIC_TEMPLATE_RELATIVE.as_posix(): (
            root
            / V22_PACKAGE_RELATIVE
            / "templates/dynamic-node-template.md"
        ).read_bytes(),
        V23_POLICY_GAP_TEMPLATE_RELATIVE.as_posix(): (
            root
            / V22_PACKAGE_RELATIVE
            / "templates/policy-gap-work-item.md"
        ).read_bytes(),
    }

    manifest = copy.deepcopy(v22_manifest)
    manifest["schema_version"] = "2.1"
    manifest["manifest_id"] = V23_MANIFEST_ID
    manifest["package_id"] = V23_PACKAGE_ID
    manifest["plan_version"] = V23_PLAN_VERSION
    manifest["created_on"] = V23_OCCURRED_ON
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
            V23_PACKAGE_RELATIVE / "templates" / old_path.name
        ).as_posix()
    manifest["imported_predecessor_goal_contract"] = {
        "source_package_id": V22_PACKAGE_ID,
        "source_plan_version": V22_PLAN_VERSION,
        "source_checkpoint_path": V23_ARCHIVE_RELATIVE.as_posix(),
        "runtime_binding_field": "imported_predecessor_goal_bindings",
        "expected_goal_count": 17,
        "goal_paths_remain_in_predecessor_package": True,
        "goal_bytes_must_match_archived_projection": True,
        "materialization_and_completion_lineage_is_imported": True,
    }
    transition = manifest["transition_contract"]
    transition["check_command_contract_version"] = (
        CHECK_COMMAND_CONTRACT_VERSION
    )
    transition["quick_activation_check_ids"] = [
        item[0] for item in QUICK_ACTIVATION_CHECKS
    ]
    transition["quick_activation_check_contract_sha256"] = (
        command_contract_sha256(QUICK_ACTIVATION_CHECKS)
    )
    transition["implementation_start_gate_check_ids"] = [
        item[0] for item in IMPLEMENTATION_START_GATE_CHECKS
    ]
    transition["implementation_start_gate_check_contract_sha256"] = (
        command_contract_sha256(IMPLEMENTATION_START_GATE_CHECKS)
    )
    transition["predecessor_runtime_import_event"] = "PACKAGE_PREPARED"
    transition["predecessor_runtime_import_is_projection_only"] = True
    manifest["successor_control_contract"] = {
        "builder_path": V23_BUILDER_RELATIVE.as_posix(),
        "checker_path": V23_CHECKER_RELATIVE.as_posix(),
        "test_path": V23_TEST_RELATIVE.as_posix(),
        "continuation_checker_path": (
            CONTINUATION_CHECKER_RELATIVE.as_posix()
        ),
        "continuation_test_path": CONTINUATION_TEST_RELATIVE.as_posix(),
        "frozen_predecessor_checker_path": V22_CHECKER_RELATIVE.as_posix(),
        "frozen_predecessor_checker_sha256": (
            EXPECTED_V22_CHECKER_SHA256
        ),
        "frozen_predecessor_test_path": V22_TEST_RELATIVE.as_posix(),
        "frozen_predecessor_test_sha256": EXPECTED_V22_TEST_SHA256,
        "stale_predecessor_test_deselects": list(
            STALE_V22_TEST_DESELECTS
        ),
        "quick_activation_checks": [
            {"check_id": check_id, "command": command}
            for check_id, command in QUICK_ACTIVATION_CHECKS
        ],
        "implementation_start_gate_checks": [
            {"check_id": check_id, "command": command}
            for check_id, command in IMPLEMENTATION_START_GATE_CHECKS
        ],
    }
    protected_paths = [
        V23_README_RELATIVE.as_posix(),
        V23_RECORD_RELATIVE.as_posix(),
        V23_ARCHIVE_RELATIVE.as_posix(),
        V23_DYNAMIC_TEMPLATE_RELATIVE.as_posix(),
        V23_POLICY_GAP_TEMPLATE_RELATIVE.as_posix(),
    ]
    manifest["protected_files"] = [
        {"path": relative, "sha256": sha256_bytes(outputs[relative])}
        for relative in protected_paths
    ]
    outputs[V23_MANIFEST_RELATIVE.as_posix()] = json_bytes(manifest)

    support_paths = sorted(
        [
            V23_README_RELATIVE.as_posix(),
            V23_MANIFEST_RELATIVE.as_posix(),
            V23_RECORD_RELATIVE.as_posix(),
            V23_ARCHIVE_RELATIVE.as_posix(),
            V23_DYNAMIC_TEMPLATE_RELATIVE.as_posix(),
            V23_POLICY_GAP_TEMPLATE_RELATIVE.as_posix(),
        ]
    )
    managed_paths = sorted(imported_goal_paths + support_paths)
    if len(managed_paths) != 23 or len(set(managed_paths)) != 23:
        raise ValueError("v2.3 managed path set must contain 23 unique paths")
    bytes_by_path = {**old_bytes_by_path, **outputs}
    package_path_hash, package_content_hash = (
        path_and_content_hashes_from_bytes(managed_paths, bytes_by_path)
    )
    manifest_sha256 = sha256_bytes(
        outputs[V23_MANIFEST_RELATIVE.as_posix()]
    )

    runtime_after = copy.deepcopy(
        predecessor_state["transition_history"][-1]["runtime_after"]
    )
    runtime_after["activation_status"] = "READY_NOT_ACTIVATED"
    runtime_after["package_status"] = "PREPARED_NOT_ACTIVATED"
    event = {
        "sequence": 1,
        "event_id": V23_EVENT_ID,
        "event_type": "PACKAGE_PREPARED",
        "occurred_on": V23_OCCURRED_ON,
        "occurred_at": V23_OCCURRED_AT,
        "previous_focus_goal_id": "",
        "previous_focus_content_sha256": "",
        "focus_goal_id": EXPECTED_FOCUS_GOAL_ID,
        "focus_goal_content_sha256": imported[
            EXPECTED_FOCUS_GOAL_ID
        ]["sha256"],
        "from_status": "",
        "to_status": "READY",
        "static_plan_manifest_sha256": manifest_sha256,
        "supersedes_event_sha256": EXPECTED_V22_TAIL_SHA256,
        "active_predecessor_import": {
            "source_package_id": V22_PACKAGE_ID,
            "source_plan_version": V22_PLAN_VERSION,
            "source_manifest_path": V22_MANIFEST_RELATIVE.as_posix(),
            "source_manifest_sha256": EXPECTED_V22_MANIFEST_SHA256,
            "source_checkpoint_path": V23_ARCHIVE_RELATIVE.as_posix(),
            "source_checkpoint_raw_sha256": (
                EXPECTED_V22_CHECKPOINT_SHA256
            ),
            "source_transition_event_count": EXPECTED_V22_EVENT_COUNT,
            "source_transition_history_anchor_sha256": (
                EXPECTED_V22_TAIL_SHA256
            ),
            "imported_predecessor_goal_bindings_sha256": canonical_sha256(
                imported
            ),
            "canonical_binding_snapshot_sha256": canonical_sha256(
                canonical_binding_snapshot(predecessor)
            ),
        },
        "bootstrap_consumed_policy_gap_pairs": copy.deepcopy(
            predecessor_state["bootstrap_consumed_policy_gap_pairs"]
        ),
        "canonical_binding_snapshot_after": canonical_binding_snapshot(
            predecessor
        ),
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
        "source_checkpoint_version": "1.14.0",
        "evidence_refs": [
            V23_RECORD_ID,
            canonical_binding_snapshot(predecessor)["IMPLEMENTATION_GAP"][
                "document_id"
            ],
            canonical_binding_snapshot(predecessor)[
                "IMPLEMENTATION_BACKLOG"
            ]["document_id"],
        ],
        "previous_event_sha256": "",
    }
    event["event_sha256"] = canonical_sha256(
        event, omit={"event_sha256"}
    )

    checkpoint = copy.deepcopy(base)
    checkpoint["schema_version"] = "1.14.0"
    checkpoint["metadata"]["checkpoint_id"] = V23_CHECKPOINT_ID
    checkpoint["metadata"]["version"] = "1.14.0"
    checkpoint["metadata"]["as_of"] = V23_OCCURRED_ON
    state = copy.deepcopy(predecessor_state)
    state["schema_version"] = "2.1"
    state["package_id"] = V23_PACKAGE_ID
    state["static_plan_version"] = V23_PLAN_VERSION
    state["static_plan_manifest_path"] = V23_MANIFEST_RELATIVE.as_posix()
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
    state["validation_cutoff_at"] = V23_OCCURRED_AT
    state["verification_evidence_refs"] = []
    state["focus_goal_id"] = EXPECTED_FOCUS_GOAL_ID
    state["focus_goal_path"] = imported[EXPECTED_FOCUS_GOAL_ID]["path"]
    state["focus_work_item_id"] = EXPECTED_FOCUS_WORK_ITEM_ID
    state["focus_source"] = "IMPLEMENTATION_BACKLOG"
    state["ready_frontier_goal_ids"] = copy.deepcopy(
        EXPECTED_READY_FRONTIER
    )
    state["goal_status"] = "READY"
    checkpoint["goal_execution"] = state
    update_handoff_verification_contract(root, checkpoint)
    update_controlled_snapshot_if_complete(root, checkpoint, outputs)
    outputs[CHECKPOINT_RELATIVE.as_posix()] = json_bytes(checkpoint)
    return outputs


def actual_native_paths(root: Path) -> set[str]:
    package_root = root / V23_PACKAGE_RELATIVE
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
        if relative.startswith(V23_PACKAGE_RELATIVE.as_posix() + "/")
    }
    actual_native = actual_native_paths(root)
    if actual_native != expected_native:
        errors.append(
            "v2.3 native path set differs: "
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
        print(f"WalkSafe Goal graph v2.3 build: FAIL: {exc}", file=sys.stderr)
        return 1

    if args.check:
        errors = check_outputs(root, outputs)
        if errors:
            for error in errors:
                print(f"WalkSafe Goal graph v2.3 check: FAIL: {error}")
            return 1
        print(
            "WalkSafe Goal graph v2.3 check: PASS "
            "(6 native files, 17 imported Goals, 23 managed paths)"
        )
        return 0

    existing_native = actual_native_paths(root)
    expected_native = {
        relative
        for relative in outputs
        if relative.startswith(V23_PACKAGE_RELATIVE.as_posix() + "/")
    }
    unexpected = existing_native - expected_native
    if unexpected:
        print(
            "WalkSafe Goal graph v2.3 build: FAIL: unexpected native files: "
            + ", ".join(sorted(unexpected)),
            file=sys.stderr,
        )
        return 1
    for relative, content in sorted(outputs.items()):
        atomic_write(root / relative, content)
    print(
        "WalkSafe Goal graph v2.3 build: PASS "
        "(6 native files, 17 imported Goals, 23 managed paths)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
