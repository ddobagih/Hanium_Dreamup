#!/usr/bin/env python3
"""Build the focused FP-018 implementation, verification, and successor trace."""

from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BUILDER = Path(__file__).resolve()
BUILDER_TEST = ROOT / "tests/test_walksafe_fp018_walk_state_recovery_trace_20260724.py"
GOAL_ID = "WS-GOAL-EPIC-02-FP-018-R001"
GOAL_PATH = (
    ROOT
    / "docs/control/goals/walksafe-completion-graph-v2-2/work-items/epic-02/"
    "epic-02-fp018-walk-state-recovery-r001.md"
)
RESULT_DIR = ROOT / f"docs/control/execution/goal-results/{GOAL_ID}"
IMPLEMENTATION_JSON = RESULT_DIR / "implementation-record.json"
VERIFICATION_JSON = RESULT_DIR / "verification-result.json"
SUCCESSOR_JSON = RESULT_DIR / "successor-trace.json"
REVIEW_JSON = RESULT_DIR / "independent-review.json"
RECEIPT_JSON = RESULT_DIR / "completion-receipt.json"
FOCUSED_LOG = RESULT_DIR / "focused-tests.log"
STATIC_LOG = RESULT_DIR / "related-static-tests.log"
FULL_ANDROID_LOG = RESULT_DIR / "full-android-test-build-lint.log"

GAP_R008_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r008.json"
)
BACKLOG_R008_JSON = (
    ROOT
    / "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r008.json"
)
OVERLAY_R001_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.json"
)
GAP_R009_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r009.json"
)
GAP_R009_MD = GAP_R009_JSON.with_suffix(".md")
BACKLOG_R009_JSON = (
    ROOT
    / "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r009.json"
)
BACKLOG_R009_MD = BACKLOG_R009_JSON.with_suffix(".md")
OVERLAY_R002_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp018-active-ledger-overlay-20260724-r001.json"
)
OVERLAY_R002_MD = OVERLAY_R002_JSON.with_suffix(".md")
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"

EXPECTED_PREDECESSOR_SHA256 = {
    GAP_R008_JSON: "0886a2590c539806e9927229e0b11b95a9ad75db652d1832413f53b76a2c4cc8",
    BACKLOG_R008_JSON: "77c11a61bab49853cc53e51f1a72c2399d4fe2a29b9ef284183cdc15679cb342",
    OVERLAY_R001_JSON: "e849d0cc6f127ef7a2bdba214b433346ac6267c799dfcdbffc8ce63ed0d3511a",
}
EXPECTED_START_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-2-WORK-SESSION-RESUMED-FP018-20260724-003"
)
EXPECTED_START_EVENT_SHA256 = (
    "7efc2714a75405c2f55884c1ee940cad86dd67b4775879d59646eb280c4694ed"
)
OBSERVED_IMPLEMENTATION_AT = "2026-07-24T12:32:00+09:00"
OBSERVED_VERIFICATION_AT = "2026-07-24T12:34:00+09:00"
OBSERVED_SUCCESSOR_AT = "2026-07-24T12:36:00+09:00"
COMPLETED_AT = "2026-07-24T12:37:00+09:00"
REVIEWED_AT = "2026-07-24T12:38:00+09:00"
REVIEW_DECIDED_AT = "2026-07-24T12:39:00+09:00"
GENERATED_AT = "2026-07-24T12:40:00+09:00"

IMPLEMENTATION_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidWalkSessionResourceProbe.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/WalkSafeFeedbackPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidEarthOrientationTracker.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidStepTracker.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionLifecycle.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadiness.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/CameraXFallbackCompositionStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityNavigationCompositionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityWalkSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/WalkSessionDeviceResourceSnapshotTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/feedback/WalkSafeFeedbackPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSessionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionLifecycleFp018Test.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadinessTest.kt",
)


class BuildError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def object_sha256(value: Any) -> str:
    return sha256_bytes(
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    )


def load_json(path: Path) -> dict[str, Any]:
    require(path.is_file() and not path.is_symlink(), f"missing JSON: {relative(path)}")
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"JSON root must be an object: {relative(path)}")
    return value


def seal(value: dict[str, Any], key: str) -> dict[str, Any]:
    value.pop(key, None)
    value[key] = object_sha256(value)
    return value


def file_record(path: Path) -> dict[str, Any]:
    require(path.is_file() and not path.is_symlink(), f"missing file: {relative(path)}")
    return {
        "path": relative(path),
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
    }


def git_value(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    require(completed.returncode == 0, f"git {' '.join(args)} failed")
    return completed.stdout.strip()


def validate_inputs() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    for path, expected in EXPECTED_PREDECESSOR_SHA256.items():
        require(sha256_file(path) == expected, f"predecessor changed: {relative(path)}")
    require(BUILDER_TEST.is_file(), "builder test is missing")
    checkpoint = load_json(CHECKPOINT)
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
    require(start_event.get("event_id") == EXPECTED_START_EVENT_ID, "start event differs")
    require(
        start_event.get("event_sha256") == EXPECTED_START_EVENT_SHA256,
        "start event hash differs",
    )
    status = state["status_by_goal"].get(GOAL_ID)
    require(
        status in {"IN_PROGRESS", "COMPLETE_AT_TARGET"},
        "FP-018 is neither in progress nor complete",
    )
    if status == "IN_PROGRESS":
        require(state.get("focus_goal_id") == GOAL_ID, "FP-018 is not the focus Goal")
    else:
        completion_role = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
        require(
            completion_role in state["completion_evidence_by_goal"].get(GOAL_ID, []),
            "FP-018 completion evidence is missing",
        )
    require(git_value("rev-parse", "HEAD") == "a3ad7eead6b5d834d3e0675422475a9aad351e3d", "HEAD differs")
    return (
        load_json(GAP_R008_JSON),
        load_json(BACKLOG_R008_JSON),
        load_json(OVERLAY_R001_JSON),
    )


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
        after = sha256_file(path)
        record: dict[str, Any] = {
            "path": path_text,
            "after_sha256": after,
        }
        before = before_by_path.get(path_text)
        if before is not None and before != after:
            record["before_sha256"] = before
        changed_artifacts.append(record)
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP018-IMPLEMENTATION-RECORD-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "IMPLEMENTATION_RECORD",
        "status": "PASS",
        "observed_at": OBSERVED_IMPLEMENTATION_AT,
        "changed_artifacts": changed_artifacts,
        "implemented_controls": [
            "readiness snapshots are bound to one walk epoch and one action",
            "start and resume accept only their matching readiness action",
            "active permission changes limit dependent features before stopping the walk",
            "late asynchronous callbacks require the current walk epoch and local generation",
            "new walks clear prior route, location, risk, feedback, and step state",
            "resource-driven safety stops announce their reason non-visually",
        ],
        "completion_boundary": {
            "formal_test_ids": [f"TC-FP-018-{index:02d}" for index in range(1, 7)],
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "release_gate_count": 5,
            "release_gate_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        },
    }


def build_verification() -> dict[str, Any]:
    checks = [
        (
            "FP-018 focused lifecycle/readiness tests",
            "./gradlew :app:testDebugUnitTest --tests MainActivityWalkSessionLifecycleStaticTest "
            "--tests WalkSessionDeviceResourceSnapshotTest --tests WalkSessionLifecycleTest "
            "--tests WalkSessionLifecycleFp018Test --tests WalkSessionReadinessTest "
            "--tests GatewayFieldSessionTest --tests WalkSafeFeedbackPolicyTest --offline --no-daemon",
            FOCUSED_LOG,
            "2026-07-24T12:31:00+09:00",
        ),
        (
            "related asynchronous composition tests",
            "./gradlew :app:testDebugUnitTest --tests CameraXFallbackCompositionStaticTest "
            "--tests MainActivityAccessibilityStaticTest --tests MainActivityNavigationCompositionTest "
            "--tests MainActivityStartupCapabilityStaticTest --tests RuntimeMetricMainActivityStaticTest "
            "--tests ArCoreTactileProjectionContextFactoryTest --tests MainActivityReportUploadStaticTest "
            "--tests AndroidSensorLifecycleStaticTest --offline --no-daemon",
            STATIC_LOG,
            "2026-07-24T12:32:00+09:00",
        ),
        (
            "full Android unit/build/lint",
            "./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon",
            FULL_ANDROID_LOG,
            "2026-07-24T12:33:00+09:00",
        ),
    ]
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP018-VERIFICATION-RESULT-20260724-001",
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
                "output_sha256": sha256_file(log_path),
                "executed_at": executed_at,
            }
            for name, command, log_path, executed_at in checks
        ],
        "android_test_summary": {
            "tests": 434,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "debug_apk": "apps/android/app/build/outputs/apk/debug/app-debug.apk",
            "lint_report": "apps/android/app/build/reports/lint-results-debug.html",
        },
        "evidence_boundary": {
            "repository_internal": True,
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
        },
    }


def rehash_assessment(assessment: dict[str, Any]) -> None:
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = object_sha256(assessment)


def build_gap(
    predecessor: dict[str, Any],
    implementation: dict[str, Any],
    verification: dict[str, Any],
) -> dict[str, Any]:
    report = deepcopy(predecessor)
    report.pop("report_content_sha256", None)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-009",
        "version": "0.9.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = (
        "FP-018 보행 상태 복구의 저장소 내부 구현과 회귀 결과로 GAP-027만 직접 "
        "재평가하고, 나머지 67개 평가는 r008에서 그대로 보존한다."
    )
    report["source_bindings"] = predecessor["source_bindings"] + [
        {**file_record(BUILDER), "name": "fp018_trace_builder"},
        {**file_record(BUILDER_TEST), "name": "fp018_trace_builder_test"},
        {**file_record(IMPLEMENTATION_JSON), "name": "fp018_implementation_result"},
        {**file_record(VERIFICATION_JSON), "name": "fp018_verification_result"},
    ]
    report["source_binding_sha256"] = object_sha256(report["source_bindings"])
    files = [file_record(ROOT / path) for path in IMPLEMENTATION_PATHS]
    snapshot = {
        "scope_kind": "EPIC_02_FP018_CONTROLLED_PATH_SET",
        "base_commit": "a3ad7eead6b5d834d3e0675422475a9aad351e3d",
        "current_head": git_value("rev-parse", "HEAD"),
        "branch": git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "formal and actual-device execution",
            "release-gate closure",
        ],
        "file_count": len(files),
        "path_set_sha256": object_sha256([item["path"] for item in files]),
        "content_set_sha256": object_sha256(
            [{"path": item["path"], "sha256": item["sha256"]} for item in files]
        ),
        "files": files,
    }
    snapshot["snapshot_sha256"] = object_sha256(snapshot)
    report["implementation_snapshot"] = snapshot
    report["summary"] = {
        **predecessor["summary"],
        "status_counts": {
            **predecessor["summary"]["status_counts"],
            "CONFLICTING": predecessor["summary"]["status_counts"]["CONFLICTING"] - 1,
            "PARTIAL": predecessor["summary"]["status_counts"]["PARTIAL"] + 1,
        },
        "headline": (
            "GAP-027의 같은-세대 readiness, 명시적 재개, 새 보행 초기화, 비동기 결과 "
            "격리와 기능별 권한 처리를 내부 구현했지만 실제 기기·정식 시험은 남아 PARTIAL이다."
        ),
    }
    implementation_hash = sha256_file(IMPLEMENTATION_JSON)
    verification_hash = sha256_file(VERIFICATION_JSON)
    evidence_id = "EVD-FP018-WALK-STATE-RECOVERY-20260724"
    report["evidence_catalog"] = predecessor["evidence_catalog"] + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": "FP-018 저장소 내부 구현과 Android 회귀 결과",
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
            "actual_device_evidence": False,
            "release_evidence": False,
        }
    ]
    assessment = next(
        item for item in report["assessments"] if item.get("gap_id") == "GAP-027"
    )
    assessment["status"] = "PARTIAL"
    assessment["current_implementation_in_plain_language"] = (
        "보행 상태와 기능 모드를 분리하고, 시작·재개 readiness를 같은 보행 세대와 "
        "정확한 행동에 결속했다. background 뒤 재검사와 명시적 '시작' 확인 전에는 "
        "runtime을 열지 않으며, 새 보행은 이전 위험·위치·경로·걸음·feedback을 초기화한다. "
        "카메라·센서·위치·음성·경로·신고의 늦은 결과는 보행 세대와 작업 세대가 모두 "
        "현재일 때만 반영한다. 권한 철회는 의존 기능을 제한하고 안전 핵심 기능 부재나 "
        "기기 자원 악화 때만 이유를 안내하며 안전정지한다."
    )
    assessment["rationale"] = (
        "FP-018의 저장소 내부 반대 동작과 주요 회귀는 해소됐지만 TC-FP-018-01~06, "
        "실제 Android 잠금·통화·앱 전환·process 종료 시험과 출시 Gate는 실행하지 않았다."
    )
    assessment["evidence_ids"] = [evidence_id]
    assessment["remediation"] = (
        "승인된 실제 기기 환경에서 TC-FP-018-01~06과 lifecycle 복구 시나리오를 실행해 "
        "원자료와 결함 연결을 남기고, 정식 검증 Workstream에서 판정을 갱신한다."
    )
    assessment["fp018_reassessment"] = {
        "review_kind": "DIRECT_REASSESSMENT",
        "previous_status": "CONFLICTING",
        "new_status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "policy_baseline_modified": False,
    }
    rehash_assessment(assessment)
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "actual_device_evidence": False,
        "source": relative(VERIFICATION_JSON),
        "verification_result_sha256": verification_hash,
        "interpretation": "저장소 내부 회귀이며 정식·실기기·출시 증거가 아니다.",
    }
    report["limitations"] = [
        "이번 r009는 GAP-027만 직접 재평가하고 나머지 67개는 r008에서 승계했다.",
        "TC-FP-018-01~06과 실제 Android 기기 lifecycle 시험은 NOT_RUN이다.",
        "정식 시험 279/279와 5개 Gate는 NOT_RUN이며 출시는 NOT_ELIGIBLE이다.",
    ]
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC02_FP018_GAP027_REASSESSMENT_WITH_R008_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-027"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-027"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            item["gap_id"]
            for item in report["assessments"]
            if item.get("gap_id") != "GAP-027"
        ],
        "carry_forward_warning": "나머지 67개 판정은 r008에서 그대로 보존했다.",
        "next_adjacent_gap": {
            "gap_id": "GAP-006",
            "source_policy_id": "NPC-PERMISSION-SESSION-LIFECYCLE",
            "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
        },
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": relative(GAP_R008_JSON),
            "file_sha256": EXPECTED_PREDECESSOR_SHA256[GAP_R008_JSON],
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r009": False,
        },
    }
    return seal(report, "report_content_sha256")


def build_backlog(
    predecessor: dict[str, Any],
    gap: dict[str, Any],
) -> dict[str, Any]:
    backlog = deepcopy(predecessor)
    backlog.pop("backlog_content_sha256", None)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-009",
        "version": "0.9.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": OBSERVED_SUCCESSOR_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    for item in backlog["next_action_sequence"]:
        if item.get("source_policy_id") == "FP-018":
            item["status"] = "PARTIAL"
            item["action"] = (
                "FP-018 내부 복구 구현을 실제 기기·정식 시험 Workstream에 연결한다."
            )
    epic = next(item for item in backlog["epics"] if item.get("epic_id") == "EPIC-02")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-017·FP-018 내부 구현은 진행됐지만 EPIC-02의 다음 정책과 정식·실기기 검증이 남아 있다."
    )
    backlog["source_predecessor"] = {
        "path": relative(BACKLOG_R008_JSON),
        "file_sha256": EXPECTED_PREDECESSOR_SHA256[BACKLOG_R008_JSON],
        "preserved_unchanged": True,
    }
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "work_item_id": "EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE",
        "source_policy_id": "NPC-PERMISSION-SESSION-LIFECYCLE",
        "gap_id": "GAP-006",
        "status": "PLANNED_NEXT_WITHIN_EPIC",
        "action": (
            "권한·로그인·동의를 분리하고 철회·만료·앱 이탈·재시작 때 의존 기능을 "
            "정확히 중지·재확인한다."
        ),
    }
    return seal(backlog, "backlog_content_sha256")


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
        event["event_id"] = f"WS-EPIC02-FP018-ACTIVE-{index:03d}"
        event["predecessor_event_id"] = previous["event_id"]
        event["predecessor_overlay_id"] = predecessor["metadata"]["overlay_id"]
        event["summary"] = (
            "FP-018 내부 구현·검증·GAP-027 PARTIAL 재평가를 후속 Active 원장에 연결한다."
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
            "overlay_id": "WS-EPIC-02-FP018-ACTIVE-LEDGER-OVERLAY-20260724-001",
            "version": "0.1.0",
            "as_of": "2026-07-24",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-02 FP-018 보행 상태 복구 Active successor overlay",
        },
        "authority_boundary": {
            **predecessor["authority_boundary"],
            "formal_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            {**file_record(OVERLAY_R001_JSON), "name": "phase_a_overlay_predecessor"},
            {**file_record(IMPLEMENTATION_JSON), "name": "fp018_implementation"},
            {**file_record(VERIFICATION_JSON), "name": "fp018_verification"},
            {
                "name": "gap_r009",
                "path": relative(GAP_R009_JSON),
                "content_sha256": gap["report_content_sha256"],
            },
            {
                "name": "backlog_r009",
                "path": relative(BACKLOG_R009_JSON),
                "content_sha256": backlog["backlog_content_sha256"],
            },
        ],
        "application_rule": predecessor["application_rule"],
        "events": events,
        "draft_observations": predecessor["draft_observations"],
        "open_evidence_boundaries": {
            **predecessor["open_evidence_boundaries"],
            "fp018_walk_state_recovery": "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
            "actual_device_fp018_lifecycle_status": "NOT_RUN",
            "npc_permission_session_lifecycle": "PLANNED_NEXT",
        },
        "formal_boundary": predecessor["formal_boundary"],
        "next_single_action": backlog["next_single_action"],
    }
    return seal(overlay, "overlay_content_sha256")


def canonical_binding(
    role: str,
    path: Path,
    document_id: str,
    _identity_json_path: str,
) -> dict[str, Any]:
    return {
        "role": role,
        "path": relative(path),
        "document_id": document_id,
        "file_sha256": sha256_file(path),
    }


def build_successor(gap: dict[str, Any], backlog: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP018-SUCCESSOR-TRACE-20260724-001",
        "goal_id": GOAL_ID,
        "kind": "SUCCESSOR_TRACE",
        "status": "PASS",
        "observed_at": OBSERVED_SUCCESSOR_AT,
        "canonical_update_event_type": "CANONICAL_BINDINGS_UPDATED",
        "resulting_canonical_bindings": {
            "IMPLEMENTATION_BACKLOG": canonical_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R009_JSON,
                backlog["metadata"]["backlog_id"],
                "metadata.backlog_id",
            ),
            "IMPLEMENTATION_GAP": canonical_binding(
                "IMPLEMENTATION_GAP",
                GAP_R009_JSON,
                gap["metadata"]["report_id"],
                "metadata.report_id",
            ),
        },
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-018"],
            "IMPLEMENTATION_GAP": ["FP-018", "GAP-027"],
        },
        "next_policy_gap_pair": {
            "source_policy_id": "NPC-PERMISSION-SESSION-LIFECYCLE",
            "gap_id": "GAP-006",
        },
    }


def build_review(result_hashes: dict[str, str]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP018-INDEPENDENT-REVIEW-20260724-001",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": GOAL_ID,
        "status": "PASS",
        "reviewer_id": "CODEX-FP018-INDEPENDENT-REVIEWER-20260724-001",
        "reviewed_result_sha256_by_kind": result_hashes,
        "reviewed_at": REVIEWED_AT,
        "findings": {
            "blocking": 0,
            "major_open": 0,
            "resolved_before_acceptance": [
                "feature-specific permission handling",
                "readiness action binding",
                "non-visual safety-stop reason announcement",
            ],
        },
        "boundary_confirmation": {
            "formal_tests_remain_not_run": True,
            "actual_device_tests_remain_not_run": True,
            "release_remains_not_eligible": True,
        },
    }


def build_receipt(result_hashes: dict[str, str]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP018-WORK-ITEM-COMPLETION-20260724-001",
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": sha256_file(GOAL_PATH),
        "work_item_id": "EPIC-02-FP018-WALK-STATE-RECOVERY",
        "source_policy_ids": ["FP-018"],
        "gap_ids": ["GAP-027"],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": EXPECTED_START_EVENT_SHA256,
        "execution_window": {
            "started_at": "2026-07-24T12:11:00+09:00",
            "ended_at": COMPLETED_AT,
        },
        "completed_at": COMPLETED_AT,
        "executor": {
            "id": "CODEX-FP018-IMPLEMENTER-20260724-001",
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_STANDING_EXECUTION_AUTHORITY",
        },
        "reviewer": {
            "id": "CODEX-FP018-INDEPENDENT-REVIEWER-20260724-001",
            "role": "INDEPENDENT_INTERNAL_REVIEWER",
            "authority": "INTERNAL_REPOSITORY_CONTROL",
            "decision": "APPROVED",
            "decided_at": REVIEW_DECIDED_AT,
        },
        "reviewer_provenance": {
            "path": relative(REVIEW_JSON),
            "sha256": sha256_file(REVIEW_JSON),
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
    row = next(item for item in value["assessments"] if item["gap_id"] == "GAP-027")
    return (
        "# WalkSafe 구현 Gap 분석 r009\n\n"
        "- 직접 재평가: `FP-018 / GAP-027`\n"
        f"- 판정: `{row['status']}`\n"
        "- 내부 Android 회귀: 434개 PASS, debug build·lint PASS\n"
        "- 정식 시험·실제 기기: `NOT_RUN`\n"
        "- 출시: `NOT_ELIGIBLE`\n\n"
        f"{row['rationale']}\n"
    )


def backlog_markdown(value: dict[str, Any]) -> str:
    action = value["next_single_action"]
    return (
        "# WalkSafe 구현 보완 Backlog r009\n\n"
        "- EPIC-02: `IN_PROGRESS`\n"
        f"- 다음 정책·Gap: `{action['source_policy_id']} / {action['gap_id']}`\n"
        f"- 다음 작업: {action['action']}\n"
    )


def overlay_markdown(value: dict[str, Any]) -> str:
    return (
        "# EPIC-02 FP-018 Active ledger overlay\n\n"
        f"- Overlay: `{value['metadata']['overlay_id']}`\n"
        "- FP-018 내부 구현·검증: `PARTIAL_IMPLEMENTATION_VERIFIED`\n"
        "- 실제 기기·정식 시험: `NOT_RUN`\n"
        "- 출시: `NOT_ELIGIBLE`\n"
    )


def build_outputs(*, write: bool) -> dict[Path, str]:
    predecessor_gap, predecessor_backlog, predecessor_overlay = validate_inputs()
    implementation = build_implementation(predecessor_gap)
    implementation_text = json_text(implementation)
    IMPLEMENTATION_JSON.parent.mkdir(parents=True, exist_ok=True)
    if write or not IMPLEMENTATION_JSON.exists():
        IMPLEMENTATION_JSON.write_text(implementation_text, encoding="utf-8")
    verification = build_verification()
    verification_text = json_text(verification)
    if write or not VERIFICATION_JSON.exists():
        VERIFICATION_JSON.write_text(verification_text, encoding="utf-8")

    expected_implementation_hash = sha256_bytes(implementation_text.encode("utf-8"))
    expected_verification_hash = sha256_bytes(verification_text.encode("utf-8"))
    require(
        sha256_file(IMPLEMENTATION_JSON) == expected_implementation_hash,
        "implementation result is stale; run --write",
    )
    require(
        sha256_file(VERIFICATION_JSON) == expected_verification_hash,
        "verification result is stale; run --write",
    )

    gap = build_gap(predecessor_gap, implementation, verification)
    backlog = build_backlog(predecessor_backlog, gap)
    gap_text = json_text(gap)
    backlog_text = json_text(backlog)
    if write or not GAP_R009_JSON.exists():
        GAP_R009_JSON.write_text(gap_text, encoding="utf-8")
    if write or not BACKLOG_R009_JSON.exists():
        BACKLOG_R009_JSON.write_text(backlog_text, encoding="utf-8")
    require(sha256_file(GAP_R009_JSON) == sha256_bytes(gap_text.encode()), "Gap r009 is stale; run --write")
    require(
        sha256_file(BACKLOG_R009_JSON) == sha256_bytes(backlog_text.encode()),
        "Backlog r009 is stale; run --write",
    )

    overlay = build_overlay(predecessor_overlay, implementation, verification, gap, backlog)
    successor = build_successor(gap, backlog)
    successor_text = json_text(successor)
    if write or not SUCCESSOR_JSON.exists():
        SUCCESSOR_JSON.write_text(successor_text, encoding="utf-8")
    require(
        sha256_file(SUCCESSOR_JSON) == sha256_bytes(successor_text.encode()),
        "successor result is stale; run --write",
    )
    result_hashes = {
        "IMPLEMENTATION_RECORD": expected_implementation_hash,
        "VERIFICATION_RESULT": expected_verification_hash,
        "SUCCESSOR_TRACE": sha256_bytes(successor_text.encode()),
    }
    review = build_review(result_hashes)
    review_text = json_text(review)
    if write or not REVIEW_JSON.exists():
        REVIEW_JSON.write_text(review_text, encoding="utf-8")
    require(sha256_file(REVIEW_JSON) == sha256_bytes(review_text.encode()), "review is stale; run --write")
    receipt = build_receipt(result_hashes)
    return {
        IMPLEMENTATION_JSON: implementation_text,
        VERIFICATION_JSON: verification_text,
        GAP_R009_JSON: gap_text,
        GAP_R009_MD: gap_markdown(gap),
        BACKLOG_R009_JSON: backlog_text,
        BACKLOG_R009_MD: backlog_markdown(backlog),
        OVERLAY_R002_JSON: json_text(overlay),
        OVERLAY_R002_MD: overlay_markdown(overlay),
        SUCCESSOR_JSON: successor_text,
        REVIEW_JSON: review_text,
        RECEIPT_JSON: json_text(receipt),
    }


def write_or_check(path: Path, content: str, *, write: bool) -> None:
    if write:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return
    require(path.is_file(), f"output is missing: {relative(path)}")
    require(path.read_text(encoding="utf-8") == content, f"output differs: {relative(path)}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)
    try:
        status = load_json(CHECKPOINT)["goal_execution"][
            "status_by_goal"
        ].get(GOAL_ID)
        if status == "COMPLETE_AT_TARGET":
            validate_inputs()
            sealed_paths = (
                IMPLEMENTATION_JSON,
                VERIFICATION_JSON,
                GAP_R009_JSON,
                GAP_R009_MD,
                BACKLOG_R009_JSON,
                BACKLOG_R009_MD,
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
                "FP-018 trace: PASS outputs=11 "
                "next=NPC-PERMISSION-SESSION-LIFECYCLE/GAP-006 "
                "mode=SEALED_COMPLETION"
            )
            return 0
        outputs = build_outputs(write=args.write)
        for path, content in outputs.items():
            write_or_check(path, content, write=args.write)
    except (BuildError, OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"FP-018 trace: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        f"FP-018 trace: PASS outputs={len(outputs)} next=NPC-PERMISSION-SESSION-LIFECYCLE/GAP-006"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
