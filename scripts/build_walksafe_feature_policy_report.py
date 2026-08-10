#!/usr/bin/env python3
"""Build the standalone WalkSafe feature-policy comprehensive review report."""

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
    from scripts import build_walksafe_effective_baseline as effective
except ModuleNotFoundError:  # Direct execution from the scripts directory.
    import build_walksafe_effective_baseline as effective  # type: ignore[no-redef]


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
INTERVIEW_DIR = REPO_ROOT / "docs" / "control" / "decision-interview"
FRAGMENT_DIR = INTERVIEW_DIR / "comprehensive-report-fragments"
FRAGMENT_PATHS = (
    FRAGMENT_DIR / "walksafe-policy-proposals-fp-001-018.json",
    FRAGMENT_DIR / "walksafe-policy-proposals-fp-019-036.json",
    FRAGMENT_DIR / "walksafe-policy-proposals-fp-037-054.json",
)
POLICY_PATH = INTERVIEW_DIR / "walksafe-feature-policy-effective-candidate.json"
REGISTER_PATH = INTERVIEW_DIR / "walksafe-effective-decision-register.json"
TEMPLATE_PATH = INTERVIEW_DIR / "feature-policy-comprehensive-template.html"
PROPOSALS_OUTPUT_PATH = (
    INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-proposals.json"
)
HTML_OUTPUT_PATH = (
    INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-review-20260719.html"
)

FRAGMENT_SCHEMA = "walksafe.comprehensive-policy-proposal-fragment.v1"
PROPOSALS_SCHEMA = "walksafe.comprehensive-policy-proposals.v1"
REPORT_SCHEMA = "walksafe.comprehensive-policy-report.v1"
EXPECTED_FEATURE_IDS = [f"FP-{index:03d}" for index in range(1, 55)]
EXPECTED_RANGES = (("FP-001", "FP-018"), ("FP-019", "FP-036"), ("FP-037", "FP-054"))
FINALIZATION_TYPES = {
    "OWNER_APPROVAL": "프로젝트 책임자가 선택",
    "ENGINEERING_REVIEW": "개발 설계를 검토해 확정",
    "MEASUREMENT": "실제 기기 시험 결과로 확정",
    "LEGAL_PRIVACY_REVIEW": "법률·개인정보 검토 후 확정",
    "SECURITY_REVIEW": "보안 검토 후 확정",
    "ACCESSIBILITY_REVIEW": "접근성 검토와 실제 사용자 시험 후 확정",
    "OPERATIONS_REVIEW": "운영 가능성과 대응 절차를 검토해 확정",
    "EXTERNAL_SERVICE_REVIEW": "외부 서비스 조건과 실제 연동 시험 후 확정",
}
FEATURE_KEYS = {
    "feature_id",
    "policy_conclusion",
    "inputs",
    "outputs",
    "design_rules",
    "prohibited_behaviors",
    "verification_scenarios",
    "open_item_proposals",
}
PROPOSAL_KEYS = {
    "source_open_item",
    "easy_question",
    "recommended_answer",
    "why",
    "tradeoff",
    "finalized_by",
    "until_finalized",
    "changes_current_policy",
    "change_warning",
}
UNEXPLAINED_TECHNICAL_TERM = re.compile(
    r"(?i)(?:queue|runtime|frame|fallback|session|token|timeout|confidence|"
    r"threshold|rollback|forward\s+fix|\bUAT\b|\bRTO\b|\bRPO\b|\bTTC\b|"
    r"\bSTT\b|\bTTS\b|\bIdP\b|\bMFA\b|\bAPI\b|\bDB\b|\bAPK\b|\bAAB\b|"
    r"\bCSV\b|\bIMU\b|\bDepth\b|\bclass\b|\bhash\b|telemetry|manifest|"
    r"gateway|rate\s+limit|retry|redaction|opt-in|dataset|registry|offline|"
    r"background|debug\s+build|OS\s+kill|crash)"
)
POLICY_STATUS_LABELS = {
    "CONFIRMED": "정책 확정",
    "CONFIRMED_WITH_OPEN_DETAILS": "큰 방향 확정·세부 기준안 검토 중",
    "CONDITIONAL": "시험 또는 전문 검토 후 확정",
    "CONFLICTING": "서로 맞지 않는 정책이 있어 재결정 필요",
    "UNRESOLVED": "결정 필요",
}
PERMISSION_LABELS = {
    "REQUIRED": "반드시 필요",
    "CONDITIONAL": "특정 상황에서만 필요",
    "OPTIONAL": "선택",
}
RESPONSIBILITY_LABELS = {
    "owner_decision": "프로젝트 책임자가 답변함",
    "already_confirmed": "기존 근거로 확인됨",
    "engineering_proposal": "개발 설계안 필요",
    "measurement_gate": "실제 측정 필요",
    "expert_review": "전문가 검토 필요",
    "generated_evidence": "실행 증거 생성 필요",
}
RESOLUTION_LABELS = {
    "RESOLVED": "현재 결정문 있음",
    "PENDING_SOURCE_NORMALIZATION": "과거 자료의 뜻을 한 문장으로 다시 확인해야 함",
    "PENDING_PROPOSAL": "설계 기준안 검토 전",
    "PENDING_MEASUREMENT": "실제 측정 전",
    "PENDING_EXPERT_REVIEW": "전문가 검토 전",
    "PENDING_EVIDENCE": "실행 증거 생성 전",
}
EASY_REPLACEMENTS = (
    (r"Google\s+Play", "구글 플레이"),
    (r"안드로이드\s+네이티브\s+앱", "안드로이드 전용 앱"),
    (r"네이티브\s+앱", "전용 앱"),
    (r"탐지\s+클래스", "찾을 물체 종류"),
    (r"로컬\s+경로", "휴대폰 내부 경로"),
    (r"로컬\s+저장", "휴대폰 내부 저장"),
    (r"완전\s+오프라인", "인터넷 없이"),
    (r"오프라인\s+동작", "인터넷 없이 작동"),
    (r"오프라인", "인터넷이 끊긴 상태"),
    (r"릴리스", "배포 버전"),
    (r"로컬", "휴대폰 내부"),
    (r"업로드", "서버 전송"),
    (r"세션", "로그인·보행 연결"),
    (r"unresolved", "아직 결정되지 않은 항목"),
    (r"지원\s+기기와\s+Web/PWA\s+legacy", "지원 기기와 과거 웹앱"),
    (r"기존\s+Web/PWA", "기존 웹앱"),
    (r"과거\s+Web/PWA", "과거 웹앱"),
    (r"Web/PWA\s+legacy", "과거 웹앱"),
    (r"외부\s+API\s+key", "외부 서비스 인증정보"),
    (r"Android\s+(?:APK·AAB|APK|AAB)", "안드로이드 앱 설치·스토어 배포 파일"),
    (r"Android\s+TalkBack", "안드로이드 화면 읽기 기능"),
    (r"IMU\s+(?:sensor|센서)", "움직임 센서"),
    (r"지원\s+matrix", "지원 조합표"),
    (r"독립\s+validation", "독립 검증"),
    (r"호출어\s+KWS", "호출어 인식"),
    (r"운영\s+telemetry", "운영 상태 기록"),
    (r"임시\s+buffer", "임시 저장공간"),
    (r"로그인\s+token", "로그인 증표"),
    (r"로그인\s+IdP", "로그인 인증 서비스"),
    (r"원격\s+wipe", "원격 삭제"),
    (r"P0\s+장애", "가장 심각한 장애"),
    (r"수치\s+threshold", "수치 기준"),
    (r"처리\s+receipt", "처리 확인서"),
    (r"전체\s+detector\s+p50\s+507ms", "전체 물체 탐지 측정의 절반은 0.507초 이하"),
    (r"모델\s+p50\s+279ms·detector\s+p50\s+507ms", "모델 측정의 절반은 0.279초 이하·전체 물체 탐지 측정의 절반은 0.507초 이하"),
    (r"camera\s+FPS", "카메라가 초당 처리하는 영상 수"),
    (r"p50·p95·p99\s+end-to-end\s+지연", "보통·느린 95%·느린 99% 상황의 전체 안내 지연"),
    (r"core_only", "핵심 명령만"),
    (r"voice_only", "음성만"),
    (r"access·refresh\s+token", "접속용·재발급용 로그인 증표"),
    (r"expand[-→]migrate[-→]contract|expand→migrate→contract", "새 구조 추가→자료 옮기기→옛 구조 제거"),
    (r"load·warmup·sample\s+inference\s+readiness", "모델 불러오기·첫 실행 준비·예제 입력 정상 작동 확인"),
    (r"sample\s+inference\s+readiness", "예제 입력 정상 작동 확인"),
    (r"model\s+registry", "모델 관리대장"),
    (r"model\s+generation", "모델 세대"),
    (r"model\s+runtime", "모델 실행 도구"),
    (r"idempotency\s+key(?:\(중복 생성 방지키\))?", "중복 요청 식별값"),
    (r"request\s+ID", "요청 식별번호"),
    (r"commit\s+receipt", "저장 완료 확인서"),
    (r"server\s+receipt", "서버 처리 확인서"),
    (r"focus\s+order", "화면 읽기·이동 순서"),
    (r"intent\s+mapping", "음성 명령 연결표"),
    (r"pause/resume", "일시중지·재개"),
    (r"safe\s+pause", "안전 일시중지"),
    (r"failure\s+recovery", "장애 복구"),
    (r"persistent\s+failure", "계속되는 장애"),
    (r"incident\s+response", "보안사고 대응"),
    (r"secret\s+store", "비밀 인증정보 보관소"),
    (r"object\s+storage", "영상·음성 같은 큰 파일 저장소"),
    (r"rate\s+limit", "짧은 시간의 요청 횟수 제한"),
    (r"forward[-\s]+fix", "되돌리지 않고 다음 수정 버전을 배포하는 방식"),
    (r"known-good", "검증된 정상 버전"),
    (r"end-to-end", "입력부터 사용자 안내까지의 전체"),
    (r"free-form", "정해진 형식이 없는"),
    (r"no-op", "아무 동작도 하지 않음"),
    (r"best-effort", "가능한 범위에서만 처리"),
    (r"fail-close", "문제가 있으면 안전하게 차단"),
    (r"pass/fail", "통과·실패"),
    (r"read/write", "읽기·쓰기"),
    (r"Wi[- ‑]Fi", "와이파이"),
    (r"Depth\s+기능", "거리 측정 기능"),
    (r"Android", "안드로이드"),
    (r"TMAP", "티맵 경로 서비스"),
    (r"GPS", "위치 정보"),
    (r"Web/PWA", "과거 웹앱"),
    (r"PWA", "설치형 웹앱"),
    (r"모델\s+load", "모델 불러오기"),
    (r"model\s+load", "인공지능 모델 불러오기"),
    (r"API\s+key", "외부 서비스 인증정보"),
    (r"signing\s+key", "앱 서명 인증키"),
    (r"encryption\s+key", "암호화 인증키"),
    (r"idempotency", "같은 요청의 중복 처리를 막는 식별값"),
    (r"sample\s+inference", "예제 입력으로 모델이 정상 작동하는지 확인"),
    (r"warmup", "첫 실행 준비"),
    (r"readiness", "사용 준비 상태"),
    (r"load", "불러오기"),
    (r"epoch270", "270번째 학습 단계"),
    (r"on-device|온디바이스", "휴대폰 내부"),
    (r"metric\s+distance", "미터 단위로 정확성이 확인된 거리값"),
    (r"debug\s+build", "개발 전용 앱"),
    (r"OS\s+kill", "안드로이드가 앱을 강제로 종료하는 상황"),
    (r"SpeechRecognizer", "안드로이드 기본 음성 인식 기능"),
    (r"TalkBack", "안드로이드 화면 읽기 기능"),
    (r"PostgreSQL/PostGIS", "계정·위치 정보를 저장하는 공간 데이터베이스"),
    (r"APK·AAB|APK|AAB", "안드로이드 앱 설치·스토어 배포 파일"),
    (r"rollback", "이전 정상 버전으로 되돌리기"),
    (r"runtime", "모델 실행 도구"),
    (r"freshness|fresh", "결과가 너무 오래되지 않았는지"),
    (r"confidence", "모델 판단 확신도"),
    (r"threshold", "판정 기준"),
    (r"telemetry", "운영 상태 기록"),
    (r"manifest", "함께 써야 하는 버전 목록"),
    (r"gateway", "서버의 단일 접속 창구"),
    (r"redaction", "민감정보 가림"),
    (r"opt-in", "선택 동의"),
    (r"dataset", "학습자료 묶음"),
    (r"registry", "관리대장"),
    (r"reconciliation", "누락·불일치 자료 맞추기"),
    (r"index", "검색 색인"),
    (r"background", "앱이 화면에 보이지 않는 상태"),
    (r"offline", "인터넷이 끊긴 상태"),
    (r"fallback", "대신 사용할 안전한 동작"),
    (r"detector", "객체 탐지 작업"),
    (r"upload", "전송"),
    (r"receipt", "확인서"),
    (r"commit", "저장 완료 확인"),
    (r"rollout", "단계적 배포"),
    (r"metadata", "부가정보"),
    (r"pending", "처리 대기"),
    (r"active", "현재 사용 중인"),
    (r"generation", "모델 세대"),
    (r"stale", "너무 오래되어 믿을 수 없는"),
    (r"secret", "비밀 인증정보"),
    (r"revocation", "권한 무효화"),
    (r"release", "배포 버전"),
    (r"local", "휴대폰 내부"),
    (r"gate", "다음 단계 전 필수 확인"),
    (r"client", "사용자·관리자 앱"),
    (r"buffer", "임시 저장공간"),
    (r"storage", "저장소"),
    (r"voice", "음성"),
    (r"legacy", "과거 방식"),
    (r"fake", "가짜"),
    (r"config", "설정"),
    (r"backend", "서버 내부 처리"),
    (r"chunk", "큰 파일을 나눈 조각"),
    (r"scheduler", "예약 실행 기능"),
    (r"STOP", "안전정지"),
    (r"AI", "인공지능"),
    (r"cloud", "클라우드 서비스"),
    (r"schema", "데이터 구조"),
    (r"endpoint", "서버 접속 주소"),
    (r"timeout", "응답을 기다리는 최대 시간"),
    (r"retry", "다시 시도"),
    (r"queue", "처리·전송 대기 목록"),
    (r"frame", "영상 한 장"),
    (r"session", "로그인·보행 연결"),
    (r"token", "로그인 증표"),
    (r"class", "탐지 종류"),
    (r"hash", "내용 변경 확인값"),
    (r"crash", "앱이 오류로 갑자기 종료되는 상황"),
    (r"UAT", "실제 사용자 검증"),
    (r"RTO", "서비스 복구 목표시간"),
    (r"RPO", "허용 가능한 데이터 손실 범위"),
    (r"TTC", "충돌까지 남은 예상 시간"),
    (r"STT", "음성인식"),
    (r"TTS", "음성 안내"),
    (r"IdP", "로그인 인증 서비스"),
    (r"MFA", "비밀번호 외 수단을 한 번 더 확인하는 관리자 인증"),
    (r"IMU", "움직임 센서"),
    (r"Depth", "거리 측정 기능"),
    (r"API", "통신 규칙"),
    (r"DB", "데이터베이스"),
    (r"CSV", "표 형태 파일"),
    (r"FPS", "초당 처리하는 카메라 화면 수"),
    (r"p50", "측정값의 절반이 이 안에 드는 값"),
    (r"p95", "느린 쪽을 포함한 95%가 이 안에 드는 값"),
    (r"p99", "느린 쪽을 포함한 99%가 이 안에 드는 값"),
    (r"TTL", "보관 유효시간"),
    (r"SEV1", "가장 심각한 운영 장애"),
    (r"SEV", "장애 심각도"),
    (r"E2E", "처음부터 끝까지 잇는 전체 흐름"),
    (r"P0", "가장 심각한 장애"),
    (r"P1", "중대한 장애"),
    (r"SLA", "처리기한 약속"),
    (r"RBAC", "역할별 접근권한"),
    (r"TLS", "통신구간 암호화"),
    (r"KMS", "암호화키 관리서비스"),
    (r"SAST", "소스코드 보안검사"),
    (r"SCA", "외부 코드 취약점검사"),
    (r"DAST", "실행 중인 서비스 보안검사"),
    (r"SBOM", "프로그램 구성요소·라이선스 목록"),
    (r"TFLite", "휴대폰용 인공지능 모델 형식"),
    (r"OpenAPI", "서버 통신 규칙 문서"),
    (r"GeoJSON", "지도정보 파일"),
    (r"KWS", "호출어 인식"),
    (r"UX", "사용 흐름"),
    (r"UI", "화면"),
    (r"SMS", "문자메시지"),
    (r"OTP", "일회용 인증번호"),
    (r"OS", "운영체제"),
    (r"Production", "정식 운영"),
    (r"Beta", "제한 공개 시험"),
    (r"MVP", "최소 기능 제품"),
    (r"Delta", "이번 추가 답변"),
    (r"native", "전용 앱"),
    (r"iOS", "아이폰 운영체제"),
    (r"model", "모델"),
    (r"alert", "경보"),
    (r"pause", "일시중지"),
    (r"resume", "재개"),
    (r"backup", "백업"),
    (r"restore", "복원"),
    (r"quota", "사용량 한도"),
    (r"proxy", "중계 서버"),
    (r"migration", "데이터 구조 변경"),
    (r"request", "요청"),
    (r"field", "데이터 항목"),
    (r"trace", "처리 흐름 기록"),
    (r"intent", "음성 명령 의미"),
    (r"focus", "선택 위치"),
    (r"export", "내보내기"),
    (r"backoff", "점차 간격을 늘려 재시도"),
    (r"job", "자동 작업"),
    (r"store", "보관소"),
    (r"unit", "단위"),
    (r"module", "기능 묶음"),
    (r"update", "업데이트"),
    (r"dashboard", "현황판"),
    (r"retention", "보존기간"),
    (r"capacity", "용량"),
    (r"cost", "비용"),
    (r"runbook", "장애 대응 절차"),
    (r"log", "기록"),
    (r"metric", "측정값"),
    (r"incident", "보안·운영 사고"),
    (r"escalation", "상위 담당자에게 넘기기"),
    (r"monitor", "상태 감시"),
    (r"thermal", "발열"),
    (r"battery", "배터리"),
    (r"warning", "경고"),
    (r"response", "대응"),
    (r"rehearsal", "모의훈련"),
    (r"workflow", "처리 흐름"),
    (r"lifecycle", "생명주기"),
    (r"history", "이력"),
    (r"route", "경로"),
    (r"identity", "고유 식별정보"),
    (r"budget", "예산"),
    (r"label", "읽을 이름"),
    (r"heading", "제목 구조"),
    (r"text", "문자 변환 결과"),
    (r"action", "사용자 동작"),
    (r"dialog", "확인창"),
    (r"reviewed", "검토 완료"),
    (r"resolved/rejected", "반영·거절"),
    (r"resumable", "중단 뒤 이어 보내기"),
    (r"pinning", "인증서 고정"),
    (r"pin", "사용 버전 고정"),
    (r"bucket", "파일 보관 구역"),
    (r"contract", "옛 구조 제거"),
    (r"expand", "새 구조 추가"),
    (r"migrate", "자료 옮기기"),
    (r"write", "쓰기"),
    (r"exponential", "점차 간격을 늘리는"),
    (r"safety", "안전"),
    (r"Data", "자료"),
    (r"scan", "검사"),
    (r"case", "사례"),
    (r"instrumentation", "실제 앱 실행"),
    (r"sandbox", "시험용 격리 환경"),
    (r"pre-launch", "출시 전"),
    (r"screenshot", "화면 캡처"),
    (r"dropped", "빠진"),
    (r"pattern", "유형"),
    (r"performance", "성능"),
    (r"source", "소스코드"),
    (r"server", "서버"),
    (r"image", "실행 이미지"),
    (r"note", "설명서"),
    (r"build", "시험할 앱 파일"),
    (r"artifact", "산출물"),
    (r"maintenance", "유지보수"),
    (r"near-miss", "사고 직전 상황"),
    (r"network", "통신망"),
    (r"database", "데이터베이스"),
    (r"billing", "요금"),
    (r"tier", "등급"),
    (r"mode", "모드"),
    (r"deploy", "배포"),
    (r"KPI", "목표 측정값"),
    (r"key", "인증키"),
    (r"logout", "로그아웃"),
    (r"validation", "독립 검증"),
    (r"code", "코드"),
    (r"new", "새"),
    (r"submitted", "제출됨"),
    (r"object", "객체"),
    (r"test", "시험"),
    (r"reconcile", "자료 맞추기"),
    (r"Web", "웹"),
    (r"capability", "기능 사용 가능 여부"),
    (r"matrix", "지원 조합표"),
    (r"ARCore", "안드로이드 거리 측정 도구"),
    (r"refresh", "재발급용"),
    (r"control", "제어 기능"),
    (r"transcript", "음성 변환문"),
    (r"Demo", "시연"),
    (r"app", "앱"),
    (r"camera", "카메라"),
    (r"postmortem", "장애 사후분석"),
    (r"inventory", "보유 목록"),
    (r"audience", "대상 사용자"),
    (r"Keystore", "휴대폰 보안키 저장소"),
    (r"Gradle/Kotlin", "안드로이드 앱 코드"),
    (r"task", "작업"),
    (r"preview", "미리보기"),
    (r"STARTING", "준비 중"),
    (r"PAUSED", "일시중지"),
    (r"unified", "통합"),
    (r"sensor", "센서"),
    (r"detection", "탐지"),
    (r"smoke", "기본 작동 확인"),
    (r"steering", "이동 방향 결정"),
    (r"TactileRoutePolicy", "점자블록 경로 규칙"),
    (r"engine", "처리 기능"),
    (r"semantic", "화면 읽기 의미정보"),
    (r"cooldown", "같은 알림을 쉬는 시간"),
    (r"timestamp", "발생시각"),
    (r"capture", "촬영"),
    (r"lineage", "자료 생성·변경 이력"),
    (r"old", "이전"),
    (r"license", "이용 허가조건"),
    (r"set", "묶음"),
    (r"sequence-safe", "순서가 꼬이지 않게"),
    (r"sequence", "처리 순서"),
    (r"train", "학습"),
    (r"seed", "같은 결과를 재현하는 시작값"),
    (r"pipeline", "처리 과정"),
    (r"HTTPS", "암호화 통신"),
    (r"versioned", "버전별"),
    (r"version", "버전"),
    (r"owner", "책임자"),
    (r"jobs", "자동 작업"),
    (r"APIs", "통신 규칙"),
    (r"wipe", "원격 삭제"),
    (r"certificate", "인증서"),
    (r"trigger", "시작 조건"),
    (r"SemVer", "세 자리 버전 규칙"),
    (r"private", "비공개"),
    (r"link", "연결"),
    (r"event", "사건"),
    (r"core", "핵심"),
    (r"sampling", "일부만 기록"),
    (r"drop", "기록 생략"),
    (r"(?<!Google\s)Play", "Google Play"),
)

SHARED_PROPOSAL_KEYS = {
    "decision_id",
    "easy_question",
    "recommended_answer",
    "why",
    "tradeoff",
    "finalized_by",
    "until_finalized",
    "changes_current_policy",
    "change_warning",
}
SHARED_DECISION_PROPOSALS = (
    {
        "decision_id": "CD-PRODUCT-RELEASE",
        "easy_question": "첫 공개 사용자를 어떤 순서로 늘릴까요?",
        "recommended_answer": "구글 플레이 내부 시험, 사전에 초대한 비공개 시험, 안전계획에 동의한 소수 대상 사용자 시험, 정식 공개의 일부 사용자, 단계적 전체 공개 순서로 넓힌다. 단계별 인원과 관찰기간은 안전사고·차단 결함·접근성·개인정보·복구 결과와 지원 가능 인력을 보고 프로젝트 책임자가 미리 승인한다. 앞 단계가 모두 통과해도 자동으로 늘리지 않는다.",
        "why": "문제가 생겼을 때 영향을 받는 사람 수를 제한하고, 실제 사용 결과를 다음 공개 단계에 반영할 수 있다.",
        "tradeoff": "바로 전체 공개하는 것보다 시간이 더 들고 시험 참여자를 따로 모집해야 한다.",
        "finalized_by": "OWNER_APPROVAL",
        "until_finalized": "대상 사용자 시험 정책을 다시 승인하기 전에는 개발팀 내부 시험만 허용하고 일반 사용자에게 정식 서비스라고 안내하지 않는다.",
        "changes_current_policy": True,
        "change_warning": "대상 사용자 시험과 일반 사용자 단계 배포는 현재의 개발자만 시험하는 정책을 바꾸는 권고다. FP-049·FP-050·FP-051의 사용자시험과 배포 정책을 함께 검토하고 프로젝트 책임자가 다시 승인하기 전에는 현재 정책을 유지한다.",
    },
    {
        "decision_id": "CD-SAFETY-POSITION",
        "easy_question": "WalkSafe만으로 보행할 수 있다고 언제 안내할 수 있나요?",
        "recommended_answer": "독립 보행 지원은 제품 목표로만 두고 지금은 기존 이동지원 수단과 함께 쓰는 보조수단으로 설명한다. 대체 표현은 학습·개발과 분리된 검토자와 실제 대상 사용자가 환경·장착·지원 기기별 안전시험을 수행해 잘못 알림·놓침·안내 지연·사용자 행동을 통과한 뒤에만 검토한다. 시험 뒤에도 남는 위험과 공식 문구를 프로젝트 책임자가 별도로 승인해야 한다.",
        "why": "목표와 현재 입증된 안전 수준을 구분해야 사용자가 검증되지 않은 보장을 믿고 위험한 상황에 놓이지 않는다.",
        "tradeoff": "제품 홍보 문구가 보수적으로 보일 수 있고 더 많은 현장시험이 필요하다.",
        "finalized_by": "OWNER_APPROVAL",
        "until_finalized": "기존 이동지원 수단을 대체한다고 표현하지 않고 보조수단의 한계를 매번 알린다.",
        "changes_current_policy": True,
        "change_warning": "독립 검토자와 실제 대상 사용자 안전시험을 필수로 두는 기준은 현재의 개발자만 시험하는 정책을 바꾸는 권고다. FP-006·FP-049·FP-050과 함께 다시 승인해야 한다.",
    },
    {
        "decision_id": "CD-USER-AGE",
        "easy_question": "만 14세 이상 청소년의 보호자 확인과 동의를 어떻게 처리할까요?",
        "recommended_answer": "첫 버전은 만 14세 이상만 가입시키고 만 18세 미만은 보호자 확인과 동의를 요구한다. 첫 버전 가입자는 본인 휴대전화 번호가 있어야 하며 보호자 번호로 대신 가입할 수 없다. 본인 번호와 보호자 번호는 용도를 나눠 저장하고 보호자의 신원과 관계를 승인된 방법으로 확인한다. 동의 문서의 버전·시각·철회·재동의·탈퇴 기록을 남긴다. 정확한 생년월일 대신 필요한 연령 구간만 확인하는 방법을 법률·개인정보·구글 플레이 검토로 정하고, 검토 결과가 현재 연령 범위를 바꾸면 프로젝트 책임자가 다시 승인한다.",
        "why": "청소년을 받는다는 현재 범위를 지키면서 불필요한 개인정보 수집과 보호자 동의 누락을 막는다.",
        "tradeoff": "보호자 확인 서비스 비용과 법률·Google Play 검토가 추가된다.",
        "finalized_by": "LEGAL_PRIVACY_REVIEW",
        "until_finalized": "검토가 끝날 때까지 만 18세 미만의 실제 가입을 열지 않는다.",
        "changes_current_policy": False,
        "change_warning": "",
    },
    {
        "decision_id": "CD-EXCLUDED-FEATURES",
        "easy_question": "보호자 위치 추적이나 넘어짐 탐지를 다시 넣으려면 무엇이 필요한가요?",
        "recommended_answer": "이번 버전에서는 두 기능을 제외한다. 다시 넣으려면 별도 변경요청을 만들고, 사용자 동의·개인정보·오알림·놓침·비상대응 요구사항과 시험을 새로 승인한 뒤 새 정책 기준선을 만든다.",
        "why": "이름만 기능 목록에 되살아나고 안전·개인정보 설계가 빠지는 일을 막는다.",
        "tradeoff": "나중에 다시 포함할 때 별도의 검토와 일정이 필요하다.",
        "finalized_by": "OWNER_APPROVAL",
        "until_finalized": "보호자 위치 추적과 넘어짐 탐지는 화면·서버·데이터 구조에 만들지 않는다.",
        "changes_current_policy": False,
        "change_warning": "",
    },
    {
        "decision_id": "CD-DEPTH-UNSUPPORTED",
        "easy_question": "정확한 거리 기능이 없는 휴대폰에서는 어디까지 안내할까요?",
        "recommended_answer": "시작 점검에서 거리 기능 미지원을 확인한다. 제한모드에서는 ‘거리 확인 기능을 사용할 수 없어 승인된 물체 종류만 안내합니다. 이 안내는 안전 판단이나 이동 지시가 아닙니다’라고 말하고, 종류별 시험에서 ‘거리 없이 안내 가능’으로 승인된 물체만 알린다. 거리 없는 결과는 위험 해제, 멈춤 해제, 정지 명령, 좌우 이동, 자동신고의 근거로 절대 쓰지 않는다. 제조사나 증강현실 거리 기능은 기기별 거리 오차·호환성·주야간·날씨·이동 물체 시험을 모두 통과한 경우에만 승인한다. 정상 보행 중 거리 기능이 고장 나면 위험 판단을 즉시 멈추고, 전환 자체의 현장시험을 통과한 경우에만 사용자 확인 뒤 제한모드로 바꾼다.",
        "why": "거리 없는 물체 이름을 정확한 위험 판단으로 오해하는 일을 막는다.",
        "tradeoff": "지원 기기가 줄고 제한모드에서는 안내 범위가 좁아진다.",
        "finalized_by": "MEASUREMENT",
        "until_finalized": "허용 물체 목록·제한 문구·금지 판단·사용자 이해도·전환 시험이 모두 승인되기 전에는 거리 기능 미지원 기기에서 카메라 탐지를 시작하지 않는다.",
        "changes_current_policy": False,
        "change_warning": "",
    },
    {
        "decision_id": "CD-IDENTITY-VERIFY",
        "easy_question": "휴대전화 문자 확인은 어떤 실패·복구 규칙으로 운영할까요?",
        "recommended_answer": "전달 성공률·화면 읽기 접근성·악용 방지·업체 장애·비용을 비교하고 보안검토를 통과한 문자 업체를 고른다. 인증번호 만료시간, 재전송 대기, 실패횟수와 잠금시간은 전화번호·계정·기기·통신 출처별 악용시험으로 정한다. 인증번호 원문은 기록하지 않고 만료 즉시 버린다. 전화번호와 시도 결과의 보존기간은 법률·개인정보 검토로 정하며, 번호 변경 뒤 옛 번호는 복구 유예가 끝나면 계정 연결에서 지우고 계정삭제 때 승인된 예외 외에는 삭제한다. 번호 변경은 현재 로그인과 새 번호를 모두 확인하고 기존 번호를 잃은 경우에는 별도 계정복구 절차를 쓴다. 청소년 본인 번호와 보호자 번호는 용도·권한·보존기간을 나눠 저장한다.",
        "why": "무제한 재시도와 오래된 인증번호 악용을 막으면서 실제 사용자가 복구할 경로를 남긴다.",
        "tradeoff": "보안·접근성·업체 장애·비용 시험이 추가되고 과도한 제한은 정상 사용자의 가입을 늦출 수 있다.",
        "finalized_by": "LEGAL_PRIVACY_REVIEW",
        "until_finalized": "보안·접근성·법률·개인정보 검토가 끝날 때까지 시험용 번호 외 실제 문자 가입을 열지 않고 번호를 운영자가 임의로 바꾸지 않는다.",
        "changes_current_policy": False,
        "change_warning": "",
    },
    {
        "decision_id": "CD-CONCURRENT-WALK",
        "easy_question": "한 계정으로 두 휴대폰에서 동시에 보행을 시작해도 되나요?",
        "recommended_answer": "여러 기기 로그인은 허용하되 한 계정의 활성 보행은 한 대만 허용한다. 서버가 보행 권한을 한 대에만 발급하고 동시 시작 경쟁과 통신 단절을 시험한다. 새 기기의 요청이 기존 활성 보행을 갑자기 끊어서는 안 된다. 서버 확인이 안 되면 두 번째 기기만 시작을 막고 기존 기기는 승인된 연결 끊김 정책을 따른다.",
        "why": "두 기기의 경로·신고·상태가 서로 덮어쓰거나 사용자가 다른 기기의 안내를 받는 일을 막는다.",
        "tradeoff": "기기 교체 때 기존 기기를 종료할 수 없으면 잠시 기다리거나 계정복구 절차가 필요하다.",
        "finalized_by": "OWNER_APPROVAL",
        "until_finalized": "동시에 두 번째 보행을 허용하지 않고 기기 전환 실패를 사용자에게 알린다.",
        "changes_current_policy": False,
        "change_warning": "",
    },
    {
        "decision_id": "CD-REMOTE-LOGOUT",
        "easy_question": "분실 기기와 비밀번호 변경 때 기존 로그인을 어떻게 끊을까요?",
        "recommended_answer": "기기 목록에는 사용자가 알아볼 이름과 최근 사용시각을 표시하고, 특정 기기를 끊기 전 현재 기기에서 본인을 다시 확인한다. 사용자가 현재 기기에서 비밀번호를 정상 변경하면 현재 기기에는 새 로그인 정보를 발급하고 다른 기기는 끊는다. 계정 잠금·도난·보안사건 때는 모든 기기를 끊고 다시 확인받는다. 관리자는 승인된 보안사건에서만 기기를 끊을 수 있으며 이유·담당자·시각을 기록하고 사용자에게 알린다. 서버는 끊긴 로그인 정보를 즉시 거부하고 연결이 없던 기기는 다음 연결 때 거부한다. 활성 보행 기기를 끊으면 원인을 즉시 안내하고 안전정지한 뒤 로그인 화면으로 이동한다.",
        "why": "기기별 로그아웃이라는 현재 선택을 유지하면서 분실·도난 때 남은 기기의 장기 접속을 막는다.",
        "tradeoff": "보안사건 때 정상 기기에서도 다시 로그인해야 할 수 있다.",
        "finalized_by": "SECURITY_REVIEW",
        "until_finalized": "기기 식별·본인 재확인·끊김 전파·활성 보행 안전정지를 시험하고 프로젝트 책임자가 다시 승인하기 전에는 사용자 선택 기기별 로그아웃만 적용하며 장기 로그인을 정식 출시하지 않는다.",
        "changes_current_policy": True,
        "change_warning": "현재의 사용자 선택 기기별 로그아웃에 관리자 해제와 보안사건 때 전체 기기 해제를 추가하는 권고다. 보안시험 뒤에도 프로젝트 책임자가 다시 승인하기 전에는 현재 정책을 유지한다.",
    },
    {
        "decision_id": "CD-REBOOT-BEHAVIOR",
        "easy_question": "재부팅이나 앱의 갑작스러운 종료 뒤 보행을 어떻게 다시 시작하나요?",
        "recommended_answer": "재부팅 뒤에는 사용자가 앱을 직접 열어야 한다. 로그인 유지 여부를 확인한 다음 권한·카메라·거리·모델·음성 상태를 다시 점검하고, 사용자가 확인한 뒤 새 보행을 시작한다. 지원 기기마다 설치 직후, 업데이트 직후, 재부팅, 앱 오류 종료, 운영체제 강제 종료를 따로 시험하고 이전 위험·경로를 자동 복원하지 않는지 확인한다.",
        "why": "사용자가 모르는 사이 카메라와 보행 안내가 다시 켜지는 일을 막고 실패 원인을 구분할 수 있다.",
        "tradeoff": "재부팅 뒤 사용자가 직접 앱을 열고 확인해야 한다.",
        "finalized_by": "ENGINEERING_REVIEW",
        "until_finalized": "어떤 종료 뒤에도 보행을 자동 재개하지 않고 준비 점검부터 다시 시작한다.",
        "changes_current_policy": False,
        "change_warning": "",
    },
    {
        "decision_id": "CD-VOICE-COMMAND-SCOPE",
        "easy_question": "첫 버전에서 꼭 알아들어야 할 음성 명령은 무엇인가요?",
        "recommended_answer": "핵심 10개는 목적지 찾기, 다음 후보, 장소 선택, 길안내 시작, 일시중지, 재개, 길안내 종료, 현재 상태, 수동 신고, 도움말로 정한다. 로그아웃과 계정삭제는 핵심 10개 밖의 별도 요청으로 받되, 현재 보행을 안전하게 멈춘 뒤 화면 읽기가 가능한 확인 화면과 추가 본인 확인을 거쳐야 실행한다. 대상 사용자가 실제 말투·소음환경에서 명령과 확인문구를 모두 수행하는 접근성 시험을 통과한 뒤 프로젝트 책임자가 목록을 승인한다.",
        "why": "버튼 없는 화면에서 필요한 행동이 빠지거나 개발자가 임의의 명령을 추가하는 일을 막는다.",
        "tradeoff": "명령마다 다른 말투·소음·잘못 인식 시험과 확인 문구가 필요하다.",
        "finalized_by": "OWNER_APPROVAL",
        "until_finalized": "정식 목록에 없는 말은 실행하지 않고, 계정 요청은 안내만 하며 실제 변경은 하지 않는다.",
        "changes_current_policy": True,
        "change_warning": "실제 대상 사용자 접근성 시험을 필수로 두는 기준은 현재의 개발자만 시험하는 정책을 바꾸는 권고다. FP-026·FP-049·FP-050과 함께 다시 승인해야 한다.",
    },
    {
        "decision_id": "CD-AUTO-REPORT-TIMING",
        "easy_question": "자동신고는 언제 어떤 통신망으로 보내고 얼마나 보관할까요?",
        "recommended_answer": "보행 중에는 암호화해 휴대폰의 신고 전용 대기 목록에 보관하고 보행 종료 뒤 보낸다. 와이파이를 기본으로 쓰며 이동통신망은 예상 데이터량·요금을 알리고 별도로 선택한 경우에만 허용한다. 해외 이동통신에서는 다시 선택받는다. 같은 신고를 한 번만 보내기 위한 번호·만료·재전송·최대 용량을 두고 한계에 닿으면 새 후보 생성을 멈춰 사용자에게 알린다. 신고 원본의 보존기간과 기관 전달 범위는 법률·개인정보 검토로 정한다. 신고 처리와 모델 개선용 원본 재사용 동의는 분리하며, 재사용에 따로 동의하지 않은 원본은 학습자료로 넘기지 않는다.",
        "why": "안전 신고와 선택적인 학습자료가 같은 전송·보존 규칙에 섞이는 일을 막는다.",
        "tradeoff": "이동통신 데이터가 들 수 있고 신고·학습자료 대기 목록을 따로 만들어야 한다.",
        "finalized_by": "LEGAL_PRIVACY_REVIEW",
        "until_finalized": "보행 중 전송하지 않고 신고와 학습자료를 분리 보관하며, 목적·기간·별도 동의가 정해지지 않은 원본은 수집하거나 학습에 쓰지 않는다.",
        "changes_current_policy": True,
        "change_warning": "신고 원본의 모델 개선 재사용을 별도 선택 동의로 분리하는 기준은 현재의 통합 동의와 광범위한 원본 학습 활용 방향을 바꾸는 권고다. FP-013·FP-031·FP-034·FP-036·FP-046과 함께 다시 승인해야 한다.",
    },
    {
        "decision_id": "CD-ADMIN-FUNCTION-SCOPE",
        "easy_question": "관리자 앱에 꼭 필요한 기능과 권한은 어디까지인가요?",
        "recommended_answer": "첫 범위는 신고 검수·내부 상태 변경·관리자의 수동 기관 전달, 장애 현황과 감사기록 조회로 제한한다. 계정삭제는 요청 접수와 처리상태 조회만 허용하고 실제 삭제는 별도 서버 절차가 수행한다. 모델과 배포는 상태만 조회한다. 사용자 대신 계정 변경, 모델 학습·승인, 서버 직접 조작, 관리자 권한 부여·회수, 자기 권한 변경은 관리자 앱에서 제외한다. 보안검토로 화면별 최소 자료와 열람 사유 기록을 먼저 승인한다.",
        "why": "‘전체 운영 관리’라는 말만으로 민감정보 열람과 위험한 변경 권한이 무제한으로 늘어나는 일을 막는다.",
        "tradeoff": "업무별 권한표와 화면 목록을 먼저 만들고 유지해야 한다.",
        "finalized_by": "OWNER_APPROVAL",
        "until_finalized": "목록과 권한이 승인되지 않은 관리자 동작은 만들거나 사용하지 않는다.",
        "changes_current_policy": False,
        "change_warning": "",
    },
    {
        "decision_id": "CD-UPLOAD-NETWORK",
        "easy_question": "학습자료는 와이파이가 오래 없거나 저장공간이 부족할 때 어떻게 하나요?",
        "recommended_answer": "학습자료는 사용자가 멈춰 있고 와이파이에 연결됐을 때만 보낸다. 충전 여부·배터리·발열 기준은 지원 기기 시험으로 정한다. 와이파이가 오래 없으면 법률·개인정보 검토로 승인한 보관기간과 용량에 따라 새 수집을 먼저 멈추고 사용자에게 알린다. 기간이 끝난 미전송 학습자료는 승인된 순서로 삭제하고 삭제 사실을 기록한다. 자동신고는 SP-11에 따라 와이파이를 기본으로 하고 사용자가 따로 선택한 경우에만 이동통신망을 쓴다.",
        "why": "선택적인 학습자료 전송이 보행 중 통신·배터리·저장공간을 빼앗는 일을 막는다.",
        "tradeoff": "와이파이를 쓰지 않는 사용자의 학습자료는 전송되지 않고 만료될 수 있다.",
        "finalized_by": "LEGAL_PRIVACY_REVIEW",
        "until_finalized": "기기시험과 보존·삭제 검토가 끝나기 전에는 보행 종료 뒤 와이파이와 충전이 모두 확인된 작은 시험자료만 보내고 정식 학습자료 수집은 열지 않는다.",
        "changes_current_policy": True,
        "change_warning": "미전송 학습자료에 유한한 보관기간과 만료삭제를 두는 기준은 서버 확인 전까지 계속 보관하는 현재 방향을 바꾸는 권고다. FP-035·FP-036·FP-046과 함께 다시 승인해야 한다.",
    },
    {
        "decision_id": "CD-FAILURE-RECOVERY",
        "easy_question": "장애가 끝난 뒤 어떤 확인을 해야 보행을 다시 시작하나요?",
        "recommended_answer": "재개하려는 기능모드에 필요한 항목과 연속 정상 횟수·시간은 반복 장애시험으로 정한다. 앱이 살아 있고 사용자가 직접 일시중지했거나 화면만 잠갔으며 보행번호와 상태가 온전할 때만 같은 보행을 이어갈지 묻는다. 비정상 종료·재부팅·권한 상실·안전정지 뒤에는 새 보행번호로 시작한다. 보존된 목적지가 있고 사용자가 다시 쓰겠다고 확인한 경우에만 새 경로를 요청하고, 목적지가 없으면 승인된 물체 안내만 준비하며 이전 경로를 복원하지 않는다. 안전 핵심 입력이 오래됐거나 믿을 수 없으면 즉시 멈춘다. 온도·배터리·저장공간의 경고선·정지선·안정 회복시간은 FP-045의 지원 기기 장시간 시험으로 정한다. 비핵심 기능만 따로 재시도하는 방식은 FP-043의 변경안이 시험과 재승인을 통과한 뒤에만 쓴다.",
        "why": "잠깐 정상처럼 보인 한 번의 신호만 믿고 반복 장애 속에서 자동 재개하는 일을 막는다.",
        "tradeoff": "복구 뒤 정상 상태를 반복 확인해야 하며 비정상 종료 뒤에는 목적지와 경로를 다시 정해야 한다.",
        "finalized_by": "MEASUREMENT",
        "until_finalized": "정상 여부가 불분명하면 안전정지를 유지하고, 일시중지 외의 복구는 전체 점검과 사용자 확인 뒤 새 보행으로 시작한다.",
        "changes_current_policy": False,
        "change_warning": "",
    },
    {
        "decision_id": "CD-SUPPORT-HOURS",
        "easy_question": "평일 오후 6시 이후와 공휴일의 중대 장애는 누가 대응하나요?",
        "recommended_answer": "평일 오전 9시부터 오후 6시까지는 지정 관리자가 대응한다. 그 밖의 시간에 정식 운영하려면 이름이 지정된 제한권한 비상 대응자, 서로 다른 두 알림경로, 장애 등급별 첫 확인·안전조치·복구 목표, 관리자 계정 잠김 복구를 모의훈련으로 승인한다. 대응자는 장애 확인·새 보행 차단·사용자 공지·증거보존만 할 수 있고 정책 승인·민감자료 대량 조회·권한 부여·새 배포는 할 수 없다. 현재의 이전 버전 복구 금지 정책에서는 안전정지를 유지하고 정상 승인 절차로 수정판을 준비한다. 이전 정상판 복구는 FP-039·FP-051의 정책 변경이 별도로 시험·재승인된 뒤에만 허용한다. 승인된 대기표가 없으면 지원시간 밖 새 보행을 차단한다.",
        "why": "한 명의 관리자에게 연락할 수 없는 시간에도 대응 가능한 것처럼 잘못 약속하지 않으면서 중대 장애의 최소 연락선을 만든다.",
        "tradeoff": "새 비상 대응 역할과 당직 비용이 필요하며, 지정 전에는 지원시간 밖 정식 운영 범위를 제한해야 한다.",
        "finalized_by": "OWNER_APPROVAL",
        "until_finalized": "비상 대응자와 대기표를 승인하기 전에는 평일 오전 9시부터 오후 6시 밖과 공휴일의 새 보행을 차단하고, 장애가 난 활성 사용자는 자동 안전정지와 안내를 따른다.",
        "changes_current_policy": True,
        "change_warning": "현재의 단일 관리자 체계에 제한권한 비상 대응자를 새로 두는 권고다. 역할·금지권한·두 알림경로·장애등급별 목표·계정복구·회수 절차를 시험하고, 이전 정상판 복구는 FP-039·FP-051과 별도로 재승인해야 한다.",
    },
)


class ComprehensiveReportValidationError(ValueError):
    """Raised when comprehensive-report source data violates its contract."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ComprehensiveReportValidationError(message)


def _load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    _require(isinstance(value, dict), f"{path.name} must contain an object")
    return value


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_path(path: Path) -> str:
    return _sha256_bytes(path.read_bytes())


def _text(value: Any, label: str) -> str:
    _require(isinstance(value, str) and bool(value.strip()), f"{label} must be non-empty text")
    return value.strip()


def _text_list(value: Any, label: str, minimum: int = 1) -> list[str]:
    _require(isinstance(value, list), f"{label} must be a list")
    result = [_text(item, f"{label}[{index}]") for index, item in enumerate(value)]
    _require(len(result) >= minimum, f"{label} must contain at least {minimum} items")
    _require(len(result) == len(set(result)), f"{label} contains duplicates")
    return result


def _assert_easy_language(value: str, label: str) -> None:
    match = UNEXPLAINED_TECHNICAL_TERM.search(value)
    _require(match is None, f"{label} contains an unexplained technical term: {match.group(0) if match else ''}")


def _validate_proposal(
    value: Any,
    *,
    feature_id: str,
    index: int,
    expected_open_item: str,
) -> dict[str, Any]:
    label = f"{feature_id}.open_item_proposals[{index}]"
    _require(isinstance(value, dict), f"{label} must be an object")
    _require(set(value) == PROPOSAL_KEYS, f"{label} fields differ: {sorted(set(value) ^ PROPOSAL_KEYS)}")
    _require(value["source_open_item"] == expected_open_item, f"{label} does not match the effective policy open item")
    question = _text(value["easy_question"], f"{label}.easy_question")
    _require(question.endswith("?"), f"{label}.easy_question must be a question")
    for field in ("easy_question", "recommended_answer", "why", "tradeoff", "until_finalized"):
        text = _text(value[field], f"{label}.{field}")
        _assert_easy_language(text, f"{label}.{field}")
    finalized_by = value["finalized_by"]
    _require(finalized_by in FINALIZATION_TYPES, f"{label}.finalized_by is invalid")
    changes = value["changes_current_policy"]
    _require(isinstance(changes, bool), f"{label}.changes_current_policy must be boolean")
    warning = value["change_warning"]
    _require(isinstance(warning, str), f"{label}.change_warning must be text")
    _require(bool(warning.strip()) == changes, f"{label}.change_warning must match changes_current_policy")
    if warning:
        _assert_easy_language(warning, f"{label}.change_warning")
    return copy.deepcopy(value)


def _validate_feature_proposal(
    value: Any,
    *,
    policy_feature: dict[str, Any],
) -> dict[str, Any]:
    feature_id = policy_feature["id"]
    _require(isinstance(value, dict), f"{feature_id} proposal must be an object")
    _require(set(value) == FEATURE_KEYS, f"{feature_id} proposal fields differ: {sorted(set(value) ^ FEATURE_KEYS)}")
    _require(value["feature_id"] == feature_id, f"feature proposal order differs at {feature_id}")
    conclusion = _text(value["policy_conclusion"], f"{feature_id}.policy_conclusion")
    _assert_easy_language(conclusion, f"{feature_id}.policy_conclusion")
    for field, minimum in (
        ("inputs", 1),
        ("outputs", 1),
        ("design_rules", 3),
        ("prohibited_behaviors", 2),
        ("verification_scenarios", 3),
    ):
        items = _text_list(value[field], f"{feature_id}.{field}", minimum)
        for index, item in enumerate(items):
            _assert_easy_language(item, f"{feature_id}.{field}[{index}]")
    open_items = policy_feature["policy"]["unresolved"]
    proposals = value["open_item_proposals"]
    _require(isinstance(proposals, list), f"{feature_id}.open_item_proposals must be a list")
    _require(len(proposals) == len(open_items), f"{feature_id} proposal/open-item count differs")
    result = copy.deepcopy(value)
    result["open_item_proposals"] = [
        _validate_proposal(
            proposal,
            feature_id=feature_id,
            index=index,
            expected_open_item=open_items[index],
        )
        for index, proposal in enumerate(proposals)
    ]
    return result


def _source_binding(path: Path) -> dict[str, str]:
    return {
        "path": path.relative_to(REPO_ROOT).as_posix(),
        "sha256": _sha256_path(path),
    }


def _load_and_validate_fragments(policy: dict[str, Any]) -> list[dict[str, Any]]:
    policy_by_id = {item["id"]: item for item in policy["features"]}
    merged: list[dict[str, Any]] = []
    for path, expected_range in zip(FRAGMENT_PATHS, EXPECTED_RANGES, strict=True):
        fragment = _load_json(path)
        _require(fragment.get("schema_version") == FRAGMENT_SCHEMA, f"{path.name} schema differs")
        _require(
            fragment.get("range") == {"first": expected_range[0], "last": expected_range[1]},
            f"{path.name} range differs",
        )
        features = fragment.get("features")
        _require(isinstance(features, list), f"{path.name}.features must be a list")
        expected_ids = EXPECTED_FEATURE_IDS[
            int(expected_range[0].split("-")[1]) - 1 : int(expected_range[1].split("-")[1])
        ]
        actual_ids = [item.get("feature_id") if isinstance(item, dict) else None for item in features]
        _require(actual_ids == expected_ids, f"{path.name} feature IDs or order differ")
        merged.extend(
            _validate_feature_proposal(item, policy_feature=policy_by_id[item["feature_id"]])
            for item in features
        )
    _require([item["feature_id"] for item in merged] == EXPECTED_FEATURE_IDS, "proposal coverage differs")
    return merged


def _validate_shared_decision_proposals(register: dict[str, Any]) -> list[dict[str, Any]]:
    decisions_with_details = [item for item in register["decisions"] if item["open_details"]]
    decision_by_id = {
        item["canonical_decision_id"]: item
        for item in decisions_with_details
    }
    expected_ids = list(decision_by_id)
    actual_ids = [item.get("decision_id") for item in SHARED_DECISION_PROPOSALS]
    _require(actual_ids == expected_ids, "shared decision proposal IDs or order differ")
    results: list[dict[str, Any]] = []
    for index, value in enumerate(SHARED_DECISION_PROPOSALS, start=1):
        label = f"shared_decision_proposals[{index - 1}]"
        _require(set(value) == SHARED_PROPOSAL_KEYS, f"{label} fields differ")
        decision = decision_by_id[value["decision_id"]]
        question = _text(value["easy_question"], f"{label}.easy_question")
        _require(question.endswith("?"), f"{label}.easy_question must be a question")
        for field in (
            "easy_question",
            "recommended_answer",
            "why",
            "tradeoff",
            "until_finalized",
        ):
            _assert_easy_language(_text(value[field], f"{label}.{field}"), f"{label}.{field}")
        finalized_by = value["finalized_by"]
        _require(finalized_by in FINALIZATION_TYPES, f"{label}.finalized_by is invalid")
        changes = value["changes_current_policy"]
        warning = value["change_warning"]
        _require(isinstance(changes, bool), f"{label}.changes_current_policy must be boolean")
        _require(isinstance(warning, str), f"{label}.change_warning must be text")
        _require(bool(warning.strip()) == changes, f"{label}.change_warning differs")
        if warning:
            _assert_easy_language(warning, f"{label}.change_warning")
        result = copy.deepcopy(value)
        result.update(
            {
                "proposal_id": f"SP-{index:02d}",
                "decision_title": decision["title"],
                "source_open_details": copy.deepcopy(decision["open_details"]),
                "affected_feature_ids": copy.deepcopy(decision["affected_feature_ids"]),
            }
        )
        results.append(result)
    _require(
        sum(len(item["source_open_details"]) for item in results)
        == sum(len(item["open_details"]) for item in decisions_with_details),
        "shared decision open-detail coverage differs",
    )
    return results


def _global_policies() -> list[dict[str, Any]]:
    return [
        {
            "id": "GP-01",
            "title": "서비스의 역할",
            "policy": "WalkSafe는 흰지팡이·안내견·보행훈련을 대신하지 않고, 위험과 이동 방향을 추가로 알려주는 보조수단으로 설계한다.",
            "why": "현재 모델과 현장시험만으로 기존 보행수단을 대체할 안전성이 입증되지 않았다.",
            "feature_ids": ["FP-001", "FP-006", "FP-050"],
            "changes_current_policy": False,
            "change_warning": "",
        },
        {
            "id": "GP-02",
            "title": "안전 판단 우선",
            "policy": "위험안내, 길안내, 음성대화, 자료 전송이 겹치면 위험안내를 먼저 처리한다. 믿을 수 없는 입력으로 안전하다고 말하지 않는다.",
            "why": "편의 기능 때문에 가까운 위험 안내가 늦거나 잘못 해제되는 일을 막는다.",
            "feature_ids": ["FP-019", "FP-020", "FP-026", "FP-043"],
            "changes_current_policy": False,
            "change_warning": "",
        },
        {
            "id": "GP-03",
            "title": "휴대폰 내부 우선 처리",
            "policy": "실시간 카메라 분석과 음성 처리는 가능한 한 휴대폰 안에서 한다. 서버 연결이 필요한 기능은 별도로 표시한다.",
            "why": "통신 지연과 인터넷 단절이 실시간 안전기능에 미치는 영향을 줄인다.",
            "feature_ids": ["FP-019", "FP-025", "FP-027", "FP-040"],
            "changes_current_policy": False,
            "change_warning": "",
        },
        {
            "id": "GP-04",
            "title": "필요한 자료만 수집",
            "policy": "영상·음성·위치는 목적과 보존기간이 승인된 항목만 수집한다. 주변인 정보는 저장 전에 가리는 것을 기본으로 한다.",
            "why": "학습 편의를 이유로 민감한 원본을 무기한 모으는 설계를 막는다.",
            "feature_ids": ["FP-013", "FP-019", "FP-020", "FP-021", "FP-034", "FP-036", "FP-046"],
            "changes_current_policy": True,
            "change_warning": "현재 자료에는 얼굴·번호판을 가리지 않은 원본과 광범위한 장기보존 의도가 있다. 최소 수집·저장 전 가림 원칙으로 바꾸려면 개인정보 검토와 사용자 재승인이 필요하다.",
        },
        {
            "id": "GP-05",
            "title": "접근 가능한 조작",
            "policy": "가입·동의·권한·오류·종료를 포함한 모든 필수 화면은 화면 읽기 기능으로 조작할 수 있어야 한다.",
            "why": "핵심 사용자가 화면을 보지 않고도 서비스 시작과 중단을 스스로 결정할 수 있어야 한다.",
            "feature_ids": ["FP-010", "FP-013", "FP-028", "FP-029", "FP-030"],
            "changes_current_policy": True,
            "change_warning": "현재 답변에서 제외한 글자 확대·명암대비·터치영역도 필수 화면에는 적용하자는 변경 권고다. 접근성 검토와 사용자 재승인이 필요하다.",
        },
        {
            "id": "GP-06",
            "title": "시험 전에는 출시하지 않음",
            "policy": "정책이 정해졌더라도 실제 기기·접근성·현장·보안·개인정보 검토 증거가 없으면 정식 출시로 판정하지 않는다.",
            "why": "문서의 결정과 실제 안전성·구현 완료는 서로 다른 문제다.",
            "feature_ids": ["FP-038", "FP-049", "FP-050", "FP-051"],
            "changes_current_policy": True,
            "change_warning": "독립 모델평가와 실제 시각장애 사용자 검증을 생략하려는 기존 방향을 바꾸는 권고다. 안전 근거 없이 정식 출시하지 않도록 재승인이 필요하다.",
        },
        {
            "id": "GP-07",
            "title": "물체 관측과 위험 행동의 책임 분리",
            "policy": "FP-019는 물체 후보와 관측 근거만 만들고, FP-020만 검증된 거리·움직임·경로를 합쳐 위험 단계와 행동을 정한다. 이동할 공간의 안전성이 별도로 검증되기 전에는 좌우 이동을 지시하지 않고 즉시 행동은 ‘멈추세요. 주변을 확인하세요.’로 제한한다.",
            "why": "한 기능이 관측과 안전 행동을 함께 결정하면 잘못된 거리나 물체 연결이 곧바로 위험한 이동 지시가 될 수 있다.",
            "feature_ids": ["FP-019", "FP-020"],
            "changes_current_policy": True,
            "change_warning": "현재 FP-019 설명은 카메라 탐지 기능 안에서 거리·위험판정·행동 안내까지 수행하는 것으로 되어 있고, FP-020에는 이동할 공간을 따로 확인하지 않은 오른쪽 이동 안내 예시가 있다. 권고안은 FP-019를 물체 후보 관측으로 제한하고 FP-020만 검증된 거리·움직임으로 위험과 행동을 결정하게 하며, 이동할 공간의 안전성이 별도로 검증되기 전에는 좌우 이동 대신 ‘멈추세요. 주변을 확인하세요.’만 안내하도록 바꾼다. 안전시험 뒤 프로젝트 책임자가 다시 승인하기 전에는 현재 정책을 대체하지 않는다.",
        },
    ]


def _system_flow() -> list[dict[str, str]]:
    return [
        {"step": "1", "title": "가입과 동의", "description": "사용자 확인, 보호자 동의가 필요한 경우의 확인, 개인정보·자동신고 동의를 마친다."},
        {"step": "2", "title": "기기 준비 확인", "description": "카메라·위치·마이크·거리 측정·음성 안내가 이 휴대폰에서 쓸 수 있는지 확인한다."},
        {"step": "3", "title": "보행 시작", "description": "로그인 뒤 안전기능을 준비하고, 목적지가 있으면 큰 경로도 함께 받는다."},
        {"step": "4", "title": "위험과 길 안내", "description": "가까운 위험을 먼저 알리고, 그다음 이동 방향과 음성 명령 결과를 안내한다."},
        {"step": "5", "title": "신고와 자료 전송", "description": "손상 점자블록 신고와 동의한 학습자료를 서로 다른 전송 대기 목록으로 관리한다."},
        {"step": "6", "title": "종료와 운영 확인", "description": "보행을 끝내고 저장·전송 결과와 장애를 운영자가 확인한다."},
    ]


def _correct_particle(replacement: str, original: str | None) -> str:
    if not original:
        return ""
    last_hangul = next(
        (character for character in reversed(replacement) if "가" <= character <= "힣"),
        None,
    )
    if last_hangul is None:
        return original
    has_final_consonant = (ord(last_hangul) - ord("가")) % 28 != 0
    if original in {"은", "는"}:
        return "은" if has_final_consonant else "는"
    if original in {"이", "가"}:
        return "이" if has_final_consonant else "가"
    if original in {"을", "를"}:
        return "을" if has_final_consonant else "를"
    if original in {"으로", "로"}:
        final_index = (ord(last_hangul) - ord("가")) % 28
        return "로" if final_index in {0, 8} else "으로"
    return "과" if has_final_consonant else "와"


def _easy(value: Any) -> str:
    result = str(value)
    for pattern, replacement in EASY_REPLACEMENTS:
        expression = (
            rf"(?<![A-Za-z0-9])(?:{pattern})(?![A-Za-z0-9])"
            r"(?P<particle>으로|로|[은는이가을를과와])?"
        )
        result = re.sub(
            expression,
            lambda match, replacement=replacement: replacement
            + _correct_particle(replacement, match.group("particle")),
            result,
            flags=re.IGNORECASE,
        )
    result = result.replace("거리 측정 기능 기능", "거리 측정 기능")
    result = result.replace("중복 요청 식별값(중복 생성 방지키)", "중복 요청 식별값")
    result = result.replace("승인된 검증된 정상 버전", "승인된 정상 버전")
    result = result.replace("대신 사용할 안전한 동작한다", "안전한 대체 동작으로 전환한다")
    result = result.replace("확인 사용 준비 상태", "사용 준비 확인")
    result = result.replace("확인 확인", "확인")
    return result


def _e(value: Any) -> str:
    return html.escape(_easy(value), quote=True)


def _raw_e(value: Any) -> str:
    return html.escape(str(value), quote=True)


def _list_html(values: list[Any], empty_text: str) -> str:
    if not values:
        return f'<p class="empty-value">{_e(empty_text)}</p>'
    return "<ul>" + "".join(f"<li>{_e(item)}</li>" for item in values) + "</ul>"


def _feature_link_list_html(feature_ids: list[str]) -> str:
    if not feature_ids:
        return '<p class="empty-value">직접 연결된 다른 기능이 없다.</p>'
    return "<ul>" + "".join(
        f'<li><a href="#{feature_id.lower()}">{feature_id}</a></li>'
        for feature_id in feature_ids
    ) + "</ul>"


def _linked_decision_html(decisions: list[dict[str, Any]]) -> str:
    cards = []
    for decision in decisions:
        statement = decision["decision_statement"] or (
            "아직 하나의 최종 정책문으로 확정되지 않았다. "
            + RESPONSIBILITY_LABELS[decision["responsibility_type"]]
            + " 절차로 닫아야 한다."
        )
        cards.append(
            '<article class="decision-card">'
            f'<p class="item-id">{_raw_e(decision["decision_id"])}</p>'
            f'<h4>{_e(decision["title"])}</h4>'
            f'<p>{_e(statement)}</p>'
            '<div class="badge-row decision-badges">'
            f'<span class="badge">{_e(RESPONSIBILITY_LABELS[decision["responsibility_type"]])}</span>'
            f'<span class="badge">{_e(RESOLUTION_LABELS[decision["resolution_status"]])}</span>'
            '</div>'
            '<h5>이 결정에 남아 있는 추가 확인</h5>'
            + _list_html(decision["open_details"], "추가 확인사항이 없다.")
            + '</article>'
        )
    return "".join(cards)


def _review_controls(review_id: str, legend: str) -> str:
    return f"""
      <fieldset class="review-box" data-review-for="{review_id}">
        <legend>{_e(legend)}</legend>
        <div class="review-options">
          <label><input type="radio" name="review-{review_id}" value="accept"> <span><strong>이 정책대로 진행</strong><small>추천 기준을 그대로 문서 작성 기준으로 사용</small></span></label>
          <label><input type="radio" name="review-{review_id}" value="revise"> <span><strong>일부 수정 후 진행</strong><small>아래에 바꿀 정책과 원하는 내용을 작성</small></span></label>
          <label><input type="radio" name="review-{review_id}" value="hold"> <span><strong>결정을 보류</strong><small>추가 조사나 합의 전까지 기준선에 넣지 않음</small></span></label>
        </div>
        <label class="note-label" for="note-{review_id}">수정할 내용 또는 검토 메모</label>
        <textarea id="note-{review_id}" data-note-for="{review_id}" rows="4" aria-describedby="review-error-{review_id}" aria-invalid="false" placeholder="예: {review_id}은 추천안 대신 …로 정합니다."></textarea>
        <p class="review-error" id="review-error-{review_id}" hidden>수정 또는 보류를 선택했으므로 정책 ID와 원하는 변경 또는 보류 이유를 적어 주세요.</p>
      </fieldset>
    """


def _shared_proposal_card(item: dict[str, Any]) -> str:
    warning = ""
    if item["changes_current_policy"]:
        warning = (
            '<div class="change-warning"><strong>현재 정책을 바꾸는 권고</strong>'
            f'<p>{_e(item["change_warning"])}</p></div>'
        )
    return f"""
      <article class="shared-proposal-card" id="{item['proposal_id'].lower()}">
        <p class="item-id">{item['proposal_id']} · 여러 기능에 함께 적용</p>
        <h3>{_e(item['easy_question'])}</h3>
        <div class="recommended-answer"><span>추천하는 기준</span><p>{_e(item['recommended_answer'])}</p></div>
        <dl class="proposal-reasoning">
          <div><dt>이 기준을 추천하는 이유</dt><dd>{_e(item['why'])}</dd></div>
          <div><dt>감수해야 할 점</dt><dd>{_e(item['tradeoff'])}</dd></div>
          <div><dt>최종 확정 방법</dt><dd>{_e(FINALIZATION_TYPES[item['finalized_by']])}</dd></div>
          <div><dt>확정 전 적용할 안전한 임시 규칙</dt><dd>{_e(item['until_finalized'])}</dd></div>
        </dl>
        {warning}
        <details class="source-detail"><summary>이 기준으로 닫는 기존 추가 확인사항</summary>{_list_html(item['source_open_details'], '추가 확인사항이 없다.')}</details>
        <p class="id-list">관련 기능: {_raw_e(', '.join(item['affected_feature_ids']))}</p>
        {_review_controls(item['proposal_id'], '이 공통 정책을 어떻게 처리할까요?')}
      </article>
    """


def _shared_feature_references(items: list[dict[str, Any]]) -> str:
    if not items:
        return ""
    cards = []
    for item in items:
        warning = '<span class="change-chip">현재 정책 변경</span>' if item["changes_current_policy"] else ""
        cards.append(
            '<article class="shared-reference">'
            f'<p class="item-id">{item["proposal_id"]} · 공통 세부 기준 {warning}</p>'
            f'<h4>{_e(item["easy_question"])}</h4>'
            '<p>이 기능에도 같은 기준이 적용됩니다. 공통 정책 카드에서 한 번만 검토합니다.</p>'
            f'<a href="#{item["proposal_id"].lower()}">추천 내용과 검토란으로 이동</a>'
            '</article>'
        )
    return (
        '<section class="shared-reference-section"><div class="section-heading">'
        '<div><p class="eyebrow">여러 기능에 함께 적용</p><h4>이 기능과 연결된 공통 세부 기준</h4></div>'
        f'<span>{len(items)}개</span></div>{"".join(cards)}</section>'
    )


def _render_feature(feature: dict[str, Any]) -> str:
    feature_id = feature["id"]
    proposal = feature["proposal"]
    policy = feature["policy"]
    guidance = feature["user_guidance"]
    boundary = feature["execution_boundary"]
    data = feature["data_handling"]
    permission_items = [
        f"{item['name']} — {item['plain_reason']} ({PERMISSION_LABELS.get(item['requirement'], item['requirement'])})"
        for item in feature["required_permissions"]
    ]
    proposal_cards = []
    changed_proposal_ids = []
    for index, item in enumerate(proposal["open_item_proposals"], start=1):
        proposal_id = f"{feature_id}-P{index:02d}"
        warning = ""
        if item["changes_current_policy"]:
            changed_proposal_ids.append(proposal_id)
            warning = (
                '<div class="change-warning"><strong>현재 정책을 바꾸는 권고</strong>'
                f'<p>{_e(item["change_warning"])}</p>'
                '<p>시험·전문 검토가 끝나도 프로젝트 책임자가 다시 승인하기 전에는 현재 정책이 유지됩니다.</p></div>'
            )
        proposal_cards.append(
            f"""
            <article class="proposal-card" id="{proposal_id.lower()}">
              <p class="item-id">{proposal_id}</p>
              <h4>{_e(item['easy_question'])}</h4>
              <div class="recommended-answer"><span>추천하는 기준</span><p>{_e(item['recommended_answer'])}</p></div>
              <dl class="proposal-reasoning">
                <div><dt>이 기준을 추천하는 이유</dt><dd>{_e(item['why'])}</dd></div>
                <div><dt>감수해야 할 점</dt><dd>{_e(item['tradeoff'])}</dd></div>
                <div><dt>최종 확정 방법</dt><dd>{_e(FINALIZATION_TYPES[item['finalized_by']])}</dd></div>
                <div><dt>확정 전 적용할 안전한 임시 규칙</dt><dd>{_e(item['until_finalized'])}</dd></div>
              </dl>
              {warning}
              <button class="quote-policy" type="button" data-policy-id="{proposal_id}">이 항목을 수정 의견에 넣기</button>
            </article>
            """
        )
    change_notes = feature["change_notes"] + feature["review_correction_notes"]
    searchable = _raw_e(json.dumps(feature, ensure_ascii=False, sort_keys=True))
    shared_proposals = feature["shared_policy_proposals"]
    changed_shared_ids = [item["proposal_id"] for item in shared_proposals if item["changes_current_policy"]]
    changed_global_ids = [
        item["id"]
        for item in feature["global_policy_proposals"]
        if item["changes_current_policy"]
    ]
    all_changed_ids = changed_global_ids + changed_proposal_ids + changed_shared_ids
    conclusion_warning = ""
    if all_changed_ids:
        conclusion_warning = (
            '<div class="conclusion-change"><strong>현재 정책 변경이 포함된 추천 결론입니다.</strong>'
            f'<p>변경 항목: {_raw_e(", ".join(all_changed_ids))}. 재승인 전에는 아래 ‘현재 자료’가 기준이지만, 이 보고서 전체가 승인 전이므로 정식 출시나 실제 보행에 사용하지 않습니다.</p></div>'
        )
    return f"""
      <article class="feature" id="{feature_id.lower()}" data-feature-id="{feature_id}"
        data-area-id="{feature['area_id']}" data-policy-status="{policy['overall_status']}"
        data-search="{searchable}">
        <header class="feature-header">
          <div>
            <p class="eyebrow">{feature_id} · 기능 {feature['order']}</p>
            <h3>{_e(feature['name'])}</h3>
          </div>
          <div class="badge-row">
            <span class="badge status-{policy['overall_status'].lower()}">{_e(POLICY_STATUS_LABELS[policy['overall_status']])}</span>
            <span class="review-badge" id="badge-{feature_id.lower()}">검토 전</span>
          </div>
        </header>

        <div class="conclusion">
          <span>이 보고서가 추천하는 최종 기준</span>
          <p>{_e(proposal['policy_conclusion'])}</p>
          {conclusion_warning}
        </div>

        <section class="plain-panel purpose-panel"><h4>사용자에게 필요한 이유</h4><p>{_e(feature['user_purpose'])}</p></section>

        <section class="io-flow" aria-label="입력과 결과">
          <div><h4>무엇을 받아서 작동하나</h4>{_list_html(proposal['inputs'], '별도 입력이 없다.')}</div>
          <span class="flow-arrow" aria-hidden="true">→</span>
          <div><h4>무엇을 만들어 내나</h4>{_list_html(proposal['outputs'], '별도 결과가 없다.')}</div>
        </section>

        <section class="proposal-section" aria-labelledby="proposal-title-{feature_id.lower()}">
          <div class="section-heading">
            <div><p class="eyebrow">설계 전에 닫을 세부 기준</p><h4 id="proposal-title-{feature_id.lower()}">쉬운 질문과 추천안</h4></div>
            <span>{len(proposal_cards)}개</span>
          </div>
          {''.join(proposal_cards)}
        </section>

        {_shared_feature_references(shared_proposals)}

        <details class="policy-block recommended-rules" data-print-main open>
          <summary>추천 기준의 작동·금지·시험 규칙</summary>
          <div class="detail-grid three">
            <section><h4>설계자가 따라야 할 작동 순서와 규칙</h4>{_list_html(proposal['design_rules'], '별도 설계 규칙이 없다.')}</section>
            <section><h4>설계에서 해서는 안 되는 일</h4>{_list_html(proposal['prohibited_behaviors'], '별도 금지사항이 없다.')}</section>
            <section><h4>완료 확인 방법</h4>{_list_html(proposal['verification_scenarios'], '별도 시험 기준이 없다.')}</section>
          </div>
          <section class="related-features"><h4>직접 연결된 다른 기능</h4>{_feature_link_list_html(feature['related_feature_ids'])}</section>
        </details>

        <details class="policy-block current-source-block">
          <summary>비교용: 현재 자료에 적힌 내용</summary>
          <div class="current-source-warning"><strong>아래는 기존 답변과 자료의 현재 상태입니다.</strong><p>위 추천 기준과 다르면 구현 기준으로 사용하지 마세요. 차이를 확인하고 추천안을 승인·수정하기 위한 비교 자료입니다.</p></div>
          <div class="intro-grid">
            <section class="plain-panel"><h4>현재 자료의 기능 설명</h4><p>{_e(feature['plain_summary'])}</p></section>
            <section class="plain-panel"><h4>현재 자료의 사용자 목적</h4><p>{_e(feature['user_purpose'])}</p></section>
          </div>
          <div class="detail-grid">
            <section><h4>현재 시작 조건</h4>{_list_html(feature['start_conditions'], '별도 시작 조건이 없다.')}</section>
            <section><h4>현재 자료의 정상 작동 순서</h4>{_list_html(feature['normal_flow'], '정상 작동 순서가 아직 작성되지 않았다.')}</section>
          </div>
          <div class="detail-grid four">
            <section><h4>현재 화면 안내</h4>{_list_html(guidance['screen'], '화면 안내가 필요하지 않다.')}</section>
            <section><h4>현재 음성 안내</h4>{_list_html(guidance['speech'], '음성 안내가 필요하지 않다.')}</section>
            <section><h4>현재 진동 안내</h4>{_list_html(guidance['vibration'], '진동 안내가 필요하지 않다.')}</section>
            <section><h4>현재 휴대폰 권한</h4>{_list_html(permission_items, '이 기능만을 위한 별도 권한이 없다.')}</section>
          </div>
          <div class="detail-grid three">
            <section><h4>현재 휴대폰 처리</h4>{_list_html(boundary['on_device'], '휴대폰에서 직접 처리하는 일이 없다.')}</section>
            <section><h4>현재 서버 처리</h4>{_list_html(boundary['server'], '서버에서 처리하는 일이 없다.')}</section>
            <section><h4>현재 외부 서비스·기관 처리</h4>{_list_html(boundary['external'], '외부 서비스나 기관을 사용하지 않는다.')}</section>
          </div>
          <div class="detail-grid four">
            <section><h4>현재 휴대폰 저장</h4>{_list_html(data['stored_on_device'], '휴대폰에 따로 저장하지 않는다.')}</section>
            <section><h4>현재 서버 전송</h4>{_list_html(data['sent_to_server'], '서버로 따로 보내지 않는다.')}</section>
            <section><h4>현재 휴대폰 삭제</h4>{_list_html(data['delete_from_device'], '휴대폰 삭제 규칙이 아직 없다.')}</section>
            <section><h4>현재 서버 보존</h4>{_list_html(data['server_retention'], '서버 보존 대상이 없다.')}</section>
          </div>
          <section><h4>현재 오류·제한·복구</h4>{_list_html(feature['failure_behavior'], '별도 실패 동작이 아직 없다.')}</section>
          <div class="detail-grid three">
            <section><h4>현재 답변으로 정한 내용</h4>{_list_html(policy['confirmed'], '아직 확정된 내용이 없다.')}</section>
            <section><h4>현재 자료에서 조건이 붙는 내용</h4>{_list_html(policy['conditional'], '별도 조건부 정책이 없다.')}</section>
            <section><h4>현재 자료에서 서로 맞지 않는 내용</h4>{_list_html(policy['conflicts'], '현재 확인된 직접 충돌은 없다.')}</section>
          </div>
          <section class="implementation"><h4>현재 구현과 목표 정책의 차이</h4><p>{_e(feature['implementation']['plain_status'])}</p></section>
          {_list_html(change_notes, '이번 답변 통합으로 별도 변경된 문장은 없다.')}
        </details>

        <details class="trace-block">
          <summary>연결된 전체 결정·추가 확인·근거 보기</summary>
          <div class="decision-list">{_linked_decision_html(feature['linked_decisions'])}</div>
          <p><strong>결정:</strong> {_raw_e(', '.join(feature['decision_refs']))}</p>
          <p><strong>근거:</strong> {_raw_e(', '.join(feature['source_refs']))}</p>
          <p><strong>현재 구현 근거:</strong> {_raw_e(', '.join(feature['implementation']['evidence_refs']) or '직접 연결된 구현 증거 없음')}</p>
          <p><strong>반영할 산출물:</strong> {_raw_e(', '.join(feature['affected_deliverables']))}</p>
        </details>

        {_review_controls(feature_id, '이 기능 정책을 어떻게 처리할까요?')}
        <a class="back-link" href="#feature-index">기능 목차로 돌아가기</a>
      </article>
    """


def _render_static_report(report: dict[str, Any]) -> str:
    feature_by_id = {item["id"]: item for item in report["features"]}
    flow = "".join(
        f'<li><span>{_e(item["step"])}</span><div><strong>{_e(item["title"])}</strong><p>{_e(item["description"])}</p></div></li>'
        for item in report["system_flow"]
    )
    global_policy_cards = []
    for item in report["global_policies"]:
        warning = ""
        if item["changes_current_policy"]:
            warning = f'<div class="change-warning"><strong>현재 정책 변경 권고</strong><p>{_e(item["change_warning"])}</p></div>'
        global_policy_cards.append(
            f'<article id="{item["id"].lower()}" class="{"policy-change" if item["changes_current_policy"] else ""}">'
            f'<p class="item-id">{item["id"]} · 추천 기준안</p><h3>{_e(item["title"])}</h3>'
            f'<p>{_e(item["policy"])}</p><small>{_e(item["why"])}</small>{warning}'
            f'<p class="id-list">관련 기능: {_e(", ".join(item["feature_ids"]))}</p>'
            f'{_review_controls(item["id"], "이 전체 정책을 어떻게 처리할까요?")}</article>'
        )
    global_policies = "".join(global_policy_cards)
    shared_policies = "".join(
        _shared_proposal_card(item)
        for item in report["shared_decision_proposals"]
    )
    change_items = [
        f'<li><a href="#{item["id"].lower()}"><strong>{item["id"]} · {_e(item["title"])}</strong></a>'
        f'<p>{_e(item["policy"])}</p><small>{_e(item["change_warning"])}</small></li>'
        for item in report["global_policies"]
        if item["changes_current_policy"]
    ]
    change_items.extend(
        f'<li><a href="#{item["proposal_id"].lower()}"><strong>{item["proposal_id"]} · {_e(item["decision_title"])}</strong></a>'
        f'<p>{_e(item["recommended_answer"])}</p><small>{_e(item["change_warning"])}</small></li>'
        for item in report["shared_decision_proposals"]
        if item["changes_current_policy"]
    )
    for feature in report["features"]:
        for index, item in enumerate(feature["proposal"]["open_item_proposals"], start=1):
            if not item["changes_current_policy"]:
                continue
            proposal_id = f"{feature['id']}-P{index:02d}"
            change_items.append(
                f'<li><a href="#{proposal_id.lower()}"><strong>{proposal_id} · {_e(feature["name"])}</strong></a>'
                f'<p>{_e(item["recommended_answer"])}</p><small>{_e(item["change_warning"])}</small></li>'
            )
    area_links = []
    area_sections = []
    for area in report["areas"]:
        feature_links = "".join(
            f'<li><a href="#{feature_id.lower()}">{feature_id} · {_e(feature_by_id[feature_id]["name"])}</a></li>'
            for feature_id in area["feature_ids"]
        )
        area_links.append(
            f'<article><a class="area-link" href="#{area["id"].lower()}"><strong>{_e(area["title"])}</strong><span>{_e(area["plain_scope"])}</span></a><ul>{feature_links}</ul></article>'
        )
        area_sections.append(
            f'<section class="area" id="{area["id"].lower()}" data-area-section="{area["id"]}">'
            f'<header class="area-header"><p class="eyebrow">{area["id"]}</p><h2>{_e(area["title"])}</h2><p>{_e(area["plain_scope"])}</p></header>'
            + "".join(_render_feature(feature_by_id[feature_id]) for feature_id in area["feature_ids"])
            + "</section>"
        )
    summary = report["summary"]
    return f"""
      <section class="hero" id="top">
        <p class="eyebrow">{_e(report['metadata']['report_id'])}</p>
        <h1>{_e(report['metadata']['title'])}</h1>
        <p class="subtitle">{_e(report['metadata']['subtitle'])}</p>
        <div class="status-line"><strong>현재 상태: 검토용 기준안</strong><span>아직 승인되지 않았고 정식 출시도 할 수 없습니다.</span></div>
        <p class="lead">이 보고서에서 54개 기능의 정책을 검토하고 수정사항을 반영한 뒤 최종 승인하면, 그때부터 요구사항·설계·시험 문서 작성의 기준으로 사용합니다. 이 보고서를 읽는 것만으로 구현 완료나 출시 승인이 되지는 않습니다.</p>
        <dl class="document-meta">
          <div><dt>기준일</dt><dd>{_e(report['metadata']['as_of'])}</dd></div>
          <div><dt>문서 버전</dt><dd>{_e(report['metadata']['document_version'])}</dd></div>
          <div><dt>기능</dt><dd>{summary['feature_count']}개</dd></div>
          <div><dt>세부 추천안</dt><dd>{summary['proposal_count']}개</dd></div>
        </dl>
      </section>

      <section class="report-section" id="how-to-review">
        <div class="section-heading"><div><p class="eyebrow">검토 방법</p><h2>이 문서를 검토하면 다음에 무엇을 하나요?</h2></div></div>
        <ol class="review-steps">
          <li><strong>기능 정책을 읽습니다.</strong><span>기능마다 입력, 작동, 결과, 오류, 데이터, 금지사항과 시험 기준을 확인합니다.</span></li>
          <li><strong>쉬운 질문과 추천안을 확인합니다.</strong><span>맞으면 그대로 진행하고, 다르면 해당 정책 ID와 원하는 내용을 메모합니다.</span></li>
          <li><strong>전체·공통 정책과 54개 기능에 결과를 남깁니다.</strong><span>각 정책마다 진행, 수정, 보류 중 하나를 명시적으로 선택합니다.</span></li>
          <li><strong>검토 결과를 파일로 저장합니다.</strong><span>저장한 결과를 반영해 정책 기준선을 다시 만들고 최종 승인을 받습니다.</span></li>
          <li><strong>승인 뒤 문서를 작성합니다.</strong><span>승인된 기준만 DOC·MGT·REQ·DES·DEV 산출물에 반영합니다.</span></li>
        </ol>
      </section>

      <section class="report-section" id="service-flow">
        <div class="section-heading"><div><p class="eyebrow">전체 구조</p><h2>WalkSafe가 작동하는 큰 흐름</h2></div></div>
        <ol class="system-flow">{flow}</ol>
      </section>

      <section class="report-section" id="global-policy">
        <div class="section-heading"><div><p class="eyebrow">모든 기능에 공통 적용</p><h2>설계가 흔들리지 않게 하는 전체 정책</h2></div></div>
        <div class="global-policy-grid">{global_policies}</div>
      </section>

      <section class="report-section" id="shared-policy">
        <div class="section-heading"><div><p class="eyebrow">기능을 가로질러 함께 결정</p><h2>여러 기능에 공통인 세부 질문과 추천안</h2></div><span>{summary['shared_proposal_count']}개</span></div>
        <p>기존 결정에 남아 있던 추가 확인 {summary['shared_open_detail_count']}개를 중복 없이 {summary['shared_proposal_count']}개 질문으로 묶었습니다. 여기서 각 공통 정책을 한 번만 검토하고, 관련 기능 안에서는 같은 정책으로 연결된 위치를 확인합니다.</p>
        <div class="shared-proposal-list">{shared_policies}</div>
      </section>

      <section class="report-section warning-section" id="change-recommendations">
        <div class="section-heading"><div><p class="eyebrow">중요 재검토</p><h2>현재 선택보다 안전·법률·운영상 바꾸기를 권하는 항목</h2></div><span>{len(change_items)}개</span></div>
        <p>아래 항목은 기존 결정을 숨기고 바꾼 것이 아닙니다. 현재 정책과 추천안을 나란히 보여주며, 사용자가 다시 승인해야만 기준선에 반영합니다.</p>
        <ol class="change-list">{''.join(change_items) if change_items else '<li>현재 정책 변경을 요구하는 추천안이 없습니다.</li>'}</ol>
      </section>

      <section class="report-section" id="feature-index">
        <div class="section-heading"><div><p class="eyebrow">18개 분야·54개 기능</p><h2>기능 목차와 검토 현황</h2></div></div>
        <div class="review-progress" role="status" aria-live="polite">
          <div><strong id="reviewed-count">0</strong><span>/ 54 기능 검토</span></div>
          <div><strong id="common-reviewed-count">0</strong><span>/ {len(global_policies) + len(report['shared_decision_proposals'])} 전체·공통 검토</span></div>
          <div><strong id="accept-count">0</strong><span>전체 항목 그대로 진행</span></div>
          <div><strong id="revise-count">0</strong><span>수정 필요</span></div>
          <div><strong id="hold-count">0</strong><span>보류</span></div>
        </div>
        <div class="filter-bar">
          <label>기능 찾기<input id="feature-search" name="feature-search" type="search" autocomplete="off" placeholder="예: 객체 탐지, 신고, 로그인"></label>
          <label>분야<select id="area-filter" name="area-filter"><option value="all">모든 분야</option>{''.join(f'<option value="{area["id"]}">{_e(area["title"])}</option>' for area in report['areas'])}</select></label>
          <label>정책 상태<select id="status-filter" name="status-filter"><option value="all">모든 상태</option><option value="CONFIRMED_WITH_OPEN_DETAILS">큰 방향 확정</option><option value="CONDITIONAL">조건부</option><option value="CONFLICTING">충돌 있음</option></select></label>
        </div>
        <p id="filter-result" class="filter-result" role="status" aria-live="polite">54개 기능을 모두 표시하고 있습니다.</p>
        <div class="area-index">{''.join(area_links)}</div>
      </section>

      <div id="feature-report">{''.join(area_sections)}</div>

      <section class="report-section final-review" id="final-review">
        <div class="section-heading"><div><p class="eyebrow">검토 결과 저장</p><h2>정책 기준선 반영을 위한 검토 기록</h2></div></div>
        <p>전체·공통 정책과 54개 기능을 모두 검토해도 자동으로 승인되지는 않습니다. 수정사항을 반영한 새 기준선을 다시 확인하고 프로젝트 책임자가 별도로 승인해야 합니다.</p>
        <label class="reviewer-label" for="reviewer-name">검토자 이름 또는 역할<input id="reviewer-name" name="reviewer-name" type="text" autocomplete="name" aria-describedby="reviewer-error" aria-invalid="false" placeholder="예: 프로젝트 책임자"></label>
        <p class="review-error" id="reviewer-error" hidden>검토를 완료하려면 검토자 이름 또는 역할을 적어 주세요.</p>
        <div class="final-actions">
          <button type="button" id="next-unreviewed">다음 미검토 기능으로 이동</button>
          <button type="button" id="export-review" class="primary">검토 결과 파일 저장</button>
          <button type="button" id="print-report">핵심 검토본 인쇄·PDF 파일</button>
        </div>
        <p class="print-note">인쇄본은 최종 기준·입력·결과·질문별 추천안·설계 규칙·금지사항·시험 기준만 담습니다. 추천 이유와 비교용 현재 자료·근거는 이 화면 문서에서 확인합니다.</p>
        <p id="completion-message" class="completion-message" tabindex="-1">현재 전체·공통 정책과 54개 기능이 모두 미검토 상태입니다.</p>
      </section>
    """


def build_comprehensive_artifacts() -> tuple[dict[str, Any], str]:
    register, policy, _ = effective.build_effective_artifacts()
    _require(policy.get("features") and len(policy["features"]) == 54, "effective policy must contain 54 features")
    _require(register.get("decisions") and len(register["decisions"]) == 135, "effective register must contain 135 decisions")
    proposals = _load_and_validate_fragments(policy)
    shared_proposals = _validate_shared_decision_proposals(register)
    source_bindings = {
        "generator": _source_binding(GENERATOR_PATH),
        "effective_policy": _source_binding(POLICY_PATH),
        "effective_register": _source_binding(REGISTER_PATH),
        "template": _source_binding(TEMPLATE_PATH),
        "proposal_fragments": [_source_binding(path) for path in FRAGMENT_PATHS],
    }
    binding_payload = json.dumps(source_bindings, ensure_ascii=False, sort_keys=True).encode("utf-8")
    binding_sha256 = _sha256_bytes(binding_payload)
    global_policies = _global_policies()
    changed_recommendation_count = sum(
        item["changes_current_policy"]
        for feature in proposals
        for item in feature["open_item_proposals"]
    ) + sum(item["changes_current_policy"] for item in global_policies + shared_proposals)
    proposals_output = {
        "schema_version": PROPOSALS_SCHEMA,
        "report_id": "WS-POLICY-COMPREHENSIVE-20260719",
        "document_version": "1.0.0",
        "as_of": "2026-07-19",
        "lifecycle_status": "IN_REVIEW",
        "baseline_status": "NOT_APPROVED",
        "title": "WalkSafe 기능별 상세 정책 기준안",
        "purpose": "54개 기능의 설계 방향을 비전공자가 검토할 수 있도록, 현재 정책과 열린 세부사항의 추천 기준을 한곳에 정리한다.",
        "binding_sha256": binding_sha256,
        "source_bindings": source_bindings,
        "summary": {
            "area_count": len(policy["areas"]),
            "feature_count": len(policy["features"]),
            "open_item_count": sum(len(item["policy"]["unresolved"]) for item in policy["features"]),
            "feature_proposal_count": sum(len(item["open_item_proposals"]) for item in proposals),
            "shared_proposal_count": len(shared_proposals),
            "shared_open_detail_count": sum(len(item["source_open_details"]) for item in shared_proposals),
            "proposal_count": sum(len(item["open_item_proposals"]) for item in proposals) + len(shared_proposals),
            "current_policy_change_recommendation_count": changed_recommendation_count,
        },
        "finalization_type_labels": FINALIZATION_TYPES,
        "global_policy_proposals": global_policies,
        "shared_decision_proposals": shared_proposals,
        "features": proposals,
    }
    proposal_by_id = {item["feature_id"]: item for item in proposals}
    decision_by_id = {
        item["canonical_decision_id"]: item
        for item in register["decisions"]
    }
    decision_feature_ids = {
        item["canonical_decision_id"]: item["affected_feature_ids"]
        for item in register["decisions"]
    }
    report_features = []
    for feature in policy["features"]:
        merged = copy.deepcopy(feature)
        merged["proposal"] = proposal_by_id[feature["id"]]
        related = {
            related_feature_id
            for canonical_id in feature["canonical_decision_refs"]
            for related_feature_id in decision_feature_ids[canonical_id]
            if related_feature_id != feature["id"]
        }
        merged["related_feature_ids"] = sorted(related)
        merged["shared_policy_proposals"] = [
            item
            for item in shared_proposals
            if feature["id"] in item["affected_feature_ids"]
        ]
        merged["global_policy_proposals"] = [
            item
            for item in global_policies
            if feature["id"] in item["feature_ids"]
        ]
        merged["linked_decisions"] = [
            {
                "decision_id": decision_by_id[canonical_id]["decision_id"],
                "title": decision_by_id[canonical_id]["title"],
                "decision_statement": decision_by_id[canonical_id]["decision_statement"],
                "open_details": decision_by_id[canonical_id]["open_details"],
                "responsibility_type": decision_by_id[canonical_id]["responsibility_type"],
                "resolution_status": decision_by_id[canonical_id]["resolution_status"],
            }
            for canonical_id in feature["canonical_decision_refs"]
        ]
        report_features.append(merged)
    report_data = {
        "schema_version": REPORT_SCHEMA,
        "metadata": {
            "report_id": proposals_output["report_id"],
            "title": "WalkSafe 기능별 정책 종합보고서",
            "subtitle": "설계 기준 검토본",
            "document_version": proposals_output["document_version"],
            "as_of": proposals_output["as_of"],
            "lifecycle_status": "IN_REVIEW",
            "baseline_status": "NOT_APPROVED",
            "release_status": "NOT_ELIGIBLE",
            "binding_sha256": binding_sha256,
        },
        "summary": {
            **proposals_output["summary"],
            "policy_status_counts": policy["summary"]["policy_status_counts"],
            "implementation_status_counts": policy["summary"]["implementation_status_counts"],
            "remaining_conflict_feature_ids": policy["summary"]["remaining_conflict_feature_ids"],
        },
        "system_flow": _system_flow(),
        "global_policies": global_policies,
        "shared_decision_proposals": shared_proposals,
        "areas": policy["areas"],
        "features": report_features,
        "finalization_type_labels": FINALIZATION_TYPES,
        "review_contract": {
            "schema_version": "walksafe.comprehensive-policy-review-answers.v2",
            "decisions": {
                "accept": "이 정책대로 진행",
                "revise": "일부 수정 후 진행",
                "hold": "결정을 보류",
            },
            "required_global_policy_ids": [item["id"] for item in global_policies],
            "required_shared_policy_ids": [item["proposal_id"] for item in shared_proposals],
            "required_feature_ids": [item["id"] for item in report_features],
            "baseline_effect": "전체·공통 정책과 54개 기능의 검토 결과와 수정사항을 반영하고 별도 승인한 뒤에만 요구사항·설계 문서의 기준으로 사용한다.",
        },
    }
    template = TEMPLATE_PATH.read_text(encoding="utf-8")
    _require(template.count("__REPORT_DATA__") == 1, "template must contain one report placeholder")
    _require(template.count("__STATIC_REPORT__") == 1, "template must contain one static-report placeholder")
    embedded = json.dumps(report_data, ensure_ascii=False, separators=(",", ":"))
    embedded = embedded.replace("<", "\\u003c")
    html = template.replace("__STATIC_REPORT__", _render_static_report(report_data))
    html = html.replace("__REPORT_DATA__", embedded)
    _require("__REPORT_DATA__" not in html and "__STATIC_REPORT__" not in html, "report placeholder remains")
    return proposals_output, html


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _write_or_check(path: Path, content: bytes, *, check: bool) -> None:
    if check:
        _require(path.is_file(), f"generated output is missing: {path.relative_to(REPO_ROOT)}")
        _require(path.read_bytes() == content, f"generated output is stale: {path.relative_to(REPO_ROOT)}")
        return
    path.write_bytes(content)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if generated files are missing or stale")
    args = parser.parse_args(argv)
    try:
        proposals, html = build_comprehensive_artifacts()
        _write_or_check(PROPOSALS_OUTPUT_PATH, _json_bytes(proposals), check=args.check)
        _write_or_check(HTML_OUTPUT_PATH, html.encode("utf-8"), check=args.check)
    except (ComprehensiveReportValidationError, effective.EffectiveBaselineValidationError, OSError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    if args.check:
        print("PASS: comprehensive report has 54 features and one proposal for every open item")
    else:
        print(PROPOSALS_OUTPUT_PATH.relative_to(REPO_ROOT))
        print(HTML_OUTPUT_PATH.relative_to(REPO_ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
