+++
schema_version = "2.0"
goal_id = "WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"
goal_kind = "MASTER"
document_version = "2.1.0"
parent_goal_id = ""
external_key = "WALKSAFE-COMPLETION"
workstream_type = "PROJECT"
priority_rank = 0
initial_status = "READY"
target_completion_level = "PROJECT_ACCEPTED_AND_HANDOVER_OR_CLOSURE_COMPLETE"
work_item_id = ""
start_requires = []
completion_requires = []
child_goal_ids = ["WS-GOAL-EPIC-01", "WS-GOAL-EPIC-02", "WS-GOAL-EPIC-03", "WS-GOAL-EPIC-04", "WS-GOAL-EPIC-05", "WS-GOAL-EPIC-06", "WS-GOAL-EPIC-07", "WS-GOAL-EPIC-08", "WS-GOAL-EPIC-09", "WS-GOAL-EPIC-10", "WS-GOAL-EPIC-11", "WS-GOAL-EPIC-12"]
source_policy_ids = []
gap_ids = []
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_condition_codes = ["POLICY_CHANGE_REQUIRED", "PRODUCT_DIRECTION_AMBIGUITY", "IRREVERSIBLE_EXTERNAL_ACTION", "SECRET_OR_PAID_RESOURCE_REQUIRED", "REAL_DEVICE_OR_PARTICIPANT_REQUIRED", "AUTHORITY_EXPANSION_REQUIRED"]
stop_condition_codes = ["CANONICAL_INPUT_VALIDATION_FAILED", "DEPENDENCY_NOT_COMPLETE", "POLICY_CONFLICT", "SAFETY_OR_SECURITY_CRITICAL_FAILURE", "USER_CHANGE_OVERLAP", "EXTERNAL_EVIDENCE_REQUIRED"]
+++

# Master Goal — WalkSafe 의존성 그래프 기반 자율 완성

## 목표

승인된 기능 정책 기준선 1.0.1과 257개 산출물을 실제 개발·검증·릴리스·운영·이관에 사용해 WalkSafe를 완성한다. 임의의 단계 수를 먼저 정하지 않고, 정책 Gap·산출물 의존성·실제 사건에서 필요한 작업을 만들고 준비된 작업을 계속 선택한다.

현재 초점은 `EPIC-02 / FP-018 / GAP-027`이다. 정식 시험 279개와 실제 기기 시험은 `NOT_RUN`, 5개 Gate는 미면제, 출시는 `NOT_ELIGIBLE`이다.

현재 `package_status=PREPARED_NOT_ACTIVATED`, `activation_status=READY_NOT_ACTIVATED`다. 사용자가 이 graph-v2-1 패키지의 활성화를 명시적으로 요청하기 전에는 새 실행 event나 기능 변경을 시작하지 않는다. 활성화 요청 뒤에는 사용자 승인 원본과 별도 quick-gate receipt를 결속한 `PACKAGE_ACTIVATED`를 먼저 기록하되 Goal 상태는 바꾸지 않는다. 제품 코드 작업 전에는 재개 runbook §10.1의 전체 구현 시작 검사를 추가로 통과해야 하며, 그 뒤 별도 `GOAL_STARTED`로 선택된 Work Item 하나만 `IN_PROGRESS`로 전환한다. 새 터미널에서 그 Work Item을 계속할 때는 전체 검사를 다시 통과한 `WORK_SESSION_RESUMED`를 먼저 기록한다.

## 정본 입력

판정 우선순위는 다음과 같다.

1. COMMITTED 승인 적용 receipt
2. 기능 정책 기준선 1.0.1
3. DOC-01 현재 상태와 257개 산출물 의존성
4. DOC-05 append-only 변경이력
5. 최신 Gap·Backlog successor
6. RTM·설계 추적·시험 원장
7. 현행 코드와 내부 실행 결과
8. 과거 계획·PWA·제출용 문서

Goal 문서는 정책 정본이 아니다. Goal은 위 정본에서 다음 실행을 계산하고 인계하는 통제자료다.

## 범위와 제외

자율 실행 범위:

- 저장소 안의 코드·설정·테스트·문서 변경
- 승인 정책을 구현하기 위한 기술 세부 결정과 ADR
- 내부 검증과 서브에이전트 독립 검토
- Draft 작성과 Active 원장의 실제 사실 기록
- 승인 정책을 바꾸지 않는 내부 기술·관리 문서의 검토 및 기준 충족 시 위임 승인
- Gap·Backlog·산출물 successor와 typed 실행 증거
- 체크포인트·daylog·local-memory 갱신
- 의존성이 충족된 다음 Goal 선택과 시작

포괄 권한에 포함되지 않는 범위:

- 규범 정책·보존기간·동의·사용자 경험 변경
- 규범 정책·안전·개인정보·Gate·출시·인수·이관·종료를 주장하는 Approved/Baselined 승격
- Gate 면제나 정식 시험 PASS 조작
- 실제 기기·참여자·독립검토를 내부 테스트로 대체
- 유료자원·비밀정보·프로덕션 배포·외부 통지·실제 삭제
- 출시·인수·운영 이관·종료 서명 대행

내부 위임 승인은 사용자가 이미 확정한 정책과 범위 안에서만 적용한다. 완료 기준, 실제 변경 파일 hash, 검증 원출력, 실행자와 다른 검토자의 review record를 남긴 뒤 기술·관리 문서를 승인할 수 있으며 새 사용자 질문을 만들지 않는다. 정책·안전·개인정보 판단, 정식 시험 PASS, Gate, 외부 서명과 실제 사건은 이 위임으로 대체하지 않는다.

## 실행 절차

### 그래프 계산

1. artifact register의 257개 항목과 698개 의존성을 검사한다.
2. 최신 Gap의 68개 `source_policy_id → gap_id`를 유일하게 연결한다.
3. 현재 materialized Goal과 typed edge를 읽는다.
4. 모든 `REQUIRES`가 끝난 `PLANNED` Goal을 완료 event hash에 결속한 `GOAL_READY`로 전환한다.
5. `READY`·`IN_PROGRESS` leaf의 ready frontier를 계산한다.
6. blocker가 있는 branch만 대기시키고 독립 ready branch는 유지한다.
7. Backlog 다음 행동, P0 위험도, 후행 해제 효과, priority rank, Goal ID 순으로 초점을 선택한다.
8. 선택 결과가 Workstream이면 필요한 Work Item을 먼저 만들고, `IN_PROGRESS`는 선택된 Work Item 하나에만 부여한다.
9. `ARTIFACT_REGISTER` SHA에 결속된 queue가 257개를 정확히 분할하는지 확인하고 실행 가능한 `DLV-*`가 있으면 단일 대상 `ARTIFACT_WORK`를 materialize한다.

### 한 작업 실행

1. 정책→요구→설계→코드→시험 추적과 현재 차이를 확인한다.
2. fail-first 검증을 만들고 최소 변경을 구현한다.
3. targeted·구성요소·회귀 검증과 독립 검토를 수행한다.
4. `IMPLEMENTATION_RECORD`, `VERIFICATION_RESULT`, `SUCCESSOR_TRACE`를 각각 남긴다.
5. 실제 사실에 맞게 Draft·Active 산출물과 Gap·Backlog successor를 갱신한다.
6. Gap·Backlog 등 canonical binding이 바뀌면 staged snapshot에서 정책·Gap별 변화와 전역 변화를 계산한다. 허용된 내부 생산자는 완료 receipt를 결속하고 update 직후 완료하며, 영향받은 Work Item은 같은 후보에서 successor 처리한다.
7. 완료 Workstream에 실질 영향이 생기면 부모·역의존 폐쇄를 함께 계산하고 과거 완료 event와 증거를 보존한다. 같은 canonical update에서 직접 영향 또는 child·completion 집계 갱신만 필요한 완료 Workstream은 `READY`, start dependency가 무효화된 완료 Workstream과 그 선행조건이 깨진 downstream 준비 상태는 `PLANNED`로 바꾸며, 후자는 upstream 재완료 뒤 별도 `GOAL_READY`가 필요하다. 영향받은 Work Item은 dependency successor를 반영한 연속 revision으로 처리하고, Workstream 재완료에는 최신 dependency·child 완료 뒤 만든 새 집계 검증 증거가 필요하다. 정적 그래프 구조 변경은 versioned successor manifest로 처리하며 완료 Master와 종결 패키지에는 새 event를 붙이지 않는다.
8. `WORK_ITEM_EXECUTION_RECEIPT`가 정확한 Goal·정책·Gap·가장 최근 실행 세션 event·시간·세 결과를 결속하고 완료 event가 같은 증거·receipt binding을 가리킬 때만 완료한다.
9. staged checkpoint와 event를 검증한 뒤 한 번의 논리적 commit으로 전환한다.
10. 새 ready frontier를 계산해 질문 조건이 없으면 다음 작업을 진행한다.

Workstream 자체는 `IN_PROGRESS`로 시작하지 않는다. 모든 필수 child·coverage·Backlog 목표가 충족되면 컨테이너를 `READY → COMPLETE_AT_TARGET`으로 닫는다.

### 조건부 실행

- 산출물 queue에서 blocker와 upstream이 해소된 대상이 생기면 단일 `DLV-*` `ARTIFACT_WORK`를 만든다. `PENDING_EVALUATION`은 적용성 결정 경로로 보존한다.
- 하나의 시험 후보가 준비되면 `INTEGRATION_CANDIDATE`를 만든다.
- 승인 시험계획과 적격 환경이 준비되면 `FORMAL_TEST_RUN`을 만든다.
- 각 Gate는 고유 선행조건이 준비될 때 별도 `RELEASE_GATE`로 만든다.
- 모든 정식 증거가 같은 후보로 모이면 `RELEASE_DECISION`을 만든다.
- 실제 권한과 사건이 생길 때만 배포·운영·이관·종료 Goal을 만든다.
- 결함이나 증거 반려는 과거를 고치지 않는다. 먼저 Gap·Backlog successor의 canonical 영향 transaction으로 기록한 뒤 기존 Work Item 유형을 유지한 연속 revision과 `GOAL_SUPERSEDED`, 필요한 `REOPEN_CONTAINER` 전환으로 처리한다. successor/reopen은 별도 Work Item 유형이 아니며, 정적 구조 변경이나 종결 뒤 변경은 versioned successor package로 처리한다.

## 검증

매 작업에서 최소한 다음을 확인한다.

- current bytes에서 Gap 재현 또는 합격 여부 구분
- targeted test와 관련 구성요소 회귀
- 정책·REQ·DES·TC·산출물 추적
- 안전·보안·개인정보 영향
- 279개 시험·5개 Gate·출시 상태 비승격
- DAG 순환·고아·미충족 의존성 없음
- checkpoint·event·manifest·파일 SHA-256 정합

실제 실행하지 않은 시험은 `PASS`라고 쓰지 않는다. 저장소 내부 검증은 `INTERNAL_VERIFIED` 범위만 주장한다.

## 완료 기준

Master Goal은 고정된 자식 수를 완료했다고 끝나지 않는다. 다음 조건을 모두 만족하고 더 이상 필수 미완료 노드가 없을 때만 끝난다.

- 68개 정책·Gap의 필수 내부 구현과 추적이 목표 수준에 도달
- 필요한 257개 산출물 revision이 상태·완료 기준을 충족
- 하나의 불변 후보로 승인된 279개 시험과 실제 기기·현장·접근성·보안·개인정보·AI 검증 완료
- 5개 Gate가 실제 증거로 `CLOSED`, `waived=false`
- TST-22·REL-02의 권한 있는 출시 적격 승인
- 실제 배포·canary·smoke·rollback·기술자료 수령 증거
- 필요한 운영 안정화·복원·비용·권한·데이터 처리 사건 완료
- 운영 책임 이관 또는 승인된 종료와 최종 인수 기록
- 열린 P0/P1 결함, 미해결 필수 위험, 미소비 blocker가 없음

동적 필수 Goal이 새로 생기면 Master 완료 후보에서 제외하고 그 Goal을 먼저 처리한다.

## 질문·중단 조건

기존 정책을 다시 묻지 않는다. 저장소 근거와 보수적 기본값으로 해결할 수 없고 다음 중 하나가 실제로 필요한 경우만 질문한다.

- 새로운 규범·제품방향 결정
- 실제 기기·참여자·독립검토자
- 비밀정보·유료자원·프로덕션 권한
- 외부 통지·실제 삭제·출시·인수·이관·종료 승인

질문할 때는 새 결정, 중복 질문이 아닌 이유, 권고안, 독립적으로 계속할 작업, 답변 뒤 재개 Goal을 한 번에 제시한다.

한 branch만 막히면 다른 ready branch를 진행한다. 모든 필수 경로가 막혔을 때만 전체 실행을 대기한다.

사용자 결정 질문과 외부 action/evidence 요청은 분리한다. 저장소 내부 ready leaf가 있는 동안 새 요청을 만들지 않고, 동일 request key는 한 번만 발행한다. 저장소 자율 범위를 모두 완료했지만 외부 action/evidence만 남으면 `COMPLETE_AWAITING_EXTERNAL` 파생 milestone과 하나의 실행 패킷을 남기되 Master·package·출시를 완료로 표시하지 않는다. 내부 독립검토는 외부 법률·전문가·실사용자·실기기 검토를 대신하지 않는다.

## 완료 후 인계

매 전환에서 다음을 남긴다.

- 변경 파일·검증 명령·실제 결과
- 주장 가능한 내부 완료수준과 아직 주장하지 않는 정식 결과
- 갱신한 Draft·Active·append-only 증거
- Gap·Backlog·산출물 successor
- ready·blocked·focus 집합과 선택 근거
- 남은 Gate·외부 조건·잔여 위험
- checkpoint event, daylog, local-memory

다음 세션은 고정된 “다음 단계 문서”가 아니라 checkpoint의 `focus_goal_id`와 `ready_frontier_goal_ids`를 읽어 재개한다.
