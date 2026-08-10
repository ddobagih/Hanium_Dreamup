# WALKSAFE R007 correction implementation plan R001 independent authority review R001

## 0. review disposition과 non-authority boundary

```text
artifact_class = INDEPENDENT_AUTHORITY_DESIGN_REVIEW
document_status = FINAL
review_subject = WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001
review_verdict = FAIL_REJECT_R001_REQUIRE_ADD_ONLY_R002
BLOCKING = 16
MAJOR = 3
MINOR = 0
R001_accepted = false
R001_disposition = REJECTED_HISTORY
required_successor = ADD_ONLY_R002
review_authority = NONE
official_progress_delta = 0
execution_authorized = false
checkpoint_mutation_authorized = false
canonical_mutation_authorized = false
product_mutation_authorized = false
release_or_production_authorized = false
```

이 문서는 exact R001 implementation plan의 authority architecture를 읽기
전용으로 공격 검수한 독립 review다. 이 review 자체는 R002 successor,
authority grant, approval, consume receipt, closure evidence 또는 실행 지시가
아니다.

R001은 이 review 결과에 따라 수정하지 않고 `REJECTED_HISTORY`로 보존한다.
교정은 새 add-only R002에서만 수행해야 한다.

## 1. exact target과 source identity

상대 경로 기준점:

```text
docs/control/execution/artifact-closure/run-20260727-001/
```

### 1.1 exact review target

| 항목 | exact 값 |
|---|---|
| 파일 | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001.md` |
| SHA-256 | `bcf67b07c5d2a0454b6a2af325964e62d55f39e04c8c88684b2006738f027944` |
| bytes / lines | `61,353 / 2,024` |
| owner | `uid=1000 / gid=1000` |
| mode / type / nlink | `0664 / regular file / 1` |
| terminal LF / CR / NUL | `true / 0 / 0` |
| declared status | `PRE_REVIEW / NON_EXECUTABLE_CORRECTION_IMPLEMENTATION_PLAN` |

검수 시작과 보고서 작성 직전에 target SHA, bytes, lines와 물리 identity를
독립 재계산했다. 요청받은 SHA와 exact 일치했다.

### 1.2 exact source set

| Source ID | 파일 | SHA-256 | bytes | lines | disposition |
|---|---|---|---:|---:|---|
| `SRC-R006` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006.md` | `0795cff0703b85469b61f454cbfefd3d6351b94900aaa49025edca3231d35c0a` | 306496 | 6670 | `REJECTED_HISTORY` |
| `SRC-FORMAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-review-r001.md` | `ced8c9319996805ebcbb691dfa49cf8d5c6e38283c0f56254a1554952be2f374` | 20542 | 412 | `FAIL`, `6/0/0` |
| `SRC-SKEPTICAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-skeptical-review-r001.md` | `a4a3873589192fc09e0a6138aa1d76d0bb7aee1817f4655f2fabb88180332b2c` | 24902 | 465 | `FAIL`, `18/4/0` |

세 source의 SHA, bytes와 lines도 R001 §1의 선언과 exact 일치했다. 이 review의
판정 근거는 target과 위 세 source의 exact bytes로 한정했다.

## 2. 검수 범위와 판정 기준

다음 축을 독립 검수했다.

1. `AttemptAuthorityAggregateV2` lifecycle, exact-four activation과 total FSM
2. `AuthorityContextV2`의 시간 순서, self/future reference와 constructibility
3. trusted deadline CAS, consume/revoke/expire/crash 경쟁과 terminal totality
4. signed CRASH source의 schema/path/publisher/Physical/direct-edge closure
5. transactional outbox, idempotent settlement, crash recovery와 terminal tail
6. nonexecution seal, sealed root Physical과 next namespace predecessor
7. GT/EO/ET, expansion, PostG7, G3의 branch projection과 authority scope
8. H2 current-authority guard, registry/count 알고리즘과 batch dependency

BLOCKING은 R002를 target 계약대로 구성할 수 없거나 합법적인 runtime cut을
terminal exactly-one으로 닫을 수 없는 결함이다. MAJOR는 구현자마다 다른
normative 결과 또는 no-op 검증을 허용하지만 위 BLOCKING과 독립인 결함이다.

## 3. finding summary

| Finding ID | Severity | 요약 |
|---|---|---|
| `R007-PLAN-AUTH-B001` | BLOCKING | `AuthorityContextV2` activation/grant self·future cycle |
| `R007-PLAN-AUTH-B002` | BLOCKING | lifecycle key와 nonexecution context 구성 불가 |
| `R007-PLAN-AUTH-B003` | BLOCKING | exact-four activation genesis 미폐쇄 |
| `R007-PLAN-AUTH-B004` | BLOCKING | role FSM과 attempt-wide terminal fence 비총체적 |
| `R007-PLAN-AUTH-B005` | BLOCKING | revoke/expire/deadline contender와 timeout terminal branch 미정 |
| `R007-PLAN-AUTH-B006` | BLOCKING | signed CRASH source의 predicate·scope·edge 미물리화 |
| `R007-PLAN-AUTH-B007` | BLOCKING | outbox transition digest cycle와 storage identity 부재 |
| `R007-PLAN-AUTH-B008` | BLOCKING | role `SETTLED`와 outbox batch settlement state 혼용 |
| `R007-PLAN-AUTH-B009` | BLOCKING | finalization consumed-open crash/deadline cut 미폐쇄 |
| `R007-PLAN-AUTH-B010` | BLOCKING | settlement transition과 terminal-tail exact-one 충돌 |
| `R007-PLAN-AUTH-B011` | BLOCKING | nonexecution/root seal·next namespace Physical 계약 미폐쇄 |
| `R007-PLAN-AUTH-B012` | BLOCKING | GT five-cut에 required ET projection 부재 |
| `R007-PLAN-AUTH-B013` | BLOCKING | expansion이 consume을 activation으로 대체하고 EP005를 손실 |
| `R007-PLAN-AUTH-B014` | BLOCKING | cardinality/prefix registry 알고리즘 실행 불가 |
| `R007-PLAN-AUTH-B015` | BLOCKING | H2 guard가 trusted deadline과 crash-safe write를 닫지 못함 |
| `R007-PLAN-AUTH-B016` | BLOCKING | PostG7 exact-two authority가 값 수준으로 미물리화 |
| `R007-PLAN-AUTH-M001` | MAJOR | G3 fixture/self-audit가 current expansion 검증을 생략 |
| `R007-PLAN-AUTH-M002` | MAJOR | batch `Closes`가 미래 batch primitive에 의존 |
| `R007-PLAN-AUTH-M003` | MAJOR | nonexecution seal variant cardinality 명칭·수치 충돌 |

```text
finding rows = 19
finding IDs unique = 19
BLOCKING = 16
MAJOR = 3
MINOR = 0
```

## 4. BLOCKING findings

### R007-PLAN-AUTH-B001 — `AuthorityContextV2`가 self/future reference로 구성 불가능하다

Target 236~294행은 request/response/decision, 세 grant, activation, consume,
revoke, deadline, selector와 terminalization transaction/receipt가 같은 full
`AuthorityContextV2`를 직접 갖게 한다. 그런데 그 context는
`aggregate_activation_receipt_sha`와 singular `granting_artifact_*`를 필수로
가진다(278~285행).

ALLOW 순서는 세 grant 뒤 activation이다(390~396행). 따라서 request,
response와 세 grant는 future activation SHA를 요구하고 activation receipt는
자기 SHA를 요구한다. Enclosing grant를 `granting_artifact_*`로 해석하면 grant
자체도 self-preimage가 된다. Nonexecution terminalization에는 activation과
grant 자체가 없다.

Target가 B04 issuance body에서 future value를 금지한 규칙(904~911행)과도
직접 충돌한다.

Required correction:

1. self/future-free `AttemptAuthorityCommonContextV2`를 분리한다.
2. post-activation artifact에만 ordered exact-four
   `RoleAuthorityBindingV2[]`와 predecessor activation SHA/Physical을 둔다.
3. enclosing artifact SHA는 자기 body에 넣지 않는다.
4. nonexecution variant는 tagged `activation=NOT_APPLICABLE`을 사용한다.

### R007-PLAN-AUTH-B002 — lifecycle key와 nonexecution context의 생성 순서가 닫히지 않았다

Aggregate key는 `attempt_id`를 필수로 한다(162~166행). 반면 lifecycle key는
namespace commit 직후 request outcome보다 먼저 만들어야 한다(204~215행).
Target에는 별도 lifecycle-key constructor가 없고 `attempt_id` constructor도
없다. R006의 request/response strict body에는 `attempt_id`가 없으며
(R006 3784~3853행), decision에서 처음 나타난다(R006 3876행).

따라서 lifecycle key를 aggregate key로 해석하면 미래 decision 값이
필요하고, 별도 key로 해석하면 key schema와 CAS token이 정의되지 않는다.
DENY/request-expired의 activation/role row가 `0/0`인 계약(439~440행)도 B001의
full context와 양립하지 않는다.

Required correction:

1. committed namespace identity만으로 계산되는
   `AttemptLifecycleKeyV2`를 별도 정의한다.
2. 또는 `attempt_id`를 namespace member/head에 commit되는 exact constructor
   값으로 앞당기고 request/response/decision까지 byte-equal carry한다.
3. four-row invariant는 `AUTHORITY_ACTIVATED(ALLOW)`에만 한정한다.
4. preactivation/nonexecution에는 role row count `0`을 명시적으로 허용한다.

### R007-PLAN-AUTH-B003 — exact-four activation이 authoritative genesis를 완성하지 않는다

Activation row의 initial state가
`UNSPENT_UNREVOKED | NOT_NEEDED`로 열려 있다(425~437행). ALLOW activation
시점에는 future terminal branch를 알 수 없으므로 required recovery role을
처음부터 `NOT_NEEDED`로 비활성화할 수 있다.

`AuthorityAggregateActivationReceiptV2`는 이름과 역할만 있다(346~360행).
Activation transaction의 aggregate pre/post token, exact four initial role
tokens, CAS service actor/Physical/signature, receipt Physical과 durable
same-store commit body가 없다. 이는 Skeptical B003의 exact-one same-store
genesis와 signed initialization receipt 요구(SRC-SKEPTICAL 96~108행)를
충족하지 않는다.

Required correction:

1. ALLOW의 four role row initial state를 모두 `UNSPENT_UNREVOKED`로 고정한다.
2. execution row는 decision payload와 detached receipt를, 나머지 세 row는
   각 grant payload/wrapper를 필수 결속한다.
3. signed activation body에 aggregate pre/post token, four role tokens,
   service actor/Physical/signature와 receipt Physical을 넣는다.
4. 모든 후행 intent가 activation SHA+Physical과 expected token을 direct
   predecessor로 검증하게 한다.

### R007-PLAN-AUTH-B004 — enum은 닫혔지만 role FSM과 attempt-wide terminal fence가 총체적이지 않다

Target 178~200행은 state enum만 정의하고 role별 legal transition table을
정의하지 않는다. Non-wrapper pre-revoke는 “state change”라고만 하고
`PRE_REVOKED`의 정확한 edge와 settlement post-state가 없다(507~518행).

Close-recovery CAS는 execution row와 close-recovery row를 input으로 받지만
execution row만 `OUTCOME_SELECTED`로 쓴다(595~604행). Wrapper selector의
`WRAPPER_PUBLICATION_SELECTED` 등은 `RoleSlotStateV2`에 없는 값이다
(610~620행). Normal, recovery, pre-revoke, abandon, existing-wrapper adoption
각 branch에서 unused role을 `NOT_NEEDED` 또는 `SETTLED`로 닫는 atomic
transition도 없다.

그 결과 attempt seal 전에 unresolved selected role이 `0`이어야 한다는
self-audit(1903행)을 만족해도 다른 live role에서 terminal 뒤 late consume이
가능하다.

Required correction:

1. `(all-four pre-state, event, guard) → (all-four post-state, outcome, outbox)`
   완전 전이표를 정의한다.
2. attempt-level state/token을
   `OPEN → TERMINAL_SELECTED → TERMINAL_SETTLED → SEALED`로 둔다.
3. terminal winner CAS가 used role과 unused role을 한 transaction에서
   `SETTLED | NOT_NEEDED`로 닫는다.
4. 모든 consume/revoke/expire intent가 attempt state `OPEN`을 함께 검사한다.

### R007-PLAN-AUTH-B005 — revoke/expire/deadline 경쟁과 settlement timeout의 terminal branch가 없다

Target 444~456행은 normal consume/NORMAL selection, deadline outcome과 outbox
settlement의 시간 경계만 정한다. Revoke CAS의 allowed time, effective time,
deadline과의 tie order는 없다.

Wrapper pending은 `REVOKE | EXPIRE` cause만 보존하고 simultaneous contender의
immutable winner 규칙이 없다(523~532행). Non-wrapper pre-consume expiry의
disposition/schema/state edge도 없다. Deadline outcome과 settlement를 모두
`linearized_at < settlement_not_after`로 제한하므로 그 window에서 worker가
실행되지 않으면 이후 합법 terminal transition이 없다.

R006의 closed selector는
`REVOCATION < EXPIRY < CRASH < NORMAL_COMPLETION` tie order를 가진다
(R006 5138~5154행). Target의 pair/triple/quadruple fixture 이름
(1837~1840행)은 그 규칙을 aggregate CAS에 물리화하지 않는다.

Required correction:

1. 모든 contender의 source, effective time, eligibility와 exact tie order를
   aggregate CAS predicate로 고정한다.
2. non-wrapper expiry disposition과 all-role post-state를 추가한다.
3. wrapper pending에 immutable selected-cause tuple을 저장한다.
4. settlement deadline까지 미정산된 obligation을 닫는 fail-closed
   `SETTLEMENT_TIMEOUT` terminal transition을 정의한다.

### R007-PLAN-AUTH-B006 — signed CRASH source가 selector input으로 완전히 물리화되지 않았다

`ExecutionCrashObservationReceiptV2` body(474~495행)는
`detached_signature`를 자기 strict body 안에 두지만 signature preimage를
정의하지 않는다. `last_heartbeat_at`, `lost_process_effective_at`,
`observed_at`의 순서와 lost-process 계산식, current heartbeat head/token,
dispatch lease/process/watchdog equality도 없다.

`CR001 HEARTBEAT/WATCHDOG`은 composite shorthand이고 `CR002`는 selector
CAS로만 간다(497~502행). Formal B004가 요구한 close-recovery grant read-set
pre-freeze, recovery consume, ET003와 terminal body 직접 결속
(SRC-FORMAL 220~253행)이 빠졌다. Skeptical B005도 같은 exact
predicate/path/read-set/terminal binding을 요구했다
(SRC-SKEPTICAL 130~143행).

Required correction:

1. self-free unsigned body와 exact signature input을 정의한다.
2. lost-process predicate와
   `observed_at >= lost_process_effective_at > last_heartbeat_at`을 닫는다.
3. selector CAS가 current heartbeat head/token, dispatch lease, process와
   watchdog identity를 재검증하게 한다.
4. CRASH path를 close-recovery scope/read set에 pre-freeze하고 receipt
   SHA+Physical을 recovery consume, ET003, selector와 selected terminal/outbox에
   직접 넣는다.

### R007-PLAN-AUTH-B007 — outbox transition digest topology와 storage identity가 닫히지 않았다

`SettlementObligationV2`는 `aggregate_transition_sha`와 output
`content_sha`를 가진다(549~569행). 같은 transaction의
`AuthorityAggregateTransitionReceiptV2`는 outcome/outbox를 결속한다
(351~359행). Consume/selection CAS가 obligation/batch를 같은 transaction에
저장한다(580~584행).

어떤 SHA가 어떤 projection에서 먼저 계산되는지 정의하지 않아 transition
receipt와 obligation이 서로의 SHA를 포함할 수 있다. Wrapper가 selection
receipt를 inward-reference하면 output `content_sha`까지 더해져 self-preimage가
된다.

또한 named path 목록(366~380행)에 obligation, outbox batch와 settlement
record의 literal path/schema/publisher/signature/Physical이 없다.

Required correction:

1. content hash를 포함하지 않는 causal transaction ID 또는 signed transition
   preimage digest를 먼저 고정한다.
2. selection receipt → output bytes → outbox batch의 단방향 construction
   order와 각 digest domain을 정의한다.
3. obligation/batch의 literal path, closed schema, CAS publisher
   actor/Physical/signature와 same-store unique key를 명시한다.

### R007-PLAN-AUTH-B008 — role `SETTLED`와 outbox batch settlement state가 혼용된다

Role state의 유일한 settlement 상태는 `SETTLED`다(178~190행). Generic
settlement rule은 exact output reopen 뒤 “aggregate state”를 `SETTLED`로
CAS한다(580~591행).

그러나 execution consume은 `EXECUTION_DISPATCH`, finalization consume은
`FINALIZATION_WORK` obligation을 먼저 만든다(539~547, 598~608행). 이 work
output이 settle될 때 role을 `SETTLED`로 닫으면 후행 terminal selection을 할
수 없다. Role이 계속 `CONSUMED_OPEN`이라면 generic “aggregate state =
SETTLED”가 어느 state를 뜻하는지 정의되지 않는다.

Required correction:

1. `OutboxBatchStateV2 = PENDING | CLAIMED | PUBLISHED | ATTESTED | EXPIRED`를
   role state와 분리한다.
2. dispatch/work batch settlement는 role을 `CONSUMED_OPEN`으로 유지한다.
3. terminal batch attestation 완료 때만 role을 `SETTLED`로 전이한다.
4. 한 role의 ordered obligation membership과 exact terminal obligation을
   signed state에 결속한다.

### R007-PLAN-AUTH-B009 — finalization consumed-open crash/deadline cut이 다시 열린다

Finalization consume은 `FINALIZATION_WORK` obligation만 동시에 만들고, success
또는 prefix/deadline failure가 나중에 `FINALIZATION_TERMINAL` outbox를
고정한다(606~608행). Consume 직후 worker가 사라지면 terminal intent와 partial
close가 없다.

Committed obligation output은 settlement deadline 전까지만 쓸 수 있다
(588~590행). Worker가 settlement deadline까지 복구되지 않으면 finalization
terminal batch를 새로 만들 수 없고, wrapper recovery도 existing partial
close가 없어 시작할 수 없다. 이는 Skeptical B008의 exact consumed
finalization pre-close crash cut(SRC-SKEPTICAL 181~194행)을 다시 연다.

Required correction:

1. Finalization consume CAS에 resumable tail obligation 또는 pre-frozen
   one-use tail-recovery intent를 원자 저장한다.
2. `CONSUMED_OPEN + CRASH | REVOKE | EXPIRE | SETTLEMENT_TIMEOUT`의 exact
   fallback partial-close/terminal branch를 정의한다.
3. 각 fallback의 later deadline, publisher, Physical, idempotency key와
   all-role post-state를 고정한다.

### R007-PLAN-AUTH-B010 — mandatory settlement transition이 terminal-tail exact-one과 충돌한다

Terminal output reopen 뒤 aggregate state를 `SETTLED`로 CAS해야 하고
(590행), accepted transition마다
`AuthorityAggregateTransitionReceiptV2` exact 1이 필요하다(351~359행).
그 뒤 settlement attestation을 terminal seal에 embed한다(591행).

반면 functional terminal 뒤 허용되는 project control write는
`AttemptTerminalSealReceiptV2` exact 1뿐이다(720~733행). 따라서 terminal
output 뒤 settlement transition receipt를 쓰면 tail을 위반하고, 쓰지 않으면
accepted-transition receipt 계약을 위반한다. SETTLED CAS 뒤 seal 전 crash에는
attestation bytes의 durable source도 없다.

Required correction:

1. Settlement CAS가 signed attestation bytes/SHA와 reopened output Physical을
   state와 원자 저장하게 한다.
2. Terminal settlement transition의 project evidence는 별도 파일로 쓰지 않고
   final seal 안에 signed embedded receipt로 둔다.
3. Named transition cardinality와 path 규칙에 이 terminal-only embedding
   variant를 명시한다.

### R007-PLAN-AUTH-B011 — nonexecution/root seal과 next namespace의 Physical 계약이 닫히지 않았다

Target는 `AttemptTerminalSealReceiptV2` 이름과 committed-attempt cardinality만
정의한다(346~364행). P2 author action은 “four seal variants”와
zero-data-plane proof를 열거할 뿐 strict common/variant body를 주지 않는다
(1430~1456행).

R006의 기존 seal은 `AttemptRootSealReceipt`와
`ATTEMPT-TERMINAL-SEAL` alias, ordered preseal inventory, tree/file Physical,
nofollow check, sealed-root identity와 next-member reopen을 정의했다
(R006 1294~1327행). Target는 새 V2 seal과 이 alias를 결속하지 않고,
DENIED/REQUEST_EXPIRED/ABANDONED가 execution/finalization field를 어떻게
대체하는지도 정의하지 않는다.

Required correction:

1. `AttemptRootSealReceiptV2 alias ATTEMPT_TERMINAL_SEAL` 하나만 정의한다.
2. Common root/namespace/aggregate/attestation/Physical body와
   `EXECUTED | DENIED | REQUEST_EXPIRED | ABANDONED` closed variant를 정의한다.
3. Nonexecution variant가 zero-data-plane ordered set/count/digest를 직접
   증명하게 한다.
4. Next namespace member가 predecessor seal SHA+Physical과
   sealed-root identity를 inward-carry하고 nofollow reopen하게 한다.

### R007-PLAN-AUTH-B012 — GT five-cut table이 required GT/EO/ET projection을 닫지 않는다

Target의 five-cut table은 `GT016..GT030`, `GT031..GT045`와 `EO001..EO015`만
수치화한다(629~635행). Source reviews가 요구한 ET cardinality column이 없고
`ET001..ET003`은 candidate source가 있을 때만 materialize한다는 prose로
남는다(637~649행).

Wrapped adoption row의 “stored profile과 exact equality”는 stored terminal이
normal인지 recovery인지, GT31/EO가 `15/0`인지 `0/15`인지 수치화하지 않는다.
Fixture와 self-audit도 단순 `PASS`만 요구한다(1821, 1907행).

Required correction:

1. Closed stored-selector profile과 adoption normal/recovery subcut을 정의한다.
2. 각 cut의 `GT016..030`, `GT031..045`, `EO001..015`, `ET001..005`에 대해
   static/materialized/new-write numeric vector를 고정한다.
3. 각 vector의 ordered edge-set digest를 fixture와 self-audit에 넣는다.

### R007-PLAN-AUTH-B013 — expansion lineage가 mandatory consume을 우회하고 EP005를 손실한다

Expansion payload의 field가
`finalization_consume_or_activation_transaction_sha`여서 activation
transaction으로 actual finalization consume을 대체할 수 있다(653~661행).
R006의 `EP005 FINALIZATION-CONSUME → DEPENDENCY-EXPANSION-RESULT-PAYLOAD`
(R006 2341~2346행)는 Target에서
`FINALIZATION-WORK-OUTBOX → EXPANSION-PAYLOAD`로 바뀐다(663~669행).

이는 Skeptical B011이 요구한 grant/consume SHA와 expansion direct lineage
(SRC-SKEPTICAL 231~248행)를 닫지 않는다. Outbox entry가 expansion
`content_sha`를 포함한다면 expansion payload의
`finalization_work_outbox_digest`와 self-cycle도 생긴다.

Required correction:

1. Exact `finalization_consume_receipt_sha`와 consume Physical을 mandatory
   single field로 둔다.
2. `EP005 FINALIZATION-CONSUME-RECEIPT → EXPANSION-PAYLOAD`를 복원한다.
3. Work-outbox edge는 새 stable ID로 추가한다.
4. Expansion payload에는 output content hash를 제외한 causally-prior
   obligation digest만 넣는다.

### R007-PLAN-AUTH-B014 — cardinality와 prefix-count registry 알고리즘을 실행할 수 없다

Target는 `BranchCardinalityRegistryV2[]`와 `AuthorityScopeRegistryV2[]` 이름만
열거하고 row schema를 정의하지 않는다(1637~1647행). `EdgeRegistryV2` row에도
`edge_namespace`가 없다(1664~1673행).

그러나 `prefix_count(P)`는 `edge.edge_namespace == P`를 필터링한다
(1730~1736행). Branch projection의 closed `BranchEnum`, predicate grammar,
`branch_instance`와 expected cardinality vector의 차원/단위도 없다
(1691~1698, 1712~1757행).

따라서 GT/EXC/EXF/B04/B06 prefix 수와 scope/branch cardinality equality를
§13 알고리즘으로 재생성할 수 없고 stale-total detector(1789~1801행)도
통과시킬 수 없다.

Required correction:

1. `edge_namespace`와 local ordinal을 signed EdgeRegistry row에 추가하거나
   edge ID에서 namespace/ordinal을 유일하게 파싱하는 closed grammar를
   정의한다.
2. BranchCardinality/AuthorityScope row, closed cut enum, predicate grammar와
   named vector dimensions를 정의한다.
3. §11 각 row에 stable ID와 logical-role/physical-file/static-edge/
   runtime-instance 단위를 명시한다.

### R007-PLAN-AUTH-B015 — H2 guard가 trusted deadline과 crash-safe protected write를 닫지 못한다

Global CAS는 trusted `linearized_at`을 transaction 안에서 비교하고 normal
consume은 strict `< operation_not_after`여야 한다(444~459행). H2 guard는
대신 `event_at <= transition_hard_deadline`을 검사한다(1087~1097행).
Caller가 미리 만든 `event_at`을 재사용하면 실제 CAS가 deadline 뒤
linearize해도 통과할 수 있고 equality boundary도 global rule과 다르다.

또한 “각 protected file transition”에 guard receipt를 요구하면서
(1066~1069행) H2 file roles `49`에 guard `25`만 둔다(1071~1105행). Raw files를
어느 guarded atomic output batch가 소유하는지 없다. Guard 성공 뒤 one-use
lease를 반환하지만 protected write 전 crash의 idempotent resume/outbox도
정의되지 않는다.

Required correction:

1. H2 guard가 CAS service의 trusted
   `linearized_at < applicable_not_after`를 검사하게 한다.
2. 49 roles를 exact 25 guarded output batch에 one-to-one membership으로
   매핑한다.
3. Guard CAS가 batch obligation/content identity를 원자 저장하고 lease
   resume/adoption을 idempotent하게 한다.
4. Guard count, file count와 physical write count를 batch mapping에서
   재계산한다.

### R007-PLAN-AUTH-B016 — PostG7 exact-two authority가 값 수준으로 물리화되지 않았다

Target는 다음 relative paths 두 개를 제시한다(684~692행).

```text
ready/post-g7-full-projection-check.payload.json
ready/post-g7-full-projection-check.signature.json
```

그러나 exact rooted literal path, schema SHA, publisher actor/Physical과
request/response/decision/grant/consume별 ordered scope row를 실제 값으로
정의하지 않고 “있어야 한다”고만 한다(694~705행). Fixture는 success/failure
count와 allowlist substitution만 검사하고 wrong publisher/schema와
stagewise scope substitution을 검사하지 않는다(1823행). Self-audit도 count와
generic equality만 요구한다(1911~1913행).

이는 Formal B003의 exact path/schema/publisher/Physical/cardinality와
stagewise scope equality 요구(SRC-FORMAL 188~218행), Skeptical B012
(SRC-SKEPTICAL 250~261행)를 완전히 닫지 않는다.

Required correction:

1. `<ATTEMPT_ROOT>`까지 펼친 two literal RoleRegistry rows를 고정한다.
2. Exact schema SHA, publisher actor/Physical, branch predicate와 cardinality를
   넣는다.
3. Request/response/decision/grant/consume scope의 ordered set/count/digest를
   단계별로 명시하고 byte-equal 검증한다.
4. missing pair, wrong publisher/schema, allowlist-only와 scope substitution
   negative fixture를 각각 추가한다.

## 5. MAJOR findings

### R007-PLAN-AUTH-M001 — G3 fixture와 self-audit가 current expansion 검증을 생략한다

G3 prose는 current expansion의 subset, cardinality, order와 digest를 모두
재계산해야 한다(716~718행). 그러나 fixture는 future reference `0`과 full
equality가 PostG7에만 있다는 사실만 검사한다(1833행). Self-audit도 두 profile
이름과 future/full-equality claim `0`만 본다(1915~1917행).

따라서 실제 current expansion 검증을 수행하지 않아도 fixture와 self-audit가
PASS할 수 있다.

Required correction:

1. Manifest, expansion payload/wrapper와 selected runtime branch의 exact direct
   binding을 fixture에 추가한다.
2. subset/cardinality/order/digest 네 재계산을 별도 machine predicate로
   추가한다.

### R007-PLAN-AUTH-M002 — batch `Closes`가 아직 정의되지 않은 future batch primitive에 의존한다

P2는 B003과 B008을 닫는다고 하지만(1430~1460행), actual four-grant-bound
activation instance는 P3에서 완성하고 outbox/settlement primitive는 P4에서
처음 정의한다(1462~1515행). P2의 four seal variants 중 EXECUTED seal도 P4
terminal-tail 없이는 검증할 수 없다.

각 batch마다 해당 fixture와 registry recalculation을 먼저 통과하라는 author
instruction(1987~1989행)과 양립하지 않는다.

Required correction:

1. Primitive/schema 준비와 finding `Closes`를 분리한다.
2. B003 close/fixture는 P3 이후, B008의 durable disposition settlement와
   executed seal fixture는 P4 이후로 미룬다.
3. 각 batch exit predicate가 그 시점에 존재하는 registry만 참조하게 한다.

### R007-PLAN-AUTH-M003 — nonexecution seal variant cardinality의 명칭과 수치가 충돌한다

Lifecycle terminal union은
`EXECUTED | DENIED | REQUEST_EXPIRED | ABANDONED`의 four variants다
(204~232행과 P2 author action 1454행). Nonexecution variant는 그중
`DENIED | REQUEST_EXPIRED | ABANDONED`의 exact three다.

그러나 self-audit는 `nonexecution seal variants = exact 4`라고 요구한다
(1877~1880행). 이를 그대로 구현하면 fourth nonexecution variant를
추가하거나 EXECUTED를 nonexecution으로 잘못 분류할 수 있다.

Required correction:

```text
terminal seal variants = exact 4
nonexecution seal variants = exact 3
executed seal variants = exact 1
```

위 세 predicate를 분리하고 같은 closed discriminant registry에서 계산한다.

## 6. 확인된 방향과 claim ceiling

다음 방향은 source finding을 교정하려는 의도와 일치한다.

- independent role keys를 단일 attempt aggregate로 통합하려는 방향
- normal selection의 trusted CAS deadline boundary
- wrapper pre-close revoke/expiry pending state
- transactional outbox와 exact-output adoption
- GT016..030과 GT031..045 분리
- expansion-wrapper에서 15 completion과 conditional checkpoint로 가는 edge
- G3 current-expansion과 PostG7 full-equality profile 분리
- terminal data/finalization output 뒤 root seal을 final control write로 두는 방향

그러나 위 방향성은 16 BLOCKING과 3 MAJOR를 상쇄하지 않는다. 현재 R001은
constructible authority FSM, race-total settlement 또는 mechanically
recalculable branch graph를 제공하지 못한다.

## 7. add-only R002 required correction gate

R002는 최소 다음을 모두 만족해야 한다.

```text
R001 exact bytes mutated = 0
R001 disposition = REJECTED_HISTORY

AuthorityContext self/future reference = 0
lifecycle key constructible before request outcome = true
ALLOW activation role rows = exact four UNSPENT_UNREVOKED
activation receipt signed same-store genesis = exact 1
all-four role terminal states = SETTLED | NOT_NEEDED
attempt-wide late consume/revoke/expire after selection = 0

trusted CAS deadline used by every transition = true
non-wrapper expiry terminal branches = complete
settlement-timeout unresolved branch = 0
CRASH signed source strict predicate/scope/direct edges = complete

outbox/transition digest cycle = 0
outbox batch state separate from role state = true
finalization consumed-open crash cut terminal = exact 1
terminal output 뒤 separate project transition write = 0
seal embeds signed settlement attestation = true

nonexecution seal variants = exact 3
terminal seal union variants = exact 4
next namespace seal SHA/Physical/root recheck = exact

GT/EO/ET five-cut numeric vectors and digests = complete
expansion mandatory finalization consume direct edge = exact 1
PostG7 literal role/scope rows = exact 2
G3 subset/cardinality/order/digest predicates = exact 4

prefix/cardinality/scope registry executable = true
H2 guard uses trusted linearized_at = true
H2 49-role/25-batch mapping and crash resume = complete

R002 formal review target SHA = R002 frozen SHA
R002 skeptical review target SHA = R002 frozen SHA
R002 authority review target SHA = R002 frozen SHA
all independent review findings = 0/0/0
```

R002도 exact bytes를 freeze하기 전에는 review target SHA를 예약하면 안 된다.
어느 independent review든 nonzero이면 R002 역시 수정하지 않고
`REJECTED_HISTORY`로 보존하며 다음 add-only revision에서 교정해야 한다.

## 8. 최종 verdict와 non-authority statement

```text
BLOCKING = 16
MAJOR = 3
MINOR = 0
verdict = FAIL_REJECT_R001_REQUIRE_ADD_ONLY_R002
R001_ACCEPTED = false
R001_REJECTED_HISTORY = true
R002_REQUIRED = true
R002_EXISTS_BY_THIS_REVIEW = false
R002_AUTHORIZED_BY_THIS_REVIEW = false
PRE_P_OR_SUCCESSOR_EXECUTION_AUTHORIZED = false
REQUEST_GRANT_CONSUME_CAS_RECEIPT_AUTHORIZED = false
H2_H4_STAGE_C_EXECUTION_AUTHORIZED = false
CHECKPOINT_OR_CANONICAL_MUTATION_AUTHORIZED = false
PRODUCT_OR_TEST_RUNTIME_MUTATION_AUTHORIZED = false
PRODUCTION_OR_RELEASE_AUTHORIZED = false
OFFICIAL_PROGRESS_DELTA = 0
```

이 review는 target R001, R006, 두 source review, checkpoint, canonical root,
product와 기존 receipt를 수정하지 않는다. 새 add-only R002의 작성과 같은
frozen SHA에 대한 findings-zero independent review 전에는 어떤 authority나
실행 가능 상태도 성립하지 않는다.
