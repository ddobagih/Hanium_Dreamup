#!/usr/bin/env python3
"""Materialize and ready the FP-048/GAP-057 v2.4 policy-gap Goal."""

from __future__ import annotations

import argparse
import copy
import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph


CHECKPOINT = Path("docs/control/walksafe-project-continuation-checkpoint.json")
GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R001"
GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-03/epic-03-fp048-encryption-connection-security-incident-r001.md"
)
GOAL_SHA256 = "043b5a463914015a5918b885c3f69c242ece190358bd18ab1f884de46f553740"
WORK_ITEM_ID = "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PARENT_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
PARENT_GOAL_SHA256 = (
    "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
)
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-047-R001"
PREDECESSOR_GOAL_SHA256 = (
    "2ff79dde64cb113d755855f05dafb6bab1393717f060b4db387bb2d48bc53b06"
)
PREDECESSOR_COMPLETION_EVENT_SHA256 = (
    "aca93931b1cd8dcc06508f4078a7983d729df43f6a6690fceb1b3e875090152f"
)
BACKLOG_PATH = (
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260726-r021.json"
)
BACKLOG_ID = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-021"
BACKLOG_SHA256 = (
    "bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0"
)
MATERIALIZED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-FP048-20260802-001"
)
READY_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP048-20260802-001"
MATERIALIZED_AT = "2026-08-02T20:38:00+09:00"
READY_AT = "2026-08-02T20:38:01+09:00"
SOURCE_TAIL_SHA256 = (
    "c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a"
)
SOURCE_PATHS = (
    GOAL_PATH.as_posix(),
    "scripts/materialize_walksafe_fp048_goal_20260802.py",
)
FINAL_CURRENT_FOCUS = (
    "FP048/GAP-057 Goal READY; 저장소 내부 암호화·열쇠 분리·회전·감사 "
    "구현 시작 gate 대기"
)
FINAL_HANDOFF_EPIC = "EPIC-03 / FP048/GAP-057 READY_NOT_STARTED"
FINAL_SCOPE = (
    "Graph v2.4 through FP048/GAP-057 materialization seq40 and readiness "
    "seq41; no GOAL_STARTED or product implementation credit."
)


def json_bytes(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")


def load_checkpoint(root: Path) -> dict[str, Any]:
    value = json.loads((root / CHECKPOINT).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("checkpoint root is not an object")
    return value


def require_goal_document(root: Path) -> None:
    path = root / GOAL_PATH
    if path.is_symlink() or not path.is_file():
        raise ValueError("FP048 Goal document is missing or unsafe")
    if contract.sha256_file(path) != GOAL_SHA256:
        raise ValueError("FP048 Goal document SHA-256 differs")


def _runtime_projection(
    state: dict[str, Any],
    *,
    focus_goal_id: str,
    focus_goal_path: str,
    focus_work_item_id: str,
    focus_source: str,
    ready_frontier_goal_ids: list[str],
    artifact_work_queue: dict[str, Any],
    completion_boundary: dict[str, Any],
) -> dict[str, Any]:
    return {
        "focus_goal_id": focus_goal_id,
        "focus_goal_path": focus_goal_path,
        "focus_work_item_id": focus_work_item_id,
        "focus_source": focus_source,
        "ready_frontier_goal_ids": copy.deepcopy(ready_frontier_goal_ids),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": contract.canonical_json_sha256(
            artifact_work_queue
        ),
        "completion_boundary_sha256": contract.canonical_json_sha256(
            completion_boundary
        ),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def derive_queue_and_boundary(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    ready_frontier_goal_ids: list[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    state = checkpoint["goal_execution"]
    bindings = goal_graph.frozen_goal.canonical_binding_map(checkpoint)
    register_binding = bindings.get("ARTIFACT_REGISTER")
    if not isinstance(register_binding, dict):
        raise ValueError("FP048 ARTIFACT_REGISTER binding is missing")
    register = contract.load_json(root / register_binding["path"])
    node_errors, nodes = goal_graph.frozen_goal.current_goal_nodes(root, state)
    if node_errors:
        raise ValueError("FP048 Goal nodes differ: " + "; ".join(node_errors))
    queue_errors, queue = goal_graph.derive_v24_artifact_work_queue_from_register(
        register_binding,
        register,
        nodes,
        state["status_by_goal"],
    )
    if queue_errors:
        raise ValueError(
            "FP048 artifact queue differs: " + "; ".join(queue_errors)
        )
    boundary_errors, boundary = goal_graph.frozen_goal.derive_completion_boundary(
        nodes,
        state["status_by_goal"],
        ready_frontier_goal_ids,
        state["blockers_by_goal"],
        queue,
        package_status=state["package_status"],
    )
    if boundary_errors:
        raise ValueError(
            "FP048 completion boundary differs: "
            + "; ".join(boundary_errors)
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


def require_source(checkpoint: dict[str, Any]) -> None:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if (
        checkpoint.get("schema_version") != "1.25.0"
        or not isinstance(history, list)
        or len(history) != 39
        or not isinstance(history[-1], dict)
        or history[-1].get("sequence") != 39
        or history[-1].get("event_id") != contract.V24_SEQ39_EVENT_ID
        or history[-1].get("event_sha256") != SOURCE_TAIL_SHA256
        or state.get("transition_history_anchor_sha256") != SOURCE_TAIL_SHA256
        or state.get("focus_goal_id") != PARENT_GOAL_ID
        or state.get("status_by_goal", {}).get(PREDECESSOR_GOAL_ID)
        != "COMPLETE_AT_TARGET"
        or GOAL_ID in state.get("status_by_goal", {})
    ):
        raise ValueError("checkpoint is not the exact live seq39 FP048 source")


def project(root: Path, source: dict[str, Any]) -> dict[str, Any]:
    require_source(source)
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    canonical_snapshot = contract.canonical_binding_snapshot(checkpoint)
    if canonical_snapshot.get("IMPLEMENTATION_BACKLOG") != {
        "role": "IMPLEMENTATION_BACKLOG",
        "document_id": BACKLOG_ID,
        "path": BACKLOG_PATH,
        "file_sha256": BACKLOG_SHA256,
    }:
        raise ValueError("FP048 canonical backlog binding differs")

    goal_paths = sorted([*state["goal_document_paths"], GOAL_PATH.as_posix()])
    managed_goal_paths = sorted(
        [*state["managed_goal_paths"], GOAL_PATH.as_posix()]
    )
    state["goal_document_paths"] = goal_paths
    state["goal_document_count"] = len(goal_paths)
    state["managed_goal_paths"] = managed_goal_paths
    state["managed_goal_path_count"] = len(managed_goal_paths)
    state["path_set_sha256"], state["content_set_sha256"] = (
        contract.package_hashes(root, managed_goal_paths)
    )

    inventory = copy.deepcopy(state["dynamic_goal_inventory"])
    inventory[GOAL_ID] = _inventory_record("0" * 64)
    children = copy.deepcopy(state["materialized_child_goal_ids_by_parent"])
    children[PARENT_GOAL_ID] = [
        *children.get(PARENT_GOAL_ID, []),
        GOAL_ID,
    ]
    state["status_by_goal"][GOAL_ID] = "PLANNED"
    state["dynamic_goal_inventory"] = inventory
    state["materialized_child_goal_ids_by_parent"] = children
    planned_frontier = [PARENT_GOAL_ID, "WS-GOAL-EPIC-12"]
    planned_queue, planned_boundary = derive_queue_and_boundary(
        root,
        checkpoint,
        ready_frontier_goal_ids=planned_frontier,
    )
    materialized: dict[str, Any] = {
        "sequence": 40,
        "event_id": MATERIALIZED_EVENT_ID,
        "event_type": "GOAL_MATERIALIZED",
        "occurred_on": "2026-08-02",
        "occurred_at": MATERIALIZED_AT,
        "previous_focus_goal_id": PARENT_GOAL_ID,
        "previous_focus_content_sha256": PARENT_GOAL_SHA256,
        "focus_goal_id": PARENT_GOAL_ID,
        "focus_goal_content_sha256": PARENT_GOAL_SHA256,
        "from_status": None,
        "to_status": "PLANNED",
        "static_plan_manifest_sha256": state[
            "static_plan_manifest_sha256"
        ],
        "status_changes": {GOAL_ID: "PLANNED"},
        "runtime_after": _runtime_projection(
            state,
            focus_goal_id=PARENT_GOAL_ID,
            focus_goal_path=PARENT_GOAL_PATH,
            focus_work_item_id="",
            focus_source="WORKSTREAM_GRAPH",
            ready_frontier_goal_ids=planned_frontier,
            artifact_work_queue=planned_queue,
            completion_boundary=planned_boundary,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": ["IMPLEMENTATION_BACKLOG"],
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
        "previous_event_sha256": SOURCE_TAIL_SHA256,
        "canonical_binding_snapshot_after": copy.deepcopy(
            canonical_snapshot
        ),
    }
    materialized["event_sha256"] = contract.event_sha256(materialized)

    inventory[GOAL_ID] = _inventory_record(materialized["event_sha256"])
    state["dynamic_goal_inventory"] = inventory
    state["status_by_goal"][GOAL_ID] = "READY"
    ready_frontier = [GOAL_ID, PARENT_GOAL_ID, "WS-GOAL-EPIC-12"]
    ready_queue, ready_boundary = derive_queue_and_boundary(
        root,
        checkpoint,
        ready_frontier_goal_ids=ready_frontier,
    )
    ready: dict[str, Any] = {
        "sequence": 41,
        "event_id": READY_EVENT_ID,
        "event_type": "GOAL_READY",
        "occurred_on": "2026-08-02",
        "occurred_at": READY_AT,
        "previous_focus_goal_id": PARENT_GOAL_ID,
        "previous_focus_content_sha256": PARENT_GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "from_status": "PLANNED",
        "to_status": "READY",
        "static_plan_manifest_sha256": state[
            "static_plan_manifest_sha256"
        ],
        "status_changes": {GOAL_ID: "READY"},
        "runtime_after": _runtime_projection(
            state,
            focus_goal_id=GOAL_ID,
            focus_goal_path=GOAL_PATH.as_posix(),
            focus_work_item_id=WORK_ITEM_ID,
            focus_source="IMPLEMENTATION_BACKLOG",
            ready_frontier_goal_ids=ready_frontier,
            artifact_work_queue=ready_queue,
            completion_boundary=ready_boundary,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [],
        "subject_goal_id": GOAL_ID,
        "readiness_basis": {
            "dependency_completion_events": [
                {
                    "goal_id": PREDECESSOR_GOAL_ID,
                    "event_sha256": PREDECESSOR_COMPLETION_EVENT_SHA256,
                }
            ],
            "predecessor_goal_id": PREDECESSOR_GOAL_ID,
            "predecessor_completion_event_sha256": (
                PREDECESSOR_COMPLETION_EVENT_SHA256
            ),
        },
        "start_evidence_bindings": {},
        "start_evidence_provenance": {},
        "dynamic_goal_inventory_after": copy.deepcopy(inventory),
        "materialized_child_goal_ids_by_parent_after": copy.deepcopy(
            children
        ),
        "previous_event_sha256": materialized["event_sha256"],
        "canonical_binding_snapshot_after": copy.deepcopy(
            canonical_snapshot
        ),
    }
    ready["event_sha256"] = contract.event_sha256(ready)

    state["transition_history"].extend([materialized, ready])
    state["transition_history_anchor_sha256"] = ready["event_sha256"]
    state["validation_cutoff_at"] = READY_AT
    state["status_by_goal"][GOAL_ID] = "READY"
    state["dynamic_goal_inventory"] = inventory
    state["materialized_child_goal_ids_by_parent"] = children
    state["focus_goal_id"] = GOAL_ID
    state["focus_goal_path"] = GOAL_PATH.as_posix()
    state["focus_work_item_id"] = WORK_ITEM_ID
    state["focus_source"] = "IMPLEMENTATION_BACKLOG"
    state["ready_frontier_goal_ids"] = ready_frontier
    state["artifact_work_queue"] = ready_queue
    state["completion_boundary"] = ready_boundary

    current = checkpoint["current_work"]
    current["work_item_id"] = WORK_ITEM_ID
    current["current_focus"] = FINAL_CURRENT_FOCUS

    snapshot = checkpoint["working_tree_snapshot"]
    managed_paths = sorted(set(snapshot["managed_changed_paths"]) | set(SOURCE_PATHS))
    path_hash, content_hash = contract.working_snapshot_hashes(
        root,
        managed_paths,
    )
    snapshot["scope"] = FINAL_SCOPE
    snapshot["managed_changed_paths"] = managed_paths
    snapshot["managed_changed_path_count"] = len(managed_paths)
    snapshot["path_set_sha256"] = path_hash
    snapshot["content_set_sha256"] = content_hash

    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    handoff["source_commit_or_snapshot"]["file_count"] = len(managed_paths)
    handoff["source_commit_or_snapshot"]["path_set_sha256"] = path_hash
    handoff["source_commit_or_snapshot"]["content_set_sha256"] = content_hash
    handoff["current_epic"] = FINAL_HANDOFF_EPIC
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = "READY_NOT_STARTED"
    return checkpoint


def reverse_projection(
    root: Path,
    checkpoint: dict[str, Any],
) -> dict[str, Any]:
    source = copy.deepcopy(checkpoint)
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if (
        not isinstance(history, list)
        or len(history) != 41
        or history[39].get("event_id") != MATERIALIZED_EVENT_ID
        or history[40].get("event_id") != READY_EVENT_ID
    ):
        raise ValueError("checkpoint is neither seq39 source nor FP048 seq41")
    del history[39:]
    state["transition_history_anchor_sha256"] = SOURCE_TAIL_SHA256
    state["validation_cutoff_at"] = history[-1]["occurred_at"]
    state["status_by_goal"].pop(GOAL_ID, None)
    state["dynamic_goal_inventory"].pop(GOAL_ID, None)
    children = state["materialized_child_goal_ids_by_parent"]
    children[PARENT_GOAL_ID] = [
        item for item in children[PARENT_GOAL_ID] if item != GOAL_ID
    ]
    state["focus_goal_id"] = PARENT_GOAL_ID
    state["focus_goal_path"] = PARENT_GOAL_PATH
    state["focus_work_item_id"] = ""
    state["focus_source"] = "WORKSTREAM_GRAPH"
    state["ready_frontier_goal_ids"] = [PARENT_GOAL_ID, "WS-GOAL-EPIC-12"]
    state["artifact_work_queue"] = copy.deepcopy(
        history[-1]["runtime_after"]["artifact_work_queue"]
    )
    state["completion_boundary"] = copy.deepcopy(
        history[-1]["runtime_after"]["completion_boundary"]
    )
    for key in ("goal_document_paths", "managed_goal_paths"):
        state[key] = [item for item in state[key] if item != GOAL_PATH.as_posix()]
    state["goal_document_count"] = len(state["goal_document_paths"])
    state["managed_goal_path_count"] = len(state["managed_goal_paths"])
    state["path_set_sha256"], state["content_set_sha256"] = (
        contract.package_hashes(root, state["managed_goal_paths"])
    )
    source["current_work"]["work_item_id"] = (
        "EPIC-03-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION"
    )
    source["current_work"]["current_focus"] = (
        "FP047/GAP-056 내부 구현 완료 및 r021 정본 반영 후 EPIC-03 "
        "Workstream READY, 다음 FP048/GAP-057 준비"
    )
    snapshot = source["working_tree_snapshot"]
    snapshot["managed_changed_paths"] = [
        item for item in snapshot["managed_changed_paths"] if item not in SOURCE_PATHS
    ]
    snapshot["managed_changed_path_count"] = len(snapshot["managed_changed_paths"])
    snapshot["scope"] = (
        "Graph v2.4 through finalized FP047 completion seq38, with r021 "
        "canonical audits and final FP047 evidence in the live controlled snapshot."
    )
    handoff = source["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(snapshot["managed_changed_paths"])
    handoff["source_commit_or_snapshot"]["file_count"] = len(
        snapshot["managed_changed_paths"]
    )
    handoff["current_epic"] = (
        "EPIC-03 / FP047/GAP-056 COMPLETE; FP048/GAP-057 NEXT"
    )
    handoff["last_updated_by_work_item"] = (
        "EPIC-03-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION"
    )
    handoff["last_verification_status"] = "PASS_WITH_EPIC_IN_PROGRESS"
    return source


def expected_output(root: Path, checkpoint: dict[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if isinstance(history, list) and len(history) == 39:
        source = checkpoint
    else:
        source = reverse_projection(root, checkpoint)
    return project(root, source)


def atomic_write(path: Path, content: bytes) -> None:
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
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        require_goal_document(root)
        checkpoint = load_checkpoint(root)
        expected = expected_output(root, checkpoint)
        expected_bytes = json_bytes(expected)
        checkpoint_path = root / CHECKPOINT
        if args.write:
            atomic_write(checkpoint_path, expected_bytes)
            mode_name = "WRITE"
        else:
            if checkpoint_path.read_bytes() != expected_bytes:
                raise ValueError("FP048 materialized checkpoint projection differs")
            mode_name = "CHECK"
    except (KeyError, OSError, TypeError, ValueError) as exc:
        print(f"WalkSafe FP048 Goal materialization: FAIL: {exc}")
        return 1
    state = expected["goal_execution"]
    print(
        "WalkSafe FP048 Goal materialization: PASS "
        f"mode={mode_name} tail={state['transition_history_anchor_sha256']} "
        f"focus={state['focus_goal_id']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
