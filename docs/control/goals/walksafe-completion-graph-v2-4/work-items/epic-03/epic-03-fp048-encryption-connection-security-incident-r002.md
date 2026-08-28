+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-03-FP-048-R002"
goal_kind = "WORK_ITEM"
document_version = "2.3.0"
parent_goal_id = "WS-GOAL-EPIC-03"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 23
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
start_requires = ["WS-GOAL-EPIC-03-FP-047-R001"]
completion_requires = ["WS-GOAL-EPIC-03-FP-047-R001"]
child_goal_ids = []
source_policy_ids = ["FP-048"]
gap_ids = ["GAP-057"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260825-r030.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260825-030"
materialized_from_sha256 = "e60301254dcdcdc18216e2640755f1726c5127d1e37bf520b167db34dd02ac83"
predecessor_goal_id = "WS-GOAL-EPIC-03-FP-046-R002"
predecessor_goal_content_sha256 = "4627c19b421f626323778fcdfd01cc2edbc36644c48c7d65c2b4847e698ac429"
supersedes_goal_id = "WS-GOAL-EPIC-03-FP-048-R001"
supersedes_goal_content_sha256 = "043b5a463914015a5918b885c3f69c242ece190358bd18ab1f884de46f553740"
reopen_reason = "CANONICAL_INPUT_CHANGED"
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++
# FP-048 Gateway 암호화 상태 회전 R002

## 목표

R030의 다음 단일 행동인 `FP-048 R002에서 7종 state rotation을 all-or-nothing으로 재개한다.`을 정확히 이어받아, Gateway 암호화 상태 7종의 lock/read/classify/schema preflight가 모두 성공한 뒤에만 기존 파일별 atomic replace 회전을 시작하도록 한다.

## 정책 기준

- 대상은 `short-session`, `field-long-session`, `field-walk-ledger`, `privacy-rights-ledger`, `privacy-deletion-v2`, `integrated-consent`, `server-capacity` 정확히 7종이다.
- 7개 파일의 lock/read/classify/schema preflight가 모두 끝나기 전에는 mutation을 0으로 유지한다.
- 하나라도 corrupt·unknown·lock·schema 실패면 7개 모두 mutation 0으로 fail-fast한다.

## 구현 범위

- 정확한 7종 파일 inventory와 schema/classification 계약
- 전 파일 lock/read/classify/schema 선행 preflight
- corrupt·unknown·lock·schema 실패 시 전 파일 mutation 0
- 모두 valid일 때 기존 파일별 atomic replace 회전
- 해당 상태 회전 집중 회귀와 GAP-057 내부 재평가

## 성공 기준

1. exact 7종 모두의 preflight 완료 전 mutation 수가 0이다.
2. 하나의 corrupt·unknown·lock·schema 실패도 7종 전체 mutation 수 0으로 종료된다.
3. 모두 valid일 때만 기존 파일별 atomic replace 경로가 시작된다.
4. 프로세스 crash 전체에 대한 cohort-atomic rollback은 완료 claim에 포함하지 않는다.
5. GAP-057은 저장소 내부 결과만 재평가하고 정식·실기기·외부·운영·출시 상태를 올리지 않는다.

## 계획 검증

- exact 7종 inventory와 정상 preflight 집중 회귀
- 각 corrupt·unknown·lock·schema fault의 전 파일 mutation 0 회귀
- 모두 valid일 때 기존 파일별 atomic replace 진입 회귀

## 제외 범위

- 실제 운영 키·비밀값·인증서 발급이나 회전
- Nginx·프로세스 재시작, capacity collector, deletion worker 변경
- readiness·health·telemetry, backup/restore 구현·운영
- crash-atomic 7파일 cohort rollback claim
- 정식 시험, 실기기, 외부 보안·법률 검토, 배포와 출시 승인

## 완료 경계

목표 완료 수준은 `INTERNAL_POLICY_CONFORMANCE_REASSESSED`이며 저장소 내부 구현·자동 검증·GAP-057 재평가까지만 포함한다. 외부·운영·배포·출시 credit은 `NOT_RUN` 또는 `0`을 유지한다.
