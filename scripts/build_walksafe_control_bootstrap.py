#!/usr/bin/env python3
"""Build and verify the WalkSafe DOC-01~CLS-16 master artifact register."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
from html import escape
import json
from pathlib import Path
import re
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
CONTROL_DIR = REPO_ROOT / "docs" / "control"
DELIVERABLE_DIR = REPO_ROOT / "docs" / "deliverables" / "00-control"
DELIVERABLE_ROOT = REPO_ROOT / "docs" / "deliverables"
DRAFT_MANIFEST_DIR = DELIVERABLE_ROOT / "manifests"
CATALOG_PATH = CONTROL_DIR / "artifact-types.json"
PLAN_PATH = CONTROL_DIR / "documentation-authoring-preparation-plan.md"
MANUAL_PATH = DELIVERABLE_DIR / "document-control-manual.md"
APPROVAL_PATH = (
    CONTROL_DIR
    / "baselines"
    / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
)
POLICY_MANIFEST_PATH = (
    CONTROL_DIR
    / "baselines"
    / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
)
ALIGNED_DECISION_REGISTER_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "walksafe-effective-decision-register-aligned-20260721-r001.json"
)
POLICY_PAYLOAD_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "walksafe-feature-policy-comprehensive-draft.json"
)
REGISTER_PATH = DELIVERABLE_DIR / "artifact-register.json"
CHANGE_LOG_PATH = DELIVERABLE_DIR / "artifact-change-log.json"
README_PATH = DELIVERABLE_DIR / "README.md"
HTML_PATH = DELIVERABLE_DIR / "artifact-register.html"
ROOT_README_PATH = DELIVERABLE_ROOT / "README.md"
TRACE_INTEGRATION_REPORT_PATH = (
    DELIVERABLE_ROOT
    / "traceability"
    / "req-des-tst-integration-report-20260721-r001.json"
)
TRACE_INTEGRATION_SUMMARY_PATH = DELIVERABLE_ROOT / "traceability" / "README.md"
FORMAL_TRACE_7_TO_12_REPORT_PATH = (
    DELIVERABLE_ROOT
    / "traceability"
    / "formal-7-12-integration-report-20260721-r001.json"
)
FORMAL_TRACE_7_TO_12_SUMMARY_PATH = (
    DELIVERABLE_ROOT / "traceability" / "formal-7-12-integration-summary.md"
)
RTM_PATH = DELIVERABLE_ROOT / "03-requirements" / "rtm.json"
DESIGN_TRACE_PATH = DELIVERABLE_ROOT / "04-design" / "design-traceability-register.json"
TEST_CASES_PATH = DELIVERABLE_ROOT / "06-testing" / "registers" / "test-cases.json"
MODULE_REGISTER_PATH = DELIVERABLE_ROOT / "05-implementation" / "module-register.json"
RAID_REGISTER_PATH = DELIVERABLE_ROOT / "01-management" / "registers" / "raid.json"
CHANGE_REQUEST_REGISTER_PATH = (
    DELIVERABLE_ROOT / "01-management" / "registers" / "change-requests.json"
)
RESIDUAL_RISK_REGISTER_PATH = (
    DELIVERABLE_ROOT / "06-testing" / "registers" / "residual-risks.json"
)
FP035_CORRECTION_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
)
AUTHORING_GOAL_INTAKE_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "source-records"
    / "walksafe-artifact-authoring-goal-20260722-r001.intake.json"
)
READINESS_AUDIT_PATH = (
    CONTROL_DIR / "audits" / "walksafe-artifact-content-readiness-audit-20260722-r001.json"
)

FP035_REQUIREMENT_ID = "RQ-FP-035-001"
FP035_CHANGE_REQUEST_ID = "CR-0002"
FP035_RESIDUAL_RISK_ID = "RSK-POLICY-FP035-NETWORK-001"

AS_OF = "2026-07-22"
GENERATED_AT = "2026-07-22T13:15:00+09:00"
NEXT_CONTROL_REVIEW_AT = "2026-07-29"
CONTROL_DOCUMENT_VERSION = "0.4.0"
PROJECT_OWNER_NAME = "김민호"
CATEGORIES = (
    "DOC",
    "MGT",
    "DSC",
    "REQ",
    "DES",
    "DEV",
    "TST",
    "SEC",
    "AIML",
    "REL",
    "OPS",
    "WS",
    "CLS",
)
FORMAL_0_TO_6_CATEGORIES = ("DOC", "MGT", "DSC", "REQ", "DES", "DEV", "TST")
PLANNED_7_TO_12_CATEGORIES = ("SEC", "AIML", "REL", "OPS", "WS", "CLS")
CATEGORY_LABELS = {
    "DOC": "산출물 통제",
    "MGT": "프로젝트 관리",
    "DSC": "발견·제품 기획",
    "REQ": "요구사항",
    "DES": "아키텍처·상세설계",
    "DEV": "구현·빌드",
    "TST": "시험·품질검증",
    "SEC": "보안·개인정보",
    "AIML": "AI·ML·데이터",
    "REL": "릴리스·배포·인도",
    "OPS": "운영·유지보수",
    "WS": "WalkSafe 특화 검증",
    "CLS": "종료·이관",
}
EXPECTED_CATEGORY_COUNTS = {
    "DOC": 5,
    "MGT": 18,
    "DSC": 15,
    "REQ": 19,
    "DES": 27,
    "DEV": 21,
    "TST": 23,
    "SEC": 19,
    "AIML": 26,
    "REL": 22,
    "OPS": 24,
    "WS": 22,
    "CLS": 16,
}
EXPECTED_REQUIRED_COUNT = 148
EXPECTED_CONDITIONAL_COUNT = 109
EXPECTED_GATE_IDS = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
}
ARTIFACT_CODE_PATTERN = re.compile(
    r"\b(?:DOC|MGT|DSC|REQ|DES|DEV|TST|SEC|AIML|REL|OPS|WS|CLS)-\d{2}\b"
)
SAFE_DRAFT_LIFECYCLES = {"DRAFT", "IN_REVIEW"}
CURRENT_BASELINE_INACTIVE = {
    "WS-16": (
        "승인된 1.0.0 제품 범위는 Android 사용자 앱과 별도 Android 관리자 앱이며 "
        "Web/PWA는 레거시 참고 범위다. Web/PWA를 공식 지원 대상으로 다시 승인할 때 재평가한다."
    )
}
CURRENT_BASELINE_ACTIVE = {
    "AIML-01",
    "AIML-03",
    "AIML-06",
    "AIML-07",
    "AIML-08",
    "AIML-09",
    "AIML-10",
    "AIML-17",
    "AIML-21",
    "AIML-22",
    "AIML-23",
    "AIML-24",
    "WS-06",
    "WS-07",
    "WS-09",
    "WS-10",
    "WS-12",
    "WS-14",
    "WS-17",
    "WS-20",
    "WS-21",
}
REVIEW_PROFILES = {
    "WEEKLY_REGISTER": "사건 발생 때 즉시 갱신하고 활성 프로젝트 기간에는 매주 검토",
    "MONTHLY_CONTROL": "변경 발생 때 즉시 갱신하고 활성 프로젝트 기간에는 매월 검토",
    "PER_RELEASE": "릴리스 후보를 만들 때마다 검토하고 승인 전 다시 확인",
    "PER_DATASET_OR_MODEL_VERSION": "데이터셋·모델·임계값 버전이 바뀔 때마다 검토",
    "PER_BUILD_OR_FIELD_CYCLE": "build·기기·시험환경·현장시험 회차가 바뀔 때마다 검토",
    "AT_OPERATION_EVENT_AND_MONTHLY": "운영 사건 발생 때 즉시 갱신하고 운영 중 매월 검토",
    "AT_CLOSURE_EVENT": "종료·이관 조건을 분기마다 확인하고 종료 제안 시 즉시 검토",
    "PER_GENERATION": "새 결과를 생성할 때마다 입력 형상과 함께 검토",
    "ON_RECEIPT_OR_EXPIRY": "외부 원본을 받을 때와 유효기간 만료 전에 검토",
    "ON_CONTROLLED_CHANGE": "통제된 코드·설정·schema가 변경될 때마다 검토",
}
CHANGE_PROFILES = {
    "VERSIONED_DOCUMENT": (
        "변경요청과 영향분석 후 새 문서 버전을 승인한다. 이전 승인본은 Superseded로 "
        "전환해 읽기 전용 보관하고 보존기간 종료 후 Archived 처리한다."
    ),
    "APPEND_ONLY_REGISTER": (
        "기존 이력 행을 지우지 않고 새 행 또는 상태변경 이력을 추가한다. 정기 snapshot을 "
        "고정하고 대체 원장은 이전 원장 ID를 연결한다."
    ),
    "CONTROLLED_SOURCE": (
        "source commit·tag·lock·hash로 변경을 결속한다. 배포된 이전 형상은 덮어쓰지 않고 "
        "새 버전이 대체하며 이전 형상은 재현 가능한 archive로 유지한다."
    ),
    "REGENERATED_EVIDENCE": (
        "결과를 손으로 고치지 않는다. 입력 build·model·config·환경을 고정해 새 evidence "
        "instance를 생성하고 이전 결과는 불변 증거로 보관한다."
    ),
    "EXTERNAL_ORIGINAL": (
        "서명·발행된 원본은 수정하지 않는다. 정정은 새 외부 원본으로 받고 이전 원본과 "
        "대체 관계·서명시각·hash를 연결한다."
    ),
}

SENSITIVE_PERSONAL_DATA_BY_CODE = {
    "DSC-04": ["조사 참여자 신원·연락·동의·서명 원본"],
    "DEV-15": ["코드 검토자 신원·확인·서명 원본"],
    "TST-10": ["인수시험 참여자 신원·연락·동의·서명 원본"],
    "TST-15": ["사용성시험 참여자 신원·연락·동의·서명 원본"],
    "TST-23": ["인수자 신원·확인·서명 원본"],
    "SEC-05": ["개인정보 영향평가 내용"],
    "SEC-06": ["동의자·동의버전·동의시각"],
    "SEC-07": ["데이터 보존·삭제 대상과 처리기록"],
    "SEC-14": ["침투시험 수행자 신원·확인·서명 원본"],
    "AIML-01": ["원본 영상", "원본 음성", "정확 위치", "행동·센서 데이터"],
    "AIML-02": ["데이터셋 구성·민감정보 요약"],
    "AIML-03": ["데이터 출처·동의·라이선스 기록"],
    "AIML-04": ["원본·라벨·위치·센서 schema"],
    "AIML-05": ["데이터 품질 표본·원자료 링크"],
    "AIML-06": ["정제·제외 대상 원자료 링크"],
    "AIML-07": ["라벨링 대상 원자료 링크"],
    "AIML-08": ["라벨 품질검사 표본 링크"],
    "AIML-09": ["학습·검증·시험 분할 파일 목록과 hash"],
    "AIML-10": ["데이터 누수 점검 표본 링크"],
    "WS-04": ["카메라·위치·마이크 권한 상태"],
    "WS-05": ["위치·영상·음성 데이터 흐름"],
    "WS-06": ["정확 위치·이동 경로·시험 영상"],
    "WS-07": ["정확 위치·이동 경로·현장 영상·음성"],
    "WS-09": ["카메라 시험 영상·성능 원자료"],
    "WS-12": ["시험 참여자 음성·명령 corpus"],
    "WS-14": ["접근성 시험 영상·참여자 관찰기록"],
    "WS-20": ["실휴대폰 화면영상·위치·음성·로그"],
    "WS-21": ["참여자 신원·연락·서명·동의·현장기록"],
    "REL-02": ["릴리스 검토자·승인자 신원·확인·서명 원본"],
    "REL-20": ["인계자·인수자 신원·연락·확인·서명 원본"],
    "REL-21": ["검수자·승인자 신원·확인·서명 원본"],
    "OPS-13": ["재해복구 훈련 참여자·검토자 신원·서명 원본"],
    "OPS-14": ["관리자·운영자 계정과 권한"],
    "OPS-17": ["장애 관련 사용자·운영 로그"],
    "OPS-18": ["사고 관련 사용자·운영 로그"],
    "OPS-23": ["개인정보 삭제 대상·실행 receipt"],
    "CLS-02": ["최종 인수자·승인자 신원·확인·서명 원본"],
    "CLS-13": ["계약 당사자·외부업체 담당자 신원·확인·서명 원본"],
    "CLS-14": ["보존·이관·삭제 대상 개인정보"],
    "CLS-15": ["계정·키·인프라 접근정보"],
}
SECURITY_RESTRICTED_CODES = {
    *{f"SEC-{number:02d}" for number in range(2, 20)},
    "OPS-14",
    "OPS-15",
    "OPS-16",
    "OPS-17",
    "OPS-18",
    "OPS-23",
    "CLS-14",
    "CLS-15",
}
DIRECT_POLICY_FEATURE_OVERRIDES = {
    "WS-06": {"FP-022", "FP-023", "FP-050"},
    "WS-07": {"FP-022", "FP-023", "FP-050"},
    "WS-09": {"FP-050"},
    "WS-10": {"FP-038", "FP-050"},
    "WS-14": {"FP-028", "FP-030", "FP-050"},
    "WS-16": {"FP-002"},
    "WS-20": {"FP-050"},
    "WS-21": {"FP-050"},
}
FP035_INDIRECT_IMPACT_CODES = {"SEC-05", "SEC-06", "AIML-01", "AIML-03"}
FP035_CORRECTION_REFS = [
    "ISS-POLICY-FP035-NETWORK-001",
    "CR-0002",
    "RAID-011",
    "WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001",
]

# A path here is a plan, not a claim that the later bundle already exists.
BUNDLE_PATHS = {
    "BND-DOC-MANUAL": "docs/deliverables/00-control/document-control-manual.md",
    "BND-DOC-REGISTER": "docs/deliverables/00-control/artifact-register.json",
    "BND-MGT-CHARTER": "docs/deliverables/01-management/project-charter.md",
    "BND-MGT-PMP": "docs/deliverables/01-management/project-management-plan.md",
    "BND-MGT-CONTROL": "docs/deliverables/01-management/project-control-registers.md",
    "BND-DSC-DISCOVERY": "docs/deliverables/02-discovery/discovery-evidence-and-analysis.md",
    "BND-DSC-PRODUCT": "docs/deliverables/02-discovery/product-definition.md",
    "BND-DSC-REGISTER": "docs/deliverables/02-discovery/product-registers.md",
    "BND-REQ-BASELINE": "docs/deliverables/03-requirements/system-requirements.md",
    "BND-REQ-ACCEPTANCE": "docs/deliverables/03-requirements/acceptance-specification.md",
    "BND-REQ-TRACE": "docs/deliverables/03-requirements/requirements-traceability.md",
    "BND-DES-ARCH": "docs/deliverables/04-design/software-architecture.md",
    "BND-DES-INTERFACE-DATA": "docs/deliverables/04-design/interface-and-data-design.md",
    "BND-DES-UX-ACCESS": "docs/deliverables/04-design/user-experience-and-accessibility-design.md",
    "BND-DES-SECOPS": "docs/deliverables/04-design/security-and-operations-design.md",
    "BND-DEV-GUIDE": "docs/deliverables/05-implementation/developer-guide.md",
    "BND-DEV-CONFIGURATION": "docs/deliverables/05-implementation/implementation-configuration.md",
    "BND-DEV-QUALITY": "docs/deliverables/05-implementation/implementation-quality-record.md",
    "BND-TST-PLAN": "docs/deliverables/06-testing/test-plan.md",
    "BND-TST-EVIDENCE": "docs/deliverables/06-testing/test-evidence.md",
    "BND-TST-QUALITY": "docs/deliverables/06-testing/test-quality-report.md",
    "BND-SEC-PLAN": "docs/deliverables/07-security/security-and-privacy-plan.md",
    "BND-SEC-VERIFICATION": "docs/deliverables/07-security/security-verification-evidence.md",
    "BND-SEC-RESPONSE": "docs/deliverables/07-security/security-response-and-monitoring.md",
    "BND-AIML-DATA": "docs/deliverables/08-ai-ml-data/data-management.md",
    "BND-AIML-MODEL": "docs/deliverables/08-ai-ml-data/model-development.md",
    "BND-AIML-EVALUATION": "docs/deliverables/08-ai-ml-data/model-evaluation.md",
    "BND-AIML-OPS": "docs/deliverables/08-ai-ml-data/model-operations.md",
    "BND-REL-CONTROL": "docs/deliverables/09-release/release-control.md",
    "BND-REL-DEPLOYMENT": "docs/deliverables/09-release/deployment-evidence.md",
    "BND-REL-DELIVERY": "docs/deliverables/09-release/delivery-and-handover.md",
    "BND-OPS-GUIDE": "docs/deliverables/10-operations/operator-guide.md",
    "BND-OPS-RECOVERY": "docs/deliverables/10-operations/recovery-plan.md",
    "BND-OPS-CONTROL": "docs/deliverables/10-operations/operations-control-registers.md",
    "BND-WS-SAFETY": "docs/deliverables/11-walksafe/walksafe-safety-and-policy.md",
    "BND-WS-ACCEPTANCE": "docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md",
    "BND-WS-FIELD-SAFETY": "docs/deliverables/11-walksafe/field-test-safety-records.md",
    "BND-CLS-CLOSURE": "docs/deliverables/12-closure/project-closure.md",
    "BND-CLS-HANDOVER": "docs/deliverables/12-closure/closure-handover-register.md",
    "BND-CLS-DECOMMISSION": "docs/deliverables/12-closure/decommissioning-plan.md",
}
BUNDLE_ADDITIONAL_PATHS = {
    "BND-DOC-REGISTER": ["docs/deliverables/00-control/artifact-change-log.json"],
}
ROOT_REVIEW_PATH_ORDER = [
    "docs/deliverables/03-requirements/system-requirements.md",
    "docs/deliverables/03-requirements/acceptance-specification.md",
    "docs/deliverables/03-requirements/requirements-traceability.md",
    "docs/deliverables/04-design/software-architecture.md",
    "docs/deliverables/04-design/user-experience-and-accessibility-design.md",
    "docs/deliverables/04-design/interface-and-data-design.md",
    "docs/deliverables/04-design/security-and-operations-design.md",
    "docs/deliverables/06-testing/test-plan.md",
    "docs/deliverables/06-testing/test-evidence.md",
    "docs/deliverables/06-testing/test-quality-report.md",
]
ROOT_REVIEW_PATH_RANK = {path: index for index, path in enumerate(ROOT_REVIEW_PATH_ORDER)}

DOC_LOCATIONS = {
    "DOC-01": {
        "path": "docs/deliverables/00-control/artifact-register.json",
        "anchor": None,
        "generated": [
            "docs/deliverables/README.md",
            "docs/deliverables/00-control/README.md",
            "docs/deliverables/00-control/artifact-register.html",
        ],
        "status": "DRAFT",
        "verification": "GENERATED_AND_CHECKED",
    },
    "DOC-02": {
        "path": "docs/deliverables/00-control/document-control-manual.md",
        "anchor": "doc-02",
        "generated": [],
        "status": "DRAFT",
        "verification": "STRUCTURE_CHECKED",
    },
    "DOC-03": {
        "path": "docs/deliverables/00-control/document-control-manual.md",
        "anchor": "doc-03",
        "generated": [],
        "status": "DRAFT",
        "verification": "STRUCTURE_CHECKED",
    },
    "DOC-04": {
        "path": "docs/deliverables/00-control/document-control-manual.md",
        "anchor": "doc-04",
        "generated": [],
        "status": "DRAFT",
        "verification": "STRUCTURE_CHECKED",
    },
    "DOC-05": {
        "path": "docs/deliverables/00-control/artifact-change-log.json",
        "anchor": None,
        "generated": [],
        "status": "DRAFT",
        "verification": "GENERATED_AND_CHECKED",
    },
}


class ControlBootstrapError(ValueError):
    """Raised when source controls or generated outputs are inconsistent."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ControlBootstrapError(message)


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        _require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ControlBootstrapError(f"non-standard JSON number: {value}")


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
        raise ControlBootstrapError(f"invalid JSON: {path}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _relative(path: Path) -> str:
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _object_sha256(value: Any) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _text_sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def _validate_policy_manifest(manifest: dict[str, Any]) -> None:
    metadata = manifest.get("metadata", {})
    boundary = manifest.get("establishment_boundary", {})
    next_steps = manifest.get("authorized_next_steps", {})
    _require(
        metadata.get("baseline_id") == "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
        "unexpected policy baseline id",
    )
    _require(metadata.get("lifecycle_status") == "BASELINED", "policy is not baselined")
    _require(boundary.get("content_approval_status") == "APPROVED", "policy is not approved")
    _require(boundary.get("baseline_status") == "BASELINED", "baseline is not established")
    _require(boundary.get("baseline_approval_recorded") is True, "baseline approval is absent")
    _require(
        next_steps.get("formal_deliverables_0_to_6") == "AUTHORIZED_TO_START",
        "0~6 deliverables are not authorized",
    )
    _require(boundary.get("formal_deliverables_authorized") is True, "authorization differs")
    _require(boundary.get("formal_deliverable_generation_status") == "NOT_RUN", "source boundary differs")
    _require(boundary.get("implementation_completion_claimed") is False, "implementation is overstated")
    _require(boundary.get("test_completion_claimed") is False, "testing is overstated")
    _require(boundary.get("remaining_gates_are_waived") is False, "gate waiver is not allowed")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "release boundary changed")

    gates = manifest.get("remaining_gates")
    _require(isinstance(gates, list) and len(gates) == 5, "five remaining gates are required")
    _require({gate.get("id") for gate in gates} == EXPECTED_GATE_IDS, "gate ids differ")
    for gate in gates:
        _require(gate.get("status") == "NOT_RUN", f"gate must remain NOT_RUN: {gate.get('id')}")

    source_bindings = manifest.get("source_bindings")
    _require(isinstance(source_bindings, dict), "source_bindings must be an object")
    for key, binding in source_bindings.items():
        _require(isinstance(binding, dict), f"baseline item must be an object: {key}")
        path = REPO_ROOT / str(binding.get("path", ""))
        _require(path.is_file(), f"baseline item is missing: {key}")
        _require(_sha256(path) == binding.get("sha256"), f"baseline item hash mismatch: {key}")


def _authorization_boundary(manifest: dict[str, Any]) -> dict[str, Any]:
    boundary = manifest["establishment_boundary"]
    next_steps = manifest["authorized_next_steps"]
    return {
        "policy_content_approval_status": boundary["content_approval_status"],
        "policy_baseline_status": boundary["baseline_status"],
        "formal_deliverables_0_to_6": next_steps["formal_deliverables_0_to_6"],
        "formal_deliverables_completed": False,
        "full_257_type_registration_status": "DRAFT_REGISTERED",
        "formal_deliverables_7_to_12": "DRAFTS_GENERATED_WITH_EXECUTION_BOUNDARY",
        "control_bootstrap_status": "DRAFT",
        "fp035_correction_candidate_status": "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL",
        "fp035_correction_effective_now": False,
        "content_readiness_audit_status": "COMPLETED_PENDING_INDEPENDENT_REVIEW",
        "content_approval_candidate_count": 129,
        "approval_or_baseline_state_changed_by_this_generation": False,
        "implementation_completion_claimed": boundary["implementation_completion_claimed"],
        "test_completion_claimed": boundary["test_completion_claimed"],
        "remaining_gates_waived": boundary["remaining_gates_are_waived"],
        "release_status": boundary["release_status"],
        "policy_manifest_generation_status_at_establishment": boundary[
            "formal_deliverable_generation_status"
        ],
    }


def _remaining_gates(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    return [{**gate, "waived": False} for gate in manifest["remaining_gates"]]


def _select_and_validate_catalog(catalog: dict[str, Any]) -> list[dict[str, Any]]:
    items = catalog.get("artifact_types")
    _require(isinstance(items, list), "artifact_types must be an array")
    selected = [item for item in items if item.get("category") in CATEGORIES]
    selected.sort(key=lambda item: item.get("sequence_hint", 0))
    _require(len(selected) == 257, "full catalog must contain exactly 257 types")

    display_codes = [item.get("display_code") for item in selected]
    type_codes = [item.get("type_code") for item in selected]
    _require(len(set(display_codes)) == 257, "display codes must be unique")
    _require(len(set(type_codes)) == 257, "type codes must be unique")
    _require(
        Counter(item.get("category") for item in selected) == Counter(EXPECTED_CATEGORY_COUNTS),
        "category counts differ from the DOC~CLS scope",
    )
    applicability = Counter(item.get("default_applicability") for item in selected)
    _require(
        applicability
        == {
            "REQUIRED": EXPECTED_REQUIRED_COUNT,
            "CONDITIONAL": EXPECTED_CONDITIONAL_COUNT,
        },
        "applicability counts differ",
    )
    _require(
        {item.get("recommended_bundle_id") for item in selected} == set(BUNDLE_PATHS),
        "40-bundle coverage differs",
    )
    for item in selected:
        for field in (
            "type_code",
            "display_code",
            "title",
            "category",
            "purpose",
            "recommended_form",
            "owner_role",
            "reviewer_roles",
            "approver_role",
            "required_contents",
            "required_inputs",
            "completion_criteria",
            "update_triggers",
        ):
            _require(item.get(field), f"catalog field is empty: {item.get('display_code')}.{field}")
    return selected


def _validate_human_sources() -> None:
    plan = PLAN_PATH.read_text(encoding="utf-8")
    manual = MANUAL_PATH.read_text(encoding="utf-8")
    _require("TST-01~23" in plan, "TST-23 is missing from the authoring plan")
    _require("Android 사용자 앱·비공개 Android 관리자 앱 주제품" in plan, "platform scope is stale")
    for stale in ("Web/PWA 주제품", "첫 검증 가능한 Web/PWA 서비스", "TST-01~22"):
        _require(stale not in plan, f"stale authoring-plan wording remains: {stale}")
    for anchor in ('id="doc-02"', 'id="doc-03"', 'id="doc-04"'):
        _require(anchor in manual, f"manual coverage anchor is missing: {anchor}")
    _require("생명주기 상태 | Draft" in manual, "manual must remain Draft")
    _require(f"버전 | {CONTROL_DOCUMENT_VERSION}" in manual, "manual version differs")
    _require("전체 257개 산출물 유형" in manual, "manual scope is stale")
    _require("7~12 범주는 작성 가능한" in manual, "manual 7~12 authoring boundary differs")
    _require("`Planned/NOT_RUN`" in manual, "manual execution-evidence boundary differs")
    _require("필수 148개와 조건부 109개" in manual, "manual applicability counts differ")
    _require("40개 bundle" in manual, "manual bundle count differs")
    for stale in ("필수 114개와 조건부 14개", "21개 bundle이"):
        _require(stale not in manual, f"stale manual wording remains: {stale}")
    _require(
        "서명 원본과 불필요한 개인정보·위치·영상·음성은 Git 저장소에 넣지 않는다" in manual,
        "manual raw personal-data storage rule is missing",
    )
    _require("출시 상태: `NOT_ELIGIBLE`" in manual, "manual release boundary differs")


def _source_bindings() -> list[dict[str, str]]:
    paths = (
        ("artifact_type_catalog", CATALOG_PATH),
        ("authoring_plan", PLAN_PATH),
        ("document_control_manual", MANUAL_PATH),
        ("policy_approval", APPROVAL_PATH),
        ("policy_baseline_manifest", POLICY_MANIFEST_PATH),
        ("approved_policy_payload", POLICY_PAYLOAD_PATH),
        ("aligned_effective_decision_register", ALIGNED_DECISION_REGISTER_PATH),
        ("req_des_tst_trace_integration_report", TRACE_INTEGRATION_REPORT_PATH),
        ("req_des_tst_trace_integration_summary", TRACE_INTEGRATION_SUMMARY_PATH),
        ("formal_7_to_12_trace_integration_report", FORMAL_TRACE_7_TO_12_REPORT_PATH),
        ("formal_7_to_12_trace_integration_summary", FORMAL_TRACE_7_TO_12_SUMMARY_PATH),
        ("requirements_rtm", RTM_PATH),
        ("design_traceability_register", DESIGN_TRACE_PATH),
        ("test_case_register", TEST_CASES_PATH),
        ("module_register", MODULE_REGISTER_PATH),
        ("management_raid_register", RAID_REGISTER_PATH),
        ("management_change_request_register", CHANGE_REQUEST_REGISTER_PATH),
        ("test_residual_risk_register", RESIDUAL_RISK_REGISTER_PATH),
        ("fp035_correction_candidate", FP035_CORRECTION_PATH),
        ("artifact_authoring_goal_intake", AUTHORING_GOAL_INTAKE_PATH),
        ("artifact_content_readiness_audit", READINESS_AUDIT_PATH),
        ("generator", GENERATOR_PATH),
    )
    return [
        {"name": name, "path": _relative(path), "sha256": _sha256(path)}
        for name, path in paths
    ]


def _readiness_index() -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    audit = load_strict_json(READINESS_AUDIT_PATH)
    _require(
        audit.get("audit_content_sha256")
        == _object_sha256(
            {key: value for key, value in audit.items() if key != "audit_content_sha256"}
        ),
        "content readiness audit hash differs",
    )
    _require(
        audit.get("metadata", {}).get("approval_status") == "NOT_APPROVED",
        "content readiness audit must not claim approval",
    )
    assessments = audit.get("artifact_assessments", [])
    _require(len(assessments) == 257, "content readiness assessment count differs")
    by_code = {row["display_code"]: row for row in assessments}
    _require(len(by_code) == 257, "content readiness codes are duplicated")
    _require(
        sum(bool(row.get("approval_proposed")) for row in assessments) == 129,
        "content readiness approval candidate count differs",
    )
    _require(
        {gate["id"] for gate in audit.get("remaining_gates", [])} == EXPECTED_GATE_IDS,
        "content readiness gate set differs",
    )
    _require(
        {gate["status"] for gate in audit.get("remaining_gates", [])} == {"NOT_RUN"},
        "content readiness gate status differs",
    )
    return by_code, audit


def _safe_deliverable_file(raw_path: Any, label: str) -> Path:
    _require(isinstance(raw_path, str) and bool(raw_path), f"{label} path is invalid")
    relative = Path(raw_path)
    _require(not relative.is_absolute() and ".." not in relative.parts, f"{label} path escapes the repo")
    path = (REPO_ROOT / relative).resolve()
    try:
        path.relative_to(DELIVERABLE_ROOT.resolve())
    except ValueError as exc:
        raise ControlBootstrapError(f"{label} is outside docs/deliverables: {raw_path}") from exc
    _require(path.is_file(), f"{label} is missing: {raw_path}")
    return path


def _declared_artifact_codes(path: Path) -> set[str]:
    if path.suffix.lower() == ".json":
        payload = load_strict_json(path)
        candidates: list[Any] = []
        metadata = payload.get("metadata")
        if isinstance(metadata, dict):
            candidates.append(metadata.get("artifact_type_ids"))
            candidates.append(metadata.get("artifact_type_codes"))
        candidates.append(payload.get("artifact_type_ids"))
        candidates.append(payload.get("artifact_type_codes"))
        result: set[str] = set()
        for candidate in candidates:
            if isinstance(candidate, list):
                result.update(
                    value for value in candidate if isinstance(value, str) and ARTIFACT_CODE_PATTERN.fullmatch(value)
                )
        return result
    text = path.read_text(encoding="utf-8")
    header = "\n".join(text.splitlines()[:16])
    return set(ARTIFACT_CODE_PATTERN.findall(header))


def _draft_file_metadata(path: Path, code: str) -> dict[str, Any]:
    lifecycle = "DRAFT"
    version: str | None = None
    anchor: str | None = None
    declared_codes = _declared_artifact_codes(path)
    if path.suffix.lower() == ".json":
        payload = load_strict_json(path)
        metadata = payload.get("metadata") if isinstance(payload.get("metadata"), dict) else {}
        raw_lifecycle = str(metadata.get("lifecycle_status", "")).upper().replace(" ", "_")
        if raw_lifecycle in SAFE_DRAFT_LIFECYCLES:
            lifecycle = raw_lifecycle
        raw_version = metadata.get("document_version", metadata.get("version"))
        if isinstance(raw_version, str) and raw_version:
            version = raw_version
        for key in ("artifact_type_ids", "artifact_type_codes"):
            values = metadata.get(key)
            if isinstance(values, list) and code in values:
                anchor = f"/metadata/{key}/{values.index(code)}"
                break
        if anchor is None:
            for key in ("artifact_type_ids", "artifact_type_codes"):
                values = payload.get(key)
                if isinstance(values, list) and code in values:
                    anchor = f"/{key}/{values.index(code)}"
                    break
    else:
        text = path.read_text(encoding="utf-8")
        status_match = re.search(r"상태:\s*(Draft|In[ _]Review)", text[:2500], re.IGNORECASE)
        if status_match:
            lifecycle = status_match.group(1).upper().replace(" ", "_")
        version_match = re.search(r"버전:\s*([0-9]+\.[0-9]+\.[0-9]+)", text[:2500])
        if version_match:
            version = version_match.group(1)
        expected_anchor = code.lower()
        if re.search(rf'<a\s+id=["\']{re.escape(expected_anchor)}["\']\s*>', text, re.IGNORECASE):
            anchor = expected_anchor
        elif re.search(rf"^#{{1,6}}\s+.*\b{re.escape(code)}\b", text, re.MULTILINE):
            anchor = expected_anchor
    if anchor is None and code in declared_codes:
        anchor = f"document-scope:{code.lower()}"
    return {
        "lifecycle_status": lifecycle,
        "document_version": version,
        "coverage_anchor": anchor,
        "declared_artifact_codes": sorted(declared_codes),
    }


def _validate_draft_manifest(path: Path, expected_codes: set[str]) -> dict[str, Any]:
    manifest = load_strict_json(path)
    metadata = manifest.get("metadata")
    _require(isinstance(metadata, dict), f"draft manifest metadata is missing: {_relative(path)}")
    lifecycle = str(metadata.get("lifecycle_status", "")).upper().replace(" ", "_")
    _require(lifecycle in SAFE_DRAFT_LIFECYCLES, f"draft manifest lifecycle is unsafe: {_relative(path)}")
    raw_version = metadata.get("document_version", metadata.get("version"))
    document_version = raw_version if isinstance(raw_version, str) and raw_version else None
    if "approval_status" in metadata:
        _require(metadata["approval_status"] == "NOT_APPROVED", f"draft manifest claims approval: {_relative(path)}")
    if "release_status" in metadata:
        _require(metadata["release_status"] == "NOT_ELIGIBLE", f"draft manifest release differs: {_relative(path)}")
    boundary = manifest.get("authorization_boundary")
    if isinstance(boundary, dict):
        _require(boundary.get("formal_deliverables_approved") is not True, "draft manifest claims formal approval")
        _require(boundary.get("implementation_completion_claimed") is not True, "draft manifest claims implementation completion")
        _require(boundary.get("test_completion_claimed") is not True, "draft manifest claims test completion")
        _require(boundary.get("remaining_gates_waived") is not True, "draft manifest waives gates")
        _require(boundary.get("release_status") == "NOT_ELIGIBLE", "draft manifest release boundary differs")
    gates = manifest.get("remaining_gates")
    if gates is not None:
        _require(isinstance(gates, list) and len(gates) == 5, "draft manifest must preserve five gates")
        _require({gate.get("id") for gate in gates} == EXPECTED_GATE_IDS, "draft manifest gate ids differ")
        _require(all(gate.get("status") == "NOT_RUN" for gate in gates), "draft manifest closes a gate")
        _require(all(gate.get("waived") is False for gate in gates), "draft manifest waives a gate")
    content_digest = manifest.get("manifest_content_sha256")
    if content_digest is not None:
        without_digest = dict(manifest)
        without_digest.pop("manifest_content_sha256")
        _require(_object_sha256(without_digest) == content_digest, f"draft manifest digest differs: {_relative(path)}")
    source_bindings = manifest.get("source_bindings")
    if isinstance(source_bindings, dict):
        for name, binding in source_bindings.items():
            _require(isinstance(binding, dict), f"draft source binding is invalid: {name}")
            source_path = REPO_ROOT / str(binding.get("path", ""))
            _require(source_path.is_file(), f"draft source binding is missing: {name}")
            _require(_sha256(source_path) == binding.get("sha256"), f"draft source hash differs: {name}")
    generated_files = manifest.get("generated_files")
    _require(isinstance(generated_files, list), f"draft manifest generated_files is missing: {_relative(path)}")
    normalized_files = []
    for index, entry in enumerate(generated_files):
        _require(isinstance(entry, dict), f"generated_files[{index}] is invalid")
        generated_path = _safe_deliverable_file(entry.get("path"), f"generated_files[{index}]")
        _require(_sha256(generated_path) == entry.get("sha256"), f"generated file hash differs: {_relative(generated_path)}")
        if "byte_length" in entry:
            _require(generated_path.stat().st_size == entry["byte_length"], f"generated file size differs: {_relative(generated_path)}")
        explicit_codes = entry.get("artifact_type_ids", [])
        _require(isinstance(explicit_codes, list), f"generated artifact_type_ids is invalid: {_relative(generated_path)}")
        _require(all(code in expected_codes for code in explicit_codes), f"generated file has out-of-scope type: {_relative(generated_path)}")
        declared_codes = _declared_artifact_codes(generated_path) & expected_codes
        normalized_files.append(
            {
                "path": _relative(generated_path),
                "sha256": entry["sha256"],
                "byte_length": generated_path.stat().st_size,
                "explicit_artifact_type_codes": sorted(set(explicit_codes)),
                "declared_artifact_type_codes": sorted(declared_codes),
            }
        )
    return {
        "manifest_path": _relative(path),
        "manifest_sha256": _sha256(path),
        "lifecycle_status": lifecycle,
        "document_version": document_version,
        "generated_by": metadata.get("generated_by"),
        "source_bindings": source_bindings if isinstance(source_bindings, dict) else {},
        "declared_artifact_type_codes": sorted(
            set(metadata.get("artifact_type_ids", [])) & expected_codes
            if isinstance(metadata.get("artifact_type_ids"), list)
            else set()
        ),
        "generated_files": normalized_files,
    }


def discover_draft_sources(items: list[dict[str, Any]]) -> dict[str, Any]:
    expected_codes = {item["display_code"] for item in items}
    by_code: dict[str, dict[str, Any]] = {
        code: {
            "manifest_paths": set(),
            "primary_manifest_paths": set(),
            "supporting_paths": set(),
            "generator_candidates": [],
            "manifest_versions": set(),
        }
        for code in expected_codes
    }
    manifest_bindings: list[dict[str, Any]] = []
    file_bindings: dict[str, dict[str, Any]] = {}
    explicit_claims: dict[str, set[str]] = {}

    manifest_paths = sorted(DRAFT_MANIFEST_DIR.glob("*.json")) if DRAFT_MANIFEST_DIR.is_dir() else []
    for manifest_path in manifest_paths:
        normalized = _validate_draft_manifest(manifest_path, expected_codes)
        manifest_bindings.append(
            {
                "path": normalized["manifest_path"],
                "sha256": normalized["manifest_sha256"],
                "lifecycle_status": normalized["lifecycle_status"],
                "generated_by": normalized["generated_by"],
            }
        )
        manifest_codes = set(normalized["declared_artifact_type_codes"])
        for code in manifest_codes:
            by_code[code]["manifest_paths"].add(normalized["manifest_path"])
            if normalized["document_version"]:
                by_code[code]["manifest_versions"].add(normalized["document_version"])
        generator_path = normalized["generated_by"]
        generator_sha = None
        generator_binding = normalized["source_bindings"].get("generator")
        if isinstance(generator_binding, dict):
            generator_path = generator_binding.get("path", generator_path)
            generator_sha = generator_binding.get("sha256")
        for generated in normalized["generated_files"]:
            path_text = generated["path"]
            if path_text == _relative(ROOT_README_PATH) or path_text.startswith("docs/deliverables/00-control/"):
                continue
            file_bindings[path_text] = generated
            explicit = set(generated["explicit_artifact_type_codes"])
            declared = set(generated["declared_artifact_type_codes"])
            linked_codes = explicit or declared
            explicit_claims.setdefault(path_text, set()).update(explicit)
            for code in linked_codes:
                by_code[code]["manifest_paths"].add(normalized["manifest_path"])
                by_code[code]["supporting_paths"].add(path_text)
                if normalized["document_version"]:
                    by_code[code]["manifest_versions"].add(normalized["document_version"])
                if code in explicit:
                    by_code[code]["primary_manifest_paths"].add(normalized["manifest_path"])
                    by_code[code]["generator_candidates"].append(
                        {
                            "path": generator_path,
                            "sha256": generator_sha,
                            "manifest_path": normalized["manifest_path"],
                            "generated_file_path": path_text,
                        }
                    )

    primary_files: dict[str, dict[str, Any]] = {}
    for item in items:
        code = item["display_code"]
        if code.startswith("DOC-"):
            continue
        planned_path_text = BUNDLE_PATHS[item["recommended_bundle_id"]]
        planned_path = REPO_ROOT / planned_path_text
        if not planned_path.is_file():
            continue
        metadata = _draft_file_metadata(planned_path, code)
        claimed = code in explicit_claims.get(planned_path_text, set())
        declared = code in metadata["declared_artifact_codes"]
        if not claimed and not declared:
            continue
        _require(
            metadata["coverage_anchor"] is not None,
            f"draft canonical coverage anchor is missing: {planned_path_text} -> {code}",
        )
        binding = file_bindings.get(planned_path_text)
        if binding is not None:
            _require(binding["sha256"] == _sha256(planned_path), f"draft canonical hash differs: {planned_path_text}")
        else:
            binding = {
                "path": planned_path_text,
                "sha256": _sha256(planned_path),
                "byte_length": planned_path.stat().st_size,
                "explicit_artifact_type_codes": [],
                "declared_artifact_type_codes": metadata["declared_artifact_codes"],
            }
            file_bindings[planned_path_text] = binding
        candidates = [
            candidate
            for candidate in by_code[code]["generator_candidates"]
            if candidate["generated_file_path"] == planned_path_text
        ]
        generator = candidates[0] if len(candidates) == 1 else None
        primary_files[code] = {
            "path": planned_path_text,
            "sha256": binding["sha256"],
            "byte_length": binding["byte_length"],
            "coverage_anchor": metadata["coverage_anchor"],
            "lifecycle_status": metadata["lifecycle_status"],
            "document_version": metadata["document_version"] or (
                next(iter(by_code[code]["manifest_versions"]))
                if len(by_code[code]["manifest_versions"]) == 1
                else None
            ),
            "generator": generator,
        }
        by_code[code]["supporting_paths"].discard(planned_path_text)

    serializable_by_code = {}
    for code, values in by_code.items():
        serializable_by_code[code] = {
            "manifest_paths": sorted(values["manifest_paths"]),
            "primary_manifest_paths": sorted(values["primary_manifest_paths"]),
            "supporting_paths": sorted(values["supporting_paths"]),
        }
    return {
        "manifest_bindings": manifest_bindings,
        "file_bindings": [file_bindings[path] for path in sorted(file_bindings)],
        "primary_files": primary_files,
        "by_code": serializable_by_code,
    }


def _unique_record_index(
    payload: dict[str, Any], list_key: str, id_key: str, label: str
) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    records = payload.get(list_key)
    _require(isinstance(records, list) and records, f"{label} rows are missing")
    _require(all(isinstance(record, dict) for record in records), f"{label} row is invalid")
    identifiers = [record.get(id_key) for record in records]
    _require(
        all(isinstance(identifier, str) and identifier for identifier in identifiers),
        f"{label} ID is invalid",
    )
    _require(len(identifiers) == len(set(identifiers)), f"{label} IDs are not unique")
    return records, {record[id_key]: record for record in records}


def _validate_trace_report_source(
    report: dict[str, Any], expected_paths: dict[str, Path]
) -> None:
    _require(
        report.get("schema_version")
        == "walksafe.req-des-tst-trace-integration-report.v1",
        "trace integration report schema differs",
    )
    metadata = report.get("metadata", {})
    _require(
        metadata.get("structural_validation_status")
        == "STRUCTURAL_TRACE_VALIDATION_PASS",
        "trace integration report has not passed structural validation",
    )
    _require(metadata.get("approval_status") == "NOT_APPROVED", "trace report claims approval")
    _require(metadata.get("release_status") == "NOT_ELIGIBLE", "trace report permits release")
    bindings = report.get("source_bindings")
    _require(isinstance(bindings, dict), "trace report source bindings are missing")
    for name, path in expected_paths.items():
        binding = bindings.get(name)
        _require(isinstance(binding, dict), f"trace report source binding is missing: {name}")
        _require(binding.get("path") == _relative(path), f"trace report source path differs: {name}")
        _require(binding.get("sha256") == _sha256(path), f"trace report source hash differs: {name}")
    for binding in bindings.values():
        if not isinstance(binding, dict):
            continue
        bound_path = binding.get("path")
        _require(
            bound_path
            not in {_relative(REGISTER_PATH), _relative(CHANGE_LOG_PATH)},
            "trace report must not bind a generated control register",
        )
    digest = report.get("report_content_sha256")
    _require(isinstance(digest, str), "trace report content digest is missing")
    without_digest = dict(report)
    without_digest.pop("report_content_sha256")
    _require(_object_sha256(without_digest) == digest, "trace report content digest differs")


def _load_trace_index_sources() -> dict[str, Any]:
    rtm = load_strict_json(RTM_PATH)
    design_trace = load_strict_json(DESIGN_TRACE_PATH)
    test_register = load_strict_json(TEST_CASES_PATH)
    module_register = load_strict_json(MODULE_REGISTER_PATH)
    raid_register = load_strict_json(RAID_REGISTER_PATH)
    change_register = load_strict_json(CHANGE_REQUEST_REGISTER_PATH)
    residual_register = load_strict_json(RESIDUAL_RISK_REGISTER_PATH)
    report = load_strict_json(TRACE_INTEGRATION_REPORT_PATH)

    _require(
        rtm.get("schema_version") == "walksafe.requirements-traceability-draft.v1",
        "RTM schema differs for the artifact trace index",
    )
    requirements, requirements_by_id = _unique_record_index(
        rtm, "requirements", "requirement_id", "requirement"
    )
    _require(len(requirements) == 68, "artifact trace index requires 68 requirements")

    _require(
        design_trace.get("schema_version") == "walksafe.design-traceability-register.v1",
        "design trace schema differs for the artifact trace index",
    )
    designs, designs_by_id = _unique_record_index(
        design_trace, "records", "design_id", "design"
    )
    _require(
        set(designs_by_id) == {f"DES-{number:02d}" for number in range(1, 28)},
        "artifact trace index requires DES-01~27",
    )
    for design in designs:
        refs = design.get("planned_specific_requirement_refs")
        _require(
            isinstance(refs, list) and set(refs) <= set(requirements_by_id),
            f"design requirement references differ: {design['design_id']}",
        )

    _require(
        test_register.get("schema_version") == "walksafe.test-case-register.v1",
        "test-case schema differs for the artifact trace index",
    )
    tests, tests_by_id = _unique_record_index(
        test_register, "test_cases", "test_case_id", "test case"
    )
    _require(len(tests) == 279, "artifact trace index requires 279 test cases")
    planned_test_ids = {
        acceptance["planned_test_id"]
        for requirement in requirements
        for acceptance in requirement.get("acceptance_conditions", [])
    }
    _require(set(tests_by_id) == planned_test_ids, "RTM acceptance and test-case IDs differ")
    for case in tests:
        _require(
            case.get("requirement_id") in requirements_by_id,
            f"test requirement is unknown: {case['test_case_id']}",
        )
        planned_types = case.get("planned_test_type_ids")
        _require(isinstance(planned_types, list), f"planned test types are missing: {case['test_case_id']}")

    _require(
        module_register.get("schema_version") == "walksafe.dev-module-register.v1",
        "module-register schema differs for the artifact trace index",
    )
    modules, modules_by_id = _unique_record_index(
        module_register, "modules", "module_id", "module"
    )
    for module in modules:
        _require(
            set(module.get("requirement_refs", [])) <= set(requirements_by_id),
            f"module requirement references differ: {module['module_id']}",
        )
        _require(
            set(module.get("design_refs", [])) <= set(designs_by_id),
            f"module design references differ: {module['module_id']}",
        )

    _require(
        raid_register.get("schema_version") == "walksafe.raid-register.v1",
        "RAID schema differs for the artifact trace index",
    )
    raids, raids_by_id = _unique_record_index(raid_register, "rows", "raid_id", "RAID")
    _require("RAID-011" in raids_by_id, "FP-035 RAID issue is missing")

    _require(
        change_register.get("schema_version") == "walksafe.change-request-register.v1",
        "change-request schema differs for the artifact trace index",
    )
    changes, changes_by_id = _unique_record_index(
        change_register, "requests", "change_request_id", "change request"
    )
    _require(FP035_CHANGE_REQUEST_ID in changes_by_id, "FP-035 change request is missing")

    _require(
        residual_register.get("schema_version") == "walksafe.residual-risk-register.v1",
        "residual-risk schema differs for the artifact trace index",
    )
    risks, risks_by_id = _unique_record_index(
        residual_register, "risks", "risk_id", "residual risk"
    )
    _require(FP035_RESIDUAL_RISK_ID in risks_by_id, "FP-035 residual risk is missing")

    _validate_trace_report_source(
        report,
        {
            "requirements_rtm": RTM_PATH,
            "design_traceability_register": DESIGN_TRACE_PATH,
            "test_cases": TEST_CASES_PATH,
            "module_register": MODULE_REGISTER_PATH,
        },
    )
    return {
        "requirements": requirements,
        "requirements_by_id": requirements_by_id,
        "designs": designs,
        "designs_by_id": designs_by_id,
        "tests": tests,
        "tests_by_id": tests_by_id,
        "modules": modules,
        "modules_by_id": modules_by_id,
        "raids": raids,
        "raids_by_id": raids_by_id,
        "changes": changes,
        "changes_by_id": changes_by_id,
        "risks": risks,
        "risks_by_id": risks_by_id,
    }


def _build_artifact_trace_index(items: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    sources = _load_trace_index_sources()
    requirements = sources["requirements"]
    designs = sources["designs"]
    tests = sources["tests"]
    modules = sources["modules"]
    all_requirement_ids = sorted(sources["requirements_by_id"])
    all_test_ids = sorted(sources["tests_by_id"])

    link_fields = (
        "requirement_ids",
        "test_ids",
        "design_ids",
        "module_ids",
        "finding_or_defect_row_ids",
        "evidence_ids",
        "risk_ids",
        "change_request_ids",
    )
    index: dict[str, dict[str, Any]] = {
        item["display_code"]: {
            "direct_link_applicability": "NOT_APPLICABLE_TO_DIRECT_REQ_DES_TEST_INDEX",
            "empty_link_reason": "이 산출물 유형은 현재 요구·설계·시험 ID를 직접 관리하지 않고 문서 유형 간 선후관계와 정본 경로로 관리한다.",
            **{field: [] for field in link_fields},
        }
        for item in items
    }

    def set_links(code: str, **values: list[str]) -> None:
        _require(code in index, f"trace index artifact code is unknown: {code}")
        for field, identifiers in values.items():
            _require(field in link_fields, f"trace index field is unknown: {field}")
            index[code][field] = sorted(set(identifiers))
        index[code]["direct_link_applicability"] = "DIRECT_LINKS_INDEXED"
        index[code]["empty_link_reason"] = None

    full_requirement_codes = {
        *[f"REQ-{number:02d}" for number in range(1, 7)],
        *[f"REQ-{number:02d}" for number in range(16, 20)],
    }
    for code in sorted(full_requirement_codes):
        values: dict[str, list[str]] = {"requirement_ids": all_requirement_ids}
        if code == "REQ-06":
            values["test_ids"] = all_test_ids
        set_links(code, **values)

    type_code_by_display = {item["display_code"]: item["type_code"] for item in items}
    for number in range(7, 16):
        code = f"REQ-{number:02d}"
        type_code = type_code_by_display[code]
        requirement_ids = [
            requirement["requirement_id"]
            for requirement in requirements
            if type_code in requirement.get("domain_artifact_type_ids", [])
        ]
        _require(requirement_ids, f"domain requirement trace is empty: {code}")
        set_links(code, requirement_ids=requirement_ids)

    tests_by_requirement: dict[str, list[str]] = {}
    for case in tests:
        tests_by_requirement.setdefault(case["requirement_id"], []).append(case["test_case_id"])
    for design in designs:
        code = design["design_id"]
        requirement_ids = design["planned_specific_requirement_refs"]
        test_ids = [
            test_id
            for requirement_id in requirement_ids
            for test_id in tests_by_requirement.get(requirement_id, [])
        ]
        _require(requirement_ids and test_ids, f"design trace is empty: {code}")
        set_links(
            code,
            requirement_ids=requirement_ids,
            test_ids=test_ids,
            design_ids=[code],
        )

    module_ids = [module["module_id"] for module in modules]
    module_requirement_ids = [
        requirement_id for module in modules for requirement_id in module.get("requirement_refs", [])
    ]
    module_design_ids = [
        design_id for module in modules for design_id in module.get("design_refs", [])
    ]
    module_test_ids = [
        test_id
        for requirement_id in sorted(set(module_requirement_ids))
        for test_id in tests_by_requirement.get(requirement_id, [])
    ]
    for code in ("DEV-01", "DEV-18", "DEV-21"):
        set_links(
            code,
            requirement_ids=module_requirement_ids,
            test_ids=module_test_ids,
            design_ids=module_design_ids,
            module_ids=module_ids,
        )

    for code in (*[f"TST-{number:02d}" for number in range(1, 6)], *[f"TST-{number:02d}" for number in range(18, 24)]):
        set_links(code, requirement_ids=all_requirement_ids, test_ids=all_test_ids)
    for number in range(6, 18):
        code = f"TST-{number:02d}"
        selected_tests = [
            case for case in tests if code in case.get("planned_test_type_ids", [])
        ]
        if selected_tests:
            set_links(
                code,
                requirement_ids=[case["requirement_id"] for case in selected_tests],
                test_ids=[case["test_case_id"] for case in selected_tests],
            )
        else:
            index[code]["direct_link_applicability"] = (
                "APPLICABLE_NO_CURRENT_PLANNED_TEST_CASES"
            )
            index[code]["empty_link_reason"] = (
                "시험 유형은 적용 대상이지만 현재 279개 계획 시험 중 이 유형으로 분류된 케이스가 없다. 시험계획 검토 때 추가 여부를 결정한다."
            )

    set_links("MGT-14", risk_ids=[row["raid_id"] for row in sources["raids"]])
    set_links(
        "MGT-16",
        change_request_ids=[row["change_request_id"] for row in sources["changes"]],
    )
    index["TST-21"]["risk_ids"] = sorted(sources["risks_by_id"])

    for trace in index.values():
        if FP035_REQUIREMENT_ID in trace["requirement_ids"]:
            trace["change_request_ids"] = sorted(
                {*trace["change_request_ids"], FP035_CHANGE_REQUEST_ID}
            )
            trace["risk_ids"] = sorted(
                {*trace["risk_ids"], FP035_RESIDUAL_RISK_ID}
            )
        trace["trace_validation_report_path"] = _relative(TRACE_INTEGRATION_REPORT_PATH)
        trace["empty_field_reasons"] = {
            field: (
                "이 산출물과 직접 연결된 현재 Draft ID가 없다. 형식 간 선후관계는 upstream_types·downstream_types에서 별도로 확인한다."
                if field not in {"finding_or_defect_row_ids", "evidence_ids"}
                else "정식 시험 실행이 0건이므로 아직 연결할 결함·판정 또는 실행 증거 ID가 없다."
            )
            for field in link_fields
            if not trace[field]
        }

    valid_ids = {
        "requirement_ids": set(sources["requirements_by_id"]),
        "test_ids": set(sources["tests_by_id"]),
        "design_ids": set(sources["designs_by_id"]),
        "module_ids": set(sources["modules_by_id"]),
        "risk_ids": {*sources["raids_by_id"], *sources["risks_by_id"]},
        "change_request_ids": set(sources["changes_by_id"]),
    }
    for code, trace in index.items():
        for field, allowed in valid_ids.items():
            _require(set(trace[field]) <= allowed, f"unknown {field} in artifact trace: {code}")
        _require(
            trace["empty_link_reason"] is not None
            or any(trace[field] for field in link_fields),
            f"artifact trace has neither links nor an empty reason: {code}",
        )
    return index


def _instance_id(display_code: str) -> str:
    return f"ART-{display_code}-001"


def _bundle_coverage(
    items: list[dict[str, Any]], artifacts: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    coverage = []
    for bundle_id, planned_path in BUNDLE_PATHS.items():
        bundle_items = [item for item in items if item["recommended_bundle_id"] == bundle_id]
        codes = [item["display_code"] for item in bundle_items]
        bundle_rows = [row for row in artifacts if row["bundle_id"] == bundle_id]
        materialized_rows = [
            row for row in bundle_rows if row["state"]["lifecycle_status"] in SAFE_DRAFT_LIFECYCLES
        ]
        canonical_paths = sorted(
            {row["location"]["canonical_path"] for row in materialized_rows if row["location"]["canonical_path"]}
        )
        if any(row["state"]["lifecycle_status"] == "IN_REVIEW" for row in materialized_rows):
            lifecycle = "IN_REVIEW"
        elif materialized_rows:
            lifecycle = "DRAFT"
        else:
            lifecycle = "PLANNED"
        if not materialized_rows:
            draft_coverage = "NOT_STARTED"
        elif len(materialized_rows) == len(bundle_rows):
            draft_coverage = "COMPLETE_DRAFT_COVERAGE"
        else:
            draft_coverage = "PARTIAL_DRAFT_COVERAGE"
        coverage.append(
            {
                "bundle_id": bundle_id,
                "category": bundle_items[0]["category"],
                "planned_canonical_path": planned_path,
                "additional_canonical_paths": BUNDLE_ADDITIONAL_PATHS.get(bundle_id, []),
                "current_canonical_paths": canonical_paths,
                "artifact_type_codes": codes,
                "type_count": len(codes),
                "required_count": sum(
                    item["default_applicability"] == "REQUIRED" for item in bundle_items
                ),
                "conditional_count": sum(
                    item["default_applicability"] == "CONDITIONAL" for item in bundle_items
                ),
                "materialized_type_count": len(materialized_rows),
                "lifecycle_status": lifecycle,
                "coverage_status": "REGISTERED_ONCE",
                "draft_coverage_status": draft_coverage,
            }
        )
    return coverage


def _review_profile_id(item: dict[str, Any]) -> str:
    category = item["category"]
    artifact_form = item["recommended_form"]
    if category == "REL":
        return "PER_RELEASE"
    if category == "AIML":
        return "PER_DATASET_OR_MODEL_VERSION"
    if category == "WS":
        return "PER_BUILD_OR_FIELD_CYCLE"
    if category == "OPS":
        return "AT_OPERATION_EVENT_AND_MONTHLY"
    if category == "CLS":
        return "AT_CLOSURE_EVENT"
    if artifact_form == "REGISTER":
        return "WEEKLY_REGISTER"
    if artifact_form == "GENERATED_EVIDENCE":
        return "PER_GENERATION"
    if artifact_form == "EXTERNAL_RECORD":
        return "ON_RECEIPT_OR_EXPIRY"
    if artifact_form == "CONTROLLED_ARTIFACT":
        return "ON_CONTROLLED_CHANGE"
    return "MONTHLY_CONTROL"


def _change_profile_id(artifact_form: str) -> str:
    if artifact_form == "REGISTER":
        return "APPEND_ONLY_REGISTER"
    if artifact_form == "CONTROLLED_ARTIFACT":
        return "CONTROLLED_SOURCE"
    if artifact_form == "GENERATED_EVIDENCE":
        return "REGENERATED_EVIDENCE"
    if artifact_form == "EXTERNAL_RECORD":
        return "EXTERNAL_ORIGINAL"
    return "VERSIONED_DOCUMENT"


def _retention_profile_id(display_code: str, artifact_form: str) -> str:
    if display_code in SENSITIVE_PERSONAL_DATA_BY_CODE:
        return "DATA_POLICY_CONTROLLED_NO_RAW_DATA_IN_GIT"
    if artifact_form in {"GENERATED_EVIDENCE", "EXTERNAL_RECORD"}:
        return "EVIDENCE_PROJECT_LIFECYCLE_PLUS_3_YEARS"
    if artifact_form == "CONTROLLED_ARTIFACT":
        return "RELEASE_AND_PROJECT_LIFECYCLE_PLUS_3_YEARS"
    return "PROJECT_LIFECYCLE_PLUS_3_YEARS"


def _management_contract(item: dict[str, Any]) -> dict[str, Any]:
    review_profile_id = _review_profile_id(item)
    change_profile_id = _change_profile_id(item["recommended_form"])
    return {
        "review_profile_id": review_profile_id,
        "review_cadence": REVIEW_PROFILES[review_profile_id],
        "next_review_rule": (
            "최초 계획행은 통제대장 다음 검토일에 적용성을 확인하고, 이후에는 "
            "review_profile과 update_triggers 중 먼저 도래하는 시점에 검토한다."
        ),
        "change_profile_id": change_profile_id,
        "change_supersede_retire_method": CHANGE_PROFILES[change_profile_id],
        "retention_profile_id": _retention_profile_id(
            item["display_code"], item["recommended_form"]
        ),
        "control_policy_refs": ["DOC-02", "DOC-03", "DOC-04", "DOC-05"],
    }


def _approved_input_trace_by_code(
    items: list[dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    register = load_strict_json(ALIGNED_DECISION_REGISTER_PATH)
    policy = load_strict_json(POLICY_PAYLOAD_PATH)
    _require(
        register.get("schema_version")
        == "walksafe.effective-decision-register-aligned.v1",
        "aligned decision register schema differs",
    )
    decisions = register.get("decisions")
    _require(isinstance(decisions, list) and len(decisions) == 135, "135 decisions are required")
    known_codes = {item["display_code"] for item in items}
    result: dict[str, dict[str, set[str]]] = {
        code: {
            "decision_ids": set(),
            "canonical_decision_ids": set(),
            "feature_policy_ids": set(),
            "gate_ids": set(),
        }
        for code in known_codes
    }

    features = policy.get("features")
    _require(isinstance(features, list) and len(features) == 54, "54 policy features are required")
    known_feature_ids: set[str] = set()
    for feature in features:
        feature_id = feature.get("id")
        _require(
            isinstance(feature_id, str) and feature_id not in known_feature_ids,
            "policy feature IDs must be unique strings",
        )
        known_feature_ids.add(feature_id)
        traceability = feature.get("traceability")
        _require(isinstance(traceability, dict), f"policy traceability is missing: {feature_id}")
        affected_codes = traceability.get("affected_deliverables")
        _require(isinstance(affected_codes, list), f"policy deliverable links are missing: {feature_id}")
        for code in affected_codes:
            _require(code in known_codes, f"policy feature references unknown artifact type: {feature_id}/{code}")
            result[code]["feature_policy_ids"].add(feature_id)

    gate_ids_by_feature: dict[str, set[str]] = {feature_id: set() for feature_id in known_feature_ids}
    gates = policy.get("remaining_gates")
    _require(isinstance(gates, list) and len(gates) == 5, "five policy gates are required")
    _require({gate.get("id") for gate in gates} == EXPECTED_GATE_IDS, "policy gate IDs differ")
    for gate in gates:
        gate_id = gate["id"]
        affected_feature_ids = gate.get("affected_feature_ids")
        _require(isinstance(affected_feature_ids, list), f"gate feature links are missing: {gate_id}")
        for feature_id in affected_feature_ids:
            _require(feature_id in known_feature_ids, f"gate references unknown feature: {gate_id}/{feature_id}")
            gate_ids_by_feature[feature_id].add(gate_id)
    for decision in decisions:
        affected_codes = decision.get("affected_artifact_type_ids", [])
        _require(isinstance(affected_codes, list), "decision artifact links must be an array")
        for code in affected_codes:
            _require(code in known_codes, f"decision references unknown artifact type: {code}")
            result[code]["decision_ids"].add(decision["decision_id"])
            result[code]["canonical_decision_ids"].add(decision["canonical_decision_id"])
            result[code]["feature_policy_ids"].update(
                value
                for value in decision.get("affected_feature_ids", [])
                if isinstance(value, str)
            )
            tracking = decision.get("verification_tracking", {})
            for binding in tracking.get("feature_policy_gate_refs", []):
                if isinstance(binding, dict):
                    result[code]["gate_ids"].update(
                        value
                        for value in binding.get("gate_ids", [])
                        if isinstance(value, str)
                    )

    serialized: dict[str, dict[str, Any]] = {}
    for code in sorted(known_codes):
        values = result[code]
        values["feature_policy_ids"].update(
            DIRECT_POLICY_FEATURE_OVERRIDES.get(code, set())
        )
        _require(
            values["feature_policy_ids"] <= known_feature_ids,
            f"artifact trace references unknown policy feature: {code}",
        )
        for feature_id in values["feature_policy_ids"]:
            values["gate_ids"].update(gate_ids_by_feature[feature_id])
        decision_ids = sorted(values["decision_ids"])
        serialized[code] = {
            "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "policy_baseline_version": "1.0.0",
            "policy_correction_candidate_id": (
                "WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001"
                if "FP-035" in values["feature_policy_ids"]
                or code in FP035_INDIRECT_IMPACT_CODES
                else None
            ),
            "policy_correction_candidate_status": (
                "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL"
                if "FP-035" in values["feature_policy_ids"]
                or code in FP035_INDIRECT_IMPACT_CODES
                else "NOT_APPLICABLE"
            ),
            "decision_register_path": _relative(ALIGNED_DECISION_REGISTER_PATH),
            "decision_register_status": "ALIGNED_DERIVED_INPUT_NOT_SEPARATELY_APPROVED",
            "decision_ids": decision_ids,
            "canonical_decision_ids": sorted(values["canonical_decision_ids"]),
            "feature_policy_ids": sorted(values["feature_policy_ids"]),
            "gate_ids": sorted(values["gate_ids"]),
            "open_issue_refs": (
                FP035_CORRECTION_REFS
                if "FP-035" in values["feature_policy_ids"]
                or code in FP035_INDIRECT_IMPACT_CODES
                else []
            ),
            "empty_direct_decision_reason": (
                None
                if decision_ids
                else "직접 결정 연결은 없으며 작성 입력은 upstream_types와 승인 정책 기준선에서 상속한다."
            ),
        }
    return serialized


def _artifact_row(
    item: dict[str, Any],
    discovery: dict[str, Any],
    trace_index: dict[str, dict[str, Any]],
    approved_input_trace: dict[str, Any],
    readiness: dict[str, Any],
) -> dict[str, Any]:
    display_code = item["display_code"]
    doc_location = DOC_LOCATIONS.get(display_code)
    is_doc = doc_location is not None
    discovered_draft = discovery["primary_files"].get(display_code)
    is_materialized = is_doc or discovered_draft is not None
    applicability = item["default_applicability"]
    planned_path = BUNDLE_PATHS[item["recommended_bundle_id"]]
    inactive_reason = CURRENT_BASELINE_INACTIVE.get(display_code)
    is_active = applicability == "REQUIRED" or display_code in CURRENT_BASELINE_ACTIVE
    readiness_status = readiness["readiness"]
    if readiness_status == "READY_FOR_BUNDLED_CONTENT_APPROVAL":
        blockers = ["HUMAN_BUNDLED_APPROVAL_PENDING"]
    elif readiness_status == "READY_WITH_FP035_CORRECTION_DEPENDENCY":
        blockers = ["FP035_CORRECTION_AND_HUMAN_BUNDLED_APPROVAL_PENDING"]
    elif readiness_status == "EVIDENCE_OR_EXTERNAL_VALUE_PENDING":
        blockers = ["EVIDENCE_OR_EXTERNAL_VALUE_PENDING", "HUMAN_REVIEW_AFTER_EVIDENCE"]
    else:
        _require(readiness_status == "PLANNED_NOT_RUN", f"unknown readiness: {display_code}")
        blockers = ["EXECUTION_NOT_RUN", "HUMAN_REVIEW_AFTER_EXECUTION"]
    if inactive_reason:
        blockers = ["NOT_ACTIVE_UNDER_CURRENT_BASELINE"]
    elif applicability == "CONDITIONAL" and not is_active:
        blockers.insert(0, "PENDING_ACTIVATION_EVALUATION")
    discovered_links = discovery["by_code"].get(display_code, {})
    supporting_paths = discovered_links.get("supporting_paths", []) if discovered_draft else []
    if is_doc:
        lifecycle_status = doc_location["status"]
        verification_status = doc_location["verification"]
        document_version = CONTROL_DOCUMENT_VERSION
        canonical_path = doc_location["path"]
        coverage_anchor = doc_location["anchor"]
        generated_paths = doc_location["generated"]
        file_sha256 = (
            _sha256(MANUAL_PATH) if display_code in {"DOC-02", "DOC-03", "DOC-04"} else None
        )
        generator_path = _relative(GENERATOR_PATH) if display_code in {"DOC-01", "DOC-05"} else None
        generator_sha256 = _sha256(GENERATOR_PATH) if generator_path else None
    elif discovered_draft:
        lifecycle_status = discovered_draft["lifecycle_status"]
        verification_status = "STRUCTURE_CHECKED"
        document_version = discovered_draft["document_version"]
        canonical_path = discovered_draft["path"]
        coverage_anchor = discovered_draft["coverage_anchor"]
        generated_paths = supporting_paths
        file_sha256 = discovered_draft["sha256"]
        generator = discovered_draft.get("generator")
        generator_path = generator.get("path") if generator else None
        generator_sha256 = generator.get("sha256") if generator else None
    else:
        lifecycle_status = "PLANNED"
        verification_status = "NOT_RUN"
        document_version = None
        canonical_path = None
        coverage_anchor = display_code.lower()
        generated_paths = []
        file_sha256 = None
        generator_path = None
        generator_sha256 = None

    if readiness_status == "PLANNED_NOT_RUN":
        lifecycle_status = "PLANNED"
        verification_status = "NOT_RUN"
        document_version = None
        canonical_path = None
        generated_paths = []
        file_sha256 = None
        generator_path = None
        generator_sha256 = None
    elif readiness_status in {
        "READY_FOR_BUNDLED_CONTENT_APPROVAL",
        "READY_WITH_FP035_CORRECTION_DEPENDENCY",
    }:
        _require(is_materialized, f"approval-ready artifact is not materialized: {display_code}")
        verification_status = "CONTENT_READINESS_AUDITED"

    external_role_tokens = ("독립", "외부", "인수자", "서명자", "발행자")
    unassigned_required_reviewer_roles = [
        role
        for role in item["reviewer_roles"]
        if any(token in role for token in external_role_tokens)
    ]
    internal_reviewer_roles = [
        role for role in item["reviewer_roles"] if role not in unassigned_required_reviewer_roles
    ]
    approver_requires_external_assignment = any(
        token in item["approver_role"] for token in external_role_tokens
    )

    return {
        "artifact_type_code": item["type_code"],
        "display_code": display_code,
        "artifact_instance_id": _instance_id(display_code),
        "title": item["title"],
        "category": item["category"],
        "applicability": applicability,
        "activation_condition": item["activation_condition"],
        "activation_result": (
            "NOT_ACTIVE_CURRENT_BASELINE"
            if inactive_reason
            else "ACTIVE"
            if is_active
            else "PENDING_EVALUATION"
        ),
        "n_a_reason": inactive_reason,
        "artifact_form": item["recommended_form"],
        "bundle_id": item["recommended_bundle_id"],
        "priority": item["current_priority"],
        "authoring_contract": {
            "purpose": item["purpose"],
            "required_contents": item["required_contents"],
            "required_inputs": item["required_inputs"],
            "update_triggers": item["update_triggers"],
            "completion_criteria": item["completion_criteria"],
        },
        "authoring_readiness": {
            "assessment_id": "WS-ARTIFACT-CONTENT-READINESS-AUDIT-20260722-001",
            "readiness": readiness_status,
            "reason": readiness["readiness_reason"],
            "approval_proposed": readiness["approval_proposed"],
            "approval_track": readiness["approval_track"],
            "pending_completion_contract": readiness["pending_completion_contract"],
        },
        "management_contract": _management_contract(item),
        "responsibility": {
            "author_role": item["owner_role"],
            "assigned_author": PROJECT_OWNER_NAME,
            "content_owner_role": item["owner_role"],
            "reviewer_roles": item["reviewer_roles"],
            "assigned_reviewers": [PROJECT_OWNER_NAME] if internal_reviewer_roles else [],
            "unassigned_required_reviewer_roles": unassigned_required_reviewer_roles,
            "canonical_approver_role": item["approver_role"],
            "assigned_approver": (
                None if approver_requires_external_assignment else PROJECT_OWNER_NAME
            ),
            "external_signer_or_issuer": None,
        },
        "state": {
            "lifecycle_status": lifecycle_status,
            "freshness_status": "CURRENT",
            "verification_status": verification_status,
            "blockers": blockers,
        },
        "version": {
            "document_version": document_version,
            "baseline_id": None,
            "source_commit": None,
            "build_id": None,
            "model_id": None,
            "config_version": None,
        },
        "location": {
            "canonical_path": canonical_path,
            "planned_canonical_path": doc_location["path"] if is_doc else planned_path,
            "coverage_anchor": coverage_anchor,
            "generated_paths": generated_paths,
            "external_source": None,
        },
        "trace": {
            "upstream_types": item["upstream_types"],
            "downstream_types": item["downstream_types"],
            "upstream_instances": [],
            "downstream_instances": [],
            "approved_input_trace": approved_input_trace,
            **trace_index[display_code],
            "trace_validation_report_path": _relative(
                FORMAL_TRACE_7_TO_12_REPORT_PATH
                if item["category"] in PLANNED_7_TO_12_CATEGORIES
                else TRACE_INTEGRATION_REPORT_PATH
            ),
            "draft_manifest_paths": discovered_links.get("manifest_paths", []),
            "supporting_artifact_paths": supporting_paths,
            "empty_field_reasons": {
                **trace_index[display_code]["empty_field_reasons"],
                "upstream_instances": "현재는 산출물 유형 간 선후관계만 확정되어 upstream_types로 관리하며, 승인된 개별 인스턴스 기준선은 아직 없다.",
                "downstream_instances": "현재는 산출물 유형 간 선후관계만 확정되어 downstream_types로 관리하며, 승인된 개별 인스턴스 기준선은 아직 없다.",
                **(
                    {
                        "draft_manifest_paths": "DOC 통제 문서는 자체 생성·관리 대상이므로 별도 후행 Draft manifest가 없다."
                    }
                    if not discovered_links.get("manifest_paths", [])
                    else {}
                ),
                **(
                    {
                        "supporting_artifact_paths": "현재 정본 외에 이 유형으로 직접 선언된 보조 산출물 경로가 없다."
                    }
                    if not supporting_paths
                    else {}
                ),
            },
        },
        "integrity": {
            "sha256": file_sha256,
            "generator": generator_path,
            "generator_sha256": generator_sha256,
            "last_verified_at": GENERATED_AT if canonical_path else None,
        },
        "dates": {
            "created_at": AS_OF,
            "updated_at": AS_OF,
            "approved_at": None,
            "external_signed_or_issued_at": None,
            "next_review_at": NEXT_CONTROL_REVIEW_AT,
            "expires_at": None,
        },
        "change_control": {
            "previous_instance": None,
            "supersedes": None,
            "waiver_id": None,
        },
        "record_controls": {
            "source_class": (
                "GENERATED"
                if display_code in {"DOC-01", "DOC-05"}
                or (canonical_path and generator_path)
                else "UNCLASSIFIED"
            ),
            "adoption_trust": "D",
            "external_record_hash": None,
            "external_signature_status": (
                "PENDING"
                if item["recommended_form"] == "EXTERNAL_RECORD"
                and lifecycle_status in SAFE_DRAFT_LIFECYCLES
                else "NOT_REQUESTED"
                if item["recommended_form"] == "EXTERNAL_RECORD" and is_active
                else "PENDING_ACTIVATION"
                if item["recommended_form"] == "EXTERNAL_RECORD"
                else "NOT_APPLICABLE"
            ),
            "confidentiality": (
                "RESTRICTED"
                if display_code in SECURITY_RESTRICTED_CODES
                or display_code in SENSITIVE_PERSONAL_DATA_BY_CODE
                else "INTERNAL"
            ),
            "personal_data": SENSITIVE_PERSONAL_DATA_BY_CODE.get(display_code, []),
            "retention_class": _retention_profile_id(
                display_code, item["recommended_form"]
            ),
            "raw_evidence_storage_rule": (
                "서명 원본과 불필요한 개인정보·위치·영상·음성은 Git 저장소에 두지 않는다. "
                "Git에는 승인된 최소 책임 메타데이터와 암호화된 통제 저장소의 ID·hash·권한·보존정책만 연결한다."
                if display_code in SENSITIVE_PERSONAL_DATA_BY_CODE
                else "정본·증거 형식에 맞는 통제 저장소를 사용한다."
            ),
            "notes": (
                "내용 준비도 감사 완료; 사람 일괄 승인 전"
                if readiness["approval_proposed"]
                else "방법·완료조건은 작성됐으나 실제 증거·외부값 또는 실행 결과가 없어 승인 대상이 아님"
            ),
        },
    }


def build_register(
    catalog: dict[str, Any], manifest: dict[str, Any]
) -> dict[str, Any]:
    items = _select_and_validate_catalog(catalog)
    readiness_by_code, readiness_audit = _readiness_index()
    _require(
        set(readiness_by_code) == {item["display_code"] for item in items},
        "content readiness/catalog code set differs",
    )
    discovery = discover_draft_sources(items)
    trace_index = _build_artifact_trace_index(items)
    approved_input_trace = _approved_input_trace_by_code(items)
    artifacts = [
        _artifact_row(
            item,
            discovery,
            trace_index,
            approved_input_trace[item["display_code"]],
            readiness_by_code[item["display_code"]],
        )
        for item in items
    ]
    category_counts = Counter(row["category"] for row in artifacts)
    form_counts = Counter(row["artifact_form"] for row in artifacts)
    state_counts = Counter(row["state"]["lifecycle_status"] for row in artifacts)
    formal_0_to_6_rows = [
        row for row in artifacts if row["category"] in FORMAL_0_TO_6_CATEGORIES
    ]
    formal_7_to_12_rows = [
        row for row in artifacts if row["category"] in PLANNED_7_TO_12_CATEGORIES
    ]
    register: dict[str, Any] = {
        "schema_version": "walksafe.artifact-register.v1",
        "metadata": {
            "register_id": "ART-DOC-01-001",
            "artifact_type_code": "DLV-DOC-01",
            "title": "WalkSafe DOC-01~CLS-16 전체 산출물 관리대장",
            "document_version": CONTROL_DOCUMENT_VERSION,
            "as_of": AS_OF,
            "generated_at": GENERATED_AT,
            "lifecycle_status": "DRAFT",
            "freshness_status": "CURRENT",
            "verification_status": "GENERATED_AND_CHECKED",
            "approval_status": "NOT_APPROVED",
            "canonical_path": _relative(REGISTER_PATH),
            "scope": "DOC~CLS (0~12), 257개 산출물 유형",
        },
        "source_bindings": _source_bindings(),
        "authorization_boundary": _authorization_boundary(manifest),
        "remaining_gates": _remaining_gates(manifest),
        "summary": {
            "artifact_type_count": len(artifacts),
            "required_count": sum(row["applicability"] == "REQUIRED" for row in artifacts),
            "conditional_count": sum(row["applicability"] == "CONDITIONAL" for row in artifacts),
            "n_a_count": sum(
                row["activation_result"] == "NOT_ACTIVE_CURRENT_BASELINE"
                for row in artifacts
            ),
            "bundle_count": len(BUNDLE_PATHS),
            "approved_artifact_count": 0,
            "content_approval_candidate_count": sum(
                row["authoring_readiness"]["approval_proposed"] for row in artifacts
            ),
            "content_approval_pending_count": sum(
                not row["authoring_readiness"]["approval_proposed"] for row in artifacts
            ),
            "active_opening_snapshot_candidate_count": sum(
                row["authoring_readiness"]["approval_track"]
                == "ACTIVE_OPENING_SNAPSHOT"
                for row in artifacts
            ),
            "versioned_content_baseline_candidate_count": sum(
                row["authoring_readiness"]["approval_track"]
                == "VERSIONED_CONTENT_BASELINE"
                for row in artifacts
            ),
            "readiness_counts": dict(
                sorted(
                    Counter(
                        row["authoring_readiness"]["readiness"] for row in artifacts
                    ).items()
                )
            ),
            "materialized_artifact_count": sum(
                row["state"]["lifecycle_status"] in SAFE_DRAFT_LIFECYCLES for row in artifacts
            ),
            "planned_artifact_count": sum(
                row["state"]["lifecycle_status"] == "PLANNED" for row in artifacts
            ),
            "category_counts": {category: category_counts[category] for category in CATEGORIES},
            "artifact_form_counts": dict(sorted(form_counts.items())),
            "lifecycle_status_counts": dict(sorted(state_counts.items())),
            "activation_result_counts": dict(
                sorted(Counter(row["activation_result"] for row in artifacts).items())
            ),
            "direct_trace_linked_artifact_count": sum(
                row["trace"]["direct_link_applicability"] == "DIRECT_LINKS_INDEXED"
                for row in artifacts
            ),
            "direct_trace_pending_case_artifact_count": sum(
                row["trace"]["direct_link_applicability"]
                == "APPLICABLE_NO_CURRENT_PLANNED_TEST_CASES"
                for row in artifacts
            ),
        },
        "scope_summaries": {
            "formal_0_to_6": {
                "artifact_type_count": len(formal_0_to_6_rows),
                "bundle_count": len(
                    {row["bundle_id"] for row in formal_0_to_6_rows}
                ),
                "materialized_artifact_count": sum(
                    row["state"]["lifecycle_status"] in SAFE_DRAFT_LIFECYCLES
                    for row in formal_0_to_6_rows
                ),
                "lifecycle_status_counts": dict(
                    sorted(
                        Counter(
                            row["state"]["lifecycle_status"]
                            for row in formal_0_to_6_rows
                        ).items()
                    )
                ),
            },
            "formal_7_to_12": {
                "artifact_type_count": len(formal_7_to_12_rows),
                "bundle_count": len(
                    {row["bundle_id"] for row in formal_7_to_12_rows}
                ),
                "materialized_artifact_count": sum(
                    row["state"]["lifecycle_status"] in SAFE_DRAFT_LIFECYCLES
                    for row in formal_7_to_12_rows
                ),
                "lifecycle_status_counts": dict(
                    sorted(
                        Counter(
                            row["state"]["lifecycle_status"]
                            for row in formal_7_to_12_rows
                        ).items()
                    )
                ),
            },
        },
        "trace_index": {
            "status": "0_TO_12_HASH_BOUND_WITH_EXECUTION_EVIDENCE_BOUNDARY",
            "validation_scope": (
                "0~6은 요구·설계·시험 ID·경로·해시 구조, 7~12는 Draft/Planned 경계·정책·결정·gate·bundle anchor·hash 구조"
            ),
            "report_paths": [
                _relative(TRACE_INTEGRATION_REPORT_PATH),
                _relative(FORMAL_TRACE_7_TO_12_REPORT_PATH),
            ],
            "human_summary_paths": [
                _relative(TRACE_INTEGRATION_SUMMARY_PATH),
                _relative(FORMAL_TRACE_7_TO_12_SUMMARY_PATH),
            ],
            "report_path": _relative(TRACE_INTEGRATION_REPORT_PATH),
            "human_summary_path": _relative(TRACE_INTEGRATION_SUMMARY_PATH),
            "formal_7_to_12_report_path": _relative(FORMAL_TRACE_7_TO_12_REPORT_PATH),
            "formal_7_to_12_human_summary_path": _relative(
                FORMAL_TRACE_7_TO_12_SUMMARY_PATH
            ),
            "formal_test_execution_status": "NOT_RUN",
            "formal_approval_status": "NOT_APPROVED",
        },
        "content_readiness_audit": {
            "audit_id": readiness_audit["metadata"]["audit_id"],
            "audit_version": readiness_audit["metadata"]["audit_version"],
            "path": _relative(READINESS_AUDIT_PATH),
            "file_sha256": _sha256(READINESS_AUDIT_PATH),
            "assessment_binding_sha256": readiness_audit["assessment_binding_sha256"],
            "approval_status": readiness_audit["metadata"]["approval_status"],
        },
        "draft_discovery": {
            "manifest_bindings": discovery["manifest_bindings"],
            "file_bindings": discovery["file_bindings"],
        },
        "bundle_coverage": _bundle_coverage(items, artifacts),
        "artifacts": artifacts,
    }
    register["content_sha256"] = _object_sha256(register)
    return register


def build_change_log(manifest: dict[str, Any], register: dict[str, Any]) -> dict[str, Any]:
    materialized_0_to_6_non_doc_codes = [
        row["display_code"]
        for row in register["artifacts"]
        if row["category"] in FORMAL_0_TO_6_CATEGORIES
        and row["category"] != "DOC"
        and row["state"]["lifecycle_status"] in SAFE_DRAFT_LIFECYCLES
    ]
    formal_0_to_6_codes = [
        row["display_code"]
        for row in register["artifacts"]
        if row["category"] in FORMAL_0_TO_6_CATEGORIES
    ]
    formal_7_to_12_codes = [
        row["display_code"]
        for row in register["artifacts"]
        if row["category"] in PLANNED_7_TO_12_CATEGORIES
    ]
    materialized_7_to_12_codes = [
        row["display_code"]
        for row in register["artifacts"]
        if row["category"] in PLANNED_7_TO_12_CATEGORIES
        and row["state"]["lifecycle_status"] in SAFE_DRAFT_LIFECYCLES
    ]
    planned_7_to_12_codes = [
        row["display_code"]
        for row in register["artifacts"]
        if row["category"] in PLANNED_7_TO_12_CATEGORIES
        and row["state"]["lifecycle_status"] == "PLANNED"
    ]
    fp035_affected_codes = [
        row["display_code"]
        for row in register["artifacts"]
        if "FP-035" in row["trace"]["approved_input_trace"]["feature_policy_ids"]
        or row["display_code"] in FP035_INDIRECT_IMPACT_CODES
    ]
    changes = [
        {
            "change_id": "CHG-DOC-0001",
            "date": AS_OF,
            "change_type": "CONTROL_BOOTSTRAP_CREATED",
            "title": "DOC-01~05 정식 초안 생성",
            "reason": "승인된 정책 기준선을 바탕으로 0~6 산출물 작성을 시작하기 위한 통제 기반 마련",
            "before_summary": "docs/deliverables/00-control 정본 초안과 0~6 통합 관리대장이 없었음",
            "after_summary": "DOC-01~05 초안, 128개 관리대장과 사람용 README·HTML 보기를 생성함",
            "affected_artifact_codes": [f"DOC-{number:02d}" for number in range(1, 6)],
            "affected_paths": [
                "docs/deliverables/00-control/document-control-manual.md",
                "docs/deliverables/00-control/artifact-register.json",
                "docs/deliverables/00-control/artifact-change-log.json",
                "docs/deliverables/00-control/README.md",
                "docs/deliverables/00-control/artifact-register.html",
            ],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
        },
        {
            "change_id": "CHG-DOC-0002",
            "date": AS_OF,
            "change_type": "PLATFORM_SCOPE_ALIGNED",
            "title": "Android 주제품 정책과 TST-23 범위 정렬",
            "reason": "승인 정책의 Android 사용자 앱·비공개 관리자 앱 주제품 및 Web/PWA 레거시 경계를 작성 계획에 반영",
            "before_summary": "일부 유형 설명과 작성 계획이 Web/PWA 주제품·Android 연구 전제를 유지하고 단계 6에서 TST-23을 누락함",
            "after_summary": "Android 사용자·관리자 앱 주제품 경계로 정렬하고 단계 6 범위를 TST-01~23으로 고침",
            "affected_artifact_codes": [
                "MGT-04",
                "DSC-09",
                "DSC-12",
                "DSC-13",
                "REQ-02",
                "REQ-14",
                "DES-03",
                "DEV-04",
                "TST-17",
                "TST-23",
            ],
            "affected_paths": [
                "docs/control/artifact-types.json",
                "docs/control/documentation-authoring-preparation-plan.md",
                "docs/control/README.md",
            ],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
        },
        {
            "change_id": "CHG-DOC-0003",
            "date": AS_OF,
            "change_type": "SCOPE_REGISTERED",
            "title": "0~6 산출물 유형 128개 등록",
            "reason": "필수 114개·조건부 14개와 21개 bundle coverage를 중복 없이 관리",
            "before_summary": "0~6 유형의 실제 인스턴스 ID·상태·위치·책임·bundle coverage 원장이 없었음",
            "after_summary": "128개 유형을 각 1회 등록하고 필수 114개·조건부 14개·21개 bundle을 기록함",
            "affected_artifact_codes": formal_0_to_6_codes,
            "affected_paths": ["docs/deliverables/00-control/artifact-register.json"],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
        },
        {
            "change_id": "CHG-DOC-0004",
            "date": AS_OF,
            "change_type": "DRAFT_DISCOVERY_SYNCHRONIZED",
            "title": "후행 1~6 정식 초안 재스캔",
            "reason": "후속 생성된 정식 Draft가 관리대장에 계속 Planned로 남지 않도록 실제 정본 경로·anchor·hash와 검토 상태를 동기화",
            "before_summary": "후속 산출물 파일이 생겨도 0~6 bootstrap 최초 등록 상태인 Planned가 유지될 수 있었음",
            "after_summary": f"현재 존재하고 구조를 확인한 MGT~TST 정식 초안 {len(materialized_0_to_6_non_doc_codes)}개 유형을 Draft/In Review로 연결함",
            "affected_artifact_codes": materialized_0_to_6_non_doc_codes,
            "affected_paths": [
                binding["path"]
                for binding in register["draft_discovery"]["file_bindings"]
                if binding["path"].startswith(
                    tuple(
                        f"docs/deliverables/{number:02d}-"
                        for number in range(1, 7)
                    )
                )
            ],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
        },
        {
            "change_id": "CHG-DOC-0005",
            "date": AS_OF,
            "change_type": "FORMAL_USER_SCOPE_WORDING_ALIGNED",
            "title": "산출물 작성계약의 공식 사용자 범위 정렬",
            "reason": "승인 정책 FP-001·FP-004에 따라 전맹·저시력 시각장애인을 같은 우선순위로 두고, 일반 접근성 고려가 공식 대상 확대처럼 읽히지 않게 고침",
            "before_summary": "MGT-02·DSC-01은 보행약자 일반을, DSC-05·REQ-11은 청각·운동·인지 사용자를 공식 대상처럼 읽을 수 있었음",
            "after_summary": "공식 대상은 같은 우선순위의 전맹·저시력 시각장애인으로 명시하고, 중복장애·일반 접근성은 보조 고려이며 범위 확대가 아님을 분리함",
            "affected_artifact_codes": ["MGT-02", "DSC-01", "DSC-05", "REQ-11"],
            "affected_paths": ["docs/control/artifact-types.json"],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
        },
        {
            "change_id": "CHG-DOC-0006",
            "date": AS_OF,
            "change_type": "FINAL_INTEGRATION_AUDIT_CORRECTIONS",
            "title": "0~6 통합 추적·가독성·상태표시 감사 보정",
            "reason": "정책 의미를 바꾸지 않고 비전공자 검토 동선, 내부 필드명 노출, FP-035 차단 전파와 Draft 상태를 실제 통제 상태에 맞추기 위함",
            "before_summary": "일부 문서가 내부 필드명·미설명 보안용어를 노출했고 FP-035 영향범위와 시험 사전차단 표시가 계층별로 달랐으며 DOC-01·05가 검토자 지정 없이 In Review였음",
            "after_summary": "쉬운 본문·문서 바로가기·논리적 검토순서를 제공하고 FP-035를 16개 설계·2개 구현모듈·4개 시험에 연결했으며 공식 검토 전 상태를 Draft로 정렬함",
            "affected_artifact_codes": [
                "DOC-01", "DOC-05", "MGT-16", "REQ-03", "DES-13", "DES-18", "DES-19",
                "DES-23", "DES-25", "DES-27", "DEV-18", "TST-01", "TST-19", "TST-20",
            ],
            "affected_paths": [
                "docs/deliverables/README.md",
                "docs/deliverables/00-control/artifact-register.html",
                "docs/deliverables/01-management/registers/change-requests.json",
                "docs/deliverables/03-requirements/system-requirements.md",
                "docs/deliverables/04-design/interface-and-data-design.md",
                "docs/deliverables/04-design/user-experience-and-accessibility-design.md",
                "docs/deliverables/04-design/security-and-operations-design.md",
                "docs/deliverables/05-implementation/module-register.json",
                "docs/deliverables/06-testing/test-plan.md",
                "docs/deliverables/06-testing/software-test-report.md",
                "docs/deliverables/06-testing/registers/metrics.json",
                "docs/deliverables/traceability/README.md",
            ],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
        },
        {
            "change_id": "CHG-DOC-0007",
            "date": AS_OF,
            "change_type": "FULL_CATALOG_SCOPE_REGISTERED",
            "title": "7~12 계획 129개를 포함한 257개 전체 관리대장 확장",
            "reason": "사용자가 요청한 DOC-01~CLS-16 전체 작성·관리 계획을 실제 DOC-01에서 한 번씩 추적하기 위함",
            "before_summary": "DOC~TST 0~6 유형 128개와 21개 bundle만 관리대장에 등록되어 있었음",
            "after_summary": "기존 128개 Draft를 보존하고 SEC~CLS 129개를 Planned로 추가해 257개·40개 bundle을 등록함",
            "affected_artifact_codes": formal_7_to_12_codes,
            "affected_paths": [
                "docs/control/documentation-authoring-preparation-plan.md",
                "docs/deliverables/00-control/artifact-register.json",
                "docs/deliverables/00-control/artifact-register.html",
                "docs/deliverables/00-control/README.md",
                "docs/deliverables/README.md",
            ],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
        },
        {
            "change_id": "CHG-DOC-0008",
            "date": AS_OF,
            "change_type": "ANDROID_SCOPE_AND_MANAGEMENT_CONTRACT_ALIGNED",
            "title": "Android 범위와 산출물별 관리계약 현행화",
            "reason": "레거시 Web/PWA 전제가 새 계획으로 전파되는 것을 막고 각 산출물의 검토주기·변경·보존·대체 방식을 명시하기 위함",
            "before_summary": "WS-16이 필수이고 WS-20이 Web/PWA E2E였으며 유형별 검토주기·변경/폐기 profile과 민감자료 저장규칙이 없었음",
            "after_summary": "WS-16을 현재 비활성 조건부로 분리하고 WS-20을 Android 실휴대폰 E2E로 고쳤으며 257개 각 행에 관리·보존 profile과 승인 입력 trace를 추가함",
            "affected_artifact_codes": [
                "DSC-05", "REQ-07", "REQ-14", "DEV-04", "TST-03", "TST-07",
                "TST-09", "TST-13", "SEC-07", "SEC-13", "SEC-18", "AIML-22",
                "REL-01", "REL-02", "REL-15", "OPS-01", "OPS-02", "OPS-04",
                "OPS-17", "OPS-19", "OPS-23", "WS-09", "WS-10", "WS-14",
                "WS-16", "WS-17", "WS-20",
            ],
            "affected_paths": [
                "docs/control/artifact-types.json",
                "docs/control/documentation-authoring-preparation-plan.md",
                "docs/deliverables/00-control/artifact-register.json",
            ],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
        },
        {
            "change_id": "CHG-DOC-0009",
            "date": AS_OF,
            "change_type": "FORMAL_7_TO_12_DRAFTS_AND_EXECUTION_BOUNDARY_MATERIALIZED",
            "title": "SEC~CLS 7~12 정식 초안과 실행 증거 경계 작성",
            "reason": "승인 정책 기준선과 사용자 작업 지시에 따라 7~12의 작성 가능한 계획·정책·절차를 만들고, 실제 실행·서명·통제 증거는 근거가 생길 때까지 Planned/NOT_RUN으로 분리하기 위함",
            "before_summary": "SEC~CLS 129개 유형이 모두 Planned이고 예정 경로와 작성계약만 DOC-01에 등록되어 있었음",
            "after_summary": (
                f"SEC~CLS 129개 중 작성 가능한 {len(materialized_7_to_12_codes)}개를 Draft로 연결하고 "
                f"실행·서명·통제 증거 {len(planned_7_to_12_codes)}개는 Planned/NOT_RUN으로 유지했으며 "
                "19개 문서 묶음의 장·정책·결정·gate·파일 지문을 구조 검증함"
            ),
            "affected_artifact_codes": formal_7_to_12_codes,
            "affected_paths": sorted(
                {
                    binding["path"]
                    for binding in register["draft_discovery"]["file_bindings"]
                    if binding["path"].startswith(
                        (
                            "docs/deliverables/07-security/",
                            "docs/deliverables/08-ai-ml-data/",
                            "docs/deliverables/09-release/",
                            "docs/deliverables/10-operations/",
                            "docs/deliverables/11-walksafe/",
                            "docs/deliverables/12-closure/",
                        )
                    )
                }
                | {
                    _relative(FORMAL_TRACE_7_TO_12_REPORT_PATH),
                    _relative(FORMAL_TRACE_7_TO_12_SUMMARY_PATH),
                    "docs/deliverables/manifests/sec-ws-draft-20260721-r001.json",
                    "docs/deliverables/manifests/aiml-draft-20260721-r001.json",
                    "docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json",
                }
            ),
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
        },
        {
            "change_id": "CHG-DOC-0010",
            "date": AS_OF,
            "change_type": "CONTROL_AND_TRACE_INDEPENDENT_AUDIT_CORRECTIONS",
            "title": "7~12 통제·추적 독립감사 보정",
            "reason": "정책 의미를 바꾸지 않고 생성 의존성, FP-035 OPEN 전파, rollback 안전조건, 통제 문서 경로와 변경이력 범위를 실제 구조에 맞추기 위함",
            "before_summary": "SEC/WS 생성기가 후행 DOC-01을 조회했고 FP-035 OPEN 참조가 일부 유형에만 표시됐으며 AIML-24·REL-15 rollback 문구와 AIML 디렉터리 표기, CHG-DOC-0004 범위가 불완전했음",
            "after_summary": "SEC/WS bundle 검증을 상위 카탈로그 기준으로 바꾸고 FP-035 직접·간접 영향 행에 OPEN 이슈를 전파했으며 rollback을 DB 호환·새 자료 무손실 시험 조건으로 제한하고 통제 경로·역사 변경범위를 바로잡음",
            "affected_artifact_codes": sorted(
                {
                    "DOC-01",
                    "DOC-02",
                    "DOC-05",
                    "AIML-24",
                    "REL-15",
                    *fp035_affected_codes,
                }
            ),
            "affected_paths": [
                "scripts/build_walksafe_control_bootstrap.py",
                "scripts/build_walksafe_formal_sec_ws_20260721.py",
                "scripts/build_walksafe_formal_aiml_20260721.py",
                "scripts/build_walksafe_formal_rel_ops_cls_20260721.py",
                "scripts/build_walksafe_formal_trace_7_12_20260721.py",
                "tests/test_walksafe_control_bootstrap.py",
                "tests/test_walksafe_formal_sec_ws.py",
                "tests/test_walksafe_formal_aiml.py",
                "tests/test_walksafe_formal_rel_ops_cls.py",
                "tests/test_walksafe_formal_trace_7_12.py",
                "docs/deliverables/00-control/document-control-manual.md",
                "docs/deliverables/00-control/artifact-register.json",
                "docs/deliverables/00-control/artifact-change-log.json",
                "docs/deliverables/00-control/README.md",
                "docs/deliverables/00-control/artifact-register.html",
                "docs/deliverables/README.md",
                "docs/deliverables/08-ai-ml-data/model-operations.md",
                "docs/deliverables/09-release/deployment-evidence.md",
                "docs/deliverables/manifests/sec-ws-draft-20260721-r001.json",
                "docs/deliverables/manifests/aiml-draft-20260721-r001.json",
                "docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json",
                "docs/deliverables/traceability/formal-7-12-integration-report-20260721-r001.json",
                "docs/deliverables/traceability/formal-7-12-integration-summary.md",
            ],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
        },
        {
            "change_id": "CHG-DOC-0011",
            "date": AS_OF,
            "change_type": "FULL_CONTENT_AUTHORING_AND_READINESS_REASSESSMENT",
            "title": "257개 내용 보완·FP-035 정정 후보·기준선 준비도 재평가",
            "reason": "기존 답변을 다시 묻지 않고 작성 가능한 산출물을 실제 WalkSafe 내용으로 보완하며, 미실행 증거는 완료로 꾸미지 않은 채 새 일괄 승인 후보를 준비하기 위함",
            "before_summary": "이전 미승인 후보는 4개만 승인 적격으로 보고 121개를 작성 부족, 57개를 조건·외부값 대기로 분류했음",
            "after_summary": "178개를 작성 가능 120개·증거/외부값 대기 53개·FP-035 정정 영향 5개로 재검증하고, 기존 통제 후보 4개와 합친 129개를 내용 승인 후보로, 53개와 Planned/NOT_RUN 75개를 미승인으로 분리함",
            "affected_artifact_codes": [row["display_code"] for row in register["artifacts"]],
            "affected_paths": [
                "docs/control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json",
                "docs/control/audits/walksafe-artifact-content-readiness-audit-20260722-r001.json",
                "docs/deliverables/00-control/document-control-manual.md",
                "docs/deliverables/00-control/artifact-register.json",
                "docs/deliverables/00-control/artifact-change-log.json",
                "docs/deliverables/00-control/artifact-register.html",
                "docs/deliverables/README.md",
            ],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
        },
    ]
    for change in changes:
        change.update(
            {
                "requested_by": "PROJECT_MANAGER_AND_FINAL_POLICY_OWNER",
                "affected_requirement_ids": [],
                "affected_test_ids": [],
                "review": {
                    "review_status": "PENDING",
                    "reviewer": None,
                    "approval_status": "NOT_APPROVED",
                    "approval_record": None,
                },
                "application": {
                    "document_version": (
                        CONTROL_DOCUMENT_VERSION
                        if change["change_id"] == "CHG-DOC-0011"
                        else "0.3.0"
                        if change["change_id"] in {"CHG-DOC-0009", "CHG-DOC-0010"}
                        else "0.2.0"
                        if change["change_id"] in {"CHG-DOC-0007", "CHG-DOC-0008"}
                        else "0.1.0"
                    ),
                    "source_commit": None,
                    "baseline_id": None,
                },
                "rollback_or_supersedes": None,
            }
        )
    scope_wording_change = next(
        change for change in changes if change["change_id"] == "CHG-DOC-0005"
    )
    scope_wording_change["affected_requirement_ids"] = ["RQ-FP-001-001", "RQ-FP-004-001"]
    audit_change = next(change for change in changes if change["change_id"] == "CHG-DOC-0006")
    audit_change["affected_requirement_ids"] = ["RQ-FP-030-001", "RQ-FP-035-001"]
    audit_change["affected_test_ids"] = [f"TC-FP-035-{number:02d}" for number in range(1, 5)]
    independent_audit_change = next(
        change for change in changes if change["change_id"] == "CHG-DOC-0010"
    )
    independent_audit_change["affected_requirement_ids"] = ["RQ-FP-035-001"]
    independent_audit_change["affected_test_ids"] = [
        f"TC-FP-035-{number:02d}" for number in range(1, 5)
    ]
    full_scope_change = next(
        change for change in changes if change["change_id"] == "CHG-DOC-0008"
    )
    full_scope_change["affected_requirement_ids"] = [
        "RQ-FP-028-001",
        "RQ-FP-030-001",
        "RQ-FP-034-001",
        "RQ-FP-035-001",
        "RQ-FP-050-001",
    ]
    result: dict[str, Any] = {
        "schema_version": "walksafe.artifact-change-log.v1",
        "metadata": {
            "register_id": "ART-DOC-05-001",
            "artifact_type_code": "DLV-DOC-05",
            "title": "WalkSafe 산출물 변경 이력",
            "document_version": CONTROL_DOCUMENT_VERSION,
            "as_of": AS_OF,
            "generated_at": GENERATED_AT,
            "lifecycle_status": "DRAFT",
            "approval_status": "NOT_APPROVED",
            "canonical_path": _relative(CHANGE_LOG_PATH),
        },
        "source_bindings": _source_bindings(),
        "draft_discovery": register["draft_discovery"],
        "authorization_boundary": _authorization_boundary(manifest),
        "summary": {"change_count": len(changes), "approved_change_count": 0},
        "changes": changes,
    }
    result["content_sha256"] = _object_sha256(result)
    return result


def build_readme(register: dict[str, Any]) -> str:
    summary = register["summary"]
    formal_7_to_12 = register["scope_summaries"]["formal_7_to_12"]
    formal_7_to_12_states = formal_7_to_12["lifecycle_status_counts"]
    lines = [
        "# WalkSafe 257개 전체 산출물 통제 초안",
        "",
        f"> 현재 상태: **Draft**. 257개 내용 준비도 재평가 결과 {summary['content_approval_candidate_count']}개는 새 일괄 내용 승인 후보이고, {summary['content_approval_pending_count']}개는 실제 증거·외부값 또는 실행 결과가 없어 승인 후보가 아닙니다. 이 분류 자체는 사람 승인·구현 완료를 뜻하지 않으며 남은 gate 5개는 모두 NOT_RUN, 출시는 NOT_ELIGIBLE입니다.",
        "",
        "## 먼저 볼 파일",
        "",
        "| 파일 | 쉬운 설명 | 편집 방법 |",
        "|---|---|---|",
        "| [../README.md](../README.md) | 257개 전체 계획과 현재 0~12 Draft/Planned 경계를 찾는 비전공자용 최상위 입구 | 생성기 입력을 고쳐 재생성 |",
        "| [artifact-register.html](artifact-register.html) | 40개 문서 묶음에 연결된 257개 산출물 유형을 검색·필터링하는 사람용 화면 | 직접 편집하지 않음 |",
        "| [document-control-manual.md](document-control-manual.md) | DOC-02~04 명명·검토·승인·버전 규칙의 정식 초안 | 사람 검토 후 원문 수정 |",
        "| [artifact-register.json](artifact-register.json) | DOC-01 기계용 관리대장 정본 초안 | 생성기 입력을 고쳐 재생성 |",
        "| [artifact-change-log.json](artifact-change-log.json) | DOC-05 변경 이력 정본 초안 | 생성기 입력을 고쳐 재생성 |",
        "| [기능 정책 종합 HTML](../../control/decision-interview/walksafe-feature-policy-comprehensive-draft-20260720.html) | 사람이 승인한 9개 공통정책·54개 기능정책 원문 | 승인 원문이므로 직접 편집하지 않음 |",
        "| [정책 승인 기록](../../control/baselines/walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json) | 승인 문장·결정 지문·내용 지문과 승인 범위 | 불변 기록, 변경은 새 revision |",
        "| [정책 기준선 manifest](../../control/baselines/walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json) | 현행 정책 정본의 경로·SHA-256·남은 gate | 불변 기록, 변경은 새 revision |",
        "| [FP-035 정정 후보](../../control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.md) | 기존 답변의 이동통신망 뜻을 1.0.1 오버레이로 바로잡는 미승인 후보 | 기존 1.0.0은 불변, 일괄 승인 전 미효력 |",
        "| [257개 내용 준비도 재평가](../../control/audits/walksafe-artifact-content-readiness-audit-20260722-r001.md) | 129개 내용 승인 후보와 128개 증거 대기 항목의 이유·완료조건 | 감사 기록, 승인 아님 |",
        "| [요구·설계·시험 교차추적 안내](../traceability/README.md) | 요구 68개·시험 279개·설계 27개의 연결 검사 결과를 쉽게 설명하며 상세 기계용 JSON 링크도 제공. 실제 시험 PASS가 아님 | 각 정식 초안 재생성 뒤 마지막에 다시 생성 |",
        "| [7~12 구조·정책 추적 안내](../traceability/formal-7-12-integration-summary.md) | SEC~CLS 129개의 Draft/Planned 경계, 정책·결정·gate·bundle anchor와 hash 검사 결과. 실제 실행 PASS가 아님 | 7~12 정식 초안 재생성 뒤 다시 생성 |",
        "",
        "## 현재 등록 결과",
        "",
        f"- 전체 유형: **{summary['artifact_type_count']}개**",
        f"- 필수: **{summary['required_count']}개**",
        f"- 조건부: **{summary['conditional_count']}개** — 승인 범위로 활성·비활성·추가 평가를 분리",
        f"- 현 기준선 비활성: **{summary['n_a_count']}개** — WS-16 PWA 시험, 재활성 조건을 유지",
        f"- canonical bundle: **{summary['bundle_count']}개**",
        f"- 실제 Draft/In Review로 연결됨: **{summary['materialized_artifact_count']}개**",
        f"- 아직 Planned: **{summary['planned_artifact_count']}개**",
        f"- 승인된 정식 산출물: **{summary['approved_artifact_count']}개**",
        f"- 새 일괄 내용 승인 후보: **{summary['content_approval_candidate_count']}개** — Active 최초본 {summary['active_opening_snapshot_candidate_count']}개, 버전 기준선 {summary['versioned_content_baseline_candidate_count']}개",
        f"- 아직 내용 승인 후보 아님: **{summary['content_approval_pending_count']}개** — 증거·외부값 53개, Planned/NOT_RUN 75개",
        "",
        "| 범주 | 의미 | 수량 |",
        "|---|---|---:|",
    ]
    for category in CATEGORIES:
        lines.append(
            f"| {category} | {CATEGORY_LABELS[category]} | {summary['category_counts'][category]} |"
        )
    lines.extend(
        [
            "",
            "## 40개 문서 묶음",
            "",
            "한 유형은 정확히 한 묶음에만 들어갑니다. Draft 유형은 정본 hash와 장 위치를 연결합니다. Planned/NOT_RUN 유형은 실제 산출물 정본으로 연결하지 않고, 같은 묶음 안의 작성·실행 계약 섹션만 안내합니다.",
            "",
            "| 묶음 ID | 범주 | 유형 수 | 연결됨 | 필수 | 조건부 | 현재·예정 경로 | 상태 |",
            "|---|---|---:|---:|---:|---:|---|---|",
        ]
    )
    for bundle in register["bundle_coverage"]:
        paths = [
            *bundle["current_canonical_paths"],
            bundle["planned_canonical_path"],
            *bundle["additional_canonical_paths"],
        ]
        paths = list(dict.fromkeys(paths))
        path_text = "<br>".join(f"`{path}`" for path in paths)
        lines.append(
            f"| {bundle['bundle_id']} | {bundle['category']} | {bundle['type_count']} | "
            f"{bundle['materialized_type_count']} | {bundle['required_count']} | "
            f"{bundle['conditional_count']} | {path_text} | "
            f"{bundle['lifecycle_status']} |"
        )
    lines.extend(
        [
            "",
            "## 닫히지 않은 검증 gate",
            "",
            "| Gate | 상태 | 언제까지 닫아야 하나 |",
            "|---|---|---|",
        ]
    )
    for gate in register["remaining_gates"]:
        lines.append(f"| {gate['title']} | {gate['status']} | {gate['must_close_before']} |")
    lines.extend(
        [
            "",
            "## 검토 순서",
            "",
            "1. HTML에서 범주·필수/조건부·묶음별 등록을 확인합니다.",
            "2. 통제 규정에서 이름, 상태 전이, 검토·승인, 기준선 규칙을 확인합니다.",
            "3. 잘못된 담당 역할·묶음·작성 내용을 수정합니다.",
            "4. 생성기와 테스트를 다시 실행합니다.",
            "5. 사람 검토가 끝난 버전만 별도 승인 기록으로 Approved/Baselined 처리합니다.",
            "",
            "## 재생성·검증",
            "",
            "```bash",
            "python3 -B scripts/build_walksafe_control_bootstrap.py",
            "python3 -B scripts/build_walksafe_control_bootstrap.py --check",
            "python3 -B -m unittest tests.test_walksafe_control_bootstrap",
            "```",
            "",
            "이 README와 HTML은 생성 파일입니다. 직접 수정하지 말고 카탈로그·정책 manifest·통제 규정 또는 생성기를 수정합니다.",
            "",
            f"관리대장 내용 지문: `{register['content_sha256']}`",
            "",
        ]
    )
    return "\n".join(lines)


def _root_relative_link(path_text: str) -> str:
    prefix = "docs/deliverables/"
    _require(path_text.startswith(prefix), f"deliverable link is outside root: {path_text}")
    return path_text[len(prefix) :]


def build_root_readme(register: dict[str, Any]) -> str:
    summary = register["summary"]
    formal_7_to_12_states = register["scope_summaries"]["formal_7_to_12"][
        "lifecycle_status_counts"
    ]
    lines = [
        "# WalkSafe 257개 산출물 작성·관리 현황",
        "",
        "> 이 파일은 DOC-01~CLS-16 전체 계획과 현재 작성된 문서를 찾는 **최상위 입구**입니다. 세부 명명·승인·기준선 규칙과 257개 전체 행은 [00-control/README.md](00-control/README.md) 및 [관리대장 HTML](00-control/artifact-register.html)에서 확인합니다.",
        "",
        "## 지금 어디까지 왔나",
        "",
        f"- 전체 유형 **{summary['artifact_type_count']}개**: 필수 **{summary['required_count']}개**, 조건부 **{summary['conditional_count']}개**",
        f"- 문서 묶음 **{summary['bundle_count']}개**",
        f"- 실제 Draft/In Review 연결 **{summary['materialized_artifact_count']}개**, 아직 Planned **{summary['planned_artifact_count']}개**",
        f"- 새 일괄 내용 승인 후보 **{summary['content_approval_candidate_count']}개** — 최초 Active 원장 **{summary['active_opening_snapshot_candidate_count']}개**, 버전 기준선 문서 **{summary['versioned_content_baseline_candidate_count']}개**",
        f"- 아직 승인 후보가 아닌 항목 **{summary['content_approval_pending_count']}개** — 실제 증거·외부값 대기 53개, Planned/NOT_RUN 75개",
        f"- 7~12 범위: 계획·정책·절차 Draft **{formal_7_to_12_states.get('DRAFT', 0)}개**, 실제 실행·서명·통제 증거 Planned/NOT_RUN **{formal_7_to_12_states.get('PLANNED', 0)}개**",
        "- 승인된 정식 산출물 **0개** — 파일이 있다는 이유만으로 승인·기준선·PASS가 되지 않음",
        "- 남은 gate **5개 모두 NOT_RUN·미면제**, 출시 **NOT_ELIGIBLE**",
        "- FP-035는 기존 답변의 정규화 지시를 1.0.1 정정 후보로 포착함. 새 질문은 없으며, 일괄 승인 전 정정 후보는 미효력이고 관련 정식 시험은 NOT_RUN",
        "",
        "## 승인 정책과 근거",
        "",
        "- [기능 정책 종합 HTML](../control/decision-interview/walksafe-feature-policy-comprehensive-draft-20260720.html)",
        "- [검토 종결 기록](../control/decision-interview/walksafe-feature-policy-baseline-review-resolution-20260721-r001.json)",
        "- [정책 승인 기록](../control/baselines/walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json)",
        "- [정책 기준선 manifest](../control/baselines/walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json)",
        "- [FP-035 1.0.1 정정 후보](../control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.md) — 미승인·미효력",
        "- [257개 내용 준비도 재평가](../control/audits/walksafe-artifact-content-readiness-audit-20260722-r001.md) — 129개 후보/128개 대기, 승인 아님",
        "- [현재 OPEN 이슈·변경요청](01-management/project-control-registers.md#mgt-14)",
        "- [요구·설계·시험 교차추적 안내](traceability/README.md) — ID·경로·파일 지문 검사 결과의 쉬운 설명과 상세 기계용 JSON 링크를 제공하며 실제 시험 결과가 아님",
        "- [7~12 구조·정책 추적 안내](traceability/formal-7-12-integration-summary.md) — SEC~CLS의 Draft/Planned 경계와 정책·결정·gate·문서 장·파일 지문 검사 결과이며 실제 실행 결과가 아님",
        "",
        "## 범주별 문서",
        "",
        "`현재 문서`는 실제 파일과 SHA-256을 관리대장에 연결한 Draft/In Review만 보여줍니다.",
        "",
        "| 범주 | 쉬운 의미 | 전체 | Draft/In Review | Planned | 현재 문서 |",
        "|---|---|---:|---:|---:|---|",
    ]
    for category in CATEGORIES:
        rows = [row for row in register["artifacts"] if row["category"] == category]
        materialized = [
            row for row in rows if row["state"]["lifecycle_status"] in SAFE_DRAFT_LIFECYCLES
        ]
        paths = sorted(
            {row["location"]["canonical_path"] for row in materialized if row["location"]["canonical_path"]},
            key=lambda path: (ROOT_REVIEW_PATH_RANK.get(path, len(ROOT_REVIEW_PATH_RANK)), path),
        )
        links = "<br>".join(
            f"[{Path(path).name}]({_root_relative_link(path)})" for path in paths
        ) or "작성 예정"
        lines.append(
            f"| {category} | {CATEGORY_LABELS[category]} | {len(rows)} | {len(materialized)} | "
            f"{len(rows) - len(materialized)} | {links} |"
        )
    lines.extend(
        [
            "",
            "## 사람 검토 순서",
            "",
            "1. [통제 규정](00-control/document-control-manual.md)에서 상태·이름·승인·버전 규칙을 먼저 확인합니다.",
            "2. 현재 Draft는 DOC → MGT → DSC → REQ → DES → DEV → TST → SEC → AIML → REL → OPS → WS → CLS 순으로 읽습니다. 각 문서 안의 Planned/NOT_RUN 항목은 실행·서명·통제 증거를 만들 조건으로 확인합니다.",
            "3. [관리대장 HTML](00-control/artifact-register.html)에서 각 유형의 목적, 필수 내용, 입력과 완료 조건을 확인합니다.",
            "4. 틀린 정책·책임·경로·조건부 판정을 수정하고 생성기와 테스트를 다시 실행합니다.",
            "5. 사람 검토 기록이 끝난 문서만 별도 승인하고, manifest hash를 만든 뒤 Baselined로 바꿉니다.",
            "",
            "## 현재 닫히지 않은 gate",
            "",
            "| Gate | 상태 | 면제 |",
            "|---|---|---|",
        ]
    )
    for gate in register["remaining_gates"]:
        lines.append(f"| {gate['title']} | {gate['status']} | {'예' if gate['waived'] else '아니오'} |")
    lines.extend(
        [
            "",
            "## 생성·관리 구분",
            "",
            "- 이 최상위 README: 현재 어떤 정식 초안이 있는지 빠르게 찾는 탐색 화면",
            "- `00-control/README.md`: 257개 등록·40개 묶음·gate와 통제 파일을 자세히 보는 화면",
            "- `00-control/artifact-register.json`: 자동화가 읽는 DOC-01 정본 초안",
            "- `00-control/artifact-register.html`: 사람이 검색·필터링하는 파생 화면",
            "",
            "이 파일은 `scripts/build_walksafe_control_bootstrap.py`의 생성물입니다. 0~12 문서의 상태·경로·hash가 바뀔 때마다 이 생성기를 마지막으로 다시 실행해야 관리대장에 반영됩니다.",
            "",
        ]
    )
    return "\n".join(lines)


def _badge(value: str) -> str:
    css = value.lower().replace("_", "-")
    return f'<span class="badge {escape(css)}">{escape(value)}</span>'


def _artifact_location_href(path: str, anchor: Any) -> str:
    href = "../" + _root_relative_link(path)
    if (
        Path(path).suffix.lower() in {".md", ".html"}
        and isinstance(anchor, str)
        and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", anchor)
    ):
        href += f"#{anchor}"
    return href


def _html_text_list(values: list[str], empty_text: str) -> str:
    if not values:
        return f'<p class="muted">{escape(empty_text)}</p>'
    return "<ul>" + "".join(f"<li>{escape(value)}</li>" for value in values) + "</ul>"


def _html_code_list(values: list[str], empty_text: str) -> str:
    if not values:
        return f'<span class="muted">{escape(empty_text)}</span>'
    return " ".join(f"<code>{escape(value)}</code>" for value in values)


def build_html(register: dict[str, Any]) -> str:
    summary = register["summary"]
    category_options = "".join(
        f'<option value="{category}">{category} · {escape(CATEGORY_LABELS[category])}</option>'
        for category in CATEGORIES
    )
    bundle_options = "".join(
        f'<option value="{escape(bundle_id)}">{escape(bundle_id)}</option>'
        for bundle_id in BUNDLE_PATHS
    )
    rows = []
    for row in register["artifacts"]:
        contract = row["authoring_contract"]
        management = row["management_contract"]
        approved_trace = row["trace"]["approved_input_trace"]
        location = row["location"]
        path = location["canonical_path"]
        if path:
            href = _artifact_location_href(path, location["coverage_anchor"])
            location_html = (
                f'<a href="{escape(href, quote=True)}"><code>{escape(path)}</code></a>'
            )
            if location["coverage_anchor"]:
                location_html += f'<br><small>장: {escape(location["coverage_anchor"])}</small>'
        else:
            planned_path = location["planned_canonical_path"]
            if (REPO_ROOT / planned_path).is_file():
                href = _artifact_location_href(planned_path, location["coverage_anchor"])
                location_html = (
                    '<span class="muted">실제 산출물은 Planned/NOT_RUN</span><br>'
                    f'<a href="{escape(href, quote=True)}">작성·실행 계약 섹션</a><br>'
                    f'<small><code>{escape(planned_path)}</code></small>'
                )
            else:
                location_html = (
                    '<span class="muted">아직 파일 없음</span><br>'
                    f'<small>예정: <code>{escape(planned_path)}</code></small>'
                )
        contents = "".join(f"<li>{escape(item)}</li>" for item in contract["required_contents"])
        inputs = "".join(f"<li>{escape(item)}</li>" for item in contract["required_inputs"])
        completion = "".join(
            f"<li>{escape(item)}</li>" for item in contract["completion_criteria"]
        )
        update_triggers = _html_text_list(contract["update_triggers"], "정해진 갱신 조건 없음")
        upstream = _html_code_list(row["trace"]["upstream_types"], "직접 선행 유형 없음")
        downstream = _html_code_list(row["trace"]["downstream_types"], "직접 후행 유형 없음")
        reviewers = _html_text_list(
            row["responsibility"]["reviewer_roles"], "별도 검토 역할 없음"
        )
        policy_features = _html_code_list(
            approved_trace["feature_policy_ids"], "직접 연결 없음 — 상위 유형과 정책 기준선에서 상속"
        )
        policy_decisions = _html_code_list(
            approved_trace["decision_ids"], "직접 연결 없음 — 상위 유형과 정책 기준선에서 상속"
        )
        policy_gates = _html_code_list(approved_trace["gate_ids"], "직접 연결된 미실행 gate 없음")
        personal_data = _html_text_list(
            row["record_controls"]["personal_data"], "별도로 식별된 개인정보 원본 없음"
        )
        n_a_reason = (
            f"<br><small>{escape(row['n_a_reason'])}</small>" if row["n_a_reason"] else ""
        )
        search = " ".join(
            [
                row["display_code"],
                row["title"],
                contract["purpose"],
                row["bundle_id"],
                *row["trace"]["upstream_types"],
                *row["trace"]["downstream_types"],
                *approved_trace["feature_policy_ids"],
                *approved_trace["decision_ids"],
                row["authoring_readiness"]["readiness"],
            ]
        ).lower()
        rows.append(
            f"""
            <tr data-search="{escape(search, quote=True)}"
                data-category="{escape(row['category'])}"
                data-applicability="{escape(row['applicability'])}"
                data-bundle="{escape(row['bundle_id'])}"
                data-state="{escape(row['state']['lifecycle_status'])}">
              <td><strong>{escape(row['display_code'])}</strong><br><small>{escape(row['artifact_instance_id'])}</small></td>
              <td><strong>{escape(row['title'])}</strong><br><span class="muted">{escape(contract['purpose'])}</span>
                <details><summary>작성 계획 자세히 보기</summary>
                  <strong>포함할 내용</strong><ul>{contents}</ul>
                  <strong>작성할 때 확인할 입력</strong><ul>{inputs}</ul>
                  <strong>선행 산출물</strong><p>{upstream}</p>
                  <strong>후행 산출물</strong><p>{downstream}</p>
                  <strong>작성·검토·승인 역할</strong><p>작성: {escape(row['responsibility']['author_role'])} · 승인: {escape(row['responsibility']['canonical_approver_role'])}</p>{reviewers}
                  <strong>언제 갱신하는가</strong>{update_triggers}
                  <strong>완료로 인정할 조건</strong><ul>{completion}</ul>
                  <strong>검토 주기와 다음 검토일</strong><p>{escape(management['review_cadence'])}<br>다음 검토일: <code>{escape(row['dates']['next_review_at'])}</code><br>{escape(management['next_review_rule'])}</p>
                  <strong>변경·대체·폐기</strong><p>{escape(management['change_supersede_retire_method'])}</p>
                  <strong>승인 입력 연결</strong><p>정책 기능: {policy_features}<br>결정: {policy_decisions}<br>미실행 gate: {policy_gates}</p>
                  <strong>민감자료·보관 통제</strong>{personal_data}<p>등급: <code>{escape(row['record_controls']['confidentiality'])}</code><br>보존 profile: <code>{escape(row['record_controls']['retention_class'])}</code><br>{escape(row['record_controls']['raw_evidence_storage_rule'])}</p>
                </details>
              </td>
              <td>{_badge(row['applicability'])}<br><small>{escape(row['activation_result'])}</small>{n_a_reason}<details><summary>적용 조건</summary><small>{escape(row['activation_condition'])}</small></details></td>
              <td><code>{escape(row['bundle_id'])}</code><br><small>{escape(row['artifact_form'])}</small></td>
              <td>{escape(row['responsibility']['content_owner_role'])}<br><small>작성 배정: {escape(row['responsibility']['assigned_author'])}<br>승인 역할: {escape(row['responsibility']['canonical_approver_role'])}</small></td>
              <td>{_badge(row['state']['lifecycle_status'])}<br><small>{escape(row['state']['verification_status'])}<br>{escape(row['authoring_readiness']['readiness'])}</small></td>
              <td>{location_html}</td>
            </tr>"""
        )
    gate_items = "".join(
        f"<li><strong>{escape(gate['title'])}</strong> — {_badge(gate['status'])}</li>"
        for gate in register["remaining_gates"]
    )
    return f"""<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="icon" href="data:,">
  <title>WalkSafe 257개 전체 산출물 관리대장</title>
  <style>
    :root {{ color-scheme: light; --ink:#172033; --muted:#5d6678; --line:#d8deea; --bg:#f5f7fb; --card:#fff; --brand:#264cc8; --warn:#8a4b00; }}
    * {{ box-sizing:border-box; }} body {{ margin:0; color:var(--ink); background:var(--bg); font:15px/1.55 system-ui,-apple-system,"Noto Sans KR",sans-serif; }}
    a {{ color:var(--brand); }} a:hover {{ text-decoration-thickness:2px; }} a,input,select,summary {{ touch-action:manipulation; }} a:focus-visible,input:focus-visible,select:focus-visible,summary:focus-visible {{ outline:3px solid var(--brand); outline-offset:2px; }} .skip {{ position:absolute; left:-9999px; }} .skip:focus {{ left:1rem; top:1rem; padding:.6rem; background:#fff; z-index:10; }}
    header, main {{ width:min(1500px,calc(100% - 2rem)); margin:auto; }} header {{ padding:2.2rem 0 1rem; }} h1 {{ margin:.2rem 0; font-size:clamp(1.65rem,3vw,2.4rem); }} h1,h2 {{ text-wrap:balance; }} h2 {{ margin-top:2rem; }}
    .eyebrow {{ color:var(--brand); font-weight:750; }} .boundary {{ margin:1rem 0; padding:1rem 1.1rem; border:1px solid #f0c36d; border-left:5px solid #d47b00; border-radius:.6rem; background:#fff8e8; }}
    .cards {{ display:grid; grid-template-columns:repeat(6,minmax(120px,1fr)); gap:.75rem; }} .card {{ padding:1rem; background:var(--card); border:1px solid var(--line); border-radius:.7rem; }} .card b {{ display:block; font-size:1.55rem; font-variant-numeric:tabular-nums; }}
    .links {{ display:flex; gap:1rem; flex-wrap:wrap; margin:1rem 0; }} .filters {{ display:grid; grid-template-columns:2fr repeat(4,1fr); gap:.65rem; padding:1rem; background:var(--card); border:1px solid var(--line); border-radius:.7rem; }} label {{ font-weight:700; }} input,select {{ width:100%; margin-top:.25rem; min-height:2.7rem; padding:.55rem; border:1px solid #aeb7c7; border-radius:.4rem; background:#fff; color:var(--ink); font:inherit; }}
    .table-wrap {{ margin-top:1rem; overflow:auto; background:var(--card); border:1px solid var(--line); border-radius:.7rem; }} table {{ width:100%; min-width:1180px; border-collapse:collapse; }} th,td {{ padding:.75rem; border-bottom:1px solid var(--line); vertical-align:top; text-align:left; }} th {{ position:sticky; top:0; background:#eef2fb; z-index:1; }} tr:hover {{ background:#fafcff; }}
    code {{ overflow-wrap:anywhere; }} small,.muted {{ color:var(--muted); }} details {{ margin-top:.4rem; }} summary {{ cursor:pointer; color:var(--brand); }}
    .badge {{ display:inline-block; padding:.12rem .45rem; border-radius:999px; background:#e9edf5; font-size:.78rem; font-weight:800; }} .required,.in-review {{ background:#e4edff; color:#173b9d; }} .conditional,.not-run {{ background:#fff0cf; color:#704000; }} .draft {{ background:#eee8ff; color:#4c318f; }} .planned {{ background:#edf0f4; color:#3f4857; }}
    .gate-list {{ columns:2; padding-left:1.2rem; }} footer {{ margin:2rem 0; color:var(--muted); }}
    @media (max-width:900px) {{ .cards {{ grid-template-columns:repeat(2,1fr); }} .filters {{ grid-template-columns:1fr 1fr; }} .filters label:first-child {{ grid-column:1/-1; }} .gate-list {{ columns:1; }} }}
    @media (max-width:560px) {{ .cards,.filters {{ grid-template-columns:1fr; }} .filters label:first-child {{ grid-column:auto; }} }}
  </style>
</head>
<body>
  <a class="skip" href="#register">관리대장으로 건너뛰기</a>
  <header>
    <div class="eyebrow">DOC-01 · 정식 초안</div>
    <h1>WalkSafe 257개 전체 산출물 관리대장</h1>
    <p>비전공자도 무엇을 왜 만들고, 누가 검토하며, 지금 어디까지 왔는지 확인하는 사람용 화면입니다.</p>
    <div class="boundary"><strong>아직 승인·완료·출시 상태가 아닙니다.</strong> 새 일괄 내용 승인 후보는 {summary['content_approval_candidate_count']}개이고, 실제 증거·외부값 또는 실행 결과가 필요한 {summary['content_approval_pending_count']}개는 승인 후보가 아닙니다. 전체는 {summary['bundle_count']}개 문서 묶음으로 관리합니다. 남은 gate 5개는 모두 NOT_RUN이며 출시는 NOT_ELIGIBLE입니다.</div>
    <nav class="links" aria-label="관련 문서"><a href="../README.md">257개 전체 계획 입구</a><a href="document-control-manual.md">명명·검토·버전 규정</a><a href="artifact-register.json">기계용 관리대장 JSON</a><a href="artifact-change-log.json">변경 이력 JSON</a><a href="README.md">통제 상세 안내</a></nav>
    <div class="cards" aria-label="등록 요약">
      <div class="card"><span>전체 유형</span><b>{summary['artifact_type_count']}</b></div>
      <div class="card"><span>필수</span><b>{summary['required_count']}</b></div>
      <div class="card"><span>조건부</span><b>{summary['conditional_count']}</b></div>
      <div class="card"><span>문서 묶음</span><b>{summary['bundle_count']}</b></div>
      <div class="card"><span>Draft 연결 유형</span><b>{summary['materialized_artifact_count']}</b></div>
      <div class="card"><span>Planned</span><b>{summary['planned_artifact_count']}</b></div>
      <div class="card"><span>내용 승인 후보</span><b>{summary['content_approval_candidate_count']}</b></div>
      <div class="card"><span>증거·실행 대기</span><b>{summary['content_approval_pending_count']}</b></div>
    </div>
  </header>
  <main id="register">
    <h2>산출물 찾기</h2>
    <div class="filters">
      <label>검색<input id="search" name="search" type="search" autocomplete="off" placeholder="예: 객체 탐지, TST-23, 요구사항…"></label>
      <label>범주<select id="category" name="category"><option value="">전체</option>{category_options}</select></label>
      <label>적용성<select id="applicability" name="applicability"><option value="">전체</option><option>REQUIRED</option><option>CONDITIONAL</option></select></label>
      <label>묶음<select id="bundle" name="bundle"><option value="">전체</option>{bundle_options}</select></label>
      <label>상태<select id="state" name="state"><option value="">전체</option><option>PLANNED</option><option>DRAFT</option><option>IN_REVIEW</option></select></label>
    </div>
    <p id="resultCount" role="status" aria-live="polite">{summary['artifact_type_count']}개 표시 중</p>
    <div class="table-wrap">
      <table>
        <thead><tr><th>ID</th><th>무엇을 만드는가</th><th>필수 여부</th><th>문서 묶음</th><th>책임 역할</th><th>현재 상태</th><th>현재·예정 위치</th></tr></thead>
        <tbody id="artifactRows">{''.join(rows)}</tbody>
      </table>
    </div>
    <h2>출시 전에 닫아야 할 검증</h2>
    <ul class="gate-list">{gate_items}</ul>
    <footer>기준일 {AS_OF} · 관리대장 지문 <code>{escape(register['content_sha256'])}</code></footer>
  </main>
  <script>
    (() => {{
      const controls = ['search','category','applicability','bundle','state'].map(id => document.getElementById(id));
      const rows = [...document.querySelectorAll('#artifactRows tr')];
      const count = document.getElementById('resultCount');
      function filterRows() {{
        const [search,category,applicability,bundle,state] = controls.map(el => el.value.trim().toLowerCase());
        let visible = 0;
        for (const row of rows) {{
          const show = (!search || row.dataset.search.includes(search)) && (!category || row.dataset.category.toLowerCase() === category) && (!applicability || row.dataset.applicability.toLowerCase() === applicability) && (!bundle || row.dataset.bundle.toLowerCase() === bundle) && (!state || row.dataset.state.toLowerCase() === state);
          row.hidden = !show; if (show) visible += 1;
        }}
        count.textContent = `${{visible}}개 표시 중`;
      }}
      controls.forEach(control => control.addEventListener(control.tagName === 'INPUT' ? 'input' : 'change', filterRows));
    }})();
  </script>
</body>
</html>
"""


def build_outputs() -> dict[Path, str]:
    catalog = load_strict_json(CATALOG_PATH)
    manifest = load_strict_json(POLICY_MANIFEST_PATH)
    load_strict_json(APPROVAL_PATH)
    _validate_policy_manifest(manifest)
    _validate_human_sources()
    register = build_register(catalog, manifest)
    change_log = build_change_log(manifest, register)
    change_log_text = _json_text(change_log)
    doc05 = next(row for row in register["artifacts"] if row["display_code"] == "DOC-05")
    doc05["integrity"]["sha256"] = _text_sha256(change_log_text)
    register.pop("content_sha256")
    register["content_sha256"] = _object_sha256(register)
    _require(
        build_change_log(manifest, register) == change_log,
        "DOC-05 hash binding unexpectedly changes the change log",
    )
    return {
        REGISTER_PATH: _json_text(register),
        CHANGE_LOG_PATH: change_log_text,
        README_PATH: build_readme(register),
        HTML_PATH: build_html(register),
        ROOT_README_PATH: build_root_readme(register),
    }


def write_or_check(outputs: dict[Path, str], check: bool) -> None:
    stale: list[str] = []
    for path, content in outputs.items():
        if check:
            if not path.is_file() or path.read_text(encoding="utf-8") != content:
                stale.append(_relative(path))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
    if stale:
        raise ControlBootstrapError("generated output is missing or stale: " + ", ".join(stale))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify outputs without writing")
    args = parser.parse_args()
    try:
        outputs = build_outputs()
        write_or_check(outputs, args.check)
    except (ControlBootstrapError, OSError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    action = "verified" if args.check else "generated"
    register = load_strict_json(REGISTER_PATH)
    summary = register["summary"]
    print(
        f"PASS: {action} DOC-01~CLS-16 master register; "
        f"artifacts={summary['artifact_type_count']}, required={summary['required_count']}, "
        f"conditional={summary['conditional_count']}, bundles={summary['bundle_count']}, "
        f"materialized={summary['materialized_artifact_count']}, planned={summary['planned_artifact_count']}, "
        "gates=5 NOT_RUN, release=NOT_ELIGIBLE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
