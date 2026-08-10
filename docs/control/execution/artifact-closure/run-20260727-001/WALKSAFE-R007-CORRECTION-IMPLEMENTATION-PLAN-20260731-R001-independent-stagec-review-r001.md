# WALKSAFE R007 correction implementation plan R001 독립 Stage-C 검수 R001

문서 ID:
`WS-WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001-INDEPENDENT-STAGEC-REVIEW-R001`

검토일:
`2026-07-31`

검토 유형:
`READ_ONLY_CONSTRUCTIBILITY_AND_MECHANICAL_REVIEW`

검토 권한:
`NONE / ABSENT_DENY_ALL`

판정:
`FAIL_REQUIRES_ADD_ONLY_CORRECTION_IMPLEMENTATION_PLAN_R002`

Finding:
`BLOCKING=6 / MAJOR=4 / MINOR=0`

## 1. exact target identity와 검수 ceiling

| 항목 | exact 값 |
|---|---|
| target | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001.md` |
| SHA-256 | `bcf67b07c5d2a0454b6a2af325964e62d55f39e04c8c88684b2006738f027944` |
| bytes / lines | `61,353 / 2,024` |
| owner | `uid=1000 / gid=1000` |
| mode / type / nlink | `0664 / regular non-symlink / 1` |
| terminal LF / CR / NUL | `true / 0 / 0` |
| target declared status | `PRE_REVIEW / NON_EXECUTABLE_CORRECTION_IMPLEMENTATION_PLAN` |

검수 시작과 보고서 작성 직전에 위 identity를 다시 계산했다. 이 review는
target, R006, 두 source review, checkpoint, canonical, product, daylog와
memory를 수정하지 않았다.

이 판정의 범위는 target이 선언한 correction을 이용해 self-contained R007을
추측 없이 작성할 수 있는지에 대한 constructibility와 mechanical consistency다.
특히 B06, B04 issuance/consume/application, M02, H2 exact6/current authority,
H4 scope/run/exact-five Gate, alias/count/digest/direct-edge 규칙과 source-order
cycle을 검토했다.

이 review는 R002 작성, PRE-P 실행, H2/H4 실행, authority 발행, checkpoint나
canonical 변경, product 변경, Gate·formal·release credit을 허용하지 않는다.

## 2. exact source identities

Target §1이 선언한 source set 세 개를 독립 재확인했다.

| Source ID | exact 파일 | SHA-256 | bytes | lines | disposition |
|---|---|---|---:|---:|---|
| `SRC-R006` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006.md` | `0795cff0703b85469b61f454cbfefd3d6351b94900aaa49025edca3231d35c0a` | 306496 | 6670 | `REJECTED_HISTORY` |
| `SRC-FORMAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-review-r001.md` | `ced8c9319996805ebcbb691dfa49cf8d5c6e38283c0f56254a1554952be2f374` | 20542 | 412 | `FAIL`, `6/0/0` |
| `SRC-SKEPTICAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-skeptical-review-r001.md` | `a4a3873589192fc09e0a6138aa1d76d0bb7aee1817f4655f2fabb88180332b2c` | 24902 | 465 | `FAIL`, `18/4/0` |

상대 경로 기준점:

```text
docs/control/execution/artifact-closure/run-20260727-001/
```

Target의 canonical correction ledger도 재계산했다.

```text
canonical BLOCKING rows = 20
canonical MAJOR rows = 4
canonical MINOR rows = 0
source BLOCKING references before dedupe = 24
source MAJOR references = 4
duplicate canonical IDs = 0
```

이 identity와 ledger PASS는 아래 constructibility finding을 상쇄하지 않는다.

## 3. 독립 판정 요약

Target은 R006보다 authority aggregation, future-reference 분리, B06/B04 edge
산술, exact6 expansion, H4 count를 구체화했다. 그러나 다음 필수 계약이 아직
닫히지 않았다.

1. `AuthorityContextV2`가 pre-activation artifact와 activation receipt 자체에
   future/self SHA를 요구한다.
2. B04 canonical application이 source가 요구한 detached payload/wrapper
   pair가 아니며 issuance/consume/application strict lineage가 없다.
3. H2의 guard `25`가 raw evidence file `24`개를 보호하지 않고 guard 자체의
   재귀·면제 규칙도 없다.
4. H4 scope/run plane은 row identity 없이 count만 있고 same-store schema와
   literal source order가 없다.
5. H4 ordered exact-five Gate ID, 15개 artifact, six literal target과 seven
   direct-edge mapping이 없다.
6. aggregate transition receipt와 outbox가 mutual-hash 또는 undefined
   transition digest를 만든다.

따라서 target R001은 self-contained R007 authoring instruction으로 수용할 수
없다. R001은 bytes를 고치지 않고 `REJECTED_HISTORY`로 보존하고, 아래
remediation을 반영한 add-only R002가 필요하다.

## 4. numeric recalculation

| 대상 | target 주장 | 독립 산술 | 기계 판정 |
|---|---:|---:|---|
| canonical ledger | `20B/4M/0m` | `20/4/0`, source refs `24+4` | PASS |
| B06 roles | `10` | five pairs `5×2=10` | arithmetic PASS |
| B06 direct edges | `26` | `5+1+4+8+6+2=26` | arithmetic PASS |
| B04 H1 roles | `6` | three pairs `3×2=6` | arithmetic PASS |
| B04 H1 edges | `22` | `3+3+1+10+4+1=22` | arithmetic PASS; M03 direct edge ambiguity |
| B04 application inbound | `51` | asserted `48+3=51` | `48` source rows absent |
| B04 ordinary actual edges | `68` | asserted `14+1+51+2=68` | `14/48/2` rows absent; application pair 추가 시 stale |
| H2 file roles | `49` | `2+10+1+(6×6)=49` | arithmetic PASS |
| H2 guards | `25` | `2+1+2+1+1+1+12+1+1+1+1+1=25` | raw 24 files uncovered |
| H2 protected writes | `74` | `49+25=74` | arithmetic PASS, authority coverage FAIL |
| H2 intermediate edges | `306` | `262-7+51=306` | `262` literal seed absent; final total unknown |
| M02 roles | `19` | inherited exact 19-row table | arithmetic PASS |
| M02 local edges | `23+1=24` | `4 base + 19 source + 1 predecessor=24` | triple member edges unassigned |
| H4 scope roles | `2` | two filenames | arithmetic PASS |
| H4 per-run roles | `11` | eleven filenames | arithmetic PASS |
| H4 Gate raw/result/receipt/closure | `5/5/5/1` | count arithmetic PASS | exact identity absent |
| H4 must-close edges | `7` | `[1,1,1,2,2]` sum=`7` | PASS |
| H4 unique targets | `6` | asserted only | mapping absent, unverifiable |

Source 요구대로 canonical application을 payload/wrapper 두 physical role로
고치면 최소 다음 값이 다시 계산돼야 한다.

```text
B04 ordinary actual edge total >= 69
H2 fixed non-exact6 roles = 11
H2 file roles = 50

if application pair uses per-file guards:
  H2 guards = 26
  H2 protected writes = 76

if application pair is one explicitly atomic guarded batch:
  H2 guards = 25
  H2 protected writes = 75
```

위 두 H2 대안 중 하나를 strict batch contract로 선택해야 하며, 현재
`49/25/74`를 그대로 freeze할 수 없다.

## 5. BLOCKING findings

### R007-STAGEC-BLOCKING-001 — `AuthorityContextV2`에 future/self SHA cycle이 있다

근거:

- target 236~238행은 request/response/decision, 세 grant, activation,
  consume, revoke와 terminalization까지 모두 full `AuthorityContextV2`를
  embed하도록 한다.
- target 278~285행의 context는 `aggregate_activation_receipt_sha`,
  `granting_artifact_payload_sha`,
  `granting_artifact_wrapper_or_decision_sha`를 필수로 가진다.
- target 387~396행의 유일한 순서는
  `decision → watchdog/grants → activation → execution consume`이다.

Pre-activation request/decision/grant는 future activation receipt SHA를 알 수
없다. Activation receipt도 full context를 가진다면 자기 SHA를 body에 넣어야
한다. Grant payload가 자신의 payload SHA를 `granting_artifact_payload_sha`로
가지는 해석도 self-preimage다. `NA`, stage discriminant 또는 predecessor-only
variant는 정의되지 않았다.

영향:

- request/grant/activation source order를 구성할 수 없다.
- B04 issuance의 `full AuthorityContextV2`와 H2/H4 downstream authority가
  같은 cycle을 상속한다.
- `R007-B003`, `R007-B005`, `R007-B006`, `R007-M001` closure가 성립하지 않는다.

최소 remediation:

1. Genesis 시점에 이미 존재하는 값만 가진
   `AuthorityFrozenIdentityContextV2`를 분리한다.
2. Request/decision/grant/activation/consume별 closed tagged binding을
   정의한다.
3. Activation SHA는 post-activation consumer부터만 허용한다.
4. 모든 artifact body에서 own payload/wrapper/receipt SHA를 금지한다.
5. Byte-equality 대상은 full evolving context가 아니라 frozen identity,
   allowlist, plane, deadlines의 exact subset으로 고정한다.

### R007-STAGEC-BLOCKING-002 — B04 canonical application이 detached pair를 구성하지 못한다

근거:

- target 928~950행은 canonical application role을 하나로 고정하고
  `application-receipt.json` 한 path만 정의한다.
- 같은 strict body에는 `full-payload detached signature input`만 있고
  detached signature artifact path, wrapper schema, verifier publisher와
  Physical이 없다.
- target 965~973행은 application inbound `51`과 ordinary total `68`을
  주장하지만 `48`, `14`, terminal `2`의 literal edge rows를 열거하지 않는다.
- `SRC-SKEPTICAL` 263~281행은 complete application payload/wrapper closed
  schema와 모든 direct Physical/hash edge를 required correction으로 정한다.

Issuance future SHA 금지와 후행 inward-reference 방향(target 889~924행)은
올바르다. 그러나 ordinary/recovery issuance와 consume의 exact filename,
schema, publisher/Physical, transition body와 application pair가 없어 실제
artifact를 만들 수 없다.

영향:

- `R007-B015`는 닫히지 않는다.
- Payload→wrapper edge가 빠져 B04 `68`은 stale다.
- H2 fixed role `application-receipt reference/binding` 하나와
  `49/25/74`도 stale다.

최소 remediation:

1. Canonical application payload와 detached signature wrapper의 exact two
   paths를 정의한다. 기존 canonical filename을 payload로 유지한다면 exact
   sibling signature path를 추가한다.
2. 두 strict schema의 closed field list, signature domain/input,
   serializer/verifier actor와 Physical을 고정한다.
3. Ordinary/recovery issuance와 consume receipt의 exact paths와 tagged XOR
   body를 정의한다.
4. Issuance→consume→POSTCHECK→application payload→wrapper→FINALIZED→CLOSED
   direct edges를 stable ID로 전부 열거한다.
5. B04/H2 logical-role, physical-write와 edge totals를 재생성한다.

### R007-STAGEC-BLOCKING-003 — H2 guard `25`가 raw evidence `24`개를 보호하지 않는다

근거:

- target 988~1037행의 file registry는 binding `2`, fixed `10`, exact6 input
  `1`, six invocation×six files `36`, 합계 `49`다.
- 각 invocation의 여섯 파일 중 raw/evidence files는
  `stdout.raw`, `stderr.raw`, `access-trace.raw`, `access-trace.json` 네 개다.
  따라서 raw/evidence role은 `6×4=24`다.
- target 1071~1085행의 guard `25`는 binding `2`, fixed roles `10`,
  exact6 input `1`, invocation intent/result `12`만 정확히 센다. Raw
  `24`는 없다.
- target 1068~1069행은 각 protected file transition이 current authority를
  다시 검사하고 guard receipt를 남긴다고 주장한다.
- `SRC-SKEPTICAL` 301~318행은 six invocation/result/evidence member와 각
  transition의 current same-store head, consume Physical, unrevoked state,
  deadline 재검증을 요구한다.

Guard receipt `25`를 `protected writes=74`에 포함하면서 guard receipt 자체가
다시 guard 대상인지, CAS service의 atomic co-write라 면제되는지 정의하지
않았다. Raw bytes가 생성되기 전에 exact output SHA를 guard에 넣을 수도 없고,
생성 뒤 임시 bytes를 protected root에 먼저 쓰면 current-authority 검사가
늦는다. `magic/frame_kind` framing도 literal magic, integer width, endian,
SHA encoding이 없어 byte-deterministic하지 않다.

영향:

- Raw evidence를 바꾸거나 다른 authority cut에서 발행해도 guard count
  predicate가 통과할 수 있다.
- Guard-of-guard infinite regress 또는 untrusted guard exception 중 하나를
  임의로 선택해야 한다.
- `R007-B017`과 H2 `49/25/74`, edge subtotal `306` closure가 성립하지 않는다.

최소 remediation:

다음 둘 중 정확히 하나를 선택한다.

1. `25 transition batches`를 정의한다. 각 invocation result batch는 staged
   raw four + result exact five outputs를 한 guard/outbox에 결속하고, guard
   receipt는 CAS atomic co-write라 재귀 guard 대상이 아님을 명시한다.
2. 모든 file별 guard를 정의하고 guard/write count를 재산출한다.

두 방식 모두 guard path/schema/publisher/Physical, pre/post token,
target-set digest, lease redemption, staged Physical, create-exclusive/fsync/
reopen/adoption 순서와 next-guard predecessor를 literal edge로 가져야 한다.
또한 `262` base edge table을 전부 열거한 뒤 final H2 edge total을 생성해야 한다.

### R007-STAGEC-BLOCKING-004 — H4 scope/run authority가 count-only다

근거:

- target 1173~1198행은 scope receipt 두 path와 plane `8/2/0/10`만 정의하며
  eight read entries를 열거하지 않는다.
- target 1202~1215행은 per-run eleven filenames를 열거하지만 schema SHA,
  publisher/Physical, run key/token과 literal read/data entries가 없다.
- target 1239~1248행의 `9/11/D_i/(20+D_i)`는 nine read rows, ordered data
  manifest, intersection/disjointness와 digest 없이 합만 주장한다.
- File listing은 close intent/receipt를 result receipt보다 앞에 두지만
  target 1235~1237행과 1308행은 result가 close의 predecessor라고 한다.
- `SRC-SKEPTICAL` 320~338행은 signed scope authority predecessor와
  run authority/consume/result/close의 strict closed schema 및 atomic consume
  store를 요구한다.

Union `10`과 `20+D_i`는 set disjointness가 증명될 때만 count로 성립한다.
현재 row identities가 없으므로 alias, overlap, candidate drift와 wrong
publisher를 판정할 수 없다.

영향:

- `R007-B018`이 닫히지 않는다.
- Run-specific authority key와 `AttemptAuthorityAggregateV2` 관계를 구현자가
  임의로 정해야 한다.
- `H4-N04`, `H4-N05`~`H4-N13` fixture를 결정적으로 만들 수 없다.

최소 remediation:

1. Scope eight read + two control exact rows를 path/schema/publisher/Physical과
   함께 열거한다.
2. Per-run nine read + eleven control + ordered `D_i` rows를 열거하고
   pairwise intersection=`0`과 digests를 고정한다.
3. Run key, initial token, operation/settlement deadline와
   `ABSENT→UNSPENT→CONSUMED_UNDISPATCHED→DISPATCHED→RESULT_RECORDED→SPENT_CLOSED`
   CAS를 strict schema로 정의한다.
4. Role registry order와 source order를 result→close로 통일한다.

### R007-STAGEC-BLOCKING-005 — H4 ordered exact-five와 six targets가 열거되지 않았다

근거:

- target 1250~1266행은 “ordered exact five”와 `5/5/5/1` count만 둔다.
- Target 전체에 exact Gate ID string이 하나도 없다.
- R006 5964~5970행이 보존한 ordered Gate IDs는 다음 five다.

```text
GATE-SINGLE-ADMIN-RECOVERY-DRILL
GATE-PHONE-QUEUE-BYTE-LIMIT
GATE-SERVER-CAPACITY-STATE-CONTRACT
GATE-RAW-COLLECTION-RELEASE-REVIEW
GATE-CLOUD-COST-MEASUREMENT
```

- target 1289~1312행은 edge multiplicity `[1,1,1,2,2]`, edge `7`, unique
  target `6`만 쓰고 Gate→target mapping과 target identity/path를 쓰지 않는다.
- Gate raw/result/receipt 15개와 exact-five closure receipt의 exact path,
  schema, publisher/Physical이 없다.
- `H4CompletionReceipt` row에는 `waived=false`, reviewer Physical/signature
  binding이 없다.
- `SRC-SKEPTICAL` 340~354행은 ordered exact-five Gate result/receipt array,
  raw/reviewer/candidate equality와 모든 literal downstream target edge를
  required correction으로 정한다.

영향:

- `[1,1,1,2,2]=7` 산술은 맞지만 unique target `6`을 재계산할 수 없다.
- 다른 Gate ID나 다른 target artifact를 사용해도 count-only fixture가
  통과할 수 있다.
- `R007-B019`, `H4-N14`~`H4-N16`이 닫히지 않는다.

최소 remediation:

1. 위 ordered five Gate ID를 exact carry한다.
2. Gate별 raw evidence, result, receipt의 exact 15 paths와 strict schemas를
   고정한다.
3. Six target role/path/schema identities와 seven Gate→target direct edges를
   stable ID로 열거한다. Shared target은 exact alias/identity로 한 번만 센다.
4. Exact-five closure receipt path/schema/publisher를 정의하고
   `gate_id, raw/result/receipt Physical+SHA, reviewer actor/Physical,
   candidate, status=PASS, waived=false`를 직접 embed한다.

### R007-STAGEC-BLOCKING-006 — transition receipt와 outbox hash source order가 닫히지 않았다

근거:

- target 348~359행은 `AuthorityAggregateTransitionReceiptV2`가
  pre/post state, CAS time, event, outcome/outbox를 담는다고 한다.
- target 549~569행의 `SettlementObligationV2`는
  `aggregate_transition_sha`와 각 output의 final `content_sha`를 필수로 가진다.
- target 580~584행은 transition CAS와 obligation/outbox를 같은 transaction에
  저장한다.

`aggregate_transition_sha`가 transition receipt SHA라면 receipt가 outbox를
담고 outbox가 receipt SHA를 담는 mutual-hash cycle이다. CAS core digest를
뜻한다면 exact preimage, domain, receipt와의 equality가 정의되지 않았다.
Output artifact가 transition receipt SHA를 inward-reference하는지도 닫혀
있지 않아 final `content_sha`를 CAS 전에 계산할 수 있는지 판정할 수 없다.

영향:

- Consume/selection atomicity를 구현할 단일 source order가 없다.
- `R007-B010`, `R007-B011` settlement fixture가 implementation-dependent다.

최소 remediation:

1. Predecessor-only `AuthorityAggregateTransitionCoreV2`와 exact domain/JCS
   digest를 정의한다.
2. Obligation/output은 precomputable core digest만 참조하거나, 별도
   transition receipt→output 단방향 DAG를 정의한다.
3. Transition receipt, obligation, outbox batch, output, settlement
   attestation, seal의 exact source order와 각 hash preimage를 표로 고정한다.
4. 어떤 object도 자신의 SHA 또는 future receipt SHA를 body에 넣지 않음을
   fixture로 검사한다.

## 6. MAJOR findings

### R007-STAGEC-MAJOR-001 — B06 arithmetic은 맞지만 strict/literal patch가 닫히지 않았다

Target 737~838행의 B06 five pairs와 edge group 산술은 `10/26`으로 맞다.
N26의 stale target schema/value 제거, V1 semantic wrapper exclusion과 full
payload signature 방향도 `SRC-SKEPTICAL` 283~299행의 finding에 부합한다.

그러나 다음 값은 아직 conceptual label이다.

```text
protected exact26 source
candidate target map
aggregate equality receipt
U1 / M03 / exact6 / ApplicationReceiptTargetSpec
resolved subject
formal/skeptical/review-pair receipts
exact signature domain
```

각 revised payload/wrapper의 closed field list, literal source role/path,
schema SHA, publisher/verifier actor와 Physical table이 없다. R006의 stale
schema에서 어떤 field를 제거·유지·rename하는지도 one-to-one patch로 닫히지
않았다.

최소 remediation:

1. Five payload/wrapper의 complete closed schema table을 작성한다.
2. `exact domain`을 object별 literal domain으로 교체한다.
3. 26 edges를 stable edge ID, literal source/target role ID와 path로 펼친다.
4. Protected exact26 row schema와 N26/T1 map equality를 exact inward-carry한다.

### R007-STAGEC-MAJOR-002 — B04 H1 `22`에 M03 direct edge 보존 여부가 불명확하다

Target 866~876행 표의 합은 `22`다. 그러나 R006 2838~2841행은 constructive
fixture가 B06 objects와 M03 recovery contract를 직접 소비한다고 규정한다.
Target은 M03→contract와 contract wrapper→fixture를 두지만 M03→fixture를
명시적으로 제거하지 않는다.

두 해석은 다음처럼 갈린다.

```text
preserve R006 fixture direct M03 input:
  B04 H1 edges = 23

replace direct M03 input with contract-wrapper-only input:
  B04 H1 edges = 22
  fixture strict body must remove direct M03 field
```

최소 remediation:

- 후자를 선택해 `22`를 유지한다면 fixture closed schema와 predecessor table에
  direct M03 field/edge가 없음을 명시한다.
- 전자를 선택한다면 M03→fixture stable edge를 복원하고 count/digest를 `23`으로
  재생성한다.

### R007-STAGEC-MAJOR-003 — M02 triple constructor의 direct member edges가 없다

Target 1132~1169행의 다음 correction은 맞다.

```text
M02 roles = 19
M02X001..M02X023 = 23
M02P001 = 1
LIVE_ROOT_TRIPLE members =
  StageCLiveRootManifestPayload
  StageCLiveRootPhysicalPublicationReceipt
  LIVE_ROOT_PUBLISHED ProgressRef
U1 = DirPhysical
```

이는 `SRC-SKEPTICAL` 394~407행의 manifest payload, U1 type와 edge ID
correction을 반영한다. 그러나 composite `LIVE_ROOT_TRIPLE`을 만들 direct
member edges가 없다. 필요한 topology는 최소 다음이다.

```text
H2 literal-root binding wrapper → LIVE_ROOT_TRIPLE constructor
manifest payload → LIVE_ROOT_TRIPLE constructor
physical-publication receipt → LIVE_ROOT_TRIPLE constructor
LIVE_ROOT_PUBLISHED ProgressRef → LIVE_ROOT_TRIPLE constructor
LIVE_ROOT_TRIPLE → ACTUAL-EXACT6-INPUT
```

기존 19 source edges에는 마지막 outgoing edge만 포함할 수 있다. 세 member
edges를 H2 namespace에 둘지 M02 namespace에 둘지 지정하지 않았다. M02 local
count에 포함하면 `24→27`이고 H2에 두면 H2 final derived total에 `+3`이
필요하다.

최소 remediation:

- 위 네 constructor inbound와 one outgoing edge에 exact stable ID와 namespace를
  배정하고 M02/H2 totals 및 digest를 재생성한다.

### R007-STAGEC-MAJOR-004 — alias/count/digest registry가 결정론적 closed schema가 아니다

근거:

- target 1637~1647행은 seven registry를 선언하지만 target 1649~1673행은
  Role/Edge row 일부만 정의한다.
- `NodeRegistryV2`, `BranchCardinalityRegistryV2`,
  `AuthorityScopeRegistryV2`, fixture/finding row schema가 없다.
- target 1677~1687행은 alias가 same bytes/Physical이어야 한다고 요구하지만
  Role row와 target 1718~1720행의 physical count tuple에는 content SHA와
  output FilePhysical identity가 없다.
- target 1693~1707행의 `<registry-domain>`은 literal string이 아니며
  `closed BranchEnum`, `normalized_branch_predicate`, `branch_instance`의
  grammar/canonical encoding도 없다.
- Target 382~383, 1624~1628, 1995~2000행은 frozen R007에 unresolved macro를
  금지하지만 H2 transaction ID, H4 run ID와 runtime `D_i`는 future instance다.

따라서 같은 semantic graph를 두 author가 다른 predicate string, alias
resolution, template/instance expansion과 digest로 만들 수 있다.

최소 remediation:

1. 모든 registry의 closed row schema, explicit ordinal과 literal digest
   domain을 정의한다.
2. Role/node row에 expected content identity와 output Physical identity,
   `alias_of_role_id`를 넣는다.
3. Branch predicate를 closed AST로 정의하고 RFC8785 JCS normalization을
   고정한다.
4. Static `RoleTemplateRegistryV2`와 runtime literal
   `RoleInstanceRegistryV2`를 분리한다.
5. H2 canonical application payload/wrapper alias target을 각각 exact role ID로
   지정한다.

## 7. 확인된 개선과 유지해야 할 부분

R002는 다음 target 개선을 유지해야 한다.

1. R006과 두 review의 exact source identity와 `REJECTED_HISTORY` disposition.
2. Canonical correction ledger `20B/4M/0m`, source reference `24+4`.
3. B06 five detached pairs와 semantic/wrapper 분리 방향.
4. B04 issuance에 future consume/postcheck/application SHA를 넣지 않고
   downstream이 issuance를 inward-reference하는 방향.
5. M02 triple이 manifest wrapper가 아니라 manifest payload를 member로 쓰는
   correction과 U1 `DirPhysical`.
6. H2 six invocation IDs, command cardinality `[1,1,1,1,1,2]`와 raw boundary
   framing 요구.
7. H4 consume receipt에서 future dispatch time을 제거하고 후행 dispatch
   receipt로 분리하는 방향.
8. Static graph count와 runtime branch projection을 분리하는 원칙.
9. Alias logical role count와 physical write count를 분리하는 원칙.
10. Execution/official progress/checkpoint/canonical/product authority가 없다는
    금지선.

## 8. add-only R002 최소 작성 순서

R002는 target R001을 수정하지 않고 다음 순서로 작성해야 한다.

1. `AuthorityContextV2`를 predecessor-only stage variants로 분리하고
   transition/outbox hash DAG를 먼저 닫는다.
2. B06 strict schema·literal 26-edge table을 고정한다.
3. B04 issuance/consume/application detached pair와 ordinary/recovery direct
   edges를 고정한다.
4. H2 application alias를 반영한 file roles를 다시 세고 raw publication
   batch/guard 방식을 하나 선택한다.
5. M02 triple constructor member edges의 namespace를 확정한다.
6. H2 전체 literal role/node/edge table과 final edge total을 생성한다.
7. H4 scope/run plane rows, same-store schemas와 source order를 고정한다.
8. Ordered five Gate, exact 15 Gate artifacts, six targets와 seven edges를
   고정한다.
9. Closed registry schemas, branch predicate normalization, alias resolution과
   digest domains로 모든 count/digest를 다시 생성한다.
10. 같은 frozen R002 SHA에 formal/skeptical 독립검수를 수행한다.

R002가 최소 만족해야 할 추가 predicate:

```text
AuthorityContext future/self SHA = 0
transition/outbox hash cycle = 0
B04 application payload/wrapper roles = 2
B04 literal ordinary edges fully enumerated = true
H2 raw file authority coverage = 24/24
H2 guard recursion = 0
H2 final edge total = generated exact value
M02 triple member direct edges = 3
H4 scope read/control rows = exact 8/2
H4 run read/control/data rows = exact 9/11/D_i
H4 Gate IDs = ordered exact 5
H4 Gate raw/result/receipt paths = exact 15
H4 must-close edges/unique targets = exact 7/6 with identities
undefined schema/publisher/Physical/path = 0
unresolved runtime macro in static literal instance registry = 0
```

## 9. 최종 verdict와 non-authority statement

```text
review verdict = FAIL
review findings = BLOCKING 6 / MAJOR 4 / MINOR 0

target R001 accepted = false
target R001 disposition = REJECTED_HISTORY
target R001 bytes may be mutated = false
required successor = add-only R002

this review grants authority = false
R002 authoring authority = false
PRE-P execution authority = false
H2/H4 execution authority = false
checkpoint mutation authorized = false
canonical mutation authorized = false
product mutation authorized = false
Gate/formal/release credit authorized = false
official progress delta = 0
```

R002가 독립검수에서 findings `0/0/0`을 받기 전에는 target R001 또는 이 review를
근거로 실행, authority 발행, checkpoint/canonical/product 변경이나 공식
진행률 승격을 해서는 안 된다.
