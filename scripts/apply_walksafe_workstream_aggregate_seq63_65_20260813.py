#!/usr/bin/env python3
"""Atomically complete EPIC-02/03 and ready EPIC-04 at seq63-65."""

from __future__ import annotations

import argparse
import copy
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812 as npc
from scripts import apply_walksafe_fp046_goal_completed_seq54_55_20260810 as fp046
from scripts import check_walksafe_goal_graph_v2_4 as graph
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import build_walksafe_workstream_aggregate_review_20260813 as aggregate_review
from scripts import generate_repository_catalogs as catalogs


CHECKPOINT_REL = npc.CHECKPOINT_REL
CATALOG_PATHS = npc.CATALOG_PATHS
SOURCE_SHA256 = "250d94d68329e1ce30083697726e2312ba57140eeb33a58c0b7501c4594fd408"
SOURCE_BYTE_COUNT = 1_842_320
SOURCE_TAIL_SHA256 = "24f586dda8e19d9c1315bee80e546906359869c49e5fa776bb891ec76beb13c1"
MANIFEST_SHA256 = npc.MANIFEST_SHA256

EPIC02 = "WS-GOAL-EPIC-02"
EPIC03 = "WS-GOAL-EPIC-03"
EPIC04 = "WS-GOAL-EPIC-04"
EPIC12 = "WS-GOAL-EPIC-12"
EPIC02_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-02-safe-walk-state-and-permissions.md"
)
EPIC03_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
EPIC04_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-04-navigation-arrival-deviation.md"
)
EPIC02_SHA256 = "40fafdf86acf23c8c7243bebc3e7c4c0566a56123b5c4c815f29d5fc3eb15665"
EPIC03_SHA256 = "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
EPIC04_SHA256 = "da4aa5a7ab2abe8c3a746c8df4edd69db77ea1dbc2b3e1bdc00399291b99ac6d"
WORKSTREAM_PATHS = {
    EPIC02: EPIC02_PATH,
    EPIC03: EPIC03_PATH,
    EPIC04: EPIC04_PATH,
    EPIC12: Path(
        "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
        "epic-12-formal-verification-gates.md"
    ),
}

EPIC02_CHILDREN = (
    "WS-GOAL-EPIC-02-FP-018-R001",
    "WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001",
    "WS-GOAL-EPIC-02-FP-004-R001",
    "WS-GOAL-EPIC-02-FP-005-R001",
    "WS-GOAL-EPIC-02-FP-006-R001",
    "WS-GOAL-EPIC-02-FP-010-R001",
    "WS-GOAL-EPIC-02-FP-011-R001",
    "WS-GOAL-EPIC-02-FP-013-R001",
    "WS-GOAL-EPIC-02-FP-015-R001",
    "WS-GOAL-EPIC-02-FP-014-R001",
    "WS-GOAL-EPIC-02-FP-016-R001",
    "WS-GOAL-EPIC-02-FP-012-R001",
)
EPIC03_CHILDREN = (
    "WS-GOAL-EPIC-03-FP-047-R001",
    "WS-GOAL-EPIC-03-FP-048-R001",
    "WS-GOAL-EPIC-03-FP-008-R001",
    "WS-GOAL-EPIC-03-FP-046-R001",
    "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001",
)
EPIC02_REFS = (
    "EPIC02_PHASE_A_RECORD",
    "IMPLEMENTATION_BACKLOG",
    *(f"WORK_ITEM_COMPLETION::{goal_id}" for goal_id in EPIC02_CHILDREN),
)
EPIC03_REFS = (
    "IMPLEMENTATION_BACKLOG",
    *(f"WORK_ITEM_COMPLETION::{goal_id}" for goal_id in EPIC03_CHILDREN),
)
EVENT_IDS = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-EPIC02-20260813-001",
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-EPIC04-20260813-001",
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-EPIC03-20260813-001",
)
SCRIPT_REL = Path("scripts/apply_walksafe_workstream_aggregate_seq63_65_20260813.py")
TEST_REL = Path("tests/test_apply_walksafe_workstream_aggregate_seq63_65_20260813.py")
FINAL_SCOPE = (
    "Graph v2.4 through aggregate Workstream transitions seq63-65: EPIC-02 and "
    "EPIC-03 are COMPLETE_AT_TARGET from their existing child completion evidence, "
    "and EPIC-04 is READY; formal, device, external, deployment, and release credit "
    "remain unchanged."
)

require = npc.require
bytes_sha256 = npc.bytes_sha256


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


def _document(root: Path, relative: Path) -> tuple[dict[str, Any], bytes]:
    raw = npc._read_safe_bytes(root, relative)
    return npc.trace.strict_json_bytes(raw, relative.as_posix()), raw


def require_source(raw: bytes, source: Mapping[str, Any]) -> None:
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(len(raw) == SOURCE_BYTE_COUNT, "seq62 source byte count differs")
    require(bytes_sha256(raw) == SOURCE_SHA256, "seq62 source SHA-256 differs")
    require(
        isinstance(history, list)
        and len(history) == 62
        and history[-1].get("sequence") == 62
        and history[-1].get("event_sha256") == SOURCE_TAIL_SHA256
        and state.get("transition_history_anchor_sha256") == SOURCE_TAIL_SHA256,
        "source is not exact seq62",
    )
    statuses = state.get("status_by_goal", {})
    require(
        statuses.get(EPIC02) == "READY"
        and statuses.get(EPIC03) == "READY"
        and statuses.get(EPIC04) == "PLANNED"
        and state.get("focus_goal_id") == EPIC02,
        "seq62 Workstream status boundary differs",
    )


def _binding_map(checkpoint: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    return continuation.canonical_binding_snapshot(dict(checkpoint))


def _completion_bindings(
    snapshot: Mapping[str, Mapping[str, Any]], refs: Sequence[str]
) -> dict[str, dict[str, Any]]:
    require(len(refs) == len(set(refs)), "aggregate evidence role is duplicated")
    missing = set(refs) - set(snapshot)
    require(not missing, "aggregate evidence role is missing: " + ", ".join(sorted(missing)))
    return {role: dict(snapshot[role]) for role in refs}


def _validate_aggregate_completion_contract(
    root: Path,
    checkpoint: Mapping[str, Any],
    nodes: Mapping[str, Mapping[str, Any]],
    backlog: Mapping[str, Any],
    *,
    goal_id: str,
    refs: Sequence[str],
) -> None:
    state = checkpoint["goal_execution"]
    statuses = state["status_by_goal"]
    active_children = {
        candidate_id
        for candidate_id, candidate in nodes.items()
        if candidate.get("goal_kind") == "WORK_ITEM"
        and candidate.get("parent_goal_id") == goal_id
        and statuses.get(candidate_id) != "SUPERSEDED"
    }
    declared_children = EPIC02_CHILDREN if goal_id == EPIC02 else EPIC03_CHILDREN
    aggregate_roles = (
        ["EPIC02_PHASE_A_RECORD", "IMPLEMENTATION_BACKLOG"]
        if goal_id == EPIC02
        else ["IMPLEMENTATION_BACKLOG"]
    )
    expected_refs = [
        *aggregate_roles,
        *(f"WORK_ITEM_COMPLETION::{child}" for child in declared_children),
    ]
    require(
        active_children == set(declared_children)
        and len(declared_children) == len(set(declared_children))
        and list(refs) == expected_refs,
        f"{goal_id} aggregate evidence does not cover the exact active child set",
    )
    snapshot = _binding_map(checkpoint)
    require(
        all(
            statuses.get(child) == "COMPLETE_AT_TARGET"
            and f"WORK_ITEM_COMPLETION::{child}" in snapshot
            and state["completion_evidence_by_goal"].get(child)
            == [f"WORK_ITEM_COMPLETION::{child}"]
            for child in active_children
        ),
        f"{goal_id} aggregate child completion binding differs",
    )
    mapping_errors, mapping = graph.frozen_goal.load_policy_gap_mapping(
        root, snapshot
    )
    require(
        not mapping_errors,
        f"{goal_id} policy/Gap mapping differs: " + "; ".join(mapping_errors),
    )
    errors = graph.frozen_goal.validate_completion_contracts(
        dict(nodes),
        dict(statuses),
        graph.frozen_goal.replay_materialized_children(
            dict(nodes), dict(statuses)
        ),
        dict(backlog),
        mapping,
        root=root,
        bindings=snapshot,
    )
    require(
        not errors,
        f"{goal_id} aggregate completion contract differs: " + "; ".join(errors),
    )


def _load_nodes_and_backlog(
    root: Path, checkpoint: Mapping[str, Any]
) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    state = checkpoint["goal_execution"]
    errors, nodes = graph.frozen_goal.current_goal_nodes(root, state)
    require(not errors, "Goal nodes differ: " + "; ".join(errors))
    binding = _binding_map(checkpoint).get("IMPLEMENTATION_BACKLOG")
    require(isinstance(binding, dict), "IMPLEMENTATION_BACKLOG binding is missing")
    backlog, raw = _document(root, Path(str(binding["path"])))
    require(bytes_sha256(raw) == binding.get("file_sha256"), "Backlog binding differs")
    return nodes, backlog


def _ready_frontier(
    nodes: Mapping[str, Mapping[str, Any]], state: Mapping[str, Any], backlog: Mapping[str, Any]
) -> list[str]:
    return graph.frozen_goal.ready_frontier(
        dict(nodes),
        dict(state["status_by_goal"]),
        dict(state["materialized_child_goal_ids_by_parent"]),
        dict(state["blockers_by_goal"]),
        dict(backlog),
    )


def _set_runtime(
    root: Path,
    checkpoint: dict[str, Any],
    nodes: Mapping[str, Mapping[str, Any]],
    backlog: Mapping[str, Any],
) -> None:
    state = checkpoint["goal_execution"]
    frontier = _ready_frontier(nodes, state, backlog)
    require(frontier, "aggregate transition produced an empty ready frontier")
    focus = frontier[0]
    node = nodes[focus]
    focus_path = WORKSTREAM_PATHS.get(focus)
    require(focus_path is not None, "aggregate focus Workstream path is unknown")
    state["focus_goal_id"] = focus
    state["focus_goal_path"] = focus_path.as_posix()
    state["focus_work_item_id"] = (
        str(node.get("work_item_id", "")) if node.get("goal_kind") == "WORK_ITEM" else ""
    )
    state["focus_source"] = (
        str(node.get("materialized_from_role"))
        if node.get("goal_kind") == "WORK_ITEM"
        else "WORKSTREAM_GRAPH"
    )
    state["ready_frontier_goal_ids"] = frontier
    queue, boundary = fp046.derive_runtime(root, checkpoint, frontier)
    state["artifact_work_queue"] = queue
    state["completion_boundary"] = boundary


def _runtime(state: Mapping[str, Any]) -> dict[str, Any]:
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


def _base_event(
    checkpoint: Mapping[str, Any],
    *,
    sequence: int,
    event_id: str,
    event_type: str,
    occurred_at: str,
    previous_focus_id: str,
    previous_focus_sha256: str,
    subject_id: str,
    from_status: str,
    to_status: str,
    previous_event_sha256: str,
    evidence_refs: Sequence[str],
) -> dict[str, Any]:
    state = checkpoint["goal_execution"]
    focus = str(state["focus_goal_id"])
    focus_sha = {
        EPIC02: EPIC02_SHA256,
        EPIC03: EPIC03_SHA256,
        EPIC04: EPIC04_SHA256,
    }.get(focus)
    require(isinstance(focus_sha, str), "aggregate focus SHA-256 is unknown")
    event = {
        "sequence": sequence,
        "event_id": event_id,
        "event_type": event_type,
        "occurred_on": datetime.fromisoformat(occurred_at).date().isoformat(),
        "occurred_at": occurred_at,
        "previous_focus_goal_id": previous_focus_id,
        "previous_focus_content_sha256": previous_focus_sha256,
        "focus_goal_id": focus,
        "focus_goal_content_sha256": focus_sha,
        "subject_goal_id": subject_id,
        "from_status": from_status,
        "to_status": to_status,
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {subject_id: to_status},
        "runtime_after": _runtime(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": checkpoint["schema_version"],
        "evidence_refs": list(evidence_refs),
        "previous_event_sha256": previous_event_sha256,
        "canonical_binding_snapshot_after": _binding_map(checkpoint),
    }
    return event


def _append_completion(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    sequence: int,
    event_id: str,
    occurred_at: str,
    goal_id: str,
    goal_sha256: str,
    refs: Sequence[str],
    nodes: Mapping[str, Mapping[str, Any]],
    backlog: Mapping[str, Any],
) -> dict[str, Any]:
    state = checkpoint["goal_execution"]
    previous_focus = str(state["focus_goal_id"])
    previous_focus_sha = goal_sha256
    require(previous_focus == goal_id, "completed Workstream is not deterministic focus")
    state["status_by_goal"][goal_id] = "COMPLETE_AT_TARGET"
    completion = copy.deepcopy(state["completion_evidence_by_goal"])
    completion[goal_id] = list(refs)
    state["completion_evidence_by_goal"] = completion
    _set_runtime(root, checkpoint, nodes, backlog)
    event = _base_event(
        checkpoint,
        sequence=sequence,
        event_id=event_id,
        event_type="GOAL_COMPLETED",
        occurred_at=occurred_at,
        previous_focus_id=previous_focus,
        previous_focus_sha256=previous_focus_sha,
        subject_id=goal_id,
        from_status="READY",
        to_status="COMPLETE_AT_TARGET",
        previous_event_sha256=state["transition_history"][-1]["event_sha256"],
        evidence_refs=refs,
    )
    event["completion_evidence_bindings"] = _completion_bindings(
        _binding_map(checkpoint), refs
    )
    event["completion_evidence_by_goal_after"] = copy.deepcopy(completion)
    event["event_sha256"] = continuation.event_sha256(event)
    state["transition_history"].append(event)
    return event


def _append_ready_epic04(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    sequence: int,
    occurred_at: str,
    epic02_completion_sha256: str,
    nodes: Mapping[str, Mapping[str, Any]],
    backlog: Mapping[str, Any],
) -> dict[str, Any]:
    state = checkpoint["goal_execution"]
    previous_focus = str(state["focus_goal_id"])
    previous_focus_sha = EPIC03_SHA256
    require(previous_focus == EPIC03, "EPIC-03 must remain focus before EPIC-04 ready")
    state["status_by_goal"][EPIC04] = "READY"
    _set_runtime(root, checkpoint, nodes, backlog)
    event = _base_event(
        checkpoint,
        sequence=sequence,
        event_id=EVENT_IDS[1],
        event_type="GOAL_READY",
        occurred_at=occurred_at,
        previous_focus_id=previous_focus,
        previous_focus_sha256=previous_focus_sha,
        subject_id=EPIC04,
        from_status="PLANNED",
        to_status="READY",
        previous_event_sha256=state["transition_history"][-1]["event_sha256"],
        evidence_refs=(),
    )
    event["start_evidence_bindings"] = {}
    event["start_evidence_provenance"] = {}
    event["readiness_basis"] = {
        "dependency_completion_events": [
            {"goal_id": EPIC02, "event_sha256": epic02_completion_sha256}
        ]
    }
    event["event_sha256"] = continuation.event_sha256(event)
    state["transition_history"].append(event)
    return event


def _snapshot_digests(root: Path, source: Mapping[str, Any]) -> dict[Path, str]:
    existing = {Path(path) for path in source["working_tree_snapshot"]["managed_changed_paths"]}
    paths = existing | npc._git_visible_managed_paths(root) | set(CATALOG_PATHS)
    result: dict[Path, str] = {}
    for relative in sorted(paths, key=Path.as_posix):
        result[relative] = bytes_sha256(npc._read_safe_bytes(root, relative))
    return result


def _apply_snapshot(
    checkpoint: dict[str, Any], sha256_by_path: Mapping[Path, str]
) -> None:
    paths = sorted(path.as_posix() for path in sha256_by_path)
    path_hash, content_hash = npc._snapshot_hashes_from_digests(sha256_by_path)
    snapshot = checkpoint["working_tree_snapshot"]
    handoff = checkpoint["session_handoff"]
    mirror = handoff["source_commit_or_snapshot"]
    snapshot.update(
        {
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": path_hash,
            "content_set_sha256": content_hash,
            "scope": FINAL_SCOPE,
        }
    )
    handoff["changed_files"] = copy.deepcopy(paths)
    mirror.update(
        {
            "file_count": len(paths),
            "path_set_sha256": path_hash,
            "content_set_sha256": content_hash,
        }
    )


def project(
    root: Path,
    source: Mapping[str, Any],
    final_sha256_by_path: Mapping[Path, str],
) -> tuple[dict[str, Any], tuple[dict[str, Any], ...]]:
    checkpoint = copy.deepcopy(dict(source))
    nodes, backlog = _load_nodes_and_backlog(root, checkpoint)
    state = checkpoint["goal_execution"]
    transition_review = aggregate_review.transition_review_binding(root)
    snapshot = _binding_map(checkpoint)
    for label, refs in (("EPIC-02", EPIC02_REFS), ("EPIC-03", EPIC03_REFS)):
        errors = graph.frozen_goal.completion_reference_errors(
            root,
            label=label,
            references=list(refs),
            bindings=snapshot,
            direct_packet_document_ids=set(),
        )
        require(not errors, f"{label} aggregate evidence differs: " + "; ".join(errors))
    base_time = datetime.fromisoformat(str(state["validation_cutoff_at"]))
    times = [(base_time + timedelta(seconds=index)).isoformat() for index in (1, 2, 3)]
    seq63 = _append_completion(
        root,
        checkpoint,
        sequence=63,
        event_id=EVENT_IDS[0],
        occurred_at=times[0],
        goal_id=EPIC02,
        goal_sha256=EPIC02_SHA256,
        refs=EPIC02_REFS,
        nodes=nodes,
        backlog=backlog,
    )
    seq63["transition_control_review_binding"] = transition_review
    seq63["source_checkpoint_binding"] = aggregate_review.SOURCE_CHECKPOINT
    seq63["event_sha256"] = continuation.event_sha256(seq63)
    state["transition_history"][-1] = seq63
    _validate_aggregate_completion_contract(
        root,
        checkpoint,
        nodes,
        backlog,
        goal_id=EPIC02,
        refs=EPIC02_REFS,
    )
    seq64 = _append_ready_epic04(
        root,
        checkpoint,
        sequence=64,
        occurred_at=times[1],
        epic02_completion_sha256=seq63["event_sha256"],
        nodes=nodes,
        backlog=backlog,
    )
    seq65 = _append_completion(
        root,
        checkpoint,
        sequence=65,
        event_id=EVENT_IDS[2],
        occurred_at=times[2],
        goal_id=EPIC03,
        goal_sha256=EPIC03_SHA256,
        refs=EPIC03_REFS,
        nodes=nodes,
        backlog=backlog,
    )
    _validate_aggregate_completion_contract(
        root,
        checkpoint,
        nodes,
        backlog,
        goal_id=EPIC03,
        refs=EPIC03_REFS,
    )
    state["transition_history_anchor_sha256"] = seq65["event_sha256"]
    state["validation_cutoff_at"] = times[2]
    _apply_snapshot(checkpoint, final_sha256_by_path)
    return checkpoint, (seq63, seq64, seq65)


def validate_projection(
    root: Path,
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    events: Sequence[Mapping[str, Any]],
) -> None:
    before = source["goal_execution"]
    after = projected["goal_execution"]
    allowed_top_level = {"goal_execution", "working_tree_snapshot", "session_handoff"}
    require(
        set(projected) == set(source)
        and all(projected[key] == source[key] for key in source if key not in allowed_top_level),
        "aggregate transition changed an unrelated top-level field",
    )
    allowed_state = {
        "status_by_goal",
        "completion_evidence_by_goal",
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
        "aggregate transition changed an unrelated Goal execution field",
    )
    before_handoff = source["session_handoff"]
    after_handoff = projected["session_handoff"]
    allowed_handoff = {"changed_files", "source_commit_or_snapshot"}
    require(
        set(after_handoff) == set(before_handoff)
        and all(
            after_handoff[key] == before_handoff[key]
            for key in before_handoff
            if key not in allowed_handoff
        ),
        "aggregate transition changed unrelated handoff state",
    )
    require(
        after["transition_history"][:-3] == before["transition_history"]
        and after["transition_history"][-3:] == list(events)
        and [event["sequence"] for event in events] == [63, 64, 65]
        and [event["event_id"] for event in events] == list(EVENT_IDS),
        "seq63-65 history boundary differs",
    )
    require(
        all(event["event_sha256"] == continuation.event_sha256(dict(event)) for event in events)
        and events[0]["previous_event_sha256"] == SOURCE_TAIL_SHA256
        and events[1]["previous_event_sha256"] == events[0]["event_sha256"]
        and events[2]["previous_event_sha256"] == events[1]["event_sha256"],
        "seq63-65 event chain differs",
    )
    require(
        events[0].get("transition_control_review_binding")
        == aggregate_review.transition_review_binding(root)
        and events[0].get("source_checkpoint_binding")
        == aggregate_review.SOURCE_CHECKPOINT,
        "seq63 transition control review binding differs",
    )
    expected = copy.deepcopy(before["status_by_goal"])
    expected.update({EPIC02: "COMPLETE_AT_TARGET", EPIC04: "READY", EPIC03: "COMPLETE_AT_TARGET"})
    require(after["status_by_goal"] == expected, "Workstream aggregate status map differs")
    require(
        after["ready_frontier_goal_ids"] == [EPIC04, EPIC12]
        and after["focus_goal_id"] == EPIC04
        and events[0]["evidence_refs"] == list(EPIC02_REFS)
        and events[2]["evidence_refs"] == list(EPIC03_REFS)
        and events[1]["readiness_basis"]
        == {
            "dependency_completion_events": [
                {"goal_id": EPIC02, "event_sha256": events[0]["event_sha256"]}
            ]
        },
        "Workstream aggregate frontier or evidence differs",
    )
    expected_runtimes = (
        (EPIC03, EPIC03_PATH.as_posix(), [EPIC03, EPIC12]),
        (EPIC03, EPIC03_PATH.as_posix(), [EPIC03, EPIC04, EPIC12]),
        (EPIC04, EPIC04_PATH.as_posix(), [EPIC04, EPIC12]),
    )
    require(
        all(
            event["runtime_after"]["focus_goal_id"] == goal_id
            and event["runtime_after"]["focus_goal_path"] == path
            and event["runtime_after"]["focus_work_item_id"] == ""
            and event["runtime_after"]["focus_source"] == "WORKSTREAM_GRAPH"
            and event["runtime_after"]["ready_frontier_goal_ids"] == frontier
            and event["completion_evidence_bindings"]
            == _completion_bindings(
                event["canonical_binding_snapshot_after"], event["evidence_refs"]
            )
            for event, (goal_id, path, frontier) in zip(
                (events[0], events[2]),
                (expected_runtimes[0], expected_runtimes[2]),
                strict=True,
            )
        )
        and events[1]["runtime_after"]["focus_goal_id"] == expected_runtimes[1][0]
        and events[1]["runtime_after"]["focus_goal_path"] == expected_runtimes[1][1]
        and events[1]["runtime_after"]["focus_work_item_id"] == ""
        and events[1]["runtime_after"]["focus_source"] == "WORKSTREAM_GRAPH"
        and events[1]["runtime_after"]["ready_frontier_goal_ids"]
        == expected_runtimes[1][2],
        "Workstream aggregate intermediate runtime or evidence binding differs",
    )
    for field in ("authority_boundary", "verification_boundary", "approved_state"):
        require(projected.get(field) == source.get(field), f"{field} credit changed")


def prepare(
    root: Path = ROOT, *, allow_stale_catalogs: bool = False
) -> Prepared:
    root = root.resolve(strict=True)
    checkpoint_path = npc._safe_file(root, CHECKPOINT_REL)
    source_bytes = checkpoint_path.read_bytes()
    source = npc.trace.strict_json_bytes(source_bytes, CHECKPOINT_REL.as_posix())
    require_source(source_bytes, source)
    universe = catalogs.discover_source_paths(root)
    physical = _snapshot_digests(root, source)
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
        def revalidate_preflight_closure() -> None:
            require(
                checkpoint_path.read_bytes() == source_bytes,
                "source changed during aggregate preflight",
            )
            require(
                catalogs.discover_source_paths(root) == universe,
                "catalog source universe changed during aggregate preflight",
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
                "live managed snapshot differs",
            )
            npc._require_git_visible_changes_are_managed(root, final)

        revalidate_preflight_closure()
        npc.validate_projected_with_both_checkers(root, projected_bytes)
        revalidate_preflight_closure()
    physical_final = dict(final)
    return Prepared(
        root=root,
        checkpoint_path=checkpoint_path,
        source_bytes=source_bytes,
        projected=projected,
        projected_bytes=projected_bytes,
        final_sha256_by_path=final,
        physical_sha256_by_path=physical_final,
        source_universe=universe,
        candidate_catalogs=candidate,
        catalogs_verified=not allow_stale_catalogs,
    )


def write_catalogs(prepared: Prepared) -> None:
    require(not prepared.catalogs_verified, "catalog refresh requires a stale projection")
    def guard() -> None:
        require(
            prepared.checkpoint_path.read_bytes() == prepared.source_bytes,
            "source changed during catalog refresh",
        )
        require(
            catalogs.discover_source_paths(prepared.root) == prepared.source_universe,
            "catalog source universe changed during refresh",
        )
        require(
            npc._build_candidate_catalog_bytes(
                prepared.root, prepared.source_universe, prepared.projected
            )
            == prepared.candidate_catalogs,
            "catalog projection changed during refresh",
        )

    guard()
    for relative in CATALOG_PATHS:
        target = npc._safe_file(prepared.root, relative)
        source = target.read_bytes()
        wanted = prepared.candidate_catalogs[relative]
        if source == wanted:
            continue
        npc.atomic_write(
            target,
            wanted,
            expected_source=source,
            commit_guard=guard,
        )
    guard()


def write_checkpoint(prepared: Prepared) -> None:
    refreshed = prepare(prepared.root)
    require(
        refreshed.source_bytes == prepared.source_bytes
        and refreshed.projected_bytes == prepared.projected_bytes
        and refreshed.candidate_catalogs == prepared.candidate_catalogs,
        "aggregate projection changed before write",
    )
    require(
        refreshed.final_sha256_by_path == prepared.final_sha256_by_path
        and refreshed.physical_sha256_by_path == prepared.physical_sha256_by_path
        and refreshed.source_universe == prepared.source_universe,
        "aggregate physical or source-universe authority changed before write",
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
                        sorted(path.as_posix() for path in prepared.final_sha256_by_path),
                    )
                    == npc._snapshot_hashes_from_digests(
                        prepared.final_sha256_by_path
                    ),
                    "aggregate managed closure changed at commit",
                )
                npc._require_git_visible_changes_are_managed(
                    prepared.root,
                    prepared.final_sha256_by_path,
                    changed_path_loader=changed_path_loader,
                )

            verify_closure()
            npc.validate_projected_with_both_checkers(
                prepared.root, prepared.projected_bytes
            )
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
        print(f"WalkSafe Workstream aggregate seq63-65: POSTCOMMIT_UNCERTAIN: {exc}")
        return 1
    except (
        OSError,
        ValueError,
        TypeError,
        npc.BuildError,
        npc.CompletionApplyError,
        catalogs.CatalogError,
    ) as exc:
        print(f"WalkSafe Workstream aggregate seq63-65: FAIL: {exc}")
        return 1
    print("WalkSafe Workstream aggregate seq63-65: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
