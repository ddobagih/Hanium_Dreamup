# WalkSafe 자율 완성 Goal 패키지 v1.1

패키지 ID: `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-V1`

상태: `PREPARED_NOT_ACTIVATED`

기준일: `2026-07-23`

## 지금 한눈에

- 현재 상태: Goal 패키지는 준비됐지만 아직 활성화하지 않았다. 프로젝트 구현은 이미 진행 중이고, 이 실행 패키지만 `READY_NOT_ACTIVATED`다.
- 사용자가 할 일: 아래 활성화 프롬프트를 Goal로 한 번 등록한다.
- 현재 작업: `Phase A → EPIC-02 → FP-018`
- 쉬운 설명: 앱으로 돌아왔을 때 안전 조건을 모두 다시 확인하고 사용자가 “시작”해야 보행을 재개한다. 재부팅·강제종료 뒤에는 이전 보행을 자동으로 되살리지 않는다.
- 활성화 뒤: 승인 정책 안의 기술 결정과 저장소 작업은 다시 승인받지 않고 이어간다.
- 질문 시점: 정책 변경, 실제 기기·참여자·비밀정보·비용, 프로덕션 배포·서명처럼 사용자 권한이 정말 필요할 때뿐이다.

문맥이 끊긴 뒤 사용자는 “WalkSafe 활성 Goal을 이어서 진행해”라고만 말하면 된다. 에이전트가 아래 재개 절차를 수행한다.

용어는 다음 뜻으로 사용한다.

- `Phase`: 프로젝트의 큰 단계
- `EPIC`: 관련 기능 묶음
- `Work Item`: 지금 실제로 처리할 한 가지 작업
- `Gap`: 승인 정책과 현재 상태의 차이
- `successor`: 과거 기록을 보존한 채 만드는 다음 판본

## 1. 왜 이 패키지가 필요한가

이 패키지는 사용자가 매번 “다음 계획을 세워줘 → 진행해줘”라고 지시하지 않아도 WalkSafe 프로젝트가 승인된 정책과 257개 산출물을 따라 다음 작업을 선택하고 이어가게 하는 실행 안내서다. 터미널이나 대화 문맥이 사라져도 다음 네 가지를 복구할 수 있어야 한다.

- 전체 완성까지의 `A → B → C → D` 순서
- 현재 Phase·EPIC·세부 작업
- 질문 없이 자율 진행할 범위와 반드시 멈출 경계
- 완료 뒤 갱신할 산출물·증거·체크포인트와 다음 Goal

이 문서 묶음은 새 제품 정책이나 258번째 산출물 유형이 아니다. `MGT-05·06·07·14·16`, `DSC-14`, `DOC-01·03·04`를 지원하는 실행 통제자료다.

## 2. 구조

```text
00 Master Goal
├─ A. 구현 준비
│  ├─ EPIC-01 … 완료된 선행 Goal
│  ├─ EPIC-02 … 현재 Goal
│  │  └─ FP-018 … 현재 세부 Goal
│  └─ EPIC-03 → 04 → 05 → 06 → 07 → 08 → 10 → 09 → 11
├─ B. 정식 검증
│  └─ EPIC-12, 279개 시험, 실제 기기·현장시험, 5개 gate
├─ C. 릴리스·배포·인도
└─ D. 운영 안정화·이관·프로젝트 종료
```

정적 단계 문서는 다음과 같다.

- [`00-master-goal.md`](00-master-goal.md)
- [`10-phase-a-implementation-readiness.md`](10-phase-a-implementation-readiness.md)
- [`20-phase-b-formal-verification.md`](20-phase-b-formal-verification.md)
- [`30-phase-c-release-delivery.md`](30-phase-c-release-delivery.md)
- [`40-phase-d-operation-handover-closure.md`](40-phase-d-operation-handover-closure.md)
- [`epics/`](epics/)의 EPIC-01~12 Goal
- [`work-items/a/epic-02-fp018-walk-state-recovery-r001.md`](work-items/a/epic-02-fp018-walk-state-recovery-r001.md)의 현재 세부 Goal

현재 실행 포인터는 `docs/control/walksafe-project-continuation-checkpoint.json`의 `goal_execution`이 정본이다. 실제 작업 내용은 같은 파일의 `current_work`와 최신 Backlog의 `next_single_action`이 서로 일치해야 한다. Goal front matter의 `initial_status`는 현재 상태가 아니라 문서를 만들 당시의 초기값이다.

## 3. 세부 Goal을 실행 직전에 만드는 이유

68개 정책·gate를 한꺼번에 68개 고정 파일로 만들면 첫 구현 뒤 생성되는 새 Gap·Backlog revision을 반영하지 못해 뒤쪽 Goal이 오래된 지시가 된다. 그래서 다음 규칙을 적용한다.

1. Phase와 EPIC 순서는 미리 고정한다.
2. 각 EPIC 문서에는 담당 정책·Gap·작업 방향을 모두 적는다.
3. 현재 실행할 정책 하나만 Work Item Goal로 구체화한다.
4. 완료 후 새 Gap·Backlog successor를 만든다.
5. 최신 successor의 `next_single_action`, EPIC 내부 완료수준, 저장소 내부 잔여작업에서 다음 단일 작업을 다시 계산한다. 정식·외부 증거만 남은 정책은 같은 Work Item으로 반복하지 않고 EPIC-12로 이관한다.
6. 다른 작업이면 새 Goal `R001`을 만든다. 완료된 같은 작업은 허용된 typed receipt로 reopen할 때만 `R002+` successor를 만든다.
7. successor와 다음 Goal을 사전검사한 뒤 체크포인트를 한 번의 논리적 commit으로 전환한다.

같은 행동을 반복 생성하지 않도록 새 Work Item을 만들기 전에 다음 `action identity`를 계산한다.

- Phase·부모 Goal
- Work Item ID와 목표 완료수준
- 정책·Gap ID
- materialization 원본의 문서 ID와 SHA-256

동일한 identity가 이미 `READY`·`IN_PROGRESS`·`AWAITING_*`이면 그 Goal을 재개하고 새 Goal을 만들지 않는다. 이미 `COMPLETE_AT_TARGET`이면 최신 Backlog의 다음 저장소 내부 행동으로 이동한다. 구현은 끝나고 외부·정식 증거만 남았다는 이유로 같은 구현 Work Item을 다시 열지 않고 EPIC-12 또는 해당 Phase의 외부 대기 작업으로 넘긴다.

완료한 행동을 다시 여는 것은 canonical trigger receipt가 이전 완료 판정을 무효화할 때만 허용한다. 유형은 `POLICY_CHANGE_APPROVAL`, `REGRESSION_DEFECT_RECEIPT`, `EVIDENCE_REJECTION_RECEIPT`, `CANONICAL_INPUT_REVISION_RECEIPT`, `GOAL_INSTRUCTION_CORRECTION_RECEIPT` 다섯 가지뿐이다. receipt는 canonical role 또는 document ID로 참조하고 효력 상태, 대상 Goal, 같은 Work Item ID, 결정 시각, approver의 ID·role·authority·승인을 검증한다. 또한 `target_completion_event_sha256`이 무효화할 과거 Goal의 실제 `GOAL_COMPLETED` event SHA-256과 같고, trigger `decided_at`은 target 완료 event `occurred_at` 이상이며 `GOAL_SUPERSEDED.occurred_at` 이하여야 한다.

reopen은 현재 실행 leaf가 아닌 오래전에 완료한 revision을 뒤늦게 대상으로 삼을 수 있다. 이 경우 새 `R002+`의 `predecessor_goal_id`와 hash는 event 당시 현재 실행 leaf를, `supersedes_goal_id`와 hash는 무효화할 과거 revision을 가리킨다. `supersedes_goal_id`가 있는 successor를 처음 도입하는 event는 반드시 `GOAL_SUPERSEDED`여야 한다. 이 event는 이전/현재 leaf 포인터를 유지할 수 있고, `status_changes`에서 이름으로 지정한 과거 target 하나만 `COMPLETE_AT_TARGET → SUPERSEDED`로 바꾸며 새 successor 하나를 `PLANNED`로 도입한다. 다른 event에서 successor를 미리 `PLANNED`로 등록하지 않는다. 과거 target의 completion refs는 `archived_completion_evidence_by_goal`로 옮겨 보존하고 successor를 현재 leaf로 전환해 실행하는 일은 그 뒤 별도 event에서 한다.

정책/Gap pair는 EPIC의 두 배열을 같은 위치끼리 묶어서 만들지 않는다. canonical `IMPLEMENTATION_GAP` JSON의 `assessments`를 읽고 각 행의 `source_policy_id → gap_id`로 유일한 mapping을 만든다. EPIC이 소유한 각 `source_policy_id`의 pair는 이 mapping으로만 결정하며 `source_policy_ids[i]`와 `gap_ids[i]`의 배열 zip이나 표시 순서는 의미가 없다. 정적 front matter의 `initial_status=COMPLETE_AT_TARGET`인 legacy EPIC만 Work Item 전수 커버리지 검사를 면제한다. 그 외 EPIC을 완료하려면 대체되지 않은 최종 Work Item이 canonical mapping의 pair 하나씩을 중복·누락 없이 맡고 모두 `COMPLETE_AT_TARGET`이어야 한다. 여러 pair를 한 Work Item에 몰아 넣거나 sibling Work Item을 한꺼번에 완료해 EPIC을 닫지 않는다.

source plan과 `materialized_from_*`은 “어떤 계획에서 이 Work Item을 만들었는가”를 보존하는 계보 입력일 뿐 완료 증거가 아니다. 모든 Work Item을 `COMPLETE_AT_TARGET`으로 바꾸려면 evidence에 canonical role `WORK_ITEM_COMPLETION::<goal_id>`가 정확히 한 번 있어야 하고, 그 binding의 JSON은 `evidence_type=WORK_ITEM_EXECUTION_RECEIPT`, `status=ACCEPTED`, `result=PASS`여야 한다. receipt는 다음을 하나로 결속한다.

- 실행한 Goal ID와 Goal 파일 SHA-256, Work Item ID, Phase별 정확한 `target_completion_level`
- Work Item의 `source_policy_ids`·`gap_ids`와 canonical IMPLEMENTATION_GAP mapping
- 해당 Goal을 가장 최근 `IN_PROGRESS`로 만든 실행 시작 event의 `execution_start_event_sha256`
- `execution_window.started_at/ended_at`, 그 안의 `completed_at`, 이후 reviewer 결정과 receipt 생성 시각. 실행 구간 시작은 최신 실행 시작 event `occurred_at`보다 이르지 않고 receipt의 완료·생성은 `GOAL_COMPLETED.occurred_at`보다 늦지 않아야 함
- 서로 다른 경로에 있는 typed JSON `IMPLEMENTATION_RECORD`, `VERIFICATION_RESULT`, `SUCCESSOR_TRACE`를 각각 정확히 하나씩과 각 파일 SHA-256. 세 JSON은 같은 Goal ID, 해당 kind, `status=PASS`, 실행 구간 안이고 완료시각 이하인 `observed_at`을 가져야 한다.

해당 Work Item을 닫는 `GOAL_COMPLETED` event의 `completion_receipt_binding`은 canonical binding의 `role`, `document_id`, `path`, `file_sha256` snapshot과 정확히 같아야 한다. event `evidence_refs`는 같은 event에서 완료되는 leaf와 eligible ancestor의 completion refs 정확한 union이어야 하며 이 role을 빼거나 임의 ref를 더하지 않는다.

다음 successor가 실행을 시작하는 event의 `occurred_at`은 predecessor Work Item completion receipt의 `generated_at`보다 이르지 않아야 한다.

Phase B/C/D를 닫는 required receipt는 `GOAL_COMPLETED`보다 먼저 최종 확정돼야 한다. 각 receipt의 `execution_window.started_at`은 해당 Phase가 처음 `IN_PROGRESS`가 된 event의 `occurred_at`보다 이르지 않아야 한다. 해당 receipt의 `generated_at`은 그 receipt를 completion evidence로 사용하는 Phase 또는 Master의 `GOAL_COMPLETED.occurred_at` 이하여야 한다. Phase C는 `technical_delivery.accepted_at`도, Phase D는 handover `accepted_at`과 project closure `closed_at`도 각각 해당 Phase/Master 완료 event 시각 이하여야 한다.

Phase B는 `TEST_PLAN_APPROVAL_RECEIPT`, `FORMAL_TEST_REPORT`, `ACTUAL_DEVICE_TEST_REPORT`, `RELEASE_GATE_CLOSURE_RECEIPT`, `RELEASE_ELIGIBILITY_APPROVAL`의 다섯 canonical receipt를 정확히 사용한다. `TEST_PLAN_APPROVAL_RECEIPT`는 승인한 `PLANNED_TEST_CASES`의 `role`, `document_id`, `path`, `file_sha256`, `version`, 279개 `test_case_id_set_sha256` snapshot과 `plan_approved_at`을 고정하며 외부 attestation anchor를 가져야 한다. 승인 시각과 receipt 생성 시각은 `FORMAL_TEST_REPORT`, `ACTUAL_DEVICE_TEST_REPORT`, `RELEASE_GATE_CLOSURE_RECEIPT` 각 `execution_window.started_at`보다 늦을 수 없다. `FORMAL_TEST_REPORT`도 같은 계획 snapshot을 포함한다. 비적용 판정의 `decided_at`은 보고서 실행 시작 이후이고 보고서 approver 결정과 `generated_at`보다 늦지 않아야 한다. 다섯 Phase B receipt가 모두 검증돼 Phase B가 완료되기 전까지 공식 경계는 `formal_test_not_run_count=279`, gate 5개 `NOT_RUN`, `actual_device_test_status=NOT_RUN`, `formal_test_pass_claimed=false`, `approved_production_profile_count=0`을 원자적으로 유지한다. 부분 증거로 이 값들만 먼저 승격하지 않는다.

Phase B와 EPIC-12의 completion evidence 및 `verification_evidence_refs`는 각각 이 다섯 role의 정확한 집합이어야 한다. 마지막 Phase B Work Item을 함께 닫는 `GOAL_COMPLETED.evidence_refs`는 이 집합과 해당 `WORK_ITEM_COMPLETION::<goal_id>`를 포함한, 같은 event에서 완료되는 Goal 증거의 정확한 합집합이어야 한다.

활성화 전에는 최종 검수 결과를 이 v1.1 파일집합에 반영할 수 있다. 활성화 후에는 다음 불변 경계를 적용한다.

- 실행을 시작한 Work Item은 덮어쓰지 않고 `r002` successor로만 교정한다.
- Master·Phase·EPIC 정적 계획은 직접 수정하거나 같은 경로의 SHA-256을 교체하지 않는다.
- v1.1 검사기는 실행 중 plan revision 전환을 지원하지 않는다. 정적 계획 변경이 필요하면 현재 Goal을 `AWAITING_USER`로 두고 작업을 멈춘다.
- 변경은 새 버전의 정적 plan manifest, 승인된 새 trust anchor, 그 버전을 명시적으로 이해하는 versioned successor checker가 함께 준비·승인된 뒤에만 별도 절차로 가능하다. 현재 manifest 경로나 체크포인트 hash만 바꿔서 우회하지 않는다.

최초 활성 계획은 `static-plan-manifest-v1.1.0.json`이 보호 파일 경로·SHA-256을 고정한다. 최초 `PACKAGE_PREPARED` event는 그 manifest SHA-256을 참조하고 검사기에 고정된 event SHA-256과 일치하므로 계획 manifest와 실행 이력의 시작점이 서로 결속된다. manifest 안에 event SHA-256을 다시 넣지는 않는다. 그렇게 하면 해시 순환이 생기기 때문이다. v1.1의 모든 event는 이 manifest를 참조한다. 미래 successor checker가 plan revision을 지원하더라도 과거 event는 event 발생 당시 manifest와 trust anchor로 검증하고 과거 manifest·event를 다시 쓰거나 재해시하지 않는다.

append-only 이력은 최초 event만 고정해서는 충분하지 않다. 현재 독립 검토가 끝난 transition history의 마지막 event SHA-256도 버전 관리되는 checker의 `EXPECTED_TRANSITION_HISTORY_HEAD_SHA256`에 고정한다. 체크포인트의 `transition_history_anchor_sha256`이 자기 history 마지막 hash를 다시 가리키는 것만으로는 외부 불변 증거가 아니다. 합법적으로 event를 append할 때마다 기존 head와 새 event를 독립 보존·검토하고, 새 head가 확정된 뒤 versioned checker의 head anchor를 명시적으로 갱신한다. 과거 checker 버전과 과거 head는 보존하며 새 event를 붙였다는 이유로 이전 event를 다시 쓰지 않는다.

전환은 다음 순서를 지킨다.

1. 구현 기록, Gap·Backlog successor, 다음 Work Item Goal을 준비한다.
2. 새 파일과 정책·Gap·의존성·hash를 격리 사전검사한다. 이 단계에서는 staged checkpoint에 대해 Goal 검사기의 격리 모드를 사용한다.
3. 새 canonical head와 작업 snapshot에 맞게 continuation 검사기의 기대 계약과 회귀 fixture를 함께 준비한다.
4. 이전 Goal 완료, 새 Goal 포인터, canonical binding, 파일집합 hash, hash-linked 전환 이력을 체크포인트 한 번의 논리적 commit으로 바꾼다.
5. 일반 모드의 continuation 검사와 Goal 검사, 관련 회귀를 모두 통과한 뒤 새 Goal을 시작한다.

1~2단계가 실패하면 체크포인트를 건드리지 않는다. 3단계 뒤 검사가 실패하면 새 작업을 시작하지 않고 직전 전환 자료로 복구한다.

전환 이력은 설명문이 아니라 replay 가능한 상태 원장이다. 모든 event는 연속 `sequence`, 고유 `event_id`, 직전 event SHA-256, 현재 static manifest SHA-256, 이전/현재 leaf ID와 파일 SHA-256, `from_status/to_status`, canonical `evidence_refs`를 가진다. 모든 event의 `occurred_at`은 timezone을 포함한 유효 시각이고 `occurred_on`과 같은 날짜여야 하며, 직전 event `occurred_at`보다 엄격히 커야 한다. 또한 현재 검토된 head와 함께 checker에 고정한 `validation_cutoff_at` 이하여야 한다. head를 갱신할 때만 체크포인트와 checker의 `EXPECTED_VALIDATION_CUTOFF_AT`을 같은 검토 사건으로 전진시키며, 미래 사건을 미리 기록하지 않는다. 또한 다음 실행 상태 전체를 재현할 수 있도록 아래 snapshot을 반드시 남긴다.

- `status_changes`: 이번 event로 바뀐 Goal 상태. 최초 `PACKAGE_PREPARED`만 모든 Goal 상태를 한 번에 고정한다.
- `pointers_after`: Phase·EPIC·leaf·leaf path·Work Item·source·Goal 상태·활성화 상태·package 상태의 전체 포인터
- `blockers_after`: event 직후의 전체 활성 blocker map
- `blocker_resolution_ids_after`: 지금까지 해제된 blocker resolution ID의 누적 목록
- `bypass_after`: 정확한 임시 우회 snapshot 또는 `null`

검사기는 모든 event를 처음부터 replay한 결과가 현재 상태·포인터·blocker·resolution·bypass와 정확히 같은지 확인한다. 상태 전환은 다음 경계를 지킨다.

| event | 허용되는 핵심 전환 |
|---|---|
| `PACKAGE_PREPARED` | 미활성 상태의 첫 event. 초기 leaf와 조상만 `READY`, blocker·resolution·bypass 없음 |
| `PACKAGE_ACTIVATED` | 정확히 한 번, 같은 초기 leaf `READY → IN_PROGRESS` |
| `GOAL_COMPLETED` | 같은 leaf `IN_PROGRESS → COMPLETE_AT_TARGET`; 미완료 자식이 있는 부모를 함께 완료하지 않음 |
| `GOAL_SUPERSEDED` | trigger receipt가 지정한 완료 Work Item 하나만 `COMPLETE_AT_TARGET → SUPERSEDED`; 과거 target이면 현재 leaf를 유지하고 successor 하나를 `PLANNED`로 도입 |
| `GOAL_TRANSITION` | 완료 또는 대체된 leaf에서 서로 다른 정상 successor로 이동 |
| `GOAL_BYPASSED` | blocker 때문에 기다리는 leaf에서만 의존성이 끝난 임시 leaf로 이동 |
| `BLOCKER_RECORDED/RESOLVED` | blocker 전체 snapshot과 typed resolution receipt를 반영 |
| `GOAL_RESUMED` | 같은 leaf 재개 또는 검증된 bypass 복귀 |
| `PACKAGE_COMPLETED` | 모든 Phase와 Master가 완료된 뒤 포인터를 비우는 마지막 event |

미활성 package에서 기능 실행 event를 기록하지 않고 `PACKAGE_ACTIVATED`를 중복 기록하지 않는다. leaf 완료를 건너뛰어 다음 leaf로 이동하거나 자식이 남은 Phase·EPIC·Master를 중간 완료하지 않는다. `PACKAGE_COMPLETED` 뒤에는 어떤 event도 추가하지 않는다.

`status_changes`에는 실제로 바뀐 상태만 기록한다. `GOAL_COMPLETED`는 current leaf와 모든 자식·canonical 정책/Gap 커버리지가 닫힌 eligible ancestor만 완료할 수 있고 sibling·무관 Goal을 bulk 완료할 수 없다. `GOAL_SUPERSEDED`는 이름으로 지정한 완료 Work Item 하나만 바꾸며, 이 대상은 현재 leaf가 아닌 과거 revision일 수 있다. blocker event는 정확한 blocker 대상 Goal만 바꾼다. 새 동적 Work Item은 한 event에 최대 하나만 도입하며 `predecessor_goal_id`는 그 event의 직전 leaf여야 한다. 새 leaf로 바로 전환할 때만 `READY/IN_PROGRESS`, 미리 준비할 때는 `PLANNED`로 도입한다.

## 4. 한 번만 사용할 활성화 프롬프트

아래 문장을 Goal로 등록하면 이후 저장소 내부 구현은 단계가 끝날 때마다 자동으로 다음 Goal로 넘어간다.

```text
WalkSafe 자율 완성 Goal `WS-GOAL-WALKSAFE-COMPLETION-V1`을 활성화한다.
프로젝트 루트는 /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715 이다.
docs/control/goals/walksafe-completion-v1/00-master-goal.md와
docs/control/walksafe-project-continuation-checkpoint.json을 실행 기준으로 삼아
현재 세부 Goal부터 시작하고, 각 Goal의 완료조건을 검증한 뒤 질문 없이 다음 Goal로 전환하라.

AUTONOMOUS_REPOSITORY_WORK=GRANTED

승인된 정책은 다시 묻지 않는다. 저장소 범위의 구현, 내부 테스트, Draft 작성,
Active 사실 기록, Gap·Backlog successor, 체크포인트, daylog, local-memory 갱신은
별도 승인 없이 수행한다. 정책 변경, Approved/Baselined 승격, 실제 기기·참여자·
유료 서비스·비밀정보, 프로덕션 배포, 파괴적 외부 작업, 독립검토, 출시·인수·종료
서명이 처음으로 불가피해질 때만 기존 답변과 중복되지 않는 질문을 한 번에 묶어 요청하라.
정식 증거가 없으면 NOT_RUN·PARTIAL·NOT_ELIGIBLE 경계를 유지하라.
```

이 프롬프트는 저장소 내부 작업을 자율화한다. 실제 배포·비용 지출·현장 참여·독립검토·인수 서명까지 미리 실행 승인하는 문장은 아니다.

활성화는 멱등적으로 처리한다.

- `READY_NOT_ACTIVATED`: 두 검사 통과 뒤 `ACTIVE/IN_PROGRESS`로 한 번 전환하고 활성화 event를 기록한다.
- 같은 package가 이미 `ACTIVE`: 새로 활성화하지 않고 현재 leaf를 재개한다.
- package가 `COMPLETE`: 다시 시작하지 않고 최종 상태를 보고한다.
- 다른 Goal package가 `ACTIVE`: 두 package의 변경 범위를 확인할 때까지 fail-closed한다.

## 5. 에이전트의 새 세션 재개 순서

다른 안내서에서 더 짧은 순서를 보더라도 아래 순서가 우선한다.

1. 저장소 `AGENTS.md`를 읽는다.
2. `docs/control/walksafe-project-resumption-runbook.md`를 읽는다.
3. 이 README와 `00-master-goal.md`를 읽는다.
4. 체크포인트의 `goal_execution`·`current_work`·canonical binding·미해결 blocker를 읽는다.
5. `python3 -B scripts/check_walksafe_project_continuation.py`를 실행한다.
6. `python3 -B scripts/check_walksafe_goal_package.py`를 실행한다.
7. 두 검사가 성공하면 현재 Phase·EPIC·Work Item Goal만 읽는다.
8. Work Item의 entry gate와 action identity를 확인하고 재개한다.

검사가 실패하면 기능 구현을 시작하지 않는다. 정책·승인·Goal·체크포인트 중 어느 결속이 깨졌는지 먼저 복구한다.

## 6. Goal 상태와 산출물 상태의 차이

Goal 실행 상태는 다음만 사용한다.

- `PLANNED`: 선행조건 전
- `READY`: 시작 가능한 다음 Goal
- `IN_PROGRESS`: 현재 실행 중
- `AWAITING_USER`: 새로운 사용자 결정이 필수
- `AWAITING_EXTERNAL`: 기기·참여자·계정·독립검토 등 외부 조건 대기
- `BLOCKED`: 안전한 대안과 독립 작업을 모두 소진
- `COMPLETE_AT_TARGET`: 해당 Goal의 명시된 완료수준만 달성
- `SUPERSEDED`: 새 Goal revision으로 대체

Work Item의 목표 완료수준은 Phase별 exact enum을 사용한다. A는 `INTERNAL_POLICY_CONFORMANCE_REASSESSED` 또는 `IMPLEMENTATION_READY`, B는 `FORMAL_VERIFICATION_ACTION_COMPLETE`, C는 `RELEASE_DELIVERY_ACTION_COMPLETE`, D는 `HANDOVER_CLOSURE_ACTION_COMPLETE`만 허용한다. Phase Goal 자체의 상위 목표 문자열을 Work Item에 사용하지 않는다.

이 상태는 `Planned/Draft/Active/Approved/Baselined` 같은 산출물 상태를 바꾸지 않는다. 예를 들어 EPIC-02가 `COMPLETE_AT_TARGET`이어도 뜻은 `IMPLEMENTATION_READY`뿐이며, 정식 시험·실기기·gate·출시는 아직 완료되지 않을 수 있다.

Phase C의 “인도”는 승인된 release bytes와 설명자료를 배포·전달하는 기술적 사건이다. Phase D의 “이관”은 운영 계정·권한·지원·복구 책임을 실제 소유자에게 넘기는 책임 이전이다.

Phase C 실제 사건은 `deployment start → canary → smoke → deployment end → technical delivery acceptance` 순서로만 닫는다. Phase D는 `Phase C acceptance → handover checks/decisions → handover acceptance → closure start → closure checks/decisions → closed` 순서로만 닫는다. 뒤 사건의 receipt나 예정 일정으로 앞 사건을 대신하지 않는다.

## 7. 자율 실행과 질문 경계

질문하지 않고 진행한다.

- 승인 정책에 맞는 구현과 내부 설계 선택
- 재현 테스트, 단위·구성요소·회귀검사
- 내부 코드·문서 검토와 서브에이전트 감사
- Draft 작성, Active 원장에 실제 사건 기록
- append-only 실행 기록과 Gap·Backlog successor
- 다음 Work Item Goal 준비·사전검사와 원자적 체크포인트 전환

다음은 처음으로 불가피할 때만 멈추고 묻는다.

- 정책이나 사용자 경험의 의미를 바꿔야 함
- 제품 방향을 바꾸는 복수 해석 중 선택이 필요함
- Approved/Baselined 내용 변경이나 새 기준선 효력화가 필요함
- 실제 계정·키·유료 서비스·기기·참여자·독립검토가 필요함
- 프로덕션 배포·외부 메시지·데이터 삭제 같은 되돌리기 어려운 작업
- 출시·인수·운영 이관·종료를 공식 확정해야 함

EPIC 순서는 정상 상황의 기본 우선순위다. 현재 branch가 `AWAITING_USER` 또는 `AWAITING_EXTERNAL`이면 다음 수명주기를 적용한다.

1. `BLOCKER_RECORDED` event와 `blockers_after`에 blocker ID, `blocks_goal_id`, condition code, owner, prompt, 생성 시각, 정확한 `return_leaf_goal_id`, 정지 action 근거와 `blocker_snapshot_sha256`을 보존한다. condition code에 따라 owner와 Goal 상태를 `USER/AWAITING_USER`, `EXTERNAL/AWAITING_EXTERNAL`, `BLOCKED/BLOCKED`로 정확히 맞춘다.
2. 원 Goal의 포인터와 지금까지의 증거를 지우지 않는다. 의존성이 모두 완료된 독립 EPIC만 임시로 진행할 수 있다. `GOAL_BYPASSED`의 `bypass_after`에는 임시 `current_goal_id`, 건너뛴 미완료 EPIC 전체 `return_goal_ids`, 정확히 하나의 `return_leaf_goal_id`와 같은 값을 가진 `blocked_leaf_goal_ids`, 건너뛴 Goal과 leaf의 모든 활성 `blocker_ids`, `resolution_required=true`를 기록한다. 임시 Work Item의 predecessor는 return leaf다.
3. blocker 해제 receipt는 canonical binding의 role 또는 document ID로만 참조한다. owner가 `USER`면 `DECISION_RECEIPT`, `EXTERNAL`이면 `EXTERNAL_EVIDENCE_RECEIPT`, `BLOCKED`면 `BLOCKER_REMEDIATION_RECEIPT`다.
4. receipt는 `status=RESOLVED`, blocker ID·Goal ID·condition code·owner, 결정 시각, resolver의 ID·role·authority·승인 결정/시각, 원자료 path·SHA-256을 정확히 가져야 한다. blocker `created_at ≤ BLOCKER_RECORDED.occurred_at ≤ receipt.decided_at ≤ BLOCKER_RESOLVED.occurred_at`이어야 한다. resolver와 reopen approver는 checker trust anchor에 고정된 canonical `AUTHORITY_ROSTER`의 같은 actor ID, 허용 role·authority·evidence type과 `authority_evidence_ref=AUTHORITY_ROSTER`를 모두 만족해야 한다. receipt에는 `authority_roster_ref=AUTHORITY_ROSTER`, `authority_roster_document_id`, `authority_roster_sha256`도 넣고 roster `approved_at`은 receipt에서 그 권한을 처음 사용한 시각보다 늦을 수 없다. receipt와 `blocker_resolution_history` 양쪽의 `blocker_event_sha256`·`blocker_snapshot_sha256`은 원래 `BLOCKER_RECORDED` event와 불변 blocker snapshot에 정확히 일치해야 한다.
5. `BLOCKER_RESOLVED.resolved_blocker_ids`에는 이번에 제거하는 활성 blocker ID 집합을, event `evidence_refs`에는 그 blocker들의 `resolution_receipt_ref` 정확한 집합만 넣고 같은 ID를 누적 resolution 목록에 보존한다. 같은 Goal의 blocker가 남아 있으면 그 Goal을 `READY`로 바꾸지 않는다. 우회가 없으면 원 leaf를 먼저 `READY`로 만든 뒤 같은 leaf의 `GOAL_RESUMED`로 `IN_PROGRESS`에 복귀한다.
6. 우회했다면 과거 `GOAL_BYPASSED` event와 현재 bypass snapshot의 return goals·return leaf·blocked leaf·blocker ID 집합이 정확히 같아야 한다. 모든 blocker가 typed receipt로 해제되고 임시 leaf가 `COMPLETE_AT_TARGET` 또는 `SUPERSEDED`가 된 뒤에만 `return_from_bypass=true`인 `GOAL_RESUMED`로 정확한 `return_leaf_goal_id`에 복귀한다. 이 event에서 `bypass_after=null`로 지운다.
7. 답변은 결속된 blocker에만 효력이 있으며 다른 비용·배포·삭제·서명 권한으로 확대 해석하지 않는다. 에이전트가 내부에서 만든 요약 JSON만으로 사람 결정이나 외부 실제성을 대신하지 않는다.

모든 실행 가능한 branch가 막힐 때만 통합 blocker 패키지를 만들어 사용자에게 전달한다. 단순히 일정만 합의했거나 미래 실행자가 지정된 상태는 blocker 해제가 아니며 `PARTIAL/AWAITING_*`을 유지한다.

blocker record는 `BLOCKER_RECORDED`에서만 추가하고 `BLOCKER_RESOLVED`에서만 제거한다. 다른 event는 기존 blocker byte를 수정·삭제·추가할 수 없다. 한 번 기록된 blocker ID는 현재 active이거나 정확히 하나의 typed resolution history로 남아야 한다. `GOAL_RESUMED`는 아직 resume에 소비되지 않은 해당 leaf의 resolution을 정확히 한 번만 사용하며 같은 resolution을 다른 resume에 재사용하지 않는다.

실기기·사람 승인·프로덕션 배포·운영책임 이전처럼 저장소 밖 실제성이 필요한 사건은 signed/externally anchored authority evidence가 없으면 완료로 올리지 않는다. Phase B/C/D 완료 receipt뿐 아니라 `REOPEN_TRIGGER::<target_goal_id>`와 `BLOCKER_RESOLUTION::<blocker_id>`의 사용자·정책·외부 결정 receipt도 canonical `AUTHORITY_ROSTER` 일치만으로 충분하지 않다. 독립 검토와 서명 또는 외부 시스템 anchor가 끝난 정확한 receipt 파일 SHA-256을 버전 관리되는 checker의 `EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE`에 정확한 동적 role별 trust anchor로 고정해야 한다. 모든 권한 receipt는 사용 당시 roster의 ID와 SHA-256을 결속한다. v1.1 roster 파일은 제자리 갱신하지 않으며 변경은 predecessor를 보존한 successor version으로 처리한다. 실제 승인 receipt가 아직 없어 map이 비어 있는 것은 정상이며, 임의 hash를 채우지 않는다. 향후 승인된 receipt만 검토된 checker 변경으로 추가한다. 내부 receipt 구조가 유효해도 이 anchor가 없으면 `AWAITING_EXTERNAL`과 `NOT_ELIGIBLE` 경계를 유지한다.

## 8. 변경과 검증

- 활성화 후 Master·Phase·EPIC은 v1.1에서 변경하지 않는다. 변경 필요 시 새 manifest·승인된 trust anchor·versioned successor checker가 마련될 때까지 fail-closed한다.
- 최신 정책·Gap·Backlog는 체크포인트의 canonical role로 찾는다.
- 목표 파일의 TOML front matter와 계층·정책·Gap 분할은 전용 검사기로 검증한다.
- Goal 패키지 파일 집합과 SHA-256은 체크포인트에 결속한다.
- 전환 때 기존 구현 기록·Gap·Backlog를 덮어쓰지 않고 successor를 만든다.
- 매 세션 종료 전 체크포인트·daylog·local-memory를 갱신한다.
