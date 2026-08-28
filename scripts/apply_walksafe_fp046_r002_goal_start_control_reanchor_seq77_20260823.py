#!/usr/bin/env python3
"""Seal FP-046 R002 start controls with a zero-credit seq77 reanchor.

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
import subprocess
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814 as base
from scripts import build_walksafe_fp046_r002_seq77_78_review_20260823 as review_authority
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import generate_repository_catalogs as catalogs
from scripts import run_walksafe_fp046_r002_goal_start_gate_20260823 as gate


npc = base.npc
aggregate = base.aggregate
bytes_sha256 = npc.bytes_sha256
CHECKPOINT_REL = base.CHECKPOINT_REL
CATALOG_PATHS = base.CATALOG_PATHS

SOURCE_SHA256 = "bf0f44f0ed2a777c2a3d62b81fa995d26e3b725e398a37034b7d70fa1d3a1a36"
SOURCE_BYTE_COUNT = 2_217_805
SOURCE_SEQUENCE = 76
SOURCE_TAIL_SHA256 = "f2a529bae5aa808fccb64191a42902d7e717d0340531410213486bdcfa279070"
SOURCE_CUTOFF_AT = "2026-08-15T00:00:05+09:00"
SOURCE_BRANCH = "current"
SOURCE_BASE_COMMIT = "f0093863e82bfc80d9f11915cef33a51d44b8730"
SOURCE_CHECKPOINT_HEAD_COMMIT = "ca0898d56eaa45b947b9f513a2bcdbfcb5bc5a0c"
SOURCE_HEAD_COMMIT = "8a146bbf9c5a7612377abb939fc4d94570d45cca"
SOURCE_MANAGED_PATH_COUNT = 947
SOURCE_PATH_SET_SHA256 = "1dd756c9b408932b41d7f0f2d0010f102cf5f23357c8d0868a3ffed95f3925b3"
SOURCE_CONTENT_SET_SHA256 = "47221a8c0cb20dc769b5b906ab97ce2512f43c1872fa44d59fc864cf2d87157c"

SEQUENCE = 77
EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-20260823-001"
)
EVENT_TYPE = "GOAL_START_CONTROL_REANCHORED"
GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R002"
GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp046-consent-withdrawal-deletion-r002.md"
)
GOAL_SHA256 = "4627c19b421f626323778fcdfd01cc2edbc36644c48c7d65c2b4847e698ac429"
READY_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP046-R002-20260815-001"
READY_FRONTIER = [
    GOAL_ID,
    "WS-GOAL-EPIC-03",
    "WS-GOAL-EPIC-04",
    "WS-GOAL-EPIC-12",
]
MANIFEST_SHA256 = "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"

R001_CONTRACT_PATH = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-046-R002/"
    "initial-start-gate-contract-r001.json"
)
R002_CONTRACT_PATH = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-046-R002/"
    "initial-start-gate-contract-r002.json"
)
R002_DOCUMENT_ID = "WS-FP046-R002-INITIAL-START-GATE-CONTRACT-20260823-002"
R002_CONTRACT_ID = "WS-FP046-R002-INTERNAL-START-GATE-R002"
R002_CONTRACT_VERSION = "2026-08-23.1"
SUCCESSOR_REASON_CODE = (
    "SEQ76_READY_TRUST_ANCHOR_AND_CURRENT_CONTROL_COHORT_REQUIRED"
)
START_GATE_RUNNER_PATH = Path(
    "scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py"
)
SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py"
)
AUTHORIZATION_REL = Path(
    "docs/control/execution/workstream-transitions/seq77-78/authorization.json"
)
REVIEW_DIR = review_authority.REVIEW_DIR
REVIEW_PATHS = review_authority.REVIEW_PATHS
PRESERVED_REVIEW_PATHS = review_authority.PRESERVED_REVIEW_PATHS
R005_REVIEW_PATHS = review_authority.R005_REVIEW_PATHS
SESSION_ARTIFACT_PATHS = review_authority.SESSION_ARTIFACT_PATHS

# These paths are the minimum live successor cohort.  Review evidence paths
# and reviewed session artifacts are successor additions; the full Git-visible
# closure is still captured by the snapshot projector.
REQUIRED_CONTROL_PATHS = (
    Path("docs/catalogs/repository-paths.json"),
    Path("docs/catalogs/scripts.json"),
    Path("docs/catalogs/tests.json"),
    R002_CONTRACT_PATH,
    AUTHORIZATION_REL,
    *PRESERVED_REVIEW_PATHS,
    *R005_REVIEW_PATHS,
    *REVIEW_PATHS,
    *SESSION_ARTIFACT_PATHS,
    SCRIPT_REL,
    START_GATE_RUNNER_PATH,
    Path("scripts/apply_walksafe_fp046_r002_goal_started_seq78_20260823.py"),
    Path("scripts/build_walksafe_fp046_r002_seq77_78_review_20260823.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    TEST_REL,
    Path("tests/test_apply_walksafe_fp046_r002_goal_started_seq78_20260823.py"),
    Path("tests/test_build_walksafe_fp046_r002_seq77_78_review_20260823.py"),
    Path("tests/test_repository_catalogs.py"),
    Path("tests/test_walksafe_fp046_r002_goal_start_gate_20260823.py"),
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
    "canonical": "b561c098fbe3c18a288c2d57509c40d58e4f5c4762054a6b15a33c08862cad12",
    "completion": "aea089f1c93cce4c8b7b99a8486856f8922a6455c4ce397f4126124fa41292fc",
    "current_work": "9b5281ccecda6d5de51ddbf10fe11a377f67169736d62d83586eea7f90101f98",
    "runtime": "8d8381bfb3eb0a3a7e430b1e4dd74d58018e3382a3a7595a4bfb7b24005964e9",
    "status": "fadea5a198be3fd0090a0c9eaf02fbb566b88832f5b321501970cf117f906a76",
    "verification": "406f4ec4b66dd3672e1fc2246c5ec2ce4c1ea6fa388549e6dabb52e49f2ac11c",
}
SOURCE_CANONICAL_BINDING_SNAPSHOT_SHA256 = (
    "a938243576e4d9055b71e0596efdb682208d246e50f1b9947ba9d4bbe1528ade"
)

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
    "authorization_binding",
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
    "Graph v2.4 FP-046 R002/GAP-055 READY through zero-credit start-control "
    "reanchor seq77; R002 seals the seq76 READY trust anchor and current "
    "control cohort. No GOAL_STARTED, implementation, artifact, test, "
    "formal, device, external, deployment, signing, or release credit."
)

CONTROL_REANCHOR_SEQUENCE = SEQUENCE
CONTROL_REANCHOR_EVENT_ID = EVENT_ID


class ControlReanchorError(RuntimeError):
    """The exact FP-046 R002 seq77 control projection cannot be proven."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ControlReanchorError(message)


def strict_json_equal(actual: Any, expected: Any) -> bool:
    """Compare JSON values without Python's bool/int/float equivalence."""

    if type(actual) is not type(expected):
        return False
    if type(expected) is dict:
        return set(actual) == set(expected) and all(
            strict_json_equal(actual[key], expected[key]) for key in expected
        )
    if type(expected) is list:
        return len(actual) == len(expected) and all(
            strict_json_equal(left, right)
            for left, right in zip(actual, expected, strict=True)
        )
    return bool(actual == expected)


def _parse_event_occurred_at(value: Any) -> datetime:
    require(
        type(value) is str and bool(value),
        "seq77 occurred_at must be canonical aware second-precision ISO-8601",
    )
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ControlReanchorError(
            "seq77 occurred_at must be canonical aware second-precision ISO-8601"
        ) from exc
    require(
        parsed.tzinfo is not None
        and parsed.utcoffset() is not None
        and parsed.microsecond == 0
        and parsed.isoformat() == value,
        "seq77 occurred_at must be canonical aware second-precision ISO-8601",
    )
    return parsed


def _reviewed_at(root: Path) -> datetime:
    reviewed_at = review_authority.validated_reviewed_at(root)
    require(
        type(reviewed_at) is datetime
        and reviewed_at.tzinfo is not None
        and reviewed_at.utcoffset() is not None,
        "seq77 reviewed_at authority must be timezone-aware",
    )
    return reviewed_at


def _require_event_after_review(root: Path, event_occurred_at: str) -> datetime:
    occurred_at = _parse_event_occurred_at(event_occurred_at)
    require(
        occurred_at > _reviewed_at(root),
        "seq77 occurred_at must follow reviewed_at",
    )
    return occurred_at


def _clock_now() -> datetime:
    return datetime.now().astimezone()


@dataclass(frozen=True)
class Prepared:
    root: Path
    checkpoint_path: Path
    source_bytes: bytes
    event_occurred_at: str
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
        "document_id": "WS-FP046-R002-INITIAL-START-GATE-CONTRACT-20260815-001",
        "contract_id": "WS-FP046-R002-INTERNAL-START-GATE-R001",
        "contract_version": "2026-08-15.1",
        "path": R001_CONTRACT_PATH.as_posix(),
        "file_sha256": "506d6afb202b63e4e30f3bb5ba1a560b18b8f76679a37a18a144a442a4a0fe5e",
        "canonical_sha256": "a8295f1a693b41bbc3864f882fdd8ba9f17e0ddba49562517fcfc3bb26679214",
    }


def _review_module() -> Any:
    return review_authority


def _load_r002_contract(root: Path) -> tuple[dict[str, Any], dict[str, str]]:
    raw = npc._read_safe_bytes(root, R002_CONTRACT_PATH)
    require(
        raw == _review_module().build_contract().encode(),
        "FP-046 R002 start-gate contract bytes differ",
    )
    document = npc.trace.strict_json_bytes(raw, R002_CONTRACT_PATH.as_posix())
    expected_supersedes = {
        **r001_contract_binding(),
        "byte_length": 1_011,
        "source_ready_event_sequence": SOURCE_SEQUENCE,
        "source_ready_event_id": READY_EVENT_ID,
        "source_ready_event_sha256": SOURCE_TAIL_SHA256,
    }
    checks = document.get("ordered_checks")
    require(
        document.get("schema_version") == "1.1"
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
        == (
            "CONTINUATION",
            "GOAL_GRAPH",
            "TEST_LAYER_REGISTRY_VALIDATE",
            "ROOT_FP046_R002_CONTROL_REGRESSION",
            "REPOSITORY_STATE",
        ),
        "FP-046 R002 start-gate contract differs",
    )
    binding = {
        "schema_version": "1.1",
        "document_id": R002_DOCUMENT_ID,
        "path": R002_CONTRACT_PATH.as_posix(),
        "file_sha256": bytes_sha256(raw),
        "contract_id": R002_CONTRACT_ID,
        "contract_version": R002_CONTRACT_VERSION,
        "canonical_contract_sha256": continuation.canonical_json_sha256(document),
    }
    return document, binding


def transition_review_binding(root: Path) -> dict[str, dict[str, Any]]:
    return _review_module().transition_review_binding(root)


def authorization_binding(root: Path) -> dict[str, Any]:
    review = _review_module()
    if hasattr(review, "authorization_binding"):
        return review.authorization_binding(root)
    raw = npc._read_safe_bytes(root, review.AUTHORIZATION_REL)
    require(
        raw == review.build_authorization().encode(),
        "FP-046 R002 authorization bytes differ",
    )
    return _binding(review.AUTHORIZATION_REL, raw)


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
        "checkpoint physical binding differs from exact seq76",
    )
    require(source.get("schema_version") == "1.25.0", "source schema differs")
    state = source.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(
        type(history) is list and len(history) == SOURCE_SEQUENCE,
        "source history is not exact seq76",
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
        and state.get("transition_history_anchor_sha256") == SOURCE_TAIL_SHA256
        and state.get("validation_cutoff_at") == SOURCE_CUTOFF_AT,
        "source seq76 READY tail differs",
    )
    require(
        state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH.as_posix()
        and state.get("focus_work_item_id") == GOAL_ID
        and state.get("focus_source") == "IMPLEMENTATION_GAP"
        and state.get("ready_frontier_goal_ids") == READY_FRONTIER
        and state.get("blockers_by_goal") == {}
        and state.get("pending_questions") == [],
        "source seq76 runtime differs",
    )
    require(
        control_projection_hashes(source) == SOURCE_UNCHANGED_CONTROL_SHA256,
        "source seq76 zero-credit projection differs",
    )
    require(
        _source_repository_context(source) == expected_source_repository_context(),
        "source seq76 repository context differs",
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
        "current_head": SOURCE_CHECKPOINT_HEAD_COMMIT,
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
        "current_head": SOURCE_HEAD_COMMIT,
        "managed_changed_path_count": len(final_sha256_by_path),
        "path_set_sha256": path_hash,
        "content_set_sha256": content_hash,
    }


def project(
    source: Mapping[str, Any],
    final_sha256_by_path: Mapping[Path, str],
    *,
    event_occurred_at: str,
    successor_contract: Mapping[str, Any],
    authorization: Mapping[str, Any],
    runner_binding: Mapping[str, Any],
    transition_review: Mapping[str, Mapping[str, Any]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    occurred_at = _parse_event_occurred_at(event_occurred_at)
    source_raw = npc.trace.json_text(dict(source)).encode()
    require_source(source_raw, source)
    source_paths = {
        Path(path) for path in source["working_tree_snapshot"]["managed_changed_paths"]
    }
    expected_paths = source_paths | set(REQUIRED_CONTROL_PATHS)
    require(
        set(final_sha256_by_path) == expected_paths,
        "seq77 successor control cohort inventory differs",
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
        "occurred_on": occurred_at.date().isoformat(),
        "occurred_at": event_occurred_at,
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
            "FP046-R002_INITIAL_START_GATE_CONTRACT_SUCCESSOR",
            "FP046-R002_START_GATE_RUNNER_SEAL",
            "FP046-R002_SEQ77_78_TRANSITION_CONTROL_REVIEW",
        ],
        "source_checkpoint_binding": source_checkpoint_binding(),
        "source_ready_event_binding": source_ready_event_binding(),
        "authorization_binding": copy.deepcopy(dict(authorization)),
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
        "canonical_binding_snapshot_after": base.fp022._binding_map(source),
        "previous_event_sha256": SOURCE_TAIL_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq77 event field set differs")
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = event_occurred_at
    aggregate._apply_snapshot(checkpoint, final_sha256_by_path)
    checkpoint["working_tree_snapshot"]["scope"] = FINAL_SCOPE
    checkpoint["session_handoff"]["source_commit_or_snapshot"][
        "current_head"
    ] = SOURCE_HEAD_COMMIT
    return checkpoint, event


def validate_projection(
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    event: Mapping[str, Any],
    *,
    event_occurred_at: str,
    successor_contract: Mapping[str, Any],
    authorization: Mapping[str, Any],
    runner_binding: Mapping[str, Any],
    transition_review: Mapping[str, Mapping[str, Any]],
) -> None:
    occurred_at = _parse_event_occurred_at(event_occurred_at)
    before = source["goal_execution"]
    after = projected["goal_execution"]
    allowed_top = {"goal_execution", "working_tree_snapshot", "session_handoff"}
    require(
        set(projected) == set(source)
        and all(
            strict_json_equal(projected[key], source[key])
            for key in source
            if key not in allowed_top
        ),
        "seq77 changed unrelated top-level state",
    )
    allowed_state = {
        "transition_history",
        "transition_history_anchor_sha256",
        "validation_cutoff_at",
    }
    require(
        set(after) == set(before)
        and all(
            strict_json_equal(after[key], before[key])
            for key in before
            if key not in allowed_state
        ),
        "seq77 changed non-history Goal state",
    )
    require(
        strict_json_equal(
            after["transition_history"][:-1], before["transition_history"]
        )
        and strict_json_equal(after["transition_history"][-1], event)
        and len(after["transition_history"]) == SEQUENCE,
        "seq77 history is not add-only",
    )
    require(
        set(event) == EVENT_FIELDS
        and type(event.get("sequence")) is int
        and event.get("sequence") == SEQUENCE
        and event.get("event_id") == EVENT_ID
        and event.get("event_type") == EVENT_TYPE
        and event.get("occurred_on") == occurred_at.date().isoformat()
        and event.get("occurred_at") == event_occurred_at
        and event.get("subject_goal_id") == GOAL_ID
        and event.get("from_status") == event.get("to_status") == "READY"
        and strict_json_equal(event.get("status_changes"), {})
        and event.get("previous_event_sha256") == SOURCE_TAIL_SHA256
        and event.get("event_sha256") == continuation.event_sha256(dict(event))
        and strict_json_equal(
            event.get("source_checkpoint_binding"), source_checkpoint_binding()
        )
        and strict_json_equal(
            event.get("source_ready_event_binding"), source_ready_event_binding()
        ),
        "seq77 event identity differs",
    )
    require(
        strict_json_equal(
            event.get("contract_supersession"),
            {
                "previous_contract_binding": r001_contract_binding(),
                "replacement_contract_binding": dict(successor_contract),
                "reason_code": SUCCESSOR_REASON_CODE,
            },
        )
        and strict_json_equal(event.get("authorization_binding"), dict(authorization))
        and strict_json_equal(
            event.get("start_gate_runner_binding"), dict(runner_binding)
        )
        and strict_json_equal(
            event.get("transition_control_review_binding"),
            {key: dict(value) for key, value in transition_review.items()},
        ),
        "seq77 successor control binding differs",
    )
    require(
        strict_json_equal(event.get("claim_boundary"), CLAIM_BOUNDARY)
        and strict_json_equal(event.get("runtime_after"), before["transition_history"][-1]["runtime_after"])
        and strict_json_equal(event.get("blockers_after"), before["transition_history"][-1]["blockers_after"])
        and strict_json_equal(
            event.get("blocker_resolution_ids_after"),
            before["transition_history"][-1]["blocker_resolution_ids_after"],
        )
        and control_projection_hashes(projected) == SOURCE_UNCHANGED_CONTROL_SHA256
        and after["status_by_goal"].get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in after["status_by_goal"].values()
        and strict_json_equal(projected["current_work"], source["current_work"])
        and strict_json_equal(
            projected["verification_boundary"], source["verification_boundary"]
        )
        and strict_json_equal(
            projected["authority_boundary"], source["authority_boundary"]
        )
        and strict_json_equal(projected["approved_state"], source["approved_state"]),
        "seq77 gained status, implementation, verification, or release credit",
    )
    snapshot = projected["working_tree_snapshot"]
    paths = snapshot["managed_changed_paths"]
    expected_paths = sorted(
        {
            *source["working_tree_snapshot"]["managed_changed_paths"],
            *(path.as_posix() for path in REQUIRED_CONTROL_PATHS),
        }
    )
    mirror = projected["session_handoff"]["source_commit_or_snapshot"]
    require(
        strict_json_equal(
            event.get("repository_context_reanchor"),
            {
                "before": _source_repository_context(source),
                "after": {
                    "branch": projected["repository"]["branch"],
                    "base_commit": mirror["base_commit"],
                    "current_head": mirror["current_head"],
                    "managed_changed_path_count": snapshot[
                        "managed_changed_path_count"
                    ],
                    "path_set_sha256": snapshot["path_set_sha256"],
                    "content_set_sha256": snapshot["content_set_sha256"],
                },
            },
        ),
        "seq77 repository context differs",
    )
    require(
        strict_json_equal(paths, expected_paths)
        and snapshot["scope"] == FINAL_SCOPE
        and strict_json_equal(
            projected["session_handoff"]["changed_files"], paths
        ),
        "seq77 managed successor closure differs",
    )
    require(
        projected["session_handoff"]["source_commit_or_snapshot"].get(
            "current_head"
        )
        == SOURCE_HEAD_COMMIT,
        "seq77 publication HEAD differs",
    )
    require(
        after["transition_history_anchor_sha256"] == event["event_sha256"]
        and after["validation_cutoff_at"] == event_occurred_at,
        "seq77 tail authority differs",
    )


def require_control_reanchored_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    run_external_validators: bool = True,
    require_live_snapshot: bool = True,
) -> None:
    """Validate an exact seq77 document, including after seq77 is published."""
    root = root.resolve(strict=True)
    _, successor = _load_r002_contract(root)
    authorization = authorization_binding(root)
    runner = start_gate_runner_binding(root)
    review = transition_review_binding(root)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(
        type(history) is list and len(history) == SEQUENCE and type(history[-1]) is dict,
        "checkpoint is not exact seq77",
    )
    previous = ""
    for sequence, item in enumerate(history, start=1):
        require(
            type(item) is dict
            and type(item.get("sequence")) is int
            and item.get("sequence") == sequence
            and item.get("previous_event_sha256") == previous
            and item.get("event_sha256") == continuation.event_sha256(item),
            f"seq77 source event {sequence} chain differs",
        )
        previous = item["event_sha256"]
    ready = history[SOURCE_SEQUENCE - 1]
    event = history[-1]
    occurred_at = _require_event_after_review(root, event.get("occurred_at"))
    require(
        ready.get("event_id") == READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == GOAL_ID
        and ready.get("event_sha256") == SOURCE_TAIL_SHA256,
        "seq77 exact seq76 READY source differs",
    )
    require(
        set(event) == EVENT_FIELDS
        and type(event.get("sequence")) is int
        and event.get("sequence") == SEQUENCE
        and event.get("event_id") == EVENT_ID
        and event.get("event_type") == EVENT_TYPE
        and event.get("occurred_on") == occurred_at.date().isoformat()
        and event.get("occurred_at") == occurred_at.isoformat()
        and event.get("previous_focus_goal_id") == GOAL_ID
        and event.get("previous_focus_content_sha256") == GOAL_SHA256
        and event.get("focus_goal_id") == GOAL_ID
        and event.get("focus_goal_content_sha256") == GOAL_SHA256
        and event.get("subject_goal_id") == GOAL_ID
        and event.get("from_status") == event.get("to_status") == "READY"
        and strict_json_equal(event.get("status_changes"), {})
        and strict_json_equal(
            event.get("source_checkpoint_binding"), source_checkpoint_binding()
        )
        and strict_json_equal(
            event.get("source_ready_event_binding"), source_ready_event_binding()
        )
        and event.get("previous_event_sha256") == SOURCE_TAIL_SHA256,
        "seq77 event identity or source binding differs",
    )
    require(
        strict_json_equal(
            event.get("contract_supersession"),
            {
                "previous_contract_binding": r001_contract_binding(),
                "replacement_contract_binding": successor,
                "reason_code": SUCCESSOR_REASON_CODE,
            },
        )
        and strict_json_equal(event.get("authorization_binding"), authorization)
        and strict_json_equal(event.get("start_gate_runner_binding"), runner)
        and strict_json_equal(
            event.get("transition_control_review_binding"), review
        )
        and strict_json_equal(event.get("claim_boundary"), CLAIM_BOUNDARY),
        "seq77 live successor authority differs",
    )
    require(
        strict_json_equal(event.get("runtime_after"), _runtime_projection(state))
        and strict_json_equal(
            event.get("blockers_after"), state.get("blockers_by_goal")
        )
        and strict_json_equal(
            event.get("blocker_resolution_ids_after"),
            [
                item["resolution_id"]
                for item in state.get("blocker_resolution_history", [])
            ],
        )
        and strict_json_equal(
            event.get("canonical_binding_snapshot_after"),
            base.fp022._binding_map(checkpoint),
        ),
        "seq77 runtime or canonical projection differs",
    )
    expected_unchanged = {
        name: {"before_sha256": digest, "after_sha256": digest}
        for name, digest in sorted(SOURCE_UNCHANGED_CONTROL_SHA256.items())
    }
    require(
        strict_json_equal(
            event.get("unchanged_control_projection"), expected_unchanged
        )
        and control_projection_hashes(checkpoint) == SOURCE_UNCHANGED_CONTROL_SHA256,
        "seq77 zero-credit control projection differs",
    )
    require(
        state.get("transition_history_anchor_sha256") == event.get("event_sha256")
        and state.get("validation_cutoff_at") == event.get("occurred_at")
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH.as_posix()
        and state.get("focus_work_item_id") == GOAL_ID
        and state.get("ready_frontier_goal_ids") == READY_FRONTIER,
        "seq77 READY state or tail differs",
    )
    paths = checkpoint["working_tree_snapshot"]["managed_changed_paths"]
    snapshot = checkpoint["working_tree_snapshot"]
    mirror = checkpoint["session_handoff"]["source_commit_or_snapshot"]
    _, frozen_source = load_frozen_source_checkpoint(root)
    expected_paths = exact_seq77_managed_paths(frozen_source)
    repository_after = {
        "branch": checkpoint["repository"]["branch"],
        "base_commit": mirror["base_commit"],
        "current_head": mirror["current_head"],
        "managed_changed_path_count": snapshot["managed_changed_path_count"],
        "path_set_sha256": snapshot["path_set_sha256"],
        "content_set_sha256": snapshot["content_set_sha256"],
    }
    require(
        strict_json_equal(
            event.get("repository_context_reanchor"),
            {
                "before": expected_source_repository_context(),
                "after": repository_after,
            },
        )
        and strict_json_equal(paths, expected_paths)
        and snapshot.get("scope") == FINAL_SCOPE
        and strict_json_equal(
            checkpoint["session_handoff"].get("changed_files"), paths
        ),
        "seq77 repository projection differs",
    )
    require(
        mirror.get("current_head") == SOURCE_HEAD_COMMIT,
        "seq77 publication HEAD differs",
    )
    if require_live_snapshot:
        observed = continuation.working_snapshot_hashes(root, paths)
        require(
            observed
            == (
                checkpoint["working_tree_snapshot"]["path_set_sha256"],
                checkpoint["working_tree_snapshot"]["content_set_sha256"],
            ),
            "seq77 live managed snapshot differs",
        )
    if run_external_validators:
        npc.validate_projected_with_both_checkers(
            root, npc.trace.json_text(dict(checkpoint)).encode()
        )


def _frozen_git_path_bytes(
    root: Path,
    relative: Path,
    *,
    maximum_bytes: int,
) -> bytes:
    try:
        completed = subprocess.run(
            [
                "/usr/bin/git",
                "-C",
                str(root),
                "show",
                f"{SOURCE_HEAD_COMMIT}:{relative.as_posix()}",
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise ControlReanchorError(
            "frozen seq76 checkpoint Git authority cannot be read"
        ) from exc
    require(
        completed.returncode == 0
        and len(completed.stderr) <= 16_384
        and len(completed.stdout) <= maximum_bytes,
        f"frozen Git authority differs: {relative}",
    )
    return completed.stdout


def load_frozen_source_checkpoint(
    root: Path = ROOT,
) -> tuple[bytes, dict[str, Any]]:
    """Load the byte-exact seq76 source from its immutable Git commit."""

    root = root.resolve(strict=True)
    raw = _frozen_git_path_bytes(
        root,
        CHECKPOINT_REL,
        maximum_bytes=SOURCE_BYTE_COUNT,
    )
    source = npc.trace.strict_json_bytes(raw, "frozen seq76 checkpoint")
    require_source(raw, source)
    return raw, source


def exact_seq77_managed_paths(source: Mapping[str, Any]) -> list[str]:
    return sorted(
        {
            *source["working_tree_snapshot"]["managed_changed_paths"],
            *(path.as_posix() for path in REQUIRED_CONTROL_PATHS),
        }
    )


def _require_publication_head(root: Path) -> None:
    try:
        completed = subprocess.run(
            [
                "/usr/bin/git",
                "-C",
                str(root),
                "rev-parse",
                "--verify",
                "HEAD^{commit}",
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise ControlReanchorError("seq77 publication Git HEAD cannot be read") from exc
    require(
        completed.returncode == 0
        and completed.stdout.strip().decode("ascii", errors="strict")
        == SOURCE_HEAD_COMMIT,
        "seq77 publication Git HEAD differs",
    )


def _require_reviewed_physical_successor(
    root: Path,
    source: Mapping[str, Any],
    physical: Mapping[Path, str],
    review_context: Any,
) -> None:
    source_paths = {
        Path(path) for path in source["working_tree_snapshot"]["managed_changed_paths"]
    }
    expected_paths = source_paths | set(REQUIRED_CONTROL_PATHS)
    require(
        len(expected_paths) == 971 and set(physical) == expected_paths,
        "seq77 physical managed path inventory contains an unreviewed delta",
    )
    current = {
        Path(row["path"]): row["sha256"]
        for row in review_context.current_control_cohort
    }
    require(
        all(physical.get(path) == digest for path, digest in current.items()),
        "seq77 reviewed control successor bytes differ",
    )
    session_artifacts = {
        Path(row["path"]): row["sha256"]
        for row in review_context.session_artifact_bindings
    }
    require(
        tuple(session_artifacts) == SESSION_ARTIFACT_PATHS
        and all(
            physical.get(path) == digest
            for path, digest in session_artifacts.items()
        ),
        "seq77 reviewed session artifact bytes differ",
    )
    rewound = {path: physical[path] for path in source_paths}
    for row in review_context.frozen_r011_cohort:
        path = Path(row["path"])
        if path in rewound:
            rewound[path] = row["sha256"]
    for path in CATALOG_PATHS:
        rewound[path] = bytes_sha256(
            _frozen_git_path_bytes(root, path, maximum_bytes=16 * 1024 * 1024)
        )
    require(
        npc._snapshot_hashes_from_digests(rewound)
        == (SOURCE_PATH_SET_SHA256, SOURCE_CONTENT_SET_SHA256),
        "seq77 source snapshot contains an unreviewed content change",
    )


def reconstructed_seq77_checkpoint_bytes(
    root: Path,
    event: Mapping[str, Any],
) -> bytes:
    """Rebuild the exact seq77 checkpoint bytes from frozen seq76 and event 77."""

    _, source = load_frozen_source_checkpoint(root)
    occurred_at = _parse_event_occurred_at(event.get("occurred_at"))
    require(
        event.get("occurred_on") == occurred_at.date().isoformat(),
        "seq77 occurred_on differs from stored occurred_at",
    )
    repository = event.get("repository_context_reanchor")
    after = repository.get("after") if isinstance(repository, dict) else None
    require(isinstance(after, dict), "seq77 repository reanchor is missing")
    paths = exact_seq77_managed_paths(source)
    path_hash = bytes_sha256(("\n".join(paths) + "\n").encode())
    require(
        type(after.get("managed_changed_path_count")) is int
        and after.get("managed_changed_path_count") == len(paths)
        and after.get("path_set_sha256") == path_hash,
        "seq77 exact managed path authority differs",
    )
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    state["transition_history"].append(copy.deepcopy(dict(event)))
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = event["occurred_at"]
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot.update(
        {
            "scope": FINAL_SCOPE,
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": path_hash,
            "content_set_sha256": after["content_set_sha256"],
        }
    )
    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(paths)
    mirror = handoff["source_commit_or_snapshot"]
    mirror.update(
        {
            "current_head": SOURCE_HEAD_COMMIT,
            "file_count": len(paths),
            "path_set_sha256": path_hash,
            "content_set_sha256": after["content_set_sha256"],
        }
    )
    return npc.trace.json_text(checkpoint).encode()


def validate_history_suffix(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> list[str]:
    """Validate the immutable seq77 event in an exact or later checkpoint."""

    try:
        root = root.resolve(strict=True)
        _, successor = _load_r002_contract(root)
        authorization = authorization_binding(root)
        runner = start_gate_runner_binding(root)
        review = transition_review_binding(root)
        state = checkpoint.get("goal_execution")
        history = state.get("transition_history") if type(state) is dict else None
        require(type(history) is list and len(history) >= SEQUENCE, "seq77 is missing")
        event = history[SEQUENCE - 1]
        previous = history[SOURCE_SEQUENCE - 1]
        require(type(event) is dict and type(previous) is dict, "seq77 history differs")
        occurred_at = _require_event_after_review(root, event.get("occurred_at"))
        require(
            event.get("occurred_on") == occurred_at.date().isoformat(),
            "seq77 occurred_on differs from stored occurred_at",
        )
        require(
            len(history) > SEQUENCE
            or state.get("validation_cutoff_at") == event.get("occurred_at"),
            "seq77 validation cutoff differs from stored occurred_at",
        )
        require(
            set(event) == EVENT_FIELDS
            and type(event.get("sequence")) is int
            and event.get("sequence") == SEQUENCE
            and event.get("event_id") == EVENT_ID
            and event.get("event_type") == EVENT_TYPE
            and event.get("occurred_at") == occurred_at.isoformat()
            and event.get("previous_focus_goal_id") == GOAL_ID
            and event.get("previous_focus_content_sha256") == GOAL_SHA256
            and event.get("focus_goal_id") == GOAL_ID
            and event.get("focus_goal_content_sha256") == GOAL_SHA256
            and event.get("subject_goal_id") == GOAL_ID
            and event.get("from_status") == event.get("to_status") == "READY"
            and event.get("static_plan_manifest_sha256") == MANIFEST_SHA256
            and strict_json_equal(event.get("status_changes"), {})
            and strict_json_equal(
                event.get("runtime_after"), previous.get("runtime_after")
            )
            and strict_json_equal(
                event.get("blockers_after"), previous.get("blockers_after")
            )
            and strict_json_equal(
                event.get("blocker_resolution_ids_after"),
                previous.get("blocker_resolution_ids_after"),
            )
            and event.get("source_checkpoint_version")
            == checkpoint.get("schema_version")
            and strict_json_equal(
                event.get("evidence_refs"),
                [
                    "FP046-R002_INITIAL_START_GATE_CONTRACT_SUCCESSOR",
                    "FP046-R002_START_GATE_RUNNER_SEAL",
                    "FP046-R002_SEQ77_78_TRANSITION_CONTROL_REVIEW",
                ],
            )
            and event.get("previous_event_sha256") == SOURCE_TAIL_SHA256
            and event.get("event_sha256") == continuation.event_sha256(event)
            and previous.get("event_id") == READY_EVENT_ID
            and previous.get("event_sha256") == SOURCE_TAIL_SHA256
            and strict_json_equal(
                event.get("source_checkpoint_binding"), source_checkpoint_binding()
            )
            and strict_json_equal(
                event.get("source_ready_event_binding"), source_ready_event_binding()
            ),
            "seq77 event identity differs",
        )
        require(
            strict_json_equal(event.get("authorization_binding"), authorization)
            and strict_json_equal(
                event.get("contract_supersession"),
                {
                    "previous_contract_binding": r001_contract_binding(),
                    "replacement_contract_binding": successor,
                    "reason_code": SUCCESSOR_REASON_CODE,
                },
            )
            and strict_json_equal(event.get("start_gate_runner_binding"), runner)
            and strict_json_equal(
                event.get("transition_control_review_binding"), review
            )
            and strict_json_equal(event.get("claim_boundary"), CLAIM_BOUNDARY)
            and strict_json_equal(
                event.get("unchanged_control_projection"),
                {
                    name: {"before_sha256": digest, "after_sha256": digest}
                    for name, digest in sorted(
                        SOURCE_UNCHANGED_CONTROL_SHA256.items()
                    )
                },
            )
            and continuation.canonical_json_sha256(
                event.get("canonical_binding_snapshot_after")
            )
            == SOURCE_CANONICAL_BINDING_SNAPSHOT_SHA256,
            "seq77 control authority differs",
        )
        _, frozen_source = load_frozen_source_checkpoint(root)
        expected_paths = exact_seq77_managed_paths(frozen_source)
        repository = event.get("repository_context_reanchor")
        after = repository.get("after") if isinstance(repository, dict) else None
        require(
            isinstance(repository, dict)
            and strict_json_equal(
                repository.get("before"), expected_source_repository_context()
            )
            and isinstance(after, dict)
            and set(after)
            == {
                "branch",
                "base_commit",
                "current_head",
                "managed_changed_path_count",
                "path_set_sha256",
                "content_set_sha256",
            }
            and after.get("branch") == SOURCE_BRANCH
            and after.get("base_commit") == SOURCE_BASE_COMMIT
            and after.get("current_head") == SOURCE_HEAD_COMMIT
            and type(after.get("managed_changed_path_count")) is int
            and after.get("managed_changed_path_count") == len(expected_paths)
            and after.get("path_set_sha256")
            == bytes_sha256(
                ("\n".join(expected_paths) + "\n").encode()
            )
            and all(
                isinstance(after.get(field), str)
                and re.fullmatch(r"[0-9a-f]{64}", after[field]) is not None
                for field in ("path_set_sha256", "content_set_sha256")
            )
            and after != repository["before"],
            "seq77 repository reanchor differs",
        )
        if len(history) >= SEQUENCE + 1:
            started = history[SEQUENCE]
            binding = (
                started.get("implementation_start_gate_binding")
                if isinstance(started, dict)
                else None
            )
            expected_receipt_relative = (
                gate.GATE_ROOT_RELATIVE
                / gate.STARTED_EVENT_ID
                / gate.RECEIPT_NAME
            )
            expected_document_id = gate._document_id(gate.STARTED_EVENT_ID)
            require(
                type(started) is dict
                and set(started) == continuation.V24_FIRST_START_EVENT_FIELDS
                and type(started.get("sequence")) is int
                and started.get("sequence") == SEQUENCE + 1
                and started.get("event_id") == gate.STARTED_EVENT_ID
                and started.get("event_type") == "GOAL_STARTED"
                and started.get("previous_focus_goal_id") == GOAL_ID
                and started.get("previous_focus_content_sha256") == GOAL_SHA256
                and started.get("focus_goal_id") == GOAL_ID
                and started.get("focus_goal_content_sha256") == GOAL_SHA256
                and started.get("subject_goal_id") == GOAL_ID
                and started.get("from_status") == "READY"
                and started.get("to_status") == "IN_PROGRESS"
                and strict_json_equal(
                    started.get("status_changes"), {GOAL_ID: "IN_PROGRESS"}
                )
                and started.get("static_plan_manifest_sha256") == MANIFEST_SHA256
                and strict_json_equal(
                    started.get("runtime_after"), event.get("runtime_after")
                )
                and strict_json_equal(
                    started.get("blockers_after"), event.get("blockers_after")
                )
                and strict_json_equal(
                    started.get("blocker_resolution_ids_after"),
                    event.get("blocker_resolution_ids_after"),
                )
                and started.get("source_checkpoint_version")
                == checkpoint.get("schema_version")
                and strict_json_equal(started.get("evidence_refs"), [])
                and started.get("previous_event_sha256") == event["event_sha256"]
                and started.get("event_sha256")
                == continuation.event_sha256(started),
                "seq77 successor event identity differs",
            )
            require(
                type(binding) is dict
                and set(binding) == {"document_id", "path", "file_sha256"}
                and binding.get("document_id") == expected_document_id
                and binding.get("path") == expected_receipt_relative.as_posix()
                and type(binding.get("file_sha256")) is str
                and re.fullmatch(r"[0-9a-f]{64}", binding["file_sha256"])
                is not None,
                "seq77 successor receipt binding differs",
            )
            receipt_relative = expected_receipt_relative
            receipt_path = npc._safe_file(root, receipt_relative)
            receipt_raw = gate._private_file_bytes(
                receipt_path,
                allow_empty=False,
                maximum_bytes=gate.LOG_MAX_BYTES,
            )
            receipt = npc.trace.strict_json_bytes(
                receipt_raw, "seq77 successor PASS receipt"
            )
            checks, _ = gate._load_gate_contract(root)
            runs = receipt.get("check_runs")
            snapshot = receipt.get("repository_snapshot")
            require(
                set(receipt) == gate.RECEIPT_FIELDS
                and bytes_sha256(receipt_raw) == binding["file_sha256"]
                and receipt.get("schema_version") == "1.1"
                and receipt.get("document_id") == expected_document_id
                and receipt.get("document_id") == binding["document_id"]
                and receipt.get("evidence_type")
                == "IMPLEMENTATION_START_OR_RESUME_GATE"
                and receipt.get("gate_purpose") == "INITIAL_START"
                and receipt.get("status") == "PASS"
                and receipt.get("package_id") == gate.PACKAGE_ID
                and receipt.get("target_transition_event_id")
                == gate.STARTED_EVENT_ID
                and receipt.get("target_goal_id") == gate.TARGET_GOAL_ID
                and receipt.get("target_goal_content_sha256")
                == gate.TARGET_GOAL_SHA256
                and receipt.get("static_plan_manifest_sha256")
                == gate.MANIFEST_SHA256
                and receipt.get("source_activation_event_sha256")
                == event["event_sha256"]
                and receipt.get("source_checkpoint_sha256")
                == bytes_sha256(reconstructed_seq77_checkpoint_bytes(root, event))
                and receipt.get("source_ready_event_sha256")
                == gate.SOURCE_READY_EVENT_SHA256
                and receipt.get("check_command_contract_version")
                == gate.CONTRACT_VERSION
                and receipt.get("check_command_contract_sha256")
                == gate.CONTRACT_CANONICAL_SHA256
                and strict_json_equal(
                    receipt.get("implementation_start_gate_contract_binding"),
                    gate.expected_contract_binding(),
                )
                and strict_json_equal(receipt.get("runtime_bindings"), [])
                and type(runs) is list
                and len(runs) == len(checks) == len(gate.EXPECTED_CHECK_IDS)
                and tuple(
                    run.get("check_id") if type(run) is dict else None
                    for run in runs
                )
                == gate.EXPECTED_CHECK_IDS
                and all(
                    type(run) is dict
                    and set(run)
                    == {
                        "check_id",
                        "command",
                        "executed_at",
                        "exit_code",
                        "output_path",
                        "output_sha256",
                    }
                    and run.get("command") == checks[index][1]
                    and type(run.get("exit_code")) is int
                    and run.get("exit_code") == 0
                    for index, run in enumerate(runs)
                )
                and isinstance(snapshot, dict)
                and type(snapshot.get("checkpoint_managed_path_count")) is int
                and snapshot.get("checkpoint_managed_path_count")
                == after["managed_changed_path_count"]
                and snapshot.get("checkpoint_path_set_sha256")
                == after["path_set_sha256"]
                and snapshot.get("checkpoint_content_set_sha256")
                == after["content_set_sha256"]
                and strict_json_equal(
                    started.get("repository_snapshot_before"), snapshot
                )
                and strict_json_equal(
                    started.get("implementation_start_gate_binding"), binding
                ),
                "seq77 successor receipt rewind authority differs",
            )
    except (
        ControlReanchorError,
        gate.GateError,
        npc.BuildError,
        npc.CompletionApplyError,
        OSError,
        ValueError,
        TypeError,
        review_authority.ReviewError,
    ) as exc:
        return [str(exc)]
    return []


def prepare(
    root: Path = ROOT,
    *,
    allow_stale_catalogs: bool = False,
    projected_validator: Callable[[Path, bytes], None] | None = None,
    event_occurred_at: str | None = None,
    clock: Callable[[], datetime] = _clock_now,
) -> Prepared:
    root = root.resolve(strict=True)
    if event_occurred_at is None:
        captured = clock()
        require(
            type(captured) is datetime
            and captured.tzinfo is not None
            and captured.utcoffset() is not None,
            "seq77 clock must return a timezone-aware datetime",
        )
        event_occurred_at = captured.replace(microsecond=0).isoformat()
    _parse_event_occurred_at(event_occurred_at)
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
    authorization = authorization_binding(root)
    runner = start_gate_runner_binding(root)
    review = transition_review_binding(root)
    review_context = _review_module().validate_post_review(root)
    _require_event_after_review(root, event_occurred_at)
    _require_publication_head(root)
    universe = catalogs.discover_source_paths(root)
    physical = aggregate._snapshot_digests(root, source)
    _require_reviewed_physical_successor(root, source, physical, review_context)
    draft, _ = project(
        source,
        physical,
        event_occurred_at=event_occurred_at,
        successor_contract=successor_contract,
        authorization=authorization,
        runner_binding=runner,
        transition_review=review,
    )
    candidate = npc._build_candidate_catalog_bytes(root, universe, draft)
    final = dict(physical)
    final.update({path: bytes_sha256(raw) for path, raw in candidate.items()})
    projected, event = project(
        source,
        final,
        event_occurred_at=event_occurred_at,
        successor_contract=successor_contract,
        authorization=authorization,
        runner_binding=runner,
        transition_review=review,
    )
    require(
        npc._build_candidate_catalog_bytes(root, universe, projected) == candidate,
        "seq77 catalog/checkpoint projection did not reach a fixed point",
    )
    validate_projection(
        source,
        projected,
        event,
        event_occurred_at=event_occurred_at,
        successor_contract=successor_contract,
        authorization=authorization,
        runner_binding=runner,
        transition_review=review,
    )
    projected_bytes = npc.trace.json_text(projected).encode()

    if not allow_stale_catalogs:
        def revalidate_closure() -> None:
            require(
                checkpoint_path.read_bytes() == source_bytes,
                "source changed during seq77 preflight",
            )
            require(
                catalogs.discover_source_paths(root) == universe,
                "catalog source universe changed during seq77 preflight",
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
                "live seq77 managed snapshot differs",
            )
            npc._require_git_visible_changes_are_managed(root, final)

        revalidate_closure()
        validator(root, projected_bytes)
        revalidate_closure()
    return Prepared(
        root=root,
        checkpoint_path=checkpoint_path,
        source_bytes=source_bytes,
        event_occurred_at=event_occurred_at,
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
            "source changed during seq77 catalog refresh",
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
            "catalog source universe changed during seq77 refresh",
        )
        require(
            npc._build_candidate_catalog_bytes(
                prepared.root, prepared.source_universe, prepared.projected
            )
            == prepared.candidate_catalogs,
            "seq77 catalog projection changed during refresh",
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


def _write_checkpoint_transport(
    atomic_writer: Callable[..., None],
    path: Path,
    content: bytes,
    *,
    expected_source: bytes,
    commit_guard: Callable[[], None],
) -> None:
    source_state = _stable_transport_target_state(
        path,
        maximum_bytes=max(len(expected_source), len(content)),
    )
    try:
        atomic_writer(
            path,
            content,
            expected_source=expected_source,
            commit_guard=commit_guard,
        )
    except (npc.CompletionApplyError, npc.CompletionPostCommitError):
        raise
    except BaseException as exc:
        current_state = _stable_transport_target_state(
            path,
            maximum_bytes=max(len(expected_source), len(content)),
        )
        if (
            source_state is not None
            and source_state[1] == expected_source
            and current_state == source_state
        ):
            raise npc.CompletionApplyError(
                "seq77 checkpoint transport failed before checkpoint replacement; "
                "the exact source remains published and retry is safe"
            ) from exc
        raise npc.CompletionPostCommitError(
            "seq77 checkpoint transport ended with an unclassified failure; "
            "published state is uncertain"
        ) from exc


def _stable_transport_target_state(
    path: Path,
    *,
    maximum_bytes: int,
) -> tuple[tuple[int, ...], bytes] | None:
    """Read the publication target only when its private identity stays stable."""

    def identity(metadata: os.stat_result) -> tuple[int, ...]:
        return (
            metadata.st_dev,
            metadata.st_ino,
            metadata.st_mode,
            metadata.st_uid,
            metadata.st_gid,
            metadata.st_nlink,
            metadata.st_size,
            metadata.st_mtime_ns,
            metadata.st_ctime_ns,
        )

    descriptor: int | None = None
    try:
        before = path.lstat()
        if (
            not stat.S_ISREG(before.st_mode)
            or stat.S_IMODE(before.st_mode) != 0o600
            or before.st_uid != os.geteuid()
            or before.st_nlink != 1
            or before.st_size > maximum_bytes
        ):
            return None
        descriptor = os.open(
            path,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
        opened = os.fstat(descriptor)
        if identity(opened) != identity(before):
            return None
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(
                descriptor,
                min(1024 * 1024, maximum_bytes + 1 - total),
            )
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            if total > maximum_bytes:
                return None
        after_open = os.fstat(descriptor)
        after_name = path.lstat()
        if (
            identity(after_open) != identity(before)
            or identity(after_name) != identity(before)
        ):
            return None
        return identity(after_name), b"".join(chunks)
    except BaseException:
        return None
    finally:
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                pass


def write_checkpoint(
    prepared: Prepared,
    *,
    atomic_writer: Callable[..., None] = npc.atomic_write,
) -> None:
    refreshed = prepare(
        prepared.root,
        projected_validator=prepared.projected_validator,
        event_occurred_at=prepared.event_occurred_at,
    )
    require(
        refreshed.source_bytes == prepared.source_bytes
        and refreshed.event_occurred_at == prepared.event_occurred_at
        and refreshed.projected_bytes == prepared.projected_bytes
        and refreshed.candidate_catalogs == prepared.candidate_catalogs
        and refreshed.final_sha256_by_path == prepared.final_sha256_by_path
        and refreshed.physical_sha256_by_path == prepared.physical_sha256_by_path
        and refreshed.source_universe == prepared.source_universe,
        "seq77 projection or physical authority changed before write",
    )
    cohort = npc.retain_physical_pin_cohort(
        prepared.root, refreshed.physical_sha256_by_path
    )
    primary: BaseException | None = None
    published = False
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
                    "seq77 managed closure changed at commit",
                )
                npc._require_git_visible_changes_are_managed(
                    prepared.root,
                    prepared.final_sha256_by_path,
                    changed_path_loader=changed_path_loader,
                )

            verify_closure()
            prepared.projected_validator(prepared.root, prepared.projected_bytes)
            verify_closure()

        _write_checkpoint_transport(
            atomic_writer,
            prepared.checkpoint_path,
            prepared.projected_bytes,
            expected_source=prepared.source_bytes,
            commit_guard=commit_guard,
        )
        published = True
    except BaseException as exc:
        primary = exc
        raise
    finally:
        try:
            cohort.close(primary)
        except BaseException as cleanup_error:
            if published or isinstance(primary, npc.CompletionPostCommitError):
                raise npc.CompletionPostCommitError(
                    "seq77 checkpoint transport completed but retained cohort "
                    "cleanup failed; published state is uncertain"
                ) from cleanup_error
            if primary is None:
                raise npc.CompletionPostCommitError(
                    "seq77 retained cohort cleanup failed at an ambiguous "
                    "publication boundary"
                ) from cleanup_error
            try:
                primary.add_note(
                    "seq77 retained cohort cleanup also failed: "
                    f"{type(cleanup_error).__name__}: {cleanup_error}"
                )
            except BaseException:
                pass


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--refresh-catalogs", action="store_true")
    mode.add_argument("--write", action="store_true")
    return parser.parse_args(argv)


def _write_raw_exact(stream: Any, content: bytes) -> None:
    descriptor = stream.fileno()
    require(type(descriptor) is int and descriptor >= 0, "output descriptor differs")
    offset = 0
    while offset < len(content):
        written = os.write(descriptor, content[offset:])
        require(
            type(written) is int and 0 < written <= len(content) - offset,
            "output write made invalid progress",
        )
        offset += written


def _write_postcommit_diagnostic(message: str) -> None:
    try:
        _write_raw_exact(sys.stderr, f"{message}\n".encode())
    except BaseException:
        pass


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    checkpoint_published = False
    try:
        if args.refresh_catalogs:
            stale = prepare(args.root, allow_stale_catalogs=True)
            write_catalogs(stale)
            prepare(args.root)
        else:
            prepared = prepare(args.root)
            if args.write:
                write_checkpoint(prepared)
                checkpoint_published = True
        try:
            _write_raw_exact(
                sys.stdout,
                b"WalkSafe FP-046 R002 start-control reanchor seq77: PASS\n",
            )
        except BaseException as exc:
            if checkpoint_published:
                raise npc.CompletionPostCommitError(
                    "published seq77 checkpoint PASS output delivery is uncertain"
                ) from exc
            raise
    except npc.CompletionPostCommitError as exc:
        _write_postcommit_diagnostic(
            "WalkSafe FP-046 R002 start-control reanchor seq77: "
            f"POSTCOMMIT-UNCERTAIN: {exc}"
        )
        return 2
    except (
        ControlReanchorError,
        OSError,
        ValueError,
        TypeError,
        npc.BuildError,
        npc.CompletionApplyError,
        catalogs.CatalogError,
        _review_module().ReviewError,
    ) as exc:
        print(f"WalkSafe FP-046 R002 start-control reanchor seq77: FAIL: {exc}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
