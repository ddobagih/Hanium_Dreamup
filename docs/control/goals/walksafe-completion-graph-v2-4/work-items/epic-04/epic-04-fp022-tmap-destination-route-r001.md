+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-04-FP-022-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-04"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 24
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "WS-GOAL-EPIC-04-FP-022-R001"
start_requires = ["WS-GOAL-EPIC-02"]
completion_requires = ["WS-GOAL-EPIC-02"]
child_goal_ids = []
source_policy_ids = ["FP-022"]
gap_ids = ["GAP-031"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260813-r027.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260813-027"
materialized_from_sha256 = "64e91046639ba44600d3584b5258d15f26b9c9161b03f25a4a662903d29e2f45"
predecessor_goal_id = "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
predecessor_goal_content_sha256 = "234a224883779208ba7878a9865076083bb9cfd205dfdb7a760b043f7af6b16d"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++
# FP-022 TMAP 목적지·큰 경로와 도착 확인

## 목표

사용자가 음성으로 목적지를 찾고 구분 가능한 TMAP 후보 중 하나를 선택한 뒤, 계단을 피한 보행 경로의 큰 방향을 따라가게 한다. 남은 거리와 도착 후보는 신뢰할 수 있는 GPS와 저장된 TMAP 경로를 주 기준으로 계산하고 보폭은 진행량 검증에만 보조로 사용하며, 사용자가 확인하기 전에는 도착을 확정하거나 길안내를 종료하지 않는다.

## 정책 기준

- TMAP 비밀키는 서버에서 보호하고 Android 사용자 앱에 포함하지 않는다.
- 목적지 검색 후보는 구분 가능한 이름·주소·거리 정보와 함께 세 개씩 읽고, 사용자가 더 듣겠다고 할 때 다음 후보를 제공한다.
- 계단 회피와 보행 접근성을 단순 최단거리보다 우선한다.
- 최종 남은 거리는 신뢰할 수 있는 GPS와 저장된 TMAP 경로를 기준으로 계산한다.
- 보폭은 남은 거리·도착·경로 이탈 계산의 보조 입력으로만 사용하며 위치나 방향을 대신 결정하지 않는다.
- GPS를 신뢰할 수 없으면 보폭으로 판단을 대신하지 않고 방향 안내를 일시중지한 뒤 원인과 중단 상태를 알린다.
- GPS·경로 끝·보폭 진행량이 도착 가능성을 나타내도 사용자 확인 전에는 도착을 확정하거나 길안내를 자동 종료하지 않는다.
- 경로 이탈 또는 불확실 상태에서 사용자 선택 없이 새 TMAP 경로를 자동 요청하지 않는다.

## 구현 범위

- Android 사용자 앱의 음성 목적지 검색, 세 개 단위 후보 안내와 사용자 선택
- 서버가 보호하는 TMAP POI·보행 경로 계약과 계단 회피 우선순위
- 저장된 TMAP 경로와 신뢰 GPS를 기준으로 한 남은 거리·경로 끝 projection
- 평균·개인 보폭을 진행량 보조 증거로만 사용하는 책임 분리
- GPS 불신·권한 거부·지도 오류에서 방향 안내 중지와 fail-closed 상태
- 도착 후보 제시, 사용자 확인·거절과 확인 전 자동 종료 차단
- 사용자 결정 전 자동 TMAP 재탐색·재요청 차단
- 정책→RQ-FP-022-001→설계→코드→내부 시험→GAP-031 successor 추적

## 성공 기준

1. 같은 이름의 목적지 후보를 세 개씩 구분 가능하게 안내하고 사용자의 명시적 선택만 적용한다.
2. 계단이 포함된 최단경로보다 접근 가능한 계단 회피 보행 경로를 우선한다.
3. GPS·위치 권한·TMAP을 신뢰할 수 없을 때 보폭으로 위치나 방향을 대신 판단하지 않고 방향 안내를 중지한다.
4. 남은 거리와 경로 끝은 GPS와 저장 TMAP 경로가 권위를 가지며 보폭은 진행량 검증만 보조한다.
5. 도착 후보는 사용자가 확인하거나 거절할 수 있고 확인 전에는 도착 확정·길안내 종료가 발생하지 않는다.
6. 이탈·불확실 상태에서 사용자 결정 전에 새 경로를 자동 요청하지 않는다.
7. 내부 구현·회귀 증거와 GAP-031 재평가가 정본 해시로 결속된다.

## 계획 검증

- `TC-FP-022-01`: 동일 이름 장소의 후보 세 개를 구분 가능한 정보와 함께 읽고 음성으로 선택
- `TC-FP-022-02`: 계단 포함 최단경로보다 계단 회피 보행 경로 우선
- `TC-FP-022-03`: 위치 권한 거부·부정확한 GPS·TMAP 오류에서 잘못된 경로 없이 원인 안내와 중지
- `TC-FP-022-04`: 목적지 근처에서도 사용자 확인 전 도착 미확정·길안내 미종료

## 제외 범위

- FP-023의 경로 암호화 보존과 이탈 후 전체 10단계 사용자 선택 흐름
- 실제 TMAP 비밀키 생성·등록·사용과 운영 외부 지도서비스 호출
- 실제 Android 기기·사용자·현장 GPS·접근성 검증
- 정식 시험·독립 외부 검토·프로덕션 배포·출시 Gate 종료
- 자동검사 결과를 실제 기기·외부·정식·배포·출시 증거로 승격하는 행위

## 완료 경계

목표 수준은 `INTERNAL_POLICY_CONFORMANCE_REASSESSED`다. 저장소 내부 코드·인터페이스·자동 검증·GAP-031 successor까지만 완료로 간주한다. 실제 기기·사용자·현장·외부 TMAP·독립 검토·정식 시험·배포·출시는 계속 `NOT_RUN` 또는 `NOT_ELIGIBLE`로 유지하며, 이 Goal과 시작 gate는 해당 범위에 완료 credit을 부여하지 않는다.
