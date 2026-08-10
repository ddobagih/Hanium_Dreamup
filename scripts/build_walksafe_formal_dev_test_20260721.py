#!/usr/bin/env python3
"""Build the controlled Draft DEV/TST deliverable bundle for WalkSafe.

The builder deliberately separates approved policy, current implementation
candidates, and not-yet-run verification evidence.  It never promotes a test
or release state merely because a source file or an older report exists.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from typing import Any, Iterable

try:
    from scripts import build_walksafe_design_deliverables_20260721 as design_builder
    from scripts import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder
    from scripts import build_walksafe_feature_policy_baseline_approval_20260721 as approval_builder
except ModuleNotFoundError:  # Direct execution from scripts/.
    import build_walksafe_design_deliverables_20260721 as design_builder
    import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder
    import build_walksafe_feature_policy_baseline_approval_20260721 as approval_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
POLICY_APPROVAL_VALIDATOR_PATH = Path(approval_builder.GENERATOR_PATH).resolve()
DECISION_ALIGNMENT_VALIDATOR_PATH = Path(alignment_builder.GENERATOR_PATH).resolve()
CONTROL_DIR = REPO_ROOT / "docs" / "control"
DELIVERABLES_DIR = REPO_ROOT / "docs" / "deliverables"
DEV_DIR = DELIVERABLES_DIR / "05-implementation"
TST_DIR = DELIVERABLES_DIR / "06-testing"
APPEND_ONLY_EXECUTION_ROOT = TST_DIR / "evidence" / "executions"
APPEND_ONLY_DEFECT_ROOT = TST_DIR / "registers" / "defect-instances"

POLICY_PATH = CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-comprehensive-draft.json"
APPROVAL_RECORD_PATH = (
    CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
)
BASELINE_MANIFEST_PATH = (
    CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
)
ARTIFACT_CATALOG_PATH = CONTROL_DIR / "artifact-types.json"
ALIGNED_DECISION_REGISTER_PATH = (
    CONTROL_DIR / "decision-interview" / "walksafe-effective-decision-register-aligned-20260721-r001.json"
)
PROJECT_ANSWERS_PATH = (
    CONTROL_DIR / "questionnaire" / "source-records" / "walksafe-project-decisions-20260717-answers.json"
)
FP035_CORRECTION_CANDIDATE_PATH = (
    CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
)

DEV_GUIDE_PATH = DEV_DIR / "developer-guide.md"
IMPLEMENTATION_CONFIGURATION_PATH = DEV_DIR / "implementation-configuration.md"
IMPLEMENTATION_QUALITY_PATH = DEV_DIR / "implementation-quality-record.md"
IMPLEMENTATION_MANIFEST_PATH = DEV_DIR / "implementation-manifest.json"
MODULE_REGISTER_PATH = DEV_DIR / "module-register.json"
QUALITY_REGISTER_PATH = DEV_DIR / "quality-evidence-register.json"
LICENSES_PATH = DEV_DIR / "licenses-and-notices.md"
SIR_PATH = DEV_DIR / "software-integration-report.md"

TEST_PLAN_PATH = TST_DIR / "test-plan.md"
TEST_EVIDENCE_PATH = TST_DIR / "test-evidence.md"
TEST_QUALITY_PATH = TST_DIR / "test-quality-report.md"
ENVIRONMENTS_PATH = TST_DIR / "registers" / "environments.json"
TEST_CASES_PATH = TST_DIR / "registers" / "test-cases.json"
EVIDENCE_README_PATH = TST_DIR / "evidence" / "README.md"
EVIDENCE_REGISTER_PATH = TST_DIR / "evidence" / "evidence-register.json"
DEFECTS_PATH = TST_DIR / "registers" / "defects.json"
METRICS_PATH = TST_DIR / "registers" / "metrics.json"
RESIDUAL_RISKS_PATH = TST_DIR / "registers" / "residual-risks.json"
STR_PATH = TST_DIR / "software-test-report.md"
READINESS_PATH = TST_DIR / "release-readiness-decision.md"
ACCEPTANCE_PATH = TST_DIR / "acceptance-receipt.md"
DEV_TEST_MANIFEST_PATH = DELIVERABLES_DIR / "manifests" / "dev-test-draft-20260721-r001.json"
TRACE_INTEGRATION_REPORT_REL = (
    "docs/deliverables/traceability/req-des-tst-integration-report-20260721-r001.json"
)
REQUIREMENT_TARGET_PATH = "docs/deliverables/03-requirements/system-requirements.md"

DESIGN_ID_ORDER = [f"DES-{number:02d}" for number in range(1, 28)]
DESIGN_COVERAGE_ID_SEQUENCE = [
    design_id
    for design_ids in design_builder.DOCUMENT_COVERAGE.values()
    for design_id in design_ids
]
DESIGN_TARGET_PATHS = {
    design_id: path.relative_to(REPO_ROOT).as_posix()
    for path, design_ids in design_builder.DOCUMENT_COVERAGE.items()
    for design_id in design_ids
}
DESIGN_MAPPING_GENERATOR_PATH = Path(design_builder.GENERATOR_PATH).resolve()

FP035_NETWORK_ISSUE_ID = "ISS-POLICY-FP035-NETWORK-001"
FP035_RESIDUAL_RISK_ID = "RSK-POLICY-FP035-NETWORK-001"
FP035_CORRECTION_CANDIDATE_ID = (
    "WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001"
)
FP035_REQUIRED_ACTIVATION_EVENT = "EXACT_NEW_BUNDLED_OWNER_APPROVAL_STATEMENT"
FP035_APPROVAL_BLOCKERS = [
    FP035_CORRECTION_CANDIDATE_ID,
    FP035_REQUIRED_ACTIVATION_EVENT,
]
FP035_BRANCH_READINESS = "BLOCKED_PENDING_BUNDLED_APPROVAL"
FP035_BRANCH_IMPLEMENTATION_STATUS = "FROZEN_PENDING_EXACT_BUNDLED_APPROVAL"
FP035_FORMAL_TEST_STATUS = "NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL"
FP035_NETWORK_BRANCH_REDESIGN = [
    "Wi-Fi 연결",
    "Wi-Fi 없음 + 이동통신망 전송 명시적 선택",
    "Wi-Fi 없음 + 이동통신망 전송 미선택",
]
FP035_NORMALIZATION_STATUS = "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL"
FP035_NORMALIZED_POLICY = {
    "walking": "일반 활동원본은 보행 중 휴대전화에 암호화해 저장하고 서버로 전송하지 않는다.",
    "stopped_mobile_opted_in": "사용자가 이동통신망 전송을 명시적으로 선택한 경우에만 정지 판정 뒤 이동통신망 전송을 허용한다.",
    "stopped_mobile_not_opted_in": "이동통신망 전송을 선택하지 않은 사용자는 정지 뒤에도 Wi-Fi에서만 전송한다.",
}
FP035_NORMATIVE_RULE = "일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 선택한 경우 보행이 정지한 뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다."

AS_OF = "2026-07-21"
VERSION = "0.1.0"
LIFECYCLE_STATUS = "DRAFT"
RELEASE_STATUS = "NOT_ELIGIBLE"
FORMAL_CATEGORIES = {"DOC", "MGT", "DSC", "REQ", "DES", "DEV", "TST"}

DEV_TYPE_COVERAGE = {
    "developer-guide.md": ["DEV-02", "DEV-03", "DEV-04", "DEV-05", "DEV-06", "DEV-08"],
    "implementation-configuration.md": [
        "DEV-01", "DEV-07", "DEV-09", "DEV-10", "DEV-11", "DEV-12", "DEV-13", "DEV-14"
    ],
    "implementation-quality-record.md": [
        "DEV-15", "DEV-16", "DEV-17", "DEV-18", "DEV-19", "DEV-20", "DEV-21"
    ],
}

TST_TYPE_COVERAGE = {
    "test-plan.md": ["TST-01", "TST-02", "TST-03", "TST-04", "TST-05"],
    "test-evidence.md": [
        "TST-06", "TST-07", "TST-08", "TST-09", "TST-10", "TST-11",
        "TST-12", "TST-13", "TST-14", "TST-15", "TST-16", "TST-17",
    ],
    "test-quality-report.md": ["TST-18", "TST-19", "TST-20", "TST-21", "TST-22", "TST-23"],
}

MODULES = [
    {
        "module_id": "MOD-ANDROID-USER",
        "name": "Android 사용자 앱",
        "paths": ["apps/android"],
        "policy_role": "사용자용 정식 제품 후보: 탐지·길안내·음성·진동·신고",
        "current_fact": "Android 소스와 단위·기기 시험 코드가 존재한다.",
        "alignment_status": "REVALIDATION_REQUIRED",
        "languages": ["Kotlin", "Gradle Kotlin DSL", "XML", "TFLite asset"],
        "responsibility": "사용자의 보행 세션, 단말 추론, 길안내, 음성·진동, 신고 후보를 처리한다.",
        "inputs": ["카메라·센서·위치", "사용자 명령", "서버·TMAP 응답", "승인된 모델·설정"],
        "outputs": ["위험 안내", "길안내", "암호화된 신고·원본 대기자료"],
        "owner_role": "Android 개발책임자",
        "policy_refs": ["FP-007", "FP-019", "FP-020", "FP-022", "FP-031", "FP-035"],
        "design_refs": [],
        "build_artifacts": ["Android 사용자 앱 APK/AAB — 아직 정식 build hash 없음"],
        "deployment_artifacts": ["사용자 앱 설치본 — 아직 미승인"],
        "deprecation_status": "ACTIVE_PRODUCT_CANDIDATE",
        "replacement_module_id": None,
    },
    {
        "module_id": "MOD-ANDROID-ADMIN",
        "name": "Android 관리자 앱",
        "paths": ["apps/android/admin"],
        "policy_role": "관리자용 정식 제품",
        "current_fact": "정책상 별도 정식 제품이나 현재 저장소에서 독립 앱 경계를 확인하지 못했다.",
        "alignment_status": "IMPLEMENTATION_GAP_REVIEW_REQUIRED",
        "languages": ["Kotlin — 계획"],
        "responsibility": "검수·승인·기관 전달·권한·감사 기록을 관리자에게 제공한다.",
        "inputs": ["신고·검수 대기자료", "관리자 인증·권한"],
        "outputs": ["검수 결정", "기관 전달 상태", "감사 기록"],
        "owner_role": "Android 관리자 앱 개발책임자",
        "policy_refs": ["FP-008", "FP-047", "FP-048", "FP-050", "FP-051"],
        "design_refs": [],
        "build_artifacts": ["별도 관리자 APK/AAB — 구현 경계 없음"],
        "deployment_artifacts": ["관리자 앱 설치본 — 없음"],
        "deprecation_status": "REQUIRED_BUT_NOT_IMPLEMENTED",
        "replacement_module_id": None,
    },
    {
        "module_id": "MOD-BACKEND",
        "name": "백엔드·PostGIS",
        "paths": ["backend", "contracts"],
        "policy_role": "계정·TMAP 중계·신고·원본과 원본 저장 메타데이터·관리 API",
        "current_fact": "FastAPI, migration, 계약 및 시험 후보가 존재한다.",
        "alignment_status": "REVALIDATION_REQUIRED",
        "languages": ["Python", "SQL", "OpenAPI JSON"],
        "responsibility": "계정, 지도 중계, 신고, 원본·메타데이터 저장, 삭제, 관리 API를 처리한다.",
        "inputs": ["인증 요청", "TMAP 요청", "신고·원본", "삭제·검수 요청"],
        "outputs": ["API 응답", "DB·원본 저장 상태", "감사 기록"],
        "owner_role": "백엔드 개발책임자",
        "policy_refs": ["FP-011", "FP-022", "FP-031", "FP-034", "FP-035", "FP-048"],
        "design_refs": [],
        "build_artifacts": ["서버 image/build — 아직 정식 hash 없음"],
        "deployment_artifacts": ["backend systemd/Docker 후보"],
        "deprecation_status": "ACTIVE_SERVICE_CANDIDATE",
        "replacement_module_id": None,
    },
    {
        "module_id": "MOD-MODEL",
        "name": "객체 탐지 모델·학습 도구",
        "paths": ["model", "apps/android/app/src/main/assets/models"],
        "policy_role": "휴대전화 추론과 서버 학습·평가 후보",
        "current_fact": "TFLite 자산과 Python 학습·검증 도구가 존재한다.",
        "alignment_status": "REVALIDATION_REQUIRED",
        "languages": ["Python", "TFLite", "PyTorch artifact"],
        "responsibility": "객체 탐지 모델의 학습·평가·변환 후보와 단말 배포 자산을 관리한다.",
        "inputs": ["승인 데이터셋", "학습 설정", "모델 후보"],
        "outputs": ["평가 결과", "TFLite 모델", "모델 hash"],
        "owner_role": "AI·ML 개발책임자",
        "policy_refs": ["FP-019", "FP-020", "FP-021", "FP-034"],
        "design_refs": [],
        "build_artifacts": ["TFLite·학습모델 후보 — release 결속 전"],
        "deployment_artifacts": ["Android model asset 후보"],
        "deprecation_status": "ACTIVE_MODEL_CANDIDATE",
        "replacement_module_id": None,
    },
    {
        "module_id": "MOD-VOICE",
        "name": "음성 처리 후보",
        "paths": ["voice", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation"],
        "policy_role": "호출어·STT·의도·TTS의 단말 우선 처리",
        "current_fact": "Android 및 Python 음성 관련 코드가 함께 존재한다.",
        "alignment_status": "BOUNDARY_REVIEW_REQUIRED",
        "languages": ["Kotlin", "Python"],
        "responsibility": "호출어·음성인식·의도·음성안내 후보를 처리하며 단말 우선 책임 경계를 검토한다.",
        "inputs": ["마이크 입력", "명령 문구", "안내 문장"],
        "outputs": ["의도", "음성 안내", "오류·성능 기록"],
        "owner_role": "음성 기능 개발책임자",
        "policy_refs": ["FP-025", "FP-026", "FP-027", "FP-028", "FP-029"],
        "design_refs": [],
        "build_artifacts": ["Android 음성 기능·Python 음성 서비스 후보"],
        "deployment_artifacts": ["voice systemd 후보"],
        "deprecation_status": "BOUNDARY_REVIEW_REQUIRED",
        "replacement_module_id": None,
    },
    {
        "module_id": "MOD-WEB-LEGACY",
        "name": "Web/PWA 과거 구현",
        "paths": ["apps/web"],
        "policy_role": "과거 참고자료이며 정식 사용자·관리자 제품이 아님",
        "current_fact": "Next.js/PWA 구현과 시험 후보가 존재한다.",
        "alignment_status": "LEGACY_REFERENCE_ONLY",
        "languages": ["TypeScript", "React", "Next.js"],
        "responsibility": "과거 동작을 비교할 때만 참고하며 Android 정식 제품의 완료·합격 근거로 쓰지 않는다.",
        "inputs": ["과거 Web/PWA 입력"],
        "outputs": ["과거 버전 비교 결과"],
        "owner_role": "기술책임자",
        "policy_refs": ["FP-009"],
        "design_refs": [],
        "build_artifacts": ["Web/PWA 과거 빌드 — 정식 제품 아님"],
        "deployment_artifacts": ["Web 환경·nginx·systemd 파일 — 과거 참고"],
        "deprecation_status": "LEGACY_REFERENCE_ONLY",
        "replacement_module_id": "MOD-ANDROID-USER",
    },
    {
        "module_id": "MOD-DEPLOY",
        "name": "배포 설정 후보",
        "paths": ["deploy", ".github/workflows", "docker-compose.yml"],
        "policy_role": "서버·품질 자동화의 구현 후보",
        "current_fact": "systemd, nginx, Docker Compose, GitHub Actions 후보가 존재한다.",
        "alignment_status": "REVALIDATION_REQUIRED",
        "languages": ["YAML", "systemd unit", "nginx configuration", "Docker Compose"],
        "responsibility": "서버 실행·이관·자동검사를 재현하는 배포·CI 후보를 관리한다.",
        "inputs": ["source·lock·환경 설정", "배포 대상"],
        "outputs": ["build·검사 결과", "서버 프로세스"],
        "owner_role": "배포·운영 개발책임자",
        "policy_refs": ["FP-002", "FP-044", "FP-053"],
        "design_refs": [],
        "build_artifacts": ["CI 결과·container 후보 — 정식 빌드 생성 이력(provenance) 없음"],
        "deployment_artifacts": ["systemd/nginx/Docker Compose 후보"],
        "deprecation_status": "REVALIDATION_REQUIRED",
        "replacement_module_id": None,
    },
    {
        "module_id": "MOD-ENGINEERING-TOOLING",
        "name": "빌드·검증·제출 도구",
        "paths": ["scripts", "tests", "configs"],
        "policy_role": "제품 코드와 분리해 관리하는 빌드·검증·제출 지원 도구",
        "current_fact": "스크립트·시험·고정 설정이 존재하나 제품·과거 참고·제출 도구의 세부 분류가 더 필요하다.",
        "alignment_status": "SCOPE_CLASSIFICATION_REQUIRED",
        "languages": ["Python", "Shell", "JSON", "YAML"],
        "responsibility": "빌드·검사·백업·제출·고정 시험자료 도구를 실행하고 재현 입력을 제공한다.",
        "inputs": ["코드·의존성 버전 고정 파일(lock)·고정 시험자료(fixture)·정책 기준"],
        "outputs": ["검사 결과", "build·제출 후보", "운영 자료"],
        "owner_role": "개발·QA 책임자",
        "policy_refs": [],
        "design_refs": [],
        "build_artifacts": ["도구 실행 결과 — 제품 artifact와 분리"],
        "deployment_artifacts": [],
        "deprecation_status": "SCOPE_CLASSIFICATION_REQUIRED",
        "replacement_module_id": None,
    },
]

MODULE_DESIGN_CANDIDATE_REFS = {
    "MOD-ANDROID-USER": ["DES-01", "DES-03", "DES-04", "DES-14", "DES-15", "DES-19", "DES-22"],
    "MOD-ANDROID-ADMIN": ["DES-01", "DES-03", "DES-04", "DES-14", "DES-15", "DES-19"],
    "MOD-BACKEND": ["DES-01", "DES-03", "DES-05", "DES-09", "DES-10", "DES-11", "DES-12", "DES-13", "DES-19", "DES-22"],
    "MOD-MODEL": ["DES-01", "DES-03", "DES-07", "DES-24"],
    "MOD-VOICE": ["DES-01", "DES-03", "DES-04", "DES-15", "DES-22"],
    "MOD-WEB-LEGACY": ["DES-01", "DES-02", "DES-03", "DES-05", "DES-06", "DES-07"],
    "MOD-DEPLOY": ["DES-01", "DES-05", "DES-23", "DES-25", "DES-27"],
    "MOD-ENGINEERING-TOOLING": ["DES-01", "DES-07"],
}


class FormalBundleError(ValueError):
    """Raised when controlled inputs or generated outputs are inconsistent."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise FormalBundleError(message)


def _reject_constant(value: str) -> None:
    raise FormalBundleError(f"non-standard JSON number is not allowed: {value}")


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        _require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    _require(not raw.startswith(b"\xef\xbb\xbf"), f"UTF-8 BOM is not allowed: {path}")
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_object_pairs,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise FormalBundleError(f"invalid JSON: {path}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_file(path: Path) -> str:
    return _sha256_bytes(path.read_bytes())


def _object_sha256(value: Any) -> str:
    return _sha256_bytes(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    )


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _md_bytes(value: str) -> bytes:
    return (value.rstrip() + "\n").encode("utf-8")


def _rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _source_binding(path: Path) -> dict[str, str]:
    return {"path": _rel(path), "sha256": _sha256_file(path)}


def _project_facts() -> dict[str, Any]:
    """Return already answered project facts without treating them as new policy."""

    source = load_strict_json(PROJECT_ANSWERS_PATH)
    answers = source.get("answers", {})
    notes = source.get("notes", {})
    expected = {
        "Q-GOV-003": "production",
        "Q-GOV-011": "milestone",
        "Q-DET-003": "unified13",
        "Q-REL-007": "managed_cloud",
        "Q-REL-008": "beta",
        "Q-REL-011": "staging_only",
        "Q-OPS-001": "나 ",
        "Q-OPS-002": "business_hours",
        "Q-OPS-003": "no_slo_beta",
    }
    for question_id, expected_value in expected.items():
        _require(
            answers.get(question_id) == expected_value,
            f"approved project answer differs: {question_id}",
        )
    milestone_note = notes.get("Q-GOV-011", "")
    _require(
        "정해진 예산은 없고" in milestone_note and "7월 26일" in milestone_note,
        "project budget/demo milestone note differs",
    )
    return {
        "target_stage": "PRODUCTION_AFTER_REQUIRED_GATES",
        "current_release_stage": "CONTROLLED_DEMO_THEN_BETA",
        "controlled_demo_date": "2026-07-26",
        "fixed_budget_krw": None,
        "platform": "ANDROID_USER_AND_SEPARATE_ADMIN_APPS",
        "object_class_candidate": "UNIFIED_13",
        "deployment_model": "MANAGED_CLOUD_STAGING_FIRST",
        "operations_owner": "PROJECT_OWNER_SINGLE_ADMIN",
        "support_window": "BUSINESS_HOURS",
        "beta_slo_policy": "NO_NUMERIC_SLO_UNTIL_MEASURED",
        "source_path": _rel(PROJECT_ANSWERS_PATH),
    }


def _fp035_correction_candidate() -> dict[str, Any]:
    candidate = load_strict_json(FP035_CORRECTION_CANDIDATE_PATH)
    content = {key: value for key, value in candidate.items() if key != "candidate_content_sha256"}
    _require(candidate.get("candidate_content_sha256") == _object_sha256(content), "FP-035 correction candidate content hash differs")
    metadata = candidate.get("metadata", {})
    _require(metadata.get("candidate_id") == FP035_CORRECTION_CANDIDATE_ID, "FP-035 candidate ID differs")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "FP-035 candidate was unexpectedly approved")
    _require(metadata.get("effective_status") == "NOT_EFFECTIVE", "FP-035 candidate was unexpectedly made effective")
    _require(candidate.get("correction", {}).get("normative_rule") == FP035_NORMATIVE_RULE, "FP-035 correction rule differs")
    _require(candidate.get("planned_effective_policy", {}).get("state_change_now") is False, "FP-035 candidate changed policy state")
    boundary = candidate.get("authorization_boundary", {})
    _require(boundary.get("owner_directive_captured") is True and boundary.get("new_product_question_required") is False, "FP-035 owner directive boundary differs")
    _require(boundary.get("candidate_generation_is_approval") is False, "FP-035 candidate generation was treated as approval")
    _require(boundary.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "FP-035 activation event differs")
    _require(boundary.get("mobile_network_branch_implementation_status") == FP035_BRANCH_IMPLEMENTATION_STATUS, "FP-035 implementation freeze differs")
    _require(boundary.get("mobile_network_branch_formal_test_status") == FP035_FORMAL_TEST_STATUS, "FP-035 formal-test freeze differs")
    return candidate


def _run_git(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    _require(completed.returncode == 0, f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def _metadata(artifact_ids: list[str], title: str) -> dict[str, Any]:
    return {
        "title": title,
        "version": VERSION,
        "as_of": AS_OF,
        "lifecycle_status": LIFECYCLE_STATUS,
        "freshness_status": "CURRENT_DRAFT",
        "verification_status": "NOT_RUN",
        "approval_status": "NOT_APPROVED",
        "release_status": RELEASE_STATUS,
        "artifact_type_ids": artifact_ids,
        "source_policy_baseline": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
        "remaining_gate_count": 5,
        "remaining_gates_waived": False,
        "generated_by": _rel(GENERATOR_PATH),
    }


def _control_header(title: str, artifact_ids: list[str], question: str) -> str:
    codes = ", ".join(artifact_ids)
    return f"""# {title}

> 포함 산출물: {codes}  
> 버전: {VERSION} · 상태: Draft · 승인: 미승인  
> 정책 기준선: WalkSafe 기능 정책 1.0.0  
> 검증: 아직 실행하지 않음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

{question}

## 쉬운 요약

이 문서는 확정된 기능 정책을 개발과 시험에 옮기는 첫 초안입니다. 저장소에 코드나 시험 파일이 있다는 사실만으로 기능이 완성됐거나 시험에 합격한 것은 아닙니다. 같은 코드·앱·모델·설정 묶음으로 다시 확인해야 합니다. 출시 전에 반드시 끝내야 할 검증 5개도 남아 있습니다.
"""


def _bullet(values: Iterable[str], empty: str = "해당 없음") -> str:
    items = list(values)
    if not items:
        return f"- {empty}"
    return "\n".join(f"- {item}" for item in items)


def _validate_inputs() -> tuple[
    dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any]
]:
    policy = load_strict_json(POLICY_PATH)
    approval = load_strict_json(APPROVAL_RECORD_PATH)
    manifest = load_strict_json(BASELINE_MANIFEST_PATH)
    catalog = load_strict_json(ARTIFACT_CATALOG_PATH)
    aligned = load_strict_json(ALIGNED_DECISION_REGISTER_PATH)
    project_facts = _project_facts()
    _fp035_correction_candidate()

    policy_body = {key: value for key, value in policy.items() if key != "document_content_sha256"}
    _require(
        policy.get("document_content_sha256") == _object_sha256(policy_body),
        "policy document content hash differs from its actual body",
    )
    _require(policy.get("document_content_sha256") == "e49ffe0ee9e59de0cec173cac9425d61aa78e0ccd65980ba568045a3975e0b28", "policy content digest differs")
    _require(len(policy.get("features", [])) == 54, "policy must contain 54 features")
    _require(len(policy.get("common_policies", [])) == 9, "policy must contain 9 common policies")
    _require(len(policy.get("remaining_gates", [])) == 5, "policy must contain five gates")
    _require(all(gate.get("status") == "NOT_RUN" for gate in policy["remaining_gates"]), "a policy gate is no longer NOT_RUN")

    metadata = manifest.get("metadata", {})
    _require(metadata.get("lifecycle_status") == "BASELINED", "policy baseline manifest is not BASELINED")
    payload = manifest.get("baseline_payload", {})
    target = payload.get("approval_target", {})
    _require(target.get("document_content_sha256") == policy["document_content_sha256"], "manifest policy digest differs")
    _require(target.get("decision_binding_sha256") == "16064cabf95dc16109bfe315032905a12881cab40cf7730e97d8171d15476538", "manifest decision digest differs")
    boundary = manifest.get("establishment_boundary", {})
    _require(boundary.get("formal_deliverables_authorized") is True, "formal 0-6 authoring is not authorized")
    _require(boundary.get("formal_deliverable_generation_status") == "NOT_RUN", "formal generation start boundary differs")
    _require(boundary.get("release_status") == RELEASE_STATUS, "release boundary differs")
    _require(boundary.get("remaining_gates_are_waived") is False, "gate waiver boundary differs")
    _require(boundary.get("implementation_completion_claimed") is False, "manifest claims implementation completion")
    _require(boundary.get("test_completion_claimed") is False, "manifest claims test completion")
    manifest_gates = manifest.get("remaining_gates", [])
    _require(
        [gate.get("id") for gate in manifest_gates] == [gate["id"] for gate in policy["remaining_gates"]]
        and all(gate.get("status") == "NOT_RUN" for gate in manifest_gates),
        "manifest gates differ from approved policy",
    )
    try:
        approval_builder.validate_approval_record(approval)
        approval_builder.validate_baseline_manifest(manifest, approval)
    except (
        approval_builder.BaselineApprovalError,
        approval_builder.review_builder.BaselineReviewError,
        OSError,
        KeyError,
        TypeError,
        ValueError,
    ) as exc:
        raise FormalBundleError(
            f"policy baseline approval validation failed: {exc}"
        ) from exc

    types = catalog.get("artifact_types", [])
    formal_types = [item for item in types if item.get("category") in FORMAL_CATEGORIES]
    _require(len(types) == 257, "artifact catalog must contain 257 types")
    _require(len(formal_types) == 128, "formal 0-6 catalog must contain 128 types")
    _require(len([item for item in formal_types if item.get("default_applicability") == "REQUIRED"]) == 114, "formal required count differs")
    _require(len([item for item in formal_types if item.get("default_applicability") == "CONDITIONAL"]) == 14, "formal conditional count differs")

    aligned_decisions = aligned.get("decisions", [])
    _require(len(aligned_decisions) == 135, "aligned decision register must contain 135 decisions")
    aligned_metadata = aligned.get("metadata", {})
    _require(aligned_metadata.get("lifecycle_status") == "IN_REVIEW", "aligned decision register lifecycle differs")
    aligned_boundary = aligned.get("approval_boundary", {})
    _require(aligned_boundary.get("source_policy_baseline_status") == "BASELINE_APPROVED", "aligned register policy baseline differs")
    _require(aligned_boundary.get("policy_alignment_status") == "COMPLETE", "decision policy alignment is incomplete")
    _require(aligned_boundary.get("artifact_approval_status") == "NOT_APPROVED", "aligned register approval boundary differs")
    _require(aligned_boundary.get("remaining_gates_are_waived") is False, "aligned register waives a gate")
    _require(aligned_boundary.get("release_status") == RELEASE_STATUS, "aligned decision release status differs")
    aligned_coverage = aligned.get("coverage", {})
    _require(aligned_coverage.get("actual_decision_count") == 135, "aligned decision coverage differs")
    _require(aligned_coverage.get("decision_feature_edge_count") == 428, "aligned decision edge coverage differs")
    _require(aligned_coverage.get("remaining_gate_count") == 5, "aligned gate coverage differs")
    _require(aligned_coverage.get("policy_conflict_count") == 0, "aligned policy conflicts remain")
    try:
        alignment_sources = alignment_builder._load_and_validate_sources()
        alignment_builder.validate_alignment(aligned, alignment_sources)
    except (
        alignment_builder.DecisionAlignmentError,
        alignment_builder.review_builder.BaselineReviewError,
        OSError,
        KeyError,
        TypeError,
        ValueError,
    ) as exc:
        raise FormalBundleError(
            f"aligned decision register validation failed: {exc}"
        ) from exc

    dev_codes = {item["display_code"] for item in formal_types if item["category"] == "DEV"}
    tst_codes = {item["display_code"] for item in formal_types if item["category"] == "TST"}
    _require(dev_codes == {code for codes in DEV_TYPE_COVERAGE.values() for code in codes}, "DEV coverage differs")
    _require(tst_codes == {code for codes in TST_TYPE_COVERAGE.values() for code in codes}, "TST coverage differs")
    return policy, manifest, catalog, aligned, project_facts


def _legacy_web_path(relative: str) -> bool:
    name = Path(relative).name.lower()
    return (
        relative.startswith("apps/web/")
        or relative in {
            "deploy/config/walksafe-web.env.example",
            "deploy/nginx/walksafe-web.conf.example",
            "deploy/systemd/walksafe-web.service",
        }
        or (
            relative.startswith("tests/")
            and any(token in name for token in ("web", "pwa"))
        )
    )


def _implementation_classification(relative: str) -> str:
    if _legacy_web_path(relative):
        return "LEGACY_REFERENCE_ONLY"
    if relative == ".github/workflows/quality.yml":
        return "MIXED_SCOPE_REVALIDATION_REQUIRED"
    if relative.startswith("tests/"):
        return "TEST_ASSET_CANDIDATE"
    if relative.startswith("scripts/"):
        return "ENGINEERING_TOOL_CANDIDATE"
    if relative.startswith("configs/submission_"):
        return "SUBMISSION_TOOL_CANDIDATE"
    if relative == "configs/walksafe_node_toolchain_lock_20260715.json":
        return "MIXED_SCOPE_REVALIDATION_REQUIRED"
    return "IMPLEMENTATION_CANDIDATE"


def _tracked_implementation_files() -> list[dict[str, Any]]:
    prefixes = (
        ".github/workflows/", "apps/android/", "apps/web/", "backend/", "configs/",
        "contracts/", "deploy/", "model/", "scripts/", "tests/", "voice/",
    )
    exact = {"docker-compose.yml"}
    paths = [line for line in _run_git("ls-files").splitlines() if line]
    selected: list[dict[str, Any]] = []
    for relative in paths:
        if relative not in exact and not relative.startswith(prefixes):
            continue
        path = REPO_ROOT / relative
        if not path.is_file():
            continue
        selected.append(
            {
                "path": relative,
                "sha256": _sha256_file(path),
                "byte_length": path.stat().st_size,
                "classification": _implementation_classification(relative),
            }
        )
    return selected


def _candidate_test_files() -> list[dict[str, Any]]:
    prefixes = ("tests/test_", "backend/tests/test_", "apps/android/app/src/test/", "apps/android/app/src/androidTest/", "apps/web/tests/")
    allowed_suffixes = {".py", ".kt", ".java", ".ts", ".tsx", ".js"}
    paths = [line for line in _run_git("ls-files").splitlines() if line]
    result = []
    for relative in paths:
        if not relative.startswith(prefixes):
            continue
        path = REPO_ROOT / relative
        if path.is_file() and path.suffix.lower() in allowed_suffixes:
            if _legacy_web_path(relative):
                scope = "LEGACY_WEB_REFERENCE"
            elif relative.startswith("apps/android/"):
                scope = "ANDROID_USER_PRODUCT"
            elif relative.startswith("backend/"):
                scope = "SERVER_PRODUCT"
            elif "submission" in path.name.lower():
                scope = "SUBMISSION_TOOLING"
            else:
                scope = "PRODUCT_OR_TOOLING_REVIEW_REQUIRED"
            result.append(
                {
                    "path": relative,
                    "sha256": _sha256_file(path),
                    "scope": scope,
                    "source_kind": "TEST_CODE",
                    "adoption_status": "CANDIDATE_NOT_REVALIDATED",
                    "result_status": "NOT_CLAIMED",
                    "formal_product_pass_eligible": False,
                }
            )
    return result


def _requirement_link(requirement_id: str) -> dict[str, str]:
    return {
        "requirement_id": requirement_id,
        "path": REQUIREMENT_TARGET_PATH,
        "anchor": requirement_id,
        "target": f"{REQUIREMENT_TARGET_PATH}#{requirement_id}",
        "trace_status": "DRAFT_NOT_APPROVED",
    }


def _design_link(design_id: str, *, trace_status: str = "DRAFT_NOT_APPROVED") -> dict[str, str]:
    _require(design_id in DESIGN_TARGET_PATHS, f"unknown declared design ID: {design_id}")
    path = DESIGN_TARGET_PATHS[design_id]
    anchor = design_id.lower()
    return {
        "design_id": design_id,
        "path": path,
        "anchor": anchor,
        "target": f"{path}#{anchor}",
        "trace_status": trace_status,
    }


def _module_register(files: list[dict[str, Any]]) -> dict[str, Any]:
    modules = []
    file_assignments: dict[str, list[str]] = {item["path"]: [] for item in files}
    for module in MODULES:
        matched = [
            item["path"]
            for item in files
            if any(
                item["path"] == scope or item["path"].startswith(f"{scope.rstrip('/')}/")
                for scope in module["paths"]
            )
        ]
        for path in matched:
            file_assignments[path].append(module["module_id"])
        requirement_refs = [f"RQ-{policy_id}-001" for policy_id in module["policy_refs"]]
        design_refs = MODULE_DESIGN_CANDIDATE_REFS[module["module_id"]]
        fp035_affected = module["module_id"] in {"MOD-ANDROID-USER", "MOD-BACKEND"}
        fp035_direct = module["module_id"] == "MOD-ANDROID-USER"
        fp035_related = module["module_id"] == "MOD-BACKEND"
        modules.append(
            {
                **module,
                "requirement_refs": requirement_refs,
                "requirement_links": [_requirement_link(item) for item in requirement_refs],
                "requirement_trace_status": "DRAFT_REQUIREMENT_LINKS_DECLARED",
                "design_refs": design_refs,
                "design_links": [_design_link(item) for item in design_refs],
                "design_candidate_refs": design_refs,
                "tracked_file_count": len(matched),
                "sample_paths": matched[:20],
                "design_trace_status": "DRAFT_DESIGN_LINKS_DECLARED",
                "link_validation_status": "SEE_EXTERNAL_INTEGRATION_REPORT",
                "integration_report_path": TRACE_INTEGRATION_REPORT_REL,
                "source_issue_ids": [FP035_NETWORK_ISSUE_ID] if fp035_affected else [],
                "change_tracking_refs": [FP035_NETWORK_ISSUE_ID] if fp035_affected else [],
                "policy_correction_candidate_refs": (
                    [FP035_CORRECTION_CANDIDATE_ID] if fp035_affected else []
                ),
                "direct_policy_correction_candidate_refs": (
                    [FP035_CORRECTION_CANDIDATE_ID] if fp035_direct else []
                ),
                "related_policy_correction_candidate_refs": (
                    [FP035_CORRECTION_CANDIDATE_ID] if fp035_related else []
                ),
                "fp035_dependency_relation": (
                    "DIRECT_MOBILE_NETWORK_BRANCH_IMPLEMENTATION_DEPENDENCY"
                    if fp035_direct
                    else "RELATED_DOWNSTREAM_UPLOAD_CONTRACT_DEPENDENCY"
                    if fp035_related
                    else None
                ),
                "correction_candidate_binding": (
                    _source_binding(FP035_CORRECTION_CANDIDATE_PATH)
                    if fp035_affected
                    else None
                ),
                "bundled_approval_dependency_refs": (
                    list(FP035_APPROVAL_BLOCKERS) if fp035_affected else []
                ),
                "required_activation_event": (
                    FP035_REQUIRED_ACTIVATION_EVENT if fp035_affected else None
                ),
                "implementation_blockers": (
                    list(FP035_APPROVAL_BLOCKERS) if fp035_affected else []
                ),
                "mobile_data_branch_status": (
                    FP035_BRANCH_READINESS if fp035_affected else "NOT_APPLICABLE"
                ),
                "mobile_network_branch_implementation_status": (
                    FP035_BRANCH_IMPLEMENTATION_STATUS if fp035_affected else None
                ),
                "mobile_network_branch_formal_test_status": (
                    FP035_FORMAL_TEST_STATUS if fp035_affected else None
                ),
                "formal_branch_implementation_and_test_frozen": fp035_affected,
                "normalized_mobile_data_policy": FP035_NORMALIZED_POLICY if fp035_affected else None,
                "inventory_status": (
                    "IMPLEMENTATION_MISSING"
                    if module["module_id"] == "MOD-ANDROID-ADMIN"
                    else "DRAFT_REVIEW_REQUIRED"
                ),
            }
        )
    unassigned = sorted(path for path, assignments in file_assignments.items() if not assignments)
    multiply_assigned = sorted(path for path, assignments in file_assignments.items() if len(assignments) > 1)
    return {
        "schema_version": "walksafe.dev-module-register.v1",
        "metadata": _metadata(["DEV-18"], "WalkSafe 프로그램·모듈 목록"),
        "platform_boundary": {
            "formal_products": ["Android 사용자 앱", "Android 관리자 앱"],
            "services": ["백엔드", "PostgreSQL/PostGIS", "대용량 원본 저장소", "TMAP"],
            "legacy_reference": ["Web/PWA"],
        },
        "known_source_policy_issues": [
            {
                "issue_id": FP035_NETWORK_ISSUE_ID,
                "status": f"OPEN_{FP035_NORMALIZATION_STATUS}",
                "owner_clarification_required": False,
                "affected_module_ids": ["MOD-ANDROID-USER", "MOD-BACKEND"],
                "change_tracking_refs": [FP035_NETWORK_ISSUE_ID],
                "correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
                "correction_candidate_binding": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
                "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
                "approval_blockers": list(FP035_APPROVAL_BLOCKERS),
                "mobile_network_branch_implementation_and_test_readiness": FP035_BRANCH_READINESS,
                "mobile_network_branch_implementation_status": FP035_BRANCH_IMPLEMENTATION_STATUS,
                "mobile_network_branch_formal_test_status": FP035_FORMAL_TEST_STATUS,
                "normalized_policy": FP035_NORMALIZED_POLICY,
                "approval_boundary": "사용자 정규화 지시는 포착했으나 새 산출물 묶음 승인 전이므로 관련 구현 확정과 정식 시험 실행은 시작하지 않는다.",
            }
        ],
        "modules": modules,
        "assignment_metrics": {
            "source_file_count": len(files),
            "unassigned_file_count": len(unassigned),
            "multiply_assigned_file_count": len(multiply_assigned),
            "unassigned_sample_paths": unassigned[:20],
            "multiply_assigned_sample_paths": multiply_assigned[:20],
        },
        "approval_boundary": {
            "module_inventory_complete_for_current_tracked_tree": False,
            "module_inventory_completion_reason": "모듈별 세부 소유·인터페이스·배포 단위와 중복 경계를 사람이 검토해야 한다.",
            "policy_alignment_complete": False,
            "implementation_completion_claimed": False,
            "release_status": RELEASE_STATUS,
        },
        "trace_integration_boundary": {
            "declared_links_include_downstream_hashes": False,
            "post_generation_report_path": TRACE_INTEGRATION_REPORT_REL,
            "structural_link_validation_is_not_approval": True,
        },
    }


def _implementation_manifest(files: list[dict[str, Any]]) -> dict[str, Any]:
    classification_counts: dict[str, int] = {}
    for item in files:
        classification_counts[item["classification"]] = classification_counts.get(item["classification"], 0) + 1
    config_roles = {
        "dependency_locks": [item for item in files if "lock" in Path(item["path"]).name],
        "build_scripts": [
            item for item in files
            if Path(item["path"]).name in {"build.gradle.kts", "settings.gradle.kts", "package.json"}
            or (item["path"].startswith("scripts/") and Path(item["path"]).name.startswith(("build_", "generate_")))
        ],
        "ci_pipeline": [item for item in files if item["path"].startswith(".github/workflows/")],
        "database_migrations": [item for item in files if item["path"].startswith("backend/alembic/versions/")],
        "fixtures": [item for item in files if "/fixtures/" in item["path"]],
        "deployment_candidates": [item for item in files if item["path"].startswith("deploy/") or item["path"] == "docker-compose.yml"],
        "engineering_tools": [item for item in files if item["path"].startswith(("scripts/", "tests/", "configs/"))],
        "legacy_references": [item for item in files if item["classification"] == "LEGACY_REFERENCE_ONLY"],
    }
    return {
        "schema_version": "walksafe.implementation-manifest.v1",
        "metadata": _metadata(
            ["DEV-01", "DEV-07", "DEV-09", "DEV-10", "DEV-11", "DEV-12", "DEV-13", "DEV-14"],
            "WalkSafe 구현 형상 초안",
        ),
        "source_snapshot": {
            "git_commit": _run_git("rev-parse", "HEAD"),
            "worktree_binding": "NOT_CLAIMED",
            "reason": "정식 산출물 작성 중인 작업트리이므로 이 manifest는 tracked 후보 파일의 개별 hash만 결속한다.",
            "file_count": len(files),
            "classification_counts": dict(sorted(classification_counts.items())),
            "included_roots": [
                ".github/workflows", "apps/android", "apps/web", "backend", "configs", "contracts",
                "deploy", "model", "scripts", "tests", "voice",
            ],
            "files": files,
        },
        "controlled_groups": config_roles,
        "applicability": {
            "DEV-10": {"status": "ACTIVE_DRAFT", "scope": "Android·backend·계약·문서 구조검사", "basis": "자동검사는 사용하되 Web/PWA 성공을 Android 합격으로 세지 않도록 정식 범위를 재구성한다."},
            "DEV-11": {"status": "ACTIVE_DRAFT_FOR_SERVER_INFRASTRUCTURE", "scope": "managed cloud staging·backend·PostGIS·원본 저장소·backup·관측", "basis": "서버 인프라는 재현·복구할 수 있게 통제하며 Android app-store 배포는 IaC와 별도로 관리한다."},
            "DEV-13": {"status": "ACTIVE_DRAFT_FOR_TEST_AND_DEMO", "scope": "비식별 계약 고정 시험자료·예시 자료", "basis": "실제 영상·음성·정확 위치는 샘플로 Git에 넣지 않고 통제 저장소로 분리한다."},
        },
        "policy_alignment": {
            "status": "REVALIDATION_REQUIRED",
            "formal_products": ["Android 사용자 앱", "Android 관리자 앱"],
            "legacy_paths": [
                "apps/web",
                "deploy/config/walksafe-web.env.example",
                "deploy/nginx/walksafe-web.conf.example",
                "deploy/systemd/walksafe-web.service",
            ],
            "missing_or_unverified": [
                "독립 Android 관리자 앱 경계",
                "승인된 원본수집·보존·삭제 전체 흐름",
                "아직 실행하지 않은 필수 검증 5개의 증거",
                "정확한 release build·model·config·환경 결속",
            ],
        },
        "approval_boundary": {
            "source_code_exists": True,
            "implementation_completion_claimed": False,
            "integration_completion_claimed": False,
            "release_status": RELEASE_STATUS,
        },
    }


def _quality_register(candidate_tests: list[dict[str, Any]]) -> dict[str, Any]:
    scope_counts: dict[str, int] = {}
    for item in candidate_tests:
        scope_counts[item["scope"]] = scope_counts.get(item["scope"], 0) + 1
    return {
        "schema_version": "walksafe.dev-quality-evidence-register.v1",
        "metadata": _metadata(["DEV-15", "DEV-16", "DEV-19", "DEV-20"], "WalkSafe 개발 품질·공급망 증거 원장"),
        "code_review": {
            "applicability_status": "ACTIVE_DRAFT_SINGLE_DEVELOPER_CONTROL",
            "records": [],
            "single_developer_rule": "작성·재검토 시점을 나누고 diff·checklist·자동검사·rollback 계획을 기록한다. 없는 독립 검토자를 기록하지 않는다.",
            "note": "이 파일은 빈 Draft snapshot이다. 정식 기록은 별도 append-only instance로 만들며 과거 기록을 자동 승인 증거로 쓰지 않는다.",
        },
        "lint_static_analysis": {"status": "NOT_RUN_FOR_FORMAL_BASELINE", "evidence": []},
        "sbom": {"status": "NOT_GENERATED_FOR_FORMAL_BUILD", "evidence": []},
        "provenance": {"status": "NOT_GENERATED_FOR_FORMAL_BUILD", "evidence": []},
        "candidate_test_sources": candidate_tests,
        "candidate_test_source_summary": {
            "code_file_count": len(candidate_tests),
            "scope_counts": dict(sorted(scope_counts.items())),
            "documentation_files_excluded": True,
            "legacy_sources_count_as_android_release_evidence": False,
        },
        "instance_storage": {
            "mode": "APPEND_ONLY_EXTERNAL_INSTANCES",
            "path_pattern": "docs/deliverables/05-implementation/evidence/<evidence-id>.json",
            "generated_snapshot_is_edit_target": False,
        },
        "approval_boundary": {
            "candidate_sources_are_pass_evidence": False,
            "formal_quality_evidence_count": 0,
            "release_status": RELEASE_STATUS,
        },
    }


def _developer_guide(policy: dict[str, Any], project_facts: dict[str, Any]) -> str:
    feature_names = {feature["id"]: feature["name"] for feature in policy["features"]}
    return _control_header(
        "WalkSafe 개발자 안내서",
        ["DEV-02", "DEV-03", "DEV-04", "DEV-05", "DEV-06", "DEV-08"],
        "정식 제품 경계를 지키면서 개발환경을 만들고, 실행·시험·변경 검토를 어떻게 해야 하는가?",
    ) + f"""

## 확정된 제품 경계

- 정식 사용자 제품: Android 사용자 앱
- 정식 운영 제품: 별도 Android 관리자 앱
- 서버: 계정, TMAP 중계, 신고, 수집 원본과 그 저장정보, 운영 API
- 단말 우선 처리: 카메라 탐지, 위험 판단, 호출어·STT·TTS·진동
- 과거 참고자료: `apps/web`의 Web/PWA. 현재 Android 제품의 완료나 시험 합격 근거로 계산하지 않는다.
- 기준 정책: FP-007 {feature_names['FP-007']}, FP-008 {feature_names['FP-008']}, FP-009 {feature_names['FP-009']}

## 현재 프로젝트 단계

- 사용자가 답한 현재 마일스톤은 **{project_facts['controlled_demo_date']} 통제 시연**입니다.
- 이 날짜는 시연 대상이지 정식 출시일이 아닙니다. 5개 gate와 실기기·현장·접근성·보안 검증이 남아 있습니다.
- 정해진 예산은 없으며, 비용이 드는 cloud·기기·외부검토는 실제 견적과 승인이 생기기 전까지 금액을 꾸며 쓰지 않습니다.

<a id="dev-02"></a>
## DEV-02 프로젝트 README와 저장소 안내

| 위치 | 의미 | 현재 취급 |
|---|---|---|
| `apps/android` | Android 사용자 앱과 현재 Android 코드 후보 | 정책 재검증 필요 |
| `backend` | FastAPI·PostGIS·TMAP·신고 서버 후보 | 정책 재검증 필요 |
| `contracts/walksafe.openapi.json` | API 계약 후보 | DES-09·10 승인 전 Draft 입력 |
| `model` | 학습·평가·모델 도구 후보 | AIML 기준선과 별도 결속 필요 |
| `voice` | Python 음성 서비스 후보 | 단말 우선 정책과 책임 경계 재검토 필요 |
| `apps/web` | 과거 Web/PWA 구현 | 과거 비교용으로만 사용 |
| `deploy`, `.github/workflows` | 배포·CI 후보 | 정식 Android/서버 배포 기준으로 재검토 필요 |

<a id="dev-03"></a>
## DEV-03 개발환경 만들기

실제 비밀값을 문서나 Git에 넣지 않습니다. `.env.example`만 복사해 로컬 값을 별도 보관하고, 운영 키와 시험 키를 분리합니다.

### Android

1. JDK와 Android SDK 버전을 `apps/android`의 Gradle 설정에 맞춘다.
2. `apps/android/gradlew`를 사용하고 임의의 전역 Gradle 버전에 의존하지 않는다.
3. 모델 설정과 TFLite 자산은 `apps/android/app/src/main/assets`의 경로와 파일 지문(hash, 파일이 바뀌었는지 확인하는 값)을 함께 확인한다.

```bash
cd apps/android
./gradlew test --no-daemon
./gradlew lint --no-daemon
./gradlew assembleDebug --no-daemon
```

종료할 때는 실행 중인 Gradle·ADB 명령을 종료하고, `apps/android/app/build/reports` 결과와 APK 파일 지문을 실행 기록에 연결합니다. build 디렉터리를 지우는 것은 증거 연결을 마친 뒤에만 합니다.

### 백엔드

1. 전용 Python 환경을 만든다.
2. `backend/requirements.lock` 의존성 버전 고정 파일(lock 파일)과 그 파일 지문(hash)을 확인해 같은 버전을 설치한다.
3. 이름에 `test`가 포함된 격리 PostGIS만 시험에 사용한다.

```bash
docker compose up -d db
python -m alembic -c backend/alembic.ini upgrade head
PYTHONPATH=. python -m uvicorn backend.app.main:app --reload --port 8000
```

서버는 `Ctrl+C`로 종료하고 시험 DB는 `docker compose stop db`로 정지합니다. 시험 DB volume 삭제는 복원·재현에 필요한 증거를 확인한 뒤 별도 승인된 정리 작업으로 합니다. 실행 log에는 secret·정확 위치·원본 영상·음성을 남기지 않습니다.

### 모델·음성·Web 참고 구현

- 모델과 음성은 각 디렉터리의 의존성 버전 고정 파일(lock 파일)과 README를 따른다.
- Web/PWA 명령은 과거 동작 비교에만 사용하고 Android 정식 제품의 인수 근거로 바꾸지 않는다.
- 실행 명령이 성공해도 정책 적합성과 실기기 안전성이 자동으로 증명되는 것은 아니다.

<a id="dev-08"></a>
## DEV-08 설정과 환경변수 규칙

- 예제 위치: `backend/.env.example`, `apps/web/.env.example`, `deploy/config/*.env.example`
- 실제 비밀값(secret), 인증 토큰, 인증서, 정확 위치, 원본 영상·음성은 문서나 고정 시험자료(fixture)에 넣지 않는다.
- 환경변수 이름·필수 여부·기본값·민감도·적용 모듈을 DES-12 데이터 사전과 구현 파일 목록·지문 기록(manifest)에 연결한다.
- release Android 앱에는 운영 secret을 내장하지 않고 서버 중계를 사용한다.
- 모델, threshold, class order, API schema는 버전과 SHA-256을 함께 고정한다.

<a id="dev-04"></a>
## DEV-04 실행·시험의 최소 원칙

1. 정확한 코드 버전(source commit), 빌드 ID, 모델, 설정, DB 구조 변경(migration), 기기·OS, 환경, 수행시각을 기록한다.
2. 자동 시험은 실패·skip·mock·부분 성공을 PASS로 바꾸지 않는다.
3. 실기기·현장 시험은 참여자 동의와 안전계획이 승인된 뒤 실행한다.
4. 원본 수집 관련 시험은 승인된 동의·보존·삭제 조건을 그대로 사용한다.
5. 결과는 `06-testing/evidence/executions/<run-id>.json`에 새 파일로 추가하고 과거 결과를 덮어쓰지 않는다.

<a id="dev-05"></a>
## DEV-05 기여·코드리뷰 규칙

- branch는 `feat/<issue-id>`, `fix/<issue-id>`, `docs/<issue-id>` 형식을 기본으로 하고 하나의 변경 목적만 담습니다.
- commit은 한 가지 논리 변경으로 나누고, 메시지와 리뷰 기록에 정책 ID·요구 ID·시험 ID를 연결합니다.
- PR이 있으면 diff·자동검사·미해결 위험을 확인한 뒤 merge합니다. 1인 개발에서는 없는 두 번째 사람을 기록하지 않고, 변경 작성과 승인 사이에 다시 읽는 시점을 나눠 checklist·자동검사·장애 복귀 계획을 남깁니다.
- 권한·위치·영상·음성·인증·삭제·보행 안전 변경은 보안·개인정보·안전 영향분석을 포함합니다. 외부·독립 검토가 명시된 gate는 실제 검토자 없이 닫지 않습니다.
- merge 전에 추가된 파일·Git 이력의 secret 후보와 개인정보 원본을 확인합니다. 유출이 의심되면 먼저 키를 폐기·회전하고 영향을 기록합니다.
- 돌릴 때는 승인 commit을 덮어쓰지 않고 revert commit과 재검증 결과를 남깁니다. 개인정보 삭제 상태·DB 구조·새 자료를 잃는 돌리기는 하지 않습니다.
- Android 정식 제품과 Web/PWA 레거시 경계를 흐리는 변경은 merge하지 않습니다.

<a id="dev-06"></a>
## DEV-06 코딩 규칙

- Kotlin은 프로젝트 Gradle 설정과 Android Kotlin 스타일을 따르고, Python은 4칸 들여쓰기·명시적 type·짧은 함수를 기본으로 합니다. 도구 이름·버전·규칙을 lock하기 전에는 특정 formatter가 통과했다고 쓰지 않습니다.
- coroutine·thread·callback은 보행 세션 생명주기에 묶고, 일시중지·종료·권한 철회에서 취소합니다. Camera·Location·microphone·file·DB handle은 소유자와 닫는 지점을 코드에 보입니다.
- 실패 시 안전한 상태로 닫고, 불완전한 API 응답·센서값·모델 출력은 사용하지 않습니다.
- 수치와 상태 전이는 이름 있는 상수·정책 ID로 추적하고 시간·거리·좌표 단위와 null 의미를 API·DB 계약에 명시합니다.
- 로그에는 secret과 원본 개인정보를 남기지 않고 correlation ID·상태·오류 코드만 남깁니다.
- 네트워크 재시도는 같은 요청 ID로 중복을 막고 무한 반복하지 않으며, 취소·timeout·재시도 한계를 시험합니다.
- 새 동작에는 정상·거부·철회·오프라인·중복·복구 경계 시험을 함께 추가합니다.
- 현재 정식 최소 자동검사는 Android `./gradlew lint test`, Python 문법·단위·계약 시험입니다. 실제 lint·정적분석 산출물은 DEV-16에서 실행 build와 도구 버전에 결속하기 전까지 `NOT_RUN`입니다.

## 현재 차단 항목

{_bullet(gate['title'] + ' — ' + gate['closure'] for gate in policy['remaining_gates'])}

이 항목들은 개발 시작 전체를 막지는 않지만, 관련 문서 승인과 사용자시험·출시판정 전에 반드시 끝내야 합니다.

## 이번 버전 변경점

- 정책 기준선 1.0.0 승인 뒤 DEV-02~06·08의 정식 초안을 처음 개설했다.
- Android 사용자·관리자 앱을 정식 제품, Web/PWA를 과거 참고용으로 명시했다.
- 과거 코드와 시험을 PASS가 아닌 재검증 후보로 분리했다.
"""


def _implementation_configuration_document(files: list[dict[str, Any]]) -> str:
    grouped = _implementation_manifest(files)["controlled_groups"]
    rows = "\n".join(
        f"| {name} | {len(items)} | "
        + (", ".join(f"`{item['path']}`" for item in items[:4]) or "없음")
        + " |"
        for name, items in grouped.items()
    )
    return _control_header(
        "WalkSafe 구현 형상",
        ["DEV-01", "DEV-07", "DEV-09", "DEV-10", "DEV-11", "DEV-12", "DEV-13", "DEV-14"],
        "현재 코드·의존성·빌드·배포·DB·고정 시험자료(fixture) 가운데 무엇이 정식 구현 후보이고 어떤 상태인가?",
    ) + f"""

<a id="dev-01"></a>
## DEV-01 소스코드

Android 사용자 앱, 아직 경계가 없는 Android 관리자 앱, 백엔드, 모델, 음성, 배포·검증 도구 후보를 파일 목록과 지문 기록인 [implementation-manifest.json](implementation-manifest.json)에 등록합니다. SHA-256은 파일이 바뀌었는지 확인하는 파일 지문입니다. 파일이 존재한다는 사실만 확인했으며 정책 구현 완료는 주장하지 않습니다. Web/PWA와 관련 배포 파일은 과거 참고용으로 따로 표시합니다.

<a id="dev-07"></a>
## DEV-07 의존성 버전 고정 파일(lock 파일)

각 개발환경의 lock 파일(설치할 의존성 버전을 고정한 파일)을 그대로 두고 경로와 파일 지문(hash)을 파일 목록·지문 기록(manifest)에 연결합니다. lock 파일이 없는 의존성, 모델·데이터 라이선스, 서로 다른 Python 환경 간 충돌은 별도 검토가 필요합니다.

<a id="dev-09"></a>
## DEV-09 빌드 스크립트

Gradle, Python, Docker와 기존 빌드 스크립트를 후보로 등록합니다. 정식 빌드에서는 사용한 코드 버전, 모델, 설정, DB 변경 순서를 함께 고정하고, 무엇으로 어떻게 만들었는지 DEV-20 기록에 남겨야 합니다.

<a id="dev-10"></a>
## DEV-10 CI/CD 파이프라인

적용은 `ACTIVE_DRAFT`로 정합니다. `.github/workflows/quality.yml`의 과거 Web 중심 단계를 정식 범위에서 분리하고 Android 사용자 앱, 별도 관리자 앱, backend, API 계약, 비밀값 노출, 문서 구조검사를 파이프라인 대상으로 합니다. 관리자 앱 경계가 없거나 5개 gate가 `NOT_RUN`이면 build가 성공해도 출시는 계속 `NOT_ELIGIBLE`입니다.

<a id="dev-11"></a>
## DEV-11 IaC

관리형 cloud의 staging·backend·PostGIS·원본 저장소·backup·관측 범위에서 `ACTIVE_DRAFT`로 정합니다. Android 앱스토어 배포는 서버 IaC와 별도로 관리합니다. 현재 Docker Compose, systemd, nginx는 재검증 후보이며 계정·원본 저장소·비밀관리·backup·관측을 같은 환경 ID로 재현하기 전에는 IaC 완료를 주장하지 않습니다.

<a id="dev-12"></a>
## DEV-12 DB 구조 변경(migration)

`backend/alembic/versions`를 정본 후보로 등록합니다. DB 변경 적용 순서, 이전 버전으로 되돌릴 수 있는 범위(rollback), 운영 데이터 보존·삭제 정책, 마지막 DB 변경 버전을 앱·서버 빌드와 함께 시험해야 합니다.

<a id="dev-13"></a>
## DEV-13 초기·샘플 데이터

계약시험·시연·개발환경 초기화에 필요하므로 `ACTIVE_DRAFT`로 정합니다. 고정 시험자료(fixture)와 예시 자료(sample)는 실제 개인정보를 포함하지 않아야 하며 출처·사용권리·자료 구조 버전·초기화 명령·기대 결과를 기록합니다. 실제 원본 영상·음성·정확 위치는 Git 예시 자료로 두지 않습니다.

<a id="dev-14"></a>
## DEV-14 고정 시험자료(fixture)

API 약속과 고정 시험자료(fixture)는 운영 데이터와 분리하고, 기대하는 자료 구조·시험 목적·변경 조건을 기록합니다. 가짜 입력으로 만든 시험자료의 성공은 실제 휴대전화·현장시험 합격이 아닙니다.

## 후보 파일 집계

| 그룹 | 파일 수 | 예시 |
|---|---:|---|
{rows}

## 승인 전 완료조건

- Android 사용자 앱과 별도 관리자 앱의 module·build 경계 확정
- 승인 요구·설계와 파일·설정·DB 구조 변경(migration)의 추적표(RTM) 연결
- 앱·서버 버전별 코드·모델·설정·소프트웨어 구성품 목록(SBOM)·빌드 생성 이력(provenance) 연결
- 남은 필수 검증 5개와 보안·현장·릴리스 선행조건 우회 0건

현재 상태는 Draft이며 구현 완료·통합 완료·출시 가능을 주장하지 않습니다.
"""


def _implementation_quality_document(candidate_tests: list[dict[str, Any]], policy: dict[str, Any]) -> str:
    scope_counts: dict[str, int] = {}
    for item in candidate_tests:
        scope_counts[item["scope"]] = scope_counts.get(item["scope"], 0) + 1
    scope_summary = ", ".join(f"{scope} {count}개" for scope, count in sorted(scope_counts.items()))
    return _control_header(
        "WalkSafe 구현 품질·공급망·통합 기록",
        ["DEV-15", "DEV-16", "DEV-17", "DEV-18", "DEV-19", "DEV-20", "DEV-21"],
        "현재 구현 후보를 누가 검토했고, 정적분석·모듈·라이선스·소프트웨어 구성품 목록(SBOM)·빌드 생성 이력(provenance)·통합 결과가 준비됐는가?",
    ) + f"""

<a id="dev-15"></a>
## DEV-15 코드리뷰 기록

적용은 `ACTIVE_DRAFT_SINGLE_DEVELOPER_CONTROL`로 정합니다. 정식 변경 검토가 생기면 검토 ID, 코드 버전·diff, 정책·요구·시험 추적, 자동검사, secret 확인, rollback, 지적·조치·재검토 결과를 `05-implementation/evidence/<evidence-id>.json`에 덮어쓰지 않는 새 파일로 추가합니다. 1인 개발일 때는 없는 다른 사람을 적지 않고 작성·재검토 시점을 나누어 기록합니다. 독립 전문검토가 명시된 5개 gate는 이 자체검토로 대체하지 않습니다. [quality-evidence-register.json](quality-evidence-register.json)은 빈 구조와 현재 집계만 담으며 현재 정식 검토 기록은 0건입니다.

<a id="dev-16"></a>
## DEV-16 lint·정적분석 결과

정식 기준선 빌드에 대한 결과는 아직 실행하지 않았습니다(`NOT_RUN`). 문서 파일을 뺀 기존 시험 코드 후보는 {len(candidate_tests)}개이며 범위는 {scope_summary}입니다. 과거 Web과 제출·검증 도구를 Android 제품 시험으로 계산하지 않으며, 어떤 후보도 실행 결과나 합격 증거가 아닙니다.

<a id="dev-17"></a>
## DEV-17 라이선스·고지

[licenses-and-notices.md](licenses-and-notices.md)에 검토 범위와 완료조건을 적었습니다. 한 번에 함께 출시할 최종 버전 묶음(release generation)의 의존성·모델·데이터·SDK 권리 검토는 `PENDING_REVIEW`입니다.

<a id="dev-18"></a>
## DEV-18 프로그램·모듈 목록

[module-register.json](module-register.json)에 Android 사용자·관리자, 서버, 모델, 음성, 과거 Web, 배포, 개발도구 경계를 나눴습니다. FP-035는 일반 활동원본을 보행 중 전송하지 않고, 정지 뒤에는 이동통신망을 명시 선택한 사용자만 이동통신망을 허용하며 미선택 사용자는 Wi-Fi만 허용하는 것으로 정규화 지시를 포착했습니다. 근거 정정 후보는 `NOT_APPROVED/NOT_EFFECTIVE`이며 새 산출물 묶음 승인 전에는 Android 사용자 앱과 백엔드의 관련 구현 확정·정식 시험을 대기시킵니다. 별도 관리자 앱이 없고 중복·미배정 경계가 남아 있어 모듈 목록은 완료로 표시하지 않습니다.

<a id="dev-19"></a>
## DEV-19 소프트웨어 구성품 목록(SBOM)

정식 Android·서버 빌드에 대한 소프트웨어 구성품 목록(SBOM)은 아직 생성하지 않았습니다. 생성할 때 구성품 버전, 사용권리, 공급자, 파일 지문(hash), 의존 관계와 그 시점의 취약점 상태를 출시 ID에 연결합니다.

<a id="dev-20"></a>
## DEV-20 빌드 생성 이력(provenance)·산출물 파일 지문(hash)

정식 빌드가 어떤 코드와 도구로 만들어졌는지 보여주는 생성 이력(provenance)은 아직 없습니다. 코드 버전, 빌드 도구, 의존성 버전 고정 파일(lock 파일), 모델, 설정, DB 구조 변경(migration), 결과 파일 지문(hash)과 서명 상태를 기록해야 합니다.

<a id="dev-21"></a>
## DEV-21 SIR

[software-integration-report.md](software-integration-report.md)는 현재 통합 실행 0건과 필요한 결속을 명시합니다. 코드가 같은 저장소에 있다는 이유로 통합됐다고 판정하지 않습니다.

## 남은 필수 검증

{_bullet(gate['id'] + ' — ' + gate['title'] for gate in policy['remaining_gates'])}

## 현재 종합판정

- 품질 증거: NOT_RUN / NOT_GENERATED
- 정식 통합: NOT_RUN
- 구현 완료: 주장하지 않음
- 출시: NOT_ELIGIBLE
"""


def _licenses_document(files: list[dict[str, Any]]) -> str:
    lock_paths = [
        f"`{item['path']}` — 범위: `{item['classification']}`"
        for item in files if "lock" in Path(item["path"]).name
    ]
    return _control_header(
        "WalkSafe 라이선스·고지 파일",
        ["DEV-17"],
        "현재 제품 후보에 포함된 코드·모델·의존성의 권리와 사용자 고지를 어떻게 확인할 것인가?",
    ) + f"""

## 현재 판정

라이선스 원문과 의존성 버전 고정 파일(lock 파일) 후보는 존재하지만, 정식 Android 사용자 앱·Android 관리자 앱·서버·모델을 한 번에 함께 출시할 버전 묶음(release generation)으로 연결한 최종 고지 검토는 아직 실행하지 않았습니다. 따라서 이 문서는 `Draft`, 공급망 라이선스 검증은 `NOT_RUN`입니다.

## 확인 대상

{_bullet(lock_paths)}

추가로 Android Gradle 의존성, Python 의존성, 모델 파일·학습데이터 출처, 아이콘·음성·지도 SDK, TMAP 약관, 복사한 코드와 생성 도구를 확인해야 합니다.

## 승인 전 완료조건

- 각 항목의 이름, 버전, 출처, 라이선스 식별자, 원문 위치, 수정 여부, 배포 방식 기록
- source·binary·model·dataset·외부 SDK의 서로 다른 의무 분리
- notice·소스 제공·표시·재배포 제한과 개인정보 약관 반영
- 정확한 출시 파일 목록·지문 기록(release manifest)과 소프트웨어 구성품 목록(SBOM)에 연결
- 불명확하거나 충돌하는 권리는 출시에서 제외하거나 권리자로부터 허락 확보

## 현재 금지

- 의존성 버전 고정 파일(lock 파일)이 있다는 이유만으로 재배포 권리가 확인됐다고 표시하지 않는다.
- 모델 파일과 학습데이터의 라이선스를 같은 것으로 가정하지 않는다.
- 과거 Web/PWA 의존성을 Android 정식 고지에 무조건 포함하지 않는다.

## 이번 버전 변경점

- DEV-17 정식 초안을 개설하고 최종 판정을 `PENDING_REVIEW`로 유지했다.
"""


def _sir_document(policy: dict[str, Any]) -> str:
    return _control_header(
        "WalkSafe 소프트웨어 통합 보고서",
        ["DEV-21"],
        "승인 설계에 맞춰 Android·서버·모델·외부 서비스를 실제로 통합했고 그 결과를 증명했는가?",
    ) + f"""

## 현재 결론

**통합 완료를 주장하지 않습니다.** 저장소에는 Android·백엔드·모델·음성·Web 후보와 과거 시험 기록이 있으나, 정책 기준선 1.0.0 이후 이름 붙인 build에 대해 54개 기능 전체를 재검증한 통합 실행은 없습니다.

| 항목 | 현재 상태 | 완료에 필요한 증거 |
|---|---|---|
| Android 사용자 앱 ↔ 백엔드 | 재검증 필요 | 인증·TMAP·신고·삭제 API 계약시험과 실기기 E2E |
| Android 관리자 앱 ↔ 백엔드 | 구현 경계 확인 필요 | 별도 앱 build, RBAC, 검수·기관 전달 흐름 |
| Android ↔ TFLite 모델 | 후보 존재 | 출시 모델 파일 지문(hash), 변환 동등성, 기기별 성능 |
| Android ↔ TMAP | 후보 존재 | schema·장애·쿼터·이탈·사용자 재탐색 판단 시험 |
| 원본 수집 ↔ 저장·삭제 | 전체 재검증 필요 | 동의, 암호화, 전송 확인, 기간 만료, 권리요청 E2E |
| 운영·복구 | 미실행 | 단일 관리자 복구훈련과 backup/restore 증거 |

## 통합 실행 때 반드시 기록할 값

- source commit·working tree 상태
- Android 사용자·관리자 APK ID와 SHA-256
- 서버 실행 이미지·빌드, DB 구조 변경(migration), OpenAPI 파일 지문(SHA-256)
- TFLite 모델·설정·threshold SHA-256
- 기기·OS·권한·네트워크·TMAP 환경
- 실행 시작·종료 시각, 시험 수행자, 원자료 위치·파일 지문(hash)
- 실패·skip·부분 성공·결함·잔여위험

## 아직 실행하지 않은 필수 검증

{_bullet(gate['id'] + ': ' + gate['title'] for gate in policy['remaining_gates'])}

## 승인 경계

- 통합 상태: `NOT_RUN_FOR_APPROVED_BASELINE`
- 구현 완료: 주장하지 않음
- 시험 완료: 주장하지 않음
- 출시 상태: `NOT_ELIGIBLE`

## 이번 버전 변경점

- DEV-21 보고서 틀을 처음 개설했다. 실제 통합 실행 뒤 새 build별 instance로 결과를 추가해야 한다.
"""


def _test_methods(text: str) -> list[str]:
    lowered = text.lower()
    checks = [
        ("ACCESSIBILITY_OR_USABILITY", ("talkback", "접근", "화면읽기", "사용성", "이해도", "오해")),
        ("DEVICE_OR_FIELD", ("실기기", "현장", "보행", "소음", "기기")),
        ("INTEGRATION_OR_CONTRACT", ("api", "서버", "전송", "중복", "데이터베이스", "계약")),
        ("PERFORMANCE_OR_CAPACITY", ("성능", "지연", "부하", "발열", "배터리", "용량")),
        ("FAILURE_OR_RECOVERY", ("복구", "장애", "오프라인", "연결끊김", "안전정지", "철회", "삭제")),
        ("FUNCTIONAL", ("자동검사", "기능 확인")),
    ]
    methods = [method for method, tokens in checks if any(token in lowered for token in tokens)]
    return methods or ["FUNCTIONAL"]


def _test_method(text: str) -> str:
    return _test_methods(text)[0]


METHOD_EXECUTION_PLAN: dict[str, dict[str, list[str]]] = {
    "ACCESSIBILITY_OR_USABILITY": {
        "test_type_ids": ["TST-14", "TST-15"],
        "environment_ids": ["ENV-DEVICE-SUPPORTED", "ENV-FIELD-CONTROLLED"],
        "evidence_types": ["RUN_MANIFEST", "ACCESSIBILITY_RESULT", "USER_OBSERVATION", "RAW_EVIDENCE_SHA256"],
    },
    "DEVICE_OR_FIELD": {
        "test_type_ids": ["TST-09", "TST-13"],
        "environment_ids": ["ENV-DEVICE-SUPPORTED", "ENV-FIELD-CONTROLLED"],
        "evidence_types": ["RUN_MANIFEST", "DEVICE_LOG", "FIELD_OBSERVATION", "RAW_EVIDENCE_SHA256"],
    },
    "ENGINEERING_REVIEW": {
        "test_type_ids": ["TST-08"],
        "environment_ids": ["ENV-UNIT-SERVER", "ENV-INTEGRATION-POSTGIS"],
        "evidence_types": ["REVIEW_RECORD", "CONTRACT_DIFF", "APPROVAL_RECORD"],
    },
    "FAILURE_OR_RECOVERY": {
        "test_type_ids": ["TST-16"],
        "environment_ids": ["ENV-DEVICE-SUPPORTED", "ENV-INTEGRATION-POSTGIS"],
        "evidence_types": ["RUN_MANIFEST", "FAILURE_INJECTION_LOG", "RECOVERY_TIMELINE", "RAW_EVIDENCE_SHA256"],
    },
    "FUNCTIONAL": {
        "test_type_ids": ["TST-06", "TST-07", "TST-09"],
        "environment_ids": ["ENV-UNIT-ANDROID", "ENV-UNIT-SERVER", "ENV-DEVICE-SUPPORTED"],
        "evidence_types": ["RUN_MANIFEST", "STEP_RESULT", "APPLICATION_LOG", "RAW_EVIDENCE_SHA256"],
    },
    "INDEPENDENT_REVIEW": {
        "test_type_ids": [],
        "environment_ids": [],
        "evidence_types": ["INDEPENDENT_REVIEW_RECORD", "FINDING_REGISTER", "APPROVAL_RECORD"],
    },
    "INTEGRATION_OR_CONTRACT": {
        "test_type_ids": ["TST-07", "TST-08"],
        "environment_ids": ["ENV-UNIT-SERVER", "ENV-INTEGRATION-POSTGIS", "ENV-DEVICE-SUPPORTED"],
        "evidence_types": ["RUN_MANIFEST", "CONTRACT_RESULT", "REQUEST_RESPONSE_LOG", "RAW_EVIDENCE_SHA256"],
    },
    "MEASUREMENT": {
        "test_type_ids": ["TST-12"],
        "environment_ids": ["ENV-DEVICE-SUPPORTED", "ENV-INTEGRATION-POSTGIS"],
        "evidence_types": ["MEASUREMENT_PROTOCOL", "RAW_MEASUREMENT", "CALCULATION_SHEET", "APPROVAL_RECORD"],
    },
    "PERFORMANCE_OR_CAPACITY": {
        "test_type_ids": ["TST-12"],
        "environment_ids": ["ENV-DEVICE-SUPPORTED", "ENV-INTEGRATION-POSTGIS"],
        "evidence_types": ["RUN_MANIFEST", "LOAD_PROFILE", "RAW_MEASUREMENT", "CALCULATION_SHEET"],
    },
    "RECOVERY_DRILL": {
        "test_type_ids": ["TST-16"],
        "environment_ids": ["ENV-DEVICE-SUPPORTED"],
        "evidence_types": ["DRILL_PLAN", "RECOVERY_TIMELINE", "SESSION_REVOCATION_EVIDENCE", "APPROVAL_RECORD"],
    },
}

METHOD_DESIGN_CANDIDATE_REFS = {
    "ACCESSIBILITY_OR_USABILITY": ["DES-15", "DES-18", "DES-22"],
    "DEVICE_OR_FIELD": ["DES-04", "DES-15", "DES-24"],
    "ENGINEERING_REVIEW": ["DES-09", "DES-10"],
    "FAILURE_OR_RECOVERY": ["DES-22", "DES-25", "DES-27"],
    "FUNCTIONAL": ["DES-03", "DES-04", "DES-22"],
    "INDEPENDENT_REVIEW": ["DES-20", "DES-21"],
    "INTEGRATION_OR_CONTRACT": ["DES-09", "DES-10", "DES-11", "DES-22"],
    "MEASUREMENT": ["DES-24"],
    "PERFORMANCE_OR_CAPACITY": ["DES-24"],
    "RECOVERY_DRILL": ["DES-19", "DES-25"],
}

GATE_DECISION_STATUS = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT": "BLOCKED_BY_THRESHOLD_DECISION",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT": "BLOCKED_BY_CONTRACT_DECISION",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW": "BLOCKED_BY_INDEPENDENT_REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT": "BLOCKED_BY_MEASUREMENT_EVIDENCE",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL": "BLOCKED_BY_RECOVERY_DRILL",
}

COMMON_POLICY_GATE_IDS = {
    "NPC-PHONE-QUEUE-CAPACITY": ["GATE-PHONE-QUEUE-BYTE-LIMIT"],
    "NPC-SERVER-CAPACITY-STATE-SYNC": ["GATE-SERVER-CAPACITY-STATE-CONTRACT"],
    "NPC-RAW-ORIGINAL-COLLECTION": ["GATE-RAW-COLLECTION-RELEASE-REVIEW"],
    "NPC-SERVER-STORAGE-CAPACITY": ["GATE-CLOUD-COST-MEASUREMENT"],
    "NPC-SINGLE-ADMIN-RECOVERY": ["GATE-SINGLE-ADMIN-RECOVERY-DRILL"],
}


def _execution_plan(method_or_methods: str | list[str]) -> dict[str, Any]:
    methods = [method_or_methods] if isinstance(method_or_methods, str) else method_or_methods

    def unique(values: list[str]) -> list[str]:
        return list(dict.fromkeys(values))

    test_type_ids = unique(
        [value for method in methods for value in METHOD_EXECUTION_PLAN[method]["test_type_ids"]]
    )
    environment_ids = unique(
        [value for method in methods for value in METHOD_EXECUTION_PLAN[method]["environment_ids"]]
    )
    evidence_types = unique(
        [value for method in methods for value in METHOD_EXECUTION_PLAN[method]["evidence_types"]]
    )
    return {
        "verification_activity_type": methods[0],
        "verification_activity_types": methods,
        "planned_test_type_ids": test_type_ids,
        "test_type_assignment_status": (
            "NOT_A_TST_06_TO_17_TYPE_REQUIRES_CONTROLLED_REVIEW_RECORD"
            if not test_type_ids
            else "DRAFT_METHOD_ASSIGNED_FROM_APPROVED_POLICY_SCENARIO"
        ),
        "eligible_environment_ids": environment_ids,
        "environment_assignment_status": (
            "CONTROLLED_REVIEW_CONTEXT_REQUIRED"
            if not environment_ids
            else "SELECT_ONE_OR_MORE_BEFORE_EXECUTION"
        ),
        "required_evidence_types": evidence_types,
    }


def _design_candidate_refs(methods: list[str]) -> list[str]:
    return list(
        dict.fromkeys(
            reference
            for method in methods
            for reference in METHOD_DESIGN_CANDIDATE_REFS[method]
        )
    )


def _direct_design_ids(source_id: str, policy: dict[str, Any]) -> list[str]:
    _require(
        len(DESIGN_COVERAGE_ID_SEQUENCE) == len(DESIGN_ID_ORDER)
        and set(DESIGN_COVERAGE_ID_SEQUENCE) == set(DESIGN_ID_ORDER)
        and set(DESIGN_TARGET_PATHS) == set(DESIGN_ID_ORDER),
        "design document coverage must contain DES-01 through DES-27 exactly once",
    )
    _require(
        set(design_builder.DESIGN_POLICY_REFS) == set(DESIGN_ID_ORDER)
        and set(design_builder.DESIGN_COMMON_REFS) <= set(DESIGN_ID_ORDER),
        "design source mapping IDs differ from DES-01 through DES-27",
    )
    if source_id.startswith("FP-"):
        selected = [
            design_id
            for design_id in DESIGN_ID_ORDER
            if source_id in design_builder.DESIGN_POLICY_REFS.get(design_id, [])
        ]
    elif source_id.startswith("NPC-"):
        selected = [
            design_id
            for design_id in DESIGN_ID_ORDER
            if source_id in design_builder.DESIGN_COMMON_REFS.get(design_id, [])
        ]
    elif source_id.startswith("GATE-"):
        gate = next(
            (item for item in policy["remaining_gates"] if item["id"] == source_id),
            None,
        )
        _require(gate is not None, f"unknown remaining gate: {source_id}")
        affected = set(gate["affected_feature_ids"])
        selected = [
            design_id
            for design_id in DESIGN_ID_ORDER
            if affected & set(design_builder.DESIGN_POLICY_REFS.get(design_id, []))
        ]
    else:
        raise FormalBundleError(f"unsupported policy trace source: {source_id}")
    _require(bool(selected), f"direct design declaration is empty: {source_id}")
    return selected


def _trace_fields(
    source_id: str,
    clause_id: str | None,
    acceptance_id: str,
    design_candidate_ids: list[str],
    policy: dict[str, Any],
) -> dict[str, Any]:
    requirement_id = f"RQ-{source_id}-001"
    # Direct edges use the same source-to-design mapping as the REQ RTM.  The
    # method-specific DES IDs remain separate execution-method candidates.
    direct_design_ids = _direct_design_ids(source_id, policy)
    verification_focus_ids = [
        design_id
        for design_id in design_candidate_ids
        if design_id in set(direct_design_ids)
    ]
    return {
        "source_policy_id": source_id,
        "source_policy_clause_id": clause_id,
        "requirement_id": requirement_id,
        "requirement_reference": _requirement_link(requirement_id),
        "acceptance_condition_id": acceptance_id,
        "design_reference_ids": direct_design_ids,
        "design_references": [_design_link(item) for item in direct_design_ids],
        "design_candidate_reference_ids": verification_focus_ids,
        "design_candidate_references": [
            _design_link(item, trace_status="VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE")
            for item in verification_focus_ids
        ],
        "design_candidate_role": "VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE",
        "design_candidate_links_are_trace_edges": False,
        "design_candidate_empty_reason": (
            None
            if verification_focus_ids
            else "시험방법 후보와 이 요구에 실제 적용되는 설계가 겹치지 않아, 근거 없이 설계를 고르지 않고 시험 초점 목록을 비워 둔다."
        ),
        "trace_validation_status": "DRAFT_REQ_DES_LINKS_DECLARED",
        "link_validation_status": "SEE_EXTERNAL_INTEGRATION_REPORT",
        "integration_report_path": TRACE_INTEGRATION_REPORT_REL,
        "trace_note": "design_reference_ids만 요구↔설계 추적 연결이다. design_candidate_reference_ids는 그 연결 안에서 시험할 부분을 좁혀 보여주는 초점 목록일 뿐 추적 연결이 아니다. 실제 파일 지문·존재·DES 역참조는 생성 순서의 마지막 통합 보고서에서 확인하며, 이 선언은 승인이나 시험 완료가 아니다.",
    }


def _decision_fields(gate_ids: list[str], *, gate_case: bool = False) -> dict[str, Any]:
    statuses = [GATE_DECISION_STATUS[gate_id] for gate_id in gate_ids]
    if gate_case:
        readiness = statuses[0]
    elif statuses:
        readiness = "PROVISIONAL_PENDING_RELATED_GATES"
    else:
        readiness = "DRAFT_CRITERION_REVIEW_REQUIRED"
    return {
        "acceptance_readiness": readiness,
        "unresolved_gate_dependencies": gate_ids,
        "unresolved_decision_statuses": statuses,
        "numeric_threshold_rule": "정책에 명시된 수치만 사용한다. 미확정 수치는 만들지 않고 차단 상태와 결정 근거를 기록한다.",
    }


def _feature_case(
    policy: dict[str, Any],
    feature: dict[str, Any],
    index: int,
    scenario: str,
) -> dict[str, Any]:
    case_id = f"TC-{feature['id']}-{index:02d}"
    acceptance_id = f"AC-{feature['id']}-{index:02d}"
    methods = _test_methods(scenario)
    method = methods[0]
    design_candidate_ids = _design_candidate_refs(methods)
    gate_ids = [gate["id"] for gate in feature["remaining_gates"]]
    case = {
        "test_case_id": case_id,
        "version": VERSION,
        "title": f"{feature['name']} — 검증 {index}",
        **_trace_fields(
            feature["id"],
            feature["policy_clause_id"],
            acceptance_id,
            design_candidate_ids,
            policy,
        ),
        "method": method,
        **_execution_plan(methods),
        "objective": scenario,
        "preconditions": feature["start_conditions"],
        "test_data_requirements": feature.get("inputs", []),
        "test_data_ids": [],
        "test_data_assignment_status": "PENDING_CONTROLLED_TEST_DATA_SELECTION",
        "procedure": [
            "source commit, build, 모델, 설정, DB 변경, 환경·기기와 수행자를 실행 원장에 먼저 기록한다.",
            "사전조건을 만들고 승인된 시험자료만 준비한다.",
            f"다음 검증 상황을 재현해 대상 기능을 실행한다: {scenario}",
            "각 단계의 실제 결과와 로그·화면·측정 원자료를 저장하고 파일 지문(SHA-256)을 계산한다.",
            "실제 결과를 아래 합격조건과 비교하고 불일치는 결함 ID로 연결한다.",
        ],
        "acceptance_criteria": [
            {
                "criterion_id": acceptance_id,
                "verification_statement": scenario,
                "pass_rule": "검증 질문의 답이 모두 ‘예’이고, 반대 동작이나 정책에서 금지한 추가 동작이 관찰되지 않는다.",
            }
        ],
        "expected_result": "검증 질문의 모든 조건이 충족되고, 반대 동작·허용되지 않은 추가 동작·미설명 오류가 발생하지 않는다.",
        **_decision_fields(gate_ids),
        "related_gate_ids": gate_ids,
        "sensitive_evidence_rule": "실제 영상·음성·정확 위치·사람·기기 식별값은 Git에 넣지 않고 암호화 저장소의 통제 ID와 SHA-256만 기록한다.",
        "execution_status": "NOT_RUN",
        "result": None,
        "evidence_ids": [],
        "defect_ids": [],
        "owner_role": "QA책임자",
        "reviewer_roles": ["기술책임자"],
    }
    if feature["id"] == "FP-035":
        case.update(
            {
                "source_issue_ids": [FP035_NETWORK_ISSUE_ID],
                "change_tracking_refs": [FP035_NETWORK_ISSUE_ID],
                "fp035_dependency_relation": "DIRECT_FORMAL_TEST_DEPENDENCY",
                "policy_correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
                "policy_correction_candidate_refs": [FP035_CORRECTION_CANDIDATE_ID],
                "correction_candidate_binding": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
                "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
                "bundled_approval_dependency_refs": list(FP035_APPROVAL_BLOCKERS),
                "approval_blockers": list(FP035_APPROVAL_BLOCKERS),
                "execution_blockers": list(FP035_APPROVAL_BLOCKERS),
                "approval_readiness": FP035_BRANCH_READINESS,
                "execution_readiness": FP035_BRANCH_READINESS,
                "mobile_network_branch_formal_test_status": FP035_FORMAL_TEST_STATUS,
                "formal_branch_implementation_and_test_frozen": True,
                "network_branch_redesign_after_issue_resolution": FP035_NETWORK_BRANCH_REDESIGN,
                "network_branch_redesign_status": "NORMALIZED_NOT_YET_BASELINED",
                "normalized_network_policy": FP035_NORMALIZED_POLICY,
            }
        )
    return case


def _build_test_cases(policy: dict[str, Any]) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for feature in policy["features"]:
        for index, scenario in enumerate(feature["verification_scenarios"], start=1):
            cases.append(_feature_case(policy, feature, index, scenario))
    for common in policy["common_policies"]:
        methods = _test_methods(common["summary"])
        method = methods[0]
        acceptance_id = f"AC-{common['id']}-01"
        gate_ids = COMMON_POLICY_GATE_IDS.get(common["id"], [])
        design_candidate_ids = _design_candidate_refs(methods)
        cases.append(
            {
                "test_case_id": f"TC-{common['id']}-01",
                "version": VERSION,
                "title": f"공통정책 — {common['title']}",
                **_trace_fields(
                    common["id"],
                    common["id"],
                    acceptance_id,
                    design_candidate_ids,
                    policy,
                ),
                "method": method,
                **_execution_plan(methods),
                "objective": common["summary"],
                "preconditions": ["정책이 적용되는 기능과 정확한 build·환경을 식별했다."],
                "test_data_requirements": ["각 규칙의 정상·거부·실패·복구 경계를 재현할 통제 자료"],
                "test_data_ids": [],
                "test_data_assignment_status": "PENDING_CONTROLLED_TEST_DATA_SELECTION",
                "procedure": [
                    "실행 대상 build·환경과 적용 기능을 기록한다.",
                    "각 정책 규칙의 정상 조건과 반대 조건을 하나씩 재현한다.",
                    "규칙별 실제 결과와 원자료를 따로 저장한다.",
                    "모든 규칙의 합격조건을 충족했는지 판정하고 불일치를 결함으로 연결한다.",
                ],
                "acceptance_criteria": [
                    {
                        "criterion_id": f"{acceptance_id}-{rule_index:02d}",
                        "verification_statement": rule,
                        "pass_rule": "관찰 결과가 이 규칙과 일치하고 반대 동작이 발생하지 않는다.",
                    }
                    for rule_index, rule in enumerate(common["rules"], start=1)
                ],
                "expected_result": f"{common['summary']} 각 세부 규칙이 독립 증거로 확인된다.",
                **_decision_fields(gate_ids),
                "related_gate_ids": gate_ids,
                "sensitive_evidence_rule": "사람·기기 식별값은 가명 ID로 기록하고 원본 개인정보는 암호화 외부 저장소에 둔다.",
                "execution_status": "NOT_RUN",
                "result": None,
                "evidence_ids": [],
                "defect_ids": [],
                "owner_role": "QA책임자",
                "reviewer_roles": ["기술책임자"],
            }
        )
    for gate in policy["remaining_gates"]:
        acceptance_id = f"AC-{gate['id']}-01"
        method = gate["kind"]
        design_candidate_ids = _design_candidate_refs([method])
        cases.append(
            {
                "test_case_id": f"TC-{gate['id']}-01",
                "version": VERSION,
                "title": f"필수 검증 종결 — {gate['title']}",
                **_trace_fields(
                    gate["id"],
                    None,
                    acceptance_id,
                    design_candidate_ids,
                    policy,
                ),
                "method": method,
                **_execution_plan(method),
                "objective": gate["closure"],
                "preconditions": ["관련 설계·빌드·환경·측정 방법을 식별하고 사전 승인했다."],
                "test_data_requirements": ["승인된 측정·검토·훈련 protocol과 대표 환경"],
                "test_data_ids": [],
                "test_data_assignment_status": "PENDING_GATE_PROTOCOL_APPROVAL",
                "procedure": [
                    "필수 검증의 종결 방법과 책임자·검토자·대상 빌드·환경을 먼저 승인한다.",
                    f"승인된 방법으로 다음 종결 작업을 수행한다: {gate['closure']}",
                    "원자료·계산·검토 지적·조치를 덮어쓰지 않는 새 증거 파일에 기록한다.",
                    "제품책임자와 필요한 전문검토자가 종결조건 충족 여부를 서명한다.",
                ],
                "acceptance_criteria": [
                    {
                        "criterion_id": acceptance_id,
                        "verification_statement": gate["closure"],
                        "pass_rule": "종결 문장의 모든 작업과 필요한 승인·원자료가 완료되어야 한다. 미확정 수치는 임의로 채우지 않는다.",
                    }
                ],
                "expected_result": "필수 검증의 종결 조건을 충족했다는 원자료와 검토·승인 기록이 함께 생성되고 미결정 값이 남지 않는다.",
                **_decision_fields([gate["id"]], gate_case=True),
                "related_gate_ids": [gate["id"]],
                "sensitive_evidence_rule": "검토자·수행자·기기는 가명 ID로 기록하고 개인정보 원본은 Git 밖의 암호화 저장소에 둔다.",
                "execution_status": "NOT_RUN",
                "result": None,
                "evidence_ids": [],
                "defect_ids": [],
                "owner_role": "QA책임자",
                "reviewer_roles": ["제품책임자", "관련 전문검토자"],
            }
        )
    ids = [item["test_case_id"] for item in cases]
    _require(len(ids) == len(set(ids)), "duplicate generated test case ID")
    return cases


def _test_case_register(cases: list[dict[str, Any]]) -> dict[str, Any]:
    methods: dict[str, int] = {}
    for case in cases:
        methods[case["method"]] = methods.get(case["method"], 0) + 1
    return {
        "schema_version": "walksafe.test-case-register.v1",
        "metadata": _metadata(["TST-05"], "WalkSafe 시험 케이스·절차 원장"),
        "summary": {
            "test_case_count": len(cases),
            "not_run_count": len(cases),
            "pass_count": 0,
            "fail_count": 0,
            "method_counts": methods,
            "source_trace_count": len({case["source_policy_id"] for case in cases}),
            "req_des_trace_validation_status": "DECLARED_SEE_EXTERNAL_INTEGRATION_REPORT",
            "post_generation_integration_report_path": TRACE_INTEGRATION_REPORT_REL,
            "fp035_policy_issue_id": FP035_NETWORK_ISSUE_ID,
            "fp035_bundle_approval_pending_test_case_count": len(
                [case for case in cases if case["source_policy_id"] == "FP-035"]
            ),
            "fp035_normalization_status": FP035_NORMALIZATION_STATUS,
        },
        "test_cases": cases,
        "execution_record_boundary": {
            "this_file_role": "GENERATED_PLAN_TEMPLATE",
            "edit_execution_results_here": False,
            "append_only_instance_path": "docs/deliverables/06-testing/evidence/executions/<run-id>.json",
            "snapshot_rule": "실행 결과는 새 instance에 기록하고 승인된 snapshot 생성기로만 집계한다.",
        },
        "approval_boundary": {
            "test_case_design_status": "DRAFT",
            "test_execution_status": "NOT_RUN",
            "test_completion_claimed": False,
            "release_status": RELEASE_STATUS,
        },
    }


def _environment_register() -> dict[str, Any]:
    common_instance_fields = [
        "environment_instance_id", "environment_id", "owner_role", "source_commit",
        "provisioned_at", "retire_at", "tool_and_runtime_versions", "network_profile",
        "test_data_boundary", "secret_source_reference", "verification_evidence_id",
        "approval_status",
    ]
    return {
        "schema_version": "walksafe.test-environment-register.v1",
        "metadata": _metadata(["TST-03"], "WalkSafe 시험 환경·지원 기기 원장"),
        "instance_field_contract": {
            "required_fields": common_instance_fields,
            "identity_rule": "실제 기기 serial·계정·secret 값 대신 가명 ID와 통제 저장소 참조를 쓴다.",
            "readiness_rule": "필수 필드와 환경 smoke 검증 증거가 없으면 정식 실행에 사용하지 않는다.",
        },
        "environments": [
            {
                "environment_id": "ENV-UNIT-ANDROID",
                "purpose": "Android JVM 단위시험",
                "required_bindings": ["source commit", "JDK", "Gradle", "Android plugin", "model-config hash"],
                "current_instance_status": "NOT_PROVISIONED_FOR_FORMAL_RUN",
            },
            {
                "environment_id": "ENV-UNIT-SERVER",
                "purpose": "Python 단위·API 계약시험",
                "required_bindings": ["source commit", "Python", "requirements lock", "OpenAPI hash"],
                "current_instance_status": "NOT_PROVISIONED_FOR_FORMAL_RUN",
            },
            {
                "environment_id": "ENV-INTEGRATION-POSTGIS",
                "purpose": "격리 PostGIS 통합시험",
                "required_bindings": ["DB image digest", "migration head", "test-only database identity"],
                "current_instance_status": "NOT_PROVISIONED_FOR_FORMAL_RUN",
            },
            {
                "environment_id": "ENV-DEVICE-SUPPORTED",
                "purpose": "지원 Android 실기기 기능·성능·접근성 시험",
                "required_bindings": ["manufacturer/model", "device serial pseudonym", "Android/API", "ARCore/depth capability", "APK hash", "model hash", "network"],
                "current_instance_status": "PENDING_SUPPORTED_DEVICE_MATRIX",
            },
            {
                "environment_id": "ENV-FIELD-CONTROLLED",
                "purpose": "통제된 실제 보행·소음·GPS·경로 시험",
                "required_bindings": ["WS-21 안전계획", "참여자 동의", "안전요원", "장소·날씨·조도", "APK/model/config hash"],
                "current_instance_status": "BLOCKED_BY_WS_21_AND_RECOVERY_GATE",
            },
            {
                "environment_id": "ENV-LEGACY-WEB",
                "purpose": "Web/PWA 과거 동작 비교",
                "required_bindings": ["source commit", "Node lock", "browser"],
                "current_instance_status": "REFERENCE_ONLY",
            },
        ],
        "support_matrix_status": "DRAFT_UNAPPROVED",
        "approval_boundary": {
            "formal_test_environment_ready": False,
            "device_field_test_authorized": False,
            "release_status": RELEASE_STATUS,
        },
    }


def _test_plan(
    policy: dict[str, Any], cases: list[dict[str, Any]], project_facts: dict[str, Any]
) -> str:
    method_labels = {
        "ACCESSIBILITY_OR_USABILITY": "접근성·사용성 확인",
        "DEVICE_OR_FIELD": "실제 기기·통제 현장 확인",
        "ENGINEERING_REVIEW": "기술 설계 검토",
        "FAILURE_OR_RECOVERY": "장애·복구 확인",
        "FUNCTIONAL": "기능 확인",
        "INDEPENDENT_REVIEW": "독립 검토",
        "INTEGRATION_OR_CONTRACT": "구성요소 연결·API 약속 확인",
        "MEASUREMENT": "실측",
        "PERFORMANCE_OR_CAPACITY": "성능·용량 측정",
        "RECOVERY_DRILL": "복구훈련",
    }
    method_counts: dict[str, int] = {}
    for case in cases:
        method_counts[case["method"]] = method_counts.get(case["method"], 0) + 1
    method_lines = "\n".join(
        f"| {method_labels[method]} (`{method}`) | {count} | 아직 실행하지 않음 |"
        for method, count in sorted(method_counts.items())
    )
    gate_lines = "\n".join(
        f"| {gate['id']} | {gate['title']} | {gate['kind_label']} | NOT_RUN | 미면제 |"
        for gate in policy["remaining_gates"]
    )
    return _control_header(
        "WalkSafe 마스터 시험 전략·시험계획·시험데이터 계획",
        ["TST-01", "TST-02", "TST-03", "TST-04", "TST-05"],
        "무엇을 어떤 순서와 증거 기준으로 시험해야 정책을 구현했다고 말할 수 있는가?",
    ) + f"""

<a id="tst-01"></a>
## 시험 목적과 범위

정식 시험은 확정된 공통정책 9개와 기능정책 54개마다 정책·요구·합격조건 ID를 먼저 지정했습니다. 요구·설계 문서의 실제 ID 존재와 양방향 연결은 통합 구조검사에서 확인했으며, 이 확인은 실제 시험을 실행했다는 뜻이 아닙니다. Android 사용자 앱, 별도 Android 관리자 앱, 서버·DB·TMAP·모델·원본 저장 흐름이 대상입니다. Web/PWA는 과거 비교용이며 Android 정식 제품의 인수시험을 대신하지 않습니다.

현재 생성된 시험 케이스는 {len(cases)}개이고 모두 `NOT_RUN`입니다.

### 단계별 일정과 승인 경계

| 단계 | 시점 | 실행 주체 | 필수 선행 | 판정 |
|---|---|---|---|---|
| 통제 시연 | {project_facts['controlled_demo_date']} 목표 | 프로젝트 책임자·개발자 | 시연 build·기기·사전 안전 checklist | 기술 시연 결과만 기록. 대상 사용자 안전성·출시 합격을 주장하지 않음 |
| 제한 사용자 시험 | 날짜 미정 | QA와 WS-21에서 승인된 안전요원·참여자 | 개발자 통제시험, 동의, 중단·응급계획, 실기기 기준 | 사전 승인된 scenario별 PASS/FAIL/BLOCKED |
| 정식 베타·출시 | 날짜 미정 | QA·보안·개인정보·안전·제품책임자 | 5개 gate 종결, TST-20·21·22, 복귀·운영 준비 | 같은 release generation에 결속된 근거로만 GO/CONDITIONAL GO/NO-GO |

<a id="tst-02"></a>
## TST-02 시험계획과 계층

1. 파일·설정 검사: 정해진 구조, 비밀값 노출, 사용권리, 코드·모델·설정의 파일 지문 확인
2. 작은 기능 단위 검사: 위험판정, 상태 변화, 권한, 삭제·재시도, 입력 해석
3. API 약속 검사: Android/관리자/서버/TMAP 요청·응답과 DB 변경 순서 확인
4. 통합시험: 로그인→동의→보행→탐지·길안내→전송·삭제·관리자 검수
5. 실제 휴대전화 전체 흐름 검사: 카메라·모델·GPS·센서·음성인식·음성안내·진동·화면읽기
6. 실패·복구 검사: 인터넷 끊김, 권한 철회, 저장공간 부족, 장애 지속, 이전 버전 복귀
7. 성능·호환성·접근성·사용성: 지원 기기별 처리속도·발열·배터리·소음·조작
8. 통제 현장시험과 사용자 인수: WS-21 안전계획과 복구훈련 완료 뒤
9. 다시 확인하는 시험과 출시 준비도: 한 시점의 필수 증거 묶음을 만든 뒤 TST-22 판단

<a id="tst-05"></a>
## TST-05 시험 케이스·절차

각 케이스는 주 검증방법 하나와 필요한 보조방법 여러 개를 함께 기록합니다. 아래 수는 주 검증방법 기준이며 케이스별 전체 방법·시험유형·환경 후보는 JSON 원장에 있습니다.

| 주 검증방법 | 케이스 수 | 현재 결과 |
|---|---:|---|
{method_lines}

## 합격·불합격 규칙

- 합격(PASS)은 사용한 코드 버전, 앱 설치파일, 모델, 설정, DB 변경, 환경·기기, 수행시각, 원자료 파일 지문이 모두 같은 실행으로 연결된 경우만 허용합니다.
- 기대결과 일부만 확인했거나, 단계를 건너뛰었거나, 가짜 입력만 썼거나, 다른 빌드·과거 Web 결과를 썼거나, 파일이 존재하기만 하는 경우는 합격이 아닙니다.
- 필수 단계 실패를 다른 기능의 성공으로 상쇄하지 않습니다.
- 안전·개인정보·접근성·인증·삭제 결함은 영향과 잔여위험을 검토하기 전 닫지 않습니다.
- 실패한 case는 결함 ID를 만들고 수정 build에서 해당 case와 회귀 범위를 다시 실행합니다.

<a id="tst-03"></a>
## TST-03 시험 환경·지원 기기

환경·기기 한 건의 기록은 [registers/environments.json](registers/environments.json)에 관리합니다. 현재 정식 실행 환경은 준비되지 않았고 지원 기기 목록은 초안입니다.

<a id="tst-04"></a>
## TST-04 시험데이터 계획

- 인공 시험자료·고정 시험자료·실사용 원본을 분리하고 출처·동의·사용권리·버전·파일 지문을 기록합니다.
- 실제 영상·음성·정확 위치는 저장소와 문서에 직접 넣지 않고 승인된 암호화 저장소의 통제 ID로 참조합니다.
- 원본수집 시험은 승인 정책의 가리지 않은 원본 범위를 사용하되 독립 출시 검토 전 외부 공개·출시 근거로 사용하지 않습니다.
- 모델 평가는 학습·검증·시험 분할과 평가 대상 TFLite 파일 지문(hash, 파일이 바뀌었는지 확인하는 값)을 고정합니다.
- 삭제시험은 운영 데이터가 아닌 격리된 시험 계정·DB·시험 저장경로에서 수행합니다.
- 전맹·저시력 사용자를 같은 우선순위로 포함하는 시각장애인 현장시험은 안전요원·중단조건·동의·사고 대응을 먼저 승인합니다.

## 아직 실행하지 않은 필수 검증

| 검증 ID | 내용 | 방식 | 상태 | 면제 |
|---|---|---|---|---|
{gate_lines}

## 조건부 시험 적용성

| 유형 | 현재 판정 | 이유 |
|---|---|---|
| TST-10 사용자 인수 | ACTIVE_BEFORE_LIMITED_USER_TEST | WS-21 동의·안전계획과 대상 사용자가 준비되면 실행 |
| TST-12 성능·부하 | ACTIVE_BEFORE_BETA | 탐지 지연·발열·배터리·서버 용량·비용 실측 필수 |
| TST-13 호환성 | ACTIVE_BEFORE_BETA | 지원 Android 기기·OS·Depth 범위를 실측으로 확정 |
| TST-15 사용성 | ACTIVE_BEFORE_RELEASE | 전맹·저시력 사용자의 이해·조작·중단 가능성 확인 |
| TST-16 장애·복구 | ACTIVE_BEFORE_BETA | 안전정지·오프라인·저장공간·외부 API·관리자 복구 실행 |
| TST-17 설치·업데이트·이전 버전 복귀 | ACTIVE_BEFORE_BETA | 서명 release generation의 설치·교체·rollback 검증 필수 |

## 진입·종료 기준

시험을 시작하려면 승인된 요구·시험계획, 구분 가능한 빌드, 격리 환경, 필요한 현장 안전계획이 있어야 합니다. TST-22 출시 판단을 시작하려면 TST-20, TST-21, REL-01·02, 적용 대상 SEC-14, WS-20과 남은 검증 5개를 모두 끝내야 합니다. 현재는 이 기준을 충족하지 않아 출시 심사 대상이 아닙니다(`NOT_ELIGIBLE`).

## 이번 버전 변경점

- TST-01~05를 처음 개설하고 정책 63개와 남은 필수 검증 5개에서 시험 케이스를 생성했다.
- 과거 시험 자료와 이번 정식 시험 실행을 분리했다.
"""


def _evidence_register(policy: dict[str, Any]) -> dict[str, Any]:
    types = [f"TST-{number:02d}" for number in range(6, 18)]
    activation_stage = {
        "TST-10": "ACTIVE_BEFORE_LIMITED_USER_TEST",
        "TST-12": "ACTIVE_BEFORE_BETA",
        "TST-13": "ACTIVE_BEFORE_BETA",
        "TST-15": "ACTIVE_BEFORE_RELEASE",
        "TST-16": "ACTIVE_BEFORE_BETA",
        "TST-17": "ACTIVE_BEFORE_BETA",
    }
    return {
        "schema_version": "walksafe.test-evidence-register.v1",
        "metadata": _metadata(types, "WalkSafe 시험 구현·실행 증거 원장"),
        "storage_model": {
            "this_file_role": "GENERATED_EMPTY_SNAPSHOT_AND_SCHEMA",
            "edit_this_file_for_execution": False,
            "append_only_instance_path": "docs/deliverables/06-testing/evidence/executions/<run-id>.json",
            "mutation_rule": "CREATE_NEW_FILE_ONLY",
            "snapshot_update_rule": "승인된 집계 절차가 append-only instance를 읽어 새 revision을 만들 때만 갱신한다.",
            "generator_overwrites_append_only_instances": False,
        },
        "evidence_schema": {
            "required_fields": [
                "run_id", "evidence_id", "verification_activity_type", "test_type_id", "test_case_ids", "requirement_ids",
                "policy_ids", "source_commit", "source_dirty", "build_id", "artifact_sha256",
                "model_sha256", "config_sha256", "openapi_sha256", "db_migration",
                "environment_id", "device_pseudonym", "started_at", "finished_at",
                "executor_pseudonym", "reviewer_pseudonym", "raw_evidence_control_id",
                "raw_evidence_access_class", "raw_evidence_retention_until", "raw_evidence_sha256",
                "result", "defect_ids", "residual_risk_ids",
                "waiver_ids", "not_applicable_reasons",
            ],
            "allowed_results": ["PASS", "FAIL", "BLOCKED", "NOT_RUN"],
            "nullable_only_with_reason": [
                "test_type_id", "model_sha256", "config_sha256", "openapi_sha256", "db_migration", "device_pseudonym"
            ],
            "identity_rules": {
                "executor_pseudonym": "실명·이메일 대신 내부 가명 ID를 사용한다.",
                "reviewer_pseudonym": "실명·이메일 대신 내부 가명 ID를 사용한다.",
                "device_pseudonym": "실제 serial·광고식별자 대신 시험기기 가명 ID를 사용한다.",
            },
            "raw_evidence_rule": "영상·음성·정확 위치·서명 원본은 Git에 넣지 않고 암호화 저장소 통제 ID와 SHA-256만 기록한다.",
        },
        "test_types": [
            {
                "test_type_id": type_id,
                "applicability": activation_stage.get(type_id, "ACTIVE"),
                "execution_status": "NOT_RUN",
                "evidence_ids": [],
            }
            for type_id in types
        ],
        "remaining_gates": [
            {"gate_id": gate["id"], "status": "NOT_RUN", "waived": False, "evidence_ids": []}
            for gate in policy["remaining_gates"]
        ],
        "execution_instance_refs": [],
        "evidence": [],
        "approval_boundary": {
            "formal_execution_count": 0,
            "pass_count": 0,
            "test_completion_claimed": False,
            "release_status": RELEASE_STATUS,
        },
    }


def _evidence_readme() -> str:
    return _control_header(
        "WalkSafe 시험 증거 저장 규칙",
        [f"TST-{number:02d}" for number in range(6, 18)],
        "시험을 실행한 뒤 결과가 어떤 build와 환경의 것인지 다시 증명할 수 있게 어떻게 저장하는가?",
    ) + """

## 권장 위치

`docs/deliverables/06-testing/evidence/executions/<run-id>.json`

한 번 만든 실행 파일은 고치거나 덮어쓰지 않습니다. 정정이 필요하면 이전 실행 ID를 가리키는 새 파일을 만듭니다. 생성기가 만드는 `evidence-register.json`은 빈 구조와 현재 집계만 담으며 실제 실행 파일을 덮어쓰지 않습니다.

원자료가 개인정보·대용량 영상·음성을 포함하면 이 저장소에 복사하지 않습니다. 암호화 외부 저장소의 통제 ID, 접근등급, 보존기간, 파일 SHA-256만 실행 파일에 기록합니다.

## 실행 한 건의 필수 결속

- 실행·증거·검증활동 종류·해당 시험유형·시험사례·요구·정책 ID
- 사용한 코드 버전과 실행 전 수정파일 존재 여부
- 앱·서버 빌드 ID와 파일 지문(SHA-256)
- 해당할 때 모델·설정·API 약속·DB 변경의 파일 지문 또는 버전
- 환경 ID, 실제 serial 대신 기기 가명 ID, OS·권한·네트워크
- 시작·종료시각, 수행자 가명 ID, 검토자 가명 ID
- 원자료의 외부 저장 통제 ID, 접근등급, 보존기한과 SHA-256
- 합격·불합격·차단·미실행 결과, 결함, 잔여위험, 예외승인 ID
- 적용되지 않는 필드는 빈칸으로 두지 않고 필드별 사유 기록

## 금지

- 다른 빌드 결과를 복사해 현재 빌드 합격으로 표시
- 여러 실행의 성공 부분만 합쳐 하나의 합격 결과 생성
- 건너뛴 단계·가짜 입력·부분성공을 합격으로 변환
- 원자료를 덮어쓰기
- 사람의 실명·이메일, 기기 serial, 개인정보·비밀값을 일반 Git 파일로 저장

현재 정식 실행 증거는 0건입니다.
"""


def _test_evidence_document(policy: dict[str, Any]) -> str:
    descriptions = {
        "TST-06": ("단위 테스트", "순수 정책·parser·상태기계·계산을 격리해 확인한다.", "ACTIVE"),
        "TST-07": ("통합 테스트", "Android·서버·DB·모델·저장 흐름을 같은 build에서 확인한다.", "ACTIVE"),
        "TST-08": ("API·계약 테스트", "OpenAPI, 인증, TMAP, 신고, 삭제, 중복 계약을 확인한다.", "ACTIVE"),
        "TST-09": ("E2E 테스트", "가입부터 보행·탐지·길안내·신고·권리행사까지 확인한다.", "ACTIVE"),
        "TST-10": ("사용자 인수 테스트", "대상 사용자가 승인 요구를 실제로 인수하는지 확인한다.", "ACTIVE_BEFORE_LIMITED_USER_TEST"),
        "TST-11": ("회귀 테스트", "수정한 기능과 영향 범위가 기존 안전 동작을 깨지 않는지 확인한다.", "ACTIVE"),
        "TST-12": ("성능·부하·스트레스", "기기 추론·배터리·발열과 서버 용량·비용을 측정한다.", "ACTIVE_BEFORE_BETA"),
        "TST-13": ("호환성 테스트", "지원 Android 기기·OS·capability matrix를 확인한다.", "ACTIVE_BEFORE_BETA"),
        "TST-14": ("접근성 테스트", "TalkBack, 초점, 터치, 음성·진동 대체경로를 확인한다.", "ACTIVE"),
        "TST-15": ("사용성 테스트", "전맹·저시력 사용자의 독립 조작과 이해 가능성을 확인한다.", "ACTIVE_BEFORE_RELEASE"),
        "TST-16": ("장애·복구 테스트", "권한 철회·오프라인·저장공간·외부 장애·안전정지를 확인한다.", "ACTIVE_BEFORE_BETA"),
        "TST-17": ("설치·업데이트·이전 버전 복귀", "앱·모델·DB의 설치, 교체, 실패 복구를 확인한다.", "ACTIVE_BEFORE_BETA"),
    }
    sections = []
    for code, (title, purpose, applicability) in descriptions.items():
        sections.append(
            f"""<a id="{code.lower()}"></a>
## {code} {title}

- 적용성: `{applicability}`
- 실행 상태: `NOT_RUN`
- 목적: {purpose}
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.
"""
        )
    return _control_header(
        "WalkSafe 시험 구현·실행 증거",
        list(descriptions),
        "각 시험 유형을 실제로 실행했으며 어느 build·환경의 어떤 결과인지 증명할 수 있는가?",
    ) + """

## 현재 결론

12개 시험 유형 모두 정식 실행 0건입니다. 기존 시험 코드와 과거 결과는 다시 확인할 후보 목록이며 이 문서의 합격으로 계산하지 않습니다. 증거 구조와 저장 규칙은 [evidence/README.md](evidence/README.md)를 따릅니다.

""" + "\n".join(sections) + f"""
## 남은 필수 검증

{_bullet(gate['id'] + ' — ' + gate['title'] + ': 아직 실행하지 않음, 면제되지 않음' for gate in policy['remaining_gates'])}

## 종합 상태

- formal execution: 0
- PASS: 0
- test completion: 주장하지 않음
- release: NOT_ELIGIBLE
"""


def _defects_register() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.defect-register.v1",
        "metadata": _metadata(["TST-18"], "WalkSafe 결함 관리대장"),
        "storage_model": {
            "this_file_role": "GENERATED_EMPTY_SNAPSHOT_AND_SCHEMA",
            "edit_this_file_for_new_defect": False,
            "append_only_instance_path": "docs/deliverables/06-testing/registers/defect-instances/<defect-id>.json",
            "mutation_rule": "CREATE_NEW_REVISION_FILE; NEVER_OVERWRITE_SOURCE_EVIDENCE",
        },
        "allowed_statuses": ["OPEN", "TRIAGED", "IN_PROGRESS", "FIXED_PENDING_RETEST", "CLOSED", "DEFERRED_APPROVED"],
        "severity_rules": {
            "S1": "사람 안전·민감정보·인증·삭제·출시 무결성에 즉각 중대한 영향",
            "S2": "핵심 기능이 잘못되거나 안전한 대체 경로가 없음",
            "S3": "제한된 기능 오류이며 명확한 우회법이 있음",
            "S4": "경미한 표시·문서 오류",
        },
        "defect_schema": {
            "required_fields": [
                "defect_id", "version", "title", "environment_id", "source_test_instance_id",
                "evidence_id", "reproduction_steps", "expected_result", "actual_result", "severity",
                "priority", "owner_role", "status", "target_date", "requirement_ids", "policy_ids",
                "risk_ids", "waiver_ids", "fix_commit", "retest_evidence_ids",
            ],
            "identity_rule": "사람·기기는 실명이나 serial 대신 승인된 가명 ID를 사용한다.",
            "closure_rule": "수정 commit과 같은 결함을 재현한 재시험 evidence가 없으면 CLOSED로 바꾸지 않는다.",
            "nullable_only_with_reason": ["target_date", "fix_commit", "retest_evidence_ids", "waiver_ids"],
        },
        "defects": [],
        "summary": {"total": 0, "open": 0, "closed": 0},
        "note": "결함이 0개라는 시험 결과가 아니라, 정식 시험 실행 전 빈 통제 원장이다.",
    }


def _metrics_register(cases: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "schema_version": "walksafe.test-metrics-register.v1",
        "metadata": _metadata(["TST-19"], "WalkSafe 시험 커버리지·품질지표"),
        "snapshot_boundary": {
            "status": "GENERATED_PRE_EXECUTION_SNAPSHOT",
            "execution_source_pattern": "docs/deliverables/06-testing/evidence/executions/<run-id>.json",
            "manual_edit_allowed": False,
            "note": "실행 뒤에는 append-only 실행 파일에서 새 revision을 집계한다. 이 0건 초안을 직접 고치지 않는다.",
        },
        "design_metrics": {
            "planned_test_case_count": len(cases),
            "policy_feature_count": 54,
            "common_policy_count": 9,
            "remaining_gate_count": 5,
            "policy_sources_with_planned_case": 68,
        },
        "execution_metrics": {
            "executed": 0,
            "passed": 0,
            "failed": 0,
            "blocked": 0,
            "skipped": 0,
            "formal_execution_coverage_percent": 0.0,
        },
        "pre_execution_policy_blockers": {
            "blocked_test_case_count": 4,
            "source_issue_id": FP035_NETWORK_ISSUE_ID,
            "status": "NOT_RUN_OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL",
            "normalized_policy": FP035_NORMALIZED_POLICY,
            "plain_explanation": "FP-035의 세 가지 네트워크 동작은 사용자 지시로 정규화됐다. 다만 새 산출물 묶음 승인 전이므로 관련 시험 4개는 실행 결과가 아니라 NOT_RUN 사전 대기 계획이다.",
        },
        "quality_metrics_status": "NOT_MEASURED",
        "warning": "실행 결과의 차단 수는 0개지만 정규화 지시 포착 뒤 묶음 승인 대기 중인 FP-035 계획은 4개다. 계획된 case 수는 품질 달성률이 아니며, 정확한 build에서 실행된 증거만 execution metric에 포함한다.",
    }


def _residual_risks(policy: dict[str, Any]) -> dict[str, Any]:
    risks = [
        {
            "risk_id": f"RSK-{gate['id']}",
            "source_gate_id": gate["id"],
            "source_control_ids": [gate["id"]],
            "title": gate["title"],
            "status": "OPEN",
            "waived": False,
            "closure": gate["closure"],
            "blocks": ["TST-22 출시 준비도 승인"],
        }
        for gate in policy["remaining_gates"]
    ]
    risks.extend(
        [
            {
                "risk_id": FP035_RESIDUAL_RISK_ID,
                "source_gate_id": None,
                "source_issue_id": FP035_NETWORK_ISSUE_ID,
                "source_control_ids": ["FP-035", FP035_NETWORK_ISSUE_ID],
                "title": "FP-035 원본 전송 네트워크 조건 정규화의 묶음 승인 대기",
                "status": "OPEN",
                "waived": False,
                "normalization_status": FP035_NORMALIZATION_STATUS,
                "normalized_policy": FP035_NORMALIZED_POLICY,
                "closure": "이미 포착한 사용자 정규화 지시를 영향 산출물에 반영하고 새 묶음 승인을 받아 세 가지 네트워크 분기의 요구·설계·시험을 기준선에 결속한다. 사용자에게 같은 정책 선택을 다시 묻지 않는다.",
                "blocks": ["FP-035 시험 승인·실행", "FP-035 구현 확정", "TST-22 출시 준비도 승인"],
            },
            {
                "risk_id": "RSK-EXTERNAL-WS-21",
                "source_gate_id": None,
                "source_control_ids": ["WS-21"],
                "title": "현장시험 참여자 동의·안전계획 미승인",
                "status": "OPEN",
                "waived": False,
                "closure": "현장시험 전에 안전계획을 승인하고 참여자별 동의 원본을 확보한다.",
                "blocks": ["실제 사용자·현장시험"],
            },
            {
                "risk_id": "RSK-EXTERNAL-REL-15",
                "source_gate_id": None,
                "source_control_ids": ["REL-15"],
                "title": "이전 버전으로 되돌리는 절차(rollback)·실행 증거 미확정",
                "status": "OPEN",
                "waived": False,
                "closure": "한 번에 함께 출시할 정확한 버전 묶음(release generation)에서 이전 버전으로 되돌리는 절차(rollback)를 승인하고 시험한다.",
                "blocks": ["TST-17 완료", "단계적 배포"],
            },
            {
                "risk_id": "RSK-EXTERNAL-TST-22-INPUTS",
                "source_gate_id": None,
                "source_control_ids": ["REL-01", "REL-02", "SEC-14", "WS-20"],
                "title": "출시 준비도 외부 입력 미완료",
                "status": "OPEN",
                "waived": False,
                "closure": "릴리스 계획·체크리스트, 활성 침투시험, 실제 휴대폰 E2E 증거를 확보한다.",
                "blocks": ["TST-22 출시 준비도 승인"],
            },
        ]
    )
    return {
        "schema_version": "walksafe.residual-risk-register.v1",
        "metadata": _metadata(["TST-21"], "WalkSafe 미해결 결함·잔여 위험 snapshot"),
        "snapshot_status": "DRAFT_PRE_EXECUTION",
        "risks": risks,
        "open_risk_count": len(risks),
        "release_status": RELEASE_STATUS,
    }


def _str_document(cases: list[dict[str, Any]], policy: dict[str, Any]) -> str:
    return _control_header(
        "WalkSafe 소프트웨어 시험 결과보고서",
        ["TST-20"],
        "정책 기준선 이후 정식 시험을 실제로 얼마나 실행했고 결과와 결함은 무엇인가?",
    ) + f"""

## 현재 결론

정식 시험 묶음은 아직 실행하지 않았습니다. 계획된 시험 케이스 {len(cases)}개 중 실행 0개, 합격 0개, 불합격 0개입니다. 이는 결함이 없다는 뜻이 아니라 결과가 아직 없다는 뜻입니다.

| 지표 | 값 |
|---|---:|
| 계획 case | {len(cases)} |
| 실행 | 0 |
| 합격(PASS) | 0 |
| 불합격(FAIL) | 0 |
| 실행 결과에서 차단됨(BLOCKED) | 0 |
| FP-035 정규화 지시 포착·묶음 승인 대기 | 4 |
| 정식 실행 비율 | 0% |

`BLOCKED 0`은 실행을 시작한 뒤 막힌 결과가 0개라는 뜻입니다. 별도로 FP-035 시험 4개는 네트워크 동작 정규화 지시는 포착했지만 새 산출물 묶음 승인 전이므로 `NOT_RUN` 상태에서 사전 대기합니다.

## 기존 자료의 취급

저장소의 작은 기능 시험, Android 현장 기록, Web/PWA, 과거 보고서는 다시 확인할 후보 목록에만 둡니다. 확정 정책 1.0.0 이후 같은 코드·빌드·모델·설정·환경으로 다시 연결하지 않은 결과는 이 보고서의 합격 합계에 포함하지 않습니다.

## 남은 필수 검증

{_bullet(gate['id'] + ' — ' + gate['title'] + ' (' + gate['status'] + ')' for gate in policy['remaining_gates'])}

## 판정

- 시험 완료: 아니오
- 잔여위험 수용: 미판정
- TST-22 진입 가능: 아니오
- 출시 상태: `NOT_ELIGIBLE`

## 다음 갱신 조건

구분 가능한 빌드에서 정식 시험을 실행하면 빌드별 새 결과보고서를 만들고, 증거·결함·지표·위험의 같은 시점 자료와 파일 지문을 연결합니다.

## 이번 버전 변경점

- TST-20 통제 틀을 처음 개설했으며 미실행 상태를 명시했다.
"""


def _readiness_document(policy: dict[str, Any]) -> str:
    rows = "\n".join(
        f"| {gate['id']} | {gate['title']} | NOT_RUN | 미충족 |" for gate in policy["remaining_gates"]
    )
    return _control_header(
        "WalkSafe 출시 준비도 판단",
        ["TST-22"],
        "현재 후보를 실제 사용자에게 배포해도 되는가?",
    ) + f"""

## 판단 결과

**NOT_ELIGIBLE — 출시 준비도 심사에 진입할 수 없습니다.**

이 판정은 제품을 영구 중단한다는 뜻이 아닙니다. 정식 요구·설계·구현 기준선과 시험 증거, 보안·현장·릴리스 선행조건이 아직 완료되지 않아 출시 승인을 내릴 수 없다는 뜻입니다.

## 반드시 끝내야 할 검증 5개

| 검증 ID | 내용 | 상태 | 판정 |
|---|---|---|---|
{rows}

## 추가 선행조건

- TST-20 구분 가능한 빌드의 시험 결과보고서 승인
- TST-21 같은 시점의 미해결 결함·잔여위험 목록과 수용 결정
- REL-01 릴리스 계획과 REL-02 canonical 승인 체크리스트
- 활성 범위의 SEC-14 침투시험
- WS-20 실제 휴대폰 E2E 영상·결과
- TST-17에 필요한 REL-15 이전 버전 복귀 절차·실행 증거
- 현장시험에는 WS-21 참여자 동의·안전계획

## 금지된 우회

- 남은 검증 5개를 정식 예외승인 없이 생략
- Web/PWA 결과를 Android 출시 근거로 대체
- 개발용·가짜 입력 결과를 출시판 합격으로 변환
- 여러 빌드의 부분 성공을 합쳐 하나의 승인 생성
- 최종 승인자의 명시적 서명 없이 상태 변경

## 다음 판단 시점

모든 필수 입력이 같은 출시 후보와 같은 시점의 증거 묶음으로 연결된 뒤 새 TST-22 문서에서 출시, 조건부 출시, 출시 불가 중 하나를 사람이 승인합니다. 이 초안은 그 승인을 대신하지 않습니다.
"""


def _acceptance_document() -> str:
    return _control_header(
        "WalkSafe 인수 확인서",
        ["TST-23"],
        "승인된 요구와 정확한 release 후보를 인수권자가 실제로 확인하고 받아들였는가?",
    ) + """

## 현재 상태

아직 인수할 준비가 되지 않았습니다(`NOT_READY_FOR_ACCEPTANCE`). TST-23은 필수 산출물이지만 TST-22가 출시 또는 조건부 출시로 승인된 뒤에만 서명할 수 있습니다.

## 서명 전 확인할 값

| 항목 | 값 |
|---|---|
| 요구 기준선 ID·버전·파일 목록과 지문 기록(manifest) SHA | 미정 |
| 설계 기준선 ID·버전·파일 목록과 지문 기록(manifest) SHA | 미정 |
| 빌드·출시 ID·산출물 파일 지문(SHA) | 미정 |
| 모델·설정·DB 구조 변경(migration) 파일 지문(SHA) | 미정 |
| TST-20 STR ID·SHA | 미정 |
| TST-21 잔여위험 snapshot ID·SHA | 미정 |
| TST-22 준비도 결정 ID·SHA | 미정 |
| 알려진 문제·제한·사용자 고지 | 미정 |
| 인수자 이름·역할·서명시각 | 미서명 |

## 인수 문장 템플릿

> 위에 적힌 한 번에 함께 출시할 단일 버전 묶음(release generation)과 승인된 요구 범위, 시험 결과, 알려진 제한, 잔여위험을 확인하고 [인수/조건부 인수/거절]합니다.

현재 이 문장을 선택하거나 서명하지 않습니다. 외부 서명 원본은 별도 보존하고 이 문서는 경로·SHA-256으로만 연결합니다.
"""


def _test_quality_document(cases: list[dict[str, Any]], policy: dict[str, Any]) -> str:
    return _control_header(
        "WalkSafe 시험 품질 판정 묶음",
        ["TST-18", "TST-19", "TST-20", "TST-21", "TST-22", "TST-23"],
        "결함·지표·시험 결과·잔여위험을 모아 출시 준비도와 최종 인수를 판단할 수 있는가?",
    ) + f"""

<a id="tst-18"></a>
## TST-18 결함 관리대장

[registers/defects.json](registers/defects.json)을 정식 원장으로 개설했습니다. 현재 결함 0건은 시험을 실행하지 않았기 때문이며 품질 문제가 없다는 뜻이 아닙니다.

<a id="tst-19"></a>
## TST-19 커버리지·품질지표

[registers/metrics.json](registers/metrics.json)에 계획 case {len(cases)}개, 실행 0개, coverage 0%를 기록했습니다. 계획 수를 실행 품질로 바꾸지 않습니다.

<a id="tst-20"></a>
## TST-20 시험 결과보고서

[software-test-report.md](software-test-report.md)는 정책 기준선 승인 뒤 정식 시험 묶음을 아직 한 번도 실행하지 않았음을 명시합니다. 앱·서버의 새 버전마다 시험을 실행하면, 이전 기록을 덮어쓰지 않고 해당 버전 전용 시험 결과보고서(STR)를 새로 만듭니다.

<a id="tst-21"></a>
## TST-21 미해결 결함·잔여위험

[registers/residual-risks.json](registers/residual-risks.json)에 FP-035 정규화 지시의 묶음 승인 대기, 남은 검증 5개, WS-21, REL-15와 TST-22 판단에 필요한 REL-01·REL-02·SEC-14·WS-20을 미해결 위험으로 보존했습니다. FP-035 정책 선택을 다시 묻지 않으며 어떤 필수 검증도 면제하지 않았습니다.

<a id="tst-22"></a>
## TST-22 출시 준비도

[release-readiness-decision.md](release-readiness-decision.md)의 현재 판정은 `NOT_ELIGIBLE`입니다. 남은 검증 5개와 필수 입력이 같은 출시 후보로 연결되기 전에는 심사를 시작하지 않습니다.

<a id="tst-23"></a>
## TST-23 인수 확인서

[acceptance-receipt.md](acceptance-receipt.md)는 필수 통제 틀이지만 현재 `NOT_READY_FOR_ACCEPTANCE`이며 미서명입니다. TST-22 승인 뒤 지정 인수자가 외부 원본으로 서명해야 합니다.

## 아직 실행하지 않은 필수 검증

{_bullet(gate['id'] + ' — ' + gate['title'] for gate in policy['remaining_gates'])}

## 종합판정

- 시험 실행: 0건
- 합격(PASS): 0건
- 잔여위험: OPEN
- 인수: 미서명
- 출시: NOT_ELIGIBLE
"""


def _source_bindings() -> dict[str, dict[str, str]]:
    return {
        "policy": _source_binding(POLICY_PATH),
        "policy_approval_record": _source_binding(APPROVAL_RECORD_PATH),
        "policy_baseline_manifest": _source_binding(BASELINE_MANIFEST_PATH),
        "artifact_catalog": _source_binding(ARTIFACT_CATALOG_PATH),
        "aligned_decision_register": _source_binding(ALIGNED_DECISION_REGISTER_PATH),
        "approved_project_answers": _source_binding(PROJECT_ANSWERS_PATH),
        "fp035_correction_candidate": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
        "policy_approval_validator_source": _source_binding(POLICY_APPROVAL_VALIDATOR_PATH),
        "decision_alignment_validator_source": _source_binding(DECISION_ALIGNMENT_VALIDATOR_PATH),
        "design_trace_mapping_source": _source_binding(DESIGN_MAPPING_GENERATOR_PATH),
        "generator": _source_binding(GENERATOR_PATH),
    }


def _build_outputs() -> dict[Path, bytes]:
    policy, _manifest, _catalog, _aligned, project_facts = _validate_inputs()
    files = _tracked_implementation_files()
    candidate_tests = _candidate_test_files()
    cases = _build_test_cases(policy)

    outputs: dict[Path, bytes] = {
        DEV_GUIDE_PATH: _md_bytes(_developer_guide(policy, project_facts)),
        IMPLEMENTATION_CONFIGURATION_PATH: _md_bytes(_implementation_configuration_document(files)),
        IMPLEMENTATION_QUALITY_PATH: _md_bytes(_implementation_quality_document(candidate_tests, policy)),
        IMPLEMENTATION_MANIFEST_PATH: _json_bytes(_implementation_manifest(files)),
        MODULE_REGISTER_PATH: _json_bytes(_module_register(files)),
        QUALITY_REGISTER_PATH: _json_bytes(_quality_register(candidate_tests)),
        LICENSES_PATH: _md_bytes(_licenses_document(files)),
        SIR_PATH: _md_bytes(_sir_document(policy)),
        TEST_PLAN_PATH: _md_bytes(_test_plan(policy, cases, project_facts)),
        TEST_EVIDENCE_PATH: _md_bytes(_test_evidence_document(policy)),
        TEST_QUALITY_PATH: _md_bytes(_test_quality_document(cases, policy)),
        ENVIRONMENTS_PATH: _json_bytes(_environment_register()),
        TEST_CASES_PATH: _json_bytes(_test_case_register(cases)),
        EVIDENCE_README_PATH: _md_bytes(_evidence_readme()),
        EVIDENCE_REGISTER_PATH: _json_bytes(_evidence_register(policy)),
        DEFECTS_PATH: _json_bytes(_defects_register()),
        METRICS_PATH: _json_bytes(_metrics_register(cases)),
        RESIDUAL_RISKS_PATH: _json_bytes(_residual_risks(policy)),
        STR_PATH: _md_bytes(_str_document(cases, policy)),
        READINESS_PATH: _md_bytes(_readiness_document(policy)),
        ACCEPTANCE_PATH: _md_bytes(_acceptance_document()),
    }

    file_entries = [
        {
            "path": _rel(path),
            "sha256": _sha256_bytes(content),
            "byte_length": len(content),
            "artifact_type_ids": (
                DEV_TYPE_COVERAGE.get(path.relative_to(DEV_DIR).as_posix(), [])
                if path.is_relative_to(DEV_DIR)
                else TST_TYPE_COVERAGE.get(path.relative_to(TST_DIR).as_posix(), [])
            ),
        }
        for path, content in sorted(outputs.items(), key=lambda item: _rel(item[0]))
    ]
    manifest = {
        "schema_version": "walksafe.formal-dev-test-draft-manifest.v1",
        "metadata": {
            **_metadata(
                [f"DEV-{number:02d}" for number in range(1, 22)]
                + [f"TST-{number:02d}" for number in range(1, 24)],
                "WalkSafe DEV·TST 정식 산출물 Draft manifest",
            ),
            "manifest_id": "WS-FORMAL-DEV-TST-DRAFT-20260721-001",
            "controlled_revision": 1,
        },
        "source_bindings": _source_bindings(),
        "source_binding_sha256": _object_sha256(_source_bindings()),
        "coverage": {
            "DEV": {"expected": 21, "covered": 21},
            "TST": {"expected": 23, "covered": 23},
            "duplicate_artifact_type_ids": [],
            "missing_artifact_type_ids": [],
        },
        "approved_project_facts_applied": project_facts,
        "fp035_policy_normalization": {
            "issue_id": FP035_NETWORK_ISSUE_ID,
            "status": FP035_NORMALIZATION_STATUS,
            "owner_clarification_required": False,
            "normalized_policy": FP035_NORMALIZED_POLICY,
            "normative_rule": FP035_NORMATIVE_RULE,
            "correction_candidate_binding": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
            "correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
            "correction_candidate_approval_status": "NOT_APPROVED",
            "correction_candidate_effective_status": "NOT_EFFECTIVE",
            "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
            "approval_blockers": list(FP035_APPROVAL_BLOCKERS),
            "mobile_network_branch_implementation_and_test_readiness": FP035_BRANCH_READINESS,
            "mobile_network_branch_implementation_status": FP035_BRANCH_IMPLEMENTATION_STATUS,
            "mobile_network_branch_formal_test_status": FP035_FORMAL_TEST_STATUS,
            "policy_effect_claimed": False,
            "related_test_case_count": 4,
            "related_test_status": "NOT_RUN",
            "approval_boundary": "사용자 정규화 지시는 포착했으나 새 산출물 묶음 승인 전이다. 같은 정책 선택을 다시 묻지 않는다.",
        },
        "generated_files": file_entries,
        "test_case_count": len(cases),
        "formal_test_execution_count": 0,
        "trace_integration_boundary": {
            "requirement_and_acceptance_id_format_reserved": True,
            "stable_requirement_and_design_links_declared": True,
            "req_des_existence_validation_status": "SEE_EXTERNAL_POST_GENERATION_REPORT",
            "req_des_hash_source_binding_added": False,
            "post_generation_report_path": TRACE_INTEGRATION_REPORT_REL,
            "reason": "REQ가 이 시험계획을 downstream 입력으로 결속하므로 ID·상대경로만 선언한다. 실제 RTM·DES 지문과 역참조는 모든 upstream 생성 뒤 별도 leaf report에 기록해 순환을 막는다.",
        },
        "append_only_execution_boundary": {
            "generated_template_paths_are_live_execution_records": False,
            "execution_instance_path": "docs/deliverables/06-testing/evidence/executions/<run-id>.json",
            "defect_instance_path": "docs/deliverables/06-testing/registers/defect-instances/<defect-id>.json",
            "generator_overwrites_instance_paths": False,
        },
        "reproducibility_boundary": {
            "current_workspace_check": "SUPPORTED_BY_GENERATOR_CHECK",
            "git_commit_only_reconstruction": "NOT_YET_CLAIMED",
            "required_before_approval": "generator·통제 입력·생성물을 commit/tag 또는 보존 archive로 함께 결속한다.",
        },
        "remaining_gates": [
            {"id": gate["id"], "status": "NOT_RUN", "waived": False}
            for gate in policy["remaining_gates"]
        ],
        "authorization_boundary": {
            "policy_baseline_status": "BASELINED",
            "formal_deliverable_lifecycle_status": LIFECYCLE_STATUS,
            "formal_deliverables_approved": False,
            "implementation_completion_claimed": False,
            "test_completion_claimed": False,
            "remaining_gates_waived": False,
            "release_status": RELEASE_STATUS,
        },
    }
    manifest["manifest_content_sha256"] = _object_sha256(manifest)
    outputs[DEV_TEST_MANIFEST_PATH] = _json_bytes(manifest)
    return outputs


def _validate_generated_outputs(outputs: dict[Path, bytes]) -> None:
    for path in outputs:
        _require(
            not path.is_relative_to(APPEND_ONLY_EXECUTION_ROOT)
            and not path.is_relative_to(APPEND_ONLY_DEFECT_ROOT),
            f"generator must not write append-only instance: {_rel(path)}",
        )
    expected_dev = {f"DEV-{number:02d}" for number in range(1, 22)}
    expected_tst = {f"TST-{number:02d}" for number in range(1, 24)}
    dev_covered = [code for codes in DEV_TYPE_COVERAGE.values() for code in codes]
    tst_covered = [code for codes in TST_TYPE_COVERAGE.values() for code in codes]
    _require(set(dev_covered) == expected_dev and len(dev_covered) == 21, "generated DEV coverage invalid")
    _require(set(tst_covered) == expected_tst and len(tst_covered) == 23, "generated TST coverage invalid")

    case_register = json.loads(outputs[TEST_CASES_PATH])
    cases = case_register["test_cases"]
    policy = load_strict_json(POLICY_PATH)
    _require(len(cases) == 279, "generated test case count differs")
    _require(cases and all(case["execution_status"] == "NOT_RUN" for case in cases), "generated test status invalid")
    _require(all(case["result"] is None and not case["evidence_ids"] for case in cases), "generated test evidence must be empty")
    _require(case_register["summary"]["pass_count"] == 0, "generated PASS count must be zero")
    _require(
        all(
            case["procedure"]
            and case["expected_result"] not in case["procedure"]
            and case["acceptance_criteria"]
            and "planned_test_type_ids" in case
            and "eligible_environment_ids" in case
            and "test_data_requirements" in case
            and "required_evidence_types" in case
            and case["trace_validation_status"] == "DRAFT_REQ_DES_LINKS_DECLARED"
            and case["link_validation_status"] == "SEE_EXTERNAL_INTEGRATION_REPORT"
            and case["integration_report_path"] == TRACE_INTEGRATION_REPORT_REL
            and case["requirement_reference"] == _requirement_link(case["requirement_id"])
            and case["design_reference_ids"]
            == _direct_design_ids(case["source_policy_id"], policy)
            and case["design_references"]
            == [
                _design_link(design_id)
                for design_id in _direct_design_ids(case["source_policy_id"], policy)
            ]
            and case["design_candidate_references"]
            == [
                _design_link(
                    design_id,
                    trace_status="VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE",
                )
                for design_id in case["design_candidate_reference_ids"]
            ]
            and set(case["design_candidate_reference_ids"])
            <= set(case["design_reference_ids"])
            and case["design_candidate_role"]
            == "VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE"
            and case["design_candidate_links_are_trace_edges"] is False
            and (
                case["design_candidate_empty_reason"] is None
                if case["design_candidate_reference_ids"]
                else bool(case["design_candidate_empty_reason"])
            )
            for case in cases
        ),
        "generated test cases are not executable Draft structures",
    )
    _require(
        all(
            case["test_type_assignment_status"]
            in {
                "DRAFT_METHOD_ASSIGNED_FROM_APPROVED_POLICY_SCENARIO",
                "NOT_A_TST_06_TO_17_TYPE_REQUIRES_CONTROLLED_REVIEW_RECORD",
            }
            for case in cases
        )
        and not any(
            case["test_type_assignment_status"] == "PROVISIONAL_REVIEW_REQUIRED"
            for case in cases
        ),
        "test-method assignment must be concrete Draft planning, not an unresolved provisional label",
    )
    _require(
        {
            design_id
            for case in cases
            for design_id in case["design_reference_ids"]
        }
        == set(DESIGN_ID_ORDER),
        "generated direct test-to-design links do not cover DES-01 through DES-27",
    )
    fp035_cases = [case for case in cases if case["source_policy_id"] == "FP-035"]
    _require(
        len(fp035_cases) == 4
        and all(case["source_issue_ids"] == [FP035_NETWORK_ISSUE_ID] for case in fp035_cases)
        and all(case["change_tracking_refs"] == [FP035_NETWORK_ISSUE_ID] for case in fp035_cases)
        and all(case["fp035_dependency_relation"] == "DIRECT_FORMAL_TEST_DEPENDENCY" for case in fp035_cases)
        and all(case["policy_correction_candidate_id"] == FP035_CORRECTION_CANDIDATE_ID for case in fp035_cases)
        and all(case["policy_correction_candidate_refs"] == [FP035_CORRECTION_CANDIDATE_ID] for case in fp035_cases)
        and all(case["correction_candidate_binding"] == _source_binding(FP035_CORRECTION_CANDIDATE_PATH) for case in fp035_cases)
        and all(case["required_activation_event"] == FP035_REQUIRED_ACTIVATION_EVENT for case in fp035_cases)
        and all(case["bundled_approval_dependency_refs"] == FP035_APPROVAL_BLOCKERS for case in fp035_cases)
        and all(case["approval_blockers"] == FP035_APPROVAL_BLOCKERS for case in fp035_cases)
        and all(case["execution_blockers"] == FP035_APPROVAL_BLOCKERS for case in fp035_cases)
        and all(case["approval_readiness"] == FP035_BRANCH_READINESS for case in fp035_cases)
        and all(case["execution_readiness"] == FP035_BRANCH_READINESS for case in fp035_cases)
        and all(case["execution_status"] == "NOT_RUN" for case in fp035_cases)
        and all(case["mobile_network_branch_formal_test_status"] == FP035_FORMAL_TEST_STATUS for case in fp035_cases)
        and all(case["formal_branch_implementation_and_test_frozen"] is True for case in fp035_cases)
        and all(
            case["network_branch_redesign_after_issue_resolution"] == FP035_NETWORK_BRANCH_REDESIGN
            for case in fp035_cases
        )
        and all(case["normalized_network_policy"] == FP035_NORMALIZED_POLICY for case in fp035_cases),
        "FP-035 planned tests must retain the normalized policy and bundle-approval boundary",
    )
    gate_cases = [case for case in cases if case["source_policy_id"].startswith("GATE-")]
    _require(
        len(gate_cases) == 5
        and {case["acceptance_readiness"] for case in gate_cases} == set(GATE_DECISION_STATUS.values()),
        "gate decision blockers differ",
    )

    evidence = json.loads(outputs[EVIDENCE_REGISTER_PATH])
    _require(evidence["approval_boundary"]["formal_execution_count"] == 0, "formal execution count must be zero")
    _require(not evidence["evidence"], "formal evidence must be empty")
    risks = json.loads(outputs[RESIDUAL_RISKS_PATH])
    gate_risks = [
        item for item in risks["risks"]
        if isinstance(item.get("source_gate_id"), str) and item["source_gate_id"].startswith("GATE-")
    ]
    _require(len(gate_risks) == 5 and all(not item["waived"] for item in gate_risks), "gate risks invalid")
    fp035_risks = [item for item in risks["risks"] if item["risk_id"] == FP035_RESIDUAL_RISK_ID]
    _require(
        len(fp035_risks) == 1
        and fp035_risks[0]["source_issue_id"] == FP035_NETWORK_ISSUE_ID
        and fp035_risks[0]["status"] == "OPEN"
        and fp035_risks[0]["waived"] is False,
        "FP-035 bundle-approval boundary must remain an open residual risk",
    )
    modules = json.loads(outputs[MODULE_REGISTER_PATH])
    _require(
        modules["approval_boundary"]["module_inventory_complete_for_current_tracked_tree"] is False,
        "Draft module inventory must not claim completion",
    )
    _require(len(modules["modules"]) == 8, "module count differs")
    _require(
        all(
            module["requirement_trace_status"] == "DRAFT_REQUIREMENT_LINKS_DECLARED"
            and module["design_trace_status"] == "DRAFT_DESIGN_LINKS_DECLARED"
            and module["link_validation_status"] == "SEE_EXTERNAL_INTEGRATION_REPORT"
            and module["integration_report_path"] == TRACE_INTEGRATION_REPORT_REL
            and module["design_refs"]
            and module["design_links"] == [_design_link(item) for item in module["design_refs"]]
            for module in modules["modules"]
        ),
        "module Draft links are incomplete",
    )
    fp035_modules = [
        module
        for module in modules["modules"]
        if module["module_id"] in {"MOD-ANDROID-USER", "MOD-BACKEND"}
    ]
    _require(
        len(fp035_modules) == 2
        and all("FP-035" in module["policy_refs"] for module in fp035_modules)
        and all(module["source_issue_ids"] == [FP035_NETWORK_ISSUE_ID] for module in fp035_modules)
        and all(module["change_tracking_refs"] == [FP035_NETWORK_ISSUE_ID] for module in fp035_modules)
        and all(module["policy_correction_candidate_refs"] == [FP035_CORRECTION_CANDIDATE_ID] for module in fp035_modules)
        and all(module["correction_candidate_binding"] == _source_binding(FP035_CORRECTION_CANDIDATE_PATH) for module in fp035_modules)
        and all(module["bundled_approval_dependency_refs"] == FP035_APPROVAL_BLOCKERS for module in fp035_modules)
        and all(module["required_activation_event"] == FP035_REQUIRED_ACTIVATION_EVENT for module in fp035_modules)
        and all(module["implementation_blockers"] == FP035_APPROVAL_BLOCKERS for module in fp035_modules)
        and all(module["mobile_data_branch_status"] == FP035_BRANCH_READINESS for module in fp035_modules)
        and all(module["mobile_network_branch_implementation_status"] == FP035_BRANCH_IMPLEMENTATION_STATUS for module in fp035_modules)
        and all(module["mobile_network_branch_formal_test_status"] == FP035_FORMAL_TEST_STATUS for module in fp035_modules)
        and all(module["formal_branch_implementation_and_test_frozen"] is True for module in fp035_modules)
        and next(module for module in fp035_modules if module["module_id"] == "MOD-ANDROID-USER")["fp035_dependency_relation"] == "DIRECT_MOBILE_NETWORK_BRANCH_IMPLEMENTATION_DEPENDENCY"
        and next(module for module in fp035_modules if module["module_id"] == "MOD-BACKEND")["fp035_dependency_relation"] == "RELATED_DOWNSTREAM_UPLOAD_CONTRACT_DEPENDENCY",
        "FP-035 implementation blocker is missing from affected modules",
    )
    web = next(item for item in modules["modules"] if item["module_id"] == "MOD-WEB-LEGACY")
    _require(web["tracked_file_count"] > 0 and web["alignment_status"] == "LEGACY_REFERENCE_ONLY", "legacy Web boundary invalid")
    quality = json.loads(outputs[QUALITY_REGISTER_PATH])
    _require(
        quality["code_review"]["applicability_status"]
        == "ACTIVE_DRAFT_SINGLE_DEVELOPER_CONTROL"
        and quality["code_review"]["records"] == [],
        "single-developer review control boundary differs",
    )
    _require(
        all(Path(item["path"]).suffix != ".md" for item in quality["candidate_test_sources"]),
        "candidate test documentation must not count as test code",
    )
    manifest = json.loads(outputs[DEV_TEST_MANIFEST_PATH])
    implementation = json.loads(outputs[IMPLEMENTATION_MANIFEST_PATH])
    _require(
        implementation["applicability"]["DEV-10"]["status"] == "ACTIVE_DRAFT"
        and implementation["applicability"]["DEV-11"]["status"]
        == "ACTIVE_DRAFT_FOR_SERVER_INFRASTRUCTURE"
        and implementation["applicability"]["DEV-13"]["status"]
        == "ACTIVE_DRAFT_FOR_TEST_AND_DEMO",
        "DEV-10/11/13 applicability decisions differ",
    )
    environments = json.loads(outputs[ENVIRONMENTS_PATH])
    _require(
        set(environments["instance_field_contract"]["required_fields"])
        == {
            "environment_instance_id", "environment_id", "owner_role", "source_commit",
            "provisioned_at", "retire_at", "tool_and_runtime_versions", "network_profile",
            "test_data_boundary", "secret_source_reference", "verification_evidence_id",
            "approval_status",
        }
        and environments["approval_boundary"]["formal_test_environment_ready"] is False,
        "formal test-environment instance contract differs",
    )
    _require(
        manifest["approved_project_facts_applied"] == _project_facts()
        and manifest["source_bindings"].get("approved_project_answers")
        == _source_binding(PROJECT_ANSWERS_PATH),
        "approved project-answer facts or source binding differ",
    )
    _require(
        manifest["fp035_policy_normalization"]["status"] == FP035_NORMALIZATION_STATUS
        and manifest["fp035_policy_normalization"]["normalized_policy"]
        == FP035_NORMALIZED_POLICY
        and manifest["fp035_policy_normalization"]["related_test_status"] == "NOT_RUN"
        and manifest["fp035_policy_normalization"]["correction_candidate_binding"]
        == _source_binding(FP035_CORRECTION_CANDIDATE_PATH)
        and manifest["fp035_policy_normalization"]["correction_candidate_id"]
        == FP035_CORRECTION_CANDIDATE_ID
        and manifest["fp035_policy_normalization"]["correction_candidate_approval_status"]
        == "NOT_APPROVED"
        and manifest["fp035_policy_normalization"]["correction_candidate_effective_status"]
        == "NOT_EFFECTIVE"
        and manifest["fp035_policy_normalization"]["required_activation_event"]
        == FP035_REQUIRED_ACTIVATION_EVENT
        and manifest["fp035_policy_normalization"]["approval_blockers"]
        == FP035_APPROVAL_BLOCKERS
        and manifest["fp035_policy_normalization"]["mobile_network_branch_implementation_and_test_readiness"]
        == FP035_BRANCH_READINESS
        and manifest["fp035_policy_normalization"]["mobile_network_branch_implementation_status"]
        == FP035_BRANCH_IMPLEMENTATION_STATUS
        and manifest["fp035_policy_normalization"]["mobile_network_branch_formal_test_status"]
        == FP035_FORMAL_TEST_STATUS
        and manifest["fp035_policy_normalization"]["policy_effect_claimed"] is False,
        "DEV/TST FP-035 normalization boundary differs",
    )
    _require(
        manifest["source_bindings"].get("policy_approval_record")
        == _source_binding(APPROVAL_RECORD_PATH),
        "DEV/TST manifest approval-record source binding differs",
    )
    for binding_name, source_path in (
        ("policy_approval_validator_source", POLICY_APPROVAL_VALIDATOR_PATH),
        ("decision_alignment_validator_source", DECISION_ALIGNMENT_VALIDATOR_PATH),
    ):
        _require(
            manifest["source_bindings"].get(binding_name)
            == _source_binding(source_path),
            f"DEV/TST manifest validator source binding differs: {binding_name}",
        )
    _require(
        manifest["source_bindings"].get("design_trace_mapping_source")
        == _source_binding(DESIGN_MAPPING_GENERATOR_PATH),
        "DEV/TST manifest design-mapping source binding differs",
    )
    _require("NOT_ELIGIBLE" in outputs[READINESS_PATH].decode("utf-8"), "readiness boundary missing")
    _require("실행 0개" in outputs[STR_PATH].decode("utf-8"), "STR must disclose zero executions")
    developer_guide = outputs[DEV_GUIDE_PATH].decode("utf-8")
    _require(
        "2026-07-26" in developer_guide
        and "feat/<issue-id>" in developer_guide
        and "./gradlew lint" in developer_guide,
        "developer guide project stage or working controls are missing",
    )


def generate() -> dict[Path, bytes]:
    outputs = _build_outputs()
    _validate_generated_outputs(outputs)
    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return outputs


def check() -> dict[Path, bytes]:
    outputs = _build_outputs()
    _validate_generated_outputs(outputs)
    for path, expected in outputs.items():
        _require(path.exists(), f"generated output is missing: {_rel(path)}")
        _require(path.read_bytes() == expected, f"generated output is stale: {_rel(path)}")
    return outputs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify committed outputs without rewriting")
    args = parser.parse_args()
    try:
        outputs = check() if args.check else generate()
    except (FormalBundleError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    action = "verified" if args.check else "generated"
    print(f"{action} {len(outputs)} DEV/TST formal draft files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
