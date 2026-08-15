#!/usr/bin/env python3
"""Run the private seven-check FP-022 initial-start gate.

The filesystem, process, repository, and add-only receipt hardening is shared
with the sealed FP-008 gate runtime.  This wrapper supplies only the FP-022
identity, successor contract, runtime bindings, and exact seq68 start-control
source validation.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import stat
import sys
from contextvars import ContextVar
from datetime import datetime
from pathlib import Path
from typing import Any, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

_IMPLEMENTATION_PATH = (
    ROOT / "scripts/run_walksafe_fp008_goal_start_gate_20260803.py"
)
_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp022_private_gate_runtime_20260813",
    _IMPLEMENTATION_PATH,
)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError("FP008 hardened gate runtime cannot be loaded")
_impl = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = _impl
_SPEC.loader.exec_module(_impl)
_base_load_checkpoint = _impl._load_checkpoint


CHECKPOINT_RELATIVE = Path(
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
MANIFEST_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "static-plan-manifest-v2.4.0.json"
)
GATE_ROOT_RELATIVE = Path("docs/control/execution/goal-gates")
ROOT_CONTROL_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp022_goal_seq66_67_20260813.py"
)
RUNNER_RELATIVE = Path(
    "scripts/run_walksafe_fp022_goal_start_gate_20260813.py"
)
CONTROL_REANCHOR_RELATIVE = Path(
    "scripts/apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814.py"
)
START_APPLY_RELATIVE = Path(
    "scripts/apply_walksafe_fp022_goal_started_seq69_20260814.py"
)
REVIEW_BUILDER_RELATIVE = Path(
    "scripts/build_walksafe_fp022_seq68_69_review_20260814.py"
)
CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-04-FP-022-R001/"
    "initial-start-gate-contract-r002.json"
)
R001_CONTRACT_RELATIVE = CONTRACT_RELATIVE.with_name(
    "initial-start-gate-contract-r001.json"
)
GOAL_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-04/epic-04-fp022-tmap-destination-route-r001.md"
)
RUNTIME_BINDING_RELATIVES = (
    Path("apps/android/gradle/wrapper/gradle-wrapper.properties"),
    Path("apps/android/gradle/wrapper/gradle-wrapper.jar"),
    Path("apps/android/gradle/verification-metadata.xml"),
    Path("apps/android/app/gradle.lockfile"),
)
SOURCE_GUARD_RELATIVES = (
    CHECKPOINT_RELATIVE,
    CONTRACT_RELATIVE,
    GOAL_RELATIVE,
    MANIFEST_RELATIVE,
    ROOT_CONTROL_TEST_RELATIVE,
    RUNNER_RELATIVE,
    CONTROL_REANCHOR_RELATIVE,
    START_APPLY_RELATIVE,
    REVIEW_BUILDER_RELATIVE,
    *RUNTIME_BINDING_RELATIVES,
)

PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
TARGET_GOAL_ID = "WS-GOAL-EPIC-04-FP-022-R001"
TARGET_GOAL_SHA256 = (
    "939075c1b4bcbf9b8280c37cb7a449fd28763f06cda14faf0ca88f691734576b"
)
PARENT_GOAL_ID = "WS-GOAL-EPIC-04"
PREDECESSOR_GOAL_ID = (
    "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
)
PREDECESSOR_COMPLETION_EVENT_SHA256 = (
    "24f586dda8e19d9c1315bee80e546906359869c49e5fa776bb891ec76beb13c1"
)
DEPENDENCY_GOAL_ID = "WS-GOAL-EPIC-02"
DEPENDENCY_COMPLETION_EVENT_SHA256 = (
    "e3cae0925f1e3bcf36335b68ead21786bd3c690c2cb4414e675412a0b76e7dd7"
)
WORK_ITEM_ID = TARGET_GOAL_ID
SOURCE_SEQUENCE = 68
SOURCE_READY_SEQUENCE = 67
MATERIALIZED_SEQUENCE = 66
MATERIALIZED_EVENT_ID: str | None = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-FP022-20260813-001"
)
READY_EVENT_ID: str | None = (
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP022-20260813-001"
)
SOURCE_READY_EVENT_SHA256 = (
    "37207b4393dd5820de6b71c7e167885f8592875f8b54d9d75d650aef230a87a2"
)
CONTROL_REANCHOR_SEQUENCE = 68
CONTROL_REANCHOR_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP022-20260814-001"
)
READY_FRONTIER = (
    TARGET_GOAL_ID,
    PARENT_GOAL_ID,
    "WS-GOAL-EPIC-12",
)
READY_CURRENT_FOCUS = (
    "FP-022/GAP-031 Goal READY; active internal start gate not run"
)
GATE_PURPOSE = "INITIAL_START"
MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)
LOCKED_TEST_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
GRADLE_USER_HOME = Path("/home/ddobagi/.gradle")
CONTRACT_DOCUMENT_ID = (
    "WS-FP022-INITIAL-START-GATE-CONTRACT-20260814-002"
)
CONTRACT_ID = "WS-FP022-INTERNAL-START-GATE-R002"
CONTRACT_VERSION = "2026-08-14.1"
CONTRACT_FILE_SHA256 = (
    "8e7f55dffcc0725fcb831118ed553b79ed09a41c0d323d00da343fa26ab01b42"
)
CONTRACT_CANONICAL_SHA256 = (
    "bc382fc5095cfa0eef246447f961d268ff3953274ba1df84360a1a5bb4663e7c"
)
EXPECTED_CHECK_IDS = (
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_NAVIGATION_INTERNAL",
    "ANDROID_USER_INTERNAL",
    "ROOT_FP022_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)
FORBIDDEN_COMMAND_FRAGMENTS = (
    "apps/web",
    "adminapp",
    "android-gateway",
    "npm",
    "pwa",
    "legacy",
    "connecteddebugandroidtest",
    " adb ",
    "device",
    "external",
    "formal",
    "deploy",
    "release",
)
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-(\d{8})-(\d{3})$"
)
STARTED_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001"
R001_CONTRACT_BINDING = {
    "schema_version": "1.0",
    "document_id": "WS-FP022-INITIAL-START-GATE-CONTRACT-20260813-001",
    "path": R001_CONTRACT_RELATIVE.as_posix(),
    "file_sha256": (
        "14ad8eee8a6c972c1e9091c2065b0779acdde427d0176b84fb83b18840865c25"
    ),
    "contract_id": "WS-FP022-INTERNAL-START-GATE-R001",
    "contract_version": "2026-08-13.1",
    "canonical_contract_sha256": (
        "a5d39ea4a1c7f919e4d3d08f3a429f7de0bdadec9d03756df7f32f4bbef074ab"
    ),
}
SUCCESSOR_REASON_CODE = (
    "SEQ67_READY_TRUST_ANCHOR_AND_CURRENT_CONTROL_COHORT_REQUIRED"
)
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


for _name, _value in {
    "CHECKPOINT_RELATIVE": CHECKPOINT_RELATIVE,
    "MANIFEST_RELATIVE": MANIFEST_RELATIVE,
    "GATE_ROOT_RELATIVE": GATE_ROOT_RELATIVE,
    "ROOT_CONTROL_TEST_RELATIVE": ROOT_CONTROL_TEST_RELATIVE,
    "RUNNER_RELATIVE": RUNNER_RELATIVE,
    "CONTROL_REANCHOR_RELATIVE": CONTROL_REANCHOR_RELATIVE,
    "START_APPLY_RELATIVE": START_APPLY_RELATIVE,
    "REVIEW_BUILDER_RELATIVE": REVIEW_BUILDER_RELATIVE,
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
    "SOURCE_READY_SEQUENCE": SOURCE_READY_SEQUENCE,
    "MATERIALIZED_EVENT_ID": MATERIALIZED_EVENT_ID,
    "READY_EVENT_ID": READY_EVENT_ID,
    "SOURCE_READY_EVENT_SHA256": SOURCE_READY_EVENT_SHA256,
    "CONTROL_REANCHOR_SEQUENCE": CONTROL_REANCHOR_SEQUENCE,
    "CONTROL_REANCHOR_EVENT_ID": CONTROL_REANCHOR_EVENT_ID,
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
}.items():
    setattr(_impl, _name, _value)


GateError = _impl.GateError
GateCheckFailed = _impl.GateCheckFailed
GatePostCommitUncertain = _impl.GatePostCommitUncertain
GateContext = _impl.GateContext
RECEIPT_FIELDS = _impl.RECEIPT_FIELDS
RECEIPT_NAME = _impl.RECEIPT_NAME
canonical_sha256 = _impl.canonical_sha256
event_sha256 = _impl.event_sha256
_private_file_bytes = _impl._private_file_bytes
repository_snapshot_from_payload = _impl.repository_snapshot_from_payload
capture_repository_state = _impl.capture_repository_state
_REQUIRE_LIVE_SNAPSHOT = ContextVar(
    "fp022_gate_require_live_snapshot",
    default=True,
)


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
        raise GateError("FP022 R002 initial-start contract file SHA-256 differs")
    try:
        contract = json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("FP022 R002 initial-start contract is not valid JSON") from exc
    if not isinstance(contract, dict) or set(contract) != _R002_CONTRACT_FIELDS:
        raise GateError("FP022 R002 initial-start contract field set differs")
    if canonical_sha256(contract) != CONTRACT_CANONICAL_SHA256:
        raise GateError("FP022 R002 canonical contract SHA-256 differs")
    if (
        contract.get("schema_version") != "1.1"
        or contract.get("document_id") != CONTRACT_DOCUMENT_ID
        or contract.get("contract_id") != CONTRACT_ID
        or contract.get("contract_version") != CONTRACT_VERSION
        or contract.get("target_goal_id") != TARGET_GOAL_ID
        or contract.get("target_goal_content_sha256") != TARGET_GOAL_SHA256
        or contract.get("gate_purpose") != GATE_PURPOSE
        or contract.get("successor_reason_code") != SUCCESSOR_REASON_CODE
    ):
        raise GateError("FP022 R002 initial-start contract identity differs")
    if contract.get("supersedes") != {
        "document_id": R001_CONTRACT_BINDING["document_id"],
        "contract_id": R001_CONTRACT_BINDING["contract_id"],
        "contract_version": R001_CONTRACT_BINDING["contract_version"],
        "path": R001_CONTRACT_BINDING["path"],
        "file_sha256": R001_CONTRACT_BINDING["file_sha256"],
        "canonical_sha256": R001_CONTRACT_BINDING[
            "canonical_contract_sha256"
        ],
        "source_ready_event_sequence": SOURCE_READY_SEQUENCE,
        "source_ready_event_id": READY_EVENT_ID,
        "source_ready_event_sha256": SOURCE_READY_EVENT_SHA256,
    }:
        raise GateError("FP022 R002 predecessor binding differs")
    raw_checks = contract.get("ordered_checks")
    if not isinstance(raw_checks, list):
        raise GateError("FP022 R002 ordered checks are missing")
    checks: list[tuple[str, str]] = []
    for item in raw_checks:
        if (
            not isinstance(item, dict)
            or set(item) != {"check_id", "command"}
            or not isinstance(item.get("check_id"), str)
            or not isinstance(item.get("command"), str)
            or not item["command"].strip()
        ):
            raise GateError("FP022 R002 ordered check differs")
        checks.append((item["check_id"], item["command"]))
    frozen = tuple(checks)
    if tuple(check_id for check_id, _ in frozen) != EXPECTED_CHECK_IDS:
        raise GateError("FP022 R002 check order differs")
    if len(set(EXPECTED_CHECK_IDS)) != len(EXPECTED_CHECK_IDS):
        raise GateError("FP022 R002 check IDs are not unique")
    for check_id, command in frozen:
        lowered = command.lower()
        forbidden = [
            fragment
            for fragment in FORBIDDEN_COMMAND_FRAGMENTS
            if fragment in lowered
        ]
        if forbidden:
            raise GateError(
                f"{check_id} contains forbidden command scope: {forbidden[0]}"
            )
    return frozen, contract


def _document_id(event_id: str) -> str:
    match = EVENT_ID_RE.fullmatch(event_id)
    if match is None:
        raise GateError(
            "event ID must match WS-GOAL-GRAPH-V2-4-GOAL-STARTED-"
            "FP022-YYYYMMDD-NNN"
        )
    if event_id != STARTED_EVENT_ID:
        raise GateError(f"event ID must equal {STARTED_EVENT_ID}")
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP022-"
        f"{match.group(1)}-{match.group(2)}"
    )


def _parse_timestamp(value: Any, *, label: str) -> datetime:
    if not isinstance(value, str):
        raise GateError(f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise GateError(f"{label} is not valid ISO-8601") from exc
    if parsed.utcoffset() is None:
        raise GateError(f"{label} must include a timezone")
    return parsed


def _validate_ready_source(
    checkpoint: dict[str, Any],
    *,
    contract_binding: dict[str, Any],
    expected_materialized_event_id: str | None = None,
    expected_ready_event_id: str | None = None,
    expected_ready_event_sha256: str | None = None,
) -> tuple[str, datetime]:
    sealed_materialized_event_id = (
        MATERIALIZED_EVENT_ID
        if expected_materialized_event_id is None
        else expected_materialized_event_id
    )
    sealed_ready_event_id = (
        READY_EVENT_ID
        if expected_ready_event_id is None
        else expected_ready_event_id
    )
    sealed_ready_sha256 = (
        SOURCE_READY_EVENT_SHA256
        if expected_ready_event_sha256 is None
        else expected_ready_event_sha256
    )
    if (
        not isinstance(sealed_materialized_event_id, str)
        or not sealed_materialized_event_id
        or not isinstance(sealed_ready_event_id, str)
        or not sealed_ready_event_id
    ):
        raise GateError("FP022 seq66/67 event IDs are not sealed")
    if (
        not isinstance(sealed_ready_sha256, str)
        or _impl.SHA256_RE.fullmatch(sealed_ready_sha256) is None
    ):
        raise GateError(
            "FP022 seq67 READY event SHA-256 trust anchor is not sealed"
        )

    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if not isinstance(state, dict) or not isinstance(history, list):
        raise GateError("checkpoint goal execution is missing")
    if len(history) != SOURCE_SEQUENCE:
        raise GateError("FP022 gate requires the exact seq68 control source")
    materialized = history[MATERIALIZED_SEQUENCE - 1]
    ready = history[SOURCE_READY_SEQUENCE - 1]
    reanchor = history[CONTROL_REANCHOR_SEQUENCE - 1]
    if not all(
        isinstance(item, dict) for item in (materialized, ready, reanchor)
    ):
        raise GateError("FP022 seq66/67/68 events are missing")
    if (
        materialized.get("sequence") != MATERIALIZED_SEQUENCE
        or materialized.get("event_id") != sealed_materialized_event_id
        or materialized.get("event_type") != "GOAL_MATERIALIZED"
        or materialized.get("materialized_goal_id") != TARGET_GOAL_ID
        or materialized.get("materialized_goal_path")
        != GOAL_RELATIVE.as_posix()
        or materialized.get("materialized_goal_content_sha256")
        != TARGET_GOAL_SHA256
        or materialized.get("predecessor_goal_id") != PREDECESSOR_GOAL_ID
        or materialized.get("to_status") != "PLANNED"
        or materialized.get("event_sha256") != event_sha256(materialized)
    ):
        raise GateError("FP022 seq66 materialization event differs")
    if (
        ready.get("sequence") != SOURCE_READY_SEQUENCE
        or ready.get("event_id") != sealed_ready_event_id
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != TARGET_GOAL_ID
        or ready.get("focus_goal_id") != TARGET_GOAL_ID
        or ready.get("focus_goal_content_sha256") != TARGET_GOAL_SHA256
        or ready.get("from_status") != "PLANNED"
        or ready.get("to_status") != "READY"
        or ready.get("previous_event_sha256")
        != materialized.get("event_sha256")
        or ready.get("event_sha256") != event_sha256(ready)
    ):
        raise GateError("FP022 seq67 readiness event differs")
    ready_sha256 = ready["event_sha256"]
    if ready_sha256 != sealed_ready_sha256:
        raise GateError("FP022 seq67 READY event SHA-256 trust anchor differs")
    from scripts import (
        apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814
        as reanchor_authority,
    )

    expected_source_ready_binding = reanchor_authority.source_ready_event_binding()
    expected_source_ready_binding.update(
        {
            "event_id": sealed_ready_event_id,
            "event_sha256": sealed_ready_sha256,
        }
    )
    if (
        set(reanchor) != reanchor_authority.EVENT_FIELDS
        or reanchor.get("sequence") != CONTROL_REANCHOR_SEQUENCE
        or reanchor.get("event_id") != CONTROL_REANCHOR_EVENT_ID
        or reanchor.get("event_type") != "GOAL_START_CONTROL_REANCHORED"
        or reanchor.get("subject_goal_id") != TARGET_GOAL_ID
        or reanchor.get("focus_goal_id") != TARGET_GOAL_ID
        or reanchor.get("focus_goal_content_sha256") != TARGET_GOAL_SHA256
        or reanchor.get("from_status") != "READY"
        or reanchor.get("to_status") != "READY"
        or reanchor.get("status_changes") != {}
        or reanchor.get("previous_event_sha256") != ready_sha256
        or reanchor.get("source_ready_event_binding")
        != expected_source_ready_binding
        or reanchor.get("source_checkpoint_binding")
        != reanchor_authority.source_checkpoint_binding()
        or reanchor.get("contract_supersession")
        != {
            "previous_contract_binding": (
                reanchor_authority.r001_contract_binding()
            ),
            "replacement_contract_binding": contract_binding,
            "reason_code": SUCCESSOR_REASON_CODE,
        }
        or reanchor.get("claim_boundary") != reanchor_authority.CLAIM_BOUNDARY
        or reanchor.get("event_sha256") != event_sha256(reanchor)
    ):
        raise GateError("FP022 seq68 start-control reanchor differs")
    if (
        state.get("transition_history_anchor_sha256")
        != reanchor.get("event_sha256")
    ):
        raise GateError("FP022 seq68 history anchor differs")

    expected_readiness_basis = {
        "dependency_completion_events": [
            {
                "goal_id": DEPENDENCY_GOAL_ID,
                "event_sha256": DEPENDENCY_COMPLETION_EVENT_SHA256,
            }
        ],
        "predecessor_goal_id": PREDECESSOR_GOAL_ID,
        "predecessor_completion_event_sha256": (
            PREDECESSOR_COMPLETION_EVENT_SHA256
        ),
    }
    if ready.get("readiness_basis") != expected_readiness_basis:
        raise GateError("FP022 seq67 readiness basis differs")
    if (
        state.get("package_id") != PACKAGE_ID
        or state.get("package_status") != "ACTIVE"
        or state.get("activation_status") != "ACTIVE"
        or state.get("focus_goal_id") != TARGET_GOAL_ID
        or state.get("focus_goal_path") != GOAL_RELATIVE.as_posix()
        or state.get("focus_work_item_id") != WORK_ITEM_ID
        or state.get("focus_source") != "IMPLEMENTATION_BACKLOG"
        or state.get("ready_frontier_goal_ids") != list(READY_FRONTIER)
    ):
        raise GateError("FP022 seq67 active focus differs")
    statuses = state.get("status_by_goal")
    if (
        not isinstance(statuses, dict)
        or statuses.get(TARGET_GOAL_ID) != "READY"
        or statuses.get(PREDECESSOR_GOAL_ID) != "COMPLETE_AT_TARGET"
        or statuses.get(DEPENDENCY_GOAL_ID) != "COMPLETE_AT_TARGET"
        or "IN_PROGRESS" in statuses.values()
    ):
        raise GateError("FP022 seq67 Goal statuses differ")
    blockers = state.get("blockers_by_goal")
    if (
        state.get("blocked_goal_ids") != []
        or state.get("pending_questions") != []
        or state.get("open_question_count") != 0
        or (isinstance(blockers, dict) and blockers.get(TARGET_GOAL_ID))
    ):
        raise GateError("FP022 seq67 has an unresolved blocker or question")
    inventory = state.get("dynamic_goal_inventory")
    goal = inventory.get(TARGET_GOAL_ID) if isinstance(inventory, dict) else None
    if (
        not isinstance(goal, dict)
        or goal.get("goal_id") != TARGET_GOAL_ID
        or goal.get("path") != GOAL_RELATIVE.as_posix()
        or goal.get("sha256") != TARGET_GOAL_SHA256
        or goal.get("materialized_event_sha256")
        != materialized.get("event_sha256")
        or goal.get("predecessor_goal_id") != PREDECESSOR_GOAL_ID
    ):
        raise GateError("FP022 dynamic Goal inventory differs")
    children = state.get("materialized_child_goal_ids_by_parent")
    parent_children = (
        children.get(PARENT_GOAL_ID) if isinstance(children, dict) else None
    )
    if (
        not isinstance(parent_children, list)
        or parent_children.count(TARGET_GOAL_ID) != 1
    ):
        raise GateError("FP022 parent/child materialization differs")
    if any(
        isinstance(event, dict)
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        for event in history
    ):
        raise GateError("FP022 has already been started")
    current = checkpoint.get("current_work")
    if (
        not isinstance(current, dict)
        or current.get("work_item_id") != WORK_ITEM_ID
        or current.get("current_focus") != READY_CURRENT_FOCUS
        or current.get("release_completion_claimed") is not False
    ):
        raise GateError("FP022 current-work READY pointer differs")
    if (
        ready.get("implementation_start_gate_contract_binding")
        != R001_CONTRACT_BINDING
    ):
        raise GateError(
            "FP022 seq67 R001 implementation-start contract binding differs"
        )
    return ready_sha256, _parse_timestamp(
        ready.get("occurred_at"),
        label="FP022 seq67 occurred_at",
    )


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
        from scripts import (
            apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814
            as reanchor_authority,
        )

        reanchor_authority.require_control_reanchored_checkpoint(
            root,
            checkpoint,
            run_external_validators=False,
            require_live_snapshot=_REQUIRE_LIVE_SNAPSHOT.get(),
        )
    except reanchor_authority.ControlReanchorError as exc:
        raise GateError(f"FP022 seq68 start-control source differs: {exc}") from exc
    return content, checkpoint


_impl.expected_contract_binding = expected_contract_binding
_impl._load_gate_contract = _load_gate_contract
_impl._load_checkpoint = _load_checkpoint
_impl._document_id = _document_id
_impl._validate_ready_source = _validate_ready_source


def load_gate_context(
    root: Path,
    retained_contents: Any = None,
    *,
    require_live_snapshot: bool = True,
) -> GateContext:
    token = _REQUIRE_LIVE_SNAPSHOT.set(require_live_snapshot)
    try:
        return _impl.load_gate_context(root, retained_contents)
    finally:
        _REQUIRE_LIVE_SNAPSHOT.reset(token)


def run_gate(root: Path, event_id: str, **kwargs: Any) -> Path:
    for path in (
        GRADLE_USER_HOME,
        GRADLE_USER_HOME / "caches",
        GRADLE_USER_HOME / "caches/modules-2",
        GRADLE_USER_HOME / "wrapper/dists",
    ):
        metadata = path.lstat()
        if (
            not stat.S_ISDIR(metadata.st_mode)
            or metadata.st_uid != os.geteuid()
            or metadata.st_mode & stat.S_IWOTH
        ):
            raise GateError(f"FP022 Gradle cache authority differs: {path}")
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
        print(f"FP-022 initial-start gate: FAIL: {exc}", file=sys.stderr)
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        print(
            f"FP-022 initial-start gate: POSTCOMMIT-UNCERTAIN: {exc}",
            file=sys.stderr,
        )
        return 2
    except (GateError, OSError, ValueError) as exc:
        print(f"FP-022 initial-start gate: ERROR: {exc}", file=sys.stderr)
        return 2
    print(f"FP-022 initial-start gate: PASS: {receipt_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
