+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02-FP-014-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 16
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-FP014-PERMISSION-DENIAL-REVOCATION"
start_requires = ["WS-GOAL-EPIC-02-FP-015-R001"]
completion_requires = ["WS-GOAL-EPIC-02-FP-015-R001"]
child_goal_ids = []
source_policy_ids = ["FP-014"]
gap_ids = ["GAP-023"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260725-r017.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260725-017"
materialized_from_sha256 = "0ca4724ffad40891503c7df1578e4bbb274991276ef852d1dd3b74ca3011dc35"
predecessor_goal_id = "WS-GOAL-EPIC-02-FP-015-R001"
predecessor_goal_content_sha256 = "9b5374de0e8a95140ec83b5f70bfe0c85cc0353c474c93ccdf4dbe9d27a9a0f3"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++

# Work Item Goal - 권한 거부·철회 시 기능별 처리

## 목표

카메라·정확한 위치·마이크 권한마다 허용되는 기능과 중지되는 기능을 분리한다.
권한 거부, 일시 허용 만료 또는 설정 변경 뒤에는 가입·동의·권한·필수 기능
상태를 다시 검사하고, 사용자가 명시적으로 재개하기 전에는 보행·수집·전송을
시작하지 않는다.

## 정본 입력

- 정책: `FP-014`, 영역 `FA-05`
- 요구사항: `RQ-FP-014-001`
- Gap: `GAP-023`, 현재 `PARTIAL`, P0
- 설계: `DES-01·04·08·14·15·16·18·19·21·22`
- 예정 시험: `TC-FP-014-01`~`03`, 모두 `NOT_RUN`
- 구현 근거: `EVD-ANDROID-PERMISSION`
- Backlog r017: 실행순서 16, `PLANNED_NEXT_WITHIN_EPIC`
- 선행 Goal: `WS-GOAL-EPIC-02-FP-015-R001`
- 질문·결정 참조: `Q-SES-004·005·006·008`, `Q-RES-008`, `FUP-014`,
  `DIR-008`, `EXT-003`, `FP-014#FP-014-DETAIL-01`~`03`

정책 적용 규칙은 다음과 같다.

1. 카메라·정확한 위치·마이크를 보행 핵심 권한으로 관리한다.
2. 방향·움직임·거리 기능은 운영체제 권한과 별도로 실제 사용 가능 여부를 검사한다.
3. 이미 허용된 권한의 확인창을 반복하지 않고 기능 사용 직전에 실제 상태를 확인한다.
4. 거부·철회된 권한에 의존하는 기능만 멈추고 독립 기능은 유지한다.
5. 남은 기능만으로 안전하지 않으면 이유를 알리고 보행기능을 안전정지한다.
6. 설정에서 돌아오면 가입·동의·권한·필수 기능을 처음부터 다시 확인한다.
7. 사용자가 멈춘 기능을 다시 쓸 때 그 기능에 필요한 권한만 요청한다.
8. 차단 상태에서는 보행·수집·전송을 시작하지 않는다.

## 범위와 제외

포함:

- Android 권한별 기능 가용성 및 안전정지 상태
- 거부·철회·일시 허용 만료·설정 변경 감지와 전체 상태 재검사
- 사용자 명시적 재개 전 보행·수집·전송 차단
- TalkBack으로 읽고 조작 가능한 거부 항목·이유·확인·설정 이동
- 관련 Android 단위·정적·구성요소 회귀와 추적 근거

제외:

- 실제 사용자·TalkBack 사용자·Android 기기 시험
- 승인된 환경에서의 `TC-FP-014-01`~`03` 정식 실행
- Android 플랫폼 심사와 운영 권한 프로파일 승인
- 운영 배포, 외부 승인, Gate 종료와 출시 가능 판정

## 실행 절차

1. 현재 권한·보행·수집·전송 진입점과 설정 복귀 흐름을 조사한다.
2. 권한별 의존 기능, 독립 기능과 전체 안전정지 조건을 명시한다.
3. 거부·철회·일시 허용 만료·설정 변경 회귀를 fail-first로 추가한다.
4. 기능 사용 직전 실제 권한 상태 검사와 권한별 차단을 구현한다.
5. 설정 복귀 뒤 전체 상태 재검사와 명시적 재개 경계를 구현한다.
6. TalkBack 거부 안내와 확인·설정 이동 조작을 내부 검증한다.
7. 관련 Android 회귀와 정적 검사를 실행해 원출력 hash를 기록한다.
8. 구현·검증 근거와 Gap·Backlog successor를 확정한다.

## 검증

- TalkBack만으로 거부 항목과 이유를 듣고 확인 또는 설정 이동을 선택한다.
- 설정에서 권한을 다시 허용하고 돌아오면 모든 필수 기능을 재검사한다.
- 거부 창에서 뒤로가거나 앱을 닫아도 보행·수집·전송이 시작되지 않는다.
- 연결된 정식 시험은 승인된 환경에서 별도로 실행하며 현재 `NOT_RUN`이다.

## 완료 기준

- 권한별 의존 기능과 독립 기능의 시작·중지 경계가 구현됨
- 거부·철회·만료·설정 변경 뒤 실제 상태를 다시 검사함
- 사용자의 명시적 재개 전 보행·수집·전송이 fail-closed로 차단됨
- 남은 기능이 안전하지 않으면 이유와 안전정지 상태를 표시함
- 관련 내부 회귀와 구현·검증 hash가 기록됨
- `GAP-023`과 Backlog가 실제 근거에 맞게 재평가됨

내부 구현만으로 정식 시험, 실제 사용자·기기 검증, 플랫폼 승인, Gate 종료 또는
출시 가능을 주장하지 않는다.

## 질문·중단 조건

현재 정본 정책 질문은 없다. 다음 경우에만 Master 정책에 따라 중단한다.

- 유효한 정책끼리 실제로 충돌함
- 같은 파일의 사용자 변경과 안전하게 병합할 수 없음
- 새 외부 승인 없이는 보수적 내부 경계를 결정할 수 없음
- 실제 사용자·기기 근거 없이 내부 완료와 외부 완료를 분리할 수 없음

## 완료 후 인계

1. FP015 완료 근거와 r017 Gap·Backlog를 보존한다.
2. `GAP-023` 재평가와 구현·검증·successor 근거를 남긴다.
3. 정식 시험·실사용자·실기기·플랫폼·출시 근거를 외부 검증으로 분리한다.
4. 완료 뒤 EPIC-02의 다음 deterministic frontier를 다시 계산한다.
