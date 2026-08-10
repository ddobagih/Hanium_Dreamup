#!/usr/bin/env python3
"""Run the private add-only nine-check FP-046 initial-start gate.

The filesystem and publication hardening is shared byte-for-byte with the
sealed FP-008 gate runner; this module supplies only the FP-046 identities,
contract, runtime bindings, and exact seq52 READY source validation.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

_IMPLEMENTATION_PATH = ROOT / "scripts/run_walksafe_fp008_goal_start_gate_20260803.py"
_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp046_private_gate_runtime_20260809",
    _IMPLEMENTATION_PATH,
)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError("FP008 hardened gate runtime cannot be loaded")
_impl = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = _impl
_SPEC.loader.exec_module(_impl)


CHECKPOINT_RELATIVE = Path("docs/control/walksafe-project-continuation-checkpoint.json")
MANIFEST_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "static-plan-manifest-v2.4.0.json"
)
GATE_ROOT_RELATIVE = Path("docs/control/execution/goal-gates")
ROOT_CONTROL_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp046_goal_seq51_52_20260809.py"
)
CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-046-R001/"
    "initial-start-gate-contract-r001.json"
)
GOAL_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-03/epic-03-fp046-consent-withdrawal-deletion-r001.md"
)
RUNTIME_BINDING_RELATIVES = (
    Path("apps/android/gradle/wrapper/gradle-wrapper.properties"),
    Path("apps/android/gradle/wrapper/gradle-wrapper.jar"),
    Path("apps/android/gradle/verification-metadata.xml"),
    Path("apps/android/app/gradle.lockfile"),
    Path("apps/android-gateway/package-lock.json"),
    Path("configs/walksafe_node_toolchain_lock_20260715.json"),
)
SOURCE_GUARD_RELATIVES = (
    CHECKPOINT_RELATIVE,
    CONTRACT_RELATIVE,
    GOAL_RELATIVE,
    MANIFEST_RELATIVE,
    ROOT_CONTROL_TEST_RELATIVE,
    *RUNTIME_BINDING_RELATIVES,
)

PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
TARGET_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R001"
TARGET_GOAL_SHA256 = (
    "f8f1fc9e9b8c1eaabe543cd64130b5a6dfa3b908b318033cb5969d4a724e8d79"
)
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-008-R001"
WORK_ITEM_ID = "EPIC-03-FP046-CONSENT-WITHDRAWAL-DELETION"
SOURCE_SEQUENCE = 52
MATERIALIZED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-FP046-20260809-001"
)
READY_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP046-20260809-001"
SOURCE_READY_EVENT_SHA256 = (
    "c15744ff17835efe9dcff8a60a515c4c60b4eaf86e480544fca48bd9a49e1c62"
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
CONTRACT_DOCUMENT_ID = "WS-FP046-INITIAL-START-GATE-CONTRACT-20260809-001"
CONTRACT_ID = "WS-FP046-INTERNAL-START-GATE-R001"
CONTRACT_VERSION = "2026-08-09.1"
CONTRACT_FILE_SHA256 = (
    "ec4b3fa6e2657f37770cba949abe68a944640690325d7f78033779f7693fdd7c"
)
CONTRACT_CANONICAL_SHA256 = (
    "7861e9f0e1547f2577520ef5ff9c18349e1f003b02870304cac274a0f88f4d10"
)
EXPECTED_CHECK_IDS = (
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_TEST_DATABASE_PREFLIGHT",
    "BACKEND_REPORT_STORAGE_RETENTION_POSTGRES",
    "ANDROID_USER_INTERNAL",
    "ANDROID_GATEWAY_PRIVACY_INTERNAL",
    "ROOT_FP046_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)
FORBIDDEN_COMMAND_FRAGMENTS = (
    "apps/web",
    "adminapp",
    "connecteddebugandroidtest",
    " adb ",
    "device",
    "external",
    "formal",
    "deploy",
    "release",
)
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-(\d{8})-(\d{3})$"
)


for _name, _value in {
    "CHECKPOINT_RELATIVE": CHECKPOINT_RELATIVE,
    "MANIFEST_RELATIVE": MANIFEST_RELATIVE,
    "GATE_ROOT_RELATIVE": GATE_ROOT_RELATIVE,
    "ROOT_CONTROL_TEST_RELATIVE": ROOT_CONTROL_TEST_RELATIVE,
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
    "MATERIALIZED_EVENT_ID": MATERIALIZED_EVENT_ID,
    "READY_EVENT_ID": READY_EVENT_ID,
    "SOURCE_READY_EVENT_SHA256": SOURCE_READY_EVENT_SHA256,
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


class _Fp046RetainedRepositoryAuthorityGuard(
    _impl.RetainedRepositoryAuthorityGuard
):
    """Keep the gate scratch HOME object sealed while permitting npm state."""

    @classmethod
    def capture(cls, root: Path) -> "_Fp046RetainedRepositoryAuthorityGuard":
        guard = super().capture(root)
        home = guard.workspace / "home"
        matching = [
            (index, entry)
            for index, entry in enumerate(guard.entries)
            if entry.path == home
        ]
        if len(matching) != 1:
            primary = _impl.GateError(
                "FP046 gate scratch HOME authority differs"
            )
            guard.close(primary)
            raise primary
        index, sealed_inventory = matching[0]
        replacement = _impl._RetainedImmutableNamespaceEntry.capture(
            home,
            seal_directory_inventory=False,
        )
        guard.entries[index] = replacement
        try:
            sealed_inventory.close()
            guard.verify()
        except BaseException as exc:
            guard.close(exc)
            raise
        return guard


_impl.RetainedRepositoryAuthorityGuard = (
    _Fp046RetainedRepositoryAuthorityGuard
)


GateError = _impl.GateError
GateCheckFailed = _impl.GateCheckFailed
GatePostCommitUncertain = _impl.GatePostCommitUncertain
GateContext = _impl.GateContext
RECEIPT_FIELDS = _impl.RECEIPT_FIELDS
RECEIPT_NAME = _impl.RECEIPT_NAME
canonical_sha256 = _impl.canonical_sha256
event_sha256 = _impl.event_sha256
expected_contract_binding = _impl.expected_contract_binding
repository_snapshot_from_payload = _impl.repository_snapshot_from_payload
capture_repository_state = _impl.capture_repository_state
_load_gate_contract = _impl._load_gate_contract
_private_file_bytes = _impl._private_file_bytes


def _document_id(event_id: str) -> str:
    match = EVENT_ID_RE.fullmatch(event_id)
    if match is None:
        raise GateError(
            "event ID must match WS-GOAL-GRAPH-V2-4-GOAL-STARTED-"
            "FP046-YYYYMMDD-NNN"
        )
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP046-"
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
) -> tuple[str, datetime]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if not isinstance(state, dict) or not isinstance(history, list):
        raise GateError("checkpoint goal execution is missing")
    if len(history) != SOURCE_SEQUENCE:
        raise GateError("FP046 gate requires the exact seq52 source")
    materialized = history[-2]
    ready = history[-1]
    if not isinstance(materialized, dict) or not isinstance(ready, dict):
        raise GateError("FP046 seq51/52 events are missing")
    if (
        materialized.get("sequence") != 51
        or materialized.get("event_id") != MATERIALIZED_EVENT_ID
        or materialized.get("event_type") != "GOAL_MATERIALIZED"
        or materialized.get("materialized_goal_id") != TARGET_GOAL_ID
        or materialized.get("to_status") != "PLANNED"
        or materialized.get("event_sha256") != event_sha256(materialized)
    ):
        raise GateError("FP046 seq51 materialization event differs")
    if (
        ready.get("sequence") != SOURCE_SEQUENCE
        or ready.get("event_id") != READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != TARGET_GOAL_ID
        or ready.get("focus_goal_id") != TARGET_GOAL_ID
        or ready.get("focus_goal_content_sha256") != TARGET_GOAL_SHA256
        or ready.get("from_status") != "PLANNED"
        or ready.get("to_status") != "READY"
        or ready.get("previous_event_sha256") != materialized.get("event_sha256")
        or ready.get("event_sha256") != event_sha256(ready)
    ):
        raise GateError("FP046 seq52 readiness event differs")
    ready_sha256 = ready["event_sha256"]
    if SOURCE_READY_EVENT_SHA256 and ready_sha256 != SOURCE_READY_EVENT_SHA256:
        raise GateError("FP046 seq52 READY event SHA-256 trust anchor differs")
    if state.get("transition_history_anchor_sha256") != ready_sha256:
        raise GateError("FP046 seq52 history anchor differs")
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
        raise GateError("FP046 seq52 active focus differs")
    statuses = state.get("status_by_goal")
    if (
        not isinstance(statuses, dict)
        or statuses.get(TARGET_GOAL_ID) != "READY"
        or statuses.get(PREDECESSOR_GOAL_ID) != "COMPLETE_AT_TARGET"
        or "IN_PROGRESS" in statuses.values()
    ):
        raise GateError("FP046 seq52 Goal statuses differ")
    blockers = state.get("blockers_by_goal")
    if (
        state.get("blocked_goal_ids") != []
        or state.get("pending_questions") != []
        or state.get("open_question_count") != 0
        or (isinstance(blockers, dict) and blockers.get(TARGET_GOAL_ID))
    ):
        raise GateError("FP046 seq52 has an unresolved blocker or question")
    inventory = state.get("dynamic_goal_inventory")
    goal = inventory.get(TARGET_GOAL_ID) if isinstance(inventory, dict) else None
    if (
        not isinstance(goal, dict)
        or goal.get("goal_id") != TARGET_GOAL_ID
        or goal.get("path") != GOAL_RELATIVE.as_posix()
        or goal.get("sha256") != TARGET_GOAL_SHA256
        or goal.get("materialized_event_sha256") != materialized.get("event_sha256")
        or goal.get("predecessor_goal_id") != PREDECESSOR_GOAL_ID
    ):
        raise GateError("FP046 dynamic Goal inventory differs")
    children = state.get("materialized_child_goal_ids_by_parent")
    parent_children = children.get(PARENT_GOAL_ID) if isinstance(children, dict) else None
    if not isinstance(parent_children, list) or parent_children.count(TARGET_GOAL_ID) != 1:
        raise GateError("FP046 parent/child materialization differs")
    if any(
        isinstance(event, dict)
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        for event in history
    ):
        raise GateError("FP046 has already been started")
    current = checkpoint.get("current_work")
    if (
        not isinstance(current, dict)
        or current.get("work_item_id") != WORK_ITEM_ID
        or current.get("current_focus")
        != "FP046/GAP-055 Goal READY; active internal start gate not run"
        or current.get("release_completion_claimed") is not False
    ):
        raise GateError("FP046 current-work READY pointer differs")
    if ready.get("implementation_start_gate_contract_binding") != contract_binding:
        raise GateError("FP046 seq52 implementation-start contract binding differs")
    return ready_sha256, _parse_timestamp(
        ready.get("occurred_at"),
        label="FP046 seq52 occurred_at",
    )


_impl._document_id = _document_id
_impl._validate_ready_source = _validate_ready_source


def load_gate_context(root: Path) -> GateContext:
    return _impl.load_gate_context(root)


def run_gate(
    root: Path,
    event_id: str,
    **kwargs: Any,
) -> Path:
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
        print(f"FP-046 initial-start gate: FAIL: {exc}", file=sys.stderr)
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        print(
            f"FP-046 initial-start gate: POSTCOMMIT-UNCERTAIN: {exc}",
            file=sys.stderr,
        )
        return 2
    except (GateError, OSError, ValueError) as exc:
        print(f"FP-046 initial-start gate: ERROR: {exc}", file=sys.stderr)
        return 2
    print(f"FP-046 initial-start gate: PASS: {receipt_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
