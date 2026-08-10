#!/usr/bin/env python3
"""Preflight the add-only FP-046 Goal materialization and READY projection.

This tool never writes the live checkpoint.  It accepts only the byte-exact
FP008 COMPLETE seq50 checkpoint and builds seq51/52 in memory for validation.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import stat
import sys
from pathlib import Path
from typing import Any, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract


CHECKPOINT = Path("docs/control/walksafe-project-continuation-checkpoint.json")
SOURCE_CHECKPOINT_SHA256 = (
    "8ed5424c1ac98284bcd5e4e7b4b602760504b8decfe65ad949e9790cda81d087"
)
SOURCE_CHECKPOINT_BYTE_COUNT = 1_579_087
SOURCE_SEQUENCE = 50
SOURCE_TAIL_SHA256 = (
    "caa5d73aa19416d997e7ef416bfa9a9af5383bde95541ad93fd31e44ee6cafd4"
)
SOURCE_WORKING_SNAPSHOT_PATH_SET_SHA256 = (
    "32db501ebc849f2caa1cd043fe68e34820360de85d238f48683824cd9c371733"
)
SOURCE_WORKING_SNAPSHOT_CONTENT_SET_SHA256 = (
    "03e718f8709ccb0255d413104f1d0cc5d50d52cc79c620eba08fb466050867f4"
)

GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R001"
GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-03/epic-03-fp046-consent-withdrawal-deletion-r001.md"
)
GOAL_SHA256 = (
    "f8f1fc9e9b8c1eaabe543cd64130b5a6dfa3b908b318033cb5969d4a724e8d79"
)
WORK_ITEM_ID = "EPIC-03-FP046-CONSENT-WITHDRAWAL-DELETION"
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PARENT_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
PARENT_GOAL_SHA256 = (
    "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
)
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-008-R001"
PREDECESSOR_GOAL_SHA256 = (
    "2654fe5f595aefaecf974a4de70bad7946c2385cba9716200f2d2f49d849dd8e"
)
PREDECESSOR_COMPLETION_EVENT_SHA256 = SOURCE_TAIL_SHA256

BACKLOG_PATH = (
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260809-r024.json"
)
BACKLOG_ID = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260809-024"
BACKLOG_SHA256 = (
    "f2c860d8d5199bdd18da62fd44bf0e161a7f6b9933bde7eebed86afb9fb8b55d"
)
GAP_PATH = (
    "docs/control/audits/"
    "walksafe-implementation-gap-analysis-20260809-r024.json"
)
GAP_ID = "WS-IMPLEMENTATION-GAP-ANALYSIS-20260809-024"
GAP_SHA256 = (
    "e9e95998875660663ad7d573cf193ad7b80de12fc64a1d5ecda1285ff71b38e0"
)

MATERIALIZED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-FP046-20260809-001"
)
READY_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP046-20260809-001"
MATERIALIZED_AT = "2026-08-09T19:20:00+09:00"
READY_AT = "2026-08-09T19:20:01+09:00"

START_GATE_CONTRACT_PATH = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-046-R001/"
    "initial-start-gate-contract-r001.json"
)
START_GATE_CONTRACT_DOCUMENT_ID = (
    "WS-FP046-INITIAL-START-GATE-CONTRACT-20260809-001"
)
START_GATE_CONTRACT_ID = "WS-FP046-INTERNAL-START-GATE-R001"
START_GATE_CONTRACT_VERSION = "2026-08-09.1"
START_GATE_CONTRACT_FILE_SHA256 = (
    "ec4b3fa6e2657f37770cba949abe68a944640690325d7f78033779f7693fdd7c"
)
START_GATE_CONTRACT_CANONICAL_SHA256 = (
    "7861e9f0e1547f2577520ef5ff9c18349e1f003b02870304cac274a0f88f4d10"
)
START_GATE_CHECK_IDS = (
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_TEST_DATABASE_PREFLIGHT",
    "BACKEND_REPORT_STORAGE_RETENTION_POSTGRES",
    "ANDROID_USER_INTERNAL",
    "ANDROID_GATEWAY_PRIVACY_INTERNAL",
    "ROOT_FP046_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)

AUTHORIZED_SOURCE_DELTAS = (
    {
        "path": "scripts/check_walksafe_project_continuation_v2_4.py",
        "before_sha256": (
            "09dcc2e7a16ab964838e5fe259f56f0be5a94f52391959a1945692aa101caa4f"
        ),
        "after_sha256": (
            "c60be7921a30cf80992f8d24a61d8ca44e9958c98893a54c8c70b3771c72a004"
        ),
        "reason": "FP046 target-scoped initial-start contract and receipt replay",
    },
    {
        "path": "scripts/run_walksafe_test_layers_20260711.sh",
        "before_sha256": (
            "62d615c191c1076ba14e92594ea6c8f5a2fd11ba98c940e07c6da1ac3ee0ee7f"
        ),
        "after_sha256": (
            "cdcbe807fed9d108072d5c5c30279ebcc71b1ccfadcacd6872552852d6a57fca"
        ),
        "reason": "FP046 active transition regressions remain explicitly inventoried",
    },
    {
        "path": "tests/test_walksafe_fp008_goal_completed_seq49_50_20260809.py",
        "before_sha256": (
            "6be9334e378048a5271c1a8e695c43c39065009c137c7e2ecf4dfa53ed6ac52a"
        ),
        "after_sha256": (
            "ede8e85c26768d57f1f8e13216ea03397918278caf0b9e43c9166aec88a2087b"
        ),
        "reason": "FP008 completion regression now reconstructs the exact seq48 source fixture",
    },
)

EXPECTED_QUEUE_SHA256 = (
    "2f6b3fdfed8d7a70a582d861edd4feee7ed35dab30d2803c061f4bf716340314"
)
EXPECTED_MATERIALIZED_BOUNDARY_SHA256 = (
    "eb7bab4cedd9aeab08aacafd7c52d077423f88dbed790a62ea3915e92d93b2f1"
)
EXPECTED_READY_BOUNDARY_SHA256 = (
    "d11c16ebbfe3384fc4f520546f2a9ae8241475df412b4151959d7c887461d2dd"
)
EXPECTED_MATERIALIZED_EVENT_SHA256 = (
    "1d555f00ec2685d51b32642de1e2627b71c0e68f26fe1b990b86d258490eda5c"
)
EXPECTED_READY_EVENT_SHA256 = (
    "c15744ff17835efe9dcff8a60a515c4c60b4eaf86e480544fca48bd9a49e1c62"
)

MATERIALIZED_FRONTIER = [PARENT_GOAL_ID, "WS-GOAL-EPIC-12"]
READY_FRONTIER = [GOAL_ID, PARENT_GOAL_ID, "WS-GOAL-EPIC-12"]
SOURCE_PATHS = (
    GOAL_PATH.as_posix(),
    START_GATE_CONTRACT_PATH.as_posix(),
    "scripts/materialize_walksafe_fp046_goal_seq51_52_20260809.py",
    "scripts/apply_walksafe_fp046_goal_seq51_52_20260809.py",
    "scripts/run_walksafe_fp046_goal_start_gate_20260809.py",
    "scripts/apply_walksafe_fp046_goal_started_seq53_20260809.py",
    "tests/test_walksafe_fp046_goal_seq51_52_20260809.py",
    "tests/test_walksafe_fp046_goal_start_gate_20260809.py",
    "tests/test_walksafe_fp046_goal_started_seq53_20260809.py",
    "tests/fixtures/walksafe-project-continuation-checkpoint-seq48-20260809.json.gz.b64",
)


class ProjectionError(RuntimeError):
    """The exact seq50 source cannot safely produce the FP046 candidate."""


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ProjectionError(message)


def _content_set_sha256_from_digests(
    paths: list[str],
    digests: dict[str, str],
) -> str:
    value = hashlib.sha256()
    for relative in sorted(paths):
        value.update(relative.encode("utf-8"))
        value.update(b"\0")
        value.update(digests[relative].encode("ascii"))
        value.update(b"\n")
    return value.hexdigest()


def require_authorized_source_delta(
    root: Path,
    source: dict[str, Any],
) -> dict[str, Any]:
    """Prove that every post-seq50 controlled byte change is enumerated."""

    snapshot = source.get("working_tree_snapshot")
    _require(isinstance(snapshot, dict), "source working snapshot is missing")
    paths = snapshot.get("managed_changed_paths")
    _require(
        isinstance(paths, list)
        and len(paths) == 712
        and len(set(paths)) == 712
        and all(isinstance(path, str) for path in paths),
        "source managed path inventory differs",
    )
    normalized = sorted(paths)
    path_set_sha256 = hashlib.sha256(
        ("\n".join(normalized) + "\n").encode("utf-8")
    ).hexdigest()
    _require(
        path_set_sha256 == SOURCE_WORKING_SNAPSHOT_PATH_SET_SHA256
        and snapshot.get("path_set_sha256") == path_set_sha256,
        "source working snapshot path set differs",
    )
    _require(
        snapshot.get("content_set_sha256")
        == SOURCE_WORKING_SNAPSHOT_CONTENT_SET_SHA256,
        "source working snapshot authority differs",
    )

    actual: dict[str, str] = {}
    for relative in normalized:
        relative_path = Path(relative)
        _require(
            not relative_path.is_absolute() and ".." not in relative_path.parts,
            f"source managed path is unsafe: {relative}",
        )
        path = root / relative_path
        _require(
            not path.is_symlink() and path.is_file(),
            f"source managed path is missing or unsafe: {relative}",
        )
        actual[relative] = contract.sha256_file(path)

    authorized = {item["path"]: item for item in AUTHORIZED_SOURCE_DELTAS}
    _require(
        len(authorized) == len(AUTHORIZED_SOURCE_DELTAS)
        and set(authorized).issubset(actual),
        "authorized source delta inventory differs",
    )
    for relative, item in authorized.items():
        before = item["before_sha256"]
        after = item["after_sha256"]
        _require(
            isinstance(before, str)
            and isinstance(after, str)
            and len(before) == 64
            and len(after) == 64
            and all(character in "0123456789abcdef" for character in before + after),
            f"authorized source delta digest is not finalized: {relative}",
        )
        _require(actual[relative] == after, f"authorized source delta differs: {relative}")
        _require(before != after, f"authorized source delta is not a change: {relative}")

    reconstructed = copy.deepcopy(actual)
    for relative, item in authorized.items():
        reconstructed[relative] = item["before_sha256"]
    _require(
        _content_set_sha256_from_digests(normalized, reconstructed)
        == SOURCE_WORKING_SNAPSHOT_CONTENT_SET_SHA256,
        "unlisted controlled-tree drift exists after seq50",
    )
    return {
        "source_path_set_sha256": SOURCE_WORKING_SNAPSHOT_PATH_SET_SHA256,
        "source_content_set_sha256": SOURCE_WORKING_SNAPSHOT_CONTENT_SET_SHA256,
        "verified_current_content_set_sha256": _content_set_sha256_from_digests(
            normalized,
            actual,
        ),
        "authorized_changed_paths": copy.deepcopy(list(AUTHORIZED_SOURCE_DELTAS)),
    }


def load_start_gate_contract(root: Path) -> dict[str, Any]:
    path = root / START_GATE_CONTRACT_PATH
    _require(not path.is_symlink() and path.is_file(), "FP046 start-gate contract is missing or unsafe")
    _require(path.stat().st_nlink == 1, "FP046 start-gate contract must have one hard link")
    raw = path.read_bytes()
    _require(
        sha256_bytes(raw) == START_GATE_CONTRACT_FILE_SHA256,
        "FP046 start-gate contract file SHA-256 differs",
    )
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProjectionError("FP046 start-gate contract is not valid JSON") from exc
    _require(isinstance(value, dict), "FP046 start-gate contract root differs")
    _require(
        contract.canonical_json_sha256(value)
        == START_GATE_CONTRACT_CANONICAL_SHA256,
        "FP046 start-gate canonical SHA-256 differs",
    )
    _require(
        set(value)
        == {
            "schema_version",
            "document_id",
            "contract_id",
            "contract_version",
            "target_goal_id",
            "target_goal_content_sha256",
            "gate_purpose",
            "ordered_checks",
        },
        "FP046 start-gate contract field set differs",
    )
    _require(
        value.get("schema_version") == "1.0"
        and value.get("document_id") == START_GATE_CONTRACT_DOCUMENT_ID
        and value.get("contract_id") == START_GATE_CONTRACT_ID
        and value.get("contract_version") == START_GATE_CONTRACT_VERSION
        and value.get("target_goal_id") == GOAL_ID
        and value.get("target_goal_content_sha256") == GOAL_SHA256
        and value.get("gate_purpose") == "INITIAL_START",
        "FP046 start-gate contract identity differs",
    )
    checks = value.get("ordered_checks")
    _require(
        isinstance(checks, list)
        and len(checks) == len(START_GATE_CHECK_IDS)
        and all(
            isinstance(item, dict)
            and set(item) == {"check_id", "command"}
            and isinstance(item.get("check_id"), str)
            and isinstance(item.get("command"), str)
            and item["command"]
            for item in checks
        ),
        "FP046 start-gate ordered check records differ",
    )
    _require(
        tuple(item["check_id"] for item in checks) == START_GATE_CHECK_IDS,
        "FP046 start-gate ordered check IDs differ",
    )
    forbidden = (
        "apps/web",
        "adminapp",
        "connecteddebugandroidtest",
        " adb ",
        "device",
        "external",
        "formal",
        "deploy",
        "release",
    )
    _require(
        not any(
            token in item["command"].lower()
            for item in checks
            for token in forbidden
        ),
        "FP046 start-gate contract contains a forbidden command scope",
    )
    return value


def start_gate_contract_binding() -> dict[str, str]:
    return {
        "schema_version": "1.0",
        "document_id": START_GATE_CONTRACT_DOCUMENT_ID,
        "path": START_GATE_CONTRACT_PATH.as_posix(),
        "file_sha256": START_GATE_CONTRACT_FILE_SHA256,
        "contract_id": START_GATE_CONTRACT_ID,
        "contract_version": START_GATE_CONTRACT_VERSION,
        "canonical_contract_sha256": START_GATE_CONTRACT_CANONICAL_SHA256,
    }


def load_exact_source(root: Path) -> tuple[bytes, dict[str, Any]]:
    path = root / CHECKPOINT
    _require(not path.is_symlink() and path.is_file(), "checkpoint is missing or unsafe")
    metadata = path.stat()
    _require(stat.S_IMODE(metadata.st_mode) == 0o600, "checkpoint mode is not 0600")
    source_bytes = path.read_bytes()
    _require(
        len(source_bytes) == SOURCE_CHECKPOINT_BYTE_COUNT,
        "checkpoint byte count differs from seq50",
    )
    _require(
        sha256_bytes(source_bytes) == SOURCE_CHECKPOINT_SHA256,
        "checkpoint SHA-256 differs from seq50",
    )
    try:
        source = json.loads(source_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProjectionError("checkpoint is not valid JSON") from exc
    _require(isinstance(source, dict), "checkpoint root is not an object")
    require_exact_source(source)
    return source_bytes, source


def require_exact_source(source: dict[str, Any]) -> None:
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    statuses = state.get("status_by_goal") if isinstance(state, dict) else None
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    _require(isinstance(history, list) and len(history) == SOURCE_SEQUENCE, "source history differs")
    tail = history[-1] if isinstance(history, list) and history else {}
    _require(
        isinstance(tail, dict)
        and tail.get("sequence") == SOURCE_SEQUENCE
        and tail.get("event_type") == "GOAL_COMPLETED"
        and tail.get("subject_goal_id") == PREDECESSOR_GOAL_ID
        and tail.get("event_sha256") == SOURCE_TAIL_SHA256
        and contract.event_sha256(tail) == SOURCE_TAIL_SHA256,
        "source tail is not the exact FP008 completion",
    )
    _require(
        state.get("transition_history_anchor_sha256") == SOURCE_TAIL_SHA256,
        "source history anchor differs",
    )
    _require(
        state.get("focus_goal_id") == PARENT_GOAL_ID
        and state.get("focus_goal_path") == PARENT_GOAL_PATH
        and state.get("focus_work_item_id") == ""
        and state.get("focus_source") == "WORKSTREAM_GRAPH",
        "source focus is not the EPIC-03 workstream",
    )
    _require(
        isinstance(statuses, dict)
        and statuses.get(PREDECESSOR_GOAL_ID) == "COMPLETE_AT_TARGET"
        and statuses.get(PARENT_GOAL_ID) == "READY"
        and GOAL_ID not in statuses
        and "IN_PROGRESS" not in statuses.values(),
        "source Goal status boundary differs",
    )
    _require(
        state.get("ready_frontier_goal_ids") == MATERIALIZED_FRONTIER,
        "source ready frontier differs",
    )
    _require(
        state.get("blocked_goal_ids") == []
        and state.get("pending_questions") == []
        and state.get("open_question_count") == 0,
        "source has an unresolved blocker or question",
    )
    _require(
        state.get("activation_status") == "ACTIVE"
        and state.get("package_status") == "ACTIVE",
        "source package is not active",
    )
    current = source.get("current_work")
    _require(
        isinstance(current, dict)
        and current.get("work_item_id") == WORK_ITEM_ID
        and current.get("current_focus")
        == "FP008/GAP-017 COMPLETE_AT_TARGET; FP046/GAP-055 PLANNED_NEXT",
        "source current-work pointer differs",
    )


def require_static_inputs(root: Path, source: dict[str, Any]) -> None:
    for relative, digest, label in (
        (GOAL_PATH.as_posix(), GOAL_SHA256, "Goal"),
        (BACKLOG_PATH, BACKLOG_SHA256, "backlog r024"),
        (GAP_PATH, GAP_SHA256, "gap r024"),
    ):
        path = root / relative
        _require(not path.is_symlink() and path.is_file(), f"{label} is missing or unsafe")
        _require(contract.sha256_file(path) == digest, f"{label} SHA-256 differs")

    load_start_gate_contract(root)
    node, _ = goal_graph.frozen_goal.parse_goal(root / GOAL_PATH)
    expected = {
        "goal_id": GOAL_ID,
        "goal_kind": "WORK_ITEM",
        "parent_goal_id": PARENT_GOAL_ID,
        "work_item_type": "POLICY_GAP_WORK",
        "priority_rank": 21,
        "initial_status": "PLANNED",
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "work_item_id": WORK_ITEM_ID,
        "start_requires": [PREDECESSOR_GOAL_ID],
        "completion_requires": [PREDECESSOR_GOAL_ID],
        "source_policy_ids": ["FP-046"],
        "gap_ids": ["GAP-055"],
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": BACKLOG_PATH,
        "materialized_from_document_id": BACKLOG_ID,
        "materialized_from_sha256": BACKLOG_SHA256,
        "predecessor_goal_id": PREDECESSOR_GOAL_ID,
        "predecessor_goal_content_sha256": PREDECESSOR_GOAL_SHA256,
    }
    _require(
        all(node.get(key) == value for key, value in expected.items()),
        "FP046 Goal metadata differs",
    )
    snapshot = contract.canonical_binding_snapshot(source)
    _require(len(snapshot) == 40, "canonical binding count differs")
    _require(
        snapshot.get("IMPLEMENTATION_BACKLOG")
        == {
            "role": "IMPLEMENTATION_BACKLOG",
            "document_id": BACKLOG_ID,
            "path": BACKLOG_PATH,
            "file_sha256": BACKLOG_SHA256,
        },
        "canonical backlog binding differs",
    )
    _require(
        snapshot.get("IMPLEMENTATION_GAP")
        == {
            "role": "IMPLEMENTATION_GAP",
            "document_id": GAP_ID,
            "path": GAP_PATH,
            "file_sha256": GAP_SHA256,
        },
        "canonical gap binding differs",
    )


def _runtime_projection(
    state: dict[str, Any],
    *,
    focus_goal_id: str,
    focus_goal_path: str,
    focus_work_item_id: str,
    focus_source: str,
    frontier: list[str],
    queue: dict[str, Any],
    boundary: dict[str, Any],
) -> dict[str, Any]:
    return {
        "focus_goal_id": focus_goal_id,
        "focus_goal_path": focus_goal_path,
        "focus_work_item_id": focus_work_item_id,
        "focus_source": focus_source,
        "ready_frontier_goal_ids": copy.deepcopy(frontier),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": contract.canonical_json_sha256(queue),
        "completion_boundary_sha256": contract.canonical_json_sha256(boundary),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def _derive_queue_and_boundary(
    root: Path,
    checkpoint: dict[str, Any],
    frontier: list[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    state = checkpoint["goal_execution"]
    bindings = goal_graph.frozen_goal.canonical_binding_map(checkpoint)
    register_binding = bindings.get("ARTIFACT_REGISTER")
    _require(isinstance(register_binding, dict), "ARTIFACT_REGISTER binding is missing")
    register = contract.load_json(root / register_binding["path"])
    node_errors, nodes = goal_graph.frozen_goal.current_goal_nodes(root, state)
    _require(not node_errors, "Goal node derivation failed: " + "; ".join(node_errors))
    queue_errors, queue = goal_graph.derive_v24_artifact_work_queue_from_register(
        register_binding,
        register,
        nodes,
        state["status_by_goal"],
    )
    _require(not queue_errors, "artifact queue derivation failed: " + "; ".join(queue_errors))
    boundary_errors, boundary = goal_graph.frozen_goal.derive_completion_boundary(
        nodes,
        state["status_by_goal"],
        frontier,
        state["blockers_by_goal"],
        queue,
        package_status=state["package_status"],
    )
    _require(
        not boundary_errors,
        "completion boundary derivation failed: " + "; ".join(boundary_errors),
    )
    return queue, boundary


def _inventory_record(materialized_event_sha256: str) -> dict[str, Any]:
    return {
        "goal_id": GOAL_ID,
        "path": GOAL_PATH.as_posix(),
        "sha256": GOAL_SHA256,
        "goal_kind": "WORK_ITEM",
        "work_item_type": "POLICY_GAP_WORK",
        "parent_goal_id": PARENT_GOAL_ID,
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": BACKLOG_PATH,
        "materialized_from_document_id": BACKLOG_ID,
        "materialized_from_sha256": BACKLOG_SHA256,
        "predecessor_goal_id": PREDECESSOR_GOAL_ID,
        "predecessor_goal_content_sha256": PREDECESSOR_GOAL_SHA256,
        "supersedes_goal_id": "",
        "supersedes_goal_content_sha256": "",
        "artifact_work_reason": "",
        "artifact_trigger_evidence_refs": [],
        "initial_status": "PLANNED",
        "materialized_event_sha256": materialized_event_sha256,
    }


def _require_pin(actual: str, expected: str, label: str) -> None:
    if expected:
        _require(actual == expected, f"{label} SHA-256 differs")


def project(
    root: Path,
    source: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    require_exact_source(source)
    require_static_inputs(root, source)
    source_delta = require_authorized_source_delta(root, source)
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    canonical_snapshot = contract.canonical_binding_snapshot(checkpoint)

    goal_paths = sorted([*state["goal_document_paths"], GOAL_PATH.as_posix()])
    managed_goal_paths = sorted([*state["managed_goal_paths"], GOAL_PATH.as_posix()])
    _require(len(goal_paths) == 29 and len(set(goal_paths)) == 29, "Goal path count differs")
    _require(
        len(managed_goal_paths) == 35 and len(set(managed_goal_paths)) == 35,
        "managed Goal path count differs",
    )
    state["goal_document_paths"] = goal_paths
    state["goal_document_count"] = len(goal_paths)
    state["managed_goal_paths"] = managed_goal_paths
    state["managed_goal_path_count"] = len(managed_goal_paths)
    state["path_set_sha256"], state["content_set_sha256"] = contract.package_hashes(
        root,
        managed_goal_paths,
    )

    inventory = copy.deepcopy(state["dynamic_goal_inventory"])
    inventory[GOAL_ID] = _inventory_record("0" * 64)
    children = copy.deepcopy(state["materialized_child_goal_ids_by_parent"])
    _require(GOAL_ID not in children.get(PARENT_GOAL_ID, []), "FP046 child is already present")
    children[PARENT_GOAL_ID] = [*children.get(PARENT_GOAL_ID, []), GOAL_ID]
    state["dynamic_goal_inventory"] = inventory
    state["materialized_child_goal_ids_by_parent"] = children
    state["status_by_goal"][GOAL_ID] = "PLANNED"

    planned_queue, planned_boundary = _derive_queue_and_boundary(
        root,
        checkpoint,
        MATERIALIZED_FRONTIER,
    )
    _require_pin(
        contract.canonical_json_sha256(planned_queue),
        EXPECTED_QUEUE_SHA256,
        "seq51 artifact queue",
    )
    _require_pin(
        contract.canonical_json_sha256(planned_boundary),
        EXPECTED_MATERIALIZED_BOUNDARY_SHA256,
        "seq51 completion boundary",
    )
    materialized: dict[str, Any] = {
        "sequence": 51,
        "event_id": MATERIALIZED_EVENT_ID,
        "event_type": "GOAL_MATERIALIZED",
        "occurred_on": "2026-08-09",
        "occurred_at": MATERIALIZED_AT,
        "previous_focus_goal_id": PARENT_GOAL_ID,
        "previous_focus_content_sha256": PARENT_GOAL_SHA256,
        "focus_goal_id": PARENT_GOAL_ID,
        "focus_goal_content_sha256": PARENT_GOAL_SHA256,
        "from_status": None,
        "to_status": "PLANNED",
        "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
        "status_changes": {GOAL_ID: "PLANNED"},
        "runtime_after": _runtime_projection(
            state,
            focus_goal_id=PARENT_GOAL_ID,
            focus_goal_path=PARENT_GOAL_PATH,
            focus_work_item_id="",
            focus_source="WORKSTREAM_GRAPH",
            frontier=MATERIALIZED_FRONTIER,
            queue=planned_queue,
            boundary=planned_boundary,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"],
        "materialized_goal_id": GOAL_ID,
        "materialized_goal_path": GOAL_PATH.as_posix(),
        "materialized_goal_content_sha256": GOAL_SHA256,
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": BACKLOG_PATH,
        "materialized_from_document_id": BACKLOG_ID,
        "materialized_from_sha256": BACKLOG_SHA256,
        "predecessor_goal_id": PREDECESSOR_GOAL_ID,
        "predecessor_goal_content_sha256": PREDECESSOR_GOAL_SHA256,
        "supersedes_goal_id": "",
        "supersedes_goal_content_sha256": "",
        "artifact_work_reason": "",
        "artifact_trigger_evidence_refs": [],
        "artifact_trigger_evidence_bindings": {},
        "source_working_snapshot_reconciliation": source_delta,
        "previous_event_sha256": SOURCE_TAIL_SHA256,
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    materialized["event_sha256"] = contract.event_sha256(materialized)
    _require_pin(
        materialized["event_sha256"],
        EXPECTED_MATERIALIZED_EVENT_SHA256,
        "seq51 event",
    )

    inventory[GOAL_ID] = _inventory_record(materialized["event_sha256"])
    state["dynamic_goal_inventory"] = inventory
    state["status_by_goal"][GOAL_ID] = "READY"
    ready_queue, ready_boundary = _derive_queue_and_boundary(root, checkpoint, READY_FRONTIER)
    _require_pin(
        contract.canonical_json_sha256(ready_queue),
        EXPECTED_QUEUE_SHA256,
        "seq52 artifact queue",
    )
    _require_pin(
        contract.canonical_json_sha256(ready_boundary),
        EXPECTED_READY_BOUNDARY_SHA256,
        "seq52 completion boundary",
    )
    ready: dict[str, Any] = {
        "sequence": 52,
        "event_id": READY_EVENT_ID,
        "event_type": "GOAL_READY",
        "occurred_on": "2026-08-09",
        "occurred_at": READY_AT,
        "previous_focus_goal_id": PARENT_GOAL_ID,
        "previous_focus_content_sha256": PARENT_GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "from_status": "PLANNED",
        "to_status": "READY",
        "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
        "status_changes": {GOAL_ID: "READY"},
        "runtime_after": _runtime_projection(
            state,
            focus_goal_id=GOAL_ID,
            focus_goal_path=GOAL_PATH.as_posix(),
            focus_work_item_id=WORK_ITEM_ID,
            focus_source="IMPLEMENTATION_BACKLOG",
            frontier=READY_FRONTIER,
            queue=ready_queue,
            boundary=ready_boundary,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": ["IMPLEMENTATION_GAP"],
        "subject_goal_id": GOAL_ID,
        "readiness_basis": {
            "dependency_completion_events": [
                {
                    "goal_id": PREDECESSOR_GOAL_ID,
                    "event_sha256": PREDECESSOR_COMPLETION_EVENT_SHA256,
                }
            ],
            "predecessor_goal_id": PREDECESSOR_GOAL_ID,
            "predecessor_completion_event_sha256": PREDECESSOR_COMPLETION_EVENT_SHA256,
        },
        "start_evidence_bindings": {},
        "start_evidence_provenance": {},
        "implementation_start_gate_contract_binding": start_gate_contract_binding(),
        "dynamic_goal_inventory_after": copy.deepcopy(inventory),
        "materialized_child_goal_ids_by_parent_after": copy.deepcopy(children),
        "previous_event_sha256": materialized["event_sha256"],
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    ready["event_sha256"] = contract.event_sha256(ready)
    _require_pin(ready["event_sha256"], EXPECTED_READY_EVENT_SHA256, "seq52 event")

    state["transition_history"].extend([materialized, ready])
    state["transition_history_anchor_sha256"] = ready["event_sha256"]
    state["validation_cutoff_at"] = READY_AT
    state["focus_goal_id"] = GOAL_ID
    state["focus_goal_path"] = GOAL_PATH.as_posix()
    state["focus_work_item_id"] = WORK_ITEM_ID
    state["focus_source"] = "IMPLEMENTATION_BACKLOG"
    state["ready_frontier_goal_ids"] = copy.deepcopy(READY_FRONTIER)
    state["artifact_work_queue"] = ready_queue
    state["completion_boundary"] = ready_boundary

    current = checkpoint["current_work"]
    current["work_item_id"] = WORK_ITEM_ID
    current["status"] = "READY"
    current["current_focus"] = "FP046/GAP-055 Goal READY; active internal start gate not run"
    current["release_completion_claimed"] = False

    snapshot = checkpoint["working_tree_snapshot"]
    managed_paths = sorted(set(snapshot["managed_changed_paths"]) | set(SOURCE_PATHS))
    path_hash, content_hash = contract.working_snapshot_hashes(root, managed_paths)
    snapshot["scope"] = (
        "Graph v2.4 FP046/GAP-055 materialization seq51 and readiness seq52; "
        "no GOAL_STARTED, product completion, admin, Web, device, external, formal, "
        "deployment, or release credit."
    )
    snapshot["managed_changed_paths"] = managed_paths
    snapshot["managed_changed_path_count"] = len(managed_paths)
    snapshot["path_set_sha256"] = path_hash
    snapshot["content_set_sha256"] = content_hash

    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    handoff["source_commit_or_snapshot"]["file_count"] = len(managed_paths)
    handoff["source_commit_or_snapshot"]["path_set_sha256"] = path_hash
    handoff["source_commit_or_snapshot"]["content_set_sha256"] = content_hash
    handoff["current_epic"] = "EPIC-03 / FP046/GAP-055 READY_NOT_STARTED"
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    return checkpoint, materialized, ready


def validate_projection(root: Path, checkpoint: dict[str, Any]) -> list[str]:
    errors, archive = contract.validate_frozen_v23_boundary(
        root,
        contract.V23_ARCHIVE_RELATIVE,
    )
    errors.extend(contract.validate_seq39_canonical_binding_authorization_request(root, checkpoint))
    errors.extend(contract.validate_seq39_canonical_binding_update(root, checkpoint))
    if archive:
        errors.extend(contract.validate_prepared_checkpoint_projection(checkpoint, archive))
        errors.extend(
            contract.validate_transition_replay(
                root,
                checkpoint,
                archive,
                expected_prepared_sha256=contract.EXPECTED_V24_PREPARED_EVENT_SHA256,
                expected_authorization_sha256=(
                    contract.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256
                ),
            )
        )
    errors.extend(contract.validate_working_snapshot(root, checkpoint))
    errors.extend(goal_graph.validate_v24_artifact_work_queue(root, checkpoint))
    errors.extend(contract.validate_generic_event_order(checkpoint["goal_execution"]["transition_history"]))
    return errors


def require_ready_checkpoint(root: Path, checkpoint: dict[str, Any]) -> None:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(isinstance(history, list) and len(history) == 52, "ready checkpoint history differs")
    materialized, ready = history[-2:]
    _require(
        materialized.get("event_id") == MATERIALIZED_EVENT_ID
        and materialized.get("event_sha256") == contract.event_sha256(materialized)
        and ready.get("event_id") == READY_EVENT_ID
        and ready.get("event_sha256") == contract.event_sha256(ready)
        and ready.get("previous_event_sha256") == materialized.get("event_sha256"),
        "seq51/52 event chain differs",
    )
    _require(
        state.get("transition_history_anchor_sha256") == ready.get("event_sha256")
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and state.get("status_by_goal", {}).get(PREDECESSOR_GOAL_ID)
        == "COMPLETE_AT_TARGET"
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH.as_posix()
        and state.get("focus_work_item_id") == WORK_ITEM_ID
        and state.get("ready_frontier_goal_ids") == READY_FRONTIER,
        "FP046 READY runtime differs",
    )
    _require(
        ready.get("implementation_start_gate_contract_binding")
        == start_gate_contract_binding(),
        "FP046 READY contract binding differs",
    )
    _require(
        checkpoint.get("current_work", {}).get("status") == "READY"
        and checkpoint.get("session_handoff", {}).get("last_verification_status")
        == "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN",
        "FP046 READY operational pointer differs",
    )
    errors = validate_projection(root, checkpoint)
    _require(not errors, "ready checkpoint validation differs: " + "; ".join(errors))


def preflight(root: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    _, source = load_exact_source(root)
    projected, materialized, ready = project(root, source)
    require_ready_checkpoint(root, projected)
    return projected, materialized, ready


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preflight", action="store_true", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        projected, materialized, ready = preflight(args.root.resolve())
    except (KeyError, OSError, TypeError, ValueError, ProjectionError) as exc:
        print(f"WalkSafe FP046 seq51/52 materialization: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "WalkSafe FP046 seq51/52 materialization: PASS mode=PREFLIGHT "
        f"seq51={materialized['event_sha256']} seq52={ready['event_sha256']} "
        f"managed={projected['working_tree_snapshot']['managed_changed_path_count']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
