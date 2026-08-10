# 257종 잔여 작업 계획 R004 — plan-only successor

- 계획 ID: `WS-257-CLOSURE-PLAN-20260729-R004`
- 상태: `PLAN_READY_NOT_STARTED`
- 작성일: `2026-07-29`
- predecessor: `WS-257-CLOSURE-PLAN-20260727-R003`
- 현재 source:
  `WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260729-R011`
- source path:
  `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r011/phase1-exact257-successor-ledger-r011.json`
- source physical SHA-256:
  `7fc4bd6f2242b17de75faa040e42b4744c972a492f87ff4365caaeae53f93368`
- source bytes: `2,637,012`
- source non-self digest:
  `3219242896c297e2e86b0c9ccd03f1a362c1226bbd78e202c8c621fef6a0fd5e`
  / canonical bytes `1,839,190` / status `PASS`
- source independent review:
  `docs/control/execution/artifact-closure/run-20260727-001/phase1-exact257-successor-independent-review-r011.md`
  / SHA-256 `3bf2d1d107db482ab63601f25ade3805536aaa242070d888823bfa271e9a4e5f`
  / `11,686` bytes / findings `0`
- 실행 경계:
  `EXECUTION_STARTED=false / ARTIFACT_CREDIT_DELTA=0 / RELEASE_CREDIT=0`

```text
OWNER_APPROVAL_DELTA=0
ATTESTATION_DELTA=0
ACCEPTANCE_DELTA=0
ACTUAL_EVENT_DELTA=0
STATIC_PLAN_LOCKED=true
IN_PLACE_STATIC_MUTATION_ALLOWED=false
TRANSITION_HISTORY_APPEND_ONLY=true
TRANSITION_HISTORY_TAIL_SEQUENCE=39
```

## 1. R003에서 고치는 것

R003의 방법론과 add-only·evidence-first·resource lease 원칙은 유지한다.
다음 수치와 해석만 최신 R011에 맞춘다.

| 항목 | R003 입력 | R004 계획 입력 |
|---|---:|---:|
| open | 133 | 131 |
| current-scope N/A closure | 후보 36의 일부 | 승인·독립검수된 exact 2 |
| closed-equivalent | baseline 124 | baseline 124 + scope N/A 2 = 126 |
| global completion claim | 미분리 위험 | 정확히 0 |
| Ready25 | 미반영 | content observation 25, acceptance·실행·종결 0 |

현재범위 N/A 2개는 `DLV-DSC-04`, `DLV-WS-16`이다. 새 trigger나 범위 변경
없이 다시 open으로 만들지 않는다.

## 2. exact open 131 lane

| Lane | R011 route | 수 | 현재 상태 | 향후 exit predicate |
|---|---|---:|---|---|
| A | `INTERNAL_READY` | 62 | content/contract 준비 후보 | required content, direct trace, reviewer와 적격 acceptance |
| B1 | `EVIDENCE_FACT_PENDING` | 6 | 사실 미확보 | 날짜·출처·소유자가 있는 fact receipt |
| B2 | `OWNER_APPROVAL_PENDING` | 14 | 실제 owner 결정 없음 | 대상별 approve/reject/return 결정과 authority |
| B3 | `ATTESTATION_REVIEW_PENDING` | 4 | 독립 확인 미완료 | 지정된 독립 reviewer와 exact subject verdict |
| C | `INTERNAL_RUN_REQUIRED` | 24 | 실제 run 필요 | raw input/output, environment, exit, receipt, independent review |
| D | `REAL_EVENT_PENDING` | 21 | 실제 사건 필요 | 정당한 device/field/deploy/agency/acceptance event receipt |
| 합계 |  | 131 | open | exact set 누락·중복 0 |

`Lane exit`는 즉시 artifact closure를 뜻하지 않는다. exit predicate를 만족한
행은 positive closure candidate가 될 뿐이며, 해당 ID의 acceptance·authority·
독립검수까지 통과해야 닫을 수 있다. `reject`, `return`, `negative`,
`inconclusive`, 실패 receipt, `WAITING_EXTERNAL`은 모두 open을 유지하고
원인에 맞는 lane으로 재라우팅한다.

| outcome | return lane | 재진입에 필요한 positive evidence |
|---|---|---|
| content evidence 누락·무효·불완전 | `INTERNAL_READY` | 교정된 content·direct trace·적격 acceptance |
| fact 누락·출처불명·귀속불가 | `EVIDENCE_FACT_PENDING` | 날짜·출처·소유자가 결속된 fact receipt |
| owner 결정 누락·무효 또는 authority 불충분 | `OWNER_APPROVAL_PENDING` | 교정 subject와 적격 authority의 유효한 결정 |
| attestation 누락·무효·inconclusive 또는 independence 불충분 | `ATTESTATION_REVIEW_PENDING` | exact subject에 대한 적격 독립 reviewer의 유효한 verdict |
| run receipt 누락·무효·inconclusive | `INTERNAL_RUN_REQUIRED` | 같은 candidate·환경에 결속된 판독 가능한 raw run receipt와 review |
| 실제 사건 미발생·receipt 무효·`WAITING_EXTERNAL` | `REAL_EVENT_PENDING` | 정당하게 발생하고 주체·권한·candidate가 결속된 판독 가능한 event receipt |
| 유효한 negative/reject/return/failed run/부정적 actual event | earliest unmet substantive dependency lane 또는 v2.4 policy-gap work | 원 receipt·reason·`return_state`·후속 재실행/재검토 의무를 보존한 교정 evidence |

유효한 negative verdict나 failed receipt는 같은 evidence lane에서 단순 반복하지
않는다. content·trace 결함이면 `INTERNAL_READY`, code/build/data/model/runtime
결함이면 v2.4 policy-gap/implementation work를 먼저 열고 그 완료 뒤
`INTERNAL_RUN_REQUIRED`, 외부 실행에서 내부 제품 결함이 드러나면
`EXTERNAL → INTERNAL_GAP` 의미의 return state를 사용한다. 이 경우에도 원
외부 receipt와 향후 외부 재실행 의무를 보존한다.

여러 실패가 겹치면 R011 per-ID `dependencies` 배열 순서에서 가장 이른 미충족
substantive dependency를 사용한다. fact→B1, owner authority→B2, independent
attestation→B3, actual event→D로 사상한다. 동일 순위가 둘 이상이거나 사상이
불명확하면 closure를 보류하고 독립 plan finding으로 남긴다. 이 결정표는 향후
add-only successor의 `reason`, `evidence`, `return_state` 규칙이며 이번 문서가
현재 R011 route를 변경하지 않는다.

### 2.1 exact 131 per-ID dependency binding

R004는 131개 ID의 dependency를 새로 추정하거나 다시 쓰지 않는다. 위에서
물리·non-self digest로 결속한 R011의 `records[]` 중
`artifact_closure.status == "OPEN"`인 exact 131행을 사용한다.

각 ID의 계획 선행조건 정본은 다음 필드 묶음이다.

- `artifact_type_code`
- `queue_route.current`
- `predecessor_phase1_action_queue_record.dependencies`
- `predecessor_phase1_action_queue_record.evidence_predicate`
- `predecessor_phase1_action_queue_record.owner_role`
- `predecessor_phase1_action_queue_record.resource_class`
- `claim_boundary`

위 필드를 recursive-key-sorted compact JSON 한 행 + LF로 직렬화한 exact 131
dependency projection SHA-256은
`651644380d821d32e93a3cf85b7495d884812dd8f2a100fb41942d966b41e50f`다.
ID·route projection SHA-256은
`8e7094e875525765af69c7fdd3ede622fa40010cd8cb8e3228430ba988cea67b`다.
필드 누락·digest 불일치·고유 ID 131 불일치는 해당 ID를 open으로 유지하는
계획 finding이며 수동 보완으로 우회하지 않는다.

### 2.2 v2.4 artifact-work scheduling 연결

R004의 준비 lane은 artifact Goal을 직접 만들거나 시작하지 않는다. 향후 실제
artifact work는 v2.4의 내부 ready frontier가 먼저 소진되고 artifact assessment
조건이 열린 뒤, exact 한 subject만 가진 Goal로 materialize한다. 상태 전이는
`PLANNED → READY → 별도 GOAL_STARTED`를 따르며 한 Goal에 여러 artifact
subject를 합치거나 R004 행을 ledger에서 직접 닫지 않는다. 이 연결은
`plan-manifest.json#static_plan_contract`와 active v2.4 artifact-work scheduling
contract를 함께 만족해야 한다.

## 3. dependency와 병렬 순서

### A. 사실·권한 준비

- B1 facts와 B2 owner decision request packet은 독립적인 범위에서 먼저 준비한다.
- B3는 subject·reviewer independence·acceptance predicate가 확정된 뒤 요청한다.
- 계획 작성이나 내부 에이전트 verdict를 owner·외부 attestation으로 쓰지 않는다.

### B. 내부 내용 lane

- A의 62건에서 병렬 가능한 범위는 read-only 조사와 noncanonical work-contract
  초안뿐이다.
- controlled artifact content 작성·acceptance·successor ledger 후보 변경은
  materialized single `ARTIFACT_WORK` Goal 뒤 canonical writer 한 명만 수행한다.
- content authored나 document review만으로 행을 닫지 않는다.

### C. run lane

- C의 각 ID는 그 ID에 결속된 code/build/data/model/candidate/environment와
  필요한 권한이 준비된 뒤에만 실행한다.
- formal·실기기·보안·접근성·AI 실행을 내부 단위시험으로 대체하지 않는다.
- 동일 raw evidence를 여러 artifact가 참조할 수 있지만 per-ID 결과를 합성하지 않는다.

### D. real event lane

- D의 각 ID는 그 ID의 주체·권한·candidate가 결속되고 정당한 실제 사건이
  있을 때만 실행한다.
- evidence를 만들기 위한 incident, deletion, deployment, 기관 제출, project closure를
  발생시키지 않는다.
- 조건부 사건은 readiness만 준비하고 `WAITING_EXTERNAL` open을 유지한다.

R2-C와 R3-D 사이에는 전역 선후관계가 없다. 각 ID의 실제 dependency만
적용하며, R2의 한 ID가 끝났다는 이유로 무관한 R3 ID를 시작하지 않는다.

## 4. 단계별 gate

| 단계 | 시작조건 | 종료조건 |
|---|---|---|
| R0 plan rebaseline | R011 exact ledger와 review 존재 | exact 131 routing·authority·dependency·claim boundary 검수 |
| R1 content/fact/decision preparation | R0 PASS | A/B 각 행의 exit predicate와 exact source binding 준비 |
| R2 internal runs | 각 C ID의 결속된 입력·환경·권한 준비 | 해당 ID의 positive raw evidence와 independent review PASS |
| R3 actual events | 각 D ID의 정당한 사건·주체·권한 존재 | 해당 ID의 positive actual receipt와 independent review PASS |
| R4 exact257 replay | exact open 131 모두 positive closure candidate이고 per-ID acceptance·authority·독립검수 PASS | 257 unique, open 0, global claim semantics 검증 |
| R5 submission/release separation | R4 독립검수 | submission과 product release 상태 별도 판정 |

이번 작업은 R0 계획 문서 작성까지만 수행하며 R1~R5를 시작하지 않는다.

## 5. 진행률 규칙

다음 축을 합산하지 않는다.

- packet materialized
- content authored
- independent content observation
- content accepted
- owner approved
- run executed
- real event observed
- current-scope N/A closure
- global artifact completion
- release eligibility

`126/257 closed-equivalent`는 현재범위 운영 수치다. 전역 completion claim 0,
formal 0/279, 실제 기기 0, Gate 0/5, release `NOT_ELIGIBLE`을 함께 보고한다.

## 6. 실행 전 필수 확인

- R011 ledger physical hash와 non-self digest
- exact 257 unique and route partition
- R011 independent review findings 0
- Gap·Backlog 재기준선과 artifact lane의 subject 충돌 0
- packet별 writer/reviewer/authority 분리
- resource class와 stop/rollback 계약
- 사용자의 별도 실행 지시

이 문서는 artifact row를 바꾸거나 R011 successor를 적용하지 않는다.
