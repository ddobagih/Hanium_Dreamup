# PRE-P Validation Convergence Design/Build Plan R006 독립검수 R001

## 1. 검수 대상과 판정

| 항목 | exact 값 |
|---|---|
| review_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R006-INDEPENDENT-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006.md` |
| target SHA-256 | `4a9f7f21d505bf6cf53d1ea8a16e21e7ebca5c154541d49928d03383b7d23de9` |
| target bytes | `91,165` |
| target lines | `1,972` |
| target type | `regular file, non-symlink, nlink=1` |
| target terminal LF / NUL | `true / 0` |
| target status | `NON_EFFECTIVE_PLAN_ONLY` |
| verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY` |
| findings | `BLOCKING=12 / MAJOR=5 / MINOR=1` |
| C0 / regression findings | `0 / 0` |
| review authority | `NONE` |

검수 시작 시 target의 SHA-256, bytes, lines와 physical properties가 위 값과
같음을 확인했다. 종료 시에도 같은 값을 다시 확인한다. 이 review는 target을
수정하지 않으며 authority journal/genesis, candidate/environment/pack,
Stage-B resolution/validation, Stage-C transaction/recovery, checkpoint,
application receipt, R007 successor 또는 P 작업 권한을 만들지 않는다.

```text
VERDICT=REJECTED_NON_EFFECTIVE_PLAN_ONLY
BLOCKING=12
MAJOR=5
MINOR=1
C0_FINDINGS=0
REGRESSION_FINDINGS=0
TARGET_UNCHANGED=true
R006_REMAINS_NON_EFFECTIVE_PLAN_ONLY=true
R006_INDEPENDENT_REVIEW_ZERO_FINDINGS=false
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
APPLICATION_RECEIPT_WRITE_AUTHORIZED=false
RECOVERY_WRITE_AUTHORIZED=false
R007_SUCCESSOR_BUILD_AUTHORIZED=false
P17_BUILD_AUTHORIZED=false
P_APPLY_AUTHORIZED=false
```

## 2. 검수 범위와 finding 없는 교차검증

Target 전체 1,972행을 다음 축으로 대조했다.

- R006 standalone 규칙과 Stage-A builder/materialization completeness
- runtime-pack closure, two-build equality와 bwrap 실행 가능성
- two-environment full19 literal command/consumer closure
- global/transaction journal bootstrap, tagged-union schema와 B→C handoff
- OFD lock, fencing, crash cut, durable-prefix와 recovery state machine
- hosted exact6 raw evidence와 application receipt publication
- C0 managed-set 산술, discovery registry, exact26와 claim ceiling

현재 checkpoint와 target의 literal set을 독립 재계산한 결과 다음 값은
일치했다. 이 산술에는 별도 C0 finding이 없다.

```text
S = 603
C intersection S = 1
C - S = {tests/requirements.lock}
A = 23
A intersection S = 0
A intersection C = 0
Q not in S or A or C
M_after = 627
projected_path_set_sha256 =
  762a4f9b487bba0177c72127c8bfa7211ca9900845452df2a02b1d9bad850c4d

exact26 = 26 distinct
CAS_REPLACE = 2
NOREPLACE = 23
CHECKPOINT_CAS_LAST = 1

discovery paths = 132 distinct
roles = 28/23/7/3/53/16/2
selected/excluded = 74/58
runner execution = 74+2 = 76
current direct / D direct = 5/2
```

R005 plan/formal/skeptical fingerprints, current checkpoint와 two CAS target
fingerprints도 target의 provenance와 일치했다. Phase0/regression-final의
선후관계와 선언 산술에서는 독립 finding을 추가하지 않았다. 이는 future
full19 또는 regression을 실행했다는 뜻이 아니다. 현재 authority가 없고 아래
execution contract findings가 남으므로 실제 validation credit은 계속 0이다.

## 3. BLOCKING findings

### PRE-P-R006-BLOCKING-001 — RUNTIME-PACK MANIFEST BYTE-EQUALITY와 PHYSICAL INODE 기록이 양립 불가

#### 근거

Target §5.1 477행은 각 environment의 build-01/build-02
`recursive-content-manifest.json`이 byte-equal해야 한다고 요구한다. 동시에
§4 415~419행은 두 build를 별도 clean directory에서 실행하고 candidate를 새
inode로 materialize하라고 하며, §5.1 478~484행은 candidate/external
materialization을 fresh distinct inode로 만들고 manifest에
`dev/inode/uid/gid/mode/nlink/size/hash/link-target`을 재귀 기록하라고 한다.

별도 materialization의 actual `dev/inode`는 달라야 한다. 이를 manifest bytes에
넣으면 build-01/build-02 manifest byte equality를 만족할 수 없다. 반대로
equality를 위해 값을 같게 만들면 fresh distinct inode와 actual physical
equality 검증을 위반한다. `PACK-02` negative fixture도 두 조건을 동시에
요구하므로 실행 경로가 없다.

#### Required remediation

- Byte-equal 대상인 logical content manifest에는
  path/type/mode/size/hash/link-target처럼 materialization과 무관한 canonical
  fields만 둔다.
- dev/inode/uid/gid/nlink와 extraction 전후 identities는 environment/build별
  physical materialization receipt로 분리한다.
- build-01, build-02, candidate와 external root의 corresponding regular
  members가 pairwise-distinct inode라는 비교 receipt를 정의한다.
- Logical equality와 physical distinctness 각각에 positive/negative canonical
  fixtures를 둔다.

### PRE-P-R006-BLOCKING-002 — STAGE-A BUILDER / MATERIALIZER를 실행할 NORMATIVE CONTRACT가 없음

#### 근거

Target §1 21~24행은 R006에 없는 operation, field와 schema를 금지한다.
그러나 §4 153~412행은 builder/template/output filenames를 열거할 뿐 각
builder의 literal argv, executable identity, cwd/environment, ordered inputs,
source read set, output schema, raw-result schema와 atomic publication 순서를
정의하지 않는다. §4 415~420행은 clean two-build equality와 fresh-inode
candidate라는 결과 조건만 둔다.

`candidate-target-map.json`은 §4.4에 세 경로만 있고 row schema가 없다.
§17은 final target와 operation만 열거하며 candidate source path, final
filesystem mode/owner, expected candidate identity와의 26-row mapping을 주지
않는다. `build-receipt.json`, `equality-receipt.json`,
`source-snapshot.json`, `typed-dependency-graph.json`도 exact signed fields와
검증 algorithm이 없다.

따라서 두 builder가 같은 임의 bytes 또는 같은 잘못된 mapping을 만들더라도
semantic equality만으로 거부할 수 없고, standalone 규칙 안에서 Stage-A
subject를 합법적으로 materialize할 방법도 없다.

#### Required remediation

- 각 builder/materializer마다 exact executable hash, literal argv token array,
  cwd, cleared env, ordered input `Physical` references, output allowlist와 raw
  result schema를 정의한다.
- Build-01/build-02/candidate publication의 temp, file fsync, directory fsync,
  NOREPLACE, reopen 검증 순서를 고정한다.
- Exact26 모두에 대해 ordinal, candidate source, final target, operation,
  before identity, after hash/bytes/uid/gid/mode/nlink를 가진 literal mapping을
  추가한다.
- 모든 receipt, snapshot, dependency graph와 target map의 exact field/type
  schema 및 missing/extra/tamper negatives를 추가한다.

### PRE-P-R006-BLOCKING-003 — STAGE-C HOSTED EXACT6의 ARGV, RAW PATH와 RESULT SCHEMA가 없음

#### 근거

Target §15 1495~1509행은 hosted exact6를 여섯 개의 설명 문장으로만
열거한다. 각 check가 §5.2 sandbox를 쓴다고 하지만 §5.2의 payload는 여전히
`<manifested argv>` placeholder이고, §6 623~651행의 complete invocation-ID
allowlist는 Stage-B before/after/full19/regression/repeat만 포함한다.

`C_ALLOW`는 hosted exact6 raw-result write를 허용하지만 Stage-C
attempt/transaction 아래 raw root, six-file sibling paths, exact invocation
IDs와 recovery fresh namespace가 없다. Journal exact tree에도 Stage-C raw
subtree가 없다. `POSTCHECK_PASSED.exact6_results`는 element type, cardinality,
raw `Physical` references와 PASS formula가 없는 opaque field다.

또한 §5.2는 sealed Stage-B projection worktree만 bind한다. Exact6 check 1이
요구하는 live exact26 physical identity를 그 projection에서 어떻게 관측하는지,
checkpoint 이후 어떤 fresh worktree를 만들 수 있는지 정의하지 않는다.
Stage-C scope에는 그러한 projection materialization도 없다.

#### Required remediation

- Exact six IDs와 각 executable, literal argv, cwd, env, input identity,
  timeout/output cap, expected assertion을 정의한다.
- Transaction/recovery-attempt scoped raw root와 각 invocation의
  intent/stdout/stderr/raw-trace/normalized-trace/result exact paths 및 schemas를
  열거한다.
- `exact6_results`를 exact-length 6 typed array로 만들고 모든 raw members,
  environment/pack, live-root observation과 PASS를 `Physical`로 결속한다.
- Live post-apply root를 read-only로 보되 final inode identities를 보존하는
  sandbox projection 방식과 그 C_ALLOW operation을 정의한다.

### PRE-P-R006-BLOCKING-004 — FULL19 SLOT의 LITERAL ARGV / CONSUMER MAP이 없음

#### 근거

Target §7 696~700행은 future `intent.json`이 argv 등을 동결한다고만 말한다.
§7 702~722행의 19-row table에는 ID와 impact뿐이며 executable, literal argv,
input consumer, expected output와 timeout이 없다. §16의 discovery registry는
132 path의 broad consumer role을 나타내지만 각 full19 slot과의 mapping이
아니다. Non-discovery direct registry도 #3/#18, #17/Stage-C-6 일부만 연결한다.

그 결과 #1~#19를 어떤 exact command로 실행하는지, #17 direct exact3 및
#19 snapshot이 무엇을 소비하는지, two environment intent가 같은 command
contract인지 독립 재현할 수 없다. Future builder가 임의 argv를 intent에
기록해도 plan과 비교할 기준이 없고, §1 standalone 규칙 때문에 실행 시
보완할 수도 없다.

#### Required remediation

- Full19 exact 19 rows에 literal argv token array, executable/interpreter
  `Physical`, cwd, exact env, timeout/output cap과 expected rc/assertion을 둔다.
- 각 slot에 discovery/direct files, routing manifest, checkpoint와 generated
  contract의 exact consumer references를 연결한다.
- Two environment intent가 동일 logical command set이고 environment/pack
  references만 다르다는 digest formula를 정의한다.
- Slot swap, argv drift, consumer omission/duplication과 unexpected discovered
  input을 거부하는 canonical fixtures를 추가한다.

### PRE-P-R006-BLOCKING-005 — `incident:Physical`의 ARTIFACT PATH / SCHEMA / PUBLICATION이 없음

#### 근거

Target §9 860행과 867행은 ordinary/recovery incident close에
`incident:Physical`을 필수로 둔다. 그러나 §3, §4, §6과 §8의 complete trees
어디에도 incident artifact path가 없다. Incident payload schema, signature
domain, publisher, mode, NOREPLACE/fsync 순서와 stage scope도 정의되지 않는다.

Early A/B incident, pending C expiry, ordinary C crash와 recovery incident는
모두 이 Physical을 만들어야 terminal closure와 retry chain을 구성할 수 있다.
기존 raw response/result를 incident로 사용하는 formula도 없다. 미열거 path와
schema가 금지되므로 `CLOSED_INCIDENT`와 `RECOVERY_CLOSED_INCIDENT`를
materialize할 수 없다.

#### Required remediation

- Stage/attempt별 exact incident artifact path를 complete tree와 scope에
  추가하고 typed signed payload, reason enum, observed identities와 raw
  references를 정의한다.
- Incident artifact를 먼저 atomic publish한 뒤 close record가 그 exact
  `Physical`을 결속하는 순서를 정의한다.
- 또는 incident evidence를 close record 안의 exact inline tagged union으로
  바꾸고 `Physical` requirement를 제거한다.
- Pre-artifact crash, orphan artifact adoption/rejection과 duplicate close
  fixtures를 추가한다.

### PRE-P-R006-BLOCKING-006 — C PENDING RECEIPT의 `expected_global_head`가 ISSUED와 시간적으로 순환

#### 근거

Stage-C pending receipt common fields는 §10 941행에서
`expected_global_head:RecordRef`를 필수로 갖는다. `ISSUED` global record는
§9 853행에서 그 receipt의 `Physical`을 결속한다. Receipt를 먼저 publish할
때 current head를 H0로 결속하면 이후 C `ISSUED` H1이 global head가 된다.
그런데 handoff gate §10.1 1041행은 receipt의 expected head가 handoff 시
current compatible head와 exact 같아야 한다고 요구한다. 정상 경로에서도
H0 != H1이다.

Receipt가 미래 H1을 미리 결속하도록 하면 receipt hash가 H1에 들어가고 H1
hash가 receipt에 들어가는 순환이 생긴다. 추가 global record가 하나라도
있으면 mismatch는 더 커진다. 따라서 `DELEGATED_AND_CLOSED`에 도달할 수 없다.

#### Required remediation

- Receipt의 issuance expected head는 `stage_c_issued.prior_record`와 비교한다.
- Handoff current head는 exact `stage_c_issued`이거나 명시적으로 허용된
  descendant chain의 last record와 비교한다.
- No-intervening-record 정책 또는 allowed intervening-kind policy를 고정하고
  delegation record가 둘을 모두 결속하게 한다.
- Receipt→ISSUED→handoff positive fixture와 stale/intervening/future-hash
  negatives를 추가한다.

### PRE-P-R006-BLOCKING-007 — APPLICATION RECEIPT PARENT를 0555로 먼저 SEAL해 FILE을 만들 수 없음

#### 근거

Target §12.2 1290~1304행은 receipt parent를 `mkdirat(..., mode0555)`
NOREPLACE로 만든 직후 그 directory에서 `O_TMPFILE`을 열고 `linkat`으로
`application-receipt.json`을 publish한다. 비특권 executor는 mode 0555
directory에 file을 만들거나 link할 write permission이 없다. Target은
privileged capability나 별도 publication actor를 허용하지 않는다.

따라서 absent-parent 정상 경로는 `O_TMPFILE` 또는 `linkat`에서 실패한다.
Existing parent recovery 경로도 directory를 누가 어떤 receipt identity로
미리 만들었는지 정의하지 않으므로 대안이 아니다.

#### Required remediation

- Parent를 private writable mode로 NOREPLACE 생성하고 parent fsync한다.
- Receipt temp write/fsync/link/reopen까지 완료한 뒤 parent를 0555로 chmod,
  directory fsync, reopen하여 final identity를 검증한다.
- Chmod와 final parent verification을 C_ALLOW와 transaction progress schema에
  추가한다.
- Crash cuts마다 writable unsealed parent의 adoption/incident rule을 정의한다.

### PRE-P-R006-BLOCKING-008 — CHECKPOINT ORDINAL 26의 LOST-PROGRESS RECOVERY TRANSITION이 없음

#### 근거

Target §12 1197~1204행은 ordinal 26을
`PREWRITE -> CHECKPOINT_CAS_COMMITTED -> FILE_FSYNCED -> PARENT_FSYNCED`으로
끝내고 checkpoint 뒤 `PREFIX_ADVANCED`를 쓰지 말라고 한다. 반면 §12.1
1242~1258행의 lost-progress reconcile은 inferred member마다 file/parent
fsync와 reopen 뒤 반드시
`RECONCILED_PREFIX -> PREFIX_ADVANCED`를 append한다.

Checkpoint CAS/rename이 실제로 성공한 뒤
`CHECKPOINT_CAS_COMMITTED`, `FILE_FSYNCED` 또는 `PARENT_FSYNCED`가 durable해지기
전에 crash하면 live checkpoint는 after bytes이지만 transaction head는
ordinal 26 이전에 머문다. Second CAS는 금지되고 generic reconcile은 forbidden
post-checkpoint `PREFIX_ADVANCED`를 요구한다. Postcheck가 요구하는
`checkpoint_durable:PARENT_FSYNCED`도 생성할 합법적 predecessor가 없다.

#### Required remediation

- Ordinal 26 전용 `CHECKPOINT_RECONCILED_DURABLE` record/state를 추가한다.
- Higher-epoch recovery가 second CAS 없이 live checkpoint file/parent
  fsync/reopen/hash를 증명하고 그 record를 checkpoint-durable predecessor로
  쓸 수 있게 한다.
- Checkpoint commit 전/후와 progress 각 단계 crash를 구분하는 phase
  adjudication을 정의한다.
- Generic `PREFIX_ADVANCED` algorithm에서 ordinal 26을 제외하고 exact
  checkpoint-specific fixtures를 추가한다.

### PRE-P-R006-BLOCKING-009 — JOURNAL GENESIS / LOCK BOOTSTRAP가 CRASH 한 번으로 BRICK될 수 있음

#### 근거

Target §8의 publisher는 이미 존재하는 final parent에서 `O_TMPFILE`을 여는
것을 전제로 하지만 journal root와 `genesis/records/attempts/reviews/
transactions` parents의 exact mkdirat, ownership/mode, fsync와 bootstrap
authority가 없다.

더 직접적으로 §11 1067~1069행은 lock file을 NOREPLACE 생성·fsync한 뒤 그
physical identity를 genesis에 서명한다고 한다. Lock link/fsync 후 genesis
publish 전에 crash하면 unbound lock inode가 남는다. Restart에서 NOREPLACE
create는 EEXIST이고, existing unsigned lock을 adopt하거나 삭제할 operation은
없으며 create/replace는 이후 금지된다. Genesis를 먼저 publish할 수도
없다. 아직 존재하지 않는 lock의 actual inode를 서명해야 하기 때문이다.

#### Required remediation

- Bootstrap 전용 authority와 root-to-leaf anchored mkdirat/openat2,
  owner/mode, parent fsync 순서를 완전히 정의한다.
- Fresh sibling bootstrap directory에서 lock과 genesis를 만들고 검증한 뒤
  whole bootstrap directory를 NOREPLACE publish하는 방식처럼 crash-atomic한
  protocol을 사용한다.
- 또는 signed bootstrap intent가 orphan lock adoption을 허용하도록 exact
  state와 identity proof를 정의한다.
- Directory/lock/genesis 각 cut의 restart 결과가 absent 또는 one complete
  valid bootstrap만 되도록 crash fixtures를 추가한다.

### PRE-P-R006-BLOCKING-010 — UNCLEAN ORDINARY-C CRASH를 RECOVERY로 넘길 ADJUDICATION이 없음

#### 근거

Target §12.1의 recovery는 곧바로
`RECOVERY_ISSUED -> ... -> FENCE_BOUND`로 시작한다. 그러나 recovery receipt
§10 960행은 `original_consume`과 `original_incident`를 모두 ACTUAL로 요구한다.
Crash한 ordinary executor는 incident close를 publish하지 못하므로
`original_incident`가 없다.

특히 durable handoff 직후 consume 전에 process가 crash하면 C는
`EFFECTIVE_UNCONSUMED`다. §10 FSM은 이 상태의 crash close/adoption을
정의하지 않고, recovery receipt는 존재하지 않는 original consume까지
요구한다. Consume/lease/prepared 뒤 crash에서도 lease expiry, kernel lock
release와 live progress를 누가 어떤 signed record로 incident 판정하는지
없다. Old attempt를 active count에서 제거하는 transition도 없어 recovery와
`active_stage_c_transaction_count<=1`을 함께 만족시킬 수 없다.

#### Required remediation

- `EFFECTIVE_UNCONSUMED`, CONSUMED, LEASED와 PREPARED 각 crash state에 대한
  custodian adjudication transition을 정의한다.
- Original consume/incident를 `ACTUAL | SIGNED_ABSENT(NOT_REACHED)` typed
  union으로 만들고 recovery eligibility를 phase별로 고정한다.
- Same OFD lock 획득, old lease expiry와 live/global/transaction replay 뒤
  original attempt를 durable incident/abandoned state로 닫고 recovery를
  발급하는 exact 순서를 추가한다.
- Handoff 직후부터 every progress cut까지 crash/restart positive fixtures를
  추가한다.

### PRE-P-R006-BLOCKING-011 — AUTHORIZATION LIFECYCLE 전체가 SAME OFD LOCK으로 직렬화되지 않음

#### 근거

Target §11은 handoff publisher, ordinary C executor와 recovery executor가 OFD
lock을 소유한다고 한다. 그러나 A/B/C issue, pending incident close, revoke,
consume/lease/renewal/prepare/terminal close와 crash adjudicator를 모두 같은
lock gate로 통과시키는 normative rule은 없다. `HANDOFF-01` fixture는 pending
close와 delegation이 같은 lock을 쓴다고 기대하지만 §10의 pending-close
operation과 §11 holder list가 그 의무를 정의하지 않는다.

더구나 §10.1의 `C_ALLOW`에는 exact26, transaction numeric stream, exact6 raw와
application receipt만 있고 Stage-C의 필수 global lifecycle numeric-slot
append가 없다. Effective C FSM이 요구하는 consume/lease/prepare/terminal
close와 recovery lifecycle records는 `C_DENY = all - C_ALLOW`에 걸린다.

Numeric slot NOREPLACE는 동일 sequence의 두 files만 직렬화한다. It does not
prevent an independent lifecycle publisher from changing revocation/lease/head
state between executor's pre-guard and target CAS. Post-guard에서 drift를
발견해도 이미 target mutation이 발생했으므로 safety를 복원할 수 없다.

#### Required remediation

- A/B/C/recovery global lifecycle state/head를 바꾸는 모든 publisher가
  `replay -> validate -> append -> parent fsync` 구간 전에 same exact OFD
  lock을 얻도록 한다. Long-running build/validation 자체는 lock 밖에 둔다.
- 위 exact C/recovery lifecycle global numeric-slot append와 parent fsync를
  C_ALLOW/Scope의 allowed operations/roots에 추가한다.
- External revoker를 포함한 actor별 lock acquisition, busy result, lease expiry
  semantics와 lock order를 정의한다.
- Guard-read부터 mutation syscall/progress append까지 lock-held critical
  section임을 state machine에 결속한다.
- Revoke/expiry/pending-close/recovery와 each mutation cut의 races를
  exhaustive fixture로 추가한다.

### PRE-P-R006-BLOCKING-012 — TRANSACTION MANIFEST의 SEQUENCE-ZERO와 FIRST `prior_progress`가 정의되지 않음

#### 근거

Target §8 784행은 transaction stream을 manifest부터 numeric records로
replay한다고 하고 §12 1149~1151행은 signed JCS transaction manifest를
NOREPLACE publish한다고 한다. 그러나 manifest payload exact schema,
signature domain/envelope, mode/fsync/reopen identity와 sequence-zero identity를
정의하지 않는다.

Transaction common fields는 모든 numeric record에
`prior_progress:RecordRef`를 요구한다. First `FENCE_BOUND`의 prior path,
sha256, bytes, sequence와 `record_kind`가 무엇이어야 하는지 없다.
`TRANSACTION_MANIFEST`는 allowed progress kinds에 없고 global genesis의
sequence-zero rule은 transaction stream에 적용되지 않는다. 따라서 첫 record
signature와 contiguous replay를 canonical하게 만들 수 없다.

#### Required remediation

- Transaction manifest의 exact payload/schema, signature domain, envelope와
  atomic publication/verification을 정의한다.
- Manifest를 transaction sequence zero로 지정하고 exact
  `RecordRef{path,hash,bytes,sequence:"000000000000",
  record_kind:"TRANSACTION_MANIFEST"}` formula를 둔다.
- First numeric record의 `prior_progress`가 그 identity와 exact 같고 이후
  chain이 contiguous함을 명시한다.
- Orphan manifest, manifest-before-global-bind crash, tamper와 first-prior drift
  fixtures를 추가한다.

## 4. MAJOR findings

### PRE-P-R006-MAJOR-001 — RUNTIME-PACK INPUT DISCOVERY / ACQUISITION PROVENANCE가 불충분

#### 근거

Target §5.1의 complete tree에는 runtime pack builder/template, tar, closure,
content manifest와 receipt만 있다. §5.1 470~475행은 “full19이 실제 호출하는”
runtime members를 완전 열거한다고 하지만 discovery seed argv, resolver/tracer
identity, allowed acquisition roots, source archive/package identities, dynamic
observation raw evidence와 per-member origin map이 없다.

완성된 pack을 사용하는 §5.2 access trace는 pack을 어떻게 선택·획득했는지의
provenance가 아니다. Static analysis와 observed access 중 무엇이 authority인지,
observation이 allowlist를 확대할 수 있는지, Python/Node/Java dynamic closure를
어떻게 fail-closed하는지도 고정되지 않는다.

#### Required remediation

- Environment별 discovery seed argv/env/cwd와 resolver/tracer executable
  hash/version을 고정한다.
- Allowed source roots와 package/archive/lock `Physical`, raw trace/drop
  counters와 typed edge evidence path를 complete tree에 추가한다.
- Every pack member의 origin source, resolution edge와 selected reason을
  manifest/receipt에 결속한다.
- Observation은 frozen source policy의 누락을 검출할 뿐 allowlist를
  자동 확대하지 못하게 하고 ambiguity/dynamic-load negatives를 추가한다.

### PRE-P-R006-MAJOR-002 — BWRAP MOUNT / WRITABLE-OUTPUT MODEL이 FULL19 BUILD와 맞지 않음

#### 근거

Target §5.2는 worktree를 read-only bind하고 `/out` 외 writable bind를
금지하며 payload는 `/out`에도 쓸 수 없다고 한다. 그러나 full19 #6~#14에는
gateway/web typecheck, test, build와 Android unit/assemble/lint가 있다.
Repository-local build output/cache를 writable scratch로 보내는 literal
argv/env와 mount가 없다. `/tmp`는 tmpfs로 실제 writable인데 “`/out`만
writable”이라는 문장과도 일치하지 않는다.

Synthetic root 전체를 `/runtime` 한 곳에만 bind하므로 ELF `PT_INTERP`와
absolute shebang이 `/lib*`, `/usr/bin` 등을 가리키는 경우 실행할 original
absolute mount 또는 deterministic rewrite contract도 없다. 반대로 raw host
directory를 `/out`에 RW bind하면서 same-uid payload의 write를 막는 uid,
seccomp, fd-only publisher mechanism도 없다.

#### Required remediation

- Slot별 exact writable scratch/build/cache mounts와 tool output redirection
  argv/env를 정의하거나 deterministic COW projection을 사용한다.
- Runtime ELF/shebang absolute lookup을 synthetic paths에 연결하는 exact
  RO bind layout 또는 verified rewrite algorithm을 정의한다.
- Raw evidence는 payload가 열 수 없는 host-side pipe/tracer/fd 또는 separate
  uid publisher로 만들고 `/out` access denial을 증명한다.
- Source worktree pre/post write-zero와 scratch/output allowlist를 raw trace에
  결속한다.

### PRE-P-R006-MAJOR-003 — `application_receipt_target ACTUAL`의 TYPE과 PRE-CREATION STATE가 없음

#### 근거

Target §10 959행은 Stage-C pending receipt에
`application_receipt_target ACTUAL`을 요구하지만 nested type/schema를
정의하지 않는다. `Physical`이라면 receipt issue 시 target과 parent가 아직
없으므로 dev/inode/hash/bytes를 ACTUAL로 채울 수 없다. String path나
expected-target object라면 exact field/type/cardinality와 normalization이 없다.

Transaction manifest의 “application receipt path”와 C_ALLOW exact path가
이 receipt field와 어떤 equality formula로 결속되는지도 없다. Unknown field
shape와 placeholder가 금지되므로 signer/validator canonical bytes가
일치한다고 보장할 수 없다.

#### Required remediation

- `ApplicationReceiptTarget`을 exact path, parent path, required final
  mode/nlink, signature domain, expected content formula와 initial
  `SIGNED_ABSENT` state를 가진 typed object로 정의한다.
- Pending receipt, transaction manifest, C_ALLOW와 final
  `APPLICATION_RECEIPT_FINALIZE` record의 equality formulas를 고정한다.
- Absent-at-issue, expected-existing recovery와 unexpected-existing state를
  canonical union으로 구분한다.

### PRE-P-R006-MAJOR-004 — LOCK PARENT IDENTITY와 “SAME OFD LOCK HELD” PROOF가 정의되지 않음

#### 근거

Target §11은 lock file dev/inode 등을 genesis에 결속하지만
`journal/genesis` parent와 journal root의 Physical identity는 결속하지 않는다.
Acquisition은 `open journal/genesis directory`라고만 하며 root-to-parent
openat2 walk, lstat/fstat equality와 genesis-bound parent identity가 없다.
따라서 `LOCK-01`의 parent replacement를 어떤 값으로 판정하는지 materialize할
수 없다.

Write guard의 “same OFD lock fd is still held by this process”도 exact kernel
check가 없다. OFD ownership은 PID가 아니며 같은 open-file-description의
`F_OFD_GETLK` 결과를 어떻게 해석할지, fd replacement/close-reopen을 어떻게
검출할지 정의되지 않는다.

#### Required remediation

- Journal root/genesis parent의 path/dev/inode/uid/gid/mode/nlink를 genesis에
  결속하고 root fd부터 anchored openat2로 각 component를 검증한다.
- Lock fd number가 아니라 open-file-description continuity를 증명하는 exact
  acquisition token/guard algorithm을 정의한다.
- Parent rename/replacement, fd reuse, explicit unlock, close/reopen와 exec/fork
  negatives를 추가한다.

### PRE-P-R006-MAJOR-005 — POST-WRITE GUARD 실패를 `write0`으로 취급하는 SEMANTICS가 잘못됨

#### 근거

Target §11 1124~1142행은 CAS/NOREPLACE, fsync, progress append와 receipt
finalize 각각의 직전·직후 guard를 요구하고, 하나라도 실패하면
target/checkpoint/application receipt `write0`이라고 한다. Mutation syscall이
성공한 뒤 post-guard가 실패하면 이미 filesystem write가 발생했다. 이를
write0으로 되돌릴 수 없고 rollback도 금지된다.

Concurrent state drift, lease boundary 또는 crash는 “mutation committed but
progress not yet durable” 상태를 만든다. 이를 precondition failure와 같은
write0으로 기록하면 recovery가 live state를 잘못 분류하고 audit count도
거짓이 된다.

#### Required remediation

- Pre-guard failure는 operation0, post-guard failure는
  `MUTATION_MAY_HAVE_COMMITTED`/recovery-required로 분리한다.
- Syscall result, live identity와 durability 단계별 signed evidence를 남기고
  higher-epoch recovery가 reconcile하도록 한다.
- Lease/revoke/head drift를 same-lock critical section으로 막고, unavoidable
  I/O/crash cuts는 per-operation recovery matrix에 넣는다.

## 5. MINOR finding

### PRE-P-R006-MINOR-001 — R006 PHYSICAL PLAN REVIEW 위치 설명이 상충

#### 근거

Target §13 1407~1413행은 R006 plan의 formal/skeptical physical review를
repository 내부 exact paths로 self-exclude한다. 반면 §14 1491~1493행은
“R006 physical plan reviews와 all attempt reviews”가 repository source 밖
external journal에 있다고 말한다. §3의 external journal review paths는
attempt reviews만 열거한다.

실제 physical plan review의 위치와 source exclusion은 §13 쪽이 구체적이지만
§14 문장은 audit 도구와 구현자가 다른 위치를 기대하게 만든다.

#### Required remediation

- R006 physical plan reviews는 repository 내부의 exact self-excluded paths,
  Stage-A/Stage-B attempt reviews만 external journal이라고 문장을 분리한다.
- Physical plan review와 attempt review path classes를 self-check에 별도로
  명시한다.

## 6. 종합 required remediation과 claim ceiling

Add-only successor는 최소 다음 순서로 findings를 닫아야 한다.

1. Logical content equality와 physical materialization identities를 분리하고
   Stage-A builder/receipt/source→target schemas를 완결한다.
2. Full19/exact6 literal argv, consumer map, raw namespaces와 reproducible
   runtime discovery/sandbox를 정의한다.
3. Incident, application target, transaction-manifest sequence zero와 journal
   bootstrap schemas를 materialize 가능한 signed bytes로 고정한다.
4. C expected-head cycle, every C lifecycle lock gate, crash adjudication,
   checkpoint lost-progress와 postwrite recovery state를 하나의 replay 가능한
   FSM으로 닫는다.
5. Exact positive fixtures와 required negative/crash matrix가 위 새 schemas와
   일치하는지 두 independent physical reviews에서 다시 확인한다.

Finding 하나라도 남은 동안 R006의 exact claim ceiling은 다음과 같다.

```text
artifact_closed_equivalent=126/257
artifact_open=131/257
artifact_completion_credit_delta=0
formal_pass=0/279
formal_not_run=279/279
formal_test_credit_delta=0
actual_device_event=0/0
actual_event_credit_delta=0
gate_pass=0/5
gate_not_run=5/5
remaining_gates_waived=false
production_deployment=0
release_status=NOT_ELIGIBLE
approval_credit_delta=0
canonical_gap_backlog=r021/r021
canonical_delta=0
product_credit_delta=0
```

따라서 이 review는 Stage A request를 열지 않는다. R006 add-only successor가
모든 finding을 닫고 formal/skeptical physical review 각각 exact `0/0/0`을
받더라도 authority는 자동 발생하지 않으며, 그 뒤 별도 사용자 승인이
필요하다.
