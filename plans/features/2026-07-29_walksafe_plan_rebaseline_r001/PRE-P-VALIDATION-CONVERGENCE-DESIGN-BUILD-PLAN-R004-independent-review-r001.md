# PRE-P Validation Convergence Design/Build Plan R004 독립검수 R001

## 1. 검수 대상과 판정

| 항목 | exact 값 |
|---|---|
| review_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R004-INDEPENDENT-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R004.md` |
| target SHA-256 | `f8392f525df655d519b39be9cc08557bd0b817975235cf26a41eadf541eb6bf4` |
| target bytes | `45,964` |
| target lines | `928` |
| target type | `regular file, non-symlink, nlink=1` |
| target status | `NON_EFFECTIVE_PLAN_ONLY` |
| verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY` |
| findings | `BLOCKING=1 / MAJOR=2 / MINOR=1` |
| review authority | `NONE` |

검수 시작과 종료 시 target의 SHA-256, bytes와 lines가 위 값과 같음을
확인했다. 이 review는 target을 수정하지 않으며, authority receipt 발급,
candidate/external environment build, Stage-B resolve/validation, Stage-C
transaction, checkpoint/control 전환, R007 successor 또는 P 작업 권한을
만들지 않는다.

```text
VERDICT=REJECTED_NON_EFFECTIVE_PLAN_ONLY
BLOCKING=1
MAJOR=2
MINOR=1
TARGET_UNCHANGED=true
R004_REMAINS_NON_EFFECTIVE_PLAN_ONLY=true
PLAN_EXECUTION_AUTHORIZED=false
STAGE_A_AUTHORIZED=false
STAGE_B_AUTHORIZED=false
STAGE_C_AUTHORIZED=false
STAGE_D_AUTHORIZED=false
STAGE_E_AUTHORIZED=false
STAGE_F_AUTHORIZED=false
STAGE_G_AUTHORIZED=false
AUTHORITY_JOURNAL_WRITE_AUTHORIZED=false
EXTERNAL_ENV_WRITE_AUTHORIZED=false
ACTIVE_WRITE_AUTHORIZED=false
CHECKPOINT_WRITE_AUTHORIZED=false
R007_SUCCESSOR_BUILD_AUTHORIZED=false
P17_BUILD_AUTHORIZED=false
P_APPLY_AUTHORIZED=false
```

## 2. 검수 범위

Target 전체 928행과 현재 read-only source 사실을 다음 축으로 대조했다.

- R003 independent review의 `BLOCKING=4 / MAJOR=2` remediation 종결 여부
- Stage-A candidate와 Stage-B resolved sibling namespaces 및 immutable seal
- predecessor-only regression identity와 post-lane regression-final
- single runner의 explicit root/checkpoint/selector/manifest exact two states
- 두 sealed environments의 ordered19 actual validation과 direct exact3
- authority journal의 issuance, single-use consume, lease/renewal, close와
  recovery replay
- Stage-B resolve-only authority에서 Stage-C active-apply authority로 이어지는
  scope/delegation/revocation semantics
- S0→E0→C0→T1→X1→V1→A_C→P1 hash DAG의 exact value domains와 물리
  artifacts
- Stage-C checkpoint-last, exact6 postcheck와 receipt-only recovery
- predecessor document/review binding과 claim ceiling

## 3. BLOCKING finding

### PRE-P-R004-BLOCKING-001 — AUTHORITY JOURNAL CONSUME/LEASE/CLOSE/RECOVERY REPLAY 미폐쇄

#### 근거

Target §11은 Stage A/B/C authority receipts의 nominal issue paths와 first
Stage-B renewal path를 예약하고, receipts를 signed RFC 8785 JCS,
NOREPLACE, `0444`, `one_use=true`로 정의한다. Payload에는
`expected_journal_head`, TTL, lease slice, hard deadline과 custody가 있다.

그러나 journal에서 authority를 실제로 한 번만 사용하는 state transition을
물리적으로 기록·재생하는 다음 artifacts와 operations가 없다.

- issue receipt를 어느 executor가 언제 atomic consume했는지 나타내는
  NOREPLACE consume record
- journal head의 exact physical path, before/after hash와 CAS primitive
- lease acquisition, heartbeat/renewal chain과 latest valid lease 선정 규칙
- Stage A/B/C success 또는 incident close record
- partial prefix 이후 recovery-only capability의 issue/consume/close records
- checkpoint 이후 exact6 postcheck/receipt-only recovery의 fixed paths/schema
- concurrent consumers, stale head와 replayed receipt를 거부하는 full journal
  replay algorithm

Receipt 본문은 immutable `0444`이므로 `one_use=true`만으로 consumed 상태를
표시할 수 없다. 두 executor가 같은 valid receipt와 같은
`expected_journal_head`를 동시에 읽은 뒤 서로 다른 allowed writes를 시작하지
못하게 하는 atomic consume step도 없다.

Renewal도 실행 가능성이 닫히지 않는다.

- Stage A의 TTL/lease slice는 `900`초, hard deadline은 `21600`초지만 Stage-A
  renewal path가 없다.
- Stage B는 첫 renewal path 하나만 예약하지만 hard deadline `3600`초 동안
  여러 renewal이 필요할 수 있다.
- Stage C의 TTL은 `300`초인데 transaction/recovery가 이를 넘을 경우 사용할
  renewal 또는 recovery receipt path가 없다.

§11은 Stage-A success/incident가 nonce/lease를 닫고 partial write에는 fresh
recovery-only capability가 필요하다고 선언하지만, 해당 close/recovery
artifacts는 expected paths, target universe 또는 journal schema에 없다.
§14의 same-valid-C-receipt prefix resume와 fresh recovery receipt도 각각 어느
journal state에서 허용되는지 구분되지 않는다.

따라서 current plan으로는 signed receipt가 실제로 single-use consume됐는지,
어느 renewal이 current인지, success/incident로 닫혔는지와 recovery가 원래
scope를 넘지 않았는지를 machine-checkable하게 증명할 수 없다. 이 상태에서
Stage A부터 Stage C까지 write를 허용하면 receipt replay 또는 concurrent use를
fail-closed하게 차단할 수 없다.

#### Required remediation

- Authority journal을 append-only state machine으로 정의하고 각 stage/attempt의
  exact paths를 예약한다. 최소 event kinds는 다음과 같다.

```text
ISSUED
CONSUME_CLAIMED
LEASE_ACQUIRED
LEASE_RENEWED
CLOSED_SUCCESS
CLOSED_INCIDENT
RECOVERY_ISSUED
RECOVERY_CONSUMED
RECOVERY_CLOSED
REVOKED
```

- 각 event는 monotonic journal sequence, prior head path/hash/bytes,
  receipt/transaction/nonce, executor identity, scope, timestamp/TTL,
  before/after state와 signature를 결속하고 NOREPLACE + file/parent fsync로
  publish한다.
- Exact journal-head file 또는 immutable head events를 정하고
  compare-and-swap/lock primitive로 한 executor만 `CONSUME_CLAIMED`를 얻도록
  한다. Loser는 child/write count 0이어야 한다.
- Stage A/B/C의 hard deadline까지 필요한 bounded renewal directory/member
  set과 ordering을 미리 정의한다. Renewal은 unconsumed scope 확장이나 hard
  deadline 연장을 허용하지 않는다.
- Stage success/incident close, pre-checkpoint prefix recovery와
  post-checkpoint exact6/receipt-only recovery를 서로 다른 signed capability와
  fixed paths/schema로 분리한다.
- Validator는 journal root부터 latest event까지 signatures, sequence,
  prior-head chain, single consume, lease intervals, close/revocation와 recovery
  scope를 전부 replay한다. Missing/duplicate/fork/reorder/stale-head/expired
  event는 active write 0이다.
- Concurrent consume, renewal fork, close 뒤 reuse, expired partial-prefix,
  checkpoint 전/후 recovery scope 교차와 receipt replay negative tests를
  추가한다.

## 4. MAJOR findings

### PRE-P-R004-MAJOR-001 — STAGE-B→C DELEGATION AND REVOCATION SEMANTICS 모호

#### 근거

Target §3은 Stage B를 sibling resolved root build/validation-only, active
write 0으로 제한하고 Stage C를 exact resolved active transaction authority로
분리한다. §11은 각 receipt가 다음 필드를 갖고 prior receipt chain을
결속한다고 한다.

```text
prior_receipt_path/hash/bytes
A0, R0
delegation_depth=0
revocation_state
allowed roots/operations
denied roots
```

하지만 B→C 관계가 다음 중 어느 것인지 정의하지 않는다.

1. Stage B가 Stage C에 authority를 위임하는 delegation
2. 동일 issuer가 별도 사용자 승인으로 Stage C를 fresh 발급하고 B는
   provenance/precondition으로만 참조
3. Stage-B challenge가 semantic transition을 승인하고 Stage C는 mechanical
   application만 승인하는 split authority

Delegation이라면 child Stage C가 parent Stage B의 `active write 0`보다 넓은
scope를 얻으므로 scope escalation 규칙이 필요하고
`delegation_depth=0`과 모순된다. Fresh independent authority라면
`prior_receipt`와 A0/R0가 authorization inheritance가 아니라 단순
precondition이라는 점, fresh user/issuer approval의 request/challenge가
별도로 존재한다는 점을 명시해야 한다.

다음 runtime semantics도 정의되지 않았다.

- Stage C가 발급된 뒤 Stage B expiry 또는 revocation이 C를 무효화하는지
- Stage C가 B의 unused lease/scope를 consume하는지 자기 lease를 새로 갖는지
- B renewal 이후 어느 exact receipt가 C parent/precondition인지
- B가 close-success되기 전/후 어느 시점에 C issuance가 가능한지
- `delegation_depth=0`, A0와 R0의 canonical 의미와 검증식
- C가 B의 denied roots를 상속하면서 active target write를 어떻게 허용받는지

Target은 C receipt가 physical A/B chain과 V1을 결속한다고만 한다. Chain
binding은 scope inheritance, non-inheritance, revocation propagation과
권한 상승 허용 조건을 스스로 결정하지 않는다.

#### Required remediation

- A→B와 B→C 각각을 `DELEGATION`, `FRESH_INDEPENDENT_AUTHORITY` 또는
  `SEMANTIC_APPROVAL_TO_MECHANICAL_CAPABILITY` 중 exact 하나로 선언한다.
- Fresh authority라면 별도 signed request/challenge/user response,
  issuer decision과 scope construction algorithm을 physical journal paths에
  추가하고 predecessor receipts는 provenance-only인지 precondition인지
  구분한다.
- Delegation이라면 parent scope보다 넓은 child scope를 금지한다. Active
  apply처럼 더 넓은 권한이 필요하면 delegation이 아니라 fresh approval로
  처리한다.
- `delegation_depth`, A0/R0, parent expiry/revocation, renewal selection,
  close state와 child invalidation의 exact truth table을 정의한다.
- Stage-C validator가 effective allow/deny set을 canonical set operation으로
  재계산하고 active target universe 외 scope가 0임을 확인하도록 한다.
- Expired/revoked B, stale B renewal, unclosed B, wrong delegation depth,
  denied-root inheritance와 unauthorized scope escalation negative tests를
  추가한다.

### PRE-P-R004-MAJOR-002 — C0 MANAGED FORMULA AND V1 REVIEW BINDING 미폐쇄

#### 근거

Target §13은 high-level acyclic order와 prohibited edges를 크게 개선했다.
그러나 C0과 V1의 실제 hash value가 아직 단일 결정적 byte formula로
정의되지 않는다.

#### C0 ambiguity

`C0/R004_AFTER_CHECKPOINT_V1`의 included value는 다음처럼만 적혀 있다.

```text
S0, E0와 self-excluded managed/session_handoff/control hashes
```

다음 exact domain이 없다.

- seq39 `session_handoff.changed_files` exact603에서 어떤 target paths를
  add/replace하고 어떤 internal/raw paths를 제외하는지
- event, routing manifests, v2.4.1 package files, supersession artifacts와
  checkpoint 각각이 managed path/content domain에 포함되는지
- `managed count/path/content`, `session_handoff.changed_files`,
  `source_commit_or_snapshot`의 canonical row encoding과 sort/order
- CAS replacement before/after bytes가 content-set에 반영되는 방식
- self-exclusion manifest가 source-relative paths와 external
  candidate/resolved/authority roots를 어떤 별도 domain으로 표현하는지
- C0의 `control hashes` exact role/path list와 digest formula

`source-self-exclusions.json`이 path들을 열거한다고 해도 C0가 그 list와
final target universe를 결합하는 exact set equation이 없으면 builder와
checker가 서로 다른 managed count/content hash를 계산할 수 있다.

#### V1 ambiguity

`V1/R004_RESOLVED_REVIEW_V1`은 X1, sealed resolved root manifest와
`physical Stage-B review hash`를 포함한다. Stage-B review는 sealed root 밖에
root manifest가 완성된 후 작성된다.

그러나 V1의 canonical value를 저장하는 distinct physical object/path와
writer가 expected Stage-B paths에 없다. 다음 두 해석이 모두 가능하다.

- Stage-B review 자체가 V1이면 V1이 자기 review hash를 포함해 self-reference다.
- V1이 review 뒤 별도로 계산되는 digest라면 그 value object/path,
  build/review 주체, NOREPLACE/freeze와 Stage-C binding이 정의되지 않았다.

Resolved root manifest도 Stage-A root manifest와 달리 자기 path를 recursive
domain에서 제외한다는 문언이 없다. High-level table에서 `self` edge를
금지해도 각 physical object의 exact value/domain과 생성 순서가 없으면
cycle detector가 어떤 bytes를 검사해야 하는지 결정할 수 없다.

#### Required remediation

- C0 managed formula를 exact set equation으로 정의한다. 예:

```text
AFTER_MANAGED_PATHS =
  (SEQ39_MANAGED_PATHS - CAS_REPLACED_PATHS)
  ∪ FINAL_CAS_PATHS
  ∪ FINAL_NOREPLACE_MANAGED_PATHS
  - EXPLICIT_NONMANAGED_PATHS
```

- 각 set의 literal member source, sort order, row encoding,
  path/content-set digest, event/checkpoint/self exclusions와 expected count를
  schema와 builder/checker 양쪽에 고정한다.
- `changed_files`, `source_commit_or_snapshot`와 control role hashes가 같은
  AFTER path set에서 어떤 서로 다른 hash definition을 쓰는지 분리한다.
- Stage-B review 뒤 별도 `resolved-review-binding.json` 같은 V1 physical
  object를 sibling journal/evidence path에 NOREPLACE로 만들고
  `{X1, resolved-root-manifest, review path/hash/bytes}`만 결속한다.
  Stage-B review는 V1을 참조하지 않고 Stage-C receipt가 V1을 참조하게 해
  순서를 단방향으로 만든다.
- 또는 V1 object를 제거하고 Stage-C receipt가 X1, sealed manifest와 external
  review를 직접 결속한다고 명시한다.
- Stage-B resolved root manifest의 explicit self-exclusion과 final seal
  pre/post digest를 추가한다.
- C0 member omission/addition, review-self inclusion, missing V1 object,
  resolved-manifest self inclusion과 canonical-order drift negative tests를
  추가한다.

## 5. MINOR finding

### PRE-P-R004-MINOR-001 — R003 PREDECESSOR VERDICT LITERAL 불일치

#### 근거

Target §1 exact table은 R003 verdict를 다음처럼 기록한다.

```text
REJECTED_PLAN_DO_NOT_EXECUTE; BLOCKING=4 / MAJOR=2 / MINOR=0
```

하지만 SHA
`41fae31a032e56e99d3f575ff9d68dbaaf3de85b66d8de7ce9cc349229253746`
로 target이 결속한 physical R003 review의 verdict는
`REJECTED_NON_EFFECTIVE_PLAN_ONLY`다. R004 §1의 바로 다음 문단은 후자의
올바른 literal을 다시 적어 같은 문서 안에서도 두 verdict가 충돌한다.

SHA/bytes/lines와 rejection severity는 올바르게 결속돼 있고 이 오기가 권한을
상승시키지는 않으므로 severity는 MINOR다. 그러나 exact predecessor table과
machine self-check 입력으로는 그대로 둘 수 없다.

#### Required remediation

- R004 add-only successor에서 exact table literal을
  `REJECTED_NON_EFFECTIVE_PLAN_ONLY; BLOCKING=4 / MAJOR=2 / MINOR=0`으로
  통일한다.
- Physical R003 review의 path/hash/bytes/lines와 parsed verdict/counts를
  기계검사하고 table과 prose가 동일한 canonical values를 소비하도록 한다.
- 기존 R003 또는 its review를 수정하지 않는다.

## 6. R003 findings 종결 상태

| R003 finding | R004 independent 판정 | 근거 |
|---|---|---|
| `BLOCKING-001` Stage-A namespace mutation | `CLOSED` | Stage-A candidate와 Stage-B resolved root를 siblings로 분리하고 각 root manifest/seal/external review를 둔다. |
| `BLOCKING-002` resolved full19/direct execution absent | `CLOSED` | 두 environments 각각 exact19 raw/result, #17 direct exact3와 19/19 PASS 조건을 둔다. |
| `BLOCKING-003` single runner selector undefined | `CLOSED` | explicit root/checkpoint/selector/manifest argv, allowed exact2와 rc2/child-exec0 negative matrix를 둔다. |
| `BLOCKING-004` authority receipt/lease validity | `NOT_CLOSED` | Receipt 서명/TTL/custody는 추가됐지만 consume/lease/close/recovery journal replay가 없다. |
| `MAJOR-001` Phase-0 future suite final-pin | `CLOSED` | predecessor-only Phase-0와 post-lane Stage-B regression-final two-build/review를 분리한다. |
| `MAJOR-002` seq40 hash DAG undefined | `PARTIALLY_CLOSED` | Topological IDs/edges는 생겼지만 C0 exact managed formula와 V1 physical review binding이 닫히지 않는다. |

추가로 R004의 B→C authority scope semantics는
`PRE-P-R004-MAJOR-001`, predecessor verdict literal은
`PRE-P-R004-MINOR-001`로 기록했다.

## 7. 확인된 충족 축과 claim ceiling

다음 설계 개선은 target에서 확인됐다.

- Stage-A candidate와 Stage-B resolved roots가 exact siblings이고 각 단계 뒤
  root seal과 외부 independent review가 있다.
- Phase 0는 predecessor identity/allowed transforms만 기록하고 successor
  regression-final은 all lanes 뒤 Stage B에서 두 번 생성·review한다.
- Runner는 explicit root/checkpoint/selector/manifest와 allowed states exact2를
  사용하고 negative에서 child execution을 0으로 제한한다.
- local-combined와 hosted-cpu 각각 ordered19 actual raw/result를 보존하고
  index17 direct exact3 및 synthetic-pack-only 5~14를 요구한다.
- Host broad bind 없이 environment별 synthetic runtime-pack을 사용한다.
- Stage C는 checkpoint-last와 hosted-cpu exact6 postcheck, receipt-only
  postcheckpoint recovery를 구분한다.
- S0→E0→C0→T1→X1→V1→A_C→P1 topological 의도와 cycle
  detector negative cases가 명시돼 있다.

이는 위 findings를 상쇄하거나 R004를 executable로 만들지 않는다. 현재 claim
ceiling은 다음과 같다.

```text
R003_BLOCKING_001_CLOSED=true
R003_BLOCKING_002_CLOSED=true
R003_BLOCKING_003_CLOSED=true
R003_BLOCKING_004_CLOSED=false
R003_MAJOR_001_CLOSED=true
R003_MAJOR_002_CLOSED=false
R004_FINDINGS_ZERO=false
R004_EXECUTABLE=false
STAGE_A_QUESTION_ALLOWED=false
AUTHORITY_JOURNAL_REPLAY_CLOSED=false
STAGE_B_TO_C_SCOPE_CLOSED=false
C0_MANAGED_FORMULA_CLOSED=false
V1_REVIEW_BINDING_CLOSED=false
STAGE_A_BUILD_ALLOWED=false
STAGE_B_RESOLUTION_ALLOWED=false
STAGE_C_APPLY_ALLOWED=false
SEQ40_ACTIVATION_ALLOWED=false
R007_SUCCESSOR_ALLOWED=false
P17_ALLOWED=false
PRODUCT_OR_CONTROL_CREDIT_ALLOWED=false
```

R004가 `NON_EFFECTIVE_PLAN_ONLY`이고 independent review findings가 nonzero이므로
target §18의 Stage-A authority 질문 조건은 성립하지 않는다.

## 8. 최종 경계

이 review의 verdict는 `REJECTED_NON_EFFECTIVE_PLAN_ONLY`다. Target R004는
add-only successor plan으로 위 `BLOCKING=1 / MAJOR=2 / MINOR=1`을 모두 닫고,
그 successor 자체가 exact byte-fixed independent review에서 findings
`0/0/0`을 얻기 전에는 실행 또는 authority 요청 근거로 사용할 수 없다.

이 review는 다음 권한이나 완료 credit을 만들지 않는다.

- Authority journal root/receipt/consume/lease/close/recovery write
- Phase-0 contract, Lane A/B/C/D/control 또는 aggregate build
- External environment provisioning, renewal, retention 또는 cleanup
- Synthetic runtime-pack discovery/build
- Stage-B authority resolution, two-env full19/regression 또는 root seal
- Active lock, runner, routing manifests, tests, validators와 v2.4.1 전환
- Seq40 event/checkpoint, compound supersession/activation
- Stage-C atomic apply/recovery 또는 application receipt
- R007 successor design/build, P candidate/resolve/apply
- Canonical/product/artifact/formal/device/gate/release 변경

공식 수치는 계속 artifact closed-equivalent `126/257`, formal
`0/279 PASS`, actual-device/real-event `0/0`, release gate `0/5`,
release `NOT_ELIGIBLE`로 유지한다.
