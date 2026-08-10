#!/usr/bin/env python3
"""Validate the immutable baseline and the active WalkSafe handoff checkpoint."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
GATE_REPOSITORY_STATE_SCHEMA_VERSION = "1.0.0"
GATE_REPOSITORY_STATE_EVIDENCE_TYPE = "GATE_REPOSITORY_STATE"
GATE_REPOSITORY_CHECKPOINT_PATH = (
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
GATE_EVENT_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
GIT_STATUS_PORCELAIN_V2_COMMAND = (
    "git",
    "status",
    "--porcelain=v2",
    "-z",
    "--untracked-files=all",
    "--ignore-submodules=none",
)
GIT_STATUS_CONFIG_OVERRIDES = {
    "status.renames": "true",
}
DISALLOWED_GATE_GIT_ENVIRONMENT = {
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_CEILING_DIRECTORIES",
    "GIT_COMMON_DIR",
    "GIT_CONFIG_COUNT",
    "GIT_CONFIG_GLOBAL",
    "GIT_CONFIG_NOSYSTEM",
    "GIT_CONFIG_PARAMETERS",
    "GIT_CONFIG_SYSTEM",
    "GIT_DIR",
    "GIT_DISCOVERY_ACROSS_FILESYSTEM",
    "GIT_INDEX_FILE",
    "GIT_NAMESPACE",
    "GIT_NO_REPLACE_OBJECTS",
    "GIT_OBJECT_DIRECTORY",
    "GIT_PREFIX",
    "GIT_SHALLOW_FILE",
    "GIT_WORK_TREE",
}

EXPECTED_CHECKPOINT_SCHEMA_VERSION = "1.13.0"
EXPECTED_CHECKPOINT_AS_OF = "2026-07-24"
EXPECTED_ACTIVE_GOAL_PACKAGE_ID = (
    "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-2"
)
EXPECTED_ACTIVE_GOAL_SCHEMA_VERSION = "2.0"
EXPECTED_ACTIVE_GOAL_PLAN_VERSION = "2.2.0"
EXPECTED_ACTIVE_GOAL_MASTER_ID = "WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"
EXPECTED_ACTIVE_GOAL_FOCUS_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/work-items/epic-02/"
    "epic-02-fp005-official-environment-crosswalk-r001.md"
)
EXPECTED_ACTIVE_GOAL_MANIFEST_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/"
    "static-plan-manifest-v2.2.0.json"
)
EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256 = (
    "c761ed6d9ac83fb98a2dded6eb99b639610e67a7bf13bdd306f8be38e996577b"
)
EXPECTED_GOAL_INITIAL_EVENT_SHA256 = (
    "90c16c3ac5d6156a0c64874d87ce472c8f47f9da50daa4ce98e6b5e7fa693ab1"
)
EXPECTED_V21_GOAL_PACKAGE_ID = (
    "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-1"
)
EXPECTED_V21_GOAL_PLAN_VERSION = "2.1.0"
EXPECTED_V21_GOAL_MANIFEST_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-1/"
    "static-plan-manifest-v2.1.0.json"
)
EXPECTED_V21_GOAL_MANIFEST_SHA256 = (
    "d8eccbe651c89895b9d1830d7af63b3d18f9bcf23683a5b0f61cc54eb5b01649"
)
EXPECTED_V21_PREPARED_EVENT_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/"
    "superseded-v2.1.0-package-prepared-event.json"
)
EXPECTED_V21_PREPARED_EVENT_SHA256 = (
    "b2a1b08556ee11713854dcafd4df9107860eeab563b1e3cfe1a191384b1bb425"
)
EXPECTED_V21_PACKAGE_MANAGED_PATH_COUNT = 21
EXPECTED_V21_PACKAGE_PATH_SET_SHA256 = (
    "28b034519b85127019410487df6b61cf1ee6010ed626659c4d0a0380d38bd1b3"
)
EXPECTED_V21_PACKAGE_CONTENT_SET_SHA256 = (
    "1462f0366b7fa6ae2a2f9499c3b1d1a4860abf6e17ee5e2e38ff626d5e1ce2d0"
)
EXPECTED_V2_GOAL_PACKAGE_ID = (
    "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2"
)
EXPECTED_V2_GOAL_PLAN_VERSION = "2.0.0"
EXPECTED_V2_GOAL_MANIFEST_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2/"
    "static-plan-manifest-v2.0.0.json"
)
EXPECTED_V2_GOAL_MANIFEST_SHA256 = (
    "ce542aeec040431cdfd473bff54a393a178649b770e0f20e14f3417bfce15847"
)
EXPECTED_V2_PREPARED_EVENT_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-1/"
    "superseded-v2.0.0-package-prepared-event.json"
)
EXPECTED_V2_PREPARED_EVENT_SHA256 = (
    "fd028de110ecb233255ecd535757bf97bc180e0d62bfc8d4bd1446acd9e2dc21"
)
EXPECTED_V1_GOAL_PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-V1"
EXPECTED_V1_GOAL_PLAN_VERSION = "1.1.0"
EXPECTED_V1_GOAL_MANIFEST_PATH = (
    "docs/control/goals/walksafe-completion-v1/static-plan-manifest-v1.1.0.json"
)
EXPECTED_V1_GOAL_MANIFEST_SHA256 = (
    "e8a4d078c5be8429ec363b6135247504eb4411d356c08b645cd9feba534ff43e"
)
EXPECTED_V1_PREPARED_EVENT_SHA256 = (
    "04bd265dbffba57f6ec9103b4bceeee8cc890a341ba71ca56fd501cb93d34438"
)

EXPECTED_VERIFICATION_COMMANDS = {
    "ADMIN_ANDROID_PHASE_B": {
        "status": "PASS",
        "command": "cd apps/android && ./gradlew :adminapp:testDebugUnitTest :adminapp:assembleDebug :adminapp:lintDebug --offline --no-daemon",
        "result": "관리자 앱 JVM 32/32, debug assemble, lint 오류 0; 정식 시험 증거 아님",
    },
    "BACKEND_PHASE_B": {
        "status": "PASS",
        "command": "WALKSAFE_TEST_DATABASE_URL=<isolated-db> python -m pytest backend/tests -q",
        "result": "격리 PostgreSQL 백엔드 내부 회귀 467/467 PASS; 정식 시험 증거 아님",
    },
    "ADMIN_HIGH_RISK_RETENTION": {
        "status": "PASS",
        "command": "python -m pytest tests/test_walksafe_admin_high_risk_data_delete_gate.py tests/test_walksafe_backup_prune.py tests/test_report_retention_scheduler.py tests/test_field_telemetry_retention.py tests/test_release_evidence_gate.py -q",
        "result": "고위험 삭제·보존·증거 gate 내부 회귀 PASS; 운영 자격 증거 아님",
    },
    "PHASE_B_TRACE": {
        "status": "EXPECTED_STALE",
        "command": "python3 -B scripts/build_walksafe_epic01_phase_b_trace_20260722.py --check",
        "result": "Phase C 이후 동일 Android 통제 경로가 변경되어 과거 Phase B live 재생성 검사는 의도적 stale; r001·r002·Phase B 불변 지문은 Phase C successor 검사로 보존",
    },
    "PHASE_C_TRACE": {
        "status": "EXPECTED_STALE",
        "command": "python3 -B scripts/build_walksafe_epic01_phase_c_trace_20260722.py --check && /home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python -m pytest tests/test_walksafe_epic01_phase_c_trace_20260722.py -q",
        "result": "Phase F가 Phase C의 Android 통제 경로를 successor로 변경해 과거 Phase C live 재생성 검사는 의도적 stale; Phase C builder·test·8개 출력의 불변 SHA-256은 후속 successor와 재개 검사기가 보존",
    },
    "LEGACY_WEB_PHASE_D": {
        "status": "EXPECTED_STALE",
        "command": "python3 -I -S -B scripts/check_walksafe_legacy_web_boundary_20260722.py --root . && /home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python -m pytest tests/test_walksafe_legacy_web_boundary_20260722.py tests/test_cloudflare_field_runner.py -q",
        "result": "Phase E에서 Legacy runtime 허용목록을 0개로 만들고 Next route를 제거해 과거 Phase D 4-route 예외 live 검사는 의도적 stale; Phase D 불변 지문과 Phase E successor 경계 검사로 의미를 보존",
    },
    "PHASE_D_TRACE": {
        "status": "EXPECTED_STALE",
        "command": "python3 -B scripts/build_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py --check && /home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python -m pytest tests/test_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py -q",
        "result": "Phase E가 Phase D 통제 경로를 successor로 변경해 과거 Phase D live 재생성 검사는 의도적 stale; Phase D builder·test·8개 출력의 불변 SHA-256은 Phase E successor와 재개 검사기가 보존",
    },
    "ANDROID_GATEWAY_PHASE_E": {
        "status": "PASS",
        "command": "test -n \"${WALKSAFE_NODE_BIN_DIR:-}\" && WALKSAFE_NODE_ROOT=\"$(/usr/bin/dirname -- \"${WALKSAFE_NODE_BIN_DIR}\")\" && test \"${WALKSAFE_NODE_BIN_DIR}\" = \"${WALKSAFE_NODE_ROOT}/bin\" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root \"${WALKSAFE_NODE_ROOT}\" --lock configs/walksafe_node_toolchain_lock_20260715.json >/dev/null && PATH=\"${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin\" \"${WALKSAFE_NODE_BIN_DIR}/npm\" --prefix apps/android-gateway run typecheck && PATH=\"${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin\" \"${WALKSAFE_NODE_BIN_DIR}/npm\" --prefix apps/android-gateway test && PATH=\"${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin\" \"${WALKSAFE_NODE_BIN_DIR}/npm\" --prefix apps/android-gateway run build && python3 -I -S -B scripts/check_walksafe_android_gateway_boundary_20260723.py --root . && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root \"${WALKSAFE_NODE_ROOT}\" --lock configs/walksafe_node_toolchain_lock_20260715.json >/dev/null",
        "result_required_fragments": ("NOT_RUN", "정식 시험 증거 아님"),
    },
    "USER_ANDROID_PHASE_F": {
        "status": "PASS",
        "command": "cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon",
        "result": "사용자 앱 JVM 379/379, debug assemble, lint 오류 0; 실제 기기·279개 정식 시험 증거 아님",
    },
    "LEGACY_WEB_PHASE_E": {
        "status": "PASS",
        "command": "test -n \"${WALKSAFE_NODE_BIN_DIR:-}\" && WALKSAFE_NODE_ROOT=\"$(/usr/bin/dirname -- \"${WALKSAFE_NODE_BIN_DIR}\")\" && test \"${WALKSAFE_NODE_BIN_DIR}\" = \"${WALKSAFE_NODE_ROOT}/bin\" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root \"${WALKSAFE_NODE_ROOT}\" --lock configs/walksafe_node_toolchain_lock_20260715.json >/dev/null && PATH=\"${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin\" \"${WALKSAFE_NODE_BIN_DIR}/npm\" --prefix apps/web test && PATH=\"${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin\" \"${WALKSAFE_NODE_BIN_DIR}/npm\" --prefix apps/web run lint && PATH=\"${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin\" \"${WALKSAFE_NODE_BIN_DIR}/npm\" --prefix apps/web run typecheck && PATH=\"${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin\" \"${WALKSAFE_NODE_BIN_DIR}/npm\" --prefix apps/web run build && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root \"${WALKSAFE_NODE_ROOT}\" --lock configs/walksafe_node_toolchain_lock_20260715.json >/dev/null",
        "result_required_fragments": ("외부 URL", "정식 시험 증거 아님"),
    },
    "PHASE_E_TRACE": {
        "status": "EXPECTED_STALE",
        "command": "python3 -B scripts/build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py --check && <locked-python> -m pytest tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py tests/test_walksafe_android_gateway_boundary_20260723.py -q",
        "result": "Phase F가 Phase E의 Android README·테스트 계층 통제 경로를 successor로 변경해 과거 Phase E live 재생성 검사는 의도적 stale; Phase E builder·test·8개 출력의 불변 SHA-256은 Phase F successor와 재개 검사기가 보존",
    },
    "PHASE_F_TRACE": {
        "status": "EXPECTED_STALE",
        "command": "python3 -B scripts/build_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py --check && <locked-python> -m pytest tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py -q",
        "result": "Phase G가 Phase F의 Android README·사용자 설명·MainActivity 통제 경로를 successor로 변경해 과거 Phase F live 재생성 검사는 의도적 stale; Phase F builder·test·8개 출력의 불변 SHA-256은 Phase G successor와 재개 검사기가 보존",
    },
    "USER_ANDROID_PHASE_G": {
        "status": "PASS",
        "command": "cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon",
        "result_required_fragments": (
            "사용자 앱 JVM",
            "debug assemble",
            "lint 오류 0",
            "실제 기기·279개 정식 시험 증거 아님",
        ),
    },
    "PHASE_G_TRACE": {
        "status": "EXPECTED_STALE",
        "command": "python3 -B scripts/build_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py --check && <locked-python> -m pytest tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py -q",
        "result": "EPIC-02 Phase A가 Phase G의 Android 통제 경로를 successor로 변경해 과거 Phase G live 재생성 검사는 의도적 stale; Phase G builder·test·8개 출력의 불변 SHA-256은 EPIC-02 Phase A successor와 재개 검사기가 보존",
    },
    "USER_ANDROID_EPIC02_PHASE_A": {
        "status": "PASS",
        "command": "cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon",
        "result": "사용자 앱 JVM 407/407, debug assemble, lint 경고 31개·오류 0; 실제 기기·279개 정식 시험 증거 아님",
    },
    "EPIC02_PHASE_A_TRACE": {
        "status": "PASS",
        "command": "python3 -B scripts/build_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py --check && <locked-python> -m pytest tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py -q",
        "result_required_fragments": (
            "GAP-026=PARTIAL",
            "EPIC-02=IN_PROGRESS",
            "formal=279/279 NOT_RUN",
            "NOT_ELIGIBLE",
            "EPIC-02-FP018-WALK-STATE-RECOVERY",
        ),
    },
    "FP018_TRACE": {
        "status": "PASS",
        "command": "python3 -B scripts/build_walksafe_fp018_walk_state_recovery_trace_20260724.py && <locked-python> -m pytest tests/test_walksafe_fp018_walk_state_recovery_trace_20260724.py -q",
        "result_required_fragments": (
            "GAP-027=PARTIAL",
            "Android 434/434",
            "formal=279/279 NOT_RUN",
            "NOT_ELIGIBLE",
            "NPC-PERMISSION-SESSION-LIFECYCLE",
        ),
    },
    "NPC_PERMISSION_SESSION_TRACE": {
        "status": "PASS",
        "command": "python3 -B scripts/build_walksafe_npc_permission_session_trace_20260724.py && <locked-python> -m pytest tests/test_walksafe_npc_permission_session_trace_20260724.py -q",
        "result_required_fragments": (
            "GAP-006=PARTIAL",
            "Android 452/452",
            "Gateway 16/16",
            "boundary 18/18",
            "actual-device=NOT_RUN",
            "formal=279/279 NOT_RUN",
            "external-rights=NOT_RUN",
            "NOT_ELIGIBLE",
            "FP-004/GAP-013",
        ),
    },
    "FP004_TRACE": {
        "status": "PASS",
        "command": "python3 -B scripts/build_walksafe_fp004_priority_user_trace_20260724.py --check && <locked-python> -m pytest tests/test_walksafe_fp004_priority_user_trace_20260724.py -q",
        "result_required_fragments": (
            "GAP-013=PARTIAL",
            "Android 480/480",
            "formal=279/279 NOT_RUN",
            "priority-user=NOT_RUN",
            "actual-device=NOT_RUN",
            "guardian-provider=NOT_RUN",
            "NOT_ELIGIBLE",
            "FP-005/GAP-014",
        ),
    },
    "UNIT_LAYER_PHASE_F": {
        "status": "PASS",
        "command": "WALKSAFE_NODE_BIN_DIR=<attested-node-v22.23.1-bin> WEB_QUALITY_PYTHON=<locked-cpython-3.14.4> PYTHON_BIN=/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python bash scripts/run_walksafe_test_layers_20260711.sh unit",
        "result_required_fragments": ("Python", "Gateway 14/14", "Android 사용자·관리자 JVM", "정식 시험 증거 아님"),
    },
    "ARTIFACT_MATERIALIZATION": {
        "status": "PASS",
        "command": "/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python -B scripts/materialize_walksafe_artifact_baseline_approval_20260722.py --check",
        "result": "102/27/53/75 COMMITTED 상태 유지",
    },
    "TEST_LAYER_INVENTORY": {
        "status": "PASS",
        "command": "PYTHON_BIN=/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python bash scripts/run_walksafe_test_layers_20260711.sh validate",
        "result": "현재 회귀와 역사적 생성기 검사를 중복·누락 없이 분류",
    },
    "DIFF_FORMAT": {
        "status": "PASS",
        "command": "git diff --check",
        "result": "공백 오류 없음",
    },
    "GAP_R001_FROZEN": {
        "status": "EXPECTED_STALE",
        "command": "/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python -B scripts/build_walksafe_implementation_gap_analysis_20260722.py --check",
        "result": "r001 동결 commit 뒤 구현 파일이 의도적으로 변경됨",
    },
    "CONTINUATION_INTEGRITY": {
        "status": "PASS",
        "command": "/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python -m pytest tests/test_walksafe_epic01_phase_b_trace_20260722.py tests/test_walksafe_epic01_phase_c_trace_20260722.py tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py tests/test_walksafe_android_gateway_boundary_20260723.py tests/test_walksafe_npc_permission_session_trace_20260724.py tests/test_walksafe_fp004_priority_user_trace_20260724.py tests/test_walksafe_project_continuation.py tests/test_walksafe_artifact_baseline_materialization_20260722.py --deselect=tests/test_walksafe_epic01_phase_b_trace_20260722.py::WalkSafeEpic01PhaseBTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_c_trace_20260722.py::WalkSafeEpic01PhaseCTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py::WalkSafeEpic01PhaseETraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py::WalkSafeEpic01PhaseFTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py::WalkSafeEpic01PhaseGTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py::WalkSafeEpic02PhaseATraceTest::test_generated_files_are_current_and_deterministic -q",
        "result_required_fragments": (
            "Phase B/C/E/F/G 및 EPIC-02 Phase A live 현재성 6개 EXPECTED_STALE",
            "EPIC-02 Phase A",
            "FP-004",
            "PASS",
        ),
    },
    "GOAL_PACKAGE": {
        "status": "PASS",
        "command": "python3 -B scripts/check_walksafe_goal_graph.py --skip-continuation && /home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python -m pytest tests/test_walksafe_goal_graph.py --deselect=tests/test_walksafe_goal_graph.py::WalkSafeGoalGraphTest::test_canonical_next_action_wins_without_serializing_other_branches --deselect=tests/test_walksafe_goal_graph.py::WalkSafeGoalGraphTest::test_policy_gap_work_item_binds_exactly_one_pair -q",
        "result_required_fragments": (
            "고정 단계 수 없음",
            "의존성 DAG",
            "ready frontier",
            "68개 정책·Gap",
            "보호본 current-state 단언 2개 EXPECTED_STALE",
            "PASS",
        ),
    },
}

EXPECTED_PHASE_D_NEXT_WORK_ITEM_ID = "EPIC-01-NEXT-BFF-EXTRACTION"
EXPECTED_PHASE_D_NEXT_ACTION = (
    "전환형 Next BFF 4개 route를 독립 Android API gateway로 추출하고 "
    "Android endpoint를 전환한다."
)
EXPECTED_PHASE_E_NEXT_WORK_ITEM_ID = "EPIC-01-PURPOSE-SURFACES"
EXPECTED_PHASE_E_NEXT_ACTION = (
    "Android 첫 화면·동의·사용설명·릴리스 설명의 목적문과 안전 한계를 "
    "승인 정책에 맞게 구현·정합화한다."
)
EXPECTED_PHASE_F_NEXT_WORK_ITEM_ID = "EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE"
EXPECTED_PHASE_F_NEXT_ACTION = (
    "목적지를 선택하지 않은 상태에서도 가까운 위험 안내가 작동하도록 "
    "길안내와 위험안내의 경로 의존 조건을 분리하고 검증한다."
)
EXPECTED_PHASE_G_NEXT_WORK_ITEM_ID = "EPIC-02-FP017-WALK-SESSION-LIFECYCLE"
EXPECTED_PHASE_G_NEXT_SOURCE_POLICY_ID = "FP-017"
EXPECTED_PHASE_G_NEXT_GAP_ID = "GAP-026"
EXPECTED_PHASE_G_NEXT_ACTION = (
    "준비→보행 중→일시정지→재검사→사용자 확인 후 재개 상태기계를 만들고 "
    "잠금·홈 버튼·통화·앱 전환 뒤 사용자 확인 전 자동 재개를 막는다."
)
EXPECTED_PHASE_A_NEXT_WORK_ITEM_ID = "EPIC-02-FP018-WALK-STATE-RECOVERY"
EXPECTED_PHASE_A_NEXT_SOURCE_POLICY_ID = "FP-018"
EXPECTED_PHASE_A_NEXT_GAP_ID = "GAP-027"
EXPECTED_PHASE_A_NEXT_ACTION = (
    "권한·센서·경로·음성안내·모델을 재검사한 뒤에만 사용자가 보행을 재개하게 하고 "
    "재부팅·강제종료 뒤 이전 보행 자동복원을 금지하는 FP-018 복구 흐름을 완성한다."
)
EXPECTED_NEXT_WORK_ITEM_ID = "EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK"
EXPECTED_NEXT_SOURCE_POLICY_ID = "FP-005"
EXPECTED_NEXT_GAP_ID = "GAP-014"
EXPECTED_NEXT_ACTION = (
    "밝고 건조한 일반 도심 보도 범위, 측정 가능한 환경 품질, 확인 불가 조건의 "
    "보수적 제한과 횡단보도 참고 고지를 Android 사전점검·안전정지에 연결한다."
)
EXPECTED_LAST_COMPLETED_WORK_SUMMARY = (
    "EPIC-01 Phase A 제품 경계·Phase B 관리자 인증·복구·"
    "Phase C runtime metric preflight·Phase D Legacy Web 저장소 경로 기술 폐쇄·"
    "Phase E Android Gateway 추출·Phase F 제품 목적·안전 한계 사용자 표면 정합화·"
    "Phase G 목적지 없는 위험안내 경로 의존 분리·"
    "EPIC-02 Phase A FP-017 보행 세션 생명주기·"
    "FP-018 보행 상태 복구·NPC 권한 세션 생명주기·"
    "FP-004 우선 사용자와 필수 사전연습의 내부 검증"
)
EXPECTED_CURRENT_FOCUS = (
    "구현 백로그 EPIC-02 상태 IN_PROGRESS·graph Workstream WS-GOAL-EPIC-02 상태 READY — "
    "FP-004 우선 사용자와 필수 사전연습 내부 검증 완료, "
    "다음 정책·Gap FP-005/GAP-014 준비"
)

EXPECTED_R011_STATUS_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 18,
    "EVIDENCE_MISSING": 4,
    "MISSING": 18,
    "PARTIAL": 23,
    "IMPLEMENTED": 0,
}
EXPECTED_CHECKPOINT_STATUS_COUNTS = dict(EXPECTED_R011_STATUS_COUNTS)
EXPECTED_EPIC_STATUS_COUNTS = {
    "IMPLEMENTATION_READY": 1,
    "IN_PROGRESS": 1,
    "PLANNED": 10,
}
EXPECTED_ARTIFACT_STATE_COUNTS = {
    "APPROVED_BASELINED": 102,
    "ACTIVE": 27,
    "DRAFT": 53,
    "PLANNED_NOT_RUN": 75,
}

EXPECTED_IMMUTABLE_PREDECESSOR_SHA256 = {
    "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r001.json":
        "e559a9381ce13462e8d377befe5005a9d5250a0cdd33ada8a98a933bad50e8fe",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r002.json":
        "94cfcfe3bcc56daeb7ab88cbf0840a7f38b9773aee1c9b4e28a24de3c544d3e5",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r001.json":
        "b0ad9cc6008e15f407cfe0e05620364564834d03794f200f1119b610fb66621f",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r002.json":
        "0d2eeafcf094c0c4426e830e548c450df6c1cd2e5100bb36107bcdd9fc59d351",
    "docs/control/execution/walksafe-epic-01-phase-b-admin-auth-recovery-implementation-record-20260722.json":
        "522474786918c5491775c28dfea772d1f786d35412047bf775170e3a4a70ea83",
    "docs/control/execution/walksafe-epic-01-phase-b-active-ledger-overlay-20260722-r001.json":
        "f38c93b3d194b12c5c348db25147ec7b96d40216df98ab197572151eb1b3ab2a",
    "docs/control/execution/walksafe-single-admin-recovery-drill-protocol-20260722-r001.json":
        "b9b92c584d190964b4c878ad3adc6c677c660dfbe151cbbd2c63ce19b2a8cca7",
    "scripts/build_walksafe_epic01_phase_b_trace_20260722.py":
        "a9874f7c64d6f4250c6146aa9bd4024a34e456bb6bb28b1ba8e5351e0e883991",
    "tests/test_walksafe_epic01_phase_b_trace_20260722.py":
        "5e594cc4c752c24473cfea167f266ec714bb6470e8b5ce087f8d1712d0e3a5f4",
    "scripts/build_walksafe_epic01_phase_c_trace_20260722.py":
        "a759eb97813f6335b6b9431b3ffe384cb165ef69cb5b1fac878b04d4f24d039e",
    "tests/test_walksafe_epic01_phase_c_trace_20260722.py":
        "20cb319c3b3ebf3ab8fd1003494f64b0cc10d4ddf141b3d60ee3b97d9893339d",
    "docs/control/execution/walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.json":
        "2f38315c2856028d56f29d824d0fcb232a6f0c94b2cd2f801482468befe9ad14",
    "docs/control/execution/walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.md":
        "222c8be5b553cec5f8556582fcaa5c0c46c3b23d32994e583598f1225ff0cf09",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r003.json":
        "4b407eeb28b163abc9dd85d04ce98568b066d899eb8fd4cb62c4f11fe6990b0c",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r003.md":
        "089bc5f90ebb7225eeabab3d5e511c1c287e496d049a91175c85caa591aa2f1b",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r003.json":
        "dd2382d81b05d729bd46dac7e80c682c6f40f01ead4fb6d0ee03815a6a6725bf",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r003.md":
        "16f01825a9ad215fafc5e8b36b2d35c4f02506b8a2b8339a8dd2f33db3c69c0d",
    "docs/control/execution/walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.json":
        "bc03cbe29556d860511e4f1a53e91c5c4096e0a2d84ffd60cf5d8580dfcb808e",
    "docs/control/execution/walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.md":
        "b600ef464946e6a5593e31cd23359d0ca889a1afc5b8b085a07f1647ebfb8301",
    "scripts/build_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py":
        "10c18b5487f3d2046e72eab1d6004ea0ef372e5af055c099f20e8c65473ccfbe",
    "tests/test_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py":
        "6a00340373022311dda91544e85212161950c06a2677eecc4e1d1ff1b8abccaf",
    "docs/control/execution/walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.json":
        "f3352011c4ffd796ba7f9207e465396a51bd4e8941807229548e185dd1160481",
    "docs/control/execution/walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.md":
        "b382c52e47ee66e73f5488c69db36cade7e8bf045ed349d25e390ac47fe7191e",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r004.json":
        "24829b3e297103bf3b3453ae5a05b0ff669cd63d9ab36a4047720340813f1aa9",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r004.md":
        "f5c84241d2535b92a9d468514b6c18f5686643c842edf7f8d2bf120c8ce9e0e0",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r004.json":
        "af045e68a86ccc7a6be3f6d01dd0b57a460a028c6a1803346d339329b40db3b0",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r004.md":
        "4765943051e65141e68b0f51408db48780717fd60da3aaac0fee2689e99ef2b5",
    "docs/control/execution/walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.json":
        "a158c3cce36e10141b17fbab91a043f8a0c3ce405df0be620c9d03a86253f5bc",
    "docs/control/execution/walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.md":
        "bbe1d34e080dc029e79d69ab7309bd38bef3fa654ffe1dd91ba8900f1b05ae26",
    "scripts/build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py":
        "4c55aadbdf7ec0dddf00d0c5363381b84366cf26b00213e6e58a408e99751e5a",
    "tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py":
        "36383044a552f627359c549a9426521e66416847751e330bef9d02d19e6fac94",
    "docs/control/execution/walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.json":
        "6deafda42451e5f03c1422bb5bcd02994890a9e05e107eef2f846b4571a72f23",
    "docs/control/execution/walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.md":
        "63d57140c43b428f7cfaa5dcb1b32abbb3cbce0afcbfce4615f273b729f809d7",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r005.json":
        "adf2a32c4e6ab04f3d6b7c97346956aa434aa281ad80de6607293bd00382e571",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r005.md":
        "dc4cab8490a5f4f13052cd1361785069088e19f8b0e7537a85f0c5786d7cfb7c",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r005.json":
        "d0479054d9a0a374ebe34dc0726f791c4e81095cfd3d4ca834bd1340be60d298",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r005.md":
        "750d93ea39bcee00fe5f05276a4a03689e64a7180e4fe27c5b6ff11083610d04",
    "docs/control/execution/walksafe-epic-01-phase-e-active-ledger-overlay-20260723-r001.json":
        "30e8275dd29302805b61810633a09276a3199a2328c63feefd9968a57f109ff1",
    "docs/control/execution/walksafe-epic-01-phase-e-active-ledger-overlay-20260723-r001.md":
        "e948928715984d375adeb7e27fc34abaa10655e2ab43206e05176e0ca0ebb2c7",
    "scripts/build_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py":
        "e961bcbc80bfb9ba510b8f54529ec88a207cf42b97d9059d18d4b587919c78ec",
    "tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py":
        "537d5f0353e894222bc9ffd74614dfb86d221be9982e17f4e7aa7b796b5844e6",
    "docs/control/execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.json":
        "5c9705238944fb53a4b9961505f9284062d6e60f50a91efa5f4c655730c7af75",
    "docs/control/execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.md":
        "8b7c536d2918b7397517c175bfca51bc39187d45493e85f7b0438e6f494afa60",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r006.json":
        "2ccea73214ad12b7484eab353a8c6fb13cdd4e222676d318572891fbc612096c",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r006.md":
        "ad374de629ba243b216937b451fdeca3816eb3dad83155618adeaf1584936916",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r006.json":
        "723e20fccc03eb93c863f6f63b9a030f6825020e79c10a6c6c2fa2b154f57c06",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r006.md":
        "db516329a1ba1ac1692dcc6a552d5b07559c58feb203742107f4a966b46c9485",
    "docs/control/execution/walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.json":
        "cf264ab050e86b9668005b13bf7e6bd640f30d859c4c771916f75d0e439495ae",
    "docs/control/execution/walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.md":
        "360b431f04312edb9e57d3130e52e9b6dfa79e578e32e1de1816dc6a9f52d436",
    "scripts/build_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py":
        "808242a596bda62ea7567bd88d171eb5dcba2ead1153a427c90bee59aa17f9b0",
    "tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py":
        "8a176d9b6e51a6f21233595605f4cecd458befe88952f753f357117020e3b3a4",
    "docs/control/execution/walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.json":
        "c1bbb54ffdefe6c5c2f958182b7aa49366def8eb2bdc36dac312d5ae5e2ce4fa",
    "docs/control/execution/walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.md":
        "ff3499c38c189a8508c0f908c809da1ea7e85d63ebbb8425ca993d8ae1a53f3e",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r007.json":
        "cb9927b1bad6967e8242495fffda46fab6cbcbba87f05064e99689d9e3df0541",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r007.md":
        "a597c8a61d09caea4a796d141646b6e85c575ce974b25215db40f8cef81652ed",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r007.json":
        "dcbe99112bbf09718833998d9c5676a0fabc1826e26e426a3cfc126b5fb21bd0",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r007.md":
        "ad47e1a7fa2eac8c0e9f31adb043287bfc241f48c028b911118920382d8dbac1",
    "docs/control/execution/walksafe-epic-01-phase-g-active-ledger-overlay-20260723-r001.json":
        "006e1d404200c959444d82acd880e52a876c426d1a51feac780b9dff8757f2f8",
    "docs/control/execution/walksafe-epic-01-phase-g-active-ledger-overlay-20260723-r001.md":
        "b29294ea2cf846cb6dbee441986ae7ecaff2ed361a20c8173279e6e85aa989f1",
    "scripts/build_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py":
        "9fd64218580d70ef081fb59f03fa574310810ee1c1e322bfc32cbd03bc7562cd",
    "tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py":
        "30eaf84e4eb31485c91c551114bde12f91c61ac6457fe167f73684c436694e58",
    "docs/control/execution/walksafe-epic-02-phase-a-walk-session-lifecycle-implementation-record-20260723.json":
        "88f326ee7bd80606174476c57dddbccd219b65ad3c8b9a87ac58893d1e7ffd31",
    "docs/control/execution/walksafe-epic-02-phase-a-walk-session-lifecycle-implementation-record-20260723.md":
        "e3bc53872ee78bd078a021404c02bbb66971593837048c4899ad0f3e7a37fdc8",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r008.json":
        "0886a2590c539806e9927229e0b11b95a9ad75db652d1832413f53b76a2c4cc8",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r008.md":
        "9e19e8d6253c876de83a214d54960e0bdec2c6d9f5d95123f0659fefd53e6bfa",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r008.json":
        "77c11a61bab49853cc53e51f1a72c2399d4fe2a29b9ef284183cdc15679cb342",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r008.md":
        "82929d60f50cff180633aa4595fc312f363964818e078607440cef3770240b16",
    "docs/control/execution/walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.json":
        "e849d0cc6f127ef7a2bdba214b433346ac6267c799dfcdbffc8ce63ed0d3511a",
    "docs/control/execution/walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.md":
        "820e5a605a7199797ab478fcbcabf3749844bce42ac8af14800336da086cf02e",
}

EXPECTED_SUCCESSOR_CANONICAL_BINDINGS = {
    "EPIC01_PHASE_B_RECORD": {
        "path": "docs/control/execution/walksafe-epic-01-phase-b-admin-auth-recovery-implementation-record-20260722.json",
        "document_id": "WS-EPIC-01-PHASE-B-ADMIN-AUTH-RECOVERY-IMPLEMENTATION-20260722-001",
        "identity_json_path": "metadata.record_id",
        "mutable": False,
    },
    "SINGLE_ADMIN_RECOVERY_DRILL_PROTOCOL": {
        "path": "docs/control/execution/walksafe-single-admin-recovery-drill-protocol-20260722-r001.json",
        "document_id": "WS-SINGLE-ADMIN-RECOVERY-DRILL-PROTOCOL-20260722-001",
        "identity_json_path": "metadata.protocol_id",
        "mutable": False,
    },
    "EPIC01_PHASE_B_ACTIVE_OVERLAY": {
        "path": "docs/control/execution/walksafe-epic-01-phase-b-active-ledger-overlay-20260722-r001.json",
        "document_id": "WS-EPIC-01-PHASE-B-ACTIVE-LEDGER-OVERLAY-20260722-001",
        "identity_json_path": "metadata.overlay_id",
        "mutable": False,
    },
    "IMPLEMENTATION_GAP": {
        "path": "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r011.json",
        "document_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-011",
        "identity_json_path": "metadata.report_id",
        "mutable": False,
    },
    "IMPLEMENTATION_BACKLOG": {
        "path": "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r011.json",
        "document_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-011",
        "identity_json_path": "metadata.backlog_id",
        "mutable": False,
    },
    "EPIC01_PHASE_C_RECORD": {
        "path": "docs/control/execution/walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.json",
        "document_id": "WS-EPIC-01-PHASE-C-RUNTIME-METRIC-PREFLIGHT-IMPLEMENTATION-20260722-001",
        "identity_json_path": "metadata.record_id",
        "mutable": False,
    },
    "EPIC01_PHASE_C_ACTIVE_OVERLAY": {
        "path": "docs/control/execution/walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.json",
        "document_id": "WS-EPIC-01-PHASE-C-ACTIVE-LEDGER-OVERLAY-20260722-001",
        "identity_json_path": "metadata.overlay_id",
        "mutable": False,
    },
    "EPIC01_PHASE_D_RECORD": {
        "path": "docs/control/execution/walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.json",
        "document_id": "WS-EPIC-01-PHASE-D-LEGACY-WEB-CLOSURE-IMPLEMENTATION-20260723-001",
        "identity_json_path": "metadata.record_id",
        "mutable": False,
    },
    "EPIC01_PHASE_D_ACTIVE_OVERLAY": {
        "path": "docs/control/execution/walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.json",
        "document_id": "WS-EPIC-01-PHASE-D-ACTIVE-LEDGER-OVERLAY-20260723-001",
        "identity_json_path": "metadata.overlay_id",
        "mutable": False,
    },
    "EPIC01_PHASE_E_RECORD": {
        "path": "docs/control/execution/walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.json",
        "document_id": "WS-EPIC-01-PHASE-E-ANDROID-GATEWAY-IMPLEMENTATION-20260723-001",
        "identity_json_path": "metadata.record_id",
        "mutable": False,
    },
    "EPIC01_PHASE_E_ACTIVE_OVERLAY": {
        "path": "docs/control/execution/walksafe-epic-01-phase-e-active-ledger-overlay-20260723-r001.json",
        "document_id": "WS-EPIC-01-PHASE-E-ACTIVE-LEDGER-OVERLAY-20260723-001",
        "identity_json_path": "metadata.overlay_id",
        "mutable": False,
    },
    "EPIC01_PHASE_F_RECORD": {
        "path": "docs/control/execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.json",
        "document_id": "WS-EPIC-01-PHASE-F-PURPOSE-SURFACES-IMPLEMENTATION-20260723-001",
        "identity_json_path": "metadata.record_id",
        "mutable": False,
    },
    "EPIC01_PHASE_F_ACTIVE_OVERLAY": {
        "path": "docs/control/execution/walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.json",
        "document_id": "WS-EPIC-01-PHASE-F-ACTIVE-LEDGER-OVERLAY-20260723-001",
        "identity_json_path": "metadata.overlay_id",
        "mutable": False,
    },
    "EPIC01_PHASE_G_RECORD": {
        "path": "docs/control/execution/walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.json",
        "document_id": "WS-EPIC-01-PHASE-G-NO-DESTINATION-HAZARD-IMPLEMENTATION-20260723-001",
        "identity_json_path": "metadata.record_id",
        "mutable": False,
    },
    "EPIC01_PHASE_G_ACTIVE_OVERLAY": {
        "path": "docs/control/execution/walksafe-epic-01-phase-g-active-ledger-overlay-20260723-r001.json",
        "document_id": "WS-EPIC-01-PHASE-G-ACTIVE-LEDGER-OVERLAY-20260723-001",
        "identity_json_path": "metadata.overlay_id",
        "mutable": False,
    },
    "EPIC02_PHASE_A_RECORD": {
        "path": "docs/control/execution/walksafe-epic-02-phase-a-walk-session-lifecycle-implementation-record-20260723.json",
        "document_id": "WS-EPIC-02-PHASE-A-WALK-SESSION-LIFECYCLE-IMPLEMENTATION-20260723-001",
        "identity_json_path": "metadata.record_id",
        "mutable": False,
    },
    "EPIC02_PHASE_A_ACTIVE_OVERLAY": {
        "path": "docs/control/execution/walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.json",
        "document_id": "WS-EPIC-02-PHASE-A-ACTIVE-LEDGER-OVERLAY-20260723-001",
        "identity_json_path": "metadata.overlay_id",
        "mutable": False,
    },
    "WORK_ITEM_COMPLETION::WS-GOAL-EPIC-02-FP-018-R001": {
        "path": "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-018-R001/completion-receipt.json",
        "document_id": "WS-FP018-WORK-ITEM-COMPLETION-20260724-001",
        "identity_json_path": "document_id",
        "mutable": False,
    },
    "WORK_ITEM_COMPLETION::WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001": {
        "path": "docs/control/execution/goal-results/WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001/completion-receipt.json",
        "document_id": "WS-NPC-PERMISSION-SESSION-WORK-ITEM-COMPLETION-20260724-001",
        "identity_json_path": "document_id",
        "mutable": False,
    },
    "WORK_ITEM_COMPLETION::WS-GOAL-EPIC-02-FP-004-R001": {
        "path": "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-004-R001/completion-receipt.json",
        "document_id": "WS-FP004-PRIORITY-USER-WORK-ITEM-COMPLETION-20260724-001",
        "identity_json_path": "document_id",
        "mutable": False,
    },
}

EXPECTED_PHASE_A_CONTROLLED_PATHS = (
    ".github/workflows/quality.yml",
    "AGENTS.md",
    "README.md",
    "apps/README.md",
    "apps/android/README.md",
    "apps/android/adminapp/build.gradle.kts",
    "apps/android/adminapp/gradle.lockfile",
    "apps/android/adminapp/proguard-rules.pro",
    "apps/android/adminapp/src/main/AndroidManifest.xml",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java",
    "apps/android/adminapp/src/main/res/values/styles.xml",
    "apps/android/app/build.gradle.kts",
    "apps/android/app/src/main/AndroidManifest.xml",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidStartupCapabilityProbe.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt",
    "apps/android/app/src/main/res/values/strings.xml",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityStartupCapabilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapabilityTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuatorStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/MainActivityReportUploadStaticTest.kt",
    "apps/android/settings.gradle.kts",
    "apps/web/README.md",
    "configs/walksafe_product_boundary_20260722.json",
    "daylog/2026-07-22.md",
    "deploy/README.md",
    "deploy/config/walksafe-web.env.example",
    "deploy/nginx/walksafe-web.conf.example",
    "deploy/systemd/walksafe-web.service",
    "docs/README.md",
    "docs/control/README.md",
    "docs/control/execution/walksafe-epic-01-phase-a-implementation-record-20260722.json",
    "docs/control/execution/walksafe-epic-01-phase-a-implementation-record-20260722.md",
    "docs/control/walksafe-project-resumption-runbook.md",
    "docs/status/current_status.md",
    "product/backlog.md",
    "product/done-criteria.md",
    "product/roadmap.md",
    "product/vision.md",
    "scripts/README.md",
    "scripts/check_walksafe_legacy_web_boundary_20260722.py",
    "scripts/check_walksafe_project_continuation.py",
    "scripts/run_walksafe_remote_field_stack_20260711.sh",
    "scripts/run_walksafe_test_layers_20260711.sh",
    "tests/README.md",
    "tests/test_walksafe_android_product_boundary.py",
    "tests/test_walksafe_legacy_web_boundary_20260722.py",
    "tests/test_walksafe_project_continuation.py",
    "tests/test_web_build_manifest.py",
)

EXPECTED_PHASE_B_IMPLEMENTATION_PATHS = (
    "configs/walksafe_product_boundary_20260722.json",
    "apps/android/adminapp/build.gradle.kts",
    "apps/android/adminapp/gradle.lockfile",
    "apps/android/adminapp/src/main/AndroidManifest.xml",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminEndpointPolicy.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGate.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityApi.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityState.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminEndpointPolicyTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGateTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityBoundaryStaticTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityControllerTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClientTest.java",
    "backend/alembic/versions/202607220001_admin_password_totp_security.py",
    "backend/.env.example",
    "backend/README.md",
    "backend/app/api/admin_security.py",
    "backend/app/api/health.py",
    "backend/app/api/reports.py",
    "backend/app/config.py",
    "backend/app/field_test_security.py",
    "backend/app/main.py",
    "backend/app/models.py",
    "backend/app/openapi_contract.py",
    "backend/app/services/admin_security.py",
    "backend/requirements.lock",
    "backend/requirements.txt",
    "backend/tests/conftest.py",
    "backend/tests/test_admin_security.py",
    "backend/tests/test_field_test_security.py",
    "backend/tests/test_health_readiness.py",
    "backend/tests/test_openapi_contract.py",
    "contracts/README.md",
    "contracts/walksafe.openapi.json",
    "deploy/config/walksafe-backend.env.example",
    "deploy/systemd/walksafe-backend-migrate.service",
    "deploy/systemd/walksafe-backend.service",
    "deploy/systemd/walksafe-log-retention.service",
    "docs/backend/api_reference.md",
    "docs/backend/backend_environment.md",
    "scripts/README.md",
    "scripts/check_report_retention_dry_run.py",
    "scripts/check_walksafe_release_evidence_20260711.py",
    "scripts/generate_walksafe_openapi.py",
    "scripts/manage_field_telemetry_retention_20260711.py",
    "scripts/prune_walksafe_backups_20260711.py",
    "scripts/run_walksafe_report_retention_20260717.sh",
    "scripts/run_walksafe_test_layers_20260711.sh",
    "scripts/validate_walksafe_full_rc_20260713.py",
    "scripts/walksafe_admin_high_risk_gate.py",
    "tests/test_walksafe_backup_prune.py",
    "tests/test_report_retention_scheduler.py",
    "tests/test_field_telemetry_retention.py",
    "tests/test_release_evidence_gate.py",
    "tests/test_walksafe_full_rc_tooling.py",
    "tests/test_walksafe_admin_high_risk_data_delete_gate.py",
    "tests/test_walksafe_android_product_boundary.py",
)

EXPECTED_PHASE_B_TRACE_PATHS = (
    "scripts/build_walksafe_epic01_phase_b_trace_20260722.py",
    "tests/test_walksafe_epic01_phase_b_trace_20260722.py",
    "docs/control/execution/walksafe-epic-01-phase-b-admin-auth-recovery-implementation-record-20260722.json",
    "docs/control/execution/walksafe-epic-01-phase-b-admin-auth-recovery-implementation-record-20260722.md",
    "docs/control/execution/walksafe-single-admin-recovery-drill-protocol-20260722-r001.json",
    "docs/control/execution/walksafe-single-admin-recovery-drill-protocol-20260722-r001.md",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r002.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r002.md",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r002.json",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r002.md",
    "docs/control/execution/walksafe-epic-01-phase-b-active-ledger-overlay-20260722-r001.json",
    "docs/control/execution/walksafe-epic-01-phase-b-active-ledger-overlay-20260722-r001.md",
)

EXPECTED_PHASE_C_IMPLEMENTATION_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidStartupCapabilityProbe.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/ApprovedDeviceProfile.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/RuntimeMetricPreflight.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/RuntimeMetricMainActivityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityStartupCapabilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/ArCoreTactileProjectionContextFactoryTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSessionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/ApprovedDeviceProfileTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/RuntimeMetricPreflightTest.kt",
)

EXPECTED_PHASE_C_TRACE_PATHS = (
    "scripts/build_walksafe_epic01_phase_c_trace_20260722.py",
    "tests/test_walksafe_epic01_phase_c_trace_20260722.py",
    "docs/control/execution/walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.json",
    "docs/control/execution/walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.md",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r003.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r003.md",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r003.json",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r003.md",
    "docs/control/execution/walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.json",
    "docs/control/execution/walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.md",
)

EXPECTED_PHASE_D_IMPLEMENTATION_PATHS = (
    ".github/workflows/quality.yml",
    "apps/web/README.md",
    "apps/web/package.json",
    "apps/web/legacy-runtime-boundary.ts",
    "apps/web/proxy.ts",
    "apps/web/tests/field-test-gateway-policy.test.ts",
    "configs/walksafe_product_boundary_20260722.json",
    "deploy/README.md",
    "deploy/config/walksafe-web.env.example",
    "deploy/nginx/walksafe-web.conf.example",
    "deploy/systemd/walksafe-web.service",
    "docs/release/walksafe_full_rc_20260713.md",
    "docs/testing/web_remote_field_test_20260711.md",
    "docs/testing/README.md",
    "scripts/README.md",
    "scripts/build_walksafe_web_release_20260711.sh",
    "scripts/build_walksafe_full_rc_20260713.py",
    "scripts/validate_walksafe_full_rc_20260713.py",
    "scripts/run_cloudflare_field_test_services_20260711.sh",
    "scripts/run_walksafe_remote_field_stack_20260711.sh",
    "scripts/run_walksafe_product_quality_20260713.py",
    "scripts/run_walksafe_web_single_instance_20260713.py",
    "scripts/check_frontend_field_test_gateway_20260711.sh",
    "scripts/check_walksafe_legacy_web_boundary_20260722.py",
    "scripts/check_walksafe_release_evidence_20260711.py",
    "scripts/run_walksafe_test_layers_20260711.sh",
    "tests/general-quality-cp312-linux-x86_64-cpu.lock",
    "tests/test_walksafe_android_product_boundary.py",
    "tests/test_release_evidence_gate.py",
    "tests/test_cloudflare_field_runner.py",
    "tests/test_walksafe_legacy_web_boundary_20260722.py",
    "tests/test_web_build_manifest.py",
    "tests/test_walksafe_isolated_python_bootstrap.py",
    "tests/test_walksafe_full_rc_tooling.py",
)

EXPECTED_PHASE_D_TRACE_PATHS = (
    "scripts/build_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py",
    "tests/test_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py",
    "docs/control/execution/walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.json",
    "docs/control/execution/walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.md",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r004.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r004.md",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r004.json",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r004.md",
    "docs/control/execution/walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.json",
    "docs/control/execution/walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.md",
)

EXPECTED_PHASE_E_IMPLEMENTATION_PATHS = (
    ".github/workflows/quality.yml",
    "README.md",
    "apps/README.md",
    "apps/android/README.md",
    "apps/android/app/build.gradle.kts",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClient.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewayEndpointPolicyTest.kt",
    "apps/android-gateway/README.md",
    "apps/android-gateway/openapi.json",
    "apps/android-gateway/package.json",
    "apps/android-gateway/package-lock.json",
    "apps/android-gateway/tsconfig.json",
    "apps/android-gateway/src/auth.ts",
    "apps/android-gateway/src/backend.ts",
    "apps/android-gateway/src/config.ts",
    "apps/android-gateway/src/node-adapter.ts",
    "apps/android-gateway/src/request-body.ts",
    "apps/android-gateway/src/routes.ts",
    "apps/android-gateway/server.ts",
    "apps/android-gateway/test/gateway-contract.test.ts",
    "apps/android-gateway/test/node-adapter.test.ts",
    "apps/web/README.md",
    "apps/web/legacy-runtime-boundary.ts",
    "apps/web/package.json",
    "apps/web/proxy.ts",
    "apps/web/tsconfig.json",
    "apps/web/tests/api-client-contract-policy.test.ts",
    "configs/walksafe_product_boundary_20260722.json",
    "deploy/README.md",
    "deploy/config/walksafe-android-gateway.env.example",
    "deploy/nginx/walksafe-android-gateway.conf.example",
    "deploy/systemd/walksafe-android-gateway.service",
    "scripts/check_frontend_policy_suite.sh",
    "scripts/run_walksafe_test_layers_20260711.sh",
    "tests/test_walksafe_android_product_boundary.py",
    "scripts/check_walksafe_android_gateway_boundary_20260723.py",
    "tests/test_walksafe_android_gateway_boundary_20260723.py",
    "scripts/README.md",
    "tests/README.md",
)

EXPECTED_PHASE_E_REMOVED_NEXT_ROUTE_PATHS = (
    "apps/web/app/api/field-session/route.ts",
    "apps/web/app/api/navigation/walking/route.ts",
    "apps/web/app/api/navigation/destinations/search/route.ts",
    "apps/web/app/api/reports/v2/route.ts",
)

EXPECTED_PHASE_E_TRACE_PATHS = (
    "scripts/build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py",
    "tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py",
    "docs/control/execution/walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.json",
    "docs/control/execution/walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.md",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r005.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r005.md",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r005.json",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r005.md",
    "docs/control/execution/walksafe-epic-01-phase-e-active-ledger-overlay-20260723-r001.json",
    "docs/control/execution/walksafe-epic-01-phase-e-active-ledger-overlay-20260723-r001.md",
)

EXPECTED_PHASE_F_IMPLEMENTATION_PATHS = (
    "apps/android/README.md",
    "apps/android/USER_GUIDE.md",
    "apps/android/RELEASE_DESCRIPTION.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSession.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapabilityTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSessionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/ProductPurposeSurfacesStaticTest.kt",
)

EXPECTED_PHASE_F_TRACE_PATHS = (
    "scripts/build_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py",
    "tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py",
    "docs/control/execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.json",
    "docs/control/execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.md",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r006.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r006.md",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r006.json",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r006.md",
    "docs/control/execution/walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.json",
    "docs/control/execution/walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.md",
)

EXPECTED_PHASE_G_IMPLEMENTATION_PATHS = (
    "apps/android/README.md",
    "apps/android/USER_GUIDE.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidLocalTactileCapability.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidNonMetricObstacleAdvisoryPolicy.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/CameraXFallbackCompositionStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/AndroidLocalTactileCapabilityTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidNonMetricObstacleAdvisoryPolicyTest.kt",
    "scripts/summarize_android_field_sessions_20260710.py",
    "scripts/walksafe_external_check_receipt.py",
    "tests/test_android_field_session_summary.py",
    "tests/test_release_evidence_gate.py",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidTactileRouteGuidanceTest.kt",
    "docs/android/arcore_depth_estimation_architecture.md",
)

EXPECTED_PHASE_G_TRACE_PATHS = (
    "scripts/build_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py",
    "tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py",
    "docs/control/execution/walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.json",
    "docs/control/execution/walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.md",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r007.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r007.md",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r007.json",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r007.md",
    "docs/control/execution/walksafe-epic-01-phase-g-active-ledger-overlay-20260723-r001.json",
    "docs/control/execution/walksafe-epic-01-phase-g-active-ledger-overlay-20260723-r001.md",
)

# Historical path inventory embedded in the sealed FP-017 completion record.
# These paths remain part of the reproducible working snapshot, but their live
# bytes are not required to match the completion-time hashes.
EXPECTED_EPIC02_PHASE_A_IMPLEMENTATION_PATHS = (
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

EXPECTED_EPIC02_PHASE_A_TRACE_PATHS = (
    "scripts/build_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py",
    "tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py",
    "docs/control/execution/walksafe-epic-02-phase-a-walk-session-lifecycle-implementation-record-20260723.json",
    "docs/control/execution/walksafe-epic-02-phase-a-walk-session-lifecycle-implementation-record-20260723.md",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r008.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r008.md",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r008.json",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r008.md",
    "docs/control/execution/walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.json",
    "docs/control/execution/walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.md",
)

EXPECTED_FP018_IN_PROGRESS_IMPLEMENTATION_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidWalkSessionResourceProbe.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/WalkSafeFeedbackPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadiness.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityNavigationCompositionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/WalkSessionDeviceResourceSnapshotTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/feedback/WalkSafeFeedbackPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSessionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionLifecycleFp018Test.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadinessTest.kt",
)

EXPECTED_FP018_TRACE_PATHS = (
    "scripts/build_walksafe_fp018_walk_state_recovery_trace_20260724.py",
    "tests/test_walksafe_fp018_walk_state_recovery_trace_20260724.py",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-018-R001/focused-tests.log",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-018-R001/related-static-tests.log",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-018-R001/full-android-test-build-lint.log",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-018-R001/implementation-record.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-018-R001/verification-result.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-018-R001/successor-trace.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-018-R001/independent-review.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-018-R001/completion-receipt.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r009.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r009.md",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r009.json",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r009.md",
    "docs/control/execution/walksafe-epic-02-fp018-active-ledger-overlay-20260724-r001.json",
    "docs/control/execution/walksafe-epic-02-fp018-active-ledger-overlay-20260724-r001.md",
)

EXPECTED_NPC_PERMISSION_SESSION_IMPLEMENTATION_PATHS = (
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

EXPECTED_NPC_PERMISSION_SESSION_TRACE_PATHS = (
    "scripts/build_walksafe_npc_permission_session_trace_20260724.py",
    "tests/test_walksafe_npc_permission_session_trace_20260724.py",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001/state-ownership-matrix.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001/implementation-record.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001/verification-result.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001/successor-trace.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001/independent-review.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001/completion-receipt.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001/focused-android-tests.log",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001/gateway-contract-boundary.log",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001/full-android-test-build-lint.log",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r010.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r010.md",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r010.json",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r010.md",
    "docs/control/execution/walksafe-epic-02-npc-permission-session-active-ledger-overlay-20260724-r001.json",
    "docs/control/execution/walksafe-epic-02-npc-permission-session-active-ledger-overlay-20260724-r001.md",
)

EXPECTED_FP004_IN_PROGRESS_IMPLEMENTATION_PATHS = (
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

EXPECTED_FP004_INTERRUPTED_TRACE_PATHS = (
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-004-R001/focused-android-tests.log",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-004-R001/full-android-test-build-lint.log",
)

EXPECTED_FP004_TRACE_PATHS = (
    "scripts/build_walksafe_fp004_priority_user_trace_20260724.py",
    "tests/test_walksafe_fp004_priority_user_trace_20260724.py",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-004-R001/focused-android-tests-resumed.log",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-004-R001/full-android-test-build-lint-resumed.log",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-004-R001/implementation-record.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-004-R001/verification-result.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-004-R001/successor-trace.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-004-R001/independent-review.json",
    "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-004-R001/completion-receipt.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r011.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260724-r011.md",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r011.json",
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r011.md",
    "docs/control/execution/walksafe-epic-02-fp004-priority-user-active-ledger-overlay-20260724-r001.json",
    "docs/control/execution/walksafe-epic-02-fp004-priority-user-active-ledger-overlay-20260724-r001.md",
)

EXPECTED_SUPERSEDED_GOAL_PACKAGE_PATHS = (
    "docs/control/goals/walksafe-completion-v1/00-master-goal.md",
    "docs/control/goals/walksafe-completion-v1/10-phase-a-implementation-readiness.md",
    "docs/control/goals/walksafe-completion-v1/20-phase-b-formal-verification.md",
    "docs/control/goals/walksafe-completion-v1/30-phase-c-release-delivery.md",
    "docs/control/goals/walksafe-completion-v1/40-phase-d-operation-handover-closure.md",
    "docs/control/goals/walksafe-completion-v1/README.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-01-product-boundary.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-02-safe-walk-state-and-permissions.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-03-account-admin-security.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-04-navigation-arrival-deviation.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-05-object-detection-safety.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-06-voice-haptic-accessibility.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-07-raw-data-lifecycle.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-08-auto-report-queue.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-09-server-capacity-resilience.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-10-ai-model-lifecycle.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-11-release-operations-recovery-readiness.md",
    "docs/control/goals/walksafe-completion-v1/epics/epic-12-formal-verification-gates.md",
    "docs/control/goals/walksafe-completion-v1/static-plan-manifest-v1.1.0.json",
    "docs/control/goals/walksafe-completion-v1/templates/work-item-template.md",
    "docs/control/goals/walksafe-completion-v1/work-items/a/epic-02-fp018-walk-state-recovery-r001.md",
    "scripts/check_walksafe_goal_package.py",
    "tests/test_walksafe_goal_package.py",
)

EXPECTED_SUPERSEDED_V2_GOAL_PACKAGE_PATHS = (
    "docs/control/goals/walksafe-completion-graph-v2/00-master-goal.md",
    "docs/control/goals/walksafe-completion-graph-v2/README.md",
    "docs/control/goals/walksafe-completion-graph-v2/preactivation-supersession-record-v1.1.0.json",
    "docs/control/goals/walksafe-completion-graph-v2/static-plan-manifest-v2.0.0.json",
    "docs/control/goals/walksafe-completion-graph-v2/templates/dynamic-node-template.md",
    "docs/control/goals/walksafe-completion-graph-v2/templates/policy-gap-work-item.md",
    "docs/control/goals/walksafe-completion-graph-v2/work-items/epic-02/epic-02-fp018-walk-state-recovery-r001.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-01-product-boundary.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-02-safe-walk-state-and-permissions.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-03-account-admin-security.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-04-navigation-arrival-deviation.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-05-object-detection-safety.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-06-voice-haptic-accessibility.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-07-raw-data-lifecycle.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-08-auto-report-queue.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-09-server-capacity-resilience.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-10-ai-model-lifecycle.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-11-release-operations-recovery-readiness.md",
    "docs/control/goals/walksafe-completion-graph-v2/workstreams/epic-12-formal-verification-gates.md",
)

EXPECTED_FROZEN_V21_GOAL_PACKAGE_PATHS = (
    "docs/control/goals/walksafe-completion-graph-v2-1/00-master-goal.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/README.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/preactivation-supersession-record-v1.1.0.json",
    "docs/control/goals/walksafe-completion-graph-v2-1/preactivation-supersession-record-v2.0.0.json",
    "docs/control/goals/walksafe-completion-graph-v2-1/static-plan-manifest-v2.1.0.json",
    "docs/control/goals/walksafe-completion-graph-v2-1/superseded-v2.0.0-package-prepared-event.json",
    "docs/control/goals/walksafe-completion-graph-v2-1/templates/dynamic-node-template.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/templates/policy-gap-work-item.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/work-items/epic-02/epic-02-fp018-walk-state-recovery-r001.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-01-product-boundary.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-02-safe-walk-state-and-permissions.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-03-account-admin-security.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-04-navigation-arrival-deviation.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-05-object-detection-safety.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-06-voice-haptic-accessibility.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-07-raw-data-lifecycle.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-08-auto-report-queue.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-09-server-capacity-resilience.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-10-ai-model-lifecycle.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-11-release-operations-recovery-readiness.md",
    "docs/control/goals/walksafe-completion-graph-v2-1/workstreams/epic-12-formal-verification-gates.md",
)

EXPECTED_GOAL_PACKAGE_PATHS = (
    "docs/control/goals/README.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/00-master-goal.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/README.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/preactivation-supersession-record-v2.1.0.json",
    "docs/control/goals/walksafe-completion-graph-v2-2/static-plan-manifest-v2.2.0.json",
    "docs/control/goals/walksafe-completion-graph-v2-2/superseded-v2.1.0-package-prepared-event.json",
    "docs/control/goals/walksafe-completion-graph-v2-2/templates/dynamic-node-template.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/templates/policy-gap-work-item.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/work-items/epic-02/epic-02-fp018-walk-state-recovery-r001.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-01-product-boundary.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-02-safe-walk-state-and-permissions.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-03-account-admin-security.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-04-navigation-arrival-deviation.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-05-object-detection-safety.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-06-voice-haptic-accessibility.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-07-raw-data-lifecycle.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-08-auto-report-queue.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-09-server-capacity-resilience.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-10-ai-model-lifecycle.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-11-release-operations-recovery-readiness.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-12-formal-verification-gates.md",
    "scripts/check_walksafe_goal_graph.py",
    "tests/test_walksafe_goal_graph.py",
)

EXPECTED_CURRENT_DYNAMIC_GOAL_PATHS = (
    "docs/control/goals/walksafe-completion-graph-v2-2/work-items/epic-02/"
    "epic-02-npc-permission-session-lifecycle-r001.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/work-items/epic-02/"
    "epic-02-fp004-priority-user-r001.md",
    "docs/control/goals/walksafe-completion-graph-v2-2/work-items/epic-02/"
    "epic-02-fp005-official-environment-crosswalk-r001.md",
)

EXPECTED_CONTROLLED_PATHS = tuple(
    sorted(
        set(EXPECTED_PHASE_A_CONTROLLED_PATHS)
        | set(EXPECTED_PHASE_B_IMPLEMENTATION_PATHS)
        | set(EXPECTED_PHASE_B_TRACE_PATHS)
        | set(EXPECTED_PHASE_C_IMPLEMENTATION_PATHS)
        | set(EXPECTED_PHASE_C_TRACE_PATHS)
        | set(EXPECTED_PHASE_D_IMPLEMENTATION_PATHS)
        | set(EXPECTED_PHASE_D_TRACE_PATHS)
        | set(EXPECTED_PHASE_E_IMPLEMENTATION_PATHS)
        | set(EXPECTED_PHASE_E_TRACE_PATHS)
        | set(EXPECTED_PHASE_F_IMPLEMENTATION_PATHS)
        | set(EXPECTED_PHASE_F_TRACE_PATHS)
        | set(EXPECTED_PHASE_G_IMPLEMENTATION_PATHS)
        | set(EXPECTED_PHASE_G_TRACE_PATHS)
        | set(EXPECTED_EPIC02_PHASE_A_IMPLEMENTATION_PATHS)
        | set(EXPECTED_EPIC02_PHASE_A_TRACE_PATHS)
        | set(EXPECTED_FP018_IN_PROGRESS_IMPLEMENTATION_PATHS)
        | set(EXPECTED_FP018_TRACE_PATHS)
        | set(EXPECTED_NPC_PERMISSION_SESSION_IMPLEMENTATION_PATHS)
        | set(EXPECTED_NPC_PERMISSION_SESSION_TRACE_PATHS)
        | set(EXPECTED_FP004_IN_PROGRESS_IMPLEMENTATION_PATHS)
        | set(EXPECTED_FP004_INTERRUPTED_TRACE_PATHS)
        | set(EXPECTED_FP004_TRACE_PATHS)
        | set(EXPECTED_SUPERSEDED_GOAL_PACKAGE_PATHS)
        | set(EXPECTED_SUPERSEDED_V2_GOAL_PACKAGE_PATHS)
        | set(EXPECTED_FROZEN_V21_GOAL_PACKAGE_PATHS)
        | set(EXPECTED_GOAL_PACKAGE_PATHS)
        | set(EXPECTED_CURRENT_DYNAMIC_GOAL_PATHS)
        | {"daylog/2026-07-23.md", "daylog/2026-07-24.md"}
    )
)


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_safe_repo_file(root: Path, relative: Any) -> Path | None:
    if not isinstance(relative, str) or not relative:
        return None
    try:
        relative_path = Path(relative)
        if relative_path.is_absolute() or ".." in relative_path.parts:
            return None
        resolved_root = root.resolve(strict=True)
        candidate = resolved_root
        for part in relative_path.parts:
            candidate /= part
            if candidate.is_symlink():
                return None
        resolved = candidate.resolve(strict=True)
    except (OSError, RuntimeError, ValueError):
        return None
    if not resolved.is_file() or not resolved.is_relative_to(resolved_root):
        return None
    return resolved


def repo_path_has_symlink_component(root: Path, relative: Any) -> bool:
    if not isinstance(relative, str) or not relative:
        return True
    try:
        relative_path = Path(relative)
        if relative_path.is_absolute() or ".." in relative_path.parts:
            return True
        candidate = root.resolve(strict=True)
        for part in relative_path.parts:
            candidate /= part
            if candidate.is_symlink():
                return True
    except (OSError, RuntimeError, ValueError):
        return True
    return False


def object_seal_is_valid(value: Any, seal_key: str) -> bool:
    if not isinstance(value, dict):
        return False
    payload = dict(value)
    actual = payload.pop(seal_key, None)
    if not isinstance(actual, str) or len(actual) != 64:
        return False
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest() == actual


def package_regular_file_paths(root: Path, package_relative: str) -> tuple[str, ...]:
    relative_path = Path(package_relative)
    if (
        relative_path.is_absolute()
        or not relative_path.parts
        or ".." in relative_path.parts
    ):
        raise ValueError(f"unsafe package path: {package_relative}")
    resolved_root = root.resolve(strict=True)
    package_path = resolved_root
    for part in relative_path.parts:
        package_path /= part
        if package_path.is_symlink():
            raise ValueError(f"package path contains a symlink: {package_relative}")
    package_path = package_path.resolve(strict=True)
    if not package_path.is_dir() or not package_path.is_relative_to(resolved_root):
        raise ValueError(f"package directory is missing or unsafe: {package_relative}")

    paths: list[str] = []
    for current_root, directory_names, file_names in os.walk(
        package_path,
        followlinks=False,
    ):
        current = Path(current_root)
        for name in directory_names:
            directory = current / name
            if directory.is_symlink():
                raise ValueError(
                    "package contains a symlink directory: "
                    f"{directory.relative_to(resolved_root).as_posix()}"
                )
        for name in file_names:
            candidate = current / name
            relative = candidate.relative_to(resolved_root).as_posix()
            try:
                candidate_stat = candidate.lstat()
            except OSError as exc:
                raise ValueError(
                    f"package file cannot be inspected: {relative}"
                ) from exc
            if not stat.S_ISREG(candidate_stat.st_mode):
                raise ValueError(
                    f"package contains a non-regular file: {relative}"
                )
            paths.append(relative)
    return tuple(sorted(paths))


def validate_protected_goal_package(
    errors: list[str],
    *,
    label: str,
    root: Path,
    manifest: dict[str, Any],
    manifest_path: str,
    expected_package_paths: tuple[str, ...],
    expected_protected_file_count: int,
    expected_path_set_sha256: str | None = None,
    expected_content_set_sha256: str | None = None,
    unprotected_runtime_paths: tuple[str, ...] = (),
) -> None:
    package_relative = Path(manifest_path).parent.as_posix()
    expected_paths = tuple(sorted(expected_package_paths))
    try:
        actual_paths = package_regular_file_paths(root, package_relative)
    except (OSError, RuntimeError, ValueError) as exc:
        errors.append(f"{label} package path inventory cannot be read: {exc}")
        actual_paths = ()
    require_equal(
        errors,
        f"{label} package path inventory",
        list(actual_paths),
        list(expected_paths),
    )

    protected_files = manifest.get("protected_files")
    if not isinstance(protected_files, list):
        errors.append(f"{label} protected_files must be a list")
        protected_files = []
    protected_by_path: dict[str, str] = {}
    protected_paths: list[str] = []
    for index, entry in enumerate(protected_files):
        if not isinstance(entry, dict):
            errors.append(f"{label} protected_files[{index}] must be an object")
            continue
        relative = entry.get("path")
        expected_hash = entry.get("sha256")
        if (
            not isinstance(relative, str)
            or not isinstance(expected_hash, str)
            or re.fullmatch(r"[0-9a-f]{64}", expected_hash) is None
        ):
            errors.append(
                f"{label} protected_files[{index}] path/hash is malformed"
            )
            continue
        if relative in protected_by_path:
            errors.append(f"{label} duplicate protected path: {relative}")
            continue
        protected_paths.append(relative)
        protected_by_path[relative] = expected_hash

    runtime_path_set = set(unprotected_runtime_paths)
    expected_protected_paths = [
        relative
        for relative in expected_paths
        if relative != manifest_path and relative not in runtime_path_set
    ]
    require_equal(
        errors,
        f"{label} protected file count",
        len(protected_files),
        expected_protected_file_count,
    )
    require_equal(
        errors,
        f"{label} protected path inventory",
        protected_paths,
        expected_protected_paths,
    )
    for relative in expected_protected_paths:
        protected = resolve_safe_repo_file(root, relative)
        if protected is None:
            errors.append(f"{label} protected file missing or unsafe: {relative}")
            continue
        require_equal(
            errors,
            f"{label} protected file SHA-256 {relative}",
            sha256_file(protected),
            protected_by_path.get(relative),
        )

    if (
        expected_path_set_sha256 is not None
        and expected_content_set_sha256 is not None
    ):
        try:
            path_hash, content_hash = working_snapshot_hashes(
                root,
                list(expected_paths),
            )
        except (OSError, RuntimeError, ValueError) as exc:
            errors.append(f"{label} package aggregate cannot be reproduced: {exc}")
        else:
            require_equal(
                errors,
                f"{label} package path-set SHA-256",
                path_hash,
                expected_path_set_sha256,
            )
            require_equal(
                errors,
                f"{label} package content-set SHA-256",
                content_hash,
                expected_content_set_sha256,
            )


def load_goal_manifest(
    errors: list[str],
    *,
    label: str,
    root: Path,
    manifest_path: str,
    expected_sha256: str,
) -> tuple[dict[str, Any], str]:
    resolved = resolve_safe_repo_file(root, manifest_path)
    if resolved is None:
        errors.append(f"{label} Goal manifest is missing or unsafe: {manifest_path}")
        return {}, ""
    actual_hash = sha256_file(resolved)
    require_equal(
        errors,
        f"{label} Goal manifest SHA-256",
        actual_hash,
        expected_sha256,
    )
    try:
        return load_json(resolved), actual_hash
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        errors.append(f"{label} Goal manifest cannot be loaded: {exc}")
        return {}, actual_hash


def load_prepared_event(
    errors: list[str],
    *,
    label: str,
    root: Path,
    event_path: str,
    expected_event_sha256: str,
    expected_manifest_sha256: str,
    expected_supersedes_event_sha256: str,
) -> dict[str, Any]:
    resolved = resolve_safe_repo_file(root, event_path)
    if resolved is None:
        errors.append(f"{label} prepared event is missing or unsafe: {event_path}")
        event: dict[str, Any] = {}
    else:
        try:
            event = load_json(resolved)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"{label} prepared event cannot be loaded: {exc}")
            event = {}
    if event and not object_seal_is_valid(event, "event_sha256"):
        errors.append(f"{label} prepared event seal is invalid")
    require_equal(
        errors,
        f"{label} prepared event SHA-256",
        event.get("event_sha256"),
        expected_event_sha256,
    )
    require_equal(
        errors,
        f"{label} prepared event manifest SHA-256",
        event.get("static_plan_manifest_sha256"),
        expected_manifest_sha256,
    )
    require_equal(
        errors,
        f"{label} prepared event predecessor SHA-256",
        event.get("supersedes_event_sha256"),
        expected_supersedes_event_sha256,
    )
    return event


def validate_goal_package_binding(
    errors: list[str],
    goal_execution: dict[str, Any],
    root: Path,
) -> None:
    require_equal(
        errors,
        "active Goal package ID",
        goal_execution.get("package_id"),
        EXPECTED_ACTIVE_GOAL_PACKAGE_ID,
    )
    require_equal(
        errors,
        "active Goal schema version",
        goal_execution.get("schema_version"),
        EXPECTED_ACTIVE_GOAL_SCHEMA_VERSION,
    )
    require_equal(
        errors,
        "active Goal master ID",
        goal_execution.get("master_goal_id"),
        EXPECTED_ACTIVE_GOAL_MASTER_ID,
    )
    require_equal(
        errors,
        "active Goal focus path",
        goal_execution.get("focus_goal_path"),
        EXPECTED_ACTIVE_GOAL_FOCUS_PATH,
    )
    require_equal(
        errors,
        "active Goal manifest path",
        goal_execution.get("static_plan_manifest_path"),
        EXPECTED_ACTIVE_GOAL_MANIFEST_PATH,
    )
    require_equal(
        errors,
        "active Goal plan version",
        goal_execution.get("static_plan_version"),
        EXPECTED_ACTIVE_GOAL_PLAN_VERSION,
    )
    require_equal(
        errors,
        "active Goal static plan lock",
        goal_execution.get("static_plan_locked"),
        True,
    )

    active_manifest, active_manifest_hash = load_goal_manifest(
        errors,
        label="active",
        root=root,
        manifest_path=EXPECTED_ACTIVE_GOAL_MANIFEST_PATH,
        expected_sha256=EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
    )
    require_equal(
        errors,
        "checkpoint active Goal manifest SHA-256",
        goal_execution.get("static_plan_manifest_sha256"),
        active_manifest_hash,
    )

    require_equal(
        errors,
        "active Goal manifest package ID",
        active_manifest.get("package_id"),
        EXPECTED_ACTIVE_GOAL_PACKAGE_ID,
    )
    require_equal(
        errors,
        "active Goal manifest plan version",
        active_manifest.get("plan_version"),
        EXPECTED_ACTIVE_GOAL_PLAN_VERSION,
    )
    active_package_prefix = (
        Path(EXPECTED_ACTIVE_GOAL_MANIFEST_PATH).parent.as_posix() + "/"
    )
    validate_protected_goal_package(
        errors,
        label="active v2.2",
        root=root,
        manifest=active_manifest,
        manifest_path=EXPECTED_ACTIVE_GOAL_MANIFEST_PATH,
        expected_package_paths=tuple(
            relative
            for relative in (
                *EXPECTED_GOAL_PACKAGE_PATHS,
                *EXPECTED_CURRENT_DYNAMIC_GOAL_PATHS,
            )
            if relative.startswith(active_package_prefix)
        ),
        expected_protected_file_count=19,
        unprotected_runtime_paths=EXPECTED_CURRENT_DYNAMIC_GOAL_PATHS,
    )

    v21_manifest, _ = load_goal_manifest(
        errors,
        label="superseded v2.1",
        root=root,
        manifest_path=EXPECTED_V21_GOAL_MANIFEST_PATH,
        expected_sha256=EXPECTED_V21_GOAL_MANIFEST_SHA256,
    )
    require_equal(
        errors,
        "superseded v2.1 Goal package ID",
        v21_manifest.get("package_id"),
        EXPECTED_V21_GOAL_PACKAGE_ID,
    )
    require_equal(
        errors,
        "superseded v2.1 Goal plan version",
        v21_manifest.get("plan_version"),
        EXPECTED_V21_GOAL_PLAN_VERSION,
    )
    validate_protected_goal_package(
        errors,
        label="superseded v2.1",
        root=root,
        manifest=v21_manifest,
        manifest_path=EXPECTED_V21_GOAL_MANIFEST_PATH,
        expected_package_paths=EXPECTED_FROZEN_V21_GOAL_PACKAGE_PATHS,
        expected_protected_file_count=20,
        expected_path_set_sha256=EXPECTED_V21_PACKAGE_PATH_SET_SHA256,
        expected_content_set_sha256=EXPECTED_V21_PACKAGE_CONTENT_SET_SHA256,
    )

    v2_manifest, _ = load_goal_manifest(
        errors,
        label="superseded v2.0",
        root=root,
        manifest_path=EXPECTED_V2_GOAL_MANIFEST_PATH,
        expected_sha256=EXPECTED_V2_GOAL_MANIFEST_SHA256,
    )

    require_equal(
        errors,
        "superseded v2.0 Goal package ID",
        v2_manifest.get("package_id"),
        EXPECTED_V2_GOAL_PACKAGE_ID,
    )
    require_equal(
        errors,
        "superseded v2.0 Goal plan version",
        v2_manifest.get("plan_version"),
        EXPECTED_V2_GOAL_PLAN_VERSION,
    )
    validate_protected_goal_package(
        errors,
        label="superseded v2.0",
        root=root,
        manifest=v2_manifest,
        manifest_path=EXPECTED_V2_GOAL_MANIFEST_PATH,
        expected_package_paths=EXPECTED_SUPERSEDED_V2_GOAL_PACKAGE_PATHS,
        expected_protected_file_count=18,
    )

    v1_manifest, _ = load_goal_manifest(
        errors,
        label="superseded v1.1",
        root=root,
        manifest_path=EXPECTED_V1_GOAL_MANIFEST_PATH,
        expected_sha256=EXPECTED_V1_GOAL_MANIFEST_SHA256,
    )
    require_equal(
        errors,
        "superseded v1.1 Goal package ID",
        v1_manifest.get("package_id"),
        EXPECTED_V1_GOAL_PACKAGE_ID,
    )
    require_equal(
        errors,
        "superseded v1.1 Goal plan version",
        v1_manifest.get("plan_version"),
        EXPECTED_V1_GOAL_PLAN_VERSION,
    )
    v1_package_prefix = Path(EXPECTED_V1_GOAL_MANIFEST_PATH).parent.as_posix() + "/"
    validate_protected_goal_package(
        errors,
        label="superseded v1.1",
        root=root,
        manifest=v1_manifest,
        manifest_path=EXPECTED_V1_GOAL_MANIFEST_PATH,
        expected_package_paths=tuple(
            relative
            for relative in EXPECTED_SUPERSEDED_GOAL_PACKAGE_PATHS
            if relative.startswith(v1_package_prefix)
        ),
        expected_protected_file_count=20,
    )

    active_supersedes = active_manifest.get("supersedes")
    if not isinstance(active_supersedes, dict):
        errors.append("active Goal manifest supersedes must be an object")
        active_supersedes = {}
    for label, key, expected in (
        ("package ID", "package_id", EXPECTED_V21_GOAL_PACKAGE_ID),
        ("plan version", "plan_version", EXPECTED_V21_GOAL_PLAN_VERSION),
        ("manifest path", "manifest_path", EXPECTED_V21_GOAL_MANIFEST_PATH),
        (
            "manifest SHA-256",
            "manifest_sha256",
            EXPECTED_V21_GOAL_MANIFEST_SHA256,
        ),
        (
            "prepared event path",
            "initial_transition_event_path",
            EXPECTED_V21_PREPARED_EVENT_PATH,
        ),
        (
            "prepared event SHA-256",
            "initial_transition_event_sha256",
            EXPECTED_V21_PREPARED_EVENT_SHA256,
        ),
    ):
        require_equal(
            errors,
            f"active Goal manifest superseded v2.1 {label}",
            active_supersedes.get(key),
            expected,
        )

    v21_supersedes = v21_manifest.get("supersedes")
    if not isinstance(v21_supersedes, dict):
        errors.append("superseded v2.1 Goal manifest supersedes must be an object")
        v21_supersedes = {}
    for label, key, expected in (
        ("package ID", "package_id", EXPECTED_V2_GOAL_PACKAGE_ID),
        ("plan version", "plan_version", EXPECTED_V2_GOAL_PLAN_VERSION),
        ("manifest path", "manifest_path", EXPECTED_V2_GOAL_MANIFEST_PATH),
        ("manifest SHA-256", "manifest_sha256", EXPECTED_V2_GOAL_MANIFEST_SHA256),
        (
            "prepared event path",
            "initial_transition_event_path",
            EXPECTED_V2_PREPARED_EVENT_PATH,
        ),
        (
            "prepared event SHA-256",
            "initial_transition_event_sha256",
            EXPECTED_V2_PREPARED_EVENT_SHA256,
        ),
    ):
        require_equal(
            errors,
            f"superseded v2.1 Goal manifest superseded v2.0 {label}",
            v21_supersedes.get(key),
            expected,
        )

    v2_supersedes = v2_manifest.get("supersedes")
    if not isinstance(v2_supersedes, dict):
        errors.append("superseded v2.0 Goal manifest supersedes must be an object")
        v2_supersedes = {}
    for label, key, expected in (
        ("package ID", "package_id", EXPECTED_V1_GOAL_PACKAGE_ID),
        ("plan version", "plan_version", EXPECTED_V1_GOAL_PLAN_VERSION),
        (
            "manifest SHA-256",
            "manifest_sha256",
            EXPECTED_V1_GOAL_MANIFEST_SHA256,
        ),
        (
            "prepared event SHA-256",
            "initial_transition_event_sha256",
            EXPECTED_V1_PREPARED_EVENT_SHA256,
        ),
    ):
        require_equal(
            errors,
            f"superseded v2.0 Goal manifest superseded v1.1 {label}",
            v2_supersedes.get(key),
            expected,
        )

    load_prepared_event(
        errors,
        label="superseded v2.1",
        root=root,
        event_path=EXPECTED_V21_PREPARED_EVENT_PATH,
        expected_event_sha256=EXPECTED_V21_PREPARED_EVENT_SHA256,
        expected_manifest_sha256=EXPECTED_V21_GOAL_MANIFEST_SHA256,
        expected_supersedes_event_sha256=EXPECTED_V2_PREPARED_EVENT_SHA256,
    )
    load_prepared_event(
        errors,
        label="superseded v2.0",
        root=root,
        event_path=EXPECTED_V2_PREPARED_EVENT_PATH,
        expected_event_sha256=EXPECTED_V2_PREPARED_EVENT_SHA256,
        expected_manifest_sha256=EXPECTED_V2_GOAL_MANIFEST_SHA256,
        expected_supersedes_event_sha256=EXPECTED_V1_PREPARED_EVENT_SHA256,
    )

    transition_history = goal_execution.get("transition_history")
    if not isinstance(transition_history, list) or not transition_history:
        errors.append("active Goal transition history must contain its initial event")
    else:
        initial_event = transition_history[0]
        if not isinstance(initial_event, dict):
            errors.append("active Goal initial transition event must be an object")
        else:
            if not object_seal_is_valid(initial_event, "event_sha256"):
                errors.append("active Goal initial transition event seal is invalid")
            require_equal(
                errors,
                "active Goal initial event manifest SHA-256",
                initial_event.get("static_plan_manifest_sha256"),
                active_manifest_hash,
            )
            require_equal(
                errors,
                "active Goal initial event supersedes v2.1 event",
                initial_event.get("supersedes_event_sha256"),
                EXPECTED_V21_PREPARED_EVENT_SHA256,
            )
            if EXPECTED_GOAL_INITIAL_EVENT_SHA256 == "0" * 64:
                errors.append(
                    "active Goal initial event SHA-256 trust anchor is not finalized"
                )
            require_equal(
                errors,
                "active Goal initial event SHA-256 trust anchor",
                initial_event.get("event_sha256"),
                EXPECTED_GOAL_INITIAL_EVENT_SHA256,
            )


def current_branch(root: Path) -> str | None:
    completed = subprocess.run(
        ["git", "branch", "--show-current"],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        return None
    return completed.stdout.strip()


def current_head(root: Path) -> str | None:
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        return None
    return completed.stdout.strip()


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_json_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def validate_gate_event_id(gate_event_id: Any) -> str:
    if (
        not isinstance(gate_event_id, str)
        or not GATE_EVENT_ID_PATTERN.fullmatch(gate_event_id)
        or gate_event_id in {".", ".."}
    ):
        raise ValueError(
            "gate event ID must be 1-128 ASCII letters, digits, dots, "
            "underscores, or hyphens and start with a letter or digit"
        )
    return gate_event_id


def _run_git_bytes(
    root: Path,
    arguments: list[str],
    *,
    accepted_returncodes: tuple[int, ...] = (0,),
    config_overrides: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[bytes]:
    environment = os.environ.copy()
    environment["LC_ALL"] = "C"
    environment["GIT_OPTIONAL_LOCKS"] = "0"
    if config_overrides:
        environment["GIT_CONFIG_COUNT"] = str(len(config_overrides))
        for index, (key, value) in enumerate(sorted(config_overrides.items())):
            environment[f"GIT_CONFIG_KEY_{index}"] = key
            environment[f"GIT_CONFIG_VALUE_{index}"] = value
    completed = subprocess.run(
        ["git", *arguments],
        cwd=root,
        check=False,
        capture_output=True,
        env=environment,
    )
    if completed.returncode not in accepted_returncodes:
        raise ValueError(
            f"git command failed safely with exit code {completed.returncode}"
        )
    return completed


def _reject_gate_git_environment_overrides() -> None:
    active = sorted(
        name
        for name in os.environ
        if name in DISALLOWED_GATE_GIT_ENVIRONMENT
        or name.startswith("GIT_CONFIG_KEY_")
        or name.startswith("GIT_CONFIG_VALUE_")
    )
    if active:
        raise ValueError(
            "gate repository state rejects Git repository/index/config "
            f"environment overrides: {active}"
        )


def _decode_git_path(raw_path: bytes) -> str:
    try:
        relative = raw_path.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ValueError("Git-visible path is not valid UTF-8") from exc
    relative_path = Path(relative)
    if (
        not relative
        or relative_path.is_absolute()
        or relative_path.parts in {(), (".",)}
        or any(part in {"", ".", ".."} for part in relative_path.parts)
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in relative)
    ):
        raise ValueError("Git-visible path is unsafe")
    return relative


def _validate_git_mode(value: str) -> None:
    if re.fullmatch(r"[0-7]{6}", value) is None:
        raise ValueError("malformed Git mode in porcelain-v2 status")
    if value == "160000":
        raise ValueError("submodule paths are not permitted in gate repository state")


def _validate_git_object_id(value: str) -> None:
    if re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", value) is None:
        raise ValueError("malformed Git object ID in porcelain-v2 status")


def _decode_ascii_field(value: bytes, label: str) -> str:
    try:
        return value.decode("ascii", errors="strict")
    except UnicodeDecodeError as exc:
        raise ValueError(f"non-ASCII {label} in porcelain-v2 status") from exc


def _validate_xy(value: str) -> None:
    if (
        len(value) != 2
        or value[0] not in ".MADRCUT"
        or value[1] not in ".MADRCUT"
    ):
        raise ValueError("malformed XY state in porcelain-v2 status")


def _validate_non_submodule(value: str) -> None:
    if value != "N...":
        raise ValueError("submodule paths are not permitted in gate repository state")


def _ordinary_status_identity(fields: list[bytes]) -> dict[str, Any]:
    xy = _decode_ascii_field(fields[1], "XY state")
    submodule = _decode_ascii_field(fields[2], "submodule state")
    head_mode = _decode_ascii_field(fields[3], "HEAD mode")
    index_mode = _decode_ascii_field(fields[4], "index mode")
    worktree_mode = _decode_ascii_field(fields[5], "worktree mode")
    head_object_id = _decode_ascii_field(fields[6], "HEAD object ID")
    index_object_id = _decode_ascii_field(fields[7], "index object ID")
    _validate_xy(xy)
    _validate_non_submodule(submodule)
    for mode in (head_mode, index_mode, worktree_mode):
        _validate_git_mode(mode)
    for object_id in (head_object_id, index_object_id):
        _validate_git_object_id(object_id)
    return {
        "kind": "ORDINARY",
        "xy": xy,
        "index_code": xy[0],
        "worktree_code": xy[1],
        "submodule": submodule,
        "head_mode": head_mode,
        "index_mode": index_mode,
        "worktree_mode": worktree_mode,
        "head_object_id": head_object_id,
        "index_object_id": index_object_id,
        "rename_or_copy_score": None,
    }


def _rename_status_identity(fields: list[bytes]) -> dict[str, Any]:
    identity = _ordinary_status_identity(fields[:8])
    score = _decode_ascii_field(fields[8], "rename/copy score")
    if re.fullmatch(r"[RC][0-9]{1,3}", score) is None:
        raise ValueError("malformed rename/copy score in porcelain-v2 status")
    identity["kind"] = "RENAMED_OR_COPIED"
    identity["rename_or_copy_score"] = score
    return identity


def _unmerged_status_identity(fields: list[bytes]) -> dict[str, Any]:
    xy = _decode_ascii_field(fields[1], "XY state")
    submodule = _decode_ascii_field(fields[2], "submodule state")
    modes = [
        _decode_ascii_field(value, "unmerged mode")
        for value in fields[3:7]
    ]
    object_ids = [
        _decode_ascii_field(value, "unmerged object ID")
        for value in fields[7:10]
    ]
    _validate_xy(xy)
    _validate_non_submodule(submodule)
    for mode in modes:
        _validate_git_mode(mode)
    for object_id in object_ids:
        _validate_git_object_id(object_id)
    return {
        "kind": "UNMERGED",
        "xy": xy,
        "index_code": xy[0],
        "worktree_code": xy[1],
        "submodule": submodule,
        "stage1_mode": modes[0],
        "stage2_mode": modes[1],
        "stage3_mode": modes[2],
        "worktree_mode": modes[3],
        "stage1_object_id": object_ids[0],
        "stage2_object_id": object_ids[1],
        "stage3_object_id": object_ids[2],
        "rename_or_copy_score": None,
    }


def parse_git_status_porcelain_v2(raw_status: bytes) -> list[dict[str, Any]]:
    if not raw_status:
        return []
    fields = raw_status.split(b"\0")
    if fields[-1] != b"":
        raise ValueError("porcelain-v2 -z output is not NUL terminated")
    records: list[dict[str, Any]] = []
    field_index = 0
    while field_index < len(fields) - 1:
        raw_record = fields[field_index]
        field_index += 1
        if raw_record.startswith(b"1 "):
            parts = raw_record.split(b" ", 8)
            if len(parts) != 9:
                raise ValueError("malformed ordinary porcelain-v2 record")
            relative = _decode_git_path(parts[8])
            records.append(
                {
                    "status": _ordinary_status_identity(parts),
                    "_raw_bytes": raw_record + b"\0",
                    "paths": [
                        {
                            "path": relative,
                            "path_role": "CURRENT",
                            "counterpart_path": None,
                        }
                    ],
                }
            )
        elif raw_record.startswith(b"2 "):
            parts = raw_record.split(b" ", 9)
            if len(parts) != 10 or field_index >= len(fields) - 1:
                raise ValueError("malformed rename/copy porcelain-v2 record")
            destination = _decode_git_path(parts[9])
            source = _decode_git_path(fields[field_index])
            field_index += 1
            if destination == source:
                raise ValueError("rename/copy porcelain-v2 paths must differ")
            identity = _rename_status_identity(parts)
            source_role = (
                "RENAME_SOURCE"
                if identity["rename_or_copy_score"].startswith("R")
                else "COPY_SOURCE"
            )
            records.append(
                {
                    "status": identity,
                    "_raw_bytes": (
                        raw_record + b"\0" + fields[field_index - 1] + b"\0"
                    ),
                    "paths": [
                        {
                            "path": destination,
                            "path_role": "DESTINATION",
                            "counterpart_path": source,
                        },
                        {
                            "path": source,
                            "path_role": source_role,
                            "counterpart_path": destination,
                        },
                    ],
                }
            )
        elif raw_record.startswith(b"u "):
            parts = raw_record.split(b" ", 10)
            if len(parts) != 11:
                raise ValueError("malformed unmerged porcelain-v2 record")
            relative = _decode_git_path(parts[10])
            records.append(
                {
                    "status": _unmerged_status_identity(parts),
                    "_raw_bytes": raw_record + b"\0",
                    "paths": [
                        {
                            "path": relative,
                            "path_role": "CURRENT",
                            "counterpart_path": None,
                        }
                    ],
                }
            )
        elif raw_record.startswith(b"? "):
            relative = _decode_git_path(raw_record[2:])
            records.append(
                {
                    "status": {
                        "kind": "UNTRACKED",
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
                    },
                    "_raw_bytes": raw_record + b"\0",
                    "paths": [
                        {
                            "path": relative,
                            "path_role": "CURRENT",
                            "counterpart_path": None,
                        }
                    ],
                }
            )
        else:
            raise ValueError("unsupported porcelain-v2 status record")
    return records


def _parse_index_entries(
    raw_index: bytes,
    selected_paths: set[str],
) -> dict[str, list[dict[str, Any]]]:
    selected_raw_paths = {
        relative.encode("utf-8"): relative
        for relative in selected_paths
    }
    result = {relative: [] for relative in selected_paths}
    if not raw_index:
        return result
    records = raw_index.split(b"\0")
    if records[-1] != b"":
        raise ValueError("git ls-files -z output is not NUL terminated")
    for raw_record in records[:-1]:
        try:
            raw_identity, raw_path = raw_record.split(b"\t", 1)
        except ValueError as exc:
            raise ValueError("malformed git index record") from exc
        relative = selected_raw_paths.get(raw_path)
        if relative is None:
            continue
        identity_fields = raw_identity.split(b" ")
        if len(identity_fields) != 3:
            raise ValueError("malformed selected-path git index identity")
        mode = _decode_ascii_field(identity_fields[0], "index mode")
        object_id = _decode_ascii_field(identity_fields[1], "index object ID")
        stage_text = _decode_ascii_field(identity_fields[2], "index stage")
        _validate_git_mode(mode)
        _validate_git_object_id(object_id)
        if stage_text not in {"0", "1", "2", "3"}:
            raise ValueError("malformed selected-path git index stage")
        result[relative].append(
            {
                "mode": mode,
                "object_id": object_id,
                "stage": int(stage_text),
            }
        )
    for relative, identities in result.items():
        identities.sort(
            key=lambda item: (
                item["stage"],
                item["mode"],
                item["object_id"],
            )
        )
        stages = [item["stage"] for item in identities]
        if len(stages) != len(set(stages)):
            raise ValueError(f"duplicate git index stage for path: {relative}")
        if 0 in stages and len(stages) != 1:
            raise ValueError(f"mixed stage-zero and unmerged index entries: {relative}")
    return result


def _reject_hidden_index_flags(raw_flags: bytes) -> None:
    if not raw_flags:
        return
    records = raw_flags.split(b"\0")
    if records[-1] != b"":
        raise ValueError("git ls-files -v -z output is not NUL terminated")
    for record in records[:-1]:
        if len(record) < 3 or record[1:2] != b" ":
            raise ValueError("malformed git index flag record")
        tag = record[:1]
        if tag == b"S" or (b"a" <= tag <= b"z"):
            raise ValueError(
                "assume-unchanged or skip-worktree index flags are not "
                "permitted in gate repository state"
            )


def _reject_nonignored_special_files(root: Path) -> None:
    resolved_root = root.resolve(strict=True)
    pending_directories = [resolved_root]
    while pending_directories:
        directory = pending_directories.pop()
        with os.scandir(directory) as entries:
            for entry in entries:
                if directory == resolved_root and entry.name == ".git":
                    continue
                try:
                    entry_stat = entry.stat(follow_symlinks=False)
                except FileNotFoundError:
                    continue
                if stat.S_ISDIR(entry_stat.st_mode):
                    pending_directories.append(Path(entry.path))
                    continue
                if stat.S_ISREG(entry_stat.st_mode) or stat.S_ISLNK(
                    entry_stat.st_mode
                ):
                    continue
                try:
                    relative_bytes = os.path.relpath(
                        os.fsencode(entry.path),
                        os.fsencode(resolved_root),
                    )
                except (OSError, ValueError) as exc:
                    raise ValueError(
                        "special worktree path cannot be normalized"
                    ) from exc
                relative = _decode_git_path(relative_bytes)
                ignored = _run_git_bytes(
                    resolved_root,
                    [
                        "check-ignore",
                        "--quiet",
                        "--no-index",
                        "--",
                        relative,
                    ],
                    accepted_returncodes=(0, 1),
                )
                if ignored.returncode == 1:
                    raise ValueError(
                        "nonignored Git worktree special file is not permitted"
                    )


def _repo_relative_parts(relative: str) -> tuple[str, ...]:
    relative_path = Path(relative)
    if (
        relative_path.is_absolute()
        or not relative_path.parts
        or any(part in {"", ".", ".."} for part in relative_path.parts)
    ):
        raise ValueError("Git-visible path is unsafe")
    return relative_path.parts


def _open_repo_parent_directory(
    root: Path,
    relative: str,
) -> tuple[int | None, str]:
    parts = _repo_relative_parts(relative)
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    if hasattr(os, "O_CLOEXEC"):
        directory_flags |= os.O_CLOEXEC
    current_descriptor = os.open(root.resolve(strict=True), directory_flags)
    for part in parts[:-1]:
        try:
            next_descriptor = os.open(
                part,
                directory_flags,
                dir_fd=current_descriptor,
            )
        except FileNotFoundError:
            os.close(current_descriptor)
            return None, parts[-1]
        except OSError as exc:
            os.close(current_descriptor)
            raise ValueError(
                "Git-visible path has an unsafe parent component"
            ) from exc
        os.close(current_descriptor)
        current_descriptor = next_descriptor
    return current_descriptor, parts[-1]


def _filesystem_mode(file_stat: os.stat_result) -> str:
    return f"{file_stat.st_mode & 0o177777:06o}"


_STABLE_STAT_FIELDS = (
    "st_dev",
    "st_ino",
    "st_mode",
    "st_size",
    "st_mtime_ns",
    "st_ctime_ns",
)


def _same_file_stat(left: os.stat_result, right: os.stat_result) -> bool:
    return all(
        getattr(left, field) == getattr(right, field)
        for field in _STABLE_STAT_FIELDS
    )


def _stable_regular_file_identity(
    parent_descriptor: int,
    name: str,
    initial_stat: os.stat_result,
) -> dict[str, Any]:
    file_flags = os.O_RDONLY | os.O_NOFOLLOW
    if hasattr(os, "O_CLOEXEC"):
        file_flags |= os.O_CLOEXEC
    try:
        descriptor = os.open(
            name,
            file_flags,
            dir_fd=parent_descriptor,
        )
    except OSError as exc:
        raise ValueError(
            "Git-visible regular file cannot be opened without symlinks"
        ) from exc
    try:
        before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(before.st_mode)
            or not _same_file_stat(initial_stat, before)
        ):
            raise ValueError("Git-visible worktree path is not a regular file")
        digest = hashlib.sha256()
        byte_count = 0
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            byte_count += len(chunk)
        after = os.fstat(descriptor)
        try:
            final_path_stat = os.stat(
                name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
        except FileNotFoundError as exc:
            raise ValueError(
                "Git-visible regular file changed during capture"
            ) from exc
    finally:
        os.close(descriptor)
    if (
        not _same_file_stat(before, after)
        or not _same_file_stat(after, final_path_stat)
    ):
        raise ValueError("Git-visible regular file changed during capture")
    if byte_count != after.st_size:
        raise ValueError("Git-visible regular file size changed during capture")
    return {
        "state": "PRESENT",
        "type": "REGULAR_FILE",
        "mode": _filesystem_mode(after),
        "byte_count": byte_count,
        "sha256": digest.hexdigest(),
        "deletion_marker": None,
        "symlink_target_sha256": None,
    }


def _worktree_identity(root: Path, relative: str) -> dict[str, Any]:
    parent_descriptor, name = _open_repo_parent_directory(root, relative)
    if parent_descriptor is None:
        return {
            "state": "ABSENT",
            "type": "DELETION_MARKER",
            "mode": "000000",
            "byte_count": 0,
            "sha256": None,
            "deletion_marker": "WORKTREE_PATH_ABSENT",
            "symlink_target_sha256": None,
        }
    try:
        try:
            before = os.stat(
                name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            return {
                "state": "ABSENT",
                "type": "DELETION_MARKER",
                "mode": "000000",
                "byte_count": 0,
                "sha256": None,
                "deletion_marker": "WORKTREE_PATH_ABSENT",
                "symlink_target_sha256": None,
            }
        if stat.S_ISREG(before.st_mode):
            return _stable_regular_file_identity(
                parent_descriptor,
                name,
                before,
            )
        if stat.S_ISLNK(before.st_mode):
            target = os.readlink(name, dir_fd=parent_descriptor)
            after = os.stat(
                name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
            if not _same_file_stat(before, after):
                raise ValueError("Git-visible symlink changed during capture")
            target_bytes = os.fsencode(target)
            target_sha256 = hashlib.sha256(target_bytes).hexdigest()
            return {
                "state": "PRESENT",
                "type": "SYMLINK",
                "mode": _filesystem_mode(after),
                "byte_count": len(target_bytes),
                "sha256": target_sha256,
                "deletion_marker": None,
                "symlink_target_sha256": target_sha256,
            }
        if stat.S_ISDIR(before.st_mode):
            raise ValueError(
                "Git-visible worktree directory is not an expanded file path"
            )
        raise ValueError("Git-visible worktree special file is not permitted")
    finally:
        os.close(parent_descriptor)


def _validate_excluded_worktree_path(root: Path, relative: str) -> None:
    parent_descriptor, name = _open_repo_parent_directory(root, relative)
    if parent_descriptor is None:
        return
    try:
        try:
            file_stat = os.stat(
                name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            return
        if stat.S_ISREG(file_stat.st_mode) or stat.S_ISLNK(file_stat.st_mode):
            return
        if stat.S_ISDIR(file_stat.st_mode):
            raise ValueError(
                "excluded Git-visible directory is not an expanded file path"
            )
        raise ValueError("excluded Git-visible special file is not permitted")
    finally:
        os.close(parent_descriptor)


def _gate_exclusion_kind(relative: str, gate_event_id: str) -> str | None:
    gate_prefix = f"docs/control/execution/goal-gates/{gate_event_id}/"
    if relative == GATE_REPOSITORY_CHECKPOINT_PATH:
        return "CHECKPOINT_EXACT_PATH"
    if relative.startswith(gate_prefix):
        return "GATE_EVENT_EXACT_PREFIX"
    return None


def _checkpoint_controlled_snapshot_summary(
    root: Path,
    checkpoint_path: Path,
) -> tuple[dict[str, Any], bytes, str]:
    expected = (root / GATE_REPOSITORY_CHECKPOINT_PATH).resolve(strict=True)
    if checkpoint_path.resolve(strict=True) != expected:
        raise ValueError(
            "gate repository state requires the canonical continuation checkpoint"
        )
    if resolve_safe_repo_file(root, GATE_REPOSITORY_CHECKPOINT_PATH) is None:
        raise ValueError("canonical continuation checkpoint is unsafe or missing")
    first_bytes = expected.read_bytes()
    second_bytes = expected.read_bytes()
    if first_bytes != second_bytes:
        raise ValueError("canonical continuation checkpoint changed during capture")
    try:
        checkpoint = json.loads(first_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("canonical continuation checkpoint is invalid JSON") from exc
    if not isinstance(checkpoint, dict):
        raise ValueError("canonical continuation checkpoint root must be an object")
    snapshot = checkpoint.get("working_tree_snapshot")
    if not isinstance(snapshot, dict):
        raise ValueError("checkpoint working_tree_snapshot must be an object")
    handoff = checkpoint.get("session_handoff")
    if not isinstance(handoff, dict):
        raise ValueError("checkpoint session_handoff must be an object")
    source_snapshot = handoff.get("source_commit_or_snapshot")
    if not isinstance(source_snapshot, dict):
        raise ValueError(
            "checkpoint handoff source_commit_or_snapshot must be an object"
        )
    handoff_current_head = source_snapshot.get("current_head")
    if (
        not isinstance(handoff_current_head, str)
        or re.fullmatch(
            r"(?:[0-9a-f]{40}|[0-9a-f]{64})",
            handoff_current_head,
        )
        is None
    ):
        raise ValueError("checkpoint handoff current HEAD is invalid")
    managed_paths = snapshot.get("managed_changed_paths")
    managed_count = snapshot.get("managed_changed_path_count")
    path_set_sha256 = snapshot.get("path_set_sha256")
    content_set_sha256 = snapshot.get("content_set_sha256")
    base_head = snapshot.get("base_head")
    if (
        not isinstance(managed_paths, list)
        or not all(isinstance(path, str) for path in managed_paths)
        or managed_paths != sorted(set(managed_paths))
        or type(managed_count) is not int
        or managed_count != len(managed_paths)
        or not isinstance(base_head, str)
        or re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", base_head) is None
        or not isinstance(path_set_sha256, str)
        or re.fullmatch(r"[0-9a-f]{64}", path_set_sha256) is None
        or not isinstance(content_set_sha256, str)
        or re.fullmatch(r"[0-9a-f]{64}", content_set_sha256) is None
    ):
        raise ValueError("checkpoint controlled working snapshot summary is malformed")
    try:
        normalized_managed_paths = [
            _decode_git_path(relative.encode("utf-8", errors="strict"))
            for relative in managed_paths
        ]
    except UnicodeEncodeError as exc:
        raise ValueError(
            "checkpoint controlled working snapshot contains an unsafe path"
        ) from exc
    if normalized_managed_paths != managed_paths:
        raise ValueError(
            "checkpoint controlled working snapshot contains an unsafe path"
        )
    expected_path_hash = hashlib.sha256(
        ("\n".join(managed_paths) + "\n").encode("utf-8")
    ).hexdigest()
    if path_set_sha256 != expected_path_hash:
        raise ValueError("checkpoint controlled path-set hash is invalid")
    try:
        live_path_hash, live_content_hash = working_snapshot_hashes(
            root,
            managed_paths,
        )
    except (OSError, RuntimeError, ValueError) as exc:
        raise ValueError(
            "checkpoint controlled working snapshot cannot be reproduced"
        ) from exc
    if (
        live_path_hash != path_set_sha256
        or live_content_hash != content_set_sha256
    ):
        raise ValueError(
            "checkpoint controlled working snapshot does not match live bytes"
        )
    return (
        {
            "base_head": snapshot.get("base_head"),
            "managed_changed_path_count": managed_count,
            "path_set_sha256": path_set_sha256,
            "content_set_sha256": content_set_sha256,
        },
        first_bytes,
        handoff_current_head,
    )


def capture_gate_repository_state(
    root: Path,
    checkpoint_path: Path,
    gate_event_id: str,
) -> dict[str, Any]:
    event_id = validate_gate_event_id(gate_event_id)
    _reject_gate_git_environment_overrides()
    resolved_root = root.resolve(strict=True)
    top_level_raw = _run_git_bytes(
        resolved_root,
        ["rev-parse", "--show-toplevel"],
    ).stdout.rstrip(b"\n")
    try:
        top_level = Path(os.fsdecode(top_level_raw)).resolve(strict=True)
    except (OSError, RuntimeError, ValueError) as exc:
        raise ValueError("Git repository top level cannot be resolved") from exc
    if top_level != resolved_root:
        raise ValueError("gate repository state root must be the Git top level")
    _reject_nonignored_special_files(resolved_root)

    (
        checkpoint_summary,
        checkpoint_bytes,
        checkpoint_handoff_current_head,
    ) = _checkpoint_controlled_snapshot_summary(
        resolved_root,
        checkpoint_path,
    )

    head_raw = _run_git_bytes(
        resolved_root,
        ["rev-parse", "--verify", "HEAD^{commit}"],
    ).stdout.strip()
    head_commit = _decode_ascii_field(head_raw, "HEAD commit")
    _validate_git_object_id(head_commit)
    ancestor = is_commit_ancestor(
        resolved_root,
        checkpoint_summary["base_head"],
        head_commit,
    )
    if ancestor is None:
        raise ValueError(
            "checkpoint controlled snapshot base HEAD cannot be resolved"
        )
    if not ancestor:
        raise ValueError(
            "checkpoint controlled snapshot base HEAD is not an ancestor of "
            "current HEAD"
        )
    if checkpoint_handoff_current_head != head_commit:
        raise ValueError(
            "checkpoint handoff current HEAD does not match current HEAD"
        )
    object_format = _decode_ascii_field(
        _run_git_bytes(
            resolved_root,
            ["rev-parse", "--show-object-format"],
        ).stdout.strip(),
        "Git object format",
    )
    if object_format not in {"sha1", "sha256"}:
        raise ValueError("unsupported Git object format")
    branch_result = _run_git_bytes(
        resolved_root,
        ["symbolic-ref", "--quiet", "--short", "HEAD"],
        accepted_returncodes=(0, 1),
    )
    branch = (
        _decode_git_path(branch_result.stdout.rstrip(b"\n"))
        if branch_result.returncode == 0
        else None
    )

    status_arguments = list(GIT_STATUS_PORCELAIN_V2_COMMAND[1:])
    raw_status = _run_git_bytes(
        resolved_root,
        status_arguments,
        config_overrides=GIT_STATUS_CONFIG_OVERRIDES,
    ).stdout
    raw_index = _run_git_bytes(
        resolved_root,
        ["ls-files", "--stage", "-z"],
    ).stdout
    raw_index_flags = _run_git_bytes(
        resolved_root,
        ["ls-files", "-v", "-z"],
    ).stdout
    _reject_hidden_index_flags(raw_index_flags)
    status_records = parse_git_status_porcelain_v2(raw_status)

    all_paths = {
        path_entry["path"]
        for record in status_records
        for path_entry in record["paths"]
    }
    index_entries = _parse_index_entries(raw_index, all_paths)
    included_entries: list[dict[str, Any]] = []
    included_raw_status = bytearray()
    included_status_record_count = 0
    observed_paths: set[str] = set()

    for record in status_records:
        record_exclusions = {
            _gate_exclusion_kind(path_entry["path"], event_id)
            for path_entry in record["paths"]
        }
        if len(record_exclusions) > 1:
            raise ValueError(
                "rename/copy status crosses a transaction exclusion boundary"
            )
        if None not in record_exclusions:
            for path_entry in record["paths"]:
                relative = path_entry["path"]
                _validate_excluded_worktree_path(resolved_root, relative)
            continue
        included_raw_status.extend(record["_raw_bytes"])
        included_status_record_count += 1
        for path_entry in record["paths"]:
            relative = path_entry["path"]
            if relative in observed_paths:
                raise ValueError(
                    f"Git-visible path has ambiguous status records: {relative}"
                )
            observed_paths.add(relative)
            included_entries.append(
                {
                    "path": relative,
                    "path_role": path_entry["path_role"],
                    "counterpart_path": path_entry["counterpart_path"],
                    "status": record["status"],
                    "index_entries": index_entries[relative],
                    "worktree": _worktree_identity(resolved_root, relative),
                }
            )

    included_entries.sort(key=lambda item: item["path"])
    dirty_paths = [entry["path"] for entry in included_entries]
    path_set_sha256 = canonical_json_sha256(dirty_paths)
    content_set_sha256 = canonical_json_sha256(
        [
            {
                "path": entry["path"],
                "worktree": entry["worktree"],
            }
            for entry in included_entries
        ]
    )
    index_state_sha256 = canonical_json_sha256(
        [
            {
                "path": entry["path"],
                "path_role": entry["path_role"],
                "counterpart_path": entry["counterpart_path"],
                "status": entry["status"],
                "index_entries": entry["index_entries"],
            }
            for entry in included_entries
        ]
    )

    ending_head = _run_git_bytes(
        resolved_root,
        ["rev-parse", "--verify", "HEAD^{commit}"],
    ).stdout.strip()
    ending_branch_result = _run_git_bytes(
        resolved_root,
        ["symbolic-ref", "--quiet", "--short", "HEAD"],
        accepted_returncodes=(0, 1),
    )
    ending_status = _run_git_bytes(
        resolved_root,
        status_arguments,
        config_overrides=GIT_STATUS_CONFIG_OVERRIDES,
    ).stdout
    ending_index = _run_git_bytes(
        resolved_root,
        ["ls-files", "--stage", "-z"],
    ).stdout
    ending_index_flags = _run_git_bytes(
        resolved_root,
        ["ls-files", "-v", "-z"],
    ).stdout
    ending_checkpoint_bytes = (
        resolved_root / GATE_REPOSITORY_CHECKPOINT_PATH
    ).read_bytes()
    if (
        ending_head != head_raw
        or ending_branch_result.returncode != branch_result.returncode
        or ending_branch_result.stdout != branch_result.stdout
        or ending_status != raw_status
        or ending_index != raw_index
        or ending_index_flags != raw_index_flags
        or ending_checkpoint_bytes != checkpoint_bytes
    ):
        raise ValueError("repository changed during gate state capture")
    for entry in included_entries:
        if (
            _worktree_identity(resolved_root, entry["path"])
            != entry["worktree"]
        ):
            raise ValueError(
                "Git-visible worktree content changed during gate state capture"
            )
    _reject_nonignored_special_files(resolved_root)

    gate_prefix = f"docs/control/execution/goal-gates/{event_id}/"
    return {
        "schema_version": GATE_REPOSITORY_STATE_SCHEMA_VERSION,
        "evidence_type": GATE_REPOSITORY_STATE_EVIDENCE_TYPE,
        "gate_event_id": event_id,
        "canonicalization": {
            "encoding": "UTF-8",
            "json": "sort_keys=true,separators=(',',':'),ensure_ascii=false",
            "trailing_newline_in_cli_output": True,
        },
        "repository": {
            "root": ".",
            "head_commit": head_commit,
            "branch": branch,
            "object_format": object_format,
        },
        "git_status_raw": {
            "command": list(GIT_STATUS_PORCELAIN_V2_COMMAND),
            "config_overrides": dict(sorted(GIT_STATUS_CONFIG_OVERRIDES.items())),
            "scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
            "sha256": hashlib.sha256(included_raw_status).hexdigest(),
            "byte_count": len(included_raw_status),
            "record_count": included_status_record_count,
        },
        "transaction_exclusions": {
            "allowed_rule_count": 2,
            "checkpoint_exact_path": GATE_REPOSITORY_CHECKPOINT_PATH,
            "gate_event_exact_prefix": gate_prefix,
        },
        "dirty_snapshot": {
            "dirty_path_count": len(dirty_paths),
            "path_set_sha256": path_set_sha256,
            "content_set_sha256": content_set_sha256,
            "index_state_sha256": index_state_sha256,
            "paths": included_entries,
        },
        "checkpoint_controlled_working_snapshot": checkpoint_summary,
    }


def is_commit_ancestor(root: Path, ancestor: str, descendant: str) -> bool | None:
    completed = subprocess.run(
        ["git", "merge-base", "--is-ancestor", ancestor, descendant],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode == 0:
        return True
    if completed.returncode == 1:
        return False
    return None


def json_path_value(document: dict[str, Any], dotted_path: str) -> Any:
    value: Any = document
    for part in dotted_path.split("."):
        if not isinstance(value, dict) or part not in value:
            raise KeyError(dotted_path)
        value = value[part]
    return value


def working_snapshot_hashes(root: Path, paths: list[str]) -> tuple[str, str]:
    normalized = sorted(paths)
    path_digest = hashlib.sha256(("\n".join(normalized) + "\n").encode("utf-8")).hexdigest()
    content_digest = hashlib.sha256()
    for relative in normalized:
        path = resolve_safe_repo_file(root, relative)
        if path is None:
            raise ValueError(f"unsafe or missing working snapshot path: {relative}")
        content_digest.update(relative.encode("utf-8"))
        content_digest.update(b"\0")
        content_digest.update(sha256_file(path).encode("ascii"))
        content_digest.update(b"\n")
    return path_digest, content_digest.hexdigest()


def require_equal(errors: list[str], label: str, actual: Any, expected: Any) -> None:
    if actual != expected:
        errors.append(f"{label}: expected {expected!r}, got {actual!r}")


def require_object(
    errors: list[str],
    label: str,
    value: Any,
) -> dict[str, Any]:
    if not isinstance(value, dict):
        errors.append(f"{label} must be an object")
        return {}
    return value


def is_string_list(value: Any) -> bool:
    return isinstance(value, list) and all(isinstance(item, str) for item in value)


def validate(checkpoint_path: Path = DEFAULT_CHECKPOINT, root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    try:
        checkpoint = load_json(checkpoint_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"checkpoint cannot be loaded: {exc}"]

    metadata = require_object(errors, "checkpoint metadata", checkpoint.get("metadata"))
    approved = require_object(
        errors,
        "checkpoint approved_state",
        checkpoint.get("approved_state"),
    )
    gap_snapshot = require_object(
        errors,
        "checkpoint implementation_gap_snapshot",
        checkpoint.get("implementation_gap_snapshot"),
    )
    repository = require_object(
        errors,
        "checkpoint repository",
        checkpoint.get("repository"),
    )
    current_work = require_object(
        errors,
        "checkpoint current_work",
        checkpoint.get("current_work"),
    )
    verification_boundary = require_object(
        errors,
        "checkpoint verification_boundary",
        checkpoint.get("verification_boundary"),
    )
    snapshot = require_object(
        errors,
        "checkpoint working_tree_snapshot",
        checkpoint.get("working_tree_snapshot"),
    )
    handoff = require_object(
        errors,
        "checkpoint session_handoff",
        checkpoint.get("session_handoff"),
    )
    goal_execution = require_object(
        errors,
        "checkpoint goal_execution",
        checkpoint.get("goal_execution"),
    )
    canonical_bindings = checkpoint.get("canonical_bindings")
    if not isinstance(canonical_bindings, list):
        errors.append("checkpoint canonical_bindings must be a list")

    if errors:
        return errors

    required_string_fields = [
        ("checkpoint metadata version", metadata.get("version")),
        ("checkpoint metadata as_of", metadata.get("as_of")),
        ("checkpoint metadata status", metadata.get("status")),
        ("checkpoint metadata runbook_path", metadata.get("runbook_path")),
        ("approved policy_baseline_id", approved.get("policy_baseline_id")),
        ("approved policy_baseline_version", approved.get("policy_baseline_version")),
        ("approved application_receipt_id", approved.get("application_receipt_id")),
        ("approved application_id", approved.get("application_id")),
        ("repository root", repository.get("root")),
        ("repository branch", repository.get("branch")),
        ("repository snapshot_base_head", repository.get("snapshot_base_head")),
        ("current_work epic_id", current_work.get("epic_id")),
        ("current_work work_item_id", current_work.get("work_item_id")),
        ("current_work status_scope", current_work.get("status_scope")),
        ("current_work scope_kind", current_work.get("scope_kind")),
        (
            "current_work work_item_id_semantics",
            current_work.get("work_item_id_semantics"),
        ),
        (
            "current_work source_policy_ids_semantics",
            current_work.get("source_policy_ids_semantics"),
        ),
        (
            "current_work gap_ids_semantics",
            current_work.get("gap_ids_semantics"),
        ),
    ]
    required_string_fields.extend(
        (f"goal_execution {field}", goal_execution.get(field))
        for field in (
            "package_id",
            "schema_version",
            "package_status",
            "activation_status",
            "goal_status",
            "master_goal_id",
            "graph_model",
            "focus_goal_id",
            "focus_goal_path",
            "focus_work_item_id",
            "focus_source",
            "selection_policy",
            "transition_commit_policy",
            "transition_history_assurance",
            "validation_cutoff_at",
            "static_plan_manifest_path",
            "static_plan_manifest_sha256",
            "static_plan_version",
        )
    )
    for label, value in required_string_fields:
        if not isinstance(value, str):
            errors.append(f"{label} must be a string")

    for label, value in (
        ("current_work source_policy_ids", current_work.get("source_policy_ids")),
        ("current_work gap_ids", current_work.get("gap_ids")),
        ("goal_execution ready_frontier_goal_ids", goal_execution.get("ready_frontier_goal_ids")),
        ("goal_execution blocked_goal_ids", goal_execution.get("blocked_goal_ids")),
        ("goal_execution standing_execution_authority", goal_execution.get("standing_execution_authority")),
        ("goal_execution external_action_required_for", goal_execution.get("external_action_required_for")),
        ("goal_execution managed_goal_paths", goal_execution.get("managed_goal_paths")),
        ("goal_execution goal_document_paths", goal_execution.get("goal_document_paths")),
        ("goal_execution support_paths", goal_execution.get("support_paths")),
    ):
        if not is_string_list(value):
            errors.append(f"{label} must be a string list")

    artifact_state_counts = approved.get("artifact_state_counts")
    if not isinstance(artifact_state_counts, dict):
        errors.append("approved artifact_state_counts must be an object")
    gap_implementation_snapshot = gap_snapshot.get("implementation_snapshot")
    if not isinstance(gap_implementation_snapshot, dict):
        errors.append("implementation_gap_snapshot implementation_snapshot must be an object")
    goal_statuses = goal_execution.get("status_by_goal")
    if not isinstance(goal_statuses, dict) or any(
        not isinstance(goal_id, str) or not isinstance(status, str)
        for goal_id, status in goal_statuses.items()
    ):
        errors.append("goal_execution status_by_goal must be a string-to-string object")
    for field in (
        "materialized_child_goal_ids_by_parent",
        "dynamic_goal_inventory",
        "completion_evidence_by_goal",
        "archived_completion_evidence_by_goal",
        "blockers_by_goal",
    ):
        if not isinstance(goal_execution.get(field), dict):
            errors.append(f"goal_execution {field} must be an object")
    for field in (
        "transition_history",
        "blocker_resolution_history",
        "pending_questions",
    ):
        if not isinstance(goal_execution.get(field), list):
            errors.append(f"goal_execution {field} must be a list")
    managed_paths = snapshot.get("managed_changed_paths")
    if not is_string_list(managed_paths):
        errors.append("working_tree_snapshot managed_changed_paths must be a string list")

    if errors:
        return errors

    require_equal(
        errors,
        "checkpoint schema",
        checkpoint.get("schema_version"),
        EXPECTED_CHECKPOINT_SCHEMA_VERSION,
    )
    require_equal(
        errors,
        "checkpoint metadata version",
        metadata.get("version"),
        EXPECTED_CHECKPOINT_SCHEMA_VERSION,
    )
    require_equal(
        errors,
        "checkpoint execution order semantics",
        checkpoint.get("execution_order_semantics"),
        "PRIORITY_TIE_BREAKER_ONLY_NOT_DEPENDENCY",
    )
    require_equal(
        errors,
        "checkpoint as-of date",
        metadata.get("as_of"),
        EXPECTED_CHECKPOINT_AS_OF,
    )
    require_equal(
        errors,
        "checkpoint status",
        metadata.get("status"),
        "ACTIVE_WORKING_CHECKPOINT",
    )
    require_equal(
        errors,
        "policy baseline",
        approved.get("policy_baseline_id"),
        "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
    )
    require_equal(errors, "release status", approved.get("release_status"), "NOT_ELIGIBLE")
    require_equal(errors, "approved transaction", approved.get("transaction_status"), "COMMITTED")
    require_equal(errors, "formal test count", approved.get("formal_test_count"), 279)
    require_equal(errors, "formal test NOT_RUN count", approved.get("formal_test_not_run_count"), 279)
    require_equal(errors, "remaining gate count", approved.get("remaining_gate_count"), 5)
    require_equal(errors, "remaining gates waived", approved.get("remaining_gates_waived"), False)
    require_equal(
        errors,
        "approved artifact state counts",
        approved.get("artifact_state_counts"),
        EXPECTED_ARTIFACT_STATE_COUNTS,
    )
    require_equal(errors, "repository root", repository.get("root"), str(root))
    validate_goal_package_binding(errors, goal_execution, root)

    for relative, expected_hash in EXPECTED_IMMUTABLE_PREDECESSOR_SHA256.items():
        predecessor = resolve_safe_repo_file(root, relative)
        if predecessor is None:
            errors.append(f"immutable predecessor missing or unsafe: {relative}")
            continue
        require_equal(
            errors,
            f"immutable predecessor SHA-256 {relative}",
            sha256_file(predecessor),
            expected_hash,
        )

    branch = current_branch(root)
    if branch is None:
        errors.append("current git branch could not be read")
    else:
        require_equal(errors, "git branch", branch, repository.get("branch"))
    head = current_head(root)
    if head is None:
        errors.append("current git HEAD could not be read")
    else:
        snapshot_base_head = repository.get("snapshot_base_head")
        if not isinstance(snapshot_base_head, str) or len(snapshot_base_head) != 40:
            errors.append("repository snapshot base HEAD is invalid")
        else:
            ancestor = is_commit_ancestor(root, snapshot_base_head, head)
            if ancestor is None:
                errors.append("repository snapshot base HEAD could not be resolved")
            elif not ancestor:
                errors.append("repository snapshot base HEAD is not an ancestor of current HEAD")

    runbook_path_value = metadata.get("runbook_path")
    runbook_path = resolve_safe_repo_file(root, runbook_path_value)
    if runbook_path is None:
        errors.append(f"runbook path is unsafe or missing: {runbook_path_value}")
    else:
        runbook = runbook_path.read_text(encoding="utf-8")
        for required_text in (
            "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "Approved/Baselined | 102개",
            "Planned/NOT_RUN | 75개",
            "EPIC-02",
            EXPECTED_ACTIVE_GOAL_PACKAGE_ID,
            "의존성 DAG",
            "ready_frontier_goal_ids",
            "WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-011",
            "WS-EPIC-01-PHASE-B-ADMIN-AUTH-RECOVERY-IMPLEMENTATION-20260722-001",
            "WS-EPIC-01-PHASE-C-RUNTIME-METRIC-PREFLIGHT-IMPLEMENTATION-20260722-001",
            "WS-EPIC-01-PHASE-D-LEGACY-WEB-CLOSURE-IMPLEMENTATION-20260723-001",
            "WS-EPIC-01-PHASE-E-ANDROID-GATEWAY-IMPLEMENTATION-20260723-001",
            "WS-EPIC-01-PHASE-F-PURPOSE-SURFACES-IMPLEMENTATION-20260723-001",
            "WS-EPIC-01-PHASE-G-NO-DESTINATION-HAZARD-IMPLEMENTATION-20260723-001",
            "WS-EPIC-02-PHASE-A-WALK-SESSION-LIFECYCLE-IMPLEMENTATION-20260723-001",
            "WS-FP004-PRIORITY-USER-WORK-ITEM-COMPLETION-20260724-001",
            "IN_PROGRESS",
            EXPECTED_NEXT_WORK_ITEM_ID,
            "PHASE-E-GATEWAY-DEPLOYMENT-NOT-RUN",
            "PHASE-E-ACTUAL-DEVICE-CONNECTIVITY-NOT-RUN",
            "127.0.0.1:8081",
            "407/407",
            "480/480",
            "runtime metric preflight",
            "LEGACY_REFERENCE_ONLY",
            "NOT_ELIGIBLE",
            "validation_cutoff_at",
            "TEST_PLAN_APPROVAL_RECEIPT",
            "test_case_id_set_sha256",
        ):
            if required_text not in runbook:
                errors.append(f"runbook is missing required text: {required_text}")

    agent_instructions = resolve_safe_repo_file(root, "AGENTS.md")
    if agent_instructions is None:
        errors.append("repository AGENTS.md is unsafe or missing")
    elif metadata.get("runbook_path") not in agent_instructions.read_text(encoding="utf-8"):
        errors.append("AGENTS.md does not point to the canonical runbook")

    bindings: dict[str, dict[str, Any]] = {}
    binding_paths: dict[str, Path] = {}
    for index, binding in enumerate(canonical_bindings):
        if not isinstance(binding, dict):
            errors.append(f"canonical binding {index} must be an object")
            continue
        role = binding.get("role")
        path_value = binding.get("path")
        if not isinstance(role, str) or not role:
            errors.append(f"canonical binding {index} role must be a non-empty string")
            continue
        if not isinstance(path_value, str) or not path_value:
            errors.append(
                f"canonical binding {index} path must be a non-empty string"
            )
            continue
        if role in bindings:
            errors.append(f"duplicate canonical binding role: {role}")
            continue
        bindings[role] = binding
        path = resolve_safe_repo_file(root, path_value)
        if path is None:
            errors.append(
                f"canonical binding path is unsafe or missing: "
                f"{role} -> {path_value}"
            )
            continue
        binding_paths[role] = path
        actual_hash = sha256_file(path)
        require_equal(errors, f"{role} file SHA-256", actual_hash, binding.get("file_sha256"))
        try:
            document = load_json(path)
            identity = json_path_value(document, str(binding.get("identity_json_path", "")))
        except (OSError, ValueError, json.JSONDecodeError, KeyError) as exc:
            errors.append(f"{role} identity cannot be read: {exc}")
        else:
            expected_id = binding.get("document_id")
            if isinstance(identity, list):
                if expected_id not in identity:
                    errors.append(f"{role} document ID {expected_id!r} is absent from {identity!r}")
            else:
                require_equal(errors, f"{role} document ID", identity, expected_id)

    required_roles = {
        "POLICY_BASELINE",
        "ARTIFACT_APPLICATION_RECEIPT",
        "ARTIFACT_REGISTER",
        "ARTIFACT_CHANGE_LOG",
        "REQUIREMENTS_TRACEABILITY",
        "IMPLEMENTATION_GAP",
        "IMPLEMENTATION_BACKLOG",
        "EPIC01_PHASE_B_RECORD",
        "SINGLE_ADMIN_RECOVERY_DRILL_PROTOCOL",
        "EPIC01_PHASE_B_ACTIVE_OVERLAY",
        "EPIC01_PHASE_C_RECORD",
        "EPIC01_PHASE_C_ACTIVE_OVERLAY",
        "EPIC01_PHASE_D_RECORD",
        "EPIC01_PHASE_D_ACTIVE_OVERLAY",
        "EPIC01_PHASE_E_RECORD",
        "EPIC01_PHASE_E_ACTIVE_OVERLAY",
        "EPIC01_PHASE_F_RECORD",
        "EPIC01_PHASE_F_ACTIVE_OVERLAY",
        "EPIC01_PHASE_G_RECORD",
        "EPIC01_PHASE_G_ACTIVE_OVERLAY",
        "EPIC02_PHASE_A_RECORD",
        "EPIC02_PHASE_A_ACTIVE_OVERLAY",
        "WORK_ITEM_COMPLETION::WS-GOAL-EPIC-02-FP-018-R001",
        "WORK_ITEM_COMPLETION::WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001",
        "WORK_ITEM_COMPLETION::WS-GOAL-EPIC-02-FP-004-R001",
        "DESIGN_TRACEABILITY",
        "MODULE_REGISTER",
        "PLANNED_TEST_CASES",
    }
    missing_roles = sorted(required_roles - bindings.keys())
    if missing_roles:
        errors.append(f"canonical bindings missing roles: {missing_roles}")

    for role, expected_binding in EXPECTED_SUCCESSOR_CANONICAL_BINDINGS.items():
        binding = bindings.get(role)
        if binding is None:
            continue
        for field, expected_value in expected_binding.items():
            require_equal(
                errors,
                f"{role} canonical binding {field}",
                binding.get(field),
                expected_value,
            )

    try:
        policy = load_json(binding_paths["POLICY_BASELINE"])
        receipt = load_json(binding_paths["ARTIFACT_APPLICATION_RECEIPT"])
        register = load_json(binding_paths["ARTIFACT_REGISTER"])
        rtm = load_json(binding_paths["REQUIREMENTS_TRACEABILITY"])
        gap = load_json(binding_paths["IMPLEMENTATION_GAP"])
        backlog = load_json(binding_paths["IMPLEMENTATION_BACKLOG"])
        phase_b_record = load_json(binding_paths["EPIC01_PHASE_B_RECORD"])
        recovery_drill = load_json(
            binding_paths["SINGLE_ADMIN_RECOVERY_DRILL_PROTOCOL"]
        )
        phase_b_overlay = load_json(
            binding_paths["EPIC01_PHASE_B_ACTIVE_OVERLAY"]
        )
        phase_c_record = load_json(binding_paths["EPIC01_PHASE_C_RECORD"])
        phase_c_overlay = load_json(
            binding_paths["EPIC01_PHASE_C_ACTIVE_OVERLAY"]
        )
        phase_d_record = load_json(binding_paths["EPIC01_PHASE_D_RECORD"])
        phase_d_overlay = load_json(
            binding_paths["EPIC01_PHASE_D_ACTIVE_OVERLAY"]
        )
        phase_e_record = load_json(binding_paths["EPIC01_PHASE_E_RECORD"])
        phase_e_overlay = load_json(
            binding_paths["EPIC01_PHASE_E_ACTIVE_OVERLAY"]
        )
        phase_f_record = load_json(binding_paths["EPIC01_PHASE_F_RECORD"])
        phase_f_overlay = load_json(
            binding_paths["EPIC01_PHASE_F_ACTIVE_OVERLAY"]
        )
        phase_g_record = load_json(binding_paths["EPIC01_PHASE_G_RECORD"])
        phase_g_overlay = load_json(
            binding_paths["EPIC01_PHASE_G_ACTIVE_OVERLAY"]
        )
        phase_a_record = load_json(binding_paths["EPIC02_PHASE_A_RECORD"])
        phase_a_overlay = load_json(
            binding_paths["EPIC02_PHASE_A_ACTIVE_OVERLAY"]
        )
        gap_r006_path = resolve_safe_repo_file(
            root,
            "docs/control/audits/"
            "walksafe-implementation-gap-analysis-20260723-r006.json",
        )
        gap_r007_path = resolve_safe_repo_file(
            root,
            "docs/control/audits/"
            "walksafe-implementation-gap-analysis-20260723-r007.json",
        )
        gap_r008_path = resolve_safe_repo_file(
            root,
            "docs/control/audits/"
            "walksafe-implementation-gap-analysis-20260723-r008.json",
        )
        gap_r010_path = resolve_safe_repo_file(
            root,
            "docs/control/audits/"
            "walksafe-implementation-gap-analysis-20260724-r010.json",
        )
        if (
            gap_r006_path is None
            or gap_r007_path is None
            or gap_r008_path is None
            or gap_r010_path is None
        ):
            raise ValueError(
                "implementation gap predecessor path is unsafe or missing"
            )
        gap_r006 = load_json(gap_r006_path)
        gap_r007 = load_json(gap_r007_path)
        gap_r008_predecessor = load_json(gap_r008_path)
        gap_r010_predecessor = load_json(gap_r010_path)
    except (KeyError, OSError, ValueError, json.JSONDecodeError) as exc:
        errors.append(f"canonical JSON validation could not start: {exc}")
        return errors

    require_equal(errors, "policy baseline ID", policy.get("metadata", {}).get("baseline_id"), approved.get("policy_baseline_id"))
    require_equal(errors, "policy baseline version", policy.get("metadata", {}).get("baseline_version"), approved.get("policy_baseline_version"))
    require_equal(errors, "policy release status", policy.get("release_status"), "NOT_ELIGIBLE")

    gates = policy.get("remaining_gates", [])
    require_equal(errors, "policy remaining gate count", len(gates), 5)
    for gate in gates:
        require_equal(errors, f"gate {gate.get('id')} status", gate.get("status"), "NOT_RUN")

    receipt_metadata = receipt.get("metadata", {})
    require_equal(errors, "receipt ID", receipt_metadata.get("receipt_id"), approved.get("application_receipt_id"))
    require_equal(errors, "receipt application ID", receipt_metadata.get("application_id"), approved.get("application_id"))
    require_equal(errors, "receipt transaction", receipt_metadata.get("transaction_status"), "COMMITTED")
    require_equal(
        errors,
        "approved/receipt transaction",
        approved.get("transaction_status"),
        receipt_metadata.get("transaction_status"),
    )
    receipt_states = receipt.get("state_summary", {})
    expected_states = approved.get("artifact_state_counts", {})
    state_map = {
        "approved_baselined": "APPROVED_BASELINED",
        "active": "ACTIVE",
        "draft_pending": "DRAFT",
        "planned_not_run": "PLANNED_NOT_RUN",
    }
    for receipt_key, checkpoint_key in state_map.items():
        require_equal(
            errors,
            f"receipt state {receipt_key}",
            receipt_states.get(receipt_key),
            expected_states.get(checkpoint_key),
        )
    receipt_boundary = receipt.get("authority_boundary", {})
    require_equal(errors, "receipt gates waived", receipt_boundary.get("remaining_gates_are_waived"), False)
    require_equal(errors, "receipt release status", receipt_boundary.get("release_status"), "NOT_ELIGIBLE")

    register_counts = register.get("summary", {}).get("lifecycle_status_counts", {})
    for register_key, checkpoint_key in (
        ("APPROVED_BASELINED", "APPROVED_BASELINED"),
        ("ACTIVE", "ACTIVE"),
        ("DRAFT", "DRAFT"),
        ("PLANNED", "PLANNED_NOT_RUN"),
    ):
        require_equal(
            errors,
            f"DOC-01 state {register_key}",
            register_counts.get(register_key),
            expected_states.get(checkpoint_key),
        )

    require_equal(errors, "RTM requirement count", rtm.get("coverage", {}).get("requirement_count"), 68)
    require_equal(errors, "RTM planned test count", rtm.get("coverage", {}).get("planned_test_count"), 279)
    require_equal(errors, "RTM verification claims", rtm.get("coverage", {}).get("verification_completion_claim_count"), 0)

    require_equal(errors, "Gap report ID", gap.get("metadata", {}).get("report_id"), gap_snapshot.get("report_id"))
    require_equal(errors, "Gap report version", gap.get("metadata", {}).get("version"), "0.11.0")
    require_equal(errors, "checkpoint Gap report version", gap_snapshot.get("report_version"), "0.11.0")
    require_equal(errors, "checkpoint Gap assessment count", gap_snapshot.get("assessment_count"), 68)
    require_equal(
        errors,
        "Gap predecessor report ID",
        gap.get("metadata", {}).get("predecessor_report_id"),
        "WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-010",
    )
    require_equal(errors, "Gap assessment count", gap.get("coverage", {}).get("assessment_count"), 68)
    require_equal(errors, "Gap planned NOT_RUN count", gap.get("coverage", {}).get("planned_test_not_run_count"), 279)
    gap_implementation_snapshot = gap.get("implementation_snapshot", {})
    checkpoint_gap_snapshot = gap_snapshot.get("implementation_snapshot", {})
    for snapshot_key in (
        "base_commit",
        "current_head",
        "file_count",
        "path_set_sha256",
        "content_set_sha256",
    ):
        require_equal(
            errors,
            f"Gap implementation snapshot {snapshot_key}",
            gap_implementation_snapshot.get(snapshot_key),
            checkpoint_gap_snapshot.get(snapshot_key),
        )
    require_equal(errors, "Gap content SHA-256", gap.get("report_content_sha256"), gap_snapshot.get("report_content_sha256"))
    actual_gap_counts = gap.get("summary", {}).get("status_counts", {})
    require_equal(errors, "Gap r011 status counts", actual_gap_counts, EXPECTED_R011_STATUS_COUNTS)
    require_equal(
        errors,
        "Gap implemented/formally verified count",
        gap.get("summary", {}).get("implemented_and_formally_verified_count"),
        0,
    )
    require_equal(
        errors,
        "checkpoint Gap status counts",
        gap_snapshot.get("status_counts"),
        EXPECTED_CHECKPOINT_STATUS_COUNTS,
    )
    expected_reassessed_gap_statuses = {
        "GAP-006": "PARTIAL",
        "GAP-010": "PARTIAL",
        "GAP-013": "PARTIAL",
        "GAP-016": "PARTIAL",
        "GAP-018": "PARTIAL",
        "GAP-020": "MISSING",
        "GAP-026": "PARTIAL",
        "GAP-027": "PARTIAL",
        "GAP-028": "CONFLICTING",
        "GAP-029": "CONFLICTING",
        "GAP-030": "CONFLICTING",
        "GAP-031": "CONFLICTING",
        "GAP-036": "CONFLICTING",
        "GAP-041": "CONFLICTING",
        "GAP-049": "PARTIAL",
        "GAP-051": "PARTIAL",
        "GAP-052": "CONFLICTING",
        "GAP-056": "CONFLICTING",
        "GAP-057": "PARTIAL",
    }
    assessments_by_id = {
        item.get("gap_id"): item
        for item in gap.get("assessments", [])
        if isinstance(item, dict)
    }
    predecessor_assessments = {
        item.get("gap_id"): item
        for item in gap_r010_predecessor.get("assessments", [])
        if isinstance(item, dict)
    }
    changed_gap_ids = {
        gap_id
        for gap_id in set(predecessor_assessments) | set(assessments_by_id)
        if predecessor_assessments.get(gap_id) != assessments_by_id.get(gap_id)
    }
    require_equal(
        errors,
        "Gap r011 assessment change set",
        changed_gap_ids,
        {"GAP-013"},
    )
    reassessment_scope = gap.get("reassessment_scope", {})
    require_equal(
        errors,
        "Gap r011 directly reassessed IDs",
        reassessment_scope.get("directly_reassessed_gap_ids"),
        ["GAP-013"],
    )
    require_equal(
        errors,
        "Gap r011 impact reviewed IDs",
        reassessment_scope.get("impact_reviewed_gap_ids"),
        [],
    )
    for gap_id, expected_status in expected_reassessed_gap_statuses.items():
        assessment = assessments_by_id.get(gap_id)
        if assessment is None:
            errors.append(f"Gap r011 is missing {gap_id}")
            continue
        require_equal(errors, f"{gap_id} status", assessment.get("status"), expected_status)
        require_equal(
            errors,
            f"{gap_id} formal test status",
            assessment.get("formal_test_status"),
            "NOT_RUN",
        )
    non_not_run_assessments = [
        item.get("gap_id")
        for item in gap.get("assessments", [])
        if item.get("formal_test_status") != "NOT_RUN"
    ]
    if non_not_run_assessments:
        errors.append(f"Gap r011 has formal tests not marked NOT_RUN: {non_not_run_assessments}")

    require_equal(errors, "backlog ID", backlog.get("metadata", {}).get("backlog_id"), gap_snapshot.get("backlog_id"))
    require_equal(errors, "backlog version", backlog.get("metadata", {}).get("version"), "0.11.0")
    require_equal(
        errors,
        "backlog predecessor ID",
        backlog.get("metadata", {}).get("predecessor_backlog_id"),
        "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-010",
    )
    require_equal(errors, "backlog EPIC count", len(backlog.get("epics", [])), 12)
    require_equal(errors, "backlog execution order", backlog.get("execution_order"), checkpoint.get("execution_order"))
    require_equal(errors, "backlog content SHA-256", backlog.get("backlog_content_sha256"), gap_snapshot.get("backlog_content_sha256"))
    require_equal(
        errors,
        "backlog Gap r011 binding",
        backlog.get("gap_report_content_sha256"),
        gap.get("report_content_sha256"),
    )
    actual_epic_status_counts: dict[str, int] = {}
    for epic in backlog.get("epics", []):
        status = epic.get("current_status")
        if isinstance(status, str):
            actual_epic_status_counts[status] = actual_epic_status_counts.get(status, 0) + 1
    require_equal(
        errors,
        "backlog EPIC status counts",
        actual_epic_status_counts,
        EXPECTED_EPIC_STATUS_COUNTS,
    )
    require_equal(
        errors,
        "checkpoint backlog EPIC status counts",
        gap_snapshot.get("epic_status_counts"),
        EXPECTED_EPIC_STATUS_COUNTS,
    )
    backlog_next_action = backlog.get("next_single_action", {})
    require_equal(
        errors,
        "backlog next work item",
        backlog_next_action.get("work_item_id"),
        EXPECTED_NEXT_WORK_ITEM_ID,
    )
    require_equal(
        errors,
        "backlog next EPIC",
        backlog_next_action.get("epic_id"),
        "EPIC-02",
    )
    require_equal(
        errors,
        "backlog next source policy",
        backlog_next_action.get("source_policy_id"),
        EXPECTED_NEXT_SOURCE_POLICY_ID,
    )
    if "gap_id" in backlog_next_action:
        require_equal(
            errors,
            "backlog next Gap",
            backlog_next_action.get("gap_id"),
            EXPECTED_NEXT_GAP_ID,
        )
    require_equal(
        errors,
        "backlog next action",
        backlog_next_action.get("action"),
        EXPECTED_NEXT_ACTION,
    )

    epic01 = next(
        (epic for epic in backlog.get("epics", []) if epic.get("epic_id") == "EPIC-01"),
        None,
    )
    if epic01 is None:
        errors.append("EPIC-01 is absent from backlog")
    else:
        completed_internal_phases = epic01.get("completed_internal_phases", [])
        for completed_phase in (
            "PHASE_E_ANDROID_GATEWAY_EXTRACTION_INTERNAL",
            "PHASE_F_PRODUCT_PURPOSE_AND_SAFETY_SURFACES_INTERNAL",
            "PHASE_G_NO_DESTINATION_HAZARD_CONFORMANCE_INTERNAL",
        ):
            if completed_phase not in completed_internal_phases:
                errors.append(f"EPIC-01 does not record completion: {completed_phase}")
        require_equal(errors, "EPIC-01 open internal work", epic01.get("open_internal_work", []), [])
        require_equal(
            errors,
            "EPIC-01 retained status",
            epic01.get("current_status"),
            "IMPLEMENTATION_READY",
        )

    current_epic = next(
        (epic for epic in backlog.get("epics", []) if epic.get("epic_id") == "EPIC-02"),
        None,
    )
    if current_epic is None:
        errors.append("EPIC-02 is absent from backlog")
    else:
        require_equal(errors, "current EPIC ID", current_work.get("epic_id"), "EPIC-02")
        require_equal(
            errors,
            "current work item ID",
            current_work.get("work_item_id"),
            EXPECTED_NEXT_WORK_ITEM_ID,
        )
        require_equal(errors, "current EPIC title", current_work.get("title"), current_epic.get("title"))
        require_equal(errors, "current EPIC target", current_work.get("target_completion_level"), current_epic.get("target_completion_level"))
        require_equal(errors, "current EPIC policy set", set(current_work.get("source_policy_ids", [])), set(current_epic.get("source_policy_ids", [])))
        require_equal(errors, "current EPIC Gap set", set(current_work.get("gap_ids", [])), set(current_epic.get("gap_ids", [])))
        require_equal(
            errors,
            "current EPIC status",
            current_epic.get("current_status"),
            "IN_PROGRESS",
        )
        ordered_policy_ids = current_epic.get("ordered_source_policy_ids", [])
        if not ordered_policy_ids or ordered_policy_ids[:2] != ["FP-017", "FP-018"]:
            errors.append("EPIC-02 first ordered policy is not FP-017")

    require_equal(errors, "current work status", current_work.get("status"), "IN_PROGRESS")
    require_equal(
        errors,
        "current work status scope",
        current_work.get("status_scope"),
        "IMPLEMENTATION_BACKLOG_EPIC_STATUS_NOT_GOAL_STATUS",
    )
    require_equal(
        errors,
        "current work scope kind",
        current_work.get("scope_kind"),
        "BACKLOG_EPIC_AGGREGATE",
    )
    require_equal(
        errors,
        "current work item ID semantics",
        current_work.get("work_item_id_semantics"),
        "NEXT_ACTION_POINTER_ONLY",
    )
    require_equal(
        errors,
        "current work policy IDs semantics",
        current_work.get("source_policy_ids_semantics"),
        "BACKLOG_EPIC_AGGREGATE_NOT_FOCUS_GOAL_COMPLETION_SCOPE",
    )
    require_equal(
        errors,
        "current work Gap IDs semantics",
        current_work.get("gap_ids_semantics"),
        "BACKLOG_EPIC_AGGREGATE_NOT_FOCUS_GOAL_COMPLETION_SCOPE",
    )
    require_equal(
        errors,
        "current work ordered policies",
        current_work.get("source_policy_ids"),
        [
            "FP-017",
            "FP-018",
            "NPC-PERMISSION-SESSION-LIFECYCLE",
            "FP-004",
            "FP-005",
            "FP-006",
            "FP-010",
            "FP-011",
            "FP-013",
            "FP-015",
            "FP-014",
            "FP-016",
            "FP-012",
        ],
    )
    require_equal(
        errors,
        "current work ordered Gaps",
        current_work.get("gap_ids"),
        [
            "GAP-006",
            "GAP-013",
            "GAP-014",
            "GAP-015",
            "GAP-019",
            "GAP-020",
            "GAP-021",
            "GAP-022",
            "GAP-023",
            "GAP-024",
            "GAP-025",
            "GAP-026",
            "GAP-027",
        ],
    )
    require_equal(errors, "current work policy change", current_work.get("policy_change_required"), False)
    require_equal(errors, "current work release claim", current_work.get("release_completion_claimed"), False)
    require_equal(
        errors,
        "current work last completed summary",
        current_work.get("last_completed_work_summary"),
        EXPECTED_LAST_COMPLETED_WORK_SUMMARY,
    )
    require_equal(
        errors,
        "current work current focus",
        current_work.get("current_focus"),
        EXPECTED_CURRENT_FOCUS,
    )
    next_action = current_work.get("next_action")
    if not isinstance(next_action, str) or not next_action.strip():
        errors.append("current work next_action is missing")
    else:
        require_equal(errors, "current work next action", next_action, EXPECTED_NEXT_ACTION)
        forbidden_action_fragments = (
            "git clean",
            "reset --hard",
            "rm -rf",
            "release_eligible=true",
            "release eligible",
            "\n",
            ";",
            "&&",
            "$(",
            "`",
        )
        found = [fragment for fragment in forbidden_action_fragments if fragment in next_action.lower()]
        if found:
            errors.append(f"current work next_action contains unsafe fragments: {found}")

    product_boundary_path = resolve_safe_repo_file(
        root,
        "configs/walksafe_product_boundary_20260722.json",
    )
    restored_next_routes = [
        relative
        for relative in EXPECTED_PHASE_E_REMOVED_NEXT_ROUTE_PATHS
        if (root / relative).exists()
        or repo_path_has_symlink_component(root, relative)
    ]
    if restored_next_routes:
        errors.append(f"Phase E removed Next routes were restored: {restored_next_routes}")
    try:
        if product_boundary_path is None:
            raise ValueError("product boundary path is unsafe or missing")
        product_boundary = load_json(product_boundary_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        errors.append(f"current EPIC execution contract cannot be loaded: {exc}")
    else:
        products = product_boundary.get("products", {})
        user_product = products.get("user_android_app", {})
        admin_product = products.get("admin_android_app", {})
        legacy_product = products.get("legacy_web", {})
        gateway_product = products.get("android_api_gateway", {})
        require_equal(errors, "product contract schema", product_boundary.get("schema_version"), "1.3.0")
        require_equal(errors, "product contract version", product_boundary.get("version"), "1.3.0")
        require_equal(errors, "product contract policy version", product_boundary.get("effective_policy_baseline", {}).get("version"), "1.0.1")
        require_equal(
            errors,
            "product contract phase",
            product_boundary.get("implementation_scope", {}).get("contract_status"),
            "IMPLEMENTED_PHASE_E_ANDROID_GATEWAY_EXTRACTION_INTERNAL",
        )
        if user_product.get("application_id") == admin_product.get("application_id"):
            errors.append("product contract user/admin application IDs are not separated")
        require_equal(
            errors,
            "product contract admin authentication",
            admin_product.get("authentication", {}).get("mode"),
            "PASSWORD_TOTP",
        )
        require_equal(
            errors,
            "product contract admin security controls",
            admin_product.get("fail_closed", {}).get("security_control_workflows_enabled"),
            True,
        )
        require_equal(
            errors,
            "product contract admin operational workflows",
            admin_product.get("fail_closed", {}).get("operational_workflows_enabled"),
            False,
        )
        require_equal(
            errors,
            "product contract admin recovery drill",
            admin_product.get("recovery", {}).get("formal_phone_loss_drill_status"),
            "NOT_RUN",
        )
        require_equal(
            errors,
            "product contract admin unlock EPIC",
            admin_product.get("fail_closed", {}).get("unlock_requires_epic"),
            "EPIC-01",
        )
        require_equal(errors, "product contract Legacy Web role", legacy_product.get("product_role"), "LEGACY_REFERENCE_ONLY")
        require_equal(errors, "product contract Legacy Web external runtime", legacy_product.get("external_user_runtime_allowed"), False)
        require_equal(
            errors,
            "product contract Legacy Web technical closure",
            legacy_product.get("technical_closure_status"),
            "COMPLETE_WITHOUT_RUNTIME_ALLOWLIST_INTERNAL",
        )
        require_equal(
            errors,
            "product contract Legacy Web remaining executable inputs",
            legacy_product.get("remaining_executable_historical_inputs"),
            [],
        )
        require_equal(
            errors,
            "product contract gateway extraction",
            legacy_product.get("transitional_android_api_routes", {}).get("extraction_status"),
            "INTERNAL_IMPLEMENTATION_VERIFIED_NOT_DEPLOYED",
        )
        require_equal(
            errors,
            "product contract Legacy Web runtime allowlist",
            legacy_product.get("transitional_android_api_routes", {}).get("runtime_allowlist"),
            [],
        )
        require_equal(
            errors,
            "product contract Gateway source path",
            gateway_product.get("source_path"),
            "apps/android-gateway",
        )
        require_equal(
            errors,
            "product contract Gateway bind",
            gateway_product.get("official_local_bind"),
            "127.0.0.1:8081",
        )
        require_equal(
            errors,
            "product contract Gateway Backend origin",
            gateway_product.get("backend_origin"),
            "http://127.0.0.1:8000",
        )
        require_equal(
            errors,
            "product contract Gateway routes",
            gateway_product.get("public_routes"),
            [
                "/api/field-session",
                "/api/navigation/walking",
                "/api/navigation/destinations/search",
                "/api/reports/v2",
            ],
        )
        require_equal(
            errors,
            "product contract Gateway fallback",
            gateway_product.get("legacy_next_fallback"),
            "PROHIBITED",
        )
        require_equal(
            errors,
            "product contract Gateway deployment status",
            gateway_product.get("deployment_status"),
            "NOT_RUN",
        )
        require_equal(
            errors,
            "product contract Gateway actual-device connectivity",
            gateway_product.get("actual_device_connectivity_status"),
            "NOT_RUN",
        )
        require_equal(errors, "product contract release status", product_boundary.get("release_control", {}).get("release_eligibility"), "NOT_ELIGIBLE")
        require_equal(
            errors,
            "product contract FULL tier status",
            product_boundary.get("device_support", {}).get("phase_a_full_tier_status"),
            "BLOCKED_PENDING_RUNTIME_METRIC_VERIFICATION",
        )
        require_equal(
            errors,
            "product contract approved device profile",
            product_boundary.get("device_support", {}).get("approved_designated_device_profile"),
            False,
        )
        require_equal(
            errors,
            "product contract approved device profile version",
            product_boundary.get("device_support", {}).get("approved_designated_device_profile_version"),
            None,
        )

    record_metadata = phase_b_record.get("metadata", {})
    record_authority = phase_b_record.get("authority_boundary", {})
    record_trace = phase_b_record.get("trace", {})
    record_release = phase_b_record.get("release_boundary", {})
    require_equal(
        errors,
        "EPIC-01 Phase B record status",
        record_metadata.get("status"),
        "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS",
    )
    require_equal(errors, "EPIC-01 Phase B record version", record_metadata.get("version"), "0.1.0")
    require_equal(errors, "EPIC-01 Phase B record epic", record_trace.get("epic_id"), "EPIC-01")
    require_equal(errors, "EPIC-01 Phase B record epic status", record_trace.get("epic_status"), "IN_PROGRESS")
    require_equal(
        errors,
        "EPIC-01 Phase B implementation-ready claim",
        record_authority.get("claims_epic_implementation_ready"),
        False,
    )
    require_equal(errors, "EPIC-01 Phase B formal-test claim", record_authority.get("claims_formal_test_pass"), False)
    require_equal(errors, "EPIC-01 Phase B recovery-drill claim", record_authority.get("claims_recovery_drill_pass"), False)
    require_equal(errors, "EPIC-01 Phase B release claim", record_authority.get("claims_release_eligible"), False)
    require_equal(errors, "EPIC-01 Phase B formal tests passed", record_release.get("formal_tests_passed"), 0)
    require_equal(errors, "EPIC-01 Phase B formal tests total", record_release.get("formal_tests_total"), 279)
    require_equal(errors, "EPIC-01 Phase B formal status", record_release.get("formal_test_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase B remaining gates", len(record_release.get("remaining_gates", [])), 5)
    require_equal(errors, "EPIC-01 Phase B gates waived", record_release.get("remaining_gates_waived"), False)
    require_equal(errors, "EPIC-01 Phase B release status", record_release.get("release_status"), "NOT_ELIGIBLE")
    require_equal(
        errors,
        "EPIC-01 Phase B immutable next action",
        phase_b_record.get("next_single_action"),
        "EPIC-01에서 실행 중 실제 미터 거리 frame과 승인된 지정 기기 프로필을 함께 검사하는 runtime metric preflight를 구현한다.",
    )

    drill_metadata = recovery_drill.get("metadata", {})
    drill_authority = recovery_drill.get("authority_boundary", {})
    drill_execution = recovery_drill.get("execution", {})
    drill_release = recovery_drill.get("release_boundary", {})
    require_equal(
        errors,
        "recovery drill protocol status",
        drill_metadata.get("status"),
        "DRAFT_PROCEDURE_NOT_EXECUTED",
    )
    require_equal(errors, "recovery drill execution status", drill_execution.get("status"), "NOT_RUN")
    require_equal(errors, "recovery drill result", drill_execution.get("result"), None)
    require_equal(errors, "recovery drill evidence", drill_execution.get("evidence_files"), [])
    require_equal(errors, "recovery drill completed", drill_authority.get("execution_completed"), False)
    require_equal(errors, "recovery drill gate closed", drill_authority.get("gate_closed"), False)
    require_equal(errors, "recovery drill gate waived", drill_authority.get("gate_waived"), False)
    require_equal(errors, "recovery drill gate status", drill_release.get("gate_status"), "NOT_RUN")
    require_equal(errors, "recovery drill formal status", drill_release.get("formal_test_status"), "NOT_RUN")
    require_equal(errors, "recovery drill release status", drill_release.get("release_status"), "NOT_ELIGIBLE")

    overlay_authority = phase_b_overlay.get("authority_boundary", {})
    overlay_formal = phase_b_overlay.get("formal_boundary", {})
    require_equal(
        errors,
        "Phase B overlay lifecycle promotion",
        overlay_authority.get("changes_lifecycle_or_approval_state"),
        False,
    )
    require_equal(
        errors,
        "Phase B overlay baseline modification",
        overlay_authority.get("approved_baseline_or_r001_modified"),
        False,
    )
    require_equal(errors, "Phase B overlay formal-test claim", overlay_authority.get("formal_test_completion_claimed"), False)
    require_equal(errors, "Phase B overlay formal tests total", overlay_formal.get("formal_tests_total"), 279)
    require_equal(errors, "Phase B overlay formal tests NOT_RUN", overlay_formal.get("formal_tests_not_run"), 279)
    require_equal(errors, "Phase B overlay gates status", overlay_formal.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "Phase B overlay gates waived", overlay_formal.get("remaining_gates_waived"), False)
    require_equal(errors, "Phase B overlay release status", overlay_formal.get("release_status"), "NOT_ELIGIBLE")

    phase_c_metadata = phase_c_record.get("metadata", {})
    phase_c_authority = phase_c_record.get("authority_boundary", {})
    phase_c_trace = phase_c_record.get("trace", {})
    phase_c_snapshot = phase_c_record.get("implementation_snapshot", {})
    phase_c_profiles = phase_c_record.get("production_device_profile_registry", {})
    phase_c_verification = phase_c_record.get("internal_verification", {})
    phase_c_release = phase_c_record.get("release_boundary", {})
    require_equal(
        errors,
        "EPIC-01 Phase C record status",
        phase_c_metadata.get("status"),
        "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS",
    )
    require_equal(errors, "EPIC-01 Phase C record version", phase_c_metadata.get("version"), "0.1.0")
    require_equal(errors, "EPIC-01 Phase C record epic", phase_c_trace.get("epic_id"), "EPIC-01")
    require_equal(errors, "EPIC-01 Phase C record epic status", phase_c_trace.get("epic_status"), "IN_PROGRESS")
    require_equal(errors, "EPIC-01 Phase C trace Gap IDs", phase_c_trace.get("gap_ids"), ["GAP-018"])
    require_equal(
        errors,
        "EPIC-01 Phase C planned test status",
        phase_c_trace.get("planned_test_execution_status"),
        "NOT_RUN",
    )
    require_equal(
        errors,
        "EPIC-01 Phase C implementation path count",
        phase_c_snapshot.get("file_count"),
        len(EXPECTED_PHASE_C_IMPLEMENTATION_PATHS),
    )
    require_equal(
        errors,
        "EPIC-01 Phase C implementation paths",
        sorted(item.get("path") for item in phase_c_snapshot.get("files", [])),
        sorted(EXPECTED_PHASE_C_IMPLEMENTATION_PATHS),
    )
    require_equal(errors, "EPIC-01 Phase C approved production profiles", phase_c_profiles.get("approved_profile_ids"), [])
    require_equal(errors, "EPIC-01 Phase C approved production profile count", phase_c_profiles.get("approved_profile_count"), 0)
    require_equal(errors, "EPIC-01 Phase C FULL tier possible", phase_c_profiles.get("full_tier_currently_possible"), False)
    require_equal(errors, "EPIC-01 Phase C actual device status", phase_c_verification.get("actual_device_execution"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase C implementation-ready claim", phase_c_authority.get("claims_epic_implementation_ready"), False)
    require_equal(errors, "EPIC-01 Phase C formal-test claim", phase_c_authority.get("claims_formal_test_pass"), False)
    require_equal(errors, "EPIC-01 Phase C actual-device claim", phase_c_authority.get("claims_actual_device_test_pass"), False)
    require_equal(errors, "EPIC-01 Phase C production-profile claim", phase_c_authority.get("claims_production_device_profile_approved"), False)
    require_equal(errors, "EPIC-01 Phase C release claim", phase_c_authority.get("claims_release_eligible"), False)
    require_equal(errors, "EPIC-01 Phase C formal tests passed", phase_c_release.get("formal_tests_passed"), 0)
    require_equal(errors, "EPIC-01 Phase C formal tests total", phase_c_release.get("formal_tests_total"), 279)
    require_equal(errors, "EPIC-01 Phase C formal tests NOT_RUN", phase_c_release.get("formal_tests_not_run"), 279)
    require_equal(errors, "EPIC-01 Phase C gate status", phase_c_release.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase C gates waived", phase_c_release.get("remaining_gates_waived"), False)
    require_equal(errors, "EPIC-01 Phase C release status", phase_c_release.get("release_status"), "NOT_ELIGIBLE")
    phase_c_next_action = phase_c_record.get("next_single_action", {})
    require_equal(
        errors,
        "EPIC-01 Phase C next work item",
        phase_c_next_action.get("work_item_id"),
        "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE",
    )
    require_equal(
        errors,
        "EPIC-01 Phase C next action",
        phase_c_next_action.get("action"),
        "남은 Legacy Web 실행·빌드·배포·외부 접근 경로를 기술적으로 닫고 읽기 전용 참고 경계를 검증한다.",
    )

    phase_c_overlay_authority = phase_c_overlay.get("authority_boundary", {})
    phase_c_overlay_evidence = phase_c_overlay.get("open_evidence_boundaries", {})
    phase_c_overlay_formal = phase_c_overlay.get("formal_boundary", {})
    require_equal(
        errors,
        "Phase C overlay lifecycle promotion",
        phase_c_overlay_authority.get("changes_lifecycle_or_approval_state"),
        False,
    )
    require_equal(
        errors,
        "Phase C overlay predecessor modification",
        phase_c_overlay_authority.get("approved_baseline_or_r001_r002_modified"),
        False,
    )
    require_equal(errors, "Phase C overlay formal-test claim", phase_c_overlay_authority.get("formal_test_completion_claimed"), False)
    require_equal(errors, "Phase C overlay actual-device claim", phase_c_overlay_authority.get("actual_device_completion_claimed"), False)
    require_equal(errors, "Phase C overlay production profile IDs", phase_c_overlay_evidence.get("approved_production_profile_ids"), [])
    require_equal(errors, "Phase C overlay production profile count", phase_c_overlay_evidence.get("approved_production_profile_count"), 0)
    require_equal(errors, "Phase C overlay actual-device status", phase_c_overlay_evidence.get("actual_device_test_status"), "NOT_RUN")
    require_equal(errors, "Phase C overlay formal status", phase_c_overlay_evidence.get("formal_test_status"), "NOT_RUN")
    require_equal(errors, "Phase C overlay formal tests total", phase_c_overlay_formal.get("formal_tests_total"), 279)
    require_equal(errors, "Phase C overlay formal tests NOT_RUN", phase_c_overlay_formal.get("formal_tests_not_run"), 279)
    require_equal(errors, "Phase C overlay gates status", phase_c_overlay_formal.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "Phase C overlay gates waived", phase_c_overlay_formal.get("remaining_gates_waived"), False)
    require_equal(errors, "Phase C overlay release status", phase_c_overlay_formal.get("release_status"), "NOT_ELIGIBLE")

    phase_d_metadata = phase_d_record.get("metadata", {})
    phase_d_authority = phase_d_record.get("authority_boundary", {})
    phase_d_trace = phase_d_record.get("trace", {})
    phase_d_snapshot = phase_d_record.get("implementation_snapshot", {})
    phase_d_contract = phase_d_record.get("legacy_web_closure_contract", {})
    phase_d_verification = phase_d_record.get("internal_verification", {})
    phase_d_release = phase_d_record.get("release_boundary", {})
    require_equal(
        errors,
        "EPIC-01 Phase D record status",
        phase_d_metadata.get("status"),
        "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS",
    )
    require_equal(errors, "EPIC-01 Phase D record version", phase_d_metadata.get("version"), "0.1.0")
    require_equal(errors, "EPIC-01 Phase D record epic", phase_d_trace.get("epic_id"), "EPIC-01")
    require_equal(errors, "EPIC-01 Phase D record epic status", phase_d_trace.get("epic_status"), "IN_PROGRESS")
    require_equal(errors, "EPIC-01 Phase D trace Gap IDs", phase_d_trace.get("gap_ids"), ["GAP-016", "GAP-018"])
    require_equal(
        errors,
        "EPIC-01 Phase D planned test status",
        phase_d_trace.get("planned_test_execution_status"),
        "NOT_RUN",
    )
    require_equal(
        errors,
        "EPIC-01 Phase D implementation path count",
        phase_d_snapshot.get("file_count"),
        len(EXPECTED_PHASE_D_IMPLEMENTATION_PATHS),
    )
    require_equal(
        errors,
        "EPIC-01 Phase D implementation paths",
        [item.get("path") for item in phase_d_snapshot.get("files", [])],
        list(EXPECTED_PHASE_D_IMPLEMENTATION_PATHS),
    )
    require_equal(
        errors,
        "EPIC-01 Phase D technical closure",
        phase_d_contract.get("technical_closure_status"),
        "COMPLETE_WITH_TRANSITIONAL_ANDROID_BFF_EXCEPTION",
    )
    require_equal(errors, "EPIC-01 Phase D repository paths closed", phase_d_contract.get("official_repository_paths_closed"), True)
    require_equal(errors, "EPIC-01 Phase D external runtime", phase_d_contract.get("external_user_runtime_allowed"), False)
    require_equal(errors, "EPIC-01 Phase D formal release component", phase_d_contract.get("formal_release_component_allowed"), False)
    require_equal(errors, "EPIC-01 Phase D UI status", phase_d_contract.get("legacy_ui_response_status"), 410)
    require_equal(errors, "EPIC-01 Phase D loopback bind", phase_d_contract.get("official_runtime_bind"), "127.0.0.1:3000")
    require_equal(
        errors,
        "EPIC-01 Phase D BFF routes",
        phase_d_contract.get("transitional_android_bff", {}).get("runtime_allowlist"),
        [
            "/api/field-session",
            "/api/navigation/walking",
            "/api/navigation/destinations/search",
            "/api/reports/v2",
        ],
    )
    require_equal(
        errors,
        "EPIC-01 Phase D BFF extraction",
        phase_d_contract.get("transitional_android_bff", {}).get("extraction_status"),
        "NOT_COMPLETED",
    )
    require_equal(errors, "EPIC-01 Phase D BFF completion claim", phase_d_authority.get("claims_bff_extraction_complete"), False)
    require_equal(errors, "EPIC-01 Phase D manual Next claim", phase_d_authority.get("claims_arbitrary_manual_next_blocked"), False)
    require_equal(errors, "EPIC-01 Phase D external URL claim", phase_d_authority.get("claims_historical_external_urls_decommissioned"), False)
    require_equal(errors, "EPIC-01 Phase D cached PWA claim", phase_d_authority.get("claims_previously_installed_or_cached_pwa_disabled"), False)
    require_equal(errors, "EPIC-01 Phase D formal-test claim", phase_d_authority.get("claims_formal_test_pass"), False)
    require_equal(errors, "EPIC-01 Phase D actual-device claim", phase_d_authority.get("claims_actual_device_test_pass"), False)
    require_equal(errors, "EPIC-01 Phase D release claim", phase_d_authority.get("claims_release_eligible"), False)
    require_equal(errors, "EPIC-01 Phase D actual device status", phase_d_verification.get("actual_device_execution"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase D external URL check", phase_d_verification.get("historical_external_url_probe_execution"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase D cached PWA check", phase_d_verification.get("previously_installed_or_cached_pwa_check"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase D formal tests passed", phase_d_release.get("formal_tests_passed"), 0)
    require_equal(errors, "EPIC-01 Phase D formal tests total", phase_d_release.get("formal_tests_total"), 279)
    require_equal(errors, "EPIC-01 Phase D formal tests NOT_RUN", phase_d_release.get("formal_tests_not_run"), 279)
    require_equal(errors, "EPIC-01 Phase D gates status", phase_d_release.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase D gates waived", phase_d_release.get("remaining_gates_waived"), False)
    require_equal(errors, "EPIC-01 Phase D release status", phase_d_release.get("release_status"), "NOT_ELIGIBLE")
    phase_d_next_action = phase_d_record.get("next_single_action", {})
    require_equal(
        errors,
        "EPIC-01 Phase D next work item",
        phase_d_next_action.get("work_item_id"),
        EXPECTED_PHASE_D_NEXT_WORK_ITEM_ID,
    )
    require_equal(
        errors,
        "EPIC-01 Phase D next action",
        phase_d_next_action.get("action"),
        EXPECTED_PHASE_D_NEXT_ACTION,
    )

    phase_d_overlay_authority = phase_d_overlay.get("authority_boundary", {})
    phase_d_overlay_evidence = phase_d_overlay.get("open_evidence_boundaries", {})
    phase_d_overlay_formal = phase_d_overlay.get("formal_boundary", {})
    require_equal(errors, "Phase D overlay lifecycle promotion", phase_d_overlay_authority.get("changes_lifecycle_or_approval_state"), False)
    require_equal(errors, "Phase D overlay predecessor modification", phase_d_overlay_authority.get("approved_baseline_or_predecessor_modified"), False)
    require_equal(errors, "Phase D overlay formal-test claim", phase_d_overlay_authority.get("formal_test_completion_claimed"), False)
    require_equal(errors, "Phase D overlay actual-device claim", phase_d_overlay_authority.get("actual_device_completion_claimed"), False)
    require_equal(errors, "Phase D overlay external URL claim", phase_d_overlay_authority.get("external_url_decommission_claimed"), False)
    require_equal(errors, "Phase D overlay technical closure", phase_d_overlay_evidence.get("legacy_web_official_repository_technical_closure"), "COMPLETE_WITH_TRANSITIONAL_ANDROID_BFF_EXCEPTION")
    require_equal(errors, "Phase D overlay BFF extraction", phase_d_overlay_evidence.get("transitional_android_bff_extraction_status"), "NOT_COMPLETED")
    require_equal(errors, "Phase D overlay manual Next claim", phase_d_overlay_evidence.get("arbitrary_manual_next_invocation_blocked"), "NOT_CLAIMED")
    require_equal(errors, "Phase D overlay external URL status", phase_d_overlay_evidence.get("historical_external_url_decommission_status"), "NOT_RUN")
    require_equal(errors, "Phase D overlay cached PWA status", phase_d_overlay_evidence.get("previously_installed_or_cached_pwa_deactivation_status"), "NOT_RUN")
    require_equal(errors, "Phase D overlay formal tests total", phase_d_overlay_formal.get("formal_tests_total"), 279)
    require_equal(errors, "Phase D overlay formal tests NOT_RUN", phase_d_overlay_formal.get("formal_tests_not_run"), 279)
    require_equal(errors, "Phase D overlay gates status", phase_d_overlay_formal.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "Phase D overlay gates waived", phase_d_overlay_formal.get("remaining_gates_waived"), False)
    require_equal(errors, "Phase D overlay release status", phase_d_overlay_formal.get("release_status"), "NOT_ELIGIBLE")

    phase_e_metadata = phase_e_record.get("metadata", {})
    phase_e_authority = phase_e_record.get("authority_boundary", {})
    phase_e_trace = phase_e_record.get("trace", {})
    phase_e_snapshot = phase_e_record.get("implementation_snapshot", {})
    phase_e_contract = phase_e_record.get("android_gateway_extraction_contract", {})
    phase_e_verification = phase_e_record.get("internal_verification", {})
    phase_e_release = phase_e_record.get("release_boundary", {})
    require_equal(
        errors,
        "EPIC-01 Phase E record status",
        phase_e_metadata.get("status"),
        "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS",
    )
    require_equal(errors, "EPIC-01 Phase E record version", phase_e_metadata.get("version"), "0.1.0")
    require_equal(errors, "EPIC-01 Phase E record epic", phase_e_trace.get("epic_id"), "EPIC-01")
    require_equal(errors, "EPIC-01 Phase E record epic status", phase_e_trace.get("epic_status"), "IN_PROGRESS")
    require_equal(
        errors,
        "EPIC-01 Phase E planned test status",
        phase_e_trace.get("planned_test_execution_status"),
        "NOT_RUN",
    )
    require_equal(
        errors,
        "EPIC-01 Phase E implementation path count",
        phase_e_snapshot.get("file_count"),
        len(EXPECTED_PHASE_E_IMPLEMENTATION_PATHS),
    )
    require_equal(
        errors,
        "EPIC-01 Phase E implementation paths",
        [item.get("path") for item in phase_e_snapshot.get("files", [])],
        list(EXPECTED_PHASE_E_IMPLEMENTATION_PATHS),
    )
    if not object_seal_is_valid(phase_e_snapshot, "snapshot_sha256"):
        errors.append("EPIC-01 Phase E implementation snapshot seal is invalid")
    require_equal(
        errors,
        "EPIC-01 Phase E removed path count",
        phase_e_snapshot.get("removed_file_count"),
        len(EXPECTED_PHASE_E_REMOVED_NEXT_ROUTE_PATHS),
    )
    require_equal(
        errors,
        "EPIC-01 Phase E removed path evidence",
        phase_e_snapshot.get("removed_next_route_paths"),
        [
            {"path": relative, "state": "ABSENT_VERIFIED"}
            for relative in EXPECTED_PHASE_E_REMOVED_NEXT_ROUTE_PATHS
        ],
    )
    require_equal(
        errors,
        "EPIC-01 Phase E removed Next routes",
        phase_e_contract.get("removed_next_route_paths"),
        list(EXPECTED_PHASE_E_REMOVED_NEXT_ROUTE_PATHS),
    )
    require_equal(errors, "EPIC-01 Phase E Gateway component", phase_e_contract.get("component"), "apps/android-gateway")
    require_equal(errors, "EPIC-01 Phase E Gateway runtime", phase_e_contract.get("runtime"), "NODE_22_SINGLE_PROCESS")
    require_equal(errors, "EPIC-01 Phase E Gateway bind", phase_e_contract.get("official_local_bind"), "127.0.0.1:8081")
    require_equal(errors, "EPIC-01 Phase E Backend origin", phase_e_contract.get("protected_backend_origin"), "http://127.0.0.1:8000")
    require_equal(
        errors,
        "EPIC-01 Phase E public routes",
        phase_e_contract.get("public_gateway_routes"),
        [
            "/api/field-session",
            "/api/navigation/walking",
            "/api/navigation/destinations/search",
            "/api/reports/v2",
        ],
    )
    require_equal(errors, "EPIC-01 Phase E Legacy allowlist", phase_e_contract.get("legacy_runtime_allowlist"), [])
    require_equal(errors, "EPIC-01 Phase E Next fallback", phase_e_contract.get("legacy_next_fallback_allowed"), False)
    require_equal(errors, "EPIC-01 Phase E deployment status", phase_e_contract.get("deployment_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase E actual-device connectivity", phase_e_contract.get("actual_device_connectivity_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase E formal test status", phase_e_contract.get("formal_test_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase E repository extraction claim", phase_e_authority.get("claims_gateway_repository_extraction_complete"), True)
    require_equal(errors, "EPIC-01 Phase E deployment claim", phase_e_authority.get("claims_gateway_deployed"), False)
    require_equal(errors, "EPIC-01 Phase E actual-device claim", phase_e_authority.get("claims_actual_device_connectivity_pass"), False)
    require_equal(errors, "EPIC-01 Phase E formal-test claim", phase_e_authority.get("claims_formal_test_pass"), False)
    require_equal(errors, "EPIC-01 Phase E release claim", phase_e_authority.get("claims_release_eligible"), False)
    require_equal(errors, "EPIC-01 Phase E deployment execution", phase_e_verification.get("deployment_execution"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase E actual-device execution", phase_e_verification.get("actual_device_execution"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase E formal tests passed", phase_e_release.get("formal_tests_passed"), 0)
    require_equal(errors, "EPIC-01 Phase E formal tests total", phase_e_release.get("formal_tests_total"), 279)
    require_equal(errors, "EPIC-01 Phase E formal tests NOT_RUN", phase_e_release.get("formal_tests_not_run"), 279)
    require_equal(errors, "EPIC-01 Phase E release deployment", phase_e_release.get("deployment_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase E release actual-device", phase_e_release.get("actual_device_test_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase E gates status", phase_e_release.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase E gates waived", phase_e_release.get("remaining_gates_waived"), False)
    require_equal(errors, "EPIC-01 Phase E release status", phase_e_release.get("release_status"), "NOT_ELIGIBLE")
    phase_e_next_action = phase_e_record.get("next_single_action", {})
    require_equal(errors, "EPIC-01 Phase E next work item", phase_e_next_action.get("work_item_id"), EXPECTED_PHASE_E_NEXT_WORK_ITEM_ID)
    require_equal(errors, "EPIC-01 Phase E next action", phase_e_next_action.get("action"), EXPECTED_PHASE_E_NEXT_ACTION)

    phase_e_overlay_metadata = phase_e_overlay.get("metadata", {})
    phase_e_overlay_authority = phase_e_overlay.get("authority_boundary", {})
    phase_e_overlay_evidence = phase_e_overlay.get("open_evidence_boundaries", {})
    phase_e_overlay_formal = phase_e_overlay.get("formal_boundary", {})
    require_equal(errors, "Phase E overlay version", phase_e_overlay_metadata.get("version"), "0.1.0")
    require_equal(errors, "Phase E overlay status", phase_e_overlay_metadata.get("status"), "ACTIVE_EVENT_OVERLAY")
    require_equal(errors, "Phase E overlay Phase D preservation", phase_e_overlay_authority.get("phase_d_overlay_preserved"), True)
    require_equal(errors, "Phase E overlay canonical Active modification", phase_e_overlay_authority.get("canonical_active_files_modified_by_builder"), False)
    require_equal(errors, "Phase E overlay lifecycle promotion", phase_e_overlay_authority.get("changes_lifecycle_or_approval_state"), False)
    require_equal(errors, "Phase E overlay formal-test claim", phase_e_overlay_authority.get("formal_test_completion_claimed"), False)
    require_equal(errors, "Phase E overlay deployment claim", phase_e_overlay_authority.get("deployment_completion_claimed"), False)
    require_equal(errors, "Phase E overlay actual-device claim", phase_e_overlay_authority.get("actual_device_completion_claimed"), False)
    require_equal(errors, "Phase E overlay release status", phase_e_overlay_authority.get("release_status"), "NOT_ELIGIBLE")
    require_equal(errors, "Phase E overlay Legacy allowlist count", phase_e_overlay_evidence.get("legacy_runtime_allowlist_count"), 0)
    require_equal(errors, "Phase E overlay Gateway extraction", phase_e_overlay_evidence.get("independent_android_gateway_repository_extraction"), "INTERNAL_COMPLETE")
    require_equal(errors, "Phase E overlay Gateway deployment", phase_e_overlay_evidence.get("android_gateway_deployment_status"), "NOT_RUN")
    require_equal(errors, "Phase E overlay actual-device connectivity", phase_e_overlay_evidence.get("actual_device_connectivity_status"), "NOT_RUN")
    require_equal(errors, "Phase E overlay formal status", phase_e_overlay_evidence.get("formal_test_status"), "NOT_RUN")
    require_equal(errors, "Phase E overlay formal tests total", phase_e_overlay_formal.get("formal_tests_total"), 279)
    require_equal(errors, "Phase E overlay formal tests NOT_RUN", phase_e_overlay_formal.get("formal_tests_not_run"), 279)
    require_equal(errors, "Phase E overlay gates status", phase_e_overlay_formal.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "Phase E overlay gates waived", phase_e_overlay_formal.get("remaining_gates_waived"), False)
    require_equal(errors, "Phase E overlay formal release status", phase_e_overlay_formal.get("release_status"), "NOT_ELIGIBLE")

    phase_f_metadata = phase_f_record.get("metadata", {})
    phase_f_authority = phase_f_record.get("authority_boundary", {})
    phase_f_trace = phase_f_record.get("trace", {})
    phase_f_snapshot = phase_f_record.get("implementation_snapshot", {})
    phase_f_contract = phase_f_record.get("purpose_surface_contract", {})
    phase_f_verification = phase_f_record.get("internal_verification", {})
    phase_f_release = phase_f_record.get("release_boundary", {})
    require_equal(errors, "EPIC-01 Phase F record status", phase_f_metadata.get("status"), "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS")
    require_equal(errors, "EPIC-01 Phase F record version", phase_f_metadata.get("version"), "0.1.0")
    require_equal(errors, "EPIC-01 Phase F record epic", phase_f_trace.get("epic_id"), "EPIC-01")
    require_equal(errors, "EPIC-01 Phase F record epic status", phase_f_trace.get("epic_status"), "IN_PROGRESS")
    require_equal(errors, "EPIC-01 Phase F trace policy IDs", phase_f_trace.get("directly_reassessed_policy_ids"), ["FP-001"])
    require_equal(errors, "EPIC-01 Phase F trace Gap IDs", phase_f_trace.get("directly_reassessed_gap_ids"), ["GAP-010"])
    require_equal(errors, "EPIC-01 Phase F planned test status", phase_f_trace.get("planned_test_execution_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase F implementation path count", phase_f_snapshot.get("file_count"), len(EXPECTED_PHASE_F_IMPLEMENTATION_PATHS))
    require_equal(
        errors,
        "EPIC-01 Phase F implementation paths",
        [item.get("path") for item in phase_f_snapshot.get("files", [])],
        list(EXPECTED_PHASE_F_IMPLEMENTATION_PATHS),
    )
    if not object_seal_is_valid(phase_f_snapshot, "snapshot_sha256"):
        errors.append("Phase F implementation snapshot seal is invalid")
    if not object_seal_is_valid(phase_f_contract, "contract_sha256"):
        errors.append("Phase F purpose surface contract seal is invalid")
    require_equal(
        errors,
        "EPIC-01 Phase F immutable r006 implementation snapshot",
        {
            key: phase_f_snapshot.get(key)
            for key in (
                "base_commit",
                "current_head",
                "file_count",
                "path_set_sha256",
                "content_set_sha256",
            )
        },
        {
            key: gap_r006.get("implementation_snapshot", {}).get(key)
            for key in (
                "base_commit",
                "current_head",
                "file_count",
                "path_set_sha256",
                "content_set_sha256",
            )
        },
    )
    require_equal(
        errors,
        "EPIC-01 Phase F purpose statement",
        phase_f_contract.get("purpose_statement_ko"),
        "워크세이프는 시각장애인의 도심 보행 중 가까운 위험과 이동 방향을 알려 주고 손상 점자블록 신고를 돕는 안드로이드 보행 보조 서비스입니다.",
    )
    require_equal(
        errors,
        "EPIC-01 Phase F safety limitation",
        phase_f_contract.get("safety_limitation_ko"),
        "워크세이프는 보행 안전을 보장하지 않으며 흰지팡이·안내견·보호자를 대신하지 않습니다.",
    )
    require_equal(errors, "EPIC-01 Phase F report scope", phase_f_contract.get("report_scope"), "DAMAGED_TACTILE_BLOCK_ONLY")
    require_equal(errors, "EPIC-01 Phase F ambiguous reporting text", phase_f_contract.get("ambiguous_general_risk_reporting_user_text"), "ABSENT_IN_CONTROLLED_ANDROID_SURFACES")
    require_equal(errors, "EPIC-01 Phase F excluded feature boundary", phase_f_contract.get("excluded_feature_boundary"), ["보호자 추적", "넘어짐 탐지"])
    require_equal(errors, "EPIC-01 Phase F purpose alignment claim", phase_f_authority.get("claims_purpose_surfaces_internally_aligned"), True)
    require_equal(errors, "EPIC-01 Phase F REL-17 approval claim", phase_f_authority.get("claims_rel17_approved"), False)
    require_equal(errors, "EPIC-01 Phase F REL-09 execution claim", phase_f_authority.get("claims_rel09_executed_or_published"), False)
    require_equal(errors, "EPIC-01 Phase F actual-device claim", phase_f_authority.get("claims_actual_device_pass"), False)
    require_equal(errors, "EPIC-01 Phase F formal-test claim", phase_f_authority.get("claims_formal_test_pass"), False)
    require_equal(errors, "EPIC-01 Phase F release claim", phase_f_authority.get("claims_release_eligible"), False)
    require_equal(errors, "EPIC-01 Phase F actual-device execution", phase_f_verification.get("actual_device_execution"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase F REL-17 status", phase_f_release.get("rel17_status"), "DRAFT_NOT_APPROVED")
    require_equal(errors, "EPIC-01 Phase F REL-09 status", phase_f_release.get("rel09_status"), "PLANNED_NOT_RUN_NOT_PUBLISHED")
    require_equal(errors, "EPIC-01 Phase F formal tests total", phase_f_release.get("formal_tests_total"), 279)
    require_equal(errors, "EPIC-01 Phase F formal tests NOT_RUN", phase_f_release.get("formal_tests_not_run"), 279)
    require_equal(errors, "EPIC-01 Phase F gates status", phase_f_release.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase F gates waived", phase_f_release.get("remaining_gates_waived"), False)
    require_equal(errors, "EPIC-01 Phase F release status", phase_f_release.get("release_status"), "NOT_ELIGIBLE")
    phase_f_next_action = phase_f_record.get("next_single_action", {})
    require_equal(
        errors,
        "EPIC-01 Phase F next work item",
        phase_f_next_action.get("work_item_id"),
        EXPECTED_PHASE_F_NEXT_WORK_ITEM_ID,
    )
    require_equal(
        errors,
        "EPIC-01 Phase F next action",
        phase_f_next_action.get("action"),
        EXPECTED_PHASE_F_NEXT_ACTION,
    )

    phase_f_overlay_metadata = phase_f_overlay.get("metadata", {})
    phase_f_overlay_authority = phase_f_overlay.get("authority_boundary", {})
    phase_f_overlay_evidence = phase_f_overlay.get("open_evidence_boundaries", {})
    phase_f_overlay_formal = phase_f_overlay.get("formal_boundary", {})
    phase_f_overlay_events = phase_f_overlay.get("events", [])
    require_equal(errors, "Phase F overlay version", phase_f_overlay_metadata.get("version"), "0.1.0")
    require_equal(errors, "Phase F overlay status", phase_f_overlay_metadata.get("status"), "ACTIVE_EVENT_OVERLAY")
    require_equal(errors, "Phase F overlay Phase E preservation", phase_f_overlay_authority.get("phase_e_overlay_preserved"), True)
    require_equal(errors, "Phase F overlay canonical Active modification", phase_f_overlay_authority.get("canonical_active_files_modified_by_builder"), False)
    require_equal(errors, "Phase F overlay lifecycle promotion", phase_f_overlay_authority.get("changes_lifecycle_or_approval_state"), False)
    require_equal(errors, "Phase F overlay REL-17 approval claim", phase_f_overlay_authority.get("rel17_approval_claimed"), False)
    require_equal(errors, "Phase F overlay REL-09 execution claim", phase_f_overlay_authority.get("rel09_execution_or_publication_claimed"), False)
    require_equal(errors, "Phase F overlay release status", phase_f_overlay_authority.get("release_status"), "NOT_ELIGIBLE")
    require_equal(errors, "Phase F overlay event count", len(phase_f_overlay_events), 9)
    if any(
        event.get("lifecycle_status_before") != "ACTIVE"
        or event.get("lifecycle_status_after") != "ACTIVE"
        or event.get("approval_state_changed") is not False
        for event in phase_f_overlay_events
    ):
        errors.append("Phase F overlay promotes an Active artifact")
    require_equal(errors, "Phase F overlay purpose surfaces", phase_f_overlay_evidence.get("purpose_surfaces_internal_alignment"), "INTERNAL_IMPLEMENTATION_VERIFIED")
    require_equal(errors, "Phase F overlay no-destination work", phase_f_overlay_evidence.get("no_destination_hazard_conformance"), "OPEN_NEXT")
    require_equal(errors, "Phase F overlay actual-device purpose surfaces", phase_f_overlay_evidence.get("actual_device_purpose_surface_status"), "NOT_RUN")
    require_equal(errors, "Phase F overlay formal tests total", phase_f_overlay_formal.get("formal_tests_total"), 279)
    require_equal(errors, "Phase F overlay formal tests NOT_RUN", phase_f_overlay_formal.get("formal_tests_not_run"), 279)
    require_equal(errors, "Phase F overlay gates status", phase_f_overlay_formal.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "Phase F overlay gates waived", phase_f_overlay_formal.get("remaining_gates_waived"), False)
    require_equal(errors, "Phase F overlay formal release status", phase_f_overlay_formal.get("release_status"), "NOT_ELIGIBLE")

    phase_g_metadata = phase_g_record.get("metadata", {})
    phase_g_authority = phase_g_record.get("authority_boundary", {})
    phase_g_trace = phase_g_record.get("trace", {})
    phase_g_snapshot = phase_g_record.get("implementation_snapshot", {})
    phase_g_contract = phase_g_record.get("no_destination_hazard_contract", {})
    phase_g_verification = phase_g_record.get("internal_verification", {})
    phase_g_release = phase_g_record.get("release_boundary", {})
    require_equal(
        errors,
        "EPIC-01 Phase G record status",
        phase_g_metadata.get("status"),
        "INTERNAL_VERIFICATION_PASS_EPIC_IMPLEMENTATION_READY",
    )
    require_equal(errors, "EPIC-01 Phase G record version", phase_g_metadata.get("version"), "0.1.0")
    require_equal(errors, "EPIC-01 Phase G record epic", phase_g_trace.get("epic_id"), "EPIC-01")
    require_equal(
        errors,
        "EPIC-01 Phase G record epic status",
        phase_g_trace.get("epic_status"),
        "IMPLEMENTATION_READY",
    )
    require_equal(
        errors,
        "EPIC-01 Phase G direct policy IDs",
        phase_g_trace.get("directly_reassessed_policy_ids"),
        ["FP-001"],
    )
    require_equal(
        errors,
        "EPIC-01 Phase G direct Gap IDs",
        phase_g_trace.get("directly_reassessed_gap_ids"),
        ["GAP-010"],
    )
    require_equal(
        errors,
        "EPIC-01 Phase G impact policy IDs",
        phase_g_trace.get("impact_reviewed_policy_ids"),
        ["FP-019", "FP-020", "FP-021", "FP-022", "FP-027", "FP-043"],
    )
    require_equal(
        errors,
        "EPIC-01 Phase G impact Gap IDs",
        phase_g_trace.get("impact_reviewed_gap_ids"),
        ["GAP-028", "GAP-029", "GAP-030", "GAP-031", "GAP-036", "GAP-052"],
    )
    require_equal(
        errors,
        "EPIC-01 Phase G excluded next policy",
        phase_g_trace.get("excluded_policy_work", {}).get("source_policy_id"),
        EXPECTED_PHASE_G_NEXT_SOURCE_POLICY_ID,
    )
    require_equal(
        errors,
        "EPIC-01 Phase G excluded next Gap",
        phase_g_trace.get("excluded_policy_work", {}).get("gap_id"),
        EXPECTED_PHASE_G_NEXT_GAP_ID,
    )
    require_equal(
        errors,
        "EPIC-01 Phase G planned test status",
        phase_g_trace.get("planned_test_execution_status"),
        "NOT_RUN",
    )
    require_equal(
        errors,
        "EPIC-01 Phase G implementation path count",
        phase_g_snapshot.get("file_count"),
        len(EXPECTED_PHASE_G_IMPLEMENTATION_PATHS),
    )
    require_equal(
        errors,
        "EPIC-01 Phase G implementation paths",
        [item.get("path") for item in phase_g_snapshot.get("files", [])],
        list(EXPECTED_PHASE_G_IMPLEMENTATION_PATHS),
    )
    if not object_seal_is_valid(phase_g_snapshot, "snapshot_sha256"):
        errors.append("Phase G implementation snapshot seal is invalid")
    if not object_seal_is_valid(phase_g_contract, "contract_sha256"):
        errors.append("Phase G no-destination hazard contract seal is invalid")
    # Phase G is now an immutable predecessor. Its live implementation files were
    # intentionally changed by EPIC-02 Phase A, so current-file hash comparison is
    # invalid here. EXPECTED_IMMUTABLE_PREDECESSOR_SHA256 protects the Phase G
    # builder, test, record, r007 reports and overlay byte-for-byte.
    behavior_matrix = {
        item.get("state"): item
        for item in phase_g_contract.get("behavior_matrix", [])
        if isinstance(item, dict)
    }
    require_equal(
        errors,
        "Phase G behavior states",
        set(behavior_matrix),
        {
            "NO_DESTINATION_ARCORE_METRIC",
            "NO_DESTINATION_CAMERAX_NON_METRIC",
            "ROUTE_REQUEST_OR_OFF_ROUTE_OR_ARRIVAL_OR_CANCEL",
            "ACTIVE_TRUSTED_ROUTE",
        },
    )
    require_equal(
        errors,
        "Phase G no-destination ARCore general hazard",
        behavior_matrix.get("NO_DESTINATION_ARCORE_METRIC", {}).get("general_hazard"),
        "ALLOWED_WHEN_GENERAL_HAZARD_GATES_PASS",
    )
    require_equal(
        errors,
        "Phase G no-destination ARCore tactile guidance",
        behavior_matrix.get("NO_DESTINATION_ARCORE_METRIC", {}).get("tactile_local_guidance"),
        "BLOCKED_WITHOUT_TRUSTED_ROUTE",
    )
    require_equal(
        errors,
        "Phase G no-destination CameraX general hazard",
        behavior_matrix.get("NO_DESTINATION_CAMERAX_NON_METRIC", {}).get("general_hazard"),
        "LOW_SCREEN_RELATIVE_ADVISORY_ALLOWED_WHEN_CAMERA_DETECTOR_IMU_GATES_PASS",
    )
    if any(
        item.get("general_hazard_report") != "FORBIDDEN"
        for item in behavior_matrix.values()
    ):
        errors.append("Phase G behavior contract allows a general hazard report")
    non_metric = phase_g_contract.get("camera_non_metric_constraints", {})
    require_equal(
        errors,
        "Phase G CameraX required gates",
        non_metric.get("required_gates"),
        [
            "camera_permission",
            "camera_fallback_running",
            "detector_available",
            "fresh_imu",
        ],
    )
    require_equal(
        errors,
        "Phase G CameraX observational route context",
        non_metric.get("observational_context_only"),
        ["tmap_route_active"],
    )
    require_equal(
        errors,
        "Phase G CameraX forbidden authority",
        non_metric.get("forbidden_authority"),
        [
            "metric_distance",
            "steps",
            "STOP_OR_HIGH",
            "local_steering",
            "route_change",
            "report_candidate",
            "vibration",
            "safety_guarantee",
        ],
    )
    require_equal(
        errors,
        "Phase G route guidance authority",
        phase_g_contract.get("route_guidance_authority"),
        "TMAP_ROUTE_SPECIFIC_POLICY_REMAINS_FAIL_CLOSED",
    )
    require_equal(
        errors,
        "Phase G foreground screen policy",
        phase_g_contract.get("screen_policy"),
        "FOREGROUND_CAMERAX_SESSION_KEEPS_SCREEN_AWAKE",
    )
    require_equal(
        errors,
        "Phase G general hazard motion context",
        phase_g_contract.get("general_hazard_motion_context"),
        "ROUTE_NEUTRAL_WITH_LOCATION_FRESHNESS_ONLY",
    )
    require_equal(
        errors,
        "EPIC-01 Phase G implementation-ready claim",
        phase_g_authority.get("claims_epic_implementation_ready"),
        True,
    )
    require_equal(
        errors,
        "EPIC-01 Phase G complete claim",
        phase_g_authority.get("claims_epic_complete"),
        False,
    )
    require_equal(errors, "EPIC-01 Phase G actual-device claim", phase_g_authority.get("claims_actual_device_pass"), False)
    require_equal(errors, "EPIC-01 Phase G formal-test claim", phase_g_authority.get("claims_formal_test_pass"), False)
    require_equal(errors, "EPIC-01 Phase G release claim", phase_g_authority.get("claims_release_eligible"), False)
    require_equal(errors, "EPIC-01 Phase G actual-device execution", phase_g_verification.get("actual_device_execution"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase G formal tests passed", phase_g_release.get("formal_tests_passed"), 0)
    require_equal(errors, "EPIC-01 Phase G formal tests total", phase_g_release.get("formal_tests_total"), 279)
    require_equal(errors, "EPIC-01 Phase G formal tests NOT_RUN", phase_g_release.get("formal_tests_not_run"), 279)
    require_equal(errors, "EPIC-01 Phase G gate status", phase_g_release.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "EPIC-01 Phase G gates waived", phase_g_release.get("remaining_gates_waived"), False)
    require_equal(errors, "EPIC-01 Phase G release status", phase_g_release.get("release_status"), "NOT_ELIGIBLE")
    phase_g_next_action = phase_g_record.get("next_single_action", {})
    require_equal(errors, "EPIC-01 Phase G next EPIC", phase_g_next_action.get("epic_id"), "EPIC-02")
    require_equal(errors, "EPIC-01 Phase G next work item", phase_g_next_action.get("work_item_id"), EXPECTED_PHASE_G_NEXT_WORK_ITEM_ID)
    require_equal(errors, "EPIC-01 Phase G next policy", phase_g_next_action.get("source_policy_id"), EXPECTED_PHASE_G_NEXT_SOURCE_POLICY_ID)
    require_equal(errors, "EPIC-01 Phase G next Gap", phase_g_next_action.get("gap_id"), EXPECTED_PHASE_G_NEXT_GAP_ID)
    require_equal(errors, "EPIC-01 Phase G next action", phase_g_next_action.get("action"), EXPECTED_PHASE_G_NEXT_ACTION)

    phase_g_overlay_metadata = phase_g_overlay.get("metadata", {})
    phase_g_overlay_authority = phase_g_overlay.get("authority_boundary", {})
    phase_g_overlay_evidence = phase_g_overlay.get("open_evidence_boundaries", {})
    phase_g_overlay_formal = phase_g_overlay.get("formal_boundary", {})
    phase_g_overlay_events = phase_g_overlay.get("events", [])
    require_equal(errors, "Phase G overlay version", phase_g_overlay_metadata.get("version"), "0.1.0")
    require_equal(errors, "Phase G overlay status", phase_g_overlay_metadata.get("status"), "ACTIVE_EVENT_OVERLAY")
    require_equal(errors, "Phase G overlay Phase F preservation", phase_g_overlay_authority.get("phase_f_overlay_preserved"), True)
    require_equal(errors, "Phase G overlay canonical Active modification", phase_g_overlay_authority.get("canonical_active_files_modified_by_builder"), False)
    require_equal(errors, "Phase G overlay lifecycle promotion", phase_g_overlay_authority.get("changes_lifecycle_or_approval_state"), False)
    require_equal(errors, "Phase G overlay implementation-ready claim", phase_g_overlay_authority.get("epic_implementation_ready_claimed"), True)
    require_equal(errors, "Phase G overlay complete claim", phase_g_overlay_authority.get("epic_complete_claimed"), False)
    require_equal(errors, "Phase G overlay formal-test claim", phase_g_overlay_authority.get("formal_test_completion_claimed"), False)
    require_equal(errors, "Phase G overlay actual-device claim", phase_g_overlay_authority.get("actual_device_completion_claimed"), False)
    require_equal(errors, "Phase G overlay release status", phase_g_overlay_authority.get("release_status"), "NOT_ELIGIBLE")
    require_equal(errors, "Phase G overlay event count", len(phase_g_overlay_events), 9)
    if any(
        event.get("lifecycle_status_before") != "ACTIVE"
        or event.get("lifecycle_status_after") != "ACTIVE"
        or event.get("approval_state_changed") is not False
        for event in phase_g_overlay_events
    ):
        errors.append("Phase G overlay promotes an Active artifact")
    require_equal(
        errors,
        "Phase G overlay no-destination conformance",
        phase_g_overlay_evidence.get("no_destination_hazard_conformance"),
        "INTERNAL_IMPLEMENTATION_VERIFIED",
    )
    require_equal(
        errors,
        "Phase G overlay actual-device status",
        phase_g_overlay_evidence.get("actual_device_no_destination_status"),
        "NOT_RUN",
    )
    require_equal(errors, "Phase G overlay formal tests total", phase_g_overlay_formal.get("formal_tests_total"), 279)
    require_equal(errors, "Phase G overlay formal tests NOT_RUN", phase_g_overlay_formal.get("formal_tests_not_run"), 279)
    require_equal(errors, "Phase G overlay gates status", phase_g_overlay_formal.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "Phase G overlay gates waived", phase_g_overlay_formal.get("remaining_gates_waived"), False)
    require_equal(errors, "Phase G overlay formal release status", phase_g_overlay_formal.get("release_status"), "NOT_ELIGIBLE")
    phase_g_overlay_next = phase_g_overlay.get("next_single_action", {})
    require_equal(errors, "Phase G overlay next EPIC", phase_g_overlay_next.get("epic_id"), "EPIC-02")
    require_equal(errors, "Phase G overlay next work item", phase_g_overlay_next.get("work_item_id"), EXPECTED_PHASE_G_NEXT_WORK_ITEM_ID)
    require_equal(errors, "Phase G overlay next policy", phase_g_overlay_next.get("source_policy_id"), EXPECTED_PHASE_G_NEXT_SOURCE_POLICY_ID)
    require_equal(errors, "Phase G overlay next Gap", phase_g_overlay_next.get("gap_id"), EXPECTED_PHASE_G_NEXT_GAP_ID)
    require_equal(errors, "Phase G overlay next action", phase_g_overlay_next.get("action"), EXPECTED_PHASE_G_NEXT_ACTION)

    phase_a_metadata = phase_a_record.get("metadata", {})
    phase_a_authority = phase_a_record.get("authority_boundary", {})
    phase_a_trace = phase_a_record.get("trace", {})
    phase_a_snapshot = phase_a_record.get("implementation_snapshot", {})
    phase_a_contract = phase_a_record.get("walk_session_lifecycle_contract", {})
    phase_a_verification = phase_a_record.get("internal_verification", {})
    phase_a_release = phase_a_record.get("release_boundary", {})
    require_equal(
        errors,
        "EPIC-02 Phase A record status",
        phase_a_metadata.get("status"),
        "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS",
    )
    require_equal(errors, "EPIC-02 Phase A record version", phase_a_metadata.get("version"), "0.1.0")
    require_equal(errors, "EPIC-02 Phase A record epic", phase_a_trace.get("epic_id"), "EPIC-02")
    require_equal(errors, "EPIC-02 Phase A epic status", phase_a_trace.get("epic_status"), "IN_PROGRESS")
    require_equal(errors, "EPIC-02 Phase A policy IDs", phase_a_trace.get("directly_reassessed_policy_ids"), ["FP-017"])
    require_equal(errors, "EPIC-02 Phase A Gap IDs", phase_a_trace.get("directly_reassessed_gap_ids"), ["GAP-026"])
    require_equal(errors, "EPIC-02 Phase A planned tests", phase_a_trace.get("planned_test_execution_status"), "NOT_RUN")
    require_equal(errors, "EPIC-02 Phase A implementation path count", phase_a_snapshot.get("file_count"), 13)
    require_equal(
        errors,
        "EPIC-02 Phase A implementation paths",
        [item.get("path") for item in phase_a_snapshot.get("files", [])],
        list(EXPECTED_EPIC02_PHASE_A_IMPLEMENTATION_PATHS),
    )
    if not object_seal_is_valid(phase_a_snapshot, "snapshot_sha256"):
        errors.append("EPIC-02 Phase A implementation snapshot seal is invalid")
    if not object_seal_is_valid(phase_a_record, "record_content_sha256"):
        errors.append("EPIC-02 Phase A record seal is invalid")
    if not object_seal_is_valid(gap, "report_content_sha256"):
        errors.append("current Gap report seal is invalid")
    if not object_seal_is_valid(backlog, "backlog_content_sha256"):
        errors.append("current Backlog seal is invalid")
    # The sealed snapshot describes the bytes that completed FP-017. Later Goals
    # may legitimately change the same product files, so only the immutable
    # record/overlay/trace outputs are checked against fixed hashes above.
    require_equal(
        errors,
        "EPIC-02 Phase A checkpoint implementation snapshot",
        {
            key: phase_a_snapshot.get(key)
            for key in (
                "base_commit",
                "current_head",
                "file_count",
                "path_set_sha256",
                "content_set_sha256",
            )
        },
        {
            key: gap_r008_predecessor.get(
                "implementation_snapshot",
                {},
            ).get(key)
            for key in (
                "base_commit",
                "current_head",
                "file_count",
                "path_set_sha256",
                "content_set_sha256",
            )
        },
    )
    require_equal(errors, "EPIC-02 Phase A lifecycle policy", phase_a_contract.get("source_policy_id"), "FP-017")
    require_equal(errors, "EPIC-02 Phase A lifecycle scope", phase_a_contract.get("scope_assessment"), "PARTIAL_IMPLEMENTATION")
    require_equal(
        errors,
        "EPIC-02 Phase A lifecycle states",
        phase_a_contract.get("states"),
        ["READY", "ACTIVE", "PAUSED", "SAFE_STOP", "ENDED"],
    )
    require_equal(
        errors,
        "EPIC-02 Phase A recovery exact words",
        phase_a_contract.get("foreground_recovery", {}).get("accepted_exact_words"),
        ["시작", "취소"],
    )
    require_equal(
        errors,
        "EPIC-02 Phase A automatic prior-walk restore",
        phase_a_contract.get("process_restart", {}).get("automatic_previous_walk_restore"),
        False,
    )
    require_equal(
        errors,
        "EPIC-02 Phase A GPS unavailable mode",
        phase_a_contract.get("mode_specific_readiness", {}).get("gps_unavailable_result"),
        "LIMITED",
    )
    require_equal(errors, "EPIC-02 Phase A policy change claim", phase_a_authority.get("changes_approved_policy"), False)
    require_equal(errors, "EPIC-02 Phase A in-progress claim", phase_a_authority.get("claims_epic_in_progress"), True)
    require_equal(errors, "EPIC-02 Phase A implementation-ready claim", phase_a_authority.get("claims_epic_implementation_ready"), False)
    require_equal(errors, "EPIC-02 Phase A complete claim", phase_a_authority.get("claims_epic_complete"), False)
    require_equal(errors, "EPIC-02 Phase A actual-device claim", phase_a_authority.get("claims_actual_device_pass"), False)
    require_equal(errors, "EPIC-02 Phase A formal-test claim", phase_a_authority.get("claims_formal_test_pass"), False)
    require_equal(errors, "EPIC-02 Phase A release claim", phase_a_authority.get("claims_release_eligible"), False)
    require_equal(errors, "EPIC-02 Phase A actual-device execution", phase_a_verification.get("actual_device_execution"), "NOT_RUN")
    require_equal(errors, "EPIC-02 Phase A formal tests passed", phase_a_release.get("formal_tests_passed"), 0)
    require_equal(errors, "EPIC-02 Phase A formal tests total", phase_a_release.get("formal_tests_total"), 279)
    require_equal(errors, "EPIC-02 Phase A formal tests NOT_RUN", phase_a_release.get("formal_tests_not_run"), 279)
    require_equal(errors, "EPIC-02 Phase A gates status", phase_a_release.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "EPIC-02 Phase A gates waived", phase_a_release.get("remaining_gates_waived"), False)
    require_equal(errors, "EPIC-02 Phase A release status", phase_a_release.get("release_status"), "NOT_ELIGIBLE")
    phase_a_next = phase_a_record.get("next_single_action", {})
    require_equal(errors, "EPIC-02 Phase A next work item", phase_a_next.get("work_item_id"), EXPECTED_PHASE_A_NEXT_WORK_ITEM_ID)
    require_equal(errors, "EPIC-02 Phase A next policy", phase_a_next.get("source_policy_id"), EXPECTED_PHASE_A_NEXT_SOURCE_POLICY_ID)
    require_equal(errors, "EPIC-02 Phase A next Gap", phase_a_next.get("gap_id"), EXPECTED_PHASE_A_NEXT_GAP_ID)
    require_equal(errors, "EPIC-02 Phase A next action", phase_a_next.get("action"), EXPECTED_PHASE_A_NEXT_ACTION)

    phase_a_overlay_metadata = phase_a_overlay.get("metadata", {})
    phase_a_overlay_authority = phase_a_overlay.get("authority_boundary", {})
    phase_a_overlay_evidence = phase_a_overlay.get("open_evidence_boundaries", {})
    phase_a_overlay_formal = phase_a_overlay.get("formal_boundary", {})
    phase_a_overlay_events = phase_a_overlay.get("events", [])
    if not object_seal_is_valid(phase_a_overlay, "overlay_content_sha256"):
        errors.append("EPIC-02 Phase A overlay seal is invalid")
    require_equal(errors, "EPIC-02 Phase A overlay version", phase_a_overlay_metadata.get("version"), "0.1.0")
    require_equal(errors, "EPIC-02 Phase A overlay status", phase_a_overlay_metadata.get("status"), "ACTIVE_EVENT_OVERLAY")
    require_equal(errors, "EPIC-02 Phase A overlay Phase G preservation", phase_a_overlay_authority.get("phase_g_overlay_preserved"), True)
    require_equal(errors, "EPIC-02 Phase A overlay lifecycle promotion", phase_a_overlay_authority.get("changes_lifecycle_or_approval_state"), False)
    require_equal(errors, "EPIC-02 Phase A overlay in-progress claim", phase_a_overlay_authority.get("epic_in_progress_claimed"), True)
    require_equal(errors, "EPIC-02 Phase A overlay implementation-ready claim", phase_a_overlay_authority.get("epic_implementation_ready_claimed"), False)
    require_equal(errors, "EPIC-02 Phase A overlay complete claim", phase_a_overlay_authority.get("epic_complete_claimed"), False)
    require_equal(errors, "EPIC-02 Phase A overlay event count", len(phase_a_overlay_events), 9)
    if any(
        event.get("lifecycle_status_before") != "ACTIVE"
        or event.get("lifecycle_status_after") != "ACTIVE"
        or event.get("approval_state_changed") is not False
        for event in phase_a_overlay_events
    ):
        errors.append("EPIC-02 Phase A overlay promotes an Active artifact")
    require_equal(
        errors,
        "EPIC-02 Phase A overlay FP-017 status",
        phase_a_overlay_evidence.get("fp017_walk_session_lifecycle"),
        "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
    )
    require_equal(
        errors,
        "EPIC-02 Phase A overlay actual-device status",
        phase_a_overlay_evidence.get("actual_device_fp017_lifecycle_status"),
        "NOT_RUN",
    )
    require_equal(
        errors,
        "EPIC-02 Phase A overlay FP-018 status",
        phase_a_overlay_evidence.get("fp018_walk_state_recovery"),
        "PLANNED_NEXT",
    )
    require_equal(errors, "EPIC-02 Phase A overlay formal tests total", phase_a_overlay_formal.get("formal_tests_total"), 279)
    require_equal(errors, "EPIC-02 Phase A overlay formal tests NOT_RUN", phase_a_overlay_formal.get("formal_tests_not_run"), 279)
    require_equal(errors, "EPIC-02 Phase A overlay gates status", phase_a_overlay_formal.get("remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "EPIC-02 Phase A overlay gates waived", phase_a_overlay_formal.get("remaining_gates_waived"), False)
    require_equal(errors, "EPIC-02 Phase A overlay release status", phase_a_overlay_formal.get("release_status"), "NOT_ELIGIBLE")

    verification_boundary = checkpoint.get("verification_boundary", {})
    expected_gate_ids = [
        "GATE-PHONE-QUEUE-BYTE-LIMIT",
        "GATE-SERVER-CAPACITY-STATE-CONTRACT",
        "GATE-RAW-COLLECTION-RELEASE-REVIEW",
        "GATE-CLOUD-COST-MEASUREMENT",
        "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
    ]
    require_equal(errors, "verification gate IDs", verification_boundary.get("remaining_gate_ids"), expected_gate_ids)
    require_equal(errors, "EPIC-01 Phase B gate IDs", record_release.get("remaining_gates"), expected_gate_ids)
    require_equal(errors, "Phase B overlay gate IDs", overlay_formal.get("remaining_gate_ids"), expected_gate_ids)
    require_equal(errors, "EPIC-01 Phase C gate IDs", phase_c_release.get("remaining_gates"), expected_gate_ids)
    require_equal(errors, "Phase C overlay gate IDs", phase_c_overlay_formal.get("remaining_gate_ids"), expected_gate_ids)
    require_equal(errors, "EPIC-01 Phase D gate IDs", phase_d_release.get("remaining_gates"), expected_gate_ids)
    require_equal(errors, "Phase D overlay gate IDs", phase_d_overlay_formal.get("remaining_gate_ids"), expected_gate_ids)
    require_equal(errors, "EPIC-01 Phase E gate IDs", phase_e_release.get("remaining_gates"), expected_gate_ids)
    require_equal(errors, "Phase E overlay gate IDs", phase_e_overlay_formal.get("remaining_gate_ids"), expected_gate_ids)
    require_equal(errors, "EPIC-01 Phase F gate IDs", phase_f_release.get("remaining_gates"), expected_gate_ids)
    require_equal(errors, "Phase F overlay gate IDs", phase_f_overlay_formal.get("remaining_gate_ids"), expected_gate_ids)
    require_equal(errors, "EPIC-01 Phase G gate IDs", phase_g_release.get("remaining_gates"), expected_gate_ids)
    require_equal(errors, "Phase G overlay gate IDs", phase_g_overlay_formal.get("remaining_gate_ids"), expected_gate_ids)
    require_equal(errors, "EPIC-02 Phase A gate IDs", phase_a_release.get("remaining_gates"), expected_gate_ids)
    require_equal(errors, "EPIC-02 Phase A overlay gate IDs", phase_a_overlay_formal.get("remaining_gate_ids"), expected_gate_ids)
    require_equal(errors, "verification gate status", verification_boundary.get("all_remaining_gate_status"), "NOT_RUN")
    require_equal(errors, "verification formal test total", verification_boundary.get("formal_test_total"), 279)
    require_equal(errors, "verification formal test NOT_RUN", verification_boundary.get("formal_test_not_run_count"), 279)
    require_equal(errors, "verification actual device status", verification_boundary.get("actual_device_test_status"), "NOT_RUN")
    require_equal(errors, "verification approved production profile count", verification_boundary.get("approved_production_profile_count"), 0)
    require_equal(errors, "formal test pass claim", verification_boundary.get("formal_test_pass_claimed"), False)
    require_equal(errors, "implementation conformance claim", verification_boundary.get("implementation_conformance_claimed"), False)
    require_equal(errors, "release eligible boundary", verification_boundary.get("release_eligible"), False)

    snapshot = checkpoint.get("working_tree_snapshot", {})
    require_equal(
        errors,
        "working snapshot base HEAD",
        snapshot.get("base_head"),
        repository.get("snapshot_base_head"),
    )
    managed_paths = snapshot.get("managed_changed_paths")
    if not isinstance(managed_paths, list) or not managed_paths:
        errors.append("working snapshot managed_changed_paths is missing")
    elif managed_paths != sorted(set(managed_paths)):
        errors.append("working snapshot managed_changed_paths must be sorted and unique")
    else:
        require_equal(
            errors,
            "working snapshot exact controlled path manifest",
            managed_paths,
            list(EXPECTED_CONTROLLED_PATHS),
        )
        require_equal(
            errors,
            "working snapshot path count",
            snapshot.get("managed_changed_path_count"),
            len(managed_paths),
        )
        unsafe = [
            relative
            for relative in managed_paths
            if not isinstance(relative, str)
            or Path(relative).is_absolute()
            or ".." in Path(relative).parts
        ]
        if unsafe:
            errors.append(f"working snapshot paths are unsafe: {unsafe}")
        missing = [
            relative
            for relative in managed_paths
            if isinstance(relative, str)
            and resolve_safe_repo_file(root, relative) is None
        ]
        if missing:
            errors.append(f"working snapshot paths are missing: {missing}")
        elif not unsafe:
            try:
                path_hash, content_hash = working_snapshot_hashes(
                    root,
                    managed_paths,
                )
            except (OSError, RuntimeError, ValueError) as exc:
                errors.append(
                    f"working snapshot hash calculation failed safely: {exc}"
                )
            else:
                require_equal(errors, "working snapshot path-set SHA-256", path_hash, snapshot.get("path_set_sha256"))
                require_equal(errors, "working snapshot content-set SHA-256", content_hash, snapshot.get("content_set_sha256"))

        required_current_work_paths = {
            ".github/workflows/quality.yml",
            "AGENTS.md",
            "README.md",
            "apps/android/adminapp/build.gradle.kts",
            "apps/android/adminapp/src/main/AndroidManifest.xml",
            "apps/android/app/src/main/AndroidManifest.xml",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt",
            "apps/android/settings.gradle.kts",
            "apps/web/README.md",
            "configs/walksafe_product_boundary_20260722.json",
            "docs/control/README.md",
            "docs/control/execution/walksafe-epic-01-phase-a-implementation-record-20260722.json",
            "docs/control/execution/walksafe-epic-01-phase-a-implementation-record-20260722.md",
            "docs/control/execution/walksafe-epic-01-phase-b-admin-auth-recovery-implementation-record-20260722.json",
            "docs/control/execution/walksafe-single-admin-recovery-drill-protocol-20260722-r001.json",
            "docs/control/execution/walksafe-epic-01-phase-b-active-ledger-overlay-20260722-r001.json",
            "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r002.json",
            "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r002.json",
            "docs/control/walksafe-project-resumption-runbook.md",
            "scripts/build_walksafe_epic01_phase_b_trace_20260722.py",
            "scripts/check_walksafe_legacy_web_boundary_20260722.py",
            "scripts/check_walksafe_project_continuation.py",
            "scripts/run_walksafe_test_layers_20260711.sh",
            "tests/test_walksafe_android_product_boundary.py",
            "tests/test_walksafe_epic01_phase_b_trace_20260722.py",
            "tests/test_walksafe_legacy_web_boundary_20260722.py",
            "tests/test_walksafe_project_continuation.py",
        } | set(EXPECTED_PHASE_C_IMPLEMENTATION_PATHS) | set(EXPECTED_PHASE_C_TRACE_PATHS) | set(
            EXPECTED_PHASE_D_IMPLEMENTATION_PATHS
        ) | set(EXPECTED_PHASE_D_TRACE_PATHS)
        required_current_work_paths |= set(EXPECTED_PHASE_E_IMPLEMENTATION_PATHS)
        required_current_work_paths |= set(EXPECTED_PHASE_E_TRACE_PATHS)
        required_current_work_paths |= set(EXPECTED_PHASE_F_IMPLEMENTATION_PATHS)
        required_current_work_paths |= set(EXPECTED_PHASE_F_TRACE_PATHS)
        required_current_work_paths |= set(EXPECTED_PHASE_G_IMPLEMENTATION_PATHS)
        required_current_work_paths |= set(EXPECTED_PHASE_G_TRACE_PATHS)
        required_current_work_paths |= set(EXPECTED_EPIC02_PHASE_A_TRACE_PATHS)
        required_current_work_paths |= set(
            EXPECTED_FP018_IN_PROGRESS_IMPLEMENTATION_PATHS
        )
        required_current_work_paths |= set(EXPECTED_FP018_TRACE_PATHS)
        required_current_work_paths |= set(
            EXPECTED_NPC_PERMISSION_SESSION_IMPLEMENTATION_PATHS
        )
        required_current_work_paths |= set(
            EXPECTED_NPC_PERMISSION_SESSION_TRACE_PATHS
        )
        required_current_work_paths |= set(
            EXPECTED_FP004_IN_PROGRESS_IMPLEMENTATION_PATHS
        )
        required_current_work_paths |= set(
            EXPECTED_FP004_INTERRUPTED_TRACE_PATHS
        )
        required_current_work_paths |= set(EXPECTED_FP004_TRACE_PATHS)
        required_current_work_paths |= set(EXPECTED_SUPERSEDED_GOAL_PACKAGE_PATHS)
        required_current_work_paths |= set(EXPECTED_SUPERSEDED_V2_GOAL_PACKAGE_PATHS)
        required_current_work_paths |= set(EXPECTED_FROZEN_V21_GOAL_PACKAGE_PATHS)
        required_current_work_paths |= set(EXPECTED_GOAL_PACKAGE_PATHS)
        required_current_work_paths |= set(EXPECTED_CURRENT_DYNAMIC_GOAL_PATHS)
        missing_current_work_paths = sorted(required_current_work_paths - set(managed_paths))
        if missing_current_work_paths:
            errors.append(
                "working snapshot omits required current-work paths: "
                f"{missing_current_work_paths}"
            )

        checkpoint_relative = "docs/control/walksafe-project-continuation-checkpoint.json"
        if checkpoint_relative in managed_paths:
            errors.append("working snapshot must exclude its own checkpoint file to avoid a circular hash")
        require_equal(
            errors,
            "working snapshot checkpoint exclusion",
            snapshot.get("checkpoint_self_exclusion"),
            "EXCLUDED_TO_AVOID_CIRCULAR_CONTENT_HASH; VALIDATED_SEMANTICALLY",
        )

    handoff = checkpoint.get("session_handoff", {})
    expected_handoff_fields = [
        "branch",
        "source_commit_or_snapshot",
        "current_epic",
        "changed_files",
        "verification_commands_and_results",
        "remaining_blockers_and_gates",
        "next_single_action",
    ]
    require_equal(
        errors,
        "handoff required fields",
        handoff.get("required_fields"),
        expected_handoff_fields,
    )
    required_handoff_values = {
        "branch": handoff.get("branch"),
        "source_commit_or_snapshot": handoff.get("source_commit_or_snapshot"),
        "current_epic": handoff.get("current_epic"),
        "changed_files": handoff.get("changed_files"),
        "verification_commands_and_results": handoff.get("verification_commands_and_results"),
        "remaining_blockers_and_gates": handoff.get("remaining_blockers_and_gates"),
        "next_single_action": handoff.get("next_single_action"),
    }
    for field, value in required_handoff_values.items():
        if value in (None, "", []):
            errors.append(f"session handoff field is empty: {field}")
    require_equal(errors, "handoff branch", handoff.get("branch"), repository.get("branch"))
    require_equal(errors, "handoff current EPIC", handoff.get("current_epic"), current_work.get("epic_id"))
    source_snapshot = handoff.get("source_commit_or_snapshot")
    if not isinstance(source_snapshot, dict):
        errors.append("handoff source_commit_or_snapshot must be an object")
    else:
        require_equal(errors, "handoff source base commit", source_snapshot.get("base_commit"), repository.get("snapshot_base_head"))
        require_equal(errors, "handoff source current HEAD", source_snapshot.get("current_head"), head)
        require_equal(errors, "handoff source path count", source_snapshot.get("file_count"), snapshot.get("managed_changed_path_count"))
        require_equal(errors, "handoff source path hash", source_snapshot.get("path_set_sha256"), snapshot.get("path_set_sha256"))
        require_equal(
            errors,
            "handoff source content hash",
            source_snapshot.get("content_set_sha256"),
            snapshot.get("content_set_sha256"),
        )
    require_equal(errors, "handoff changed files", handoff.get("changed_files"), managed_paths)
    require_equal(errors, "handoff next action", handoff.get("next_single_action"), next_action)
    require_equal(errors, "handoff work item", handoff.get("last_updated_by_work_item"), current_work.get("epic_id"))
    require_equal(
        errors,
        "handoff verification status",
        handoff.get("last_verification_status"),
        "PASS_WITH_EPIC_IN_PROGRESS",
    )
    verification_results = handoff.get("verification_commands_and_results", [])
    if not isinstance(verification_results, list):
        errors.append("session handoff verification results must be a list")
    else:
        observed_verifications: dict[str, dict[str, Any]] = {}
        for item in verification_results:
            if not isinstance(item, dict):
                errors.append("session handoff has a non-object verification result")
                continue
            verification_id = item.get("id")
            if not isinstance(verification_id, str) or not verification_id:
                errors.append("session handoff verification result is missing id")
                continue
            if verification_id in observed_verifications:
                errors.append(f"duplicate handoff verification id: {verification_id}")
                continue
            observed_verifications[verification_id] = item
        require_equal(
            errors,
            "handoff verification IDs",
            set(observed_verifications),
            set(EXPECTED_VERIFICATION_COMMANDS),
        )
        for verification_id, expected in EXPECTED_VERIFICATION_COMMANDS.items():
            item = observed_verifications.get(verification_id, {})
            require_equal(
                errors,
                f"handoff verification {verification_id} status",
                item.get("status"),
                expected["status"],
            )
            require_equal(
                errors,
                f"handoff verification {verification_id} command",
                item.get("command"),
                expected["command"],
            )
            result = item.get("result")
            if "result" in expected:
                require_equal(
                    errors,
                    f"handoff verification {verification_id} result",
                    result,
                    expected["result"],
                )
            else:
                required_fragments = expected.get("result_required_fragments", ())
                if not isinstance(result, str) or not result.strip():
                    errors.append(f"handoff verification {verification_id} result is missing")
                else:
                    missing_fragments = [
                        fragment for fragment in required_fragments if fragment not in result
                    ]
                    if missing_fragments:
                        errors.append(
                            f"handoff verification {verification_id} result omits "
                            f"required non-claim fragments: {missing_fragments}"
                        )
            if isinstance(result, str):
                normalized_result = result.lower()
                forbidden_completion_claim = any(
                    forbidden in normalized_result
                    for forbidden in (
                        "formal tests passed",
                        "release ready",
                        "정식 시험 통과",
                        "출시 가능",
                    )
                ) or (
                    "279/279" in normalized_result
                    and "not_run" not in normalized_result
                    and "not run" not in normalized_result
                )
                if forbidden_completion_claim:
                    errors.append(
                        f"handoff verification {verification_id} contains a forbidden completion claim"
                    )

    blockers = handoff.get("remaining_blockers_and_gates", [])
    if not isinstance(blockers, list):
        errors.append("session handoff blockers and gates must be a list")
    else:
        blocker_gate_ids = {
            item.get("id")
            for item in blockers
            if isinstance(item, dict) and item.get("kind") == "RELEASE_GATE"
        }
        missing_handoff_gates = sorted(set(expected_gate_ids) - blocker_gate_ids)
        if missing_handoff_gates:
            errors.append(f"session handoff omits release gates: {missing_handoff_gates}")
        invalid_gate_entries = [
            item
            for item in blockers
            if isinstance(item, dict)
            and item.get("kind") == "RELEASE_GATE"
            and (item.get("status") != "NOT_RUN" or item.get("waived") is not False)
        ]
        if invalid_gate_entries:
            errors.append("session handoff has a release gate that is not NOT_RUN and unwaived")
        expected_implementation_blockers = {
            "ADMIN-PRODUCTION-PROVISIONING": "OPEN",
            "ADMIN-OFF-PHONE-ENCRYPTED-CUSTODY": "OPEN",
            "ADMIN-SIGNING-DISTRIBUTION-DEVICE": "DEFERRED_TO_EPIC_11",
            "ADMIN-OFFLINE-DELETE-FENCING": "OPEN",
            "ADMIN-OFFLINE-DELETE-DURABLE-JOURNAL": "OPEN",
            "ADMIN-RETENTION-CREDENTIAL-HANDOFF": "OPEN",
            "ADMIN-DB-ROLE-SEPARATION": "OPEN",
            "PHASE-C-PRODUCTION-PROFILE-EMPTY": "OPEN",
            "PHASE-C-ACTUAL-DEVICE-NOT-RUN": "NOT_RUN",
            "PHASE-C-FORMAL-TESTS-NOT-RUN": "NOT_RUN",
            "PHASE-D-ARBITRARY-MANUAL-NEXT-BYPASS": "LIMITATION_OPEN",
            "PHASE-D-HISTORICAL-EXTERNAL-URL-DECOMMISSION": "NOT_RUN",
            "PHASE-D-CACHED-PWA-DEACTIVATION": "NOT_RUN",
            "PHASE-E-GATEWAY-DEPLOYMENT-NOT-RUN": "NOT_RUN",
            "PHASE-E-ACTUAL-DEVICE-CONNECTIVITY-NOT-RUN": "NOT_RUN",
            "EPIC-02-FP017-ACTUAL-DEVICE-LIFECYCLE": "NOT_RUN",
            "EPIC-02-FP017-FORMAL": "NOT_RUN",
            "EPIC-02-FP018-ACTUAL-DEVICE-LIFECYCLE": "NOT_RUN",
            "EPIC-02-FP018-FORMAL": "NOT_RUN",
            "EPIC-02-NPC-ACTUAL-DEVICE-LIFECYCLE": "NOT_RUN",
            "EPIC-02-NPC-FORMAL": "NOT_RUN",
            "EPIC-02-NPC-EXTERNAL-RIGHTS-OPERATION": "NOT_RUN",
            "EPIC-02-FP004-FORMAL": "NOT_RUN",
            "EPIC-02-FP004-PRIORITY-USER-TEST": "NOT_RUN",
            "EPIC-02-FP004-ACTUAL-DEVICE": "NOT_RUN",
            "EPIC-02-FP004-GUARDIAN-VERIFICATION-PROVIDER": "NOT_RUN",
            "EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK": "PLANNED_NEXT",
            "EPIC-11-RELEASE-SIGNING-DISTRIBUTION": "DEFERRED_TO_EPIC_11",
            "EPIC-12-DEVICE-AND-FORMAL-VERIFICATION": "DEFERRED_TO_EPIC_12",
        }
        handoff_implementation_items = [
            item
            for item in blockers
            if isinstance(item, dict) and item.get("kind") != "RELEASE_GATE"
        ]
        handoff_implementation_ids = {
            item.get("id") for item in handoff_implementation_items
        }
        missing_implementation_blockers = sorted(
            set(expected_implementation_blockers) - handoff_implementation_ids
        )
        if missing_implementation_blockers:
            errors.append(
                "session handoff omits implementation blockers: "
                f"{missing_implementation_blockers}"
            )
        unexpected_implementation_blockers = sorted(
            handoff_implementation_ids - set(expected_implementation_blockers)
        )
        if unexpected_implementation_blockers:
            errors.append(
                "session handoff has stale or unexpected implementation blockers: "
                f"{unexpected_implementation_blockers}"
            )
        duplicate_implementation_ids = sorted(
            blocker_id
            for blocker_id in handoff_implementation_ids
            if sum(item.get("id") == blocker_id for item in handoff_implementation_items) > 1
        )
        if duplicate_implementation_ids:
            errors.append(
                "session handoff duplicates implementation blockers: "
                f"{duplicate_implementation_ids}"
            )
        observed_implementation_statuses = {
            item.get("id"): item.get("status") for item in handoff_implementation_items
        }
        for blocker_id, expected_status in expected_implementation_blockers.items():
            if blocker_id in observed_implementation_statuses:
                require_equal(
                    errors,
                    f"handoff blocker {blocker_id} status",
                    observed_implementation_statuses[blocker_id],
                    expected_status,
                )

        required_record_remaining_ids = {
            "ADMIN-PRODUCTION-PROVISIONING",
            "ADMIN-OFF-PHONE-ENCRYPTED-CUSTODY",
            "ADMIN-SIGNING-DISTRIBUTION-DEVICE",
            "ADMIN-OFFLINE-DELETE-FENCING",
            "ADMIN-OFFLINE-DELETE-DURABLE-JOURNAL",
            "ADMIN-RETENTION-CREDENTIAL-HANDOFF",
            "ADMIN-DB-ROLE-SEPARATION",
        }
        record_remaining_ids = {
            item.get("id")
            for item in phase_b_record.get("remaining_work", [])
            if isinstance(item, dict)
        }
        missing_record_remaining_ids = sorted(
            required_record_remaining_ids - record_remaining_ids
        )
        if missing_record_remaining_ids:
            errors.append(
                "EPIC-01 Phase B record omits required remaining work: "
                f"{missing_record_remaining_ids}"
            )

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--checkpoint", type=Path, default=DEFAULT_CHECKPOINT)
    parser.add_argument(
        "--print-working-snapshot-hashes",
        action="store_true",
        help="print the path/content hashes for the checkpoint path list without changing files",
    )
    parser.add_argument(
        "--print-gate-repository-state",
        action="store_true",
        help=(
            "print one deterministic canonical JSON object binding the complete "
            "Git-visible implementation-gate repository state"
        ),
    )
    parser.add_argument(
        "--gate-event-id",
        help=(
            "safe event ID used to define the exact event-scoped gate evidence "
            "transaction exclusion"
        ),
    )
    args = parser.parse_args()

    if args.print_working_snapshot_hashes and args.print_gate_repository_state:
        print(
            "WalkSafe continuation snapshot calculation: FAIL: "
            "choose exactly one print mode",
            file=sys.stderr,
        )
        return 2
    if args.gate_event_id and not args.print_gate_repository_state:
        print(
            "WalkSafe gate repository state capture: FAIL: "
            "--gate-event-id requires --print-gate-repository-state",
            file=sys.stderr,
        )
        return 2

    if args.print_gate_repository_state:
        if not args.gate_event_id:
            print(
                "WalkSafe gate repository state capture: FAIL: "
                "--gate-event-id is required",
                file=sys.stderr,
            )
            return 2
        try:
            state = capture_gate_repository_state(
                args.root.resolve(),
                args.checkpoint.resolve(),
                args.gate_event_id,
            )
        except (
            OSError,
            RuntimeError,
            ValueError,
            json.JSONDecodeError,
        ) as exc:
            print(
                f"WalkSafe gate repository state capture: FAIL: {exc}",
                file=sys.stderr,
            )
            return 2
        sys.stdout.buffer.write(canonical_json_bytes(state) + b"\n")
        return 0

    if args.print_working_snapshot_hashes:
        try:
            checkpoint = load_json(args.checkpoint.resolve())
            paths = checkpoint["working_tree_snapshot"]["managed_changed_paths"]
            if not isinstance(paths, list) or not all(isinstance(path, str) for path in paths):
                raise ValueError("working_tree_snapshot.managed_changed_paths must be a string list")
            path_hash, content_hash = working_snapshot_hashes(args.root.resolve(), paths)
        except (OSError, KeyError, ValueError, json.JSONDecodeError) as exc:
            print(f"WalkSafe working snapshot hash calculation: FAIL: {exc}", file=sys.stderr)
            return 2
        print(
            json.dumps(
                {
                    "base_commit": checkpoint["working_tree_snapshot"].get("base_head"),
                    "current_head": current_head(args.root.resolve()),
                    "file_count": len(paths),
                    "path_set_sha256": path_hash,
                    "content_set_sha256": content_hash,
                },
                ensure_ascii=False,
                indent=2,
            ),
        )
        return 0

    errors = validate(args.checkpoint.resolve(), args.root.resolve())
    if errors:
        print("WalkSafe continuation check: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    checkpoint = load_json(args.checkpoint.resolve())
    current_work = checkpoint["current_work"]
    states = checkpoint["approved_state"]["artifact_state_counts"]
    print(
        "WalkSafe continuation check: PASS "
        f"(policy 1.0.1, states {states['APPROVED_BASELINED']}/"
        f"{states['ACTIVE']}/{states['DRAFT']}/{states['PLANNED_NOT_RUN']}, "
        f"current {current_work['epic_id']} {current_work['status']}, release NOT_ELIGIBLE)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
