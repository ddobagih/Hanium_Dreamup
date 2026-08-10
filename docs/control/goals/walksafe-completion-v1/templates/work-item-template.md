# Work Item Goal 템플릿

이 파일은 Goal discovery 대상이 아니다. 새 Work Item은 이 내용을 복사해 날짜·revision이 붙은 새 파일로 만들고, 이미 실행한 Goal 파일을 덮어쓰지 않는다.

```toml
+++
schema_version = "1.0"
goal_id = "WS-GOAL-{PHASE}-{EPIC}-{POLICY}-R001"
goal_kind = "WORK_ITEM"
document_version = "1.1.0"
phase_id = "{A|B|C|D}"
parent_goal_id = "WS-GOAL-{PHASE}-EPIC-{NN}"
sequence = 1
initial_status = "READY"
target_completion_level = "{INTERNAL_POLICY_CONFORMANCE_REASSESSED|IMPLEMENTATION_READY|FORMAL_VERIFICATION_ACTION_COMPLETE|RELEASE_DELIVERY_ACTION_COMPLETE|HANDOVER_CLOSURE_ACTION_COMPLETE}"
next_goal_id = ""
return_goal_id = "WS-GOAL-{PHASE}-EPIC-{NN}"
work_item_id = "{LATEST_BACKLOG_WORK_ITEM_ID}"
dependencies = []
child_goal_ids = []
source_policy_ids = ["{POLICY_ID}"]
gap_ids = ["{GAP_ID}"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "{IMPLEMENTATION_BACKLOG|PHASE_PLAN}"
materialized_from_path = "{SOURCE_DOCUMENT_PATH}"
materialized_from_document_id = "{SOURCE_DOCUMENT_ID}"
materialized_from_sha256 = "{SOURCE_DOCUMENT_SHA256}"
predecessor_goal_id = "{PREVIOUS_EXECUTED_GOAL_ID_OR_EMPTY}"
predecessor_goal_content_sha256 = "{PREVIOUS_EXECUTED_GOAL_SHA256_OR_EMPTY}"
supersedes_goal_id = "{SAME_WORK_ITEM_PREVIOUS_REVISION_ID_OR_EMPTY}"
supersedes_goal_content_sha256 = "{SUPERSEDED_GOAL_SHA256_OR_EMPTY}"
reopen_reason = "{POLICY_CHANGE_APPROVAL|REGRESSION_DEFECT_RECEIPT|EVIDENCE_REJECTION_RECEIPT|CANONICAL_INPUT_REVISION_RECEIPT|GOAL_INSTRUCTION_CORRECTION_RECEIPT|EMPTY_FOR_R001}"
reopen_evidence_refs = ["{CANONICAL_TRIGGER_RECEIPT_ROLE_OR_DOCUMENT_ID}"]
+++
```

첫 실행에서는 `reopen_reason=""`, `reopen_evidence_refs=[]`로 둔다.

다른 작업으로 넘어가면 새 Goal ID의 `R001`을 만들고 `predecessor_goal_id`로 event 당시 현재 실행 leaf를 연결한다. 같은 `work_item_id`를 다시 열면 `R002` 이상의 successor를 만들고 `supersedes_goal_id`와 이전 파일 SHA-256을 함께 기록한다. 모든 `R002+`는 명시적 `reopen_reason`과 canonical trigger receipt의 role 또는 document ID를 `reopen_evidence_refs`에 반드시 기록한다. 파일 경로나 임의 JSON은 참조할 수 없다.

delayed historical reopen에서는 두 관계가 달라질 수 있다. `predecessor_goal_id`는 reopen event 당시 현재 실행 leaf이고 `supersedes_goal_id`는 과거에 완료한 같은 Work Item revision이다. `supersedes_goal_id`가 있는 successor의 최초 도입 event는 반드시 `GOAL_SUPERSEDED`다. 이 event는 이전/현재 leaf 포인터를 유지하면서 `status_changes`에서 이름으로 지정한 과거 target 하나만 `COMPLETE_AT_TARGET → SUPERSEDED`로 바꾸고 새 successor 하나를 같은 event에서 `PLANNED`로 도입한다. 다른 event에서 successor를 미리 등록하지 않는다. 이때 과거 completion refs는 `archived_completion_evidence_by_goal`로 이동해 보존한다. 새 successor를 실행 leaf로 바꾸는 것은 그 뒤 별도 `GOAL_TRANSITION`으로 처리하며 두 경우 모두 과거 Goal 파일은 수정하지 않는다.

허용되는 trigger receipt 유형은 정확히 `POLICY_CHANGE_APPROVAL`, `REGRESSION_DEFECT_RECEIPT`, `EVIDENCE_REJECTION_RECEIPT`, `CANONICAL_INPUT_REVISION_RECEIPT`, `GOAL_INSTRUCTION_CORRECTION_RECEIPT` 다섯 가지다. receipt는 canonical binding의 document ID와 일치하고, 효력 상태 `APPROVED/ACCEPTED/CONFIRMED`, superseded 대상 Goal ID, 같은 Work Item ID, 결정 시각, approver의 ID·role·authority·승인 결정/시각을 가져야 한다. `target_completion_event_sha256`은 대상 과거 Goal의 실제 `GOAL_COMPLETED` event SHA-256과 정확히 같고 `target GOAL_COMPLETED.occurred_at ≤ decided_at ≤ GOAL_SUPERSEDED.occurred_at`이어야 한다. 단순 재시도, 미실행 외부 증거 대기, 일정 변경은 reopen 사유가 아니다.

reopen approver와 blocker resolver는 pinned canonical `AUTHORITY_ROSTER`의 actor여야 한다. receipt의 ID·role·authority·evidence type이 roster의 허용값과 일치하고 `authority_evidence_ref="AUTHORITY_ROSTER"`를 가져야 한다. receipt 자체에도 `authority_roster_ref="AUTHORITY_ROSTER"`, `authority_roster_document_id`, `authority_roster_sha256`을 기록하며 roster `approved_at`은 결정 시각보다 앞선다. v1.1 roster를 제자리에서 바꾸지 않는다.

### Phase별 front matter 조건

| 사용 경우 | `parent_goal_id` | `materialized_from_role` | 정책·Gap | 목표 완료수준과 필수 완료 증거 |
|---|---|---|---|---|
| Phase A 구현 Work Item | 소유 `WS-GOAL-A-EPIC-{NN}` | `IMPLEMENTATION_BACKLOG` | 각각 정확히 1개 | `INTERNAL_POLICY_CONFORMANCE_REASSESSED` 또는 `IMPLEMENTATION_READY` |
| Phase B 정식 검증 Work Item | `WS-GOAL-B-EPIC-12` | 정책별이면 `IMPLEMENTATION_BACKLOG`, 검증 실행계획이면 `PHASE_PLAN` | 정책 행동이면 각각 정확히 1개 | `FORMAL_VERIFICATION_ACTION_COMPLETE` |
| Phase C phase-direct Work Item | `WS-GOAL-PHASE-C` | `PHASE_PLAN` | 특정 정책·Gap을 직접 해결할 때만 각각 1개, 아니면 빈 배열 | `RELEASE_DELIVERY_ACTION_COMPLETE` |
| Phase D phase-direct Work Item | `WS-GOAL-PHASE-D` | `PHASE_PLAN` | 특정 정책·Gap을 직접 해결할 때만 각각 1개, 아니면 빈 배열 | `HANDOVER_CLOSURE_ACTION_COMPLETE` |

이 표의 문자열만 Work Item `target_completion_level`에 허용한다. Phase Goal 자체의 `IMPLEMENTATION_READY`, `VERIFICATION_COMPLETE`, `RELEASE_DELIVERED`, `OPERATIONS_HANDOVER_AND_PROJECT_CLOSURE`를 B/C/D Work Item에 복사하지 않는다.

정책/Gap pair는 `source_policy_ids`와 `gap_ids`의 같은 배열 위치를 zip해 만들지 않는다. canonical `IMPLEMENTATION_GAP` JSON의 `assessments`에서 유일한 `source_policy_id → gap_id` mapping을 만들고, 정책을 가진 Work Item은 그 mapping의 정확한 pair 하나를 사용한다. EPIC 완료 커버리지 역시 EPIC 소유 정책을 이 mapping으로 조회해 계산한다.

Phase C·D는 소유 EPIC이 없으므로 가짜 EPIC 부모를 만들지 않는다. v1.1의 `PHASE_PLAN` source는 현재 정적 manifest가 보호하는 해당 Phase Goal의 정확한 문서 ID·경로·SHA-256에만 결속한다. README, 임의 메모, 다른 Phase 파일이나 승인되지 않은 plan revision을 source로 사용하지 않는다. 정적 Phase 계획을 바꿔야 하면 새 버전 manifest·trust anchor·successor checker가 승인될 때까지 Work Item을 만들지 않는다.

Phase C·D Work Item은 한 번에 Phase 전체를 완료했다고 주장하지 않는다. 예를 들어 배포 준비, 실제 배포, 기술자료 전달, 운영 통제 수행, 책임 이관, 프로젝트 종료를 필요한 만큼 독립 Work Item으로 나눈다. 일정·담당자만 승인된 외부 행동은 `PARTIAL/AWAITING_*`이며 실제 수행 receipt 전에는 완료하지 않는다.

Phase C Work Item은 전체 실행 원장이 `deployment start → canary → smoke → deployment end → technical delivery acceptance` 순서를 깨뜨리지 않게 범위를 잡는다. Phase D Work Item은 `Phase C acceptance → handover checks/decisions → handover acceptance → closure start → closure checks/decisions → closed` 순서를 깨뜨리지 않게 하고, 뒤 행동을 먼저 완료 처리하지 않는다.

Phase B/C/D의 상위 완료 receipt, `REOPEN_TRIGGER::<target_goal_id>`, `BLOCKER_RESOLUTION::<blocker_id>`는 `AUTHORITY_ROSTER` 일치만으로 효력이 생기지 않는다. 독립 검토와 서명 또는 외부 시스템 anchor를 마친 정확한 receipt 파일 SHA-256이 버전 관리되는 checker의 `EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE`에 정확한 role별로 고정돼야 한다. 실제 receipt가 없어 map이 비어 있는 것은 정상적인 fail-closed 상태이며, 실제 승인 receipt만 별도 검토한 checker 변경으로 추가한다.

transition history append는 체크포인트의 `transition_history_anchor_sha256` head 자기참조만 갱신해서 끝내지 않는다. 현재 검토 완료 head가 checker의 `EXPECTED_TRANSITION_HISTORY_HEAD_SHA256`과 일치해야 하며, 새 event마다 기존 head와 새 head를 독립 보존·검토한 뒤 versioned checker anchor를 갱신한다. 과거 checker 버전과 event는 수정하지 않는다.

### Work Item 완료 receipt 계약

`materialized_from_*` source plan은 생성 계보일 뿐 완료 증거가 아니다. Phase와 무관하게 Work Item을 완료하려면 해당 Goal evidence에 canonical role `WORK_ITEM_COMPLETION::<goal_id>`가 정확히 하나 있어야 한다. binding 대상 JSON의 `evidence_type`은 `WORK_ITEM_EXECUTION_RECEIPT`, `status=ACCEPTED`, `result=PASS`다.

receipt는 `target_goal_id`·`target_goal_content_sha256`, `work_item_id`, `source_policy_ids`·`gap_ids`, Phase별 정확한 `target_completion_level`, 가장 최근에 이 Goal을 `IN_PROGRESS`로 만든 `execution_start_event_sha256`을 Goal front matter와 정확히 일치시킨다. `execution_window.started_at/ended_at`은 유효한 시각이고 시작은 해당 execution start event `occurred_at`보다 이르지 않아야 한다. 시간은 `execution_window.started_at ≤ completed_at ≤ execution_window.ended_at ≤ reviewer.decided_at ≤ generated_at ≤ GOAL_COMPLETED.occurred_at` 순서여야 한다. 다음 successor의 실행 시작 event `occurred_at`은 predecessor receipt `generated_at`보다 이르지 않아야 한다.

`result_evidence`에는 서로 다른 실제 JSON 경로의 다음 세 kind를 각각 정확히 하나씩 둔다.

- `IMPLEMENTATION_RECORD`
- `VERIFICATION_RESULT`
- `SUCCESSOR_TRACE`

각 파일은 자신의 path/SHA-256과 일치하고 `goal_id`가 완료 Goal, `kind`가 선언 kind, `status=PASS`여야 한다. `observed_at`은 receipt 실행 구간 안이고 `completed_at`보다 늦지 않아야 한다. Goal 파일이나 source plan을 이 세 결과 파일로 재사용하지 않는다.

`GOAL_COMPLETED` event의 `completion_receipt_binding`은 이 canonical binding의 `role`, `document_id`, `path`, `file_sha256` snapshot과 정확히 같아야 한다. event `evidence_refs`는 같은 event에서 완료되는 leaf와 eligible ancestor가 가진 completion evidence의 정확한 union이어야 하며, Work Item role을 누락하거나 임의 ref를 더하지 않는다.

Phase B/C/D required receipt를 이 Work Item·Phase·Master completion evidence로 올리면 각 receipt `execution_window.started_at`은 해당 Phase가 가장 최근 `IN_PROGRESS`가 된 event의 `occurred_at` 이상이고, `generated_at`은 해당 `GOAL_COMPLETED.occurred_at` 이하여야 한다. Phase C `technical_delivery.accepted_at`, Phase D handover `accepted_at`과 project closure `closed_at`도 해당 Phase/Master 완료 event보다 늦을 수 없다. Phase B와 EPIC-12의 completion evidence 및 `verification_evidence_refs`는 `TEST_PLAN_APPROVAL_RECEIPT`, `FORMAL_TEST_REPORT`, `ACTUAL_DEVICE_TEST_REPORT`, `RELEASE_GATE_CLOSURE_RECEIPT`, `RELEASE_ELIGIBILITY_APPROVAL`의 정확한 집합이어야 한다.

모든 transition event의 `occurred_at`은 직전 event보다 엄격히 크고 checker·체크포인트에 함께 고정된 `validation_cutoff_at` 이하여야 한다. cutoff 뒤 실제 사건은 검토된 새 head와 `EXPECTED_VALIDATION_CUTOFF_AT`을 함께 전진시킨 다음 checkpoint revision에서만 기록한다.

새 문서 본문에는 아래 제목을 정확히 한 번씩 둔다.

## 목표

- 최신 Backlog action을 그대로 복사하지 말고 정책상 결과와 사용자 영향을 쉬운 말로 설명한다.
- `target_completion_level`과 완료해도 주장하지 않는 상위 결과를 적는다.

## 정본 입력

- canonical role로 정책·receipt·register·RTM·DES·TC·최신 Gap·Backlog를 찾는다.
- 정책 본문의 적용 규칙, Gap의 현재 근거, 요구 ID, 설계 ID, 시험 ID를 적는다.
- action identity를 구성하는 Phase·부모·Work Item ID·목표수준·정책·Gap·materialization 문서 ID/SHA-256을 적고 기존 Goal과 중복되지 않는지 확인한다.
- reopen이면 이전 완료 Goal과 trigger receipt의 canonical role/document ID·binding SHA-256·이전 판정을 무효화한 이유를 적는다.

## 범위와 제외

- 변경할 기능과 직접 영향 범위
- 이번 Work Item에서 하지 않을 후속 정책·정식시험·외부작업

## 단계별 실행

1. 현재 Gap을 재현하거나 판정 가능한 실패검사를 만든다.
2. 관련 코드·기존 패턴·사용자 변경을 조사한다.
3. 최소 구현을 한다.
4. targeted·구성요소·안전 회귀를 실행한다.
5. 독립 내부 검토를 수행한다.
6. 실행 기록·Active 원장·Gap·Backlog successor를 갱신한다.

## 검증

- 구체 명령과 예상 결과
- 정책·REQ·DES·TC 추적
- 정식 시험·gate·출시 비승격

## 완료 기준

- 내부 완료조건
- 남겨야 할 외부·정식 증거
- 부모 EPIC 완료 또는 다음 Work Item 선택 기준
- legacy 완료 EPIC이 아니면 대체되지 않은 최종 sibling Work Item 전체가 canonical `IMPLEMENTATION_GAP.assessments` mapping으로 계산한 부모 EPIC의 모든 정책/Gap pair를 각각 정확히 한 번 덮고 완료됐는지 확인하는 기준
- `WORK_ITEM_COMPLETION::<goal_id>` receipt 하나와 Goal·target·pair·최신 시작 event·시간·세 typed result JSON을 확인하는 기준
- Phase C·D이면 해당 canonical receipt에 들어갈 실제 check와 원자료를 적고, receipt 전체가 없을 때 주장 가능한 부분 완료수준을 적는다.

## 질문·중단 조건

- Master 상속
- 이 Work Item에 특화된 새로운 결정·외부 자원·안전 blocker
- 대기 전 blocker ID·`blocks_goal_id`·condition code·owner·prompt·생성 시각·정확한 return leaf·snapshot SHA-256을 `BLOCKER_RECORDED`와 전체 `blockers_after`에 보존하는 절차
- owner에 맞는 canonical `DECISION_RECEIPT`·`EXTERNAL_EVIDENCE_RECEIPT`·`BLOCKER_REMEDIATION_RECEIPT`를 검증하는 절차. receipt와 resolution history 양쪽의 `blocker_event_sha256`·`blocker_snapshot_sha256`이 원 event/snapshot과 정확히 같고 `blocker.created_at ≤ BLOCKER_RECORDED.occurred_at`, `blocker.created_at ≤ receipt.decided_at ≤ BLOCKER_RESOLVED.occurred_at`이어야 함
- blocker snapshot을 `BLOCKER_RECORDED/RESOLVED` 외 event에서 변경하지 않고 기록 ID를 active 또는 typed resolution으로 보존하는 절차
- `BLOCKER_RESOLVED.resolved_blocker_ids`가 이번에 제거하는 활성 blocker 집합, `evidence_refs`가 그 blocker들의 resolution receipt refs 정확한 집합이며, `blocker_resolution_ids_after`에 같은 ID를 누적하는 절차
- 우회가 없으면 미소비 해당-leaf resolution 전체로 같은 leaf를, 우회했다면 historical/runtime bypass의 정확한 return goals·leaf·blocker IDs 전체를 해제한 뒤 정확한 return leaf를 `GOAL_RESUMED`로 한 번만 재개하는 절차

## 완료 후 인계

- 고정할 구현 기록과 successor
- 부모 Goal로 복귀해 최신 Backlog의 `next_single_action` 중 저장소 내부 미완료가 남은 다음 Work Item을 선택하는 절차
- successor와 다음 Work Item 파일을 먼저 준비하고 격리 사전검사하는 절차
- 새 canonical head에 맞는 continuation snapshot 계약과 회귀 fixture를 준비하는 절차
- `GOAL_COMPLETED` 뒤 별도 leaf 전환 event를 만들고 `status_changes`·전체 `pointers_after`·`blockers_after`·누적 resolution IDs·`bypass_after`를 체크포인트 한 번의 논리적 commit으로 바꾸는 절차
- 모든 event에 timezone 포함 `occurred_at`과 같은 날짜의 `occurred_on`을 기록하고 event마다 `occurred_at`을 엄격히 증가시키는 절차
- append 뒤 새 transition history head를 독립 보존·검토하고 `EXPECTED_TRANSITION_HISTORY_HEAD_SHA256`을 versioned checker 변경으로 갱신하는 절차. checkpoint 자기참조만으로 완료하지 않음
- `GOAL_COMPLETED.status_changes`는 current leaf/eligible ancestor, `GOAL_SUPERSEDED`는 named 과거 target 하나와 새 successor `PLANNED`, blocker event는 정확한 blocker 대상으로 제한하고 sibling bulk 완료를 금지하는 절차
- historical reopen이면 현재 leaf를 유지한 `GOAL_SUPERSEDED` 뒤 별도 `GOAL_TRANSITION`으로 successor를 실행하는 절차
- 동적 Work Item을 한 event에 하나만, 직전 leaf predecessor와 함께 `PLANNED` 또는 직접 전환 상태로 도입하는 절차
- 같은 action identity의 Goal이 이미 존재하면 새로 만들지 않고 재개하거나 다음 action을 선택하는 절차
