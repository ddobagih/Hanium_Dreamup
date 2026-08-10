# WalkSafe 다음 단계 상세 로드맵 20260731 R006 독립검수 R001

- 문서 ID:
  `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-INDEPENDENT-REVIEW-R001`
- 검토일:
  `2026-07-31`
- review subject type:
  `ROADMAP_REVIEW`
- review authority:
  `NONE / ABSENT_DENY_ALL`
- verdict:
  `FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR_R007`
- findings:
  `BLOCKING=6 / MAJOR=0 / MINOR=0`

## 1. exact target과 물리 identity

| 항목 | exact 값 |
|---|---|
| target | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006.md` |
| SHA-256 | `0795cff0703b85469b61f454cbfefd3d6351b94900aaa49025edca3231d35c0a` |
| bytes / lines | `306,496 / 6,670` |
| owner | `uid=1000 / gid=1000` |
| mode / type / nlink | `0664 / regular non-symlink / 1` |
| terminal LF / CR / NUL | `true / 0 / 0` |

검수 시작과 보고서 작성 직전에 위 identity를 독립 재계산했다. target은
요청받은 exact SHA와 일치했고 이 review는 target을 수정하지 않았다.

## 2. 검수 입력, 범위와 방법

다음 frozen review를 source of truth로 읽기 전용 교차검증했다.

- R005 formal review exact SHA
  `21ca1a6faee68b84abc0c83b5389eab5c4864cd5ebb3eee94064c3dca437186c`,
  `18,757 bytes / 403 lines`, 판정 `3 BLOCKING / 2 MAJOR / 0 MINOR`
- R005 skeptical review exact SHA
  `7096234daae97d732b42ca47c7d9f42f1fe2125943c4884cb79d35585bb9a4ba`,
  `18,452 bytes / 376 lines`, 판정 `10 BLOCKING / 4 MAJOR / 1 MINOR`
- 두 review의 required-correction union:
  exact `10 BLOCKING / 4 MAJOR / 1 MINOR`, unique row `15`

검수 축은 다음과 같다.

1. R005 formal `5/5`, skeptical `15/15`, union `15/15` required correction
2. G0 bootstrap no-cycle, revision/namespace append/CAS/seal과 plane binding
3. four-role authority, pre-freeze, consume/revoke/expiry FSM과 terminal selector
4. TD25 legal cut, branch-dependent edge condition과 execution/finalization DAG
5. finalization write authority, G6 prefix, P7/G7와 post-G7 full projection close
6. schema/path/publisher/cardinality, task/finding/debt/milestone crosswalk와 §22

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
R005 formal/skeptical review SHA = exact/exact
Quick2 continuation/Goal graph = PASS/PASS
task rows/unique IDs = 15/15
task dependency edges/unique = 25/25
task DAG cycle = 0
TD25 legal downward-closed cuts = 376/32768
gate task membership = 2/1/2/8/2
R005 formal finding IDs = 5/5
R005 skeptical finding IDs = 15/15
R005 union unique correction rows = 15/15
git diff --check(target) = PASS
```

task DAG의 lexicographic Kahn topological order는 다음과 같이 독립
재계산했다.

```text
T01,T02,T06,T07,T08,T09,T10,T11,T05,T04,T03,T12,T13,T14,T15
```

위 PASS는 문서의 수치와 정적 DAG 산술이 맞다는 뜻이다. authority가 모든
허용 cut을 구성할 수 있는지, 필수 output에 실제 write authority가 있는지,
동시성 FSM이 add-only artifact로 닫히는지는 별도 조건이며 아래 finding을
상쇄하지 않는다.

## 4. R005 formal/skeptical union 재판정

| R005 review correction | R006 재판정 | 근거 |
|---|---|---|
| attempt ordinal member/head/compare-append CAS | `CLOSED_DESIGN` | §7.1 member/head/observation, `AN001..AN012`, fork/skip/stale fixtures |
| concrete allowlist와 AttemptPlaneSetBinding | `CLOSED_DESIGN` | §7.1과 §13.2/§13.8의 embedded entries/count/digest/union/intersection |
| genuine G0 bootstrap와 revision constructor | `CLOSED_DESIGN` | §0.1, §4.1, §7.1, `GB001..GB013`, current-P0-free constructor |
| watchdog/close-recovery grant direct predecessor | `CLOSED_DESIGN` | `ER005/ER007`, revised execution consume fields, fail-closed dispatch |
| four-role same-store consume/revoke | `CLOSED_DESIGN` | §13.8.2 crosswalk, atomic transaction과 durable receipt |
| total cause selector | `PARTIAL` | priority는 생겼지만 CRASH source artifact가 없어 `R006-FORMAL-BLOCKING-004` |
| TD25 static/runtime legal cut | `CLOSED_DESIGN` | exact 25 edges, static 4/runtime 3, independent count `376/32768` |
| deadline order | `CLOSED_DESIGN` | `execution < close-recovery <= finalization < wrapper-recovery` |
| G6 prefix와 post-G7 full projection 분리 | `CLOSED_DESIGN` | G6 self/future-free prefix, P7 direct result, 별도 post-G7 pair |
| wrapper recovery total outcome | `PARTIAL` | post-consume revoke와 wrapper publication이 선형화되지 않아 `R006-FORMAL-BLOCKING-005` |
| final attempt seal/OldRootRecheck | `CLOSED_DESIGN` | final seal, nofollow reopen, recheck→CAS direct edge |
| control/data plane exact union | `CLOSED_DESIGN` | ordered entry/count/digest/union/intersection `0` |
| M02 live-root triple | `CLOSED_DESIGN` | manifest wrapper/publication receipt/ProgressRef exact 3, H2 binding 별도 |
| single branch enum | `CLOSED_DESIGN` | `RuntimeBranchEnumV1`, checkpoint/G3/G6/post-G7 named equality |
| GateGraphBinding exact eight fields | `CLOSED_DESIGN` | graph 8 fields, branch/TD25 fields 분리 |

따라서 R005 union 재판정은 다음과 같다.

```text
formal required corrections = CLOSED 5 / PARTIAL 0 / OPEN 0
skeptical required corrections = CLOSED 13 / PARTIAL 2 / OPEN 0
union unique rows = CLOSED 13 / PARTIAL 2 / OPEN 0
```

R006 §21.4의 `15/15 mapped`는 설계 위치가 존재한다는 산술로는 맞다. 그러나
`mapped`는 `closed`가 아니며, 위 두 partial과 아래 신규/regression
finding 때문에 findings-zero 완료조건은 성립하지 않는다.

## 5. BLOCKING findings

### R006-FORMAL-BLOCKING-001 — finalization grant의 pre-execution freeze가 DAG와 consume bytes에서 빠졌다

R006은 `PostCloseFinalizationAuthorityGrantPayload`를 governing decision 뒤,
execution consume 전에 freeze해야 한다고 명시한다(4176~4180행). 그러나
유일한 normative graph는 `GE012 DECISION→FINALIZATION-GRANT`,
`GE013 DECISION→V1-SPEC-BUNDLE` 뒤 바로
`GE015 V1-SPEC-BUNDLE→EXECUTION-CONSUME-INTENT`로 건너뛴다
(2240~2244행). `FINALIZATION-GRANT→EXECUTION-CONSUME-INTENT` edge는 없다.

이는 단순 번호 공백이 아니다. rejected-history R005에는 정확히
`GE014 FINALIZATION-GRANT→EXECUTION-CONSUME`가 있었지만
(R005 1527~1530행), R006에서 edge와 대체 binding이 함께 사라졌다.
`AuthorityConsumeReceipt` strict body에도 finalization grant SHA가 없고
(4005~4030행), R006이 추가한 mandatory execution-consume fields도 watchdog과
close-recovery grant만 결속한다(4887~4899행).

그 결과 normative graph를 그대로 지킨 dispatcher는 finalization grant가
없거나 늦어도 task를 시작할 수 있다. execution 뒤 프로세스가 사라지면
이미 시작된 attempt를 pre-frozen finalization authority로 닫을 수 없고,
뒤늦은 grant 작성은 4178~4179행의 invariant를 위반한다.

Required correction:

1. `FINALIZATION-GRANT-WRAPPER→EXECUTION-CONSUME-INTENT`를 unique static graph의
   direct predecessor로 복원한다.
2. execution atomic consume intent와 `AuthorityConsumeReceipt`에 finalization
   grant payload/wrapper SHA, publication/freeze time, attempt/nonce/scope와
   concrete allowlist/plane binding을 named field로 추가한다.
3. absent/late/wrong-attempt/wrong-scope grant이면 consume, dispatch와 lease
   cardinality를 모두 `0`으로 둔다.
4. grant missing, grant-after-consume와 wrong wrapper SHA fixture를 추가한다.

### R006-FORMAL-BLOCKING-002 — wrapper-recovery grant의 pre-finalization freeze도 실행 가능한 predecessor가 아니다

strict timeline은 terminal-wrapper recovery grant가 original finalization
consume보다 먼저 freeze돼야 한다고 요구한다(876~887행). §13.4도 같은
조건을 반복하고, grant가 finalization consume 뒤 freeze되면 recovery
cardinality가 `0`이라고 한다(4221~4222, 4267~4270행).

그러나 `WR001..WR005`는 decision/finalization grant에서 wrapper-recovery
grant pair를 만들고 그 wrapper를 **wrapper-recovery consume intent**에만
연결한다(2409~2415행). wrapper-recovery grant wrapper에서 original
`FINALIZATION-CONSUME`으로 가는 predecessor edge는 없다.
`PostCloseFinalizationConsumeReceipt`도 finalization grant만 결속하고
wrapper-recovery grant SHA, write-subset digest와 freeze/publication time은
담지 않는다(4276~4291행).

따라서 original finalization consume과 side effect가 먼저 시작되고
recovery grant가 아직 durable하지 않은 cut을 graph/schema가 거부하지
못한다. finalization close 뒤 terminal wrapper 전에 crash가 나면 required
bounded recovery authority가 없어 partial close를 닫을 수 없다.

Required correction:

1. `TERMINAL-WRAPPER-RECOVERY-GRANT-WRAPPER→FINALIZATION-CONSUME-INTENT`를
   direct predecessor로 추가한다.
2. finalization consume intent/receipt에 grant payload/wrapper SHA,
   frozen/published time, scope/write-subset digest, initial revocation head와
   same namespace/allowlist/plane binding을 직접 넣는다.
3. grant publication이 finalization consume보다 늦거나 field가 다르면
   finalization consume과 downstream write를 `0`으로 둔다.
4. grant-before-consume positive, grant-after-consume와 missing-edge
   negative fixture를 고정한다.

### R006-FORMAL-BLOCKING-003 — 필수 post-G7 projection pair가 finalization write authority에 없다

finalization grant의 `literal_write_set[]`은 finalization control,
dependency expansion, task completion, CAS/milestone/22+4, G1E..G7,
P7와 `ready payload/wrapper`, failure tail을 열거한다
(4197~4206행). 별도 role인
`PostG7FullProjectionCheckPayload/Wrapper` 두 파일은 이 exact set에 없다.

반면 allowlist 표는 ready evaluation pair와 post-G7 pair를 합쳐 exact
`4` roles로 구분한다(1695~1697행). success DAG와 unique graph는 Ready
payload 뒤 post-G7 pair를 발행하고 그 wrapper가 finalization close의
필수 predecessor여야 한다고 규정한다(2381~2386, 4323~4338,
5776~5835행).

grant의 scope는 request/response/decision/grant/consume 사이 exact equality이고
consume 뒤 늘릴 수 없다(4293~4313행). global concrete allowlist는 실행
권한이 아니며(1451~1458행), R006 자신도 allowlist가 finalization authority를
대체하지 못한다고 명시한다(4501~4507행). 따라서 성공 경로의 필수 pair는
허용 경로에는 있지만 쓸 권한이 없어 close와 effective ready에 도달할 수
없다.

Required correction:

1. 두 exact literal path를 별도 schema role/publisher/Physical/cardinality와
   함께 finalization grant의 `SUCCESS_SUFFIX_CANDIDATE` write set에 추가한다.
2. request/response/decision/grant/consume의 scope digest와 branch cardinality가
   이 두 entry를 byte-exact 결속하게 한다.
3. `GE039..GE041`과 close schema가 같은 payload/wrapper SHA를 predecessor로
   검증한다.
4. pair missing, only-allowlisted, wrong publisher/schema와 scope-substitution
   fixture를 추가한다.

### R006-FORMAL-BLOCKING-004 — CRASH cause에 구성 가능한 signed source artifact가 없다

유일한 selector는 REVOCATION, EXPIRY, CRASH와 NORMAL을 비교하지만 CRASH는
`watchdog-proven lost-process time` 한 줄로만 정의한다(5127~5153행).
같은 selector는 모든 candidate에 source payload/receipt SHA,
trusted-clock correlation과 effective/observed time을 요구하고 source가
없으면 선택할 수 없다고 한다(5168~5175행).

REVOCATION에는 durable transaction receipt가 있고 EXPIRY에는 strict
`ExecutionDeadlineWatchdogObservationReceipt`가 있다. 반면 CRASH에는
다음이 전부 없다.

- strict schema와 literal path
- process/watchdog identity와 publisher actor/Physical
- signed source receipt와 trusted effective/observed time
- close-recovery grant literal read-set entry
- recovery consume과 selector로 가는 exact direct edge

required node set도 crash observation artifact를 열거하지 않고, graph는
단지 `ET003 selected revoke/expiry/crash source`라고만 쓴다
(2012~2018, 2330~2335행). 그 결과 CRASH 단독 또는 동시 cause cut에서
selector가 요구하는 canonical source bytes를 만들 수 없다.

Required correction:

1. 별도 signed `ExecutionCrashObservationReceipt` 또는 watchdog의 strict
   tagged CRASH variant 중 정확히 하나를 정의한다.
2. exact path/schema/publisher/Physical, watched process identity,
   watchdog payload/wrapper, lost-process effective time, observed time와
   trusted-clock correlation을 고정한다.
3. 그 path를 close-recovery grant read set/scope에 pre-freeze하고 receipt를
   recovery consume, `ET003`, selector CAS와 terminal body에 직접 결속한다.
4. CRASH absent/forged/stale/wrong-process/untrusted-time 및
   REVOCATION/EXPIRY/NORMAL과의 pair/triple/quadruple fixture를 검사한다.

### R006-FORMAL-BLOCKING-005 — wrapper publish와 post-consume revoke 사이에 add-only 선형화 지점이 없다

terminal-wrapper recovery consume은 same-store CAS에서 state를
`CONSUMED`로 바꾸고 wrapper-publication lease를 반환한다
(4976~4981행). 그 뒤 revoke는 같은 key의 `CONSUMED` 상태에서 여전히
accepted되어 `CONSUMED_POST_REVOKE_OBSERVED`와 durable receipt를 만든다
(4983~4999행).

expected wrapper는 CAS transaction이 아니라 별도 create-exclusive
file/fsync/reopen으로 발행되고, accepted post-consume revoke receipt와
observation을 inward-reference해야 한다(4491~4499행). 이 두 동작 사이를
닫는 terminalization CAS 또는 durable selection receipt가 없다.
`WRAPPER_PUBLISHED→ROLE_TERMINALIZED`는 선언적 FSM 문장일 뿐
(5311~5315행), wrapper bytes를 동결하기 전 future accepted revoke가 더
없다는 것을 원자적으로 증명하지 않는다.

따라서 다음 합법 race를 구성할 수 없다.

```text
consume CAS wins
→ wrapper bytes freeze/create 시작
→ revoke linearizes as accepted POST_CONSUME_REVOKE_OBSERVED
→ wrapper publication completes
```

wrapper가 revoke receipt를 생략하면 inward-reference 규칙을 위반하고,
future receipt를 미리 넣을 수도 없으며, 이미 create-exclusive 발행한
wrapper를 rewrite할 수도 없다. R005 skeptical B010은 total outcome table이
추가됐지만 이 cut 때문에 `PARTIAL`이다.

Required correction:

1. terminal-wrapper role에서 consume winner가 곧 immutable
   `WRAPPER_PUBLICATION_SELECTED` state를 원자 선택하고 이후 revoke를
   `effect=NONE`으로 만드는 방법, 또는 동등한 final selection CAS/receipt를
   정확히 하나 정의한다.
2. selected receipt가 wrapper path/schema/publisher, payload/close Physical,
   final revoke receipt SHA-or-`NA`와 publication lease를 동결하게 한다.
3. wrapper는 그 receipt를 inward-reference하고, selection 뒤 revoke는
   token/receipt/lease/write를 만들지 않게 한다.
4. consume→revoke→wrapper, consume→wrapper→late-revoke와 simultaneous
   freeze/revoke/publish fixture에서 terminal exactly-one을 검증한다.

### R006-FORMAL-BLOCKING-006 — 15-result recovery CAS cut과 GT edge condition이 서로 모순된다

truth table은 exact 15 results와 `CAS_PRESENT_UNWRAPPED` 상태에서
REVOCATION/CRASH/EXPIRY가 NORMAL보다 먼저 effective하면 recovery terminal이
그 CAS의 sole wrapper가 되는 cut을 명시한다(4117~4125행). CAS schema도
15-result cut의 payload가 SUCCESS/FAILURE/CRASH/EXPIRED terminal wrapper를
받을 수 있다고 반복한다(4671~4679행).

그러나 unique graph의 `GT016..GT030`은 T01..T15 result에서
`CAS-PRECLOSE`로 가는 유일한 15개 edge인데(2328~2330행), branch
materialization 규칙은 `GT016..GT045` 전체를 non-recovery
SUCCESS/FAILURE에서만 materialize하고 recovery
FAILURE/CRASH/EXPIRED에서는 `0`으로 만든다(2542~2544행).

따라서 15-result recovery cut에서는 `CAS_PRESENT_UNWRAPPED`를 주장하면서
그 CAS의 15개 result predecessor edge는 exact `0`이어야 한다. CAS에 edge를
넣으면 branch rule의 extra edge이고, 빼면 CAS body의 15-member lineage와
graph가 맞지 않는다. mandatory 15-result/unwrapped-CAS recovery cut을
동시에 만족할 수 없다.

Required correction:

1. `GT016..GT030`과 `GT031..GT045`의 materialization 조건을 분리한다.
2. `GT016..GT030`은 terminal kind가 아니라
   `CAS_PRESENT_UNWRAPPED|CAS_WRAPPED`인 exact 15-result cut에서 항상
   cardinality `15`가 되게 한다.
3. recovery selector input은 `EO` observed set을 사용하되, normal용
   `GT031..GT045`와 중복/대체 조건을 exact branch table로 고정한다.
4. `<15/CAS-absent`, `15/CAS-absent`, `15/CAS-unwrapped recovery`,
   `15/CAS-unwrapped normal`, wrapped adoption 각각의
   `GT016..045/EO/ET` cardinality truth table을 fixture로 검증한다.

## 6. 확인된 R006 개선과 claim ceiling

아래 보강은 독립 확인했다.

- pure G0 bootstrap root, sole documentation provenance와 no current-P0 cycle
- successor revision/attempt namespace ordinal member, signed head, CAS와 seal
- concrete allowlist/plane set entries/count/digest/union/intersection binding
- watchdog/close-recovery direct execution predecessors와 exact deadline order
- four-role same-store consume/revoke transaction과 durable receipt
- TD25 25-edge downward-closed registry와 static `4`/runtime `3` 분리
- G6 self/future-free prefix와 P7 direct G6-result consumption
- separate post-G7 full projection schema와 Ready-before-check-before-close DAG
- M02 actual triple, single branch enum과 GateGraphBinding exact eight fields
- exact 15 task, 22 finding, 4 debt, 29 unique milestone 설계 산술

현재 공식 사실과 실행 금지 경계도 일관되게 유지됐다.

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
old S1/rejected R007/stale FP-048 execution = forbidden
```

이 개선 목록은 findings-zero, R007 closure, successor readiness 또는 실행
권한을 뜻하지 않는다.

## 7. add-only R007 required-correction 완료조건

R007은 R006과 기존 review/history를 수정하지 않고 add-only successor로
다음을 모두 닫아야 한다.

```text
finalization-grant-wrapper → execution-consume-intent direct edge = exact 1
execution consume finalization-grant named binding = exact
terminal-wrapper-recovery-grant-wrapper
  → finalization-consume-intent direct edge = exact 1
finalization consume wrapper-recovery-grant named binding = exact
post-G7 full projection pair in finalization literal write set = exact 2 roles
allowlist-only post-G7 authority substitution accepted = 0
CRASH signed source artifact/path/schema/publisher/clock = exact 1
CRASH source → recovery consume/selector direct binding = exact
wrapper publication/revoke final selection linearization source = exact 1
wrapper selection 뒤 accepted revoke/receipt/lease/write = 0
15-result recovery CAS GT016..GT030 cardinality = 15
GT016..GT045/EO branch truth table contradiction = 0
R005 union disposition = independently re-reviewed
R007 formal ROADMAP_REVIEW findings = 0/0/0
R007 skeptical ROADMAP_REVIEW findings = 0/0/0
```

R007은 exact bytes를 freeze한 뒤 서로 독립인 formal/skeptical review를 같은
SHA에 대해 받아야 한다. 어느 쪽이든 nonzero면 R007도 rejected history로
보존하고 다음 add-only revision으로 교정한다.

## 8. 최종 disposition

```text
BLOCKING=6
MAJOR=0
MINOR=0
verdict=FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR_R007
R006_ROADMAP_REVIEW_FINDINGS_ZERO=false
R005_UNION_CLOSED/PARTIAL/OPEN=13/2/0
R007_FINDINGS_CLOSED=false
PRE_P_CLOSURE_SUCCESSOR_EXISTS=false
PRE_P_SUCCESSOR_READY=false
AUTHORITY_BOUNDARY_DECISION_EXISTS=false
CONSTRUCTIVE_FIXTURE_AUTHORIZED=false
CHECKPOINT_OR_CANONICAL_WRITE_AUTHORIZED=false
PRODUCT_OR_RELEASE_AUTHORIZED=false
OFFICIAL_CREDIT_DELTA=0
```

R006는 rejected history로 immutable 보존해야 한다. 이 review는 R006 target,
checkpoint, canonical, Goal, product와 기존 receipt를 수정하지 않았으며
어떤 실행도 승인하지 않는다.
