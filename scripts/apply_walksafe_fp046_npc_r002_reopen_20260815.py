#!/usr/bin/env python3
"""Preflight the FP-046/NPC R002 reopen transaction without applying it.

This is deliberately not a checkpoint writer.  It reads the frozen seq71/R028
state plus a caller-provided R029 successor from a fixture root, builds the
five required transition records and two R002 Goal documents in memory, and
then replays the result fail-closed.  ``--apply`` is reserved for the separate
atomic transaction tool and always refuses here.  The returned ``final_state``
is an in-memory projection, not a canonical transaction or apply payload.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timedelta
import importlib
import json
from pathlib import Path
import stat
import sys

sys.dont_write_bytecode = True

import tomllib
from typing import Any, Mapping, Sequence


def _load_io_base() -> Any:
    try:
        from scripts import (
            build_walksafe_fp008_admin_review_delivery_trace_20260803 as module,
        )

        return module
    except (ImportError, ModuleNotFoundError):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(
            "scripts.build_walksafe_fp008_admin_review_delivery_trace_20260803"
        )


io_base = _load_io_base()
BuildError = io_base.BuildError
require = io_base.require
bytes_sha256 = io_base.bytes_sha256
object_sha256 = io_base.object_sha256
json_text = io_base.json_text
strict_json_bytes = io_base.strict_json_bytes
verify_seal = io_base.verify_seal


def _load_r029_candidate_builder() -> Any:
    try:
        from scripts import (
            build_walksafe_fp046_gap_backlog_r029_candidate_20260815 as module,
        )

        return module
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(
            "scripts.build_walksafe_fp046_gap_backlog_r029_candidate_20260815"
        )


r029_candidate = _load_r029_candidate_builder()


def _load_r029_canonical_bridge() -> Any:
    try:
        from scripts import (
            build_walksafe_fp046_gap_backlog_r029_20260815 as module,
        )

        return module
    except (ImportError, ModuleNotFoundError):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(
            "scripts.build_walksafe_fp046_gap_backlog_r029_20260815"
        )


r029_bridge = _load_r029_canonical_bridge()


def _load_goal_graph() -> Any:
    try:
        from scripts import check_walksafe_goal_graph_v2_4 as module

        return module
    except (ImportError, ModuleNotFoundError):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module("scripts.check_walksafe_goal_graph_v2_4")


goal_graph = _load_goal_graph()

ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")

R028_GAP_JSON_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260814-r028.json"
)
R028_GAP_MD_REL = R028_GAP_JSON_REL.with_suffix(".md")
R028_BACKLOG_JSON_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260814-r028.json"
)
R028_BACKLOG_MD_REL = R028_BACKLOG_JSON_REL.with_suffix(".md")
R028_PATHS = (
    R028_GAP_JSON_REL,
    R028_GAP_MD_REL,
    R028_BACKLOG_JSON_REL,
    R028_BACKLOG_MD_REL,
)
IMMUTABLE_R028_BINDINGS = {
    R028_GAP_JSON_REL: {
        "sha256": "f7be788ed4bcde8440d2539dceed9105421019f38ec1a4106648a5fab7120bab",
        "byte_length": 532549,
    },
    R028_GAP_MD_REL: {
        "sha256": "dbec9a7564c76cf28e559dcb3309834a7410dfb002fe0bf4782373788f8dd173",
        "byte_length": 547,
    },
    R028_BACKLOG_JSON_REL: {
        "sha256": "acf975cffcdec26906099236567f825a616226bfb030ef70d20bb7a21bcfbe55",
        "byte_length": 66305,
    },
    R028_BACKLOG_MD_REL: {
        "sha256": "3ae60a068ceaf2ec69a600b122f662a1e69dfd00cdfb5dd05fb773e58a773914",
        "byte_length": 332,
    },
}

R029_GAP_JSON_REL = r029_candidate.R029_GAP_JSON_REL
R029_GAP_MD_REL = r029_candidate.R029_GAP_MD_REL
R029_BACKLOG_JSON_REL = r029_candidate.R029_BACKLOG_JSON_REL
R029_BACKLOG_MD_REL = r029_candidate.R029_BACKLOG_MD_REL
DISCOVERY_JSON_REL = r029_candidate.DISCOVERY_JSON_REL
DISCOVERY_MD_REL = r029_candidate.DISCOVERY_MD_REL
R029_PATHS = {
    "gap_json": R029_GAP_JSON_REL,
    "gap_md": R029_GAP_MD_REL,
    "backlog_json": R029_BACKLOG_JSON_REL,
    "backlog_md": R029_BACKLOG_MD_REL,
    "discovery_json": DISCOVERY_JSON_REL,
    "discovery_md": DISCOVERY_MD_REL,
}

FP046_R001 = "WS-GOAL-EPIC-03-FP-046-R001"
FP046_R002 = "WS-GOAL-EPIC-03-FP-046-R002"
NPC_R001 = "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
NPC_R002 = "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R002"
EPIC03 = "WS-GOAL-EPIC-03"
EPIC04 = "WS-GOAL-EPIC-04"
FP008_R001 = "WS-GOAL-EPIC-03-FP-008-R001"
FP022_R001 = "WS-GOAL-EPIC-04-FP-022-R001"

FP046_R001_REL = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp046-consent-withdrawal-deletion-r001.md"
)
FP046_R002_REL = FP046_R001_REL.with_name(
    "epic-03-fp046-consent-withdrawal-deletion-r002.md"
)
NPC_R001_REL = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-npc-single-admin-recovery-r001.md"
)
NPC_R002_REL = NPC_R001_REL.with_name("epic-03-npc-single-admin-recovery-r002.md")
EPIC03_REL = Path(
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
FP022_R001_REL = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-04/"
    "epic-04-fp022-tmap-destination-route-r001.md"
)
FP046_GAP_ID = "GAP-055"
FP046_POLICY_ID = "FP-046"
NPC_POLICY_ID = "NPC-SINGLE-ADMIN-RECOVERY"
R002_REVISIONS = (
    (
        FP046_R001,
        FP046_R002,
        FP046_R002_REL,
        "FP-046 R002",
        {
            "source_policy_ids": [FP046_POLICY_ID],
            "gap_ids": [FP046_GAP_ID],
            "start_requires": [FP008_R001],
            "completion_requires": [FP008_R001],
        },
    ),
    (
        NPC_R001,
        NPC_R002,
        NPC_R002_REL,
        "NPC R002",
        {
            "source_policy_ids": [NPC_POLICY_ID],
            "gap_ids": ["GAP-008"],
            "start_requires": [FP046_R002],
            "completion_requires": [FP046_R002],
        },
    ),
)
R002_MUTABLE_FIELDS = {
    "goal_id", "start_requires", "completion_requires", "materialized_from_path",
    "materialized_from_document_id", "materialized_from_sha256", "predecessor_goal_id",
    "predecessor_goal_content_sha256", "supersedes_goal_id", "supersedes_goal_content_sha256",
    "reopen_reason", "reopen_evidence_refs",
}

SOURCE_SEQUENCE = 71
SOURCE_UPDATE_SEQUENCE = 70
REOPEN_SEQUENCES = (72, 73, 74, 75, 76)
REOPEN_EVENT_TYPES = (
    "CANONICAL_BINDINGS_UPDATED",
    "GOAL_SUPERSEDED",
    "GOAL_SUPERSEDED",
    "GOAL_READY",
    "GOAL_READY",
)
REOPEN_EVENT_IDS = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP046-R002-20260815-001",
    "WS-GOAL-GRAPH-V2-4-GOAL-SUPERSEDED-FP046-R002-20260815-001",
    "WS-GOAL-GRAPH-V2-4-GOAL-SUPERSEDED-NPC-SINGLE-ADMIN-RECOVERY-R002-20260815-001",
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-EPIC03-REOPEN-20260815-001",
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP046-R002-20260815-001",
)
R002_REOPEN_EVIDENCE_REFS: list[str] = []
R002_REOPEN_REASON = "CANONICAL_INPUT_CHANGED"

def _read(root: Path, relative: Path, label: str) -> bytes:
    require(not relative.is_absolute() and ".." not in relative.parts, f"unsafe {label} path")
    target = root / relative
    try:
        return target.read_bytes()
    except FileNotFoundError as exc:
        raise BuildError(f"required input missing: {relative}") from exc


def _parse_time(value: Any, label: str) -> datetime:
    require(type(value) is str and bool(value), f"{label} is missing")
    try:
        result = datetime.fromisoformat(value)
    except ValueError as exc:
        raise BuildError(f"{label} is not ISO-8601") from exc
    require(result.tzinfo is not None and result.isoformat() == value, f"{label} is not canonical ISO-8601")
    return result


def _binding(path: Path, raw: bytes, *, role: str | None = None, document_id: str | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {
        "path": path.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
    }
    if role is not None:
        result["role"] = role
    if document_id is not None:
        result["document_id"] = document_id
    return result


def _canonical_binding(path: Path, raw: bytes, role: str, document_id: str) -> dict[str, str]:
    return {
        "role": role,
        "document_id": document_id,
        "path": path.as_posix(),
        "file_sha256": bytes_sha256(raw),
    }


def _seal_event(event: Mapping[str, Any]) -> dict[str, Any]:
    result = deepcopy(dict(event))
    result.pop("event_sha256", None)
    result["event_sha256"] = object_sha256(result)
    return result


def _validate_event_seal(event: Mapping[str, Any], label: str) -> None:
    candidate = dict(event)
    digest = candidate.pop("event_sha256", None)
    require(
        type(digest) is str and digest == object_sha256(candidate),
        f"{label} event seal differs",
    )


def _goal_parts(raw: bytes, label: str) -> tuple[dict[str, Any], str]:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BuildError(f"{label} is not UTF-8") from exc
    require(text.startswith("+++\n"), f"{label} TOML frontmatter is missing")
    try:
        frontmatter, body = text[4:].split("+++\n", 1)
    except ValueError as exc:
        raise BuildError(f"{label} TOML frontmatter is unterminated") from exc
    try:
        metadata = tomllib.loads(frontmatter)
    except tomllib.TOMLDecodeError as exc:
        raise BuildError(f"{label} TOML frontmatter is invalid") from exc
    return metadata, body


def _toml_value(value: Any) -> str:
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return "[" + ", ".join(_toml_value(item) for item in value) + "]"
    raise BuildError("unsupported generated TOML value")


def _rewrite_goal(raw: bytes, replacements: Mapping[str, Any], label: str) -> str:
    metadata, body = _goal_parts(raw, label)
    frontmatter = raw.decode("utf-8")[4:].split("+++\n", 1)[0]
    lines = frontmatter.splitlines()
    remaining = dict(replacements)
    rewritten: list[str] = []
    for line in lines:
        key = line.split(" = ", 1)[0]
        if key in remaining:
            rewritten.append(f"{key} = {_toml_value(remaining.pop(key))}")
        else:
            rewritten.append(line)
    require(not remaining, f"{label} missing rewrite keys: {sorted(remaining)}")
    text = "+++\n" + "\n".join(rewritten) + "\n+++\n" + body
    parsed, parsed_body = _goal_parts(text.encode(), label)
    require(parsed_body == body and parsed.get("goal_id") != metadata.get("goal_id"), f"{label} rewrite differs")
    return text


def _goal_inventory_record(
    goal: Mapping[str, Any], path: Path, raw: bytes, materialized_event_sha256: str
) -> dict[str, Any]:
    return {
        "goal_id": goal["goal_id"],
        "goal_kind": goal["goal_kind"],
        "initial_status": goal["initial_status"],
        "parent_goal_id": goal["parent_goal_id"],
        "path": path.as_posix(),
        "sha256": bytes_sha256(raw),
        "work_item_type": goal["work_item_type"],
        "materialized_from_role": goal["materialized_from_role"],
        "materialized_from_path": goal["materialized_from_path"],
        "materialized_from_document_id": goal["materialized_from_document_id"],
        "materialized_from_sha256": goal["materialized_from_sha256"],
        "predecessor_goal_id": goal["predecessor_goal_id"],
        "predecessor_goal_content_sha256": goal["predecessor_goal_content_sha256"],
        "supersedes_goal_id": goal["supersedes_goal_id"],
        "supersedes_goal_content_sha256": goal["supersedes_goal_content_sha256"],
        "artifact_work_reason": goal["artifact_work_reason"],
        "artifact_trigger_evidence_refs": goal["artifact_trigger_evidence_refs"],
        "materialized_event_sha256": materialized_event_sha256,
    }


def _source_completion_event(history: Sequence[Mapping[str, Any]], goal_id: str) -> Mapping[str, Any]:
    matches = [
        event
        for event in history
        if event.get("event_type") == "GOAL_COMPLETED"
        and event.get("subject_goal_id") == goal_id
    ]
    require(len(matches) == 1, f"source completion event differs: {goal_id}")
    return matches[0]


def _load_source(root: Path) -> dict[str, Any]:
    checkpoint_raw = _read(root, CHECKPOINT_REL, "checkpoint")
    checkpoint = strict_json_bytes(checkpoint_raw, "checkpoint")
    checkpoint_schema_version = checkpoint.get("schema_version")
    require(
        type(checkpoint_schema_version) is str and checkpoint_schema_version,
        "checkpoint schema version is missing",
    )
    state = checkpoint.get("goal_execution")
    require(type(state) is dict, "checkpoint goal_execution is missing")
    history = state.get("transition_history")
    require(type(history) is list and len(history) == SOURCE_SEQUENCE, "source history is not exact seq71")
    update, completion = history[SOURCE_UPDATE_SEQUENCE - 1 : SOURCE_SEQUENCE]
    require(type(update) is dict and type(completion) is dict, "source seq70/71 is malformed")
    _validate_event_seal(update, "source seq70")
    _validate_event_seal(completion, "source seq71")
    require(
        update.get("sequence") == SOURCE_UPDATE_SEQUENCE
        and update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and update.get("event_id")
        == "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP022-20260814-001"
        and completion.get("sequence") == SOURCE_SEQUENCE
        and completion.get("event_type") == "GOAL_COMPLETED"
        and completion.get("event_id")
        == "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP022-20260814-001"
        and completion.get("subject_goal_id") == FP022_R001
        and completion.get("previous_event_sha256") == update.get("event_sha256"),
        "source seq70/71 identity or chain differs",
    )
    require(
        update.get("source_checkpoint_version") == checkpoint_schema_version
        and completion.get("source_checkpoint_version") == checkpoint_schema_version,
        "source checkpoint version binding differs",
    )
    require(
        state.get("status_by_goal", {}).get(FP046_R001) == "COMPLETE_AT_TARGET"
        and state.get("status_by_goal", {}).get(NPC_R001) == "COMPLETE_AT_TARGET"
        and state.get("status_by_goal", {}).get(EPIC03) == "COMPLETE_AT_TARGET"
        and state.get("focus_goal_id") == EPIC04
        and state.get("focus_work_item_id") == ""
        and completion.get("subject_goal_id") == FP022_R001
        and completion.get("previous_focus_goal_id") == FP022_R001,
        "source seq71 status or focus differs",
    )
    r028_raw = {path: _read(root, path, "immutable R028") for path in R028_PATHS}
    for path, expected in IMMUTABLE_R028_BINDINGS.items():
        raw = r028_raw[path]
        require(
            bytes_sha256(raw) == expected["sha256"] and len(raw) == expected["byte_length"],
            f"immutable R028 bytes differ: {path}",
        )
    gap = strict_json_bytes(r028_raw[R028_GAP_JSON_REL], "R028 gap")
    backlog = strict_json_bytes(r028_raw[R028_BACKLOG_JSON_REL], "R028 backlog")
    require(
        r028_raw[R028_GAP_JSON_REL] == json_text(gap).encode()
        and r028_raw[R028_BACKLOG_JSON_REL] == json_text(backlog).encode(),
        "R028 JSON is noncanonical",
    )
    verify_seal(gap, "report_content_sha256", "R028 gap")
    verify_seal(backlog, "backlog_content_sha256", "R028 backlog")
    require(
        gap.get("metadata", {}).get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260814-028"
        and backlog.get("metadata", {}).get("backlog_id")
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260814-028",
        "R028 identity differs",
    )
    snapshots = (update.get("canonical_binding_snapshot_after"), completion.get("canonical_binding_snapshot_after"))
    require(all(isinstance(snapshot, dict) for snapshot in snapshots), "source canonical snapshot is missing")
    for snapshot in snapshots:
        require(
            snapshot["IMPLEMENTATION_GAP"]
            == _canonical_binding(
                R028_GAP_JSON_REL,
                r028_raw[R028_GAP_JSON_REL],
                "IMPLEMENTATION_GAP",
                "WS-IMPLEMENTATION-GAP-ANALYSIS-20260814-028",
            )
            and snapshot["IMPLEMENTATION_BACKLOG"]
            == _canonical_binding(
                R028_BACKLOG_JSON_REL,
                r028_raw[R028_BACKLOG_JSON_REL],
                "IMPLEMENTATION_BACKLOG",
                "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260814-028",
            ),
            "source R028 canonical binding differs",
        )
    assessments = gap.get("assessments")
    actions = backlog.get("next_action_sequence")
    require(type(assessments) is list and type(actions) is list, "R028 assessment/action inventory is missing")
    fp046 = [row for row in assessments if row.get("gap_id") == FP046_GAP_ID]
    fp046_action = [row for row in actions if row.get("source_policy_id") == FP046_POLICY_ID]
    npc_action = [row for row in actions if row.get("source_policy_id") == NPC_POLICY_ID]
    require(
        len(fp046) == 1
        and fp046[0].get("source_policy_id") == FP046_POLICY_ID
        and fp046[0].get("status") == "PARTIAL"
        and len(fp046_action) == 1
        and fp046_action[0].get("status") == "PARTIAL"
        and len(npc_action) == 1
        and npc_action[0].get("status") == "PARTIAL",
        "R028 FP-046/NPC corrective baseline differs",
    )
    source_goal_raw = {
        FP046_R001: _read(root, FP046_R001_REL, "FP-046 R001 Goal"),
        NPC_R001: _read(root, NPC_R001_REL, "NPC R001 Goal"),
        FP022_R001: _read(root, FP022_R001_REL, "current execution leaf Goal"),
        EPIC03: _read(root, EPIC03_REL, "EPIC-03 Goal"),
    }
    goals = {goal_id: _goal_parts(raw, goal_id)[0] for goal_id, raw in source_goal_raw.items()}
    leaf_sha256 = bytes_sha256(source_goal_raw[FP022_R001])
    inventory = state.get("dynamic_goal_inventory")
    require(type(inventory) is dict, "source dynamic inventory is missing")
    for goal_id, path in ((FP046_R001, FP046_R001_REL), (NPC_R001, NPC_R001_REL)):
        record = inventory.get(goal_id)
        require(
            type(record) is dict
            and record.get("path") == path.as_posix()
            and record.get("sha256") == bytes_sha256(source_goal_raw[goal_id]),
            f"source inventory binding differs: {goal_id}",
        )
    require(
        goals[FP046_R001].get("goal_id") == FP046_R001
        and goals[FP046_R001].get("start_requires") == [FP008_R001]
        and goals[FP046_R001].get("completion_requires") == [FP008_R001]
        and goals[NPC_R001].get("goal_id") == NPC_R001
        and goals[NPC_R001].get("start_requires") == [FP046_R001]
        and goals[NPC_R001].get("completion_requires") == [FP046_R001]
        and goals[FP022_R001].get("goal_id") == FP022_R001
        and goals[FP022_R001].get("goal_kind") == "WORK_ITEM"
        and completion.get("previous_focus_content_sha256") == leaf_sha256
        and goals[EPIC03].get("goal_id") == EPIC03,
        "source Goal semantics or last completed leaf binding differs",
    )
    completion_evidence = state.get("completion_evidence_by_goal")
    children = state.get("materialized_child_goal_ids_by_parent")
    archived = state.get("archived_completion_evidence_by_goal")
    require(
        type(completion_evidence) is dict
        and type(children) is dict
        and archived == {}
        and state.get("status_by_goal", {}).get(FP022_R001) == "COMPLETE_AT_TARGET"
        and bool(completion_evidence.get(FP022_R001))
        and all(
            bool(completion_evidence.get(goal_id))
            for goal_id in (EPIC03, FP046_R001, NPC_R001)
        )
        and children.get(EPIC03)
        == [
            "WS-GOAL-EPIC-03-FP-047-R001",
            "WS-GOAL-EPIC-03-FP-048-R001",
            FP008_R001,
            FP046_R001,
            NPC_R001,
        ],
        "source completion/archive/child state differs",
    )
    return {
        "checkpoint_raw": checkpoint_raw,
        "checkpoint": checkpoint,
        "state": state,
        "history": history,
        "update": update,
        "completion": completion,
        "r028_raw": r028_raw,
        "gap": gap,
        "backlog": backlog,
        "source_goal_raw": source_goal_raw,
        "goals": goals,
        "completion_events": {
            FP046_R001: _source_completion_event(history, FP046_R001),
            NPC_R001: _source_completion_event(history, NPC_R001),
            EPIC03: _source_completion_event(history, EPIC03),
            FP008_R001: _source_completion_event(history, FP008_R001),
        },
        "last_completed_execution_leaf": {
            "goal_id": FP022_R001,
            "path": FP022_R001_REL.as_posix(),
            "sha256": leaf_sha256,
            "completion_event_sha256": completion["event_sha256"],
            "completion_evidence_refs": deepcopy(completion_evidence[FP022_R001]),
            "current_focus_goal_id": EPIC04,
            "current_focus_is_workstream": True,
            "current_focus_work_item_id": "",
        },
    }


def _load_r029_raw(
    root: Path,
    paths: Mapping[str, Path],
    raw: Mapping[str, bytes],
) -> dict[str, Any]:
    """Validate R029 bytes supplied by either the candidate or canonical bridge.

    The candidate builder remains the frozen source of truth.  The canonical
    bridge may replace only the publishable Gap/Backlog paths with byte-exact
    copies; discovery stays a candidate-only evidence record.
    """

    require(set(paths) == set(R029_PATHS), "R029 path inventory differs")
    require(set(raw) == set(R029_PATHS), "R029 byte inventory differs")
    expected_outputs = r029_candidate.build_outputs(root)
    expected_by_name = {
        name: expected_outputs[path].encode() for name, path in R029_PATHS.items()
    }
    require(dict(raw) == expected_by_name, "R029 bytes differ from builder output")
    gap = strict_json_bytes(raw["gap_json"], "R029 gap")
    backlog = strict_json_bytes(raw["backlog_json"], "R029 backlog")
    discovery = strict_json_bytes(raw["discovery_json"], "R029 discovery")
    require(
        raw["gap_json"] == json_text(gap).encode()
        and raw["backlog_json"] == json_text(backlog).encode()
        and raw["discovery_json"] == json_text(discovery).encode(),
        "R029 JSON is noncanonical",
    )
    verify_seal(gap, "report_content_sha256", "R029 gap")
    verify_seal(backlog, "backlog_content_sha256", "R029 backlog")
    verify_seal(discovery, "discovery_content_sha256", "R029 discovery")
    require(
        gap.get("metadata", {}).get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260815-029"
        and gap.get("metadata", {}).get("status")
        == "DRAFT_DIAGNOSTIC_COMPLETE"
        and backlog.get("metadata", {}).get("backlog_id")
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260815-029"
        and backlog.get("metadata", {}).get("status")
        == "DRAFT_DIAGNOSTIC_COMPLETE"
        and {
            "canonical_application_status",
            "approval_status",
            "operational_application_boundary",
        }.isdisjoint(gap)
        and {
            "canonical_application_status",
            "approval_status",
            "operational_application_boundary",
        }.isdisjoint(backlog)
        and discovery.get("candidate_status")
        == "DISCOVERED_NOT_CANONICALLY_APPLIED"
        and discovery.get("canonical_application_status") == "NOT_APPLIED"
        and discovery.get("approval_status") == "NOT_REQUESTED"
        and discovery.get("operational_application_boundary")
        == r029_candidate.operational_application_boundary(),
        "R029 candidate identity or application boundary differs",
    )
    prepared_at = _parse_time(gap["metadata"].get("prepared_at"), "R029 gap prepared_at")
    require(
        backlog.get("metadata", {}).get("prepared_at") == gap["metadata"].get("prepared_at"),
        "R029 prepared_at differs",
    )
    assessments = gap.get("assessments")
    actions = backlog.get("next_action_sequence")
    epics = backlog.get("epics")
    require(
        type(assessments) is list and type(actions) is list and type(epics) is list,
        "R029 candidate corrective inventory is missing",
    )
    fp046_gaps = [row for row in assessments if row.get("gap_id") == FP046_GAP_ID]
    fp046_actions = [
        row for row in actions if row.get("source_policy_id") == FP046_POLICY_ID
    ]
    epic03_rows = [row for row in epics if row.get("epic_id") == "EPIC-03"]
    proposed_next_action = discovery.get("proposed_next_single_action")
    require(
        len(fp046_gaps) == len(fp046_actions) == len(epic03_rows) == 1
        and fp046_gaps[0].get("regression_reopen", {}).get("reopen_required")
        is True
        and fp046_actions[0].get("status") == "REOPEN_REQUIRED"
        and epic03_rows[0].get("current_status") == "PLANNED"
        and backlog.get("source_predecessor")
        == {
            "path": R028_BACKLOG_JSON_REL.as_posix(),
            "file_sha256": IMMUTABLE_R028_BINDINGS[R028_BACKLOG_JSON_REL][
                "sha256"
            ],
            "preserved_unchanged": True,
        }
        and isinstance(proposed_next_action, dict)
        and set(proposed_next_action)
        == {
            "epic_id",
            "source_policy_id",
            "gap_id",
            "priority_rank",
            "status",
            "work_item_id",
            "action",
        }
        and proposed_next_action.get("epic_id") == "EPIC-03"
        and proposed_next_action.get("source_policy_id") == FP046_POLICY_ID
        and proposed_next_action.get("gap_id") == FP046_GAP_ID
        and proposed_next_action.get("priority_rank") == 21
        and proposed_next_action.get("status") == "REOPEN_REQUIRED"
        and proposed_next_action.get("work_item_id") == FP046_R002
        and type(proposed_next_action.get("action")) is str
        and bool(proposed_next_action["action"]),
        "R029 candidate corrective routing differs",
    )
    return {
        "raw": raw,
        "gap": gap,
        "backlog": backlog,
        "discovery": discovery,
        "prepared_at": prepared_at,
    }


def _load_r029(root: Path, paths: Mapping[str, Path]) -> dict[str, Any]:
    """Load the historical candidate-path form used by legacy preflight tests."""

    require(paths == R029_PATHS, "R029 candidate paths differ from the approved builder")
    raw = {name: _read(root, path, f"R029 {name}") for name, path in paths.items()}
    return _load_r029_raw(root, paths, raw)


def _r029_paths(**overrides: Path) -> dict[str, Path]:
    return {**R029_PATHS, **overrides}


def _build_r002_documents(source: Mapping[str, Any], candidate: Mapping[str, Any], paths: Mapping[str, Path]) -> dict[Path, str]:
    backlog_raw = candidate["raw"]["backlog_json"]
    backlog = candidate["backlog"]
    common = {
        "materialized_from_path": paths["backlog_json"].as_posix(),
        "materialized_from_document_id": backlog["metadata"]["backlog_id"],
        "materialized_from_sha256": bytes_sha256(backlog_raw),
        "predecessor_goal_id": source["last_completed_execution_leaf"]["goal_id"],
        "predecessor_goal_content_sha256": source["last_completed_execution_leaf"]["sha256"],
        "reopen_evidence_refs": R002_REOPEN_EVIDENCE_REFS,
    }
    return {
        path: _rewrite_goal(
            source["source_goal_raw"][old_goal],
            {
                **common,
                "goal_id": goal_id,
                "start_requires": semantics["start_requires"],
                "completion_requires": semantics["completion_requires"],
                "supersedes_goal_id": old_goal,
                "supersedes_goal_content_sha256": bytes_sha256(
                    source["source_goal_raw"][old_goal]
                ),
                "reopen_reason": R002_REOPEN_REASON,
            },
            label,
        )
        for old_goal, goal_id, path, label, semantics in R002_REVISIONS
    }


def _validate_r002_documents(
    documents: Mapping[Path, str], source: Mapping[str, Any], candidate: Mapping[str, Any], paths: Mapping[str, Path]
) -> tuple[dict[str, Any], dict[str, bytes]]:
    require(
        set(documents) == {spec[2] for spec in R002_REVISIONS},
        "R002 document inventory differs",
    )
    raw = {path: text.encode() for path, text in documents.items()}
    common = {
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": paths["backlog_json"].as_posix(),
        "materialized_from_document_id": candidate["backlog"]["metadata"]["backlog_id"],
        "materialized_from_sha256": bytes_sha256(candidate["raw"]["backlog_json"]),
        "predecessor_goal_id": source["last_completed_execution_leaf"]["goal_id"],
        "predecessor_goal_content_sha256": source["last_completed_execution_leaf"]["sha256"],
        "initial_status": "PLANNED",
        "parent_goal_id": EPIC03,
        "goal_kind": "WORK_ITEM",
        "work_item_type": "POLICY_GAP_WORK",
        "reopen_evidence_refs": R002_REOPEN_EVIDENCE_REFS,
    }
    goals: dict[str, Any] = {}
    goal_raw: dict[str, bytes] = {}
    for old_goal, goal_id, path, label, semantics in R002_REVISIONS:
        goal, body = _goal_parts(raw[path], label)
        old, old_body = _goal_parts(source["source_goal_raw"][old_goal], old_goal)
        require(
            body == old_body,
            f"R002 Goal body changed: {goal_id}",
        )
        require(
            all(goal.get(key) == value for key, value in common.items())
            and goal.get("goal_id") == goal_id
            and all(goal.get(key) == value for key, value in semantics.items())
            and goal.get("supersedes_goal_id") == old_goal
            and goal.get("supersedes_goal_content_sha256")
            == bytes_sha256(source["source_goal_raw"][old_goal])
            and goal.get("reopen_reason") == R002_REOPEN_REASON,
            f"R002 semantic invariant differs: {goal_id}",
        )
        require(
            {key: value for key, value in goal.items() if key not in R002_MUTABLE_FIELDS}
            == {key: value for key, value in old.items() if key not in R002_MUTABLE_FIELDS},
            "R002 changes unrelated Goal semantics",
        )
        goals[goal_id] = goal
        goal_raw[goal_id] = raw[path]
    return goals, goal_raw


def _runtime(
    source_runtime: Mapping[str, Any],
    *,
    focus_id: str,
    focus_path: Path,
    focus_source: str,
    focus_work_item_id: str,
    ready: Sequence[str],
    artifact_work_queue: Mapping[str, Any],
    completion_boundary: Mapping[str, Any],
) -> dict[str, Any]:
    runtime = deepcopy(dict(source_runtime))
    runtime.update(
        {
            "focus_goal_id": focus_id,
            "focus_goal_path": focus_path.as_posix(),
            "focus_source": focus_source,
            "focus_work_item_id": focus_work_item_id,
            "ready_frontier_goal_ids": list(ready),
            "artifact_work_queue_sha256": goal_graph.continuation.canonical_json_sha256(
                dict(artifact_work_queue)
            ),
            "completion_boundary_sha256": goal_graph.continuation.canonical_json_sha256(
                dict(completion_boundary)
            ),
        }
    )
    return runtime


def _derive_queue_and_boundary(
    root: Path,
    checkpoint: Mapping[str, Any],
    nodes: Mapping[str, Mapping[str, Any]],
    statuses: Mapping[str, str],
    ready: Sequence[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    state = checkpoint.get("goal_execution")
    require(isinstance(state, dict), "runtime projection state is missing")
    bindings = goal_graph.frozen_goal.canonical_binding_map(dict(checkpoint))
    register_binding = bindings.get("ARTIFACT_REGISTER")
    require(
        isinstance(register_binding, dict),
        "runtime projection ARTIFACT_REGISTER binding is missing",
    )
    register_path = goal_graph.continuation.resolve_repo_file(
        root, register_binding.get("path")
    )
    require(
        register_path is not None,
        "runtime projection ARTIFACT_REGISTER path is missing or unsafe",
    )
    register = goal_graph.continuation.load_json(register_path)
    queue_errors, queue = goal_graph.derive_v24_artifact_work_queue_from_register(
        register_binding,
        register,
        dict(nodes),
        dict(statuses),
    )
    require(
        not queue_errors,
        "runtime artifact queue derivation failed: " + "; ".join(queue_errors),
    )
    boundary_errors, boundary = goal_graph.frozen_goal.derive_completion_boundary(
        dict(nodes),
        dict(statuses),
        list(ready),
        state["blockers_by_goal"],
        queue,
        package_status=state["package_status"],
    )
    require(
        not boundary_errors,
        "runtime completion boundary derivation failed: "
        + "; ".join(boundary_errors),
    )
    return queue, boundary


def _event_common(
    *, sequence: int, event_id: str, event_type: str, occurred_at: datetime, previous: Mapping[str, Any], focus_goal_id: str, focus_goal_sha256: str, runtime_after: Mapping[str, Any], static_plan_manifest_sha256: str, source_checkpoint_version: str
) -> dict[str, Any]:
    return {
        "sequence": sequence,
        "event_id": event_id,
        "event_type": event_type,
        "occurred_on": occurred_at.date().isoformat(),
        "occurred_at": occurred_at.isoformat(),
        "previous_focus_goal_id": previous["focus_goal_id"],
        "previous_focus_content_sha256": previous["focus_goal_content_sha256"],
        "focus_goal_id": focus_goal_id,
        "focus_goal_content_sha256": focus_goal_sha256,
        "static_plan_manifest_sha256": static_plan_manifest_sha256,
        "runtime_after": deepcopy(dict(runtime_after)),
        "blockers_after": {},
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source_checkpoint_version,
        "previous_event_sha256": previous["event_sha256"],
    }


def _build_preflight_unchecked(
    root: Path,
    paths: Mapping[str, Path],
    *,
    r029_raw: Mapping[str, bytes] | None = None,
) -> dict[str, Any]:
    source = _load_source(root)
    candidate = (
        _load_r029(root, paths)
        if r029_raw is None
        else _load_r029_raw(root, paths, r029_raw)
    )
    documents = _build_r002_documents(source, candidate, paths)
    goals, goal_raw = _validate_r002_documents(documents, source, candidate, paths)
    state = source["state"]
    source_runtime = source["completion"]["runtime_after"]
    static_hash = state["static_plan_manifest_sha256"]
    source_version = source["checkpoint"]["schema_version"]
    source_focus_sha = source["completion"]["focus_goal_content_sha256"]
    source_focus_path = Path(source_runtime["focus_goal_path"])
    initial = source["completion"]
    occurred = max(
        _parse_time(source["completion"]["occurred_at"], "source seq71 occurred_at"),
        candidate["prepared_at"],
    ) + timedelta(seconds=1)

    canonical = deepcopy(source["completion"]["canonical_binding_snapshot_after"])
    canonical["IMPLEMENTATION_GAP"] = _canonical_binding(
        paths["gap_json"], candidate["raw"]["gap_json"], "IMPLEMENTATION_GAP", candidate["gap"]["metadata"]["report_id"]
    )
    canonical["IMPLEMENTATION_BACKLOG"] = _canonical_binding(
        paths["backlog_json"], candidate["raw"]["backlog_json"], "IMPLEMENTATION_BACKLOG", candidate["backlog"]["metadata"]["backlog_id"]
    )
    completion_evidence = deepcopy(state["completion_evidence_by_goal"])
    archived = deepcopy(state["archived_completion_evidence_by_goal"])
    inventory = deepcopy(state["dynamic_goal_inventory"])
    children = deepcopy(state["materialized_child_goal_ids_by_parent"])
    statuses = deepcopy(state["status_by_goal"])
    node_errors, nodes = goal_graph.frozen_goal.current_goal_nodes(root, state)
    require(
        not node_errors,
        "runtime Goal node derivation failed: " + "; ".join(node_errors),
    )
    nodes.update(deepcopy(goals))

    archived[EPIC03] = completion_evidence.pop(EPIC03)
    statuses[EPIC03] = "PLANNED"
    source_ready = source_runtime["ready_frontier_goal_ids"]
    queue72, boundary72 = _derive_queue_and_boundary(
        root, source["checkpoint"], nodes, statuses, source_ready
    )
    source_runtime_after = _runtime(
        source_runtime,
        focus_id=EPIC04,
        focus_path=source_focus_path,
        focus_source=source_runtime["focus_source"],
        focus_work_item_id=source_runtime["focus_work_item_id"],
        ready=source_ready,
        artifact_work_queue=queue72,
        completion_boundary=boundary72,
    )
    cbu = _event_common(
        sequence=72,
        event_id=REOPEN_EVENT_IDS[0],
        event_type="CANONICAL_BINDINGS_UPDATED",
        occurred_at=occurred,
        previous=initial,
        focus_goal_id=EPIC04,
        focus_goal_sha256=source_focus_sha,
        runtime_after=source_runtime_after,
        static_plan_manifest_sha256=static_hash,
        source_checkpoint_version=source_version,
    )
    cbu.update(
        {
            "subject_goal_id": EPIC03,
            "from_status": "COMPLETE_AT_TARGET",
            "to_status": "PLANNED",
            "status_changes": {EPIC03: "PLANNED"},
            "evidence_refs": R002_REOPEN_EVIDENCE_REFS,
            "produced_by_goal_id": None,
            "produced_binding_roles": [],
            "producer_completion_receipt_binding": None,
            "changed_binding_roles": ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"],
            "changed_subject_ids_by_role": {
                "IMPLEMENTATION_BACKLOG": [FP046_POLICY_ID],
                "IMPLEMENTATION_GAP": [FP046_POLICY_ID, FP046_GAP_ID],
            },
            "producer_output_subject_ids_by_role": {},
            "impact_closure_goal_ids": [EPIC03, FP046_R001, NPC_R001],
            "impact_disposition_by_goal": {
                FP046_R001: {"result": "REOPEN_REQUIRED"},
                NPC_R001: {"result": "REOPEN_REQUIRED"},
                EPIC03: {"result": "REOPEN_CONTAINER", "target_status": "PLANNED"},
            },
            "reopened_completion_event_sha256_by_goal": {
                EPIC03: source["completion_events"][EPIC03]["event_sha256"]
            },
            "canonical_binding_snapshot_after": canonical,
            "completion_evidence_by_goal_after": deepcopy(completion_evidence),
            "archived_completion_evidence_by_goal_after": deepcopy(archived),
            "source_bindings": {
                "checkpoint": _binding(CHECKPOINT_REL, source["checkpoint_raw"]),
                "source_seq70": {"event_id": source["update"]["event_id"], "sha256": source["update"]["event_sha256"]},
                "source_seq71": {"event_id": source["completion"]["event_id"], "sha256": source["completion"]["event_sha256"]},
                "r028_gap": _binding(R028_GAP_JSON_REL, source["r028_raw"][R028_GAP_JSON_REL]),
                "r028_backlog": _binding(R028_BACKLOG_JSON_REL, source["r028_raw"][R028_BACKLOG_JSON_REL]),
                "r029_gap": _binding(paths["gap_json"], candidate["raw"]["gap_json"]),
                "r029_backlog": _binding(paths["backlog_json"], candidate["raw"]["backlog_json"]),
                "regression_trigger_discovery": _binding(
                    paths["discovery_json"],
                    candidate["raw"]["discovery_json"],
                    role="REGRESSION_TRIGGER_DISCOVERY_CANDIDATE",
                    document_id=candidate["discovery"]["document_id"],
                ),
                "last_completed_execution_leaf": deepcopy(source["last_completed_execution_leaf"]),
            },
        }
    )
    cbu = _seal_event(cbu)

    def supersede(
        *, index: int, old_goal: str, new_goal: str, new_path: Path, prior: Mapping[str, Any]
    ) -> dict[str, Any]:
        nonlocal occurred
        occurred += timedelta(seconds=1)
        archived[old_goal] = completion_evidence.pop(old_goal)
        statuses[old_goal] = "SUPERSEDED"
        statuses[new_goal] = "PLANNED"
        raw = goal_raw[new_goal]
        inventory[new_goal] = _goal_inventory_record(goals[new_goal], new_path, raw, "")
        require(
            old_goal in children[EPIC03] and new_goal not in children[EPIC03],
            f"source child revision binding differs: {old_goal}",
        )
        children[EPIC03] = sorted(set(children[EPIC03]) | {new_goal})
        queue, boundary = _derive_queue_and_boundary(
            root,
            source["checkpoint"],
            nodes,
            statuses,
            source_ready,
        )
        runtime_after = _runtime(
            source_runtime,
            focus_id=EPIC04,
            focus_path=source_focus_path,
            focus_source=source_runtime["focus_source"],
            focus_work_item_id=source_runtime["focus_work_item_id"],
            ready=source_ready,
            artifact_work_queue=queue,
            completion_boundary=boundary,
        )
        event = _event_common(
            sequence=REOPEN_SEQUENCES[index],
            event_id=REOPEN_EVENT_IDS[index],
            event_type="GOAL_SUPERSEDED",
            occurred_at=occurred,
            previous=prior,
            focus_goal_id=EPIC04,
            focus_goal_sha256=source_focus_sha,
            runtime_after=runtime_after,
            static_plan_manifest_sha256=static_hash,
            source_checkpoint_version=source_version,
        )
        event.update(
            {
                "subject_goal_id": old_goal,
                "materialized_goal_id": new_goal,
                "materialized_goal_path": new_path.as_posix(),
                "materialized_goal_content_sha256": bytes_sha256(raw),
                "materialized_from_role": goals[new_goal]["materialized_from_role"],
                "materialized_from_path": goals[new_goal]["materialized_from_path"],
                "materialized_from_document_id": goals[new_goal][
                    "materialized_from_document_id"
                ],
                "materialized_from_sha256": goals[new_goal]["materialized_from_sha256"],
                "artifact_work_reason": goals[new_goal]["artifact_work_reason"],
                "artifact_trigger_evidence_refs": goals[new_goal][
                    "artifact_trigger_evidence_refs"
                ],
                "predecessor_goal_id": source["last_completed_execution_leaf"]["goal_id"],
                "predecessor_goal_content_sha256": bytes_sha256(source["source_goal_raw"][FP022_R001]),
                "supersedes_goal_id": old_goal,
                "supersedes_goal_content_sha256": bytes_sha256(source["source_goal_raw"][old_goal]),
                "from_status": "COMPLETE_AT_TARGET",
                "to_status": "SUPERSEDED",
                "status_changes": {old_goal: "SUPERSEDED", new_goal: "PLANNED"},
                "evidence_refs": R002_REOPEN_EVIDENCE_REFS,
                "canonical_binding_snapshot_after": deepcopy(canonical),
                "completion_evidence_by_goal_after": deepcopy(completion_evidence),
                "archived_completion_evidence_by_goal_after": deepcopy(archived),
                "reopen_trigger": {
                    "canonical_update_event_sha256": cbu["event_sha256"],
                    "target_completion_event_sha256": source["completion_events"][old_goal]["event_sha256"],
                    "target_completion_occurred_at": source["completion_events"][old_goal]["occurred_at"],
                    "decided_at": cbu["occurred_at"],
                },
            }
        )
        sealed = _seal_event(event)
        inventory[new_goal]["materialized_event_sha256"] = sealed["event_sha256"]
        return sealed

    fp046_superseded = supersede(
        index=1,
        old_goal=FP046_R001,
        new_goal=FP046_R002,
        new_path=FP046_R002_REL,
        prior=cbu,
    )
    npc_superseded = supersede(
        index=2,
        old_goal=NPC_R001,
        new_goal=NPC_R002,
        new_path=NPC_R002_REL,
        prior=fp046_superseded,
    )

    occurred += timedelta(seconds=1)
    statuses[EPIC03] = "READY"
    epic03_raw = source["source_goal_raw"][EPIC03]
    epic03_ready_ids = [EPIC03, *source_ready]
    queue75, boundary75 = _derive_queue_and_boundary(
        root, source["checkpoint"], nodes, statuses, epic03_ready_ids
    )
    epic03_ready_runtime = _runtime(
        source_runtime,
        focus_id=EPIC03,
        focus_path=EPIC03_REL,
        focus_source="WORKSTREAM_GRAPH",
        focus_work_item_id="",
        ready=epic03_ready_ids,
        artifact_work_queue=queue75,
        completion_boundary=boundary75,
    )
    epic03_ready = _event_common(
        sequence=75,
        event_id=REOPEN_EVENT_IDS[3],
        event_type="GOAL_READY",
        occurred_at=occurred,
        previous=npc_superseded,
        focus_goal_id=EPIC03,
        focus_goal_sha256=bytes_sha256(epic03_raw),
        runtime_after=epic03_ready_runtime,
        static_plan_manifest_sha256=static_hash,
        source_checkpoint_version=source_version,
    )
    epic03_ready.update(
        {
            "subject_goal_id": EPIC03,
            "from_status": "PLANNED",
            "to_status": "READY",
            "status_changes": {EPIC03: "READY"},
            "evidence_refs": R002_REOPEN_EVIDENCE_REFS,
            "dynamic_goal_inventory_after": deepcopy(inventory),
            "materialized_child_goal_ids_by_parent_after": deepcopy(children),
            "readiness_basis": {
                "mode": "CANONICAL_DEPENDENCY_CLOSURE_REOPEN",
                "canonical_update_event_sha256": cbu["event_sha256"],
                "successor_event_sha256_by_goal": {
                    FP046_R002: fp046_superseded["event_sha256"],
                    NPC_R002: npc_superseded["event_sha256"],
                },
                "archived_completion_event_sha256": source["completion_events"][
                    EPIC03
                ]["event_sha256"],
            },
        }
    )
    epic03_ready = _seal_event(epic03_ready)

    occurred += timedelta(seconds=1)
    statuses[FP046_R002] = "READY"
    fp046_ready_ids = [FP046_R002, EPIC03, *source_ready]
    queue76, boundary76 = _derive_queue_and_boundary(
        root, source["checkpoint"], nodes, statuses, fp046_ready_ids
    )
    fp046_ready_runtime = _runtime(
        source_runtime,
        focus_id=FP046_R002,
        focus_path=FP046_R002_REL,
        focus_source="IMPLEMENTATION_GAP",
        focus_work_item_id=FP046_R002,
        ready=fp046_ready_ids,
        artifact_work_queue=queue76,
        completion_boundary=boundary76,
    )
    fp046_ready = _event_common(
        sequence=76,
        event_id=REOPEN_EVENT_IDS[4],
        event_type="GOAL_READY",
        occurred_at=occurred,
        previous=epic03_ready,
        focus_goal_id=FP046_R002,
        focus_goal_sha256=bytes_sha256(goal_raw[FP046_R002]),
        runtime_after=fp046_ready_runtime,
        static_plan_manifest_sha256=static_hash,
        source_checkpoint_version=source_version,
    )
    fp046_ready.update(
        {
            "subject_goal_id": FP046_R002,
            "from_status": "PLANNED",
            "to_status": "READY",
            "status_changes": {FP046_R002: "READY"},
            "evidence_refs": R002_REOPEN_EVIDENCE_REFS,
            "reopened_container_ready_event_sha256": epic03_ready["event_sha256"],
            "readiness_basis": {
                "dependency_completion_events": [{
                    "goal_id": FP008_R001,
                    "event_sha256": source["completion_events"][FP008_R001]["event_sha256"],
                }],
            },
        }
    )
    fp046_ready = _seal_event(fp046_ready)

    final_status = deepcopy(statuses)
    return {
        "schema_version": "walksafe.fp046-npc-r002-reopen-preflight.v1",
        "transaction_status": "PREFLIGHT_ONLY_NOT_AUTHORIZED",
        "final_state_projection_only": True,
        "required_before_apply": candidate["discovery"]["operational_application_boundary"][
            "required_before_apply"
        ],
        "source_sequence": SOURCE_SEQUENCE,
        "candidate_paths": {name: path.as_posix() for name, path in paths.items()},
        "documents": {path.as_posix(): text for path, text in documents.items()},
        "events": [cbu, fp046_superseded, npc_superseded, epic03_ready, fp046_ready],
        "final_state": {
            "status_by_goal": final_status,
            "completion_evidence_by_goal": completion_evidence,
            "archived_completion_evidence_by_goal": archived,
            "dynamic_goal_inventory": inventory,
            "materialized_child_goal_ids_by_parent": children,
            "ready_frontier_goal_ids": fp046_ready_runtime["ready_frontier_goal_ids"],
            "focus_goal_id": FP046_R002,
            "artifact_work_queue": queue76,
            "completion_boundary": boundary76,
        },
    }


def _validate_replay(plan: Mapping[str, Any], source: Mapping[str, Any]) -> None:
    events = plan.get("events")
    require(type(events) is list and len(events) == 5, "reopen event inventory differs")
    require(
        [event.get("sequence") for event in events] == list(REOPEN_SEQUENCES)
        and [event.get("event_type") for event in events] == list(REOPEN_EVENT_TYPES)
        and [event.get("event_id") for event in events] == list(REOPEN_EVENT_IDS),
        "reopen event order differs",
    )
    previous = source["completion"]
    for index, event in enumerate(events):
        require(type(event) is dict, f"reopen event is malformed: {index}")
        _validate_event_seal(event, f"reopen seq{event.get('sequence')}")
        require(event.get("previous_event_sha256") == previous.get("event_sha256"), "reopen event chain differs")
        require(_parse_time(event.get("occurred_at"), "reopen occurred_at") > _parse_time(previous.get("occurred_at"), "previous occurred_at"), "reopen event time order differs")
        previous = event
    cbu, fp046, npc, epic03_ready, fp046_ready = events
    require(
        cbu.get("status_changes") == {EPIC03: "PLANNED"}
        and cbu.get("from_status") == "COMPLETE_AT_TARGET"
        and cbu.get("to_status") == "PLANNED"
        and cbu.get("changed_binding_roles")
        == ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
        and cbu.get("changed_subject_ids_by_role")
        == {
            "IMPLEMENTATION_BACKLOG": [FP046_POLICY_ID],
            "IMPLEMENTATION_GAP": [FP046_POLICY_ID, FP046_GAP_ID],
        }
        and cbu.get("impact_closure_goal_ids") == [EPIC03, FP046_R001, NPC_R001]
        and cbu.get("impact_disposition_by_goal") == {
            FP046_R001: {"result": "REOPEN_REQUIRED"},
            NPC_R001: {"result": "REOPEN_REQUIRED"},
            EPIC03: {"result": "REOPEN_CONTAINER", "target_status": "PLANNED"},
        }
        and cbu.get("reopened_completion_event_sha256_by_goal") == {
            EPIC03: source["completion_events"][EPIC03]["event_sha256"]
        },
        "canonical reopen closure/disposition differs",
    )
    source_active = deepcopy(source["state"]["completion_evidence_by_goal"])
    cbu_active = deepcopy(source_active)
    cbu_archived: dict[str, Any] = {EPIC03: cbu_active.pop(EPIC03)}
    fp046_active = deepcopy(cbu_active)
    fp046_archived = deepcopy(cbu_archived)
    fp046_archived[FP046_R001] = fp046_active.pop(FP046_R001)
    npc_active = deepcopy(fp046_active)
    npc_archived = deepcopy(fp046_archived)
    npc_archived[NPC_R001] = npc_active.pop(NPC_R001)
    require(
        cbu.get("completion_evidence_by_goal_after") == cbu_active
        and cbu.get("archived_completion_evidence_by_goal_after") == cbu_archived
        and fp046.get("completion_evidence_by_goal_after") == fp046_active
        and fp046.get("archived_completion_evidence_by_goal_after") == fp046_archived
        and npc.get("completion_evidence_by_goal_after") == npc_active
        and npc.get("archived_completion_evidence_by_goal_after") == npc_archived,
        "reopen completion evidence archive projection differs",
    )
    document_texts = plan.get("documents")
    require(type(document_texts) is dict, "R002 documents are missing")
    for event, goal_id, path, predecessor in (
        (fp046, FP046_R002, FP046_R002_REL, FP046_R001),
        (npc, NPC_R002, NPC_R002_REL, NPC_R001),
    ):
        text = document_texts.get(path.as_posix())
        require(type(text) is str, f"R002 document is missing: {goal_id}")
        goal, _ = _goal_parts(text.encode(), goal_id)
        require(
            "canonical_update_event_sha256" not in event
            and not {
                "target_completion_event_sha256",
                "target_completion_occurred_at",
                "decided_at",
            }.intersection(event)
            and event.get("canonical_binding_snapshot_after")
            == cbu.get("canonical_binding_snapshot_after")
            and event.get("materialized_goal_id") == goal_id
            and event.get("materialized_goal_path") == path.as_posix()
            and event.get("materialized_goal_content_sha256") == bytes_sha256(
                text.encode()
            )
            and event.get("materialized_from_role")
            == goal.get("materialized_from_role")
            and event.get("materialized_from_path")
            == goal.get("materialized_from_path")
            and event.get("materialized_from_document_id")
            == goal.get("materialized_from_document_id")
            and event.get("materialized_from_sha256")
            == goal.get("materialized_from_sha256")
            and event.get("artifact_work_reason")
            == goal.get("artifact_work_reason")
            and event.get("artifact_trigger_evidence_refs")
            == goal.get("artifact_trigger_evidence_refs")
            and event.get("predecessor_goal_id")
            == source["last_completed_execution_leaf"]["goal_id"]
            and event.get("supersedes_goal_id") == predecessor
            and event.get("reopen_trigger")
            == {
                "canonical_update_event_sha256": cbu["event_sha256"],
                "target_completion_event_sha256": source["completion_events"][
                    predecessor
                ]["event_sha256"],
                "target_completion_occurred_at": source["completion_events"][
                    predecessor
                ]["occurred_at"],
                "decided_at": cbu["occurred_at"],
            }
            and "dynamic_goal_inventory_after" not in event
            and "materialized_child_goal_ids_by_parent_after" not in event,
            f"R002 successor materialization projection differs: {goal_id}",
        )
    require(
        fp046.get("status_changes") == {FP046_R001: "SUPERSEDED", FP046_R002: "PLANNED"}
        and npc.get("status_changes") == {NPC_R001: "SUPERSEDED", NPC_R002: "PLANNED"}
        and fp046.get("subject_goal_id") == FP046_R001
        and fp046.get("materialized_goal_id") == FP046_R002
        and npc.get("subject_goal_id") == NPC_R001
        and npc.get("materialized_goal_id") == NPC_R002,
        "R002 successor transition differs",
    )
    require(
        epic03_ready.get("subject_goal_id") == EPIC03
        and epic03_ready.get("status_changes") == {EPIC03: "READY"}
        and epic03_ready.get("readiness_basis")
        == {
            "mode": "CANONICAL_DEPENDENCY_CLOSURE_REOPEN",
            "canonical_update_event_sha256": cbu["event_sha256"],
            "successor_event_sha256_by_goal": {
                FP046_R002: fp046["event_sha256"],
                NPC_R002: npc["event_sha256"],
            },
            "archived_completion_event_sha256": source["completion_events"][
                EPIC03
            ]["event_sha256"],
        }
        and fp046_ready.get("subject_goal_id") == FP046_R002
        and fp046_ready.get("status_changes") == {FP046_R002: "READY"}
        and fp046_ready.get("previous_event_sha256") == epic03_ready["event_sha256"]
        and fp046_ready.get("reopened_container_ready_event_sha256")
        == epic03_ready["event_sha256"]
        and fp046_ready.get("readiness_basis")
        == {
            "dependency_completion_events": [
                {
                    "goal_id": FP008_R001,
                    "event_sha256": source["completion_events"][FP008_R001][
                        "event_sha256"
                    ],
                }
            ]
        },
        "R002 readiness order differs",
    )
    final = plan.get("final_state")
    require(
        plan.get("final_state_projection_only") is True and type(final) is dict,
        "preflight final state is not projection-only",
    )
    require(
        final.get("status_by_goal", {}).get(EPIC03) == "READY"
        and final["status_by_goal"].get(FP046_R001) == "SUPERSEDED"
        and final["status_by_goal"].get(FP046_R002) == "READY"
        and final["status_by_goal"].get(NPC_R001) == "SUPERSEDED"
        and final["status_by_goal"].get(NPC_R002) == "PLANNED",
        "final reopen status differs",
    )
    archived = final.get("archived_completion_evidence_by_goal")
    active = final.get("completion_evidence_by_goal")
    require(
        type(archived) is dict
        and type(active) is dict
        and archived == npc_archived
        and active == npc_active,
        "completion evidence archive differs",
    )
    inventory = final.get("dynamic_goal_inventory")
    children = final.get("materialized_child_goal_ids_by_parent")
    require(
        type(inventory) is dict
        and type(children) is dict
        and inventory.get(FP046_R002, {}).get("materialized_event_sha256") == fp046.get("event_sha256")
        and inventory.get(NPC_R002, {}).get("materialized_event_sha256") == npc.get("event_sha256")
        and epic03_ready.get("dynamic_goal_inventory_after") == inventory
        and epic03_ready.get("materialized_child_goal_ids_by_parent_after")
        == children
        and children.get(EPIC03)
        == sorted(
            [
                "WS-GOAL-EPIC-03-FP-047-R001",
                "WS-GOAL-EPIC-03-FP-048-R001",
                FP008_R001,
                FP046_R001,
                NPC_R001,
                FP046_R002,
                NPC_R002,
            ]
        ),
        "R002 inventory or child binding differs",
    )


def build_preflight(
    root: Path = ROOT,
    *,
    r029_gap_json: Path = R029_GAP_JSON_REL,
    r029_gap_md: Path = R029_GAP_MD_REL,
    r029_backlog_json: Path = R029_BACKLOG_JSON_REL,
    r029_backlog_md: Path = R029_BACKLOG_MD_REL,
    r029_discovery_json: Path = DISCOVERY_JSON_REL,
    r029_discovery_md: Path = DISCOVERY_MD_REL,
) -> dict[str, Any]:
    root = root.resolve(strict=True)
    paths = _r029_paths(
        gap_json=r029_gap_json,
        gap_md=r029_gap_md,
        backlog_json=r029_backlog_json,
        backlog_md=r029_backlog_md,
        discovery_json=r029_discovery_json,
        discovery_md=r029_discovery_md,
    )
    plan = _build_preflight_unchecked(root, paths)
    validate_preflight(root, plan)
    return plan


def validate_preflight(root: Path, plan: Mapping[str, Any]) -> None:
    root = root.resolve(strict=True)
    candidate_paths = plan.get("candidate_paths")
    require(
        type(candidate_paths) is dict
        and set(candidate_paths)
        == {
            "gap_json",
            "gap_md",
            "backlog_json",
            "backlog_md",
            "discovery_json",
            "discovery_md",
        },
        "candidate path inventory differs",
    )
    paths = {name: Path(value) for name, value in candidate_paths.items() if isinstance(value, str)}
    require(len(paths) == 6, "candidate path type differs")
    expected = _build_preflight_unchecked(root, paths)
    require(plan == expected, "preflight plan differs from strict deterministic replay")
    _validate_replay(plan, _load_source(root))


# The R029 candidate is a diagnostic source only.  Publication and the
# seq72--76 transition use the bridge's four canonical paths, while retaining
# the two discovery files as in-memory provenance only.
TRANSITION_R001_REVIEW_DIR = Path(
    "docs/control/execution/workstream-transitions/seq72-76/review-rounds/R001"
)
TRANSITION_R001_ASSIGNMENT_REL = TRANSITION_R001_REVIEW_DIR / "assignment.json"
TRANSITION_R001_RESULT_REL = TRANSITION_R001_REVIEW_DIR / "review-result.json"
TRANSITION_R001_INDEPENDENT_REL = (
    TRANSITION_R001_REVIEW_DIR / "independent-review.json"
)
TRANSITION_R001_PATHS = (
    TRANSITION_R001_ASSIGNMENT_REL,
    TRANSITION_R001_RESULT_REL,
    TRANSITION_R001_INDEPENDENT_REL,
)
TRANSITION_R001_PINS = {
    TRANSITION_R001_ASSIGNMENT_REL: (
        "1e671ac96a6cf91a9b47f1b1d879fd9392a531e5adf516c35fe0f3e072a54427",
        6_333,
    ),
    TRANSITION_R001_RESULT_REL: (
        "7f21510be130b8e309f077f55864958ea693c4c01f7ad6ac204dcf491ddcd586",
        6_343,
    ),
    TRANSITION_R001_INDEPENDENT_REL: (
        "1a51a85798256ec08c4c42795d63bb3a865f8a20ac0224ed845c756b202ac37b",
        6_600,
    ),
}
TRANSITION_R001_ROUND_ID = (
    "WS-FP046-NPC-R002-REOPEN-TRANSITION-20260815-R001"
)

TRANSITION_R002_REVIEW_DIR = Path(
    "docs/control/execution/workstream-transitions/seq72-76/review-rounds/R002"
)
TRANSITION_R002_ASSIGNMENT_REL = TRANSITION_R002_REVIEW_DIR / "assignment.json"
TRANSITION_R002_RESULT_REL = TRANSITION_R002_REVIEW_DIR / "review-result.json"
TRANSITION_R002_INDEPENDENT_REL = (
    TRANSITION_R002_REVIEW_DIR / "independent-review.json"
)
TRANSITION_R002_PATHS = (
    TRANSITION_R002_ASSIGNMENT_REL,
    TRANSITION_R002_RESULT_REL,
    TRANSITION_R002_INDEPENDENT_REL,
)
TRANSITION_R002_ROUND_ID = (
    "WS-FP046-NPC-R002-REOPEN-TRANSITION-20260815-R002"
)

# Public current-review aliases.  R001 stays frozen and is never selected by a
# writer after the R002 successor exists.
TRANSITION_REVIEW_DIR = TRANSITION_R002_REVIEW_DIR
TRANSITION_ASSIGNMENT_REL = TRANSITION_R002_ASSIGNMENT_REL
TRANSITION_RESULT_REL = TRANSITION_R002_RESULT_REL
TRANSITION_INDEPENDENT_REL = TRANSITION_R002_INDEPENDENT_REL
TRANSITION_ROUND_ID = TRANSITION_R002_ROUND_ID
TRANSITION_GOAL_ID = FP046_R002
TRANSITION_R002_ASSIGNER_ID = (
    "codex-root-fp046-npc-r002-transition-successor-assigner-20260823"
)
TRANSITION_R002_ASSIGNER_TASK = "/root"
TRANSITION_R002_EXECUTOR_ID = (
    "codex-fp046-npc-r002-transition-successor-executor-20260823"
)
TRANSITION_R002_EXECUTOR_TASK = "/root/audit_transaction"
TRANSITION_R002_REVIEWER_ID = (
    "codex-fp046-npc-r002-transition-successor-reviewer-20260823"
)
TRANSITION_R002_REVIEWER_TASK = "/root/r002_transition_final_review"
AUTHORIZATION_REL = Path(
    "docs/control/execution/workstream-transitions/seq72-76/authorization.json"
)
INITIAL_START_GATE_CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-046-R002/"
    "initial-start-gate-contract-r001.json"
)
TRANSITION_ADD_ONLY_PATHS = (
    *r029_bridge.CANONICAL_OUTPUT_PATHS,
    FP046_R002_REL,
    NPC_R002_REL,
    AUTHORIZATION_REL,
    INITIAL_START_GATE_CONTRACT_REL,
)
USER_AUTHORIZATION_QUOTE = (
    "계획 세워서 단계적으로 진행해 어떻게 진행해야 하는지 알지?"
)
R007_REVIEW_DIR = Path(
    "docs/control/execution/workstream-transitions/seq70-71/"
    "control-successor-reviews/20260815/R007"
)
R007_REVIEW_PINS = {
    R007_REVIEW_DIR / "assignment.json": (
        "7acb9a1eb5fcd79da3d0532daca0a7153f0c2a6ecb45922c957a66e827a9714c",
        9908,
    ),
    R007_REVIEW_DIR / "review-result.json": (
        "c744f4066daef97a14879ed1407b16982211ef0ae581da04322c110d83268471",
        9918,
    ),
    R007_REVIEW_DIR / "independent-review.json": (
        "bee57d7f30fb1d8bd7607baa001cde76ee0f5df5a1b0a88efe06362cc0c48e13",
        10218,
    ),
}
TRANSITION_BOUNDARY = {
    "product_implementation_credit_added": 0,
    "formal_test_credit_added": 0,
    "actual_device_credit_added": 0,
    "external_review_credit_added": 0,
    "deployment_credit_added": 0,
    "approval_credit_added": 0,
    "release_credit_added": 0,
    "release_status": "NOT_ELIGIBLE",
    "external_independence_claimed": False,
}
TRANSITION_REVIEW_ACCEPTANCE = {
    "seq72_through_seq76_only": True,
    "seq77_goal_started_remains_not_authorized_not_run": True,
    "r029_canonical_outputs_are_byte_exact_bridge_outputs": True,
    "discovery_records_remain_candidate_only": True,
    "epic03_fp046_npc_completion_evidence_moves_active_to_archive": True,
    "no_product_formal_device_external_deployment_or_release_credit": True,
    "atomic_apply_requires_exact_source_compare_exchange": True,
    "r001_transition_review_is_frozen_predecessor_only": True,
    "r009_control_successor_review_is_exact_and_approved": True,
    "r002_approval_envelope_normalizes_to_reviewed_plan_core": True,
    "all_eight_add_only_outputs_are_byte_exact": True,
}


def _validated_root(root: Path) -> Path:
    root = Path(root)
    try:
        info = root.lstat()
    except OSError as exc:
        raise BuildError(f"transition root cannot be inspected: {root}") from exc
    require(
        stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode),
        "transition root must be a non-symlink directory",
    )
    return root.resolve(strict=True)


def _safe_regular_bytes(root: Path, relative: Path, label: str) -> bytes:
    require(
        not relative.is_absolute() and bool(relative.parts)
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"unsafe {label} path",
    )
    cursor = root
    for part in relative.parts:
        cursor = cursor / part
        try:
            info = cursor.lstat()
        except OSError as exc:
            raise BuildError(f"required {label} is missing: {relative}") from exc
        require(not stat.S_ISLNK(info.st_mode), f"{label} uses a symlink: {relative}")
    require(
        stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
        f"{label} must be a single-link regular file: {relative}",
    )
    try:
        return cursor.read_bytes()
    except OSError as exc:
        raise BuildError(f"required {label} cannot be read: {relative}") from exc


def _write_add_only(root: Path, relative: Path, raw: bytes, label: str) -> None:
    require(
        not relative.is_absolute() and bool(relative.parts)
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"unsafe {label} output path",
    )
    cursor = root
    for part in relative.parent.parts:
        cursor = cursor / part
        try:
            info = cursor.lstat()
        except FileNotFoundError:
            try:
                cursor.mkdir()
            except FileExistsError:
                pass
            info = cursor.lstat()
        require(
            stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode),
            f"unsafe {label} output parent: {relative}",
        )
    target = cursor / relative.name
    try:
        info = target.lstat()
    except FileNotFoundError:
        info = None
    require(info is None, f"{label} output already exists: {relative}")
    try:
        with target.open("xb") as handle:
            handle.write(raw)
    except FileExistsError as exc:
        raise BuildError(f"{label} output already exists: {relative}") from exc


def _review_binding_by_role(
    paths: Sequence[Path], raw_by_path: Mapping[Path, bytes]
) -> dict[str, dict[str, Any]]:
    require(len(paths) == 3, "review triplet path inventory differs")
    return {
        role: _binding(relative, raw_by_path[relative])
        for role, relative in zip(
            ("assignment", "review_result", "independent_review"),
            paths,
            strict=True,
        )
    }


def load_frozen_transition_r001(
    root: Path = ROOT,
) -> tuple[dict[str, dict[str, Any]], dict[Path, bytes]]:
    """Replay the immutable R001 approval without treating it as current."""

    root = _validated_root(root)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative in TRANSITION_R001_PATHS:
        raw = _safe_regular_bytes(root, relative, "frozen transition R001 review")
        expected_sha256, expected_length = TRANSITION_R001_PINS[relative]
        require(
            bytes_sha256(raw) == expected_sha256 and len(raw) == expected_length,
            f"frozen transition R001 review differs: {relative}",
        )
        document = strict_json_bytes(raw, f"frozen transition R001 {relative.name}")
        require(
            raw == json_text(document).encode("utf-8"),
            f"frozen transition R001 {relative.name} is noncanonical",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[TRANSITION_R001_ASSIGNMENT_REL]
    result = documents[TRANSITION_R001_RESULT_REL]
    independent = documents[TRANSITION_R001_INDEPENDENT_REL]
    assignment_binding = _binding(
        TRANSITION_R001_ASSIGNMENT_REL,
        raw_by_path[TRANSITION_R001_ASSIGNMENT_REL],
    )
    result_binding = _binding(
        TRANSITION_R001_RESULT_REL,
        raw_by_path[TRANSITION_R001_RESULT_REL],
    )
    require(
        assignment.get("round_id") == TRANSITION_R001_ROUND_ID
        and result.get("round_id") == TRANSITION_R001_ROUND_ID
        and independent.get("round_id") == TRANSITION_R001_ROUND_ID
        and result.get("decision") == "APPROVED"
        and independent.get("decision") == "APPROVED"
        and result.get("assignment_binding") == assignment_binding
        and independent.get("assignment_provenance") == assignment_binding
        and independent.get("review_result_provenance") == result_binding,
        "frozen transition R001 approval provenance differs",
    )
    return _review_binding_by_role(TRANSITION_R001_PATHS, raw_by_path), raw_by_path


def _control_successor_module() -> Any:
    try:
        from scripts import (
            build_walksafe_fp022_completion_seq70_71_review_20260814 as module,
        )

        return module
    except (ImportError, ModuleNotFoundError):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(
            "scripts.build_walksafe_fp022_completion_seq70_71_review_20260814"
        )


def load_validated_control_successor_r009(
    root: Path = ROOT,
) -> tuple[dict[str, dict[str, Any]], dict[Path, bytes]]:
    """Load the reviewer-approved R009 control successor as exact bytes."""

    root = _validated_root(root)
    module = _control_successor_module()
    context = module.validated_control_successor_r009_context(root)
    paths = tuple(Path(path) for path in module.CONTROL_SUCCESSOR_R009_PATHS)
    require(len(paths) == 3 and len(set(paths)) == 3, "R009 control review paths differ")
    raw_by_path = {
        relative: _safe_regular_bytes(root, relative, "R009 control-successor review")
        for relative in paths
    }
    assignment_raw, result_raw, independent_raw = (
        raw_by_path[relative] for relative in paths
    )
    assignment = strict_json_bytes(assignment_raw, "R009 control assignment")
    result = strict_json_bytes(result_raw, "R009 control review result")
    module.validate_control_successor_r009_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    require(
        independent_raw
        == module.build_control_successor_r009_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ).encode("utf-8"),
        "R009 control independent review differs",
    )
    return _review_binding_by_role(paths, raw_by_path), raw_by_path


def _r007_review_bindings(root: Path) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for path, (expected_sha256, expected_length) in R007_REVIEW_PINS.items():
        raw = _safe_regular_bytes(root, path, "R007 control-successor review")
        require(
            bytes_sha256(raw) == expected_sha256 and len(raw) == expected_length,
            f"R007 control-successor review differs: {path}",
        )
        document = strict_json_bytes(raw, f"R007 {path.name}")
        require(raw == json_text(document).encode(), f"R007 {path.name} is noncanonical")
        documents[path] = document
        raw_by_path[path] = raw
        result.append(_binding(path, raw))
    assignment = documents[R007_REVIEW_DIR / "assignment.json"]
    review_result = documents[R007_REVIEW_DIR / "review-result.json"]
    independent = documents[R007_REVIEW_DIR / "independent-review.json"]
    expected_round = "WS-FP022-SEQ70-71-CURRENT-ACCEPTANCE-CONTROL-SUCCESSOR-20260815-R007"
    require(
        assignment.get("round_id") == expected_round
        and review_result.get("round_id") == expected_round
        and independent.get("round_id") == expected_round
        and review_result.get("decision") == "APPROVED"
        and independent.get("decision") == "APPROVED",
        "R007 control-successor review decision differs",
    )
    assignment_binding = _binding(
        R007_REVIEW_DIR / "assignment.json",
        raw_by_path[R007_REVIEW_DIR / "assignment.json"],
    )
    result_binding = _binding(
        R007_REVIEW_DIR / "review-result.json",
        raw_by_path[R007_REVIEW_DIR / "review-result.json"],
    )
    require(
        review_result.get("assignment_binding") == assignment_binding
        and independent.get("assignment_provenance") == assignment_binding
        and independent.get("review_result_provenance") == result_binding,
        "R007 control-successor provenance differs",
    )
    return result


def _canonical_r029_inputs(
    root: Path,
) -> tuple[dict[str, Path], dict[str, bytes], dict[Path, str], dict[str, Any]]:
    outputs, source_evidence = r029_bridge.build_outputs_and_source_evidence(root)
    candidate_outputs = r029_candidate.build_outputs(root)
    paths = {
        "gap_json": r029_bridge.CANONICAL_GAP_JSON_REL,
        "gap_md": r029_bridge.CANONICAL_GAP_MD_REL,
        "backlog_json": r029_bridge.CANONICAL_BACKLOG_JSON_REL,
        "backlog_md": r029_bridge.CANONICAL_BACKLOG_MD_REL,
        "discovery_json": DISCOVERY_JSON_REL,
        "discovery_md": DISCOVERY_MD_REL,
    }
    raw = {
        "gap_json": outputs[r029_bridge.CANONICAL_GAP_JSON_REL].encode("utf-8"),
        "gap_md": outputs[r029_bridge.CANONICAL_GAP_MD_REL].encode("utf-8"),
        "backlog_json": outputs[r029_bridge.CANONICAL_BACKLOG_JSON_REL].encode("utf-8"),
        "backlog_md": outputs[r029_bridge.CANONICAL_BACKLOG_MD_REL].encode("utf-8"),
        "discovery_json": candidate_outputs[DISCOVERY_JSON_REL].encode("utf-8"),
        "discovery_md": candidate_outputs[DISCOVERY_MD_REL].encode("utf-8"),
    }
    expected = {
        "gap_json": candidate_outputs[R029_GAP_JSON_REL].encode("utf-8"),
        "gap_md": candidate_outputs[R029_GAP_MD_REL].encode("utf-8"),
        "backlog_json": candidate_outputs[R029_BACKLOG_JSON_REL].encode("utf-8"),
        "backlog_md": candidate_outputs[R029_BACKLOG_MD_REL].encode("utf-8"),
        "discovery_json": candidate_outputs[DISCOVERY_JSON_REL].encode("utf-8"),
        "discovery_md": candidate_outputs[DISCOVERY_MD_REL].encode("utf-8"),
    }
    require(raw == expected, "canonical R029 bridge bytes differ from candidate")
    require(
        source_evidence.get("candidate_only_paths")
        == [path.as_posix() for path in r029_bridge.CANDIDATE_ONLY_PATHS]
        and source_evidence.get("discovery_boundary")
        == {
            "canonical_application_status": "NOT_APPLIED",
            "publication_scope": "CANDIDATE_ONLY_SOURCE_EVIDENCE",
        },
        "R029 candidate-only discovery boundary differs",
    )
    return paths, raw, outputs, source_evidence


def build_canonical_preflight(root: Path = ROOT) -> dict[str, Any]:
    """Build the seq72--76 projection against canonical R029 bytes in memory."""

    root = _validated_root(root)
    paths, raw, _outputs, _evidence = _canonical_r029_inputs(root)
    plan = _build_preflight_unchecked(root, paths, r029_raw=raw)
    _validate_replay(plan, _load_source(root))
    return plan


def _authorization_document() -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP046-NPC-R002-REOPEN-AUTHORIZATION-20260815-001",
        "evidence_type": "USER_AUTHORIZATION",
        "authorization_quote": USER_AUTHORIZATION_QUOTE,
        "authorization_scope": {
            "authorized_actions": [
                "R029_CANONICAL_GAP_BACKLOG_PUBLICATION",
                "SEQ72_76_REOPEN_TRANSACTION",
                "R002_GOAL_DOCUMENT_MATERIALIZATION",
                "R002_INITIAL_START_GATE_CONTRACT_PUBLICATION",
            ],
            "excluded_actions": [
                "SEQ77_GOAL_STARTED",
                "PRODUCT_CODE_CHANGE",
                "FORMAL_TEST_CREDIT",
                "ACTUAL_DEVICE_CREDIT",
                "EXTERNAL_REVIEW_CREDIT",
                "DEPLOYMENT",
                "RELEASE_APPROVAL",
            ],
        },
        "authorization_status": "AUTHORIZED_FOR_SEQ72_76_ONLY",
        "sequence_77_status": "NOT_AUTHORIZED",
    }


def _initial_start_gate_contract(plan: Mapping[str, Any]) -> dict[str, Any]:
    documents = plan.get("documents")
    require(type(documents) is dict, "preflight R002 documents are missing")
    raw = documents.get(FP046_R002_REL.as_posix())
    require(type(raw) is str, "FP046 R002 document is missing")
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP046-R002-INITIAL-START-GATE-CONTRACT-20260815-001",
        "contract_id": "WS-FP046-R002-INTERNAL-START-GATE-R001",
        "contract_version": "2026-08-15.1",
        "target_goal_id": FP046_R002,
        "target_goal_content_sha256": bytes_sha256(raw.encode("utf-8")),
        "gate_purpose": "INITIAL_START",
        "sequence_77_boundary": {
            "event_type": "GOAL_STARTED",
            "authorization_status": "NOT_AUTHORIZED",
            "execution_status": "NOT_RUN",
            "product_code_change_authorized": False,
        },
        "ordered_checks": [
            {"check_id": "CONTINUATION", "status": "NOT_RUN"},
            {"check_id": "GOAL_GRAPH", "status": "NOT_RUN"},
            {"check_id": "TEST_LAYER_REGISTRY_VALIDATE", "status": "NOT_RUN"},
            {"check_id": "ROOT_FP046_R002_CONTROL_REGRESSION", "status": "NOT_RUN"},
            {"check_id": "REPOSITORY_STATE", "status": "NOT_RUN"},
        ],
    }


def _output_binding(path: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
    }


def _reseal_transition_event(event: Mapping[str, Any]) -> dict[str, Any]:
    sealed = deepcopy(dict(event))
    sealed.pop("event_sha256", None)
    sealed["event_sha256"] = object_sha256(sealed)
    return sealed


def bind_transition_review_evidence(
    plan: Mapping[str, Any],
    *,
    predecessor_transition_review_binding: Mapping[str, Any] | None = None,
    transition_review_binding: Mapping[str, Any] | None = None,
    transition_review_subject_binding: Mapping[str, Any] | None = None,
    r009_control_review_binding: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Attach review evidence to seq72 and deterministically reseal seq72--76."""

    result = deepcopy(dict(plan))
    events = result.get("events")
    require(type(events) is list and len(events) == 5, "preflight event inventory differs")
    cbu, fp046, npc, epic03_ready, fp046_ready = events
    require(all(isinstance(event, dict) for event in events), "preflight event type differs")
    for field in (
        "predecessor_transition_review_binding",
        "transition_review_binding",
        "transition_review_subject_binding",
        "r008_control_review_binding",
        "r009_control_review_binding",
    ):
        cbu.pop(field, None)
    for field, binding in (
        ("predecessor_transition_review_binding", predecessor_transition_review_binding),
        ("transition_review_binding", transition_review_binding),
        ("transition_review_subject_binding", transition_review_subject_binding),
        ("r009_control_review_binding", r009_control_review_binding),
    ):
        if binding is not None:
            require(isinstance(binding, Mapping), f"{field} is malformed")
            cbu[field] = deepcopy(dict(binding))
    cbu = _reseal_transition_event(cbu)
    for event in (fp046, npc):
        trigger = event.get("reopen_trigger")
        require(isinstance(trigger, dict), "successor reopen trigger is missing")
        trigger["canonical_update_event_sha256"] = cbu["event_sha256"]
    fp046["previous_event_sha256"] = cbu["event_sha256"]
    fp046 = _reseal_transition_event(fp046)
    npc["previous_event_sha256"] = fp046["event_sha256"]
    npc = _reseal_transition_event(npc)
    final = result.get("final_state")
    require(isinstance(final, dict), "preflight final state is missing")
    inventory = final.get("dynamic_goal_inventory")
    children = final.get("materialized_child_goal_ids_by_parent")
    require(
        isinstance(inventory, dict) and isinstance(children, dict),
        "preflight dynamic Goal inventory is missing",
    )
    inventory[FP046_R002]["materialized_event_sha256"] = fp046["event_sha256"]
    inventory[NPC_R002]["materialized_event_sha256"] = npc["event_sha256"]
    basis = epic03_ready.get("readiness_basis")
    require(isinstance(basis, dict), "EPIC03 readiness basis is missing")
    basis["canonical_update_event_sha256"] = cbu["event_sha256"]
    basis["successor_event_sha256_by_goal"] = {
        FP046_R002: fp046["event_sha256"],
        NPC_R002: npc["event_sha256"],
    }
    epic03_ready["previous_event_sha256"] = npc["event_sha256"]
    epic03_ready["dynamic_goal_inventory_after"] = deepcopy(inventory)
    epic03_ready["materialized_child_goal_ids_by_parent_after"] = deepcopy(children)
    epic03_ready = _reseal_transition_event(epic03_ready)
    fp046_ready["previous_event_sha256"] = epic03_ready["event_sha256"]
    fp046_ready["reopened_container_ready_event_sha256"] = epic03_ready["event_sha256"]
    fp046_ready = _reseal_transition_event(fp046_ready)
    result["events"] = [cbu, fp046, npc, epic03_ready, fp046_ready]
    return result


def approval_neutral_plan_core(plan: Mapping[str, Any]) -> dict[str, Any]:
    """Remove only the current R002 approval envelope and reseal the plan."""

    events = plan.get("events")
    require(
        isinstance(events, list) and events and isinstance(events[0], dict),
        "approval-neutral plan is malformed",
    )
    seq72 = events[0]
    return bind_transition_review_evidence(
        plan,
        predecessor_transition_review_binding=seq72.get(
            "predecessor_transition_review_binding"
        ),
        r009_control_review_binding=seq72.get("r009_control_review_binding"),
    )


def transition_plan_core_binding(plan: Mapping[str, Any]) -> dict[str, Any]:
    raw = json_text(dict(plan)).encode("utf-8")
    return {
        "content_type": "FP046_NPC_R002_APPROVAL_NEUTRAL_SEQ72_76_PLAN_CORE",
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
    }


def validate_reviewed_transition_plan(
    plan: Mapping[str, Any],
    package_or_scope: Mapping[str, Any],
    transition_review_binding: Mapping[str, Any],
) -> None:
    """Require a final R002-bound plan to normalize to its reviewed core."""

    events = plan.get("events")
    require(
        isinstance(events, list) and events and isinstance(events[0], dict),
        "reviewed transition plan is malformed",
    )
    seq72 = events[0]
    reviewed_core = package_or_scope.get("approval_neutral_plan_core")
    if reviewed_core is None:
        reviewed_core_binding = package_or_scope.get("corrected_plan_core_binding")
        predecessor_binding = package_or_scope.get(
            "predecessor_transition_review_bindings"
        )
        r009_binding = package_or_scope.get(
            "r009_control_successor_review_bindings"
        )
    else:
        reviewed_core_binding = package_or_scope.get(
            "approval_neutral_plan_core_binding"
        )
        predecessor_binding = package_or_scope.get(
            "predecessor_transition_review_bindings"
        )
        r009_binding = package_or_scope.get(
            "r009_control_successor_review_bindings"
        )
    require(
        isinstance(reviewed_core_binding, dict)
        and (
            reviewed_core is None
            or (
                isinstance(reviewed_core, dict)
                and reviewed_core_binding
                == transition_plan_core_binding(reviewed_core)
            )
        ),
        "reviewed transition plan core binding differs",
    )
    require(
        "r008_control_review_binding" not in seq72
        and seq72.get("predecessor_transition_review_binding")
        == predecessor_binding
        and seq72.get("r009_control_review_binding")
        == r009_binding
        and seq72.get("transition_review_binding")
        == transition_review_binding
        and seq72.get("transition_review_subject_binding")
        == reviewed_core_binding,
        "reviewed transition plan evidence binding differs",
    )
    neutral = approval_neutral_plan_core(plan)
    require(
        transition_plan_core_binding(neutral) == reviewed_core_binding
        and (reviewed_core is None or neutral == reviewed_core),
        "final transition plan does not normalize to the reviewed core",
    )


def build_transition_package(root: Path = ROOT) -> dict[str, Any]:
    """Stage, but never publish, the complete R002 seq72--76 subject set."""

    root = _validated_root(root)
    preflight = build_canonical_preflight(root)
    _paths, _raw, canonical_outputs, source_evidence = _canonical_r029_inputs(root)
    r001_binding, _r001_raw = load_frozen_transition_r001(root)
    r009_binding, _r009_raw = load_validated_control_successor_r009(root)
    authorization = json_text(_authorization_document()).encode("utf-8")
    start_contract = json_text(_initial_start_gate_contract(preflight)).encode("utf-8")
    subjects: dict[Path, bytes] = {
        **{
            path: text.encode("utf-8")
            for path, text in canonical_outputs.items()
        },
        **{
            Path(path): text.encode("utf-8")
            for path, text in preflight["documents"].items()
        },
        AUTHORIZATION_REL: authorization,
        INITIAL_START_GATE_CONTRACT_REL: start_contract,
    }
    expected_paths = set(TRANSITION_ADD_ONLY_PATHS)
    require(set(subjects) == expected_paths, "staged transition subject inventory differs")
    source = _load_source(root)
    approval_neutral_core = bind_transition_review_evidence(
        preflight,
        predecessor_transition_review_binding=r001_binding,
        r009_control_review_binding=r009_binding,
    )
    return {
        "schema_version": "walksafe.fp046-npc-r002-reopen-transition-package.v2",
        "transaction_status": "STAGED_NOT_APPLIED",
        "source_checkpoint": _binding(CHECKPOINT_REL, source["checkpoint_raw"]),
        "source_sequence": SOURCE_SEQUENCE,
        "predecessor_transition_review_bindings": r001_binding,
        "r009_control_successor_review_bindings": r009_binding,
        "canonical_r029_source_evidence": source_evidence,
        "candidate_discovery_publication": "NOT_APPLIED_CANDIDATE_ONLY",
        "authorization": _output_binding(AUTHORIZATION_REL, authorization),
        "initial_start_gate_contract": _output_binding(
            INITIAL_START_GATE_CONTRACT_REL, start_contract
        ),
        "staged_subject_bindings": [
            _output_binding(path, subjects[path]) for path in sorted(subjects)
        ],
        "preflight": preflight,
        "approval_neutral_plan_core": approval_neutral_core,
        "approval_neutral_plan_core_binding": transition_plan_core_binding(
            approval_neutral_core
        ),
    }


def _transition_scope(package: Mapping[str, Any]) -> dict[str, Any]:
    preflight = package.get("approval_neutral_plan_core")
    require(
        package.get("schema_version")
        == "walksafe.fp046-npc-r002-reopen-transition-package.v2"
        and package.get("transaction_status") == "STAGED_NOT_APPLIED"
        and package.get("source_sequence") == SOURCE_SEQUENCE
        and type(preflight) is dict,
        "transition package envelope differs",
    )
    events = preflight.get("events")
    require(type(events) is list and len(events) == 5, "transition event projection is missing")
    require(
        package.get("approval_neutral_plan_core_binding")
        == transition_plan_core_binding(preflight),
        "transition plan core binding differs",
    )
    staged_subject_bindings = package.get("staged_subject_bindings")
    expected_output_paths = sorted(path.as_posix() for path in TRANSITION_ADD_ONLY_PATHS)
    require(
        isinstance(staged_subject_bindings, list)
        and len(staged_subject_bindings) == 8,
        "transition add-only output bindings differ",
    )
    require(
        [row.get("path") if isinstance(row, Mapping) else None for row in staged_subject_bindings]
        == expected_output_paths
        and all(
            isinstance(row, Mapping)
            and set(row) == {"path", "sha256", "byte_length"}
            and isinstance(row.get("sha256"), str)
            and isinstance(row.get("byte_length"), int)
            for row in staged_subject_bindings
        ),
        "transition add-only output binding inventory differs",
    )
    staged_by_path = {row["path"]: row for row in staged_subject_bindings}
    require(
        staged_by_path[AUTHORIZATION_REL.as_posix()] == package.get("authorization")
        and staged_by_path[INITIAL_START_GATE_CONTRACT_REL.as_posix()]
        == package.get("initial_start_gate_contract"),
        "transition add-only output bindings differ",
    )
    seq72 = events[0]
    require(
        seq72.get("predecessor_transition_review_binding")
        == package.get("predecessor_transition_review_bindings")
        and seq72.get("r009_control_review_binding")
        == package.get("r009_control_successor_review_bindings")
        and "transition_review_binding" not in seq72
        and "transition_review_subject_binding" not in seq72,
        "approval-neutral transition review bindings differ",
    )
    return {
        "source_checkpoint": deepcopy(package["source_checkpoint"]),
        "source_sequence": SOURCE_SEQUENCE,
        "predecessor_transition_review_bindings": deepcopy(
            package["predecessor_transition_review_bindings"]
        ),
        "r009_control_successor_review_bindings": deepcopy(
            package["r009_control_successor_review_bindings"]
        ),
        "corrected_plan_core_binding": deepcopy(
            package["approval_neutral_plan_core_binding"]
        ),
        "authorization": deepcopy(package["authorization"]),
        "initial_start_gate_contract": deepcopy(package["initial_start_gate_contract"]),
        "canonical_r029_subject_bindings": [
            row
            for row in package["staged_subject_bindings"]
            if row["path"]
            in {path.as_posix() for path in r029_bridge.CANONICAL_OUTPUT_PATHS}
        ],
        "candidate_discovery_publication": "NOT_APPLIED_CANDIDATE_ONLY",
        "r002_goal_subject_bindings": [
            row
            for row in package["staged_subject_bindings"]
            if row["path"] in {FP046_R002_REL.as_posix(), NPC_R002_REL.as_posix()}
        ],
        "corrected_add_only_output_bindings": deepcopy(
            staged_subject_bindings
        ),
        "projected_transition": [
            {
                "sequence": event["sequence"],
                "event_id": event["event_id"],
                "event_type": event["event_type"],
                "status_changes": deepcopy(event["status_changes"]),
            }
            for event in events
        ],
        "acceptance": deepcopy(TRANSITION_REVIEW_ACCEPTANCE),
    }


def build_transition_assignment(root: Path, *, assigned_at: str) -> str:
    _parse_time(assigned_at, "transition assignment time")
    package = build_transition_package(root)
    assignment = {
        "schema_version": "1.0",
        "evidence_type": "FP046_NPC_R002_REOPEN_TRANSITION_REVIEW_ASSIGNMENT",
        "goal_id": TRANSITION_GOAL_ID,
        "round_id": TRANSITION_ROUND_ID,
        "assigned_at": assigned_at,
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": TRANSITION_R002_ASSIGNER_ID,
            "canonical_task": TRANSITION_R002_ASSIGNER_TASK,
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": TRANSITION_R002_EXECUTOR_ID,
            "canonical_task": TRANSITION_R002_EXECUTOR_TASK,
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": TRANSITION_R002_REVIEWER_ID,
            "canonical_task": TRANSITION_R002_REVIEWER_TASK,
        },
        "review_scope": _transition_scope(package),
        "review_boundary": deepcopy(TRANSITION_BOUNDARY),
    }
    raw = json_text(assignment)
    validate_transition_assignment_document(assignment, raw.encode("utf-8"), package)
    return raw


def validate_transition_assignment_document(
    assignment: Mapping[str, Any], raw: bytes, package: Mapping[str, Any]
) -> None:
    _validate_transition_assignment_envelope(assignment, raw)
    require(
        assignment.get("review_scope") == _transition_scope(package),
        "transition assignment envelope differs",
    )


def _validate_transition_assignment_envelope(
    assignment: Mapping[str, Any], raw: bytes
) -> None:
    require(raw == json_text(dict(assignment)).encode("utf-8"), "transition assignment is noncanonical")
    require(
        set(assignment)
        == {
            "schema_version", "evidence_type", "goal_id", "round_id", "assigned_at",
            "assigner", "executor", "reviewer", "review_scope", "review_boundary",
        }
        and assignment.get("schema_version") == "1.0"
        and assignment.get("evidence_type") == "FP046_NPC_R002_REOPEN_TRANSITION_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == TRANSITION_GOAL_ID
        and assignment.get("round_id") == TRANSITION_ROUND_ID
        and isinstance(assignment.get("review_scope"), dict)
        and assignment.get("review_boundary") == TRANSITION_BOUNDARY,
        "transition assignment envelope differs",
    )
    _parse_time(assignment.get("assigned_at"), "transition assignment time")
    for field, expected in (
        (
            "assigner",
            (
                "INTERNAL_REVIEW_ASSIGNER",
                TRANSITION_R002_ASSIGNER_ID,
                TRANSITION_R002_ASSIGNER_TASK,
            ),
        ),
        (
            "executor",
            (
                "INTERNAL_IMPLEMENTATION_EXECUTOR",
                TRANSITION_R002_EXECUTOR_ID,
                TRANSITION_R002_EXECUTOR_TASK,
            ),
        ),
        (
            "reviewer",
            (
                "SEPARATE_INTERNAL_REVIEWER",
                TRANSITION_R002_REVIEWER_ID,
                TRANSITION_R002_REVIEWER_TASK,
            ),
        ),
    ):
        value = assignment.get(field)
        require(
            isinstance(value, dict)
            and (value.get("role"), value.get("agent_instance_id"), value.get("canonical_task")) == expected,
            f"transition assignment {field} differs",
        )
    assigner = assignment["assigner"]
    executor = assignment["executor"]
    reviewer = assignment["reviewer"]
    require(
        reviewer["agent_instance_id"]
        not in {assigner["agent_instance_id"], executor["agent_instance_id"]}
        and reviewer["canonical_task"]
        not in {assigner["canonical_task"], executor["canonical_task"]},
        "transition assignment reviewer is not separate",
    )


def _read_transition_assignment(root: Path) -> tuple[dict[str, Any], bytes, dict[str, Any]]:
    package = build_transition_package(root)
    raw = _safe_regular_bytes(root, TRANSITION_ASSIGNMENT_REL, "transition assignment")
    assignment = strict_json_bytes(raw, "transition assignment")
    validate_transition_assignment_document(assignment, raw, package)
    return assignment, raw, package


def _validate_transition_result_document(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
) -> None:
    assignment_binding = _binding(TRANSITION_ASSIGNMENT_REL, assignment_raw)
    require(raw == json_text(dict(result)).encode("utf-8"), "transition review result is noncanonical")
    require(
        set(result)
        == {
            "schema_version", "evidence_type", "goal_id", "round_id", "reviewed_at",
            "reviewer", "assignment_binding", "decision", "findings",
            "finding_dispositions", "review_scope", "review_boundary",
        }
        and result.get("schema_version") == "1.0"
        and result.get("evidence_type") == "FP046_NPC_R002_REOPEN_TRANSITION_REVIEWER_AUTHORED_RESULT"
        and result.get("goal_id") == TRANSITION_GOAL_ID
        and result.get("round_id") == TRANSITION_ROUND_ID
        and result.get("assignment_binding") == assignment_binding
        and result.get("decision") == "APPROVED"
        and result.get("findings") == {"blocking": [], "major_open": [], "minor_open": []}
        and result.get("finding_dispositions") == []
        and result.get("review_scope") == assignment.get("review_scope")
        and result.get("review_boundary") == assignment.get("review_boundary"),
        "transition review result differs",
    )
    reviewer = result.get("reviewer")
    require(
        isinstance(reviewer, dict) and reviewer == assignment.get("reviewer"),
        "transition review reviewer identity differs",
    )
    assigner = assignment.get("assigner")
    executor = assignment.get("executor")
    require(
        isinstance(assigner, dict)
        and isinstance(executor, dict)
        and reviewer.get("agent_instance_id")
        not in {assigner.get("agent_instance_id"), executor.get("agent_instance_id")}
        and reviewer.get("canonical_task")
        not in {assigner.get("canonical_task"), executor.get("canonical_task")},
        "transition review reviewer is not separate",
    )
    require(
        _parse_time(result.get("reviewed_at"), "transition review time")
        >= _parse_time(assignment.get("assigned_at"), "transition assignment time"),
        "transition review predates assignment",
    )


def validate_transition_review_result(root: Path) -> tuple[dict[str, Any], bytes]:
    assignment, assignment_raw, _package = _read_transition_assignment(root)
    raw = _safe_regular_bytes(root, TRANSITION_RESULT_REL, "transition review result")
    result = strict_json_bytes(raw, "transition review result")
    _validate_transition_result_document(result, raw, assignment, assignment_raw)
    return result, raw


def build_transition_independent_review(
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    _validate_transition_result_document(result, result_raw, assignment, assignment_raw)
    independent = {
        "schema_version": "1.0",
        "evidence_type": "FP046_NPC_R002_REOPEN_TRANSITION_INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": TRANSITION_GOAL_ID,
        "round_id": TRANSITION_ROUND_ID,
        "reviewed_at": result["reviewed_at"],
        "reviewer": deepcopy(result["reviewer"]),
        "assignment_provenance": _binding(TRANSITION_ASSIGNMENT_REL, assignment_raw),
        "review_result_provenance": _binding(TRANSITION_RESULT_REL, result_raw),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_scope": deepcopy(assignment["review_scope"]),
        "review_boundary": deepcopy(assignment["review_boundary"]),
    }
    return json_text(independent)


def validate_transition_post_review(root: Path = ROOT) -> dict[str, Any]:
    root = _validated_root(root)
    assignment, assignment_raw, package = _read_transition_assignment(root)
    result, result_raw = validate_transition_review_result(root)
    expected = build_transition_independent_review(
        assignment, assignment_raw, result, result_raw
    ).encode("utf-8")
    actual = _safe_regular_bytes(root, TRANSITION_INDEPENDENT_REL, "transition independent review")
    require(actual == expected, "transition independent review differs")
    return package


def _validate_post_publish_transition_scope(
    root: Path,
    assignment: Mapping[str, Any],
    checkpoint: Mapping[str, Any],
) -> None:
    scope = assignment.get("review_scope")
    require(isinstance(scope, dict), "transition review scope is missing")
    expected_scope_fields = {
        "source_checkpoint",
        "source_sequence",
        "predecessor_transition_review_bindings",
        "r009_control_successor_review_bindings",
        "corrected_plan_core_binding",
        "authorization",
        "initial_start_gate_contract",
        "canonical_r029_subject_bindings",
        "candidate_discovery_publication",
        "r002_goal_subject_bindings",
        "corrected_add_only_output_bindings",
        "projected_transition",
        "acceptance",
    }
    source_checkpoint = scope.get("source_checkpoint")
    core_binding = scope.get("corrected_plan_core_binding")
    require(
        set(scope) == expected_scope_fields
        and isinstance(source_checkpoint, dict)
        and source_checkpoint.get("path") == CHECKPOINT_REL.as_posix()
        and isinstance(source_checkpoint.get("sha256"), str)
        and len(source_checkpoint["sha256"]) == 64
        and isinstance(source_checkpoint.get("byte_length"), int)
        and isinstance(core_binding, dict)
        and core_binding.get("content_type")
        == "FP046_NPC_R002_APPROVAL_NEUTRAL_SEQ72_76_PLAN_CORE"
        and isinstance(core_binding.get("sha256"), str)
        and len(core_binding["sha256"]) == 64
        and isinstance(core_binding.get("byte_length"), int),
        "post-publish transition review scope envelope differs",
    )
    r001_binding, _r001_raw = load_frozen_transition_r001(root)
    r009_binding, _r009_raw = load_validated_control_successor_r009(root)
    output_bindings = [
        _output_binding(
            relative,
            _safe_regular_bytes(root, relative, "published transition output"),
        )
        for relative in sorted(TRANSITION_ADD_ONLY_PATHS)
    ]
    output_by_path = {row["path"]: row for row in output_bindings}
    events = _post_publish_transition_events(checkpoint)
    projected = [
        {
            "sequence": event["sequence"],
            "event_id": event["event_id"],
            "event_type": event["event_type"],
            "status_changes": deepcopy(event["status_changes"]),
        }
        for event in events
    ]
    require(
        scope.get("source_sequence") == SOURCE_SEQUENCE
        and scope.get("predecessor_transition_review_bindings") == r001_binding
        and scope.get("r009_control_successor_review_bindings") == r009_binding
        and scope.get("corrected_add_only_output_bindings") == output_bindings
        and scope.get("authorization")
        == output_by_path[AUTHORIZATION_REL.as_posix()]
        and scope.get("initial_start_gate_contract")
        == output_by_path[INITIAL_START_GATE_CONTRACT_REL.as_posix()]
        and scope.get("canonical_r029_subject_bindings")
        == [
            row
            for row in output_bindings
            if row["path"]
            in {path.as_posix() for path in r029_bridge.CANONICAL_OUTPUT_PATHS}
        ]
        and scope.get("r002_goal_subject_bindings")
        == [
            output_by_path[path.as_posix()]
            for path in (FP046_R002_REL, NPC_R002_REL)
        ]
        and scope.get("candidate_discovery_publication")
        == "NOT_APPLIED_CANDIDATE_ONLY"
        and scope.get("projected_transition") == projected
        and scope.get("acceptance") == TRANSITION_REVIEW_ACCEPTANCE,
        "post-publish transition review scope differs",
    )


def _post_publish_transition_events(
    checkpoint: Mapping[str, Any],
) -> tuple[Mapping[str, Any], ...]:
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(isinstance(history, list), "post-publish transition history is missing")
    selected: list[tuple[int, Mapping[str, Any]]] = []
    for sequence, event_id, event_type in zip(
        REOPEN_SEQUENCES,
        REOPEN_EVENT_IDS,
        REOPEN_EVENT_TYPES,
        strict=True,
    ):
        matches = [
            (index, event)
            for index, event in enumerate(history)
            if isinstance(event, Mapping) and event.get("sequence") == sequence
        ]
        require(
            len(matches) == 1,
            f"post-publish seq{sequence} transition is missing or non-unique",
        )
        index, event = matches[0]
        require(
            event.get("event_id") == event_id
            and event.get("event_type") == event_type,
            f"post-publish seq{sequence} transition identity differs",
        )
        selected.append((index, event))
    require(
        [index for index, _event in selected]
        == [sequence - 1 for sequence in REOPEN_SEQUENCES],
        "post-publish seq72-76 transition position differs",
    )
    return tuple(event for _index, event in selected)


def load_validated_transition_r002(
    root: Path = ROOT,
) -> tuple[dict[str, dict[str, Any]], dict[Path, bytes]]:
    """Load the current approved R002 review triplet as exact role bindings."""

    root = _validated_root(root)
    raw_by_path = {
        relative: _safe_regular_bytes(root, relative, "transition R002 review")
        for relative in TRANSITION_R002_PATHS
    }
    assignment_raw = raw_by_path[TRANSITION_R002_ASSIGNMENT_REL]
    result_raw = raw_by_path[TRANSITION_R002_RESULT_REL]
    independent_raw = raw_by_path[TRANSITION_R002_INDEPENDENT_REL]
    checkpoint = strict_json_bytes(
        _safe_regular_bytes(root, CHECKPOINT_REL, "transition checkpoint"),
        "transition checkpoint",
    )
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(isinstance(history, list), "transition checkpoint history is missing")
    if len(history) == SOURCE_SEQUENCE:
        validate_transition_post_review(root)
    else:
        assignment = strict_json_bytes(assignment_raw, "transition R002 assignment")
        result = strict_json_bytes(result_raw, "transition R002 review result")
        _validate_transition_assignment_envelope(assignment, assignment_raw)
        _validate_post_publish_transition_scope(root, assignment, checkpoint)
        _validate_transition_result_document(
            result,
            result_raw,
            assignment,
            assignment_raw,
        )
        require(
            independent_raw
            == build_transition_independent_review(
                assignment,
                assignment_raw,
                result,
                result_raw,
            ).encode("utf-8"),
            "transition independent review differs",
        )
    return _review_binding_by_role(TRANSITION_R002_PATHS, raw_by_path), raw_by_path


def write_transition_assignment(root: Path, *, assigned_at: str) -> None:
    root = _validated_root(root)
    _write_add_only(
        root,
        TRANSITION_ASSIGNMENT_REL,
        build_transition_assignment(root, assigned_at=assigned_at).encode("utf-8"),
        "transition assignment",
    )


def write_transition_independent_review(root: Path) -> None:
    root = _validated_root(root)
    assignment, assignment_raw, _package = _read_transition_assignment(root)
    result, result_raw = validate_transition_review_result(root)
    _write_add_only(
        root,
        TRANSITION_INDEPENDENT_REL,
        build_transition_independent_review(
            assignment, assignment_raw, result, result_raw
        ).encode("utf-8"),
        "transition independent review",
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--r029-gap-json", type=Path, default=R029_GAP_JSON_REL)
    parser.add_argument("--r029-gap-md", type=Path, default=R029_GAP_MD_REL)
    parser.add_argument("--r029-backlog-json", type=Path, default=R029_BACKLOG_JSON_REL)
    parser.add_argument("--r029-backlog-md", type=Path, default=R029_BACKLOG_MD_REL)
    parser.add_argument("--r029-discovery-json", type=Path, default=DISCOVERY_JSON_REL)
    parser.add_argument("--r029-discovery-md", type=Path, default=DISCOVERY_MD_REL)
    parser.add_argument("--print-json", action="store_true")
    parser.add_argument("--print-transition-package", action="store_true")
    parser.add_argument("--write-transition-assignment", action="store_true")
    parser.add_argument("--check-transition-assignment", action="store_true")
    parser.add_argument("--check-transition-review-result", action="store_true")
    parser.add_argument("--write-transition-independent", action="store_true")
    parser.add_argument("--check-transition-post-review", action="store_true")
    parser.add_argument("--assigned-at")
    parser.add_argument("--apply", action="store_true", help="reserved for the separate atomic transaction tool")
    args = parser.parse_args(argv)
    try:
        modes = sum(
            bool(value)
            for value in (
                args.print_json,
                args.print_transition_package,
                args.write_transition_assignment,
                args.check_transition_assignment,
                args.check_transition_review_result,
                args.write_transition_independent,
                args.check_transition_post_review,
                args.apply,
            )
        )
        require(modes <= 1, "choose at most one execution mode")
        root = _validated_root(args.root)
        if args.apply:
            raise BuildError(
                "apply belongs to apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py"
            )
        if args.print_transition_package:
            print(json_text(build_transition_package(root)))
            return 0
        if args.write_transition_assignment:
            require(type(args.assigned_at) is str, "--assigned-at is required for transition assignment")
            write_transition_assignment(root, assigned_at=args.assigned_at)
            print("WalkSafe FP046/NPC R002 transition assignment: WRITTEN")
            return 0
        if args.check_transition_assignment:
            _read_transition_assignment(root)
            print("WalkSafe FP046/NPC R002 transition assignment: VALIDATED")
            return 0
        if args.check_transition_review_result:
            validate_transition_review_result(root)
            print("WalkSafe FP046/NPC R002 transition review result: VALIDATED")
            return 0
        if args.write_transition_independent:
            write_transition_independent_review(root)
            print("WalkSafe FP046/NPC R002 transition independent review: WRITTEN")
            return 0
        if args.check_transition_post_review:
            validate_transition_post_review(root)
            print("WalkSafe FP046/NPC R002 transition post-review: VALIDATED")
            return 0
        plan = build_preflight(
            root,
            r029_gap_json=args.r029_gap_json,
            r029_gap_md=args.r029_gap_md,
            r029_backlog_json=args.r029_backlog_json,
            r029_backlog_md=args.r029_backlog_md,
            r029_discovery_json=args.r029_discovery_json,
            r029_discovery_md=args.r029_discovery_md,
        )
    except (BuildError, OSError, ValueError, KeyError) as exc:
        print(f"WalkSafe FP046/NPC R002 reopen preflight: FAIL: {exc}", file=sys.stderr)
        return 1
    if args.print_json:
        print(json_text(plan))
    else:
        print(
            "WalkSafe FP046/NPC R002 reopen preflight: PASS "
            "(PREFLIGHT_ONLY_NOT_AUTHORIZED; in-memory final-state projection "
            "only; no files written; seq72-76 prepared)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
