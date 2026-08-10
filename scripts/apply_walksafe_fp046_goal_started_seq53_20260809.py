#!/usr/bin/env python3
"""Append FP-046 GOAL_STARTED seq53 only after its sealed nine-check gate."""

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
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import materialize_walksafe_fp046_goal_seq51_52_20260809 as materialize
from scripts import run_walksafe_fp046_goal_start_gate_20260809 as gate


CHECKPOINT_RELATIVE = gate.CHECKPOINT_RELATIVE
EVENT_SEQUENCE = 53
STARTED_CURRENT_FOCUS = (
    "FP046/GAP-055 GOAL_STARTED/IN_PROGRESS; repository-internal consent, "
    "withdrawal, deletion, storage, and retention implementation authorized"
)
STARTED_SCOPE = (
    "Graph v2.4 through FP046/GAP-055 initial start seq53 after the exact "
    "nine-check internal PASS gate; no product completion, admin, Web, device, "
    "external, formal, deployment, or release credit."
)
STARTED_HANDOFF_EPIC = "EPIC-03 / FP046/GAP-055 IN_PROGRESS"


class StartApplyError(RuntimeError):
    """Raised before any checkpoint write when seq53 cannot be proven safe."""


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
    projected_checkpoint: dict[str, Any]
    projected_checkpoint_bytes: bytes
    event: dict[str, Any]
    context: gate.GateContext
    evidence: GateEvidence


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


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
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise StartApplyError(f"{label} lacks a timezone")
    if second_precision and parsed.microsecond:
        raise StartApplyError(f"{label} must use second precision")
    return parsed


def _safe_path(root: Path, relative: Path, *, directory: bool) -> Path:
    if relative.is_absolute() or ".." in relative.parts:
        raise StartApplyError(f"unsafe repository path: {relative}")
    current = root.resolve(strict=True)
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            raise StartApplyError(f"symlink is not allowed: {relative}")
    try:
        resolved = current.resolve(strict=True)
        resolved.relative_to(root.resolve(strict=True))
    except (OSError, RuntimeError, ValueError) as exc:
        raise StartApplyError(f"repository path is missing or unsafe: {relative}") from exc
    expected_type = resolved.is_dir() if directory else resolved.is_file()
    if not expected_type:
        kind = "directory" if directory else "regular file"
        raise StartApplyError(f"repository path is not a {kind}: {relative}")
    return resolved


def _source_activation(
    source: dict[str, Any],
    activation_sha256: str,
) -> dict[str, Any]:
    matches = [
        event
        for event in source["goal_execution"]["transition_history"]
        if isinstance(event, dict)
        and event.get("event_type") == "PACKAGE_ACTIVATED"
        and event.get("event_sha256") == activation_sha256
    ]
    _require(len(matches) == 1, "source activation event differs")
    return matches[0]


def require_exact_source(source: dict[str, Any]) -> None:
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    _require(isinstance(state, dict), "source goal_execution is missing")
    _require(isinstance(history, list) and len(history) == 52, "source is not seq52")
    materialized = history[-2]
    ready = history[-1]
    _require(isinstance(materialized, dict), "seq51 is not an object")
    _require(isinstance(ready, dict), "seq52 is not an object")
    _require(
        materialized.get("sequence") == 51
        and materialized.get("event_id") == materialize.MATERIALIZED_EVENT_ID
        and materialized.get("event_sha256") == contract.event_sha256(materialized)
        and materialized.get("previous_event_sha256") == materialize.SOURCE_TAIL_SHA256,
        "seq51 materialization seal differs",
    )
    _require(
        ready.get("sequence") == 52
        and ready.get("event_id") == materialize.READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == materialize.GOAL_ID
        and ready.get("from_status") == "PLANNED"
        and ready.get("to_status") == "READY"
        and ready.get("event_sha256") == contract.event_sha256(ready)
        and ready.get("previous_event_sha256") == materialized.get("event_sha256"),
        "seq52 readiness seal differs",
    )
    if materialize.EXPECTED_MATERIALIZED_EVENT_SHA256:
        _require(
            materialized.get("event_sha256")
            == materialize.EXPECTED_MATERIALIZED_EVENT_SHA256,
            "seq51 trust anchor differs",
        )
    if materialize.EXPECTED_READY_EVENT_SHA256:
        _require(
            ready.get("event_sha256") == materialize.EXPECTED_READY_EVENT_SHA256,
            "seq52 trust anchor differs",
        )
    _require(
        state.get("transition_history_anchor_sha256") == ready.get("event_sha256"),
        "source history anchor differs",
    )
    statuses = state.get("status_by_goal")
    _require(
        isinstance(statuses, dict)
        and statuses.get(materialize.GOAL_ID) == "READY"
        and statuses.get(materialize.PREDECESSOR_GOAL_ID) == "COMPLETE_AT_TARGET"
        and not any(value == "IN_PROGRESS" for value in statuses.values()),
        "FP046 is not the READY start target",
    )
    _require(
        state.get("focus_goal_id") == materialize.GOAL_ID
        and state.get("focus_goal_path") == materialize.GOAL_PATH.as_posix()
        and state.get("focus_work_item_id") == materialize.WORK_ITEM_ID
        and state.get("focus_source") == "IMPLEMENTATION_BACKLOG"
        and state.get("ready_frontier_goal_ids") == materialize.READY_FRONTIER,
        "seq52 FP046 focus projection differs",
    )
    _require(
        ready.get("implementation_start_gate_contract_binding")
        == materialize.start_gate_contract_binding(),
        "seq52 start-gate contract binding differs",
    )


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
    errors.extend(contract.validate_seq39_canonical_binding_authorization_request(root, checkpoint))
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
    return errors


def _derive_event_time(source: dict[str, Any], generated_at: datetime) -> datetime:
    tail = _parse_timestamp(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        label="seq52 occurred_at",
        second_precision=True,
    )
    _require(generated_at.microsecond == 0, "receipt generated_at must use second precision")
    return max(tail + timedelta(seconds=1), generated_at + timedelta(seconds=1))


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
    receipt_path = _safe_path(root, receipt_relative, directory=False)
    _require(stat.S_IMODE(event_dir.stat().st_mode) == 0o700, "gate event directory mode differs")
    receipt_metadata = receipt_path.stat()
    _require(
        stat.S_IMODE(receipt_metadata.st_mode) == 0o600
        and receipt_metadata.st_nlink == 1,
        "gate receipt authority differs",
    )
    expected_names = {
        gate.RECEIPT_NAME,
        *(
            f"{index:02d}-{check_id}.log"
            for index, (check_id, _) in enumerate(context.checks, start=1)
        ),
    }
    _require({entry.name for entry in event_dir.iterdir()} == expected_names, "gate event directory file set differs")

    receipt_bytes = receipt_path.read_bytes()
    try:
        receipt = json.loads(receipt_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise StartApplyError("PASS receipt is not valid JSON") from exc
    _require(isinstance(receipt, dict), "PASS receipt root is not an object")
    _require(set(receipt) == gate.RECEIPT_FIELDS, "PASS receipt field set differs")
    expected_receipt_values = {
        "schema_version": "1.1",
        "document_id": gate._document_id(event_id),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": event_id,
        "target_goal_id": materialize.GOAL_ID,
        "target_goal_content_sha256": context.target_goal_sha256,
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "source_activation_event_sha256": context.source_activation_event_sha256,
        "source_checkpoint_sha256": sha256_bytes(source_checkpoint_bytes),
        "source_ready_event_sha256": source["goal_execution"]["transition_history"][-1]["event_sha256"],
        "check_command_contract_version": gate.CONTRACT_VERSION,
        "check_command_contract_sha256": gate.CONTRACT_CANONICAL_SHA256,
        "implementation_start_gate_contract_binding": materialize.start_gate_contract_binding(),
        "runtime_bindings": [
            {"path": path, "file_sha256": digest}
            for path, digest in context.runtime_bindings
        ],
    }
    for field, expected in expected_receipt_values.items():
        _require(receipt.get(field) == expected, f"PASS receipt {field} differs")

    window = receipt.get("execution_window")
    _require(
        isinstance(window, dict) and set(window) == {"started_at", "ended_at"},
        "PASS receipt execution window differs",
    )
    started_at = _parse_timestamp(window.get("started_at"), label="gate started_at", second_precision=True)
    ended_at = _parse_timestamp(window.get("ended_at"), label="gate ended_at", second_precision=True)
    generated_at = _parse_timestamp(receipt.get("generated_at"), label="gate generated_at", second_precision=True)
    _require(started_at <= ended_at <= generated_at, "gate chronology differs")
    activation = _source_activation(source, context.source_activation_event_sha256)
    activation_at = _parse_timestamp(activation.get("occurred_at"), label="activation occurred_at", second_precision=True)
    _require(activation_at <= started_at, "gate precedes package activation")

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
        output_path = _safe_path(root, output_relative, directory=False)
        output_metadata = output_path.stat()
        _require(
            stat.S_IMODE(output_metadata.st_mode) == 0o600
            and output_metadata.st_nlink == 1,
            f"gate check {index} log authority differs",
        )
        output_bytes = output_path.read_bytes()
        _require(output_bytes, f"gate check {index} output is empty")
        output_sha256 = sha256_bytes(output_bytes)
        _require(run.get("output_sha256") == output_sha256, f"gate check {index} output SHA-256 differs")
        executed_at = _parse_timestamp(
            run.get("executed_at"),
            label=f"gate check {index} executed_at",
            second_precision=False,
        )
        _require(started_at <= executed_at <= ended_at, f"gate check {index} is outside the execution window")
        _require(
            previous_executed_at is None or executed_at > previous_executed_at,
            f"gate check {index} time is not strictly increasing",
        )
        previous_executed_at = executed_at
        if check_id == "REPOSITORY_STATE":
            try:
                repository_payload = json.loads(output_bytes)
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise StartApplyError("REPOSITORY_STATE log is not valid JSON") from exc
            _require(isinstance(repository_payload, dict), "REPOSITORY_STATE root is not an object")
            _require(
                output_bytes == contract.canonical_json_bytes(repository_payload) + b"\n",
                "REPOSITORY_STATE log is not canonical CLI output",
            )
            repository_log_sha256 = output_sha256

    _require(repository_payload is not None, "REPOSITORY_STATE evidence is missing")
    expected_snapshot = gate.repository_snapshot_from_payload(
        repository_payload,
        event_id=event_id,
        output_sha256=repository_log_sha256,
    )
    _require(receipt.get("repository_snapshot") == expected_snapshot, "PASS receipt repository snapshot differs")
    current_payload = capture_repository_state(root, root / CHECKPOINT_RELATIVE, event_id)
    _require(current_payload == repository_payload, "current repository snapshot differs from the PASS gate")
    event_time = _derive_event_time(source, generated_at)
    _require(
        generated_at < event_time and event_time - generated_at <= timedelta(hours=1),
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


def project_seq53(
    source: dict[str, Any],
    evidence: GateEvidence,
    *,
    event_id: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    ready = state["transition_history"][-1]
    occurred_at = evidence.event_occurred_at
    event: dict[str, Any] = {
        "sequence": EVENT_SEQUENCE,
        "event_id": event_id,
        "event_type": "GOAL_STARTED",
        "occurred_on": datetime.fromisoformat(occurred_at).date().isoformat(),
        "occurred_at": occurred_at,
        "previous_focus_goal_id": materialize.GOAL_ID,
        "previous_focus_content_sha256": materialize.GOAL_SHA256,
        "focus_goal_id": materialize.GOAL_ID,
        "focus_goal_content_sha256": materialize.GOAL_SHA256,
        "subject_goal_id": materialize.GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "status_changes": {materialize.GOAL_ID: "IN_PROGRESS"},
        "runtime_after": copy.deepcopy(ready["runtime_after"]),
        "repository_snapshot_before": copy.deepcopy(evidence.receipt["repository_snapshot"]),
        "implementation_start_gate_binding": copy.deepcopy(evidence.receipt_binding),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            record["resolution_id"]
            for record in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [],
        "previous_event_sha256": ready["event_sha256"],
    }
    event["event_sha256"] = contract.event_sha256(event)
    _require(set(event) == contract.V24_FIRST_START_EVENT_FIELDS, "seq53 GOAL_STARTED field set differs")
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    state["status_by_goal"][materialize.GOAL_ID] = "IN_PROGRESS"
    checkpoint["current_work"]["status"] = "IN_PROGRESS"
    checkpoint["current_work"]["current_focus"] = STARTED_CURRENT_FOCUS
    checkpoint["working_tree_snapshot"]["scope"] = STARTED_SCOPE
    checkpoint["session_handoff"]["current_epic"] = STARTED_HANDOFF_EPIC
    return checkpoint, event


def prepare_projection(
    root: Path,
    *,
    event_id: str,
    live_validator: Callable[[Path], list[str]] = validate_live_source,
    projected_validator: Callable[[Path, dict[str, Any]], list[str]] = validate_projected_checkpoint,
    context_loader: Callable[[Path], gate.GateContext] = gate.load_gate_context,
    capture_repository_state: Callable[[Path, Path, str], dict[str, Any]] = contract.capture_gate_repository_state,
) -> PreparedProjection:
    root = root.resolve(strict=True)
    gate._document_id(event_id)
    checkpoint_path = _safe_path(root, CHECKPOINT_RELATIVE, directory=False)
    source_bytes = checkpoint_path.read_bytes()
    try:
        source = json.loads(source_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise StartApplyError("source checkpoint is not valid JSON") from exc
    _require(isinstance(source, dict), "source checkpoint root is not an object")
    require_exact_source(source)
    source_errors = live_validator(root)
    _require(not source_errors, "live seq52 validation failed: " + "; ".join(source_errors))
    context = context_loader(root)
    _require(context.target_goal_sha256 == materialize.GOAL_SHA256, "gate context FP046 Goal SHA-256 differs")
    _require(checkpoint_path.read_bytes() == source_bytes, "source checkpoint changed during preflight")
    evidence = validate_gate_evidence(
        root,
        source,
        source_bytes,
        context,
        event_id=event_id,
        capture_repository_state=capture_repository_state,
    )
    _require(checkpoint_path.read_bytes() == source_bytes, "source checkpoint changed during gate evidence validation")
    projected, event = project_seq53(source, evidence, event_id=event_id)
    projected_errors = projected_validator(root, projected)
    _require(not projected_errors, "projected seq53 validation failed: " + "; ".join(projected_errors))
    _require(checkpoint_path.read_bytes() == source_bytes, "source checkpoint changed during projected validation")
    return PreparedProjection(
        root=root,
        checkpoint_path=checkpoint_path,
        event_id=event_id,
        source_checkpoint_bytes=source_bytes,
        projected_checkpoint=projected,
        projected_checkpoint_bytes=json_bytes(projected),
        event=event,
        context=context,
        evidence=evidence,
    )


def write_projection(
    prepared: PreparedProjection,
    *,
    atomic_writer: Callable[..., None] = atomic.atomic_write,
) -> None:
    def full_commit_guard() -> None:
        _require(
            prepared.checkpoint_path.read_bytes() == prepared.source_checkpoint_bytes,
            "source checkpoint changed before seq53 publication",
        )
        source = json.loads(prepared.source_checkpoint_bytes)
        context = gate.load_gate_context(prepared.root)
        evidence = validate_gate_evidence(
            prepared.root,
            source,
            prepared.source_checkpoint_bytes,
            context,
            event_id=prepared.event_id,
            capture_repository_state=contract.capture_gate_repository_state,
        )
        _require(context == prepared.context, "gate context changed before write")
        _require(evidence == prepared.evidence, "gate evidence changed before write")

    def transaction_guard() -> None:
        checkpoint_bytes = prepared.checkpoint_path.read_bytes()
        _require(
            checkpoint_bytes
            in {
                prepared.source_checkpoint_bytes,
                prepared.projected_checkpoint_bytes,
            },
            "checkpoint changed at the seq53 transaction boundary",
        )
        source = json.loads(prepared.source_checkpoint_bytes)
        snapshot = source["working_tree_snapshot"]
        paths = snapshot["managed_changed_paths"]
        path_sha256, content_sha256 = contract.working_snapshot_hashes(
            prepared.root,
            paths,
        )
        _require(
            path_sha256 == snapshot["path_set_sha256"]
            and content_sha256 == snapshot["content_set_sha256"],
            "controlled source changed at the seq53 transaction boundary",
        )
        checks, _ = gate._load_gate_contract(prepared.root)
        _require(
            checks == prepared.context.checks
            and contract.sha256_file(prepared.root / gate.GOAL_RELATIVE)
            == prepared.context.target_goal_sha256
            and contract.sha256_file(prepared.root / gate.MANIFEST_RELATIVE)
            == gate.MANIFEST_SHA256,
            "static gate context changed at the seq53 transaction boundary",
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
            "runtime binding changed at the seq53 transaction boundary",
        )
        evidence = validate_gate_evidence(
            prepared.root,
            source,
            prepared.source_checkpoint_bytes,
            prepared.context,
            event_id=prepared.event_id,
            capture_repository_state=(
                lambda _root, _checkpoint, _event_id: (
                    prepared.evidence.repository_payload
                )
            ),
        )
        _require(
            evidence == prepared.evidence,
            "gate evidence changed at the seq53 transaction boundary",
        )

    full_commit_guard()
    transaction_guard()
    atomic_writer(
        prepared.checkpoint_path,
        prepared.projected_checkpoint_bytes,
        expected_source=prepared.source_checkpoint_bytes,
        commit_guard=transaction_guard,
    )
    path = prepared.checkpoint_path
    metadata = path.lstat()
    _require(
        stat.S_ISREG(metadata.st_mode)
        and stat.S_IMODE(metadata.st_mode) == 0o600
        and metadata.st_nlink == 1
        and path.read_bytes() == prepared.projected_checkpoint_bytes,
        "published seq53 checkpoint authority differs",
    )
    transaction_guard()


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
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
        gate.GateError,
        atomic.CompletionApplyError,
        atomic.CompletionPostCommitError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exc:
        print(f"FP-046 GOAL_STARTED seq53: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "FP-046 GOAL_STARTED seq53: PASS "
        f"mode={mode} event_sha256={prepared.event['event_sha256']} "
        f"occurred_at={prepared.event['occurred_at']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
