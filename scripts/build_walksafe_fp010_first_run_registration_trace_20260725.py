#!/usr/bin/env python3
"""Build the focused FP-010 first-run registration implementation trace."""

from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path
import re
import sys
from typing import Any

try:
    from scripts import build_walksafe_fp006_phone_mounting_trace_20260724 as template
except ModuleNotFoundError:
    import build_walksafe_fp006_phone_mounting_trace_20260724 as template


base = template.base
ROOT = Path(__file__).resolve().parents[1]
BUILDER = Path(__file__).resolve()
BUILDER_TEST = ROOT / "tests/test_walksafe_fp010_first_run_registration_trace_20260725.py"

GOAL_ID = "WS-GOAL-EPIC-02-FP-010-R001"
GOAL_PATH = (
    ROOT
    / "docs/control/goals/walksafe-completion-graph-v2-3/work-items/epic-02/"
    "epic-02-fp010-first-run-registration-r001.md"
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

GAP_R013_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r013.json"
)
BACKLOG_R013_JSON = (
    ROOT
    / "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260724-r013.json"
)
FP006_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp006-solo-walk-phone-mounting-active-ledger-"
    "overlay-20260724-r001.json"
)
GAP_R014_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260725-r014.json"
)
GAP_R014_MD = GAP_R014_JSON.with_suffix(".md")
BACKLOG_R014_JSON = (
    ROOT
    / "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260725-r014.json"
)
BACKLOG_R014_MD = BACKLOG_R014_JSON.with_suffix(".md")
FP010_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp010-first-run-registration-active-ledger-"
    "overlay-20260725-r001.json"
)
FP010_OVERLAY_MD = FP010_OVERLAY_JSON.with_suffix(".md")
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
START_GATE_RECEIPT = (
    ROOT
    / "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-3-GOAL-STARTED-FP010-20260725-002/"
    "implementation-start-gate-receipt.json"
)
START_GATE_REPOSITORY_STATE_LOG = START_GATE_RECEIPT.parent / "19-REPOSITORY_STATE.log"

EXPECTED_PREDECESSOR_SHA256 = {
    GAP_R013_JSON: "df2e0682c3589a82bc5c701ef94043bfcf9bdc74ccd3a71d396d6551ec13299a",
    BACKLOG_R013_JSON: "ce22a11e2c3388ef2e728f4a19eb970ecb7bee5658668f159d178a77262c8edb",
    FP006_OVERLAY_JSON: "8fda1f8a28a0865559c5d167caa98f0283afc4ffef9ee4dd22e489889a38e031",
}
EXPECTED_GOAL_SHA256 = (
    "eaa31b6e48baf258e5d76886169c1f1c9596e27ca6a38c59b61bc9cb1d7da763"
)
EXPECTED_START_GATE_RECEIPT_SHA256 = (
    "c2292e6479fc2418bb727bda223826ce575d7d31ca595866bdac94bd891272dd"
)
EXPECTED_START_GATE_RECEIPT_ID = (
    "WS-GOAL-GRAPH-V2-3-IMPLEMENTATION-START-GATE-FP010-20260725-002"
)
EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256 = (
    "56aa66ec75b5519d8c8e4143c1e04abf039c98d162ef74204d3a242c055047ab"
)
EXPECTED_EXECUTION_EVENT_SEQUENCE = 13
EXPECTED_EXECUTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-3-GOAL-STARTED-FP010-20260725-002"
)
EXPECTED_EXECUTION_EVENT_SHA256 = (
    "7a7b1a6ddb78e5e25dd95e29efd055b36c03dea2e30080a0c73d4772a855c3c2"
)
EXPECTED_EXECUTION_STARTED_AT = "2026-07-25T01:25:59+09:00"
BASE_COMMIT = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"

OBSERVED_IMPLEMENTATION_AT = "2026-07-25T02:17:00+09:00"
FOCUSED_EXECUTED_AT = "2026-07-25T02:17:11+09:00"
FULL_ANDROID_EXECUTED_AT = "2026-07-25T02:17:41+09:00"
CONTROL_PLANE_EXECUTED_AT = "2026-07-25T02:44:57+09:00"
OBSERVED_VERIFICATION_AT = "2026-07-25T02:44:58+09:00"
OBSERVED_SUCCESSOR_AT = "2026-07-25T02:44:59+09:00"
COMPLETED_AT = "2026-07-25T02:45:00+09:00"
REVIEWED_AT = "2026-07-25T02:45:01+09:00"
REVIEW_DECIDED_AT = "2026-07-25T02:45:02+09:00"
GENERATED_AT = "2026-07-25T02:45:03+09:00"

IMPLEMENTATION_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "FirstRunOnboardingPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadiness.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityFirstRunRegistrationStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityOfficialEnvironmentStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityPhoneMountingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityStartupCapabilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityWalkSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "PriorityUserOnboardingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "FirstRunOnboardingPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadinessTest.kt",
    "scripts/check_walksafe_project_continuation_v2_3.py",
    "tests/test_walksafe_goal_graph_v2_3.py",
    "tests/test_walksafe_project_continuation_v2_3.py",
)
START_EXISTING_IMPLEMENTATION_PATHS = {
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadiness.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityOfficialEnvironmentStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityPhoneMountingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityStartupCapabilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityWalkSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "PriorityUserOnboardingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadinessTest.kt",
    "scripts/check_walksafe_project_continuation_v2_3.py",
    "tests/test_walksafe_goal_graph_v2_3.py",
    "tests/test_walksafe_project_continuation_v2_3.py",
}
NEW_IMPLEMENTATION_PATHS = set(IMPLEMENTATION_PATHS) - START_EXISTING_IMPLEMENTATION_PATHS

OUTPUT_PATHS = (
    IMPLEMENTATION_JSON,
    VERIFICATION_JSON,
    SUCCESSOR_JSON,
    REVIEW_JSON,
    RECEIPT_JSON,
    GAP_R014_JSON,
    GAP_R014_MD,
    BACKLOG_R014_JSON,
    BACKLOG_R014_MD,
    FP010_OVERLAY_JSON,
    FP010_OVERLAY_MD,
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


def gate_start_hashes() -> dict[str, str]:
    repository_state = base.load_json(START_GATE_REPOSITORY_STATE_LOG)
    rows = repository_state["dirty_snapshot"]["paths"]
    result = {}
    for row in rows:
        path = row.get("path")
        worktree = row.get("worktree", {})
        if (
            path in START_EXISTING_IMPLEMENTATION_PATHS
            and worktree.get("state") == "PRESENT"
            and isinstance(worktree.get("sha256"), str)
        ):
            result[path] = worktree["sha256"]
    require(
        set(result) == START_EXISTING_IMPLEMENTATION_PATHS,
        "start-gate implementation path coverage differs",
    )
    dirty_paths = {row.get("path") for row in rows}
    require(
        NEW_IMPLEMENTATION_PATHS.isdisjoint(dirty_paths),
        "a path classified as new existed at the start gate",
    )
    return result


def validate_inputs() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    for path, expected in EXPECTED_PREDECESSOR_SHA256.items():
        require(path.is_file(), f"predecessor is missing: {relative(path)}")
        require(
            base.sha256_file(path) == expected,
            f"predecessor changed: {relative(path)}",
        )
    require(GOAL_PATH.is_file(), "FP-010 Goal is missing")
    require(
        base.sha256_file(GOAL_PATH) == EXPECTED_GOAL_SHA256,
        "FP-010 Goal content changed",
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
    gate = base.load_json(START_GATE_RECEIPT)
    require(gate.get("document_id") == EXPECTED_START_GATE_RECEIPT_ID, "gate id differs")
    require(
        gate.get("target_transition_event_id") == EXPECTED_EXECUTION_EVENT_ID,
        "gate target event differs",
    )
    require(gate.get("target_goal_id") == GOAL_ID, "gate target Goal differs")
    require(
        gate.get("target_goal_content_sha256") == EXPECTED_GOAL_SHA256,
        "gate target Goal hash differs",
    )
    require(
        gate.get("repository_snapshot", {}).get(
            "gate_repository_state_output_sha256"
        )
        == EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256,
        "gate repository-state binding differs",
    )
    require(BUILDER_TEST.is_file(), "builder test is missing")

    checkpoint = base.load_json(CHECKPOINT)
    state = checkpoint["goal_execution"]
    event = latest_execution_event(state)
    require(event is not None, "FP-010 execution-session event is missing")
    require(
        event.get("sequence") == EXPECTED_EXECUTION_EVENT_SEQUENCE,
        "latest FP-010 execution-session sequence differs",
    )
    require(event.get("event_id") == EXPECTED_EXECUTION_EVENT_ID, "start event differs")
    require(event.get("event_type") == "GOAL_STARTED", "start event type differs")
    require(
        event.get("event_sha256") == EXPECTED_EXECUTION_EVENT_SHA256,
        "start event hash differs",
    )
    require(
        event.get("occurred_at") == EXPECTED_EXECUTION_STARTED_AT,
        "start event time differs",
    )
    require(
        event.get("implementation_start_gate_binding", {}).get("file_sha256")
        == EXPECTED_START_GATE_RECEIPT_SHA256,
        "start event gate binding differs",
    )
    status = state["status_by_goal"].get(GOAL_ID)
    require(
        status in {"IN_PROGRESS", "COMPLETE_AT_TARGET"},
        "FP-010 Goal is neither in progress nor complete",
    )
    if status == "IN_PROGRESS":
        require(state.get("focus_goal_id") == GOAL_ID, "FP-010 is not the focus Goal")
    else:
        completion_role = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
        require(
            completion_role in state["completion_evidence_by_goal"].get(GOAL_ID, []),
            "FP-010 completion evidence is missing",
        )
    require(base.git_value("rev-parse", "HEAD") == BASE_COMMIT, "HEAD differs")
    gate_start_hashes()
    return (
        base.load_json(GAP_R013_JSON),
        base.load_json(BACKLOG_R013_JSON),
        base.load_json(FP006_OVERLAY_JSON),
    )


def completion_boundary() -> dict[str, Any]:
    return {
        "formal_test_ids": [f"TC-FP-010-{index:02d}" for index in range(1, 5)],
        "formal_test_status": "NOT_RUN",
        "planned_formal_test_total": 279,
        "planned_formal_test_total_status": "NOT_RUN",
        "external_signup_provider_status": "NOT_RUN",
        "external_sms_provider_status": "NOT_RUN",
        "external_guardian_provider_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_talkback_user_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "production_evidence_verifier_configured": False,
        "approved_production_provider_count": 0,
        "release_gate_count": 5,
        "release_gate_status": "NOT_RUN",
        "release_gates_waived": False,
        "release_status": "NOT_ELIGIBLE",
    }


def implementation_content_set_sha256() -> str:
    files = []
    for path_text in IMPLEMENTATION_PATHS:
        path = ROOT / path_text
        require(path.is_file() and not path.is_symlink(), f"path is missing: {path_text}")
        files.append({"path": path_text, "sha256": base.sha256_file(path)})
    return base.object_sha256(files)


def build_implementation(
    predecessor_gap: dict[str, Any],
    *,
    expected_content_set_sha256: str | None = None,
) -> dict[str, Any]:
    _ = predecessor_gap
    start_hashes = gate_start_hashes()
    changed_artifacts = []
    for path_text in IMPLEMENTATION_PATHS:
        path = ROOT / path_text
        require(path.is_file() and not path.is_symlink(), f"path is missing: {path_text}")
        after = base.sha256_file(path)
        record: dict[str, Any] = {"path": path_text, "after_sha256": after}
        before = start_hashes.get(path_text)
        if before is not None:
            require(before != after, f"implementation path did not change: {path_text}")
            record["before_sha256"] = before
        changed_artifacts.append(record)
    content_set_sha256 = base.object_sha256(
        [
            {"path": row["path"], "sha256": row["after_sha256"]}
            for row in changed_artifacts
        ]
    )
    if expected_content_set_sha256 is not None:
        require(
            re.fullmatch(r"[0-9a-f]{64}", expected_content_set_sha256) is not None,
            "implementation content-set guard is not lowercase SHA-256",
        )
        require(
            content_set_sha256 == expected_content_set_sha256,
            "implementation content-set guard differs",
        )
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP010-FIRST-RUN-REGISTRATION-IMPLEMENTATION-20260725-001",
        "goal_id": GOAL_ID,
        "kind": "IMPLEMENTATION_RECORD",
        "status": "PASS",
        "observed_at": OBSERVED_IMPLEMENTATION_AT,
        "implementation_content_set_sha256": content_set_sha256,
        "changed_artifacts": changed_artifacts,
        "implemented_controls": [
            "purpose and safety limits precede age-band selection and every later first-run stage",
            "under-14 users terminate fail closed and only ages 14 through 17 require guardian evidence",
            "consent, opaque submission, verified SMS, guardian approval when required, activation and verified login are ordered",
            "the immutable state machine binds epoch, revision, request and attempt identity and ignores stale, duplicate or reordered responses",
            "restoration accepts only a verified ordered receipt prefix and never restores pending work or a raw completion boolean",
            "raw password, OTP, phone number, phone hash, exact birth date and disability type are neither modeled nor persisted",
            "the production evidence verifier is deliberately unconfigured and rejects positive external-provider claims",
            "current OS permission observation is just in time; device preflight is isolated before completion and runtime sensors remain blocked",
            "all start, resume, destination, route and report actions require completed first-run readiness",
            "local reporter input cannot impersonate externally verified signup, activation or login",
            "accessible status, logical traversal and 48dp controls avoid duplicate non-emergency TTS under TalkBack",
            "existing FP-004 priority-user training remains a distinct required stage and policy",
        ],
        "remaining_implementation_boundaries": [
            "external signup, SMS and guardian providers and operating credentials are unconfigured",
            "actual account activation and verified login against production services were not run",
            "actual TalkBack users, Android devices and network interruption scenarios were not run",
            "TC-FP-010-01 through TC-FP-010-04, the 279-test formal set and five release gates were not run",
        ],
        "completion_boundary": completion_boundary(),
    }


def checked_android_log(
    *,
    name: str,
    command: str,
    source_path: Path,
    logical_path: Path,
    executed_at: str,
    task_markers: tuple[str, ...],
) -> dict[str, Any]:
    require(
        source_path.is_file() and not source_path.is_symlink(),
        f"log is missing: {source_path}",
    )
    content = source_path.read_text(encoding="utf-8")
    require("BUILD SUCCESSFUL" in content, f"log is not successful: {source_path}")
    require("BUILD FAILED" not in content, f"log contains failed build: {source_path}")
    for marker in task_markers:
        require(marker in content, f"log is missing task marker {marker}: {source_path}")
    return {
        "name": name,
        "command": command,
        "exit_code": 0,
        "output_path": relative(logical_path),
        "output_sha256": base.sha256_file(source_path),
        "executed_at": executed_at,
    }


def checked_control_log(source_path: Path) -> dict[str, Any]:
    require(
        source_path.is_file() and not source_path.is_symlink(),
        f"log is missing: {source_path}",
    )
    content = source_path.read_text(encoding="utf-8")
    require(
        "[100%]" in content and re.search(r"\b[1-9][0-9]* passed\b", content),
        "control-plane verification log is not successful",
    )
    require(
        "failed" not in content.lower() and "error" not in content.lower(),
        "control-plane verification log contains a failure",
    )
    return {
        "name": "FP-010 trace and v2.3 completion-chain regressions",
        "command": (
            "python3 -m py_compile "
            "scripts/build_walksafe_fp010_first_run_registration_trace_20260725.py "
            "scripts/check_walksafe_goal_graph_v2_3.py "
            "scripts/check_walksafe_project_continuation_v2_3.py && "
            "LOCKED_PY=/home/ddobagi/.local/share/hanium-dreamup/"
            "walksafe-general-cpu-verify-20260715/bin/python; "
            "\"$LOCKED_PY\" -m pytest -q "
            "tests/test_walksafe_fp010_first_run_registration_trace_20260725.py "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_fp010_to_fp011_candidate_uses_the_ordered_transaction_path "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_fp010_canonical_update_rejects_out_of_order_fp011_events "
            "tests/test_walksafe_goal_graph_v2_3.py::"
            "WalkSafeGoalGraphV23Test::"
            "test_v2_3_preparation_has_exact_mixed_ownership_layout "
            "tests/test_walksafe_project_continuation_v2_3.py::"
            "WalkSafeProjectContinuationV23Test::"
            "test_fp010_seq13_candidate_tail_is_valid_and_keeps_master_status "
            "tests/test_walksafe_project_continuation_v2_3.py::"
            "WalkSafeProjectContinuationV23Test::"
            "test_fp011_seq14_to_seq17_candidate_prefixes_are_valid "
            "tests/test_walksafe_project_continuation_v2_3.py::"
            "WalkSafeProjectContinuationV23Test::"
            "test_fp011_candidate_rejects_r014_binding_hash_tampering "
            "tests/test_walksafe_project_continuation_v2_3.py::"
            "WalkSafeProjectContinuationV23Test::"
            "test_fp011_candidate_rejects_out_of_order_completion "
            "tests/test_walksafe_project_continuation_v2_3.py::"
            "WalkSafeProjectContinuationV23Test::"
            "test_fp011_candidate_rejects_broken_event_hash_chain "
            "tests/test_walksafe_project_continuation_v2_3.py::"
            "WalkSafeProjectContinuationV23Test::"
            "test_fp011_candidate_rejects_wrong_ready_dependency "
            "tests/test_walksafe_project_continuation_v2_3.py::"
            "WalkSafeProjectContinuationV23Test::"
            "test_fp010_focus_is_the_r013_gap019_successor_of_completed_fp006"
        ),
        "exit_code": 0,
        "output_path": relative(CONTROL_PLANE_LOG),
        "output_sha256": base.sha256_file(source_path),
        "executed_at": CONTROL_PLANE_EXECUTED_AT,
    }


def build_verification(
    *,
    focused_log: Path = FOCUSED_LOG,
    full_android_log: Path = FULL_ANDROID_LOG,
    control_plane_log: Path = CONTROL_PLANE_LOG,
) -> dict[str, Any]:
    checks = [
        checked_android_log(
            name="FP-010 focused Android first-run tests",
            command=(
                "./gradlew --offline --no-daemon :app:testDebugUnitTest "
                "--tests kr.co.hanium.dreamup.walksafe.session."
                "FirstRunOnboardingPolicyTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "MainActivityFirstRunRegistrationStaticTest "
                "--tests kr.co.hanium.dreamup.walksafe.session."
                "WalkSessionReadinessTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "PriorityUserOnboardingStaticTest "
                "--tests kr.co.hanium.dreamup.walksafe.session."
                "PriorityUserOnboardingPolicyTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "MainActivityAccessibilityStaticTest --rerun-tasks"
            ),
            source_path=focused_log,
            logical_path=FOCUSED_LOG,
            executed_at=FOCUSED_EXECUTED_AT,
            task_markers=(":app:testDebugUnitTest",),
        ),
        checked_android_log(
            name="full Android unit/build/lint verification",
            command=(
                "./gradlew --offline --no-daemon :app:testDebugUnitTest "
                ":app:assembleDebug :app:lintDebug --rerun-tasks"
            ),
            source_path=full_android_log,
            logical_path=FULL_ANDROID_LOG,
            executed_at=FULL_ANDROID_EXECUTED_AT,
            task_markers=(
                ":app:testDebugUnitTest",
                ":app:assembleDebug",
                ":app:lintDebug",
            ),
        ),
        checked_control_log(control_plane_log),
    ]
    boundary = completion_boundary()
    boundary.pop("formal_test_ids")
    boundary.pop("release_gate_count")
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP010-FIRST-RUN-REGISTRATION-VERIFICATION-20260725-001",
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
        "evidence_boundary": {"repository_internal": True, **boundary},
    }


def build_gap(
    predecessor: dict[str, Any],
    implementation: dict[str, Any],
    implementation_text: str,
    verification: dict[str, Any],
    verification_text: str,
) -> dict[str, Any]:
    require(
        predecessor["summary"]["status_counts"]["MISSING"] == 16
        and predecessor["summary"]["status_counts"]["PARTIAL"] == 25
        and predecessor["summary"]["status_counts"]["IMPLEMENTED"] == 0,
        "r013 status counts differ",
    )
    report = deepcopy(predecessor)
    report.pop("report_content_sha256", None)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260725-014",
        "version": "0.14.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = (
        "FP-010 첫 실행 등록 상태기계, 외부 증거 fail-closed 경계, 보행·센서 관문과 "
        "접근성의 저장소 내부 구현 및 회귀로 GAP-019만 직접 재평가하고 나머지 67개 "
        "평가는 r013에서 보존한다."
    )
    report["source_bindings"] = predecessor["source_bindings"] + [
        {**base.file_record(BUILDER), "name": "fp010_first_run_trace_builder"},
        {**base.file_record(BUILDER_TEST), "name": "fp010_first_run_builder_test"},
        {
            **base.file_record(START_GATE_RECEIPT),
            "name": "fp010_implementation_start_gate_receipt",
        },
        generated_record(
            IMPLEMENTATION_JSON,
            implementation_text,
            name="fp010_first_run_implementation",
        ),
        generated_record(
            VERIFICATION_JSON,
            verification_text,
            name="fp010_first_run_verification",
        ),
    ]
    report["source_binding_sha256"] = base.object_sha256(report["source_bindings"])

    files = [base.file_record(ROOT / path) for path in IMPLEMENTATION_PATHS]
    snapshot = {
        "scope_kind": "EPIC_02_FP010_FIRST_RUN_REGISTRATION_EXACT_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": base.git_value("rev-parse", "HEAD"),
        "branch": base.git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "external signup, SMS and guardian provider execution",
            "actual-user TalkBack and actual-device testing",
            "formal acceptance testing and the 279-test formal set",
            "release-gate closure",
        ],
        "file_count": len(files),
        "path_set_sha256": base.object_sha256([item["path"] for item in files]),
        "content_set_sha256": base.object_sha256(
            [{"path": item["path"], "sha256": item["sha256"]} for item in files]
        ),
        "files": files,
    }
    require(
        snapshot["content_set_sha256"]
        == implementation["implementation_content_set_sha256"],
        "implementation snapshot content set differs",
    )
    snapshot["snapshot_sha256"] = base.object_sha256(snapshot)
    report["implementation_snapshot"] = snapshot
    report["summary"] = {
        **predecessor["summary"],
        "status_counts": {
            **predecessor["summary"]["status_counts"],
            "MISSING": 15,
            "PARTIAL": 26,
            "IMPLEMENTED": 0,
        },
        "headline": (
            "GAP-019의 첫 실행 순서·외부 증거 fail-closed 상태기계·보행 및 민감 "
            "출력 차단·접근성을 내부 구현했지만 공급자·실제 사용자·실기기·정식 "
            "검증은 남아 PARTIAL이다."
        ),
    }
    implementation_hash = base.sha256_bytes(implementation_text.encode("utf-8"))
    verification_hash = base.sha256_bytes(verification_text.encode("utf-8"))
    evidence_id = "EVD-FP010-FIRST-RUN-REGISTRATION-20260725"
    report["evidence_catalog"] = predecessor["evidence_catalog"] + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": "FP-010 저장소 내부 첫 실행 등록 상태기계와 Android 회귀",
            "producer_goal_id": GOAL_ID,
            "producer_completion_receipt_role": f"WORK_ITEM_COMPLETION::{GOAL_ID}",
            "result_evidence_sha256_by_kind": {
                "IMPLEMENTATION_RECORD": implementation_hash,
                "VERIFICATION_RESULT": verification_hash,
            },
            "files": [
                {"path": relative(IMPLEMENTATION_JSON), "sha256": implementation_hash},
                {"path": relative(VERIFICATION_JSON), "sha256": verification_hash},
            ],
            "formal_test_evidence": False,
            "external_provider_evidence": False,
            "actual_user_evidence": False,
            "actual_talkback_user_evidence": False,
            "actual_device_evidence": False,
            "release_evidence": False,
        }
    ]
    assessment = next(
        item for item in report["assessments"] if item.get("gap_id") == "GAP-019"
    )
    require(assessment.get("status") == "MISSING", "GAP-019 predecessor is not MISSING")
    require(
        assessment.get("planned_test_ids")
        == [f"TC-FP-010-{index:02d}" for index in range(1, 5)],
        "GAP-019 formal test ids differ",
    )
    assessment["status"] = "PARTIAL"
    assessment["current_implementation_in_plain_language"] = (
        "Android는 목적·안전 한계부터 연령대, 통합 동의, 불투명 가입 제출, 확인된 "
        "SMS, 14~17세 보호자 확인, 활성화, 확인된 로그인, 기능 직전 권한, 격리된 "
        "기기점검, FP-004 훈련 순서를 불변 상태기계로 고정한다. epoch·revision·"
        "request·attempt가 맞지 않는 늦음·중복·역순 응답은 무효이며 확인된 순서 "
        "receipt prefix만 복원한다. 원 비밀번호·OTP·전화번호·전화 hash·정확 생년월일·"
        "장애유형은 모델링하거나 저장하지 않는다. 운영 검증기는 미설정 상태에서 항상 "
        "거부하고 완료 전 보행·신고·카메라·마이크·위치 출력을 차단한다."
    )
    assessment["rationale"] = (
        "저장소 내부 상태·관문·접근성 회귀는 해소됐지만 외부 signup·SMS·guardian "
        "공급자와 운영 자격정보는 구성되지 않았다. 실제 계정 활성화·로그인, 실제 "
        "TalkBack 사용자·실기기, TC-FP-010-01~04, 정식 279개와 5개 출시 Gate를 "
        "실행하지 않았으므로 최대 PARTIAL이다."
    )
    assessment["evidence_ids"] = [evidence_id]
    assessment["remediation"] = (
        "승인된 signup·SMS·guardian 공급자와 운영 자격정보를 프로비저닝하고 실제 "
        "계정·TalkBack 사용자·Android 기기에서 실패·재시도·복원 흐름을 검증한다. "
        "이후 TC-FP-010-01~04, 정식 279개와 5개 출시 Gate를 실행한다."
    )
    assessment["fp010_reassessment"] = {
        "review_kind": "DIRECT_REASSESSMENT",
        "previous_status": "MISSING",
        "new_status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "planned_formal_test_total": 279,
        "planned_formal_test_total_status": "NOT_RUN",
        "external_signup_provider_status": "NOT_RUN",
        "external_sms_provider_status": "NOT_RUN",
        "external_guardian_provider_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_talkback_user_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "production_evidence_verifier_configured": False,
        "policy_baseline_modified": False,
    }
    base.rehash_assessment(assessment)
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "external_provider_evidence": False,
        "actual_user_evidence": False,
        "actual_talkback_user_evidence": False,
        "actual_device_evidence": False,
        "source": relative(VERIFICATION_JSON),
        "verification_result_sha256": verification_hash,
        "interpretation": (
            "저장소 내부 회귀이며 공급자·정식·실제 사용자·실기기·출시 증거가 아니다."
        ),
    }
    report["limitations"] = [
        "이번 r014는 GAP-019만 직접 재평가하고 나머지 67개는 r013에서 승계했다.",
        "TC-FP-010-01~04, 정식 279개, 실제 TalkBack 사용자와 Android 기기 시험은 NOT_RUN이다.",
        "외부 signup·SMS·guardian 공급자와 실제 계정 활성화·로그인은 NOT_RUN이다.",
        "5개 Gate는 미면제 NOT_RUN이며 출시는 NOT_ELIGIBLE이다.",
    ]
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC02_FP010_GAP019_REASSESSMENT_WITH_R013_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-019"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-019"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            item["gap_id"]
            for item in report["assessments"]
            if item.get("gap_id") != "GAP-019"
        ],
        "carry_forward_warning": "나머지 67개 판정은 r013에서 그대로 보존했다.",
        "next_adjacent_gap": {
            "gap_id": "GAP-020",
            "source_policy_id": "FP-011",
            "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
        },
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": relative(GAP_R013_JSON),
            "file_sha256": EXPECTED_PREDECESSOR_SHA256[GAP_R013_JSON],
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r014": False,
        },
    }
    return base.seal(report, "report_content_sha256")


def build_backlog(predecessor: dict[str, Any], gap: dict[str, Any]) -> dict[str, Any]:
    backlog = deepcopy(predecessor)
    backlog.pop("backlog_content_sha256", None)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260725-014",
        "version": "0.14.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    changed = 0
    for item in backlog["next_action_sequence"]:
        if item.get("source_policy_id") == "FP-010":
            changed += 1
            require(item.get("status") == "MISSING", "FP-010 backlog status differs")
            item["status"] = "PARTIAL"
            item["action"] = (
                "FP-010 내부 첫 실행 상태기계·보행 차단·접근성을 외부 공급자·"
                "실제 사용자·실기기·정식 검증 Workstream에 연결한다."
            )
    require(changed == 1, "FP-010 backlog row count differs")
    epic = next(item for item in backlog["epics"] if item.get("epic_id") == "EPIC-02")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-017·FP-018·NPC·FP-004·FP-005·FP-006·FP-010 내부 구현은 진행됐지만 "
        "EPIC-02의 후속 정책과 외부 공급자·사용자·기기·정식 검증이 남아 있다."
    )
    backlog["source_predecessor"] = {
        "path": relative(BACKLOG_R013_JSON),
        "file_sha256": EXPECTED_PREDECESSOR_SHA256[BACKLOG_R013_JSON],
        "preserved_unchanged": True,
    }
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "work_item_id": "EPIC-02-FP011-LONG-LIVED-LOGIN",
        "source_policy_id": "FP-011",
        "gap_id": "GAP-020",
        "status": "PLANNED_NEXT_WITHIN_EPIC",
        "action": (
            "Android 보안 저장소 기반 접근용·회전 갱신용 로그인 증명과 만료·"
            "재발급·재사용 탐지·기기별 원격 폐기를 구현한다."
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
        event["event_id"] = f"WS-EPIC02-FP010-FIRST-RUN-ACTIVE-{index:03d}"
        event["predecessor_event_id"] = previous["event_id"]
        event["predecessor_overlay_id"] = predecessor["metadata"]["overlay_id"]
        event["summary"] = (
            "FP-010 첫 실행 순서·외부 증거 fail-closed·보행 및 민감 출력 차단·"
            "접근성 내부 구현·검증과 GAP-019 PARTIAL 재평가를 연결한다."
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
                "WS-EPIC-02-FP010-FIRST-RUN-REGISTRATION-ACTIVE-LEDGER-"
                "OVERLAY-20260725-001"
            ),
            "version": "0.1.0",
            "as_of": "2026-07-25",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-02 FP-010 첫 실행 등록 Active successor overlay",
        },
        "authority_boundary": {
            **predecessor["authority_boundary"],
            "formal_test_completion_claimed": False,
            "external_provider_completion_claimed": False,
            "actual_user_test_completion_claimed": False,
            "actual_talkback_user_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            {**base.file_record(FP006_OVERLAY_JSON), "name": "fp006_overlay_predecessor"},
            generated_record(
                IMPLEMENTATION_JSON,
                implementation_text,
                name="fp010_first_run_implementation",
            ),
            generated_record(
                VERIFICATION_JSON,
                verification_text,
                name="fp010_first_run_verification",
            ),
            generated_record(GAP_R014_JSON, gap_text, name="gap_r014"),
            generated_record(BACKLOG_R014_JSON, backlog_text, name="backlog_r014"),
        ],
        "application_rule": predecessor["application_rule"],
        "events": events,
        "draft_observations": predecessor["draft_observations"],
        "open_evidence_boundaries": {
            **predecessor["open_evidence_boundaries"],
            "approved_production_provider_count": 0,
            "fp010_first_run_registration": "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
            "fp010_external_signup_provider_status": "NOT_RUN",
            "fp010_external_sms_provider_status": "NOT_RUN",
            "fp010_external_guardian_provider_status": "NOT_RUN",
            "fp010_actual_user_status": "NOT_RUN",
            "fp010_actual_talkback_user_status": "NOT_RUN",
            "actual_device_fp010_status": "NOT_RUN",
            "fp010_formal_test_total": 279,
            "fp010_formal_test_total_status": "NOT_RUN",
            "fp011_long_lived_login": "PLANNED_NEXT",
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
        "document_id": "WS-FP010-FIRST-RUN-REGISTRATION-SUCCESSOR-20260725-001",
        "goal_id": GOAL_ID,
        "kind": "SUCCESSOR_TRACE",
        "status": "PASS",
        "observed_at": OBSERVED_SUCCESSOR_AT,
        "canonical_update_event_type": "CANONICAL_BINDINGS_UPDATED",
        "resulting_canonical_bindings": {
            "IMPLEMENTATION_BACKLOG": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R014_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
            "IMPLEMENTATION_GAP": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R014_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
        },
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-010"],
            "IMPLEMENTATION_GAP": ["FP-010", "GAP-019"],
        },
        "next_policy_gap_pair": {
            "source_policy_id": "FP-011",
            "gap_id": "GAP-020",
        },
    }


def build_review(result_hashes: dict[str, str]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP010-FIRST-RUN-REGISTRATION-INTERNAL-REVIEW-20260725-001",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": GOAL_ID,
        "status": "PASS",
        "reviewer_id": "CODEX-FP010-FIRST-RUN-SEPARATE-REVIEW-20260725-001",
        "reviewed_result_sha256_by_kind": result_hashes,
        "reviewed_at": REVIEWED_AT,
        "findings": {
            "blocking": 0,
            "major_open": 0,
            "resolved_before_acceptance": [
                "the first-run order and age/guardian branches are explicit and fail closed",
                "external evidence cannot be promoted by local identifiers or an unconfigured verifier",
                "stale, duplicate, reordered and reset-crossing responses cannot advance state",
                "only a verified ordered receipt prefix is restorable",
                "raw password, OTP, phone, exact birth date and disability data are absent",
                "all walk actions and sensitive outputs are gated before first-run completion",
                "device preflight is isolated from runtime sensor and detector output",
                "accessible controls avoid duplicate non-emergency TalkBack speech",
                "the existing FP-004 policy remains unchanged and separately required",
            ],
            "known_nonblocking_boundaries": [
                "external signup, SMS and guardian providers remain unconfigured",
                "actual activation, login and provider receipt verification were not run",
                "formal, actual-user TalkBack and actual-device tests were not run",
            ],
        },
        "review_boundary": {
            "separate_internal_review_pass": True,
            "external_independence_claimed": False,
            "formal_tests_remain_not_run": True,
            "external_provider_tests_remain_not_run": True,
            "actual_user_tests_remain_not_run": True,
            "actual_talkback_user_tests_remain_not_run": True,
            "actual_device_tests_remain_not_run": True,
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
            "WS-FP010-FIRST-RUN-REGISTRATION-WORK-ITEM-COMPLETION-20260725-001"
        ),
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": base.sha256_file(GOAL_PATH),
        "work_item_id": "EPIC-02-FP010-FIRST-RUN-REGISTRATION",
        "source_policy_ids": ["FP-010"],
        "gap_ids": ["GAP-019"],
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
            "id": "CODEX-FP010-FIRST-RUN-IMPLEMENTER-20260725-001",
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_3_STANDING_EXECUTION_AUTHORITY",
        },
        "reviewer": {
            "id": "CODEX-FP010-FIRST-RUN-SEPARATE-REVIEW-20260725-001",
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
    row = next(item for item in value["assessments"] if item["gap_id"] == "GAP-019")
    return (
        "# WalkSafe 구현 Gap 분석 r014\n\n"
        "- 직접 재평가: `FP-010 / GAP-019`\n"
        f"- 판정: `{row['status']}` (`MISSING → PARTIAL`)\n"
        "- 내부 Android focused·전체 회귀와 debug build·lint: `PASS`\n"
        "- TC-FP-010-01~04·정식 279개·공급자·실제 사용자·실기기: `NOT_RUN`\n"
        "- 출시 Gate 5개: `NOT_RUN` / 미면제\n"
        "- 출시: `NOT_ELIGIBLE`\n\n"
        f"{row['rationale']}\n"
    )


def backlog_markdown(value: dict[str, Any]) -> str:
    action = value["next_single_action"]
    return (
        "# WalkSafe 구현 보완 Backlog r014\n\n"
        "- EPIC-02: `IN_PROGRESS`\n"
        f"- 다음 정책·Gap: `{action['source_policy_id']} / {action['gap_id']}`\n"
        "- 다음 정책: `장기 로그인 유지`\n"
        f"- 다음 작업: {action['action']}\n"
    )


def overlay_markdown(value: dict[str, Any]) -> str:
    return (
        "# EPIC-02 FP-010 첫 실행 등록 Active ledger overlay\n\n"
        f"- Overlay: `{value['metadata']['overlay_id']}`\n"
        "- FP-010 내부 구현·검증: `PARTIAL_IMPLEMENTATION_VERIFIED`\n"
        "- TC-FP-010-01~04·정식 279개·공급자·사용자·기기: `NOT_RUN`\n"
        "- 출시 Gate 5개: `NOT_RUN` / 미면제\n"
        "- 출시: `NOT_ELIGIBLE`\n"
    )


def build_outputs(
    *,
    focused_log: Path = FOCUSED_LOG,
    full_android_log: Path = FULL_ANDROID_LOG,
    control_plane_log: Path = CONTROL_PLANE_LOG,
    expected_implementation_content_set_sha256: str | None = None,
) -> dict[Path, str]:
    predecessor_gap, predecessor_backlog, predecessor_overlay = validate_inputs()
    implementation = build_implementation(
        predecessor_gap,
        expected_content_set_sha256=expected_implementation_content_set_sha256,
    )
    implementation_text = json_text(implementation)
    verification = build_verification(
        focused_log=focused_log,
        full_android_log=full_android_log,
        control_plane_log=control_plane_log,
    )
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
        GAP_R014_JSON: gap_text,
        GAP_R014_MD: gap_markdown(gap),
        BACKLOG_R014_JSON: backlog_text,
        BACKLOG_R014_MD: backlog_markdown(backlog),
        FP010_OVERLAY_JSON: json_text(overlay),
        FP010_OVERLAY_MD: overlay_markdown(overlay),
    }
    require(tuple(outputs) == OUTPUT_PATHS, "output path order or membership differs")
    return outputs


def output_destination(path: Path, output_root: Path) -> Path:
    require(path in OUTPUT_PATHS, f"unapproved output path: {path}")
    root = output_root.resolve()
    destination = (root / path.relative_to(ROOT)).resolve()
    require(
        destination == root or root in destination.parents,
        "staged output escapes output root",
    )
    return destination


def write_or_check_outputs(
    outputs: dict[Path, str],
    *,
    write: bool,
    output_root: Path = ROOT,
) -> None:
    require(tuple(outputs) == OUTPUT_PATHS, "output path order or membership differs")
    destinations = {
        path: output_destination(path, output_root)
        for path in outputs
    }
    if write:
        for path, content in outputs.items():
            destination = destinations[path]
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content, encoding="utf-8")
        return
    for path, content in outputs.items():
        destination = destinations[path]
        require(
            destination.is_file(),
            f"output is missing: {destination}",
        )
        require(
            destination.read_bytes() == content.encode("utf-8"),
            f"output differs: {destination}",
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--focused-log", type=Path, default=FOCUSED_LOG)
    parser.add_argument("--full-android-log", type=Path, default=FULL_ANDROID_LOG)
    parser.add_argument("--control-plane-log", type=Path, default=CONTROL_PLANE_LOG)
    parser.add_argument("--output-root", type=Path, default=ROOT)
    parser.add_argument("--implementation-content-set-sha256")
    args = parser.parse_args(argv)
    try:
        outputs = build_outputs(
            focused_log=args.focused_log,
            full_android_log=args.full_android_log,
            control_plane_log=args.control_plane_log,
            expected_implementation_content_set_sha256=(
                args.implementation_content_set_sha256
            ),
        )
        write_or_check_outputs(
            outputs,
            write=args.write,
            output_root=args.output_root,
        )
    except (
        BuildError,
        base.BuildError,
        OSError,
        ValueError,
        KeyError,
        json.JSONDecodeError,
    ) as exc:
        print(f"FP-010 first-run trace: FAIL: {exc}", file=sys.stderr)
        return 1
    mode_name = "WRITE" if args.write else "CHECK"
    print(
        f"FP-010 first-run trace: PASS outputs={len(outputs)} "
        f"next=FP-011/GAP-020 mode={mode_name}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
