+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02-FP-012-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 18
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-FP012-MULTI-DEVICE-SESSION-LEDGER"
start_requires = ["WS-GOAL-EPIC-02-FP-016-R001"]
completion_requires = ["WS-GOAL-EPIC-02-FP-016-R001"]
child_goal_ids = []
source_policy_ids = ["FP-012"]
gap_ids = ["GAP-021"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r019.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-019"
materialized_from_sha256 = "0c95156951ffcf7004fc92fbd6ca2cffe04febcea86ac457195fb10551ee6e62"
predecessor_goal_id = "WS-GOAL-EPIC-02-FP-016-R001"
predecessor_goal_content_sha256 = "d7fe0204199c22acdc3bc6b748cde67b2b3b6227b726ce6a50ddf2c3f019af81"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++
# FP-012 여러 기기 동시 로그인과 단일 활성 보행 원장

## 목표

계정별 기기·세션·보행 원장을 서버 권한으로 관리한다. 여러 기기의 로그인 상태는 유지하되 한 계정에는 동시에 하나의 활성 보행 권한만 존재하도록 하고, 충돌을 조용히 덮어쓰지 않고 사용자가 음성 확인으로 이전 보행 종료와 권한 이전을 선택하게 한다.

## 정책 기준

- 여러 휴대폰의 로그인 세션은 함께 유지할 수 있다.
- 한 계정에는 한 시점에 하나의 활성 보행만 허용한다.
- 새 기기에서 보행을 시작하면 기존 활성 보행 종료 여부를 음성으로 확인한다.
- 계정 ID, 기기 ID, 보행 ID, 발생 시각을 구분해 원장에 남긴다.
- 서버는 단일 보행 권한 또는 lease를 발급하며, 승인된 종료나 만료 전에는 두 번째 보행을 차단한다.
- 충돌 보고와 보행 데이터는 덮어쓰거나 중복 저장하지 않는다.

## 구현 범위

- 계정별 기기 세션과 활성 보행 lease 원장
- 두 번째 기기의 시작 요청에 대한 서버 측 원자적 차단
- 음성 확인을 거친 이전 보행 종료 및 새 기기 권한 이전
- 오프라인 상태였던 이전 기기의 종료·만료 반영
- 계정·기기·보행 식별자와 시각을 포함한 감사 가능한 전이 기록
- 중복 요청과 재시도에도 단일 활성 보행 불변식을 유지하는 검증

## 성공 기준

1. 두 기기가 로그인된 상태를 유지하면서도 활성 보행은 하나만 존재한다.
2. 사용자 확인 전 두 번째 기기의 보행 시작은 차단된다.
3. 승인된 이전 후에는 새 기기의 보행만 활성 상태다.
4. 중복 요청이나 충돌로 보고·보행 데이터가 덮어써지거나 중복 생성되지 않는다.
5. 오프라인이었던 이전 기기는 재연결 시 종료 또는 승인된 lease 만료를 준수한다.
6. 내부 구현·정적·단위 검증 증거와 GAP 재평가 산출물이 정본 해시로 결속된다.

## 계획 검증

- `TC-FP-012-01`: 두 기기 로그인 상태 보존과 단일 활성 보행
- `TC-FP-012-02`: 확인 없는 두 번째 시작 차단
- `TC-FP-012-03`: 승인된 보행 권한 이전의 원자성
- `TC-FP-012-04`: 중복·재시도·충돌 데이터 보존
- `TC-FP-012-05`: 오프라인 이전 기기의 종료·만료 수렴

## 제외 범위

- 정식 279개 테스트의 완료 선언
- 실제 사용자·보호자 음성 확인 검증
- 실제 Android 다중 기기·불안정 네트워크 현장 검증
- 운영 서버 lease 정책 승인과 운영 배포
- 법무·개인정보·접근성 외부 독립 검증

## 완료 경계

이 Goal의 목표 완료 수준은 `INTERNAL_POLICY_CONFORMANCE_REASSESSED`다. 내부 코드와 자동 검증, 증거 사슬, `GAP-021` 재평가까지만 완료로 간주하며 외부 검증과 release 적격성은 별도 경계를 유지한다.
