#!/usr/bin/env python3
"""Append FP-022 GOAL_STARTED seq69 after the seq68 reanchor and PASS gate."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass, field
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as atomic
from scripts import apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814 as reanchor
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import run_walksafe_fp022_goal_start_gate_20260813 as gate


CHECKPOINT_RELATIVE = gate.CHECKPOINT_RELATIVE
CATALOG_PATHS = reanchor.CATALOG_PATHS
catalogs = reanchor.catalogs
npc = reanchor.npc
EVENT_SEQUENCE = 69
EVENT_ID = gate.STARTED_EVENT_ID
SCRIPT_RELATIVE = Path("scripts/apply_walksafe_fp022_goal_started_seq69_20260814.py")
TEST_RELATIVE = Path("tests/test_apply_walksafe_fp022_goal_started_seq69_20260814.py")
STARTED_CURRENT_FOCUS = (
    "FP-022/GAP-031 GOAL_STARTED/IN_PROGRESS; repository-internal TMAP "
    "destination route implementation authorized"
)
STARTED_SCOPE = (
    "Graph v2.4 through FP022/GAP-031 GOAL_STARTED seq69 after the exact "
    "seven-check internal PASS gate; no product completion, artifact completion, "
    "formal-test, device, external, deployment, approval, or release credit."
)
STARTED_HANDOFF_EPIC = "EPIC-04 / FP-022/GAP-031 IN_PROGRESS"
STARTED_VERIFICATION_STATUS = (
    "PASS_WITH_FP022_IN_PROGRESS_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
)


class StartApplyError(RuntimeError):
    """The exact seq68 source and private PASS gate do not authorize seq69."""


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
    final_sha256_by_path: Mapping[Path, str] = field(default_factory=dict)
    physical_sha256_by_path: Mapping[Path, str] = field(default_factory=dict)
    source_universe: tuple[str, ...] = ()
    candidate_catalogs: Mapping[Path, bytes] = field(default_factory=dict)
    catalogs_verified: bool = False


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
            _require(key not in result, f"{label} contains duplicate key: {key}")
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
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
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


def _source_activation(source: dict[str, Any], digest: str) -> dict[str, Any]:
    matches = [
        event
        for event in source["goal_execution"]["transition_history"]
        if isinstance(event, dict)
        and event.get("event_type") == "PACKAGE_ACTIVATED"
        and event.get("event_sha256") == digest
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


def require_exact_source(
    root: Path,
    source: dict[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> None:
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    reanchor.require_control_reanchored_checkpoint(
        root,
        source,
        run_external_validators=False,
        require_live_snapshot=require_live_snapshot,
    )
    ready_sha256, _ = gate._validate_ready_source(
        source,
        contract_binding=gate.expected_contract_binding(),
    )
    _require(
        ready_sha256 == gate.SOURCE_READY_EVENT_SHA256,
        "source seq67 READY trust anchor differs",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(
        isinstance(history, list)
        and len(history) == EVENT_SEQUENCE - 1
        and gate.SOURCE_SEQUENCE == reanchor.CONTROL_REANCHOR_SEQUENCE
        == EVENT_SEQUENCE - 1,
        "source is not exact seq68",
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
    _require(
        ready.get("sequence") == gate.SOURCE_READY_SEQUENCE
        and ready.get("event_id") == gate.READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == gate.TARGET_GOAL_ID
        and ready.get("event_sha256") == gate.SOURCE_READY_EVENT_SHA256,
        "source seq67 READY event differs",
    )
    supersession = control.get("contract_supersession")
    replacement = (
        supersession.get("replacement_contract_binding")
        if isinstance(supersession, dict)
        else None
    )
    _require(
        control.get("sequence") == reanchor.CONTROL_REANCHOR_SEQUENCE
        and control.get("event_id") == reanchor.CONTROL_REANCHOR_EVENT_ID
        and control.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and control.get("subject_goal_id") == gate.TARGET_GOAL_ID
        and control.get("from_status") == control.get("to_status") == "READY"
        and control.get("status_changes") == {}
        and control.get("previous_event_sha256") == gate.SOURCE_READY_EVENT_SHA256
        and replacement == gate.expected_contract_binding(),
        "source seq68 control reanchor differs",
    )
    if hasattr(reanchor, "CONTROL_REANCHOR_EVENT_SHA256"):
        _require(
            control.get("event_sha256") == reanchor.CONTROL_REANCHOR_EVENT_SHA256,
            "source seq68 control reanchor trust anchor differs",
        )
    statuses = state.get("status_by_goal")
    _require(
        state.get("transition_history_anchor_sha256") == control.get("event_sha256")
        and isinstance(statuses, dict)
        and statuses.get(gate.TARGET_GOAL_ID) == "READY"
        and list(statuses.values()).count("IN_PROGRESS") == 0,
        "source FP022 Goal is not READY without another IN_PROGRESS Goal",
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


def run_continuation_checker(root: Path, checkpoint_path: Path) -> list[str]:
    return contract.validate(
        root,
        checkpoint_path,
        contract.V23_ARCHIVE_RELATIVE,
        contract.V24_MANIFEST_RELATIVE,
    )


def run_goal_graph_checker(root: Path, checkpoint_path: Path) -> list[str]:
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
    continuation_checker: Callable[[Path, Path], list[str]] = (
        run_continuation_checker
    ),
    goal_graph_checker: Callable[[Path, Path], list[str]] = run_goal_graph_checker,
) -> list[str]:
    checkpoint_dir = root / CHECKPOINT_RELATIVE.parent
    descriptor, temporary_name = tempfile.mkstemp(
        dir=checkpoint_dir,
        prefix=".walksafe-fp022-seq69-preflight.",
        suffix=".json",
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(json_bytes(checkpoint))
            stream.flush()
            os.fsync(stream.fileno())
        relative = temporary.relative_to(root)
        errors = [
            f"continuation: {error}"
            for error in continuation_checker(root, relative)
        ]
        errors.extend(
            f"goal-graph: {error}"
            for error in goal_graph_checker(root, relative)
        )
        return errors
    finally:
        temporary.unlink(missing_ok=True)


def _derive_event_time(source: dict[str, Any], generated_at: datetime) -> datetime:
    control_time = _parse_timestamp(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        label="seq68 occurred_at",
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
    _require(event_id == EVENT_ID, "seq69 event ID differs")
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
    _require(
        tuple(check_id for check_id, _ in context.checks) == gate.EXPECTED_CHECK_IDS,
        "gate context is not the exact seven-check contract",
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
        "implementation_start_gate_contract_binding": gate.expected_contract_binding(),
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
        "gate context confuses seq67 READY with raw seq68 checkpoint",
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
        activation.get("occurred_at"), label="activation occurred_at", second_precision=True
    )
    control_at = _parse_timestamp(
        source["goal_execution"]["transition_history"][-1].get("occurred_at"),
        label="seq68 occurred_at",
        second_precision=True,
    )
    _require(
        activation_at <= control_at <= started_at,
        "gate precedes activation or seq68 control reanchor",
    )
    runs = receipt.get("check_runs")
    _require(
        isinstance(runs, list) and len(runs) == len(context.checks) == 7,
        "PASS receipt check count differs",
    )
    previous_executed_at: datetime | None = None
    repository_payload: dict[str, Any] | None = None
    repository_log_sha256 = ""
    for index, ((check_id, command), run) in enumerate(
        zip(context.checks, runs, strict=True), start=1
    ):
        _require(isinstance(run, dict), f"gate check {index} is not an object")
        _require(
            set(run)
            == {
                "check_id", "command", "executed_at", "exit_code",
                "output_path", "output_sha256",
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


def _sealed_gate_repository_payload(
    root: Path,
    _checkpoint_path: Path,
    event_id: str,
) -> dict[str, Any]:
    _require(event_id == EVENT_ID, "seq69 event ID differs")
    repository_index = gate.EXPECTED_CHECK_IDS.index("REPOSITORY_STATE") + 1
    relative = (
        gate.GATE_ROOT_RELATIVE
        / event_id
        / f"{repository_index:02d}-REPOSITORY_STATE.log"
    )
    raw = gate._private_file_bytes(
        _safe_path(root, relative, directory=False),
        allow_empty=False,
        maximum_bytes=gate.LOG_MAX_BYTES,
    )
    payload = _strict_json(raw, "sealed REPOSITORY_STATE log")
    _require(
        raw == contract.canonical_json_bytes(payload) + b"\n",
        "sealed REPOSITORY_STATE log is not canonical",
    )
    return payload


def _assert_zero_credit_projection(
    source: dict[str, Any], projected: dict[str, Any]
) -> None:
    for key in (
        "approved_state", "authority_boundary", "verification_boundary",
        "canonical_bindings",
    ):
        _require(projected.get(key) == source.get(key), f"seq69 changed {key}")
    source_state = source["goal_execution"]
    projected_state = projected["goal_execution"]
    for key in (
        "completion_evidence_by_goal", "archived_completion_evidence_by_goal",
        "imported_predecessor_goal_bindings", "verification_evidence_refs",
        "artifact_work_queue", "completion_boundary", "dynamic_goal_inventory",
        "materialized_child_goal_ids_by_parent", "blockers_by_goal",
        "blocked_goal_ids", "pending_questions", "open_question_count",
    ):
        _require(
            projected_state.get(key) == source_state.get(key),
            f"seq69 changed zero-credit state: {key}",
        )
    expected_statuses = copy.deepcopy(source_state["status_by_goal"])
    expected_statuses[gate.TARGET_GOAL_ID] = "IN_PROGRESS"
    _require(
        projected_state["status_by_goal"] == expected_statuses
        and list(expected_statuses.values()).count("IN_PROGRESS") == 1,
        "seq69 status projection is not one READY-to-IN_PROGRESS change",
    )
    _require_zero_credit_source(
        {
            **projected,
            "current_work": {**projected["current_work"], "status": "READY"},
        }
    )
    _require(
        projected["current_work"].get("status") == "IN_PROGRESS"
        and projected["current_work"].get("release_completion_claimed") is False,
        "seq69 current-work status or release boundary differs",
    )


def project_seq69(
    root: Path,
    source: dict[str, Any],
    evidence: GateEvidence,
    *,
    event_id: str,
    final_sha256_by_path: Mapping[Path, str] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    _require(event_id == EVENT_ID, "seq69 event ID differs")
    require_exact_source(root, source, require_live_snapshot=False)
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
        "repository_snapshot_before": copy.deepcopy(evidence.receipt["repository_snapshot"]),
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
    _require(
        set(event) == contract.V24_FIRST_START_EVENT_FIELDS,
        "seq69 GOAL_STARTED field set differs",
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
    if final_sha256_by_path is None:
        path_sha256, content_sha256 = contract.working_snapshot_hashes(root, paths)
    else:
        _require(
            set(paths) == {path.as_posix() for path in final_sha256_by_path},
            "seq69 final managed path inventory differs",
        )
        path_sha256, content_sha256 = npc._snapshot_hashes_from_digests(
            final_sha256_by_path
        )
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
        "seq1-68 history prefix changed",
    )
    for field in (
        "focus_goal_id", "focus_goal_path", "focus_work_item_id",
        "focus_source", "ready_frontier_goal_ids",
    ):
        _require(state[field] == source_state[field], f"seq69 changed {field}")
    _assert_zero_credit_projection(source, checkpoint)
    return checkpoint, event


def prepare_projection(
    root: Path,
    *,
    event_id: str,
    allow_stale_catalogs: bool = False,
    live_validator: Callable[[Path], list[str]] = validate_live_source,
    projected_validator: Callable[[Path, dict[str, Any]], list[str]] = validate_projected_checkpoint,
    context_loader: Callable[[Path], gate.GateContext] = gate.load_gate_context,
    capture_repository_state: Callable[[Path, Path, str], dict[str, Any]] = (
        contract.capture_gate_repository_state
    ),
) -> PreparedProjection:
    root = root.resolve(strict=True)
    _require(event_id == EVENT_ID, "seq69 event ID differs")
    gate._document_id(event_id)
    checkpoint_path, source_bytes, source = _read_checkpoint(root)
    require_exact_source(root, source, require_live_snapshot=False)
    context = (
        context_loader(root, require_live_snapshot=False)
        if context_loader is gate.load_gate_context
        else context_loader(root)
    )
    _require(
        context.target_goal_sha256 == gate.TARGET_GOAL_SHA256
        and context.source_ready_event_sha256 == gate.SOURCE_READY_EVENT_SHA256
        and context.checkpoint_sha256 == sha256_bytes(source_bytes)
        and context.contract_binding == gate.expected_contract_binding(),
        "FP022 R002 gate context differs",
    )
    evidence_capture = (
        _sealed_gate_repository_payload
        if capture_repository_state is contract.capture_gate_repository_state
        else capture_repository_state
    )
    evidence = validate_gate_evidence(
        root,
        source,
        source_bytes,
        context,
        event_id=event_id,
        capture_repository_state=evidence_capture,
    )
    _require(checkpoint_path.read_bytes() == source_bytes, "source checkpoint changed")
    universe = catalogs.discover_source_paths(root)
    physical = reanchor.aggregate._snapshot_digests(root, source)
    draft, _ = project_seq69(
        root,
        source,
        evidence,
        event_id=event_id,
        final_sha256_by_path=physical,
    )
    candidate = npc._build_candidate_catalog_bytes(root, universe, draft)
    final = dict(physical)
    final.update({path: sha256_bytes(raw) for path, raw in candidate.items()})
    projected, event = project_seq69(
        root,
        source,
        evidence,
        event_id=event_id,
        final_sha256_by_path=final,
    )
    _require(
        npc._build_candidate_catalog_bytes(root, universe, projected) == candidate,
        "seq69 catalog/checkpoint projection did not reach a fixed point",
    )
    if not allow_stale_catalogs:
        def require_catalog_closure() -> None:
            _require(
                checkpoint_path.read_bytes() == source_bytes,
                "source checkpoint changed during seq69 preflight",
            )
            npc._require_candidate_catalogs_exact(
                root, universe, candidate, checkpoint=projected
            )
            npc._require_physical_matches(root, final)
            _require(
                contract.working_snapshot_hashes(
                    root, sorted(path.as_posix() for path in final)
                )
                == npc._snapshot_hashes_from_digests(final),
                "live seq69 managed snapshot differs",
            )
            npc._require_git_visible_changes_are_managed(root, final)

        require_catalog_closure()
        errors = projected_validator(root, projected)
        _require(not errors, "projected seq69 validation failed: " + "; ".join(errors))
        require_catalog_closure()
    prepared = PreparedProjection(
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
        final_sha256_by_path=final,
        physical_sha256_by_path=dict(final),
        source_universe=universe,
        candidate_catalogs=candidate,
        catalogs_verified=not allow_stale_catalogs,
    )
    _require(
        checkpoint_path.read_bytes() == source_bytes,
        "source checkpoint changed during projected checker",
    )
    if not allow_stale_catalogs:
        snapshot = projected["working_tree_snapshot"]
        _require(
            contract.working_snapshot_hashes(root, snapshot["managed_changed_paths"])
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "controlled projected content changed during projected checker",
        )
    _require_static_context(prepared)
    refreshed_context = (
        context_loader(root, require_live_snapshot=False)
        if context_loader is gate.load_gate_context
        else context_loader(root)
    )
    _require(refreshed_context == context, "gate context changed during checker")
    observed_phase = _require_live_repository_recapture(
        prepared,
        source_bytes,
    )
    _require(observed_phase is not None, "catalog transition phase is missing")
    if allow_stale_catalogs and observed_phase == 0:
        errors = live_validator(root)
        _require(not errors, "live seq68 validation failed: " + "; ".join(errors))
        _require_live_repository_recapture(
            prepared,
            source_bytes,
            expected_catalog_phases={observed_phase},
        )
    refreshed_evidence = validate_gate_evidence(
        root,
        source,
        source_bytes,
        refreshed_context,
        event_id=event_id,
        capture_repository_state=evidence_capture,
    )
    _require(refreshed_evidence == evidence, "gate evidence changed during checker")
    _require_live_repository_recapture(
        prepared,
        source_bytes,
        expected_catalog_phases={observed_phase},
    )
    _require(
        checkpoint_path.read_bytes() == source_bytes,
        "source checkpoint changed during checker revalidation",
    )
    return prepared


def _require_static_context(prepared: PreparedProjection) -> None:
    checks, _ = gate._load_gate_contract(prepared.root)
    _require(
        checks == prepared.context.checks
        and gate.expected_contract_binding() == prepared.context.contract_binding
        and contract.sha256_file(prepared.root / gate.GOAL_RELATIVE)
        == gate.TARGET_GOAL_SHA256
        and contract.sha256_file(prepared.root / gate.MANIFEST_RELATIVE)
        == gate.MANIFEST_SHA256,
        "static FP022 R002 gate context changed",
    )
    runtime_bindings = tuple(
        (relative.as_posix(), contract.sha256_file(prepared.root / relative))
        for relative in gate.RUNTIME_BINDING_RELATIVES
    )
    _require(
        runtime_bindings == prepared.context.runtime_bindings,
        "FP022 R002 runtime binding changed",
    )


def _require_live_repository_recapture(
    prepared: PreparedProjection,
    checkpoint_bytes: bytes,
    *,
    expected_catalog_phases: set[int] | None = None,
) -> int | None:
    staging_relative = _atomic_staging_relative(prepared, checkpoint_bytes)
    expected = prepared.evidence.repository_payload
    catalog_transitions = (
        _catalog_transition_snapshot_transitions(
            expected,
            prepared.candidate_catalogs,
        )
        if (
            prepared.candidate_catalogs
            and checkpoint_bytes == prepared.source_checkpoint_bytes
        )
        else None
    )
    capture_options: dict[str, Any] = {}
    if staging_relative is not None:
        capture_options["ephemeral_exact_exclusion"] = staging_relative
    if catalog_transitions is not None:
        capture_options["controlled_snapshot_transitions"] = catalog_transitions
    captured = contract.capture_gate_repository_state(
        prepared.root,
        prepared.checkpoint_path,
        prepared.event_id,
        **capture_options,
    )
    if staging_relative is not None:
        captured = copy.deepcopy(captured)
        exclusions = captured.get("transaction_exclusions")
        _require(
            isinstance(exclusions, dict)
            and exclusions.get("allowed_rule_count") == 3
            and exclusions.get("ephemeral_exact_path") == staging_relative,
            "atomic staging exclusion differs",
        )
        exclusions.pop("ephemeral_exact_path")
        exclusions["allowed_rule_count"] = 2
    if checkpoint_bytes == prepared.projected_checkpoint_bytes:
        captured = copy.deepcopy(captured)
        captured["checkpoint_controlled_working_snapshot"] = copy.deepcopy(
            expected["checkpoint_controlled_working_snapshot"]
        )
    if prepared.candidate_catalogs:
        captured, phase = _normalize_catalog_transition(
            captured,
            expected,
            prepared.candidate_catalogs,
        )
        if expected_catalog_phases is not None:
            _require(
                phase in expected_catalog_phases,
                "catalog transition phase differs",
            )
    _require(
        captured == expected,
        "independent live repository recapture differs from the PASS gate",
    )
    return phase if prepared.candidate_catalogs else None


def _catalog_transition_snapshot_transitions(
    expected: dict[str, Any],
    candidate_catalogs: Mapping[Path, bytes],
) -> tuple[dict[str, Any], ...]:
    expected_dirty = expected.get("dirty_snapshot")
    _require(
        isinstance(expected_dirty, dict)
        and isinstance(expected_dirty.get("paths"), list),
        "gate catalog predecessor snapshot differs",
    )
    expected_by_path = {
        row.get("path"): row
        for row in expected_dirty["paths"]
        if isinstance(row, dict) and isinstance(row.get("path"), str)
    }
    _require(
        set(candidate_catalogs) == set(CATALOG_PATHS),
        "candidate catalog transition inventory differs",
    )
    transitions: list[dict[str, Any]] = []
    for relative_path in CATALOG_PATHS:
        relative = relative_path.as_posix()
        predecessor = expected_by_path.get(relative)
        worktree = predecessor.get("worktree") if isinstance(predecessor, dict) else None
        _require(
            isinstance(worktree, dict)
            and worktree.get("state") == "PRESENT"
            and worktree.get("type") == "REGULAR_FILE"
            and isinstance(worktree.get("mode"), str)
            and type(worktree.get("byte_count")) is int
            and worktree["byte_count"] >= 0
            and isinstance(worktree.get("sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", worktree["sha256"]) is not None
            and worktree.get("deletion_marker") is None
            and worktree.get("symlink_target_sha256") is None,
            f"gate catalog predecessor worktree differs: {relative}",
        )
        candidate = candidate_catalogs[relative_path]
        candidate_worktree = copy.deepcopy(worktree)
        candidate_worktree["sha256"] = sha256_bytes(candidate)
        candidate_worktree["byte_count"] = len(candidate)
        transitions.append(
            {
                "path": relative,
                "predecessor_worktree": copy.deepcopy(worktree),
                "candidate_worktree": candidate_worktree,
            }
        )
    return tuple(transitions)


def _normalize_catalog_transition(
    captured: dict[str, Any],
    expected: dict[str, Any],
    candidate_catalogs: Mapping[Path, bytes],
) -> tuple[dict[str, Any], int]:
    normalized = copy.deepcopy(captured)
    current_dirty = normalized.get("dirty_snapshot")
    expected_dirty = expected.get("dirty_snapshot")
    _require(
        isinstance(current_dirty, dict)
        and isinstance(expected_dirty, dict)
        and isinstance(current_dirty.get("paths"), list)
        and isinstance(expected_dirty.get("paths"), list),
        "repository dirty snapshot differs",
    )
    expected_by_path = {
        row.get("path"): row
        for row in expected_dirty["paths"]
        if isinstance(row, dict) and isinstance(row.get("path"), str)
    }
    catalog_paths = {path.as_posix() for path in CATALOG_PATHS}
    _require(
        set(candidate_catalogs) == set(CATALOG_PATHS),
        "candidate catalog transition inventory differs",
    )
    observed_catalogs: set[str] = set()
    transition_states: dict[str, str] = {}
    for index, row in enumerate(current_dirty["paths"]):
        if not isinstance(row, dict) or row.get("path") not in catalog_paths:
            continue
        relative = row["path"]
        predecessor = expected_by_path.get(relative)
        _require(isinstance(predecessor, dict), "gate catalog predecessor is missing")
        _require(
            {key: value for key, value in row.items() if key != "worktree"}
            == {
                key: value
                for key, value in predecessor.items()
                if key != "worktree"
            },
            f"gate catalog Git state differs: {relative}",
        )
        predecessor_worktree = predecessor.get("worktree")
        _require(
            isinstance(predecessor_worktree, dict),
            f"gate catalog predecessor worktree differs: {relative}",
        )
        candidate = candidate_catalogs[Path(relative)]
        candidate_worktree = copy.deepcopy(predecessor_worktree)
        candidate_worktree["sha256"] = sha256_bytes(candidate)
        candidate_worktree["byte_count"] = len(candidate)
        if row.get("worktree") == predecessor_worktree:
            transition_states[relative] = "P"
        elif row.get("worktree") == candidate_worktree:
            transition_states[relative] = "C"
        else:
            raise StartApplyError(
                "catalog worktree is neither gate predecessor nor candidate: "
                f"{relative}"
            )
        current_dirty["paths"][index] = copy.deepcopy(predecessor)
        observed_catalogs.add(relative)
    _require(
        observed_catalogs == catalog_paths,
        "gate catalog transition inventory differs",
    )
    ordered_states = [transition_states[path.as_posix()] for path in CATALOG_PATHS]
    phase = ordered_states.count("C")
    _require(
        ordered_states == ["C"] * phase + ["P"] * (len(ordered_states) - phase),
        "catalog transition order differs",
    )
    current_dirty["content_set_sha256"] = contract.canonical_json_sha256(
        [
            {"path": row["path"], "worktree": row["worktree"]}
            for row in current_dirty["paths"]
        ]
    )
    return normalized, phase


def _read_catalog_transition_bytes(
    root: Path,
    relative: Path,
    predecessor_worktree: Mapping[str, Any],
    candidate: bytes,
) -> bytes:
    predecessor_sha256 = predecessor_worktree.get("sha256")
    predecessor_byte_count = predecessor_worktree.get("byte_count")
    _require(
        isinstance(predecessor_sha256, str)
        and re.fullmatch(r"[0-9a-f]{64}", predecessor_sha256) is not None
        and type(predecessor_byte_count) is int
        and predecessor_byte_count >= 0,
        f"gate catalog predecessor binding differs: {relative}",
    )
    utility = contract._load_frozen_v23_utility()
    parent_descriptor, name = utility._open_repo_parent_directory(
        root,
        relative.as_posix(),
    )
    _require(parent_descriptor is not None, f"catalog path is missing: {relative}")
    descriptor: int | None = None
    try:
        parent_before = os.fstat(parent_descriptor)
        before = os.stat(
            name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
        _require(
            stat.S_ISREG(before.st_mode)
            and stat.S_IMODE(before.st_mode) == 0o600
            and before.st_uid == os.geteuid()
            and before.st_nlink == 1,
            f"catalog authority differs: {relative}",
        )
        descriptor = os.open(
            name,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=parent_descriptor,
        )
        opened = os.fstat(descriptor)
        maximum = max(predecessor_byte_count, len(candidate))
        first = os.pread(descriptor, maximum + 1, 0)
        middle = os.fstat(descriptor)
        second = os.pread(descriptor, maximum + 1, 0)
        after = os.fstat(descriptor)
        entry_after = os.stat(
            name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
        parent_after = os.fstat(parent_descriptor)
    except OSError as exc:
        raise StartApplyError(f"catalog authority differs: {relative}") from exc
    finally:
        if descriptor is not None:
            os.close(descriptor)
        os.close(parent_descriptor)
    def signature(value: os.stat_result) -> tuple[int, ...]:
        return (
            value.st_dev,
            value.st_ino,
            value.st_mode,
            value.st_uid,
            value.st_gid,
            value.st_nlink,
            value.st_size,
            value.st_mtime_ns,
            value.st_ctime_ns,
        )
    _require(
        first == second
        and signature(before)
        == signature(opened)
        == signature(middle)
        == signature(after)
        == signature(entry_after)
        and (parent_before.st_dev, parent_before.st_ino)
        == (parent_after.st_dev, parent_after.st_ino),
        f"catalog bytes or identity changed: {relative}",
    )
    binding = (sha256_bytes(first), len(first))
    _require(
        binding
        in {
            (predecessor_sha256, predecessor_byte_count),
            (sha256_bytes(candidate), len(candidate)),
        },
        f"catalog is neither gate predecessor nor candidate: {relative}",
    )
    return first


def _atomic_staging_relative(
    prepared: PreparedProjection,
    checkpoint_bytes: bytes,
) -> str | None:
    pattern = re.compile(
        rf"^\.{re.escape(prepared.checkpoint_path.name)}\.fp048-seq43-44\."
        r"[0-9a-f]{24}\.tmp$"
    )
    candidates = sorted(
        (
            path
            for path in prepared.checkpoint_path.parent.iterdir()
            if pattern.fullmatch(path.name)
        ),
        key=lambda path: path.name,
    )
    _require(len(candidates) <= 1, "atomic staging path is ambiguous")
    if not candidates:
        return None
    staging = candidates[0]
    before = staging.lstat()
    _require(
        stat.S_ISREG(before.st_mode)
        and stat.S_IMODE(before.st_mode) == 0o600
        and before.st_uid == os.geteuid()
        and before.st_nlink == 1,
        "atomic staging authority differs",
    )
    expected = (
        prepared.projected_checkpoint_bytes
        if checkpoint_bytes == prepared.source_checkpoint_bytes
        else prepared.source_checkpoint_bytes
    )
    first = staging.read_bytes()
    second = staging.read_bytes()
    after = staging.lstat()
    _require(
        first == second == expected
        and (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns),
        "atomic staging bytes or identity differ",
    )
    try:
        return staging.relative_to(prepared.root).as_posix()
    except ValueError as exc:
        raise StartApplyError("atomic staging path escapes repository") from exc


def write_catalogs(prepared: PreparedProjection) -> None:
    _require(not prepared.catalogs_verified, "catalog refresh requires stale catalogs")
    transitions = _catalog_transition_snapshot_transitions(
        prepared.evidence.repository_payload,
        prepared.candidate_catalogs,
    )
    transition_by_path = {Path(row["path"]): row for row in transitions}

    def guard(
        target: Path | None = None,
        source: bytes | None = None,
        wanted: bytes | None = None,
        expected_phases: set[int] | None = None,
    ) -> int:
        _require(
            prepared.checkpoint_path.read_bytes()
            == prepared.source_checkpoint_bytes,
            "source checkpoint changed during seq69 catalog refresh",
        )
        _require(
            reanchor._catalog_source_universe_during_atomic_write(
                prepared.root,
                prepared.source_universe,
                target=target,
                source=source,
                wanted=wanted,
            )
            == prepared.source_universe,
            "catalog source universe changed during seq69 refresh",
        )
        _require(
            npc._build_candidate_catalog_bytes(
                prepared.root,
                prepared.source_universe,
                prepared.projected_checkpoint,
            )
            == prepared.candidate_catalogs,
            "seq69 catalog projection changed during refresh",
        )
        _require_static_context(prepared)
        phase = _require_live_repository_recapture(
            prepared,
            prepared.source_checkpoint_bytes,
            expected_catalog_phases=expected_phases,
        )
        _require(phase is not None, "catalog transition phase is missing")
        return phase

    observed_phase = guard(expected_phases=set(range(len(CATALOG_PATHS) + 1)))
    for index in range(observed_phase, len(CATALOG_PATHS)):
        relative = CATALOG_PATHS[index]
        target = npc._safe_file(prepared.root, relative)
        wanted = prepared.candidate_catalogs[relative]
        source = _read_catalog_transition_bytes(
            prepared.root,
            relative,
            transition_by_path[relative]["predecessor_worktree"],
            wanted,
        )
        if source != wanted:
            guard(expected_phases={index})

            def catalog_guard(
                index: int = index,
                target: Path = target,
                source: bytes = source,
                wanted: bytes = wanted,
            ) -> None:
                guard(target, source, wanted, {index, index + 1})

            npc.atomic_write(
                target,
                wanted,
                expected_source=source,
                commit_guard=catalog_guard,
            )
        observed_phase = guard(expected_phases={index + 1})
    _require(
        observed_phase == len(CATALOG_PATHS),
        "catalog transition did not reach the final phase",
    )
    guard(expected_phases={len(CATALOG_PATHS)})


def write_projection(
    prepared: PreparedProjection,
    *,
    atomic_writer: Callable[..., None] = atomic.atomic_write,
) -> None:
    _require(prepared.catalogs_verified, "seq69 catalogs are not verified")

    def require_transaction_inputs() -> bytes:
        checkpoint_bytes = prepared.checkpoint_path.read_bytes()
        _require(
            checkpoint_bytes
            in {prepared.source_checkpoint_bytes, prepared.projected_checkpoint_bytes},
            "checkpoint changed at the seq69 transaction boundary",
        )
        snapshot = prepared.projected_checkpoint["working_tree_snapshot"]
        path_sha256, content_sha256 = contract.working_snapshot_hashes(
            prepared.root,
            snapshot["managed_changed_paths"],
        )
        _require(
            path_sha256 == snapshot["path_set_sha256"]
            and content_sha256 == snapshot["content_set_sha256"],
            "controlled projected content changed at the transaction boundary",
        )
        _require_static_context(prepared)
        if prepared.candidate_catalogs:
            def catalog_source_loader(root: Path) -> tuple[str, ...]:
                universe = catalogs.discover_source_paths(root)
                staging_relative = _atomic_staging_relative(
                    prepared, checkpoint_bytes
                )
                if staging_relative is None:
                    return universe
                _require(
                    universe.count(staging_relative) == 1,
                    "checkpoint staging source-universe entry differs",
                )
                return tuple(
                    relative
                    for relative in universe
                    if relative != staging_relative
                )

            npc._require_candidate_catalogs_exact(
                prepared.root,
                prepared.source_universe,
                prepared.candidate_catalogs,
                checkpoint=prepared.projected_checkpoint,
                catalog_source_loader=catalog_source_loader,
            )
            npc._require_physical_matches(
                prepared.root, prepared.physical_sha256_by_path
            )
            npc._require_git_visible_changes_are_managed(
                prepared.root, prepared.final_sha256_by_path
            )
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
        return checkpoint_bytes

    def transaction_guard() -> None:
        checkpoint_bytes = require_transaction_inputs()
        errors = validate_projected_checkpoint(
            prepared.root,
            prepared.projected_checkpoint,
        )
        _require(
            not errors,
            "projected seq69 transaction validation failed: " + "; ".join(errors),
        )
        _require(
            require_transaction_inputs() == checkpoint_bytes,
            "checkpoint changed during projected seq69 transaction validation",
        )

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
        and prepared.checkpoint_path.read_bytes() == prepared.projected_checkpoint_bytes,
        "published seq69 checkpoint authority differs",
    )
    transaction_guard()


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--refresh-catalogs", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.refresh_catalogs:
            stale = prepare_projection(
                args.root,
                event_id=args.event_id,
                allow_stale_catalogs=True,
            )
            write_catalogs(stale)
            prepared = prepare_projection(args.root, event_id=args.event_id)
            mode = "REFRESH_CATALOGS"
        else:
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
        print(f"FP022 GOAL_STARTED seq69: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "FP022 GOAL_STARTED seq69: PASS "
        f"mode={mode} event_sha256={prepared.event['event_sha256']} "
        f"occurred_at={prepared.event['occurred_at']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
