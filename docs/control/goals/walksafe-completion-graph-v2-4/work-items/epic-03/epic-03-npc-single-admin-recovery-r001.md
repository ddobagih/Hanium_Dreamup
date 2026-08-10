+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-03"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 22
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-03-NPC-SINGLE-ADMIN-RECOVERY"
start_requires = ["WS-GOAL-EPIC-03-FP-046-R001"]
completion_requires = ["WS-GOAL-EPIC-03-FP-046-R001"]
child_goal_ids = []
source_policy_ids = ["NPC-SINGLE-ADMIN-RECOVERY"]
gap_ids = ["GAP-008"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260810-r025.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260810-025"
materialized_from_sha256 = "346fadca1cc9fea3f3d0a95a7f4beed061116d2d258080ed3105c50feeeaa5da"
predecessor_goal_id = "WS-GOAL-EPIC-03-FP-046-R001"
predecessor_goal_content_sha256 = "f8f1fc9e9b8c1eaabe543cd64130b5a6dfa3b908b318033cb5969d4a724e8d79"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++
# NPC-SINGLE-ADMIN-RECOVERY 한 명의 관리자와 계정 복구

## 목표

관리자와 최종 승인자를 한 명으로 유지하면서, 관리자 휴대전화 분실이나 계정 접근 상실 때 휴대전화 밖의 복구수단, 원격 세션 폐기, 고위험 작업 동결과 감사로 통제권을 안전하게 복구한다.

## 정책 기준

- 관리자 계정은 비밀번호 외 추가 인증 또는 패스키를 사용하고 공용·숨은 우회 비밀번호를 두지 않는다.
- 복구코드나 보안키는 관리자 휴대전화 밖에 보관한다.
- 휴대전화 분실 시 별도 관리 경로에서 그 기기의 로그인 상태를 폐기한다.
- 서버키·앱 서명키·관리자 복구자료는 서로 분리해 암호화 백업한다.
- 접근을 잃으면 복구할 때까지 출시·권한 변경·데이터 삭제 같은 고위험 작업을 동결한다.
- 실제 사용자시험이나 배포 전에 휴대전화 분실 복구훈련을 한 번 수행한다.
- 모델·법률·보안·접근성의 독립 검토는 관리자 한 명이라는 이유로 제거하지 않는다.

## 구현 범위

- 별도 Android 관리자 앱의 추가 본인확인·패스키 경계
- 휴대전화 밖 복구자료를 표현하는 fail-closed 저장·상태 계약
- 분실 기기와 관리자 세션의 원격 폐기 및 재사용 거부
- 관리자 접근 상실 중 출시·권한 변경·데이터 삭제의 동결
- 복구·폐기·동결·재인증 결정의 감사로그
- 정책→RQ-NPC-SINGLE-ADMIN-RECOVERY-001→설계→코드→내부 시험→GAP-008 successor 추적

## 성공 기준

1. 비밀번호 단독 또는 숨은 우회 비밀번호로 관리자 기능에 접근할 수 없다.
2. 복구자료가 관리자 휴대전화 내부에만 남지 않는다.
3. 분실 기기 세션을 별도 관리 경로에서 폐기하고 기존 자격 재사용을 거부한다.
4. 복구 전에는 출시·권한 변경·데이터 삭제가 fail-closed로 동결된다.
5. 키·복구자료 분리와 모든 고위험 결정이 감사 가능하다.
6. 내부 구현·회귀 증거와 GAP-008 재평가가 정본 해시로 결속된다.

## 계획 검증

- `TC-NPC-SINGLE-ADMIN-RECOVERY-01`: 추가 인증, 외부 복구수단, 원격 폐기, 키 분리, 고위험 동결과 독립 검토 유지
- 정식 시험과 실제 분실 복구훈련은 이 내부 Goal에서 PASS로 승격하지 않는다.

## 제외 범위

- 실제 복구코드·보안키·앱 서명키·비밀값 생성 또는 사용
- 실제 관리자 기기 분실 복구훈련
- 실제 기기·운영 시스템·외부 보안·법률·접근성 검토
- 정식 시험·배포·출시와 `GATE-SINGLE-ADMIN-RECOVERY-DRILL` 종료

## 완료 경계

목표 수준은 `INTERNAL_POLICY_CONFORMANCE_REASSESSED`다. 저장소 내부 구현·자동 검증·GAP-008 successor까지만 완료로 간주하며 실제 복구훈련·외부 검토·정식 시험·배포·출시는 미실행 상태로 유지한다.
