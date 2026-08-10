# WalkSafe 산출물·구현 최종 완성 실행 계획

문서 ID: `WS-ARTIFACT-COMPLETION-EXECUTION-PLAN-20260726-001`

버전: `1.0.0`

상태: `ACTIVE_EXECUTION_PLAN`

기준일: `2026-07-26`

## 1. 목적

기존 질문·답변과 승인 정책을 요구사항 근거로 사용해 현재 WalkSafe 코드와 257개 산출물 유형을 대조하고, 누락·불일치·미구현 사항을 실제 코드·테스트·산출물 수정으로 닫는다.

최종 목표는 다음과 같다.

> 257개 산출물 유형이 적용성에 맞는 상태와 근거를 가지고, 제출 대상 문서의 형식·용어·수치·내용이 통일되며, 구현 주장이 현재 코드와 테스트에 의해 뒷받침되는 제출 패키지를 완성한다.

Goal은 이 목표를 달성하기 위한 실행 단위다. Goal 문서나 Goal 엔진 자체의 확장은 최종 목적이 아니다.

## 2. 범위

이 계획의 범위:

- 현재 `IN_PROGRESS`인 FP047의 실제 결함 보완과 완료
- 질문·답변, 정책, 요구사항, 코드, 테스트, 산출물의 현재 상태 대조
- 현재 상태에서 새로 확인되는 GAP의 등록과 보완
- Draft 53개와 Planned 75개의 적용성 판정·작성·검증
- 승인 기준선 102개와 Active 27개의 현재 코드 영향 및 전체 정합성 확인
- 40개 산출물 bundle 기준 병렬 작성·교차검토
- 코드·산출물 양방향 일치 검사
- 최종 제출 후보의 렌더링·누락·중복·무결성 검사
- 실제 기기·사용자·외부 승인 등 내부에서 끝낼 수 없는 경계의 정확한 분리

이 계획의 비범위:

- 새 Goal graph 버전 설계
- 완료된 Goal의 상태 변경이나 과거 실행 재판정
- 완료된 Goal을 처음부터 다시 실행하는 작업
- 기존 정책을 이유 없이 다시 질문하는 작업
- 실제로 수행하지 않은 정식 시험·실기기·현장·출시 결과의 완료 주장
- 최종 승인자를 AI로 대체하는 작업

완료된 Goal이 만든 현재 코드나 산출물은 비범위가 아니다. 현재 결과에서 오류가 발견되면 새 GAP과 새 보완 작업으로 수정하되, 과거 Goal 이력은 그대로 보존한다.

## 3. 현재 권위 기준

현재 상태가 서로 다르게 보일 때 다음 순서로 판정한다.

1. 유효한 승인 적용 receipt와 정책 기준선
2. 현재 `walksafe-project-continuation-checkpoint.json`
3. `artifact-register.json`과 artifact change log
4. 최신 implementation Gap·Backlog successor
5. 요구사항·설계·모듈·시험 추적자료
6. 현재 코드·설정·테스트와 실제 실행 결과
7. 과거 Goal 결과와 과거 계획 문서

현재 checkpoint가 과거 README의 준비 상태와 다르면 현재 checkpoint를 따른다.

| 기준 | 현재 값 |
|---|---|
| Goal package | `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4` |
| Package 상태 | `ACTIVE` |
| 활성 상태 | `ACTIVE` |
| 현재 focus | `WS-GOAL-EPIC-03-FP-047-R001` |
| 현재 Goal 상태 | `IN_PROGRESS` |
| 현재 정책·GAP | `FP-047 / GAP-056` |
| checkpoint schema | `1.21.0` |
| Gap 기준선 | r020, 68개 |
| Gap 상태 | BLOCKED 5, CONFLICTING 17, EVIDENCE_MISSING 4, MISSING 11, PARTIAL 31, IMPLEMENTED 0 |
| 산출물 분모 | 257 |
| 승인 기준선 | 102 |
| Active | 27 |
| Draft | 53 |
| Planned | 75 |

산출물 257은 독립 파일 257개를 뜻하지 않는다. 한 canonical 문서나 register가 여러 산출물 유형을 충족할 수 있으므로 진행률은 파일 개수가 아니라 artifact ID 상태로 계산한다.

## 4. 완료 상태 정의

### 4.1 내부 구현 완료

- 승인 정책과 충돌하는 구현이 없다.
- 저장소 내부에서 해결 가능한 필수 GAP이 없다.
- 필수 코드 변경에 targeted test와 관련 회귀 근거가 있다.
- 현재 코드의 공개 동작과 산출물 주장이 일치한다.
- 미실행 외부 검증을 내부 PASS로 표현하지 않는다.

### 4.2 산출물 내용 완료

- 257개 artifact ID가 승인·Active·검토 가능 Draft·근거 있는 N/A·외부 대기 중 하나로 명확히 판정된다.
- 제출 대상 산출물에는 placeholder, TODO, 가짜 수치, 오래된 구현 주장이 없다.
- 요구사항, 설계, API, 화면, 모델, 보안, 시험, 운영 수치가 bundle 사이에서 일치한다.
- 생성 산출물은 정본 입력과 generator를 통해 다시 만들 수 있다.

### 4.3 제출 패키지 준비

- 제출 manifest와 실제 파일이 1:1로 일치한다.
- 제출 파일은 전 페이지 렌더링·열기 검사를 통과한다.
- 깨진 링크, 누락 첨부, 중복 정본, 비밀값, 실제 개인정보가 없다.
- 내부에서 완료할 수 있는 P0/P1/P2 결함이 없다.
- 외부 사람·기기·권한이 필요한 항목은 대상, 행동, 근거, 승인 주체가 분리돼 있다.

### 4.4 외부 완료

실제 사용자·관리자·기기·현장·프로덕션·외부 전문검토·서명이 필요한 항목은 실제 evidence가 있을 때만 완료한다. 내부 제출 패키지 준비와 외부 완료는 같은 상태로 합치지 않는다.

## 5. 전체 실행 시퀀스

| 단계 | 핵심 작업 | 병렬 실행 | 종료 조건 | 상세 계획 |
|---|---|---|---|---|
| 0 | 중단 상태 복구와 FP047 보완 | FP047 코드·테스트·문서 영향 packet | FP047 차단 finding 0, 독립 검토 승인, 완료 전이 | [01-phase-0-fp047-resume.md](2026-07-26_artifact_completion_execution_plan/01-phase-0-fp047-resume.md) |
| 1 | 현재 코드·산출물 기준선 감사 | 7개 도메인 read-only lane | 257개 상태·적용성·현재 GAP 후보 분류 완료 | [02-phase-1-current-state-baseline.md](2026-07-26_artifact_completion_execution_plan/02-phase-1-current-state-baseline.md) |
| 2 | GAP 정규화와 실행 Wave 편성 | 충돌 없는 도메인별 packet 설계 | 중복 없는 GAP, 수용 기준, 소유 경로, 의존성 확정 | [03-phases-2-3-gap-wave-execution.md](2026-07-26_artifact_completion_execution_plan/03-phases-2-3-gap-wave-execution.md) |
| 3 | Goal 단위 코드·테스트·산출물 보완 | 하나의 focus Goal 내부 6~9개 packet | Goal별 수용 기준과 영향 산출물 반영 완료 | [03-phases-2-3-gap-wave-execution.md](2026-07-26_artifact_completion_execution_plan/03-phases-2-3-gap-wave-execution.md) |
| 4 | Draft·Planned 산출물 완성 | 40개 bundle 중 독립 bundle 병렬 작성 | 내부 작성 가능 산출물의 내용·정합성 완료 | [04-phase-4-artifact-authoring-consistency.md](2026-07-26_artifact_completion_execution_plan/04-phase-4-artifact-authoring-consistency.md) |
| 5 | 묶음·전체 통합 검증 | 영역별 검사와 독립 검토 | 내부 P0/P1/P2 0, 현재 코드와 산출물 일치 | [05-phase-5-integration-validation.md](2026-07-26_artifact_completion_execution_plan/05-phase-5-integration-validation.md) |
| 6 | 최종 제출 후보와 외부 경계 정리 | 렌더링·manifest·보안 검사 | 제출 패키지 고정 또는 정확한 외부 blocker 분리 | [06-phase-6-final-package-external-boundary.md](2026-07-26_artifact_completion_execution_plan/06-phase-6-final-package-external-boundary.md) |

병렬 에이전트·파일 소유권·메모리 피크·로그아웃 복구는 모든 단계에 [07-parallel-resource-resumption.md](2026-07-26_artifact_completion_execution_plan/07-parallel-resource-resumption.md)를 적용한다.

## 6. Goal 실행 원칙

- 현재 v2.4의 단일 `focus_goal_id` 계약은 유지한다.
- 동시에 여러 canonical Goal을 `IN_PROGRESS`로 만들지 않는다.
- 하나의 Goal 내부 작업을 코드 영역, 테스트, 산출물 영향, 독립 검토 packet으로 나눠 병렬화한다.
- 완료된 Goal은 다시 열지 않는다.
- 현재 결과에서 발견한 결함은 새 GAP 또는 현재 Goal의 미해결 finding으로 처리한다.
- Goal 종료 검사는 한 번 수행하고, 완료 뒤에는 현재 제품 통합 검사만 수행한다.
- Goal 완료 전에 관련 artifact ID와 register 변화량을 확인한다.
- 기존 v2.4가 요구하는 최소 receipt와 전이는 유지하되, 목적 없는 builder·checker·중복 Markdown·반복 checkpoint는 추가하지 않는다.
- 다음 Goal은 유효한 checkpoint, latest Backlog, ready frontier, 실제 의존성으로 선택한다.

## 7. 연속 실행과 질문 조건

다음 경우가 아니면 Goal 사이에서 사용자에게 진행 확인을 요청하지 않는다.

- 승인 정책을 바꿔야 하는 새로운 제품 결정
- 서로 양립할 수 없는 사용자 답변
- 실제 기기·참여자·외부 전문검토자 필요
- 비밀값·유료 자원·프로덕션 권한 필요
- 외부 통지·실제 삭제·배포·출시·인수·이관·종료 승인
- 사용자 작업과 동일 파일에서 예상하지 못한 변경 충돌

한 branch가 막혀도 독립적인 내부 ready packet과 artifact bundle을 계속 진행한다. 모든 내부 경로가 막힌 경우에만 전체 대기를 선언한다.

## 8. 목표 일정

| 기간 | 목표 |
|---|---|
| 0~1일 | FP047 재개·보완과 현재 상태 감사 병렬 수행 |
| 1~2일 | GAP 정규화, 적용성 판정, Wave와 파일 소유권 확정 |
| 2~5일 | 독립 GAP Goal과 산출물 bundle 병렬 실행 |
| 5~7일 | 전체 통합 검사, corrective Goal, 문서 렌더링 수정 |
| 7~9일 | 최종 manifest, 제출 후보, 외부 action packet 확정 |

이 일정은 내부에서 수행 가능한 작업 기준이다. 실제 기기, 사용자, 프로덕션, 외부 승인 대기시간은 별도로 표시한다.

## 9. 진행률 보고

단일 백분율 대신 다음 값을 함께 보고한다.

- Artifact: 승인 기준선 / Active / Draft / Planned / 외부 대기 / 전체 257
- GAP: 내부 미해결 / 외부 차단 / 완료
- Goal: 현재 focus / 현재 상태 / 다음 후보
- Packet: 완료 / 실행 / 차단 / 전체
- 검증: targeted / component / integration / formal / device / external
- Heavy lane: 실행 중 / 대기 / 유휴
- 재개 경계: 마지막 committed event와 다음 단일 행동

## 10. 최종 성공 기준

- 모든 257개 artifact ID의 적용성과 상태가 명확하다.
- 제출 대상 산출물의 필수 내용 coverage가 100%다.
- 필수 요구사항의 산출물 연결률이 100%다.
- 구현 요구사항의 코드·테스트 연결률이 100%다.
- 현재 코드의 공개 동작과 산출물 설명 사이 P0/P1 불일치가 0이다.
- 제출 대상 P0/P1/P2 미해결 결함이 0이다.
- 깨진 참조, manifest 외 파일, placeholder, 비밀값, 실제 개인정보가 0이다.
- 최종 제출 파일의 렌더링 검사율이 100%다.
- 실제로 수행하지 않은 외부 시험과 승인을 완료로 주장한 항목이 0이다.
- 중단 후 새 세션이 현재 checkpoint와 인계 정보만으로 정확히 재개할 수 있다.

