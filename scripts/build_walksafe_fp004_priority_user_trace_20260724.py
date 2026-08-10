#!/usr/bin/env python3
"""Build the focused FP-004 priority-user implementation and successor trace."""

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
BUILDER_TEST = ROOT / "tests/test_walksafe_fp004_priority_user_trace_20260724.py"
GOAL_ID = "WS-GOAL-EPIC-02-FP-004-R001"
GOAL_PATH = (
    ROOT
    / "docs/control/goals/walksafe-completion-graph-v2-2/work-items/epic-02/"
    "epic-02-fp004-priority-user-r001.md"
)
RESULT_DIR = ROOT / f"docs/control/execution/goal-results/{GOAL_ID}"
IMPLEMENTATION_JSON = RESULT_DIR / "implementation-record.json"
VERIFICATION_JSON = RESULT_DIR / "verification-result.json"
SUCCESSOR_JSON = RESULT_DIR / "successor-trace.json"
REVIEW_JSON = RESULT_DIR / "independent-review.json"
RECEIPT_JSON = RESULT_DIR / "completion-receipt.json"
FOCUSED_LOG = RESULT_DIR / "focused-android-tests-resumed.log"
FULL_ANDROID_LOG = RESULT_DIR / "full-android-test-build-lint-resumed.log"

GAP_R010_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r010.json"
)
BACKLOG_R010_JSON = (
    ROOT
    / "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r010.json"
)
NPC_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-npc-permission-session-active-ledger-overlay-20260724-r001.json"
)
GAP_R011_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r011.json"
)
GAP_R011_MD = GAP_R011_JSON.with_suffix(".md")
BACKLOG_R011_JSON = (
    ROOT
    / "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r011.json"
)
BACKLOG_R011_MD = BACKLOG_R011_JSON.with_suffix(".md")
FP004_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp004-priority-user-active-ledger-overlay-20260724-r001.json"
)
FP004_OVERLAY_MD = FP004_OVERLAY_JSON.with_suffix(".md")
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"

EXPECTED_PREDECESSOR_SHA256 = {
    GAP_R010_JSON: "917c846a1db5fda6825976b59ee67e37b527e00053fe0a48066491a3a4c4928e",
    BACKLOG_R010_JSON: "61954297c2679a331ee3e546e0be0682b9d80f5e4439216849a64dfee57625da",
    NPC_OVERLAY_JSON: "272bfbee209424f584179fb62e015941d60f8b9d0ad805884e6b700092e53d26",
}
EXPECTED_EXECUTION_EVENT_SEQUENCE = 16
EXPECTED_EXECUTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-2-WORK-SESSION-RESUMED-FP004-20260724-003"
)
EXPECTED_EXECUTION_EVENT_SHA256 = (
    "2e7f543ad0e3cd97cd0ddba82f8b1ebc27f439eb4dd0850e05a3ef605abb5d43"
)
BASE_COMMIT = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
OBSERVED_IMPLEMENTATION_AT = "2026-07-24T16:38:00+09:00"
OBSERVED_VERIFICATION_AT = "2026-07-24T16:38:30+09:00"
OBSERVED_SUCCESSOR_AT = "2026-07-24T16:39:00+09:00"
REVIEWED_AT = "2026-07-24T16:40:00+09:00"
REVIEW_DECIDED_AT = "2026-07-24T16:40:30+09:00"
COMPLETED_AT = "2026-07-24T16:39:30+09:00"
GENERATED_AT = "2026-07-24T16:41:30+09:00"

IMPLEMENTATION_PATHS = (
    "apps/android/README.md",
    "apps/android/USER_GUIDE.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PriorityUserOnboardingPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadiness.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/PriorityUserOnboardingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/PriorityUserOnboardingPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadinessTest.kt",
)
EXPECTED_START_SHA256 = {
    "apps/android/README.md": (
        "0a347b4f47916abea1232b3c41ce64d2cf7e91031591627742ecebc9a5f2bddd"
    ),
    "apps/android/USER_GUIDE.md": (
        "101f5c44c7693852081aa8a7520e88bef8405aab8e6ef184e94d18e13774b368"
    ),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt": (
        "4ff2d1034acc5910b1310dd5947b0676cb2863dc25b90e990c78cf4f816b7b74"
    ),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/"
    "AndroidFeedbackActuator.kt": (
        "79137333d2786d625f32da6f3945dee12f55eb91c6725c3da93b1f8e75b67571"
    ),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadiness.kt": (
        "703e31d8752f57de658eda174245b0550ddad9ba1dc70b3ccc9d0b0580b2b83e"
    ),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadinessTest.kt": (
        "0bcb928ccb7d30d76fd0fddf7b2cec3299068271ed5bece7b5ae23be96a4f1dc"
    ),
}
NEW_IMPLEMENTATION_PATHS = {
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "PriorityUserOnboardingPolicy.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "PriorityUserOnboardingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "PriorityUserOnboardingPolicyTest.kt",
}

OUTPUT_PATHS = (
    IMPLEMENTATION_JSON,
    VERIFICATION_JSON,
    SUCCESSOR_JSON,
    REVIEW_JSON,
    RECEIPT_JSON,
    GAP_R011_JSON,
    GAP_R011_MD,
    BACKLOG_R011_JSON,
    BACKLOG_R011_MD,
    FP004_OVERLAY_JSON,
    FP004_OVERLAY_MD,
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
    require(BUILDER_TEST.is_file(), "builder test is missing")
    checkpoint = base.load_json(CHECKPOINT)
    state = checkpoint["goal_execution"]
    event = latest_execution_event(state)
    require(event is not None, "FP-004 execution-session event is missing")
    require(
        event.get("sequence") == EXPECTED_EXECUTION_EVENT_SEQUENCE,
        "latest FP-004 execution-session sequence differs",
    )
    require(
        event.get("event_id") == EXPECTED_EXECUTION_EVENT_ID,
        "latest FP-004 execution-session event differs",
    )
    require(
        event.get("event_type") == "WORK_SESSION_RESUMED",
        "latest FP-004 execution-session event is not WORK_SESSION_RESUMED",
    )
    require(
        event.get("event_sha256") == EXPECTED_EXECUTION_EVENT_SHA256,
        "latest FP-004 execution-session event hash differs",
    )
    status = state["status_by_goal"].get(GOAL_ID)
    require(
        status in {"IN_PROGRESS", "COMPLETE_AT_TARGET"},
        "FP-004 Goal is neither in progress nor complete",
    )
    if status == "IN_PROGRESS":
        require(state.get("focus_goal_id") == GOAL_ID, "FP-004 is not the focus Goal")
    else:
        completion_role = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
        require(
            completion_role in state["completion_evidence_by_goal"].get(GOAL_ID, []),
            "FP-004 completion evidence is missing",
        )
    require(base.git_value("rev-parse", "HEAD") == BASE_COMMIT, "HEAD differs")
    return (
        base.load_json(GAP_R010_JSON),
        base.load_json(BACKLOG_R010_JSON),
        base.load_json(NPC_OVERLAY_JSON),
    )


def build_implementation(predecessor_gap: dict[str, Any]) -> dict[str, Any]:
    require(
        set(EXPECTED_START_SHA256) | NEW_IMPLEMENTATION_PATHS ==
        set(IMPLEMENTATION_PATHS),
        "implementation start/new path partition differs",
    )
    changed_artifacts = []
    for path_text in IMPLEMENTATION_PATHS:
        path = ROOT / path_text
        after = base.sha256_file(path)
        record: dict[str, Any] = {"path": path_text, "after_sha256": after}
        before = EXPECTED_START_SHA256.get(path_text)
        if before is not None:
            require(before != after, f"implementation path did not change: {path_text}")
            record["before_sha256"] = before
        changed_artifacts.append(record)
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP004-PRIORITY-USER-IMPLEMENTATION-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "IMPLEMENTATION_RECORD",
        "status": "PASS",
        "observed_at": OBSERVED_IMPLEMENTATION_AT,
        "changed_artifacts": changed_artifacts,
        "implemented_controls": [
            "blind and low-vision users have equal support priority",
            "onboarding uses short Korean one-action instructions and explicit accessible controls",
            "first walk stays blocked until education and four directly performed safety practices complete",
            "speech playback completion and vibration request-window completion are both required for each practice",
            "under-14 and unverified minor accounts fail closed",
            "missing Korean speech or vibration blocks start and causes a reasoned safe stop if lost during a walk",
            "onboarding completion is account-scoped while prior walks and routes never auto-resume after restart",
        ],
        "completion_boundary": {
            "formal_test_ids": [f"TC-FP-004-{index:02d}" for index in range(1, 5)],
            "formal_test_status": "NOT_RUN",
            "priority_user_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "guardian_verification_provider_status": "NOT_RUN",
            "release_gate_count": 5,
            "release_gate_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        },
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


def build_verification() -> dict[str, Any]:
    checks = [
        checked_log_record(
            name="FP-004 focused Android onboarding tests after session resume",
            command=(
                "./gradlew --offline --no-daemon :app:testDebugUnitTest "
                "--tests kr.co.hanium.dreamup.walksafe.session."
                "PriorityUserOnboardingPolicyTest "
                "--tests kr.co.hanium.dreamup.walksafe."
                "PriorityUserOnboardingStaticTest "
                "--tests kr.co.hanium.dreamup.walksafe.session."
                "WalkSessionReadinessTest --rerun-tasks"
            ),
            path=FOCUSED_LOG,
            executed_at="2026-07-24T16:35:51+09:00",
            required_task_markers=(":app:testDebugUnitTest",),
        ),
        checked_log_record(
            name="full Android unit/build/lint after session resume",
            command=(
                "./gradlew --offline --no-daemon :app:testDebugUnitTest "
                ":app:assembleDebug :app:lintDebug --rerun-tasks"
            ),
            path=FULL_ANDROID_LOG,
            executed_at="2026-07-24T16:36:29+09:00",
            required_task_markers=(
                ":app:testDebugUnitTest",
                ":app:assembleDebug",
                ":app:lintDebug",
            ),
        ),
    ]
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP004-PRIORITY-USER-VERIFICATION-20260724-001",
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
            "debug_apk": "apps/android/app/build/outputs/apk/debug/app-debug.apk",
            "lint_report": "apps/android/app/build/reports/lint-results-debug.html",
        },
        "evidence_boundary": {
            "repository_internal": True,
            "formal_test_status": "NOT_RUN",
            "priority_user_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "guardian_verification_provider_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
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
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-011",
        "version": "0.11.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = (
        "FP-004 우선 사용자 온보딩과 필수 사전연습의 저장소 내부 구현·회귀로 "
        "GAP-013만 직접 재평가하고 나머지 67개 평가는 r010에서 보존한다."
    )
    report["source_bindings"] = predecessor["source_bindings"] + [
        {**base.file_record(BUILDER), "name": "fp004_priority_user_trace_builder"},
        {**base.file_record(BUILDER_TEST), "name": "fp004_priority_user_builder_test"},
        generated_record(
            IMPLEMENTATION_JSON,
            implementation_text,
            name="fp004_priority_user_implementation",
        ),
        generated_record(
            VERIFICATION_JSON,
            verification_text,
            name="fp004_priority_user_verification",
        ),
    ]
    report["source_binding_sha256"] = base.object_sha256(report["source_bindings"])
    files = [base.file_record(ROOT / path) for path in IMPLEMENTATION_PATHS]
    snapshot = {
        "scope_kind": "EPIC_02_FP004_PRIORITY_USER_EXACT_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": base.git_value("rev-parse", "HEAD"),
        "branch": base.git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "formal priority-user testing",
            "actual-device execution",
            "guardian-verification provider integration",
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
            "GAP-013의 접근 가능한 교육·직접 안전연습·연령·필수 안내 채널 "
            "fail-closed를 내부 구현했지만 사용자·실기기·정식·공급자 검증은 남아 PARTIAL이다."
        ),
    }
    implementation_hash = base.sha256_bytes(implementation_text.encode("utf-8"))
    verification_hash = base.sha256_bytes(verification_text.encode("utf-8"))
    evidence_id = "EVD-FP004-PRIORITY-USER-ONBOARDING-20260724"
    report["evidence_catalog"] = predecessor["evidence_catalog"] + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": "FP-004 저장소 내부 온보딩·필수 사전연습 구현과 Android 회귀 결과",
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
            "priority_user_test_evidence": False,
            "actual_device_evidence": False,
            "provider_evidence": False,
            "release_evidence": False,
        }
    ]
    assessment = next(
        item for item in report["assessments"] if item.get("gap_id") == "GAP-013"
    )
    assessment["status"] = "PARTIAL"
    assessment["current_implementation_in_plain_language"] = (
        "Android는 전맹·저시력 사용자를 같은 우선순위로 두고, 짧은 한국어 행동 지시와 "
        "명시적 접근 가능 버튼으로 최초 교육을 제공한다. 실제 보행은 안전교육, 안전한 "
        "장소 확인, 위험 안내·일시정지·재개·안전정지의 직접 연습을 순서대로 마치고 각 "
        "연습의 음성 재생과 진동 요청 구간이 끝나야 열린다. 만 14세 미만과 보호자 확인이 "
        "없는 미성년 계정, 한국어 음성 또는 진동 불가 환경은 fail-closed한다. 완료 기록은 "
        "계정별로 보존하지만 앱 재시작 뒤 이전 실제 보행과 경로는 자동 재개하지 않는다."
    )
    assessment["rationale"] = (
        "저장소 내부 반대 동작과 Android 회귀는 해소됐지만 TC-FP-004-01~04, 전맹·저시력·"
        "스마트폰 초보 사용자 관찰, 실제 Android 기기와 보호자 본인확인 공급자 시험은 "
        "실행하지 않았다."
    )
    assessment["evidence_ids"] = [evidence_id]
    assessment["remediation"] = (
        "승인된 참여자·실제 기기·보호자 확인 공급자 환경에서 TC-FP-004-01~04와 "
        "접근성 관찰시험을 실행하고 정식 검증 Workstream에 원자료를 연결한다."
    )
    assessment["fp004_reassessment"] = {
        "review_kind": "DIRECT_REASSESSMENT",
        "previous_status": "MISSING",
        "new_status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "priority_user_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "guardian_verification_provider_status": "NOT_RUN",
        "policy_baseline_modified": False,
    }
    base.rehash_assessment(assessment)
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "priority_user_evidence": False,
        "actual_device_evidence": False,
        "provider_evidence": False,
        "source": relative(VERIFICATION_JSON),
        "verification_result_sha256": verification_hash,
        "interpretation": "저장소 내부 회귀이며 정식·사용자·실기기·공급자·출시 증거가 아니다.",
    }
    report["limitations"] = [
        "이번 r011은 GAP-013만 직접 재평가하고 나머지 67개는 r010에서 승계했다.",
        "TC-FP-004-01~04와 우선 사용자·실제 Android 기기 시험은 NOT_RUN이다.",
        "보호자 본인확인 공급자 시험과 5개 Gate는 NOT_RUN이며 출시는 NOT_ELIGIBLE이다.",
    ]
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC02_FP004_GAP013_REASSESSMENT_WITH_R010_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-013"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-013"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            item["gap_id"]
            for item in report["assessments"]
            if item.get("gap_id") != "GAP-013"
        ],
        "carry_forward_warning": "나머지 67개 판정은 r010에서 그대로 보존했다.",
        "next_adjacent_gap": {
            "gap_id": "GAP-014",
            "source_policy_id": "FP-005",
            "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
        },
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": relative(GAP_R010_JSON),
            "file_sha256": EXPECTED_PREDECESSOR_SHA256[GAP_R010_JSON],
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r011": False,
        },
    }
    return base.seal(report, "report_content_sha256")


def build_backlog(predecessor: dict[str, Any], gap: dict[str, Any]) -> dict[str, Any]:
    backlog = deepcopy(predecessor)
    backlog.pop("backlog_content_sha256", None)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-011",
        "version": "0.11.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    for item in backlog["next_action_sequence"]:
        if item.get("source_policy_id") == "FP-004":
            item["status"] = "PARTIAL"
            item["action"] = (
                "FP-004 내부 온보딩·필수 사전연습 구현을 사용자·실기기·정식·공급자 "
                "검증 Workstream에 연결한다."
            )
    epic = next(item for item in backlog["epics"] if item.get("epic_id") == "EPIC-02")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-017·FP-018·NPC·FP-004 내부 구현은 진행됐지만 EPIC-02의 다음 정책과 "
        "정식·사용자·실기기 검증이 남아 있다."
    )
    backlog["source_predecessor"] = {
        "path": relative(BACKLOG_R010_JSON),
        "file_sha256": EXPECTED_PREDECESSOR_SHA256[BACKLOG_R010_JSON],
        "preserved_unchanged": True,
    }
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "work_item_id": "EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK",
        "source_policy_id": "FP-005",
        "gap_id": "GAP-014",
        "status": "PLANNED_NEXT_WITHIN_EPIC",
        "action": (
            "밝고 건조한 일반 도심 보도 범위, 측정 가능한 환경 품질, 확인 불가 조건의 "
            "보수적 제한과 횡단보도 참고 고지를 Android 사전점검·안전정지에 연결한다."
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
        event["event_id"] = f"WS-EPIC02-FP004-PRIORITY-USER-ACTIVE-{index:03d}"
        event["predecessor_event_id"] = previous["event_id"]
        event["predecessor_overlay_id"] = predecessor["metadata"]["overlay_id"]
        event["summary"] = (
            "FP-004 온보딩·필수 사전연습 내부 구현·검증과 GAP-013 PARTIAL 재평가를 "
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
            "overlay_id": "WS-EPIC-02-FP004-PRIORITY-USER-ACTIVE-LEDGER-OVERLAY-20260724-001",
            "version": "0.1.0",
            "as_of": "2026-07-24",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-02 FP-004 우선 사용자 Active successor overlay",
        },
        "authority_boundary": {
            **predecessor["authority_boundary"],
            "formal_test_completion_claimed": False,
            "priority_user_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "guardian_verification_provider_completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            {**base.file_record(NPC_OVERLAY_JSON), "name": "npc_overlay_predecessor"},
            generated_record(
                IMPLEMENTATION_JSON,
                implementation_text,
                name="fp004_priority_user_implementation",
            ),
            generated_record(
                VERIFICATION_JSON,
                verification_text,
                name="fp004_priority_user_verification",
            ),
            generated_record(GAP_R011_JSON, gap_text, name="gap_r011"),
            generated_record(BACKLOG_R011_JSON, backlog_text, name="backlog_r011"),
        ],
        "application_rule": predecessor["application_rule"],
        "events": events,
        "draft_observations": predecessor["draft_observations"],
        "open_evidence_boundaries": {
            **predecessor["open_evidence_boundaries"],
            "fp004_priority_user": "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
            "fp004_priority_user_test_status": "NOT_RUN",
            "actual_device_fp004_status": "NOT_RUN",
            "guardian_verification_provider_status": "NOT_RUN",
            "fp005_official_environment_crosswalk": "PLANNED_NEXT",
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
        "document_id": "WS-FP004-PRIORITY-USER-SUCCESSOR-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "SUCCESSOR_TRACE",
        "status": "PASS",
        "observed_at": OBSERVED_SUCCESSOR_AT,
        "canonical_update_event_type": "CANONICAL_BINDINGS_UPDATED",
        "resulting_canonical_bindings": {
            "IMPLEMENTATION_BACKLOG": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R011_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
            "IMPLEMENTATION_GAP": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R011_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
        },
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-004"],
            "IMPLEMENTATION_GAP": ["FP-004", "GAP-013"],
        },
        "next_policy_gap_pair": {
            "source_policy_id": "FP-005",
            "gap_id": "GAP-014",
        },
    }


def build_review(result_hashes: dict[str, str]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP004-PRIORITY-USER-INTERNAL-REVIEW-20260724-001",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": GOAL_ID,
        "status": "PASS",
        "reviewer_id": "CODEX-FP004-PRIORITY-USER-SEPARATE-REVIEW-20260724-001",
        "reviewed_result_sha256_by_kind": result_hashes,
        "reviewed_at": REVIEWED_AT,
        "findings": {
            "blocking": 0,
            "major_open": 0,
            "resolved_before_acceptance": [
                "education acknowledgement cannot complete practice",
                "speech and vibration delivery are both required",
                "account-scoped profiles cannot leak completion between actors",
                "profile mutation is durably invalidated before a replacement is committed",
                "the hazard practice advances the ordered practice state while lifecycle-changing actions use the production reducer",
                "minor and unsupported-device conditions fail closed",
                "large-text, high-contrast and explicit focus-order source contracts are present",
                "onboarding persistence cannot restore a prior walk",
            ],
            "known_nonblocking_boundaries": [
                "SharedPreferences power-loss and storage-fault injection were not executed on an actual device",
                "actual TalkBack, 200-percent text rendering and physical vibration delivery remain untested",
            ],
        },
        "review_boundary": {
            "separate_internal_review_pass": True,
            "external_independence_claimed": False,
            "formal_tests_remain_not_run": True,
            "priority_user_tests_remain_not_run": True,
            "actual_device_tests_remain_not_run": True,
            "guardian_verification_provider_tests_remain_not_run": True,
            "release_remains_not_eligible": True,
        },
    }


def build_receipt(
    result_hashes: dict[str, str],
    review_text: str,
) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP004-PRIORITY-USER-WORK-ITEM-COMPLETION-20260724-001",
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": base.sha256_file(GOAL_PATH),
        "work_item_id": "EPIC-02-FP004-PRIORITY-USER",
        "source_policy_ids": ["FP-004"],
        "gap_ids": ["GAP-013"],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": EXPECTED_EXECUTION_EVENT_SHA256,
        "execution_session_event": {
            "sequence": EXPECTED_EXECUTION_EVENT_SEQUENCE,
            "event_id": EXPECTED_EXECUTION_EVENT_ID,
            "event_type": "WORK_SESSION_RESUMED",
            "event_sha256": EXPECTED_EXECUTION_EVENT_SHA256,
        },
        "execution_window": {
            "started_at": "2026-07-24T15:46:27+09:00",
            "ended_at": COMPLETED_AT,
        },
        "completed_at": COMPLETED_AT,
        "executor": {
            "id": "CODEX-FP004-PRIORITY-USER-IMPLEMENTER-20260724-001",
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_STANDING_EXECUTION_AUTHORITY",
        },
        "reviewer": {
            "id": "CODEX-FP004-PRIORITY-USER-SEPARATE-REVIEW-20260724-001",
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
        "completion_boundary": {
            "formal_test_status": "NOT_RUN",
            "priority_user_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "guardian_verification_provider_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        },
        "generated_at": GENERATED_AT,
    }


def gap_markdown(value: dict[str, Any]) -> str:
    row = next(item for item in value["assessments"] if item["gap_id"] == "GAP-013")
    return (
        "# WalkSafe 구현 Gap 분석 r011\n\n"
        "- 직접 재평가: `FP-004 / GAP-013`\n"
        f"- 판정: `{row['status']}` (`MISSING → PARTIAL`)\n"
        "- 내부 Android focused·전체 회귀와 debug build·lint: `PASS`\n"
        "- 정식·우선 사용자·실제 기기·보호자 확인 공급자 시험: `NOT_RUN`\n"
        "- 출시: `NOT_ELIGIBLE`\n\n"
        f"{row['rationale']}\n"
    )


def backlog_markdown(value: dict[str, Any]) -> str:
    action = value["next_single_action"]
    return (
        "# WalkSafe 구현 보완 Backlog r011\n\n"
        "- EPIC-02: `IN_PROGRESS`\n"
        f"- 다음 정책·Gap: `{action['source_policy_id']} / {action['gap_id']}`\n"
        f"- 다음 작업: {action['action']}\n"
    )


def overlay_markdown(value: dict[str, Any]) -> str:
    return (
        "# EPIC-02 FP-004 우선 사용자 Active ledger overlay\n\n"
        f"- Overlay: `{value['metadata']['overlay_id']}`\n"
        "- FP-004 내부 구현·검증: `PARTIAL_IMPLEMENTATION_VERIFIED`\n"
        "- 정식·우선 사용자·실제 기기·보호자 확인 공급자 시험: `NOT_RUN`\n"
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
        GAP_R011_JSON: gap_text,
        GAP_R011_MD: gap_markdown(gap),
        BACKLOG_R011_JSON: backlog_text,
        BACKLOG_R011_MD: backlog_markdown(backlog),
        FP004_OVERLAY_JSON: json_text(overlay),
        FP004_OVERLAY_MD: overlay_markdown(overlay),
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
        print(f"FP-004 priority-user trace: FAIL: {exc}", file=sys.stderr)
        return 1
    mode = "WRITE" if args.write else "CHECK"
    print(
        f"FP-004 priority-user trace: PASS outputs={len(outputs)} "
        f"next=FP-005/GAP-014 mode={mode}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
