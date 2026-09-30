# WalkSafe 프로젝트 가이드

이 문서는 프로젝트를 처음 보는 팀원을 위한 탐색·절차 안내입니다. 정책, 요구사항, 승인 상태, 다음 작업의 정본이 아니며 충돌할 때는 연결된 통제 자료가 우선합니다.

## 프로젝트 목적

WalkSafe는 시각장애인의 도심 보행을 돕는 Android 보행 보조 프로젝트입니다. 가까운 위험 안내, TMAP 기반 큰 이동 방향 안내, 손상 점자블록 신고를 결합합니다. 흰지팡이·안내견·보호자를 대체하거나 보행 안전을 보장하는 제품으로 설명하지 않습니다. 짧은 소개는 [루트 README](../../README.md)에서 확인합니다.

## 현재 제품 경계

| 구성 | 역할 | 현재 해석 |
|---|---|---|
| Android 사용자 앱 | 위험·길안내·신고를 제공하는 사용자 제품 | 정식 제품 후보, 내부 구현·검증 진행 중 |
| Android 관리자 앱 | 신고 검수·수동 기관 전달 기록·감사 업무를 처리하는 별도 제품 | 사용자 앱과 식별자·서명·세션·배포 경계 분리 |
| Android Gateway | 세션·보행 원장은 로컬 종결하고 길찾기·신고는 Backend에 중계하며, 동의·삭제에 혼합 제어를 적용 | 지원 서비스, 실제 배포·실기기 연결은 별도 검증 필요 |
| Backend | 계정·신고·공간·감사 데이터와 내부 API 제공 | 지원 서비스, 운영 준비와 출시 승인은 별도 |
| Web/PWA | 과거 동작과 회귀를 위한 참고 코드 | 제품 경계 `LEGACY_REFERENCE_ONLY`·저장소 분류 `LEGACY_REFERENCE`, 현재 제품·출시 근거로 사용 금지 |

제품 경계의 상세 설명은 [애플리케이션 안내](../../apps/README.md), 사용자 앱은 [현재 Android 코드 지도](code/android-user.md), 관리자 앱은 [관리자 앱 안내](../../apps/android/adminapp/README.md), Gateway는 [현재 Gateway 코드 지도](code/android-gateway.md), 서버는 [Backend 안내](../../backend/README.md)에서 확인합니다. 모듈 README는 현행 안내이며, 그 안의 날짜형 검증과 과거 실행 증거는 해당 시점의 기록으로 구분합니다.

## 무엇이 정본인가

| 판단 | 확인할 자료 |
|---|---|
| 승인된 기능·안전·운영 정책 | [정책 기준선 1.0.1](../control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json) |
| 현재 기능 개선·분담·다음 행동 | [2026-09-30 인계](../handoff/2026-09-30/README.md), [최근 daylog](../../daylog/2026-09-30.md), [기능 개선 실행 계획](../planning/walksafe-feature-improvement-execution-20260926.md) |
| 현행 기능 작업·파일 소유·완료 판정 | 저장소 [`AGENTS.md`](../../AGENTS.md)의 기능 우선 원칙, [팀 작업 흐름](team-workflow.md), 자기 파트 원문 성공 조건 |
| 과거 재개 절차 감사 자료 | [hash로 봉인된 재개 안내서](../control/walksafe-project-resumption-runbook.md). 상태·focus·활성화·gate 명령을 현행으로 실행하지 않음 |
| 산출물별 현재 상태 | [산출물 current notice](../deliverables/00-control/artifact-register-current-notice-20260728-r001.md)에서 연결하는 DOC-01 |
| 요구사항과 예정 시험 연결 | [요구사항 추적표](../deliverables/03-requirements/rtm.json) |
| 현재 구현 차이와 수정 후보 | [구현 Gap r026](../control/audits/walksafe-implementation-gap-analysis-20260812-r026.json), [수정 백로그 r026](../control/audits/walksafe-implementation-remediation-backlog-20260812-r026.json) |

날짜가 오래된 README, 과거 Web/PWA 계획, 구현 코드 자체를 승인 정책으로 역승격하지 않습니다.

## 현재 진행 상태를 읽는 법

- `내부 구현`은 코드와 저장소 내부 검증 근거가 있다는 뜻입니다.
- `완료 영수증`은 해당 Work Item의 허용 범위를 닫는 기록이며 프로젝트 전체 완료나 출시 승인을 뜻하지 않습니다.
- 예정된 정식 시험은 `279/279 NOT_RUN`입니다.
- 출시 gate 5개는 모두 `NOT_RUN`·미면제입니다.
- 현재 출시는 `NOT_ELIGIBLE`입니다.

최신 기능 상태와 다음 행동은 [인계 현황](../handoff/2026-09-30/status.md)과 자기 파트에서 확인합니다. 과거 checkpoint의 focus를 현재 기능 분담으로 재사용하지 않습니다. 필수 기기·외부 증거가 남은 항목은 PARTIAL로 유지합니다.

## 기능 계획과 분담

[기능 구현·분담 목록](../planning/walksafe_feature_implementation_catalog.html)은 기능을 쉬운 표현으로 나누고 분야·상태·우선순위·담당자를 함께 보는 협업 화면입니다. 이 목록은 분담 보조 도구이며 정책 승인, Goal 활성화, 구현 완료 판정을 대신하지 않습니다.

작업을 고를 때는 다음 순서를 지킵니다.

1. [두 사람 인계](../handoff/2026-09-30/README.md)에서 자기 담당 ID와 파일 소유를 확인합니다.
2. [항목 상세](../handoff/2026-09-30/remaining-items.json)의 원문·잔여·의존성을 현행 코드와 대조합니다. 미정합은 이 대조부터 수행합니다.
3. 같은 원인의 작은 묶음을 선택하고 상대 담당과 연결 계약·통합 순서를 맞춥니다.
4. 실제 source·OpenAPI·lock·runtime config와 관련 시험으로 범위를 확정합니다. 과거 카탈로그·r026은 당시 참고 자료이며 최신 잔여를 대신하지 않습니다.

구체적인 협업 순서는 [팀 작업 흐름](team-workflow.md)을 따릅니다.

## 산출물과 시험

산출물 작성 위치와 상태는 [산출물 가이드](deliverables-guide.md)에서 연결하는 current notice와 관리대장이 기준입니다. 구현 결과를 문서화할 때 기존 승인본이나 불변 영수증을 덮어쓰지 않고, 허용된 Active 기록 또는 새 revision으로 남깁니다.

시험 계층과 실행 절차는 [테스트 가이드](testing-guide.md)를 따릅니다. 내부 단위·통합 검사가 통과해도 실기기·현장·운영·접근성·외부 수락 시험을 실행한 것으로 기록하지 않습니다.

## 완료 판단

기능 작업은 최소한 코드, 관련 자동 검사, 필요한 문서, 추적 가능한 변경 근거가 함께 있어야 검토할 수 있습니다. 그러나 다음 항목은 별도 실제 증거 없이는 완료로 판정하지 않습니다.

- 실제 Android 기기와 통제된 현장 관찰
- TMAP 등 외부 공급자 연동 결과
- 운영 배포, 용량 측정, 백업·복구 훈련
- 사람 대상 접근성·안전 시험과 외부 검토
- 정식 시험 279건과 출시 gate 5개

출시 판정은 [릴리스 통제 문서](../deliverables/09-release/release-control.md)의 실제 증거와 승인 절차를 따릅니다.
