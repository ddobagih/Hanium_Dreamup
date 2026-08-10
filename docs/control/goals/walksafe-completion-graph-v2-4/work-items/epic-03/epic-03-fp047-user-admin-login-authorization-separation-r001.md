+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-03-FP-047-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-03"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 19
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-03-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION"
start_requires = ["WS-GOAL-EPIC-02-FP-012-R001"]
completion_requires = ["WS-GOAL-EPIC-02-FP-012-R001"]
child_goal_ids = []
source_policy_ids = ["FP-047"]
gap_ids = ["GAP-056"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r020.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-020"
materialized_from_sha256 = "20b103c83f397894a7b9d5c9eedd696b0aa977b02552f49614876714b540dd6f"
predecessor_goal_id = "WS-GOAL-EPIC-02-FP-012-R001"
predecessor_goal_content_sha256 = "4f85f016683176ca38de6024cd7fea4c09b9cf65e8c3318d27b785120d634c3f"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++
# FP-047 사용자·관리자 로그인과 권한 분리

## 목표

운영 사용자 로그인과 관리자 로그인을 계정·세션·권한 경계에서 분리하고, 모든 보호 자원 접근을 서버 권한검사로 통제한다. 사용자 세션은 기기별 회전과 원격 폐기를 지원하며 관리자 세션은 추가 인증, 고위험 작업 재확인, 감사 가능한 전이 기록을 요구한다.

## 정책 기준

- 일반 사용자와 관리자는 별도 로그인 진입점, 세션 정책, 권한 범위를 사용한다.
- 역할 표시는 클라이언트 입력이 아니라 서버가 검증한 계정 권한에서 결정한다.
- 사용자 세션은 기기별 식별, 회전, 만료, 원격 폐기를 지원한다.
- 관리자 로그인과 고위험 작업에는 추가 인증과 재확인을 적용한다.
- 권한 거부, 세션 회전·폐기, 관리자 인증·재확인 결과를 감사로그에 남긴다.
- 복구 절차는 단일 관리자 계정에 의존하지 않도록 별도 운영 경계를 유지한다.

## 구현 범위

- 사용자·관리자 인증 진입점과 세션 저장소 분리
- 서버 기반 역할·소유권 검사와 기본 거부
- 기기별 회전 세션, 만료, 원격 폐기
- 관리자 추가 인증과 고위험 작업 재확인
- 인증·인가·세션 전이 감사로그
- 권한 상승, 세션 재사용, 폐기 후 접근에 대한 내부 회귀 검증

## 성공 기준

1. 사용자 세션으로 관리자 보호 자원에 접근할 수 없다.
2. 관리자 권한은 서버가 검증하며 클라이언트 역할 변조로 상승하지 않는다.
3. 세션 회전 또는 원격 폐기 후 이전 자격증명은 거부된다.
4. 관리자 로그인과 고위험 작업은 추가 인증·재확인 없이 진행되지 않는다.
5. 모든 인증·인가·세션 전이가 계정·기기·시각과 함께 감사 가능하다.
6. 내부 구현·정적·단위 검증 증거와 GAP-056 재평가 산출물이 정본 해시로 결속된다.

## 계획 검증

- `TC-FP-047-01`: 사용자·관리자 로그인 및 세션 경계 분리
- `TC-FP-047-02`: 서버 역할·소유권 검사와 권한 상승 차단
- `TC-FP-047-03`: 기기별 세션 회전·만료·원격 폐기
- `TC-FP-047-04`: 관리자 추가 인증과 고위험 작업 재확인
- `TC-FP-047-05`: 인증·인가·세션 감사로그 완전성

## 제외 범위

- `GATE-SINGLE-ADMIN-RECOVERY-DRILL`의 실제 운영 복구 훈련과 승인
- 정식 279개 테스트 완료 선언
- 실제 사용자·관리자·운영 환경 검증
- 외부 독립 보안·법무·개인정보·접근성 검토
- 운영 배포와 release 적격성

## 완료 경계

이 Goal의 목표 완료 수준은 `INTERNAL_POLICY_CONFORMANCE_REASSESSED`다. 내부 코드와 자동 검증, 증거 사슬, `GAP-056` 재평가까지만 완료로 간주한다. `GATE-SINGLE-ADMIN-RECOVERY-DRILL`은 시작 차단 조건이 아니라 release 경계이며, 실제 복구 훈련과 운영 승인이 없으면 release 적격성을 선언하지 않는다.
