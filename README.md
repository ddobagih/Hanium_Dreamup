# Hanium DreamUp — WalkSafe

WalkSafe는 시각장애인의 도심 보행을 돕는 Android 보행 보조 프로젝트입니다. 카메라와 기기 내 AI로 가까운 위험을 찾고, TMAP 기반 보행 경로와 음성·진동 안내를 제공하며, 손상된 점자블록 신고를 돕습니다. 정식 안전시험 전에는 흰지팡이·안내견·보호자를 대신하거나 보행 안전을 보장하는 제품으로 설명하지 않습니다.

## 빠른 링크

| 찾는 내용 | 시작점 |
|---|---|
| 제품 코드와 구조 | [코드 가이드](docs/guides/code-guide.md) |
| 팀 기능 분담 | [기능 구현 카탈로그](docs/planning/walksafe_feature_implementation_catalog.html) |
| 산출물 정본 | [산출물 대장 current notice](docs/deliverables/00-control/artifact-register-current-notice-20260728-r001.md) |
| 자동 테스트와 정식 시험 | [테스트 가이드](docs/guides/testing-guide.md) |
| 현재 상태와 다음 작업 | [continuation checkpoint](docs/control/walksafe-project-continuation-checkpoint.json) |
| 레거시 사용 금지 경계 | [저장소 가이드 — 레거시 경계](docs/guides/repository-guide.md#레거시-경계) |
| 기여·보안 | [CONTRIBUTING](CONTRIBUTING.md) · [SECURITY](SECURITY.md) |

[전체 가이드 포털](docs/guides/README.md)에서 프로젝트·개발환경·코드·산출물·스크립트·데이터/AI·보안·릴리스·팀 작업 안내를 찾을 수 있습니다.

## 현재 상태

기준일은 2026-08-11이며, 동적 현재 상태의 정본은 [checkpoint](docs/control/walksafe-project-continuation-checkpoint.json)입니다.

- Goal package: v2.4 `ACTIVE`
- 다음 내부 작업: `NPC-SINGLE-ADMIN-RECOVERY / GAP-008`, Goal `READY`; 내부 시작 gate `NOT_RUN`
- 정식 시험: 279/279 `NOT_RUN`
- 출시 gate: 5/5 `NOT_RUN`, 면제 없음
- 실제 기기·현장·운영 배포·외부 수락: `NOT_RUN`
- 출시 상태: `NOT_ELIGIBLE`

자동 테스트나 debug build가 통과해도 위 정식 시험·배포·출시 완료를 뜻하지 않습니다.

## 제품 경계

정책 기준선은 [PB-WALKSAFE-FEATURE-POLICY-1.0.1](docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json)입니다.

| 구성 | 역할 | 상태 |
|---|---|---|
| [`apps/android/app`](apps/android/app) | 일반 사용자용 Android 앱 | `CURRENT_PRODUCT`, 정식 출시 전 |
| [`apps/android/adminapp`](apps/android/adminapp) | 지정 관리자용 별도 Android 앱 | `CURRENT_PRODUCT`, 내부 검증만 수행 |
| [`apps/android-gateway`](apps/android-gateway) | Android 세션·보행·길찾기·신고·개인정보 제어 Gateway | `SUPPORT`, 실제 배포 `NOT_RUN` |
| [`backend`](backend) | 계정·신고·관리자·데이터·외부 API 서버 | `SUPPORT`, 운영 배포 `NOT_RUN` |
| [`model`](model) · [`data_sources`](data_sources) | 모델 runtime·학습 후보·provenance | `SUPPORT`, 정식 모델 승인 전 |
| [`apps/web`](apps/web) | 과거 Web/PWA 코드와 410 경계 회귀 | `LEGACY_REFERENCE_ONLY`, 구현·출시 근거 사용 금지 |
| [`voice`](voice) | 별도 로컬 음성 prototype | `LEGACY_REFERENCE`, Android 제품 경로 아님 |

관리자 앱은 외부 기관에 자동 전송하지 않습니다. 앱 밖에서 실제 수행한 수동 전달 사실과 상태를 기록할 뿐입니다.

## 작업 시작

1. [AGENTS.md](AGENTS.md)를 읽습니다.
2. [프로젝트 가이드](docs/guides/project-guide.md)와 [개발환경 가이드](docs/guides/development-environment-guide.md)를 확인합니다.
3. 읽기 전용 continuation 검사를 실행합니다.

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_4.py
```

현재 standalone 저장소의 Goal graph 검사는 제외된 과거 history 때문에 기존 63건이 실패합니다. 이를 성공으로 오인하거나 새 실패와 섞지 않습니다. 제품 기능 변경 전에는 `AGENTS.md`, 현재 checkpoint와 checkpoint가 가리키는 focus Goal의 event-scoped 계약을 따라야 합니다. hash로 봉인된 과거 재개 안내서의 gate 명령은 현행 절차가 아닙니다.

빌드·테스트 대표 명령은 [테스트 가이드](docs/guides/testing-guide.md), 환경 구성은 [개발환경 가이드](docs/guides/development-environment-guide.md)만 기준으로 사용합니다.

## 산출물과 구현 상태

- 산출물 작성·변경 절차: [산출물 가이드](docs/guides/deliverables-guide.md)
- 최신 구현 Gap: [r025 Gap](docs/control/audits/walksafe-implementation-gap-analysis-20260810-r025.json)
- 최신 수정 백로그: [r025 Backlog](docs/control/audits/walksafe-implementation-remediation-backlog-20260810-r025.json)
- 기능별 분담: [팀 기능 카탈로그](docs/planning/walksafe_feature_implementation_catalog.html)

기존 승인 기록·Goal event·gate 증거와 canonical register는 직접 수정하지 않습니다. 변경 절차는 가이드와 checkpoint를 따릅니다.

## 모델·데이터 저장 정책

현재 저장소에는 Android runtime에 필요한 TFLite 3개와 추적 가능한 모델 후보 PT 1개가 명시적 예외로 포함되어 있습니다. 이 예외는 새 weight·dataset·사용자 자료를 임의로 Git에 추가해도 된다는 뜻이 아닙니다.

원본 데이터셋, 사용자 영상·음성·정확 위치, 비밀값, 운영 로그, 신규 대형 모델 산출물은 Git에 넣지 않습니다. 자세한 반입·provenance 규칙은 [데이터·AI 가이드](docs/guides/data-ai-guide.md)를 따릅니다.

## 현대화 전 복구 지점

현대화 전 `current`는 원격 `archive/current-pre-modernization-20260811`, 태그 `preservation/current-pre-modernization-20260811`, 권한 제한 로컬 bundle로 보존했습니다. 검증 기록은 [preservation record](docs/planning/repository-modernization-20260811/preservation-record.md)에 있습니다. 기존 `main`은 변경하지 않았습니다.
