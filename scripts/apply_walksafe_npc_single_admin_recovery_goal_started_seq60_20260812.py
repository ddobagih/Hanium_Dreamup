#!/usr/bin/env python3
"""Append NPC single-admin-recovery GOAL_STARTED seq60 after correction and gate."""

from __future__ import annotations

import argparse
import copy
from datetime import datetime, timedelta
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as atomic
from scripts import apply_walksafe_npc_goal_start_control_correction_seq59_20260812 as correction
from scripts import apply_walksafe_npc_single_admin_recovery_goal_started_seq59_20260812 as previous
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812 as gate


CHECKPOINT_RELATIVE = gate.CHECKPOINT_RELATIVE
EVENT_SEQUENCE = 60
SCRIPT_RELATIVE = Path(
    "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_"
    "seq60_20260812.py"
)
TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_npc_single_admin_recovery_goal_started_"
    "seq60_20260812.py"
)
STARTED_CURRENT_FOCUS = (
    "NPC-SINGLE-ADMIN-RECOVERY/GAP-008 GOAL_STARTED/IN_PROGRESS; "
    "repository-internal single-admin recovery implementation authorized"
)
STARTED_SCOPE = (
    "Graph v2.4 through NPC-SINGLE-ADMIN-RECOVERY/GAP-008 GOAL_STARTED "
    "seq60 after the exact R002 eight-check internal PASS gate; no product "
    "completion, artifact completion, formal-test, device, external, "
    "deployment, approval, or release credit."
)
STARTED_HANDOFF_EPIC = (
    "EPIC-03 / NPC-SINGLE-ADMIN-RECOVERY/GAP-008 IN_PROGRESS"
)
STARTED_VERIFICATION_STATUS = (
    "PASS_WITH_NPC_SINGLE_ADMIN_RECOVERY_IN_PROGRESS_"
    "FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
)


class StartApplyError(RuntimeError):
    """The exact seq59 correction and R002 PASS gate do not authorize seq60."""


GateEvidence = previous.GateEvidence
PreparedProjection = previous.PreparedProjection
sha256_bytes = previous.sha256_bytes
json_bytes = previous.json_bytes
_read_checkpoint = previous._read_checkpoint
_require_zero_credit_source = previous._require_zero_credit_source
validate_projected_checkpoint = previous.validate_projected_checkpoint


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise StartApplyError(message)


def require_exact_source(root: Path, source: dict[str, Any]) -> None:
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    correction.require_control_corrected_checkpoint(
        root,
        source,
        run_external_validators=False,
    )
    ready_sha256, _ = gate._validate_ready_source(
        source,
        contract_binding=gate.expected_contract_binding(),
    )
    _require(
        ready_sha256 == gate.SOURCE_READY_EVENT_SHA256,
        "source seq57 READY trust anchor differs",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(
        isinstance(history, list)
        and len(history) == EVENT_SEQUENCE - 1
        and correction.CONTROL_CORRECTION_SEQUENCE == EVENT_SEQUENCE - 1
        and gate.SOURCE_SEQUENCE == EVENT_SEQUENCE - 1,
        "source is not the exact seq59 correction",
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
    corrected = history[-1]
    _require(
        ready.get("event_id") == gate.READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == gate.TARGET_GOAL_ID
        and ready.get("event_sha256") == gate.SOURCE_READY_EVENT_SHA256,
        "source seq57 READY event differs",
    )
    replacement = corrected.get("contract_supersession")
    replacement = (
        replacement.get("replacement_contract_binding")
        if isinstance(replacement, dict)
        else None
    )
    _require(
        corrected.get("sequence") == correction.CONTROL_CORRECTION_SEQUENCE
        and corrected.get("event_id") == correction.CONTROL_CORRECTION_EVENT_ID
        and corrected.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and corrected.get("subject_goal_id") == gate.TARGET_GOAL_ID
        and corrected.get("from_status") == corrected.get("to_status") == "READY"
        and corrected.get("status_changes") == {}
        and replacement == gate.expected_contract_binding(),
        "source seq59 control correction differs",
    )
    statuses = state.get("status_by_goal")
    _require(
        state.get("transition_history_anchor_sha256")
        == corrected.get("event_sha256")
        and isinstance(statuses, dict)
        and statuses.get(gate.TARGET_GOAL_ID) == "READY"
        and list(statuses.values()).count("IN_PROGRESS") == 0,
        "source NPC Goal is not the sole READY start target",
    )
    for field, expected in (
        ("focus_goal_id", gate.TARGET_GOAL_ID),
        ("focus_goal_path", gate.GOAL_RELATIVE.as_posix()),
        ("focus_work_item_id", gate.WORK_ITEM_ID),
        ("focus_source", "IMPLEMENTATION_BACKLOG"),
        ("ready_frontier_goal_ids", list(gate.READY_FRONTIER)),
    ):
        _require(state.get(field) == expected, f"source {field} differs")
    _require_zero_credit_source(source)


def validate_live_source(root: Path) -> list[str]:
    return contract.validate(
        root,
        CHECKPOINT_RELATIVE,
        contract.V23_ARCHIVE_RELATIVE,
        gate.MANIFEST_RELATIVE,
    )


def _derive_event_time(source: dict[str, Any], generated_at: datetime) -> datetime:
    correction_time = previous._parse_timestamp(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        label="seq59 correction occurred_at",
        second_precision=True,
    )
    _require(generated_at.microsecond == 0, "gate generated_at must use second precision")
    return max(correction_time + timedelta(seconds=1), generated_at + timedelta(seconds=1))


def validate_gate_evidence(
    root: Path,
    source: dict[str, Any],
    source_checkpoint_bytes: bytes,
    context: gate.GateContext,
    *,
    event_id: str,
    capture_repository_state: Callable[[Path, Path, str], dict[str, Any]],
) -> GateEvidence:
    evidence = previous.validate_gate_evidence(
        root,
        source,
        source_checkpoint_bytes,
        context,
        event_id=event_id,
        capture_repository_state=capture_repository_state,
    )
    generated_at = previous._parse_timestamp(
        evidence.receipt.get("generated_at"),
        label="gate generated_at",
        second_precision=True,
    )
    event_time = _derive_event_time(source, generated_at)
    _require(
        event_time.isoformat() == evidence.event_occurred_at,
        "GOAL_STARTED time is not derived from the seq59 correction",
    )
    return evidence


def project_seq60(
    root: Path,
    source: dict[str, Any],
    evidence: GateEvidence,
    *,
    event_id: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    require_exact_source(root, source)
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    source_state = source["goal_execution"]
    correction_event = state["transition_history"][-1]
    occurred_at = evidence.event_occurred_at
    event: dict[str, Any] = {
        "sequence": EVENT_SEQUENCE,
        "event_id": event_id,
        "event_type": "GOAL_STARTED",
        "occurred_on": datetime.fromisoformat(occurred_at).date().isoformat(),
        "occurred_at": occurred_at,
        "previous_focus_goal_id": gate.TARGET_GOAL_ID,
        "previous_focus_content_sha256": gate.TARGET_GOAL_SHA256,
        "focus_goal_id": gate.TARGET_GOAL_ID,
        "focus_goal_content_sha256": gate.TARGET_GOAL_SHA256,
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "status_changes": {gate.TARGET_GOAL_ID: "IN_PROGRESS"},
        "runtime_after": copy.deepcopy(correction_event["runtime_after"]),
        "repository_snapshot_before": copy.deepcopy(
            evidence.receipt["repository_snapshot"]
        ),
        "implementation_start_gate_binding": copy.deepcopy(
            evidence.receipt_binding
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            record["resolution_id"]
            for record in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [],
        "previous_event_sha256": correction_event["event_sha256"],
    }
    event["event_sha256"] = contract.event_sha256(event)
    _require(
        set(event) == contract.V24_FIRST_START_EVENT_FIELDS,
        "seq60 GOAL_STARTED field set differs",
    )
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    state["status_by_goal"][gate.TARGET_GOAL_ID] = "IN_PROGRESS"

    current = checkpoint["current_work"]
    current["status"] = "IN_PROGRESS"
    current["current_focus"] = STARTED_CURRENT_FOCUS
    current["release_completion_claimed"] = False

    snapshot = checkpoint["working_tree_snapshot"]
    paths = sorted(
        set(snapshot["managed_changed_paths"])
        | {SCRIPT_RELATIVE.as_posix(), TEST_RELATIVE.as_posix()}
    )
    path_sha256, content_sha256 = contract.working_snapshot_hashes(root, paths)
    snapshot["scope"] = STARTED_SCOPE
    snapshot["managed_changed_paths"] = paths
    snapshot["managed_changed_path_count"] = len(paths)
    snapshot["path_set_sha256"] = path_sha256
    snapshot["content_set_sha256"] = content_sha256

    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(paths)
    handoff_snapshot = handoff["source_commit_or_snapshot"]
    handoff_snapshot["file_count"] = len(paths)
    handoff_snapshot["path_set_sha256"] = path_sha256
    handoff_snapshot["content_set_sha256"] = content_sha256
    handoff["current_epic"] = STARTED_HANDOFF_EPIC
    handoff["last_updated_by_work_item"] = gate.WORK_ITEM_ID
    handoff["last_verification_status"] = STARTED_VERIFICATION_STATUS

    _require(
        state["transition_history"][: EVENT_SEQUENCE - 1]
        == source_state["transition_history"],
        "seq1-59 history prefix changed",
    )
    for field in (
        "focus_goal_id",
        "focus_goal_path",
        "focus_work_item_id",
        "focus_source",
        "ready_frontier_goal_ids",
    ):
        _require(
            state[field] == source_state[field],
            f"seq60 changed focus/frontier field: {field}",
        )
    previous._assert_zero_credit_projection(source, checkpoint)
    return checkpoint, event


def prepare_projection(
    root: Path,
    *,
    event_id: str,
    live_validator: Callable[[Path], list[str]] = validate_live_source,
    projected_validator: Callable[[Path, dict[str, Any]], list[str]] = (
        validate_projected_checkpoint
    ),
    context_loader: Callable[[Path], gate.GateContext] = gate.load_gate_context,
    capture_repository_state: Callable[[Path, Path, str], dict[str, Any]] = (
        contract.capture_gate_repository_state
    ),
) -> PreparedProjection:
    root = root.resolve(strict=True)
    gate._document_id(event_id)
    checkpoint_path, source_bytes, source = _read_checkpoint(root)
    require_exact_source(root, source)
    errors = live_validator(root)
    _require(not errors, "live seq59 validation failed: " + "; ".join(errors))
    context = context_loader(root)
    _require(
        context.target_goal_sha256 == gate.TARGET_GOAL_SHA256
        and context.source_ready_event_sha256 == gate.SOURCE_READY_EVENT_SHA256
        and context.checkpoint_sha256 == sha256_bytes(source_bytes)
        and context.contract_binding == gate.expected_contract_binding(),
        "NPC R002 gate context differs",
    )
    evidence = validate_gate_evidence(
        root,
        source,
        source_bytes,
        context,
        event_id=event_id,
        capture_repository_state=capture_repository_state,
    )
    _require(checkpoint_path.read_bytes() == source_bytes, "source checkpoint changed")
    projected, event = project_seq60(root, source, evidence, event_id=event_id)
    errors = projected_validator(root, projected)
    _require(not errors, "projected seq60 validation failed: " + "; ".join(errors))
    _require(checkpoint_path.read_bytes() == source_bytes, "source checkpoint changed")
    return PreparedProjection(
        root=root,
        checkpoint_path=checkpoint_path,
        event_id=event_id,
        source_checkpoint_bytes=source_bytes,
        source_checkpoint=source,
        projected_checkpoint=projected,
        projected_checkpoint_bytes=json_bytes(projected),
        event=event,
        context=context,
        evidence=evidence,
    )


def _require_static_context(prepared: PreparedProjection) -> None:
    checks, _ = gate._load_gate_contract(prepared.root)
    _require(
        checks == prepared.context.checks
        and gate.expected_contract_binding() == prepared.context.contract_binding
        and contract.sha256_file(prepared.root / gate.GOAL_RELATIVE)
        == gate.TARGET_GOAL_SHA256
        and contract.sha256_file(prepared.root / gate.MANIFEST_RELATIVE)
        == gate.MANIFEST_SHA256,
        "static NPC R002 gate context changed",
    )
    runtime_bindings = tuple(
        (relative.as_posix(), contract.sha256_file(prepared.root / relative))
        for relative in gate.RUNTIME_BINDING_RELATIVES
    )
    _require(
        runtime_bindings == prepared.context.runtime_bindings,
        "NPC R002 runtime binding changed",
    )


def _require_live_repository_recapture(
    prepared: PreparedProjection,
    checkpoint_bytes: bytes,
) -> None:
    captured = contract.capture_gate_repository_state(
        prepared.root,
        prepared.checkpoint_path,
        prepared.event_id,
    )
    expected = prepared.evidence.repository_payload
    if checkpoint_bytes == prepared.projected_checkpoint_bytes:
        captured = copy.deepcopy(captured)
        captured["checkpoint_controlled_working_snapshot"] = copy.deepcopy(
            expected["checkpoint_controlled_working_snapshot"]
        )
    _require(
        captured == expected,
        "independent live repository recapture differs from the PASS gate",
    )


def write_projection(
    prepared: PreparedProjection,
    *,
    atomic_writer: Callable[..., None] = atomic.atomic_write,
) -> None:
    def transaction_guard() -> None:
        checkpoint_bytes = prepared.checkpoint_path.read_bytes()
        _require(
            checkpoint_bytes
            in {prepared.source_checkpoint_bytes, prepared.projected_checkpoint_bytes},
            "checkpoint changed at the seq60 transaction boundary",
        )
        for label, checkpoint in (
            ("source", prepared.source_checkpoint),
            ("projected", prepared.projected_checkpoint),
        ):
            snapshot = checkpoint["working_tree_snapshot"]
            path_sha256, content_sha256 = contract.working_snapshot_hashes(
                prepared.root,
                snapshot["managed_changed_paths"],
            )
            _require(
                path_sha256 == snapshot["path_set_sha256"]
                and content_sha256 == snapshot["content_set_sha256"],
                f"controlled {label} content changed at the transaction boundary",
            )
        _require_static_context(prepared)
        evidence = validate_gate_evidence(
            prepared.root,
            prepared.source_checkpoint,
            prepared.source_checkpoint_bytes,
            prepared.context,
            event_id=prepared.event_id,
            capture_repository_state=(
                lambda _root, _checkpoint, _event: prepared.evidence.repository_payload
            ),
        )
        _require(evidence == prepared.evidence, "gate evidence changed")
        _require_live_repository_recapture(prepared, checkpoint_bytes)

    transaction_guard()
    atomic_writer(
        prepared.checkpoint_path,
        prepared.projected_checkpoint_bytes,
        expected_source=prepared.source_checkpoint_bytes,
        commit_guard=transaction_guard,
    )
    metadata = prepared.checkpoint_path.lstat()
    _require(
        stat.S_ISREG(metadata.st_mode)
        and stat.S_IMODE(metadata.st_mode) == 0o600
        and metadata.st_uid == os.geteuid()
        and metadata.st_nlink == 1
        and prepared.checkpoint_path.read_bytes()
        == prepared.projected_checkpoint_bytes,
        "published seq60 checkpoint authority differs",
    )
    transaction_guard()


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
    try:
        prepared = prepare_projection(args.root, event_id=args.event_id)
        if args.write:
            write_projection(prepared)
        mode = "WRITE" if args.write else "PREFLIGHT"
    except (
        StartApplyError,
        previous.StartApplyError,
        correction.ControlCorrectionError,
        gate.GateError,
        atomic.CompletionApplyError,
        atomic.CompletionPostCommitError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exc:
        print(f"NPC GOAL_STARTED seq60: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "NPC GOAL_STARTED seq60: PASS "
        f"mode={mode} event_sha256={prepared.event['event_sha256']} "
        f"occurred_at={prepared.event['occurred_at']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
