#!/usr/bin/env python3
"""Prepare or append FP-048 R002 GOAL_STARTED seq94 after its R005 PASS gate."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import os
from pathlib import Path
import stat
import sys
import tempfile
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (
    apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_seq93_20260826
    as reanchor,
)
from scripts import run_walksafe_fp048_r002_goal_start_gate_r005_20260826 as gate


correction = reanchor
seq90 = reanchor.seq90
contract = reanchor.continuation
CHECKPOINT_RELATIVE = gate.CHECKPOINT_RELATIVE
EVENT_SEQUENCE = 94
EVENT_ID = gate.STARTED_EVENT_ID
TARGET_GOAL_SHA256 = reanchor.GOAL_SHA256
EVENT_FIELDS = frozenset(contract.V24_FIRST_START_EVENT_FIELDS)
SCRIPT_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq94_20260826.py"
)
TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq94_20260826.py"
)
RUNTIME_TRUST_CLOSURE_PATHS = (
    Path("scripts/apply_walksafe_fp046_goal_completed_seq54_55_20260810.py"),
    Path("scripts/build_walksafe_fp008_admin_review_delivery_trace_20260803.py"),
    Path("scripts/build_walksafe_fp008_artifact_trace_successor_20260803.py"),
    Path("scripts/build_walksafe_fp008_gap_backlog_r024_20260803.py"),
    Path("scripts/build_walksafe_fp046_artifact_trace_successor_20260810.py"),
    Path("scripts/build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810.py"),
    Path("scripts/build_walksafe_fp046_gap_backlog_r025_20260810.py"),
    Path("scripts/build_walksafe_fp046_strict_review_gate_20260810.py"),
    Path("scripts/build_walksafe_phase1_exact257_successor_r014_20260810.py"),
    Path("scripts/materialize_walksafe_fp048_goal_20260802.py"),
)
STARTED_CURRENT_FOCUS = (
    "FP-048 R002/GAP-057 GOAL_STARTED/IN_PROGRESS; repository-internal "
    "seven-state encryption rotation all-or-nothing fail-fast work authorized"
)
STARTED_WORK_NEXT_ACTION = (
    "FP-048 R002에서 7종 state rotation을 all-or-nothing으로 재개한다."
)
STARTED_SCOPE = (
    "Graph v2.4 through FP048-R002/GAP-057 GOAL_STARTED seq94 after exact "
    "seq93 zero-credit branch-semantics reanchor and fresh R005 five-check internal "
    "PASS gate; no product completion, artifact completion, formal-test, "
    "device, external, deployment, approval, or release credit."
)
STARTED_HANDOFF_EPIC = "EPIC-03 / FP-048 R002/GAP-057 IN_PROGRESS"
STARTED_VERIFICATION_STATUS = (
    "PASS_WITH_FP048-R002_IN_PROGRESS_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
)


class StartApplyError(RuntimeError):
    """The exact seq93 reanchor and fresh R005 PASS gate do not authorize seq94."""


transport_reanchor = seq90
sha256_bytes = seq90.sha256_bytes
json_bytes = correction.checkpoint_json_bytes
_strict_json = seq90.strict_json


@dataclass(frozen=True)
class GateEvidence:
    receipt: dict[str, Any]
    receipt_bytes: bytes
    receipt_binding: dict[str, str]
    repository_payload: dict[str, Any]
    event_occurred_at: str


@dataclass(frozen=True)
class PreparedProjection:
    transport: seq90.Prepared
    checkpoint_path: Path
    event_id: str
    context: gate.GateContext
    evidence: GateEvidence

    @property
    def root(self) -> Path:
        return self.transport.root

    @property
    def source_checkpoint_bytes(self) -> bytes:
        return self.transport.source_raw

    @property
    def source_checkpoint(self) -> Mapping[str, Any]:
        return self.transport.source

    @property
    def projected_checkpoint(self) -> Mapping[str, Any]:
        return self.transport.projected

    @property
    def projected_checkpoint_bytes(self) -> bytes:
        return self.transport.projected_raw

    @property
    def event(self) -> Mapping[str, Any]:
        return self.transport.event

    @property
    def final_sha256_by_path(self) -> Mapping[Path, str]:
        return {
            path: value.sha256
            for path, value in self.transport.managed_inputs.items()
        }


def _correction() -> Any:
    return correction


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise StartApplyError(message)


def _parse_timestamp(
    value: Any,
    *,
    label: str,
    second_precision: bool,
) -> datetime:
    if not isinstance(value, str):
        raise StartApplyError(f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise StartApplyError(f"{label} is not ISO-8601") from exc
    _require(parsed.utcoffset() is not None, f"{label} lacks a timezone")
    if second_precision:
        _require(parsed.microsecond == 0, f"{label} must use second precision")
    return parsed


def _safe_path(root: Path, relative: Path, *, directory: bool) -> Path:
    _require(not relative.is_absolute() and ".." not in relative.parts, "unsafe path")
    root = root.resolve(strict=True)
    current = root
    for part in relative.parts:
        current /= part
        _require(not current.is_symlink(), f"symlink is not allowed: {relative}")
    try:
        resolved = current.resolve(strict=True)
        resolved.relative_to(root)
    except (OSError, RuntimeError, ValueError) as exc:
        raise StartApplyError(f"repository path is missing or unsafe: {relative}") from exc
    _require(
        resolved.is_dir() if directory else resolved.is_file(),
        f"repository path type differs: {relative}",
    )
    return resolved


def _read_checkpoint(root: Path) -> tuple[Path, bytes, dict[str, Any]]:
    root = seq90._safe_root(root)
    read = seq90._stable_read(root, CHECKPOINT_RELATIVE)
    return (
        root / CHECKPOINT_RELATIVE,
        read.raw,
        _strict_json(read.raw, "source checkpoint"),
    )


def _strict_gate_evidence_equal(actual: GateEvidence, expected: GateEvidence) -> bool:
    return (
        seq90.strict_json_equal(actual.receipt, expected.receipt)
        and actual.receipt_bytes == expected.receipt_bytes
        and seq90.strict_json_equal(actual.receipt_binding, expected.receipt_binding)
        and seq90.strict_json_equal(actual.repository_payload, expected.repository_payload)
        and actual.event_occurred_at == expected.event_occurred_at
    )


def _snapshot_hashes_from_digests(
    values: Mapping[Path, str],
) -> tuple[str, str]:
    paths = sorted(path.as_posix() for path in values)
    path_sha256 = hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
    content = hashlib.sha256()
    for path in paths:
        content.update(path.encode("utf-8"))
        content.update(b"\0")
        content.update(values[Path(path)].encode("ascii"))
        content.update(b"\n")
    return path_sha256, content.hexdigest()


class _LocalSnapshotDigestAuthority:
    _snapshot_hashes_from_digests = staticmethod(_snapshot_hashes_from_digests)


npc = _LocalSnapshotDigestAuthority()


def _require_zero_credit_source(source: Mapping[str, Any]) -> None:
    approved = source.get("approved_state")
    verification = source.get("verification_boundary")
    current = source.get("current_work")
    correction = _correction()
    _require(
        isinstance(approved, dict)
        and type(approved.get("formal_test_count")) is int
        and type(approved.get("formal_test_not_run_count")) is int
        and approved.get("formal_test_count")
        == approved.get("formal_test_not_run_count")
        and approved.get("remaining_gate_count") == 5
        and approved.get("remaining_gates_waived") is False
        and approved.get("release_status") == "NOT_ELIGIBLE",
        "source approved zero-credit boundary differs",
    )
    _require(
        isinstance(verification, dict)
        and verification.get("actual_device_test_status") == "NOT_RUN"
        and verification.get("all_remaining_gate_status") == "NOT_RUN"
        and verification.get("formal_test_pass_claimed") is False
        and verification.get("implementation_conformance_claimed") is False
        and verification.get("release_eligible") is False,
        "source verification zero-credit boundary differs",
    )
    _require(
        isinstance(current, dict)
        and current.get("work_item_id") == correction.WORK_ITEM_ID
        and current.get("status") == "READY"
        and current.get("release_completion_claimed") is False,
        "source current-work READY zero-credit boundary differs",
    )


def require_exact_source(
    root: Path,
    source: dict[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> None:
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    reanchor_authority = _correction()
    try:
        reanchor_authority.require_branch_semantics_reanchored_checkpoint(
            root,
            source,
            run_external_validators=False,
            require_live_snapshot=require_live_snapshot,
        )
        authority = gate.bind_branch_reanchored_source(root, source)
    except Exception as exc:
        raise StartApplyError(f"source seq93 reanchor authority differs: {exc}") from exc
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(
        isinstance(history, list)
        and len(history) == EVENT_SEQUENCE - 1
        and reanchor_authority.REANCHOR_SEQUENCE == EVENT_SEQUENCE - 1,
        "source is not exact seq93",
    )
    previous_sha256 = ""
    for sequence, event in enumerate(history, start=1):
        _require(isinstance(event, dict), f"source seq{sequence} is malformed")
        _require(event.get("sequence") == sequence, f"source seq{sequence} number differs")
        _require(
            event.get("event_sha256") == contract.event_sha256(event),
            f"source seq{sequence} seal differs",
        )
        if sequence > 1:
            _require(
                event.get("previous_event_sha256") == previous_sha256,
                f"source seq{sequence} lineage differs",
            )
        previous_sha256 = str(event["event_sha256"])
    ready = history[gate.SOURCE_READY_SEQUENCE - 1]
    control = history[-1]
    supersession = control.get("contract_supersession")
    replacement = (
        supersession.get("replacement_contract_binding")
        if isinstance(supersession, dict)
        else None
    )
    expected_contract_binding = reanchor_authority.r005_contract_binding(root)
    _require(
        ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == gate.TARGET_GOAL_ID
        and ready.get("event_sha256") == authority.ready_event_sha256,
        "source seq89 READY event differs",
    )
    _require(
        control.get("sequence") == reanchor_authority.REANCHOR_SEQUENCE
        and control.get("event_id") == reanchor_authority.REANCHOR_EVENT_ID
        and control.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and control.get("subject_goal_id") == gate.TARGET_GOAL_ID
        and control.get("from_status") == control.get("to_status") == "READY"
        and transport_reanchor.strict_json_equal(control.get("status_changes"), {})
        and control.get("event_sha256") == authority.reanchor_event_sha256
        and transport_reanchor.strict_json_equal(
            replacement, authority.contract_binding
        ),
        "source seq93 branch-semantics reanchor differs",
    )
    _require(
        transport_reanchor.strict_json_equal(
            replacement,
            expected_contract_binding,
        ),
        "source seq93 R005 contract binding differs",
    )
    statuses = state.get("status_by_goal")
    _require(
        state.get("transition_history_anchor_sha256")
        == authority.reanchor_event_sha256
        and isinstance(statuses, dict)
        and statuses.get(gate.TARGET_GOAL_ID) == "READY"
        and list(statuses.values()).count("IN_PROGRESS") == 0
        and state.get("goal_status") == "READY",
        "source FP048-R002 Goal is not exclusively READY",
    )
    _require_zero_credit_source(source)


def _source_activation(source: dict[str, Any], digest: str) -> dict[str, Any]:
    reanchor_authority = _correction()
    matches = [
        event
        for event in source["goal_execution"]["transition_history"]
        if isinstance(event, dict)
        and event.get("sequence") == reanchor_authority.REANCHOR_SEQUENCE
        and event.get("event_id") == reanchor_authority.REANCHOR_EVENT_ID
        and event.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and event.get("event_sha256") == digest
    ]
    _require(len(matches) == 1, "source seq93 trust anchor differs")
    return matches[0]


def _derive_event_time(source: dict[str, Any], generated_at: datetime) -> datetime:
    control_time = _parse_timestamp(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        label="seq93 occurred_at",
        second_precision=True,
    )
    _require(generated_at.microsecond == 0, "gate generated_at must use second precision")
    return max(control_time + timedelta(seconds=1), generated_at + timedelta(seconds=1))


def _assert_zero_credit_projection(
    source: dict[str, Any], projected: dict[str, Any]
) -> None:
    for key in ("approved_state", "authority_boundary", "verification_boundary", "canonical_bindings"):
        _require(
            transport_reanchor.strict_json_equal(projected.get(key), source.get(key)),
            f"seq94 changed {key}",
        )
    source_state = source["goal_execution"]
    projected_state = projected["goal_execution"]
    for key in (
        "completion_evidence_by_goal",
        "archived_completion_evidence_by_goal",
        "imported_predecessor_goal_bindings",
        "verification_evidence_refs",
        "artifact_work_queue",
        "completion_boundary",
        "dynamic_goal_inventory",
        "materialized_child_goal_ids_by_parent",
        "blockers_by_goal",
        "blocked_goal_ids",
        "pending_questions",
        "open_question_count",
    ):
        _require(
            transport_reanchor.strict_json_equal(
                projected_state.get(key), source_state.get(key)
            ),
            f"seq94 changed zero-credit state: {key}",
        )
    expected_statuses = copy.deepcopy(source_state["status_by_goal"])
    expected_statuses[gate.TARGET_GOAL_ID] = "IN_PROGRESS"
    _require(
        transport_reanchor.strict_json_equal(
            projected_state["status_by_goal"], expected_statuses
        )
        and list(expected_statuses.values()).count("IN_PROGRESS") == 1,
        "seq94 status projection is not one READY-to-IN_PROGRESS change",
    )
    _require(
        source_state.get("goal_status") == "READY"
        and projected_state.get("goal_status") == "IN_PROGRESS",
        "seq94 goal status projection differs",
    )
    _require(
        projected["current_work"].get("status") == "IN_PROGRESS"
        and projected["current_work"].get("release_completion_claimed") is False,
        "seq94 current-work status or release boundary differs",
    )


def project_seq94(
    root: Path,
    source: dict[str, Any],
    evidence: GateEvidence,
    *,
    event_id: str,
    final_sha256_by_path: Mapping[Path, str] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    _require(event_id == EVENT_ID, "seq94 event ID differs")
    require_exact_source(root, source, require_live_snapshot=False)
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    source_state = source["goal_execution"]
    control = state["transition_history"][-1]
    occurred_at = evidence.event_occurred_at
    goal_sha256 = TARGET_GOAL_SHA256
    event: dict[str, Any] = {
        "sequence": EVENT_SEQUENCE,
        "event_id": event_id,
        "event_type": "GOAL_STARTED",
        "occurred_on": datetime.fromisoformat(occurred_at).date().isoformat(),
        "occurred_at": occurred_at,
        "previous_focus_goal_id": gate.TARGET_GOAL_ID,
        "previous_focus_content_sha256": goal_sha256,
        "focus_goal_id": gate.TARGET_GOAL_ID,
        "focus_goal_content_sha256": goal_sha256,
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "status_changes": {gate.TARGET_GOAL_ID: "IN_PROGRESS"},
        "runtime_after": copy.deepcopy(control["runtime_after"]),
        "repository_snapshot_before": copy.deepcopy(
            evidence.receipt["repository_snapshot"]
        ),
        "implementation_start_gate_binding": copy.deepcopy(evidence.receipt_binding),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            record["resolution_id"] for record in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [],
        "previous_event_sha256": control["event_sha256"],
    }
    event["event_sha256"] = contract.event_sha256(event)
    _require(set(event) == EVENT_FIELDS, "seq94 GOAL_STARTED field set differs")
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    state["status_by_goal"][gate.TARGET_GOAL_ID] = "IN_PROGRESS"
    state["goal_status"] = "IN_PROGRESS"

    current = checkpoint["current_work"]
    current["status"] = "IN_PROGRESS"
    current["current_focus"] = STARTED_CURRENT_FOCUS
    current["next_action"] = STARTED_WORK_NEXT_ACTION
    current["release_completion_claimed"] = False

    snapshot = checkpoint["working_tree_snapshot"]
    paths = sorted(
        set(snapshot["managed_changed_paths"])
        | {SCRIPT_RELATIVE.as_posix(), TEST_RELATIVE.as_posix()}
    )
    if final_sha256_by_path is None:
        path_sha256, content_sha256 = contract.working_snapshot_hashes(root, paths)
    else:
        _require(
            set(paths) == {path.as_posix() for path in final_sha256_by_path},
            "seq94 final managed path inventory differs",
        )
        path_sha256, content_sha256 = _snapshot_hashes_from_digests(
            final_sha256_by_path
        )
    snapshot.update(
        {
            "scope": STARTED_SCOPE,
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": path_sha256,
            "content_set_sha256": content_sha256,
        }
    )
    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(paths)
    mirror = handoff["source_commit_or_snapshot"]
    mirror["file_count"] = len(paths)
    mirror["path_set_sha256"] = path_sha256
    mirror["content_set_sha256"] = content_sha256
    handoff["current_epic"] = STARTED_HANDOFF_EPIC
    handoff["last_updated_by_work_item"] = _correction().WORK_ITEM_ID
    handoff["last_verification_status"] = STARTED_VERIFICATION_STATUS
    handoff["next_single_action"] = STARTED_WORK_NEXT_ACTION

    _require(
        transport_reanchor.strict_json_equal(
            state["transition_history"][: EVENT_SEQUENCE - 1],
            source_state["transition_history"],
        ),
        "seq1-93 history prefix changed",
    )
    for field in (
        "focus_goal_id",
        "focus_goal_path",
        "focus_work_item_id",
        "focus_source",
        "ready_frontier_goal_ids",
    ):
        _require(
            transport_reanchor.strict_json_equal(state[field], source_state[field]),
            f"seq94 changed {field}",
        )
    _assert_zero_credit_projection(source, checkpoint)
    _require_exact_seq94_projection(
        root,
        checkpoint,
        state,
        event,
        expected_repository_snapshot=evidence.receipt["repository_snapshot"],
        require_live_snapshot=True,
    )
    return checkpoint, event


def _require_exact_seq94_projection(
    root: Path,
    checkpoint: Mapping[str, Any],
    state: Mapping[str, Any],
    event: Mapping[str, Any],
    *,
    expected_repository_snapshot: Mapping[str, Any],
    require_live_snapshot: bool,
) -> None:
    history = state.get("transition_history")
    _require(
        isinstance(history, list)
        and len(history) == EVENT_SEQUENCE
        and history[EVENT_SEQUENCE - 1] is event,
        "seq94 history position differs",
    )
    control = history[EVENT_SEQUENCE - 2]
    binding = event.get("implementation_start_gate_binding")
    resolutions = state.get("blocker_resolution_history")
    _require(
        isinstance(control, dict)
        and isinstance(binding, dict)
        and set(binding) == {"document_id", "path", "file_sha256"}
        and binding.get("document_id") == gate._document_id(EVENT_ID)
        and binding.get("path")
        == (gate.GATE_ROOT_RELATIVE / EVENT_ID / gate.RECEIPT_NAME).as_posix()
        and isinstance(binding.get("file_sha256"), str)
        and gate._impl.SHA256_RE.fullmatch(binding["file_sha256"]) is not None,
        "seq94 gate binding differs",
    )
    occurred_at = _parse_timestamp(
        event.get("occurred_at"), label="seq94 occurred_at", second_precision=True
    )
    _require(
        set(event) == EVENT_FIELDS
        and event.get("sequence") == EVENT_SEQUENCE
        and event.get("event_id") == EVENT_ID
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("occurred_on") == occurred_at.date().isoformat()
        and event.get("previous_focus_goal_id") == gate.TARGET_GOAL_ID
        and event.get("previous_focus_content_sha256") == TARGET_GOAL_SHA256
        and event.get("focus_goal_id") == gate.TARGET_GOAL_ID
        and event.get("focus_goal_content_sha256") == TARGET_GOAL_SHA256
        and event.get("subject_goal_id") == gate.TARGET_GOAL_ID
        and event.get("from_status") == "READY"
        and event.get("to_status") == "IN_PROGRESS"
        and event.get("static_plan_manifest_sha256") == gate.MANIFEST_SHA256
        and transport_reanchor.strict_json_equal(
            event.get("status_changes"), {gate.TARGET_GOAL_ID: "IN_PROGRESS"}
        )
        and transport_reanchor.strict_json_equal(
            event.get("runtime_after"), control.get("runtime_after")
        )
        and transport_reanchor.strict_json_equal(
            event.get("blockers_after"), state.get("blockers_by_goal")
        )
        and isinstance(resolutions, list)
        and all(isinstance(record, dict) and "resolution_id" in record for record in resolutions)
        and event.get("blocker_resolution_ids_after")
        == [record["resolution_id"] for record in resolutions]
        and event.get("source_checkpoint_version") == checkpoint.get("schema_version")
        and transport_reanchor.strict_json_equal(event.get("evidence_refs"), [])
        and transport_reanchor.strict_json_equal(
            event.get("repository_snapshot_before"),
            expected_repository_snapshot,
        )
        and event.get("previous_event_sha256") == control.get("event_sha256")
        and event.get("event_sha256") == contract.event_sha256(event),
        "seq94 event authority differs",
    )
    statuses = state.get("status_by_goal")
    current = checkpoint.get("current_work")
    snapshot = checkpoint.get("working_tree_snapshot")
    handoff = checkpoint.get("session_handoff")
    _require(
        isinstance(statuses, dict)
        and statuses.get(gate.TARGET_GOAL_ID) == "IN_PROGRESS"
        and list(statuses.values()).count("IN_PROGRESS") == 1
        and state.get("goal_status") == "IN_PROGRESS"
        and state.get("transition_history_anchor_sha256")
        == event.get("event_sha256")
        and state.get("validation_cutoff_at") == event.get("occurred_at"),
        "seq94 state projection differs",
    )
    correction = _correction()
    _require(
        isinstance(current, dict)
        and current.get("work_item_id") == correction.WORK_ITEM_ID
        and current.get("status") == "IN_PROGRESS"
        and current.get("current_focus") == STARTED_CURRENT_FOCUS
        and current.get("next_action") == STARTED_WORK_NEXT_ACTION
        and current.get("release_completion_claimed") is False,
        "seq94 current-work projection differs",
    )
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    _require(
        isinstance(paths, list)
        and all(isinstance(path, str) and path for path in paths)
        and paths == sorted(set(paths))
        and snapshot.get("scope") == STARTED_SCOPE
        and type(snapshot.get("managed_changed_path_count")) is int
        and snapshot.get("managed_changed_path_count") == len(paths),
        "seq94 working-tree snapshot projection differs",
    )
    path_sha256 = snapshot.get("path_set_sha256")
    content_sha256 = snapshot.get("content_set_sha256")
    if require_live_snapshot:
        observed_path_sha256, observed_content_sha256 = (
            contract.working_snapshot_hashes(root, paths)
        )
        _require(
            path_sha256 == observed_path_sha256
            and content_sha256 == observed_content_sha256,
            "seq94 working-tree snapshot hashes differ",
        )
    mirror = handoff.get("source_commit_or_snapshot") if isinstance(handoff, dict) else None
    _require(
        isinstance(handoff, dict)
        and transport_reanchor.strict_json_equal(handoff.get("changed_files"), paths)
        and handoff.get("current_epic") == STARTED_HANDOFF_EPIC
        and handoff.get("last_updated_by_work_item") == correction.WORK_ITEM_ID
        and handoff.get("last_verification_status")
        == STARTED_VERIFICATION_STATUS
        and handoff.get("next_single_action") == STARTED_WORK_NEXT_ACTION
        and isinstance(mirror, dict)
        and type(mirror.get("file_count")) is int
        and mirror.get("file_count") == len(paths)
        and mirror.get("path_set_sha256") == path_sha256
        and mirror.get("content_set_sha256") == content_sha256,
        "seq94 session handoff projection differs",
    )


def _gate_receipt_authority(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], bytes, dict[str, str]]:
    root = seq90._safe_root(root)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(
        isinstance(history, list) and len(history) == EVENT_SEQUENCE,
        "checkpoint is not exact seq94",
    )
    event = history[EVENT_SEQUENCE - 1]
    _require(isinstance(event, dict), "seq94 event differs")
    binding = event.get("implementation_start_gate_binding")
    expected_relative = gate.GATE_ROOT_RELATIVE / EVENT_ID / gate.RECEIPT_NAME
    _require(
        isinstance(binding, dict)
        and set(binding) == {"document_id", "path", "file_sha256"}
        and binding.get("document_id") == gate._document_id(EVENT_ID)
        and binding.get("path") == expected_relative.as_posix()
        and isinstance(binding.get("file_sha256"), str)
        and gate._impl.SHA256_RE.fullmatch(binding["file_sha256"]) is not None,
        "seq94 gate receipt binding differs",
    )
    receipt_read = seq90._stable_read(root, expected_relative)
    _require(
        stat.S_ISREG(receipt_read.identity.mode)
        and not stat.S_ISLNK(receipt_read.identity.mode)
        and stat.S_IMODE(receipt_read.identity.mode) == 0o600
        and 0 < len(receipt_read.raw) <= gate._impl.LOG_MAX_BYTES,
        "seq94 gate receipt mode or size differs",
    )
    receipt = _strict_json(receipt_read.raw, "seq94 PASS receipt")
    _require(
        receipt_read.raw == json_bytes(receipt),
        "seq94 PASS receipt bytes are not canonical",
    )
    _require(
        sha256_bytes(receipt_read.raw) == binding["file_sha256"],
        "seq94 gate receipt file binding differs",
    )
    return event, receipt, receipt_read.raw, copy.deepcopy(binding)


def goal_start_gate_receipt_binding(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> dict[str, str]:
    """Return a detached binding only after exact R005 receipt verification."""

    _event, _receipt, _raw, binding = _gate_receipt_authority(root, checkpoint)
    reconstructed_seq93_checkpoint_bytes(root, checkpoint)
    return binding


def _require_exact_r005_receipt(
    root: Path,
    source: dict[str, Any],
    source_raw: bytes,
    event: Mapping[str, Any],
    receipt: Mapping[str, Any],
    receipt_raw: bytes,
) -> None:
    authority = gate.bind_branch_reanchored_source(root, source)
    contract_binding = authority.contract_binding
    _require(
        set(receipt) == gate._impl.RECEIPT_FIELDS,
        "seq94 R005 receipt field set differs",
    )
    expected_scalars = {
        "schema_version": "1.1",
        "document_id": gate._document_id(EVENT_ID),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": EVENT_ID,
        "target_goal_id": gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": authority.goal_sha256,
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "source_activation_event_sha256": authority.reanchor_event_sha256,
        "source_checkpoint_sha256": sha256_bytes(source_raw),
        "source_ready_event_sha256": authority.ready_event_sha256,
        "check_command_contract_version": contract_binding["contract_version"],
        "check_command_contract_sha256": contract_binding[
            "canonical_contract_sha256"
        ],
    }
    for field, expected in expected_scalars.items():
        _require(
            transport_reanchor.strict_json_equal(receipt.get(field), expected),
            f"seq94 R005 receipt {field} differs",
        )
    _require(
        transport_reanchor.strict_json_equal(
            receipt.get("implementation_start_gate_contract_binding"),
            contract_binding,
        )
        and transport_reanchor.strict_json_equal(receipt.get("runtime_bindings"), [])
        and transport_reanchor.strict_json_equal(
            receipt.get("repository_snapshot"),
            event.get("repository_snapshot_before"),
        ),
        "seq94 R005 receipt binding differs",
    )

    window = receipt.get("execution_window")
    _require(
        isinstance(window, dict) and set(window) == {"started_at", "ended_at"},
        "seq94 R005 receipt execution window differs",
    )
    started_at = _parse_timestamp(
        window.get("started_at"),
        label="R005 started_at",
        second_precision=True,
    )
    ended_at = _parse_timestamp(
        window.get("ended_at"),
        label="R005 ended_at",
        second_precision=True,
    )
    generated_at = _parse_timestamp(
        receipt.get("generated_at"),
        label="R005 generated_at",
        second_precision=True,
    )
    source_at = _parse_timestamp(
        source["goal_execution"]["transition_history"][-1].get("occurred_at"),
        label="seq93 occurred_at",
        second_precision=True,
    )
    event_at = _parse_timestamp(
        event.get("occurred_at"),
        label="seq94 occurred_at",
        second_precision=True,
    )
    _require(
        source_at <= started_at <= ended_at <= generated_at
        and event_at == max(
            source_at + timedelta(seconds=1),
            generated_at + timedelta(seconds=1),
        ),
        "seq94 R005 receipt chronology differs",
    )

    runs = receipt.get("check_runs")
    _require(
        isinstance(runs, list)
        and len(runs) == len(gate.EXPECTED_CHECK_IDS) == 5,
        "seq94 R005 receipt check count differs",
    )
    event_root = gate.GATE_ROOT_RELATIVE / EVENT_ID
    event_path = _safe_path(root, event_root, directory=True)
    event_metadata = event_path.lstat()
    expected_names = {gate.RECEIPT_NAME}
    expected_runs: list[dict[str, Any]] = []
    previous_executed_at: datetime | None = None
    repository_snapshot: dict[str, Any] | None = None
    for index, (check_id, run) in enumerate(
        zip(gate.EXPECTED_CHECK_IDS, runs, strict=True),
        start=1,
    ):
        _require(isinstance(run, dict), f"seq94 R005 check {index} differs")
        output_relative = event_root / f"{index:02d}-{check_id}.log"
        expected_names.add(output_relative.name)
        output_read = seq90._stable_read(root, output_relative)
        _require(
            stat.S_ISREG(output_read.identity.mode)
            and not stat.S_ISLNK(output_read.identity.mode)
            and stat.S_IMODE(output_read.identity.mode) == 0o600
            and 0 < len(output_read.raw) <= gate._impl.LOG_MAX_BYTES,
            f"seq94 R005 check {index} evidence mode or size differs",
        )
        executed_at = _parse_timestamp(
            run.get("executed_at"),
            label=f"R005 check {index} executed_at",
            second_precision=False,
        )
        _require(
            set(run)
            == {
                "check_id",
                "command",
                "executed_at",
                "exit_code",
                "output_path",
                "output_sha256",
            }
            and run.get("check_id") == check_id
            and run.get("command") == gate.CONTRACT_COMMANDS[check_id]
            and type(run.get("exit_code")) is int
            and run.get("exit_code") == 0
            and run.get("output_path") == output_relative.as_posix()
            and run.get("output_sha256") == sha256_bytes(output_read.raw)
            and started_at <= executed_at <= ended_at
            and (
                previous_executed_at is None
                or executed_at > previous_executed_at
            ),
            f"seq94 R005 check {index} authority differs",
        )
        previous_executed_at = executed_at
        expected_runs.append(
            {
                "check_id": check_id,
                "command": gate.CONTRACT_COMMANDS[check_id],
                "output_path": output_relative.as_posix(),
                "output_sha256": sha256_bytes(output_read.raw),
                "exit_code": 0,
                "executed_at": run["executed_at"],
            }
        )
        if check_id == "REPOSITORY_STATE":
            repository_payload = _strict_json(
                output_read.raw,
                "seq94 sealed REPOSITORY_STATE log",
            )
            _require(
                output_read.raw
                == contract.canonical_json_bytes(repository_payload) + b"\n",
                "seq94 sealed REPOSITORY_STATE log is not canonical",
            )
            repository_snapshot = gate._impl.repository_snapshot_from_payload(
                repository_payload,
                event_id=EVENT_ID,
                output_sha256=sha256_bytes(output_read.raw),
            )
    _require(
        stat.S_ISDIR(event_metadata.st_mode)
        and stat.S_IMODE(event_metadata.st_mode) == 0o700
        and event_metadata.st_uid == os.geteuid()
        and {entry.name for entry in event_path.iterdir()} == expected_names,
        "seq94 R005 evidence namespace differs",
    )
    _require(
        repository_snapshot is not None
        and transport_reanchor.strict_json_equal(
            receipt.get("repository_snapshot"),
            repository_snapshot,
        ),
        "seq94 R005 repository snapshot differs",
    )
    expected_receipt = {
        **expected_scalars,
        "implementation_start_gate_contract_binding": copy.deepcopy(
            contract_binding
        ),
        "runtime_bindings": [],
        "execution_window": {
            "started_at": window["started_at"],
            "ended_at": window["ended_at"],
        },
        "check_runs": expected_runs,
        "repository_snapshot": copy.deepcopy(receipt["repository_snapshot"]),
        "generated_at": receipt["generated_at"],
    }
    _require(
        receipt_raw == json_bytes(expected_receipt),
        "seq94 R005 receipt bytes differ from exact producer order",
    )


def _restored_seq93_checkpoint(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    correction = _correction()
    restored = copy.deepcopy(checkpoint)
    state = restored.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(
        isinstance(history, list) and len(history) == EVENT_SEQUENCE,
        "seq93 inverse source is not exact seq94",
    )
    source_event = history[EVENT_SEQUENCE - 2]
    _require(isinstance(source_event, dict), "seq93 inverse source event differs")
    state["transition_history"] = history[: EVENT_SEQUENCE - 1]
    state["transition_history_anchor_sha256"] = source_event["event_sha256"]
    state["validation_cutoff_at"] = source_event["occurred_at"]
    state["status_by_goal"][gate.TARGET_GOAL_ID] = "READY"
    state["goal_status"] = "READY"

    current = restored["current_work"]
    current["status"] = "READY"
    current["current_focus"] = correction.CURRENT_FOCUS
    current["next_action"] = correction.NEXT_ACTION
    current["release_completion_claimed"] = False

    snapshot = restored["working_tree_snapshot"]
    snapshot["scope"] = correction.SCOPE
    repository_context = source_event.get("repository_context_reanchor")
    repository_after = (
        repository_context.get("after")
        if isinstance(repository_context, dict)
        else None
    )
    if isinstance(repository_after, dict):
        for field in (
            "scope",
            "managed_changed_paths",
            "managed_changed_path_count",
            "path_set_sha256",
            "content_set_sha256",
        ):
            if field in repository_after:
                snapshot[field] = copy.deepcopy(repository_after[field])
    handoff = restored["session_handoff"]
    handoff["current_epic"] = correction.HANDOFF_EPIC
    handoff["last_updated_by_work_item"] = correction.WORK_ITEM_ID
    handoff["last_verification_status"] = correction.VERIFICATION_STATUS
    handoff["next_single_action"] = correction.NEXT_ACTION
    if isinstance(repository_after, dict):
        paths = repository_after.get("managed_changed_paths")
        count = repository_after.get("managed_changed_path_count")
        path_sha256 = repository_after.get("path_set_sha256")
        content_sha256 = repository_after.get("content_set_sha256")
        if isinstance(paths, list):
            handoff["changed_files"] = copy.deepcopy(paths)
        mirror = handoff.get("source_commit_or_snapshot")
        if isinstance(mirror, dict):
            mirror["file_count"] = count
            mirror["path_set_sha256"] = path_sha256
            mirror["content_set_sha256"] = content_sha256
    return restored


def reconstructed_seq93_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    """Reconstruct and validate the exact seq93 source bound by the R005 receipt."""

    root = seq90._safe_root(root)
    event, receipt, receipt_raw, _binding = _gate_receipt_authority(root, checkpoint)
    state = checkpoint["goal_execution"]
    _require_exact_seq94_projection(
        root,
        checkpoint,
        state,
        event,
        expected_repository_snapshot=receipt.get("repository_snapshot"),
        require_live_snapshot=False,
    )
    restored = _restored_seq93_checkpoint(checkpoint)
    correction = _correction()
    correction.require_branch_semantics_reanchored_checkpoint(
        root,
        restored,
        run_external_validators=False,
        require_live_snapshot=False,
    )
    canonical = correction.canonical_seq93_checkpoint_bytes(root, restored)
    reconstructed = correction.reconstructed_seq93_checkpoint_bytes(root, restored)
    _require(
        canonical == reconstructed,
        "restored seq93 canonical authority differs",
    )
    _require(
        receipt.get("source_checkpoint_sha256") == sha256_bytes(canonical),
        "seq94 gate receipt source checkpoint SHA-256 differs",
    )
    source = _strict_json(canonical, "restored seq93 checkpoint")
    _require_exact_r005_receipt(
        root,
        source,
        canonical,
        event,
        receipt,
        receipt_raw,
    )
    return canonical


def require_exact_seq94_projection(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> None:
    """Require the exact single READY-to-IN_PROGRESS seq94 projection."""

    root = seq90._safe_root(root)
    event, receipt, _receipt_raw, _binding = _gate_receipt_authority(root, checkpoint)
    state = checkpoint["goal_execution"]
    _require_exact_seq94_projection(
        root,
        checkpoint,
        state,
        event,
        expected_repository_snapshot=receipt.get("repository_snapshot"),
        require_live_snapshot=require_live_snapshot,
    )
    source_raw = reconstructed_seq93_checkpoint_bytes(root, checkpoint)
    source = _strict_json(source_raw, "reconstructed seq93 checkpoint")
    _correction().require_branch_semantics_reanchored_checkpoint(
        root,
        source,
        run_external_validators=False,
        require_live_snapshot=require_live_snapshot,
    )
    authority = gate.bind_branch_reanchored_source(root, source)
    _require(
        receipt.get("schema_version") == "1.1"
        and receipt.get("document_id") == gate._document_id(EVENT_ID)
        and receipt.get("status") == "PASS"
        and receipt.get("target_transition_event_id") == EVENT_ID
        and receipt.get("target_goal_id") == gate.TARGET_GOAL_ID
        and receipt.get("source_checkpoint_sha256") == sha256_bytes(source_raw)
        and receipt.get("source_ready_event_sha256") == authority.ready_event_sha256
        and receipt.get("source_activation_event_sha256")
        == authority.reanchor_event_sha256
        and transport_reanchor.strict_json_equal(
            receipt.get("implementation_start_gate_contract_binding"),
            authority.contract_binding,
        )
        and transport_reanchor.strict_json_equal(
            event.get("repository_snapshot_before"),
            receipt.get("repository_snapshot"),
        ),
        "seq94 R005 receipt authority differs",
    )


def validate_history_suffix(root: Path, checkpoint: Mapping[str, Any]) -> list[str]:
    try:
        state = checkpoint.get("goal_execution")
        history = state.get("transition_history") if isinstance(state, dict) else None
        _require(isinstance(history, list) and len(history) >= EVENT_SEQUENCE, "seq94 is missing")
        control = history[EVENT_SEQUENCE - 2]
        event = history[EVENT_SEQUENCE - 1]
        _require(isinstance(control, dict) and isinstance(event, dict), "seq94 history differs")
        binding = event.get("implementation_start_gate_binding")
        _require(
            isinstance(binding, dict)
            and set(binding) == {"document_id", "path", "file_sha256"}
            and binding.get("document_id") == gate._document_id(EVENT_ID)
            and binding.get("path")
            == (gate.GATE_ROOT_RELATIVE / EVENT_ID / gate.RECEIPT_NAME).as_posix()
            and isinstance(binding.get("file_sha256"), str)
            and gate._impl.SHA256_RE.fullmatch(binding["file_sha256"]) is not None,
            "seq94 gate binding differs",
        )
        _require(
            set(event) == EVENT_FIELDS
            and event.get("sequence") == EVENT_SEQUENCE
            and event.get("event_id") == EVENT_ID
            and event.get("event_type") == "GOAL_STARTED"
            and event.get("previous_focus_goal_id") == gate.TARGET_GOAL_ID
            and event.get("focus_goal_id") == gate.TARGET_GOAL_ID
            and event.get("subject_goal_id") == gate.TARGET_GOAL_ID
            and event.get("from_status") == "READY"
            and event.get("to_status") == "IN_PROGRESS"
            and event.get("static_plan_manifest_sha256") == gate.MANIFEST_SHA256
            and transport_reanchor.strict_json_equal(
                event.get("status_changes"), {gate.TARGET_GOAL_ID: "IN_PROGRESS"}
            )
            and transport_reanchor.strict_json_equal(
                event.get("runtime_after"), control.get("runtime_after")
            )
            and transport_reanchor.strict_json_equal(event.get("evidence_refs"), [])
            and event.get("previous_event_sha256") == control.get("event_sha256")
            and event.get("event_sha256") == contract.event_sha256(event),
            "seq94 event authority differs",
        )
        receipt_path = _safe_path(root, Path(binding["path"]), directory=False)
        receipt_bytes = gate._private_file_bytes(
            receipt_path, allow_empty=False, maximum_bytes=gate._impl.LOG_MAX_BYTES
        )
        _require(
            sha256_bytes(receipt_bytes) == binding["file_sha256"],
            "seq94 gate receipt file binding differs",
        )
        receipt = _strict_json(receipt_bytes, "seq94 PASS receipt")
        _require_exact_seq94_projection(
            root,
            checkpoint,
            state,
            event,
            expected_repository_snapshot=receipt.get("repository_snapshot"),
            require_live_snapshot=True,
        )
        _require(
            transport_reanchor.strict_json_equal(
                event.get("repository_snapshot_before"),
                receipt.get("repository_snapshot"),
            ),
            "seq94 repository snapshot binding differs",
        )
        reconstructed_source = reconstructed_seq93_checkpoint_bytes(
            root, checkpoint
        )
        _require(
            receipt.get("source_checkpoint_sha256")
            == sha256_bytes(reconstructed_source),
            "seq94 gate receipt source checkpoint SHA-256 differs",
        )
    except (
        StartApplyError,
        gate.GateError,
        KeyError,
        OSError,
        RuntimeError,
        TypeError,
        ValueError,
    ) as exc:
        return [str(exc)]
    return []


def validate_live_source(root: Path) -> list[str]:
    return contract.validate(
        root,
        CHECKPOINT_RELATIVE,
        contract.V23_ARCHIVE_RELATIVE,
        gate.MANIFEST_RELATIVE,
    )


def run_continuation_checker(root: Path, checkpoint_path: Path) -> list[str]:
    return contract.validate(
        root,
        checkpoint_path,
        contract.V23_ARCHIVE_RELATIVE,
        contract.V24_MANIFEST_RELATIVE,
    )


def run_goal_graph_checker(root: Path, checkpoint_path: Path) -> list[str]:
    from scripts import check_walksafe_goal_graph_v2_4 as goal_graph

    return goal_graph.validate(
        root,
        checkpoint_path,
        contract.V23_ARCHIVE_RELATIVE,
        contract.V24_MANIFEST_RELATIVE,
        check_continuation=False,
    )


def validate_projected_checkpoint(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    continuation_checker: Callable[[Path, Path], list[str]] = run_continuation_checker,
    goal_graph_checker: Callable[[Path, Path], list[str]] = run_goal_graph_checker,
) -> list[str]:
    errors = [f"seq94: {error}" for error in validate_history_suffix(root, checkpoint)]
    descriptor, temporary_name = tempfile.mkstemp(
        dir=root / CHECKPOINT_RELATIVE.parent,
        prefix=".walksafe-fp048-r002-seq94-preflight.",
        suffix=".json",
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(json_bytes(checkpoint))
            stream.flush()
            os.fsync(stream.fileno())
        relative = temporary.relative_to(root)
        errors.extend(f"continuation: {error}" for error in continuation_checker(root, relative))
        errors.extend(f"goal-graph: {error}" for error in goal_graph_checker(root, relative))
        return errors
    finally:
        temporary.unlink(missing_ok=True)


def _gate_input_paths(context: gate.GateContext, event_id: str) -> tuple[Path, ...]:
    event_root = gate.GATE_ROOT_RELATIVE / event_id
    return (
        event_root / gate.RECEIPT_NAME,
        *(
            event_root / f"{index:02d}-{check_id}.log"
            for index, (check_id, _command) in enumerate(context.checks, start=1)
        ),
    )


def _capture_gate_inputs(
    root: Path,
    context: gate.GateContext,
    event_id: str,
) -> dict[Path, seq90.ReadResult]:
    event_relative = gate.GATE_ROOT_RELATIVE / event_id
    event_path = _safe_path(root, event_relative, directory=True)
    metadata = event_path.lstat()
    _require(
        stat.S_ISDIR(metadata.st_mode)
        and stat.S_IMODE(metadata.st_mode) == 0o700
        and metadata.st_uid == os.geteuid(),
        "gate event directory authority differs",
    )
    paths = _gate_input_paths(context, event_id)
    _require(
        {entry.name for entry in event_path.iterdir()}
        == {path.name for path in paths},
        "gate event directory file set differs",
    )
    retained = {path: seq90._stable_read(root, path) for path in paths}
    _require(
        all(
            stat.S_IMODE(value.identity.mode) == 0o600
            and 0 < len(value.raw) <= gate._impl.LOG_MAX_BYTES
            for value in retained.values()
        ),
        "gate evidence size differs",
    )
    return retained


def _retained_bytes(
    retained: Mapping[Path, seq90.ReadResult],
    path: Path,
) -> bytes:
    value = retained.get(path)
    _require(value is not None, f"retained gate evidence is missing: {path}")
    return value.raw


def validate_gate_evidence(
    root: Path,
    source: dict[str, Any],
    source_checkpoint_bytes: bytes,
    context: gate.GateContext,
    *,
    event_id: str,
    capture_repository_state: Callable[[Path, Path, str], dict[str, Any]],
    retained_inputs: Mapping[Path, seq90.ReadResult] | None = None,
) -> GateEvidence:
    _require(event_id == EVENT_ID, "seq94 event ID differs")
    gate._document_id(event_id)
    retained = (
        dict(retained_inputs)
        if retained_inputs is not None
        else _capture_gate_inputs(root, context, event_id)
    )
    _require(
        set(retained) == set(_gate_input_paths(context, event_id)),
        "retained gate evidence membership differs",
    )
    receipt_relative = gate.GATE_ROOT_RELATIVE / event_id / gate.RECEIPT_NAME
    receipt_bytes = _retained_bytes(retained, receipt_relative)
    receipt = _strict_json(receipt_bytes, "PASS receipt")
    _require(
        receipt_bytes == json_bytes(receipt),
        "PASS receipt bytes are not canonical",
    )
    _require(set(receipt) == gate._impl.RECEIPT_FIELDS, "PASS receipt field set differs")
    binding = context.contract_binding
    expected_values = {
        "schema_version": "1.1",
        "document_id": gate._document_id(event_id),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": event_id,
        "target_goal_id": gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": context.target_goal_sha256,
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "source_activation_event_sha256": context.source_activation_event_sha256,
        "source_checkpoint_sha256": sha256_bytes(source_checkpoint_bytes),
        "source_ready_event_sha256": context.source_ready_event_sha256,
        "check_command_contract_version": binding.get("contract_version"),
        "check_command_contract_sha256": binding.get("canonical_contract_sha256"),
        "implementation_start_gate_contract_binding": binding,
        "runtime_bindings": [
            {"path": path, "file_sha256": digest}
            for path, digest in context.runtime_bindings
        ],
    }
    for field, expected in expected_values.items():
        _require(
            seq90.strict_json_equal(receipt.get(field), expected),
            f"PASS receipt {field} differs",
        )
    _require(
        context.checkpoint_sha256 == sha256_bytes(source_checkpoint_bytes),
        "gate context source checkpoint differs",
    )
    window = receipt.get("execution_window")
    _require(
        isinstance(window, dict) and set(window) == {"started_at", "ended_at"},
        "PASS receipt execution window differs",
    )
    started_at = _parse_timestamp(
        window.get("started_at"), label="gate started_at", second_precision=True
    )
    ended_at = _parse_timestamp(
        window.get("ended_at"), label="gate ended_at", second_precision=True
    )
    generated_at = _parse_timestamp(
        receipt.get("generated_at"), label="gate generated_at", second_precision=True
    )
    _require(started_at <= ended_at <= generated_at, "gate chronology differs")
    activation = _source_activation(source, context.source_activation_event_sha256)
    activation_at = _parse_timestamp(
        activation.get("occurred_at"),
        label="seq93 reanchor occurred_at",
        second_precision=True,
    )
    _require(activation_at <= started_at, "gate precedes seq93 reanchor")
    runs = receipt.get("check_runs")
    _require(
        isinstance(runs, list) and len(runs) == len(context.checks) == 5,
        "PASS receipt check count differs",
    )
    previous_executed_at: datetime | None = None
    repository_payload: dict[str, Any] | None = None
    repository_log_sha256 = ""
    expected_runs: list[dict[str, Any]] = []
    event_root = gate.GATE_ROOT_RELATIVE / event_id
    for index, ((check_id, command), run) in enumerate(
        zip(context.checks, runs, strict=True), start=1
    ):
        _require(isinstance(run, dict), f"gate check {index} is not an object")
        _require(
            set(run)
            == {
                "check_id",
                "command",
                "executed_at",
                "exit_code",
                "output_path",
                "output_sha256",
            },
            f"gate check {index} field set differs",
        )
        output_relative = event_root / f"{index:02d}-{check_id}.log"
        output = _retained_bytes(retained, output_relative)
        output_sha256 = sha256_bytes(output)
        _require(
            run.get("check_id") == check_id
            and run.get("command") == command
            and type(run.get("exit_code")) is int
            and run.get("exit_code") == 0
            and run.get("output_path") == output_relative.as_posix()
            and run.get("output_sha256") == output_sha256,
            f"gate check {index} contract differs",
        )
        executed_at = _parse_timestamp(
            run.get("executed_at"),
            label=f"gate check {index} executed_at",
            second_precision=False,
        )
        _require(
            started_at <= executed_at <= ended_at
            and (previous_executed_at is None or executed_at > previous_executed_at),
            f"gate check {index} chronology differs",
        )
        previous_executed_at = executed_at
        expected_runs.append(
            {
                "check_id": check_id,
                "command": command,
                "output_path": output_relative.as_posix(),
                "output_sha256": output_sha256,
                "exit_code": 0,
                "executed_at": run["executed_at"],
            }
        )
        if check_id == "REPOSITORY_STATE":
            repository_payload = _strict_json(output, "REPOSITORY_STATE log")
            _require(
                output == contract.canonical_json_bytes(repository_payload) + b"\n",
                "REPOSITORY_STATE log is not canonical CLI output",
            )
            repository_log_sha256 = output_sha256
    _require(repository_payload is not None, "REPOSITORY_STATE evidence is missing")
    expected_snapshot = gate._impl.repository_snapshot_from_payload(
        repository_payload,
        event_id=event_id,
        output_sha256=repository_log_sha256,
    )
    _require(
        seq90.strict_json_equal(receipt.get("repository_snapshot"), expected_snapshot),
        "PASS receipt repository snapshot differs",
    )
    expected_receipt = {
        "schema_version": "1.1",
        "document_id": gate._document_id(event_id),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": event_id,
        "target_goal_id": gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": context.target_goal_sha256,
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "source_activation_event_sha256": context.source_activation_event_sha256,
        "source_checkpoint_sha256": sha256_bytes(source_checkpoint_bytes),
        "source_ready_event_sha256": context.source_ready_event_sha256,
        "check_command_contract_version": binding["contract_version"],
        "check_command_contract_sha256": binding["canonical_contract_sha256"],
        "implementation_start_gate_contract_binding": copy.deepcopy(binding),
        "runtime_bindings": [
            {"path": path, "file_sha256": digest}
            for path, digest in context.runtime_bindings
        ],
        "execution_window": {
            "started_at": window["started_at"],
            "ended_at": window["ended_at"],
        },
        "check_runs": expected_runs,
        "repository_snapshot": expected_snapshot,
        "generated_at": receipt["generated_at"],
    }
    _require(
        receipt_bytes == json_bytes(expected_receipt),
        "PASS receipt bytes differ from the exact gate producer order",
    )
    current_payload = capture_repository_state(
        root,
        root / CHECKPOINT_RELATIVE,
        event_id,
    )
    _require(
        seq90.strict_json_equal(current_payload, repository_payload),
        "current repository snapshot differs from the PASS gate",
    )
    event_time = _derive_event_time(source, generated_at)
    _require(
        generated_at < event_time
        and event_time - generated_at <= timedelta(hours=1),
        "derived GOAL_STARTED time is outside gate freshness",
    )
    return GateEvidence(
        receipt=receipt,
        receipt_bytes=receipt_bytes,
        receipt_binding={
            "document_id": receipt["document_id"],
            "path": receipt_relative.as_posix(),
            "file_sha256": sha256_bytes(receipt_bytes),
        },
        repository_payload=repository_payload,
        event_occurred_at=event_time.isoformat(),
    )


def _sealed_gate_repository_payload(
    root: Path,
    _checkpoint_path: Path,
    event_id: str,
) -> dict[str, Any]:
    _require(event_id == EVENT_ID, "seq94 event ID differs")
    index = gate.EXPECTED_CHECK_IDS.index("REPOSITORY_STATE") + 1
    relative = (
        gate.GATE_ROOT_RELATIVE
        / event_id
        / f"{index:02d}-REPOSITORY_STATE.log"
    )
    raw = seq90._stable_read(root, relative).raw
    payload = _strict_json(raw, "sealed REPOSITORY_STATE log")
    _require(
        raw == contract.canonical_json_bytes(payload) + b"\n",
        "sealed REPOSITORY_STATE log is not canonical",
    )
    return payload


def _load_context(
    root: Path,
    loader: Callable[..., gate.GateContext],
) -> gate.GateContext:
    if loader is gate.load_gate_context:
        return loader(root, require_live_snapshot=False)
    return loader(root)


def _require_context(
    root: Path,
    source: dict[str, Any],
    source_raw: bytes,
    context: gate.GateContext,
) -> None:
    authority = gate.bind_branch_reanchored_source(root, source)
    _require(
        context.target_goal_sha256 == authority.goal_sha256
        and context.source_ready_event_sha256 == authority.ready_event_sha256
        and context.source_activation_event_sha256
        == authority.reanchor_event_sha256
        and context.checkpoint_sha256 == sha256_bytes(source_raw)
        and context.contract_binding == authority.contract_binding
        and context.runtime_bindings == ()
        and tuple(gate.IMPORT_TRUST_RUNTIME_CLOSURE_RELATIVES)
        == RUNTIME_TRUST_CLOSURE_PATHS,
        "FP048-R002 R005 gate context differs",
    )


def _final_managed_paths(
    source: Mapping[str, Any],
    git_visible: Sequence[str],
) -> tuple[str, ...]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    gate_prefix = gate.GATE_ROOT_RELATIVE.as_posix() + "/"
    paths.update(
        path
        for path in git_visible
        if path != CHECKPOINT_RELATIVE.as_posix()
        and not path.startswith(gate_prefix)
    )
    paths.update((SCRIPT_RELATIVE.as_posix(), TEST_RELATIVE.as_posix()))
    paths.update(path.as_posix() for path in RUNTIME_TRUST_CLOSURE_PATHS)
    return tuple(sorted(paths))


def _require_prepared_exact(
    prepared: PreparedProjection,
    *,
    projected_validator: Callable[[Path, dict[str, Any]], list[str]] = (
        validate_projected_checkpoint
    ),
) -> None:
    transport = prepared.transport
    observed_gate_inputs = _capture_gate_inputs(
        prepared.root,
        prepared.context,
        prepared.event_id,
    )
    _require(
        set(observed_gate_inputs) == set(transport.retained_inputs)
        and all(
            observed.identity == transport.retained_inputs[path].identity
            and observed.raw == transport.retained_inputs[path].raw
            for path, observed in observed_gate_inputs.items()
        ),
        "R005 gate evidence changed or namespace membership differs",
    )
    source_read = seq90.ReadResult(
        raw=transport.source_raw,
        identity=transport.source_identity,
    )
    seq90._require_preflight_cohort_unchanged(
        prepared.root,
        source=source_read,
        retained=transport.retained_inputs,
        managed=transport.managed_inputs,
        git_visible=transport.git_visible_inputs,
        git_status_raw=transport.git_status_raw,
        git_head=transport.git_head,
        git_branch=transport.git_branch,
        phase="during seq94 publication preflight",
    )
    refreshed = gate.load_gate_context(
        prepared.root,
        require_live_snapshot=False,
    )
    _require(refreshed == prepared.context, "R005 gate context changed")
    refreshed_evidence = validate_gate_evidence(
        prepared.root,
        dict(transport.source),
        transport.source_raw,
        refreshed,
        event_id=prepared.event_id,
        capture_repository_state=contract.capture_gate_repository_state,
        retained_inputs=transport.retained_inputs,
    )
    _require(
        _strict_gate_evidence_equal(refreshed_evidence, prepared.evidence),
        "R005 gate evidence changed",
    )
    projected_for_validation = _fresh_exact_projected_checkpoint(prepared)
    errors = projected_validator(prepared.root, projected_for_validation)
    _require(not errors, "projected seq94 validation failed: " + "; ".join(errors))
    _fresh_exact_projected_checkpoint(prepared)
    seq90._require_preflight_cohort_unchanged(
        prepared.root,
        source=source_read,
        retained=transport.retained_inputs,
        managed=transport.managed_inputs,
        git_visible=transport.git_visible_inputs,
        git_status_raw=transport.git_status_raw,
        git_head=transport.git_head,
        git_branch=transport.git_branch,
        phase="after seq94 projected consumer validation",
    )
    observed_gate_inputs = _capture_gate_inputs(
        prepared.root,
        prepared.context,
        prepared.event_id,
    )
    _require(
        set(observed_gate_inputs) == set(transport.retained_inputs)
        and all(
            observed.identity == transport.retained_inputs[path].identity
            and observed.raw == transport.retained_inputs[path].raw
            for path, observed in observed_gate_inputs.items()
        ),
        "R005 gate evidence changed after projected consumer validation",
    )


def _fresh_exact_projected_checkpoint(
    prepared: PreparedProjection,
    raw: bytes | None = None,
) -> dict[str, Any]:
    authority_raw = prepared.projected_checkpoint_bytes
    observed_raw = authority_raw if raw is None else raw
    _require(observed_raw == authority_raw, "seq94 projected checkpoint raw bytes differ")
    projected = _strict_json(observed_raw, "seq94 projected checkpoint")
    _require(
        json_bytes(projected) == observed_raw
        and json_bytes(prepared.projected_checkpoint) == authority_raw,
        "seq94 projected checkpoint authority differs",
    )
    state = projected.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(
        isinstance(history, list)
        and len(history) == EVENT_SEQUENCE
        and isinstance(history[-1], dict)
        and seq90.canonical_json_bytes(history[-1])
        == seq90.canonical_json_bytes(prepared.event),
        "seq94 projected event authority differs",
    )
    return projected


def _require_private_checkpoint_authority(
    checkpoint: seq90.ReadResult,
    *,
    phase: str,
) -> None:
    mode = checkpoint.identity.mode
    _require(
        stat.S_ISREG(mode)
        and not stat.S_ISLNK(mode)
        and stat.S_IMODE(mode) == 0o600,
        f"seq94 checkpoint authority differs {phase}",
    )


def _write_projection_transport(
    writer: Callable[..., None],
    path: Path,
    content: bytes,
    *,
    expected_source: bytes,
    commit_guard: Callable[[], None],
) -> None:
    source_read = seq90._stable_read(path.parent, Path(path.name))
    _require(
        source_read.raw == expected_source,
        "seq94 checkpoint source differs before injected writer",
    )
    guard_called = False

    def required_guard() -> None:
        nonlocal guard_called
        commit_guard()
        guard_called = True

    try:
        writer(
            path,
            content,
            expected_source=expected_source,
            commit_guard=required_guard,
        )
        if not guard_called:
            raise seq90.PostcommitUncertain(
                "seq94 checkpoint writer returned without its commit guard"
            )
    except seq90.PostcommitUncertain:
        raise
    except BaseException as exc:
        try:
            observed = seq90._stable_read(path.parent, Path(path.name))
            unchanged = (
                observed.identity == source_read.identity
                and observed.raw == expected_source
            )
        except BaseException:
            unchanged = False
        if unchanged:
            if isinstance(exc, seq90.ControlReanchorError):
                raise
            raise StartApplyError(
                "seq94 checkpoint transport failed before replacement"
            ) from exc
        raise seq90.PostcommitUncertain(
            "seq94 checkpoint transport ended with an unclassified failure"
        ) from exc


def _verify_published_projection(prepared: PreparedProjection) -> None:
    try:
        published_read = seq90._stable_read(prepared.root, CHECKPOINT_RELATIVE)
        _require_private_checkpoint_authority(
            published_read,
            phase="immediately after publication",
        )
        projected_for_validation = _fresh_exact_projected_checkpoint(
            prepared,
            published_read.raw,
        )
        observed_gate_inputs = _capture_gate_inputs(
            prepared.root,
            prepared.context,
            prepared.event_id,
        )
        _require(
            set(observed_gate_inputs) == set(prepared.transport.retained_inputs)
            and all(
                observed.identity
                == prepared.transport.retained_inputs[path].identity
                and observed.raw == prepared.transport.retained_inputs[path].raw
                for path, observed in observed_gate_inputs.items()
            ),
            "published seq94 gate evidence changed or membership differs",
        )
        errors = validate_projected_checkpoint(
            prepared.root,
            projected_for_validation,
        )
        _require(not errors, "published seq94 validation failed: " + "; ".join(errors))
        terminal_checkpoint = seq90._stable_read(
            prepared.root,
            CHECKPOINT_RELATIVE,
        )
        _require_private_checkpoint_authority(
            terminal_checkpoint,
            phase="after terminal consumers",
        )
        _require(
            terminal_checkpoint.identity == published_read.identity
            and terminal_checkpoint.raw == published_read.raw,
            "published seq94 checkpoint changed during terminal consumers",
        )
        _fresh_exact_projected_checkpoint(prepared, terminal_checkpoint.raw)
        terminal_gate_inputs = _capture_gate_inputs(
            prepared.root,
            prepared.context,
            prepared.event_id,
        )
        _require(
            set(terminal_gate_inputs) == set(prepared.transport.retained_inputs)
            and all(
                observed.identity
                == prepared.transport.retained_inputs[path].identity
                and observed.raw == prepared.transport.retained_inputs[path].raw
                for path, observed in terminal_gate_inputs.items()
            ),
            "published seq94 gate evidence changed during terminal consumers",
        )
        seq90._require_managed_inputs_unchanged(
            prepared.root,
            prepared.transport.managed_inputs,
        )
        terminal_git_visible_inputs = {
            path: value
            for path, value in prepared.transport.git_visible_inputs.items()
            if path != CHECKPOINT_RELATIVE
        }
        seq90._require_managed_inputs_unchanged(
            prepared.root,
            terminal_git_visible_inputs,
            label="terminal full Git-visible input",
        )
        seq90._require_git_context(
            prepared.root,
            prepared.transport.git_head,
            prepared.transport.git_branch,
        )
        terminal_status_raw, _ = seq90.capture_git_visible_paths(prepared.root)
        _require(
            terminal_status_raw == prepared.transport.git_status_raw,
            "Git-visible cohort changed during terminal consumers",
        )
    except seq90.PostcommitUncertain:
        raise
    except BaseException as exc:
        raise seq90.PostcommitUncertain(
            "seq94 checkpoint was replaced but terminal validation failed"
        ) from exc


def write_projection(
    prepared: PreparedProjection,
    *,
    atomic_writer: Callable[..., None] | None = None,
) -> None:
    _require_prepared_exact(prepared)
    if atomic_writer is None:
        seq90.write_checkpoint(
            prepared.transport,
            commit_guard=lambda: _require_prepared_exact(prepared),
        )
    else:
        _write_projection_transport(
            atomic_writer,
            prepared.checkpoint_path,
            prepared.projected_checkpoint_bytes,
            expected_source=prepared.source_checkpoint_bytes,
            commit_guard=lambda: _require_prepared_exact(prepared),
        )
    _verify_published_projection(prepared)


def prepare_projection(
    root: Path,
    *,
    event_id: str,
    live_validator: Callable[[Path], list[str]] = validate_live_source,
    projected_validator: Callable[[Path, dict[str, Any]], list[str]] = validate_projected_checkpoint,
    context_loader: Callable[..., gate.GateContext] = gate.load_gate_context,
    capture_repository_state: Callable[[Path, Path, str], dict[str, Any]] = contract.capture_gate_repository_state,
) -> PreparedProjection:
    root = seq90._safe_root(root)
    _require(event_id == EVENT_ID, "seq94 event ID differs")
    gate._document_id(event_id)
    source_read = seq90._stable_read(root, CHECKPOINT_RELATIVE)
    _require_private_checkpoint_authority(source_read, phase="before seq94 preflight")
    source = _strict_json(source_read.raw, CHECKPOINT_RELATIVE.as_posix())
    _require(
        source_read.raw == json_bytes(source),
        "source checkpoint bytes are not canonical",
    )
    git_head, git_branch = seq90._capture_git_context(root)
    expected_head = source["session_handoff"]["source_commit_or_snapshot"][
        "current_head"
    ]
    _require(git_head == expected_head, "live Git HEAD differs from seq93 source")
    git_status_raw, git_visible = seq90.capture_git_visible_paths(root)
    paths = _final_managed_paths(source, git_visible)
    managed = seq90._capture_managed_inputs(root, paths)
    visible_inputs = seq90._capture_managed_inputs(root, git_visible)
    path_sha256, content_sha256 = seq90._managed_input_snapshot_hashes(managed)
    final_sha256_by_path = {
        path: value.sha256 for path, value in managed.items()
    }
    require_exact_source(root, source, require_live_snapshot=False)
    _require(
        source_read.raw
        == correction.canonical_seq93_checkpoint_bytes(root, source),
        "source checkpoint is not the exact canonical seq93 reanchor",
    )
    live_errors = live_validator(root)
    _require(not live_errors, "live seq93 validation failed: " + "; ".join(live_errors))
    context = _load_context(root, context_loader)
    _require_context(root, source, source_read.raw, context)
    retained = _capture_gate_inputs(root, context, event_id)
    evidence = validate_gate_evidence(
        root,
        source,
        source_read.raw,
        context,
        event_id=event_id,
        capture_repository_state=capture_repository_state,
        retained_inputs=retained,
    )
    projected, event = project_seq94(
        root,
        source,
        evidence,
        event_id=event_id,
        final_sha256_by_path=final_sha256_by_path,
    )
    _require(
        (path_sha256, content_sha256)
        == (
            projected["working_tree_snapshot"]["path_set_sha256"],
            projected["working_tree_snapshot"]["content_set_sha256"],
        ),
        "captured final managed cohort differs from projected snapshot",
    )
    transport = seq90.Prepared(
        root=root,
        source_raw=source_read.raw,
        source_identity=source_read.identity,
        source=source,
        projected=projected,
        projected_raw=json_bytes(projected),
        event=event,
        retained_inputs=retained,
        managed_inputs=managed,
        git_visible_inputs=visible_inputs,
        git_status_raw=git_status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    prepared = PreparedProjection(
        transport=transport,
        checkpoint_path=root / CHECKPOINT_RELATIVE,
        event_id=event_id,
        context=context,
        evidence=evidence,
    )
    _require_prepared_exact(prepared, projected_validator=projected_validator)
    refreshed = _load_context(root, context_loader)
    _require(refreshed == context, "R005 gate context changed during checker")
    return prepared


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    published = False
    try:
        prepared = prepare_projection(args.root, event_id=args.event_id)
        if args.write:
            write_projection(prepared)
            published = True
        message = (
            "FP048-R002 GOAL_STARTED seq94: PASS "
            f"event_sha256={prepared.event['event_sha256']} "
            f"occurred_at={prepared.event['occurred_at']}\n"
        ).encode()
        try:
            gate._impl._write_raw_exact(sys.stdout, message)
        except BaseException as exc:
            if published:
                raise seq90.PostcommitUncertain(
                    "published seq94 PASS output delivery is uncertain"
                ) from exc
            raise
    except seq90.PostcommitUncertain as exc:
        print(f"FP048-R002 GOAL_STARTED seq94: POSTCOMMIT-UNCERTAIN: {exc}", file=sys.stderr)
        return 2
    except (
        StartApplyError,
        gate.GateError,
        seq90.ControlReanchorError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exc:
        print(f"FP048-R002 GOAL_STARTED seq94: FAIL: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
