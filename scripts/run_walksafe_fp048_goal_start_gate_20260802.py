#!/usr/bin/env python3
"""Run the sealed v2.4 full gate for the initial FP-048 Goal start."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Any, Callable, Sequence
from zoneinfo import ZoneInfo


ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT_RELATIVE = Path(
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
MANIFEST_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "static-plan-manifest-v2.4.0.json"
)
GATE_ROOT_RELATIVE = Path("docs/control/execution/goal-gates")
TOOLCHAIN_LOCK_RELATIVE = Path(
    "configs/walksafe_node_toolchain_lock_20260715.json"
)

PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
TARGET_GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R001"
GATE_PURPOSE = "INITIAL_START"
CONTRACT_VERSION = "2026-07-25.4"
CONTRACT_SHA256 = (
    "8c7e16f13a66398e5ba067cba0df9ba00258018f630256f6abc5b881b0467b7c"
)
MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)
PINNED_NODE_BIN_DIR = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "node-v22.23.1-linux-x64/bin"
)
LOCKED_TEST_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
EXPECTED_CHECK_IDS = (
    "CONTINUATION",
    "GOAL_GRAPH",
    "BASELINE_MATERIALIZATION",
    "ANDROID_GATEWAY_BOUNDARY",
    "NODE_TOOLCHAIN_PRE",
    "GATEWAY_TYPECHECK",
    "GATEWAY_TEST",
    "GATEWAY_BUILD",
    "WEB_TEST",
    "WEB_LINT",
    "WEB_TYPECHECK",
    "WEB_BUILD",
    "NODE_TOOLCHAIN_POST",
    "ANDROID_UNIT_ASSEMBLE_LINT",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "FIELD_AND_RELEASE_PYTEST",
    "GOAL_CONTROL_PYTEST",
    "CONTROL_AND_TRACE_PYTEST",
    "REPOSITORY_STATE",
)
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-(\d{8})-(\d{3})$"
)
RECEIPT_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "gate_purpose",
    "status",
    "package_id",
    "target_transition_event_id",
    "target_goal_id",
    "target_goal_content_sha256",
    "static_plan_manifest_sha256",
    "source_activation_event_sha256",
    "check_command_contract_version",
    "check_command_contract_sha256",
    "toolchain_lock_binding",
    "execution_window",
    "check_runs",
    "repository_snapshot",
    "generated_at",
}


class GateError(RuntimeError):
    """Raised when the gate cannot safely produce a PASS receipt."""


class GateCheckFailed(GateError):
    def __init__(self, check_id: str, exit_code: int, output_path: str) -> None:
        super().__init__(
            f"{check_id} failed with exit code {exit_code}; see {output_path}"
        )
        self.exit_code = exit_code


@dataclass(frozen=True)
class GateContext:
    checks: tuple[tuple[str, str], ...]
    target_goal_sha256: str
    source_activation_event_sha256: str
    toolchain_lock_sha256: str


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def canonical_sha256(value: Any) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return sha256_bytes(encoded)


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise GateError(f"JSON root must be an object: {path}")
    return value


def repo_file(root: Path, relative: str | Path) -> Path:
    relative_path = Path(relative)
    if relative_path.is_absolute() or ".." in relative_path.parts:
        raise GateError(f"unsafe repository path: {relative_path}")
    root = root.resolve()
    candidate = (root / relative_path).resolve(strict=True)
    if not candidate.is_relative_to(root) or not candidate.is_file():
        raise GateError(f"repository file is missing or unsafe: {relative_path}")
    return candidate


def load_gate_context(root: Path) -> GateContext:
    root = root.resolve(strict=True)
    checkpoint = load_json(repo_file(root, CHECKPOINT_RELATIVE))
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        raise GateError("checkpoint goal_execution is missing")
    if (
        state.get("package_id") != PACKAGE_ID
        or state.get("package_status") != "ACTIVE"
        or state.get("activation_status") != "ACTIVE"
    ):
        raise GateError("v2.4 package is not ACTIVE")
    if state.get("focus_goal_id") != TARGET_GOAL_ID:
        raise GateError("FP-048 is not the checkpoint focus Goal")
    statuses = state.get("status_by_goal")
    if not isinstance(statuses, dict) or statuses.get(TARGET_GOAL_ID) != "READY":
        raise GateError("FP-048 must be READY before its initial-start gate")

    manifest_path_value = state.get("static_plan_manifest_path")
    if manifest_path_value != MANIFEST_RELATIVE.as_posix():
        raise GateError("checkpoint v2.4 manifest path differs")
    manifest_path = repo_file(root, MANIFEST_RELATIVE)
    manifest_sha256 = sha256_file(manifest_path)
    if (
        manifest_sha256 != MANIFEST_SHA256
        or state.get("static_plan_manifest_sha256") != MANIFEST_SHA256
    ):
        raise GateError("v2.4 manifest SHA-256 differs")
    manifest = load_json(manifest_path)
    transition = manifest.get("transition_contract")
    control = manifest.get("successor_control_contract")
    if not isinstance(transition, dict) or not isinstance(control, dict):
        raise GateError("v2.4 gate contract is missing")
    raw_checks = control.get("implementation_start_gate_checks")
    if not isinstance(raw_checks, list):
        raise GateError("v2.4 full-gate checks are missing")
    checks: list[tuple[str, str]] = []
    for item in raw_checks:
        if not isinstance(item, dict) or set(item) != {"check_id", "command"}:
            raise GateError("v2.4 full-gate check record differs")
        check_id = item.get("check_id")
        command = item.get("command")
        if not isinstance(check_id, str) or not isinstance(command, str):
            raise GateError("v2.4 full-gate check value differs")
        checks.append((check_id, command))
    if tuple(item[0] for item in checks) != EXPECTED_CHECK_IDS:
        raise GateError("v2.4 full-gate check order differs")
    contract_value = {
        "contract_version": CONTRACT_VERSION,
        "checks": [
            {"check_id": check_id, "command": command}
            for check_id, command in checks
        ],
    }
    if (
        transition.get("check_command_contract_version") != CONTRACT_VERSION
        or transition.get("implementation_start_gate_check_ids")
        != list(EXPECTED_CHECK_IDS)
        or transition.get("implementation_start_gate_check_contract_sha256")
        != CONTRACT_SHA256
        or canonical_sha256(contract_value) != CONTRACT_SHA256
    ):
        raise GateError("v2.4 full-gate command contract differs")

    inventory = state.get("dynamic_goal_inventory")
    item = inventory.get(TARGET_GOAL_ID) if isinstance(inventory, dict) else None
    if not isinstance(item, dict):
        raise GateError("FP-048 Goal has not been materialized")
    goal_path_value = item.get("path")
    if not isinstance(goal_path_value, str):
        raise GateError("FP-048 Goal path is missing")
    goal_sha256 = sha256_file(repo_file(root, goal_path_value))
    if item.get("sha256") != goal_sha256:
        raise GateError("FP-048 Goal SHA-256 differs from the checkpoint")

    history = state.get("transition_history")
    activations = [
        event
        for event in history
        if isinstance(event, dict)
        and event.get("event_type") == "PACKAGE_ACTIVATED"
        and event.get("static_plan_manifest_sha256") == MANIFEST_SHA256
    ] if isinstance(history, list) else []
    if len(activations) != 1:
        raise GateError("v2.4 source activation event is missing or ambiguous")
    activation_sha256 = activations[0].get("event_sha256")
    if not isinstance(activation_sha256, str) or not re.fullmatch(
        r"[0-9a-f]{64}", activation_sha256
    ):
        raise GateError("v2.4 source activation event SHA-256 is invalid")

    lock_path = repo_file(root, TOOLCHAIN_LOCK_RELATIVE)
    return GateContext(
        checks=tuple(checks),
        target_goal_sha256=goal_sha256,
        source_activation_event_sha256=activation_sha256,
        toolchain_lock_sha256=sha256_file(lock_path),
    )


def repository_snapshot_from_payload(
    payload: dict[str, Any],
    *,
    event_id: str,
    output_sha256: str,
) -> dict[str, Any]:
    if (
        payload.get("schema_version") != "1.0.0"
        or payload.get("evidence_type") != "GATE_REPOSITORY_STATE"
        or payload.get("gate_event_id") != event_id
    ):
        raise GateError("REPOSITORY_STATE output identity differs")
    repository = payload.get("repository")
    raw = payload.get("git_status_raw")
    dirty = payload.get("dirty_snapshot")
    controlled = payload.get("checkpoint_controlled_working_snapshot")
    if not all(isinstance(value, dict) for value in (
        repository, raw, dirty, controlled
    )):
        raise GateError("REPOSITORY_STATE output structure differs")
    assert isinstance(repository, dict)
    assert isinstance(raw, dict)
    assert isinstance(dirty, dict)
    assert isinstance(controlled, dict)
    return {
        "gate_event_id": event_id,
        "snapshot_scope": payload.get("snapshot_scope"),
        "head_commit": repository.get("head_commit"),
        "branch": repository.get("branch"),
        "object_format": repository.get("object_format"),
        "git_status_raw_sha256": raw.get("sha256"),
        "git_status_raw_byte_count": raw.get("byte_count"),
        "git_status_raw_record_count": raw.get("record_count"),
        "dirty_path_count": dirty.get("dirty_path_count"),
        "path_set_sha256": dirty.get("path_set_sha256"),
        "content_set_sha256": dirty.get("content_set_sha256"),
        "index_state_sha256": dirty.get("index_state_sha256"),
        "checkpoint_base_head": controlled.get("base_head"),
        "checkpoint_managed_path_count": controlled.get(
            "managed_changed_path_count"
        ),
        "checkpoint_path_set_sha256": controlled.get("path_set_sha256"),
        "checkpoint_content_set_sha256": controlled.get("content_set_sha256"),
        "gate_repository_state_output_sha256": output_sha256,
    }


def _document_id(event_id: str) -> str:
    match = EVENT_ID_RE.fullmatch(event_id)
    if match is None:
        raise GateError(
            "event ID must match WS-GOAL-GRAPH-V2-4-GOAL-STARTED-"
            "FP048-YYYYMMDD-NNN"
        )
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP048-"
        f"{match.group(1)}-{match.group(2)}"
    )


def run_gate(
    root: Path,
    event_id: str,
    *,
    process_runner: Callable[..., subprocess.CompletedProcess[Any]] = (
        subprocess.run
    ),
    clock: Callable[[], datetime] = lambda: datetime.now(
        ZoneInfo("Asia/Seoul")
    ),
) -> Path:
    document_id = _document_id(event_id)
    root = root.resolve(strict=True)
    gate_root = (root / GATE_ROOT_RELATIVE).resolve(strict=True)
    if not gate_root.is_relative_to(root) or not gate_root.is_dir():
        raise GateError("goal-gates root is missing or unsafe")
    event_dir = gate_root / event_id
    if event_dir.exists():
        raise GateError(f"gate event directory already exists: {event_dir}")
    context = load_gate_context(root)
    event_dir.mkdir(mode=0o755, parents=False, exist_ok=False)

    environment = os.environ.copy()
    environment["WALKSAFE_NODE_BIN_DIR"] = PINNED_NODE_BIN_DIR
    environment["WALKSAFE_LOCKED_TEST_PYTHON"] = LOCKED_TEST_PYTHON
    environment["WALKSAFE_GATE_EVENT_ID"] = event_id

    previous_time: datetime | None = None

    def timestamp(*, second_precision: bool = False) -> str:
        nonlocal previous_time
        value = clock()
        if value.tzinfo is None or value.utcoffset() is None:
            raise GateError("gate clock must return a timezone-aware datetime")
        if second_precision:
            value = value.replace(microsecond=0)
            if previous_time is not None and value < previous_time:
                value = previous_time.replace(microsecond=0)
                if value < previous_time:
                    value += timedelta(seconds=1)
        elif previous_time is not None and value <= previous_time:
            value = previous_time + timedelta(microseconds=1)
        previous_time = value
        return value.isoformat()

    started_at = timestamp(second_precision=True)
    check_runs: list[dict[str, Any]] = []
    for index, (check_id, command) in enumerate(context.checks, start=1):
        output_relative = (
            GATE_ROOT_RELATIVE / event_id / f"{index:02d}-{check_id}.log"
        )
        output_path = root / output_relative
        executed_at = timestamp()
        with output_path.open("xb") as output:
            result = process_runner(
                command,
                shell=True,
                executable="/bin/bash",
                cwd=root,
                env=environment,
                stdout=output,
                stderr=subprocess.STDOUT,
                check=False,
            )
        exit_code = int(result.returncode)
        if exit_code != 0:
            raise GateCheckFailed(
                check_id,
                exit_code,
                output_relative.as_posix(),
            )
        if output_path.stat().st_size == 0 and not (
            index == 15 and check_id == "TEST_LAYER_REGISTRY_VALIDATE"
        ):
            raise GateError(f"{check_id} succeeded without required output")
        check_runs.append(
            {
                "check_id": check_id,
                "command": command,
                "output_path": output_relative.as_posix(),
                "output_sha256": sha256_file(output_path),
                "exit_code": 0,
                "executed_at": executed_at,
            }
        )

    ended_at = timestamp(second_precision=True)
    repository_log = event_dir / "19-REPOSITORY_STATE.log"
    try:
        repository_payload = load_json(repository_log)
    except (OSError, json.JSONDecodeError) as exc:
        raise GateError(f"REPOSITORY_STATE output is not valid JSON: {exc}") from exc
    repository_snapshot = repository_snapshot_from_payload(
        repository_payload,
        event_id=event_id,
        output_sha256=check_runs[-1]["output_sha256"],
    )
    generated_at = timestamp(second_precision=True)
    receipt = {
        "schema_version": "1.0",
        "document_id": document_id,
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": GATE_PURPOSE,
        "status": "PASS",
        "package_id": PACKAGE_ID,
        "target_transition_event_id": event_id,
        "target_goal_id": TARGET_GOAL_ID,
        "target_goal_content_sha256": context.target_goal_sha256,
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "source_activation_event_sha256": (
            context.source_activation_event_sha256
        ),
        "check_command_contract_version": CONTRACT_VERSION,
        "check_command_contract_sha256": CONTRACT_SHA256,
        "toolchain_lock_binding": {
            "path": TOOLCHAIN_LOCK_RELATIVE.as_posix(),
            "file_sha256": context.toolchain_lock_sha256,
        },
        "execution_window": {
            "started_at": started_at,
            "ended_at": ended_at,
        },
        "check_runs": check_runs,
        "repository_snapshot": repository_snapshot,
        "generated_at": generated_at,
    }
    if set(receipt) != RECEIPT_FIELDS:
        raise GateError("implementation-start receipt field set differs")
    receipt_path = event_dir / "implementation-start-gate-receipt.json"
    with receipt_path.open("xb") as output:
        output.write(
            (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode(
                "utf-8"
            )
        )
    return receipt_path


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        receipt_path = run_gate(args.root, args.event_id)
    except GateCheckFailed as exc:
        print(f"FP-048 initial-start gate: FAIL: {exc}", file=sys.stderr)
        return exc.exit_code if exc.exit_code > 0 else 1
    except (GateError, OSError, ValueError) as exc:
        print(f"FP-048 initial-start gate: ERROR: {exc}", file=sys.stderr)
        return 2
    print(f"FP-048 initial-start gate: PASS: {receipt_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
