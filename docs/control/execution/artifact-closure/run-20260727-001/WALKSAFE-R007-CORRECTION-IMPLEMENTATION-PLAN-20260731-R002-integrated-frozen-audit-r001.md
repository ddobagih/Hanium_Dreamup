# WALKSAFE R007 correction implementation plan R002 — integrated frozen audit R001

## 0. 문서 지위와 금지선

```text
artifact_class = INDEPENDENT_INTEGRATED_FROZEN_AUDIT_REPORT
report_status = FINAL
authority = NONE
official_progress_delta = 0
execution_authorized = false
checkpoint_mutation_authorized = false
canonical_mutation_authorized = false
product_mutation_authorized = false
release_or_production_authorized = false
target_mutation_authorized = false
```

이 보고서는 아래 exact frozen R002 bytes에 대한 add-only 독립 감사 결과다.
승인, authority grant, consume receipt, closure evidence, 실행 지시 또는 R007
successor가 아니다. 이 보고서의 생성은 대상의 `PRE_REVIEW` 지위를 올리지 않으며
official progress delta는 `0`이다.

감사 과정에서 대상, predecessor, checkpoint, canonical, product, daylog와
memory를 수정하지 않았다. 보고서 자신의 SHA는 자기참조를 피하기 위해 body에
넣지 않고 freeze 후 외부 handoff에서만 전달한다.

## 1. frozen 대상과 감사 기준점

이 보고서에서 다음 약칭을 사용한다.

```text
T = WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R002.md
A = WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001-independent-authority-review-r001.md
base = docs/control/execution/artifact-closure/run-20260727-001/
```

대상 identity는 시작과 종료에 각각 독립 재계산했다.

```text
target_start_sha256 = 5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0
target_end_sha256   = 5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0
target_bytes        = 299396
target_lf_lines     = 6338
target_start_equals_end = true
target_mutation_count = 0
```

`A`의 frozen source identity는 `T:L44`에 적힌 SHA-256
`c1098e45a4e8eaddc33ca7be6a795d8f01daa999cf3e75ff08f8a7f9d6096cf3`
이다. Finding의 `T:Lx-Ly`와 `A:Lx-Ly` 표기는 이 두 frozen 파일의 1-based
LF line을 뜻한다.

## 2. 통합 판정과 중복 병합

```text
verdict = FAIL
BLOCKING = 26
MAJOR = 7
MINOR = 0
freeze_gate_0_0_0 = FAIL
```

이 수치는 같은 결함의 반복 표현을 한 번만 세었다.

- H4 Gate endpoint 결함은 선행 broad review의 Major와 H4 전용 review의
  Blocking이 같은 `H4G001..H4G007`을 가리키므로 `B20` 한 건으로 병합했다.
- registry generation/path 충돌과 정의되지 않은 closure-batch receipt는 같은
  C1→C4 add-only chain의 두 면이므로 `B22` 한 건으로 병합했다.
- Fixture case status, execution receipt status, SelfAudit status와 completion
  admission의 자유도는 같은 비총결정 machine oracle이므로 `B25` 한 건으로
  병합했다.
- 잘못 인용된 outbox enum 후보는 §5의 provenance 확인 결과 제외했으며 어떤
  severity에도 포함하지 않았다.

## 3. BLOCKING findings

### B01 — seal obligation이 terminal recovery cut 뒤에는 실행될 수 없다

`FSM032/F`는 recovery cutoff에 도달한 뒤 새 seal obligation을 큐잉한다
(`T:L1145-L1146`, `T:L1170-L1177`). 그러나 generic claim, reclaim, publish와
repair는 모두 `terminal_recovery_not_after`보다 엄격히 이전이어야 한다
(`T:L1495-L1507`, `T:L1549-L1557`). 따라서 새 seal batch는 claim 가능한
시간이 없고, 앞선 seal 실패에도 별도 fail-close가 없다.

최소 수정: `ATTEMPT_SEAL`에 terminal batch와 분리된 timeline을 부여하고
`attempt_seal_not_after`까지 claim/reclaim/publish/repair와 seal-deadline
fail-close를 정의한다.

### B02 — lifecycle publication과 `SEALED` 전이 사이 crash가 영구 hang을 만든다

`LOBX002`는 lifecycle outbox를 `PUBLISHING→PUBLISHED`로 만들고 `LOBX009`가
별도로 lifecycle을 `SEALED`로 바꾼다(`T:L1967-L1979`). 두 연산 사이 crash
후 deadline이 지나면 pre-deadline transition만 남아 `SEAL_QUEUED/PUBLISHED`
상태를 닫을 수 없으며, 문서가 주장하는 terminal totality와 충돌한다
(`T:L2012-L2015`, `T:L2032-L2047`).

최소 수정: `LOBX002`와 lifecycle state update를 같은 CAS에 넣거나,
pre-deadline publication 증거로 deadline 뒤 store-only settlement를 허용한다.

### B03 — lifecycle timer와 claim lease의 authority contract가 없다

expiry timer registration과 lifecycle fail-close timer를 요구하지만
(`T:L1782-L1786`, `T:L1890-L1896`, `T:L1912-L1921`, `T:L1933-L1939`),
timer artifact의 role, path, schema, publisher, signed body, clock domain과 CAS
comparator가 없다. `lifecycle_claim_lease_ns`도 사용만 되고 freeze되지 않는다
(`T:L1967`, `T:L2016-L2020`).

최소 수정: timer registration/firing/expiry를 signed artifacts와 exact
CAS transition으로 닫고 `lifecycle_claim_lease_ns`의 타입과 frozen 값을
명시한다.

### B04 — preselection lifecycle이 모든 허용 입력과 시간을 닫지 못한다

request expiry와 lifecycle seal deadline의 전역 ordering이 보장되지 않는데
expiry transition은 제한된 interval만 허용한다(`T:L565-L583`,
`T:L1793-L1795`). `ALLOW_DECIDED` 뒤 activation 전 timeout도 없고 activation은
role deadline보다 앞서야 한다(`T:L276-L287`, `T:L915-L927`,
`T:L1039-L1046`). outbox fail-close는 selection 뒤에만 존재한다
(`T:L1912-L1922`, `T:L2037-L2047`).

최소 수정: request/lifecycle/role deadline ordering을 freeze하고 모든
preselection state에 finite signed timeout transition을 추가하며 ALLOW event,
activation과 seal enqueue의 atomicity를 정의한다.

### B05 — preconsume/finalization/wrapper adverse terminal source를 만들 수 없다

FSM은 phase revoke, role revoke/expiry와 crash를 terminal source로 요구한다
(`T:L1115-L1121`, `T:L1135-L1138`). 그러나 열거된 execution revoke/expiry
pair는 execution consume과 dispatch 이후에만 존재한다(`T:L1222-L1250`).
post-ALLOW preconsume abandon source도 role/phase-specific predecessor를
충분히 제공하지 않는다(`T:L2273-L2281`).

최소 수정: 각 preconsume, finalization, wrapper cut마다 exact signed
revoke/expiry/crash source pair, Role/Node와 direct edge를 정의한다.

### B06 — `FSM027` exact-existing adoption은 도달 불가하거나 seal을 중복시킨다

`FSM027`은 attempt가 `OPEN`이어야 한다(`T:L1140`). 반면 exact-existing은
동일 output의 prior `PUBLISHED`와 attestation을 요구하고 selection은 이미
aggregate state를 바꾼다(`T:L1635-L1637`, `T:L2453-L2471`,
`T:L2579-L2587`). 기존 attestation은 이전 seal obligation을 이미 포함하지만
adoption은 새 seal obligation을 만든다(`T:L1718-L1724`, `T:L2561-L2565`).

최소 수정: selection-level adoption을 제거하고 publish-once file adoption만
사용하거나 predecessor/current aggregate identity와 seal-duty transfer를
명시적으로 허가한다.

### B07 — POST_ALLOW_PRECONSUME abandoned seal digest에 입력이 없다

해당 variant body는 abandon source fields만 가진다(`T:L2273-L2281`). 그러나
strict variant는 authority aggregate를 요구하고(`T:L2284-L2293`), authority
digest constructor는 exact `terminal_evidence`를 hash한다(`T:L2336-L2348`).
이 variant에는 그 필드나 대체 tagged preimage가 없다.

최소 수정: 모든 seal variant에 대해 required/forbidden fields가 완전한 tagged
preimage와 literal digest domain을 정의한다.

### B08 — manifest payload에서 detached signature로 가는 direct edge가 없다

manifest pair schema는 signature가 payload를 결속한다고 규정한다
(`T:L2766-L2779`). 그러나 direct-edge registry에는 payload→signature edge가
없고 payload role은 다른 edge에도 나타나지 않는다(`T:L2821-L2849`).

최소 수정: stable payload→signature edge ID, exact endpoints, branch,
cardinality와 digest 반영을 추가한다.

### B09 — expansion lineage endpoint와 mandatory work-outbox가 물리화되지 않았다

`FINALIZATION-CONSUME-TRANSITION-RECEIPT`, `FINALIZATION-WORK-OBLIGATION`과
generic prefix checkpoint는 exact Role/Node/path/schema/publisher/CAS artifact로
정의되지 않았다(`T:L2829-L2831`, `T:L2847-L2849`). generic obligation과
outbox는 서로 다른 object인데(`T:L1509-L1540`) consume→work-outbox→expansion
lineage 대신 obligation endpoint만 사용한다.

최소 수정: consume receipt, work obligation, work outbox와 exact checkpoint
payload를 각각 closed primary rows로 정의하고 mandatory direct edges를 전개한다.

### B10 — PostG7 predecessor가 미래의 Ready wrapper를 요구한다

PostG7 payload가 Ready payload와 Ready wrapper의 SHA/Physical을 모두 요구한다
(`T:L2895-L2914`). 그러나 chain은 Ready payload→PostG7→signature→close로만
정의되고 Ready wrapper의 role/body/publication은 없다(`T:L2920-L2927`).
wrapper가 close 뒤 만들어져야 한다면 pre-close PostG7과 cycle이 된다.

최소 수정: PostG7 predecessor를 Ready payload로 제한하거나 pre-close
signature와 post-close wrapper를 분리해 순방향 DAG를 다시 정의한다.

### B11 — recovery suffix 뒤 application-final branch가 실행될 수 없다

recovery와 application finalization이 같은 recovery issuance pair를 사용한다
(`T:L3469-L3470`, `T:L3493-L3512`). consume key는 `RECOVERY_STAGE_C` 하나뿐이고
issuance/consume도 한 instance다(`T:L3542-L3549`, `T:L3609-L3611`). recovery
suffix는 application output을 만들지 않는다고 명시한다(`T:L3896-L3901`).
suffix가 key를 소비하면 나중 application-final issuance 경로가 없다.

최소 수정: recovery/application keys, paths와 roles를 분리하거나 한 durable
two-stage FSM에서 suffix와 final application을 모두 표현한다.

### B12 — application branch별 edge tuple을 같은 stable ID에 넣을 수 없다

APPIN rows는 branch에 따라 다른 source를 선택하지만(`T:L3803-L3812`) 같은
five stable edge IDs와 closed union을 주장한다(`T:L3855-L3879`).
`EdgeRegistryRowV2`는 한 ID에 한 source/target tuple만 허용한다
(`T:L5585-L5614`). `STAGE-C-RUNTIME-BINDING` endpoint도 primary role로
정의되지 않았다.

최소 수정: branch별 edge IDs와 full rows를 분리하고 APPIN002 endpoint의
RoleInstance/Node/path/schema/publisher를 정의한다.

### B13 — issuance의 current-head fields가 한 observation과 결속되지 않는다

issuance payload는 observation, head payload binding, head wrapper binding과
token을 별도 fields로 가진다(`T:L3478-L3481`). 하지만 정의된 primary row와
edges는 observation뿐이며(`T:L3605`, `T:L3616-L3621`, `T:L3652`, `T:L3673`),
세 값을 같은 head에서 얻었다는 equality가 없다.

최소 수정: observation을 유일한 head authority로 삼거나 별도 head roles,
direct edges와 byte-exact equality를 추가한다.

### B14 — immutable H2 guard가 reclaim 가능한 publication lease를 포함한다

generic outbox는 PENDING에서 claim 때 lease를 만들고 timeout/reclaim 때 lease를
바꾼다(`T:L1542-L1600`); settlement도 actual lease를 기록한다
(`T:L1726-L1729`). 그런데 H2 guard content identity에 lease ID/token이 들어가고
같은 batch를 crash-resume한다고 요구한다(`T:L4384-L4385`,
`T:L4407-L4413`, `T:L4424-L4429`, `T:L4494-L4498`). reclaim 뒤 guard가
필연적으로 stale해진다.

최소 수정: lease를 immutable guard에서 제거하고 claim receipt와 settlement
evidence에서만 결속한다.

### B15 — H2 guard 25개 project output의 registry identity가 없다

50 RoleTemplate rows만 열거되고(`T:L3953-L4006`) ordered batch/guard binding은
element schema가 없다(`T:L4048-L4050`). batch table은 guard filename만 가지며
role/schema/publisher/constructor/OutputEntry가 없는데 guard를 project co-output으로
센다(`T:L4323-L4352`, `T:L4401-L4405`). 이는 OutputEntry와 §13 registry
요건(`T:L1470-L1489`, `T:L5513-L5583`) 및 asserted 25 rows
(`T:L5950-L5954`, `T:L5971-L5976`)과 충돌한다.

최소 수정: 25 guard 각각의 role, OutputEntry, path, schema, publisher,
RoleInstance/Node와 expansion row를 열거한다.

### B16 — H4 grant의 control digest가 future/self reference를 요구한다

11 control roles에는 grant 자체와 모든 후행 output이 포함된다
(`T:L4818-L4828`). grant에는 future-safe specs만 넣는다고 했지만
(`T:L4848-L4850`), digest 식은 11 full resolved refs를 hash하고 grant가 그
digest를 운반한다(`T:L4870-L4873`, `T:L4907-L4909`). 미래 content
SHA/Physical 없이 이 digest를 만들 수 없다.

최소 수정: pre-publication spec digest와 post-publication resolved-ref digest를
서로 다른 이름, domain과 artifact 단계로 분리한다.

### B17 — H4 receipt chain이 mandatory predecessor Physical을 누락한다

모든 later receipt가 settled predecessor를 직접 결속해야 한다는 규칙
(`T:L4851-L4855`)과 달리 state-init, consume intent/receipt와 close receipt의
closed bodies는 여러 predecessor의 Physical 또는 predecessor 자체를 생략한다
(`T:L4917-L4919`, `T:L4931-L4944`, `T:L4942-L4954`, `T:L5002-L5005`).

최소 수정: 각 단계 body에 모든 direct predecessor의 SHA와 Physical을 함께
넣고 schema/signature/publisher coherence와 missing/wrong Physical negatives를
추가한다.

### B18 — H4 closed arrays와 digest preimage가 정의되지 않았다

scope tuple set(`T:L4778-L4781`), run `scope_digest`(`T:L4911`), raw evidence와
assertion results(`T:L4980-L4983`), Gate measurements(`T:L5088`), exact-five
Gate/edge/target digests(`T:L5187-L5194`)와 completion run/target digests
(`T:L5211-L5216`)의 element type, ordering, duplicate rule 또는 domain-separated
constructor가 없다.

최소 수정: 각 배열의 closed row type과 canonical order/duplicate rule을
정의하고 모든 digest를 literal domain, NUL과 RFC 8785 JCS preimage로 고정한다.

### B19 — H4 completion이 비교할 frozen run universe artifact가 없다

모든 run ID가 activation 전에 frozen이라고만 선언한다(`T:L4686-L4687`).
completion은 자신이 선택한 `ordered_required_run_close_refs[]`와 count/digest만
가지며(`T:L5208-L5211`) “all required runs”의 외부 비교 대상이 없다
(`T:L5223-L5226`). subset이 자기 count/digest와 일치해 PASS할 수 있다.

최소 수정: signed `H4ActivationV2`에 ordered run descriptors/count/digest를
freeze하고 scope authority, grants와 completion이 그 SHA/Physical 및 같은
run-set digest를 직접 결속하게 한다.

### B20 — `H4G001..H4G007`은 Edge registry로 물리화할 수 없다

H4 표는 source를 conceptual “Gate receipt PASS”로, target을 target ID로만 둔다
(`T:L5127-L5137`). closure rows도 node/role endpoints와 typed branch predicate가
없다(`T:L5188-L5189`). 하지만 `EdgeRegistryRowV2`는 source/target Node,
RoleInstance와 `branch_ast`를 모두 요구하고(`T:L5585-L5607`), closed AST에는
`PASS && waived=false` predicate가 없다(`T:L5651-L5664`).

최소 수정: Gate raw/result/receipt와 target의 exact RoleInstance/Node rows,
일곱 full Edge rows를 전개하고 typed PASS/nonwaived predicate를 추가하거나
PASS-only CAS transition Node를 둔다.

### B21 — reviewer result가 CAS 없이 same-store state transition을 주장한다

`H4RunResultReceiptV2`는 `DISPATCHED→RESULT_RECORDED`와 state tokens를
주장하지만 store ID, record Physical 또는 CAS-service attestation이 없다
(`T:L4971-L4987`). result publisher는 CAS-service의 명시적 예외다
(`T:L5022-L5024`), 그런데 same-store FSM은 이 transition을 close의 필수
전제로 둔다(`T:L5039-L5048`).

최소 수정: reviewer-signed evidence와 CAS-service atomic state receipt를
분리하고 후자가 state token/store Physical을 commit·서명하도록 한다.

### B22 — C1→C4 registry generation과 closure receipt chain이 add-only가 아니다

C1 전에 seven registries를 freeze하면서 C1→C4마다 completion rows를 추가해야
한다(`T:L5321-L5332`). 그러나 registry paths는 generation/batch 구분 없는 고정
14개이고(`T:L5372-L5390`) `registry_version=2`도 schema 상수일 뿐이다
(`T:L5402-L5404`). predecessor는 무내용 `GENESIS_C1` 또는 임의 SHA/Physical뿐이며
typed batch receipt가 없다(`T:L5912-L5914`); completion row는 evidence 뒤에야
생긴다(`T:L5931-L5933`). overwrite 없이 순차 chain을 만들 수 없다.

최소 수정: schema version과 별도 generation ID/ordinal/prior generation을
정의하고 create-only versioned paths를 사용한다. signed
`PreparationClosureReceiptV2`와 `ClosureBatchReceiptV2`에 prior receipt,
exact finding set/count/digest, PASS completion rows, implementation commit과
registry publication bundle ref를 넣는다.

### B23 — registry consumers가 mandatory publication triplet을 누락한다

모든 consumer는 payload SHA/Physical, signature SHA/Physical과 publication
binding SHA/CasRecordPhysical을 결속해야 한다(`T:L5472-L5474`). 하지만
`FixtureExecutionReceiptV2`, `FindingCompletionRegistryRowV2`와
`SelfAuditPredicateResultV2`는 bare digest 또는 일부 payload ref만 가진다
(`T:L5841-L5854`, `T:L5902-L5911`, `T:L6092-L6100`). exact closed schemas라
암묵적 보완도 불가능하다.

최소 수정: closed `RegistryPublicationRefV2` triplet을 정의하고 세 consumer의
모든 registry predecessor를 이 타입으로 교체한다.

### B24 — required positive fixture case ID 24개가 존재하지 않는다

Fixture row는 `positive_case_id`를 필수로 요구한다(`T:L5813`). §14는 positive와
negative ID가 literal이라고 선언하지만 table header와 24 rows에는 positive ID
열이 없고 설명문만 있다(`T:L5980-L6009`). 따라서 execution receipt의
`case_id`와 결정론적으로 join할 값이 없다.

최소 수정: 24개의 unique literal positive-case ID 열을 추가하고 registry row와
result의 byte-exact ID equality를 규정한다.

### B25 — Fixture, SelfAudit와 FindingCompletion의 PASS oracle이 총결정식이 아니다

fixture에는 하나의 branch/cardinality expectation만 있고 case result에는
assertion rows나 status reduction이 없다(`T:L5772-L5802`, `T:L5813-L5822`).
execution receipt는 actual branch/recomputed cardinality와 exact case set 없이
자유 `result_status`를 가진다(`T:L5855-L5864`, `T:L6011-L6012`). SelfAudit의
recomputation/assertion digests와 result status도 constructor/iff 식이 없다
(`T:L6101-L6111`), prose를 oracle로 읽을 수도 없다(`T:L6134-L6137`).
Completion은 자유 `FAIL` row와 일방향 PASS 필요조건만 두며
(`T:L5918`, `T:L5932-L5937`) global assertion은 cardinality만 확인한다
(`T:L6207`). nested failure, case 누락 또는 result 순열 교환에도 PASS가 가능하다.

최소 수정: closed input-manifest/scenario/assertion row schemas, actual branch와
recomputed cardinality, exact case IDs/order/count, 모든 digest domain/preimage를
정의한다. 각 level을 `PASS iff` 완전식으로 닫고 FAIL evidence는 completion
registry와 분리한다.

### B26 — `MV005 malformed=0`이 현재 bytes에서 거짓이다

raw code-span `S|N` 때문에 six-column FSM table의 `T:L1142-L1143`은 각각
seven cells로, five-column variant table의 `T:L2288-L2289`, `T:L2293`은 각각
six cells로 parse된다. 따라서 malformed row는 정확히 `5`이며 `MV005`의
expected `0`(`T:L6229`)과 충돌한다.

최소 수정: 다섯 internal pipe를 escaped pipe 또는 pipe 없는 closed enum 표기로
교체하고 같은 CommonMark table parser로 `malformed=0`을 재검증한다.

## 4. MAJOR findings

### M01 — DENY context가 존재하지 않아야 할 four role deadlines를 강제한다

`DecisionBoundContext`는 `role_deadlines`를 무조건 포함하고
(`T:L629-L650`) `RoleDeadlineSet`은 exact four다(`T:L715-L757`). DENY는
non-ALLOW branch이고(`T:L962-L964`) 문서도 decision role deadline이 없다고
말한다(`T:L2044-L2046`).

최소 수정: context를 tagged `ALLOW_WITH_ROLE_DEADLINES`와
`NONALLOW_NOT_APPLICABLE` union으로 분리한다.

### M02 — `CauseEnumV2`와 branch별 허용 cause가 닫히지 않았다

`CauseEnumV2`는 사용 위치 외 정의가 없다(`T:L1431-L1434`). execution cause
목록은 settlement timeout을 포함하지 않지만(`T:L1200-L1211`) wrapper adverse
목록에는 포함된다(`T:L2481-L2491`). 정확한 전체 enum과 selector별 subset을
재생성할 수 없다.

최소 수정: 한 closed cause enum과 각 phase/selector가 허용하는 tagged subset,
priority와 required evidence를 열거한다.

### M03 — §5 graph digest constructor가 정의되지 않았다

manifest node/edge, expansion/prefix, PostG7 actual/projected와 G3 recomputation
digests를 요구하지만 row schema, canonical order, domain과 preimage가 없다
(`T:L2773-L2776`, `T:L2794-L2798`, `T:L2908-L2910`,
`T:L2953-L2964`, `T:L3005-L3013`).

최소 수정: 각 digest마다 closed row type, ordering/duplicate rule과 literal
domain, NUL, RFC 8785 JCS preimage를 고정한다.

### M04 — B04 graph/count digest constructor가 정의되지 않았다

B04의 branch/cardinality와 input/edge digests는 이름과 equality만 있고 exact
constructor가 없다(`T:L3351`, `T:L3364`, `T:L3374-L3376`,
`T:L3395-L3398`).

최소 수정: source rows와 normalized branch를 포함하는 closed digest preimage,
domain과 deterministic order를 정의한다.

### M05 — `EP004`와 `G3E002`가 같은 direct tuple을 중복 등록한다

두 edge는 같은 endpoints를 가리킨다(`T:L2827-L2828`, `T:L3026-L3027`).
`EdgeRegistryRowV2`는 two IDs for one resolved tuple를 거부한다
(`T:L5609-L5614`).

최소 수정: 한 canonical direct edge만 유지해 양쪽 count가 이를 참조하게 하거나
실제로 다른 Node endpoints를 정의한다.

### M06 — M02 19-row registry와 네 endpoint를 재생성할 수 없다

closed `M02LateBoundRoleV2`는 constructor actor/path/SHA, argv, schema SHA,
carrier pointer와 wrong-phase predicate 등을 요구한다(`T:L4520-L4543`). 그러나
19-row 표는 이 필드를 제공하지 않으면서 표에서 full rows를 regenerate한다고
주장한다(`T:L4545-L4565`, `T:L4580-L4582`). `M02X001..M02X004`의 registry와
verification payload/signature endpoints도 primary role/path/schema/publisher로
정의되지 않았다(`T:L4608-L4611`).

최소 수정: 19개의 모든 required literal field와 closed selector union을 표에
전개하고 네 artifact endpoint의 exact RoleInstance/Node rows를 추가한다.

### M07 — H4 row bytes가 `AuthorityPlaneSpecV2`와 같을 수 없다

H4 spec/ref는 `ordinal,role_id` shape를 사용한다(`T:L4717-L4724`,
`T:L4833-L4845`). generic authority spec은 `role_instance_id,branch_ast`를
요구하고(`T:L5722-L5729`) digest도 full generic spec bytes에서 계산한다
(`T:L5760-L5765`). 그런데 H4 authority-scope registry는 같은 spec/ref bytes를
운반한다고 하고(`T:L4853-L4854`) M001 fixture도 byte equality를 요구한다
(`T:L6006`).

최소 수정: H4와 generic authority registry를 하나의 exact row shape로
normalize하고 missing branch, role-instance와 authority fields를 채운다.

## 5. 제외한 false enum finding과 provenance

한 audit 후보는 frozen source가
`OutboxBatchStateV2 = PENDING|PUBLISHING|SETTLED|FAILED`를 요구한다고 전제했다.
그 전제는 false이므로 finding 전체를 severity count에서 제외했다.

실제 source `A:L293-L312`는 role `SETTLED`와 outbox batch state를 분리하는
correction이고, `A:L307`의 exact enum은 다음 다섯 값이다.

```text
PENDING | CLAIMED | PUBLISHED | ATTESTED | EXPIRED
```

`A:L309-L312`는 work publication 동안 role을 `CONSUMED_OPEN`으로 유지하고
terminal batch attestation 때만 role을 `SETTLED`로 바꾸라고 요구한다.
따라서 위 잘못된 four-state provenance를 근거로 한 finding은 채택할 수 없다.
이 제외는 `T:L1542-L1543`의 대체 enum을 별도로 승인한다는 뜻이 아니며, 이
통합 count의 어떤 결함도 false provenance에 의존하지 않는다.

## 6. mechanical validation

### 6.1 PASS

- strict UTF-8 decode: `PASS`
- CR bytes / NUL bytes: `0 / 0`
- terminal LF: 정확히 `1`
- target SHA/bytes/LF lines: 시작과 종료 모두
  `5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0 / 299396 / 6338`
- §1 frozen source identity triples: `7/7 exact`
- inherited canonical findings: `24`
- source finding refs: `28/28`, unmapped `0`
- R001 plan-review headings: `49/49`, duplicate `0`
- fixture registry rows: `24`
- trace rows: `24`
- self-audit assertions: `59`
- static path tokens: exact nine-token set, invalid `0`
- unmatched fenced blocks: `0`
- unresolved cut placeholder: `0`

### 6.2 FAIL

- CommonMark table count는 `55`다. 별도 근거 없는 `56`을 사용하지 않는다.
- malformed rows는 `5`다: `T:L1142-L1143`, `T:L2288-L2289`, `T:L2293`.
- 따라서 `MV005 malformed=0`(`T:L6229`)은 FAIL이며 `B26`으로 집계했다.

## 7. 최종 disposition

```text
integrated_verdict = FAIL
integrated_findings = 26 BLOCKING / 7 MAJOR / 0 MINOR
target_start_equals_end = true
target_mutation_count = 0
authority = NONE
official_progress_delta = 0
R002_PRE_REVIEW_promotion = forbidden
R007_authoring_input_gate = not_satisfied
```

R002를 authoring input으로 사용하려면 B01–B26과 M01–M07을 add-only successor에서
모두 수정하고, 새 exact bytes에 대해 source identity, registry generation,
fixture oracle, CommonMark table과 independent `0/0/0` freeze gate를 다시
검증해야 한다.
