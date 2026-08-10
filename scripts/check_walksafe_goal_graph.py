#!/usr/bin/env python3
"""Validate the WalkSafe dependency-graph Goal package and runtime checkpoint."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tomllib
from collections import Counter
from contextlib import contextmanager
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Iterator


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_goal_package as legacy  # noqa: E402
from scripts import check_walksafe_project_continuation as continuation  # noqa: E402


CHECKPOINT_RELATIVE = Path("docs/control/walksafe-project-continuation-checkpoint.json")
PACKAGE_RELATIVE = Path("docs/control/goals/walksafe-completion-graph-v2-2")
MANIFEST_RELATIVE = PACKAGE_RELATIVE / "static-plan-manifest-v2.2.0.json"
EXPECTED_PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-2"
EXPECTED_PLAN_VERSION = "2.2.0"
EXPECTED_ROOT_GOAL_ID = "WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"
EXPECTED_INITIAL_FOCUS_GOAL_ID = "WS-GOAL-EPIC-02-FP-018-R001"
EXPECTED_PREACTIVATION_COMPLETION_EVIDENCE_BY_GOAL = {
    "WS-GOAL-EPIC-01": [
        "EPIC01_PHASE_G_RECORD",
        "IMPLEMENTATION_BACKLOG",
    ],
}
EXPECTED_MANIFEST_SHA256 = (
    "c761ed6d9ac83fb98a2dded6eb99b639610e67a7bf13bdd306f8be38e996577b"
)
EXPECTED_INITIAL_EVENT_SHA256 = (
    "90c16c3ac5d6156a0c64874d87ce472c8f47f9da50daa4ce98e6b5e7fa693ab1"
)
EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256 = (
    "d3b4d27c86bec6c6f2c53b6ebc5c71a653483754ce467a12584781fde21ed01c"
)
EXPECTED_PACKAGE_ACTIVATION_EVENT_SHA256 = (
    "d21327bb087478cb00ee829343113845e4005aff4c40e2eb4ca7b5dde16f1951"
)
EXPECTED_V1_MANIFEST_SHA256 = (
    "e8a4d078c5be8429ec363b6135247504eb4411d356c08b645cd9feba534ff43e"
)
EXPECTED_V1_EVENT_SHA256 = (
    "04bd265dbffba57f6ec9103b4bceeee8cc890a341ba71ca56fd501cb93d34438"
)
EXPECTED_V2_MANIFEST_SHA256 = (
    "ce542aeec040431cdfd473bff54a393a178649b770e0f20e14f3417bfce15847"
)
EXPECTED_V2_EVENT_SHA256 = (
    "fd028de110ecb233255ecd535757bf97bc180e0d62bfc8d4bd1446acd9e2dc21"
)
EXPECTED_V2_PATH_SET_SHA256 = (
    "7854ff69413cde16b6b73a8868be3e7b005e1d052981231e956e1ce9fe571ea2"
)
EXPECTED_V2_CONTENT_SET_SHA256 = (
    "bd76332410a740a3f74e11f34c6bfbf0779e60f7f7ee9b7ea2eb066eae620080"
)
V21_PACKAGE_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-1"
)
V21_MANIFEST_RELATIVE = (
    V21_PACKAGE_RELATIVE / "static-plan-manifest-v2.1.0.json"
)
EXPECTED_V21_PACKAGE_ID = (
    "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-1"
)
EXPECTED_V21_PLAN_VERSION = "2.1.0"
EXPECTED_V21_MANIFEST_SHA256 = (
    "d8eccbe651c89895b9d1830d7af63b3d18f9bcf23683a5b0f61cc54eb5b01649"
)
EXPECTED_V21_INITIAL_EVENT_SHA256 = (
    "b2a1b08556ee11713854dcafd4df9107860eeab563b1e3cfe1a191384b1bb425"
)
EXPECTED_V21_PATH_SET_SHA256 = (
    "28b034519b85127019410487df6b61cf1ee6010ed626659c4d0a0380d38bd1b3"
)
EXPECTED_V21_CONTENT_SET_SHA256 = (
    "1462f0366b7fa6ae2a2f9499c3b1d1a4860abf6e17ee5e2e38ff626d5e1ce2d0"
)
EXPECTED_STANDING_EXECUTION_AUTHORITY = [
    "REPOSITORY_SCOPED_IMPLEMENTATION",
    "INTERNAL_VERIFICATION",
    "DRAFT_AUTHORING",
    "ACTIVE_FACT_RECORDING",
    "DELEGATED_INTERNAL_DOCUMENT_APPROVAL",
    "SUCCESSOR_TRACE_GENERATION",
    "CHECKPOINT_DAYLOG_MEMORY_UPDATE",
    "DEPENDENCY_READY_NEXT_GOAL_START",
]
EXPECTED_EXTERNAL_ACTION_REQUIRED_FOR = [
    "NORMATIVE_POLICY_CHANGE",
    "NORMATIVE_OR_EXTERNAL_APPROVED_BASELINED_TRANSITION",
    "GATE_WAIVER",
    "FORMAL_TEST_PASS",
    "REAL_DEVICE_OR_PARTICIPANT_EXECUTION",
    "SECRET_PAID_OR_PRODUCTION_RESOURCE",
    "DESTRUCTIVE_EXTERNAL_ACTION",
    "INDEPENDENT_HUMAN_REVIEW",
    "RELEASE_ACCEPTANCE_HANDOVER_OR_CLOSURE_SIGNATURE",
]
EXPECTED_GATE_IDS = {
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
}
EXPECTED_INTEGRATION_CANDIDATE_ARTIFACT_CODES = {
    "DLV-AIML-14",
    "DLV-AIML-16",
    "DLV-AIML-21",
    "DLV-AIML-24",
    "DLV-DEV-01",
    "DLV-DEV-07",
    "DLV-DEV-08",
    "DLV-DEV-09",
    "DLV-DEV-12",
    "DLV-DEV-18",
    "DLV-DEV-19",
    "DLV-DEV-20",
}
EXPECTED_POLICY_GAP_MAPPING_SHA256 = (
    "3423815d07d130ab85c6729733b26c7061ae13f63b4f344dfecdc1c47c4f80b5"
)
EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS = [
    {
        "source_policy_id": "FP-017",
        "gap_id": "GAP-026",
        "completion_level": (
            "INTERNAL_SLICE_COMPLETE_NOT_FORMAL_IMPLEMENTED"
        ),
        "source_evidence_roles": [
            "EPIC02_PHASE_A_RECORD",
            "IMPLEMENTATION_BACKLOG",
        ],
    }
]
MANDATORY_DYNAMIC_CANONICAL_INPUT_ROLES = {
    "POLICY_BASELINE",
    "ARTIFACT_APPLICATION_RECEIPT",
    "ARTIFACT_REGISTER",
    "ARTIFACT_CHANGE_LOG",
    "REQUIREMENTS_TRACEABILITY",
    "DESIGN_TRACEABILITY",
    "MODULE_REGISTER",
    "PLANNED_TEST_CASES",
    "IMPLEMENTATION_GAP",
    "IMPLEMENTATION_BACKLOG",
}
SUCCESSOR_INVARIANT_FIELDS = {
    "artifact_trigger_evidence_refs",
    "artifact_work_reason",
    "work_item_id",
    "work_item_type",
    "parent_goal_id",
    "priority_rank",
    "source_policy_ids",
    "gap_ids",
    "target_completion_level",
    "canonical_input_roles",
    "source_blocker_ids",
    "output_subject_ids_by_role",
    "question_policy",
    "stop_policy",
}
PROHIBITED_LINEAR_FIELDS = {
    "phase_id",
    "phase_order",
    "current_phase_goal_id",
    "next_goal_id",
    "return_goal_id",
    "sequence",
}
GOAL_REQUIRED_FIELDS = {
    "schema_version",
    "goal_id",
    "goal_kind",
    "document_version",
    "parent_goal_id",
    "priority_rank",
    "initial_status",
    "target_completion_level",
    "work_item_id",
    "start_requires",
    "completion_requires",
    "child_goal_ids",
    "source_policy_ids",
    "gap_ids",
    "canonical_input_roles",
}
GOAL_ALLOWED_KINDS = {"MASTER", "WORKSTREAM", "WORK_ITEM"}
WORK_ITEM_RESULT_KINDS = {
    "IMPLEMENTATION_RECORD",
    "VERIFICATION_RESULT",
    "SUCCESSOR_TRACE",
}
POLICY_GAP_CATALOG_RESULT_KINDS = {
    "IMPLEMENTATION_RECORD",
    "VERIFICATION_RESULT",
}
INTERNAL_REASSESSMENT_TERMINAL_GAP_STATUSES = {
    "PARTIAL",
    "EVIDENCE_MISSING",
    "IMPLEMENTED",
}
EXTERNALLY_GOVERNED_ARTIFACT_DISPLAY_CODES = {
    "REQ-09",
    "REQ-10",
    "REQ-15",
    "TST-10",
    "TST-14",
    "TST-15",
    "TST-20",
    "TST-22",
    "TST-23",
    "SEC-05",
    "SEC-06",
    "SEC-07",
    "SEC-14",
    "SEC-16",
    "AIML-20",
    "AIML-23",
    "REL-02",
    "REL-06",
    "REL-07",
    "REL-13",
    "REL-14",
    "REL-20",
    "REL-21",
    "OPS-11",
    "OPS-13",
    "OPS-14",
    "OPS-17",
    "OPS-18",
    "OPS-23",
    *(f"WS-{index:02d}" for index in range(1, 23)),
    *(f"CLS-{index:02d}" for index in range(1, 17)),
}
EXTERNAL_EXECUTION_BLOCKERS = {
    "EXECUTION_NOT_RUN",
    "HUMAN_REVIEW_AFTER_EXECUTION",
    "PENDING_ACTIVATION_EVALUATION",
    "EVIDENCE_OR_EXTERNAL_VALUE_PENDING",
}
EXTERNAL_FACT_STATUS_TOKENS = {
    "ACCEPTED",
    "CLOSED",
    "COMPLETE",
    "DELIVERED",
    "DEPLOYED",
    "ELIGIBLE",
    "EXECUTED",
    "HANDOVER",
    "PASS",
    "RELEASED",
    "SIGNED",
    "VERIFIED",
}
CHECK_COMMAND_CONTRACT_VERSION = "2026-07-24.2"
QUICK_ACTIVATION_CHECKS = (
    (
        "CONTINUATION_QUICK",
        "python3 -B scripts/check_walksafe_project_continuation.py",
    ),
    (
        "GOAL_GRAPH_QUICK",
        "python3 -B scripts/check_walksafe_goal_graph.py",
    ),
)
IMPLEMENTATION_START_GATE_CHECKS = (
    (
        "CONTINUATION",
        "python3 -B scripts/check_walksafe_project_continuation.py",
    ),
    (
        "GOAL_GRAPH",
        "python3 -B scripts/check_walksafe_goal_graph.py",
    ),
    (
        "BASELINE_MATERIALIZATION",
        "python3 -B scripts/materialize_walksafe_artifact_baseline_approval_20260722.py --check",
    ),
    (
        "ANDROID_GATEWAY_BOUNDARY",
        "python3 -B scripts/check_walksafe_android_gateway_boundary_20260723.py --root .",
    ),
    (
        "NODE_TOOLCHAIN_PRE",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && WALKSAFE_NODE_ROOT="$(/usr/bin/dirname -- "${WALKSAFE_NODE_BIN_DIR}")" && test "${WALKSAFE_NODE_BIN_DIR}" = "${WALKSAFE_NODE_ROOT}/bin" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root "${WALKSAFE_NODE_ROOT}" --lock configs/walksafe_node_toolchain_lock_20260715.json',
    ),
    (
        "GATEWAY_TYPECHECK",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run typecheck',
    ),
    (
        "GATEWAY_TEST",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway test',
    ),
    (
        "GATEWAY_BUILD",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run build',
    ),
    (
        "WEB_TEST",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web test',
    ),
    (
        "WEB_LINT",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run lint',
    ),
    (
        "WEB_TYPECHECK",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run typecheck',
    ),
    (
        "WEB_BUILD",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run build',
    ),
    (
        "NODE_TOOLCHAIN_POST",
        ': "${WALKSAFE_NODE_BIN_DIR:?required}" && WALKSAFE_NODE_ROOT="$(/usr/bin/dirname -- "${WALKSAFE_NODE_BIN_DIR}")" && test "${WALKSAFE_NODE_BIN_DIR}" = "${WALKSAFE_NODE_ROOT}/bin" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root "${WALKSAFE_NODE_ROOT}" --lock configs/walksafe_node_toolchain_lock_20260715.json',
    ),
    (
        "ANDROID_UNIT_ASSEMBLE_LINT",
        "(cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon)",
    ),
    (
        "TEST_LAYER_REGISTRY_VALIDATE",
        'WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && PYTHON_BIN="${WALKSAFE_LOCKED_TEST_PYTHON}" bash scripts/run_walksafe_test_layers_20260711.sh validate',
    ),
    (
        "FIELD_AND_RELEASE_PYTEST",
        'WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_android_field_session_summary.py tests/test_release_evidence_gate.py -q',
    ),
    (
        "GOAL_CONTROL_PYTEST",
        'WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_walksafe_goal_graph.py tests/test_walksafe_project_continuation.py -q',
    ),
    (
        "CONTROL_AND_TRACE_PYTEST",
        'WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_walksafe_epic01_phase_b_trace_20260722.py tests/test_walksafe_epic01_phase_c_trace_20260722.py tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py tests/test_walksafe_android_gateway_boundary_20260723.py tests/test_walksafe_artifact_baseline_materialization_20260722.py --deselect=tests/test_walksafe_epic01_phase_b_trace_20260722.py::WalkSafeEpic01PhaseBTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_c_trace_20260722.py::WalkSafeEpic01PhaseCTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py::WalkSafeEpic01PhaseETraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py::WalkSafeEpic01PhaseFTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py::WalkSafeEpic01PhaseGTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py::WalkSafeEpic02PhaseATraceTest::test_generated_files_are_current_and_deterministic -q',
    ),
    (
        "REPOSITORY_STATE",
        ': "${WALKSAFE_GATE_EVENT_ID:?required}" && python3 -B scripts/check_walksafe_project_continuation.py --root . --checkpoint docs/control/walksafe-project-continuation-checkpoint.json --print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"',
    ),
)
QUICK_ACTIVATION_CHECK_IDS = tuple(
    check_id for check_id, _ in QUICK_ACTIVATION_CHECKS
)
IMPLEMENTATION_START_GATE_CHECK_IDS = tuple(
    check_id for check_id, _ in IMPLEMENTATION_START_GATE_CHECKS
)
QUICK_ACTIVATION_COMMAND_BY_ID = dict(QUICK_ACTIVATION_CHECKS)
IMPLEMENTATION_START_GATE_COMMAND_BY_ID = dict(
    IMPLEMENTATION_START_GATE_CHECKS
)
PRESTART_CONTROL_REPAIR_CHANGED_PATHS = (
    "scripts/check_walksafe_goal_graph.py",
    "tests/test_walksafe_goal_graph.py",
)
PRESTART_CONTROL_REPAIR_SEMANTIC_REGRESSION_COMMAND = (
    'WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_walksafe_goal_graph.py tests/test_walksafe_project_continuation.py -q '
    "--deselect=tests/test_walksafe_goal_graph.py::WalkSafeGoalGraphTest::test_current_goal_graph_is_valid "
    "--deselect=tests/test_walksafe_project_continuation.py::WalkSafeProjectContinuationTest::test_current_checkpoint_is_valid"
)
PRESTART_CONTROL_REPAIR_CHECKS = (
    (
        "FAIL_BEFORE",
        IMPLEMENTATION_START_GATE_COMMAND_BY_ID["GOAL_CONTROL_PYTEST"],
        1,
        "01-FAIL_BEFORE.log",
    ),
    (
        "PASS_AFTER",
        PRESTART_CONTROL_REPAIR_SEMANTIC_REGRESSION_COMMAND,
        0,
        "02-PASS_AFTER.log",
    ),
)
PRESTART_CONTROL_REPAIR_EVENT_FIELDS = frozenset(
    {
        "sequence",
        "event_id",
        "event_type",
        "occurred_at",
        "occurred_on",
        "source_checkpoint_version",
        "static_plan_manifest_sha256",
        "previous_event_sha256",
        "event_sha256",
        "status_changes",
        "from_status",
        "to_status",
        "focus_goal_id",
        "focus_goal_content_sha256",
        "previous_focus_goal_id",
        "previous_focus_content_sha256",
        "blockers_after",
        "blocker_resolution_ids_after",
        "evidence_refs",
        "runtime_after",
        "prestart_control_repair_receipt_binding",
        "repository_snapshot_before",
        "repository_snapshot_after",
    }
)
GATE_REPOSITORY_STATE_SCHEMA_VERSION = "1.0.0"
GATE_REPOSITORY_STATE_EVIDENCE_TYPE = "GATE_REPOSITORY_STATE"
GATE_REPOSITORY_CHECKPOINT_PATH = (
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
GATE_REPOSITORY_STATE_SNAPSHOT_SCOPE = (
    "COMPLETE_GIT_VISIBLE_DIRTY_STATE_EXCLUDING_CHECKPOINT_AND_CURRENT_GATE_EVENT"
)
GATE_REPOSITORY_STATE_CANONICALIZATION = {
    "encoding": "UTF-8",
    "json": "sort_keys=true,separators=(',',':'),ensure_ascii=false",
    "trailing_newline_in_cli_output": True,
}
GATE_REPOSITORY_STATE_GIT_STATUS_COMMAND = [
    "git",
    "status",
    "--porcelain=v2",
    "-z",
    "--untracked-files=all",
    "--ignore-submodules=none",
]
GATE_REPOSITORY_STATE_GIT_STATUS_CONFIG = {
    "status.renames": "true",
}
GATE_REPOSITORY_STATE_CHECK_NUMBER = 19


def check_command_contract_sha256(
    checks: tuple[tuple[str, str], ...],
) -> str:
    payload = {
        "contract_version": CHECK_COMMAND_CONTRACT_VERSION,
        "checks": [
            {"check_id": check_id, "command": command}
            for check_id, command in checks
        ],
    }
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


QUICK_ACTIVATION_CHECK_CONTRACT_SHA256 = check_command_contract_sha256(
    QUICK_ACTIVATION_CHECKS
)
IMPLEMENTATION_START_GATE_CHECK_CONTRACT_SHA256 = (
    check_command_contract_sha256(IMPLEMENTATION_START_GATE_CHECKS)
)
RUNTIME_STATUSES = {
    "PLANNED",
    "READY",
    "IN_PROGRESS",
    "AWAITING_USER",
    "AWAITING_EXTERNAL",
    "BLOCKED",
    "COMPLETE_AT_TARGET",
    "SUPERSEDED",
}
TERMINAL_STATUSES = {"COMPLETE_AT_TARGET", "SUPERSEDED"}
RUNNABLE_STATUSES = {"READY", "IN_PROGRESS"}
ACTIVATION_PACKAGE_PAIRS = {
    ("READY_NOT_ACTIVATED", "PREPARED_NOT_ACTIVATED"),
    ("ACTIVE", "ACTIVE"),
    ("COMPLETED", "COMPLETED"),
}
ALLOWED_STATUS_TRANSITIONS = {
    "PLANNED": {"PLANNED", "READY", "AWAITING_USER", "AWAITING_EXTERNAL", "BLOCKED", "SUPERSEDED"},
    "READY": {"READY", "IN_PROGRESS", "AWAITING_USER", "AWAITING_EXTERNAL", "BLOCKED", "SUPERSEDED"},
    "IN_PROGRESS": {
        "IN_PROGRESS",
        "READY",
        "AWAITING_USER",
        "AWAITING_EXTERNAL",
        "BLOCKED",
        "COMPLETE_AT_TARGET",
        "SUPERSEDED",
    },
    "AWAITING_USER": {
        "AWAITING_USER",
        "PLANNED",
        "READY",
        "IN_PROGRESS",
        "BLOCKED",
        "SUPERSEDED",
    },
    "AWAITING_EXTERNAL": {
        "AWAITING_EXTERNAL",
        "PLANNED",
        "READY",
        "IN_PROGRESS",
        "BLOCKED",
        "SUPERSEDED",
    },
    "BLOCKED": {"BLOCKED", "PLANNED", "READY", "IN_PROGRESS", "SUPERSEDED"},
    "COMPLETE_AT_TARGET": {"COMPLETE_AT_TARGET", "SUPERSEDED"},
    "SUPERSEDED": {"SUPERSEDED"},
}
DYNAMIC_EXTERNAL_TYPES = {
    "FORMAL_TEST_RUN",
    "RELEASE_GATE",
    "RELEASE_DECISION",
    "DEPLOYMENT_DELIVERY_EVENT",
    "OPERATION_EVENT",
    "HANDOVER_CLOSURE_EVENT",
    "BLOCKER_OR_EXTERNAL_RECEIPT",
}
TYPE_REQUIRED_EVIDENCE_ROLES = {
    "ARTIFACT_WORK": {"ARTIFACT_CHANGE_LOG"},
    "INTEGRATION_CANDIDATE": {"INTEGRATION_CANDIDATE_MANIFEST"},
    "FORMAL_TEST_RUN": {
        "TEST_PLAN_APPROVAL_RECEIPT",
        "FORMAL_TEST_REPORT",
        "ACTUAL_DEVICE_TEST_REPORT",
    },
    "RELEASE_GATE": {"RELEASE_GATE_CLOSURE_RECEIPT"},
    "RELEASE_DECISION": {
        "TEST_PLAN_APPROVAL_RECEIPT",
        "FORMAL_TEST_REPORT",
        "ACTUAL_DEVICE_TEST_REPORT",
        "RELEASE_GATE_CLOSURE_RECEIPT",
        "RELEASE_ELIGIBILITY_APPROVAL",
    },
    "DEPLOYMENT_DELIVERY_EVENT": {"PHASE_C_TECHNICAL_DELIVERY_RECEIPT"},
    "OPERATION_EVENT": {"OPERATION_EVENT_RECORD"},
    "HANDOVER_CLOSURE_EVENT": {
        "PHASE_D_OPERATION_HANDOVER_RECEIPT",
        "PHASE_D_PROJECT_CLOSURE_RECEIPT",
    },
    "BLOCKER_OR_EXTERNAL_RECEIPT": {"BLOCKER_RESOLUTION_RECEIPT"},
}
TYPE_EXECUTION_EVIDENCE_ROLES = {
    "INTEGRATION_CANDIDATE": {"INTEGRATION_CANDIDATE_MANIFEST"},
    "FORMAL_TEST_RUN": {
        "FORMAL_TEST_REPORT",
        "ACTUAL_DEVICE_TEST_REPORT",
    },
    "RELEASE_GATE": {"RELEASE_GATE_CLOSURE_RECEIPT"},
    "RELEASE_DECISION": {"RELEASE_ELIGIBILITY_APPROVAL"},
    "DEPLOYMENT_DELIVERY_EVENT": {"PHASE_C_TECHNICAL_DELIVERY_RECEIPT"},
    "OPERATION_EVENT": {"OPERATION_EVENT_RECORD"},
    "HANDOVER_CLOSURE_EVENT": {
        "PHASE_D_OPERATION_HANDOVER_RECEIPT",
        "PHASE_D_PROJECT_CLOSURE_RECEIPT",
    },
    "BLOCKER_OR_EXTERNAL_RECEIPT": {"BLOCKER_RESOLUTION_RECEIPT"},
}
START_EVIDENCE_STATUS_BY_ROLE = {
    "TEST_PLAN_APPROVAL_RECEIPT": "APPROVED",
    "INTEGRATION_CANDIDATE_MANIFEST": "BOUND",
    "FORMAL_TEST_REPORT": "PASS",
    "ACTUAL_DEVICE_TEST_REPORT": "PASS",
    "RELEASE_GATE_CLOSURE_RECEIPT": "CLOSED",
    "RELEASE_ELIGIBILITY_APPROVAL": "ELIGIBLE",
    "PHASE_C_TECHNICAL_DELIVERY_RECEIPT": "DELIVERED",
}
SPECIALIZED_COMPLETION_STATUS_BY_ROLE = {
    **START_EVIDENCE_STATUS_BY_ROLE,
    "PHASE_D_OPERATION_HANDOVER_RECEIPT": "HANDED_OVER",
    "PHASE_D_PROJECT_CLOSURE_RECEIPT": "CLOSED",
}
START_EVIDENCE_PRODUCER_TYPES_BY_ROLE = {
    "INTEGRATION_CANDIDATE_MANIFEST": {"INTEGRATION_CANDIDATE"},
    "FORMAL_TEST_REPORT": {"FORMAL_TEST_RUN"},
    "ACTUAL_DEVICE_TEST_REPORT": {"FORMAL_TEST_RUN"},
    "RELEASE_GATE_CLOSURE_RECEIPT": {"RELEASE_GATE"},
    "RELEASE_ELIGIBILITY_APPROVAL": {"RELEASE_DECISION"},
    "PHASE_C_TECHNICAL_DELIVERY_RECEIPT": {
        "DEPLOYMENT_DELIVERY_EVENT",
    },
}
NORMATIVE_IMPACT_ROLES = {
    "POLICY_BASELINE",
    "REQUIREMENTS_TRACEABILITY",
    "DESIGN_TRACEABILITY",
    "IMPLEMENTATION_GAP",
    "IMPLEMENTATION_BACKLOG",
}
SCOPED_IMPACT_ROLES = NORMATIVE_IMPACT_ROLES | {
    "ARTIFACT_REGISTER",
    "ARTIFACT_CHANGE_LOG",
    "MODULE_REGISTER",
    "PLANNED_TEST_CASES",
}
TRACE_TRANSLATION_ROLES = {
    "REQUIREMENTS_TRACEABILITY",
    "DESIGN_TRACEABILITY",
    "MODULE_REGISTER",
    "PLANNED_TEST_CASES",
}
SCOPED_CONTENT_KEYS_BY_ROLE = {
    "POLICY_BASELINE": {
        "composition",
        "remaining_gates",
    },
    "REQUIREMENTS_TRACEABILITY": {
        "requirements",
        "remaining_gates",
        "bound_policy_correction_candidates",
    },
    "DESIGN_TRACEABILITY": {
        "records",
        "remaining_gates",
        "bound_policy_correction_candidates",
    },
    "IMPLEMENTATION_GAP": {
        "assessments",
        "critical_findings",
    },
    "IMPLEMENTATION_BACKLOG": {
        "next_action_sequence",
        "epics",
    },
    "MODULE_REGISTER": {
        "known_source_policy_issues",
        "modules",
    },
    "PLANNED_TEST_CASES": {
        "test_cases",
    },
}
GLOBAL_CONTENT_KEYS_BY_ROLE = {
    "POLICY_BASELINE": {"release_status", "change_control"},
    "REQUIREMENTS_TRACEABILITY": {
        "authorization_boundary",
        "status_definitions",
        "artifact_management_plan",
        "requirement_change_tracking",
    },
    "DESIGN_TRACEABILITY": {
        "authorization_boundary",
        "product_boundary",
    },
    "IMPLEMENTATION_GAP": {
        "assessment_method",
        "known_baseline_consistency_risk",
        "authorization_boundary",
    },
    "IMPLEMENTATION_BACKLOG": {
        "estimation_rule",
        "target_completion_model",
        "current_status_model",
        "priority_rule",
        "execution_order",
        "authorization_boundary",
    },
    "MODULE_REGISTER": {
        "platform_boundary",
        "approval_boundary",
        "trace_integration_boundary",
    },
    "PLANNED_TEST_CASES": {
        "execution_record_boundary",
        "approval_boundary",
    },
}
NON_IMPACT_PROVENANCE_TOP_LEVEL_KEYS_BY_ROLE = {
    "IMPLEMENTATION_GAP": {
        "ad_hoc_validation",
        "decision_precedence",
        "evidence_catalog",
        "limitations",
        "purpose",
        "reassessment_scope",
        "summary",
    },
    "MODULE_REGISTER": {
        "assignment_metrics",
    },
    "PLANNED_TEST_CASES": {
        "summary",
    },
}
NON_RETROACTIVE_LEDGER_ROLES = {
    "ARTIFACT_REGISTER",
    "ARTIFACT_CHANGE_LOG",
}
NON_IMPACT_CONTROL_ROLES = {
    "ARTIFACT_APPLICATION_RECEIPT",
}
INTERNAL_PRODUCER_OUTPUT_ROLES_BY_TYPE = {
    "POLICY_GAP_WORK": {
        "IMPLEMENTATION_GAP",
        "IMPLEMENTATION_BACKLOG",
    },
    "ARTIFACT_WORK": {
        "ARTIFACT_REGISTER",
        "ARTIFACT_CHANGE_LOG",
        "REQUIREMENTS_TRACEABILITY",
        "DESIGN_TRACEABILITY",
        "MODULE_REGISTER",
        "PLANNED_TEST_CASES",
        "IMPLEMENTATION_GAP",
        "IMPLEMENTATION_BACKLOG",
    },
}
CANONICAL_PRODUCER_WORK_TYPES = set(INTERNAL_PRODUCER_OUTPUT_ROLES_BY_TYPE)
DYNAMIC_START_CONTRACTS = {
    "POLICY_GAP_WORK": {
        "allowed_parent_scope": "IMPLEMENTATION_WORKSTREAM",
        "required_start_evidence_roles": [],
        "required_start_goal_type_counts": {},
        "same_candidate_required": False,
    },
    "ARTIFACT_WORK": {
        "allowed_parent_scope": "READY_CONTAINER",
        "required_start_evidence_roles": [],
        "required_start_goal_type_counts": {},
        "same_candidate_required": False,
        "materialization_output_subject_scope_required": True,
        "materialization_reason_required": True,
        "trigger_evidence_required_for_event_driven_update": True,
        "one_artifact_code_per_work_item": True,
        "pending_evaluation_allowed_only_for_applicability_decision": True,
        "required_output_roles": [
            "ARTIFACT_CHANGE_LOG",
            "ARTIFACT_REGISTER",
        ],
    },
    "INTEGRATION_CANDIDATE": {
        "allowed_parent_scope": "EPIC_11_OR_MASTER",
        "required_start_evidence_roles": [],
        "required_start_goal_type_counts": {},
        "required_artifact_subject_codes_from_contract": (
            "artifact_graph_contract.integration_candidate_required_artifact_codes"
        ),
        "required_start_goal_ids_by_parent": {
            "WS-GOAL-EPIC-11": [
                "WS-GOAL-EPIC-02",
                "WS-GOAL-EPIC-03",
                "WS-GOAL-EPIC-04",
                "WS-GOAL-EPIC-05",
                "WS-GOAL-EPIC-06",
                "WS-GOAL-EPIC-07",
                "WS-GOAL-EPIC-08",
                "WS-GOAL-EPIC-09",
                "WS-GOAL-EPIC-10",
            ],
            EXPECTED_ROOT_GOAL_ID: [
                "WS-GOAL-EPIC-02",
                "WS-GOAL-EPIC-03",
                "WS-GOAL-EPIC-04",
                "WS-GOAL-EPIC-05",
                "WS-GOAL-EPIC-06",
                "WS-GOAL-EPIC-07",
                "WS-GOAL-EPIC-08",
                "WS-GOAL-EPIC-09",
                "WS-GOAL-EPIC-10",
                "WS-GOAL-EPIC-11",
            ],
        },
        "same_candidate_required": False,
    },
    "FORMAL_TEST_RUN": {
        "allowed_parent_scope": "EPIC_12",
        "required_start_evidence_roles": [
            "TEST_PLAN_APPROVAL_RECEIPT",
            "INTEGRATION_CANDIDATE_MANIFEST",
        ],
        "required_start_goal_type_counts": {"INTEGRATION_CANDIDATE": 1},
        "same_candidate_required": True,
    },
    "RELEASE_GATE": {
        "allowed_parent_scope": "EPIC_12",
        "required_start_evidence_roles": [
            "TEST_PLAN_APPROVAL_RECEIPT",
            "INTEGRATION_CANDIDATE_MANIFEST",
        ],
        "required_start_goal_type_counts": {"INTEGRATION_CANDIDATE": 1},
        "same_candidate_required": True,
    },
    "RELEASE_DECISION": {
        "allowed_parent_scope": "EPIC_12",
        "required_start_evidence_roles": [
            "TEST_PLAN_APPROVAL_RECEIPT",
            "INTEGRATION_CANDIDATE_MANIFEST",
            "FORMAL_TEST_REPORT",
            "ACTUAL_DEVICE_TEST_REPORT",
            "RELEASE_GATE_CLOSURE_RECEIPT",
        ],
        "required_start_goal_type_counts": {
            "INTEGRATION_CANDIDATE": 1,
            "FORMAL_TEST_RUN": 1,
            "RELEASE_GATE": 5,
        },
        "same_candidate_required": True,
    },
    "DEPLOYMENT_DELIVERY_EVENT": {
        "allowed_parent_scope": "MASTER",
        "required_start_evidence_roles": [
            "RELEASE_ELIGIBILITY_APPROVAL",
            "INTEGRATION_CANDIDATE_MANIFEST",
        ],
        "required_start_goal_type_counts": {"RELEASE_DECISION": 1},
        "required_start_goal_ids": ["WS-GOAL-EPIC-12"],
        "same_candidate_required": True,
    },
    "OPERATION_EVENT": {
        "allowed_parent_scope": "MASTER",
        "required_start_evidence_roles": [
            "PHASE_C_TECHNICAL_DELIVERY_RECEIPT",
        ],
        "required_start_goal_type_counts": {
            "DEPLOYMENT_DELIVERY_EVENT": 1,
        },
        "same_candidate_required": False,
    },
    "HANDOVER_CLOSURE_EVENT": {
        "allowed_parent_scope": "MASTER",
        "required_start_evidence_roles": [
            "PHASE_C_TECHNICAL_DELIVERY_RECEIPT",
        ],
        "required_start_goal_type_counts": {
            "DEPLOYMENT_DELIVERY_EVENT": 1,
            "OPERATION_EVENT": 1,
        },
        "same_candidate_required": False,
    },
    "BLOCKER_OR_EXTERNAL_RECEIPT": {
        "allowed_parent_scope": "READY_CONTAINER",
        "required_start_evidence_roles": [],
        "required_start_goal_type_counts": {},
        "same_candidate_required": False,
    },
}
ALLOWED_EVENT_TYPES = {
    "PACKAGE_PREPARED",
    "PACKAGE_ACTIVATED",
    "PRESTART_CONTROL_REPAIR_COMMITTED",
    "CANONICAL_BINDINGS_UPDATED",
    "GOAL_MATERIALIZED",
    "GOAL_READY",
    "GOAL_STARTED",
    "WORK_SESSION_RESUMED",
    "GOAL_COMPLETED",
    "GOAL_FOCUS_CHANGED",
    "GOAL_SUPERSEDED",
    "BLOCKER_RECORDED",
    "BLOCKER_RESOLVED",
    "PACKAGE_COMPLETED",
}
SHA256_PATTERN = legacy.SHA256_PATTERN
SAFE_ID_PATTERN = re.compile(r"[A-Za-z0-9._-]+")
DIRECT_EVENT_RECEIPT_MAX_JSON_NESTING = 128


def load_json(path: Path) -> dict[str, Any]:
    return legacy.load_json(path)


def sha256_file(path: Path) -> str:
    return legacy.sha256_file(path)


def resolve_safe_repo_file(root: Path, relative: Any) -> Path | None:
    return legacy.resolve_safe_repo_file(root, relative)


def parse_goal(path: Path) -> tuple[dict[str, Any], str]:
    return legacy.parse_goal(path)


def canonical_binding_map(checkpoint: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return legacy.canonical_binding_map(checkpoint)


def canonical_binding_snapshot(
    bindings: dict[str, dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    fields = ("role", "document_id", "path", "file_sha256")
    return {
        role: {field: binding.get(field) for field in fields}
        for role, binding in sorted(bindings.items())
        if isinstance(role, str) and isinstance(binding, dict)
    }


def validate_canonical_binding_snapshot(
    root: Path,
    value: Any,
    *,
    label: str,
) -> tuple[list[str], dict[str, dict[str, Any]]]:
    errors: list[str] = []
    if not isinstance(value, dict):
        return [f"{label}: canonical binding snapshot must be an object"], {}
    normalized: dict[str, dict[str, Any]] = {}
    required_fields = {"role", "document_id", "path", "file_sha256"}
    for role, binding in value.items():
        if not isinstance(role, str) or not role or not isinstance(binding, dict):
            errors.append(f"{label}: canonical binding snapshot entry is malformed")
            continue
        if set(binding) != required_fields or binding.get("role") != role:
            errors.append(f"{label}: canonical binding snapshot fields differ: {role}")
            continue
        document_id = binding.get("document_id")
        relative = binding.get("path")
        digest = binding.get("file_sha256")
        if not isinstance(document_id, str) or not document_id:
            errors.append(f"{label}: canonical binding document ID is missing: {role}")
        resolved = resolve_safe_repo_file(root, relative)
        if resolved is None:
            errors.append(f"{label}: canonical binding path is unsafe or missing: {role}")
        if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            errors.append(f"{label}: canonical binding SHA-256 is invalid: {role}")
        elif resolved is not None and sha256_file(resolved) != digest:
            errors.append(f"{label}: canonical binding SHA-256 differs: {role}")
        normalized[role] = dict(binding)
    return errors, normalized


def materialization_source_snapshot(value: dict[str, Any]) -> dict[str, Any]:
    return {
        "role": value.get("materialized_from_role"),
        "document_id": value.get("materialized_from_document_id"),
        "path": value.get("materialized_from_path"),
        "file_sha256": value.get("materialized_from_sha256"),
    }


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_json_sha256(value: Any, *, omit: set[str] | None = None) -> str:
    if isinstance(value, dict) and omit:
        value = {key: item for key, item in value.items() if key not in omit}
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def event_sha256(event: dict[str, Any]) -> str:
    return canonical_json_sha256(event, omit={"event_sha256"})


def package_hashes(root: Path, relative_paths: list[str]) -> tuple[str, str]:
    return legacy.path_and_content_hashes(root, relative_paths)


def string_list(value: Any) -> list[str]:
    return legacy.string_list(value)


def parse_iso_datetime(value: Any) -> datetime | None:
    return legacy.parse_iso_datetime(value)


def discover_package_paths(root: Path) -> list[str]:
    package_root = root / PACKAGE_RELATIVE
    return sorted(
        path.relative_to(root).as_posix()
        for path in package_root.rglob("*")
        if path.is_file()
    )


def load_manifest(root: Path) -> tuple[list[str], dict[str, Any]]:
    path = resolve_safe_repo_file(root, MANIFEST_RELATIVE.as_posix())
    if path is None:
        return ["Goal graph manifest is missing or unsafe"], {}
    try:
        manifest = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"Goal graph manifest cannot be loaded: {exc}"], {}
    return [], manifest


def manifest_node_map(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    graph = manifest.get("goal_graph")
    if not isinstance(graph, dict):
        return {}
    nodes = graph.get("static_nodes")
    if not isinstance(nodes, list):
        return {}
    return {
        node["goal_id"]: node
        for node in nodes
        if isinstance(node, dict) and isinstance(node.get("goal_id"), str)
    }


def validate_manifest(
    root: Path,
    checkpoint_state: dict[str, Any],
    manifest: dict[str, Any],
    discovered_paths: list[str],
) -> list[str]:
    errors: list[str] = []
    manifest_path = resolve_safe_repo_file(root, MANIFEST_RELATIVE.as_posix())
    if manifest_path is None:
        return ["Goal graph manifest is missing or unsafe"]
    manifest_hash = sha256_file(manifest_path)
    if EXPECTED_MANIFEST_SHA256.startswith("__FINALIZE_"):
        errors.append("Goal graph manifest checker trust anchor is not finalized")
    elif manifest_hash != EXPECTED_MANIFEST_SHA256:
        errors.append("Goal graph manifest differs from checker trust anchor")
    if checkpoint_state.get("static_plan_manifest_path") != MANIFEST_RELATIVE.as_posix():
        errors.append("Goal graph manifest path differs from checkpoint")
    if checkpoint_state.get("static_plan_manifest_sha256") != manifest_hash:
        errors.append("Goal graph manifest SHA-256 differs from checkpoint")
    if checkpoint_state.get("static_plan_version") != EXPECTED_PLAN_VERSION:
        errors.append("Goal graph plan version differs from checkpoint")
    if manifest.get("schema_version") != "2.0":
        errors.append("Goal graph manifest schema differs")
    if manifest.get("package_id") != EXPECTED_PACKAGE_ID:
        errors.append("Goal graph manifest package ID differs")
    if manifest.get("plan_version") != EXPECTED_PLAN_VERSION:
        errors.append("Goal graph manifest version differs")

    supersedes = manifest.get("supersedes")
    if not isinstance(supersedes, dict):
        errors.append("Goal graph manifest supersedes record is missing")
    else:
        if (
            supersedes.get("package_id")
            != EXPECTED_V21_PACKAGE_ID
            or supersedes.get("plan_version") != EXPECTED_V21_PLAN_VERSION
            or supersedes.get("manifest_path")
            != V21_MANIFEST_RELATIVE.as_posix()
            or supersedes.get("manifest_sha256")
            != EXPECTED_V21_MANIFEST_SHA256
            or supersedes.get("initial_transition_event_path")
            != (
                f"{PACKAGE_RELATIVE.as_posix()}/"
                "superseded-v2.1.0-package-prepared-event.json"
            )
            or supersedes.get("initial_transition_event_sha256")
            != EXPECTED_V21_INITIAL_EVENT_SHA256
            or supersedes.get("supersession_record_path")
            != (
                f"{PACKAGE_RELATIVE.as_posix()}/"
                "preactivation-supersession-record-v2.1.0.json"
            )
        ):
            errors.append("superseded v2.1 package binding differs")
        if supersedes.get("activation_status") != "READY_NOT_ACTIVATED":
            errors.append(
                "superseded v2.1 package was not recorded as unactivated"
            )

    orchestration = manifest.get("orchestration")
    if not isinstance(orchestration, dict):
        errors.append("Goal graph orchestration contract is missing")
    else:
        expected = {
            "model": "DEPENDENCY_DAG_READY_FRONTIER",
            "fixed_stage_count": False,
            "single_focus_goal": True,
            "dynamic_goal_materialization": True,
            "standing_control_is_stage": False,
            "backlog_execution_order_semantics": (
                "PRIORITY_TIE_BREAKER_ONLY_NOT_DEPENDENCY"
            ),
        }
        for field, value in expected.items():
            if orchestration.get(field) != value:
                errors.append(f"Goal graph orchestration {field} differs")

    artifact_contract = manifest.get("artifact_graph_contract")
    if not isinstance(artifact_contract, dict):
        errors.append("artifact graph contract is missing")
    else:
        for field, value in (
            ("artifact_count", 257),
            ("dependency_edge_count", 698),
            ("cycle_count", 0),
            ("upstream_downstream_reverse_reference_required", True),
            ("duplicate_edge_prohibited", True),
            ("bundle_file_count_is_not_goal_count", True),
        ):
            if artifact_contract.get(field) != value:
                errors.append(f"artifact graph contract {field} differs")
        if set(
            string_list(
                artifact_contract.get(
                    "integration_candidate_required_artifact_codes"
                )
            )
        ) != EXPECTED_INTEGRATION_CANDIDATE_ARTIFACT_CODES:
            errors.append(
                "integration candidate artifact subject contract differs"
            )

    scheduling_contract = manifest.get("artifact_work_scheduling_contract")
    expected_scheduling_contract = {
        "source_role": "ARTIFACT_REGISTER",
        "queue_field": "artifact_work_queue",
        "partition_statuses": [
            "INACTIVE",
            "LIVE_GOAL",
            "STRUCTURALLY_DUE",
            "TERMINAL",
            "WAITING_APPLICABILITY",
            "WAITING_TRIGGER",
            "WAITING_UPSTREAM",
        ],
        "partition_covers_all_artifact_codes_exactly_once": True,
        "one_artifact_code_per_work_item": True,
        "live_output_subject_overlap_prohibited": True,
        "structurally_due_requires_materialization": True,
        "pending_evaluation_requires_explicit_disposition": True,
        "recompute_after_artifact_register_update": True,
        "waiting_applicability_and_trigger_are_nonterminal": True,
        "assessment_after_internal_ready_frontier_exhausted": True,
        "assessment_target_requires_materialization_or_terminal_disposition": True,
        "selection_policy": (
            "PRIORITY_THEN_UNLOCK_DESC_THEN_TOPO_RANK_THEN_ARTIFACT_CODE"
        ),
        "allowed_work_reasons": [
            "ACTIVE_EVENT_UPDATE",
            "APPLICABILITY_DECISION",
            "DRAFT_COMPLETION",
            "PLANNED_EVIDENCE",
        ],
    }
    if scheduling_contract != expected_scheduling_contract:
        errors.append("artifact work scheduling contract differs")

    policy_contract = manifest.get("policy_gap_contract")
    if not isinstance(policy_contract, dict):
        errors.append("policy/Gap contract is missing")
    else:
        if policy_contract.get("expected_mapping_count") != 68:
            errors.append("policy/Gap mapping count contract differs")
        if policy_contract.get(
            "expected_mapping_sha256"
        ) != EXPECTED_POLICY_GAP_MAPPING_SHA256:
            errors.append("policy/Gap mapping identity contract differs")
        if policy_contract.get(
            "bootstrap_consumed_policy_gap_pairs"
        ) != EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS:
            errors.append(
                "policy/Gap bootstrap consumed pair contract differs"
            )
        if policy_contract.get("work_item_pair_cardinality") != 1:
            errors.append("policy/Gap Work Item cardinality differs")
        for field in (
            "effective_policy_subject_set_must_match_mapping",
            "mapping_identity_changes_require_successor_package",
            "gap_backlog_pair_is_atomic",
            "bootstrap_consumed_pair_is_workstream_coverage_only",
        ):
            if policy_contract.get(field) is not True:
                errors.append(f"policy/Gap contract {field} differs")

    dynamic_contracts = dynamic_contract_map(manifest)
    if set(dynamic_contracts) != set(DYNAMIC_START_CONTRACTS):
        errors.append("dynamic start contract type set differs")
    for work_type, expected in DYNAMIC_START_CONTRACTS.items():
        contract = dynamic_contracts.get(work_type, {})
        expected_statuses = {
            role: START_EVIDENCE_STATUS_BY_ROLE[role]
            for role in expected["required_start_evidence_roles"]
        }
        expected_fields = {
            **expected,
            "required_start_evidence_status_by_role": expected_statuses,
        }
        for field, value in expected_fields.items():
            if contract.get(field) != value:
                errors.append(
                    f"dynamic start contract differs: {work_type}.{field}"
                )

    inventory_contract = manifest.get("dynamic_inventory_contract")
    if not isinstance(inventory_contract, dict):
        errors.append("dynamic Goal inventory contract is missing")
    else:
        if inventory_contract.get(
            "static_manifest_changes_when_dynamic_goal_is_added"
        ) is not False:
            errors.append("dynamic Goal inventory would mutate the static manifest")
        if inventory_contract.get("materialized_goal_is_immutable") is not True:
            errors.append("dynamic Goal immutability contract differs")
        if inventory_contract.get(
            "source_binding_is_creation_time_snapshot"
        ) is not True:
            errors.append("dynamic Goal source binding snapshot contract differs")
        if set(
            string_list(
                inventory_contract.get(
                    "mandatory_dynamic_canonical_input_roles"
                )
            )
        ) != MANDATORY_DYNAMIC_CANONICAL_INPUT_ROLES:
            errors.append("dynamic Goal mandatory canonical input roles differ")
        if inventory_contract.get(
            "active_dependency_may_reference_superseded_revision"
        ) is not False:
            errors.append("dynamic Goal superseded dependency contract differs")
        if inventory_contract.get(
            "work_item_completion_requires_subset_of_start_requires"
        ) is not True:
            errors.append("dynamic Goal dependency readiness contract differs")
        if inventory_contract.get(
            "work_item_dependency_container_scope"
        ) != "SAME_OR_TRANSITIVE_UPSTREAM_OR_MASTER_LEVEL":
            errors.append("dynamic Goal dependency container scope differs")
        required_inventory_fields = {
            "artifact_trigger_evidence_refs",
            "artifact_work_reason",
            "goal_id",
            "path",
            "sha256",
            "goal_kind",
            "work_item_type",
            "parent_goal_id",
            "materialized_event_sha256",
            "materialized_from_role",
            "materialized_from_path",
            "materialized_from_document_id",
            "materialized_from_sha256",
            "predecessor_goal_id",
            "predecessor_goal_content_sha256",
            "supersedes_goal_id",
            "supersedes_goal_content_sha256",
        }
        if set(string_list(inventory_contract.get("required_fields"))) != (
            required_inventory_fields
        ):
            errors.append("dynamic Goal inventory required fields differ")
    transition_contract = manifest.get("transition_contract")
    expected_transition_contract = {
        "initial_event_is_immutable": True,
        "subsequent_events_are_append_only": True,
        "current_head_is_derived_from_replay": True,
        "validation_cutoff_is_snapshot_specific": True,
        "activation_event_required_before_execution": True,
        "activation_event_changes_goal_status": False,
        "activation_requires_user_request_and_quick_check_receipt": True,
        "activation_authorization_requires_checker_trust_anchor": True,
        "activation_quick_gate_is_separate_from_authorization": True,
        "activation_quick_gate_is_bound_by_activation_event": True,
        "execution_start_event": "GOAL_STARTED",
        "execution_resume_event": "WORK_SESSION_RESUMED",
        "execution_must_begin_after_goal_started": True,
        "goal_started_requires_fresh_full_implementation_start_gate": True,
        "work_session_resume_requires_fresh_full_implementation_start_gate": True,
        "current_work_session_can_be_asserted_by_checker_argument": True,
        "quick_activation_and_full_start_gates_are_distinct": True,
        "check_command_contract_version": CHECK_COMMAND_CONTRACT_VERSION,
        "quick_activation_check_ids": list(
            QUICK_ACTIVATION_CHECK_IDS
        ),
        "quick_activation_check_contract_sha256": (
            QUICK_ACTIVATION_CHECK_CONTRACT_SHA256
        ),
        "implementation_start_gate_check_ids": list(
            IMPLEMENTATION_START_GATE_CHECK_IDS
        ),
        "implementation_start_gate_check_contract_sha256": (
            IMPLEMENTATION_START_GATE_CHECK_CONTRACT_SHA256
        ),
        "gate_repository_snapshot_is_event_bound": True,
        "gate_output_paths_are_event_scoped_append_only": True,
        "gate_evidence_is_excluded_from_working_snapshot_and_bound_directly": True,
        "historical_implementation_trace_does_not_pin_future_live_bytes": True,
        "materialization_initial_status": "PLANNED",
        "materialization_parent_must_be_ready_container": True,
        "planned_to_ready_event": "GOAL_READY",
        "blocker_resolution_returns_recorded_status": True,
        "successor_event": "GOAL_SUPERSEDED",
        "successor_event_is_atomic": True,
        "standalone_defect_successor_is_prohibited": True,
        "successor_semantic_fields_immutable": sorted(
            SUCCESSOR_INVARIANT_FIELDS
        ),
        "package_completion_is_terminal": True,
        "canonical_binding_update_event": "CANONICAL_BINDINGS_UPDATED",
        "canonical_update_subject_diff_required": True,
        "canonical_update_impact_disposition_required": True,
        "canonical_update_producer_must_complete_atomically": True,
        "canonical_update_internal_producer_roles_are_allowlisted": True,
        "canonical_update_unscoped_change_is_global": True,
        "canonical_update_unknown_payload_defaults_global": True,
        "trace_row_without_policy_or_gap_defaults_global": True,
        "artifact_work_output_scope_bound_at_materialization": True,
        "artifact_work_queue_covers_register_exactly_once": True,
        "artifact_work_due_target_requires_live_goal": True,
        "artifact_work_live_subject_overlap_prohibited": True,
        "artifact_work_one_subject_per_goal": True,
        "artifact_work_requires_canonical_update_before_completion": True,
        "artifact_work_requires_register_change_log_atomic_pair": True,
        "artifact_target_activation_must_match_work_reason": True,
        "artifact_active_event_requires_direct_evidence_bindings": True,
        "event_runtime_queue_and_completion_boundary_are_replay_derived": True,
        "completion_boundary_includes_internal_pending_leaves_and_artifact_queue": True,
        "artifact_assessment_follows_internal_ready_frontier": True,
        "externally_governed_artifact_state_requires_external_authorization": True,
        "delegated_internal_artifact_approval_requires_strong_producer_evidence": True,
        "delegated_internal_artifact_approval_is_event_declared": True,
        "artifact_ledger_content_self_seal_required": True,
        "policy_gap_work_requires_atomic_gap_backlog_output": True,
        "policy_gap_work_requires_owned_reassessment_terminal_outcome": True,
        "policy_gap_evidence_catalog_binds_current_producer_results": True,
        "policy_gap_evidence_catalog_excludes_post_update_successor_trace_to_avoid_hash_cycle": True,
        "policy_gap_work_derives_parent_progress_and_next_action": True,
        "backlog_operational_fields_change_only_through_policy_gap_work": True,
        "artifact_change_log_rows_bind_semantics_goal_register_and_date": True,
        "artifact_change_log_path_hashes_match_implementation_record": True,
        "canonical_application_receipt_is_nonimpact_control": True,
        "internal_completion_requires_changed_artifacts_checks_successor_trace": True,
        "internal_completion_requires_independent_review_provenance": True,
        "release_decision_binds_candidate_and_all_gate_provenance": True,
        "operation_handover_same_deployment_chain_required": True,
        "completed_workstream_change_strategy": (
            "EVENT_SOURCED_AGGREGATE_REOPEN_WITH_WORK_ITEM_SUCCESSORS"
        ),
        "completed_workstream_reopen_event": "CANONICAL_BINDINGS_UPDATED",
        "completed_workstream_direct_reopen_status": "READY",
        "completed_workstream_start_dependency_invalidated_status": "PLANNED",
        "workstream_revalidation_binds_latest_impact_event": True,
        "completed_master_change_strategy": (
            "TERMINAL_NEW_PROJECT_PACKAGE_REQUIRED"
        ),
        "same_package_reopen_requires_static_topology_unchanged": True,
        "static_graph_change_strategy": (
            "VERSIONED_SUCCESSOR_MANIFEST_AND_CHECKER_REQUIRED"
        ),
        "nonretroactive_ledgers_require_append_or_identity_continuity": True,
        "typed_start_evidence_event_binding_required": True,
        "typed_completion_required_roles_are_exact": True,
        "repository_scope_completion_milestone": (
            "COMPLETE_AWAITING_EXTERNAL"
        ),
        "repository_scope_completion_does_not_complete_package": True,
        "user_decision_questions_are_separate_from_external_actions": True,
        "blocking_request_must_target_deterministic_focus": True,
        "active_request_does_not_preempt_internal_ready_branch": True,
        "pending_user_questions_are_deferred_until_internal_frontier_exhausted": True,
        "external_request_identity_is_deduplicated": True,
        "request_key_is_canonical_logical_basis_sha256": True,
        "blocking_requests_require_explicit_typed_identity": True,
        "request_keys_are_globally_unique_across_history": True,
        "pending_questions_exactly_match_active_user_decisions": True,
        "complete_awaiting_external_requires_event_scoped_action_packet": True,
        "external_action_packet_is_not_completion_evidence": True,
        "external_action_packet_reissue_requires_basis_change": True,
        "internal_independent_review_does_not_substitute_external_review": True,
        "external_attestation_anchor_history_is_append_only": True,
        "authority_roster_anchor_history_is_append_only": True,
        "authority_roster_rotation_is_monotonic": True,
        "completion_receipt_event_binding_required": True,
        "tail_assurance": "INTERNAL_HASH_CHAIN_REQUIRES_GIT_OR_EXTERNAL_SNAPSHOT",
    }
    if not isinstance(transition_contract, dict) or any(
        transition_contract.get(field) != value
        for field, value in expected_transition_contract.items()
    ):
        errors.append("Goal graph transition contract differs")

    protected = manifest.get("protected_files")
    if not isinstance(protected, list):
        return errors + ["Goal graph protected_files must be a list"]
    if len(protected) != 19:
        errors.append("Goal graph protected file count differs")
    protected_paths: set[str] = set()
    for index, record in enumerate(protected):
        if not isinstance(record, dict):
            errors.append(f"protected file {index} must be an object")
            continue
        relative = record.get("path")
        digest = record.get("sha256")
        if not isinstance(relative, str) or relative in protected_paths:
            errors.append(f"protected file {index} path is invalid or duplicated")
            continue
        protected_paths.add(relative)
        path = resolve_safe_repo_file(root, relative)
        if path is None:
            errors.append(f"protected file is missing or unsafe: {relative}")
        elif not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            errors.append(f"protected file hash is invalid: {relative}")
        elif sha256_file(path) != digest:
            errors.append(f"protected file content changed: {relative}")
    required_protected = {
        str(node.get("path"))
        for node in manifest_node_map(manifest).values()
        if isinstance(node.get("path"), str)
    }
    required_protected.update(
        str(contract.get("template_path"))
        for contract in manifest.get("dynamic_node_contracts", [])
        if isinstance(contract, dict)
        and isinstance(contract.get("template_path"), str)
    )
    required_protected.update(
        {
            (PACKAGE_RELATIVE / "README.md").as_posix(),
            (
                PACKAGE_RELATIVE
                / "preactivation-supersession-record-v2.1.0.json"
            ).as_posix(),
            (
                PACKAGE_RELATIVE
                / "superseded-v2.1.0-package-prepared-event.json"
            ).as_posix(),
        }
    )
    missing_required = required_protected - protected_paths
    if missing_required:
        errors.append(
            "Goal graph protected static contract is incomplete: "
            f"{sorted(missing_required)}"
        )
    unexpected_protected = {
        path
        for path in protected_paths
        if not path.startswith(f"{PACKAGE_RELATIVE.as_posix()}/")
    }
    if unexpected_protected:
        errors.append(
            "Goal graph protected path escapes package: "
            f"{sorted(unexpected_protected)}"
        )
    allowed_unprotected = {
        path
        for path in discovered_paths
        if path.endswith(".md") and "/work-items/" in path
    }
    unexpected_unprotected = (
        set(discovered_paths)
        - protected_paths
        - allowed_unprotected
        - {MANIFEST_RELATIVE.as_posix()}
    )
    if unexpected_unprotected:
        errors.append(
            "Goal graph contains an undeclared support file: "
            f"{sorted(unexpected_unprotected)}"
        )
    if (
        checkpoint_state.get("activation_status") == "READY_NOT_ACTIVATED"
        and len(discovered_paths) != 20
    ):
        errors.append("unactivated Goal graph managed path count differs")
    return errors


def validate_superseded_v1(root: Path) -> list[str]:
    errors: list[str] = []
    relative = (
        "docs/control/goals/walksafe-completion-v1/"
        "static-plan-manifest-v1.1.0.json"
    )
    path = resolve_safe_repo_file(root, relative)
    if path is None:
        return ["superseded v1 manifest is missing or unsafe"]
    if sha256_file(path) != EXPECTED_V1_MANIFEST_SHA256:
        errors.append("superseded v1 manifest content changed")
    try:
        manifest = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"superseded v1 manifest cannot be loaded: {exc}"]
    protected = manifest.get("protected_files")
    if not isinstance(protected, list):
        return errors + ["superseded v1 protected file list is malformed"]
    paths = [relative]
    for index, record in enumerate(protected):
        if not isinstance(record, dict):
            errors.append(f"superseded v1 protected file {index} is malformed")
            continue
        protected_path = resolve_safe_repo_file(root, record.get("path"))
        if protected_path is None:
            errors.append(
                f"superseded v1 protected file is missing: {record.get('path')}"
            )
            continue
        if record.get("sha256") != sha256_file(protected_path):
            errors.append(
                f"superseded v1 protected file changed: {record.get('path')}"
            )
        paths.append(str(record.get("path")))
    record_path = resolve_safe_repo_file(
        root,
        (
            V21_PACKAGE_RELATIVE
            / "preactivation-supersession-record-v1.1.0.json"
        ).as_posix(),
    )
    if record_path is None:
        return errors + ["v1 supersession record is missing or unsafe"]
    try:
        record = load_json(record_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"v1 supersession record cannot be loaded: {exc}"]
    expected_content_hash = record.get("superseded_package", {}).get(
        "content_set_sha256"
    )
    try:
        _, actual_content_hash = package_hashes(root, sorted(paths))
    except (OSError, ValueError) as exc:
        errors.append(f"superseded v1 content hash failed: {exc}")
    else:
        if actual_content_hash != expected_content_hash:
            errors.append("superseded v1 content set differs from supersession record")
    return errors


def validate_superseded_v2_0(root: Path) -> list[str]:
    errors: list[str] = []
    package_relative = Path(
        "docs/control/goals/walksafe-completion-graph-v2"
    )
    manifest_relative = (
        package_relative / "static-plan-manifest-v2.0.0.json"
    ).as_posix()
    manifest_path = resolve_safe_repo_file(root, manifest_relative)
    if manifest_path is None:
        return ["superseded v2 manifest is missing or unsafe"]
    if sha256_file(manifest_path) != EXPECTED_V2_MANIFEST_SHA256:
        errors.append("superseded v2 manifest content changed")
    try:
        manifest = load_json(manifest_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"superseded v2 manifest cannot be loaded: {exc}"]

    protected = manifest.get("protected_files")
    if not isinstance(protected, list):
        return errors + ["superseded v2 protected file list is malformed"]
    if len(protected) != 18:
        errors.append("superseded v2 protected file count differs")
    protected_paths: set[str] = set()
    for index, record in enumerate(protected):
        if not isinstance(record, dict):
            errors.append(f"superseded v2 protected file {index} is malformed")
            continue
        relative = record.get("path")
        digest = record.get("sha256")
        if (
            not isinstance(relative, str)
            or relative in protected_paths
            or not relative.startswith(f"{package_relative.as_posix()}/")
        ):
            errors.append(
                f"superseded v2 protected file {index} path is invalid or duplicated"
            )
            continue
        protected_paths.add(relative)
        protected_path = resolve_safe_repo_file(root, relative)
        if protected_path is None:
            errors.append(
                f"superseded v2 protected file is missing or unsafe: {relative}"
            )
        elif not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            errors.append(
                f"superseded v2 protected file hash is invalid: {relative}"
            )
        elif sha256_file(protected_path) != digest:
            errors.append(f"superseded v2 protected file changed: {relative}")

    managed_paths = sorted({manifest_relative, *protected_paths})
    if len(managed_paths) != 19:
        errors.append("superseded v2 managed path count differs")
    discovered_paths = sorted(
        path.relative_to(root).as_posix()
        for path in (root / package_relative).rglob("*")
        if path.is_file()
    )
    if discovered_paths != managed_paths:
        errors.append("superseded v2 managed path set differs")
    try:
        path_set_hash, content_set_hash = package_hashes(root, managed_paths)
    except (OSError, ValueError) as exc:
        errors.append(f"superseded v2 package hash failed: {exc}")
    else:
        if path_set_hash != EXPECTED_V2_PATH_SET_SHA256:
            errors.append("superseded v2 path set differs")
        if content_set_hash != EXPECTED_V2_CONTENT_SET_SHA256:
            errors.append("superseded v2 content set differs")

    archived_event_relative = (
        V21_PACKAGE_RELATIVE
        / "superseded-v2.0.0-package-prepared-event.json"
    ).as_posix()
    archived_event_path = resolve_safe_repo_file(root, archived_event_relative)
    if archived_event_path is None:
        errors.append("superseded v2 preparation event is missing or unsafe")
        archived_event: dict[str, Any] = {}
    else:
        try:
            archived_event = load_json(archived_event_path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(
                f"superseded v2 preparation event cannot be loaded: {exc}"
            )
            archived_event = {}
    if archived_event:
        if archived_event.get("event_sha256") != EXPECTED_V2_EVENT_SHA256:
            errors.append("superseded v2 preparation event stored hash differs")
        if event_sha256(archived_event) != EXPECTED_V2_EVENT_SHA256:
            errors.append("superseded v2 preparation event canonical hash differs")

    record_relative = (
        V21_PACKAGE_RELATIVE
        / "preactivation-supersession-record-v2.0.0.json"
    ).as_posix()
    record_path = resolve_safe_repo_file(root, record_relative)
    if record_path is None:
        return errors + ["v2 supersession record is missing or unsafe"]
    try:
        record = load_json(record_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"v2 supersession record cannot be loaded: {exc}"]
    expected_superseded_package = {
        "package_id": "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2",
        "plan_version": "2.0.0",
        "activation_status": "READY_NOT_ACTIVATED",
        "manifest_path": manifest_relative,
        "manifest_sha256": EXPECTED_V2_MANIFEST_SHA256,
        "initial_transition_event_path": archived_event_relative,
        "initial_transition_event_sha256": EXPECTED_V2_EVENT_SHA256,
        "protected_file_count": 18,
        "managed_path_count": 19,
        "path_set_sha256": EXPECTED_V2_PATH_SET_SHA256,
        "content_set_sha256": EXPECTED_V2_CONTENT_SET_SHA256,
    }
    if record.get("schema_version") != "1.0":
        errors.append("v2 supersession record schema differs")
    if (
        record.get("record_id")
        != "WS-GOAL-PREACTIVATION-SUPERSESSION-20260724-001"
    ):
        errors.append("v2 supersession record ID differs")
    if record.get("superseded_package") != expected_superseded_package:
        errors.append("v2 supersession record package binding differs")
    if (
        record.get("successor_package_id") != EXPECTED_V21_PACKAGE_ID
        or record.get("successor_plan_version") != EXPECTED_V21_PLAN_VERSION
    ):
        errors.append("v2 supersession record successor binding differs")
    if (
        record.get("execution_effect")
        != (
            "NO_PRODUCT_WORK_EXECUTED_BY_V2_0; NO_ACTIVATION_EVENT_RECORDED; "
            "V2_0_RETAINED_BYTE_EXACT_FOR_AUDIT_ONLY"
        )
    ):
        errors.append("v2 supersession record execution effect differs")
    if not isinstance(record.get("reason"), str) or not record["reason"].strip():
        errors.append("v2 supersession record reason is missing")
    record_time = parse_iso_datetime(record.get("recorded_at"))
    event_time = parse_iso_datetime(archived_event.get("occurred_at"))
    if record_time is None:
        errors.append("v2 supersession record timestamp is invalid")
    if event_time is None:
        errors.append("superseded v2 preparation event timestamp is invalid")
    if (
        record_time is not None
        and event_time is not None
        and record_time <= event_time
    ):
        errors.append("v2 supersession record does not follow preparation event")
    return errors


def validate_superseded_v2_1(root: Path) -> list[str]:
    errors: list[str] = []
    manifest_path = resolve_safe_repo_file(
        root,
        V21_MANIFEST_RELATIVE.as_posix(),
    )
    if manifest_path is None:
        return ["superseded v2.1 manifest is missing or unsafe"]
    if sha256_file(manifest_path) != EXPECTED_V21_MANIFEST_SHA256:
        errors.append("superseded v2.1 manifest content changed")
    try:
        manifest = load_json(manifest_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"superseded v2.1 manifest cannot be loaded: {exc}"]

    if (
        manifest.get("package_id") != EXPECTED_V21_PACKAGE_ID
        or manifest.get("plan_version") != EXPECTED_V21_PLAN_VERSION
    ):
        errors.append("superseded v2.1 manifest package binding differs")
    supersedes = manifest.get("supersedes")
    if not isinstance(supersedes, dict) or supersedes != {
        "package_id": "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2",
        "plan_version": "2.0.0",
        "activation_status": "READY_NOT_ACTIVATED",
        "manifest_path": (
            "docs/control/goals/walksafe-completion-graph-v2/"
            "static-plan-manifest-v2.0.0.json"
        ),
        "manifest_sha256": EXPECTED_V2_MANIFEST_SHA256,
        "initial_transition_event_path": (
            f"{V21_PACKAGE_RELATIVE.as_posix()}/"
            "superseded-v2.0.0-package-prepared-event.json"
        ),
        "initial_transition_event_sha256": EXPECTED_V2_EVENT_SHA256,
        "supersession_record_path": (
            f"{V21_PACKAGE_RELATIVE.as_posix()}/"
            "preactivation-supersession-record-v2.0.0.json"
        ),
    }:
        errors.append("superseded v2.1 predecessor binding differs")

    protected = manifest.get("protected_files")
    if not isinstance(protected, list):
        return errors + ["superseded v2.1 protected file list is malformed"]
    if len(protected) != 20:
        errors.append("superseded v2.1 protected file count differs")
    protected_paths: set[str] = set()
    for index, record in enumerate(protected):
        if not isinstance(record, dict):
            errors.append(
                f"superseded v2.1 protected file {index} is malformed"
            )
            continue
        relative = record.get("path")
        digest = record.get("sha256")
        if (
            not isinstance(relative, str)
            or relative in protected_paths
            or not relative.startswith(
                f"{V21_PACKAGE_RELATIVE.as_posix()}/"
            )
        ):
            errors.append(
                "superseded v2.1 protected file "
                f"{index} path is invalid or duplicated"
            )
            continue
        protected_paths.add(relative)
        protected_path = resolve_safe_repo_file(root, relative)
        if protected_path is None:
            errors.append(
                "superseded v2.1 protected file is missing or unsafe: "
                f"{relative}"
            )
        elif not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            errors.append(
                f"superseded v2.1 protected file hash is invalid: {relative}"
            )
        elif sha256_file(protected_path) != digest:
            errors.append(
                f"superseded v2.1 protected file changed: {relative}"
            )

    managed_paths = sorted(
        {V21_MANIFEST_RELATIVE.as_posix(), *protected_paths}
    )
    if len(managed_paths) != 21:
        errors.append("superseded v2.1 managed path count differs")
    discovered_paths = sorted(
        path.relative_to(root).as_posix()
        for path in (root / V21_PACKAGE_RELATIVE).rglob("*")
        if path.is_file()
    )
    if discovered_paths != managed_paths:
        errors.append("superseded v2.1 managed path set differs")
    try:
        path_set_hash, content_set_hash = package_hashes(root, managed_paths)
    except (OSError, ValueError) as exc:
        errors.append(f"superseded v2.1 package hash failed: {exc}")
    else:
        if path_set_hash != EXPECTED_V21_PATH_SET_SHA256:
            errors.append("superseded v2.1 path set differs")
        if content_set_hash != EXPECTED_V21_CONTENT_SET_SHA256:
            errors.append("superseded v2.1 content set differs")

    archived_event_relative = (
        PACKAGE_RELATIVE
        / "superseded-v2.1.0-package-prepared-event.json"
    ).as_posix()
    archived_event_path = resolve_safe_repo_file(
        root,
        archived_event_relative,
    )
    if archived_event_path is None:
        errors.append(
            "superseded v2.1 preparation event is missing or unsafe"
        )
        archived_event: dict[str, Any] = {}
    else:
        try:
            archived_event = load_json(archived_event_path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(
                "superseded v2.1 preparation event cannot be loaded: "
                f"{exc}"
            )
            archived_event = {}
    if archived_event:
        if (
            archived_event.get("event_sha256")
            != EXPECTED_V21_INITIAL_EVENT_SHA256
        ):
            errors.append(
                "superseded v2.1 preparation event stored hash differs"
            )
        if (
            event_sha256(archived_event)
            != EXPECTED_V21_INITIAL_EVENT_SHA256
        ):
            errors.append(
                "superseded v2.1 preparation event canonical hash differs"
            )

    record_relative = (
        PACKAGE_RELATIVE
        / "preactivation-supersession-record-v2.1.0.json"
    ).as_posix()
    record_path = resolve_safe_repo_file(root, record_relative)
    if record_path is None:
        return errors + ["v2.1 supersession record is missing or unsafe"]
    try:
        record = load_json(record_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"v2.1 supersession record cannot be loaded: {exc}"
        ]
    expected_superseded_package = {
        "package_id": EXPECTED_V21_PACKAGE_ID,
        "plan_version": EXPECTED_V21_PLAN_VERSION,
        "activation_status": "READY_NOT_ACTIVATED",
        "manifest_path": V21_MANIFEST_RELATIVE.as_posix(),
        "manifest_sha256": EXPECTED_V21_MANIFEST_SHA256,
        "initial_transition_event_path": archived_event_relative,
        "initial_transition_event_sha256": (
            EXPECTED_V21_INITIAL_EVENT_SHA256
        ),
        "protected_file_count": 20,
        "managed_path_count": 21,
        "path_set_sha256": EXPECTED_V21_PATH_SET_SHA256,
        "content_set_sha256": EXPECTED_V21_CONTENT_SET_SHA256,
    }
    if record.get("schema_version") != "1.0":
        errors.append("v2.1 supersession record schema differs")
    if (
        record.get("record_id")
        != "WS-GOAL-PREACTIVATION-SUPERSESSION-20260724-002"
    ):
        errors.append("v2.1 supersession record ID differs")
    if record.get("superseded_package") != expected_superseded_package:
        errors.append("v2.1 supersession record package binding differs")
    if (
        record.get("successor_package_id") != EXPECTED_PACKAGE_ID
        or record.get("successor_plan_version") != EXPECTED_PLAN_VERSION
    ):
        errors.append("v2.1 supersession record successor binding differs")
    if (
        record.get("execution_effect")
        != (
            "NO_PRODUCT_WORK_EXECUTED_BY_V2_1; NO_ACTIVATION_EVENT_RECORDED; "
            "V2_1_RETAINED_BYTE_EXACT_FOR_AUDIT_ONLY"
        )
    ):
        errors.append("v2.1 supersession record execution effect differs")
    if not isinstance(record.get("reason"), str) or not record["reason"].strip():
        errors.append("v2.1 supersession record reason is missing")
    record_time = parse_iso_datetime(record.get("recorded_at"))
    event_time = parse_iso_datetime(archived_event.get("occurred_at"))
    if record_time is None:
        errors.append("v2.1 supersession record timestamp is invalid")
    if event_time is None:
        errors.append(
            "superseded v2.1 preparation event timestamp is invalid"
        )
    if (
        record_time is not None
        and event_time is not None
        and record_time <= event_time
    ):
        errors.append(
            "v2.1 supersession record does not follow preparation event"
        )
    return errors


def validate_goal_document(
    relative: str,
    metadata: dict[str, Any],
    body: str,
) -> list[str]:
    errors: list[str] = []
    missing = GOAL_REQUIRED_FIELDS - set(metadata)
    if missing:
        errors.append(f"{relative}: required Goal fields are missing: {sorted(missing)}")
    prohibited = PROHIBITED_LINEAR_FIELDS & set(metadata)
    if prohibited:
        errors.append(
            f"{relative}: fixed-stage fields are prohibited: {sorted(prohibited)}"
        )
    if metadata.get("schema_version") != "2.0":
        errors.append(f"{relative}: Goal schema version differs")
    if metadata.get("goal_kind") not in GOAL_ALLOWED_KINDS:
        errors.append(f"{relative}: Goal kind is invalid")
    if not isinstance(metadata.get("priority_rank"), int):
        errors.append(f"{relative}: priority_rank must be an integer")
    for field in (
        "start_requires",
        "completion_requires",
        "child_goal_ids",
        "source_policy_ids",
        "gap_ids",
        "canonical_input_roles",
    ):
        value = metadata.get(field)
        if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
            errors.append(f"{relative}: {field} must be a string list")
    if metadata.get("goal_kind") == "MASTER":
        if metadata.get("parent_goal_id") != "":
            errors.append(f"{relative}: Master parent must be empty")
    elif not isinstance(metadata.get("parent_goal_id"), str) or not metadata.get(
        "parent_goal_id"
    ):
        errors.append(f"{relative}: non-Master parent is missing")
    if metadata.get("goal_kind") == "WORK_ITEM":
        if not isinstance(metadata.get("work_item_type"), str):
            errors.append(f"{relative}: Work Item type is missing")
        if not isinstance(metadata.get("work_item_id"), str) or not metadata.get(
            "work_item_id"
        ):
            errors.append(f"{relative}: Work Item ID is missing")
        if not isinstance(metadata.get("artifact_work_reason"), str):
            errors.append(f"{relative}: artifact_work_reason is missing")
        if (
            not isinstance(metadata.get("artifact_trigger_evidence_refs"), list)
            or any(
                not isinstance(item, str)
                for item in metadata.get(
                    "artifact_trigger_evidence_refs",
                    [],
                )
            )
        ):
            errors.append(
                f"{relative}: artifact_trigger_evidence_refs must be a string list"
            )
    elif metadata.get("work_item_id") != "":
        errors.append(f"{relative}: non-Work Item has a work_item_id")
    if metadata.get("goal_kind") == "WORKSTREAM":
        for prohibited_claim in (
            "자동 시작한다",
            "다음 Goal은",
            "현재 후속 Goal",
        ):
            if prohibited_claim in body:
                errors.append(
                    f"{relative}: linear handoff claim is prohibited: "
                    f"{prohibited_claim}"
                )
    required_headings = (
        "## 목표",
        "## 정본 입력",
        "## 범위와 제외",
        "## 실행 절차",
        "## 검증",
        "## 완료 기준",
        "## 질문·중단 조건",
        "## 완료 후 인계",
    )
    for heading in required_headings:
        if heading not in body:
            errors.append(f"{relative}: required heading is missing: {heading}")
    return errors


def dynamic_contract_map(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    contracts = manifest.get("dynamic_node_contracts")
    if not isinstance(contracts, list):
        return {}
    result: dict[str, dict[str, Any]] = {}
    for contract in contracts:
        if not isinstance(contract, dict):
            continue
        work_type = contract.get("work_item_type")
        if isinstance(work_type, str) and work_type not in result:
            result[work_type] = contract
    return result


def parent_matches_start_contract(
    node: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    scope: str,
) -> bool:
    parent_id = node.get("parent_goal_id")
    parent = nodes.get(str(parent_id), {})
    if scope == "READY_CONTAINER":
        return parent.get("goal_kind") in {"WORKSTREAM", "MASTER"}
    if scope == "IMPLEMENTATION_WORKSTREAM":
        return (
            parent.get("goal_kind") == "WORKSTREAM"
            and parent.get("workstream_type") == "IMPLEMENTATION"
        )
    if scope == "EPIC_11_OR_MASTER":
        return parent_id in {"WS-GOAL-EPIC-11", EXPECTED_ROOT_GOAL_ID}
    if scope == "EPIC_12":
        return parent_id == "WS-GOAL-EPIC-12"
    if scope == "MASTER":
        return parent_id == EXPECTED_ROOT_GOAL_ID
    return False


def start_requirement_type_count(
    dependency_nodes: list[dict[str, Any]],
    requirement: str,
) -> int:
    if requirement.startswith("GOAL_KIND::"):
        expected_kind = requirement.split("::", 1)[1]
        return sum(
            node.get("goal_kind") == expected_kind for node in dependency_nodes
        )
    return sum(
        node.get("goal_kind") == "WORK_ITEM"
        and node.get("work_item_type") == requirement
        for node in dependency_nodes
    )


def successor_parent_accepts_revision(
    parent_status: str | None,
    *,
    canonical_change_successor: bool,
) -> bool:
    if parent_status == "READY":
        return True
    return bool(
        canonical_change_successor
        and parent_status in RUNTIME_STATUSES - TERMINAL_STATUSES
    )


def successor_semantic_scope_matches(
    predecessor: dict[str, Any],
    successor: dict[str, Any],
) -> bool:
    return all(
        successor.get(field) == predecessor.get(field)
        for field in SUCCESSOR_INVARIANT_FIELDS
    )


def validate_work_item_dependency_topology(
    goal_id: str,
    node: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, str] | None,
) -> list[str]:
    errors: list[str] = []
    dependencies = set(string_list(node.get("start_requires"))) | set(
        string_list(node.get("completion_requires"))
    )
    completion_only_dependencies = set(
        string_list(node.get("completion_requires"))
    ) - set(string_list(node.get("start_requires")))
    if completion_only_dependencies:
        errors.append(
            f"{goal_id}: single-focus Work Item completion dependencies "
            "must also be start dependencies: "
            f"{sorted(completion_only_dependencies)}"
        )
    ancestors: set[str] = set()
    cursor = str(node.get("parent_goal_id", ""))
    while cursor and cursor in nodes and cursor not in ancestors:
        ancestors.add(cursor)
        cursor = str(nodes[cursor].get("parent_goal_id", ""))
    ancestor_dependencies = dependencies & ancestors
    if ancestor_dependencies:
        errors.append(
            f"{goal_id}: Work Item dependency cannot reference its ancestor: "
            f"{sorted(ancestor_dependencies)}"
        )

    parent_id = str(node.get("parent_goal_id", ""))
    if parent_id != EXPECTED_ROOT_GOAL_ID:
        allowed_workstreams: set[str] = {parent_id}
        frontier = [parent_id]
        while frontier:
            workstream_id = frontier.pop()
            workstream = nodes.get(workstream_id, {})
            for dependency_id in (
                string_list(workstream.get("start_requires"))
                + string_list(workstream.get("completion_requires"))
            ):
                if (
                    nodes.get(dependency_id, {}).get("goal_kind")
                    == "WORKSTREAM"
                    and dependency_id not in allowed_workstreams
                ):
                    allowed_workstreams.add(dependency_id)
                    frontier.append(dependency_id)
        invalid_container_dependencies: set[str] = set()
        for dependency_id in dependencies:
            dependency = nodes.get(dependency_id, {})
            dependency_kind = dependency.get("goal_kind")
            if dependency_kind == "WORKSTREAM":
                container_id = dependency_id
            elif dependency_kind == "WORK_ITEM":
                container_id = str(dependency.get("parent_goal_id", ""))
            else:
                continue
            if (
                container_id != EXPECTED_ROOT_GOAL_ID
                and container_id not in allowed_workstreams
            ):
                invalid_container_dependencies.add(dependency_id)
        if invalid_container_dependencies:
            errors.append(
                f"{goal_id}: Work Item dependency points to a downstream "
                "or unrelated Workstream: "
                f"{sorted(invalid_container_dependencies)}"
            )

    if isinstance(statuses, dict) and statuses.get(goal_id) not in {
        "COMPLETE_AT_TARGET",
        "SUPERSEDED",
    }:
        superseded_dependencies = {
            dependency
            for dependency in dependencies
            if statuses.get(dependency) == "SUPERSEDED"
        }
        if superseded_dependencies:
            errors.append(
                f"{goal_id}: active Work Item dependency references a "
                "superseded revision: "
                f"{sorted(superseded_dependencies)}"
            )
    return errors


def validate_dynamic_control_policy(
    goal_id: str,
    node: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    for field in ("question_policy", "stop_policy"):
        if node.get(field) != "INHERIT_MASTER":
            errors.append(
                f"{goal_id}: {field} must be INHERIT_MASTER"
            )
    return errors


def validate_artifact_work_materialization_scope(
    root: Path,
    goal_id: str,
    node: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    declared = node.get("output_subject_ids_by_role")
    normalized = (
        {
            role: string_list(subject_ids)
            for role, subject_ids in declared.items()
        }
        if isinstance(declared, dict)
        else {}
    )
    required_roles = {"ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG"}
    if (
        not isinstance(declared, dict)
        or not required_roles.issubset(normalized)
        or not set(normalized).issubset(
            INTERNAL_PRODUCER_OUTPUT_ROLES_BY_TYPE["ARTIFACT_WORK"]
        )
        or any(
            declared.get(role) != subject_ids
            or subject_ids != sorted(set(subject_ids))
            for role, subject_ids in normalized.items()
        )
    ):
        return [
            f"{goal_id}: ARTIFACT_WORK materialization output scope is invalid"
        ]
    register_scope = normalized["ARTIFACT_REGISTER"]
    change_log_scope = normalized["ARTIFACT_CHANGE_LOG"]
    if (
        not register_scope
        or register_scope != change_log_scope
        or "*" in register_scope
        or len(register_scope) != 1
        or any(not code.startswith("DLV-") for code in register_scope)
    ):
        errors.append(
            f"{goal_id}: ARTIFACT_WORK register/change-log target scope "
            "must be the same single DLV-* subject"
        )
        return errors
    if node.get("materialized_from_role") != "ARTIFACT_REGISTER":
        return errors + [
            f"{goal_id}: ARTIFACT_WORK materialization source must be "
            "ARTIFACT_REGISTER"
        ]
    source = materialization_source_snapshot(node)
    source_path = resolve_safe_repo_file(root, source.get("path"))
    if (
        source_path is None
        or source.get("file_sha256") != sha256_file(source_path)
    ):
        return errors + [
            f"{goal_id}: ARTIFACT_WORK register snapshot binding differs"
        ]
    try:
        register = load_json(source_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"{goal_id}: ARTIFACT_WORK register snapshot cannot be loaded: {exc}"
        ]
    rows = {
        row.get("artifact_type_code"): row
        for row in register.get("artifacts", [])
        if isinstance(row, dict)
        and isinstance(row.get("artifact_type_code"), str)
    }
    reason = node.get("artifact_work_reason")
    trigger_refs = string_list(node.get("artifact_trigger_evidence_refs"))
    if reason not in {
        "ACTIVE_EVENT_UPDATE",
        "APPLICABILITY_DECISION",
        "DRAFT_COMPLETION",
        "PLANNED_EVIDENCE",
    }:
        errors.append(f"{goal_id}: ARTIFACT_WORK reason is invalid")
    if (
        node.get("artifact_trigger_evidence_refs") != trigger_refs
        or trigger_refs != sorted(set(trigger_refs))
        or (reason == "ACTIVE_EVENT_UPDATE" and not trigger_refs)
        or (reason != "ACTIVE_EVENT_UPDATE" and trigger_refs)
    ):
        errors.append(
            f"{goal_id}: ARTIFACT_WORK trigger evidence differs from reason"
        )
    for artifact_code in register_scope:
        row = rows.get(artifact_code)
        if not isinstance(row, dict):
            errors.append(
                f"{goal_id}: ARTIFACT_WORK target is absent from the "
                f"artifact register: {artifact_code}"
            )
        elif (
            reason == "APPLICABILITY_DECISION"
            and row.get("activation_result") != "PENDING_EVALUATION"
        ):
            errors.append(
                f"{goal_id}: applicability decision target is not pending "
                f"evaluation: {artifact_code}"
            )
        elif (
            reason != "APPLICABILITY_DECISION"
            and row.get("activation_result") != "ACTIVE"
        ):
            errors.append(
                f"{goal_id}: ARTIFACT_WORK target is not active for its "
                f"reason: {artifact_code}"
            )
    return errors


def validate_artifact_trigger_event_bindings(
    root: Path,
    *,
    label: str,
    event: dict[str, Any],
    node: dict[str, Any],
) -> list[str]:
    if node.get("work_item_type") != "ARTIFACT_WORK":
        return []
    reason = node.get("artifact_work_reason")
    refs = string_list(node.get("artifact_trigger_evidence_refs"))
    bindings = event.get("artifact_trigger_evidence_bindings")
    if reason != "ACTIVE_EVENT_UPDATE":
        return (
            []
            if bindings == {}
            else [
                f"{label}: non-event ARTIFACT_WORK must have empty direct "
                "trigger evidence bindings"
            ]
        )
    errors: list[str] = []
    if (
        event.get("artifact_trigger_evidence_refs") != refs
        or refs != sorted(set(refs))
        or not refs
        or not isinstance(bindings, dict)
        or set(bindings) != set(refs)
    ):
        return [
            f"{label}: ACTIVE_EVENT_UPDATE trigger evidence references and "
            "direct bindings differ"
        ]
    expected_fields = {"document_id", "path", "file_sha256"}
    for reference in refs:
        binding = bindings.get(reference)
        if not isinstance(binding, dict) or set(binding) != expected_fields:
            errors.append(
                f"{label}: ACTIVE_EVENT_UPDATE direct binding differs: "
                f"{reference}"
            )
            continue
        path = resolve_safe_repo_file(root, binding.get("path"))
        digest = binding.get("file_sha256")
        if (
            binding.get("document_id") != reference
            or path is None
            or not isinstance(digest, str)
            or not SHA256_PATTERN.fullmatch(digest)
            or digest != sha256_file(path)
        ):
            errors.append(
                f"{label}: ACTIVE_EVENT_UPDATE direct binding is not an "
                f"exact live file binding: {reference}"
            )
    return errors


def validate_dynamic_work_items(
    root: Path,
    manifest: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    paths_by_id: dict[str, str],
    mapping: dict[str, str],
    bindings: dict[str, dict[str, Any]],
    statuses: dict[str, str] | None = None,
) -> list[str]:
    errors: list[str] = []
    # Current bindings intentionally do not replace each immutable Goal's
    # creation-time source. Transition replay validates the historical snapshot.
    _ = bindings
    contracts = dynamic_contract_map(manifest)
    declared_contracts = manifest.get("dynamic_node_contracts")
    if not isinstance(declared_contracts, list) or len(contracts) != len(
        declared_contracts
    ):
        errors.append("dynamic node contracts are missing, malformed, or duplicated")
    for goal_id, node in nodes.items():
        if node.get("goal_kind") != "WORK_ITEM":
            continue
        work_type = node.get("work_item_type")
        contract = contracts.get(str(work_type))
        if contract is None:
            errors.append(f"{goal_id}: Work Item type is not declared by manifest")
            continue
        errors.extend(validate_dynamic_control_policy(goal_id, node))
        canonical_roles = string_list(node.get("canonical_input_roles"))
        if (
            node.get("canonical_input_roles") != canonical_roles
            or len(canonical_roles) != len(set(canonical_roles))
            or not MANDATORY_DYNAMIC_CANONICAL_INPUT_ROLES.issubset(
                canonical_roles
            )
        ):
            errors.append(
                f"{goal_id}: dynamic Work Item canonical input role set "
                "omits mandatory stale-input controls"
            )
        errors.extend(
            validate_work_item_dependency_topology(
                goal_id,
                node,
                nodes,
                statuses,
            )
        )
        allowed_targets = string_list(
            contract.get("allowed_target_completion_levels")
        )
        if node.get("target_completion_level") not in allowed_targets:
            errors.append(
                f"{goal_id}: target completion level is not allowed for {work_type}"
            )
        start_contract = DYNAMIC_START_CONTRACTS.get(str(work_type), {})
        if not parent_matches_start_contract(
            node,
            nodes,
            str(start_contract.get("allowed_parent_scope", "")),
        ):
            errors.append(
                f"{goal_id}: parent differs from the {work_type} start contract"
            )
        dependency_nodes = [
            nodes[dependency]
            for dependency in string_list(node.get("start_requires"))
            if dependency in nodes
        ]
        required_goal_ids = set(
            string_list(start_contract.get("required_start_goal_ids"))
        )
        required_goal_ids.update(
            start_contract.get("required_start_goal_ids_by_parent", {}).get(
                str(node.get("parent_goal_id")),
                [],
            )
        )
        missing_goal_ids = required_goal_ids - set(
            string_list(node.get("start_requires"))
        )
        if missing_goal_ids:
            errors.append(
                f"{goal_id}: start contract omits required Goal IDs: "
                f"{sorted(missing_goal_ids)}"
            )
        for requirement, minimum_count in start_contract.get(
            "required_start_goal_type_counts",
            {},
        ).items():
            if (
                not isinstance(minimum_count, int)
                or minimum_count < 1
                or start_requirement_type_count(
                    dependency_nodes,
                    requirement,
                )
                < minimum_count
            ):
                errors.append(
                    f"{goal_id}: start requirements lack {minimum_count} "
                    f"completed {requirement} Goal(s)"
                )
        if work_type == "RELEASE_GATE":
            gate_ids = set(string_list(node.get("source_policy_ids")))
            if len(gate_ids) != 1 or not gate_ids.issubset(EXPECTED_GATE_IDS):
                errors.append(
                    f"{goal_id}: RELEASE_GATE must bind exactly one expected Gate ID"
                )
        if (
            work_type == "INTEGRATION_CANDIDATE"
            and node.get("parent_goal_id") == "WS-GOAL-EPIC-11"
        ):
            parent = nodes.get("WS-GOAL-EPIC-11", {})
            expected_pairs = {
                (policy_id, mapping.get(policy_id))
                for policy_id in string_list(parent.get("source_policy_ids"))
            }
            dependency_pairs = {
                (
                    string_list(dependency_node.get("source_policy_ids"))[0],
                    string_list(dependency_node.get("gap_ids"))[0],
                )
                for dependency_node in dependency_nodes
                if dependency_node.get("work_item_type") == "POLICY_GAP_WORK"
                and dependency_node.get("parent_goal_id") == "WS-GOAL-EPIC-11"
                and len(
                    string_list(dependency_node.get("source_policy_ids"))
                )
                == 1
                and len(string_list(dependency_node.get("gap_ids"))) == 1
            }
            if dependency_pairs != expected_pairs:
                errors.append(
                    f"{goal_id}: integration candidate lacks complete EPIC-11 "
                    "policy/Gap Work Item dependencies"
                )
            artifact_subjects = {
                subject_id
                for dependency_node in dependency_nodes
                if dependency_node.get("work_item_type") == "ARTIFACT_WORK"
                for subject_id in string_list(
                    (
                        dependency_node.get("output_subject_ids_by_role")
                        or {}
                    ).get("ARTIFACT_REGISTER")
                )
            }
            if artifact_subjects != EXPECTED_INTEGRATION_CANDIDATE_ARTIFACT_CODES:
                errors.append(
                    f"{goal_id}: integration candidate artifact subject "
                    "coverage differs"
                )
        if work_type == "RELEASE_DECISION":
            covered_gate_ids = {
                gate_id
                for dependency_node in dependency_nodes
                if dependency_node.get("work_item_type") == "RELEASE_GATE"
                for gate_id in string_list(
                    dependency_node.get("source_policy_ids")
                )
            }
            if covered_gate_ids != EXPECTED_GATE_IDS:
                errors.append(
                    f"{goal_id}: release decision does not require all five Gate Goals"
                )
        if work_type == "HANDOVER_CLOSURE_EVENT":
            deployment_dependencies = {
                dependency_id
                for dependency_id in string_list(node.get("start_requires"))
                if nodes.get(dependency_id, {}).get("work_item_type")
                == "DEPLOYMENT_DELIVERY_EVENT"
            }
            operation_dependencies = {
                dependency_id
                for dependency_id in string_list(node.get("start_requires"))
                if nodes.get(dependency_id, {}).get("work_item_type")
                == "OPERATION_EVENT"
            }
            operation_dependency_ids = (
                set(
                    string_list(
                        nodes[next(iter(operation_dependencies))].get(
                            "start_requires"
                        )
                    )
                )
                if len(operation_dependencies) == 1
                else set()
            )
            if (
                len(deployment_dependencies) != 1
                or len(operation_dependencies) != 1
                or not deployment_dependencies.issubset(
                    operation_dependency_ids
                )
            ):
                errors.append(
                    f"{goal_id}: handover must bind one operation event "
                    "from the same single deployment"
                )
        if work_type == "BLOCKER_OR_EXTERNAL_RECEIPT":
            source_blocker_ids = string_list(node.get("source_blocker_ids"))
            if (
                len(source_blocker_ids) != 1
            ):
                errors.append(
                    f"{goal_id}: external receipt Goal must bind exactly one "
                    "source blocker"
                )
        elif string_list(node.get("source_blocker_ids")):
            errors.append(
                f"{goal_id}: only an external receipt Goal may bind source blockers"
            )
        if string_list(node.get("child_goal_ids")):
            errors.append(f"{goal_id}: a materialized Work Item cannot own child Goals")
        expected_initial_status = (
            "READY"
            if goal_id == EXPECTED_INITIAL_FOCUS_GOAL_ID
            else "PLANNED"
        )
        if node.get("initial_status") != expected_initial_status:
            errors.append(
                f"{goal_id}: dynamic Goal initial status must be "
                f"{expected_initial_status}"
            )

        role = node.get("materialized_from_role")
        if not isinstance(role, str) or not role:
            errors.append(f"{goal_id}: materialization source role is missing")
        else:
            source_snapshot = materialization_source_snapshot(node)
            source_path = resolve_safe_repo_file(root, source_snapshot.get("path"))
            if source_path is None:
                errors.append(f"{goal_id}: materialization source path is unsafe")
            source_hash = source_snapshot.get("file_sha256")
            if (
                not isinstance(source_snapshot.get("document_id"), str)
                or not source_snapshot.get("document_id")
            ):
                errors.append(f"{goal_id}: materialization source document ID is missing")
            if (
                not isinstance(source_hash, str)
                or not SHA256_PATTERN.fullmatch(source_hash)
            ):
                errors.append(f"{goal_id}: materialization source hash is invalid")
            elif source_path is not None and source_hash != sha256_file(source_path):
                errors.append(f"{goal_id}: materialization source hash differs")

        for prefix in ("predecessor", "supersedes"):
            referenced_goal = node.get(f"{prefix}_goal_id")
            referenced_hash = node.get(f"{prefix}_goal_content_sha256")
            if referenced_goal:
                if referenced_goal not in nodes:
                    errors.append(f"{goal_id}: {prefix} Goal is missing")
                    continue
                if nodes[referenced_goal].get("goal_kind") != "WORK_ITEM":
                    errors.append(
                        f"{goal_id}: {prefix} Goal must be a Work Item"
                    )
                referenced_path = resolve_safe_repo_file(
                    root,
                    paths_by_id.get(str(referenced_goal)),
                )
                if referenced_path is None:
                    errors.append(f"{goal_id}: {prefix} Goal path is unsafe")
                elif referenced_hash != sha256_file(referenced_path):
                    errors.append(f"{goal_id}: {prefix} Goal hash differs")
            elif referenced_hash:
                errors.append(f"{goal_id}: {prefix} hash exists without Goal ID")

        if work_type == "POLICY_GAP_WORK":
            if node.get("materialized_from_role") != "IMPLEMENTATION_BACKLOG":
                errors.append(
                    f"{goal_id}: POLICY_GAP_WORK materialization role must be "
                    "IMPLEMENTATION_BACKLOG"
                )
            policies = string_list(node.get("source_policy_ids"))
            gaps = string_list(node.get("gap_ids"))
            if len(policies) != 1 or len(gaps) != 1:
                errors.append(
                    f"{goal_id}: POLICY_GAP_WORK must bind exactly one policy and one Gap"
                )
            elif mapping.get(policies[0]) != gaps[0]:
                errors.append(
                    f"{goal_id}: POLICY_GAP_WORK pair differs from canonical mapping"
                )
        if work_type == "ARTIFACT_WORK":
            errors.extend(
                validate_artifact_work_materialization_scope(
                    root,
                    goal_id,
                    node,
                )
            )
        elif (
            node.get("artifact_work_reason") != ""
            or node.get("artifact_trigger_evidence_refs") != []
        ):
            errors.append(
                f"{goal_id}: non-ARTIFACT_WORK Goal has artifact scheduling fields"
            )

    revisions_by_work_item: dict[str, dict[int, str]] = {}
    for goal_id, node in nodes.items():
        if node.get("goal_kind") != "WORK_ITEM":
            continue
        work_item_id = node.get("work_item_id")
        if not isinstance(work_item_id, str) or not work_item_id:
            continue
        match = re.search(r"-R(\d{3})$", goal_id)
        if match is None:
            errors.append(f"{goal_id}: Work Item Goal ID must end in -RNNN")
            continue
        revision = int(match.group(1))
        revisions = revisions_by_work_item.setdefault(work_item_id, {})
        if revision in revisions:
            errors.append(
                f"{work_item_id}: duplicate Work Item revision R{revision:03d}"
            )
        revisions[revision] = goal_id

    for work_item_id, revisions in revisions_by_work_item.items():
        ordered = sorted(revisions)
        if ordered != list(range(1, len(ordered) + 1)):
            errors.append(f"{work_item_id}: Work Item revisions are not contiguous")
            continue
        for revision in ordered:
            goal_id = revisions[revision]
            node = nodes[goal_id]
            expected_supersedes = revisions.get(revision - 1, "")
            if node.get("supersedes_goal_id") != expected_supersedes:
                errors.append(
                    f"{goal_id}: Work Item supersedes chain differs"
                )
            if expected_supersedes:
                predecessor_node = nodes[expected_supersedes]
                if node.get("parent_goal_id") != predecessor_node.get(
                    "parent_goal_id"
                ):
                    errors.append(
                        f"{goal_id}: successor parent differs from prior revision"
                    )
                if (
                    isinstance(statuses, dict)
                    and statuses.get(expected_supersedes) != "SUPERSEDED"
                ):
                    errors.append(
                        f"{goal_id}: prior Work Item revision is not SUPERSEDED"
                    )
                if not successor_semantic_scope_matches(
                    predecessor_node,
                    node,
                ):
                    errors.append(
                        f"{goal_id}: successor semantic scope differs from "
                        "the prior revision"
                    )
        if isinstance(statuses, dict):
            active_revisions = [
                goal_id
                for goal_id in revisions.values()
                if statuses.get(goal_id) != "SUPERSEDED"
            ]
            if len(active_revisions) > 1:
                errors.append(
                    f"{work_item_id}: multiple Work Item revisions are active"
                )
    return errors


def validate_dynamic_goal_inventory(
    root: Path,
    state: dict[str, Any],
    manifest: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    paths_by_id: dict[str, str],
) -> list[str]:
    errors: list[str] = []
    static_ids = set(manifest_node_map(manifest))
    dynamic_ids = {
        goal_id
        for goal_id, node in nodes.items()
        if node.get("goal_kind") == "WORK_ITEM" and goal_id not in static_ids
    }
    inventory = state.get("dynamic_goal_inventory")
    if not isinstance(inventory, dict) or set(inventory) != dynamic_ids:
        return ["dynamic Goal inventory differs from materialized Work Items"]
    history = state.get("transition_history")
    events_by_hash = {
        event.get("event_sha256"): event
        for event in history
        if isinstance(event, dict)
        and isinstance(event.get("event_sha256"), str)
    } if isinstance(history, list) else {}
    for goal_id in sorted(dynamic_ids):
        record = inventory.get(goal_id)
        node = nodes[goal_id]
        if not isinstance(record, dict):
            errors.append(f"{goal_id}: dynamic inventory record is malformed")
            continue
        path = paths_by_id[goal_id]
        resolved = resolve_safe_repo_file(root, path)
        digest = sha256_file(resolved) if resolved is not None else ""
        expected = {
            "artifact_trigger_evidence_refs": node.get(
                "artifact_trigger_evidence_refs"
            ),
            "artifact_work_reason": node.get("artifact_work_reason"),
            "goal_id": goal_id,
            "path": path,
            "sha256": digest,
            "goal_kind": "WORK_ITEM",
            "work_item_type": node.get("work_item_type"),
            "parent_goal_id": node.get("parent_goal_id"),
            "materialized_from_role": node.get("materialized_from_role"),
            "materialized_from_path": node.get("materialized_from_path"),
            "materialized_from_document_id": node.get(
                "materialized_from_document_id"
            ),
            "materialized_from_sha256": node.get("materialized_from_sha256"),
            "predecessor_goal_id": node.get("predecessor_goal_id"),
            "predecessor_goal_content_sha256": node.get(
                "predecessor_goal_content_sha256"
            ),
            "supersedes_goal_id": node.get("supersedes_goal_id"),
            "supersedes_goal_content_sha256": node.get(
                "supersedes_goal_content_sha256"
            ),
        }
        for field, value in expected.items():
            if record.get(field) != value:
                errors.append(f"{goal_id}: dynamic inventory differs: {field}")
        materialized_event_hash = record.get("materialized_event_sha256")
        event = events_by_hash.get(materialized_event_hash)
        if not isinstance(event, dict):
            errors.append(f"{goal_id}: materialization event seal is missing")
            continue
        if event.get("event_type") == "PACKAGE_PREPARED":
            if goal_id != EXPECTED_INITIAL_FOCUS_GOAL_ID:
                errors.append(
                    f"{goal_id}: only the initial Work Item may be prepared with package"
                )
            if event.get("focus_goal_id") != goal_id:
                errors.append(f"{goal_id}: preparation event Goal binding differs")
            if event.get("focus_goal_content_sha256") != digest:
                errors.append(f"{goal_id}: preparation event content hash differs")
        elif event.get("event_type") in {
            "GOAL_MATERIALIZED",
            "GOAL_SUPERSEDED",
        }:
            event_bindings = (
                (
                    "artifact_trigger_evidence_refs",
                    node.get("artifact_trigger_evidence_refs"),
                ),
                (
                    "artifact_work_reason",
                    node.get("artifact_work_reason"),
                ),
                ("materialized_goal_id", goal_id),
                ("materialized_goal_path", path),
                ("materialized_goal_content_sha256", digest),
                ("materialized_from_role", node.get("materialized_from_role")),
                ("materialized_from_path", node.get("materialized_from_path")),
                (
                    "materialized_from_document_id",
                    node.get("materialized_from_document_id"),
                ),
                ("materialized_from_sha256", node.get("materialized_from_sha256")),
                ("predecessor_goal_id", node.get("predecessor_goal_id")),
                (
                    "predecessor_goal_content_sha256",
                    node.get("predecessor_goal_content_sha256"),
                ),
                ("supersedes_goal_id", node.get("supersedes_goal_id")),
                (
                    "supersedes_goal_content_sha256",
                    node.get("supersedes_goal_content_sha256"),
                ),
            )
            for field, value in event_bindings:
                if event.get(field) != value:
                    errors.append(
                        f"{goal_id}: materialization event differs: {field}"
                    )
            errors.extend(
                validate_artifact_trigger_event_bindings(
                    root,
                    label=f"{goal_id}: materialization event",
                    event=event,
                    node=node,
                )
            )
        else:
            errors.append(f"{goal_id}: inventory points to a non-materialization event")
    return errors


def has_cycle(
    nodes: dict[str, dict[str, Any]],
    edge_fields: tuple[str, ...],
) -> bool:
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(goal_id: str) -> bool:
        if goal_id in visiting:
            return True
        if goal_id in visited:
            return False
        visiting.add(goal_id)
        node = nodes.get(goal_id, {})
        targets: list[str] = []
        for field in edge_fields:
            value = node.get(field)
            if isinstance(value, str) and value:
                targets.append(value)
            elif isinstance(value, list):
                targets.extend(item for item in value if isinstance(item, str))
        for target in targets:
            if target in nodes and visit(target):
                return True
        visiting.remove(goal_id)
        visited.add(goal_id)
        return False

    return any(visit(goal_id) for goal_id in nodes)


def validate_graph(
    nodes: dict[str, dict[str, Any]],
    manifest: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    static = manifest_node_map(manifest)
    if EXPECTED_ROOT_GOAL_ID not in nodes:
        errors.append("Goal graph root is missing")
        return errors
    roots = [
        goal_id
        for goal_id, node in nodes.items()
        if node.get("goal_kind") == "MASTER"
    ]
    if roots != [EXPECTED_ROOT_GOAL_ID]:
        errors.append("Goal graph must have exactly the declared root")
    for goal_id, node in nodes.items():
        if goal_id in static:
            declaration = static[goal_id]
            for field in (
                "goal_id",
                "goal_kind",
                "parent_goal_id",
                "priority_rank",
                "start_requires",
                "completion_requires",
                "target_completion_level",
            ):
                if node.get(field) != declaration.get(field):
                    errors.append(f"{goal_id}: Goal metadata differs from manifest: {field}")
            for field in ("external_key", "workstream_type"):
                if node.get(field) != declaration.get(field):
                    errors.append(f"{goal_id}: Goal metadata differs from manifest: {field}")
        parent = node.get("parent_goal_id")
        if goal_id != EXPECTED_ROOT_GOAL_ID and parent not in nodes:
            errors.append(f"{goal_id}: parent Goal is missing")
        if (
            node.get("goal_kind") == "WORK_ITEM"
            and parent in nodes
            and nodes[parent].get("goal_kind") not in {"WORKSTREAM", "MASTER"}
        ):
            errors.append(
                f"{goal_id}: Work Item parent must be a Workstream or Master"
            )
        for field in ("start_requires", "completion_requires"):
            for dependency in string_list(node.get(field)):
                if dependency == goal_id:
                    errors.append(f"{goal_id}: self dependency is prohibited")
                elif dependency not in nodes:
                    errors.append(f"{goal_id}: dependency target is missing: {dependency}")
        for child in string_list(node.get("child_goal_ids")):
            if child not in nodes:
                errors.append(f"{goal_id}: child Goal is missing: {child}")
            elif nodes[child].get("parent_goal_id") != goal_id:
                errors.append(f"{goal_id}: child/parent relation differs: {child}")
    if has_cycle(nodes, ("parent_goal_id",)):
        errors.append("Goal parent graph contains a cycle")
    if has_cycle(nodes, ("start_requires", "completion_requires")):
        errors.append("Goal dependency graph contains a cycle")
    if has_cycle(
        nodes,
        ("parent_goal_id", "start_requires", "completion_requires"),
    ):
        errors.append("combined Goal ownership/dependency graph contains a cycle")
    for goal_id in nodes:
        cursor = goal_id
        seen: set[str] = set()
        while cursor and cursor in nodes and cursor not in seen:
            seen.add(cursor)
            cursor = str(nodes[cursor].get("parent_goal_id", ""))
        if cursor:
            errors.append(f"{goal_id}: Goal does not reach the declared root")
    root_children = set(string_list(nodes[EXPECTED_ROOT_GOAL_ID].get("child_goal_ids")))
    expected_root_children = {
        goal_id
        for goal_id, declaration in static.items()
        if declaration.get("parent_goal_id") == EXPECTED_ROOT_GOAL_ID
    }
    if root_children != expected_root_children:
        errors.append("Master static Workstream child set differs from manifest")
    return errors


def load_policy_gap_mapping(
    root: Path,
    bindings: dict[str, dict[str, Any]],
) -> tuple[list[str], dict[str, str]]:
    return legacy.load_policy_gap_mapping(root, bindings)


def load_effective_policy_subject_ids(
    root: Path,
    binding: dict[str, Any] | None,
) -> tuple[list[str], set[str]]:
    """Resolve the policy/Gate scope carried by an effective baseline chain."""

    if not isinstance(binding, dict):
        return ["POLICY_BASELINE canonical binding is missing"], set()
    initial_path = resolve_safe_repo_file(root, binding.get("path"))
    if initial_path is None or binding.get("file_sha256") != sha256_file(
        initial_path
    ):
        return ["POLICY_BASELINE binding is missing, unsafe, or stale"], set()

    errors: list[str] = []
    subject_ids: set[str] = set()
    visited_paths: set[Path] = set()

    def collect_policy_document(path: Path, expected_sha256: Any) -> None:
        if (
            not isinstance(expected_sha256, str)
            or not SHA256_PATTERN.fullmatch(expected_sha256)
            or sha256_file(path) != expected_sha256
        ):
            errors.append("effective policy source binding SHA-256 differs")
            return
        try:
            payload = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"effective policy source cannot be loaded: {exc}")
            return
        common = payload.get("common_policies")
        features = payload.get("features")
        gates = payload.get("remaining_gates")
        if not all(isinstance(rows, list) for rows in (common, features, gates)):
            errors.append("effective policy source scope lists are missing")
            return
        for rows, prefix in (
            (common, "NPC-"),
            (features, "FP-"),
            (gates, "GATE-"),
        ):
            identifiers = [
                row.get("id")
                for row in rows
                if isinstance(row, dict)
            ]
            if (
                len(identifiers) != len(rows)
                or len(set(identifiers)) != len(identifiers)
                or any(
                    not isinstance(identifier, str)
                    or not identifier.startswith(prefix)
                    for identifier in identifiers
                )
            ):
                errors.append("effective policy source contains invalid scope IDs")
                continue
            subject_ids.update(identifiers)

    def collect_manifest(path: Path, expected_sha256: str) -> None:
        resolved = path.resolve()
        if resolved in visited_paths:
            errors.append("POLICY_BASELINE supersession chain contains a cycle")
            return
        visited_paths.add(resolved)
        if sha256_file(path) != expected_sha256:
            errors.append("POLICY_BASELINE superseded manifest SHA-256 differs")
            return
        try:
            payload = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"POLICY_BASELINE manifest cannot be loaded: {exc}")
            return

        supersedes = payload.get("supersedes")
        if isinstance(supersedes, dict):
            predecessor = resolve_safe_repo_file(
                root,
                supersedes.get("manifest_path"),
            )
            predecessor_sha256 = supersedes.get("manifest_file_sha256")
            if (
                predecessor is None
                or not isinstance(predecessor_sha256, str)
                or not SHA256_PATTERN.fullmatch(predecessor_sha256)
            ):
                errors.append("POLICY_BASELINE predecessor binding is invalid")
            else:
                collect_manifest(predecessor, predecessor_sha256)

        source_bindings = payload.get("source_bindings")
        policy_source = (
            source_bindings.get("policy_json")
            if isinstance(source_bindings, dict)
            else None
        )
        if isinstance(policy_source, dict):
            policy_path = resolve_safe_repo_file(
                root,
                policy_source.get("path"),
            )
            if policy_path is None:
                errors.append("effective policy source path is missing or unsafe")
            else:
                collect_policy_document(
                    policy_path,
                    policy_source.get("sha256"),
                )

        composition = payload.get("composition")
        if isinstance(composition, dict):
            removal_keys = {
                key
                for key in composition
                if any(
                    token in key.lower()
                    for token in ("remove", "delete", "retire", "exclude")
                )
            }
            if removal_keys:
                errors.append(
                    "POLICY_BASELINE scope removal requires "
                    "STATIC_GRAPH_CHANGE_REQUIRES_SUCCESSOR_PACKAGE"
                )
        subject_ids.update(
            identifier
            for identifier in subject_ids_in_value(payload)
            if identifier.startswith(("FP-", "NPC-", "GATE-"))
        )

    collect_manifest(initial_path, str(binding.get("file_sha256")))
    if not subject_ids:
        errors.append("effective POLICY_BASELINE subject scope is empty")
    return errors, subject_ids


def validate_policy_scope_against_mapping(
    root: Path,
    bindings: dict[str, dict[str, Any]],
    mapping: dict[str, str],
) -> list[str]:
    errors, subject_ids = load_effective_policy_subject_ids(
        root,
        bindings.get("POLICY_BASELINE"),
    )
    if subject_ids != set(mapping):
        errors.append(
            "effective POLICY_BASELINE scope differs from the policy/Gap "
            "mapping; STATIC_GRAPH_CHANGE_REQUIRES_SUCCESSOR_PACKAGE"
        )
    mapping_sha256 = canonical_json_sha256(
        [
            {
                "source_policy_id": policy_id,
                "gap_id": mapping[policy_id],
            }
            for policy_id in sorted(mapping)
        ]
    )
    if mapping_sha256 != EXPECTED_POLICY_GAP_MAPPING_SHA256:
        errors.append(
            "policy/Gap pair identity differs from the static manifest; "
            "STATIC_GRAPH_CHANGE_REQUIRES_SUCCESSOR_PACKAGE"
        )
    return errors


def validate_workstreams_against_backlog(
    nodes: dict[str, dict[str, Any]],
    backlog: dict[str, Any],
    mapping: dict[str, str],
) -> list[str]:
    errors: list[str] = []
    workstreams = {
        node.get("external_key"): node
        for node in nodes.values()
        if node.get("goal_kind") == "WORKSTREAM"
        and isinstance(node.get("external_key"), str)
    }
    backlog_epics = {
        item.get("epic_id"): item
        for item in backlog.get("epics", [])
        if isinstance(item, dict) and isinstance(item.get("epic_id"), str)
    }
    if set(workstreams) != set(backlog_epics):
        errors.append("manifest Workstream set differs from canonical Backlog EPIC set")
    execution_order = backlog.get("execution_order")
    if not isinstance(execution_order, list) or set(execution_order) != set(backlog_epics):
        errors.append("Backlog execution_order is not a permutation of EPICs")
        execution_order = []
    covered_policies: list[str] = []
    covered_gaps: list[str] = []
    key_to_goal_id = {
        key: str(node.get("goal_id")) for key, node in workstreams.items()
    }
    for epic_id, epic in backlog_epics.items():
        node = workstreams.get(epic_id)
        if node is None:
            continue
        expected_policies = epic.get("ordered_source_policy_ids")
        if not isinstance(expected_policies, list):
            expected_policies = epic.get("source_policy_ids", [])
        if node.get("source_policy_ids") != expected_policies:
            errors.append(f"{epic_id}: ordered policies differ from canonical Backlog")
        if set(string_list(node.get("gap_ids"))) != set(string_list(epic.get("gap_ids"))):
            errors.append(f"{epic_id}: Gap set differs from canonical Backlog")
        if node.get("target_completion_level") != epic.get(
            "target_completion_level"
        ):
            errors.append(
                f"{epic_id}: target completion level changed; "
                "STATIC_GRAPH_CHANGE_REQUIRES_SUCCESSOR_PACKAGE"
            )
        expected_dependencies = [
            key_to_goal_id[item]
            for item in string_list(epic.get("dependencies"))
            if item in key_to_goal_id
        ]
        expected_start_dependencies = (
            [] if epic_id == "EPIC-12" else expected_dependencies
        )
        if node.get("start_requires") != expected_start_dependencies:
            errors.append(
                f"{epic_id}: start requirements changed; "
                "STATIC_GRAPH_CHANGE_REQUIRES_SUCCESSOR_PACKAGE"
            )
        if node.get("completion_requires") != expected_dependencies:
            errors.append(
                f"{epic_id}: completion requirements changed; "
                "STATIC_GRAPH_CHANGE_REQUIRES_SUCCESSOR_PACKAGE"
            )
        if epic_id in execution_order:
            expected_rank = execution_order.index(epic_id) + 1
            if node.get("priority_rank") != expected_rank:
                errors.append(f"{epic_id}: priority rank differs from Backlog order")
        covered_policies.extend(string_list(node.get("source_policy_ids")))
        covered_gaps.extend(string_list(node.get("gap_ids")))
    if Counter(covered_policies) != Counter(mapping.keys()):
        errors.append("policy set is not covered exactly once by Workstreams")
    if Counter(covered_gaps) != Counter(mapping.values()):
        errors.append("Gap set is not covered exactly once by Workstreams")
    return errors


def validate_artifact_graph(
    root: Path,
    bindings: dict[str, dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    binding = bindings.get("ARTIFACT_REGISTER")
    if not isinstance(binding, dict):
        return ["ARTIFACT_REGISTER canonical binding is missing"]
    path = resolve_safe_repo_file(root, binding.get("path"))
    if path is None:
        return ["ARTIFACT_REGISTER path is missing or unsafe"]
    if binding.get("file_sha256") != sha256_file(path):
        errors.append("ARTIFACT_REGISTER binding SHA-256 differs")
    try:
        register = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"ARTIFACT_REGISTER cannot be loaded: {exc}"]
    artifacts = register.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != 257:
        return errors + ["artifact graph must contain 257 artifact rows"]
    codes = [
        item.get("artifact_type_code")
        for item in artifacts
        if isinstance(item, dict)
    ]
    if len(codes) != len(set(codes)) or any(not isinstance(item, str) for item in codes):
        errors.append("artifact graph type codes are missing or duplicated")
    code_set = set(codes)
    upstream_by_code: dict[str, list[str]] = {}
    upstream_edges: set[tuple[str, str]] = set()
    downstream_edges: set[tuple[str, str]] = set()
    upstream_count = 0
    downstream_count = 0
    for item in artifacts:
        if not isinstance(item, dict):
            errors.append("artifact graph row is malformed")
            continue
        code = item.get("artifact_type_code")
        trace = item.get("trace")
        if not isinstance(code, str) or not isinstance(trace, dict):
            errors.append("artifact graph row identity/trace is malformed")
            continue
        upstream = string_list(trace.get("upstream_types"))
        downstream = string_list(trace.get("downstream_types"))
        if len(upstream) != len(set(upstream)):
            errors.append(f"artifact graph {code} has duplicate upstream edges")
        if len(downstream) != len(set(downstream)):
            errors.append(f"artifact graph {code} has duplicate downstream edges")
        upstream_by_code[code] = upstream
        upstream_count += len(upstream)
        downstream_count += len(downstream)
        upstream_edges.update((target, code) for target in upstream)
        downstream_edges.update((code, target) for target in downstream)
        invalid = [target for target in upstream + downstream if target not in code_set]
        if invalid:
            errors.append(f"artifact graph {code} has missing targets: {invalid}")
        if code in upstream or code in downstream:
            errors.append(f"artifact graph {code} has a self edge")
    if upstream_count != 698 or downstream_count != 698:
        errors.append("artifact graph dependency edge count differs from 698")
    if len(upstream_edges) != 698 or len(downstream_edges) != 698:
        errors.append("artifact graph unique dependency edge count differs from 698")
    if upstream_edges != downstream_edges:
        errors.append("artifact graph upstream/downstream reverse references differ")
    synthetic = {
        code: {"upstream": upstream}
        for code, upstream in upstream_by_code.items()
    }
    if has_cycle(synthetic, ("upstream",)):
        errors.append("artifact dependency graph contains a cycle")
    return errors


def artifact_terminal_state_is_valid(state: Any) -> bool:
    if not isinstance(state, dict):
        return False
    return (
        state.get("lifecycle_status"),
        state.get("verification_status"),
        state.get("approval_status"),
        state.get("baseline_status"),
    ) in {
        (
            "APPROVED_BASELINED",
            "CONTENT_APPROVED_AND_HASH_BOUND",
            "APPROVED",
            "BASELINED",
        ),
        (
            "ACTIVE",
            "OPENING_SNAPSHOT_APPROVED",
            "APPROVED_OPENING_SNAPSHOT",
            "ACTIVE_CONTINUOUSLY_UPDATED",
        ),
    }


ARTIFACT_QUEUE_STATUSES = (
    "INACTIVE",
    "LIVE_GOAL",
    "STRUCTURALLY_DUE",
    "TERMINAL",
    "WAITING_APPLICABILITY",
    "WAITING_TRIGGER",
    "WAITING_UPSTREAM",
)


def artifact_dependency_is_terminal(row: dict[str, Any]) -> bool:
    activation = row.get("activation_result")
    return activation == "NOT_ACTIVE_CURRENT_BASELINE" or (
        activation == "ACTIVE"
        and artifact_terminal_state_is_valid(row.get("state"))
    )


def artifact_work_subject_ids(node: dict[str, Any]) -> list[str]:
    scopes = node.get("output_subject_ids_by_role")
    if not isinstance(scopes, dict):
        return []
    return string_list(scopes.get("ARTIFACT_REGISTER"))


def artifact_live_owner_errors(
    artifact_code: str,
    row: dict[str, Any],
    node: dict[str, Any],
    status: str,
    rows_by_code: dict[str, dict[str, Any]],
) -> list[str]:
    reason = node.get("artifact_work_reason")
    activation = row.get("activation_result")
    state = row.get("state")
    lifecycle = (
        state.get("lifecycle_status")
        if isinstance(state, dict)
        else None
    )
    trigger_refs = string_list(node.get("artifact_trigger_evidence_refs"))
    post_update_terminal = (
        status == "IN_PROGRESS"
        and artifact_dependency_is_terminal(row)
    )
    compatible = {
        "ACTIVE_EVENT_UPDATE": (
            activation == "ACTIVE"
            and bool(trigger_refs)
            and node.get("artifact_trigger_evidence_refs")
            == sorted(set(trigger_refs))
        ),
        "APPLICABILITY_DECISION": (
            activation == "PENDING_EVALUATION" or post_update_terminal
        ),
        "DRAFT_COMPLETION": (
            (
                activation == "ACTIVE"
                and lifecycle == "DRAFT"
            )
            or post_update_terminal
        ),
        "PLANNED_EVIDENCE": (
            (
                activation == "ACTIVE"
                and lifecycle == "PLANNED"
            )
            or post_update_terminal
        ),
    }.get(str(reason), False)
    errors: list[str] = []
    if not compatible:
        errors.append(
            "artifact work queue live owner reason/register lifecycle "
            f"is incompatible: {artifact_code}"
        )
    if not post_update_terminal:
        trace = row.get("trace")
        upstream = string_list(
            trace.get("upstream_types")
            if isinstance(trace, dict)
            else []
        )
        if any(
            dependency not in rows_by_code
            or not artifact_dependency_is_terminal(
                rows_by_code[dependency]
            )
            for dependency in upstream
        ):
            errors.append(
                "artifact work queue live owner bypasses artifact upstream "
                f"eligibility: {artifact_code}"
            )
    return errors


def artifact_topological_rank(
    rows_by_code: dict[str, dict[str, Any]],
) -> dict[str, int]:
    indegree = {
        code: len(
            [
                upstream
                for upstream in string_list(
                    (row.get("trace") or {}).get("upstream_types")
                )
                if upstream in rows_by_code
            ]
        )
        for code, row in rows_by_code.items()
    }
    ready = sorted(code for code, count in indegree.items() if count == 0)
    ordered: list[str] = []
    while ready:
        code = ready.pop(0)
        ordered.append(code)
        trace = rows_by_code[code].get("trace")
        downstream = string_list(
            trace.get("downstream_types")
            if isinstance(trace, dict)
            else []
        )
        for target in downstream:
            if target not in indegree:
                continue
            indegree[target] -= 1
            if indegree[target] == 0:
                ready.append(target)
                ready.sort()
    if len(ordered) != len(rows_by_code):
        ordered.extend(sorted(set(rows_by_code) - set(ordered)))
    return {code: index for index, code in enumerate(ordered)}


def derive_artifact_work_queue_from_register(
    source_binding: dict[str, Any],
    register: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, str],
) -> tuple[list[str], dict[str, Any]]:
    errors: list[str] = []
    artifacts = register.get("artifacts")
    if not isinstance(artifacts, list):
        return ["artifact work queue source register is malformed"], {}
    rows_by_code = {
        row.get("artifact_type_code"): row
        for row in artifacts
        if isinstance(row, dict)
        and isinstance(row.get("artifact_type_code"), str)
    }
    if len(rows_by_code) != len(artifacts):
        errors.append("artifact work queue source codes are missing or duplicated")

    live_owners_by_code: dict[str, list[str]] = {}
    live_owner_nodes_by_code: dict[str, list[tuple[str, dict[str, Any], str]]] = {}
    completed_owners_by_code: dict[str, list[str]] = {}
    for goal_id, node in nodes.items():
        if (
            node.get("goal_kind") != "WORK_ITEM"
            or node.get("work_item_type") != "ARTIFACT_WORK"
        ):
            continue
        target_ids = artifact_work_subject_ids(node)
        if len(target_ids) != 1:
            continue
        target_id = target_ids[0]
        status = statuses.get(goal_id)
        if status is None:
            continue
        if status in TERMINAL_STATUSES:
            if status == "COMPLETE_AT_TARGET":
                completed_owners_by_code.setdefault(target_id, []).append(
                    goal_id
                )
        else:
            live_owners_by_code.setdefault(target_id, []).append(goal_id)
            live_owner_nodes_by_code.setdefault(target_id, []).append(
                (goal_id, node, status)
            )

    duplicate_live = {
        code: sorted(owner_ids)
        for code, owner_ids in live_owners_by_code.items()
        if len(owner_ids) != 1
    }
    if duplicate_live:
        errors.append(
            "artifact work queue has duplicate live subject ownership: "
            f"{duplicate_live}"
        )

    for code, owners in sorted(live_owner_nodes_by_code.items()):
        row = rows_by_code.get(code)
        if not isinstance(row, dict):
            errors.append(
                "artifact work queue live owner targets an absent register "
                f"subject: {code}"
            )
            continue
        for _, node, status in owners:
            errors.extend(
                artifact_live_owner_errors(
                    code,
                    row,
                    node,
                    status,
                    rows_by_code,
                )
            )

    for code, owner_ids in completed_owners_by_code.items():
        row = rows_by_code.get(code)
        if isinstance(row, dict) and not artifact_dependency_is_terminal(row):
            errors.append(
                "completed ARTIFACT_WORK still has a nonterminal register "
                f"subject: {code} ({sorted(owner_ids)})"
            )

    structural_candidates = {
        code
        for code, row in rows_by_code.items()
        if row.get("activation_result") == "ACTIVE"
        and isinstance(row.get("state"), dict)
        and row["state"].get("lifecycle_status") in {"DRAFT", "PLANNED"}
        and code not in live_owners_by_code
        and all(
            upstream in rows_by_code
            and artifact_dependency_is_terminal(rows_by_code[upstream])
            for upstream in string_list(
                (row.get("trace") or {}).get("upstream_types")
            )
        )
    }
    applicability_candidates = {
        code
        for code, row in rows_by_code.items()
        if row.get("activation_result") == "PENDING_EVALUATION"
        and code not in live_owners_by_code
        and all(
            upstream in rows_by_code
            and artifact_dependency_is_terminal(rows_by_code[upstream])
            for upstream in string_list(
                (row.get("trace") or {}).get("upstream_types")
            )
        )
    }
    topo_rank = artifact_topological_rank(rows_by_code)

    def priority_rank(code: str) -> int:
        priority = rows_by_code[code].get("priority")
        if (
            isinstance(priority, str)
            and priority.startswith("P")
            and priority[1:].isdigit()
        ):
            return int(priority[1:])
        return 99

    def immediate_unlock_count(code: str) -> int:
        trace = rows_by_code[code].get("trace")
        downstream = string_list(
            trace.get("downstream_types")
            if isinstance(trace, dict)
            else []
        )
        return sum(
            1
            for target in downstream
            if target in rows_by_code
            and rows_by_code[target].get("activation_result") == "ACTIVE"
            and isinstance(rows_by_code[target].get("state"), dict)
            and rows_by_code[target]["state"].get("lifecycle_status")
            in {"DRAFT", "PLANNED"}
            and target not in live_owners_by_code
            and all(
                upstream == code
                or (
                    upstream in rows_by_code
                    and artifact_dependency_is_terminal(
                        rows_by_code[upstream]
                    )
                )
                for upstream in string_list(
                    (rows_by_code[target].get("trace") or {}).get(
                        "upstream_types"
                    )
                )
            )
        )

    def selection_key(code: str) -> tuple[int, int, int, str]:
        return (
            priority_rank(code),
            -immediate_unlock_count(code),
            topo_rank.get(code, 10**9),
            code,
        )

    ordered_structural_candidates = sorted(
        structural_candidates,
        key=selection_key,
    )
    ordered_assessment_candidates = sorted(
        structural_candidates | applicability_candidates,
        key=selection_key,
    )

    partition = {status: [] for status in ARTIFACT_QUEUE_STATUSES}
    for code in sorted(rows_by_code):
        row = rows_by_code[code]
        activation = row.get("activation_result")
        state = row.get("state")
        lifecycle = (
            state.get("lifecycle_status")
            if isinstance(state, dict)
            else None
        )
        trace = row.get("trace")
        upstream = string_list(
            trace.get("upstream_types")
            if isinstance(trace, dict)
            else []
        )
        if code in live_owners_by_code:
            queue_status = "LIVE_GOAL"
        elif activation == "NOT_ACTIVE_CURRENT_BASELINE":
            queue_status = "INACTIVE"
        elif activation == "PENDING_EVALUATION":
            queue_status = "WAITING_APPLICABILITY"
        elif activation == "ACTIVE" and artifact_terminal_state_is_valid(state):
            queue_status = "TERMINAL"
        elif activation == "ACTIVE" and lifecycle in {"DRAFT", "PLANNED"}:
            blockers = (
                state.get("blockers")
                if isinstance(state, dict)
                else None
            )
            if isinstance(blockers, list) and blockers:
                queue_status = "WAITING_TRIGGER"
            elif any(
                target not in rows_by_code
                or not artifact_dependency_is_terminal(rows_by_code[target])
                for target in upstream
            ):
                queue_status = "WAITING_UPSTREAM"
            else:
                queue_status = "STRUCTURALLY_DUE"
        else:
            queue_status = "WAITING_TRIGGER"
            errors.append(
                f"artifact work queue cannot classify register subject: {code}"
            )
        partition[queue_status].append(code)

    due_ids = partition["STRUCTURALLY_DUE"]
    next_assessment_target_id = (
        ordered_assessment_candidates[0]
        if ordered_assessment_candidates
        else None
    )
    next_assessment_row = rows_by_code.get(
        str(next_assessment_target_id),
        {},
    )
    next_assessment_state = next_assessment_row.get("state")
    next_assessment_lifecycle = (
        next_assessment_state.get("lifecycle_status")
        if isinstance(next_assessment_state, dict)
        else None
    )
    next_assessment_reason = (
        "APPLICABILITY_DECISION"
        if next_assessment_row.get("activation_result")
        == "PENDING_EVALUATION"
        else "DRAFT_COMPLETION"
        if next_assessment_lifecycle == "DRAFT"
        else "PLANNED_EVIDENCE"
        if next_assessment_lifecycle == "PLANNED"
        else None
    )
    next_due_target_id = next(
        (
            code
            for code in ordered_structural_candidates
            if code in set(due_ids)
        ),
        None,
    )
    queue = {
        "schema_version": "1.0",
        "source_binding": {
            field: source_binding.get(field)
            for field in ("role", "document_id", "path", "file_sha256")
        },
        "artifact_code_count": len(rows_by_code),
        "selection_policy": (
            "PRIORITY_THEN_UNLOCK_DESC_THEN_TOPO_RANK_THEN_ARTIFACT_CODE"
        ),
        "partition_by_status": partition,
        "counts_by_status": {
            status: len(partition[status])
            for status in ARTIFACT_QUEUE_STATUSES
        },
        "partition_sha256": canonical_json_sha256(partition),
        "structural_candidate_ids": ordered_structural_candidates,
        "assessment_policy": (
            "INTERNAL_READY_FRONTIER_THEN_PRIORITY_UNLOCK_TOPO_CODE"
        ),
        "next_assessment_target_id": next_assessment_target_id,
        "next_assessment_queue_status": (
            next(
                (
                    status
                    for status in ARTIFACT_QUEUE_STATUSES
                    if next_assessment_target_id in partition[status]
                ),
                None,
            )
            if next_assessment_target_id is not None
            else None
        ),
        "next_assessment_required_work_reason": next_assessment_reason,
        "next_assessment_required_outcomes": (
            [
                "MATERIALIZE_ARTIFACT_WORK",
                "CANONICAL_REGISTER_TERMINAL_DISPOSITION",
            ]
            if next_assessment_target_id is not None
            else []
        ),
        "next_due_target_id": next_due_target_id,
    }
    return errors, queue


def derive_artifact_work_queue(
    root: Path,
    bindings: dict[str, dict[str, Any]],
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, str],
) -> tuple[list[str], dict[str, Any]]:
    binding = bindings.get("ARTIFACT_REGISTER")
    if not isinstance(binding, dict):
        return ["artifact work queue lacks ARTIFACT_REGISTER binding"], {}
    path = resolve_safe_repo_file(root, binding.get("path"))
    if (
        path is None
        or binding.get("file_sha256") != sha256_file(path)
    ):
        return ["artifact work queue source binding differs"], {}
    try:
        register = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"artifact work queue source cannot be loaded: {exc}"], {}
    return derive_artifact_work_queue_from_register(
        binding,
        register,
        nodes,
        statuses,
    )


def artifact_assessment_obligation_target(
    previous_runtime: Any,
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, str],
) -> str | None:
    if not isinstance(previous_runtime, dict):
        return None
    previous_queue = previous_runtime.get("artifact_work_queue")
    previous_boundary = previous_runtime.get("completion_boundary")
    if not isinstance(previous_queue, dict) or not isinstance(
        previous_boundary,
        dict,
    ):
        return None
    previous_partition = previous_queue.get("partition_by_status")
    previous_live_targets = set(
        string_list(
            previous_partition.get("LIVE_GOAL")
            if isinstance(previous_partition, dict)
            else []
        )
    )
    candidate = previous_queue.get("next_assessment_target_id")
    prior_internal_runnable = string_list(
        previous_boundary.get("internal_runnable_goal_ids")
    )
    prior_internal_in_progress = any(
        status == "IN_PROGRESS"
        and not (
            nodes.get(goal_id, {}).get("goal_kind") == "WORK_ITEM"
            and nodes.get(goal_id, {}).get("work_item_type")
            in DYNAMIC_EXTERNAL_TYPES
        )
        for goal_id, status in statuses.items()
    )
    if (
        not isinstance(candidate, str)
        or not candidate
        or candidate in previous_live_targets
        or prior_internal_runnable
        or prior_internal_in_progress
    ):
        return None
    return candidate


def artifact_assessment_transition_satisfied(
    *,
    target_id: str,
    event_type: Any,
    event: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    queue_after: dict[str, Any],
) -> bool:
    partition = queue_after.get("partition_by_status")
    if not isinstance(partition, dict):
        return False
    if event_type == "GOAL_MATERIALIZED":
        materialized_node = nodes.get(
            str(event.get("materialized_goal_id")),
            {},
        )
        return (
            artifact_work_subject_ids(materialized_node) == [target_id]
            and target_id
            in set(string_list(partition.get("LIVE_GOAL")))
        )
    if event_type == "CANONICAL_BINDINGS_UPDATED":
        return target_id in (
            set(string_list(partition.get("TERMINAL")))
            | set(string_list(partition.get("INACTIVE")))
        )
    return False


def unconsumed_structurally_due_ids(runtime: Any) -> list[str]:
    queue = (
        runtime.get("artifact_work_queue")
        if isinstance(runtime, dict)
        else None
    )
    partition = (
        queue.get("partition_by_status")
        if isinstance(queue, dict)
        else None
    )
    return string_list(
        partition.get("STRUCTURALLY_DUE")
        if isinstance(partition, dict)
        else []
    )


REQUEST_CONTRACT_BY_OWNER = {
    "USER": {
        "request_kind": "USER_DECISION",
        "authority_requirement": "USER_AUTHORIZATION",
    },
    "EXTERNAL": {
        "request_kind": "EXTERNAL_ACTION_EVIDENCE",
        "authority_requirement": "EXTERNAL_ATTESTATION",
    },
}


def request_logical_basis(record: dict[str, Any]) -> dict[str, Any]:
    blocker_id = record.get("blocker_id")
    roles = [
        (
            "BLOCKER_RESOLUTION::<BLOCKER_ID>"
            if isinstance(blocker_id, str)
            and role == f"BLOCKER_RESOLUTION::{blocker_id}"
            else role
        )
        for role in string_list(
            record.get("required_resolution_evidence_roles")
        )
    ]
    return {
        "owner": record.get("owner"),
        "request_kind": record.get("request_kind"),
        "requested_action": record.get("requested_action"),
        "target_goal_path": record.get("target_goal_path"),
        "target_goal_content_sha256": record.get(
            "target_goal_content_sha256"
        ),
        "target_work_item_type": record.get("target_work_item_type"),
        "required_resolution_evidence_roles": roles,
        "authority_requirement": record.get("authority_requirement"),
    }


def deterministic_request_key(record: dict[str, Any]) -> str:
    return (
        "WS-REQUEST-"
        + canonical_json_sha256(request_logical_basis(record)).upper()
    )


def request_basis_row(record: dict[str, Any]) -> dict[str, Any]:
    return {
        "request_key": record.get("request_key"),
        "request_kind": record.get("request_kind"),
        "requested_action": record.get("requested_action"),
        "target_goal_id": record.get("blocks_goal_id"),
        "target_goal_path": record.get("target_goal_path"),
        "target_goal_content_sha256": record.get(
            "target_goal_content_sha256"
        ),
        "target_work_item_type": record.get("target_work_item_type"),
        "blocker_id": record.get("blocker_id"),
        "blocker_snapshot_sha256": record.get(
            "blocker_snapshot_sha256"
        ),
        "blocker_event_id": record.get("request_event_id"),
        "required_resolution_evidence_roles": record.get(
            "required_resolution_evidence_roles"
        ),
        "authority_requirement": record.get("authority_requirement"),
    }


def validate_typed_request_record(
    record: dict[str, Any],
    *,
    nodes: dict[str, dict[str, Any]] | None = None,
    paths_by_id: dict[str, str] | None = None,
    root: Path | None = None,
) -> list[str]:
    owner = record.get("owner")
    expected = REQUEST_CONTRACT_BY_OWNER.get(str(owner))
    if expected is None:
        return []
    blocker_id = record.get("blocker_id")
    request_key = record.get("request_key")
    action = record.get("requested_action")
    event_id = record.get("request_event_id")
    target_path = record.get("target_goal_path")
    target_hash = record.get("target_goal_content_sha256")
    target_work_type = record.get("target_work_item_type")
    blocker_snapshot_hash = record.get("blocker_snapshot_sha256")
    roles = string_list(
        record.get("required_resolution_evidence_roles")
    )
    errors: list[str] = []
    if (
        not isinstance(blocker_id, str)
        or not blocker_id.strip()
        or not SAFE_ID_PATTERN.fullmatch(blocker_id)
        or not isinstance(request_key, str)
        or not request_key.strip()
        or not isinstance(action, str)
        or not action.strip()
        or action != action.strip()
        or not isinstance(event_id, str)
        or not event_id.strip()
        or not SAFE_ID_PATTERN.fullmatch(event_id)
        or not isinstance(target_path, str)
        or not target_path.strip()
        or not isinstance(target_hash, str)
        or not SHA256_PATTERN.fullmatch(target_hash)
        or not isinstance(target_work_type, str)
        or not isinstance(blocker_snapshot_hash, str)
        or not SHA256_PATTERN.fullmatch(blocker_snapshot_hash)
        or request_key != deterministic_request_key(record)
        or record.get("request_kind") != expected["request_kind"]
        or record.get("authority_requirement")
        != expected["authority_requirement"]
        or record.get("required_resolution_evidence_roles") != roles
        or roles != [f"BLOCKER_RESOLUTION::{blocker_id}"]
    ):
        errors.append("typed blocker request contract differs")
    if owner == "USER" and (
        not isinstance(record.get("prompt"), str)
        or not record.get("prompt").strip()
        or record.get("prompt") != record.get("prompt").strip()
    ):
        errors.append(
            "typed USER blocker question is blank or not normalized"
        )
    goal_id = record.get("blocks_goal_id")
    if nodes is not None and paths_by_id is not None and root is not None:
        node = nodes.get(str(goal_id), {})
        relative = paths_by_id.get(str(goal_id))
        goal_path = resolve_safe_repo_file(root, relative)
        expected_work_type = (
            node.get("work_item_type")
            if node.get("goal_kind") == "WORK_ITEM"
            else ""
        )
        if (
            not node
            or goal_path is None
            or record.get("target_goal_path") != relative
            or record.get("target_goal_content_sha256")
            != sha256_file(goal_path)
            or record.get("target_work_item_type") != expected_work_type
        ):
            errors.append("typed blocker request target binding differs")
    return errors


def external_action_request_basis(
    blockers: dict[str, list[dict[str, Any]]],
) -> list[dict[str, Any]]:
    rows = [
        request_basis_row(record)
        for records in blockers.values()
        if isinstance(records, list)
        for record in records
        if isinstance(record, dict) and record.get("owner") == "EXTERNAL"
    ]
    return sorted(
        rows,
        key=lambda row: (
            str(row.get("request_key", "")),
            str(row.get("blocker_id", "")),
        ),
    )


def pending_user_questions(
    blockers: dict[str, list[dict[str, Any]]],
    *,
    delivery_deferred: bool = False,
) -> list[dict[str, Any]]:
    return sorted(
        [
            {
                "request_key": record.get("request_key"),
                "goal_id": goal_id,
                "blocker_id": record.get("blocker_id"),
                "question": record.get("prompt"),
                "delivery_status": (
                    "DEFERRED_INTERNAL_FRONTIER"
                    if delivery_deferred
                    else "READY_FOR_USER"
                ),
            }
            for goal_id, records in blockers.items()
            if isinstance(records, list)
            for record in records
            if isinstance(record, dict) and record.get("owner") == "USER"
        ],
        key=lambda question: (
            str(question.get("request_key", "")),
            str(question.get("blocker_id", "")),
        ),
    )


def blocker_request_targets_deterministic_focus(
    subject_goal_id: Any,
    previous_runtime: Any,
    ready_goal_ids: list[str],
) -> bool:
    return bool(
        ready_goal_ids
        and isinstance(previous_runtime, dict)
        and subject_goal_id == ready_goal_ids[0]
        and subject_goal_id == previous_runtime.get("focus_goal_id")
    )


def external_action_packet_is_required(
    previous_boundary: Any,
    current_boundary: dict[str, Any],
) -> bool:
    return bool(
        current_boundary.get("repository_scope_status")
        == "COMPLETE_AWAITING_EXTERNAL"
        and (
            not isinstance(previous_boundary, dict)
            or previous_boundary.get("repository_scope_status")
            != "COMPLETE_AWAITING_EXTERNAL"
            or previous_boundary.get(
                "external_action_request_basis_sha256"
            )
            != current_boundary.get(
                "external_action_request_basis_sha256"
            )
        )
    )


def derive_completion_boundary(
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, str],
    ready_goal_ids: list[str],
    blockers: dict[str, list[dict[str, Any]]],
    artifact_work_queue: dict[str, Any],
    *,
    package_status: str,
) -> tuple[list[str], dict[str, Any]]:
    errors: list[str] = []
    internal_runnable = [
        goal_id
        for goal_id in ready_goal_ids
        if not (
            nodes.get(goal_id, {}).get("goal_kind") == "WORK_ITEM"
            and nodes.get(goal_id, {}).get("work_item_type")
            in DYNAMIC_EXTERNAL_TYPES
        )
    ]
    open_goal_ids = {
        goal_id
        for goal_id, status in statuses.items()
        if status not in TERMINAL_STATUSES
    }
    open_children_by_parent: dict[str, set[str]] = {}
    for goal_id in open_goal_ids:
        parent_id = nodes.get(goal_id, {}).get("parent_goal_id")
        if isinstance(parent_id, str) and parent_id:
            open_children_by_parent.setdefault(parent_id, set()).add(goal_id)
    internal_pending = sorted(
        goal_id
        for goal_id in open_goal_ids
        if (
            statuses.get(goal_id) != "AWAITING_EXTERNAL"
            and not (
                nodes.get(goal_id, {}).get("goal_kind") == "WORK_ITEM"
                and nodes.get(goal_id, {}).get("work_item_type")
                in DYNAMIC_EXTERNAL_TYPES
            )
            and not open_children_by_parent.get(goal_id)
        )
    )
    external_waiting = sorted(
        {
            goal_id
            for goal_id, status in statuses.items()
            if status == "AWAITING_EXTERNAL"
        }
        | {
            goal_id
            for goal_id in open_goal_ids
            if (
                nodes.get(goal_id, {}).get("goal_kind") == "WORK_ITEM"
                and nodes.get(goal_id, {}).get("work_item_type")
                in DYNAMIC_EXTERNAL_TYPES
            )
        }
    )
    partition = artifact_work_queue.get("partition_by_status")
    if not isinstance(partition, dict):
        errors.append("completion boundary artifact work queue is malformed")
        partition = {}
    artifact_pending = sorted(
        {
            artifact_code
            for status in (
                "LIVE_GOAL",
                "STRUCTURALLY_DUE",
                "WAITING_APPLICABILITY",
                "WAITING_TRIGGER",
                "WAITING_UPSTREAM",
            )
            for artifact_code in string_list(partition.get(status))
        }
    )
    user_request_keys: list[str] = []
    external_request_keys: list[str] = []
    all_request_keys: list[str] = []
    external_blocker_ids: set[str] = set()
    for goal_id, records in blockers.items():
        if not isinstance(records, list):
            continue
        for record in records:
            if not isinstance(record, dict):
                continue
            request_key = record.get("request_key")
            owner = record.get("owner")
            if owner == "USER":
                errors.extend(validate_typed_request_record(record))
                if isinstance(request_key, str) and request_key:
                    user_request_keys.append(request_key)
                    all_request_keys.append(request_key)
            elif owner == "EXTERNAL":
                errors.extend(validate_typed_request_record(record))
                blocker_id = record.get("blocker_id")
                if isinstance(blocker_id, str) and blocker_id:
                    external_blocker_ids.add(blocker_id)
                if isinstance(request_key, str) and request_key:
                    external_request_keys.append(request_key)
                    all_request_keys.append(request_key)
    if len(user_request_keys) != len(set(user_request_keys)):
        errors.append("completion boundary has duplicate user decision requests")
    if len(external_request_keys) != len(set(external_request_keys)):
        errors.append("completion boundary has duplicate external action requests")
    if len(all_request_keys) != len(set(all_request_keys)):
        errors.append(
            "completion boundary has duplicate request identity across owners"
        )
    user_request_keys = sorted(set(user_request_keys))
    external_request_keys = sorted(set(external_request_keys))
    for goal_id in external_waiting:
        node = nodes.get(goal_id, {})
        source_blocker_ids = set(
            string_list(node.get("source_blocker_ids"))
        )
        if (
            node.get("goal_kind") == "WORK_ITEM"
            and node.get("work_item_type") in DYNAMIC_EXTERNAL_TYPES
            and (
                not source_blocker_ids
                or not source_blocker_ids.issubset(
                    external_blocker_ids
                )
            )
        ):
            errors.append(
                "external waiting Goal lacks an active typed EXTERNAL "
                f"request blocker: {goal_id}"
            )

    if package_status == "COMPLETED":
        if (
            internal_runnable
            or internal_pending
            or artifact_pending
            or external_waiting
            or user_request_keys
            or external_request_keys
        ):
            errors.append(
                "completed package still has internal, artifact, user, or "
                "external pending work"
            )
            repository_status = "IN_PROGRESS"
            project_status = "NOT_COMPLETE"
        else:
            repository_status = "COMPLETE"
            project_status = "COMPLETE"
    elif (
        not internal_runnable
        and not internal_pending
        and not artifact_pending
        and bool(external_request_keys)
        and not user_request_keys
    ):
        repository_status = "COMPLETE_AWAITING_EXTERNAL"
        project_status = "NOT_COMPLETE"
    else:
        repository_status = "IN_PROGRESS"
        project_status = "NOT_COMPLETE"
    request_basis = external_action_request_basis(blockers)
    request_basis_sha256 = (
        canonical_json_sha256(request_basis) if request_basis else ""
    )
    packet_id = (
        "WS-EXTERNAL-ACTION-PACKET-"
        + request_basis_sha256[:16].upper()
        if repository_status == "COMPLETE_AWAITING_EXTERNAL"
        else ""
    )
    return errors, {
        "schema_version": "1.0",
        "repository_scope_status": repository_status,
        "project_status": project_status,
        "internal_runnable_goal_ids": internal_runnable,
        "internal_pending_goal_ids": internal_pending,
        "artifact_pending_target_ids": artifact_pending,
        "external_waiting_goal_ids": external_waiting,
        "pending_user_decision_request_keys": user_request_keys,
        "pending_external_action_request_keys": external_request_keys,
        "external_action_request_basis_sha256": request_basis_sha256,
        "external_action_packet_id": packet_id,
    }


def validate_project_artifact_completion(
    root: Path,
    bindings: dict[str, dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    register_binding = bindings.get("ARTIFACT_REGISTER")
    receipt_binding = bindings.get("ARTIFACT_APPLICATION_RECEIPT")
    if not isinstance(register_binding, dict) or not isinstance(
        receipt_binding,
        dict,
    ):
        return ["project completion lacks artifact register/application bindings"]
    register_path = resolve_safe_repo_file(root, register_binding.get("path"))
    receipt_path = resolve_safe_repo_file(root, receipt_binding.get("path"))
    if (
        register_path is None
        or register_binding.get("file_sha256") != sha256_file(register_path)
        or receipt_path is None
        or receipt_binding.get("file_sha256") != sha256_file(receipt_path)
    ):
        return ["project completion artifact binding hash differs"]
    try:
        register = load_json(register_path)
        receipt = load_json(receipt_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"project completion artifact control cannot be loaded: {exc}"]
    artifacts = register.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != 257:
        return ["project completion artifact register differs from 257 rows"]
    incomplete: list[str] = []
    undecided: list[str] = []
    invalid_inactive: list[str] = []
    for row in artifacts:
        if not isinstance(row, dict):
            incomplete.append("<MALFORMED>")
            continue
        code = str(row.get("display_code", "<UNKNOWN>"))
        applicability = row.get("applicability")
        activation = row.get("activation_result")
        state = row.get("state")
        if not isinstance(state, dict):
            incomplete.append(code)
            continue
        blockers = string_list(state.get("blockers"))
        if activation == "PENDING_EVALUATION":
            undecided.append(code)
            continue
        if activation == "NOT_ACTIVE_CURRENT_BASELINE":
            if (
                applicability != "CONDITIONAL"
                or not isinstance(row.get("n_a_reason"), str)
                or not row.get("n_a_reason")
                or blockers != ["NOT_ACTIVE_UNDER_CURRENT_BASELINE"]
            ):
                invalid_inactive.append(code)
            continue
        if activation != "ACTIVE" or (
            applicability == "REQUIRED" and activation != "ACTIVE"
        ):
            incomplete.append(code)
            continue
        if (
            not artifact_terminal_state_is_valid(state)
            or blockers
        ):
            incomplete.append(code)
    if undecided:
        errors.append(
            "project completion has unevaluated conditional artifacts: "
            f"{sorted(undecided)}"
        )
    if invalid_inactive:
        errors.append(
            "project completion has invalid inactive artifact decisions: "
            f"{sorted(invalid_inactive)}"
        )
    if incomplete:
        errors.append(
            "project completion has nonterminal active artifacts: "
            f"{sorted(incomplete)}"
        )
    metadata = receipt.get("metadata")
    authority = receipt.get("authority_boundary")
    approval_application = register.get("approval_application")
    expected_register_binding = {
        "role": "ARTIFACT_REGISTER",
        "path": register_binding.get("path"),
        "document_id": register_binding.get("document_id"),
        "file_sha256": register_binding.get("file_sha256"),
    }
    errors.extend(
        "project completion artifact approval "
        + error
        for error in legacy.validate_external_attestation_anchor(
            "ARTIFACT_APPLICATION_RECEIPT",
            sha256_file(receipt_path),
        )
    )
    if (
        not isinstance(metadata, dict)
        or metadata.get("transaction_status") != "COMMITTED"
        or metadata.get("receipt_id") != receipt_binding.get("document_id")
        or not isinstance(authority, dict)
        or authority.get("implementation_conformance_assessed") is not True
        or authority.get("execution_or_test_completion_claimed") is not True
        or authority.get("remaining_gates_are_waived") is not False
        or authority.get("release_status") != "ELIGIBLE"
        or not isinstance(approval_application, dict)
        or approval_application.get("commit_receipt_id")
        != metadata.get("receipt_id")
        or approval_application.get("commit_receipt_path")
        != receipt_binding.get("path")
        or approval_application.get("commit_receipt_sha256")
        != receipt_binding.get("file_sha256")
        or receipt.get("artifact_register_binding")
        != expected_register_binding
    ):
        errors.append(
            "project completion artifact application receipt is not a "
            "committed terminal approval"
        )
    return errors


def goal_path_by_id(
    nodes: dict[str, dict[str, Any]],
    paths: dict[str, str],
) -> dict[str, str]:
    return {goal_id: paths[goal_id] for goal_id in nodes if goal_id in paths}


def ready_frontier(
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, str],
    materialized_children: dict[str, list[str]],
    blockers: dict[str, list[dict[str, Any]]],
    backlog: dict[str, Any],
) -> list[str]:
    candidates: list[str] = []
    for goal_id, node in nodes.items():
        if goal_id == EXPECTED_ROOT_GOAL_ID:
            continue
        status = statuses.get(goal_id)
        if status not in RUNNABLE_STATUSES:
            continue
        unfinished_children = [
            child
            for child in materialized_children.get(goal_id, [])
            if statuses.get(child) not in TERMINAL_STATUSES
        ]
        if unfinished_children:
            continue
        if blockers.get(goal_id):
            continue
        parent = node.get("parent_goal_id")
        ancestor = str(parent or "")
        ancestor_unavailable = False
        seen_ancestors: set[str] = set()
        while ancestor and ancestor in nodes and ancestor not in seen_ancestors:
            seen_ancestors.add(ancestor)
            if (
                statuses.get(ancestor) not in RUNNABLE_STATUSES
                or blockers.get(ancestor)
            ):
                ancestor_unavailable = True
                break
            ancestor = str(nodes[ancestor].get("parent_goal_id") or "")
        if ancestor_unavailable:
            continue
        if any(
            statuses.get(dependency) != "COMPLETE_AT_TARGET"
            for dependency in string_list(node.get("start_requires"))
        ):
            continue
        candidates.append(goal_id)

    next_action = backlog.get("next_single_action")
    next_work_item = (
        next_action.get("work_item_id")
        if isinstance(next_action, dict)
        else None
    )
    epic_priority = {
        item.get("epic_id"): item.get("priority")
        for item in backlog.get("epics", [])
        if isinstance(item, dict) and isinstance(item.get("epic_id"), str)
    }

    def workstream_for(goal_id: str) -> dict[str, Any]:
        cursor = goal_id
        seen: set[str] = set()
        while cursor in nodes and cursor not in seen:
            seen.add(cursor)
            node = nodes[cursor]
            if node.get("goal_kind") == "WORKSTREAM":
                return node
            cursor = str(node.get("parent_goal_id", ""))
        return {}

    def risk_rank(goal_id: str) -> int:
        node = nodes[goal_id]
        risk = node.get("risk_priority")
        if not isinstance(risk, str):
            workstream = workstream_for(goal_id)
            risk = epic_priority.get(workstream.get("external_key"))
        if isinstance(risk, str) and risk.startswith("P") and risk[1:].isdigit():
            return int(risk[1:])
        return 99

    def unlock_count(goal_id: str) -> int:
        return sum(
            1
            for candidate_id, candidate in nodes.items()
            if candidate_id != goal_id
            and statuses.get(candidate_id) not in TERMINAL_STATUSES
            and goal_id in string_list(candidate.get("start_requires"))
        )

    def sort_key(goal_id: str) -> tuple[int, int, int, int, int, str]:
        node = nodes[goal_id]
        already_started = int(statuses.get(goal_id) != "IN_PROGRESS")
        canonical_next = int(node.get("work_item_id") != next_work_item)
        rank = node.get("priority_rank")
        return (
            already_started,
            canonical_next,
            risk_rank(goal_id),
            -unlock_count(goal_id),
            rank if isinstance(rank, int) else 10**9,
            goal_id,
        )

    return sorted(candidates, key=sort_key)


def validate_current_work_item(
    root: Path,
    node: dict[str, Any],
    goal_path: str,
    mapping: dict[str, str],
) -> list[str]:
    errors: list[str] = []
    if node.get("goal_kind") != "WORK_ITEM":
        return errors
    if node.get("work_item_type") != "POLICY_GAP_WORK":
        return errors
    policies = string_list(node.get("source_policy_ids"))
    gaps = string_list(node.get("gap_ids"))
    if len(policies) != 1 or len(gaps) != 1:
        errors.append("POLICY_GAP_WORK must bind exactly one policy and one Gap")
    elif mapping.get(policies[0]) != gaps[0]:
        errors.append("POLICY_GAP_WORK policy/Gap pair differs from canonical mapping")
    if node.get("materialized_from_role") != "IMPLEMENTATION_BACKLOG":
        errors.append("focus POLICY_GAP_WORK materialization role differs")
    path = resolve_safe_repo_file(root, goal_path)
    if path is None:
        errors.append("focus Goal path is missing or unsafe")
    return errors


@contextmanager
def semantic_legacy_goal_ids(
    *,
    formal_goal_id: str | None = None,
    delivery_goal_id: str | None = None,
    handover_goal_id: str | None = None,
) -> Iterator[None]:
    old_phases = dict(legacy.EXPECTED_PHASE_IDS)
    old_epics = dict(legacy.EXPECTED_EPIC_GOAL_IDS)
    try:
        if formal_goal_id:
            legacy.EXPECTED_PHASE_IDS["B"] = formal_goal_id
            legacy.EXPECTED_EPIC_GOAL_IDS["EPIC-12"] = formal_goal_id
        if delivery_goal_id:
            legacy.EXPECTED_PHASE_IDS["C"] = delivery_goal_id
        if handover_goal_id:
            legacy.EXPECTED_PHASE_IDS["D"] = handover_goal_id
        yield
    finally:
        legacy.EXPECTED_PHASE_IDS.clear()
        legacy.EXPECTED_PHASE_IDS.update(old_phases)
        legacy.EXPECTED_EPIC_GOAL_IDS.clear()
        legacy.EXPECTED_EPIC_GOAL_IDS.update(old_epics)


def replace_legacy_phase_labels(errors: list[str]) -> list[str]:
    replacements = {
        "Phase B": "formal verification",
        "Phase C": "technical delivery",
        "Phase D": "handover/closure",
    }
    result: list[str] = []
    for error in errors:
        for old, new in replacements.items():
            error = error.replace(old, new)
        result.append(error)
    return result


def validate_specialized_evidence_chronology(
    root: Path,
    *,
    goal_id: str,
    node: dict[str, Any],
    work_type: str,
    binding_snapshot: dict[str, dict[str, Any]],
    paths_by_id: dict[str, str],
    nodes: dict[str, dict[str, Any]],
    latest_start_event: dict[str, Any] | None,
    completion_event: dict[str, Any] | None,
) -> list[str]:
    errors: list[str] = []
    required_roles = TYPE_EXECUTION_EVIDENCE_ROLES.get(work_type, set())
    if not required_roles:
        return errors
    started_at = parse_iso_datetime(
        latest_start_event.get("occurred_at")
        if isinstance(latest_start_event, dict)
        else None
    )
    completed_at = parse_iso_datetime(
        completion_event.get("occurred_at")
        if isinstance(completion_event, dict)
        else None
    )
    if started_at is None or completed_at is None:
        return [f"{goal_id}: specialized evidence lacks a started/completed event window"]
    common_roles = sorted(
        role
        for role in required_roles
        if role in SPECIALIZED_COMPLETION_STATUS_BY_ROLE
        and role not in {
            "INTEGRATION_CANDIDATE_MANIFEST",
            "OPERATION_EVENT_RECORD",
            "BLOCKER_RESOLUTION_RECEIPT",
        }
    )
    if common_roles:
        roster_errors, authority_actors = legacy.load_authority_roster(
            root,
            binding_snapshot,
        )
        errors.extend(f"{goal_id}: {error}" for error in roster_errors)
        allowed_source_goal_ids = {
            goal_id,
            str(node.get("parent_goal_id")),
            *string_list(node.get("start_requires")),
        }
        for role in common_roles:
            receipt_errors, _ = legacy.validate_receipt_common(
                root,
                binding_snapshot,
                role=role,
                expected_status=SPECIALIZED_COMPLETION_STATUS_BY_ROLE[role],
                allowed_source_goal_ids=allowed_source_goal_ids,
                goal_path_by_id=paths_by_id,
                authority_actors=authority_actors,
            )
            errors.extend(
                f"{goal_id}: {error}" for error in receipt_errors
            )
    started_candidate_hashes: set[str] = set()
    start_bindings = (
        latest_start_event.get("start_evidence_bindings")
        if isinstance(latest_start_event, dict)
        else None
    )
    if isinstance(start_bindings, dict):
        for role, start_binding in start_bindings.items():
            if not isinstance(start_binding, dict):
                continue
            start_path = resolve_safe_repo_file(root, start_binding.get("path"))
            if start_path is None:
                continue
            if role == "INTEGRATION_CANDIDATE_MANIFEST":
                candidate_hash = sha256_file(start_path)
            else:
                try:
                    start_payload = load_json(start_path)
                except (OSError, ValueError, json.JSONDecodeError):
                    continue
                candidate_hash = start_payload.get("candidate_sha256")
            if (
                isinstance(candidate_hash, str)
                and SHA256_PATTERN.fullmatch(candidate_hash)
            ):
                started_candidate_hashes.add(candidate_hash)
    if len(started_candidate_hashes) > 1:
        errors.append(f"{goal_id}: GOAL_STARTED evidence spans multiple candidates")
    started_candidate_hash = (
        next(iter(started_candidate_hashes))
        if len(started_candidate_hashes) == 1
        else None
    )
    if started_candidate_hash is not None and (
        not isinstance(completion_event, dict)
        or completion_event.get("started_candidate_sha256")
        != started_candidate_hash
    ):
        errors.append(
            f"{goal_id}: completion event is not bound to its started candidate"
        )
    for role in required_roles:
        binding = binding_snapshot.get(role)
        if not isinstance(binding, dict):
            errors.append(
                f"{goal_id}: specialized execution evidence binding is missing: {role}"
            )
            continue
        path = resolve_safe_repo_file(root, binding.get("path"))
        if path is None or binding.get("file_sha256") != sha256_file(path):
            errors.append(
                f"{goal_id}: specialized execution evidence binding differs: {role}"
            )
            continue
        try:
            payload = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(
                f"{goal_id}: specialized execution evidence cannot be loaded "
                f"({role}): {exc}"
            )
            continue
        execution_window = payload.get("execution_window")
        evidence_started_at = parse_iso_datetime(
            execution_window.get("started_at")
            if isinstance(execution_window, dict)
            else payload.get("generated_at")
        )
        generated_at = parse_iso_datetime(payload.get("generated_at"))
        evidence_candidate_hash = payload.get("candidate_sha256")
        if (
            evidence_started_at is None
            or evidence_started_at < started_at
            or generated_at is None
            or generated_at > completed_at
        ):
            errors.append(
                f"{goal_id}: specialized evidence is outside the Goal execution "
                f"window: {role}"
            )
        if (
            started_candidate_hash is not None
            and evidence_candidate_hash != started_candidate_hash
        ):
            errors.append(
                f"{goal_id}: completion evidence candidate differs from "
                f"GOAL_STARTED: {role}"
            )
        if work_type == "OPERATION_EVENT":
            evidence_ended_at = parse_iso_datetime(
                execution_window.get("ended_at")
                if isinstance(execution_window, dict)
                else None
            )
            reviewer = payload.get("reviewer")
            reviewed_at = parse_iso_datetime(
                reviewer.get("decided_at")
                if isinstance(reviewer, dict)
                else None
            )
            raw_times = [
                parse_iso_datetime(item.get("collected_at"))
                for item in payload.get("raw_evidence", [])
                if isinstance(item, dict)
            ]
            if (
                evidence_ended_at is None
                or reviewed_at is None
                or not raw_times
                or any(value is None for value in raw_times)
                or generated_at is None
                or not (
                    started_at
                    <= evidence_started_at
                    <= min(raw_times)
                    <= max(raw_times)
                    <= evidence_ended_at
                    <= reviewed_at
                    <= generated_at
                    <= completed_at
                )
            ):
                errors.append(
                    f"{goal_id}: operation evidence chronology differs: {role}"
                )
    return errors


def validate_release_gate_work_item_receipt(
    root: Path,
    *,
    goal_id: str,
    node: dict[str, Any],
    binding_snapshot: dict[str, dict[str, Any]],
) -> list[str]:
    gate_ids = string_list(node.get("source_policy_ids"))
    if len(gate_ids) != 1 or gate_ids[0] not in EXPECTED_GATE_IDS:
        return [f"{goal_id}: release Gate target ID differs"]
    binding = binding_snapshot.get("RELEASE_GATE_CLOSURE_RECEIPT")
    if not isinstance(binding, dict):
        return [f"{goal_id}: release Gate receipt binding is missing"]
    path = resolve_safe_repo_file(root, binding.get("path"))
    if path is None or binding.get("file_sha256") != sha256_file(path):
        return [f"{goal_id}: release Gate receipt binding differs"]
    try:
        receipt = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"{goal_id}: release Gate receipt cannot be loaded: {exc}"]
    matching = [
        row
        for row in receipt.get("gates", [])
        if isinstance(row, dict) and row.get("gate_id") == gate_ids[0]
    ]
    if len(matching) != 1:
        return [f"{goal_id}: release Gate receipt target row differs"]
    row = matching[0]
    raw_paths = {
        item.get("path")
        for item in receipt.get("raw_evidence", [])
        if isinstance(item, dict) and isinstance(item.get("path"), str)
    }
    references = string_list(row.get("evidence_refs"))
    if (
        row.get("status") != "CLOSED"
        or row.get("waived") is not False
        or not references
        or not set(references).issubset(raw_paths)
        or not legacy.is_within_execution_window(
            row.get("completed_at"),
            receipt.get("execution_window"),
        )
    ):
        return [f"{goal_id}: release Gate target is not closed with bound evidence"]
    return []


def validate_blocker_receipt_work_item(
    root: Path,
    *,
    goal_id: str,
    node: dict[str, Any],
    references: list[str],
    binding_snapshot: dict[str, dict[str, Any]],
    recorded_blocker_by_id: dict[str, dict[str, Any]],
    recorded_blocker_event_by_id: dict[str, str],
    recorded_blocker_event_time_by_id: dict[str, datetime | None],
    completion_event: dict[str, Any] | None,
) -> list[str]:
    blocker_ids = string_list(node.get("source_blocker_ids"))
    if len(blocker_ids) != 1:
        return [f"{goal_id}: external receipt Goal source blocker differs"]
    blocker_id = blocker_ids[0]
    blocker = recorded_blocker_by_id.get(blocker_id)
    if not isinstance(blocker, dict):
        return [f"{goal_id}: source blocker snapshot is unavailable"]
    errors = legacy.validate_blocker_resolution_receipt(
        label=goal_id,
        reference="BLOCKER_RESOLUTION_RECEIPT",
        root=root,
        bindings=binding_snapshot,
        blocker_id=blocker_id,
        goal_id=blocker.get("blocks_goal_id"),
        condition_code=blocker.get("condition_code"),
        owner=blocker.get("owner"),
        blocker_event_sha256=recorded_blocker_event_by_id.get(blocker_id),
        blocker_snapshot_hash=blocker.get("blocker_snapshot_sha256"),
    )
    if references.count("BLOCKER_RESOLUTION_RECEIPT") != 1:
        errors.append(
            f"{goal_id}: blocker resolution receipt reference differs"
        )
    binding = binding_snapshot.get("BLOCKER_RESOLUTION_RECEIPT")
    path = (
        resolve_safe_repo_file(root, binding.get("path"))
        if isinstance(binding, dict)
        else None
    )
    try:
        receipt = load_json(path) if path is not None else {}
    except (OSError, ValueError, json.JSONDecodeError):
        receipt = {}
    recorded_at = recorded_blocker_event_time_by_id.get(blocker_id)
    decided_at = parse_iso_datetime(receipt.get("decided_at"))
    generated_at = parse_iso_datetime(receipt.get("generated_at"))
    completed_at = parse_iso_datetime(
        completion_event.get("occurred_at")
        if isinstance(completion_event, dict)
        else None
    )
    if (
        recorded_at is None
        or decided_at is None
        or generated_at is None
        or completed_at is None
        or not recorded_at <= decided_at <= generated_at <= completed_at
    ):
        errors.append(
            f"{goal_id}: blocker receipt completion chronology differs"
        )
    return errors


def goal_transitively_depends_on(
    nodes: dict[str, dict[str, Any]],
    *,
    candidate_goal_id: str,
    predecessor_goal_id: str,
) -> bool:
    pending = [candidate_goal_id]
    visited: set[str] = set()
    while pending:
        current = pending.pop()
        if current in visited:
            continue
        visited.add(current)
        node = nodes.get(current, {})
        dependencies = string_list(node.get("start_requires")) + string_list(
            node.get("completion_requires")
        )
        if predecessor_goal_id in dependencies:
            return True
        pending.extend(
            dependency
            for dependency in dependencies
            if dependency in nodes and dependency not in visited
        )
    return False


def completed_successor_artifact_chain_reaches_live(
    root: Path,
    *,
    goal_id: str,
    relative_path: str,
    historical_sha256: str,
    nodes: dict[str, dict[str, Any]],
    completion_bindings_by_goal: dict[
        str,
        dict[str, dict[str, Any]],
    ],
    completion_event_by_goal: dict[str, dict[str, Any]],
) -> bool:
    source_event = completion_event_by_goal.get(goal_id, {})
    source_sequence = source_event.get("sequence")
    path = resolve_safe_repo_file(root, relative_path)
    if (
        not isinstance(source_sequence, int)
        or path is None
        or not SHA256_PATTERN.fullmatch(historical_sha256)
    ):
        return False
    current_sha256 = sha256_file(path)
    expected_before = historical_sha256
    candidates = sorted(
        (
            (event.get("sequence"), candidate_goal_id)
            for candidate_goal_id, event in completion_event_by_goal.items()
            if candidate_goal_id != goal_id
            and isinstance(event, dict)
            and isinstance(event.get("sequence"), int)
            and event["sequence"] > source_sequence
            and nodes.get(candidate_goal_id, {}).get("work_item_type")
            in CANONICAL_PRODUCER_WORK_TYPES
            and goal_transitively_depends_on(
                nodes,
                candidate_goal_id=candidate_goal_id,
                predecessor_goal_id=goal_id,
            )
        ),
        key=lambda item: (item[0], item[1]),
    )
    for _, candidate_goal_id in candidates:
        receipt_role = f"WORK_ITEM_COMPLETION::{candidate_goal_id}"
        binding = completion_bindings_by_goal.get(candidate_goal_id, {}).get(
            receipt_role
        )
        receipt_path = (
            resolve_safe_repo_file(root, binding.get("path"))
            if isinstance(binding, dict)
            else None
        )
        if (
            receipt_path is None
            or binding.get("file_sha256") != sha256_file(receipt_path)
        ):
            return False
        try:
            receipt = load_json(receipt_path)
        except (OSError, ValueError, json.JSONDecodeError):
            return False
        implementation_items = [
            item
            for item in receipt.get("result_evidence", [])
            if isinstance(item, dict)
            and item.get("kind") == "IMPLEMENTATION_RECORD"
        ]
        if len(implementation_items) != 1:
            return False
        implementation_item = implementation_items[0]
        implementation_path = resolve_safe_repo_file(
            root,
            implementation_item.get("path"),
        )
        if (
            implementation_path is None
            or implementation_item.get("sha256")
            != sha256_file(implementation_path)
        ):
            return False
        try:
            implementation = load_json(implementation_path)
        except (OSError, ValueError, json.JSONDecodeError):
            return False
        if (
            implementation.get("goal_id") != candidate_goal_id
            or implementation.get("kind") != "IMPLEMENTATION_RECORD"
        ):
            return False
        matching_changes = [
            item
            for item in implementation.get("changed_artifacts", [])
            if isinstance(item, dict)
            and item.get("path") == relative_path
        ]
        if not matching_changes:
            continue
        if len(matching_changes) != 1:
            return False
        change = matching_changes[0]
        before_sha256 = change.get("before_sha256")
        after_sha256 = change.get("after_sha256")
        if (
            before_sha256 != expected_before
            or not isinstance(after_sha256, str)
            or not SHA256_PATTERN.fullmatch(after_sha256)
            or after_sha256 == before_sha256
        ):
            return False
        expected_before = after_sha256
    return expected_before == current_sha256


def validate_internal_producer_result_evidence(
    root: Path,
    *,
    goal_id: str,
    node: dict[str, Any],
    binding_snapshot: dict[str, dict[str, Any]],
    nodes: dict[str, dict[str, Any]] | None = None,
    completion_bindings_by_goal: (
        dict[str, dict[str, dict[str, Any]]] | None
    ) = None,
    completion_event_by_goal: dict[str, dict[str, Any]] | None = None,
) -> list[str]:
    role = f"WORK_ITEM_COMPLETION::{goal_id}"
    binding = binding_snapshot.get(role)
    if not isinstance(binding, dict):
        return [f"{goal_id}: internal result receipt binding is missing"]
    receipt_path = resolve_safe_repo_file(root, binding.get("path"))
    if receipt_path is None or binding.get("file_sha256") != sha256_file(
        receipt_path
    ):
        return [f"{goal_id}: internal result receipt binding differs"]
    try:
        receipt = load_json(receipt_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"{goal_id}: internal result receipt cannot be loaded: {exc}"]
    result_by_kind: dict[str, dict[str, Any]] = {}
    result_hash_by_kind: dict[str, str] = {}
    errors: list[str] = []
    for item in receipt.get("result_evidence", []):
        if not isinstance(item, dict):
            continue
        kind = item.get("kind")
        path = resolve_safe_repo_file(root, item.get("path"))
        if (
            kind not in legacy.WORK_ITEM_RESULT_EVIDENCE_KINDS
            or path is None
            or item.get("sha256") != sha256_file(path)
        ):
            continue
        try:
            payload = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError):
            continue
        result_by_kind[str(kind)] = payload
        result_hash_by_kind[str(kind)] = str(item.get("sha256"))
    if set(result_by_kind) != legacy.WORK_ITEM_RESULT_EVIDENCE_KINDS:
        return [
            f"{goal_id}: internal result evidence set is incomplete"
        ]
    executor = receipt.get("executor")
    reviewer = receipt.get("reviewer")
    executor_id = (
        executor.get("id") if isinstance(executor, dict) else None
    )
    reviewer_id = (
        reviewer.get("id") if isinstance(reviewer, dict) else None
    )
    if (
        not isinstance(executor_id, str)
        or not executor_id
        or not isinstance(reviewer_id, str)
        or not reviewer_id
        or executor_id == reviewer_id
    ):
        errors.append(
            f"{goal_id}: executor and reviewer must be distinct identified actors"
        )
    provenance = receipt.get("reviewer_provenance")
    review_path = resolve_safe_repo_file(
        root,
        provenance.get("path") if isinstance(provenance, dict) else None,
    )
    review_hash = (
        provenance.get("sha256")
        if isinstance(provenance, dict)
        else None
    )
    try:
        review = load_json(review_path) if review_path is not None else {}
    except (OSError, ValueError, json.JSONDecodeError):
        review = {}
    reviewed_at = parse_iso_datetime(review.get("reviewed_at"))
    completed_at = parse_iso_datetime(receipt.get("completed_at"))
    generated_at = parse_iso_datetime(receipt.get("generated_at"))
    if (
        review_path is None
        or not isinstance(review_hash, str)
        or not SHA256_PATTERN.fullmatch(review_hash)
        or sha256_file(review_path) != review_hash
        or review.get("evidence_type") != "INDEPENDENT_INTERNAL_REVIEW"
        or review.get("goal_id") != goal_id
        or review.get("status") != "PASS"
        or review.get("reviewer_id") != reviewer_id
        or review.get("reviewed_result_sha256_by_kind")
        != result_hash_by_kind
        or reviewed_at is None
        or completed_at is None
        or generated_at is None
        or not completed_at <= reviewed_at <= generated_at
    ):
        errors.append(
            f"{goal_id}: independent reviewer provenance differs"
        )

    implementation = result_by_kind["IMPLEMENTATION_RECORD"]
    changed_artifacts = implementation.get("changed_artifacts")
    if not isinstance(changed_artifacts, list) or not changed_artifacts:
        errors.append(
            f"{goal_id}: implementation record changed_artifacts is missing"
        )
        changed_artifacts = []
    seen_paths: set[str] = set()
    for index, record in enumerate(changed_artifacts):
        changed_path = resolve_safe_repo_file(
            root,
            record.get("path") if isinstance(record, dict) else None,
        )
        after_hash = (
            record.get("after_sha256")
            if isinstance(record, dict)
            else None
        )
        before_hash = (
            record.get("before_sha256")
            if isinstance(record, dict)
            else None
        )
        relative = record.get("path") if isinstance(record, dict) else None
        live_content_matches = bool(
            changed_path is not None
            and isinstance(after_hash, str)
            and SHA256_PATTERN.fullmatch(after_hash)
            and sha256_file(changed_path) == after_hash
        )
        successor_chain_matches = bool(
            not live_content_matches
            and isinstance(relative, str)
            and isinstance(after_hash, str)
            and completed_successor_artifact_chain_reaches_live(
                root,
                goal_id=goal_id,
                relative_path=relative,
                historical_sha256=after_hash,
                nodes=nodes or {},
                completion_bindings_by_goal=(
                    completion_bindings_by_goal or {}
                ),
                completion_event_by_goal=completion_event_by_goal or {},
            )
        )
        if (
            not isinstance(record, dict)
            or changed_path is None
            or relative in seen_paths
            or not isinstance(after_hash, str)
            or not SHA256_PATTERN.fullmatch(after_hash)
            or not (live_content_matches or successor_chain_matches)
            or (
                before_hash is not None
                and (
                    not isinstance(before_hash, str)
                    or not SHA256_PATTERN.fullmatch(before_hash)
                    or before_hash == after_hash
                )
            )
        ):
            errors.append(
                f"{goal_id}: implementation changed artifact {index} differs"
            )
        elif isinstance(relative, str):
            seen_paths.add(relative)

    verification = result_by_kind["VERIFICATION_RESULT"]
    checks = verification.get("checks")
    if not isinstance(checks, list) or not checks:
        errors.append(f"{goal_id}: verification checks are missing")
        checks = []
    for index, check in enumerate(checks):
        output_path = resolve_safe_repo_file(
            root,
            check.get("output_path") if isinstance(check, dict) else None,
        )
        output_hash = (
            check.get("output_sha256")
            if isinstance(check, dict)
            else None
        )
        if (
            not isinstance(check, dict)
            or not isinstance(check.get("command"), str)
            or not check.get("command")
            or check.get("exit_code") != 0
            or output_path is None
            or not isinstance(output_hash, str)
            or not SHA256_PATTERN.fullmatch(output_hash)
            or sha256_file(output_path) != output_hash
            or not legacy.is_within_execution_window(
                check.get("executed_at"),
                receipt.get("execution_window"),
            )
        ):
            errors.append(
                f"{goal_id}: verification check {index} differs"
            )

    successor = result_by_kind["SUCCESSOR_TRACE"]
    work_type = str(node.get("work_item_type"))
    expected_roles = (
        {"IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"}
        if work_type == "POLICY_GAP_WORK"
        else set(
            (node.get("output_subject_ids_by_role") or {}).keys()
        )
    )
    resulting_bindings = successor.get("resulting_canonical_bindings")
    expected_bindings = {
        output_role: binding_snapshot.get(output_role)
        for output_role in sorted(expected_roles)
    }
    if (
        successor.get("canonical_update_event_type")
        != "CANONICAL_BINDINGS_UPDATED"
        or resulting_bindings != expected_bindings
        or any(
            not isinstance(value, dict)
            for value in expected_bindings.values()
        )
    ):
        errors.append(
            f"{goal_id}: successor trace canonical bindings differ"
        )
    declared_scope = successor.get("changed_subject_ids_by_role")
    if work_type == "ARTIFACT_WORK":
        expected_scope = node.get("output_subject_ids_by_role")
        if declared_scope != expected_scope:
            errors.append(
                f"{goal_id}: successor trace artifact subject scope differs"
            )
    elif (
        not isinstance(declared_scope, dict)
        or set(declared_scope)
        != {"IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"}
        or set(
            string_list(declared_scope.get("IMPLEMENTATION_GAP"))
        )
        != set(
            string_list(node.get("source_policy_ids"))
            + string_list(node.get("gap_ids"))
        )
        or not set(string_list(node.get("source_policy_ids"))).issubset(
            string_list(declared_scope.get("IMPLEMENTATION_BACKLOG"))
        )
    ):
        errors.append(
            f"{goal_id}: successor trace policy/Gap subject scope differs"
        )
    return errors


def validate_typed_work_item_completion_semantics(
    root: Path,
    *,
    goal_id: str,
    node: dict[str, Any],
    goal_path: str,
    references: list[str],
    binding_snapshot: dict[str, dict[str, Any]],
    paths_by_id: dict[str, str],
    nodes: dict[str, dict[str, Any]] | None = None,
    completion_bindings_by_goal: (
        dict[str, dict[str, dict[str, Any]]] | None
    ) = None,
    historical_completion_bindings_by_goal: (
        dict[str, dict[str, dict[str, Any]]] | None
    ) = None,
    historical_completion_event_by_goal: (
        dict[str, dict[str, Any]] | None
    ) = None,
    recorded_blocker_by_id: dict[str, dict[str, Any]] | None = None,
    recorded_blocker_event_by_id: dict[str, str] | None = None,
    recorded_blocker_event_time_by_id: (
        dict[str, datetime | None] | None
    ) = None,
    latest_start_event: dict[str, Any] | None = None,
    completion_event: dict[str, Any] | None = None,
) -> list[str]:
    errors = legacy.validate_work_item_completion_receipt(
        label=goal_id,
        goal_id=goal_id,
        references=references,
        root=root,
        bindings=binding_snapshot,
        node=node,
        goal_path=goal_path,
    )
    work_type = str(node.get("work_item_type"))
    required_roles = TYPE_REQUIRED_EVIDENCE_ROLES.get(work_type, set())
    expected_roles = {
        f"WORK_ITEM_COMPLETION::{goal_id}",
        *required_roles,
    }
    reference_counts = Counter(references)
    if (
        set(reference_counts) != expected_roles
        or any(count != 1 for count in reference_counts.values())
    ):
        errors.append(
            f"{goal_id}: typed completion evidence roles differ for "
            f"{work_type}: expected {sorted(expected_roles)}"
        )
    errors.extend(
        validate_specialized_evidence_chronology(
            root,
            goal_id=goal_id,
            node=node,
            work_type=work_type,
            binding_snapshot=binding_snapshot,
            paths_by_id=paths_by_id,
            nodes=nodes or {},
            latest_start_event=latest_start_event,
            completion_event=completion_event,
        )
    )
    if work_type == "INTEGRATION_CANDIDATE":
        binding = binding_snapshot.get("INTEGRATION_CANDIDATE_MANIFEST")
        if not isinstance(binding, dict):
            errors.append(
                f"{goal_id}: integration candidate manifest binding is missing"
            )
        else:
            manifest_errors, _, _ = validate_integration_candidate_manifest(
                root,
                label=goal_id,
                binding=binding,
            )
            errors.extend(manifest_errors)
    if work_type == "OPERATION_EVENT":
        errors.extend(
            validate_operation_event_record(
                root,
                goal_id=goal_id,
                node=node,
                binding_snapshot=binding_snapshot,
                paths_by_id=paths_by_id,
                nodes=nodes or {},
                completion_bindings_by_goal=(
                    completion_bindings_by_goal or {}
                ),
            )
        )
    if work_type == "RELEASE_GATE":
        errors.extend(
            validate_release_gate_work_item_receipt(
                root,
                goal_id=goal_id,
                node=node,
                binding_snapshot=binding_snapshot,
            )
        )
    if work_type == "BLOCKER_OR_EXTERNAL_RECEIPT":
        errors.extend(
            validate_blocker_receipt_work_item(
                root,
                goal_id=goal_id,
                node=node,
                references=references,
                binding_snapshot=binding_snapshot,
                recorded_blocker_by_id=recorded_blocker_by_id or {},
                recorded_blocker_event_by_id=(
                    recorded_blocker_event_by_id or {}
                ),
                recorded_blocker_event_time_by_id=(
                    recorded_blocker_event_time_by_id or {}
                ),
                completion_event=completion_event,
            )
        )
    if work_type in CANONICAL_PRODUCER_WORK_TYPES:
        errors.extend(
            validate_internal_producer_result_evidence(
                root,
                goal_id=goal_id,
                node=node,
                binding_snapshot=binding_snapshot,
                nodes=nodes or {},
                completion_bindings_by_goal=(
                    historical_completion_bindings_by_goal or {}
                ),
                completion_event_by_goal=(
                    historical_completion_event_by_goal or {}
                ),
            )
        )
    if work_type == "RELEASE_DECISION":
        allowed_source_goal_ids = {
            goal_id,
            str(node.get("parent_goal_id")),
            *string_list(node.get("start_requires")),
        }
        legacy_errors = legacy.validate_phase_b_evidence_receipts(
            root,
            binding_snapshot,
            references,
            paths_by_id,
            allowed_source_goal_ids=allowed_source_goal_ids,
        )
        errors.extend(replace_legacy_phase_labels(legacy_errors))
    if work_type == "DEPLOYMENT_DELIVERY_EVENT":
        with semantic_legacy_goal_ids(delivery_goal_id=goal_id):
            legacy_errors = legacy.validate_phase_c_evidence_receipts(
                root,
                binding_snapshot,
                references,
                paths_by_id,
            )
        errors.extend(replace_legacy_phase_labels(legacy_errors))
    if work_type == "HANDOVER_CLOSURE_EVENT":
        with semantic_legacy_goal_ids(handover_goal_id=goal_id):
            legacy_errors = legacy.validate_phase_d_evidence_receipts(
                root,
                binding_snapshot,
                references,
                paths_by_id,
            )
        errors.extend(replace_legacy_phase_labels(legacy_errors))
    return errors


def validate_workstream_completion_semantics(
    root: Path,
    *,
    goal_id: str,
    references: list[str],
    binding_snapshot: dict[str, dict[str, Any]],
    paths_by_id: dict[str, str],
    nodes: dict[str, dict[str, Any]],
    statuses_before_completion: dict[str, str],
    completion_event_by_goal: dict[str, dict[str, Any]],
    revalidation_required: bool = False,
) -> list[str]:
    if goal_id != "WS-GOAL-EPIC-12":
        return []
    active_child_ids = {
        candidate_id
        for candidate_id, candidate in nodes.items()
        if candidate.get("goal_kind") == "WORK_ITEM"
        and candidate.get("parent_goal_id") == goal_id
        and statuses_before_completion.get(candidate_id) != "SUPERSEDED"
    }
    formal_run_ids = {
        candidate_id
        for candidate_id in active_child_ids
        if nodes[candidate_id].get("work_item_type") == "FORMAL_TEST_RUN"
    }
    release_gate_ids = {
        candidate_id
        for candidate_id in active_child_ids
        if nodes[candidate_id].get("work_item_type") == "RELEASE_GATE"
    }
    release_decision_ids = {
        candidate_id
        for candidate_id in active_child_ids
        if nodes[candidate_id].get("work_item_type") == "RELEASE_DECISION"
    }
    active_gate_subjects = [
        string_list(nodes[candidate_id].get("source_policy_ids"))
        for candidate_id in sorted(release_gate_ids)
    ]
    required_completed_ids = (
        formal_run_ids | release_gate_ids | release_decision_ids
    )
    errors: list[str] = []
    expected_roles = set(TYPE_REQUIRED_EVIDENCE_ROLES["RELEASE_DECISION"])
    if revalidation_required:
        expected_roles.add(f"WORKSTREAM_REVALIDATION::{goal_id}")
    reference_counts = Counter(references)
    if (
        set(reference_counts) != expected_roles
        or any(count != 1 for count in reference_counts.values())
    ):
        errors.append(
            f"{goal_id}: formal Workstream completion evidence roles differ: "
            f"expected {sorted(expected_roles)}"
        )
    if (
        len(formal_run_ids) != 1
        or len(release_decision_ids) != 1
        or any(len(subjects) != 1 for subjects in active_gate_subjects)
        or {
            subjects[0]
            for subjects in active_gate_subjects
            if len(subjects) == 1
        }
        != EXPECTED_GATE_IDS
        or len(release_gate_ids) != len(EXPECTED_GATE_IDS)
    ):
        errors.append(
            f"{goal_id}: formal verification branch lacks one active "
            "Formal Run, exact five Gate Goals, or a Release Decision"
        )
    incomplete = {
        candidate_id
        for candidate_id in required_completed_ids
        if (
            statuses_before_completion.get(candidate_id)
            != "COMPLETE_AT_TARGET"
            or candidate_id not in completion_event_by_goal
        )
    }
    if incomplete:
        errors.append(
            f"{goal_id}: formal verification child completion basis is "
            f"incomplete: {sorted(incomplete)}"
        )
    producer_ids = {
        dependency_id
        for release_id in release_decision_ids
        for dependency_id in string_list(
            nodes[release_id].get("start_requires")
        )
    }
    legacy_errors = legacy.validate_phase_b_evidence_receipts(
        root,
        binding_snapshot,
        references,
        paths_by_id,
        allowed_source_goal_ids={
            *release_decision_ids,
            *producer_ids,
        },
    )
    errors.extend(replace_legacy_phase_labels(legacy_errors))
    return errors


def validate_reopened_workstream_completion(
    root: Path,
    *,
    label: str,
    goal_id: str,
    node: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    statuses_before_completion: dict[str, str],
    references: list[str],
    binding_snapshot: dict[str, dict[str, Any]],
    completion_event_by_goal: dict[str, dict[str, Any]],
    reopen_event: dict[str, Any],
    latest_impact_event: dict[str, Any],
    completion_event: dict[str, Any],
) -> list[str]:
    role = f"WORKSTREAM_REVALIDATION::{goal_id}"
    errors: list[str] = []
    if references.count(role) != 1:
        errors.append(f"{label}: reopened Workstream revalidation role differs")
    binding = binding_snapshot.get(role)
    if not isinstance(binding, dict):
        return errors + [
            f"{label}: reopened Workstream revalidation binding is missing"
        ]
    path = resolve_safe_repo_file(root, binding.get("path"))
    if path is None or binding.get("file_sha256") != sha256_file(path):
        return errors + [
            f"{label}: reopened Workstream revalidation binding differs"
        ]
    dependency_ids = sorted(
        set(string_list(node.get("start_requires")))
        | set(string_list(node.get("completion_requires")))
    )
    active_child_ids = sorted(
        child_id
        for child_id, child in nodes.items()
        if child.get("parent_goal_id") == goal_id
        and statuses_before_completion.get(child_id) != "SUPERSEDED"
    )
    incomplete_basis = [
        item
        for item in dependency_ids + active_child_ids
        if (
            statuses_before_completion.get(item) != "COMPLETE_AT_TARGET"
            or item not in completion_event_by_goal
        )
    ]
    if incomplete_basis:
        errors.append(
            f"{label}: reopened Workstream aggregate basis is incomplete: "
            f"{sorted(set(incomplete_basis))}"
        )
    expected_basis = {
        "reopen_event_sha256": reopen_event.get("event_sha256"),
        "latest_impact_event_sha256": latest_impact_event.get(
            "event_sha256"
        ),
        "dependency_completion_event_sha256_by_goal": {
            dependency: completion_event_by_goal.get(dependency, {}).get(
                "event_sha256"
            )
            for dependency in dependency_ids
        },
        "child_completion_event_sha256_by_goal": {
            child_id: completion_event_by_goal.get(child_id, {}).get(
                "event_sha256"
            )
            for child_id in active_child_ids
        },
    }
    if completion_event.get("aggregate_revalidation") != expected_basis:
        errors.append(
            f"{label}: reopened Workstream aggregate completion basis differs"
        )
    try:
        receipt = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"{label}: reopened Workstream revalidation cannot be loaded: {exc}"
        ]
    generated_at = parse_iso_datetime(receipt.get("generated_at"))
    reopen_at = parse_iso_datetime(reopen_event.get("occurred_at"))
    latest_impact_at = parse_iso_datetime(
        latest_impact_event.get("occurred_at")
    )
    completed_at = parse_iso_datetime(completion_event.get("occurred_at"))
    basis_event_times = [
        parse_iso_datetime(
            completion_event_by_goal.get(item, {}).get("occurred_at")
        )
        for item in sorted(set(dependency_ids + active_child_ids))
    ]
    valid_basis_times = [
        timestamp
        for timestamp in [reopen_at, latest_impact_at, *basis_event_times]
        if timestamp is not None
    ]
    basis_is_complete = (
        reopen_at is not None
        and latest_impact_at is not None
        and len(valid_basis_times) == 2 + len(basis_event_times)
    )
    latest_basis_at = max(valid_basis_times) if basis_is_complete else None
    if (
        receipt.get("schema_version") != "1.0"
        or receipt.get("evidence_type") != "WORKSTREAM_REVALIDATION"
        or receipt.get("document_id") != binding.get("document_id")
        or receipt.get("status") != "ACCEPTED"
        or receipt.get("result") != "PASS"
        or receipt.get("target_goal_id") != goal_id
        or receipt.get("aggregate_revalidation") != expected_basis
        or generated_at is None
        or completed_at is None
        or latest_basis_at is None
        or not (latest_basis_at < generated_at <= completed_at)
    ):
        errors.append(
            f"{label}: reopened Workstream revalidation semantics differ"
        )
    return errors


def validate_integration_candidate_manifest(
    root: Path,
    *,
    label: str,
    binding: dict[str, Any],
) -> tuple[list[str], dict[str, Any], str]:
    errors: list[str] = []
    path = resolve_safe_repo_file(root, binding.get("path"))
    if path is None:
        return [f"{label}: integration candidate path is missing or unsafe"], {}, ""
    actual_hash = sha256_file(path)
    if binding.get("file_sha256") != actual_hash:
        errors.append(f"{label}: integration candidate binding hash differs")
    try:
        payload = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"{label}: integration candidate manifest cannot be loaded: {exc}"
        ], {}, actual_hash
    for field, expected in (
        ("schema_version", "1.0"),
        ("evidence_type", "INTEGRATION_CANDIDATE_MANIFEST"),
        ("document_id", binding.get("document_id")),
        ("status", "BOUND"),
    ):
        if payload.get(field) != expected:
            errors.append(f"{label}: integration candidate {field} differs")
    if parse_iso_datetime(payload.get("generated_at")) is None:
        errors.append(f"{label}: integration candidate generated_at is invalid")
    commit_hash = payload.get("source_commit_sha256")
    if (
        not isinstance(commit_hash, str)
        or re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", commit_hash) is None
    ):
        errors.append(f"{label}: integration candidate source commit hash is invalid")
    for field in ("configuration_generation", "database_generation"):
        if not isinstance(payload.get(field), str) or not payload.get(field):
            errors.append(f"{label}: integration candidate {field} is missing")
    components = payload.get("components")
    seen_names: set[str] = set()
    seen_paths: set[str] = set()
    seen_kinds: set[str] = set()
    component_hashes: dict[str, str] = {}
    required_component_kinds = {
        "ANDROID_APP",
        "BACKEND",
        "ANDROID_GATEWAY",
        "ON_DEVICE_MODEL",
        "CONFIGURATION",
        "DATABASE_MIGRATION",
    }
    if not isinstance(components, list) or not components:
        errors.append(f"{label}: integration candidate components are missing")
    else:
        for index, component in enumerate(components):
            item_label = f"{label}: integration candidate component {index}"
            if not isinstance(component, dict):
                errors.append(f"{item_label} is malformed")
                continue
            name = component.get("name")
            relative = component.get("path")
            component_hash = component.get("sha256")
            version = component.get("version")
            kind = component.get("kind")
            component_path = resolve_safe_repo_file(root, relative)
            if (
                not isinstance(name, str)
                or not name
                or name in seen_names
                or not isinstance(relative, str)
                or not relative
                or relative in seen_paths
                or component_path is None
                or not isinstance(component_hash, str)
                or not SHA256_PATTERN.fullmatch(component_hash)
                or sha256_file(component_path) != component_hash
                or not isinstance(version, str)
                or not version
                or kind not in required_component_kinds
                or kind in seen_kinds
            ):
                errors.append(f"{item_label} identity or hash differs")
                continue
            seen_names.add(name)
            seen_paths.add(relative)
            seen_kinds.add(str(kind))
            component_hashes[name] = component_hash
    if seen_kinds != required_component_kinds:
        errors.append(
            f"{label}: integration candidate required component kinds differ"
        )
    component_set_sha256 = hashlib.sha256(
        json.dumps(
            component_hashes,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    if payload.get("component_set_sha256") != component_set_sha256:
        errors.append(f"{label}: integration candidate component set hash differs")
    for field in ("sbom", "provenance"):
        reference = payload.get(field)
        reference_path = resolve_safe_repo_file(
            root,
            reference.get("path") if isinstance(reference, dict) else None,
        )
        reference_hash = (
            reference.get("sha256") if isinstance(reference, dict) else None
        )
        if (
            reference_path is None
            or not isinstance(reference_hash, str)
            or not SHA256_PATTERN.fullmatch(reference_hash)
            or sha256_file(reference_path) != reference_hash
        ):
            errors.append(f"{label}: integration candidate {field} differs")
            continue
        try:
            reference_payload = load_json(reference_path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(
                f"{label}: integration candidate {field} cannot be loaded: {exc}"
            )
            continue
        expected_evidence_type = {
            "sbom": "SBOM",
            "provenance": "BUILD_PROVENANCE",
        }[field]
        if (
            reference_payload.get("evidence_type") != expected_evidence_type
            or reference_payload.get("candidate_document_id")
            != payload.get("document_id")
            or reference_payload.get("component_hashes")
            != component_hashes
            or parse_iso_datetime(reference_payload.get("generated_at"))
            is None
        ):
            errors.append(
                f"{label}: integration candidate {field} semantic binding differs"
            )
        if field == "sbom" and (
            not isinstance(reference_payload.get("packages"), list)
            or not reference_payload.get("packages")
        ):
            errors.append(f"{label}: integration candidate SBOM packages are missing")
        if field == "provenance" and (
            reference_payload.get("source_commit_sha256") != commit_hash
            or not isinstance(reference_payload.get("builder"), dict)
            or not reference_payload.get("builder")
        ):
            errors.append(
                f"{label}: integration candidate provenance source differs"
            )
    return errors, payload, actual_hash


def validate_operation_event_record(
    root: Path,
    *,
    goal_id: str,
    node: dict[str, Any],
    binding_snapshot: dict[str, dict[str, Any]],
    paths_by_id: dict[str, str],
    nodes: dict[str, dict[str, Any]],
    completion_bindings_by_goal: dict[
        str,
        dict[str, dict[str, Any]],
    ],
) -> list[str]:
    errors: list[str] = []
    role = "OPERATION_EVENT_RECORD"
    binding = binding_snapshot.get(role)
    if not isinstance(binding, dict):
        return [f"{goal_id}: operation event record binding is missing"]
    path = resolve_safe_repo_file(root, binding.get("path"))
    if path is None or binding.get("file_sha256") != sha256_file(path):
        return [f"{goal_id}: operation event record binding differs"]
    actual_hash = sha256_file(path)
    errors.extend(
        f"{goal_id}: {error}"
        for error in legacy.validate_external_attestation_anchor(
            role,
            actual_hash,
        )
    )
    try:
        payload = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"{goal_id}: operation event record cannot be loaded: {exc}"]
    if (
        payload.get("schema_version") != "1.0"
        or payload.get("evidence_type") != role
        or payload.get("document_id") != binding.get("document_id")
        or payload.get("status") != "RECORDED"
        or payload.get("source_goal_id") != goal_id
    ):
        errors.append(f"{goal_id}: operation event record identity/status differs")
    goal_path = resolve_safe_repo_file(root, paths_by_id.get(goal_id))
    if (
        goal_path is None
        or payload.get("source_goal_sha256") != sha256_file(goal_path)
    ):
        errors.append(f"{goal_id}: operation event record Goal binding differs")
    deployment_ids = {
        dependency
        for dependency in string_list(node.get("start_requires"))
        if nodes.get(dependency, {}).get("work_item_type")
        == "DEPLOYMENT_DELIVERY_EVENT"
    }
    if (
        len(deployment_ids) != 1
        or payload.get("source_deployment_goal_id") not in deployment_ids
    ):
        errors.append(f"{goal_id}: operation event deployment source differs")
    else:
        deployment_id = next(iter(deployment_ids))
        delivery_binding = completion_bindings_by_goal.get(
            deployment_id,
            {},
        ).get("PHASE_C_TECHNICAL_DELIVERY_RECEIPT")
        if payload.get(
            "source_deployment_completion_binding"
        ) != delivery_binding:
            errors.append(
                f"{goal_id}: operation event deployment completion "
                "binding differs"
            )
        delivery_path = (
            resolve_safe_repo_file(root, delivery_binding.get("path"))
            if isinstance(delivery_binding, dict)
            else None
        )
        try:
            delivery = (
                load_json(delivery_path)
                if delivery_path is not None
                else {}
            )
        except (OSError, ValueError, json.JSONDecodeError):
            delivery = {}
        deployed_candidate = delivery.get(
            "deployed_candidate_sha256",
            delivery.get("candidate_sha256"),
        )
        if payload.get("candidate_sha256") != deployed_candidate:
            errors.append(
                f"{goal_id}: operation candidate differs from its "
                "deployment candidate"
            )
    if payload.get("event_kind") not in {
        "STABILIZATION",
        "INCIDENT",
        "RESTORE",
        "COST_MEASUREMENT",
        "ACCESS_REVIEW",
        "SECRET_ROTATION",
        "DATA_LIFECYCLE",
        "PATCH",
    }:
        errors.append(f"{goal_id}: operation event kind is invalid")
    candidate_hash = payload.get("candidate_sha256")
    if (
        not isinstance(candidate_hash, str)
        or not SHA256_PATTERN.fullmatch(candidate_hash)
    ):
        errors.append(f"{goal_id}: operation event candidate hash is invalid")
    errors.extend(
        f"{goal_id}: {error}"
        for error in legacy.validate_receipt_authority_roster_binding(
            root,
            payload,
            binding_snapshot,
        )
    )
    roster_errors, actors = legacy.load_authority_roster(
        root,
        binding_snapshot,
    )
    errors.extend(f"{goal_id}: {error}" for error in roster_errors)
    actor_ids: list[str] = []
    for actor_name in ("executor", "reviewer"):
        actor = payload.get(actor_name)
        errors.extend(
            legacy.validate_receipt_actor(
                role,
                actor_name,
                actor,
                require_decision=actor_name == "reviewer",
            )
        )
        errors.extend(
            legacy.validate_actor_against_roster(
                receipt_role=role,
                actor_name=actor_name,
                actor=actor,
                actors_by_id=actors,
            )
        )
        if isinstance(actor, dict) and isinstance(actor.get("id"), str):
            actor_ids.append(actor["id"])
    if len(actor_ids) != 2 or len(set(actor_ids)) != 2:
        errors.append(f"{goal_id}: operation executor/reviewer are not independent")
    execution_window = payload.get("execution_window")
    started_at = parse_iso_datetime(
        execution_window.get("started_at")
        if isinstance(execution_window, dict)
        else None
    )
    ended_at = parse_iso_datetime(
        execution_window.get("ended_at")
        if isinstance(execution_window, dict)
        else None
    )
    raw_evidence = payload.get("raw_evidence")
    if (
        started_at is None
        or ended_at is None
        or started_at > ended_at
        or not isinstance(raw_evidence, list)
        or not raw_evidence
    ):
        errors.append(f"{goal_id}: operation execution window/raw evidence is invalid")
        raw_evidence = []
    seen_paths: set[str] = set()
    for index, record in enumerate(raw_evidence):
        record_path = resolve_safe_repo_file(
            root,
            record.get("path") if isinstance(record, dict) else None,
        )
        record_hash = record.get("sha256") if isinstance(record, dict) else None
        collected_at = parse_iso_datetime(
            record.get("collected_at") if isinstance(record, dict) else None
        )
        if (
            not isinstance(record, dict)
            or record_path is None
            or record.get("path") in seen_paths
            or not isinstance(record_hash, str)
            or not SHA256_PATTERN.fullmatch(record_hash)
            or sha256_file(record_path) != record_hash
            or not isinstance(record.get("record_count"), int)
            or isinstance(record.get("record_count"), bool)
            or record.get("record_count", 0) < 1
            or collected_at is None
            or started_at is None
            or ended_at is None
            or not started_at <= collected_at <= ended_at
        ):
            errors.append(
                f"{goal_id}: operation raw evidence {index} differs"
            )
        elif isinstance(record.get("path"), str):
            seen_paths.add(record["path"])
    return errors


def validate_typed_start_evidence(
    root: Path,
    *,
    label: str,
    event: dict[str, Any],
    goal_id: str,
    node: dict[str, Any],
    binding_snapshot: dict[str, dict[str, Any]],
    paths_by_id: dict[str, str],
    completion_event_by_goal: dict[str, dict[str, Any]],
    completion_bindings_by_goal: dict[str, dict[str, dict[str, Any]]],
    nodes: dict[str, dict[str, Any]],
    occurred_at: datetime | None,
) -> list[str]:
    errors: list[str] = []
    contract = DYNAMIC_START_CONTRACTS.get(str(node.get("work_item_type")))
    if not isinstance(contract, dict):
        return [f"{label}: Work Item start contract is missing"]
    required_roles = list(contract["required_start_evidence_roles"])
    event_references = string_list(event.get("evidence_refs"))
    if event_references != required_roles:
        errors.append(
            f"{label}: typed start evidence references differ: "
            f"expected {required_roles}"
        )
    expected_bindings = {
        role: binding_snapshot.get(role)
        for role in required_roles
    }
    if required_roles and event.get("start_evidence_bindings") != expected_bindings:
        errors.append(f"{label}: start evidence binding snapshot differs")
    elif (
        not required_roles
        and event.get("start_evidence_bindings") is not None
        and event.get("start_evidence_bindings") != {}
    ):
        errors.append(f"{label}: unexpected start evidence binding snapshot")

    candidate_hashes: set[str] = set()
    expected_statuses = {
        role: START_EVIDENCE_STATUS_BY_ROLE[role]
        for role in required_roles
    }
    receipt_roles = set(required_roles) - {"INTEGRATION_CANDIDATE_MANIFEST"}
    authority_actors: dict[str, dict[str, Any]] = {}
    if receipt_roles:
        roster_errors, authority_actors = legacy.load_authority_roster(
            root,
            binding_snapshot,
        )
        errors.extend(f"{label}: {error}" for error in roster_errors)
    allowed_source_goal_ids = {
        goal_id,
        str(node.get("parent_goal_id")),
        *string_list(node.get("start_requires")),
    }
    expected_provenance: dict[str, list[dict[str, Any]]] = {}
    for role in required_roles:
        producer_types = START_EVIDENCE_PRODUCER_TYPES_BY_ROLE.get(role)
        if not producer_types:
            continue
        provenance_rows: list[dict[str, Any]] = []
        for dependency_id in string_list(node.get("start_requires")):
            dependency = nodes.get(dependency_id, {})
            event_binding = completion_bindings_by_goal.get(
                dependency_id,
                {},
            ).get(role)
            if (
                dependency.get("work_item_type") in producer_types
                and completion_event_by_goal.get(dependency_id)
                and role
                in string_list(
                    completion_event_by_goal[dependency_id].get(
                        "evidence_refs"
                    )
                )
                and event_binding == binding_snapshot.get(role)
            ):
                provenance_rows.append(
                    {
                        "producer_goal_id": dependency_id,
                        "completion_event_sha256": completion_event_by_goal[
                            dependency_id
                        ].get("event_sha256"),
                        "binding": event_binding,
                    }
                )
        if not provenance_rows:
            errors.append(
                f"{label}: start evidence lacks a matching completed producer: {role}"
            )
        expected_provenance[role] = sorted(
            provenance_rows,
            key=lambda row: str(row["producer_goal_id"]),
        )
        if (
            node.get("work_item_type") == "RELEASE_DECISION"
            and role == "RELEASE_GATE_CLOSURE_RECEIPT"
        ):
            gate_dependency_ids = {
                dependency_id
                for dependency_id in string_list(
                    node.get("start_requires")
                )
                if nodes.get(dependency_id, {}).get("work_item_type")
                == "RELEASE_GATE"
            }
            bound_gate_ids = {
                row["producer_goal_id"] for row in provenance_rows
            }
            if (
                len(gate_dependency_ids) != len(EXPECTED_GATE_IDS)
                or bound_gate_ids != gate_dependency_ids
            ):
                errors.append(
                    f"{label}: release decision gate provenance does not "
                    "bind all five completed Gate Goals"
                )
    if required_roles and event.get(
        "start_evidence_provenance"
    ) != expected_provenance:
        errors.append(f"{label}: start evidence producer provenance differs")
    elif (
        not required_roles
        and event.get("start_evidence_provenance") is not None
        and event.get("start_evidence_provenance") != {}
    ):
        errors.append(f"{label}: unexpected start evidence producer provenance")

    for role in required_roles:
        binding = binding_snapshot.get(role)
        if not isinstance(binding, dict):
            errors.append(f"{label}: start evidence binding is missing: {role}")
            continue
        path = resolve_safe_repo_file(root, binding.get("path"))
        if path is None:
            errors.append(f"{label}: start evidence path is missing or unsafe: {role}")
            continue
        actual_hash = sha256_file(path)
        if binding.get("file_sha256") != actual_hash:
            errors.append(f"{label}: start evidence binding hash differs: {role}")
        if role == "INTEGRATION_CANDIDATE_MANIFEST":
            manifest_errors, payload, candidate_hash = (
                validate_integration_candidate_manifest(
                    root,
                    label=label,
                    binding=binding,
                )
            )
            errors.extend(manifest_errors)
        else:
            common_errors, payload = legacy.validate_receipt_common(
                root,
                binding_snapshot,
                role=role,
                expected_status=expected_statuses[role],
                allowed_source_goal_ids=allowed_source_goal_ids,
                goal_path_by_id=paths_by_id,
                authority_actors=authority_actors,
            )
            errors.extend(
                f"{label}: {error}" for error in common_errors
            )
            candidate_hash = payload.get("candidate_sha256")
        if (
            not isinstance(candidate_hash, str)
            or not SHA256_PATTERN.fullmatch(candidate_hash)
        ):
            errors.append(f"{label}: start evidence candidate hash is invalid: {role}")
        else:
            candidate_hashes.add(candidate_hash)
        generated_at = parse_iso_datetime(payload.get("generated_at"))
        if generated_at is None:
            errors.append(f"{label}: start evidence generated_at is invalid: {role}")
        elif occurred_at is not None and generated_at > occurred_at:
            errors.append(f"{label}: start evidence postdates the transition: {role}")
    if contract["same_candidate_required"] and len(candidate_hashes) != 1:
        errors.append(f"{label}: start evidence is not bound to one candidate")
    return errors


def validate_semantic_completion_evidence(
    root: Path,
    checkpoint: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    paths_by_id: dict[str, str],
    bindings: dict[str, dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    state = checkpoint.get("goal_execution", {})
    statuses = state.get("status_by_goal", {})
    evidence = state.get("completion_evidence_by_goal", {})
    if not isinstance(statuses, dict) or not isinstance(evidence, dict):
        return ["Goal completion evidence runtime shape is invalid"]
    complete_ids = {
        goal_id
        for goal_id, status in statuses.items()
        if status == "COMPLETE_AT_TARGET"
    }
    archived = state.get("archived_completion_evidence_by_goal")
    superseded_ids = {
        goal_id
        for goal_id, status in statuses.items()
        if status == "SUPERSEDED"
    }
    if set(evidence) != complete_ids:
        errors.append("completion evidence key set differs from completed Goals")
    if not isinstance(archived, dict) or set(archived) != superseded_ids:
        errors.append("archived completion evidence key set differs from superseded Goals")
    for goal_id, references in evidence.items():
        if not isinstance(references, list) or any(
            not isinstance(reference, str) for reference in references
        ):
            errors.append(f"{goal_id}: completion evidence references are malformed")
            continue
        missing = [reference for reference in references if reference not in bindings]
        if missing:
            errors.append(f"{goal_id}: completion evidence bindings are missing: {missing}")

    # Work Item and Workstream receipts are validated at each GOAL_COMPLETED
    # event against the canonical binding snapshot that was effective then.
    # Final-state validation must not reinterpret historical completion through
    # a later canonical binding.
    return errors


def validate_completion_contracts(
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, str],
    materialized: dict[str, list[str]],
    backlog: dict[str, Any],
    mapping: dict[str, str],
    *,
    root: Path | None = None,
    bindings: dict[str, dict[str, Any]] | None = None,
) -> list[str]:
    errors: list[str] = []
    backlog_epics = {
        item.get("epic_id"): item
        for item in backlog.get("epics", [])
        if isinstance(item, dict) and isinstance(item.get("epic_id"), str)
    }
    for goal_id, node in nodes.items():
        if statuses.get(goal_id) != "COMPLETE_AT_TARGET":
            continue
        incomplete_dependencies = [
            dependency
            for dependency in string_list(node.get("completion_requires"))
            if statuses.get(dependency) != "COMPLETE_AT_TARGET"
        ]
        if incomplete_dependencies:
            errors.append(
                f"{goal_id}: completion requirements are not complete: "
                f"{incomplete_dependencies}"
            )
        children = set(string_list(node.get("child_goal_ids")))
        children.update(string_list(materialized.get(goal_id)))
        unfinished_children = [
            child
            for child in sorted(children)
            if statuses.get(child) not in TERMINAL_STATUSES
        ]
        if unfinished_children:
            errors.append(
                f"{goal_id}: materialized child Goals are not terminal: "
                f"{unfinished_children}"
            )
        if node.get("goal_kind") == "MASTER":
            unfinished_workstreams = [
                child
                for child in string_list(node.get("child_goal_ids"))
                if statuses.get(child) != "COMPLETE_AT_TARGET"
            ]
            if unfinished_workstreams:
                errors.append(
                    f"{goal_id}: project cannot complete before all Workstreams: "
                    f"{unfinished_workstreams}"
                )
            completed_by_type: dict[str, set[str]] = {}
            for candidate_id, candidate in nodes.items():
                work_type = candidate.get("work_item_type")
                if (
                    statuses.get(candidate_id) == "COMPLETE_AT_TARGET"
                    and isinstance(work_type, str)
                    and work_type
                ):
                    completed_by_type.setdefault(work_type, set()).add(
                        candidate_id
                    )
            required_terminal_types = {
                "RELEASE_DECISION",
                "DEPLOYMENT_DELIVERY_EVENT",
                "OPERATION_EVENT",
                "HANDOVER_CLOSURE_EVENT",
            }
            missing_types = required_terminal_types - set(completed_by_type)
            if missing_types:
                errors.append(
                    f"{goal_id}: project completion lacks required event classes: "
                    f"{sorted(missing_types)}"
                )
            terminal_chain_exists = False
            for deployment_id in completed_by_type.get(
                "DEPLOYMENT_DELIVERY_EVENT",
                set(),
            ):
                deployment_dependencies = set(
                    string_list(nodes[deployment_id].get("start_requires"))
                )
                if (
                    "WS-GOAL-EPIC-12" not in deployment_dependencies
                    or statuses.get("WS-GOAL-EPIC-12")
                    != "COMPLETE_AT_TARGET"
                ):
                    continue
                release_ids = (
                    deployment_dependencies
                    & completed_by_type.get("RELEASE_DECISION", set())
                )
                operation_ids = {
                    item
                    for item in completed_by_type.get(
                        "OPERATION_EVENT",
                        set(),
                    )
                    if deployment_id
                    in string_list(nodes[item].get("start_requires"))
                }
                handover_ids = {
                    item
                    for item in completed_by_type.get(
                        "HANDOVER_CLOSURE_EVENT",
                        set(),
                    )
                    if deployment_id
                    in string_list(nodes[item].get("start_requires"))
                    and operation_ids.intersection(
                        string_list(nodes[item].get("start_requires"))
                    )
                }
                if release_ids and handover_ids and operation_ids:
                    terminal_chain_exists = True
                    break
            if not terminal_chain_exists:
                errors.append(
                    f"{goal_id}: project completion lacks one completed "
                    "release→deployment→operation/handover dependency chain"
                )
            if root is None or bindings is None:
                errors.append(
                    f"{goal_id}: project artifact completion context is missing"
                )
            else:
                errors.extend(
                    validate_project_artifact_completion(root, bindings)
                )
            continue
        if (
            node.get("goal_kind") != "WORKSTREAM"
            or node.get("workstream_type") != "IMPLEMENTATION"
        ):
            continue
        external_key = node.get("external_key")
        epic = backlog_epics.get(external_key)
        if not isinstance(epic, dict):
            errors.append(f"{goal_id}: canonical Backlog EPIC is missing")
        if external_key == "EPIC-01":
            # EPIC-01 was internally completed before graph-v2 was prepared.
            continue
        expected_pairs = {
            (policy_id, mapping[policy_id])
            for policy_id in string_list(node.get("source_policy_ids"))
            if policy_id in mapping
        }
        actual_completed_pairs = {
            (
                string_list(child.get("source_policy_ids"))[0],
                string_list(child.get("gap_ids"))[0],
            )
            for child_id, child in nodes.items()
            if child.get("goal_kind") == "WORK_ITEM"
            and child.get("work_item_type") == "POLICY_GAP_WORK"
            and child.get("parent_goal_id") == goal_id
            and statuses.get(child_id) == "COMPLETE_AT_TARGET"
            and len(string_list(child.get("source_policy_ids"))) == 1
            and len(string_list(child.get("gap_ids"))) == 1
        }
        workstream_coverage_pairs = set(actual_completed_pairs)
        if (
            external_key == "EPIC-02"
            and node.get("target_completion_level")
            == "IMPLEMENTATION_READY"
        ):
            workstream_coverage_pairs.update(
                (
                    str(record["source_policy_id"]),
                    str(record["gap_id"]),
                )
                for record in (
                    EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS
                )
            )
        missing_pairs = expected_pairs - workstream_coverage_pairs
        if missing_pairs:
            errors.append(
                f"{goal_id}: Workstream policy/Gap completion coverage is incomplete: "
                f"{sorted(missing_pairs)}"
            )
        if isinstance(epic, dict) and epic.get("current_status") != node.get(
            "target_completion_level"
        ):
            bootstrap_coverage_only_completion = (
                external_key == "EPIC-02"
                and not missing_pairs
                and epic.get("current_status") == "IN_PROGRESS"
                and bool(
                    workstream_coverage_pairs
                    - actual_completed_pairs
                )
                and (
                    expected_pairs - actual_completed_pairs
                    == (
                        workstream_coverage_pairs
                        - actual_completed_pairs
                    )
                )
            )
            if not bootstrap_coverage_only_completion:
                errors.append(
                    f"{goal_id}: canonical Backlog has not reached target "
                    "completion"
                )
    return errors


def replay_materialized_children(
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, str],
) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for goal_id, node in nodes.items():
        parent = node.get("parent_goal_id")
        if (
            goal_id in statuses
            and node.get("goal_kind") == "WORK_ITEM"
            and isinstance(parent, str)
            and parent
        ):
            result.setdefault(parent, []).append(goal_id)
    return {parent: sorted(children) for parent, children in result.items()}


def load_snapshot_json(
    root: Path,
    snapshot: dict[str, dict[str, Any]],
    role: str,
) -> tuple[list[str], dict[str, Any]]:
    binding = snapshot.get(role)
    if not isinstance(binding, dict):
        return [f"transition binding snapshot lacks {role}"], {}
    path = resolve_safe_repo_file(root, binding.get("path"))
    if path is None or binding.get("file_sha256") != sha256_file(path):
        return [f"transition binding snapshot is invalid for {role}"], {}
    try:
        return [], load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"transition binding snapshot cannot load {role}: {exc}"], {}


def validate_gap_backlog_pair(
    root: Path,
    snapshot: dict[str, dict[str, Any]],
) -> list[str]:
    """Require a self-sealed Gap/Backlog pair in every canonical snapshot."""

    errors: list[str] = []
    gap_errors, gap = load_snapshot_json(
        root,
        snapshot,
        "IMPLEMENTATION_GAP",
    )
    backlog_errors, backlog = load_snapshot_json(
        root,
        snapshot,
        "IMPLEMENTATION_BACKLOG",
    )
    errors.extend(gap_errors)
    errors.extend(backlog_errors)
    if gap_errors or backlog_errors:
        return errors
    if not continuation.object_seal_is_valid(
        gap,
        "report_content_sha256",
    ):
        errors.append("IMPLEMENTATION_GAP report content seal differs")
    if not continuation.object_seal_is_valid(
        backlog,
        "backlog_content_sha256",
    ):
        errors.append("IMPLEMENTATION_BACKLOG content seal differs")
    if backlog.get("gap_report_content_sha256") != gap.get(
        "report_content_sha256"
    ):
        errors.append(
            "IMPLEMENTATION_GAP and IMPLEMENTATION_BACKLOG must be "
            "revised as one atomically bound pair"
        )
    return errors


def validate_work_item_completion_event(
    root: Path,
    *,
    label: str,
    event: dict[str, Any],
    goal_id: str,
    node: dict[str, Any],
    goal_path: str,
    completion_references: list[str],
    binding_snapshot: dict[str, dict[str, Any]],
    latest_start_event: dict[str, Any] | None,
) -> list[str]:
    errors: list[str] = []
    role = f"WORK_ITEM_COMPLETION::{goal_id}"
    if completion_references.count(role) != 1:
        errors.append(f"{label}: Work Item completion evidence role differs")
    binding = binding_snapshot.get(role)
    if not isinstance(binding, dict):
        return errors + [f"{label}: Work Item completion binding is not effective"]
    if event.get("completion_receipt_binding") != binding:
        errors.append(f"{label}: Work Item completion receipt binding snapshot differs")
    receipt_path = resolve_safe_repo_file(root, binding.get("path"))
    if receipt_path is None or binding.get("file_sha256") != sha256_file(receipt_path):
        return errors + [f"{label}: Work Item completion receipt binding is invalid"]
    try:
        receipt = load_json(receipt_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"{label}: Work Item completion receipt cannot be loaded: {exc}"]

    goal_file = resolve_safe_repo_file(root, goal_path)
    expected_goal_hash = sha256_file(goal_file) if goal_file is not None else None
    for field, expected in (
        ("target_goal_id", goal_id),
        ("target_goal_content_sha256", expected_goal_hash),
        ("work_item_id", node.get("work_item_id")),
    ):
        if receipt.get(field) != expected:
            errors.append(f"{label}: Work Item completion receipt {field} differs")

    if not isinstance(latest_start_event, dict):
        errors.append(
            f"{label}: Work Item completion lacks an execution-session event"
        )
        latest_start_event = {}
    if receipt.get("execution_start_event_sha256") != latest_start_event.get(
        "event_sha256"
    ):
        errors.append(
            f"{label}: Work Item receipt is not bound to the latest "
            "execution-session event"
        )

    window = receipt.get("execution_window")
    reviewer = receipt.get("reviewer")
    chronology = (
        parse_iso_datetime(latest_start_event.get("occurred_at")),
        parse_iso_datetime(window.get("started_at") if isinstance(window, dict) else None),
        parse_iso_datetime(receipt.get("completed_at")),
        parse_iso_datetime(window.get("ended_at") if isinstance(window, dict) else None),
        parse_iso_datetime(
            reviewer.get("decided_at") if isinstance(reviewer, dict) else None
        ),
        parse_iso_datetime(receipt.get("generated_at")),
        parse_iso_datetime(event.get("occurred_at")),
    )
    if any(value is None for value in chronology):
        errors.append(f"{label}: Work Item completion chronology is invalid")
    elif list(chronology) != sorted(chronology):
        errors.append(
            f"{label}: Work Item receipt is not finalized within its started/completed event window"
        )
    return errors


SUBJECT_ID_PATTERN = re.compile(
    r"^(?:FP-\d{3}|GAP-\d{3}|NPC-[A-Z0-9-]+|RQ-[A-Z0-9-]+|"
    r"DES-\d{2}|GATE-[A-Z0-9-]+)$"
)


def subject_ids_in_value(value: Any) -> set[str]:
    if isinstance(value, str):
        return {value} if SUBJECT_ID_PATTERN.fullmatch(value) else set()
    if isinstance(value, list):
        return set().union(*(subject_ids_in_value(item) for item in value))
    if isinstance(value, dict):
        return set().union(
            *(subject_ids_in_value(item) for item in value.values())
        )
    return set()


def changed_subject_ids(before: Any, after: Any) -> set[str]:
    if before == after:
        return set()
    if isinstance(before, dict) and isinstance(after, dict):
        changed: set[str] = set()
        keys = set(before) | set(after)
        changed_keys = {
            key
            for key in keys
            if before.get(key) != after.get(key)
        }
        for key in changed_keys:
            changed.update(
                changed_subject_ids(before.get(key), after.get(key))
            )
        for source in (before, after):
            for key, value in source.items():
                if (
                    key.endswith("_id")
                    or key.endswith("_ids")
                    or key.endswith("_refs")
                ):
                    changed.update(subject_ids_in_value(value))
        return changed
    if isinstance(before, list) and isinstance(after, list):
        if all(isinstance(item, dict) for item in before + after):
            for key in (
                "source_policy_id",
                "gap_id",
                "requirement_id",
                "design_id",
                "id",
                "epic_id",
                "evidence_id",
            ):
                before_ids = [
                    item.get(key)
                    for item in before
                    if isinstance(item.get(key), str)
                ]
                after_ids = [
                    item.get(key)
                    for item in after
                    if isinstance(item.get(key), str)
                ]
                if (
                    len(before_ids) == len(before)
                    and len(after_ids) == len(after)
                    and len(before_ids) == len(set(before_ids))
                    and len(after_ids) == len(set(after_ids))
                ):
                    before_by_id = {
                        str(item[key]): item for item in before
                    }
                    after_by_id = {
                        str(item[key]): item for item in after
                    }
                    return set().union(
                        *(
                            changed_subject_ids(
                                before_by_id.get(item_id),
                                after_by_id.get(item_id),
                            )
                            for item_id in set(before_by_id) | set(after_by_id)
                        )
                    )
            changed: set[str] = set()
            for index in range(max(len(before), len(after))):
                changed.update(
                    changed_subject_ids(
                        before[index] if index < len(before) else None,
                        after[index] if index < len(after) else None,
                    )
                )
            return changed
        if all(isinstance(item, str) for item in before + after):
            return subject_ids_in_value(
                sorted(set(before) ^ set(after))
            )
        return subject_ids_in_value(before) | subject_ids_in_value(after)
    return subject_ids_in_value(before) | subject_ids_in_value(after)


def normalized_normative_payload(value: Any, *, parent_key: str = "") -> Any:
    if isinstance(value, dict):
        normalized: dict[str, Any] = {}
        for key, item in value.items():
            if parent_key == "metadata" and (
                key
                in {
                    "document_id",
                    "manifest_id",
                    "baseline_id",
                    "report_id",
                    "predecessor_report_id",
                    "backlog_id",
                    "predecessor_backlog_id",
                    "register_id",
                    "version",
                    "document_version",
                    "title",
                    "prepared_at",
                    "generated_at",
                    "as_of",
                }
            ):
                continue
            normalized[key] = normalized_normative_payload(
                item,
                parent_key=key,
            )
        return normalized
    if isinstance(value, list):
        return [
            normalized_normative_payload(item, parent_key=parent_key)
            for item in value
        ]
    return value


def valid_provenance_binding_record(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    allowed = {
        "name",
        "path",
        "sha256",
        "content_sha256",
        "file_sha256",
        "bytes",
        "byte_length",
        "relation",
        "immutability",
        "binding_kind",
    }
    if (
        not set(value).issubset(allowed)
        or not isinstance(value.get("path"), str)
        or not value.get("path")
    ):
        return False
    hashes = [
        item
        for key, item in value.items()
        if key in {"sha256", "content_sha256", "file_sha256"}
    ]
    if (
        not hashes
        or any(
            not isinstance(item, str)
            or not SHA256_PATTERN.fullmatch(item)
            for item in hashes
        )
    ):
        return False
    for key in {"name", "relation", "immutability", "binding_kind"} & set(
        value
    ):
        if not isinstance(value[key], str):
            return False
    for key in {"bytes", "byte_length"} & set(value):
        if not isinstance(value[key], int) or value[key] < 0:
            return False
    return True


def valid_source_bindings_provenance(value: Any) -> bool:
    if isinstance(value, list):
        return bool(value) and all(
            valid_provenance_binding_record(item) for item in value
        )
    if isinstance(value, dict):
        return bool(value) and all(
            isinstance(name, str)
            and bool(name)
            and valid_provenance_binding_record(record)
            for name, record in value.items()
        )
    return False


def valid_gap_implementation_snapshot(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    allowed = {
        "scope_kind",
        "base_commit",
        "current_head",
        "branch",
        "dirty_worktree_expected",
        "whole_repository_frozen",
        "focused_scope_only",
        "excluded_from_scope",
        "file_count",
        "path_set_sha256",
        "content_set_sha256",
        "files",
        "snapshot_sha256",
    }
    if set(value) != allowed:
        return False
    for key in {
        "path_set_sha256",
        "content_set_sha256",
        "snapshot_sha256",
    }:
        if (
            not isinstance(value.get(key), str)
            or not SHA256_PATTERN.fullmatch(value[key])
        ):
            return False
    files = value.get("files")
    return (
        isinstance(files, list)
        and value.get("file_count") == len(files)
        and all(valid_provenance_binding_record(item) for item in files)
    )


def normalized_role_residual_value(
    role: str,
    key: str,
    value: Any,
) -> Any:
    hash_keys_by_role = {
        "POLICY_BASELINE": {"content_sha256"},
        "REQUIREMENTS_TRACEABILITY": {
            "document_content_sha256",
            "requirement_binding_sha256",
            "source_binding_sha256",
        },
        "DESIGN_TRACEABILITY": {"register_content_sha256"},
        "IMPLEMENTATION_GAP": {
            "report_content_sha256",
            "source_binding_sha256",
        },
        "IMPLEMENTATION_BACKLOG": {
            "backlog_content_sha256",
            "gap_report_content_sha256",
        },
    }
    if key in hash_keys_by_role.get(role, set()):
        if isinstance(value, str) and SHA256_PATTERN.fullmatch(value):
            return "<VALID_PROVENANCE_SHA256>"
        return value
    if key == "source_bindings" and role in {
        "REQUIREMENTS_TRACEABILITY",
        "DESIGN_TRACEABILITY",
        "IMPLEMENTATION_GAP",
    }:
        if valid_source_bindings_provenance(value):
            return "<VALID_SOURCE_BINDINGS_PROVENANCE>"
        return value
    if role == "IMPLEMENTATION_GAP" and key == "implementation_snapshot":
        if valid_gap_implementation_snapshot(value):
            return "<VALID_IMPLEMENTATION_SNAPSHOT>"
        return value
    if role == "IMPLEMENTATION_BACKLOG" and key == "source_predecessor":
        if (
            isinstance(value, dict)
            and set(value)
            == {"path", "file_sha256", "preserved_unchanged"}
            and isinstance(value.get("path"), str)
            and isinstance(value.get("file_sha256"), str)
            and SHA256_PATTERN.fullmatch(value["file_sha256"])
            and value.get("preserved_unchanged") is True
        ):
            return "<VALID_SOURCE_PREDECESSOR>"
        return value
    if role == "IMPLEMENTATION_BACKLOG" and key == "next_single_action":
        allowed = {
            "epic_id",
            "work_item_id",
            "source_policy_id",
            "gap_id",
            "status",
            "action",
        }
        required = {
            "epic_id",
            "work_item_id",
            "source_policy_id",
            "status",
            "action",
        }
        if (
            isinstance(value, dict)
            and required.issubset(value)
            and set(value).issubset(allowed)
            and all(isinstance(item, str) for item in value.values())
        ):
            return "<VALID_DERIVED_NEXT_ACTION>"
        return value
    return normalized_normative_payload(value, parent_key=key)


def backlog_scoped_projection(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    normative_epic_fields = {
        "epic_id",
        "title",
        "priority",
        "wave",
        "source_policy_ids",
        "ordered_source_policy_ids",
        "gap_ids",
        "dependencies",
        "owner_roles",
        "effort_mix",
        "target_completion_level",
        "implementation_done_when",
        "release_verification_done_when",
        "deferred_release_gate_ids",
        "done_when",
        "rank_within_wave",
    }
    epics = [
        {
            key: value
            for key, value in epic.items()
            if key in normative_epic_fields
        }
        for epic in payload.get("epics", [])
        if isinstance(epic, dict)
    ]
    return {
        "next_action_sequence": payload.get("next_action_sequence"),
        "epics": epics,
    }


def backlog_operational_projection(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    return {
        "next_single_action": payload.get("next_single_action"),
        "epic_progress": [
            {
                "epic_id": row.get("epic_id"),
                "current_status": row.get("current_status"),
                "current_status_reason": row.get("current_status_reason"),
            }
            for row in payload.get("epics", [])
            if isinstance(row, dict)
        ],
    }


def validate_gap_successor_scope(
    *,
    before_payload: Any,
    after_payload: Any,
) -> tuple[list[str], set[str]]:
    if not isinstance(before_payload, dict) or not isinstance(
        after_payload,
        dict,
    ):
        return ["implementation Gap successor payload is missing"], set()
    before_rows = {
        row.get("gap_id"): row
        for row in before_payload.get("assessments", [])
        if isinstance(row, dict) and isinstance(row.get("gap_id"), str)
    }
    after_rows = {
        row.get("gap_id"): row
        for row in after_payload.get("assessments", [])
        if isinstance(row, dict) and isinstance(row.get("gap_id"), str)
    }
    changed_gap_ids = {
        gap_id
        for gap_id in set(before_rows) | set(after_rows)
        if before_rows.get(gap_id) != after_rows.get(gap_id)
    }
    scope = after_payload.get("reassessment_scope")
    if not isinstance(scope, dict):
        return ["implementation Gap reassessment scope is missing"], changed_gap_ids
    direct = set(string_list(scope.get("directly_reassessed_gap_ids")))
    reviewed_impact = set(string_list(scope.get("impact_reviewed_gap_ids")))
    reviewed = set(string_list(scope.get("reviewed_gap_ids")))
    carried = set(string_list(scope.get("carried_forward_gap_ids")))
    errors: list[str] = []
    if (
        direct & reviewed_impact
        or reviewed != direct | reviewed_impact
        or reviewed != changed_gap_ids
        or carried != set(after_rows) - reviewed
        or scope.get("carried_forward_gap_count") != len(carried)
        or any(
            before_rows.get(gap_id) != after_rows.get(gap_id)
            for gap_id in carried
        )
    ):
        errors.append("implementation Gap reassessment scope differs from row delta")
    subject_ids = set(changed_gap_ids)
    subject_ids.update(
        str(after_rows[gap_id].get("source_policy_id"))
        for gap_id in changed_gap_ids
        if gap_id in after_rows
        and isinstance(after_rows[gap_id].get("source_policy_id"), str)
    )
    return errors, subject_ids


def validate_backlog_successor_binding(
    root: Path,
    *,
    before_binding: dict[str, Any] | None,
    after_payload: Any,
    bindings_after: dict[str, dict[str, Any]],
) -> list[str]:
    if not isinstance(after_payload, dict):
        return ["implementation Backlog successor payload is missing"]
    predecessor = after_payload.get("source_predecessor")
    errors: list[str] = []
    if (
        not isinstance(before_binding, dict)
        or not isinstance(predecessor, dict)
        or predecessor.get("path") != before_binding.get("path")
        or predecessor.get("file_sha256")
        != before_binding.get("file_sha256")
    ):
        errors.append("implementation Backlog predecessor binding differs")
    gap_binding = bindings_after.get("IMPLEMENTATION_GAP")
    gap_path = (
        resolve_safe_repo_file(root, gap_binding.get("path"))
        if isinstance(gap_binding, dict)
        else None
    )
    try:
        gap_payload = load_json(gap_path) if gap_path is not None else {}
    except (OSError, ValueError, json.JSONDecodeError):
        gap_payload = {}
    if after_payload.get("gap_report_content_sha256") != gap_payload.get(
        "report_content_sha256"
    ):
        errors.append("implementation Backlog is not bound to the current Gap report")
    sequence = after_payload.get("next_action_sequence")
    if (
        not isinstance(sequence, list)
        or any(not isinstance(row, dict) for row in sequence)
        or len(
            {
                row.get("source_policy_id")
                for row in sequence
                if isinstance(row, dict)
            }
        )
        != len(sequence)
    ):
        errors.append("implementation Backlog action sequence is invalid")
    return errors


def canonical_changed_subject_ids_by_role(
    root: Path,
    *,
    changed_roles: list[str],
    bindings_before: dict[str, dict[str, Any]],
    bindings_after: dict[str, dict[str, Any]],
) -> tuple[list[str], dict[str, list[str]]]:
    errors: list[str] = []
    result: dict[str, list[str]] = {}
    for role in changed_roles:
        if role not in SCOPED_IMPACT_ROLES:
            continue
        before_binding = bindings_before.get(role)
        after_binding = bindings_after.get(role)
        before_payload: Any = None
        after_payload: Any = None
        for side, binding in (
            ("before", before_binding),
            ("after", after_binding),
        ):
            if binding is None:
                continue
            path = resolve_safe_repo_file(root, binding.get("path"))
            if path is None or binding.get("file_sha256") != sha256_file(path):
                errors.append(
                    f"canonical {role} {side} binding cannot be diffed"
                )
                continue
            try:
                payload = load_json(path)
            except (OSError, ValueError, json.JSONDecodeError) as exc:
                errors.append(
                    f"canonical {role} {side} payload cannot be diffed: {exc}"
                )
                continue
            if side == "before":
                before_payload = payload
            else:
                after_payload = payload
        if role == "ARTIFACT_REGISTER":
            (
                delta_errors,
                changed_artifact_codes,
                _,
                global_changed,
            ) = artifact_register_delta(
                root,
                before_binding,
                after_binding,
            )
            errors.extend(
                f"canonical {role}: {error}" for error in delta_errors
            )
            subject_ids = set(changed_artifact_codes)
            if global_changed:
                subject_ids.add("*")
            result[role] = sorted(subject_ids)
            continue
        if role == "ARTIFACT_CHANGE_LOG":
            delta_errors, changed_artifact_codes = (
                artifact_change_log_delta_scope(
                    root,
                    before_binding,
                    after_binding,
                    bindings_after.get("ARTIFACT_REGISTER"),
                )
            )
            errors.extend(
                f"canonical {role}: {error}" for error in delta_errors
            )
            subject_ids = set(changed_artifact_codes)
            if delta_errors and before_payload != after_payload:
                subject_ids.add("*")
            result[role] = sorted(subject_ids)
            continue
        scoped_keys = SCOPED_CONTENT_KEYS_BY_ROLE.get(role, set())
        global_keys = GLOBAL_CONTENT_KEYS_BY_ROLE.get(role, set())
        before_scoped = {
            key: before_payload.get(key)
            if isinstance(before_payload, dict)
            else None
            for key in scoped_keys
        }
        after_scoped = {
            key: after_payload.get(key)
            if isinstance(after_payload, dict)
            else None
            for key in scoped_keys
        }
        if before_payload == after_payload:
            subject_ids = set()
        elif role == "IMPLEMENTATION_GAP":
            scope_errors, subject_ids = validate_gap_successor_scope(
                before_payload=before_payload,
                after_payload=after_payload,
            )
            errors.extend(
                f"canonical {role}: {error}" for error in scope_errors
            )
        else:
            if role == "IMPLEMENTATION_BACKLOG":
                before_scoped = backlog_scoped_projection(before_payload)
                after_scoped = backlog_scoped_projection(after_payload)
                errors.extend(
                    f"canonical {role}: {error}"
                    for error in validate_backlog_successor_binding(
                        root,
                        before_binding=bindings_before.get(role),
                        after_payload=after_payload,
                        bindings_after=bindings_after,
                    )
                )
            subject_ids = changed_subject_ids(before_scoped, after_scoped)
        if (
            normalized_normative_payload(before_scoped)
            != normalized_normative_payload(after_scoped)
            and not subject_ids
        ):
            subject_ids.add("*")
        before_global = {
            key: before_payload.get(key)
            if isinstance(before_payload, dict)
            else None
            for key in global_keys
        }
        after_global = {
            key: after_payload.get(key)
            if isinstance(after_payload, dict)
            else None
            for key in global_keys
        }
        if normalized_normative_payload(
            before_global
        ) != normalized_normative_payload(after_global):
            subject_ids.add("*")
        all_top_level_keys = (
            set(before_payload)
            if isinstance(before_payload, dict)
            else set()
        ) | (
            set(after_payload)
            if isinstance(after_payload, dict)
            else set()
        )
        residual_keys = (
            all_top_level_keys
            - scoped_keys
            - global_keys
            - NON_IMPACT_PROVENANCE_TOP_LEVEL_KEYS_BY_ROLE.get(
                role,
                set(),
            )
        )
        before_residual = {
            key: normalized_role_residual_value(
                role,
                key,
                before_payload.get(key)
                if isinstance(before_payload, dict)
                else None,
            )
            for key in residual_keys
        }
        after_residual = {
            key: normalized_role_residual_value(
                role,
                key,
                after_payload.get(key)
                if isinstance(after_payload, dict)
                else None,
            )
            for key in residual_keys
        }
        if before_residual != after_residual:
            subject_ids.add("*")
        if role in TRACE_TRANSLATION_ROLES and before_payload != after_payload:
            trace_subject_ids = {
                identifier
                for identifier in subject_ids
                if identifier == "*"
                or identifier.startswith(("FP-", "NPC-", "GATE-", "GAP-"))
            }
            if subject_ids and not trace_subject_ids:
                trace_subject_ids.add("*")
            subject_ids = trace_subject_ids
        result[role] = sorted(subject_ids)
    return errors, result


def changed_binding_affected_goals(
    *,
    changed_roles: set[str],
    changed_subject_ids_by_role: dict[str, list[str]],
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, str],
    completion_bindings_by_goal: dict[str, dict[str, dict[str, Any]]],
    latest_start_event_by_goal: dict[str, dict[str, Any]],
) -> dict[str, set[str]]:
    affected: dict[str, set[str]] = {}
    for goal_id, status in statuses.items():
        node = nodes.get(goal_id, {})
        is_completed = status == "COMPLETE_AT_TARGET"
        is_open_work_item = (
            node.get("goal_kind") == "WORK_ITEM"
            and status not in TERMINAL_STATUSES
        )
        is_open_workstream = (
            node.get("goal_kind") == "WORKSTREAM"
            and status not in TERMINAL_STATUSES
        )
        if not is_completed and not is_open_work_item and not is_open_workstream:
            continue
        consumed_roles = set(completion_bindings_by_goal.get(goal_id, {}))
        start_bindings = latest_start_event_by_goal.get(goal_id, {}).get(
            "start_evidence_bindings"
        )
        if isinstance(start_bindings, dict):
            consumed_roles.update(start_bindings)
        materialized_from_role = node.get("materialized_from_role")
        if isinstance(materialized_from_role, str):
            consumed_roles.add(materialized_from_role)
        consumed_roles.update(string_list(node.get("canonical_input_roles")))
        goal_subject_ids = set(string_list(node.get("source_policy_ids")))
        goal_subject_ids.update(string_list(node.get("gap_ids")))
        impact_roles: set[str] = set()
        for role in changed_roles & consumed_roles:
            if role in (
                NON_RETROACTIVE_LEDGER_ROLES
                | NON_IMPACT_CONTROL_ROLES
            ):
                continue
            if role not in SCOPED_IMPACT_ROLES:
                impact_roles.add(role)
                continue
            changed_ids = set(changed_subject_ids_by_role.get(role, []))
            if "*" in changed_ids or changed_ids & goal_subject_ids:
                impact_roles.add(role)
            elif (
                node.get("goal_kind") == "WORK_ITEM"
                and not goal_subject_ids
                and changed_ids
            ):
                impact_roles.add(role)
        if impact_roles:
            affected[goal_id] = impact_roles
    return affected


def expand_affected_goal_dependency_closure(
    *,
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, str],
    directly_affected_goal_roles: dict[str, set[str]],
) -> dict[str, set[str]]:
    """Expand canonical impact through ownership and completed dependencies."""

    affected = {
        goal_id: set(roles)
        for goal_id, roles in directly_affected_goal_roles.items()
    }
    changed = True
    while changed:
        changed = False
        affected_ids = set(affected)
        for goal_id, node in nodes.items():
            status = statuses.get(goal_id)
            kind = node.get("goal_kind")
            if kind not in {"WORK_ITEM", "WORKSTREAM", "MASTER"}:
                continue
            start_dependencies = set(string_list(node.get("start_requires")))
            completion_dependencies = set(
                string_list(node.get("completion_requires"))
            )
            invalid_start_dependencies = start_dependencies & affected_ids
            invalid_completion_dependencies = (
                completion_dependencies & affected_ids
            )
            invalid_work_item_dependencies = {
                dependency
                for dependency in (
                    invalid_start_dependencies
                    | invalid_completion_dependencies
                )
                if nodes.get(dependency, {}).get("goal_kind") == "WORK_ITEM"
            }
            affected_children = {
                child_id
                for child_id, child in nodes.items()
                if child.get("parent_goal_id") == goal_id
                and child_id in affected_ids
            }
            must_invalidate = bool(
                invalid_start_dependencies
                and status
                in {
                    "READY",
                    "IN_PROGRESS",
                    "AWAITING_USER",
                    "AWAITING_EXTERNAL",
                    "BLOCKED",
                    "COMPLETE_AT_TARGET",
                }
            )
            must_invalidate = must_invalidate or bool(
                status == "COMPLETE_AT_TARGET"
                and (invalid_completion_dependencies or affected_children)
            )
            must_invalidate = must_invalidate or bool(
                kind == "WORK_ITEM"
                and status not in TERMINAL_STATUSES
                and invalid_work_item_dependencies
            )
            if not must_invalidate:
                continue
            source_ids = (
                invalid_start_dependencies
                | invalid_completion_dependencies
                | affected_children
            )
            inherited_roles = {
                role
                for source_id in source_ids
                for role in affected.get(source_id, set())
            }
            inherited_roles.add("DEPENDENCY_CLOSURE")
            if invalid_start_dependencies:
                inherited_roles.add("START_DEPENDENCY_INVALIDATED")
            if invalid_completion_dependencies:
                inherited_roles.add("COMPLETION_DEPENDENCY_INVALIDATED")
            if affected_children:
                inherited_roles.add("CHILD_AGGREGATE_INVALIDATED")
            if invalid_work_item_dependencies:
                inherited_roles.add(
                    "WORK_ITEM_DEPENDENCY_REVISION_REQUIRED"
                )
            merged_roles = affected.get(goal_id, set()) | inherited_roles
            if merged_roles != affected.get(goal_id, set()):
                affected[goal_id] = merged_roles
                changed = True
    return affected


def workstream_reopen_target_status(impact_roles: set[str]) -> str:
    return (
        "PLANNED"
        if "START_DEPENDENCY_INVALIDATED" in impact_roles
        else "READY"
    )


def goal_allows_unaffected_disposition(
    node: dict[str, Any],
    status: str | None,
    impact_roles: set[str],
) -> bool:
    return (
        node.get("goal_kind") == "WORK_ITEM"
        and status == "COMPLETE_AT_TARGET"
        and "DEPENDENCY_CLOSURE" not in impact_roles
    )


def validate_unaffected_impact_assessment(
    root: Path,
    *,
    label: str,
    goal_id: str,
    completion_event: dict[str, Any],
    changed_roles: list[str],
    bindings_before: dict[str, dict[str, Any]],
    bindings_after: dict[str, dict[str, Any]],
    disposition: dict[str, Any],
    occurred_at: datetime | None,
) -> list[str]:
    errors: list[str] = []
    binding = disposition.get("assessment_binding")
    role = binding.get("role") if isinstance(binding, dict) else None
    if (
        role != f"CANONICAL_CHANGE_IMPACT_ASSESSMENT::{goal_id}"
        or set(binding) != {"role", "document_id", "path", "file_sha256"}
    ):
        return [f"{label}: {goal_id} impact assessment binding differs"]
    path = resolve_safe_repo_file(root, binding.get("path"))
    if path is None or binding.get("file_sha256") != sha256_file(path):
        return [f"{label}: {goal_id} impact assessment file differs"]
    actual_hash = sha256_file(path)
    errors.extend(
        f"{label}: {goal_id}: {error}"
        for error in legacy.validate_external_attestation_anchor(
            role,
            actual_hash,
        )
    )
    try:
        assessment = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"{label}: {goal_id} impact assessment cannot be loaded: {exc}"
        ]
    expected_before = {
        changed_role: bindings_before.get(changed_role)
        for changed_role in changed_roles
    }
    expected_after = {
        changed_role: bindings_after.get(changed_role)
        for changed_role in changed_roles
    }
    assessed_at = parse_iso_datetime(assessment.get("assessed_at"))
    assessor = assessment.get("assessor")
    if (
        assessment.get("schema_version") != "1.0"
        or assessment.get("evidence_type")
        != "CANONICAL_CHANGE_IMPACT_ASSESSMENT"
        or assessment.get("document_id") != binding.get("document_id")
        or assessment.get("status") != "ACCEPTED"
        or assessment.get("result") != "UNAFFECTED"
        or assessment.get("target_goal_id") != goal_id
        or assessment.get("target_completion_event_sha256")
        != completion_event.get("event_sha256")
        or assessment.get("changed_roles") != changed_roles
        or assessment.get("bindings_before") != expected_before
        or assessment.get("bindings_after") != expected_after
        or not isinstance(assessment.get("rationale"), str)
        or not assessment.get("rationale")
        or assessed_at is None
        or occurred_at is None
        or assessed_at > occurred_at
        or not isinstance(assessor, dict)
        or not isinstance(assessor.get("id"), str)
        or not assessor.get("id")
        or not isinstance(assessor.get("role"), str)
        or not assessor.get("role")
        or assessor.get("decided_at") != assessment.get("assessed_at")
    ):
        errors.append(f"{label}: {goal_id} impact assessment semantics differ")
    errors.extend(
        f"{label}: {goal_id}: {error}"
        for error in legacy.validate_receipt_authority_roster_binding(
            root,
            assessment,
            bindings_after,
        )
    )
    roster_errors, actors = legacy.load_authority_roster(
        root,
        bindings_after,
    )
    errors.extend(
        f"{label}: {goal_id}: {error}" for error in roster_errors
    )
    errors.extend(
        f"{label}: {goal_id}: {error}"
        for error in legacy.validate_receipt_actor(
            "CANONICAL_CHANGE_IMPACT_ASSESSMENT",
            "assessor",
            assessor,
            require_decision=True,
        )
    )
    errors.extend(
        f"{label}: {goal_id}: {error}"
        for error in legacy.validate_actor_against_roster(
            receipt_role="CANONICAL_CHANGE_IMPACT_ASSESSMENT",
            actor_name="assessor",
            actor=assessor,
            actors_by_id=actors,
        )
    )
    return errors


def load_bound_json_payload(
    root: Path,
    binding: dict[str, Any] | None,
    *,
    label: str,
) -> tuple[list[str], dict[str, Any]]:
    if not isinstance(binding, dict):
        return [f"{label} binding is missing"], {}
    path = resolve_safe_repo_file(root, binding.get("path"))
    if path is None or binding.get("file_sha256") != sha256_file(path):
        return [f"{label} binding differs"], {}
    try:
        return [], load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"{label} cannot be loaded: {exc}"], {}


def artifact_register_sensitive_projection(row: dict[str, Any]) -> dict[str, Any]:
    state = row.get("state")
    version = row.get("version")
    dates = row.get("dates")
    location = row.get("location")
    record_controls = row.get("record_controls")
    trace = row.get("trace")
    approved_input_trace = (
        trace.get("approved_input_trace")
        if isinstance(trace, dict)
        else None
    )
    return {
        "applicability": row.get("applicability"),
        "activation_condition": row.get("activation_condition"),
        "activation_result": row.get("activation_result"),
        "n_a_reason": row.get("n_a_reason"),
        "authoring_readiness": row.get("authoring_readiness"),
        "responsibility": row.get("responsibility"),
        "approval_control": row.get("approval_control"),
        "change_control": row.get("change_control"),
        "state": state,
        "version": {
            key: version.get(key)
            for key in (
                "baseline_id",
                "approved_snapshot_version",
                "snapshot_id",
            )
        }
        if isinstance(version, dict)
        else version,
        "approval_dates": {
            key: dates.get(key)
            for key in (
                "approved_at",
                "approval_event_date",
                "approved_at_status",
                "external_signed_or_issued_at",
            )
        }
        if isinstance(dates, dict)
        else dates,
        "external_source": (
            location.get("external_source")
            if isinstance(location, dict)
            else location
        ),
        "external_record": {
            key: record_controls.get(key)
            for key in (
                "external_record_hash",
                "external_signature_status",
            )
        }
        if isinstance(record_controls, dict)
        else record_controls,
        "approved_input_trace": approved_input_trace,
    }


def artifact_register_delta(
    root: Path,
    binding_before: dict[str, Any] | None,
    binding_after: dict[str, Any] | None,
) -> tuple[list[str], list[str], bool, bool]:
    """Return errors, changed artifact codes, auth requirement, global change."""

    before_errors, before = load_bound_json_payload(
        root,
        binding_before,
        label="ARTIFACT_REGISTER before",
    )
    after_errors, after = load_bound_json_payload(
        root,
        binding_after,
        label="ARTIFACT_REGISTER after",
    )
    errors = before_errors + after_errors
    if errors:
        return errors, [], True, True
    if (
        "content_sha256" in before
        or "content_sha256" in after
    ):
        if not continuation.object_seal_is_valid(
            before,
            "content_sha256",
        ):
            errors.append("ARTIFACT_REGISTER before content seal differs")
        if not continuation.object_seal_is_valid(
            after,
            "content_sha256",
        ):
            errors.append("ARTIFACT_REGISTER after content seal differs")
    before_rows = before.get("artifacts")
    after_rows = after.get("artifacts")
    if not isinstance(before_rows, list) or not isinstance(after_rows, list):
        return ["ARTIFACT_REGISTER rows are missing"], [], True, True
    before_by_code = {
        row.get("artifact_type_code"): row
        for row in before_rows
        if isinstance(row, dict)
        and isinstance(row.get("artifact_type_code"), str)
    }
    after_by_code = {
        row.get("artifact_type_code"): row
        for row in after_rows
        if isinstance(row, dict)
        and isinstance(row.get("artifact_type_code"), str)
    }
    if len(before_by_code) != len(before_rows) or len(after_by_code) != len(
        after_rows
    ):
        errors.append("ARTIFACT_REGISTER contains duplicate or invalid row IDs")
    changed_codes = sorted(
        code
        for code in set(before_by_code) | set(after_by_code)
        if before_by_code.get(code) != after_by_code.get(code)
    )
    authorization_required = set(before_by_code) != set(after_by_code)
    for code in changed_codes:
        before_row = before_by_code.get(code)
        after_row = after_by_code.get(code)
        if not isinstance(before_row, dict) or not isinstance(after_row, dict):
            authorization_required = True
            continue
        lifecycle = before_row.get("state", {}).get("lifecycle_status")
        if (
            lifecycle == "APPROVED_BASELINED"
            or artifact_register_sensitive_projection(before_row)
            != artifact_register_sensitive_projection(after_row)
        ):
            authorization_required = True
    ignored_top_level = {
        "metadata",
        "source_bindings",
        "artifacts",
        "content_sha256",
    }
    before_global = {
        key: value
        for key, value in before.items()
        if key not in ignored_top_level
    }
    after_global = {
        key: value
        for key, value in after.items()
        if key not in ignored_top_level
    }
    global_changed = before_global != after_global
    if global_changed:
        authorization_required = True
    return errors, changed_codes, authorization_required, global_changed


def artifact_row_claims_external_fact(row: dict[str, Any]) -> bool:
    state = row.get("state")
    if not isinstance(state, dict):
        return True
    status_values = {
        str(value).upper()
        for key, value in state.items()
        if key.endswith("_status") and isinstance(value, str)
    }
    if any(
        token in re.split(r"[^A-Z0-9]+", value)
        for value in status_values
        for token in EXTERNAL_FACT_STATUS_TOKENS
    ):
        return True
    return bool(
        set(string_list(state.get("blockers"))) & EXTERNAL_EXECUTION_BLOCKERS
    )


def artifact_register_requires_external_authorization(
    root: Path,
    binding_before: dict[str, Any] | None,
    binding_after: dict[str, Any] | None,
) -> bool:
    errors, changed_codes, sensitive, global_changed = artifact_register_delta(
        root,
        binding_before,
        binding_after,
    )
    if errors or global_changed:
        return True
    if not sensitive:
        return False
    before_errors, before = load_bound_json_payload(
        root,
        binding_before,
        label="ARTIFACT_REGISTER before authorization",
    )
    after_errors, after = load_bound_json_payload(
        root,
        binding_after,
        label="ARTIFACT_REGISTER after authorization",
    )
    if before_errors or after_errors:
        return True
    before_rows = {
        row.get("artifact_type_code"): row
        for row in before.get("artifacts", [])
        if isinstance(row, dict)
        and isinstance(row.get("artifact_type_code"), str)
    }
    after_rows = {
        row.get("artifact_type_code"): row
        for row in after.get("artifacts", [])
        if isinstance(row, dict)
        and isinstance(row.get("artifact_type_code"), str)
    }
    if set(before_rows) != set(after_rows):
        return True
    for code in changed_codes:
        before_row = before_rows.get(code)
        after_row = after_rows.get(code)
        if not isinstance(before_row, dict) or not isinstance(after_row, dict):
            return True
        display_code = after_row.get("display_code")
        management = after_row.get("management_contract")
        change_profile = (
            management.get("change_profile_id")
            if isinstance(management, dict)
            else None
        )
        before_state = before_row.get("state")
        after_state = after_row.get("state")
        removed_external_blocker = (
            isinstance(before_state, dict)
            and isinstance(after_state, dict)
            and bool(
                (
                    set(string_list(before_state.get("blockers")))
                    & EXTERNAL_EXECUTION_BLOCKERS
                )
                - set(string_list(after_state.get("blockers")))
            )
        )
        if (
            display_code in EXTERNALLY_GOVERNED_ARTIFACT_DISPLAY_CODES
            or change_profile == "EXTERNAL_ORIGINAL"
            or removed_external_blocker
            or (
                not artifact_row_claims_external_fact(before_row)
                and artifact_row_claims_external_fact(after_row)
            )
        ):
            return True
    return False


def artifact_change_log_delta_scope(
    root: Path,
    binding_before: dict[str, Any] | None,
    binding_after: dict[str, Any] | None,
    artifact_register_binding_after: dict[str, Any] | None,
) -> tuple[list[str], list[str]]:
    before_errors, before = load_bound_json_payload(
        root,
        binding_before,
        label="ARTIFACT_CHANGE_LOG before",
    )
    after_errors, after = load_bound_json_payload(
        root,
        binding_after,
        label="ARTIFACT_CHANGE_LOG after",
    )
    register_errors, register = load_bound_json_payload(
        root,
        artifact_register_binding_after,
        label="ARTIFACT_REGISTER after",
    )
    errors = before_errors + after_errors + register_errors
    if errors:
        return errors, []
    if (
        "content_sha256" in before
        or "content_sha256" in after
    ):
        if not continuation.object_seal_is_valid(
            before,
            "content_sha256",
        ):
            errors.append("ARTIFACT_CHANGE_LOG before content seal differs")
        if not continuation.object_seal_is_valid(
            after,
            "content_sha256",
        ):
            errors.append("ARTIFACT_CHANGE_LOG after content seal differs")
    if errors:
        return errors, []
    before_changes = before.get("changes")
    after_changes = after.get("changes")
    if (
        not isinstance(before_changes, list)
        or not isinstance(after_changes, list)
        or after_changes[: len(before_changes)] != before_changes
        or len(after_changes) == len(before_changes)
    ):
        return ["ARTIFACT_CHANGE_LOG producer must append a change row"], []
    display_to_type = {
        row.get("display_code"): row.get("artifact_type_code")
        for row in register.get("artifacts", [])
        if isinstance(row, dict)
        and isinstance(row.get("display_code"), str)
        and isinstance(row.get("artifact_type_code"), str)
    }
    existing_ids = {
        change.get("change_id")
        for change in before_changes
        if isinstance(change, dict)
        and isinstance(change.get("change_id"), str)
    }
    scope: set[str] = set()
    for change in after_changes[len(before_changes) :]:
        if not isinstance(change, dict):
            errors.append("ARTIFACT_CHANGE_LOG appended row is malformed")
            continue
        change_id = change.get("change_id")
        semantic_text_fields = (
            "change_type",
            "title",
            "reason",
            "before_summary",
            "after_summary",
            "source_baseline_id",
            "lifecycle_status",
            "producer_receipt_role",
        )
        source_goal_binding = change.get("source_goal_binding")
        source_goal_path = resolve_safe_repo_file(
            root,
            source_goal_binding.get("path")
            if isinstance(source_goal_binding, dict)
            else None,
        )
        affected_paths = change.get("affected_paths")
        affected_path_bindings = change.get("affected_path_bindings")
        normalized_path_bindings = (
            [
                {
                    "path": item.get("path"),
                    "before_sha256": item.get("before_sha256"),
                    "after_sha256": item.get("after_sha256"),
                }
                for item in affected_path_bindings
                if isinstance(item, dict)
            ]
            if isinstance(affected_path_bindings, list)
            else []
        )
        parsed_date = None
        try:
            parsed_date = datetime.strptime(
                str(change.get("date")),
                "%Y-%m-%d",
            ).date()
        except ValueError:
            pass
        if (
            not isinstance(change_id, str)
            or not re.fullmatch(r"CHG-[A-Z0-9-]+", change_id)
            or change_id in existing_ids
            or any(
                not isinstance(change.get(field), str)
                or not change.get(field)
                for field in semantic_text_fields
            )
            or parsed_date is None
            or change.get("artifact_register_binding_after")
            != artifact_register_binding_after
            or not isinstance(source_goal_binding, dict)
            or set(source_goal_binding)
            != {"document_id", "path", "file_sha256"}
            or not isinstance(source_goal_binding.get("document_id"), str)
            or not source_goal_binding.get("document_id")
            or source_goal_path is None
            or source_goal_binding.get("file_sha256")
            != sha256_file(source_goal_path)
            or not isinstance(affected_paths, list)
            or not affected_paths
            or affected_paths != sorted(set(affected_paths))
            or any(
                resolve_safe_repo_file(root, item) is None
                for item in affected_paths
            )
            or not isinstance(affected_path_bindings, list)
            or len(normalized_path_bindings) != len(affected_path_bindings)
            or [
                item.get("path") for item in normalized_path_bindings
            ]
            != affected_paths
            or any(
                not isinstance(item.get("after_sha256"), str)
                or not SHA256_PATTERN.fullmatch(item["after_sha256"])
                or (
                    item.get("before_sha256") is not None
                    and (
                        not isinstance(item.get("before_sha256"), str)
                        or not SHA256_PATTERN.fullmatch(
                            item["before_sha256"]
                        )
                        or item.get("before_sha256")
                        == item.get("after_sha256")
                    )
                )
                or sha256_file(
                    resolve_safe_repo_file(root, item.get("path"))
                )
                != item.get("after_sha256")
                for item in normalized_path_bindings
            )
        ):
            errors.append(
                f"ARTIFACT_CHANGE_LOG appended row semantics differ: {change_id}"
            )
        if isinstance(change_id, str):
            existing_ids.add(change_id)
        affected = string_list(change.get("affected_artifact_codes"))
        if not affected or affected != sorted(set(affected)):
            errors.append(
                "ARTIFACT_CHANGE_LOG appended row lacks affected artifact codes"
            )
        for code in affected:
            artifact_type_code = (
                code if code.startswith("DLV-") else display_to_type.get(code)
            )
            if not isinstance(artifact_type_code, str):
                errors.append(
                    f"ARTIFACT_CHANGE_LOG references unknown artifact code: {code}"
                )
            else:
                scope.add(artifact_type_code)
    return errors, sorted(scope)


def artifact_change_log_appended_rows(
    root: Path,
    binding_before: dict[str, Any] | None,
    binding_after: dict[str, Any] | None,
) -> list[dict[str, Any]]:
    before_errors, before = load_bound_json_payload(
        root,
        binding_before,
        label="ARTIFACT_CHANGE_LOG before producer",
    )
    after_errors, after = load_bound_json_payload(
        root,
        binding_after,
        label="ARTIFACT_CHANGE_LOG after producer",
    )
    if before_errors or after_errors:
        return []
    before_changes = before.get("changes")
    after_changes = after.get("changes")
    if (
        not isinstance(before_changes, list)
        or not isinstance(after_changes, list)
        or after_changes[: len(before_changes)] != before_changes
    ):
        return []
    return [
        row
        for row in after_changes[len(before_changes) :]
        if isinstance(row, dict)
    ]


def validate_artifact_change_log_implementation_binding(
    *,
    appended_rows: list[dict[str, Any]],
    implementation: dict[str, Any],
) -> list[str]:
    implementation_changes = [
        {
            "path": item.get("path"),
            "before_sha256": item.get("before_sha256"),
            "after_sha256": item.get("after_sha256"),
        }
        for item in implementation.get("changed_artifacts", [])
        if isinstance(item, dict)
    ]
    log_changes = [
        {
            "path": item.get("path"),
            "before_sha256": item.get("before_sha256"),
            "after_sha256": item.get("after_sha256"),
        }
        for row in appended_rows
        for item in row.get("affected_path_bindings", [])
        if isinstance(item, dict)
    ]
    if (
        not implementation_changes
        or not log_changes
        or len(
            {item.get("path") for item in implementation_changes}
        )
        != len(implementation_changes)
        or len({item.get("path") for item in log_changes})
        != len(log_changes)
        or sorted(
            implementation_changes,
            key=lambda item: str(item.get("path")),
        )
        != sorted(log_changes, key=lambda item: str(item.get("path")))
    ):
        return [
            "change-log affected paths/hashes differ from the producer "
            "implementation record"
        ]
    return []


def canonical_role_requires_external_authorization(
    root: Path,
    role: str,
    binding_before: dict[str, Any] | None,
    binding_after: dict[str, Any] | None,
) -> bool:
    if role == "POLICY_BASELINE":
        return True
    if role == "ARTIFACT_REGISTER":
        return artifact_register_requires_external_authorization(
            root,
            binding_before,
            binding_after,
        )
    if role not in {"REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY"}:
        return False
    effective_states: list[bool] = []
    for binding in (binding_before, binding_after):
        if not isinstance(binding, dict):
            continue
        path = resolve_safe_repo_file(root, binding.get("path"))
        if path is None:
            return True
        try:
            payload = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError):
            return True
        metadata = payload.get("metadata")
        if not isinstance(metadata, dict):
            return True
        approval_status = str(metadata.get("approval_status", ""))
        baseline_status = str(metadata.get("baseline_status", ""))
        effective_states.append(
            approval_status not in {"", "NOT_APPROVED", "DRAFT"}
            or baseline_status not in {"", "NOT_BASELINED", "DRAFT"}
        )
    return not effective_states or any(effective_states)


def validate_canonical_update_authorization(
    root: Path,
    *,
    label: str,
    event: dict[str, Any],
    roles_requiring_authorization: list[str],
    bindings_before: dict[str, dict[str, Any]],
    bindings_after: dict[str, dict[str, Any]],
    occurred_at: datetime | None,
) -> list[str]:
    if not roles_requiring_authorization:
        if event.get("canonical_update_authorization_binding") not in (
            None,
            "",
        ):
            return [f"{label}: unexpected canonical update authorization"]
        return []
    binding = event.get("canonical_update_authorization_binding")
    role = (
        binding.get("role")
        if isinstance(binding, dict)
        else None
    )
    expected_role = (
        f"CANONICAL_UPDATE_AUTHORIZATION::{event.get('event_id')}"
    )
    if (
        role != expected_role
        or set(binding) != {"role", "document_id", "path", "file_sha256"}
    ):
        return [f"{label}: canonical update authorization binding differs"]
    path = resolve_safe_repo_file(root, binding.get("path"))
    if path is None or binding.get("file_sha256") != sha256_file(path):
        return [f"{label}: canonical update authorization file differs"]
    errors = [
        f"{label}: {error}"
        for error in legacy.validate_external_attestation_anchor(
            expected_role,
            sha256_file(path),
        )
    ]
    try:
        receipt = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"{label}: canonical update authorization cannot be loaded: {exc}"
        ]
    approved_at = parse_iso_datetime(receipt.get("approved_at"))
    approver = receipt.get("approver")
    expected_before = {
        role_name: bindings_before.get(role_name)
        for role_name in roles_requiring_authorization
    }
    expected_after = {
        role_name: bindings_after.get(role_name)
        for role_name in roles_requiring_authorization
    }
    if (
        receipt.get("schema_version") != "1.0"
        or receipt.get("evidence_type")
        != "CANONICAL_BINDING_UPDATE_AUTHORIZATION"
        or receipt.get("document_id") != binding.get("document_id")
        or receipt.get("status") != "APPROVED"
        or receipt.get("event_id") != event.get("event_id")
        or receipt.get("changed_binding_roles")
        != roles_requiring_authorization
        or receipt.get("bindings_before") != expected_before
        or receipt.get("bindings_after") != expected_after
        or approved_at is None
        or occurred_at is None
        or approved_at > occurred_at
        or not isinstance(approver, dict)
        or approver.get("decided_at") != receipt.get("approved_at")
    ):
        errors.append(f"{label}: canonical update authorization semantics differ")
    errors.extend(
        f"{label}: {error}"
        for error in legacy.validate_receipt_authority_roster_binding(
            root,
            receipt,
            bindings_after,
        )
    )
    roster_errors, actors = legacy.load_authority_roster(
        root,
        bindings_after,
    )
    errors.extend(f"{label}: {error}" for error in roster_errors)
    errors.extend(
        f"{label}: {error}"
        for error in legacy.validate_receipt_actor(
            "CANONICAL_BINDING_UPDATE_AUTHORIZATION",
            "approver",
            approver,
            require_decision=True,
        )
    )
    errors.extend(
        f"{label}: {error}"
        for error in legacy.validate_actor_against_roster(
            receipt_role="CANONICAL_BINDING_UPDATE_AUTHORIZATION",
            actor_name="approver",
            actor=approver,
            actors_by_id=actors,
        )
    )
    return errors


def canonical_producer_output_subject_ids_by_role(
    root: Path,
    *,
    produced_roles: list[str],
    impact_subject_ids_by_role: dict[str, list[str]],
    bindings_before: dict[str, dict[str, Any]],
    bindings_after: dict[str, dict[str, Any]],
) -> tuple[list[str], dict[str, list[str]]]:
    errors: list[str] = []
    result = {
        role: list(impact_subject_ids_by_role.get(role, []))
        for role in produced_roles
    }
    for role in set(produced_roles) & TRACE_TRANSLATION_ROLES:
        before_errors, before = load_bound_json_payload(
            root,
            bindings_before.get(role),
            label=f"{role} producer before",
        )
        after_errors, after = load_bound_json_payload(
            root,
            bindings_after.get(role),
            label=f"{role} producer after",
        )
        errors.extend(before_errors)
        errors.extend(after_errors)
        if before_errors or after_errors:
            continue
        scoped_keys = SCOPED_CONTENT_KEYS_BY_ROLE.get(role, set())
        before_scoped = {key: before.get(key) for key in scoped_keys}
        after_scoped = {key: after.get(key) for key in scoped_keys}
        raw_subject_ids = changed_subject_ids(
            before_scoped,
            after_scoped,
        )
        if "*" in impact_subject_ids_by_role.get(role, []):
            raw_subject_ids.add("*")
        result[role] = sorted(raw_subject_ids)
    return errors, result


def validate_policy_gap_reassessment_result(
    *,
    producer_goal_id: str,
    producer_node: dict[str, Any],
    gap_before: dict[str, Any],
    gap_after: dict[str, Any],
) -> list[str]:
    owned_gap_ids = string_list(producer_node.get("gap_ids"))
    owned_policy_ids = string_list(producer_node.get("source_policy_ids"))
    if len(owned_gap_ids) != 1 or len(owned_policy_ids) != 1:
        return [
            f"{producer_goal_id}: policy/Gap ownership is not singular"
        ]
    gap_id = owned_gap_ids[0]
    policy_id = owned_policy_ids[0]
    before_rows = {
        row.get("gap_id"): row
        for row in gap_before.get("assessments", [])
        if isinstance(row, dict)
        and isinstance(row.get("gap_id"), str)
    }
    after_rows = {
        row.get("gap_id"): row
        for row in gap_after.get("assessments", [])
        if isinstance(row, dict)
        and isinstance(row.get("gap_id"), str)
    }
    row = after_rows.get(gap_id)
    errors: list[str] = []
    if (
        not isinstance(row, dict)
        or row.get("source_policy_id") != policy_id
        or before_rows.get(gap_id) == row
        or row.get("status")
        not in INTERNAL_REASSESSMENT_TERMINAL_GAP_STATUSES
        or not continuation.object_seal_is_valid(
            row,
            "assessment_sha256",
        )
        or not string_list(row.get("evidence_ids"))
        or not isinstance(row.get("rationale"), str)
        or not row.get("rationale")
        or not isinstance(row.get("current_implementation_in_plain_language"), str)
        or not row.get("current_implementation_in_plain_language")
        or not isinstance(row.get("remediation"), str)
        or not row.get("remediation")
        or not string_list(row.get("acceptance_criteria"))
    ):
        errors.append(
            f"{producer_goal_id}: owned Gap reassessment outcome/evidence differs"
        )
    scope = gap_after.get("reassessment_scope")
    if (
        not isinstance(scope, dict)
        or set(string_list(scope.get("directly_reassessed_gap_ids")))
        != {gap_id}
        or gap_id
        not in set(string_list(scope.get("reviewed_gap_ids")))
    ):
        errors.append(
            f"{producer_goal_id}: owned Gap is not the exact direct reassessment"
        )
    return errors


def gap_evidence_record_is_valid(
    root: Path,
    record: dict[str, Any],
) -> bool:
    if (
        not isinstance(record.get("evidence_id"), str)
        or not record.get("evidence_id")
        or not isinstance(record.get("kind"), str)
        or not record.get("kind")
        or not isinstance(record.get("claim"), str)
        or not record.get("claim")
    ):
        return False
    file_records = record.get("files")
    if isinstance(file_records, list) and file_records:
        for item in file_records:
            path = resolve_safe_repo_file(
                root,
                item.get("path") if isinstance(item, dict) else None,
            )
            expected_hash = (
                item.get("sha256")
                if isinstance(item, dict)
                else None
            )
            if (
                path is None
                or not isinstance(expected_hash, str)
                or not SHA256_PATTERN.fullmatch(expected_hash)
                or sha256_file(path) != expected_hash
            ):
                return False
        return True
    for path_key, hash_key in (
        ("source_report_path", "source_report_file_sha256"),
        ("path", "file_sha256"),
        ("path", "sha256"),
    ):
        if path_key not in record and hash_key not in record:
            continue
        path = resolve_safe_repo_file(root, record.get(path_key))
        expected_hash = record.get(hash_key)
        return bool(
            path is not None
            and isinstance(expected_hash, str)
            and SHA256_PATTERN.fullmatch(expected_hash)
            and sha256_file(path) == expected_hash
        )
    path = resolve_safe_repo_file(root, record.get("path"))
    content_hash_fields = [
        key
        for key in record
        if key.endswith("_content_sha256")
    ]
    if path is not None and len(content_hash_fields) == 1:
        try:
            payload = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError):
            return False
        field = content_hash_fields[0]
        return (
            record.get(field) == payload.get(field)
            and continuation.object_seal_is_valid(payload, field)
        )
    return False


def validate_policy_gap_evidence_catalog(
    root: Path,
    *,
    producer_goal_id: str,
    producer_node: dict[str, Any],
    gap_after: dict[str, Any],
    receipt: dict[str, Any],
) -> list[str]:
    owned_gap_ids = string_list(producer_node.get("gap_ids"))
    if len(owned_gap_ids) != 1:
        return [
            f"{producer_goal_id}: Gap evidence ownership is not singular"
        ]
    owned_row = next(
        (
            row
            for row in gap_after.get("assessments", [])
            if isinstance(row, dict)
            and row.get("gap_id") == owned_gap_ids[0]
        ),
        None,
    )
    catalog_rows = [
        row
        for row in gap_after.get("evidence_catalog", [])
        if isinstance(row, dict)
    ]
    catalog_by_id = {
        row.get("evidence_id"): row
        for row in catalog_rows
        if isinstance(row.get("evidence_id"), str)
    }
    evidence_ids = (
        string_list(owned_row.get("evidence_ids"))
        if isinstance(owned_row, dict)
        else []
    )
    errors: list[str] = []
    if (
        len(catalog_by_id) != len(catalog_rows)
        or not evidence_ids
        or any(
            evidence_id not in catalog_by_id
            or not gap_evidence_record_is_valid(
                root,
                catalog_by_id[evidence_id],
            )
            for evidence_id in evidence_ids
        )
    ):
        errors.append(
            f"{producer_goal_id}: Gap evidence IDs do not resolve to unique "
            "valid catalog records"
        )

    result_items = [
        item
        for item in receipt.get("result_evidence", [])
        if isinstance(item, dict)
        and item.get("kind") in POLICY_GAP_CATALOG_RESULT_KINDS
    ]
    result_hashes: dict[str, str] = {}
    implementation_paths: set[str] = set()
    verification_paths: set[str] = set()
    result_paths: set[str] = set()
    result_items_are_exact = len(result_items) == len(
        POLICY_GAP_CATALOG_RESULT_KINDS
    )
    for item in result_items:
        kind = str(item.get("kind"))
        result_path = resolve_safe_repo_file(root, item.get("path"))
        result_hash = item.get("sha256")
        if (
            kind in result_hashes
            or result_path is None
            or not isinstance(result_hash, str)
            or not SHA256_PATTERN.fullmatch(result_hash)
            or result_hash != sha256_file(result_path)
        ):
            result_items_are_exact = False
            continue
        try:
            payload = load_json(result_path)
        except (OSError, ValueError, json.JSONDecodeError):
            result_items_are_exact = False
            continue
        result_hashes[kind] = result_hash
        result_paths.add(str(item.get("path")))
        if kind == "IMPLEMENTATION_RECORD":
            implementation_paths.update(
                str(change.get("path"))
                for change in payload.get("changed_artifacts", [])
                if isinstance(change, dict)
                and isinstance(change.get("path"), str)
            )
        if kind == "VERIFICATION_RESULT":
            verification_paths.update(
                str(check.get("output_path"))
                for check in payload.get("checks", [])
                if isinstance(check, dict)
                and isinstance(check.get("output_path"), str)
            )
    if (
        not result_items_are_exact
        or set(result_hashes) != POLICY_GAP_CATALOG_RESULT_KINDS
    ):
        errors.append(
            f"{producer_goal_id}: Gap evidence producer result role set "
            "must contain exactly implementation and verification, "
            "with one valid item per role"
        )
    producer_records = [
        catalog_by_id[evidence_id]
        for evidence_id in evidence_ids
        if evidence_id in catalog_by_id
        and catalog_by_id[evidence_id].get("producer_goal_id")
        == producer_goal_id
    ]
    linked = False
    for record in producer_records:
        record_paths = {
            str(item.get("path"))
            for item in record.get("files", [])
            if isinstance(item, dict)
            and isinstance(item.get("path"), str)
        }
        for key in ("path", "source_report_path"):
            if isinstance(record.get(key), str):
                record_paths.add(str(record[key]))
        if (
            record.get("producer_completion_receipt_role")
            == f"WORK_ITEM_COMPLETION::{producer_goal_id}"
            and record.get("result_evidence_sha256_by_kind")
            == result_hashes
            and bool(
                record_paths
                & (
                    implementation_paths
                    | verification_paths
                    | result_paths
                )
            )
        ):
            linked = True
            break
    if not linked:
        errors.append(
            f"{producer_goal_id}: Gap evidence is not bound to this "
            "producer receipt and its implementation/verification results"
        )
    return errors


def bootstrap_consumed_policy_gap_pair_set(
    records: Any,
) -> set[tuple[str, str]]:
    if records != EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS:
        return set()
    return {
        (
            str(record["source_policy_id"]),
            str(record["gap_id"]),
        )
        for record in records
    }


def policy_pairs_completed_after_producer(
    *,
    nodes: dict[str, dict[str, Any]],
    statuses_before_update: dict[str, str],
    producer_goal_id: str,
) -> set[tuple[str, str]]:
    completed: set[tuple[str, str]] = set()
    for goal_id, node in nodes.items():
        if (
            node.get("goal_kind") != "WORK_ITEM"
            or node.get("work_item_type") != "POLICY_GAP_WORK"
            or statuses_before_update.get(goal_id) == "SUPERSEDED"
        ):
            continue
        policy_ids = string_list(node.get("source_policy_ids"))
        gap_ids = string_list(node.get("gap_ids"))
        if (
            len(policy_ids) == 1
            and len(gap_ids) == 1
            and (
                statuses_before_update.get(goal_id)
                == "COMPLETE_AT_TARGET"
                or goal_id == producer_goal_id
            )
        ):
            completed.add((policy_ids[0], gap_ids[0]))
    return completed


def validate_policy_backlog_progress_update(
    *,
    producer_goal_id: str,
    producer_node: dict[str, Any],
    backlog_before: dict[str, Any],
    backlog_after: dict[str, Any],
    gap_after: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    statuses_before_update: dict[str, str],
) -> list[str]:
    parent = nodes.get(str(producer_node.get("parent_goal_id")), {})
    parent_epic_id = parent.get("external_key")
    before_epics = {
        row.get("epic_id"): row
        for row in backlog_before.get("epics", [])
        if isinstance(row, dict)
        and isinstance(row.get("epic_id"), str)
    }
    after_epics = {
        row.get("epic_id"): row
        for row in backlog_after.get("epics", [])
        if isinstance(row, dict)
        and isinstance(row.get("epic_id"), str)
    }
    errors: list[str] = []
    if (
        not isinstance(parent_epic_id, str)
        or set(before_epics) != set(after_epics)
        or parent_epic_id not in after_epics
    ):
        return [
            f"{producer_goal_id}: parent Backlog EPIC binding differs"
        ]
    progress_fields = {"current_status", "current_status_reason"}
    changed_progress_epics = {
        epic_id
        for epic_id in after_epics
        if {
            field: before_epics[epic_id].get(field)
            for field in progress_fields
        }
        != {
            field: after_epics[epic_id].get(field)
            for field in progress_fields
        }
    }
    if not changed_progress_epics.issubset({parent_epic_id}):
        errors.append(
            f"{producer_goal_id}: unrelated Backlog EPIC progress was changed"
        )

    gap_by_policy = {
        row.get("source_policy_id"): row.get("gap_id")
        for row in gap_after.get("assessments", [])
        if isinstance(row, dict)
        and isinstance(row.get("source_policy_id"), str)
        and isinstance(row.get("gap_id"), str)
    }
    actual_completed_pairs = policy_pairs_completed_after_producer(
        nodes=nodes,
        statuses_before_update=statuses_before_update,
        producer_goal_id=producer_goal_id,
    )
    sequencing_consumed_pairs = (
        actual_completed_pairs
        | bootstrap_consumed_policy_gap_pair_set(
            EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS
        )
    )
    expected_parent_pairs = {
        (policy_id, gap_by_policy[policy_id])
        for policy_id in string_list(parent.get("source_policy_ids"))
        if policy_id in gap_by_policy
    }
    expected_status = (
        str(parent.get("target_completion_level"))
        if expected_parent_pairs
        and expected_parent_pairs.issubset(actual_completed_pairs)
        else "IN_PROGRESS"
    )
    parent_after = after_epics[parent_epic_id]
    if (
        parent_after.get("current_status") != expected_status
        or not isinstance(parent_after.get("current_status_reason"), str)
        or not parent_after.get("current_status_reason")
    ):
        errors.append(
            f"{producer_goal_id}: parent Backlog progress is not derived "
            "from completed policy/Gap Work Items"
        )

    expected_next_pair: tuple[str, str, str] | None = None
    workstream_by_epic = {
        node.get("external_key"): (goal_id, node)
        for goal_id, node in nodes.items()
        if node.get("goal_kind") == "WORKSTREAM"
        and isinstance(node.get("external_key"), str)
    }
    for epic_id in string_list(backlog_after.get("execution_order")):
        workstream_entry = workstream_by_epic.get(epic_id)
        if workstream_entry is None:
            continue
        workstream_id, workstream = workstream_entry
        if statuses_before_update.get(workstream_id) == "COMPLETE_AT_TARGET":
            continue
        ordered_policies = string_list(
            after_epics.get(epic_id, {}).get("ordered_source_policy_ids")
        )
        for policy_id in ordered_policies:
            gap_id = gap_by_policy.get(policy_id)
            if (
                isinstance(gap_id, str)
                and (policy_id, gap_id) not in sequencing_consumed_pairs
            ):
                expected_next_pair = (epic_id, policy_id, gap_id)
                break
        if expected_next_pair is not None:
            break
    next_action = backlog_after.get("next_single_action")
    if expected_next_pair is not None:
        expected_epic, expected_policy, expected_gap = expected_next_pair
        if (
            not isinstance(next_action, dict)
            or next_action.get("epic_id") != expected_epic
            or next_action.get("source_policy_id") != expected_policy
            or next_action.get("gap_id") != expected_gap
            or not isinstance(next_action.get("work_item_id"), str)
            or not next_action.get("work_item_id")
            or not isinstance(next_action.get("status"), str)
            or not next_action.get("status")
            or not isinstance(next_action.get("action"), str)
            or not next_action.get("action")
        ):
            errors.append(
                f"{producer_goal_id}: Backlog next action is not the first "
                "unfinished dependency-ordered policy/Gap pair"
            )
    elif next_action not in (None, {}):
        errors.append(
            f"{producer_goal_id}: Backlog next action must be empty when "
            "all dependency-ordered policy/Gap pairs are complete"
        )
    return errors


def validate_internal_canonical_producer(
    root: Path,
    *,
    label: str,
    event: dict[str, Any],
    producer_goal_id: str,
    producer_node: dict[str, Any],
    produced_roles: list[str],
    changed_subjects_by_role: dict[str, list[str]],
    bindings_before: dict[str, dict[str, Any]],
    bindings_after: dict[str, dict[str, Any]],
    paths_by_id: dict[str, str],
    nodes: dict[str, dict[str, Any]],
    statuses_before_update: dict[str, str],
    latest_start_event: dict[str, Any] | None,
    occurred_at: datetime | None,
) -> list[str]:
    errors: list[str] = []
    work_item_type = str(producer_node.get("work_item_type"))
    allowed_roles = INTERNAL_PRODUCER_OUTPUT_ROLES_BY_TYPE.get(
        work_item_type,
        set(),
    )
    if not set(produced_roles).issubset(allowed_roles):
        errors.append(f"{label}: producer output role is not allowed")
    if work_item_type == "POLICY_GAP_WORK" and set(produced_roles) != {
        "IMPLEMENTATION_GAP",
        "IMPLEMENTATION_BACKLOG",
    }:
        errors.append(
            f"{label}: POLICY_GAP_WORK must atomically produce the "
            "Gap/Backlog pair"
        )
    if work_item_type == "ARTIFACT_WORK" and not {
        "ARTIFACT_REGISTER",
        "ARTIFACT_CHANGE_LOG",
    }.issubset(produced_roles):
        errors.append(
            f"{label}: ARTIFACT_WORK must atomically update the artifact "
            "register and append its change log"
        )
    parent = nodes.get(str(producer_node.get("parent_goal_id")), {})
    allowed_subject_ids = set(
        string_list(parent.get("source_policy_ids"))
    ) | set(string_list(parent.get("gap_ids")))
    gap_binding = bindings_after.get("IMPLEMENTATION_GAP")
    gap_path = (
        resolve_safe_repo_file(root, gap_binding.get("path"))
        if isinstance(gap_binding, dict)
        else None
    )
    try:
        gap_payload = load_json(gap_path) if gap_path is not None else {}
    except (OSError, ValueError, json.JSONDecodeError):
        gap_payload = {}
    gap_rows = {
        row.get("gap_id"): row
        for row in gap_payload.get("assessments", [])
        if isinstance(row, dict) and isinstance(row.get("gap_id"), str)
    }
    gap_scope = gap_payload.get("reassessment_scope")
    direct_gap_ids = set(
        string_list(
            gap_scope.get("directly_reassessed_gap_ids")
            if isinstance(gap_scope, dict)
            else None
        )
    )
    reviewed_gap_ids = set(
        string_list(
            gap_scope.get("reviewed_gap_ids")
            if isinstance(gap_scope, dict)
            else None
        )
    )
    direct_subject_ids = set(direct_gap_ids)
    reviewed_subject_ids = set(reviewed_gap_ids)
    for gap_id in reviewed_gap_ids:
        policy_id = gap_rows.get(gap_id, {}).get("source_policy_id")
        if isinstance(policy_id, str):
            reviewed_subject_ids.add(policy_id)
            if gap_id in direct_gap_ids:
                direct_subject_ids.add(policy_id)
    producer_owned_subject_ids = set(
        string_list(producer_node.get("source_policy_ids"))
    ) | set(string_list(producer_node.get("gap_ids")))
    if work_item_type == "ARTIFACT_WORK":
        output_scope_errors, actual_output_scope = (
            canonical_producer_output_subject_ids_by_role(
                root,
                produced_roles=produced_roles,
                impact_subject_ids_by_role=changed_subjects_by_role,
                bindings_before=bindings_before,
                bindings_after=bindings_after,
            )
            if bindings_before and bindings_after
            else (
                [],
                {
                    role: list(changed_subjects_by_role.get(role, []))
                    for role in produced_roles
                },
            )
        )
        errors.extend(f"{label}: {error}" for error in output_scope_errors)
        declared_output_scope = producer_node.get(
            "output_subject_ids_by_role"
        )
        normalized_declared_scope = (
            {
                role: string_list(subject_ids)
                for role, subject_ids in declared_output_scope.items()
            }
            if isinstance(declared_output_scope, dict)
            else {}
        )
        if (
            not isinstance(declared_output_scope, dict)
            or set(normalized_declared_scope) != set(produced_roles)
            or any(
                declared_output_scope.get(role) != subject_ids
                or subject_ids != sorted(set(subject_ids))
                for role, subject_ids in normalized_declared_scope.items()
            )
            or event.get("producer_output_subject_ids_by_role")
            != actual_output_scope
            or normalized_declared_scope != actual_output_scope
        ):
            errors.append(
                f"{label}: ARTIFACT_WORK output exceeds its "
                "materialization-time subject scope"
            )
        if {
            "ARTIFACT_REGISTER",
            "ARTIFACT_CHANGE_LOG",
        }.issubset(produced_roles):
            register_scope = actual_output_scope.get(
                "ARTIFACT_REGISTER",
                [],
            )
            change_log_scope = actual_output_scope.get(
                "ARTIFACT_CHANGE_LOG",
                [],
            )
            if not register_scope or register_scope != change_log_scope:
                errors.append(
                    f"{label}: artifact register delta and appended change-log "
                    "scope must be the same non-empty artifact set"
                )
            register_delta_errors, _, sensitive_change, _ = (
                artifact_register_delta(
                    root,
                    bindings_before.get("ARTIFACT_REGISTER"),
                    bindings_after.get("ARTIFACT_REGISTER"),
                )
            )
            errors.extend(
                f"{label}: {error}" for error in register_delta_errors
            )
            external_authorization_required = (
                artifact_register_requires_external_authorization(
                    root,
                    bindings_before.get("ARTIFACT_REGISTER"),
                    bindings_after.get("ARTIFACT_REGISTER"),
                )
            )
            expected_delegation = (
                {
                    "authority_scope": (
                        "DELEGATED_INTERNAL_DOCUMENT_APPROVAL"
                    ),
                    "authority_source": (
                        "GRAPH_V2_STANDING_EXECUTION_AUTHORITY"
                    ),
                    "producer_goal_id": producer_goal_id,
                    "artifact_type_codes": register_scope,
                    "producer_completion_receipt_role": (
                        f"WORK_ITEM_COMPLETION::{producer_goal_id}"
                    ),
                }
                if sensitive_change
                and not external_authorization_required
                else None
            )
            if event.get("delegated_internal_approval") != expected_delegation:
                errors.append(
                    f"{label}: delegated internal approval declaration differs"
                )
            goal_path = resolve_safe_repo_file(
                root,
                paths_by_id.get(producer_goal_id),
            )
            expected_goal_binding = (
                {
                    "document_id": producer_goal_id,
                    "path": paths_by_id.get(producer_goal_id),
                    "file_sha256": sha256_file(goal_path),
                }
                if goal_path is not None
                else None
            )
            appended_rows = artifact_change_log_appended_rows(
                root,
                bindings_before.get("ARTIFACT_CHANGE_LOG"),
                bindings_after.get("ARTIFACT_CHANGE_LOG"),
            )
            expected_date = (
                occurred_at.date().isoformat()
                if occurred_at is not None
                else None
            )
            if (
                not appended_rows
                or any(
                    row.get("source_goal_binding")
                    != expected_goal_binding
                    or row.get("producer_receipt_role")
                    != f"WORK_ITEM_COMPLETION::{producer_goal_id}"
                    or row.get("date") != expected_date
                    for row in appended_rows
                )
            ):
                errors.append(
                    f"{label}: appended change-log row is not bound to the "
                    "producer Goal/date"
                )
    else:
        for role in produced_roles:
            changed_ids = set(changed_subjects_by_role.get(role, []))
            permitted_subject_ids = allowed_subject_ids
            if role in {"IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"}:
                permitted_subject_ids = (
                    allowed_subject_ids | reviewed_subject_ids
                )
            if (
                "*" in changed_ids
                or not changed_ids.issubset(permitted_subject_ids)
                or (
                    role == "IMPLEMENTATION_GAP"
                    and direct_subject_ids != producer_owned_subject_ids
                )
            ):
                errors.append(
                    f"{label}: producer output exceeds its Workstream subject scope"
                )
    if work_item_type == "POLICY_GAP_WORK":
        gap_before_errors, gap_before_payload = load_bound_json_payload(
            root,
            bindings_before.get("IMPLEMENTATION_GAP"),
            label=f"{label}: IMPLEMENTATION_GAP before",
        )
        backlog_before_errors, backlog_before_payload = (
            load_bound_json_payload(
                root,
                bindings_before.get("IMPLEMENTATION_BACKLOG"),
                label=f"{label}: IMPLEMENTATION_BACKLOG before",
            )
        )
        backlog_after_errors, backlog_after_payload = (
            load_bound_json_payload(
                root,
                bindings_after.get("IMPLEMENTATION_BACKLOG"),
                label=f"{label}: IMPLEMENTATION_BACKLOG after",
            )
        )
        errors.extend(gap_before_errors)
        errors.extend(backlog_before_errors)
        errors.extend(backlog_after_errors)
        if (
            not gap_before_errors
            and not backlog_before_errors
            and not backlog_after_errors
            and isinstance(gap_payload, dict)
        ):
            errors.extend(
                f"{label}: {error}"
                for error in validate_policy_gap_reassessment_result(
                    producer_goal_id=producer_goal_id,
                    producer_node=producer_node,
                    gap_before=gap_before_payload,
                    gap_after=gap_payload,
                )
            )
            errors.extend(
                f"{label}: {error}"
                for error in validate_policy_backlog_progress_update(
                    producer_goal_id=producer_goal_id,
                    producer_node=producer_node,
                    backlog_before=backlog_before_payload,
                    backlog_after=backlog_after_payload,
                    gap_after=gap_payload,
                    nodes=nodes,
                    statuses_before_update=statuses_before_update,
                )
            )
    completion_role = f"WORK_ITEM_COMPLETION::{producer_goal_id}"
    completion_binding = bindings_after.get(completion_role)
    if event.get("producer_completion_receipt_binding") != completion_binding:
        errors.append(f"{label}: producer completion receipt binding differs")
    if not isinstance(completion_binding, dict):
        return errors
    errors.extend(
        f"{label}: {error}"
        for error in legacy.validate_work_item_completion_receipt(
            label=f"{label}: producer",
            goal_id=producer_goal_id,
            references=[completion_role],
            root=root,
            bindings=bindings_after,
            node=producer_node,
            goal_path=paths_by_id.get(producer_goal_id, ""),
        )
    )
    path = resolve_safe_repo_file(root, completion_binding.get("path"))
    if path is None:
        return errors
    try:
        receipt = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError):
        return errors
    if work_item_type == "POLICY_GAP_WORK":
        errors.extend(
            f"{label}: {error}"
            for error in validate_policy_gap_evidence_catalog(
                root,
                producer_goal_id=producer_goal_id,
                producer_node=producer_node,
                gap_after=gap_payload,
                receipt=receipt,
            )
        )
    if work_item_type == "ARTIFACT_WORK":
        implementation_item = next(
            (
                item
                for item in receipt.get("result_evidence", [])
                if isinstance(item, dict)
                and item.get("kind") == "IMPLEMENTATION_RECORD"
            ),
            None,
        )
        implementation_path = resolve_safe_repo_file(
            root,
            implementation_item.get("path")
            if isinstance(implementation_item, dict)
            else None,
        )
        try:
            implementation = (
                load_json(implementation_path)
                if implementation_path is not None
                and implementation_item.get("sha256")
                == sha256_file(implementation_path)
                else {}
            )
        except (OSError, ValueError, json.JSONDecodeError):
            implementation = {}
        errors.extend(
            f"{label}: {error}"
            for error in validate_artifact_change_log_implementation_binding(
                appended_rows=artifact_change_log_appended_rows(
                    root,
                    bindings_before.get("ARTIFACT_CHANGE_LOG"),
                    bindings_after.get("ARTIFACT_CHANGE_LOG"),
                ),
                implementation=implementation,
            )
        )
    generated_at = parse_iso_datetime(receipt.get("generated_at"))
    start_hash = (
        latest_start_event.get("event_sha256")
        if isinstance(latest_start_event, dict)
        else None
    )
    if (
        receipt.get("execution_start_event_sha256") != start_hash
        or generated_at is None
        or occurred_at is None
        or generated_at > occurred_at
    ):
        errors.append(f"{label}: producer receipt chronology/binding differs")
    return errors


def validate_nonretroactive_ledger_update(
    root: Path,
    *,
    label: str,
    role: str,
    binding_before: dict[str, Any] | None,
    binding_after: dict[str, Any] | None,
) -> list[str]:
    if role not in NON_RETROACTIVE_LEDGER_ROLES:
        return []
    payloads: list[dict[str, Any]] = []
    for side, binding in (("before", binding_before), ("after", binding_after)):
        if not isinstance(binding, dict):
            return [f"{label}: {role} {side} binding is missing"]
        path = resolve_safe_repo_file(root, binding.get("path"))
        if path is None or binding.get("file_sha256") != sha256_file(path):
            return [f"{label}: {role} {side} binding differs"]
        try:
            payloads.append(load_json(path))
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            return [f"{label}: {role} {side} ledger cannot be loaded: {exc}"]
    before_payload, after_payload = payloads
    if role == "ARTIFACT_CHANGE_LOG":
        before_changes = before_payload.get("changes")
        after_changes = after_payload.get("changes")
        if (
            not isinstance(before_changes, list)
            or not isinstance(after_changes, list)
            or after_changes[: len(before_changes)] != before_changes
        ):
            return [f"{label}: artifact change log is not append-only"]
        return []
    delta_errors, _, _, _ = artifact_register_delta(
        root,
        binding_before,
        binding_after,
    )
    if delta_errors:
        return [f"{label}: {error}" for error in delta_errors]
    before_rows = before_payload.get("artifacts")
    after_rows = after_payload.get("artifacts")
    if not isinstance(before_rows, list) or not isinstance(after_rows, list):
        return [f"{label}: artifact register rows are missing"]
    before_identity = {
        row.get("artifact_type_code"): row.get("artifact_instance_id")
        for row in before_rows
        if isinstance(row, dict)
    }
    after_identity = {
        row.get("artifact_type_code"): row.get("artifact_instance_id")
        for row in after_rows
        if isinstance(row, dict)
    }
    if (
        len(before_identity) != len(before_rows)
        or len(after_identity) != len(after_rows)
        or not set(before_identity).issubset(after_identity)
        or any(
            after_identity.get(code) != instance_id
            for code, instance_id in before_identity.items()
        )
    ):
        return [f"{label}: artifact register identity history was rewritten"]
    return []


def validate_authority_roster_rotation(
    root: Path,
    *,
    label: str,
    binding_before: dict[str, Any] | None,
    binding_after: dict[str, Any] | None,
    occurred_at: datetime | None,
) -> list[str]:
    if not isinstance(binding_after, dict):
        return [f"{label}: authority roster rotation binding is missing"]
    before_path = (
        resolve_safe_repo_file(root, binding_before.get("path"))
        if isinstance(binding_before, dict)
        else None
    )
    after_path = resolve_safe_repo_file(root, binding_after.get("path"))
    if after_path is None:
        return [f"{label}: authority roster rotation path is missing"]
    before_hash = sha256_file(before_path) if before_path is not None else ""
    after_hash = sha256_file(after_path)
    errors: list[str] = []
    current_anchor = legacy.EXPECTED_AUTHORITY_ROSTER_SHA256
    admissible_anchors = {
        anchor
        for anchor in (
            current_anchor,
            *legacy.EXPECTED_AUTHORITY_ROSTER_SHA256_HISTORY,
        )
        if isinstance(anchor, str) and SHA256_PATTERN.fullmatch(anchor)
    }
    if (
        (
            isinstance(binding_before, dict)
            and binding_before.get("file_sha256") != before_hash
        )
        or binding_after.get("file_sha256") != after_hash
        or after_hash not in admissible_anchors
        or before_hash == after_hash
    ):
        errors.append(f"{label}: authority roster rotation anchor differs")
    try:
        after_roster = load_json(after_path)
        before_roster = load_json(before_path) if before_path is not None else {}
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"{label}: authority roster rotation cannot be loaded: {exc}"
        ]
    after_sequence = after_roster.get("sequence")
    after_effective = parse_iso_datetime(after_roster.get("effective_at"))
    if binding_before is None:
        lineage_valid = (
            after_sequence == 1
            and after_roster.get("supersedes_authority_roster")
            in (None, "")
            and after_effective is not None
            and occurred_at is not None
            and after_effective <= occurred_at
        )
    else:
        before_sequence = before_roster.get("sequence")
        before_effective = parse_iso_datetime(
            before_roster.get("effective_at")
            or before_roster.get("approved_at")
        )
        lineage_valid = (
            isinstance(before_sequence, int)
            and not isinstance(before_sequence, bool)
            and after_sequence == before_sequence + 1
            and after_roster.get("supersedes_authority_roster")
            == {
                "document_id": binding_before.get("document_id"),
                "file_sha256": before_hash,
            }
            and before_effective is not None
            and after_effective is not None
            and occurred_at is not None
            and before_effective < after_effective <= occurred_at
        )
    if not lineage_valid:
        errors.append(f"{label}: authority roster successor lineage differs")
    return errors


def load_direct_event_receipt(
    root: Path,
    binding: Any,
    *,
    label: str,
) -> tuple[list[str], dict[str, Any], str | None]:
    if (
        not isinstance(binding, dict)
        or set(binding) != {
            "document_id",
            "path",
            "file_sha256",
        }
    ):
        return [f"{label} binding differs"], {}, None
    path = resolve_safe_repo_file(root, binding.get("path"))
    if path is None:
        return [f"{label} file differs"], {}, None
    try:
        raw = path.read_bytes()
        actual_sha256 = hashlib.sha256(raw).hexdigest()
        if binding.get("file_sha256") != actual_sha256:
            return [f"{label} file differs"], {}, None
        depth = 0
        in_string = False
        escaped = False
        for byte in raw:
            if in_string:
                if escaped:
                    escaped = False
                elif byte == 92:
                    escaped = True
                elif byte == 34:
                    in_string = False
            elif byte == 34:
                in_string = True
            elif byte in (123, 91):
                depth += 1
                if depth > DIRECT_EVENT_RECEIPT_MAX_JSON_NESTING:
                    raise ValueError(
                        "JSON nesting exceeds direct receipt limit"
                    )
            elif byte in (125, 93):
                depth -= 1
        payload = json.loads(raw.decode("utf-8", errors="strict"))
    except (
        OSError,
        RuntimeError,
        UnicodeDecodeError,
        ValueError,
        json.JSONDecodeError,
        RecursionError,
    ) as exc:
        return [f"{label} cannot be loaded: {exc}"], {}, None
    if not isinstance(payload, dict):
        return [
            f"{label} root must be an object"
        ], {}, actual_sha256
    if payload.get("document_id") != binding.get("document_id"):
        return [f"{label} document ID differs"], payload, actual_sha256
    return [], payload, actual_sha256


def validate_external_action_packet(
    root: Path,
    *,
    label: str,
    event: dict[str, Any],
    blockers_after: dict[str, list[dict[str, Any]]],
    expected_boundary: dict[str, Any],
    occurred_at: datetime | None,
    previous_occurred_at: datetime | None,
    used_packet_paths: set[str],
    used_packet_document_ids: set[str],
) -> list[str]:
    binding = event.get("external_action_packet_binding")
    load_errors, packet, _ = load_direct_event_receipt(
        root,
        binding,
        label=f"{label}: external action packet",
    )
    errors = list(load_errors)
    event_id = str(event.get("event_id", ""))
    expected_path = (
        f"docs/control/execution/goal-gates/{event_id}/"
        "external-action-packet.json"
    )
    binding_path = (
        binding.get("path") if isinstance(binding, dict) else None
    )
    binding_document_id = (
        binding.get("document_id")
        if isinstance(binding, dict)
        else None
    )
    if binding_path != expected_path:
        errors.append(
            f"{label}: external action packet path is not event-scoped"
        )
    elif binding_path in used_packet_paths:
        errors.append(
            f"{label}: external action packet path is reused"
        )
    else:
        used_packet_paths.add(binding_path)
    if (
        not isinstance(binding_document_id, str)
        or not binding_document_id.strip()
    ):
        errors.append(
            f"{label}: external action packet document ID is missing"
        )
    elif binding_document_id in used_packet_document_ids:
        errors.append(
            f"{label}: external action packet document ID is reused"
        )
    else:
        used_packet_document_ids.add(binding_document_id)
    basis = external_action_request_basis(blockers_after)
    basis_hash = canonical_json_sha256(basis)
    issued_at = parse_iso_datetime(packet.get("issued_at"))
    request_created_times = [
        parse_iso_datetime(record.get("created_at"))
        for records in blockers_after.values()
        if isinstance(records, list)
        for record in records
        if isinstance(record, dict) and record.get("owner") == "EXTERNAL"
    ]
    required_fields = {
        "schema_version",
        "document_id",
        "evidence_type",
        "status",
        "package_id",
        "static_plan_manifest_sha256",
        "target_transition_event_id",
        "previous_transition_event_sha256",
        "request_basis",
        "request_basis_sha256",
        "issued_at",
    }
    if (
        set(packet) != required_fields
        or packet.get("schema_version") != "1.0"
        or packet.get("document_id")
        != expected_boundary.get("external_action_packet_id")
        or packet.get("evidence_type") != "EXTERNAL_ACTION_PACKET"
        or packet.get("status") != "ISSUED_NOT_EVIDENCE"
        or packet.get("package_id") != EXPECTED_PACKAGE_ID
        or packet.get("static_plan_manifest_sha256")
        != event.get("static_plan_manifest_sha256")
        or packet.get("target_transition_event_id")
        != event.get("event_id")
        or packet.get("previous_transition_event_sha256")
        != event.get("previous_event_sha256")
        or packet.get("request_basis") != basis
        or packet.get("request_basis_sha256") != basis_hash
        or expected_boundary.get(
            "external_action_request_basis_sha256"
        )
        != basis_hash
        or issued_at is None
        or occurred_at is None
        or (
            previous_occurred_at is not None
            and issued_at is not None
            and issued_at < previous_occurred_at
        )
        or issued_at > occurred_at
        or any(
            created_at is None or created_at > issued_at
            for created_at in request_created_times
        )
    ):
        errors.append(
            f"{label}: external action packet semantics differ"
        )
    return errors


def binding_is_external_action_packet(
    root: Path,
    binding: Any,
) -> bool:
    if not isinstance(binding, dict):
        return False
    path = resolve_safe_repo_file(root, binding.get("path"))
    if (
        path is None
        or binding.get("file_sha256") != sha256_file(path)
    ):
        return False
    try:
        payload = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError):
        return False
    return payload.get("evidence_type") == "EXTERNAL_ACTION_PACKET"


def completion_references_include_external_action_packet(
    root: Path,
    references: list[str],
    bindings: dict[str, dict[str, Any]],
    direct_packet_document_ids: set[str] | None = None,
) -> bool:
    return bool(
        set(references) & (direct_packet_document_ids or set())
    ) or any(
        binding_is_external_action_packet(
            root,
            bindings.get(reference),
        )
        for reference in references
    )


def completion_reference_errors(
    root: Path,
    *,
    label: str,
    references: list[str],
    bindings: dict[str, dict[str, Any]],
    direct_packet_document_ids: set[str],
) -> list[str]:
    errors: list[str] = []
    unresolved = set(references) - set(bindings)
    if unresolved:
        errors.append(
            f"{label}: completion evidence references must all resolve to "
            f"canonical bindings: {sorted(unresolved)}"
        )
    if completion_references_include_external_action_packet(
        root,
        references,
        bindings,
        direct_packet_document_ids,
    ):
        errors.append(
            f"{label}: external action packet cannot be used as completion "
            "evidence"
        )
    return errors


def validate_internal_check_runs(
    root: Path,
    *,
    label: str,
    check_runs: Any,
    expected_checks: tuple[tuple[str, str], ...],
    event_id: str,
    used_output_paths: set[str],
    started_at: datetime | None,
    ended_at: datetime | None,
) -> list[str]:
    if not isinstance(check_runs, list):
        return [f"{label}: check run list is missing"]
    errors: list[str] = []
    expected_ids = [check_id for check_id, _ in expected_checks]
    actual_ids = [
        item.get("check_id") if isinstance(item, dict) else None
        for item in check_runs
    ]
    if actual_ids != expected_ids:
        errors.append(f"{label}: check ID order differs")
    if not SAFE_ID_PATTERN.fullmatch(event_id):
        errors.append(f"{label}: event ID is unsafe for evidence paths")
    previous_executed_at: datetime | None = None
    receipt_output_paths: set[str] = set()
    for index, (check_id, expected_command) in enumerate(
        expected_checks,
        start=1,
    ):
        item = check_runs[index - 1] if index <= len(check_runs) else {}
        if not isinstance(item, dict):
            item = {}
        relative_output = item.get("output_path")
        expected_output = (
            f"docs/control/execution/goal-gates/{event_id}/"
            f"{index:02d}-{check_id}.log"
        )
        output_path = resolve_safe_repo_file(root, relative_output)
        executed_at = parse_iso_datetime(item.get("executed_at"))
        if (
            set(item)
            != {
                "check_id",
                "command",
                "executed_at",
                "exit_code",
                "output_path",
                "output_sha256",
            }
            or item.get("check_id") != check_id
            or item.get("command") != expected_command
            or item.get("exit_code") != 0
            or relative_output != expected_output
            or output_path is None
            or output_path.stat().st_size <= 0
            or item.get("output_sha256") != sha256_file(output_path)
            or started_at is None
            or ended_at is None
            or executed_at is None
            or not started_at <= executed_at <= ended_at
        ):
            errors.append(f"{label}: check result differs: {check_id}")
        if (
            executed_at is not None
            and previous_executed_at is not None
            and executed_at <= previous_executed_at
        ):
            errors.append(
                f"{label}: check execution times are not strictly increasing: "
                f"{check_id}"
            )
        if executed_at is not None:
            previous_executed_at = executed_at
        if isinstance(relative_output, str):
            if (
                relative_output in receipt_output_paths
                or relative_output in used_output_paths
            ):
                errors.append(
                    f"{label}: check output path is reused: {relative_output}"
                )
            receipt_output_paths.add(relative_output)
            used_output_paths.add(relative_output)
    if len(check_runs) != len(expected_checks):
        errors.append(f"{label}: check run count differs")
    return errors


def _gate_nonnegative_integer(value: Any) -> bool:
    return type(value) is int and value >= 0


def _gate_git_object_id_is_valid(
    value: Any,
    object_format: str,
) -> bool:
    length = 40 if object_format == "sha1" else 64
    return bool(
        isinstance(value, str)
        and re.fullmatch(rf"[0-9a-f]{{{length}}}", value)
    )


def _gate_git_mode_is_valid(value: Any) -> bool:
    return bool(
        isinstance(value, str)
        and re.fullmatch(r"[0-7]{6}", value)
        and value != "160000"
    )


def _gate_git_path_is_valid(value: Any) -> bool:
    if not isinstance(value, str) or not value or value.startswith("/"):
        return False
    try:
        value.encode("utf-8", errors="strict")
    except UnicodeEncodeError:
        return False
    parts = value.split("/")
    return bool(
        all(part not in {"", ".", ".."} for part in parts)
        and not any(
            ord(character) < 0x20 or ord(character) == 0x7F
            for character in value
        )
    )


def _gate_status_record_errors(
    value: Any,
    *,
    object_format: str,
) -> list[str]:
    if not isinstance(value, dict):
        return ["status must be an object"]
    kind = value.get("kind")
    ordinary_fields = {
        "kind",
        "xy",
        "index_code",
        "worktree_code",
        "submodule",
        "head_mode",
        "index_mode",
        "worktree_mode",
        "head_object_id",
        "index_object_id",
        "rename_or_copy_score",
    }
    unmerged_fields = {
        "kind",
        "xy",
        "index_code",
        "worktree_code",
        "submodule",
        "stage1_mode",
        "stage2_mode",
        "stage3_mode",
        "worktree_mode",
        "stage1_object_id",
        "stage2_object_id",
        "stage3_object_id",
        "rename_or_copy_score",
    }
    errors: list[str] = []
    if isinstance(kind, str) and kind in (
        "ORDINARY",
        "RENAMED_OR_COPIED",
        "UNTRACKED",
    ):
        if set(value) != ordinary_fields:
            return ["status fields differ"]
    elif kind == "UNMERGED":
        if set(value) != unmerged_fields:
            return ["status fields differ"]
    else:
        return ["status kind differs"]

    xy = value.get("xy")
    if (
        not isinstance(xy, str)
        or len(xy) != 2
        or value.get("index_code") != xy[0]
        or value.get("worktree_code") != xy[1]
    ):
        errors.append("status XY fields differ")
    if kind == "UNTRACKED":
        expected = {
            "xy": "??",
            "index_code": "?",
            "worktree_code": "?",
            "submodule": None,
            "head_mode": None,
            "index_mode": None,
            "worktree_mode": None,
            "head_object_id": None,
            "index_object_id": None,
            "rename_or_copy_score": None,
        }
        if any(value.get(field) != expected_value for field, expected_value in expected.items()):
            errors.append("untracked status semantics differ")
        return errors
    if (
        not isinstance(xy, str)
        or len(xy) != 2
        or any(code not in ".MADRCUT" for code in xy)
        or xy == ".."
        or value.get("submodule") != "N..."
    ):
        errors.append("tracked status semantics differ")
    if kind in ("ORDINARY", "RENAMED_OR_COPIED"):
        for field in ("head_mode", "index_mode", "worktree_mode"):
            if not _gate_git_mode_is_valid(value.get(field)):
                errors.append(f"status {field} is invalid")
        for field in ("head_object_id", "index_object_id"):
            if not _gate_git_object_id_is_valid(
                value.get(field),
                object_format,
            ):
                errors.append(f"status {field} is invalid")
        score = value.get("rename_or_copy_score")
        if kind == "ORDINARY" and score is not None:
            errors.append("ordinary status rename/copy score differs")
        if kind == "RENAMED_OR_COPIED":
            match = (
                re.fullmatch(r"([RC])([0-9]{1,3})", score)
                if isinstance(score, str)
                else None
            )
            if match is None or int(match.group(2)) > 100:
                errors.append("rename/copy status score is invalid")
    else:
        for field in (
            "stage1_mode",
            "stage2_mode",
            "stage3_mode",
            "worktree_mode",
        ):
            if not _gate_git_mode_is_valid(value.get(field)):
                errors.append(f"status {field} is invalid")
        for field in (
            "stage1_object_id",
            "stage2_object_id",
            "stage3_object_id",
        ):
            if not _gate_git_object_id_is_valid(
                value.get(field),
                object_format,
            ):
                errors.append(f"status {field} is invalid")
        if value.get("rename_or_copy_score") is not None:
            errors.append("unmerged status rename/copy score differs")
    return errors


def _gate_index_entry_list_errors(
    value: Any,
    *,
    object_format: str,
) -> list[str]:
    if not isinstance(value, list):
        return ["index_entries must be a list"]
    errors: list[str] = []
    normalized: list[tuple[int, str, str]] = []
    stages: list[int] = []
    for index, entry in enumerate(value):
        if (
            not isinstance(entry, dict)
            or set(entry) != {"mode", "object_id", "stage"}
            or not _gate_git_mode_is_valid(entry.get("mode"))
            or not _gate_git_object_id_is_valid(
                entry.get("object_id"),
                object_format,
            )
            or type(entry.get("stage")) is not int
            or entry["stage"] not in {0, 1, 2, 3}
        ):
            errors.append(f"index entry {index} differs")
            continue
        stages.append(entry["stage"])
        normalized.append(
            (entry["stage"], entry["mode"], entry["object_id"])
        )
    if normalized != sorted(normalized):
        errors.append("index entries are not canonical")
    if len(stages) != len(set(stages)):
        errors.append("index stages are duplicated")
    if 0 in stages and len(stages) != 1:
        errors.append("stage-zero and unmerged index entries are mixed")
    return errors


def _gate_worktree_identity_errors(value: Any) -> list[str]:
    fields = {
        "state",
        "type",
        "mode",
        "byte_count",
        "sha256",
        "deletion_marker",
        "symlink_target_sha256",
    }
    if not isinstance(value, dict) or set(value) != fields:
        return ["worktree identity fields differ"]
    if value.get("state") == "ABSENT":
        if (
            value
            != {
                "state": "ABSENT",
                "type": "DELETION_MARKER",
                "mode": "000000",
                "byte_count": 0,
                "sha256": None,
                "deletion_marker": "WORKTREE_PATH_ABSENT",
                "symlink_target_sha256": None,
            }
            or type(value.get("byte_count")) is not int
        ):
            return ["absent worktree identity semantics differ"]
        return []
    if (
        value.get("state") != "PRESENT"
        or not _gate_nonnegative_integer(value.get("byte_count"))
        or not isinstance(value.get("sha256"), str)
        or not SHA256_PATTERN.fullmatch(value["sha256"])
        or value.get("deletion_marker") is not None
    ):
        return ["present worktree identity semantics differ"]
    if value.get("type") == "REGULAR_FILE":
        if (
            not isinstance(value.get("mode"), str)
            or re.fullmatch(r"10[0-7]{4}", value["mode"]) is None
            or value.get("symlink_target_sha256") is not None
        ):
            return ["regular-file worktree identity semantics differ"]
        return []
    if value.get("type") == "SYMLINK":
        if (
            value.get("mode") != "120777"
            or value.get("symlink_target_sha256") != value.get("sha256")
        ):
            return ["symlink worktree identity semantics differ"]
        return []
    return ["worktree identity type differs"]


def _gate_path_cross_field_errors(
    entry: dict[str, Any],
    *,
    object_format: str,
) -> list[str]:
    status = entry.get("status")
    index_entries = entry.get("index_entries")
    worktree = entry.get("worktree")
    role = entry.get("path_role")
    if (
        not isinstance(status, dict)
        or not isinstance(index_entries, list)
        or not isinstance(worktree, dict)
    ):
        return []
    kind = status.get("kind")
    errors: list[str] = []
    if kind == "UNTRACKED":
        if (
            role != "CURRENT"
            or index_entries
            or worktree.get("state") != "PRESENT"
        ):
            errors.append(
                "untracked status/index/worktree semantics differ"
            )
        return errors

    if role == "RENAME_SOURCE":
        if index_entries or worktree.get("state") != "ABSENT":
            errors.append("rename source index/worktree semantics differ")
        return errors
    if role == "COPY_SOURCE":
        if (
            worktree.get("state") != "PRESENT"
            or len(index_entries) != 1
            or not isinstance(index_entries[0], dict)
            or index_entries[0].get("stage") != 0
        ):
            errors.append("copy source index/worktree semantics differ")
        return errors

    worktree_mode = status.get("worktree_mode")
    expected_worktree_state = (
        "ABSENT" if worktree_mode == "000000" else "PRESENT"
    )
    if worktree.get("state") != expected_worktree_state:
        errors.append("status/worktree presence semantics differ")

    if kind in ("ORDINARY", "RENAMED_OR_COPIED"):
        if status.get("index_mode") != "000000":
            expected_index_entries = [
                {
                    "mode": status.get("index_mode"),
                    "object_id": status.get("index_object_id"),
                    "stage": 0,
                }
            ]
        elif (
            kind == "ORDINARY"
            and status.get("xy") == ".A"
            and status.get("head_mode") == "000000"
        ):
            empty_blob = hashlib.new(
                object_format,
                b"blob 0\0",
            ).hexdigest()
            expected_index_entries = [
                {
                    "mode": status.get("worktree_mode"),
                    "object_id": empty_blob,
                    "stage": 0,
                }
            ]
        else:
            expected_index_entries = []
        if index_entries != expected_index_entries:
            errors.append("status/index stage-zero identity differs")
    elif kind == "UNMERGED":
        expected_index_entries = [
            {
                "mode": status.get(f"stage{stage}_mode"),
                "object_id": status.get(f"stage{stage}_object_id"),
                "stage": stage,
            }
            for stage in (1, 2, 3)
            if status.get(f"stage{stage}_mode") != "000000"
        ]
        if index_entries != expected_index_entries:
            errors.append("unmerged status/index identities differ")
    return errors


def validate_gate_repository_state_payload(
    value: Any,
    *,
    expected_event_id: str,
) -> list[str]:
    errors: list[str] = []
    top_level_fields = {
        "schema_version",
        "evidence_type",
        "gate_event_id",
        "canonicalization",
        "repository",
        "git_status_raw",
        "transaction_exclusions",
        "dirty_snapshot",
        "checkpoint_controlled_working_snapshot",
    }
    if not isinstance(value, dict) or set(value) != top_level_fields:
        return ["gate repository state top-level fields differ"]
    event_id_is_valid = bool(
        isinstance(expected_event_id, str)
        and re.fullmatch(
            r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}",
            expected_event_id,
        )
        and expected_event_id not in (".", "..")
    )
    if (
        value.get("schema_version")
        != GATE_REPOSITORY_STATE_SCHEMA_VERSION
        or value.get("evidence_type")
        != GATE_REPOSITORY_STATE_EVIDENCE_TYPE
        or value.get("gate_event_id") != expected_event_id
        or not event_id_is_valid
    ):
        errors.append("gate repository state identity differs")
    canonicalization = value.get("canonicalization")
    if (
        canonicalization != GATE_REPOSITORY_STATE_CANONICALIZATION
        or not isinstance(canonicalization, dict)
        or type(
            canonicalization.get("trailing_newline_in_cli_output")
        )
        is not bool
    ):
        errors.append("gate repository state canonicalization differs")

    repository = value.get("repository")
    object_format = (
        repository.get("object_format")
        if isinstance(repository, dict)
        else None
    )
    if (
        not isinstance(repository, dict)
        or set(repository) != {
            "root",
            "head_commit",
            "branch",
            "object_format",
        }
        or repository.get("root") != "."
        or object_format not in ("sha1", "sha256")
        or not _gate_git_object_id_is_valid(
            repository.get("head_commit"),
            str(object_format),
        )
        or (
            repository.get("branch") is not None
            and not _gate_git_path_is_valid(repository.get("branch"))
        )
    ):
        errors.append("gate repository state repository identity differs")
    if object_format not in ("sha1", "sha256"):
        object_format = "sha1"

    git_status_raw = value.get("git_status_raw")
    if (
        not isinstance(git_status_raw, dict)
        or set(git_status_raw)
        != {
            "command",
            "config_overrides",
            "scope",
            "sha256",
            "byte_count",
            "record_count",
        }
        or git_status_raw.get("command")
        != GATE_REPOSITORY_STATE_GIT_STATUS_COMMAND
        or git_status_raw.get("config_overrides")
        != GATE_REPOSITORY_STATE_GIT_STATUS_CONFIG
        or git_status_raw.get("scope")
        != "AFTER_EXACT_TRANSACTION_EXCLUSIONS"
        or not isinstance(git_status_raw.get("sha256"), str)
        or not SHA256_PATTERN.fullmatch(git_status_raw["sha256"])
        or not _gate_nonnegative_integer(git_status_raw.get("byte_count"))
        or not _gate_nonnegative_integer(git_status_raw.get("record_count"))
    ):
        errors.append("gate repository state raw Git status differs")

    gate_prefix = (
        f"docs/control/execution/goal-gates/{expected_event_id}/"
        if isinstance(expected_event_id, str)
        else ""
    )
    exclusions = value.get("transaction_exclusions")
    if (
        exclusions
        != {
            "allowed_rule_count": 2,
            "checkpoint_exact_path": GATE_REPOSITORY_CHECKPOINT_PATH,
            "gate_event_exact_prefix": gate_prefix,
        }
        or not isinstance(exclusions, dict)
        or type(exclusions.get("allowed_rule_count")) is not int
    ):
        errors.append("gate repository state transaction exclusions differ")

    checkpoint_summary = value.get(
        "checkpoint_controlled_working_snapshot"
    )
    if (
        not isinstance(checkpoint_summary, dict)
        or set(checkpoint_summary)
        != {
            "base_head",
            "managed_changed_path_count",
            "path_set_sha256",
            "content_set_sha256",
        }
        or not _gate_git_object_id_is_valid(
            checkpoint_summary.get("base_head"),
            object_format,
        )
        or not _gate_nonnegative_integer(
            checkpoint_summary.get("managed_changed_path_count")
        )
        or not isinstance(
            checkpoint_summary.get("path_set_sha256"),
            str,
        )
        or not SHA256_PATTERN.fullmatch(
            checkpoint_summary["path_set_sha256"]
        )
        or not isinstance(
            checkpoint_summary.get("content_set_sha256"),
            str,
        )
        or not SHA256_PATTERN.fullmatch(
            checkpoint_summary["content_set_sha256"]
        )
    ):
        errors.append(
            "gate repository state checkpoint controlled summary differs"
        )

    dirty = value.get("dirty_snapshot")
    if (
        not isinstance(dirty, dict)
        or set(dirty)
        != {
            "dirty_path_count",
            "path_set_sha256",
            "content_set_sha256",
            "index_state_sha256",
            "paths",
        }
        or not _gate_nonnegative_integer(dirty.get("dirty_path_count"))
        or not isinstance(dirty.get("paths"), list)
        or any(
            not isinstance(dirty.get(field), str)
            or not SHA256_PATTERN.fullmatch(dirty[field])
            for field in (
                "path_set_sha256",
                "content_set_sha256",
                "index_state_sha256",
            )
        )
    ):
        errors.append("gate repository state dirty snapshot fields differ")
        return errors

    paths = dirty["paths"]
    path_names: list[str] = []
    entries_by_path: dict[str, dict[str, Any]] = {}
    represented_record_count = 0
    for index, entry in enumerate(paths):
        entry_label = f"gate repository state path row {index}"
        if (
            not isinstance(entry, dict)
            or set(entry)
            != {
                "path",
                "path_role",
                "counterpart_path",
                "status",
                "index_entries",
                "worktree",
            }
        ):
            errors.append(f"{entry_label} fields differ")
            continue
        path = entry.get("path")
        role = entry.get("path_role")
        counterpart = entry.get("counterpart_path")
        if (
            not _gate_git_path_is_valid(path)
            or path == GATE_REPOSITORY_CHECKPOINT_PATH
            or (
                isinstance(path, str)
                and path.startswith(gate_prefix)
            )
        ):
            errors.append(f"{entry_label} path/exclusion differs")
        elif path in entries_by_path:
            errors.append(f"{entry_label} path is duplicated")
        else:
            path_names.append(path)
            entries_by_path[path] = entry
        if role == "CURRENT":
            represented_record_count += 1
            if counterpart is not None:
                errors.append(f"{entry_label} counterpart differs")
        elif isinstance(role, str) and role in {
            "DESTINATION",
            "RENAME_SOURCE",
            "COPY_SOURCE",
        }:
            if not _gate_git_path_is_valid(counterpart) or counterpart == path:
                errors.append(f"{entry_label} counterpart differs")
            if role == "DESTINATION":
                represented_record_count += 1
        else:
            errors.append(f"{entry_label} path role differs")
        status_errors = _gate_status_record_errors(
            entry.get("status"),
            object_format=object_format,
        )
        errors.extend(f"{entry_label} {error}" for error in status_errors)
        errors.extend(
            f"{entry_label} {error}"
            for error in _gate_index_entry_list_errors(
                entry.get("index_entries"),
                object_format=object_format,
            )
        )
        errors.extend(
            f"{entry_label} {error}"
            for error in _gate_worktree_identity_errors(
                entry.get("worktree")
            )
        )
        errors.extend(
            f"{entry_label} {error}"
            for error in _gate_path_cross_field_errors(
                entry,
                object_format=object_format,
            )
        )
        status = entry.get("status")
        if isinstance(status, dict):
            if status.get("kind") == "UNTRACKED" and entry.get(
                "index_entries"
            ) != []:
                errors.append(f"{entry_label} untracked index differs")
            if (
                role == "CURRENT"
                and status.get("kind") == "RENAMED_OR_COPIED"
            ) or (
                role != "CURRENT"
                and status.get("kind") != "RENAMED_OR_COPIED"
            ):
                errors.append(f"{entry_label} role/status differs")

    if path_names != sorted(path_names) or len(path_names) != len(paths):
        errors.append("gate repository state paths are not canonical")
    if dirty.get("dirty_path_count") != len(paths):
        errors.append("gate repository state dirty path count differs")
    for path, entry in entries_by_path.items():
        role = entry.get("path_role")
        if role == "CURRENT":
            continue
        counterpart = entry.get("counterpart_path")
        counterpart_entry = (
            entries_by_path.get(counterpart)
            if isinstance(counterpart, str)
            else None
        )
        score = (
            entry.get("status", {}).get("rename_or_copy_score")
            if isinstance(entry.get("status"), dict)
            else None
        )
        expected_source_role = (
            "RENAME_SOURCE"
            if isinstance(score, str) and score.startswith("R")
            else "COPY_SOURCE"
        )
        if role == "DESTINATION":
            pair_valid = bool(
                isinstance(counterpart_entry, dict)
                and counterpart_entry.get("path_role")
                == expected_source_role
                and counterpart_entry.get("counterpart_path") == path
            )
        else:
            pair_valid = bool(
                isinstance(counterpart_entry, dict)
                and counterpart_entry.get("path_role") == "DESTINATION"
                and counterpart_entry.get("counterpart_path") == path
                and role == expected_source_role
            )
        if (
            not pair_valid
            or not isinstance(counterpart_entry, dict)
            or counterpart_entry.get("status") != entry.get("status")
        ):
            errors.append(
                f"gate repository state rename/copy pair differs: {path}"
            )

    expected_path_hash = canonical_json_sha256(path_names)
    expected_content_hash = canonical_json_sha256(
        [
            {
                "path": entry["path"],
                "worktree": entry["worktree"],
            }
            for entry in paths
            if isinstance(entry, dict)
            and set(entry)
            >= {"path", "worktree"}
        ]
    )
    expected_index_hash = canonical_json_sha256(
        [
            {
                "path": entry["path"],
                "path_role": entry["path_role"],
                "counterpart_path": entry["counterpart_path"],
                "status": entry["status"],
                "index_entries": entry["index_entries"],
            }
            for entry in paths
            if isinstance(entry, dict)
            and set(entry)
            >= {
                "path",
                "path_role",
                "counterpart_path",
                "status",
                "index_entries",
            }
        ]
    )
    if dirty.get("path_set_sha256") != expected_path_hash:
        errors.append("gate repository state dirty path-set hash differs")
    if dirty.get("content_set_sha256") != expected_content_hash:
        errors.append("gate repository state worktree content hash differs")
    if dirty.get("index_state_sha256") != expected_index_hash:
        errors.append("gate repository state index hash differs")
    if isinstance(git_status_raw, dict):
        if git_status_raw.get("record_count") != represented_record_count:
            errors.append("gate repository state Git status record count differs")
        byte_count = git_status_raw.get("byte_count")
        raw_sha256 = git_status_raw.get("sha256")
        empty_raw_sha256 = hashlib.sha256(b"").hexdigest()
        if (
            (represented_record_count == 0 and byte_count != 0)
            or (
                represented_record_count > 0
                and (
                    not _gate_nonnegative_integer(byte_count)
                    or byte_count == 0
                )
            )
        ):
            errors.append("gate repository state Git status byte count differs")
        if (
            represented_record_count == 0
            and raw_sha256 != empty_raw_sha256
        ) or (
            represented_record_count > 0
            and raw_sha256 == empty_raw_sha256
        ):
            errors.append("gate repository state Git status hash semantics differ")
    return errors


def gate_repository_state_snapshot_summary(
    payload: dict[str, Any],
    *,
    output_sha256: str,
) -> dict[str, Any]:
    repository = payload["repository"]
    raw = payload["git_status_raw"]
    dirty = payload["dirty_snapshot"]
    checkpoint = payload["checkpoint_controlled_working_snapshot"]
    return {
        "snapshot_scope": GATE_REPOSITORY_STATE_SNAPSHOT_SCOPE,
        "gate_event_id": payload["gate_event_id"],
        "head_commit": repository["head_commit"],
        "branch": repository["branch"],
        "object_format": repository["object_format"],
        "git_status_raw_sha256": raw["sha256"],
        "git_status_raw_byte_count": raw["byte_count"],
        "git_status_raw_record_count": raw["record_count"],
        "dirty_path_count": dirty["dirty_path_count"],
        "path_set_sha256": dirty["path_set_sha256"],
        "content_set_sha256": dirty["content_set_sha256"],
        "index_state_sha256": dirty["index_state_sha256"],
        "checkpoint_base_head": checkpoint["base_head"],
        "checkpoint_managed_path_count": checkpoint[
            "managed_changed_path_count"
        ],
        "checkpoint_path_set_sha256": checkpoint["path_set_sha256"],
        "checkpoint_content_set_sha256": checkpoint[
            "content_set_sha256"
        ],
        "gate_repository_state_output_sha256": output_sha256,
    }


def load_gate_repository_state_log(
    root: Path,
    *,
    label: str,
    event_id: str,
    check_runs: Any,
) -> tuple[
    list[str],
    dict[str, Any] | None,
    dict[str, Any] | None,
]:
    errors: list[str] = []
    expected_index = GATE_REPOSITORY_STATE_CHECK_NUMBER
    item = (
        check_runs[expected_index - 1]
        if isinstance(check_runs, list)
        and len(check_runs) >= expected_index
        and isinstance(check_runs[expected_index - 1], dict)
        else {}
    )
    expected_output = (
        f"docs/control/execution/goal-gates/{event_id}/"
        f"{expected_index:02d}-REPOSITORY_STATE.log"
    )
    relative_output = item.get("output_path")
    output_path = resolve_safe_repo_file(root, relative_output)
    if (
        item.get("check_id") != "REPOSITORY_STATE"
        or relative_output != expected_output
        or output_path is None
    ):
        return [
            f"{label}: repository state log is not the 19th event-scoped check"
        ], None, None
    try:
        raw = output_path.read_bytes()
        if output_path.read_bytes() != raw:
            raise ValueError("log changed while being read")
    except (OSError, ValueError) as exc:
        return [
            f"{label}: repository state log cannot be read safely: {exc}"
        ], None, None
    output_sha256 = hashlib.sha256(raw).hexdigest()
    if item.get("output_sha256") != output_sha256:
        errors.append(f"{label}: repository state log hash differs")
    try:
        decoded = raw.decode("utf-8", errors="strict")
        payload = json.loads(decoded)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        return errors + [
            f"{label}: repository state log is not canonical JSON: {exc}"
        ], None, None
    if not isinstance(payload, dict):
        return errors + [
            f"{label}: repository state log root must be an object"
        ], None, None
    try:
        expected_raw = canonical_json_bytes(payload) + b"\n"
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        return errors + [
            f"{label}: repository state log cannot be canonicalized: {exc}"
        ], None, None
    if raw != expected_raw:
        errors.append(
            f"{label}: repository state log bytes are not canonical JSON"
        )
    payload_errors = validate_gate_repository_state_payload(
        payload,
        expected_event_id=event_id,
    )
    errors.extend(f"{label}: {error}" for error in payload_errors)
    summary = (
        gate_repository_state_snapshot_summary(
            payload,
            output_sha256=output_sha256,
        )
        if not payload_errors
        else None
    )
    return errors, payload, summary


def repository_snapshot_record_is_valid(
    value: Any,
    *,
    expected: dict[str, Any] | None = None,
) -> bool:
    return bool(
        isinstance(value, dict)
        and set(value)
        == {
            "base_head",
            "branch",
            "managed_path_count",
            "path_set_sha256",
            "content_set_sha256",
        }
        and isinstance(value.get("base_head"), str)
        and value.get("base_head")
        and isinstance(value.get("branch"), str)
        and value.get("branch")
        and isinstance(value.get("managed_path_count"), int)
        and not isinstance(value.get("managed_path_count"), bool)
        and value.get("managed_path_count") > 0
        and isinstance(value.get("path_set_sha256"), str)
        and SHA256_PATTERN.fullmatch(value["path_set_sha256"])
        and isinstance(value.get("content_set_sha256"), str)
        and SHA256_PATTERN.fullmatch(value["content_set_sha256"])
        and (expected is None or value == expected)
    )


def repository_snapshot_from_checkpoint(
    checkpoint: dict[str, Any],
) -> dict[str, Any]:
    working = checkpoint.get("working_tree_snapshot")
    repository = checkpoint.get("repository")
    if not isinstance(working, dict) or not isinstance(repository, dict):
        return {}
    return {
        "base_head": working.get("base_head"),
        "branch": repository.get("branch"),
        "managed_path_count": working.get("managed_changed_path_count"),
        "path_set_sha256": working.get("path_set_sha256"),
        "content_set_sha256": working.get("content_set_sha256"),
    }


def validate_package_activation_receipts(
    root: Path,
    *,
    label: str,
    event: dict[str, Any],
    occurred_at: datetime | None,
    expected_live_repository_snapshot: dict[str, Any] | None,
    used_output_paths: set[str],
) -> list[str]:
    event_id = str(event.get("event_id", ""))
    authorization_binding = event.get(
        "package_activation_authorization_binding"
    )
    quick_binding = event.get("activation_quick_gate_binding")
    authorization_errors, authorization, authorization_hash = (
        load_direct_event_receipt(
            root,
            authorization_binding,
            label=f"{label}: package activation authorization",
        )
    )
    quick_errors, receipt, _ = load_direct_event_receipt(
        root,
        quick_binding,
        label=f"{label}: activation quick gate",
    )
    errors = [*authorization_errors, *quick_errors]
    if (
        not isinstance(authorization_binding, dict)
        or authorization_binding.get("path")
        != (
            f"docs/control/execution/goal-gates/{event_id}/"
            "authorization.json"
        )
        or not isinstance(quick_binding, dict)
        or quick_binding.get("path")
        != (
            f"docs/control/execution/goal-gates/{event_id}/"
            "quick-gate-receipt.json"
        )
    ):
        errors.append(
            f"{label}: activation evidence path is not event-scoped"
        )
    if (
        authorization_hash
        != EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256
        or not isinstance(
            EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256,
            str,
        )
        or not SHA256_PATTERN.fullmatch(
            EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256
        )
    ):
        errors.append(
            f"{label}: package activation authorization lacks the exact "
            "checker trust anchor"
        )
    authorization_request = authorization.get("activation_request")
    requested_at = parse_iso_datetime(
        authorization_request.get("requested_at")
        if isinstance(authorization_request, dict)
        else None
    )
    authorized_at = parse_iso_datetime(authorization.get("authorized_at"))
    authorization_generated_at = parse_iso_datetime(
        authorization.get("generated_at")
    )
    if (
        authorization.get("schema_version") != "1.0"
        or authorization.get("evidence_type")
        != "PACKAGE_ACTIVATION_AUTHORIZATION"
        or authorization.get("status") != "AUTHORIZED"
        or authorization.get("package_id") != EXPECTED_PACKAGE_ID
        or not isinstance(authorization_request, dict)
        or authorization_request.get("source_kind")
        != "USER_EXPLICIT_REQUEST"
        or not isinstance(
            authorization_request.get("request_id"),
            str,
        )
        or not authorization_request.get("request_id")
        or not isinstance(
            authorization_request.get("request_sha256"),
            str,
        )
        or not SHA256_PATTERN.fullmatch(
            authorization_request["request_sha256"]
        )
        or any(
            value is None
            for value in (
                requested_at,
                authorized_at,
                authorization_generated_at,
            )
        )
        or not (
            requested_at
            <= authorized_at
            <= authorization_generated_at
        )
    ):
        errors.append(
            f"{label}: package activation authorization semantics differ"
        )
    window = receipt.get("execution_window")
    started_at = parse_iso_datetime(
        window.get("started_at") if isinstance(window, dict) else None
    )
    ended_at = parse_iso_datetime(
        window.get("ended_at") if isinstance(window, dict) else None
    )
    generated_at = parse_iso_datetime(receipt.get("generated_at"))
    event_snapshot = event.get("repository_snapshot_before")
    if (
        receipt.get("schema_version") != "1.0"
        or receipt.get("evidence_type")
        != "PACKAGE_ACTIVATION_QUICK_GATE"
        or receipt.get("status") != "PASS"
        or receipt.get("package_id") != EXPECTED_PACKAGE_ID
        or receipt.get("target_transition_event_id")
        != event.get("event_id")
        or receipt.get("static_plan_manifest_sha256")
        != event.get("static_plan_manifest_sha256")
        or receipt.get("authorization_receipt_binding")
        != authorization_binding
        or receipt.get("check_command_contract_version")
        != CHECK_COMMAND_CONTRACT_VERSION
        or receipt.get("check_command_contract_sha256")
        != QUICK_ACTIVATION_CHECK_CONTRACT_SHA256
        or not repository_snapshot_record_is_valid(
            event_snapshot,
            expected=receipt.get("repository_snapshot"),
        )
        or (
            expected_live_repository_snapshot is not None
            and event_snapshot != expected_live_repository_snapshot
        )
        or any(
            value is None
            for value in (
                requested_at,
                authorization_generated_at,
                started_at,
                ended_at,
                generated_at,
                occurred_at,
            )
        )
        or not (
            requested_at
            <= authorization_generated_at
            <= started_at
            <= ended_at
            <= generated_at
            <= occurred_at
        )
    ):
        errors.append(f"{label}: package activation receipt semantics differ")
    errors.extend(
        validate_internal_check_runs(
            root,
            label=f"{label}: package activation",
            check_runs=receipt.get("check_runs"),
            expected_checks=QUICK_ACTIVATION_CHECKS,
            event_id=event_id,
            used_output_paths=used_output_paths,
            started_at=started_at,
            ended_at=ended_at,
        )
    )
    return errors


def controlled_content_set_sha256(
    root: Path,
    managed_paths: list[str],
    *,
    sha256_overrides: dict[str, str],
) -> str:
    sha256_by_path: dict[str, str] = {}
    for relative in sorted(managed_paths):
        content_sha256 = sha256_overrides.get(relative)
        if content_sha256 is None:
            path = resolve_safe_repo_file(root, relative)
            if path is None:
                raise ValueError(
                    f"unsafe or missing controlled path: {relative}"
                )
            content_sha256 = sha256_file(path)
        sha256_by_path[relative] = content_sha256
    return controlled_content_root_from_witness(sha256_by_path)


def controlled_content_root_from_witness(
    sha256_by_path: dict[str, str],
) -> str:
    digest = hashlib.sha256()
    for relative in sorted(sha256_by_path):
        content_sha256 = sha256_by_path[relative]
        if not SHA256_PATTERN.fullmatch(content_sha256):
            raise ValueError(
                f"invalid controlled path SHA-256: {relative}"
            )
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(content_sha256.encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def validate_prestart_control_repair_receipt(
    root: Path,
    *,
    label: str,
    event: dict[str, Any],
    activation_event: dict[str, Any] | None,
    activation_occurred_at: datetime | None,
    occurred_at: datetime | None,
    checkpoint: dict[str, Any],
    live_repository_snapshot: dict[str, Any],
    is_tail: bool,
    used_output_paths: set[str],
) -> list[str]:
    errors: list[str] = []
    event_id = str(event.get("event_id", ""))
    if (
        not SAFE_ID_PATTERN.fullmatch(event_id)
        or event_id in {".", ".."}
    ):
        return [
            f"{label}: prestart control repair event ID is unsafe"
        ]
    event_prefix = f"docs/control/execution/goal-gates/{event_id}"
    receipt_binding = event.get(
        "prestart_control_repair_receipt_binding"
    )
    load_errors, receipt, _ = load_direct_event_receipt(
        root,
        receipt_binding,
        label=f"{label}: prestart control repair receipt",
    )
    errors.extend(load_errors)
    expected_receipt_path = (
        f"{event_prefix}/prestart-control-repair-receipt.json"
    )
    if (
        not isinstance(receipt_binding, dict)
        or receipt_binding.get("path") != expected_receipt_path
    ):
        errors.append(
            f"{label}: repair receipt path is not event-scoped"
        )

    receipt_fields = {
        "schema_version",
        "document_id",
        "evidence_type",
        "status",
        "package_id",
        "target_transition_event_id",
        "source_activation_event_sha256",
        "reason_code",
        "repository_snapshot_before",
        "repository_snapshot_after",
        "changed_paths",
        "execution_window",
        "check_runs",
        "executor",
        "controlled_content_witness_binding",
        "independent_review_binding",
        "generated_at",
    }
    document_id = receipt.get("document_id")
    if (
        set(receipt) != receipt_fields
        or receipt.get("schema_version") != "1.0"
        or not isinstance(document_id, str)
        or not SAFE_ID_PATTERN.fullmatch(document_id)
        or receipt.get("evidence_type")
        != "PRESTART_CONTROL_REPAIR_RECEIPT"
        or receipt.get("status") != "PASS"
        or receipt.get("package_id") != EXPECTED_PACKAGE_ID
        or receipt.get("target_transition_event_id") != event_id
        or receipt.get("source_activation_event_sha256")
        != (
            activation_event.get("event_sha256")
            if isinstance(activation_event, dict)
            else None
        )
        or receipt.get("reason_code")
        != "STATE_DEPENDENT_TEST_FIXTURE_REPAIR"
    ):
        errors.append(f"{label}: repair receipt semantics differ")

    activation_snapshot = (
        activation_event.get("repository_snapshot_before")
        if isinstance(activation_event, dict)
        else None
    )
    snapshot_before = event.get("repository_snapshot_before")
    snapshot_after = event.get("repository_snapshot_after")
    if (
        not repository_snapshot_record_is_valid(
            snapshot_before,
            expected=activation_snapshot,
        )
        or receipt.get("repository_snapshot_before")
        != snapshot_before
        or not repository_snapshot_record_is_valid(snapshot_after)
        or receipt.get("repository_snapshot_after") != snapshot_after
    ):
        errors.append(f"{label}: repair repository snapshots differ")
    if (
        isinstance(snapshot_before, dict)
        and isinstance(snapshot_after, dict)
        and (
            {
                key: snapshot_before.get(key)
                for key in (
                    "base_head",
                    "branch",
                    "managed_path_count",
                    "path_set_sha256",
                )
            }
            != {
                key: snapshot_after.get(key)
                for key in (
                    "base_head",
                    "branch",
                    "managed_path_count",
                    "path_set_sha256",
                )
            }
            or snapshot_before.get("content_set_sha256")
            == snapshot_after.get("content_set_sha256")
        )
    ):
        errors.append(
            f"{label}: repair changed repository identity/path scope "
            "or is a content no-op"
        )
    if is_tail and snapshot_after != live_repository_snapshot:
        errors.append(
            f"{label}: tail repair snapshot differs from live checkpoint"
        )

    changed_paths = receipt.get("changed_paths")
    expected_changed_paths = list(PRESTART_CONTROL_REPAIR_CHANGED_PATHS)
    changed_path_rows_valid = bool(
        isinstance(changed_paths, list)
        and len(changed_paths) == len(expected_changed_paths)
        and [
            row.get("path") if isinstance(row, dict) else None
            for row in changed_paths
        ]
        == expected_changed_paths
    )
    if not changed_path_rows_valid:
        errors.append(
            f"{label}: repair changed path set/order differs"
        )
        changed_paths = []

    before_overrides: dict[str, str] = {}
    expected_evidence_files = {
        expected_receipt_path,
        f"{event_prefix}/independent-review.json",
        f"{event_prefix}/controlled-content-witness.json",
        *(
            f"{event_prefix}/{file_name}"
            for _, _, _, file_name in PRESTART_CONTROL_REPAIR_CHECKS
        ),
        *(
            f"{event_prefix}/before/{relative}"
            for relative in PRESTART_CONTROL_REPAIR_CHANGED_PATHS
        ),
    }
    for index, expected_path in enumerate(expected_changed_paths):
        row = (
            changed_paths[index]
            if index < len(changed_paths)
            and isinstance(changed_paths[index], dict)
            else {}
        )
        expected_archive = f"{event_prefix}/before/{expected_path}"
        archive_path = resolve_safe_repo_file(
            root,
            row.get("before_archive_path"),
        )
        current_path = resolve_safe_repo_file(root, expected_path)
        before_sha256 = row.get("before_sha256")
        after_sha256 = row.get("after_sha256")
        archive_size: int | None = None
        archive_actual_sha256: str | None = None
        current_actual_sha256: str | None = None
        try:
            if archive_path is not None:
                archive_size = archive_path.stat().st_size
                archive_actual_sha256 = sha256_file(archive_path)
            if current_path is not None:
                current_actual_sha256 = sha256_file(current_path)
        except (OSError, RuntimeError, ValueError):
            archive_size = None
            archive_actual_sha256 = None
            current_actual_sha256 = None
        if (
            set(row)
            != {
                "path",
                "before_archive_path",
                "before_sha256",
                "after_sha256",
            }
            or row.get("path") != expected_path
            or row.get("before_archive_path") != expected_archive
            or archive_path is None
            or archive_size is None
            or archive_size <= 0
            or not isinstance(before_sha256, str)
            or not SHA256_PATTERN.fullmatch(before_sha256)
            or before_sha256 != archive_actual_sha256
            or not isinstance(after_sha256, str)
            or not SHA256_PATTERN.fullmatch(after_sha256)
            or before_sha256 == after_sha256
            or current_path is None
            or after_sha256 != current_actual_sha256
        ):
            errors.append(
                f"{label}: repair changed path evidence differs: "
                f"{expected_path}"
            )
        elif isinstance(before_sha256, str):
            before_overrides[expected_path] = before_sha256

    witness_binding = receipt.get(
        "controlled_content_witness_binding"
    )
    witness_errors, witness, _ = load_direct_event_receipt(
        root,
        witness_binding,
        label=f"{label}: controlled content witness",
    )
    errors.extend(witness_errors)
    expected_witness_path = (
        f"{event_prefix}/controlled-content-witness.json"
    )
    if (
        not isinstance(witness_binding, dict)
        or witness_binding.get("path") != expected_witness_path
    ):
        errors.append(
            f"{label}: controlled content witness path is not "
            "event-scoped"
        )
    witness_fields = {
        "schema_version",
        "document_id",
        "evidence_type",
        "status",
        "target_transition_event_id",
        "repository_snapshot_before",
        "repository_snapshot_after",
        "managed_content_sha256_by_path_after",
        "generated_at",
    }
    witness_document_id = witness.get("document_id")
    after_sha256_by_path = witness.get(
        "managed_content_sha256_by_path_after"
    )
    expected_witness_path_count = (
        snapshot_before.get("managed_path_count")
        if isinstance(snapshot_before, dict)
        and isinstance(
            snapshot_before.get("managed_path_count"),
            int,
        )
        and not isinstance(
            snapshot_before.get("managed_path_count"),
            bool,
        )
        else None
    )
    witness_paths = (
        list(after_sha256_by_path)
        if (
            isinstance(after_sha256_by_path, dict)
            and expected_witness_path_count is not None
            and len(after_sha256_by_path)
            == expected_witness_path_count
        )
        else []
    )
    witness_paths_are_valid = bool(
        isinstance(after_sha256_by_path, dict)
        and witness_paths
        and witness_paths == sorted(witness_paths)
        and all(
            isinstance(relative, str)
            and relative
            and "\0" not in relative
            and "\n" not in relative
            and not Path(relative).is_absolute()
            and bool(Path(relative).parts)
            and all(
                part not in {"", ".", ".."}
                for part in Path(relative).parts
            )
            and Path(relative).as_posix() == relative
            and isinstance(after_sha256_by_path.get(relative), str)
            and SHA256_PATTERN.fullmatch(
                after_sha256_by_path[relative]
            )
            for relative in witness_paths
        )
    )
    try:
        witness_path_set_sha256 = (
            hashlib.sha256(
                ("\n".join(witness_paths) + "\n").encode("utf-8")
            ).hexdigest()
            if witness_paths_are_valid
            else None
        )
    except UnicodeEncodeError:
        witness_paths_are_valid = False
        witness_path_set_sha256 = None
    try:
        witness_after_content_sha256 = (
            controlled_content_root_from_witness(
                after_sha256_by_path
            )
            if witness_paths_are_valid
            else None
        )
    except (TypeError, ValueError):
        witness_after_content_sha256 = None
    witness_before_sha256_by_path = (
        dict(after_sha256_by_path)
        if witness_paths_are_valid
        else {}
    )
    for changed_path, before_sha256 in before_overrides.items():
        if changed_path in witness_before_sha256_by_path:
            witness_before_sha256_by_path[changed_path] = before_sha256
    try:
        witness_before_content_sha256 = (
            controlled_content_root_from_witness(
                witness_before_sha256_by_path
            )
            if (
                witness_paths_are_valid
                and set(before_overrides)
                == set(PRESTART_CONTROL_REPAIR_CHANGED_PATHS)
                and set(PRESTART_CONTROL_REPAIR_CHANGED_PATHS)
                .issubset(witness_before_sha256_by_path)
            )
            else None
        )
    except (TypeError, ValueError):
        witness_before_content_sha256 = None
    changed_after_sha256_by_path = {
        row.get("path"): row.get("after_sha256")
        for row in changed_paths
        if isinstance(row, dict)
        and isinstance(row.get("path"), str)
    }
    if (
        set(witness) != witness_fields
        or witness.get("schema_version") != "1.0"
        or not isinstance(witness_document_id, str)
        or not SAFE_ID_PATTERN.fullmatch(witness_document_id)
        or witness.get("evidence_type")
        != "PRESTART_CONTROL_REPAIR_CONTROLLED_CONTENT_WITNESS"
        or witness.get("status") != "CAPTURED"
        or witness.get("target_transition_event_id") != event_id
        or witness.get("repository_snapshot_before") != snapshot_before
        or witness.get("repository_snapshot_after") != snapshot_after
        or not witness_paths_are_valid
        or not isinstance(snapshot_before, dict)
        or not isinstance(snapshot_after, dict)
        or snapshot_before.get("managed_path_count")
        != len(witness_paths)
        or snapshot_after.get("managed_path_count")
        != len(witness_paths)
        or snapshot_before.get("path_set_sha256")
        != witness_path_set_sha256
        or snapshot_after.get("path_set_sha256")
        != witness_path_set_sha256
        or snapshot_after.get("content_set_sha256")
        != witness_after_content_sha256
        or snapshot_before.get("content_set_sha256")
        != witness_before_content_sha256
        or any(
            after_sha256_by_path.get(relative)
            != changed_after_sha256_by_path.get(relative)
            for relative in PRESTART_CONTROL_REPAIR_CHANGED_PATHS
        )
    ):
        errors.append(
            f"{label}: controlled content witness differs"
        )
    witness_generated_at = parse_iso_datetime(
        witness.get("generated_at")
    )

    event_directory = root / event_prefix
    expected_evidence_directories = {
        f"{event_prefix}/before",
        f"{event_prefix}/before/scripts",
        f"{event_prefix}/before/tests",
    }
    try:
        actual_evidence_files: set[str] = set()
        actual_evidence_directories: set[str] = set()
        unsafe_evidence_entries: set[str] = set()
        allowed_evidence_entries = (
            expected_evidence_files
            | expected_evidence_directories
        )
        if (
            continuation.repo_path_has_symlink_component(
                root,
                event_prefix,
            )
            or not event_directory.is_dir()
        ):
            unsafe_evidence_entries.add(event_prefix)
        else:
            for path in event_directory.rglob("*"):
                relative = path.relative_to(root).as_posix()
                if relative not in allowed_evidence_entries:
                    unsafe_evidence_entries.add(relative)
                    break
                if path.is_symlink():
                    unsafe_evidence_entries.add(relative)
                    break
                if path.is_file():
                    actual_evidence_files.add(relative)
                elif path.is_dir():
                    actual_evidence_directories.add(relative)
                else:
                    unsafe_evidence_entries.add(relative)
                    break
    except (OSError, RuntimeError, ValueError):
        actual_evidence_files = set()
        actual_evidence_directories = set()
        unsafe_evidence_entries = {"<inventory-read-failed>"}
    if (
        actual_evidence_files != expected_evidence_files
        or actual_evidence_directories
        != expected_evidence_directories
        or unsafe_evidence_entries
    ):
        errors.append(
            f"{label}: repair event evidence inventory differs"
        )

    window = receipt.get("execution_window")
    if not isinstance(window, dict) or set(window) != {
        "started_at",
        "ended_at",
    }:
        errors.append(f"{label}: repair execution window fields differ")
        window = {}
    started_at = parse_iso_datetime(window.get("started_at"))
    ended_at = parse_iso_datetime(window.get("ended_at"))
    check_runs = receipt.get("check_runs")
    if not isinstance(check_runs, list):
        errors.append(f"{label}: repair check run list is missing")
        check_runs = []
    actual_check_ids = [
        row.get("check_id") if isinstance(row, dict) else None
        for row in check_runs
    ]
    if actual_check_ids != [
        check_id for check_id, _, _, _ in PRESTART_CONTROL_REPAIR_CHECKS
    ]:
        errors.append(f"{label}: repair check ID order differs")
    check_times: list[datetime | None] = []
    receipt_output_paths: set[str] = set()
    for index, (
        check_id,
        expected_command,
        expected_exit_code,
        file_name,
    ) in enumerate(PRESTART_CONTROL_REPAIR_CHECKS):
        row = (
            check_runs[index]
            if index < len(check_runs)
            and isinstance(check_runs[index], dict)
            else {}
        )
        expected_output = f"{event_prefix}/{file_name}"
        relative_output = row.get("output_path")
        output_path = resolve_safe_repo_file(root, relative_output)
        executed_at = parse_iso_datetime(row.get("executed_at"))
        check_times.append(executed_at)
        output_size: int | None = None
        output_actual_sha256: str | None = None
        try:
            if output_path is not None:
                output_size = output_path.stat().st_size
                output_actual_sha256 = sha256_file(output_path)
        except (OSError, RuntimeError, ValueError):
            output_size = None
            output_actual_sha256 = None
        if (
            set(row)
            != {
                "check_id",
                "command",
                "executed_at",
                "exit_code",
                "output_path",
                "output_sha256",
            }
            or row.get("check_id") != check_id
            or row.get("command") != expected_command
            or type(row.get("exit_code")) is not int
            or row.get("exit_code") != expected_exit_code
            or relative_output != expected_output
            or output_path is None
            or output_size is None
            or output_size <= 0
            or row.get("output_sha256") != output_actual_sha256
            or started_at is None
            or ended_at is None
            or executed_at is None
            or not started_at <= executed_at <= ended_at
        ):
            errors.append(
                f"{label}: repair check result differs: {check_id}"
            )
        if isinstance(relative_output, str):
            if (
                relative_output in receipt_output_paths
                or relative_output in used_output_paths
            ):
                errors.append(
                    f"{label}: repair check output path is reused: "
                    f"{relative_output}"
                )
            receipt_output_paths.add(relative_output)
            used_output_paths.add(relative_output)
    if len(check_runs) != len(PRESTART_CONTROL_REPAIR_CHECKS):
        errors.append(f"{label}: repair check run count differs")

    executor = receipt.get("executor")
    executor_id = (
        executor.get("id") if isinstance(executor, dict) else None
    )
    if (
        not isinstance(executor, dict)
        or set(executor) != {"id", "role", "authority"}
        or not isinstance(executor_id, str)
        or not SAFE_ID_PATTERN.fullmatch(executor_id)
        or executor.get("role") != "CONTROL_PLANE_REPAIR_EXECUTOR"
        or executor.get("authority")
        != "INTERNAL_REPOSITORY_CONTROL"
    ):
        errors.append(f"{label}: repair executor identity differs")

    review_binding = receipt.get("independent_review_binding")
    review_errors, review, _ = load_direct_event_receipt(
        root,
        review_binding,
        label=f"{label}: independent repair review",
    )
    errors.extend(review_errors)
    expected_review_path = f"{event_prefix}/independent-review.json"
    if (
        not isinstance(review_binding, dict)
        or review_binding.get("path") != expected_review_path
    ):
        errors.append(
            f"{label}: repair review path is not event-scoped"
        )
    review_fields = {
        "schema_version",
        "document_id",
        "evidence_type",
        "status",
        "target_transition_event_id",
        "repository_snapshot_before",
        "repository_snapshot_after",
        "changed_paths",
        "controlled_content_witness_binding",
        "reviewer",
        "reviewed_at",
        "generated_at",
    }
    review_document_id = review.get("document_id")
    reviewer = review.get("reviewer")
    reviewer_id = (
        reviewer.get("id") if isinstance(reviewer, dict) else None
    )
    if (
        set(review) != review_fields
        or review.get("schema_version") != "1.0"
        or not isinstance(review_document_id, str)
        or not SAFE_ID_PATTERN.fullmatch(review_document_id)
        or review.get("evidence_type")
        != "PRESTART_CONTROL_REPAIR_INDEPENDENT_REVIEW"
        or review.get("status") != "ACCEPTED"
        or review.get("target_transition_event_id") != event_id
        or review.get("repository_snapshot_before") != snapshot_before
        or review.get("repository_snapshot_after") != snapshot_after
        or review.get("changed_paths") != changed_paths
        or review.get("controlled_content_witness_binding")
        != witness_binding
        or not isinstance(reviewer, dict)
        or set(reviewer) != {"id", "role", "authority"}
        or not isinstance(reviewer_id, str)
        or not SAFE_ID_PATTERN.fullmatch(reviewer_id)
        or reviewer_id == executor_id
        or reviewer_id
        == "SYNTHETIC-CHECKER-VALIDATION-ONLY-NOT-FOR-COMMIT"
        or reviewer.get("role") != "INDEPENDENT_CONTROL_REVIEWER"
        or reviewer.get("authority")
        != "INTERNAL_REPOSITORY_CONTROL"
    ):
        errors.append(f"{label}: independent repair review differs")

    fail_executed_at = check_times[0] if len(check_times) > 0 else None
    pass_executed_at = check_times[1] if len(check_times) > 1 else None
    reviewed_at = parse_iso_datetime(review.get("reviewed_at"))
    review_generated_at = parse_iso_datetime(review.get("generated_at"))
    receipt_generated_at = parse_iso_datetime(receipt.get("generated_at"))
    chronology = (
        activation_occurred_at,
        started_at,
        fail_executed_at,
        pass_executed_at,
        ended_at,
        witness_generated_at,
        reviewed_at,
        review_generated_at,
        receipt_generated_at,
        occurred_at,
    )
    if (
        any(value is None for value in chronology)
        or list(chronology) != sorted(chronology)
        or (
            fail_executed_at is not None
            and pass_executed_at is not None
            and fail_executed_at >= pass_executed_at
        )
    ):
        errors.append(f"{label}: repair evidence chronology differs")

    working_snapshot = checkpoint.get("working_tree_snapshot")
    managed_paths = (
        working_snapshot.get("managed_changed_paths")
        if isinstance(working_snapshot, dict)
        else None
    )
    controlled_manifest_valid = bool(
        isinstance(managed_paths, list)
        and managed_paths == sorted(set(managed_paths))
        and not (
            set(PRESTART_CONTROL_REPAIR_CHANGED_PATHS)
            - set(managed_paths)
        )
    )
    if not controlled_manifest_valid:
        errors.append(
            f"{label}: repair controlled path manifest differs"
        )
    else:
        checkpoint_path = CHECKPOINT_RELATIVE.as_posix()
        if checkpoint_path in managed_paths:
            errors.append(
                f"{label}: repair snapshot includes its checkpoint"
            )
        if any(
            path.startswith(f"{event_prefix}/")
            for path in managed_paths
        ):
            errors.append(
                f"{label}: repair snapshot includes event evidence"
            )
        if is_tail:
            try:
                reconstructed_before_sha256 = (
                    controlled_content_set_sha256(
                        root,
                        managed_paths,
                        sha256_overrides=before_overrides,
                    )
                )
            except (OSError, RuntimeError, ValueError) as exc:
                errors.append(
                    f"{label}: repair before snapshot cannot be "
                    f"reconstructed: {exc}"
                )
            else:
                if (
                    len(before_overrides)
                    != len(PRESTART_CONTROL_REPAIR_CHANGED_PATHS)
                    or not isinstance(snapshot_before, dict)
                    or reconstructed_before_sha256
                    != snapshot_before.get("content_set_sha256")
                ):
                    errors.append(
                        f"{label}: repair before snapshot content differs"
                    )
            try:
                live_path_sha256, live_content_sha256 = (
                    continuation.working_snapshot_hashes(
                        root,
                        managed_paths,
                    )
                )
            except (OSError, RuntimeError, ValueError) as exc:
                errors.append(
                    f"{label}: repair after snapshot cannot be "
                    f"reproduced: {exc}"
                )
            else:
                if (
                    not isinstance(snapshot_after, dict)
                    or snapshot_after.get("managed_path_count")
                    != len(managed_paths)
                    or snapshot_after.get("path_set_sha256")
                    != live_path_sha256
                    or snapshot_after.get("content_set_sha256")
                    != live_content_sha256
                ):
                    errors.append(
                        f"{label}: repair after snapshot content differs"
                    )
    return errors


def validate_implementation_start_gate_receipt(
    root: Path,
    *,
    label: str,
    event: dict[str, Any],
    goal_id: str,
    goal_path: str,
    activation_event_sha256: str | None,
    activation_occurred_at: datetime | None,
    occurred_at: datetime | None,
    used_output_paths: set[str],
    gate_purpose: str,
) -> list[str]:
    event_id = str(event.get("event_id", ""))
    gate_binding = event.get("implementation_start_gate_binding")
    load_errors, receipt, _ = load_direct_event_receipt(
        root,
        gate_binding,
        label=f"{label}: implementation start gate",
    )
    errors = list(load_errors)
    expected_receipt_name = (
        "implementation-start-gate-receipt.json"
        if gate_purpose == "INITIAL_START"
        else "implementation-resume-gate-receipt.json"
    )
    if (
        not isinstance(gate_binding, dict)
        or gate_binding.get("path")
        != (
            f"docs/control/execution/goal-gates/{event_id}/"
            f"{expected_receipt_name}"
        )
    ):
        errors.append(
            f"{label}: implementation gate receipt path is not event-scoped"
        )
    window = receipt.get("execution_window")
    started_at = parse_iso_datetime(
        window.get("started_at") if isinstance(window, dict) else None
    )
    ended_at = parse_iso_datetime(
        window.get("ended_at") if isinstance(window, dict) else None
    )
    generated_at = parse_iso_datetime(receipt.get("generated_at"))
    goal_file = resolve_safe_repo_file(root, goal_path)
    lock_path = resolve_safe_repo_file(
        root,
        "configs/walksafe_node_toolchain_lock_20260715.json",
    )
    expected_lock_binding = (
        {
            "path": (
                "configs/walksafe_node_toolchain_lock_20260715.json"
            ),
            "file_sha256": sha256_file(lock_path),
        }
        if lock_path is not None
        else None
    )
    if (
        receipt.get("schema_version") != "1.0"
        or receipt.get("evidence_type")
        != "IMPLEMENTATION_START_OR_RESUME_GATE"
        or receipt.get("gate_purpose") != gate_purpose
        or receipt.get("status") != "PASS"
        or receipt.get("package_id") != EXPECTED_PACKAGE_ID
        or receipt.get("target_transition_event_id")
        != event.get("event_id")
        or receipt.get("target_goal_id") != goal_id
        or goal_file is None
        or receipt.get("target_goal_content_sha256")
        != sha256_file(goal_file)
        or receipt.get("static_plan_manifest_sha256")
        != event.get("static_plan_manifest_sha256")
        or receipt.get("source_activation_event_sha256")
        != activation_event_sha256
        or receipt.get("check_command_contract_version")
        != CHECK_COMMAND_CONTRACT_VERSION
        or receipt.get("check_command_contract_sha256")
        != IMPLEMENTATION_START_GATE_CHECK_CONTRACT_SHA256
        or receipt.get("toolchain_lock_binding")
        != expected_lock_binding
        or any(
            value is None
            for value in (
                started_at,
                ended_at,
                generated_at,
                activation_occurred_at,
                occurred_at,
            )
        )
        or not (
            activation_occurred_at
            <= started_at
            <= ended_at
            <= generated_at
            <= occurred_at
        )
        or occurred_at - generated_at > timedelta(hours=1)
    ):
        errors.append(
            f"{label}: implementation start gate semantics/freshness differ"
        )
    errors.extend(
        validate_internal_check_runs(
            root,
            label=f"{label}: implementation start gate",
            check_runs=receipt.get("check_runs"),
            expected_checks=IMPLEMENTATION_START_GATE_CHECKS,
            event_id=event_id,
            used_output_paths=used_output_paths,
            started_at=started_at,
            ended_at=ended_at,
        )
    )
    repository_state_errors, _, repository_snapshot = (
        load_gate_repository_state_log(
            root,
            label=f"{label}: implementation start gate",
            event_id=event_id,
            check_runs=receipt.get("check_runs"),
        )
    )
    errors.extend(repository_state_errors)
    if (
        repository_snapshot is None
        or receipt.get("repository_snapshot") != repository_snapshot
        or event.get("repository_snapshot_before") != repository_snapshot
    ):
        errors.append(
            f"{label}: implementation gate repository snapshot "
            "log/receipt/event binding differs"
    )
    return errors


def validate_prestart_repair_first_execution_binding(
    root: Path,
    *,
    label: str,
    repair_event: dict[str, Any],
    execution_event: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    gate_binding = execution_event.get(
        "implementation_start_gate_binding"
    )
    load_errors, gate_receipt, _ = load_direct_event_receipt(
        root,
        gate_binding,
        label=f"{label}: first execution gate",
    )
    errors.extend(load_errors)
    event_id = str(execution_event.get("event_id", ""))
    repository_errors, _, gate_snapshot = (
        load_gate_repository_state_log(
            root,
            label=f"{label}: first execution gate",
            event_id=event_id,
            check_runs=gate_receipt.get("check_runs"),
        )
    )
    errors.extend(repository_errors)
    repair_snapshot_after = repair_event.get(
        "repository_snapshot_after"
    )
    expected_gate_projection = (
        {
            "base_head": gate_snapshot.get("checkpoint_base_head"),
            "branch": gate_snapshot.get("branch"),
            "managed_path_count": gate_snapshot.get(
                "checkpoint_managed_path_count"
            ),
            "path_set_sha256": gate_snapshot.get(
                "checkpoint_path_set_sha256"
            ),
            "content_set_sha256": gate_snapshot.get(
                "checkpoint_content_set_sha256"
            ),
        }
        if isinstance(gate_snapshot, dict)
        else None
    )
    window = gate_receipt.get("execution_window")
    gate_started_at = parse_iso_datetime(
        window.get("started_at") if isinstance(window, dict) else None
    )
    repair_occurred_at = parse_iso_datetime(
        repair_event.get("occurred_at")
    )
    if (
        not isinstance(gate_snapshot, dict)
        or not isinstance(repair_snapshot_after, dict)
        or repair_snapshot_after != expected_gate_projection
        or gate_snapshot.get("head_commit")
        != repair_snapshot_after.get("base_head")
    ):
        errors.append(
            f"{label}: first execution gate checkpoint snapshot "
            "differs from prestart repair after snapshot"
        )
    if (
        repair_occurred_at is None
        or gate_started_at is None
        or repair_occurred_at > gate_started_at
    ):
        errors.append(
            f"{label}: first execution gate predates prestart repair"
        )
    return errors


def validate_current_gate_repository_state(
    root: Path,
    *,
    checkpoint_path: Path,
    event: dict[str, Any],
) -> list[str]:
    event_id = str(event.get("event_id", ""))
    binding = event.get("implementation_start_gate_binding")
    load_errors, receipt, _ = load_direct_event_receipt(
        root,
        binding,
        label="current work session implementation start gate",
    )
    errors = list(load_errors)
    log_errors, recorded_payload, _ = load_gate_repository_state_log(
        root,
        label="current work session implementation start gate",
        event_id=event_id,
        check_runs=receipt.get("check_runs"),
    )
    errors.extend(log_errors)
    try:
        live_payload = continuation.capture_gate_repository_state(
            root,
            checkpoint_path,
            event_id,
        )
    except (
        OSError,
        RuntimeError,
        ValueError,
        json.JSONDecodeError,
    ) as exc:
        errors.append(
            "current work session live gate repository state cannot be "
            f"captured: {exc}"
        )
        return errors
    if recorded_payload is None or live_payload != recorded_payload:
        errors.append(
            "current work session live gate repository state differs from "
            "the recorded full payload"
        )
    return errors


def validate_blocker_resolution_event_chronology(
    root: Path,
    *,
    label: str,
    reference: Any,
    bindings: dict[str, dict[str, Any]],
    blocker_created_at: datetime | None,
    blocker_recorded_at: datetime | None,
    blocker_resolved_at: datetime | None,
) -> list[str]:
    if not isinstance(reference, str):
        return [f"{label}: blocker resolution receipt reference is missing"]
    binding = bindings.get(reference)
    if not isinstance(binding, dict):
        return [f"{label}: blocker resolution receipt binding is missing"]
    path = resolve_safe_repo_file(root, binding.get("path"))
    if path is None or binding.get("file_sha256") != sha256_file(path):
        return [f"{label}: blocker resolution receipt binding differs"]
    try:
        receipt = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"{label}: blocker resolution receipt cannot be loaded: {exc}"]
    window = receipt.get("execution_window")
    started_at = parse_iso_datetime(
        window.get("started_at") if isinstance(window, dict) else None
    )
    ended_at = parse_iso_datetime(
        window.get("ended_at") if isinstance(window, dict) else None
    )
    decided_at = parse_iso_datetime(receipt.get("decided_at"))
    generated_at = parse_iso_datetime(receipt.get("generated_at"))
    raw_evidence = receipt.get("raw_evidence")
    raw_times = [
        parse_iso_datetime(item.get("collected_at"))
        for item in raw_evidence
        if isinstance(item, dict)
    ] if isinstance(raw_evidence, list) else []
    chronology = (
        blocker_created_at,
        blocker_recorded_at,
        started_at,
        min(raw_times) if raw_times and None not in raw_times else None,
        max(raw_times) if raw_times and None not in raw_times else None,
        ended_at,
        decided_at,
        generated_at,
        blocker_resolved_at,
    )
    if any(value is None for value in chronology) or list(chronology) != sorted(
        chronology
    ):
        return [f"{label}: blocker resolution chronology differs"]
    return []


def pending_canonical_transaction_order_errors(
    *,
    pending_producer_completion_goal_id: str | None,
    pending_reopen_goal_ids: set[str],
    event_type: Any,
    subject_goal_id: Any,
) -> list[str]:
    if pending_producer_completion_goal_id is not None:
        if (
            event_type == "GOAL_COMPLETED"
            and subject_goal_id == pending_producer_completion_goal_id
        ):
            return []
        return [
            "canonical binding producer must complete before any other event"
        ]
    if pending_reopen_goal_ids and event_type != "GOAL_SUPERSEDED":
        return [
            "unresolved canonical-change Goals must be superseded before "
            "any other event"
        ]
    return []


def validate_transition_history(
    root: Path,
    checkpoint: dict[str, Any],
    state: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    paths_by_id: dict[str, str],
    bindings: dict[str, dict[str, Any]],
    live_repository_snapshot: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    history = state.get("transition_history")
    if not isinstance(history, list) or not history:
        return ["Goal graph transition history is missing"]
    previous_hash = ""
    previous_time: datetime | None = None
    cutoff = parse_iso_datetime(state.get("validation_cutoff_at"))
    if cutoff is None:
        errors.append("Goal graph validation cutoff is invalid")
    replay_statuses: dict[str, str] = {}
    replay_bindings: dict[str, dict[str, Any]] = {}
    completion_event_by_goal: dict[str, dict[str, Any]] = {}
    completion_bindings_by_goal: dict[
        str,
        dict[str, dict[str, Any]],
    ] = {}
    historical_completion_event_by_goal: dict[str, dict[str, Any]] = {}
    historical_completion_bindings_by_goal: dict[
        str,
        dict[str, dict[str, Any]],
    ] = {}
    for declared_event in history:
        if (
            not isinstance(declared_event, dict)
            or declared_event.get("event_type")
            not in {"GOAL_COMPLETED", "PACKAGE_COMPLETED"}
        ):
            continue
        declared_goal_id = declared_event.get("subject_goal_id")
        declared_bindings = declared_event.get("completion_evidence_bindings")
        if (
            isinstance(declared_goal_id, str)
            and isinstance(declared_bindings, dict)
        ):
            historical_completion_event_by_goal[declared_goal_id] = (
                declared_event
            )
            historical_completion_bindings_by_goal[declared_goal_id] = {
                role: binding
                for role, binding in declared_bindings.items()
                if isinstance(role, str) and isinstance(binding, dict)
            }
    replay_completion_evidence_by_goal: dict[str, list[str]] = {}
    replay_archived_completion_evidence_by_goal: dict[str, list[str]] = {}
    latest_workstream_reopen_event_by_goal: dict[str, dict[str, Any]] = {}
    latest_workstream_impact_event_by_goal: dict[str, dict[str, Any]] = {}
    latest_start_event_by_goal: dict[str, dict[str, Any]] = {}
    final_runtime: dict[str, Any] = {}
    previous_runtime: dict[str, Any] = {}
    previous_blockers: dict[str, Any] = {}
    activated = False
    activation_event: dict[str, Any] | None = None
    activation_event_sha256: str | None = None
    activation_occurred_at: datetime | None = None
    prestart_control_repair_seen = False
    prestart_control_repair_event: dict[str, Any] | None = None
    prestart_repair_first_execution_bound = False
    execution_event_seen = False
    package_completed_seen = False
    pending_reopen_goal_ids: set[str] = set()
    pending_reopen_source_event_by_goal: dict[str, str] = {}
    successor_goal_id_by_superseded: dict[str, str] = {}
    pending_producer_completion_goal_id: str | None = None
    pending_producer_update_event_sha256: str | None = None
    seen_event_ids: set[str] = set()
    used_gate_output_paths: set[str] = set()
    evidence = state.get("completion_evidence_by_goal", {})
    archived_evidence = state.get("archived_completion_evidence_by_goal", {})
    if not isinstance(evidence, dict):
        evidence = {}
    if not isinstance(archived_evidence, dict):
        archived_evidence = {}
    resolution_history = state.get("blocker_resolution_history")
    resolution_by_id: dict[str, dict[str, Any]] = {}
    if not isinstance(resolution_history, list):
        errors.append("Goal graph blocker resolution history must be a list")
        resolution_history = []
    for index, resolution in enumerate(resolution_history):
        if not isinstance(resolution, dict):
            errors.append(
                f"Goal graph blocker resolution {index} must be an object"
            )
            continue
        blocker_id = resolution.get("blocker_id")
        if (
            not isinstance(blocker_id, str)
            or not SAFE_ID_PATTERN.fullmatch(blocker_id)
            or blocker_id in resolution_by_id
        ):
            errors.append(
                f"Goal graph blocker resolution {index} ID is missing or duplicated"
            )
            continue
        resolution_by_id[blocker_id] = resolution
    replay_resolution_ids: list[str] = []
    recorded_blocker_event_by_id: dict[str, str] = {}
    recorded_blocker_event_id_by_id: dict[str, str] = {}
    recorded_blocker_event_time_by_id: dict[str, datetime | None] = {}
    recorded_blocker_by_id: dict[str, dict[str, Any]] = {}
    request_key_owner_by_key: dict[str, tuple[str, str]] = {}
    used_external_action_packet_paths: set[str] = set()
    used_external_action_packet_document_ids: set[str] = set()

    for index, event in enumerate(history, start=1):
        label = f"Goal graph event {index}"
        if not isinstance(event, dict):
            errors.append(f"{label}: event must be an object")
            continue
        event_type = event.get("event_type")
        pre_event_artifact_queue: dict[str, Any] = {}
        if index > 1:
            pre_queue_errors, pre_event_artifact_queue = (
                derive_artifact_work_queue(
                    root,
                    replay_bindings,
                    nodes,
                    replay_statuses,
                )
            )
            errors.extend(
                f"{label}: before event: {error}"
                for error in pre_queue_errors
            )
        assessment_obligation_target: str | None = None
        if index > 1:
            assessment_obligation_target = (
                artifact_assessment_obligation_target(
                    previous_runtime,
                    nodes,
                    replay_statuses,
                )
            )
        bindings_before_event = canonical_binding_snapshot(replay_bindings)
        expected_canonical_status_changes: dict[str, str] = {}
        errors.extend(
            f"{label}: {error}"
            for error in pending_canonical_transaction_order_errors(
                pending_producer_completion_goal_id=(
                    pending_producer_completion_goal_id
                ),
                pending_reopen_goal_ids=pending_reopen_goal_ids,
                event_type=event_type,
                subject_goal_id=event.get("subject_goal_id"),
            )
        )
        if package_completed_seen:
            errors.append(f"{label}: event appears after PACKAGE_COMPLETED")
        if event.get("sequence") != index:
            errors.append(f"{label}: sequence differs")
        if event_type not in ALLOWED_EVENT_TYPES:
            errors.append(f"{label}: event type is invalid")
        if (
            event_type != "PACKAGE_PREPARED"
            and "bootstrap_consumed_policy_gap_pairs" in event
        ):
            errors.append(
                f"{label}: bootstrap consumed policy/Gap pairs are "
                "allowed only on PACKAGE_PREPARED"
            )
        event_id = event.get("event_id")
        if (
            not isinstance(event_id, str)
            or not SAFE_ID_PATTERN.fullmatch(event_id)
            or event_id in seen_event_ids
        ):
            errors.append(f"{label}: event ID is missing or duplicated")
        else:
            seen_event_ids.add(event_id)
        if event.get("previous_event_sha256") != previous_hash:
            errors.append(f"{label}: previous event hash differs")
        actual_hash = event_sha256(event)
        if event.get("event_sha256") != actual_hash:
            errors.append(f"{label}: event SHA-256 differs")
        if event_type == "PACKAGE_ACTIVATED" and (
            not isinstance(
                EXPECTED_PACKAGE_ACTIVATION_EVENT_SHA256,
                str,
            )
            or not SHA256_PATTERN.fullmatch(
                EXPECTED_PACKAGE_ACTIVATION_EVENT_SHA256
            )
            or actual_hash
            != EXPECTED_PACKAGE_ACTIVATION_EVENT_SHA256
        ):
            errors.append(
                f"{label}: PACKAGE_ACTIVATED lacks the exact checker "
                "event trust anchor"
            )
        if event.get("static_plan_manifest_sha256") != state.get(
            "static_plan_manifest_sha256"
        ):
            errors.append(f"{label}: static manifest binding differs")
        event_references = event.get("evidence_refs")
        if not isinstance(event_references, list) or any(
            not isinstance(reference, str) or not reference
            for reference in event_references
        ):
            errors.append(f"{label}: evidence_refs must be a string list")
            event_references = []
        current_packet_binding = event.get(
            "external_action_packet_binding"
        )
        current_packet_document_id = (
            current_packet_binding.get("document_id")
            if isinstance(current_packet_binding, dict)
            and isinstance(
                current_packet_binding.get("document_id"),
                str,
            )
            and current_packet_binding.get("document_id").strip()
            else None
        )
        packet_document_ids_through_event = set(
            used_external_action_packet_document_ids
        )
        if current_packet_document_id is not None:
            packet_document_ids_through_event.add(
                current_packet_document_id
            )

        previous_event_time = previous_time
        occurred_at = parse_iso_datetime(event.get("occurred_at"))
        if occurred_at is None:
            errors.append(f"{label}: occurred_at is invalid")
        else:
            if event.get("occurred_on") != occurred_at.date().isoformat():
                errors.append(f"{label}: occurred_on differs from occurred_at")
            if previous_time is not None and occurred_at <= previous_time:
                errors.append(f"{label}: occurred_at is not strictly increasing")
            if cutoff is not None and occurred_at > cutoff:
                errors.append(f"{label}: event postdates validation cutoff")
            previous_time = occurred_at

        if event_type in {"PACKAGE_PREPARED", "CANONICAL_BINDINGS_UPDATED"}:
            snapshot_errors, next_bindings = validate_canonical_binding_snapshot(
                root,
                event.get("canonical_binding_snapshot_after"),
                label=label,
            )
            errors.extend(snapshot_errors)
            errors.extend(
                f"{label}: {error}"
                for error in validate_gap_backlog_pair(root, next_bindings)
            )
            snapshot_mapping_errors, snapshot_mapping = (
                load_policy_gap_mapping(root, next_bindings)
            )
            errors.extend(
                f"{label}: {error}" for error in snapshot_mapping_errors
            )
            if not snapshot_mapping_errors:
                errors.extend(
                    f"{label}: {error}"
                    for error in validate_policy_scope_against_mapping(
                        root,
                        next_bindings,
                        snapshot_mapping,
                    )
                )
            if event_type == "PACKAGE_PREPARED":
                if index != 1:
                    errors.append(f"{label}: PACKAGE_PREPARED must be first")
                if event.get(
                    "bootstrap_consumed_policy_gap_pairs"
                ) != EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS:
                    errors.append(
                        f"{label}: prepared bootstrap consumed policy/Gap "
                        "pair contract differs"
                    )
            else:
                changed_roles = sorted(
                    role
                    for role in set(replay_bindings) | set(next_bindings)
                    if replay_bindings.get(role) != next_bindings.get(role)
                )
                if not changed_roles:
                    errors.append(f"{label}: canonical binding update is a no-op")
                if event.get("changed_binding_roles") != changed_roles:
                    errors.append(f"{label}: changed canonical binding roles differ")
                for ledger_role in sorted(
                    set(changed_roles) & NON_RETROACTIVE_LEDGER_ROLES
                ):
                    errors.extend(
                        validate_nonretroactive_ledger_update(
                            root,
                            label=label,
                            role=ledger_role,
                            binding_before=bindings_before_event.get(
                                ledger_role
                            ),
                            binding_after=next_bindings.get(ledger_role),
                        )
                    )
                if "AUTHORITY_ROSTER" in changed_roles:
                    errors.extend(
                        validate_authority_roster_rotation(
                            root,
                            label=label,
                            binding_before=bindings_before_event.get(
                                "AUTHORITY_ROSTER"
                            ),
                            binding_after=next_bindings.get(
                                "AUTHORITY_ROSTER"
                            ),
                            occurred_at=occurred_at,
                        )
                    )
                subject_errors, changed_subjects_by_role = (
                    canonical_changed_subject_ids_by_role(
                        root,
                        changed_roles=changed_roles,
                        bindings_before=bindings_before_event,
                        bindings_after=next_bindings,
                    )
                )
                errors.extend(f"{label}: {error}" for error in subject_errors)
                if event.get("changed_subject_ids_by_role") != (
                    changed_subjects_by_role
                ):
                    errors.append(
                        f"{label}: canonical changed subject IDs differ"
                    )
                known_subject_ids = set(snapshot_mapping) | set(
                    snapshot_mapping.values()
                )
                out_of_scope_subject_ids = sorted(
                    {
                        identifier
                        for role, identifiers in (
                            changed_subjects_by_role.items()
                        )
                        for identifier in identifiers
                        if identifier != "*"
                        and role not in NON_RETROACTIVE_LEDGER_ROLES
                        and identifier not in known_subject_ids
                    }
                )
                if out_of_scope_subject_ids:
                    errors.append(
                        f"{label}: canonical change introduces subjects outside "
                        "the static policy/Gap scope "
                        f"{out_of_scope_subject_ids}; "
                        "STATIC_GRAPH_CHANGE_REQUIRES_SUCCESSOR_PACKAGE"
                    )
                affected_goal_roles = changed_binding_affected_goals(
                    changed_roles=set(changed_roles),
                    changed_subject_ids_by_role=changed_subjects_by_role,
                    nodes=nodes,
                    statuses=replay_statuses,
                    completion_bindings_by_goal=completion_bindings_by_goal,
                    latest_start_event_by_goal=latest_start_event_by_goal,
                )
                directly_affected_goal_ids = set(affected_goal_roles)
                producer_goal_id = event.get("produced_by_goal_id")
                produced_roles = string_list(
                    event.get("produced_binding_roles")
                )
                if producer_goal_id in {"", None}:
                    if produced_roles:
                        errors.append(
                            f"{label}: produced binding roles lack a producer"
                        )
                    if event.get("producer_completion_receipt_binding") not in (
                        None,
                        "",
                    ):
                        errors.append(
                            f"{label}: producer receipt lacks a producer"
                        )
                    producer_goal_id = None
                else:
                    producer_node = nodes.get(str(producer_goal_id), {})
                    if (
                        replay_statuses.get(str(producer_goal_id))
                        != "IN_PROGRESS"
                        or producer_node.get("goal_kind") != "WORK_ITEM"
                        or event.get("focus_goal_id") != producer_goal_id
                        or not produced_roles
                        or produced_roles != sorted(set(produced_roles))
                        or not set(produced_roles).issubset(changed_roles)
                    ):
                        errors.append(
                            f"{label}: canonical binding producer contract differs"
                        )
                    errors.extend(
                        validate_internal_canonical_producer(
                            root,
                            label=label,
                            event=event,
                            producer_goal_id=str(producer_goal_id),
                            producer_node=producer_node,
                            produced_roles=produced_roles,
                            changed_subjects_by_role=(
                                changed_subjects_by_role
                            ),
                            bindings_before=bindings_before_event,
                            bindings_after=next_bindings,
                            paths_by_id=paths_by_id,
                            nodes=nodes,
                            statuses_before_update=replay_statuses,
                            latest_start_event=latest_start_event_by_goal.get(
                                str(producer_goal_id)
                            ),
                            occurred_at=occurred_at,
                        )
                    )
                    producer_impact_roles = affected_goal_roles.get(
                        str(producer_goal_id),
                        set(),
                    )
                    if producer_impact_roles.issubset(set(produced_roles)):
                        affected_goal_roles.pop(str(producer_goal_id), None)
                    else:
                        errors.append(
                            f"{label}: producer changed an input outside its "
                            "declared output roles"
                        )
                    pending_producer_completion_goal_id = str(
                        producer_goal_id
                    )
                    pending_producer_update_event_sha256 = actual_hash
                if "IMPLEMENTATION_BACKLOG" in changed_roles:
                    backlog_before_errors, backlog_before_payload = (
                        load_bound_json_payload(
                            root,
                            bindings_before_event.get(
                                "IMPLEMENTATION_BACKLOG"
                            ),
                            label=f"{label}: IMPLEMENTATION_BACKLOG before",
                        )
                    )
                    backlog_after_errors, backlog_after_payload = (
                        load_bound_json_payload(
                            root,
                            next_bindings.get("IMPLEMENTATION_BACKLOG"),
                            label=f"{label}: IMPLEMENTATION_BACKLOG after",
                        )
                    )
                    errors.extend(backlog_before_errors)
                    errors.extend(backlog_after_errors)
                    operational_delta = (
                        not backlog_before_errors
                        and not backlog_after_errors
                        and backlog_operational_projection(
                            backlog_before_payload
                        )
                        != backlog_operational_projection(
                            backlog_after_payload
                        )
                    )
                    if (
                        operational_delta
                        and (
                            producer_goal_id is None
                            or nodes.get(
                                str(producer_goal_id),
                                {},
                            ).get("work_item_type")
                            != "POLICY_GAP_WORK"
                        )
                    ):
                        errors.append(
                            f"{label}: Backlog progress/next action may only "
                            "change through its owning POLICY_GAP_WORK"
                        )
                directly_affected_goal_ids = set(affected_goal_roles)
                affected_goal_roles = expand_affected_goal_dependency_closure(
                    nodes=nodes,
                    statuses=replay_statuses,
                    directly_affected_goal_roles=affected_goal_roles,
                )
                if event.get("impact_closure_goal_ids") != sorted(
                    affected_goal_roles
                ):
                    errors.append(
                        f"{label}: canonical impact dependency closure differs"
                    )
                semantic_changed_roles = sorted(
                    role
                    for role, subject_ids in changed_subjects_by_role.items()
                    if subject_ids
                )
                semantic_changed_roles.extend(
                    role
                    for role in sorted(
                        set(changed_roles)
                        & NON_RETROACTIVE_LEDGER_ROLES
                    )
                    if bindings_before_event.get(role, {}).get("file_sha256")
                    != next_bindings.get(role, {}).get("file_sha256")
                )
                semantic_changed_roles = sorted(
                    set(semantic_changed_roles)
                )
                roles_requiring_authorization = sorted(
                    set(semantic_changed_roles) - set(produced_roles)
                )
                roles_requiring_authorization = sorted(
                    set(roles_requiring_authorization)
                    | {
                        role
                        for role in produced_roles
                        if (
                            "*"
                            in changed_subjects_by_role.get(role, [])
                            or canonical_role_requires_external_authorization(
                                root,
                                role,
                                bindings_before_event.get(role),
                                next_bindings.get(role),
                            )
                        )
                    }
                )
                errors.extend(
                    validate_canonical_update_authorization(
                        root,
                        label=label,
                        event=event,
                        roles_requiring_authorization=(
                            roles_requiring_authorization
                        ),
                        bindings_before=bindings_before_event,
                        bindings_after=next_bindings,
                        occurred_at=occurred_at,
                    )
                )
                affected_goal_ids = set(affected_goal_roles)
                dispositions = event.get(
                    "impact_disposition_by_goal"
                )
                if (
                    not isinstance(dispositions, dict)
                    or set(dispositions) != affected_goal_ids
                ):
                    errors.append(
                        f"{label}: canonical change impact disposition set differs"
                    )
                    dispositions = {}
                for goal_id in sorted(affected_goal_ids):
                    disposition = dispositions.get(goal_id)
                    if not isinstance(disposition, dict):
                        errors.append(
                            f"{label}: {goal_id} impact disposition is malformed"
                        )
                        continue
                    result = disposition.get("result")
                    if result == "UNAFFECTED":
                        if nodes.get(goal_id, {}).get(
                            "goal_kind"
                        ) != "WORK_ITEM":
                            errors.append(
                                f"{label}: {goal_id} container impact must use "
                                "REOPEN_CONTAINER"
                            )
                            continue
                        if (
                            "DEPENDENCY_CLOSURE"
                            in affected_goal_roles.get(goal_id, set())
                        ):
                            errors.append(
                                f"{label}: {goal_id} explicit dependency "
                                "invalidation cannot be declared unaffected"
                            )
                            continue
                        if not goal_allows_unaffected_disposition(
                            nodes.get(goal_id, {}),
                            replay_statuses.get(goal_id),
                            affected_goal_roles.get(goal_id, set()),
                        ):
                            errors.append(
                                f"{label}: {goal_id} open Work Item cannot be "
                                "declared unaffected after its input changed"
                            )
                            continue
                        completion_event = completion_event_by_goal.get(
                            goal_id
                        )
                        if not isinstance(completion_event, dict):
                            errors.append(
                                f"{label}: {goal_id} completion event is missing"
                            )
                            continue
                        errors.extend(
                            validate_unaffected_impact_assessment(
                                root,
                                label=label,
                                goal_id=goal_id,
                                completion_event=completion_event,
                                changed_roles=changed_roles,
                                bindings_before=bindings_before_event,
                                bindings_after=next_bindings,
                                disposition=disposition,
                                occurred_at=occurred_at,
                            )
                        )
                    elif result == "REOPEN_REQUIRED":
                        if nodes.get(goal_id, {}).get("goal_kind") != "WORK_ITEM":
                            errors.append(
                                f"{label}: {goal_id} container impact must use "
                                "REOPEN_CONTAINER"
                            )
                        elif replay_statuses.get(goal_id) in {
                            "AWAITING_USER",
                            "AWAITING_EXTERNAL",
                            "BLOCKED",
                        }:
                            errors.append(
                                f"{label}: {goal_id} blocker must be resolved "
                                "before canonical input replacement"
                            )
                        else:
                            pending_reopen_goal_ids.add(goal_id)
                            pending_reopen_source_event_by_goal[goal_id] = (
                                actual_hash
                            )
                    elif result == "REOPEN_CONTAINER":
                        goal_kind = nodes.get(goal_id, {}).get("goal_kind")
                        if (
                            goal_kind != "WORKSTREAM"
                            or replay_statuses.get(goal_id)
                            != "COMPLETE_AT_TARGET"
                        ):
                            errors.append(
                                f"{label}: {goal_id} is not a completed "
                                "Workstream that may be reopened"
                            )
                        else:
                            target_status = workstream_reopen_target_status(
                                affected_goal_roles.get(goal_id, set())
                            )
                            if disposition.get("target_status") != target_status:
                                errors.append(
                                    f"{label}: {goal_id} reopened Workstream "
                                    "target status differs"
                                )
                            expected_canonical_status_changes[
                                goal_id
                            ] = target_status
                            latest_workstream_impact_event_by_goal[
                                goal_id
                            ] = event
                    elif result == "REVALIDATION_REFRESH_REQUIRED":
                        current_status = replay_statuses.get(goal_id)
                        if (
                            nodes.get(goal_id, {}).get("goal_kind")
                            != "WORKSTREAM"
                            or current_status in TERMINAL_STATUSES
                        ):
                            errors.append(
                                f"{label}: {goal_id} is not an open "
                                "Workstream requiring revalidation refresh"
                            )
                        else:
                            target_status = (
                                "PLANNED"
                                if (
                                    current_status == "READY"
                                    and "START_DEPENDENCY_INVALIDATED"
                                    in affected_goal_roles.get(
                                        goal_id,
                                        set(),
                                    )
                                )
                                else current_status
                            )
                            if (
                                disposition.get("target_status")
                                != target_status
                            ):
                                errors.append(
                                    f"{label}: {goal_id} revalidation "
                                    "refresh target status differs"
                                )
                            if target_status != current_status:
                                expected_canonical_status_changes[
                                    goal_id
                                ] = str(target_status)
                            latest_workstream_impact_event_by_goal[
                                goal_id
                            ] = event
                    elif result == "DEPENDENCY_INVALIDATED":
                        if (
                            goal_id in directly_affected_goal_ids
                            or replay_statuses.get(goal_id) != "READY"
                            or nodes.get(goal_id, {}).get("goal_kind")
                            != "WORK_ITEM"
                            or "DEPENDENCY_CLOSURE"
                            not in affected_goal_roles.get(goal_id, set())
                            or "WORK_ITEM_DEPENDENCY_REVISION_REQUIRED"
                            in affected_goal_roles.get(goal_id, set())
                            or disposition.get("target_status") != "PLANNED"
                        ):
                            errors.append(
                                f"{label}: {goal_id} dependency invalidation "
                                "disposition differs"
                            )
                        else:
                            expected_canonical_status_changes[
                                goal_id
                            ] = "PLANNED"
                    else:
                        errors.append(
                            f"{label}: {goal_id} impact disposition is invalid"
                        )
                expected_reopened_completion_events = {
                    goal_id: completion_event_by_goal.get(goal_id, {}).get(
                        "event_sha256"
                    )
                    for goal_id, target_status
                    in expected_canonical_status_changes.items()
                    if (
                        replay_statuses.get(goal_id)
                        == "COMPLETE_AT_TARGET"
                        and target_status in {"READY", "PLANNED"}
                    )
                }
                if event.get(
                    "reopened_completion_event_sha256_by_goal"
                ) != expected_reopened_completion_events:
                    errors.append(
                        f"{label}: reopened Workstream completion binding differs"
                    )
                if event.get("status_changes") != (
                    expected_canonical_status_changes
                ):
                    errors.append(
                        f"{label}: canonical update Workstream reopen set differs"
                    )
            replay_bindings = next_bindings
        elif "canonical_binding_snapshot_after" in event:
            errors.append(f"{label}: canonical binding snapshot is not allowed")

        changes = event.get("status_changes")
        if not isinstance(changes, dict):
            errors.append(f"{label}: status_changes must be an object")
            changes = {}
        before = dict(replay_statuses)
        for goal_id, status in changes.items():
            if goal_id not in nodes:
                errors.append(f"{label}: status target is missing: {goal_id}")
            if status not in RUNTIME_STATUSES:
                errors.append(f"{label}: runtime status is invalid: {status}")
            previous_status = before.get(goal_id)
            allowed = status in ALLOWED_STATUS_TRANSITIONS.get(
                str(previous_status), set()
            )
            if (
                event_type == "PACKAGE_COMPLETED"
                and goal_id == EXPECTED_ROOT_GOAL_ID
                and previous_status == "READY"
                and status == "COMPLETE_AT_TARGET"
            ):
                allowed = True
            if (
                event_type == "GOAL_COMPLETED"
                and nodes.get(goal_id, {}).get("goal_kind") == "WORKSTREAM"
                and previous_status == "READY"
                and status == "COMPLETE_AT_TARGET"
            ):
                allowed = True
            if (
                event_type == "CANONICAL_BINDINGS_UPDATED"
                and nodes.get(goal_id, {}).get("goal_kind") == "WORKSTREAM"
                and previous_status == "COMPLETE_AT_TARGET"
                and status in {"READY", "PLANNED"}
                and expected_canonical_status_changes.get(goal_id) == status
            ):
                allowed = True
            if (
                event_type == "CANONICAL_BINDINGS_UPDATED"
                and previous_status == "READY"
                and status == "PLANNED"
                and expected_canonical_status_changes.get(goal_id) == "PLANNED"
            ):
                allowed = True
            if previous_status is not None and not allowed:
                errors.append(
                    f"{label}: illegal status transition "
                    f"{goal_id} {previous_status}->{status}"
                )
            if previous_status == status:
                errors.append(f"{label}: no-op status change is prohibited: {goal_id}")
            replay_statuses[goal_id] = status
            if status == "SUPERSEDED":
                replay_archived_completion_evidence_by_goal[goal_id] = list(
                    replay_completion_evidence_by_goal.get(goal_id, [])
                )
            if (
                previous_status == "COMPLETE_AT_TARGET"
                and status != "COMPLETE_AT_TARGET"
            ):
                replay_completion_evidence_by_goal.pop(goal_id, None)

        if index == 1:
            if event_type != "PACKAGE_PREPARED":
                errors.append("Goal graph first event must be PACKAGE_PREPARED")
            if event.get("from_status") != "":
                errors.append(f"{label}: preparation from_status must be empty")
            focus_id = event.get("focus_goal_id")
            if event.get("to_status") != replay_statuses.get(focus_id):
                errors.append(f"{label}: preparation to_status differs")
            initial_node = nodes.get(EXPECTED_INITIAL_FOCUS_GOAL_ID, {})
            source_snapshot = materialization_source_snapshot(initial_node)
            if replay_bindings.get(str(source_snapshot.get("role"))) != source_snapshot:
                errors.append(
                    f"{label}: initial Work Item source differs from canonical snapshot"
                )
            bootstrap_source_roles = {
                role
                for record in EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS
                for role in string_list(
                    record.get("source_evidence_roles")
                )
            }
            if any(
                not isinstance(replay_bindings.get(role), dict)
                for role in bootstrap_source_roles
            ):
                errors.append(
                    f"{label}: bootstrap consumed policy/Gap source "
                    "bindings are missing from the prepared snapshot"
                )
            initial_complete_ids = {
                goal_id
                for goal_id, status in changes.items()
                if status == "COMPLETE_AT_TARGET"
            }
            if state.get("activation_status") == "READY_NOT_ACTIVATED":
                if (
                    initial_complete_ids
                    != set(
                        EXPECTED_PREACTIVATION_COMPLETION_EVIDENCE_BY_GOAL
                    )
                ):
                    errors.append(
                        f"{label}: preactivation completed Goal set differs "
                        "from checker contract"
                    )
                for goal_id in sorted(initial_complete_ids):
                    refs = string_list(evidence.get(goal_id))
                    if (
                        evidence.get(goal_id) != refs
                        or refs
                        != EXPECTED_PREACTIVATION_COMPLETION_EVIDENCE_BY_GOAL.get(
                            goal_id
                        )
                        or any(
                            reference not in replay_bindings
                            for reference in refs
                        )
                    ):
                        errors.append(
                            f"{label}: {goal_id} preactivation completion "
                            "evidence differs"
                        )
                    replay_completion_evidence_by_goal[goal_id] = refs
        else:
            if not activated and event_type != "PACKAGE_ACTIVATED":
                errors.append(
                    f"{label}: execution event occurred before PACKAGE_ACTIVATED"
                )
            if event_type == "PACKAGE_ACTIVATED":
                if activated:
                    errors.append(f"{label}: package activation is duplicated")
                if changes:
                    errors.append(
                        f"{label}: PACKAGE_ACTIVATED must not rewrite Goal status"
                    )
                imported_ids = {
                    goal_id
                    for goal_id, status in before.items()
                    if status == "COMPLETE_AT_TARGET"
                }
                imported_refs = event.get(
                    "imported_completion_evidence_refs_by_goal"
                )
                imported_bindings = event.get(
                    "imported_completion_evidence_bindings_by_goal"
                )
                imported_events = event.get(
                    "imported_completion_event_sha256_by_goal"
                )
                if (
                    not isinstance(imported_refs, dict)
                    or set(imported_refs) != imported_ids
                    or imported_refs
                    != EXPECTED_PREACTIVATION_COMPLETION_EVIDENCE_BY_GOAL
                    or not isinstance(imported_bindings, dict)
                    or set(imported_bindings) != imported_ids
                    or not isinstance(imported_events, dict)
                    or set(imported_events) != imported_ids
                ):
                    errors.append(
                        f"{label}: activation imported completion set differs"
                    )
                    imported_refs = {}
                    imported_bindings = {}
                    imported_events = {}
                for goal_id in sorted(imported_ids):
                    refs = string_list(imported_refs.get(goal_id))
                    expected_bindings = {
                        reference: replay_bindings.get(reference)
                        for reference in refs
                    }
                    if (
                        imported_refs.get(goal_id) != refs
                        or imported_bindings.get(goal_id)
                        != expected_bindings
                        or imported_events.get(goal_id)
                        != completion_event_by_goal.get(goal_id, {}).get(
                            "event_sha256"
                        )
                    ):
                        errors.append(
                            f"{label}: {goal_id} activation imported "
                            "completion evidence differs"
                        )
                    replay_completion_evidence_by_goal[goal_id] = refs
                    completion_bindings_by_goal[goal_id] = {
                        reference: binding
                        for reference, binding in expected_bindings.items()
                        if isinstance(binding, dict)
                    }
                errors.extend(
                    validate_package_activation_receipts(
                        root,
                        label=label,
                        event=event,
                        occurred_at=occurred_at,
                        expected_live_repository_snapshot=(
                            live_repository_snapshot
                            if index == len(history)
                            else None
                        ),
                        used_output_paths=used_gate_output_paths,
                    )
                )
                activated = True
                activation_event = event
                activation_event_sha256 = actual_hash
                activation_occurred_at = occurred_at
            elif event_type == "PRESTART_CONTROL_REPAIR_COMMITTED":
                previous_event = (
                    history[index - 2]
                    if index >= 2
                    and isinstance(history[index - 2], dict)
                    else {}
                )
                previous_focus = previous_runtime.get("focus_goal_id")
                previous_focus_status = before.get(str(previous_focus))
                if (
                    prestart_control_repair_seen
                    or index != 3
                    or previous_event.get("event_type")
                    != "PACKAGE_ACTIVATED"
                    or execution_event_seen
                ):
                    errors.append(
                        f"{label}: prestart control repair must occur "
                        "exactly once immediately after activation and "
                        "before execution"
                    )
                if set(event) != PRESTART_CONTROL_REPAIR_EVENT_FIELDS:
                    errors.append(
                        f"{label}: prestart control repair event fields differ"
                    )
                if (
                    changes
                    or event.get("from_status") != previous_focus_status
                    or event.get("to_status") != previous_focus_status
                    or event.get("previous_focus_goal_id")
                    != previous_focus
                    or event.get("focus_goal_id") != previous_focus
                    or event.get("runtime_after") != previous_runtime
                    or event.get("blockers_after") != previous_blockers
                    or event.get("blocker_resolution_ids_after")
                    != replay_resolution_ids
                    or event_references != []
                    or event.get("source_checkpoint_version")
                    != checkpoint.get("schema_version")
                ):
                    errors.append(
                        f"{label}: prestart control repair must be a "
                        "Goal/runtime/blocker/completion semantic no-op"
                    )
                errors.extend(
                    validate_prestart_control_repair_receipt(
                        root,
                        label=label,
                        event=event,
                        activation_event=activation_event,
                        activation_occurred_at=activation_occurred_at,
                        occurred_at=occurred_at,
                        checkpoint=checkpoint,
                        live_repository_snapshot=(
                            live_repository_snapshot
                        ),
                        is_tail=index == len(history),
                        used_output_paths=used_gate_output_paths,
                    )
                )
                prestart_control_repair_seen = True
                prestart_control_repair_event = event
            elif event_type == "CANONICAL_BINDINGS_UPDATED":
                if changes != expected_canonical_status_changes:
                    errors.append(
                        f"{label}: canonical binding update may only reopen "
                        "affected Workstreams or invalidate ready dependents"
                    )
            elif event_type == "GOAL_MATERIALIZED":
                subject_id = event.get("materialized_goal_id")
                if not isinstance(subject_id, str) or subject_id not in nodes:
                    errors.append(f"{label}: materialized Goal ID is missing")
                else:
                    node = nodes[subject_id]
                    if subject_id in before:
                        errors.append(f"{label}: materialized Goal already existed")
                    if node.get("goal_kind") != "WORK_ITEM":
                        errors.append(
                            f"{label}: only a Work Item may be materialized"
                        )
                    if set(changes) != {subject_id}:
                        errors.append(
                            f"{label}: materialization must change exactly its Goal"
                        )
                    if changes.get(subject_id) != "PLANNED":
                        errors.append(
                            f"{label}: GOAL_MATERIALIZED must create a PLANNED Goal"
                        )
                    source_snapshot = materialization_source_snapshot(event)
                    if replay_bindings.get(str(source_snapshot.get("role"))) != (
                        source_snapshot
                    ):
                        errors.append(
                            f"{label}: materialization source is not canonical at this event"
                        )
                    parent_id = node.get("parent_goal_id")
                    if (
                        parent_id not in before
                        or nodes.get(str(parent_id), {}).get("goal_kind")
                        not in {"WORKSTREAM", "MASTER"}
                        or before.get(str(parent_id)) != "READY"
                    ):
                        errors.append(
                            f"{label}: materialized Goal parent is not an open READY container"
                        )
                    predecessor_id = event.get("predecessor_goal_id")
                    if (
                        not isinstance(predecessor_id, str)
                        or predecessor_id not in before
                        or nodes.get(predecessor_id, {}).get("goal_kind")
                        != "WORK_ITEM"
                    ):
                        errors.append(
                            f"{label}: materialization predecessor is not an existing Work Item"
                        )
                    if node.get("supersedes_goal_id") or event.get(
                        "supersedes_goal_id"
                    ):
                        errors.append(
                            f"{label}: ordinary materialization cannot introduce a successor"
                        )
                    if node.get(
                        "work_item_type"
                    ) == "BLOCKER_OR_EXTERNAL_RECEIPT":
                        source_blocker_ids = string_list(
                            node.get("source_blocker_ids")
                        )
                        active_blockers, duplicate_blocker_ids = (
                            legacy.blocker_records_by_id(previous_blockers)
                        )
                        expected_blocker_bindings = {
                            blocker_id: active_blockers.get(blocker_id)
                            for blocker_id in source_blocker_ids
                        }
                        if (
                            duplicate_blocker_ids
                            or not source_blocker_ids
                            or any(
                                expected_blocker_bindings[blocker_id] is None
                                for blocker_id in source_blocker_ids
                            )
                            or event.get("source_blocker_bindings")
                            != expected_blocker_bindings
                        ):
                            errors.append(
                                f"{label}: external receipt Goal is not bound "
                                "to active blocker snapshots"
                            )
                    if node.get("work_item_type") == "ARTIFACT_WORK":
                        errors.extend(
                            validate_artifact_trigger_event_bindings(
                                root,
                                label=label,
                                event=event,
                                node=node,
                            )
                        )
                        target_ids = artifact_work_subject_ids(node)
                        if (
                            node.get("artifact_work_reason")
                            != "ACTIVE_EVENT_UPDATE"
                            and (
                                len(target_ids) != 1
                                or target_ids[0]
                                != pre_event_artifact_queue.get(
                                    "next_assessment_target_id"
                                )
                                or node.get("artifact_work_reason")
                                != pre_event_artifact_queue.get(
                                    "next_assessment_required_work_reason"
                                )
                            )
                        ):
                            errors.append(
                                f"{label}: ARTIFACT_WORK materialization does "
                                "not satisfy the deterministic assessment target"
                            )
                if event.get("from_status") != "":
                    errors.append(
                        f"{label}: materialization from_status must be empty"
                    )
                if event.get("to_status") != changes.get(subject_id):
                    errors.append(f"{label}: materialization to_status differs")
            elif event_type == "GOAL_SUPERSEDED":
                subject_id = event.get("subject_goal_id")
                successor_id = event.get("materialized_goal_id")
                subject_node = nodes.get(str(subject_id), {})
                successor_node = nodes.get(str(successor_id), {})
                if (
                    not isinstance(subject_id, str)
                    or subject_id not in before
                    or subject_node.get("goal_kind") != "WORK_ITEM"
                    or before.get(subject_id) in {None, "SUPERSEDED"}
                ):
                    errors.append(
                        f"{label}: GOAL_SUPERSEDED target must be an active "
                        "or completed Work Item"
                    )
                if (
                    not isinstance(successor_id, str)
                    or successor_id not in nodes
                    or successor_id in before
                    or successor_node.get("goal_kind") != "WORK_ITEM"
                ):
                    errors.append(
                        f"{label}: GOAL_SUPERSEDED successor must be a new Work Item"
                    )
                if set(changes) != {subject_id, successor_id}:
                    errors.append(
                        f"{label}: GOAL_SUPERSEDED must atomically replace one Work Item"
                    )
                if changes.get(subject_id) != "SUPERSEDED" or changes.get(
                    successor_id
                ) != "PLANNED":
                    errors.append(
                        f"{label}: GOAL_SUPERSEDED status changes differ"
                    )
                if event.get("from_status") != before.get(subject_id):
                    errors.append(f"{label}: GOAL_SUPERSEDED from_status differs")
                if event.get("to_status") != "SUPERSEDED":
                    errors.append(f"{label}: GOAL_SUPERSEDED to_status differs")
                if successor_node:
                    if (
                        successor_node.get("supersedes_goal_id") != subject_id
                        or successor_node.get("work_item_id")
                        != subject_node.get("work_item_id")
                    ):
                        errors.append(
                            f"{label}: successor lineage or work item ID differs"
                        )
                    if not successor_semantic_scope_matches(
                        subject_node,
                        successor_node,
                    ):
                        errors.append(
                            f"{label}: successor semantic scope differs "
                            "from the superseded revision"
                        )
                    subject_dependencies = set(
                        string_list(subject_node.get("start_requires"))
                        + string_list(
                            subject_node.get("completion_requires")
                        )
                    )
                    unresolved_dependency_reopens = (
                        subject_dependencies & pending_reopen_goal_ids
                    )
                    if unresolved_dependency_reopens:
                        errors.append(
                            f"{label}: successor dependencies must be revised "
                            "before their downstream Goal"
                        )
                    expected_start_requires = [
                        successor_goal_id_by_superseded.get(
                            dependency,
                            dependency,
                        )
                        for dependency in string_list(
                            subject_node.get("start_requires")
                        )
                    ]
                    expected_completion_requires = [
                        successor_goal_id_by_superseded.get(
                            dependency,
                            dependency,
                        )
                        for dependency in string_list(
                            subject_node.get("completion_requires")
                        )
                    ]
                    if (
                        successor_node.get("start_requires")
                        != expected_start_requires
                        or successor_node.get("completion_requires")
                        != expected_completion_requires
                    ):
                        errors.append(
                            f"{label}: successor dependency revision differs"
                        )
                    parent_id = successor_node.get("parent_goal_id")
                    pending_source_event = (
                        pending_reopen_source_event_by_goal.get(
                            str(subject_id)
                        )
                    )
                    if (
                        parent_id not in before
                        or nodes.get(str(parent_id), {}).get("goal_kind")
                        not in {"WORKSTREAM", "MASTER"}
                        or not successor_parent_accepts_revision(
                            before.get(str(parent_id)),
                            canonical_change_successor=(
                                pending_source_event is not None
                            ),
                        )
                    ):
                        errors.append(
                            f"{label}: successor parent cannot accept this revision"
                        )
                    predecessor_id = event.get("predecessor_goal_id")
                    if (
                        not isinstance(predecessor_id, str)
                        or predecessor_id not in before
                        or nodes.get(predecessor_id, {}).get("goal_kind")
                        != "WORK_ITEM"
                    ):
                        errors.append(
                            f"{label}: successor predecessor is not an existing Work Item"
                        )
                    source_snapshot = materialization_source_snapshot(event)
                    if replay_bindings.get(
                        str(source_snapshot.get("role"))
                    ) != source_snapshot:
                        errors.append(
                            f"{label}: successor materialization source is not canonical"
                        )
                    reopen_refs = string_list(
                        successor_node.get("reopen_evidence_refs")
                    )
                    if event_references != reopen_refs:
                        errors.append(
                            f"{label}: successor reopen evidence references differ"
                        )
                    if pending_source_event is not None:
                        if (
                            event.get("canonical_update_event_sha256")
                            != pending_source_event
                            or successor_node.get("reopen_reason")
                            != "CANONICAL_INPUT_CHANGED"
                            or reopen_refs
                        ):
                            errors.append(
                                f"{label}: canonical-change successor binding differs"
                            )
                    else:
                        errors.append(
                            f"{label}: standalone defect/evidence successor "
                            "is prohibited; first record a canonical "
                            "Gap/Backlog impact transaction or create a "
                            "versioned successor package"
                        )
                    if successor_node.get("work_item_type") == "ARTIFACT_WORK":
                        errors.extend(
                            validate_artifact_trigger_event_bindings(
                                root,
                                label=label,
                                event=event,
                                node=successor_node,
                            )
                        )
                if isinstance(subject_id, str):
                    pending_reopen_goal_ids.discard(subject_id)
                    pending_reopen_source_event_by_goal.pop(
                        subject_id,
                        None,
                    )
                    if isinstance(successor_id, str):
                        successor_goal_id_by_superseded[
                            subject_id
                        ] = successor_id
            elif event_type == "WORK_SESSION_RESUMED":
                subject_id = event.get("subject_goal_id")
                previous_execution_event = latest_start_event_by_goal.get(
                    str(subject_id)
                )
                if (
                    not isinstance(subject_id, str)
                    or subject_id not in before
                    or nodes.get(subject_id, {}).get("goal_kind")
                    != "WORK_ITEM"
                    or before.get(subject_id) != "IN_PROGRESS"
                    or previous_runtime.get("focus_goal_id") != subject_id
                ):
                    errors.append(
                        f"{label}: WORK_SESSION_RESUMED target is not the "
                        "current IN_PROGRESS Work Item"
                    )
                if changes:
                    errors.append(
                        f"{label}: WORK_SESSION_RESUMED must not change status"
                    )
                if (
                    event.get("from_status") != "IN_PROGRESS"
                    or event.get("to_status") != "IN_PROGRESS"
                ):
                    errors.append(
                        f"{label}: WORK_SESSION_RESUMED status boundary differs"
                    )
                if (
                    not isinstance(previous_execution_event, dict)
                    or event.get("previous_execution_session_event_sha256")
                    != previous_execution_event.get("event_sha256")
                ):
                    errors.append(
                        f"{label}: WORK_SESSION_RESUMED predecessor differs"
                    )
                for field in (
                    "start_evidence_bindings",
                    "start_evidence_provenance",
                ):
                    if event.get(field) != (
                        previous_execution_event.get(field)
                        if isinstance(previous_execution_event, dict)
                        else None
                    ):
                        errors.append(
                            f"{label}: WORK_SESSION_RESUMED {field} differs"
                        )
                previous_execution_time = parse_iso_datetime(
                    previous_execution_event.get("occurred_at")
                    if isinstance(previous_execution_event, dict)
                    else None
                )
                errors.extend(
                    validate_implementation_start_gate_receipt(
                        root,
                        label=label,
                        event=event,
                        goal_id=str(subject_id),
                        goal_path=paths_by_id.get(str(subject_id), ""),
                        activation_event_sha256=activation_event_sha256,
                        activation_occurred_at=max(
                            value
                            for value in (
                                activation_occurred_at,
                                previous_execution_time,
                            )
                            if value is not None
                        )
                        if any(
                            value is not None
                            for value in (
                                activation_occurred_at,
                                previous_execution_time,
                            )
                        )
                        else None,
                        occurred_at=occurred_at,
                        used_output_paths=used_gate_output_paths,
                        gate_purpose="SESSION_RESUME",
                    )
                )
                if isinstance(subject_id, str):
                    latest_start_event_by_goal[subject_id] = event
            elif event_type in {
                "GOAL_READY",
                "GOAL_STARTED",
                "GOAL_COMPLETED",
                "BLOCKER_RECORDED",
                "BLOCKER_RESOLVED",
                "PACKAGE_COMPLETED",
            }:
                subject_id = event.get("subject_goal_id")
                if not isinstance(subject_id, str) or subject_id not in before:
                    errors.append(f"{label}: subject Goal is missing")
                else:
                    if set(changes) != {subject_id}:
                        errors.append(
                            f"{label}: subject event must change exactly its Goal"
                        )
                    if event.get("from_status") != before.get(subject_id):
                        errors.append(f"{label}: from_status differs from replay")
                    if event.get("to_status") != changes.get(subject_id):
                        errors.append(f"{label}: to_status differs from status_changes")
                    expected_targets = {
                        "GOAL_READY": "READY",
                        "GOAL_STARTED": "IN_PROGRESS",
                        "GOAL_COMPLETED": "COMPLETE_AT_TARGET",
                        "PACKAGE_COMPLETED": "COMPLETE_AT_TARGET",
                    }
                    expected_target = expected_targets.get(str(event_type))
                    if (
                        expected_target is not None
                        and changes.get(subject_id) != expected_target
                    ):
                        errors.append(f"{label}: event type and target status differ")
                    subject_node = nodes.get(subject_id, {})
                    if event_type in {
                        "GOAL_COMPLETED",
                        "PACKAGE_COMPLETED",
                    }:
                        errors.extend(
                            completion_reference_errors(
                            root,
                                label=label,
                                references=list(event_references),
                                bindings=replay_bindings,
                                direct_packet_document_ids=(
                                    packet_document_ids_through_event
                                ),
                            )
                        )
                    if (
                        event_type in {"GOAL_READY", "GOAL_STARTED"}
                        and subject_node.get("goal_kind") == "WORK_ITEM"
                    ):
                        errors.extend(
                            validate_typed_start_evidence(
                                root,
                                label=label,
                                event=event,
                                goal_id=subject_id,
                                node=subject_node,
                                binding_snapshot=replay_bindings,
                                paths_by_id=paths_by_id,
                                completion_event_by_goal=completion_event_by_goal,
                                completion_bindings_by_goal=(
                                    completion_bindings_by_goal
                                ),
                                nodes=nodes,
                                occurred_at=occurred_at,
                            )
                        )
                        if subject_node.get(
                            "work_item_type"
                        ) == "BLOCKER_OR_EXTERNAL_RECEIPT":
                            source_blocker_ids = set(
                                string_list(
                                    subject_node.get("source_blocker_ids")
                                )
                            )
                            if (
                                not source_blocker_ids
                                or not source_blocker_ids.issubset(
                                    recorded_blocker_by_id
                                )
                            ):
                                errors.append(
                                    f"{label}: external receipt Goal lacks "
                                    "recorded blocker lifecycle provenance"
                                )

                    if event_type == "GOAL_READY":
                        if before.get(subject_id) != "PLANNED":
                            errors.append(f"{label}: GOAL_READY must start from PLANNED")
                        dependencies = string_list(
                            nodes.get(subject_id, {}).get("start_requires")
                        )
                        expected_basis = [
                            {
                                "goal_id": dependency,
                                "event_sha256": completion_event_by_goal.get(
                                    dependency, {}
                                ).get("event_sha256"),
                            }
                            for dependency in dependencies
                        ]
                        if event.get("readiness_basis") != {
                            "dependency_completion_events": expected_basis
                        }:
                            errors.append(f"{label}: readiness basis differs")
                        if any(
                            before.get(dependency) != "COMPLETE_AT_TARGET"
                            or dependency not in completion_event_by_goal
                            for dependency in dependencies
                        ):
                            errors.append(
                                f"{label}: GOAL_READY dependencies are not complete"
                            )
                        parent = nodes.get(subject_id, {}).get("parent_goal_id")
                        if parent and before.get(str(parent)) in TERMINAL_STATUSES:
                            errors.append(f"{label}: GOAL_READY parent is closed")
                        if subject_id in previous_blockers:
                            errors.append(f"{label}: GOAL_READY still has a blocker")
                    elif event_type == "GOAL_STARTED":
                        if nodes.get(subject_id, {}).get("goal_kind") != "WORK_ITEM":
                            errors.append(
                                f"{label}: only a Work Item may enter IN_PROGRESS"
                            )
                        if before.get(subject_id) != "READY":
                            errors.append(f"{label}: GOAL_STARTED must start from READY")
                        backlog_errors, replay_backlog = load_snapshot_json(
                            root,
                            replay_bindings,
                            "IMPLEMENTATION_BACKLOG",
                        )
                        errors.extend(f"{label}: {error}" for error in backlog_errors)
                        selected = ready_frontier(
                            nodes,
                            before,
                            replay_materialized_children(nodes, before),
                            previous_blockers,
                            replay_backlog,
                        )
                        if not selected or selected[0] != subject_id:
                            errors.append(
                                f"{label}: GOAL_STARTED subject is not deterministic focus"
                            )
                        errors.extend(
                            validate_implementation_start_gate_receipt(
                                root,
                                label=label,
                                event=event,
                                goal_id=subject_id,
                                goal_path=paths_by_id.get(subject_id, ""),
                                activation_event_sha256=(
                                    activation_event_sha256
                                ),
                                activation_occurred_at=(
                                    activation_occurred_at
                                ),
                                occurred_at=occurred_at,
                                used_output_paths=(
                                    used_gate_output_paths
                                ),
                                gate_purpose="INITIAL_START",
                            )
                        )
                        latest_start_event_by_goal[subject_id] = event
                    elif event_type == "GOAL_COMPLETED":
                        node = nodes.get(subject_id, {})
                        if (
                            node.get("goal_kind") == "WORK_ITEM"
                            and node.get("work_item_type")
                            in CANONICAL_PRODUCER_WORK_TYPES
                            and subject_id
                            != pending_producer_completion_goal_id
                        ):
                            errors.append(
                                f"{label}: canonical-producing Work Item must "
                                "first commit its declared canonical update"
                            )
                        expected_from_status = (
                            "IN_PROGRESS"
                            if node.get("goal_kind") == "WORK_ITEM"
                            else "READY"
                            if node.get("goal_kind") == "WORKSTREAM"
                            else None
                        )
                        if before.get(subject_id) != expected_from_status:
                            errors.append(
                                f"{label}: GOAL_COMPLETED source status differs by Goal kind"
                            )
                        refs = list(event_references)
                        expected_completion_bindings = {
                            reference: replay_bindings.get(reference)
                            for reference in refs
                        }
                        if event.get("completion_evidence_bindings") != (
                            expected_completion_bindings
                        ):
                            errors.append(
                                f"{label}: completion evidence binding snapshot differs"
                            )
                        if node.get("goal_kind") == "WORK_ITEM":
                            errors.extend(
                                validate_work_item_completion_event(
                                    root,
                                    label=label,
                                    event=event,
                                    goal_id=subject_id,
                                    node=node,
                                    goal_path=paths_by_id.get(subject_id, ""),
                                    completion_references=refs,
                                    binding_snapshot=replay_bindings,
                                    latest_start_event=latest_start_event_by_goal.get(
                                        subject_id
                                    ),
                                )
                            )
                            errors.extend(
                                validate_typed_work_item_completion_semantics(
                                    root,
                                    goal_id=subject_id,
                                    node=node,
                                    goal_path=paths_by_id.get(subject_id, ""),
                                    references=refs,
                                    binding_snapshot=replay_bindings,
                                    paths_by_id=paths_by_id,
                                    nodes=nodes,
                                    completion_bindings_by_goal=(
                                        completion_bindings_by_goal
                                    ),
                                    historical_completion_bindings_by_goal=(
                                        historical_completion_bindings_by_goal
                                    ),
                                    historical_completion_event_by_goal=(
                                        historical_completion_event_by_goal
                                    ),
                                    recorded_blocker_by_id=(
                                        recorded_blocker_by_id
                                    ),
                                    recorded_blocker_event_by_id=(
                                        recorded_blocker_event_by_id
                                    ),
                                    recorded_blocker_event_time_by_id=(
                                        recorded_blocker_event_time_by_id
                                    ),
                                    latest_start_event=latest_start_event_by_goal.get(
                                        subject_id
                                    ),
                                    completion_event=event,
                                )
                            )
                        elif node.get("goal_kind") == "WORKSTREAM":
                            reopen_event = (
                                latest_workstream_reopen_event_by_goal.get(
                                    subject_id
                                )
                            )
                            errors.extend(
                                validate_workstream_completion_semantics(
                                    root,
                                    goal_id=subject_id,
                                    references=refs,
                                    binding_snapshot=replay_bindings,
                                    paths_by_id=paths_by_id,
                                    nodes=nodes,
                                    statuses_before_completion=before,
                                    completion_event_by_goal=(
                                        completion_event_by_goal
                                    ),
                                    revalidation_required=isinstance(
                                        reopen_event,
                                        dict,
                                    ),
                                )
                            )
                            if isinstance(reopen_event, dict):
                                errors.extend(
                                    validate_reopened_workstream_completion(
                                        root,
                                        label=label,
                                        goal_id=subject_id,
                                        node=node,
                                        nodes=nodes,
                                        statuses_before_completion=before,
                                        references=refs,
                                        binding_snapshot=replay_bindings,
                                    completion_event_by_goal=(
                                        completion_event_by_goal
                                    ),
                                    reopen_event=reopen_event,
                                    latest_impact_event=(
                                        latest_workstream_impact_event_by_goal.get(
                                            subject_id,
                                            reopen_event,
                                        )
                                    ),
                                    completion_event=event,
                                    )
                                )
                        if subject_id == pending_producer_completion_goal_id:
                            if (
                                event.get("canonical_update_event_sha256")
                                != pending_producer_update_event_sha256
                            ):
                                errors.append(
                                    f"{label}: producer completion is not bound "
                                    "to its canonical update"
                                )
                            pending_producer_completion_goal_id = None
                            pending_producer_update_event_sha256 = None
                    elif event_type == "BLOCKER_RECORDED":
                        if changes.get(subject_id) not in {
                            "AWAITING_USER",
                            "AWAITING_EXTERNAL",
                            "BLOCKED",
                        }:
                            errors.append(
                                f"{label}: blocker event target status is invalid"
                            )
                    elif event_type == "BLOCKER_RESOLVED":
                        if before.get(subject_id) not in {
                            "AWAITING_USER",
                            "AWAITING_EXTERNAL",
                            "BLOCKED",
                        } or subject_id not in previous_blockers:
                            errors.append(
                                f"{label}: blocker resolution has no active blocker"
                            )
                    elif event_type == "PACKAGE_COMPLETED":
                        if subject_id != EXPECTED_ROOT_GOAL_ID:
                            errors.append(
                                f"{label}: PACKAGE_COMPLETED subject must be Master"
                            )
                        if pending_reopen_goal_ids:
                            errors.append(
                                f"{label}: package completion has unresolved "
                                "canonical-change reopen Goals"
                            )
                        package_completed_seen = True
            elif event_type == "GOAL_FOCUS_CHANGED":
                if changes:
                    errors.append(
                        f"{label}: focus change must not rewrite Goal status"
                    )

        if (
            event_type in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
            and isinstance(prestart_control_repair_event, dict)
            and not prestart_repair_first_execution_bound
        ):
            errors.extend(
                validate_prestart_repair_first_execution_binding(
                    root,
                    label=label,
                    repair_event=prestart_control_repair_event,
                    execution_event=event,
                )
            )
            prestart_repair_first_execution_bound = True

        if event_type == "CANONICAL_BINDINGS_UPDATED":
            for goal_id, status in changes.items():
                if (
                    before.get(goal_id) == "COMPLETE_AT_TARGET"
                    and status in {"READY", "PLANNED"}
                    and nodes.get(goal_id, {}).get("goal_kind")
                    == "WORKSTREAM"
                ):
                    latest_workstream_reopen_event_by_goal[goal_id] = event

        if event_type == "PACKAGE_PREPARED":
            for goal_id, status in changes.items():
                if status == "COMPLETE_AT_TARGET":
                    refs = replay_completion_evidence_by_goal.get(
                        goal_id,
                        [],
                    )
                    completion_event_by_goal[goal_id] = event
                    completion_bindings_by_goal[goal_id] = {
                        reference: replay_bindings.get(reference)
                        for reference in refs
                        if isinstance(replay_bindings.get(reference), dict)
                    }
        elif event_type in {"GOAL_COMPLETED", "PACKAGE_COMPLETED"}:
            for goal_id, status in changes.items():
                if status == "COMPLETE_AT_TARGET":
                    refs = list(event_references)
                    completion_event_by_goal[goal_id] = event
                    completion_bindings_by_goal[goal_id] = {
                        reference: replay_bindings.get(reference)
                        for reference in refs
                        if isinstance(replay_bindings.get(reference), dict)
                    }
                    replay_completion_evidence_by_goal[goal_id] = refs

        runtime_after = event.get("runtime_after")
        if not isinstance(runtime_after, dict):
            errors.append(f"{label}: runtime_after must be an object")
        else:
            final_runtime = runtime_after
            focus_id = runtime_after.get("focus_goal_id")
            focus_path = paths_by_id.get(str(focus_id))
            focus_file = resolve_safe_repo_file(root, focus_path)
            if (
                focus_id in {"", None}
                and runtime_after.get("package_status") == "COMPLETED"
            ):
                if runtime_after.get("focus_goal_path") not in {"", None}:
                    errors.append(f"{label}: completed runtime focus path is not empty")
            elif focus_id not in nodes or focus_file is None:
                errors.append(f"{label}: runtime focus is missing or unsafe")
            else:
                if runtime_after.get("focus_goal_path") != focus_path:
                    errors.append(f"{label}: runtime focus path differs")
                if event.get("focus_goal_id") != focus_id:
                    errors.append(f"{label}: event focus differs from runtime")
                if event.get("focus_goal_content_sha256") != sha256_file(focus_file):
                    errors.append(f"{label}: focus Goal content hash differs")
                focus_node = nodes.get(str(focus_id), {})
                expected_work_item_id = (
                    focus_node.get("work_item_id")
                    if focus_node.get("goal_kind") == "WORK_ITEM"
                    else ""
                )
                expected_source = (
                    focus_node.get("materialized_from_role")
                    if focus_node.get("goal_kind") == "WORK_ITEM"
                    else "WORKSTREAM_GRAPH"
                )
                if runtime_after.get("focus_work_item_id") != expected_work_item_id:
                    errors.append(f"{label}: runtime focus Work Item ID differs")
                if runtime_after.get("focus_source") != expected_source:
                    errors.append(f"{label}: runtime focus source differs")
            if focus_id in {"", None}:
                if runtime_after.get("focus_work_item_id") not in {"", None}:
                    errors.append(f"{label}: empty runtime focus has a Work Item ID")
                if runtime_after.get("focus_source") not in {"", None}:
                    errors.append(f"{label}: empty runtime focus has a source")
            expected_runtime_pair = (
                ("READY_NOT_ACTIVATED", "PREPARED_NOT_ACTIVATED")
                if index == 1
                else ("COMPLETED", "COMPLETED")
                if package_completed_seen
                else ("ACTIVE", "ACTIVE")
            )
            runtime_pair = (
                runtime_after.get("activation_status"),
                runtime_after.get("package_status"),
            )
            if runtime_pair not in ACTIVATION_PACKAGE_PAIRS:
                errors.append(f"{label}: runtime activation/package status is invalid")
            elif runtime_pair != expected_runtime_pair:
                errors.append(
                    f"{label}: runtime activation/package status differs from lifecycle"
                )
            if index > 1:
                previous_focus = previous_runtime.get("focus_goal_id")
                if event.get("previous_focus_goal_id") != previous_focus:
                    errors.append(f"{label}: previous focus Goal differs")
                if previous_focus:
                    previous_path = paths_by_id.get(str(previous_focus))
                    previous_file = resolve_safe_repo_file(root, previous_path)
                    if (
                        previous_file is None
                        or event.get("previous_focus_content_sha256")
                        != sha256_file(previous_file)
                    ):
                        errors.append(f"{label}: previous focus content hash differs")
                if event_type == "GOAL_FOCUS_CHANGED" and focus_id == previous_focus:
                    errors.append(f"{label}: focus change event is a no-op")
                if event_type == "PACKAGE_ACTIVATED" and (
                    runtime_after.get("activation_status"),
                    runtime_after.get("package_status"),
                ) != ("ACTIVE", "ACTIVE"):
                    errors.append(f"{label}: activation runtime status differs")
            blockers_after = event.get("blockers_after")
            if not isinstance(blockers_after, dict):
                errors.append(f"{label}: blockers_after must be an object")
                blockers_after = {}
            previous_blocker_records, previous_duplicate_ids = (
                legacy.blocker_records_by_id(previous_blockers)
            )
            current_blocker_records, current_duplicate_ids = (
                legacy.blocker_records_by_id(blockers_after)
            )
            if previous_duplicate_ids or current_duplicate_ids:
                errors.append(f"{label}: blocker snapshot contains duplicate IDs")
            previous_blocker_ids = set(previous_blocker_records)
            current_blocker_ids = set(current_blocker_records)
            valid_condition_codes = set(
                legacy.EXPECTED_QUESTION_CODES + legacy.EXPECTED_STOP_CODES
            )
            for blocked_goal_id, records in blockers_after.items():
                if (
                    blocked_goal_id not in nodes
                    or not isinstance(records, list)
                    or not records
                ):
                    errors.append(f"{label}: blocker snapshot entry is malformed")
                    continue
                for record in records:
                    if not isinstance(record, dict):
                        errors.append(f"{label}: blocker record must be an object")
                        continue
                    condition_code = record.get("condition_code")
                    expected_owner = (
                        "USER"
                        if condition_code in legacy.USER_WAIT_CODES
                        else "EXTERNAL"
                        if condition_code in legacy.EXTERNAL_WAIT_CODES
                        else "BLOCKED"
                    )
                    expected_waiting_status = {
                        "USER": "AWAITING_USER",
                        "EXTERNAL": "AWAITING_EXTERNAL",
                        "BLOCKED": "BLOCKED",
                    }[expected_owner]
                    return_status = record.get("return_status")
                    created_at = parse_iso_datetime(record.get("created_at"))
                    if (
                        not isinstance(record.get("blocker_id"), str)
                        or not SAFE_ID_PATTERN.fullmatch(
                            record.get("blocker_id")
                        )
                        or record.get("blocks_goal_id") != blocked_goal_id
                        or condition_code not in valid_condition_codes
                        or record.get("owner") != expected_owner
                        or replay_statuses.get(blocked_goal_id)
                        != expected_waiting_status
                        or return_status not in {"PLANNED", "READY"}
                        or created_at is None
                        or not isinstance(record.get("prompt"), str)
                        or not record.get("prompt").strip()
                        or record.get("prompt")
                        != record.get("prompt").strip()
                        or record.get("blocker_snapshot_sha256")
                        != legacy.blocker_snapshot_sha256(record)
                    ):
                        errors.append(f"{label}: blocker record contract differs")
            common_blocker_ids = previous_blocker_ids & current_blocker_ids
            if any(
                previous_blocker_records[blocker_id]
                != current_blocker_records[blocker_id]
                for blocker_id in common_blocker_ids
            ):
                errors.append(f"{label}: existing blocker record was rewritten")

            resolution_ids_after = event.get("blocker_resolution_ids_after")
            if (
                not isinstance(resolution_ids_after, list)
                or any(
                    not isinstance(blocker_id, str)
                    or not SAFE_ID_PATTERN.fullmatch(blocker_id)
                    for blocker_id in resolution_ids_after
                )
                or len(resolution_ids_after) != len(set(resolution_ids_after))
                or resolution_ids_after[: len(replay_resolution_ids)]
                != replay_resolution_ids
            ):
                errors.append(f"{label}: blocker resolution ID history differs")
                resolution_ids_after = list(replay_resolution_ids)
            added_resolution_ids = set(resolution_ids_after) - set(
                replay_resolution_ids
            )

            if event_type == "BLOCKER_RECORDED":
                subject_id = event.get("subject_goal_id")
                introduced_ids = current_blocker_ids - previous_blocker_ids
                removed_ids = previous_blocker_ids - current_blocker_ids
                expected_return_status = (
                    "PLANNED"
                    if before.get(subject_id) == "PLANNED"
                    else "READY"
                )
                if (
                    before.get(subject_id)
                    not in {"PLANNED", "READY", "IN_PROGRESS"}
                    or not introduced_ids
                    or removed_ids
                    or set(string_list(event.get("blocker_ids")))
                    != introduced_ids
                    or any(
                        current_blocker_records[blocker_id].get(
                            "blocks_goal_id"
                        )
                        != subject_id
                        or current_blocker_records[blocker_id].get(
                            "return_status"
                        )
                        != expected_return_status
                        for blocker_id in introduced_ids
                    )
                    or added_resolution_ids
                ):
                    errors.append(f"{label}: invalid BLOCKER_RECORDED transition")
                before_backlog_errors, before_backlog = load_snapshot_json(
                    root,
                    replay_bindings,
                    "IMPLEMENTATION_BACKLOG",
                )
                errors.extend(
                    f"{label}: {error}"
                    for error in before_backlog_errors
                )
                ready_before_request = ready_frontier(
                    nodes,
                    before,
                    replay_materialized_children(nodes, before),
                    previous_blockers,
                    before_backlog,
                )
                if not blocker_request_targets_deterministic_focus(
                    subject_id,
                    previous_runtime,
                    ready_before_request,
                ):
                    errors.append(
                        f"{label}: blocker request must target the current "
                        "deterministic focus"
                    )
                for blocker_id in introduced_ids:
                    record = current_blocker_records[blocker_id]
                    typed_request_errors = validate_typed_request_record(
                        record,
                        nodes=nodes,
                        paths_by_id=paths_by_id,
                        root=root,
                    )
                    errors.extend(
                        f"{label}: {error}"
                        for error in typed_request_errors
                    )
                    if (
                        record.get("owner") in {"USER", "EXTERNAL"}
                        and record.get("request_event_id")
                        != event.get("event_id")
                    ):
                        errors.append(
                            f"{label}: typed blocker request event ID differs"
                        )
                    request_key = record.get("request_key")
                    if (
                        record.get("owner") in {"USER", "EXTERNAL"}
                        and isinstance(request_key, str)
                        and request_key.strip()
                    ):
                        prior_request = request_key_owner_by_key.get(
                            request_key
                        )
                        current_request = (
                            str(record.get("owner")),
                            blocker_id,
                        )
                        if prior_request is not None:
                            errors.append(
                                f"{label}: blocker request key was already "
                                f"issued in history: {request_key}"
                            )
                        else:
                            request_key_owner_by_key[
                                request_key
                            ] = current_request
                    if blocker_id in recorded_blocker_by_id:
                        errors.append(
                            f"{label}: blocker ID was already issued in "
                            f"history: {blocker_id}"
                        )
                    else:
                        recorded_blocker_event_by_id[blocker_id] = str(
                            event.get("event_sha256", "")
                        )
                        recorded_blocker_event_id_by_id[blocker_id] = str(
                            event.get("event_id", "")
                        )
                        recorded_blocker_event_time_by_id[
                            blocker_id
                        ] = occurred_at
                        recorded_blocker_by_id[blocker_id] = record
                    created_at = parse_iso_datetime(record.get("created_at"))
                    if (
                        created_at is not None
                        and occurred_at is not None
                        and created_at > occurred_at
                    ):
                        errors.append(
                            f"{label}: blocker creation postdates its event"
                        )
            elif event_type == "BLOCKER_RESOLVED":
                subject_id = event.get("subject_goal_id")
                removed_ids = previous_blocker_ids - current_blocker_ids
                introduced_ids = current_blocker_ids - previous_blocker_ids
                resolved_now = added_resolution_ids
                return_statuses = {
                    previous_blocker_records[blocker_id].get("return_status")
                    for blocker_id in removed_ids
                    if blocker_id in previous_blocker_records
                }
                expected_receipt_refs = {
                    resolution_by_id.get(blocker_id, {}).get(
                        "resolution_receipt_ref"
                    )
                    for blocker_id in resolved_now
                }
                if (
                    not resolved_now
                    or resolved_now != removed_ids
                    or introduced_ids
                    or set(string_list(event.get("resolved_blocker_ids")))
                    != resolved_now
                    or any(
                        previous_blocker_records[blocker_id].get(
                            "blocks_goal_id"
                        )
                        != subject_id
                        for blocker_id in removed_ids
                    )
                    or len(return_statuses) != 1
                    or changes.get(subject_id)
                    != next(iter(return_statuses), None)
                    or None in expected_receipt_refs
                    or set(event_references or []) != expected_receipt_refs
                ):
                    errors.append(f"{label}: invalid BLOCKER_RESOLVED transition")
                for blocker_id in resolved_now:
                    resolution = resolution_by_id.get(blocker_id, {})
                    original = recorded_blocker_by_id.get(
                        blocker_id,
                        previous_blocker_records.get(blocker_id, {}),
                    )
                    recorded_event_hash = recorded_blocker_event_by_id.get(
                        blocker_id
                    )
                    if (
                        resolution.get("goal_id")
                        != original.get("blocks_goal_id")
                        or resolution.get("condition_code")
                        != original.get("condition_code")
                        or resolution.get("owner") != original.get("owner")
                        or resolution.get("blocker_event_sha256")
                        != recorded_event_hash
                        or resolution.get("blocker_snapshot_sha256")
                        != original.get("blocker_snapshot_sha256")
                        or (
                            original.get("owner") in {"USER", "EXTERNAL"}
                            and resolution.get("resolution_receipt_ref")
                            not in string_list(
                                original.get(
                                    "required_resolution_evidence_roles"
                                )
                            )
                        )
                    ):
                        errors.append(
                            f"{label}: blocker resolution history differs from blocker"
                        )
                    errors.extend(
                        legacy.validate_blocker_resolution_receipt(
                            label=f"{label}: blocker {blocker_id}",
                            reference=resolution.get("resolution_receipt_ref"),
                            root=root,
                            bindings=replay_bindings,
                            blocker_id=blocker_id,
                            goal_id=original.get("blocks_goal_id"),
                            condition_code=original.get("condition_code"),
                            owner=original.get("owner"),
                            blocker_event_sha256=recorded_event_hash,
                            blocker_snapshot_hash=original.get(
                                "blocker_snapshot_sha256"
                            ),
                        )
                    )
                    errors.extend(
                        validate_blocker_resolution_event_chronology(
                            root,
                            label=f"{label}: blocker {blocker_id}",
                            reference=resolution.get(
                                "resolution_receipt_ref"
                            ),
                            bindings=replay_bindings,
                            blocker_created_at=parse_iso_datetime(
                                original.get("created_at")
                            ),
                            blocker_recorded_at=(
                                recorded_blocker_event_time_by_id.get(
                                    blocker_id
                                )
                            ),
                            blocker_resolved_at=occurred_at,
                        )
                    )
            else:
                if blockers_after != previous_blockers:
                    errors.append(
                        f"{label}: blocker snapshot changed outside a blocker event"
                    )
                if added_resolution_ids:
                    errors.append(
                        f"{label}: blocker resolutions changed outside BLOCKER_RESOLVED"
                    )
            replay_resolution_ids = list(resolution_ids_after)
            waiting_after = {
                goal_id
                for goal_id, status in replay_statuses.items()
                if status in {"AWAITING_USER", "AWAITING_EXTERNAL", "BLOCKED"}
            }
            if set(blockers_after) != waiting_after:
                errors.append(f"{label}: blocker snapshot differs from replayed status")
            if set(string_list(runtime_after.get("blocked_goal_ids"))) != set(
                blockers_after
            ):
                errors.append(f"{label}: runtime blocked Goal list differs")

            backlog_errors, replay_backlog = load_snapshot_json(
                root,
                replay_bindings,
                "IMPLEMENTATION_BACKLOG",
            )
            errors.extend(f"{label}: {error}" for error in backlog_errors)
            calculated_ready = ready_frontier(
                nodes,
                replay_statuses,
                replay_materialized_children(nodes, replay_statuses),
                blockers_after,
                replay_backlog,
            )
            if runtime_after.get("ready_frontier_goal_ids") != calculated_ready:
                errors.append(f"{label}: runtime ready frontier differs")
            if calculated_ready and focus_id != calculated_ready[0]:
                errors.append(f"{label}: runtime focus is not deterministic")
            if (
                not calculated_ready
                and runtime_after.get("package_status") != "COMPLETED"
                and focus_id not in {"", None}
            ):
                errors.append(f"{label}: runtime focus exists without a ready Goal")
            internal_frontier_after = [
                goal_id
                for goal_id in calculated_ready
                if not (
                    nodes.get(goal_id, {}).get("goal_kind") == "WORK_ITEM"
                    and nodes.get(goal_id, {}).get("work_item_type")
                    in DYNAMIC_EXTERNAL_TYPES
                )
            ]
            expected_event_pending_questions = pending_user_questions(
                blockers_after,
                delivery_deferred=bool(internal_frontier_after),
            )
            if runtime_after.get("pending_questions") != (
                expected_event_pending_questions
            ):
                errors.append(
                    f"{label}: runtime pending questions differ from active "
                    "USER requests and internal frontier"
                )
            if runtime_after.get("open_question_count") != len(
                expected_event_pending_questions
            ):
                errors.append(
                    f"{label}: runtime open question count differs"
                )
            event_queue_errors, expected_event_queue = (
                derive_artifact_work_queue(
                    root,
                    replay_bindings,
                    nodes,
                    replay_statuses,
                )
            )
            errors.extend(
                f"{label}: {error}" for error in event_queue_errors
            )
            if runtime_after.get("artifact_work_queue") != expected_event_queue:
                errors.append(
                    f"{label}: runtime artifact work queue differs from "
                    "replayed bindings and statuses"
                )
            event_boundary_errors, expected_event_boundary = (
                derive_completion_boundary(
                    nodes,
                    replay_statuses,
                    calculated_ready,
                    blockers_after,
                    expected_event_queue,
                    package_status=str(
                        runtime_after.get("package_status", "")
                    ),
                )
            )
            errors.extend(
                f"{label}: {error}" for error in event_boundary_errors
            )
            if runtime_after.get("completion_boundary") != (
                expected_event_boundary
            ):
                errors.append(
                    f"{label}: runtime completion boundary differs from "
                    "replayed status, blockers, and artifact queue"
                )
            previous_boundary = previous_runtime.get(
                "completion_boundary"
            )
            packet_required = external_action_packet_is_required(
                previous_boundary,
                expected_event_boundary,
            )
            packet_binding_present = event.get(
                "external_action_packet_binding"
            ) not in (None, "")
            if packet_required:
                errors.extend(
                    validate_external_action_packet(
                        root,
                        label=label,
                        event=event,
                        blockers_after=blockers_after,
                        expected_boundary=expected_event_boundary,
                        occurred_at=occurred_at,
                        previous_occurred_at=previous_event_time,
                        used_packet_paths=used_external_action_packet_paths,
                        used_packet_document_ids=(
                            used_external_action_packet_document_ids
                        ),
                    )
                )
            elif packet_binding_present:
                errors.append(
                    f"{label}: external action packet is prohibited outside "
                    "milestone entry or changed request basis"
                )
            if assessment_obligation_target is not None:
                assessment_satisfied = (
                    artifact_assessment_transition_satisfied(
                        target_id=assessment_obligation_target,
                        event_type=event_type,
                        event=event,
                        nodes=nodes,
                        queue_after=expected_event_queue,
                    )
                )
                if not assessment_satisfied:
                    errors.append(
                        f"{label}: pending artifact assessment target must "
                        "materialize or receive a terminal register disposition "
                        f"before another transition: "
                        f"{assessment_obligation_target}"
                    )
            if event_type in {"GOAL_COMPLETED", "PACKAGE_COMPLETED"}:
                mapping_errors, event_mapping = load_policy_gap_mapping(
                    root,
                    replay_bindings,
                )
                errors.extend(f"{label}: {error}" for error in mapping_errors)
                event_completion_errors = validate_completion_contracts(
                    nodes,
                    replay_statuses,
                    replay_materialized_children(nodes, replay_statuses),
                    replay_backlog,
                    event_mapping,
                    root=root,
                    bindings=replay_bindings,
                )
                errors.extend(
                    f"{label}: {error}" for error in event_completion_errors
                )
            if event_type == "PACKAGE_COMPLETED":
                if (
                    runtime_after.get("activation_status"),
                    runtime_after.get("package_status"),
                ) != ("COMPLETED", "COMPLETED"):
                    errors.append(f"{label}: completed package runtime status differs")
                if calculated_ready:
                    errors.append(f"{label}: completed package has a ready frontier")
                for field in (
                    "focus_goal_id",
                    "focus_goal_path",
                    "focus_work_item_id",
                    "focus_source",
                ):
                    if runtime_after.get(field) not in {"", None}:
                        errors.append(
                            f"{label}: completed package runtime {field} is not empty"
                        )
            previous_runtime = runtime_after
            previous_blockers = dict(blockers_after)
        if event_type in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}:
            execution_event_seen = True
        previous_hash = actual_hash

    final_unconsumed_due_ids = unconsumed_structurally_due_ids(
        final_runtime
    )
    if final_unconsumed_due_ids:
        errors.append(
            "Goal graph transition tail has unmaterialized structurally due "
            f"artifact work: {final_unconsumed_due_ids}"
        )

    if (
        history[0].get("supersedes_event_sha256")
        != EXPECTED_V21_INITIAL_EVENT_SHA256
    ):
        errors.append(
            "Goal graph preparation event lacks v2.1 supersession binding"
        )
    if state.get("activation_status") == "READY_NOT_ACTIVATED":
        if len(history) != 1:
            errors.append("unactivated Goal graph must contain only its preparation event")
    elif len(history) < 2 or history[1].get("event_type") != "PACKAGE_ACTIVATED":
        errors.append("active Goal graph lacks the activation event")
    repair_event_count = sum(
        1
        for event in history
        if isinstance(event, dict)
        and event.get("event_type")
        == "PRESTART_CONTROL_REPAIR_COMMITTED"
    )
    if repair_event_count > 1:
        errors.append(
            "Goal graph prestart control repair event is duplicated"
        )
    if len(history) > 2 and (
        not isinstance(history[2], dict)
        or history[2].get("event_type")
        != "PRESTART_CONTROL_REPAIR_COMMITTED"
        or repair_event_count != 1
    ):
        errors.append(
            "Goal graph first post-activation event must be the one-time "
            "prestart control repair"
        )
    if replay_statuses != state.get("status_by_goal"):
        errors.append("Goal graph replayed statuses differ from runtime")
    if previous_blockers != state.get("blockers_by_goal"):
        errors.append("Goal graph replayed blocker records differ from runtime")
    if replay_resolution_ids != list(resolution_by_id):
        errors.append("Goal graph replayed blocker resolutions differ from runtime")
    final_blocker_records, final_duplicate_blocker_ids = (
        legacy.blocker_records_by_id(previous_blockers)
    )
    if final_duplicate_blocker_ids:
        errors.append("Goal graph final blocker records contain duplicate IDs")
    if set(final_blocker_records) & set(resolution_by_id):
        errors.append(
            "Goal graph active and resolved blocker ID histories overlap"
        )
    if set(recorded_blocker_by_id) != (
        set(final_blocker_records) | set(resolution_by_id)
    ):
        errors.append("Goal graph blocker event history differs from final lifecycle")
    if replay_bindings != canonical_binding_snapshot(bindings):
        errors.append("Goal graph replayed canonical bindings differ from checkpoint")
    if replay_completion_evidence_by_goal != evidence:
        errors.append(
            "Goal graph replayed active completion evidence differs from runtime"
        )
    if replay_archived_completion_evidence_by_goal != archived_evidence:
        errors.append(
            "Goal graph replayed archived completion evidence differs from runtime"
        )
    final_authority_roster = replay_bindings.get("AUTHORITY_ROSTER")
    if isinstance(final_authority_roster, dict):
        current_roster_anchor = legacy.EXPECTED_AUTHORITY_ROSTER_SHA256
        if (
            not isinstance(current_roster_anchor, str)
            or not SHA256_PATTERN.fullmatch(current_roster_anchor)
            or final_authority_roster.get("file_sha256")
            != current_roster_anchor
        ):
            errors.append(
                "Goal graph current authority roster differs from the "
                "current checker anchor"
            )
    if state.get("pending_reopen_goal_ids") != sorted(
        pending_reopen_goal_ids
    ):
        errors.append("Goal graph pending reopen Goal list differs from replay")
    if pending_reopen_goal_ids:
        errors.append(
            "Goal graph has unresolved canonical-change reopen Goals; "
            "the update and successor events must be committed together"
        )
    if state.get("pending_producer_completion_goal_id") != (
        pending_producer_completion_goal_id or ""
    ):
        errors.append(
            "Goal graph pending canonical producer differs from replay"
        )
    if pending_producer_completion_goal_id is not None:
        errors.append(
            "Goal graph canonical binding producer has not completed; "
            "the update and completion events must be committed together"
        )
    runtime_fields = (
        "focus_goal_id",
        "focus_goal_path",
        "focus_work_item_id",
        "focus_source",
        "ready_frontier_goal_ids",
        "blocked_goal_ids",
        "pending_questions",
        "open_question_count",
        "artifact_work_queue",
        "completion_boundary",
        "activation_status",
        "package_status",
    )
    for field in runtime_fields:
        if final_runtime.get(field) != state.get(field):
            errors.append(f"Goal graph replayed runtime differs: {field}")
    if state.get("transition_history_anchor_sha256") != previous_hash:
        errors.append("Goal graph transition history self anchor differs")
    if EXPECTED_INITIAL_EVENT_SHA256.startswith("__FINALIZE_"):
        errors.append("Goal graph initial event trust anchor is not finalized")
    elif history[0].get("event_sha256") != EXPECTED_INITIAL_EVENT_SHA256:
        errors.append("Goal graph initial event differs from checker trust anchor")
    if previous_time is not None and cutoff is not None and cutoff < previous_time:
        errors.append("Goal graph validation cutoff predates its latest event")
    return errors


def validate_runtime(
    root: Path,
    checkpoint: dict[str, Any],
    manifest: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    paths_by_id: dict[str, str],
    backlog: dict[str, Any],
    mapping: dict[str, str],
    bindings: dict[str, dict[str, Any]],
    discovered_paths: list[str],
    current_work_session_id: str | None,
) -> list[str]:
    errors: list[str] = []
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        return ["checkpoint goal_execution must be an object"]
    if PROHIBITED_LINEAR_FIELDS & set(state):
        errors.append("runtime contains prohibited fixed-stage fields")
    expected_scalars = {
        "schema_version": "2.0",
        "package_id": EXPECTED_PACKAGE_ID,
        "master_goal_id": EXPECTED_ROOT_GOAL_ID,
        "graph_model": "DEPENDENCY_DAG_READY_FRONTIER",
        "selection_policy": (
            "IN_PROGRESS_THEN_CANONICAL_NEXT_ACTION_THEN_RISK_UNLOCK_PRIORITY"
        ),
        "transition_commit_policy": (
            "PREPARE_VALIDATE_THEN_ATOMIC_CHECKPOINT_SWITCH"
        ),
        "transition_history_assurance": (
            "INITIAL_EVENT_CHECKER_ANCHORED_TAIL_HASH_CHAINED_"
            "EXTERNAL_SNAPSHOT_REQUIRED"
        ),
        "static_plan_version": EXPECTED_PLAN_VERSION,
        "static_plan_locked": True,
    }
    for field, value in expected_scalars.items():
        if state.get(field) != value:
            errors.append(f"Goal graph runtime differs: {field}")
    if state.get("bootstrap_consumed_policy_gap_pairs") != (
        EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS
    ):
        errors.append(
            "Goal graph bootstrap consumed policy/Gap pair state differs"
        )
    activation_package_pair = (
        state.get("activation_status"),
        state.get("package_status"),
    )
    if activation_package_pair not in ACTIVATION_PACKAGE_PAIRS:
        errors.append("Goal graph activation/package status combination is invalid")
    if state.get("standing_execution_authority") != EXPECTED_STANDING_EXECUTION_AUTHORITY:
        errors.append("Goal graph standing execution authority differs")
    if state.get("external_action_required_for") != EXPECTED_EXTERNAL_ACTION_REQUIRED_FOR:
        errors.append("Goal graph external action boundary differs")
    statuses = state.get("status_by_goal")
    if not isinstance(statuses, dict) or set(statuses) != set(nodes):
        errors.append("Goal graph runtime status set differs from materialized nodes")
        statuses = {}
    elif any(status not in RUNTIME_STATUSES for status in statuses.values()):
        errors.append("Goal graph runtime contains an invalid status")
    in_progress_ids = [
        goal_id
        for goal_id, status in statuses.items()
        if status == "IN_PROGRESS"
    ]
    if len(in_progress_ids) > 1:
        errors.append("Goal graph may have only one logical IN_PROGRESS focus")
    elif in_progress_ids and state.get("focus_goal_id") != in_progress_ids[0]:
        errors.append("Goal graph IN_PROGRESS Goal differs from focus")
    if state.get("activation_status") == "READY_NOT_ACTIVATED" and any(
        status == "IN_PROGRESS" for status in statuses.values()
    ):
        errors.append("unactivated Goal graph cannot contain IN_PROGRESS work")
    if state.get("goal_status") != statuses.get(EXPECTED_ROOT_GOAL_ID):
        errors.append("Goal graph root summary status differs")
    root_complete = statuses.get(EXPECTED_ROOT_GOAL_ID) == "COMPLETE_AT_TARGET"
    if root_complete != (
        activation_package_pair == ("COMPLETED", "COMPLETED")
    ):
        errors.append("Goal graph project completion and package status differ")
    materialized = state.get("materialized_child_goal_ids_by_parent")
    if not isinstance(materialized, dict):
        errors.append("materialized child Goal map is invalid")
        materialized = {}
    else:
        expected_materialized: dict[str, list[str]] = {}
        for goal_id, node in nodes.items():
            parent = node.get("parent_goal_id")
            if (
                node.get("goal_kind") == "WORK_ITEM"
                and isinstance(parent, str)
                and parent
            ):
                expected_materialized.setdefault(parent, []).append(goal_id)
        expected_materialized = {
            key: sorted(value) for key, value in expected_materialized.items()
        }
        normalized = {
            key: sorted(string_list(value)) for key, value in materialized.items()
        }
        if normalized != expected_materialized:
            errors.append("materialized child Goal map differs from Goal documents")
    blockers = state.get("blockers_by_goal")
    if not isinstance(blockers, dict):
        errors.append("Goal graph blocker map is invalid")
        blockers = {}
    blocked_ids = state.get("blocked_goal_ids")
    if not isinstance(blocked_ids, list) or any(
        not isinstance(goal_id, str) for goal_id in blocked_ids
    ):
        errors.append("Goal graph blocked Goal list is invalid")
        blocked_ids = []
    if set(blocked_ids) != set(blockers):
        errors.append("Goal graph blocked Goal list differs from blocker map")
    for goal_id, records in blockers.items():
        if (
            goal_id not in nodes
            or not isinstance(records, list)
            or not records
            or statuses.get(goal_id)
            not in {"AWAITING_USER", "AWAITING_EXTERNAL", "BLOCKED"}
        ):
            errors.append(f"{goal_id}: blocker state, records, and status differ")
        if isinstance(records, list):
            for record in records:
                if isinstance(record, dict):
                    errors.extend(
                        f"{goal_id}: {error}"
                        for error in validate_typed_request_record(
                            record,
                            nodes=nodes,
                            paths_by_id=paths_by_id,
                            root=root,
                        )
                    )
    waiting_ids = {
        goal_id
        for goal_id, status in statuses.items()
        if status in {"AWAITING_USER", "AWAITING_EXTERNAL", "BLOCKED"}
    }
    if waiting_ids != set(blockers):
        errors.append("Goal graph waiting statuses differ from blocker map")
    pending_questions = state.get("pending_questions")
    if not isinstance(pending_questions, list):
        errors.append("Goal graph pending question list is invalid")
        pending_questions = []
    if state.get("open_question_count") != len(pending_questions):
        errors.append("Goal graph open question count differs")
    calculated_ready = ready_frontier(
        nodes,
        statuses,
        materialized,
        blockers,
        backlog,
    )
    if state.get("ready_frontier_goal_ids") != calculated_ready:
        errors.append("Goal graph ready frontier differs from dependencies and blockers")
    if calculated_ready and state.get("focus_goal_id") != calculated_ready[0]:
        errors.append("Goal graph focus is not the deterministic ready selection")
    if not calculated_ready and state.get("focus_goal_id") not in {"", None}:
        errors.append("Goal graph focus must be empty when no Goal is runnable")
    internal_frontier = [
        goal_id
        for goal_id in calculated_ready
        if not (
            nodes.get(goal_id, {}).get("goal_kind") == "WORK_ITEM"
            and nodes.get(goal_id, {}).get("work_item_type")
            in DYNAMIC_EXTERNAL_TYPES
        )
    ]
    expected_pending_questions = pending_user_questions(
        blockers,
        delivery_deferred=bool(internal_frontier),
    )
    if pending_questions != expected_pending_questions:
        errors.append(
            "Goal graph pending questions differ from active USER requests "
            "and internal frontier delivery state"
        )
    queue_errors, expected_artifact_queue = derive_artifact_work_queue(
        root,
        bindings,
        nodes,
        statuses,
    )
    errors.extend(queue_errors)
    if state.get("artifact_work_queue") != expected_artifact_queue:
        errors.append(
            "Goal graph artifact work queue differs from the canonical register"
        )
    boundary_errors, expected_completion_boundary = derive_completion_boundary(
        nodes,
        statuses,
        calculated_ready,
        blockers,
        expected_artifact_queue,
        package_status=str(state.get("package_status", "")),
    )
    errors.extend(boundary_errors)
    if state.get("completion_boundary") != expected_completion_boundary:
        errors.append(
            "Goal graph completion boundary differs from runnable and "
            "external work"
        )
    focus_id = state.get("focus_goal_id")
    focus_node = nodes.get(focus_id, {})
    focus_path = paths_by_id.get(str(focus_id), "")
    if state.get("focus_goal_path") != focus_path:
        errors.append("Goal graph focus Goal path differs")
    if focus_node:
        expected_work_item_id = (
            focus_node.get("work_item_id")
            if focus_node.get("goal_kind") == "WORK_ITEM"
            else ""
        )
        if state.get("focus_work_item_id") != expected_work_item_id:
            errors.append("Goal graph focus Work Item ID differs")
        expected_source = (
            focus_node.get("materialized_from_role")
            if focus_node.get("goal_kind") == "WORK_ITEM"
            else "WORKSTREAM_GRAPH"
        )
        if state.get("focus_source") != expected_source:
            errors.append("Goal graph focus source differs")
        errors.extend(
            validate_current_work_item(
                root,
                focus_node,
                focus_path,
                mapping,
            )
        )
        current_work = checkpoint.get("current_work")
        if not isinstance(current_work, dict):
            errors.append("checkpoint current_work must be an object")
        else:
            expected_current_work_contract = {
                "status_scope": "IMPLEMENTATION_BACKLOG_EPIC_STATUS_NOT_GOAL_STATUS",
                "scope_kind": "BACKLOG_EPIC_AGGREGATE",
                "work_item_id_semantics": "NEXT_ACTION_POINTER_ONLY",
                "source_policy_ids_semantics": (
                    "BACKLOG_EPIC_AGGREGATE_NOT_FOCUS_GOAL_COMPLETION_SCOPE"
                ),
                "gap_ids_semantics": (
                    "BACKLOG_EPIC_AGGREGATE_NOT_FOCUS_GOAL_COMPLETION_SCOPE"
                ),
            }
            for field, expected in expected_current_work_contract.items():
                if current_work.get(field) != expected:
                    errors.append(
                        f"checkpoint current_work {field} differs from Goal graph"
                    )
    if state.get("activation_status") == "READY_NOT_ACTIVATED":
        graph_contract = manifest.get("goal_graph", {})
        if state.get("focus_goal_id") != graph_contract.get(
            "initial_focus_goal_id"
        ):
            errors.append("unactivated Goal graph focus differs from initial contract")
        if state.get("ready_frontier_goal_ids") != graph_contract.get(
            "initial_ready_frontier_goal_ids"
        ):
            errors.append(
                "unactivated Goal graph ready frontier differs from initial contract"
            )
    if state.get("managed_goal_paths") != discovered_paths:
        errors.append("Goal graph managed path set differs")
    goal_paths = sorted(paths_by_id.values())
    if state.get("goal_document_paths") != goal_paths:
        errors.append("Goal graph document path set differs")
    if state.get("managed_goal_path_count") != len(discovered_paths):
        errors.append("Goal graph managed path count differs")
    if state.get("goal_document_count") != len(goal_paths):
        errors.append("Goal graph document count differs")
    try:
        path_hash, content_hash = package_hashes(root, discovered_paths)
    except (OSError, ValueError) as exc:
        errors.append(f"Goal graph package hash calculation failed: {exc}")
    else:
        if state.get("path_set_sha256") != path_hash:
            errors.append("Goal graph path-set SHA-256 differs")
        if state.get("content_set_sha256") != content_hash:
            errors.append("Goal graph content-set SHA-256 differs")
    errors.extend(
        validate_dynamic_goal_inventory(
            root,
            state,
            manifest,
            nodes,
            paths_by_id,
        )
    )
    errors.extend(
        validate_transition_history(
            root,
            checkpoint,
            state,
            nodes,
            paths_by_id,
            bindings,
            repository_snapshot_from_checkpoint(checkpoint),
        )
    )
    if current_work_session_id is not None:
        history = state.get("transition_history")
        focus_id = state.get("focus_goal_id")
        latest_event = (
            history[-1]
            if isinstance(history, list)
            and history
            and isinstance(history[-1], dict)
            else {}
        )
        selects_latest_gate_event = bool(
            re.fullmatch(
                r"[A-Za-z0-9._-]+",
                current_work_session_id,
            )
            is not None
            and latest_event.get("event_type")
            in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
            and latest_event.get("event_id") == current_work_session_id
        )
        current_session_is_bound = bool(
            selects_latest_gate_event
            and state.get("status_by_goal", {}).get(focus_id)
            == "IN_PROGRESS"
            and latest_event.get("subject_goal_id") == focus_id
        )
        if not current_session_is_bound:
            errors.append(
                "current work session is not bound to the latest fresh "
                "execution-session gate event"
            )
        if selects_latest_gate_event:
            errors.extend(
                validate_current_gate_repository_state(
                    root,
                    checkpoint_path=(root / CHECKPOINT_RELATIVE).resolve(),
                    event=latest_event,
                )
            )
    errors.extend(
        validate_completion_contracts(
            nodes,
            statuses,
            materialized,
            backlog,
            mapping,
            root=root,
            bindings=bindings,
        )
    )
    errors.extend(
        validate_semantic_completion_evidence(
            root,
            checkpoint,
            nodes,
            paths_by_id,
            bindings,
        )
    )

    approved = checkpoint.get("approved_state", {})
    boundary = checkpoint.get("verification_boundary", {})
    formal_complete = statuses.get("WS-GOAL-EPIC-12") == "COMPLETE_AT_TARGET"
    verification_references = state.get("verification_evidence_refs")
    expected_verification_roles = (
        set(TYPE_REQUIRED_EVIDENCE_ROLES["RELEASE_DECISION"])
        if formal_complete
        else set()
    )
    verification_counts = Counter(
        verification_references
        if isinstance(verification_references, list)
        else []
    )
    if (
        not isinstance(verification_references, list)
        or set(verification_counts) != expected_verification_roles
        or any(count != 1 for count in verification_counts.values())
    ):
        errors.append(
            "Goal graph verification evidence role set differs from "
            "formal verification status"
        )
    remaining_gate_ids = string_list(boundary.get("remaining_gate_ids"))
    if len(remaining_gate_ids) != len(set(remaining_gate_ids)):
        errors.append("verification boundary contains duplicate Gate IDs")
    if approved.get("remaining_gate_count") != len(remaining_gate_ids):
        errors.append("remaining Gate count differs from remaining Gate IDs")
    if boundary.get("formal_test_not_run_count") != approved.get(
        "formal_test_not_run_count"
    ):
        errors.append("formal NOT_RUN counts differ across runtime boundary")
    if boundary.get("release_eligible") != (
        approved.get("release_status") == "ELIGIBLE"
    ):
        errors.append("release eligibility differs across runtime boundary")
    if not formal_complete:
        if approved.get("formal_test_not_run_count") != 279:
            errors.append("formal tests were promoted before formal verification completed")
        if approved.get("remaining_gate_count") != 5:
            errors.append("release Gates were promoted before formal verification completed")
        if approved.get("remaining_gates_waived") is not False:
            errors.append("remaining release Gates must not be waived")
        if approved.get("release_status") != "NOT_ELIGIBLE":
            errors.append("release eligibility was promoted without formal evidence")
        if boundary.get("actual_device_test_status") != "NOT_RUN":
            errors.append("actual-device status was promoted without formal evidence")
        if set(remaining_gate_ids) != EXPECTED_GATE_IDS:
            errors.append("verification boundary open Gate ID set differs")
        if boundary.get("all_remaining_gate_status") != "NOT_RUN":
            errors.append("open release Gates must remain NOT_RUN")
        if boundary.get("formal_test_pass_claimed") is not False:
            errors.append("formal test PASS was claimed before verification completed")
    else:
        if approved.get("formal_test_not_run_count") != 0:
            errors.append("completed formal verification still contains NOT_RUN tests")
        if approved.get("remaining_gate_count") != 0:
            errors.append("completed formal verification still contains open Gates")
        if approved.get("remaining_gates_waived") is not False:
            errors.append("completed formal verification may not waive release Gates")
        if boundary.get("actual_device_test_status") != "PASS":
            errors.append("completed formal verification lacks actual-device PASS")
        if approved.get("release_status") != "ELIGIBLE":
            errors.append("completed formal verification lacks release eligibility")
        if remaining_gate_ids:
            errors.append("completed formal verification still lists remaining Gates")
        if boundary.get("all_remaining_gate_status") != "CLOSED":
            errors.append("completed formal verification lacks CLOSED Gate status")
        if boundary.get("formal_test_pass_claimed") is not True:
            errors.append("completed formal verification lacks formal PASS claim")
    if statuses.get(EXPECTED_ROOT_GOAL_ID) == "COMPLETE_AT_TARGET":
        completed_types = {
            node.get("work_item_type")
            for goal_id, node in nodes.items()
            if statuses.get(goal_id) == "COMPLETE_AT_TARGET"
        }
        required_terminal_types = {
            "RELEASE_DECISION",
            "DEPLOYMENT_DELIVERY_EVENT",
            "OPERATION_EVENT",
            "HANDOVER_CLOSURE_EVENT",
        }
        if not required_terminal_types.issubset(completed_types):
            errors.append(
                "project completion lacks release, delivery, operation, or "
                "handover evidence Goals"
            )
    return errors


def validate(
    root: Path = ROOT,
    *,
    check_continuation: bool = True,
    current_work_session_id: str | None = None,
) -> list[str]:
    errors: list[str] = []
    checkpoint_path = resolve_safe_repo_file(root, CHECKPOINT_RELATIVE.as_posix())
    if checkpoint_path is None:
        return ["checkpoint is missing or unsafe"]
    if check_continuation:
        errors.extend(
            f"continuation: {error}"
            for error in continuation.validate(checkpoint_path, root)
        )
    try:
        checkpoint = load_json(checkpoint_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"checkpoint cannot be loaded: {exc}"]
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        return errors + ["checkpoint goal_execution must be an object"]
    discovered_paths = discover_package_paths(root)
    manifest_errors, manifest = load_manifest(root)
    errors.extend(manifest_errors)
    if not manifest:
        return errors
    errors.extend(validate_manifest(root, state, manifest, discovered_paths))
    errors.extend(validate_superseded_v1(root))
    errors.extend(validate_superseded_v2_0(root))
    errors.extend(validate_superseded_v2_1(root))
    errors.extend(legacy.validate_canonical_bindings_shape(checkpoint))
    bindings = canonical_binding_map(checkpoint)

    manifest_nodes = manifest_node_map(manifest)
    static_paths = {
        declaration.get("path")
        for declaration in manifest_nodes.values()
        if isinstance(declaration.get("path"), str)
    }
    dynamic_paths = [
        path
        for path in discovered_paths
        if path.endswith(".md") and "/work-items/" in path
    ]
    goal_paths = sorted(static_paths | set(dynamic_paths))
    nodes: dict[str, dict[str, Any]] = {}
    paths_by_id: dict[str, str] = {}
    for relative in goal_paths:
        path = resolve_safe_repo_file(root, relative)
        if path is None:
            errors.append(f"Goal document is missing or unsafe: {relative}")
            continue
        try:
            metadata, body = parse_goal(path)
        except (OSError, ValueError, tomllib.TOMLDecodeError) as exc:
            errors.append(f"{relative}: Goal cannot be parsed: {exc}")
            continue
        errors.extend(validate_goal_document(relative, metadata, body))
        goal_id = metadata.get("goal_id")
        if not isinstance(goal_id, str) or not goal_id:
            errors.append(f"{relative}: Goal ID is missing")
        elif goal_id in nodes:
            errors.append(f"duplicate Goal ID: {goal_id}")
        else:
            nodes[goal_id] = metadata
            paths_by_id[goal_id] = relative
    if set(manifest_nodes) - set(nodes):
        errors.append("manifest static Goal document set is incomplete")
    errors.extend(validate_graph(nodes, manifest))

    mapping_errors, mapping = load_policy_gap_mapping(root, bindings)
    errors.extend(mapping_errors)
    if not mapping_errors:
        errors.extend(
            validate_policy_scope_against_mapping(root, bindings, mapping)
        )
    errors.extend(validate_gap_backlog_pair(root, bindings))
    errors.extend(
        validate_dynamic_work_items(
            root,
            manifest,
            nodes,
            paths_by_id,
            mapping,
            bindings,
            checkpoint.get("goal_execution", {}).get("status_by_goal"),
        )
    )
    backlog_binding = bindings.get("IMPLEMENTATION_BACKLOG")
    backlog: dict[str, Any] = {}
    if not isinstance(backlog_binding, dict):
        errors.append("IMPLEMENTATION_BACKLOG canonical binding is missing")
    else:
        backlog_path = resolve_safe_repo_file(root, backlog_binding.get("path"))
        if backlog_path is None:
            errors.append("IMPLEMENTATION_BACKLOG path is missing or unsafe")
        elif backlog_binding.get("file_sha256") != sha256_file(backlog_path):
            errors.append("IMPLEMENTATION_BACKLOG binding SHA-256 differs")
        else:
            try:
                backlog = load_json(backlog_path)
            except (OSError, ValueError, json.JSONDecodeError) as exc:
                errors.append(f"IMPLEMENTATION_BACKLOG cannot be loaded: {exc}")
    if backlog:
        errors.extend(validate_workstreams_against_backlog(nodes, backlog, mapping))
    errors.extend(validate_artifact_graph(root, bindings))
    if backlog:
        errors.extend(
            validate_runtime(
                root,
                checkpoint,
                manifest,
                nodes,
                paths_by_id,
                backlog,
                mapping,
                bindings,
                discovered_paths,
                current_work_session_id,
            )
        )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--skip-continuation", action="store_true")
    parser.add_argument("--current-work-session-id")
    args = parser.parse_args()
    root = args.root.resolve()
    errors = validate(
        root,
        check_continuation=not args.skip_continuation,
        current_work_session_id=args.current_work_session_id,
    )
    if errors:
        print("WalkSafe Goal graph check: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    checkpoint = load_json(root / CHECKPOINT_RELATIVE)
    state = checkpoint["goal_execution"]
    print(
        "WalkSafe Goal graph check: PASS "
        f"(dynamic DAG, {state['goal_document_count']} materialized Goals, "
        f"ready {len(state['ready_frontier_goal_ids'])}, "
        f"focus {state['focus_goal_id']}, {state['activation_status']})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
