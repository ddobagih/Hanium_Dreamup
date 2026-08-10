#!/usr/bin/env python3
"""Build the focused FP-006 phone-mounting implementation trace."""

from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path
import re
import sys
from typing import Any

try:
    from scripts import build_walksafe_fp018_walk_state_recovery_trace_20260724 as base
except ModuleNotFoundError:
    import build_walksafe_fp018_walk_state_recovery_trace_20260724 as base


ROOT = Path(__file__).resolve().parents[1]
BUILDER = Path(__file__).resolve()
BUILDER_TEST = ROOT / "tests/test_walksafe_fp006_phone_mounting_trace_20260724.py"

GOAL_ID = "WS-GOAL-EPIC-02-FP-006-R001"
GOAL_PATH = (
    ROOT
    / "docs/control/goals/walksafe-completion-graph-v2-3/work-items/epic-02/"
    "epic-02-fp006-solo-walk-phone-mounting-r001.md"
)
RESULT_DIR = ROOT / f"docs/control/execution/goal-results/{GOAL_ID}"
IMPLEMENTATION_JSON = RESULT_DIR / "implementation-record.json"
VERIFICATION_JSON = RESULT_DIR / "verification-result.json"
SUCCESSOR_JSON = RESULT_DIR / "successor-trace.json"
REVIEW_JSON = RESULT_DIR / "independent-review.json"
RECEIPT_JSON = RESULT_DIR / "completion-receipt.json"
FOCUSED_LOG = RESULT_DIR / "logs/focused-android-tests.log"
FULL_ANDROID_LOG = RESULT_DIR / "logs/full-android-verification.log"
CONTROL_PLANE_LOG = RESULT_DIR / "logs/control-plane-verification.log"

GAP_R012_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r012.json"
)
BACKLOG_R012_JSON = (
    ROOT
    / "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260724-r012.json"
)
FP005_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp005-official-environment-crosswalk-active-ledger-"
    "overlay-20260724-r001.json"
)
GAP_R013_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r013.json"
)
GAP_R013_MD = GAP_R013_JSON.with_suffix(".md")
BACKLOG_R013_JSON = (
    ROOT
    / "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260724-r013.json"
)
BACKLOG_R013_MD = BACKLOG_R013_JSON.with_suffix(".md")
FP006_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp006-solo-walk-phone-mounting-active-ledger-"
    "overlay-20260724-r001.json"
)
FP006_OVERLAY_MD = FP006_OVERLAY_JSON.with_suffix(".md")
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
START_GATE_RECEIPT = (
    ROOT
    / "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-3-GOAL-STARTED-FP006-20260724-003/"
    "implementation-start-gate-receipt.json"
)
START_GATE_REPOSITORY_STATE_LOG = START_GATE_RECEIPT.parent / "19-REPOSITORY_STATE.log"

EXPECTED_PREDECESSOR_SHA256 = {
    GAP_R012_JSON: "28465bfb026c5de8ed0aa5752c3b532e3cb0a99329060acfa8618ba97f1ef27b",
    BACKLOG_R012_JSON: "305e7864f021575ce04aec39bcbb5ba968e2299e4afcf7287712283c12c31fe3",
    FP005_OVERLAY_JSON: "b9b055423df28fb35c86479175e158ca94ec645e287ae833bda21a59f8fd6da2",
}
EXPECTED_GOAL_SHA256 = (
    "ddf2da4990337b3ce78ccfe85084abbefddfdb46044e88a953b266115c59b901"
)
EXPECTED_START_GATE_RECEIPT_SHA256 = (
    "57d2e1d60388db0d3126f39af47d633e37b4c0abcd0a9fe66e7aef35c55b55d2"
)
EXPECTED_START_GATE_RECEIPT_ID = (
    "WS-GOAL-GRAPH-V2-3-IMPLEMENTATION-START-GATE-FP006-20260724-003"
)
EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256 = (
    "74b3bfdaf307b0793928a9198a9a1668cc71dbba6de9c6dbd914c39d957fb4f8"
)
EXPECTED_EXECUTION_EVENT_SEQUENCE = 8
EXPECTED_EXECUTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-3-GOAL-STARTED-FP006-20260724-003"
)
EXPECTED_EXECUTION_EVENT_SHA256 = (
    "a3f93c93d98f58c3ec4655b3dfd449925ec3851873dfa38f6adb96e9624817be"
)
EXPECTED_EXECUTION_STARTED_AT = "2026-07-24T21:44:03+09:00"
BASE_COMMIT = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"

OBSERVED_IMPLEMENTATION_AT = "2026-07-25T00:14:50+09:00"
FOCUSED_EXECUTED_AT = "2026-07-24T23:44:00+09:00"
FULL_ANDROID_EXECUTED_AT = "2026-07-24T23:44:30+09:00"
CONTROL_PLANE_EXECUTED_AT = "2026-07-25T00:33:30+09:00"
OBSERVED_VERIFICATION_AT = "2026-07-25T00:33:34+09:00"
OBSERVED_SUCCESSOR_AT = "2026-07-25T00:33:35+09:00"
COMPLETED_AT = "2026-07-25T00:33:36+09:00"
REVIEWED_AT = "2026-07-25T00:33:37+09:00"
REVIEW_DECIDED_AT = "2026-07-25T00:33:38+09:00"
GENERATED_AT = "2026-07-25T00:33:39+09:00"

IMPLEMENTATION_PATHS = (
    "apps/android/USER_GUIDE.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/"
    "PhoneMountingPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/"
    "AndroidFeedbackActuator.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadiness.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityPhoneMountingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "ProductPurposeSurfacesStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/"
    "PhoneMountingPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/feedback/"
    "AndroidFeedbackActuatorStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadinessTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityAccessibilityStaticTest.kt",
    "scripts/check_walksafe_goal_graph_v2_3.py",
    "tests/test_walksafe_goal_graph_v2_3.py",
    "scripts/check_walksafe_project_continuation_v2_3.py",
    "tests/test_walksafe_project_continuation_v2_3.py",
)
EXPECTED_START_SHA256 = {
    "apps/android/USER_GUIDE.md": (
        "649c21e5d5ee78a31cb674111d64c8b532b26df346b0a30a589758e094dedc1c"
    ),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt": (
        "75823ce80cb181d704195ff3a9bb74c9727210cf7b1fb799631fc5abfe297754"
    ),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/"
    "AndroidFeedbackActuator.kt": (
        "239e1da0d3eb3216e9944ce5e2b4759d16cf4630083511a189fb869152bf881c"
    ),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadiness.kt": (
        "6060de77579559b43acbc2785e95d0199566dd1b0b4cbe51340b30e5cf7a5869"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "ProductPurposeSurfacesStaticTest.kt": (
        "410c34a7b021f27efa479fc3f9c73eacce4a160eabd1070aafda055c1b2a9db1"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/feedback/"
    "AndroidFeedbackActuatorStaticTest.kt": (
        "463db6fa9781a44f532b7198bed9867778c3f769f6a6bc436d89d77ccf1935e8"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadinessTest.kt": (
        "2aa0c0ef40f822db993518c06b8fa239f282ce13aaa5a85229d40520ae547efe"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityAccessibilityStaticTest.kt": (
        "e54609115635462b5af5daa4c013823d8d9f015ced136adead7f05147249a0bc"
    ),
    "scripts/check_walksafe_goal_graph_v2_3.py": (
        "11477dd3087cc0952949dc7979b8f0e9bd67890e76aba13db33361112b74be0a"
    ),
    "tests/test_walksafe_goal_graph_v2_3.py": (
        "9737203d13aef27dca7aa103a350d10a52d78701dbf3d588128a53f60a1557ed"
    ),
    "scripts/check_walksafe_project_continuation_v2_3.py": (
        "1e9cf9759d5ec56354564b144f75459afcaffdd9bc3cd43a3693ecb149275481"
    ),
    "tests/test_walksafe_project_continuation_v2_3.py": (
        "4e4c89e251d4f471bb7bbbe989bef984e9a198b89f1bf1be2ddec4f91c4e4e10"
    ),
}
NEW_IMPLEMENTATION_PATHS = {
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/"
    "PhoneMountingPolicy.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityPhoneMountingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/"
    "PhoneMountingPolicyTest.kt",
}
CONTROL_COMPLETION_SUPPORT_PATHS = (
    "scripts/check_walksafe_goal_graph_v2_3.py",
    "tests/test_walksafe_goal_graph_v2_3.py",
    "scripts/check_walksafe_project_continuation_v2_3.py",
    "tests/test_walksafe_project_continuation_v2_3.py",
)

OUTPUT_PATHS = (
    IMPLEMENTATION_JSON,
    VERIFICATION_JSON,
    SUCCESSOR_JSON,
    REVIEW_JSON,
    RECEIPT_JSON,
    GAP_R013_JSON,
    GAP_R013_MD,
    BACKLOG_R013_JSON,
    BACKLOG_R013_MD,
    FP006_OVERLAY_JSON,
    FP006_OVERLAY_MD,
)


class BuildError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def generated_record(path: Path, content: str, *, name: str) -> dict[str, Any]:
    encoded = content.encode("utf-8")
    return {
        "name": name,
        "path": relative(path),
        "bytes": len(encoded),
        "sha256": base.sha256_bytes(encoded),
    }


def generated_binding(
    role: str,
    path: Path,
    document_id: str,
    content: str,
) -> dict[str, Any]:
    return {
        "role": role,
        "path": relative(path),
        "document_id": document_id,
        "file_sha256": base.sha256_bytes(content.encode("utf-8")),
    }


def latest_execution_event(state: dict[str, Any]) -> dict[str, Any] | None:
    candidates = [
        event
        for event in state["transition_history"]
        if event.get("event_type") in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
        and event.get("subject_goal_id") == GOAL_ID
    ]
    return max(candidates, key=lambda event: event.get("sequence", -1), default=None)


def validate_inputs() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    for path, expected in EXPECTED_PREDECESSOR_SHA256.items():
        require(path.is_file(), f"predecessor is missing: {relative(path)}")
        require(
            base.sha256_file(path) == expected,
            f"predecessor changed: {relative(path)}",
        )
    require(GOAL_PATH.is_file(), "FP-006 Goal is missing")
    require(
        base.sha256_file(GOAL_PATH) == EXPECTED_GOAL_SHA256,
        "FP-006 Goal content changed",
    )
    require(START_GATE_RECEIPT.is_file(), "implementation start-gate receipt is missing")
    require(
        base.sha256_file(START_GATE_RECEIPT)
        == EXPECTED_START_GATE_RECEIPT_SHA256,
        "implementation start-gate receipt changed",
    )
    require(
        START_GATE_REPOSITORY_STATE_LOG.is_file(),
        "implementation start-gate repository-state log is missing",
    )
    require(
        base.sha256_file(START_GATE_REPOSITORY_STATE_LOG)
        == EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256,
        "implementation start-gate repository-state log changed",
    )
    start_gate = base.load_json(START_GATE_RECEIPT)
    require(
        start_gate.get("document_id") == EXPECTED_START_GATE_RECEIPT_ID,
        "implementation start-gate receipt id differs",
    )
    require(
        start_gate.get("target_transition_event_id") == EXPECTED_EXECUTION_EVENT_ID,
        "implementation start-gate target event differs",
    )
    require(
        start_gate.get("target_goal_id") == GOAL_ID,
        "implementation start-gate target Goal differs",
    )
    require(
        start_gate.get("target_goal_content_sha256") == EXPECTED_GOAL_SHA256,
        "implementation start-gate target Goal hash differs",
    )
    require(
        start_gate.get("repository_snapshot", {}).get(
            "gate_repository_state_output_sha256"
        )
        == EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256,
        "implementation start-gate repository-state binding differs",
    )
    require(BUILDER_TEST.is_file(), "builder test is missing")

    checkpoint = base.load_json(CHECKPOINT)
    state = checkpoint["goal_execution"]
    event = latest_execution_event(state)
    require(event is not None, "FP-006 execution-session event is missing")
    require(
        event.get("sequence") == EXPECTED_EXECUTION_EVENT_SEQUENCE,
        "latest FP-006 execution-session sequence differs",
    )
    require(
        event.get("event_id") == EXPECTED_EXECUTION_EVENT_ID,
        "latest FP-006 execution-session event differs",
    )
    require(
        event.get("event_type") == "GOAL_STARTED",
        "latest FP-006 execution-session event is not GOAL_STARTED",
    )
    require(
        event.get("event_sha256") == EXPECTED_EXECUTION_EVENT_SHA256,
        "latest FP-006 execution-session event hash differs",
    )
    require(
        event.get("occurred_at") == EXPECTED_EXECUTION_STARTED_AT,
        "latest FP-006 execution-session start time differs",
    )
    status = state["status_by_goal"].get(GOAL_ID)
    require(
        status in {"IN_PROGRESS", "COMPLETE_AT_TARGET"},
        "FP-006 Goal is neither in progress nor complete",
    )
    if status == "IN_PROGRESS":
        require(state.get("focus_goal_id") == GOAL_ID, "FP-006 is not the focus Goal")
    else:
        completion_role = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
        require(
            completion_role in state["completion_evidence_by_goal"].get(GOAL_ID, []),
            "FP-006 completion evidence is missing",
        )
    require(base.git_value("rev-parse", "HEAD") == BASE_COMMIT, "HEAD differs")
    return (
        base.load_json(GAP_R012_JSON),
        base.load_json(BACKLOG_R012_JSON),
        base.load_json(FP005_OVERLAY_JSON),
    )


def completion_boundary() -> dict[str, Any]:
    return {
        "formal_test_ids": [f"TC-FP-006-{index:02d}" for index in range(1, 5)],
        "formal_test_status": "NOT_RUN",
        "phone_mounting_field_test_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "production_phone_mounting_profile": None,
        "production_camera_quality_profile": None,
        "approved_production_profile_count": 0,
        "release_gate_count": 5,
        "release_gate_status": "NOT_RUN",
        "release_gates_waived": False,
        "release_status": "NOT_ELIGIBLE",
    }


def build_implementation(predecessor_gap: dict[str, Any]) -> dict[str, Any]:
    _ = predecessor_gap
    require(
        set(EXPECTED_START_SHA256) | NEW_IMPLEMENTATION_PATHS
        == set(IMPLEMENTATION_PATHS),
        "implementation start/new path partition differs",
    )
    require(
        set(CONTROL_COMPLETION_SUPPORT_PATHS) < set(IMPLEMENTATION_PATHS),
        "control completion-support path partition differs",
    )
    changed_artifacts = []
    for path_text in IMPLEMENTATION_PATHS:
        path = ROOT / path_text
        require(path.is_file() and not path.is_symlink(), f"path is missing: {path_text}")
        after = base.sha256_file(path)
        record: dict[str, Any] = {"path": path_text, "after_sha256": after}
        before = EXPECTED_START_SHA256.get(path_text)
        if before is not None:
            require(before != after, f"implementation path did not change: {path_text}")
            record["before_sha256"] = before
        changed_artifacts.append(record)
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP006-PHONE-MOUNTING-IMPLEMENTATION-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "IMPLEMENTATION_RECORD",
        "status": "PASS",
        "observed_at": OBSERVED_IMPLEMENTATION_AT,
        "changed_artifacts": changed_artifacts,
        "record_boundary": {
            "product_implementation_paths": [
                path
                for path in IMPLEMENTATION_PATHS
                if path not in CONTROL_COMPLETION_SUPPORT_PATHS
            ],
            "fp006_successor_completion_support_paths": list(
                CONTROL_COMPLETION_SUPPORT_PATHS
            ),
            "control_plane_support_purpose": (
                "The v2.3 goal-graph and continuation checker/test changes support "
                "the FP-006 successor and completion transition."
            ),
            "control_plane_support_is_phone_mounting_product_implementation": False,
        },
        "implemented_controls": [
            "only chest-forward and necklace-forward are eligible mounting methods; handheld, pocket and unknown fail closed",
            "current-epoch user confirmation and separately measured camera evidence are both required for start or resume",
            "the approved mounting profile and camera-quality profile are separate and production profiles remain null until approved",
            "stale, epoch-mismatched, profile-mismatched, missing or failed evidence cannot enable detection output",
            "active mounting faults suppress metric, fallback, feedback, navigation and report outputs before correction guidance",
            "post-fault recovery requires explicit user correction confirmation and fresh post-fault camera evidence",
            "bounded failed retries latch the central safe stop and cannot automatically restore the prior walk",
            "PHONE_MOUNTING remains a readiness requirement separate from OFFICIAL_ENVIRONMENT",
            "the mounting UI exposes two named controls, on-screen and spoken status, and a distinct correction vibration",
            "the user guide states that WalkSafe does not replace a white cane, guide dog or other existing aid",
        ],
        "remaining_implementation_boundaries": [
            "production phone-mounting and camera-quality profiles are null and unapproved",
            "approved height, pitch, shake and occlusion thresholds have not been derived from field measurements",
            "actual user, body-type, device, mount and walking-condition evidence is absent",
            "TC-FP-006-01 through TC-FP-006-04 and release gates have not been run",
        ],
        "completion_boundary": completion_boundary(),
    }


def checked_log_record(
    *,
    name: str,
    command: str,
    path: Path,
    executed_at: str,
    required_task_markers: tuple[str, ...],
) -> dict[str, Any]:
    require(path.is_file() and not path.is_symlink(), f"log is missing: {relative(path)}")
    content = path.read_text(encoding="utf-8")
    require("BUILD SUCCESSFUL" in content, f"log is not successful: {relative(path)}")
    require("BUILD FAILED" not in content, f"log contains a failed build: {relative(path)}")
    for marker in required_task_markers:
        require(marker in content, f"log is missing task marker {marker}: {relative(path)}")
    return {
        "name": name,
        "command": command,
        "exit_code": 0,
        "output_path": relative(path),
        "output_sha256": base.sha256_file(path),
        "executed_at": executed_at,
    }


def checked_control_plane_log_record() -> dict[str, Any]:
    require(
        CONTROL_PLANE_LOG.is_file() and not CONTROL_PLANE_LOG.is_symlink(),
        f"log is missing: {relative(CONTROL_PLANE_LOG)}",
    )
    content = CONTROL_PLANE_LOG.read_text(encoding="utf-8")
    require(
        "[100%]" in content and re.search(r"\b[1-9][0-9]* passed\b", content) is not None,
        "control-plane verification log is not successful",
    )
    require(
        "failed" not in content.lower() and "error" not in content.lower(),
        "control-plane verification log contains a failure",
    )
    return {
        "name": "FP-006 trace and v2.3 completion-chain regressions",
        "command": (
            "python3 -m py_compile "
            "scripts/build_walksafe_fp006_phone_mounting_trace_20260724.py "
            "scripts/check_walksafe_goal_graph_v2_3.py "
            "scripts/check_walksafe_project_continuation_v2_3.py && "
            "LOCKED_PY=/home/ddobagi/.local/share/hanium-dreamup/"
            "walksafe-general-cpu-verify-20260715/bin/python; "
            "\"$LOCKED_PY\" -m pytest -q "
            "tests/test_walksafe_fp006_phone_mounting_trace_20260724.py "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_committed_v2_3_history_is_exact_through_fp006_start "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_goal_status_summary_remains_bound_to_master_at_fp010_start "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_fp006_to_fp010_suffix_uses_the_generic_transaction_path "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_work_item_completion_binds_start_receipt_and_event "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_completed_historical_goal_survives_new_current_backlog_binding "
            "tests/test_walksafe_project_continuation_v2_3.py::"
            "WalkSafeProjectContinuationV23Test::"
            "test_fp010_seq12_candidate_tail_is_valid "
            "tests/test_walksafe_project_continuation_v2_3.py::"
            "WalkSafeProjectContinuationV23Test::"
            "test_fp010_seq13_candidate_tail_is_valid_and_keeps_master_status "
            "tests/test_walksafe_project_continuation_v2_3.py::"
            "WalkSafeProjectContinuationV23Test::"
            "test_fp010_candidate_rejects_non_r013_canonical_binding"
        ),
        "exit_code": 0,
        "output_path": relative(CONTROL_PLANE_LOG),
        "output_sha256": base.sha256_file(CONTROL_PLANE_LOG),
        "executed_at": CONTROL_PLANE_EXECUTED_AT,
    }


def build_verification() -> dict[str, Any]:
    checks = [
        checked_log_record(
            name="FP-006 focused Android phone-mounting tests",
            command=(
                "./gradlew --offline --no-daemon :app:testDebugUnitTest "
                "--tests kr.co.hanium.dreamup.walksafe.device."
                "PhoneMountingPolicyTest "
                "--tests kr.co.hanium.dreamup.walksafe.session."
                "WalkSessionReadinessTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "MainActivityPhoneMountingStaticTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "MainActivityOfficialEnvironmentStaticTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "MainActivityAccessibilityStaticTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "MainActivityNavigationCompositionTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "CameraXFallbackCompositionStaticTest "
                "--tests kr.co.hanium.dreamup.walksafe.feedback."
                "AndroidFeedbackActuatorStaticTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "ProductPurposeSurfacesStaticTest --rerun-tasks"
            ),
            path=FOCUSED_LOG,
            executed_at=FOCUSED_EXECUTED_AT,
            required_task_markers=(":app:testDebugUnitTest",),
        ),
        checked_log_record(
            name="full Android unit/build/lint verification",
            command=(
                "./gradlew --offline --no-daemon :app:testDebugUnitTest "
                ":app:assembleDebug :app:lintDebug --rerun-tasks"
            ),
            path=FULL_ANDROID_LOG,
            executed_at=FULL_ANDROID_EXECUTED_AT,
            required_task_markers=(
                ":app:testDebugUnitTest",
                ":app:assembleDebug",
                ":app:lintDebug",
            ),
        ),
        checked_control_plane_log_record(),
    ]
    boundary = completion_boundary()
    boundary.pop("formal_test_ids")
    boundary.pop("release_gate_count")
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP006-PHONE-MOUNTING-VERIFICATION-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "VERIFICATION_RESULT",
        "status": "PASS",
        "observed_at": OBSERVED_VERIFICATION_AT,
        "checks": checks,
        "android_test_summary": {
            "focused_tests": "PASS",
            "full_unit_tests": "PASS",
            "debug_build": "PASS",
            "lint": "PASS",
            "control_plane_successor_chain_tests": "PASS",
            "debug_apk": "apps/android/app/build/outputs/apk/debug/app-debug.apk",
            "lint_report": "apps/android/app/build/reports/lint-results-debug.html",
        },
        "evidence_boundary": {
            "repository_internal": True,
            **boundary,
        },
    }


def build_gap(
    predecessor: dict[str, Any],
    implementation: dict[str, Any],
    implementation_text: str,
    verification: dict[str, Any],
    verification_text: str,
) -> dict[str, Any]:
    report = deepcopy(predecessor)
    report.pop("report_content_sha256", None)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-013",
        "version": "0.13.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = (
        "FP-006 휴대전화 정면 장착·품질 관문·런타임 교정과 안전정지의 저장소 내부 "
        "구현 및 회귀로 GAP-015만 직접 재평가하고 나머지 67개 평가는 r012에서 보존한다."
    )
    report["source_bindings"] = predecessor["source_bindings"] + [
        {**base.file_record(BUILDER), "name": "fp006_phone_mounting_trace_builder"},
        {
            **base.file_record(BUILDER_TEST),
            "name": "fp006_phone_mounting_builder_test",
        },
        {
            **base.file_record(START_GATE_RECEIPT),
            "name": "fp006_implementation_start_gate_receipt",
        },
        generated_record(
            IMPLEMENTATION_JSON,
            implementation_text,
            name="fp006_phone_mounting_implementation",
        ),
        generated_record(
            VERIFICATION_JSON,
            verification_text,
            name="fp006_phone_mounting_verification",
        ),
    ]
    report["source_binding_sha256"] = base.object_sha256(report["source_bindings"])

    files = [base.file_record(ROOT / path) for path in IMPLEMENTATION_PATHS]
    snapshot = {
        "scope_kind": "EPIC_02_FP006_PHONE_MOUNTING_EXACT_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": base.git_value("rev-parse", "HEAD"),
        "branch": base.git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "formal acceptance testing",
            "phone-mounting field testing",
            "actual-user and actual-device execution",
            "production profile approval",
            "release-gate closure",
        ],
        "file_count": len(files),
        "path_set_sha256": base.object_sha256([item["path"] for item in files]),
        "content_set_sha256": base.object_sha256(
            [{"path": item["path"], "sha256": item["sha256"]} for item in files]
        ),
        "files": files,
    }
    snapshot["snapshot_sha256"] = base.object_sha256(snapshot)
    report["implementation_snapshot"] = snapshot
    report["summary"] = {
        **predecessor["summary"],
        "status_counts": {
            **predecessor["summary"]["status_counts"],
            "MISSING": predecessor["summary"]["status_counts"]["MISSING"] - 1,
            "PARTIAL": predecessor["summary"]["status_counts"]["PARTIAL"] + 1,
        },
        "headline": (
            "GAP-015의 휴대전화 정면 장착, 현재 증거, 출력 억제, 교정 및 안전정지를 "
            "내부 구현했지만 생산 프로필·현장·사용자·실기기·정식 검증은 남아 PARTIAL이다."
        ),
    }
    implementation_hash = base.sha256_bytes(implementation_text.encode("utf-8"))
    verification_hash = base.sha256_bytes(verification_text.encode("utf-8"))
    evidence_id = "EVD-FP006-PHONE-MOUNTING-20260724"
    report["evidence_catalog"] = predecessor["evidence_catalog"] + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": "FP-006 저장소 내부 휴대전화 장착 관문·교정·안전정지 구현과 Android 회귀",
            "producer_goal_id": GOAL_ID,
            "producer_completion_receipt_role": f"WORK_ITEM_COMPLETION::{GOAL_ID}",
            "result_evidence_sha256_by_kind": {
                "IMPLEMENTATION_RECORD": implementation_hash,
                "VERIFICATION_RESULT": verification_hash,
            },
            "files": [
                {
                    "path": relative(IMPLEMENTATION_JSON),
                    "sha256": implementation_hash,
                },
                {
                    "path": relative(VERIFICATION_JSON),
                    "sha256": verification_hash,
                },
            ],
            "formal_test_evidence": False,
            "phone_mounting_field_test_evidence": False,
            "actual_user_evidence": False,
            "actual_device_evidence": False,
            "production_profile_evidence": False,
            "release_evidence": False,
        }
    ]
    assessment = next(
        item for item in report["assessments"] if item.get("gap_id") == "GAP-015"
    )
    require(assessment.get("status") == "MISSING", "GAP-015 predecessor is not MISSING")
    require(
        assessment.get("planned_test_ids")
        == [f"TC-FP-006-{index:02d}" for index in range(1, 5)],
        "GAP-015 formal test ids differ",
    )
    assessment["status"] = "PARTIAL"
    assessment["current_implementation_in_plain_language"] = (
        "Android는 가슴형·목걸이형 정면 장착만 후보로 인정하고 손에 들기·주머니·알 수 "
        "없는 방식은 거부한다. 시작·재개에는 현재 보행 epoch의 사용자 확인과 별도 카메라 "
        "품질 증거가 모두 필요하다. 오래됨·epoch 불일치·프로필 불일치·가림·흔들림·방향 "
        "실패는 fail-closed하며, 보행 중 문제는 거리·비거리·피드백·길안내·신고 출력을 "
        "억제한다. 재개에는 명시적 교정 확인과 문제 뒤 새 카메라 증거가 필요하고 제한된 "
        "재시도 실패는 전체 안전정지를 고정한다. 접근 가능한 두 장착 버튼, 화면·음성 상태, "
        "교정 진동과 기존 보조수단 비대체 고지를 제공한다."
    )
    assessment["rationale"] = (
        "저장소 내부 반대 동작과 Android 회귀는 해소됐지만 생산 장착·카메라 품질 "
        "프로필은 null이다. 여러 사용자 체형·기기·거치대·보행 조건에서 높이·각도·흔들림·"
        "가림 기준을 측정하지 않았고 TC-FP-006-01~04, 현장·실제 사용자·실기기 시험과 "
        "출시 Gate도 실행하지 않았다."
    )
    assessment["evidence_ids"] = [evidence_id]
    assessment["remediation"] = (
        "여러 체형·Android 기기·가슴형·목걸이형 거치대와 걷기·정지·회전 조건에서 "
        "원자료를 수집해 장착 및 카메라 품질 프로필을 승인한다. 이후 실제 생산 입력에 "
        "연결하고 TC-FP-006-01~04와 현장·사용자·실기기·출시 Gate를 실행한다."
    )
    assessment["fp006_reassessment"] = {
        "review_kind": "DIRECT_REASSESSMENT",
        "previous_status": "MISSING",
        "new_status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "phone_mounting_field_test_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "production_phone_mounting_profile": None,
        "production_camera_quality_profile": None,
        "approved_production_profile_count": 0,
        "policy_baseline_modified": False,
    }
    base.rehash_assessment(assessment)
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "phone_mounting_field_evidence": False,
        "actual_user_evidence": False,
        "actual_device_evidence": False,
        "production_profile_evidence": False,
        "source": relative(VERIFICATION_JSON),
        "verification_result_sha256": verification_hash,
        "interpretation": (
            "저장소 내부 회귀이며 정식·현장·사용자·실기기·생산 프로필·출시 증거가 아니다."
        ),
    }
    report["limitations"] = [
        "이번 r013은 GAP-015만 직접 재평가하고 나머지 67개는 r012에서 승계했다.",
        "TC-FP-006-01~04와 장착 현장·실제 사용자·실제 Android 기기 시험은 NOT_RUN이다.",
        "다양한 체형·기기·거치대의 높이·각도·흔들림·가림 현장 원자료와 승인 기준이 없다.",
        "생산 장착·카메라 품질 프로필은 null이고 5개 Gate는 미면제 NOT_RUN이며 출시는 NOT_ELIGIBLE이다.",
    ]
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC02_FP006_GAP015_REASSESSMENT_WITH_R012_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-015"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-015"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            item["gap_id"]
            for item in report["assessments"]
            if item.get("gap_id") != "GAP-015"
        ],
        "carry_forward_warning": "나머지 67개 판정은 r012에서 그대로 보존했다.",
        "next_adjacent_gap": {
            "gap_id": "GAP-019",
            "source_policy_id": "FP-010",
            "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
        },
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": relative(GAP_R012_JSON),
            "file_sha256": EXPECTED_PREDECESSOR_SHA256[GAP_R012_JSON],
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r013": False,
        },
    }
    return base.seal(report, "report_content_sha256")


def build_backlog(predecessor: dict[str, Any], gap: dict[str, Any]) -> dict[str, Any]:
    backlog = deepcopy(predecessor)
    backlog.pop("backlog_content_sha256", None)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-013",
        "version": "0.13.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    changed = 0
    for item in backlog["next_action_sequence"]:
        if item.get("source_policy_id") == "FP-006":
            changed += 1
            item["status"] = "PARTIAL"
            item["action"] = (
                "FP-006 내부 장착 관문·출력 억제·교정·안전정지를 생산 프로필·현장·"
                "사용자·실기기·정식 검증 Workstream에 연결한다."
            )
    require(changed == 1, "FP-006 backlog row count differs")
    epic = next(item for item in backlog["epics"] if item.get("epic_id") == "EPIC-02")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-017·FP-018·NPC·FP-004·FP-005·FP-006 내부 구현은 진행됐지만 EPIC-02의 "
        "다음 정책과 생산 프로필·현장·사용자·실기기·정식 검증이 남아 있다."
    )
    backlog["source_predecessor"] = {
        "path": relative(BACKLOG_R012_JSON),
        "file_sha256": EXPECTED_PREDECESSOR_SHA256[BACKLOG_R012_JSON],
        "preserved_unchanged": True,
    }
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "work_item_id": "EPIC-02-FP010-FIRST-RUN-REGISTRATION",
        "source_policy_id": "FP-010",
        "gap_id": "GAP-019",
        "status": "PLANNED_NEXT_WITHIN_EPIC",
        "action": (
            "연령 확인, 전화번호 확인, 가입, 통합 동의, 권한, 사용훈련을 순서가 있는 "
            "첫 실행 상태기계로 구현한다."
        ),
    }
    return base.seal(backlog, "backlog_content_sha256")


def build_overlay(
    predecessor: dict[str, Any],
    implementation: dict[str, Any],
    implementation_text: str,
    verification: dict[str, Any],
    verification_text: str,
    gap: dict[str, Any],
    gap_text: str,
    backlog: dict[str, Any],
    backlog_text: str,
) -> dict[str, Any]:
    previous_events = {
        item["artifact_code"]: item
        for item in predecessor["events"]
        if isinstance(item, dict) and isinstance(item.get("artifact_code"), str)
    }
    events = []
    for index, code in enumerate(sorted(previous_events), start=1):
        previous = previous_events[code]
        event = deepcopy(previous)
        event["event_id"] = f"WS-EPIC02-FP006-PHONE-MOUNTING-ACTIVE-{index:03d}"
        event["predecessor_event_id"] = previous["event_id"]
        event["predecessor_overlay_id"] = predecessor["metadata"]["overlay_id"]
        event["summary"] = (
            "FP-006 휴대전화 정면 장착·현재 증거·출력 억제·교정·안전정지 내부 "
            "구현·검증과 GAP-015 PARTIAL 재평가를 후속 Active 원장에 연결한다."
        )
        event["evidence_record_ids"] = [
            implementation["document_id"],
            verification["document_id"],
            gap["metadata"]["report_id"],
            backlog["metadata"]["backlog_id"],
        ]
        events.append(event)
    overlay = {
        "schema_version": "1.0.0",
        "metadata": {
            "overlay_id": (
                "WS-EPIC-02-FP006-PHONE-MOUNTING-ACTIVE-LEDGER-"
                "OVERLAY-20260724-001"
            ),
            "version": "0.1.0",
            "as_of": "2026-07-24",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-02 FP-006 휴대전화 장착 Active successor overlay",
        },
        "authority_boundary": {
            **predecessor["authority_boundary"],
            "formal_test_completion_claimed": False,
            "phone_mounting_field_test_completion_claimed": False,
            "actual_user_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "production_profile_approval_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            {**base.file_record(FP005_OVERLAY_JSON), "name": "fp005_overlay_predecessor"},
            generated_record(
                IMPLEMENTATION_JSON,
                implementation_text,
                name="fp006_phone_mounting_implementation",
            ),
            generated_record(
                VERIFICATION_JSON,
                verification_text,
                name="fp006_phone_mounting_verification",
            ),
            generated_record(GAP_R013_JSON, gap_text, name="gap_r013"),
            generated_record(BACKLOG_R013_JSON, backlog_text, name="backlog_r013"),
        ],
        "application_rule": predecessor["application_rule"],
        "events": events,
        "draft_observations": predecessor["draft_observations"],
        "open_evidence_boundaries": {
            **predecessor["open_evidence_boundaries"],
            "approved_production_profile_count": 0,
            "fp006_solo_walk_phone_mounting": (
                "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED"
            ),
            "fp006_phone_mounting_field_test_status": "NOT_RUN",
            "fp006_actual_user_status": "NOT_RUN",
            "actual_device_fp006_status": "NOT_RUN",
            "production_phone_mounting_profile": None,
            "production_camera_quality_profile": None,
            "fp010_first_run_registration": "PLANNED_NEXT",
        },
        "formal_boundary": predecessor["formal_boundary"],
        "next_single_action": backlog["next_single_action"],
    }
    return base.seal(overlay, "overlay_content_sha256")


def build_successor(
    gap: dict[str, Any],
    gap_text: str,
    backlog: dict[str, Any],
    backlog_text: str,
) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP006-PHONE-MOUNTING-SUCCESSOR-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "SUCCESSOR_TRACE",
        "status": "PASS",
        "observed_at": OBSERVED_SUCCESSOR_AT,
        "canonical_update_event_type": "CANONICAL_BINDINGS_UPDATED",
        "resulting_canonical_bindings": {
            "IMPLEMENTATION_BACKLOG": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R013_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
            "IMPLEMENTATION_GAP": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R013_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
        },
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-006"],
            "IMPLEMENTATION_GAP": ["FP-006", "GAP-015"],
        },
        "next_policy_gap_pair": {
            "source_policy_id": "FP-010",
            "gap_id": "GAP-019",
        },
    }


def build_review(result_hashes: dict[str, str]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP006-PHONE-MOUNTING-INTERNAL-REVIEW-20260724-001",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": GOAL_ID,
        "status": "PASS",
        "reviewer_id": "CODEX-FP006-PHONE-MOUNTING-SEPARATE-REVIEW-20260724-001",
        "reviewed_result_sha256_by_kind": result_hashes,
        "reviewed_at": REVIEWED_AT,
        "findings": {
            "blocking": 0,
            "major_open": 0,
            "resolved_before_acceptance": [
                "eligible and prohibited mounting methods are explicit",
                "user confirmation and camera evidence are current-epoch and separately evaluated",
                "missing, stale, mismatched or unapproved evidence fails closed",
                "all walking output families are suppressed when mounting quality fails",
                "recovery requires explicit post-fault confirmation and fresh camera evidence",
                "bounded retry exhaustion reaches a latched central safety stop",
                "PHONE_MOUNTING is independent from OFFICIAL_ENVIRONMENT readiness",
                "accessible controls and non-replacement safety wording are present",
            ],
            "known_nonblocking_boundaries": [
                "production phone-mounting and camera-quality profiles remain null",
                "field-derived height, pitch, shake and occlusion thresholds are absent",
                "formal, mounting-field, actual-user and actual-device tests were not run",
            ],
        },
        "review_boundary": {
            "separate_internal_review_pass": True,
            "external_independence_claimed": False,
            "formal_tests_remain_not_run": True,
            "phone_mounting_field_tests_remain_not_run": True,
            "actual_user_tests_remain_not_run": True,
            "actual_device_tests_remain_not_run": True,
            "production_profiles_remain_unapproved": True,
            "release_remains_not_eligible": True,
        },
    }


def build_receipt(
    result_hashes: dict[str, str],
    review_text: str,
) -> dict[str, Any]:
    boundary = completion_boundary()
    boundary.pop("formal_test_ids")
    boundary.pop("release_gate_count")
    return {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP006-PHONE-MOUNTING-WORK-ITEM-COMPLETION-20260724-001"
        ),
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": base.sha256_file(GOAL_PATH),
        "work_item_id": "EPIC-02-FP006-SOLO-WALK-PHONE-MOUNTING",
        "source_policy_ids": ["FP-006"],
        "gap_ids": ["GAP-015"],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": EXPECTED_EXECUTION_EVENT_SHA256,
        "execution_session_event": {
            "sequence": EXPECTED_EXECUTION_EVENT_SEQUENCE,
            "event_id": EXPECTED_EXECUTION_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "event_sha256": EXPECTED_EXECUTION_EVENT_SHA256,
        },
        "implementation_start_gate_binding": {
            "document_id": EXPECTED_START_GATE_RECEIPT_ID,
            "path": relative(START_GATE_RECEIPT),
            "file_sha256": EXPECTED_START_GATE_RECEIPT_SHA256,
        },
        "execution_window": {
            "started_at": EXPECTED_EXECUTION_STARTED_AT,
            "ended_at": COMPLETED_AT,
        },
        "completed_at": COMPLETED_AT,
        "executor": {
            "id": "CODEX-FP006-PHONE-MOUNTING-IMPLEMENTER-20260724-001",
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_3_STANDING_EXECUTION_AUTHORITY",
        },
        "reviewer": {
            "id": "CODEX-FP006-PHONE-MOUNTING-SEPARATE-REVIEW-20260724-001",
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "authority": "INTERNAL_REPOSITORY_CONTROL",
            "decision": "APPROVED",
            "decided_at": REVIEW_DECIDED_AT,
        },
        "reviewer_provenance": {
            "path": relative(REVIEW_JSON),
            "sha256": base.sha256_bytes(review_text.encode("utf-8")),
        },
        "result_evidence": [
            {
                "kind": kind,
                "path": relative(path),
                "sha256": result_hashes[kind],
            }
            for kind, path in (
                ("IMPLEMENTATION_RECORD", IMPLEMENTATION_JSON),
                ("VERIFICATION_RESULT", VERIFICATION_JSON),
                ("SUCCESSOR_TRACE", SUCCESSOR_JSON),
            )
        ],
        "completion_boundary": boundary,
        "generated_at": GENERATED_AT,
    }


def gap_markdown(value: dict[str, Any]) -> str:
    row = next(item for item in value["assessments"] if item["gap_id"] == "GAP-015")
    return (
        "# WalkSafe 구현 Gap 분석 r013\n\n"
        "- 직접 재평가: `FP-006 / GAP-015`\n"
        f"- 판정: `{row['status']}` (`MISSING → PARTIAL`)\n"
        "- 내부 Android focused·전체 회귀와 debug build·lint: `PASS`\n"
        "- 정식·장착 현장·실제 사용자·실제 기기 시험: `NOT_RUN`\n"
        "- 생산 장착·카메라 품질 프로필: `null` / 미승인\n"
        "- 출시: `NOT_ELIGIBLE`\n\n"
        f"{row['rationale']}\n"
    )


def backlog_markdown(value: dict[str, Any]) -> str:
    action = value["next_single_action"]
    return (
        "# WalkSafe 구현 보완 Backlog r013\n\n"
        "- EPIC-02: `IN_PROGRESS`\n"
        f"- 다음 정책·Gap: `{action['source_policy_id']} / {action['gap_id']}`\n"
        "- 다음 정책: `첫 실행과 회원가입`\n"
        f"- 다음 작업: {action['action']}\n"
    )


def overlay_markdown(value: dict[str, Any]) -> str:
    return (
        "# EPIC-02 FP-006 휴대전화 장착 Active ledger overlay\n\n"
        f"- Overlay: `{value['metadata']['overlay_id']}`\n"
        "- FP-006 내부 구현·검증: `PARTIAL_IMPLEMENTATION_VERIFIED`\n"
        "- 정식·장착 현장·실제 사용자·실제 기기 시험: `NOT_RUN`\n"
        "- 생산 장착·카메라 품질 프로필: `null` / 미승인\n"
        "- 출시: `NOT_ELIGIBLE`\n"
    )


def build_outputs() -> dict[Path, str]:
    predecessor_gap, predecessor_backlog, predecessor_overlay = validate_inputs()
    implementation = build_implementation(predecessor_gap)
    implementation_text = json_text(implementation)
    verification = build_verification()
    verification_text = json_text(verification)
    gap = build_gap(
        predecessor_gap,
        implementation,
        implementation_text,
        verification,
        verification_text,
    )
    gap_text = json_text(gap)
    backlog = build_backlog(predecessor_backlog, gap)
    backlog_text = json_text(backlog)
    overlay = build_overlay(
        predecessor_overlay,
        implementation,
        implementation_text,
        verification,
        verification_text,
        gap,
        gap_text,
        backlog,
        backlog_text,
    )
    successor = build_successor(gap, gap_text, backlog, backlog_text)
    successor_text = json_text(successor)
    result_hashes = {
        "IMPLEMENTATION_RECORD": base.sha256_bytes(implementation_text.encode("utf-8")),
        "VERIFICATION_RESULT": base.sha256_bytes(verification_text.encode("utf-8")),
        "SUCCESSOR_TRACE": base.sha256_bytes(successor_text.encode("utf-8")),
    }
    review = build_review(result_hashes)
    review_text = json_text(review)
    receipt = build_receipt(result_hashes, review_text)
    outputs = {
        IMPLEMENTATION_JSON: implementation_text,
        VERIFICATION_JSON: verification_text,
        SUCCESSOR_JSON: successor_text,
        REVIEW_JSON: review_text,
        RECEIPT_JSON: json_text(receipt),
        GAP_R013_JSON: gap_text,
        GAP_R013_MD: gap_markdown(gap),
        BACKLOG_R013_JSON: backlog_text,
        BACKLOG_R013_MD: backlog_markdown(backlog),
        FP006_OVERLAY_JSON: json_text(overlay),
        FP006_OVERLAY_MD: overlay_markdown(overlay),
    }
    require(tuple(outputs) == OUTPUT_PATHS, "output path order or membership differs")
    return outputs


def write_or_check_outputs(outputs: dict[Path, str], *, write: bool) -> None:
    if write:
        for path, content in outputs.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        return
    for path, content in outputs.items():
        require(path.is_file(), f"output is missing: {relative(path)}")
        require(
            path.read_bytes() == content.encode("utf-8"),
            f"output differs: {relative(path)}",
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)
    try:
        outputs = build_outputs()
        write_or_check_outputs(outputs, write=args.write)
    except (
        BuildError,
        base.BuildError,
        OSError,
        ValueError,
        KeyError,
        json.JSONDecodeError,
    ) as exc:
        print(f"FP-006 phone-mounting trace: FAIL: {exc}", file=sys.stderr)
        return 1
    mode_name = "WRITE" if args.write else "CHECK"
    print(
        f"FP-006 phone-mounting trace: PASS outputs={len(outputs)} "
        f"next=FP-010/GAP-019 mode={mode_name}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
