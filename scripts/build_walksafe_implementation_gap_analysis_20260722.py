#!/usr/bin/env python3
"""Build the WalkSafe approved-policy versus current-implementation Gap report.

This builder is deliberately diagnostic.  It does not modify the approved
policy/artifact baselines, approve tests, or change release eligibility.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import html
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any, Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
AUDIT_DIR = REPO_ROOT / "docs" / "control" / "audits"
REPORT_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260722-r001.json"
REPORT_MD = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260722-r001.md"
REPORT_HTML = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260722-r001.html"
BACKLOG_JSON = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260722-r001.json"

POLICY_MANIFEST = REPO_ROOT / "docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json"
POLICY_SOURCE = REPO_ROOT / "docs/control/decision-interview/walksafe-feature-policy-comprehensive-draft.json"
RTM_PATH = REPO_ROOT / "docs/deliverables/03-requirements/rtm.json"
DESIGN_TRACE_PATH = REPO_ROOT / "docs/deliverables/04-design/design-traceability-register.json"
MODULE_REGISTER_PATH = REPO_ROOT / "docs/deliverables/05-implementation/module-register.json"
TEST_CASES_PATH = REPO_ROOT / "docs/deliverables/06-testing/registers/test-cases.json"
ARTIFACT_REGISTER_PATH = REPO_ROOT / "docs/deliverables/00-control/artifact-register.json"
APPROVAL_RECEIPT_PATH = REPO_ROOT / "docs/control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json"
RUNTIME_CANDIDATE_PATH = REPO_ROOT / "docs/control/questionnaire/walksafe-integrated-baseline-analysis.json"

PREPARED_AT = "2026-07-22T15:05:54+09:00"
EXPECTED_COMMIT = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
EXPECTED_BRANCH = "codex/walksafe-rc2-hardening-20260715"
EXPECTED_IMPLEMENTATION_FILE_COUNT = 424
EXPECTED_IMPLEMENTATION_PATH_SET_SHA256 = "a5d72a2e97d4ea74844400f2d9119ec1011377af223a57a480232f7f4f9a0a63"
EXPECTED_SOURCE_SHA256 = {
    POLICY_MANIFEST: "b6f5b850a3983b8059b85d93dd07864520219d31fa65a65d740b6bab78231308",
    POLICY_SOURCE: "5741749fd6f3a361eb5a43f3dbca48e6f5ec1a0f786e3169007327ceca5ce6ac",
    RTM_PATH: "1d73d1e6e0a47ea3833df779c945397d799b14e9b26fdac4bb577197a660a7bd",
    DESIGN_TRACE_PATH: "1ffb5861887d8edb26efbf0019cd4547baa6a24d8a73a576c3a5e9adf1e892aa",
    MODULE_REGISTER_PATH: "54f2119ccb8598f06261590f8855c8d4c442cd662c4502501b62164a2cc0fe49",
    TEST_CASES_PATH: "19a6b425bf3a0ee880f1c9229a42b96845ab4c3a1f99037783fe872426e0deee",
    ARTIFACT_REGISTER_PATH: "c4a5259744febc9cc442785be1748a0e0da88dbc86ef6459f1f6ee74fcaf62b6",
    APPROVAL_RECEIPT_PATH: "002355b92c9862a9fbdc443a48b28657975f91fb58caf3504a28df5eb1f524bb",
}

IMPLEMENTATION_PREFIXES = (
    "apps/android/", "apps/web/", "backend/", "model/", "voice/",
    "contracts/", "deploy/", "configs/", ".github/",
)
ALLOWED_STATUSES = {
    "IMPLEMENTED", "PARTIAL", "MISSING", "CONFLICTING", "EVIDENCE_MISSING", "BLOCKED"
}
STATUS_LABELS = {
    "IMPLEMENTED": "구현·정식검증 완료",
    "PARTIAL": "일부 구현",
    "MISSING": "핵심 구현 없음",
    "CONFLICTING": "정책과 충돌",
    "EVIDENCE_MISSING": "정식 증거 없음",
    "BLOCKED": "미실행 출시 확인",
}
TARGET_COMPLETION_LEVEL_LABELS = {
    "IMPLEMENTATION_READY": "목표: 구현 준비",
    "VERIFICATION_COMPLETE": "목표: 정식 검증 완료",
}
EXPECTED_GATE_IDS = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
}


class GapAnalysisError(RuntimeError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise GapAnalysisError(message)


def _relative(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def _object_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        _require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_strict_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(GapAnalysisError(f"invalid number: {token}")),
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise GapAnalysisError(f"cannot read strict JSON: {_relative(path)}") from exc
    _require(isinstance(value, dict), f"JSON root is not an object: {_relative(path)}")
    return value


def _run_git(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=False
    )
    _require(completed.returncode == 0, f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def _source_binding(name: str, path: Path) -> dict[str, Any]:
    _require(path.is_file(), f"source missing: {_relative(path)}")
    actual = _file_sha256(path)
    expected = EXPECTED_SOURCE_SHA256.get(path)
    if expected:
        _require(actual == expected, f"approved input changed: {_relative(path)}")
    return {"name": name, "path": _relative(path), "bytes": path.stat().st_size, "sha256": actual}


def implementation_snapshot() -> tuple[dict[str, Any], list[str]]:
    commit = _run_git("rev-parse", "HEAD")
    branch = _run_git("branch", "--show-current")
    _require(commit == EXPECTED_COMMIT, "implementation commit differs from the frozen audit target")
    _require(branch == EXPECTED_BRANCH, "implementation branch differs from the frozen audit target")
    paths = [
        row for row in _run_git("ls-files").splitlines()
        if row.startswith(IMPLEMENTATION_PREFIXES)
    ]
    _require(len(paths) == EXPECTED_IMPLEMENTATION_FILE_COUNT, "implementation file count changed")
    path_set_sha = hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
    _require(path_set_sha == EXPECTED_IMPLEMENTATION_PATH_SET_SHA256, "implementation path set changed")
    changed = _run_git("status", "--porcelain", "--", *IMPLEMENTATION_PREFIXES)
    _require(not changed, "implementation scope contains tracked or untracked changes")
    entries = []
    counts: Counter[str] = Counter()
    for raw in paths:
        path = REPO_ROOT / raw
        _require(path.is_file(), f"tracked implementation file missing: {raw}")
        top = ".github" if raw.startswith(".github/") else raw.split("/", 1)[0]
        if top == "apps":
            top = "/".join(raw.split("/", 2)[:2])
        counts[top] += 1
        entries.append({"path": raw, "bytes": path.stat().st_size, "sha256": _file_sha256(path)})
    content_set_sha = _object_sha256(entries)
    snapshot = {
        "commit": commit,
        "branch": branch,
        "scope_prefixes": list(IMPLEMENTATION_PREFIXES),
        "tracked_scope_clean": True,
        "file_count": len(paths),
        "file_count_by_area": dict(sorted(counts.items())),
        "path_set_sha256": path_set_sha,
        "content_set_sha256": content_set_sha,
    }
    snapshot["snapshot_sha256"] = _object_sha256(snapshot)
    return snapshot, paths


def _file_evidence(evidence_id: str, path_text: str, start: int, end: int, claim: str) -> dict[str, Any]:
    path = REPO_ROOT / path_text
    _require(path.is_file(), f"evidence file missing: {path_text}")
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    _require(1 <= start <= end <= len(lines), f"invalid evidence lines: {path_text}:{start}-{end}")
    excerpt = "\n".join(lines[start - 1:end]).strip()
    return {
        "evidence_id": evidence_id,
        "kind": "IMPLEMENTATION_FILE",
        "path": path_text,
        "line_start": start,
        "line_end": end,
        "file_sha256": _file_sha256(path),
        "claim": claim,
        "excerpt": excerpt[:2400],
    }


def _negative_evidence(
    evidence_id: str, paths: list[str], patterns: list[str], claim: str
) -> dict[str, Any]:
    matches: list[dict[str, Any]] = []
    compiled = [re.compile(pattern, re.IGNORECASE) for pattern in patterns]
    for path_text in paths:
        path = REPO_ROOT / path_text
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for line_no, line in enumerate(text.splitlines(), 1):
            if any(pattern.search(line) for pattern in compiled):
                matches.append({"path": path_text, "line": line_no, "text": line.strip()[:300]})
    return {
        "evidence_id": evidence_id,
        "kind": "NEGATIVE_SEARCH",
        "paths_checked": paths,
        "patterns": patterns,
        "match_count": len(matches),
        "matches": matches[:20],
        "claim": claim,
    }


def _plain_text(value: str) -> str:
    """Replace implementation jargon in reader-facing prose without changing IDs or paths."""
    replacements = (
        ("object storage", "대용량 파일 저장소"), ("object-storage", "대용량 파일 저장소"),
        ("client_report_id", "휴대전화가 만든 고정 신고번호"),
        ("LEGACY_REFERENCE_ONLY", "과거 참고자료 전용"),
        ("deployment_eligible", "배포 적격 여부"),
        ("decode·letterbox", "영상 읽기·크기 맞춤"),
        ("READY→ACTIVE→PAUSED→RECHECK→CONFIRMED_RESUME", "준비→보행 중→일시정지→재검사→사용자 확인 후 재개"),
        ("health/readiness", "운영상태·시작가능 여부"),
        ("session/segment", "보행 1회·구간"), ("primary/backup", "주 저장소·백업 저장소"),
        ("load/invoke", "모델 불러오기·실행"), ("frame age", "영상 취득 후 지난 시간"),
        ("content hash", "내용 지문"),
        ("rate limit", "요청 횟수 제한"),
        ("not approved", "미승인"),
        ("access token", "접근용 로그인 증명"), ("refresh token", "갱신용 로그인 증명"),
        ("Web/PWA", "Web 앱"), ("kill switch", "새 보행 긴급 차단"),
        ("TalkBack", "Android 화면읽기 기능"), ("TFLite", "휴대전화용 객체탐지 모델"),
        ("ARCore", "공간·거리 인식 기능"), ("idempotency", "중복 처리 방지값"),
        ("tombstone", "삭제 완료 표식"), ("ScrollView", "스크롤 화면"),
        ("foreground", "앱 화면이 켜진 상태"), ("pipeline", "자동 처리 절차"),
        ("fallback", "대체 실행"), ("rollback", "이전 정상 상태로 되돌리기"),
        ("runtime", "실행 구성"), ("manifest", "구성 목록"),
        ("staging", "임시 저장"), ("rename", "최종 이름 전환"),
        ("uploader", "전송기"), ("bundle", "한 묶음"),
        ("unified", "통합형"), ("legacy", "이전 방식"),
        ("orchestration", "연계 처리"), ("migration", "자료 구조 전환"),
        ("provenance", "추적 기록"), ("workflow", "자동 시험 절차"),
        ("compatible", "호환 가능한"), ("Keystore", "Android 보안 저장소"),
        ("ledger", "관리대장"), ("sequence", "촬영 순서"),
        ("RGB", "색상 영상"), ("E2E", "처음부터 끝까지의 전체 과정"),
        ("SpeechRecognizer", "Android 내장 음성인식"),
        ("SafetyStateMachine", "중앙 안전상태 제어기"),
        ("PostgreSQL/PostGIS", "공간 데이터베이스"),
        ("HTTPS", "암호화된 인터넷 연결"), ("HTTP", "인터넷 요청"),
        ("opt-in", "명시적 사용 동의"), ("MFA", "추가 본인확인"),
        ("IdP", "조직 로그인 서비스"), ("JPEG", "압축 이미지"),
        ("release", "정식 배포본"), ("Noop", "저장하지 않는 구현"),
        ("candidate", "후보"), ("config", "설정"), ("debug", "개발용"),
        ("SharedPreferences", "일반 설정 저장소"), ("app ID", "앱 식별값"),
        ("socket", "인터넷 연결"), ("POST", "자료 전송"),
        ("queue", "전송 대기함"), ("TTL", "정보 유효시간"),
        ("KMS", "암호화 열쇠 관리서비스"), ("RBAC", "역할별 접근권한"),
        ("gateway", "보호 서버"), ("worker", "자동 처리 작업"),
        ("PWA", "Web 앱"), ("STT", "음성인식"), ("TTS", "음성안내"),
        ("API", "서버 연결 기능"), ("token", "로그인 증명값"),
        ("RTM", "요구사항 연결표"), ("DB", "데이터베이스"),
        ("NOT_RUN", "미실행(NOT_RUN)"), ("PASS", "합격"),
        ("gate", "확인 관문"), ("schema", "자료 구조"),
        ("threshold", "판단 기준값"), ("class", "탐지대상"),
        ("depth", "거리·깊이 정보"), ("freshness", "정보 최신성"),
        ("confidence", "판단 확실성"), ("version", "판본"),
        ("drain", "진행 중 작업의 안전 종료"), ("receipt", "수신확인서"),
        ("lease", "동시 보행 잠금"), ("build", "앱 설치본"), ("RC", "출시 후보 묶음"),
        ("backup", "백업"), ("primary", "주 처리"), ("update", "갱신"),
        ("load", "불러오기"), ("pair", "조합"), ("Home", "홈 버튼"),
        ("access", "접근용"),
        ("upload", "파일 전송"), ("quota", "사용 한도"),
        ("timeout", "응답 시간 초과"), ("backoff", "재시도 간격 늘리기"),
        ("UNAVAILABLE", "사용할 수 없는 상태"),
        ("known-good", "이전에 확인한 정상"), ("dataset", "학습자료"),
        ("metadata", "부가정보"), ("reconciliation", "비교·정정"),
        ("Next.js", "Web"), ("launcher", "시작 화면"), ("MainActivity", "주 화면"),
        ("commit", "소스 판본 지점"), ("frame", "영상 한 장"),
        ("heading", "진행 방향"), ("distance", "거리"), ("metric", "측정 거리"),
        ("stale", "너무 오래됨"), ("pending", "대기 중"), ("active", "진행 중"),
        ("field", "현장시험"), ("admin", "관리자"), ("server", "서버"),
        ("fake", "모의"), ("app", "앱"), ("OS", "운영체제"), ("hash", "지문"),
        ("code", "오류 코드"),
    )
    result = value
    for source, replacement in replacements:
        pattern = rf"(?<![A-Za-z0-9_]){re.escape(source)}(?![A-Za-z0-9_])"
        result = re.sub(pattern, replacement, result, flags=re.IGNORECASE)
    particle_fixes = {
        "앱를": "앱을", "앱가": "앱이", "앱는": "앱은", "앱와": "앱과",
        "관문를": "관문을", "관문가": "관문이", "관문는": "관문은", "관문와": "관문과",
        "모델를": "모델을", "모델가": "모델이", "모델는": "모델은", "모델와": "모델과",
        "설정를": "설정을", "설정가": "설정이", "설정는": "설정은", "설정와": "설정과",
        "배포본를": "배포본을", "배포본가": "배포본이", "배포본는": "배포본은", "배포본와": "배포본과",
        "설치본를": "설치본을", "설치본가": "설치본이", "설치본는": "설치본은", "설치본와": "설치본과",
        "기준값를": "기준값을", "기준값가": "기준값이", "기준값는": "기준값은", "기준값와": "기준값과",
        "묶음를": "묶음을", "묶음가": "묶음이", "묶음는": "묶음은", "묶음와": "묶음과",
        "작업를": "작업을", "작업가": "작업이", "작업는": "작업은", "작업와": "작업과",
        "기능를": "기능을", "기능가": "기능이", "기능는": "기능은", "기능와": "기능과",
        "탐지대상를": "탐지대상을", "탐지대상가": "탐지대상이", "탐지대상는": "탐지대상은", "탐지대상와": "탐지대상과",
        "스크롤 화면로": "스크롤 화면으로", "스크롤 화면를": "스크롤 화면을",
        "이전 정상 상태로 되돌리기을": "이전 정상 상태로 되돌리기를",
        "역할별 접근권한를": "역할별 접근권한을", "역할별 접근권한가": "역할별 접근권한이",
        "구성 목록로": "구성 목록으로", "구성 목록를": "구성 목록을",
        "전송 대기함를": "전송 대기함을", "전송 대기함가": "전송 대기함이",
        "판단 확실성가": "판단 확실성이", "판단 확실성를": "판단 확실성을",
        "차단와": "차단과", "연계 처리을": "연계 처리를", "촬영 순서를": "촬영 순서를",
        "파일 전송를": "파일 전송을", "파일 전송가": "파일 전송이",
        "재시도 간격 늘리기을": "재시도 간격 늘리기를",
        "영상 한 장를": "영상 한 장을", "영상 한 장가": "영상 한 장이",
        "진행 방향를": "진행 방향을", "진행 방향가": "진행 방향이",
        "시작 화면를": "시작 화면을", "시작 화면가": "시작 화면이", "시작 화면는": "시작 화면은",
        "주 화면를": "주 화면을", "주 화면가": "주 화면이", "주 화면는": "주 화면은",
        "현장시험를": "현장시험을", "현장시험가": "현장시험이", "현장시험와": "현장시험과",
        "소스 판본 지점를": "소스 판본 지점을", "소스 판본 지점가": "소스 판본 지점이",
        "Android Android 보안 저장소": "Android 보안 저장소",
        "관리대장를": "관리대장을", "동시 보행 잠금와": "동시 보행 잠금과",
        "전송 대기함와": "전송 대기함과",
        "진행 중로": "진행 중 상태로", "판단 확실성와": "판단 확실성과",
        "추적 기록와": "추적 기록과", "음성인식를": "음성인식을",
        "관리대장와": "관리대장과", "촬영 촬영 순서": "촬영 순서",
        "파일 파일 전송": "파일 전송", "중앙 중앙 안전상태": "중앙 안전상태",
        "전송 대기함-full": "전송 대기함이 가득 찬 상태",
        "역할별 접근권한와": "역할별 접근권한과",
        "처음부터 끝까지의 전체 과정를": "처음부터 끝까지의 전체 과정을",
        "이전 정상 상태로 되돌리기이": "이전 정상 상태로 되돌리는 기능이",
        "모델 모델 불러오기": "모델 불러오기",
        "조합를": "조합을", "조합가": "조합이", "조합와": "조합과",
        "이전에 확인한 정상가": "이전에 확인한 정상 상태가",
        "학습 학습자료으로": "학습자료로", "학습자료으로": "학습자료로",
        "응답 시간 초과은": "응답 시간 초과는",
        "재시도 간격 늘리기한다": "재시도 간격을 늘린다",
        "파일 전송 전송 대기함": "파일 전송 대기함",
        "대용량 원본 대용량 파일 저장소": "원본용 대용량 파일 저장소",
        "저장하지 않는 구현 구현": "저장하지 않는 구현",
        "삭제 삭제 완료 표식": "삭제 완료 표식",
        "진행 중 보행 진행 중 작업의 안전 종료": "진행 중 보행의 안전 종료",
        "Android 화면읽기 기능인 Android 화면읽기 기능": "Android 화면읽기 기능",
        "명시적 이동통신망 명시적 사용 동의": "이동통신망 사용에 대한 명시적 동의",
        "조직 조직 로그인 서비스": "조직 로그인 서비스",
    }
    for broken, corrected in particle_fixes.items():
        result = result.replace(broken, corrected)
    return result


def build_evidence(implementation_paths: list[str]) -> list[dict[str, Any]]:
    kotlin_paths = [p for p in implementation_paths if p.startswith("apps/android/") and p.endswith((".kt", ".kts", ".xml"))]
    backend_paths = [p for p in implementation_paths if p.startswith("backend/") and p.endswith((".py", ".sql", ".yml", ".yaml"))]
    evidence = [
        _file_evidence("EVD-PRODUCT-ROOT", "README.md", 3, 9, "루트 설명은 Next.js Web/PWA를 주 앱으로 두고 Android를 보조 실험 경로로 둔다."),
        _file_evidence("EVD-PRODUCT-PURPOSE", "README.md", 57, 67, "현재 문서는 보행 위험안내와 신고 원칙 일부를 설명하지만 Android 정식 제품 목적과 안전 비대체 원칙을 한곳에서 일관되게 선언하지 않는다."),
        _file_evidence("EVD-PRODUCT-ANDROID-README", "apps/android/README.md", 1, 8, "Android 설명에도 Web/PWA 우선 전제가 남아 있다."),
        _file_evidence("EVD-PRODUCT-WEB", "apps/web/README.md", 1, 8, "Web 앱은 자신을 현재 주 앱으로 설명한다."),
        _file_evidence("EVD-ANDROID-SINGLE-MODULE", "apps/android/settings.gradle.kts", 14, 18, "Android 빌드는 사용자·관리자 분리 없이 app 모듈 하나만 포함한다."),
        _file_evidence("EVD-ANDROID-MANIFEST", "apps/android/app/src/main/AndroidManifest.xml", 3, 44, "필수 권한과 단일 launcher MainActivity가 선언돼 있다."),
        _file_evidence("EVD-ANDROID-ACCOUNT", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 423, 600, "첫 실행은 온보딩 상태기계 대신 단일 화면과 일반 설정 저장소의 사용자 ID를 쓴다. 개발용 build는 서버 주소와 인증값을 수동 입력하지만 정식 build 주소는 승인값으로 고정한다."),
        _file_evidence("EVD-ANDROID-WALK-UI", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 1304, 1535, "보행 화면에 개발·로그인·좌표·신고 등 다수 조작을 한 ScrollView로 배치한다."),
        _file_evidence("EVD-ANDROID-RESUME", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 860, 942, "화면 복귀 시 전체 재검사·사용자 확인 상태 없이 세션과 위치를 재개한다."),
        _file_evidence("EVD-ANDROID-PERMISSION", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 1024, 1062, "권한 문제는 상태문구와 버튼 중심으로 처리돼 기능별 안전정지 흐름이 불완전하다."),
        _file_evidence("EVD-NAV-DECISION", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt", 195, 216, "GPS·경로 끝만으로 도착을 자동 확정하고 반복 이탈 뒤 자동 재탐색 신호를 낸다."),
        _file_evidence("EVD-NAV-AUTO-REROUTE", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 4570, 4604, "사용자 선택 없이 TMAP 재요청과 도착 종료를 실행한다."),
        _file_evidence("EVD-VOICE-COMMAND", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommand.kt", 1, 44, "제한 명령 파서는 있으나 호출어 감지는 이 구성요소에 없다."),
        _file_evidence("EVD-VOICE-PARSER", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommand.kt", 34, 87, "인식 결과의 첫 후보만 사용하고 낮은 신뢰도·부정 표현·등록되지 않은 명령은 실행하지 않는 기본 파서가 있다."),
        _file_evidence("EVD-VOICE-UNMATCHED", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 3710, 3718, "해석되지 않은 음성은 실행하지 않고 다시 말해 달라고 안내한다."),
        _file_evidence("EVD-VOICE-PLATFORM-STT", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 3631, 3732, "버튼으로 플랫폼 SpeechRecognizer를 시작하며 길라잡이 호출어·오프라인 강제가 없다."),
        _file_evidence("EVD-TTS-HAPTIC", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt", 97, 155, "한국어 TTS와 진동 기본 연결은 구현돼 있다."),
        _file_evidence("EVD-TTS-FAILURE", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt", 293, 329, "TTS 초기화·언어 실패는 UNAVAILABLE 반환에 머물고 전체 안전정지와 결합되지 않는다."),
        _file_evidence("EVD-AUTO-CANDIDATE", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 3311, 3419, "보행 프레임에서 자동신고 후보를 즉시 준비한다."),
        _file_evidence("EVD-AUTO-UPLOAD", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 3422, 3506, "후보 생성 직후 보행·네트워크 분기 없는 비동기 HTTP 전송을 시작한다."),
        _file_evidence("EVD-REPORT-UPLOADER", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt", 38, 88, "신고 전송기는 로그인된 보호 서버 세션을 쓰지만 휴대전화 영구 전송 대기함 없이 즉시 HTTP 전송한다."),
        _file_evidence("EVD-REPORT-CONSENT", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSession.kt", 1, 53, "신고 동의는 JPEG·GPS와 현재 foreground 세션에 한정된 메모리 상태다."),
        _file_evidence("EVD-RAW-NOOP", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt", 13, 69, "현장 로그 계약은 정확 좌표·사용자 입력 원본을 제외하고 아무것도 저장하지 않는 구현도 함께 둔다."),
        _file_evidence("EVD-RAW-RELEASE-SELECTION", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 431, 445, "정식 배포 build에서는 현장 세션 원본 로그 대신 아무것도 저장하지 않는 구현을 선택한다."),
        _file_evidence("EVD-RAW-RELEASE", "apps/android/app/src/release/java/kr/co/hanium/dreamup/walksafe/debuglog/DebugFrameCaptureUploaderFactory.kt", 1, 7, "release 프레임 수집 uploader는 Noop 구현을 반환한다."),
        _file_evidence("EVD-METADATA-MEMORY", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MetadataCaptureLog.kt", 1, 43, "수집 로그는 제한된 메모리 진단정보일 뿐 승인된 원본 묶음이 아니다."),
        _file_evidence("EVD-ANDROID-BUILD", "apps/android/app/build.gradle.kts", 49, 114, "Android는 단일 app ID·0.1.0 버전이고 release 서명·영속 queue 의존성이 없다."),
        _file_evidence("EVD-ANDROID-RELEASE-CONTRACT", "apps/android/app/build.gradle.kts", 7, 78, "정식 Android 앱 설치본은 정확한 소스 지문과 승인된 암호화 서버 주소가 없으면 생성을 막는 기본 계약이 있다."),
        _file_evidence("EVD-BACKEND-AUTH", "backend/app/field_test_security.py", 1, 90, "백엔드는 실제 계정 인증 대신 field/admin 정적 token 경계를 사용한다."),
        _file_evidence("EVD-BACKEND-BOUNDARY", "backend/app/README.md", 30, 36, "현재 gateway는 현장·staging 경계이며 조직 IdP·중앙 RBAC를 대신하지 않는다고 명시한다."),
        _file_evidence("EVD-GATEWAY-ENDPOINT", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayEndpointPolicy.kt", 5, 27, "정식 배포 요청 주소는 build에서 승인한 HTTPS 서버 주소 하나로 제한한다."),
        _file_evidence("EVD-DATABASE-TLS", "backend/app/config.py", 170, 212, "외부 데이터베이스 연결은 서버 신원까지 확인하는 암호화 연결만 허용한다."),
        _file_evidence("EVD-TMAP-SERVER-CONFIG", "backend/.env.example", 32, 40, "지도 서비스 비밀값과 연결 주소·시간 한도를 서버 설정에 둔다."),
        _file_evidence("EVD-TMAP-BOUNDED", "backend/app/services/tmap_pedestrian.py", 590, 645, "서버가 지도 비밀값을 붙이고 비밀값 누락, 응답 시간초과와 과대 응답을 차단한다."),
        _file_evidence("EVD-BACKEND-REPORT-STORAGE", "backend/app/services/report_storage.py", 1, 120, "신고 이미지는 로컬 파일 staging/rename 중심이며 대용량 원본 object-storage 수명주기가 아니다."),
        _file_evidence("EVD-REPORT-DATABASE", "backend/app/models.py", 13, 66, "공간 좌표와 탐지·신고 메타데이터를 PostgreSQL/PostGIS 데이터베이스에 저장하는 기본 구조가 있다."),
        _file_evidence("EVD-REPORT-RETENTION", "scripts/check_report_retention_dry_run.py", 42, 46, "현재 신고 자료의 180일 보존 일부는 승인 정책과 맞지만, 휴대전화·검역·학습자료·백업·삭제요청 등 저장소별 전체 수명주기는 구현되지 않았다."),
        _file_evidence("EVD-WEB-ADMIN", "apps/web/app/admin/README.md", 1, 5, "관리 기능은 Android가 아니라 인증/RBAC 제한이 명시된 Web 화면에 남아 있다."),
        _file_evidence("EVD-MODEL-REGISTRY", "model/registry/walksafe-model-registry.json", 1, 57, "모델 후보 등록정보는 있으나 배포 동등성·독립평가·자동 복구 완료 증거는 아니다."),
        _file_evidence("EVD-MODEL-TRAIN", "model/train_yolo.py", 11, 79, "학습자료·기초모델·반복횟수·영상크기를 명시해 수동 학습을 실행하는 경로가 있다."),
        _file_evidence("EVD-DATASET-VALIDATION", "model/validate_yolo_dataset.py", 39, 89, "학습·검증·시험 자료의 영상·라벨 짝과 탐지대상 번호·좌표 범위를 확인하는 기초 검사기가 있다."),
        _file_evidence("EVD-MODEL-ANDROID-CONFIG", "apps/android/app/src/main/assets/model-config/two_model_runtime.json", 1, 69, "Android 실행 설정은 배포 부적격 후보를 주 모델로 켜고 13개 탐지대상과 낮은 후보 판단 기준값을 사용한다."),
        _file_evidence("EVD-CANDIDATE-REPORT-POLICY", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportCandidatePolicy.kt", 133, 146, "배포 부적격 후보인 unified 모델을 자동신고 생성에 허용한다."),
        _file_evidence("EVD-MODEL-LOCAL-DEPLOYMENT", "model/deployments/local-deployment.json", 1, 33, "로컬 배포 레코드는 후보 모델이며 이전 정상모델을 지정하지 않는다."),
        _file_evidence("EVD-MODEL-FALLBACK", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/TfliteAndroidFrameDetector.kt", 139, 183, "현재 runtime은 unified load 실패 때 승인된 이전 정상묶음 확인 없이 설정에 있는 legacy pair를 자동 fallback한다."),
        _file_evidence("EVD-MODEL-RUNTIME", "model/two_model_runtime.py", 1, 120, "두 모델 runtime 기본 코드는 존재한다."),
        _file_evidence("EVD-DETECTION-MESSAGE", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/MessagePolicy.kt", 93, 106, "현재 STOP·WARNING 음성 문구가 승인된 고정 행동문과 다르다."),
        _file_evidence("EVD-DETECTION-HAPTIC", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/WalkSafeFeedbackPolicy.kt", 367, 377, "위험 진동은 STOP에만 있고 WARNING·CAUTION은 비어 있다."),
        _file_evidence("EVD-DETECTION-FAILURE", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 2744, 2800, "탐지기가 반복 실패하면 전체 안전정지 대신 카메라만 끄고 TMAP 전용으로 계속한다."),
        _file_evidence("EVD-IMAGE-PREPROCESS", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/YuvImagePreprocessor.kt", 47, 104, "영상 전처리는 decode·letterbox 중심이며 밝기·가림·흔들림·각도 안전 gate가 없다."),
        _file_evidence("EVD-DEVICE-GATE", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/DeviceGate.kt", 1, 43, "기기 gate는 카메라·ARCore·depth·TFLite·freshness만 보고 저장공간·배터리·발열을 포함하지 않는다."),
        _file_evidence("EVD-TACTILE-POLICY", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/TactileRoutePolicy.kt", 61, 139, "TMAP 큰 경로와 휴대전화 점자블록 보조를 분리하고 불확실할 때 안내하지 않는 기본 규칙은 구현돼 있다."),
        _file_evidence("EVD-TACTILE-GUIDANCE", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidTactileRouteGuidance.kt", 166, 285, "경로·위치·센서·거리정보의 최신성을 함께 확인하는 점자블록 보조 흐름이 있다."),
        _file_evidence("EVD-TALKBACK", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 3168, 3251, "TalkBack 활성 감지와 우선순위 음성 알림 일부가 구현돼 있다."),
        _file_evidence("EVD-ACCESSIBILITY-UI", "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", 1312, 1468, "일부 상태 설명은 있으나 작은 글자 입력과 개발용 조작이 한 화면에 섞여 있다."),
        _file_evidence("EVD-OPERATIONS-HEALTH", "backend/app/api/health.py", 150, 240, "서버 health/readiness 점검 일부는 구현돼 있다."),
        _file_evidence("EVD-OPERATIONS-AUDIT", "backend/app/services/report_read_audit.py", 1, 80, "신고 조회 목적과 행위자를 남기는 감사기록 일부는 구현돼 있다."),
        _file_evidence("EVD-MAINTENANCE-LOCK", "backend/app/api/reports.py", 819, 837, "백업 중 신고 쓰기를 잠시 막기 위한 서버 잠금은 있으나 전체 보행 세션의 안전 종료·서비스 종료 절차까지 구현한 것은 아니다."),
        _file_evidence("EVD-ENCRYPTED-BACKUP", "scripts/backup_walksafe_data_20260711.sh", 291, 414, "데이터베이스와 신고 파일을 암호화하고 파일 지문과 별도 서명을 남기는 백업 절차가 있다."),
        _file_evidence("EVD-RESTORE-DRILL", "scripts/restore_walksafe_backup_drill_20260711.sh", 43, 99, "서명과 파일 지문을 확인한 백업만 빈 시험환경에 복원하는 훈련 절차가 있다."),
        _file_evidence("EVD-BACKUP-PRUNE", "scripts/prune_walksafe_backups_20260711.py", 33, 97, "서명된 백업만 보존기간과 최소 사본 수에 따라 정리하는 절차가 있다."),
        _file_evidence("EVD-VOICE-SERVER", "voice/server.py", 1, 100, "별도 server 음성 경로도 존재해 승인된 완전 단말내 실행 경계와 구분 검증이 필요하다."),
        _file_evidence("EVD-ANDROID-DEVICE-WORKFLOW", ".github/workflows/android-device-acceptance.yml", 160, 225, "실기기 자동 시험 절차는 모델 불러오기·실행만 확인하며 카메라·공간·거리 인식·음성안내·진동·TMAP을 함께 쓰는 현장 전체과정 시험은 포함하지 않는다."),
        _file_evidence("EVD-RELEASE-BLOCK", "docs/deliverables/06-testing/test-quality-report.md", 30, 40, "정식 품질 보고서는 출시 상태를 NOT_ELIGIBLE로 유지한다."),
        _file_evidence("EVD-FP035-RELEASE-DRIFT", "docs/deliverables/09-release/registers/release-control-register.json", 44, 68, "릴리스 통제 대장은 승인된 FP-035 정정을 아직 대기 중이며 미승인으로 표시한다."),
        _file_evidence("EVD-FP035-REQ-CONFLICT", "docs/deliverables/03-requirements/system-requirements.md", 6128, 6144, "공통 자동신고 절은 Wi-Fi가 없을 때 대기한다고 적어 승인된 이동통신망 opt-in 분기를 흐린다."),
        _file_evidence("EVD-FP035-DES-STALE", "docs/deliverables/04-design/security-and-operations-design.md", 20, 31, "승인 후에도 설계 문서 일부가 FP-035 정정안을 NOT_EFFECTIVE로 표시한다."),
    ]
    evidence.extend([
        _negative_evidence(
            "EVD-NEG-ANDROID-ADMIN-AUTH", kotlin_paths,
            [r"BiometricPrompt", r"Passkey", r"WebAuthn", r"FIDO"],
            "Android 구현 범위에서 관리자용 MFA·패스키 흐름을 찾지 못했다.",
        ),
        _negative_evidence(
            "EVD-NEG-ANDROID-QUEUE", kotlin_paths,
            [r"RoomDatabase", r"WorkManager", r"EncryptedFile", r"SQLCipher", r"SQLiteOpenHelper", r"SQLiteDatabase", r"JobScheduler", r"JobService", r"AlarmManager", r"TRANSPORT_CELLULAR", r"TRANSPORT_WIFI"],
            "Android 구현 범위에서 암호화된 영속 전송 대기함, 재부팅 후 자동 작업, Wi-Fi/이동통신망 분기 구성요소를 찾지 못했다.",
        ),
        _negative_evidence(
            "EVD-NEG-ANDROID-HEALTH", kotlin_paths,
            [r"ThermalStatus", r"ACTION_DEVICE_STORAGE_LOW", r"isPowerSaveMode"],
            "Android 구현 범위에서 발열·저장공간·절전 상태를 합성하는 안전정지 상태기계를 찾지 못했다.",
        ),
        _negative_evidence(
            "EVD-NEG-BACKEND-CAPACITY", backend_paths,
            [r"capacity_state_version", r"capacity_state_ttl", r"storage_percent.*70", r"storage_percent.*85", r"storage_percent.*95"],
            "백엔드 구현 범위에서 승인된 70/85/95/100% 용량상태 계약·TTL·version을 찾지 못했다.",
        ),
        _negative_evidence(
            "EVD-NEG-BACKEND-NETWORK-POLICY", backend_paths,
            [r"cellular_opt_in", r"walking_state", r"network_transport"],
            "백엔드 신고/자료 수신 경로에서 보행상태·이동통신망 opt-in·전송망 입력 검사를 찾지 못했다.",
        ),
    ])
    for item in evidence:
        item["claim"] = _plain_text(item["claim"])
    return evidence


# The status is a point-in-time conformance judgement, not a completion claim.
STATUS_BY_SOURCE = {
    "NPC-RAW-ORIGINAL-COLLECTION": "MISSING", "NPC-DATA-LIFECYCLE": "PARTIAL",
    "NPC-SERVER-STORAGE-CAPACITY": "MISSING", "NPC-PHONE-QUEUE-CAPACITY": "MISSING",
    "NPC-AUTO-REPORT": "CONFLICTING", "NPC-PERMISSION-SESSION-LIFECYCLE": "CONFLICTING",
    "NPC-NAVIGATION-ROUTE-DIRECTION": "CONFLICTING", "NPC-SINGLE-ADMIN-RECOVERY": "MISSING",
    "NPC-SERVER-CAPACITY-STATE-SYNC": "MISSING",
    "FP-001": "PARTIAL", "FP-002": "EVIDENCE_MISSING", "FP-003": "MISSING",
    "FP-004": "MISSING", "FP-005": "MISSING", "FP-006": "MISSING",
    "FP-007": "CONFLICTING", "FP-008": "MISSING", "FP-009": "CONFLICTING",
    "FP-010": "MISSING", "FP-011": "MISSING", "FP-012": "MISSING", "FP-013": "MISSING",
    "FP-014": "PARTIAL", "FP-015": "MISSING", "FP-016": "CONFLICTING",
    "FP-017": "CONFLICTING", "FP-018": "CONFLICTING", "FP-019": "CONFLICTING",
    "FP-020": "CONFLICTING", "FP-021": "CONFLICTING", "FP-022": "CONFLICTING",
    "FP-023": "CONFLICTING", "FP-024": "EVIDENCE_MISSING", "FP-025": "CONFLICTING",
    "FP-026": "PARTIAL", "FP-027": "CONFLICTING", "FP-028": "PARTIAL", "FP-029": "MISSING",
    "FP-030": "PARTIAL", "FP-031": "CONFLICTING", "FP-032": "CONFLICTING",
    "FP-033": "PARTIAL", "FP-034": "MISSING", "FP-035": "CONFLICTING", "FP-036": "MISSING",
    "FP-037": "CONFLICTING", "FP-038": "PARTIAL", "FP-039": "CONFLICTING",
    "FP-040": "PARTIAL", "FP-041": "PARTIAL", "FP-042": "PARTIAL",
    "FP-043": "CONFLICTING", "FP-044": "CONFLICTING", "FP-045": "MISSING",
    "FP-046": "MISSING", "FP-047": "CONFLICTING", "FP-048": "PARTIAL",
    "FP-049": "EVIDENCE_MISSING", "FP-050": "EVIDENCE_MISSING", "FP-051": "PARTIAL",
    "FP-052": "PARTIAL", "FP-053": "PARTIAL", "FP-054": "PARTIAL",
    "GATE-PHONE-QUEUE-BYTE-LIMIT": "BLOCKED",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT": "BLOCKED",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW": "BLOCKED",
    "GATE-CLOUD-COST-MEASUREMENT": "BLOCKED",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL": "BLOCKED",
}


def _domain(source_id: str) -> str:
    if source_id.startswith("GATE-"):
        return "출시 게이트"
    if source_id.startswith("NPC-"):
        mapping = {
            "NPC-RAW-ORIGINAL-COLLECTION": "자료·개인정보", "NPC-DATA-LIFECYCLE": "자료·개인정보",
            "NPC-SERVER-STORAGE-CAPACITY": "서버·운영", "NPC-PHONE-QUEUE-CAPACITY": "신고·전송",
            "NPC-AUTO-REPORT": "신고·전송", "NPC-PERMISSION-SESSION-LIFECYCLE": "가입·동의·권한",
            "NPC-NAVIGATION-ROUTE-DIRECTION": "경로·길안내", "NPC-SINGLE-ADMIN-RECOVERY": "인증·관리자",
            "NPC-SERVER-CAPACITY-STATE-SYNC": "서버·운영",
        }
        return mapping[source_id]
    if source_id == "FP-008": return "인증·관리자"
    number = int(source_id.split("-")[1])
    if number <= 3: return "제품·거버넌스"
    if number <= 6: return "사용자·안전범위"
    if number <= 9: return "앱·지원환경"
    if number <= 18: return "가입·동의·보행상태"
    if number <= 21: return "객체탐지·위험안내"
    if number <= 24: return "경로·길안내"
    if number <= 30: return "음성·접근성"
    if number <= 36: return "신고·자료전송"
    if number <= 39: return "AI·모델"
    if number <= 45: return "서버·장애대응"
    if number <= 48: return "보안·개인정보"
    if number <= 50: return "시험·현장검증"
    return "릴리스·운영"


P0_IDS = set("""NPC-RAW-ORIGINAL-COLLECTION NPC-DATA-LIFECYCLE NPC-SERVER-STORAGE-CAPACITY NPC-PHONE-QUEUE-CAPACITY NPC-AUTO-REPORT NPC-PERMISSION-SESSION-LIFECYCLE NPC-NAVIGATION-ROUTE-DIRECTION NPC-SINGLE-ADMIN-RECOVERY NPC-SERVER-CAPACITY-STATE-SYNC FP-002 FP-003 FP-004 FP-005 FP-006 FP-008 FP-010 FP-011 FP-013 FP-014 FP-015 FP-017 FP-018 FP-019 FP-020 FP-021 FP-022 FP-023 FP-025 FP-027 FP-028 FP-029 FP-030 FP-031 FP-032 FP-034 FP-035 FP-036 FP-037 FP-038 FP-039 FP-040 FP-041 FP-042 FP-043 FP-044 FP-045 FP-046 FP-047 FP-048 FP-049 FP-050 FP-051 FP-053 GATE-PHONE-QUEUE-BYTE-LIMIT GATE-SERVER-CAPACITY-STATE-CONTRACT GATE-RAW-COLLECTION-RELEASE-REVIEW GATE-CLOUD-COST-MEASUREMENT GATE-SINGLE-ADMIN-RECOVERY-DRILL""".split())
P2_IDS: set[str] = set()


def _priority(source_id: str) -> str:
    if source_id in P0_IDS: return "P0"
    if source_id in P2_IDS: return "P2"
    return "P1"


EVIDENCE_BY_DOMAIN = {
    "제품·거버넌스": ["EVD-PRODUCT-ROOT", "EVD-PRODUCT-ANDROID-README", "EVD-PRODUCT-WEB"],
    "사용자·안전범위": ["EVD-ANDROID-MANIFEST", "EVD-ANDROID-WALK-UI"],
    "앱·지원환경": ["EVD-PRODUCT-ANDROID-README", "EVD-ANDROID-SINGLE-MODULE", "EVD-ANDROID-BUILD"],
    "가입·동의·보행상태": ["EVD-ANDROID-ACCOUNT", "EVD-ANDROID-RESUME", "EVD-ANDROID-PERMISSION", "EVD-REPORT-CONSENT"],
    "객체탐지·위험안내": ["EVD-MODEL-RUNTIME", "EVD-METADATA-MEMORY"],
    "경로·길안내": ["EVD-NAV-DECISION", "EVD-NAV-AUTO-REROUTE"],
    "음성·접근성": ["EVD-VOICE-COMMAND", "EVD-VOICE-PLATFORM-STT", "EVD-TTS-HAPTIC", "EVD-TTS-FAILURE", "EVD-TALKBACK", "EVD-ACCESSIBILITY-UI"],
    "신고·자료전송": ["EVD-AUTO-CANDIDATE", "EVD-AUTO-UPLOAD", "EVD-REPORT-UPLOADER", "EVD-NEG-ANDROID-QUEUE"],
    "AI·모델": ["EVD-MODEL-REGISTRY", "EVD-MODEL-RUNTIME"],
    "서버·장애대응": ["EVD-BACKEND-AUTH", "EVD-BACKEND-REPORT-STORAGE", "EVD-NEG-BACKEND-CAPACITY", "EVD-NEG-ANDROID-HEALTH"],
    "보안·개인정보": ["EVD-BACKEND-AUTH", "EVD-BACKEND-BOUNDARY", "EVD-REPORT-CONSENT", "EVD-RAW-NOOP"],
    "시험·현장검증": ["EVD-MODEL-RUNTIME", "EVD-ANDROID-BUILD"],
    "릴리스·운영": ["EVD-ANDROID-BUILD", "EVD-BACKEND-BOUNDARY", "EVD-MODEL-REGISTRY"],
    "자료·개인정보": ["EVD-RAW-NOOP", "EVD-RAW-RELEASE-SELECTION", "EVD-RAW-RELEASE", "EVD-METADATA-MEMORY", "EVD-BACKEND-REPORT-STORAGE"],
    "서버·운영": ["EVD-BACKEND-REPORT-STORAGE", "EVD-NEG-BACKEND-CAPACITY"],
    "신고·전송": ["EVD-AUTO-UPLOAD", "EVD-REPORT-UPLOADER", "EVD-NEG-ANDROID-QUEUE", "EVD-NEG-BACKEND-NETWORK-POLICY"],
    "가입·동의·권한": ["EVD-ANDROID-ACCOUNT", "EVD-ANDROID-PERMISSION", "EVD-REPORT-CONSENT"],
    "인증·관리자": ["EVD-ANDROID-SINGLE-MODULE", "EVD-NEG-ANDROID-ADMIN-AUTH", "EVD-BACKEND-AUTH", "EVD-WEB-ADMIN"],
    "출시 게이트": [],
}

SPECIAL_EVIDENCE = {
    "FP-002": ["EVD-RELEASE-BLOCK"],
    "FP-003": ["EVD-ANDROID-SINGLE-MODULE", "EVD-NEG-ANDROID-ADMIN-AUTH", "EVD-BACKEND-AUTH"],
    "FP-004": ["EVD-ANDROID-ACCOUNT", "EVD-ANDROID-WALK-UI"],
    "FP-005": ["EVD-IMAGE-PREPROCESS", "EVD-DEVICE-GATE", "EVD-DETECTION-FAILURE"],
    "FP-006": ["EVD-IMAGE-PREPROCESS", "EVD-DEVICE-GATE"],
    "FP-001": ["EVD-PRODUCT-PURPOSE", "EVD-PRODUCT-ROOT"],
    "FP-007": ["EVD-PRODUCT-ANDROID-README", "EVD-PRODUCT-WEB"],
    "FP-008": ["EVD-ANDROID-SINGLE-MODULE", "EVD-NEG-ANDROID-ADMIN-AUTH", "EVD-WEB-ADMIN"],
    "FP-009": ["EVD-PRODUCT-ANDROID-README", "EVD-PRODUCT-WEB"],
    "FP-010": ["EVD-ANDROID-ACCOUNT"], "FP-011": ["EVD-ANDROID-ACCOUNT"], "FP-012": ["EVD-ANDROID-ACCOUNT"],
    "FP-013": ["EVD-REPORT-CONSENT", "EVD-RAW-NOOP"], "FP-014": ["EVD-ANDROID-PERMISSION"],
    "FP-015": ["EVD-REPORT-CONSENT"], "FP-016": ["EVD-ANDROID-WALK-UI"],
    "FP-017": ["EVD-ANDROID-RESUME"], "FP-018": ["EVD-ANDROID-RESUME"],
    "FP-019": ["EVD-MODEL-REGISTRY", "EVD-MODEL-ANDROID-CONFIG", "EVD-DETECTION-FAILURE"],
    "FP-020": ["EVD-DETECTION-MESSAGE", "EVD-DETECTION-HAPTIC"],
    "FP-021": ["EVD-IMAGE-PREPROCESS", "EVD-MODEL-ANDROID-CONFIG"],
    "FP-022": ["EVD-NAV-DECISION", "EVD-NAV-AUTO-REROUTE"], "FP-023": ["EVD-NAV-DECISION", "EVD-NAV-AUTO-REROUTE"],
    "FP-024": ["EVD-TACTILE-POLICY", "EVD-TACTILE-GUIDANCE"],
    "FP-025": ["EVD-VOICE-PLATFORM-STT", "EVD-VOICE-COMMAND"], "FP-027": ["EVD-TTS-HAPTIC", "EVD-TTS-FAILURE"],
    "FP-026": ["EVD-VOICE-PARSER", "EVD-VOICE-UNMATCHED"],
    "FP-028": ["EVD-TALKBACK", "EVD-ACCESSIBILITY-UI", "EVD-ANDROID-WALK-UI"],
    "FP-029": ["EVD-ANDROID-PERMISSION", "EVD-DETECTION-FAILURE", "EVD-ANDROID-WALK-UI"],
    "FP-030": ["EVD-ACCESSIBILITY-UI", "EVD-TALKBACK"],
    "FP-031": ["EVD-AUTO-CANDIDATE", "EVD-AUTO-UPLOAD", "EVD-REPORT-CONSENT"],
    "FP-032": ["EVD-REPORT-UPLOADER", "EVD-NEG-ANDROID-QUEUE"], "FP-033": ["EVD-ANDROID-SINGLE-MODULE", "EVD-WEB-ADMIN"],
    "FP-034": ["EVD-RAW-NOOP", "EVD-RAW-RELEASE-SELECTION", "EVD-RAW-RELEASE", "EVD-METADATA-MEMORY"],
    "FP-035": ["EVD-AUTO-UPLOAD", "EVD-NEG-ANDROID-QUEUE", "EVD-NEG-BACKEND-NETWORK-POLICY", "EVD-FP035-REQ-CONFLICT", "EVD-FP035-DES-STALE"],
    "FP-036": ["EVD-RAW-NOOP", "EVD-NEG-ANDROID-QUEUE", "EVD-BACKEND-REPORT-STORAGE", "EVD-REPORT-RETENTION"],
    "FP-037": ["EVD-MODEL-REGISTRY", "EVD-MODEL-ANDROID-CONFIG", "EVD-CANDIDATE-REPORT-POLICY", "EVD-AUTO-UPLOAD"],
    "FP-038": ["EVD-MODEL-TRAIN", "EVD-DATASET-VALIDATION", "EVD-MODEL-REGISTRY", "EVD-RAW-RELEASE-SELECTION"],
    "FP-039": ["EVD-MODEL-REGISTRY", "EVD-MODEL-LOCAL-DEPLOYMENT", "EVD-MODEL-FALLBACK"],
    "FP-040": ["EVD-GATEWAY-ENDPOINT", "EVD-REPORT-UPLOADER", "EVD-BACKEND-BOUNDARY"],
    "FP-041": ["EVD-REPORT-DATABASE", "EVD-BACKEND-REPORT-STORAGE", "EVD-NEG-BACKEND-CAPACITY"],
    "FP-042": ["EVD-TMAP-SERVER-CONFIG", "EVD-TMAP-BOUNDED", "EVD-BACKEND-BOUNDARY"],
    "FP-043": ["EVD-TTS-FAILURE", "EVD-NEG-ANDROID-HEALTH"],
    "FP-044": ["EVD-AUTO-UPLOAD", "EVD-VOICE-PLATFORM-STT", "EVD-NEG-ANDROID-QUEUE"],
    "FP-045": ["EVD-NEG-ANDROID-HEALTH", "EVD-NEG-ANDROID-QUEUE"],
    "FP-046": ["EVD-REPORT-CONSENT", "EVD-RAW-RELEASE-SELECTION"],
    "FP-047": ["EVD-BACKEND-AUTH", "EVD-NEG-ANDROID-ADMIN-AUTH", "EVD-ANDROID-ACCOUNT"],
    "FP-048": ["EVD-GATEWAY-ENDPOINT", "EVD-DATABASE-TLS", "EVD-BACKEND-REPORT-STORAGE"],
    "FP-049": ["EVD-RELEASE-BLOCK"], "FP-050": ["EVD-ANDROID-DEVICE-WORKFLOW", "EVD-RELEASE-BLOCK"],
    "FP-051": ["EVD-ANDROID-RELEASE-CONTRACT", "EVD-MODEL-LOCAL-DEPLOYMENT", "EVD-RELEASE-BLOCK"],
    "FP-052": ["EVD-OPERATIONS-HEALTH", "EVD-OPERATIONS-AUDIT", "EVD-BACKEND-BOUNDARY"],
    "FP-053": ["EVD-ENCRYPTED-BACKUP", "EVD-RESTORE-DRILL", "EVD-BACKUP-PRUNE", "EVD-REPORT-RETENTION", "EVD-NEG-BACKEND-CAPACITY"],
    "FP-054": ["EVD-MAINTENANCE-LOCK", "EVD-OPERATIONS-HEALTH", "EVD-RELEASE-BLOCK"],
    "NPC-AUTO-REPORT": ["EVD-AUTO-UPLOAD", "EVD-REPORT-CONSENT", "EVD-NEG-ANDROID-QUEUE"],
    "NPC-DATA-LIFECYCLE": ["EVD-REPORT-RETENTION", "EVD-RAW-RELEASE-SELECTION", "EVD-BACKEND-REPORT-STORAGE"],
    "NPC-NAVIGATION-ROUTE-DIRECTION": ["EVD-NAV-DECISION", "EVD-NAV-AUTO-REROUTE"],
    "NPC-SINGLE-ADMIN-RECOVERY": ["EVD-NEG-ANDROID-ADMIN-AUTH", "EVD-ANDROID-SINGLE-MODULE"],
}


REMEDIATION_BY_DOMAIN = {
    "제품·거버넌스": "Android 사용자 앱과 별도 Android 관리자 앱만 정식 제품으로 선언하고 Web/PWA를 LEGACY_REFERENCE_ONLY로 격리한다. 빌드·배포·문서의 제품 경계를 같은 값으로 검사한다.",
    "사용자·안전범위": "지원 사용자·장착·환경·안전 제한을 Android 온보딩과 시험 시나리오에 연결하고, 지원하지 않는 조건에서는 기능을 안전정지한다.",
    "앱·지원환경": "사용자 앱과 관리자 앱을 app ID·서명·세션·배포 채널까지 분리하고 지원 기기별 승인 build를 만든다.",
    "가입·동의·보행상태": "가입·본인확인·버전 동의·권한·로그인·보행 상태를 명시적 상태기계로 구현하고 Android Keystore 기반 세션과 권리행사 흐름을 연결한다.",
    "객체탐지·위험안내": "승인 클래스·거리·신뢰도·영상품질·안전정지 규칙을 단말 runtime에 연결하고 실제 기기 원자료로 오탐·미탐·지연을 검증한다.",
    "경로·길안내": "TMAP 경로, GPS, 진행방향, 보폭의 책임을 분리하고 도착은 사용자 확인으로, 이탈은 승인된 10단계 선택 흐름으로 구현한다.",
    "음성·접근성": "길라잡이 호출어와 단말내 한국어 STT/TTS, 진동 대체, 전체 TalkBack 흐름을 구현하고 소음·오프라인·장애 조건에서 시험한다.",
    "신고·자료전송": "암호화 영속 queue를 만들고 보행 종료·Wi-Fi·이동통신망 opt-in·재시도·중복방지·삭제순서를 하나의 상태기계와 서버 receipt로 연결한다.",
    "AI·모델": "데이터 검사, 재현 가능한 학습, 독립 평가, 모델 registry·서명·동등성·원자 교체·이전 정상모델 복구를 통제된 pipeline으로 만든다.",
    "서버·장애대응": "단일 gateway, 계정 기반 인증/RBAC, object storage, 용량상태 계약, 백업·복구와 통합 health 안전정지를 구현한다.",
    "보안·개인정보": "수집 항목별 동의·암호화·보존·삭제·감사·권리행사와 사용자/관리자 인증 경계를 구현하고 독립 보안·개인정보 검토를 수행한다.",
    "시험·현장검증": "승인된 시험계획대로 지원 기기·접근성·현장·성능·안전 시험을 실행하고 원자료 지문, 실제 결과, 결함 연결을 남긴다.",
    "릴리스·운영": "서명된 Android release, 단계 배포·rollback, 대시보드·알림·백업복원·비용·서비스 종료 절차를 실제 실행 증거와 함께 만든다.",
    "자료·개인정보": "승인된 영상·음성·정확 위치·센서·경로·신고·성능 원본 schema와 암호화 수집, receipt, 보존·삭제 worker를 구현한다.",
    "서버·운영": "원본 object storage와 DB 메타데이터를 분리하고 70/85/95/100% 용량정책, 백업·복원·비용 측정과 단말 동기화를 구현한다.",
    "신고·전송": "보행 중 전송 금지, 종료 후 Wi-Fi 우선, 명시적 이동통신망 opt-in, 재부팅 후 이어보내기와 조용한 용량 보류를 구현한다.",
    "가입·동의·권한": "권한·로그인·동의를 서로 다른 상태로 저장하고 철회·만료·앱 이탈·재부팅마다 종속 기능을 정확히 중지·재확인한다.",
    "인증·관리자": "별도 Android 관리자 앱에 MFA/패스키, 별도 복구수단, 세션 폐기, 고위험 작업 재인증·동결과 감사로그를 구현한다.",
    "출시 게이트": "게이트별 측정·독립검토·복구훈련을 실제로 수행하고 승인된 증거와 결론을 연결한다. 문서 작성이나 단위시험으로 대체하지 않는다.",
}

REMEDIATION_BY_SOURCE = {
    "FP-001": "위험안내·경로안내·신고의 세 가지 제품 목적과 ‘보행 안전을 보장하거나 보호자를 대신하지 않는다’는 제한을 Android 첫 화면, 동의 화면, 사용자 설명서와 출시 문서에 같은 문장으로 고정한다.",
    "FP-002": "시연, 제한된 사용자시험, 정식 공개의 세 단계를 나누고 단계마다 시작·종료 조건을 정한다. 같은 앱·서버·모델·설정 묶음으로 시험한 기록과 승인 없이는 다음 단계로 넘어가지 못하게 한다.",
    "FP-003": "한 명의 최종관리자 체계에 MFA/패스키, 휴대전화 밖 복구코드·보안키, 원격 세션 폐기, 고위험 작업 동결을 구현하고 실제 분실 복구훈련을 한다.",
    "FP-008": "사용자 앱과 별개 app ID·서명·세션을 쓰는 Android 관리자 앱을 만들고 로그인, 검수, 기관 전달, 감사까지 관리자 흐름을 분리한다.",
    "FP-009": "정식 Android 앱 시작 때 지원 기기의 카메라·거리·위치·음성·진동 능력을 검사하고 부족하면 제한 또는 안전정지한다. Web 앱은 정식 사용자 실행·배포 경로에서 기술적으로 차단한다.",
    "FP-010": "연령 확인, 전화번호 확인, 가입, 통합 동의, 권한, 사용훈련을 순서가 있는 첫 실행 상태기계로 구현한다.",
    "FP-011": "Android 보안 저장소 기반 접근용·회전 갱신용 로그인 증명과 만료·재발급·원격 폐기 흐름을 구현하고 앱 재시작·장기 미사용·로그인 증명값 탈취를 시험한다.",
    "FP-012": "계정별 기기·세션 원장을 만들고 여러 기기 로그인은 허용하되 동시에 한 보행만 활성화되도록 서버 동시 보행 잠금과 원격 로그아웃을 구현한다.",
    "FP-013": "원본수집·자동신고·이동통신망·학습재사용을 각각 버전 관리하는 통합 동의 화면과 서버 관리대장을 만들고 동의하지 않은 항목은 수집·전송하지 않는다.",
    "FP-014": "카메라·위치·마이크 권한마다 허용 기능과 중지 기능을 분리하고, 거부·일시허용 만료·설정 변경 뒤에는 전체 상태를 다시 검사한 후 사용자가 명시적으로 재개하게 한다.",
    "FP-015": "동의 철회나 계정 삭제가 시작되면 해당 수집·자동신고·학습재사용을 즉시 중지하고 휴대전화 미전송 자료를 삭제하며 서버 저장자료 삭제요청과 완료확인을 연결한다.",
    "FP-016": "정상 보행 화면을 읽기 전용 카메라·안전상태로 분리하고 개발 입력을 release에서 제거하며 뒤로가기 즉시 일시중지와 종료 확인을 구현한다.",
    "FP-017": "READY→ACTIVE→PAUSED→RECHECK→CONFIRMED_RESUME 상태기계를 만들고 잠금·Home·통화·앱 전환 뒤 사용자 확인 전 자동 재개를 막는다.",
    "FP-018": "권한·센서·경로·TTS·모델을 재검사한 뒤에만 사용자가 보행을 재개하게 하고 재부팅·강제종료 뒤 이전 보행 자동복원을 금지한다.",
    "FP-019": "배포 적격으로 승인된 모델·탐지대상·판단 기준값만 정식 배포본에서 활성화하고 관측번호·연결확실성 추적 기록과 반복 실패 전체 안전정지를 구현한다.",
    "FP-020": "STOP 문구를 ‘멈추세요. 주변을 확인하세요.’로 고정하고 WARNING 고유 진동, 불확실 후보 무안내, 좌우 추정 지시 금지를 계약시험으로 잠근다.",
    "FP-021": "밝기·가림·흔들림·카메라각 사전 품질 gate와 class별 거리·신뢰도 gate를 구현하고 거리 미지원 제한모드는 실기기 사전시험과 사용자 확인 뒤에만 허용한다.",
    "FP-022": "남은 거리는 GPS와 저장 TMAP 경로를 기준으로, 보폭은 보조 검증으로 사용한다. GPS·경로 끝·보폭을 함께 본 뒤 사용자 확인으로 도착을 확정한다.",
    "FP-023": "경로를 암호화해 종료 또는 24시간까지 보관하고, 이탈 때 회전안내 중지→설명→새 경로/재확인/보행종료 선택의 승인된 10단계를 구현한다.",
    "FP-024": "TMAP 경로를 주 기준으로 유지하면서 휴대전화가 확인한 점자블록은 방향을 확정할 수 있을 때만 보조한다. 손상 점자블록은 경로 지시와 분리해 신고 후보로 처리하고 불확실하면 안내하지 않는다.",
    "FP-025": "활성 보행에서만 작동하는 ‘길라잡이’ 호출어, 단말내 오프라인 한국어 STT, 듣기 시작·끝 신호와 원음·STT 보존 생명주기를 구현한다.",
    "FP-026": "승인 명령 목록과 상태전이를 모두 구현하고 confidence가 없거나 낮아도 실행하지 않으며 비가역 명령은 음성·TalkBack으로 확인받는다.",
    "FP-027": "보행 시작 전 오프라인 한국어 TTS를 확인하고 제한 재시도 후에도 실패하면 화면·진동으로 알린 뒤 모든 보행 기능을 안전정지한다.",
    "FP-028": "가입·동의·권한·보행·일시중지·복구·종료·삭제요청 화면의 읽는 순서, 버튼 역할, 상태변경 알림과 포커스를 Android 화면읽기 기능으로 완결하고 화면읽기만으로 전 과정을 시험한다.",
    "FP-029": "권한 거부·지속 장애를 한 접근 가능한 안전정지 화면으로 통합하고 종료·설정·재검사 후 명시 재개를 TalkBack으로 완결한다.",
    "FP-030": "큰 글자, 충분한 색상 대비, 넓은 터치영역, 읽는 순서와 상태 설명을 모든 사용자 화면에 적용하고 TalkBack만으로 가입부터 보행 종료·복구까지 완주 시험한다.",
    "FP-031": "자동신고 동의를 지속 설정으로 저장하고 반복관측 후보는 보행 종료 뒤 암호화 queue에 넣어 허용된 통신망에서만 조용히 전송한다.",
    "FP-032": "client_report_id, 영속 재시도 상태, 동일 장소 관찰 병합, 서버 receipt와 사용자용 제한 상태조회를 구현하고 응답 유실·동시 재시도를 시험한다.",
    "FP-033": "Android 관리자 앱에 접수·검수·기각사유·기관제출·기관수신·해결 상태를 분리하고 제출본 version과 수신증거를 감사로그에 남긴다.",
    "FP-034": "영상·RGB·depth·음성·STT·정확 위치·센서·경로·탐지·신고·성능을 수집 시 압축 원본으로 묶는 session/segment schema와 암호화 저장을 구현한다.",
    "FP-035": "보행 중에는 socket 전송 자체를 금지하고, 정지 뒤 Wi-Fi 또는 명시적 이동통신망 opt-in일 때만 이어보내며 움직임 재개 즉시 중단한다.",
    "FP-036": "원본 조각별 SHA-256 receipt와 서버 영속 확인 뒤 단말 사본 삭제를 연결하고 14일·7일·3년·35일 등 승인된 저장소별 보존·삭제 worker를 구현한다.",
    "FP-037": "candidate 모델은 명명된 통제 시연에서만 사용하고 release 위험안내·자동신고·기관 반출은 승인 모델·앱·설정 bundle일 때만 허용한다.",
    "FP-038": "출처·동의 ledger와 content hash, 촬영 sequence가 섞이지 않은 분할, 잠긴 독립시험셋, 독립평가·실폰시험·철회자료 제거를 완료한다.",
    "FP-039": "앱·서버·모델·탐지대상·판단 기준값·설정·데이터베이스 세대를 하나의 서명 구성 목록으로 묶고 승인된 이전 정상 조합으로만 중간 상태 없이 되돌아가도록 구현·훈련한다.",
    "FP-040": "정식 build의 승인된 서버 주소 제한은 유지하고, 로그인·TMAP·신고·원본·관리자 요청 전체를 계정 기반 보호 서버와 중앙 역할별 권한검사로 통일한다. 개발용 수동 주소·인증값은 정식 build에 들어가지 않게 검사한다.",
    "FP-041": "계정·동의·신고 메타데이터 DB와 KMS 암호화 object storage를 분리하고 원본 manifest·receipt·migration·삭제 tombstone을 구현한다.",
    "FP-042": "지도·경로 서비스의 비밀값은 서버에만 두고 사용자 요청과 자동처리 요청의 자원을 분리한다. 요청 중복 제거, 속도·일일 사용량·월 비용 한도와 초과 시 안전한 대체 안내를 구현한다.",
    "FP-043": "카메라·거리·위치·위험판정·TTS 실패를 중앙 SafetyStateMachine에 모아 핵심 기능 불신 시 모두 정지하고 사용자 재확인 뒤에만 재개한다.",
    "FP-044": "30일 암호화 영속 queue, 보행 중 전송 금지, 정지+통신망 동의, idempotency·TTL·용량 계약을 구현하고 비행기모드·재부팅·queue-full을 시험한다.",
    "FP-045": "저장공간·배터리·발열·frame age·추론지연을 감시해 승인된 축소 순서와 전체 정지 기준을 적용하고 실제 지원폰 장시간 측정으로 수치를 확정한다.",
    "FP-046": "동의·철회·전체삭제 요청/API와 저장소별 삭제 orchestration, 부분실패 재시도, backup 복원 뒤 tombstone 재적용, 3년 삭제영수증을 구현한다.",
    "FP-047": "사용자 기기별 회전 세션과 별도 관리자 MFA/패스키, 고위험 재인증·동결, 원격폐기·복구를 계정 기반 RBAC와 감사로그로 구현한다.",
    "FP-049": "하나의 불변 RC를 지정해 자동·실폰 E2E·접근성·현장·개인정보·보안 시험을 모두 실행하고 중대결함 0과 5개 gate 종료 뒤 TST-22를 판단한다.",
    "FP-050": "지원폰별 전체 기능 동시 장시간 성능·열·배터리·queue를 측정하고 안전요원·중단계획 아래 목표사용자 현장시험을 수행한다.",
    "FP-051": "서명된 불변 release manifest, 단계 배포, 활성 보행 중 update 연기, 신규 세션 kill switch와 데이터 무손실 compatible rollback을 실제로 훈련한다.",
    "FP-052": "보행 안전상태, 신고 전송, 저장공간, 외부 서비스, 모델·앱 판본을 한 운영 현황판에 모으고 단계별 알림·자동차단·담당자 호출·비상대응 절차를 실제 장애훈련으로 검증한다.",
    "FP-053": "primary/backup 사용량·비용 worker, 70/85/95/100% 동작, 35일 backup 순환과 격리 복원시험을 구현하고 월 비용을 실측한다.",
    "FP-054": "진행 중 보행 drain·신규 보행 차단·안전한 migration·rollback·서비스 종료·자료 이관/삭제 절차를 한 RC에서 훈련해 실행 증거를 남긴다.",
}

USER_IMPACT_BY_SOURCE = {
    "FP-001": "제품 목적이 문서와 화면마다 다르면 사용자가 위험안내·길안내·신고의 한계와 책임 범위를 잘못 이해할 수 있다.",
    "FP-033": "관리자 검수가 잘못되거나 필요 이상의 원본이 공유되고, 기관이 받지 않은 신고를 받은 것으로 오해하며, 사용자가 자기 신고의 조회·정정·삭제를 요청하지 못할 수 있다.",
}

CURRENT_BEHAVIOR_BY_SOURCE = {
    "FP-001": "보행 위험안내와 신고 목적 일부는 문서에 적혀 있지만, 공식 첫 설명은 Web/PWA를 주 앱으로 두고 있어 승인된 Android 제품 목적과 안전 한계가 한 흐름으로 정리돼 있지 않다.",
    "FP-002": "현재 정식 품질보고서는 출시 부적격을 유지하고 있으며 승인된 정식 시험 279개와 5개 출시 확인 관문이 모두 미실행이다. 단계별 완료를 증명할 실행 기록이 아직 없다.",
    "FP-026": "음성 인식의 첫 후보만 사용하고 낮은 신뢰도·부정 표현·등록되지 않은 말은 실행하지 않는 기본 명령 해석기가 있다. 다만 일시정지·재개 상태 제한과 되돌리기 어려운 명령의 접근 가능한 확인 흐름은 완성되지 않았다.",
    "FP-030": "TalkBack 활성 여부를 감지하고 일부 상태를 음성으로 안내하지만, 작은 글자와 개발용 조작이 한 화면에 섞여 가입부터 복구까지 화면읽기만으로 끝낼 수 있는지는 확인되지 않았다.",
    "FP-037": "배포 부적격으로 등록된 후보 모델이 Android의 주 모델로 켜져 있고, 그 모델의 탐지 결과가 자동신고 후보와 즉시 서버 전송까지 이어진다. 통제된 시연 밖 전송을 막는 정식 배포 차단장치는 확인되지 않았다.",
    "FP-038": "자료·모델·반복횟수를 정한 수동 학습, 영상·라벨의 기초 검사와 후보 등록부는 있다. 승인된 원본 수집부터 검역, 촬영순서가 섞이지 않는 분할, 잠긴 독립평가, 승인 승격까지 잇는 자동 흐름은 없다.",
    "FP-040": "정식 앱 설치본은 승인된 HTTPS 보호 서버 주소 하나만 사용하고 신고도 로그인된 보호 서버 세션으로 보낸다. 그러나 실제 계정 인증, 중앙 역할별 접근권한과 모든 API의 통일은 아직 완성되지 않았다.",
    "FP-041": "공간 좌표와 탐지·신고 정보를 데이터베이스에 저장하고 이미지를 별도 파일로 두는 기본 분리는 있다. 계정·동의 데이터베이스, 원본용 대용량 파일 저장소, 저장확인·이관·삭제 완료 표식은 없다.",
    "FP-042": "지도 서비스 비밀값을 서버에 두고 비밀값 누락·시간초과·과대 응답을 막는 경로는 있다. 보행 핵심 요청과 대용량 처리의 자원 분리, 중복 처리 방지와 사용량·비용 제한은 완성되지 않았다.",
    "FP-048": "정식 Android 요청 주소와 외부 데이터베이스 연결은 암호화 연결로 제한한다. 하지만 휴대전화 대기자료·신고 이미지·원본·백업의 저장 암호화, 열쇠 분리·회전과 전체 구간 검증은 완성되지 않았다.",
    "FP-051": "정식 Android 앱 설치본을 소스 지문과 승인 서버 주소에 묶고 잘못된 값이면 생성을 막는 기본 계약은 있다. 서명된 최종 묶음, 별도 관리자 앱, 단계 배포, 보행 중 갱신 연기와 실제 되돌리기 훈련은 아직 완성되지 않았다.",
    "FP-053": "데이터베이스와 신고 파일을 암호화·서명해 백업하고 검증된 백업을 빈 시험환경에 복원하며 오래된 백업을 정리하는 절차는 있다. 실제 복원 합격 기록, 35일 순환, 비용 측정과 70·85·95·100% 용량 자동대응은 없다.",
    "FP-054": "백업 중 새 신고 쓰기를 잠시 막는 서버 잠금과 상태 점검 일부는 있다. 진행 중 보행의 안전 종료, 신규 보행 차단, 자료 이관·삭제, 서비스 종료 훈련 전체는 아직 없다.",
}


USER_IMPACT_BY_DOMAIN = {
    "제품·거버넌스": "팀이 다른 제품을 구현·시험·배포해 결과를 잘못 승인할 수 있다.",
    "사용자·안전범위": "지원하지 않는 환경에서 사용자가 기능을 과신할 수 있다.",
    "앱·지원환경": "사용자와 관리자의 권한·배포 경계가 섞이고 지원 기기 동작을 보장할 수 없다.",
    "가입·동의·보행상태": "동의·권한·세션이 잘못 유지되거나 보행 안내가 사용자 확인 없이 재개될 수 있다.",
    "객체탐지·위험안내": "위험을 놓치거나 없는 위험을 안내해 보행 판단을 방해할 수 있다.",
    "경로·길안내": "잘못된 도착·이탈·재탐색 안내로 사용자가 경로를 벗어날 수 있다.",
    "음성·접근성": "화면을 보지 않는 사용자가 기능을 시작·복구·종료하지 못하거나 핵심 안내를 놓칠 수 있다.",
    "신고·자료전송": "보행 중 또는 허용하지 않은 통신망으로 자료가 전송되고, 실패 자료가 유실·중복될 수 있다.",
    "AI·모델": "어떤 모델이 안전하게 배포됐는지 증명하거나 문제 모델을 신속히 되돌릴 수 없다.",
    "서버·장애대응": "저장공간·외부 서비스·인증 장애가 사용자 앱의 잘못된 정상 동작으로 이어질 수 있다.",
    "보안·개인정보": "민감한 영상·음성·위치가 잘못 수집·접근·보존되거나 삭제 요구가 이행되지 않을 수 있다.",
    "시험·현장검증": "코드가 실행된다는 사실을 실제 보행 안전과 접근성 충족으로 오인할 수 있다.",
    "릴리스·운영": "잘못된 build 배포, 복구 실패, 장기 장애를 통제하지 못할 수 있다.",
    "자료·개인정보": "승인된 학습 원본이 생성되지 않거나 민감자료의 보존·삭제를 통제하지 못한다.",
    "서버·운영": "비용·용량 초과로 새 자료가 예고 없이 유실되거나 서비스가 멈출 수 있다.",
    "신고·전송": "자동신고가 정책과 다른 시점·통신망으로 보내지거나 대기자료가 유실될 수 있다.",
    "가입·동의·권한": "사용자의 의사와 다른 수집·기능 실행 또는 권한 철회 후 오동작이 생길 수 있다.",
    "인증·관리자": "관리자 휴대전화 분실이나 계정 탈취 때 서비스 통제권과 민감자료를 잃을 수 있다.",
    "출시 게이트": "측정되지 않은 용량·비용·복구·개인정보 위험을 안고 출시할 수 있다.",
}


def _owner(domain: str) -> str:
    if domain in {"AI·모델", "객체탐지·위험안내"}: return "AI·Android 기술책임자"
    if domain in {"서버·장애대응", "서버·운영", "릴리스·운영"}: return "백엔드·운영 기술책임자"
    if domain in {"보안·개인정보", "자료·개인정보", "인증·관리자"}: return "보안·개인정보책임자"
    if domain in {"시험·현장검증", "출시 게이트"}: return "QA책임자"
    return "Android 기술책임자"


LARGE_IMPLEMENTATION_IDS = set("""NPC-RAW-ORIGINAL-COLLECTION NPC-DATA-LIFECYCLE NPC-SERVER-STORAGE-CAPACITY NPC-PHONE-QUEUE-CAPACITY NPC-AUTO-REPORT NPC-PERMISSION-SESSION-LIFECYCLE NPC-SINGLE-ADMIN-RECOVERY NPC-SERVER-CAPACITY-STATE-SYNC FP-008 FP-010 FP-011 FP-012 FP-013 FP-015 FP-017 FP-018 FP-019 FP-021 FP-022 FP-023 FP-025 FP-028 FP-029 FP-030 FP-031 FP-032 FP-033 FP-034 FP-035 FP-036 FP-038 FP-039 FP-041 FP-043 FP-044 FP-045 FP-046 FP-047 FP-048 FP-049 FP-050 FP-051 FP-053 FP-054""".split())
LARGE_VERIFICATION_IDS = set("""FP-004 FP-005 FP-006 FP-019 FP-020 FP-021 FP-022 FP-023 FP-024 FP-025 FP-026 FP-027 FP-028 FP-029 FP-030 FP-037 FP-038 FP-039 FP-046 FP-048 FP-049 FP-050 FP-051 GATE-PHONE-QUEUE-BYTE-LIMIT GATE-SERVER-CAPACITY-STATE-CONTRACT GATE-RAW-COLLECTION-RELEASE-REVIEW GATE-CLOUD-COST-MEASUREMENT GATE-SINGLE-ADMIN-RECOVERY-DRILL""".split())


def _efforts(source_id: str, status: str, domain: str) -> tuple[str, str, str]:
    implementation = "해당 없음" if status == "BLOCKED" else ("L" if source_id in LARGE_IMPLEMENTATION_IDS else "M")
    verification = "L" if source_id in LARGE_VERIFICATION_IDS else "M"
    overall = "L" if "L" in {implementation, verification} else "M"
    return implementation, verification, overall


def _status_rationale(status: str, observation: str) -> str:
    prefix = {
        "PARTIAL": "관련 코드 일부는 있으나 승인 규칙 전체와 실행 증거가 완성되지 않았다.",
        "MISSING": "승인 규칙을 책임질 핵심 구현 경로를 현재 동결 범위에서 확인하지 못했다.",
        "CONFLICTING": "현재 구현 또는 운영 설명이 승인 규칙과 반대 동작을 포함한다.",
        "EVIDENCE_MISSING": "후보 구현은 있으나 승인된 환경·방법으로 실행한 합격 증거가 없다.",
        "BLOCKED": "출시 전 완료해야 할 측정·검토·훈련이 NOT_RUN이다.",
        "IMPLEMENTED": "구현과 승인된 검증 증거가 모두 확인됐다.",
    }[status]
    return f"{prefix} 기존 RTM 관찰: {observation}"


def build_assessments(
    policy: dict[str, Any], rtm: dict[str, Any], gates: list[dict[str, Any]], evidence: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    requirement_by_source = {row["source_policy_id"]: row for row in rtm["requirements"]}
    _require(len(requirement_by_source) == 68, "RTM source mapping is not one-to-one")
    feature_by_id = {row["id"]: row for row in policy["features"]}
    common_by_id = {row["id"]: row for row in policy["common_policies"]}
    gate_by_id = {row["id"]: row for row in gates}
    source_ids = [row["id"] for row in policy["common_policies"]] + [row["id"] for row in policy["features"]] + [row["id"] for row in gates]
    _require(set(source_ids) == set(STATUS_BY_SOURCE), "explicit conformance map does not cover exactly 68 sources")
    evidence_by_id = {row["evidence_id"]: row for row in evidence}
    evidence_ids = set(evidence_by_id)
    assessments = []
    for index, source_id in enumerate(source_ids, 1):
        requirement = requirement_by_source[source_id]
        source_kind = requirement["source_kind"]
        if source_kind == "FEATURE_POLICY":
            source = feature_by_id[source_id]
            policy_summary = " ".join([
                f"목적: {source['user_purpose']}",
                f"핵심 규칙: {' '.join(source['design_rules'])}",
                f"함께 적용할 최신 결정: {source['effective_policy_summary']}",
            ])
            failure_behavior = list(source.get("failure_behavior", []))
        elif source_kind == "COMMON_POLICY":
            source = common_by_id[source_id]
            policy_summary = source["summary"]
            failure_behavior = []
        else:
            source = gate_by_id[source_id]
            policy_summary = source["completion_condition"]
            failure_behavior = []
        status = STATUS_BY_SOURCE[source_id]
        domain = _domain(source_id)
        linked_evidence = SPECIAL_EVIDENCE.get(source_id, EVIDENCE_BY_DOMAIN[domain])
        if source_kind == "REMAINING_GATE":
            linked_evidence = []
        _require(set(linked_evidence) <= evidence_ids, f"unknown evidence ID for {source_id}")
        current_behavior = CURRENT_BEHAVIOR_BY_SOURCE.get(
            source_id,
            " ".join(evidence_by_id[item]["claim"] for item in linked_evidence[:3]),
        )
        if not current_behavior:
            current_behavior = "실제 완료 기록이 없고 연결된 정식 시험 또는 게이트가 NOT_RUN이다."
        design_ids = [link["design_id"] for link in requirement.get("design_trace", {}).get("links", [])]
        acceptance = [
            condition.get("then") or condition.get("expected_result") or "승인 기준을 만족한다."
            for condition in requirement.get("acceptance_conditions", [])
        ]
        acceptance.append("연결된 정식 시험을 승인된 환경에서 실행해 모두 합격하고, 원자료 SHA-256·실제 결과·결함 연결을 남긴다.")
        blocking_gate_ids = list(requirement.get("gate_refs") or []) + list(requirement.get("related_gate_ids") or [])
        if source_kind == "REMAINING_GATE":
            blocking_gate_ids = []
        implementation_effort, external_verification_effort, overall_effort = _efforts(source_id, status, domain)
        assessment = {
            "gap_id": f"GAP-{index:03d}",
            "source_policy_id": source_id,
            "source_kind": source_kind,
            "title": requirement["title"],
            "domain": domain,
            "requirement_id": requirement["requirement_id"],
            "design_ids": design_ids,
            "affected_artifact_type_ids": requirement["domain_artifact_type_ids"],
            "planned_test_ids": requirement["planned_test_ids"],
            "formal_test_status": "NOT_RUN",
            "status": status,
            "priority": _priority(source_id),
            "policy_in_plain_language": _plain_text(policy_summary),
            "failure_behavior_in_plain_language": [_plain_text(item) for item in failure_behavior],
            "current_implementation_in_plain_language": _plain_text(current_behavior),
            "rationale": _plain_text(_status_rationale(status, requirement["implementation_observation"])),
            "evidence_ids": linked_evidence,
            "user_impact": _plain_text(USER_IMPACT_BY_SOURCE.get(source_id, USER_IMPACT_BY_DOMAIN[domain])),
            "remediation": _plain_text(REMEDIATION_BY_SOURCE.get(source_id, REMEDIATION_BY_DOMAIN[domain])),
            "acceptance_criteria": [_plain_text(item) for item in acceptance],
            "owner_role": _owner(domain),
            "implementation_effort": implementation_effort,
            "external_verification_effort": external_verification_effort,
            "relative_effort": overall_effort,
            "blocking_gate_ids": sorted(set(blocking_gate_ids)),
            "confidence": "HIGH" if status in {"CONFLICTING", "MISSING", "BLOCKED"} else "MEDIUM",
            "waived": False,
        }
        assessment["assessment_sha256"] = _object_sha256(assessment)
        assessments.append(assessment)
    return assessments


def build_backlog(assessments: list[dict[str, Any]], report_hash: str) -> dict[str, Any]:
    epic_defs = [
        ("EPIC-01", "제품 경계와 Web/PWA 오염 제거", {"제품·거버넌스", "앱·지원환경"}, [], "P1"),
        ("EPIC-02", "안전한 보행 상태와 권한", {"가입·동의·보행상태", "가입·동의·권한", "사용자·안전범위"}, ["EPIC-01"], "P0"),
        ("EPIC-03", "계정·관리자 앱·보안", {"인증·관리자", "보안·개인정보"}, ["EPIC-01"], "P0"),
        ("EPIC-04", "경로·도착·이탈 사용자 결정 흐름", {"경로·길안내"}, ["EPIC-02"], "P0"),
        ("EPIC-05", "객체탐지·위험안내 안전성", {"객체탐지·위험안내"}, ["EPIC-02"], "P0"),
        ("EPIC-06", "음성·진동·접근성", {"음성·접근성"}, ["EPIC-02"], "P0"),
        ("EPIC-07", "원본 수집·보존·삭제", {"자료·개인정보"}, ["EPIC-02", "EPIC-03"], "P0"),
        ("EPIC-08", "자동신고 암호화 queue와 전송", {"신고·자료전송", "신고·전송"}, ["EPIC-02", "EPIC-03", "EPIC-07"], "P0"),
        ("EPIC-09", "서버 저장·용량·장애대응", {"서버·운영", "서버·장애대응"}, ["EPIC-03", "EPIC-07", "EPIC-08"], "P0"),
        ("EPIC-10", "AI 모델 수명주기", {"AI·모델"}, ["EPIC-05", "EPIC-07"], "P0"),
        ("EPIC-11", "Android 릴리스·운영·복구 구현 준비", {"릴리스·운영"}, ["EPIC-09", "EPIC-10"], "P1"),
        ("EPIC-12", "정식 시험·현장 검증·5개 게이트", {"시험·현장검증", "출시 게이트"}, ["EPIC-04", "EPIC-05", "EPIC-06", "EPIC-08", "EPIC-09", "EPIC-10", "EPIC-11"], "P0"),
    ]
    epics = []
    covered: set[str] = set()
    assessment_by_source = {row["source_policy_id"]: row for row in assessments}
    priority_rank = {"P0": 0, "P1": 1, "P2": 2, "P3": 3}
    status_action_rank = {"CONFLICTING": 0, "MISSING": 1, "PARTIAL": 2, "EVIDENCE_MISSING": 3, "BLOCKED": 4, "IMPLEMENTED": 5}
    wave_by_id: dict[str, int] = {}
    for epic_id, title, domains, deps, _suggested_priority in epic_defs:
        sources = [row["source_policy_id"] for row in assessments if row["domain"] in domains]
        covered.update(sources)
        efforts = Counter(assessment_by_source[source]["relative_effort"] for source in sources)
        priority = min(
            (assessment_by_source[source]["priority"] for source in sources),
            key=priority_rank.__getitem__,
        )
        owner_roles = sorted({assessment_by_source[source]["owner_role"] for source in sources})
        _require(set(deps) <= set(wave_by_id), f"epic dependencies are not topologically ordered: {epic_id}")
        wave = 0 if not deps else 1 + max(wave_by_id[dependency] for dependency in deps)
        wave_by_id[epic_id] = wave
        deferred_gate_ids = sorted({
            gate_id
            for source in sources
            for gate_id in assessment_by_source[source]["blocking_gate_ids"]
        })
        verification_epic = epic_id == "EPIC-12"
        ordered_sources = sorted(
            sources,
            key=lambda source: (
                priority_rank[assessment_by_source[source]["priority"]],
                status_action_rank[assessment_by_source[source]["status"]],
                source,
            ),
        )
        implementation_done_when = (
            "해당 없음. 앞 작업 묶음에서 구현 준비가 끝난 뒤 정식 검증만 수행한다."
            if verification_epic else
            "코드·인터페이스·단위/구성요소 인수조건을 구현하고 검토한다. 아직 실행하지 않은 현장·독립검토·출시 게이트는 EPIC-12에 넘기며, 그것 때문에 이 구현 준비 상태를 서로 기다리게 하지 않는다."
        )
        release_verification_done_when = (
            "연결된 279개 정식 시험 중 해당 범위를 승인 환경에서 실행하고, 5개 게이트를 모두 닫으며, P0/P1 결함이 열려 있지 않다."
            if verification_epic else
            "EPIC-12에서 연결된 정식 시험과 출시 게이트를 별도로 완료해야 한다."
        )
        epics.append({
            "epic_id": epic_id,
            "title": _plain_text(title),
            "priority": priority,
            "wave": wave,
            "source_policy_ids": sources,
            "ordered_source_policy_ids": ordered_sources,
            "gap_ids": [assessment_by_source[source]["gap_id"] for source in sources],
            "dependencies": deps,
            "owner_roles": owner_roles,
            "effort_mix": dict(sorted(efforts.items())),
            "current_status": "PLANNED",
            "current_status_reason": "아직 이 작업 묶음의 구현·정식 검증 완료 증거를 만들지 않았다.",
            "target_completion_level": "VERIFICATION_COMPLETE" if verification_epic else "IMPLEMENTATION_READY",
            "implementation_done_when": implementation_done_when,
            "release_verification_done_when": release_verification_done_when,
            "deferred_release_gate_ids": deferred_gate_ids,
            "done_when": release_verification_done_when if verification_epic else implementation_done_when,
        })
    _require(covered == set(assessment_by_source), "backlog does not cover all 68 assessments")
    epics.sort(key=lambda row: row["wave"])
    ranks_by_wave: Counter[int] = Counter()
    for epic in epics:
        ranks_by_wave[epic["wave"]] += 1
        epic["rank_within_wave"] = ranks_by_wave[epic["wave"]]
    next_action_sequence = []
    for epic in epics:
        for source in epic["ordered_source_policy_ids"]:
            row = assessment_by_source[source]
            next_action_sequence.append({
                "order": len(next_action_sequence) + 1,
                "epic_id": epic["epic_id"],
                "wave": epic["wave"],
                "source_policy_id": source,
                "priority": row["priority"],
                "status": row["status"],
                "action": row["remediation"],
            })
    backlog = {
        "schema_version": "walksafe.implementation-remediation-backlog.v1",
        "metadata": {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260722-001",
            "version": "0.1.0",
            "status": "DRAFT_DIAGNOSTIC",
            "prepared_at": PREPARED_AT,
        },
        "gap_report_content_sha256": report_hash,
        "estimation_rule": "구현 공수와 외부 검증 공수를 분리한다. S는 한 구성요소의 좁은 수정, M은 여러 구성요소 또는 계약 변경, L은 앱·서버·자료처럼 여러 시스템을 함께 바꾸거나 실기기·현장·독립검토가 필요한 범위다. 일정·비용 약속은 아니다.",
        "target_completion_model": {
            "IMPLEMENTATION_READY": "EPIC-01~11이 앞으로 달성해야 할 목표다. 구현과 내부 검토를 마치되 정식 시험·현장검증·출시 게이트는 EPIC-12에서 확인한다.",
            "VERIFICATION_COMPLETE": "EPIC-12가 앞으로 달성해야 할 목표다. 고정된 구현 후보로 정식 시험과 5개 출시 게이트를 모두 완료한다.",
            "cycle_prevention": "구현 작업은 미실행 출시 게이트를 EPIC-12로 넘길 수 있으므로, 구현과 검증이 서로의 완료를 기다리지 않는다.",
        },
        "current_status_model": {
            "PLANNED": "아직 착수 또는 완료를 주장할 실행 증거가 없는 계획 상태다.",
        },
        "priority_rule": {
            "P0": "안전·개인정보·인증·출시 차단 수준이다. P0끼리의 실제 순서는 wave와 next_action_sequence를 따른다.",
            "P1": "정식 제품·운영에 필요. P0 설계와 병행하거나 직후 해결한다.",
            "P2": "출시 전 품질·운영 완성도를 높이는 항목이다.",
            "P3": "출시 차단은 아니며 기준선 충족 뒤 정리·최적화한다.",
        },
        "execution_order": [row["epic_id"] for row in epics],
        "next_action_sequence": next_action_sequence,
        "epics": epics,
        "authorization_boundary": {
            "implementation_change_authorized": False,
            "baseline_change_authorized": False,
            "release_status": "NOT_ELIGIBLE",
            "remaining_gates_waived": False,
        },
    }
    backlog["backlog_content_sha256"] = _object_sha256(backlog)
    return backlog


def build_report() -> tuple[dict[str, Any], dict[str, Any]]:
    policy = load_strict_json(POLICY_SOURCE)
    manifest = load_strict_json(POLICY_MANIFEST)
    rtm = load_strict_json(RTM_PATH)
    tests = load_strict_json(TEST_CASES_PATH)
    receipt = load_strict_json(APPROVAL_RECEIPT_PATH)
    snapshot, implementation_paths = implementation_snapshot()
    gates = manifest["remaining_gates"]
    _require({row["id"] for row in gates} == EXPECTED_GATE_IDS, "remaining gate set changed")
    _require({row["status"] for row in gates} == {"NOT_RUN"}, "a remaining gate no longer says NOT_RUN")
    _require(receipt["metadata"]["transaction_status"] == "COMMITTED", "artifact approval receipt is not committed")
    _require(tests["summary"]["test_case_count"] == 279, "planned test count changed")
    _require(tests["summary"]["not_run_count"] == 279, "formal test execution status changed")
    evidence = build_evidence(implementation_paths)
    assessments = build_assessments(policy, rtm, gates, evidence)
    status_counts = Counter(row["status"] for row in assessments)
    priority_counts = Counter(row["priority"] for row in assessments)
    source_bindings = [
        _source_binding("policy_baseline_manifest_1_0_1", POLICY_MANIFEST),
        _source_binding("approved_policy_content", POLICY_SOURCE),
        _source_binding("requirements_traceability", RTM_PATH),
        _source_binding("design_traceability", DESIGN_TRACE_PATH),
        _source_binding("module_register", MODULE_REGISTER_PATH),
        _source_binding("planned_test_cases", TEST_CASES_PATH),
        _source_binding("artifact_register", ARTIFACT_REGISTER_PATH),
        _source_binding("artifact_approval_receipt", APPROVAL_RECEIPT_PATH),
        _source_binding("runtime_candidate_context_non_normative", RUNTIME_CANDIDATE_PATH),
        _source_binding("generator", GENERATOR_PATH),
    ]
    report = {
        "schema_version": "walksafe.implementation-gap-analysis.v1",
        "metadata": {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260722-001",
            "version": "0.1.0",
            "status": "DRAFT_DIAGNOSTIC_COMPLETE",
            "prepared_at": PREPARED_AT,
            "baseline_id": manifest["metadata"]["baseline_id"],
            "baseline_version": manifest["metadata"]["baseline_version"],
        },
        "purpose": "승인된 기능정책 1.0.1과 현재 동결 구현을 비교해 구현·충돌·증거 Gap과 수정 순서를 제시한다.",
        "decision_precedence": [
            "정책 기준선 1.0.1과 COMMITTED 산출물 승인 영수증",
            "승인된 요구·설계 추적",
            "현재 commit의 구현 관찰",
            "과거 README·후보 분석은 사실 확인용이며 정책을 바꾸지 못함",
        ],
        "source_bindings": source_bindings,
        "source_binding_sha256": _object_sha256(source_bindings),
        "implementation_snapshot": snapshot,
        "assessment_method": {
            "IMPLEMENTED": "승인 규칙 전체를 구현했고 연결된 정식 검증 증거가 PASS인 경우",
            "PARTIAL": "규칙 일부 코드가 있으나 흐름·예외·증거가 불완전한 경우",
            "MISSING": "규칙을 책임질 핵심 구현 경로를 찾지 못한 경우",
            "CONFLICTING": "현재 구현·운영 설명이 승인 정책과 반대 동작을 하는 경우",
            "EVIDENCE_MISSING": "후보 구현은 있으나 승인된 검증 증거가 없는 경우",
            "BLOCKED": "외부 측정·검토·훈련 게이트가 NOT_RUN인 경우",
        },
        "coverage": {
            "common_policy_count": 9, "feature_policy_count": 54, "remaining_gate_count": 5,
            "assessment_count": len(assessments), "requirement_count": len(rtm["requirements"]),
            "planned_test_count": tests["summary"]["test_case_count"], "planned_test_not_run_count": tests["summary"]["not_run_count"],
        },
        "summary": {
            "status_counts": dict(sorted(status_counts.items())),
            "priority_counts": dict(sorted(priority_counts.items())),
            "implemented_and_formally_verified_count": status_counts.get("IMPLEMENTED", 0),
            "release_status": "NOT_ELIGIBLE",
            "headline": _plain_text("현재 구현은 일부 핵심 코드가 있으나 승인 정책과 직접 충돌하는 보행·신고 흐름, 빠진 계정·관리자·원본·queue 기능, 미실행 정식 검증 때문에 출시 적격이 아니다."),
        },
        "critical_findings": [
            {"id": "CF-01", "title": "공식 문서의 정식 제품 경계가 승인안과 반대", "source_ids": ["FP-007", "FP-009"], "evidence_ids": ["EVD-PRODUCT-ROOT", "EVD-PRODUCT-WEB"]},
            {"id": "CF-02", "title": "별도 Android 관리자 앱·추가 본인확인 없음", "source_ids": ["FP-008", "FP-047", "NPC-SINGLE-ADMIN-RECOVERY"], "evidence_ids": ["EVD-ANDROID-SINGLE-MODULE", "EVD-NEG-ANDROID-ADMIN-AUTH"]},
            {"id": "CF-03", "title": "가입·통합동의·안전한 장기 세션 없음", "source_ids": ["FP-010", "FP-011", "FP-013"], "evidence_ids": ["EVD-ANDROID-ACCOUNT", "EVD-REPORT-CONSENT"]},
            {"id": "CF-04", "title": "복귀 즉시 보행 기능 재개", "source_ids": ["FP-017", "FP-018"], "evidence_ids": ["EVD-ANDROID-RESUME"]},
            {"id": "CF-05", "title": "도착 자동 확정·이탈 자동 재탐색", "source_ids": ["FP-022", "FP-023", "NPC-NAVIGATION-ROUTE-DIRECTION"], "evidence_ids": ["EVD-NAV-DECISION", "EVD-NAV-AUTO-REROUTE"]},
            {"id": "CF-06", "title": "자동신고 후보를 보행 중 즉시 인터넷 전송", "source_ids": ["FP-031", "FP-035", "NPC-AUTO-REPORT"], "evidence_ids": ["EVD-AUTO-UPLOAD", "EVD-NEG-ANDROID-QUEUE"]},
            {"id": "CF-07", "title": "승인된 원본 수집·보존·삭제 경로 없음", "source_ids": ["FP-034", "FP-036", "NPC-RAW-ORIGINAL-COLLECTION"], "evidence_ids": ["EVD-RAW-RELEASE-SELECTION", "EVD-RAW-RELEASE"]},
            {"id": "CF-08", "title": "보호 서버 후보는 있으나 계정 인증·중앙 역할별 권한·용량 계약이 미완성", "source_ids": ["FP-040", "FP-041", "FP-047", "NPC-SERVER-CAPACITY-STATE-SYNC"], "evidence_ids": ["EVD-GATEWAY-ENDPOINT", "EVD-BACKEND-AUTH", "EVD-NEG-BACKEND-CAPACITY"]},
            {"id": "CF-09", "title": "정식 시험 279개 전부 미실행", "source_ids": ["FP-049", "FP-050"], "evidence_ids": []},
            {"id": "CF-10", "title": "5개 출시 확인 관문 전부 미실행", "source_ids": sorted(EXPECTED_GATE_IDS), "evidence_ids": []},
        ],
        "evidence_catalog": evidence,
        "assessments": assessments,
        "ad_hoc_validation": {
            "formal_evidence": False,
            "results": [
                {"scope": "Android JVM unit tests", "command": "./gradlew testDebugUnitTest --no-daemon --rerun-tasks", "tests": 326, "failures": 0},
                {"scope": "targeted backend/model unit tests", "command": "pytest selected security/health/OpenAPI/report/inference/model tests", "tests": 171, "failures": 0},
            ],
            "interpretation": "코드 단위의 기본 건강성만 확인한다. 승인된 기기·현장·E2E·접근성·게이트 증거를 대신하지 않는다.",
        },
        "known_baseline_consistency_risk": {
            "status": "OPEN_NON_MUTATING_OBSERVATION",
            "description": "정책 1.0.1은 이동통신망 opt-in 분기를 승인했지만 일부 승인 묶음 문구는 Wi-Fi 대기만 말하거나 FP-035 정정안을 NOT_EFFECTIVE로 표시한다. 본 진단은 정책 1.0.1을 우선 적용했고 승인 파일은 수정하지 않았다.",
            "evidence_ids": ["EVD-FP035-REQ-CONFLICT", "EVD-FP035-DES-STALE", "EVD-FP035-RELEASE-DRIFT"],
        },
        "authorization_boundary": {
            "diagnosis_only": True,
            "implementation_modified": False,
            "approved_baseline_modified": False,
            "formal_test_completion_claimed": False,
            "artifact_approval_claimed": False,
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "limitations": [
            "정적 코드·설정·문서 관찰은 실기기 동작을 완전히 증명하지 못한다.",
            "부재 판정은 동결된 424개 tracked 구현 파일과 명시된 검색식 범위에 한정된다.",
            "공수는 상대 크기이며 일정·비용 약속이 아니다.",
        ],
    }
    report["report_content_sha256"] = _object_sha256(report)
    backlog = build_backlog(assessments, report["report_content_sha256"])
    return report, backlog


def validate_report(report: dict[str, Any], backlog: dict[str, Any]) -> None:
    report_payload = dict(report)
    report_hash = report_payload.pop("report_content_sha256", None)
    _require(report_hash == _object_sha256(report_payload), "report content hash is invalid")
    backlog_payload = dict(backlog)
    backlog_hash = backlog_payload.pop("backlog_content_sha256", None)
    _require(backlog_hash == _object_sha256(backlog_payload), "backlog content hash is invalid")
    snapshot_payload = dict(report["implementation_snapshot"])
    snapshot_hash = snapshot_payload.pop("snapshot_sha256", None)
    _require(snapshot_hash == _object_sha256(snapshot_payload), "implementation snapshot hash is invalid")
    _require(report["source_binding_sha256"] == _object_sha256(report["source_bindings"]), "source binding hash is invalid")
    assessments = report["assessments"]
    _require(len(assessments) == 68, "report must contain 68 assessments")
    _require(len({row["source_policy_id"] for row in assessments}) == 68, "source IDs are duplicated")
    _require(len({row["requirement_id"] for row in assessments}) == 68, "requirement IDs are duplicated")
    _require({row["status"] for row in assessments} <= ALLOWED_STATUSES, "unknown assessment status")
    _require(all(row["formal_test_status"] == "NOT_RUN" for row in assessments), "formal test status was overstated")
    evidence_by_id = {row["evidence_id"]: row for row in report["evidence_catalog"]}
    _require(len(evidence_by_id) == len(report["evidence_catalog"]), "evidence IDs are duplicated")
    for item in evidence_by_id.values():
        if item["kind"] == "NEGATIVE_SEARCH":
            _require(item["match_count"] == 0 and not item["matches"], f"negative search found a match: {item['evidence_id']}")
    for row in assessments:
        assessment_payload = dict(row)
        assessment_hash = assessment_payload.pop("assessment_sha256", None)
        _require(assessment_hash == _object_sha256(assessment_payload), f"assessment hash is invalid: {row['source_policy_id']}")
        if row["status"] in {"MISSING", "CONFLICTING"}:
            linked = [evidence_by_id[item] for item in row["evidence_ids"]]
            _require(any(item["kind"] in {"IMPLEMENTATION_FILE", "NEGATIVE_SEARCH"} for item in linked), f"{row['source_policy_id']} lacks direct evidence")
        _require(row["affected_artifact_type_ids"], f"affected artifact types are empty: {row['source_policy_id']}")
        if row["source_kind"] == "FEATURE_POLICY":
            _require(row["failure_behavior_in_plain_language"], f"failure behavior is empty: {row['source_policy_id']}")
        _require(set(row["blocking_gate_ids"]) <= EXPECTED_GATE_IDS, f"unknown blocking gate: {row['source_policy_id']}")
    gate_rows = [row for row in assessments if row["source_policy_id"].startswith("GATE-")]
    _require({row["source_policy_id"] for row in gate_rows} == EXPECTED_GATE_IDS, "gate coverage differs")
    _require({row["status"] for row in gate_rows} == {"BLOCKED"}, "gate status must remain BLOCKED")
    _require({row["source_policy_id"] for row in assessments if row["status"] == "BLOCKED"} == EXPECTED_GATE_IDS, "BLOCKED is reserved for the five remaining gates")
    _require(report["coverage"]["planned_test_not_run_count"] == 279, "formal tests must remain NOT_RUN")
    formal_test_ids = {row["test_case_id"] for row in load_strict_json(TEST_CASES_PATH)["test_cases"]}
    linked_test_list = [test_id for row in assessments for test_id in row["planned_test_ids"]]
    linked_test_ids = set(linked_test_list)
    _require(
        linked_test_ids == formal_test_ids and len(linked_test_list) == len(linked_test_ids) == 279,
        "planned test trace must cover the exact formal registry once each",
    )
    _require(report["authorization_boundary"]["release_status"] == "NOT_ELIGIBLE", "release boundary changed")
    _require(not report["authorization_boundary"]["approved_baseline_modified"], "report claims baseline mutation")
    _require(backlog["gap_report_content_sha256"] == report["report_content_sha256"], "backlog is not bound to report")
    covered = {source for epic in backlog["epics"] for source in epic["source_policy_ids"]}
    _require(covered == {row["source_policy_id"] for row in assessments}, "backlog coverage is incomplete")
    _require(
        [row["order"] for row in backlog["next_action_sequence"]] == list(range(1, 69)),
        "next action order must be a complete 1..68 sequence",
    )
    _require(
        {row["source_policy_id"] for row in backlog["next_action_sequence"]} == covered,
        "next action sequence does not cover all assessments",
    )
    _require({row["current_status"] for row in backlog["epics"]} == {"PLANNED"}, "all remediation epics must remain PLANNED")
    verification_epics = [row for row in backlog["epics"] if row["target_completion_level"] == "VERIFICATION_COMPLETE"]
    _require([row["epic_id"] for row in verification_epics] == ["EPIC-12"], "formal verification must be isolated in EPIC-12")


def _markdown(report: dict[str, Any], backlog: dict[str, Any]) -> str:
    counts = report["summary"]["status_counts"]
    lines = [
        "# WalkSafe 승인 정책 대비 현행 구현 Gap 분석", "",
        f"- 보고서: `{report['metadata']['report_id']}` v{report['metadata']['version']}",
        f"- 정책 기준선: `{report['metadata']['baseline_id']}`", f"- 구현 commit: `{report['implementation_snapshot']['commit']}`",
        "- 결론: **출시 부적격(NOT_ELIGIBLE)** — 기준선이나 구현을 변경하지 않은 진단 보고서", "",
        "## 한눈에 보는 결과", "",
        f"68개 기준을 모두 비교했습니다. 구현·정식검증까지 완료된 항목은 {counts.get('IMPLEMENTED', 0)}개입니다.", "",
        "| 판정 | 개수 | 뜻 |", "|---|---:|---|",
    ]
    for status in ("IMPLEMENTED", "PARTIAL", "MISSING", "CONFLICTING", "EVIDENCE_MISSING", "BLOCKED"):
        lines.append(f"| {STATUS_LABELS[status]} (`{status}`) | {counts.get(status, 0)} | {STATUS_LABELS[status]} |")
    lines.extend(["", "## 먼저 바로잡아야 할 핵심", ""])
    for finding in report["critical_findings"]:
        lines.append(f"- **{finding['id']} {finding['title']}** — {', '.join(finding['source_ids'])}")
    lines.extend([
        "", "## 기능·정책별 판정", "",
        "| ID | 기능·정책 | 판정 | 우선 | 담당 | 구현 공수 | 외부 검증 공수 | 전체 |", "|---|---|---|---|---|---|---|---|",
    ])
    for row in report["assessments"]:
        lines.append(f"| {row['source_policy_id']} | {row['title']} | {STATUS_LABELS[row['status']]} (`{row['status']}`) | {row['priority']} | {row['owner_role']} | {row['implementation_effort']} | {row['external_verification_effort']} | {row['relative_effort']} |")
    lines.extend(["", "## 수정 작업 묶음과 순서", ""])
    for epic in backlog["epics"]:
        lines.append(f"{epic['epic_id']}. **{_plain_text(epic['title'])}** ({epic['priority']}, 실행 단계 {epic['wave']}, 현재 미착수 `{epic['current_status']}`, {TARGET_COMPLETION_LEVEL_LABELS[epic['target_completion_level']]}) — 선행: {', '.join(epic['dependencies']) or '없음'}; 순서: {', '.join(epic['ordered_source_policy_ids'])}")
    lines.extend([
        "", "## 검증 경계", "",
        "- Android 내부 단위시험 326건과 선택한 서버·모델 단위시험 171건은 통과했지만 정식 승인 시험이 아닙니다.",
        "- 승인된 279개 시험과 5개 출시 확인 관문은 모두 미실행(`NOT_RUN`)입니다.",
        "- 이 보고서는 정책·산출물 기준선, 구현 코드, 출시 상태를 변경하지 않았습니다.", "",
        f"보고서 내용 지문: `{report['report_content_sha256']}`", f"백로그 내용 지문: `{backlog['backlog_content_sha256']}`", "",
    ])
    return "\n".join(lines)


def _html(report: dict[str, Any], backlog: dict[str, Any]) -> str:
    esc = html.escape
    counts = report["summary"]["status_counts"]
    cards = "".join(
        f'<div class="metric"><strong>{counts.get(status, 0)}</strong><span>{esc(STATUS_LABELS[status])}<small>{esc(status)}</small></span></div>'
        for status in ("CONFLICTING", "MISSING", "PARTIAL", "EVIDENCE_MISSING", "BLOCKED", "IMPLEMENTED")
    )
    rows = []
    for item in report["assessments"]:
        evidence = [next(row for row in report["evidence_catalog"] if row["evidence_id"] == ev) for ev in item["evidence_ids"]]
        evidence_html = "".join(
            f"<li><code>{esc(ev['evidence_id'])}</code> — {esc(ev['claim'])}"
            + (f" <span class=path>{esc(ev.get('path',''))}:{ev.get('line_start','')}-{ev.get('line_end','')}</span>" if ev.get("path") else "") + "</li>"
            for ev in evidence
        ) or "<li>정식 시험/게이트 실행 기록이 아직 없습니다.</li>"
        tests = ", ".join(item["planned_test_ids"])
        failure_html = "".join(f"<li>{esc(value)}</li>" for value in item["failure_behavior_in_plain_language"]) or "<li>기능별 항목이 아닌 공통정책 또는 출시 확인 항목입니다.</li>"
        gates = ", ".join(item["blocking_gate_ids"]) or "없음"
        artifacts = ", ".join(item["affected_artifact_type_ids"])
        searchable = " ".join([
            item["source_policy_id"], item["title"], item["domain"], item["policy_in_plain_language"],
            *item["failure_behavior_in_plain_language"], item["current_implementation_in_plain_language"],
            item["rationale"], item["user_impact"], item["remediation"], artifacts, gates,
        ]).lower()
        rows.append(f'''<article class="gap" data-status="{esc(item['status'])}" data-priority="{esc(item['priority'])}" data-domain="{esc(item['domain'])}" data-search="{esc(searchable)}">
  <header><div><span class="pill {esc(item['priority'])}">{esc(item['priority'])}</span> <span class="pill {esc(item['status'])}">{esc(STATUS_LABELS[item['status']])} ({esc(item['status'])})</span></div><h3>{esc(item['source_policy_id'])} · {esc(item['title'])}</h3><p>{esc(item['policy_in_plain_language'])}</p></header>
  <details><summary>왜 이렇게 판단했는지와 고칠 방법 보기</summary>
    <dl><dt>장애가 나면 적용할 규칙</dt><dd><ul>{failure_html}</ul></dd><dt>현재 구현은 어떤가</dt><dd>{esc(item['current_implementation_in_plain_language'])}</dd><dt>현재 판단</dt><dd>{esc(item['rationale'])}</dd><dt>사용자 영향</dt><dd>{esc(item['user_impact'])}</dd><dt>구현 근거</dt><dd><ul>{evidence_html}</ul></dd><dt>고칠 내용</dt><dd>{esc(item['remediation'])}</dd><dt>완료 확인</dt><dd>{esc(' '.join(item['acceptance_criteria']))}</dd><dt>연결</dt><dd>요구 {esc(item['requirement_id'])}; 설계 {esc(', '.join(item['design_ids']) or '없음')}; 시험 {esc(tests)}; 함께 고칠 산출물 {esc(artifacts)}</dd><dt>출시 전 남은 확인</dt><dd>{esc(gates)}</dd><dt>담당·구현 공수·외부 검증 공수</dt><dd>{esc(item['owner_role'])} · 구현 {esc(item['implementation_effort'])} · 외부 검증 {esc(item['external_verification_effort'])} · 전체 {esc(item['relative_effort'])}</dd></dl>
  </details></article>''')
    epics = "".join(
        f"<tr><td>{esc(e['epic_id'])}</td><td>{esc(_plain_text(e['title']))}</td><td>{e['wave']}-{e['rank_within_wave']}</td><td>미착수<small>({esc(e['current_status'])})</small></td><td>{esc(TARGET_COMPLETION_LEVEL_LABELS[e['target_completion_level']])}<small>({esc(e['target_completion_level'])})</small></td><td>{esc(', '.join(e['dependencies']) or '없음')}</td><td>{esc(', '.join(e['ordered_source_policy_ids']))}</td><td>{esc(_plain_text(e['done_when']))}</td></tr>"
        for e in backlog["epics"]
    )
    findings = "".join(f"<li><strong>{esc(f['id'])} · {esc(f['title'])}</strong><br><span>{esc(', '.join(f['source_ids']))}</span></li>" for f in report["critical_findings"])
    return f'''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="icon" href="data:,"><title>WalkSafe 구현 Gap 종합 보고서</title><style>
:root{{--bg:#f4f7fb;--paper:#fff;--ink:#18212f;--muted:#5d6b7d;--line:#d9e1ec;--navy:#17365d;--danger:#a82332;--warn:#a85e00;--ok:#19734b}}*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--ink);font-family:system-ui,-apple-system,"Noto Sans KR",sans-serif;line-height:1.62}}main{{max-width:1180px;margin:auto;padding:28px 18px 80px}}h1{{font-size:clamp(1.8rem,5vw,3rem);line-height:1.15;margin:.3em 0}}h2{{margin-top:2.4em}}.eyebrow{{color:var(--navy);font-weight:800;letter-spacing:.08em}}.warning{{background:#fff1f1;border:2px solid #d14654;border-radius:14px;padding:16px;margin:22px 0;font-weight:700}}.metrics{{display:grid;grid-template-columns:repeat(6,1fr);gap:10px}}.metric{{background:var(--paper);border:1px solid var(--line);border-radius:12px;padding:14px;display:flex;flex-direction:column}}.metric strong{{font-size:1.8rem}}.metric span{{font-size:.75rem;color:var(--muted);overflow-wrap:anywhere}}.metric small{{display:block}}.panel,.gap{{background:var(--paper);border:1px solid var(--line);border-radius:14px;padding:18px;margin:12px 0;box-shadow:0 3px 16px #2030400d}}.filters{{position:sticky;top:0;z-index:4;background:#f4f7fbf2;padding:12px 0;display:grid;grid-template-columns:2fr 1fr 1fr 1fr;gap:8px}}input,select{{font:inherit;padding:12px;border:1px solid #aeb9c8;border-radius:9px;background:white;min-width:0}}.gap h3{{margin:.5rem 0 .25rem}}.gap p{{margin:.2rem 0;color:#344154}}.pill{{font-size:.72rem;font-weight:850;border-radius:999px;padding:4px 9px;background:#e8edf4}}.P0,.CONFLICTING,.BLOCKED{{background:#ffe1e4;color:#871525}}.P1,.MISSING{{background:#ffedd4;color:#754000}}.P2,.PARTIAL{{background:#fff5c2;color:#684f00}}.EVIDENCE_MISSING{{background:#e4edff;color:#244b8a}}.IMPLEMENTED{{background:#dff5e9;color:#17673f}}summary{{cursor:pointer;font-weight:750;margin-top:10px}}dt{{font-weight:800;color:var(--navy);margin-top:10px}}dd{{margin-left:0}}.path{{display:block;color:var(--muted);font-size:.84rem;overflow-wrap:anywhere}}table{{width:100%;border-collapse:collapse;background:white}}th,td{{border:1px solid var(--line);padding:10px;text-align:left;vertical-align:top}}.table-wrap{{overflow-x:auto}}code{{overflow-wrap:anywhere}}.hidden{{display:none!important}}.count{{font-weight:750;color:var(--navy)}}footer{{margin-top:48px;color:var(--muted);font-size:.9rem}}@media(max-width:760px){{.metrics{{grid-template-columns:repeat(2,1fr)}}.filters{{grid-template-columns:1fr 1fr}}.filters input{{grid-column:1/-1}}main{{padding:18px 12px 60px}}.gap{{padding:14px}}}}
</style></head><body><main><p class="eyebrow">승인 기준선 1.0.1 × 확인한 구현 지문 {esc(report['implementation_snapshot']['commit'][:12])}</p><h1>WalkSafe 구현 Gap 종합 보고서</h1><p>{esc(_plain_text(report['summary']['headline']))}</p><div class="warning">출시 상태는 부적격(NOT_ELIGIBLE)입니다. 이 문서는 진단 결과이며 기준선 승인, 구현 수정, 시험 완료 또는 5개 출시 확인 관문의 면제를 뜻하지 않습니다.</div><section class="metrics">{cards}</section>
<section><h2>가장 먼저 봐야 할 10가지</h2><div class="panel"><ol>{findings}</ol></div></section>
<section><h2>68개 기능·공통정책·출시 확인 항목 전체 판정</h2><p>쉬운 말로 정책, 장애 때 규칙, 현재 차이, 사용자 영향, 고칠 내용, 완료 확인 방법을 한 항목 안에 묶었습니다. <span id="visibleCount" class="count">68개 표시</span></p><div class="filters"><input id="search" type="search" aria-label="기능·정책 검색" placeholder="예: 자동신고, FP-035, 접근성"><select id="status" aria-label="판정 필터"><option value="">모든 판정</option>{''.join(f'<option value="{s}">{esc(STATUS_LABELS[s])} ({s})</option>' for s in sorted(ALLOWED_STATUSES))}</select><select id="priority" aria-label="우선순위 필터"><option value="">모든 우선순위</option>{''.join(f'<option>{p}</option>' for p in ('P0','P1','P2','P3'))}</select><select id="domain" aria-label="분야 필터"><option value="">모든 분야</option>{''.join(f'<option>{esc(d)}</option>' for d in sorted({r['domain'] for r in report['assessments']}))}</select></div><div id="gaps">{''.join(rows)}</div></section>
<section><h2>수정 작업 순서</h2><p>EPIC은 서로 연관된 수정 묶음이며 현재 모두 미착수(PLANNED)입니다. 표의 목표 완료 수준은 앞으로 달성할 기준이지 현재 완료 상태가 아닙니다. 구현 준비와 정식 검증을 분리해 서로 기다리는 순환을 없앴습니다. 구현/외부 검증의 S·M·L은 각각 한 구성요소, 여러 구성요소, 여러 시스템·현장검증 수준의 상대 공수이며 일정 약속이 아닙니다.</p><div class="table-wrap"><table><thead><tr><th>ID</th><th>작업 묶음</th><th>순서</th><th>현재 상태</th><th>목표 완료 수준</th><th>선행 작업</th><th>묶음 안 실행 순서</th><th>완료 조건</th></tr></thead><tbody>{epics}</tbody></table></div></section>
<section><h2>판정 읽는 법</h2><div class="panel"><dl><dt>정책과 충돌 (CONFLICTING)</dt><dd>현재 동작이 승인 정책과 반대입니다.</dd><dt>핵심 구현 없음 (MISSING)</dt><dd>핵심 구현 경로를 찾지 못했습니다.</dd><dt>일부 구현 (PARTIAL)</dt><dd>코드 일부는 있지만 전체 흐름·예외·증거가 부족합니다.</dd><dt>정식 증거 없음 (EVIDENCE_MISSING)</dt><dd>후보 구현은 있으나 승인된 시험을 실행하지 않았습니다.</dd><dt>미실행 게이트 (BLOCKED)</dt><dd>측정·독립검토·훈련 확인 관문이 미실행입니다.</dd></dl></div></section>
<section><h2>검증과 한계</h2><div class="panel"><p>Android 내부 단위시험 326건과 선택한 서버·모델 단위시험 171건은 실패 없이 끝났습니다. 그러나 승인된 정식 시험 279개와 5개 출시 확인 관문은 모두 미실행(NOT_RUN)입니다.</p><p>부재 판정은 확인한 소스 묶음에 등록된 구현 파일 424개와 보고서에 공개한 검색식에 한정됩니다. 실기기·현장 동작은 정식 시험으로 확인해야 합니다.</p></div></section><footer>보고서 {esc(report['metadata']['report_id'])} · 내용 지문 <code>{esc(report['report_content_sha256'])}</code></footer></main><script>
const q=document.querySelector('#search'),s=document.querySelector('#status'),p=document.querySelector('#priority'),d=document.querySelector('#domain'),items=[...document.querySelectorAll('.gap')],count=document.querySelector('#visibleCount');function apply(){{const text=q.value.trim().toLowerCase();let n=0;for(const el of items){{const show=(!text||el.dataset.search.includes(text))&&(!s.value||el.dataset.status===s.value)&&(!p.value||el.dataset.priority===p.value)&&(!d.value||el.dataset.domain===d.value);el.classList.toggle('hidden',!show);if(show)n++;}}count.textContent=`${{n}}개 표시`;}}[q,s,p,d].forEach(el=>el.addEventListener('input',apply));
</script></body></html>'''


def _json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False, allow_nan=False) + "\n"


def _write_or_check(path: Path, content: str, check: bool) -> None:
    if check:
        _require(path.is_file(), f"generated output missing: {_relative(path)}")
        _require(path.read_text(encoding="utf-8") == content, f"generated output is stale: {_relative(path)}")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="validate that generated outputs are current")
    args = parser.parse_args(argv)
    try:
        report, backlog = build_report()
        validate_report(report, backlog)
        outputs = {
            REPORT_JSON: _json_text(report), BACKLOG_JSON: _json_text(backlog),
            REPORT_MD: _markdown(report, backlog), REPORT_HTML: _html(report, backlog),
        }
        for path, content in outputs.items():
            _write_or_check(path, content, args.check)
    except GapAnalysisError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    mode = "checked" if args.check else "built"
    counts = report["summary"]["status_counts"]
    print(f"{mode} 68 assessments; statuses={dict(sorted(counts.items()))}; release=NOT_ELIGIBLE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
