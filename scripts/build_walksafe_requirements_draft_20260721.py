#!/usr/bin/env python3
"""Build the formal WalkSafe requirements Draft from the approved policy baseline."""

from __future__ import annotations

import argparse
import copy
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any, Iterable

try:
    from scripts import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder
    from scripts import build_walksafe_design_deliverables_20260721 as design_builder
except ModuleNotFoundError:  # Direct execution from scripts/.
    import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder
    import build_walksafe_design_deliverables_20260721 as design_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
CONTROL_DIR = REPO_ROOT / "docs" / "control"
INTERVIEW_DIR = CONTROL_DIR / "decision-interview"
BASELINE_DIR = CONTROL_DIR / "baselines"
OUTPUT_DIR = REPO_ROOT / "docs" / "deliverables" / "03-requirements"
MANIFEST_DIR = REPO_ROOT / "docs" / "deliverables" / "manifests"

POLICY_PATH = INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-draft.json"
APPROVAL_PATH = BASELINE_DIR / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
POLICY_MANIFEST_PATH = BASELINE_DIR / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
RESOLUTION_PATH = INTERVIEW_DIR / "walksafe-feature-policy-baseline-review-resolution-20260721-r001.json"
ALIGNMENT_PATH = INTERVIEW_DIR / "walksafe-effective-decision-register-aligned-20260721-r001.json"
OWNER_REVIEW_PATH = INTERVIEW_DIR / "source-records" / "walksafe-feature-policy-comprehensive-review-20260719-answers.json"
FP035_CORRECTION_CANDIDATE_PATH = INTERVIEW_DIR / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
ARTIFACT_CATALOG_PATH = CONTROL_DIR / "artifact-types.json"
RUNTIME_ANALYSIS_PATH = CONTROL_DIR / "questionnaire" / "walksafe-integrated-baseline-analysis.json"
PLANNED_TEST_CATALOG_PATH = REPO_ROOT / "docs" / "deliverables" / "06-testing" / "registers" / "test-cases.json"
TRACE_INTEGRATION_REPORT_PATH = REPO_ROOT / "docs" / "deliverables" / "traceability" / "req-des-tst-integration-report-20260721-r001.json"

SYSTEM_PATH = OUTPUT_DIR / "system-requirements.md"
ACCEPTANCE_PATH = OUTPUT_DIR / "acceptance-specification.md"
TRACE_PATH = OUTPUT_DIR / "requirements-traceability.md"
RTM_JSON_PATH = OUTPUT_DIR / "rtm.json"
RTM_HTML_PATH = OUTPUT_DIR / "rtm.html"
CHANGE_LOG_PATH = OUTPUT_DIR / "requirement-change-log.json"
GLOSSARY_PATH = OUTPUT_DIR / "glossary.json"
MANIFEST_PATH = MANIFEST_DIR / "requirements-draft-20260721-r001.json"

OUTPUT_PATHS = (
    SYSTEM_PATH,
    ACCEPTANCE_PATH,
    TRACE_PATH,
    RTM_JSON_PATH,
    RTM_HTML_PATH,
    CHANGE_LOG_PATH,
    GLOSSARY_PATH,
    MANIFEST_PATH,
)

REQ_BUNDLES = {
    "BND-REQ-BASELINE": {
        "path": "docs/deliverables/03-requirements/system-requirements.md",
        "artifact_type_ids": [
            "DLV-REQ-01", "DLV-REQ-02", "DLV-REQ-03", "DLV-REQ-04",
            "DLV-REQ-07", "DLV-REQ-08", "DLV-REQ-09", "DLV-REQ-10",
            "DLV-REQ-11", "DLV-REQ-12", "DLV-REQ-13", "DLV-REQ-14", "DLV-REQ-15",
        ],
    },
    "BND-REQ-ACCEPTANCE": {
        "path": "docs/deliverables/03-requirements/acceptance-specification.md",
        "artifact_type_ids": ["DLV-REQ-05", "DLV-REQ-06"],
    },
    "BND-REQ-TRACE": {
        "path": "docs/deliverables/03-requirements/requirements-traceability.md",
        "artifact_type_ids": ["DLV-REQ-16", "DLV-REQ-17", "DLV-REQ-18", "DLV-REQ-19"],
    },
}
ALL_ARTIFACT_TYPE_IDS = [f"DLV-REQ-{number:02d}" for number in range(1, 20)]
DISPLAY_ARTIFACT_TYPE_IDS = [f"REQ-{number:02d}" for number in range(1, 20)]
AS_OF = "2026-07-22"
VERSION = "0.2.0"
FP035_NETWORK_ISSUE_ID = "ISS-POLICY-FP035-NETWORK-001"
FP035_NORMALIZATION_ID = "DEC-FP035-NETWORK-NORMALIZATION-20260722"
FP035_CORRECTION_CANDIDATE_ID = "WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001"
FP035_REQUIRED_ACTIVATION_EVENT = "EXACT_NEW_BUNDLED_OWNER_APPROVAL_STATEMENT"
FP035_APPROVAL_BLOCKERS = [FP035_CORRECTION_CANDIDATE_ID, FP035_REQUIRED_ACTIVATION_EVENT]
FP035_NORMALIZED_RULE = (
    "일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 선택한 경우 보행이 정지한 "
    "뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다."
)
FP035_NETWORK_CLARIFICATION = {
    "issue_id": FP035_NETWORK_ISSUE_ID,
    "severity": "HIGH",
    "status": "CORRECTION_CANDIDATE_BOUND_NOT_APPROVED_NOT_EFFECTIVE",
    "normalization_id": FP035_NORMALIZATION_ID,
    "correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
    "correction_candidate_approval_status": "NOT_APPROVED",
    "correction_candidate_effective_status": "NOT_EFFECTIVE",
    "source_policy_ids": ["FP-035", "SP-13", "CD-UPLOAD-NETWORK"],
    "affected_requirement_ids": ["RQ-FP-035-001"],
    "source_conflicting_clauses_preserved": [
        "FP-035.start_conditions[1]: Wi-Fi 또는 사용자가 명시적으로 선택한 이동통신망",
        "FP-035.normal_flow[4]·design_rules[1]: 정지 상태와 Wi-Fi를 모두 요구",
    ],
    "normalized_rule": FP035_NORMALIZED_RULE,
    "source_answer_path": "docs/control/decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json",
    "source_answer_refs": ["shared_policy_reviews.SP-13", "feature_reviews.FP-035"],
    "correction_candidate_path": "docs/control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json",
    "policy_baseline_original_mutated": False,
    "draft_effect": "REQ-03·REQ-06과 RTM Draft는 정정 후보의 네 분기를 사용한다. 후보와 영향 산출물이 새 묶음으로 승인되기 전에는 정책 효력·시험 통과를 주장하지 않는다.",
}

FP035_EXPECTED_BRANCHES = [
    {
        "branch_id": "FP035-NET-01",
        "walking_state": "WALKING",
        "wifi_available": None,
        "mobile_network_opt_in": None,
        "expected_transfer": "BLOCKED",
        "easy_explanation": "걷는 동안에는 Wi-Fi나 이동통신망이 있어도 일반 활동원본을 보내지 않는다.",
    },
    {
        "branch_id": "FP035-NET-02",
        "walking_state": "STATIONARY",
        "wifi_available": True,
        "mobile_network_opt_in": None,
        "expected_transfer": "WIFI_ALLOWED",
        "easy_explanation": "정지했고 Wi-Fi가 있으면 이동통신망 선택 여부와 관계없이 Wi-Fi로 보낸다.",
    },
    {
        "branch_id": "FP035-NET-03",
        "walking_state": "STATIONARY",
        "wifi_available": False,
        "mobile_network_opt_in": True,
        "expected_transfer": "APPROVED_MOBILE_NETWORK_ALLOWED",
        "easy_explanation": "정지했고 Wi-Fi가 없더라도 사용자가 이동통신망 전송을 명시적으로 선택했다면 허용된 이동통신망으로 보낸다.",
    },
    {
        "branch_id": "FP035-NET-04",
        "walking_state": "STATIONARY",
        "wifi_available": False,
        "mobile_network_opt_in": False,
        "expected_transfer": "QUEUED_UNTIL_WIFI",
        "easy_explanation": "이동통신망 전송을 선택하지 않았다면 정지 상태에서도 Wi-Fi가 생길 때까지 암호화해 보관한다.",
    },
]

DOMAIN_TITLES = {
    "DLV-REQ-07": "외부·내부 인터페이스 요구사항",
    "DLV-REQ-08": "데이터 요구사항",
    "DLV-REQ-09": "보안 요구사항",
    "DLV-REQ-10": "개인정보 요구사항",
    "DLV-REQ-11": "접근성 요구사항",
    "DLV-REQ-12": "성능·용량 요구사항",
    "DLV-REQ-13": "가용성·복구 요구사항",
    "DLV-REQ-14": "호환성·지원환경 요구사항",
    "DLV-REQ-15": "법률·라이선스·규제 요구사항",
}

DOMAIN_COMPLETION_GUIDANCE = {
    "DLV-REQ-07": """
### 인터페이스 계약에 반드시 적을 항목

| 제공자 → 소비자 | 계약 단위 | 인증·중복방지 | 실패 시 사용자 안전동작 |
|---|---|---|---|
| Android 사용자 앱 → gateway | 계정·동의·세션·TMAP 검색/경로·신고·원본 조각·삭제요청 API | 사용자/기기 session, 요청 ID, 변경 요청의 idempotency key | timeout이나 상태불명은 성공으로 보지 않고 상태조회 또는 안전정지 |
| Android 관리자 앱 → gateway | 신고 검토·기관전달·상태·감사·용량조회 API | 관리자 MFA/패스키, 역할, 민감작업 재인증 | 관리자 기능만 중지하고 사용자 안전 API 자원과 격리 |
| backend → TMAP | 검색·보행경로 요청/응답 | 서버 보관 key, quota, 상관 ID | 과거 응답이나 직선 추정 금지; 길안내 일시중지 |
| backend → DB/object storage | metadata transaction, 원본 조각, digest receipt, 삭제·복원 상태 | 좁은 service identity, 원본 ID와 digest | DB·파일 일부 성공을 완료로 표시하지 않고 reconciliation |

각 API는 method/path, request·response schema, 인증 audience, 오류 code, idempotency, timeout·retry, version 호환과 quota를 DES-09·10에 고정해야 합니다. 실제 timeout·quota 수치는 공급자 계약·부하시험 전까지 `TBD-MEASUREMENT`로 남기며 임의 PASS 기준을 만들지 않습니다.
""",
    "DLV-REQ-08": """
### 데이터 항목을 정의하는 최소 열

모든 데이터 항목은 `이름·뜻·형식·단위·필수 여부·생성자·소유자·민감도·검증규칙·보존기간·삭제조건·schema version`을 가져야 합니다. 핵심 묶음은 계정·동의·기기 session, 보행 session, TMAP 검색·경로, 영상·음성·위치·센서 원본, 탐지·위험판정, 자동신고, 전송 조각·digest receipt, 학습자료 lineage, 감사·삭제영수증입니다. 실제 SQL 형식과 제약은 DES-11·12에서 고정하고 migration으로만 바꿉니다.
""",
    "DLV-REQ-09": """
### 위협→통제→확인 연결

| 위협 | 필수 통제 | 확인 방법(아직 실행 전) |
|---|---|---|
| 계정·관리자 위조 | 기기별 session, 관리자 MFA/패스키, 원격폐기, 역할·resource 검사 | 인증 우회·분실기기·권한상승 시험 |
| 원본·모델·요청 변조 | TLS, digest receipt, 서명·version 결속, 중복방지 ID | 변조·재전송·부분성공 시험 |
| 민감정보 노출 | 앱 전용 암호화, 비공개 object, 최소권한, log redaction, 접근감사 | secret/SAST/DAST/권한·원본 export 검토 |
| 서비스 마비·용량고갈 | 안전 API 격리, rate limit, bounded queue, backpressure·safe stop | 부하·quota·offline·용량 상태 시험 |

보안 요구의 완료는 통제 문장만으로 판정하지 않고 SEC 위험 ID와 계획 시험·결과를 RTM에 연결했을 때만 판단합니다.
""",
    "DLV-REQ-10": """
### 개인정보 처리 목적별 요구

| 목적 | 자료 | 근거·고지 | 권리·삭제기한 |
|---|---|---|---|
| 보행·길안내 | 영상·음성·정확 위치·센서·경로·판정 원본 | 최초 화면의 명시적 설명·동의; 독립검토 gate 유지 | 전체 삭제요청 즉시 새 수집·전송 중지, 휴대전화 24시간·서버 원본 7일 |
| 자동신고 | 원본 사진·정확 위치·판정근거 | 자동신고 기능·자료·전송시점·보존기간을 최초 동의에서 분리해 설명 | 기능을 끄면 미전송 24시간, 서버 저장본 삭제요청 7일 |
| 모델개선 | 승인 원본·라벨·가공본·lineage | 승인된 수집 목적과 dataset 승격기록 | 데이터셋·라벨·가공본 30일 안에 제외, 백업 최대 35일 |
| 삭제 증명 | 원본 없는 식별값 hash·처리시각·결과 | 권리요청 이행·감사 | 3년 보존 뒤 삭제 |

법적 근거의 최종 적합성, 주변인 자료, 제3자 제공·국외이전은 독립 법률·개인정보 검토 결과가 필요하며 이 Draft가 검토 완료를 주장하지 않습니다.
""",
    "DLV-REQ-11": """
### 접근성 적용·확인 범위

가입·로그인·동의·권한·기기점검·안전연습·장애·설정·삭제요청 화면은 TalkBack만으로 제목, 상태, 입력, 오류와 결과를 논리적 순서로 이해하고 완료할 수 있어야 합니다. 정상 보행은 화면 터치를 요구하지 않고 짧은 한국어 TTS와 구분 가능한 진동을 사용합니다. 큰 글자·확대·충분한 대비·큰 조작영역·키보드/스위치 입력은 화면별 인수조건에 연결하며 실제 TalkBack·기기·대상 사용자 시험 전에는 충족으로 표시하지 않습니다.
""",
    "DLV-REQ-12": """
### 정량값을 정하는 방법

카메라→위험안내 지연, 처리율, 배터리·온도, STT/TTS 지연, GPS·TMAP 지연, API 처리량, 단말 queue byte, 동시사용자와 저장비를 각각 측정합니다. 서버 용량 정책값(주 원본 300 GiB, 백업 300 GiB, 70/85/95/100%, 월 저장비 30,000원)은 승인 정책값입니다. 나머지 성능 합격선은 지원 기기와 통제환경 측정 전 `TBD-MEASUREMENT`이며, 측정 방법·기기·표본·산식·판정자를 함께 기록해야 합니다.
""",
    "DLV-REQ-13": """
### 가용성·복구 기준의 현재 경계

정식 운영 전에는 서비스별 SLI, 관측기간, SLO, RTO, RPO와 오류예산을 실제 영향·복원훈련·비용 측정으로 정합니다. 현재 확정된 것은 장애 때 추정 안내를 계속하지 않는 안전정지, 35일 순환 백업, 삭제목록 선적용, 복구 후 사용자 확인, 미실행 5개 gate입니다. 수치가 없다는 이유로 임의 기본값을 승인값처럼 채우지 않습니다.
""",
    "DLV-REQ-14": """
### 공식 지원환경과 제외환경

정식 제품은 Android 사용자 앱과 별도 Android 관리자 앱이며 Web/PWA·iOS·모바일 브라우저는 출시 범위가 아닙니다. 공식 하한 후보는 Android 12(API 31) 이상이지만, 시작 시 카메라·위치·음성·거리 기능 검사와 이름 붙인 실제 기기 matrix를 모두 통과해야 지원 기기로 올립니다. 거리 기능이 없는 기기는 정책에 따른 제한기능만 제공하고 같은 전체 기능을 주장하지 않습니다. 최소 OS·기기 목록의 최종 승격은 TST-03·13·WS-09·17 결과로 갱신합니다.
""",
    "DLV-REQ-15": """
### 법률·라이선스·외부의무 관리

한국 대상 Android 정식 운영을 기준으로 개인정보·위치·통신·접근성 고지, Google Play 정책, TMAP·Google Cloud 계약/쿼터, Android·서버·모델·데이터셋 오픈소스 및 데이터 사용권을 목록화합니다. 각 항목은 적용 지역·버전·의무·담당자·검토일·증거·불일치 조치가 있어야 합니다. `research/fair use` 같은 과거 답변만으로 정식 운영 권리를 확정하지 않으며, 전문가 검토가 필요한 항목은 SEC-05와 출시 gate가 닫힐 때까지 `PENDING_EXTERNAL_REVIEW`입니다.
""",
}

COMMON_DOMAINS = {
    "NPC-RAW-ORIGINAL-COLLECTION": ["DLV-REQ-08", "DLV-REQ-10", "DLV-REQ-15"],
    "NPC-DATA-LIFECYCLE": ["DLV-REQ-08", "DLV-REQ-09", "DLV-REQ-10", "DLV-REQ-15"],
    "NPC-SERVER-STORAGE-CAPACITY": ["DLV-REQ-08", "DLV-REQ-12", "DLV-REQ-13"],
    "NPC-PHONE-QUEUE-CAPACITY": ["DLV-REQ-08", "DLV-REQ-12", "DLV-REQ-13"],
    "NPC-AUTO-REPORT": ["DLV-REQ-07", "DLV-REQ-08", "DLV-REQ-10", "DLV-REQ-13"],
    "NPC-PERMISSION-SESSION-LIFECYCLE": ["DLV-REQ-09", "DLV-REQ-10", "DLV-REQ-13"],
    "NPC-NAVIGATION-ROUTE-DIRECTION": ["DLV-REQ-07", "DLV-REQ-11", "DLV-REQ-14"],
    "NPC-SINGLE-ADMIN-RECOVERY": ["DLV-REQ-09", "DLV-REQ-13"],
    "NPC-SERVER-CAPACITY-STATE-SYNC": ["DLV-REQ-07", "DLV-REQ-12", "DLV-REQ-13"],
}

AREA_DOMAINS = {
    "FA-01": ["DLV-REQ-15"],
    "FA-02": ["DLV-REQ-07", "DLV-REQ-11", "DLV-REQ-14"],
    "FA-03": ["DLV-REQ-07", "DLV-REQ-14"],
    "FA-04": ["DLV-REQ-09", "DLV-REQ-10", "DLV-REQ-13"],
    "FA-05": ["DLV-REQ-08", "DLV-REQ-09", "DLV-REQ-10", "DLV-REQ-15"],
    "FA-06": ["DLV-REQ-07", "DLV-REQ-11", "DLV-REQ-14"],
    "FA-07": ["DLV-REQ-07", "DLV-REQ-08", "DLV-REQ-12", "DLV-REQ-13"],
    "FA-08": ["DLV-REQ-07", "DLV-REQ-08", "DLV-REQ-12"],
    "FA-09": ["DLV-REQ-07", "DLV-REQ-11", "DLV-REQ-14"],
    "FA-10": ["DLV-REQ-07", "DLV-REQ-11", "DLV-REQ-14"],
    "FA-11": ["DLV-REQ-07", "DLV-REQ-08", "DLV-REQ-10", "DLV-REQ-13"],
    "FA-12": ["DLV-REQ-07", "DLV-REQ-08", "DLV-REQ-09", "DLV-REQ-10", "DLV-REQ-15"],
    "FA-13": ["DLV-REQ-07", "DLV-REQ-08", "DLV-REQ-12", "DLV-REQ-15"],
    "FA-14": ["DLV-REQ-07", "DLV-REQ-08", "DLV-REQ-12"],
    "FA-15": ["DLV-REQ-07", "DLV-REQ-08", "DLV-REQ-12", "DLV-REQ-13"],
    "FA-16": ["DLV-REQ-08", "DLV-REQ-09", "DLV-REQ-10", "DLV-REQ-15"],
    "FA-17": ["DLV-REQ-07", "DLV-REQ-11", "DLV-REQ-14", "DLV-REQ-15"],
    "FA-18": ["DLV-REQ-07", "DLV-REQ-08", "DLV-REQ-09", "DLV-REQ-10", "DLV-REQ-12", "DLV-REQ-13", "DLV-REQ-15"],
}


class RequirementsDraftError(ValueError):
    """Raised when the requirements Draft cannot be trusted."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise RequirementsDraftError(message)


def _object_sha256(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _bytes_sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _file_sha256(path: Path) -> str:
    return _bytes_sha256(path.read_bytes())


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _text_bytes(value: str) -> bytes:
    return (value.rstrip() + "\n").encode("utf-8")


def _unique(values: Iterable[str]) -> list[str]:
    return list(dict.fromkeys(value for value in values if value))


def _repo_path(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _source_binding(path: Path, relation: str) -> dict[str, Any]:
    payload = path.read_bytes()
    return {
        "path": _repo_path(path),
        "sha256": _bytes_sha256(payload),
        "byte_length": len(payload),
        "relation": relation,
    }


def _requirement_id(source_id: str) -> str:
    return f"RQ-{source_id}-001"


def _acceptance_id(source_id: str, index: int) -> str:
    return f"AC-{source_id}-{index:02d}"


def _test_id(source_id: str, index: int) -> str:
    return f"TC-{source_id}-{index:02d}"


def _clause_id(requirement_id: str, kind: str, index: int) -> str:
    return f"{requirement_id}-{kind}-{index:02d}"


def _load_json(path: Path) -> dict[str, Any]:
    _require(path.is_file(), f"required source is missing: {_repo_path(path)}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RequirementsDraftError(f"invalid JSON source: {_repo_path(path)}") from exc
    _require(isinstance(value, dict), f"source root must be an object: {_repo_path(path)}")
    return value


def _validate_owner_fp035_answer(owner_review: dict[str, Any]) -> None:
    shared_note = owner_review.get("shared_policy_reviews", {}).get("SP-13", {}).get("note", "")
    feature_note = owner_review.get("feature_reviews", {}).get("FP-035", {}).get("note", "")
    for label, note in (("SP-13", shared_note), ("FP-035", feature_note)):
        _require("이동통신망" in note and "Wi-Fi" in note, f"{label} owner answer does not bind the network choice")
    _require("이동통신망 사용을 선택하지 않은" in shared_note, "SP-13 no-mobile-choice meaning differs")
    _require("모바일 데이터로 전송하지 않는다" in shared_note, "SP-13 mobile-network prohibition differs")
    _require("일반 활동원본은 정지 상태" in feature_note, "FP-035 stationary-transfer rule differs")


def _validate_fp035_correction_candidate(candidate: dict[str, Any]) -> None:
    metadata = candidate.get("metadata", {})
    correction = candidate.get("correction", {})
    boundary = candidate.get("authorization_boundary", {})
    _require(candidate.get("schema_version") == "walksafe.feature-policy-correction-candidate.v1", "FP-035 correction candidate schema differs")
    _require(metadata.get("candidate_id") == FP035_CORRECTION_CANDIDATE_ID, "FP-035 correction candidate ID differs")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "FP-035 correction candidate must remain NOT_APPROVED")
    _require(metadata.get("effective_status") == "NOT_EFFECTIVE", "FP-035 correction candidate must remain NOT_EFFECTIVE")
    _require(correction.get("feature_id") == "FP-035", "FP-035 correction candidate feature differs")
    _require(correction.get("normative_rule") == FP035_NORMALIZED_RULE, "FP-035 correction candidate normative rule differs")
    _require(
        correction.get("affected_artifact_codes") == ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"],
        "FP-035 correction candidate artifact scope differs",
    )
    _require(candidate.get("base_policy", {}).get("bytes_modified_by_this_candidate") is False, "FP-035 candidate mutates policy 1.0.0")
    _require(boundary.get("new_product_question_required") is False, "FP-035 candidate unexpectedly requires a new owner question")
    _require(boundary.get("candidate_generation_is_approval") is False, "FP-035 candidate claims approval")
    _require(boundary.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "FP-035 activation event differs")
    _require(boundary.get("authoring_and_planning_allowed_before_activation") is True, "FP-035 candidate blocks authoring")
    _require(boundary.get("mobile_network_branch_implementation_status") == "FROZEN_PENDING_EXACT_BUNDLED_APPROVAL", "FP-035 implementation freeze differs")
    _require(boundary.get("mobile_network_branch_formal_test_status") == "NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL", "FP-035 formal-test freeze differs")
    _require(correction.get("change_control_refs") == ["CR-0002", FP035_NETWORK_ISSUE_ID, "RAID-011"], "FP-035 change-control refs differ")
    required_related = {item.get("artifact_code"): item.get("path") for item in correction.get("required_related_records", [])}
    _require(required_related.get("REQ-18") == _repo_path(CHANGE_LOG_PATH), "FP-035 REQ-18 tracking path differs")


def _normalize_fp035_feature(feature: dict[str, Any]) -> dict[str, Any]:
    normalized = copy.deepcopy(feature)
    normalized["effective_policy_summary"] = FP035_NORMALIZED_RULE + (
        " 전송할 수 없는 자료는 사용자에게 개별 알림을 보내지 않고 암호화 대기열에 최대 30일 보관하며, "
        "움직임이 다시 시작되면 새 조각 전송을 즉시 멈춘다."
    )
    normalized["policy_state"] = {
        **normalized["policy_state"],
        "requirements_normalization_status": "CORRECTION_CANDIDATE_APPLIED_TO_DRAFT_NOT_EFFECTIVE",
        "requirements_normalization_id": FP035_NORMALIZATION_ID,
        "correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
        "correction_candidate_approval_status": "NOT_APPROVED",
        "correction_candidate_effective_status": "NOT_EFFECTIVE",
    }
    normalized["start_conditions"] = [
        "암호화된 미전송 원본이 있고 여러 움직임 정보가 안정적으로 정지를 가리킨다.",
        "Wi-Fi가 연결됐거나 사용자가 이동통신망 전송을 명시적으로 선택했고, 배터리·저장공간·안전기능 자원이 충분하다.",
    ]
    normalized["inputs"] = [
        "암호화된 미전송 자료 조각과 전송 상태",
        "위치·속도·휴대전화 움직임이 보여주는 이동·정지 상태",
        "Wi-Fi 연결 여부와 사용자의 이동통신망 전송 선택 상태",
        "충전 여부, 배터리와 저장공간 상태",
        "서버가 이미 받은 자료 조각과 이어 보내기 위치",
    ]
    normalized["normal_flow"] = [
        "보행 상태가 WALKING이면 네트워크 종류와 관계없이 일반 활동원본 전송을 시작하지 않는다.",
        "여러 움직임 정보가 안정적으로 정지를 가리킬 때만 STATIONARY로 판정한다.",
        "STATIONARY이고 Wi-Fi가 있으면 Wi-Fi로 전송한다.",
        "STATIONARY이고 Wi-Fi가 없으면 이동통신망 전송을 명시적으로 선택한 사용자에게만 허용된 이동통신망으로 전송한다.",
        "이동통신망 전송을 선택하지 않은 사용자는 Wi-Fi가 생길 때까지 암호화 대기열에 보관한다.",
        "움직임이 다시 감지되면 새 자료 조각 전송을 즉시 막고 서버가 온전히 받은 마지막 조각 다음부터 나중에 이어 보낸다.",
        "서버는 모든 조각과 파일 지문이 맞기 전에는 완료로 확정하지 않는다.",
        "신고 자료와 일반 활동원본의 보관공간과 전송 순서를 분리해 신고 자료를 우선 보호한다.",
    ]
    normalized["design_rules"] = [
        FP035_NORMALIZED_RULE,
        "WALKING·STATIONARY, Wi-Fi 가용 여부, 이동통신망 전송 선택 여부를 서로 다른 상태값으로 기록한다.",
        "이동통신망 전송 선택은 명시적으로 저장된 현재 선택값이 있을 때만 참으로 보며, 없거나 읽을 수 없으면 미선택으로 처리한다.",
        "움직임이 다시 감지되면 새 자료 전송을 즉시 막고 진행 중 자료를 안전하게 중단한다.",
        "서버는 모든 조각과 파일 지문이 맞기 전에는 완료로 확정하지 않는다.",
        "신고 자료와 일반 활동원본의 보관공간과 전송 순서를 분리해 신고 자료를 우선 보호한다.",
    ]
    normalized["prohibited_behaviors"] = [
        "보행 중 일반 활동원본을 새로 서버로 보내지 않는다.",
        "이동통신망 전송을 명시적으로 선택하지 않은 사용자의 자료를 모바일 데이터로 보내지 않는다.",
        "Wi-Fi가 없다는 이유만으로 이동통신망 선택 상태를 자동으로 바꾸지 않는다.",
        "미완료 자료를 서버에서 완전한 학습자료로 표시하지 않는다.",
        "저장공간 부족 때 미전송 신고를 일반 학습자료보다 먼저 지우지 않는다.",
    ]
    normalized["execution_boundary"] = {
        **normalized["execution_boundary"],
        "external": ["Wi-Fi", "사용자가 명시적으로 선택한 경우에만 허용되는 이동통신망"],
    }
    normalized["normalization"] = {
        "normalization_id": FP035_NORMALIZATION_ID,
        "correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
        "correction_candidate_approval_status": "NOT_APPROVED",
        "correction_candidate_effective_status": "NOT_EFFECTIVE",
        "source_answer_refs": ["SP-13", "FP-035"],
        "normalized_rule": FP035_NORMALIZED_RULE,
        "network_branches": copy.deepcopy(FP035_EXPECTED_BRANCHES),
    }
    return normalized


def _normalized_policy(
    policy: dict[str, Any],
    owner_review: dict[str, Any],
    correction_candidate: dict[str, Any],
) -> dict[str, Any]:
    _validate_owner_fp035_answer(owner_review)
    _validate_fp035_correction_candidate(correction_candidate)
    normalized = copy.deepcopy(policy)
    index = next(index for index, item in enumerate(normalized["features"]) if item["id"] == "FP-035")
    normalized["features"][index] = _normalize_fp035_feature(normalized["features"][index])
    return normalized


def _validate_artifact_catalog(catalog: dict[str, Any]) -> dict[str, dict[str, Any]]:
    entries = catalog.get("artifact_types")
    _require(isinstance(entries, list), "artifact catalog has no artifact_types array")
    req_entries = {entry.get("type_code"): entry for entry in entries if entry.get("type_code") in ALL_ARTIFACT_TYPE_IDS}
    _require(set(req_entries) == set(ALL_ARTIFACT_TYPE_IDS), "artifact catalog must contain REQ-01..REQ-19 exactly")
    expected_bundles = {
        artifact_id: bundle_id
        for bundle_id, bundle in REQ_BUNDLES.items()
        for artifact_id in bundle["artifact_type_ids"]
    }
    for artifact_id, entry in req_entries.items():
        _require(entry.get("display_code") == artifact_id.removeprefix("DLV-"), f"{artifact_id} display code differs")
        _require(entry.get("recommended_bundle_id") == expected_bundles[artifact_id], f"{artifact_id} bundle differs")
        _require(isinstance(entry.get("required_contents"), list) and entry["required_contents"], f"{artifact_id} contents missing")
    return req_entries


def _artifact_management_plan(artifact_entries: dict[str, dict[str, Any]]) -> dict[str, dict[str, Any]]:
    path_by_type = {
        artifact_type_id: bundle["path"]
        for bundle in REQ_BUNDLES.values()
        for artifact_type_id in bundle["artifact_type_ids"]
    }
    supporting_paths = {
        "DLV-REQ-16": [_repo_path(RTM_JSON_PATH), _repo_path(RTM_HTML_PATH)],
        "DLV-REQ-18": [_repo_path(CHANGE_LOG_PATH)],
        "DLV-REQ-19": [_repo_path(GLOSSARY_PATH)],
    }
    plan: dict[str, dict[str, Any]] = {}
    for artifact_type_id in ALL_ARTIFACT_TYPE_IDS:
        entry = artifact_entries[artifact_type_id]
        display_code = entry["display_code"]
        plan[display_code] = {
            "artifact_type_id": artifact_type_id,
            "display_code": display_code,
            "title": entry["title"],
            "purpose": entry["purpose"],
            "applicability": entry["default_applicability"],
            "activation_condition": entry["activation_condition"],
            "required_contents": entry["required_contents"],
            "required_inputs": entry["required_inputs"],
            "upstream_types": entry["upstream_types"],
            "downstream_types": entry["downstream_types"],
            "owner_role": entry["owner_role"],
            "reviewer_roles": entry["reviewer_roles"],
            "approver_role": entry["approver_role"],
            "recommended_form": entry["recommended_form"],
            "canonical_location": path_by_type[artifact_type_id],
            "coverage_anchor": display_code.lower(),
            "supporting_locations": supporting_paths.get(artifact_type_id, []),
            "completion_criteria": entry["completion_criteria"],
            "update_triggers": entry["update_triggers"],
            "review_cycle": "초안 필수내용 작성 완료 시, 상위 기준선·정책 변경 시, 요구 기준선 승인 전마다 검토한다.",
            "change_and_retirement_rule": (
                "승인본을 덮어쓰지 않는다. 변경요청과 영향분석을 기록해 새 버전을 만들고, 대체된 버전은 "
                "Superseded로 표시한 뒤 보존기간 종료 시 Archived로 옮긴다."
            ),
            "current_lifecycle_status": "DRAFT",
            "current_approval_status": "NOT_APPROVED",
            "current_baseline_status": "NOT_BASELINED",
        }
    for display_code in ("REQ-03", "REQ-06"):
        plan[display_code].update({
            "fp035_direct_impact": True,
            "policy_correction_candidate_refs": [FP035_CORRECTION_CANDIDATE_ID],
            "approval_blockers": copy.deepcopy(FP035_APPROVAL_BLOCKERS),
            "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
            "authoring_and_planning_readiness": "ALLOWED",
            "mobile_network_branch_implementation_and_test_readiness": "BLOCKED_PENDING_BUNDLED_APPROVAL",
        })
    plan["REQ-18"].update({
        "fp035_direct_impact": False,
        "correction_tracking_role": "REQUIRED_RELATED_CHANGE_RECORD",
        "policy_correction_candidate_refs": [FP035_CORRECTION_CANDIDATE_ID],
        "change_control_refs": ["CR-0002", FP035_NETWORK_ISSUE_ID, "RAID-011"],
        "required_tracking_location": _repo_path(CHANGE_LOG_PATH),
        "tracking_does_not_activate_policy": True,
    })
    return plan


def _validate_planned_tests(policy: dict[str, Any], tests: dict[str, Any]) -> dict[str, dict[str, Any]]:
    cases = tests.get("test_cases")
    _require(isinstance(cases, list), "planned test catalog has no test_cases array")
    by_id: dict[str, dict[str, Any]] = {}
    for case in cases:
        case_id = case.get("test_case_id")
        _require(isinstance(case_id, str) and case_id not in by_id, "planned test IDs must be unique")
        by_id[case_id] = case

    expected: list[tuple[str, str, str]] = []
    for feature in policy["features"]:
        source_id = feature["id"]
        for index, _ in enumerate(feature["verification_scenarios"], start=1):
            expected.append((_test_id(source_id, index), _requirement_id(source_id), _acceptance_id(source_id, index)))
    for common in policy["common_policies"]:
        source_id = common["id"]
        expected.append((_test_id(source_id, 1), _requirement_id(source_id), _acceptance_id(source_id, 1)))
    for gate in policy["remaining_gates"]:
        source_id = gate["id"]
        expected.append((_test_id(source_id, 1), _requirement_id(source_id), _acceptance_id(source_id, 1)))

    _require(len(expected) == 279, "expected acceptance/test case count must be 279")
    _require(set(by_id) == {item[0] for item in expected}, "planned test catalog IDs differ from policy-derived IDs")
    for test_id, requirement_id, acceptance_id in expected:
        case = by_id[test_id]
        _require(case.get("requirement_id") == requirement_id, f"{test_id} requirement link differs")
        _require(case.get("acceptance_condition_id") == acceptance_id, f"{test_id} acceptance link differs")
        _require(case.get("execution_status") == "NOT_RUN", f"{test_id} must remain NOT_RUN")
        _require(case.get("evidence_ids") == [], f"{test_id} must not claim evidence")
    return by_id


def load_sources() -> dict[str, Any]:
    alignment_sources = alignment_builder._load_and_validate_sources()
    stored_alignment = _load_json(ALIGNMENT_PATH)
    alignment_builder.validate_alignment(stored_alignment, alignment_sources)
    _require(stored_alignment == alignment_builder.build_alignment(), "stored aligned decision register is stale")

    approved_policy = _load_json(POLICY_PATH)
    _require(approved_policy == alignment_sources["document"], "policy source differs from the validated alignment input")
    owner_review = _load_json(OWNER_REVIEW_PATH)
    correction_candidate = _load_json(FP035_CORRECTION_CANDIDATE_PATH)
    policy = _normalized_policy(approved_policy, owner_review, correction_candidate)
    approval = _load_json(APPROVAL_PATH)
    policy_manifest = _load_json(POLICY_MANIFEST_PATH)
    resolution = _load_json(RESOLUTION_PATH)
    _require(approval == alignment_sources["approval"], "approval source differs from the validated alignment input")
    _require(policy_manifest == alignment_sources["manifest"], "policy manifest differs from the validated alignment input")
    _require(resolution == alignment_sources["resolution"], "review resolution differs from the validated alignment input")
    _require([item["id"] for item in policy["features"]] == [f"FP-{number:03d}" for number in range(1, 55)], "feature IDs must be FP-001..FP-054")
    _require(len(policy["common_policies"]) == 9, "common policy count must be 9")
    _require(len(policy["remaining_gates"]) == 5, "remaining gate count must be 5")
    _require(len(stored_alignment["decisions"]) == 135, "aligned decision count must be 135")

    artifact_catalog = _load_json(ARTIFACT_CATALOG_PATH)
    artifact_entries = _validate_artifact_catalog(artifact_catalog)
    runtime_analysis = _load_json(RUNTIME_ANALYSIS_PATH)
    runtime_evidence = runtime_analysis.get("runtime_evidence")
    _require(isinstance(runtime_evidence, list) and len(runtime_evidence) == 9, "runtime evidence must contain RTE-001..009")
    _require([item.get("id") for item in runtime_evidence] == [f"RTE-{number:03d}" for number in range(1, 10)], "runtime evidence IDs differ")
    for item in runtime_evidence:
        _require(item.get("status") in {"IMPLEMENTED", "MEASURED", "NOT_MEASURED"}, f"{item.get('id')} status differs")
        for evidence in item.get("evidence", []):
            path = REPO_ROOT / evidence["path"]
            _require(path.is_file(), f"runtime evidence path is missing: {evidence['path']}")

    planned_tests = _load_json(PLANNED_TEST_CATALOG_PATH)
    tests_by_id = _validate_planned_tests(policy, planned_tests)

    return {
        "policy": policy,
        "approved_policy_source": approved_policy,
        "owner_review": owner_review,
        "fp035_correction_candidate": correction_candidate,
        "alignment": stored_alignment,
        "approval": approval,
        "policy_manifest": policy_manifest,
        "resolution": resolution,
        "artifact_catalog": artifact_catalog,
        "artifact_entries": artifact_entries,
        "runtime_analysis": runtime_analysis,
        "runtime_by_id": {item["id"]: item for item in runtime_evidence},
        "planned_tests": planned_tests,
        "tests_by_id": tests_by_id,
    }


def _trace_links(feature: dict[str, Any], runtime_by_id: dict[str, dict[str, Any]]) -> tuple[dict[str, Any], dict[str, Any]]:
    code_links: list[dict[str, Any]] = []
    evidence_links: list[dict[str, Any]] = []
    code_prefixes = ("apps/", "services/", "packages/", "scripts/")
    for rte_id in feature["implementation"]["evidence_refs"]:
        rte = runtime_by_id[rte_id]
        for evidence in rte.get("evidence", []):
            link = {
                "runtime_evidence_id": rte_id,
                "runtime_observation_status": rte["status"],
                "path": evidence["path"],
                "lines": evidence.get("lines"),
                "claim": evidence["claim"],
                "trace_status": "SOURCE_ONLY",
            }
            if evidence["path"].startswith(code_prefixes):
                code_links.append(link)
            else:
                evidence_links.append(link)
    code_status = "NOT_LINKED" if not code_links else (
        "EXISTS_REVALIDATION_REQUIRED"
        if feature["implementation"]["alignment_status"] == "REVALIDATION_REQUIRED"
        else "EXISTS_NOT_VALIDATED"
    )
    evidence_status = "NOT_LINKED" if not evidence_links else (
        "EXISTS_INSUFFICIENT" if any(link["runtime_observation_status"] == "MEASURED" for link in evidence_links)
        else "EXISTS_NOT_VALIDATED"
    )
    return (
        {"status": code_status, "links": code_links},
        {"status": evidence_status, "links": evidence_links},
    )


def _feature_clauses(feature: dict[str, Any], requirement_id: str) -> list[dict[str, str]]:
    clauses: list[dict[str, str]] = []
    groups = [
        ("START", "시작 조건", feature["start_conditions"]),
        ("FLOW", "정상 흐름", feature["normal_flow"]),
        ("LIFE", "생명주기", [statement for statements in feature["lifecycle_rules"].values() for statement in statements]),
        ("RULE", "설계 규칙", feature["design_rules"]),
        ("FAIL", "실패 시 동작", feature["failure_behavior"]),
        ("BAN", "금지 동작", feature["prohibited_behaviors"]),
    ]
    for code, label, statements in groups:
        for index, statement in enumerate(statements, start=1):
            clauses.append({
                "clause_id": _clause_id(requirement_id, code, index),
                "kind": label,
                "statement": statement,
            })
    return clauses


def _common_clauses(common: dict[str, Any], requirement_id: str) -> list[dict[str, str]]:
    return [
        {
            "clause_id": _clause_id(requirement_id, "RULE", index),
            "kind": "공통 규칙",
            "statement": statement,
        }
        for index, statement in enumerate(common["rules"], start=1)
    ]


def _gate_clauses(gate: dict[str, Any], requirement_id: str) -> list[dict[str, str]]:
    return [{
        "clause_id": _clause_id(requirement_id, "CLOSE", 1),
        "kind": "게이트 종료 조건",
        "statement": gate["closure"],
    }]


def _feature_acceptance(feature: dict[str, Any], tests_by_id: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    source_id = feature["id"]
    given = feature["start_conditions"] or ["해당 기능의 사전 조건이 갖춰져 있다."]
    result = []
    for index, scenario in enumerate(feature["verification_scenarios"], start=1):
        test_id = _test_id(source_id, index)
        test_case = tests_by_id[test_id]
        source_issue_ids: list[str] = []
        acceptance = {
            "acceptance_condition_id": _acceptance_id(source_id, index),
            "given": given,
            "when": test_case["procedure"],
            "then": scenario,
            "lifecycle_status": "DRAFT",
            "planned_test_id": test_id,
            "test_execution_status": "NOT_RUN",
            "pass_claimed": False,
            "source_issue_ids": source_issue_ids,
            "approval_blockers": [],
            "execution_blockers": [],
            "authoring_readiness": "ALLOWED",
            "approval_readiness": "DRAFT_REVIEW_REQUIRED",
            "execution_readiness": "PRECONDITIONS_AND_REVIEW_REQUIRED",
        }
        if source_id == "FP-035":
            branch = FP035_EXPECTED_BRANCHES[index - 1]
            acceptance["normalization_id"] = FP035_NORMALIZATION_ID
            acceptance["policy_correction_candidate_id"] = FP035_CORRECTION_CANDIDATE_ID
            acceptance["required_activation_event"] = FP035_REQUIRED_ACTIVATION_EVENT
            acceptance["approval_blockers"] = copy.deepcopy(FP035_APPROVAL_BLOCKERS)
            acceptance["network_branch"] = copy.deepcopy(branch)
            acceptance["given"] = [branch["easy_explanation"]]
            acceptance["when"] = [
                "보행 상태, Wi-Fi 가용 여부와 저장된 이동통신망 전송 선택값을 조합해 전송 시작·보류 결정을 확인한다.",
                *test_case["procedure"],
            ]
            acceptance["then"] = (
                f"{branch['easy_explanation']} 결과 상태는 {branch['expected_transfer']}로 기록되고, "
                "예정 시험은 실행 전이므로 통과를 주장하지 않는다."
            )
            if branch["expected_transfer"] == "APPROVED_MOBILE_NETWORK_ALLOWED":
                acceptance["execution_blockers"] = copy.deepcopy(FP035_APPROVAL_BLOCKERS)
                acceptance["execution_readiness"] = "BLOCKED_PENDING_BUNDLED_APPROVAL"
                acceptance["formal_branch_implementation_and_test_frozen"] = True
            else:
                acceptance["formal_branch_implementation_and_test_frozen"] = False
        result.append(acceptance)
    return result


def _common_acceptance(common: dict[str, Any], tests_by_id: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    source_id = common["id"]
    test_id = _test_id(source_id, 1)
    test_case = tests_by_id[test_id]
    return [{
        "acceptance_condition_id": _acceptance_id(source_id, 1),
        "given": ["이 공통정책의 영향을 받는 기능 또는 운영 절차가 활성 상태다."],
        "when": test_case["procedure"],
        "then": test_case["expected_result"],
        "lifecycle_status": "DRAFT",
        "planned_test_id": test_id,
        "test_execution_status": "NOT_RUN",
        "pass_claimed": False,
    }]


def _gate_acceptance(gate: dict[str, Any], tests_by_id: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    source_id = gate["id"]
    test_id = _test_id(source_id, 1)
    test_case = tests_by_id[test_id]
    return [{
        "acceptance_condition_id": _acceptance_id(source_id, 1),
        "given": ["게이트 측정·검토에 필요한 대상 기기, 환경, 원자료가 준비되어 있다."],
        "when": test_case["procedure"],
        "then": gate["closure"],
        "lifecycle_status": "DRAFT",
        "planned_test_id": test_id,
        "test_execution_status": "NOT_RUN",
        "pass_claimed": False,
    }]


def _row_hash(row: dict[str, Any]) -> dict[str, Any]:
    row["content_sha256"] = _object_sha256(row)
    return row


def _design_trace(source_id: str, policy: dict[str, Any]) -> dict[str, Any]:
    document_by_design_id = {
        design_id: path
        for path, design_ids in design_builder.DOCUMENT_COVERAGE.items()
        for design_id in design_ids
    }
    if source_id.startswith("FP-"):
        design_ids = [
            design_id
            for design_id, feature_ids in design_builder.DESIGN_POLICY_REFS.items()
            if source_id in feature_ids
        ]
    elif source_id.startswith("NPC-"):
        design_ids = [
            design_id
            for design_id, common_ids in design_builder.DESIGN_COMMON_REFS.items()
            if source_id in common_ids
        ]
    else:
        gate = next(item for item in policy["remaining_gates"] if item["id"] == source_id)
        affected = set(gate["affected_feature_ids"])
        design_ids = [
            design_id
            for design_id, feature_ids in design_builder.DESIGN_POLICY_REFS.items()
            if affected & set(feature_ids)
        ]
    links = []
    for design_id in design_ids:
        path = _repo_path(document_by_design_id[design_id])
        anchor = design_id.lower()
        links.append({
            "design_id": design_id,
            "path": path,
            "anchor": anchor,
            "target": f"{path}#{anchor}",
            "trace_status": "DRAFT_NOT_APPROVED",
        })
    _require(bool(links), f"design declaration is empty: {source_id}")
    return {
        "status": "DRAFT_DESIGN_LINKS_DECLARED",
        "links": links,
        "link_validation_status": "SEE_EXTERNAL_INTEGRATION_REPORT",
        "integration_report_path": _repo_path(TRACE_INTEGRATION_REPORT_PATH),
    }


def _build_requirement_rows(sources: dict[str, Any]) -> list[dict[str, Any]]:
    policy = sources["policy"]
    alignment = sources["alignment"]
    runtime_by_id = sources["runtime_by_id"]
    tests_by_id = sources["tests_by_id"]
    decisions_by_feature: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for decision in alignment["decisions"]:
        for feature_id in decision["affected_feature_ids"]:
            decisions_by_feature[feature_id].append(decision)
    gates_by_feature: dict[str, list[str]] = defaultdict(list)
    for gate in policy["remaining_gates"]:
        for feature_id in gate["affected_feature_ids"]:
            gates_by_feature[feature_id].append(gate["id"])

    rows: list[dict[str, Any]] = []
    for common in policy["common_policies"]:
        source_id = common["id"]
        requirement_id = _requirement_id(source_id)
        affected_decisions = _unique(
            decision["decision_id"]
            for feature_id in common["affected_feature_ids"]
            for decision in decisions_by_feature[feature_id]
        )
        canonical_decisions = _unique(
            decision["canonical_decision_id"]
            for feature_id in common["affected_feature_ids"]
            for decision in decisions_by_feature[feature_id]
        )
        gate_refs = _unique(gate for feature_id in common["affected_feature_ids"] for gate in gates_by_feature[feature_id])
        acceptance = _common_acceptance(common, tests_by_id)
        rows.append(_row_hash({
            "requirement_id": requirement_id,
            "requirement_version": VERSION,
            "source_policy_id": source_id,
            "source_kind": "COMMON_POLICY",
            "title": common["title"],
            "plain_requirement": common["summary"],
            "area_id": None,
            "priority": "MUST",
            "lifecycle_status": "DRAFT",
            "approval_status": "NOT_APPROVED",
            "baseline_status": "NOT_BASELINED",
            "trace_status": "REQUIREMENT_DRAFTED",
            "source_status": "POLICY_BASELINED",
            "domain_artifact_type_ids": COMMON_DOMAINS[source_id],
            "source_refs": _unique([source_id, *common["source_review_ids"]]),
            "decision_refs": affected_decisions,
            "canonical_decision_refs": canonical_decisions,
            "gate_refs": gate_refs,
            "affected_feature_ids": common["affected_feature_ids"],
            "requirement_clauses": _common_clauses(common, requirement_id),
            "requirement_details": {"rules": common["rules"]},
            "acceptance_conditions": acceptance,
            "planned_test_ids": [item["planned_test_id"] for item in acceptance],
            "design_trace": _design_trace(source_id, policy),
            "code_trace": {"status": "NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL", "links": []},
            "evidence_trace": {"status": "NOT_LINKED", "links": []},
            "implementation_observation": "공통정책은 개별 기능·설계·시험에서 준수 여부를 다시 확인해야 한다.",
            "verification_status": "NOT_RUN",
            "verification_completion_claimed": False,
            "waiver_refs": [],
            "defect_refs": [],
        }))

    for feature in policy["features"]:
        source_id = feature["id"]
        requirement_id = _requirement_id(source_id)
        aligned_decisions = decisions_by_feature[source_id]
        decision_refs = [item["decision_id"] for item in aligned_decisions]
        canonical_refs = _unique(item["canonical_decision_id"] for item in aligned_decisions)
        _require(set(decision_refs) == set(feature["traceability"]["decision_ids"]), f"{source_id} decision trace differs")
        code_trace, evidence_trace = _trace_links(feature, runtime_by_id)
        acceptance = _feature_acceptance(feature, tests_by_id)
        source_issue_ids: list[str] = []
        normalization_refs = [FP035_NORMALIZATION_ID] if source_id == "FP-035" else []
        rows.append(_row_hash({
            "requirement_id": requirement_id,
            "requirement_version": VERSION,
            "source_policy_id": source_id,
            "source_policy_clause_id": feature["policy_clause_id"],
            "source_kind": "FEATURE_POLICY",
            "title": feature["name"],
            "plain_requirement": feature["effective_policy_summary"],
            "user_purpose": feature["user_purpose"],
            "area_id": feature["area_id"],
            "priority": "MUST",
            "lifecycle_status": "DRAFT",
            "approval_status": "NOT_APPROVED",
            "baseline_status": "NOT_BASELINED",
            "trace_status": "REQUIREMENT_DRAFTED",
            "source_status": "POLICY_1_0_0_BASELINED_WITH_NOT_EFFECTIVE_CORRECTION_CANDIDATE" if normalization_refs else "POLICY_BASELINED",
            "source_issue_ids": source_issue_ids,
            "approval_blockers": copy.deepcopy(FP035_APPROVAL_BLOCKERS) if normalization_refs else [],
            "execution_blockers": copy.deepcopy(FP035_APPROVAL_BLOCKERS) if normalization_refs else [],
            "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT if normalization_refs else None,
            "authoring_readiness": "ALLOWED",
            "formal_branch_implementation_and_test_readiness": (
                "BLOCKED_PENDING_BUNDLED_APPROVAL" if normalization_refs else "PRECONDITIONS_AND_REVIEW_REQUIRED"
            ),
            "blocked_network_branch_ids": ["FP035-NET-03"] if normalization_refs else [],
            "normalization_refs": normalization_refs,
            "policy_correction_candidate_refs": [FP035_CORRECTION_CANDIDATE_ID] if normalization_refs else [],
            "bundled_approval_dependency_refs": [FP035_CORRECTION_CANDIDATE_ID] if normalization_refs else [],
            "artifact_binding_refs": ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"] if normalization_refs else [],
            "domain_artifact_type_ids": AREA_DOMAINS[feature["area_id"]],
            "source_refs": _unique([
                feature["policy_clause_id"],
                feature["traceability"].get("direct_review_id"),
                *feature["traceability"]["applicable_review_ids"],
                *feature["traceability"]["source_refs"],
                *normalization_refs,
            ]),
            "decision_refs": decision_refs,
            "canonical_decision_refs": canonical_refs,
            "gate_refs": gates_by_feature[source_id],
            "affected_feature_ids": [source_id],
            "requirement_clauses": _feature_clauses(feature, requirement_id),
            "requirement_details": {
                "start_conditions": feature["start_conditions"],
                "inputs": feature["inputs"],
                "outputs": feature["outputs"],
                "normal_flow": feature["normal_flow"],
                "lifecycle_rules": feature["lifecycle_rules"],
                "design_rules": feature["design_rules"],
                "failure_behavior": feature["failure_behavior"],
                "prohibited_behaviors": feature["prohibited_behaviors"],
                "user_guidance": feature["user_guidance"],
                "permissions": feature["permissions"],
                "execution_boundary": feature["execution_boundary"],
                "data_handling": feature["data_handling"],
                "common_policy_effects": feature["common_policy_effects"],
                "owner_answer_normalization": feature.get("normalization"),
            },
            "acceptance_conditions": acceptance,
            "planned_test_ids": [item["planned_test_id"] for item in acceptance],
            "design_trace": _design_trace(source_id, policy),
            "code_trace": code_trace,
            "evidence_trace": evidence_trace,
            "implementation_observation": feature["implementation"]["plain_status_before_review"],
            "verification_status": "NOT_RUN",
            "verification_completion_claimed": False,
            "waiver_refs": [],
            "defect_refs": [],
        }))

    for gate in policy["remaining_gates"]:
        source_id = gate["id"]
        requirement_id = _requirement_id(source_id)
        affected_decisions = _unique(
            decision["decision_id"]
            for feature_id in gate["affected_feature_ids"]
            for decision in decisions_by_feature[feature_id]
        )
        canonical_decisions = _unique(
            decision["canonical_decision_id"]
            for feature_id in gate["affected_feature_ids"]
            for decision in decisions_by_feature[feature_id]
        )
        acceptance = _gate_acceptance(gate, tests_by_id)
        rows.append(_row_hash({
            "requirement_id": requirement_id,
            "requirement_version": VERSION,
            "source_policy_id": source_id,
            "source_kind": "REMAINING_GATE",
            "title": gate["title"],
            "plain_requirement": gate["closure"],
            "area_id": None,
            "priority": "MUST_BEFORE_RELEASE",
            "lifecycle_status": "DRAFT",
            "approval_status": "NOT_APPROVED",
            "baseline_status": "NOT_BASELINED",
            "trace_status": "REQUIREMENT_DRAFTED",
            "source_status": "SOURCE_ONLY",
            "domain_artifact_type_ids": ["DLV-REQ-06", "DLV-REQ-12", "DLV-REQ-13"],
            "source_refs": [source_id],
            "decision_refs": affected_decisions,
            "canonical_decision_refs": canonical_decisions,
            "gate_refs": [source_id],
            "affected_feature_ids": gate["affected_feature_ids"],
            "requirement_clauses": _gate_clauses(gate, requirement_id),
            "requirement_details": {
                "gate_kind": gate["kind"],
                "gate_status": gate["status"],
                "closure": gate["closure"],
            },
            "acceptance_conditions": acceptance,
            "planned_test_ids": [item["planned_test_id"] for item in acceptance],
            "design_trace": _design_trace(source_id, policy),
            "code_trace": {"status": "NOT_APPLICABLE", "links": []},
            "evidence_trace": {"status": "NOT_CREATED", "links": []},
            "implementation_observation": "측정·전문가 검토·운영 증거가 아직 없어 출시 전 종료가 필요한 게이트다.",
            "verification_status": "NOT_RUN",
            "verification_completion_claimed": False,
            "waiver_refs": [],
            "defect_refs": [],
        }))

    _require(len(rows) == 68, "requirement row count must be 68")
    _require(len({row["requirement_id"] for row in rows}) == 68, "requirement IDs must be unique")
    return rows


def _rtm_source_bindings() -> list[dict[str, Any]]:
    return [
        _source_binding(GENERATOR_PATH, "GENERATOR"),
        _source_binding(design_builder.GENERATOR_PATH, "DESIGN_TRACE_MAPPING_SOURCE"),
        _source_binding(POLICY_PATH, "APPROVED_POLICY_PAYLOAD"),
        _source_binding(APPROVAL_PATH, "APPROVAL_RECORD"),
        _source_binding(POLICY_MANIFEST_PATH, "POLICY_BASELINE_MANIFEST"),
        _source_binding(RESOLUTION_PATH, "REVIEW_RESOLUTION"),
        _source_binding(OWNER_REVIEW_PATH, "EXISTING_OWNER_ANSWER_FOR_FP035_NORMALIZATION"),
        _source_binding(FP035_CORRECTION_CANDIDATE_PATH, "FP035_CORRECTION_CANDIDATE_NOT_APPROVED_NOT_EFFECTIVE"),
        _source_binding(ALIGNMENT_PATH, "ALIGNED_DECISION_REGISTER"),
        _source_binding(ARTIFACT_CATALOG_PATH, "ARTIFACT_TYPE_CATALOG"),
        _source_binding(RUNTIME_ANALYSIS_PATH, "LEGACY_RUNTIME_OBSERVATION"),
        _source_binding(PLANNED_TEST_CATALOG_PATH, "DOWNSTREAM_PLANNED_TEST_CATALOG"),
    ]


def build_rtm(sources: dict[str, Any] | None = None) -> dict[str, Any]:
    if sources is None:
        sources = load_sources()
    rows = _build_requirement_rows(sources)
    bindings = _rtm_source_bindings()
    feature_rows = [row for row in rows if row["source_kind"] == "FEATURE_POLICY"]
    decision_refs = _unique(
        decision_ref
        for row in feature_rows
        for decision_ref in row["decision_refs"]
    )
    canonical_decision_refs = _unique(
        decision_ref
        for row in feature_rows
        for decision_ref in row["canonical_decision_refs"]
    )
    rtm: dict[str, Any] = {
        "schema_version": "walksafe.requirements-traceability-draft.v1",
        "metadata": {
            "document_id": "WS-REQ-RTM-DRAFT-20260721-R001",
            "document_version": VERSION,
            "title": "WalkSafe 요구사항 추적표 Draft",
            "as_of": AS_OF,
            "lifecycle_status": "DRAFT",
            "approval_status": "NOT_APPROVED",
            "baseline_status": "NOT_BASELINED",
            "verification_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
            "owner_role": "요구사항책임자",
            "reviewer_roles": ["프로젝트책임자", "기술책임자", "QA책임자"],
        },
        "authorization_boundary": {
            "policy_source_is_approved": True,
            "requirements_are_approved": False,
            "requirements_are_baselined": False,
            "verification_is_complete": False,
            "release_is_authorized": False,
            "plain_explanation": "승인된 정책을 요구사항 초안으로 옮긴 상태다. 이 문서 자체의 검토·승인과 시험 실행은 아직 끝나지 않았다.",
        },
        "status_definitions": {
            "SOURCE_ONLY": "출처 또는 기존 자료가 존재한다는 뜻이며 요구 충족 판정은 아니다.",
            "REQUIREMENT_DRAFTED": "정책을 요구 문장과 인수조건으로 옮겼지만 아직 승인되지 않았다.",
            "NOT_RUN": "연결된 시험을 아직 실행하지 않았다.",
            "NOT_BASELINED": "이 Draft가 정식 요구사항 기준선으로 승인되지 않았다.",
            "POLICY_1_0_0_BASELINED_WITH_NOT_EFFECTIVE_CORRECTION_CANDIDATE": "정책 1.0.0 원본은 바꾸지 않고 기존 답변을 담은 정정 후보를 요구 Draft에 연결했다. 후보와 영향 산출물의 새 묶음 승인 전에는 정책 효력이 없다.",
            "DRAFT_DESIGN_LINKS_DECLARED": "요구와 설계 Draft의 ID·이동 경로를 연결했다. 설계 승인이나 구현 완료 뜻은 아니며 별도 교차추적 보고서에서 파일 지문을 확인한다.",
        },
        "source_bindings": bindings,
        "source_binding_sha256": _object_sha256(bindings),
        "policy_baseline_binding": {
            "baseline_id": sources["approval"]["approved_baseline_payload"]["baseline_id"],
            "approved_baseline_payload_sha256": sources["approval"]["approved_baseline_payload_sha256"],
            "approval_record_path": _repo_path(APPROVAL_PATH),
            "approval_record_sha256": _file_sha256(APPROVAL_PATH),
        },
        "aligned_decision_binding": {
            "register_path": _repo_path(ALIGNMENT_PATH),
            "register_sha256": _file_sha256(ALIGNMENT_PATH),
            "decision_binding_sha256": sources["alignment"]["decision_binding_sha256"],
            "decision_count": 135,
            "decision_feature_edge_count": 428,
        },
        "artifact_bundle_map": REQ_BUNDLES,
        "artifact_management_plan": _artifact_management_plan(sources["artifact_entries"]),
        "trace_integration_validation": {
            "status": "SEE_EXTERNAL_INTEGRATION_REPORT",
            "report_id": "WS-REQ-DES-TST-TRACE-INTEGRATION-20260721-001",
            "report_path": _repo_path(TRACE_INTEGRATION_REPORT_PATH),
        },
        "bound_policy_correction_candidates": [FP035_NETWORK_CLARIFICATION],
        "requirement_change_tracking": {
            "artifact_code": "REQ-18",
            "path": _repo_path(CHANGE_LOG_PATH),
            "policy_correction_candidate_refs": [FP035_CORRECTION_CANDIDATE_ID],
            "change_control_refs": ["CR-0002", FP035_NETWORK_ISSUE_ID, "RAID-011"],
            "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
            "candidate_approval_status": "NOT_APPROVED",
            "candidate_effective_status": "NOT_EFFECTIVE",
            "tracking_does_not_activate_policy": True,
        },
        "requirements": rows,
        "remaining_gates": [
            {
                **gate,
                "requirement_id": _requirement_id(gate["id"]),
                "waived": False,
                "completion_claimed": False,
            }
            for gate in sources["alignment"]["remaining_gates"]
        ],
        "coverage": {
            "artifact_type_count": 19,
            "bundle_count": 3,
            "requirement_count": len(rows),
            "feature_requirement_count": len(feature_rows),
            "common_policy_requirement_count": sum(row["source_kind"] == "COMMON_POLICY" for row in rows),
            "remaining_gate_requirement_count": sum(row["source_kind"] == "REMAINING_GATE" for row in rows),
            "requirement_clause_count": sum(len(row["requirement_clauses"]) for row in rows),
            "acceptance_condition_count": sum(len(row["acceptance_conditions"]) for row in rows),
            "planned_test_count": sum(len(row["planned_test_ids"]) for row in rows),
            "aligned_decision_count": len(decision_refs),
            "canonical_decision_count": len(canonical_decision_refs),
            "decision_feature_edge_count": sum(len(row["decision_refs"]) for row in feature_rows),
            "remaining_gate_count": len(sources["alignment"]["remaining_gates"]),
            "design_artifact_count": len({
                link["design_id"]
                for row in rows
                for link in row["design_trace"]["links"]
            }),
            "requirement_design_edge_count": sum(
                len(row["design_trace"]["links"]) for row in rows
            ),
            "requirements_with_not_run_verification": sum(row["verification_status"] == "NOT_RUN" for row in rows),
            "verification_completion_claim_count": sum(bool(row["verification_completion_claimed"]) for row in rows),
            "open_source_policy_issue_count": 0,
            "owner_content_question_required_count": 0,
            "not_effective_policy_correction_candidate_count": 1,
        },
    }
    rtm["requirement_binding_sha256"] = _object_sha256(rows)
    rtm["document_content_sha256"] = _object_sha256(rtm)
    validate_rtm(rtm, sources)
    return rtm


def validate_rtm(rtm: dict[str, Any], sources: dict[str, Any] | None = None) -> None:
    if sources is None:
        sources = load_sources()
    _require(rtm.get("schema_version") == "walksafe.requirements-traceability-draft.v1", "RTM schema differs")
    metadata = rtm.get("metadata", {})
    _require(metadata.get("lifecycle_status") == "DRAFT", "RTM must be Draft")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "RTM must not claim approval")
    _require(metadata.get("baseline_status") == "NOT_BASELINED", "RTM must not claim a baseline")
    _require(metadata.get("verification_status") == "NOT_RUN", "RTM verification must be NOT_RUN")
    _require(metadata.get("release_status") == "NOT_ELIGIBLE", "RTM must not authorize release")
    rows = rtm.get("requirements")
    _require(isinstance(rows, list) and len(rows) == 68, "RTM must contain 68 requirements")
    _require(len({row.get("requirement_id") for row in rows}) == 68, "RTM requirement IDs must be unique")
    clarifications = rtm.get("bound_policy_correction_candidates")
    _require(clarifications == [FP035_NETWORK_CLARIFICATION], "FP-035 clarification register differs")
    _require(rtm.get("coverage", {}).get("open_source_policy_issue_count") == 0, "open source-policy issue count differs")
    _require(rtm.get("coverage", {}).get("owner_content_question_required_count") == 0, "owner content question count differs")
    _require(rtm.get("coverage", {}).get("not_effective_policy_correction_candidate_count") == 1, "correction candidate count differs")
    _require(
        rtm.get("requirement_change_tracking") == {
            "artifact_code": "REQ-18",
            "path": _repo_path(CHANGE_LOG_PATH),
            "policy_correction_candidate_refs": [FP035_CORRECTION_CANDIDATE_ID],
            "change_control_refs": ["CR-0002", FP035_NETWORK_ISSUE_ID, "RAID-011"],
            "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
            "candidate_approval_status": "NOT_APPROVED",
            "candidate_effective_status": "NOT_EFFECTIVE",
            "tracking_does_not_activate_policy": True,
        },
        "REQ-18 change tracking differs",
    )
    _require(
        set(rtm.get("artifact_management_plan", {})) == set(DISPLAY_ARTIFACT_TYPE_IDS),
        "REQ artifact management plan coverage differs",
    )
    management_plan = rtm["artifact_management_plan"]
    for display_code in ("REQ-03", "REQ-06"):
        management = management_plan[display_code]
        _require(management.get("fp035_direct_impact") is True, f"{display_code} FP-035 direct impact differs")
        _require(management.get("policy_correction_candidate_refs") == [FP035_CORRECTION_CANDIDATE_ID], f"{display_code} candidate refs differ")
        _require(management.get("approval_blockers") == FP035_APPROVAL_BLOCKERS, f"{display_code} approval blockers differ")
        _require(management.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, f"{display_code} activation event differs")
        _require(management.get("authoring_and_planning_readiness") == "ALLOWED", f"{display_code} authoring readiness differs")
        _require(management.get("mobile_network_branch_implementation_and_test_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL", f"{display_code} execution readiness differs")
    change_tracking = management_plan["REQ-18"]
    _require(change_tracking.get("correction_tracking_role") == "REQUIRED_RELATED_CHANGE_RECORD", "REQ-18 correction tracking role differs")
    _require(change_tracking.get("policy_correction_candidate_refs") == [FP035_CORRECTION_CANDIDATE_ID], "REQ-18 correction candidate refs differ")
    _require(change_tracking.get("change_control_refs") == ["CR-0002", FP035_NETWORK_ISSUE_ID, "RAID-011"], "REQ-18 change-control refs differ")
    _require(change_tracking.get("required_tracking_location") == _repo_path(CHANGE_LOG_PATH), "REQ-18 tracking location differs")
    _require(change_tracking.get("tracking_does_not_activate_policy") is True, "REQ-18 tracking activation boundary differs")
    expected_source_ids = {
        item["id"] for item in sources["policy"]["features"]
    } | {
        item["id"] for item in sources["policy"]["common_policies"]
    } | {
        item["id"] for item in sources["policy"]["remaining_gates"]
    }
    _require({row.get("source_policy_id") for row in rows} == expected_source_ids, "RTM source coverage differs")
    for row in rows:
        source_id = row["source_policy_id"]
        _require(row["requirement_id"] == _requirement_id(source_id), f"{source_id} requirement ID differs")
        _require(row["lifecycle_status"] == "DRAFT", f"{source_id} lifecycle must be Draft")
        _require(row["approval_status"] == "NOT_APPROVED", f"{source_id} approval differs")
        _require(row["baseline_status"] == "NOT_BASELINED", f"{source_id} baseline differs")
        _require(row["trace_status"] == "REQUIREMENT_DRAFTED", f"{source_id} trace status differs")
        _require(row["verification_status"] == "NOT_RUN", f"{source_id} verification differs")
        _require(row["verification_completion_claimed"] is False, f"{source_id} completion must be false")
        if source_id == "FP-035":
            _require(row.get("source_status") == "POLICY_1_0_0_BASELINED_WITH_NOT_EFFECTIVE_CORRECTION_CANDIDATE", "FP-035 normalization status differs")
            _require(row.get("source_issue_ids") == [], "FP-035 Draft must not keep the resolved text conflict as a row blocker")
            _require(row.get("approval_blockers") == FP035_APPROVAL_BLOCKERS, "FP-035 Draft approval blockers differ")
            _require(row.get("execution_blockers") == FP035_APPROVAL_BLOCKERS, "FP-035 Draft execution blockers differ")
            _require(row.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "FP-035 activation event differs")
            _require(row.get("authoring_readiness") == "ALLOWED", "FP-035 authoring readiness differs")
            _require(row.get("formal_branch_implementation_and_test_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL", "FP-035 formal execution readiness differs")
            _require(row.get("blocked_network_branch_ids") == ["FP035-NET-03"], "FP-035 blocked branch IDs differ")
            _require(row.get("normalization_refs") == [FP035_NORMALIZATION_ID], "FP-035 normalization trace differs")
            _require(row.get("policy_correction_candidate_refs") == [FP035_CORRECTION_CANDIDATE_ID], "FP-035 correction candidate trace differs")
            _require(row.get("bundled_approval_dependency_refs") == [FP035_CORRECTION_CANDIDATE_ID], "FP-035 bundled approval dependency differs")
            _require(row.get("artifact_binding_refs") == ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"], "FP-035 artifact binding differs")
            _require(
                [item.get("network_branch") for item in row["acceptance_conditions"]] == FP035_EXPECTED_BRANCHES,
                "FP-035 network branch acceptance matrix differs",
            )
            _require(
                all(
                    item.get("approval_blockers") == FP035_APPROVAL_BLOCKERS
                    and item.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT
                    and item.get("authoring_readiness") == "ALLOWED"
                    for item in row["acceptance_conditions"]
                ),
                "FP-035 acceptance approval boundary differs",
            )
            mobile_acceptance = next(
                item for item in row["acceptance_conditions"]
                if item["network_branch"]["expected_transfer"] == "APPROVED_MOBILE_NETWORK_ALLOWED"
            )
            _require(mobile_acceptance.get("execution_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL", "FP-035 mobile branch readiness differs")
            _require(mobile_acceptance.get("execution_blockers") == FP035_APPROVAL_BLOCKERS, "FP-035 mobile branch blockers differ")
            _require(mobile_acceptance.get("formal_branch_implementation_and_test_frozen") is True, "FP-035 mobile branch freeze differs")
            _require(
                all(
                    item.get("execution_readiness") == "PRECONDITIONS_AND_REVIEW_REQUIRED"
                    and item.get("execution_blockers") == []
                    and item.get("formal_branch_implementation_and_test_frozen") is False
                    for item in row["acceptance_conditions"]
                    if item is not mobile_acceptance
                ),
                "FP-035 non-mobile acceptance readiness differs",
            )
        expected_design_trace = _design_trace(source_id, sources["policy"])
        _require(row["design_trace"] == expected_design_trace, f"{source_id} design trace differs")
        _require(row["content_sha256"] == _object_sha256({key: value for key, value in row.items() if key != "content_sha256"}), f"{source_id} row hash differs")
        for acceptance in row["acceptance_conditions"]:
            _require(acceptance["planned_test_id"] in sources["tests_by_id"], f"{source_id} planned test missing")
            _require(acceptance["test_execution_status"] == "NOT_RUN", f"{source_id} test status differs")
            _require(acceptance["pass_claimed"] is False, f"{source_id} pass claim must be false")
        for trace_name in ("code_trace", "evidence_trace"):
            for link in row[trace_name]["links"]:
                _require((REPO_ROOT / link["path"]).is_file(), f"trace path is missing: {link['path']}")
                _require(link["trace_status"] == "SOURCE_ONLY", "existing trace must remain SOURCE_ONLY")
    feature_rows = [row for row in rows if row["source_kind"] == "FEATURE_POLICY"]
    _require(sum(len(row["decision_refs"]) for row in feature_rows) == 428, "decision-feature edge coverage differs")
    _require(len({ref for row in feature_rows for ref in row["decision_refs"]}) == 135, "decision coverage differs")
    _require(sum(len(row["acceptance_conditions"]) for row in rows) == 279, "acceptance coverage differs")
    design_ids = {
        link["design_id"]
        for row in rows
        for link in row["design_trace"]["links"]
    }
    design_edge_count = sum(len(row["design_trace"]["links"]) for row in rows)
    _require(design_ids == {f"DES-{number:02d}" for number in range(1, 28)}, "design declaration coverage differs")
    _require(design_edge_count == 716, "requirement-design edge coverage differs")
    _require(rtm["coverage"]["design_artifact_count"] == 27, "design artifact coverage count differs")
    _require(rtm["coverage"]["requirement_design_edge_count"] == 716, "design edge coverage count differs")
    _require(rtm["coverage"]["verification_completion_claim_count"] == 0, "verification completion claims are forbidden")
    _require(rtm["requirement_binding_sha256"] == _object_sha256(rows), "requirement binding hash differs")
    _require(rtm["source_binding_sha256"] == _object_sha256(rtm["source_bindings"]), "source binding hash differs")
    _require(rtm["document_content_sha256"] == _object_sha256({key: value for key, value in rtm.items() if key != "document_content_sha256"}), "RTM content hash differs")
    for row in rows:
        status_values = [
            row["trace_status"],
            row["verification_status"],
            row["design_trace"]["status"],
            row["code_trace"]["status"],
            row["evidence_trace"]["status"],
            *[item["test_execution_status"] for item in row["acceptance_conditions"]],
        ]
        _require(all(value != "VERIFIED" for value in status_values), f"{row['source_policy_id']} has a forbidden completed status")


GLOSSARY_DEFINITIONS = [
    ("WalkSafe", None, "전맹과 저시력 사용자를 같은 우선순위로 둔 시각장애인이 휴대전화 카메라·위치·음성 안내를 이용해 도심 보행 중 위험을 알아차리고 길을 따라가도록 돕는 프로젝트다.", ["FP-001"]),
    ("정책 기준선", "Policy baseline", "사용자가 검토하고 승인하여 뒤 문서가 따라야 하는 정책 묶음이다. 정책 승인이 곧 요구·설계·시험의 완료를 뜻하지는 않는다.", ["NPC-DATA-LIFECYCLE"]),
    ("요구사항", "Requirement", "시스템이 해야 하거나 지켜야 할 일을 구현·시험 가능한 문장으로 적은 것이다.", ["FP-001"]),
    ("기능 정책", "FP", "FP-001부터 FP-054까지 특정 기능 하나의 목적, 흐름, 실패 동작과 금지 동작을 정한 정책이다.", ["FP-001"]),
    ("공통정책", "NPC", "여러 기능이 함께 지켜야 하는 데이터, 저장공간, 권한, 경로 안내 같은 규칙이다.", ["NPC-DATA-LIFECYCLE"]),
    ("미결 게이트", "Gate", "정책 방향은 정했지만 실제 수치·전문가 검토·운영 증거가 없어 출시 전에 반드시 닫아야 하는 항목이다.", ["GATE-PHONE-QUEUE-BYTE-LIMIT"]),
    ("활성 보행", "Active walk", "사용자가 보행 시작을 확인한 뒤 종료·일시중지하기 전까지의 한 번의 보행 세션이다.", ["NPC-PERMISSION-SESSION-LIFECYCLE"]),
    ("원본 자료", "Raw original", "학습이나 신고 처리를 위해 가공하기 전의 영상·음성·위치 등 최초 자료다.", ["NPC-RAW-ORIGINAL-COLLECTION"]),
    ("목적에 필요한 최소 수집", "Data minimization", "정한 기능 목적에 꼭 필요한 항목만 수집하고, 목적·접근자·보존기간·삭제조건을 미리 정하는 원칙이다.", ["NPC-RAW-ORIGINAL-COLLECTION"]),
    ("자동신고 후보", None, "위험 탐지 결과가 신고 정책 조건을 충족해 휴대전화의 전송 대기열에 들어간 자료 묶음이다.", ["NPC-AUTO-REPORT"]),
    ("전송 대기열", "Queue", "통신이 가능할 때 서버로 보내기 위해 휴대전화에 암호화해 잠시 보관하는 신고 후보 목록이다.", ["NPC-PHONE-QUEUE-CAPACITY"]),
    ("서버 용량 상태", None, "서버 저장공간 사용률에 따라 경고·참여자 추가 중단·새 원본 세션 보류 같은 동작을 선택하는 상태다.", ["NPC-SERVER-STORAGE-CAPACITY"]),
    ("동기화", "Sync", "서버의 현재 용량 상태를 휴대전화가 받아 기능 시작 가능 여부를 같은 기준으로 판단하도록 맞추는 일이다.", ["NPC-SERVER-CAPACITY-STATE-SYNC"]),
    ("위치 좌표", "GPS location", "휴대전화 위치 센서가 추정한 현재 지점이다. 오차가 있으므로 단독으로 안전을 보장하는 값으로 쓰지 않는다.", ["FP-022"]),
    ("TMAP 경로", None, "TMAP 경로 서비스에서 받은 보행 경로 선과 경로 단계 정보다.", ["FP-022"]),
    ("보폭", "Step length", "한 걸음에 이동한 것으로 추정하는 거리다. 남은 거리·도착·이탈 판단의 보조 입력이며 현재 좌표나 진행 방향을 대신하지 않는다.", ["FP-022"]),
    ("경로 이탈", "Route deviation", "현재 위치와 저장된 경로의 거리가 정책 기준을 벗어난 상태다. 사용자에게 알리고 사용자가 다음 행동을 판단하게 한다.", ["FP-023"]),
    ("객체 탐지", "Object detection", "카메라 영상에서 학습된 모델이 위험물의 종류와 위치 후보를 찾는 기능이다. 탐지 결과만으로 안전을 보장하지 않는다.", ["FP-013"]),
    ("오탐", "False positive", "실제 위험물이 아닌데 위험물이라고 탐지한 경우다.", ["FP-013"]),
    ("미탐", "False negative", "실제 위험물이 있는데 탐지하지 못한 경우다.", ["FP-013"]),
    ("안전 정지", "Safe stop", "필수 입력·권한·외부 연결을 믿을 수 없을 때 추측 안내를 계속하지 않고 관련 처리를 멈추는 동작이다.", ["NPC-NAVIGATION-ROUTE-DIRECTION"]),
    ("STT", "Speech-to-text", "사용자의 음성을 글자나 명령 의도로 바꾸는 기능이다.", ["FP-027"]),
    ("TTS", "Text-to-speech", "안내 문장을 음성으로 읽어 주는 기능이다.", ["FP-028"]),
    ("TalkBack", None, "Android 화면 내용을 음성으로 읽고 조작을 돕는 화면 읽기 기능이다.", ["FP-009"]),
    ("RTM", "Requirements Traceability Matrix", "정책 결정부터 요구, 인수조건, 예정 시험, 존재하는 코드·증거까지 연결 상태를 보여 주는 표다.", ["FP-001"]),
    ("인수조건", "Acceptance condition", "요구가 충족됐다고 판단하려면 무엇을 준비하고, 무엇을 실행하며, 무엇이 보여야 하는지 적은 조건이다.", ["FP-001"]),
    ("기준선", "Baseline", "검토와 승인을 마쳐 이후 변경을 정식 변경절차로만 할 수 있게 고정한 버전이다.", ["FP-002"]),
    ("출처만 연결", "SOURCE_ONLY", "파일이나 코드가 실제로 존재한다는 연결이다. 최신 요구를 충족했다거나 시험을 통과했다는 뜻은 아니다.", ["FP-002"]),
    ("MFA", "Multi-factor authentication", "비밀번호 하나 외에 다른 인증수단을 함께 요구하는 관리자 로그인 방식이다.", ["NPC-SINGLE-ADMIN-RECOVERY"]),
    ("복구코드", "Recovery code", "관리자 휴대전화를 잃었을 때 계정 접근을 복구하기 위해 별도로 안전하게 보관하는 일회성 코드다.", ["NPC-SINGLE-ADMIN-RECOVERY"]),
]


def build_glossary(rtm: dict[str, Any]) -> dict[str, Any]:
    requirement_by_source = {row["source_policy_id"]: row["requirement_id"] for row in rtm["requirements"]}
    entries = []
    for index, (term, english, definition, source_refs) in enumerate(GLOSSARY_DEFINITIONS, start=1):
        entries.append({
            "entry_id": f"GLO-{index:03d}",
            "term": term,
            "english_or_abbreviation": english,
            "easy_definition": definition,
            "source_refs": source_refs,
            "related_requirement_ids": [requirement_by_source[source] for source in source_refs],
            "lifecycle_status": "DRAFT",
            "owner_role": "요구사항책임자",
        })
    glossary: dict[str, Any] = {
        "schema_version": "walksafe.requirements-glossary-draft.v1",
        "metadata": {
            "document_id": "WS-REQ-GLOSSARY-DRAFT-20260721-R001",
            "document_version": VERSION,
            "artifact_type_id": "DLV-REQ-19",
            "lifecycle_status": "DRAFT",
            "approval_status": "NOT_APPROVED",
            "baseline_status": "NOT_BASELINED",
            "as_of": AS_OF,
        },
        "entries": entries,
        "coverage": {"term_count": len(entries), "requirements_linked_count": len({rid for entry in entries for rid in entry["related_requirement_ids"]})},
    }
    glossary["content_sha256"] = _object_sha256(glossary)
    return glossary


def build_change_log(rtm: dict[str, Any]) -> dict[str, Any]:
    change_log: dict[str, Any] = {
        "schema_version": "walksafe.requirements-change-log-draft.v1",
        "metadata": {
            "document_id": "WS-REQ-CHANGE-LOG-DRAFT-20260721-R001",
            "document_version": VERSION,
            "artifact_type_id": "DLV-REQ-18",
            "lifecycle_status": "DRAFT",
            "approval_status": "NOT_APPROVED",
            "baseline_status": "NOT_BASELINED",
            "as_of": AS_OF,
        },
        "entries": [{
            "change_id": "REQ-CHG-20260721-001",
            "change_type": "INITIAL_DRAFT_DERIVATION",
            "recorded_on": "2026-07-21",
            "summary": "승인된 기능정책 기준선과 정렬된 135개 결정으로 요구사항 Draft를 최초 생성했다.",
            "policy_change_claimed": False,
            "affected_requirement_ids": [row["requirement_id"] for row in rtm["requirements"]],
            "before_version": None,
            "after_version": "0.1.0",
            "approval_status": "NOT_APPROVED",
            "verification_status": "NOT_RUN",
            "source_refs": [
                _repo_path(POLICY_PATH),
                _repo_path(APPROVAL_PATH),
                _repo_path(ALIGNMENT_PATH),
            ],
            "requirement_binding_sha256": rtm["requirement_binding_sha256"],
            "impact_analysis": "68개 요구를 최초 생성했으며 구현·시험·출시 완료 상태는 바꾸지 않았다.",
            "reviewer_roles": ["제품책임자", "기술책임자", "QA책임자"],
            "approver_role": "제품책임자",
            "supersedes": None,
        }, {
            "change_id": "REQ-CHG-20260722-002",
            "change_type": "NOT_EFFECTIVE_FP035_CORRECTION_CANDIDATE_BINDING",
            "recorded_on": AS_OF,
            "summary": "기존 SP-13·FP-035 답변을 담은 정정 후보를 요구 Draft에 연결했다. 정정 후보는 아직 승인·효력화되지 않았다.",
            "policy_change_claimed": False,
            "affected_requirement_ids": ["RQ-FP-035-001"],
            "before_version": "0.1.0",
            "after_version": VERSION,
            "approval_status": "NOT_APPROVED",
            "effective_status": "NOT_EFFECTIVE",
            "policy_correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
            "policy_correction_candidate_path": _repo_path(FP035_CORRECTION_CANDIDATE_PATH),
            "policy_correction_candidate_sha256": _file_sha256(FP035_CORRECTION_CANDIDATE_PATH),
            "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
            "approval_blockers": copy.deepcopy(FP035_APPROVAL_BLOCKERS),
            "authoring_and_planning_readiness": "ALLOWED",
            "mobile_network_branch_implementation_and_test_readiness": "BLOCKED_PENDING_BUNDLED_APPROVAL",
            "affected_artifact_codes": ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"],
            "change_control_refs": ["CR-0002", FP035_NETWORK_ISSUE_ID, "RAID-011"],
            "required_related_record": {"artifact_code": "REQ-18", "path": _repo_path(CHANGE_LOG_PATH)},
            "verification_status": "NOT_RUN",
            "source_refs": [FP035_NORMALIZATION_ID, FP035_CORRECTION_CANDIDATE_ID, "FP-035", "SP-13", "CD-UPLOAD-NETWORK", _repo_path(OWNER_REVIEW_PATH), _repo_path(FP035_CORRECTION_CANDIDATE_PATH)],
            "requirement_binding_sha256": rtm["requirement_binding_sha256"],
            "impact_analysis": "REQ-03·REQ-06·RTM과 DES-04·DES-13·DES-20 Draft에 네트워크 상태 분기를 결속한다. 정책 1.0.0 원본과 실행증거는 변경하지 않으며 새 묶음 승인 전 정책 효력을 주장하지 않는다.",
            "reviewer_roles": ["제품책임자", "기술책임자", "QA책임자"],
            "approver_role": "제품책임자",
            "supersedes": FP035_NETWORK_ISSUE_ID,
        }],
    }
    change_log["content_sha256"] = _object_sha256(change_log)
    return change_log


def _md(value: Any) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def _artifact_heading(number: int, title: str, rtm: dict[str, Any]) -> list[str]:
    code = f"REQ-{number:02d}"
    management = rtm["artifact_management_plan"][code]
    required_contents = "<br>".join(f"- {_md(item)}" for item in management["required_contents"])
    required_inputs = "<br>".join(f"- {_md(item)}" for item in management["required_inputs"])
    completion = "<br>".join(f"- {_md(item)}" for item in management["completion_criteria"])
    update_triggers = "<br>".join(f"- {_md(item)}" for item in management["update_triggers"])
    upstream = ", ".join(f"`{item.removeprefix('DLV-')}`" for item in management["upstream_types"]) or "없음"
    downstream = ", ".join(f"`{item.removeprefix('DLV-')}`" for item in management["downstream_types"]) or "없음"
    reviewers = ", ".join(management["reviewer_roles"])
    supporting = ", ".join(f"`{item}`" for item in management["supporting_locations"]) or "없음"
    fp035_rows: list[str] = []
    if code in {"REQ-03", "REQ-06"}:
        fp035_rows = [
            f"| FP-035 정정 후보 | `{FP035_CORRECTION_CANDIDATE_ID}` · 직접 영향 · `NOT_APPROVED / NOT_EFFECTIVE` |",
            f"| 승인 차단조건 | {', '.join(f'`{item}`' for item in management['approval_blockers'])} |",
            f"| 효력 발생 사건 | `{management['required_activation_event']}` |",
            f"| 승인 전 허용 범위 | 작성·계획 `{management['authoring_and_planning_readiness']}` |",
            f"| 이동통신망 분기 | 구현·정식시험 `{management['mobile_network_branch_implementation_and_test_readiness']}` |",
        ]
    elif code == "REQ-18":
        fp035_rows = [
            f"| FP-035 추적 역할 | `{management['correction_tracking_role']}` · 직접 영향 아님 |",
            f"| 정정 후보·변경통제 | `{FP035_CORRECTION_CANDIDATE_ID}` · {', '.join(f'`{item}`' for item in management['change_control_refs'])} |",
            f"| 필수 기록 위치 | `{management['required_tracking_location']}` |",
            "| 효력 경계 | 이 변경기록만으로 정책을 승인하거나 효력화하지 않음 |",
        ]
    return [
        f'<a id="req-{number:02d}"></a>',
        "",
        f"## {code} {title}",
        "",
        "| 항목 | 현재 값 |",
        "|---|---|",
        f"| 산출물 유형 | `{code}` (기계관리 ID: `DLV-{code}`) |",
        "| 생명주기 | `DRAFT` |",
        "| 승인 | `NOT_APPROVED` |",
        "| 기준선 | `NOT_BASELINED` |",
        "| 검증 | `NOT_RUN` |",
        *fp035_rows,
        f"| 작성 목적 | {_md(management['purpose'])} |",
        f"| 필수/조건 | `{management['applicability']}` · {_md(management['activation_condition'])} |",
        f"| 들어갈 내용 | {required_contents} |",
        f"| 작성 입력 | {required_inputs} |",
        f"| 선행 → 후속 | {upstream} → {downstream} |",
        f"| 작성·검토·승인 | {management['owner_role']} · {reviewers} · {management['approver_role']} |",
        f"| 형식·정본 위치 | `{management['recommended_form']}` · `{management['canonical_location']}#{management['coverage_anchor']}` |",
        f"| 보조 파일 | {supporting} |",
        f"| 완료·승인 기준 | {completion} |",
        f"| 갱신 조건 | {update_triggers} |",
        f"| 검토 주기 | {_md(management['review_cycle'])} |",
        f"| 변경·대체·폐기 | {_md(management['change_and_retirement_rule'])} |",
        "",
        "쉽게 말하면, 이 표는 ‘왜 만드는지, 무엇을 채워야 하는지, 누가 확인하며 언제 다시 고치는지’를 이 산출물 하나에 대해 정한 관리 약속입니다.",
        "",
    ]


DETAIL_LABELS = {
    "stop_or_pause": "멈추거나 일시중지할 때",
    "resume": "다시 시작할 때",
    "screen": "화면 안내",
    "speech": "음성 안내",
    "vibration": "진동 안내",
    "on_device": "휴대전화가 하는 일",
    "server": "서버가 하는 일",
    "external": "외부서비스가 하는 일",
    "current_policy.stored_on_device": "휴대전화에 저장",
    "current_policy.sent_to_server": "서버로 전송",
    "current_policy.delete_from_device": "휴대전화에서 삭제",
    "current_policy.server_retention": "서버 보존·삭제기한",
}
HIDDEN_DETAIL_KEYS = {"uses_common_policy", "policy_control_only", "common_policy_ids"}
DETAIL_VALUE_LABELS = {
    True: "예",
    False: "아니오",
    "REQUIRED": "필수",
    "CONDITIONAL": "해당 기능을 사용할 때 필요",
}


def _flatten_detail(value: Any, prefix: str = "", key_path: str = "") -> list[str]:
    if value is None or value == [] or value == {}:
        return []
    if isinstance(value, (str, bool)):
        plain_value = DETAIL_VALUE_LABELS.get(value, value)
        return [f"{prefix}: {plain_value}" if prefix else str(plain_value)]
    if isinstance(value, list):
        result: list[str] = []
        for item in value:
            result.extend(_flatten_detail(item, prefix, key_path))
        return result
    if isinstance(value, dict):
        if {"id", "title", "summary"}.issubset(value):
            return [f"{value['id']} {value['title']}: {value['summary']}"]
        result = []
        for key, item in value.items():
            if key in HIDDEN_DETAIL_KEYS:
                continue
            raw_path = f"{key_path}.{key}" if key_path else key
            if raw_path == "current_policy":
                result.extend(_flatten_detail(item, "", raw_path))
                continue
            label = DETAIL_LABELS.get(raw_path, DETAIL_LABELS.get(key, key.replace("_", " ")))
            result.extend(_flatten_detail(item, label, raw_path))
        return result
    return [f"{prefix}: {value}" if prefix else str(value)]


def _detail_block(label: str, value: Any) -> list[str]:
    if label == "필요 권한" and isinstance(value, list):
        permissions = []
        for item in value:
            if not isinstance(item, dict):
                permissions.extend(_flatten_detail(item))
                continue
            name = item.get("name", "이름이 정해지지 않은 권한")
            need = item.get("requirement_label") or DETAIL_VALUE_LABELS.get(
                item.get("requirement"), item.get("requirement", "필요 여부 미정")
            )
            reason = item.get("plain_reason", "필요한 이유를 검토해야 한다.")
            permissions.append(f"{name} — {need}: {reason}")
        if not permissions:
            return [f"**{label}:** 해당 없음", ""]
        return [f"**{label}**", "", *[f"- {item}" for item in permissions], ""]
    items = _flatten_detail(value)
    if not items:
        return [f"**{label}:** 해당 없음", ""]
    return [f"**{label}**", "", *[f"- {item}" for item in items], ""]


def _document_header(title: str, bundle_id: str, artifact_ids: list[str]) -> list[str]:
    return [
        f"# {title}",
        "",
        "> 이 문서는 승인된 기능정책을 요구사항으로 옮긴 **검토 전 Draft**입니다. 정책은 승인됐지만 이 요구 문서, 설계, 구현, 시험은 아직 승인되거나 완료된 것이 아닙니다.",
        "",
        "| 항목 | 값 |",
        "|---|---|",
        f"| 문서 묶음 | `{bundle_id}` |",
        f"| 문서 버전 | `{VERSION}` |",
        f"| 기준일 | `{AS_OF}` |",
        "| 포함 산출물 유형 | " + ", ".join(f"`{item}`" for item in artifact_ids) + " |",
        "| 생명주기 | `DRAFT` |",
        "| 승인 | `NOT_APPROVED` |",
        "| 출시 판단 | `NOT_ELIGIBLE` |",
        "",
        "상태를 읽는 법: `SOURCE_ONLY`는 자료가 있다는 뜻일 뿐 요구 충족 판정이 아닙니다. `REQUIREMENT_DRAFTED`는 정책을 요구 문장으로 옮겼다는 뜻입니다. `NOT_RUN`은 시험하지 않았다는 뜻입니다.",
        "",
        "추적표의 다른 상태도 완료 판정이 아닙니다. `DRAFT_DESIGN_LINKS_DECLARED`는 설계 Draft로 가는 길을 연결했다는 뜻, `EXISTS_REVALIDATION_REQUIRED`는 기존 코드 후보를 다시 확인해야 한다는 뜻, `NOT_LINKED`는 증거 연결이 없다는 뜻, `NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL`은 공통정책 행 자체에는 코드 하나를 직접 연결하지 않는다는 뜻입니다.",
        "",
        "각 REQ 장의 첫 표에는 작성 목적, 필수 내용, 입력, 선후관계, 담당 역할, 완료 기준, 갱신·대체 방법을 함께 적었습니다. 정책 내용과 문서 관리 방법을 한 곳에서 확인하기 위한 것입니다.",
        "",
    ]


def build_system_markdown(rtm: dict[str, Any], sources: dict[str, Any]) -> str:
    policy = sources["policy"]
    row_by_source = {row["source_policy_id"]: row for row in rtm["requirements"]}
    feature_by_id = {feature["id"]: feature for feature in policy["features"]}
    lines = _document_header(
        "WalkSafe 시스템·소프트웨어 요구사항 Draft",
        "BND-REQ-BASELINE",
        REQ_BUNDLES["BND-REQ-BASELINE"]["artifact_type_ids"],
    )
    lines += [
        "## 먼저 보는 전체 그림",
        "",
        "WalkSafe는 시각장애인의 판단을 대신하거나 안전을 보장하는 서비스가 아닙니다. 전맹과 저시력 사용자를 같은 우선순위로 두고, 휴대전화의 카메라·위치·음성·진동을 이용해 위험 후보와 경로 정보를 알기 쉽게 제공하며, 불확실할 때는 추측 안내를 멈추고 최종 행동은 사용자가 선택하도록 돕습니다.",
        "",
        "이 Draft에는 정책 출처 68개가 각각 하나의 상위 요구로 들어 있습니다: 기능 54개, 여러 기능이 함께 지킬 공통정책 9개, 출시 전에 닫아야 할 미결 게이트 5개입니다. 세부 규칙은 각 상위 요구 아래 고유 clause ID로 쪼개어 `rtm.json`에 보존했습니다.",
        "",
        f"### FP-035 정정 후보 연결 `{FP035_CORRECTION_CANDIDATE_ID}`",
        "",
        FP035_NORMALIZED_RULE,
        "",
        f"이 문장은 새 질문에 대한 추측이 아니라 기존 SP-13·FP-035 답변을 담은 `{FP035_NORMALIZATION_ID}` Draft 규칙입니다. 정책 1.0.0 원본은 고치지 않았습니다. 정정 후보의 상태는 `NOT_APPROVED / NOT_EFFECTIVE`이며, 후보와 영향 산출물이 새 묶음으로 승인되기 전에는 정책 효력을 주장하지 않습니다. 시험도 `NOT_RUN`입니다.",
        "",
        "### 시스템 경계",
        "",
        "| 경계 안 | 경계 밖 또는 외부 의존성 |",
        "|---|---|",
        "| Android 앱의 카메라·위치·음성 입력, 위험 후보 처리, 경로 진행, 음성·진동·접근성 안내, 신고 대기열 | 휴대전화 운영체제와 센서의 정확도 자체 |",
        "| WalkSafe 서버의 계정·동의·경로 중계·신고·자료 보존·운영 상태 관리 | TMAP 경로 서비스의 정확도·가용성·쿼터 |",
        "| 관리자 접근·복구·감사기록과 정책에 따른 삭제·용량 통제 | 이동통신망 또는 Wi-Fi의 연결 품질 |",
        "| 학습·평가 자료와 모델의 버전·해시·임계값 관리 | 사용자의 주변 환경에 위험이 전혀 없다는 보장 |",
        "",
    ]

    lines += _artifact_heading(1, "SRD(System Requirements Document)", rtm)
    lines += [
        "### 목적과 사용자",
        "",
        "주 사용자는 전맹과 저시력 사용자를 같은 우선순위로 둔 시각장애인입니다. 현장시험 참여자, 단일 프로젝트 관리자, 개발·시험 책임자는 운영 이해관계자입니다. 청각·지체장애인을 별도 공식 사용자 범위로 확대한 것은 아닙니다. 앱은 위험을 ‘확정’하거나 사용자의 결정을 빼앗지 않고, 관찰된 정보를 일관된 방식으로 전달해야 합니다.",
        "",
        "### 18개 기능 영역",
        "",
        "| 영역 | 쉬운 범위 | 기능 |",
        "|---|---|---|",
    ]
    for area in policy["areas"]:
        lines.append(f"| `{area['id']}` { _md(area['title']) } | {_md(area['plain_scope'])} | {', '.join(f'`{item}`' for item in area['feature_ids'])} |")
    lines += [
        "",
        "### 시스템 수준 제약",
        "",
        "- 위치·카메라·마이크 권한이 없거나 입력을 믿을 수 없으면 관련 안내를 억지로 계속하지 않습니다.",
        "- 원본 자료 수집은 승인된 수집 목적, 수집 항목, 접근자, 보존기간, 삭제조건 안에서만 수행합니다.",
        "- 자동신고는 최초 동의 뒤 후보마다 알리지 않지만, 사용자는 설정에서 향후 자동신고를 끄거나 개인정보 삭제를 요청할 수 있습니다.",
        "- 보폭은 거리 진행의 보조값입니다. GPS·저장 경로를 대신해 좌표, 진행 방향, 경로 이탈을 단독 판단하지 않습니다.",
        "- 미결 게이트 5개가 모두 닫히고 요구·설계·시험 승인이 끝나기 전에는 출시 가능 판정을 내리지 않습니다.",
        "",
    ]

    lines += _artifact_heading(2, "SRS(Software Requirements Specification)", rtm)
    lines += [
        "### 소프트웨어가 지켜야 할 공통 형식",
        "",
        "각 요구는 고유 ID, 시작 조건, 입력, 결과, 정상 흐름, 중지·재개 규칙, 실패 시 동작, 금지 동작, 인수조건, 예정 시험을 가집니다. 요구 ID는 이후 설계와 시험에서 바꾸지 않고 참조합니다.",
        "",
        "### 상태 전이",
        "",
        "`DRAFT → IN_REVIEW → APPROVED/BASELINED` 순서로만 이동합니다. 현재는 전부 `DRAFT`입니다. 정책 문서가 승인됐다는 이유로 요구, 코드, 시험 상태를 자동 승격하지 않습니다.",
        "",
        "### 외부 입력 실패의 기본 원칙",
        "",
        "위치·경로·카메라·음성·서버 상태 중 필수 입력이 없거나 오래됐거나 서로 모순되면, 해당 기능은 불확실한 결과를 사실처럼 안내하지 않습니다. 사용자에게 필요한 범위의 상태를 설명하고 안전한 중지 또는 재시도를 선택하게 합니다.",
        "",
    ]

    lines += _artifact_heading(3, "기능 요구사항", rtm)
    lines += [
        "아래 54개 기능 요구는 승인된 FP 정책과 1:1입니다. 각 표 뒤에 목적·흐름·데이터·실패·금지 규칙을 쉬운 문장으로 풀어 씁니다. 고유 clause와 전체 결정 연결은 `rtm.json`과 `rtm.html`에서 확인합니다.",
        "",
    ]
    for area in policy["areas"]:
        lines += [f"### {area['id']} {area['title']}", "", area["plain_scope"], "", "| 요구 ID | 기능 | 쉬운 요구 요약 | 인수조건 | 미결 게이트 |", "|---|---|---|---:|---|"]
        for feature_id in area["feature_ids"]:
            row = row_by_source[feature_id]
            gates = ", ".join(f"`{item}`" for item in row["gate_refs"]) or "없음"
            lines.append(f"| `{row['requirement_id']}` | `{feature_id}` {_md(row['title'])} | {_md(row['plain_requirement'])} | {len(row['acceptance_conditions'])} | {gates} |")
        lines.append("")
        for feature_id in area["feature_ids"]:
            feature = feature_by_id[feature_id]
            row = row_by_source[feature_id]
            acceptance_refs = ", ".join(f"`{item['acceptance_condition_id']}`" for item in row["acceptance_conditions"])
            test_refs = ", ".join(f"`{item}`" for item in row["planned_test_ids"])
            gate_refs = ", ".join(f"`{item}`" for item in row["gate_refs"]) or "없음"
            lines += [
                f'<a id="{row["requirement_id"]}"></a>',
                "",
                f"#### {row['requirement_id']} — {feature['name']}",
                "",
                f"**현재 상태:** `DRAFT` · `NOT_APPROVED` · 시험 `NOT_RUN`",
                "",
                f"**사용자에게 필요한 이유:** {feature['user_purpose']}",
                "",
                f"**확정 요구:** {feature['effective_policy_summary']}",
                "",
            ]
            if feature_id == "FP-035":
                lines += [
                    f"**정정 후보 Draft 적용:** `{FP035_CORRECTION_CANDIDATE_ID}` / `{FP035_NORMALIZATION_ID}` — 걷는 동안 전송 금지, 정지+Wi-Fi 허용, 정지+명시적 이동통신망 선택 허용, 미선택 시 Wi-Fi 대기 네 분기를 Draft에 적용했습니다. 후보는 `NOT_APPROVED / NOT_EFFECTIVE`이며 정책 원본 변경·정책 효력·시험 통과를 주장하지 않습니다.",
                    "",
                    "| 분기 | 상태 | 기대 동작 |",
                    "|---|---|---|",
                    *[f"| `{item['branch_id']}` | `{item['walking_state']}` · Wi-Fi `{item['wifi_available']}` · 이동통신망 선택 `{item['mobile_network_opt_in']}` | {_md(item['easy_explanation'])} (`{item['expected_transfer']}`) |" for item in FP035_EXPECTED_BRANCHES],
                    "",
                ]
            if feature_id == "FP-030":
                lines += [
                    "**사용자 범위 설명:** 여기서 운동 제약이나 스위치 조작은 전맹·저시력 사용자 중 해당 접근성 수단이 필요한 경우를 뜻합니다. 청각·지체장애인을 별도 공식 사용자군으로 넓힌다는 뜻은 아닙니다.",
                    "",
                ]
            lines += _detail_block("언제 시작하는가", feature["start_conditions"])
            lines += _detail_block("무엇을 입력으로 쓰는가", feature["inputs"])
            lines += _detail_block("무엇을 결과로 내는가", feature["outputs"])
            lines += _detail_block("정상 작동 순서", feature["normal_flow"])
            lines += _detail_block("중지·재개·종료 규칙", feature["lifecycle_rules"])
            lines += _detail_block("반드시 지킬 설계 규칙", feature["design_rules"])
            lines += _detail_block("실패하거나 믿을 수 없을 때", feature["failure_behavior"])
            lines += _detail_block("하면 안 되는 동작", feature["prohibited_behaviors"])
            lines += _detail_block("사용자에게 보여 주거나 들려줄 안내", feature["user_guidance"])
            lines += _detail_block("필요 권한", feature["permissions"])
            lines += _detail_block("휴대전화·서버·외부서비스의 역할", feature["execution_boundary"])
            lines += _detail_block("수집·전송·보존·삭제", feature["data_handling"])
            lines += _detail_block("함께 적용되는 공통정책", feature["common_policy_effects"])
            lines += [
                "**추적과 확인**",
                "",
                f"- 정렬 결정: {len(row['decision_refs'])}개",
                f"- 인수조건: {acceptance_refs}",
                f"- 예정 시험: {test_refs} (전부 `NOT_RUN`)",
                f"- 미결 게이트: {gate_refs}",
                f"- 기존 구현 관찰: {row['implementation_observation']} 이 문장은 과거 자료의 관찰이며 현재 요구 충족 판정이 아닙니다.",
                "",
            ]

    lines += _artifact_heading(4, "비기능 요구사항", rtm)
    lines += [
        "기능 여러 개에 공통으로 적용되는 9개 정책을 별도 요구로 유지합니다. 기능 요구에 흩어 써서 누락되지 않게 하기 위한 장치입니다.",
        "",
        "| 요구 ID | 공통정책 | 쉬운 설명 | 영향 기능 수 | 관련 게이트 |",
        "|---|---|---|---:|---|",
    ]
    for common in policy["common_policies"]:
        row = row_by_source[common["id"]]
        gates = ", ".join(f"`{item}`" for item in row["gate_refs"]) or "없음"
        lines.append(f"| `{row['requirement_id']}` | `{common['id']}` {_md(common['title'])} | {_md(common['summary'])} | {len(common['affected_feature_ids'])} | {gates} |")
    lines.append("")
    for common in policy["common_policies"]:
        row = row_by_source[common["id"]]
        lines += [
            f'<a id="{row["requirement_id"]}"></a>',
            "",
            f"### {row['requirement_id']} — {common['title']}",
            "",
            "**현재 상태:** `DRAFT` · `NOT_APPROVED` · 시험 `NOT_RUN`",
            "",
            common["summary"],
            "",
            "**모든 적용 기능이 지킬 규칙**",
            "",
            *[f"- {rule}" for rule in common["rules"]],
            "",
            f"**영향 기능:** {', '.join(f'`{item}`' for item in common['affected_feature_ids'])}",
            "",
            f"**예정 확인:** `{row['acceptance_conditions'][0]['acceptance_condition_id']}` → `{row['planned_test_ids'][0]}` (`NOT_RUN`)",
            "",
        ]
    lines += ["", "### 출시 전 반드시 닫을 5개 요구", "", "| 요구 ID | 게이트 | 현재 상태 | 종료 조건 |", "|---|---|---|---|"]
    for gate in policy["remaining_gates"]:
        row = row_by_source[gate["id"]]
        lines.append(f"| `{row['requirement_id']}` | `{gate['id']}` {_md(gate['title'])} | `NOT_RUN` | {_md(gate['closure'])} |")
    lines.append("")
    for gate in policy["remaining_gates"]:
        row = row_by_source[gate["id"]]
        lines += [
            f'<a id="{row["requirement_id"]}"></a>',
            "",
            f"### {row['requirement_id']} — {gate['title']}",
            "",
            "**현재 상태:** `DRAFT` · 게이트 `NOT_RUN` · 면제 없음 · 시험 `NOT_RUN`",
            "",
            f"**왜 남았는가:** 정책 방향만으로 정할 수 없는 {gate['kind_label']} 자료가 필요합니다.",
            "",
            f"**닫는 조건:** {gate['closure']}",
            "",
            f"**영향 기능:** {', '.join(f'`{item}`' for item in gate['affected_feature_ids'])}",
            "",
            f"**예정 확인:** `{row['acceptance_conditions'][0]['acceptance_condition_id']}` → `{row['planned_test_ids'][0]}` (`NOT_RUN`)",
            "",
        ]

    for number in range(7, 16):
        artifact_id = f"DLV-REQ-{number:02d}"
        lines += _artifact_heading(number, DOMAIN_TITLES[artifact_id], rtm)
        domain_rows = [row for row in rtm["requirements"] if artifact_id in row["domain_artifact_type_ids"]]
        lines += [
            f"이 장은 별도 요구를 새로 만들어 정책을 바꾸는 대신, 68개 요구 중 **{len(domain_rows)}개**를 이 관점에서 모아 보여 줍니다.",
            "",
            "| 요구 ID | 종류 | 기능·정책 | 이 관점에서 지킬 핵심 |",
            "|---|---|---|---|",
        ]
        for row in domain_rows:
            kind = {"FEATURE_POLICY": "기능", "COMMON_POLICY": "공통", "REMAINING_GATE": "게이트"}[row["source_kind"]]
            lines.append(f"| `{row['requirement_id']}` | {kind} | `{row['source_policy_id']}` {_md(row['title'])} | {_md(row['plain_requirement'])} |")
        lines.append("")
        lines += [DOMAIN_COMPLETION_GUIDANCE[artifact_id].strip(), ""]
        if artifact_id == "DLV-REQ-10":
            lines += [
                "개인정보 관점의 공통 해석: 원본을 수집하더라도 수집 목적·항목·접근자·전송시점·보존기간·파기조건을 고지하고, 목적이 끝나거나 삭제조건이 생기면 정책에 따라 삭제해야 합니다. 자동신고를 끄면 새 후보 생성을 즉시 중단하고 미전송 후보는 삭제합니다.",
                "",
            ]
        if artifact_id == "DLV-REQ-12":
            lines += [
                "용량 임계값 70%·85%·95%·100%는 **서버 저장공간 기준**입니다. 휴대전화 대기열의 바이트 상한은 별도 게이트에서 실제 기기 측정으로 정합니다.",
                "",
            ]

    lines += [
        "## 검토자가 먼저 볼 질문",
        "",
        "- 각 요구의 쉬운 요약이 실제 의도와 같은가?",
        "- 세부 clause에 빠진 예외·금지 동작이 있는가?",
        "- 5개 게이트의 담당자·기한·측정 방법을 다음 검토에서 확정할 수 있는가?",
        "- 기존 코드 링크를 ‘구현 완료’로 오해할 문구가 없는가?",
        "- 승인 뒤 변경이 필요하면 `requirement-change-log.json`에 변경 사유와 영향을 남길 수 있는가?",
        "",
    ]
    return "\n".join(lines)


def build_acceptance_markdown(rtm: dict[str, Any], sources: dict[str, Any]) -> str:
    policy = sources["policy"]
    row_by_source = {row["source_policy_id"]: row for row in rtm["requirements"]}
    lines = _document_header(
        "WalkSafe 유스케이스·인수조건 Draft",
        "BND-REQ-ACCEPTANCE",
        REQ_BUNDLES["BND-REQ-ACCEPTANCE"]["artifact_type_ids"],
    )
    lines += _artifact_heading(5, "유스케이스·사용자 스토리", rtm)
    lines += [
        "사용자 스토리는 ‘누가, 왜, 무엇을 원하는가’를 쉽게 읽기 위한 설명입니다. 실제 구현 판단은 연결된 요구 ID와 인수조건을 따릅니다.",
        "",
    ]
    for area in policy["areas"]:
        features = [next(feature for feature in policy["features"] if feature["id"] == feature_id) for feature_id in area["feature_ids"]]
        lines += [
            f"### UC-{int(area['id'].split('-')[1]):02d} {area['title']}",
            "",
            "**주 행위자:** 시각장애인 사용자. 관리자 기능이 포함된 경우 지정 관리자 1명이 보조 행위자입니다.",
            "",
            f"**사용자 이야기:** WalkSafe 사용자 또는 운영자는 {_md(area['plain_scope'])}",
            "",
            "**시작 전 조건**",
            "",
            *[f"- `{feature['id']}` {_md(condition)}" for feature in features for condition in feature["start_conditions"]],
            "",
            "**관련 요구:** " + ", ".join(f"`{_requirement_id(feature['id'])}`" for feature in features),
            "",
            "**기본 흐름**",
            "",
        ]
        for feature in features:
            lines.append(f"{feature['order']}. **{feature['name']}** — {feature['plain_summary']}")
        lines += [
            "",
            "**완료 뒤 상태**",
            "",
            *[f"- `{feature['id']}` {_md(output)}" for feature in features for output in feature["outputs"]],
            "",
            "**대안·예외 흐름**",
            "",
            *[f"- `{feature['id']}` {_md(failure)}" for feature in features for failure in feature["failure_behavior"]],
            "",
            "필수 입력·권한·외부 연결을 신뢰할 수 없으면 추측 결과를 계속 안내하지 않고, 위 실패 동작과 안전정지를 적용합니다.",
            "",
        ]

    lines += _artifact_heading(6, "인수 조건", rtm)
    lines += [
        "총 279개 인수조건은 279개 예정 시험과 1:1입니다. 지금은 모두 `DRAFT`/`NOT_RUN`이며 통과를 주장하지 않습니다.",
        "",
        f"`AC-FP-035-01~04`는 `{FP035_CORRECTION_CANDIDATE_ID}` / `{FP035_NORMALIZATION_ID}`의 네 Draft 분기와 1:1로 연결했습니다. 정정 후보는 `NOT_APPROVED / NOT_EFFECTIVE`이며 모두 예정 시험 `NOT_RUN`입니다.",
        "",
        "Given은 준비 조건, When은 확인 절차, Then은 눈으로 확인하거나 기록으로 남겨야 할 결과입니다.",
        "",
    ]
    for row in rtm["requirements"]:
        if row["source_policy_id"] == "FP-035":
            lines += [
                f"**네트워크 분기:** `{FP035_CORRECTION_CANDIDATE_ID}` / `{FP035_NORMALIZATION_ID}`의 미효력 정정 후보를 따라 아래 네 인수조건을 걷는 중, 정지+Wi-Fi, 정지+이동통신망 선택, 정지+미선택 순서로 작성했습니다. 새 묶음 승인과 실행 결과는 아직 없습니다.",
                "",
            ]
        lines += [
            f'<a id="{row["requirement_id"]}-acceptance"></a>',
            "",
            f"### {row['requirement_id']} — {row['title']}",
            "",
            f"출처: `{row['source_policy_id']}` · 요구 상태: `DRAFT` · 시험 상태: `NOT_RUN`",
            "",
            "| 인수조건 | Given(준비) | When(확인) | Then(보여야 할 결과) | 예정 시험 |",
            "|---|---|---|---|---|",
        ]
        for acceptance in row["acceptance_conditions"]:
            given = "<br>".join(_md(item) for item in acceptance["given"])
            when = "<br>".join(_md(item) for item in acceptance["when"])
            lines.append(
                f"| <a id=\"{acceptance['acceptance_condition_id']}\"></a>`{acceptance['acceptance_condition_id']}` | {given} | {when} | {_md(acceptance['then'])} | `{acceptance['planned_test_id']}` (`NOT_RUN`) |"
            )
        lines.append("")
    return "\n".join(lines)


def build_trace_markdown(
    rtm: dict[str, Any],
    glossary: dict[str, Any],
    change_log: dict[str, Any],
) -> str:
    lines = _document_header(
        "WalkSafe 요구사항 추적·기준선·변경·용어 Draft",
        "BND-REQ-TRACE",
        REQ_BUNDLES["BND-REQ-TRACE"]["artifact_type_ids"],
    )
    lines += _artifact_heading(16, "RTM(Requirements Traceability Matrix)", rtm)
    coverage = rtm["coverage"]
    lines += [
        "RTM은 ‘왜 이 요구가 생겼는지’와 ‘앞으로 무엇으로 확인할지’를 연결합니다. 코드나 자료 링크가 있어도 현재 요구를 충족했다는 뜻은 아닙니다.",
        "",
        "| 확인 항목 | 수 |",
        "|---|---:|",
        f"| 요구 | {coverage['requirement_count']} |",
        f"| 기능 / 공통정책 / 게이트 | {coverage['feature_requirement_count']} / {coverage['common_policy_requirement_count']} / {coverage['remaining_gate_requirement_count']} |",
        f"| 세부 clause | {coverage['requirement_clause_count']} |",
        f"| 인수조건·예정 시험 | {coverage['acceptance_condition_count']} / {coverage['planned_test_count']} |",
        f"| 정렬된 결정 / 결정-기능 연결 | {coverage['aligned_decision_count']} / {coverage['decision_feature_edge_count']} |",
        f"| 미결 게이트 | {coverage['remaining_gate_count']} |",
        f"| 열린 정책 정합성 이슈 | {coverage['open_source_policy_issue_count']} |",
        f"| 새 제품질문 필요 | {coverage['owner_content_question_required_count']} |",
        f"| 연결된 미효력 정정 후보 | {coverage['not_effective_policy_correction_candidate_count']} |",
        f"| 시험 실행 완료 주장 | {coverage['verification_completion_claim_count']} |",
        "",
        "기계가독 원본은 [`rtm.json`](rtm.json), 사람이 검색하기 쉬운 화면은 [`rtm.html`](rtm.html)입니다.",
        "",
        "| 요구 ID | 출처 | 상태 | 결정 수 | 인수조건 | 설계 | 코드 | 자료 |",
        "|---|---|---|---:|---:|---|---|---|",
    ]
    for row in rtm["requirements"]:
        lines.append(
            f"| `{row['requirement_id']}` | `{row['source_policy_id']}` | `{row['trace_status']}` | {len(row['decision_refs'])} | {len(row['acceptance_conditions'])} | `{row['design_trace']['status']}` | `{row['code_trace']['status']}` | `{row['evidence_trace']['status']}` |"
        )
    lines.append("")

    lines += _artifact_heading(17, "요구사항 기준선", rtm)
    lines += [
        "현재 파일은 기준선이 아니라 **기준선 후보 Draft**입니다. 승인된 것은 입력 정책 기준선이며, 이 요구 묶음의 승인·기준선 고정은 별도로 해야 합니다.",
        "",
        "| 항목 | 값 |",
        "|---|---|",
        "| 제안 요구 기준선 ID | `RB-WALKSAFE-REQUIREMENTS-1.0.0` |",
        f"| 현재 문서 버전 | `{VERSION}` |",
        "| 현재 생명주기 | `DRAFT` |",
        "| 승인 | `NOT_APPROVED` |",
        "| 기준선 | `NOT_BASELINED` |",
        "| 출시 | `NOT_ELIGIBLE` |",
        "| Draft manifest | [`requirements-draft-20260721-r001.json`](../manifests/requirements-draft-20260721-r001.json) |",
        "",
        "### 기준선 승인 전 체크",
        "",
        "1. 68개 요구와 세부 clause의 뜻을 제품책임자·기술책임자·QA책임자가 검토합니다.",
        "2. 279개 인수조건이 실제로 관찰 가능하고 서로 모순되지 않는지 검토합니다.",
        "3. 5개 미결 게이트에 담당자·기한·측정 또는 검토 방법을 배정합니다.",
        f"4. `{FP035_NORMALIZATION_ID}`의 네 전송망 분기가 REQ-03·REQ-06·DES-04·DES-13·DES-20에서 같은지 확인합니다.",
        "5. 별도 교차추적 보고서에서 요구·설계·시험 ID와 파일 지문을 확인합니다.",
        "6. 명시적 승인 기록을 만든 뒤에만 버전을 1.0.0으로 올리고 기준선을 고정합니다.",
        "",
    ]

    lines += _artifact_heading(18, "요구사항 변경이력", rtm)
    lines += [
        "기계가독 변경대장은 [`requirement-change-log.json`](requirement-change-log.json)입니다.",
        "",
        "| 변경 ID | 종류 | 설명 | 영향 요구 | 정책 변경 | 승인 |",
        "|---|---|---|---:|---|---|",
    ]
    for entry in change_log["entries"]:
        lines.append(f"| `{entry['change_id']}` | `{entry['change_type']}` | {_md(entry['summary'])} | {len(entry['affected_requirement_ids'])} | `{str(entry['policy_change_claimed']).lower()}` | `{entry['approval_status']}` |")
    lines += [
        "",
        "앞으로 요구 문장을 고치면 새 change ID, 변경 전·후 버전, 변경 이유, 영향 요구·설계·시험, 검토자와 승인 결과를 새 항목으로 추가해야 합니다. 기존 이력은 덮어쓰지 않습니다.",
        "",
    ]

    lines += _artifact_heading(19, "용어집", rtm)
    lines += [
        "기계가독 용어집은 [`glossary.json`](glossary.json)입니다. 아래 정의는 어려운 용어를 같은 뜻으로 읽기 위한 Draft이며, 정책 의미를 줄이거나 바꾸지 않습니다.",
        "",
        "| 용어 | 영문·약어 | 쉬운 뜻 | 관련 요구 |",
        "|---|---|---|---|",
    ]
    for entry in glossary["entries"]:
        related = ", ".join(f"`{item}`" for item in entry["related_requirement_ids"])
        lines.append(f"| {_md(entry['term'])} | {_md(entry['english_or_abbreviation'] or '-')} | {_md(entry['easy_definition'])} | {related} |")
    lines += [
        "",
        "## 현재 문서 묶음의 승인 경계",
        "",
        "- 정책 입력: 승인됨",
        "- 요구사항 68개: Draft, 미승인",
        "- 설계 연결: 별도 교차추적 보고서에서 존재·ID·파일 지문을 검증하고 이 RTM에는 승인되지 않은 Draft 링크로 표시",
        "- 예정 시험 279개: 실행하지 않음",
        f"- FP-035 정정 후보: `{FP035_CORRECTION_CANDIDATE_ID}` / `{FP035_NORMALIZATION_ID}` 1건, 기존 답변을 네 네트워크 분기로 Draft에 결속; `NOT_APPROVED / NOT_EFFECTIVE`, 시험 `NOT_RUN`",
        "- 미결 게이트 5개: `NOT_RUN`, 면제 없음",
        "- 출시: 불가",
        "",
    ]
    return "\n".join(lines)


def build_rtm_html(rtm: dict[str, Any]) -> str:
    embedded = json.dumps(rtm, ensure_ascii=False, separators=(",", ":"))
    embedded = embedded.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    return f"""<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>WalkSafe 요구사항 추적표 Draft</title>
  <style>
    :root {{ color-scheme: light; font-family: system-ui,-apple-system,"Noto Sans KR",sans-serif; color:#162238; background:#f3f6fa; }}
    * {{ box-sizing:border-box; }}
    body {{ margin:0; }}
    header {{ background:#12345b; color:white; padding:24px max(20px,calc((100vw - 1440px)/2)); }}
    header h1 {{ margin:0 0 8px; font-size:clamp(1.5rem,3vw,2.3rem); }}
    header p {{ margin:5px 0; max-width:1000px; }}
    main {{ max-width:1440px; margin:auto; padding:20px; }}
    .warning {{ border-left:6px solid #d97706; background:#fff7df; padding:14px; margin-bottom:18px; }}
    .summary {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(145px,1fr)); gap:10px; margin:18px 0; }}
    .card {{ background:white; border:1px solid #d8e0ea; border-radius:10px; padding:13px; }}
    .card b {{ display:block; font-size:1.5rem; color:#12345b; }}
    .controls {{ display:grid; grid-template-columns:2fr repeat(3,minmax(130px,1fr)); gap:10px; position:sticky; top:0; z-index:2; background:#f3f6fa; padding:10px 0; }}
    input,select {{ width:100%; min-height:44px; border:1px solid #9aa9bb; border-radius:8px; padding:9px; background:white; font:inherit; }}
    table {{ width:100%; border-collapse:collapse; background:white; font-size:.91rem; }}
    th,td {{ border:1px solid #d8e0ea; padding:8px; text-align:left; vertical-align:top; }}
    th {{ background:#e9f0f8; position:sticky; top:64px; }}
    code {{ overflow-wrap:anywhere; }}
    details {{ margin-top:6px; }}
    .pill {{ display:inline-block; padding:2px 7px; border-radius:999px; background:#e8eef5; margin:1px; font-size:.8rem; }}
    .draft {{ background:#fff0c2; color:#7a4b00; }}
    .muted {{ color:#53647a; }}
    .empty {{ padding:30px; text-align:center; }}
    @media(max-width:850px) {{ .controls {{ grid-template-columns:1fr 1fr; }} .table-wrap {{ overflow:auto; }} th {{ position:static; }} }}
  </style>
</head>
<body>
<header>
  <h1>WalkSafe 요구사항 추적표 <span class="pill draft">DRAFT</span></h1>
  <p>정책은 승인됐지만 이 요구사항, 설계, 구현, 시험은 아직 승인·완료되지 않았습니다.</p>
  <p>코드·자료 링크는 존재 여부만 보여 주며 요구 충족 판정이 아닙니다.</p>
</header>
<main>
  <div class="warning"><strong>출시 판단 금지:</strong> 미결 게이트 5개가 열려 있고 예정 시험은 모두 실행 전입니다.</div>
  <div class="warning"><strong>FP-035 정정 후보 Draft:</strong> 걷는 동안에는 보내지 않고, 정지 뒤 Wi-Fi 또는 사용자가 명시적으로 선택한 이동통신망만 사용합니다. 미선택 사용자는 Wi-Fi에서만 전송합니다. 후보는 NOT_APPROVED / NOT_EFFECTIVE이며 시험도 NOT_RUN입니다.</div>
  <section class="summary" id="summary"></section>
  <section class="controls" aria-label="검색과 필터">
    <input id="search" type="search" placeholder="요구 ID, 기능명, 설명 검색" aria-label="요구 검색">
    <select id="kind" aria-label="종류 필터"><option value="">모든 종류</option><option value="FEATURE_POLICY">기능</option><option value="COMMON_POLICY">공통정책</option><option value="REMAINING_GATE">미결 게이트</option></select>
    <select id="area" aria-label="영역 필터"><option value="">모든 영역</option></select>
    <select id="gate" aria-label="게이트 필터"><option value="">게이트 관계 전체</option><option value="yes">게이트 있음</option><option value="no">게이트 없음</option></select>
  </section>
  <p id="count" aria-live="polite"></p>
  <div class="table-wrap">
    <table>
      <thead><tr><th>요구·출처</th><th>쉬운 요구</th><th>추적</th><th>인수조건·시험</th><th>기존 자료 상태</th></tr></thead>
      <tbody id="rows"></tbody>
    </table>
  </div>
</main>
<script id="rtm-data" type="application/json">{embedded}</script>
<script>
const data=JSON.parse(document.getElementById('rtm-data').textContent);
const esc=v=>String(v??'').replace(/[&<>\"']/g,c=>({{'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}}[c]));
const list=v=>(v||[]).map(x=>`<span class="pill">${{esc(x)}}</span>`).join(' ');
const summary=document.getElementById('summary');
const c=data.coverage;
summary.innerHTML=[['요구',c.requirement_count],['기능',c.feature_requirement_count],['공통정책',c.common_policy_requirement_count],['게이트',c.remaining_gate_requirement_count],['인수조건',c.acceptance_condition_count],['결정',c.aligned_decision_count],['시험 실행',0]].map(x=>`<div class="card"><b>${{x[1]}}</b>${{x[0]}}</div>`).join('');
const areas=[...new Set(data.requirements.map(r=>r.area_id).filter(Boolean))];
document.getElementById('area').innerHTML+=[...areas].map(x=>`<option value="${{esc(x)}}">${{esc(x)}}</option>`).join('');
const controls=['search','kind','area','gate'].map(id=>document.getElementById(id));
function render(){{
  const q=controls[0].value.trim().toLowerCase(),kind=controls[1].value,area=controls[2].value,gate=controls[3].value;
  const rows=data.requirements.filter(r=>{{
    const hay=JSON.stringify([r.requirement_id,r.source_policy_id,r.title,r.plain_requirement,r.requirement_clauses]).toLowerCase();
    return (!q||hay.includes(q))&&(!kind||r.source_kind===kind)&&(!area||r.area_id===area)&&(!gate||(gate==='yes')===Boolean(r.gate_refs.length));
  }});
  document.getElementById('count').textContent=`${{rows.length}}개 요구 표시`;
  document.getElementById('rows').innerHTML=rows.length?rows.map(r=>`<tr id="${{esc(r.requirement_id)}}">
    <td><code>${{esc(r.requirement_id)}}</code><br><strong>${{esc(r.title)}}</strong><br><span class="muted">${{esc(r.source_policy_id)}} · ${{esc(r.area_id||'공통')}}</span><br><span class="pill draft">${{esc(r.lifecycle_status)}}</span></td>
    <td>${{esc(r.plain_requirement)}}<details><summary>세부 규칙 ${{r.requirement_clauses.length}}개</summary><ol>${{r.requirement_clauses.map(x=>`<li><code>${{esc(x.clause_id)}}</code> ${{esc(x.statement)}}</li>`).join('')}}</ol></details></td>
    <td>결정 ${{r.decision_refs.length}}개<br>${{list(r.gate_refs)}}<details><summary>출처 보기</summary>${{list(r.source_refs)}}</details></td>
    <td>${{r.acceptance_conditions.length}}개 · 모두 NOT_RUN<details><summary>ID 보기</summary>${{r.acceptance_conditions.map(x=>`<p><code>${{esc(x.acceptance_condition_id)}}</code> → <code>${{esc(x.planned_test_id)}}</code><br>${{esc(x.then)}}</p>`).join('')}}</details></td>
    <td>설계: <code>${{esc(r.design_trace.status)}}</code><br>코드: <code>${{esc(r.code_trace.status)}}</code><br>자료: <code>${{esc(r.evidence_trace.status)}}</code><details><summary>연결 대상 보기</summary>${{r.design_trace.links.map(x=>`<p><code>${{esc(x.design_id)}}</code><br><code>${{esc(x.target)}}</code> · 초안 연결</p>`).join('')}}${{[...r.code_trace.links,...r.evidence_trace.links].map(x=>`<p><code>${{esc(x.path)}}:${{esc(x.lines||'')}}</code><br>${{esc(x.claim)}} · SOURCE_ONLY</p>`).join('')}}</details></td>
  </tr>`).join(''):`<tr><td colspan="5" class="empty">조건에 맞는 요구가 없습니다.</td></tr>`;
}}
controls.forEach(el=>el.addEventListener('input',render)); render();
</script>
</body>
</html>"""


def _manifest_source_bindings() -> dict[str, dict[str, str]]:
    paths = {
        "policy": POLICY_PATH,
        "policy_approval_record": APPROVAL_PATH,
        "policy_baseline_manifest": POLICY_MANIFEST_PATH,
        "review_resolution": RESOLUTION_PATH,
        "existing_owner_review_answers": OWNER_REVIEW_PATH,
        "fp035_correction_candidate_not_effective": FP035_CORRECTION_CANDIDATE_PATH,
        "aligned_decision_register": ALIGNMENT_PATH,
        "artifact_catalog": ARTIFACT_CATALOG_PATH,
        "runtime_observations": RUNTIME_ANALYSIS_PATH,
        "planned_test_catalog": PLANNED_TEST_CATALOG_PATH,
        "design_trace_mapping_source": design_builder.GENERATOR_PATH,
        "generator": GENERATOR_PATH,
    }
    return {
        key: {"path": _repo_path(path), "sha256": _file_sha256(path)}
        for key, path in paths.items()
    }


def build_manifest(non_manifest_outputs: dict[Path, bytes], sources: dict[str, Any], rtm: dict[str, Any]) -> dict[str, Any]:
    artifact_ids_by_path = {
        SYSTEM_PATH: [f"REQ-{number:02d}" for number in [1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 13, 14, 15]],
        ACCEPTANCE_PATH: ["REQ-05", "REQ-06"],
        TRACE_PATH: ["REQ-16", "REQ-17", "REQ-18", "REQ-19"],
        RTM_JSON_PATH: ["REQ-16"],
        RTM_HTML_PATH: ["REQ-16"],
        CHANGE_LOG_PATH: ["REQ-18"],
        GLOSSARY_PATH: ["REQ-19"],
    }
    bindings = _manifest_source_bindings()
    generated_files = []
    for path in (SYSTEM_PATH, ACCEPTANCE_PATH, TRACE_PATH, RTM_JSON_PATH, RTM_HTML_PATH, CHANGE_LOG_PATH, GLOSSARY_PATH):
        payload = non_manifest_outputs[path]
        generated_files.append({
            "path": _repo_path(path),
            "sha256": _bytes_sha256(payload),
            "byte_length": len(payload),
            "artifact_type_ids": artifact_ids_by_path[path],
        })
    manifest: dict[str, Any] = {
        "schema_version": "walksafe.formal-requirements-draft-manifest.v1",
        "metadata": {
            "title": "WalkSafe REQ 정식 산출물 Draft manifest",
            "version": VERSION,
            "as_of": AS_OF,
            "lifecycle_status": "DRAFT",
            "freshness_status": "CURRENT_DRAFT",
            "verification_status": "NOT_RUN",
            "approval_status": "NOT_APPROVED",
            "release_status": "NOT_ELIGIBLE",
            "artifact_type_ids": DISPLAY_ARTIFACT_TYPE_IDS,
            "source_policy_baseline": sources["approval"]["approved_baseline_payload"]["baseline_id"],
            "proposed_requirements_baseline": "RB-WALKSAFE-REQUIREMENTS-1.0.0",
            "generated_by": _repo_path(GENERATOR_PATH),
            "manifest_id": "WS-FORMAL-REQ-DRAFT-20260721-001",
            "controlled_revision": 1,
        },
        "approval_status": "NOT_APPROVED",
        "release_status": "NOT_ELIGIBLE",
        "artifact_type_ids": DISPLAY_ARTIFACT_TYPE_IDS,
        "generated_by": _repo_path(GENERATOR_PATH),
        "source_bindings": bindings,
        "source_binding_sha256": _object_sha256(bindings),
        "coverage": {
            "REQ": {"expected": 19, "covered": 19},
            "bundle_count": 3,
            "requirement_count": 68,
            "feature_requirement_count": 54,
            "common_policy_requirement_count": 9,
            "remaining_gate_requirement_count": 5,
            "acceptance_condition_count": 279,
            "aligned_decision_count": 135,
            "decision_feature_edge_count": 428,
            "not_effective_policy_correction_candidate_count": 1,
            "fp035_direct_impact_requirement_type_count": 2,
            "fp035_direct_impact_requirement_type_ids": ["REQ-03", "REQ-06"],
            "fp035_required_related_change_record_ids": ["REQ-18"],
            "missing_artifact_type_ids": [],
            "duplicate_artifact_type_ids": [],
        },
        "generated_files": generated_files,
        "remaining_gates": [
            {"id": gate["id"], "status": "NOT_RUN", "waived": False}
            for gate in sources["alignment"]["remaining_gates"]
        ],
        "authorization_boundary": {
            "policy_baseline_status": "BASELINED",
            "fp035_correction_candidate_approval_status": "NOT_APPROVED",
            "fp035_correction_candidate_effective_status": "NOT_EFFECTIVE",
            "fp035_bundled_approval_required": True,
            "fp035_required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
            "fp035_authoring_and_planning_readiness": "ALLOWED",
            "fp035_mobile_network_branch_implementation_and_test_readiness": "BLOCKED_PENDING_BUNDLED_APPROVAL",
            "fp035_directly_affected_artifact_codes": ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"],
            "decision_alignment_status": "COMPLETE",
            "formal_deliverable_lifecycle_status": "DRAFT",
            "formal_deliverables_approved": False,
            "requirements_baselined": False,
            "implementation_completion_claimed": False,
            "test_completion_claimed": False,
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "rtm_binding": {
            "requirement_binding_sha256": rtm["requirement_binding_sha256"],
            "decision_binding_sha256": rtm["aligned_decision_binding"]["decision_binding_sha256"],
        },
    }
    manifest["manifest_content_sha256"] = _object_sha256(manifest)
    return manifest


def build_outputs(sources: dict[str, Any] | None = None) -> dict[Path, bytes]:
    if sources is None:
        sources = load_sources()
    rtm = build_rtm(sources)
    glossary = build_glossary(rtm)
    change_log = build_change_log(rtm)
    outputs: dict[Path, bytes] = {
        SYSTEM_PATH: _text_bytes(build_system_markdown(rtm, sources)),
        ACCEPTANCE_PATH: _text_bytes(build_acceptance_markdown(rtm, sources)),
        TRACE_PATH: _text_bytes(build_trace_markdown(rtm, glossary, change_log)),
        RTM_JSON_PATH: _json_bytes(rtm),
        RTM_HTML_PATH: _text_bytes(build_rtm_html(rtm)),
        CHANGE_LOG_PATH: _json_bytes(change_log),
        GLOSSARY_PATH: _json_bytes(glossary),
    }
    manifest = build_manifest(outputs, sources, rtm)
    outputs[MANIFEST_PATH] = _json_bytes(manifest)
    validate_outputs(outputs, sources)
    return outputs


def validate_outputs(outputs: dict[Path, bytes], sources: dict[str, Any] | None = None) -> None:
    if sources is None:
        sources = load_sources()
    _require(set(outputs) == set(OUTPUT_PATHS), "generated output path set differs")
    try:
        rtm = json.loads(outputs[RTM_JSON_PATH].decode("utf-8"))
        change_log = json.loads(outputs[CHANGE_LOG_PATH].decode("utf-8"))
        glossary = json.loads(outputs[GLOSSARY_PATH].decode("utf-8"))
        manifest = json.loads(outputs[MANIFEST_PATH].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RequirementsDraftError("generated JSON is invalid") from exc
    validate_rtm(rtm, sources)

    _require(change_log["metadata"]["lifecycle_status"] == "DRAFT", "change log must be Draft")
    _require(change_log["metadata"]["approval_status"] == "NOT_APPROVED", "change log approval differs")
    _require(len(change_log["entries"]) == 2, "change log must contain initial derivation and the FP-035 normalization")
    _require(len(change_log["entries"][0]["affected_requirement_ids"]) == 68, "change log requirement coverage differs")
    _require(change_log["entries"][1]["change_id"] == "REQ-CHG-20260722-002", "FP-035 normalization record is missing")
    _require(change_log["entries"][1]["approval_status"] == "NOT_APPROVED", "FP-035 change record claims approval")
    fp035_change = change_log["entries"][1]
    _require(fp035_change.get("effective_status") == "NOT_EFFECTIVE", "FP-035 change record claims effect")
    _require(fp035_change.get("policy_correction_candidate_id") == FP035_CORRECTION_CANDIDATE_ID, "FP-035 change candidate ref differs")
    _require(
        fp035_change.get("policy_correction_candidate_sha256") == _file_sha256(FP035_CORRECTION_CANDIDATE_PATH),
        "FP-035 change candidate SHA differs",
    )
    _require(fp035_change.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "FP-035 change activation event differs")
    _require(fp035_change.get("approval_blockers") == FP035_APPROVAL_BLOCKERS, "FP-035 change approval blockers differ")
    _require(fp035_change.get("mobile_network_branch_implementation_and_test_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL", "FP-035 change execution readiness differs")
    _require(fp035_change.get("affected_artifact_codes") == ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"], "FP-035 change affected artifact scope differs")
    _require(fp035_change.get("change_control_refs") == ["CR-0002", FP035_NETWORK_ISSUE_ID, "RAID-011"], "FP-035 change-control refs differ")
    _require(fp035_change.get("required_related_record") == {"artifact_code": "REQ-18", "path": _repo_path(CHANGE_LOG_PATH)}, "REQ-18 change record link differs")
    _require(change_log["content_sha256"] == _object_sha256({key: value for key, value in change_log.items() if key != "content_sha256"}), "change log hash differs")

    _require(glossary["metadata"]["lifecycle_status"] == "DRAFT", "glossary must be Draft")
    _require(glossary["metadata"]["approval_status"] == "NOT_APPROVED", "glossary approval differs")
    _require(len(glossary["entries"]) >= 25, "glossary must explain the core terms")
    _require(len({entry["term"] for entry in glossary["entries"]}) == len(glossary["entries"]), "glossary terms must be unique")
    _require(glossary["content_sha256"] == _object_sha256({key: value for key, value in glossary.items() if key != "content_sha256"}), "glossary hash differs")

    expected_anchors = {
        SYSTEM_PATH: [1, 2, 3, 4, *range(7, 16)],
        ACCEPTANCE_PATH: [5, 6],
        TRACE_PATH: [16, 17, 18, 19],
    }
    all_anchors: list[str] = []
    for path, numbers in expected_anchors.items():
        content = outputs[path].decode("utf-8")
        anchors = re.findall(r'<a id="(req-\d{2})"></a>', content)
        expected = [f"req-{number:02d}" for number in numbers]
        _require(anchors == expected, f"{_repo_path(path)} anchors differ")
        for number in numbers:
            _require(f"REQ-{number:02d}" in content, f"REQ-{number:02d} heading is missing")
            _require("`DRAFT`" in content, f"REQ-{number:02d} has no independent status")
        all_anchors.extend(anchors)
    _require(all_anchors == [f"req-{number:02d}" for number in [1, 2, 3, 4, *range(7, 16), 5, 6, 16, 17, 18, 19]], "canonical anchor coverage differs")
    _require(set(all_anchors) == {f"req-{number:02d}" for number in range(1, 20)}, "REQ-01..REQ-19 anchor coverage differs")

    system_text = outputs[SYSTEM_PATH].decode("utf-8")
    acceptance_text = outputs[ACCEPTANCE_PATH].decode("utf-8")
    trace_text = outputs[TRACE_PATH].decode("utf-8")
    for row in rtm["requirements"]:
        _require(row["requirement_id"] in system_text or row["requirement_id"] in trace_text, f"{row['requirement_id']} missing from narrative")
        _require(row["requirement_id"] in acceptance_text, f"{row['requirement_id']} missing from acceptance document")
        for acceptance in row["acceptance_conditions"]:
            _require(acceptance["acceptance_condition_id"] in acceptance_text, f"{acceptance['acceptance_condition_id']} missing from acceptance document")
            _require(acceptance["planned_test_id"] in acceptance_text, f"{acceptance['planned_test_id']} missing from acceptance document")

    html_text = outputs[RTM_HTML_PATH].decode("utf-8")
    match = re.search(r'<script id="rtm-data" type="application/json">(.*?)</script>', html_text, re.DOTALL)
    _require(match is not None, "RTM HTML has no embedded data")
    embedded_rtm = json.loads(match.group(1))
    _require(embedded_rtm == rtm, "RTM HTML data differs from rtm.json")
    _require("https://" not in html_text and "http://" not in html_text, "RTM HTML must be standalone")

    metadata = manifest.get("metadata", {})
    _require(metadata.get("lifecycle_status") == "DRAFT", "manifest lifecycle differs")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "manifest approval differs")
    _require(metadata.get("release_status") == "NOT_ELIGIBLE", "manifest release differs")
    _require(metadata.get("artifact_type_ids") == DISPLAY_ARTIFACT_TYPE_IDS, "manifest artifact coverage differs")
    _require(manifest.get("approval_status") == "NOT_APPROVED", "manifest root approval differs")
    _require(manifest.get("release_status") == "NOT_ELIGIBLE", "manifest root release differs")
    _require(manifest.get("artifact_type_ids") == DISPLAY_ARTIFACT_TYPE_IDS, "manifest root artifact IDs differ")
    _require(manifest.get("generated_by") == _repo_path(GENERATOR_PATH), "manifest generator differs")
    _require(manifest["source_binding_sha256"] == _object_sha256(manifest["source_bindings"]), "manifest source binding differs")
    generated_by_path = {item["path"]: item for item in manifest["generated_files"]}
    _require(len(generated_by_path) == 7, "manifest must bind seven generated files")
    for path, payload in outputs.items():
        if path == MANIFEST_PATH:
            continue
        entry = generated_by_path.get(_repo_path(path))
        _require(entry is not None, f"manifest generated file missing: {_repo_path(path)}")
        _require(entry["sha256"] == _bytes_sha256(payload), f"manifest file hash differs: {_repo_path(path)}")
        _require(entry["byte_length"] == len(payload), f"manifest file size differs: {_repo_path(path)}")
        _require(entry["artifact_type_ids"], f"manifest artifact IDs missing: {_repo_path(path)}")
    _require(len(manifest["remaining_gates"]) == 5, "manifest gate coverage differs")
    _require(all(gate == {"id": gate["id"], "status": "NOT_RUN", "waived": False} for gate in manifest["remaining_gates"]), "manifest gates must be open and unwaived")
    auth = manifest["authorization_boundary"]
    _require(auth["fp035_correction_candidate_approval_status"] == "NOT_APPROVED", "FP-035 candidate approval boundary differs")
    _require(auth["fp035_correction_candidate_effective_status"] == "NOT_EFFECTIVE", "FP-035 candidate effective boundary differs")
    _require(auth["fp035_bundled_approval_required"] is True, "FP-035 bundled approval requirement is hidden")
    _require(auth.get("fp035_required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "FP-035 activation event differs")
    _require(auth.get("fp035_authoring_and_planning_readiness") == "ALLOWED", "FP-035 authoring readiness differs")
    _require(auth.get("fp035_mobile_network_branch_implementation_and_test_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL", "FP-035 execution readiness differs")
    _require(auth.get("fp035_directly_affected_artifact_codes") == ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"], "FP-035 direct artifact scope differs")
    _require(manifest["coverage"].get("fp035_direct_impact_requirement_type_ids") == ["REQ-03", "REQ-06"], "FP-035 direct requirement scope differs")
    _require(manifest["coverage"].get("fp035_required_related_change_record_ids") == ["REQ-18"], "FP-035 REQ-18 tracking scope differs")
    _require(auth["formal_deliverables_approved"] is False, "manifest must not claim approval")
    _require(auth["requirements_baselined"] is False, "manifest must not claim baseline")
    _require(auth["implementation_completion_claimed"] is False, "manifest must not claim implementation completion")
    _require(auth["test_completion_claimed"] is False, "manifest must not claim test completion")
    _require(auth["remaining_gates_waived"] is False, "manifest must not waive gates")
    _require(auth["release_status"] == "NOT_ELIGIBLE", "manifest must not authorize release")
    _require(manifest["manifest_content_sha256"] == _object_sha256({key: value for key, value in manifest.items() if key != "manifest_content_sha256"}), "manifest content hash differs")

def _write_outputs(outputs: dict[Path, bytes]) -> None:
    for path, payload in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)


def _check_outputs(outputs: dict[Path, bytes]) -> None:
    stale: list[str] = []
    for path, expected in outputs.items():
        if not path.is_file() or path.read_bytes() != expected:
            stale.append(_repo_path(path))
    _require(not stale, "generated outputs are missing or stale: " + ", ".join(stale))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if generated outputs differ")
    args = parser.parse_args(argv)
    try:
        sources = load_sources()
        outputs = build_outputs(sources)
        if args.check:
            _check_outputs(outputs)
            verb = "checked"
        else:
            _write_outputs(outputs)
            verb = "wrote"
        rtm = json.loads(outputs[RTM_JSON_PATH])
        print(
            f"{verb} WalkSafe requirements Draft: "
            f"{rtm['coverage']['requirement_count']} requirements, "
            f"{rtm['coverage']['acceptance_condition_count']} acceptance conditions, "
            f"{rtm['coverage']['aligned_decision_count']} decisions, "
            f"{rtm['coverage']['decision_feature_edge_count']} edges"
        )
        return 0
    except RequirementsDraftError as exc:
        print(f"requirements Draft generation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
