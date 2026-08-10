+++
schema_version = "1.0"
goal_id = "WS-GOAL-PHASE-A"
goal_kind = "PHASE"
document_version = "1.0.0"
phase_id = "A"
parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-V1"
sequence = 10
initial_status = "READY"
target_completion_level = "IMPLEMENTATION_READY"
next_goal_id = "WS-GOAL-PHASE-B"
return_goal_id = ""
work_item_id = ""
dependencies = []
child_goal_ids = ["WS-GOAL-A-EPIC-01", "WS-GOAL-A-EPIC-02", "WS-GOAL-A-EPIC-03", "WS-GOAL-A-EPIC-04", "WS-GOAL-A-EPIC-05", "WS-GOAL-A-EPIC-06", "WS-GOAL-A-EPIC-07", "WS-GOAL-A-EPIC-08", "WS-GOAL-A-EPIC-10", "WS-GOAL-A-EPIC-09", "WS-GOAL-A-EPIC-11"]
source_policy_ids = []
gap_ids = []
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# Phase A Goal — 정책 정합 구현 준비

## 목표

EPIC-01~11의 코드·인터페이스·설정·내부 검증·추적자료를 승인 정책에 맞춰 `IMPLEMENTATION_READY`로 만든다. 현재는 EPIC-01이 이 수준에 도달했고 EPIC-02가 `IN_PROGRESS`이며, 다음 Work Item은 FP-018/GAP-027이다.

## 정본 입력

- 정책: `POLICY_BASELINE`
- 승인 상태: `ARTIFACT_APPLICATION_RECEIPT`, `ARTIFACT_REGISTER`
- 추적: `REQUIREMENTS_TRACEABILITY`, `DESIGN_TRACEABILITY`, `PLANNED_TEST_CASES`
- 현재 진단: `IMPLEMENTATION_GAP`, `IMPLEMENTATION_BACKLOG`
- 현재 작업 포인터: 체크포인트 `current_work`, `goal_execution`

파일명 안의 날짜를 현재성 근거로 추측하지 않고 canonical role을 사용한다.

## 범위와 제외

포함:

- EPIC-01~11 내부 구현
- 단위·구성요소·계약·정적·회귀검사
- 구현에 따라 변하는 Draft와 Active 자료
- append-only 구현 기록과 Gap·Backlog successor

제외:

- 279개 정식 시험의 PASS 판정
- 실제 기기·현장·참여자 결과
- 5개 gate 종료
- 출시 승인과 프로덕션 배포

## 단계별 실행

아래 순서는 정상 상황의 기본 우선순위다.

```text
EPIC-01(완료) → EPIC-02 → EPIC-03 → EPIC-04 → EPIC-05 → EPIC-06
→ EPIC-07 → EPIC-08 → EPIC-10 → EPIC-09 → EPIC-11
```

숫자 순서와 달리 `EPIC-08 → EPIC-10 → EPIC-09`다. 각 EPIC은 자신의 Goal 문서에 적힌 정책 순서대로 Work Item을 처리한다.

현재 EPIC이 `AWAITING_USER` 또는 `AWAITING_EXTERNAL`이고 의존성이 모두 완료된 후속 EPIC이 있으면 그 독립 branch를 임시로 진행할 수 있다. 이때 우회 이유, 막힌 Goal, 반드시 돌아올 Goal을 체크포인트에 기록하며 미완료 의존성은 건너뛰지 않는다.

Work Item 완료 뒤에는 바로 다음 고정 파일을 사용하지 않는다. 먼저 새 Gap·Backlog successor를 만들고 최신 Backlog의 `next_single_action`을 확인한다. 저장소 내부 구현이 목표수준에 도달해 정식·외부 증거만 남은 정책은 반복 선택하지 않고 EPIC-12로 이관한다. EPIC의 내부 완료수준과 내부 잔여작업을 함께 확인해 다음 Work Item Goal을 생성하며, 이 과정은 사용자 승인 없이 수행한다.

Phase A Work Item의 `target_completion_level`은 정책 정합성을 다시 평가하는 행동이면 `INTERNAL_POLICY_CONFORMANCE_REASSESSED`, 구현 준비를 닫는 행동이면 `IMPLEMENTATION_READY` 중 하나만 사용한다. Phase A의 다른 목표 문자열이나 부모 Phase 목표를 Work Item에 복사하지 않는다.

정책/Gap pair는 EPIC front matter의 두 배열을 위치별로 zip하지 않는다. canonical `IMPLEMENTATION_GAP.assessments`의 각 `source_policy_id → gap_id` mapping을 읽고, EPIC 소유 정책마다 mapping이 지정한 pair 하나를 Work Item 하나에 배정한다.

모든 Phase A Work Item은 source Backlog나 Phase plan을 계보 입력으로만 사용한다. 완료할 때는 `WORK_ITEM_COMPLETION::<goal_id>` role의 `WORK_ITEM_EXECUTION_RECEIPT` 하나가 Goal 파일 SHA-256, Work Item ID, 위 target enum, 정확한 정책/Gap pair, 최신 실행 시작 event SHA-256, 실행 시간과 `IMPLEMENTATION_RECORD`·`VERIFICATION_RESULT`·`SUCCESSOR_TRACE` typed JSON을 결속해야 한다. 실행 구간은 시작 event `occurred_at`보다 이르지 않고 receipt의 완료·생성 시각은 `GOAL_COMPLETED.occurred_at`보다 늦지 않아야 한다. 이 binding snapshot과 완료 증거 집합이 `GOAL_COMPLETED` event에 정확히 반영되기 전에는 `COMPLETE_AT_TARGET`으로 올리지 않는다.

Phase A가 기록하는 모든 transition event도 timezone 포함 `occurred_at`을 가지며, `occurred_on`은 그 시각과 같은 날짜이고 event 순서대로 `occurred_at`이 엄격히 증가해야 한다.

## 검증

각 EPIC은 다음을 확인한다.

- 해당 정책·Gap을 중복이나 누락 없이 다룸
- 정책과 반대되는 현재 동작을 재현하거나 명확히 구분
- 최소 구현과 내부 인수조건 통과
- 영향받는 요구·설계·모듈·예정 시험 연결 갱신
- 구현 파일과 검증 결과 SHA-256 기록
- 실제로 하지 않은 시험과 gate는 `NOT_RUN`
- 다음 EPIC의 dependency 충족

## 완료 기준

EPIC-01~11이 모두 다음 조건을 만족해야 한다.

- 각 Goal이 `COMPLETE_AT_TARGET/IMPLEMENTATION_READY`
- P0/P1 정책 충돌·누락에 대한 저장소 내부 구현이 완료됨
- 관련 내부 검증이 통과함
- 각 완료 Work Item에 canonical `WORK_ITEM_COMPLETION::<goal_id>` receipt가 정확히 하나 있고 source plan만으로 완료한 항목이 없음
- 정식 검증으로 넘길 항목이 EPIC-12에 명시적으로 연결됨
- Active 원장과 최신 Gap·Backlog가 현재 사실을 반영함
- 정식 시험 279개·실기기·5개 gate를 완료했다고 과장하지 않음

## 질문·중단 조건

Master Goal의 질문·중단 정책을 상속한다. 보존기간·동의·지원 사용자·자동신고·안전정지 같은 승인 정책을 기술 편의 때문에 바꾸지 않는다. 외부 측정은 Phase A 완료를 서로 기다리게 하지 않고 EPIC-12로 이관하되, 외부값이 없으면 안전한 구현 자체가 불가능한 경우에만 해당 branch를 중단한다.

## 완료 후 인계

Phase A exit를 기록하고 체크포인트에서 Phase A를 `COMPLETE_AT_TARGET`, Phase B를 `READY`로 전환한다. 그 뒤 사용자에게 재승인을 묻지 않고 [`20-phase-b-formal-verification.md`](20-phase-b-formal-verification.md)를 읽어 entry gate를 평가한다.
