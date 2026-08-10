# WALKSAFE R007 correction implementation plan R002 independent ledger review R001

## 0. 문서 지위와 최종 판정

```text
artifact_class = READ_ONLY_INDEPENDENT_LEDGER_REVIEW
review_subject_type = NON_EXECUTABLE_CORRECTION_IMPLEMENTATION_PLAN
review_status = COMPLETE
verdict = FAIL_REJECTED_HISTORY_REQUIRE_R003
BLOCKING = 7
MAJOR = 0
MINOR = 0
review_authority = NONE
execution_authorized = false
authoring_authorized = false
checkpoint_mutation_authorized = false
canonical_mutation_authorized = false
product_mutation_authorized = false
release_or_production_authorized = false
official_progress_delta = 0
```

이 문서는 아래 exact R002 구현계획을 읽기 전용으로 독립 검수한 ledger
review다. R002는 이 판정으로 `REJECTED_HISTORY`가 되며, 교정은 R002의 기존
bytes를 수정하지 않는 add-only R003 successor에서 수행해야 한다.

이 review는 R003 작성 또는 실행, PRE-P, G0/P0, V1, H2~H5, Stage-C,
checkpoint, canonical, product, production 또는 release 권한이 아니다.
R003도 exact bytes를 freeze한 뒤 요구된 독립 review gate를 별도로 통과해야
한다.

## 1. exact target과 source identity

검수 대상:

| 항목 | exact 값 |
|---|---|
| target | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R002.md` |
| SHA-256 | `5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0` |
| bytes / lines | `299,396 / 6,338` |
| strict UTF-8 / terminal LF | `PASS / true` |
| CR / NUL | `0 / 0` |
| start identity / end identity | `equal / equal` |

상대 경로 기준점:

```text
docs/control/execution/artifact-closure/run-20260727-001/
```

검수 시작과 모든 검사 종료 뒤 각각 raw bytes를 다시 읽어 계산했다.

```text
start SHA-256 = 5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0
start bytes/lines = 299396/6338
end SHA-256 = 5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0
end bytes/lines = 299396/6338
target mutation during review = 0
```

R002 §1의 exact seven-source set도 실제 bytes와 일치했다.

| Source ID | 파일 | SHA-256 | bytes / lines | disposition |
|---|---|---|---:|---|
| `SRC-R006` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006.md` | `0795cff0703b85469b61f454cbfefd3d6351b94900aaa49025edca3231d35c0a` | `306,496 / 6,670` | `REJECTED_HISTORY` |
| `SRC-R006-FORMAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-review-r001.md` | `ced8c9319996805ebcbb691dfa49cf8d5c6e38283c0f56254a1554952be2f374` | `20,542 / 412` | `FAIL 6/0/0` |
| `SRC-R006-SKEPTICAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-skeptical-review-r001.md` | `a4a3873589192fc09e0a6138aa1d76d0bb7aee1817f4655f2fabb88180332b2c` | `24,902 / 465` | `FAIL 18/4/0` |
| `SRC-PLAN-R001` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001.md` | `bcf67b07c5d2a0454b6a2af325964e62d55f39e04c8c88684b2006738f027944` | `61,353 / 2,024` | `REJECTED_HISTORY` |
| `SRC-PLAN-R001-LEDGER` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001-independent-ledger-review-r001.md` | `1ed21a96908e5a9300ca433c259c12ffab0f2ba16fec3014fcaa1e76a9294c17` | `25,582 / 581` | `FAIL 12/8/0` |
| `SRC-PLAN-R001-STAGEC` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001-independent-stagec-review-r001.md` | `448d8e7cd31ce7d1207d2426955c20c4a51ef65897f85b324ce8e314bfe2746c` | `26,466 / 592` | `FAIL 6/4/0` |
| `SRC-PLAN-R001-AUTHORITY` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001-independent-authority-review-r001.md` | `c1098e45a4e8eaddc33ca7be6a795d8f01daa999cf3e75ff08f8a7f9d6096cf3` | `31,791 / 650` | `FAIL 16/3/0` |

아래 line reference의 `T`는 exact target R002를 뜻한다.

## 2. 독립 검수 방법

R002의 자체 PASS 주장이나 §15 self-audit 결과를 판정 근거로 신뢰하지 않고
다음을 raw Markdown과 일곱 frozen source에서 재계산했다.

1. 대상과 source SHA-256, raw byte count, LF-split line count와 predecessor
   disposition을 검증했다.
2. R006 review의 28 source headings를 추출해 canonical 24개와 네 overlap을
   순방향·역방향으로 비교했다.
3. R001 ledger, Stage-C, authority review의 49 headings를 추출해 §3.2의 18
   correction cluster와 비교했다.
4. canonical, fixture, predicate, output, completion, closure-batch 집합을
   독립 파싱해 24-way cardinality와 unique join을 계산했다.
5. FSM/outbox와 B06, B04, application, H2, M02, H4의 literal row·edge·scope
   cardinality를 seed 없이 다시 계산했다.
6. fence, GFM table, token, placeholder, source guard, freeze/review gate를
   별도 검사했다.
7. 각 source-order cut에서 crash 또는 deadline 직후 실제로 허용되는 다음
   transition이 존재하는지 닫힌 state/event relation만으로 검증했다.

## 3. BLOCKING findings

### R002-LEDGER-REVIEW-BLOCKING-001 — lifecycle PUBLISHED 상태가 deadline 뒤 영구 정지할 수 있다

근거:

- `LOBX002`는 deadline 전 publish 성공을 `PUBLISHING→PUBLISHED`로만 기록하고
  lease를 지운다(`T:1963-1969`).
- lifecycle을 `SEALED`로 바꾸는 유일한 row인 `LOBX009`도 strict deadline
  전만 허용된다(`T:1975`).
- deadline 이후 row는 `PENDING`, `PUBLISHING`, `FAILED`만 처리하고
  `PUBLISHED`에는 적용되지 않는다(`T:1972-1979`).
- 그럼에도 본문은 모든 nonexecution selection이 `SEALED` 또는 signed
  fail-closed terminal에 도달하며 equality 이후에는 fail-close chain만
  legal하다고 주장한다(`T:2032-2047`).

따라서 다음 cut이 닫히지 않는다.

```text
LOBX002 project-file publish success
→ process crash before LOBX009
→ lifecycle_seal_not_after reached
→ batch remains PUBLISHED and lifecycle remains SEAL_QUEUED forever
```

최소 교정:

1. `LOBX002`의 exact reopen 성공과 lifecycle `SEALED` 전이를 한 CAS로
   정산하거나,
2. deadline 이후 `PUBLISHED` exact-file adoption/settlement 전이를 추가하고,
3. 해당 파일이 불일치하거나 정산 불가능할 때의 signed fail-close terminal을
   closed state/event row로 정의한다.

### R002-LEDGER-REVIEW-BLOCKING-002 — ATTEMPT_SEAL outbox는 recovery deadline 뒤 생성되지만 publish할 수 없다

근거:

- attempt state enum은 `OPEN`, `TERMINAL_SELECTED`, `TERMINAL_SETTLED`,
  `SEAL_QUEUED`, `SEALED`뿐이며 seal publication failure terminal이 없다
  (`T:1086-1094`).
- `FSM032/FSM032F`는 `terminal_recovery_not_after` 이후 functional batch를
  `FAILED`로 freeze한 같은 CAS에서 attempt를 `SEAL_QUEUED`로 만들고 새 seal
  obligation을 queue한다(`T:1145-1177`).
- `ATTEMPT_SEAL`은 일반 exact-nine outbox slot 중 하나다(`T:1495-1507`).
- 그러나 일반 `OBX001`~`OBX006` claim, success, failure와 repair는 모두
  `terminal_recovery_not_after` 전에만 허용되고, 이후 `OBX007`~`OBX009`는
  batch를 `FAILED`로 만들 뿐 publish하지 않는다(`T:1547-1557`).
- seal publisher가 사용할 별도 relation 없이 §4.8 `PUBLISH_ONCE`를 사용한다고
  명시한다(`T:2305-2315`). Crash resume와 separate seal deadline 주장도
  같은 gap을 닫지 못한다(`T:2328-2330`).

결과적으로 `FSM032F`가 만든 seal은 생성 시점부터 claim window가 종료돼
있다. 정상 terminal settlement 뒤 seal publication이 recovery deadline까지
실패한 경우에도 attempt는 이미 `SEAL_QUEUED`라 `FSM032`의
`TERMINAL_SELECTED` pre-state를 만족하지 못한다.

최소 교정:

1. functional terminal recovery와 분리된 `SealOutboxDeadlineSetV2`와 seal
   전용 claim/reclaim/publish/failure FSM을 정의한다.
2. 그 FSM은 seal enqueue 시점부터 `attempt_seal_not_after`까지 동일 output
   identity를 publish/adopt할 수 있어야 한다.
3. `SEAL_QUEUED`에서 deadline failure를 유한하게 종결하는 signed fail-close
   state와 successor-blocking rule을 추가한다.

### R002-LEDGER-REVIEW-BLOCKING-003 — H2 same-batch guard의 source order와 outbox state가 양립하지 않는다

근거:

- 공통 §4.8은 `TransitionCoreV2`와 output bytes/content SHA를 CAS 전에
  계산하고, immutable `OutboxBatchV2`의 initial state를 `PENDING`으로 둔다
  (`T:1398-1468`, `T:1525-1540`).
- 공통 outbox lease는 후속 `OBX001` 또는 `OBX002` claim에서 생성된다
  (`T:1547-1555`).
- 반면 `H2BatchGuardReceiptV2` body는 CAS가 고르는
  `cas_linearized_at`, pre/post guard token, publication lease ID/token,
  outbox transition/core와 idempotency digest를 포함한다(`T:4360-4398`).
- 그 guard bytes/signature는 같은 batch의 project co-output인데 CAS 안에서
  처음 생성되고, 같은 CAS가 guard output identity와 one-use lease를 함께
  commit한다고 한다(`T:4401-4413`, `T:4424-4429`, `T:4494-4498`).

즉 공통 schema대로라면 output identity에 들어갈 lease가 아직 없고,
H2 설명대로라면 initial `PENDING`과 후속 claim 없이 lease가 이미 있다.
이를 대체하는 H2 전용 immutable batch/state schema와 complete transition
relation은 정의되지 않았다.

최소 교정:

1. guard project output을 CAS 이전 predecessor-only spec/core로 만들고
   CAS-chosen time/token/lease는 별도 CAS-internal attestation으로 이동하거나,
2. guard를 CAS 안에서 생성해야 한다면 H2 전용 initial state, lease 생성,
   output/obligation/outbox digest source order와 crash recovery relation을
   exact schema/FSM으로 완전히 정의한다.

### R002-LEDGER-REVIEW-BLOCKING-004 — C1→C4 closure predecessor receipt를 생성할 계약이 없다

근거:

- closure DAG는 C2가 durable C1 receipt, C3가 durable C2 receipt, C4가
  durable C3 receipt를 hard predecessor로 요구한다(`T:5325-5348`).
- 그러나 registry path set에는 단 하나의
  `finding-completion.payload.json/signature.json`만 있고
  closure-batch receipt path가 없다(`T:5378-5390`).
- registry envelope은 fixed `registry_version=2`와 단일 row set만 정의한다
  (`T:5397-5421`). C1, C2, C3, C4 snapshot을 같은 literal path에 append,
  version 또는 immutable publish하는 rule이 없다.
- `FindingCompletionRegistryRowV2`는 predecessor를 generic
  `RECEIPT{sha,physical}`로 참조하지만 그 receipt의 body, signature domain,
  literal path, publisher와 생성 조건을 정의하지 않는다(`T:5887-5933`).

따라서 C1 completion rows에서 C2가 소비할 durable identity를 생성할 수
없고, 네 batch를 순차 materialize하면서 단일 immutable registry path를
보존할 수도 없다.

최소 교정:

1. `ClosureBatchReceiptV2`의 exact body/signature/path/publisher와 C1~C4별
   predecessor binding을 정의한다.
2. finding-completion registry를 immutable versioned snapshot 경로로 만들고
   predecessor version/SHA/Physical을 결속하거나,
3. C1~C4 append-only batch receipts를 먼저 만들고 모든 batch 완료 뒤 단일
   final 24-row completion registry를 publication하는 순서로 바꾼다.

### R002-LEDGER-REVIEW-BLOCKING-005 — 핵심 outbox digest의 exact constructor가 없다

근거:

- `OutputEntryV2`는 closed fields를 열거하지만 `output_entry_digest`의
  projection, comparator, domain 또는 JCS preimage를 정의하지 않는다.
  별도로 정의된 것은 개별 `idempotency_key` 식뿐이다(`T:1470-1493`).
- `SettlementObligationV2`, `OutboxBatchV2`와 mutable state가
  `output_entry_digest`, `obligation_digest`, `outbox_digest`,
  `event_set_digest`, `idempotency_set_digest`와
  `ordered_output_identity_digest`를 소비하지만 exact constructor는 없다
  (`T:1509-1540`, `T:1564-1588`, `T:2503-2505`).
- expansion은 `finalization_work_obligation_digest`가 output content SHA를
  제외한 causally-prior projection이라고만 설명하고 그 closed projection과
  literal domain을 제시하지 않는다(`T:2781-2804`).

서로 독립된 producer/checker가 같은 bytes와 digest를 재현할 수 없으므로
transition/obligation/outbox identity와 cycle-avoidance 주장을 구현할 수
없다.

최소 교정:

1. 각 digest마다 exact closed preimage type, array order/comparator, literal
   domain, NUL separator와 RFC 8785 JCS 식을 열거한다.
2. `finalization_work_obligation_digest`에는 포함·제외 field를 명시한 별도
   predecessor-only projection type을 정의한다.
3. 각 constructor의 golden bytes/hash와 wrong-domain/order/field negative
   vector를 추가한다.

### R002-LEDGER-REVIEW-BLOCKING-006 — 24 fixture는 ID-level bijection일 뿐 실행 가능한 registry row가 아니다

근거:

- `FixtureRegistryRowV2`는 input manifest SHA/Physical, expected branch,
  cardinality vector와 다섯 digest, runner literal path/SHA/Physical/argv,
  result literal path/schema SHA를 필수로 요구한다(`T:5805-5829`).
- §14의 24 rows는 fixture ID, canonical ID, narrative positive expectation과
  negative case IDs만 제공한다(`T:5978-6014`). 위 필수 field를 채울 literal
  row 또는 deterministic expansion rule이 없다.
- §15는 predicate ID와 result output path만 연결한다(`T:6016-6075`).
  predicate runner의 literal path, executable SHA/Physical, argv와 input
  contract는 없다.
- 각 predicate는 exact fixture row를 읽어야 하고 prose table을 oracle로
  파싱하면 안 된다고 명시한다(`T:6134-6137`).
- acceptance는 24 fixture와 59 global predicate가 specified and mechanically
  addressable해야 한다고 요구한다(`T:6272-6284`).

따라서 canonical→fixture→predicate→output의 ID 집합 bijection은 맞지만,
P7이 요구하는 fixture registry나 independent runner invocation을
결정론적으로 생성할 수 없다.

최소 교정:

1. 24개 `FixtureRegistryRowV2`의 concrete input/result path, expected branch,
   named vector/digest source와 runner path/argv를 literal row/template로
   제공한다.
2. runtime에만 알 수 있는 SHA/Physical의 predecessor publication 및 row
   freeze 순서를 정의한다.
3. fixture execution receipt와 predicate runner 각각의 executable identity,
   literal argv, input/output schema와 publication path를 닫는다.

### R002-LEDGER-REVIEW-BLOCKING-007 — 다섯 normative GFM table row가 unescaped pipe로 malformed다

근거:

- `T:1142-1143`의 FSM rows에 있는 inline-code `S|N` 때문에 header 기준
  6 cells가 아니라 각각 7 cells로 파싱된다.
- `T:2288-2289,2293`의 strict variant rows도 inline-code `S|N` 때문에
  header 기준 5 cells가 아니라 각각 6 cells로 파싱된다.
- GFM table parser는 inline code span 안의 literal pipe도 table delimiter로
  해석하므로 backslash escaping이 필요하다.
- R002 자체의 `MV005`는 escaped-pipe handling 뒤 malformed table count가
  `0`이어야 한다고 요구한다(`T:6223-6230`). 실제 count는 five malformed
  rows다.

이 오류는 단순 표시 문제가 아니라 FSM pre-state와 seal variant authority
binding이라는 normative cells를 서로 다른 열로 분리하므로 기계적 의미가
변한다.

최소 교정: 위 다섯 곳의 literal을 다음 escaped representation으로 바꾸고
GFM parser로 cell count를 재검사한다.

```text
S\|N
```

## 4. 독립적으로 PASS한 mapping과 cardinality

### 4.1 canonical/source coverage

```text
canonical findings = 24 unique = 20 BLOCKING + 4 MAJOR
R006 review source refs = 28 unique and covered 28/28
unmapped source refs = 0
duplicate source refs = 0
dual-source overlaps = 4
```

네 overlap도 exact했다.

| Canonical ID | Formal source | Skeptical source |
|---|---|---|
| `R007-B007` | `R006-FORMAL-BLOCKING-004` | `R006-SK-BLOCKING-005` |
| `R007-B011` | `R006-FORMAL-BLOCKING-005` | `R006-SK-BLOCKING-009` |
| `R007-B012` | `R006-FORMAL-BLOCKING-006` | `R006-SK-BLOCKING-010` |
| `R007-B014` | `R006-FORMAL-BLOCKING-003` | `R006-SK-BLOCKING-012` |

R001 plan-review source coverage도 exact했다.

```text
ledger review headings = 20
Stage-C review headings = 10
authority review headings = 19
raw headings total = 49
mapped headings = 49
duplicate/missing/extra = 0/0/0
R002 correction clusters = 18 unique
```

### 4.2 fixture/trace/output ID-level bijection

독립 join 결과는 다음과 같다.

```text
§14 fixture rows = 24 unique canonical IDs and 24 unique fixture IDs
§15 trace rows = 24 unique canonical/fixture/predicate tuples
§15 output continuation rows = 24 unique canonical/output/completion tuples
canonical set equality across all three tables = PASS
fixture pair equality across §14 and §15 = PASS
source ref equality across §3.1 and §15 = PASS
negative case IDs = 121 unique
H4 negative IDs = H4-N01 through H4-N16 exact
global assertion IDs = 59 unique
```

이 PASS는 BLOCKING-006의 필수 concrete fixture fields와 runner 계약이
존재한다는 뜻이 아니다.

### 4.3 literal cardinality 재계산

| 영역 | 독립 재계산 결과 |
|---|---|
| all-four FSM / outboxes | `35 FSM rows / 9 OBX rows / 9 LOBX rows` |
| B06 H1 | `10 roles / 26 edges`, duplicate tuple `0` |
| B04 H1 | `6 roles / 23 edges`, duplicate tuple `0` |
| ordinary application | original `48` + triple/signing `4` = inbound `52`; `14 + 1 + 52 + 2 = 69` |
| H2 | `50 files / 25 batches / 25 guards / 75 outputs`; six result batches each exact five |
| M02 | `19 roles / 24 local edges / 3 H2 member edges` |
| H4 scope | read/control/data/union `8/2/0/10` |
| H4 run | read/control `9/11`, data `D_i`, union `20+D_i` |
| H4 Gates | raw/result/receipt/closure `5/5/5/1` |
| H4 must-close | multiplicity `[1,1,1,2,2]`, edges/targets `7/6` |

모든 검사 대상 literal role ID와 edge tuple은 해당 표 안에서 unique였다.

## 5. Markdown, token, placeholder와 gate 검사

독립 검사 결과:

```text
strict UTF-8 decode = PASS
terminal LF = true
CR bytes = 0
NUL bytes = 0
fenced blocks = 185
unmatched fences = 0
GFM malformed rows = 5
unknown static brace tokens = 0
TODO/TBD/FIXME placeholders = 0
shell-template or double-brace placeholders = 0
ellipsis placeholders = 0
own target SHA occurrence inside target = 0
```

exact static brace-token set은 아홉 개로 일치했다.

```text
successor_revision_id
attempt_namespace_id
attempt_id
transition_id
role_id
h2_transaction_id
h4_id
run_id
gate_slug
```

다음 source/freeze guard도 문서상 유지됐다.

```text
R006 disposition = REJECTED_HISTORY
R001 plan disposition = REJECTED_HISTORY
historical/source mutation authorized = false
R002 self-hash embedded = false
same-frozen-SHA review gate declared = true
execution authority declared = NONE
official progress delta = 0
```

그러나 independent review findings가 nonzero이므로 R002의 요구된
`0B/0M/0m` plan-review gate는 실패한다. 구조·산술 PASS는 일곱 Blocking을
상쇄하지 않는다.

## 6. 최종 disposition과 R003 최소 gate

```text
review verdict = FAIL_REJECTED_HISTORY_REQUIRE_R003
R002 correction implementation plan accepted = false
R002 disposition = REJECTED_HISTORY
required correction vehicle = ADD_ONLY_R003
R002 bytes may be edited/replaced/deleted = false
R003 authoring authority granted by this review = false
R003 execution authority granted by this review = false
official progress delta = 0
```

R003는 최소 다음을 모두 만족해야 한다.

1. lifecycle `PUBLISHED` crash/deadline cut을 `SEALED` 또는 signed fail-close로
   유한하게 닫는다.
2. functional terminal recovery와 분리된, 실제 publish 가능한
   `ATTEMPT_SEAL` deadline/FSM을 정의한다.
3. H2 guard의 CAS-chosen fields, output identity, outbox initial state와 lease
   생성 순서를 하나의 closed contract로 통일한다.
4. C1~C4 durable receipt와 immutable completion-registry publication 순서를
   물질화한다.
5. 모든 core outbox digest의 exact domain/preimage/JCS constructor를
   제공한다.
6. 24 fixture와 predicate runner를 concrete path/schema/argv/identity까지
   mechanically addressable하게 만든다.
7. 다섯 GFM table row를 교정하고 actual parser로 malformed `0`을 확인한다.
8. Exact R003 bytes를 freeze한 뒤 그 동일 SHA를 대상으로 요구된 모든 독립
   review에서 각각 findings `0B/0M/0m`을 받아야 한다.

어느 review든 nonzero이면 해당 successor도 수정하지 않고
`REJECTED_HISTORY`로 add-only 보존하며 다음 successor에서 교정해야 한다.

## 7. 최종 non-authority statement

```text
this review grants authority = false
R002 remains non-executable rejected history = true
R002/source bytes may be mutated = false
R003 is required but not authorized for authoring or execution by this review = true
checkpoint/canonical/product mutation authorized = false
production/release authorized = false
official progress delta = 0
```
