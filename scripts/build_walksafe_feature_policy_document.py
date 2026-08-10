#!/usr/bin/env python3
"""Build the post-review WalkSafe feature-by-feature policy document."""

from __future__ import annotations

import argparse
import copy
import hashlib
import html
import json
from pathlib import Path
import re
import sys
from typing import Any

try:
    from scripts import build_walksafe_feature_policy_report as previous_report
    from scripts import build_walksafe_feature_policy_resolution as resolver
except ModuleNotFoundError:  # Direct execution from the scripts directory.
    import build_walksafe_feature_policy_report as previous_report  # type: ignore[no-redef]
    import build_walksafe_feature_policy_resolution as resolver  # type: ignore[no-redef]


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
INTERVIEW_DIR = REPO_ROOT / "docs" / "control" / "decision-interview"
RESOLUTION_PATH = INTERVIEW_DIR / "walksafe-feature-policy-review-resolution.json"
RULES_PATH = INTERVIEW_DIR / "walksafe-feature-policy-document-rules.json"
PROPOSALS_PATH = INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-proposals.json"
BASE_POLICY_PATH = INTERVIEW_DIR / "walksafe-feature-policy-effective-candidate.json"
REGISTER_PATH = INTERVIEW_DIR / "walksafe-effective-decision-register.json"
TEMPLATE_PATH = INTERVIEW_DIR / "feature-policy-document-template.html"
JSON_OUTPUT_PATH = INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-draft.json"
HTML_OUTPUT_PATH = INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-draft-20260720.html"

EXPECTED_FEATURE_IDS = [f"FP-{index:03d}" for index in range(1, 55)]
EXPECTED_AREA_IDS = [f"FA-{index:02d}" for index in range(1, 19)]
EXPECTED_CONSTANT_IDS = {
    "NPC-RAW-ORIGINAL-COLLECTION",
    "NPC-DATA-LIFECYCLE",
    "NPC-SERVER-STORAGE-CAPACITY",
    "NPC-PHONE-QUEUE-CAPACITY",
    "NPC-AUTO-REPORT",
    "NPC-PERMISSION-SESSION-LIFECYCLE",
    "NPC-NAVIGATION-ROUTE-DIRECTION",
    "NPC-SINGLE-ADMIN-RECOVERY",
    "NPC-SERVER-CAPACITY-STATE-SYNC",
}
EXPECTED_GATE_IDS = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
}
ARRAY_SECTIONS = {
    "inputs",
    "outputs",
    "design_rules",
    "prohibited_behaviors",
    "verification_scenarios",
    "open_item_proposals",
}
FINALIZATION_LABELS = {
    "OWNER_APPROVAL": "제품책임자 결정으로 정책 확정",
    "ENGINEERING_REVIEW": "개발 설계와 검토 결과 필요",
    "MEASUREMENT": "실제 기기·현장 측정값 필요",
    "LEGAL_PRIVACY_REVIEW": "법률·개인정보 독립 검토 필요",
    "SECURITY_REVIEW": "보안 독립 검토 필요",
    "ACCESSIBILITY_REVIEW": "접근성 검토와 사용자 시험 필요",
    "OPERATIONS_REVIEW": "운영 가능성과 대응훈련 필요",
    "EXTERNAL_SERVICE_REVIEW": "외부 서비스 조건과 연동시험 필요",
}
INTEGRATION_LABELS = {
    "ACCEPTED_AS_PROPOSED": "제안한 정책을 그대로 반영",
    "ACCEPTED_CHANGE_PROPOSAL": "현행 변경안을 반영",
    "ACCEPTED_WITH_AMENDMENTS": "제안과 후속 확정을 함께 반영",
    "CASCADE_AMENDMENT_REQUIRED": "공통 정책 변경을 함께 반영",
    "REVISION_OVERLAY_REQUIRED": "후속 확정으로 충돌 문구를 교체",
}
IMPLEMENTATION_LABELS = {
    "UNKNOWN": "현재 자료만으로 구현 여부를 확인할 수 없음",
    "POLICY_ONLY": "정책만 있고 구현 증거가 없음",
    "PARTIALLY_IMPLEMENTED": "일부 구현 근거만 있음",
    "MEASURED_INSUFFICIENT": "구현은 있으나 필요한 측정이 부족함",
    "IMPLEMENTED_UNVERIFIED": "구현 근거는 있으나 독립 검증 전",
    "NOT_IMPLEMENTED": "현재 구현되지 않음",
}
ALIGNMENT_LABELS = {
    "REVALIDATION_REQUIRED": "정책 변경 뒤 현재 구현을 다시 확인해야 함",
    "NOT_ASSESSED_IN_THIS_STAGE": "이번 정책 작성 단계에서는 현재 구현이 정책과 맞는지 판단하지 않음",
}
PERMISSION_LABELS = {
    "REQUIRED": "반드시 필요",
    "CONDITIONAL": "해당 기능을 사용할 때 필요",
    "OPTIONAL": "사용자 선택",
}
GATE_TITLES = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT": "휴대전화 대기자료의 실제 용량 한도",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT": "서버 용량상태를 휴대전화에 전달하는 규칙",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW": "무가림 원본 수집의 출시 전 독립 검토",
    "GATE-CLOUD-COST-MEASUREMENT": "실제 클라우드 저장비 측정",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL": "관리자 휴대전화 분실 복구훈련",
}
GATE_KIND_LABELS = {
    "MEASUREMENT": "실측",
    "ENGINEERING_REVIEW": "개발 설계 검토",
    "INDEPENDENT_REVIEW": "독립 검토",
    "RECOVERY_DRILL": "복구훈련",
}


class FeaturePolicyDocumentError(ValueError):
    """Raised when the generated policy document is unsafe or incomplete."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise FeaturePolicyDocumentError(message)


def _load_json(path: Path) -> dict[str, Any]:
    value = resolver.load_strict_json(path)
    _require(isinstance(value, dict), f"{path.name} must contain a JSON object")
    return value


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _object_sha256(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _source_binding(path: Path) -> dict[str, str]:
    return {
        "path": str(path.relative_to(REPO_ROOT)),
        "sha256": _file_sha256(path),
    }


def _strip_review_prefix(value: str) -> str:
    return re.sub(
        r"^2026-07-20\s+대화\s+후속\s+확정(?:\([^)]*\))?:\s*",
        "",
        value.strip(),
    )


def _easy(value: Any) -> str:
    result = str(value)
    replacements = (
        (r"Web/PWA legacy", "과거 웹 버전"),
        (r"Web/PWA", "웹 설치형 앱"),
        (r"Android 네이티브", "안드로이드 전용"),
        (r"Android", "안드로이드"),
        (r"background", "다른 화면 전환"),
        (r"source-quality", "수집할 때 만들어진 품질의"),
        (r"on[- ]device", "휴대전화 내부"),
        (r"온디바이스", "휴대전화 내부"),
        (r"RGB", "색상 영상"),
        (r"depth", "거리 또는 깊이 값"),
        (r"confidence와", "판단 확실성과"),
        (r"confidence", "판단 확실성"),
        (r"STT가", "음성인식이"),
        (r"STT 결과", "음성인식 결과"),
        (r"STT", "음성인식"),
        (r"TTS", "음성안내"),
        (r"KWS", "호출어 인식"),
        (r"ARCore가", "휴대전화의 카메라 방향 측정 기능이"),
        (r"ARCore", "휴대전화 카메라 방향 측정 기능"),
        (r"IMU", "가속도·회전센서"),
        (r"TTC", "충돌까지 남은 예상 시간"),
        (r"TFLite", "휴대전화용 모델 파일"),
        (r"FPS", "초당 처리 화면 수"),
        (r"p50", "처리시간 중간값"),
        (r"(?<=\d)ms", "밀리초"),
        (r"TMAP", "티맵"),
        (r"TalkBack", "안드로이드 화면읽기 기능"),
        (r"SpeechRecognizer", "안드로이드 기본 음성인식 기능"),
        (r"offline voice", "인터넷 없이 동작하는 음성"),
        (r"fallback", "대체 안내"),
        (r"telemetry", "관측자료"),
        (r"dashboard", "상태판"),
        (r"runbook", "장애 대응 절차"),
        (r"rate limit", "요청 횟수 제한"),
        (r"quota", "사용 한도"),
        (r"timeout", "응답 대기시간"),
        (r"backoff", "실패할수록 늘리는 재시도 간격"),
        (r"idempotency", "같은 요청 중복 처리 방지"),
        (r"RTO", "복구시간 목표"),
        (r"RPO", "허용 자료손실 목표"),
        (r"자동 재탐색", "사용자 선택 없는 새 경로 요청"),
        (r"queue", "대기자료"),
        (r"runtime", "실행 환경"),
        (r"metadata", "부가정보"),
        (r"rollback", "이전 정상판 복구"),
        (r"MFA", "비밀번호 외 추가 인증"),
        (r"hash", "파일 지문"),
        (r"polling", "주기적인 상태 조회"),
        (r"TTL", "상태 유효시간"),
        (r"model registry", "승인 모델 목록"),
        (r"registry", "등록 목록"),
        (r"gateway", "서버 출입구"),
        (r"API key", "외부 서비스 비밀키"),
        (r"API가", "서버 연결 규칙이"),
        (r"API를", "서버 연결 규칙을"),
        (r"API와", "서버 연결 규칙과"),
        (r"API", "서버 연결 규칙"),
        (r"출시 시험 gate", "출시 전 필수 확인"),
        (r"(?<![A-Za-z])gate(?![A-Za-z])", "확인 조건"),
        (r"UAT", "사용자 현장시험"),
        (r"manifest", "구성요소 목록"),
        (r"known-good", "검증된 이전 정상판"),
        (r"readiness", "출시 준비 상태"),
        (r"pipeline", "처리 과정"),
        (r"dataset", "학습자료 묶음"),
        (r"config", "설정"),
        (r"forward-fix", "되돌리지 않고 수정판 배포"),
        (r"artifact", "배포 파일"),
        (r"SBOM", "사용한 소프트웨어 목록"),
        (r"AAB", "안드로이드 배포 파일"),
        (r"rehearsal", "모의훈련"),
        (r"E2E로", "처음부터 끝까지 전체 흐름으로"),
        (r"E2E는", "처음부터 끝까지 전체 흐름 시험은"),
        (r"E2E", "처음부터 끝까지 전체 흐름 시험"),
        (r"pre-launch", "출시 전 자동검사"),
        (r"smoke test", "기본 연결 확인"),
        (r"sandbox", "분리된 시험환경"),
        (r"schema", "자료 구조"),
        (r"migration", "자료 구조 변경"),
        (r"bucket", "파일 보관 공간"),
        (r"chunk", "나눈 자료 조각"),
        (r"reconciliation", "파일과 기록 일치 확인"),
        (r"receipt", "저장 확인서"),
        (r"upload", "서버 전송"),
        (r"download", "내려받기"),
        (r"access token", "짧게 쓰는 로그인 증명"),
        (r"refresh token", "로그인 연장용 증명"),
        (r"token", "로그인 증명"),
        (r"session", "사용 상태"),
        (r"buffer", "임시자료"),
        (r"frame", "영상 한 장"),
        (r"transcript", "음성인식 글"),
        (r"threshold", "판정 기준값"),
        (r"stale", "너무 오래된 값"),
        (r"freshness", "자료 최신성"),
        (r"metric", "측정값"),
        (r"capability", "기능 사용 가능 여부"),
        (r"matrix", "지원표"),
        (r"focus", "키보드 초점"),
        (r"heading", "제목 구조"),
        (r"semantic", "화면읽기가 이해하는 의미"),
        (r"dialog", "확인창"),
        (r"Google Cloud Storage", "구글 클라우드 파일 저장소"),
        (r"Standard Storage", "일반 보관 방식"),
        (r"Nearline Storage", "자주 꺼내지 않는 백업 방식"),
        (r"Google Play", "구글 플레이"),
        (r"서울 단일 리전\(asia-northeast3\)", "서울 서버 지역"),
        (r"GPS", "위치 정보"),
        (r"(?<![A-Za-z])class(?![A-Za-z])", "물체 종류"),
        (r"(?<![A-Za-z])UI(?![A-Za-z])", "화면"),
        (r"(?<![A-Za-z])OS(?![A-Za-z])", "운영체제"),
        (r"(?<![A-Za-z])model(?![A-Za-z])", "판단 모델"),
        (r"(?<![A-Za-z])cloud(?![A-Za-z])", "외부 서버"),
        (r"(?<![A-Za-z])storage(?![A-Za-z])", "저장소"),
        (r"(?<![A-Za-z])secret(?![A-Za-z])", "비밀값"),
        (r"(?<![A-Za-z])proxy(?![A-Za-z])", "중계 기능"),
        (r"(?<![A-Za-z])pause(?![A-Za-z])", "일시중지"),
        (r"(?<![A-Za-z])object(?![A-Za-z])", "물체"),
        (r"(?<![A-Za-z])active(?![A-Za-z])", "사용 중"),
        (r"(?<![A-Za-z])refresh(?![A-Za-z])", "갱신"),
        (r"(?<![A-Za-z])intent(?![A-Za-z])", "사용자 의도"),
        (r"(?<![A-Za-z])request(?![A-Za-z])", "요청"),
        (r"(?<![A-Za-z])logout(?![A-Za-z])", "로그아웃"),
        (r"(?<![A-Za-z])lifecycle(?![A-Za-z])", "상태 변화"),
        (r"(?<![A-Za-z])detector(?![A-Za-z])", "물체 탐지 전체 과정"),
        (r"(?<![A-Za-z])access(?![A-Za-z])", "짧게 쓰는 로그인 증명"),
        (r"(?<![A-Za-z])control(?![A-Za-z])", "조작 기능"),
        (r"(?<![A-Za-z])text(?![A-Za-z])", "글"),
        (r"(?<![A-Za-z])voice(?![A-Za-z])", "음성 종류"),
        (r"(?<![A-Za-z])order(?![A-Za-z])", "순서"),
        (r"(?<![A-Za-z])code(?![A-Za-z])", "구분값"),
        (r"(?<![A-Za-z])commit(?![A-Za-z])", "서버 저장 완료"),
        (r"(?<![A-Za-z])label(?![A-Za-z])", "이름표"),
        (r"(?<![A-Za-z])capture(?![A-Za-z])", "촬영"),
        (r"(?<![A-Za-z])license(?![A-Za-z])", "사용 허가 조건"),
        (r"test set", "독립 시험자료"),
        (r"(?<![A-Za-z])test(?![A-Za-z])", "시험"),
        (r"(?<![A-Za-z])set(?![A-Za-z])", "묶음"),
        (r"HTTPS", "암호화된 인터넷 연결"),
        (r"versioned", "버전이 기록된"),
        (r"(?<![A-Za-z])client(?![A-Za-z])", "연결 기능"),
        (r"서울 단일 서버 지역\(asia-northeast3\)", "서울 서버 지역"),
        (r"asia-northeast3", "서울 서버 지역"),
        (r"(?<![A-Za-z])store(?![A-Za-z])", "보관 기능"),
        (r"(?<![A-Za-z])case(?![A-Za-z])", "사례"),
        (r"(?<![A-Za-z])build(?![A-Za-z])", "시험용 앱"),
        (r"(?<![A-Za-z])release(?![A-Za-z])", "출시"),
        (r"screenshot", "화면 캡처"),
        (r"(?<![A-Za-z])trace(?![A-Za-z])", "추적 기록"),
        (r"(?<![A-Za-z])resumable(?![A-Za-z])", "끊긴 지점부터 이어 보내기"),
        (r"(?<![A-Za-z])resume(?![A-Za-z])", "재개"),
        (r"(?<![A-Za-z])offline(?![A-Za-z])", "인터넷 단절"),
        (r"(?<![A-Za-z])inventory(?![A-Za-z])", "자료 목록"),
        (r"(?<![A-Za-z])alert(?![A-Za-z])", "경보"),
        (r"(?<![A-Za-z])backup(?![A-Za-z])", "백업"),
        (r"(?<![A-Za-z])restore(?![A-Za-z])", "복원"),
        (r"PostgreSQL/PostGIS", "위치정보를 다루는 데이터베이스"),
        (r"retention jobs", "보존·삭제 작업"),
        (r"maintenance mode", "점검 상태"),
        (r"route identity", "경로 식별값"),
        (r"(?<![A-Za-z])instrumentation(?![A-Za-z])", "실제 앱 자동시험"),
        (r"(?<![A-Za-z])steering(?![A-Za-z])", "진행 방향 계산"),
        (r"(?<![A-Za-z])distance(?![A-Za-z])", "거리값"),
        (r"(?<![A-Za-z])history(?![A-Za-z])", "이전 기록"),
        (r"(?<![A-Za-z])mapping(?![A-Za-z])", "연결 규칙"),
        (r"(?<![A-Za-z])pending(?![A-Za-z])", "처리 대기"),
        (r"(?<![A-Za-z])workflow(?![A-Za-z])", "전체 작업 흐름"),
        (r"(?<![A-Za-z])deploy(?![A-Za-z])", "배포"),
        (r"(?<![A-Za-z])update(?![A-Za-z])", "업데이트"),
        (r"(?<![A-Za-z])version(?![A-Za-z])", "버전"),
        (r"(?<![A-Za-z])event(?![A-Za-z])", "발생 기록"),
        (r"(?<![A-Za-z])action(?![A-Za-z])", "조작"),
        (r"(?<![A-Za-z])fake(?![A-Za-z])", "가짜"),
        (r"(?<![A-Za-z])old(?![A-Za-z])", "이전"),
        (r"(?<![A-Za-z])job(?![A-Za-z])", "작업"),
        (r"(?<![A-Za-z])key(?![A-Za-z])", "식별값"),
        (r"(?<![A-Za-z])unit(?![A-Za-z])", "작은 기능 단위 시험"),
        (r"IdP", "로그인 관리 시스템"),
        (r"safe pause", "안전 일시중지"),
        (r"safe 일시중지", "안전 일시중지"),
        (r"Wi-Fi", "와이파이"),
        (r"GiB", "GB"),
        (r"리전", "서버 지역"),
        (r"검역", "학습에 써도 되는지 검사"),
        (r"가명 식별값", "사용자 계정과 직접 연결되지 않는 별도 번호"),
        (r"활성 보행 세션", "현재 진행 중인 보행"),
        (r"현재 보행 세션", "현재 보행"),
        (r"보행 세션", "보행"),
        (r"로그인 세션", "로그인 상태"),
        (r"기기 세션", "기기 로그인 상태"),
        (r"관리자 세션", "관리자 로그인 상태"),
        (r"세션 상태", "보행 상태"),
        (r"신규세션", "새 보행"),
        (r"새 세션", "새 보행"),
        (r"세션은", "사용 상태는"),
        (r"세션이", "사용 상태가"),
        (r"세션을", "사용 상태를"),
        (r"세션과", "사용 상태와"),
        (r"세션", "사용 상태"),
        (r"패스키", "기기에 저장되는 로그인 수단"),
        (r"원자적", "중간 상태 없이 한 번에"),
        (r"상태기계", "상태 변화 규칙"),
        (r"생명주기", "처리 단계"),
        (r"업로드", "서버 전송"),
        (r"데이터셋", "학습자료 묶음"),
        (r"로컬", "휴대전화 내부"),
        (r"휴대폰", "휴대전화"),
        (r"단말", "휴대전화"),
        (r"오프라인", "인터넷이 없을 때"),
        (r"릴리스", "출시"),
        (r"객체", "물체"),
        (r"(?<![A-Za-z])AI(?![A-Za-z])", "인공지능"),
        (r"(?<![A-Za-z])DB(?![A-Za-z])", "데이터베이스"),
    )
    for pattern, replacement in replacements:
        result = re.sub(pattern, replacement, result, flags=re.IGNORECASE)
    result = result.replace("기능가", "기능이")
    result = result.replace("목록와", "목록과")
    result = result.replace("결과 결과", "결과")
    result = result.replace("판단 확실성와", "판단 확실성과")
    result = result.replace("화면는", "화면은")
    result = result.replace("음성인식가", "음성인식이")
    result = result.replace("시험는", "시험은")
    result = result.replace("시험로", "시험으로")
    result = result.replace("화면와", "화면과")
    result = result.replace("규칙로", "규칙으로")
    result = result.replace("상태과", "상태와")
    result = result.replace("설정가", "설정이")
    result = result.replace("값로", "값으로")
    result = result.replace("검사이", "검사가")
    result = result.replace("방식를", "방식을")
    result = result.replace("조작 조작 기능", "여러 조작 요소")
    result = result.replace("이전 정상판 이전 정상판 복구", "이전 정상판 복구")
    result = result.replace("과거 이전 정상판 복구", "과거 복구")
    result = result.replace("수집 시 생성된 수집할 때 만들어진 품질의 압축 원본", "촬영·녹음 직후의 품질을 가능한 유지한 압축 원본")
    result = result.replace("휴대전화과", "휴대전화와")
    result = result.replace("안드로이드 앱·플랫폼", "안드로이드 앱·지원 기기")
    result = result.replace("가입·로그인·기기 로그인 상태", "가입·로그인·기기별 로그인 상태")
    result = result.replace("검증된 이전 정상판가", "검증된 이전 정상판이")
    result = result.replace("물체 저장소 파일 보관 공간", "파일 저장소의 보관 공간")
    result = result.replace("측정값 거리값", "거리 측정값")
    result = result.replace("서울 서버 지역 서버", "서울 서버 지역의 서버")
    result = result.replace("보행 화면·사용 상태 처리 단계", "보행 화면·상태 변화")
    result = result.replace("안드로이드 화면읽기 기능·접근 가능한 조작", "화면읽기·접근 가능한 조작")
    result = result.replace("인공지능 데이터·판단 모델 처리 단계", "인공지능 학습자료·모델 관리")
    result = result.replace("인공지능 데이터·모델 처리 단계", "인공지능 학습자료·모델 관리")
    result = result.replace("서버 서버 연결 규칙·데이터베이스·외부연동", "서버 연결·데이터베이스·외부 서비스")
    result = result.replace("장애·인터넷이 없을 때·복구", "장애·인터넷 단절·복구")
    result = result.replace("사용자 활동데이터", "사용자 활동자료")
    result = result.replace("복구이", "복구가")
    result = result.replace("로그인 상태을", "로그인 상태를")
    result = result.replace("로그인 수단를", "로그인 수단을")
    result = result.replace("로그인 수단와", "로그인 수단과")
    result = result.replace("로그인 수단로", "로그인 수단으로")
    result = result.replace("사용 상태을", "사용 상태를")
    result = result.replace("휴대전화은", "휴대전화는")
    result = result.replace("휴대전화이", "휴대전화가")
    result = result.replace("휴대전화을", "휴대전화를")
    result = result.replace("이 휴대전화으로", "이 휴대전화로")
    result = result.replace("수단가", "수단이")
    result = result.replace("필수 출시 확인 조건가", "필수 출시 확인 조건이")
    result = result.replace("거리 측정값가", "측정값이")
    result = result.replace("명령 글와", "명령문과")
    result = result.replace("글와", "글과")
    result = result.replace("음성와", "음성과")
    result = result.replace("큰 파일 서버 전송가", "큰 파일 서버 전송이")
    result = result.replace("별도 번호으로", "별도 번호로")
    result = result.replace("과거 Web 결과", "과거 웹판 결과")
    result = result.replace("지원 지원표", "지원 기기표")
    result = result.replace("안드로이드 화면읽기 기능인 안드로이드 화면읽기 기능만으로", "안드로이드 화면읽기 기능만으로")
    result = result.replace("안드로이드 안드로이드 화면읽기 기능", "안드로이드 화면읽기 기능")
    result = result.replace("전체 파일 서버 저장 완료과", "모든 파일의 서버 저장 완료와")
    result = result.replace("티맵 경로 서버 연결 규칙을 다시 호출한다", "티맵 서버에 새 경로를 다시 요청한다")
    result = result.replace("티맵 검색·보행 경로 서버 연결 규칙", "티맵 서버의 장소 검색·보행 경로 요청")
    result = result.replace("티맵 경로 서버 연결 규칙", "티맵 서버에 경로 요청")
    result = result.replace("티맵·신고처럼 실행 대상 서버 연결 규칙", "티맵 경로 요청·신고처럼 실행되는 기능")
    result = result.replace("명령 실행에 필요한 서버 연결 규칙 요청", "명령 실행에 필요한 서버 요청")
    result = result.replace("실행 시 안드로이드 서버 연결 규칙·카메라", "실행 시 안드로이드 버전·카메라")
    result = result.replace("암호화된 인터넷 연결 서버 출입구와 버전이 기록된 서버 연결 규칙 계약이 배포됐다", "암호화된 서버 출입구와 앱·서버가 주고받을 자료 형식 및 버전이 준비됐다")
    result = result.replace("앱이 지원 서버 연결 규칙 버전과 인증 사용 상태를 가진다", "앱이 지원되는 서버 통신 규칙 버전과 유효한 로그인 상태를 갖고 있다")
    result = result.replace("실시간 인공지능·음성·서버 연결 규칙 연결 기능", "실시간 인공지능·음성·서버 통신 기능")
    result = result.replace("서버 연결 규칙 계약", "앱과 서버가 주고받을 자료 형식과 버전")
    result = result.replace("지원 서버 연결 규칙 버전", "지원되는 서버 통신 규칙 버전")
    result = result.replace("서버 연결 규칙 버전", "서버 통신 규칙 버전")
    result = result.replace("학습 학습자료 묶음", "학습자료 묶음")
    result = result.replace("식별값 파일 지문", "식별값·파일 지문")
    result = result.replace("회전센서 센서", "회전센서")
    result = result.replace("로그인 로그인 증명", "로그인 증명")
    result = result.replace("임시 임시자료", "임시자료")
    result = result.replace("서버 서버 전송", "서버 전송")
    result = result.replace("독립 독립 시험자료", "독립 시험자료")
    result = result.replace("배포 파일 파일 지문", "배포 파일 지문")
    result = result.replace("작은 기능·기능 묶음", "개별 기능·여러 기능 묶음")
    result = result.replace("제한권한", "제한된 권한의")
    result = result.replace("처리 대기으로", "처리 대기로")
    result = result.replace("고아 파일", "연결 기록이 없는 파일")
    result = result.replace("고아파일", "연결 기록이 없는 파일")
    result = result.replace("실행 환경 파일 지문가 구성요소 목록에", "실행 환경·파일 지문이 구성요소 목록에")
    result = result.replace("원본 수신구역", "서버가 처음 받은 원본 보관구역")
    result = result.replace("수신구역 사본", "서버가 처음 받은 원본 사본")
    result = result.replace("학습에 써도 되는지 검사 실패구역", "검사에서 탈락한 원본 보관구역")
    result = result.replace("검사 실패구역", "검사에서 탈락한 원본 보관구역")
    result = result.replace("학습 학습자료 묶음으로 승격", "학습자료 묶음으로 승인")
    result = result.replace("학습용으로 승격", "학습자료로 승인")
    result = result.replace("승격", "정식 사용 승인")
    result = result.replace("가용성", "사용 가능 여부")
    result = result.replace("무결성과 준비 상태", "파일이 손상되거나 바뀌지 않았는지와 실행 준비 상태")
    result = result.replace("무결성", "파일 손상·변경 여부")
    result = result.replace("파일 지문", "파일이 같은지 확인하는 값")
    result = result.replace("승인된 검증된 이전 정상판", "승인된 이전 정상판")
    result = result.replace("기존 보행기기를 인터넷이 없을 때으로 만든 뒤", "기존 보행기기를 인터넷이 없는 상태로 만든 뒤")
    result = result.replace("자동 사용 중로 가지 않고", "자동으로 사용 중 상태가 되지 않고")
    result = result.replace("화면 나이", "카메라 화면이 얼마나 오래됐는지")
    result = result.replace("인터넷이 없을 때이거나 상태가 오래됐으면", "인터넷이 없거나 상태 정보가 오래됐으면")
    result = result.replace("학습자료 묶음으로 정식 사용 승인", "학습자료 묶음으로 승인")
    result = result.replace("서버가 처음 받은 원본 보관구역 사본", "서버가 처음 받은 원본 사본")
    result = result.replace("법적 보존·신고·학습 정식 사용 승인 데이터", "법적 보존·신고·학습자료 승인 대상")
    result = result.replace("현재 기기 사용 상태 ID", "현재 기기 사용 상태 번호")
    result = result.replace("자료 ID", "자료 번호")
    result = result.replace("요청 ID", "요청 번호")
    return result


def _e(value: Any) -> str:
    return html.escape(_easy(value), quote=True)


def _raw_e(value: Any) -> str:
    return html.escape(str(value), quote=True)


def _list_html(values: list[Any], empty: str = "이 기능에 별도로 정한 내용이 없습니다.") -> str:
    if not values:
        return f'<p class="not-applicable">{_e(empty)}</p>'
    return "<ul>" + "".join(f"<li>{_e(value)}</li>" for value in values) + "</ul>"


def _render_review_controls(unit_id: str, title: str) -> str:
    slug = unit_id.lower()
    return f"""
      <section class="review-controls screen-only" aria-labelledby="review-title-{slug}">
        <fieldset>
          <legend id="review-title-{slug}">{_e(title)} 검토 결과</legend>
          <label><input type="radio" id="review-{slug}-confirm" name="review-{unit_id}" value="confirm"> 이대로 확정</label>
          <label><input type="radio" id="review-{slug}-revise" name="review-{unit_id}" value="revise"> 수정 필요</label>
          <label><input type="radio" id="review-{slug}-hold" name="review-{unit_id}" value="hold"> 보류</label>
        </fieldset>
        <label for="review-note-{slug}">고칠 내용 또는 보류 이유</label>
        <textarea id="review-note-{slug}" data-review-note rows="3" placeholder="수정할 문장과 원하는 내용을 적어 주세요."></textarea>
      </section>
    """


def _feature_links(feature_ids: list[str], feature_names: dict[str, str]) -> str:
    if not feature_ids:
        return '<span class="not-applicable">직접 연결 기능 없음</span>'
    return ", ".join(
        f'<a href="#{feature_id.lower()}">{feature_id} · {_e(feature_names[feature_id])}</a>'
        for feature_id in feature_ids
    )


def _end_to_end_flows() -> list[dict[str, Any]]:
    return [
        {
            "id": "FLOW-01",
            "title": "프로젝트 결정과 서비스 원칙",
            "summary": "목표·안전 원칙·한 명의 최종 책임자를 정하고 제품·화면·관리 기능의 기준으로 넘긴다.",
            "feature_ids": ["FP-001", "FP-002", "FP-003"],
        },
        {
            "id": "FLOW-02",
            "title": "제품 화면과 접근 가능한 조작",
            "summary": "사용자·관리자 화면, 구조, 오류 처리와 접근성 시험을 하나의 사용 흐름으로 관리한다.",
            "feature_ids": ["FP-004", "FP-005", "FP-006", "FP-007", "FP-008", "FP-009", "FP-028", "FP-029", "FP-030"],
        },
        {
            "id": "FLOW-03",
            "title": "가입·로그인·동의·권한 준비",
            "summary": "가입부터 앱 시작, 로그인, 원본 동의, 운영체제 권한 확인, 종료·철회까지 서로 다른 상태로 관리한다.",
            "feature_ids": ["FP-010", "FP-011", "FP-012", "FP-013", "FP-014", "FP-015", "FP-016", "FP-017", "FP-018"],
        },
        {
            "id": "FLOW-04",
            "title": "카메라 탐지부터 행동 안내",
            "summary": "영상 사전검사, 물체 후보 관측, 거리 근거 사후검사, 위험·행동 결정, 음성·진동 안내의 책임을 분리한다.",
            "feature_ids": ["FP-019", "FP-020", "FP-021", "FP-027"],
        },
        {
            "id": "FLOW-05",
            "title": "목적지·경로·이탈 처리",
            "summary": "티맵 큰 경로와 점자블록 보조를 결합하되 이탈 뒤 새 경로 요청은 사용자가 결정한다.",
            "feature_ids": ["FP-022", "FP-023", "FP-024", "FP-040", "FP-042", "FP-043", "FP-044"],
        },
        {
            "id": "FLOW-06",
            "title": "음성 조작과 음성·진동 안내",
            "summary": "호출어·음성 명령의 입력 책임과 위험·길안내 결과를 말하고 진동하는 책임을 나눈다.",
            "feature_ids": ["FP-025", "FP-027"],
        },
        {
            "id": "FLOW-07",
            "title": "손상 점자블록 신고",
            "summary": "손상 후보 생성, 자동신고 대기, 중복 없는 전송, 관리자 검수와 기관 전달을 분리한다.",
            "feature_ids": ["FP-026", "FP-031", "FP-032", "FP-033"],
        },
        {
            "id": "FLOW-08",
            "title": "원본 수집·전송·삭제와 용량",
            "summary": "활성 보행 원본이 휴대전화와 서버를 거쳐 학습자료가 되거나 정해진 기한에 삭제되는 전 과정을 관리한다.",
            "feature_ids": ["FP-034", "FP-035", "FP-036", "FP-041", "FP-045", "FP-046", "FP-053"],
        },
        {
            "id": "FLOW-09",
            "title": "모델 학습·평가·교체",
            "summary": "수집자료 검역, 서버 재학습, 독립평가, 모델 등록, 앱 결속과 이전 정상판 복구를 관리한다.",
            "feature_ids": ["FP-037", "FP-038", "FP-039", "FP-049", "FP-050", "FP-051"],
        },
        {
            "id": "FLOW-10",
            "title": "보안·운영·복구·종료",
            "summary": "한 명의 관리자가 권한을 통제하되 독립 검토, 암호화, 복구훈련, 안전한 배포와 종료 절차를 유지한다.",
            "feature_ids": ["FP-003", "FP-008", "FP-047", "FP-048", "FP-051", "FP-052", "FP-053", "FP-054"],
        },
        {
            "id": "FLOW-11",
            "title": "실기기·접근성·현장 검증",
            "summary": "기술 시험을 통과한 뒤 대상 사용자와 안전요원이 참여하는 통제 시험으로 넓혀 출시 근거를 만든다.",
            "feature_ids": ["FP-002", "FP-007", "FP-009", "FP-028", "FP-029", "FP-030", "FP-045", "FP-049", "FP-050"],
        },
    ]


def _present_constant(value: dict[str, Any], affected_feature_ids: list[str]) -> dict[str, Any]:
    constant_id = value["id"]
    if constant_id == "NPC-RAW-ORIGINAL-COLLECTION":
        title = "활성 보행 중 원본 수집"
        summary = "동의한 사용자가 보행을 시작하면 영상·음성·정확 위치·센서·탐지·경로·신고·성능 원본을 수집하며 주변인의 얼굴·번호판·목소리도 가리지 않는다."
        rules = [
            "수집은 활성 보행 시작과 함께 시작하고 일시중지·종료·의존 권한 철회·전체 삭제 요청·용량상 새 수집 보류 때 해당 범위를 멈춘다.",
            "원본은 수집할 때 만들어진 압축 원본으로 정의한다.",
            "카메라 영상과 색상·거리·확신도, 음성과 음성인식 결과, 정확 위치·속도·방향, 티맵 검색·목적지·경로, 가속도·회전·보폭·걸음 수, 탐지·거리·위험·모델·설정, 자동신고, 성능·오류·전송 상태를 포함한다.",
            "처음 동의받을 때 수집 항목, 목적, 전송 시점, 보존기간, 삭제방법을 함께 설명한다.",
            "원본 수집에 동의하지 않으면 원본을 수집하지 않는다.",
        ]
    elif constant_id == "NPC-DATA-LIFECYCLE":
        title = "보존기간과 삭제기한"
        summary = "휴대전화·서버·학습자료·백업마다 서로 다른 유한 보존기간을 적용하고 삭제 요청도 저장 위치별 기한 안에 처리한다."
        rules = [
            "서버 수신이 확인된 휴대전화 사본은 24시간 안에 삭제하고, 아직 보내지 못한 휴대전화 원본은 최대 30일 보관한다.",
            "서버 수신·검역 원본은 14일, 일반·자동신고 원본은 180일 보관한다.",
            "학습자료로 승인된 원본·라벨·고정 검증자료는 승인 뒤 3년 보관하고 운영 백업은 35일 순환한다.",
            "안내용 휴대전화 경로 사본은 보행 종료와 24시간 중 먼저 도달한 때 삭제한다.",
            "전체 삭제 요청 시 새 수집·전송은 즉시 중단하고 휴대전화 24시간, 서버 원본·검역·복사본 7일, 학습자료·라벨·가공본 30일, 백업 최대 35일 안에 처리한다.",
            "원본이 없는 삭제 확인 기록은 식별값의 파일 지문·처리시각·결과만 3년 보관한다.",
            "미전송 휴대전화 원본은 확신도와 관계없이 30일이 절대 상한이다.",
        ]
    elif constant_id == "NPC-SERVER-STORAGE-CAPACITY":
        title = "서버 원본·백업 용량과 저장비"
        summary = "서울 리전에 주 원본 300 GiB, 백업 300 GiB를 두고 저장비 월 30,000원 안에서 단계별로 새 수집만 제한한다."
        rules = [
            "주 원본은 표준 저장소 300 GiB, 35일 백업은 저빈도 저장소 300 GiB이며 물리 합계 상한은 600 GiB다.",
            "월 저장비 상한은 부가세·저장 요청·복원 여유를 포함해 30,000원으로 정한다.",
            "주 원본 70%(210 GiB)는 관리자 경고, 85%(255 GiB)는 신규 현장시험 참여자 추가 중단, 95%(285 GiB)는 만료자료 정리 후 새 원본수집 보류, 100%(300 GiB)는 새 학습자료·자동신고 후보 생성을 조용히 보류한다.",
            "만료되지 않은 암호화 자료와 실시간 객체 탐지·길안내는 유지하고 비용 때문에 원본을 일찍 삭제하지 않는다.",
            "공간이 생기면 자료 수집·전송 흐름만 자동 재개하며 보행 자체는 자동 재개하지 않는다.",
            "저장비 계산 가정은 표준 $0.023/GiB-월, 백업 $0.016/GiB-월, 환율 1달러=1,500원이며 저장비 계산값은 월 17,550원이다.",
            "현재 단계에서는 실제 클라우드 저장소를 만들거나 배포하지 않는다.",
        ]
    elif constant_id == "NPC-PHONE-QUEUE-CAPACITY":
        title = "휴대전화 대기자료 용량"
        summary = "휴대전화 용량은 서버 기준과 분리하며, 실제 바이트 상한은 기기별 실측 뒤 정한다. 자료 흐름만 보류되면 사용자에게 알리지 않는다."
        rules = [
            "휴대전화에는 서버의 300 GiB와 70·85·95·100% 기준을 적용하지 않는다.",
            "정리 순서는 다시 만들 수 있는 임시자료, 서버 수신 확인 사본, 만료된 선택 학습자료, 만료된 낮은 확신도의 미전송 신고 후보 순이다.",
            "그래도 부족하면 새 학습자료와 자동신고 후보 생성을 보류한다.",
            "보존기간 안의 미전송 신고와 동의·철회·삭제·보안기록은 우선 보호한다.",
            "자료 생성 보류는 관리자 기록과 운영 지표에만 남긴다. 실시간 안전기능까지 믿을 수 없을 때에만 이유와 안전정지를 사용자에게 알린다.",
        ]
    elif constant_id == "NPC-AUTO-REPORT":
        title = "손상 점자블록 자동신고"
        summary = "처음 한 번 명확히 동의받은 뒤 후보마다 알리거나 취소받지 않고, 보행 종료 후 허용된 망에서 중복 없이 전송한다."
        rules = [
            "후보별 음성·진동·푸시 알림과 개별 취소는 제공하지 않는다. 사용자가 직접 음성으로 요청한 수동 신고 결과 안내는 유지한다.",
            "설정에서 자동신고 전체를 끌 수 있다. 끄면 새 후보를 즉시 막고 미전송 후보를 24시간 안에 삭제하며 서버 원본은 7일 안에 삭제한다.",
            "이동통신망은 별도 선택한 경우에만 사용하고, 와이파이가 없으면 암호화해 다음 와이파이 연결까지 기다리며 장기 미전송 알림은 보내지 않는다.",
            "자동신고는 보행 종료 뒤 전송하며 같은 신고를 다시 보내도 중복되지 않도록 고정 신고번호를 사용한다.",
            "응답을 알 수 없으면 같은 번호로 서버 상태를 먼저 확인하고, 서버의 전체 파일 저장과 파일 지문 일치 확인서를 받은 뒤 완료로 처리한다.",
            "자동신고가 보행안전을 보장한다고 설명하지 않는다.",
        ]
    elif constant_id == "NPC-PERMISSION-SESSION-LIFECYCLE":
        title = "권한·로그인·동의 상태 분리"
        summary = "운영체제 권한, 로그인, 원본 동의, 자동신고, 이동통신망 선택을 서로 다른 상태로 관리한다."
        rules = [
            "이미 허용한 카메라·위치·마이크 권한을 반복해서 묻지 않고 실제 사용 직전에 현재 상태를 조용히 확인한다.",
            "권한이 철회되면 그 권한에 의존하는 기능만 멈춘다. 남은 기능만으로 안전하지 않을 때에만 이유를 알리고 전체 보행기능을 안전정지한다.",
            "로그인은 명시적 로그아웃·인증 만료·보안사고 전까지 유지한다.",
            "로그아웃은 운영체제 권한이나 서버 동의·자료를 없애지 않는다. 앱 삭제는 휴대전화 로그인 상태와 휴대전화 자료만 지운다.",
            "앱 밖에서도 서버 자료 열람·철회·삭제를 요청할 수 있는 경로를 둔다.",
            "재부팅·비정상 종료·운영체제 강제 종료 뒤 이전 보행과 경로를 자동 재개하지 않는다.",
        ]
    elif constant_id == "NPC-NAVIGATION-ROUTE-DIRECTION":
        title = "경로·진행방향·보폭 책임 분리"
        summary = "실제 이동방향은 위치 정보, 가야 할 방향은 저장된 티맵 경로, 카메라 방향은 회전센서와 카메라 방향 계산 기능이 담당하며 보폭은 진행량 보조에만 쓴다."
        rules = [
            "개인 보폭이 계산되기 전에는 일반 평균 보폭을 쓰고 신뢰할 수 있는 위치 이동거리와 걸음 수가 쌓이면 개인 보폭으로 바꾼다.",
            "남은 거리의 주 기준은 위치 정보와 저장 경로다. 보폭은 보조 입력일 뿐 위치나 방향을 대신 판단하지 않는다.",
            "도착은 위치 정보·경로 끝·보폭 진행량을 함께 확인한 뒤 사용자 확인으로 확정한다.",
            "경로 이탈은 위치 정확도·저장 경로와의 거리·연속 관측으로 판단한다. 의심 단계에서는 오래된 회전안내를 멈춘다.",
            "이탈 확정 뒤 사용자가 새 경로 요청·위치 다시 확인·길안내 종료 중 하나를 고르며 새 경로를 선택했을 때만 티맵을 다시 호출한다.",
            "위치 정보를 믿을 수 없으면 보폭으로 대신하지 않고 방향 안내를 일시중지한다.",
        ]
    elif constant_id == "NPC-SINGLE-ADMIN-RECOVERY":
        title = "한 명의 관리자와 계정 복구"
        summary = "관리자와 최종 승인자는 한 명으로 유지하되 휴대전화 밖의 복구수단, 원격 세션 폐기와 고위험 작업 동결로 계정 분실에 대비한다."
        rules = [
            "관리자 계정은 비밀번호 외 추가 인증 또는 패스키를 사용하고 공용·숨은 우회 비밀번호를 두지 않는다.",
            "복구코드나 보안키는 관리자 휴대전화 밖에 보관한다.",
            "휴대전화 분실 시 별도 관리 경로에서 그 기기의 로그인 상태를 폐기한다.",
            "서버키·앱 서명키·관리자 복구자료는 서로 분리해 암호화 백업한다.",
            "접근을 잃으면 복구할 때까지 출시·권한 변경·데이터 삭제 같은 고위험 작업을 동결한다.",
            "실제 사용자시험이나 배포 전에 휴대전화 분실 복구훈련을 한 번 수행한다.",
            "모델·법률·보안·접근성의 독립 검토는 관리자 한 명이라는 이유로 제거하지 않는다.",
        ]
    elif constant_id == "NPC-SERVER-CAPACITY-STATE-SYNC":
        title = "서버 용량상태를 휴대전화에 전달"
        summary = "휴대전화는 서버 상태의 버전과 확인시각을 함께 받고, 조회주기와 유효시간은 실제 지연·오프라인 시험 뒤 정한다."
        rules = [
            "앱을 시작할 때 온라인이면 조회하고 네트워크가 다시 연결될 때도 최신 상태를 받는다.",
            "온라인 조회주기와 상태 유효시간은 아직 정하지 않았으며 개발 검토와 시험으로 확정한다.",
            "오프라인이거나 상태가 오래됐으면 휴대전화 자체 대기자료 상한을 적용하고 자료를 암호화해 보관한다.",
            "서버가 용량 때문에 거부하면 휴대전화에 최대 30일 보관하고 공간이 생기면 다시 보내며, 30일 만료 시 내부 기록을 남기고 삭제한다.",
        ]
    else:  # pragma: no cover - protected by expected constant IDs.
        raise FeaturePolicyDocumentError(f"unknown normalized constant: {constant_id}")
    return {
        "id": constant_id,
        "title": title,
        "summary": summary,
        "rules": rules,
        "affected_feature_ids": affected_feature_ids,
        "source_review_ids": value["source_review_ids"],
    }


def _suppression_map(
    rules: dict[str, Any],
    proposals_by_id: dict[str, dict[str, Any]],
    resolution: dict[str, Any],
) -> dict[tuple[str, str, int], str]:
    replacements = {item["id"] for item in resolution["known_conflict_replacements"]}
    review_ids = {item["review_id"] for item in resolution["review_records"]}
    result: dict[tuple[str, str, int], str] = {}
    entries = rules.get("clause_suppressions")
    _require(isinstance(entries, list) and entries, "document rules need clause suppressions")
    for entry_index, entry in enumerate(entries):
        _require(isinstance(entry, dict), f"suppression {entry_index} must be an object")
        _require(
            set(entry) == {"feature_id", "section", "indexes", "superseded_by"},
            f"suppression {entry_index} fields differ",
        )
        feature_id = entry["feature_id"]
        section = entry["section"]
        indexes = entry["indexes"]
        superseded_by = entry["superseded_by"]
        _require(feature_id in proposals_by_id, f"unknown suppression feature: {feature_id}")
        _require(section in ARRAY_SECTIONS, f"unknown suppression section: {section}")
        _require(isinstance(indexes, list) and indexes, f"suppression indexes missing: {feature_id}.{section}")
        _require(superseded_by in replacements | review_ids, f"unknown superseding policy: {superseded_by}")
        values = proposals_by_id[feature_id][section]
        for index in indexes:
            _require(isinstance(index, int) and 0 <= index < len(values), f"invalid suppression index: {feature_id}.{section}[{index}]")
            key = (feature_id, section, index)
            _require(key not in result, f"duplicate suppression: {feature_id}.{section}[{index}]")
            result[key] = superseded_by
    return result


def _replacement_map(
    rules: dict[str, Any],
    proposals_by_id: dict[str, dict[str, Any]],
    resolution: dict[str, Any],
) -> dict[tuple[str, str, int], dict[str, str]]:
    superseding_ids = {
        *[item["id"] for item in resolution["known_conflict_replacements"]],
        *[item["review_id"] for item in resolution["review_records"]],
    }
    result: dict[tuple[str, str, int], dict[str, str]] = {}
    for entry_index, entry in enumerate(rules.get("clause_replacements", [])):
        _require(
            isinstance(entry, dict)
            and set(entry) == {"feature_id", "section", "index", "replacement", "superseded_by"},
            f"replacement {entry_index} fields differ",
        )
        feature_id = entry["feature_id"]
        section = entry["section"]
        index = entry["index"]
        _require(feature_id in proposals_by_id, f"unknown replacement feature: {feature_id}")
        _require(section in ARRAY_SECTIONS, f"unknown replacement section: {section}")
        _require(isinstance(index, int), f"replacement index is invalid: {feature_id}.{section}")
        _require(0 <= index < len(proposals_by_id[feature_id][section]), f"replacement index is out of range: {feature_id}.{section}[{index}]")
        _require(isinstance(entry["replacement"], str) and entry["replacement"].strip(), f"replacement text is empty: {feature_id}.{section}[{index}]")
        _require(entry["superseded_by"] in superseding_ids, f"unknown replacement authority: {entry['superseded_by']}")
        key = (feature_id, section, index)
        _require(key not in result, f"duplicate replacement: {feature_id}.{section}[{index}]")
        result[key] = {
            "replacement": entry["replacement"].strip(),
            "superseded_by": entry["superseded_by"],
        }
    return result


def _clause_selection_digest(
    rules: dict[str, Any], proposals_by_id: dict[str, dict[str, Any]]
) -> str:
    selected: list[dict[str, Any]] = []
    for entry in rules.get("clause_suppressions", []):
        for index in entry["indexes"]:
            selected.append(
                {
                    "action": "SUPPRESS",
                    "feature_id": entry["feature_id"],
                    "section": entry["section"],
                    "index": index,
                    "source_value": proposals_by_id[entry["feature_id"]][entry["section"]][index],
                }
            )
    for entry in rules.get("clause_replacements", []):
        selected.append(
            {
                "action": "REPLACE",
                "feature_id": entry["feature_id"],
                "section": entry["section"],
                "index": entry["index"],
                "source_value": proposals_by_id[entry["feature_id"]][entry["section"]][entry["index"]],
            }
        )
    selected.sort(key=lambda item: (item["feature_id"], item["section"], item["index"], item["action"]))
    return _object_sha256(selected)


def _active_array(
    feature_id: str,
    section: str,
    values: list[Any],
    suppressions: dict[tuple[str, str, int], str],
    replacements: dict[tuple[str, str, int], dict[str, str]],
) -> tuple[list[Any], list[dict[str, Any]], list[dict[str, Any]]]:
    active: list[Any] = []
    removed: list[dict[str, Any]] = []
    replaced: list[dict[str, Any]] = []
    for index, value in enumerate(values):
        key = (feature_id, section, index)
        superseded_by = suppressions.get(key)
        if superseded_by:
            removed.append({"section": section, "source_index": index, "superseded_by": superseded_by})
        elif key in replacements:
            replacement = replacements[key]
            active.append(replacement["replacement"])
            replaced.append(
                {
                    "section": section,
                    "source_index": index,
                    "source_text_sha256": _object_sha256(value),
                    "superseded_by": replacement["superseded_by"],
                }
            )
        else:
            active.append(copy.deepcopy(value))
    return active, removed, replaced


def _normalize_accepted_policy(value: str) -> str:
    result = _strip_review_prefix(value).strip()
    result = re.sub(r"^재승인될 경우\s+", "", result)
    result = result.replace("이 보고서의 권고 기준은", "현재 적용할 정책은")
    result = result.replace("이 보고서에서 권고하는 기준은", "현재 적용할 정책은")
    result = re.sub(r"^현재 적용할 정책은\s+", "", result)
    return result


def _effective_review_policy(review: dict[str, Any], proposal_text: str) -> tuple[str, str | None]:
    note = review["reviewer_note"].strip()
    if review["merge_action"] == "SUPERSEDE_CONFLICTING_CLAUSES_AND_APPLY_NOTE":
        return _normalize_accepted_policy(note), None
    current = _normalize_accepted_policy(proposal_text)
    if note and review["is_dated_correction"]:
        current = f"{current} {_normalize_accepted_policy(note)}"
        return current, None
    return current, note or None


def _reviewed_cross_feature_policies(
    proposals: dict[str, Any], resolution: dict[str, Any], rules: dict[str, Any]
) -> list[dict[str, Any]]:
    source_by_id = {
        item["id"]: {
            "id": item["id"],
            "scope": "GLOBAL",
            "title": item["title"],
            "question": item["title"],
            "proposal_text": item["policy"],
            "reason": item["why"],
            "affected_feature_ids": item["feature_ids"],
        }
        for item in proposals["global_policy_proposals"]
    }
    source_by_id.update(
        {
            item["proposal_id"]: {
                "id": item["proposal_id"],
                "scope": "SHARED",
                "title": item["decision_title"],
                "question": item["easy_question"],
                "proposal_text": item["recommended_answer"],
                "reason": item["why"],
                "affected_feature_ids": item["affected_feature_ids"],
                "completion_type": item["finalized_by"],
                "interim_rule": item["until_finalized"],
            }
            for item in proposals["shared_decision_proposals"]
        }
    )
    reviews = {item["review_id"]: item for item in resolution["review_records"]}
    expected_ids = [f"GP-{index:02d}" for index in range(1, 8)] + [f"SP-{index:02d}" for index in range(1, 16)]
    _require(set(source_by_id) == set(expected_ids), "global/shared proposal coverage differs")
    title_overrides = rules.get("cross_policy_title_overrides", {})
    _require(isinstance(title_overrides, dict), "cross policy title overrides must be an object")
    _require(set(title_overrides) <= set(expected_ids), "unknown cross policy title override")
    _require(
        all(isinstance(value, str) and value.strip() for value in title_overrides.values()),
        "cross policy title overrides must be non-empty strings",
    )
    result = []
    for review_id in expected_ids:
        source = source_by_id[review_id]
        review = reviews[review_id]
        current_policy, followup_note = _effective_review_policy(review, source["proposal_text"])
        item = {
            "id": f"{review_id}-POLICY-001",
            "source_review_id": review_id,
            "scope": source["scope"],
            "title": title_overrides.get(review_id, source["title"]),
            "question": source["question"],
            "current_policy": current_policy,
            "reason": source["reason"],
            "affected_feature_ids": source["affected_feature_ids"],
            "review_decision": review["decision"],
            "merge_action": review["merge_action"],
            "followup_note": followup_note,
        }
        if source["scope"] == "SHARED":
            item["completion_type"] = source["completion_type"]
            item["completion_label"] = FINALIZATION_LABELS[source["completion_type"]]
            item["interim_rule"] = source["interim_rule"]
        result.append(item)
    return result


def _feature_overrides(rules: dict[str, Any], feature_id: str) -> dict[str, Any]:
    overrides = rules.get("feature_content_overrides", {})
    _require(isinstance(overrides, dict), "feature content overrides must be an object")
    value = overrides.get(feature_id, {})
    _require(isinstance(value, dict), f"feature override must be an object: {feature_id}")
    allowed = {
        "effective_policy_summary",
        "plain_summary",
        "start_conditions",
        "failure_behavior",
        "user_guidance",
        "execution_boundary",
    }
    _require(set(value) <= allowed, f"unknown feature override field: {feature_id}")
    return value


def _current_guidance(
    feature_id: str,
    base: dict[str, Any],
    constant_ids: list[str],
    override: dict[str, Any],
) -> dict[str, list[str]]:
    if "user_guidance" in override:
        return copy.deepcopy(override["user_guidance"])
    guidance = copy.deepcopy(base["user_guidance"])
    if "NPC-AUTO-REPORT" in constant_ids:
        guidance["screen"] = list(dict.fromkeys([
            *guidance.get("screen", []),
            "자동신고 후보·전송·용량 보류는 일반 사용자에게 개별 표시하지 않는다. 설정·권리요청 화면에서는 자동신고 켜짐 여부와 삭제 요청 결과를 확인할 수 있다.",
        ]))
        guidance["speech"] = list(dict.fromkeys([
            *guidance.get("speech", []),
            "사용자가 직접 음성으로 요청한 수동 신고 결과나 실시간 안전기능 정지를 제외하면 자동신고 상태를 말하지 않는다.",
        ]))
    elif "NPC-PHONE-QUEUE-CAPACITY" in constant_ids:
        guidance["screen"] = list(dict.fromkeys([
            *guidance.get("screen", []),
            "자료 생성·전송 보류는 관리자 기록에만 남긴다. 실시간 안전기능을 믿을 수 없어 중지할 때만 사용자에게 이유를 표시한다.",
        ]))
        guidance["speech"] = list(dict.fromkeys([
            *guidance.get("speech", []),
            "자료 용량만 부족한 때에는 알리지 않고, 안전기능 자체가 중지될 때만 이유와 다음 행동을 말한다.",
        ]))
    return guidance


def _current_failure_behavior(
    base: dict[str, Any], constant_ids: list[str], override: dict[str, Any]
) -> list[str]:
    if "failure_behavior" in override:
        return copy.deepcopy(override["failure_behavior"])
    result = copy.deepcopy(base["failure_behavior"])
    if "NPC-PERMISSION-SESSION-LIFECYCLE" in constant_ids:
        result.append("권한이 없거나 철회되면 그 권한이 필요한 기능만 멈춘다. 남은 기능만으로 안전하지 않을 때에만 이유를 알리고 보행을 안전정지한다.")
    if "NPC-NAVIGATION-ROUTE-DIRECTION" in constant_ids:
        result.append("위치를 믿을 수 없으면 보폭으로 대신 판단하지 않고 방향 안내를 멈춘다. 이탈 확정 뒤 사용자가 선택하기 전에는 새 경로를 자동 요청하지 않는다.")
    if "NPC-AUTO-REPORT" in constant_ids:
        result.append("자동신고 후보 생성·전송만 실패하거나 보류되면 암호화 자료와 내부 기록을 유지하고 사용자에게 개별 알림을 보내지 않는다.")
    if "NPC-PHONE-QUEUE-CAPACITY" in constant_ids:
        result.append("자료 대기공간만 부족하면 정해진 순서로 만료자료를 정리하고 새 자료 생성을 조용히 보류한다. 실시간 안전기능은 계속 유지한다.")
    return list(dict.fromkeys(item.strip() for item in result if item.strip()))


def _current_data_handling(
    feature_id: str, base: dict[str, Any], constant_ids: list[str]
) -> dict[str, Any]:
    current = copy.deepcopy(base["data_handling"])
    policy_control_only = feature_id in {
        "FP-010",
        "FP-011",
        "FP-013",
        "FP-014",
        "FP-015",
        "FP-047",
    }
    if "NPC-RAW-ORIGINAL-COLLECTION" in constant_ids:
        if policy_control_only:
            current["stored_on_device"] = list(dict.fromkeys([
                *current.get("stored_on_device", []),
                "이 기능은 활성 보행의 영상·음성·위치·센서 원본을 직접 만들지 않는다. 원본 수집 동의·권한·철회·계정 상태만 통제한다.",
            ]))
        else:
            current["stored_on_device"] = [
                "동의한 활성 보행에서 이 기능이 직접 만들거나 전달·보관하는 영상·음성·위치·센서·탐지·경로·신고·성능 원본 중 해당 항목을 암호화해 보관한다."
            ]
            current["sent_to_server"] = [
                "이 기능이 원본 전송을 담당할 때에는 정해진 조건을 만족한 가리지 않은 수집 원본과 필요한 부가정보만 암호화해 서버로 보낸다."
            ]
    if "NPC-DATA-LIFECYCLE" in constant_ids:
        lifecycle_device = "활성 보행 원본을 보관하는 경우, 서버 수신이 확인된 휴대전화 사본은 24시간 안에 삭제하고 미전송 원본은 30일을 넘기지 않는다."
        lifecycle_server = [
            "활성 보행 자료를 서버에서 다루는 경우, 서버 수신·검역 원본 14일, 일반·자동신고 원본 180일, 승인 학습자료·라벨·고정 검증자료 3년, 운영 백업 35일을 적용한다.",
            "전체 삭제 요청은 서버 원본 7일, 학습자료·라벨·가공본 30일, 백업 최대 35일 안에 처리한다. 원본 없는 삭제 확인 기록은 3년 보관한다.",
        ]
        if policy_control_only:
            current["delete_from_device"] = list(dict.fromkeys([
                *current.get("delete_from_device", []),
                lifecycle_device,
            ]))
            current["server_retention"] = list(dict.fromkeys([
                *current.get("server_retention", []),
                *lifecycle_server,
            ]))
        else:
            current["delete_from_device"] = [lifecycle_device]
            current["server_retention"] = lifecycle_server
    if "NPC-AUTO-REPORT" in constant_ids:
        current["sent_to_server"] = list(dict.fromkeys([
            *current.get("sent_to_server", []),
            "자동신고 후보에 한해서 보행 종료 뒤 사용자가 허용한 통신망에서 같은 신고번호로 중복 없이 전송하고, 서버 파일 저장과 파일 지문 확인서를 받은 뒤 완료로 처리한다.",
        ]))
        current["delete_from_device"] = list(dict.fromkeys([
            *current.get("delete_from_device", []),
            "자동신고를 끄면 자동신고 후보에 한해서 미전송 자료의 전송을 멈추고 24시간 안에 삭제한다. 이미 서버에 저장된 자동신고 원본은 삭제요청 상태로 바꿔 7일 안에 삭제한다. 일반 활동 원본은 자동신고를 껐다는 이유만으로 삭제하지 않고 별도의 동의 철회·전체 삭제·보존기간 정책을 따른다.",
        ]))
    empty_reasons = {
        "stored_on_device": "이 기능만을 위해 휴대전화에 따로 저장하는 자료는 없다.",
        "sent_to_server": "이 기능만을 위해 서버로 따로 보내는 자료는 없다.",
        "delete_from_device": "휴대전화에 따로 저장하지 않으므로 기능 고유 삭제 시점도 없다.",
        "server_retention": "서버에 따로 저장하지 않으므로 기능 고유 보존기간도 없다.",
    }
    for key, reason in empty_reasons.items():
        if not current.get(key):
            current[key] = [reason]
    common_policy_ids = [
        constant_id
        for constant_id in constant_ids
        if constant_id
        in {
            "NPC-RAW-ORIGINAL-COLLECTION",
            "NPC-DATA-LIFECYCLE",
            "NPC-AUTO-REPORT",
            "NPC-PHONE-QUEUE-CAPACITY",
            "NPC-SERVER-STORAGE-CAPACITY",
        }
    ]
    return {
        "uses_common_policy": bool(common_policy_ids),
        "policy_control_only": policy_control_only,
        "common_policy_ids": common_policy_ids,
        "current_policy": current,
    }


def _normal_flow(
    start_conditions: list[str], inputs: list[str], design_rules: list[str], outputs: list[str]
) -> list[str]:
    return [
        *[f"시작 조건을 확인한다: {item}" for item in start_conditions],
        f"필요한 정보를 확인한다: {'; '.join(inputs)}",
        *[f"처리한다: {item}" for item in design_rules],
        f"처리 결과를 만든다: {'; '.join(outputs)}",
    ]


def _lifecycle_rules(constant_ids: list[str]) -> dict[str, list[str]]:
    stop_or_pause = [
        "사용자가 해당 기능이나 보행을 직접 일시중지·종료하면 새 처리를 시작하지 않고 진행 중 상태를 안전하게 정리한다."
    ]
    resume = [
        "중지 원인이 사라졌는지 필요한 입력·권한·외부 연결·자료 상태를 다시 확인한 뒤에만 재개한다.",
        "재부팅·앱 오류 종료·운영체제 강제 종료 뒤에는 이전 보행이나 경로를 자동 재개하지 않고 사용자가 새 보행을 확인한다.",
    ]
    if "NPC-PERMISSION-SESSION-LIFECYCLE" in constant_ids:
        stop_or_pause.append("권한을 철회하면 그 권한에 의존하는 기능만 즉시 멈추고 다른 권한·로그인·동의 상태는 그대로 둔다.")
    if "NPC-RAW-ORIGINAL-COLLECTION" in constant_ids:
        stop_or_pause.append("원본 수집은 보행 일시중지·종료, 필요한 권한 철회, 전체 삭제 요청, 용량상 새 수집 보류 때 해당 범위를 멈춘다.")
    if "NPC-AUTO-REPORT" in constant_ids:
        stop_or_pause.append("자동신고를 끄면 새 후보를 즉시 막고 미전송 후보의 전송과 삭제 절차를 시작한다.")
    if "NPC-NAVIGATION-ROUTE-DIRECTION" in constant_ids:
        stop_or_pause.append("위치를 믿을 수 없거나 이탈이 의심되면 오래된 방향 안내를 멈추고 사용자의 다음 선택을 기다린다.")
        resume.append("새 경로는 사용자가 요청한 경우에만 받고, 회복 신호만으로 이전 길안내를 자동 재개하지 않는다.")
    return {"stop_or_pause": stop_or_pause, "resume": resume}


def _build_decision_catalog(register: dict[str, Any]) -> list[dict[str, Any]]:
    catalog = []
    for decision in register["decisions"]:
        feature_ids = decision["affected_feature_ids"]
        catalog.append(
            {
                "decision_id": decision["decision_id"],
                "canonical_decision_id": decision["canonical_decision_id"],
                "title": decision["title"],
                "register_statement_before_review": decision["decision_statement"],
                "register_status_before_review": decision["resolution_status"],
                "affected_feature_ids": feature_ids,
                "feature_policy_refs": [f"{feature_id}-POLICY-001" for feature_id in feature_ids],
                "mapping_granularity": "FEATURE_POLICY_BUNDLE",
                "current_report_treatment": "AFFECTED_FEATURE_POLICY_IDENTIFIED",
                "formal_register_update_status": "NOT_APPLIED",
            }
        )
    return catalog


def build_document() -> dict[str, Any]:
    expected_resolution = resolver.build_resolution()
    resolution = _load_json(RESOLUTION_PATH)
    _require(resolution == expected_resolution, "stored resolution is not the current recomputed resolution")
    rules = _load_json(RULES_PATH)
    proposals = _load_json(PROPOSALS_PATH)
    base_policy = _load_json(BASE_POLICY_PATH)
    register = _load_json(REGISTER_PATH)

    _require(rules.get("schema_version") == "walksafe.feature-policy-document-rules.v1", "document rules schema is invalid")
    _require(rules.get("lifecycle_status") == "IN_REVIEW", "document rules must stay in review")
    _require(rules["resolution_binding"]["path"] == str(RESOLUTION_PATH.relative_to(REPO_ROOT)), "document rules resolution path differs")
    _require(
        rules["resolution_binding"]["resolution_content_sha256"] == resolution["resolution_content_sha256"],
        "document rules are bound to another resolution",
    )
    _require(rules["approval_boundary"]["baseline_status"] == "NOT_APPROVED", "document rules claim baseline approval")
    _require(rules["approval_boundary"]["release_status"] == "NOT_ELIGIBLE", "document rules claim release eligibility")
    _require(
        [item["feature_id"] for item in proposals["features"]] == EXPECTED_FEATURE_IDS,
        "proposal feature coverage differs",
    )
    _require([item["id"] for item in base_policy["features"]] == EXPECTED_FEATURE_IDS, "base feature coverage differs")
    _require([item["id"] for item in base_policy["areas"]] == EXPECTED_AREA_IDS, "area coverage differs")
    _require(len(register["decisions"]) == 135, "decision register must contain 135 decisions")
    _require(len({item["decision_id"] for item in register["decisions"]}) == 135, "decision IDs are duplicated")
    _require(len({item["canonical_decision_id"] for item in register["decisions"]}) == 135, "canonical decision IDs are duplicated")
    _require(
        {item["id"] for item in resolution["normalized_policy_constants"]} == EXPECTED_CONSTANT_IDS,
        "normalized constant coverage differs",
    )
    _require({item["id"] for item in resolution["remaining_gates"]} == EXPECTED_GATE_IDS, "remaining gate coverage differs")
    _require(resolution["summary"]["remaining_policy_conflict_feature_ids"] == [], "policy conflicts remain")
    _require(resolution["summary"]["unresolved_review_ids"] == [], "review decisions remain unresolved")
    implementation_summary_overrides = rules.get("implementation_summary_overrides", {})
    _require(
        isinstance(implementation_summary_overrides, dict),
        "implementation summary overrides must be an object",
    )
    _require(
        set(implementation_summary_overrides) <= set(EXPECTED_FEATURE_IDS),
        "unknown implementation summary override",
    )
    _require(
        all(isinstance(value, str) and value.strip() for value in implementation_summary_overrides.values()),
        "implementation summary overrides must be non-empty strings",
    )

    proposals_by_id = {item["feature_id"]: item for item in proposals["features"]}
    base_by_id = {item["id"]: item for item in base_policy["features"]}
    resolution_by_id = {item["feature_id"]: item for item in resolution["feature_resolutions"]}
    review_by_id = {item["review_id"]: item for item in resolution["review_records"]}
    suppression_map = _suppression_map(rules, proposals_by_id, resolution)
    replacement_map = _replacement_map(rules, proposals_by_id, resolution)
    _require(
        rules.get("clause_selection_source_sha256")
        == _clause_selection_digest(rules, proposals_by_id),
        "suppression/replacement source selection binding differs",
    )
    flows = _end_to_end_flows()
    reviewed_cross_policies = _reviewed_cross_feature_policies(proposals, resolution, rules)

    constant_features = {
        constant_id: [
            feature["feature_id"]
            for feature in resolution["feature_resolutions"]
            if constant_id in feature["normalized_constant_ids"]
        ]
        for constant_id in EXPECTED_CONSTANT_IDS
    }
    common_policies = [
        _present_constant(item, constant_features[item["id"]])
        for item in resolution["normalized_policy_constants"]
    ]
    common_by_id = {item["id"]: item for item in common_policies}
    gates = [
        {
            **copy.deepcopy(gate),
            "title": GATE_TITLES[gate["id"]],
            "kind_label": GATE_KIND_LABELS[gate["kind"]],
        }
        for gate in resolution["remaining_gates"]
    ]
    gates_by_id = {item["id"]: item for item in gates}

    features = []
    suppressed_count = 0
    active_detail_count = 0
    pending_detail_count = 0
    replaced_count = 0
    for feature_id in EXPECTED_FEATURE_IDS:
        proposal = proposals_by_id[feature_id]
        base = base_by_id[feature_id]
        resolved = resolution_by_id[feature_id]
        direct_review = review_by_id[resolved["direct_review_id"]]
        removed: list[dict[str, Any]] = []
        replaced: list[dict[str, Any]] = []
        active_sections: dict[str, list[Any]] = {}
        for section in ("inputs", "outputs", "design_rules", "prohibited_behaviors", "verification_scenarios"):
            active_sections[section], section_removed, section_replaced = _active_array(
                feature_id, section, proposal[section], suppression_map, replacement_map
            )
            removed.extend(section_removed)
            replaced.extend(section_replaced)
        active_open, open_removed, open_replaced = _active_array(
            feature_id,
            "open_item_proposals",
            proposal["open_item_proposals"],
            suppression_map,
            replacement_map,
        )
        removed.extend(open_removed)
        replaced.extend(open_replaced)
        suppressed_count += len(removed)
        replaced_count += len(replaced)

        detailed_decisions = []
        for index, item in enumerate(proposal["open_item_proposals"]):
            decision_id = f"{feature_id}-DETAIL-{index + 1:02d}"
            detail_key = (feature_id, "open_item_proposals", index)
            superseded_by = suppression_map.get(detail_key)
            if superseded_by:
                continue
            detail_replacement = replacement_map.get(detail_key)
            evidence_pending = item["finalized_by"] != "OWNER_APPROVAL"
            pending_detail_count += int(evidence_pending)
            detailed_decisions.append(
                {
                    "id": decision_id,
                    "question": item["easy_question"],
                    "policy": detail_replacement["replacement"]
                    if detail_replacement
                    else item["recommended_answer"],
                    "reason": item["why"],
                    "completion_type": item["finalized_by"],
                    "completion_label": FINALIZATION_LABELS[item["finalized_by"]],
                    "status": "POLICY_METHOD_DECIDED_EVIDENCE_PENDING" if evidence_pending else "POLICY_DECIDED",
                    "interim_rule": (
                        "이 정책은 적용하되 표시된 독립 검토·측정·훈련 증거가 생기기 전에는 구현 완료나 출시 가능으로 판정하지 않는다."
                        if evidence_pending and detail_replacement
                        else item["until_finalized"]
                        if evidence_pending
                        else None
                    ),
                }
            )
        active_detail_count += len(detailed_decisions)

        effective_summary, followup_note = _effective_review_policy(
            direct_review, proposal["policy_conclusion"]
        )
        override = _feature_overrides(rules, feature_id)
        effective_summary = override.get("effective_policy_summary", effective_summary)
        plain_summary = override.get("plain_summary", effective_summary)
        start_conditions = copy.deepcopy(override.get("start_conditions", base["start_conditions"]))
        guidance = _current_guidance(
            feature_id, base, resolved["normalized_constant_ids"], override
        )
        execution_boundary = copy.deepcopy(
            override.get("execution_boundary", base["execution_boundary"])
        )
        failure_behavior = _current_failure_behavior(
            base, resolved["normalized_constant_ids"], override
        )
        data_handling = _current_data_handling(
            feature_id, base, resolved["normalized_constant_ids"]
        )
        common_effects = [
            {
                "id": constant_id,
                "title": common_by_id[constant_id]["title"],
                "summary": common_by_id[constant_id]["summary"],
            }
            for constant_id in resolved["normalized_constant_ids"]
        ]
        feature_gates = [gates_by_id[gate_id] for gate_id in resolved["remaining_gate_ids"]]
        permission_items = [
            {
                **copy.deepcopy(item),
                "requirement_label": PERMISSION_LABELS[item["requirement"]],
            }
            for item in base["required_permissions"]
        ]
        flow_ids = [flow["id"] for flow in flows if feature_id in flow["feature_ids"]]
        related_feature_ids = sorted(
            {
                related_id
                for flow in flows
                if feature_id in flow["feature_ids"]
                for related_id in flow["feature_ids"]
                if related_id != feature_id
            }
        )
        normal_flow = _normal_flow(
            start_conditions,
            active_sections["inputs"],
            active_sections["design_rules"],
            active_sections["outputs"],
        )
        features.append(
            {
                "id": feature_id,
                "policy_clause_id": f"{feature_id}-POLICY-001",
                "area_id": base["area_id"],
                "order": base["order"],
                "name": _easy(rules["feature_display_name_overrides"].get(feature_id, base["name"])),
                "source_name": base["name"],
                "plain_summary": plain_summary,
                "user_purpose": base["user_purpose"],
                "effective_policy_summary": effective_summary,
                "review_followup_note": followup_note,
                "policy_state": {
                    "integration_status": resolved["policy_integration_status"],
                    "integration_label": INTEGRATION_LABELS[resolved["policy_integration_status"]],
                    "policy_conflict_status": "CALCULATED_NONE",
                    "implementation_alignment_status": resolved["implementation_alignment_status"],
                    "implementation_alignment_label": ALIGNMENT_LABELS[resolved["implementation_alignment_status"]],
                },
                "start_conditions": start_conditions,
                "inputs": active_sections["inputs"],
                "outputs": active_sections["outputs"],
                "normal_flow": normal_flow,
                "lifecycle_rules": _lifecycle_rules(resolved["normalized_constant_ids"]),
                "design_rules": active_sections["design_rules"],
                "failure_behavior": failure_behavior,
                "prohibited_behaviors": active_sections["prohibited_behaviors"],
                "verification_scenarios": active_sections["verification_scenarios"],
                "detailed_decisions": detailed_decisions,
                "superseded_source_clauses": removed,
                "replaced_source_clauses": replaced,
                "user_guidance": guidance,
                "permissions": permission_items,
                "execution_boundary": execution_boundary,
                "data_handling": data_handling,
                "common_policy_effects": common_effects,
                "remaining_gates": feature_gates,
                "implementation": {
                    "status_before_review": base["implementation"]["status"],
                    "status_label": IMPLEMENTATION_LABELS[base["implementation"]["status"]],
                    "plain_status_before_review": implementation_summary_overrides.get(
                        feature_id, base["implementation"]["plain_status"]
                    ),
                    "evidence_refs": base["implementation"]["evidence_refs"],
                    "alignment_status": resolved["implementation_alignment_status"],
                    "alignment_label": ALIGNMENT_LABELS[resolved["implementation_alignment_status"]],
                },
                "flow_ids": flow_ids,
                "related_feature_ids": related_feature_ids,
                "traceability": {
                    "direct_review_id": resolved["direct_review_id"],
                    "applicable_review_ids": resolved["applicable_review_ids"],
                    "cascade_group_ids": resolved["cascade_group_ids"],
                    "normalized_constant_ids": resolved["normalized_constant_ids"],
                    "replacement_ids": resolved["known_conflict_replacement_ids"],
                    "decision_ids": base["decision_refs"],
                    "canonical_decision_ids": base["canonical_decision_refs"],
                    "source_refs": base["source_refs"],
                    "affected_deliverables": base["affected_deliverables"],
                },
            }
        )

    for feature in features:
        search_values = [
            feature["id"],
            feature["name"],
            feature["source_name"],
            feature["plain_summary"],
            feature["user_purpose"],
            feature["effective_policy_summary"],
            *feature["normal_flow"],
            *(item for values in feature["lifecycle_rules"].values() for item in values),
            *feature["failure_behavior"],
            *feature["inputs"],
            *feature["outputs"],
            *feature["design_rules"],
            *feature["prohibited_behaviors"],
            *feature["verification_scenarios"],
            *(item for values in feature["user_guidance"].values() for item in values),
            *(item for values in feature["execution_boundary"].values() for item in values),
            *(item for values in feature["data_handling"]["current_policy"].values() for item in values),
        ]
        feature["search_text"] = _easy(" ".join(search_values))

    expected_review_ids = {
        *[f"GP-{index:02d}" for index in range(1, 8)],
        *[f"SP-{index:02d}" for index in range(1, 16)],
        *EXPECTED_FEATURE_IDS,
    }
    review_application_log = []
    for review in resolution["review_records"]:
        if review["scope"] in {"GLOBAL", "SHARED"}:
            refs = [f"{review['review_id']}-POLICY-001"]
        else:
            refs = [f"{feature_id}-POLICY-001" for feature_id in review["affected_feature_ids"]]
        review_application_log.append(
            {
                "review_id": review["review_id"],
                "scope": review["scope"],
                "decision": review["decision"],
                "merge_action": review["merge_action"],
                "active_policy_refs": refs,
                "feature_policy_refs": [ref for ref in refs if ref.startswith("FP-")],
                "followup_note_preserved": bool(
                    review["reviewer_note"] and not review["is_dated_correction"]
                ),
                "application_status": "APPLIED_TO_REVIEW_DRAFT",
            }
        )
    _require(
        {item["review_id"] for item in review_application_log} == expected_review_ids,
        "review application coverage differs",
    )

    decision_catalog = _build_decision_catalog(register)
    decision_edges = sum(len(item["affected_feature_ids"]) for item in decision_catalog)
    source_bindings = {
        "generator": _source_binding(GENERATOR_PATH),
        "document_rules": _source_binding(RULES_PATH),
        "resolution": _source_binding(RESOLUTION_PATH),
        "proposals": _source_binding(PROPOSALS_PATH),
        "base_policy": _source_binding(BASE_POLICY_PATH),
        "decision_register": _source_binding(REGISTER_PATH),
        "template": _source_binding(TEMPLATE_PATH),
    }
    source_binding_sha256 = _object_sha256(source_bindings)
    document = {
        "schema_version": "walksafe.feature-policy-comprehensive-draft.v1",
        "metadata": {
            "document_id": "WS-FEATURE-POLICY-DRAFT-20260720",
            "document_version": "1.0.0",
            "as_of": "2026-07-20",
            "title": "WalkSafe 기능별 종합 정책서",
            "subtitle": "답변과 후속 결정을 모두 반영한 검토용 초안",
            "lifecycle_status": "IN_REVIEW",
            "policy_decision_status": "REVIEW_COMPLETE_WITH_REVISIONS_INTEGRATED",
            "baseline_status": "NOT_APPROVED",
            "release_status": "NOT_ELIGIBLE",
            "html_report_generation_status": "GENERATED",
            "formal_deliverable_generation_status": "NOT_RUN",
            "purpose": "54개 기능의 목적·작동·권한·데이터·안전·운영·시험 정책을 비전공자가 검토하고 이후 정식 산출물의 공통 원천으로 확정하기 위한 문서",
        },
        "source_binding_sha256": source_binding_sha256,
        "source_bindings": source_bindings,
        "resolution_content_sha256": resolution["resolution_content_sha256"],
        "policy_precedence": rules["policy_precedence"],
        "summary": {
            "area_count": len(base_policy["areas"]),
            "feature_count": len(features),
            "review_count": resolution["summary"]["reviewed"],
            "normalized_constant_count": len(common_policies),
            "cascade_group_count": len(resolution["cascade_groups"]),
            "replacement_count": len(resolution["known_conflict_replacements"]),
            "remaining_gate_count": len(gates),
            "gate_feature_edge_count": sum(len(item["affected_feature_ids"]) for item in gates),
            "implementation_revalidation_feature_count": resolution["summary"]["implementation_revalidation_feature_count"],
            "decision_count": len(decision_catalog),
            "decision_feature_edge_count": decision_edges,
            "source_detail_decision_count": sum(len(item["open_item_proposals"]) for item in proposals["features"]),
            "active_detail_decision_count": active_detail_count,
            "evidence_pending_detail_decision_count": pending_detail_count,
            "suppressed_detail_decision_count": sum(
                sum(item["section"] == "open_item_proposals" for item in feature["superseded_source_clauses"])
                for feature in features
            ),
            "suppressed_source_clause_count": suppressed_count,
            "replaced_source_clause_count": replaced_count,
            "cross_feature_policy_count": len(reviewed_cross_policies),
            "remaining_policy_conflict_count": 0,
        },
        "reading_guide": [
            {
                "title": "정책은 정해졌습니다",
                "text": "답변과 후속 확정을 합쳐 현재 기능 설계에 적용할 정책을 적었습니다. 폐기된 과거 문구는 활성 본문에서 제외했습니다.",
            },
            {
                "title": "구현 완료와는 다릅니다",
                "text": "정책이 정해졌어도 코드가 그 정책과 같은지는 다시 확인해야 합니다. 50개 기능은 구현 근거 재검증 대상입니다.",
            },
            {
                "title": "남은 확인은 숨기지 않습니다",
                "text": "숫자 측정, 독립 검토, 복구훈련이 필요한 항목은 ‘정책 방법은 결정·증거는 대기’로 표시했습니다.",
            },
        ],
        "end_to_end_flows": flows,
        "common_policies": common_policies,
        "reviewed_cross_feature_policies": reviewed_cross_policies,
        "remaining_gates": gates,
        "areas": copy.deepcopy(base_policy["areas"]),
        "features": features,
        "decision_catalog": decision_catalog,
        "review_application_log": review_application_log,
        "approval_boundary": {
            **copy.deepcopy(resolution["approval_boundary"]),
            "document_status": rules["approval_boundary"]["document_status"],
            "formal_deliverable_generation_status": rules["approval_boundary"]["formal_deliverable_generation_status"],
        },
        "handoff": {
            "current_step": "FEATURE_POLICY_REVIEW_DRAFT",
            "next_steps": [
                "사용자가 공통정책 9개와 기능 54개를 읽고 각 NPC·FP ID별로 확정·수정 필요·보류를 선택하며, 수정하거나 보류할 문장을 메모한다.",
                "63개 항목을 모두 확인한 뒤 답변 JSON을 내보내 프로젝트에 다시 전달한다.",
                "수정·보류 내용을 반영해 종합 정책서를 다시 생성하고 재검토한다.",
                "모든 검토가 끝난 뒤 프로젝트관리자가 별도 정책 기준선 승인 기록을 남긴다.",
                "승인된 정책과 135개 결정대장을 맞춘 뒤 요구사항 추적표를 만든다.",
                "그 기준으로 0~6 산출물을 작성하고 구현·시험 증거를 연결한다.",
            ],
            "baseline_approval_required_before_formal_deliverables": True,
        },
        "coverage": {
            "required_area_ids": EXPECTED_AREA_IDS,
            "required_feature_ids": EXPECTED_FEATURE_IDS,
            "required_constant_ids": sorted(EXPECTED_CONSTANT_IDS),
            "required_gate_ids": sorted(EXPECTED_GATE_IDS),
            "all_reviews_accounted_for": {item["review_id"] for item in review_application_log}
            == expected_review_ids
            and all(item["active_policy_refs"] for item in review_application_log),
            "all_decisions_linked": decision_edges == 428,
            "policy_conflicts_remaining": [],
            "unresolved_review_ids": [],
        },
    }
    document["document_content_sha256"] = _object_sha256(document)
    validate_document(document, rules=rules, resolution=resolution, proposals=proposals)
    return document


def validate_document(
    document: dict[str, Any],
    *,
    rules: dict[str, Any] | None = None,
    resolution: dict[str, Any] | None = None,
    proposals: dict[str, Any] | None = None,
) -> None:
    rules = rules or _load_json(RULES_PATH)
    resolution = resolution or _load_json(RESOLUTION_PATH)
    proposals = proposals or _load_json(PROPOSALS_PATH)
    base_policy = _load_json(BASE_POLICY_PATH)
    register = _load_json(REGISTER_PATH)
    digest = document.get("document_content_sha256")
    _require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest) is not None, "document digest is invalid")
    payload = copy.deepcopy(document)
    payload.pop("document_content_sha256")
    _require(_object_sha256(payload) == digest, "document digest differs from content")
    _require(document["metadata"]["lifecycle_status"] == "IN_REVIEW", "document claims approved lifecycle")
    _require(document["metadata"]["baseline_status"] == "NOT_APPROVED", "document claims baseline approval")
    _require(document["metadata"]["release_status"] == "NOT_ELIGIBLE", "document claims release eligibility")
    _require(document["metadata"]["formal_deliverable_generation_status"] == "NOT_RUN", "document claims formal deliverables")
    _require(
        document["metadata"]["policy_decision_status"]
        == "REVIEW_COMPLETE_WITH_REVISIONS_INTEGRATED",
        "policy decision status differs",
    )
    _require(document["resolution_content_sha256"] == resolution["resolution_content_sha256"], "resolution content binding differs")
    expected_bindings = {
        "generator": _source_binding(GENERATOR_PATH),
        "document_rules": _source_binding(RULES_PATH),
        "resolution": _source_binding(RESOLUTION_PATH),
        "proposals": _source_binding(PROPOSALS_PATH),
        "base_policy": _source_binding(BASE_POLICY_PATH),
        "decision_register": _source_binding(REGISTER_PATH),
        "template": _source_binding(TEMPLATE_PATH),
    }
    _require(document["source_bindings"] == expected_bindings, "source bindings differ from current files")
    _require(document["source_binding_sha256"] == _object_sha256(document["source_bindings"]), "source binding digest differs")
    _require(document["policy_precedence"] == rules["policy_precedence"], "policy precedence differs")
    expected_approval_boundary = {
        **resolution["approval_boundary"],
        "document_status": rules["approval_boundary"]["document_status"],
        "formal_deliverable_generation_status": rules["approval_boundary"]["formal_deliverable_generation_status"],
    }
    _require(document["approval_boundary"] == expected_approval_boundary, "approval boundary differs")

    features = document["features"]
    _require([item["id"] for item in features] == EXPECTED_FEATURE_IDS, "document feature coverage differs")
    _require(len({item["policy_clause_id"] for item in features}) == 54, "feature policy clause IDs are duplicated")
    _require([item["id"] for item in document["areas"]] == EXPECTED_AREA_IDS, "document area coverage differs")
    constant_features = {
        constant_id: [
            feature["feature_id"]
            for feature in resolution["feature_resolutions"]
            if constant_id in feature["normalized_constant_ids"]
        ]
        for constant_id in EXPECTED_CONSTANT_IDS
    }
    expected_common = [
        _present_constant(item, constant_features[item["id"]])
        for item in resolution["normalized_policy_constants"]
    ]
    _require(document["common_policies"] == expected_common, "common policies differ from resolution")
    expected_gates = [
        {
            **copy.deepcopy(gate),
            "title": GATE_TITLES[gate["id"]],
            "kind_label": GATE_KIND_LABELS[gate["kind"]],
        }
        for gate in resolution["remaining_gates"]
    ]
    _require(document["remaining_gates"] == expected_gates, "remaining gates differ from resolution")
    expected_catalog = _build_decision_catalog(register)
    _require(document["decision_catalog"] == expected_catalog, "decision catalog differs from register")
    _require(len({item["decision_id"] for item in document["decision_catalog"]}) == 135, "decision IDs are duplicated")
    _require(sum(len(item["affected_feature_ids"]) for item in document["decision_catalog"]) == 428, "decision links differ")
    _require(all(item["feature_policy_refs"] for item in document["decision_catalog"]), "decision has no feature policy reference")
    _require(all(item["mapping_granularity"] == "FEATURE_POLICY_BUNDLE" for item in document["decision_catalog"]), "decision mapping granularity is overstated")
    _require(document["summary"]["remaining_policy_conflict_count"] == 0, "document reports policy conflict")
    _require(document["summary"]["implementation_revalidation_feature_count"] == 50, "implementation revalidation count differs")
    _require(document["summary"]["gate_feature_edge_count"] == 42, "gate-feature edge count differs")
    original_detail_count = sum(len(item["open_item_proposals"]) for item in proposals["features"])
    active_detail_count = sum(len(item["detailed_decisions"]) for item in features)
    suppressed_open_count = sum(
        sum(item["section"] == "open_item_proposals" for item in feature["superseded_source_clauses"])
        for feature in features
    )
    _require(original_detail_count == 151, "source detail decision count differs")
    _require(active_detail_count + suppressed_open_count == original_detail_count, "detail decision accounting differs")
    _require(document["summary"]["suppressed_detail_decision_count"] == suppressed_open_count, "suppressed detail count differs")
    _require(document["summary"]["cross_feature_policy_count"] == 22, "cross-feature policy count differs")
    expected_cross = _reviewed_cross_feature_policies(proposals, resolution, rules)
    _require(document["reviewed_cross_feature_policies"] == expected_cross, "cross-feature policies differ from reviews")

    expected_review_ids = {
        *[f"GP-{index:02d}" for index in range(1, 8)],
        *[f"SP-{index:02d}" for index in range(1, 16)],
        *EXPECTED_FEATURE_IDS,
    }
    application_log = document["review_application_log"]
    _require(len(application_log) == 76, "review application log count differs")
    _require({item["review_id"] for item in application_log} == expected_review_ids, "review application IDs differ")
    known_policy_refs = {
        *[item["policy_clause_id"] for item in features],
        *[item["id"] for item in expected_cross],
    }
    _require(
        all(item["active_policy_refs"] and set(item["active_policy_refs"]) <= known_policy_refs for item in application_log),
        "review application has an unknown policy reference",
    )
    sp06 = next(item for item in expected_cross if item["source_review_id"] == "SP-06")
    _require(sp06["followup_note"] == "문자 업체는 내가 조사해볼게", "SP-06 follow-up note was not preserved")

    resolution_by_id = {item["feature_id"]: item for item in resolution["feature_resolutions"]}
    base_by_id = {item["id"]: item for item in base_policy["features"]}
    for feature in features:
        _require(feature["effective_policy_summary"].strip(), f"{feature['id']} policy summary is empty")
        _require(feature["inputs"], f"{feature['id']} inputs are empty")
        _require(feature["outputs"], f"{feature['id']} outputs are empty")
        _require(feature["normal_flow"], f"{feature['id']} normal flow is empty")
        _require(feature["design_rules"], f"{feature['id']} design rules are empty")
        _require(feature["failure_behavior"], f"{feature['id']} failure behavior is empty")
        _require(feature["prohibited_behaviors"], f"{feature['id']} prohibited behaviors are empty")
        _require(feature["verification_scenarios"], f"{feature['id']} verification scenarios are empty")
        _require(feature["policy_state"]["policy_conflict_status"] == "CALCULATED_NONE", f"{feature['id']} conflict remains")
        _require(feature["name"] in feature["search_text"], f"{feature['id']} display name is not searchable")
        _require(all(feature["data_handling"]["current_policy"].values()), f"{feature['id']} current data handling is incomplete")
        _require(
            all(
                detail["interim_rule"]
                for detail in feature["detailed_decisions"]
                if detail["status"] == "POLICY_METHOD_DECIDED_EVIDENCE_PENDING"
            ),
            f"{feature['id']} evidence-pending detail has no interim rule",
        )
        expected = resolution_by_id[feature["id"]]
        _require(
            [item["id"] for item in feature["remaining_gates"]] == expected["remaining_gate_ids"],
            f"{feature['id']} gate links differ",
        )
        _require(
            [item["id"] for item in feature["common_policy_effects"]] == expected["normalized_constant_ids"],
            f"{feature['id']} normalized policy links differ",
        )
        expected_trace = {
            "direct_review_id": expected["direct_review_id"],
            "applicable_review_ids": expected["applicable_review_ids"],
            "cascade_group_ids": expected["cascade_group_ids"],
            "normalized_constant_ids": expected["normalized_constant_ids"],
            "replacement_ids": expected["known_conflict_replacement_ids"],
            "decision_ids": base_by_id[feature["id"]]["decision_refs"],
            "canonical_decision_ids": base_by_id[feature["id"]]["canonical_decision_refs"],
            "source_refs": base_by_id[feature["id"]]["source_refs"],
            "affected_deliverables": base_by_id[feature["id"]]["affected_deliverables"],
        }
        _require(feature["traceability"] == expected_trace, f"{feature['id']} traceability differs")
        _require(
            feature["implementation"]["alignment_status"] == expected["implementation_alignment_status"],
            f"{feature['id']} implementation alignment was overstated",
        )

    active_text = json.dumps(
        [
            {
                "summary": item["effective_policy_summary"],
                "plain_summary": item["plain_summary"],
                "start": item["start_conditions"],
                "inputs": item["inputs"],
                "outputs": item["outputs"],
                "flow": item["normal_flow"],
                "design": item["design_rules"],
                "failure": item["failure_behavior"],
                "prohibited": item["prohibited_behaviors"],
                "verification": item["verification_scenarios"],
                "guidance": item["user_guidance"],
                "permissions": item["permissions"],
                "boundary": item["execution_boundary"],
                "data": item["data_handling"]["current_policy"],
                "details": [
                    {key: value for key, value in detail.items() if key != "interim_rule"}
                    for detail in item["detailed_decisions"]
                ],
            }
            for item in features
        ],
        ensure_ascii=False,
    )
    forbidden_active_phrases = (
        "임시 메모리에서 분석한 뒤 바로 버리며",
        "사람 얼굴과 차량 번호판을 가린 뒤 전송",
        "기본값은 원본 음성을 저장하거나 서버로 보내지 않는",
        "지속적인 보행 전체 원본은 모으지 않는다",
        "후보 생성 즉시 음성으로 알리고",
        "자동으로 최대 두 번 새 경로",
        "두 명의 독립 확인자가 복구",
        "서로 다른 두 승인자",
        "상한이 0원이면 원본 학습자료 수집을 끄고",
        "자동 재탐색",
        "재시도 budget",
        "현재 답변은 rollback 없이",
        "현재 답변은 모델을 APK에 함께 넣어",
        "0원 목표와 대용량 원본",
        "이 보고서의 권고 기준은",
        "재승인될 경우",
        "사용자 안내 없이 미전송 신고를 오래된 순서로 임의 삭제하지 않는다",
        "오른쪽으로 천천히 이동하세요",
    )
    for phrase in forbidden_active_phrases:
        _require(phrase not in active_text, f"superseded phrase remains active: {phrase}")

    fp019 = next(item for item in features if item["id"] == "FP-019")
    fp019_responsibility = json.dumps(
        {
            "summary": fp019["plain_summary"],
            "guidance": fp019["user_guidance"],
            "boundary": fp019["execution_boundary"],
        },
        ensure_ascii=False,
    )
    _require("물체 후보와 관측 근거" in fp019_responsibility, "FP-019 observation responsibility is missing")
    _require("FP-020" in fp019_responsibility, "FP-019 does not delegate risk decisions to FP-020")
    _require("오른쪽으로" not in fp019_responsibility, "FP-019 gives an unsafe direction instruction")


def _render_common_policy(item: dict[str, Any], feature_names: dict[str, str]) -> str:
    return f"""
      <article class="common-card" id="{item['id'].lower()}" data-review-unit-id="{item['id']}">
        <p class="eyebrow">공통 정책 · {_raw_e(item['id'])}</p>
        <h3>{_e(item['title'])}</h3>
        <p>{_e(item['summary'])}</p>
        {_list_html(item['rules'])}
        <details class="trace-only"><summary>적용 기능과 근거 ID</summary><div class="details-body">
          <p>{_feature_links(item['affected_feature_ids'], feature_names)}</p>
          <p class="trace-id">근거 검토: {_raw_e(', '.join(item['source_review_ids']))}</p>
        </div></details>
        {_render_review_controls(item['id'], item['title'])}
      </article>
    """


def _render_cross_policy(item: dict[str, Any], feature_names: dict[str, str]) -> str:
    followup = (
        f'<p class="notice"><strong>후속 담당 메모:</strong> {_e(item["followup_note"])}</p>'
        if item["followup_note"]
        else ""
    )
    interim = (
        f'<details><summary>측정·검토가 끝나기 전 적용할 제한</summary><div class="details-body"><p>{_e(item["interim_rule"])}</p></div></details>'
        if item.get("interim_rule") and item.get("completion_type") != "OWNER_APPROVAL"
        else ""
    )
    return f"""
      <article class="common-card" id="{item['id'].lower()}">
        <p class="eyebrow">이미 검토한 {('전체' if item['scope'] == 'GLOBAL' else '여러 기능 공통')} 정책 · {_raw_e(item['source_review_id'])}</p>
        <h3>{_e(item['title'])}</h3>
        <p><strong>검토 질문:</strong> {_e(item['question'])}</p>
        <p>{_e(item['current_policy'])}</p>
        <p class="muted"><strong>이유:</strong> {_e(item['reason'])}</p>
        {followup}
        {interim}
        <details class="trace-only"><summary>적용 기능과 반영 상태</summary><div class="details-body">
          <p>{_feature_links(item['affected_feature_ids'], feature_names)}</p>
          <p class="trace-id">검토결과: {_raw_e(item['review_decision'])} · 처리: {_raw_e(item['merge_action'])}</p>
        </div></details>
      </article>
    """


def _render_gate(item: dict[str, Any], feature_names: dict[str, str]) -> str:
    return f"""
      <article class="gate-card" id="{item['id'].lower()}">
        <p class="eyebrow">아직 실행하지 않은 확인 · {_raw_e(item['id'])}</p>
        <h3>{_e(item['title'])}</h3>
        <p><span class="badge pending">{_e(item['kind_label'])} · 미실행</span></p>
        <p>{_e(item['closure'])}</p>
        <details><summary>영향받는 기능 {len(item['affected_feature_ids'])}개</summary><div class="details-body">
          <p>{_feature_links(item['affected_feature_ids'], feature_names)}</p>
        </div></details>
      </article>
    """


def _render_boundaries(boundary: dict[str, list[str]]) -> str:
    return f"""
      <div class="three-col">
        <section class="panel"><h4>휴대전화가 담당</h4>{_list_html(boundary['on_device'], '휴대전화 전용 책임 없음')}</section>
        <section class="panel"><h4>서버가 담당</h4>{_list_html(boundary['server'], '서버 책임 없음')}</section>
        <section class="panel"><h4>외부 서비스·기관</h4>{_list_html(boundary['external'], '외부 연동 없음')}</section>
      </div>
    """


def _render_data_policy(data: dict[str, Any]) -> str:
    current = data["current_policy"]
    return f"""
      <div class="two-col">
        <section class="panel"><h4>휴대전화에 저장</h4>{_list_html(current['stored_on_device'])}</section>
        <section class="panel"><h4>서버로 전송</h4>{_list_html(current['sent_to_server'])}</section>
        <section class="panel"><h4>휴대전화에서 삭제</h4>{_list_html(current['delete_from_device'])}</section>
        <section class="panel"><h4>서버 보존·삭제</h4>{_list_html(current['server_retention'])}</section>
      </div>
    """


def _render_guidance(guidance: dict[str, list[str]]) -> str:
    return f"""
      <div class="three-col">
        <section class="panel"><h4>화면·화면읽기</h4>{_list_html(guidance['screen'], '화면에 별도 안내하지 않음')}</section>
        <section class="panel"><h4>음성</h4>{_list_html(guidance['speech'], '음성으로 별도 안내하지 않음')}</section>
        <section class="panel"><h4>진동</h4>{_list_html(guidance['vibration'], '진동을 사용하지 않음')}</section>
      </div>
    """


def _render_permissions(items: list[dict[str, Any]]) -> str:
    if not items:
        return '<p class="not-applicable">이 기능이 직접 요청하는 운영체제 권한은 없습니다.</p>'
    return "<ul>" + "".join(
        f"<li><strong>{_e(item['name'])} · {_e(item['requirement_label'])}</strong><br>{_e(item['plain_reason'])}</li>"
        for item in items
    ) + "</ul>"


def _render_details(feature: dict[str, Any]) -> str:
    if not feature["detailed_decisions"]:
        return '<p class="not-applicable">별도의 세부 결정 항목이 없습니다.</p>'
    cards = []
    for item in feature["detailed_decisions"]:
        status = "pending" if item["status"].endswith("PENDING") else "policy"
        interim = (
            f'<p class="notice"><strong>증거가 생기기 전 적용할 안전 제한:</strong> {_e(item["interim_rule"])}</p>'
            if item["interim_rule"]
            else ""
        )
        cards.append(
            f"""
            <article class="decision-card" id="{item['id'].lower()}">
              <p class="trace-id">{_raw_e(item['id'])}</p>
              <h5>{_e(item['question'])}</h5>
              <p><strong>적용할 기준:</strong> {_e(item['policy'])}</p>
              <p><strong>이유:</strong> {_e(item['reason'])}</p>
              {interim}
              <p><span class="badge {status}">{_e(item['completion_label'])}</span></p>
            </article>
            """
        )
    return "".join(cards)


def _render_feature(feature: dict[str, Any], feature_names: dict[str, str]) -> str:
    search_text = feature["search_text"]
    gate_ids = " ".join(item["id"] for item in feature["remaining_gates"])
    common_effects = _list_html([], "이 기능에 직접 전파된 공통 정책이 없습니다.")
    if feature["common_policy_effects"]:
        common_effects = '<ul class="common-effects">' + "".join(
            f'<li><strong><a href="#{item["id"].lower()}">{_e(item["title"])}</a></strong>{_e(item["summary"])}</li>'
            for item in feature["common_policy_effects"]
        ) + "</ul>"
    gate_html = _list_html([], "이 기능에 연결된 5대 공통 미실행 확인은 없습니다.")
    if feature["remaining_gates"]:
        gate_html = "<ul>" + "".join(
            f'<li><a href="#{item["id"].lower()}"><strong>{_e(item["title"])}</strong></a><br>{_e(item["closure"])}</li>'
            for item in feature["remaining_gates"]
        ) + "</ul>"
    evidence_pending = sum(
        item["status"] == "POLICY_METHOD_DECIDED_EVIDENCE_PENDING"
        for item in feature["detailed_decisions"]
    )
    if feature["data_handling"]["policy_control_only"]:
        data_text = "이 기능은 보행 원본을 직접 만들지 않습니다. 동의·권한·철회·계정 상태를 관리해 위 공통 수집정책이 언제 시작되고 멈추는지를 통제하며, 아래에는 이 기능이 실제로 다루는 계정·상태 자료를 따로 적었습니다."
    elif feature["data_handling"]["uses_common_policy"]:
        data_text = "이 기능이 직접 만들거나 전달·보관하는 원본에는 위 공통 보존·삭제 정책을 적용합니다. 기능별 과거 보존 문구보다 공통 정책이 우선합니다."
    else:
        data_text = "이 기능은 공통 원본 수집·보존 정책의 직접 대상이 아닙니다. 아래 기능 고유 자료 처리 기준을 적용합니다."
    return f"""
      <article class="feature" id="{feature['id'].lower()}" data-feature-id="{feature['id']}"
        data-review-unit-id="{feature['id']}"
        data-area-id="{feature['area_id']}" data-gate-ids="{_raw_e(gate_ids)}"
        data-search="{_raw_e(search_text)}">
        <header class="feature-header">
          <div>
            <p class="eyebrow">{feature['id']} · 기능 {feature['order']}</p>
            <h3>{_e(feature['name'])}</h3>
          </div>
          <div class="status-badges">
            <span class="badge policy">정책 충돌 0건</span>
            <span class="badge">{_e(feature['policy_state']['integration_label'])}</span>
            <span class="badge pending">{_e(feature['policy_state']['implementation_alignment_label'])}</span>
            {f'<span class="badge pending">공통 확인 {len(feature["remaining_gates"])}건 미실행</span>' if feature['remaining_gates'] else ''}
          </div>
        </header>

        <section class="policy-summary" id="{feature['policy_clause_id'].lower()}">
          <span>현재 적용할 기능 정책</span>
          <p>{_e(feature['effective_policy_summary'])}</p>
        </section>

        <div class="two-col">
          <section class="panel"><h4>이 기능은 무엇인가</h4><p>{_e(feature['plain_summary'])}</p></section>
          <section class="panel"><h4>사용자에게 왜 필요한가</h4><p>{_e(feature['user_purpose'])}</p></section>
        </div>

        <details open><summary>시작 조건·입력·결과</summary><div class="details-body">
          <section class="panel"><h4>시작 조건</h4>{_list_html(feature['start_conditions'])}</section>
          <div class="io-grid">
            <section class="panel"><h4>받는 정보</h4>{_list_html(feature['inputs'])}</section>
            <div class="arrow" aria-hidden="true">→</div>
            <section class="panel"><h4>만드는 결과</h4>{_list_html(feature['outputs'])}</section>
          </div>
        </div></details>

        <details open><summary>처음부터 결과까지 정상 작동 순서</summary><div class="details-body">
          {_list_html(feature['normal_flow'])}
        </div></details>

        <details open><summary>구체적인 작동 규칙</summary><div class="details-body">
          {_list_html(feature['design_rules'])}
        </div></details>

        <details open><summary>실패·중지·복구 규칙</summary><div class="details-body">
          {_list_html(feature['failure_behavior'])}
        </div></details>

        <details><summary>사용자 안내와 접근성</summary><div class="details-body">
          {_render_guidance(feature['user_guidance'])}
        </div></details>

        <details><summary>권한과 실행 책임</summary><div class="details-body">
          <section class="panel"><h4>필요 권한</h4>{_render_permissions(feature['permissions'])}</section>
          {_render_boundaries(feature['execution_boundary'])}
        </div></details>

        <details><summary>데이터·공통 정책이 이 기능에 미치는 영향</summary><div class="details-body">
          <p>{_e(data_text)}</p>
          {_render_data_policy(feature['data_handling'])}
          {common_effects}
        </div></details>

        <details><summary>세부 결정 {len(feature['detailed_decisions'])}건 · 증거 대기 {evidence_pending}건</summary><div class="details-body">
          <p class="notice"><strong>표시 기준:</strong> 아래 내용은 정책 방향이나 확인 방법은 정해졌지만, ‘실제 측정 필요’라고 표시된 수치는 시험 결과가 생기기 전까지 임의로 만들지 않습니다.</p>
          {_render_details(feature)}
          {f'<p class="superseded-note">후속 확정과 충돌한 과거 세부 문구 {len(feature["superseded_source_clauses"])}건은 활성 정책에서 제외했습니다. 대체 근거 ID는 접힌 추적정보에 남겼습니다.</p>' if feature['superseded_source_clauses'] else ''}
        </div></details>

        <details open><summary>금지 동작과 확인 방법</summary><div class="details-body">
          <div class="two-col">
            <section class="panel"><h4>하면 안 되는 일</h4>{_list_html(feature['prohibited_behaviors'])}</section>
            <section class="panel"><h4>시험·검토로 확인할 일</h4>{_list_html(feature['verification_scenarios'])}</section>
          </div>
        </div></details>

        <details class="pending-section" {'open' if feature['remaining_gates'] else ''}><summary>아직 실행하지 않은 공통 확인 {len(feature['remaining_gates'])}건</summary><div class="details-body">
          {gate_html}
        </div></details>

        <details><summary>현재 구현 근거와 정책 일치 여부</summary><div class="details-body">
          <p><strong>검토 전 구현 상태:</strong> {_e(feature['implementation']['status_label'])}</p>
          <p>{_e(feature['implementation']['plain_status_before_review'])}</p>
          <p><strong>현재 판단:</strong> {_e(feature['implementation']['alignment_label'])}</p>
        </div></details>

        <details class="trace-only"><summary>연결 기능·결정·산출물 추적정보</summary><div class="details-body">
          {f'<p class="trace-id"><strong>검토 전 기술 제목:</strong> {_raw_e(feature["source_name"])}</p>' if feature['name'] != feature['source_name'] else ''}
          <p class="trace-id"><strong>구현 근거:</strong> {_raw_e(', '.join(feature['implementation']['evidence_refs']) or '직접 연결 근거 없음')}</p>
          <p><strong>같은 흐름의 기능:</strong> {_feature_links(feature['related_feature_ids'], feature_names)}</p>
          <p class="trace-id"><strong>검토:</strong> {_raw_e(', '.join(feature['traceability']['applicable_review_ids']))}</p>
          <p class="trace-id"><strong>공통 정책:</strong> {_raw_e(', '.join(feature['traceability']['normalized_constant_ids']) or '없음')}</p>
          <p class="trace-id"><strong>충돌 대체:</strong> {_raw_e(', '.join(feature['traceability']['replacement_ids']) or '없음')}</p>
          <p class="trace-id"><strong>결정:</strong> {_raw_e(', '.join(feature['traceability']['decision_ids']))}</p>
          <p class="trace-id"><strong>근거:</strong> {_raw_e(', '.join(feature['traceability']['source_refs']))}</p>
          <p class="trace-id"><strong>반영할 산출물 유형:</strong> {_raw_e(', '.join(feature['traceability']['affected_deliverables']))}</p>
        </div></details>
        {_render_review_controls(feature['id'], feature['name'])}
        <a class="back-link" href="#feature-index">기능 목차로 돌아가기</a>
      </article>
    """


def render_html(document: dict[str, Any]) -> str:
    feature_names = {item["id"]: item["name"] for item in document["features"]}
    features_by_id = {item["id"]: item for item in document["features"]}
    area_options = "".join(
        f'<option value="{area["id"]}">{_e(area["id"])} · {_e(area["title"])}</option>'
        for area in document["areas"]
    )
    gate_options = "".join(
        f'<option value="{gate["id"]}">{_e(gate["title"])}</option>'
        for gate in document["remaining_gates"]
    )
    area_index = "".join(
        f"""
        <article><a href="#{area['id'].lower()}">{_e(area['id'])} · {_e(area['title'])}<span>{_e(area['plain_scope'])}</span></a>
          <ul>{''.join(f'<li><a href="#{feature_id.lower()}">{feature_id} · {_e(feature_names[feature_id])}</a></li>' for feature_id in area['feature_ids'])}</ul>
        </article>
        """
        for area in document["areas"]
    )
    area_sections = "".join(
        f"""
        <section class="area" id="{area['id'].lower()}" data-area-section>
          <header class="area-header"><p class="eyebrow">{_e(area['id'])}</p><h2>{_e(area['title'])}</h2><p>{_e(area['plain_scope'])}</p></header>
          {''.join(_render_feature(features_by_id[feature_id], feature_names) for feature_id in area['feature_ids'])}
        </section>
        """
        for area in document["areas"]
    )
    decision_rows = "".join(
        f"<tr><td>{_raw_e(item['decision_id'])}</td><td>{_e(item['title'])}</td><td>{_raw_e(item['register_status_before_review'])}<br><small>검토 전 상태</small></td><td>{_feature_links(item['affected_feature_ids'], feature_names)}</td><td>{_raw_e(', '.join(item['feature_policy_refs']))}<br><small>기능 정책 묶음 수준</small></td></tr>"
        for item in document["decision_catalog"]
    )
    static = f"""
      <section class="hero" id="document-top">
        <p class="eyebrow">WalkSafe · 설계 기준 검토 단계</p>
        <h1>{_e(document['metadata']['title'])}</h1>
        <p class="subtitle">{_e(document['metadata']['subtitle'])}</p>
        <p class="lead">{_e(document['metadata']['purpose'])}</p>
        <div class="state-strip">
          <span class="state-pill">정책 답변 반영 완료</span>
          <span class="state-pill">설계 기준 공식 승인 전</span>
          <span class="state-pill">출시 불가</span>
          <span class="state-pill">0~6 정식 산출물 미작성</span>
        </div>
        <dl class="document-meta">
          <div><dt>문서 ID</dt><dd translate="no">{_raw_e(document['metadata']['document_id'])}</dd></div>
          <div><dt>버전</dt><dd translate="no">{_raw_e(document['metadata']['document_version'])}</dd></div>
          <div><dt>기준일</dt><dd>{_raw_e(document['metadata']['as_of'])}</dd></div>
          <div><dt>기능 수</dt><dd>{document['summary']['feature_count']}개</dd></div>
        </dl>
      </section>

      <section class="report-section" id="reading-guide">
        <div class="section-heading"><div><p class="eyebrow">먼저 확인</p><h2>이 문서가 말하는 것과 말하지 않는 것</h2></div></div>
        <p class="notice"><strong>현재 의미:</strong> 기능 정책의 충돌은 해소됐습니다. 그러나 이 문서를 만드는 것만으로 설계 기준으로 공식 승인한 버전(기준선), 구현 완료, 안전 검증, 출시 허가가 되지는 않습니다.</p>
        <div class="reading-grid">{''.join(f'<article class="card"><h3>{_e(item["title"])}</h3><p>{_e(item["text"])}</p></article>' for item in document['reading_guide'])}</div>
        <div class="metrics">
          <div><strong>{document['summary']['feature_count']}</strong><span>기능</span></div>
          <div><strong>{document['summary']['review_count']}</strong><span>반영한 검토결정</span></div>
          <div><strong>{document['summary']['implementation_revalidation_feature_count']}</strong><span>구현 재검증 기능</span></div>
          <div><strong>{document['summary']['remaining_gate_count']}</strong><span>공통 미실행 확인</span></div>
        </div>
        <h3>수정 요청은 이렇게 적으면 됩니다</h3>
        <p><code>FP-019 &gt; 구체적인 작동 규칙 &gt; “바꾸고 싶은 현재 문장”을 “원하는 문장”으로 변경</code>처럼 기능 ID, 위치, 원하는 내용을 적어 주세요. 용어집을 따로 읽지 않아도 본문 문장 자체가 이해되도록 작성했습니다.</p>
      </section>

      <section class="report-section screen-only" id="policy-review">
        <div class="section-heading"><div><p class="eyebrow">검토 결과 저장</p><h2>공통 정책 9개와 기능 54개를 확인해 주세요</h2></div><span class="badge pending" id="review-progress">63개 중 0개 검토</span></div>
        <p>각 공통 정책과 기능 카드 아래에서 ‘이대로 확정·수정 필요·보류’ 중 하나를 고르고, 필요한 경우 메모를 적으세요. 선택해도 화면 위치나 펼침 상태는 바뀌지 않습니다. 이 결과는 설계 기준의 공식 승인 자체가 아니라 수정·승인을 위한 입력입니다.</p>
        <div class="two-col">
          <label for="reviewer-name">검토자 이름<input id="reviewer-name" name="reviewer_name" type="text" autocomplete="name" placeholder="예: 프로젝트 책임자"></label>
          <div><strong>자동저장:</strong> 이 브라우저에 현재 문서 지문과 함께 저장됩니다.<p id="review-status"></p></div>
        </div>
        <div class="toolbar">
          <button type="button" id="export-review">검토 결과 파일 저장</button>
          <label for="import-review-file">기존 검토 결과 파일 불러오기<input id="import-review-file" type="file" accept="application/json,.json"></label>
          <button type="button" id="clear-review">검토 입력 전체 지우기</button>
        </div>
      </section>

      <section class="report-section" id="system-flows">
        <div class="section-heading"><div><p class="eyebrow">전체 연결</p><h2>기능들이 함께 작동하는 흐름</h2></div><span class="badge">{len(document['end_to_end_flows'])}개 흐름</span></div>
        <div class="flow-grid">{''.join(f'<article class="flow-card"><p class="flow-id">{_raw_e(flow["id"])}</p><h3>{_e(flow["title"])}</h3><p>{_e(flow["summary"])}</p><p>{_feature_links(list(dict.fromkeys(flow["feature_ids"])), feature_names)}</p></article>' for flow in document['end_to_end_flows'])}</div>
        <article class="notice">
          <h3>물체 탐지는 혼자 안전 행동을 결정하지 않습니다</h3>
          <p><a href="#fp-021">FP-021 영상 사전검사</a> → <a href="#fp-019">FP-019 물체 후보 관측</a> → <a href="#fp-021">FP-021 사후검사·거리 근거</a> → <a href="#fp-020">FP-020 위험 단계와 행동 결정</a> → <a href="#fp-027">FP-027 음성·진동 안내</a> 순서로 작동합니다. 모델 학습·교체와 출시는 <a href="#fp-037">FP-037</a>~<a href="#fp-039">FP-039</a>, <a href="#fp-049">FP-049</a>~<a href="#fp-051">FP-051</a>에서 따로 통제합니다.</p>
        </article>
      </section>

      <section class="report-section" id="reviewed-cross-policies">
        <div class="section-heading"><div><p class="eyebrow">이전 질문지 적용 내역</p><h2>이미 검토한 전체·여러 기능 공통 정책</h2></div><span class="badge policy">{len(document['reviewed_cross_feature_policies'])}개</span></div>
        <p>GP 7개와 SP 15개는 앞선 76개 답변에서 이미 검토한 내용이라 다시 선택하게 하지 않습니다. 현재 적용 문장과 영향 기능, 보존한 후속 담당 메모를 확인할 수 있습니다.</p>
        <div class="common-grid">{''.join(_render_cross_policy(item, feature_names) for item in document['reviewed_cross_feature_policies'])}</div>
      </section>

      <section class="report-section" id="common-policies">
        <div class="section-heading"><div><p class="eyebrow">모든 관련 기능에 우선 적용</p><h2>기능을 가로지르는 확정 정책</h2></div><span class="badge policy">{len(document['common_policies'])}개</span></div>
        <p>아래 정책은 관련 기능 카드의 과거 설명보다 우선합니다. 서버와 휴대전화 용량, 원본과 경로 사본처럼 이름이 비슷해도 서로 다른 대상을 섞지 않습니다.</p>
        <div class="common-grid">{''.join(_render_common_policy(item, feature_names) for item in document['common_policies'])}</div>
      </section>

      <section class="report-section" id="remaining-gates">
        <div class="section-heading"><div><p class="eyebrow">정책과 증거 분리</p><h2>아직 실행하지 않은 공통 확인</h2></div><span class="badge pending">5건 모두 미실행</span></div>
        <p>이 항목들은 정책 문서 작성을 막지는 않지만, 해당 기능의 구현 완료나 출시 가능 판정 전에는 반드시 닫아야 합니다.</p>
        <div class="gate-grid">{''.join(_render_gate(item, feature_names) for item in document['remaining_gates'])}</div>
      </section>

      <section class="report-section" id="feature-index">
        <div class="section-heading"><div><p class="eyebrow">18개 영역 · 54개 기능</p><h2>기능별 정책 찾기</h2></div></div>
        <div class="filter-panel">
          <label for="feature-search">기능·정책 문장 검색<input id="feature-search" name="feature_search" type="search" autocomplete="off" placeholder="예: 객체 탐지, 자동신고, 원본 삭제…"></label>
          <label for="area-filter">기능 영역<select id="area-filter" name="area_filter"><option value="all">전체 영역</option>{area_options}</select></label>
          <label for="gate-filter">남은 공통 확인<select id="gate-filter" name="gate_filter"><option value="all">전체</option><option value="none">공통 확인 없음</option>{gate_options}</select></label>
          <button type="button" id="clear-filters">검색조건 지우기</button>
        </div>
        <p class="filter-result" id="filter-result" aria-live="polite"></p>
        <div class="area-index">{area_index}</div>
      </section>

      <div id="feature-report">{area_sections}</div>

      <section class="report-section trace-only" id="decision-catalog">
        <div class="section-heading"><div><p class="eyebrow">추적 부록</p><h2>135개 결정과 기능 정책 연결</h2></div><span class="badge">연결 {document['summary']['decision_feature_edge_count']}건</span></div>
        <p class="notice"><strong>주의:</strong> 아래 상태는 종합 검토 전 결정대장의 상태입니다. 현재 정책 반영 결과나 승인 상태를 뜻하지 않으며, 정식 결정대장은 기준선 승인 단계에서 갱신합니다.</p>
        <details><summary>결정 추적표 펼치기</summary><div class="details-body"><div class="table-wrap" tabindex="0" role="region" aria-label="135개 결정 추적표"><table class="trace-table">
          <caption>검토 전 결정대장 135개와 현재 기능 정책 묶음의 영향 관계</caption>
          <thead><tr><th scope="col">결정 ID</th><th scope="col">제목</th><th scope="col">검토 전 상태</th><th scope="col">영향 기능</th><th scope="col">기능 정책 묶음</th></tr></thead>
          <tbody>{decision_rows}</tbody>
        </table></div></div></details>
      </section>

      <section class="report-section" id="next-step">
        <div class="section-heading"><div><p class="eyebrow">검토 뒤 진행</p><h2>이 문서 다음 단계</h2></div></div>
        <ol>{''.join(f'<li>{_e(item)}</li>' for item in document['handoff']['next_steps'])}</ol>
        <p class="notice"><strong>승인 경계:</strong> 사용자가 이 문서를 검토하고 정책 기준선 승인 기록을 남기기 전에는 0~6 정식 산출물의 확정본을 만들지 않습니다.</p>
        <details class="trace-only"><summary>문서 결속정보</summary><div class="details-body">
          <p class="trace-id">정책 resolution 내용 지문: {_raw_e(document['resolution_content_sha256'])}</p>
          <p class="trace-id">문서 입력 결속 지문: {_raw_e(document['source_binding_sha256'])}</p>
          <p class="trace-id">문서 내용 지문: {_raw_e(document['document_content_sha256'])}</p>
        </div></details>
      </section>
    """
    template = TEMPLATE_PATH.read_text(encoding="utf-8")
    _require(template.count("__STATIC_REPORT__") == 1, "template needs one static report placeholder")
    _require(template.count("__REPORT_DATA__") == 1, "template needs one report data placeholder")
    embedded = json.dumps(document, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")
    rendered = template.replace("__STATIC_REPORT__", static).replace("__REPORT_DATA__", embedded)
    _require("__STATIC_REPORT__" not in rendered and "__REPORT_DATA__" not in rendered, "template placeholder remains")
    return rendered


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _write_or_check(path: Path, content: bytes, *, check: bool) -> None:
    if check:
        _require(path.is_file(), f"generated output is missing: {path.relative_to(REPO_ROOT)}")
        _require(path.read_bytes() == content, f"generated output is stale: {path.relative_to(REPO_ROOT)}")
    else:
        path.write_bytes(content)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if generated outputs are missing or stale")
    args = parser.parse_args(argv)
    try:
        document = build_document()
        rendered_html = render_html(document)
        _write_or_check(JSON_OUTPUT_PATH, _json_bytes(document), check=args.check)
        _write_or_check(HTML_OUTPUT_PATH, rendered_html.encode("utf-8"), check=args.check)
    except (
        FeaturePolicyDocumentError,
        resolver.PolicyResolutionValidationError,
        OSError,
        KeyError,
        TypeError,
    ) as error:
        print(f"WalkSafe feature policy document failed: {error}", file=sys.stderr)
        return 1
    if args.check:
        print(
            "WalkSafe feature policy document check passed: "
            f"{document['summary']['feature_count']} features, "
            f"{document['summary']['decision_count']} decisions, "
            f"{document['summary']['remaining_gate_count']} remaining gates"
        )
    else:
        print(JSON_OUTPUT_PATH.relative_to(REPO_ROOT))
        print(HTML_OUTPUT_PATH.relative_to(REPO_ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
