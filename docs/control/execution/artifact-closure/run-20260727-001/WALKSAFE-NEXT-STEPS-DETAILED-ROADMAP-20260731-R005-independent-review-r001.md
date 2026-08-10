# WalkSafe 다음 단계 상세 로드맵 20260731 R005 독립검수 R001

- 문서 ID:
  `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R005-INDEPENDENT-REVIEW-R001`
- 검토일:
  `2026-07-31`
- review subject type:
  `ROADMAP_REVIEW`
- review authority:
  `NONE / ABSENT_DENY_ALL`
- verdict:
  `FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR_R006`
- findings:
  `BLOCKING=3 / MAJOR=2 / MINOR=0`

## 1. exact target과 물리 identity

| 항목 | exact 값 |
|---|---|
| target | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R005.md` |
| SHA-256 | `7ab82008326b3770a1a647d6f05d5f5c2e02533ea0a24b4260aec86d2735a844` |
| bytes / lines | `212,430 / 4,905` |
| owner | `uid=1000 / gid=1000` |
| mode / type / nlink | `0664 / regular non-symlink / 1` |
| terminal LF / CR / NUL | `true / 0 / 0` |

검수 시작과 보고서 작성 직전에 위 identity를 독립 재계산했다. target은
요청받은 exact SHA와 일치했고 이 review는 target을 수정하지 않았다.

## 2. 검수 입력, 범위와 방법

다음 frozen review를 source of truth로 읽기 전용 교차검증했다.

- R004 formal review exact SHA
  `fdac0201acc412e9251bcfc7fa60c6c7dd3ae5a8dcf5f10645cfdf8ce6c1b448`,
  판정 `8 BLOCKING / 3 MAJOR / 0 MINOR`
- R004 skeptical review exact SHA
  `15e023b75c479bce8747ff5c32ad4e62fa1fc5b68329ccf7cec880e9b1c824db`,
  판정 `10 BLOCKING / 3 MAJOR / 0 MINOR`
- 두 review의 required-correction union:
  exact `10 BLOCKING / 3 MAJOR`

검수 축은 다음과 같다.

1. R004 union `10B/3M`의 교정 여부를 finding별로 재판정
2. retry namespace, concrete allowlist와 governing authority의 exact equality
3. execution/result cut, terminal/CAS와 finalization recovery의 complete path
4. publication/hash 방향, schema 구성 가능성과 H2/H4 inward reference
5. static/runtime dependency graph, task DAG와 recovery result-set ordering
6. claim ceiling, 현재 공식 상태와 stale 실행 금지 경계

이 review의 ceiling은 frozen roadmap 품질 판정뿐이다. 아래 교정안은
successor 작성, G0/P0, V1, H2~H5, checkpoint, canonical, product,
artifact/formal/device/Gate, production 또는 release 권한이 아니다.

## 3. 독립 기계검증

다음은 PASS했다.

```text
target requested SHA/bytes/lines = exact
target regular non-symlink/nlink = PASS
terminal LF = true
CR/NUL = 0/0
R004 formal review SHA = exact
R004 skeptical review SHA = exact
Quick2 continuation/Goal graph = PASS/PASS
task rows/unique IDs = 15/15
task dependency edges/unique = 25/25
task DAG cycle = 0
gate task membership = 2/1/2/8/2
git diff --check(target) = PASS
```

task DAG의 lexicographic Kahn topological order를 독립 계산하면 다음이다.

```text
T01,T02,T06,T07,T08,T09,T10,T11,T05,T04,T03,T12,T13,T14,T15
```

반면 숫자순 `T01..T15`는 다음 exact edge 7개를 위반한다.

```text
T11→T05
T05→T04
T11→T04
T04→T03
T05→T03
T10→T03
T11→T03
```

또한 exact text 검색으로 다음을 확인했다.

```text
concrete allowlist digest named-field 요구 = 2회, §7 설명문에만 존재
request/response/decision/grant/consume의 해당 named field = 0
authoritative AttemptNamespaceHead/CAS schema/path/edge = 0
"stale attempt namespace head adoption" = negative-fixture 문장 1회
```

정적 task DAG가 무순환인 것과 recovery가 모든 runtime cut에서
결정론적으로 닫히는 것은 서로 다른 조건이다. 위 PASS 항목은 아래
findings를 상쇄하지 않는다.

## 4. R004 formal/skeptical union 재판정

| R004 union correction | R005 재판정 | 근거 |
|---|---|---|
| B001 result `0..15`, CAS truth table | `PARTIAL` | `0..15`와 CAS 3-state는 추가됐지만 observed strict order가 정의되지 않아 `R005-FORMAL-BLOCKING-003` |
| B002 success CAS 뒤 intermediate failure | `CLOSED_DESIGN` | §13.4는 consume exact-one, durable prefix, first failure와 branch-switch checkpoint를 정의 |
| B003 immutable revocation ordinal | `CLOSED_DESIGN` | §7/§13.3은 four-role × ordinal `0/1/2` version path와 pre/post observation을 정의 |
| B004 close 뒤 missing wrapper recovery | `CLOSED_DESIGN` | §6.1/§13.4는 pre-frozen one-use wrapper-only recovery와 adoption equality를 정의 |
| B005 H4 consume self-SHA | `CLOSED_DESIGN` | §17.2는 consume/result/close tagged schema를 분리하고 consume own SHA를 금지 |
| B006 static graph의 미래 runtime 선결정 | `CLOSED_DESIGN` | §8.3은 static union과 post-terminal expansion result를 분리 |
| B007 B04 C/recovery exact extension | `CLOSED_DESIGN` | §9.1.2는 세 variant, N26/T1/X1/V1, target bytes와 physical contract를 정의 |
| B008 B06 five object physical/hash | `CLOSED_DESIGN` | §9.1.1은 10 files, schema, path, publisher, domain/hash와 inward edge를 정의 |
| B009 retry fixed path 충돌 | `PARTIAL` | attempt root는 분리됐지만 authoritative next-namespace 선택과 old-root seal이 없어 `BLOCKING-001`, `MAJOR-001/002` |
| B010 EXPIRED deadline 모순 | `CLOSED_DESIGN` | §6.5/§13.3은 execution cause deadline과 later recovery ceilings를 분리하고 watchdog을 결속 |
| M001 terminal enum vs Gate status | `CLOSED_DESIGN` | §13.3은 typed terminal/state/predicate와 generic Gate status를 분리 |
| M002 H2 consume lineage | `CLOSED_DESIGN` | §9.3 binding payload에 consume SHA/time/head/deadline과 timeline이 직접 존재 |
| M003 M02 exhaustive enum | `CLOSED_DESIGN` | §11 M02는 exact 19 literal roles와 constructor/path/schema/cardinality를 열거 |

따라서 R004 union은 많이 개선됐지만 모두 닫히지는 않았다. R005 자신의
`CLOSED_DESIGN_IN_R005` 표는 이 review의 nonzero finding 때문에
findings-zero 완료조건으로 사용할 수 없다.

## 5. BLOCKING findings

### R005-FORMAL-BLOCKING-001 — attempt namespace가 authoritative next-attempt fork를 막지 못한다

R005는 `ATTEMPT_NAMESPACE_ID`를
`tested_successor_sha`, fresh nonce, materializer actor와 frozen time의
SHA-256으로 만든다(828~833행). namespace freeze payload에는 attempt ordinal,
predecessor terminal-or-`NA`와 root를 둔다(843~850행).

그러나 다음 strict contract가 없다.

- predecessor attempt terminal SHA에서 next ordinal을 계산하는 식
- 같은 predecessor/ordinal에 허용되는 namespace member cardinality
- immutable namespace ordinal member sequence
- authoritative current namespace head 또는 외부 CAS pointer
- head latest-observation receipt와 request/decision의 current-head equality
- 두 materializer가 만든 competing child 중 하나를 고르는 edge

실제 dependency graph에는 namespace freeze payload/wrapper edge만 있고
(1520~1523행), namespace head/CAS node나 edge는 없다. 그럼에도 §13.3은
`stale attempt namespace head adoption`을 mandatory negative fixture라고
한다(3148행). 존재하지 않는 head schema/path/current-selection 식으로는 이
fixture의 expected PASS/FAIL을 계산할 수 없다.

서로 다른 fresh nonce를 쓴 두 materializer가 같은 predecessor와 같은
attempt ordinal을 기록하면 서로 다른 두 ID/path를 얻는다. 두 파일 모두
create-exclusive이고 서로 충돌하지 않으므로 현재 규칙만으로는 둘 다
유효하다. 각 request가 자기 freeze SHA를 결속하는 것은 그 request의 root를
식별할 뿐, 어느 것이 authoritative next attempt인지 증명하지 않는다.

영향:

- retry lineage가 단일 authoritative sequence인지 fork 가능한 DAG인지
  결정되지 않는다.
- attempt ordinal, stale-head 거부와 predecessor history 검증이 재현
  불가능하다.
- R004 B009의 replay/stale retry correction은 부분적으로만 닫힌다.

Required correction:

1. immutable ordinal member와 authoritative namespace-head/CAS 또는 동등한
   append-only selection 계약을 물리화한다.
2. member가 exact predecessor terminal/seal SHA와
   `next_ordinal=predecessor_ordinal+1`을 결속하게 한다.
3. request/response/decision은 latest head observation과 selected member
   digest를 named field로 exact equality 결속한다.
4. same predecessor/ordinal fork, stale observation, skipped ordinal과 head
   CAS race의 mandatory fixture를 추가한다.

### R005-FORMAL-BLOCKING-002 — concrete allowlist digest를 authority schema가 결속하지 않는다

§7은 `AttemptNamespaceFreezePayload`가 모든
`AttemptConcreteAllowlistEntry[]`를 펼쳐 exact path-set/order/digest를
담고, request/response/decision과 각 grant/consume이 **같은 concrete
digest를 named field로 결속한다**고 명시한다(887~893행).

그러나 실제 strict field 목록에는 그 named field가 없다.

- `UserAuthorityRequestPayload`: 2957~2984행
- `ImmutableUserAuthorityResponseReceipt`: 2988~3017행
- `AuthorityBoundaryDecisionEnvelope`: 3021~3052행
- request/response/decision equality 식: 3057~3062행
- `AuthorityConsumeReceipt`: 3151~3169행
- close-recovery/finalization/wrapper-recovery grant·consume:
  3190~3243행, 3298~3418행, 3570~3587행

text search에서도 concrete digest는 §7의 요구 문장에만 있고 authority
schema에는 `0`회다. scope digest는 read/write/exec path set과 deadline,
attempt, nonce를 결속하지만 concrete entry가 가진 schema SHA, publisher
actor/Physical, phase, cardinality와 authority ceiling 전부를 대신하지
않는다.

영향:

- namespace freeze에서 승인된 concrete list와 dispatch/finalization이
  소비한 list의 exact equality를 canonical bytes에서 검증할 수 없다.
- 같은 path에 다른 schema/publisher/cardinality를 쓰는 substitution을
  request→consume 전 구간에서 거부할 named predicate가 없다.
- §7의 allowlist/authority boundary가 자기 strict schema로 구성되지 않는다.

Required correction:

1. `attempt_concrete_allowlist_digest`를 namespace freeze, request, immutable
   response, decision, 모든 grant와 모든 consume의 required named field로
   추가한다.
2. digest의 canonical input을 ordered concrete entries 전체로 고정하고
   freeze wrapper가 독립 재계산한다.
3. 모든 authority transition에서 byte-exact equality를 검사하고
   missing/wrong schema/publisher/cardinality/ceiling fixture를 둔다.
4. generic scope digest나 freeze SHA의 간접 참조로 이 named equality를
   대체하지 못하게 한다.

### R005-FORMAL-BLOCKING-003 — recovery result prefix의 total order가 없고 숫자순 해석은 task DAG와 충돌한다

R005는 recovery terminal이 observed signed result set `0..15`를 받도록
확장했다(3251~3258행). 그러나 observed set이
“`T01`부터의 strict execution order”와 다르면 거부한다(3259~3261행).
terminal 공통 body도 ordered refs를 요구하고 reordered task를 거부한다
(3263~3272행).

문서에는 exact 25-edge task DAG만 있고 runtime dispatch의 exact total
order나 tie-break algorithm은 없다(3808~3865행). 문서에 별도로 나타나는
`canonical_task_id_order=T01..T15`는 execution이 끝난 뒤 final CAS의 15개
completion-member serialization 순서다(3780~3800행). 이것을 recovery
execution order로 가져오면 위 기계검증의 7개 역방향 dependency 때문에
실행 자체가 불가능하다.

예를 들어 T01, T02 뒤 T03은 T04, T05, T10, T11 결과 없이는 실행할 수
없다. 따라서 numeric prefix를 지키면서 세 번째 result를 만들 수 없다.
반대로 valid topological task인 T06이나 T10을 먼저 끝내면 observed set은
`T01부터의 numeric prefix`가 아니어서 crash/expiry terminal이 거부된다.
다른 topological order를 뜻한다면 어느 order인지 frozen bytes가 없어
reviewer마다 다른 prefix를 계산한다.

영향:

- result 2개 이후의 많은 정상 crash/expiry/revocation cut을 terminal로
  닫을 수 없다.
- `ordered_observed_result_ref[]`, `EO001..EO015`와 missing/reordered
  negative fixture의 expected value가 비결정적이다.
- R004 B001의 all-cut closure가 여전히 성립하지 않는다.

Required correction:

1. TD DAG를 만족하는 exact 15-member serial dispatch order와 tie-break를
   successor bytes에 동결하거나, recovery 관찰을 prefix가 아닌
   dependency-downward-closed result set으로 재정의한다.
2. 선택한 모델을 task spec, result, terminal, EO edges, runtime expansion과
   all cut-point truth table에 하나의 named digest로 결속한다.
3. 모든 `0..15` cut에서 valid observed/missing set을 생성해 terminal
   exactly-one을 검증한다.
4. numeric `T01..T15` completion serialization과 actual execution order를
   서로 다른 field/type으로 분리한다.

## 6. MAJOR findings

### R005-FORMAL-MAJOR-001 — namespace-freeze path가 “모든 attempt-specific write는 ATTEMPT_ROOT 아래” invariant와 충돌한다

§7은 매 fresh attempt의 namespace freeze payload/wrapper를

```text
<H1_ROOT>/attempt-namespaces/<ATTEMPT_NAMESPACE_ID>.payload.json
<H1_ROOT>/attempt-namespaces/<ATTEMPT_NAMESPACE_ID>.signature.json
```

에 발행한다고 고정한다(843~850, 918~919행). 이 두 파일은 fresh nonce,
attempt ordinal, predecessor terminal과 concrete attempt path set을 담는
명백한 fresh attempt-specific write다.

반면 §13.5는 “모든 fresh attempt-specific write path는 새
`ATTEMPT_ROOT` 아래”라고 요구한다(3645~3649행). 위 두 literal path는
`<H1_ROOT>/attempts/<ATTEMPT_NAMESPACE_ID>`인 `ATTEMPT_ROOT`의 하위가
아니다. bootstrap exception이나 별도 `ATTEMPT_NAMESPACE_ROOT` type도 없다.

영향:

- strict path checker가 두 규칙을 동시에 PASS할 수 없다.
- R004 B009의 retry path-set intersection 완료 predicate가 어떤 universe를
  비교해야 하는지 모호하다.

Required correction: namespace freeze pair를 명시적인 pre-attempt bootstrap
exception으로 분리하고 disjointness 식의 양쪽 set에 포함시키거나, literal
path를 ATTEMPT_ROOT 아래로 옮긴 뒤 self/future reference가 없는 publication
순서를 다시 고정한다. “모든” invariant와 실제 allowlist는 한 식이어야 한다.

### R005-FORMAL-MAJOR-002 — 이전 attempt root의 immutable-history 주장을 검증할 seal/recheck가 없다

§7은 이전 attempt root를 immutable history라고 선언하고(855~857행),
§13.5도 old root가 immutable history라고 반복한다(3645~3649행). namespace
freeze는 생성 시점의 새 root/parent Physical과 predecessor terminal을
기록하지만, 이전 root의 closed member inventory/tree digest나
terminal seal receipt를 요구하지 않는다.

다음 attempt의 namespace freeze, pre-authority review와 decision field에도
다음 값이 없다.

- previous attempt terminal-seal receipt SHA
- previous root ordered member/path/type/content/Physical set digest
- previous root close 시점과 next-attempt request 직전의 equality recheck
- previous root post-terminal write count `0`

따라서 new write allowlist가 old path와 교집합 `0`인 것은 계산할 수 있어도,
old root bytes가 실제로 보존됐다는 별도 주장은 검증할 수 없다.

영향:

- predecessor terminal만 보존되고 그 terminal이 닫았다는 artifact set의
  mutation 여부는 retry lineage에 들어가지 않는다.
- “immutable history”와 stale/replay 방지의 물리 검증이 prose assertion에
  머문다.

Required correction: each attempt terminal 뒤 ordered member/tree/Physical
digest와 write cardinality를 가진 immutable `AttemptTerminalSealReceipt`를
발행하고, next namespace freeze 및 pre-authority review가 그 exact receipt와
request 직전 recheck equality를 결속해야 한다.

## 7. 확인된 R005 개선과 claim ceiling

아래 보강은 독립 확인했다.

- result count `0..15`, CAS absent/present-unwrapped/wrapped truth table
- execution hard deadline과 later close/wrapper-recovery deadline 분리
- four-role × three-ordinal immutable revocation version path와 observation
- finalization consume exact-one과 success-prefix failure checkpoint
- close 뒤 missing wrapper만 복구하는 one-use recovery
- pre-G1 static graph와 post-terminal runtime expansion 분리
- B04 exact three variants/six files, B06 five pairs/ten files
- H2 binding payload의 actual Stage-C consume/head/deadline 직접 결속
- M02 exact 19-role closed enum
- H4 consume/result/close 순방향 schema와 self-SHA 금지

현재 공식 사실과 금지 경계도 일관되게 유지됐다.

```text
v2.4 ACTIVE / sequence 39
canonical Gap/Backlog = r021/r021
artifact closed-equivalent/open = 126/257, 131
formal = 0/279
Gate = 0/5
authority = ABSENT_DENY_ALL
production = 0
release = NOT_ELIGIBLE
official delta = 0
old S1/R007/stale FP-048 execution = forbidden
```

이 PASS/개선 목록은 roadmap review findings-zero, R007 closure, successor
readiness 또는 실행 권한을 뜻하지 않는다.

## 8. R006 required-correction 완료조건

R006은 기존 R005를 수정하지 않고 add-only successor로 다음을 모두 닫아야
한다.

```text
authoritative attempt namespace member/head/CAS contract = exact 1
same predecessor/ordinal namespace fork accepted = 0
stale namespace-head adoption accepted = 0
request/response/decision/all grant/all consume
  concrete allowlist digest named equality = exact
concrete schema/publisher/cardinality/ceiling substitution accepted = 0
runtime execution total order or downward-closed-set model = exact 1
all result cuts 0..15 terminal exactly-one = PASS
numeric completion order reused as incompatible execution order = 0
namespace bootstrap/path invariant contradiction = 0
previous attempt terminal seal and pre-request recheck = required
R004 union 10B/3M disposition = independently re-reviewed
R006 formal ROADMAP_REVIEW findings = 0/0/0
R006 skeptical ROADMAP_REVIEW findings = 0/0/0
```

R006는 target exact bytes를 freeze한 뒤 서로 독립인 formal/skeptical
review를 같은 SHA에 대해 받아야 한다. 어느 쪽이든 nonzero면 R006도
history로 보존하고 다음 add-only revision으로 교정한다.

## 9. 최종 disposition

```text
BLOCKING=3
MAJOR=2
MINOR=0
verdict=FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR_R006
R005_ROADMAP_REVIEW_FINDINGS_ZERO=false
R007_FINDINGS_CLOSED=false
PRE_P_CLOSURE_SUCCESSOR_EXISTS=false
PRE_P_SUCCESSOR_READY=false
AUTHORITY_BOUNDARY_DECISION_EXISTS=false
CONSTRUCTIVE_FIXTURE_AUTHORIZED=false
CHECKPOINT_OR_CANONICAL_WRITE_AUTHORIZED=false
PRODUCT_OR_RELEASE_AUTHORIZED=false
OFFICIAL_CREDIT_DELTA=0
```

R005는 rejected history로 immutable 보존한다. 이 review는 R005 target,
checkpoint, canonical, Goal, product와 기존 receipt를 수정하지 않았으며
어떤 실행도 승인하지 않는다.
