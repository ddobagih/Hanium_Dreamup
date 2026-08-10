# WalkSafe 다음 단계 상세 로드맵 20260731 R004 독립 공격검수 R001

- 문서 ID:
  `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R004-INDEPENDENT-SKEPTICAL-REVIEW-R001`
- 검토일:
  `2026-07-31`
- review subject type:
  `ROADMAP_REVIEW`
- review authority:
  `NONE / ABSENT_DENY_ALL`
- verdict:
  `FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR_R005`
- findings:
  `BLOCKING=10 / MAJOR=3 / MINOR=0`

## 1. exact target과 검수 ceiling

| 항목 | exact 값 |
|---|---|
| target | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R004.md` |
| SHA-256 | `7c559aab44788608079c9b9768659e227e5e66e672fedb750210b601bf6b9209` |
| bytes / lines | `160,363 / 3,910` |
| mode / type / nlink | `0664 / regular non-symlink / 1` |
| terminal LF / CR / NUL | `true / 0 / 0` |
| target declared status | `NONCANONICAL_PLAN_ONLY / NOT_EXECUTABLE / ROADMAP_REVIEW_PENDING` |

검수 시작과 종료 직전에 target identity를 재계산했다. 이 review는 target,
checkpoint, canonical, product, daylog와 memory를 수정하지 않았다.

검수 범위는 악의적 실행자, crash/expiry cut-point, replay, scope widening,
edge alias, status mismatch, authority bypass, H2 root binding과 H4 device
scope/run lineage다. R003 formal `4/2/0`, R003 skeptical `7/7/0`, rejected
R007와 그 formal/skeptical `18/4/0`도 읽기 전용 교차검증했다.

이 판정은 frozen roadmap의 구성 가능성만 다룬다. 어떤 finding의 교정안도
G0/P0, successor, V1, H2~H5, checkpoint, canonical, product, formal/device/
Gate, production 또는 release 권한을 만들지 않는다.

## 2. 기계검증과 확인된 개선

다음 정적 조건은 PASS했다.

```text
B/M heading = 18/4
finding rows / unique IDs = 22/22
debt IDs = 4 unique
task IDs / TD edges / cycle = 15/25/0
gate task membership = 2/1/2/8/2
milestone slots / unique objects = 33/29
task-to-milestone / milestone-predecessor edges = 29/41
milestone cycle = 0
row/external/supporting-task edges = 40/12/45
CR/NUL = 0/0
terminal LF = true
```

G0 durable output `0`, P0 first durable record, Gate predecessor bindings,
10개 SPEC/EVIDENCE allowlist, 15-task 기본 산술, B16/M04 정적 DAG, named
G6/P7/G7/ready와 H4 `must_close_before`도 설계상 확인했다. 그러나 정적
cardinality가 맞는 것은 아래 runtime cut-point와 publication 문제를 닫지
않는다.

동일 SHA의 formal review
`fdac0201acc412e9251bcfc7fa60c6c7dd3ae5a8dcf5f10645cfdf8ce6c1b448`
(`8B/3M`)과 최종 대조했다. 아래 B001~B008과 M001~M003은 formal finding과
독립적으로 같은 결론에 도달한 중복 확인이고, B009~B010은 이 공격검수에서
추가로 분리한 독립 blocker다.

## 3. BLOCKING findings

### R004-SK-BLOCKING-001 — 15번째 result 뒤 terminal 전 cut-point가 닫히지 않는다

normal branch는 exact 15 result 뒤 pre-close CAS를 요구하지만
(1300~1307, 2574~2580행), recovery FAILURE/CRASH/EXPIRED는 observed prefix
`0..14`와 CAS `NA`만 허용한다(1308~1309, 1415~1417, 2548~2553행).

따라서 15번째 result 뒤 CAS 전 crash, 또는 CAS payload 뒤 terminal wrapper
전 crash는 normal/recovery 어느 branch에도 속하지 않아 terminal cardinality
`1`을 만들 수 없다.

교정: observed prefix를 `0..15`로 열고, `CAS_ABSENT`,
`CAS_PRESENT_UNWRAPPED`, `CAS_WRAPPED`를 strict tagged state로 나눈 complete
truth table과 cut-point fixture를 추가해야 한다.

### R004-SK-BLOCKING-002 — success CAS 뒤 intermediate failure를 failure tail로 전환할 수 없다

success path는 final CAS 뒤 milestone/row/Gate/review를 발행한다
(2671~2684행). 중간 FAIL/NOT_RUN을 failure tail로 보낸다고 하지만
(2686~2703행), 그 tail은 `V1FailureCASReceipt`를 요구하고 success/failure
CAS 동시 존재를 금지한다(2705~2709행). exact edge manifest도
task-completion→failure-CAS만 있고 first failed success-prefix artifact에서
failure checkpoint로 가는 edge가 없다(1350~1358행).

교정: 기존 success CAS를 부정하지 않는 별도
`FinalizationPrefixFailureCheckpoint`를 두고 exact durable prefix,
first-failure artifact, revocation/deadline과 branch-switch edge를 결속해야
한다. finalization consume은 재소비하지 않고 항상 한 번만 선행해야 한다.

### R004-SK-BLOCKING-003 — fixed revocation-head path로 increasing ordinal을 add-only 발행할 수 없다

allowlist는 세 consume role마다 고정
`head.payload.json/head.signature.json` 한 pair만 허용한다(815~817, 905행).
반면 revocation head는 predecessor와 strictly increasing ordinal을 요구하고,
consume 뒤 더 큰 `REVOKED` ordinal도 관찰해야 한다(2453~2481행).

첫 head 뒤 같은 path를 덮으면 add-only/physical identity를 깨고, 덮지 않으면
later head를 발행할 수 없다. 따라서 R007 B02의 revocation FSM도 실제로
닫히지 않았다.

교정: ordinal별 create-exclusive literal version path와 immutable sequence,
current-head CAS/observation receipt, pre/post-consume publication cardinality와
exact legal transition table을 정의해야 한다.

### R004-SK-BLOCKING-004 — finalization close와 terminal wrapper 사이 crash를 회복할 수 없다

D3는 terminal payload→finalization close→terminal wrapper 순서다
(516~529, 2742~2752행). close 뒤 wrapper 전 crash이면 fixed close path는
이미 점유되지만 문서는 partial close를 재사용하지 않으며, missing wrapper
하나만 발행할 별도 close-tail recovery authority/deadline도 없다.

교정: exact payload+close Physical을 adopt하고 missing wrapper만
create-exclusive 발행하는 `FINALIZATION_TERMINAL_WRAPPER_RECOVERY_ONLY`
grant/consume, cut-point truth table과 terminal exactly-one 검증이 필요하다.

### R004-SK-BLOCKING-005 — H4 run consume receipt가 자기 SHA를 요구한다

`H4ActualRunAuthorityConsumeReceipt`, result와 close가 “각각” 같은 identity를
가지며 그 identity에 `run_authority_consume_receipt_sha`가 들어간다
(3498~3518행). consume body가 자기 파일 SHA를 포함해야 하므로 canonical
bytes를 구성할 수 없고 §6.1의 self-reference 금지와 충돌한다.

교정: consume schema는 authority payload/receipt, scope, nonce와 time만
결속하고, result/close schema만 이미 존재하는 consume SHA를
inward-reference하도록 세 tagged schema를 분리해야 한다.

### R004-SK-BLOCKING-006 — pre-G1 graph가 미래 runtime branch를 선결정한다

유일한 runtime dependency manifest는 successor 직후, G1-SPEC 전에
cardinality/digest까지 동결된다(1121~1150, 1275~1299행). 그러나 같은
manifest의 ET/EO/GT/FT/FE actual edge 수와 `selected_branch`는 미래 terminal,
observed prefix와 intermediate failure에 따라 정해진다
(1300~1358, 1394~1425행).

교정: pre-G1에는 branch-independent static union/algorithm만 freeze하고,
terminal 뒤 별도 signed `ClosureDependencyExpansionResult`가 concrete
branch/prefix/node/edge cardinality와 digest를 결속하게 해야 한다.

### R004-SK-BLOCKING-007 — R007 B04 C/recovery tagged receipt bytes가 없다

B04는 N26/T1/X1/V1, capability rows, target spec과 predecessor를 “binding”
한다고 요약할 뿐이다(1885~1901행). H2 13-role common schema에도 이 exact
payload/receipt SHA, byte-equal `ApplicationReceiptTargetSpec`, executor
phase와 recovery branch별 required field가 없다.

교정: constructive와 actual C/recovery를 분리한 strict tagged schema에 위
exact fields와 canonical target bytes/hash, path/schema/publisher와
branch-disjoint verifier를 직접 materialize해야 한다.

### R004-SK-BLOCKING-008 — R007 B06 N26/T1/X1/ReviewBinding/V1을 구성할 physical 계약이 없다

B06은 inward order와 producer 필요성만 적는다(1917~1930행). literal path,
strict payload/wrapper schema, publisher actor/Physical, domain-separated hash
bytes, publication receipt와 concrete artifact edge가 없어 R007 B06을
재현 가능하게 닫지 못한다.

교정: 다섯 object의 exact path/schema/publisher/domain/hash 공식과
payload→wrapper, `N26→T1/X1→StageCReviewBinding→V1` concrete edges를
정의해야 한다.

### R004-SK-BLOCKING-009 — fresh whole retry가 fixed r001 path와 충돌한다

`PRE_SUCCESSOR_ROOT`와 `H1_ROOT`는 단일 `...-r001` literal로 고정되고
(736~750행), request/decision/consume/task/result/CAS/Gate/ready path와
cardinality도 단일 instance다(772~924행). 그런데 failure/crash/timeout/
revocation 뒤 fresh P0/G0, reviews, request/response, decision, task specs,
results와 close를 모두 새로 쓰라고 한다(2760~2792행).

첫 attempt의 immutable add-only files가 경로를 점유한 뒤에는 overwrite
없이 두 번째 fresh lineage를 발행할 곳이 없다. stale path를 재사용하면
replay 금지를 깨고, 새 임의 path는 allowlist 위반이다.

교정: retry마다 decision 전에 동결되는 새 literal attempt/revision root와
그 exact allowlist를 정의하거나, fresh successor revision이 새 H1 root를
발행하는 one-way 전환 계약을 두어야 한다. 이전 root는 immutable history로
남겨야 한다.

### R004-SK-BLOCKING-010 — EXPIRED terminal과 execution deadline 식이 양립하지 않는다

공통 V1 timeline은 `execution_close_at <= execution_hard_deadline`을
강제한다(665~679행). 반면 EXPIRED는 execution 또는 close-recovery deadline이
“도달”한 뒤 recovery consume으로 발행한다(2510~2544, 2548~2563행).
execution deadline expiry 뒤 실제 recovery close는 일반적으로 그 deadline
보다 늦고, close-recovery deadline 자체가 만료된 뒤에는 그 권한으로 새
terminal을 쓰는 것도 허용될 수 없다.

교정: normal close와 recovery close timeline을 tagged union으로 분리하고,
`cause_observed_at`, recovery consume, recovery close와 각각의 hard deadline
관계를 명시해야 한다. deadline 만료 자체를 terminal 원인으로 쓸 경우
만료 전 pre-authorized watchdog/close mechanism도 필요하다.

## 4. MAJOR findings

### R004-SK-MAJOR-001 — execution terminal kind와 generic PASS status mapping이 없다

Gate predecessor는 `expected_status=PASS`를 쓰지만(575~597행), execution
terminal은 `SUCCESS/FAILURE/CRASH/EXPIRED` role이고 finalization grant/G6는
정의되지 않은 terminal `status`까지 비교한다(2546~2563, 2593~2604,
3130~3134행).

교정: strict `ExecutionTerminalState`와 kind별
`eligible_for_finalization/success_suffix/failure_tail` predicate를 정의하고
generic Gate PASS enum과 변환되지 않게 해야 한다.

### R004-SK-MAJOR-002 — H2 binding payload가 Stage-C consume lineage를 직접 결속하지 않는다

H2B003은 `StageCAuthorityConsumeReceipt → H2LiteralRootBindingPayload` edge를
요구한다(1674~1682행). 그러나 binding payload schema에는 consume SHA/time/
revocation/deadline이 없고(1602~1629행), consume은 뒤 wrapper에만 나온다
(1653~1671행).

교정: payload 자체에 exact consume receipt, consume time, current head/
ordinal, hard deadline을 넣고 approval≤consume≤freeze≤deadline을 검증해야
한다.

### R004-SK-MAJOR-003 — M02 exhaustive late-bound enum이 실제 closed set이 아니다

M02는 “closed enum”이 필요하다고만 하고 Stage A allow/deny 범주만 제시한다
(2127~2138행). actual member ID, role별 allowed phase/constructor,
path/schema와 exact cardinality가 없어 future live-root/transaction/
exact26/U1/RuntimeActual/repository role 누락을 판별할 expected set이 없다.

교정: 모든 role ID를 literal closed set으로 열거하고 role별 constructor
Physical/SHA, allowed phase, output type/path/schema와 wrong-phase predicate를
freeze해야 한다.

## 5. R003 required-correction 전수 재판정

| R003 review finding | R004 공격 재판정 |
|---|---|
| formal B001 predecessor binding | 기본 schema는 개선; runtime expansion은 SK-B006 |
| formal B002 ten Gate allowlist | 기본 role은 개선; revocation/retry/recovery role은 SK-B003/B004/B009 |
| formal B003 G6/G7/ready named binding | success path 개선; intermediate failure는 SK-B002 |
| formal B004 user provenance/fresh lineage | 논리적 fresh 요구는 있음; 물리 path가 없는 SK-B009 |
| formal M001 owner/milestone | `CLOSED_DESIGN_IN_R004` |
| formal M002 common review identity | `CLOSED_DESIGN_IN_R004` |
| skeptical B001 G0 durable write | `CLOSED_DESIGN_IN_R004` |
| skeptical B002 predecessor/time/publication | terminal cut/deadline은 SK-B001/B004/B010 |
| skeptical B003 full allowlist | versioned head/runtime expansion/retry는 SK-B003/B006/B009 |
| skeptical B004 one-use retry | SK-B009 |
| skeptical B005 task/debt crosswalk | 정적 산술 개선; failure prefix는 SK-B001/B002 |
| skeptical B006 B09 actual root | 기본 13-role 개선; C receipt/H2 consume은 SK-B007, SK-M002 |
| skeptical B007 H4 Gate ordering | 순서 개선; actual-run self-cycle은 SK-B005 |
| skeptical M001 review identity | `CLOSED_DESIGN_IN_R004` |
| skeptical M002 G0 freshness | `CLOSED_DESIGN_IN_R004` |
| skeptical M003 P0/627 derivation | `CLOSED_DESIGN_IN_R004` |
| skeptical M004 single graph | static source는 하나; runtime timing은 SK-B006 |
| skeptical M005 B01 wrapper | `CLOSED_DESIGN_IN_R004` |
| skeptical M006 M04 producer/checker | `CLOSED_DESIGN_IN_R004` |
| skeptical M007 formal 279 N/A | `CLOSED_DESIGN_IN_R004` |

## 6. R007 `18 BLOCKING / 4 MAJOR` 전수 crosswalk

| R007 ID | R004 공격 재판정 |
|---|---|
| B01 | design mapped; actual evidence `0` |
| B02 | `PARTIAL`: versioned revocation FSM 없음, SK-B003 |
| B03 | design mapped; actual evidence `0` |
| B04 | `PARTIAL`: tagged receipt bytes 없음, SK-B007 |
| B05 | design mapped; actual evidence `0` |
| B06 | `PARTIAL`: canonical object bytes 없음, SK-B008 |
| B07 | design mapped; actual evidence `0` |
| B08 | design mapped; actual evidence `0` |
| B09 | design mapped; H2 bootstrap residual SK-M002 |
| B10 | design mapped; actual evidence `0` |
| B11 | design mapped; actual evidence `0` |
| B12 | design mapped; actual evidence `0` |
| B13 | design mapped; actual evidence `0` |
| B14 | design mapped; actual evidence `0` |
| B15 | design mapped; actual evidence `0` |
| B16 | design mapped; actual evidence `0` |
| B17 | design mapped; actual evidence `0` |
| B18 | design mapped; actual evidence `0` |
| M01 | design mapped; actual evidence `0` |
| M02 | `PARTIAL`: exhaustive closed enum 없음, SK-M003 |
| M03 | design mapped; recovery terminal residual SK-B001/B004/B010 |
| M04 | static design mapped; runtime graph residual SK-B006 |

즉 22개 heading/owner/milestone mapping은 존재하지만 R007 finding closure
receipt는 `0`이고, B02/B04/B06/M02는 design-level strict materialization도
완료되지 않았다.

## 7. 최종 disposition

```text
TARGET_EXACT_SHA_REVIEWED=true
TARGET_MUTATED=false
SKEPTICAL_ROADMAP_REVIEW=FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR_R005
BLOCKING=10
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

다음 단계는 R004와 두 review를 rejected history로 보존하고, formal
`8/3/0`과 skeptical `10/3/0`의 합집합을 닫는 add-only R005를 작성한 뒤
그 exact SHA를 처음부터 다시 이중 검수하는 것이다.
