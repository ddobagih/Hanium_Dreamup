#!/usr/bin/env python3
"""Run the private eight-check NPC single-admin-recovery start gate.

The hardened filesystem, process, repository, and add-only receipt runtime is
shared with the sealed FP-008 runner.  This wrapper supplies only the NPC Goal
identity, the add-only R002 contract, and the exact seq59 control-correction
source validation.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

_IMPLEMENTATION_PATH = ROOT / "scripts/run_walksafe_fp008_goal_start_gate_20260803.py"
_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_npc_single_admin_recovery_private_gate_runtime_20260812",
    _IMPLEMENTATION_PATH,
)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError("FP008 hardened gate runtime cannot be loaded")
_impl = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = _impl
_SPEC.loader.exec_module(_impl)
_base_load_checkpoint = _impl._load_checkpoint

from scripts import (
    apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812
    as _control_reanchor,
)
from scripts import (
    apply_walksafe_npc_goal_start_control_correction_seq59_20260812
    as _control_correction,
)


CHECKPOINT_RELATIVE = Path("docs/control/walksafe-project-continuation-checkpoint.json")
MANIFEST_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "static-plan-manifest-v2.4.0.json"
)
GATE_ROOT_RELATIVE = Path("docs/control/execution/goal-gates")
ROOT_CONTROL_TEST_RELATIVE = Path(
    "tests/test_run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py"
)
CONTROL_REANCHOR_RELATIVE = Path(
    "scripts/apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812.py"
)
CONTROL_CORRECTION_RELATIVE = Path(
    "scripts/apply_walksafe_npc_goal_start_control_correction_seq59_20260812.py"
)
CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/"
    "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001/"
    "initial-start-gate-contract-r002.json"
)
GOAL_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-03/epic-03-npc-single-admin-recovery-r001.md"
)
RUNTIME_BINDING_RELATIVES = (
    Path("apps/android/gradle/wrapper/gradle-wrapper.properties"),
    Path("apps/android/gradle/wrapper/gradle-wrapper.jar"),
    Path("apps/android/gradle/verification-metadata.xml"),
    Path("apps/android/adminapp/gradle.lockfile"),
)
SOURCE_GUARD_RELATIVES = (
    CHECKPOINT_RELATIVE,
    CONTRACT_RELATIVE,
    GOAL_RELATIVE,
    MANIFEST_RELATIVE,
    ROOT_CONTROL_TEST_RELATIVE,
    CONTROL_REANCHOR_RELATIVE,
    CONTROL_CORRECTION_RELATIVE,
    *RUNTIME_BINDING_RELATIVES,
)

PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
TARGET_GOAL_ID = "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
TARGET_GOAL_SHA256 = (
    "234a224883779208ba7878a9865076083bb9cfd205dfdb7a760b043f7af6b16d"
)
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R001"
WORK_ITEM_ID = "EPIC-03-NPC-SINGLE-ADMIN-RECOVERY"
SOURCE_SEQUENCE = 59
CONTROL_REANCHOR_SEQUENCE = 58
CONTROL_CORRECTION_SEQUENCE = 59
SOURCE_READY_SEQUENCE = 57
READY_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-NPC-SINGLE-ADMIN-RECOVERY-20260810-001"
)
SOURCE_READY_EVENT_SHA256 = (
    "08e25cd9808e2301a4af7a3b463d5a1cda795caf41eed57a35de29676f2210ef"
)
CONTROL_REANCHOR_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-20260812-001"
)
CONTROL_CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "NPC-CORRECTION-20260812-001"
)
READY_FRONTIER = (
    TARGET_GOAL_ID,
    PARENT_GOAL_ID,
    "WS-GOAL-EPIC-12",
)
GATE_PURPOSE = "INITIAL_START"
MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)
LOCKED_TEST_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
CONTRACT_DOCUMENT_ID = (
    "WS-NPC-SINGLE-ADMIN-RECOVERY-INITIAL-START-GATE-CONTRACT-20260812-002"
)
CONTRACT_ID = "WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-START-GATE-R002"
CONTRACT_VERSION = "2026-08-12.1"
CONTRACT_FILE_SHA256 = (
    "37b843953a5c8089ae2b23224804fbef0c21150c2f2bbf876a57cb4cd3083227"
)
CONTRACT_CANONICAL_SHA256 = (
    "1ca0369ac5be15375514d800b1c5a66f9a3ff3af4c487df6e379f57a54ca67de"
)
EXPECTED_CHECK_IDS = (
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_TEST_DATABASE_PREFLIGHT",
    "BACKEND_ADMIN_SECURITY_RECOVERY_POSTGRES",
    "ANDROID_ADMIN_INTERNAL",
    "ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)
FORBIDDEN_COMMAND_FRAGMENTS = (
    "apps/web",
    "android-gateway",
    "connecteddebugandroidtest",
    " adb ",
    "device",
    "external",
    "formal",
    "deploy",
    "release",
)
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-"
    r"(\d{8})-(\d{3})$"
)
BURNED_EVENT_IDS = frozenset(
    {
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-"
        "20260812-001"
    }
)

R001_CONTRACT_BINDING = {
    "document_id": (
        "WS-NPC-SINGLE-ADMIN-RECOVERY-INITIAL-START-GATE-CONTRACT-20260810-001"
    ),
    "contract_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-START-GATE-R001",
    "contract_version": "2026-08-10.1",
    "path": (
        "docs/control/execution/goal-contracts/"
        "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001/"
        "initial-start-gate-contract-r001.json"
    ),
    "file_sha256": (
        "0e9b80005e3ad206af6d43d724dfc70688e6d7ef8907ad6ee2c72da2e60679d1"
    ),
    "canonical_sha256": (
        "b6a8ada662716ee963f1248ffdf5fdd730c545640a35a7fc11ed134016321186"
    ),
}

_R002_CONTRACT_FIELDS = {
    "schema_version",
    "document_id",
    "contract_id",
    "contract_version",
    "target_goal_id",
    "target_goal_content_sha256",
    "gate_purpose",
    "successor_reason_code",
    "supersedes",
    "ordered_checks",
    "claim_boundary",
}


def expected_contract_binding() -> dict[str, Any]:
    return {
        "schema_version": "1.1",
        "document_id": CONTRACT_DOCUMENT_ID,
        "path": CONTRACT_RELATIVE.as_posix(),
        "file_sha256": CONTRACT_FILE_SHA256,
        "contract_id": CONTRACT_ID,
        "contract_version": CONTRACT_VERSION,
        "canonical_contract_sha256": CONTRACT_CANONICAL_SHA256,
    }


def _load_gate_contract(
    root: Path,
    *,
    retained_content: bytes | None = None,
) -> tuple[tuple[tuple[str, str], ...], dict[str, Any]]:
    content = (
        retained_content
        if retained_content is not None
        else _impl.repo_file(root, CONTRACT_RELATIVE).read_bytes()
    )
    if _impl.sha256_bytes(content) != CONTRACT_FILE_SHA256:
        raise _impl.GateError("NPC R002 initial-start contract file SHA-256 differs")
    try:
        contract = json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise _impl.GateError("NPC R002 initial-start contract is not valid JSON") from exc
    if not isinstance(contract, dict) or set(contract) != _R002_CONTRACT_FIELDS:
        raise _impl.GateError("NPC R002 initial-start contract field set differs")
    if _impl.canonical_sha256(contract) != CONTRACT_CANONICAL_SHA256:
        raise _impl.GateError("NPC R002 initial-start canonical SHA-256 differs")
    if (
        contract.get("schema_version") != "1.1"
        or contract.get("document_id") != CONTRACT_DOCUMENT_ID
        or contract.get("contract_id") != CONTRACT_ID
        or contract.get("contract_version") != CONTRACT_VERSION
        or contract.get("target_goal_id") != TARGET_GOAL_ID
        or contract.get("target_goal_content_sha256") != TARGET_GOAL_SHA256
        or contract.get("gate_purpose") != GATE_PURPOSE
        or contract.get("successor_reason_code")
        != "CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED"
    ):
        raise _impl.GateError("NPC R002 initial-start contract identity differs")
    supersedes = contract.get("supersedes")
    if not isinstance(supersedes, dict) or supersedes != {
        **R001_CONTRACT_BINDING,
        "source_ready_event_sequence": SOURCE_READY_SEQUENCE,
        "source_ready_event_id": READY_EVENT_ID,
        "source_ready_event_sha256": SOURCE_READY_EVENT_SHA256,
    }:
        raise _impl.GateError("NPC R002 predecessor binding differs")
    ordered_checks = contract.get("ordered_checks")
    if not isinstance(ordered_checks, list):
        raise _impl.GateError("NPC R002 ordered checks are missing")
    checks: list[tuple[str, str]] = []
    for item in ordered_checks:
        if (
            not isinstance(item, dict)
            or set(item) != {"check_id", "command"}
            or not isinstance(item.get("check_id"), str)
            or not isinstance(item.get("command"), str)
            or not item["command"].strip()
        ):
            raise _impl.GateError("NPC R002 ordered check differs")
        checks.append((item["check_id"], item["command"]))
    frozen = tuple(checks)
    if tuple(check_id for check_id, _ in frozen) != EXPECTED_CHECK_IDS:
        raise _impl.GateError("NPC R002 check order differs")
    if len(set(EXPECTED_CHECK_IDS)) != len(EXPECTED_CHECK_IDS):
        raise _impl.GateError("NPC R002 check IDs are not unique")
    root_regression = dict(frozen)[
        "ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION"
    ]
    if ROOT_CONTROL_TEST_RELATIVE.as_posix() not in root_regression:
        raise _impl.GateError("NPC R002 does not execute its gate regression")
    for check_id, command in frozen:
        lowered = command.lower()
        forbidden = [
            fragment
            for fragment in FORBIDDEN_COMMAND_FRAGMENTS
            if fragment in lowered
        ]
        if forbidden:
            raise _impl.GateError(
                f"{check_id} contains forbidden command scope: {forbidden[0]}"
            )
    return frozen, contract


def _load_checkpoint(
    root: Path,
    *,
    retained_content: bytes | None = None,
) -> tuple[bytes, dict[str, Any]]:
    content, checkpoint = _base_load_checkpoint(
        root,
        retained_content=retained_content,
    )
    try:
        _control_correction.require_control_corrected_checkpoint(
            root,
            checkpoint,
            run_external_validators=False,
        )
    except _control_correction.ControlCorrectionError as exc:
        raise _impl.GateError(
            f"NPC seq59 control-correction source differs: {exc}"
        ) from exc
    return content, checkpoint


def _document_id(event_id: str) -> str:
    if event_id in BURNED_EVENT_IDS:
        raise _impl.GateError("event ID belongs to an immutable failed gate attempt")
    match = EVENT_ID_RE.fullmatch(event_id)
    if match is None:
        raise _impl.GateError(
            "event ID must match WS-GOAL-GRAPH-V2-4-GOAL-STARTED-"
            "NPC-SINGLE-ADMIN-RECOVERY-YYYYMMDD-NNN"
        )
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-"
        "NPC-SINGLE-ADMIN-RECOVERY-"
        f"{match.group(1)}-{match.group(2)}"
    )


def _parse_timestamp(value: Any, *, label: str) -> datetime:
    if not isinstance(value, str):
        raise _impl.GateError(f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise _impl.GateError(f"{label} is not valid ISO-8601") from exc
    if parsed.utcoffset() is None:
        raise _impl.GateError(f"{label} must include a timezone")
    return parsed


def _validate_ready_source(
    checkpoint: dict[str, Any],
    *,
    contract_binding: dict[str, Any],
) -> tuple[str, datetime]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if not isinstance(state, dict) or not isinstance(history, list):
        raise _impl.GateError("checkpoint goal execution is missing")
    if len(history) != SOURCE_SEQUENCE:
        raise _impl.GateError("NPC gate requires the exact seq59 source")
    ready = history[-3]
    reanchor = history[-2]
    correction = history[-1]
    if not all(isinstance(event, dict) for event in (ready, reanchor, correction)):
        raise _impl.GateError("NPC seq57/58/59 source events are missing")
    if (
        ready.get("sequence") != SOURCE_READY_SEQUENCE
        or ready.get("event_id") != READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != TARGET_GOAL_ID
        or ready.get("focus_goal_id") != TARGET_GOAL_ID
        or ready.get("focus_goal_content_sha256") != TARGET_GOAL_SHA256
        or ready.get("from_status") != "PLANNED"
        or ready.get("to_status") != "READY"
        or ready.get("event_sha256") != SOURCE_READY_EVENT_SHA256
        or ready.get("event_sha256") != _impl.event_sha256(ready)
        or ready.get("implementation_start_gate_contract_binding")
        != R001_CONTRACT_BINDING
    ):
        raise _impl.GateError("NPC seq57 READY event differs")
    if (
        reanchor.get("sequence") != CONTROL_REANCHOR_SEQUENCE
        or reanchor.get("event_id") != CONTROL_REANCHOR_EVENT_ID
        or reanchor.get("event_type") != "GOAL_START_CONTROL_REANCHORED"
        or reanchor.get("focus_goal_id") != TARGET_GOAL_ID
        or reanchor.get("subject_goal_id") != TARGET_GOAL_ID
        or reanchor.get("from_status") != "READY"
        or reanchor.get("to_status") != "READY"
        or reanchor.get("status_changes") != {}
        or reanchor.get("previous_event_sha256") != SOURCE_READY_EVENT_SHA256
        or reanchor.get("event_sha256") != _impl.event_sha256(reanchor)
    ):
        raise _impl.GateError("NPC seq58 control-reanchor event differs")

    supersession = reanchor.get("contract_supersession")
    if not isinstance(supersession, dict) or supersession != {
        "previous_contract_binding": R001_CONTRACT_BINDING,
        "replacement_contract_binding": contract_binding,
        "reason_code": "CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED",
    }:
        raise _impl.GateError("NPC seq58 contract supersession differs")
    source_ready = reanchor.get("source_ready_event_binding")
    if source_ready != _control_reanchor._source_ready_event_binding():
        raise _impl.GateError("NPC seq58 source READY binding differs")
    if (
        correction.get("sequence") != CONTROL_CORRECTION_SEQUENCE
        or correction.get("event_id") != CONTROL_CORRECTION_EVENT_ID
        or correction.get("event_type") != "GOAL_START_CONTROL_REANCHORED"
        or correction.get("focus_goal_id") != TARGET_GOAL_ID
        or correction.get("subject_goal_id") != TARGET_GOAL_ID
        or correction.get("from_status") != "READY"
        or correction.get("to_status") != "READY"
        or correction.get("status_changes") != {}
        or correction.get("previous_event_sha256") != reanchor.get("event_sha256")
        or correction.get("event_sha256") != _impl.event_sha256(correction)
    ):
        raise _impl.GateError("NPC seq59 control-correction event differs")
    if (
        state.get("transition_history_anchor_sha256")
        != correction.get("event_sha256")
        or state.get("package_id") != PACKAGE_ID
        or state.get("package_status") != "ACTIVE"
        or state.get("activation_status") != "ACTIVE"
        or state.get("focus_goal_id") != TARGET_GOAL_ID
        or state.get("focus_goal_path") != GOAL_RELATIVE.as_posix()
        or state.get("focus_work_item_id") != WORK_ITEM_ID
        or state.get("focus_source") != "IMPLEMENTATION_BACKLOG"
        or state.get("ready_frontier_goal_ids") != list(READY_FRONTIER)
    ):
        raise _impl.GateError("NPC seq59 active focus differs")
    if any(
        isinstance(event, dict)
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        for event in history
    ):
        raise _impl.GateError("NPC Goal has already been started")
    current = checkpoint.get("current_work")
    if (
        not isinstance(current, dict)
        or current.get("work_item_id") != WORK_ITEM_ID
        or current.get("current_focus")
        != "NPC-SINGLE-ADMIN-RECOVERY/GAP-008 Goal READY; internal start gate not run"
        or current.get("release_completion_claimed") is not False
    ):
        raise _impl.GateError("NPC current-work READY pointer differs")
    return SOURCE_READY_EVENT_SHA256, _parse_timestamp(
        correction.get("occurred_at"),
        label="NPC seq59 occurred_at",
    )


for _name, _value in {
    "CHECKPOINT_RELATIVE": CHECKPOINT_RELATIVE,
    "MANIFEST_RELATIVE": MANIFEST_RELATIVE,
    "GATE_ROOT_RELATIVE": GATE_ROOT_RELATIVE,
    "ROOT_CONTROL_TEST_RELATIVE": ROOT_CONTROL_TEST_RELATIVE,
    "CONTROL_REANCHOR_RELATIVE": CONTROL_REANCHOR_RELATIVE,
    "CONTROL_CORRECTION_RELATIVE": CONTROL_CORRECTION_RELATIVE,
    "CONTRACT_RELATIVE": CONTRACT_RELATIVE,
    "GOAL_RELATIVE": GOAL_RELATIVE,
    "RUNTIME_BINDING_RELATIVES": RUNTIME_BINDING_RELATIVES,
    "SOURCE_GUARD_RELATIVES": SOURCE_GUARD_RELATIVES,
    "PACKAGE_ID": PACKAGE_ID,
    "TARGET_GOAL_ID": TARGET_GOAL_ID,
    "TARGET_GOAL_SHA256": TARGET_GOAL_SHA256,
    "PARENT_GOAL_ID": PARENT_GOAL_ID,
    "PREDECESSOR_GOAL_ID": PREDECESSOR_GOAL_ID,
    "WORK_ITEM_ID": WORK_ITEM_ID,
    "SOURCE_SEQUENCE": SOURCE_SEQUENCE,
    "CONTROL_REANCHOR_SEQUENCE": CONTROL_REANCHOR_SEQUENCE,
    "CONTROL_CORRECTION_SEQUENCE": CONTROL_CORRECTION_SEQUENCE,
    "READY_EVENT_ID": READY_EVENT_ID,
    "SOURCE_READY_EVENT_SHA256": SOURCE_READY_EVENT_SHA256,
    "CONTROL_REANCHOR_EVENT_ID": CONTROL_REANCHOR_EVENT_ID,
    "CONTROL_CORRECTION_EVENT_ID": CONTROL_CORRECTION_EVENT_ID,
    "READY_FRONTIER": READY_FRONTIER,
    "GATE_PURPOSE": GATE_PURPOSE,
    "MANIFEST_SHA256": MANIFEST_SHA256,
    "LOCKED_TEST_PYTHON": LOCKED_TEST_PYTHON,
    "CONTRACT_DOCUMENT_ID": CONTRACT_DOCUMENT_ID,
    "CONTRACT_ID": CONTRACT_ID,
    "CONTRACT_VERSION": CONTRACT_VERSION,
    "CONTRACT_FILE_SHA256": CONTRACT_FILE_SHA256,
    "CONTRACT_CANONICAL_SHA256": CONTRACT_CANONICAL_SHA256,
    "EXPECTED_CHECK_IDS": EXPECTED_CHECK_IDS,
    "FORBIDDEN_COMMAND_FRAGMENTS": FORBIDDEN_COMMAND_FRAGMENTS,
    "EVENT_ID_RE": EVENT_ID_RE,
    "BURNED_EVENT_IDS": BURNED_EVENT_IDS,
}.items():
    setattr(_impl, _name, _value)

_impl.expected_contract_binding = expected_contract_binding
_impl._load_gate_contract = _load_gate_contract
_impl._load_checkpoint = _load_checkpoint
_impl._document_id = _document_id
_impl._validate_ready_source = _validate_ready_source


GateError = _impl.GateError
GateCheckFailed = _impl.GateCheckFailed
GatePostCommitUncertain = _impl.GatePostCommitUncertain
GateContext = _impl.GateContext
RECEIPT_FIELDS = _impl.RECEIPT_FIELDS
RECEIPT_NAME = _impl.RECEIPT_NAME
canonical_sha256 = _impl.canonical_sha256
event_sha256 = _impl.event_sha256
repository_snapshot_from_payload = _impl.repository_snapshot_from_payload
capture_repository_state = _impl.capture_repository_state
_private_file_bytes = _impl._private_file_bytes


def load_gate_context(
    root: Path,
    retained_contents: Any = None,
) -> GateContext:
    return _impl.load_gate_context(root, retained_contents)


def run_gate(root: Path, event_id: str, **kwargs: Any) -> Path:
    return _impl.run_gate(root, event_id, **kwargs)


def __getattr__(name: str) -> Any:
    return getattr(_impl, name)


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
        print(f"NPC single-admin-recovery start gate: FAIL: {exc}", file=sys.stderr)
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        print(
            "NPC single-admin-recovery start gate: POSTCOMMIT-UNCERTAIN: "
            f"{exc}",
            file=sys.stderr,
        )
        return 2
    except (GateError, OSError, ValueError) as exc:
        print(f"NPC single-admin-recovery start gate: ERROR: {exc}", file=sys.stderr)
        return 2
    print(f"NPC single-admin-recovery start gate: PASS: {receipt_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
