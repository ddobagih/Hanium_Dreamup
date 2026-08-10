+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-03-FP-008-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-03"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 20
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-03-FP008-ADMIN-REVIEW-DELIVERY"
start_requires = ["WS-GOAL-EPIC-03-FP-048-R001"]
completion_requires = ["WS-GOAL-EPIC-03-FP-048-R001"]
child_goal_ids = []
source_policy_ids = ["FP-008"]
gap_ids = ["GAP-017"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260802-r023.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260802-023"
materialized_from_sha256 = "eabd987cff1086c6a45b9b6eee9166213727ab59d63cd5b7075da01e27651021"
predecessor_goal_id = "WS-GOAL-EPIC-03-FP-048-R001"
predecessor_goal_content_sha256 = "043b5a463914015a5918b885c3f69c242ece190358bd18ab1f884de46f553740"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++
# FP-008 Android 관리자 검수·기관 전달

## 목표

사용자 앱과 별개의 앱 식별값·서명·세션을 사용하는 Android 관리자 앱에서 지정 관리자가 로그인하고, 신고를 검수하며, 기관 전달 결과와 모든 조회·변경 이유를 감사 가능하게 기록한다.

## 정책 기준

- 관리자 앱은 사용자 앱과 별도 application ID, 배포·서명 경계와 세션을 사용한다.
- 등록된 관리자 기기와 지정 관리자 계정의 추가 인증을 모두 만족해야 관리자 기능에 접근할 수 있다.
- 신고 승인·반려·중복 판정에는 관리자, 시각, 이유를 기록한다.
- 기관 전달은 사람의 위치·사진·중복·개인정보 검수 뒤 수동으로 수행하며 접수번호와 상태를 다시 조회할 수 있어야 한다.
- 관리자 앱 장애는 관리자 경보와 내부 감사에 남기고 정상 동작 중인 사용자 보행 기능을 중지하지 않는다.
- 실시간 안전기능까지 영향을 받을 때만 사용자에게 이유를 알리고 해당 기능을 안전하게 중지한다.

## 구현 범위

- Android 사용자 앱과 관리자 앱의 application ID·권한·세션 분리
- 관리자 로그인, 등록 기기 확인과 추가 인증
- 신고 조회·승인·반려·중복 처리 및 사유 기록
- 기관 수동 전달, 접수번호·상태 기록과 재조회
- 관리자 조회·변경·전달 감사와 관리자 장애 격리
- 정책→요구→설계→코드→내부 시험→GAP-017 successor 추적

## 성공 기준

1. 등록하지 않은 기기나 일반 사용자 계정은 관리자 기능에 접근하지 못한다.
2. 승인·반려·중복 처리마다 이유와 관리자 정보가 남는다.
3. 기관 수동 전달 뒤 접수번호와 상태를 기록하고 다시 조회할 수 있다.
4. 관리자 앱 장애는 관리자 경보와 내부 기록에 남고 정상 사용자 보행 기능은 유지된다.
5. 내부 구현·회귀·보안 검토 증거와 GAP-017 재평가가 정본 해시로 결속된다.

## 계획 검증

- `TC-FP-008-01`: 미등록 기기·일반 사용자 관리자 접근 거부
- `TC-FP-008-02`: 승인·반려·중복 사유와 관리자 감사
- `TC-FP-008-03`: 기관 수동 전달 접수번호·상태 기록과 재조회
- `TC-FP-008-04`: 관리자 장애 격리와 조건부 사용자 안전정지

## 제외 범위

- Web/PWA와 과거 한이음 제출 후보의 구현·시험·근거
- 실제 기기·기관·외부 인증·운영 데이터베이스·배포 시험
- 실제 앱 서명키·비밀값·기관 접수 생성
- 독립 외부 보안·개인정보 검토와 정식 시험
- 출시·배포 적격성

## 완료 경계

이 Goal의 목표 완료 수준은 `INTERNAL_POLICY_CONFORMANCE_REASSESSED`다. 현행 Android 사용자 앱·관리자 앱·backend와 root control의 저장소 내부 구현·자동 검증·증거 사슬까지만 완료로 간주한다. 실제 기기·외부기관·배포·정식 시험은 `NOT_RUN`, 출시는 `NOT_ELIGIBLE`로 유지한다.
