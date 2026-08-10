#!/usr/bin/env python3
"""Build the add-only NPC single-admin-recovery seq56/57 projection."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract


CHECKPOINT = Path("docs/control/walksafe-project-continuation-checkpoint.json")
SOURCE_SEQUENCE = 55
MATERIALIZED_SEQUENCE = 56
READY_SEQUENCE = 57

GOAL_ID = "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-npc-single-admin-recovery-r001.md"
)
WORK_ITEM_ID = "EPIC-03-NPC-SINGLE-ADMIN-RECOVERY"
POLICY_ID = "NPC-SINGLE-ADMIN-RECOVERY"
POLICY_GAP_ID = "GAP-008"
PRIORITY_RANK = 22
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PARENT_GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
PARENT_GOAL_SHA256 = (
    "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
)
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R001"
PREDECESSOR_GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp046-consent-withdrawal-deletion-r001.md"
)
PREDECESSOR_GOAL_SHA256 = (
    "f8f1fc9e9b8c1eaabe543cd64130b5a6dfa3b908b318033cb5969d4a724e8d79"
)
EPIC12_GOAL_ID = "WS-GOAL-EPIC-12"

BACKLOG_PATH = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260810-r025.json"
)
BACKLOG_ID = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260810-025"
GAP_PATH = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260810-r025.json"
)
GAP_DOCUMENT_ID = "WS-IMPLEMENTATION-GAP-ANALYSIS-20260810-025"

SOURCE_CANONICAL_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP046-20260810-001"
)
SOURCE_COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP046-20260810-001"
)
MATERIALIZED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-"
    "NPC-SINGLE-ADMIN-RECOVERY-20260810-001"
)
READY_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-"
    "NPC-SINGLE-ADMIN-RECOVERY-20260810-001"
)

START_GATE_CONTRACT_PATH = Path(
    "docs/control/execution/goal-contracts/"
    f"{GOAL_ID}/initial-start-gate-contract-r001.json"
)
START_GATE_CONTRACT_DOCUMENT_ID = (
    "WS-NPC-SINGLE-ADMIN-RECOVERY-INITIAL-START-GATE-CONTRACT-20260810-001"
)
START_GATE_CONTRACT_ID = (
    "WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-START-GATE-R001"
)
START_GATE_CONTRACT_VERSION = "2026-08-10.1"
START_GATE_CHECK_IDS = (
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_TEST_DATABASE_PREFLIGHT",
    "BACKEND_ADMIN_SECURITY_RECOVERY_POSTGRES",
    "ANDROID_ADMIN_INTERNAL",
    "ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)

NEXT_ACTION = (
    "별도 Android 관리자 앱에 추가 본인확인/패스키, 별도 복구수단, 세션 폐기, "
    "고위험 작업 재인증·동결과 감사로그를 구현한다."
)
SAFE_VERIFICATION_STATUS = (
    "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
)
MATERIALIZED_FRONTIER = [PARENT_GOAL_ID, EPIC12_GOAL_ID]
READY_FRONTIER = [GOAL_ID, PARENT_GOAL_ID, EPIC12_GOAL_ID]

SCRIPT_PATH = Path(
    "scripts/materialize_walksafe_npc_single_admin_recovery_goal_"
    "seq56_57_20260810.py"
)
TEST_PATH = Path(
    "tests/test_walksafe_npc_single_admin_recovery_goal_"
    "seq56_57_20260810.py"
)
APPLY_SCRIPT_PATH = Path(
    "scripts/apply_walksafe_npc_single_admin_recovery_goal_"
    "seq56_57_20260810.py"
)
APPLY_TEST_PATH = Path(
    "tests/test_apply_walksafe_npc_single_admin_recovery_goal_"
    "seq56_57_20260810.py"
)
SOURCE_PATHS = tuple(
    path.as_posix()
    for path in (
        GOAL_PATH,
        START_GATE_CONTRACT_PATH,
        SCRIPT_PATH,
        TEST_PATH,
        APPLY_SCRIPT_PATH,
        APPLY_TEST_PATH,
    )
)

GOAL_BODY = """# NPC-SINGLE-ADMIN-RECOVERY 한 명의 관리자와 계정 복구

## 목표

관리자와 최종 승인자를 한 명으로 유지하면서, 관리자 휴대전화 분실이나 계정 접근 상실 때 휴대전화 밖의 복구수단, 원격 세션 폐기, 고위험 작업 동결과 감사로 통제권을 안전하게 복구한다.

## 정책 기준

- 관리자 계정은 비밀번호 외 추가 인증 또는 패스키를 사용하고 공용·숨은 우회 비밀번호를 두지 않는다.
- 복구코드나 보안키는 관리자 휴대전화 밖에 보관한다.
- 휴대전화 분실 시 별도 관리 경로에서 그 기기의 로그인 상태를 폐기한다.
- 서버키·앱 서명키·관리자 복구자료는 서로 분리해 암호화 백업한다.
- 접근을 잃으면 복구할 때까지 출시·권한 변경·데이터 삭제 같은 고위험 작업을 동결한다.
- 실제 사용자시험이나 배포 전에 휴대전화 분실 복구훈련을 한 번 수행한다.
- 모델·법률·보안·접근성의 독립 검토는 관리자 한 명이라는 이유로 제거하지 않는다.

## 구현 범위

- 별도 Android 관리자 앱의 추가 본인확인·패스키 경계
- 휴대전화 밖 복구자료를 표현하는 fail-closed 저장·상태 계약
- 분실 기기와 관리자 세션의 원격 폐기 및 재사용 거부
- 관리자 접근 상실 중 출시·권한 변경·데이터 삭제의 동결
- 복구·폐기·동결·재인증 결정의 감사로그
- 정책→RQ-NPC-SINGLE-ADMIN-RECOVERY-001→설계→코드→내부 시험→GAP-008 successor 추적

## 성공 기준

1. 비밀번호 단독 또는 숨은 우회 비밀번호로 관리자 기능에 접근할 수 없다.
2. 복구자료가 관리자 휴대전화 내부에만 남지 않는다.
3. 분실 기기 세션을 별도 관리 경로에서 폐기하고 기존 자격 재사용을 거부한다.
4. 복구 전에는 출시·권한 변경·데이터 삭제가 fail-closed로 동결된다.
5. 키·복구자료 분리와 모든 고위험 결정이 감사 가능하다.
6. 내부 구현·회귀 증거와 GAP-008 재평가가 정본 해시로 결속된다.

## 계획 검증

- `TC-NPC-SINGLE-ADMIN-RECOVERY-01`: 추가 인증, 외부 복구수단, 원격 폐기, 키 분리, 고위험 동결과 독립 검토 유지
- 정식 시험과 실제 분실 복구훈련은 이 내부 Goal에서 PASS로 승격하지 않는다.

## 제외 범위

- 실제 복구코드·보안키·앱 서명키·비밀값 생성 또는 사용
- 실제 관리자 기기 분실 복구훈련
- 실제 기기·운영 시스템·외부 보안·법률·접근성 검토
- 정식 시험·배포·출시와 `GATE-SINGLE-ADMIN-RECOVERY-DRILL` 종료

## 완료 경계

목표 수준은 `INTERNAL_POLICY_CONFORMANCE_REASSESSED`다. 저장소 내부 구현·자동 검증·GAP-008 successor까지만 완료로 간주하며 실제 복구훈련·외부 검토·정식 시험·배포·출시는 미실행 상태로 유지한다.
"""


class ProjectionError(RuntimeError):
    """The exact seq55 source cannot safely produce seq56/57."""


@dataclass(frozen=True)
class SourceCheckpoint:
    raw: bytes
    raw_sha256: str
    byte_count: int
    document: dict[str, Any]
    identity: tuple[int, ...]


@dataclass(frozen=True)
class StaticInputs:
    goal_sha256: str
    backlog_binding: dict[str, str]
    gap_binding: dict[str, str]
    start_gate_binding: dict[str, str]


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ProjectionError(message)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
    )


def _strict_json(raw: bytes, label: str) -> dict[str, Any]:
    _require(not raw.startswith(b"\xef\xbb\xbf"), f"{label} contains a BOM")
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ProjectionError(f"{label} is not strict UTF-8") from exc

    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                raise ProjectionError(f"{label} contains duplicate key: {key}")
            result[key] = value
        return result

    def reject_constant(value: str) -> Any:
        raise ProjectionError(f"{label} contains non-finite number: {value}")

    try:
        parsed = json.loads(
            text,
            object_pairs_hook=pairs,
            parse_constant=reject_constant,
        )
    except json.JSONDecodeError as exc:
        raise ProjectionError(f"{label} is not valid JSON") from exc
    _require(isinstance(parsed, dict), f"{label} root is not an object")
    return parsed


def _safe_regular_file(root: Path, relative: Path, label: str) -> Path:
    _require(not relative.is_absolute() and ".." not in relative.parts, f"unsafe {label} path")
    current = root
    for part in relative.parts:
        current /= part
        _require(not current.is_symlink(), f"{label} path contains a symlink")
    metadata = current.lstat()
    _require(
        stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 1,
        f"{label} is not a single-link regular file",
    )
    return current


def render_goal_document(backlog_binding: Mapping[str, str]) -> bytes:
    expected_binding = {
        "role": "IMPLEMENTATION_BACKLOG",
        "document_id": BACKLOG_ID,
        "path": BACKLOG_PATH.as_posix(),
        "file_sha256": backlog_binding.get("file_sha256"),
    }
    _require(dict(backlog_binding) == expected_binding, "R025 backlog binding differs")
    frontmatter = f'''+++
schema_version = "2.0"
goal_id = "{GOAL_ID}"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "{PARENT_GOAL_ID}"
work_item_type = "POLICY_GAP_WORK"
priority_rank = {PRIORITY_RANK}
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "{WORK_ITEM_ID}"
start_requires = ["{PREDECESSOR_GOAL_ID}"]
completion_requires = ["{PREDECESSOR_GOAL_ID}"]
child_goal_ids = []
source_policy_ids = ["{POLICY_ID}"]
gap_ids = ["{POLICY_GAP_ID}"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "{BACKLOG_PATH.as_posix()}"
materialized_from_document_id = "{BACKLOG_ID}"
materialized_from_sha256 = "{backlog_binding['file_sha256']}"
predecessor_goal_id = "{PREDECESSOR_GOAL_ID}"
predecessor_goal_content_sha256 = "{PREDECESSOR_GOAL_SHA256}"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++
'''
    return (frontmatter + GOAL_BODY).encode("utf-8")


def _binding_map(checkpoint: dict[str, Any]) -> dict[str, dict[str, Any]]:
    bindings = checkpoint.get("canonical_bindings")
    _require(isinstance(bindings, list), "canonical bindings are missing")
    result: dict[str, dict[str, Any]] = {}
    for binding in bindings:
        _require(isinstance(binding, dict), "canonical binding is malformed")
        role = binding.get("role")
        _require(isinstance(role, str) and role not in result, "canonical role differs")
        result[role] = binding
    return result


def _require_event_chain(history: list[Any]) -> None:
    previous = ""
    for sequence, event in enumerate(history, start=1):
        _require(isinstance(event, dict), f"seq{sequence} event is malformed")
        _require(event.get("sequence") == sequence, f"seq{sequence} number differs")
        _require(
            event.get("event_sha256") == contract.event_sha256(event),
            f"seq{sequence} seal differs",
        )
        if sequence > 1:
            _require(
                event.get("previous_event_sha256") == previous,
                f"seq{sequence} lineage differs",
            )
        previous = str(event["event_sha256"])


def require_exact_source(source: dict[str, Any]) -> None:
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(isinstance(history, list) and len(history) == SOURCE_SEQUENCE, "source is not seq55")
    _require_event_chain(history)
    canonical_event, completion_event = history[-2:]
    _require(
        canonical_event.get("event_id") == SOURCE_CANONICAL_EVENT_ID
        and canonical_event.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and canonical_event.get("sequence") == 54
        and canonical_event.get("produced_by_goal_id") == PREDECESSOR_GOAL_ID,
        "source seq54 canonical producer event differs",
    )
    _require(
        completion_event.get("event_id") == SOURCE_COMPLETION_EVENT_ID
        and completion_event.get("event_type") == "GOAL_COMPLETED"
        and completion_event.get("sequence") == 55
        and completion_event.get("subject_goal_id") == PREDECESSOR_GOAL_ID
        and completion_event.get("from_status") == "IN_PROGRESS"
        and completion_event.get("to_status") == "COMPLETE_AT_TARGET"
        and completion_event.get("status_changes")
        == {PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET"},
        "source seq55 FP046 completion event differs",
    )
    _require(
        completion_event.get("canonical_update_event_sha256")
        == canonical_event.get("event_sha256"),
        "source seq54/55 producer adjacency differs",
    )
    completion_role = f"WORK_ITEM_COMPLETION::{PREDECESSOR_GOAL_ID}"
    bindings = _binding_map(source)
    completion_binding = contract.canonical_binding_snapshot(source).get(completion_role)
    _require(
        len(bindings) == 41
        and isinstance(completion_binding, dict)
        and completion_event.get("completion_receipt_binding") == completion_binding
        and completion_event.get("completion_evidence_bindings")
        == {completion_role: completion_binding}
        and state.get("completion_evidence_by_goal", {}).get(PREDECESSOR_GOAL_ID)
        == [completion_role],
        "source FP046 completion evidence differs",
    )
    statuses = state.get("status_by_goal")
    _require(isinstance(statuses, dict), "source Goal statuses are missing")
    _require(
        statuses.get(PREDECESSOR_GOAL_ID) == "COMPLETE_AT_TARGET"
        and statuses.get(PARENT_GOAL_ID) == "READY"
        and GOAL_ID not in statuses
        and "IN_PROGRESS" not in statuses.values(),
        "source completion frontier differs",
    )
    _require(
        state.get("transition_history_anchor_sha256") == completion_event.get("event_sha256")
        and state.get("focus_goal_id") == PARENT_GOAL_ID
        and state.get("focus_goal_path") == PARENT_GOAL_PATH.as_posix()
        and state.get("focus_work_item_id") == ""
        and state.get("focus_source") == "WORKSTREAM_GRAPH"
        and state.get("ready_frontier_goal_ids") == MATERIALIZED_FRONTIER,
        "source focus or frontier differs",
    )
    _require(
        not state.get("blocked_goal_ids")
        and not state.get("blockers_by_goal")
        and not state.get("pending_questions")
        and state.get("open_question_count") == 0
        and state.get("activation_status") == "ACTIVE"
        and state.get("package_status") == "ACTIVE",
        "source active unblocked boundary differs",
    )
    for collection in (
        state.get("goal_document_paths"),
        state.get("managed_goal_paths"),
    ):
        _require(isinstance(collection, list) and GOAL_PATH.as_posix() not in collection, "target Goal already exists")
    _require(
        not any(
            event.get("subject_goal_id") == GOAL_ID
            or event.get("materialized_goal_id") == GOAL_ID
            or event.get("event_id") in {MATERIALIZED_EVENT_ID, READY_EVENT_ID}
            for event in history
        ),
        "source already contains seq56/57 target history",
    )
    _require(GOAL_ID not in state.get("dynamic_goal_inventory", {}), "target inventory already exists")
    _require(
        GOAL_ID not in state.get("materialized_child_goal_ids_by_parent", {}).get(PARENT_GOAL_ID, []),
        "target child already exists",
    )
    current_snapshot = contract.canonical_binding_snapshot(source)
    _require(
        completion_event.get("canonical_binding_snapshot_after") == current_snapshot
        and canonical_event.get("canonical_binding_snapshot_after") == current_snapshot,
        "source canonical snapshot differs",
    )
    current = source.get("current_work")
    _require(
        isinstance(current, dict)
        and current.get("work_item_id") == WORK_ITEM_ID
        and current.get("next_action") == NEXT_ACTION
        and current.get("release_completion_claimed") is False,
        "source NPC next-work pointer differs",
    )
    approved = source.get("approved_state")
    verification = source.get("verification_boundary")
    _require(
        isinstance(approved, dict)
        and approved.get("release_status") == "NOT_ELIGIBLE"
        and isinstance(verification, dict)
        and verification.get("actual_device_test_status") == "NOT_RUN"
        and verification.get("all_remaining_gate_status") == "NOT_RUN"
        and verification.get("formal_test_pass_claimed") is False
        and verification.get("release_eligible") is False,
        "source zero-credit boundary differs",
    )


def load_exact_source(root: Path) -> SourceCheckpoint:
    root = root.resolve(strict=True)
    path = _safe_regular_file(root, CHECKPOINT, "checkpoint")
    descriptor = os.open(
        path,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        before = os.fstat(descriptor)
        _require(
            stat.S_IMODE(before.st_mode) == 0o600,
            "checkpoint mode is not 0600",
        )
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        after = os.fstat(descriptor)
        named = path.lstat()
        _require(
            _identity(before) == _identity(after) == _identity(named),
            "checkpoint identity changed while reading",
        )
        raw = b"".join(chunks)
    finally:
        os.close(descriptor)
    source = _strict_json(raw, "checkpoint")
    require_exact_source(source)
    return SourceCheckpoint(
        raw=raw,
        raw_sha256=sha256_bytes(raw),
        byte_count=len(raw),
        document=source,
        identity=_identity(named),
    )


def _require_json_binding_file(
    root: Path,
    binding: Mapping[str, Any],
    *,
    expected_role: str,
    expected_id: str,
    expected_path: Path,
) -> dict[str, Any]:
    expected = {
        "role": expected_role,
        "document_id": expected_id,
        "path": expected_path.as_posix(),
        "file_sha256": binding.get("file_sha256"),
    }
    _require(dict(binding) == expected, f"{expected_role} binding differs")
    path = _safe_regular_file(root, expected_path, expected_role)
    raw = path.read_bytes()
    _require(sha256_bytes(raw) == binding["file_sha256"], f"{expected_role} file SHA-256 differs")
    return _strict_json(raw, expected_role)


def _load_start_gate_contract(root: Path, goal_sha256: str) -> dict[str, str]:
    path = _safe_regular_file(root, START_GATE_CONTRACT_PATH, "start gate contract")
    raw = path.read_bytes()
    document = _strict_json(raw, "start gate contract")
    _require(
        set(document)
        == {
            "schema_version",
            "document_id",
            "contract_id",
            "contract_version",
            "target_goal_id",
            "target_goal_content_sha256",
            "gate_purpose",
            "ordered_checks",
        },
        "start gate contract field set differs",
    )
    expected_scalars = {
        "schema_version": "1.0",
        "document_id": START_GATE_CONTRACT_DOCUMENT_ID,
        "contract_id": START_GATE_CONTRACT_ID,
        "contract_version": START_GATE_CONTRACT_VERSION,
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": goal_sha256,
        "gate_purpose": "INITIAL_START",
    }
    _require(
        all(document.get(key) == value for key, value in expected_scalars.items()),
        "start gate contract identity differs",
    )
    checks = document.get("ordered_checks")
    _require(isinstance(checks, list), "start gate checks are missing")
    _require(
        tuple(item.get("check_id") for item in checks if isinstance(item, dict))
        == START_GATE_CHECK_IDS
        and all(
            set(item) == {"check_id", "command"}
            and isinstance(item.get("command"), str)
            and item["command"].strip()
            for item in checks
            if isinstance(item, dict)
        )
        and len(checks) == len(START_GATE_CHECK_IDS),
        "start gate ordered check contract differs",
    )
    commands = "\n".join(item["command"] for item in checks)
    required_tokens = (
        "check_walksafe_project_continuation_v2_4",
        "check_walksafe_goal_graph_v2_4",
        "admin",
        "recovery",
        "print-gate-repository-state",
    )
    forbidden_tokens = (
        "connectedAndroidTest",
        "installDebug",
        " adb ",
        " deploy",
        "cloudflare",
        "playwright",
    )
    lowered = commands.lower()
    _require(all(token.lower() in lowered for token in required_tokens), "start gate required command scope differs")
    _require(not any(token.lower() in lowered for token in forbidden_tokens), "start gate contains forbidden device/external/deploy scope")
    return {
        "document_id": START_GATE_CONTRACT_DOCUMENT_ID,
        "contract_id": START_GATE_CONTRACT_ID,
        "contract_version": START_GATE_CONTRACT_VERSION,
        "path": START_GATE_CONTRACT_PATH.as_posix(),
        "file_sha256": sha256_bytes(raw),
        "canonical_sha256": contract.canonical_json_sha256(document),
    }


def load_static_inputs(root: Path, source: dict[str, Any]) -> StaticInputs:
    bindings = contract.canonical_binding_snapshot(source)
    backlog_binding = bindings.get("IMPLEMENTATION_BACKLOG")
    gap_binding = bindings.get("IMPLEMENTATION_GAP")
    _require(isinstance(backlog_binding, dict), "R025 backlog binding is missing")
    _require(isinstance(gap_binding, dict), "R025 gap binding is missing")
    backlog = _require_json_binding_file(
        root,
        backlog_binding,
        expected_role="IMPLEMENTATION_BACKLOG",
        expected_id=BACKLOG_ID,
        expected_path=BACKLOG_PATH,
    )
    gap = _require_json_binding_file(
        root,
        gap_binding,
        expected_role="IMPLEMENTATION_GAP",
        expected_id=GAP_DOCUMENT_ID,
        expected_path=GAP_PATH,
    )
    expected_next = {
        "action": NEXT_ACTION,
        "epic_id": "EPIC-03",
        "gap_id": POLICY_GAP_ID,
        "priority_rank": PRIORITY_RANK,
        "source_policy_id": POLICY_ID,
        "status": "PLANNED_NEXT",
    }
    _require(backlog.get("next_single_action") == expected_next, "R025 next action differs")
    sequence_rows = [
        row
        for row in backlog.get("next_action_sequence", [])
        if isinstance(row, dict) and row.get("source_policy_id") == POLICY_ID
    ]
    _require(
        sequence_rows
        == [
            {
                "action": NEXT_ACTION,
                "epic_id": "EPIC-03",
                "order": PRIORITY_RANK,
                "priority": "P0",
                "source_policy_id": POLICY_ID,
                "status": "MISSING",
                "wave": 1,
            }
        ],
        "R025 priority-22 NPC row differs",
    )
    assessments = [
        row
        for row in gap.get("assessments", [])
        if isinstance(row, dict)
        and (row.get("source_policy_id") == POLICY_ID or row.get("gap_id") == POLICY_GAP_ID)
    ]
    _require(
        len(assessments) == 1
        and assessments[0].get("source_policy_id") == POLICY_ID
        and assessments[0].get("gap_id") == POLICY_GAP_ID
        and assessments[0].get("status") == "MISSING"
        and assessments[0].get("formal_test_status") == "NOT_RUN"
        and assessments[0].get("planned_test_ids")
        == ["TC-NPC-SINGLE-ADMIN-RECOVERY-01"],
        "R025 GAP-008 assessment differs",
    )
    goal_path = _safe_regular_file(root, GOAL_PATH, "NPC Goal")
    expected_goal = render_goal_document(backlog_binding)
    actual_goal = goal_path.read_bytes()
    _require(actual_goal == expected_goal, "NPC Goal exact contract differs")
    goal_sha256 = sha256_bytes(actual_goal)
    _require(
        _safe_regular_file(root, PREDECESSOR_GOAL_PATH, "FP046 Goal").read_bytes()
        and contract.sha256_file(root / PREDECESSOR_GOAL_PATH)
        == PREDECESSOR_GOAL_SHA256,
        "FP046 predecessor Goal differs",
    )
    _require(
        contract.sha256_file(_safe_regular_file(root, PARENT_GOAL_PATH, "EPIC03 Goal"))
        == PARENT_GOAL_SHA256,
        "EPIC03 parent Goal differs",
    )
    start_gate_binding = _load_start_gate_contract(root, goal_sha256)
    return StaticInputs(
        goal_sha256=goal_sha256,
        backlog_binding=dict(backlog_binding),
        gap_binding=dict(gap_binding),
        start_gate_binding=start_gate_binding,
    )


def require_source_working_snapshot(root: Path, source: dict[str, Any]) -> None:
    snapshot = source.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    _require(isinstance(paths, list) and paths == sorted(set(paths)), "source managed paths differ")
    path_hash, content_hash = contract.working_snapshot_hashes(root, paths)
    _require(
        snapshot.get("managed_changed_path_count") == len(paths)
        and snapshot.get("path_set_sha256") == path_hash
        and snapshot.get("content_set_sha256") == content_hash,
        "seq55 working snapshot content differs",
    )


def _derive_queue_and_boundary(
    root: Path,
    checkpoint: dict[str, Any],
    frontier: list[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    state = checkpoint["goal_execution"]
    bindings = goal_graph.frozen_goal.canonical_binding_map(checkpoint)
    register_binding = bindings.get("ARTIFACT_REGISTER")
    _require(isinstance(register_binding, dict), "ARTIFACT_REGISTER binding is missing")
    register = contract.load_json(root / register_binding["path"])
    node_errors, nodes = goal_graph.frozen_goal.current_goal_nodes(root, state)
    _require(not node_errors, "Goal node derivation failed: " + "; ".join(node_errors))
    queue_errors, queue = goal_graph.derive_v24_artifact_work_queue_from_register(
        register_binding,
        register,
        nodes,
        state["status_by_goal"],
    )
    _require(not queue_errors, "artifact queue derivation failed: " + "; ".join(queue_errors))
    boundary_errors, boundary = goal_graph.frozen_goal.derive_completion_boundary(
        nodes,
        state["status_by_goal"],
        frontier,
        state["blockers_by_goal"],
        queue,
        package_status=state["package_status"],
    )
    _require(not boundary_errors, "completion boundary derivation failed: " + "; ".join(boundary_errors))
    return queue, boundary


def _runtime_projection(
    state: dict[str, Any],
    *,
    focus_goal_id: str,
    focus_goal_path: str,
    focus_work_item_id: str,
    focus_source: str,
    frontier: list[str],
    queue: dict[str, Any],
    boundary: dict[str, Any],
) -> dict[str, Any]:
    return {
        "focus_goal_id": focus_goal_id,
        "focus_goal_path": focus_goal_path,
        "focus_work_item_id": focus_work_item_id,
        "focus_source": focus_source,
        "ready_frontier_goal_ids": copy.deepcopy(frontier),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": contract.canonical_json_sha256(queue),
        "completion_boundary_sha256": contract.canonical_json_sha256(boundary),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def _inventory_record(inputs: StaticInputs, materialized_sha256: str) -> dict[str, Any]:
    return {
        "goal_id": GOAL_ID,
        "path": GOAL_PATH.as_posix(),
        "sha256": inputs.goal_sha256,
        "goal_kind": "WORK_ITEM",
        "work_item_type": "POLICY_GAP_WORK",
        "parent_goal_id": PARENT_GOAL_ID,
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": BACKLOG_PATH.as_posix(),
        "materialized_from_document_id": BACKLOG_ID,
        "materialized_from_sha256": inputs.backlog_binding["file_sha256"],
        "predecessor_goal_id": PREDECESSOR_GOAL_ID,
        "predecessor_goal_content_sha256": PREDECESSOR_GOAL_SHA256,
        "supersedes_goal_id": "",
        "supersedes_goal_content_sha256": "",
        "artifact_work_reason": "",
        "artifact_trigger_evidence_refs": [],
        "initial_status": "PLANNED",
        "materialized_event_sha256": materialized_sha256,
    }


def _event_times(source_tail: Mapping[str, Any]) -> tuple[str, str, str, str]:
    value = source_tail.get("occurred_at")
    _require(isinstance(value, str), "seq55 occurred_at is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ProjectionError("seq55 occurred_at is invalid") from exc
    _require(parsed.tzinfo is not None, "seq55 occurred_at lacks timezone")
    materialized = parsed + timedelta(seconds=1)
    ready = materialized + timedelta(seconds=1)
    return (
        materialized.date().isoformat(),
        materialized.isoformat(),
        ready.date().isoformat(),
        ready.isoformat(),
    )


def project(
    root: Path,
    source_checkpoint: SourceCheckpoint,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    source = source_checkpoint.document
    require_exact_source(source)
    require_source_working_snapshot(root, source)
    inputs = load_static_inputs(root, source)
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    canonical_snapshot = contract.canonical_binding_snapshot(checkpoint)
    source_history = source["goal_execution"]["transition_history"]
    source_tail = source_history[-1]
    materialized_on, materialized_at, ready_on, ready_at = _event_times(source_tail)

    goal_paths = sorted([*state["goal_document_paths"], GOAL_PATH.as_posix()])
    managed_goal_paths = sorted([*state["managed_goal_paths"], GOAL_PATH.as_posix()])
    _require(len(goal_paths) == len(set(goal_paths)), "Goal path set differs")
    _require(len(managed_goal_paths) == len(set(managed_goal_paths)), "managed Goal path set differs")
    state["goal_document_paths"] = goal_paths
    state["goal_document_count"] = len(goal_paths)
    state["managed_goal_paths"] = managed_goal_paths
    state["managed_goal_path_count"] = len(managed_goal_paths)
    state["path_set_sha256"], state["content_set_sha256"] = contract.package_hashes(
        root,
        managed_goal_paths,
    )

    inventory = copy.deepcopy(state["dynamic_goal_inventory"])
    inventory[GOAL_ID] = _inventory_record(inputs, "0" * 64)
    children = copy.deepcopy(state["materialized_child_goal_ids_by_parent"])
    children[PARENT_GOAL_ID] = [*children.get(PARENT_GOAL_ID, []), GOAL_ID]
    state["dynamic_goal_inventory"] = inventory
    state["materialized_child_goal_ids_by_parent"] = children
    state["status_by_goal"][GOAL_ID] = "PLANNED"

    planned_queue, planned_boundary = _derive_queue_and_boundary(
        root,
        checkpoint,
        MATERIALIZED_FRONTIER,
    )
    source_binding = {
        "sequence": SOURCE_SEQUENCE,
        "event_sha256": source_tail["event_sha256"],
        "checkpoint_raw_sha256": source_checkpoint.raw_sha256,
        "checkpoint_raw_byte_count": source_checkpoint.byte_count,
    }
    materialized: dict[str, Any] = {
        "sequence": MATERIALIZED_SEQUENCE,
        "event_id": MATERIALIZED_EVENT_ID,
        "event_type": "GOAL_MATERIALIZED",
        "occurred_on": materialized_on,
        "occurred_at": materialized_at,
        "previous_focus_goal_id": PARENT_GOAL_ID,
        "previous_focus_content_sha256": PARENT_GOAL_SHA256,
        "focus_goal_id": PARENT_GOAL_ID,
        "focus_goal_content_sha256": PARENT_GOAL_SHA256,
        "from_status": None,
        "to_status": "PLANNED",
        "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
        "status_changes": {GOAL_ID: "PLANNED"},
        "runtime_after": _runtime_projection(
            state,
            focus_goal_id=PARENT_GOAL_ID,
            focus_goal_path=PARENT_GOAL_PATH.as_posix(),
            focus_work_item_id="",
            focus_source="WORKSTREAM_GRAPH",
            frontier=MATERIALIZED_FRONTIER,
            queue=planned_queue,
            boundary=planned_boundary,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"],
        "materialized_goal_id": GOAL_ID,
        "materialized_goal_path": GOAL_PATH.as_posix(),
        "materialized_goal_content_sha256": inputs.goal_sha256,
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": BACKLOG_PATH.as_posix(),
        "materialized_from_document_id": BACKLOG_ID,
        "materialized_from_sha256": inputs.backlog_binding["file_sha256"],
        "predecessor_goal_id": PREDECESSOR_GOAL_ID,
        "predecessor_goal_content_sha256": PREDECESSOR_GOAL_SHA256,
        "supersedes_goal_id": "",
        "supersedes_goal_content_sha256": "",
        "artifact_work_reason": "",
        "artifact_trigger_evidence_refs": [],
        "artifact_trigger_evidence_bindings": {},
        "source_working_snapshot_reconciliation": source_binding,
        "previous_event_sha256": source_tail["event_sha256"],
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    materialized["event_sha256"] = contract.event_sha256(materialized)

    inventory[GOAL_ID] = _inventory_record(inputs, materialized["event_sha256"])
    state["dynamic_goal_inventory"] = inventory
    state["status_by_goal"][GOAL_ID] = "READY"
    ready_queue, ready_boundary = _derive_queue_and_boundary(root, checkpoint, READY_FRONTIER)
    ready: dict[str, Any] = {
        "sequence": READY_SEQUENCE,
        "event_id": READY_EVENT_ID,
        "event_type": "GOAL_READY",
        "occurred_on": ready_on,
        "occurred_at": ready_at,
        "previous_focus_goal_id": PARENT_GOAL_ID,
        "previous_focus_content_sha256": PARENT_GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": inputs.goal_sha256,
        "from_status": "PLANNED",
        "to_status": "READY",
        "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
        "status_changes": {GOAL_ID: "READY"},
        "runtime_after": _runtime_projection(
            state,
            focus_goal_id=GOAL_ID,
            focus_goal_path=GOAL_PATH.as_posix(),
            focus_work_item_id=WORK_ITEM_ID,
            focus_source="IMPLEMENTATION_BACKLOG",
            frontier=READY_FRONTIER,
            queue=ready_queue,
            boundary=ready_boundary,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": ["IMPLEMENTATION_GAP"],
        "subject_goal_id": GOAL_ID,
        "readiness_basis": {
            "dependency_completion_events": [
                {
                    "goal_id": PREDECESSOR_GOAL_ID,
                    "event_sha256": source_tail["event_sha256"],
                }
            ],
            "predecessor_goal_id": PREDECESSOR_GOAL_ID,
            "predecessor_completion_event_sha256": source_tail["event_sha256"],
        },
        "start_evidence_bindings": {},
        "start_evidence_provenance": {},
        "implementation_start_gate_contract_binding": copy.deepcopy(
            inputs.start_gate_binding
        ),
        "dynamic_goal_inventory_after": copy.deepcopy(inventory),
        "materialized_child_goal_ids_by_parent_after": copy.deepcopy(children),
        "previous_event_sha256": materialized["event_sha256"],
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    ready["event_sha256"] = contract.event_sha256(ready)

    state["transition_history"].extend([materialized, ready])
    state["transition_history_anchor_sha256"] = ready["event_sha256"]
    state["validation_cutoff_at"] = ready_at
    state["focus_goal_id"] = GOAL_ID
    state["focus_goal_path"] = GOAL_PATH.as_posix()
    state["focus_work_item_id"] = WORK_ITEM_ID
    state["focus_source"] = "IMPLEMENTATION_BACKLOG"
    state["ready_frontier_goal_ids"] = copy.deepcopy(READY_FRONTIER)
    state["artifact_work_queue"] = ready_queue
    state["completion_boundary"] = ready_boundary

    current = checkpoint["current_work"]
    current["work_item_id"] = WORK_ITEM_ID
    current["status"] = "READY"
    current["current_focus"] = (
        "NPC-SINGLE-ADMIN-RECOVERY/GAP-008 Goal READY; "
        "internal start gate not run"
    )
    current["next_action"] = NEXT_ACTION
    current["release_completion_claimed"] = False

    snapshot = checkpoint["working_tree_snapshot"]
    managed_paths = sorted(set(snapshot["managed_changed_paths"]) | set(SOURCE_PATHS))
    path_hash, content_hash = contract.working_snapshot_hashes(root, managed_paths)
    snapshot["scope"] = (
        "Graph v2.4 NPC-SINGLE-ADMIN-RECOVERY/GAP-008 materialization seq56 "
        "and readiness seq57; no seq58, GOAL_STARTED, product completion, formal, "
        "device, external, deployment, or release credit."
    )
    snapshot["managed_changed_paths"] = managed_paths
    snapshot["managed_changed_path_count"] = len(managed_paths)
    snapshot["path_set_sha256"] = path_hash
    snapshot["content_set_sha256"] = content_hash

    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    handoff_snapshot = handoff["source_commit_or_snapshot"]
    handoff_snapshot["file_count"] = len(managed_paths)
    handoff_snapshot["path_set_sha256"] = path_hash
    handoff_snapshot["content_set_sha256"] = content_hash
    handoff["current_epic"] = (
        "EPIC-03 / NPC-SINGLE-ADMIN-RECOVERY/GAP-008 READY_NOT_STARTED"
    )
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = SAFE_VERIFICATION_STATUS
    return checkpoint, materialized, ready


def validate_projection(root: Path, checkpoint: dict[str, Any]) -> list[str]:
    errors, archive = contract.validate_frozen_v23_boundary(
        root,
        contract.V23_ARCHIVE_RELATIVE,
    )
    errors.extend(contract.validate_seq39_canonical_binding_authorization_request(root, checkpoint))
    errors.extend(contract.validate_seq39_canonical_binding_update(root, checkpoint))
    if archive:
        errors.extend(contract.validate_prepared_checkpoint_projection(checkpoint, archive))
        errors.extend(
            contract.validate_transition_replay(
                root,
                checkpoint,
                archive,
                expected_prepared_sha256=contract.EXPECTED_V24_PREPARED_EVENT_SHA256,
                expected_authorization_sha256=contract.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256,
            )
        )
    errors.extend(contract.validate_working_snapshot(root, checkpoint))
    errors.extend(goal_graph.validate_v24_artifact_work_queue(root, checkpoint))
    errors.extend(contract.validate_generic_event_order(checkpoint["goal_execution"]["transition_history"]))
    return errors


def require_ready_checkpoint(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    run_external_validators: bool = True,
) -> None:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(isinstance(history, list) and len(history) == READY_SEQUENCE, "ready history is not seq57")
    _require_event_chain(history)
    materialized, ready = history[-2:]
    _require(
        materialized.get("event_id") == MATERIALIZED_EVENT_ID
        and materialized.get("event_type") == "GOAL_MATERIALIZED"
        and ready.get("event_id") == READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("previous_event_sha256") == materialized.get("event_sha256"),
        "seq56/57 event chain differs",
    )
    source_completion = history[SOURCE_SEQUENCE - 1]
    _require(
        ready.get("readiness_basis")
        == {
            "dependency_completion_events": [
                {
                    "goal_id": PREDECESSOR_GOAL_ID,
                    "event_sha256": source_completion["event_sha256"],
                }
            ],
            "predecessor_goal_id": PREDECESSOR_GOAL_ID,
            "predecessor_completion_event_sha256": source_completion["event_sha256"],
        },
        "seq57 FP046 readiness basis differs",
    )
    _require(
        state.get("transition_history_anchor_sha256") == ready.get("event_sha256")
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and state.get("status_by_goal", {}).get(PREDECESSOR_GOAL_ID)
        == "COMPLETE_AT_TARGET"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH.as_posix()
        and state.get("focus_work_item_id") == WORK_ITEM_ID
        and state.get("ready_frontier_goal_ids") == READY_FRONTIER,
        "NPC READY runtime differs",
    )
    _require(
        not any(
            event.get("subject_goal_id") == GOAL_ID
            and event.get("event_type") in {"GOAL_STARTED", "GOAL_COMPLETED"}
            for event in history[:READY_SEQUENCE]
        )
        and all(event.get("sequence", 0) <= READY_SEQUENCE for event in history),
        "seq58/start/completion credit is present",
    )
    current = checkpoint.get("current_work", {})
    approved = checkpoint.get("approved_state", {})
    verification = checkpoint.get("verification_boundary", {})
    _require(
        current.get("work_item_id") == WORK_ITEM_ID
        and current.get("status") == "READY"
        and current.get("release_completion_claimed") is False
        and approved.get("release_status") == "NOT_ELIGIBLE"
        and verification.get("actual_device_test_status") == "NOT_RUN"
        and verification.get("all_remaining_gate_status") == "NOT_RUN"
        and verification.get("formal_test_pass_claimed") is False
        and verification.get("release_eligible") is False
        and checkpoint.get("session_handoff", {}).get("last_verification_status")
        == SAFE_VERIFICATION_STATUS,
        "NPC zero-credit boundary differs",
    )
    inputs = load_static_inputs(root, checkpoint)
    _require(
        ready.get("implementation_start_gate_contract_binding")
        == inputs.start_gate_binding,
        "NPC READY start-gate binding differs",
    )
    if run_external_validators:
        errors = validate_projection(root, checkpoint)
        _require(not errors, "ready projection validation differs: " + "; ".join(errors))


def preflight(root: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    source = load_exact_source(root)
    projected, materialized, ready = project(root, source)
    require_ready_checkpoint(root, projected)
    projected_state = projected["goal_execution"]
    source_state = source.document["goal_execution"]
    _require(
        projected_state["transition_history"][:SOURCE_SEQUENCE]
        == source_state["transition_history"],
        "source transition history changed",
    )
    for key in (
        "canonical_bindings",
        "approved_state",
        "authority_boundary",
        "verification_boundary",
    ):
        _require(projected.get(key) == source.document.get(key), f"source {key} changed")
    for key in (
        "completion_evidence_by_goal",
        "archived_completion_evidence_by_goal",
        "imported_predecessor_goal_bindings",
        "verification_evidence_refs",
    ):
        _require(projected_state.get(key) == source_state.get(key), f"source {key} changed")
    return projected, materialized, ready


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--preflight", action="store_true", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        projected, materialized, ready = preflight(args.root.resolve())
    except (KeyError, OSError, TypeError, ValueError, ProjectionError) as exc:
        print(
            "WalkSafe NPC single-admin-recovery seq56/57 materialization: "
            f"FAIL: {exc}",
            file=sys.stderr,
        )
        return 1
    print(
        "WalkSafe NPC single-admin-recovery seq56/57 materialization: PASS "
        f"mode=PREFLIGHT seq56={materialized['event_sha256']} "
        f"seq57={ready['event_sha256']} "
        f"managed={projected['working_tree_snapshot']['managed_changed_path_count']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
