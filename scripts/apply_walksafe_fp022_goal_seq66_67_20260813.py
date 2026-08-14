#!/usr/bin/env python3
"""Materialize FP-022 at seq66 and project it READY at seq67 atomically."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_workstream_aggregate_seq63_65_20260813 as aggregate
from scripts import check_walksafe_goal_graph_v2_4 as graph
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import generate_repository_catalogs as catalogs


npc = aggregate.npc
require = npc.require
bytes_sha256 = npc.bytes_sha256
CHECKPOINT_REL = aggregate.CHECKPOINT_REL
CATALOG_PATHS = aggregate.CATALOG_PATHS

SOURCE_SHA256 = "25ff4d3f630ccbaffe1aa9e4eb5a1c922050d1705171bbaa162e6dad0d692644"
SOURCE_BYTE_COUNT = 1_922_305
SOURCE_SEQUENCE = 65
SOURCE_TAIL_SHA256 = "c8eb9b6b90f546af6960fa929c93b1c97b6115d690938c09a33dd9a4155f0a49"
SOURCE_CUTOFF_AT = "2026-08-13T19:48:40+09:00"
MANIFEST_SHA256 = aggregate.MANIFEST_SHA256

GOAL_ID = "WS-GOAL-EPIC-04-FP-022-R001"
GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-04/epic-04-fp022-tmap-destination-route-r001.md"
)
GOAL_SHA256 = "939075c1b4bcbf9b8280c37cb7a449fd28763f06cda14faf0ca88f691734576b"
GOAL_BYTE_COUNT = 5_970
WORK_ITEM_ID = GOAL_ID
PARENT_GOAL_ID = aggregate.EPIC04
PARENT_GOAL_PATH = aggregate.EPIC04_PATH
PARENT_GOAL_SHA256 = aggregate.EPIC04_SHA256
EPIC12 = aggregate.EPIC12

MATERIALIZATION_PREDECESSOR_ID = (
    "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
)
MATERIALIZATION_PREDECESSOR_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-03/epic-03-npc-single-admin-recovery-r001.md"
)
MATERIALIZATION_PREDECESSOR_SHA256 = (
    "234a224883779208ba7878a9865076083bb9cfd205dfdb7a760b043f7af6b16d"
)
MATERIALIZATION_PREDECESSOR_BYTE_COUNT = 4_552
MATERIALIZATION_PREDECESSOR_COMPLETION_SHA256 = (
    "24f586dda8e19d9c1315bee80e546906359869c49e5fa776bb891ec76beb13c1"
)
READY_DEPENDENCY_ID = aggregate.EPIC02
READY_DEPENDENCY_COMPLETION_SHA256 = (
    "e3cae0925f1e3bcf36335b68ead21786bd3c690c2cb4414e675412a0b76e7dd7"
)

BACKLOG_PATH = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260813-r027.json"
)
BACKLOG_ID = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260813-027"
BACKLOG_SHA256 = "64e91046639ba44600d3584b5258d15f26b9c9161b03f25a4a662903d29e2f45"
BACKLOG_BYTE_COUNT = 65_457
GAP_PATH = Path(
    "docs/control/audits/"
    "walksafe-implementation-gap-analysis-20260813-r027.json"
)
GAP_ID = "WS-IMPLEMENTATION-GAP-ANALYSIS-20260813-027"
GAP_SHA256 = "ec04719873e80d7769e49c6b959fa3d712907272c3c968640147e26081b4c04f"
GAP_BYTE_COUNT = 527_521

CONTRACT_PATH = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-04-FP-022-R001/"
    "initial-start-gate-contract-r001.json"
)
CONTRACT_FILE_SHA256 = (
    "14ad8eee8a6c972c1e9091c2065b0779acdde427d0176b84fb83b18840865c25"
)
CONTRACT_BYTE_COUNT = 2_912
CONTRACT_CANONICAL_SHA256 = (
    "a5d39ea4a1c7f919e4d3d08f3a429f7de0bdadec9d03756df7f32f4bbef074ab"
)
CONTRACT_DOCUMENT_ID = "WS-FP022-INITIAL-START-GATE-CONTRACT-20260813-001"
CONTRACT_ID = "WS-FP022-INTERNAL-START-GATE-R001"
CONTRACT_VERSION = "2026-08-13.1"
CONTRACT_CHECK_IDS = (
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_NAVIGATION_INTERNAL",
    "ANDROID_USER_INTERNAL",
    "ROOT_FP022_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)

MATERIALIZED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-FP022-20260813-001"
)
READY_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP022-20260813-001"
MATERIALIZED_FRONTIER = [PARENT_GOAL_ID, EPIC12]
READY_FRONTIER = [GOAL_ID, PARENT_GOAL_ID, EPIC12]
MATERIALIZED_EVIDENCE_REFS = ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
READY_EVIDENCE_REFS = ["IMPLEMENTATION_GAP"]
EXPECTED_QUEUE_SHA256 = (
    "ba5dc2d8cdd0d3956840a86c0f0a3b767b4fd4ca2dc54393ce05957f3ac022dc"
)
EXPECTED_MATERIALIZED_BOUNDARY_SHA256 = (
    "80b93ba193b5cf85df7b3b76c427c51481c061503ee025cbeb0bc88733066656"
)
EXPECTED_READY_BOUNDARY_SHA256 = (
    "d2af49d56117b96af9785e1f95dc0ac8914f11defefb469eda3e3b794c4ef7a1"
)
EXPECTED_PACKAGE_PATH_SET_SHA256 = (
    "90b784d13c3aa37fecf0099d6f0030397e8021886158070f5015b0f51da6f022"
)
EXPECTED_PACKAGE_CONTENT_SET_SHA256 = (
    "d996feb8d7f00abbbaa0f0dab9c148994f38dcf663911e84098786dcf373c5fa"
)
COMMON_EVENT_FIELDS = {
    "sequence",
    "event_id",
    "event_type",
    "occurred_on",
    "occurred_at",
    "previous_focus_goal_id",
    "previous_focus_content_sha256",
    "focus_goal_id",
    "focus_goal_content_sha256",
    "from_status",
    "to_status",
    "static_plan_manifest_sha256",
    "status_changes",
    "runtime_after",
    "blockers_after",
    "blocker_resolution_ids_after",
    "source_checkpoint_version",
    "evidence_refs",
    "previous_event_sha256",
    "canonical_binding_snapshot_after",
    "event_sha256",
}
MATERIALIZED_EVENT_FIELDS = COMMON_EVENT_FIELDS | {
    "source_checkpoint_binding",
    "transition_control_review_binding",
    "materialized_goal_id",
    "materialized_goal_path",
    "materialized_goal_content_sha256",
    "materialized_from_role",
    "materialized_from_path",
    "materialized_from_document_id",
    "materialized_from_sha256",
    "predecessor_goal_id",
    "predecessor_goal_content_sha256",
    "supersedes_goal_id",
    "supersedes_goal_content_sha256",
    "artifact_work_reason",
    "artifact_trigger_evidence_refs",
    "artifact_trigger_evidence_bindings",
}
READY_EVENT_FIELDS = COMMON_EVENT_FIELDS | {
    "subject_goal_id",
    "readiness_basis",
    "start_evidence_bindings",
    "start_evidence_provenance",
    "implementation_start_gate_contract_binding",
    "dynamic_goal_inventory_after",
    "materialized_child_goal_ids_by_parent_after",
}

SCRIPT_REL = Path("scripts/apply_walksafe_fp022_goal_seq66_67_20260813.py")
TEST_REL = Path("tests/test_walksafe_fp022_goal_seq66_67_20260813.py")
FINAL_SCOPE = (
    "Graph v2.4 FP-022/GAP-031 materialization seq66 and readiness seq67; "
    "no GOAL_STARTED, implementation completion, device, external, formal, "
    "deployment, or release credit."
)


@dataclass(frozen=True)
class Prepared:
    root: Path
    checkpoint_path: Path
    source_bytes: bytes
    projected: dict[str, Any]
    projected_bytes: bytes
    final_sha256_by_path: Mapping[Path, str]
    physical_sha256_by_path: Mapping[Path, str]
    source_universe: tuple[str, ...]
    candidate_catalogs: Mapping[Path, bytes]
    catalogs_verified: bool
    projected_validator: Callable[[Path, bytes], None]


def _safe_bytes(root: Path, relative: Path) -> bytes:
    return npc._read_safe_bytes(root, relative)


def _exact_file(root: Path, relative: Path, digest: str, count: int) -> bytes:
    raw = _safe_bytes(root, relative)
    require(len(raw) == count, f"{relative} byte count differs")
    require(bytes_sha256(raw) == digest, f"{relative} SHA-256 differs")
    return raw


def _binding_map(checkpoint: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    return continuation.canonical_binding_snapshot(dict(checkpoint))


def require_source(raw: bytes, source: Mapping[str, Any]) -> None:
    require(len(raw) == SOURCE_BYTE_COUNT, "checkpoint byte count differs from seq65")
    require(bytes_sha256(raw) == SOURCE_SHA256, "checkpoint SHA-256 differs from seq65")
    require(source.get("schema_version") == "1.25.0", "checkpoint schema differs")
    state = source.get("goal_execution")
    require(type(state) is dict, "source Goal execution is missing")
    history = state.get("transition_history")
    statuses = state.get("status_by_goal")
    require(
        type(history) is list and len(history) == SOURCE_SEQUENCE,
        "source history is not exact seq65",
    )
    tail = history[-1]
    require(
        type(tail) is dict
        and tail.get("sequence") == SOURCE_SEQUENCE
        and tail.get("event_type") == "GOAL_COMPLETED"
        and tail.get("subject_goal_id") == aggregate.EPIC03
        and tail.get("event_sha256") == SOURCE_TAIL_SHA256
        and continuation.event_sha256(tail) == SOURCE_TAIL_SHA256
        and state.get("transition_history_anchor_sha256") == SOURCE_TAIL_SHA256
        and state.get("validation_cutoff_at") == SOURCE_CUTOFF_AT,
        "source seq65 tail differs",
    )
    require(
        type(statuses) is dict
        and statuses.get(aggregate.EPIC02) == "COMPLETE_AT_TARGET"
        and statuses.get(aggregate.EPIC03) == "COMPLETE_AT_TARGET"
        and statuses.get(PARENT_GOAL_ID) == "READY"
        and statuses.get(MATERIALIZATION_PREDECESSOR_ID) == "COMPLETE_AT_TARGET"
        and GOAL_ID not in statuses
        and "IN_PROGRESS" not in statuses.values(),
        "source status boundary differs",
    )
    require(
        state.get("focus_goal_id") == PARENT_GOAL_ID
        and state.get("focus_goal_path") == PARENT_GOAL_PATH.as_posix()
        and state.get("focus_work_item_id") == ""
        and state.get("focus_source") == "WORKSTREAM_GRAPH"
        and state.get("ready_frontier_goal_ids") == MATERIALIZED_FRONTIER,
        "source focus or frontier differs",
    )
    require(
        state.get("dynamic_goal_inventory", {}).get(GOAL_ID) is None
        and GOAL_ID
        not in state.get("materialized_child_goal_ids_by_parent", {}).get(
            PARENT_GOAL_ID, []
        ),
        "FP-022 Goal is already materialized",
    )
    current = source.get("current_work")
    handoff = source.get("session_handoff")
    require(
        type(current) is dict
        and current.get("work_item_id") == WORK_ITEM_ID
        and current.get("status") == "PLANNED"
        and current.get("current_focus")
        == "FP-022/GAP-031 PLANNED_NEXT; EPIC-04 canonical Backlog aggregate"
        and type(handoff) is dict
        and handoff.get("current_epic")
        == "EPIC-04 / FP-022/GAP-031 PLANNED_NEXT",
        "source operational pointer differs",
    )


def contract_binding() -> dict[str, str]:
    return {
        "schema_version": "1.0",
        "document_id": CONTRACT_DOCUMENT_ID,
        "path": CONTRACT_PATH.as_posix(),
        "file_sha256": CONTRACT_FILE_SHA256,
        "contract_id": CONTRACT_ID,
        "contract_version": CONTRACT_VERSION,
        "canonical_contract_sha256": CONTRACT_CANONICAL_SHA256,
    }


def transition_review_binding(root: Path) -> dict[str, dict[str, Any]]:
    from scripts import build_walksafe_fp022_seq66_67_review_20260814 as review

    return review.transition_review_binding(root)


def require_static_inputs(root: Path, source: Mapping[str, Any]) -> None:
    _exact_file(root, GOAL_PATH, GOAL_SHA256, GOAL_BYTE_COUNT)
    _exact_file(root, BACKLOG_PATH, BACKLOG_SHA256, BACKLOG_BYTE_COUNT)
    _exact_file(root, GAP_PATH, GAP_SHA256, GAP_BYTE_COUNT)
    _exact_file(
        root,
        MATERIALIZATION_PREDECESSOR_PATH,
        MATERIALIZATION_PREDECESSOR_SHA256,
        MATERIALIZATION_PREDECESSOR_BYTE_COUNT,
    )
    contract_raw = _exact_file(
        root, CONTRACT_PATH, CONTRACT_FILE_SHA256, CONTRACT_BYTE_COUNT
    )
    contract = npc.trace.strict_json_bytes(contract_raw, CONTRACT_PATH.as_posix())
    require(
        continuation.canonical_json_sha256(contract) == CONTRACT_CANONICAL_SHA256,
        "FP-022 start contract canonical SHA-256 differs",
    )
    require(
        set(contract)
        == {
            "schema_version",
            "document_id",
            "contract_id",
            "contract_version",
            "target_goal_id",
            "target_goal_content_sha256",
            "gate_purpose",
            "ordered_checks",
        }
        and contract.get("schema_version") == "1.0"
        and contract.get("document_id") == CONTRACT_DOCUMENT_ID
        and contract.get("contract_id") == CONTRACT_ID
        and contract.get("contract_version") == CONTRACT_VERSION
        and contract.get("target_goal_id") == GOAL_ID
        and contract.get("target_goal_content_sha256") == GOAL_SHA256
        and contract.get("gate_purpose") == "INITIAL_START",
        "FP-022 start contract identity differs",
    )
    checks = contract.get("ordered_checks")
    require(
        type(checks) is list
        and tuple(row.get("check_id") for row in checks if type(row) is dict)
        == CONTRACT_CHECK_IDS
        and all(
            type(row) is dict
            and set(row) == {"check_id", "command"}
            and type(row.get("check_id")) is str
            and type(row.get("command")) is str
            and bool(row["command"])
            for row in checks
        ),
        "FP-022 start contract ordered checks differ",
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
    require(
        not any(
            token in row["command"].lower()
            for row in checks
            for token in forbidden
        ),
        "FP-022 start contract contains forbidden command scope",
    )
    node, _ = graph.frozen_goal.parse_goal(root / GOAL_PATH)
    expected = {
        "goal_id": GOAL_ID,
        "goal_kind": "WORK_ITEM",
        "parent_goal_id": PARENT_GOAL_ID,
        "work_item_type": "POLICY_GAP_WORK",
        "priority_rank": 24,
        "initial_status": "PLANNED",
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "work_item_id": WORK_ITEM_ID,
        "start_requires": [READY_DEPENDENCY_ID],
        "completion_requires": [READY_DEPENDENCY_ID],
        "source_policy_ids": ["FP-022"],
        "gap_ids": ["GAP-031"],
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": BACKLOG_PATH.as_posix(),
        "materialized_from_document_id": BACKLOG_ID,
        "materialized_from_sha256": BACKLOG_SHA256,
        "predecessor_goal_id": MATERIALIZATION_PREDECESSOR_ID,
        "predecessor_goal_content_sha256": MATERIALIZATION_PREDECESSOR_SHA256,
    }
    require(
        all(node.get(key) == value for key, value in expected.items()),
        "FP-022 Goal metadata differs",
    )
    bindings = _binding_map(source)
    require(
        bindings.get("IMPLEMENTATION_BACKLOG")
        == {
            "role": "IMPLEMENTATION_BACKLOG",
            "document_id": BACKLOG_ID,
            "path": BACKLOG_PATH.as_posix(),
            "file_sha256": BACKLOG_SHA256,
        }
        and bindings.get("IMPLEMENTATION_GAP")
        == {
            "role": "IMPLEMENTATION_GAP",
            "document_id": GAP_ID,
            "path": GAP_PATH.as_posix(),
            "file_sha256": GAP_SHA256,
        },
        "R027 canonical Gap/Backlog binding differs",
    )


def _inventory_record(materialized_event_sha256: str) -> dict[str, Any]:
    return {
        "goal_id": GOAL_ID,
        "path": GOAL_PATH.as_posix(),
        "sha256": GOAL_SHA256,
        "goal_kind": "WORK_ITEM",
        "work_item_type": "POLICY_GAP_WORK",
        "parent_goal_id": PARENT_GOAL_ID,
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": BACKLOG_PATH.as_posix(),
        "materialized_from_document_id": BACKLOG_ID,
        "materialized_from_sha256": BACKLOG_SHA256,
        "predecessor_goal_id": MATERIALIZATION_PREDECESSOR_ID,
        "predecessor_goal_content_sha256": MATERIALIZATION_PREDECESSOR_SHA256,
        "supersedes_goal_id": "",
        "supersedes_goal_content_sha256": "",
        "artifact_work_reason": "",
        "artifact_trigger_evidence_refs": [],
        "initial_status": "PLANNED",
        "materialized_event_sha256": materialized_event_sha256,
    }


def _derive_queue_boundary(
    root: Path, checkpoint: dict[str, Any], frontier: list[str]
) -> tuple[dict[str, Any], dict[str, Any]]:
    state = checkpoint["goal_execution"]
    bindings = graph.frozen_goal.canonical_binding_map(checkpoint)
    register_binding = bindings.get("ARTIFACT_REGISTER")
    require(type(register_binding) is dict, "ARTIFACT_REGISTER binding is missing")
    register = continuation.load_json(root / register_binding["path"])
    node_errors, nodes = graph.frozen_goal.current_goal_nodes(root, state)
    require(not node_errors, "Goal node derivation failed: " + "; ".join(node_errors))
    queue_errors, queue = graph.derive_v24_artifact_work_queue_from_register(
        register_binding, register, nodes, state["status_by_goal"]
    )
    require(not queue_errors, "artifact queue derivation failed: " + "; ".join(queue_errors))
    boundary_errors, boundary = graph.frozen_goal.derive_completion_boundary(
        nodes,
        state["status_by_goal"],
        frontier,
        state["blockers_by_goal"],
        queue,
        package_status=state["package_status"],
    )
    require(
        not boundary_errors,
        "completion boundary derivation failed: " + "; ".join(boundary_errors),
    )
    return queue, boundary


def _set_runtime(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    focus_goal_id: str,
    focus_goal_path: str,
    focus_work_item_id: str,
    focus_source: str,
    frontier: list[str],
) -> dict[str, Any]:
    state = checkpoint["goal_execution"]
    queue, boundary = _derive_queue_boundary(root, checkpoint, frontier)
    state["focus_goal_id"] = focus_goal_id
    state["focus_goal_path"] = focus_goal_path
    state["focus_work_item_id"] = focus_work_item_id
    state["focus_source"] = focus_source
    state["ready_frontier_goal_ids"] = copy.deepcopy(frontier)
    state["artifact_work_queue"] = queue
    state["completion_boundary"] = boundary
    return {
        "focus_goal_id": focus_goal_id,
        "focus_goal_path": focus_goal_path,
        "focus_work_item_id": focus_work_item_id,
        "focus_source": focus_source,
        "ready_frontier_goal_ids": copy.deepcopy(frontier),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": continuation.canonical_json_sha256(queue),
        "completion_boundary_sha256": continuation.canonical_json_sha256(boundary),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def _event_base(
    checkpoint: dict[str, Any],
    *,
    sequence: int,
    event_id: str,
    event_type: str,
    occurred_at: str,
    previous_event_sha256: str,
    focus_goal_id: str,
    focus_goal_sha256: str,
    from_status: str | None,
    to_status: str,
    status_changes: dict[str, str],
    runtime: dict[str, Any],
    evidence_refs: list[str],
) -> dict[str, Any]:
    state = checkpoint["goal_execution"]
    return {
        "sequence": sequence,
        "event_id": event_id,
        "event_type": event_type,
        "occurred_on": datetime.fromisoformat(occurred_at).date().isoformat(),
        "occurred_at": occurred_at,
        "previous_focus_goal_id": PARENT_GOAL_ID,
        "previous_focus_content_sha256": PARENT_GOAL_SHA256,
        "focus_goal_id": focus_goal_id,
        "focus_goal_content_sha256": focus_goal_sha256,
        "from_status": from_status,
        "to_status": to_status,
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": status_changes,
        "runtime_after": runtime,
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": checkpoint["schema_version"],
        "evidence_refs": copy.deepcopy(evidence_refs),
        "previous_event_sha256": previous_event_sha256,
        "canonical_binding_snapshot_after": _binding_map(checkpoint),
    }


def _apply_snapshot(
    checkpoint: dict[str, Any], sha256_by_path: Mapping[Path, str]
) -> None:
    aggregate._apply_snapshot(checkpoint, sha256_by_path)
    checkpoint["working_tree_snapshot"]["scope"] = FINAL_SCOPE


def project(
    root: Path,
    source: Mapping[str, Any],
    final_sha256_by_path: Mapping[Path, str],
    *,
    transition_review: Mapping[str, Mapping[str, Any]] | None = None,
) -> tuple[dict[str, Any], tuple[dict[str, Any], dict[str, Any]]]:
    source_bytes = npc.trace.json_text(dict(source)).encode()
    require_source(source_bytes, source)
    require_static_inputs(root, source)
    checkpoint = copy.deepcopy(dict(source))
    state = checkpoint["goal_execution"]

    state["goal_document_paths"] = sorted(
        [*state["goal_document_paths"], GOAL_PATH.as_posix()]
    )
    state["goal_document_count"] = len(state["goal_document_paths"])
    state["managed_goal_paths"] = sorted(
        [*state["managed_goal_paths"], GOAL_PATH.as_posix()]
    )
    state["managed_goal_path_count"] = len(state["managed_goal_paths"])
    state["path_set_sha256"], state["content_set_sha256"] = (
        continuation.package_hashes(root, state["managed_goal_paths"])
    )
    require(
        state["goal_document_count"] == 31
        and state["managed_goal_path_count"] == 37
        and state["path_set_sha256"] == EXPECTED_PACKAGE_PATH_SET_SHA256
        and state["content_set_sha256"] == EXPECTED_PACKAGE_CONTENT_SET_SHA256,
        "FP-022 Goal package projection differs",
    )

    inventory = copy.deepcopy(state["dynamic_goal_inventory"])
    inventory[GOAL_ID] = _inventory_record("0" * 64)
    children = copy.deepcopy(state["materialized_child_goal_ids_by_parent"])
    require(not children.get(PARENT_GOAL_ID), "EPIC-04 already has a materialized child")
    children[PARENT_GOAL_ID] = [GOAL_ID]
    state["dynamic_goal_inventory"] = inventory
    state["materialized_child_goal_ids_by_parent"] = children
    state["status_by_goal"][GOAL_ID] = "PLANNED"

    base_time = datetime.fromisoformat(SOURCE_CUTOFF_AT)
    materialized_at = (base_time + timedelta(seconds=1)).isoformat()
    ready_at = (base_time + timedelta(seconds=2)).isoformat()
    materialized_runtime = _set_runtime(
        root,
        checkpoint,
        focus_goal_id=PARENT_GOAL_ID,
        focus_goal_path=PARENT_GOAL_PATH.as_posix(),
        focus_work_item_id="",
        focus_source="WORKSTREAM_GRAPH",
        frontier=MATERIALIZED_FRONTIER,
    )
    materialized = _event_base(
        checkpoint,
        sequence=66,
        event_id=MATERIALIZED_EVENT_ID,
        event_type="GOAL_MATERIALIZED",
        occurred_at=materialized_at,
        previous_event_sha256=SOURCE_TAIL_SHA256,
        focus_goal_id=PARENT_GOAL_ID,
        focus_goal_sha256=PARENT_GOAL_SHA256,
        from_status=None,
        to_status="PLANNED",
        status_changes={GOAL_ID: "PLANNED"},
        runtime=materialized_runtime,
        evidence_refs=MATERIALIZED_EVIDENCE_REFS,
    )
    materialized.update(
        {
            "source_checkpoint_binding": {
                "path": CHECKPOINT_REL.as_posix(),
                "sequence": SOURCE_SEQUENCE,
                "sha256": SOURCE_SHA256,
                "byte_length": SOURCE_BYTE_COUNT,
                "tail_event_sha256": SOURCE_TAIL_SHA256,
            },
            "transition_control_review_binding": copy.deepcopy(
                dict(transition_review)
                if transition_review is not None
                else transition_review_binding(root)
            ),
            "materialized_goal_id": GOAL_ID,
            "materialized_goal_path": GOAL_PATH.as_posix(),
            "materialized_goal_content_sha256": GOAL_SHA256,
            "materialized_from_role": "IMPLEMENTATION_BACKLOG",
            "materialized_from_path": BACKLOG_PATH.as_posix(),
            "materialized_from_document_id": BACKLOG_ID,
            "materialized_from_sha256": BACKLOG_SHA256,
            "predecessor_goal_id": MATERIALIZATION_PREDECESSOR_ID,
            "predecessor_goal_content_sha256": MATERIALIZATION_PREDECESSOR_SHA256,
            "supersedes_goal_id": "",
            "supersedes_goal_content_sha256": "",
            "artifact_work_reason": "",
            "artifact_trigger_evidence_refs": [],
            "artifact_trigger_evidence_bindings": {},
        }
    )
    materialized["event_sha256"] = continuation.event_sha256(materialized)

    inventory[GOAL_ID] = _inventory_record(materialized["event_sha256"])
    state["dynamic_goal_inventory"] = inventory
    state["status_by_goal"][GOAL_ID] = "READY"
    ready_runtime = _set_runtime(
        root,
        checkpoint,
        focus_goal_id=GOAL_ID,
        focus_goal_path=GOAL_PATH.as_posix(),
        focus_work_item_id=WORK_ITEM_ID,
        focus_source="IMPLEMENTATION_BACKLOG",
        frontier=READY_FRONTIER,
    )
    ready = _event_base(
        checkpoint,
        sequence=67,
        event_id=READY_EVENT_ID,
        event_type="GOAL_READY",
        occurred_at=ready_at,
        previous_event_sha256=materialized["event_sha256"],
        focus_goal_id=GOAL_ID,
        focus_goal_sha256=GOAL_SHA256,
        from_status="PLANNED",
        to_status="READY",
        status_changes={GOAL_ID: "READY"},
        runtime=ready_runtime,
        evidence_refs=READY_EVIDENCE_REFS,
    )
    ready.update(
        {
            "subject_goal_id": GOAL_ID,
            "readiness_basis": {
                "dependency_completion_events": [
                    {
                        "goal_id": READY_DEPENDENCY_ID,
                        "event_sha256": READY_DEPENDENCY_COMPLETION_SHA256,
                    }
                ],
                "predecessor_goal_id": MATERIALIZATION_PREDECESSOR_ID,
                "predecessor_completion_event_sha256": (
                    MATERIALIZATION_PREDECESSOR_COMPLETION_SHA256
                ),
            },
            "start_evidence_bindings": {},
            "start_evidence_provenance": {},
            "implementation_start_gate_contract_binding": contract_binding(),
            "dynamic_goal_inventory_after": copy.deepcopy(inventory),
            "materialized_child_goal_ids_by_parent_after": copy.deepcopy(children),
        }
    )
    ready["event_sha256"] = continuation.event_sha256(ready)
    for actual, expected, label in (
        (
            materialized_runtime["artifact_work_queue_sha256"],
            EXPECTED_QUEUE_SHA256,
            "seq66 queue",
        ),
        (
            ready_runtime["artifact_work_queue_sha256"],
            EXPECTED_QUEUE_SHA256,
            "seq67 queue",
        ),
        (
            materialized_runtime["completion_boundary_sha256"],
            EXPECTED_MATERIALIZED_BOUNDARY_SHA256,
            "seq66 boundary",
        ),
        (
            ready_runtime["completion_boundary_sha256"],
            EXPECTED_READY_BOUNDARY_SHA256,
            "seq67 boundary",
        ),
    ):
        require(actual == expected, f"{label} SHA-256 differs")
    state["transition_history"].extend([materialized, ready])
    state["transition_history_anchor_sha256"] = ready["event_sha256"]
    state["validation_cutoff_at"] = ready_at

    current = checkpoint["current_work"]
    current["status"] = "READY"
    current["current_focus"] = (
        "FP-022/GAP-031 Goal READY; active internal start gate not run"
    )
    current["release_completion_claimed"] = False
    handoff = checkpoint["session_handoff"]
    handoff["current_epic"] = "EPIC-04 / FP-022/GAP-031 READY_NOT_STARTED"
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    _apply_snapshot(checkpoint, final_sha256_by_path)
    return checkpoint, (materialized, ready)


def validate_projection(
    root: Path,
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    events: Sequence[Mapping[str, Any]],
) -> None:
    before = source["goal_execution"]
    after = projected["goal_execution"]
    allowed_top = {
        "goal_execution",
        "current_work",
        "working_tree_snapshot",
        "session_handoff",
    }
    require(
        set(projected) == set(source)
        and all(projected[key] == source[key] for key in source if key not in allowed_top),
        "FP-022 projection changed unrelated top-level state",
    )
    allowed_state = {
        "goal_document_paths",
        "goal_document_count",
        "managed_goal_paths",
        "managed_goal_path_count",
        "path_set_sha256",
        "content_set_sha256",
        "dynamic_goal_inventory",
        "materialized_child_goal_ids_by_parent",
        "status_by_goal",
        "transition_history",
        "transition_history_anchor_sha256",
        "validation_cutoff_at",
        "focus_goal_id",
        "focus_goal_path",
        "focus_work_item_id",
        "focus_source",
        "ready_frontier_goal_ids",
        "artifact_work_queue",
        "completion_boundary",
    }
    require(
        set(after) == set(before)
        and all(after[key] == before[key] for key in before if key not in allowed_state),
        "FP-022 projection changed unrelated Goal state",
    )
    allowed_current = {"status", "current_focus", "release_completion_claimed"}
    require(
        set(projected["current_work"]) == set(source["current_work"])
        and all(
            projected["current_work"][key] == source["current_work"][key]
            for key in source["current_work"]
            if key not in allowed_current
        ),
        "FP-022 projection changed unrelated current-work state",
    )
    allowed_handoff = {
        "changed_files",
        "source_commit_or_snapshot",
        "current_epic",
        "last_updated_by_work_item",
    }
    require(
        set(projected["session_handoff"]) == set(source["session_handoff"])
        and all(
            projected["session_handoff"][key] == source["session_handoff"][key]
            for key in source["session_handoff"]
            if key not in allowed_handoff
        ),
        "FP-022 projection changed unrelated handoff state",
    )
    source_mirror = source["session_handoff"]["source_commit_or_snapshot"]
    projected_mirror = projected["session_handoff"]["source_commit_or_snapshot"]
    allowed_mirror = {"file_count", "path_set_sha256", "content_set_sha256"}
    require(
        set(projected_mirror) == set(source_mirror)
        and all(
            projected_mirror[key] == source_mirror[key]
            for key in source_mirror
            if key not in allowed_mirror
        ),
        "FP-022 projection changed unrelated handoff source identity",
    )
    allowed_snapshot = {
        "managed_changed_paths",
        "managed_changed_path_count",
        "path_set_sha256",
        "content_set_sha256",
        "scope",
    }
    require(
        set(projected["working_tree_snapshot"])
        == set(source["working_tree_snapshot"])
        and all(
            projected["working_tree_snapshot"][key]
            == source["working_tree_snapshot"][key]
            for key in source["working_tree_snapshot"]
            if key not in allowed_snapshot
        ),
        "FP-022 projection changed unrelated working-snapshot state",
    )
    require(
        after["completion_evidence_by_goal"] == before["completion_evidence_by_goal"]
        and projected["verification_boundary"] == source["verification_boundary"]
        and projected["authority_boundary"] == source["authority_boundary"]
        and projected["approved_state"] == source["approved_state"],
        "FP-022 readiness changed completion or release credit",
    )
    require(
        len(events) == 2
        and after["transition_history"][:-2] == before["transition_history"]
        and after["transition_history"][-2:] == list(events),
        "seq66/67 history is not add-only",
    )
    materialized, ready = events
    source_binding = {
        "path": CHECKPOINT_REL.as_posix(),
        "sequence": SOURCE_SEQUENCE,
        "sha256": SOURCE_SHA256,
        "byte_length": SOURCE_BYTE_COUNT,
        "tail_event_sha256": SOURCE_TAIL_SHA256,
    }
    review_binding = materialized.get("transition_control_review_binding")
    require(
        set(materialized) == MATERIALIZED_EVENT_FIELDS
        and materialized.get("sequence") == 66
        and materialized.get("event_id") == MATERIALIZED_EVENT_ID
        and materialized.get("event_type") == "GOAL_MATERIALIZED"
        and materialized.get("occurred_on") == "2026-08-13"
        and materialized.get("occurred_at") == "2026-08-13T19:48:41+09:00"
        and materialized.get("materialized_goal_id") == GOAL_ID
        and materialized.get("from_status") is None
        and materialized.get("to_status") == "PLANNED"
        and materialized.get("status_changes") == {GOAL_ID: "PLANNED"}
        and materialized.get("previous_event_sha256") == SOURCE_TAIL_SHA256
        and materialized.get("evidence_refs") == MATERIALIZED_EVIDENCE_REFS
        and materialized.get("source_checkpoint_binding") == source_binding
        and type(review_binding) is dict
        and set(review_binding)
        == {"assignment", "review_result", "independent_review"}
        and all(
            type(binding) is dict
            and set(binding) == {"path", "sha256", "byte_length"}
            and continuation.SHA256_RE.fullmatch(
                str(binding.get("sha256", ""))
            )
            is not None
            and type(binding.get("byte_length")) is int
            and binding["byte_length"] > 0
            for binding in review_binding.values()
        )
        and materialized.get("event_sha256")
        == continuation.event_sha256(dict(materialized)),
        "seq66 materialization event differs",
    )
    expected_basis = {
        "dependency_completion_events": [
            {
                "goal_id": READY_DEPENDENCY_ID,
                "event_sha256": READY_DEPENDENCY_COMPLETION_SHA256,
            }
        ],
        "predecessor_goal_id": MATERIALIZATION_PREDECESSOR_ID,
        "predecessor_completion_event_sha256": (
            MATERIALIZATION_PREDECESSOR_COMPLETION_SHA256
        ),
    }
    require(
        set(ready) == READY_EVENT_FIELDS
        and ready.get("sequence") == 67
        and ready.get("event_id") == READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("occurred_on") == "2026-08-13"
        and ready.get("occurred_at") == "2026-08-13T19:48:42+09:00"
        and ready.get("subject_goal_id") == GOAL_ID
        and ready.get("from_status") == "PLANNED"
        and ready.get("to_status") == "READY"
        and ready.get("status_changes") == {GOAL_ID: "READY"}
        and ready.get("previous_event_sha256") == materialized.get("event_sha256")
        and ready.get("evidence_refs") == READY_EVIDENCE_REFS
        and ready.get("readiness_basis") == expected_basis
        and ready.get("start_evidence_bindings") == {}
        and ready.get("start_evidence_provenance") == {}
        and ready.get("implementation_start_gate_contract_binding")
        == contract_binding()
        and ready.get("event_sha256") == continuation.event_sha256(dict(ready)),
        "seq67 readiness event differs",
    )
    canonical = _binding_map(source)
    require(
        all(event.get("canonical_binding_snapshot_after") == canonical for event in events)
        and all(
            all(role in event["canonical_binding_snapshot_after"] for role in event["evidence_refs"])
            for event in events
        ),
        "seq66/67 canonical evidence snapshot differs",
    )
    inventory = after["dynamic_goal_inventory"]
    children = after["materialized_child_goal_ids_by_parent"]
    require(
        len(inventory) == len(before["dynamic_goal_inventory"]) + 1
        and inventory.get(GOAL_ID) == _inventory_record(materialized["event_sha256"])
        and children.get(PARENT_GOAL_ID) == [GOAL_ID]
        and ready.get("dynamic_goal_inventory_after") == inventory
        and ready.get("materialized_child_goal_ids_by_parent_after") == children,
        "FP-022 dynamic inventory or child binding differs",
    )
    require(
        after["status_by_goal"].get(GOAL_ID) == "READY"
        and after["focus_goal_id"] == GOAL_ID
        and after["focus_goal_path"] == GOAL_PATH.as_posix()
        and after["focus_work_item_id"] == WORK_ITEM_ID
        and after["focus_source"] == "IMPLEMENTATION_BACKLOG"
        and after["ready_frontier_goal_ids"] == READY_FRONTIER
        and materialized["runtime_after"]["ready_frontier_goal_ids"]
        == MATERIALIZED_FRONTIER
        and ready["runtime_after"]["ready_frontier_goal_ids"] == READY_FRONTIER,
        "FP-022 final runtime or frontier differs",
    )
    materialized_runtime = materialized["runtime_after"]
    ready_runtime = ready["runtime_after"]
    require(
        materialized_runtime.get("focus_goal_id") == PARENT_GOAL_ID
        and materialized_runtime.get("focus_goal_path")
        == PARENT_GOAL_PATH.as_posix()
        and materialized_runtime.get("focus_work_item_id") == ""
        and materialized_runtime.get("focus_source") == "WORKSTREAM_GRAPH"
        and materialized_runtime.get("artifact_work_queue_sha256")
        == EXPECTED_QUEUE_SHA256
        and materialized_runtime.get("completion_boundary_sha256")
        == EXPECTED_MATERIALIZED_BOUNDARY_SHA256
        and ready_runtime.get("focus_goal_id") == GOAL_ID
        and ready_runtime.get("focus_goal_path") == GOAL_PATH.as_posix()
        and ready_runtime.get("focus_work_item_id") == WORK_ITEM_ID
        and ready_runtime.get("focus_source") == "IMPLEMENTATION_BACKLOG"
        and ready_runtime.get("artifact_work_queue_sha256")
        == EXPECTED_QUEUE_SHA256
        and ready_runtime.get("completion_boundary_sha256")
        == EXPECTED_READY_BOUNDARY_SHA256,
        "FP-022 exact event runtime differs",
    )
    current = projected["current_work"]
    handoff = projected["session_handoff"]
    require(
        current.get("work_item_id") == WORK_ITEM_ID
        and current.get("status") == "READY"
        and current.get("current_focus")
        == "FP-022/GAP-031 Goal READY; active internal start gate not run"
        and current.get("release_completion_claimed") is False
        and handoff.get("current_epic")
        == "EPIC-04 / FP-022/GAP-031 READY_NOT_STARTED"
        and handoff.get("last_updated_by_work_item") == WORK_ITEM_ID
        and handoff.get("last_verification_status")
        == source["session_handoff"]["last_verification_status"]
        and handoff.get("next_single_action")
        == source["session_handoff"]["next_single_action"],
        "FP-022 current-work or handoff projection differs",
    )
    require(
        not any(
            event.get("event_type") in {"GOAL_STARTED", "GOAL_COMPLETED"}
            and event.get("subject_goal_id") == GOAL_ID
            for event in after["transition_history"]
        ),
        "FP-022 readiness gained start or completion credit",
    )


def prepare(
    root: Path = ROOT,
    *,
    allow_stale_catalogs: bool = False,
    projected_validator: Callable[[Path, bytes], None] | None = None,
) -> Prepared:
    root = root.resolve(strict=True)
    validator = projected_validator or npc.validate_projected_with_both_checkers
    checkpoint_path = npc._safe_file(root, CHECKPOINT_REL)
    metadata = checkpoint_path.stat()
    require(
        stat.S_IMODE(metadata.st_mode) == 0o600 and metadata.st_nlink == 1,
        "checkpoint publication identity differs",
    )
    source_bytes = checkpoint_path.read_bytes()
    source = npc.trace.strict_json_bytes(source_bytes, CHECKPOINT_REL.as_posix())
    require_source(source_bytes, source)
    universe = catalogs.discover_source_paths(root)
    physical = aggregate._snapshot_digests(root, source)
    draft, _ = project(root, source, physical)
    candidate = npc._build_candidate_catalog_bytes(root, universe, draft)
    final = dict(physical)
    final.update({path: bytes_sha256(raw) for path, raw in candidate.items()})
    projected, events = project(root, source, final)
    require(
        npc._build_candidate_catalog_bytes(root, universe, projected) == candidate,
        "catalog/checkpoint projection did not reach a fixed point",
    )
    validate_projection(root, source, projected, events)
    projected_bytes = npc.trace.json_text(projected).encode()

    if not allow_stale_catalogs:
        def revalidate_closure() -> None:
            require(
                checkpoint_path.read_bytes() == source_bytes,
                "source changed during FP-022 preflight",
            )
            require(
                catalogs.discover_source_paths(root) == universe,
                "catalog source universe changed during FP-022 preflight",
            )
            npc._require_candidate_catalogs_exact(
                root, universe, candidate, checkpoint=projected
            )
            npc._require_physical_matches(root, physical)
            require(
                continuation.working_snapshot_hashes(
                    root, sorted(path.as_posix() for path in final)
                )
                == npc._snapshot_hashes_from_digests(final),
                "live FP-022 managed snapshot differs",
            )
            npc._require_git_visible_changes_are_managed(root, final)

        revalidate_closure()
        validator(root, projected_bytes)
        revalidate_closure()
    return Prepared(
        root=root,
        checkpoint_path=checkpoint_path,
        source_bytes=source_bytes,
        projected=projected,
        projected_bytes=projected_bytes,
        final_sha256_by_path=final,
        physical_sha256_by_path=dict(final),
        source_universe=universe,
        candidate_catalogs=candidate,
        catalogs_verified=not allow_stale_catalogs,
        projected_validator=validator,
    )


def write_catalogs(prepared: Prepared) -> None:
    require(not prepared.catalogs_verified, "catalog refresh requires a stale projection")

    def guard() -> None:
        require(
            prepared.checkpoint_path.read_bytes() == prepared.source_bytes,
            "source changed during FP-022 catalog refresh",
        )
        require(
            catalogs.discover_source_paths(prepared.root) == prepared.source_universe,
            "catalog source universe changed during FP-022 refresh",
        )
        require(
            npc._build_candidate_catalog_bytes(
                prepared.root, prepared.source_universe, prepared.projected
            )
            == prepared.candidate_catalogs,
            "FP-022 catalog projection changed during refresh",
        )

    guard()
    for relative in CATALOG_PATHS:
        target = npc._safe_file(prepared.root, relative)
        source = target.read_bytes()
        wanted = prepared.candidate_catalogs[relative]
        if source != wanted:
            npc.atomic_write(target, wanted, expected_source=source, commit_guard=guard)
    guard()


def write_checkpoint(prepared: Prepared) -> None:
    refreshed = prepare(
        prepared.root, projected_validator=prepared.projected_validator
    )
    require(
        refreshed.source_bytes == prepared.source_bytes
        and refreshed.projected_bytes == prepared.projected_bytes
        and refreshed.candidate_catalogs == prepared.candidate_catalogs
        and refreshed.final_sha256_by_path == prepared.final_sha256_by_path
        and refreshed.physical_sha256_by_path == prepared.physical_sha256_by_path
        and refreshed.source_universe == prepared.source_universe,
        "FP-022 projection or physical authority changed before write",
    )
    cohort = npc.retain_physical_pin_cohort(
        prepared.root, refreshed.physical_sha256_by_path
    )
    primary: BaseException | None = None
    try:
        def source_loader(root: Path) -> tuple[str, ...]:
            return npc._catalog_source_universe_at_checkpoint_commit(
                root,
                prepared.source_bytes,
                prepared.projected_bytes,
                loader=catalogs.discover_source_paths,
            )

        def changed_path_loader(root: Path) -> set[Path]:
            return npc._git_visible_paths_at_checkpoint_commit(
                root,
                prepared.source_bytes,
                prepared.projected_bytes,
                loader=npc._git_visible_managed_paths,
            )

        def commit_guard() -> None:
            def verify_closure() -> None:
                cohort.verify()
                npc._require_candidate_catalogs_exact(
                    prepared.root,
                    prepared.source_universe,
                    prepared.candidate_catalogs,
                    checkpoint=prepared.projected,
                    catalog_source_loader=source_loader,
                )
                npc._require_physical_matches(
                    prepared.root, prepared.physical_sha256_by_path
                )
                require(
                    continuation.working_snapshot_hashes(
                        prepared.root,
                        sorted(
                            path.as_posix()
                            for path in prepared.final_sha256_by_path
                        ),
                    )
                    == npc._snapshot_hashes_from_digests(
                        prepared.final_sha256_by_path
                    ),
                    "FP-022 managed closure changed at commit",
                )
                npc._require_git_visible_changes_are_managed(
                    prepared.root,
                    prepared.final_sha256_by_path,
                    changed_path_loader=changed_path_loader,
                )

            verify_closure()
            prepared.projected_validator(prepared.root, prepared.projected_bytes)
            verify_closure()

        npc.atomic_write(
            prepared.checkpoint_path,
            prepared.projected_bytes,
            expected_source=prepared.source_bytes,
            commit_guard=commit_guard,
        )
    except BaseException as exc:
        primary = exc
        raise
    finally:
        cohort.close(primary)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--refresh-catalogs", action="store_true")
    mode.add_argument("--write", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.refresh_catalogs:
            stale = prepare(args.root, allow_stale_catalogs=True)
            write_catalogs(stale)
            prepare(args.root)
        else:
            prepared = prepare(args.root)
            if args.write:
                write_checkpoint(prepared)
    except npc.CompletionPostCommitError as exc:
        print(f"WalkSafe FP-022 seq66/67: POSTCOMMIT_UNCERTAIN: {exc}")
        return 1
    except (
        OSError,
        ValueError,
        TypeError,
        npc.BuildError,
        npc.CompletionApplyError,
        catalogs.CatalogError,
    ) as exc:
        print(f"WalkSafe FP-022 seq66/67: FAIL: {exc}")
        return 1
    print("WalkSafe FP-022 seq66/67: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
