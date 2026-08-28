#!/usr/bin/env python3
"""Project FP-048 R002 GOAL_STARTED seq98 after the pending R009 handshake."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import stat
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_r002_goal_started_seq97_20260826 as frozen_start
from scripts import check_walksafe_project_continuation_v2_4 as continuation


CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
SOURCE_SEQUENCE = 97
EVENT_SEQUENCE = 98
SOURCE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-CONTRACT-CORRECTED-"
    "FP048-R002-20260826-005"
)
EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-004"
STARTED_EVENT_ID = EVENT_ID
TARGET_GOAL_ID = frozen_start.gate.TARGET_GOAL_ID
TARGET_GOAL_SHA256 = frozen_start.TARGET_GOAL_SHA256
MANIFEST_SHA256 = frozen_start.gate.MANIFEST_SHA256
WORK_ITEM_ID = frozen_start.correction.WORK_ITEM_ID
EVENT_FIELDS = frozenset(continuation.V24_FIRST_START_EVENT_FIELDS)

SCRIPT_REL = Path("scripts/apply_walksafe_fp048_r002_goal_started_seq98_20260826.py")
TEST_REL = Path("tests/test_apply_walksafe_fp048_r002_goal_started_seq98_20260826.py")
CORRECTION_MODULE = (
    "scripts.apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_"
    "correction_seq97_20260826"
)
GATE_MODULE = "scripts.run_walksafe_fp048_r002_goal_start_gate_r009_20260826"
GATE_DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-"
    "FP048-R002-20260826-004"
)
GATE_RECEIPT_REL = (
    Path("docs/control/execution/goal-gates") / EVENT_ID
    / "implementation-start-gate-receipt.json"
)
AUTHORITY_PATHS = (
    Path("scripts/apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_correction_seq97_20260826.py"),
    Path("tests/test_apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_correction_seq97_20260826.py"),
    Path("scripts/run_walksafe_fp048_r002_goal_start_gate_r009_20260826.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
)
PENDING_AUTHORITY_PINS: Mapping[Path, tuple[str, int] | None] = {
    AUTHORITY_PATHS[0]: (
        "d9ade6918eac71767110125816719361591ef98ec784f70e984760a8415c2ee7",
        73_776,
    ),
    AUTHORITY_PATHS[1]: (
        "0e894b50f928fe4f864f59c50d0d3ed8f9a59e1a9bf59591f9ca93da8b74b704",
        44_313,
    ),
    AUTHORITY_PATHS[2]: (
        "f5ed6e22507e3e08413dbc089accd73e791bda02d20a88ff8b50c9914c1c1e24",
        33_639,
    ),
    AUTHORITY_PATHS[3]: (
        "c3d9f1fdeedd0b623d061efddc7a7f6e02f8a3a76d0aa72d18c00ee98cfbfba4",
        940_826,
    ),
    AUTHORITY_PATHS[4]: (
        "d9cfe408aab905e937047d04af2b5e1ebe24baa879cbcf42ac210939355447d1",
        662_878,
    ),
}

STARTED_CURRENT_FOCUS = (
    "FP-048 R002/GAP-057 GOAL_STARTED/IN_PROGRESS after exact seq97 "
    "snapshot-hygiene correction and private R009 PASS"
)
STARTED_NEXT_ACTION = (
    "FP-048 R002의 seven-state encryption rotation을 내부 범위에서 완료한다."
)
STARTED_SCOPE = (
    "Graph v2.4 through FP048 R002 GOAL_STARTED seq98; formal, device, "
    "external, deployment and release credit remain zero."
)
STARTED_HANDOFF = "EPIC-03 / FP-048 R002/GAP-057 IN_PROGRESS"
STARTED_VERIFICATION = (
    "R009_PASS_FP048_R002_IN_PROGRESS_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
)


class StartApplyError(RuntimeError):
    """The recovery authority does not authorize seq98."""


@dataclass(frozen=True)
class GateEvidence:
    receipt: Mapping[str, Any]
    receipt_bytes: bytes
    receipt_binding: Mapping[str, Any]
    event_occurred_at: str


@dataclass(frozen=True)
class PreparedProjection:
    root: Path
    source_bytes: bytes
    source: Mapping[str, Any]
    projected: Mapping[str, Any]
    projected_bytes: bytes
    event: Mapping[str, Any]
    evidence: GateEvidence
    authority_pins: Mapping[Path, tuple[str, int]]
    source_identity: Any
    retained_inputs: Mapping[Path, Any]
    managed_inputs: Mapping[Path, Any]
    git_visible_inputs: Mapping[Path, Any]
    git_status_raw: bytes
    git_head: str
    git_branch: str


def require(condition: bool, message: str) -> None:
    if not condition:
        raise StartApplyError(message)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def checkpoint_bytes(value: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")


def gate_receipt_bytes(value: Mapping[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def strict_json(raw: bytes, label: str) -> dict[str, Any]:
    def reject(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            require(key not in result, f"duplicate JSON key: {label}: {key}")
            result[key] = value
        return result

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=reject)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise StartApplyError(f"invalid JSON: {label}") from exc
    require(type(value) is dict, f"JSON root differs: {label}")
    return value


def strict_equal(left: Any, right: Any) -> bool:
    try:
        return continuation.canonical_json_bytes(left) == continuation.canonical_json_bytes(right)
    except (TypeError, ValueError):
        return False


def _parse_time(value: Any, *, label: str) -> datetime:
    require(isinstance(value, str), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise StartApplyError(f"{label} is not ISO-8601") from exc
    require(parsed.utcoffset() is not None, f"{label} lacks timezone")
    require(parsed.microsecond == 0, f"{label} must use second precision")
    return parsed


def _parse_gate_time(
    value: Any,
    *,
    label: str,
    second_precision: bool,
) -> datetime:
    require(isinstance(value, str), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise StartApplyError(f"{label} is not ISO-8601") from exc
    require(parsed.utcoffset() is not None, f"{label} lacks timezone")
    if second_precision:
        require(parsed.microsecond == 0, f"{label} must use second precision")
    return parsed


def _correction() -> Any:
    try:
        return importlib.import_module(CORRECTION_MODULE)
    except ImportError as exc:
        raise StartApplyError("seq97 correction authority is unavailable") from exc


def _gate() -> Any:
    try:
        return importlib.import_module(GATE_MODULE)
    except ImportError as exc:
        raise StartApplyError("R009 gate authority is unavailable") from exc


def _canonical_seq97_authority_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    correction = _correction()
    validator = getattr(correction, "require_snapshot_hygiene_corrected_checkpoint", None)
    canonical = getattr(correction, "canonical_seq97_checkpoint_bytes", None)
    require(callable(validator) and callable(canonical), "correction API handshake differs")
    try:
        validator(
            root,
            checkpoint,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        raw = canonical(root, checkpoint)
    except Exception as exc:
        raise StartApplyError(f"seq97 correction physical authority differs: {exc}") from exc
    require(type(raw) is bytes, "seq97 correction canonical bytes differ")
    return raw


def validate_authority_handshake(
    root: Path,
    pins: Mapping[Path, tuple[str, int] | None],
) -> dict[Path, tuple[str, int]]:
    require(set(pins) == set(AUTHORITY_PATHS), "authority handshake path set differs")
    frozen: dict[Path, tuple[str, int]] = {}
    pending: list[str] = []
    for relative in AUTHORITY_PATHS:
        pin = pins[relative]
        if pin is None:
            pending.append(relative.as_posix())
            continue
        require(
            type(pin) is tuple
            and len(pin) == 2
            and isinstance(pin[0], str)
            and re.fullmatch(r"[0-9a-f]{64}", pin[0]) is not None
            and type(pin[1]) is int
            and pin[1] > 0,
            f"authority pin differs: {relative}",
        )
        frozen[relative] = pin
    require(not pending, "authority handshake is PENDING: " + ", ".join(pending))
    for relative, pin in frozen.items():
        raw = (root / relative).read_bytes()
        require(
            (sha256_bytes(raw), len(raw)) == pin,
            f"authority file drifted: {relative}",
        )
    return frozen


def _require_zero_credit(checkpoint: Mapping[str, Any]) -> None:
    approved = checkpoint.get("approved_state")
    verification = checkpoint.get("verification_boundary")
    require(
        isinstance(approved, dict)
        and type(approved.get("formal_test_count")) is int
        and type(approved.get("formal_test_not_run_count")) is int
        and type(approved.get("remaining_gate_count")) is int
        and approved.get("formal_test_count")
        == approved.get("formal_test_not_run_count")
        == 279
        and approved.get("remaining_gate_count") == 5
        and approved.get("remaining_gates_waived") is False
        and approved.get("release_status") == "NOT_ELIGIBLE",
        "formal/release zero-credit boundary differs",
    )
    require(
        isinstance(verification, dict)
        and verification.get("actual_device_test_status") == "NOT_RUN"
        and verification.get("all_remaining_gate_status") == "NOT_RUN"
        and verification.get("formal_test_pass_claimed") is False
        and verification.get("implementation_conformance_claimed") is False
        and verification.get("release_eligible") is False,
        "verification zero-credit boundary differs",
    )


def _require_source_shape(source: Mapping[str, Any]) -> None:
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        source.get("schema_version") == "1.25.0"
        and isinstance(history, list)
        and len(history) == SOURCE_SEQUENCE,
        "source is not exact seq97",
    )
    tail = history[-1]
    statuses = state.get("status_by_goal")
    require(
        isinstance(tail, dict)
        and type(tail.get("sequence")) is int
        and tail.get("sequence") == SOURCE_SEQUENCE
        and tail.get("event_id") == SOURCE_EVENT_ID
        and tail.get("event_type") == "GOAL_START_GATE_CONTRACT_CORRECTED"
        and tail.get("subject_goal_id") == TARGET_GOAL_ID
        and tail.get("from_status") == tail.get("to_status") == "READY"
        and strict_equal(tail.get("status_changes"), {})
        and tail.get("event_sha256") == continuation.event_sha256(tail)
        and isinstance(statuses, dict)
        and statuses.get(TARGET_GOAL_ID) == "READY"
        and "IN_PROGRESS" not in statuses.values()
        and state.get("goal_status") == "READY"
        and state.get("focus_goal_id") == TARGET_GOAL_ID
        and state.get("pending_producer_completion_goal_id") in {None, ""},
        "seq97 snapshot-hygiene correction authority differs",
    )
    current = source.get("current_work")
    require(
        isinstance(current, dict)
        and current.get("status") == "READY"
        and current.get("release_completion_claimed") is False,
        "source current-work boundary differs",
    )
    _require_zero_credit(source)


def validate_gate_evidence(
    source: Mapping[str, Any], evidence: GateEvidence
) -> None:
    receipt = evidence.receipt
    binding = evidence.receipt_binding
    tail = source["goal_execution"]["transition_history"][-1]
    occurred_at = _parse_time(evidence.event_occurred_at, label="seq98 occurred_at")
    generated_at = _parse_time(receipt.get("generated_at"), label="R009 generated_at")
    require(
        isinstance(receipt, Mapping)
        and type(evidence.receipt_bytes) is bytes
        and evidence.receipt_bytes == gate_receipt_bytes(receipt)
        and receipt.get("status") == "PASS"
        and receipt.get("document_id") == GATE_DOCUMENT_ID
        and receipt.get("target_transition_event_id") == EVENT_ID
        and receipt.get("target_goal_id") == TARGET_GOAL_ID
        and receipt.get("source_activation_event_sha256") == tail["event_sha256"]
        and isinstance(receipt.get("repository_snapshot"), Mapping),
        "private R009 PASS receipt differs",
    )
    require(
        isinstance(binding, Mapping)
        and set(binding) == {"document_id", "path", "file_sha256"}
        and binding.get("document_id") == GATE_DOCUMENT_ID
        and binding.get("path") == GATE_RECEIPT_REL.as_posix()
        and binding.get("file_sha256") == sha256_bytes(evidence.receipt_bytes),
        "private R009 receipt binding differs",
    )
    source_at = _parse_time(tail.get("occurred_at"), label="seq97 occurred_at")
    require(
        occurred_at == max(source_at, generated_at) + timedelta(seconds=1),
        "seq98 chronology differs",
    )


def _published_r009_gate_evidence(
    root: Path,
    source: Mapping[str, Any],
    source_bytes: bytes,
    *,
    event_occurred_at: str | None,
) -> GateEvidence:
    gate = _gate()
    try:
        authority = gate.bind_published_contract_corrected_source(
            root, copy.deepcopy(dict(source))
        )
    except Exception as exc:
        raise StartApplyError(f"R009 source authority differs: {exc}") from exc
    event_rel = gate.GATE_ROOT_RELATIVE / EVENT_ID
    event_path = root / event_rel
    try:
        event_metadata = event_path.lstat()
    except OSError as exc:
        raise StartApplyError("R009 evidence namespace is missing") from exc
    require(
        stat.S_ISDIR(event_metadata.st_mode)
        and not stat.S_ISLNK(event_metadata.st_mode)
        and stat.S_IMODE(event_metadata.st_mode) == 0o700
        and event_metadata.st_uid == os.geteuid(),
        "R009 evidence directory authority differs",
    )
    receipt_read = frozen_start.seq90._stable_read(root, GATE_RECEIPT_REL)
    require(
        stat.S_ISREG(receipt_read.identity.mode)
        and not stat.S_ISLNK(receipt_read.identity.mode)
        and stat.S_IMODE(receipt_read.identity.mode) == 0o600
        and receipt_read.identity.uid == os.geteuid()
        and receipt_read.identity.links == 1,
        "R009 receipt file authority differs",
    )
    receipt = strict_json(receipt_read.raw, "R009 receipt")
    window = receipt.get("execution_window")
    runs = receipt.get("check_runs")
    require(
        set(receipt) == set(gate._impl.RECEIPT_FIELDS)
        and isinstance(window, dict)
        and set(window) == {"started_at", "ended_at"}
        and isinstance(runs, list)
        and len(runs) == len(gate.EXPECTED_CHECK_IDS),
        "R009 receipt schema differs",
    )
    started_at = _parse_gate_time(
        window.get("started_at"), label="R009 started_at", second_precision=True
    )
    ended_at = _parse_gate_time(
        window.get("ended_at"), label="R009 ended_at", second_precision=True
    )
    generated_at = _parse_gate_time(
        receipt.get("generated_at"),
        label="R009 generated_at",
        second_precision=True,
    )
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1].get("occurred_at"),
        label="seq97 occurred_at",
    )
    require(
        source_at <= started_at <= ended_at <= generated_at,
        "R009 receipt chronology differs",
    )
    expected_runs: list[dict[str, Any]] = []
    expected_names = {gate.RECEIPT_NAME}
    repository_snapshot: Mapping[str, Any] | None = None
    previous_executed_at: datetime | None = None
    for index, (check_id, run) in enumerate(
        zip(gate.EXPECTED_CHECK_IDS, runs, strict=True), start=1
    ):
        require(
            isinstance(run, dict)
            and set(run)
            == {
                "check_id", "command", "output_path", "output_sha256",
                "exit_code", "executed_at",
            },
            f"R009 check {index} schema differs",
        )
        output_rel = event_rel / f"{index:02d}-{check_id}.log"
        output_read = frozen_start.seq90._stable_read(root, output_rel)
        require(
            stat.S_ISREG(output_read.identity.mode)
            and not stat.S_ISLNK(output_read.identity.mode)
            and stat.S_IMODE(output_read.identity.mode) == 0o600
            and output_read.identity.uid == os.geteuid()
            and output_read.identity.links == 1
            and 0 < len(output_read.raw) <= gate._impl.LOG_MAX_BYTES,
            f"R009 check {index} log authority differs",
        )
        executed_at = _parse_gate_time(
            run.get("executed_at"),
            label=f"R009 check {index} executed_at",
            second_precision=False,
        )
        output_sha256 = sha256_bytes(output_read.raw)
        require(
            run.get("check_id") == check_id
            and run.get("command") == gate.CONTRACT_COMMANDS[check_id]
            and run.get("output_path") == output_rel.as_posix()
            and run.get("output_sha256") == output_sha256
            and type(run.get("exit_code")) is int
            and run.get("exit_code") == 0
            and started_at <= executed_at <= ended_at
            and (
                previous_executed_at is None
                or previous_executed_at < executed_at
            ),
            f"R009 check {index} authority differs",
        )
        previous_executed_at = executed_at
        expected_names.add(output_rel.name)
        expected_runs.append(
            {
                "check_id": check_id,
                "command": gate.CONTRACT_COMMANDS[check_id],
                "output_path": output_rel.as_posix(),
                "output_sha256": output_sha256,
                "exit_code": 0,
                "executed_at": run["executed_at"],
            }
        )
        if check_id == "REPOSITORY_STATE":
            payload = strict_json(output_read.raw, "R009 REPOSITORY_STATE")
            require(
                output_read.raw == gate._impl.canonical_json_bytes(payload) + b"\n",
                "R009 REPOSITORY_STATE bytes differ",
            )
            repository_snapshot = gate._impl.repository_snapshot_from_payload(
                payload,
                event_id=EVENT_ID,
                output_sha256=output_sha256,
            )
    require(
        {entry.name for entry in event_path.iterdir()} == expected_names
        and repository_snapshot is not None,
        "R009 evidence namespace membership differs",
    )
    expected_receipt = {
        "schema_version": "1.1",
        "document_id": GATE_DOCUMENT_ID,
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": gate.GATE_PURPOSE,
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": EVENT_ID,
        "target_goal_id": TARGET_GOAL_ID,
        "target_goal_content_sha256": authority.goal_sha256,
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "source_activation_event_sha256": authority.correction_event_sha256,
        "source_checkpoint_sha256": sha256_bytes(source_bytes),
        "source_ready_event_sha256": authority.ready_event_sha256,
        "check_command_contract_version": gate.CONTRACT_VERSION,
        "check_command_contract_sha256": gate.CONTRACT_CANONICAL_SHA256,
        "implementation_start_gate_contract_binding": gate.expected_r009_binding(),
        "runtime_bindings": [],
        "execution_window": {
            "started_at": window["started_at"],
            "ended_at": window["ended_at"],
        },
        "check_runs": expected_runs,
        "repository_snapshot": copy.deepcopy(repository_snapshot),
        "generated_at": receipt["generated_at"],
    }
    require(
        receipt_read.raw == gate_receipt_bytes(expected_receipt),
        "R009 receipt bytes differ from exact producer order",
    )
    evidence = GateEvidence(
        receipt=receipt,
        receipt_bytes=receipt_read.raw,
        receipt_binding={
            "document_id": GATE_DOCUMENT_ID,
            "path": GATE_RECEIPT_REL.as_posix(),
            "file_sha256": sha256_bytes(receipt_read.raw),
        },
        event_occurred_at=(
            event_occurred_at
            if event_occurred_at is not None
            else (max(source_at, generated_at) + timedelta(seconds=1)).isoformat()
        ),
    )
    validate_gate_evidence(source, evidence)
    return evidence


def require_published_r009_gate_for_seq97(
    root: Path,
    source: Mapping[str, Any],
    source_bytes: bytes,
    *,
    gate_loader: Callable[[Path, Mapping[str, Any], bytes], GateEvidence] | None = None,
    event_occurred_at: str | None = None,
) -> GateEvidence:
    """Load and validate the exact private R009 PASS evidence."""

    if gate_loader is None:
        evidence = _published_r009_gate_evidence(
            root,
            source,
            source_bytes,
            event_occurred_at=event_occurred_at,
        )
    else:
        evidence = gate_loader(root, source, source_bytes)
    require(isinstance(evidence, GateEvidence), "R009 gate loader result differs")
    validate_gate_evidence(source, evidence)
    return evidence


def project_seq98(
    root: Path,
    source: Mapping[str, Any],
    evidence: GateEvidence,
    *,
    managed_paths: Sequence[str],
    snapshot_hashes: tuple[str, str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    _require_source_shape(source)
    validate_gate_evidence(source, evidence)
    require(
        len(snapshot_hashes) == 2
        and all(re.fullmatch(r"[0-9a-f]{64}", value) for value in snapshot_hashes),
        "snapshot hashes differ",
    )
    checkpoint = copy.deepcopy(dict(source))
    state = checkpoint["goal_execution"]
    control = state["transition_history"][-1]
    event: dict[str, Any] = {
        "sequence": EVENT_SEQUENCE,
        "event_id": EVENT_ID,
        "event_type": "GOAL_STARTED",
        "occurred_on": datetime.fromisoformat(evidence.event_occurred_at).date().isoformat(),
        "occurred_at": evidence.event_occurred_at,
        "previous_focus_goal_id": TARGET_GOAL_ID,
        "previous_focus_content_sha256": TARGET_GOAL_SHA256,
        "focus_goal_id": TARGET_GOAL_ID,
        "focus_goal_content_sha256": TARGET_GOAL_SHA256,
        "subject_goal_id": TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {TARGET_GOAL_ID: "IN_PROGRESS"},
        "runtime_after": copy.deepcopy(control["runtime_after"]),
        "repository_snapshot_before": copy.deepcopy(
            evidence.receipt["repository_snapshot"]
        ),
        "implementation_start_gate_binding": copy.deepcopy(dict(evidence.receipt_binding)),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [],
        "previous_event_sha256": control["event_sha256"],
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq98 GOAL_STARTED field set differs")
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = event["occurred_at"]
    state["status_by_goal"][TARGET_GOAL_ID] = "IN_PROGRESS"
    state["goal_status"] = "IN_PROGRESS"

    current = checkpoint["current_work"]
    current["status"] = "IN_PROGRESS"
    current["current_focus"] = STARTED_CURRENT_FOCUS
    current["next_action"] = STARTED_NEXT_ACTION
    current["release_completion_claimed"] = False

    paths = sorted(set(managed_paths) | {SCRIPT_REL.as_posix(), TEST_REL.as_posix()})
    require(paths and all(isinstance(path, str) and path for path in paths), "managed paths differ")
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot.update(
        {
            "scope": STARTED_SCOPE,
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": snapshot_hashes[0],
            "content_set_sha256": snapshot_hashes[1],
        }
    )
    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(paths)
    handoff["current_epic"] = STARTED_HANDOFF
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = STARTED_VERIFICATION
    handoff["next_single_action"] = STARTED_NEXT_ACTION
    mirror = handoff["source_commit_or_snapshot"]
    mirror["file_count"] = len(paths)
    mirror["path_set_sha256"] = snapshot_hashes[0]
    mirror["content_set_sha256"] = snapshot_hashes[1]
    _require_zero_credit(checkpoint)
    return checkpoint, event


def validate_projection(
    root: Path,
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    evidence: GateEvidence,
) -> None:
    snapshot = projected.get("working_tree_snapshot")
    require(isinstance(snapshot, dict), "projected snapshot differs")
    expected, _event = project_seq98(
        root,
        source,
        evidence,
        managed_paths=snapshot.get("managed_changed_paths", []),
        snapshot_hashes=(snapshot.get("path_set_sha256"), snapshot.get("content_set_sha256")),
    )
    require(strict_equal(projected, expected), "seq98 projection differs")


def _restored_seq97_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == EVENT_SEQUENCE,
        "seq97 inverse requires exact seq98",
    )
    event = history[-1]
    control = history[-2]
    require(
        isinstance(event, dict)
        and isinstance(control, dict)
        and set(event) == EVENT_FIELDS
        and type(event.get("sequence")) is int
        and event.get("sequence") == EVENT_SEQUENCE
        and event.get("event_id") == EVENT_ID
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("previous_event_sha256") == control.get("event_sha256")
        and event.get("event_sha256") == continuation.event_sha256(event),
        "seq98 inverse event differs",
    )
    reanchor = control.get("repository_context_reanchor")
    after = reanchor.get("after") if isinstance(reanchor, dict) else None
    require(
        isinstance(after, dict)
        and type(after.get("managed_changed_path_count")) is int
        and after["managed_changed_path_count"] > 0
        and isinstance(after.get("path_set_sha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", after["path_set_sha256"])
        and isinstance(after.get("content_set_sha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", after["content_set_sha256"]),
        "seq97 correction snapshot seal differs",
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    projected_paths = (
        snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    )
    require(
        isinstance(projected_paths, list)
        and projected_paths == sorted(set(projected_paths))
        and all(isinstance(path, str) and path for path in projected_paths),
        "seq98 managed path inventory differs",
    )
    additions = (SCRIPT_REL.as_posix(), TEST_REL.as_posix())
    candidates: list[list[str]] = []
    for mask in range(1 << len(additions)):
        removed = {
            path for index, path in enumerate(additions) if mask & (1 << index)
        }
        candidate = [path for path in projected_paths if path not in removed]
        path_hash = hashlib.sha256(
            ("\n".join(candidate) + "\n").encode("utf-8")
        ).hexdigest()
        if (
            len(candidate) == after["managed_changed_path_count"]
            and path_hash == after["path_set_sha256"]
            and candidate not in candidates
        ):
            candidates.append(candidate)
    require(len(candidates) == 1, "seq97 managed path preimage is ambiguous")
    paths = candidates[0]
    correction = _correction()
    restored = copy.deepcopy(dict(checkpoint))
    restored_state = restored["goal_execution"]
    restored_state["transition_history"] = copy.deepcopy(history[:SOURCE_SEQUENCE])
    restored_state["transition_history_anchor_sha256"] = control["event_sha256"]
    restored_state["validation_cutoff_at"] = control["occurred_at"]
    restored_state["status_by_goal"][TARGET_GOAL_ID] = "READY"
    restored_state["goal_status"] = "READY"
    current = restored["current_work"]
    current.update(
        {
            "status": "READY",
            "current_focus": correction.CURRENT_FOCUS,
            "next_action": correction.NEXT_ACTION,
            "release_completion_claimed": False,
        }
    )
    restored_snapshot = restored["working_tree_snapshot"]
    restored_snapshot.update(
        {
            "scope": correction.SCOPE,
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": after["path_set_sha256"],
            "content_set_sha256": after["content_set_sha256"],
        }
    )
    handoff = restored["session_handoff"]
    handoff.update(
        {
            "changed_files": copy.deepcopy(paths),
            "current_epic": "EPIC-03 / FP-048 R002 snapshot hygiene recovery",
            "last_updated_by_work_item": correction.WORK_ITEM_ID,
            "last_verification_status": "SEQ97_R009_CORRECTION_REVIEWED_ZERO_CREDIT",
            "next_single_action": correction.NEXT_ACTION,
        }
    )
    mirror = handoff["source_commit_or_snapshot"]
    mirror.update(
        {
            "file_count": len(paths),
            "path_set_sha256": after["path_set_sha256"],
            "content_set_sha256": after["content_set_sha256"],
        }
    )
    return restored


def reconstructed_seq97_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    root = root.resolve(strict=True)
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(isinstance(history, list), "seq97 inverse history differs")
    if len(history) == SOURCE_SEQUENCE:
        raw = checkpoint_bytes(checkpoint)
        require(
            _canonical_seq97_authority_bytes(root, checkpoint) == raw,
            "seq97 canonical identity differs",
        )
        return raw
    restored = _restored_seq97_checkpoint(root, checkpoint)
    raw = checkpoint_bytes(restored)
    require(
        _canonical_seq97_authority_bytes(root, restored) == raw,
        "seq97 reconstructed checkpoint differs",
    )
    return raw


def require_started_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
    run_external_validators: bool = False,
) -> None:
    root = root.resolve(strict=True)
    source_raw = reconstructed_seq97_checkpoint_bytes(root, checkpoint)
    source = strict_json(source_raw, "reconstructed seq97 checkpoint")
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(
        isinstance(history, list) and len(history) == EVENT_SEQUENCE,
        "checkpoint is not exact seq98 GOAL_STARTED",
    )
    event = history[-1]
    require(isinstance(event, dict), "seq98 GOAL_STARTED event differs")
    evidence = require_published_r009_gate_for_seq97(
        root,
        source,
        source_raw,
        event_occurred_at=event.get("occurred_at"),
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    require(isinstance(snapshot, dict), "seq98 working snapshot differs")
    expected, expected_event = project_seq98(
        root,
        source,
        evidence,
        managed_paths=snapshot.get("managed_changed_paths", []),
        snapshot_hashes=(
            snapshot.get("path_set_sha256"),
            snapshot.get("content_set_sha256"),
        ),
    )
    require(
        checkpoint_bytes(checkpoint) == checkpoint_bytes(expected)
        and strict_equal(event, expected_event),
        "seq98 started checkpoint projection differs",
    )
    paths = snapshot.get("managed_changed_paths")
    if require_live_snapshot:
        require(
            continuation.working_snapshot_hashes(root, paths)
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "seq98 live working snapshot differs",
        )
    if run_external_validators:
        live_raw = (root / CHECKPOINT_REL).read_bytes()
        require(live_raw == checkpoint_bytes(checkpoint), "live seq98 checkpoint differs")
        errors = continuation.validate(root, CHECKPOINT_REL)
        goal_graph = importlib.import_module("scripts.check_walksafe_goal_graph_v2_4")
        errors.extend(
            f"goal graph: {error}"
            for error in goal_graph.validate(
                root,
                CHECKPOINT_REL,
                check_continuation=False,
            )
        )
        require(not errors, "seq98 external validators failed: " + "; ".join(errors))


def goal_start_gate_receipt_binding(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> dict[str, str]:
    """Return the detached R009 binding only after exact seq98 verification."""

    require_started_checkpoint(
        root,
        checkpoint,
        require_live_snapshot=False,
        run_external_validators=False,
    )
    event = checkpoint["goal_execution"]["transition_history"][-1]
    binding = event["implementation_start_gate_binding"]
    require(
        isinstance(binding, dict)
        and set(binding) == {"document_id", "path", "file_sha256"},
        "seq98 R009 receipt binding differs",
    )
    return copy.deepcopy(binding)


def _publication_retained_paths(root: Path, source: Mapping[str, Any]) -> tuple[Path, ...]:
    correction = _correction()
    reviewed = getattr(correction, "noncredit_reviewed_control_successor_bindings", None)
    require(callable(reviewed), "R002 reviewed-control API is missing")
    try:
        reviewed(
            root,
            source,
            require_live_snapshot=False,
        )
    except Exception as exc:
        raise StartApplyError(f"R002 reviewed-control authority differs: {exc}") from exc
    paths: list[Path] = [SCRIPT_REL, TEST_REL, *AUTHORITY_PATHS]
    for name in ("AUTHORIZATION_REL",):
        value = getattr(correction, name, None)
        if isinstance(value, Path):
            paths.append(value)
    for name in ("REVIEW_PATHS", "REVIEWED_CONTROL_PATHS"):
        values = getattr(correction, name, ())
        require(
            isinstance(values, (tuple, list))
            and all(isinstance(value, Path) for value in values),
            f"R002 reviewed-control path inventory differs: {name}",
        )
        paths.extend(values)
    gate = _gate()
    event_root = gate.GATE_ROOT_RELATIVE / EVENT_ID
    paths.append(GATE_RECEIPT_REL)
    paths.extend(
        event_root / f"{index:02d}-{check_id}.log"
        for index, check_id in enumerate(gate.EXPECTED_CHECK_IDS, start=1)
    )
    return tuple(dict.fromkeys(paths))


def _validate_projected_with_consumers(root: Path, projected: Mapping[str, Any]) -> None:
    try:
        frozen_start.seq90._validate_projected_with_consumers(root, projected)
    except Exception as exc:
        raise StartApplyError(f"seq98 projected consumer validation failed: {exc}") from exc


def _require_prepared_exact(prepared: PreparedProjection, *, phase: str) -> None:
    seq90 = frozen_start.seq90
    source_read = seq90.ReadResult(prepared.source_bytes, prepared.source_identity)
    try:
        seq90._require_preflight_cohort_unchanged(
            prepared.root,
            source=source_read,
            retained=prepared.retained_inputs,
            managed=prepared.managed_inputs,
            git_visible=prepared.git_visible_inputs,
            git_status_raw=prepared.git_status_raw,
            git_head=prepared.git_head,
            git_branch=prepared.git_branch,
            phase=phase,
        )
    except Exception as exc:
        raise StartApplyError(str(exc)) from exc
    validate_authority_handshake(prepared.root, prepared.authority_pins)
    require(
        prepared.source_bytes == checkpoint_bytes(prepared.source)
        and _canonical_seq97_authority_bytes(prepared.root, prepared.source)
        == prepared.source_bytes,
        "prepared seq97 source authority differs",
    )
    current_evidence = require_published_r009_gate_for_seq97(
        prepared.root,
        prepared.source,
        prepared.source_bytes,
        event_occurred_at=prepared.evidence.event_occurred_at,
    )
    require(
        current_evidence.receipt_bytes == prepared.evidence.receipt_bytes
        and strict_equal(current_evidence.receipt_binding, prepared.evidence.receipt_binding),
        "R009 evidence changed after preflight",
    )
    require(
        prepared.projected_bytes == checkpoint_bytes(prepared.projected),
        "prepared seq98 bytes differ",
    )
    validate_projection(
        prepared.root,
        prepared.source,
        prepared.projected,
        prepared.evidence,
    )
    snapshot = prepared.projected["working_tree_snapshot"]
    paths = sorted(path.as_posix() for path in prepared.managed_inputs)
    hashes = seq90._managed_input_snapshot_hashes(prepared.managed_inputs)
    require(
        paths == snapshot["managed_changed_paths"]
        and hashes == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
        "prepared seq98 managed snapshot differs",
    )


def prepare_projection(
    root: Path = ROOT,
    *,
    authority_pins: Mapping[Path, tuple[str, int] | None] = PENDING_AUTHORITY_PINS,
    gate_loader: Callable[[Path, Mapping[str, Any], bytes], GateEvidence] | None = None,
) -> PreparedProjection:
    root = root.resolve(strict=True)
    pins = validate_authority_handshake(root, authority_pins)
    seq90 = frozen_start.seq90
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    require(
        stat.S_IMODE(source_read.identity.mode) == 0o600,
        "seq97 checkpoint mode differs",
    )
    source_bytes = source_read.raw
    source = strict_json(source_bytes, "seq97 checkpoint")
    require(source_bytes == checkpoint_bytes(source), "seq97 checkpoint bytes differ")
    require(
        _canonical_seq97_authority_bytes(root, source) == source_bytes,
        "exact seq97 correction CAS differs",
    )
    evidence = require_published_r009_gate_for_seq97(
        root,
        source,
        source_bytes,
        gate_loader=gate_loader,
    )
    retained_paths = _publication_retained_paths(root, source)
    retained = {path: seq90._stable_read(root, path) for path in retained_paths}
    git_status_raw, git_visible_paths = seq90.capture_git_visible_paths(root)
    git_head, git_branch = seq90._capture_git_context(root)
    paths = sorted(
        set(source["working_tree_snapshot"]["managed_changed_paths"])
        | set(git_visible_paths)
        | {path.as_posix() for path in retained_paths}
    )
    managed = seq90._capture_managed_inputs(root, paths)
    git_visible = seq90._capture_managed_inputs(root, git_visible_paths)
    hashes = seq90._managed_input_snapshot_hashes(managed)
    projected, event = project_seq98(
        root,
        source,
        evidence,
        managed_paths=paths,
        snapshot_hashes=hashes,
    )
    validate_projection(root, source, projected, evidence)
    _validate_projected_with_consumers(root, projected)
    prepared = PreparedProjection(
        root=root,
        source_bytes=source_bytes,
        source=source,
        projected=projected,
        projected_bytes=checkpoint_bytes(projected),
        event=event,
        evidence=evidence,
        authority_pins=pins,
        source_identity=source_read.identity,
        retained_inputs=retained,
        managed_inputs=managed,
        git_visible_inputs=git_visible,
        git_status_raw=git_status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    _require_prepared_exact(prepared, phase="during seq98 preflight")
    return prepared


def write_projection(
    prepared: PreparedProjection,
    *,
    writer: Callable[..., None] = frozen_start.seq90.write_checkpoint,
) -> None:
    checkpoint = prepared.root / CHECKPOINT_REL
    current = frozen_start.seq90._stable_read(prepared.root, CHECKPOINT_REL)
    if current.raw == prepared.projected_bytes:
        require(
            stat.S_IMODE(current.identity.mode) == 0o600,
            "published seq98 checkpoint mode differs",
        )
        published = strict_json(current.raw, "published seq98 checkpoint")
        require_started_checkpoint(
            prepared.root,
            published,
            require_live_snapshot=True,
            run_external_validators=True,
        )
        terminal = frozen_start.seq90._stable_read(
            prepared.root, CHECKPOINT_REL
        )
        require(
            terminal.identity == current.identity
            and terminal.raw == current.raw,
            "published seq98 checkpoint changed during terminal validation",
        )
        return
    require(
        current.identity == prepared.source_identity
        and current.raw == prepared.source_bytes,
        "seq97 source changed before publication",
    )
    _require_prepared_exact(prepared, phase="before seq98 publication")
    transport = frozen_start.seq90.Prepared(
        root=prepared.root,
        source_raw=prepared.source_bytes,
        source_identity=prepared.source_identity,
        source=prepared.source,
        projected=prepared.projected,
        projected_raw=prepared.projected_bytes,
        event=prepared.event,
        retained_inputs=prepared.retained_inputs,
        managed_inputs=prepared.managed_inputs,
        git_visible_inputs=prepared.git_visible_inputs,
        git_status_raw=prepared.git_status_raw,
        git_head=prepared.git_head,
        git_branch=prepared.git_branch,
    )
    try:
        writer(
            transport,
            commit_guard=lambda: _require_prepared_exact(
                prepared, phase="at seq98 commit point"
            ),
        )
    except frozen_start.seq90.PostcommitUncertain:
        raise
    except BaseException as exc:
        observed = frozen_start.seq90._stable_read(prepared.root, CHECKPOINT_REL)
        if observed.raw == prepared.projected_bytes:
            raise frozen_start.seq90.PostcommitUncertain(
                "seq98 writer failed after publishing exact bytes"
            ) from exc
        raise
    published_read = frozen_start.seq90._stable_read(prepared.root, CHECKPOINT_REL)
    if published_read.raw != prepared.projected_bytes:
        raise frozen_start.seq90.PostcommitUncertain(
            "published seq98 checkpoint bytes are uncertain"
        )
    try:
        require(
            stat.S_IMODE(published_read.identity.mode) == 0o600,
            "published seq98 checkpoint mode differs",
        )
        published = strict_json(published_read.raw, "published seq98 checkpoint")
        require_started_checkpoint(
            prepared.root,
            published,
            require_live_snapshot=True,
            run_external_validators=True,
        )
        terminal = frozen_start.seq90._stable_read(prepared.root, CHECKPOINT_REL)
        require(
            terminal.identity == published_read.identity
            and terminal.raw == published_read.raw,
            "published seq98 checkpoint changed during terminal validation",
        )
    except BaseException as exc:
        raise frozen_start.seq90.PostcommitUncertain(
            "seq98 publication completed but terminal authority is uncertain"
        ) from exc


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        prepared = prepare_projection(args.root)
        mode = "PREFLIGHT"
        if args.write:
            write_projection(prepared)
            mode = "WRITE"
    except frozen_start.seq90.PostcommitUncertain as exc:
        print(f"FP048 R002 GOAL_STARTED seq98: UNCERTAIN: {exc}", file=sys.stderr)
        return 2
    except (StartApplyError, KeyError, OSError, TypeError, ValueError) as exc:
        print(f"FP048 R002 GOAL_STARTED seq98: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "FP048 R002 GOAL_STARTED seq98: PASS "
        f"mode={mode} event_sha256={prepared.event['event_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
