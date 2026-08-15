# WalkSafe 문서 지도

팀원이 먼저 볼 문서는 [중앙 가이드 포털](guides/README.md)입니다. 이 파일과 `docs/guides/**`는 문서를 찾고 안전하게 실행하는 방법을 설명하는 안내 계층이며, 정책·승인·시험 결과의 정본이 아닙니다.

## 정본 우선순위

1. 동적 현재 작업·출시 경계: [continuation checkpoint](control/walksafe-project-continuation-checkpoint.json)
2. 승인 정책: [PB-WALKSAFE-FEATURE-POLICY-1.0.1](control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json)
3. 산출물 승인 상태: [artifact current notice](deliverables/00-control/artifact-register-current-notice-20260728-r001.md)에서 연결하는 DOC-01·DOC-05·COMMITTED 영수증
4. 구현 사실: 현재 코드, OpenAPI, lockfile와 실행 가능한 자동검사
5. 탐색·실행 절차: `docs/guides/**`와 코드 가까운 README
6. 날짜가 지난 상태·계획·Web/PWA 문서: 역사 참고만 허용

정본끼리 충돌하면 임의로 하나를 고치지 말고 [산출물 가이드](guides/deliverables-guide.md)의 변경 절차를 따릅니다.

## 빠른 시작

| 목적 | 문서 |
|---|---|
| 프로젝트 범위·현재 상태 | [프로젝트 가이드](guides/project-guide.md) |
| 저장소 구조·CURRENT/LEGACY 구분 | [저장소 가이드](guides/repository-guide.md) |
| 제품 코드와 요청 흐름 | [코드 가이드](guides/code-guide.md) |
| 개발환경 구성 | [개발환경 가이드](guides/development-environment-guide.md) |
| 산출물 작성·변경·승인 | [산출물 가이드](guides/deliverables-guide.md) |
| 자동 테스트와 정식 시험 | [테스트 가이드](guides/testing-guide.md) |
| 스크립트 선택과 부작용 | [스크립트 가이드](guides/scripts-guide.md) |
| 모델·데이터 반입 | [데이터·AI 가이드](guides/data-ai-guide.md) |
| 보안·개인정보 | [보안·개인정보 가이드](guides/security-privacy-guide.md) |
| 릴리스·운영 | [릴리스·운영 가이드](guides/release-operations-guide.md) |
| 팀 분담·완료 정의 | [팀 작업 가이드](guides/team-workflow.md) |
| 구현 기능 목록 | [기능 구현 카탈로그](planning/walksafe_feature_implementation_catalog.html) |

## 현재 상태

기준일 2026-08-13의 요약이며 checkpoint가 우선합니다.

- 제품: Android 사용자 앱과 별도 Android 관리자 앱
- 지원: Android Gateway, Backend, 모델·데이터·계약·배포 도구
- Web/PWA: `LEGACY_REFERENCE_ONLY`
- `NPC-SINGLE-ADMIN-RECOVERY / GAP-008`: 저장소 내부 구현·자동검증 `COMPLETE_AT_TARGET`, Gap `PARTIAL`
- 다음 통제 작업: `EPIC-03` workstream 완료 조건 평가; 다음 구현 후보는 `FP-022 / GAP-031`
- 정식 시험: 279/279 `NOT_RUN`
- 출시 gate: 5/5 `NOT_RUN`, 미면제
- 출시: `NOT_ELIGIBLE`

## 현재 문서 폴더

| 경로 | 역할 | 사용 규칙 |
|---|---|---|
| [`guides/`](guides/) | 사람용 프로젝트·코드·산출물·테스트·운영 안내 | 첫 진입점, 비정본 |
| [`deliverables/`](deliverables/) | 257종 정식 산출물·대장·추적자료 | 등록된 경로 유지, 승인 절차 없이 직접 수정 금지 |
| [`control/`](control/) | 정책 기준선·checkpoint·Goal·감사·실행 증거 | 내부 통제 영역, 과거 event·receipt 불변 |
| [`planning/`](planning/) | 팀 기능 카탈로그와 현대화 계획 | 협업·계획 자료, 승인 정책을 대체하지 않음 |
| [`testing/`](testing/) | 자동·현장·과거 Web 시험 안내 | 현행 실행법은 중앙 테스트 가이드 우선 |
| [`backend/`](backend/) · [`android/`](android/) | 하위 시스템 상세 기술 문서 | 현재 코드·로컬 README와 대조 |
| [`design/`](design/) · [`operations/`](operations/) · [`release/`](release/) | 설계·운영·릴리스 참고자료 | 정식 deliverable과 checkpoint가 우선 |
| [`status/`](status/) · [`walksafe-v2/`](walksafe-v2/) · [`evidence/`](evidence/) · [`review/`](review/) | 날짜별 상태·이전 설계·검토 근거 | 현재 상태 정본으로 사용하지 않음 |
| [`execution/`](execution/) | 날짜별 초기 실행 기록 | `EVIDENCE / KEEP_AT_PATH`; 현재 상태의 정본으로는 사용하지 않음 |

## 통제 문서 주의사항

- `control/goals/walksafe-completion-graph-v2-4/README.md` 등 package 내부 설명은 당시 활성화 상태를 byte-exact하게 보존합니다. 그 문구가 현재 checkpoint와 달라도 직접 수정하지 않습니다.
- 현재 v2.4 package는 checkpoint 기준 `ACTIVE`입니다.
- 최신 Gap·Backlog는 [r026 Gap](control/audits/walksafe-implementation-gap-analysis-20260812-r026.json)과 [r026 Backlog](control/audits/walksafe-implementation-remediation-backlog-20260812-r026.json)입니다.
- frozen Goal·기존 gate 원출력·완료 영수증·canonical binding은 경로와 bytes를 보존합니다.

## 역사 문서 사용 규칙

과거 Web/PWA 중심 설명, 날짜별 status, 실행 기록과 완료된 계획은 삭제 대상이 아니라 감사·비교용 역사 자료입니다. 다만 다음 용도로 사용하면 안 됩니다.

- Android 제품 정책을 결정하는 근거
- 현재 기능 완료율이나 출시 가능 상태의 근거
- 정식 시험·실기기·현장 PASS 주장
- 현재 API·모델·배포 명령의 단독 근거

필요한 역사 자료가 현재 branch에서 격리됐다면 원격 `archive/current-pre-modernization-20260811` 또는 [보존 기록](planning/repository-modernization-20260811/preservation-record.md)에서 찾습니다.

## Git에 넣지 않는 자료

원본 데이터셋, 사용자 영상·음성·정확 위치, 비밀값, 운영 로그, 임시 build/cache, 신규 대형 모델 산출물은 Git에 넣지 않습니다. 현재 추적 중인 TFLite 3개와 모델 후보 PT 1개는 검증 가능한 기존 예외이며 신규 반입 허가가 아닙니다. 세부 기준은 [데이터·AI 가이드](guides/data-ai-guide.md)와 [보안·개인정보 가이드](guides/security-privacy-guide.md)를 따릅니다.
