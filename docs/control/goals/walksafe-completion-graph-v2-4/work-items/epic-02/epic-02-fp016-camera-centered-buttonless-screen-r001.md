+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02-FP-016-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 17
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-FP016-CAMERA-CENTERED-BUTTONLESS-SCREEN"
start_requires = ["WS-GOAL-EPIC-02-FP-014-R001"]
completion_requires = ["WS-GOAL-EPIC-02-FP-014-R001"]
child_goal_ids = []
source_policy_ids = ["FP-016"]
gap_ids = ["GAP-025"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r018.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-018"
materialized_from_sha256 = "667ae83090e73d39e8ea371f2543e560a5d8465505dad323c84e901f369e31b0"
predecessor_goal_id = "WS-GOAL-EPIC-02-FP-014-R001"
predecessor_goal_content_sha256 = "8790f46e43d410c1fb4dc5c6c0b7013fa2dfc87b511a249068df4bdddbc2c06e"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++

# Work Item Goal - 로그인 뒤 카메라 중심 무버튼 화면

## 목표

로그인과 필수 확인을 마친 정상 보행 화면을 카메라 영상과 읽기 전용 안전상태
중심으로 구성한다. 정식 배포 화면에서는 개발 입력과 일반 조작 버튼을 만들지
않고, 뒤로가기는 보행을 즉시 일시중지한 뒤 접근 가능한 종료 확인으로 연결한다.

## 정본 입력

- 정책: `FP-016`, 조항 `FP-016-POLICY-001`
- 요구사항: `RQ-FP-016-001`
- Gap: `GAP-025`, 현재 `CONFLICTING`, P1
- 설계: `DES-01·04·08·14·15·16·17·18`
- 예정 시험: `TC-FP-016-01`~`04`, 모두 `NOT_RUN`
- Backlog r018: 실행순서 17, `IMPLEMENTATION_READY`
- 선행 Goal: `WS-GOAL-EPIC-02-FP-014-R001`

정책 적용 규칙은 다음과 같다.

1. 정상 보행 화면은 카메라 영상과 읽기 전용 안전상태를 주 화면으로 제공한다.
2. 개발용 Gateway URL·token·위경도 입력과 개발 실행 버튼은 정식 배포 UI 계층에 생성하지 않는다.
3. 정상 보행 화면에는 일반 조작 버튼을 두지 않고 필요한 제어는 접근 가능한 시스템 흐름으로 분리한다.
4. 뒤로가기를 받는 즉시 진행 중 보행을 사용자 일시중지 상태로 전환한 뒤 종료 확인을 표시한다.
5. 종료 취소 뒤에도 자동 재개하지 않으며 기존의 명시적 재개 계약을 유지한다.
6. 카메라 또는 필수 음성 기능이 지속 실패하면 이유를 알리고 안전정지한다.
7. 고빈도 상태 변화는 읽기 방해를 막고 위험 알림만 제한된 빈도로 즉시 알린다.

## 범위와 제외

포함:

- Android 정상 보행 화면의 카메라·읽기 전용 안전상태 구성
- 정식 배포 UI에서 개발 입력·버튼 비생성
- 뒤로가기 즉시 일시중지, 접근 가능한 종료 확인과 취소 후 정지 유지
- 카메라·필수 음성 실패 시 안전정지
- TalkBack live-region 및 위험 알림 빈도 회귀
- 관련 Android 단위·정적·구성요소 회귀와 추적 근거

제외:

- 실제 시각장애 사용자·TalkBack 사용자·Android 기기·현장 시험
- 승인된 환경에서의 `TC-FP-016-01`~`04` 정식 실행
- Android 플랫폼 심사와 외부 승인
- 운영 배포, release gate 종료와 출시 가능 판정

## 실행 절차

1. 로그인 뒤 ACTIVE 화면과 개발 입력·일반 버튼 생성 경로를 조사한다.
2. 카메라·안전상태와 개발 제어를 분리하는 fail-first 회귀를 추가한다.
3. 정식 배포 UI에서 개발 입력과 일반 조작 버튼을 생성하지 않도록 구현한다.
4. 뒤로가기 즉시 일시중지와 접근 가능한 종료 확인을 구현한다.
5. 취소 뒤 정지 유지와 명시적 재개 경계를 FP014 계약과 함께 검증한다.
6. 카메라·필수 음성 실패 안전정지와 알림 빈도 정책을 내부 검증한다.
7. 관련 Android 회귀와 정적 검사를 실행해 원출력 hash를 기록한다.
8. 구현·검증 근거와 Gap·Backlog successor를 확정한다.

## 검증

- ACTIVE 화면의 정식 배포 UI 계층에 개발 입력, 일반 조작 버튼과 listener가 없다.
- TalkBack은 저빈도 안전 요약을 읽고 고빈도 세부 변화로 반복 방해받지 않는다.
- 뒤로가기를 누르는 즉시 보행이 정지하고, 종료 취소 뒤 자동 재개하지 않는다.
- 카메라 또는 필수 음성 기능의 지속 실패가 안전정지로 이어진다.
- 연결된 정식 시험은 승인된 환경에서 별도로 실행하며 현재 `NOT_RUN`이다.

## 완료 기준

- 정상 보행 화면이 카메라와 읽기 전용 안전상태 중심으로 분리됨
- 정식 배포 UI에서 개발 입력과 일반 조작 버튼이 생성되지 않음
- 뒤로가기 즉시 일시중지와 접근 가능한 종료 확인이 구현됨
- 종료 취소 뒤 정지를 유지하고 명시적 재개만 허용함
- 카메라·필수 음성 지속 실패가 안전정지로 연결됨
- 관련 내부 회귀와 구현·검증 hash가 기록됨
- `GAP-025`와 Backlog가 실제 근거에 맞게 재평가됨

내부 구현만으로 정식 시험, 실제 사용자·기기·현장 검증, 플랫폼 승인, 외부 승인,
운영 배포, release gate 종료 또는 출시 가능을 주장하지 않는다.

## 질문·중단 조건

현재 정본 정책 질문은 없다. 다음 경우에만 Master 정책에 따라 중단한다.

- 유효한 정책끼리 실제로 충돌함
- 같은 파일의 사용자 변경과 안전하게 병합할 수 없음
- 새 외부 승인 없이는 보수적 내부 경계를 결정할 수 없음
- 실제 사용자·기기 근거 없이 내부 완료와 외부 완료를 분리할 수 없음

## 완료 후 인계

1. FP014 완료 근거와 r018 Gap·Backlog를 보존한다.
2. `GAP-025` 재평가와 구현·검증·successor 근거를 남긴다.
3. 정식 시험·실사용자·실기기·현장·플랫폼·외부·배포·출시 근거를 외부 검증으로 분리한다.
4. 완료 뒤 EPIC-02의 다음 deterministic frontier를 다시 계산한다.
