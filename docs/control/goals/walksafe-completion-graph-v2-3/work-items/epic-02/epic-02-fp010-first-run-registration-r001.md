+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02-FP-010-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 12
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-FP010-FIRST-RUN-REGISTRATION"
start_requires = ["WS-GOAL-EPIC-02-FP-006-R001"]
completion_requires = ["WS-GOAL-EPIC-02-FP-006-R001"]
child_goal_ids = []
source_policy_ids = ["FP-010"]
gap_ids = ["GAP-019"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r013.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-013"
materialized_from_sha256 = "ce22a11e2c3388ef2e728f4a19eb970ecb7bee5658668f159d178a77262c8edb"
predecessor_goal_id = "WS-GOAL-EPIC-02-FP-006-R001"
predecessor_goal_content_sha256 = "ddf2da4990337b3ce78ccfe85084abbefddfdb46044e88a953b266115c59b901"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++

# Work Item Goal — 첫 실행과 회원가입

## 목표

서비스 목적·안전 한계 설명 → 만 14세 이상과 보호자 필요 여부 확인 →
통합 동의 → ID·비밀번호·전화번호 입력 → SMS 확인 → 만 18세 미만
보호자 확인·동의 → 계정 활성화 → 로그인 → 기능 직전 권한 → 기기점검 →
FP-004 필수 안전훈련을 순서가 있는 첫 실행 상태기계로 구현한다. 어느 필수
단계라도 실패하거나 현재 증거가 없으면 보행 화면과 민감 센서 수집으로
넘어가지 않는다.

이 Goal의 완료수준은 저장소 내부 정책 정합 구현과 `GAP-019` 재평가다.
외부 가입·SMS·보호자 API와 운영 승인, 실제 사용자 TalkBack·기기 시험,
정식 시험과 출시 Gate는 별도 검증 Workstream에 남긴다. 따라서 내부
slice를 완료해도 `GAP-019`는 최대 `PARTIAL`이며 `IMPLEMENTED`로 올리지 않는다.

## 정본 입력

- 정책: `FP-010`
- 요구사항: `RQ-FP-010-001`
- Gap: `GAP-019`, 현재 `MISSING`, P0
- 설계 연결: `DES-01·04·08·09·11·12·14·15·16·18·19·21`
- 예정 정식 시험: `TC-FP-010-01`~`04`, 모두 `NOT_RUN`
- 선행 Goal: `WS-GOAL-EPIC-02-FP-006-R001`
- 생성 근거: Gap·Backlog r013, 실행순서 12
- 현재 관찰: 단일 화면과 일반 설정의 사용자 ID는 있으나 승인된 첫 실행
  전체 순서, 실패 차단, 접근 가능한 단계 복원 계약은 확인되지 않음

승인 정책의 핵심 규칙은 다음과 같다.

1. 서비스 목적과 안전 한계를 먼저 쉬운 문장으로 설명한다.
2. 만 14세 이상인지 확인하고 만 18세 미만이면 보호자 절차가 필요함을 고정한다.
3. 통합 동의를 받은 뒤 ID·비밀번호·전화번호를 입력받는다.
4. SMS로 전화번호를 확인하고, 만 18세 미만은 보호자 확인·동의를 마친다.
5. 위 증거가 모두 유효할 때만 계정을 활성화하고 로그인한다.
6. 운영체제 권한은 기능을 처음 쓰기 직전에 현재 상태를 확인하고 요청한다.
7. 기기점검 뒤 FP-004 필수 안전훈련을 완료해야 보행 기능을 연다.
8. 모든 입력·오류·버튼·진행 상태를 TalkBack으로 사용할 수 있게 한다.
9. 가입 중 카메라·음성·위치 수집이나 보행 안내를 시작하지 않는다.
10. 재시도 때에는 서버·로컬 증거로 확인된 안전한 단계만 복원한다.

## 범위와 제외

포함:

- 첫 실행 단계·전이·실패·재시도·완료 상태 모델
- 만 14세 이상과 만 18세 미만 보호자 절차의 fail-closed 판정
- 통합 동의·ID/PW/전화 입력·SMS·보호자·활성화·로그인 순서
- 기능 직전 권한 확인, 기기점검, FP-004 필수 안전훈련 연결
- TalkBack 이름·읽기 순서·오류·진행 안내와 버튼 없는 우회 차단
- 보행 lifecycle, 장착·환경 readiness, 센서·신고 출력과의 시작 관문
- 단위·정적·구성요소·Android 전체 회귀와 내부 추적 증거

제외:

- 실제 signup·SMS·보호자 확인 API와 운영 자격정보 프로비저닝
- 외부 공급자 선정·계약·운영 승인과 실제 계정 활성화
- 실제 사용자 TalkBack·실기기 접근성·가입 성공률 측정
- `TC-FP-010-01`~`04`, 정식 시험 279개, 5개 Gate, 배포 또는 출시 승인
- FP-011 세션 증명 회전과 FP-013 통합 동의 원장의 전체 후속 범위

## 실행 절차

1. 현재 로그인·사용자 ID·동의·권한·교육·보행 진입 경로와 저장 위치를 조사한다.
2. 정책→요구→설계→코드→시험 차이와 보안·개인정보 경계를 확정한다.
3. 단계 건너뛰기, 실패 뒤 보행 진입, 민감 센서 조기 시작, 오래된 단계
   복원과 TalkBack 누락을 재현하는 fail-first 회귀를 추가한다.
4. 서버 확인 증거와 로컬 표시 상태를 분리한 최소 첫 실행 상태기계를 구현한다.
5. 목적·안전 → 만 14세/보호자 필요 → 통합 동의 → ID/PW/전화 → SMS →
   만 18세 미만 보호자 확인·동의 → 활성화 → 로그인 → JIT 권한 → 기기점검
   → FP-004 훈련 순서를 보행 시작 관문과 원자적으로 연결한다.
6. 취소·프로세스 재생성·네트워크 실패·중복 응답에서 확인되지 않은 단계가
   완료로 승격되지 않도록 epoch와 멱등 계약을 적용한다.
7. targeted·구성요소·Android 전체 회귀, debug build와 lint를 실행한다.
8. 구현기록·검증결과와 r013을 잇는 Gap·Backlog successor를 확정한다.
9. 독립 검토·completion receipt·canonical update를 결속한다.
10. 완료 뒤 부모 EPIC의 다음 정책 Work Item을 결정적으로 선택한다.

## 검증

- 새 설치 → 목적·안전 한계부터 승인 순서대로 진행
- 미성년 조건 또는 보호자 확인 미충족 → 다음 단계와 보행 진입 차단
- 잘못되거나 만료된 전화 확인 → 계정 비활성, 접근 가능한 오류 안내
- 네트워크 중단·재시도·늦은 응답 → 중복 계정·중복 완료·단계 역행 없음
- 권한 거부·기기점검·안전 연습 미완료 → 보행 및 민감 출력 차단
- 이미 허용된 권한 → 설명창 때문에 반복 요청하지 않고 현재 상태 확인
- 프로세스 재생성 → 서버로 확인된 단계만 복원
- TalkBack → 모든 조작·오류·진행·초점 순서를 독립 사용 가능
- 정식·실제 사용자·실기기·공급자·Gate·출시 → `NOT_RUN`, `NOT_ELIGIBLE`

## 완료 기준

- 승인된 첫 실행 순서가 명시적 상태·전이·실패 계약으로 구현됨
- 필수 단계 누락·오래된 증거·실패·취소 때 보행 화면으로 넘어가지 않음
- 가입 중 카메라·음성·위치와 보행·신고 출력을 시작하지 않음
- 휴대전화 확인과 계정 활성화가 로컬 표시값만으로 위조되지 않음
- 재시도·늦은 응답·프로세스 재생성에서 중복·역행이 차단됨
- TalkBack 조작과 오류 복구 경로가 내부 회귀로 검증됨
- raw 비밀번호·OTP·원 전화번호·정확 생년월일·장애유형을 저장하지 않음
- TalkBack 활성 시 비긴급 설명을 별도 TTS로 중복 재생하지 않음
- 운영 signup·SMS·guardian 공급자 성공을 추측하거나 fake 성공을 출시
  증거로 사용하지 않음
- 관련 Android 회귀·build·lint와 구현·검증 hash가 기록됨
- `GAP-019`를 실제 근거에 맞게 재평가한 새 Gap·Backlog revision이 존재함

내부 slice 완료 뒤에도 외부 signup·SMS·보호자 API와 운영 승인, 실제
사용자 TalkBack, `TC-FP-010-01`~`04`가 없으므로 `GAP-019`는 최대
`PARTIAL`이다. 내부 구현만으로 `IMPLEMENTED`, Gate 종료 또는 출시 가능을
주장하지 않는다.

## 질문·중단 조건

현재 정책 질문은 없다. 승인된 첫 실행 순서와 필수 단계 실패 시 보행 차단을
다시 묻지 않는다. 다음 경우에만 Master 정책에 따라 중단한다.

- 유효 정책끼리 실제로 충돌해 정본 우선순위로 해결할 수 없음
- 같은 파일의 사용자 변경과 안전하게 병합할 수 없음
- 보호자 확인 또는 동의 범위를 정하는 새 사용자 결정이 반드시 필요함
- 실제 공급자·비밀값·외부 권한 없이는 보수적 내부 경계도 구현할 수 없음

## 완료 후 인계

1. r013과 FP006 증거를 보존하고 새 구현·검증·Gap·Backlog successor를 만든다.
2. 부모 EPIC-02의 남은 정책·Gap과 결정적 ready frontier를 재평가한다.
3. SMS·보호자·실제 사용자·기기·정식 근거는 typed 외부 검증으로 분리한다.
4. 다음 내부 정책 Work Item은 별도 `GOAL_MATERIALIZED → GOAL_READY`로 준비한다.
5. checkpoint·Goal graph·daylog·local-memory를 갱신한다.
