#!/usr/bin/env python3
"""Project the add-only FP-048 R002 supersession/readiness seq88-89 pair.

This module is deliberately projection-only.  It renders the new Goal and its
initial start-gate contract in memory, binds them to an exact live seq87/R030
source, and refuses every publication mode.  A later reviewed publisher owns
the checkpoint/catalog transaction.
"""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import re
import stat
import sys
import tomllib
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as continuation


CHECKPOINT_PATH = Path("docs/control/walksafe-project-continuation-checkpoint.json")
GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R002"
GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp048-encryption-connection-security-incident-r002.md"
)
WORK_ITEM_ID = "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
SUPERSEDED_GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R001"
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R002"
DEPENDENCY_GOAL_ID = "WS-GOAL-EPIC-03-FP-047-R001"
EPIC04_GOAL_ID = "WS-GOAL-EPIC-04"
EPIC12_GOAL_ID = "WS-GOAL-EPIC-12"
GAP_ID = "GAP-057"
SOURCE_POLICY_ID = "FP-048"

MATERIALIZED_SEQUENCE = 88
READY_SEQUENCE = 89
SUPERSEDED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-SUPERSEDED-FP048-R002-20260825-001"
)
MATERIALIZED_EVENT_ID = SUPERSEDED_EVENT_ID
READY_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP048-R002-20260825-001"
CONTRACT_PATH = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r001.json"
)
CONTRACT_CHECK_IDS = (
    "CONTINUATION",
    "GOAL_GRAPH",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "ROOT_FP048_R002_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)
LOCKED_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
CONTRACT_COMMANDS = {
    "CONTINUATION": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {LOCKED_PYTHON} -B "
        "scripts/check_walksafe_project_continuation_v2_4.py --root . "
        "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json"
    ),
    "GOAL_GRAPH": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {LOCKED_PYTHON} -B "
        "scripts/check_walksafe_goal_graph_v2_4.py --root . "
        "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json"
    ),
    "TEST_LAYER_REGISTRY_VALIDATE": (
        f"PYTHON_BIN={LOCKED_PYTHON} "
        "bash scripts/run_walksafe_test_layers_current.sh validate"
    ),
    "ROOT_FP048_R002_CONTROL_REGRESSION": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {LOCKED_PYTHON} -B "
        "-m pytest -p no:cacheprovider -q "
        "tests/test_apply_walksafe_fp048_r002_goal_seq88_89_20260825.py "
        "tests/test_walksafe_fp048_r002_goal_start_gate_20260825.py "
        "tests/test_apply_walksafe_fp048_r002_goal_started_seq90_20260825.py"
    ),
    "REPOSITORY_STATE": (
        ': "${WALKSAFE_GATE_EVENT_ID:?required}" && '
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {LOCKED_PYTHON} -B "
        "scripts/check_walksafe_project_continuation_v2_4.py "
        "--root . --checkpoint "
        "docs/control/walksafe-project-continuation-checkpoint.json "
        '--print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"'
    ),
}
CONTRACT_DOCUMENT_ID = (
    "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260825-001"
)
CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R001"
CONTRACT_VERSION = "2026-08-25.1"

SOURCE_READY_FRONTIER = (PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID)
READY_FRONTIER = (GOAL_ID, *SOURCE_READY_FRONTIER)
FOCUS_SOURCE = "IMPLEMENTATION_BACKLOG"
SCRIPT_PATH = Path("scripts/apply_walksafe_fp048_r002_goal_seq88_89_20260825.py")
TEST_PATH = Path("tests/test_apply_walksafe_fp048_r002_goal_seq88_89_20260825.py")
GATE_RUNNER_PATH = Path("scripts/run_walksafe_fp048_r002_goal_start_gate_20260825.py")
START_APPLY_PATH = Path("scripts/apply_walksafe_fp048_r002_goal_started_seq90_20260825.py")
GATE_TEST_PATH = Path("tests/test_walksafe_fp048_r002_goal_start_gate_20260825.py")
START_APPLY_TEST_PATH = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq90_20260825.py"
)
CONTROL_PATHS = (
    SCRIPT_PATH,
    TEST_PATH,
    GATE_RUNNER_PATH,
    START_APPLY_PATH,
    GATE_TEST_PATH,
    START_APPLY_TEST_PATH,
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

MATERIALIZED_FIELDS = {
    "sequence", "event_id", "event_type", "occurred_on", "occurred_at",
    "previous_focus_goal_id", "previous_focus_content_sha256", "focus_goal_id",
    "focus_goal_content_sha256", "subject_goal_id", "materialized_goal_id",
    "materialized_goal_path", "materialized_goal_content_sha256",
    "materialized_from_role", "materialized_from_path",
    "materialized_from_document_id", "materialized_from_sha256",
    "predecessor_goal_id", "predecessor_goal_content_sha256",
    "supersedes_goal_id", "supersedes_goal_content_sha256",
    "artifact_work_reason", "artifact_trigger_evidence_refs", "from_status",
    "to_status", "static_plan_manifest_sha256", "status_changes",
    "runtime_after", "blockers_after", "blocker_resolution_ids_after",
    "source_checkpoint_version", "source_checkpoint_binding", "evidence_refs",
    "reopen_trigger",
    "completion_evidence_by_goal_after",
    "archived_completion_evidence_by_goal_after",
    "canonical_binding_snapshot_after", "previous_event_sha256", "event_sha256",
}
READY_FIELDS = {
    "sequence", "event_id", "event_type", "occurred_on", "occurred_at",
    "previous_focus_goal_id", "previous_focus_content_sha256", "focus_goal_id",
    "focus_goal_content_sha256", "subject_goal_id", "from_status", "to_status",
    "static_plan_manifest_sha256", "status_changes", "runtime_after",
    "blockers_after", "blocker_resolution_ids_after", "source_checkpoint_version",
    "evidence_refs", "readiness_basis", "start_evidence_bindings",
    "start_evidence_provenance", "implementation_start_gate_contract_binding",
    "dynamic_goal_inventory_after", "materialized_child_goal_ids_by_parent_after",
    "canonical_binding_snapshot_after", "previous_event_sha256", "event_sha256",
}


class ProjectionError(RuntimeError):
    """The source or projected transition is not the exact authorized shape."""


@dataclass(frozen=True)
class SourceContext:
    checkpoint: Mapping[str, Any]
    checkpoint_bytes: bytes
    canonical_snapshot: Mapping[str, Mapping[str, Any]]
    backlog_binding: Mapping[str, Any]
    gap_binding: Mapping[str, Any]
    completion_role: str
    next_action: str
    source_overlay: Mapping[Path, bytes]
    superseded_goal_raw: bytes
    superseded_goal: Mapping[str, Any]
    predecessor_goal_raw: bytes
    predecessor_goal_sha256: str
    dependency_completion_sha256: str
    superseded_completion_sha256: str
    predecessor_completion_sha256: str


@dataclass(frozen=True)
class PreparedProjection:
    root: Path
    source_bytes: bytes
    source: Mapping[str, Any]
    projected: Mapping[str, Any]
    projected_bytes: bytes
    staged_outputs: Mapping[Path, bytes]
    materialized_event: Mapping[str, Any]
    ready_event: Mapping[str, Any]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ProjectionError(message)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_json_bytes(value: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")


def checkpoint_json_bytes(value: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")


def json_bytes(value: Mapping[str, Any]) -> bytes:
    """Compatibility name for canonical evidence/document JSON bytes."""

    return canonical_json_bytes(value)


def strict_json(raw: bytes, label: str) -> dict[str, Any]:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            require(key not in result, f"duplicate JSON key: {label}: {key}")
            result[key] = value
        return result

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicates)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ProjectionError(f"invalid JSON: {label}") from exc
    require(type(value) is dict, f"JSON root differs: {label}")
    return value


def _validated_root(root: Path) -> Path:
    root = Path(root)
    try:
        info = root.lstat()
    except OSError as exc:
        raise ProjectionError(f"root cannot be inspected: {root}") from exc
    require(stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode), "unsafe root")
    return root.resolve(strict=True)


def _safe_regular_bytes(root: Path, relative: Path) -> bytes:
    require(
        not relative.is_absolute()
        and bool(relative.parts)
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"unsafe path: {relative}",
    )
    cursor = root
    for index, part in enumerate(relative.parts):
        cursor /= part
        try:
            info = cursor.lstat()
        except OSError as exc:
            raise ProjectionError(f"required path is missing: {relative}") from exc
        require(not stat.S_ISLNK(info.st_mode), f"symlink path rejected: {relative}")
        if index < len(relative.parts) - 1:
            require(stat.S_ISDIR(info.st_mode), f"unsafe parent path: {relative}")
    require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, f"unsafe file: {relative}")
    return cursor.read_bytes()


def _input_bytes(
    root: Path,
    relative: Path,
    overlay: Mapping[Path, bytes],
) -> bytes:
    raw = overlay.get(relative)
    if raw is not None:
        require(type(raw) is bytes, f"overlay bytes differ: {relative}")
        return raw
    return _safe_regular_bytes(root, relative)


def _parse_goal(raw: bytes, label: str) -> tuple[dict[str, Any], str]:
    try:
        text = raw.decode("utf-8")
    except UnicodeError as exc:
        raise ProjectionError(f"Goal encoding differs: {label}") from exc
    parts = text.split("+++\n")
    require(len(parts) == 3 and parts[0] == "", f"Goal frontmatter differs: {label}")
    try:
        metadata = tomllib.loads(parts[1])
    except tomllib.TOMLDecodeError as exc:
        raise ProjectionError(f"Goal TOML differs: {label}") from exc
    require(type(metadata) is dict, f"Goal metadata differs: {label}")
    return metadata, parts[2]


def _goal_record(state: Mapping[str, Any], goal_id: str) -> Mapping[str, Any]:
    for field in ("dynamic_goal_inventory", "imported_predecessor_goal_bindings"):
        records = state.get(field)
        record = records.get(goal_id) if isinstance(records, Mapping) else None
        if isinstance(record, Mapping):
            return record
    raise ProjectionError(f"Goal binding is missing: {goal_id}")


def _completion_event_sha256(
    history: Sequence[Mapping[str, Any]], goal_id: str
) -> str:
    matches = [
        event
        for event in history
        if event.get("event_type") == "GOAL_COMPLETED"
        and event.get("subject_goal_id") == goal_id
        and isinstance(event.get("event_sha256"), str)
    ]
    require(len(matches) == 1, f"completion event differs: {goal_id}")
    event = matches[0]
    require(
        event["event_sha256"] == continuation.event_sha256(dict(event)),
        f"completion event seal differs: {goal_id}",
    )
    return str(event["event_sha256"])


def _completion_event_occurred_at(
    history: Sequence[Mapping[str, Any]], event_sha256: str
) -> str:
    matches = [
        event
        for event in history
        if event.get("event_sha256") == event_sha256
        and isinstance(event.get("occurred_at"), str)
    ]
    require(len(matches) == 1, "completion event time provenance differs")
    return str(matches[0]["occurred_at"])


def _binding_document(
    root: Path,
    binding: Mapping[str, Any],
    overlay: Mapping[Path, bytes],
    label: str,
) -> tuple[dict[str, Any], bytes]:
    relative = Path(str(binding.get("path", "")))
    raw = _input_bytes(root, relative, overlay)
    require(
        SHA256_RE.fullmatch(str(binding.get("file_sha256", ""))) is not None
        and sha256_bytes(raw) == binding.get("file_sha256"),
        f"{label} live binding differs",
    )
    return strict_json(raw, label), raw


def _source_context(
    root: Path,
    source_bytes: bytes,
    source_overlay: Mapping[Path, bytes] | None = None,
    *,
    source_checkpoint_sha256: str,
    source_r030_gap_sha256: str,
    source_r030_backlog_sha256: str,
) -> SourceContext:
    root = _validated_root(root)
    overlay = {
        Path(path): raw for path, raw in (source_overlay or {}).items()
    }
    source = strict_json(source_bytes, CHECKPOINT_PATH.as_posix())
    require(
        checkpoint_json_bytes(source) == source_bytes,
        "source checkpoint insertion-order round trip differs",
    )
    require(
        canonical_json_bytes(source) != source_bytes,
        "source checkpoint sorted-key historical rewrite differs",
    )
    for label, digest in (
        ("source checkpoint", source_checkpoint_sha256),
        ("source R030 gap", source_r030_gap_sha256),
        ("source R030 backlog", source_r030_backlog_sha256),
    ):
        require(SHA256_RE.fullmatch(digest) is not None, f"{label} pin is invalid")
    require(
        sha256_bytes(source_bytes) == source_checkpoint_sha256,
        "source checkpoint explicit SHA-256 pin differs",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, Mapping) else None
    require(
        isinstance(history, list)
        and len(history) == READY_SEQUENCE - 2
        and all(isinstance(event, dict) for event in history),
        "source is not exact seq87",
    )
    update = history[-2]
    completion = history[-1]
    require(
        update.get("sequence") == 86
        and update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and update.get("produced_by_goal_id") == PREDECESSOR_GOAL_ID
        and update.get("event_sha256") == continuation.event_sha256(update),
        "source seq86 producer/seal differs",
    )
    require(
        completion.get("sequence") == 87
        and completion.get("event_type") == "GOAL_COMPLETED"
        and completion.get("subject_goal_id") == PREDECESSOR_GOAL_ID
        and completion.get("previous_event_sha256") == update.get("event_sha256")
        and completion.get("canonical_update_event_sha256") == update.get("event_sha256")
        and completion.get("event_sha256") == continuation.event_sha256(completion)
        and state.get("transition_history_anchor_sha256") == completion.get("event_sha256"),
        "source seq87 completion/seal differs",
    )
    statuses = state.get("status_by_goal")
    require(
        isinstance(statuses, Mapping)
        and statuses.get(PREDECESSOR_GOAL_ID) == "COMPLETE_AT_TARGET"
        and statuses.get(SUPERSEDED_GOAL_ID) == "COMPLETE_AT_TARGET"
        and statuses.get(PARENT_GOAL_ID) == "READY"
        and GOAL_ID not in statuses
        and "IN_PROGRESS" not in statuses.values()
        and tuple(state.get("ready_frontier_goal_ids", ())) == SOURCE_READY_FRONTIER,
        "source seq87 Goal status/frontier differs",
    )
    require(
        state.get("focus_goal_id") == PARENT_GOAL_ID
        and state.get("pending_producer_completion_goal_id") in {None, ""},
        "source seq87 focus/producer differs",
    )
    canonical = continuation.canonical_binding_snapshot(source)
    gap_binding = canonical.get("IMPLEMENTATION_GAP")
    backlog_binding = canonical.get("IMPLEMENTATION_BACKLOG")
    require(
        isinstance(gap_binding, Mapping)
        and isinstance(backlog_binding, Mapping),
        "source R030 canonical bindings are missing",
    )
    gap, _gap_raw = _binding_document(root, gap_binding, overlay, "R030 gap")
    backlog, _backlog_raw = _binding_document(
        root, backlog_binding, overlay, "R030 backlog"
    )
    require(
        gap_binding.get("file_sha256") == source_r030_gap_sha256
        and backlog_binding.get("file_sha256") == source_r030_backlog_sha256,
        "source R030 explicit SHA-256 pins differ from canonical bindings",
    )
    require(
        str(gap.get("metadata", {}).get("report_id", "")).endswith("-030")
        and gap.get("metadata", {}).get("report_id") == gap_binding.get("document_id")
        and str(backlog.get("metadata", {}).get("backlog_id", "")).endswith("-030")
        and backlog.get("metadata", {}).get("backlog_id")
        == backlog_binding.get("document_id"),
        "source canonical documents are not R030",
    )
    assessment = [
        row
        for row in gap.get("assessments", [])
        if isinstance(row, Mapping)
        and row.get("source_policy_id") == SOURCE_POLICY_ID
        and row.get("gap_id") == GAP_ID
    ]
    next_action = backlog.get("next_single_action")
    require(
        len(assessment) == 1
        and isinstance(next_action, Mapping)
        and next_action.get("source_policy_id") == SOURCE_POLICY_ID
        and next_action.get("gap_id") == GAP_ID
        and next_action.get("goal_id") == GOAL_ID
        and next_action.get("work_item_id") == WORK_ITEM_ID
        and next_action.get("status") == "PLANNED_NEXT",
        "R030 GAP-057 next-single-action identity differs",
    )
    action = next_action.get("action")
    require(
        isinstance(action, str)
        and action.strip() == action
        and "\n" not in action
        and "7종 state rotation" in action
        and "all-or-nothing" in action,
        "R030 GAP-057 next-single-action text differs",
    )
    completion_roles = sorted(
        role
        for role in canonical
        if role == f"WORK_ITEM_COMPLETION::{PREDECESSOR_GOAL_ID}"
    )
    require(len(completion_roles) == 1, "FP046 R002 completion role differs")
    completion_role = completion_roles[0]
    require(
        completion_role in completion.get("evidence_refs", [])
        and canonical == completion.get("canonical_binding_snapshot_after"),
        "seq87 canonical completion evidence differs",
    )

    superseded_record = _goal_record(state, SUPERSEDED_GOAL_ID)
    superseded_path = Path(str(superseded_record.get("path", "")))
    superseded_raw = _input_bytes(root, superseded_path, overlay)
    superseded_goal, _body = _parse_goal(superseded_raw, SUPERSEDED_GOAL_ID)
    require(
        superseded_goal.get("goal_id") == SUPERSEDED_GOAL_ID
        and superseded_goal.get("work_item_id") == WORK_ITEM_ID
        and superseded_record.get("sha256") == sha256_bytes(superseded_raw),
        "FP048 R001 live Goal binding differs",
    )
    predecessor_record = _goal_record(state, PREDECESSOR_GOAL_ID)
    predecessor_path = Path(str(predecessor_record.get("path", "")))
    predecessor_raw = _input_bytes(root, predecessor_path, overlay)
    predecessor_sha256 = sha256_bytes(predecessor_raw)
    require(
        predecessor_record.get("sha256") == predecessor_sha256,
        "FP046 R002 live Goal binding differs",
    )
    dependency_completion = _completion_event_sha256(history, DEPENDENCY_GOAL_ID)
    superseded_completion = _completion_event_sha256(history, SUPERSEDED_GOAL_ID)
    predecessor_completion = _completion_event_sha256(history, PREDECESSOR_GOAL_ID)
    require(
        predecessor_completion == completion.get("event_sha256"),
        "FP046 R002 predecessor completion provenance differs",
    )
    impact = update.get("impact_disposition_by_goal")
    changed_subjects = update.get("changed_subject_ids_by_role")
    reopened = update.get("reopened_completion_event_sha256_by_goal")
    require(
        isinstance(impact, Mapping)
        and impact.get(SUPERSEDED_GOAL_ID)
        == {"result": "REOPEN_REQUIRED", "target_status": "SUPERSEDED"}
        and isinstance(changed_subjects, Mapping)
        and SOURCE_POLICY_ID
        in changed_subjects.get("IMPLEMENTATION_BACKLOG", [])
        and SOURCE_POLICY_ID in changed_subjects.get("IMPLEMENTATION_GAP", [])
        and GAP_ID in changed_subjects.get("IMPLEMENTATION_GAP", [])
        and isinstance(reopened, Mapping)
        and reopened.get(SUPERSEDED_GOAL_ID) == superseded_completion,
        "seq86 FP048 R001 canonical reopen impact differs",
    )
    return SourceContext(
        checkpoint=source,
        checkpoint_bytes=source_bytes,
        canonical_snapshot=canonical,
        backlog_binding=copy.deepcopy(dict(backlog_binding)),
        gap_binding=copy.deepcopy(dict(gap_binding)),
        completion_role=completion_role,
        next_action=action,
        source_overlay=overlay,
        superseded_goal_raw=superseded_raw,
        superseded_goal=superseded_goal,
        predecessor_goal_raw=predecessor_raw,
        predecessor_goal_sha256=predecessor_sha256,
        dependency_completion_sha256=dependency_completion,
        superseded_completion_sha256=superseded_completion,
        predecessor_completion_sha256=predecessor_completion,
    )


def _toml_value(value: Any) -> str:
    require(
        isinstance(value, (str, int, bool, list)),
        "unsupported Goal frontmatter value",
    )
    return json.dumps(value, ensure_ascii=False)


def _render_goal(context: SourceContext) -> tuple[bytes, dict[str, Any]]:
    old = context.superseded_goal
    require(
        old.get("parent_goal_id") == PARENT_GOAL_ID
        and old.get("work_item_type") == "POLICY_GAP_WORK"
        and old.get("source_policy_ids") == [SOURCE_POLICY_ID]
        and old.get("gap_ids") == [GAP_ID]
        and old.get("start_requires") == [DEPENDENCY_GOAL_ID]
        and old.get("completion_requires") == [DEPENDENCY_GOAL_ID],
        "FP048 R001 semantic scope differs",
    )
    reopen_refs: list[str] = []
    metadata: dict[str, Any] = {
        "schema_version": "2.0",
        "goal_id": GOAL_ID,
        "goal_kind": "WORK_ITEM",
        "document_version": "2.3.0",
        "parent_goal_id": PARENT_GOAL_ID,
        "work_item_type": old["work_item_type"],
        "priority_rank": old["priority_rank"],
        "initial_status": "PLANNED",
        "target_completion_level": old["target_completion_level"],
        "work_item_id": old["work_item_id"],
        "start_requires": [DEPENDENCY_GOAL_ID],
        "completion_requires": [DEPENDENCY_GOAL_ID],
        "child_goal_ids": [],
        "source_policy_ids": copy.deepcopy(old["source_policy_ids"]),
        "gap_ids": copy.deepcopy(old["gap_ids"]),
        "canonical_input_roles": copy.deepcopy(old["canonical_input_roles"]),
        "question_policy": old["question_policy"],
        "stop_policy": old["stop_policy"],
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": context.backlog_binding["path"],
        "materialized_from_document_id": context.backlog_binding["document_id"],
        "materialized_from_sha256": context.backlog_binding["file_sha256"],
        "predecessor_goal_id": PREDECESSOR_GOAL_ID,
        "predecessor_goal_content_sha256": context.predecessor_goal_sha256,
        "supersedes_goal_id": SUPERSEDED_GOAL_ID,
        "supersedes_goal_content_sha256": sha256_bytes(
            context.superseded_goal_raw
        ),
        "reopen_reason": "CANONICAL_INPUT_CHANGED",
        "reopen_evidence_refs": reopen_refs,
        "artifact_work_reason": old.get("artifact_work_reason", ""),
        "artifact_trigger_evidence_refs": copy.deepcopy(
            old.get("artifact_trigger_evidence_refs", [])
        ),
    }
    order = tuple(metadata)
    frontmatter = "\n".join(
        f"{key} = {_toml_value(metadata[key])}" for key in order
    )
    body = f"""# FP-048 Gateway 암호화 상태 회전 R002

## 목표

R030의 다음 단일 행동인 `{context.next_action}`을 정확히 이어받아, Gateway 암호화 상태 7종의 lock/read/classify/schema preflight가 모두 성공한 뒤에만 기존 파일별 atomic replace 회전을 시작하도록 한다.

## 정책 기준

- 대상은 `short-session`, `field-long-session`, `field-walk-ledger`, `privacy-rights-ledger`, `privacy-deletion-v2`, `integrated-consent`, `server-capacity` 정확히 7종이다.
- 7개 파일의 lock/read/classify/schema preflight가 모두 끝나기 전에는 mutation을 0으로 유지한다.
- 하나라도 corrupt·unknown·lock·schema 실패면 7개 모두 mutation 0으로 fail-fast한다.

## 구현 범위

- 정확한 7종 파일 inventory와 schema/classification 계약
- 전 파일 lock/read/classify/schema 선행 preflight
- corrupt·unknown·lock·schema 실패 시 전 파일 mutation 0
- 모두 valid일 때 기존 파일별 atomic replace 회전
- 해당 상태 회전 집중 회귀와 GAP-057 내부 재평가

## 성공 기준

1. exact 7종 모두의 preflight 완료 전 mutation 수가 0이다.
2. 하나의 corrupt·unknown·lock·schema 실패도 7종 전체 mutation 수 0으로 종료된다.
3. 모두 valid일 때만 기존 파일별 atomic replace 경로가 시작된다.
4. 프로세스 crash 전체에 대한 cohort-atomic rollback은 완료 claim에 포함하지 않는다.
5. GAP-057은 저장소 내부 결과만 재평가하고 정식·실기기·외부·운영·출시 상태를 올리지 않는다.

## 계획 검증

- exact 7종 inventory와 정상 preflight 집중 회귀
- 각 corrupt·unknown·lock·schema fault의 전 파일 mutation 0 회귀
- 모두 valid일 때 기존 파일별 atomic replace 진입 회귀

## 제외 범위

- 실제 운영 키·비밀값·인증서 발급이나 회전
- Nginx·프로세스 재시작, capacity collector, deletion worker 변경
- readiness·health·telemetry, backup/restore 구현·운영
- crash-atomic 7파일 cohort rollback claim
- 정식 시험, 실기기, 외부 보안·법률 검토, 배포와 출시 승인

## 완료 경계

목표 완료 수준은 `{metadata['target_completion_level']}`이며 저장소 내부 구현·자동 검증·GAP-057 재평가까지만 포함한다. 외부·운영·배포·출시 credit은 `NOT_RUN` 또는 `0`을 유지한다.
"""
    raw = f"+++\n{frontmatter}\n+++\n{body}".encode("utf-8")
    parsed, _ = _parse_goal(raw, GOAL_ID)
    require(parsed == metadata, "rendered FP048 R002 Goal differs")
    invariant_fields = goal_graph.frozen_goal.SUCCESSOR_INVARIANT_FIELDS
    require(
        all(parsed.get(field) == old.get(field) for field in invariant_fields),
        "FP048 R002 successor invariant differs",
    )
    return raw, metadata


def _render_contract(
    context: SourceContext, goal_raw: bytes
) -> tuple[bytes, dict[str, Any], dict[str, Any]]:
    contract = {
        "schema_version": "1.0",
        "document_id": CONTRACT_DOCUMENT_ID,
        "contract_id": CONTRACT_ID,
        "contract_version": CONTRACT_VERSION,
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": sha256_bytes(goal_raw),
        "gate_purpose": "INITIAL_START",
        "ordered_checks": [
            {"check_id": check_id, "command": CONTRACT_COMMANDS[check_id]}
            for check_id in CONTRACT_CHECK_IDS
        ],
    }
    raw = canonical_json_bytes(contract)
    binding = {
        "schema_version": contract["schema_version"],
        "document_id": contract["document_id"],
        "contract_id": contract["contract_id"],
        "contract_version": contract["contract_version"],
        "path": CONTRACT_PATH.as_posix(),
        "file_sha256": sha256_bytes(raw),
        "canonical_contract_sha256": continuation.canonical_json_sha256(contract),
    }
    return raw, contract, binding


def _inventory_record(
    context: SourceContext,
    goal_raw: bytes,
    metadata: Mapping[str, Any],
    event_sha256: str,
) -> dict[str, Any]:
    return {
        "goal_id": GOAL_ID,
        "path": GOAL_PATH.as_posix(),
        "sha256": sha256_bytes(goal_raw),
        "goal_kind": "WORK_ITEM",
        "work_item_type": metadata["work_item_type"],
        "parent_goal_id": PARENT_GOAL_ID,
        "materialized_from_role": metadata["materialized_from_role"],
        "materialized_from_path": metadata["materialized_from_path"],
        "materialized_from_document_id": metadata["materialized_from_document_id"],
        "materialized_from_sha256": metadata["materialized_from_sha256"],
        "predecessor_goal_id": PREDECESSOR_GOAL_ID,
        "predecessor_goal_content_sha256": context.predecessor_goal_sha256,
        "supersedes_goal_id": SUPERSEDED_GOAL_ID,
        "supersedes_goal_content_sha256": sha256_bytes(
            context.superseded_goal_raw
        ),
        "artifact_work_reason": metadata["artifact_work_reason"],
        "artifact_trigger_evidence_refs": copy.deepcopy(
            metadata["artifact_trigger_evidence_refs"]
        ),
        "initial_status": "PLANNED",
        "materialized_event_sha256": event_sha256,
    }


def _nodes_with_goal(
    root: Path,
    state: Mapping[str, Any],
    metadata: Mapping[str, Any],
) -> dict[str, dict[str, Any]]:
    errors, nodes = goal_graph.frozen_goal.current_goal_nodes(root, dict(state))
    require(not errors, "source Goal node derivation failed: " + "; ".join(errors))
    require(GOAL_ID not in nodes, "FP048 R002 Goal already exists")
    nodes[GOAL_ID] = copy.deepcopy(dict(metadata))
    return nodes


def _queue_boundary(
    root: Path,
    checkpoint: Mapping[str, Any],
    nodes: Mapping[str, Mapping[str, Any]],
    statuses: Mapping[str, str],
    frontier: Sequence[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    state = checkpoint["goal_execution"]
    bindings = goal_graph.frozen_goal.canonical_binding_map(dict(checkpoint))
    register_binding = bindings.get("ARTIFACT_REGISTER")
    require(isinstance(register_binding, dict), "ARTIFACT_REGISTER binding is missing")
    register_path = Path(str(register_binding.get("path", "")))
    register = strict_json(_safe_regular_bytes(root, register_path), "ARTIFACT_REGISTER")
    queue_errors, queue = goal_graph.derive_v24_artifact_work_queue_from_register(
        register_binding, register, dict(nodes), dict(statuses)
    )
    require(not queue_errors, "artifact queue derivation failed: " + "; ".join(queue_errors))
    boundary_errors, boundary = goal_graph.frozen_goal.derive_completion_boundary(
        dict(nodes),
        dict(statuses),
        list(frontier),
        state["blockers_by_goal"],
        queue,
        package_status=state["package_status"],
    )
    require(
        not boundary_errors,
        "completion boundary derivation failed: " + "; ".join(boundary_errors),
    )
    return queue, boundary


def _runtime(
    state: Mapping[str, Any],
    *,
    focus_id: str,
    focus_path: str,
    focus_work_item_id: str,
    focus_source: str,
    frontier: Sequence[str],
    queue: Mapping[str, Any],
    boundary: Mapping[str, Any],
) -> dict[str, Any]:
    return {
        "focus_goal_id": focus_id,
        "focus_goal_path": focus_path,
        "focus_work_item_id": focus_work_item_id,
        "focus_source": focus_source,
        "ready_frontier_goal_ids": list(frontier),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": continuation.canonical_json_sha256(dict(queue)),
        "completion_boundary_sha256": continuation.canonical_json_sha256(
            dict(boundary)
        ),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def _snapshot_hashes(
    root: Path,
    paths: Sequence[str],
    overlay: Mapping[Path, bytes],
) -> tuple[str, str]:
    normalized = sorted(paths)
    require(len(normalized) == len(set(normalized)), "snapshot path duplicate")
    path_sha256 = sha256_bytes(("\n".join(normalized) + "\n").encode("utf-8"))
    content = hashlib.sha256()
    for relative_text in normalized:
        relative = Path(relative_text)
        raw = _input_bytes(root, relative, overlay)
        content.update(relative_text.encode("utf-8"))
        content.update(b"\0")
        content.update(sha256_bytes(raw).encode("ascii"))
        content.update(b"\n")
    return path_sha256, content.hexdigest()


def _event_common(
    source: Mapping[str, Any],
    *,
    sequence: int,
    event_id: str,
    event_type: str,
    occurred_at: datetime,
    previous: Mapping[str, Any],
    focus_goal_id: str,
    focus_goal_sha256: str,
    from_status: str,
    to_status: str,
    status_changes: Mapping[str, str],
    runtime_after: Mapping[str, Any],
    evidence_refs: Sequence[str],
) -> dict[str, Any]:
    state = source["goal_execution"]
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
        "from_status": from_status,
        "to_status": to_status,
        "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
        "status_changes": dict(status_changes),
        "runtime_after": copy.deepcopy(dict(runtime_after)),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": list(evidence_refs),
        "canonical_binding_snapshot_after": continuation.canonical_binding_snapshot(
            dict(source)
        ),
        "previous_event_sha256": previous["event_sha256"],
    }


def _project(context: SourceContext, root: Path) -> PreparedProjection:
    source = context.checkpoint
    source_state = source["goal_execution"]
    for relative in (GOAL_PATH, CONTRACT_PATH):
        target = root / relative
        require(
            not target.exists() and not target.is_symlink(),
            f"add-only staged target already exists: {relative}",
        )
    goal_raw, metadata = _render_goal(context)
    contract_raw, _contract, contract_binding = _render_contract(context, goal_raw)
    staged_outputs = {GOAL_PATH: goal_raw, CONTRACT_PATH: contract_raw}
    overlay = {**context.source_overlay, **staged_outputs}
    checkpoint = copy.deepcopy(dict(source))
    state = checkpoint["goal_execution"]
    statuses = copy.deepcopy(state["status_by_goal"])
    nodes = _nodes_with_goal(root, source_state, metadata)
    parent_record = _goal_record(source_state, PARENT_GOAL_ID)
    parent_sha256 = str(parent_record["sha256"])
    parent_path = str(parent_record["path"])

    statuses[SUPERSEDED_GOAL_ID] = "SUPERSEDED"
    statuses[GOAL_ID] = "PLANNED"
    queue88, boundary88 = _queue_boundary(
        root, source, nodes, statuses, SOURCE_READY_FRONTIER
    )
    runtime88 = _runtime(
        source_state,
        focus_id=PARENT_GOAL_ID,
        focus_path=parent_path,
        focus_work_item_id="",
        focus_source="WORKSTREAM_GRAPH",
        frontier=SOURCE_READY_FRONTIER,
        queue=queue88,
        boundary=boundary88,
    )
    source_tail = source_state["transition_history"][-1]
    source_at = datetime.fromisoformat(source_tail["occurred_at"])
    event88 = _event_common(
        source,
        sequence=MATERIALIZED_SEQUENCE,
        event_id=MATERIALIZED_EVENT_ID,
        event_type="GOAL_SUPERSEDED",
        occurred_at=source_at + timedelta(seconds=1),
        previous=source_tail,
        focus_goal_id=PARENT_GOAL_ID,
        focus_goal_sha256=parent_sha256,
        from_status="COMPLETE_AT_TARGET",
        to_status="SUPERSEDED",
        status_changes={
            SUPERSEDED_GOAL_ID: "SUPERSEDED",
            GOAL_ID: "PLANNED",
        },
        runtime_after=runtime88,
        evidence_refs=[],
    )
    event88.update(
        {
            "subject_goal_id": SUPERSEDED_GOAL_ID,
            "materialized_goal_id": GOAL_ID,
            "materialized_goal_path": GOAL_PATH.as_posix(),
            "materialized_goal_content_sha256": sha256_bytes(goal_raw),
            "materialized_from_role": metadata["materialized_from_role"],
            "materialized_from_path": metadata["materialized_from_path"],
            "materialized_from_document_id": metadata[
                "materialized_from_document_id"
            ],
            "materialized_from_sha256": metadata["materialized_from_sha256"],
            "predecessor_goal_id": PREDECESSOR_GOAL_ID,
            "predecessor_goal_content_sha256": context.predecessor_goal_sha256,
            "supersedes_goal_id": SUPERSEDED_GOAL_ID,
            "supersedes_goal_content_sha256": sha256_bytes(
                context.superseded_goal_raw
            ),
            "artifact_work_reason": metadata["artifact_work_reason"],
            "artifact_trigger_evidence_refs": copy.deepcopy(
                metadata["artifact_trigger_evidence_refs"]
            ),
            "source_checkpoint_binding": {
                "path": CHECKPOINT_PATH.as_posix(),
                "sequence": 87,
                "sha256": sha256_bytes(context.checkpoint_bytes),
                "byte_length": len(context.checkpoint_bytes),
                "tail_event_sha256": context.predecessor_completion_sha256,
            },
            "reopen_trigger": {
                "canonical_update_event_sha256": source_state[
                    "transition_history"
                ][-2]["event_sha256"],
                "target_completion_event_sha256": (
                    context.superseded_completion_sha256
                ),
                "target_completion_occurred_at": _completion_event_occurred_at(
                    source_state["transition_history"],
                    context.superseded_completion_sha256,
                ),
                "decided_at": source_state["transition_history"][-2][
                    "occurred_at"
                ],
            },
        }
    )
    completed = copy.deepcopy(state["completion_evidence_by_goal"])
    archived = copy.deepcopy(state["archived_completion_evidence_by_goal"])
    require(
        SUPERSEDED_GOAL_ID in completed
        and SUPERSEDED_GOAL_ID not in archived,
        "FP048 R001 completion archive source differs",
    )
    archived[SUPERSEDED_GOAL_ID] = completed.pop(SUPERSEDED_GOAL_ID)
    event88["completion_evidence_by_goal_after"] = copy.deepcopy(completed)
    event88["archived_completion_evidence_by_goal_after"] = copy.deepcopy(archived)
    event88["event_sha256"] = continuation.event_sha256(event88)

    inventory = copy.deepcopy(state["dynamic_goal_inventory"])
    inventory[GOAL_ID] = _inventory_record(
        context, goal_raw, metadata, event88["event_sha256"]
    )
    children = copy.deepcopy(state["materialized_child_goal_ids_by_parent"])
    members = children.get(PARENT_GOAL_ID)
    require(
        isinstance(members, list)
        and GOAL_ID not in members
        and SUPERSEDED_GOAL_ID in members,
        "EPIC-03 materialized child map differs",
    )
    children[PARENT_GOAL_ID] = [*members, GOAL_ID]
    statuses[GOAL_ID] = "READY"
    queue89, boundary89 = _queue_boundary(
        root, source, nodes, statuses, READY_FRONTIER
    )
    runtime89 = _runtime(
        source_state,
        focus_id=GOAL_ID,
        focus_path=GOAL_PATH.as_posix(),
        focus_work_item_id=WORK_ITEM_ID,
        focus_source=FOCUS_SOURCE,
        frontier=READY_FRONTIER,
        queue=queue89,
        boundary=boundary89,
    )
    event89 = _event_common(
        source,
        sequence=READY_SEQUENCE,
        event_id=READY_EVENT_ID,
        event_type="GOAL_READY",
        occurred_at=source_at + timedelta(seconds=2),
        previous=event88,
        focus_goal_id=GOAL_ID,
        focus_goal_sha256=sha256_bytes(goal_raw),
        from_status="PLANNED",
        to_status="READY",
        status_changes={GOAL_ID: "READY"},
        runtime_after=runtime89,
        evidence_refs=["IMPLEMENTATION_GAP"],
    )
    event89.update(
        {
            "subject_goal_id": GOAL_ID,
            "readiness_basis": {
                "dependency_completion_events": [
                    {
                        "goal_id": DEPENDENCY_GOAL_ID,
                        "event_sha256": context.dependency_completion_sha256,
                    }
                ],
                "superseded_goal_id": SUPERSEDED_GOAL_ID,
                "superseded_completion_event_sha256": (
                    context.superseded_completion_sha256
                ),
                "predecessor_goal_id": PREDECESSOR_GOAL_ID,
                "predecessor_completion_event_sha256": (
                    context.predecessor_completion_sha256
                ),
            },
            "start_evidence_bindings": {},
            "start_evidence_provenance": {},
            "implementation_start_gate_contract_binding": contract_binding,
            "dynamic_goal_inventory_after": copy.deepcopy(inventory),
            "materialized_child_goal_ids_by_parent_after": copy.deepcopy(children),
        }
    )
    event89["event_sha256"] = continuation.event_sha256(event89)

    state["status_by_goal"] = statuses
    state["completion_evidence_by_goal"] = completed
    state["archived_completion_evidence_by_goal"] = archived
    state["dynamic_goal_inventory"] = inventory
    state["materialized_child_goal_ids_by_parent"] = children
    state["transition_history"].extend([event88, event89])
    state["transition_history_anchor_sha256"] = event89["event_sha256"]
    state["validation_cutoff_at"] = event89["occurred_at"]
    state["focus_goal_id"] = GOAL_ID
    state["focus_goal_path"] = GOAL_PATH.as_posix()
    state["focus_work_item_id"] = WORK_ITEM_ID
    state["focus_source"] = FOCUS_SOURCE
    state["ready_frontier_goal_ids"] = list(READY_FRONTIER)
    state["artifact_work_queue"] = queue89
    state["completion_boundary"] = boundary89
    state["goal_status"] = "READY"
    state["pending_reopen_goal_ids"] = []
    state["pending_producer_completion_goal_id"] = None
    state["goal_document_paths"] = sorted(
        [*state["goal_document_paths"], GOAL_PATH.as_posix()]
    )
    state["goal_document_count"] = len(state["goal_document_paths"])
    state["managed_goal_paths"] = sorted(
        set(state["managed_goal_paths"]) | {GOAL_PATH.as_posix()}
    )
    state["managed_goal_path_count"] = len(state["managed_goal_paths"])
    state["path_set_sha256"], state["content_set_sha256"] = _snapshot_hashes(
        root, state["managed_goal_paths"], overlay
    )

    current = checkpoint["current_work"]
    current.update(
        {
            "status": "READY",
            "current_focus": (
                "FP-048 R002/GAP-057 READY; initial five-check start gate NOT_RUN"
            ),
            "gap_ids": [GAP_ID],
            "next_action": context.next_action,
            "release_completion_claimed": False,
            "source_policy_ids": [SOURCE_POLICY_ID],
            "target_completion_level": metadata["target_completion_level"],
            "title": "FP-048 Gateway 7-state rotation R002",
            "work_item_id": WORK_ITEM_ID,
        }
    )
    snapshot = checkpoint["working_tree_snapshot"]
    additions = {GOAL_PATH, CONTRACT_PATH, *CONTROL_PATHS}
    managed = sorted(
        set(snapshot["managed_changed_paths"])
        | {path.as_posix() for path in additions}
    )
    snapshot["managed_changed_paths"] = managed
    snapshot["managed_changed_path_count"] = len(managed)
    snapshot["path_set_sha256"], snapshot["content_set_sha256"] = _snapshot_hashes(
        root, managed, overlay
    )
    snapshot["scope"] = (
        "Graph v2.4 through FP048-R002/GAP-057 GOAL_READY seq89; initial "
        "five-check start gate, product work, formal, device, external, "
        "deployment, approval, and release credit remain NOT_RUN."
    )
    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = managed
    handoff["current_epic"] = "EPIC-03 / FP-048 R002/GAP-057 READY_NOT_STARTED"
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = (
        "PASS_WITH_FP048_R002_READY_START_GATE_NOT_RUN"
    )
    handoff["next_single_action"] = context.next_action
    mirror = handoff["source_commit_or_snapshot"]
    mirror["file_count"] = len(managed)
    mirror["path_set_sha256"] = snapshot["path_set_sha256"]
    mirror["content_set_sha256"] = snapshot["content_set_sha256"]

    prepared = PreparedProjection(
        root=root,
        source_bytes=context.checkpoint_bytes,
        source=source,
        projected=checkpoint,
        projected_bytes=checkpoint_json_bytes(checkpoint),
        staged_outputs=staged_outputs,
        materialized_event=event88,
        ready_event=event89,
    )
    require(
        prepared.projected_bytes == checkpoint_json_bytes(checkpoint)
        and prepared.projected_bytes != canonical_json_bytes(checkpoint),
        "projected checkpoint insertion-order serialization differs",
    )
    validate_projection(prepared, context)
    return prepared


def validate_projection(
    prepared: PreparedProjection, context: SourceContext
) -> None:
    source = prepared.source
    projected = prepared.projected
    before = source["goal_execution"]
    after = projected["goal_execution"]
    event88 = prepared.materialized_event
    event89 = prepared.ready_event
    require(set(event88) == MATERIALIZED_FIELDS, "seq88 field set differs")
    require(set(event89) == READY_FIELDS, "seq89 field set differs")
    require(
        event88.get("sequence") == MATERIALIZED_SEQUENCE
        and event88.get("event_id") == MATERIALIZED_EVENT_ID
        and event88.get("event_type") == "GOAL_SUPERSEDED"
        and event88.get("subject_goal_id") == SUPERSEDED_GOAL_ID
        and event88.get("materialized_goal_id") == GOAL_ID
        and event88.get("from_status") == "COMPLETE_AT_TARGET"
        and event88.get("to_status") == "SUPERSEDED"
        and event88.get("status_changes")
        == {SUPERSEDED_GOAL_ID: "SUPERSEDED", GOAL_ID: "PLANNED"}
        and event88.get("previous_event_sha256")
        == context.predecessor_completion_sha256
        and event88.get("reopen_trigger")
        == {
            "canonical_update_event_sha256": before["transition_history"][-2][
                "event_sha256"
            ],
            "target_completion_event_sha256": (
                context.superseded_completion_sha256
            ),
            "target_completion_occurred_at": _completion_event_occurred_at(
                before["transition_history"],
                context.superseded_completion_sha256,
            ),
            "decided_at": before["transition_history"][-2]["occurred_at"],
        }
        and event88.get("event_sha256") == continuation.event_sha256(dict(event88)),
        "seq88 supersession differs",
    )
    require(
        event89.get("sequence") == READY_SEQUENCE
        and event89.get("event_id") == READY_EVENT_ID
        and event89.get("event_type") == "GOAL_READY"
        and event89.get("subject_goal_id") == GOAL_ID
        and event89.get("from_status") == "PLANNED"
        and event89.get("to_status") == "READY"
        and event89.get("status_changes") == {GOAL_ID: "READY"}
        and event89.get("previous_event_sha256") == event88.get("event_sha256")
        and event89.get("event_sha256") == continuation.event_sha256(dict(event89)),
        "seq89 readiness differs",
    )
    require(
        after["transition_history"][:-2] == before["transition_history"]
        and after["transition_history"][-2:] == [event88, event89]
        and after["transition_history_anchor_sha256"] == event89["event_sha256"],
        "seq88/89 history is not add-only",
    )
    inventory = after["dynamic_goal_inventory"]
    children = after["materialized_child_goal_ids_by_parent"]
    require(
        GOAL_ID not in before["dynamic_goal_inventory"]
        and inventory[GOAL_ID]["sha256"]
        == sha256_bytes(prepared.staged_outputs[GOAL_PATH])
        and inventory[GOAL_ID]["materialized_event_sha256"]
        == event88["event_sha256"]
        and event89["dynamic_goal_inventory_after"] == inventory
        and event89["materialized_child_goal_ids_by_parent_after"] == children,
        "FP048 R002 inventory projection differs",
    )
    require(
        after["status_by_goal"][SUPERSEDED_GOAL_ID] == "SUPERSEDED"
        and after["status_by_goal"][GOAL_ID] == "READY"
        and "IN_PROGRESS" not in after["status_by_goal"].values()
        and after["focus_goal_id"] == GOAL_ID
        and tuple(after["ready_frontier_goal_ids"]) == READY_FRONTIER,
        "FP048 R002 final status/focus differs",
    )
    require(
        projected["session_handoff"].get("last_updated_by_work_item")
        == WORK_ITEM_ID,
        "FP048 R002 handoff work-item identity differs",
    )
    require(
        SUPERSEDED_GOAL_ID not in after["completion_evidence_by_goal"]
        and after["archived_completion_evidence_by_goal"][SUPERSEDED_GOAL_ID]
        == before["completion_evidence_by_goal"][SUPERSEDED_GOAL_ID]
        and event88["completion_evidence_by_goal_after"]
        == after["completion_evidence_by_goal"]
        and event88["archived_completion_evidence_by_goal_after"]
        == after["archived_completion_evidence_by_goal"],
        "seq88 canonical reopen completion archive differs",
    )
    require(
        not any(
            event.get("event_type") in {"GOAL_STARTED", "GOAL_COMPLETED"}
            and event.get("subject_goal_id") == GOAL_ID
            for event in after["transition_history"]
        ),
        "FP048 R002 readiness gained execution/completion credit",
    )
    errors = continuation.validate_generic_event_order(
        copy.deepcopy(after["transition_history"])
    )
    require(not errors, "generic seq88/89 event order differs: " + "; ".join(errors))
    _require_exact_ready_projection(prepared.root, projected, prepared.staged_outputs)


def prepare(
    root: Path = ROOT,
    *,
    source_bytes: bytes | None = None,
    source_overlay: Mapping[Path, bytes] | None = None,
    source_checkpoint_sha256: str,
    source_r030_gap_sha256: str,
    source_r030_backlog_sha256: str,
) -> PreparedProjection:
    """Build the exact projection in memory and never write a target."""

    root = _validated_root(root)
    raw = (
        _safe_regular_bytes(root, CHECKPOINT_PATH)
        if source_bytes is None
        else source_bytes
    )
    require(type(raw) is bytes, "source checkpoint bytes differ")
    context = _source_context(
        root,
        raw,
        source_overlay,
        source_checkpoint_sha256=source_checkpoint_sha256,
        source_r030_gap_sha256=source_r030_gap_sha256,
        source_r030_backlog_sha256=source_r030_backlog_sha256,
    )
    return _project(context, root)


def goal_binding_from_checkpoint(checkpoint: Mapping[str, Any]) -> dict[str, str]:
    state = checkpoint.get("goal_execution")
    inventory = state.get("dynamic_goal_inventory") if isinstance(state, Mapping) else None
    record = inventory.get(GOAL_ID) if isinstance(inventory, Mapping) else None
    require(
        isinstance(record, Mapping)
        and record.get("path") == GOAL_PATH.as_posix()
        and SHA256_RE.fullmatch(str(record.get("sha256", ""))) is not None,
        "FP048 R002 Goal checkpoint binding differs",
    )
    return {
        "goal_id": GOAL_ID,
        "path": GOAL_PATH.as_posix(),
        "sha256": str(record["sha256"]),
    }


def contract_binding_from_checkpoint(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, Mapping) else None
    require(isinstance(history, list) and len(history) >= READY_SEQUENCE, "seq89 is missing")
    ready = history[READY_SEQUENCE - 1]
    binding = ready.get("implementation_start_gate_contract_binding")
    require(
        isinstance(binding, Mapping)
        and binding.get("path") == CONTRACT_PATH.as_posix()
        and binding.get("contract_id") == CONTRACT_ID
        and SHA256_RE.fullmatch(str(binding.get("file_sha256", ""))) is not None
        and SHA256_RE.fullmatch(
            str(binding.get("canonical_contract_sha256", ""))
        )
        is not None,
        "FP048 R002 contract checkpoint binding differs",
    )
    return copy.deepcopy(dict(binding))


def ready_event_sha256(checkpoint: Mapping[str, Any]) -> str:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, Mapping) else None
    require(isinstance(history, list) and len(history) == READY_SEQUENCE, "seq89 is missing")
    ready = history[-1]
    digest = ready.get("event_sha256")
    require(
        ready.get("event_id") == READY_EVENT_ID
        and digest == continuation.event_sha256(dict(ready)),
        "seq89 ready event seal differs",
    )
    return str(digest)


def _require_exact_ready_projection(
    root: Path,
    checkpoint: Mapping[str, Any],
    overlay: Mapping[Path, bytes],
) -> None:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, Mapping) else None
    require(isinstance(history, list) and len(history) == READY_SEQUENCE, "seq89 is missing")
    seq86, seq87, seq88, seq89 = history[85:89]
    source_binding = seq88.get("source_checkpoint_binding")
    require(
        set(seq88) == MATERIALIZED_FIELDS
        and seq88.get("sequence") == MATERIALIZED_SEQUENCE
        and seq88.get("event_id") == SUPERSEDED_EVENT_ID
        and seq88.get("event_type") == "GOAL_SUPERSEDED"
        and seq88.get("subject_goal_id") == SUPERSEDED_GOAL_ID
        and seq88.get("materialized_goal_id") == GOAL_ID
        and seq88.get("from_status") == "COMPLETE_AT_TARGET"
        and seq88.get("to_status") == "SUPERSEDED"
        and seq88.get("status_changes")
        == {SUPERSEDED_GOAL_ID: "SUPERSEDED", GOAL_ID: "PLANNED"}
        and seq88.get("evidence_refs") == []
        and seq88.get("previous_event_sha256") == seq87.get("event_sha256")
        and seq88.get("reopen_trigger")
        == {
            "canonical_update_event_sha256": seq86.get("event_sha256"),
            "target_completion_event_sha256": seq86.get(
                "reopened_completion_event_sha256_by_goal", {}
            ).get(SUPERSEDED_GOAL_ID),
            "target_completion_occurred_at": _completion_event_occurred_at(
                history,
                str(
                    seq86.get(
                        "reopened_completion_event_sha256_by_goal", {}
                    ).get(SUPERSEDED_GOAL_ID, "")
                ),
            ),
            "decided_at": seq86.get("occurred_at"),
        }
        and seq88.get("event_sha256") == continuation.event_sha256(dict(seq88)),
        "seq88 canonical-change supersession trust anchor differs",
    )
    require(
        isinstance(source_binding, Mapping)
        and set(source_binding)
        == {"path", "sequence", "sha256", "byte_length", "tail_event_sha256"}
        and source_binding.get("path") == CHECKPOINT_PATH.as_posix()
        and source_binding.get("sequence") == 87
        and SHA256_RE.fullmatch(str(source_binding.get("sha256", ""))) is not None
        and isinstance(source_binding.get("byte_length"), int)
        and source_binding["byte_length"] > 0
        and source_binding.get("tail_event_sha256") == seq87.get("event_sha256"),
        "seq88 source checkpoint binding differs",
    )
    require(
        set(seq89) == READY_FIELDS
        and seq89.get("sequence") == READY_SEQUENCE
        and seq89.get("event_id") == READY_EVENT_ID
        and seq89.get("event_type") == "GOAL_READY"
        and seq89.get("subject_goal_id") == GOAL_ID
        and seq89.get("from_status") == "PLANNED"
        and seq89.get("to_status") == "READY"
        and seq89.get("status_changes") == {GOAL_ID: "READY"}
        and seq89.get("previous_event_sha256") == seq88.get("event_sha256")
        and seq89.get("event_sha256") == continuation.event_sha256(dict(seq89)),
        "seq89 readiness trust anchor differs",
    )
    active_completion = state.get("completion_evidence_by_goal")
    archived_completion = state.get("archived_completion_evidence_by_goal")
    require(
        isinstance(active_completion, Mapping)
        and isinstance(archived_completion, Mapping)
        and SUPERSEDED_GOAL_ID not in active_completion
        and SUPERSEDED_GOAL_ID in archived_completion
        and seq88.get("completion_evidence_by_goal_after") == active_completion
        and seq88.get("archived_completion_evidence_by_goal_after")
        == archived_completion,
        "seq88 FP048 R001 completion archive trust anchor differs",
    )
    require(
        state.get("transition_history_anchor_sha256")
        == ready_event_sha256(checkpoint)
        and state.get("status_by_goal", {}).get(SUPERSEDED_GOAL_ID) == "SUPERSEDED"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and state.get("focus_goal_id") == GOAL_ID
        and tuple(state.get("ready_frontier_goal_ids", ())) == READY_FRONTIER,
        "checkpoint is not exact FP048 R002 seq89 READY",
    )
    goal_binding = goal_binding_from_checkpoint(checkpoint)
    goal_raw = _input_bytes(root, GOAL_PATH, overlay)
    goal, _body = _parse_goal(goal_raw, GOAL_ID)
    require(
        sha256_bytes(goal_raw) == goal_binding["sha256"]
        and goal.get("goal_id") == GOAL_ID
        and goal.get("supersedes_goal_id") == SUPERSEDED_GOAL_ID
        and goal.get("predecessor_goal_id") == PREDECESSOR_GOAL_ID
        and goal.get("start_requires") == [DEPENDENCY_GOAL_ID]
        and goal.get("completion_requires") == [DEPENDENCY_GOAL_ID],
        "live FP048 R002 Goal differs",
    )
    contract_binding = contract_binding_from_checkpoint(checkpoint)
    contract_raw = _input_bytes(root, CONTRACT_PATH, overlay)
    contract = strict_json(contract_raw, "FP048 R002 start contract")
    require(
        sha256_bytes(contract_raw) == contract_binding["file_sha256"]
        and continuation.canonical_json_sha256(contract)
        == contract_binding["canonical_contract_sha256"]
        and contract.get("target_goal_content_sha256") == goal_binding["sha256"]
        and tuple(
            row.get("check_id")
            for row in contract.get("ordered_checks", [])
            if isinstance(row, Mapping)
        )
        == CONTRACT_CHECK_IDS,
        "live FP048 R002 start contract differs",
    )


def require_exact_ready_source(root: Path, checkpoint: Mapping[str, Any]) -> None:
    """Validate a published seq89 checkpoint and its live Goal/contract bytes."""

    _require_exact_ready_projection(_validated_root(root), checkpoint, {})


def load_exact_ready_source(root: Path = ROOT) -> dict[str, Any]:
    root = _validated_root(root)
    checkpoint = strict_json(
        _safe_regular_bytes(root, CHECKPOINT_PATH), CHECKPOINT_PATH.as_posix()
    )
    require_exact_ready_source(root, checkpoint)
    return checkpoint


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--source-checkpoint-sha256", required=True)
    parser.add_argument("--source-r030-gap-sha256", required=True)
    parser.add_argument("--source-r030-backlog-sha256", required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--apply", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if args.write or args.apply:
        print("WalkSafe FP048 R002 seq88/89: REFUSED: projection-only module")
        return 2
    try:
        prepared = prepare(
            args.root,
            source_checkpoint_sha256=args.source_checkpoint_sha256,
            source_r030_gap_sha256=args.source_r030_gap_sha256,
            source_r030_backlog_sha256=args.source_r030_backlog_sha256,
        )
    except (OSError, TypeError, ValueError, ProjectionError) as exc:
        print(f"WalkSafe FP048 R002 seq88/89: FAIL: {exc}")
        return 1
    print(
        "WalkSafe FP048 R002 seq88/89: PASS "
        f"ready_event_sha256={prepared.ready_event['event_sha256']} "
        "mode=PREFLIGHT_ZERO_WRITE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
