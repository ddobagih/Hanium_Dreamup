# WalkSafe 다음 단계 상세 로드맵 20260731 R004 독립검수 R001

- 문서 ID:
  `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R004-INDEPENDENT-REVIEW-R001`
- 검토일:
  `2026-07-31`
- review subject type:
  `ROADMAP_REVIEW`
- review authority:
  `NONE / ABSENT_DENY_ALL`
- verdict:
  `FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR_R005`
- findings:
  `BLOCKING=8 / MAJOR=3 / MINOR=0`

## 1. exact target과 물리 identity

| 항목 | exact 값 |
|---|---|
| target | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R004.md` |
| SHA-256 | `7c559aab44788608079c9b9768659e227e5e66e672fedb750210b601bf6b9209` |
| bytes / lines | `160,363 / 3,910` |
| owner | `uid=1000 / gid=1000` |
| mode / type / nlink | `0664 / regular non-symlink / 1` |
| terminal LF / CR / NUL | `true / 0 / 0` |

검수 시작과 보고서 작성 직전에 위 identity를 독립 재계산했다. target은
요청받은 exact SHA와 일치했고 이 review는 target을 수정하지 않았다.

## 2. 검수 범위와 방법

다음을 source of truth로 읽기 전용 교차검증했다.

- R003 formal review exact SHA
  `0abe4ea3e84d223d08b4ea350e8f9ce3fbf31597fd2b50fd9b7ab4ab36564c91`,
  판정 `4/2/0`
- R003 skeptical review exact SHA
  `e32fab4b38c6c7ddb32a65ec51ba0f1a17f9a1199dd05f654c07d87f4fd0a65b`,
  판정 `7/7/0`
- rejected PRE-P R007 target exact SHA
  `02766312b1bbb00eb05e2789fe4d054cbf749407e6c5bd26dce62250e6dd98ef`
- R007 formal review exact SHA
  `c2e6c226204d977db9706a58935125b472c829e5790c91ef83e3c866dde51269`,
  판정 `18/4/0`
- R007 skeptical review exact SHA
  `0bc4fb67ab80acaae69ae8024b7f39156bc5c98d6f6df2e9bfa508fb2c91fe44`,
  판정 `18/4/0`

검수 축은 다음과 같다.

1. R003의 formal 6개와 skeptical 14개 required correction 전수
2. R007의 B01~B18/M01~M04 strict materialization 요구
3. publication/hash 방향, exact predecessor, authority/revocation와 deadline
4. success/failure/crash/expiry의 전체 terminal path
5. H2 binding과 actual Stage-C, H4 actual-run authority
6. task/milestone/row/debt graph, cardinality와 산술

이 review의 ceiling은 frozen roadmap 품질 판정뿐이다. finding의 교정안을
기술하는 것은 successor 작성, G0/P0, V1, H2~H5, checkpoint, canonical,
product, artifact, formal/device/Gate, production 또는 release 권한이 아니다.

## 3. 독립 기계검증

다음은 PASS했다.

```text
protected input manifest physical SHA match = 20/20
Quick2 continuation/Goal graph = PASS/PASS
B heading count = 18
M heading count = 4
finding matrix rows/unique IDs = 22/22
debt IDs = 4 unique
task rows/unique IDs = 15/15
task dependency edges = 25 unique
task DAG cycle = 0
gate task membership = 2/1/2/8/2
milestone row slots = 33
milestone objects = 29 unique
task-to-milestone edges = 29
milestone predecessor edges = 41
milestone DAG cycle = 0
row predecessor edges = 40
external predecessor edges = 12
row supporting-task edges = 45
terminal LF = true
CR/NUL = 0/0
git diff --check(target) = PASS
```

특히 다음 설계 개선은 확인됐다.

- G0 durable output `0`, transient observation과 P0 first durable record
- successor freeze 뒤 G1-SPEC을 시작하는 순서
- exact predecessor result/receipt/subject/attempt/lineage Gate schema
- 10개 SPEC/EVIDENCE Gate의 literal output allowlist
- 15-task, 29-milestone, 22-finding/4-debt 기본 산술
- B16/M04의 `M04a→B16a→M04b→B16b` 무순환 milestone DAG
- named G6 disposition, P7 class 분리, G7와 ready equality
- H2 13-role live-root manifest/publication/ProgressRef 기본 순서
- H4 안의 five Gate `must_close_before`와 authorized N/A 산술
- 공식 상태 `126/257`, open `131`, formal `0/279`, Gate `0/5`,
  release `NOT_ELIGIBLE`, official delta `0`

위 PASS 항목은 아래 findings를 상쇄하지 않는다. 특히 static task/milestone
DAG가 무순환인 것과 runtime-selected full graph를 pre-G1에서 exact
materialize할 수 있는지는 다른 조건이다.

## 4. BLOCKING findings

### R004-FORMAL-BLOCKING-001 — exact 15 result 뒤 pre-close CAS 전 cut-point를 terminal로 닫을 수 없다

R004는 non-recovery branch에 exact 15 task result 뒤 pre-close CAS를
요구한다(1300~1307, 2548~2553, 2574~2580행). 반면 recovery terminal은
observed strict prefix `0..14`만 허용한다(1308~1309, 1395, 1415~1417,
2551~2553행).

따라서 15번째 signed result가 durable해진 직후, 아직 CAS pre-close
payload를 발행하기 전에 process crash 또는 deadline expiry가 발생하면:

- normal SUCCESS/FAILURE는 필수 CAS가 없어 발행 불가
- recovery FAILURE/CRASH/EXPIRED는 prefix cardinality가 15라 발행 불가
- four terminal actual cardinality `1`도 만족 불가

Required correction:

1. recovery 관찰 result set을 `0..15`로 정의한다.
2. `prefix_count=15`일 때 CAS가 `ABSENT_BEFORE_PUBLICATION`인지 이미 exact
   존재하는지를 tagged state로 구분한다.
3. optional CAS와 terminal kind의 complete truth table을 만들고 terminal
   exactly-one을 검사한다.
4. 15-result/no-CAS crash·expiry negative fixture를 추가한다.

### R004-FORMAL-BLOCKING-002 — success CAS 뒤 intermediate failure의 durable prefix와 branch-switch DAG가 없다

success path는 finalization consume 뒤 task completion, success CAS,
milestone, row, Gate, review와 ready를 순서대로 쓴다(2671~2684행). 중간
`FAIL/NOT_RUN`도 failure tail로 보낸다고 하지만(2686~2703행), failure
path 표는 이미 소비된 `PostCloseFinalizationConsumeReceipt`를
`first failed artifact` 뒤에 다시 배치한다.

또한 edge manifest의 failure branch는 task completion→failure CAS와
failure CAS 이후 edge만 가진다(1350~1358행). 다음을 증명하는 immutable
branch-switch predecessor가 없다.

- success CAS 뒤 실제 어디까지 durable했는지
- exact first failed/non-runnable artifact가 무엇인지
- 그 앞 prefix에 missing/extra/duplicate artifact가 없는지
- finalization consume은 이미 한 번만 이루어졌는지

`V1FailureCASReceipt`의 prefix digest 문자열만으로는 first-failure artifact와
physical prefix membership을 독립 재계산할 수 없다.

Required correction:

1. finalization consume은 어떤 branch에서도 exact 한 번만 선행하게 한다.
2. success suffix 각 cut-point에 적용할 strict
   `FinalizationPrefixCheckpoint`/branch-switch receipt를 정의한다.
3. exact prefix artifact physical refs, first failure artifact/receipt,
   revocation/deadline 관찰과 missing-set을 결속한다.
4. `FIRST-FAILURE/PREFIX-CHECKPOINT → CAS-FAILURE` edge를 runtime expansion에
   추가하고 재소비 경로를 제거한다.

### R004-FORMAL-BLOCKING-003 — fixed revocation-head 두 파일로 increasing ordinal과 post-consume revocation을 발행할 수 없다

allowlist는 consume point마다
`head.payload.json/head.signature.json` 한 pair만 열고 cardinality도 한
role로 취급한다(§7). 그러나 §13.3은 predecessor head와 strictly increasing
ordinal을 요구하고, consume 뒤 더 큰 ordinal의 `REVOKED` head 관찰을
failure-close predecessor로 사용한다(2453~2481행).

add-only fixed path에 첫 `UNREVOKED` head pair를 쓴 뒤에는 더 큰 ordinal의
`REVOKED` pair를 overwrite 없이 발행할 수 없다. 반대로 첫 파일을 current
mutable head로 덮으면 add-only, signed physical identity와 predecessor
lineage를 깨뜨린다. B02의 required renewal ordinal과 legal/illegal FSM도
exact transition table이나 materialized version path가 없이 요약 문장에
머문다(1858~1869행).

Required correction:

1. ordinal별 create-exclusive literal version path와 signed immutable head
   sequence를 allowlist에 넣고 current-head CAS pointer의 별도 규칙을 둔다.
2. 또는 외부 CAS head와 immutable observation receipt를 분리하되
   pre/post-consume observation path/cardinality를 모두 열거한다.
3. original deadline, renewal formula, revoke/consume/close/failure의 exact
   legal transition table과 negative predecessor를 물리화한다.
4. later REVOKED head의 publication/adoption과 failure-close edge를 검증한다.

### R004-FORMAL-BLOCKING-004 — finalization close→terminal wrapper 사이 crash/expiry를 회복할 terminal 계약이 없다

D3와 §13.4는 selected terminal payload 뒤 finalization close를 먼저
publish하고 마지막 wrapper를 발행한다(§6.1, 2671~2684,
2742~2752행). 그런데 close 뒤 wrapper 전 crash/expiry에 대해:

- close는 이미 fixed add-only path를 점유한다.
- partial close는 ready=false로 보되 기존 close를 재사용하지 않는다고 한다.
- finalization deadline 뒤 wrapper를 발행할 별도 close-tail recovery
  authority가 없다.
- 새 whole lineage는 기존 attempt의 orphan close와 충돌한다.

따라서 two-member terminal transaction이 partial이면 그 attempt를
READY도 FAILURE도 아닌 상태로 영구 고정할 수 있다.

Required correction:

1. `FINALIZATION_TERMINAL_WRAPPER_RECOVERY_ONLY` grant/consume과 deadline을
   별도 정의한다.
2. exact existing payload+close bytes/Physical을 adopt한 뒤 missing wrapper
   한 개만 발행하는 path를 허용한다.
3. close-before-wrapper crash, deadline expiry, collision과 wrong close SHA의
   truth table을 만든다.
4. recovery 성공 뒤 full lineage/terminal exactly-one과 terminal 뒤 write
   `0`을 재검증한다.

### R004-FORMAL-BLOCKING-005 — H4 consume receipt가 자기 SHA를 자기 body에 요구한다

§17.2는 `H4ActualRunAuthorityConsumeReceipt`, result와 close receipt
“각각” 같은 identity를 직접 가진다고 하고, 그 identity에
`run_authority_consume_receipt_sha`를 포함한다(3498~3518행).

그러면 consume receipt 자체가 자기 파일 SHA를 자신의 signed body에
포함해야 한다. 이는 R004 §6.1이 금지한 self/future hash cycle이며 canonical
bytes를 구성할 수 없다.

Required correction:

1. run authority consume schema에서는 자기 receipt SHA를 제거한다.
2. consume은 authority payload/receipt, nonce, scope, time과 atomic dispatch
   start를 결속한다.
3. result와 close만 existing consume receipt SHA를 inward-reference한다.
4. 세 artifact의 schema를 같은 field 목록이 아닌 순방향 tagged union으로
   분리하고 self-reference negative fixture를 둔다.

### R004-FORMAL-BLOCKING-006 — pre-G1 frozen graph가 runtime branch와 관찰 prefix를 선결정해야 한다

`ClosureDependencyEdgeManifest`는 successor receipt 뒤, G1-SPEC 전에
payload/receipt와 expanded cardinality/digest를 동결한다(§3.2,
§8.3). 그러나 같은 manifest의 ET/EO/GT/FT/FE edge actual cardinality는
미래 V1 terminal, observed result prefix와 intermediate failure에 따라
달라진다(1298~1358, 1415~1425행).

특히 문서는 runtime `selected_branch = SUCCESS XOR FAILURE`를
“manifest result”에 기록한다고 하지만(1421~1425행), allowlist와 schema에는
별도 manifest-result artifact가 없다. pre-G1 payload는 미래 terminal과
prefix를 알 수 없고, 나중에 바꿀 수도 없다.

Required correction:

1. pre-G1에는 branch-independent static definition/union graph와 expansion
   algorithm/digest만 동결한다.
2. execution terminal 뒤 별도 add-only
   `ClosureDependencyExpansionResultPayload/Receipt`를 정의한다.
3. result가 terminal kind, observed prefix, first failure, concrete
   node/edge set/cardinality/digest를 결속하게 한다.
4. G3-EVIDENCE/G6는 static definition digest와 post-runtime expansion
   receipt를 각각 named field로 비교한다.

### R004-FORMAL-BLOCKING-007 — B04의 Stage-C/recovery receipt exact extension이 materialize되지 않았다

R007 B04는 Stage-C/recovery tagged receipt가 N26/T1/X1/V1, capability rows,
executor/predecessor와 byte-equal `ApplicationReceiptTargetSpec`을 직접
가질 것을 요구한다. R004 B04는 이 이름을 한 줄로 나열할 뿐이다
(1885~1901행).

§9.3의 13-role common binding과 `StageCApplicationReceipt` 경로/schema-role도
N26/T1/X1/V1, target-spec canonical bytes/hash, executor phase와 recovery
branch별 required field를 정의하지 않는다. “binding”이라는 prose는 strict
tagged receipt bytes를 대신하지 못한다.

Required correction:

1. constructive fixture와 actual C/recovery의 disjoint strict tagged schema를
   각각 정의한다.
2. N26/T1/X1/V1 exact payload/receipt SHA, capability row set, predecessor,
   executor phase와 target spec canonical bytes/hash를 required field로 둔다.
3. target spec byte equality와 branch-disjointness verifier를 named
   producer/path/schema/publisher와 함께 allowlist에 추가한다.

### R004-FORMAL-BLOCKING-008 — B06의 N26/T1/X1/StageCReviewBinding/V1 canonical bytes를 구성할 수 없다

R007 B06는 T1/X1과 `StageCReviewBinding`의 strict payload/type/path/publisher,
domain-separated hash/signature 공식과 inward-only V1 topology를 요구한다.
R004 B06은 order와 두 producer가 필요하다고만 적고
(1917~1930행), 어느 artifact에도 다음을 주지 않는다.

- literal canonical path
- strict payload와 detached wrapper schema
- publisher actor/Physical
- N26/T1/X1/V1 domain-separated hash input bytes
- `StageCReviewBinding` publication receipt와 V1 inward-reference fields

generic bundle path나 milestone 이름은 이 값들의 physical materialization이
아니다.

Required correction: N26, T1, X1, `StageCReviewBinding`, V1 각각의 exact
tagged schema/path/publisher/domain/hash 공식을 정의하고, payload→wrapper와
`N26→T1/X1→ReviewBinding→V1`의 concrete artifact edge를 single normative
manifest에 넣는다.

## 5. MAJOR findings

### R004-FORMAL-MAJOR-001 — terminal kind와 generic PASS status의 exact enum mapping이 없다

일반 predecessor 계약은 `expected_status=PASS`를 요구한다(§6.2~§6.4).
반면 V1 execution terminal은 `SUCCESS/FAILURE/CRASH/EXPIRED` role이고
post-close grant는 `role/path/status` tagged union이라고 선언한 뒤 네 role
이름만 열거한다(2546~2563, 2593~2604행).

다음이 정의되지 않았다.

- terminal kind별 canonical `status` 값
- FAILURE/CRASH/EXPIRED가 finalization consume에는 valid predecessor지만
  Gate PASS에는 invalid인 typed predicate
- generic PASS edge가 terminal role을 잘못 비교하지 않는 variant mapping

Required correction: `ExecutionTerminalState` strict tagged enum과
`eligible_for_finalization`, `eligible_for_success_suffix`,
`eligible_for_failure_tail` predicate를 kind별로 정의하고 generic Gate
status와 서로 변환하지 못하게 한다.

### R004-FORMAL-MAJOR-002 — H2 binding payload가 실제 Stage-C consume SHA와 consume time을 결속하지 않는다

bootstrap DAG는 `StageCAuthorityConsumeReceipt →
H2LiteralRootBindingPayload`를 요구한다(1677~1681행). 그러나 binding payload
schema에는 approval, attempt/nonce와 frozen time만 있고 consume receipt SHA,
consume time, consume revocation-head/deadline 상태가 없다(1602~1629행).
consume SHA는 뒤 wrapper에만 나타난다(1653~1671행).

따라서 payload 자체는 같은 approval/nonce의 다른 consume 관찰과 구별되지
않으며 DAG의 direct edge를 payload canonical bytes에서 재계산할 수 없다.

Required correction: binding payload에 exact Stage-C consume receipt SHA,
consume time, current revocation head/ordinal과 hard deadline을 넣고
`approval <= consume <= binding frozen <= deadline`을 검증한다. wrapper는
그 payload와 동일한 consume lineage를 재확인한다.

### R004-FORMAL-MAJOR-003 — M02가 요구하는 exhaustive late-bound role enum이 실제로 열거되지 않았다

R007 M02는 모든 future Stage-C invocation-data role을 typed late-bound
enum으로 열거하고 각 allowed phase/constructor를 고정할 것을 요구한다.
R004 M02는 “closed enum”이 필요하다고 쓰고 Stage A allow/deny 범주만
제시한다(§11 M02). actual enum member, role별 constructor, canonical
path/schema와 exact cardinality는 없다.

이 상태에서는 future live root/transaction/exact26/U1/RuntimeActual/
repository inventory 중 누락된 role을 exhaustive checker가 발견할 expected
set 자체가 없다.

Required correction: 모든 late-bound role ID를 literal closed set으로
열거하고 role별 allowed phase, constructor Physical/SHA, output type/path/
schema와 wrong-phase predicate를 freeze한다.

## 6. R003 required-correction 재판정

### 6.1 formal `4 BLOCKING / 2 MAJOR`

| R003 formal finding | R004 재판정 |
|---|---|
| exact predecessor PASS binding | 기본 Gate schema는 교정됐으나 runtime graph selection은 `R004-FORMAL-BLOCKING-006` |
| ten Gate output allowlist | 기본 role은 교정됐으나 revocation/failure terminal role은 `BLOCKING-003/004` |
| named G6/G7/ready binding | success path named binding은 교정; intermediate failure closure는 `BLOCKING-002` |
| immutable user provenance/fresh retry | 기본 provenance는 교정; partial finalization을 closed lineage로 만들 수 없는 `BLOCKING-004` |
| owner/milestone G6 predicate | `CLOSED_DESIGN_IN_R004` |
| common review identity | `CLOSED_DESIGN_IN_R004` |

### 6.2 skeptical `7 BLOCKING / 7 MAJOR`

| R003 skeptical finding | R004 재판정 |
|---|---|
| G0 durable write contradiction | `CLOSED_DESIGN_IN_R004` |
| predecessor/time/publication | 기본 Gate 계약은 교정; terminal cut/close transaction은 `BLOCKING-001/004` |
| full H1 allowlist | 기본 Gate/task role은 교정; versioned revocation/expansion-result role은 `BLOCKING-003/006` |
| one-use retry | 기본 fresh lineage는 교정; orphan partial close는 `BLOCKING-004` |
| task/debt crosswalk | static 산술은 교정; runtime failure prefix는 `BLOCKING-001/002` |
| B09 actual live-root contract | 기본 13-role triple은 교정; consume binding과 C receipt extension은 `BLOCKING-007`, `MAJOR-002` |
| H4 five Gate ordering | must-close-before는 교정; actual-run consume self-hash는 `BLOCKING-005` |
| review actor identity | `CLOSED_DESIGN_IN_R004` |
| G0/R004/review freshness | `CLOSED_DESIGN_IN_R004` |
| P0 inventory/627 derivation | `CLOSED_DESIGN_IN_R004` |
| single normative graph | static source는 하나지만 runtime expansion timing은 `BLOCKING-006` |
| B01 detached wrapper | `CLOSED_DESIGN_IN_R004` |
| M04 producer/checker | `CLOSED_DESIGN_IN_R004` |
| formal 279 N/A | `CLOSED_DESIGN_IN_R004` |

## 7. R007 strict closure 재판정

R004는 B01~B18/M01~M04의 22 heading, single owner와 milestone 산술을
보존했다. 다만 heading과 completion row의 존재가 strict physical
remediation을 뜻하지는 않는다.

```text
R007 B02 = PARTIAL; BLOCKING-003
R007 B04 = PARTIAL; BLOCKING-007
R007 B06 = PARTIAL; BLOCKING-008
R007 M02 = PARTIAL; MAJOR-003
R007 remaining mapping/cardinality = structurally present
R007 finding closure receipts produced = 0
```

특히 R007 B04/B06/M02는 R004 §10/§11이 required field를 이름으로
요약했지만 exact schema/path/publisher/hash bytes를 materialize하지 않아
`CLOSED_DESIGN_IN_R004`라고 확정할 수 없다.

## 8. 최종 disposition

```text
TARGET_EXACT_SHA_REVIEWED=true
TARGET_MUTATED=false
FORMAL_ROADMAP_REVIEW=FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR_R005
BLOCKING=8
MAJOR=3
MINOR=0
ROADMAP_REVIEW_FINDINGS_ZERO=false
R004_HANDOFF_COMPLETE=false
R007_FINDINGS_CLOSED=false
PRE_P_CLOSURE_SUCCESSOR_EXISTS=false
PRE_P_SUCCESSOR_READY=false
AUTHORITY_BOUNDARY_DECISION_EXISTS=false
V1_OR_H2_TO_H5_AUTHORIZED=false
CHECKPOINT_CANONICAL_PRODUCT_RELEASE_AUTHORIZED=false
OFFICIAL_CREDIT_DELTA=0
```

다음 단계는 R004와 이 review를 history로 보존하고, 위 `8/3/0`을 닫는
add-only R005 roadmap을 작성한 뒤 그 exact SHA에 대해 formal/skeptical
`ROADMAP_REVIEW`를 처음부터 다시 수행하는 것이다. R004의 명령, schema
요약이나 allowlist 일부를 standalone authority로 실행하면 안 된다.
