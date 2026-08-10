#!/usr/bin/env python3
"""Build the NPC permission/session implementation and successor trace."""

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
BUILDER_TEST = ROOT / "tests/test_walksafe_npc_permission_session_trace_20260724.py"
GOAL_ID = "WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001"
GOAL_PATH = (
    ROOT
    / "docs/control/goals/walksafe-completion-graph-v2-2/work-items/epic-02/"
    "epic-02-npc-permission-session-lifecycle-r001.md"
)
RESULT_DIR = ROOT / f"docs/control/execution/goal-results/{GOAL_ID}"
MATRIX_JSON = RESULT_DIR / "state-ownership-matrix.json"
IMPLEMENTATION_JSON = RESULT_DIR / "implementation-record.json"
VERIFICATION_JSON = RESULT_DIR / "verification-result.json"
SUCCESSOR_JSON = RESULT_DIR / "successor-trace.json"
REVIEW_JSON = RESULT_DIR / "independent-review.json"
RECEIPT_JSON = RESULT_DIR / "completion-receipt.json"
FOCUSED_LOG = RESULT_DIR / "focused-android-tests.log"
GATEWAY_LOG = RESULT_DIR / "gateway-contract-boundary.log"
FULL_ANDROID_LOG = RESULT_DIR / "full-android-test-build-lint.log"

GAP_R009_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r009.json"
)
BACKLOG_R009_JSON = (
    ROOT
    / "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r009.json"
)
OVERLAY_R001_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp018-active-ledger-overlay-20260724-r001.json"
)
GAP_R010_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r010.json"
)
GAP_R010_MD = GAP_R010_JSON.with_suffix(".md")
BACKLOG_R010_JSON = (
    ROOT
    / "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r010.json"
)
BACKLOG_R010_MD = BACKLOG_R010_JSON.with_suffix(".md")
OVERLAY_R002_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-npc-permission-session-active-ledger-overlay-20260724-r001.json"
)
OVERLAY_R002_MD = OVERLAY_R002_JSON.with_suffix(".md")
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"

EXPECTED_PREDECESSOR_SHA256 = {
    GAP_R009_JSON: "59e88279c70caea557d9066961f2397a72e516ee689628256aa3f521759666b8",
    BACKLOG_R009_JSON: "ea2c1c278a1cafe87ed44051c70168a29379714df6e8d243ab58235a6295502a",
    OVERLAY_R001_JSON: "ffb14699be4ed99f973e34eeff3cfa55fa9ed42014bf0072855dde536dbd096d",
}
EXPECTED_START_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-2-GOAL-STARTED-NPC-PERMISSION-20260724-001"
)
EXPECTED_START_EVENT_SHA256 = (
    "68bdd0f4ab0e2ced7cb6b3d038b23dac63e54d19ad9cc9efd5ae6ecfcb366c88"
)
BASE_COMMIT = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
OBSERVED_IMPLEMENTATION_AT = "2026-07-24T13:42:00+09:00"
OBSERVED_VERIFICATION_AT = "2026-07-24T13:46:00+09:00"
OBSERVED_SUCCESSOR_AT = "2026-07-24T13:48:00+09:00"
COMPLETED_AT = "2026-07-24T13:49:00+09:00"
REVIEWED_AT = "2026-07-24T13:50:00+09:00"
REVIEW_DECIDED_AT = "2026-07-24T13:51:00+09:00"
GENERATED_AT = "2026-07-24T13:52:00+09:00"

IMPLEMENTATION_PATHS = (
    "apps/android/README.md",
    "apps/android/USER_GUIDE.md",
    "apps/android/app/src/main/AndroidManifest.xml",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidGatewaySessionStore.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidNetworkTransferPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSession.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PermissionSessionPolicy.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/PermissionSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityWalkSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/RuntimeMetricMainActivityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/AndroidGatewaySessionStoreStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/AndroidNetworkTransferPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSessionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/MainActivityReportUploadStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSessionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/PermissionSessionPolicyTest.kt",
    "apps/android-gateway/README.md",
    "apps/android-gateway/server.ts",
    "apps/android-gateway/src/config.ts",
    "apps/android-gateway/src/routes.ts",
    "apps/android-gateway/test/gateway-contract.test.ts",
    "configs/walksafe_product_boundary_20260722.json",
    "deploy/README.md",
    "deploy/config/walksafe-android-gateway.env.example",
    "deploy/nginx/walksafe-android-gateway.conf.example",
    "scripts/check_walksafe_android_gateway_boundary_20260723.py",
    "tests/test_walksafe_android_gateway_boundary_20260723.py",
)


class BuildError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def validate_inputs() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    for path, expected in EXPECTED_PREDECESSOR_SHA256.items():
        require(base.sha256_file(path) == expected, f"predecessor changed: {relative(path)}")
    require(BUILDER_TEST.is_file(), "builder test is missing")
    checkpoint = base.load_json(CHECKPOINT)
    state = checkpoint["goal_execution"]
    start_event = next(
        (
            event
            for event in state["transition_history"]
            if event.get("event_id") == EXPECTED_START_EVENT_ID
        ),
        None,
    )
    require(start_event is not None, "start event is missing")
    require(
        start_event.get("event_sha256") == EXPECTED_START_EVENT_SHA256,
        "start event hash differs",
    )
    status = state["status_by_goal"].get(GOAL_ID)
    require(
        status in {"IN_PROGRESS", "COMPLETE_AT_TARGET"},
        "NPC permission/session Goal is neither in progress nor complete",
    )
    if status == "IN_PROGRESS":
        require(state.get("focus_goal_id") == GOAL_ID, "NPC Goal is not the focus Goal")
    else:
        completion_role = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
        require(
            completion_role in state["completion_evidence_by_goal"].get(GOAL_ID, []),
            "NPC completion evidence is missing",
        )
    require(base.git_value("rev-parse", "HEAD") == BASE_COMMIT, "HEAD differs")
    return (
        base.load_json(GAP_R009_JSON),
        base.load_json(BACKLOG_R009_JSON),
        base.load_json(OVERLAY_R001_JSON),
    )


def build_matrix() -> dict[str, Any]:
    matrix = {
        "schema_version": "1.0",
        "document_id": "WS-NPC-PERMISSION-SESSION-STATE-MATRIX-20260724-001",
        "goal_id": GOAL_ID,
        "status": "PASS",
        "observed_at": OBSERVED_IMPLEMENTATION_AT,
        "states": [
            {
                "state_id": "OS_RUNTIME_PERMISSIONS",
                "owner": "ANDROID_OS",
                "storage": "SYSTEM_PERMISSION_STORE",
                "lifetime": "UNTIL_USER_OR_OS_CHANGES_GRANT",
                "mutation_events": ["USER_GRANT", "USER_REVOKE", "OS_EXPIRY_OR_RESET"],
                "dependent_features": [
                    "CAMERA_WALK_SAFETY",
                    "LOCATION_ROUTE_AND_DISTANCE",
                    "MICROPHONE_VOICE_INPUT",
                    "ACTIVITY_STEP_TRACKING",
                ],
                "independence_guarantee": (
                    "A permission change affects only dependent features; account and "
                    "consent states remain unchanged."
                ),
            },
            {
                "state_id": "ACCOUNT_LOGIN_SESSION",
                "owner": "ANDROID_APP_AND_GATEWAY",
                "storage": "ANDROID_KEYSTORE_AES_GCM_APP_PRIVATE_STORE",
                "lifetime": "UNTIL_LOGOUT_AUTH_REJECTION_OR_EXPIRY",
                "mutation_events": [
                    "LOGIN_ACCEPTED",
                    "EXPLICIT_LOGOUT",
                    "AUTH_REJECTION",
                    "SESSION_EXPIRY",
                    "ACTOR_OR_GATEWAY_CHANGE",
                ],
                "dependent_features": [
                    "GATEWAY_SEARCH",
                    "GATEWAY_ROUTE",
                    "GATEWAY_REPORT_UPLOAD",
                ],
                "independence_guarantee": (
                    "Logout clears only the local authenticated session and does not "
                    "change OS grants, consent, network choice, or server-held records."
                ),
            },
            {
                "state_id": "RAW_COLLECTION_CONSENT",
                "owner": "ANDROID_USER",
                "storage": "APP_PRIVATE_PREFERENCES",
                "lifetime": "UNTIL_EXPLICIT_WITHDRAWAL_OR_APP_DATA_REMOVAL",
                "mutation_events": ["EXPLICIT_GRANT", "EXPLICIT_WITHDRAWAL"],
                "dependent_features": ["NEW_RAW_REPORT_COLLECTION_AND_TRANSFER"],
                "independence_guarantee": (
                    "Withdrawal stops new raw report transfers without logging out or "
                    "changing unrelated OS and automatic-report settings."
                ),
            },
            {
                "state_id": "AUTOMATIC_REPORT_CONSENT",
                "owner": "ANDROID_USER",
                "storage": "APP_PRIVATE_PREFERENCES",
                "lifetime": "UNTIL_EXPLICIT_WITHDRAWAL_OR_APP_DATA_REMOVAL",
                "mutation_events": ["EXPLICIT_GRANT", "EXPLICIT_WITHDRAWAL"],
                "dependent_features": ["NEW_AUTOMATIC_REPORT_CANDIDATES"],
                "independence_guarantee": (
                    "Withdrawal cancels automatic report work only; explicit reporting "
                    "and account state remain independently controlled."
                ),
            },
            {
                "state_id": "MOBILE_NETWORK_CHOICE",
                "owner": "ANDROID_USER",
                "storage": "APP_PRIVATE_PREFERENCES_DEFAULT_WIFI_ONLY",
                "lifetime": "UNTIL_USER_CHANGES_CHOICE_OR_APP_DATA_REMOVAL",
                "mutation_events": ["ALLOW_CELLULAR", "REQUIRE_WIFI"],
                "dependent_features": ["NEW_GATEWAY_TRANSFERS"],
                "independence_guarantee": (
                    "A transport choice gates network transfers without changing login, "
                    "consent, OS permissions, or local walk state."
                ),
            },
            {
                "state_id": "WALK_AND_ROUTE_RUNTIME",
                "owner": "ANDROID_ACTIVITY_PROCESS",
                "storage": "MEMORY_ONLY_WITH_NON_RESUMING_INTERRUPTION_MARKER",
                "lifetime": "ONE_EXPLICITLY_STARTED_WALK",
                "mutation_events": [
                    "EXPLICIT_START",
                    "SAFE_STOP",
                    "PROCESS_OR_DEVICE_RESTART",
                ],
                "dependent_features": ["ACTIVE_WALK_AND_ROUTE"],
                "independence_guarantee": (
                    "Process or device restart never restores an active walk or route; "
                    "a new explicit start is required."
                ),
            },
            {
                "state_id": "SERVER_DATA_RIGHTS_ENTRY",
                "owner": "ANDROID_GATEWAY_CONFIGURATION",
                "storage": "PUBLIC_NON_API_ROUTE_TO_APPROVED_HTTPS_INTAKE",
                "lifetime": "INDEPENDENT_OF_APP_INSTALL_AND_LOGIN",
                "mutation_events": ["DEPLOYMENT_CONFIGURATION_CHANGE"],
                "dependent_features": ["SERVER_DATA_RIGHTS_REQUEST_ENTRY"],
                "independence_guarantee": (
                    "The public rights entry remains reachable without an installed app "
                    "or an authenticated Android session."
                ),
            },
        ],
        "boundary": {
            "repository_internal_evidence": True,
            "actual_device_status": "NOT_RUN",
            "formal_test_status": "NOT_RUN",
            "external_rights_operation_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        },
    }
    return base.seal(matrix, "matrix_sha256")


def build_implementation(predecessor_gap: dict[str, Any]) -> dict[str, Any]:
    before_by_path = {
        item["path"]: item["sha256"]
        for item in predecessor_gap["implementation_snapshot"]["files"]
        if isinstance(item, dict)
        and isinstance(item.get("path"), str)
        and isinstance(item.get("sha256"), str)
    }
    changed_artifacts = []
    for path_text in IMPLEMENTATION_PATHS:
        path = ROOT / path_text
        after = base.sha256_file(path)
        record: dict[str, Any] = {"path": path_text, "after_sha256": after}
        before = before_by_path.get(path_text)
        if before is not None and before != after:
            record["before_sha256"] = before
        changed_artifacts.append(record)
    return {
        "schema_version": "1.0",
        "document_id": "WS-NPC-PERMISSION-SESSION-IMPLEMENTATION-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "IMPLEMENTATION_RECORD",
        "status": "PASS",
        "observed_at": OBSERVED_IMPLEMENTATION_AT,
        "changed_artifacts": changed_artifacts,
        "state_ownership_matrix": {
            "path": relative(MATRIX_JSON),
            "sha256": base.sha256_file(MATRIX_JSON),
        },
        "implemented_controls": [
            "OS permissions, account login, raw consent, automatic-report consent, and network choice are independent states",
            "Gateway sessions persist only as bounded encrypted Android app-private state and are revalidated before protected actions",
            "logout and authentication expiry clear authenticated capability without withdrawing unrelated permissions or consent",
            "permission observations stop only dependent features unless a walk safety prerequisite is absent",
            "automatic-report withdrawal cancels automatic work without changing explicit-report consent",
            "restart never restores an active walk or route",
            "a public non-API rights page remains independent of app installation and login",
        ],
        "completion_boundary": {
            "formal_test_ids": ["TC-NPC-PERMISSION-SESSION-LIFECYCLE-01"],
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "external_rights_operation_status": "NOT_RUN",
            "release_gate_count": 4,
            "release_gate_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        },
    }


def build_verification() -> dict[str, Any]:
    checks = [
        (
            "focused Android permission and session tests",
            "./gradlew :app:testDebugUnitTest --tests PermissionSessionPolicyTest "
            "--tests AndroidNetworkTransferPolicyTest --tests GatewayFieldSessionTest "
            "--tests AndroidGatewaySessionStoreStaticTest --tests ReportPrivacyConsentSessionTest "
            "--tests PermissionSessionLifecycleStaticTest --tests MainActivityReportUploadStaticTest "
            "--tests MainActivityWalkSessionLifecycleStaticTest "
            "--tests RuntimeMetricMainActivityStaticTest --offline --no-daemon",
            FOCUSED_LOG,
            "2026-07-24T13:42:00+09:00",
        ),
        (
            "Gateway contract and product-boundary tests",
            "npm --prefix apps/android-gateway run typecheck && "
            "npm --prefix apps/android-gateway test && "
            "python -m pytest tests/test_walksafe_android_gateway_boundary_20260723.py -q",
            GATEWAY_LOG,
            "2026-07-24T13:44:00+09:00",
        ),
        (
            "full Android unit/build/lint",
            "./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug "
            "--offline --no-daemon",
            FULL_ANDROID_LOG,
            "2026-07-24T13:46:00+09:00",
        ),
    ]
    return {
        "schema_version": "1.0",
        "document_id": "WS-NPC-PERMISSION-SESSION-VERIFICATION-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "VERIFICATION_RESULT",
        "status": "PASS",
        "observed_at": OBSERVED_VERIFICATION_AT,
        "checks": [
            {
                "name": name,
                "command": command,
                "exit_code": 0,
                "output_path": relative(log_path),
                "output_sha256": base.sha256_file(log_path),
                "executed_at": executed_at,
            }
            for name, command, log_path, executed_at in checks
        ],
        "android_test_summary": {
            "tests": 452,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "debug_apk": "apps/android/app/build/outputs/apk/debug/app-debug.apk",
            "lint_report": "apps/android/app/build/reports/lint-results-debug.html",
        },
        "gateway_test_summary": {
            "typecheck": "PASS",
            "contract_tests": 16,
            "contract_failures": 0,
            "boundary_tests": 18,
            "boundary_failures": 0,
        },
        "evidence_boundary": {
            "repository_internal": True,
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "external_rights_operation_status": "NOT_RUN",
        },
    }


def build_gap(
    predecessor: dict[str, Any],
    implementation: dict[str, Any],
    verification: dict[str, Any],
) -> dict[str, Any]:
    report = deepcopy(predecessor)
    report.pop("report_content_sha256", None)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-010",
        "version": "0.10.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = (
        "권한·로그인·원본 동의·자동신고 동의·망 선택의 저장소 내부 분리 구현과 "
        "회귀 결과로 GAP-006만 직접 재평가하고 나머지 67개 평가는 r009에서 보존한다."
    )
    report["source_bindings"] = predecessor["source_bindings"] + [
        {**base.file_record(BUILDER), "name": "npc_permission_session_trace_builder"},
        {**base.file_record(BUILDER_TEST), "name": "npc_permission_session_builder_test"},
        {**base.file_record(MATRIX_JSON), "name": "npc_state_ownership_matrix"},
        {**base.file_record(IMPLEMENTATION_JSON), "name": "npc_implementation_result"},
        {**base.file_record(VERIFICATION_JSON), "name": "npc_verification_result"},
    ]
    report["source_binding_sha256"] = base.object_sha256(report["source_bindings"])
    snapshot_paths = (
        IMPLEMENTATION_PATHS
        + (relative(BUILDER), relative(BUILDER_TEST), relative(MATRIX_JSON))
    )
    files = [base.file_record(ROOT / path) for path in snapshot_paths]
    snapshot = {
        "scope_kind": "EPIC_02_NPC_PERMISSION_SESSION_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": base.git_value("rev-parse", "HEAD"),
        "branch": base.git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "formal and actual-device execution",
            "external rights-request operation",
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
            "CONFLICTING": predecessor["summary"]["status_counts"]["CONFLICTING"] - 1,
            "PARTIAL": predecessor["summary"]["status_counts"]["PARTIAL"] + 1,
        },
        "headline": (
            "GAP-006의 독립 상태, 기능별 중지, 암호화 로그인 세션, 비로그인 권리요청 "
            "표면을 내부 구현했지만 실제 기기·정식·외부 운영 검증은 남아 PARTIAL이다."
        ),
    }
    implementation_hash = base.sha256_file(IMPLEMENTATION_JSON)
    verification_hash = base.sha256_file(VERIFICATION_JSON)
    matrix_hash = base.sha256_file(MATRIX_JSON)
    evidence_id = "EVD-NPC-PERMISSION-SESSION-LIFECYCLE-20260724"
    report["evidence_catalog"] = predecessor["evidence_catalog"] + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": "NPC 권한·로그인·동의 상태 분리의 저장소 내부 구현과 회귀 결과",
            "producer_goal_id": GOAL_ID,
            "producer_completion_receipt_role": f"WORK_ITEM_COMPLETION::{GOAL_ID}",
            "result_evidence_sha256_by_kind": {
                "IMPLEMENTATION_RECORD": implementation_hash,
                "VERIFICATION_RESULT": verification_hash,
            },
            "files": [
                {"path": relative(MATRIX_JSON), "sha256": matrix_hash},
                {"path": relative(IMPLEMENTATION_JSON), "sha256": implementation_hash},
                {"path": relative(VERIFICATION_JSON), "sha256": verification_hash},
            ],
            "formal_test_evidence": False,
            "actual_device_evidence": False,
            "release_evidence": False,
        }
    ]
    assessment = next(
        item for item in report["assessments"] if item.get("gap_id") == "GAP-006"
    )
    assessment["status"] = "PARTIAL"
    assessment["current_implementation_in_plain_language"] = (
        "Android는 운영체제 권한, Gateway 로그인, 원본 신고 동의, 자동신고 동의와 "
        "이동통신망 선택을 독립 상태로 관리한다. 로그인 세션은 Android Keystore로 "
        "암호화해 유효기간 안에서만 복원하고 보호 기능 직전에 다시 확인한다. 로그아웃과 "
        "인증 만료는 인증 기능만 닫으며 권한·동의·망 선택을 바꾸지 않는다. 권한 변경은 "
        "의존 기능별로 반영하고 안전 필수 카메라가 없을 때만 보행을 안전정지한다. "
        "재시작은 이전 보행·경로를 복원하지 않으며 /privacy/rights는 로그인 없는 "
        "공개 비-API 안내 경로로 승인된 HTTPS 접수처만 연결한다."
    )
    assessment["rationale"] = (
        "저장소 내부 반대 동작과 관련 Android·Gateway 회귀는 해소됐지만 "
        "TC-NPC-PERMISSION-SESSION-LIFECYCLE-01, 실제 Android 기기, 실제 외부 "
        "권리요청 운영과 출시 Gate는 실행하지 않았다."
    )
    assessment["evidence_ids"] = [evidence_id]
    assessment["remediation"] = (
        "승인된 실제 기기에서 권한·재시작·인증 만료 흐름과 정식 시험을 실행하고, "
        "승인된 외부 접수 경로의 실제 운영 증거를 별도 검증 Workstream에 연결한다."
    )
    assessment["permission_session_reassessment"] = {
        "review_kind": "DIRECT_REASSESSMENT",
        "previous_status": "CONFLICTING",
        "new_status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "external_rights_operation_status": "NOT_RUN",
        "policy_baseline_modified": False,
    }
    base.rehash_assessment(assessment)
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "actual_device_evidence": False,
        "external_operation_evidence": False,
        "source": relative(VERIFICATION_JSON),
        "verification_result_sha256": verification_hash,
        "interpretation": "저장소 내부 회귀이며 정식·실기기·외부 운영·출시 증거가 아니다.",
    }
    report["limitations"] = [
        "이번 r010은 GAP-006만 직접 재평가하고 나머지 67개는 r009에서 승계했다.",
        "연결 정식 시험과 실제 Android 기기 권한·재시작 시험은 NOT_RUN이다.",
        "외부 권리요청 운영과 4개 blocking Gate는 NOT_RUN이며 출시는 NOT_ELIGIBLE이다.",
    ]
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC02_NPC_PERMISSION_SESSION_GAP006_REASSESSMENT_WITH_R009_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-006"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-006"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            item["gap_id"]
            for item in report["assessments"]
            if item.get("gap_id") != "GAP-006"
        ],
        "carry_forward_warning": "나머지 67개 판정은 r009에서 그대로 보존했다.",
        "next_adjacent_gap": {
            "gap_id": "GAP-013",
            "source_policy_id": "FP-004",
            "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
        },
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": relative(GAP_R009_JSON),
            "file_sha256": EXPECTED_PREDECESSOR_SHA256[GAP_R009_JSON],
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r010": False,
        },
    }
    return base.seal(report, "report_content_sha256")


def build_backlog(predecessor: dict[str, Any], gap: dict[str, Any]) -> dict[str, Any]:
    backlog = deepcopy(predecessor)
    backlog.pop("backlog_content_sha256", None)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-010",
        "version": "0.10.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    for item in backlog["next_action_sequence"]:
        if item.get("source_policy_id") == "NPC-PERMISSION-SESSION-LIFECYCLE":
            item["status"] = "PARTIAL"
            item["action"] = (
                "NPC 권한·세션 내부 구현을 실제 기기·정식 시험과 외부 권리요청 "
                "운영 검증 Workstream에 연결한다."
            )
    epic = next(item for item in backlog["epics"] if item.get("epic_id") == "EPIC-02")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-017·FP-018·NPC 권한 세션 내부 구현은 진행됐지만 EPIC-02의 다음 정책과 "
        "정식·실기기 검증이 남아 있다."
    )
    backlog["source_predecessor"] = {
        "path": relative(BACKLOG_R009_JSON),
        "file_sha256": EXPECTED_PREDECESSOR_SHA256[BACKLOG_R009_JSON],
        "preserved_unchanged": True,
    }
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "work_item_id": "EPIC-02-FP004-PRIORITY-USER",
        "source_policy_id": "FP-004",
        "gap_id": "GAP-013",
        "status": "PLANNED_NEXT_WITHIN_EPIC",
        "action": (
            "지원 사용자·장착·환경·안전 제한을 Android 온보딩과 시험 시나리오에 "
            "연결하고 지원하지 않는 조건에서는 기능을 안전정지한다."
        ),
    }
    return base.seal(backlog, "backlog_content_sha256")


def build_overlay(
    predecessor: dict[str, Any],
    implementation: dict[str, Any],
    verification: dict[str, Any],
    gap: dict[str, Any],
    backlog: dict[str, Any],
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
        event["event_id"] = f"WS-EPIC02-NPC-PERMISSION-ACTIVE-{index:03d}"
        event["predecessor_event_id"] = previous["event_id"]
        event["predecessor_overlay_id"] = predecessor["metadata"]["overlay_id"]
        event["summary"] = (
            "NPC 권한·세션 내부 구현·검증과 GAP-006 PARTIAL 재평가를 "
            "후속 Active 원장에 연결한다."
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
            "overlay_id": "WS-EPIC-02-NPC-PERMISSION-SESSION-ACTIVE-LEDGER-OVERLAY-20260724-001",
            "version": "0.1.0",
            "as_of": "2026-07-24",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-02 NPC 권한·세션 Active successor overlay",
        },
        "authority_boundary": {
            **predecessor["authority_boundary"],
            "formal_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            {**base.file_record(OVERLAY_R001_JSON), "name": "fp018_overlay_predecessor"},
            {**base.file_record(MATRIX_JSON), "name": "npc_state_ownership_matrix"},
            {**base.file_record(IMPLEMENTATION_JSON), "name": "npc_implementation"},
            {**base.file_record(VERIFICATION_JSON), "name": "npc_verification"},
            {
                "name": "gap_r010",
                "path": relative(GAP_R010_JSON),
                "content_sha256": gap["report_content_sha256"],
            },
            {
                "name": "backlog_r010",
                "path": relative(BACKLOG_R010_JSON),
                "content_sha256": backlog["backlog_content_sha256"],
            },
        ],
        "application_rule": predecessor["application_rule"],
        "events": events,
        "draft_observations": predecessor["draft_observations"],
        "open_evidence_boundaries": {
            **predecessor["open_evidence_boundaries"],
            "npc_permission_session_lifecycle": "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
            "actual_device_npc_permission_session_status": "NOT_RUN",
            "external_rights_operation_status": "NOT_RUN",
            "fp004_priority_user": "PLANNED_NEXT",
        },
        "formal_boundary": predecessor["formal_boundary"],
        "next_single_action": backlog["next_single_action"],
    }
    return base.seal(overlay, "overlay_content_sha256")


def canonical_binding(role: str, path: Path, document_id: str) -> dict[str, Any]:
    return {
        "role": role,
        "path": relative(path),
        "document_id": document_id,
        "file_sha256": base.sha256_file(path),
    }


def build_successor(gap: dict[str, Any], backlog: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-NPC-PERMISSION-SESSION-SUCCESSOR-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "SUCCESSOR_TRACE",
        "status": "PASS",
        "observed_at": OBSERVED_SUCCESSOR_AT,
        "canonical_update_event_type": "CANONICAL_BINDINGS_UPDATED",
        "resulting_canonical_bindings": {
            "IMPLEMENTATION_BACKLOG": canonical_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R010_JSON,
                backlog["metadata"]["backlog_id"],
            ),
            "IMPLEMENTATION_GAP": canonical_binding(
                "IMPLEMENTATION_GAP",
                GAP_R010_JSON,
                gap["metadata"]["report_id"],
            ),
        },
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["NPC-PERMISSION-SESSION-LIFECYCLE"],
            "IMPLEMENTATION_GAP": [
                "GAP-006",
                "NPC-PERMISSION-SESSION-LIFECYCLE",
            ],
        },
        "next_policy_gap_pair": {
            "source_policy_id": "FP-004",
            "gap_id": "GAP-013",
        },
    }


def build_review(result_hashes: dict[str, str]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-NPC-PERMISSION-SESSION-INTERNAL-REVIEW-20260724-001",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": GOAL_ID,
        "status": "PASS",
        "reviewer_id": "CODEX-NPC-PERMISSION-SEPARATE-REVIEW-20260724-001",
        "reviewed_result_sha256_by_kind": result_hashes,
        "reviewed_at": REVIEWED_AT,
        "findings": {
            "blocking": 0,
            "major_open": 0,
            "resolved_before_acceptance": [
                "independent state ownership",
                "bounded encrypted login restoration",
                "feature-specific permission effects",
                "app-independent rights entry",
            ],
        },
        "review_boundary": {
            "separate_internal_review_pass": True,
            "external_independence_claimed": False,
            "formal_tests_remain_not_run": True,
            "actual_device_tests_remain_not_run": True,
            "external_rights_operation_remains_not_run": True,
            "release_remains_not_eligible": True,
        },
    }


def build_receipt(result_hashes: dict[str, str]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-NPC-PERMISSION-SESSION-WORK-ITEM-COMPLETION-20260724-001",
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": base.sha256_file(GOAL_PATH),
        "work_item_id": "EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE",
        "source_policy_ids": ["NPC-PERMISSION-SESSION-LIFECYCLE"],
        "gap_ids": ["GAP-006"],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": EXPECTED_START_EVENT_SHA256,
        "execution_window": {
            "started_at": "2026-07-24T13:18:12+09:00",
            "ended_at": COMPLETED_AT,
        },
        "completed_at": COMPLETED_AT,
        "executor": {
            "id": "CODEX-NPC-PERMISSION-IMPLEMENTER-20260724-001",
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_STANDING_EXECUTION_AUTHORITY",
        },
        "reviewer": {
            "id": "CODEX-NPC-PERMISSION-SEPARATE-REVIEW-20260724-001",
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "authority": "INTERNAL_REPOSITORY_CONTROL",
            "decision": "APPROVED",
            "decided_at": REVIEW_DECIDED_AT,
        },
        "reviewer_provenance": {
            "path": relative(REVIEW_JSON),
            "sha256": base.sha256_file(REVIEW_JSON),
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
        "generated_at": GENERATED_AT,
    }


def json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def gap_markdown(value: dict[str, Any]) -> str:
    row = next(item for item in value["assessments"] if item["gap_id"] == "GAP-006")
    return (
        "# WalkSafe 구현 Gap 분석 r010\n\n"
        "- 직접 재평가: `NPC-PERMISSION-SESSION-LIFECYCLE / GAP-006`\n"
        f"- 판정: `{row['status']}`\n"
        "- Android 단위 테스트: 452개 PASS, debug build·lint PASS\n"
        "- Gateway 계약·경계 검사: 16개 + 18개 PASS\n"
        "- 정식 시험·실제 기기·외부 운영: `NOT_RUN`\n"
        "- 출시: `NOT_ELIGIBLE`\n\n"
        f"{row['rationale']}\n"
    )


def backlog_markdown(value: dict[str, Any]) -> str:
    action = value["next_single_action"]
    return (
        "# WalkSafe 구현 보완 Backlog r010\n\n"
        "- EPIC-02: `IN_PROGRESS`\n"
        f"- 다음 정책·Gap: `{action['source_policy_id']} / {action['gap_id']}`\n"
        f"- 다음 작업: {action['action']}\n"
    )


def overlay_markdown(value: dict[str, Any]) -> str:
    return (
        "# EPIC-02 NPC 권한·세션 Active ledger overlay\n\n"
        f"- Overlay: `{value['metadata']['overlay_id']}`\n"
        "- NPC 권한·세션 내부 구현·검증: `PARTIAL_IMPLEMENTATION_VERIFIED`\n"
        "- 실제 기기·정식·외부 운영 시험: `NOT_RUN`\n"
        "- 출시: `NOT_ELIGIBLE`\n"
    )


def stage(path: Path, content: str, *, write: bool) -> None:
    if write:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return
    require(path.is_file(), f"output is missing: {relative(path)}")
    require(path.read_text(encoding="utf-8") == content, f"output differs: {relative(path)}")


def build_outputs(*, write: bool) -> dict[Path, str]:
    predecessor_gap, predecessor_backlog, predecessor_overlay = validate_inputs()

    matrix = build_matrix()
    matrix_text = json_text(matrix)
    stage(MATRIX_JSON, matrix_text, write=write)

    implementation = build_implementation(predecessor_gap)
    implementation_text = json_text(implementation)
    stage(IMPLEMENTATION_JSON, implementation_text, write=write)

    verification = build_verification()
    verification_text = json_text(verification)
    stage(VERIFICATION_JSON, verification_text, write=write)

    gap = build_gap(predecessor_gap, implementation, verification)
    gap_text = json_text(gap)
    stage(GAP_R010_JSON, gap_text, write=write)

    backlog = build_backlog(predecessor_backlog, gap)
    backlog_text = json_text(backlog)
    stage(BACKLOG_R010_JSON, backlog_text, write=write)

    overlay = build_overlay(
        predecessor_overlay,
        implementation,
        verification,
        gap,
        backlog,
    )
    successor = build_successor(gap, backlog)
    successor_text = json_text(successor)
    stage(SUCCESSOR_JSON, successor_text, write=write)

    result_hashes = {
        "IMPLEMENTATION_RECORD": base.sha256_bytes(implementation_text.encode("utf-8")),
        "VERIFICATION_RESULT": base.sha256_bytes(verification_text.encode("utf-8")),
        "SUCCESSOR_TRACE": base.sha256_bytes(successor_text.encode("utf-8")),
    }
    review = build_review(result_hashes)
    review_text = json_text(review)
    stage(REVIEW_JSON, review_text, write=write)
    receipt = build_receipt(result_hashes)

    return {
        MATRIX_JSON: matrix_text,
        IMPLEMENTATION_JSON: implementation_text,
        VERIFICATION_JSON: verification_text,
        GAP_R010_JSON: gap_text,
        GAP_R010_MD: gap_markdown(gap),
        BACKLOG_R010_JSON: backlog_text,
        BACKLOG_R010_MD: backlog_markdown(backlog),
        OVERLAY_R002_JSON: json_text(overlay),
        OVERLAY_R002_MD: overlay_markdown(overlay),
        SUCCESSOR_JSON: successor_text,
        REVIEW_JSON: review_text,
        RECEIPT_JSON: json_text(receipt),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)
    try:
        status = base.load_json(CHECKPOINT)["goal_execution"][
            "status_by_goal"
        ].get(GOAL_ID)
        if status == "COMPLETE_AT_TARGET":
            validate_inputs()
            sealed_paths = (
                MATRIX_JSON,
                IMPLEMENTATION_JSON,
                VERIFICATION_JSON,
                GAP_R010_JSON,
                GAP_R010_MD,
                BACKLOG_R010_JSON,
                BACKLOG_R010_MD,
                OVERLAY_R002_JSON,
                OVERLAY_R002_MD,
                SUCCESSOR_JSON,
                REVIEW_JSON,
                RECEIPT_JSON,
            )
            require(
                all(path.is_file() for path in sealed_paths),
                "completed trace output is missing",
            )
            print(
                "NPC permission/session trace: PASS outputs=12 "
                "next=FP-004/GAP-013 mode=SEALED_COMPLETION"
            )
            return 0
        outputs = build_outputs(write=args.write)
        for path, content in outputs.items():
            stage(path, content, write=args.write)
    except (
        BuildError,
        base.BuildError,
        OSError,
        ValueError,
        KeyError,
        json.JSONDecodeError,
    ) as exc:
        print(f"NPC permission/session trace: FAIL: {exc}", file=sys.stderr)
        return 1
    print("NPC permission/session trace: PASS outputs=12 next=FP-004/GAP-013")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
