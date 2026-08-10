# WalkSafe 다음 단계 상세 로드맵 20260731 R003 독립 공격검수 R001

## 1. exact target과 판정

| 항목 | exact 값 |
|---|---|
| review ID | `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R003-INDEPENDENT-SKEPTICAL-ROADMAP-REVIEW-R001` |
| review subject type | `ROADMAP_REVIEW` |
| 검수일 | `2026-07-31` |
| target | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R003.md` |
| target SHA-256 | `9f72ac89504ed2f3fdee5d50a53713acbb31068cbf4e43a1dce2a000ad618a62` |
| target bytes / lines | `40,836 / 1,235` |
| target physical | `regular, non-symlink, mode=0664, nlink=1` |
| terminal LF / CR / NUL | `true / 0 / 0` |
| target declared status | `NONCANONICAL_PLAN_ONLY / NOT_EXECUTABLE / ROADMAP_REVIEW_PENDING` |
| verdict | `FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR` |
| findings | `BLOCKING=7 / MAJOR=7 / MINOR=0` |
| review authority | `NONE / ABSENT_DENY_ALL` |

검수 시작 시 target identity는 요청값과 exact 일치했다. 이 review는 target,
daylog 또는 다른 파일을 수정하지 않고 위 frozen bytes만 검수했다.

R002 roadmap formal `3/1/0`, skeptical `7/4/0`, R007 target과
formal·skeptical `18/4/0`, 기술 인계서와 현재 v2.4 EPIC-12 계약을 읽기
전용으로 교차 대조했다. R003는 R002의 직접 결함 다수를 고쳤지만, 아래
실행-lineage와 R007 physical remediation 회귀 때문에 same-SHA
`ROADMAP_REVIEW=0/0/0`이 아니다.

```text
VERDICT=FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR
BLOCKING=7
MAJOR=7
MINOR=0
ROADMAP_REVIEW_FINDINGS_ZERO=false
R003_HANDOFF_COMPLETE=false
R007_FINDINGS_CLOSED=false
R007_FORMAL_18_4_0_REMAINS=true
R007_SKEPTICAL_18_4_0_REMAINS=true
PRE_P_CLOSURE_SUCCESSOR_EXISTS=false
PRE_P_SUCCESSOR_READY=false
AUTHORITY_BOUNDARY_DECISION_EXISTS=false
CONSTRUCTIVE_FIXTURE_AUTHORIZED=false
JOURNAL_OR_STAGE_A_TO_G_AUTHORIZED=false
CHECKPOINT_OR_CANONICAL_WRITE_AUTHORIZED=false
OFFICIAL_CREDIT_DELTA=0
```

## 2. R002 correction 재검수

### 2.1 formal `3 BLOCKING / 1 MAJOR`

| R002 formal finding | R003 재검수 |
|---|---|
| G0→G7 PASS 상태와 ready | `PARTIAL`: G6→P7→G7→ready는 닫혔으나 predecessor actual PASS receipt lineage가 Gate schema에 없음 |
| B16↔M04 cycle | `CLOSED`: `M04a→B16a→M04b→B16b` |
| single-owner 모순 | `CLOSED`: accountable owner 1개와 contributor/milestone 분리 |
| G0 successor 자기선행 | `CLOSED`: R003 reviews→Z0 reviewed G0 spec→G0→successor 순서 |

### 2.2 skeptical `7 BLOCKING / 4 MAJOR`

| R002 skeptical finding | R003 재검수 |
|---|---|
| G0 자기선행·불완전 spec | `PARTIAL`: 자기선행과 기본 field는 닫혔으나 read-only/무권한 write 모순과 freshness가 남음 |
| B16↔M04 cycle | `CLOSED` |
| owner cardinality | `CLOSED` |
| V0 governing provenance | `PARTIAL`: 최초 decision lineage는 추가됐으나 재실행의 fresh one-use decision과 physical decision artifact가 없음 |
| G7 ready bypass·review class 혼동 | `CLOSED` |
| Gate spec/result 미래 SHA | `PARTIAL`: pre/post 분리는 닫혔으나 predecessor result, 시간과 result/receipt publication DAG가 없음 |
| Stage D approval | `CLOSED` |
| H1 write allowlist | `PARTIAL`: bridge는 추가됐으나 필수 G1~G5 SPEC gate output이 빠짐 |
| reviewer actor 독립성 | `PARTIAL`: P7/bridge는 보강됐으나 ROADMAP/G0 review는 빠짐 |
| evidence tested-successor·재실행 | `PARTIAL`: SHA 변경 시 재실행은 요구하나 one-use authority 재발급이 없음 |
| artifact wording | `CLOSED`: `closed-equivalent=126/257`, 새 credit 0 |

## 3. BLOCKING findings

### ROADMAP-R003-SK-BLOCKING-001 — read-only G0가 권한 없는 durable write를 요구한다

R003 132행은 G0를 read-only 실행으로 부르고, 180~182행도 Z0/G0를
plan-only 경계로 설명한다. 그러나 `G0ExecutionSpec.write_set`은 새 raw-output
directory를 요구하고(202행), 실행 뒤 result와 receipt를 add-only로
발행해야 한다(225~236행). 현재 authority는 `ABSENT_DENY_ALL`이고, 기술
인계서의 첫 재계산 지시는 “아무 파일도 고치지 말고” 수행하도록 고정한다.
G0는 successor와 governing decision보다 앞이므로 이 durable write에 도달할
별도 authority predecessor도 없다.

따라서 실제로 read-only이면 필수 result/receipt를 만들 수 없고, result를
만들면 현재 권한과 스스로 선언한 read-only 성질을 위반한다. 어느 경우든
G0 PASS→successor 경로가 실행 불가능하다.

Required correction:

- G0 관찰은 stdout/stderr를 외부 durable path에 쓰지 않는 진짜 read-only
  probe로 만들고, 관찰값을 다음 명시적 add-only plan 작성 입력으로만
  전달하거나,
- durable evidence가 반드시 필요하면 exact G0 evidence subject, literal
  write set과 별도 사용자 authority를 G0보다 먼저 두되
  `G0_SPEC_REVIEW`가 write authority가 아님을 명시한다.
- 선택한 모델을 normative DAG, §4, §11.1과 stop rule에 동일하게 반영한다.

### ROADMAP-R003-SK-BLOCKING-002 — Gate의 actual predecessor·시간·publication lineage가 닫히지 않았다

R003 257~273행의 `GateExecutionSpec`은 `predecessor_spec_sha[]`와
`predecessor_required_status=PASS`만 가진다. 279~291행의
`GateExecutionResult`에도 일반 predecessor result/receipt SHA와 그 actual
status가 없고 `authority_predecessor_sha[]`만 있다. 여러 attempt 중 어느
result가 같은 subject에 대한 PASS였는지 독립 재계산할 수 없다.

같은 result schema에는 execution start/end, raw collection, result publication
시각이 없다. V0는 hard deadline, revocation, one-use를 요구하고 G6는
consume/close를 요구하지만, `consume < execute < close ≤ deadline`을 검증할
bytes가 없다.

또한 result 안에 `receipt_path + receipt_sha`를 넣으면서 result와 receipt의
서로 다른 schema·서명 domain·publication order를 정의하지 않는다. Receipt가
result SHA를 역참조하면 hash cycle이고, 역참조하지 않으면 result와 무결하게
결속되지 않는다. “result 자기선행 금지” 문장만으로 이 publication DAG는
정해지지 않는다.

Required correction:

- spec에 expected predecessor subject와 이미 존재하는
  `predecessor_result_sha[]/predecessor_receipt_sha[]`를 넣는다.
- result에 실제 검증한 predecessor result/receipt SHA, status, subject와
  selected attempt를 결속한다.
- 공통 trusted clock/source를 고정하고
  `spec_frozen_at ≤ consume_at ≤ execution_started_at ≤ raw_collected_at
  ≤ execution_ended_at ≤ result_generated_at ≤ close_at ≤ hard_deadline`을
  검증한다.
- `result payload(no receipt SHA) → signature/receipt wrapper`처럼 한 방향
  publication DAG, strict schema와 signature domain을 고정한다.

### ROADMAP-R003-SK-BLOCKING-003 — H1 allowlist가 V0 전 필수 five SPEC gate를 금지한다

Normative DAG는 V0 전에 `G1-SPEC`부터 `G5-SPEC`까지 PASS를 요구하고
(142~153행), G6도 그 다섯 PASS receipt를 요구한다(901~906행). 그러나
§11.1 allowlist(789~803행)는 G0, successor/spec, bridge/decision, V1 evidence,
G6/G7와 review만 열거한다. 실행 뒤 add-only여야 하는 G1~G5 SPEC
`GateExecutionResult`, raw output와 receipt는 없다.

§17은 allowlist 밖 write를 금지하므로 roadmap 자체가 요구한 five SPEC
gate는 output을 발행할 수 없고 V0에 도달하지 못한다.

Required correction: G1~G5 SPEC 각각의 exact add-only raw/result/receipt role,
path root, publisher와 cardinality를 §11.1에 추가하고 그 외 write는 계속
deny한다. P0 freeze artifact도 같은 방식으로 명시해야 한다.

### ROADMAP-R003-SK-BLOCKING-004 — V1 재실행이 one-use governing decision을 재사용할 수 있다

`AUTHORITY_BOUNDARY_DECISION`은 `ALLOW_ONE_ISOLATED_ATTEMPT`와 one-use
consume/close를 요구한다(830~848행). 그러나 successor/spec/input/fixture/
verifier SHA가 바뀌면 “새 attempt”로 V1을 다시 실행하라고만 하고
(893~895행), fresh bridge review, decision, nonce와 consume/close를 요구하지
않는다. Task receipt는 이전 `authority_decision_sha`와 consume receipt를
그대로 결속할 수 있고(879~891행), G6도 decision subject와 새 attempt의
equality를 직접 검사하지 않는다.

이는 한 번 소비된 approval·nonce·receipt를 다른 attempt에 재사용하지
않는다는 기술 인계서의 경계와 정면으로 충돌한다.

Required correction:

- SHA 변화 또는 retry 때 이전 decision을 spent/closed로 유지하고 V1을
  `NOT_RUN`으로 되돌린다.
- 변경된 successor/bridge/fixture/verifier/input SHA와 새 `attempt_id`를
  결속한 fresh user decision, nonce, consume와 close receipt를 요구한다.
- 모든 task/CAS/G6/G7가 같은 decision subject, attempt와 execution window를
  직접 검증하게 한다.

### ROADMAP-R003-SK-BLOCKING-005 — seven V1 task로 G3와 four debt evidence를 생산할 수 없다

G3-EVIDENCE는 producer/graph/digest independent equality를, G4-EVIDENCE는
four debt evidence를 요구한다(298~304행). 그러나 V1 일곱 task
(859~867행)에는 M04/B16 graph membership·digest task가 없다. Debt도
D-B의 runner/CLI와 일부 generation 작업만 간접적으로 겹칠 뿐 D-A lock
epoch, D-C Gateway exact4/exact5, D-D artifact dual-baseline을 닫는 task와
receipt가 없다.

그런데 G6는 G3/G4 PASS와 debt 4개 `CLOSED_CANDIDATE`를 동시에 요구한다
(901~924행). Debt predicate도 `rows=4/closed=4`뿐이라 unique ID, accountable
owner와 verified completion receipt 4개를 검사하지 않는다. 현재 7-task
산술로는 이 조건을 정당하게 만들 수 없다.

Required correction:

- `task_id → evidence gate → finding/debt receipt` exact crosswalk을 만든다.
- M04/B16 independent graph equality와 D-A~D-D 각각을 명시적 task/subtask로
  추가하고 실제 task cardinality와 CAS membership 산술을 갱신한다.
- G6에 debt unique IDs=4, accountable owner cardinality=1, verified debt
  completion receipts=4, missing/duplicate evidence=0을 추가한다.

### ROADMAP-R003-SK-BLOCKING-006 — B09 actual live-root physical publication 계약이 다시 빠졌다

R007 B09는 checkpoint durability 뒤 actual `StageCLiveRootManifest`와 별도
physical publication receipt를 만들고 모든 actual exact6 input/intent/result가
동일 manifest를 결속하도록 요구한다. R003는 actual type 이름을 나열하지만
(391~403행), B09 상세는 H1 synthetic root의 `627-member shape`와 synthetic
integration만 닫는다(543~558행).

Actual manifest/receipt의 canonical path, producer, physical identity/set
digest, checkpoint application predecessor와 same-root exact6 binding이 없다.
이 상태에서는 `StageCLiveRootIntegrationReceipt`나
`StageCApplicationReceipt`라는 이름만으로 R007 B09 physical remediation을
구성할 수 없다.

Required correction: H2 actual contract에 strict `StageCLiveRootManifest`와
physical publication receipt의 path/schema/publisher/predecessor를 정의하고,
checkpoint durability→root publication→actual exact6 input/intent/result→
application receipt의 한 방향 same-root SHA edge를 고정한다.

### ROADMAP-R003-SK-BLOCKING-007 — H4 완료 뒤 H5 Gate 순서가 canonical must-close-before를 위반한다

R003는 H4 완료를 formal 279와 실제 device/event 완료로 정의한 뒤
H4 completion receipt 뒤에 H5 five Gate를 수행한다(1086~1109행). 현재
v2.4 EPIC-12 계약은 실제 사용자시험 전에 admin-recovery Gate, 지원 기기별
사용자시험 범위 확정 전에 phone-queue Gate, 관련 통합시험 완료 전에
server-capacity Gate가 닫혀야 한다고 명시한다. Formal 279에는 실제 기기,
사용자와 관련 통합시험이 포함된다.

따라서 R003 순서대로는 H4가 선행 Gate를 기다리면서, Gate는 H4 completion을
기다리는 cycle이 생긴다.

Required correction: candidate freeze 뒤 Gate별 `must_close_before` edge를
H4 내부에 넣고, 선행 Gate를 지킨 formal/device execution 뒤 H4 completion이
`279 + actual device/event + five Gate`를 같은 candidate에 결속하게 한다.
H5는 release eligibility decision과 deploy/canary/rollback부터 시작한다.

## 4. MAJOR findings

### ROADMAP-R003-SK-MAJOR-001 — ROADMAP/G0 review actor 독립성이 강제되지 않는다

R003/P7와 bridge는 formal actor와 skeptical actor의 차이 및 author 배제를
요구한다(822~823, 929~962행). 반면 현재 `ROADMAP_REVIEW` gate
(125~134행)와 미래 `G0_SPEC_REVIEW`(217~223행)는 두 review와 findings-zero만
요구하고 actor/session identity, formal≠skeptical, author/merge 배제를
요구하지 않는다. 같은 actor가 G0 spec을 쓰고 두 review를 발행해 successor
authoring을 열 수 있다.

Required correction: 공통 `ReviewReceiptIdentity`를 ROADMAP, G0_SPEC,
SUCCESSOR, BRIDGE, EVIDENCE 전체에 적용하고 각 gate가 pair actor inequality,
subject author/merge/producer와 reviewer 집합의 disjointness를 검사하게 한다.

### ROADMAP-R003-SK-MAJOR-002 — G0가 R003/reviews와 관찰 freshness를 보호하지 않는다

G0 protected path는 §1.1 전부라고만 한다(212~217행). §1.1에는 자기 SHA를
내용에 넣을 수 없는 R003와 아직 없던 두 R003 review가 없으므로, 미래
G0 spec이 exact R003와 실제 review SHA를 추가해야 하지만 그런 요구가 없다.
또한 G0 result에는 observation start/end, before/after snapshot digest와
P0가 그 end snapshot을 계승하는 rule이 없다(225~249행). 따라서 review 뒤
R003가 바뀌거나 G0 PASS 뒤 protected input이 drift해도 이전 PASS를 재사용할
수 있다.

Required correction: 미래 G0 spec이 exact R003 physical identity와 두 actual
review SHA를 추가하고, before/after 동일 snapshot digest와 observation
interval을 result에 넣으며 P0가 exact G0 end snapshot을 재확인하게 한다.
Drift이면 G0와 그 이후 gate를 `NOT_RUN`으로 되돌린다.

### ROADMAP-R003-SK-MAJOR-003 — P0 inventory와 synthetic 627 derivation이 orphan이다

Normative DAG는 P0 protected/mutable inventory freeze를 요구하고(142~145행)
B08/B11/B15/B17과 여러 matrix edge가 이를 소비한다. 그러나 P0 output의
canonical path, strict schema, categories, producer, verifier와 receipt가
없다. B09는 근거 없이 `627-member shape`를 고정하면서(543~558행) P0
inventory나 approved add-only delta에서 그 set/count를 파생하지 않는다.

Required correction: generated/tree/symlink/direct-origin/Git/runner/lock/
Gateway/baseline epoch를 포함한 `P0InventoryManifest`와 freeze receipt를
정의하고, B09 synthetic set은 그 manifest와 명시적 allowed delta로부터
결정론적으로 파생되게 한다.

### ROADMAP-R003-SK-MAJOR-004 — 두 개의 normative dependency 표현이 일치하지 않는다

§6.3의 “cycle-free normative edge”(351~365행)는 matrix가 요구하는
B01→B02, B12→B13→B07/B14, B17→B15, D-A/D-B→B18 등의 edge를 누락한다.
반대로 matrix의 “ordered milestone 요약”(740~763행)은 더 많은 dependency를
규정한다. 어느 집합이 G3/G6 cycle·predecessor verifier의 정본인지 정의되지
않아 조기 실행과 서로 다른 graph digest가 가능하다.

Required correction: exact node/edge manifest 하나를 normative source로
고정하고 §6.3, 22-row matrix, gate spec과 verifier expected digest를 그
manifest에서 결정론적으로 생성한다.

### ROADMAP-R003-SK-MAJOR-005 — B01 signature-outside-payload wrapper가 누락됐다

R007 B01 remediation은 strict `CanonicalRootSpec`과
signature-outside-payload wrapper를 요구한다. R003 B01은 JCS bytes와
signature domain을 말하지만(434~447행), payload와 wrapper schema,
서명 입력 bytes와 wrapper publication 관계를 정의하지 않는다. Payload
내부 signature/self-hash 문제를 미래 successor가 다시 만들 수 있다.

Required correction: unsigned payload canonical bytes, detached signature
wrapper, 각각의 schema/path/domain과 `payload SHA → wrapper` publication
edge를 B01 strict output에 복원한다.

### ROADMAP-R003-SK-MAJOR-006 — M04 producer Physical이 closure schema에서 빠졌다

R007 M04는 graph membership/digest의 독립 producer/checker Physical을
요구한다. R003 공통 row schema는 verifier만 가지며(416~431행), M04a/M04b
상세에도 producer executable/path/SHA가 없다(721~734행). Accountable phase는
artifact producer Physical을 대신하지 않는다.

Required correction: 공통 row에 output별 `producer_physical`과 SHA/argv를
추가하고, M04a extraction 및 M04b serialization/digest 각각의 producer와
independent checker를 다른 Physical로 결속한다.

### ROADMAP-R003-SK-MAJOR-007 — formal 279가 승인된 N/A를 불가능하게 한다

R003 H4는 `formal PASS=279`를 요구한다(1094~1103행). Canonical EPIC-12
계약은 279개 행을 모두 보존하되 applicable row는 PASS, non-applicable row는
권한 있는 approver의 `APPROVED_NOT_APPLICABLE` 결정·시각을 허용한다.
R003 식은 합법적인 N/A 한 건만 있어도 H4를 영구 실패시키거나 N/A를 PASS로
오표기하게 한다.

Required correction:

```text
formal rows = 279
PASS + APPROVED_NOT_APPLICABLE = 279
all applicable rows = PASS
FAIL = 0
NOT_RUN = 0
each N/A has approval authority receipt and timestamp
```

## 5. 확인된 폐쇄와 R007 crosswalk

R003가 실제로 닫은 항목은 다음과 같다.

- G0 successor 자기선행은
  `R003 reviews→G0 spec reviews→G0 PASS→successor`로 해소
- Gate expected spec과 actual result의 기본 pre/post 분리
- B16/M04를 `M04a→B16a→M04b→B16b`로 단방향화
- 22개 finding 각각 accountable owner 정확히 1개
- H1 synthetic와 H2 actual Stage-C tagged type의 상호 대체 금지
- G6→four class-specific P7 reviews→G7→ready와 exact G7 receipt
- actual Stage-C receipt + D subject + D one-use approval의 Stage D 경계
- evidence receipt/CAS의 tested successor/spec/input/fixture/verifier SHA
  결속과 SHA 변경 시 V1 재실행 요구
- artifact 표현 `closed-equivalent=126/257`, 공식 delta 0
- B18 predecessor는 D-A/D-B만 `yes`, D-C/D-D는 `no`
- old S1, rejected R007과 stale FP-048 실행 금지

산술상 R007 mapping은 `18 BLOCKING + 4 MAJOR`, finding 22 unique, debt
D-A~D-D 4 unique로 보존됐다. 주요 physical field도 B03/B05/B07/B14/B17/M02
등에서 보강됐다. 그러나 B09 actual publication, B01 wrapper와 M04 producer가
위 findings처럼 축약 과정에서 빠졌으므로 이것은 R007 finding closure나
physical remediation 완료 판정이 아니다.

## 6. review ceiling과 disposition

이 review의 최대 효력은 exact R003 bytes에 대한 비권한
`ROADMAP_REVIEW` 품질 판정이다. 이 review는 다음 중 어느 것도 아니다.

- R007 finding closure evidence 또는 PRE-P successor
- `G0_SPEC_REVIEW`, `SUCCESSOR_PLAN_REVIEW`,
  `AUTHORITY_BRIDGE_REVIEW`, `CONSTRUCTIVE_EVIDENCE_REVIEW`
- V0/V1, journal bootstrap 또는 Stage A~G approval/receipt
- checkpoint, canonical, Goal, artifact, formal, device/event, Gate,
  production 또는 release credit

따라서 R003 §21의 skeptical `ROADMAP_REVIEW=0/0/0` 조건은 충족되지 않는다.
R003와 이 review를 history로 보존하고 위 7 BLOCKING과 7 MAJOR를 닫는 새
add-only roadmap successor를 작성한 뒤 formal/skeptical
`ROADMAP_REVIEW`를 처음부터 다시 수행해야 한다.

```text
TARGET_UNCHANGED=true
ROADMAP_FINDINGS_ZERO=false
R003_USABLE_AS_EXECUTION_SUCCESSOR=false
R007_REMAINS_REJECTED_DEFERRED=true
CURRENT_AUTHORITY=ABSENT_DENY_ALL
NEXT_ACTION=ADD_ONLY_ROADMAP_SUCCESSOR_AND_NEW_DUAL_ROADMAP_REVIEWS
```
