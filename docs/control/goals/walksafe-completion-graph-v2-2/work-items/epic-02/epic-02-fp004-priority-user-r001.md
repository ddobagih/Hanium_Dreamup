+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02-FP-004-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 9
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-FP004-PRIORITY-USER"
start_requires = ["WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001"]
completion_requires = ["WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001"]
child_goal_ids = []
source_policy_ids = ["FP-004"]
gap_ids = ["GAP-013"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r010.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-010"
materialized_from_sha256 = "61954297c2679a331ee3e546e0be0682b9d80f5e4439216849a64dfee57625da"
predecessor_goal_id = "WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001"
predecessor_goal_content_sha256 = "06ec8298dcc18cb8a8e3f04c05c9bbad2d09709a9538177c842141e3f01ec490"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++

# Work Item Goal — 우선 사용자와 필수 사전연습

## 목표

전맹 사용자와 저시력 사용자를 같은 우선순위로 지원하고, 스마트폰 사용이 익숙하지 않은 사람도 화면을 계속 보거나 복잡한 조작을 외우지 않고 핵심 기능을 이해하게 한다. 최초 실제 보행 전에는 접근 가능한 짧은 교육과 안전한 장소의 음성·진동 조작 연습을 직접 완료해야만 보행을 시작할 수 있게 한다.

이 Goal의 완료수준은 저장소 내부 정책 정합 구현과 `GAP-013` 재평가다. 전맹·저시력·초보 사용자와 실제 기기를 사용하는 정식 사용자시험, `TC-FP-004-01`~`04`의 정식 PASS, 279개 정식 시험과 5개 Gate는 별도 검증 Workstream에 남긴다.

## 정본 입력

- 정책: `FP-004`
- 요구사항: `RQ-FP-004-001`
- Gap: `GAP-013`, 현재 `MISSING`, P0
- 설계 연결: `DES-01·14·16·17·18`
- 예정 정식 시험: `TC-FP-004-01`~`04`, 모두 `NOT_RUN`
- 선행 Goal: `WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001`
- 현재 successor: Gap·Backlog r010

승인 정책의 핵심 규칙은 다음과 같다.

1. 전맹과 저시력 사용자를 같은 우선순위로 지원하고 어느 한 집단만 통과한 결과를 전체 접근성 완료로 보지 않는다.
2. 화면을 보지 않고도 가입·동의·보행·오류 대응의 핵심 절차를 끝낼 수 있게 한다.
3. 설명은 한 번에 한 행동을 지시하는 짧고 쉬운 한국어로 제공한다.
4. 숨겨진 손짓이나 복잡한 메뉴를 필수 조작으로 두지 않는다.
5. 최초 실제 보행 전 안전 제한, 장착, 위험 안내, 일시정지, 재개, 안전정지를 음성과 진동으로 직접 연습하게 한다.
6. 교육을 읽었다는 확인만으로 완료하지 않으며, 안전한 장소에서 핵심 조작을 성공해야 완료로 기록한다.
7. 만 14세 이상 조건과 보호자 동의 완료 여부를 구분하고, 만 18세 미만 계정은 보호자 확인 없이 활성화하지 않는다.
8. 필수 안내 채널이 동작하지 않거나 교육·연습이 미완료이면 보행을 시작하지 않거나 안전정지한다.

## 범위와 제외

포함:

- Android 최초 사용 교육·연습 상태기계와 완료 기록
- 화면읽기, 큰 글자, 고대비, 한국어 음성, 진동 가능 여부의 시작 전 확인
- 위험 안내·일시정지·재개·안전정지 연습 흐름과 성공 조건
- 교육 미완료·필수 안내 채널 불가·연령 또는 보호자 조건 미충족 시 보행 시작 차단
- 기존 FP-017·FP-018 생명주기 및 권한 세션 정책과의 연결
- TalkBack 이름, 초점 순서, 짧은 행동 지시, 큰 글자에서의 잘림 방지
- 교육 재실행과 앱 재시작 뒤 이전 실제 보행 자동재개 금지
- 정책 동작을 검증하는 단위·정적·구성요소 회귀

제외:

- 실제 전맹·저시력·초보 사용자 대상 관찰시험의 PASS
- 실제 보호자 본인확인 서비스와 운영 계정 발급
- 한국어 이외 언어 지원
- 프로덕션 배포와 실제 기기·현장 시험
- 정식 시험 279개와 5개 Gate의 완료 또는 면제

## 실행 절차

1. 현재 Android 첫 화면, 보행 시작, 접근성 설명, 음성·진동, 연령·계정 경로를 조사하고 정책과의 차이표를 만든다.
2. 교육·연습·지원환경·보호자 확인·필수 안내 채널을 각각 명시적 상태와 전이 조건으로 정의한다.
3. 다음 일반적인 불일치가 검출되는 회귀를 먼저 추가한다.
   - 교육 미완료인데 실제 보행을 시작할 수 있음
   - 안내를 읽었다는 확인만으로 연습 완료가 됨
   - 음성 또는 진동의 필수 안내 채널이 없는데 보행이 계속됨
   - 화면읽기 이름이나 초점 순서가 없어 핵심 조작을 화면 없이 찾을 수 없음
   - 큰 글자에서 필수 안내나 행동 버튼이 잘림
   - 만 18세 미만 계정이 보호자 확인 없이 활성화됨
   - 앱 재시작 뒤 교육 상태와 무관하게 이전 보행이 자동 재개됨
4. 기존 화면을 최소 변경해 접근 가능한 온보딩과 안전한 연습 흐름을 연결한다.
5. 교육 완료는 위험 안내·일시정지·재개·안전정지의 직접 성공 기록으로만 부여한다.
6. 필수 조건이 사라지면 해당 기능을 중지하고, 보행 안전에 필수이면 이유를 안내한 뒤 안전정지한다.
7. targeted·구성요소·Android 전체 회귀, debug build와 lint를 실행하고 원출력을 기록한다.
8. 구현기록·검증결과를 확정하고 r010을 보존한 Gap·Backlog successor를 생성한다.
9. successor trace·내부 검토·completion receipt를 결속하고 staged checkpoint를 검증한다.
10. `CANONICAL_BINDINGS_UPDATED → GOAL_COMPLETED` 뒤 다음 정책 Work Item을 결정적으로 선택한다.

## 검증

내부 검증은 최소한 다음 결과를 구분한다.

- 교육 미완료 → 실제 보행 시작 불가 → 설명과 안전한 연습만 제공
- 위험 안내·일시정지·재개·안전정지 연습 모두 성공 → 교육 완료 기록
- 화면읽기 사용 → 핵심 화면과 행동 버튼에 명확한 이름·역할·초점 순서 제공
- 큰 글자·고대비 → 핵심 설명과 버튼이 손실 없이 표시
- 스마트폰 초보 흐름 → 숨겨진 손짓 없이 한 번에 한 행동을 쉬운 한국어로 안내
- 만 14세 미만 또는 필요한 보호자 확인 미완료 → 계정·보행 시작 차단
- 만 14세 이상 18세 미만이며 보호자 확인 미완료 → 계정 활성화 차단
- 필수 음성·진동 안내 불가 → 보행 시작 차단 또는 안전정지
- 앱 재시작 → 교육 완료 기록은 정책대로 보존하되 이전 보행·경로는 자동복원하지 않음
- 정식 시험·Gate·출시 상태 → `279 NOT_RUN`, `5 NOT_RUN`, `NOT_ELIGIBLE`

내부 자동화는 정책 동작과 접근성 속성의 회귀를 확인할 수 있지만 실제 사용자의 성공을 대신하지 않는다. 실제 전맹·저시력·초보 사용자 관찰 결과는 정식 외부 증거로 분리한다.

## 완료 기준

- 최초 보행 전 교육·연습 상태기계와 성공 조건이 구현됨
- 교육을 읽었다는 확인만으로 완료할 수 없음
- 전맹·저시력·초보 사용자가 핵심 조작을 찾을 수 있는 접근성 속성과 짧은 한국어 안내가 구현됨
- 연령·보호자 확인·필수 안내 채널 조건이 보행 시작과 안전정지에 연결됨
- 재시작 뒤 이전 보행·경로를 자동 재개하지 않음
- 관련 Android 단위·정적·구성요소·전체 회귀와 build·lint가 통과함
- 구현 파일·결과 hash와 남은 사용자·실제 기기·정식 검증이 기록됨
- `GAP-013`을 실제 근거에 맞게 재평가한 새 Gap·Backlog revision이 존재함

내부 구현만으로 실제 사용자 접근성 PASS, `IMPLEMENTED`, 정식 시험 완료, Gate 종료 또는 출시 가능을 주장하지 않는다.

## 질문·중단 조건

현재 정책 질문은 없다. 승인된 사용자 우선순위, 최초 보행 전 연습 의무, 보호자 확인, 한국어 지원 범위와 필수 안내 채널 부재 시 안전정지를 다시 묻지 않는다.

다음 경우에만 Master 정책에 따라 중단한다.

- 유효 정책끼리 실제로 충돌해 정본 우선순위로 해결할 수 없음
- 같은 파일의 사용자 변경과 안전하게 병합할 수 없음
- 실제 사람·보호자 확인 공급자·운영 권한 없이는 보수적 내부 경계를 정할 수 없음
- 민감정보나 비밀값을 저장소에 넣어야만 진행 가능한 설계

## 완료 후 인계

1. r010과 NPC 증거를 보존하고 새 구현·검증·Gap·Backlog successor를 append-only로 만든다.
2. 부모 [`../../workstreams/epic-02-safe-walk-state-and-permissions.md`](../../workstreams/epic-02-safe-walk-state-and-permissions.md)의 남은 정책·Gap을 재평가한다.
3. 실제 사용자·실제 기기·정식 근거는 typed 외부 Workstream으로 분리한다.
4. 다음 내부 정책 Work Item이 있으면 `GOAL_MATERIALIZED → GOAL_READY`로 준비한다.
5. checkpoint·Goal graph·continuation·daylog·local-memory를 갱신한 뒤 다음 ready leaf를 진행한다.
