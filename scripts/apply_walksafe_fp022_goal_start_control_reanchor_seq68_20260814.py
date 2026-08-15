#!/usr/bin/env python3
"""Seal FP-022 start controls with a zero-credit seq68 reanchor.

The command is read-only in ``--preflight`` mode.  Catalog refresh and the
checkpoint CAS publication are separate explicit operations.
"""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime
import os
from pathlib import Path
import re
import stat
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp022_goal_seq66_67_20260813 as fp022
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import generate_repository_catalogs as catalogs


npc = fp022.npc
aggregate = fp022.aggregate
bytes_sha256 = npc.bytes_sha256
CHECKPOINT_REL = fp022.CHECKPOINT_REL
CATALOG_PATHS = fp022.CATALOG_PATHS

SOURCE_SHA256 = "87990596835e0ed3dad48c4a974fb2521a28d9dfdfe076a1ec10596306179354"
SOURCE_BYTE_COUNT = 1_994_168
SOURCE_SEQUENCE = 67
SOURCE_TAIL_SHA256 = "37207b4393dd5820de6b71c7e167885f8592875f8b54d9d75d650aef230a87a2"
SOURCE_CUTOFF_AT = "2026-08-13T19:48:42+09:00"
SOURCE_BRANCH = "current"
SOURCE_BASE_COMMIT = "f0093863e82bfc80d9f11915cef33a51d44b8730"
SOURCE_HEAD_COMMIT = "ca0898d56eaa45b947b9f513a2bcdbfcb5bc5a0c"
SOURCE_MANAGED_PATH_COUNT = 819
SOURCE_PATH_SET_SHA256 = "53d24c9b9e68ee520ed50195bc1fcfa842d737a760e7304c227f62b1153d7e02"
SOURCE_CONTENT_SET_SHA256 = "b50334b78981de9788bb7691da7d7e598609d31f8fdadfb5f952932aa823be24"

SEQUENCE = 68
EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP022-20260814-001"
)
EVENT_TYPE = "GOAL_START_CONTROL_REANCHORED"
OCCURRED_AT = "2026-08-14T01:54:30+09:00"
GOAL_ID = fp022.GOAL_ID
GOAL_PATH = fp022.GOAL_PATH
GOAL_SHA256 = fp022.GOAL_SHA256
READY_EVENT_ID = fp022.READY_EVENT_ID
READY_FRONTIER = fp022.READY_FRONTIER
MANIFEST_SHA256 = fp022.MANIFEST_SHA256

R001_CONTRACT_PATH = fp022.CONTRACT_PATH
R002_CONTRACT_PATH = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-04-FP-022-R001/"
    "initial-start-gate-contract-r002.json"
)
R002_DOCUMENT_ID = "WS-FP022-INITIAL-START-GATE-CONTRACT-20260814-002"
R002_CONTRACT_ID = "WS-FP022-INTERNAL-START-GATE-R002"
R002_CONTRACT_VERSION = "2026-08-14.1"
R002_FILE_SHA256 = "8e7f55dffcc0725fcb831118ed553b79ed09a41c0d323d00da343fa26ab01b42"
R002_BYTE_LENGTH = 4_561
R002_CANONICAL_SHA256 = "bc382fc5095cfa0eef246447f961d268ff3953274ba1df84360a1a5bb4663e7c"
SUCCESSOR_REASON_CODE = (
    "SEQ67_READY_TRUST_ANCHOR_AND_CURRENT_CONTROL_COHORT_REQUIRED"
)
START_GATE_RUNNER_PATH = Path(
    "scripts/run_walksafe_fp022_goal_start_gate_20260813.py"
)
SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814.py"
)

# These paths are the minimum live successor cohort.  Review evidence paths
# are added through transition_review_binding and the full Git-visible closure
# is still captured by the snapshot projector.
REQUIRED_CONTROL_PATHS = (
    Path("docs/catalogs/repository-paths.json"),
    Path("docs/catalogs/scripts.json"),
    Path("docs/catalogs/tests.json"),
    R002_CONTRACT_PATH,
    SCRIPT_REL,
    START_GATE_RUNNER_PATH,
    Path("scripts/apply_walksafe_fp022_goal_started_seq69_20260814.py"),
    Path("scripts/build_walksafe_fp022_seq68_69_review_20260814.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    TEST_REL,
    Path("tests/test_apply_walksafe_fp022_goal_started_seq69_20260814.py"),
    Path("tests/test_build_walksafe_fp022_seq68_69_review_20260814.py"),
    Path("tests/test_repository_catalogs.py"),
    Path("tests/test_walksafe_fp022_goal_start_gate_20260813.py"),
)

CLAIM_BOUNDARY = {
    "implementation_start_authorized": False,
    "goal_status_change_count": 0,
    "product_implementation_credit_delta": 0,
    "artifact_completion_credit_delta": 0,
    "test_credit_delta": 0,
    "formal_test_credit_delta": 0,
    "approval_credit_delta": 0,
    "actual_event_credit_delta": 0,
    "external_action_credit_delta": 0,
    "actual_device_credit_delta": 0,
    "deployment_credit_delta": 0,
    "signing_credit_delta": 0,
    "release_credit_delta": 0,
    "final_completion_credit_delta": 0,
    "formal_test_not_run_count": 279,
    "remaining_gate_count": 5,
    "remaining_gates_waived": False,
    "release_status": "NOT_ELIGIBLE",
}

SOURCE_UNCHANGED_CONTROL_SHA256 = {
    "artifact": "7a1d083a21bc1018cb2a1ee2e4ba715d1b279042c3d54398802ee6a9093d509b",
    "canonical": "e57e28246d57e48c61247d5648073e560d7efa181d2f9bd005d68935d3201344",
    "completion": "c157dab589288e0c4bea50b127ef10a65422142b4fd0466392693d731056b82c",
    "current_work": "93ef3861a5c1059ad508f6d947213a3e9f551e455feee43d8eb815d9728a1aa9",
    "runtime": "e4e2979c50937bc9ab6db40e330529450f047388bff3f4d5581eb0d9c9fe80dc",
    "status": "c85728f18479c976e898ed447469cdf783627dabfd635e5341aa6b8c466b9a92",
    "verification": "406f4ec4b66dd3672e1fc2246c5ec2ce4c1ea6fa388549e6dabb52e49f2ac11c",
}

EVENT_FIELDS = {
    "sequence",
    "event_id",
    "event_type",
    "occurred_on",
    "occurred_at",
    "previous_focus_goal_id",
    "previous_focus_content_sha256",
    "focus_goal_id",
    "focus_goal_content_sha256",
    "subject_goal_id",
    "from_status",
    "to_status",
    "static_plan_manifest_sha256",
    "status_changes",
    "runtime_after",
    "blockers_after",
    "blocker_resolution_ids_after",
    "source_checkpoint_version",
    "evidence_refs",
    "source_checkpoint_binding",
    "source_ready_event_binding",
    "contract_supersession",
    "start_gate_runner_binding",
    "transition_control_review_binding",
    "repository_context_reanchor",
    "claim_boundary",
    "unchanged_control_projection",
    "canonical_binding_snapshot_after",
    "previous_event_sha256",
    "event_sha256",
}

FINAL_SCOPE = (
    "Graph v2.4 FP-022/GAP-031 READY through zero-credit start-control "
    "reanchor seq68; R002 seals the seq67 READY trust anchor and current "
    "control cohort. No GOAL_STARTED, implementation, artifact, test, "
    "formal, device, external, deployment, signing, or release credit."
)

CONTROL_REANCHOR_SEQUENCE = SEQUENCE
CONTROL_REANCHOR_EVENT_ID = EVENT_ID


class ControlReanchorError(RuntimeError):
    """The exact FP-022 seq68 control projection cannot be proven."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ControlReanchorError(message)


@dataclass(frozen=True)
class Prepared:
    root: Path
    checkpoint_path: Path
    source_bytes: bytes
    projected: dict[str, Any]
    projected_bytes: bytes
    final_sha256_by_path: Mapping[Path, str]
    physical_sha256_by_path: Mapping[Path, str]
    source_universe: tuple[str, ...]
    candidate_catalogs: Mapping[Path, bytes]
    catalogs_verified: bool
    projected_validator: Callable[[Path, bytes], None]


def _binding(path: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
    }


def source_checkpoint_binding() -> dict[str, Any]:
    return {
        "path": CHECKPOINT_REL.as_posix(),
        "sequence": SOURCE_SEQUENCE,
        "sha256": SOURCE_SHA256,
        "byte_length": SOURCE_BYTE_COUNT,
        "tail_event_sha256": SOURCE_TAIL_SHA256,
    }


def source_ready_event_binding() -> dict[str, Any]:
    return {
        "sequence": SOURCE_SEQUENCE,
        "event_id": READY_EVENT_ID,
        "event_sha256": SOURCE_TAIL_SHA256,
        "goal_id": GOAL_ID,
        "status": "READY",
    }


def r001_contract_binding() -> dict[str, str]:
    return {
        "document_id": fp022.CONTRACT_DOCUMENT_ID,
        "contract_id": fp022.CONTRACT_ID,
        "contract_version": fp022.CONTRACT_VERSION,
        "path": fp022.CONTRACT_PATH.as_posix(),
        "file_sha256": fp022.CONTRACT_FILE_SHA256,
        "canonical_sha256": fp022.CONTRACT_CANONICAL_SHA256,
    }


def _load_r002_contract(root: Path) -> tuple[dict[str, Any], dict[str, str]]:
    raw = npc._read_safe_bytes(root, R002_CONTRACT_PATH)
    document = npc.trace.strict_json_bytes(raw, R002_CONTRACT_PATH.as_posix())
    expected_supersedes = {
        **r001_contract_binding(),
        "source_ready_event_sequence": SOURCE_SEQUENCE,
        "source_ready_event_id": READY_EVENT_ID,
        "source_ready_event_sha256": SOURCE_TAIL_SHA256,
    }
    checks = document.get("ordered_checks")
    require(
        len(raw) == R002_BYTE_LENGTH
        and bytes_sha256(raw) == R002_FILE_SHA256
        and continuation.canonical_json_sha256(document) == R002_CANONICAL_SHA256
        and document.get("schema_version") == "1.1"
        and document.get("document_id") == R002_DOCUMENT_ID
        and document.get("contract_id") == R002_CONTRACT_ID
        and document.get("contract_version") == R002_CONTRACT_VERSION
        and document.get("target_goal_id") == GOAL_ID
        and document.get("target_goal_content_sha256") == GOAL_SHA256
        and document.get("gate_purpose") == "INITIAL_START"
        and document.get("successor_reason_code") == SUCCESSOR_REASON_CODE
        and document.get("supersedes") == expected_supersedes
        and type(checks) is list
        and tuple(row.get("check_id") for row in checks if type(row) is dict)
        == fp022.CONTRACT_CHECK_IDS,
        "FP-022 R002 start-gate contract differs",
    )
    binding = {
        "schema_version": "1.1",
        "document_id": R002_DOCUMENT_ID,
        "path": R002_CONTRACT_PATH.as_posix(),
        "file_sha256": R002_FILE_SHA256,
        "contract_id": R002_CONTRACT_ID,
        "contract_version": R002_CONTRACT_VERSION,
        "canonical_contract_sha256": R002_CANONICAL_SHA256,
    }
    return document, binding


def transition_review_binding(root: Path) -> dict[str, dict[str, Any]]:
    from scripts import build_walksafe_fp022_seq68_69_review_20260814 as review

    return review.transition_review_binding(root)


def start_gate_runner_binding(root: Path) -> dict[str, Any]:
    return _binding(
        START_GATE_RUNNER_PATH,
        npc._read_safe_bytes(root, START_GATE_RUNNER_PATH),
    )


def _runtime_projection(state: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "focus_goal_id": state.get("focus_goal_id"),
        "focus_goal_path": state.get("focus_goal_path"),
        "focus_work_item_id": state.get("focus_work_item_id"),
        "focus_source": state.get("focus_source"),
        "ready_frontier_goal_ids": copy.deepcopy(state.get("ready_frontier_goal_ids")),
        "blocked_goal_ids": copy.deepcopy(state.get("blocked_goal_ids")),
        "pending_questions": copy.deepcopy(state.get("pending_questions")),
        "open_question_count": state.get("open_question_count"),
        "artifact_work_queue_sha256": continuation.canonical_json_sha256(
            state.get("artifact_work_queue")
        ),
        "completion_boundary_sha256": continuation.canonical_json_sha256(
            state.get("completion_boundary")
        ),
        "activation_status": state.get("activation_status"),
        "package_status": state.get("package_status"),
    }


def _control_projection_values(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    require(type(state) is dict, "goal_execution is missing")
    return {
        "artifact": {
            "artifact_work_queue": copy.deepcopy(state.get("artifact_work_queue")),
            "artifact_state_counts": copy.deepcopy(
                checkpoint.get("approved_state", {}).get("artifact_state_counts")
            ),
        },
        "canonical": copy.deepcopy(checkpoint.get("canonical_bindings")),
        "completion": {
            "completion_boundary": copy.deepcopy(state.get("completion_boundary")),
            "completion_evidence_by_goal": copy.deepcopy(
                state.get("completion_evidence_by_goal")
            ),
            "archived_completion_evidence_by_goal": copy.deepcopy(
                state.get("archived_completion_evidence_by_goal")
            ),
            "pending_reopen_goal_ids": copy.deepcopy(
                state.get("pending_reopen_goal_ids")
            ),
            "pending_producer_completion_goal_id": state.get(
                "pending_producer_completion_goal_id"
            ),
        },
        "current_work": copy.deepcopy(checkpoint.get("current_work")),
        "runtime": {
            "runtime": _runtime_projection(state),
            "blockers_by_goal": copy.deepcopy(state.get("blockers_by_goal")),
            "blocker_resolution_history": copy.deepcopy(
                state.get("blocker_resolution_history")
            ),
            "dynamic_goal_inventory": copy.deepcopy(
                state.get("dynamic_goal_inventory")
            ),
            "materialized_child_goal_ids_by_parent": copy.deepcopy(
                state.get("materialized_child_goal_ids_by_parent")
            ),
        },
        "status": {
            "goal_status": state.get("goal_status"),
            "status_by_goal": copy.deepcopy(state.get("status_by_goal")),
        },
        "verification": {
            "approved_state": copy.deepcopy(checkpoint.get("approved_state")),
            "verification_boundary": copy.deepcopy(
                checkpoint.get("verification_boundary")
            ),
        },
    }


def control_projection_hashes(checkpoint: Mapping[str, Any]) -> dict[str, str]:
    return {
        name: continuation.canonical_json_sha256(value)
        for name, value in _control_projection_values(checkpoint).items()
    }


def require_source(raw: bytes, source: Mapping[str, Any]) -> None:
    require(
        len(raw) == SOURCE_BYTE_COUNT and bytes_sha256(raw) == SOURCE_SHA256,
        "checkpoint physical binding differs from exact seq67",
    )
    require(source.get("schema_version") == "1.25.0", "source schema differs")
    state = source.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(
        type(history) is list and len(history) == SOURCE_SEQUENCE,
        "source history is not exact seq67",
    )
    previous = ""
    for sequence, event in enumerate(history, start=1):
        require(
            type(event) is dict
            and event.get("sequence") == sequence
            and event.get("previous_event_sha256") == previous
            and event.get("event_sha256") == continuation.event_sha256(event),
            f"source event {sequence} chain differs",
        )
        previous = event["event_sha256"]
    ready = history[-1]
    require(
        ready.get("event_id") == READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == GOAL_ID
        and ready.get("from_status") == "PLANNED"
        and ready.get("to_status") == "READY"
        and ready.get("event_sha256") == SOURCE_TAIL_SHA256
        and ready.get("implementation_start_gate_contract_binding")
        == fp022.contract_binding()
        and state.get("transition_history_anchor_sha256") == SOURCE_TAIL_SHA256
        and state.get("validation_cutoff_at") == SOURCE_CUTOFF_AT,
        "source seq67 READY tail differs",
    )
    require(
        state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH.as_posix()
        and state.get("focus_work_item_id") == GOAL_ID
        and state.get("focus_source") == "IMPLEMENTATION_BACKLOG"
        and state.get("ready_frontier_goal_ids") == READY_FRONTIER
        and state.get("blockers_by_goal") == {}
        and state.get("pending_questions") == [],
        "source seq67 runtime differs",
    )
    require(
        control_projection_hashes(source) == SOURCE_UNCHANGED_CONTROL_SHA256,
        "source seq67 zero-credit projection differs",
    )
    require(
        _source_repository_context(source) == expected_source_repository_context(),
        "source seq67 repository context differs",
    )


def _source_repository_context(source: Mapping[str, Any]) -> dict[str, Any]:
    snapshot = source["working_tree_snapshot"]
    mirror = source["session_handoff"]["source_commit_or_snapshot"]
    return {
        **source_checkpoint_binding(),
        "branch": source["repository"]["branch"],
        "base_commit": mirror["base_commit"],
        "current_head": mirror["current_head"],
        "managed_changed_path_count": snapshot["managed_changed_path_count"],
        "path_set_sha256": snapshot["path_set_sha256"],
        "content_set_sha256": snapshot["content_set_sha256"],
    }


def expected_source_repository_context() -> dict[str, Any]:
    return {
        **source_checkpoint_binding(),
        "branch": SOURCE_BRANCH,
        "base_commit": SOURCE_BASE_COMMIT,
        "current_head": SOURCE_HEAD_COMMIT,
        "managed_changed_path_count": SOURCE_MANAGED_PATH_COUNT,
        "path_set_sha256": SOURCE_PATH_SET_SHA256,
        "content_set_sha256": SOURCE_CONTENT_SET_SHA256,
    }


def _after_repository_context(
    source: Mapping[str, Any], final_sha256_by_path: Mapping[Path, str]
) -> dict[str, Any]:
    path_hash, content_hash = npc._snapshot_hashes_from_digests(final_sha256_by_path)
    mirror = source["session_handoff"]["source_commit_or_snapshot"]
    return {
        "branch": source["repository"]["branch"],
        "base_commit": mirror["base_commit"],
        "current_head": mirror["current_head"],
        "managed_changed_path_count": len(final_sha256_by_path),
        "path_set_sha256": path_hash,
        "content_set_sha256": content_hash,
    }


def project(
    source: Mapping[str, Any],
    final_sha256_by_path: Mapping[Path, str],
    *,
    successor_contract: Mapping[str, Any],
    runner_binding: Mapping[str, Any],
    transition_review: Mapping[str, Mapping[str, Any]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    source_raw = npc.trace.json_text(dict(source)).encode()
    require_source(source_raw, source)
    require(
        set(REQUIRED_CONTROL_PATHS).issubset(final_sha256_by_path),
        "seq68 successor control cohort is incomplete",
    )
    checkpoint = copy.deepcopy(dict(source))
    state = checkpoint["goal_execution"]
    ready = state["transition_history"][-1]
    before_context = _source_repository_context(source)
    after_context = _after_repository_context(source, final_sha256_by_path)
    event: dict[str, Any] = {
        "sequence": SEQUENCE,
        "event_id": EVENT_ID,
        "event_type": EVENT_TYPE,
        "occurred_on": datetime.fromisoformat(OCCURRED_AT).date().isoformat(),
        "occurred_at": OCCURRED_AT,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "subject_goal_id": GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": copy.deepcopy(ready["runtime_after"]),
        "blockers_after": copy.deepcopy(ready["blockers_after"]),
        "blocker_resolution_ids_after": copy.deepcopy(
            ready["blocker_resolution_ids_after"]
        ),
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [
            "FP022_INITIAL_START_GATE_CONTRACT_SUCCESSOR",
            "FP022_START_GATE_RUNNER_SEAL",
            "FP022_SEQ68_69_TRANSITION_CONTROL_REVIEW",
        ],
        "source_checkpoint_binding": source_checkpoint_binding(),
        "source_ready_event_binding": source_ready_event_binding(),
        "contract_supersession": {
            "previous_contract_binding": r001_contract_binding(),
            "replacement_contract_binding": copy.deepcopy(dict(successor_contract)),
            "reason_code": SUCCESSOR_REASON_CODE,
        },
        "start_gate_runner_binding": copy.deepcopy(dict(runner_binding)),
        "transition_control_review_binding": copy.deepcopy(
            {key: dict(value) for key, value in transition_review.items()}
        ),
        "repository_context_reanchor": {
            "before": before_context,
            "after": after_context,
        },
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "unchanged_control_projection": {
            name: {"before_sha256": digest, "after_sha256": digest}
            for name, digest in sorted(SOURCE_UNCHANGED_CONTROL_SHA256.items())
        },
        "canonical_binding_snapshot_after": fp022._binding_map(source),
        "previous_event_sha256": SOURCE_TAIL_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq68 event field set differs")
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = OCCURRED_AT
    aggregate._apply_snapshot(checkpoint, final_sha256_by_path)
    checkpoint["working_tree_snapshot"]["scope"] = FINAL_SCOPE
    return checkpoint, event


def validate_projection(
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    event: Mapping[str, Any],
    *,
    successor_contract: Mapping[str, Any],
    runner_binding: Mapping[str, Any],
    transition_review: Mapping[str, Mapping[str, Any]],
) -> None:
    before = source["goal_execution"]
    after = projected["goal_execution"]
    allowed_top = {"goal_execution", "working_tree_snapshot", "session_handoff"}
    require(
        set(projected) == set(source)
        and all(projected[key] == source[key] for key in source if key not in allowed_top),
        "seq68 changed unrelated top-level state",
    )
    allowed_state = {
        "transition_history",
        "transition_history_anchor_sha256",
        "validation_cutoff_at",
    }
    require(
        set(after) == set(before)
        and all(after[key] == before[key] for key in before if key not in allowed_state),
        "seq68 changed non-history Goal state",
    )
    require(
        after["transition_history"][:-1] == before["transition_history"]
        and after["transition_history"][-1] == event
        and len(after["transition_history"]) == SEQUENCE,
        "seq68 history is not add-only",
    )
    require(
        set(event) == EVENT_FIELDS
        and event.get("sequence") == SEQUENCE
        and event.get("event_id") == EVENT_ID
        and event.get("event_type") == EVENT_TYPE
        and event.get("occurred_at") == OCCURRED_AT
        and event.get("subject_goal_id") == GOAL_ID
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("previous_event_sha256") == SOURCE_TAIL_SHA256
        and event.get("event_sha256") == continuation.event_sha256(dict(event))
        and event.get("source_checkpoint_binding") == source_checkpoint_binding()
        and event.get("source_ready_event_binding") == source_ready_event_binding(),
        "seq68 event identity differs",
    )
    require(
        event.get("contract_supersession")
        == {
            "previous_contract_binding": r001_contract_binding(),
            "replacement_contract_binding": dict(successor_contract),
            "reason_code": SUCCESSOR_REASON_CODE,
        }
        and event.get("start_gate_runner_binding") == dict(runner_binding)
        and event.get("transition_control_review_binding")
        == {key: dict(value) for key, value in transition_review.items()},
        "seq68 successor control binding differs",
    )
    require(
        event.get("claim_boundary") == CLAIM_BOUNDARY
        and control_projection_hashes(projected) == SOURCE_UNCHANGED_CONTROL_SHA256
        and after["status_by_goal"].get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in after["status_by_goal"].values()
        and projected["current_work"] == source["current_work"]
        and projected["verification_boundary"] == source["verification_boundary"]
        and projected["authority_boundary"] == source["authority_boundary"]
        and projected["approved_state"] == source["approved_state"],
        "seq68 gained status, implementation, verification, or release credit",
    )
    snapshot = projected["working_tree_snapshot"]
    paths = snapshot["managed_changed_paths"]
    require(
        paths == sorted(set(paths))
        and set(path.as_posix() for path in REQUIRED_CONTROL_PATHS).issubset(paths)
        and snapshot["scope"] == FINAL_SCOPE
        and projected["session_handoff"]["changed_files"] == paths,
        "seq68 managed successor closure differs",
    )
    require(
        after["transition_history_anchor_sha256"] == event["event_sha256"]
        and after["validation_cutoff_at"] == OCCURRED_AT,
        "seq68 tail authority differs",
    )


def require_control_reanchored_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    run_external_validators: bool = True,
    require_live_snapshot: bool = True,
) -> None:
    """Validate an exact seq68 document, including after seq68 is published."""
    root = root.resolve(strict=True)
    _, successor = _load_r002_contract(root)
    runner = start_gate_runner_binding(root)
    review = transition_review_binding(root)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(
        type(history) is list and len(history) == SEQUENCE and type(history[-1]) is dict,
        "checkpoint is not exact seq68",
    )
    previous = ""
    for sequence, item in enumerate(history, start=1):
        require(
            type(item) is dict
            and item.get("sequence") == sequence
            and item.get("previous_event_sha256") == previous
            and item.get("event_sha256") == continuation.event_sha256(item),
            f"seq68 source event {sequence} chain differs",
        )
        previous = item["event_sha256"]
    ready = history[SOURCE_SEQUENCE - 1]
    event = history[-1]
    require(
        ready.get("event_id") == READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == GOAL_ID
        and ready.get("event_sha256") == SOURCE_TAIL_SHA256
        and ready.get("implementation_start_gate_contract_binding")
        == fp022.contract_binding(),
        "seq68 exact seq67 READY source differs",
    )
    require(
        set(event) == EVENT_FIELDS
        and event.get("sequence") == SEQUENCE
        and event.get("event_id") == EVENT_ID
        and event.get("event_type") == EVENT_TYPE
        and event.get("occurred_on") == "2026-08-14"
        and event.get("occurred_at") == OCCURRED_AT
        and event.get("previous_focus_goal_id") == GOAL_ID
        and event.get("previous_focus_content_sha256") == GOAL_SHA256
        and event.get("focus_goal_id") == GOAL_ID
        and event.get("focus_goal_content_sha256") == GOAL_SHA256
        and event.get("subject_goal_id") == GOAL_ID
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("source_checkpoint_binding") == source_checkpoint_binding()
        and event.get("source_ready_event_binding") == source_ready_event_binding()
        and event.get("previous_event_sha256") == SOURCE_TAIL_SHA256,
        "seq68 event identity or source binding differs",
    )
    require(
        event.get("contract_supersession")
        == {
            "previous_contract_binding": r001_contract_binding(),
            "replacement_contract_binding": successor,
            "reason_code": SUCCESSOR_REASON_CODE,
        }
        and event.get("start_gate_runner_binding") == runner
        and event.get("transition_control_review_binding") == review
        and event.get("claim_boundary") == CLAIM_BOUNDARY,
        "seq68 live successor authority differs",
    )
    require(
        event.get("runtime_after") == _runtime_projection(state)
        and event.get("blockers_after") == state.get("blockers_by_goal")
        and event.get("blocker_resolution_ids_after")
        == [
            item["resolution_id"]
            for item in state.get("blocker_resolution_history", [])
        ]
        and event.get("canonical_binding_snapshot_after")
        == fp022._binding_map(checkpoint),
        "seq68 runtime or canonical projection differs",
    )
    expected_unchanged = {
        name: {"before_sha256": digest, "after_sha256": digest}
        for name, digest in sorted(SOURCE_UNCHANGED_CONTROL_SHA256.items())
    }
    require(
        event.get("unchanged_control_projection") == expected_unchanged
        and control_projection_hashes(checkpoint) == SOURCE_UNCHANGED_CONTROL_SHA256,
        "seq68 zero-credit control projection differs",
    )
    require(
        state.get("transition_history_anchor_sha256") == event.get("event_sha256")
        and state.get("validation_cutoff_at") == OCCURRED_AT
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH.as_posix()
        and state.get("focus_work_item_id") == GOAL_ID
        and state.get("ready_frontier_goal_ids") == READY_FRONTIER,
        "seq68 READY state or tail differs",
    )
    paths = checkpoint["working_tree_snapshot"]["managed_changed_paths"]
    snapshot = checkpoint["working_tree_snapshot"]
    mirror = checkpoint["session_handoff"]["source_commit_or_snapshot"]
    repository_after = {
        "branch": checkpoint["repository"]["branch"],
        "base_commit": mirror["base_commit"],
        "current_head": mirror["current_head"],
        "managed_changed_path_count": snapshot["managed_changed_path_count"],
        "path_set_sha256": snapshot["path_set_sha256"],
        "content_set_sha256": snapshot["content_set_sha256"],
    }
    require(
        event.get("repository_context_reanchor")
        == {
            "before": expected_source_repository_context(),
            "after": repository_after,
        }
        and paths == sorted(set(paths))
        and set(path.as_posix() for path in REQUIRED_CONTROL_PATHS).issubset(paths)
        and snapshot.get("scope") == FINAL_SCOPE
        and checkpoint["session_handoff"].get("changed_files") == paths,
        "seq68 repository projection differs",
    )
    if require_live_snapshot:
        observed = continuation.working_snapshot_hashes(root, paths)
        require(
            observed
            == (
                checkpoint["working_tree_snapshot"]["path_set_sha256"],
                checkpoint["working_tree_snapshot"]["content_set_sha256"],
            ),
            "seq68 live managed snapshot differs",
        )
    if run_external_validators:
        npc.validate_projected_with_both_checkers(
            root, npc.trace.json_text(dict(checkpoint)).encode()
        )


def prepare(
    root: Path = ROOT,
    *,
    allow_stale_catalogs: bool = False,
    projected_validator: Callable[[Path, bytes], None] | None = None,
) -> Prepared:
    root = root.resolve(strict=True)
    validator = projected_validator or npc.validate_projected_with_both_checkers
    checkpoint_path = npc._safe_file(root, CHECKPOINT_REL)
    metadata = checkpoint_path.stat()
    require(
        stat.S_IMODE(metadata.st_mode) == 0o600 and metadata.st_nlink == 1,
        "checkpoint publication identity differs",
    )
    source_bytes = checkpoint_path.read_bytes()
    source = npc.trace.strict_json_bytes(source_bytes, CHECKPOINT_REL.as_posix())
    require_source(source_bytes, source)
    _, successor_contract = _load_r002_contract(root)
    runner = start_gate_runner_binding(root)
    review = transition_review_binding(root)
    universe = catalogs.discover_source_paths(root)
    physical = aggregate._snapshot_digests(root, source)
    draft, _ = project(
        source,
        physical,
        successor_contract=successor_contract,
        runner_binding=runner,
        transition_review=review,
    )
    candidate = npc._build_candidate_catalog_bytes(root, universe, draft)
    final = dict(physical)
    final.update({path: bytes_sha256(raw) for path, raw in candidate.items()})
    projected, event = project(
        source,
        final,
        successor_contract=successor_contract,
        runner_binding=runner,
        transition_review=review,
    )
    require(
        npc._build_candidate_catalog_bytes(root, universe, projected) == candidate,
        "seq68 catalog/checkpoint projection did not reach a fixed point",
    )
    validate_projection(
        source,
        projected,
        event,
        successor_contract=successor_contract,
        runner_binding=runner,
        transition_review=review,
    )
    projected_bytes = npc.trace.json_text(projected).encode()

    if not allow_stale_catalogs:
        def revalidate_closure() -> None:
            require(
                checkpoint_path.read_bytes() == source_bytes,
                "source changed during seq68 preflight",
            )
            require(
                catalogs.discover_source_paths(root) == universe,
                "catalog source universe changed during seq68 preflight",
            )
            npc._require_candidate_catalogs_exact(
                root, universe, candidate, checkpoint=projected
            )
            npc._require_physical_matches(root, physical)
            require(
                continuation.working_snapshot_hashes(
                    root, sorted(path.as_posix() for path in final)
                )
                == npc._snapshot_hashes_from_digests(final),
                "live seq68 managed snapshot differs",
            )
            npc._require_git_visible_changes_are_managed(root, final)

        revalidate_closure()
        validator(root, projected_bytes)
        revalidate_closure()
    return Prepared(
        root=root,
        checkpoint_path=checkpoint_path,
        source_bytes=source_bytes,
        projected=projected,
        projected_bytes=projected_bytes,
        final_sha256_by_path=final,
        physical_sha256_by_path=dict(final),
        source_universe=universe,
        candidate_catalogs=candidate,
        catalogs_verified=not allow_stale_catalogs,
        projected_validator=validator,
    )


def write_catalogs(prepared: Prepared) -> None:
    require(not prepared.catalogs_verified, "catalog refresh requires stale catalogs")

    def guard(
        target: Path | None = None,
        source: bytes | None = None,
        wanted: bytes | None = None,
    ) -> None:
        require(
            prepared.checkpoint_path.read_bytes() == prepared.source_bytes,
            "source changed during seq68 catalog refresh",
        )
        require(
            _catalog_source_universe_during_atomic_write(
                prepared.root,
                prepared.source_universe,
                target=target,
                source=source,
                wanted=wanted,
            )
            == prepared.source_universe,
            "catalog source universe changed during seq68 refresh",
        )
        require(
            npc._build_candidate_catalog_bytes(
                prepared.root, prepared.source_universe, prepared.projected
            )
            == prepared.candidate_catalogs,
            "seq68 catalog projection changed during refresh",
        )

    guard()
    for relative in CATALOG_PATHS:
        target = npc._safe_file(prepared.root, relative)
        source = target.read_bytes()
        wanted = prepared.candidate_catalogs[relative]
        if source != wanted:
            npc.atomic_write(
                target,
                wanted,
                expected_source=source,
                commit_guard=lambda: guard(target, source, wanted),
            )
    guard()


def _catalog_source_universe_during_atomic_write(
    root: Path,
    expected: tuple[str, ...],
    *,
    target: Path | None,
    source: bytes | None,
    wanted: bytes | None,
) -> tuple[str, ...]:
    observed = catalogs.discover_source_paths(root)
    if target is None:
        require(
            source is None and wanted is None,
            "catalog staging bytes are unexpected",
        )
        return observed
    require(
        source is not None and wanted is not None,
        "catalog staging bytes are missing",
    )
    pattern = re.compile(
        rf"^\.{re.escape(target.name)}\.fp048-seq43-44\."
        r"[0-9a-f]{24}\.tmp$"
    )
    candidates = sorted(
        (path for path in target.parent.iterdir() if pattern.fullmatch(path.name)),
        key=lambda path: path.name,
    )
    require(len(candidates) <= 1, "catalog staging path is ambiguous")
    if not candidates:
        return observed
    staging = candidates[0]
    try:
        descriptor = os.open(
            staging,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
    except OSError as exc:
        raise ControlReanchorError("catalog staging authority differs") from exc
    try:
        before = os.fstat(descriptor)
        require(
            stat.S_ISREG(before.st_mode)
            and stat.S_IMODE(before.st_mode) == 0o600
            and before.st_uid == os.geteuid()
            and before.st_nlink == 1,
            "catalog staging authority differs",
        )
        first = os.pread(descriptor, before.st_size + 1, 0)
        middle = os.fstat(descriptor)
        second = os.pread(descriptor, middle.st_size + 1, 0)
        after = os.fstat(descriptor)
        current = os.stat(staging, follow_symlinks=False)
        require(
            first == second
            and first in {source, wanted}
            and (
                before.st_dev,
                before.st_ino,
                before.st_size,
                before.st_mtime_ns,
            )
            == (
                middle.st_dev,
                middle.st_ino,
                middle.st_size,
                middle.st_mtime_ns,
            )
            == (
                after.st_dev,
                after.st_ino,
                after.st_size,
                after.st_mtime_ns,
            )
            and (current.st_dev, current.st_ino)
            == (after.st_dev, after.st_ino),
            "catalog staging authority differs",
        )
    finally:
        os.close(descriptor)
    try:
        relative = staging.relative_to(root).as_posix()
    except ValueError as exc:
        raise ControlReanchorError("catalog staging path escapes repository") from exc
    normalized = tuple(path for path in observed if path != relative)
    require(
        normalized == expected
        and observed in {expected, tuple(sorted((*expected, relative)))},
        "catalog staging source universe differs",
    )
    return expected


def write_checkpoint(prepared: Prepared) -> None:
    refreshed = prepare(
        prepared.root, projected_validator=prepared.projected_validator
    )
    require(
        refreshed.source_bytes == prepared.source_bytes
        and refreshed.projected_bytes == prepared.projected_bytes
        and refreshed.candidate_catalogs == prepared.candidate_catalogs
        and refreshed.final_sha256_by_path == prepared.final_sha256_by_path
        and refreshed.physical_sha256_by_path == prepared.physical_sha256_by_path
        and refreshed.source_universe == prepared.source_universe,
        "seq68 projection or physical authority changed before write",
    )
    cohort = npc.retain_physical_pin_cohort(
        prepared.root, refreshed.physical_sha256_by_path
    )
    primary: BaseException | None = None
    try:
        def source_loader(root: Path) -> tuple[str, ...]:
            return npc._catalog_source_universe_at_checkpoint_commit(
                root,
                prepared.source_bytes,
                prepared.projected_bytes,
                loader=catalogs.discover_source_paths,
            )

        def changed_path_loader(root: Path) -> set[Path]:
            return npc._git_visible_paths_at_checkpoint_commit(
                root,
                prepared.source_bytes,
                prepared.projected_bytes,
                loader=npc._git_visible_managed_paths,
            )

        def commit_guard() -> None:
            def verify_closure() -> None:
                cohort.verify()
                npc._require_candidate_catalogs_exact(
                    prepared.root,
                    prepared.source_universe,
                    prepared.candidate_catalogs,
                    checkpoint=prepared.projected,
                    catalog_source_loader=source_loader,
                )
                npc._require_physical_matches(
                    prepared.root, prepared.physical_sha256_by_path
                )
                require(
                    continuation.working_snapshot_hashes(
                        prepared.root,
                        sorted(
                            path.as_posix()
                            for path in prepared.final_sha256_by_path
                        ),
                    )
                    == npc._snapshot_hashes_from_digests(
                        prepared.final_sha256_by_path
                    ),
                    "seq68 managed closure changed at commit",
                )
                npc._require_git_visible_changes_are_managed(
                    prepared.root,
                    prepared.final_sha256_by_path,
                    changed_path_loader=changed_path_loader,
                )

            verify_closure()
            prepared.projected_validator(prepared.root, prepared.projected_bytes)
            verify_closure()

        npc.atomic_write(
            prepared.checkpoint_path,
            prepared.projected_bytes,
            expected_source=prepared.source_bytes,
            commit_guard=commit_guard,
        )
    except BaseException as exc:
        primary = exc
        raise
    finally:
        cohort.close(primary)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--refresh-catalogs", action="store_true")
    mode.add_argument("--write", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.refresh_catalogs:
            stale = prepare(args.root, allow_stale_catalogs=True)
            write_catalogs(stale)
            prepare(args.root)
        else:
            prepared = prepare(args.root)
            if args.write:
                write_checkpoint(prepared)
    except (
        ControlReanchorError,
        OSError,
        ValueError,
        TypeError,
        npc.BuildError,
        npc.CompletionApplyError,
        catalogs.CatalogError,
    ) as exc:
        print(f"WalkSafe FP-022 start-control reanchor seq68: FAIL: {exc}")
        return 1
    print("WalkSafe FP-022 start-control reanchor seq68: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
