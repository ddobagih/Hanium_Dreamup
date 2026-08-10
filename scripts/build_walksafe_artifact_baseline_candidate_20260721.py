#!/usr/bin/env python3
"""Build the pre-approval eligibility package for all 257 WalkSafe artifacts.

This script never records approval.  It classifies the current Draft/Planned
artifacts, binds the exact reviewed bytes and per-anchor approval units, and
produces one owner approval statement for a later, separate approval step.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
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
BASELINE_DIR = CONTROL_DIR / "baselines"
DELIVERABLE_ROOT = REPO_ROOT / "docs" / "deliverables"
REGISTER_PATH = DELIVERABLE_ROOT / "00-control" / "artifact-register.json"
CHANGE_LOG_PATH = DELIVERABLE_ROOT / "00-control" / "artifact-change-log.json"
MANUAL_PATH = DELIVERABLE_ROOT / "00-control" / "document-control-manual.md"
CATALOG_PATH = CONTROL_DIR / "artifact-types.json"
AUTHORING_PLAN_PATH = CONTROL_DIR / "documentation-authoring-preparation-plan.md"
POLICY_APPROVAL_PATH = (
    BASELINE_DIR / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
)
POLICY_MANIFEST_PATH = (
    BASELINE_DIR / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
)
REVIEW_RESOLUTION_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "walksafe-feature-policy-baseline-review-resolution-20260721-r001.json"
)
POLICY_DOCUMENT_PATH = (
    CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-comprehensive-draft.json"
)
DECISION_REGISTER_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "walksafe-effective-decision-register-aligned-20260721-r001.json"
)
TRACE_0_6_PATH = (
    DELIVERABLE_ROOT
    / "traceability"
    / "req-des-tst-integration-report-20260721-r001.json"
)
TRACE_7_12_PATH = (
    DELIVERABLE_ROOT
    / "traceability"
    / "formal-7-12-integration-report-20260721-r001.json"
)

CANDIDATE_PATH = (
    BASELINE_DIR / "walksafe-artifact-baseline-candidate-20260721-r001.json"
)
REVIEW_MD_PATH = (
    BASELINE_DIR / "walksafe-artifact-baseline-candidate-review-20260721-r001.md"
)
REVIEW_HTML_PATH = (
    BASELINE_DIR / "walksafe-artifact-baseline-candidate-review-20260721-r001.html"
)

PREPARED_AT = "2026-07-21T23:55:19+09:00"
CANDIDATE_ID = "WS-ARTIFACT-BASELINE-CANDIDATE-20260721-001"
CANDIDATE_VERSION = "1.0.0"
SCHEMA_VERSION = "walksafe.artifact-baseline-approval-candidate.v1"

PLANNED_POST_APPROVAL_IDENTIFIERS = {
    "approval_record_id": "WS-ARTIFACT-BASELINE-APPROVAL-20260721-001",
    "pre_transition_snapshot_manifest_id": "WS-ARTIFACT-PRETRANSITION-SNAPSHOT-20260721-001",
    "post_transition_manifest_id": "WS-ARTIFACT-POSTTRANSITION-MANIFEST-20260721-001",
    "versioned_baseline": {
        "baseline_id": "CB-WALKSAFE-DOCUMENT-CONTROL-1.0.0",
        "baseline_version": "1.0.0",
        "artifact_codes": ["DOC-03", "DOC-04"],
        "manifest_path": (
            "docs/control/baselines/"
            "walksafe-document-control-baseline-1.0.0-manifest-20260721-r001.json"
        ),
    },
    "active_opening_snapshots": [
        {
            "artifact_code": "DOC-01",
            "snapshot_id": "ART-DOC-01-001-SNAPSHOT-001",
            "snapshot_version": "1.0.0",
        },
        {
            "artifact_code": "DOC-05",
            "snapshot_id": "ART-DOC-05-001-SNAPSHOT-001",
            "snapshot_version": "1.0.0",
        },
    ],
}

DISPOSITION_BASELINE = "A_BASELINE_ELIGIBLE"
DISPOSITION_ACTIVE = "B_ACTIVE_INITIAL_ELIGIBLE"
DISPOSITION_PLANNED = "C_PLANNED_NOT_RUN"
DISPOSITION_HOLD = "D_DRAFT_HOLD"
DISPOSITION_CONDITIONAL = "E_CONDITIONAL_PENDING"
DISPOSITION_ORDER = (
    DISPOSITION_BASELINE,
    DISPOSITION_ACTIVE,
    DISPOSITION_PLANNED,
    DISPOSITION_HOLD,
    DISPOSITION_CONDITIONAL,
)
EXPECTED_COUNTS = {
    DISPOSITION_BASELINE: 2,
    DISPOSITION_ACTIVE: 2,
    DISPOSITION_PLANNED: 75,
    DISPOSITION_HOLD: 121,
    DISPOSITION_CONDITIONAL: 57,
}

EXPECTED_IMMUTABLE_HASHES = {
    POLICY_APPROVAL_PATH: "10ce10b104da2f625ebba51a9bf37dced2eab8007d2dd0519a67c268d387bbd5",
    POLICY_MANIFEST_PATH: "7285111aafd3907a5e8e338c79ca42f9af2c0d7db9de0460512b66a6cfac90be",
    REVIEW_RESOLUTION_PATH: "d71cf9940cf8037226d3e095be26aa4ed34fe2c2feabe565c88f803eaab0dc50",
}
EXPECTED_GATE_IDS = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
}
FP035_OPEN_REFS = ["ISS-POLICY-FP035-NETWORK-001", "CR-0002", "RAID-011"]
ANCHOR_RE = re.compile(r'<a id="([^"]+)"></a>')


def _codes(prefix: str, numbers: list[int] | range) -> set[str]:
    return {f"{prefix}-{number:02d}" for number in numbers}


PRE_DEPENDENCY_BASELINE_CANDIDATE_CODES = {
    *_codes("DOC", [3, 4]),
    *_codes("MGT", [4]),
    *_codes("DES", [14, 15, 18, 19]),
    *_codes("TST", [4]),
    *_codes("SEC", [7]),
    *_codes("WS", [5]),
}

PRE_DEPENDENCY_ACTIVE_CANDIDATE_CODES = {
    *_codes("DOC", [1, 5]),
    *_codes("MGT", [9, 10, 14, 15, 16, 17, 18]),
    *_codes("DSC", [14]),
    *_codes("REQ", [16, 18, 19]),
    *_codes("DES", [6]),
    *_codes("DEV", [18]),
    *_codes("TST", [3, 5, 18, 19, 21]),
    *_codes("SEC", [3, 15, 16]),
    *_codes("AIML", [3]),
}

BASELINE_ELIGIBLE_CODES = _codes("DOC", [3, 4])
ACTIVE_INITIAL_CODES = _codes("DOC", [1, 5])
DEPENDENCY_HOLD_CODES = (
    PRE_DEPENDENCY_BASELINE_CANDIDATE_CODES | PRE_DEPENDENCY_ACTIVE_CANDIDATE_CODES
) - (BASELINE_ELIGIBLE_CODES | ACTIVE_INITIAL_CODES)

EXTRA_PLANNED_NOT_RUN_CODES = {
    *_codes("DSC", [4, 7, 9]),
    *_codes("DEV", [16, 17, 19, 20, 21]),
    *_codes("TST", [*range(6, 18), 20, 22, 23]),
}

HOLD_CODES = {
    *_codes("DOC", [2]),
    *_codes("MGT", [1, 2, 5, 6, 11, 12, 13]),
    *_codes("DSC", [1, 2, 6, 8, 10, 12, 13, 15]),
    *_codes("REQ", [1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 14, 15, 17]),
    *_codes("DES", [1, 2, 3, 4, 7, 9, 10, 11, 12, 13, 16, 20, 21, 23, 26, 27]),
    *_codes("DEV", [1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 14]),
    *_codes("TST", [1, 2]),
    *_codes("SEC", [1, 2, 4, 6, 8, 9, 17, 19]),
    *_codes("AIML", [2, 4, 7, 14, 15, 16]),
    *_codes("REL", [15, 16, 17, 18, 19, 22]),
    *_codes("OPS", [11, 13]),
    *_codes("WS", [1, 2, 3, 4, 11, 19, 22]),
    *_codes("CLS", [10, 14, 15, 16]),
    *DEPENDENCY_HOLD_CODES,
}

CONDITIONAL_PENDING_CODES = {
    *_codes("MGT", [3, 7, 8]),
    *_codes("DSC", [3, 5, 11]),
    *_codes("REQ", [4, 12, 13]),
    *_codes("DES", [5, 8, 17, 22, 24, 25]),
    *_codes("DEV", [10, 11, 13, 15]),
    *_codes("SEC", [5, 18]),
    *_codes("AIML", [1, 6, 12, 17, 24, 25, 26]),
    *_codes("REL", [1, 2, 10, 11, 12]),
    *_codes("OPS", [1, 2, 3, 4, 5, 8, 9, 10, 12, 14, 15, 16, 17, 19, 20, 21, 22, 23, 24]),
    *_codes("WS", [8, 18]),
    *_codes("CLS", [7, 8, 9]),
}

PENDING_ACTIVATION_RECLASSIFIED_CODES = {
    *_codes("SEC", [18]),
    *_codes("AIML", [12, 25, 26]),
    *_codes("REL", [2, 10, 11, 12]),
    *_codes("OPS", [1, 2, 3, 5, 8, 9, 10, 14, 15, 16, 17, 19, 20, 21, 22, 23, 24]),
    *_codes("CLS", [7, 8, 9]),
}

ELIGIBLE_REASONS = {
    "DOC-03": "검토·승인 역할, 상태 전이와 승인 조건이 최초 통제 기준으로 사용할 수 있는 수준이다.",
    "DOC-04": "기준선 설정, 버전 상승과 대체·폐기 절차가 문서 관리 기준으로 사용할 수 있는 수준이다.",
}

HOLD_REASONS = {
    "DES-07": "현재 저장소에서 읽은 후보 기술·버전 표가 본문 대부분이므로 구현 Gap 검증 전 설계 기준선으로 승인하지 않는다.",
    "DSC-08": "가정 원장에 type·위험·표본·통과/실패 기준·owner·기한·검증결과 필드가 없어 최초본 완료기준을 충족하지 않는다.",
    "DSC-15": "착수·계속·중단 판단 행에 조건·기한·결정권자·이견·재평가일이 없어 최초 판단 원장으로 승인하지 않는다.",
    "DEV-01": "현행 소스 snapshot은 다음 Gap 분석 전 정책·설계 적합성을 확인하지 않았으므로 승인하지 않는다.",
    "DEV-02": "현재 저장소의 디렉터리·실행 상태를 설명하므로 구현 Gap 확인 전 승인하지 않는다.",
    "DEV-03": "지원환경·도구 버전과 실제 재현 여부를 Gap 확인하기 전 승인하지 않는다.",
    "DEV-07": "현행 lock 파일의 완전성·재현성·승인 의존성 결속을 Gap 분석 전 확인하지 않았다.",
    "DEV-08": "실제 환경변수·설정 예제 파일이 승인단위에 결속되지 않아 opening snapshot이 성립하지 않는다.",
    "DEV-09": "현행 build script의 대상·실행·산출물·실패 처리·재현성을 Gap 분석 전 확인하지 않았다.",
    "DEV-12": "현행 migration의 revision chain·대상 DB·upgrade/downgrade·backup 전제를 Gap 분석 전 확인하지 않았다.",
    "DEV-14": "현행 fixture의 출처·비밀정보 제거·결정성·적용 시험을 Gap 분석 전 확인하지 않았다.",
    "SEC-04": "보안 설계검토 절차와 실제 검토 결과가 섞여 있고 정식 검토 결과는 아직 없다.",
    "AIML-02": "후보 데이터셋의 현황·결손 snapshot이며 승인된 정식 데이터셋 카드가 아니다.",
    "AIML-04": "현행 Android 후보에서 읽은 13개 클래스 정보가 섞여 있어 승인 목록으로 승격하지 않는다.",
    "AIML-07": "현행 13개 후보 class와 보류된 AIML-04에 의존하며 positive·negative 경계와 승인 예시 세트가 아직 없다.",
    "AIML-14": "후보 모델 register가 현행 구현 snapshot이며 정식 실험·평가 기준선과 승격 기록이 없다.",
    "AIML-15": "후보 모델 ID·hash·과거 metric snapshot이며 정식 평가·배포 승인이 아니다.",
    "AIML-16": "후보 모델 파일·hash가 정식 실험·평가·TFLite 동등성 증거와 결속되지 않았다.",
    "REL-15": "롤백 절차와 아직 없는 실행 결과가 한 유형에 섞여 있다.",
    "REL-16": "명명된 실제 릴리스와 설치 검증에 맞춰야 하므로 현재 일반 초안을 승인하지 않는다.",
    "REL-17": "명명된 실제 릴리스의 사용자 동작과 검증 결과에 맞춰야 한다.",
    "REL-18": "명명된 실제 릴리스의 관리자 기능·권한과 검증 결과에 맞춰야 한다.",
    "REL-19": "실제 릴리스·교육 대상·시연 결과가 정해진 뒤 작성해야 한다.",
    "REL-22": "실제 릴리스 의존성·버전과 권리 검토가 끝난 뒤 고지본을 확정해야 한다.",
    "OPS-11": "복원 절차와 아직 실행하지 않은 실제 복원시험 결과가 섞여 있다.",
    "OPS-13": "재해복구 계획과 아직 실행하지 않은 훈련 결과가 섞여 있다.",
    "CLS-10": "실제 운영 소유권·계정·권한 이관 대상과 확인 기록이 아직 없다.",
    "CLS-14": "실제 종료 시점의 데이터 보존·이관·삭제 대상과 영수증이 필요하다.",
    "CLS-15": "실제 종료 시점의 계정·키·인프라 대상과 폐기 확인이 필요하다.",
    "CLS-16": "실제 서비스 종료 결정·대상·고지·폐기 증거가 필요하다.",
}

HOLD_REASONS.update(
    {
        "DOC-02": "자동생성·증거·보관 파일 접미사와 이름 변경 시 참조 갱신 절차가 없다.",
        "MGT-01": "헌장에 이해관계자, 핵심 위험, 종료조건과 전체 범위 요약이 부족하다.",
        "MGT-02": "사업 필요성을 뒷받침할 근거, 대안 비교와 효과 측정 방법이 없다.",
        "MGT-05": "관리계획이 한 문장 수준이며 일정·범위·품질·변경·위험 관리 방법을 통합하지 못했다.",
        "MGT-06": "WBS에 담당자, 공수, 작업 간 의존성, MVP 구분과 산출물 범위 누락 점검이 없다.",
        "MGT-11": "보고 주기, 채널, 공식 기록 위치와 민감정보 전달 규칙이 확정되지 않았다.",
        "MGT-12": "품질지표, 결함 심각도, 조치기한, 감사와 예외 승인 절차가 없다.",
        "MGT-13": "형상 항목별 담당자, 기준선 생성 절차, 변경 감사와 복구 절차가 부족하다.",
        "DSC-01": "문제 정의를 뒷받침할 조사 근거, 대안과 문제 검증 방법이 없다.",
        "DSC-02": "사용자별 포함·제외 기준, 사용환경, 이용 빈도와 보호 필요성이 구체화되지 않았다.",
        "DSC-06": "문서가 현재 여정이 완전히 확인되지 않았다고 명시하며 고충·감정·실패·복구 근거도 부족하다.",
        "DSC-10": "제품 비전은 있으나 목표 시점과 성공 상태를 판정할 기준이 없다.",
        "DSC-12": "MVP 항목별 인수 시나리오와 범위 변경 승인 gate가 없다.",
        "DSC-13": "개발 순서만 있고 단계별 가치, 의존성, 진입·종료조건과 검토 일정이 없다.",
        "REQ-01": "SRD가 시스템 목적·환경·제약·인터페이스·품질 요구사항을 완전하게 통합하지 못했다.",
        "REQ-02": "SRS가 기능별 상세 동작, 예외, 데이터와 검증 기준을 독립적으로 제공하지 못한다.",
        "REQ-03": "FP-035 요구사항이 미결인데 이를 제외한 부분 기준선 단위와 추적 제외 규칙이 없다.",
        "REQ-05": "액터, 사전·사후조건, 정상·대안·예외 흐름과 요구사항 연결 구조가 부족하다.",
        "REQ-06": "FP-035 인수조건 4개가 승인 불가 상태인데 별도 부분 승인 단위가 없어 문서 전체를 기준선화할 수 없다.",
        "REQ-07": "endpoint, 요청·응답 schema, 인증, timeout과 오류 계약이 구체화되지 않았다.",
        "REQ-08": "데이터 필드별 형식, 단위, 필수 여부와 품질·검증 규칙이 없다.",
        "REQ-09": "위협별 보안통제, 보안 검증 방법과 추적 가능한 완료조건이 부족하다.",
        "REQ-10": "처리 목적별 법적 근거, 제3자 제공과 정보주체 권리 처리 조건이 없다.",
        "REQ-11": "지원 보조기술, 접근성 기준, 화면별 적용·검증 조건이 부족하다.",
        "REQ-14": "지원 OS·브라우저·기기의 최소 버전과 제외·중단 기준이 없다.",
        "REQ-15": "적용 법률·라이선스·지역과 법률 검토 결과가 구체적으로 확정되지 않았다.",
        "REQ-17": "문서가 스스로 현재 파일을 기준선이 아닌 기준선 후보 Draft라고 명시한다.",
        "DES-01": "설계 개요가 구성요소, 핵심 결정, 품질속성 대응과 상세설계 연결을 충분히 제공하지 않는다.",
        "DES-02": "외부 주체·시스템, 데이터 흐름, 책임 경계와 신뢰 경계가 완전하지 않다.",
        "DES-03": "모듈별 책임, 제공·요구 인터페이스, 의존성과 실패 격리가 구체화되지 않았다.",
        "DES-04": "연결된 설계 추적 record가 BLOCKED_PENDING_POLICY_REAPPROVAL 상태다.",
        "DES-09": "현재 OpenAPI 후보에 보안, server, 공통 오류, 멱등성과 운영 계약이 없다.",
        "DES-10": "구현 후보 snapshot이며 요구사항·작업 ID, 보안, 오류 봉투, 예제와 호환성 정책이 빠져 있다.",
        "DES-11": "물리 ERD와 실제 key·관계·제약조건이 아직 확정되지 않았다.",
        "DES-12": "목표 개념 사전 수준이며 실제 열의 SQL 형식, null, 기본값, 제약조건과 migration ID가 없다.",
        "DES-13": "연결된 데이터 생명주기 설계 record가 FP-035 재승인 대기로 차단되어 있다.",
        "DES-16": "저해상도 초안 중심이며 주요 상태·오류·접근성 화면과 검증 결과가 부족하다.",
        "DES-20": "FP-035 설계 추적 차단과 함께 위협 가능성·영향·위험도 산정이 없다.",
        "DES-21": "실제 cloud 사업자, 재처리자, 국외이전과 처리 법적 근거가 확정되지 않았다.",
        "DES-23": "로그별 보존·sampling, dashboard와 경보·대응 기준이 없다.",
        "DES-26": "migration 후보의 목표 schema 적합성이 NOT_ASSESSED여서 실행 설계로 승인할 수 없다.",
        "DES-27": "외부 서비스별 SLA, quota, endpoint, 전환 조건과 장애시험 기준이 없다.",
        "DEV-04": "실행·종료 명령, 환경변수, test 명령, 선행조건과 log·정리 방법이 없다.",
        "DEV-05": "branch·commit·PR·merge·되돌리기와 비밀정보 검토 규칙이 없다.",
        "DEV-06": "사용 언어별 style, 비동기·자원 처리와 lint·formatter 기준이 없다.",
        "TST-01": "위험 우선순위, 시험 수준별 전략, 자동·수동 구분과 진입·종료조건이 없다.",
        "TST-02": "대상 build, 환경·기기·데이터, 담당자·일정·도구와 중단·재개 조건이 없다.",
        "SEC-01": "문서가 현재는 계획만 Draft라고 명시하며 역할, 교육, scan·조치기한과 예외 절차가 부족하다.",
        "SEC-02": "공격자, 데이터 흐름도, 가능성·영향·위험도 평가가 없다.",
        "SEC-06": "처리별 법적 근거, 실제 고지 화면·버전, 제3자와 재동의 조건이 없다.",
        "SEC-08": "권한 부여·회수, 정기검토와 비상권한의 전체 생명주기가 없다.",
        "SEC-09": "비밀별 소유자, 저장소·주입 방식, 만료·회전 일정과 유출 대응이 없다.",
        "SEC-17": "사고 심각도, 연락·지휘 체계, 보고기한, 훈련과 사후분석 절차가 없다.",
        "SEC-19": "탐지 임계값, 경보 조사 절차가 없고 일부 감사로그 보존기간도 미정이다.",
        "WS-01": "사용자 특성·보조기기, 단계별 실패·대체 행동과 시험 연결이 부족하다.",
        "WS-02": "신고 최소 데이터, 검증·반영 SLA와 지도 반영·제외 규칙이 없다.",
        "WS-03": "위험정보의 신뢰도·유효기간·출처 표시와 정정 전파 방법이 없다.",
        "WS-04": "영구 권한 거부, 설정 이동, 재요청과 접근성 대응 흐름이 부족하다.",
        "WS-11": "승인 anchor가 문서 끝까지 이어져 Planned/NOT_RUN 실행 항목까지 승인 범위에 포함한다.",
        "WS-19": "악용·중복·spam 판정의 수치 기준, rate limit과 운영지표가 없다.",
        "WS-22": "실제 사용자 고지문, 버전·동의 기록과 법률 검토 결과가 없다.",
    }
)

HOLD_REASONS.update(
    {
        code: "이번 승인에 필요한 모든 선행 산출물이 승인되지 않아 내용의 나머지 적격성과 무관하게 보류한다."
        for code in DEPENDENCY_HOLD_CODES
    }
)

ADDITIONAL_CONTENT_GAP_REASONS = {
    "MGT-09": "영향력·권한·참여 방식과 대리 담당자 정보가 없다.",
    "MGT-10": "역할 분리·겸임, 대리 책임과 미배정 역할 검사 기준이 없다.",
    "MGT-14": "원인·영향·가능성·완화조치와 잔여 노출 필드가 부족하다.",
    "MGT-15": "결정 원문이 미승인 pointer이고 결정 근거·대안·효력 같은 핵심 필드가 부족하다.",
    "MGT-16": "긴급 변경, 영향분석, commit·검증 증거와 rollback 정보가 없다.",
    "MGT-17": "milestone, P0, coverage, 추세와 관측시점 정보가 없다.",
    "MGT-18": "회의 메타정보, 논의·결정·연결과 미완료 action 이월 규칙이 없다.",
    "DSC-14": "모든 항목이 P0이며 effort·의존성·가중치·MVP·인수조건이 없다.",
    "REQ-18": "변경 전후 내용, 영향분석과 적용 기준선 정보가 없다.",
    "REQ-19": "동의어, 단위·범위, 사용 맥락과 용어 schema가 없다.",
    "DES-06": "대안·owner·결정일·대체 관계를 다음 revision에서 보강한다고 명시한다.",
    "DEV-18": "프로그램·모듈 inventory가 스스로 inventory_complete=false라고 명시한다.",
    "TST-03": "구체 시험환경·기기·버전·owner를 기록할 schema가 부족하다.",
    "TST-05": "279개 중 278개의 시험유형 배정이 provisional이고 환경·기기·데이터가 선택되지 않았다.",
    "TST-18": "결함 원장의 opened_at과 개설 기준선 정보가 없다.",
    "TST-19": "code·branch·device·flaky·defect 지표, 계산식과 관측시점이 없다.",
    "TST-21": "가능성·심각도·완화조치·수용자와 수용 만료일이 없다.",
    "SEC-03": "공격경로·조치기한·현재 노출과 처리방식 정보가 없다.",
    "SEC-15": "악용 가능성·환경·CVE/결함·영향 릴리스·opened_at·개설 기준선이 없다.",
    "SEC-16": "대안, 모니터링과 예외 철회·재검토 조건이 없다.",
    "AIML-03": "핵심 출처 값이 null이고 허용·금지 사용, 표시의무와 철회 처리 schema가 없다.",
}

CONDITIONAL_REASONS = {
    "MGT-08": "역할별 투입량·실제 비용추적·초과 승인 책임과 기한이 아직 확정되지 않았다.",
    "DSC-03": "사용자 조사 표본·장소·일정·분석 방법과 실제 책임자가 아직 확정되지 않았다.",
    "DSC-05": "실제 조사 persona가 아니라 승인 정책에 근거한 임시 사용자 프로필이다.",
    "DEV-10": "CI/CD 사용 여부와 통제 대상이 아직 조건평가 중이다.",
    "DEV-11": "IaC 적용 여부와 통제 대상이 아직 조건평가 중이다.",
    "DEV-13": "초기·샘플 데이터 적용 여부와 통제 대상이 아직 조건평가 중이다.",
    "DEV-15": "정식 코드리뷰 절차 활성화와 실제 기록 적용 여부가 아직 조건평가 중이다.",
    "REL-01": "명명된 릴리스 후보·범위·일정·담당자가 아직 없어 일반 작성 계약만 존재한다.",
    "OPS-04": "SLI 작성 원칙은 있으나 SLO와 error budget 수치가 측정·승인 전 미정이다.",
    "OPS-12": "RTO·RPO 수치가 실제 영향·복원 측정·비용 검토 전 미정이다.",
    "SEC-18": "적용 여부가 아직 PENDING_EVALUATION이고 신고 채널·필수 정보·safe harbor 범위가 확정되지 않았다.",
    "AIML-12": "학습 실험이 시작되지 않아 experiment ID와 code·data·config·seed 최초 기록을 승인할 수 없다.",
    "AIML-06": "blur·노출·해상도와 잘못된 class·box 수치 기준이 protocol revision 전 미정이다.",
    "AIML-17": "test split·hash·IoU/confidence grid·합격기준·허용오차가 사전 동결되지 않았다.",
    "AIML-25": "운영 모델과 관측자료가 없어 drift baseline·지표·임계값이 아직 확정되지 않았다.",
    "AIML-26": "재학습 적용 여부와 trigger·데이터 승인·split freeze 절차가 아직 확정되지 않았다.",
    "REL-02": "명명된 릴리스 후보와 gate 증거가 없어 실제 승인 체크리스트를 확정할 수 없다.",
    "REL-10": "명명된 릴리스가 없어 영향 버전이 결속된 알려진 문제 최초 원장을 열 수 없다.",
    "REL-11": "대상 환경·권한·backup·capacity·health 조건이 정해지지 않아 배포 절차를 확정할 수 없다.",
    "REL-12": "migration 대상과 revision·사전 backup·검사가 정해지지 않아 절차를 확정할 수 없다.",
    "OPS-01": "운영 개시 여부와 유지보수 범위·유형·소유·지원 역할이 아직 확정되지 않았다.",
    "OPS-02": "운영 서비스·자산과 owner·on-call·대리 책임자가 아직 확정되지 않았다.",
    "OPS-03": "지원 대상·시간대·접수 채널·에스컬레이션 체계가 아직 확정되지 않았다.",
    "OPS-05": "운영 환경과 SLO가 없어 event·metric·span 이름·단위·label 제한을 확정할 수 없다.",
    "OPS-08": "운영 장애 유형·발동 조건·접근 사전조건이 정해지지 않아 실제 runbook을 확정할 수 없다.",
    "OPS-09": "운영 대상·점검 주기·담당·health 기준이 정해지지 않아 최초 점검표를 열 수 없다.",
    "OPS-10": "운영 자산과 소유자·full/incremental 주기가 정해지지 않아 백업 정책을 확정할 수 없다.",
    "OPS-14": "운영 계정·역할·자원과 기준일이 정해지지 않아 권한검토 최초 원장을 열 수 없다.",
    "OPS-15": "회전 대상·owner·만료일·주기·trigger가 정해지지 않아 회전 정책을 확정할 수 없다.",
    "OPS-16": "취약점 정보원·수집주기·severity·영향 우선순위가 정해지지 않아 대응 절차를 확정할 수 없다.",
    "OPS-17": "운영이 시작되지 않아 실제 incident를 기록하는 최초 원장을 열 수 없다.",
    "OPS-19": "운영 대상과 변경 통제가 시작되지 않아 운영 변경이력 최초 원장을 열 수 없다.",
    "OPS-20": "운영·유지보수 범위가 확정되지 않아 유지보수 Backlog 최초 원장을 열 수 없다.",
    "OPS-21": "운영 기준선이 없어 위치·원인·영향이 결속된 기술부채 최초 원장을 열 수 없다.",
    "OPS-22": "운영 자원·단가·청구 출처와 실제 사용량이 없어 용량·비용 최초 원장을 열 수 없다.",
    "OPS-23": "운영 데이터와 삭제 실행이 없어 정책 버전에 결속된 실행 원장을 열 수 없다.",
    "OPS-24": "운영 배포 구성이 없어 provider·service·owner·endpoint 현황을 확정할 수 없다.",
    "CLS-07": "종료·인수 단계가 시작되지 않아 대상 버전에 결속된 미해결 결함 원장을 열 수 없다.",
    "CLS-08": "종료·인수 단계가 시작되지 않아 최종 잔여 위험과 수용 상태를 확정할 수 없다.",
    "CLS-09": "종료·인수 기준선이 없어 이관할 기술부채 최초 원장을 열 수 없다.",
}

CONDITIONAL_REASONS.update(
    {
        "MGT-03": "성공지표의 수치 목표, 계산식, 측정주기와 책임자가 확정되지 않았다.",
        "MGT-07": "일정 JSON의 모든 target_date가 null이며 기준 일정·주요경로·buffer가 없다.",
        "DSC-11": "FPS·지연·battery·오탐·미탐·GPS·STT·비용 목표값과 측정 조건이 추후 확정 상태다.",
        "REQ-04": "핵심 비기능 요구사항의 정량 기준과 측정 환경이 미정이다.",
        "REQ-12": "성능·용량 임계값, 부하 조건과 합격 기준이 미정이다.",
        "REQ-13": "가용성 목표와 RTO·RPO, 장애·복구 합격 기준이 미정이다.",
        "DES-05": "배포 domain, TLS·firewall, service account, key 관리와 확장 설정이 배포환경 결정 대기다.",
        "DES-08": "연동 대상 버전·계정·fixture와 실제 연동 검증 조건이 외부 환경 확정 대기다.",
        "DES-17": "실제 색상, 글자 크기와 진동 pattern이 접근성·기기 시험 후 확정되도록 남아 있다.",
        "DES-22": "timeout·retry·circuit breaker·용량 TTL의 실제 수치가 성능 검증 대기다.",
        "DES-24": "지연, 처리량, 동시성, 용량과 확장 임계값이 측정 후 확정 상태다.",
        "DES-25": "RTO·RPO와 복구·재해복구 수치가 미정이다.",
        "AIML-01": "학습·재학습 재개 시 적용되는 조건부 계획이며 데이터 출처·권리·분할·품질 책임도 미확정이다.",
        "AIML-24": "모델 교체 시 적용되는 조건부 계획이고 실제 rollback 검증은 NOT_RUN이다.",
        "SEC-05": "독립 검토가 NOT_RUN이고 법률 준수 확인도 완료되지 않았다.",
        "WS-08": "분석 정책 Draft이며 class별 안전 위험과 오탐·미탐 시험 증거가 없다.",
        "WS-18": "지도 사업자의 실제 endpoint·quota·감시·전환 조건과 장애시험이 외부 연동 확정 대기다.",
    }
)


class CandidateError(RuntimeError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise CandidateError(message)


def _upstream_codes(row: dict[str, Any]) -> list[str]:
    return [value.removeprefix("DLV-") for value in row["trace"]["upstream_types"]]


def _approval_sequence(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    row_by_code = {row["display_code"]: row for row in rows}
    remaining = set(BASELINE_ELIGIBLE_CODES | ACTIVE_INITIAL_CODES)
    completed: set[str] = set()
    phases: list[dict[str, Any]] = []
    while remaining:
        ready = sorted(
            code
            for code in remaining
            if set(_upstream_codes(row_by_code[code])) <= completed
        )
        _require(bool(ready), "approval-eligible dependency graph is blocked or cyclic")
        phases.append(
            {
                "phase": len(phases) + 1,
                "artifact_codes": ready,
                "required_predecessor_codes": sorted(
                    {
                        upstream
                        for code in ready
                        for upstream in _upstream_codes(row_by_code[code])
                    }
                ),
                "preconditions": [
                    "PRE_TRANSITION_SNAPSHOT_STILL_SELECTED",
                    "ALL_PREVIOUS_PHASE_LOGICAL_EVENTS_SUCCEEDED",
                ],
                "failure_effect": "STOP_WITHOUT_LIVE_STATE_MATERIALIZATION_OR_LATER_PHASES",
                "required_state_events": [
                    "STAGE_DRAFT_TO_IN_REVIEW",
                    "STAGE_IN_REVIEW_TO_APPROVED",
                    "STAGE_BASELINED_FOR_A_OR_ACTIVE_OPENING_SNAPSHOT_FOR_B",
                ],
            }
        )
        completed.update(ready)
        remaining.difference_update(ready)
    return phases


def _validate_classification_configuration(
    rows: list[dict[str, Any]], current_planned: set[str]
) -> None:
    all_codes = {row["display_code"] for row in rows}
    row_by_code = {row["display_code"]: row for row in rows}
    groups = {
        DISPOSITION_BASELINE: BASELINE_ELIGIBLE_CODES,
        DISPOSITION_ACTIVE: ACTIVE_INITIAL_CODES,
        DISPOSITION_PLANNED: current_planned | EXTRA_PLANNED_NOT_RUN_CODES,
        DISPOSITION_HOLD: HOLD_CODES,
        DISPOSITION_CONDITIONAL: CONDITIONAL_PENDING_CODES,
    }
    seen: set[str] = set()
    for disposition in DISPOSITION_ORDER:
        codes = groups[disposition]
        _require(not (seen & codes), f"classification sets overlap: {disposition}")
        seen.update(codes)
        _require(
            len(codes) == EXPECTED_COUNTS[disposition],
            f"configured count differs: {disposition}",
        )
    _require(seen == all_codes, "classification sets do not exactly cover DOC-01")
    _require(set(ELIGIBLE_REASONS) == BASELINE_ELIGIBLE_CODES, "eligible reasons differ from codes")
    _require(set(HOLD_REASONS) == HOLD_CODES, "hold reasons differ from codes")
    _require(
        set(ADDITIONAL_CONTENT_GAP_REASONS) <= DEPENDENCY_HOLD_CODES,
        "additional content gaps must belong to dependency holds",
    )
    _require(
        set(CONDITIONAL_REASONS) == CONDITIONAL_PENDING_CODES,
        "conditional reasons differ from codes",
    )
    _require(
        PENDING_ACTIVATION_RECLASSIFIED_CODES <= CONDITIONAL_PENDING_CODES,
        "pending activation set is not conditional",
    )
    pre_dependency_candidates = (
        PRE_DEPENDENCY_BASELINE_CANDIDATE_CODES
        | PRE_DEPENDENCY_ACTIVE_CANDIDATE_CODES
    )
    dependency_closed = set(pre_dependency_candidates)
    while True:
        blocked = {
            code
            for code in dependency_closed
            if any(
                upstream not in dependency_closed
                for upstream in _upstream_codes(row_by_code[code])
            )
        }
        if not blocked:
            break
        dependency_closed.difference_update(blocked)
    configured_eligible = BASELINE_ELIGIBLE_CODES | ACTIVE_INITIAL_CODES
    _require(
        dependency_closed == configured_eligible,
        "eligible set is not the maximal dependency-closed candidate set",
    )
    _require(
        pre_dependency_candidates - configured_eligible == DEPENDENCY_HOLD_CODES,
        "dependency hold set differs from removed candidates",
    )
    _require(
        [phase["artifact_codes"] for phase in _approval_sequence(rows)]
        == [["DOC-01"], ["DOC-03", "DOC-04"], ["DOC-05"]],
        "approval sequence differs from the audited three-phase closure",
    )


def _reject_constant(value: str) -> None:
    raise CandidateError(f"non-standard JSON constant: {value}")


def _strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CandidateError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    value = json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=_strict_pairs,
        parse_constant=_reject_constant,
    )
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _object_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _relative(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _source_binding(path: Path) -> dict[str, Any]:
    _require(path.is_file(), f"missing source: {_relative(path)}")
    return {
        "path": _relative(path),
        "sha256": _file_sha256(path),
        "byte_length": path.stat().st_size,
    }


def _artifact_path(row: dict[str, Any]) -> Path | None:
    location = row["location"]
    relative = location.get("canonical_path") or location.get("planned_canonical_path")
    if not relative:
        return None
    path = REPO_ROOT / relative
    return path if path.is_file() else None


def _artifact_anchor_units(rows: list[dict[str, Any]]) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    by_file: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        path = _artifact_path(row)
        anchor = row["location"].get("coverage_anchor")
        if path is not None and anchor:
            by_file[_relative(path)].append(row)

    units: dict[str, dict[str, Any]] = {}
    preambles: dict[str, dict[str, Any]] = {}
    for relative, file_rows in sorted(by_file.items()):
        path = REPO_ROOT / relative
        text = path.read_text(encoding="utf-8")
        positions: list[tuple[int, str]] = []
        for row in file_rows:
            anchor = row["location"]["coverage_anchor"]
            marker = f'<a id="{anchor}"></a>'
            start = text.find(marker)
            _require(start >= 0, f"missing artifact anchor: {row['display_code']} -> {relative}#{anchor}")
            _require(text.find(marker, start + 1) < 0, f"duplicate artifact anchor: {relative}#{anchor}")
            positions.append((start, row["display_code"]))
        positions.sort()
        _require(len({start for start, _ in positions}) == len(positions), f"overlapping anchors: {relative}")
        first_start = positions[0][0]
        preamble = text[:first_start].encode("utf-8")
        preambles[relative] = {
            "path": relative,
            "unit_kind": "COMMON_PREAMBLE_SNAPSHOT_NOT_ARTIFACT_APPROVAL",
            "byte_length": len(preamble),
            "sha256": hashlib.sha256(preamble).hexdigest(),
        }
        for index, (start, code) in enumerate(positions):
            end = positions[index + 1][0] if index + 1 < len(positions) else len(text)
            raw = text[start:end].encode("utf-8")
            units[code] = {
                "unit_kind": "MARKDOWN_ARTIFACT_ANCHOR",
                "path": relative,
                "anchor": next(
                    row["location"]["coverage_anchor"]
                    for row in file_rows
                    if row["display_code"] == code
                ),
                "byte_length": len(raw),
                "sha256": hashlib.sha256(raw).hexdigest(),
            }
    return units, preambles


def _json_code_projection(payload: dict[str, Any], code: str) -> dict[str, Any] | None:
    identifier_fields = ("artifact_type_id", "artifact_type_code", "artifact_code", "display_code", "design_id")
    selections: list[dict[str, Any]] = []
    for key in sorted(payload):
        values = payload[key]
        if not isinstance(values, list):
            continue
        matched = [
            value
            for value in values
            if isinstance(value, dict)
            and any(value.get(field) == code for field in identifier_fields)
        ]
        if matched:
            selections.append(
                {
                    "pointer": "/" + key.replace("~", "~0").replace("/", "~1"),
                    "identifier_fields": list(identifier_fields),
                    "selection_count": len(matched),
                    "selected_items_canonical_sha256": _object_sha256(matched),
                }
            )
    if not selections:
        return None
    projection_payload = {"artifact_code": code, "selections": selections}
    return {
        **projection_payload,
        "selection_count": sum(item["selection_count"] for item in selections),
        "projection_sha256": _object_sha256(projection_payload),
    }


def _generated_path_components(rows: list[dict[str, Any]]) -> dict[str, dict[str, list[dict[str, Any]]]]:
    """Use DOC-01 generated_paths as the authoritative supporting-file map."""
    users_by_path: dict[str, set[str]] = defaultdict(set)
    for row in rows:
        for relative in row["location"].get("generated_paths", []):
            users_by_path[relative].add(row["display_code"])

    by_code: dict[str, dict[str, list[dict[str, Any]]]] = {}
    for row in rows:
        code = row["display_code"]
        normative: list[dict[str, Any]] = []
        integrity_only: list[dict[str, Any]] = []
        for relative in sorted(row["location"].get("generated_paths", [])):
            path = REPO_ROOT / relative
            _require(path.is_file(), f"missing generated path for {code}: {relative}")
            base = {
                "path": relative,
                "container_file_sha256": _file_sha256(path),
                "container_byte_length": path.stat().st_size,
                "declared_for_artifact_codes": sorted(users_by_path[relative]),
            }
            if path.suffix == ".json" and len(users_by_path[relative]) == 1:
                normative.append(
                    {
                        "role": "DEDICATED_STRUCTURED_OPENING_SNAPSHOT",
                        "kind": "WHOLE_JSON_FILE_BYTES",
                        **base,
                    }
                )
                continue
            if path.suffix == ".json":
                payload = load_strict_json(path)
                projection = _json_code_projection(payload, code)
                if projection is not None:
                    normative.append(
                        {
                            "role": "SHARED_STRUCTURED_CODE_PROJECTION",
                            "kind": "JSON_CODE_PROJECTION",
                            **base,
                            "selector": projection,
                        }
                    )
                    continue
            integrity_only.append(
                {
                    "role": "INTEGRITY_ONLY_NOT_CONTENT_APPROVED",
                    "kind": "WHOLE_SUPPORTING_FILE_BYTES",
                    **base,
                    "reason": (
                        "파생 view이거나 공유 container에서 이 code만 고르는 결정적 selector가 없어 "
                        "변조 방지용으로만 결속한다."
                    ),
                }
            )
        by_code[code] = {
            "normative_components": normative,
            "integrity_only_components": integrity_only,
        }
    return by_code


def _compound_approval_unit(
    code: str,
    primary_unit: dict[str, Any] | None,
    generated_components: dict[str, list[dict[str, Any]]],
) -> dict[str, Any]:
    normative_components: list[dict[str, Any]] = []
    if primary_unit is not None:
        normative_components.append(
            {
                "role": "PRIMARY_ARTIFACT_CONTENT",
                "kind": primary_unit["unit_kind"],
                **{key: value for key, value in primary_unit.items() if key != "unit_kind"},
            }
        )
    normative_components.extend(generated_components["normative_components"])
    components: dict[str, Any] = {
        "unit_kind": "COMPOUND_ARTIFACT_APPROVAL_UNIT",
        "artifact_code": code,
        "normative_components": normative_components,
        "integrity_only_components": generated_components["integrity_only_components"],
        "semantic_scope": (
            "normative_components만 이 artifact의 내용 승인 단위다. integrity_only_components와 공유 "
            "파일의 다른 code는 내용 승인 대상이 아니며 각 행의 별도 분류를 따른다."
        ),
    }
    return {**components, "compound_sha256": _object_sha256(components)}


def _declared_versions(path: Path, related_rows: list[dict[str, Any]]) -> list[str]:
    versions = {
        row["version"].get("document_version")
        for row in related_rows
        if row["version"].get("document_version")
    }
    if path.suffix == ".json":
        try:
            payload = load_strict_json(path)
        except (CandidateError, UnicodeDecodeError, json.JSONDecodeError):
            payload = {}
        containers = [payload]
        if isinstance(payload.get("metadata"), dict):
            containers.append(payload["metadata"])
        for container in containers:
            for key in (
                "document_version",
                "manifest_version",
                "report_version",
                "register_version",
                "version",
            ):
                value = container.get(key)
                if isinstance(value, str) and value.strip():
                    versions.add(value.strip())
    elif path.suffix in {".md", ".html"}:
        text = path.read_text(encoding="utf-8")
        for pattern in (
            r"(?:문서\s*)?버전:\s*`?([0-9]+\.[0-9]+\.[0-9]+)`?",
            r"\|\s*(?:문서\s*)?버전\s*\|\s*`?([0-9]+\.[0-9]+\.[0-9]+)`?",
        ):
            versions.update(re.findall(pattern, text))
    return sorted(value for value in versions if value)


def _disposition_for(code: str, current_planned: set[str]) -> str:
    if code in BASELINE_ELIGIBLE_CODES:
        return DISPOSITION_BASELINE
    if code in ACTIVE_INITIAL_CODES:
        return DISPOSITION_ACTIVE
    if code in current_planned or code in EXTRA_PLANNED_NOT_RUN_CODES:
        return DISPOSITION_PLANNED
    if code in HOLD_CODES:
        return DISPOSITION_HOLD
    if code in CONDITIONAL_PENDING_CODES:
        return DISPOSITION_CONDITIONAL
    raise CandidateError(f"unclassified artifact: {code}")


def _reason_for(
    code: str,
    disposition: str,
    current_status: str,
    unapproved_upstream_codes: list[str],
) -> str:
    if disposition == DISPOSITION_BASELINE:
        return ELIGIBLE_REASONS[code]
    if disposition == DISPOSITION_ACTIVE:
        return (
            "원장 schema·통제 규칙과 승인 시점 최초 snapshot만 승인하고 이후에는 사건·변경 때 "
            "append-only 또는 새 통제 형상으로 계속 갱신한다."
        )
    if disposition == DISPOSITION_PLANNED:
        if current_status == "PLANNED":
            return "실제 시험·측정·배포·서명·운영·종료 근거가 없어 기존 Planned/NOT_RUN 상태를 유지한다."
        return (
            "0~6 Draft 안에 실행 계약만 있었을 뿐 정식 결과는 0건이다. 승인 시 결과로 승격하지 않고 "
            "Planned/NOT_RUN으로 바로잡는다."
        )
    if disposition == DISPOSITION_HOLD:
        if code in DEPENDENCY_HOLD_CODES:
            reason = (
                "작성·승인 선후관계상 다음 선행 산출물이 이번 승인 대상이 아니므로, 내용의 나머지 "
                f"적격성과 무관하게 Draft로 보류한다: {', '.join(unapproved_upstream_codes)}."
            )
            if code in ADDITIONAL_CONTENT_GAP_REASONS:
                reason += f" 별도 내용 보완사항: {ADDITIONAL_CONTENT_GAP_REASONS[code]}"
            return reason
        return HOLD_REASONS[code]
    if disposition == DISPOSITION_CONDITIONAL:
        return CONDITIONAL_REASONS[code]
    raise CandidateError(f"unknown disposition: {disposition}")


def _target_state(disposition: str) -> dict[str, Any]:
    if disposition == DISPOSITION_BASELINE:
        return {
            "lifecycle_status": "APPROVED",
            "baseline_status": "BASELINED",
            "operational_status": "VERSIONED_BASELINE",
        }
    if disposition == DISPOSITION_ACTIVE:
        return {
            "lifecycle_status": "APPROVED",
            "baseline_status": "INITIAL_SNAPSHOT_APPROVED_NOT_VERSIONED_BASELINE",
            "operational_status": "ACTIVE_CONTINUOUS_CONTROL",
        }
    if disposition == DISPOSITION_PLANNED:
        return {
            "lifecycle_status": "PLANNED",
            "baseline_status": "NOT_BASELINED",
            "operational_status": "NOT_RUN",
        }
    if disposition == DISPOSITION_HOLD:
        return {
            "lifecycle_status": "DRAFT",
            "baseline_status": "NOT_BASELINED",
            "operational_status": "HOLD_FOR_GAP_OR_EXECUTION_SCOPE",
        }
    if disposition == DISPOSITION_CONDITIONAL:
        return {
            "lifecycle_status": "DRAFT",
            "baseline_status": "NOT_BASELINED",
            "operational_status": "PENDING_APPLICABILITY_OR_VALUE",
        }
    raise CandidateError(f"unknown disposition: {disposition}")


def _approval_object(disposition: str) -> str:
    if disposition == DISPOSITION_BASELINE:
        return "NORMATIVE_COMPOUND_ARTIFACT_CONTENT"
    if disposition == DISPOSITION_ACTIVE:
        return "CONTROL_CONTRACT_AND_BOUND_OPENING_SNAPSHOT_ONLY"
    return "NONE"


def _approval_scope(disposition: str) -> str:
    if disposition == DISPOSITION_BASELINE:
        return (
            "해당 artifact anchor와 이 code를 선언한 구조화 snapshot의 목표 정책·요구·설계·계획·절차, "
            "그리고 명시된 미결 경계. 공유 파일의 다른 code, 현행 구현 후보·실행 결과·출시 적합성은 제외한다."
        )
    if disposition == DISPOSITION_ACTIVE:
        return (
            "해당 artifact anchor와 이 code를 선언한 구조화 파일의 schema·갱신 규칙·승인 시점 최초 "
            "snapshot. 공유 파일의 다른 code와 향후 추가 행, 사실 수용, 구현 적합성·완료 판정은 미리 승인하지 않는다."
        )
    return "승인 대상이 아니다. 현재 차단·대기 상태와 작성 계약만 공개한다."


def _build_artifact_dispositions(register: dict[str, Any]) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    rows = register["artifacts"]
    current_planned = {
        row["display_code"]
        for row in rows
        if row["state"]["lifecycle_status"] == "PLANNED"
    }
    _require(len(current_planned) == 52, "current Planned count differs from approved preparation state")
    _require(not (current_planned & EXTRA_PLANNED_NOT_RUN_CODES), "extra Planned set overlaps current Planned")
    _validate_classification_configuration(rows, current_planned)
    disposition_by_code = {
        row["display_code"]: _disposition_for(row["display_code"], current_planned)
        for row in rows
    }

    anchor_units, preambles = _artifact_anchor_units(rows)
    generated_by_code = _generated_path_components(rows)
    result: list[dict[str, Any]] = []
    for row in rows:
        code = row["display_code"]
        disposition = disposition_by_code[code]
        upstream_codes = _upstream_codes(row)
        unapproved_upstream_codes = [
            upstream
            for upstream in upstream_codes
            if disposition_by_code[upstream]
            not in {DISPOSITION_BASELINE, DISPOSITION_ACTIVE}
        ]
        classification_blockers: list[dict[str, Any]] = []
        if code in DEPENDENCY_HOLD_CODES:
            classification_blockers.append(
                {
                    "type": "UPSTREAM_NOT_APPROVED",
                    "artifact_codes": unapproved_upstream_codes,
                }
            )
        if code in ADDITIONAL_CONTENT_GAP_REASONS:
            classification_blockers.append(
                {
                    "type": "CONTENT_GAP",
                    "description": ADDITIONAL_CONTENT_GAP_REASONS[code],
                }
            )
        if code in ADDITIONAL_CONTENT_GAP_REASONS:
            content_review_result = "CONTENT_GAP_AND_UPSTREAM_BLOCKED"
        elif code == "REQ-16":
            content_review_result = "CONTENT_READY_UPSTREAM_BLOCKED_TRACE_SCOPE_CLARIFIED"
        elif code in DEPENDENCY_HOLD_CODES:
            content_review_result = "CONTENT_REVIEW_PASSED_UPSTREAM_BLOCKED"
        else:
            content_review_result = "NOT_SEPARATELY_ASSERTED"
        path = _artifact_path(row)
        unit = anchor_units.get(code)
        if unit is None and path is not None:
            raw = path.read_bytes()
            unit = {
                "unit_kind": "WHOLE_FILE_ARTIFACT",
                "path": _relative(path),
                "anchor": None,
                "byte_length": len(raw),
                "sha256": hashlib.sha256(raw).hexdigest(),
            }
        approved_trace = row["trace"]["approved_input_trace"]
        current_status = row["state"]["lifecycle_status"]
        result.append(
            {
                "display_code": code,
                "artifact_type_code": row["artifact_type_code"],
                "artifact_instance_id": row["artifact_instance_id"],
                "title": row["title"],
                "category": row["category"],
                "applicability": row["applicability"],
                "activation_result": row["activation_result"],
                "artifact_form": row["artifact_form"],
                "change_profile_id": row["management_contract"]["change_profile_id"],
                "disposition": disposition,
                "classification_reason": _reason_for(
                    code,
                    disposition,
                    current_status,
                    unapproved_upstream_codes,
                ),
                "upstream_artifact_codes": upstream_codes,
                "unapproved_upstream_codes": unapproved_upstream_codes,
                "classification_blockers": classification_blockers,
                "content_review_result": content_review_result,
                "approval_object": _approval_object(disposition),
                "approval_scope": _approval_scope(disposition),
                "current_state": {
                    "lifecycle_status": current_status,
                    "verification_status": row["state"]["verification_status"],
                    "blockers": row["state"]["blockers"],
                    "approval_status": "NOT_APPROVED",
                    "baseline_id": row["version"]["baseline_id"],
                },
                "target_state_after_explicit_approval": _target_state(disposition),
                "artifact_content_approval_proposed": disposition
                in {DISPOSITION_BASELINE, DISPOSITION_ACTIVE},
                "implementation_conformance_assessed": False,
                "implementation_completion_claimed": False,
                "execution_completion_claimed": False,
                "test_pass_claimed": False,
                "release_or_handover_approval_claimed": False,
                "canonical_or_contract_path": unit["path"] if unit else row["location"].get("planned_canonical_path"),
                "coverage_anchor": row["location"].get("coverage_anchor"),
                "document_version": row["version"]["document_version"],
                "primary_content_unit": unit,
                "approval_unit": _compound_approval_unit(code, unit, generated_by_code[code]),
                "gate_ids": approved_trace["gate_ids"],
                "open_issue_refs": approved_trace["open_issue_refs"],
                "feature_policy_ids": approved_trace["feature_policy_ids"],
                "trace_reference_scope": (
                    "DOC-01_DIRECT_TYPE_INPUT_TRACE; NOT_A_COMPLETE_SCAN_OF_REFERENCES_INSIDE_"
                    "THE_ARTIFACT_CONTENT"
                ),
            }
        )
    return result, preambles


def _build_file_inventory(
    register: dict[str, Any], dispositions: list[dict[str, Any]], preambles: dict[str, dict[str, Any]]
) -> list[dict[str, Any]]:
    row_by_code = {row["display_code"]: row for row in register["artifacts"]}
    units_by_path: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for item in dispositions:
        unit = item["primary_content_unit"]
        if unit:
            units_by_path[unit["path"]].append(item)

    inventory: list[dict[str, Any]] = []
    for index, path in enumerate(sorted(item for item in DELIVERABLE_ROOT.rglob("*") if item.is_file()), 1):
        relative = _relative(path)
        related = sorted(units_by_path.get(relative, []), key=lambda item: item["display_code"])
        dispositions_in_file = sorted({item["disposition"] for item in related})
        if not related:
            role = "SUPPORTING_OR_DERIVED_SNAPSHOT"
        elif len(dispositions_in_file) > 1:
            role = "MIXED_APPROVAL_SNAPSHOT"
        elif dispositions_in_file == [DISPOSITION_BASELINE]:
            role = "BASELINE_CONTENT_SNAPSHOT"
        elif dispositions_in_file == [DISPOSITION_ACTIVE]:
            role = "ACTIVE_INITIAL_SNAPSHOT"
        else:
            role = "NON_APPROVED_CONTRACT_OR_HOLD_SNAPSHOT"
        versions = _declared_versions(path, [row_by_code[item["display_code"]] for item in related])
        inventory.append(
            {
                "file_id": f"FILE-{index:03d}",
                "path": relative,
                "role": role,
                "byte_length": path.stat().st_size,
                "file_sha256": _file_sha256(path),
                "document_versions": versions,
                "version_label": ", ".join(versions) if versions else "HASH_BOUND_NOT_SEPARATELY_VERSIONED",
                "covered_artifact_codes": [item["display_code"] for item in related],
                "covered_dispositions": dispositions_in_file,
                "common_preamble_unit": preambles.get(relative),
            }
        )
    return inventory


def _build_required_statement(target: dict[str, Any], summary: dict[str, Any]) -> str:
    return (
        f"승인 후보 {CANDIDATE_ID} 버전 {CANDIDATE_VERSION}, 승인대상 지문 "
        f"{target['approval_target_sha256']}, 파일집합 지문 {target['file_set_binding_sha256']}, "
        f"분류 지문 {target['classification_binding_sha256']}를 근거로 WalkSafe 산출물 중 기준선 적격 "
        f"{summary[DISPOSITION_BASELINE]}개(DOC-03·DOC-04)를 CB-WALKSAFE-DOCUMENT-CONTROL-1.0.0으로 "
        "Approved/Baselined로, 최초 Active 적격 "
        f"{summary[DISPOSITION_ACTIVE]}개(DOC-01·DOC-05)를 ART-DOC-01-001-SNAPSHOT-001 및 "
        "ART-DOC-05-001-SNAPSHOT-001 최초본 승인과 계속 갱신 상태로 전환하는 것을 승인합니다. "
        f"기존 Planned 52개와 추가 미실행 증거 23개를 합한 {summary[DISPOSITION_PLANNED]}개는 "
        f"Planned/NOT_RUN으로 유지·정정하고, 내용·승인단위·선행관계 또는 구현·실행근거 보완이 필요한 "
        f"{summary[DISPOSITION_HOLD]}개와 "
        f"적용성·외부값·측정값 확정 대기 {summary[DISPOSITION_CONDITIONAL]}개는 승인하지 않습니다. "
        "전환은 변경 전 불변 snapshot을 "
        "한 번 검증·동결한 뒤 1단계 DOC-01, 2단계 DOC-03·DOC-04, 3단계 DOC-05의 논리 승인 사건을 순서대로 "
        "적용하고, 앞 단계 실패 시 후속 단계를 중단하며, 전 단계 성공 뒤에만 DOC-01·DOC-05를 한 번 갱신합니다. "
        "이 승인은 현행 구현 적합성, "
        "시험·배포·운영·인수·종료 완료를 뜻하지 않으며, FP-035는 OPEN, 5개 gate는 NOT_RUN·미면제, "
        "출시는 NOT_ELIGIBLE로 유지합니다."
    )


def _source_paths() -> dict[str, Path]:
    return {
        "generator": GENERATOR_PATH,
        "artifact_register": REGISTER_PATH,
        "artifact_change_log": CHANGE_LOG_PATH,
        "document_control_manual": MANUAL_PATH,
        "artifact_catalog": CATALOG_PATH,
        "authoring_plan": AUTHORING_PLAN_PATH,
        "policy_approval_record": POLICY_APPROVAL_PATH,
        "policy_baseline_manifest": POLICY_MANIFEST_PATH,
        "policy_review_resolution": REVIEW_RESOLUTION_PATH,
        "approved_policy_document": POLICY_DOCUMENT_PATH,
        "aligned_decision_register": DECISION_REGISTER_PATH,
        "formal_trace_0_to_6": TRACE_0_6_PATH,
        "formal_trace_7_to_12": TRACE_7_12_PATH,
    }


def _candidate_metadata() -> dict[str, Any]:
    return {
        "candidate_id": CANDIDATE_ID,
        "candidate_version": CANDIDATE_VERSION,
        "controlled_revision": 1,
        "prepared_at": PREPARED_AT,
        "lifecycle_status": "READY_FOR_OWNER_APPROVAL",
        "approval_status": "NOT_APPROVED",
        "approved_at": None,
        "approver": None,
        "supersedes_candidate_id": None,
    }


def _authority_boundary() -> dict[str, Any]:
    return {
        "normative_basis": "APPROVED_POLICY_AND_OWNER_DECISIONS_ONLY",
        "current_implementation_used_as_policy_basis": False,
        "current_implementation_may_appear_only_as_informative_snapshot": True,
        "implementation_conformance_assessed": False,
        "implementation_completion_claimed": False,
        "test_completion_claimed": False,
        "deployment_completion_claimed": False,
        "operation_completion_claimed": False,
        "acceptance_or_closure_completion_claimed": False,
        "human_review_or_approval_already_recorded": False,
        "fake_reviewer_or_approver_created": False,
        "approval_requires_later_exact_owner_statement": True,
        "fp035_status": "OPEN_OWNER_CLARIFICATION_AND_REAPPROVAL_REQUIRED",
        "fp035_implementation_and_test_branch": "FROZEN",
        "remaining_gate_count": 5,
        "remaining_gates_are_waived": False,
        "release_status": "NOT_ELIGIBLE",
        "web_pwa_status": "LEGACY_REFERENCE_NOT_CURRENT_PRODUCT",
        "current_products": ["ANDROID_USER_APP", "SEPARATE_PRIVATE_ANDROID_ADMIN_APP"],
    }


def _known_open_issues() -> list[dict[str, Any]]:
    return [
        {
            "id": "ISS-POLICY-FP035-NETWORK-001",
            "status": "OPEN_OWNER_CLARIFICATION_AND_REAPPROVAL_REQUIRED",
            "linked_refs": FP035_OPEN_REFS,
            "approval_effect": "NOT_CLOSED_OR_WAIVED",
            "implementation_and_test_effect": "GENERAL_ACTIVITY_RAW_MOBILE_NETWORK_BRANCH_FROZEN",
        }
    ]


def _state_transition_plan(
    approval_sequence: list[dict[str, Any]], deliverable_file_count: int
) -> dict[str, Any]:
    return {
        "candidate_generation_changes_artifact_states": False,
        "exact_owner_statement_required": True,
        "pre_transition_hash_reverification_required": True,
        "pre_transition_snapshot_required_before_mutation": True,
        "immutable_pre_transition_snapshot_created_now": False,
        "candidate_invalid_if_any_bound_source_or_target_changes": True,
        "candidate_invalidation_scope": "BEFORE_TRANSACTION_START",
        "ordered_approval_phases": approval_sequence,
        "later_phase_requires_previous_phase_success": True,
        "phase_failure_stops_all_later_phases": True,
        "pre_transition_hash_reverification_occurs_once": True,
        "each_phase_requires_live_hash_reverification": False,
        "live_doc01_or_doc05_mutation_during_phases": False,
        "materialize_live_state_only_after_all_phases_succeed": True,
        "state_event_chain_required": "STAGED_DRAFT_TO_IN_REVIEW_TO_APPROVED_THEN_BASELINED_OR_ACTIVE",
        "after_explicit_approval": {
            DISPOSITION_BASELINE: "APPROVED + BASELINED",
            DISPOSITION_ACTIVE: "OPENING_SNAPSHOT APPROVED + OPERATIONAL ACTIVE",
            DISPOSITION_PLANNED: "PLANNED + NOT_RUN",
            DISPOSITION_HOLD: "DRAFT + HOLD",
            DISPOSITION_CONDITIONAL: "DRAFT + PENDING_EVALUATION",
        },
        "nonapproved_classification_recording": {
            "planned": "C 75개의 NOT_RUN 유지·정정 상태를 DOC-01과 DOC-05에 기록",
            "holds": "D 121개의 classification_reason과 unapproved_upstream_codes를 DOC-01 blocker와 DOC-05 사건에 기록",
            "conditional": "E 57개의 classification_reason과 PENDING_EVALUATION 상태를 DOC-01과 DOC-05에 기록",
        },
        "approval_source_record_created_now": False,
        "approval_intake_created_now": False,
        "approval_record_created_now": False,
        "baseline_manifest_created_now": False,
        "doc01_or_doc05_approval_state_changed_now": False,
        "post_approval_outputs": [
            "사용자 승인 원문 통제 사본",
            "승인 intake와 불변 approval record",
            f"상태 변경 전 {deliverable_file_count}개 대상 파일의 불변 byte snapshot과 manifest",
            "분야별 기준선 manifest와 Active 최초 snapshot",
            "DOC-01 상태·기준선 ID·분류 blocker와 DOC-05 승인·분류 변경 사건",
        ],
    }


def _classification_summary(
    dispositions: list[dict[str, Any]], file_inventory: list[dict[str, Any]]
) -> dict[str, Any]:
    counts = Counter(item["disposition"] for item in dispositions)
    summary = {disposition: counts[disposition] for disposition in DISPOSITION_ORDER}
    summary.update(
        {
            "artifact_type_count": 257,
            "approval_eligible_count": counts[DISPOSITION_BASELINE] + counts[DISPOSITION_ACTIVE],
            "not_approved_by_this_package_count": (
                counts[DISPOSITION_PLANNED]
                + counts[DISPOSITION_HOLD]
                + counts[DISPOSITION_CONDITIONAL]
            ),
            "current_planned_count": 52,
            "additional_pre_execution_reclassification_count": 23,
            "pending_activation_reclassified_count": 28,
            "strict_completion_or_binding_reclassified_count": 92,
            "upstream_dependency_reclassified_count": 30,
            "deliverable_file_count": len(file_inventory),
            "mixed_file_count": sum(
                item["role"] == "MIXED_APPROVAL_SNAPSHOT" for item in file_inventory
            ),
        }
    )
    return summary


def build_candidate() -> dict[str, Any]:
    register = load_strict_json(REGISTER_PATH)
    policy_approval = load_strict_json(POLICY_APPROVAL_PATH)
    policy_manifest = load_strict_json(POLICY_MANIFEST_PATH)
    _require(register["summary"]["artifact_type_count"] == 257, "master register must contain 257 artifacts")
    _require(register["summary"]["approved_artifact_count"] == 0, "an artifact already claims approval")
    _require(register["authorization_boundary"]["release_status"] == "NOT_ELIGIBLE", "release boundary differs")
    for path, expected in EXPECTED_IMMUTABLE_HASHES.items():
        _require(_file_sha256(path) == expected, f"immutable source hash differs: {_relative(path)}")

    dispositions, preambles = _build_artifact_dispositions(register)
    counts = Counter(item["disposition"] for item in dispositions)
    _require(dict(counts) == EXPECTED_COUNTS, f"classification counts differ: {dict(counts)}")
    _require(len({item["display_code"] for item in dispositions}) == 257, "classification is not one-to-one")

    file_inventory = _build_file_inventory(register, dispositions, preambles)
    _require(len(file_inventory) >= 100, "deliverable file inventory is unexpectedly small")

    source_bindings = {key: _source_binding(path) for key, path in _source_paths().items()}
    source_binding_sha256 = _object_sha256(source_bindings)

    gates = policy_approval["remaining_gates"]
    _require({gate["id"] for gate in gates} == EXPECTED_GATE_IDS, "gate set differs")
    _require(all(gate["status"] == "NOT_RUN" for gate in gates), "a gate claims execution")
    _require(policy_approval["approval_boundary"]["remaining_gates_are_waived"] is False, "a gate is waived")
    _require(
        policy_manifest["baseline_payload"]["approval_boundary"]["release_status"]
        == "NOT_ELIGIBLE",
        "policy manifest release differs",
    )

    summary = _classification_summary(dispositions, file_inventory)
    authority_boundary = _authority_boundary()
    approval_sequence = _approval_sequence(register["artifacts"])

    classification_binding_sha256 = _object_sha256(dispositions)
    file_set_binding_sha256 = _object_sha256(file_inventory)
    boundary_binding_sha256 = _object_sha256(authority_boundary)
    preamble_binding_sha256 = _object_sha256(preambles)
    approval_sequence_binding_sha256 = _object_sha256(approval_sequence)
    planned_identifier_binding_sha256 = _object_sha256(PLANNED_POST_APPROVAL_IDENTIFIERS)
    target_payload = {
        "candidate_id": CANDIDATE_ID,
        "candidate_version": CANDIDATE_VERSION,
        "policy_baseline_id": policy_approval["approved_baseline_payload"]["baseline_id"],
        "policy_baseline_version": policy_approval["approved_baseline_payload"]["baseline_version"],
        "classification_summary": summary,
        "classification_binding_sha256": classification_binding_sha256,
        "file_set_binding_sha256": file_set_binding_sha256,
        "boundary_binding_sha256": boundary_binding_sha256,
        "preamble_binding_sha256": preamble_binding_sha256,
        "source_binding_sha256": source_binding_sha256,
        "approval_sequence_binding_sha256": approval_sequence_binding_sha256,
        "planned_post_approval_identifier_binding_sha256": planned_identifier_binding_sha256,
        "approval_effect": {
            "baseline_candidates_to_approve": counts[DISPOSITION_BASELINE],
            "active_initial_snapshots_to_approve": counts[DISPOSITION_ACTIVE],
            "planned_not_run_after_approval": counts[DISPOSITION_PLANNED],
            "draft_holds_not_approved": counts[DISPOSITION_HOLD],
            "conditional_pending_not_approved": counts[DISPOSITION_CONDITIONAL],
            "implementation_or_execution_approval": False,
            "release_approval": False,
        },
    }
    approval_target_sha256 = _object_sha256(target_payload)
    target_payload["approval_target_sha256"] = approval_target_sha256
    required_statement = _build_required_statement(target_payload, summary)

    candidate: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "metadata": _candidate_metadata(),
        "source_bindings": source_bindings,
        "source_binding_sha256": source_binding_sha256,
        "authority_boundary": authority_boundary,
        "authority_boundary_sha256": boundary_binding_sha256,
        "classification_summary": summary,
        "artifact_dispositions": dispositions,
        "classification_binding_sha256": classification_binding_sha256,
        "file_inventory": file_inventory,
        "file_set_binding_sha256": file_set_binding_sha256,
        "common_preamble_units": preambles,
        "common_preamble_binding_sha256": preamble_binding_sha256,
        "remaining_gates": gates,
        "known_open_issues": _known_open_issues(),
        "approval_sequence": approval_sequence,
        "approval_sequence_binding_sha256": approval_sequence_binding_sha256,
        "planned_post_approval_identifiers": PLANNED_POST_APPROVAL_IDENTIFIERS,
        "planned_post_approval_identifier_binding_sha256": planned_identifier_binding_sha256,
        "state_transition_plan": _state_transition_plan(
            approval_sequence, summary["deliverable_file_count"]
        ),
        "approval_target": target_payload,
        "approval_target_sha256": approval_target_sha256,
        "required_approval_statement": required_statement,
        "required_approval_statement_sha256": hashlib.sha256(required_statement.encode("utf-8")).hexdigest(),
    }
    candidate["candidate_record_content_sha256"] = _object_sha256(candidate)
    validate_candidate(candidate, verify_files=True)
    return candidate


def validate_candidate(candidate: dict[str, Any], *, verify_files: bool) -> None:
    _require(candidate["schema_version"] == SCHEMA_VERSION, "candidate schema differs")
    metadata = candidate["metadata"]
    _require(metadata == _candidate_metadata(), "candidate metadata differs")
    _require(metadata["approval_status"] == "NOT_APPROVED", "candidate claims approval")
    _require(metadata["approver"] is None and metadata["approved_at"] is None, "candidate invents approval metadata")
    _require(metadata["lifecycle_status"] == "READY_FOR_OWNER_APPROVAL", "candidate lifecycle differs")
    _require(candidate["authority_boundary_sha256"] == _object_sha256(candidate["authority_boundary"]), "authority hash differs")
    _require(candidate["source_binding_sha256"] == _object_sha256(candidate["source_bindings"]), "source hash differs")
    _require(candidate["classification_binding_sha256"] == _object_sha256(candidate["artifact_dispositions"]), "classification hash differs")
    _require(candidate["file_set_binding_sha256"] == _object_sha256(candidate["file_inventory"]), "file set hash differs")
    _require(candidate["common_preamble_binding_sha256"] == _object_sha256(candidate["common_preamble_units"]), "preamble hash differs")
    target_without_hash = {
        key: value for key, value in candidate["approval_target"].items() if key != "approval_target_sha256"
    }
    _require(candidate["approval_target_sha256"] == _object_sha256(target_without_hash), "approval target hash differs")
    _require(candidate["approval_target"]["approval_target_sha256"] == candidate["approval_target_sha256"], "nested target hash differs")
    target = candidate["approval_target"]
    _require(target["candidate_id"] == CANDIDATE_ID, "approval target candidate ID differs")
    _require(target["candidate_version"] == CANDIDATE_VERSION, "approval target candidate version differs")
    _require(
        target["policy_baseline_id"] == "PB-WALKSAFE-FEATURE-POLICY-1.0.0"
        and target["policy_baseline_version"] == "1.0.0",
        "approval target policy baseline differs",
    )
    _require(target["classification_summary"] == candidate["classification_summary"], "target summary differs")
    _require(
        target["classification_binding_sha256"] == candidate["classification_binding_sha256"],
        "target classification binding differs",
    )
    _require(
        target["file_set_binding_sha256"] == candidate["file_set_binding_sha256"],
        "target file-set binding differs",
    )
    _require(
        target["boundary_binding_sha256"] == candidate["authority_boundary_sha256"],
        "target authority binding differs",
    )
    _require(
        target["preamble_binding_sha256"] == candidate["common_preamble_binding_sha256"],
        "target preamble binding differs",
    )
    _require(
        target["source_binding_sha256"] == candidate["source_binding_sha256"],
        "target source binding differs",
    )
    _require(
        candidate["approval_sequence_binding_sha256"]
        == _object_sha256(candidate["approval_sequence"]),
        "approval sequence hash differs",
    )
    _require(
        target["approval_sequence_binding_sha256"]
        == candidate["approval_sequence_binding_sha256"],
        "target approval sequence binding differs",
    )
    _require(
        candidate["planned_post_approval_identifiers"]
        == PLANNED_POST_APPROVAL_IDENTIFIERS,
        "planned post-approval identifiers differ",
    )
    _require(
        candidate["planned_post_approval_identifier_binding_sha256"]
        == _object_sha256(PLANNED_POST_APPROVAL_IDENTIFIERS),
        "planned post-approval identifier hash differs",
    )
    _require(
        target["planned_post_approval_identifier_binding_sha256"]
        == candidate["planned_post_approval_identifier_binding_sha256"],
        "target planned identifier binding differs",
    )
    expected_effect = {
        "baseline_candidates_to_approve": EXPECTED_COUNTS[DISPOSITION_BASELINE],
        "active_initial_snapshots_to_approve": EXPECTED_COUNTS[DISPOSITION_ACTIVE],
        "planned_not_run_after_approval": EXPECTED_COUNTS[DISPOSITION_PLANNED],
        "draft_holds_not_approved": EXPECTED_COUNTS[DISPOSITION_HOLD],
        "conditional_pending_not_approved": EXPECTED_COUNTS[DISPOSITION_CONDITIONAL],
        "implementation_or_execution_approval": False,
        "release_approval": False,
    }
    _require(target["approval_effect"] == expected_effect, "approval target effect differs")
    expected_statement = _build_required_statement(target, candidate["classification_summary"])
    _require(candidate["required_approval_statement"] == expected_statement, "approval statement content differs")
    _require(
        candidate["required_approval_statement_sha256"]
        == hashlib.sha256(candidate["required_approval_statement"].encode("utf-8")).hexdigest(),
        "approval statement hash differs",
    )
    content_without_hash = {
        key: value for key, value in candidate.items() if key != "candidate_record_content_sha256"
    }
    _require(candidate["candidate_record_content_sha256"] == _object_sha256(content_without_hash), "candidate content hash differs")

    rows = candidate["artifact_dispositions"]
    _require(len(rows) == len({row["display_code"] for row in rows}) == 257, "candidate rows are not exactly 257")
    counts = Counter(row["disposition"] for row in rows)
    _require(dict(counts) == EXPECTED_COUNTS, f"candidate counts differ: {dict(counts)}")
    codes_by_disposition = {
        disposition: {
            row["display_code"]
            for row in rows
            if row["disposition"] == disposition
        }
        for disposition in DISPOSITION_ORDER
    }
    _require(
        codes_by_disposition[DISPOSITION_BASELINE] == BASELINE_ELIGIBLE_CODES,
        "baseline eligible code set differs",
    )
    _require(
        codes_by_disposition[DISPOSITION_ACTIVE] == ACTIVE_INITIAL_CODES,
        "active opening code set differs",
    )
    _require(
        codes_by_disposition[DISPOSITION_HOLD] == HOLD_CODES,
        "hold code set differs",
    )
    current_planned_codes = {
        row["display_code"]
        for row in rows
        if row["current_state"]["lifecycle_status"] == "PLANNED"
    }
    _require(
        codes_by_disposition[DISPOSITION_PLANNED]
        == current_planned_codes | EXTRA_PLANNED_NOT_RUN_CODES,
        "planned code set differs",
    )
    _require(
        codes_by_disposition[DISPOSITION_CONDITIONAL] == CONDITIONAL_PENDING_CODES,
        "conditional code set differs",
    )
    _require(
        candidate["classification_summary"]
        == _classification_summary(rows, candidate["file_inventory"]),
        "candidate summary differs from rows or inventory",
    )
    for row in rows:
        disposition = row["disposition"]
        proposed = row["artifact_content_approval_proposed"]
        _require(proposed == (disposition in {DISPOSITION_BASELINE, DISPOSITION_ACTIVE}), f"approval effect differs: {row['display_code']}")
        _require(
            row["target_state_after_explicit_approval"] == _target_state(disposition),
            f"target state differs: {row['display_code']}",
        )
        _require(
            row["approval_object"] == _approval_object(disposition),
            f"approval object differs: {row['display_code']}",
        )
        _require(
            row["approval_scope"] == _approval_scope(disposition),
            f"approval scope differs: {row['display_code']}",
        )
        compound = row["approval_unit"]
        compound_without_hash = {
            key: value for key, value in compound.items() if key != "compound_sha256"
        }
        _require(
            compound["compound_sha256"] == _object_sha256(compound_without_hash),
            f"compound approval unit hash differs: {row['display_code']}",
        )
        _require(
            all(
                component["role"] == "INTEGRITY_ONLY_NOT_CONTENT_APPROVED"
                for component in compound["integrity_only_components"]
            ),
            f"integrity-only component role differs: {row['display_code']}",
        )
        if disposition in {DISPOSITION_BASELINE, DISPOSITION_ACTIVE}:
            _require(
                row["activation_result"] == "ACTIVE",
                f"pending applicability proposed for approval: {row['display_code']}",
            )
            _require(
                not row["unapproved_upstream_codes"],
                f"eligible row has unapproved upstream: {row['display_code']}",
            )
        if row["display_code"] in DEPENDENCY_HOLD_CODES:
            _require(
                bool(row["unapproved_upstream_codes"]),
                f"dependency hold has no blocker: {row['display_code']}",
            )
            _require(
                row["classification_blockers"]
                and row["classification_blockers"][0]["type"]
                == "UPSTREAM_NOT_APPROVED",
                f"dependency blocker is not structured: {row['display_code']}",
            )
        _require(row["implementation_conformance_assessed"] is False, f"implementation assessed: {row['display_code']}")
        _require(row["implementation_completion_claimed"] is False, f"implementation claimed: {row['display_code']}")
        _require(row["execution_completion_claimed"] is False, f"execution claimed: {row['display_code']}")
        _require(row["test_pass_claimed"] is False, f"test pass claimed: {row['display_code']}")
        _require(
            row["release_or_handover_approval_claimed"] is False,
            f"release or handover claimed: {row['display_code']}",
        )
        if disposition == DISPOSITION_PLANNED:
            _require(row["target_state_after_explicit_approval"]["operational_status"] == "NOT_RUN", f"planned execution claimed: {row['display_code']}")
    boundary = candidate["authority_boundary"]
    _require(boundary == _authority_boundary(), "candidate authority boundary differs")
    _require(boundary["current_implementation_used_as_policy_basis"] is False, "implementation became policy basis")
    _require(boundary["release_status"] == "NOT_ELIGIBLE", "candidate claims release eligibility")
    _require(boundary["remaining_gates_are_waived"] is False, "candidate waives gates")
    _require(len(candidate["remaining_gates"]) == 5, "candidate gate count differs")
    _require({gate["id"] for gate in candidate["remaining_gates"]} == EXPECTED_GATE_IDS, "candidate gate set differs")
    _require(all(gate["status"] == "NOT_RUN" for gate in candidate["remaining_gates"]), "candidate gate executed")
    _require(candidate["known_open_issues"] == _known_open_issues(), "known open issues differ")
    register = load_strict_json(REGISTER_PATH)
    expected_sequence = _approval_sequence(register["artifacts"])
    _require(candidate["approval_sequence"] == expected_sequence, "approval sequence differs")
    _require(
        candidate["state_transition_plan"]
        == _state_transition_plan(
            expected_sequence,
            candidate["classification_summary"]["deliverable_file_count"],
        ),
        "state transition plan differs",
    )
    _require(candidate["state_transition_plan"]["candidate_generation_changes_artifact_states"] is False, "candidate changes states")
    _require(candidate["state_transition_plan"]["approval_record_created_now"] is False, "candidate creates approval record")

    if verify_files:
        for path, expected_hash in EXPECTED_IMMUTABLE_HASHES.items():
            _require(_file_sha256(path) == expected_hash, f"immutable source hash differs: {_relative(path)}")
        expected_sources = {key: _source_binding(path) for key, path in _source_paths().items()}
        _require(candidate["source_bindings"] == expected_sources, "bound source set or bytes differ")
        expected_rows, expected_preambles = _build_artifact_dispositions(register)
        expected_inventory = _build_file_inventory(register, expected_rows, expected_preambles)
        _require(rows == expected_rows, "artifact dispositions differ from current source and bytes")
        _require(
            candidate["common_preamble_units"] == expected_preambles,
            "common preamble units differ from current bytes",
        )
        _require(
            candidate["file_inventory"] == expected_inventory,
            "target file set, bytes, anchors, or roles differ",
        )
        policy_approval = load_strict_json(POLICY_APPROVAL_PATH)
        _require(
            candidate["remaining_gates"] == policy_approval["remaining_gates"],
            "remaining gates differ from approved policy boundary",
        )


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def _render_markdown(candidate: dict[str, Any], candidate_file_sha256: str) -> str:
    summary = candidate["classification_summary"]
    labels = {
        DISPOSITION_BASELINE: "기준선 승인 후보",
        DISPOSITION_ACTIVE: "최초본 승인 후 계속 갱신",
        DISPOSITION_PLANNED: "Planned/NOT_RUN",
        DISPOSITION_HOLD: "Draft 보류",
        DISPOSITION_CONDITIONAL: "조건·값 확정 대기",
    }
    lines = [
        "# WalkSafe 257개 산출물 일괄 승인 후보 검토서",
        "",
        "> 상태: 승인 후보 · 아직 승인되지 않음  ",
        f"> 후보 ID: `{CANDIDATE_ID}` · 버전 `{CANDIDATE_VERSION}`  ",
        f"> 승인대상 지문: `{candidate['approval_target_sha256']}`  ",
        f"> 후보 JSON 파일 SHA-256: `{candidate_file_sha256}`",
        "",
        "## 결론",
        "",
        "이 패키지는 승인된 정책·사용자 결정만 기준으로 257개를 분류했습니다. 현행 구현은 정책 기준이 아니며, 현재 코드가 문서와 맞는지는 다음 Gap 분석에서 별도로 판정합니다.",
        "",
        "## 다섯 분류가 뜻하는 것",
        "",
        "| 분류 | 수량 | 이번 승인 효과 |",
        "|---|---:|---|",
    ]
    effects = {
        DISPOSITION_BASELINE: "사용자의 명시 승인 뒤에만 Approved/Baselined",
        DISPOSITION_ACTIVE: "schema·최초 snapshot만 승인하고 이후 계속 갱신",
        DISPOSITION_PLANNED: "승인하지 않고 Planned/NOT_RUN 유지·정정",
        DISPOSITION_HOLD: "내용·승인단위·선행관계 또는 구현·실행근거 보완 전 Draft 유지",
        DISPOSITION_CONDITIONAL: "적용성·외부값·측정값이 정해질 때까지 Draft 유지",
    }
    for disposition in DISPOSITION_ORDER:
        lines.append(f"| {labels[disposition]} | {summary[disposition]} | {effects[disposition]} |")
    lines += [
        "",
        "기존 7~12의 Planned 52개에 더해, 0~6에서 결과 없이 Draft로만 만들어진 23개를 찾아 총 75개를 Planned/NOT_RUN으로 분리했습니다. 이는 결과를 새로 만든 것이 아니라 가짜 완료를 막는 상태 정정안입니다.",
        f"또한 적용 여부가 PENDING_EVALUATION인 {summary['pending_activation_reclassified_count']}개와 자체 완료기준 미충족 또는 정본·현행 구현 결속 문제가 확인된 {summary['strict_completion_or_binding_reclassified_count']}개를 엄격 감사에서 승인 대상에서 제외했습니다.",
        f"내용 검토 뒤에도 미승인 선행 산출물이 남은 {summary['upstream_dependency_reclassified_count']}개를 추가로 보류했습니다. 따라서 이번에 실제 전환할 수 있는 폐쇄집합은 DOC-01·03·04·05 네 개뿐입니다.",
        "",
        "## 승인 전환 순서",
        "",
        "한 승인문이 아래 세 단계를 순서대로 허가합니다. 시작 직전에 대상 파일을 한 번 검증해 불변 snapshot으로 동결하고, 단계 중에는 live DOC-01·DOC-05를 수정하지 않습니다. 모든 논리 승인 사건이 성공한 뒤에만 상태와 변경이력을 한 번 반영합니다.",
        "",
        "| 단계 | 논리 승인 대상 | 먼저 성공해야 하는 산출물 | 실패 시 |",
        "|---:|---|---|---|",
    ]
    for phase in candidate["approval_sequence"]:
        predecessors = ", ".join(phase["required_predecessor_codes"]) or "없음"
        lines.append(
            f"| {phase['phase']} | {', '.join(phase['artifact_codes'])} | {predecessors} | "
            "live 상태를 반영하지 않고 후속 단계 중단 |"
        )
    identifiers = candidate["planned_post_approval_identifiers"]
    baseline = identifiers["versioned_baseline"]
    snapshots = identifiers["active_opening_snapshots"]
    lines += [
        "",
        f"예정 기준선 ID: `{baseline['baseline_id']}` · 버전 `{baseline['baseline_version']}` · 대상 DOC-03, DOC-04  ",
        f"예정 Active 최초본 ID: `{snapshots[0]['snapshot_id']}`(DOC-01), `{snapshots[1]['snapshot_id']}`(DOC-05)",
        f"예정 승인기록 ID: `{identifiers['approval_record_id']}`  ",
        f"예정 전환 전/후 manifest ID: `{identifiers['pre_transition_snapshot_manifest_id']}`, `{identifiers['post_transition_manifest_id']}`",
        "",
        "## 승인으로 바뀌지 않는 것",
        "",
        "- 현행 구현 적합성은 아직 평가하지 않았습니다.",
        "- 시험·배포·운영·인수·종료 완료를 주장하지 않습니다.",
        "- FP-035는 OPEN입니다. 동결 범위는 **일반 활동원본의 이동통신망 전송 분기**이며, 정정·재승인 전 그 분기의 구현·시험만 멈춥니다. 원본수집 승인 전체가 취소된 것은 아닙니다.",
        "- 5개 gate는 모두 NOT_RUN·미면제이며 출시는 NOT_ELIGIBLE입니다.",
        "- 사용자 승인 원문·승인 기록·실제 기준선 manifest는 아직 만들지 않았습니다.",
        "",
        "## 출시 전 남은 5개 gate",
        "",
        "아래 항목은 이 문서 승인으로 면제되지 않습니다. 각 항목의 차단 시점 전까지 실제 실행·검토 증거를 남겨야 합니다.",
        "",
        "| 해야 할 일 | 현재 | 완료 조건 | 이 항목이 막는 것 |",
        "|---|---|---|---|",
    ]
    for gate in candidate["remaining_gates"]:
        blocks = " / ".join(gate["blocks_until_complete"])
        lines.append(
            f"| {gate['title']} | {gate['status']} · 면제 없음 | {gate['completion_condition']} | "
            f"{blocks} — {gate['must_close_before']} |"
        )
    lines += [
        "",
        "## 정확한 일괄 승인문",
        "",
        "아래 문장을 그대로 승인해야만 별도 후속 단계에서 상태를 전환합니다.",
        f"승인 직전 결속된 source나 대상 파일이 하나라도 바뀌면 이 승인문과 지문은 무효입니다. 그 경우 후보를 다시 생성해야 합니다. 승인 수신 뒤에도 상태를 바꾸기 전에 `--check`를 다시 실행하고 현재 {summary['deliverable_file_count']}개 파일 bytes의 불변 snapshot을 먼저 보존합니다.",
        "",
        "```text",
        candidate["required_approval_statement"],
        "```",
        "",
        "## 유형별 분류",
        "",
        "복합 승인단위는 기본 문서 구간과 DOC-01이 연결한 구조화 정본을 함께 묶습니다. 공유 JSON은 code별 결정적 projection이 있을 때만 내용 승인 구성요소가 되며, 파생 화면이나 분리할 수 없는 공유 파일은 무결성 확인용일 뿐 내용 승인이 아닙니다.",
        "JSON 행의 gate_ids·open_issue_refs·feature_policy_ids는 DOC-01에 등록된 직접 유형 입력만 뜻하며, 산출물 본문 안의 모든 참조를 전수 집계한 값이 아닙니다.",
        "",
        "| ID | 산출물 | 현재 | 분류 | 분류 근거 | 승인 후 또는 유지 상태 | 정본·구간 | 복합 승인단위 지문 |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for row in candidate["artifact_dispositions"]:
        location = row["canonical_or_contract_path"] or "아직 파일 없음"
        if row["coverage_anchor"]:
            location += f"#{row['coverage_anchor']}"
        compound = row["approval_unit"]
        location_cell = (
            f"`{location}`<br>내용 구성요소 {len(compound['normative_components'])}개 · "
            f"무결성만 {len(compound['integrity_only_components'])}개"
        )
        unit_sha = compound["compound_sha256"]
        target = row["target_state_after_explicit_approval"]
        target_label = f"{target['lifecycle_status']} / {target['operational_status']}"
        title = row["title"].replace("|", "\\|")
        reason = row["classification_reason"].replace("|", "\\|")
        lines.append(
            f"| {row['display_code']} | {title} | {row['current_state']['lifecycle_status']} | "
            f"{labels[row['disposition']]} | {reason} | {target_label} | {location_cell} | `{unit_sha}` |"
        )
    lines += [
        "",
        "## 파일 단위 지문",
        "",
        f"총 {summary['deliverable_file_count']}개 파일의 실제 bytes를 SHA-256으로 고정했습니다. 혼합 파일은 파일 전체를 한 상태로 승인하지 않고 artifact anchor별 구간 지문으로 승인 범위를 나눕니다.",
        "",
        "| 파일 | 버전 | 역할 | SHA-256 |",
        "|---|---|---|---|",
    ]
    for entry in candidate["file_inventory"]:
        lines.append(
            f"| `{entry['path']}` | {entry['version_label']} | {entry['role']} | `{entry['file_sha256']}` |"
        )
    return "\n".join(lines) + "\n"


def _render_html(candidate: dict[str, Any], candidate_file_sha256: str) -> str:
    summary = candidate["classification_summary"]
    labels = {
        DISPOSITION_BASELINE: "기준선 후보",
        DISPOSITION_ACTIVE: "Active 최초본",
        DISPOSITION_PLANNED: "Planned/NOT_RUN",
        DISPOSITION_HOLD: "Draft 보류",
        DISPOSITION_CONDITIONAL: "조건·값 확정 대기",
    }
    cards = "".join(
        f'<article class="card"><strong>{summary[key]}</strong><span>{escape(labels[key])}</span></article>'
        for key in DISPOSITION_ORDER
    )
    options = "".join(
        f'<option value="{key}">{escape(labels[key])} · {summary[key]}개</option>'
        for key in DISPOSITION_ORDER
    )
    effects = {
        DISPOSITION_BASELINE: "명시 승인 뒤 내용 기준선으로 고정합니다. 구현 완료나 시험 통과를 뜻하지 않습니다.",
        DISPOSITION_ACTIVE: "원장 규칙과 최초 snapshot만 승인하고, 이후 실제 사건은 새 행으로 계속 기록합니다.",
        DISPOSITION_PLANNED: "아직 실행하지 않았으므로 승인하지 않고 Planned/NOT_RUN으로 둡니다.",
        DISPOSITION_HOLD: "내용·승인단위·선행관계 또는 구현·실행근거를 보완할 때까지 Draft로 보류합니다.",
        DISPOSITION_CONDITIONAL: "적용성·외부값·측정값이 정해질 때까지 Draft로 두고 승인하지 않습니다.",
    }
    effect_rows = "".join(
        f"<tr><td><span class=\"pill {key}\">{escape(labels[key])}</span></td>"
        f"<td>{summary[key]}개</td><td>{escape(effects[key])}</td></tr>"
        for key in DISPOSITION_ORDER
    )
    gate_rows = "".join(
        "<tr>"
        f"<td><strong>{escape(gate['title'])}</strong><br><small>{escape(gate['id'])}</small></td>"
        f"<td>{escape(gate['status'])}<br><small>면제 없음</small></td>"
        f"<td>{escape(gate['completion_condition'])}</td>"
        f"<td>{escape(' / '.join(gate['blocks_until_complete']))}<br><small>{escape(gate['must_close_before'])}</small></td>"
        "</tr>"
        for gate in candidate["remaining_gates"]
    )
    phase_rows = "".join(
        "<tr>"
        f"<td>{phase['phase']}</td>"
        f"<td><strong>{escape(', '.join(phase['artifact_codes']))}</strong></td>"
        f"<td>{escape(', '.join(phase['required_predecessor_codes']) or '없음')}</td>"
        "<td>live 상태를 반영하지 않고 후속 단계 중단</td>"
        "</tr>"
        for phase in candidate["approval_sequence"]
    )
    identifiers = candidate["planned_post_approval_identifiers"]
    baseline = identifiers["versioned_baseline"]
    snapshots = identifiers["active_opening_snapshots"]

    def component_html(component: dict[str, Any]) -> str:
        location = component.get("path", "문서 내부 구성요소")
        if component.get("anchor"):
            location += "#" + component["anchor"]
        digest = (
            component.get("sha256")
            or component.get("selector", {}).get("projection_sha256")
            or component.get("container_file_sha256", "")
        )
        selector = component.get("selector")
        selector_html = ""
        if selector:
            selections = "; ".join(
                f"{item['pointer']} · {item['selection_count']}건 · "
                f"selected SHA-256 {item['selected_items_canonical_sha256']}"
                for item in selector["selections"]
            )
            selector_html = (
                f"<br><small>선택 총 {selector['selection_count']}건 · "
                f"{escape(selections)}</small>"
            )
        return (
            f"<li>{escape(component['role'])} · {escape(component['kind'])}<br>"
            f"<small>{escape(location)}</small><br><code>{escape(digest)}</code>"
            f"{selector_html}</li>"
        )

    rows = []
    for row in candidate["artifact_dispositions"]:
        compound = row["approval_unit"]
        normative_html = "".join(component_html(item) for item in compound["normative_components"])
        integrity_html = "".join(component_html(item) for item in compound["integrity_only_components"])
        component_details = (
            f"<details><summary>복합 승인단위 구성</summary><p><b>내용 구성요소 "
            f"{len(compound['normative_components'])}개</b></p><ul>{normative_html or '<li>없음</li>'}</ul>"
            f"<p><b>무결성만 결속 {len(compound['integrity_only_components'])}개</b> — 이 파일 내용은 "
            f"이 행의 승인 대상이 아닙니다.</p><ul>{integrity_html or '<li>없음</li>'}</ul></details>"
        )
        unapproved_upstream_html = (
            ", ".join(row["unapproved_upstream_codes"]) or "없음"
        )
        location = row["canonical_or_contract_path"]
        if location:
            href = "../../" + location.removeprefix("docs/")
            if row["coverage_anchor"]:
                href += "#" + row["coverage_anchor"]
            location_html = f'<a href="{escape(href)}">{escape(location)}{("#" + escape(row["coverage_anchor"])) if row["coverage_anchor"] else ""}</a>'
        else:
            location_html = "아직 파일 없음"
        target = row["target_state_after_explicit_approval"]
        search = " ".join(
            [row["display_code"], row["title"], row["classification_reason"], row["category"]]
        ).lower()
        rows.append(
            f'<tr data-category="{row["category"]}" data-disposition="{row["disposition"]}" data-search="{escape(search)}">'
            f'<td><strong>{row["display_code"]}</strong><br><small>{escape(row["category"])}</small></td>'
            f'<td>{escape(row["title"])}<details><summary>분류 근거</summary><p>{escape(row["classification_reason"])}</p><p><b>미승인 선행:</b> {escape(unapproved_upstream_html)}</p><p><b>내용 검토 상태:</b> {escape(row["content_review_result"])}</p><p><b>승인 범위:</b> {escape(row["approval_scope"])}</p></details></td>'
            f'<td>{escape(row["current_state"]["lifecycle_status"])}</td>'
            f'<td><span class="pill {row["disposition"]}">{escape(labels[row["disposition"]])}</span></td>'
            f'<td>{escape(target["lifecycle_status"])}<br><small>{escape(target["operational_status"])}</small></td>'
            f'<td>{location_html}<br><small>복합 승인단위</small><br><code>{escape(compound["compound_sha256"])}</code>{component_details}</td>'
            "</tr>"
        )
    data_json = json.dumps(candidate, ensure_ascii=False).replace("</", "<\\/")
    return f"""<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="icon" href="data:,">
  <title>WalkSafe 257개 산출물 일괄 승인 후보</title>
  <style>
    :root {{ --ink:#172033; --muted:#56637a; --line:#d9e0eb; --bg:#f5f7fb; --card:#fff; --brand:#2457d6; --warn:#a74b00; --danger:#a52a2a; }}
    * {{ box-sizing:border-box; }} body {{ margin:0; color:var(--ink); background:var(--bg); font-family:system-ui,-apple-system,"Noto Sans KR",sans-serif; line-height:1.55; }}
    main {{ max-width:1500px; margin:auto; padding:1.2rem; }} h1 {{ font-size:clamp(1.55rem,3vw,2.35rem); margin:.2rem 0; }} h2 {{ margin-top:2rem; }}
    .notice,.approval {{ background:var(--card); border:1px solid var(--line); border-radius:.8rem; padding:1rem; margin:1rem 0; }}
    .notice strong {{ color:var(--danger); }} .cards {{ display:grid; grid-template-columns:repeat(5,1fr); gap:.7rem; margin:1rem 0; }}
    .card {{ background:var(--card); border:1px solid var(--line); border-radius:.7rem; padding:.9rem; }} .card strong {{ display:block; font-size:1.65rem; }} .card span,small {{ color:var(--muted); }}
    .filters {{ display:grid; grid-template-columns:2fr 1fr 1fr; gap:.7rem; background:var(--card); border:1px solid var(--line); padding:1rem; border-radius:.7rem; position:sticky; top:0; z-index:2; }}
    label {{ font-weight:700; }} input,select,textarea,button {{ width:100%; font:inherit; }} input,select {{ min-height:2.7rem; margin-top:.25rem; border:1px solid #aab5c7; border-radius:.45rem; padding:.5rem; background:#fff; }}
    textarea {{ min-height:12rem; padding:.8rem; border:1px solid #aab5c7; border-radius:.5rem; resize:vertical; background:#fbfcff; }} button {{ width:auto; min-height:2.7rem; margin-top:.6rem; padding:.55rem 1rem; border:0; border-radius:.45rem; color:#fff; background:var(--brand); cursor:pointer; }}
    .table-wrap {{ overflow:auto; background:#fff; border:1px solid var(--line); border-radius:.7rem; }} table {{ width:100%; border-collapse:collapse; min-width:1100px; }} .plain-table {{ min-width:700px; }} th,td {{ text-align:left; vertical-align:top; padding:.7rem; border-bottom:1px solid var(--line); }} th {{ position:sticky; top:5.7rem; background:#edf2fb; z-index:1; }}
    code {{ font-size:.75rem; word-break:break-all; }} a {{ color:var(--brand); }} .pill {{ display:inline-block; padding:.2rem .45rem; border-radius:999px; background:#e9eef9; white-space:nowrap; }}
    .A_BASELINE_ELIGIBLE {{ background:#d9f4e5; }} .B_ACTIVE_INITIAL_ELIGIBLE {{ background:#dcecff; }} .C_PLANNED_NOT_RUN {{ background:#eee; }} .D_DRAFT_HOLD {{ background:#ffe3dc; }} .E_CONDITIONAL_PENDING {{ background:#fff0c9; }}
    .hash {{ word-break:break-all; }} #copyStatus {{ margin-left:.6rem; color:var(--muted); }}
    @media (max-width:900px) {{ .cards {{ grid-template-columns:repeat(2,1fr); }} .filters {{ grid-template-columns:1fr; position:static; }} th {{ top:0; }} }}
  </style>
</head>
<body>
<main>
  <h1>WalkSafe 257개 산출물 일괄 승인 후보</h1>
  <p>기존 정책을 다시 묻는 문서가 아닙니다. 실제 승인 가능한 내용과 계속 갱신할 원장, 미실행 증거, 보류 항목을 분리한 최종 승인 준비서입니다.</p>
  <section class="notice"><strong>아직 승인되지 않았습니다.</strong> 이 화면을 열거나 복사 버튼을 누르는 것으로 상태가 바뀌지 않습니다. 사용자 본인이 아래 문장을 채팅으로 명시적으로 승인한 뒤에만 별도 승인 기록과 기준선을 만듭니다.</section>
  <section class="cards">{cards}</section>
  <section class="notice">
    <h2>다섯 분류가 뜻하는 것</h2>
    <div class="table-wrap"><table class="plain-table"><thead><tr><th>분류</th><th>수량</th><th>이번 승인에서 처리하는 방식</th></tr></thead><tbody>{effect_rows}</tbody></table></div>
  </section>
  <section class="notice">
    <h2>이번 감사에서 바로잡은 점</h2>
    <p>기존 Planned 52개 외에 결과 없이 Draft로만 만들어진 0~6 산출물 23개를 추가로 찾아 총 75개를 Planned/NOT_RUN으로 분리했습니다. 적용 여부가 아직 정해지지 않은 {summary['pending_activation_reclassified_count']}개와 자체 완료기준 미충족·정본 또는 현행 구현 결속 문제가 있는 {summary['strict_completion_or_binding_reclassified_count']}개도 엄격 감사에서 승인 대상에서 제외했습니다.</p>
    <p>내용 검토 뒤에도 미승인 선행 산출물이 남은 {summary['upstream_dependency_reclassified_count']}개를 추가로 보류했습니다. 이번에 실제 전환할 수 있는 폐쇄집합은 DOC-01·03·04·05 네 개뿐입니다.</p>
    <ul><li>현행 구현은 정책 판단 기준이 아닙니다.</li><li>FP-035는 OPEN입니다. 정정·재승인 전 <strong>일반 활동원본의 이동통신망 전송 분기</strong>만 구현·시험 동결하며, 원본수집 승인 전체가 취소된 것은 아닙니다.</li><li>5개 gate는 NOT_RUN·미면제, 출시는 NOT_ELIGIBLE입니다.</li></ul>
  </section>
  <section class="notice">
    <h2>승인 전환 순서</h2>
    <p>한 승인문이 아래 세 단계를 순서대로 허가합니다. 시작 직전에 대상 파일을 한 번 검증해 불변 snapshot으로 동결하고, 단계 중에는 live DOC-01·DOC-05를 수정하지 않습니다. 모든 논리 승인 사건이 성공한 뒤에만 상태와 변경이력을 한 번 반영합니다.</p>
    <div class="table-wrap"><table class="plain-table"><thead><tr><th>단계</th><th>논리 승인 대상</th><th>먼저 성공해야 하는 산출물</th><th>실패 시</th></tr></thead><tbody>{phase_rows}</tbody></table></div>
    <p><b>예정 기준선:</b> <code>{escape(baseline['baseline_id'])}</code> · 버전 {escape(baseline['baseline_version'])} · DOC-03, DOC-04<br><b>예정 Active 최초본:</b> <code>{escape(snapshots[0]['snapshot_id'])}</code> · <code>{escape(snapshots[1]['snapshot_id'])}</code><br><b>예정 승인기록:</b> <code>{escape(identifiers['approval_record_id'])}</code><br><b>예정 전환 전/후 manifest:</b> <code>{escape(identifiers['pre_transition_snapshot_manifest_id'])}</code> · <code>{escape(identifiers['post_transition_manifest_id'])}</code></p>
  </section>
  <section class="notice">
    <h2>출시 전 남은 5개 gate</h2>
    <p>이 승인으로 면제되지 않습니다. 각 차단 시점 전까지 실제 실행·검토 증거가 필요합니다.</p>
    <div class="table-wrap"><table class="plain-table"><thead><tr><th>해야 할 일</th><th>현재</th><th>완료 조건</th><th>막는 것·기한</th></tr></thead><tbody>{gate_rows}</tbody></table></div>
  </section>
  <section class="approval">
    <h2>한 번만 사용할 정확한 승인문</h2>
    <p><strong>중요:</strong> 승인 전에 결속된 source나 대상 파일이 하나라도 바뀌면 아래 승인문과 지문은 무효가 되며 후보를 다시 생성해야 합니다. 승인 수신 뒤에도 상태 변경 전에 자동 재검증하고 현재 {summary['deliverable_file_count']}개 파일 bytes를 불변 snapshot으로 먼저 보존합니다.</p>
    <label for="approvalText">채팅에 그대로 보낼 정확한 승인문</label>
    <textarea id="approvalText" readonly aria-describedby="copyStatus">{escape(candidate['required_approval_statement'])}</textarea>
    <button id="copyApproval" type="button">승인문 복사</button><span id="copyStatus" role="status" aria-live="polite"></span>
    <p><b>승인대상 지문:</b> <code class="hash">{candidate['approval_target_sha256']}</code><br><b>후보 JSON 파일 SHA-256:</b> <code class="hash">{candidate_file_sha256}</code><br><b>파일집합 지문:</b> <code class="hash">{candidate['file_set_binding_sha256']}</code><br><b>분류 지문:</b> <code class="hash">{candidate['classification_binding_sha256']}</code></p>
    <p><a href="walksafe-artifact-baseline-candidate-20260721-r001.json">기계 판독 정본 JSON 열기</a> · <a href="walksafe-artifact-baseline-candidate-review-20260721-r001.md">Markdown 검토서 열기</a></p>
  </section>
  <h2>257개 전수 분류</h2>
  <p>각 행의 복합 승인단위는 기본 문서 구간과 그 ID에 결속된 구조화 정본을 함께 묶습니다. 공유 파일은 해당 ID를 기계적으로 분리할 수 있을 때만 내용 승인에 포함하고, 파생 화면·분리 불가능한 공유 파일은 변조 확인용으로만 묶어 내용 승인에서 제외합니다.</p>
  <p>JSON 행의 gate_ids·open_issue_refs·feature_policy_ids는 DOC-01에 등록된 직접 유형 입력만 뜻하며, 산출물 본문 안의 모든 참조를 전수 집계한 값이 아닙니다.</p>
  <div class="filters">
    <label>검색<input id="search" type="search" autocomplete="off" placeholder="예: 객체 탐지, TST-20, 운영"></label>
    <label>범주<select id="category"><option value="">전체 범주</option>{''.join(f'<option>{category}</option>' for category in ['DOC','MGT','DSC','REQ','DES','DEV','TST','SEC','AIML','REL','OPS','WS','CLS'])}</select></label>
    <label>분류<select id="disposition"><option value="">전체 분류</option>{options}</select></label>
  </div>
  <p id="resultCount" role="status" aria-live="polite">257개 표시 중</p>
  <div class="table-wrap"><table><thead><tr><th>ID</th><th>산출물·근거</th><th>현재</th><th>분류</th><th>승인 후/유지</th><th>정본 구간·SHA-256</th></tr></thead><tbody id="rows">{''.join(rows)}</tbody></table></div>
</main>
<script id="candidateData" type="application/json">{data_json}</script>
<script>
(() => {{
  const search = document.getElementById('search');
  const category = document.getElementById('category');
  const disposition = document.getElementById('disposition');
  const rows = [...document.querySelectorAll('#rows tr')];
  const count = document.getElementById('resultCount');
  function applyFilters() {{
    const q = search.value.trim().toLowerCase();
    let visible = 0;
    for (const row of rows) {{
      const show = (!q || row.dataset.search.includes(q)) && (!category.value || row.dataset.category === category.value) && (!disposition.value || row.dataset.disposition === disposition.value);
      row.hidden = !show; if (show) visible += 1;
    }}
    count.textContent = `${{visible}}개 표시 중`;
  }}
  search.addEventListener('input', applyFilters);
  category.addEventListener('change', applyFilters);
  disposition.addEventListener('change', applyFilters);
  document.getElementById('copyApproval').addEventListener('click', async () => {{
    const text = document.getElementById('approvalText').value;
    try {{ await navigator.clipboard.writeText(text); document.getElementById('copyStatus').textContent = '복사했습니다.'; }}
    catch (_) {{
      const field = document.getElementById('approvalText');
      field.focus(); field.select();
      const copied = document.execCommand('copy');
      document.getElementById('copyStatus').textContent = copied ? '복사했습니다.' : '자동 복사에 실패했습니다. 선택된 문장을 직접 복사해 주세요.';
    }}
  }});
}})();
</script>
</body>
</html>
"""


def build_outputs() -> dict[Path, bytes]:
    candidate = build_candidate()
    candidate_bytes = _json_bytes(candidate)
    candidate_file_sha256 = hashlib.sha256(candidate_bytes).hexdigest()
    return {
        CANDIDATE_PATH: candidate_bytes,
        REVIEW_MD_PATH: _render_markdown(candidate, candidate_file_sha256).encode("utf-8"),
        REVIEW_HTML_PATH: _render_html(candidate, candidate_file_sha256).encode("utf-8"),
    }


def _write_or_check(outputs: dict[Path, bytes], *, check: bool) -> None:
    stale: list[str] = []
    for path, content in outputs.items():
        if check:
            if not path.is_file() or path.read_bytes() != content:
                stale.append(_relative(path))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    if stale:
        raise CandidateError("stale or missing generated outputs: " + ", ".join(stale))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify generated files without writing")
    args = parser.parse_args()
    try:
        outputs = build_outputs()
        _write_or_check(outputs, check=args.check)
        candidate = json.loads(outputs[CANDIDATE_PATH])
        summary = candidate["classification_summary"]
        action = "verified" if args.check else "generated"
        print(
            f"{action} artifact baseline candidate; total=257, "
            f"baseline={summary[DISPOSITION_BASELINE]}, active={summary[DISPOSITION_ACTIVE]}, "
            f"planned={summary[DISPOSITION_PLANNED]}, hold={summary[DISPOSITION_HOLD]}, "
            f"conditional={summary[DISPOSITION_CONDITIONAL]}, approval=NOT_APPROVED, "
            "release=NOT_ELIGIBLE"
        )
        return 0
    except (CandidateError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
