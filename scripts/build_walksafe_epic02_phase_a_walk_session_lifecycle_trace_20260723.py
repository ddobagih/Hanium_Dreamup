#!/usr/bin/env python3
"""Build the append-only EPIC-02 Phase A FP-017 lifecycle trace.

The builder freezes the Phase G builder, test, and eight generated outputs,
reassesses only GAP-026, advances EPIC-02 to IN_PROGRESS, and emits a focused
implementation record plus an Active-ledger overlay. Repository-internal
verification is never promoted to formal, actual-device, gate, or release
evidence.
"""

from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
TRACE_TEST_PATH = (
    REPO_ROOT
    / "tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py"
)
AUDIT_DIR = REPO_ROOT / "docs/control/audits"
EXECUTION_DIR = REPO_ROOT / "docs/control/execution"

RECORD_JSON = (
    EXECUTION_DIR
    / "walksafe-epic-02-phase-a-walk-session-lifecycle-implementation-record-20260723.json"
)
RECORD_MD = RECORD_JSON.with_suffix(".md")
GAP_R008_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260723-r008.json"
GAP_R008_MD = GAP_R008_JSON.with_suffix(".md")
BACKLOG_R008_JSON = (
    AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260723-r008.json"
)
BACKLOG_R008_MD = BACKLOG_R008_JSON.with_suffix(".md")
ACTIVE_OVERLAY_JSON = (
    EXECUTION_DIR / "walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.json"
)
ACTIVE_OVERLAY_MD = ACTIVE_OVERLAY_JSON.with_suffix(".md")

PHASE_G_BUILDER = (
    REPO_ROOT / "scripts/build_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py"
)
PHASE_G_TEST = (
    REPO_ROOT / "tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py"
)
PHASE_G_RECORD_JSON = (
    EXECUTION_DIR
    / "walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.json"
)
PHASE_G_RECORD_MD = PHASE_G_RECORD_JSON.with_suffix(".md")
GAP_R007_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260723-r007.json"
GAP_R007_MD = GAP_R007_JSON.with_suffix(".md")
BACKLOG_R007_JSON = (
    AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260723-r007.json"
)
BACKLOG_R007_MD = BACKLOG_R007_JSON.with_suffix(".md")
PHASE_G_OVERLAY_JSON = (
    EXECUTION_DIR / "walksafe-epic-01-phase-g-active-ledger-overlay-20260723-r001.json"
)
PHASE_G_OVERLAY_MD = PHASE_G_OVERLAY_JSON.with_suffix(".md")

PREPARED_AT = "2026-07-23T13:34:00+09:00"
BASE_COMMIT = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
EXPECTED_BRANCH = "codex/walksafe-rc2-hardening-20260715"
FORMAL_TEST_COUNT = 279
GATE_IDS = (
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
)
COMPLETED_WORK_ITEM_ID = "EPIC-02-FP017-WALK-SESSION-LIFECYCLE"
NEXT_WORK_ITEM_ID = "EPIC-02-FP018-WALK-STATE-RECOVERY"
NEXT_ACTION = (
    "권한·센서·경로·음성안내·모델을 재검사한 뒤에만 사용자가 보행을 재개하게 하고 "
    "재부팅·강제종료 뒤 이전 보행 자동복원을 금지하는 FP-018 복구 흐름을 완성한다."
)
RESUME_PROMPT = "보행 안내를 다시 시작할까요? 시작 또는 취소라고 말해 주세요"

IMPLEMENTATION_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionLifecycle.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidStepTracker.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidEarthOrientationTracker.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionLifecycleTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/AndroidSensorLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityWalkSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/CameraXFallbackCompositionStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityStartupCapabilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/RuntimeMetricMainActivityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapabilityTest.kt",
)

ACTIVE_ARTIFACT_CODES = (
    "DOC-01",
    "DOC-05",
    "DSC-14",
    "REQ-16",
    "DES-06",
    "DEV-15",
    "SEC-03",
    "TST-19",
    "TST-21",
)
EXPECTED_GAP_STATUS_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 20,
    "EVIDENCE_MISSING": 4,
    "MISSING": 19,
    "PARTIAL": 20,
    "IMPLEMENTED": 0,
}
EXPECTED_EPIC_STATUS_COUNTS = {
    "IMPLEMENTATION_READY": 1,
    "IN_PROGRESS": 1,
    "PLANNED": 10,
}

# Historical SHA-256 values rendered into the canonical Phase A outputs.
IMMUTABLE_SHA256 = {
    PHASE_G_BUILDER: "808242a596bda62ea7567bd88d171eb5dcba2ead1153a427c90bee59aa17f9b0",
    PHASE_G_TEST: "8a176d9b6e51a6f21233595605f4cecd458befe88952f753f357117020e3b3a4",
    PHASE_G_RECORD_JSON: "c1bbb54ffdefe6c5c2f958182b7aa49366def8eb2bdc36dac312d5ae5e2ce4fa",
    PHASE_G_RECORD_MD: "ff3499c38c189a8508c0f908c809da1ea7e85d63ebbb8425ca993d8ae1a53f3e",
    GAP_R007_JSON: "cb9927b1bad6967e8242495fffda46fab6cbcbba87f05064e99689d9e3df0541",
    GAP_R007_MD: "a597c8a61d09caea4a796d141646b6e85c575ce974b25215db40f8cef81652ed",
    BACKLOG_R007_JSON: "dcbe99112bbf09718833998d9c5676a0fabc1826e26e426a3cfc126b5fb21bd0",
    BACKLOG_R007_MD: "ad47e1a7fa2eac8c0e9f31adb043287bfc241f48c028b911118920382d8dbac1",
    PHASE_G_OVERLAY_JSON: "006e1d404200c959444d82acd880e52a876c426d1a51feac780b9dff8757f2f8",
    PHASE_G_OVERLAY_MD: "b29294ea2cf846cb6dbee441986ae7ecaff2ed361a20c8173279e6e85aa989f1",
}

# Phase G's builder and test were repaired after Phase A was generated. Only
# these exact successor-repair values are accepted live; the historical values
# above remain embedded in Phase A's canonical evidence.
LIVE_PREDECESSOR_SHA256 = {
    **IMMUTABLE_SHA256,
    PHASE_G_BUILDER: "fcb50cac043bbbc2f88a64ef6f1e2dcade19731623526f634c2d1f2dccf9e01b",
    PHASE_G_TEST: "b6b80e55cf7d6fdfed50422912796d5b486ff8db794a5e698a575fe84b625aef",
}

HISTORICAL_FILE_BYTES = {
    PHASE_G_BUILDER: 70521,
    PHASE_G_TEST: 19069,
    PHASE_G_RECORD_JSON: 20612,
    PHASE_G_RECORD_MD: 1866,
    GAP_R007_JSON: 424753,
    GAP_R007_MD: 619,
    BACKLOG_R007_JSON: 59465,
    BACKLOG_R007_MD: 703,
    PHASE_G_OVERLAY_JSON: 15595,
    PHASE_G_OVERLAY_MD: 800,
    GENERATOR_PATH: 58296,
    TRACE_TEST_PATH: 19521,
}

HISTORICAL_SELF_SHA256 = {
    GENERATOR_PATH: "9fd64218580d70ef081fb59f03fa574310810ee1c1e322bfc32cbd03bc7562cd",
    TRACE_TEST_PATH: "30eaf84e4eb31485c91c551114bde12f91c61ac6457fe167f73684c436694e58",
}

HISTORICAL_IMPLEMENTATION_FILES = (
    (IMPLEMENTATION_PATHS[0], 337843, "15e896424a3154865a3e6a2f6ad034514089f9d62f7c52da69623aeebab75925"),
    (IMPLEMENTATION_PATHS[1], 7881, "734d08e8467683f4443b394cc7b5cb1f5bdd2a8098996f9991232f5923d928af"),
    (IMPLEMENTATION_PATHS[2], 2924, "299e18925a61e6429e3f152083f6c673981421690da61b0711c55defede4fb0c"),
    (IMPLEMENTATION_PATHS[3], 3044, "c902db98e300fe34defead1df96334062847168b7e3b46d77ccaf99d8ea776d6"),
    (IMPLEMENTATION_PATHS[4], 7469, "5565c6119df0883409359477e29e96ed6a0360bd5f5a4cc37cee104bbcf08c9a"),
    (IMPLEMENTATION_PATHS[5], 12545, "7983b03fcea72027465fbbaa98c35230f5288e03c07f644e07274fb8c9259349"),
    (IMPLEMENTATION_PATHS[6], 1545, "159447ccecf0e1a0a32c6efc31e7a3ced696db458cbd8c17849f81087c355363"),
    (IMPLEMENTATION_PATHS[7], 11056, "22a27ba6d25caadd7c1d5bf539191efaa5ff4aaded37e243aa986dbc10d3c20e"),
    (IMPLEMENTATION_PATHS[8], 18097, "cbb5f5b52046be7d23dbe388d4c6e55ce6507da03522e5ba80d2d9446597c4e7"),
    (IMPLEMENTATION_PATHS[9], 16761, "5b6b622393cd6ed4b3bd555bd3e4d857e83843966166464459c9673d65947873"),
    (IMPLEMENTATION_PATHS[10], 10179, "0fd8ce50fa6dcf69bf3e8af9e2a259d1b99b7defa3ace911257d403fb8dd0244"),
    (IMPLEMENTATION_PATHS[11], 19998, "549edeb99ee572ac59dfdebbdf49ca41eb0494dbcaac4148ebac05954fe9ba59"),
    (IMPLEMENTATION_PATHS[12], 7175, "da7f1c604691ca541c8f7cfd3a679dc866cfafe0bdce5751d111b9e714cecee7"),
)


class TraceBuildError(RuntimeError):
    """Raised when a predecessor, implementation contract, or output differs."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise TraceBuildError(message)


def _relative(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _file_sha256(path: Path) -> str:
    return _sha256_bytes(path.read_bytes())


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _object_sha256(value: Any) -> str:
    return _sha256_bytes(_canonical_bytes(value))


def _strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        _require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _load_json(path: Path) -> dict[str, Any]:
    _require(path.is_file() and not path.is_symlink(), f"required JSON is missing: {_relative(path)}")
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_strict_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                TraceBuildError(f"invalid JSON number: {token}")
            ),
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise TraceBuildError(f"cannot load strict JSON: {_relative(path)}") from exc
    _require(isinstance(value, dict), f"JSON root is not an object: {_relative(path)}")
    return value


def _read_text(path: Path) -> str:
    _require(path.is_file() and not path.is_symlink(), f"required file is missing: {_relative(path)}")
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise TraceBuildError(f"cannot read UTF-8 file: {_relative(path)}") from exc


def _seal(value: dict[str, Any], key: str) -> dict[str, Any]:
    _require(key not in value, f"object already contains seal field: {key}")
    value[key] = _object_sha256(value)
    return value


def _verify_seal(value: dict[str, Any], key: str) -> None:
    payload = dict(value)
    actual = payload.pop(key, None)
    _require(isinstance(actual, str) and len(actual) == 64, f"missing object seal: {key}")
    _require(actual == _object_sha256(payload), f"invalid object seal: {key}")


def _file_binding(name: str, path: Path, *, immutable: bool = False) -> dict[str, Any]:
    _require(path.is_file() and not path.is_symlink(), f"source file is missing: {_relative(path)}")
    result = {
        "name": name,
        "path": _relative(path),
        "bytes": path.stat().st_size,
        "sha256": _file_sha256(path),
    }
    if immutable:
        result["immutability"] = "PREDECESSOR_INPUT_NOT_MODIFIED"
    return result


def _historical_file_binding(
    name: str,
    path: Path,
    *,
    immutable: bool = False,
) -> dict[str, Any]:
    sha256 = IMMUTABLE_SHA256.get(path) or HISTORICAL_SELF_SHA256.get(path)
    _require(sha256 is not None, f"historical SHA-256 is missing: {_relative(path)}")
    _require(path in HISTORICAL_FILE_BYTES, f"historical byte count is missing: {_relative(path)}")
    result = {
        "name": name,
        "path": _relative(path),
        "bytes": HISTORICAL_FILE_BYTES[path],
        "sha256": sha256,
    }
    if immutable:
        result["immutability"] = "PREDECESSOR_INPUT_NOT_MODIFIED"
    return result


def _object_binding(
    name: str,
    path: Path,
    value: dict[str, Any],
    hash_key: str,
) -> dict[str, Any]:
    return {
        "name": name,
        "path": _relative(path),
        "content_sha256": value[hash_key],
        "binding_kind": "CANONICAL_JSON_OBJECT_EXCLUDING_OWN_HASH_FIELD",
    }


def _git_value(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    _require(completed.returncode == 0, f"git {' '.join(args)} failed")
    return completed.stdout.strip()


def _assert_immutable_inputs() -> None:
    for path, expected in LIVE_PREDECESSOR_SHA256.items():
        _require(path.is_file(), f"immutable input is missing: {_relative(path)}")
        _require(
            _file_sha256(path) == expected,
            f"immutable predecessor changed: {_relative(path)}",
        )
    record = _load_json(PHASE_G_RECORD_JSON)
    gap = _load_json(GAP_R007_JSON)
    backlog = _load_json(BACKLOG_R007_JSON)
    overlay = _load_json(PHASE_G_OVERLAY_JSON)
    _verify_seal(record, "record_content_sha256")
    _verify_seal(gap, "report_content_sha256")
    _verify_seal(backlog, "backlog_content_sha256")
    _verify_seal(overlay, "overlay_content_sha256")
    _require(
        record["metadata"]["record_id"]
        == "WS-EPIC-01-PHASE-G-NO-DESTINATION-HAZARD-IMPLEMENTATION-20260723-001",
        "Phase G record differs",
    )
    _require(
        gap["metadata"]["report_id"] == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-007",
        "Gap r007 differs",
    )
    _require(
        backlog["metadata"]["backlog_id"]
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-007",
        "Backlog r007 differs",
    )
    _require(
        backlog["next_single_action"]["work_item_id"] == COMPLETED_WORK_ITEM_ID,
        "Phase G next work item differs",
    )
    _require(
        gap["coverage"]["planned_test_count"] == FORMAL_TEST_COUNT
        and gap["coverage"]["planned_test_not_run_count"] == FORMAL_TEST_COUNT,
        "formal test inventory differs",
    )
    _require(
        not overlay["formal_boundary"]["remaining_gates_waived"],
        "a Phase G gate was waived",
    )


def _implementation_snapshot() -> dict[str, Any]:
    _require(
        len(IMPLEMENTATION_PATHS) == len(set(IMPLEMENTATION_PATHS)),
        "duplicate implementation path",
    )
    entries = []
    historical_by_path = {
        path: (byte_count, sha256)
        for path, byte_count, sha256 in HISTORICAL_IMPLEMENTATION_FILES
    }
    for relative in IMPLEMENTATION_PATHS:
        path = REPO_ROOT / relative
        _require(
            path.is_file() and not path.is_symlink(),
            f"implementation evidence path is missing: {relative}",
        )
        _require(
            relative in historical_by_path,
            f"historical implementation binding is missing: {relative}",
        )
        byte_count, sha256 = historical_by_path[relative]
        entries.append(
            {"path": relative, "bytes": byte_count, "sha256": sha256}
        )
    snapshot = {
        "scope_kind": "EPIC_02_PHASE_A_FP017_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": _git_value("rev-parse", "HEAD"),
        "branch": _git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "checkpoint, runbook, indexes, test-layer registry, and daylog",
            "Phase G immutable predecessors",
            "this builder, its test, and its eight generated outputs",
        ],
        "file_count": len(entries),
        "path_set_sha256": _sha256_bytes(
            ("\n".join(item["path"] for item in entries) + "\n").encode("utf-8")
        ),
        "content_set_sha256": _object_sha256(entries),
        "files": entries,
    }
    _require(snapshot["current_head"] == BASE_COMMIT, "base commit differs")
    _require(snapshot["branch"] == EXPECTED_BRANCH, "branch differs")
    return _seal(snapshot, "snapshot_sha256")


def _source_contract() -> dict[str, Any]:
    main = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[0])
    lifecycle = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[1])
    step_tracker = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[2])
    orientation_tracker = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[3])
    capability = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[4])
    lifecycle_test = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[5])
    sensor_test = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[6])
    lifecycle_composition_test = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[7])
    camera_test = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[8])
    accessibility_test = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[9])
    startup_test = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[10])
    runtime_metric_test = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[11])
    capability_test = _read_text(REPO_ROOT / IMPLEMENTATION_PATHS[12])

    for marker in (
        "READY",
        "ACTIVE",
        "PAUSED",
        "SAFE_STOP",
        "ENDED",
        "RECHECK_REQUIRED",
        "AWAITING_RESUME_CONFIRMATION",
        "InitialCheckCompleted",
        "EnteredBackground",
        "RecoveryRecheckCompleted",
        "ResumeConfirmationReceived",
        "ProcessRestarted",
    ):
        _require(marker in lifecycle, f"lifecycle marker is missing: {marker}")
    _require(
        'normalized == "시작"' in lifecycle and 'normalized == "취소"' in lifecycle,
        "resume parser is not exact",
    )
    for marker in (
        "handleWalkSessionForegroundReturn()",
        "WalkSessionEvent.EnteredBackground",
        "private fun isWalkSessionRuntimeActive()",
        "PREF_WALK_SESSION_INTERRUPTED",
        "VoiceRecognitionPurpose.WALK_SESSION_RESUME",
        RESUME_PROMPT,
    ):
        _require(marker in main, f"MainActivity lifecycle marker is missing: {marker}")
    _require(
        main.count("isWalkSessionRuntimeActive()") >= 12,
        "runtime producer gates are incomplete",
    )
    for marker in (
        "foregroundReturnCannotResumeBeforeRecheckAndExactStartConfirmation",
        "leavingForegroundWhileAwaitingConfirmationRequiresAnotherRecheck",
        "processRestartNeverRestoresPreviouslyActiveSession",
    ):
        _require(marker in lifecycle_test, f"lifecycle regression is missing: {marker}")
    for marker in (
        "foregroundReturnCannotDirectlyRestartRuntimeResources",
        "pauseTransitionsTheSessionBeforeCancellingRuntimeWork",
        "everyRuntimeEntryPointRequiresAnActiveWalkSession",
        "recheckUsesTheApprovedPromptAndOnlyExactStartCanResume",
        "processRestartMarkerPreventsFreshAutomaticRestart",
    ):
        _require(
            marker in lifecycle_composition_test,
            f"lifecycle composition regression is missing: {marker}",
        )
    for marker in (
        "private var activeWindowHardwareBaseline: Float? = null",
        "activeWindowStepOffset = totalActiveSteps",
        "if (!started) return",
    ):
        _require(marker in step_tracker, f"step lifecycle marker is missing: {marker}")
    for marker in (
        "private var started = false",
        "if (!started || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return",
        "started = false\n        sensorManager.unregisterListener(this)",
    ):
        _require(marker in orientation_tracker, f"orientation lifecycle marker is missing: {marker}")
    _require(
        "stoppedTrackersIgnoreAlreadyQueuedSensorCallbacks" in sensor_test,
        "sensor stale-callback regression is missing",
    )
    _require(
        "WalkSafeStartupRequirement.GPS," in capability
        and "val fullOnlyRequirements = setOf(" in capability,
        "GPS full-only capability boundary is missing",
    )
    _require(
        "unavailableGpsFallsBackToCameraOnlyLimitedMode" in capability_test,
        "GPS-to-LIMITED regression is missing",
    )
    for marker, text in (
        ("unknownAvailabilityUsesAsyncRecheckWithLifecycleAndRequestGenerationGuards", camera_test),
        ("asynchronousFeedbackCannotCrossForegroundGeneration", accessibility_test),
        ("runtimeSpeechFailureInvalidatesConfirmationAndStopsWalkingOutputs", startup_test),
        ("sessionLeaseAndMetricStateLockPreventOldFramesFromCrossingGenerations", runtime_metric_test),
    ):
        _require(marker in text, f"async generation regression is missing: {marker}")
    for marker in (
        "private fun isCurrentFrameGeneration(generation: Int)",
        "generation != cameraFallbackGeneration",
        "isFeedbackLifecycleCurrent(feedbackGeneration)",
        "isWalkSessionRuntimeActive()",
    ):
        _require(marker in main, f"MainActivity async/runtime marker is missing: {marker}")

    return {
        "contract_id": "WS-EPIC-02-PHASE-A-FP017-LIFECYCLE-CONTRACT-20260723-001",
        "source_policy_id": "FP-017",
        "scope_assessment": "PARTIAL_IMPLEMENTATION",
        "states": ["READY", "ACTIVE", "PAUSED", "SAFE_STOP", "ENDED"],
        "recovery_stages": ["RECHECK_REQUIRED", "AWAITING_RESUME_CONFIRMATION"],
        "initial_start": {
            "rule": "필수 준비상태를 같은 시점에 확인한 뒤 초기 보행만 자동 시작한다.",
            "implemented_boundary": "현재 Android 준비 확인과 선택 모드 gate에 연결됨",
            "remaining_boundary": (
                "가입·통합동의·운영 인증, 일반 원본수집 동의, 인증된 Gateway 로그인 readiness, "
                "필수 서버, 저장공간·배터리·발열 aggregate와 실제 기기 검증은 후속 EPIC-02 "
                "범위이며 현재 초기 시작 gate가 완결됐다고 주장하지 않는다."
            ),
        },
        "background_pause": {
            "rule": "앱이 보이지 않으면 상태를 먼저 PAUSED로 바꾸고 카메라·음성·길안내·신고 생성을 정지한다.",
            "runtime_gate": "WalkSessionState.ACTIVE && foreground",
        },
        "foreground_recovery": {
            "rule": "과거 준비 결과를 재사용하지 않고 재검사한 뒤 정확한 사용자 확인 전에는 재개하지 않는다.",
            "prompt": RESUME_PROMPT,
            "accepted_exact_words": ["시작", "취소"],
            "no_response_behavior": "PAUSED 유지",
            "unrecognized_behavior": "PAUSED 유지",
        },
        "process_restart": {
            "rule": "중단 표식이 있으면 ACTIVE를 복원하지 않고 PAUSED/RECHECK_REQUIRED에서 시작한다.",
            "automatic_previous_walk_restore": False,
        },
        "voice_channel_separation": {
            "command": "COMMAND",
            "recovery_confirmation": "WALK_SESSION_RESUME",
        },
        "mode_specific_readiness": {
            "full_mode": "GPS·미터 거리·승인 기기 프로필과 전체 기능 권한을 요구한다.",
            "distance_limited_mode": (
                "GPS 또는 미터 거리 기능이 없으면 제한 안내 뒤 물체 종류 경고만 허용하고 "
                "위치·활동인식 권한은 시작 필수조건에서 제외한다."
            ),
            "gps_unavailable_result": "LIMITED",
        },
        "sensor_lifecycle": {
            "stale_callback_behavior": "stop 뒤 이미 대기하던 센서 callback을 무시한다.",
            "step_count_scope": (
                "TYPE_STEP_COUNTER의 하드웨어 누계 기준을 활성 구간마다 다시 잡고 "
                "일시중지 중 걸음은 보행 세션 누계에 포함하지 않는다."
            ),
        },
        "asynchronous_result_boundary": {
            "generation_tracked_jobs": (
                "카메라·detector·ARCore·feedback처럼 generation을 가진 비동기 작업은 "
                "ACTIVE 상태와 해당 작업 generation이 모두 현재일 때만 적용한다."
            ),
            "ordinary_permission_callbacks": (
                "일반 Android permission callback에는 별도 generation이 있다고 주장하지 않는다. "
                "현재 lifecycle·foreground를 다시 확인하고 후속 시작 함수의 ACTIVE gate를 통과할 때만 "
                "runtime으로 이어진다."
            ),
            "late_result_behavior": "무시하고 runtime이나 사용자 출력을 다시 시작하지 않는다.",
        },
        "approved_baseline_files_modified": False,
        "new_product_policy_created": False,
    }


def _predecessor_bindings() -> list[dict[str, Any]]:
    names = {
        PHASE_G_BUILDER: "phase_g_builder_expected_stale",
        PHASE_G_TEST: "phase_g_builder_test",
        PHASE_G_RECORD_JSON: "phase_g_record",
        PHASE_G_RECORD_MD: "phase_g_record_markdown",
        GAP_R007_JSON: "gap_r007",
        GAP_R007_MD: "gap_r007_markdown",
        BACKLOG_R007_JSON: "backlog_r007",
        BACKLOG_R007_MD: "backlog_r007_markdown",
        PHASE_G_OVERLAY_JSON: "phase_g_active_overlay",
        PHASE_G_OVERLAY_MD: "phase_g_active_overlay_markdown",
    }
    bindings = [
        _historical_file_binding(names[path], path, immutable=True)
        for path in IMMUTABLE_SHA256
    ]
    bindings[0]["live_currentness"] = "EXPECTED_STALE_AFTER_SUCCESSOR_IMPLEMENTATION"
    bindings[0]["execution_required"] = False
    return bindings


def _build_record(snapshot: dict[str, Any], contract: dict[str, Any]) -> dict[str, Any]:
    record = {
        "schema_version": "1.0.0",
        "metadata": {
            "record_id": "WS-EPIC-02-PHASE-A-WALK-SESSION-LIFECYCLE-IMPLEMENTATION-20260723-001",
            "version": "0.1.0",
            "as_of": "2026-07-23",
            "status": "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS",
            "title": "EPIC-02 Phase A FP-017 보행 세션 생명주기 구현 기록",
        },
        "authority_boundary": {
            "record_kind": "APPEND_ONLY_IMPLEMENTATION_EVIDENCE",
            "changes_approved_policy": False,
            "changes_phase_g_or_approved_baseline": False,
            "changes_formal_artifact_state": False,
            "claims_epic_in_progress": True,
            "claims_epic_implementation_ready": False,
            "claims_epic_complete": False,
            "claims_actual_device_pass": False,
            "claims_formal_test_pass": False,
            "claims_release_eligible": False,
        },
        "source": {
            "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "phase_g_record_id": (
                "WS-EPIC-01-PHASE-G-NO-DESTINATION-HAZARD-IMPLEMENTATION-20260723-001"
            ),
            "gap_predecessor_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-007",
            "backlog_predecessor_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-007",
            "overlay_predecessor_id": (
                "WS-EPIC-01-PHASE-G-ACTIVE-LEDGER-OVERLAY-20260723-001"
            ),
            "phase_g_live_builder_currentness": "EXPECTED_STALE_AFTER_SUCCESSOR_IMPLEMENTATION",
            "base_commit": BASE_COMMIT,
            "bindings": _predecessor_bindings()
            + [
                _historical_file_binding("trace_builder", GENERATOR_PATH),
                _historical_file_binding("trace_builder_test", TRACE_TEST_PATH),
            ],
        },
        "trace": {
            "epic_id": "EPIC-02",
            "epic_title": "안전한 보행 상태와 권한",
            "epic_status": "IN_PROGRESS",
            "phase": "PHASE_A_FP017_WALK_SESSION_LIFECYCLE",
            "directly_reassessed_policy_ids": ["FP-017"],
            "directly_reassessed_gap_ids": ["GAP-026"],
            "planned_test_ids": [
                "TC-FP-017-01",
                "TC-FP-017-02",
                "TC-FP-017-03",
                "TC-FP-017-04",
                "TC-FP-017-05",
                "TC-FP-017-06",
            ],
            "planned_test_execution_status": "NOT_RUN",
        },
        "implementation_snapshot": snapshot,
        "walk_session_lifecycle_contract": contract,
        "implemented_controls": [
            {
                "control": "EXPLICIT_WALK_SESSION_STATE_MACHINE",
                "result": "READY·ACTIVE·PAUSED·SAFE_STOP·ENDED와 재검사·재개확인 단계를 순수 상태기계로 분리했다.",
                "paths": [
                    IMPLEMENTATION_PATHS[1],
                    IMPLEMENTATION_PATHS[5],
                    IMPLEMENTATION_PATHS[7],
                ],
            },
            {
                "control": "BACKGROUND_FAIL_CLOSED",
                "result": "background 전환 시 PAUSED를 먼저 기록하고 runtime 생산자와 비동기 callback을 ACTIVE gate로 막는다.",
                "paths": [
                    IMPLEMENTATION_PATHS[0],
                    IMPLEMENTATION_PATHS[7],
                    IMPLEMENTATION_PATHS[8],
                    IMPLEMENTATION_PATHS[9],
                    IMPLEMENTATION_PATHS[11],
                ],
            },
            {
                "control": "FRESH_RECHECK_AND_EXACT_CONFIRMATION",
                "result": f"복귀 뒤 재검사와 '{RESUME_PROMPT}'의 정확한 시작 확인 전 자동 재개를 차단한다.",
                "paths": list(IMPLEMENTATION_PATHS),
            },
            {
                "control": "PROCESS_RESTART_NO_AUTO_RESTORE",
                "result": "중단 표식이 있으면 이전 ACTIVE 보행을 복원하지 않고 사용자 시작이 필요한 PAUSED 상태로 연다.",
                "paths": [
                    IMPLEMENTATION_PATHS[0],
                    IMPLEMENTATION_PATHS[1],
                    IMPLEMENTATION_PATHS[5],
                    IMPLEMENTATION_PATHS[7],
                ],
            },
            {
                "control": "MODE_SPECIFIC_CAPABILITY_AND_PERMISSION_GATES",
                "result": "GPS·미터 거리·승인 프로필 부재는 FULL을 막되 LIMITED 물체 종류 경고를 허용하고 모드별 권한을 분리했다.",
                "paths": [
                    IMPLEMENTATION_PATHS[0],
                    IMPLEMENTATION_PATHS[4],
                    IMPLEMENTATION_PATHS[10],
                    IMPLEMENTATION_PATHS[12],
                ],
            },
            {
                "control": "SENSOR_STALE_CALLBACK_AND_ACTIVE_WINDOW_STEPS",
                "result": "stop 뒤 센서 callback을 무시하고 걸음 수 기준을 활성 구간마다 다시 잡아 일시중지 중 걸음을 제외했다.",
                "paths": [
                    IMPLEMENTATION_PATHS[2],
                    IMPLEMENTATION_PATHS[3],
                    IMPLEMENTATION_PATHS[6],
                ],
            },
            {
                "control": "ASYNC_RUNTIME_GENERATION_AND_PERMISSION_LIFECYCLE_GATES",
                "result": (
                    "generation을 가진 카메라·detector·ARCore·feedback 작업은 ACTIVE+현재 "
                    "generation으로 차단하고, 일반 permission callback은 현재 lifecycle·foreground와 "
                    "후속 ACTIVE 시작 gate로 runtime 재시작을 차단했다."
                ),
                "paths": [
                    IMPLEMENTATION_PATHS[0],
                    IMPLEMENTATION_PATHS[8],
                    IMPLEMENTATION_PATHS[9],
                    IMPLEMENTATION_PATHS[10],
                    IMPLEMENTATION_PATHS[11],
                ],
            },
        ],
        "internal_verification": {
            "status": "PASS_RECORDED_FOR_IMPLEMENTATION_SESSION",
            "formal_evidence": False,
            "actual_device_execution": "NOT_RUN",
            "commands": [
                {
                    "scope": "FP-017 상태기계와 MainActivity lifecycle 집중 JVM 회귀",
                    "command": (
                        "./gradlew :app:testDebugUnitTest --tests "
                        "'*WalkSessionLifecycleTest' --tests "
                        "'*MainActivityWalkSessionLifecycleStaticTest' "
                        "--offline --no-daemon --rerun-tasks"
                    ),
                    "status": "PASS_INTERNAL_FOCUSED",
                },
                {
                    "scope": "Android 사용자 앱 전체 JVM·assemble·lint",
                    "command": (
                        "./gradlew :app:testDebugUnitTest :app:assembleDebug "
                        ":app:lintDebug --offline --no-daemon"
                    ),
                    "status": "PASS_INTERNAL_SESSION_RECORDED",
                },
            ],
            "interpretation": (
                "저장소 내부 상태기계·구성·회귀 검증이다. 실제 잠금·홈·통화·앱 전환 기기 시험, "
                "대상 사용자 접근성, 정식 시험 또는 출시 증거가 아니다."
            ),
        },
        "open_issues": [
            {
                "id": "EPIC-02-FP017-ACTUAL-DEVICE-LIFECYCLE",
                "status": "NOT_RUN",
                "description": "실제 Android 기기에서 잠금·홈·통화·앱 전환과 복귀를 실행하지 않았다.",
            },
            {
                "id": "EPIC-02-FP017-FORMAL",
                "status": "NOT_RUN",
                "description": "TC-FP-017-01~06을 포함한 정식 시험은 미실행이다.",
            },
            {
                "id": "EPIC-02-FP017-READINESS-AGGREGATE",
                "status": "OPEN",
                "description": (
                    "가입·통합동의·운영 인증, 일반 원본수집 동의, 인증된 Gateway 로그인 "
                    "readiness, 필수 서버, 저장공간·배터리·발열 aggregate를 하나의 초기 시작 "
                    "판단으로 완결하지 않았다."
                ),
            },
            {
                "id": NEXT_WORK_ITEM_ID,
                "status": "PLANNED_NEXT",
                "description": NEXT_ACTION,
            },
        ],
        "release_boundary": {
            "formal_tests_total": FORMAL_TEST_COUNT,
            "formal_tests_passed": 0,
            "formal_tests_not_run": FORMAL_TEST_COUNT,
            "actual_device_test_status": "NOT_RUN",
            "remaining_gates": list(GATE_IDS),
            "remaining_gate_status": "NOT_RUN",
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "next_single_action": {
            "epic_id": "EPIC-02",
            "work_item_id": NEXT_WORK_ITEM_ID,
            "source_policy_id": "FP-018",
            "gap_id": "GAP-027",
            "status": "PLANNED_NEXT_WITHIN_EPIC",
            "action": NEXT_ACTION,
        },
    }
    return _seal(record, "record_content_sha256")


def _rehash_assessment(assessment: dict[str, Any]) -> None:
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = _object_sha256(assessment)


def _build_gap(record: dict[str, Any], snapshot: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(GAP_R007_JSON)
    report = deepcopy(predecessor)
    report.pop("report_content_sha256", None)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-008",
        "version": "0.8.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-007",
    }
    report["purpose"] = (
        "EPIC-02 Phase A에서 승인된 FP-017 보행 세션 생명주기 핵심 제어의 내부 구현을 근거로 "
        "GAP-026만 직접 재평가하고, r007의 나머지 67개 평가는 변경 없이 보존한다. 이 보고서는 "
        "실제 기기·정식 시험·출시 완료를 주장하지 않는다."
    )
    report["decision_precedence"] = [
        "승인된 WalkSafe 기능 정책 기준선 1.0.1과 FP-017 보행 세션 생명주기",
        "byte 단위 불변 Phase G builder·test·8개 출력과 Gap r007",
        "EPIC-02 Phase A의 13개 통제 경로와 현재 Android 저장소 내부 회귀",
        "실제 기기·정식 시험 279개·5개 gate는 실제 증거 전까지 NOT_RUN이며 출시는 NOT_ELIGIBLE",
    ]
    report["source_bindings"] = predecessor["source_bindings"] + [
        _object_binding("epic02_phase_a_record", RECORD_JSON, record, "record_content_sha256"),
        _historical_file_binding("epic02_phase_a_generator", GENERATOR_PATH),
        _historical_file_binding("epic02_phase_a_generator_test", TRACE_TEST_PATH),
    ]
    report["source_binding_sha256"] = _object_sha256(report["source_bindings"])
    report["implementation_snapshot"] = snapshot
    report["summary"] = {
        **predecessor["summary"],
        "status_counts": EXPECTED_GAP_STATUS_COUNTS,
        "headline": (
            "GAP-026의 보행 상태기계·background 정지·재검사·정확한 재개 확인은 내부 구현됐지만 "
            "가입·동의·인증·Gateway·필수 서버·기기자원 readiness와 실제 기기·정식 시험이 남아 "
            "PARTIAL이다. "
            "279개 정식 시험과 5개 gate는 NOT_RUN이며 출시는 NOT_ELIGIBLE이다."
        ),
    }
    finding = next(item for item in report["critical_findings"] if item["id"] == "CF-04")
    finding["title"] = "보행 복귀 자동 재개는 차단됐으나 전체 가입·동의·인증과 실기기 검증 미완료"
    finding["evidence_ids"] = [
        "EVD-PHASEA-WALK-SESSION-STATE-MACHINE",
        "EVD-PHASEA-WALK-SESSION-COMPOSITION",
    ]
    files = snapshot["files"]
    report["evidence_catalog"] = predecessor["evidence_catalog"] + [
        {
            "evidence_id": "EVD-PHASEA-WALK-SESSION-STATE-MACHINE",
            "kind": "FOCUSED_EPIC02_PHASE_A_PATH_GROUP",
            "claim": "명시적 상태기계가 background 정지, fresh recheck, 정확한 시작 확인, process 재시작 비복원을 표현한다.",
            "formal_test_evidence": False,
            "actual_device_evidence": False,
            "files": [files[index] for index in (0, 1, 5, 7)],
        },
        {
            "evidence_id": "EVD-PHASEA-WALK-SESSION-COMPOSITION",
            "kind": "FOCUSED_EPIC02_PHASE_A_PATH_GROUP",
            "claim": "MainActivity가 ACTIVE/foreground gate로 카메라·음성·길안내·신고 runtime을 통제한다.",
            "formal_test_evidence": False,
            "actual_device_evidence": False,
            "files": [files[index] for index in (0, 7, 8, 9, 10, 11)],
        },
        {
            "evidence_id": "EVD-PHASEA-MODE-SPECIFIC-CAPABILITY",
            "kind": "FOCUSED_EPIC02_PHASE_A_PATH_GROUP",
            "claim": "GPS·미터 거리·승인 프로필 부재는 차단 대신 제한모드로 내리고 모드별 권한을 분리한다.",
            "formal_test_evidence": False,
            "actual_device_evidence": False,
            "files": [files[index] for index in (0, 4, 10, 12)],
        },
        {
            "evidence_id": "EVD-PHASEA-SENSOR-ACTIVE-WINDOW",
            "kind": "FOCUSED_EPIC02_PHASE_A_PATH_GROUP",
            "claim": "정지된 센서의 늦은 callback을 무시하고 걸음 수는 활성 구간만 누적한다.",
            "formal_test_evidence": False,
            "actual_device_evidence": False,
            "files": [files[index] for index in (2, 3, 6)],
        },
        {
            "evidence_id": "EVD-PHASEA-ASYNC-AND-PERMISSION-GATES",
            "kind": "FOCUSED_EPIC02_PHASE_A_PATH_GROUP",
            "claim": (
                "generation 보유 비동기 작업은 ACTIVE+현재 generation을 요구하고 일반 permission "
                "callback은 현재 lifecycle·foreground와 후속 ACTIVE 시작 gate를 요구한다."
            ),
            "formal_test_evidence": False,
            "actual_device_evidence": False,
            "files": [files[index] for index in (0, 8, 9, 10, 11)],
        },
        {
            "evidence_id": "EVD-PHASEA-INTERNAL-VERIFICATION-CONTRACT",
            "kind": "APPEND_ONLY_IMPLEMENTATION_RECORD",
            "path": _relative(RECORD_JSON),
            "record_id": record["metadata"]["record_id"],
            "record_content_sha256": record["record_content_sha256"],
            "claim": "EPIC-02 Phase A 내부 구현 검증과 정식·실기기·출시 미실행 경계를 함께 기록한다.",
            "formal_test_evidence": False,
            "actual_device_evidence": False,
            "release_evidence": False,
        },
    ]
    assessment = next(item for item in report["assessments"] if item["gap_id"] == "GAP-026")
    assessment["status"] = "PARTIAL"
    assessment["current_implementation_in_plain_language"] = (
        "명시적 보행 상태기계와 ACTIVE/foreground runtime gate를 추가했다. 앱이 뒤로 가면 즉시 "
        "PAUSED로 바뀌고 카메라·음성·길안내·신고 runtime이 멈춘다. 복귀 뒤 준비상태를 다시 "
        f"확인하고 '{RESUME_PROMPT}'에 정확히 '시작'이라고 확인하기 전에는 재개하지 않는다. "
        "중단 표식이 있으면 이전 ACTIVE 보행을 자동 복원하지 않는다. GPS·미터 거리 기능이 "
        "없으면 제한모드로 내리고 모드별 권한을 적용하며, 정지 뒤 센서 callback과 비동기 "
        "generation이 지난 카메라·detector·ARCore·feedback 결과는 무시한다. 걸음 수는 "
        "활성 구간만 누적한다."
    )
    assessment["rationale"] = (
        "승인된 FP-017 lifecycle 핵심 제어와 내부 회귀가 구현되었으나 가입·통합동의·운영 인증, "
        "일반 원본수집 동의, 인증된 Gateway 로그인 readiness, 필수 서버, 저장공간·배터리·발열 "
        "aggregate의 완전한 선행조건과 실제 기기 lifecycle·연결 정식 시험은 아직 완료되지 않았다."
    )
    assessment["evidence_ids"] = [
        "EVD-PHASEA-WALK-SESSION-STATE-MACHINE",
        "EVD-PHASEA-WALK-SESSION-COMPOSITION",
        "EVD-PHASEA-MODE-SPECIFIC-CAPABILITY",
        "EVD-PHASEA-SENSOR-ACTIVE-WINDOW",
        "EVD-PHASEA-ASYNC-AND-PERMISSION-GATES",
        "EVD-PHASEA-INTERNAL-VERIFICATION-CONTRACT",
    ]
    assessment["remediation"] = (
        "가입·통합동의·운영 인증, 일반 원본수집 동의, 인증된 Gateway 로그인 readiness, 필수 "
        "서버, 저장공간·배터리·발열 aggregate를 후속 EPIC-02 작업에서 하나의 초기 시작 판단으로 "
        "완결하고 실제 Android 기기의 잠금·홈·통화·앱 전환과 TC-FP-017-01~06을 승인 환경에서 "
        "실행한다."
    )
    assessment["phase_a_reassessment"] = {
        "review_kind": "DIRECT_REASSESSMENT",
        "previous_status": "CONFLICTING",
        "new_status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "policy_baseline_modified": False,
    }
    _rehash_assessment(assessment)
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "actual_device_evidence": False,
        "source": "EPIC-02 Phase A append-only implementation record",
        "record_content_sha256": record["record_content_sha256"],
        "commands": record["internal_verification"]["commands"],
        "interpretation": record["internal_verification"]["interpretation"],
    }
    report["limitations"] = [
        "이번 r008은 GAP-026만 직접 재평가했으며 나머지 67개 판정은 r007에서 그대로 승계했다.",
        (
            "가입·통합동의·운영 인증, 일반 원본수집 동의, 인증된 Gateway 로그인 readiness, "
            "필수 서버, 저장공간·배터리·발열 aggregate의 완전한 초기 시작 판단은 후속 "
            "EPIC-02 작업으로 남아 있다."
        ),
        (
            "일반 permission callback은 현재 lifecycle·foreground와 후속 ACTIVE gate로 "
            "차단하며 별도의 permission generation 구현을 주장하지 않는다."
        ),
        "실제 잠금·홈·통화·앱 전환 Android 기기 실행은 NOT_RUN이다.",
        "정식 시험 279/279와 5개 gate는 NOT_RUN·미면제이며 출시는 NOT_ELIGIBLE이다.",
        "통제 snapshot은 미커밋 작업트리의 13개 FP-017 구현·센서·기기능력·회귀 경로에 한정된다.",
    ]
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC02_PHASE_A_GAP026_REASSESSMENT_WITH_R007_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-026"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-026"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            item["gap_id"] for item in report["assessments"] if item["gap_id"] != "GAP-026"
        ],
        "carry_forward_warning": "나머지 67개는 r007 판정을 그대로 보존했으며 전체 구현 재진단이 아니다.",
        "next_adjacent_gap": {
            "gap_id": "GAP-027",
            "source_policy_id": "FP-018",
            "reason": "보행 상태 복구와 재부팅·강제종료 후 새 보행 흐름은 다음 작업이다.",
        },
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": _relative(GAP_R007_JSON),
            "file_sha256": IMMUTABLE_SHA256[GAP_R007_JSON],
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r008": False,
        },
    }
    return _seal(report, "report_content_sha256")


def _build_backlog(report: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(BACKLOG_R007_JSON)
    backlog = deepcopy(predecessor)
    backlog.pop("backlog_content_sha256", None)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-008",
        "version": "0.8.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "predecessor_backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-007",
    }
    backlog["gap_report_content_sha256"] = report["report_content_sha256"]
    for item in backlog["next_action_sequence"]:
        if item["source_policy_id"] == "FP-017":
            item["status"] = "PARTIAL"
            item["action"] = (
                "FP-017 내부 lifecycle 구현을 후속 가입·동의·인증과 실제 기기·정식 검증에 연결한다."
            )
    epic = next(item for item in backlog["epics"] if item["epic_id"] == "EPIC-02")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "첫 작업 FP-017의 핵심 lifecycle 제어는 내부 구현되어 GAP-026이 PARTIAL로 개선됐지만 "
        "EPIC-02의 나머지 정책과 정식·실기기 검증은 남아 있다."
    )
    backlog["source_predecessor"] = {
        "path": _relative(BACKLOG_R007_JSON),
        "file_sha256": IMMUTABLE_SHA256[BACKLOG_R007_JSON],
        "preserved_unchanged": True,
    }
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "work_item_id": NEXT_WORK_ITEM_ID,
        "source_policy_id": "FP-018",
        "gap_id": "GAP-027",
        "status": "PLANNED_NEXT_WITHIN_EPIC",
        "action": NEXT_ACTION,
    }
    return _seal(backlog, "backlog_content_sha256")


def _build_overlay(
    record: dict[str, Any],
    report: dict[str, Any],
    backlog: dict[str, Any],
) -> dict[str, Any]:
    predecessor = _load_json(PHASE_G_OVERLAY_JSON)
    summary_by_code = {
        "DOC-01": "Phase A 구현기록·r008·backlog r008·overlay의 8개 새 경로와 지문을 다음 Active writer에서 등록한다.",
        "DOC-05": "Phase G와 승인 기준선을 고치지 않고 FP-017 lifecycle 내부 구현 사건을 추가한다.",
        "DSC-14": "EPIC-02를 IN_PROGRESS로 기록하고 FP-018/GAP-027을 다음 행동으로 연결한다.",
        "REQ-16": "FP-017 핵심 lifecycle의 PARTIAL 구현과 남은 가입·동의·인증·정식 검증 경계를 추적한다.",
        "DES-06": "READY·ACTIVE·PAUSED·SAFE_STOP·ENDED 상태기계와 fresh recheck·명시 재개 gate를 설계 사건으로 남긴다.",
        "DEV-15": "13개 통제 경로와 내부 JVM·assemble·lint 검증을 기록하되 정식·실기기 증거가 아님을 남긴다.",
        "SEC-03": "중단 표식과 process restart 비복원, 권한 철회 시 fail-closed 경계를 남긴다.",
        "TST-19": "집중·전체 Android 내부 회귀만 비정식 지표로 기록하고 정식 PASS는 0으로 유지한다.",
        "TST-21": "실기기·279개 정식 시험·5개 gate NOT_RUN과 출시 NOT_ELIGIBLE을 유지한다.",
    }
    previous_events = {item["artifact_code"]: item for item in predecessor["events"]}
    events = []
    evidence_ids = [
        record["metadata"]["record_id"],
        report["metadata"]["report_id"],
        backlog["metadata"]["backlog_id"],
    ]
    for index, code in enumerate(ACTIVE_ARTIFACT_CODES, start=1):
        previous = previous_events[code]
        events.append(
            {
                "event_id": f"WS-EPIC02-PHASEA-ACTIVE-{index:03d}",
                "artifact_code": code,
                "artifact_instance_id": previous["artifact_instance_id"],
                "predecessor_event_id": previous["event_id"],
                "predecessor_overlay_id": predecessor["metadata"]["overlay_id"],
                "predecessor_opening_snapshot_id": previous["predecessor_opening_snapshot_id"],
                "canonical_path": previous["canonical_path"],
                "update_mode": "APPEND_OR_SUCCESSOR_REVISION_ONLY",
                "lifecycle_status_before": "ACTIVE",
                "lifecycle_status_after": "ACTIVE",
                "approval_state_changed": False,
                "summary": summary_by_code[code],
                "evidence_record_ids": evidence_ids,
            }
        )
    overlay = {
        "schema_version": "1.0.0",
        "metadata": {
            "overlay_id": "WS-EPIC-02-PHASE-A-ACTIVE-LEDGER-OVERLAY-20260723-001",
            "version": "0.1.0",
            "as_of": "2026-07-23",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-02 Phase A FP-017 최소 Active 원장 successor overlay",
        },
        "authority_boundary": {
            "phase_g_overlay_preserved": True,
            "events_derived_from_phase_g_predecessor_only": True,
            "canonical_active_files_modified_by_builder": False,
            "approved_baseline_or_predecessor_modified": False,
            "creates_new_artifact_type": False,
            "creates_new_product_policy": False,
            "changes_lifecycle_or_approval_state": False,
            "formal_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "epic_in_progress_claimed": True,
            "epic_implementation_ready_claimed": False,
            "epic_complete_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            _file_binding("phase_g_active_overlay_predecessor", PHASE_G_OVERLAY_JSON, immutable=True),
            _file_binding("gap_r007_predecessor", GAP_R007_JSON, immutable=True),
            _file_binding("backlog_r007_predecessor", BACKLOG_R007_JSON, immutable=True),
            _object_binding("epic02_phase_a_record", RECORD_JSON, record, "record_content_sha256"),
            _object_binding("gap_r008", GAP_R008_JSON, report, "report_content_sha256"),
            _object_binding("backlog_r008", BACKLOG_R008_JSON, backlog, "backlog_content_sha256"),
        ],
        "application_rule": {
            "effective_for_epic02_phase_a_trace": True,
            "canonical_merge_required_for_next_doc01_snapshot": True,
            "merge_method": (
                "다음 범용 Active writer가 Phase G overlay와 이 overlay 지문을 검증한 뒤 "
                "관련 Active 원장의 새 revision에 한 번 반영한다."
            ),
            "duplicate_application_forbidden": True,
            "failure_behavior": (
                "binding이나 predecessor event가 다르면 병합하지 않고 새 overlay revision을 만든다."
            ),
        },
        "events": events,
        "draft_observations": predecessor["draft_observations"],
        "open_evidence_boundaries": {
            **predecessor["open_evidence_boundaries"],
            "fp017_walk_session_lifecycle": "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
            "actual_device_fp017_lifecycle_status": "NOT_RUN",
            "fp018_walk_state_recovery": "PLANNED_NEXT",
        },
        "formal_boundary": {
            "formal_tests_total": FORMAL_TEST_COUNT,
            "formal_tests_passed": 0,
            "formal_tests_not_run": FORMAL_TEST_COUNT,
            "remaining_gate_ids": list(GATE_IDS),
            "remaining_gate_status": "NOT_RUN",
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "next_single_action": {
            "epic_id": "EPIC-02",
            "work_item_id": NEXT_WORK_ITEM_ID,
            "source_policy_id": "FP-018",
            "gap_id": "GAP-027",
            "action": NEXT_ACTION,
        },
    }
    return _seal(overlay, "overlay_content_sha256")


def _validate_outputs(outputs: dict[str, dict[str, Any]]) -> None:
    record = outputs["record"]
    report = outputs["gap"]
    backlog = outputs["backlog"]
    overlay = outputs["overlay"]
    _verify_seal(record, "record_content_sha256")
    _verify_seal(report, "report_content_sha256")
    _verify_seal(backlog, "backlog_content_sha256")
    _verify_seal(overlay, "overlay_content_sha256")
    assessments = {item["gap_id"]: item for item in report["assessments"]}
    _require(len(assessments) == 68, "assessment count differs")
    _require(assessments["GAP-026"]["status"] == "PARTIAL", "GAP-026 is not PARTIAL")
    _require(
        dict(Counter(item["status"] for item in report["assessments"]))
        == {key: value for key, value in EXPECTED_GAP_STATUS_COUNTS.items() if value},
        "gap status counts differ",
    )
    _require(
        report["summary"]["status_counts"] == EXPECTED_GAP_STATUS_COUNTS,
        "gap summary counts differ",
    )
    _require(
        all(item["formal_test_status"] == "NOT_RUN" for item in report["assessments"]),
        "a formal assessment was run",
    )
    epic_counts = dict(Counter(item["current_status"] for item in backlog["epics"]))
    _require(epic_counts == EXPECTED_EPIC_STATUS_COUNTS, "epic status counts differ")
    epic02 = next(item for item in backlog["epics"] if item["epic_id"] == "EPIC-02")
    _require(epic02["current_status"] == "IN_PROGRESS", "EPIC-02 is not IN_PROGRESS")
    _require(
        backlog["next_single_action"]["work_item_id"] == NEXT_WORK_ITEM_ID,
        "next work item differs",
    )
    _require(len(overlay["events"]) == 9, "Active event count differs")
    _require(
        all(
            item["lifecycle_status_before"] == "ACTIVE"
            and item["lifecycle_status_after"] == "ACTIVE"
            and not item["approval_state_changed"]
            for item in overlay["events"]
        ),
        "an Active lifecycle or approval state changed",
    )
    for value in (record, report, backlog, overlay):
        boundary = (
            value.get("release_boundary")
            or value.get("formal_boundary")
            or value.get("authorization_boundary")
        )
        _require(boundary["release_status"] == "NOT_ELIGIBLE", "release status differs")
        _require(not boundary["remaining_gates_waived"], "a release gate was waived")


def build_outputs() -> dict[str, dict[str, Any]]:
    _assert_immutable_inputs()
    snapshot = _implementation_snapshot()
    contract = _source_contract()
    record = _build_record(snapshot, contract)
    report = _build_gap(record, snapshot)
    backlog = _build_backlog(report)
    overlay = _build_overlay(record, report, backlog)
    outputs = {"record": record, "gap": report, "backlog": backlog, "overlay": overlay}
    _validate_outputs(outputs)
    return outputs


def _json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


def _record_markdown(value: dict[str, Any]) -> str:
    snapshot = value["implementation_snapshot"]
    return f"""# EPIC-02 Phase A FP-017 보행 세션 생명주기 구현 기록

- 기록 ID: `{value["metadata"]["record_id"]}`
- 버전: `{value["metadata"]["version"]}`
- EPIC 상태: `IN_PROGRESS`
- GAP-026 판정: `PARTIAL`
- 집중 구현 경로: `{snapshot["file_count"]}`개
- snapshot 지문: `{snapshot["snapshot_sha256"]}`

## 구현 경계

- 초기 준비 확인 뒤 보행 시작
- background 진입 즉시 `PAUSED` 및 runtime 차단
- 복귀 뒤 fresh recheck
- `{RESUME_PROMPT}`의 정확한 `시작` 확인 전 재개 금지
- process 재시작 뒤 이전 `ACTIVE` 보행 자동복원 금지

가입·통합동의·운영 인증, 일반 원본수집 동의, 인증된 Gateway 로그인 readiness,
필수 서버, 저장공간·배터리·발열 aggregate와 실제 기기·정식 시험은 남아 있어
EPIC-02는 `IN_PROGRESS`, GAP-026은 `PARTIAL`이다.

## 출시 경계

- 정식 시험: `0/{FORMAL_TEST_COUNT}`, `{FORMAL_TEST_COUNT}`개 `NOT_RUN`
- 실제 기기: `NOT_RUN`
- 5개 gate: `NOT_RUN`, 미면제
- 출시: `NOT_ELIGIBLE`
- 다음 작업: `{NEXT_WORK_ITEM_ID}` / `FP-018` / `GAP-027`
"""


def _gap_markdown(value: dict[str, Any]) -> str:
    counts = value["summary"]["status_counts"]
    return f"""# WalkSafe 구현 Gap 분석 r008

- 보고서 ID: `{value["metadata"]["report_id"]}`
- 버전: `{value["metadata"]["version"]}`
- 직접 재평가: `GAP-026` (`CONFLICTING` → `PARTIAL`)
- 승계: r007의 나머지 67개
- 집계: BLOCKED {counts["BLOCKED"]}, CONFLICTING {counts["CONFLICTING"]}, EVIDENCE_MISSING {counts["EVIDENCE_MISSING"]}, MISSING {counts["MISSING"]}, PARTIAL {counts["PARTIAL"]}, IMPLEMENTED {counts["IMPLEMENTED"]}
- 정식 시험: `{FORMAL_TEST_COUNT}/{FORMAL_TEST_COUNT} NOT_RUN`
- 실제 기기: `NOT_RUN`
- gate: `5 NOT_RUN`, 미면제
- 출시: `NOT_ELIGIBLE`
"""


def _backlog_markdown(value: dict[str, Any]) -> str:
    counts = Counter(item["current_status"] for item in value["epics"])
    return f"""# WalkSafe 구현 개선 Backlog r008

- Backlog ID: `{value["metadata"]["backlog_id"]}`
- EPIC-01: `IMPLEMENTATION_READY`
- EPIC-02: `IN_PROGRESS`
- 상태 집계: IMPLEMENTATION_READY {counts["IMPLEMENTATION_READY"]}, IN_PROGRESS {counts["IN_PROGRESS"]}, PLANNED {counts["PLANNED"]}
- 다음 작업: `{NEXT_WORK_ITEM_ID}` / `FP-018` / `GAP-027`
- 출시: `NOT_ELIGIBLE`
"""


def _overlay_markdown(value: dict[str, Any]) -> str:
    return f"""# EPIC-02 Phase A Active 원장 Overlay

- Overlay ID: `{value["metadata"]["overlay_id"]}`
- 버전: `{value["metadata"]["version"]}`
- Active 사건: `{len(value["events"])}`개
- 상태 전환: 모두 `ACTIVE → ACTIVE`
- 승인 상태 변경: 없음
- 정식 시험: `0/{FORMAL_TEST_COUNT}`
- 실제 기기: `NOT_RUN`
- gate: `5 NOT_RUN`, 미면제
- 출시: `NOT_ELIGIBLE`
- 다음 작업: `{NEXT_WORK_ITEM_ID}`
"""


def render_outputs(outputs: dict[str, dict[str, Any]]) -> dict[Path, str]:
    return {
        RECORD_JSON: _json_text(outputs["record"]),
        RECORD_MD: _record_markdown(outputs["record"]),
        GAP_R008_JSON: _json_text(outputs["gap"]),
        GAP_R008_MD: _gap_markdown(outputs["gap"]),
        BACKLOG_R008_JSON: _json_text(outputs["backlog"]),
        BACKLOG_R008_MD: _backlog_markdown(outputs["backlog"]),
        ACTIVE_OVERLAY_JSON: _json_text(outputs["overlay"]),
        ACTIVE_OVERLAY_MD: _overlay_markdown(outputs["overlay"]),
    }


def _write_or_check(path: Path, content: str, *, check: bool) -> None:
    if check:
        _require(path.is_file(), f"generated output is missing: {_relative(path)}")
        _require(
            path.read_text(encoding="utf-8") == content,
            f"generated output is stale: {_relative(path)}",
        )
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    try:
        outputs = build_outputs()
        rendered = render_outputs(outputs)
        for path, content in rendered.items():
            _write_or_check(path, content, check=args.check)
    except TraceBuildError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    mode = "current" if args.check else "written"
    print(
        f"EPIC-02 Phase A trace {mode}: "
        f"paths={len(IMPLEMENTATION_PATHS)} outputs={len(rendered)} "
        "GAP-026=PARTIAL EPIC-02=IN_PROGRESS "
        f"formal={FORMAL_TEST_COUNT}/{FORMAL_TEST_COUNT} NOT_RUN "
        f"gates={len(GATE_IDS)} NOT_RUN/unwaived release=NOT_ELIGIBLE "
        f"next={NEXT_WORK_ITEM_ID}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
