#!/usr/bin/env python3
"""Build the append-only EPIC-01 Phase B trace and focused r002 audit.

The builder intentionally leaves the approved policy baseline, the committed
artifact application, and every r001 document unchanged.  It records internal
implementation evidence without converting a formal test or release gate to
PASS.
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
TRACE_TEST_PATH = REPO_ROOT / "tests/test_walksafe_epic01_phase_b_trace_20260722.py"
AUDIT_DIR = REPO_ROOT / "docs/control/audits"
EXECUTION_DIR = REPO_ROOT / "docs/control/execution"

PHASE_B_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-b-admin-auth-recovery-implementation-record-20260722.json"
PHASE_B_MD = EXECUTION_DIR / "walksafe-epic-01-phase-b-admin-auth-recovery-implementation-record-20260722.md"
DRILL_JSON = EXECUTION_DIR / "walksafe-single-admin-recovery-drill-protocol-20260722-r001.json"
DRILL_MD = EXECUTION_DIR / "walksafe-single-admin-recovery-drill-protocol-20260722-r001.md"
GAP_R002_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260722-r002.json"
GAP_R002_MD = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260722-r002.md"
BACKLOG_R002_JSON = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260722-r002.json"
BACKLOG_R002_MD = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260722-r002.md"
ACTIVE_OVERLAY_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-b-active-ledger-overlay-20260722-r001.json"
ACTIVE_OVERLAY_MD = EXECUTION_DIR / "walksafe-epic-01-phase-b-active-ledger-overlay-20260722-r001.md"

POLICY_MANIFEST = REPO_ROOT / "docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json"
APPLICATION_RECEIPT = REPO_ROOT / "docs/control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json"
GAP_R001 = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260722-r001.json"
BACKLOG_R001 = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260722-r001.json"
PHASE_A_RECORD = EXECUTION_DIR / "walksafe-epic-01-phase-a-implementation-record-20260722.json"
ARTIFACT_REGISTER = REPO_ROOT / "docs/deliverables/00-control/artifact-register.json"
ARTIFACT_CHANGE_LOG = REPO_ROOT / "docs/deliverables/00-control/artifact-change-log.json"

PREPARED_AT = "2026-07-22T19:45:00+09:00"
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

IMMUTABLE_SHA256 = {
    POLICY_MANIFEST: "b6f5b850a3983b8059b85d93dd07864520219d31fa65a65d740b6bab78231308",
    APPLICATION_RECEIPT: "002355b92c9862a9fbdc443a48b28657975f91fb58caf3504a28df5eb1f524bb",
    GAP_R001: "e559a9381ce13462e8d377befe5005a9d5250a0cdd33ada8a98a933bad50e8fe",
    BACKLOG_R001: "b0ad9cc6008e15f407cfe0e05620364564834d03794f200f1119b610fb66621f",
    PHASE_A_RECORD: "c4dcdd69a8cfa76881d5fbe9c7f6779849adad1468a792944cee957261debd18",
    ARTIFACT_REGISTER: "c4a5259744febc9cc442785be1748a0e0da88dbc86ef6459f1f6ee74fcaf62b6",
    ARTIFACT_CHANGE_LOG: "c4b63a01c3ae30efecb0f08c21678057626af10ed8cfef258ddf90136d0aca1c",
}

# This is a controlled evidence set, not a claim that the whole dirty working
# tree has been frozen.  Generated documents are excluded to avoid hash cycles.
PHASE_B_IMPLEMENTATION_PATHS = (
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


class TraceBuildError(RuntimeError):
    pass


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
    _require(path.is_file(), f"required JSON is missing: {_relative(path)}")
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


def _assert_immutable_inputs() -> None:
    for path, expected in IMMUTABLE_SHA256.items():
        _require(path.is_file(), f"immutable input is missing: {_relative(path)}")
        _require(
            _file_sha256(path) == expected,
            f"immutable r001/baseline input changed: {_relative(path)}",
        )
    receipt = _load_json(APPLICATION_RECEIPT)
    _require(receipt["metadata"]["transaction_status"] == "COMMITTED", "artifact application is not COMMITTED")
    manifest = _load_json(POLICY_MANIFEST)
    _require(manifest["metadata"]["baseline_id"] == "PB-WALKSAFE-FEATURE-POLICY-1.0.1", "policy baseline differs")
    gates = manifest["remaining_gates"]
    _require(tuple(item["id"] for item in gates) == GATE_IDS, "release gate set or order differs")
    _require(all(item["status"] == "NOT_RUN" for item in gates), "a release gate was executed")
    _require(
        not receipt["authority_boundary"]["remaining_gates_are_waived"],
        "a release gate was waived",
    )


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


def _file_binding(name: str, path: Path, *, immutable: bool = False) -> dict[str, Any]:
    _require(path.is_file(), f"source file is missing: {_relative(path)}")
    result = {
        "name": name,
        "path": _relative(path),
        "bytes": path.stat().st_size,
        "sha256": _file_sha256(path),
    }
    if immutable:
        result["immutability"] = "APPROVED_OR_R001_INPUT_NOT_MODIFIED"
    return result


def _object_binding(name: str, path: Path, value: dict[str, Any], hash_key: str) -> dict[str, Any]:
    return {
        "name": name,
        "path": _relative(path),
        "content_sha256": value[hash_key],
        "binding_kind": "CANONICAL_JSON_OBJECT_EXCLUDING_OWN_HASH_FIELD",
    }


def _seal(value: dict[str, Any], key: str) -> dict[str, Any]:
    _require(key not in value, f"object already contains seal field: {key}")
    value[key] = _object_sha256(value)
    return value


def _verify_seal(value: dict[str, Any], key: str) -> None:
    payload = dict(value)
    actual = payload.pop(key, None)
    _require(isinstance(actual, str) and len(actual) == 64, f"missing object seal: {key}")
    _require(actual == _object_sha256(payload), f"invalid object seal: {key}")


def _implementation_snapshot() -> dict[str, Any]:
    entries: list[dict[str, Any]] = []
    for relative in PHASE_B_IMPLEMENTATION_PATHS:
        path = REPO_ROOT / relative
        _require(path.is_file(), f"Phase B evidence path is missing: {relative}")
        entries.append({
            "path": relative,
            "bytes": path.stat().st_size,
            "sha256": _file_sha256(path),
        })
    path_set = "\n".join(item["path"] for item in entries) + "\n"
    snapshot = {
        "scope_kind": "EPIC_01_PHASE_B_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": _git_value("rev-parse", "HEAD"),
        "branch": _git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "file_count": len(entries),
        "path_set_sha256": _sha256_bytes(path_set.encode("utf-8")),
        "content_set_sha256": _object_sha256(entries),
        "files": entries,
    }
    _require(snapshot["current_head"] == BASE_COMMIT, "Phase B trace must remain based on the approved working base commit")
    _require(snapshot["branch"] == EXPECTED_BRANCH, "Phase B trace branch differs")
    return _seal(snapshot, "snapshot_sha256")


def _path_group_evidence(evidence_id: str, claim: str, paths: list[str]) -> dict[str, Any]:
    files = []
    for relative in paths:
        path = REPO_ROOT / relative
        _require(path.is_file(), f"evidence path is missing: {relative}")
        files.append({"path": relative, "sha256": _file_sha256(path)})
    return {
        "evidence_id": evidence_id,
        "kind": "CONTROLLED_IMPLEMENTATION_PATH_SET",
        "claim": claim,
        "files": files,
        "path_set_content_sha256": _object_sha256(files),
        "formal_test_evidence": False,
    }


def _build_phase_b(snapshot: dict[str, Any]) -> dict[str, Any]:
    record = {
        "schema_version": "walksafe.epic-implementation-record.v1",
        "metadata": {
            "record_id": "WS-EPIC-01-PHASE-B-ADMIN-AUTH-RECOVERY-IMPLEMENTATION-20260722-001",
            "version": "0.1.0",
            "as_of": "2026-07-22",
            "status": "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS",
            "title": "EPIC-01 Phase B 관리자 인증·복구 내부 구현 기록",
        },
        "authority_boundary": {
            "record_kind": "APPEND_ONLY_IMPLEMENTATION_EVIDENCE",
            "changes_approved_policy": False,
            "changes_approved_baseline_or_r001": False,
            "changes_formal_artifact_state": False,
            "claims_epic_implementation_ready": False,
            "claims_formal_test_pass": False,
            "claims_recovery_drill_pass": False,
            "claims_production_provisioning_complete": False,
            "claims_release_eligible": False,
        },
        "source": {
            "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "artifact_application_receipt_id": "WS-ARTIFACT-BASELINE-APPLICATION-RECEIPT-20260722-001",
            "phase_a_record_id": "WS-EPIC-01-PHASE-A-IMPLEMENTATION-20260722-001",
            "implementation_gap_predecessor_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260722-001",
            "implementation_backlog_predecessor_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260722-001",
            "base_commit": BASE_COMMIT,
            "bindings": [
                _file_binding("policy_baseline", POLICY_MANIFEST, immutable=True),
                _file_binding("artifact_application_receipt", APPLICATION_RECEIPT, immutable=True),
                _file_binding("phase_a_record", PHASE_A_RECORD, immutable=True),
                _file_binding("gap_r001", GAP_R001, immutable=True),
                _file_binding("backlog_r001", BACKLOG_R001, immutable=True),
                _file_binding("trace_builder", GENERATOR_PATH),
                _file_binding("trace_builder_test", TRACE_TEST_PATH),
            ],
        },
        "trace": {
            "epic_id": "EPIC-01",
            "epic_title": "제품 경계와 Web 앱 오염 제거",
            "epic_status": "IN_PROGRESS",
            "phase": "PHASE_B_ADMIN_AUTH_AND_RECOVERY",
            "reassessed_policy_ids": ["FP-003"],
            "related_policy_ids_not_reassessed_here": ["FP-007", "FP-047"],
            "gap_ids": ["GAP-012", "GAP-068"],
            "requirement_ids": ["RQ-FP-003-001", "RQ-GATE-SINGLE-ADMIN-RECOVERY-DRILL-001"],
            "planned_test_ids": [
                "TC-FP-003-01",
                "TC-FP-003-02",
                "TC-FP-003-03",
                "TC-FP-003-04",
                "TC-GATE-SINGLE-ADMIN-RECOVERY-DRILL-01",
            ],
            "planned_test_execution_status": "NOT_RUN",
        },
        "implementation_snapshot": snapshot,
        "implemented_controls": [
            {
                "control": "PASSWORD_TOTP_AND_REPLAY_REJECTION",
                "result": "개별 관리자 비밀번호의 scrypt 저장과 TOTP 추가 본인확인, 성공한 같은 시각구간 또는 과거 TOTP 재사용 거부를 서버 권한으로 구현했다.",
                "paths": [
                    "backend/app/services/admin_security.py",
                    "backend/app/config.py",
                    "backend/app/api/admin_security.py",
                ],
            },
            {
                "control": "SERVER_AUTHORITATIVE_DEVICE_BOUND_SESSIONS",
                "result": "불투명 세션 원문은 응답 시점에만 만들고 서버에는 SHA-256 지문만 저장하며, 관리자 앱 종류·역할·대상 API·기기 ID를 함께 검사하고 세션 조회·개별 폐기를 제공한다.",
                "paths": [
                    "backend/alembic/versions/202607220001_admin_password_totp_security.py",
                    "backend/app/models.py",
                    "backend/app/services/admin_security.py",
                    "backend/app/api/admin_security.py",
                ],
            },
            {
                "control": "FAIL_CLOSED_RECOVERY_STATE",
                "result": "복구 시작 시 기존 세션을 폐기하고 고위험 작업을 동결한다. 복구코드는 성공적으로 완료될 때만 사용 처리하며, 응답 유실 때 같은 미사용 코드·같은 기기에서 만료를 늘리지 않고 복구 증명값을 회전해 재개한다.",
                "paths": [
                    "backend/app/services/admin_security.py",
                    "backend/app/models.py",
                    "configs/walksafe_product_boundary_20260722.json",
                ],
            },
            {
                "control": "HIGH_RISK_OPERATION_GUARD",
                "result": "복구 중이거나 최근 비밀번호·TOTP 재확인이 없으면 고위험 작업을 실패 닫힘으로 차단한다. API는 실제 변경 transaction에서 다시 검사한다. 오프라인 삭제는 DB 연결이 정상 유지되는 동안 관리자 control transaction 잠금을 유지하고 성공 시 관리자·세션 결속 감사기록을 함께 commit한다.",
                "paths": [
                    "backend/app/services/admin_security.py",
                    "backend/app/field_test_security.py",
                    "backend/app/api/reports.py",
                    "scripts/walksafe_admin_high_risk_gate.py",
                    "scripts/check_report_retention_dry_run.py",
                    "scripts/manage_field_telemetry_retention_20260711.py",
                    "scripts/prune_walksafe_backups_20260711.py",
                ],
            },
            {
                "control": "ADMIN_ANDROID_SECURITY_ONLY_BOUNDARY",
                "result": "관리자 Android 앱은 보안 제어 화면만 열고 업무 기능은 잠근다. 세션·복구 증명값은 프로세스 메모리에만 두며 release 빌드는 HTTPS 관리자 API 주소가 없으면 생성하지 않는다.",
                "paths": [
                    "apps/android/adminapp/src/main/AndroidManifest.xml",
                    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminEndpointPolicy.java",
                    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java",
                    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java",
                    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityState.java",
                ],
            },
        ],
        "internal_verification": {
            "status": "PASS_RECORDED_FOR_IMPLEMENTATION_SESSION",
            "formal_evidence": False,
            "result_counts_intentionally_omitted": True,
            "commands": [
                {
                    "scope": "Android 관리자 앱 단위·조립·정적검사",
                    "command": "cd apps/android && ./gradlew :adminapp:testDebugUnitTest :adminapp:assembleDebug :adminapp:lintDebug --offline --no-daemon",
                    "status": "PASS_INTERNAL",
                },
                {
                    "scope": "격리 PostgreSQL을 포함한 백엔드 전체 회귀",
                    "command": "WALKSAFE_TEST_DATABASE_URL=<isolated-db> python -m pytest backend/tests -q",
                    "status": "PASS_INTERNAL",
                },
                {
                    "scope": "격리 PostgreSQL 관리자 전체 흐름",
                    "command": "WALKSAFE_TEST_DATABASE_URL=<isolated-db> python -m pytest backend/tests/test_admin_security.py -q",
                    "status": "PASS_INTERNAL",
                },
                {
                    "scope": "고위험 자료삭제 잠금·감사 결속과 Android 제품경계",
                    "command": "python -m pytest tests/test_walksafe_admin_high_risk_data_delete_gate.py tests/test_walksafe_backup_prune.py tests/test_walksafe_android_product_boundary.py -q",
                    "status": "PASS_INTERNAL",
                },
                {
                    "scope": "관리자 release API origin 양·음성 gate",
                    "command": "WALKSAFE_ADMIN_API_ORIGIN=https://<approved-origin> ./gradlew :adminapp:assembleRelease --offline --no-daemon; unset origin validation must fail",
                    "status": "PASS_INTERNAL_UNSIGNED_APK_ONLY",
                },
            ],
            "interpretation": "구현 세션의 내부 회귀 결과다. 승인 환경의 정식 시험, 실제 휴대전화 분실 복구훈련 또는 출시 증거가 아니다.",
        },
        "remaining_work": [
            {
                "id": "ADMIN-PRODUCTION-PROVISIONING",
                "status": "OPEN",
                "description": "실제 운영 관리자 계정·TOTP 비밀·고엔트로피 복구코드를 비밀관리 절차로 만들고 배포환경에 주입하지 않았다.",
            },
            {
                "id": "ADMIN-OFF-PHONE-ENCRYPTED-CUSTODY",
                "status": "OPEN",
                "description": "관리자 휴대전화와 분리된 암호화 복구자료·서버키·서명키 백업을 실제로 만들고 복원 확인하지 않았다.",
            },
            {
                "id": "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
                "status": "NOT_RUN",
                "description": "실제 휴대전화 분실을 가정한 외부 복구수단·세션폐기·고위험 동결·복구훈련을 실행하지 않았다.",
            },
            {
                "id": "ADMIN-SIGNING-DISTRIBUTION-DEVICE",
                "status": "DEFERRED",
                "description": "관리자 앱 실제 서명·비공개 배포·등록 실기기 검증은 후속 EPIC과 정식 시험에 남아 있다.",
            },
            {
                "id": "ADMIN-OFFLINE-DELETE-FENCING",
                "status": "OPEN",
                "description": "장시간 오프라인 삭제 중 DB 연결·transaction 잠금이 끊기면 이후 삭제를 즉시 중단시키는 operation lease·fencing과 항목별 재검증이 아직 없다.",
            },
            {
                "id": "ADMIN-OFFLINE-DELETE-DURABLE-JOURNAL",
                "status": "OPEN",
                "description": "현장 로그·백업 삭제가 일부 진행된 뒤 실패해도 독립 STARTED·항목별 진행·FAILED 기록이 남는 durable journal을 아직 강제하지 않는다.",
            },
            {
                "id": "ADMIN-RETENTION-CREDENTIAL-HANDOFF",
                "status": "OPEN",
                "description": "정기 보존삭제에 최근 관리자 재확인 세션을 파일에 저장하지 않고 일회성으로 인계하는 절차와 최소권한 DB role 계약이 아직 없다.",
            },
            {
                "id": "ADMIN-DB-ROLE-SEPARATION",
                "status": "OPEN",
                "description": "migration owner와 runtime·retention DB role을 분리하고 DDL 권한을 회수하지 않아 DB owner가 append-only 감사 trigger를 변경할 수 있는 잔여 위험이 있다.",
            },
            {
                "id": "EPIC-01-REMAINING-PHASES",
                "status": "OPEN",
                "description": "실행 중 미터 거리 preflight, Legacy Web 전체 기술 폐쇄, Android API gateway 추출과 나머지 목적 표면 정합화가 남아 있다.",
            },
        ],
        "release_boundary": {
            "formal_tests_total": FORMAL_TEST_COUNT,
            "formal_tests_passed": 0,
            "formal_test_status": "NOT_RUN",
            "remaining_gates": list(GATE_IDS),
            "remaining_gate_status": "NOT_RUN",
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "next_single_action": "EPIC-01에서 실행 중 실제 미터 거리 frame과 승인된 지정 기기 프로필을 함께 검사하는 runtime metric preflight를 구현한다.",
    }
    return _seal(record, "record_content_sha256")


def _build_drill(phase_b: dict[str, Any]) -> dict[str, Any]:
    protocol = {
        "schema_version": "walksafe.recovery-drill-protocol.v1",
        "metadata": {
            "protocol_id": "WS-SINGLE-ADMIN-RECOVERY-DRILL-PROTOCOL-20260722-001",
            "version": "0.1.0",
            "as_of": "2026-07-22",
            "status": "DRAFT_PROCEDURE_NOT_EXECUTED",
            "title": "단일 관리자 휴대전화 분실 복구훈련 절차",
        },
        "authority_boundary": {
            "procedure_only": True,
            "execution_authorized_by_this_document": False,
            "execution_completed": False,
            "gate_closed": False,
            "gate_waived": False,
            "formal_test_pass_claimed": False,
            "release_eligible": False,
        },
        "source": {
            "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "phase_b_record": _object_binding("phase_b_record", PHASE_B_JSON, phase_b, "record_content_sha256"),
            "requirement_id": "RQ-GATE-SINGLE-ADMIN-RECOVERY-DRILL-001",
            "planned_test_id": "TC-GATE-SINGLE-ADMIN-RECOVERY-DRILL-01",
            "gate_id": "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
        },
        "execution": {
            "status": "NOT_RUN",
            "started_at": None,
            "completed_at": None,
            "environment_id": None,
            "release_candidate_id": None,
            "result": None,
            "defect_ids": [],
            "evidence_files": [],
            "evidence_manifest_sha256": None,
            "approved_by": None,
            "approved_at": None,
        },
        "roles": {
            "executor": "최종관리자 1명",
            "recorder": "최종관리자 또는 지정 시험기록 담당자",
            "optional_observer": "독립 관찰자는 둘 수 있으나 정책상 두 명의 독립 확인자를 합격조건으로 요구하지 않는다.",
        },
        "preconditions": [
            "실사용 개인정보가 없는 격리된 비운영 환경과 고정된 Android 관리자 앱·백엔드·DB 형상을 준비한다.",
            "관리자 휴대전화 밖에 보관한 고엔트로피 일회용 복구코드와 회전할 새 TOTP 비밀을 서로 노출하지 않는 방식으로 준비한다.",
            "기존 관리자 기기 A와 등록할 대체 기기 B, 기존 세션 두 개 이상, 정상 상태에서 최근 재확인이 필요한 고위험 시험 동작을 준비한다.",
            "로그·화면녹화·스크린샷에서 비밀번호, TOTP, 복구코드, 원문 세션·복구 증명값, TOTP 비밀이 저장되지 않도록 수집 설정을 확인한다.",
            "중단 기준, 롤백 담당자, 시험 DB 백업과 복원 가능성을 확인한다.",
        ],
        "steps": [
            {
                "order": 1,
                "action": "고정 형상과 정상 초기상태를 기록한다.",
                "expected": "보안 상태 NORMAL, 기존 기기 A 세션 목록, 고위험 작업은 최근 비밀번호·TOTP 재확인 뒤에만 허용된다.",
                "evidence": "비밀을 제외한 앱·서버·DB migration·설정 지문, 시각, 세션 ID·기기 ID의 부분 마스킹 목록",
            },
            {
                "order": 2,
                "action": "기기 A를 사용할 수 없다고 선언하고 기기 B에서 외부 복구코드로 복구 시작을 요청한다.",
                "expected": "보안 상태가 RECOVERY_IN_PROGRESS로 바뀌고 기존 모든 세션이 폐기되며, 복구코드는 아직 사용 완료 처리되지 않는다.",
                "evidence": "상태 버전, 기존 세션 폐기 시각, 비밀을 제외한 감사 사건",
            },
            {
                "order": 3,
                "action": "기기 A의 기존 세션과 다른 기존 세션으로 상태조회 및 고위험 작업을 시도한다.",
                "expected": "기존 세션은 모두 거부되고 권한 변경·자료 삭제·출시 승인은 실패 닫힘으로 동결된다.",
                "evidence": "HTTP 상태·오류 코드와 오프라인 gate 실패 결과; 원문 증명값 제외",
            },
            {
                "order": 4,
                "action": "첫 복구 시작 응답을 유실했다고 가정하고 같은 미사용 코드·같은 기기 B로 다시 시작한다.",
                "expected": "복구 만료시각은 늘어나지 않고 새 복구 증명값만 발급되며 이전 복구 증명값은 사용할 수 없다.",
                "evidence": "마스킹된 transaction ID, 변하지 않은 만료시각, 이전 증명값 거부 결과",
            },
            {
                "order": 5,
                "action": "다른 기기 또는 다른 코드로 진행 중 복구를 가로채려 시도한다.",
                "expected": "요청은 거부되고 RECOVERY_IN_PROGRESS와 고위험 동결이 유지된다.",
                "evidence": "오류 코드, 상태 버전, 비밀 없는 감사 사건",
            },
            {
                "order": 6,
                "action": "기기 B에서 새 비밀번호, 새 외부 TOTP 비밀의 현재 코드, 최신 복구 증명값으로 복구를 완료한다.",
                "expected": "복구코드가 이 성공 시점에만 사용 처리되고 기존 세션은 계속 폐기된 채 새 기기 B 세션만 만들어지며 상태가 NORMAL로 돌아온다.",
                "evidence": "완료 시각, 사용 처리 시각, 새 세션 ID의 부분 마스킹, 상태 버전",
            },
            {
                "order": 7,
                "action": "이전 비밀번호·이전 TOTP·사용한 복구코드·이전 복구 증명값·기기 A 세션을 각각 재사용한다.",
                "expected": "모두 거부되고 성공한 TOTP 시각구간을 다시 사용해도 거부된다.",
                "evidence": "입력 원문을 저장하지 않은 거부 결과와 감사 사건",
            },
            {
                "order": 8,
                "action": "새 자격정보로 로그인하고 최근 비밀번호·TOTP 재확인 전후의 고위험 작업을 비교한다.",
                "expected": "재확인 전에는 차단되고 재확인 뒤 정해진 짧은 시간 안에서만 허용된다.",
                "evidence": "마스킹된 세션 ID, 재확인 만료시각, 전후 결과",
            },
            {
                "order": 9,
                "action": "복구자료의 휴대전화 외부 암호화 보관과 실제 열람·복원 가능성을 확인하고 시험환경을 정리한다.",
                "expected": "복구자료는 기기 B 안에만 남지 않고, 원문 비밀은 증거 묶음에 포함되지 않으며 시험용 세션과 자료가 정해진 절차로 폐기된다.",
                "evidence": "보관 매체 식별자·암호화/접근통제 확인·삭제 영수증; 비밀 원문 제외",
            },
        ],
        "pass_criteria": [
            "모든 단계의 기대 결과가 같은 고정 형상에서 충족되고 P0/P1 결함이 없다.",
            "복구 시작부터 완료까지 기존 세션과 고위험 작업이 우회 없이 차단된다.",
            "복구코드는 성공 완료 전에는 소비되지 않고 성공 뒤에는 재사용되지 않는다.",
            "응답 유실 재개는 같은 코드·같은 기기에만 허용되며 만료를 연장하지 않고 이전 증명값을 무효화한다.",
            "원문 비밀이나 실제 개인정보가 시험 증거·로그·저장소에 남지 않는다.",
            "원자료·환경·실행자·시각·결함·결론을 SHA-256 manifest로 결속하고 지정 승인 절차를 마친다.",
        ],
        "abort_and_fail_conditions": [
            "복구 중 기존 세션이나 고위험 작업이 한 번이라도 허용된다.",
            "다른 기기·다른 코드가 진행 중 transaction을 이어받는다.",
            "성공 전에 복구코드가 소비되거나 성공 뒤 다시 사용된다.",
            "원문 비밀번호·TOTP·복구코드·세션/복구 증명값·TOTP 비밀이 로그나 증거에 기록된다.",
            "증거가 불완전하거나 고정 형상과 실행환경을 재현할 수 없다.",
        ],
        "evidence_manifest_required_fields": [
            "protocol_id_and_version",
            "immutable_candidate_component_hashes",
            "environment_and_device_ids",
            "executor_and_observer_if_any",
            "started_and_completed_at",
            "step_results_without_secrets",
            "audit_and_http_result_file_hashes",
            "defect_ids_and_dispositions",
            "overall_result",
            "approver_and_approved_at",
        ],
        "release_boundary": {
            "gate_id": "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
            "gate_status": "NOT_RUN",
            "waived": False,
            "formal_test_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        },
    }
    return _seal(protocol, "protocol_content_sha256")


def _build_new_evidence(phase_b: dict[str, Any], drill: dict[str, Any]) -> list[dict[str, Any]]:
    android_paths = [
        item for item in PHASE_B_IMPLEMENTATION_PATHS
        if item.startswith("apps/android/adminapp/")
    ]
    backend_paths = [
        item for item in PHASE_B_IMPLEMENTATION_PATHS
        if item.startswith("backend/")
    ]
    high_risk_paths = [
        "backend/app/field_test_security.py",
        "backend/app/services/admin_security.py",
        "scripts/walksafe_admin_high_risk_gate.py",
        "scripts/check_report_retention_dry_run.py",
        "scripts/manage_field_telemetry_retention_20260711.py",
        "tests/test_walksafe_admin_high_risk_data_delete_gate.py",
    ]
    return [
        _path_group_evidence(
            "EVD-PHASEB-ANDROID-ADMIN-SECURITY",
            "별도 Android 관리자 앱에 인증·세션폐기·복구 보안 제어와 업무기능 잠금 경계가 존재한다.",
            android_paths,
        ),
        _path_group_evidence(
            "EVD-PHASEB-BACKEND-ADMIN-SECURITY",
            "서버 권한의 비밀번호·TOTP·재사용 거부·기기결속 세션·복구 상태·감사 저장구조와 HTTP 계약이 존재한다.",
            backend_paths,
        ),
        _path_group_evidence(
            "EVD-PHASEB-HIGH-RISK-FREEZE",
            "복구 중 또는 최근 추가 본인확인 없이 고위험 작업을 차단하는 공통 guard와 자료삭제 진입점 연결이 존재한다.",
            high_risk_paths,
        ),
        {
            "evidence_id": "EVD-PHASEB-INTERNAL-VERIFICATION-CONTRACT",
            "kind": "APPEND_ONLY_IMPLEMENTATION_RECORD",
            "path": _relative(PHASE_B_JSON),
            "record_id": phase_b["metadata"]["record_id"],
            "record_content_sha256": phase_b["record_content_sha256"],
            "claim": "내부 검증 명령과 결과 경계를 기록했으며 정식 시험·실기기·훈련 증거로 사용하지 않는다.",
            "formal_test_evidence": False,
        },
        {
            "evidence_id": "EVD-PHASEB-RECOVERY-DRILL-PROTOCOL",
            "kind": "DRAFT_PROCEDURE",
            "path": _relative(DRILL_JSON),
            "protocol_id": drill["metadata"]["protocol_id"],
            "protocol_content_sha256": drill["protocol_content_sha256"],
            "execution_status": "NOT_RUN",
            "claim": "실제 관리자 휴대전화 분실 복구훈련을 위한 절차만 작성했으며 실행 결과는 없다.",
            "formal_test_evidence": False,
        },
    ]


def _rehash_assessment(assessment: dict[str, Any]) -> None:
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = _object_sha256(assessment)


def _build_gap_r002(
    snapshot: dict[str, Any],
    phase_b: dict[str, Any],
    drill: dict[str, Any],
) -> dict[str, Any]:
    r001 = _load_json(GAP_R001)
    report = deepcopy(r001)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260722-002",
        "version": "0.2.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": r001["metadata"]["report_id"],
    }
    report["purpose"] = "불변 r001을 보존한 채 EPIC-01 Phase B 관리자 인증·복구 내부 구현으로 직접 달라진 GAP-012와 절차만 생긴 GAP-068을 집중 재평가한다."
    report["decision_precedence"] = [
        "정책 기준선 1.0.1과 COMMITTED 산출물 승인 영수증",
        "불변 Gap r001과 EPIC-01 Phase A 실행기록",
        "Phase B 통제 경로의 현재 내용 지문과 내부 검증 기록",
        "정식 시험·실기기·복구훈련은 실제 증거가 생길 때까지 NOT_RUN",
    ]
    report["source_bindings"] = [
        _file_binding("policy_baseline", POLICY_MANIFEST, immutable=True),
        _file_binding("artifact_application_receipt", APPLICATION_RECEIPT, immutable=True),
        _file_binding("gap_r001", GAP_R001, immutable=True),
        _file_binding("backlog_r001", BACKLOG_R001, immutable=True),
        _file_binding("phase_a_record", PHASE_A_RECORD, immutable=True),
        _object_binding("phase_b_record", PHASE_B_JSON, phase_b, "record_content_sha256"),
        _object_binding("recovery_drill_protocol", DRILL_JSON, drill, "protocol_content_sha256"),
        _file_binding("generator", GENERATOR_PATH),
        _file_binding("generator_test", TRACE_TEST_PATH),
    ]
    report["source_binding_sha256"] = _object_sha256(report["source_bindings"])
    report["implementation_snapshot"] = snapshot
    report["reassessment_scope"] = {
        "mode": "FOCUSED_PHASE_B_REASSESSMENT_WITH_R001_CARRY_FORWARD",
        "reassessed_gap_ids": ["GAP-012", "GAP-068"],
        "carried_forward_gap_count": 66,
        "carried_forward_gap_ids": [
            item["gap_id"] for item in r001["assessments"]
            if item["gap_id"] not in {"GAP-012", "GAP-068"}
        ],
        "carry_forward_warning": "나머지 66개는 r001 판정을 그대로 보존했으며 Phase A/B 전체 영향 재평가가 아니다. 해당 항목의 현재 구현 판정으로 과대해석하지 않는다.",
        "inherited_evidence": {
            "report_id": r001["metadata"]["report_id"],
            "path": _relative(GAP_R001),
            "file_sha256": _file_sha256(GAP_R001),
            "evidence_count": len(r001["evidence_catalog"]),
            "evidence_ids": [item["evidence_id"] for item in r001["evidence_catalog"]],
            "revalidated_in_r002": False,
        },
    }
    report["evidence_catalog"] = _build_new_evidence(phase_b, drill) + [
        {
            "evidence_id": item["evidence_id"],
            "kind": "INHERITED_R001_EVIDENCE_REFERENCE",
            "source_report_id": r001["metadata"]["report_id"],
            "source_report_path": _relative(GAP_R001),
            "source_report_file_sha256": _file_sha256(GAP_R001),
            "revalidated_in_r002": False,
            "claim": "재평가하지 않은 r001 판정의 원래 증거를 외부 참조한다. 현재 구현 증거로 재검증하지 않았다.",
            "formal_test_evidence": False,
        }
        for item in r001["evidence_catalog"]
    ]

    by_id = {item["gap_id"]: item for item in report["assessments"]}
    gap012 = by_id["GAP-012"]
    gap012.update({
        "status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "current_implementation_in_plain_language": (
            "별도 Android 관리자 앱과 서버 권한의 개별 비밀번호·TOTP 추가 본인확인, TOTP 재사용 거부, "
            "기기결속 세션 조회·폐기, 복구 상태기계, 복구 중 고위험 작업 동결을 내부 구현했다. "
            "하지만 운영 자격정보를 실제로 안전하게 만들고 주입하지 않았고, 휴대전화 밖 암호화 백업·실기기·서명·비공개 배포·실제 분실 복구훈련과 정식 시험은 미실행이다."
        ),
        "rationale": (
            "FP-003 핵심 제어의 코드와 내부 회귀 경로가 생겨 MISSING에서 PARTIAL로 바뀐다. "
            "운영 비밀 프로비저닝, 분리 보관·복원, 실제 휴대전화 분실 훈련과 정식 시험이 없으므로 IMPLEMENTED 또는 완료로 올릴 수 없다."
        ),
        "evidence_ids": [
            "EVD-PHASEB-ANDROID-ADMIN-SECURITY",
            "EVD-PHASEB-BACKEND-ADMIN-SECURITY",
            "EVD-PHASEB-HIGH-RISK-FREEZE",
            "EVD-PHASEB-INTERNAL-VERIFICATION-CONTRACT",
        ],
        "remediation": (
            "운영 관리자 자격정보와 외부 복구수단을 비밀관리 절차로 프로비저닝하고, 서버키·서명키·복구자료를 휴대전화 밖에 암호화 보관해 복원을 확인한다. "
            "고정 형상과 실제 등록 기기로 분실 복구훈련과 연결 정식 시험을 실행한다."
        ),
        "confidence": "HIGH",
        "waived": False,
    })
    _rehash_assessment(gap012)

    gap068 = by_id["GAP-068"]
    gap068.update({
        "status": "BLOCKED",
        "formal_test_status": "NOT_RUN",
        "current_implementation_in_plain_language": (
            "복구 코드와 시험 초안 절차는 마련됐지만 실제 관리자 휴대전화 분실, 외부 복구수단 사용, 기존 세션 폐기, "
            "고위험 동결과 복귀를 하나의 고정 형상에서 실행한 증거는 없다."
        ),
        "rationale": "절차 작성과 내부 단위·통합검증은 실제 복구훈련을 대신하지 않는다. 실행 결과가 없으므로 BLOCKED·NOT_RUN·미면제를 유지한다.",
        "evidence_ids": ["EVD-PHASEB-RECOVERY-DRILL-PROTOCOL"],
        "confidence": "HIGH",
        "waived": False,
    })
    _rehash_assessment(gap068)

    for finding in report["critical_findings"]:
        if finding["id"] == "CF-02":
            finding.update({
                "title": "관리자 인증·복구 핵심 제어는 내부 구현됐으나 운영 프로비저닝·실기기 복구훈련은 미실행",
                "source_ids": ["FP-003", "GATE-SINGLE-ADMIN-RECOVERY-DRILL"],
                "evidence_ids": [
                    "EVD-PHASEB-ANDROID-ADMIN-SECURITY",
                    "EVD-PHASEB-BACKEND-ADMIN-SECURITY",
                    "EVD-PHASEB-RECOVERY-DRILL-PROTOCOL",
                ],
            })

    counts = Counter(item["status"] for item in report["assessments"])
    report["summary"]["status_counts"] = dict(sorted(counts.items()))
    report["summary"]["implemented_and_formally_verified_count"] = 0
    report["summary"]["release_status"] = "NOT_ELIGIBLE"
    report["summary"]["headline"] = (
        "관리자 인증·복구 핵심 제어는 내부 구현돼 GAP-012가 PARTIAL이 됐지만, 운영 프로비저닝과 실제 복구훈련·279개 정식 시험·5개 gate가 남아 출시 적격이 아니다."
    )
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "source": "EPIC-01 Phase B append-only implementation record",
        "record_content_sha256": phase_b["record_content_sha256"],
        "commands": phase_b["internal_verification"]["commands"],
        "interpretation": "내부 코드·구성요소 회귀다. 실제 기기·복구훈련·승인 환경의 279개 정식 시험을 대신하지 않는다.",
    }
    report["authorization_boundary"].update({
        "diagnosis_only": True,
        "implementation_modified_by_this_report": False,
        "implementation_change_observed": True,
        "approved_baseline_modified": False,
        "formal_test_completion_claimed": False,
        "artifact_approval_claimed": False,
        "remaining_gates_waived": False,
        "release_status": "NOT_ELIGIBLE",
    })
    report["limitations"] = [
        "이번 r002는 Phase B가 직접 바꾼 GAP-012와 연결 gate GAP-068만 재평가했다. 나머지 66개 r001 판정은 재평가하지 않았다.",
        "코드 경로와 내부 검증 기록은 운영 비밀 프로비저닝, 실제 기기, 서명·배포, 외부 복구자료와 분실훈련을 증명하지 못한다.",
        "통제 경로 snapshot은 미커밋 작업트리의 제한된 파일집합이며 외부 서명된 출시 후보가 아니다.",
    ]
    report.pop("report_content_sha256", None)
    return _seal(report, "report_content_sha256")


def _build_backlog_r002(report: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(BACKLOG_R001)
    backlog = deepcopy(predecessor)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260722-002",
        "version": "0.2.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = report["report_content_sha256"]
    backlog["source_predecessor"] = {
        "path": _relative(BACKLOG_R001),
        "file_sha256": _file_sha256(BACKLOG_R001),
        "preserved_unchanged": True,
    }
    backlog["current_status_model"] = {
        "IN_PROGRESS": "일부 내부 구현과 검증을 남겼지만 작업 묶음의 IMPLEMENTATION_READY 조건은 아직 충족하지 않았다.",
        "PLANNED": "아직 착수 또는 완료를 주장할 실행 증거가 없는 계획 상태다.",
    }
    assessment_by_source = {item["source_policy_id"]: item for item in report["assessments"]}
    for item in backlog["next_action_sequence"]:
        source = item["source_policy_id"]
        item["status"] = assessment_by_source[source]["status"]
        item["action"] = assessment_by_source[source]["remediation"]

    epic01 = next(item for item in backlog["epics"] if item["epic_id"] == "EPIC-01")
    epic01.update({
        "current_status": "IN_PROGRESS",
        "current_status_reason": (
            "Phase A 제품경계와 Phase B 관리자 인증·복구 핵심 제어의 내부 구현 기록이 있으나, runtime metric preflight·Legacy Web 전체 기술 폐쇄·gateway 추출·목적 표면·목적지 없는 위험안내가 남아 IMPLEMENTATION_READY가 아니다."
        ),
        "completed_internal_phases": [
            "PHASE_A_PRODUCT_BOUNDARY_INTERNAL",
            "PHASE_B_ADMIN_AUTH_RECOVERY_INTERNAL",
        ],
        "phase_b_policy_status": {
            "source_policy_id": "FP-003",
            "gap_id": "GAP-012",
            "status": "PARTIAL",
            "formal_test_status": "NOT_RUN",
            "recovery_gate_status": "NOT_RUN",
        },
        "open_internal_work": [
            "EPIC-01-RUNTIME-METRIC-PREFLIGHT",
            "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE",
            "EPIC-01-NEXT-BFF-EXTRACTION",
            "EPIC-01-PURPOSE-SURFACES",
            "EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE",
        ],
    })
    backlog["next_single_action"] = {
        "epic_id": "EPIC-01",
        "work_item_id": "EPIC-01-RUNTIME-METRIC-PREFLIGHT",
        "source_policy_id": "FP-009",
        "status": "PLANNED_NEXT_WITHIN_IN_PROGRESS_EPIC",
        "action": "실행 중 실제 미터 거리 frame과 승인된 지정 기기 프로필·비어 있지 않은 프로필 버전을 안전 기능 시작 전에 함께 검사하고, 하나라도 없으면 FULL을 금지한다.",
    }
    backlog["authorization_boundary"].update({
        "implementation_change_authorized": False,
        "baseline_change_authorized": False,
        "formal_test_completion_claimed": False,
        "remaining_gates_waived": False,
        "release_status": "NOT_ELIGIBLE",
    })
    backlog.pop("backlog_content_sha256", None)
    return _seal(backlog, "backlog_content_sha256")


def _build_active_overlay(
    phase_b: dict[str, Any],
    drill: dict[str, Any],
    report: dict[str, Any],
    backlog: dict[str, Any],
) -> dict[str, Any]:
    register = _load_json(ARTIFACT_REGISTER)
    rows = {item["display_code"]: item for item in register["artifacts"]}
    _require(all(rows[code]["state"]["lifecycle_status"] == "ACTIVE" for code in ACTIVE_ARTIFACT_CODES), "expected Active artifact is not ACTIVE")
    _require(rows["DEV-18"]["state"]["lifecycle_status"] == "DRAFT", "DEV-18 must remain Draft")

    event_summaries = {
        "DOC-01": "Phase B 새 실행·감사·절차·overlay 경로를 다음 정식 Active writer에서 위치·지문과 함께 등록한다.",
        "DOC-05": "승인 정책 변경 없이 EPIC-01 Phase B 내부 구현과 r002 진단을 추가한 append 사건을 기록한다.",
        "DSC-14": "EPIC-01을 IN_PROGRESS로 유지하고 Phase B 내부 작업 완료와 runtime metric preflight 다음 행동을 연결한다.",
        "REQ-16": "RQ-FP-003-001과 RQ-GATE-SINGLE-ADMIN-RECOVERY-DRILL-001을 구현 기록·절차·GAP-012/068에 역추적한다.",
        "DES-06": "PASSWORD_TOTP, 서버권한 기기결속 세션, 성공 시 복구코드 소비, 동일 코드·기기 응답유실 재개, 고위험 동결 결정을 구현 증거에 연결한다.",
        "DEV-15": "Phase B 내부 Android·백엔드·고위험 gate 검증 명령과 비정식 한계를 기록한다.",
        "SEC-03": "운영 프로비저닝·외부 암호화 보관·실기기 복구훈련·비밀 없는 증거수집을 OPEN 위험으로 유지한다.",
        "TST-19": "내부 회귀는 별도 내부 지표로만 기록하고 정식 279개 시험 PASS 수는 0으로 유지한다.",
        "TST-21": "GAP-068과 5개 gate NOT_RUN·미면제, 관리자 서명·배포·실기기 검증 미실행을 잔여위험으로 유지한다.",
    }
    events = []
    for index, code in enumerate(ACTIVE_ARTIFACT_CODES, 1):
        row = rows[code]
        events.append({
            "event_id": f"WS-EPIC01-PHASEB-ACTIVE-{index:03d}",
            "artifact_code": code,
            "artifact_instance_id": row["artifact_instance_id"],
            "predecessor_opening_snapshot_id": row["version"]["snapshot_id"],
            "canonical_path": row["location"]["canonical_path"],
            "update_mode": "APPEND_OR_SUCCESSOR_REVISION_ONLY",
            "lifecycle_status_before": "ACTIVE",
            "lifecycle_status_after": "ACTIVE",
            "approval_state_changed": False,
            "summary": event_summaries[code],
            "evidence_record_ids": [
                phase_b["metadata"]["record_id"],
                report["metadata"]["report_id"],
                backlog["metadata"]["backlog_id"],
            ],
        })

    overlay = {
        "schema_version": "walksafe.active-ledger-event-overlay.v1",
        "metadata": {
            "overlay_id": "WS-EPIC-01-PHASE-B-ACTIVE-LEDGER-OVERLAY-20260722-001",
            "version": "0.1.0",
            "as_of": "2026-07-22",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-01 Phase B 최소 Active 원장 successor overlay",
        },
        "authority_boundary": {
            "approved_opening_snapshots_preserved": True,
            "canonical_active_files_modified_by_builder": False,
            "approved_baseline_or_r001_modified": False,
            "creates_new_artifact_type": False,
            "changes_lifecycle_or_approval_state": False,
            "formal_test_completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            _file_binding("artifact_register_opening_successor_source", ARTIFACT_REGISTER, immutable=True),
            _file_binding("artifact_change_log_opening_successor_source", ARTIFACT_CHANGE_LOG, immutable=True),
            _object_binding("phase_b_record", PHASE_B_JSON, phase_b, "record_content_sha256"),
            _object_binding("recovery_drill_protocol", DRILL_JSON, drill, "protocol_content_sha256"),
            _object_binding("gap_r002", GAP_R002_JSON, report, "report_content_sha256"),
            _object_binding("backlog_r002", BACKLOG_R002_JSON, backlog, "backlog_content_sha256"),
        ],
        "application_rule": {
            "effective_for_phase_b_trace": True,
            "canonical_merge_required_for_next_doc01_snapshot": True,
            "merge_method": "다음 범용 Active writer가 생기면 이 overlay의 사건 ID와 지문을 검증한 뒤 DOC-01·DOC-05 및 관련 Active register의 새 revision에 한 번 반영한다.",
            "duplicate_application_forbidden": True,
            "failure_behavior": "어느 binding이나 predecessor snapshot이 다르면 병합하지 않고 새 overlay revision을 만든다.",
        },
        "events": events,
        "draft_observations": [
            {
                "artifact_code": "DEV-18",
                "lifecycle_status": "DRAFT",
                "state_change": False,
                "observation": "새 관리자 Android·백엔드 모듈은 차기 module-register generator revision에 추가해야 한다. 이번 overlay는 DEV-18을 Active 또는 승인 상태로 올리지 않는다.",
            }
        ],
        "formal_boundary": {
            "formal_tests_total": FORMAL_TEST_COUNT,
            "formal_tests_passed": 0,
            "formal_tests_not_run": FORMAL_TEST_COUNT,
            "remaining_gate_ids": list(GATE_IDS),
            "remaining_gate_status": "NOT_RUN",
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
    }
    return _seal(overlay, "overlay_content_sha256")


def _validate_outputs(outputs: dict[str, dict[str, Any]]) -> None:
    phase_b = outputs["phase_b"]
    drill = outputs["drill"]
    report = outputs["gap"]
    backlog = outputs["backlog"]
    overlay = outputs["overlay"]
    for value, key in (
        (phase_b, "record_content_sha256"),
        (drill, "protocol_content_sha256"),
        (report, "report_content_sha256"),
        (backlog, "backlog_content_sha256"),
        (overlay, "overlay_content_sha256"),
    ):
        _verify_seal(value, key)

    _require(phase_b["trace"]["epic_status"] == "IN_PROGRESS", "EPIC-01 must remain IN_PROGRESS")
    _require(phase_b["release_boundary"]["formal_tests_passed"] == 0, "formal PASS was overstated")
    _require(drill["execution"]["status"] == "NOT_RUN" and drill["execution"]["result"] is None, "drill result was fabricated")
    _require(not drill["authority_boundary"]["gate_closed"] and not drill["authority_boundary"]["gate_waived"], "drill gate changed")

    assessments = report["assessments"]
    _require(len(assessments) == 68, "r002 must retain all 68 assessment rows")
    by_id = {item["gap_id"]: item for item in assessments}
    _require(len(by_id) == 68, "r002 gap IDs are duplicated")
    _require(by_id["GAP-012"]["status"] == "PARTIAL", "GAP-012 must be PARTIAL")
    _require(by_id["GAP-068"]["status"] == "BLOCKED", "GAP-068 must be BLOCKED")
    _require(all(item["formal_test_status"] == "NOT_RUN" for item in assessments), "a formal test was marked run")
    _require(report["coverage"]["planned_test_count"] == FORMAL_TEST_COUNT, "formal test count differs")
    _require(report["coverage"]["planned_test_not_run_count"] == FORMAL_TEST_COUNT, "formal NOT_RUN count differs")
    _require(report["summary"]["implemented_and_formally_verified_count"] == 0, "IMPLEMENTED count was overstated")
    evidence_ids = [item["evidence_id"] for item in report["evidence_catalog"]]
    _require(len(evidence_ids) == len(set(evidence_ids)), "r002 evidence IDs are duplicated")
    _require(
        all(set(item["evidence_ids"]) <= set(evidence_ids) for item in assessments),
        "an assessment evidence reference is unresolved",
    )
    for item in assessments:
        _verify_seal(item, "assessment_sha256")

    _require(backlog["gap_report_content_sha256"] == report["report_content_sha256"], "backlog is not bound to r002")
    epics = {item["epic_id"]: item for item in backlog["epics"]}
    _require(epics["EPIC-01"]["current_status"] == "IN_PROGRESS", "EPIC-01 backlog status differs")
    _require(all(item["current_status"] == "PLANNED" for key, item in epics.items() if key != "EPIC-01"), "future EPIC status was advanced")
    _require(backlog["next_single_action"]["work_item_id"] == "EPIC-01-RUNTIME-METRIC-PREFLIGHT", "next action differs")

    _require([item["artifact_code"] for item in overlay["events"]] == list(ACTIVE_ARTIFACT_CODES), "Active event set differs")
    _require(overlay["draft_observations"] == [{
        "artifact_code": "DEV-18",
        "lifecycle_status": "DRAFT",
        "state_change": False,
        "observation": "새 관리자 Android·백엔드 모듈은 차기 module-register generator revision에 추가해야 한다. 이번 overlay는 DEV-18을 Active 또는 승인 상태로 올리지 않는다.",
    }], "DEV-18 Draft boundary differs")
    _require(overlay["formal_boundary"]["formal_tests_not_run"] == FORMAL_TEST_COUNT, "overlay formal boundary differs")
    _require(overlay["formal_boundary"]["remaining_gate_ids"] == list(GATE_IDS), "overlay gate set differs")
    _require(not overlay["formal_boundary"]["remaining_gates_waived"], "overlay waived a gate")
    _require(overlay["formal_boundary"]["release_status"] == "NOT_ELIGIBLE", "overlay changed release status")


def build_outputs() -> dict[str, dict[str, Any]]:
    _assert_immutable_inputs()
    snapshot = _implementation_snapshot()
    phase_b = _build_phase_b(snapshot)
    drill = _build_drill(phase_b)
    report = _build_gap_r002(snapshot, phase_b, drill)
    backlog = _build_backlog_r002(report)
    overlay = _build_active_overlay(phase_b, drill, report, backlog)
    outputs = {
        "phase_b": phase_b,
        "drill": drill,
        "gap": report,
        "backlog": backlog,
        "overlay": overlay,
    }
    _validate_outputs(outputs)
    return outputs


def _json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


def _phase_b_markdown(value: dict[str, Any]) -> str:
    controls = "\n".join(
        f"- **{item['control']}** — {item['result']}" for item in value["implemented_controls"]
    )
    remaining = "\n".join(
        f"- `{item['id']}` — {item['status']}: {item['description']}" for item in value["remaining_work"]
    )
    commands = "\n".join(
        f"- `{item['status']}` {item['scope']}: `{item['command']}`"
        for item in value["internal_verification"]["commands"]
    )
    return f"""# EPIC-01 Phase B 관리자 인증·복구 내부 구현 기록

- 문서 ID: `{value['metadata']['record_id']}`
- 버전: `{value['metadata']['version']}`
- 상태: `{value['metadata']['status']}`
- EPIC: `EPIC-01 IN_PROGRESS`

## 이번에 내부 구현한 것

{controls}

## 내부 검증 경계

{commands}

위 결과는 내부 코드·구성요소 회귀다. 정식 시험, 실제 휴대전화 분실 복구훈련, 실기기 서명·배포 또는 출시 증거가 아니다.

## 아직 남은 것

{remaining}

정식 시험 **279/279는 NOT_RUN**, 5개 gate는 **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

## 다음 한 가지 작업

{value['next_single_action']}

내용 지문: `{value['record_content_sha256']}`
"""


def _drill_markdown(value: dict[str, Any]) -> str:
    steps = "\n".join(
        f"{item['order']}. **{item['action']}**\n   - 기대: {item['expected']}\n   - 증거: {item['evidence']}"
        for item in value["steps"]
    )
    criteria = "\n".join(f"- {item}" for item in value["pass_criteria"])
    return f"""# 단일 관리자 휴대전화 분실 복구훈련 절차

- 문서 ID: `{value['metadata']['protocol_id']}`
- 버전: `{value['metadata']['version']}`
- 상태: `{value['metadata']['status']}`
- 실행 결과: **NOT_RUN**

이 문서는 실행 절차 초안이다. 이 문서를 만들었다는 사실만으로 복구훈련 gate를 닫거나 면제하지 않는다.

## 실행 순서

{steps}

## 합격조건

{criteria}

현재 gate는 `GATE-SINGLE-ADMIN-RECOVERY-DRILL = NOT_RUN`, 미면제이며 출시는 `NOT_ELIGIBLE`이다.

내용 지문: `{value['protocol_content_sha256']}`
"""


def _gap_markdown(value: dict[str, Any]) -> str:
    counts = value["summary"]["status_counts"]
    gap012 = next(item for item in value["assessments"] if item["gap_id"] == "GAP-012")
    gap068 = next(item for item in value["assessments"] if item["gap_id"] == "GAP-068")
    return f"""# WalkSafe 구현 Gap 집중 재평가 r002

- 보고서: `{value['metadata']['report_id']}` v{value['metadata']['version']}
- 선행 보고서: `{value['metadata']['predecessor_report_id']}` — 변경하지 않고 보존
- 범위: Phase B가 직접 바꾼 `GAP-012`, 연결 gate `GAP-068`
- 출시 상태: **NOT_ELIGIBLE**

## 판정

| Gap | r002 판정 | 이유 |
|---|---|---|
| GAP-012 / FP-003 | `{gap012['status']}` | {gap012['rationale']} |
| GAP-068 / 복구훈련 gate | `{gap068['status']}` | {gap068['rationale']} |

전체 68개 상태 집계는 `{json.dumps(counts, ensure_ascii=False, sort_keys=True)}`다. `IMPLEMENTED`와 정식검증 완료 수는 0이다.

나머지 66개는 r001 판정을 그대로 운반했으며 이번에 재평가하지 않았다. 따라서 이 r002를 Phase A/B 전체 구현의 완전한 재진단으로 해석하면 안 된다.

정식 시험 279/279는 `NOT_RUN`, 5개 gate는 `NOT_RUN`·미면제다.

내용 지문: `{value['report_content_sha256']}`
"""


def _backlog_markdown(value: dict[str, Any]) -> str:
    epic = next(item for item in value["epics"] if item["epic_id"] == "EPIC-01")
    next_action = value["next_single_action"]
    return f"""# WalkSafe 구현 수정 백로그 r002

- 백로그: `{value['metadata']['backlog_id']}` v{value['metadata']['version']}
- EPIC-01: **{epic['current_status']}**
- 다른 EPIC: `PLANNED` 유지
- 정식 시험·gate·출시 상태: 변경 없음

## Phase B 반영

FP-003의 `GAP-012`는 내부 구현을 근거로 `PARTIAL`이다. 실제 운영 프로비저닝, 휴대전화 밖 암호화 복구자료, 실제 복구훈련은 남아 있다. EPIC-01 전체는 아직 `IMPLEMENTATION_READY`가 아니다.

## 다음 한 가지 작업

`{next_action['work_item_id']}` — {next_action['action']}

내용 지문: `{value['backlog_content_sha256']}`
"""


def _overlay_markdown(value: dict[str, Any]) -> str:
    events = "\n".join(
        f"- `{item['artifact_code']}` / `{item['event_id']}` — {item['summary']}"
        for item in value["events"]
    )
    return f"""# EPIC-01 Phase B Active 원장 successor overlay

- overlay: `{value['metadata']['overlay_id']}` v{value['metadata']['version']}
- 상태: `{value['metadata']['status']}`
- 승인·수명주기 상태 변경: 없음

이 파일은 승인된 opening snapshot과 기존 r001을 고치지 않고 Phase B 사건을 잇는 최소 overlay다. 다음 범용 Active writer에서 중복 없이 canonical 새 revision으로 병합해야 한다.

## Active 사건

{events}

`DEV-18`은 **DRAFT 유지**이며 새 모듈 inventory는 차기 생성기 revision에서 반영한다.

정식 시험 279/279 `NOT_RUN`, 5개 gate `NOT_RUN`·미면제, 출시 `NOT_ELIGIBLE`을 유지한다.

내용 지문: `{value['overlay_content_sha256']}`
"""


def render_outputs(outputs: dict[str, dict[str, Any]]) -> dict[Path, str]:
    return {
        PHASE_B_JSON: _json_text(outputs["phase_b"]),
        PHASE_B_MD: _phase_b_markdown(outputs["phase_b"]),
        DRILL_JSON: _json_text(outputs["drill"]),
        DRILL_MD: _drill_markdown(outputs["drill"]),
        GAP_R002_JSON: _json_text(outputs["gap"]),
        GAP_R002_MD: _gap_markdown(outputs["gap"]),
        BACKLOG_R002_JSON: _json_text(outputs["backlog"]),
        BACKLOG_R002_MD: _backlog_markdown(outputs["backlog"]),
        ACTIVE_OVERLAY_JSON: _json_text(outputs["overlay"]),
        ACTIVE_OVERLAY_MD: _overlay_markdown(outputs["overlay"]),
    }


def _write_or_check(path: Path, content: str, check: bool) -> None:
    if check:
        _require(path.is_file(), f"generated output is missing: {_relative(path)}")
        _require(path.read_text(encoding="utf-8") == content, f"generated output is stale: {_relative(path)}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify deterministic generated outputs")
    args = parser.parse_args(argv)
    try:
        outputs = build_outputs()
        for path, content in render_outputs(outputs).items():
            _write_or_check(path, content, args.check)
    except TraceBuildError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    mode = "checked" if args.check else "built"
    counts = outputs["gap"]["summary"]["status_counts"]
    print(
        f"{mode} EPIC-01 Phase B trace; GAP-012=PARTIAL; GAP-068=BLOCKED; "
        f"statuses={dict(sorted(counts.items()))}; formal={FORMAL_TEST_COUNT}/{FORMAL_TEST_COUNT} NOT_RUN; "
        "release=NOT_ELIGIBLE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
