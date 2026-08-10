+++
schema_version = "1.0"
goal_id = "WS-GOAL-WALKSAFE-COMPLETION-V1"
goal_kind = "MASTER"
document_version = "1.1.0"
phase_id = ""
parent_goal_id = ""
sequence = 0
initial_status = "READY"
target_completion_level = "PROJECT_ACCEPTED_AND_HANDOVER_OR_CLOSURE_COMPLETE"
next_goal_id = "WS-GOAL-PHASE-A"
return_goal_id = ""
work_item_id = ""
dependencies = []
child_goal_ids = ["WS-GOAL-PHASE-A", "WS-GOAL-PHASE-B", "WS-GOAL-PHASE-C", "WS-GOAL-PHASE-D"]
source_policy_ids = []
gap_ids = []
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_condition_codes = ["POLICY_CHANGE_REQUIRED", "PRODUCT_DIRECTION_AMBIGUITY", "IRREVERSIBLE_EXTERNAL_ACTION", "SECRET_OR_PAID_RESOURCE_REQUIRED", "REAL_DEVICE_OR_PARTICIPANT_REQUIRED", "AUTHORITY_EXPANSION_REQUIRED"]
stop_condition_codes = ["CANONICAL_INPUT_VALIDATION_FAILED", "DEPENDENCY_NOT_COMPLETE", "POLICY_CONFLICT", "SAFETY_OR_SECURITY_CRITICAL_FAILURE", "USER_CHANGE_OVERLAP", "EXTERNAL_EVIDENCE_REQUIRED"]
+++

# Master Goal — WalkSafe 프로젝트 자율 완성

## 목표

승인된 WalkSafe 기능 정책 기준선 1.0.1과 257개 산출물을 실제 개발·검증·릴리스·운영·이관에 사용해 프로젝트를 끝까지 진행한다. “문서를 작성했다”를 완료로 보지 않고 아래 네 단계의 서로 다른 완료수준을 순서대로 충족한다.

```text
A. EPIC-01~11 구현 준비
  → B. EPIC-12 정식 검증과 출시 적격 판단
  → C. 승인된 릴리스·배포·인도
  → D. 운영 안정화·이관·프로젝트 종료
```

현재 출발점은 `EPIC-02 / FP-018 / GAP-027`이다. EPIC-01은 `IMPLEMENTATION_READY`, FP-017 내부 단계는 구현됐지만 `GAP-026=PARTIAL`, 정식 시험 279개와 실제 기기 검증은 전부 `NOT_RUN`, 5개 gate는 미면제, 출시는 `NOT_ELIGIBLE`이다.

## 정본 입력

판정 우선순위는 다음과 같다.

1. COMMITTED 승인 적용 receipt
2. 유효 정책 기준선 1.0.1
3. DOC-01 현재 상태
4. DOC-05 변경 이력
5. 최신 Gap·Backlog successor
6. RTM·설계 추적·시험 원장
7. 현행 코드와 내부 실행 결과
8. 과거 계획·PWA·제출용 문서

Goal 문서는 정책 정본이 아니다. 경로가 바뀐 최신 Gap·Backlog는 체크포인트의 `IMPLEMENTATION_GAP`, `IMPLEMENTATION_BACKLOG` canonical role로 찾는다.

이 v1.1 검수본이 활성화된 뒤 `static-plan-manifest-v1.1.0.json`이 Master·Phase·EPIC 정적 파일을 실행계획 기준점으로 고정한다. 최초 `PACKAGE_PREPARED` event는 manifest SHA-256을 참조하고 검사기의 최초 event trust anchor와 일치해야 한다. v1.1은 실행 중 plan revision을 직접 지원하지 않는다. 정적 계획 변경이 필요하면 `AWAITING_USER`로 멈추고 새 버전 manifest·승인된 새 trust anchor·그 버전을 이해하는 successor checker가 모두 마련될 때까지 기존 계획을 바꾸지 않는다. 미래 successor checker도 과거 event는 당시 manifest로 검증하며 과거 이력을 재작성하지 않는다. Work Item 실행 successor는 이 정적 plan revision과 별개다.

append-only transition chain은 시작점과 현재 검토 head를 모두 고정한다. 최초 event는 `EXPECTED_INITIAL_TRANSITION_EVENT_SHA256`, 현재 독립 검토가 끝난 마지막 event는 버전 관리되는 checker의 `EXPECTED_TRANSITION_HISTORY_HEAD_SHA256`과 일치해야 한다. 체크포인트의 `transition_history_anchor_sha256` 자기참조만으로는 이력 불변성을 증명하지 못한다. 합법적 append마다 기존 head와 새 event를 독립 보존·검토한 뒤 새 head를 versioned checker anchor로 갱신하고, 과거 checker 버전·head·event는 그대로 보존한다.

## 범위와 제외

자율 실행 범위:

- 저장소 안의 코드·설정·테스트·문서 변경
- 승인 정책을 구현하기 위한 기술 세부 결정과 ADR
- 내부 검증과 독립적인 서브에이전트 재검토
- Draft 작성과 Active 원장의 사실 기록
- append-only 실행 기록, Gap·Backlog successor
- Goal·체크포인트·daylog·local-memory 인계 갱신
- 의존성이 충족된 다음 Goal 자동 시작

사전 포괄 권한에 포함되지 않는 범위:

- 정책·보존기간·동의·사용자 경험의 규범 변경
- 미래 산출물의 `Approved/Baselined` 자동 승격
- 5개 gate 면제 또는 정식 시험 PASS 조작
- 실제 기기·참여자·독립 전문검토를 내부 테스트로 대체
- 유료 자원 생성, 비밀정보 사용, 프로덕션 배포, 외부 통지, 실제 데이터 삭제
- 출시·인수·운영 이관·프로젝트 종료 서명 대행

## 단계별 실행

### 공통 작업 루프

각 Work Item은 다음 순서로 처리한다.

1. 체크포인트와 canonical binding 무결성을 검사한다.
2. 최신 Backlog에서 현재 작업·정책·Gap·의존성을 확인한다.
3. 정책 본문 → 요구사항·인수조건 → 설계 → 예정 시험 → 코드 순으로 추적한다.
4. 현재 오동작을 재현하거나 합격 여부를 구분할 fail-first 검증을 만든다.
5. 승인 정책을 만족하는 최소 변경을 구현한다.
6. targeted → 관련 구성요소 → 안전한 회귀 순서로 검증한다.
7. 구현과 별개인 내부 재검토를 수행한다.
8. 구현 결과를 서로 다른 typed JSON `IMPLEMENTATION_RECORD`, `VERIFICATION_RESULT`, `SUCCESSOR_TRACE`로 남기고 Active 원장에 실제 사실만 반영한다.
9. 과거 Gap·Backlog를 보존하고 새 successor에서 재평가한다.
10. source plan과 `materialized_from_*` 계보만으로 완료 처리하지 않는다. `WORK_ITEM_COMPLETION::<goal_id>` canonical binding에 `WORK_ITEM_EXECUTION_RECEIPT`를 정확히 하나 등록하고 Goal SHA-256·Work Item ID·Phase별 목표수준·canonical 정책/Gap pair·최신 실행 시작 event·실행 시간과 8단계의 세 typed JSON을 결속한다.
11. 부모 EPIC의 내부 완료 여부를 판정하고 최신 Backlog `next_single_action`에서 다음 저장소 내부 작업을 고른다. Phase·Work Item·목표수준·정책·Gap·materialization 문서 ID/SHA-256으로 action identity를 계산하고, 기존 Goal과 같으면 새로 만들지 않는다. 정식·외부 증거만 남은 항목은 EPIC-12로 이관한다.
12. successor, 다음 Work Item Goal, hash, 전환 event와 새 canonical head용 continuation snapshot 계약을 먼저 준비해 격리 사전검사한다.
13. `GOAL_COMPLETED.completion_receipt_binding`에 10단계 canonical binding의 `role/document_id/path/file_sha256` snapshot을 넣고, 완료되는 Goal들의 증거 union과 event `evidence_refs`를 정확히 일치시킨다. 이전 Goal 완료·canonical binding·새 포인터·파일집합 hash·전환 이력을 체크포인트 한 번의 논리적 commit으로 전환한다. 실패하면 체크포인트는 이전 상태를 유지한다.
14. 일반 continuation 검사·Goal 검사·관련 회귀를 통과시킨 뒤 체크포인트·daylog·local-memory를 갱신한다.
15. EPIC이 완료되면 의존성 순서의 다음 Goal을 질문 없이 시작한다.

Work Item의 `target_completion_level`은 Phase별로 다음 값만 허용한다.

| Phase | 허용 Work Item 목표 |
|---|---|
| A | `INTERNAL_POLICY_CONFORMANCE_REASSESSED` 또는 `IMPLEMENTATION_READY` |
| B | `FORMAL_VERIFICATION_ACTION_COMPLETE` |
| C | `RELEASE_DELIVERY_ACTION_COMPLETE` |
| D | `HANDOVER_CLOSURE_ACTION_COMPLETE` |

Phase Goal 자체의 `IMPLEMENTATION_READY`, `VERIFICATION_COMPLETE`, `RELEASE_DELIVERED`, `OPERATIONS_HANDOVER_AND_PROJECT_CLOSURE`와 Work Item 목표 enum을 섞지 않는다.

`COMPLETE_AT_TARGET` 행동은 다섯 typed receipt 중 하나가 이전 완료를 무효화할 때만 다시 연다: `POLICY_CHANGE_APPROVAL`, `REGRESSION_DEFECT_RECEIPT`, `EVIDENCE_REJECTION_RECEIPT`, `CANONICAL_INPUT_REVISION_RECEIPT`, `GOAL_INSTRUCTION_CORRECTION_RECEIPT`. receipt는 canonical role/document ID, 효력 상태, 대상 Goal·Work Item, approver 권한을 검증하고 `target_completion_event_sha256`을 과거 target의 실제 완료 event에 정확히 결속한다. trigger `decided_at`은 target `GOAL_COMPLETED.occurred_at` 이상이고 이를 적용하는 `GOAL_SUPERSEDED.occurred_at` 이하여야 한다.

delayed historical reopen에서는 `supersedes_goal_id`가 과거 완료 revision을, 새 successor의 `predecessor_goal_id`가 event 당시 현재 실행 leaf를 가리킨다. `supersedes_goal_id`가 있는 successor의 최초 도입 event는 반드시 `GOAL_SUPERSEDED`다. 이 event는 현재 leaf 포인터를 유지한 채 이름으로 지정한 과거 target 하나만 `SUPERSEDED`로 바꾸고 새 successor 하나를 `PLANNED`로 도입한다. 다른 event에서 successor를 미리 등록하지 않는다. 과거 완료 증거는 `archived_completion_evidence_by_goal`로 보존하며 새 successor 실행 전환은 별도 event로 한다.

정책/Gap pair는 EPIC의 `source_policy_ids`와 `gap_ids` 배열을 위치별로 zip하지 않는다. canonical `IMPLEMENTATION_GAP.assessments`의 유일한 `source_policy_id → gap_id` mapping만 사용한다. 정적 `initial_status=COMPLETE_AT_TARGET` legacy EPIC 외에는 대체되지 않은 최종 Work Item이 이 mapping으로 계산한 EPIC의 모든 pair를 하나씩 정확히 덮고 모두 완료돼야 EPIC을 닫을 수 있다. sibling·누락·중복·여러 pair를 묶은 bulk 완료는 허용하지 않는다.

모든 Work Item 완료 receipt는 `status=ACCEPTED`, `result=PASS`이고 Goal ID/파일 SHA-256, Work Item ID, 정확한 target enum, 정책/Gap 배열, 최신 `execution_start_event_sha256`, `execution_window` 안의 `completed_at`, reviewer 승인과 생성 시각을 결속해야 한다. 실행 구간 시작은 시작 event `occurred_at`보다 이르지 않고 receipt의 `completed_at`과 `generated_at`은 해당 `GOAL_COMPLETED.occurred_at`보다 늦지 않아야 한다. 다음 successor의 실행 시작 event는 predecessor receipt `generated_at`보다 이르지 않아야 한다. `result_evidence`에는 같은 Goal ID와 실행 구간을 가진 `IMPLEMENTATION_RECORD`, `VERIFICATION_RESULT`, `SUCCESSOR_TRACE` typed JSON을 서로 다른 경로로 각각 정확히 하나씩 넣는다. source plan은 이 receipt를 대신하지 않는다.

Phase B/C/D required receipt도 해당 Phase 또는 Master를 닫는 `GOAL_COMPLETED` 이전에 최종 확정한다. 각 receipt의 `execution_window.started_at`은 해당 Phase Goal을 가장 최근 `IN_PROGRESS`로 만든 event의 `occurred_at` 이상이어야 한다. completion evidence로 사용되는 모든 required receipt의 `generated_at`은 해당 완료 event `occurred_at` 이하여야 한다. Phase C `technical_delivery.accepted_at`, Phase D handover `accepted_at`과 project closure `closed_at`도 같은 Phase/Master 완료 event 시각을 넘을 수 없다.

Phase B와 EPIC-12를 완료할 때 두 Goal의 completion evidence와 `verification_evidence_refs`는 각각 `TEST_PLAN_APPROVAL_RECEIPT`, `FORMAL_TEST_REPORT`, `ACTUAL_DEVICE_TEST_REPORT`, `RELEASE_GATE_CLOSURE_RECEIPT`, `RELEASE_ELIGIBILITY_APPROVAL`의 정확한 다섯 role 집합이어야 한다. 마지막 Phase B Work Item을 함께 닫는 event는 이 집합과 그 Work Item의 `WORK_ITEM_COMPLETION::<goal_id>`를 포함해 같은 event에서 완료되는 Goal 증거의 정확한 합집합만 기록한다.

각 논리적 전환 event는 hash chain뿐 아니라 `status_changes`, 전체 `pointers_after`, 전체 `blockers_after`, 누적 `blocker_resolution_ids_after`, `bypass_after`를 남긴다. 모든 event는 timezone 포함 `occurred_at`과 같은 날짜의 `occurred_on`을 가지며 `occurred_at`은 직전 event보다 엄격히 증가하고 checker와 체크포인트에 함께 고정된 `validation_cutoff_at` 이하여야 한다. 새 실제 사건을 반영할 때만 검토된 head와 `EXPECTED_VALIDATION_CUTOFF_AT`을 같은 revision으로 전진시키며 미래 사건을 미리 기록하지 않는다. 이 snapshot을 첫 event부터 replay한 값이 현재 체크포인트와 일치해야 한다. Work Item 완료는 같은 current leaf의 `GOAL_COMPLETED`, reopen 대상 폐쇄는 named historical target도 허용하는 `GOAL_SUPERSEDED`, 다음 실행 leaf 이동은 별도 `GOAL_TRANSITION`으로 기록한다. `GOAL_COMPLETED`는 current leaf와 eligible ancestor, `GOAL_SUPERSEDED`는 named target 하나와 새 successor `PLANNED`, blocker event는 정확한 blocker 대상만 바꾸며 sibling을 bulk 변경하지 않는다. 동적 Work Item은 한 event에 하나만 도입하고 event 당시 current leaf를 predecessor로 삼는다. 활성화 전 실행, 중복 `PACKAGE_ACTIVATED`, 미완료 자식을 가진 부모의 중간 완료, 완료 event 없는 leaf 건너뛰기, 최종 `PACKAGE_COMPLETED` 뒤 event 추가를 금지한다.

### 병렬 팀 운용

복잡한 Work Item은 가능한 경우 다음 세 역할로 나눈다.

- 정책·REQ·DES·시험 추적과 오염 점검
- 코드·테스트 구현
- 독립 정합성·안전·완료과장 검토

같은 파일을 동시에 편집할 가능성이 있으면 하위 에이전트는 조사·검증만 수행하고 최종 편집과 통합은 한 에이전트가 맡는다. 서브에이전트 검토는 내부 품질검사이며 사람 승인이나 독립 전문검토를 대신하지 않는다.

## 검증

매 Work Item의 최소 검증은 다음과 같다.

- 변경 전 실패 또는 현재 Gap을 구분하는 재현 근거
- 변경 후 targeted test
- 관련 앱·서버·모델·계약 구성요소 검사
- 정책·REQ·DES·TC 추적 누락 검사
- 정식 시험·gate·출시 상태 비승격 검사
- Goal 패키지와 체크포인트 검사

실제 실행하지 않은 검사는 `PASS`라고 쓰지 않는다. 저장소 내부 검증은 `INTERNAL_VERIFIED` 범위만 주장한다.

## 완료 기준

Master Goal은 A~D가 각자 요구하는 외부·내부 증거를 실제로 갖추고 다음을 모두 만족할 때만 완료한다.

- 구현 기준과 실제 코드를 연결한 Gap이 목표 수준에서 닫힘
- 하나의 불변 출시 후보로 적용되는 정식 시험이 실행됨
- 실제 기기·현장·접근성·보안·개인정보 검증이 완료됨
- 5개 gate가 면제 없이 실제 증거로 닫힘
- 시험 실행 전에 승인·외부 고정된 `TEST_PLAN_APPROVAL_RECEIPT`와 실행자·검토자·승인자·279개 집계·5개 gate·원자료가 같은 후보 SHA-256에 결속된 나머지 네 Phase B 정본 증거가 존재함
- `PHASE_C_TECHNICAL_DELIVERY_RECEIPT`가 승인 범위의 실제 릴리스·배포·smoke·canary·rollback과 기술자료 수령을 증명함
- `PHASE_D_OPERATION_HANDOVER_RECEIPT`가 운영 계정·권한·지원·복구·비용·잔여위험의 실제 책임 이전을 증명함
- `PHASE_D_PROJECT_CLOSURE_RECEIPT`가 프로젝트 종료 또는 지속운영 이관을 실제 권한자가 승인했음을 증명함

Phase C는 `deployment start → canary → smoke → deployment end → technical delivery acceptance`를, Phase D는 `Phase C acceptance → handover checks/decisions → handover acceptance → closure start → closure checks/decisions → closed`를 시간 증거로 재현해야 한다. 각 Phase의 receipt와 check가 모두 있어도 이 순서가 어긋나면 완료가 아니다.

Phase A의 `COMPLETE_AT_TARGET`은 구현 준비일 뿐 Master 완료가 아니다. Phase B·C·D도 앞 단계의 증거 없이 건너뛰지 않는다.

Phase C의 기술자료 전달은 배포된 bytes와 설명서·운영자료를 정확한 수령자에게 전달한 기술적 사건이다. Phase D의 운영 이관은 계정·권한·지원·복구 의무와 의사결정 책임을 실제 소유자에게 넘기는 책임 이전이다. 앞 사건만으로 뒤 사건을 완료 처리하지 않는다. 에이전트가 스스로 작성한 receipt JSON은 구조 검증 봉투일 뿐 실제 플랫폼 로그, 원자료, 외부 실행자와 권한 있는 승인자의 기록을 대신하지 않는다. 사람·프로덕션·외부 시스템의 실제성을 현재 저장소에서 직접 확인할 수 없고 signed/externally anchored authority evidence도 없으면 `AWAITING_EXTERNAL`과 `NOT_ELIGIBLE`을 유지한다.

Phase B/C/D 완료 receipt와 `REOPEN_TRIGGER::<target_goal_id>`, `BLOCKER_RESOLUTION::<blocker_id>` 권한 receipt는 `AUTHORITY_ROSTER` 검증만으로 효력이 생기지 않는다. 독립 검토와 서명 또는 외부 시스템 anchor가 끝난 정확한 receipt 파일 SHA-256을 버전 관리되는 Goal checker의 `EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE`에 정확한 role별로 고정해야 한다. 각 receipt는 `authority_roster_ref=AUTHORITY_ROSTER`, `authority_roster_document_id`, `authority_roster_sha256`으로 사용 당시 roster를 결속하며 roster `approved_at`은 권한 사용보다 앞선다. v1.1 roster는 제자리 갱신하지 않고 predecessor를 보존한 successor version으로만 바꾼다. 실제 receipt가 아직 없을 때 이 map이 비어 있는 것은 의도된 fail-closed 상태다. 미래에 실제 승인된 receipt만 검토된 checker 코드 변경으로 추가하며 임시·예상 hash나 에이전트가 자체 생성한 hash를 넣지 않는다.

## 질문·중단 조건

기존 답변이나 확정 정책을 다시 묻지 않는다. 질문은 front matter의 `question_condition_codes` 중 하나가 실제로 발생하고, 저장소 근거·안전한 기본값·독립 작업을 모두 검토한 뒤에도 현재 임계경로를 진행할 수 없을 때만 한다.

질문에는 다음을 한 번에 포함한다.

- 새로 필요한 결정이나 외부 행동
- 이미 확인한 정본과 중복 질문이 아닌 이유
- 권고안과 다른 선택지의 영향
- 답이 없어도 계속할 수 있는 독립 작업
- 답변 뒤 정확히 재개할 Goal

front matter의 `stop_condition_codes`가 발생하면 해당 branch는 fail-closed한다. 다른 독립 branch가 있으면 계속 진행한다. blocker에는 고유 ID·`blocks_goal_id`·condition code·owner·prompt·생성 시각·정확한 return leaf·snapshot SHA-256을 기록하고 owner와 상태를 `USER/AWAITING_USER`, `EXTERNAL/AWAITING_EXTERNAL`, `BLOCKED/BLOCKED`로 일치시킨다. 모든 branch가 막히면 정지 Goal과 action identity·근거·필요 receipt·잔여위험을 묶어 대기한다.

EPIC 순서는 정상 상황의 기본 우선순위다. 현재 Goal이 기다리는 동안에는 의존성이 모두 완료된 후속 EPIC만 임시로 진행할 수 있다. `GOAL_BYPASSED` event와 runtime snapshot은 임시 current Goal, 건너뛴 미완료 EPIC 전체, 정확한 return/blocked leaf, 해당 범위의 모든 blocker ID와 `resolution_required=true`를 동일하게 보존한다. 임시 Work Item의 predecessor는 return leaf이며 미완료 의존성은 건너뛰지 않는다.

해제 receipt는 canonical role 또는 document ID로 참조한다. owner별 허용 유형은 `DECISION_RECEIPT`, `EXTERNAL_EVIDENCE_RECEIPT`, `BLOCKER_REMEDIATION_RECEIPT`뿐이다. receipt는 blocker ID·Goal ID·condition code·owner, `RESOLVED`, resolver ID·role·authority·승인과 원자료 path/SHA-256을 가져야 한다. receipt와 `blocker_resolution_history` 양쪽의 `blocker_event_sha256`과 `blocker_snapshot_sha256`은 각각 원 `BLOCKER_RECORDED` event와 생성 당시 불변 blocker snapshot에 정확히 일치해야 한다. 시간은 `blocker.created_at ≤ BLOCKER_RECORDED.occurred_at ≤ receipt.decided_at ≤ BLOCKER_RESOLVED.occurred_at`이어야 한다. resolver와 reopen approver는 pinned canonical `AUTHORITY_ROSTER`의 actor ID, 허용 role·authority·evidence type과 authority reference에 일치해야 한다.

blocker는 `BLOCKER_RECORDED/RESOLVED` 외 event에서 추가·수정·삭제하지 않는다. 기록된 ID는 active 또는 정확히 하나의 typed resolution으로 보존한다. `BLOCKER_RESOLVED` event는 이번에 제거한 활성 blocker ID 집합을 `resolved_blocker_ids`에, 그 blocker들의 resolution receipt reference 집합만 정확히 `evidence_refs`에 넣고, 같은 ID를 `blocker_resolution_ids_after`에 누적한다. 아직 같은 Goal을 막는 blocker가 있으면 그 Goal을 `READY`로 바꾸지 않는다. 우회가 없으면 아직 소비되지 않은 해당 leaf resolution 전체로 같은 leaf를 한 번 재개한다. 우회했으면 과거 bypass snapshot과 같은 모든 blocker resolution을 한 번만 소비하고 임시 leaf가 닫힌 뒤 정확한 return leaf로 돌아가며 bypass를 제거한다. 일정·담당자만 정한 상태는 실행 완료가 아니고 `PARTIAL/AWAITING_*`을 유지한다.

## 완료 후 인계

Phase 또는 Work Item을 끝낼 때 다음을 남긴다.

- 변경 파일과 검증 명령·실제 결과
- 내부 완료수준과 아직 주장하지 않는 정식 결과
- 갱신한 Draft·Active·append-only 증거
- 새 Gap·Backlog revision과 재평가 결과
- 남은 blocker·gate·외부 증거
- 체크포인트 `goal_execution`의 상태·현재 포인터·다음 행동
- daylog와 local-memory 인계

Phase A 완료 후 [`20-phase-b-formal-verification.md`](20-phase-b-formal-verification.md)를, B 완료 후 [`30-phase-c-release-delivery.md`](30-phase-c-release-delivery.md)를, C 완료 후 [`40-phase-d-operation-handover-closure.md`](40-phase-d-operation-handover-closure.md)를 자동으로 읽는다.
