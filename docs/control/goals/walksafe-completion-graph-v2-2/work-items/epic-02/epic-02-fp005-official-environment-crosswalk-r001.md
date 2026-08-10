+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02-FP-005-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 10
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK"
start_requires = ["WS-GOAL-EPIC-02-FP-004-R001"]
completion_requires = ["WS-GOAL-EPIC-02-FP-004-R001"]
child_goal_ids = []
source_policy_ids = ["FP-005"]
gap_ids = ["GAP-014"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r011.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-011"
materialized_from_sha256 = "27eb2c1a4a656a921cd3573f11e76d3b5656afa25f3707b60e5e455c906a523e"
predecessor_goal_id = "WS-GOAL-EPIC-02-FP-004-R001"
predecessor_goal_content_sha256 = "b558e360daf9cc3ae3682241ff3aceba2822de2dbe26bf67c6b200f7a9ea1bf1"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++

# Work Item Goal — 공식 사용환경과 횡단보도

## 목표

첫 공식 지원환경을 비나 눈이 오지 않는 밝은 시간의 일반 도심 보도로 제한한다. 앱이 직접 확인할 수 있는 위치·카메라 품질은 측정 가능한 관문으로 판단하고, 날씨·공사·혼잡처럼 신뢰성 있게 확인할 수 없는 조건은 정상이라고 추측하지 않는다. 횡단보도 정보는 참고용으로만 안내하며 신호 준수나 안전을 보장하지 않는다.

이 Goal의 완료수준은 저장소 내부 정책 정합 구현과 `GAP-014` 재평가다. 환경별 현장시험, 실제 사용자·기기 시험, `TC-FP-005-01`~`04`의 정식 PASS와 출시 Gate는 별도 검증 Workstream에 남긴다.

## 정본 입력

- 정책: `FP-005`
- 요구사항: `RQ-FP-005-001`
- Gap: `GAP-014`, 현재 `MISSING`, P0
- 설계 연결: `DES-01·14·16·17·18`
- 예정 정식 시험: `TC-FP-005-01`~`04`, 모두 `NOT_RUN`
- 선행 Goal: `WS-GOAL-EPIC-02-FP-004-R001`
- 현재 successor: Gap·Backlog r011

승인 정책의 핵심 규칙은 다음과 같다.

1. 첫 공식 지원환경은 밝고 건조한 일반 도심 보도다.
2. 환경별 지원 여부를 날씨·밝기·혼잡도·도로 유형으로 나누어 기록한다.
3. 앱이 실제로 측정할 수 있는 조건과 사용자 고지만 가능한 조건을 구분한다.
4. 현재 환경이나 필수 센서 품질을 믿을 수 없으면 정상 안내를 계속하지 않고 제한 또는 안전정지로 바꾼다.
5. 횡단보도 안내는 참고 정보이며 사용자의 주변 확인과 신호 준수를 대신하지 않는다.
6. 야간·악천후·공사구간·매우 붐비는 곳은 환경별 현장시험과 승인 기록 전까지 지원하지 않는다.
7. 새 환경은 현장시험 결과와 승인 기록이 생긴 뒤에만 지원 목록에 추가한다.

## 범위와 제외

포함:

- Android 시작 전·보행 중 공식 환경 지원 상태와 사유 모델
- 조도·위치·카메라 품질처럼 기기가 측정할 수 있는 조건의 명시적 기준과 재검사
- 날씨·공사·혼잡처럼 확인할 수 없는 조건의 보수적 사용자 확인·고지 또는 사용 제한
- 지원하지 않는 환경과 필수 품질 상실 시 접근 가능한 안내와 안전정지
- 횡단보도 참고 안내의 고정 문구와 안전 보장 금지
- 기존 FP-017·FP-018 생명주기, FP-004 사전교육과의 연결
- 정책 동작을 검증하는 단위·정적·구성요소 회귀

제외:

- 실제 도로의 횡단 가능 여부, 신호 상태 또는 안전 보장
- 날씨·공사·혼잡의 신뢰성 있는 자동 인식이 없는 상태에서의 추정
- 야간·악천후·공사구간·고밀도 혼잡 환경의 지원 승인
- 실제 사용자·기기·현장 시험의 PASS
- 프로덕션 배포와 정식 시험 279개·5개 Gate의 완료 또는 면제

## 실행 절차

1. Android 시작 전 점검, 카메라·위치 품질, 보행 생명주기와 횡단보도 안내 경로를 조사한다.
2. 측정 가능 조건과 사용자 확인 조건을 분리한 환경 지원 상태·사유·전이 계약을 정의한다.
3. 밝고 건조한 일반 도심 보도의 내부 허용 조건과 미확인·미지원 조건의 fail-closed 회귀를 먼저 추가한다.
4. 시작 전 환경 확인을 FP-004 교육과 FP-017·FP-018 readiness에 연결한다.
5. 활성 보행 중 필수 품질이 기준 아래로 떨어지고 재시도 뒤에도 회복되지 않으면 원인을 안내하고 안전정지한다.
6. 횡단보도에서는 참고용이며 주변과 신호를 직접 확인해야 한다는 고정 안내만 제공한다.
7. targeted·구성요소·Android 전체 회귀, debug build와 lint를 실행하고 원출력을 기록한다.
8. 구현기록·검증결과를 확정하고 r011을 보존한 Gap·Backlog successor를 생성한다.
9. successor trace·내부 검토·completion receipt를 결속하고 staged checkpoint를 검증한다.
10. `CANONICAL_BINDINGS_UPDATED → GOAL_COMPLETED` 뒤 다음 정책 Work Item을 결정적으로 선택한다.

## 검증

내부 검증은 최소한 다음 결과를 구분한다.

- 밝고 건조한 일반 도심 보도이며 필수 위치·카메라 품질 충족 → 정상 안내 시작 가능
- 야간·비·눈 또는 환경 상태 미확인 → 지원한다고 추측하지 않고 제한 또는 안전정지
- 공사·매우 붐비는 조건을 확인할 수 없음 → 사전 고지·사용 제한 적용
- 위치·카메라 품질 저하가 재시도 뒤에도 지속 → 원인 안내 후 전체 보행 안전정지
- 횡단보도 진입 → 참고 정보이고 최종 안전 판단을 대신하지 않는다는 안내
- 정식 시험·현장시험·출시 상태 → `NOT_RUN`, `NOT_ELIGIBLE`

내부 자동화는 상태 전이와 고지 계약을 확인할 수 있지만 실제 환경의 안전성이나 횡단 가능 여부를 증명하지 않는다.

## 완료 기준

- 공식 지원·제한·미확인 환경 상태와 이유가 명시적으로 구현됨
- 측정 가능한 위치·카메라 품질이 시작 전과 활성 보행 중 안전 상태에 연결됨
- 측정할 수 없는 날씨·공사·혼잡 조건을 정상으로 추측하지 않음
- 야간·악천후·공사구간·매우 붐비는 곳은 승인 전까지 보수적으로 제한됨
- 횡단보도 안내가 참고용이며 안전을 보장하지 않는다는 계약이 구현됨
- 관련 Android 단위·정적·구성요소·전체 회귀와 build·lint가 통과함
- 구현 파일·결과 hash와 남은 실제 사용자·기기·현장·정식 검증이 기록됨
- `GAP-014`를 실제 근거에 맞게 재평가한 새 Gap·Backlog revision이 존재함

내부 구현만으로 실제 환경 안전성, 횡단 가능 여부, `IMPLEMENTED`, 정식 시험 완료, Gate 종료 또는 출시 가능을 주장하지 않는다.

## 질문·중단 조건

현재 정책 질문은 없다. 승인된 첫 지원환경, 횡단보도 참고 안내, 미확인 조건의 보수적 제한을 다시 묻지 않는다.

다음 경우에만 Master 정책에 따라 중단한다.

- 유효 정책끼리 실제로 충돌해 정본 우선순위로 해결할 수 없음
- 같은 파일의 사용자 변경과 안전하게 병합할 수 없음
- 실제 사람·현장·운영 권한 없이는 보수적 내부 경계를 정할 수 없음
- 민감정보나 비밀값을 저장소에 넣어야만 진행 가능한 설계

## 완료 후 인계

1. r011과 FP-004 증거를 보존하고 새 구현·검증·Gap·Backlog successor를 append-only로 만든다.
2. 부모 [`../../workstreams/epic-02-safe-walk-state-and-permissions.md`](../../workstreams/epic-02-safe-walk-state-and-permissions.md)의 남은 정책·Gap을 재평가한다.
3. 실제 사용자·기기·현장·정식 근거는 typed 외부 Workstream으로 분리한다.
4. 다음 내부 정책 Work Item이 있으면 `GOAL_MATERIALIZED → GOAL_READY`로 준비한다.
5. checkpoint·Goal graph·continuation·daylog·local-memory를 갱신한 뒤 다음 ready leaf를 진행한다.
