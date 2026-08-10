+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-03-FP-046-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-03"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 21
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-03-FP046-CONSENT-WITHDRAWAL-DELETION"
start_requires = ["WS-GOAL-EPIC-03-FP-008-R001"]
completion_requires = ["WS-GOAL-EPIC-03-FP-008-R001"]
child_goal_ids = []
source_policy_ids = ["FP-046"]
gap_ids = ["GAP-055"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260809-r024.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260809-024"
materialized_from_sha256 = "f2c860d8d5199bdd18da62fd44bf0e161a7f6b9933bde7eebed86afb9fb8b55d"
predecessor_goal_id = "WS-GOAL-EPIC-03-FP-008-R001"
predecessor_goal_content_sha256 = "2654fe5f595aefaecf974a4de70bad7946c2385cba9716200f2d2f49d849dd8e"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++
# FP-046 동의·철회·전체 개인정보 삭제

## 목표

서비스 제공 동의와 선택적 모델 개선 동의를 분리하고, 철회 또는 전체 개인정보 삭제 요청이 들어오면 새 수집·전송을 중단한 뒤 휴대전화·서버·가공본·백업의 처리 상태와 원본 없는 삭제 영수증을 일관되게 추적한다.

## 정책 기준

- 서비스 제공에 필요한 자료와 선택적 모델 개선자료의 목적·동의 상태를 분리한다.
- 동의가 없거나 철회되면 해당 원본의 새 수집과 전송을 시작하지 않거나 즉시 중단한다.
- 전체 삭제 요청은 휴대전화 24시간, 서버 원본·검역본·복사본 7일, 학습자료·라벨·가공본 30일, 백업 최대 35일의 처리 경계를 구분한다.
- 모든 저장 위치가 끝나기 전에는 전체 완료로 표시하지 않고 부분 실패와 재시도 상태를 보존한다.
- 백업 복원 시 삭제 완료 표식을 먼저 재적용해 삭제 대상 자료가 서비스에 되살아나지 않게 한다.
- 삭제 영수증은 원본 없이 식별값 지문·처리시각·결과만 3년 보존한다.

## 구현 범위

- Android 사용자 앱의 통합 동의·철회·계정 삭제 fail-closed 경계
- Android Gateway의 동의 revision·삭제 generation·재시도 가능한 개인정보 권리 원장
- backend 신고 저장·보존·삭제 정합성과 전용 PostgreSQL 내부 검증
- 휴대전화 대기자료·서버 원본·가공본·백업 만료 대기 상태의 분리된 처리 결과
- 정책→요구→설계→코드→내부 시험→GAP-055 successor 추적

## 성공 기준

1. 모델 개선 선택동의를 거부해도 서비스 제공 흐름은 유지되고 선택 학습자료는 수집되지 않는다.
2. 철회·전체 삭제 뒤 새 수집과 전송이 즉시 fail-closed로 중단된다.
3. 저장 위치별 삭제 상태와 기한, 부분 실패와 재시도가 서로 덮어쓰지 않고 추적된다.
4. 백업 복원 뒤 삭제 표식이 먼저 적용돼 삭제 대상 자료가 다시 활성화되지 않는다.
5. 내부 구현·회귀·저장/보존 검증과 GAP-055 재평가가 정본 해시로 결속된다.

## 계획 검증

- `TC-FP-046-01`: 모델 개선 선택동의 거부와 서비스 제공 분리
- `TC-FP-046-02`: 저장 위치별 전체 삭제 상태·기한·부분 실패
- `TC-FP-046-03`: 백업 복원 전 삭제 완료 표식 재적용
- `TC-FP-046-04`: 보호자 동의 철회와 계정·수집 차단
- `TC-FP-046-05`: 승인되지 않은 제공 대상·저장지역 차단

## 제외 범위

- 관리자 앱, Web/PWA와 과거 제출 후보의 구현·시험·근거
- 실제 기기·사용자·외부 업체·운영 데이터베이스·배포 시험
- 실제 개인정보 삭제, 운영 백업 복원 또는 외부 권리요청 수행
- 독립 외부 개인정보 검토와 정식 시험
- 출시·배포 적격성 또는 Gate 종료

## 완료 경계

이 Goal의 목표 완료 수준은 `INTERNAL_POLICY_CONFORMANCE_REASSESSED`다. 현행 Android 사용자 앱·Android Gateway·backend와 root control의 저장소 내부 구현·자동 검증·증거 사슬까지만 완료로 간주한다. 실제 기기·외부 사건·정식 시험·배포·출시는 계속 미실행 상태로 유지한다.
