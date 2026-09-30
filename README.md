# Hanium DreamUp — WalkSafe

WalkSafe는 시각장애인의 도심 보행을 돕는 Android 프로젝트입니다. 카메라와 기기 내 AI로 가까운 위험을 찾고, TMAP 기반 경로와 음성·진동 안내를 제공하며, 손상된 점자블록 신고를 돕습니다. 흰지팡이·안내견·보호자를 대체하거나 보행 안전을 보장하는 제품으로 설명하지 않습니다.

## 현재 개발 상태

2026-09-30 기능 개선 원장 **rev119: DONE553 / PARTIAL282 / 미정합95 / 해당 없음12**입니다. 전체942개는 원문 분석872개와 정지·이동 추가 요구70개이며 확정 버그 수가 아닙니다. 최신 구현·시험 결과와 남은 검증 범위는 [인계 현황](docs/handoff/2026-09-30/status.md), 실행별 근거는 [daylog](daylog/2026-09-30.md)에 있습니다.

팀 공통 브랜치는 **`temp`**입니다. [두 사람 작업 인계](docs/handoff/2026-09-30/README.md)에서 다음처럼 나눕니다.

| 담당 | PARTIAL | 미정합 | 합계 | 범위 |
|---|---:|---:|---:|---|
| [A 사용자](docs/handoff/2026-09-30/part-a.md) | 127 | 62 | 189 | 보행·경로·센서·인지·출력·기기 준비 |
| [B 팀원](docs/handoff/2026-09-30/part-b.md) | 155 | 33 | 188 | 계정·음성 입력·신고·개인정보·관리자·서버·검증 도구 |

PARTIAL은 원문의 남은 구현·검증 조건을 닫아야 합니다. 미정합은 원문과 현행 코드·근거의 대조부터 수행합니다. 필수 실기기·현장·외부 증거가 없으면 완료로 올리지 않습니다. 최근 사용자 앱 전체 시험에는 기존21개, Gateway에는 기존음성7개 실패가 남아 있습니다. APK 생성과 JVM 연동 시험은 실기기 완료를 뜻하지 않습니다.

## 빠른 링크

| 찾는 내용 | 시작점 |
|---|---|
| 현재 상태·남은 문제·담당 | [2026-09-30 인계](docs/handoff/2026-09-30/README.md) |
| 항목별 원문·잔여·기존 근거 | [잔여377개 상세](docs/handoff/2026-09-30/remaining-items.json) |
| 전체·단계 실행 계획 | [21개 분야 기능 개선 계획](docs/planning/walksafe-feature-improvement-execution-20260926.md) |
| 개발환경·새 clone 준비 | [개발환경 가이드](docs/guides/development-environment-guide.md) |
| 사용자·관리자 앱 빌드·기기 시험 | [Android 앱별 테스트 가이드](docs/guides/android-apps-testing-guide.md) |
| 코드·요청 흐름 | [코드 가이드](docs/guides/code-guide.md) |
| 직접·통합 시험과 결과 해석 | [테스트 가이드](docs/guides/testing-guide.md) |
| 팀 브랜치·파일 소유·통합 | [팀 작업 흐름](docs/guides/team-workflow.md) |
| 전체 문서 | [가이드 포털](docs/guides/README.md) |
| 기여·보안 | [CONTRIBUTING](CONTRIBUTING.md) · [SECURITY](SECURITY.md) |

## 제품 구성

승인 정책은 [PB-WALKSAFE-FEATURE-POLICY-1.0.1](docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json)을 따릅니다.

| 구성 | 역할 |
|---|---|
| [`apps/android/app`](apps/android/app) | 일반 사용자용 Android 앱 |
| [`apps/android/adminapp`](apps/android/adminapp) | 지정 관리자용 별도 Android 앱 |
| [`apps/android-gateway`](apps/android-gateway) | 세션·보행·길찾기·신고·동의·개인정보 제어 |
| [`backend`](backend) | 계정·신고·관리자·공간 데이터·외부 API 서버 |
| [`model`](model) · [`data_sources`](data_sources) | 모델·데이터와 provenance |
| [`voice`](voice) | Gateway 뒤의 선택형 STT·TTS 지원 서비스, 기본 OFF |
| [`apps/web`](apps/web) | 과거 Web/PWA 참고 코드, `LEGACY_REFERENCE_ONLY` |

관리자 앱의 기관 전달은 앱 밖에서 수행한 수동 전달 사실의 기록입니다. 외부 기관 자동 전송이나 기관 응답을 앱이 검증한 결과로 설명하지 않습니다.

## 작업 시작

1. [AGENTS.md](AGENTS.md)의 **현재 작업 원칙 — 기능 구현 우선**을 읽습니다.
2. [자기 파트](docs/handoff/2026-09-30/README.md)에서 작은 ID 묶음을 고르고 원문·현행 코드·관련 시험을 확인합니다.
3. 개발환경을 준비합니다. 새 clone에는 Android SDK 경로·venv·Node 의존성·비밀값·한국어 Vosk 모델·생성 APK가 포함되지 않습니다. [모델 준비 절차](docs/guides/android-apps-testing-guide.md#모델-자산-준비)를 먼저 확인합니다.
4. 현행 반례 → 최소 수정 → 같은 조건의 직접 시험 → 필요한 독립 검토 → 통합 순서로 진행합니다.

과거 Goal graph나 checkpoint의 focus는 현재 기능 분담을 대신하지 않습니다. 과거 통제 문서·승인본·gate 원출력은 보존하며, 제품과 무관한 통제 복구를 기능 개선 시작 조건으로 확대하지 않습니다. 빌드·시험 명령은 [현행 테스트 가이드](docs/guides/testing-guide.md)를 따릅니다.

## 정식 검증·출시 경계

다음은 기존 [승인·통제 기록](docs/control/walksafe-project-continuation-checkpoint.json)의 상태입니다. 위 기능 개선 원장의 DONE 수와 별도로 관리합니다.

- Goal package: v2.4 `ACTIVE`
- 정식 시험: 279/279 `NOT_RUN`
- 출시 gate: 5/5 `NOT_RUN`, 면제 없음
- 출시 상태: `NOT_ELIGIBLE`

내부 시험·debug build·국소 기능 완료를 정식 안전시험, 운영 배포 또는 출시 승인으로 바꾸지 않습니다. [2026-08-30 실행 보고서](docs/testing/walksafe-real-environment-execution-report-20260830.md)는 해당 시점의 제한된 실행 기록입니다.

## 데이터·모델과 보존

현재 모델 선택·class·tensor·hash는 [runtime config](apps/android/app/src/main/assets/model-config/two_model_runtime.json), 빌드에 포함되는 asset은 실제 Gradle 계약을 따릅니다. 기존 추적 모델의 예외는 새 weight·dataset·사용자 자료 반입 허가가 아닙니다.

비밀값, 원본 데이터셋, 사용자 영상·음성·정확 위치, 운영 로그, 임시 build/cache, `work/`는 Git에 넣지 않습니다. [데이터·AI 가이드](docs/guides/data-ai-guide.md)와 [저장소 가이드](docs/guides/repository-guide.md)를 확인합니다. 현대화 이전 저장소의 복구 지점은 [보존 기록](docs/planning/repository-modernization-20260811/preservation-record.md)에 있으며, 기존 `main`은 이번 인계의 통합 대상이 아닙니다.
