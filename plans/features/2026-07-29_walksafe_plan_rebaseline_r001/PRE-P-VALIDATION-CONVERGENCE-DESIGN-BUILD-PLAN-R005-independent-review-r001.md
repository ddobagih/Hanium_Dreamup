# PRE-P Validation Convergence Design/Build Plan R005 독립검수 R001

## 1. 검수 대상과 판정

| 항목 | exact 값 |
|---|---|
| review_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R005-INDEPENDENT-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R005.md` |
| target SHA-256 | `675645378e7f87d002cfba2e109bc72712c6998dd620a53de42864f8c7f3a25d` |
| target bytes | `39,839` |
| target lines | `997` |
| target type | `regular file, non-symlink, nlink=1` |
| target terminal LF / NUL | `true / 0` |
| target status | `NON_EFFECTIVE_PLAN_ONLY` |
| verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY` |
| findings | `BLOCKING=5 / MAJOR=0 / MINOR=0` |
| review authority | `NONE` |

검수 시작과 종료 시 target의 SHA-256, bytes와 lines가 위 값과 같음을
확인했다. 이 review는 target을 수정하지 않으며, authority journal/genesis,
candidate/external environment, Stage-B resolve/validation, Stage-C
transaction/recovery, checkpoint/control 전환, R007 successor 또는 P 작업
권한을 만들지 않는다.

```text
VERDICT=REJECTED_NON_EFFECTIVE_PLAN_ONLY
BLOCKING=5
MAJOR=0
MINOR=0
TARGET_UNCHANGED=true
R005_REMAINS_NON_EFFECTIVE_PLAN_ONLY=true
PLAN_EXECUTION_AUTHORIZED=false
STAGE_A_REQUEST_ALLOWED=false
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
RECOVERY_WRITE_AUTHORIZED=false
R007_SUCCESSOR_BUILD_AUTHORIZED=false
P17_BUILD_AUTHORIZED=false
P_APPLY_AUTHORIZED=false
```

## 2. 검수 범위

Target 전체 997행과 현재 read-only source 사실을 다음 축으로 대조했다.

- R004 formal `1/2/1` 및 skeptical `4/2/0` findings의 실제 종결 여부
- R005 단독 normative completeness와 predecessor non-import 경계
- discovery current/final layer 합, direct/discovered 분리와 exact26 arithmetic
- Phase-0 predecessor identity와 Stage-B successor regression-final
- single runner exact two states 및 two-environment ordered19 actual validation
- synthetic runtime-pack build provenance와 isolated sandbox 실행 모델
- append-only global journal의 record-kind schemas와 full replay 가능성
- pending Stage-C handoff expiry, incident close와 retry transition
- Stage-C effective allow set과 application receipt publication
- pre-checkpoint crash 뒤 `RECONCILED_PREFIX`의 data/directory durability
- C0 managed formula, external V1 binding과 official claim ceiling

독립 산술 재계산 결과는 target과 일치했다.

```text
current assigned = 31+23+7+3+45+18 = 127
successor assigned = 28+23+7+3+53+16+2 = 132
selected + excluded = 74+58 = 132
runner all execution = 74+2 = 76
exact active targets = 2+23+1 = 26
```

## 3. BLOCKING findings

### PRE-P-R005-BLOCKING-001 — SYNTHETIC RUNTIME-PACK / ISOLATED SANDBOX NORMATIVE CONTRACT 누락

#### 근거

Target §1.1은 R005만 future PRE-P execution의 normative plan이며 R004와 이전
plans를 normative merge/import하지 않는다고 선언한다. R005에 없는 execution
operation/path/schema는 금지된다.

그러나 synthetic runtime-pack과 isolated projection은 다음 수준으로만
언급된다.

- §4 Stage-A role tree의
  `runtime-pack/{local-combined,hosted-cpu}/{build-01,build-02,candidate}/`
- §14의 각 projection이 자기 synthetic pack을 사용한다는 문장
- full19 index 5~14의 unmanifested host open/read/exec가 0이어야 한다는
  결과 조건

R005 자체에는 runtime-pack과 sandbox를 실제로 만들고 검증·실행할 normative
contract가 없다. 최소 다음이 빠져 있다.

- `discover-runtime-inputs`와 `build-runtime-pack` exact builder paths,
  builder manifests 및 raw trace/negative receipt paths
- exact executable, shebang interpreter, stdlib, ELF loader/shared-library
  transitive closure, locale/timezone/NSS/CA/config의 allowed input schema
- observed trace는 discovery evidence일 뿐 allowlist가 아니라는 enforcement
  algorithm
- environment별 build-01/build-02/candidate recursive
  path/type/mode/hash/link-target manifests와 byte-equality rule
- device/FIFO/socket, absolute/dangling/escaping links, hardlinks와 unmanifested
  member rejection
- bwrap executable/version/hash와 exact mount argv
- broad host `/usr`, `/etc`, `/lib`, `/bin` bind 금지 및 unavailable fallback
  금지
- active source read-only `/source`, repository 밖 writable projection,
  final-relative overlay, root FD/realpath/dev/inode/mode와 source double-read
- `.git` copy, source/destination distinct inode, no hardlink, staged
  `nlink=1`, cwd/`__file__`/import-root enforcement
- cache/temp/raw write allowlist와 active-root pre/post write-0 proof

R004에 이러한 설명이 있었더라도 §1.1이 R004를
`SUPERSEDED_NON_NORMATIVE`로 만들었으므로 R005 실행 시 이를 암묵적으로
가져올 수 없다. §14의 `synthetic pack only` 결과 assertion만으로는 pack을
생성하거나 full19 command를 sandbox 안에서 실행할 exact argv/root가 없다.

따라서 Stage-A subject의 runtime-pack members와 Stage-B two-environment
projections를 합법적으로 생성할 normative operation이 없고, ordered19
acceptance에 도달할 수 없다.

#### Required remediation

- R005 add-only successor에 environment별 runtime-pack builders,
  builder-manifest, build-01/02/candidate recursive manifests와 raw
  read/exec/open traces의 exact paths를 다시 완전히 열거한다.
- Frozen predecessor command contract에서 allowed runtime inputs를 정하고,
  trace가 allowlist를 확대하지 못하도록 한다.
- Required binaries, interpreters, stdlib/native libraries와 config의 exact
  transitive closure algorithm, member types/link policy와 two-build equality를
  명시한다.
- Exact bwrap JSON argv/mount universe를 고정한다. Synthetic subtrees와 sealed
  environment, active RO, outside-repository projection RW 외 host bind는
  허용하지 않는다.
- Projection copy/overlay/root/import/link/write semantics와 active source
  pre/post identity를 모두 Stage-B resolved evidence에 결속한다.
- Pack build 또는 bwrap이 불가능하거나 unresolved host access가 하나라도
  있으면 broad bind/fallback 없이 Stage-A/Stage-B write 0 또는 validation
  INCOMPLETE로 중단한다.
- Local-combined와 hosted-cpu 각각의 pack/environment/input-lock mapping을
  exact하게 정의하고 두 projection의 ordered19가 자기 pack 밖 input을 읽지
  않았음을 검증한다.

### PRE-P-R005-BLOCKING-002 — JOURNAL RECORD-KIND PAYLOAD SCHEMAS가 MATERIALIZE 불가

#### 근거

Target §5.2는 global journal의 15 record kinds를 열거하지만 모든 kinds에
공통인 한 payload field list만 제시한다. §6의 `common ∪ exact stage fields`
규칙은 attempt authority receipt schema이며 global journal record-kind
schemas가 아니다.

Full replay와 §7 handoff가 요구하는 핵심 값 중 다음은 §5.2 common fields에
없다.

- `LEASE_RENEWED`와 `RECOVERY_LEASE_RENEWED`의 prior lease
  path/hash/bytes 및 renewal ordinal
- `PREPARED`의 sealed subject manifest, candidate/resolved review,
  two-env validation/regression digests와 prepared record identity
- `DELEGATED_AND_CLOSED`의 B receipt/latest lease/PREPARED
  path/hash/bytes, C pending receipt path/hash/bytes,
  B/C 각각의 authorization before/after state와 handoff depth
- recovery records의 original C consume/incident, progress head,
  checkpoint live state와 exact recovery scope
- close records의 prepared/consume/lease identities, terminal reason와
  immutable closure identity

§5.3은 latest renewal이 exact prior lease hash를 이어야 한다고 하고 §7은
`DELEGATED_AND_CLOSED`가 위 hashes를 포함한다고 설명하지만, 그 fields의
canonical names, types, required/forbidden status와 value formulas가 없다.

Stage-C pending receipt도 §6에서
`authorization_state=PENDING_DELEGATION`이라고 요구하지만 receipt common
exact fields와 stage-specific table에 이 field의 canonical schema가 없다.
Unknown/future field는 `FORBIDDEN_FIELD` rc2이므로 구현자가 이 상태 field를
임의 추가할 수도 없다.

그 결과 signer, journal writer와 replay validator가 같은 JCS payload bytes를
구성할 수 없고, `PREPARED` 또는 atomic B→C handoff가 실제로 어떤 physical
evidence를 결속했는지 검증할 수 없다. Record-kind prose semantics는 signed
payload schema를 대체하지 않는다.

#### Required remediation

- 15 global record kinds 각각에 대해 exact required/optional/forbidden field
  schema를 별도 정의한다. Field name, JSON type, enum/cardinality, canonical
  path/hash/bytes tuple와 before/after state를 고정한다.
- 특히 lease records에는 `renewal_ordinal`과 exact prior lease identity,
  PREPARED에는 subject/review/validation identities,
  DELEGATED_AND_CLOSED에는 B receipt/consume/latest-lease/prepared와 C pending
  receipt/state identities를 필수로 둔다.
- Stage A/B/C/recovery attempt receipt도 common fields와 exact stage fields의
  literal field tables를 만들고 `authorization_state`와 handoff semantics를
  포함한다.
- 각 record kind의 allowed predecessor states, exact successor state와
  terminal/nonterminal 성질을 transition table로 정의한다.
- Signer와 validator가 동일 schema ID/version을 결속하고 missing/extra/wrong
  type/wrong prior identity/renewal ordinal gap을 rc2로 거부한다.
- Positive canonical fixtures와 per-kind missing/extra/tamper negative fixtures를
  frozen builder/review paths에 추가한다.

### PRE-P-R005-BLOCKING-003 — PENDING STAGE-C EXPIRY의 VALID JOURNAL TRANSITION 부재

#### 근거

Target §5.3의 global replay rule은 `close before prepare`를 무조건 rc2/write0으로
거부한다. §7은 B가 `PREPARED`인 동안 fresh Stage-C receipt를
`PENDING_DELEGATION`으로 만들고, atomic `DELEGATED_AND_CLOSED` 전에는 C가
ineffective/unconsumed이라고 정의한다.

동시에 §7은 첫 pending C attempt가 handoff 전에 만료되면:

```text
pending C attempt를 incident-close
→ fresh C attempt/challenge 발급
```

하라고 한다. 이 C attempt는 handoff 전이므로 B의 PREPARED를 상속하지 않고,
자체 `PREPARED` record도 없다. Effective/consumed/leased 상태도 아니다.
따라서 generic `CLOSED_INCIDENT`를 쓰면 §5.3의 close-before-prepare rule이
그 record를 거부한다.

Close가 거부되면 §4의 retry가 요구하는 prior attempt immutable incident
closure를 만들 수 없고, fresh C attempt의 prior-attempt chain도 닫히지
않는다. 반대로 replay rule을 무시하고 close하면 journal validator 자체가
그 head를 invalid로 만든다.

#### Required remediation

- Pending, effective와 recovery attempts의 state machine을 분리한다.
- Handoff 전 C expiry/cancellation을 위한 exact record kind
  `PENDING_EXPIRED` 또는 `PENDING_CANCELLED`를 추가하거나, stage-specific
  `CLOSED_INCIDENT` predecessor state에 `ISSUED_PENDING`을 명시적으로
  허용한다.
- 이 transition은 C pending receipt/expiry/reason을 닫되 B PREPARED,
  B lease/effective state를 변경하지 않아야 한다.
- Pending close 뒤 fresh C attempt가 previous pending closure
  path/hash/bytes와 same B PREPARED identity를 결속하도록 한다.
- `close before prepare` rule은 B/A prepared attempts, pending-C cancellation,
  effective-C incident와 recovery incident별 exact truth table로 대체한다.
- Pending expiry, cancellation, concurrent handoff race, close-after-handoff,
  duplicate pending close와 stale B prepared negative tests를 추가한다.

### PRE-P-R005-BLOCKING-004 — C_ALLOW가 APPLICATION RECEIPT WRITE를 금지함

#### 근거

Target §7의 effective Stage-C scope는 정확히:

```text
C_ALLOW = exact26 target operations ∪ transaction journal writes
C_DENY = all paths/operations - C_ALLOW
```

이다. §16은 application receipt가 exact26에 포함되지 않는 postcheck output임을
명시한다. Receipt의 final path는 repository 안의:

```text
docs/control/execution/goal-gates/
  WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001/
  application-receipt.json
```

이며 external transaction journal write도 아니다.

그런데 §15는 hosted-cpu exact6이 모두 PASS한 뒤 이 application receipt를
써야 Stage C success가 끝난다고 한다. 현재 set equation에서는 receipt file
NOREPLACE, file fsync와 repository parent-directory fsync가 모두
`C_DENY`에 속한다.

Post-checkpoint recovery scope에는 application receipt/fsync가 명시돼 있지만,
ordinary successful Stage C가 원래부터 금지된 write를 수행하기 위해 일부러
recovery incident로 전환할 수는 없다. 따라서 exact6 PASS 후 정상
application receipt publication에 도달할 authorized path가 없다.

#### Required remediation

- C_ALLOW에 application receipt exact path의
  `NOREPLACE_CREATE`, file fsync와 exact parent-directory fsync operations를
  명시적으로 추가한다.
- 이를 exact26 pre-checkpoint target universe와 분리된
  `POSTCHECK_OUTPUT_EXACT1` 같은 typed set으로 정의해 target count와 M_after가
  변하지 않도록 한다.
- Receipt creation은 checkpoint committed + exact6 PASS + valid C
  consume/lease + transaction progress head 조건에서만 허용한다.
- Ordinary C와 postcheckpoint recovery가 같은 receipt bytes/path를
  NOREPLACE로 경쟁할 때 existing exact hash와 durability를 검증하는
  idempotence rule을 둔다.
- Receipt file 또는 parent fsync 전에 expiry/crash가 난 각 상태와
  wrong path/overwrite/second receipt/receipt-before-postcheck negative tests를
  추가한다.
- Authority/global journal lifecycle writes가 별도 issuer/journal-writer
  capability인지 C executor scope인지도 actor별 allow set으로 분리한다.

### PRE-P-R005-BLOCKING-005 — RECONCILED_PREFIX가 NON-DURABLE LIVE STATE를 승격할 수 있음

#### 근거

Target §8.1의 정상 target 순서는:

```text
CAS/NOREPLACE commit
→ file fsync
→ parent fsync
→ PREFIX_ADVANCED
```

이다. 그러나 §8.2는 commit보다 뒤의 progress record가 유실된 경우 live
exact26 before/after hashes로 unique prefix를 복원하고
`RECONCILED_PREFIX` signed record를 먼저 쓴다고 한다.

다음 crash 상태들은 모두 live path/hash가 after bytes로 보일 수 있다.

- rename/CAS 뒤 file fsync 전 crash
- file fsync 뒤 parent directory fsync 전 crash
- parent fsync 뒤 corresponding progress record publish 전 crash

첫 두 상태에서 live hash를 읽을 수 있다는 사실은 file data와 directory entry가
다음 power-loss까지 durable하다는 증거가 아니다. `RECONCILED_PREFIX` journal
record 자체를 fsync해도 repository target file과 그 parent directory를
durable하게 만들지 않는다.

현재 문언은 recovery가 live hash만 보고 prefix를 durable로 승격한 뒤 remaining
suffix와 checkpoint ordinal 26을 commit할 수 있게 한다. 그 후 다시 crash하면
checkpoint는 남지만 earlier reconciled target가 사라지는 atomicity 위반이
가능하다.

#### Required remediation

- `RECONCILED_PREFIX` 전 각 reconstructed after-state target을 exact root FD
  기준으로 reopen하고 path/type/dev/inode/mode/hash/bytes와 no-symlink를
  재검증한다.
- Regular file을 fsync하고 해당 final parent directory를 fsync한 뒤에만
  signed `FILE_FSYNCED`와 `PARENT_FSYNCED` reconciliation evidence를
  durable publish한다.
- 그 두 durability records가 current recovery consume/lease와 exact target
  ordinal을 잇는 경우에만 `RECONCILED_PREFIX`와 `PREFIX_ADVANCED`를 쓴다.
- NOREPLACE/CAS rename result가 ambiguous하거나 file/parent FD를 안전하게
  reopen·fsync할 수 없으면 prefix로 승격하지 않고 write 0 incident로
  중단한다.
- Crash injection에 commit→file-fsync, file-fsync→parent-fsync,
  parent-fsync→progress-record와 each reconciliation durability step을
  포함한다.
- Reconciled prefix 뒤 checkpoint commit 및 두 번째 crash에서도 exact25
  target bytes가 모두 durable함을 recovery test로 증명한다.

## 4. Predecessor findings 종결 상태

### 4.1 R004 formal review

| R004 formal finding | R005 independent 판정 | 근거 |
|---|---|---|
| `BLOCKING-001` authority journal lifecycle | `NOT_CLOSED` | Append-only journal과 consume/lease/close kinds는 추가됐지만 record-kind schemas와 pending-C close transition이 실행 불가능하다. |
| `MAJOR-001` B→C semantics | `PARTIALLY_CLOSED` | Fresh C와 atomic handoff 의도는 명확해졌지만 signed DELEGATED_AND_CLOSED payload schema가 없다. |
| `MAJOR-002` C0/V1 | `CLOSED` | §9 exact managed equation/rows와 §10 external review-binding object/path로 self-reference를 제거한다. |
| `MINOR-001` R003 verdict literal | `CLOSED` | §1은 physical literal `REJECTED_NON_EFFECTIVE_PLAN_ONLY`를 사용한다. |

### 4.2 R004 skeptical review

| R004 skeptical finding | R005 independent 판정 | 근거 |
|---|---|---|
| `BLOCKING-001` retry namespace | `CLOSED` | Attempt-key siblings, max16, shared hard deadline와 immutable prior close chain을 둔다. |
| `BLOCKING-002` durable recovery | `NOT_CLOSED` | Recovery-only attempts/progress journal은 생겼지만 RECONCILED_PREFIX 전 live target durability를 복구하지 않는다. |
| `BLOCKING-003` S0/review | `CLOSED` | Stage-B reviews와 V1 binding을 external journal domain에 둔다. |
| `BLOCKING-004` discovery arithmetic | `CLOSED` | Exact layer transforms, assigned132, selected74/excluded58와 direct registries가 산술·member 계약으로 닫힌다. |
| `MAJOR-001` stage schemas | `NOT_CLOSED` | Attempt receipt table은 개선됐지만 global journal record-kind required/forbidden schemas가 없다. |
| `MAJOR-002` official claim ceiling | `CLOSED` | §1이 current/after exact numbers와 all deltas zero를 명시한다. |

R005의 normative completeness 선언 뒤 synthetic pack/sandbox를 재열거하지 않은
문제는 `PRE-P-R005-BLOCKING-001`, C_ALLOW application receipt omission은
`PRE-P-R005-BLOCKING-004`로 새로 발생했다.

## 5. 확인된 충족 축과 claim ceiling

다음은 target에서 확인됐다.

- R004 formal/skeptical review paths, hashes, bytes, lines와 verdicts가
  physical files에 일치한다.
- R003 physical verdict literal이
  `REJECTED_NON_EFFECTIVE_PLAN_ONLY`로 정정됐다.
- Attempt-key sibling roots, external reviews와 immutable prior-attempt chain이
  fixed `-001` reuse를 제거한다.
- Mutable head 대신 contiguous signed global records와 NOREPLACE competition을
  사용하려는 설계가 있다.
- Phase 0는 predecessor-only이고 successor regression-final은 all lane/resolved
  inputs 뒤 two-build/review된다.
- Discovery current/final arithmetic, selected/excluded/direct partitions와
  exact26 count/modes가 일치한다.
- Runner exact2 selector, two-environment ordered19 actual results와 Stage-C
  hosted exact6 범위가 명시돼 있다.
- C0 managed path/content formulas와 external V1 review binding은 R004의
  ambiguity를 닫는다.
- Official artifact/formal/device/gate/release와 canonical/product delta는
  exact zero ceiling으로 유지된다.

이 충족 축은 위 blockers를 상쇄하거나 실행 권한을 만들지 않는다. 현재 claim
ceiling은 다음과 같다.

```text
ARITHMETIC_VALIDATED=true
PHASE0_PREDECESSOR_ONLY_CLOSED=true
C0_MANAGED_FORMULA_CLOSED=true
V1_EXTERNAL_BINDING_CLOSED=true
SYNTHETIC_PACK_NORMATIVE_CONTRACT_CLOSED=false
SANDBOX_NORMATIVE_CONTRACT_CLOSED=false
GLOBAL_RECORD_KIND_SCHEMAS_CLOSED=false
PENDING_C_CLOSE_TRANSITION_CLOSED=false
ORDINARY_C_APPLICATION_RECEIPT_AUTHORIZED=false
RECONCILED_PREFIX_DURABILITY_CLOSED=false
R005_FINDINGS_ZERO=false
R005_EXECUTABLE=false
STAGE_A_REQUEST_ALLOWED=false
STAGE_A_BUILD_ALLOWED=false
STAGE_B_RESOLUTION_ALLOWED=false
STAGE_C_APPLY_ALLOWED=false
RECOVERY_ALLOWED=false
SEQ40_ACTIVATION_ALLOWED=false
R007_SUCCESSOR_ALLOWED=false
P17_ALLOWED=false
PRODUCT_OR_CONTROL_CREDIT_ALLOWED=false
```

R005가 `NON_EFFECTIVE_PLAN_ONLY`이고 independent review findings가 nonzero이므로
target §21의 Stage-A request 조건은 성립하지 않는다.

## 6. 최종 경계

이 review의 verdict는 `REJECTED_NON_EFFECTIVE_PLAN_ONLY`다. Target R005는
add-only successor plan으로 위 `BLOCKING=5 / MAJOR=0 / MINOR=0`을 모두 닫고,
그 successor 자체가 exact byte-fixed independent review에서 findings
`0/0/0`을 얻기 전에는 실행 또는 authority 요청 근거로 사용할 수 없다.

이 review는 다음 권한이나 완료 credit을 만들지 않는다.

- Authority journal genesis/receipt/global record/transaction record write
- Phase-0 contract, Lane A/B/C/D/control 또는 aggregate build
- External environment provisioning, lease/renewal, retention 또는 cleanup
- Synthetic runtime-pack discovery/build 또는 isolated projection
- Stage-B authority resolution, two-env full19/regression 또는 subject seal
- Pending/atomic Stage-C handoff, consume, transaction 또는 recovery
- Active lock, runner, routing manifests, tests, validators와 v2.4.1 전환
- Seq40 event/checkpoint, compound supersession/activation
- Application receipt 또는 any official completion/approval credit
- R007 successor design/build, P candidate/resolve/apply
- Canonical/product/artifact/formal/device/gate/release 변경

공식 수치는 계속 artifact closed-equivalent `126/257`, artifact open
`131/257`, formal `0/279 PASS`, actual-device/real-event `0/0`, release gate
`0/5`, release `NOT_ELIGIBLE`로 유지한다.
