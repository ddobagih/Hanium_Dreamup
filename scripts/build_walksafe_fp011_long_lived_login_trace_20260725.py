#!/usr/bin/env python3
"""Build the focused FP-011 long-lived login implementation trace."""

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
BUILDER_TEST = ROOT / "tests/test_walksafe_fp011_long_lived_login_trace_20260725.py"

GOAL_ID = "WS-GOAL-EPIC-02-FP-011-R001"
GOAL_PATH = (
    ROOT
    / "docs/control/goals/walksafe-completion-graph-v2-3/work-items/epic-02/"
    "epic-02-fp011-long-lived-login-r001.md"
)
RESULT_DIR = ROOT / f"docs/control/execution/goal-results/{GOAL_ID}"
IMPLEMENTATION_JSON = RESULT_DIR / "implementation-record.json"
VERIFICATION_JSON = RESULT_DIR / "verification-result.json"
SUCCESSOR_JSON = RESULT_DIR / "successor-trace.json"
REVIEW_JSON = RESULT_DIR / "independent-review.json"
RECEIPT_JSON = RESULT_DIR / "completion-receipt.json"
FOCUSED_LOG = RESULT_DIR / "logs/focused-android-tests.log"
FULL_ANDROID_LOG = RESULT_DIR / "logs/full-android-verification.log"
GATEWAY_LOG = RESULT_DIR / "logs/android-gateway-verification.log"
CONTROL_PLANE_LOG = RESULT_DIR / "logs/control-plane-verification.log"

GAP_R014_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260725-r014.json"
)
BACKLOG_R014_JSON = (
    ROOT
    / "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260725-r014.json"
)
FP010_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp010-first-run-registration-active-ledger-"
    "overlay-20260725-r001.json"
)
GAP_R015_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260725-r015.json"
)
GAP_R015_MD = GAP_R015_JSON.with_suffix(".md")
BACKLOG_R015_JSON = (
    ROOT
    / "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260725-r015.json"
)
BACKLOG_R015_MD = BACKLOG_R015_JSON.with_suffix(".md")
FP011_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp011-long-lived-login-active-ledger-"
    "overlay-20260725-r001.json"
)
FP011_OVERLAY_MD = FP011_OVERLAY_JSON.with_suffix(".md")
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
START_GATE_RECEIPT = (
    ROOT
    / "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP011-20260725-001/"
    "implementation-start-gate-receipt.json"
)
START_GATE_REPOSITORY_STATE_LOG = START_GATE_RECEIPT.parent / "19-REPOSITORY_STATE.log"

EXPECTED_PREDECESSOR_SHA256 = {
    GAP_R014_JSON: "ce8d05bd68e70b9f8396e96a050a8b54db29df5e229e7621c02c43f879d33fb3",
    BACKLOG_R014_JSON: "f98ec0f5aa8a7515fb70939569c31175b61652835091046bebf11a87500fd333",
    FP010_OVERLAY_JSON: "1eb940f42e26ae49f43fd0b9f6a14c5f1a28c82d2be443233165319f3fe96866",
}
EXPECTED_GOAL_SHA256 = (
    "55dbce00b3092053a2766c078f1de83ef79e9193bfb372c7bcf30836c596e56f"
)
EXPECTED_START_GATE_RECEIPT_SHA256 = (
    "ef8f0918f4a415fd3b4d2f346cb0a7828eaddf459bba32fe8da25b07762733ed"
)
EXPECTED_START_GATE_RECEIPT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP011-20260725-001"
)
EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256 = (
    "c2183619136ad83cccb20621d293bc4fcaa844dd38fdb025ffde8ea71c8e1934"
)
EXPECTED_EXECUTION_EVENT_SEQUENCE = 3
EXPECTED_EXECUTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP011-20260725-001"
)
EXPECTED_EXECUTION_EVENT_SHA256 = (
    "a97c469beb3424c02214d2b8aa124bd61e33bc2ef406734a5cab042e807987b2"
)
EXPECTED_EXECUTION_STARTED_AT = "2026-07-25T09:56:04+09:00"
BASE_COMMIT = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"

OBSERVED_IMPLEMENTATION_AT = "2026-07-25T12:25:00+09:00"
FOCUSED_EXECUTED_AT = "2026-07-25T12:25:30+09:00"
FULL_ANDROID_EXECUTED_AT = "2026-07-25T12:30:00+09:00"
GATEWAY_EXECUTED_AT = "2026-07-25T12:30:01+09:00"
CONTROL_PLANE_EXECUTED_AT = "2026-07-25T12:30:38+09:00"
OBSERVED_VERIFICATION_AT = "2026-07-25T12:31:00+09:00"
OBSERVED_SUCCESSOR_AT = "2026-07-25T12:31:01+09:00"
COMPLETED_AT = "2026-07-25T13:16:40+09:00"
REVIEWED_AT = "2026-07-25T13:16:41+09:00"
REVIEW_DECIDED_AT = "2026-07-25T13:16:42+09:00"
GENERATED_AT = "2026-07-25T13:16:43+09:00"

IMPLEMENTATION_PATHS = (
    "apps/android/app/build.gradle.kts",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
    "AndroidGatewaySessionStore.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
    "GatewayFieldSession.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
    "GatewayPersistedLoginState.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
    "GatewaySessionProcessCoordinator.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "PermissionSessionPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadiness.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityLongLivedLoginStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityFirstRunRegistrationStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "PermissionSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "PriorityUserOnboardingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
    "AndroidGatewaySessionStoreStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
    "GatewayFieldSessionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
    "GatewayPersistedLoginStateTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
    "GatewaySessionProcessCoordinatorTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
    "MainActivityReportUploadStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "PermissionSessionPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadinessTest.kt",
    "apps/android-gateway/README.md",
    "apps/android-gateway/openapi.json",
    "apps/android-gateway/src/auth.ts",
    "apps/android-gateway/src/config.ts",
    "apps/android-gateway/src/field-long-session.ts",
    "apps/android-gateway/src/routes.ts",
    "apps/android-gateway/test/field-long-session.test.ts",
    "apps/android-gateway/test/gateway-contract.test.ts",
    "apps/android-gateway/test/node-adapter.test.ts",
    "deploy/config/walksafe-android-gateway.env.example",
    "scripts/check_walksafe_goal_graph_v2_4.py",
    "tests/test_walksafe_goal_graph_v2_4.py",
)

OUTPUT_PATHS = (
    IMPLEMENTATION_JSON,
    VERIFICATION_JSON,
    SUCCESSOR_JSON,
    REVIEW_JSON,
    RECEIPT_JSON,
    GAP_R015_JSON,
    GAP_R015_MD,
    BACKLOG_R015_JSON,
    BACKLOG_R015_MD,
    FP011_OVERLAY_JSON,
    FP011_OVERLAY_MD,
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
    result: dict[str, str] = {}
    for row in rows:
        path = row.get("path")
        worktree = row.get("worktree", {})
        if (
            path in IMPLEMENTATION_PATHS
            and worktree.get("state") == "PRESENT"
            and isinstance(worktree.get("sha256"), str)
        ):
            result[path] = worktree["sha256"]
    return result


def validate_inputs() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    for path, expected in EXPECTED_PREDECESSOR_SHA256.items():
        require(path.is_file(), f"predecessor is missing: {relative(path)}")
        require(
            base.sha256_file(path) == expected,
            f"predecessor changed: {relative(path)}",
        )
    require(GOAL_PATH.is_file(), "FP-011 Goal is missing")
    require(
        base.sha256_file(GOAL_PATH) == EXPECTED_GOAL_SHA256,
        "FP-011 Goal content changed",
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
    start_events = [
        item
        for item in state["transition_history"]
        if item.get("event_type") == "GOAL_STARTED"
        and item.get("subject_goal_id") == GOAL_ID
    ]
    require(len(start_events) == 1, "FP-011 start event count differs")
    start_event = start_events[0]
    require(
        start_event.get("sequence") == EXPECTED_EXECUTION_EVENT_SEQUENCE,
        "FP-011 start event sequence differs",
    )
    require(
        start_event.get("event_id") == EXPECTED_EXECUTION_EVENT_ID,
        "FP-011 start event id differs",
    )
    require(
        start_event.get("event_sha256") == EXPECTED_EXECUTION_EVENT_SHA256,
        "FP-011 start event hash differs",
    )
    require(
        start_event.get("occurred_at") == EXPECTED_EXECUTION_STARTED_AT,
        "FP-011 start event time differs",
    )
    require(
        start_event.get("implementation_start_gate_binding", {}).get("file_sha256")
        == EXPECTED_START_GATE_RECEIPT_SHA256,
        "start event gate binding differs",
    )
    event = latest_execution_event(state)
    require(event is not None, "FP-011 execution-session event is missing")
    require(
        event.get("event_type") in {"GOAL_STARTED", "WORK_SESSION_RESUMED"},
        "latest FP-011 execution-session type differs",
    )
    require(
        isinstance(event.get("sequence"), int)
        and event["sequence"] >= EXPECTED_EXECUTION_EVENT_SEQUENCE,
        "latest FP-011 execution-session sequence differs",
    )
    require(
        isinstance(event.get("event_id"), str)
        and isinstance(event.get("event_sha256"), str)
        and isinstance(event.get("occurred_at"), str),
        "latest FP-011 execution-session binding is malformed",
    )
    status = state["status_by_goal"].get(GOAL_ID)
    require(
        status in {"IN_PROGRESS", "COMPLETE_AT_TARGET"},
        "FP-011 Goal is neither in progress nor complete",
    )
    if status == "IN_PROGRESS":
        require(state.get("focus_goal_id") == GOAL_ID, "FP-011 is not the focus Goal")
    else:
        completion_role = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
        require(
            completion_role in state["completion_evidence_by_goal"].get(GOAL_ID, []),
            "FP-011 completion evidence is missing",
        )
    require(base.git_value("rev-parse", "HEAD") == BASE_COMMIT, "HEAD differs")
    gate_start_hashes()
    return (
        base.load_json(GAP_R014_JSON),
        base.load_json(BACKLOG_R014_JSON),
        base.load_json(FP010_OVERLAY_JSON),
    )


def completion_boundary() -> dict[str, Any]:
    return {
        "formal_test_ids": [f"TC-FP-011-{index:02d}" for index in range(1, 6)],
        "formal_test_status": "NOT_RUN",
        "planned_formal_test_total": 279,
        "planned_formal_test_total_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "actual_network_status": "NOT_RUN",
        "production_credentials_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "remote_device_revoke_drill_status": "NOT_RUN",
        "account_lock_drill_status": "NOT_RUN",
        "security_incident_drill_status": "NOT_RUN",
        "production_long_lived_login_enabled": False,
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
            record["change_kind"] = "MODIFIED"
        else:
            record["change_kind"] = "ADDED"
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
        "document_id": "WS-FP011-LONG-LIVED-LOGIN-IMPLEMENTATION-20260725-001",
        "goal_id": GOAL_ID,
        "kind": "IMPLEMENTATION_RECORD",
        "status": "PASS",
        "observed_at": OBSERVED_IMPLEMENTATION_AT,
        "implementation_content_set_sha256": content_set_sha256,
        "changed_artifacts": changed_artifacts,
        "implemented_controls": [
            "access and one-time refresh credentials have separate purpose, lifetime and storage boundaries",
            "the server stores token digests and device-family lineage rather than raw refresh credentials",
            "refresh rotation is atomic and reuse revokes only the affected device family",
            "idle and absolute expiry both fail closed and require explicit configured durations",
            "selective device revocation is distinct from account-lock and security-incident all-family revocation",
            "Android persists only the long-lived bundle through Android Keystore backed authenticated encryption",
            "restored state is unverified until a server refresh succeeds and the replacement is durably saved",
            "stale, duplicate, late, out-of-order and partial-save results cannot promote authentication",
            "legacy short sessions remain process-only and long-lived mode is disabled by default",
            "readiness capture rejects expired sessions and observed logout or downgrade invalidates prepared walk tokens",
            "logout clears login and walk-dependent outputs without revoking camera, location or microphone OS permissions",
        ],
        "remaining_implementation_boundaries": [
            "production credentials, deployment and long-lived-login enablement remain unconfigured",
            "approved idle, absolute and access duration values remain outside this internal implementation claim",
            "automatic expiry-time monitoring and protected-API access-expiry renewal integration remain open",
            "dedicated sensitive-account-change reauthentication enforcement remains open",
            "actual users, Android devices and network interruption scenarios were not run",
            "remote-device revoke, account-lock and security-incident drills were not run",
            "TC-FP-011-01 through TC-FP-011-05, the 279-test formal set and five release gates were not run",
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
        "name": "FP-011 deterministic trace regression",
        "command": (
            "python3 -m py_compile "
            "scripts/build_walksafe_fp011_long_lived_login_trace_20260725.py && "
            "LOCKED_PY=/home/ddobagi/.local/share/hanium-dreamup/"
            "walksafe-general-cpu-verify-20260715/bin/python; "
            "\"$LOCKED_PY\" -m pytest -q "
            "tests/test_walksafe_fp011_long_lived_login_trace_20260725.py"
        ),
        "exit_code": 0,
        "output_path": relative(CONTROL_PLANE_LOG),
        "output_sha256": base.sha256_file(source_path),
        "executed_at": CONTROL_PLANE_EXECUTED_AT,
    }


def checked_gateway_log(source_path: Path) -> dict[str, Any]:
    require(
        source_path.is_file() and not source_path.is_symlink(),
        f"log is missing: {source_path}",
    )
    content = source_path.read_text(encoding="utf-8")
    require(
        re.search(r"# pass [1-9][0-9]*\b", content) is not None
        and "# fail 0" in content,
        "Android Gateway verification log is not successful",
    )
    require(
        "not ok " not in content.lower() and "npm error" not in content.lower(),
        "Android Gateway verification log contains a failure",
    )
    return {
        "name": "Android Gateway long-lived-session build, typecheck and tests",
        "command": "cd apps/android-gateway && npm run typecheck && npm test",
        "exit_code": 0,
        "output_path": relative(GATEWAY_LOG),
        "output_sha256": base.sha256_file(source_path),
        "executed_at": GATEWAY_EXECUTED_AT,
    }


def build_verification(
    *,
    focused_log: Path = FOCUSED_LOG,
    full_android_log: Path = FULL_ANDROID_LOG,
    gateway_log: Path = GATEWAY_LOG,
    control_plane_log: Path = CONTROL_PLANE_LOG,
) -> dict[str, Any]:
    checks = [
        checked_android_log(
            name="FP-011 focused Android long-lived tests",
            command=(
                "./gradlew :app:testDebugUnitTest "
                "--tests '*GatewayFieldSessionTest' "
                "--tests '*AndroidGatewaySessionStoreStaticTest' "
                "--tests '*PermissionSessionPolicyTest' "
                "--tests '*WalkSessionReadinessTest' "
                "--tests '*MainActivityLongLivedLoginStaticTest' "
                "--offline --no-daemon --max-workers=4 "
                "-Pkotlin.compiler.execution.strategy=in-process --rerun-tasks"
            ),
            source_path=focused_log,
            logical_path=FOCUSED_LOG,
            executed_at=FOCUSED_EXECUTED_AT,
            task_markers=(":app:testDebugUnitTest",),
        ),
        checked_android_log(
            name="full Android unit/build/lint verification",
            command=(
                "./gradlew -Dorg.gradle.jvmargs='-Xmx1536m "
                "-XX:MaxMetaspaceSize=1024m' --offline --no-daemon "
                ":app:testDebugUnitTest "
                ":app:assembleDebug :app:lintDebug --max-workers=4 "
                "-Pkotlin.compiler.execution.strategy=in-process --rerun-tasks"
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
        checked_gateway_log(gateway_log),
        checked_control_log(control_plane_log),
    ]
    boundary = completion_boundary()
    boundary.pop("formal_test_ids")
    boundary.pop("release_gate_count")
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP011-LONG-LIVED-LOGIN-VERIFICATION-20260725-001",
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
            "gateway_build_typecheck_tests": "PASS",
            "trace_builder_tests": "PASS",
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
        predecessor["summary"]["status_counts"]["MISSING"] == 15
        and predecessor["summary"]["status_counts"]["PARTIAL"] == 26
        and predecessor["summary"]["status_counts"]["IMPLEMENTED"] == 0,
        "r014 status counts differ",
    )
    report = deepcopy(predecessor)
    report.pop("report_content_sha256", None)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260725-015",
        "version": "0.15.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = (
        "FP-011의 접근·회전 갱신 증명, 기기 family, 만료·폐기, Android 보호 저장소와 "
        "재시작 fail-closed 경계를 저장소 내부에서 구현·검증해 GAP-020만 직접 "
        "재평가하고 나머지 67개 평가는 r014에서 보존한다."
    )
    report["source_bindings"] = predecessor["source_bindings"] + [
        {**base.file_record(BUILDER), "name": "fp011_long_lived_login_trace_builder"},
        {**base.file_record(BUILDER_TEST), "name": "fp011_long_lived_login_builder_test"},
        {
            **base.file_record(START_GATE_RECEIPT),
            "name": "fp011_implementation_start_gate_receipt",
        },
        generated_record(
            IMPLEMENTATION_JSON,
            implementation_text,
            name="fp011_long_lived_login_implementation",
        ),
        generated_record(
            VERIFICATION_JSON,
            verification_text,
            name="fp011_long_lived_login_verification",
        ),
    ]
    report["source_binding_sha256"] = base.object_sha256(report["source_bindings"])

    files = [base.file_record(ROOT / path) for path in IMPLEMENTATION_PATHS]
    snapshot = {
        "scope_kind": "EPIC_02_FP011_LONG_LIVED_LOGIN_EXACT_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": base.git_value("rev-parse", "HEAD"),
        "branch": base.git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "production credentials and production deployment",
            "actual-user, actual-device and long-running network testing",
            "remote-device revoke, account-lock and security-incident drills",
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
            "MISSING": 14,
            "PARTIAL": 27,
            "IMPLEMENTED": 0,
        },
        "headline": (
            "GAP-020의 접근·회전 갱신 증명, 기기별 폐기, 이중 만료, Android 보호 "
            "저장소와 재시작 fail-closed 경계를 내부 구현했지만 운영 자격정보·배포·"
            "실제 사용자·실기기·보안 훈련·정식 검증은 남아 PARTIAL이다."
        ),
    }
    implementation_hash = base.sha256_bytes(implementation_text.encode("utf-8"))
    verification_hash = base.sha256_bytes(verification_text.encode("utf-8"))
    evidence_id = "EVD-FP011-LONG-LIVED-LOGIN-20260725"
    report["evidence_catalog"] = predecessor["evidence_catalog"] + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": "FP-011 저장소 내부 장기 로그인 상태기계와 Android·Gateway 회귀",
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
            "actual_user_evidence": False,
            "actual_device_evidence": False,
            "actual_network_evidence": False,
            "production_deployment_evidence": False,
            "security_drill_evidence": False,
            "release_evidence": False,
        }
    ]
    assessment = next(
        item for item in report["assessments"] if item.get("gap_id") == "GAP-020"
    )
    require(assessment.get("status") == "MISSING", "GAP-020 predecessor is not MISSING")
    require(
        assessment.get("planned_test_ids")
        == [f"TC-FP-011-{index:02d}" for index in range(1, 6)],
        "GAP-020 formal test ids differ",
    )
    assessment["status"] = "PARTIAL"
    assessment["current_implementation_in_plain_language"] = (
        "Android Gateway는 짧은 access와 1회용 refresh를 기기 family·rotation에 "
        "결속하고 원문 대신 digest와 사용 tombstone을 보존한다. refresh 재사용은 "
        "해당 family만 폐기하며 기기별 선택 폐기와 계정 잠금·보안사고 전체 폐기를 "
        "구분한다. idle·absolute 만료를 함께 적용하고 명시적 유효 설정이 없으면 "
        "장기 모드는 꺼진다. Android는 Keystore 기반 인증 암호화 저장소에만 장기 "
        "bundle을 두며 복원 직후에는 미검증이다. 서버 회전 성공과 원자 저장 뒤에만 "
        "인증·보행을 연다. readiness capture는 만료 세션을 거부하고 관측된 logout·"
        "downgrade는 준비 token과 활성 보행을 fail closed한다."
    )
    assessment["rationale"] = (
        "저장소 내부 Android·Gateway 상태기계와 보안·보행 관문 회귀는 구현했지만 "
        "장기 모드는 기본 OFF이고 운영 자격정보·승인 배포·실제 사용자·실기기·실제 "
        "네트워크·원격 폐기/잠금/사고 훈련을 수행하지 않았다. 자동 만료 감시, "
        "보호 API access-expiry 갱신 통합과 민감 계정 변경 재인증도 남았다. "
        "TC-FP-011-01~05, 정식 279개와 5개 출시 Gate도 실행하지 않았으므로 "
        "최대 PARTIAL이다."
    )
    assessment["evidence_ids"] = [evidence_id]
    assessment["remediation"] = (
        "승인된 access·idle·absolute 기간과 운영 자격정보·배포 후보를 별도로 결속하고 "
        "자동 만료 감시·보호 API 갱신과 민감 계정 변경 재인증을 완성한다. 실제 사용자·"
        "Android 기기·네트워크에서 재시작·회전·부분응답을 검증한다. "
        "분실 기기 선택 폐기, 계정 잠금과 보안사고 훈련 뒤 TC-FP-011-01~05, "
        "정식 279개와 5개 출시 Gate를 실행한다."
    )
    assessment["fp011_reassessment"] = {
        "review_kind": "DIRECT_REASSESSMENT",
        "previous_status": "MISSING",
        "new_status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "planned_formal_test_total": 279,
        "planned_formal_test_total_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "actual_network_status": "NOT_RUN",
        "production_credentials_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "remote_device_revoke_drill_status": "NOT_RUN",
        "account_lock_drill_status": "NOT_RUN",
        "security_incident_drill_status": "NOT_RUN",
        "production_long_lived_login_enabled": False,
        "policy_baseline_modified": False,
    }
    base.rehash_assessment(assessment)
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "actual_user_evidence": False,
        "actual_device_evidence": False,
        "actual_network_evidence": False,
        "production_deployment_evidence": False,
        "security_drill_evidence": False,
        "source": relative(VERIFICATION_JSON),
        "verification_result_sha256": verification_hash,
        "interpretation": (
            "저장소 내부 회귀이며 운영 배포·정식·실제 사용자·실기기·네트워크·"
            "보안 훈련·출시 증거가 아니다."
        ),
    }
    report["limitations"] = [
        "이번 r015는 GAP-020만 직접 재평가하고 나머지 67개는 r014에서 승계했다.",
        "자동 만료 감시, 보호 API access-expiry 갱신 통합과 민감 계정 변경 재인증은 열려 있다.",
        "TC-FP-011-01~05, 정식 279개, 실제 사용자·Android 기기·네트워크 시험은 NOT_RUN이다.",
        "운영 자격정보·배포·장기 로그인 enablement와 원격 폐기·잠금·사고 훈련은 NOT_RUN이다.",
        "5개 Gate는 미면제 NOT_RUN이며 출시는 NOT_ELIGIBLE이다.",
    ]
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC02_FP011_GAP020_REASSESSMENT_WITH_R014_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-020"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-020"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            item["gap_id"]
            for item in report["assessments"]
            if item.get("gap_id") != "GAP-020"
        ],
        "carry_forward_warning": "나머지 67개 판정은 r014에서 그대로 보존했다.",
        "next_adjacent_gap": {
            "gap_id": "GAP-022",
            "source_policy_id": "FP-013",
            "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
        },
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": relative(GAP_R014_JSON),
            "file_sha256": EXPECTED_PREDECESSOR_SHA256[GAP_R014_JSON],
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r015": False,
        },
    }
    return base.seal(report, "report_content_sha256")


def build_backlog(predecessor: dict[str, Any], gap: dict[str, Any]) -> dict[str, Any]:
    backlog = deepcopy(predecessor)
    backlog.pop("backlog_content_sha256", None)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260725-015",
        "version": "0.15.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    changed = 0
    for item in backlog["next_action_sequence"]:
        if item.get("source_policy_id") == "FP-011":
            changed += 1
            require(item.get("status") == "MISSING", "FP-011 backlog status differs")
            item["status"] = "PARTIAL"
            item["action"] = (
                "FP-011 내부 접근·회전 갱신 증명, 기기별 폐기, 이중 만료와 Android "
                "보호 저장소를 운영 배포·실제 사용자·실기기·보안 훈련·정식 검증 "
                "Workstream에 연결한다."
            )
    require(changed == 1, "FP-011 backlog row count differs")
    epic = next(item for item in backlog["epics"] if item.get("epic_id") == "EPIC-02")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-017·FP-018·NPC·FP-004·FP-005·FP-006·FP-010·FP-011 내부 구현은 "
        "진행됐지만 EPIC-02의 후속 정책과 운영 배포·사용자·기기·정식 검증이 남아 있다."
    )
    backlog["source_predecessor"] = {
        "path": relative(BACKLOG_R014_JSON),
        "file_sha256": EXPECTED_PREDECESSOR_SHA256[BACKLOG_R014_JSON],
        "preserved_unchanged": True,
    }
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "work_item_id": "EPIC-02-FP013-FIRST-RUN-INTEGRATED-CONSENT",
        "source_policy_id": "FP-013",
        "gap_id": "GAP-022",
        "status": "PLANNED_NEXT_WITHIN_EPIC",
        "action": (
            "원본수집·자동신고·이동통신망·학습재사용을 각각 버전 관리하는 통합 "
            "동의 화면과 서버 관리대장을 만들고 동의하지 않은 항목은 수집·전송하지 않는다."
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
        event["event_id"] = f"WS-EPIC02-FP011-LONG-LIVED-LOGIN-ACTIVE-{index:03d}"
        event["predecessor_event_id"] = previous["event_id"]
        event["predecessor_overlay_id"] = predecessor["metadata"]["overlay_id"]
        event["summary"] = (
            "FP-011 접근·회전 갱신 증명, 기기별 폐기, 이중 만료, Android 보호 "
            "저장소와 재시작 fail-closed 내부 구현·검증 및 GAP-020 PARTIAL "
            "재평가를 연결한다."
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
                "WS-EPIC-02-FP011-LONG-LIVED-LOGIN-ACTIVE-LEDGER-"
                "OVERLAY-20260725-001"
            ),
            "version": "0.1.0",
            "as_of": "2026-07-25",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-02 FP-011 장기 로그인 Active successor overlay",
        },
        "authority_boundary": {
            **predecessor["authority_boundary"],
            "formal_test_completion_claimed": False,
            "external_provider_completion_claimed": False,
            "actual_user_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "actual_network_test_completion_claimed": False,
            "production_deployment_completion_claimed": False,
            "security_drill_completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            {**base.file_record(FP010_OVERLAY_JSON), "name": "fp010_overlay_predecessor"},
            generated_record(
                IMPLEMENTATION_JSON,
                implementation_text,
                name="fp011_long_lived_login_implementation",
            ),
            generated_record(
                VERIFICATION_JSON,
                verification_text,
                name="fp011_long_lived_login_verification",
            ),
            generated_record(GAP_R015_JSON, gap_text, name="gap_r015"),
            generated_record(BACKLOG_R015_JSON, backlog_text, name="backlog_r015"),
        ],
        "application_rule": predecessor["application_rule"],
        "events": events,
        "draft_observations": predecessor["draft_observations"],
        "open_evidence_boundaries": {
            **predecessor["open_evidence_boundaries"],
            "fp011_long_lived_login": "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
            "fp011_actual_user_status": "NOT_RUN",
            "actual_device_fp011_status": "NOT_RUN",
            "fp011_actual_network_status": "NOT_RUN",
            "fp011_production_credentials_status": "NOT_RUN",
            "fp011_production_deployment_status": "NOT_RUN",
            "fp011_remote_device_revoke_drill_status": "NOT_RUN",
            "fp011_account_lock_drill_status": "NOT_RUN",
            "fp011_security_incident_drill_status": "NOT_RUN",
            "fp011_production_long_lived_login_enabled": False,
            "fp011_formal_test_total": 279,
            "fp011_formal_test_total_status": "NOT_RUN",
            "fp013_first_run_integrated_consent": "PLANNED_NEXT",
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
        "document_id": "WS-FP011-LONG-LIVED-LOGIN-SUCCESSOR-20260725-001",
        "goal_id": GOAL_ID,
        "kind": "SUCCESSOR_TRACE",
        "status": "PASS",
        "observed_at": OBSERVED_SUCCESSOR_AT,
        "canonical_update_event_type": "CANONICAL_BINDINGS_UPDATED",
        "resulting_canonical_bindings": {
            "IMPLEMENTATION_BACKLOG": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R015_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
            "IMPLEMENTATION_GAP": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R015_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
        },
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-011"],
            "IMPLEMENTATION_GAP": ["FP-011", "GAP-020"],
        },
        "next_policy_gap_pair": {
            "source_policy_id": "FP-013",
            "gap_id": "GAP-022",
        },
    }


def build_review(result_hashes: dict[str, str]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP011-LONG-LIVED-LOGIN-INTERNAL-REVIEW-20260725-001",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": GOAL_ID,
        "status": "PASS",
        "reviewer_id": "CODEX-FP011-LONG-LIVED-LOGIN-SEPARATE-REVIEW-20260725-001",
        "reviewed_result_sha256_by_kind": result_hashes,
        "reviewed_at": REVIEWED_AT,
        "findings": {
            "blocking": 0,
            "major_open": 0,
            "resolved_before_acceptance": [
                "access and refresh purposes, lifetimes and storage boundaries are distinct",
                "raw refresh credentials are not persisted by the Gateway state model",
                "one-time rotation and reuse detection revoke only the affected device family",
                "idle and absolute expiry both fail closed",
                "selective device revoke is distinct from account-wide lock and incident revoke",
                "Android restore remains unverified until server refresh and durable replacement save",
                "stale, duplicate, late, reordered and partial-save results cannot advance authentication",
                "walk start and resume require the verified actor-bound Gateway state",
                "logout stops login-dependent activity without changing OS permission grants",
                "long-lived capability remains disabled by default pending approved configuration",
            ],
            "known_nonblocking_boundaries": [
                "production credentials, deployment and long-lived enablement remain unconfigured",
                "actual users, devices, networks and security drills were not run",
                "formal tests and release gates were not run",
            ],
        },
        "review_boundary": {
            "separate_internal_review_pass": True,
            "external_independence_claimed": False,
            "formal_tests_remain_not_run": True,
            "actual_user_tests_remain_not_run": True,
            "actual_device_tests_remain_not_run": True,
            "actual_network_tests_remain_not_run": True,
            "production_deployment_remains_not_run": True,
            "security_drills_remain_not_run": True,
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
    execution_session = latest_execution_event(
        base.load_json(CHECKPOINT)["goal_execution"],
    )
    require(execution_session is not None, "FP-011 execution-session event is missing")
    return {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP011-LONG-LIVED-LOGIN-WORK-ITEM-COMPLETION-20260725-001"
        ),
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": base.sha256_file(GOAL_PATH),
        "work_item_id": "EPIC-02-FP011-LONG-LIVED-LOGIN",
        "source_policy_ids": ["FP-011"],
        "gap_ids": ["GAP-020"],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": EXPECTED_EXECUTION_EVENT_SHA256,
        "execution_session_event": {
            "sequence": execution_session["sequence"],
            "event_id": execution_session["event_id"],
            "event_type": execution_session["event_type"],
            "event_sha256": execution_session["event_sha256"],
        },
        "implementation_start_gate_binding": {
            "document_id": EXPECTED_START_GATE_RECEIPT_ID,
            "path": relative(START_GATE_RECEIPT),
            "file_sha256": EXPECTED_START_GATE_RECEIPT_SHA256,
        },
        "execution_window": {
            "started_at": execution_session["occurred_at"],
            "ended_at": COMPLETED_AT,
        },
        "completed_at": COMPLETED_AT,
        "executor": {
            "id": "CODEX-FP011-LONG-LIVED-LOGIN-IMPLEMENTER-20260725-001",
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_4_STANDING_EXECUTION_AUTHORITY",
        },
        "reviewer": {
            "id": "CODEX-FP011-LONG-LIVED-LOGIN-SEPARATE-REVIEW-20260725-001",
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
    row = next(item for item in value["assessments"] if item["gap_id"] == "GAP-020")
    return (
        "# WalkSafe 구현 Gap 분석 r015\n\n"
        "- 직접 재평가: `FP-011 / GAP-020`\n"
        f"- 판정: `{row['status']}` (`MISSING → PARTIAL`)\n"
        "- 내부 Android·Gateway focused/전체 회귀와 build·lint/typecheck: `PASS`\n"
        "- TC-FP-011-01~05·정식 279개·실제 사용자·기기·네트워크·보안 훈련: `NOT_RUN`\n"
        "- 출시 Gate 5개: `NOT_RUN` / 미면제\n"
        "- 출시: `NOT_ELIGIBLE`\n\n"
        f"{row['rationale']}\n"
    )


def backlog_markdown(value: dict[str, Any]) -> str:
    action = value["next_single_action"]
    return (
        "# WalkSafe 구현 보완 Backlog r015\n\n"
        "- EPIC-02: `IN_PROGRESS`\n"
        f"- 다음 정책·Gap: `{action['source_policy_id']} / {action['gap_id']}`\n"
        "- 다음 정책: `첫 실행 통합 동의`\n"
        f"- 다음 작업: {action['action']}\n"
    )


def overlay_markdown(value: dict[str, Any]) -> str:
    return (
        "# EPIC-02 FP-011 장기 로그인 Active ledger overlay\n\n"
        f"- Overlay: `{value['metadata']['overlay_id']}`\n"
        "- FP-011 내부 구현·검증: `PARTIAL_IMPLEMENTATION_VERIFIED`\n"
        "- TC-FP-011-01~05·정식 279개·사용자·기기·네트워크·보안 훈련: `NOT_RUN`\n"
        "- 출시 Gate 5개: `NOT_RUN` / 미면제\n"
        "- 출시: `NOT_ELIGIBLE`\n"
    )


def build_outputs(
    *,
    focused_log: Path = FOCUSED_LOG,
    full_android_log: Path = FULL_ANDROID_LOG,
    gateway_log: Path = GATEWAY_LOG,
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
        gateway_log=gateway_log,
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
        GAP_R015_JSON: gap_text,
        GAP_R015_MD: gap_markdown(gap),
        BACKLOG_R015_JSON: backlog_text,
        BACKLOG_R015_MD: backlog_markdown(backlog),
        FP011_OVERLAY_JSON: json_text(overlay),
        FP011_OVERLAY_MD: overlay_markdown(overlay),
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
    parser.add_argument("--gateway-log", type=Path, default=GATEWAY_LOG)
    parser.add_argument("--control-plane-log", type=Path, default=CONTROL_PLANE_LOG)
    parser.add_argument("--output-root", type=Path, default=ROOT)
    parser.add_argument("--implementation-content-set-sha256")
    args = parser.parse_args(argv)
    try:
        outputs = build_outputs(
            focused_log=args.focused_log,
            full_android_log=args.full_android_log,
            gateway_log=args.gateway_log,
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
        print(f"FP-011 long-lived trace: FAIL: {exc}", file=sys.stderr)
        return 1
    mode_name = "WRITE" if args.write else "CHECK"
    print(
        f"FP-011 long-lived trace: PASS outputs={len(outputs)} "
        f"next=FP-013/GAP-022 mode={mode_name}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
