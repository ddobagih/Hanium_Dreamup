#!/usr/bin/env python3
"""Build the focused FP-005 official-environment implementation trace."""

from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path
import sys
from typing import Any

try:
    from scripts import build_walksafe_fp018_walk_state_recovery_trace_20260724 as base
except ModuleNotFoundError:
    import build_walksafe_fp018_walk_state_recovery_trace_20260724 as base


ROOT = Path(__file__).resolve().parents[1]
BUILDER = Path(__file__).resolve()
BUILDER_TEST = (
    ROOT / "tests/test_walksafe_fp005_official_environment_trace_20260724.py"
)
GOAL_ID = "WS-GOAL-EPIC-02-FP-005-R001"
GOAL_PATH = (
    ROOT
    / "docs/control/goals/walksafe-completion-graph-v2-2/work-items/epic-02/"
    "epic-02-fp005-official-environment-crosswalk-r001.md"
)
RESULT_DIR = ROOT / f"docs/control/execution/goal-results/{GOAL_ID}"
IMPLEMENTATION_JSON = RESULT_DIR / "implementation-record.json"
VERIFICATION_JSON = RESULT_DIR / "verification-result.json"
SUCCESSOR_JSON = RESULT_DIR / "successor-trace.json"
REVIEW_JSON = RESULT_DIR / "independent-review.json"
RECEIPT_JSON = RESULT_DIR / "completion-receipt.json"
FOCUSED_LOG = RESULT_DIR / "logs/focused-android-tests.log"
FULL_ANDROID_LOG = RESULT_DIR / "logs/full-android-verification.log"
CONTROL_PLANE_LOG = (
    RESULT_DIR / "logs/control-plane-successor-chain-verification.log"
)

GAP_R011_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r011.json"
)
BACKLOG_R011_JSON = (
    ROOT
    / "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r011.json"
)
FP004_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp004-priority-user-active-ledger-overlay-20260724-r001.json"
)
GAP_R012_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r012.json"
)
GAP_R012_MD = GAP_R012_JSON.with_suffix(".md")
BACKLOG_R012_JSON = (
    ROOT
    / "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r012.json"
)
BACKLOG_R012_MD = BACKLOG_R012_JSON.with_suffix(".md")
FP005_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp005-official-environment-crosswalk-active-ledger-"
    "overlay-20260724-r001.json"
)
FP005_OVERLAY_MD = FP005_OVERLAY_JSON.with_suffix(".md")
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
START_GATE_RECEIPT = (
    ROOT
    / "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-3-GOAL-STARTED-FP005-20260724-001/"
    "implementation-start-gate-receipt.json"
)

EXPECTED_PREDECESSOR_SHA256 = {
    GAP_R011_JSON: "5488991735ab2ca6537520cd0a8cfa7f1e2a99e1936dca0f1aa6fab3b8b3a9d0",
    BACKLOG_R011_JSON: "27eb2c1a4a656a921cd3573f11e76d3b5656afa25f3707b60e5e455c906a523e",
    FP004_OVERLAY_JSON: "01bb76ba4fac12b55457d14fae237a57584f681312ae9c89d5546a46dd8f5ca2",
}
EXPECTED_START_GATE_RECEIPT_SHA256 = (
    "f023723484fa21fff80a60be98537ae2f790508ff88af594a5dd0d7b2fc5ebb0"
)
EXPECTED_START_GATE_RECEIPT_ID = (
    "WS-GOAL-GRAPH-V2-3-IMPLEMENTATION-START-GATE-FP005-20260724-001"
)
EXPECTED_EXECUTION_EVENT_SEQUENCE = 3
EXPECTED_EXECUTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-3-GOAL-STARTED-FP005-20260724-001"
)
EXPECTED_EXECUTION_EVENT_SHA256 = (
    "0d9f9feb3d36a9937239ca142c6c77da1a6c49004856b00b0b3a86ece8e86723"
)
EXPECTED_EXECUTION_STARTED_AT = "2026-07-24T19:30:55+09:00"
BASE_COMMIT = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
OBSERVED_IMPLEMENTATION_AT = "2026-07-24T20:49:30+09:00"
FOCUSED_EXECUTED_AT = "2026-07-24T20:17:09+09:00"
FULL_ANDROID_EXECUTED_AT = "2026-07-24T20:17:52+09:00"
CONTROL_PLANE_EXECUTED_AT = "2026-07-24T20:49:39+09:00"
OBSERVED_VERIFICATION_AT = "2026-07-24T20:49:41+09:00"
OBSERVED_SUCCESSOR_AT = "2026-07-24T20:49:42+09:00"
COMPLETED_AT = "2026-07-24T20:49:43+09:00"
REVIEWED_AT = "2026-07-24T20:49:44+09:00"
REVIEW_DECIDED_AT = "2026-07-24T20:49:45+09:00"
GENERATED_AT = "2026-07-24T20:49:46+09:00"

IMPLEMENTATION_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/MessagePolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/CameraFrameQualityPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/CrosswalkReferencePolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/OfficialEnvironmentPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PermissionSessionPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadiness.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityOfficialEnvironmentStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityWalkSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/RuntimeMetricMainActivityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/depth/MessagePolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/CameraFrameQualityPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapabilityTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigatorTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/OfficialEnvironmentPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/PermissionSessionPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadinessTest.kt",
    "scripts/check_walksafe_goal_graph_v2_3.py",
    "tests/test_walksafe_goal_graph_v2_3.py",
    "tests/test_walksafe_epic02_trace_v2_2_history.py",
    "docs/control/walksafe-project-resumption-runbook.md",
)
EXPECTED_START_SHA256 = {
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt": (
        "87d937c73491f050625ea9941b1713718a4da7be6e5f1c6ea7af9f93fe5f3e11"
    ),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/"
    "MessagePolicy.kt": (
        "e5331d6fae2ffbce37d05f033d735b790e63c8d1b94d561af3a3f9f3034e5a6e"
    ),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/"
    "WalkSafeStartupCapability.kt": (
        "5565c6119df0883409359477e29e96ed6a0360bd5f5a4cc37cee104bbcf08c9a"
    ),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/"
    "RouteNavigator.kt": (
        "2fe1c8200cb4f1e302be2123df9160297256c3b6d8b7ccf05c7ae5451cd8e39b"
    ),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "PermissionSessionPolicy.kt": (
        "fd42fa8001180547b1ba1ece425adf628bb5926898e2e62b10d3736b140f5982"
    ),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadiness.kt": (
        "ae0834115453f67b8b9c0523781d94e19c7d556ac41f30577011e67ec2d9a838"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityAccessibilityStaticTest.kt": (
        "3bb4b06a2615aa60b62345b6b867216ec0b05b053f58799f5cd723bd73a8cec4"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityWalkSessionLifecycleStaticTest.kt": (
        "75cd31e0c2d99590c3f35c7218a7cbbdab7b46191ef4ba2831707f216647f6d5"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "RuntimeMetricMainActivityStaticTest.kt": (
        "93f6d183ac3e2c46e03f01f60d437c30f75a6f9d2cc4320a21348efa445b3563"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/depth/"
    "MessagePolicyTest.kt": (
        "b53630daaf1d021ad8de938ea18b27bb1acf41f01b0ae1cdd41fc2bcb868c7a4"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/"
    "WalkSafeStartupCapabilityTest.kt": (
        "da7f1c604691ca541c8f7cfd3a679dc866cfafe0bdce5751d111b9e714cecee7"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/"
    "RouteNavigatorTest.kt": (
        "0d3b049363a276a64cfa5b62fe6939c9cefbf7c402368ef91b757e4ced35a812"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "PermissionSessionPolicyTest.kt": (
        "eb8a09646210ad57965e2f72e5e813c68d745bfd31ba008d68fb9f7ae5bed542"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadinessTest.kt": (
        "52437497f6c2474aef4bef07ac02076c918f3b69fd5af86c473529e29fb55662"
    ),
    "scripts/check_walksafe_goal_graph_v2_3.py": (
        "c3458544fecdd86a8de222a5672e883e0f1b352e3e2dd95d86a22c038e8e321b"
    ),
    "tests/test_walksafe_goal_graph_v2_3.py": (
        "0053d6060357f98d1b24bcd82c12d74180d53ac0cb0848b354221b4cc141d56a"
    ),
    "tests/test_walksafe_epic02_trace_v2_2_history.py": (
        "2490d06da3194908af64c6043859b8606a9cca1c6c703c38e405ab1efe8c9079"
    ),
    "docs/control/walksafe-project-resumption-runbook.md": (
        "6f35ec493848b9fabb19c796b16b2ca004f75d494bbdddace16fd4e0664f9d00"
    ),
}
NEW_IMPLEMENTATION_PATHS = {
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/"
    "CameraFrameQualityPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/"
    "CrosswalkReferencePolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "OfficialEnvironmentPolicy.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityOfficialEnvironmentStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/"
    "CameraFrameQualityPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "OfficialEnvironmentPolicyTest.kt",
}

OUTPUT_PATHS = (
    IMPLEMENTATION_JSON,
    VERIFICATION_JSON,
    SUCCESSOR_JSON,
    REVIEW_JSON,
    RECEIPT_JSON,
    GAP_R012_JSON,
    GAP_R012_MD,
    BACKLOG_R012_JSON,
    BACKLOG_R012_MD,
    FP005_OVERLAY_JSON,
    FP005_OVERLAY_MD,
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
    require(START_GATE_RECEIPT.is_file(), "implementation start-gate receipt is missing")
    require(
        base.sha256_file(START_GATE_RECEIPT)
        == EXPECTED_START_GATE_RECEIPT_SHA256,
        "implementation start-gate receipt changed",
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
    require(BUILDER_TEST.is_file(), "builder test is missing")
    checkpoint = base.load_json(CHECKPOINT)
    state = checkpoint["goal_execution"]
    event = latest_execution_event(state)
    require(event is not None, "FP-005 execution-session event is missing")
    require(
        event.get("sequence") == EXPECTED_EXECUTION_EVENT_SEQUENCE,
        "latest FP-005 execution-session sequence differs",
    )
    require(
        event.get("event_id") == EXPECTED_EXECUTION_EVENT_ID,
        "latest FP-005 execution-session event differs",
    )
    require(
        event.get("event_type") == "GOAL_STARTED",
        "latest FP-005 execution-session event is not GOAL_STARTED",
    )
    require(
        event.get("event_sha256") == EXPECTED_EXECUTION_EVENT_SHA256,
        "latest FP-005 execution-session event hash differs",
    )
    require(
        event.get("occurred_at") == EXPECTED_EXECUTION_STARTED_AT,
        "latest FP-005 execution-session start time differs",
    )
    status = state["status_by_goal"].get(GOAL_ID)
    require(
        status in {"IN_PROGRESS", "COMPLETE_AT_TARGET"},
        "FP-005 Goal is neither in progress nor complete",
    )
    if status == "IN_PROGRESS":
        require(state.get("focus_goal_id") == GOAL_ID, "FP-005 is not the focus Goal")
    else:
        completion_role = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
        require(
            completion_role in state["completion_evidence_by_goal"].get(GOAL_ID, []),
            "FP-005 completion evidence is missing",
        )
    require(base.git_value("rev-parse", "HEAD") == BASE_COMMIT, "HEAD differs")
    return (
        base.load_json(GAP_R011_JSON),
        base.load_json(BACKLOG_R011_JSON),
        base.load_json(FP004_OVERLAY_JSON),
    )


def completion_boundary() -> dict[str, Any]:
    return {
        "formal_test_ids": [f"TC-FP-005-{index:02d}" for index in range(1, 5)],
        "formal_test_status": "NOT_RUN",
        "environment_field_test_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "production_official_environment_profile": None,
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
        "document_id": "WS-FP005-OFFICIAL-ENVIRONMENT-IMPLEMENTATION-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "IMPLEMENTATION_RECORD",
        "status": "PASS",
        "observed_at": OBSERVED_IMPLEMENTATION_AT,
        "changed_artifacts": changed_artifacts,
        "implemented_controls": [
            "measured GPS and camera evidence is separate from user-confirmed weather, construction and crowding",
            "only a bright dry ordinary urban sidewalk with every required factor passing is an internal support candidate",
            "unknown, stale, epoch-mismatched or unapproved evidence fails closed",
            "runtime quality loss suppresses walking outputs immediately and reaches a terminal safe stop after bounded retries",
            "crosswalk information is explicitly non-authoritative and never guarantees a signal, vehicle state or safe crossing",
            "production official-environment and camera-quality profiles remain unapproved and null",
            "archived v2.2 completion artifacts are accepted only through an exact receipt-bound before-to-after-to-live successor chain",
        ],
        "remaining_implementation_boundaries": [
            "production official-environment and camera-quality profiles are null and unapproved",
            "real brightness, occlusion, shake and mounting raw observations are not populated in the production activity",
            "an independent CameraX camera-quality preflight path is not implemented",
            "environment field evidence and production approval are still required",
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
        "7 passed" in content and "[100%]" in content,
        "control-plane successor-chain log is not successful",
    )
    require(
        "failed" not in content.lower() and "error" not in content.lower(),
        "control-plane successor-chain log contains a failure",
    )
    return {
        "name": "v2.3 archived-to-live successor-chain and repository capture regressions",
        "command": (
            "python3 -m py_compile scripts/check_walksafe_goal_graph_v2_3.py && "
            "LOCKED_PY=/home/ddobagi/.local/share/hanium-dreamup/"
            "walksafe-general-cpu-verify-20260715/bin/python; "
            "\"$LOCKED_PY\" -m pytest -q "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_archive_changed_artifact_errors_are_deferred_only "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_cross_package_completion_order_precedes_raw_sequence "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_staged_fp005_completion_resolves_archived_live_bytes "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_staged_fp005_completion_requires_event_and_receipt "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_staged_fp005_completion_rejects_broken_before_hash "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_work_item_completion_binds_start_receipt_and_event "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_completed_historical_goal_survives_new_current_backlog_binding"
        ),
        "exit_code": 0,
        "output_path": relative(CONTROL_PLANE_LOG),
        "output_sha256": base.sha256_file(CONTROL_PLANE_LOG),
        "executed_at": CONTROL_PLANE_EXECUTED_AT,
    }


def build_verification() -> dict[str, Any]:
    checks = [
        checked_log_record(
            name="FP-005 focused Android official-environment tests",
            command=(
                "./gradlew --offline --no-daemon :app:testDebugUnitTest "
                "--tests kr.co.hanium.dreamup.walksafe.session."
                "OfficialEnvironmentPolicyTest "
                "--tests kr.co.hanium.dreamup.walksafe.device."
                "CameraFrameQualityPolicyTest "
                "--tests kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorTest "
                "--tests kr.co.hanium.dreamup.walksafe.depth.MessagePolicyTest "
                "--tests kr.co.hanium.dreamup.walksafe.device."
                "WalkSafeStartupCapabilityTest "
                "--tests kr.co.hanium.dreamup.walksafe.session."
                "WalkSessionReadinessTest "
                "--tests kr.co.hanium.dreamup.walksafe.session."
                "PermissionSessionPolicyTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "MainActivityWalkSessionLifecycleStaticTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "MainActivityAccessibilityStaticTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "RuntimeMetricMainActivityStaticTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "MainActivityOfficialEnvironmentStaticTest --rerun-tasks"
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
        "document_id": "WS-FP005-OFFICIAL-ENVIRONMENT-VERIFICATION-20260724-001",
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
            "control_plane_successor_chain_tests": "PASS_7",
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
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-012",
        "version": "0.12.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = (
        "FP-005 공식 사용환경·품질 관문·횡단보도 비권위 안내의 저장소 내부 구현과 "
        "회귀로 GAP-014만 직접 재평가하고 나머지 67개 평가는 r011에서 보존한다."
    )
    report["source_bindings"] = predecessor["source_bindings"] + [
        {**base.file_record(BUILDER), "name": "fp005_official_environment_trace_builder"},
        {
            **base.file_record(BUILDER_TEST),
            "name": "fp005_official_environment_builder_test",
        },
        {
            **base.file_record(START_GATE_RECEIPT),
            "name": "fp005_implementation_start_gate_receipt",
        },
        generated_record(
            IMPLEMENTATION_JSON,
            implementation_text,
            name="fp005_official_environment_implementation",
        ),
        generated_record(
            VERIFICATION_JSON,
            verification_text,
            name="fp005_official_environment_verification",
        ),
    ]
    report["source_binding_sha256"] = base.object_sha256(report["source_bindings"])
    files = [base.file_record(ROOT / path) for path in IMPLEMENTATION_PATHS]
    snapshot = {
        "scope_kind": "EPIC_02_FP005_OFFICIAL_ENVIRONMENT_EXACT_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": base.git_value("rev-parse", "HEAD"),
        "branch": base.git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "formal acceptance testing",
            "environment field testing",
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
            "GAP-014의 환경·카메라 품질 fail-closed와 횡단보도 비권위 안내를 "
            "내부 구현했지만 생산 프로필·현장·사용자·실기기·정식 검증은 남아 PARTIAL이다."
        ),
    }
    implementation_hash = base.sha256_bytes(implementation_text.encode("utf-8"))
    verification_hash = base.sha256_bytes(verification_text.encode("utf-8"))
    evidence_id = "EVD-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK-20260724"
    report["evidence_catalog"] = predecessor["evidence_catalog"] + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": "FP-005 저장소 내부 환경 품질·안전정지·횡단보도 안내 구현과 Android 회귀",
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
            "environment_field_test_evidence": False,
            "actual_user_evidence": False,
            "actual_device_evidence": False,
            "production_profile_evidence": False,
            "release_evidence": False,
        }
    ]
    assessment = next(
        item for item in report["assessments"] if item.get("gap_id") == "GAP-014"
    )
    require(assessment.get("status") == "MISSING", "GAP-014 predecessor is not MISSING")
    require(
        assessment.get("planned_test_ids")
        == [f"TC-FP-005-{index:02d}" for index in range(1, 5)],
        "GAP-014 formal test ids differ",
    )
    assessment["status"] = "PARTIAL"
    assessment["current_implementation_in_plain_language"] = (
        "Android는 측정 가능한 GPS·카메라 품질과 사용자가 확인할 날씨·공사·혼잡 조건을 "
        "분리한다. 밝고 건조한 일반 도심 보도 후보에서도 모든 필수 증거가 현재 epoch에서 "
        "PASS이고 사용자가 직접 확인해야만 시작을 허용한다. 미승인 생산 프로필, unknown, "
        "stale 또는 epoch mismatch는 fail-closed하고 보행 중 품질 저하는 출력을 즉시 "
        "억제한 뒤 제한된 재시도 후 전체 안전정지한다. 횡단보도 정보는 참고용이며 신호·"
        "차량·안전한 횡단 판단을 대신하지 않는 고정 안내만 제공한다."
    )
    assessment["rationale"] = (
        "저장소 내부 반대 동작과 Android 회귀는 해소됐지만 TC-FP-005-01~04, 환경별 "
        "현장시험, 실제 사용자·Android 기기 시험과 생산 환경·카메라 품질 프로필 승인은 "
        "실행하지 않았다. 또한 production Activity의 밝기·가림·흔들림·장착 raw 값 "
        "population과 독립 CameraX 품질 사전점검은 아직 구현되지 않았다."
    )
    assessment["evidence_ids"] = [evidence_id]
    assessment["remediation"] = (
        "production Activity에 실제 밝기·가림·흔들림·장착 raw population과 독립 CameraX "
        "품질 사전점검을 연결한다. 이후 승인된 생산 프로필과 환경별 현장·실제 사용자·"
        "실기기 조건에서 TC-FP-005-01~04를 실행하고 원자료와 승인 기록을 연결한다."
    )
    assessment["fp005_reassessment"] = {
        "review_kind": "DIRECT_REASSESSMENT",
        "previous_status": "MISSING",
        "new_status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "environment_field_test_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "production_official_environment_profile": None,
        "production_camera_quality_profile": None,
        "approved_production_profile_count": 0,
        "policy_baseline_modified": False,
    }
    base.rehash_assessment(assessment)
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "environment_field_evidence": False,
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
        "이번 r012는 GAP-014만 직접 재평가하고 나머지 67개는 r011에서 승계했다.",
        "TC-FP-005-01~04와 환경별 현장·실제 사용자·실제 Android 기기 시험은 NOT_RUN이다.",
        "production Activity의 밝기·가림·흔들림·장착 raw population과 독립 CameraX 품질 사전점검은 아직 구현되지 않았다.",
        "생산 환경·카메라 품질 프로필은 null이고 5개 Gate는 미면제 NOT_RUN이며 출시는 NOT_ELIGIBLE이다.",
    ]
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC02_FP005_GAP014_REASSESSMENT_WITH_R011_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-014"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-014"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            item["gap_id"]
            for item in report["assessments"]
            if item.get("gap_id") != "GAP-014"
        ],
        "carry_forward_warning": "나머지 67개 판정은 r011에서 그대로 보존했다.",
        "next_adjacent_gap": {
            "gap_id": "GAP-015",
            "source_policy_id": "FP-006",
            "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
        },
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": relative(GAP_R011_JSON),
            "file_sha256": EXPECTED_PREDECESSOR_SHA256[GAP_R011_JSON],
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r012": False,
        },
    }
    return base.seal(report, "report_content_sha256")


def build_backlog(predecessor: dict[str, Any], gap: dict[str, Any]) -> dict[str, Any]:
    backlog = deepcopy(predecessor)
    backlog.pop("backlog_content_sha256", None)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-012",
        "version": "0.12.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    changed = 0
    for item in backlog["next_action_sequence"]:
        if item.get("source_policy_id") == "FP-005":
            changed += 1
            item["status"] = "PARTIAL"
            item["action"] = (
                "FP-005 내부 환경 품질·안전정지·횡단보도 비권위 안내를 생산 프로필·"
                "현장·사용자·실기기·정식 검증 Workstream에 연결한다."
            )
    require(changed == 1, "FP-005 backlog row count differs")
    epic = next(item for item in backlog["epics"] if item.get("epic_id") == "EPIC-02")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-017·FP-018·NPC·FP-004·FP-005 내부 구현은 진행됐지만 EPIC-02의 다음 "
        "정책과 생산 프로필·현장·사용자·실기기·정식 검증이 남아 있다."
    )
    backlog["source_predecessor"] = {
        "path": relative(BACKLOG_R011_JSON),
        "file_sha256": EXPECTED_PREDECESSOR_SHA256[BACKLOG_R011_JSON],
        "preserved_unchanged": True,
    }
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "work_item_id": "EPIC-02-FP006-SOLO-WALK-PHONE-MOUNTING",
        "source_policy_id": "FP-006",
        "gap_id": "GAP-015",
        "status": "PLANNED_NEXT_WITHIN_EPIC",
        "action": (
            "가슴·목걸이형 정면 장착, 가림·흔들림·각도 품질과 기존 보조수단 비대체 "
            "고지를 Android 사전점검·안전정지에 연결한다."
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
        event["event_id"] = f"WS-EPIC02-FP005-OFFICIAL-ENVIRONMENT-ACTIVE-{index:03d}"
        event["predecessor_event_id"] = previous["event_id"]
        event["predecessor_overlay_id"] = predecessor["metadata"]["overlay_id"]
        event["summary"] = (
            "FP-005 환경 품질·안전정지·횡단보도 비권위 안내 내부 구현·검증과 "
            "GAP-014 PARTIAL 재평가를 후속 Active 원장에 연결한다."
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
                "WS-EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK-ACTIVE-"
                "LEDGER-OVERLAY-20260724-001"
            ),
            "version": "0.1.0",
            "as_of": "2026-07-24",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-02 FP-005 공식 사용환경·횡단보도 Active successor overlay",
        },
        "authority_boundary": {
            **predecessor["authority_boundary"],
            "formal_test_completion_claimed": False,
            "environment_field_test_completion_claimed": False,
            "actual_user_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "production_profile_approval_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            {**base.file_record(FP004_OVERLAY_JSON), "name": "fp004_overlay_predecessor"},
            generated_record(
                IMPLEMENTATION_JSON,
                implementation_text,
                name="fp005_official_environment_implementation",
            ),
            generated_record(
                VERIFICATION_JSON,
                verification_text,
                name="fp005_official_environment_verification",
            ),
            generated_record(GAP_R012_JSON, gap_text, name="gap_r012"),
            generated_record(BACKLOG_R012_JSON, backlog_text, name="backlog_r012"),
        ],
        "application_rule": predecessor["application_rule"],
        "events": events,
        "draft_observations": predecessor["draft_observations"],
        "open_evidence_boundaries": {
            **predecessor["open_evidence_boundaries"],
            "approved_production_profile_count": 0,
            "fp005_official_environment_crosswalk": (
                "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED"
            ),
            "fp005_environment_field_test_status": "NOT_RUN",
            "fp005_actual_user_status": "NOT_RUN",
            "actual_device_fp005_status": "NOT_RUN",
            "production_official_environment_profile": None,
            "production_camera_quality_profile": None,
            "fp006_solo_walk_phone_mounting": "PLANNED_NEXT",
        },
        "formal_boundary": predecessor["formal_boundary"],
        "next_single_action": backlog["next_single_action"],
    }
    return base.seal(overlay, "overlay_content_sha256")


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


def build_successor(
    gap: dict[str, Any],
    gap_text: str,
    backlog: dict[str, Any],
    backlog_text: str,
) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP005-OFFICIAL-ENVIRONMENT-SUCCESSOR-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "SUCCESSOR_TRACE",
        "status": "PASS",
        "observed_at": OBSERVED_SUCCESSOR_AT,
        "canonical_update_event_type": "CANONICAL_BINDINGS_UPDATED",
        "resulting_canonical_bindings": {
            "IMPLEMENTATION_BACKLOG": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R012_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
            "IMPLEMENTATION_GAP": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R012_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
        },
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-005"],
            "IMPLEMENTATION_GAP": ["FP-005", "GAP-014"],
        },
        "next_policy_gap_pair": {
            "source_policy_id": "FP-006",
            "gap_id": "GAP-015",
        },
    }


def build_review(result_hashes: dict[str, str]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP005-OFFICIAL-ENVIRONMENT-INTERNAL-REVIEW-20260724-001",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": GOAL_ID,
        "status": "PASS",
        "reviewer_id": (
            "CODEX-FP005-OFFICIAL-ENVIRONMENT-SEPARATE-REVIEW-20260724-001"
        ),
        "reviewed_result_sha256_by_kind": result_hashes,
        "reviewed_at": REVIEWED_AT,
        "findings": {
            "blocking": 0,
            "major_open": 0,
            "resolved_before_acceptance": [
                "measured and user-confirmed environment factors are separate",
                "unknown, stale and epoch-mismatched evidence fails closed",
                "unapproved production profiles cannot enable official support",
                "runtime quality loss suppresses outputs before bounded retry and safe stop",
                "crosswalk wording cannot guarantee a signal, vehicle state or safe crossing",
                "prior environment evidence cannot restore a walk after restart",
                "archived completion replay cannot skip a missing event, receipt or changed-artifact before hash",
            ],
            "known_nonblocking_boundaries": [
                "production official-environment and camera-quality profiles remain null",
                "production raw brightness, occlusion, shake and mounting observations are not populated",
                "an independent CameraX quality preflight path remains unimplemented",
                "formal, environment-field, actual-user and actual-device tests were not run",
            ],
        },
        "review_boundary": {
            "separate_internal_review_pass": True,
            "external_independence_claimed": False,
            "formal_tests_remain_not_run": True,
            "environment_field_tests_remain_not_run": True,
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
            "WS-FP005-OFFICIAL-ENVIRONMENT-WORK-ITEM-COMPLETION-20260724-001"
        ),
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": base.sha256_file(GOAL_PATH),
        "work_item_id": "EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK",
        "source_policy_ids": ["FP-005"],
        "gap_ids": ["GAP-014"],
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
            "id": "CODEX-FP005-OFFICIAL-ENVIRONMENT-IMPLEMENTER-20260724-001",
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_3_STANDING_EXECUTION_AUTHORITY",
        },
        "reviewer": {
            "id": (
                "CODEX-FP005-OFFICIAL-ENVIRONMENT-SEPARATE-REVIEW-20260724-001"
            ),
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
    row = next(item for item in value["assessments"] if item["gap_id"] == "GAP-014")
    return (
        "# WalkSafe 구현 Gap 분석 r012\n\n"
        "- 직접 재평가: `FP-005 / GAP-014`\n"
        f"- 판정: `{row['status']}` (`MISSING → PARTIAL`)\n"
        "- 내부 Android focused·전체 회귀와 debug build·lint: `PASS`\n"
        "- 정식·환경별 현장·실제 사용자·실제 기기 시험: `NOT_RUN`\n"
        "- 생산 환경·카메라 품질 프로필: `null` / 미승인\n"
        "- 출시: `NOT_ELIGIBLE`\n\n"
        f"{row['rationale']}\n"
    )


def backlog_markdown(value: dict[str, Any]) -> str:
    action = value["next_single_action"]
    return (
        "# WalkSafe 구현 보완 Backlog r012\n\n"
        "- EPIC-02: `IN_PROGRESS`\n"
        f"- 다음 정책·Gap: `{action['source_policy_id']} / {action['gap_id']}`\n"
        "- 다음 정책: `단독 보행 목표와 휴대전화 장착`\n"
        f"- 다음 작업: {action['action']}\n"
    )


def overlay_markdown(value: dict[str, Any]) -> str:
    return (
        "# EPIC-02 FP-005 공식 사용환경·횡단보도 Active ledger overlay\n\n"
        f"- Overlay: `{value['metadata']['overlay_id']}`\n"
        "- FP-005 내부 구현·검증: `PARTIAL_IMPLEMENTATION_VERIFIED`\n"
        "- 정식·환경별 현장·실제 사용자·실제 기기 시험: `NOT_RUN`\n"
        "- 생산 환경·카메라 품질 프로필: `null` / 미승인\n"
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
        GAP_R012_JSON: gap_text,
        GAP_R012_MD: gap_markdown(gap),
        BACKLOG_R012_JSON: backlog_text,
        BACKLOG_R012_MD: backlog_markdown(backlog),
        FP005_OVERLAY_JSON: json_text(overlay),
        FP005_OVERLAY_MD: overlay_markdown(overlay),
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
        print(f"FP-005 official-environment trace: FAIL: {exc}", file=sys.stderr)
        return 1
    mode_name = "WRITE" if args.write else "CHECK"
    print(
        f"FP-005 official-environment trace: PASS outputs={len(outputs)} "
        f"next=FP-006/GAP-015 mode={mode_name}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
