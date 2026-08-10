+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 8
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE"
start_requires = ["WS-GOAL-EPIC-02-FP-018-R001"]
completion_requires = ["WS-GOAL-EPIC-02-FP-018-R001"]
child_goal_ids = []
source_policy_ids = ["NPC-PERMISSION-SESSION-LIFECYCLE"]
gap_ids = ["GAP-006"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r009.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-009"
materialized_from_sha256 = "ea2c1c278a1cafe87ed44051c70168a29379714df6e8d243ab58235a6295502a"
predecessor_goal_id = "WS-GOAL-EPIC-02-FP-018-R001"
predecessor_goal_content_sha256 = "0607dd70faa6def46fe07f9511fc5eec87f884858b650600fe286b4c207aa501"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++

# Work Item Goal — 권한·로그인·동의 상태 분리

## 목표

운영체제 권한, 사용자 로그인, 원본 수집 동의, 자동신고 동의, 이동통신망 선택을 서로 독립된 상태로 관리한다. 한 상태가 바뀌어도 무관한 상태를 함께 지우거나 재요청하지 않으며, 바뀐 상태에 직접 의존하는 기능만 중지·재확인한다.

이 Goal의 완료수준은 저장소 내부 정책 정합 구현과 `GAP-006` 재평가다. 실제 기기와 `TC-NPC-PERMISSION-SESSION-LIFECYCLE-01` 정식 PASS, 외부 권리요청 운영 증거, 279개 정식 시험과 5개 Gate는 별도 Workstream에 남긴다.

## 정본 입력

- 정책: `NPC-PERMISSION-SESSION-LIFECYCLE`
- 요구사항: `RQ-NPC-PERMISSION-SESSION-LIFECYCLE-001`
- Gap: `GAP-006`, 현재 `CONFLICTING`, P0
- 설계 연결: `DES-01·04·09·14·15·16·18·19·22`
- 예정 정식 시험: `TC-NPC-PERMISSION-SESSION-LIFECYCLE-01`, 현재 `NOT_RUN`
- 선행 Goal: `WS-GOAL-EPIC-02-FP-018-R001`
- 현재 successor: Gap·Backlog r009

승인 정책의 여섯 규칙은 다음과 같다.

1. 이미 허용한 카메라·위치·마이크 권한은 반복 요청하지 않고 실제 사용 직전에 현재 상태를 확인한다.
2. 권한 철회 시 그 권한에 의존하는 기능만 멈추며, 남은 기능만으로 안전하지 않을 때만 이유를 알리고 전체 보행기능을 안전정지한다.
3. 로그인은 명시적 로그아웃·인증 만료·보안사고 전까지 유지한다.
4. 로그아웃은 운영체제 권한·서버 동의·서버 자료를 지우지 않으며, 앱 삭제는 휴대전화 로그인 상태와 휴대전화 자료만 제거한다.
5. 앱 밖에서도 서버 자료의 열람·철회·삭제를 요청할 수 있는 경로를 제공한다.
6. 재부팅·비정상 종료·운영체제 강제 종료 뒤 이전 보행과 경로를 자동 재개하지 않는다.

## 범위와 제외

포함:

- Android 사용자 앱의 권한·로그인·동의·자동신고·망 선택 상태 모델 분리
- 각 상태의 저장 위치, 수명, 갱신 원인, 종속 기능 표 작성
- 실제 사용 직전 권한 확인과 이미 허용된 권한의 반복 요청 방지
- 권한별 기능 중지와 안전 필수기능 부재 시 전체 안전정지
- 로그인 유지·명시적 로그아웃·인증 만료 처리의 구분
- 로그아웃·앱 자료 초기화·서버 동의 철회·서버 자료 삭제의 분리
- 앱 밖 권리요청 경로의 사용자 표면과 서버 연결 경계
- 재시작 뒤 이전 보행 자동복원 금지와 FP-018 lifecycle 연결
- 상태 전이와 근거를 검증하는 단위·정적·통합 회귀

제외:

- 사용자 가입과 본인확인의 전체 화면·운영 절차
- 실제 외부 인증 공급자, 운영 계정 발급, 프로덕션 배포
- 서버 자료 삭제의 법적 판단과 실제 운영 처리 완료
- 실제 기기·현장·접근성·정식 시험 PASS
- 4개 blocking Gate의 완료 또는 면제

## 실행 절차

1. 현재 Android·Gateway·backend·사용자 표면에서 권한, 로그인, 원본 동의, 자동신고, 망 선택의 저장·조회·초기화 경로를 조사한다.
2. 정책 여섯 규칙을 상태·소유자·저장 위치·만료·중지 대상·재개 조건으로 나눈 차이표를 만든다.
3. 다음 반대 동작을 재현하는 fail-first 테스트나 정적 검사를 추가한다.
   - 이미 허용된 권한을 다시 요청하는 경우
   - 한 권한 철회가 무관한 기능·로그인·동의까지 지우는 경우
   - 안전 필수기능이 없는데 보행이 계속되는 경우
   - 로그아웃이 운영체제 권한이나 서버 동의·자료 삭제로 취급되는 경우
   - 인증 만료 뒤 보호된 서버 기능이 계속되는 경우
   - 앱 재시작 뒤 이전 보행·경로가 자동 재개되는 경우
   - 앱 밖 권리요청 경로가 없거나 접근 불가능한 경우
4. 상태별 단일 책임 모델을 만들고 기존 FP-017·FP-018 lifecycle과 연결한다.
5. 권한 콜백·인증 만료·로그아웃·동의 철회·망 선택 변경을 각각 해당 종속 기능에만 반영한다.
6. 서버 자료 권리요청은 앱 설치·로그인 유지 여부와 독립된 접근 가능한 경로로 연결하고, 실제 운영 처리는 별도 외부 증거로 남긴다.
7. targeted·구성요소·전체 회귀를 실행하고 실제 변경 경로와 원출력을 기록한다.
8. 구현기록·검증결과를 먼저 확정하고 r009를 보존한 Gap·Backlog successor를 생성한다.
9. 새 Gap·Backlog hash로 successor trace를 만들고 독립 검토와 completion receipt를 결속한다.
10. `CANONICAL_BINDINGS_UPDATED → GOAL_COMPLETED`를 연속 적용한 뒤 다음 정책 Work Item을 결정적으로 선택한다.

## 검증

내부 검증은 최소한 다음 결과를 구분해야 한다.

- 권한 허용 유지 → 반복 요청 없음 → 해당 기능 정상 사용
- 위치 권한 철회 → 위치·거리 의존 기능만 제한 → 카메라 기반 기능과 로그인·동의 유지
- 카메라 등 안전 필수 권한 철회 → 이유 안내 → 전체 보행 안전정지
- 로그인 유지 조건 충족 → 앱 재실행 뒤 로그인 유지
- 로그아웃 → 휴대전화 인증정보 제거 → 운영체제 권한과 서버 동의·자료 유지
- 인증 만료 → 보호된 서버 기능 중지·재로그인 요구 → 로컬 비의존 기능 상태와 구분
- 원본 동의 철회 → 새 원본 수집·전송 중지 → 로그인과 무관한 자료처리 상태 보존
- 자동신고 동의 철회 → 새 자동신고 후보만 중지 → 일반 활동 동의와 구분
- 앱 삭제 또는 자료 초기화 → 휴대전화 상태만 제거 → 서버 권리요청 경로 유지
- 재부팅·비정상 종료 → 새 보행 전 명시적 시작 필요 → 이전 경로·위험판단 자동복원 없음
- 정식 시험·Gate·출시 상태 → `279 NOT_RUN`, `5 NOT_RUN`, `NOT_ELIGIBLE`

검증은 관련 Android 단위·정적 테스트, Gateway/backend 계약 회귀, 사용자 표면 접근성 검사, debug build와 lint를 포함한다. 기존 사용자 변경과 충돌하는 파일은 덮어쓰지 않고 현재 패턴에 최소 변경으로 통합한다.

## 완료 기준

- 다섯 상태가 독립된 모델·저장·전이 규칙으로 표현됨
- 각 권한 철회가 종속 기능에만 적용되고 안전 필수기능 부재는 전체 안전정지로 구분됨
- 로그인 유지·로그아웃·인증 만료·앱 자료 초기화가 서로 다른 결과를 냄
- 서버 동의·자료 권리요청이 앱 설치와 독립된 경로로 제공됨
- 재시작 뒤 이전 보행·경로가 자동 복원되지 않음
- 정책 여섯 규칙의 내부 회귀와 관련 전체 회귀가 통과함
- 구현 파일·결과 hash와 남은 실제 기기·정식·외부 검증이 기록됨
- `GAP-006`을 실제 근거에 맞게 재평가한 새 Gap·Backlog revision이 존재함

내부 구현만으로 `IMPLEMENTED`, 정식 PASS, Gate 종료, 실제 삭제 완료 또는 출시 가능을 주장하지 않는다.

## 질문·중단 조건

현재 정책 질문은 없다. 이미 정해진 상태 분리, 기능별 중지, 안전 필수기능 부재 시 전체 안전정지, 로그아웃과 동의·자료의 분리, 비정상 종료 뒤 자동재개 금지를 다시 묻지 않는다.

다음 경우에만 Master 정책에 따라 중단한다.

- 유효 정책끼리 실제로 충돌해 우선순위로 해결할 수 없음
- 같은 파일의 사용자 변경과 안전하게 병합할 수 없음
- 외부 운영 권한·실제 계정·법적 판단 없이는 보수적 내부 경계를 정할 수 없음
- 민감 원본이나 비밀값을 저장소에 넣어야만 진행 가능한 설계

## 완료 후 인계

1. r009와 FP-018 증거를 보존하고 새 구현·검증·Gap·Backlog successor를 append-only로 만든다.
2. 부모 [`../../workstreams/epic-02-safe-walk-state-and-permissions.md`](../../workstreams/epic-02-safe-walk-state-and-permissions.md)의 남은 정책·Gap을 재평가한다.
3. 정식·실제 기기·외부 운영 근거는 typed 외부 Workstream으로 분리한다.
4. 다음 내부 정책 Work Item이 있으면 `GOAL_MATERIALIZED → GOAL_READY`로 준비한다.
5. checkpoint·Goal graph·continuation·daylog·local-memory를 갱신한 뒤 결정적 ready frontier의 다음 leaf를 진행한다.
