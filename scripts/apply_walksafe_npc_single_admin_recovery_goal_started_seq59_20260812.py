#!/usr/bin/env python3
"""Append NPC single-admin-recovery GOAL_STARTED seq59 after its R002 gate."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as atomic
from scripts import apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812 as reanchor
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812 as gate


CHECKPOINT_RELATIVE = gate.CHECKPOINT_RELATIVE
EVENT_SEQUENCE = 59
SCRIPT_RELATIVE = Path(
    "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_"
    "seq59_20260812.py"
)
TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_npc_single_admin_recovery_goal_started_"
    "seq59_20260812.py"
)
STARTED_CURRENT_FOCUS = (
    "NPC-SINGLE-ADMIN-RECOVERY/GAP-008 GOAL_STARTED/IN_PROGRESS; "
    "repository-internal single-admin recovery implementation authorized"
)
STARTED_SCOPE = (
    "Graph v2.4 through NPC-SINGLE-ADMIN-RECOVERY/GAP-008 GOAL_STARTED "
    "seq59 after the exact R002 eight-check internal PASS gate; no product "
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
    """The exact seq58 source and R002 PASS gate do not authorize seq59."""


@dataclass(frozen=True)
class GateEvidence:
    receipt: dict[str, Any]
    receipt_bytes: bytes
    receipt_binding: dict[str, str]
    repository_payload: dict[str, Any]
    event_occurred_at: str


@dataclass(frozen=True)
class PreparedProjection:
    root: Path
    checkpoint_path: Path
    event_id: str
    source_checkpoint_bytes: bytes
    source_checkpoint: dict[str, Any]
    projected_checkpoint: dict[str, Any]
    projected_checkpoint_bytes: bytes
    event: dict[str, Any]
    context: gate.GateContext
    evidence: GateEvidence


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise StartApplyError(message)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _strict_json(raw: bytes, label: str) -> dict[str, Any]:
    _require(not raw.startswith(b"\xef\xbb\xbf"), f"{label} contains a BOM")
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise StartApplyError(f"{label} is not strict UTF-8") from exc

    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                raise StartApplyError(f"{label} contains duplicate key: {key}")
            result[key] = value
        return result

    def reject_constant(value: str) -> Any:
        raise StartApplyError(f"{label} contains non-finite number: {value}")

    try:
        value = json.loads(
            text,
            object_pairs_hook=pairs,
            parse_constant=reject_constant,
        )
    except json.JSONDecodeError as exc:
        raise StartApplyError(f"{label} is not valid JSON") from exc
    _require(isinstance(value, dict), f"{label} root is not an object")
    return value


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
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise StartApplyError(f"{label} lacks a timezone")
    if second_precision and parsed.microsecond:
        raise StartApplyError(f"{label} must use second precision")
    return parsed


def _safe_path(root: Path, relative: Path, *, directory: bool) -> Path:
    if relative.is_absolute() or ".." in relative.parts:
        raise StartApplyError(f"unsafe repository path: {relative}")
    root = root.resolve(strict=True)
    current = root
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            raise StartApplyError(f"symlink is not allowed: {relative}")
    try:
        resolved = current.resolve(strict=True)
        resolved.relative_to(root)
    except (OSError, RuntimeError, ValueError) as exc:
        raise StartApplyError(
            f"repository path is missing or unsafe: {relative}"
        ) from exc
    expected_type = resolved.is_dir() if directory else resolved.is_file()
    kind = "directory" if directory else "regular file"
    _require(expected_type, f"repository path is not a {kind}: {relative}")
    return resolved


def _read_checkpoint(root: Path) -> tuple[Path, bytes, dict[str, Any]]:
    path = _safe_path(root, CHECKPOINT_RELATIVE, directory=False)
    before = path.lstat()
    _require(
        stat.S_ISREG(before.st_mode)
        and stat.S_IMODE(before.st_mode) == 0o600
        and before.st_uid == os.geteuid()
        and before.st_nlink == 1,
        "source checkpoint authority differs",
    )
    descriptor = os.open(
        path,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        opened = os.fstat(descriptor)
        _require(
            (opened.st_dev, opened.st_ino, opened.st_mode, opened.st_uid,
             opened.st_nlink, opened.st_size)
            == (before.st_dev, before.st_ino, before.st_mode, before.st_uid,
                before.st_nlink, before.st_size),
            "source checkpoint identity changed while opening",
        )
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        raw = b"".join(chunks)
        after = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    named = path.lstat()
    _require(
        (after.st_dev, after.st_ino, after.st_mode, after.st_uid,
         after.st_nlink, after.st_size)
        == (before.st_dev, before.st_ino, before.st_mode, before.st_uid,
            before.st_nlink, before.st_size)
        == (named.st_dev, named.st_ino, named.st_mode, named.st_uid,
            named.st_nlink, named.st_size),
        "source checkpoint changed while reading",
    )
    return path, raw, _strict_json(raw, "source checkpoint")


def _source_activation(
    source: dict[str, Any],
    activation_sha256: str,
) -> dict[str, Any]:
    history = source["goal_execution"]["transition_history"]
    matches = [
        event
        for event in history
        if isinstance(event, dict)
        and event.get("event_type") == "PACKAGE_ACTIVATED"
        and event.get("event_sha256") == activation_sha256
    ]
    _require(len(matches) == 1, "source activation event differs")
    return matches[0]


def _require_zero_credit_source(source: dict[str, Any]) -> None:
    approved = source.get("approved_state")
    verification = source.get("verification_boundary")
    current = source.get("current_work")
    _require(
        isinstance(approved, dict)
        and approved.get("formal_test_count") == 279
        and approved.get("formal_test_not_run_count") == 279
        and approved.get("remaining_gate_count") == 5
        and approved.get("remaining_gates_waived") is False
        and approved.get("release_status") == "NOT_ELIGIBLE",
        "source approved zero-credit boundary differs",
    )
    _require(
        isinstance(verification, dict)
        and verification.get("actual_device_test_status") == "NOT_RUN"
        and verification.get("all_remaining_gate_status") == "NOT_RUN"
        and verification.get("formal_test_not_run_count") == 279
        and verification.get("formal_test_pass_claimed") is False
        and verification.get("implementation_conformance_claimed") is False
        and verification.get("release_eligible") is False,
        "source verification zero-credit boundary differs",
    )
    _require(
        isinstance(current, dict)
        and current.get("work_item_id") == gate.WORK_ITEM_ID
        and current.get("status") == "READY"
        and current.get("release_completion_claimed") is False,
        "source current-work READY zero-credit boundary differs",
    )


def require_exact_source(root: Path, source: dict[str, Any]) -> None:
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    reanchor.require_control_reanchored_checkpoint(
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
        and gate.SOURCE_SEQUENCE == gate.CONTROL_REANCHOR_SEQUENCE
        == EVENT_SEQUENCE - 1,
        "source is not exact seq58",
    )
    previous = ""
    for sequence, event in enumerate(history, start=1):
        _require(isinstance(event, dict), f"source seq{sequence} is malformed")
        _require(event.get("sequence") == sequence, f"source seq{sequence} number differs")
        _require(
            event.get("event_sha256") == contract.event_sha256(event),
            f"source seq{sequence} seal differs",
        )
        if sequence > 1:
            _require(
                event.get("previous_event_sha256") == previous,
                f"source seq{sequence} lineage differs",
            )
        previous = str(event["event_sha256"])
    ready, control = history[-2:]
    _require(
        ready.get("sequence") == 57
        and ready.get("event_id") == gate.READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == gate.TARGET_GOAL_ID
        and ready.get("event_sha256") == gate.SOURCE_READY_EVENT_SHA256,
        "source seq57 READY event differs",
    )
    replacement = control.get("contract_supersession")
    replacement = (
        replacement.get("replacement_contract_binding")
        if isinstance(replacement, dict)
        else None
    )
    _require(
        control.get("sequence") == gate.CONTROL_REANCHOR_SEQUENCE
        and control.get("event_id") == gate.CONTROL_REANCHOR_EVENT_ID
        and control.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and control.get("subject_goal_id") == gate.TARGET_GOAL_ID
        and control.get("from_status") == control.get("to_status") == "READY"
        and control.get("status_changes") == {}
        and control.get("previous_event_sha256") == gate.SOURCE_READY_EVENT_SHA256
        and replacement == gate.expected_contract_binding(),
        "source seq58 control reanchor differs",
    )
    statuses = state.get("status_by_goal")
    _require(
        state.get("transition_history_anchor_sha256") == control.get("event_sha256")
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


def validate_projected_checkpoint(
    root: Path,
    checkpoint: dict[str, Any],
) -> list[str]:
    errors, archive = contract.validate_frozen_v23_boundary(
        root,
        contract.V23_ARCHIVE_RELATIVE,
    )
    errors.extend(
        contract.validate_seq39_canonical_binding_authorization_request(
            root,
            checkpoint,
        )
    )
    errors.extend(contract.validate_seq39_canonical_binding_update(root, checkpoint))
    if archive:
        errors.extend(contract.validate_prepared_checkpoint_projection(checkpoint, archive))
        errors.extend(
            contract.validate_transition_replay(
                root,
                checkpoint,
                archive,
                gate.MANIFEST_RELATIVE,
                expected_prepared_sha256=contract.EXPECTED_V24_PREPARED_EVENT_SHA256,
                expected_authorization_sha256=(
                    contract.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256
                ),
            )
        )
    errors.extend(contract.validate_working_snapshot(root, checkpoint))
    errors.extend(goal_graph.validate_v24_artifact_work_queue(root, checkpoint))
    errors.extend(
        contract.validate_generic_event_order(
            checkpoint["goal_execution"]["transition_history"]
        )
    )
    return errors


def _derive_event_time(source: dict[str, Any], generated_at: datetime) -> datetime:
    control_time = _parse_timestamp(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        label="seq58 occurred_at",
        second_precision=True,
    )
    _require(generated_at.microsecond == 0, "gate generated_at must use second precision")
    return max(control_time + timedelta(seconds=1), generated_at + timedelta(seconds=1))


def validate_gate_evidence(
    root: Path,
    source: dict[str, Any],
    source_checkpoint_bytes: bytes,
    context: gate.GateContext,
    *,
    event_id: str,
    capture_repository_state: Callable[[Path, Path, str], dict[str, Any]],
) -> GateEvidence:
    gate._document_id(event_id)
    event_dir_relative = gate.GATE_ROOT_RELATIVE / event_id
    receipt_relative = event_dir_relative / gate.RECEIPT_NAME
    event_dir = _safe_path(root, event_dir_relative, directory=True)
    metadata = event_dir.lstat()
    _require(
        stat.S_ISDIR(metadata.st_mode)
        and stat.S_IMODE(metadata.st_mode) == 0o700
        and metadata.st_uid == os.geteuid(),
        "gate event directory authority differs",
    )
    expected_names = {
        gate.RECEIPT_NAME,
        *(
            f"{index:02d}-{check_id}.log"
            for index, (check_id, _) in enumerate(context.checks, start=1)
        ),
    }
    _require(
        {entry.name for entry in event_dir.iterdir()} == expected_names,
        "gate event directory file set differs",
    )
    receipt_path = _safe_path(root, receipt_relative, directory=False)
    receipt_bytes = gate._private_file_bytes(
        receipt_path,
        allow_empty=False,
        maximum_bytes=gate.LOG_MAX_BYTES,
    )
    receipt = _strict_json(receipt_bytes, "PASS receipt")
    _require(set(receipt) == gate.RECEIPT_FIELDS, "PASS receipt field set differs")

    expected_receipt_values = {
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
        "source_ready_event_sha256": gate.SOURCE_READY_EVENT_SHA256,
        "check_command_contract_version": gate.CONTRACT_VERSION,
        "check_command_contract_sha256": gate.CONTRACT_CANONICAL_SHA256,
        "implementation_start_gate_contract_binding": (
            gate.expected_contract_binding()
        ),
        "runtime_bindings": [
            {"path": path, "file_sha256": digest}
            for path, digest in context.runtime_bindings
        ],
    }
    for field, expected in expected_receipt_values.items():
        _require(receipt.get(field) == expected, f"PASS receipt {field} differs")
    _require(
        context.source_ready_event_sha256 == gate.SOURCE_READY_EVENT_SHA256
        and context.checkpoint_sha256 == sha256_bytes(source_checkpoint_bytes),
        "gate context confuses seq57 READY with seq58 checkpoint",
    )

    window = receipt.get("execution_window")
    _require(
        isinstance(window, dict) and set(window) == {"started_at", "ended_at"},
        "PASS receipt execution window differs",
    )
    started_at = _parse_timestamp(
        window.get("started_at"),
        label="gate started_at",
        second_precision=True,
    )
    ended_at = _parse_timestamp(
        window.get("ended_at"),
        label="gate ended_at",
        second_precision=True,
    )
    generated_at = _parse_timestamp(
        receipt.get("generated_at"),
        label="gate generated_at",
        second_precision=True,
    )
    _require(started_at <= ended_at <= generated_at, "gate chronology differs")
    activation = _source_activation(source, context.source_activation_event_sha256)
    activation_at = _parse_timestamp(
        activation.get("occurred_at"),
        label="activation occurred_at",
        second_precision=True,
    )
    control_at = _parse_timestamp(
        source["goal_execution"]["transition_history"][-1].get("occurred_at"),
        label="seq58 occurred_at",
        second_precision=True,
    )
    _require(
        activation_at <= control_at <= started_at,
        "gate precedes activation or seq58 control reanchor",
    )

    runs = receipt.get("check_runs")
    _require(
        isinstance(runs, list) and len(runs) == len(context.checks),
        "PASS receipt check count differs",
    )
    previous_executed_at: datetime | None = None
    repository_payload: dict[str, Any] | None = None
    repository_log_sha256 = ""
    for index, ((check_id, command), run) in enumerate(
        zip(context.checks, runs, strict=True),
        start=1,
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
        output_relative = event_dir_relative / f"{index:02d}-{check_id}.log"
        _require(
            run.get("check_id") == check_id
            and run.get("command") == command
            and run.get("exit_code") == 0
            and run.get("output_path") == output_relative.as_posix(),
            f"gate check {index} contract differs",
        )
        output = gate._private_file_bytes(
            _safe_path(root, output_relative, directory=False),
            allow_empty=False,
            maximum_bytes=gate.LOG_MAX_BYTES,
        )
        output_sha256 = sha256_bytes(output)
        _require(
            run.get("output_sha256") == output_sha256,
            f"gate check {index} output SHA-256 differs",
        )
        executed_at = _parse_timestamp(
            run.get("executed_at"),
            label=f"gate check {index} executed_at",
            second_precision=False,
        )
        _require(
            started_at <= executed_at <= ended_at,
            f"gate check {index} is outside the execution window",
        )
        _require(
            previous_executed_at is None or executed_at > previous_executed_at,
            f"gate check {index} time is not strictly increasing",
        )
        previous_executed_at = executed_at
        if check_id == "REPOSITORY_STATE":
            repository_payload = _strict_json(output, "REPOSITORY_STATE log")
            _require(
                output == contract.canonical_json_bytes(repository_payload) + b"\n",
                "REPOSITORY_STATE log is not canonical CLI output",
            )
            repository_log_sha256 = output_sha256

    _require(repository_payload is not None, "REPOSITORY_STATE evidence is missing")
    expected_snapshot = gate.repository_snapshot_from_payload(
        repository_payload,
        event_id=event_id,
        output_sha256=repository_log_sha256,
    )
    _require(
        receipt.get("repository_snapshot") == expected_snapshot,
        "PASS receipt repository snapshot differs",
    )
    current_payload = capture_repository_state(
        root,
        root / CHECKPOINT_RELATIVE,
        event_id,
    )
    _require(
        current_payload == repository_payload,
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


def _assert_zero_credit_projection(
    source: dict[str, Any],
    projected: dict[str, Any],
) -> None:
    for key in (
        "approved_state",
        "authority_boundary",
        "verification_boundary",
        "canonical_bindings",
    ):
        _require(projected.get(key) == source.get(key), f"seq59 changed {key}")
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
            projected_state.get(key) == source_state.get(key),
            f"seq59 changed zero-credit state: {key}",
        )
    expected_statuses = copy.deepcopy(source_state["status_by_goal"])
    expected_statuses[gate.TARGET_GOAL_ID] = "IN_PROGRESS"
    _require(
        projected_state["status_by_goal"] == expected_statuses
        and list(expected_statuses.values()).count("IN_PROGRESS") == 1,
        "seq59 status projection is not a single READY-to-IN_PROGRESS change",
    )
    _require_zero_credit_source(
        {
            **projected,
            "current_work": {
                **projected["current_work"],
                "status": "READY",
            },
        }
    )
    _require(
        projected["current_work"].get("status") == "IN_PROGRESS"
        and projected["current_work"].get("release_completion_claimed") is False,
        "seq59 current-work status or release boundary differs",
    )


def project_seq59(
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
    control = state["transition_history"][-1]
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
        "runtime_after": copy.deepcopy(control["runtime_after"]),
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
        "previous_event_sha256": control["event_sha256"],
    }
    event["event_sha256"] = contract.event_sha256(event)
    _require(
        set(event) == contract.V24_FIRST_START_EVENT_FIELDS,
        "seq59 GOAL_STARTED field set differs",
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
        "seq1-58 history prefix changed",
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
            f"seq59 changed focus/frontier field: {field}",
        )
    _assert_zero_credit_projection(source, checkpoint)
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
    source_errors = live_validator(root)
    _require(
        not source_errors,
        "live seq58 validation failed: " + "; ".join(source_errors),
    )
    context = context_loader(root)
    _require(
        context.target_goal_sha256 == gate.TARGET_GOAL_SHA256
        and context.source_ready_event_sha256 == gate.SOURCE_READY_EVENT_SHA256
        and context.checkpoint_sha256 == sha256_bytes(source_bytes)
        and context.contract_binding == gate.expected_contract_binding(),
        "NPC R002 gate context differs",
    )
    _require(
        checkpoint_path.read_bytes() == source_bytes,
        "source checkpoint changed during preflight",
    )
    evidence = validate_gate_evidence(
        root,
        source,
        source_bytes,
        context,
        event_id=event_id,
        capture_repository_state=capture_repository_state,
    )
    _require(
        checkpoint_path.read_bytes() == source_bytes,
        "source checkpoint changed during gate evidence validation",
    )
    projected, event = project_seq59(
        root,
        source,
        evidence,
        event_id=event_id,
    )
    projected_errors = projected_validator(root, projected)
    _require(
        not projected_errors,
        "projected seq59 validation failed: " + "; ".join(projected_errors),
    )
    _require(
        checkpoint_path.read_bytes() == source_bytes,
        "source checkpoint changed during projected validation",
    )
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
        (
            relative.as_posix(),
            contract.sha256_file(prepared.root / relative),
        )
        for relative in gate.RUNTIME_BINDING_RELATIVES
    )
    _require(
        runtime_bindings == prepared.context.runtime_bindings,
        "NPC R002 runtime binding changed",
    )


def write_projection(
    prepared: PreparedProjection,
    *,
    atomic_writer: Callable[..., None] = atomic.atomic_write,
) -> None:
    def full_commit_guard() -> None:
        _require(
            prepared.checkpoint_path.read_bytes()
            == prepared.source_checkpoint_bytes,
            "source checkpoint changed before seq59 publication",
        )
        require_exact_source(prepared.root, prepared.source_checkpoint)
        _require_static_context(prepared)
        evidence = validate_gate_evidence(
            prepared.root,
            prepared.source_checkpoint,
            prepared.source_checkpoint_bytes,
            prepared.context,
            event_id=prepared.event_id,
            capture_repository_state=contract.capture_gate_repository_state,
        )
        _require(evidence == prepared.evidence, "gate evidence changed before write")

    def transaction_guard() -> None:
        checkpoint_bytes = prepared.checkpoint_path.read_bytes()
        _require(
            checkpoint_bytes
            in {
                prepared.source_checkpoint_bytes,
                prepared.projected_checkpoint_bytes,
            },
            "checkpoint changed at the seq59 transaction boundary",
        )
        source_snapshot = prepared.source_checkpoint["working_tree_snapshot"]
        source_path, source_content = contract.working_snapshot_hashes(
            prepared.root,
            source_snapshot["managed_changed_paths"],
        )
        _require(
            source_path == source_snapshot["path_set_sha256"]
            and source_content == source_snapshot["content_set_sha256"],
            "controlled seq58 source changed at the transaction boundary",
        )
        projected_snapshot = prepared.projected_checkpoint["working_tree_snapshot"]
        projected_path, projected_content = contract.working_snapshot_hashes(
            prepared.root,
            projected_snapshot["managed_changed_paths"],
        )
        _require(
            projected_path == projected_snapshot["path_set_sha256"]
            and projected_content == projected_snapshot["content_set_sha256"],
            "projected seq59 controlled content changed at the transaction boundary",
        )
        _require_static_context(prepared)
        evidence = validate_gate_evidence(
            prepared.root,
            prepared.source_checkpoint,
            prepared.source_checkpoint_bytes,
            prepared.context,
            event_id=prepared.event_id,
            capture_repository_state=contract.capture_gate_repository_state,
        )
        _require(
            evidence == prepared.evidence,
            "gate evidence changed at the seq59 transaction boundary",
        )

    full_commit_guard()
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
        "published seq59 checkpoint authority differs",
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
        reanchor.ControlReanchorError,
        gate.GateError,
        atomic.CompletionApplyError,
        atomic.CompletionPostCommitError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exc:
        print(f"NPC GOAL_STARTED seq59: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "NPC GOAL_STARTED seq59: PASS "
        f"mode={mode} event_sha256={prepared.event['event_sha256']} "
        f"occurred_at={prepared.event['occurred_at']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
