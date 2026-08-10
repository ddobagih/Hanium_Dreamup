+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02-FP-006-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 11
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-FP006-SOLO-WALK-PHONE-MOUNTING"
start_requires = ["WS-GOAL-EPIC-02-FP-005-R001"]
completion_requires = ["WS-GOAL-EPIC-02-FP-005-R001"]
child_goal_ids = []
source_policy_ids = ["FP-006"]
gap_ids = ["GAP-015"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r012.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-012"
materialized_from_sha256 = "305e7864f021575ce04aec39bcbb5ba968e2299e4afcf7287712283c12c31fe3"
predecessor_goal_id = "WS-GOAL-EPIC-02-FP-005-R001"
predecessor_goal_content_sha256 = "61742bc14a794592978c788b9600db1e1e7185edaa6e6413d37fc49cca286a21"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++

# Work Item Goal — 단독 보행 목표와 휴대전화 장착

## 목표

휴대전화를 가슴형 또는 목걸이형 거치대에 고정하고 렌즈가 앞을 향하며 옷이나 손에 가리지 않은 상태에서만 위험 탐지를 시작한다. 시작 전과 보행 중 렌즈 방향·가림·흔들림·영상 품질을 다시 확인하고, 장착이 나빠지면 접근 가능한 교정 안내를 먼저 제공한 뒤 제한된 재검사에도 회복되지 않으면 탐지 결과를 신뢰하지 않고 전체 보행을 안전정지한다.

허용 높이·각도·흔들림 범위는 전맹·저시력 참여자, 서로 다른 체형과 보행 속도, 공식 지원 기기를 조합한 실제 측정으로 정한다. 그 근거와 승인 프로필이 없는 동안 수치를 추측해 정상 장착으로 판정하지 않는다. WalkSafe가 흰지팡이·안내견 등 기존 보조수단을 대신한다고 안내하지 않는다.

이 Goal의 완료수준은 저장소 내부 정책 정합 구현과 `GAP-015` 재평가다. 장착 범위 확정을 위한 실제 사용자·기기·현장 측정, `TC-FP-006-01`~`04`의 정식 PASS와 출시 Gate는 별도 검증 Workstream에 남긴다.

## 정본 입력

- 정책: `FP-006`
- 요구사항: `RQ-FP-006-001`
- Gap: `GAP-015`, 현재 `MISSING`, P0
- 설계 연결: `DES-01·14·16·17·18`
- 예정 정식 시험: `TC-FP-006-01`~`04`, 모두 `NOT_RUN`
- 선행 Goal: `WS-GOAL-EPIC-02-FP-005-R001`
- 현재 successor: Gap·Backlog r012
- 기존 내부 경계: FP-005의 카메라 품질·환경 fail-closed 상태기계와 안전정지 연결. 프로덕션 장착·카메라 품질 프로필과 실제 장착 관측값은 아직 없음

승인 정책의 핵심 규칙은 다음과 같다.

1. 가슴형 또는 목걸이형 거치대만 공식 장착 후보로 사용하고 손에 들거나 주머니에 넣은 상태는 인정하지 않는다.
2. 렌즈가 정면을 향하고 옷이나 손에 가리지 않았는지 시작 전과 보행 중에 계속 확인한다.
3. 장착 방향·가림·흔들림·영상 품질을 기기가 측정하는 근거와 승인된 장착 범위를 분리한다.
4. 허용 높이·각도·흔들림 범위는 여러 사용자·체형·보행 속도·지원 기기의 실제 측정으로 정하며 한 사람이나 한 기기의 결과로 일반화하지 않는다.
5. 장착이 부적합하면 먼저 짧은 음성·화면·진동 교정 안내를 제공하고, 계속 실패하면 정상 위험 안내를 중단해 안전정지한다.
6. 승인 프로필이나 신뢰할 수 있는 장착 근거가 없으면 정상이라고 추측하지 않고 보행을 시작하지 않는다.
7. 정식 문서와 사용자 안내에서 기존 보조수단을 대체한다고 주장하지 않는다.

## 범위와 제외

포함:

- Android의 장착 적합·교정 필요·사용 불가 상태와 사유 모델
- 가슴형·목걸이형 정면 장착 후보와 손-held·주머니 장착 금지 계약
- 카메라 방향·가림·흔들림·영상 품질 관측의 시작 전·보행 중 생명주기
- 승인된 프로덕션 장착 프로필 부재와 알 수 없는 관측값의 fail-closed 처리
- 장착 오류의 접근 가능한 교정 안내, 제한된 재검사, 출력 억제와 전체 안전정지
- FP-005 공식 환경·카메라 품질, FP-017·FP-018 보행 생명주기, FP-004 사전교육과의 연결
- 기존 보조수단 비대체 고지의 Android 사용자 표면·문구 계약
- 정책 동작을 검증하는 단위·정적·구성요소·Android 전체 회귀

제외:

- 허용 높이·상하 각도·좌우 기울기·흔들림의 임의 수치 결정
- 실제 전맹·저시력 참여자와 여러 체형·보행 속도·지원 기기의 장착시험 PASS
- 특정 거치대 제품의 구매·인증·공식 지원 승인
- 기존 보조수단 없이 사용할 수 있다는 안전성 또는 대체 가능성 주장
- 실제 사용자·기기·현장 시험과 프로덕션 프로필 승인
- 정식 시험 279개와 5개 Gate의 완료 또는 면제

## 실행 절차

1. 현재 Android 카메라·IMU·방향 관측, FP-005 카메라 품질, 시작 readiness, 활성 보행 출력과 안전정지 경로를 조사한다.
2. 장착 후보, 측정 가능한 관측, 승인 프로필, 사용자 교정 확인을 분리한 상태·사유·epoch 계약을 정의한다.
3. 다음 반대 동작을 재현하는 fail-first 회귀를 추가한다.
   - 손에 들거나 주머니에 넣은 상태를 공식 장착으로 인정함
   - 렌즈 방향·가림·흔들림이 알 수 없거나 오래됐는데 보행을 시작함
   - 승인된 장착 프로필 없이 추측한 수치로 장착을 통과시킴
   - 장착 품질을 잃은 뒤 이전 관측이나 늦게 도착한 결과가 출력을 다시 켬
   - 교정 재시도가 계속 실패해도 위험 안내가 유지됨
   - 사용자 표면이 기존 보조수단을 대신한다고 암시함
4. FP-005 품질 경계와 중복되지 않는 최소 장착 정책 모델과 관측 어댑터를 구현한다.
5. 시작 전 장착 확인을 FP-004 교육과 보행 readiness에 연결하고, 활성 보행에서는 품질 손실 즉시 출력을 억제한다.
6. 짧은 교정 안내와 제한된 재검사를 제공한 뒤 지속 부적합이면 원인과 다음 행동을 알리고 중앙 안전정지한다.
7. targeted·구성요소·Android 전체 회귀, debug build와 lint를 실행하고 원출력을 기록한다.
8. 구현기록·검증결과를 확정하고 r012를 보존한 Gap·Backlog successor를 생성한다.
9. successor trace·내부 검토·completion receipt를 결속하고 staged checkpoint를 검증한다.
10. `CANONICAL_BINDINGS_UPDATED → GOAL_COMPLETED` 뒤 부모 EPIC의 다음 정책 Work Item을 결정적으로 선택한다.

## 검증

내부 검증은 최소한 다음 결과를 구분한다.

- 가슴형 또는 목걸이형 정면 장착 후보, 신선한 관측과 승인 프로필 충족 → 탐지 시작 후보
- 손-held·주머니 장착 또는 렌즈 비정면 → 공식 장착 불인정, 시작 차단
- 렌즈 가림·과도한 흔들림·영상 품질 부적합 → 정상 탐지 출력 억제와 교정 안내
- 장착 근거 미확인·오래됨·epoch 불일치·프로필 미승인 → 정상으로 추측하지 않고 fail-closed
- 보행 중 장착 품질 손실 → 늦은 결과 무시, 제한된 재검사, 지속 실패 시 전체 안전정지
- 교정 후 신선한 근거가 다시 충족됨 → 사용자 확인과 생명주기 계약에 따른 재개 후보
- 사용자 안내·문서 표면 → WalkSafe가 흰지팡이·안내견 등 기존 보조수단을 대신한다는 표현 없음
- 정식 시험·실제 사용자·기기·현장·Gate·출시 상태 → `NOT_RUN`, `NOT_ELIGIBLE`

내부 자동화는 상태 전이·고지·fail-closed 계약을 확인할 수 있지만 실제 장착 범위나 단독 보행 안전성을 증명하지 않는다.

## 완료 기준

- 공식 장착 후보와 금지 장착 방식이 명시적으로 구현됨
- 방향·가림·흔들림·영상 품질 관측과 승인 장착 프로필이 분리됨
- 승인 프로필과 신선한 관측이 없으면 장착을 정상으로 추측하지 않음
- 시작 전과 활성 보행 중 장착 품질이 readiness·출력 억제·교정·안전정지에 연결됨
- 이전 epoch·오래된·늦게 도착한 장착 근거가 보행 출력을 재활성화하지 못함
- 기존 보조수단 비대체 고지가 Android 사용자 표면에 일관되게 구현됨
- 관련 Android 단위·정적·구성요소·전체 회귀와 build·lint가 통과함
- 구현 파일·결과 hash와 남은 실제 사용자·기기·현장·정식 검증이 기록됨
- `GAP-015`를 실제 근거에 맞게 재평가한 새 Gap·Backlog revision이 존재함

내부 구현만으로 허용 장착 수치, 실제 단독 보행 안전성, `IMPLEMENTED`, 정식 시험 완료, Gate 종료 또는 출시 가능을 주장하지 않는다.

## 질문·중단 조건

현재 정책 질문은 없다. 승인된 가슴형·목걸이형 정면 장착 후보, 다중 사용자·기기 측정 전 수치 추측 금지, 부적합 장착의 안전정지, 기존 보조수단 비대체 경계를 다시 묻지 않는다.

다음 경우에만 Master 정책에 따라 중단한다.

- 유효 정책끼리 실제로 충돌해 정본 우선순위로 해결할 수 없음
- 같은 파일의 사용자 변경과 안전하게 병합할 수 없음
- 실제 사람·기기·현장 측정 없이는 보수적 내부 경계 자체도 정할 수 없음
- 특정 거치대 구매·외부 공급자·프로덕션 승인 또는 비밀값이 있어야만 진행 가능함

## 완료 후 인계

1. r012와 FP-005 증거를 보존하고 새 구현·검증·Gap·Backlog successor를 append-only로 만든다.
2. 부모 [`../../../walksafe-completion-graph-v2-2/workstreams/epic-02-safe-walk-state-and-permissions.md`](../../../walksafe-completion-graph-v2-2/workstreams/epic-02-safe-walk-state-and-permissions.md)의 남은 정책·Gap을 재평가한다.
3. 허용 장착 범위 확정과 실제 사용자·기기·현장·정식 근거는 typed 외부 검증 Workstream으로 분리한다.
4. 다음 내부 정책 Work Item이 있으면 `GOAL_MATERIALIZED → GOAL_READY`로 준비한다.
5. checkpoint·Goal graph·continuation·daylog·local-memory를 갱신한 뒤 결정적 ready frontier의 다음 leaf를 진행한다.
