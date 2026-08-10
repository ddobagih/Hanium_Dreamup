+++
schema_version = "1.0"
goal_id = "WS-GOAL-A-EPIC-02-FP-018-R001"
goal_kind = "WORK_ITEM"
document_version = "1.1.0"
phase_id = "A"
parent_goal_id = "WS-GOAL-A-EPIC-02"
sequence = 7
initial_status = "READY"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
next_goal_id = ""
return_goal_id = "WS-GOAL-A-EPIC-02"
work_item_id = "EPIC-02-FP018-WALK-STATE-RECOVERY"
dependencies = ["WS-GOAL-A-EPIC-01"]
child_goal_ids = []
source_policy_ids = ["FP-018"]
gap_ids = ["GAP-027"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r008.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-008"
materialized_from_sha256 = "77c11a61bab49853cc53e51f1a72c2399d4fe2a29b9ef284183cdc15679cb342"
predecessor_goal_id = ""
predecessor_goal_content_sha256 = ""
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
+++

# Work Item Goal — FP-018 보행 상태 복구

## 목표

앱이 background에서 돌아오거나 안전정지 뒤 회복됐을 때 권한·센서·경로·음성안내·모델을 다시 확인한 후에만 사용자가 명시적으로 보행을 재개하게 한다. 재부팅·앱 오류 종료·운영체제 강제 종료 뒤에는 이전 보행·경로·위험판단을 자동 복원하지 않고 새 보행으로 분리한다.

이 Goal의 완료수준은 저장소 내부 정책 정합 구현과 Gap 재평가다. 실제 기기와 `TC-FP-018-01~06` 정식 PASS는 Phase B까지 남긴다.

## 정본 입력

- 정책: `FP-018` 보행 상태·종료·복구
- 요구사항: `RQ-FP-018-001`
- Gap: `GAP-027`, 현재 `CONFLICTING`, P0
- 설계 연결: `DES-01·04·08·10·12·13·14·15·16·18·21·22·23·27`
- 예정 정식 시험: `TC-FP-018-01~06`, 현재 전부 `NOT_RUN`
- 선행 구현: FP-017의 `WalkSessionLifecycle`과 MainActivity 연결

현재 코드는 background 정지·재검사·정확한 “시작” 확인과 일부 process restart 방어를 이미 포함한다. 이 Goal은 r008의 과거 한 줄 관찰만 믿고 기능을 중복 구현하지 않는다. 먼저 현재 bytes에서 FP-018 전체 계약을 재감사하고, 남은 결함만 구현한다.

## 범위와 제외

포함:

- 보행 lifecycle 상태와 `FULL/DISTANCE_LIMITED/UNAVAILABLE` 기능 모드의 분리
- 권한·카메라·위치·거리·모델·음성 입출력·경로·필수 서버·기기 자원 재검사 집계
- 재검사 결과와 정확한 사용자 재개 확인 사이의 차단
- background·잠금·앱 전환·통화 뒤 늦은 비동기 결과 무효화
- process restart·비정상 종료·재부팅 뒤 이전 active walk 자동복원 금지
- 목적지·암호화 미전송 자료와 즉시 폐기할 일시 상태의 구분
- 상태 변화의 이전/다음 상태·원인·시각 기록

제외:

- FP-010~015의 가입·인증·동의 원장 전체 구현
- FP-023 이탈 10단계와 새 경로 선택 전체 구현
- 실제 기기에서 연속 정상 횟수·시간 확정
- 실제 기기·현장·접근성·정식 시험 PASS

## 단계별 실행

1. 정책·Gap·현재 lifecycle 코드·MainActivity·기존 테스트의 차이를 표로 만든다.
2. 다음 실패가 현재 테스트로 잡히는지 확인하고, 빠진 항목은 fail-first 테스트를 추가한다.
   - recheck 일부만 성공했는데 재개되는 경우
   - 이전 generation의 늦은 callback이 새 상태에 반영되는 경우
   - 권한 하나 철회가 무조건 전체 종료되거나, 반대로 안전 필수기능 부재인데 계속되는 경우
   - 거리 제한을 PAUSED/SAFE_STOP과 같은 단일 상태로 섞는 경우
   - process restart 뒤 이전 active 상태·위험판단·현재 위치·경로 안내가 되살아나는 경우
   - 재개 확인 없이 AR/session·센서·안내가 활성화되는 경우
3. 재개 readiness를 한 불변 snapshot으로 집계하고 필요한 항목이 모두 같은 generation에서 통과했을 때만 확인 단계로 보낸다.
4. “시작” 이외 응답·무응답·인식 실패에서는 멈춘 상태를 유지한다.
5. 기능별 권한 철회는 해당 기능만 중지하되, 남은 기능으로 보행 안전을 신뢰할 수 없으면 이유를 알리고 SAFE_STOP으로 전환한다.
6. process restart에서는 새 session ID를 만들고 이전 실시간 판단을 초기화한다. 목적지가 남아 있더라도 사용자 확인과 새 경로 요청 없이는 안내를 시작하지 않는다.
7. targeted JVM test와 MainActivity 정적/구성요소 검사를 실행하고 Android debug build·lint 회귀를 확인한다.
8. 독립 서브에이전트가 정책 누락·자동재개 경로·완료 과장을 재검토한다.
9. append-only 구현 기록, Active overlay, Gap·Backlog successor를 만든다.

## 검증

내부 검증은 최소한 다음 행동을 구분해야 한다.

- 준비 → 모든 필수 검사 통과 → ACTIVE
- 잠금/background → PAUSED/RECHECK_REQUIRED → 재검사 → 사용자 “시작” → ACTIVE
- 거리 기능만 불가 → lifecycle 유지 + DISTANCE_LIMITED, 거리·충돌예상·관련 신고판단 차단
- DISTANCE_LIMITED 중 잠금 → PAUSED와 제한모드가 동시에 표현됨
- 필수 기능 지속 실패 → SAFE_STOP + 이유 안내 + 자동재개 없음
- 앱 오류 종료/process restart → 새 보행, 이전 위험판단·오래된 위치·경로 안내 없음
- 이전 generation callback → 상태 변경 없음
- 공식 정식 시험 수·gate·출시 상태 → `279 NOT_RUN`, `5 NOT_RUN`, `NOT_ELIGIBLE`

검증 명령은 실제 변경 범위에 맞게 선택하되 적어도 `WalkSessionLifecycleTest`, MainActivity lifecycle 정적검사, 관련 앱 JVM test, debug assemble, lint를 포함한다.

## 완료 기준

- FP-018의 저장소 내부 반대 동작이 제거됨
- readiness와 사용자 확인 전에는 보행 관련 runtime이 활성화되지 않음
- process restart 뒤 이전 보행이 자동 복원되지 않음
- lifecycle와 기능 모드가 독립적으로 표현됨
- 새·늦은 callback의 session generation 경계가 검증됨
- targeted와 관련 회귀가 통과함
- 구현 파일·결과 해시, 남은 실제 기기·정식 시험이 기록됨
- `GAP-027`을 현재 근거에 맞게 재평가한 새 Gap·Backlog revision이 존재함

내부 구현만으로 `IMPLEMENTED`, 정식 PASS, gate 종료, 출시 가능을 주장하지 않는다.

## 질문·중단 조건

현재 정책 질문은 없다. 이미 정해진 “기능별 권한 중지”, “안전 필수기능 부재 시 전체 안전정지”, “비정상 종료 뒤 새 보행”, “사용자 확인 전 재개 금지”를 다시 묻지 않는다.

다음 경우에만 Master 정책에 따라 중단한다.

- 기존 유효 정책끼리 실제 충돌해 어느 쪽도 우선순위로 해결할 수 없음
- 현재 사용자 변경과 같은 lifecycle 파일에서 안전하게 병합할 수 없는 충돌
- 민감 원본을 Git에 넣어야만 구현 가능한 설계
- 실제 기기 측정값 없이는 구현 경계를 안전하게 정할 수 없고 보수적 interim rule도 적용 불가

## 완료 후 인계

1. append-only 구현 기록과 Active overlay를 만든다.
2. r008을 수정하지 않고 Gap·Backlog successor를 준비한다.
3. 부모 [`../../epics/epic-02-safe-walk-state-and-permissions.md`](../../epics/epic-02-safe-walk-state-and-permissions.md)의 내부 완료수준과 내부 잔여작업을 재평가한다.
4. 최신 Backlog의 `next_single_action`이 저장소 내부 미완료를 가리키면 Work Item 템플릿으로 다음 Goal `R001`을 준비한다. 정식·외부 증거만 남은 정책은 반복하지 않고 EPIC-12로 이관한다.
5. successor, 다음 Goal, 경로·내용 hash, 전환 event와 새 canonical head용 continuation snapshot 계약을 격리 사전검사한다.
6. 사전검사가 모두 통과한 뒤에만 한 번의 논리적 checkpoint commit으로 이 Goal 완료, canonical `IMPLEMENTATION_GAP/BACKLOG` binding, 다음 Goal 포인터, 파일집합 hash, 전환 이력을 함께 바꾼다.
7. 중간 단계가 실패하면 체크포인트를 바꾸지 않고 준비 파일을 폐기하거나 고친다. commit 뒤 일반 continuation·Goal 검사와 관련 회귀를 통과하면 사용자 질문 없이 다음 Goal을 시작한다.
