# WALKSAFE R007 correction implementation plan R001 independent ledger review R001

## 0. 문서 지위와 판정

```text
artifact_class = READ_ONLY_INDEPENDENT_LEDGER_REVIEW
review_subject_type = NON_EXECUTABLE_CORRECTION_IMPLEMENTATION_PLAN
review_status = COMPLETE
verdict = FAIL_REJECTED_REQUIRES_ADD_ONLY_R002
BLOCKING = 12
MAJOR = 8
MINOR = 0
review_authority = NONE
execution_authorized = false
checkpoint_mutation_authorized = false
canonical_mutation_authorized = false
product_mutation_authorized = false
release_or_production_authorized = false
official_progress_delta = 0
```

이 문서는 아래 exact R001 구현계획을 읽기 전용으로 검수한 독립 보고서다.
R001은 `REJECTED_HISTORY`로 add-only 보존해야 하며, 교정은 기존 bytes를
수정하지 않는 새 R002 successor에서 수행해야 한다.

이 판정은 R002 작성·실행, PRE-P, G0/P0, V1, H2~H5, Stage-C, checkpoint,
canonical, product, production 또는 release 권한이 아니다.

## 1. exact target과 source identity

검수 대상:

| 항목 | exact 값 |
|---|---|
| target | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001.md` |
| SHA-256 | `bcf67b07c5d2a0454b6a2af325964e62d55f39e04c8c88684b2006738f027944` |
| bytes / lines | `61,353 / 2,024` |
| type / nlink | `regular non-symlink / 1` |
| mode / owner | `0664 / uid=1000, gid=1000` |
| terminal LF / CR / NUL | `true / 0 / 0` |

상대 경로 기준점:

```text
docs/control/execution/artifact-closure/run-20260727-001/
```

R001 §1이 선언한 유일한 source set도 실제 bytes와 일치했다.

| Source ID | 파일 | SHA-256 | bytes / lines |
|---|---|---|---:|
| `SRC-R006` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006.md` | `0795cff0703b85469b61f454cbfefd3d6351b94900aaa49025edca3231d35c0a` | `306,496 / 6,670` |
| `SRC-FORMAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-review-r001.md` | `ced8c9319996805ebcbb691dfa49cf8d5c6e38283c0f56254a1554952be2f374` | `20,542 / 412` |
| `SRC-SKEPTICAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-skeptical-review-r001.md` | `a4a3873589192fc09e0a6138aa1d76d0bb7aee1817f4655f2fabb88180332b2c` | `24,902 / 465` |

아래 line reference 약어는 다음 exact file을 뜻한다.

```text
P = WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001.md
F = WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-review-r001.md
S = WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-skeptical-review-r001.md
R6 = WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006.md
```

## 2. 검수 방법

다음 순서로 독립 재검산했다.

1. 대상과 세 source의 SHA-256, bytes, lines, file type와 LF identity를 먼저
   확인했다.
2. Formal `6 BLOCKING`과 skeptical `18 BLOCKING / 4 MAJOR` heading을 exact
   source ID로 추출했다.
3. 각 heading의 제목뿐 아니라 required-correction 하위 조건을 canonical
   ledger, 설계 본문, P0~P8 `Closes`, §18 fixture와 §22 predicate까지
   순방향·역방향으로 추적했다.
4. 네 overlap cluster가 같은 root cause인지와 더 넓은 cluster가 좁은
   finding의 하위 조건을 보존하는지 검사했다.
5. P0~P8 dependency DAG, batch closure 시점, 24 fixture one-to-one,
   source-reference multiplicity와 count arithmetic을 독립 계산했다.
6. 고정 수치는 exact source 또는 이 계획 안의 literal registry/derivation으로
   재생성 가능한지 검사했다.

검수 중 대상, R006, 두 source review, checkpoint, canonical, product,
daylog와 memory는 수정하지 않았다.

## 3. BLOCKING findings

### R001-LEDGER-REVIEW-BLOCKING-001 — AuthorityContextV2가 미래 참조와 자기 해시를 강제한다

근거:

- `P:236-294`는 request/response/decision, 세 grant, activation과 후행
  transaction/receipt 모두에 full `AuthorityContextV2`를 요구한다.
- 같은 객체는 `aggregate_activation_receipt_sha`와
  `granting_artifact_payload_sha`/wrapper를 필수 field로 가진다
  (`P:278-285`).
- 실제 순서는 세 grant 뒤 activation이다(`P:387-397`). 따라서 pre-activation
  artifact에는 future activation SHA가 필요하고 activation receipt 자신에는
  자기 SHA가 필요하다.
- DENIED/REQUEST_EXPIRED branch의 activation cardinality는 `0`이다
  (`P:439-440`).
- 원 source는 genesis token/Physical을 후행 intent와 CAS에 결속하라고
  요구한다(`S:96-108`). Predecessor가 자기나 미래 artifact를 참조하라는
  요구가 아니다.

영향:

```text
pre-activation request/grant constructible = false
activation receipt constructible = false
non-ALLOW seal context constructible = false
```

최소 교정:

1. stable identity/plane만 가진 `PreActivationContextV2`와
   `ActivatedAuthorityContextV2`를 분리한다.
2. activation receipt는 prior decision/grant SHA와 initial tokens를 가지되
   자기 receipt SHA를 body에 넣지 않는다.
3. activation receipt SHA/Physical/token은 후행 consume/revoke/deadline/
   terminalization부터 직접 결속한다.
4. signed publisher/Physical과 missing/wrong Physical/token negative fixture를
   추가한다.

### R001-LEDGER-REVIEW-BLOCKING-002 — Formal B001의 execution consume binding이 완성되지 않았다

근거:

- Source는 finalization grant publication/freeze time, attempt/nonce/scope,
  concrete allowlist/plane binding을 execution consume intent/receipt에 named
  field로 넣고 invalid grant의 consume/dispatch/lease를 모두 `0`으로
  요구한다(`F:145-154`).
- R001은 direct edge와 generic granting SHA/scope를 두지만(`P:399-437`),
  `AuthorityContextV2`에 grant publication/freeze time과 grant-specific
  nonce가 없다(`P:236-294`).
- Fixture는 missing/late/wrong-scope만 열거하고 wrong-attempt/wrong-wrapper와
  lease `0`을 검사하지 않는다(`P:1814`).
- §22도 direct edge와 consume/dispatch만 검사한다(`P:1887-1889`).

최소 교정:

1. execution consume intent/receipt에 finalization grant의
   `published_at`, `frozen_at`, attempt/nonce와 exact scope fields를 추가한다.
2. wrong attempt/nonce/wrapper와 consume/dispatch/lease `0/0/0` fixture 및
   §22 predicate를 추가한다.

### R001-LEDGER-REVIEW-BLOCKING-003 — Formal B002의 wrapper-grant consume bytes가 완성되지 않았다

근거:

- Source는 wrapper grant frozen/published time, write-subset digest, initial
  revocation head와 namespace/allowlist/plane equality를 finalization consume에
  직접 요구한다(`F:176-186`).
- R001은 direct edge를 복원했지만(`P:405-406`), 공통 context와 activation
  role row에 frozen/published time, named write-subset digest와 initial
  revocation head가 없다(`P:241-294,425-437`).
- `initial_role_token`이 revocation head를 대체한다면 그 byte-level
  equivalence와 migration rule이 없다.
- Fixture에는 missing-edge cut이 없고 downstream-write-zero 검사가
  축약돼 있다(`P:1815,1888-1889`).

최소 교정:

1. 누락 field를 finalization consume intent/receipt에 직접 추가하거나
   aggregate 대체 계약을 exact equality 식으로 명시한다.
2. missing edge, late grant, wrong subset/head와 finalization consume/downstream
   write `0/0` fixture를 추가한다.

### R001-LEDGER-REVIEW-BLOCKING-004 — AttemptTerminalSeal의 crash-safe publication 경로가 없다

근거:

- Namespace retry는 terminal seal을 필수 predecessor로 사용한다
  (`P:204-226`).
- R001은 seal artifact와 terminal final-write를 요구한다
  (`P:346-364,720-733`).
- 그러나 ordered exact-eight outbox slot에는 nonexecution 또는 attempt seal
  slot이 없다(`P:534-547`).
- Settlement rule도 accepted consume/terminal selection의 functional output만
  다룬다(`P:580-593`).
- Branch table은 DENIED/REQUEST_EXPIRED/ABANDONED 모두 seal `1`을 요구한다
  (`P:1338-1345`).
- 원 finding은 nonexecution attempt에도 실제 seal과 next-freeze edge가 있어야
  namespace deadlock이 해소된다는 조건이다(`S:81-94`).

`selection commit → seal file publication 전 crash` cut에는 durable output
identity나 idempotent settlement가 없어 같은 namespace deadlock이 재발한다.

최소 교정:

1. `ATTEMPT_TERMINAL_SEAL` 전용 obligation/outbox slot을 추가한다.
2. nonexecution selection과 seal content identity를 같은 transaction에
   영속화한다.
3. exact bytes/Physical adopt와 retry-safe settlement 뒤에만 next namespace
   member를 허용한다.

### R001-LEDGER-REVIEW-BLOCKING-005 — CRASH source가 grant에서 terminal body까지 결속되지 않았다

근거:

- Source는 CRASH strict artifact뿐 아니라 close-recovery grant read-set/scope,
  recovery consume, ET003, selector와 terminal body의 direct binding을
  요구한다(`F:220-253`, `S:130-143`).
- R001은 receipt body와 두 edge를 정의한다(`P:474-505`).
- 그러나 exact heartbeat-loss predicate, concrete allowlist/close-recovery
  read-set row, recovery consume/ET003/terminal-body source SHA binding은 없다.
- §22는 selected source cardinality만 검사한다(`P:1891-1894`).

최소 교정:

1. heartbeat-loss closed predicate와 CRASH literal role row를 정의한다.
2. grant read-set/scope → recovery consume → ET003/selector → terminal body에
   source SHA/Physical을 직접 결속한다.
3. 각 edge와 equality를 fixture 및 §22에 추가한다.

### R001-LEDGER-REVIEW-BLOCKING-006 — PRE_CONSUME revoke와 execution selector 충돌이 남아 있다

근거:

- Source는 execution pre-revoke를 nonexecution outcome으로 분리하고
  REVOCATION selector를 post-consume source로 제한하라고 요구한다
  (`S:145-163`).
- R001은 execution pre-revoke를 `ABANDONED`로 분리하고 세 disposition을
  추가했다(`P:228-232,507-518`).
- 그러나 REVOCATION candidate의 closed eligibility predicate와
  transition→outbox→disposition→seal literal direct edges가 없다.
- Fixture/§22도 role별 selector exclusion과 seal binding을 검사하지 않는다
  (`P:1817,1896`).

최소 교정:

1. `REVOCATION candidate eligible iff original execution consume exists`를
   selector strict predicate로 둔다.
2. execution pre-revoke의 `NOT_RUN/ABANDONED` terminal proof와 세 role별
   disposition/terminal edges를 literal registry에 추가한다.

### R001-LEDGER-REVIEW-BLOCKING-007 — Wrapper final selection proof bytes가 불완전하다

근거:

- Formal source는 selected record가 wrapper path/schema/publisher,
  payload/close Physical, final revoke receipt SHA-or-`NA`와 publication
  lease를 동결하고 wrapper가 그 selection을 inward-reference하게 요구한다
  (`F:285-296`).
- Skeptical source는 durable output identity, late revoke `effect=NONE`과
  later settlement deadline을 모두 요구한다(`S:196-217`).
- R001 outbox는 output path/schema/publisher/content를 가지지만 input
  payload/close Physical과 final revoke SHA-or-`NA`가 없다
  (`P:549-572`).
- Wrapper CAS에는 selection proof의 wrapper inward binding이 없다
  (`P:610-620`).

최소 교정:

1. non-self-referential selection payload/receipt를 정의하고 payload/close
   Physical, final revoke SHA-or-`NA`와 publication authorization을 동결한다.
2. wrapper가 stable selection identity를 inward-reference하게 한다.
3. revoke-wins, wrapper-wins와 simultaneous race를 별도 exactly-one fixture로
   둔다.

### R001-LEDGER-REVIEW-BLOCKING-008 — Expansion이 finalization consume을 우회할 수 있다

근거:

- Source는 expansion payload가 finalization grant/consume SHA와 prefix
  lineage를 직접 결속하도록 요구한다(`S:231-248`).
- R001은
  `finalization_consume_or_activation_transaction_sha`를 허용한다
  (`P:653-661`).
- Activation은 consume 이전 artifact이므로 activation-only expansion이
  finalization consume 권한을 증명하지 못한다.
- Fixture와 §22는 expansion→completion/checkpoint edge 수만 검사한다
  (`P:1822,1908-1909`).

최소 교정:

1. field를 required
   `finalization_consume_transition_receipt_sha`와 exact Physical로 바꾼다.
2. consume→work-outbox→expansion의 literal direct edges를 둔다.
3. activation-only, wrong grant/consume와 wrong prefix를 거부한다.

### R001-LEDGER-REVIEW-BLOCKING-009 — B04 application strict schema와 51/68 edge 계약이 재현되지 않는다

근거:

- Source는 exact6 result array, target/runtime binding, finalizing executor,
  terminal claim, full signing input과 모든 N26/T1/X1/V1 direct edge를
  요구한다(`S:263-281`).
- R001 application body는 불투명한 `protected application fields`를 사용하고
  exact6 result array와 target/runtime binding을 field-by-field 열거하지
  않는다(`P:939-951`).
- `application original inbound = 48`, issuance `14`, tail `2`는 literal edge
  rows나 exact source 없이 선언된다(`P:962-974`).
- 그 값은 fixed normative count로 반복된다(`P:1763-1768,1824,1920-1922`).
- 이는 `prose numeric claim without registry source = 0`이라는 자체 규칙과
  충돌한다(`P:1789-1800`).

최소 교정:

1. application closed field list와 signature/publisher/Physical을 완전히
   전개한다.
2. original 48 inbound, LIVE_ROOT_TRIPLE 3, issuance 14와 terminal tail 2의
   stable edge rows를 제공한다.
3. 제공할 수 없다면 `51/68` hardcode를 제거하고 generated registry 값으로
   바꾼다.

### R001-LEDGER-REVIEW-BLOCKING-010 — H2 schema가 미완성이고 deadline 계약이 충돌한다

근거:

- Source는 six invocation/result/evidence member의 framing과 각 transition의
  current head, consume Physical, unrevoked state와 hard deadline 재검증을
  요구한다(`S:301-318`).
- R001은 file 목록과 framing은 주지만 invocation별 input/intent/result/
  evidence closed field schema, ordered assertion registry와
  publisher/signature를 정의하지 않는다(`P:1017-1064`).
- 공통 authority CAS는 trusted transaction
  `linearized_at < operation_not_after`를 요구한다(`P:442-459`).
- H2 guard는 대신 외부 `event_at <= transition_hard_deadline`을 허용한다
  (`P:1087-1097`).

따라서 equality boundary와 pre-check→commit tick advance에서 두 normative
규칙이 서로 다른 결과를 낸다.

최소 교정:

1. six invocation별 closed schema/assertion/evidence registry를 작성한다.
2. H2 authority guard도 trusted CAS
   `linearized_at < operation_not_after`로 통일한다.
3. current token/head/consume Physical/unrevoked/deadline과 raw assertion
   recomputation을 fixture·§22에서 각각 검사한다.

### R001-LEDGER-REVIEW-BLOCKING-011 — H4 scope/run authority chain이 strict contract가 아니다

근거:

- Source는 signed scope-authority predecessor와 run grant/consume/result/close의
  strict closed schema, atomic store와 non-future dispatch time을 요구한다
  (`S:320-338`).
- R001은 두 scope path와 eleven run role path를 제시한다
  (`P:1181-1216`).
- 그러나 scope receipt의 exact field/publisher/signature와 run grant/init/
  consume/dispatch/result/close의 exact fields, signature input와 CAS
  transaction을 완전히 열거하지 않는다(`P:1181-1237`).
- Fixture와 §22는 scope count, C=11, state order와 time separation만
  검사한다(`P:1827,1932-1933`).

최소 교정:

1. 두 scope artifact와 eleven run artifact의 closed schemas를 작성한다.
2. wrong publisher/signature/token/time/result discriminant와 missing direct
   predecessor를 fixture·§22에 추가한다.

### R001-LEDGER-REVIEW-BLOCKING-012 — H4 exact-five가 여전히 count-only다

근거:

- Source는 ordered exact-five Gate result/receipt array와 모든 downstream
  target의 literal artifact/path edge를 요구한다(`S:340-354`).
- R006에는 five Gate ID가 이미 존재한다(`R6:5964-5970`).
- R001은 “Gate ID를 ordered exact five로 freeze”한다고만 하고 ID를 열거하지
  않는다(`P:1250-1267`).
- Must-close도 `7 edges / 6 targets` count만 주며 여섯 literal target role/path와
  일곱 edge tuple을 제시하지 않는다(`P:1289-1297`).
- 그 상태에서 P7이 B019를 closed 처리한다(`P:1595-1604`).

최소 교정:

1. ordered five Gate ID를 exact row로 inward-carry한다.
2. 여섯 literal target role/path와 일곱
   `(source,target,branch_predicate)` edge를 stable ID로 전개한다.
3. count-only completion을 통과시키지 않는 fixture와 §22 digest equality를
   추가한다.

## 4. MAJOR findings

### R001-LEDGER-REVIEW-MAJOR-001 — PostG7 negative fixture와 §22가 source보다 좁다

Core exact-two scope와 same-SHA predecessor 설계는 존재한다(`P:684-705`).
그러나 source가 요구한 wrong publisher/schema, scope substitution와
`GE039..GE041`/finalization-close payload·wrapper SHA equality negative cut이
fixture와 §22에 없다(`F:209-218`; `P:1823,1911-1913`).

최소 교정: 네 negative cut과 GE/close exact SHA predicate를 추가한다.

### R001-LEDGER-REVIEW-MAJOR-002 — GT fixture가 ET 전체 cardinality vector를 검사하지 않는다

GT/EO 핵심 분리는 교정됐다(`P:624-649`). 그러나 source는 five cut마다
`GT016..GT045/EO/ET` 전체 cardinality를 요구한다(`F:318-328`,
`S:219-230`). Fixture는 GT/EO와 selected ET `1/1`만 쓰고 §22는
`GT/EO five-cut truth table = PASS`만 둔다(`P:1821,1907`).

최소 교정: five-cut table, fixture expected vector와 §22에 ET001..ET005
materialization을 명시한다.

### R001-LEDGER-REVIEW-MAJOR-003 — Wrapper pre-close guard의 두 Physical 조건이 축약됐다

Source는 existing payload와 close Physical 및 wrapper cardinality `0`을
revoke CAS의 direct precondition으로 요구한다(`S:165-179`). R001은
“exact Physical equality”라고만 쓰고 두 Physical을 별도 field로 결속하지
않으며 fixture/§22도 premature wrapper를 검사하지 않는다
(`P:520-532,1818,1897-1898`).

최소 교정: `existing_payload_physical`, `existing_close_physical`과
`pre-close wrapper cardinality = 0`을 strict CAS/fixture/predicate에 추가한다.

### R001-LEDGER-REVIEW-MAJOR-004 — Attempt namespace constructor object 자체가 미정의다

Source는 exact field/type/NA encoding의 domain-tagged object를 요구한다
(`S:384-392`). R001은 domain과 scalar encoding rule만 주고
`exact_constructor_object`의 key set, type와 nullability를 열거하지 않는다
(`P:308-330`). P1의 “golden bytes 정의” 지시와 fixture는 oracle이 될
closed object가 없다(`P:1425,1831,1874-1875`).

최소 교정: constructor의 모든 key/type/requiredness를 열거하고 canonical
byte vector와 SHA golden fixture를 고정한다.

### R001-LEDGER-REVIEW-MAJOR-005 — Batch closure 선언이 required output보다 빠르다

다음 batch는 자체 fixture를 통과하기 전에 finding을 closed 처리한다.

| Finding | 조기 closure | 실제 필요한 후행 output |
|---|---|---|
| `R007-M001` | P1 `P:1421` | P2~P7에서 새로 생기는 모든 strict schema와 P8 global check |
| `R007-B003` | P2 `P:1446` | grant-bound activation instance/fan-out을 P3로 유예 `P:1458-1460,1482-1485` |
| `R007-B008` | P2 `P:1446` | durable disposition outbox/settlement P4 `P:1489-1514` |
| `R007-B009` | P3 `P:1478` | close 뒤 exact outcome/outbox fixture가 요구하는 P4 output `P:1489-1514,1818` |

이는 각 batch마다 해당 fixture를 통과하라는 instruction과 충돌한다
(`P:1987-1990`).

최소 교정: closure를 실제 마지막 dependency batch로 이동하거나 필요한
output을 앞 batch로 이동한다.

### R001-LEDGER-REVIEW-MAJOR-006 — §22가 canonical/fixture stable ID와 결속되지 않았다

R001은 ledger, fixture와 §22를 같은 stable ID로 결속하라고 요구한다
(`P:98,108-109`). §18 fixture에는 24 canonical ID가 있으나
(`P:1803-1833`), §22 block에는 `R007-Bxxx/Mxxx`, fixture ID 또는 predicate
ID가 없다(`P:1857-1948`).

`source finding unmapped = 0`만으로는 다음을 검증할 수 없다.

```text
formal BLOCKING refs = exact 6 once each
skeptical BLOCKING refs = exact 18 once each
skeptical MAJOR refs = exact 4 once each
merged overlap clusters = exact 4
fixture expected/actual digest equality = 24/24
```

최소 교정: `canonical_id → source_ids[] → fixture_id → predicate_ids[]`
coverage registry와 exact multiplicity/digest predicate를 §22에 추가한다.

### R001-LEDGER-REVIEW-MAJOR-007 — H2 262→306 intermediate seed에 source provenance가 없다

R001은 `base expanded H2 edges = 262`, minimal application inbound `7`과
intermediate `306`을 선언한다(`P:1113-1118`). 세 exact source 어디에도
`262` seed나 262-row registry가 없으며 R001에도 literal derivation이 없다.
그런데 stale-number 검사의 관리 대상과 acceptance criterion으로 반복된다
(`P:1787,1924-1926,1965-1966`).

산술 `262 - 7 + 51 = 306` 자체는 맞지만 source provenance가 없어 재현할 수
없다.

최소 교정: intermediate를 삭제하고 final generated registry만 요구하거나
262-row seed registry와 digest를 제공한다.

### R001-LEDGER-REVIEW-MAJOR-008 — §22의 nonexecution seal cardinality 명칭과 값이 모순된다

Source의 terminal union은
`EXECUTED | DENIED | REQUEST_EXPIRED | ABANDONED` exact four다
(`S:81-94`). R001 lifecycle도 같은 구조다(`P:208-215`). 따라서
nonexecution variant는 세 개지만 §22는
`nonexecution seal variants = exact 4`라고 요구한다(`P:1877-1880`).

최소 교정: predicate를 `terminal seal variants = exact 4`로 바꾸고
nonexecution count는 exact `3`으로 별도 검사한다.

## 5. Canonical mapping과 overlap 판정

Heading-level crosswalk는 완전하다.

```text
formal BLOCKING headings referenced = 6/6, each exact once
skeptical BLOCKING headings referenced = 18/18, each exact once
skeptical MAJOR headings referenced = 4/4, each exact once
source BLOCKING refs before dedupe = 24
source MAJOR refs = 4
canonical BLOCKING IDs = 20 unique
canonical MAJOR IDs = 4 unique
canonical MINOR IDs = 0
```

산술도 맞다.

```text
6 formal B + 18 skeptical B - 4 overlaps = 20 canonical B
20 canonical B + 4 canonical M = 24 canonical findings
24 source B refs + 4 source M refs = 28 source refs before dedupe
```

네 overlap은 root-cause taxonomy 수준에서는 타당하다.

| Formal source | Skeptical source | Canonical ID | taxonomy |
|---|---|---|---|
| `FORMAL-B003` | `SK-B012` | `R007-B014` | PostG7 exact-two finalization authority |
| `FORMAL-B004` | `SK-B005` | `R007-B007` | signed CRASH source |
| `FORMAL-B005` | `SK-B009` | `R007-B011` | wrapper output/revoke/expiry totality |
| `FORMAL-B006` | `SK-B010` | `R007-B012` | 15-result CAS/GT contradiction |

그러나 taxonomy가 같다는 사실은 implementation/fixture/§22 하위 조건이
자동 보존됐다는 뜻이 아니다. B005, B007과 M001/M002 때문에 네 cluster 모두
end-to-end findings-zero closure에는 도달하지 못했다.

## 6. 통과한 구조·산술 검사

다음 항목은 PASS했다.

1. Target과 세 source의 exact identity.
2. Canonical ledger `20B/4M/0m`, 24 unique ID.
3. Source heading 28 references의 누락·중복 `0`.
4. P0~P8 declared dependency graph cycle `0`.
5. P1~P7 `Closes` 집합이 canonical 24 ID를 각각 exact once 포함.
6. §18 fixture table이 24 rows/24 unique fixture ID이고 canonical 24 ID와
   형식상 one-to-one.
7. 다음 표시 산술:

```text
B06 edges = 5 + 1 + 4 + 8 + 6 + 2 = 26
B04 H1 edges = 3 + 3 + 1 + 10 + 4 + 1 = 22
H2 roles = 2 + 10 + 1 + 36 = 49
H2 guards = 2 + 1 + 2 + 1 + 1 + 1 + 12 + 1 + 1 + 1 + 1 + 1 = 25
H2 displayed writes = 49 + 25 = 74
M02 local edges = 23 + 1 = 24
H4 must-close edges = 1 + 1 + 1 + 2 + 2 = 7
```

이 PASS는 B04 actual `48/51/68`이나 H2 seed `262/306`의 source provenance를
증명하지 않는다. 해당 문제는 B009와 M007에 별도 기록했다.

## 7. 최종 disposition과 R002 최소 gate

```text
review verdict = FAIL
R001 correction implementation plan accepted = false
R001 disposition = REJECTED_HISTORY
required correction vehicle = ADD_ONLY_R002
R001 bytes may be edited/replaced/deleted = false
R002 execution authority granted by this review = false
official progress delta = 0
```

R002는 최소 다음을 모두 만족해야 한다.

1. 이 review의 `12 BLOCKING / 8 MAJOR / 0 MINOR`를 stable correction
   crosswalk로 inward-carry한다.
2. B001의 stage-split context부터 먼저 교정해 self/future SHA를 제거한다.
3. Grant/CRASH/revoke/wrapper/expansion/seal lineage를 closed schema,
   literal edge와 crash-safe settlement로 닫는다.
4. B04/H2/H4의 placeholder와 unsupported count를 literal registry와
   generated digest로 대체한다.
5. Batch closure를 실제 마지막 dependency와 일치시킨다.
6. §18 fixture와 §22 predicate를 canonical stable ID로 직접 결속한다.
7. Exact R002 bytes를 freeze한 뒤 같은 SHA에 대한 독립 formal/skeptical 및
   ledger review에서 각각 findings `0/0/0`을 받아야 한다.

어느 review든 nonzero이면 R002도 수정하지 않고 `REJECTED_HISTORY`로
add-only 보존하며 다음 successor에서 교정해야 한다.

## 8. 최종 non-authority statement

```text
this review grants authority = false
R001 remains non-executable rejected history = true
R001/source bytes may be mutated = false
R002 is required but not authorized for execution by this review = true
checkpoint/canonical/product mutation authorized = false
production/release authorized = false
official progress delta = 0
```
