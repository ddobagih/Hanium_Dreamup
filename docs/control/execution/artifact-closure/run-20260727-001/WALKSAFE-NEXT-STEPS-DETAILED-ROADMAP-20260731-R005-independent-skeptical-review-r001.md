# WalkSafe 다음 단계 상세 로드맵 20260731 R005 독립 공격검수 R001

- 문서 ID:
  `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R005-INDEPENDENT-SKEPTICAL-REVIEW-R001`
- 검토일:
  `2026-07-31`
- review subject type:
  `ROADMAP_REVIEW`
- review authority:
  `NONE / ABSENT_DENY_ALL`
- verdict:
  `FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR_R006`
- findings:
  `BLOCKING=10 / MAJOR=4 / MINOR=1`

## 1. exact target과 검수 ceiling

| 항목 | exact 값 |
|---|---|
| target | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R005.md` |
| SHA-256 | `7ab82008326b3770a1a647d6f05d5f5c2e02533ea0a24b4260aec86d2735a844` |
| bytes / lines | `212,430 / 4,905` |
| owner | `uid=1000 / gid=1000` |
| mode / type / nlink | `0664 / regular non-symlink / 1` |
| terminal LF / CR / NUL | `true / 0 / 0` |
| target declared status | `NONCANONICAL_PLAN_ONLY / NOT_EXECUTABLE / ROADMAP_REVIEW_PENDING` |

검수 시작과 보고서 발행 직전에 target identity를 독립 재계산했다. 이
review는 target, checkpoint, canonical, product, daylog와 memory를 수정하지
않았다.

검수 범위는 R004 formal `8/3/0`과 skeptical `10/3/0` required correction의
합집합, crash/expiry/retry/revocation cut, self/future hash, authority scope,
static/runtime graph projection, B04/B06/M02/H2/H4 materialization이다. 이
판정과 교정안은 successor 작성 또는 실행 권한이 아니며, G0/P0, V1, H2~H5,
checkpoint, canonical, product, formal/device/Gate, production과 release
credit을 만들지 않는다.

## 2. 확인된 R005 개선

다음 R004 결함에는 실제 설계 보완이 들어갔다.

- result `0..15`와 CAS 3-state cut, execution terminal strict enum
- finalization consume 재사용 없는 prefix-failure checkpoint
- four-role revocation ordinal `0/1/2` version/observation 경로
- finalization close 뒤 missing wrapper 전용 recovery grant/consume
- H4 consume/result/close의 순방향 schema와 consume self-SHA 금지
- pre-G1 static union과 post-terminal runtime expansion 분리
- B04 exact three receipt variant와 B06 five object/ten file 계약
- H2 binding payload의 Stage-C consume/head/deadline 직접 결속
- M02 19-role literal registry와 strict wrong-role negative 방향

위 개선은 확인했지만, 아래 잔여 모순 때문에 R005 자체를 executable
successor 설계로 승인할 수 없다.

## 3. BLOCKING findings

### R005-SK-BLOCKING-001 — attempt namespace가 같은 predecessor에서 분기될 수 있다

namespace ID는 successor SHA, fresh nonce, materializer와 freeze time만으로
계산한다(828~833행). attempt ordinal과 predecessor terminal은 payload에
기록할 뿐 ID나 별도 current-head CAS의 전이 조건이 아니다(842~846행).
3147~3149행은 stale namespace head를 negative fixture라고 부르지만 그
head의 path, schema, CAS token과 compare-and-append 규칙이 없다.

따라서 같은 predecessor와 ordinal에 서로 다른 nonce를 가진 두 freeze를
동시에 create-exclusive 발행하면 서로 다른 path라 둘 다 성공한다. 이후 두
authority lineage가 모두 자신을 fresh attempt라고 주장할 수 있다.

Required correction:

1. predecessor attempt terminal SHA와 next ordinal을 namespace identity에
   직접 포함한다.
2. signed immutable namespace-head와 compare-and-append CAS receipt를
   정의한다.
3. 같은 predecessor/ordinal의 second child, skipped ordinal과 concurrent
   publication fixture를 거부한다.

### R005-SK-BLOCKING-002 — concrete allowlist digest named field가 authority schema에 없다

887~893행은 namespace freeze가 만든 concrete path-set digest를
request/response/decision과 모든 grant/consume이 같은 named field로
결속해야 한다고 규정한다. 그러나 실제 request/response/decision field
목록(2957~3049행), execution consume(3151~3169행)과 recovery/finalization
grant/consume에는 그 named field가 없다. target 전체에서 concrete digest
언급도 889행과 892행의 요구문뿐이다.

그 결과 authority scope가 승인된 namespace의 exact concrete path set과
같은지 strict verifier가 재계산할 입력이 없다. scope digest가 같더라도 다른
concrete allowlist를 선택하는 권한 우회를 배제할 수 없다.

Required correction:

1. `attempt_concrete_allowlist_digest`를 freeze, request, immutable response,
   decision, four grant와 four consume schema에 추가한다.
2. constructor에서 재계산한 ordered entry set/count/digest와 모든 artifact의
   byte equality를 mandatory predicate로 둔다.
3. missing, reordered, subset, superset과 wrong-attempt digest fixture를
   거부한다.

### R005-SK-BLOCKING-003 — freshness 변경 뒤 새 successor root를 구성할 계약이 없다

유일한 `PRE_SUCCESSOR_ROOT/H1_ROOT`는 fixed `...r001` literal이다
(821~840행). 하지만 protected freshness, dependency/schema/template가
바뀌면 새 successor revision과 새 disjoint roots를 요구하고 기존 r001
재사용/overwrite를 금지한다(857~861, 3620~3623, 3645~3649행).

새 revision/root ID, literal path constructor, predecessor root terminal과
one-way transition receipt가 없으므로 첫 revision 뒤 freshness가 바뀌면
준수 가능한 새 root를 만들 수 없다.

Required correction:

1. successor revision ID와 두 root의 deterministic constructor를
   materialize한다.
2. previous root terminal/seal에서 new revision freeze로 가는 add-only
   transition receipt와 edge를 정의한다.
3. same-revision overwrite, old-root reuse, two-child revision fork를
   negative fixture로 둔다.

### R005-SK-BLOCKING-004 — close-recovery grant/watchdog가 execution consume의 필수 predecessor가 아니다

timeline은 close-recovery grant가 execution consume보다 먼저 동결되어야
한다고 한다(728~742, 3175~3187행). 그러나 static DAG의 execution consume
predecessor는 finalization grant와 V1 spec bundle뿐이고
(1527~1530행), close-recovery grant는 recovery consume으로만 이어진다
(1560~1562행). `AuthorityConsumeReceipt`에도 close-recovery grant/wrapper와
watchdog identity가 없다(3151~3169행).

따라서 grant 없이 execution을 consume한 뒤 crash하면 grant를 사후 발행할
수도 없고 valid recovery terminal도 만들 수 없다. 반대로 정상 성공하면
필수 선행 누락을 downstream이 판별하지 못한다.

Required correction:

1. close-recovery grant wrapper와 watchdog freeze에서 execution consume으로
   exact predecessor edge를 추가한다.
2. execution consume body가 grant/wrapper SHA, watchdog spec/config/Physical과
   freeze time을 직접 결속하게 한다.
3. grant absent/late/wrong watchdog이면 dispatch cardinality를 `0`으로
   검증한다.

### R005-SK-BLOCKING-005 — revocation CAS 확인과 durable consume 사이가 원자적이지 않다

R005는 consume 직전 source CAS를 다시 읽고 token/digest equality를
비교한다고 한다(3069~3070, 3116~3121행). consume receipt는 읽은 CAS 값을
기록하지만(3151~3169행), source CAS에 대한 conditional state transition과
durable consume publication이 하나의 linearizable operation이라는 계약은
없다.

CAS 재확인 직후, consume 발행 또는 dispatch 직전에 revoke가 성공하면
stale `UNREVOKED` 관찰로 실행이 시작된다. post-observation failure-close는
이미 발생한 unauthorized dispatch를 되돌리지 못한다.

Required correction:

1. source CAS가 exact observed token일 때만 `UNSPENT→CONSUMED`를 수행하는
   atomic compare-and-consume primitive를 정의한다.
2. 그 transaction receipt와 dispatch-start를 같은 ordered lineage에
   결속한다.
3. reread 뒤 revoke, simultaneous consume/revoke와 duplicate consume
   interleaving fixture를 전수 검사한다.

### R005-SK-BLOCKING-006 — revocation/crash/expiry가 겹칠 때 terminal exactly-one이 결정되지 않는다

truth table의 revocation, process crash와 execution expiry 행은 같은
`0..15 + CAS_ABSENT` 상태에서 동시에 성립할 수 있고, 15-result/unwrapped
CAS 행도 세 원인을 한 행에 합친다(3247~3255행). total selection rule은
CRASH와 EXPIRED가 함께 관찰된 경우만 정의한다(3262~3270행). revocation과
crash 또는 expiry가 동시에 관찰된 경우의 우선순위는 없다.

따라서 동일 physical cut에서 FAILURE/CRASH/EXPIRED 둘 이상이 eligible해져
terminal cardinality `1`과 deterministic replay를 보장할 수 없다.

Required correction:

1. revocation, crash, expiry와 normal completion 전부에 대해 total
   cause-precedence table을 동결한다.
2. cause observation의 trusted time tie-break, CAS state와 adoption rule을
   한 selector에 넣는다.
3. 모든 pair/triple simultaneous fixture에서 selected terminal이 정확히
   하나인지 검사한다.

### R005-SK-BLOCKING-007 — recovery의 numeric result prefix가 실제 task DAG와 충돌한다

recovery는 observed result set을 T01부터의 strict execution order라고
제한한다(3257~3260, 3288~3289행). 그러나 실제 TD DAG는 T11이 T05보다,
T05가 T04보다, T04/T05/T10/T11이 T03보다 먼저여야 한다
(3810~3826, 3839~3865행). 즉 합법적 실행은 일반적으로 numeric
`T01,T02,T03...` prefix가 아니다.

예를 들어 T01/T02/T10/T11이 끝나고 T05 전후에 crash하면 observed set은
합법적 downward-closed DAG prefix지만 numeric prefix는 아니다. normal
terminal은 15 result가 없어 불가하고 recovery도 이를 거부해 attempt가
닫히지 않는다.

Required correction:

1. recovery set을 numeric prefix가 아니라 TD25에 대한 exact
   downward-closed set으로 정의한다.
2. parallel 실행을 금지하려면 대신 deterministic topological schedule과
   event ordinal을 명시적으로 freeze한다.
3. 각 legal DAG cut, missing predecessor와 reordered event fixture를
   검사한다.

### R005-SK-BLOCKING-008 — recovery terminal이 finalization deadline 뒤에 생길 수 있다

R005는
`execution_hard_deadline < close_recovery_hard_deadline`만 강제한다
(737~746행). normal finalization은 selected wrapper까지
`finalization_hard_deadline` 이내여야 하고(748~759행), 별도 규칙은
`finalization_hard_deadline < terminal_wrapper_recovery_hard_deadline`만
정한다(3381~3384행).

`close_recovery_hard_deadline <= finalization_hard_deadline` 관계가 없으므로
EXPIRED execution terminal이 finalization deadline 뒤에 합법적으로
발행될 수 있다. 그 terminal은 finalization eligible이라고 선언돼도
finalization consume/close할 시간이 없어 영구 partial attempt가 된다.

Required correction:

1. 최소
   `execution < close-recovery <= finalization < wrapper-recovery`
   deadline order를 freeze한다.
2. 그렇지 않으면 late recovery terminal을 위한 별도 bounded finalization
   recovery authority/timeline을 정의한다.
3. 각 boundary equality와 one-tick-before/after fixture를 검사한다.

### R005-SK-BLOCKING-009 — G6가 아직 존재하지 않는 P7/G7/Ready의 actual equality를 요구한다

static success projection의 `FINAL_GATE`에는 G6 뒤의 P7 reviews/pairs,
G7과 Ready payload/wrapper가 포함되고(1498~1506행), edge도
`G6→P7→G7→Ready` 순서다(1603~1618행). normative success DAG도 같은 순서를
요구한다(3438~3446행). 그런데 G6 PASS predicate가 이미 full success suffix
actual/projected node-edge set equality를 요구한다(4047~4048행).

G6 실행 시점에는 P7/G7/Ready actual files가 아직 없어 full projected set과
같을 수 없다. 그것들을 먼저 만들면 mandatory order를 거꾸로 실행한다.

Required correction:

1. G6에서는 G6까지의 prefix projection equality만 검사한다.
2. P7/G7/Ready까지의 full equality는 Ready 직전 별도 post-G7 verifier 또는
   terminal close predicate로 이동한다.
3. 각 phase의 projected/actual set 경계를 서로 다른 named digest로
   정의한다.

### R005-SK-BLOCKING-010 — wrapper recovery 중 post-consume revocation 결과가 서로 모순된다

공통 revocation 규칙/FSM은 consume 뒤 larger `REVOKED`를 관찰하면
`FAILURE_CLOSE_ONLY`로만 닫고 ready credit을 `0`으로 한다
(3126~3144행). 반면 wrapper-recovery graph는 post-observation에서 selected
terminal wrapper로 직접 가며(1556~1559행), recovery schema는 existing
READY 또는 FAILURE close의 expected wrapper를 그대로 만들고 optional
post-consume revocation observation까지 wrapper에 넣을 수 있다
(3558~3568, 3589~3597행).

이미 READY payload/close가 존재한 뒤 wrapper-recovery consume과 revoke가
겹치면 기존 close를 rewrite할 수 없다. common FSM대로 FAILURE로 바꿀 수도
없고, graph대로 READY wrapper를 쓰면 revocation 뒤 ready 금지를 어긴다.

Required correction:

1. wrapper-recovery consume/revoke의 linearization point와 total outcome
   table을 정의한다.
2. READY partial close의 post-revocation disposition을 add-only로 구성 가능한
   exact artifact/edge로 물리화한다.
3. ready/failure close 각각에 pre/post-consume revoke fixture와 wrapper
   cardinality를 고정한다.

## 4. MAJOR findings

### R005-SK-MAJOR-001 — 이전 attempt root의 immutability를 검증할 seal이 없다

namespace freeze는 root/parent Physical과 predecessor terminal을 기록한다고
하지만(842~846행), old root를 immutable history라고 단정하는 부분
(855~861, 3645~3649행)에 ordered member manifest, tree digest, close seal과
다음 attempt 전 재검증 규칙이 없다.

이전 attempt의 raw member를 바꾸거나 새 파일을 추가해도 terminal file
identity만 유지되면 새 freeze가 mutation을 판별하지 못한다.

Required correction: terminal 시점의 nofollow ordered tree/member digest와
다음 namespace 전 byte/set equality recheck receipt를 추가한다.

### R005-SK-MAJOR-002 — namespace freeze 위치와 “모든 attempt write” invariant가 충돌한다

freeze payload/wrapper는
`<H1_ROOT>/attempt-namespaces/<ATTEMPT_NAMESPACE_ID>.*`에 둔다
(842~850행). 그러나 retry 규칙은 모든 fresh attempt-specific write path가
새 `ATTEMPT_ROOT` 아래라고 단정한다(3643~3646행).

freeze 자체가 attempt-specific durable write이므로 두 규칙을 동시에
만족하지 않는다. verifier에 따라 valid freeze가 path escape로 거부되거나
통제-plane 예외가 암묵적으로 생긴다.

Required correction: freeze를 ATTEMPT_ROOT 안으로 옮기거나, H1
control-plane namespace를 명시적 closed exception으로 정의하고 두 set의
cardinality/digest를 분리한다.

### R005-SK-MAJOR-003 — M02 `LIVE_ROOT_TRIPLE`이 canonical actual triple과 다르다

actual Stage-C 계약은 exact triple을 live-root manifest,
physical-publication receipt와 `LIVE_ROOT_PUBLISHED ProgressRef`로 정의하고
모든 actual object가 세 SHA를 byte-equal로 결속하게 한다
(2291~2301행). M02의 `LIVE_ROOT_TRIPLE` row는 H2 binding V2, manifest wrapper,
physical-publication receipt를 사용해 ProgressRef를 빼고 H2 binding으로
대체한다(2707행).

M02가 19/19 PASS해도 §9의 actual input triple과 다른 object를 검증하게
되어 H2→Stage-C same-root 증명을 대체할 수 없다.

Required correction: M02 row를 §9 exact triple의 세 role/SHA와
byte-equal하게 만들고 H2 binding은 별도 predecessor role로 둔다.

### R005-SK-MAJOR-004 — runtime branch enum이 두 곳에서 다른 값을 쓴다

expansion schema는
`SUCCESS_SUFFIX_CANDIDATE | FAILURE_TAIL`을 strict 값으로 사용한다
(1330, 1339~1340행). graph materialization 설명은 같은
`selected_branch`에 `SUCCESS XOR FAILURE`를 기록하라고 한다
(1741~1745행).

strict serializer/checker가 어느 vocabulary를 정본으로 삼는지에 따라 같은
expansion payload가 PASS/FAIL로 갈린다.

Required correction: 하나의 closed branch enum과 schema domain을 정하고
graph/checkpoint/G3/G6 전체에서 exact token을 재사용한다.

## 5. MINOR finding

### R005-SK-MINOR-001 — Gate graph field 수 설명이 실제 schema와 다르다

Gate spec의 graph-related field는 633~640행에 8개지만 708~709행은 “일곱
graph field”라고 한다. 필드 이름은 열거돼 있어 설계 복구는 가능하지만
cardinality fixture와 문구가 불일치한다.

Required correction: 실제 strict field 수에 맞게 `8`로 고치고 NA
cardinality fixture도 같은 수를 사용한다.

## 6. R004 합집합 재판정

| R004 correction 축 | R005 공격 재판정 |
|---|---|
| 15-result/CAS cut | 기본 truth table 추가; cause totality와 DAG cut은 B006/B007 |
| prefix failure switch | 기본 checkpoint 추가; 해당 R004 finding은 설계상 교정 |
| versioned revocation | immutable slots 추가; fork/CAS/recovery revoke는 B001/B005/B010 |
| terminal wrapper recovery | 기본 recovery 추가; post-revoke outcome은 B010 |
| H4 self-SHA | 세 tagged schema로 교정 확인 |
| static/runtime graph split | 기본 분리; G6 timing과 enum은 B009/M004 |
| retry/fresh root | attempt root 추가; namespace fork/root transition은 B001/B003 |
| recovery deadlines/watchdog | watchdog 추가; predecessor/deadline order는 B004/B008 |
| B04/B06 | exact schema/path/hash 방향 교정 확인 |
| H2 consume lineage | payload direct binding 교정 확인 |
| M02 closed role enum | 19 rows 추가; actual triple row는 M003 |

## 7. 결론과 다음 조치

R005는 R004의 큰 구조 결함을 상당수 물리화했지만, 현재 exact SHA에는
authority namespace fork, scope 결속 누락, crash terminalization 공백,
revocation race/비결정성, G6 시간 역전과 root transition 부재가 남아 있다.
그러므로 판정은 다음과 같다.

```text
review verdict = FAIL
required successor = add-only R006
R005 executable authority = NONE
official progress delta = 0
checkpoint/canonical/product mutation authorized = false
```

R006는 먼저 B001~B010을 모두 구성 가능한 exact schema/edge/deadline
계약으로 닫고, M001~M004와 minor를 정리한 뒤 동일 target SHA에 대한 새
formal/skeptical 독립검수를 받아야 한다. 그 전에는 R005를 근거로 G0/P0,
successor execution, user authority 질문, V1 또는 downstream을 시작하면
안 된다.
