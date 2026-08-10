# PRE-P Validation Convergence Design/Build Plan R006 독립 공격검수 R001

## 1. 검수 대상과 최종 판정

| 항목 | exact 값 |
|---|---|
| review_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R006-INDEPENDENT-SKEPTICAL-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006.md` |
| target SHA-256 | `4a9f7f21d505bf6cf53d1ea8a16e21e7ebca5c154541d49928d03383b7d23de9` |
| target bytes | `91,165` |
| target lines | `1,972` |
| target type | `regular file, non-symlink, mode=0664, nlink=1` |
| target status | `NON_EFFECTIVE_PLAN_ONLY` |
| verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY` |
| findings | `BLOCKING=8 / MAJOR=2 / MINOR=1` |
| review authority | `NONE` |

검수 시작과 종료 시 target의 SHA-256, bytes, lines와 물리 속성이 위 값과
같음을 확인한다. 이 review는 R006을 수정하지 않았고 formal independent
review를 대체하지 않는다. 또한 candidate/external environment build,
authority bootstrap·issue·consume, exact26 apply, checkpoint 전환, application
receipt 생성, successor plan 작성 또는 P 단계 실행을 허가하지 않는다.

```text
VERDICT=REJECTED_NON_EFFECTIVE_PLAN_ONLY
BLOCKING=8
MAJOR=2
MINOR=1
TARGET_UNCHANGED=true
R006_REMAINS_NON_EFFECTIVE_PLAN_ONLY=true
PLAN_EXECUTION_AUTHORIZED=false
AUTHORITY_BOOTSTRAP_AUTHORIZED=false
STAGE_A_REQUEST_ALLOWED=false
STAGE_A_AUTHORIZED=false
STAGE_B_AUTHORIZED=false
STAGE_C_AUTHORIZED=false
RECOVERY_STAGE_C_AUTHORIZED=false
AUTHORITY_JOURNAL_WRITE_AUTHORIZED=false
ACTIVE_WRITE_AUTHORIZED=false
CHECKPOINT_WRITE_AUTHORIZED=false
APPLICATION_RECEIPT_WRITE_AUTHORIZED=false
SUCCESSOR_BUILD_AUTHORIZED=false
P_APPLY_AUTHORIZED=false
OFFICIAL_CREDIT_DELTA=0
```

## 2. 범위, 방법과 독립 재계산

Target 전체 1,972행과 current source/checkpoint를 read-only로 다음 축에서
대조했다.

- R005 formal/skeptical finding을 R006 단독 계약이 실제로 폐쇄했는지
- authority journal의 최초 물리 생성부터 record publication까지 실행 가능한지
- synthetic runtime pack을 mount한 sandbox에서 실제 executable이 시작되는지
- full19의 writable scratch와 raw evidence가 payload로부터 격리되는지
- A/B/C/recovery receipt와 journal tagged union을 모순 없이 serialize할 수 있는지
- B→C handoff의 expected-head가 receipt/ISSUED hash cycle 없이 만족되는지
- effective-unconsumed C와 incident/recovery가 모든 crash 지점에서 닫히는지
- exact26 apply, checkpoint reconcile과 application receipt가 durable한지
- current/future runner inventory와 exact26 count가 독립 산술과 일치하는지
- official completion claim이 현재 증거보다 앞서가지 않는지

현재 filesystem에서는 다음 세 future parent가 실제로 부재했다.

```text
ABSENT /home/ddobagi/.local/share/hanium-dreamup/
       walksafe-pre-p-authority-r006/
ABSENT docs/control/execution/goal-gates/
       WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001/
ABSENT docs/control/goals/walksafe-completion-graph-v2-4-1/
```

R006에는 auxiliary parent transition 계약이 없다. 이 review에서
`AUX_PARENT_001`은 세 번째 absent path를 exact26 ordinal 15 전에 만드는
remediation-local 이름일 뿐 R006의 기존 token이나 authority가 아니다. 각
부재를 별도 아홉 번째 BLOCKING으로 중복 계산하지 않는다. Authority의
auxiliary publication parent bootstrap 부재는 `BLOCKING-001`, application
receipt parent 생성·권한 모순은 `BLOCKING-007`, exact26 source→target과
`AUX_PARENT_001` materialization map 부재는 `MAJOR-002`로 귀속한다.

§17 exact26 표는 다음 산술에 대해서는 정확했다.

```text
ACTIVE_TARGET_COUNT=26
CAS_REPLACE=2
NOREPLACE=23
CHECKPOINT_CAS_LAST=1
DUPLICATE_PATH_COUNT=0
APPLICATION_RECEIPT_EXCLUDED_FROM_EXACT26=true
```

Current와 successor runner registry도 독립 파싱 결과 다음과 일치했다.

```text
CURRENT_DISCOVERED=132
SUCCESSOR_DISCOVERED=132
SUCCESSOR_ASSIGNED=28+23+7+3+53+16+2=132
SUCCESSOR_UNASSIGNED=0
RUNNER_ALL_DISCOVERED=28+23+7+16=74
RUNNER_ALL_DIRECT=2
RUNNER_ALL_EXECUTION=76
EXCLUDED_DISCOVERED=3+53+2=58
```

`S=603`, `C∩S={scripts/run_walksafe_test_layers_20260711.sh}`,
`C-S={tests/requirements.lock}`, `A∩S=∅`, `M_after=627`도 일치했다. §13의
path-set 공식을 독립 재계산한 값은:

```text
762a4f9b487bba0177c72127c8bfa7211ca9900845452df2a02b1d9bad850c4d
```

로 target 기재값과 같았다. 아래 finding은 이 산술을 반박하는 것이 아니라,
그 미래 bytes를 실제로 build·검수·apply·recover하는 실행 계약이 아직
materializable하지 않다는 판정이다.

## 3. BLOCKING findings

### PRE-P-R006-BLOCKING-001 — 최초 authority journal bootstrap 계약이 없음

#### 근거

Target §8 730~776행은 exact authority tree와 최종 record publisher를
정의한다.

```text
walksafe-pre-p-authority-r006/journal/
  genesis/
  records/
  attempts/stage-{a,b,c}/
  attempts/recovery-stage-c/
  reviews/stage-{a,b}/
  transactions/<transaction-id>/records/
```

그러나 publisher의 첫 동작은 이미 존재하는 final parent 안에서
`O_TMPFILE`을 여는 것이다. §11 1060행 이후도 이미 존재하는
`journal/genesis`에서 lock을 만들고 genesis에 결속하는 과정부터 시작한다.
현재 `walksafe-pre-p-authority-r006` root 자체가 없다.

R006에는 다음 bootstrap 계약이 없다.

- authority root, `journal`, `genesis`, `records`, `attempts`, `reviews`,
  `transactions`와 stage별 parent의 exact 생성 순서
- 각 parent의 creator, uid/gid, 초기·최종 mode, nlink와 symlink/type guard
- `mkdirat` NOREPLACE/기존 디렉터리 검증 분기와 실패 시 persistent write count
- child 생성 뒤 각 parent와 조상 parent를 fsync하는 순서
- lock→genesis→first global record의 물리 publication/fsync 순서와
  partial-crash recovery
- bootstrap을 누가, 어느 authority로, Stage A request 전 어느 범위까지 수행하는지
- incident 및 기타 auxiliary/side-output parent의 path와 materialization 절차

즉 genesis 또는 최초 request/receipt를 만들려면 R006에 없는 operation과
path 결정을 추가해야 한다. §1의 단독 normative plan/no implicit operation
원칙 아래에서는 실행자가 그 결정을 임의로 보충할 수 없다.

#### Required remediation

- successor에 `BOOTSTRAP_UNINITIALIZED`에서 시작하는 exact state machine을 둔다.
- root부터 모든 고정 parent와 attempt/transaction 동적 parent까지 path,
  parent fd, `mkdirat` flags, uid/gid, initial/final mode, expected nlink와
  fsync 순서를 표로 고정한다.
- existing path는 `openat2`/`O_NOFOLLOW`와 lstat/fstat identity로 검증하고,
  partial tree, wrong owner/mode/type, symlink와 unexpected child를 fail-stop한다.
- lock file을 NOREPLACE·fsync한 뒤 genesis가 그 Physical을 서명하고, genesis
  fsync 뒤에만 first global numeric record를 허용한다.
- bootstrap scope는 journal 구조 생성만 허용하고 Stage A build/apply 권한과
  분리한다. crash 지점별 replay/adopt 또는 fail-stop 규칙을 추가한다.

### PRE-P-R006-BLOCKING-002 — `/runtime` 단일 bind로는 packed runtime을 실행할 수 없음

#### 근거

§5.1 470~485행은 ELF interpreter, `DT_NEEDED`, shebang interpreter와 Python,
Node, Java/Android closure를 pack한다고 한다. 그러나 §5.2 487~521행의 허용
mount는 pack root를 `/runtime`에 한 번 bind하는 것뿐이고 host `/usr`, `/bin`,
`/lib*`, home bind를 명시적으로 금지한다.

Linux kernel과 dynamic loader는 manifest의 논리 edge가 아니라 executable에
기록된 absolute path를 연다. Current의 실제 예는 다음과 같다.

```text
scripts/run_walksafe_test_layers_20260711.sh -> #!/usr/bin/env bash
scripts/check_walksafe_project_continuation_v2_4.py -> #!/usr/bin/env python3
/usr/bin/node PT_INTERP -> /lib64/ld-linux-x86-64.so.2
/usr/lib/jvm/java-21-openjdk-amd64/bin/java
  PT_INTERP -> /lib64/ld-linux-x86-64.so.2
current python3 PT_INTERP -> /home/linuxbrew/.linuxbrew/lib/ld.so
```

`/runtime/bin/env`로 최초 process를 시작해도, 그 process가 직접 실행하는
script의 `#!/usr/bin/env` 또는 ELF의 `/lib64/...`와 `/home/linuxbrew/...`
lookup은 원래 absolute namespace에서 일어난다. R006은 그 절대 위치를
synthetic mount로 제공하지 않고 executable을 relocatable하게 patch/rewrite
하는 결정론적 builder도 정의하지 않는다. 따라서 full19의 dynamic runtimes는
host fallback 0을 지키면서 시작할 수 없다.

#### Required remediation

- 두 environment pack이 필요로 하는 absolute `/usr/bin`, `/bin`, `/lib`,
  `/lib64`, JDK/SDK와 interpreter prefix를 synthetic subtree로 만들고 각
  original absolute path에 최소 read-only bind하는 exact mount table을 둔다.
- 또는 shebang과 ELF interpreter/RPATH를 결정론적으로 rewrite하는 builder,
  before/after hash, 재현성 검증과 허용 transformation을 완전히 명시한다.
- 어느 방식이든 broad host bind는 금지하고, executable별 resolved interpreter,
  loader, library와 data path를 access trace로 증명한다.
- shebang, ELF PT_INTERP, recursive `DT_NEEDED`, Python subprocess, Node,
  Java/Gradle과 Android tool invocation을 각각 실제 spawn하는 negative/positive
  fixture를 추가한다.

### PRE-P-R006-BLOCKING-003 — read-only worktree와 payload-writable `/out`이 full19와 evidence 무결성을 동시에 깨뜨림

#### 근거

§5.2 498~500행은 worktree 전체를 read-only로 bind하고 raw invocation
directory 전체를 `/out`에 read-write bind한다. 516~518행은 `/out`만
writable이며 “result publisher 외 payload가 write할 수 없다”고 주장하지만,
같은 invocation namespace·uid의 payload가 `/out`을 보지 못하거나 쓰지 못하게
하는 mount, fd, uid 또는 syscall boundary가 없다.

첫째, full19 §7의 gateway test/build, web test/lint/typecheck/build와 Android
unit/assemble/lint는 일반적으로 worktree 아래 build, cache, generated output을
만든다. R006은 각 tool의 output/cache/temp를 writable scratch로 redirect하는
exact argv/env 또는 selective overlay를 정의하지 않는다. worktree read-only와
`HOME=/home/sandbox` directory도 writable backing 계약이 없어 실제 run이
실패한다.

둘째, `/out`에는 다음 신뢰 evidence가 함께 놓인다.

```text
intent.json
stdout.bin
stderr.bin
access-trace.raw
access-trace.json
result.json
```

payload가 `/out`을 쓰거나 기존 파일을 교체할 수 있으므로 intent, trace 또는
result를 성공처럼 위조할 수 있다. R006의 tracer detach/overflow 검사는
evidence publisher와 payload 사이의 write isolation을 대신하지 못한다.

#### Required remediation

- full19 command별 writable path를 사전 열거하고 worktree의 최소 scratch
  subtree에 tmpfs/overlay를 제공하거나 output/cache를 별도 scratch로 정확히
  redirect한다.
- stdout/stderr는 payload-visible path가 아니라 host supervisor가 소유한 pipe
  fd로 수집하고, trace/result/intent는 payload가 접근할 수 없는 namespace 또는
  별도 uid의 publisher가 쓴다.
- payload에는 필요하면 write-only result fd만 전달하고 evidence final parent는
  mount하지 않는다. publisher는 tmp fsync→NOREPLACE→parent fsync→reopen으로
  봉인한다.
- same-uid payload의 `/out` tamper, rename, unlink, symlink, tracer kill과
  worktree write 시도를 negative fixture로 고정한다.

### PRE-P-R006-BLOCKING-004 — A/B receipt의 transaction과 scope를 exact schema로 만들 수 없음

#### 근거

§9 843~847행은 journal record의 `transaction_binding`을 명확한 union으로
정한다.

```text
A/B -> Absent(reason:"STAGE_HAS_NO_TRANSACTION")
C/recovery -> {transaction_id, transaction_manifest:Physical}
```

반면 §10 936~950행의 receipt common fields는 모든 stage에 literal
`transaction_id`를 필수로 둔다. A/B에서 이 field가 빠져도, null/empty여도
exact schema 위반이고, 실제 transaction ID를 넣으면 “A/B has no
transaction”과 충돌한다. receipt에는 ACTUAL/SIGNED_ABSENT union이 없다.

또한 `scope:Scope`는 common ACTUAL이고 `Scope`는 allowed/denied operations와
roots를 요구하지만, §10.1은 C_ALLOW만 정의한다. Stage A와 B가 candidate,
external environment, runtime pack, raw, review, resolved subject와 journal의
어느 exact path에 어떤 create/seal operation을 수행할 수 있는지 normative
arrays가 없다. §10의 stage additional table은 subject binding의
ACTUAL/SIGNED_ABSENT만 말할 뿐 A/B scope를 채우지 못한다.

따라서 최초 A/B receipt를 strict JCS exact schema로 발행하는 단계부터
non-materializable하다.

#### Required remediation

- receipt common의 `transaction_binding`을 journal과 동일한 tagged union으로
  바꾸고 A/B에는 exact SIGNED_ABSENT reason, C/recovery에는 manifest Physical을
  요구한다. bare `transaction_id` common field는 제거한다.
- A/B/C/recovery 각각에 exact allowed operations/roots와 denied
  operations/roots를 literal canonical arrays로 정의한다.
- A/B scope에는 build-01/02, candidate, raw, external materialization,
  review/subject publication과 journal append를 creator별로 분리한다.
- stage×field matrix와 schema fixture로 missing/null/empty/unknown/foreign-stage
  key를 모두 rc2로 검증한다.

### PRE-P-R006-BLOCKING-005 — C receipt expected head와 ISSUED publication 사이에 hash cycle이 생김

#### 근거

§9 853행의 `ISSUED` record는 `receipt:Physical`을 필수로 가진다. 따라서 receipt
bytes가 먼저 완성·publish되어야 ISSUED payload/hash를 만들 수 있다. §10
941행은 receipt에 `expected_global_head:RecordRef`를 요구하고, §10.1
1037~1046행의 handoff gate는 C receipt의 expected head가 handoff 시 current
compatible head와 같아야 한다.

발행 직전 head를 `H0`, 그 receipt를 결속한 C `ISSUED` record를 `H1`이라 하면:

```text
receipt.expected_global_head = H0
publish ISSUED(receipt:Physical) -> current head = H1
handoff requires receipt.expected_global_head == current head
H0 != H1
```

가 되어 정상 발행만으로 receipt가 stale해진다. receipt에 미래 `H1`을 넣으면:

```text
receipt hash -> ISSUED payload/hash -> receipt expected H1 -> receipt hash
```

의 순환 참조가 생겨 어느 bytes도 먼저 확정할 수 없다.

#### Required remediation

- receipt에는 `issuance_expected_predecessor=H0`를 결속하고 ISSUED의
  `prior_record`가 정확히 H0인지 검증한다.
- handoff에서는 receipt expected predecessor가 C ISSUED의 prior와 같고,
  current head가 C ISSUED 또는 명시적으로 허용된 compatible descendant
  chain인지 검증한다. 단순 current-head equality를 제거한다.
- B PREPARED/C ISSUED/lease 또는 renewal descendant의 허용 순서를 exact
  state predicate로 정의한다.
- future-head injection, intervening foreign record, stale receipt, reissued
  attempt와 pair digest mismatch fixture를 추가한다.

### PRE-P-R006-BLOCKING-006 — incident Physical과 effective-unconsumed C의 종료·복구 경로가 없음

#### 근거

§9 860행과 867행은 `CLOSED_INCIDENT`와
`RECOVERY_CLOSED_INCIDENT`에 `incident:Physical`을 필수로 요구한다. 그러나
R006에는 incident file의 exact path/schema, creator, publication 순서,
mode/ownership/fsync와 A/B/C/recovery scope 안의 allowed incident write가 없다.
따라서 incident를 기록해야 하는 모든 실패 경로가 정의되지 않은 auxiliary
operation을 필요로 한다.

별도로 §10 992~1000행의 effective FSM과 §10.1 1046~1051행의 handoff
after-state를 합치면 C handoff 직후 상태는 `EFFECTIVE_UNCONSUMED`이지만,
C effective incident transition은
`CONSUMED|LEASED|PREPARED -> CLOSED_INCIDENT`만 허용한다. 다음 crash가
정상적으로 발생할 수 있다.

```text
DELEGATED_AND_CLOSED durable
-> C EFFECTIVE_UNCONSUMED
-> crash before CONSUME_CLAIMED
-> receipt expires or executor is lost
```

이 상태에서는 original C를 incident-close할 수 없다. §10 960행의 recovery
receipt는 `original_consume`와 `original_incident`를 ACTUAL로 요구하므로
consume 전 crash에서 recovery도 발행할 수 없다. original이 terminal하지
않아 fresh ordinary C retry chain도 시작할 수 없다.

#### Required remediation

- stage/attempt별 incident evidence exact parent/path/schema와 signed publication,
  Physical identity, creator, initial/final mode와 fsync 절차를 정의하고 각
  scope에 최소 operation을 허용한다.
- C effective FSM에
  `EFFECTIVE_UNCONSUMED -> CLOSED_INCIDENT`를 추가하고 one-use receipt expiry,
  revocation과 custodian loss의 exact close reason을 둔다.
- recovery receipt의 original binding은 crash phase에 따라
  `original_consume: ACTUAL | SIGNED_ABSENT(NOT_REACHED)`와
  `original_incident: ACTUAL`을 허용한다.
- original attempt를 signed terminal incident로 먼저 닫은 뒤에만 recovery
  또는 fresh C를 issue하도록 순서를 고정한다.
- handoff fsync 직후, consume append 전후, receipt expiry와 incident
  publication 각 crash 지점을 fault-inject한다.

### PRE-P-R006-BLOCKING-007 — application receipt parent를 mode 0555로 만들면 receipt를 쓸 수 없음

#### 근거

§12.2 1289~1304행의 exact sequence는 현재 부재한 receipt parent를:

```text
mkdirat exact receipt parent mode0555 NOREPLACE
-> open receipt parent
-> O_TMPFILE
-> write/fsync
-> linkat NOREPLACE
```

순서로 만든다. 동일 uid의 process가 mode 0555 directory 안에서 `O_TMPFILE`을
만들고 final name을 link하려면 directory write 권한이 필요하다. parent를
처음부터 0555로 만들면 `O_TMPFILE` 또는 `linkat`이 `EACCES`로 실패한다.
현재 exact receipt parent가 실제로 부재하므로 “existing expected directory”
분기로 우회할 수도 없다.

parent를 임의로 0755/0700으로 만들거나 나중에 chmod하면 R006의 exact
sequence와 C_ALLOW에 없는 operation이 된다. checkpoint와 exact6가 이미
성공한 뒤 receipt finalization만 영구 불가능한 상태가 된다.

#### Required remediation

- private initial mode 0700 등 exact writable mode로 parent를 NOREPLACE 생성하고
  owner/type/nlink를 검증한다.
- O_TMPFILE write/fchmod/fsync/link, parent fsync와 receipt reopen 검증 뒤
  parent를 exact final 0555로 `fchmod`하고 다시 fsync/reopen 검증한다.
- `PARENT_CREATE`, `RECEIPT_PUBLISH`, `PARENT_SEAL` operation과 exact path를
  C_ALLOW 및 recovery scope에 포함한다.
- crash-before-link, after-link-before-parent-fsync, after-link-before-seal,
  existing sealed parent+expected receipt와 wrong-mode parent fixture를 추가한다.

### PRE-P-R006-BLOCKING-008 — checkpoint CAS 직후 crash를 reconcile하는 합법적 progress state가 없음

#### 근거

§12 1197~1204행은 ordinals 1..25에는 `PREFIX_ADVANCED`를 쓰지만 checkpoint
ordinal26 뒤에는 그것을 쓰지 않고 final `PARENT_FSYNCED`가 durable prefix
1..26을 증명한다고 정한다. 정상 경로만 보면 일관된다.

그러나 checkpoint rename/CAS가 filesystem에 반영된 뒤
`CHECKPOINT_CAS_COMMITTED`, `FILE_FSYNCED` 또는 `PARENT_FSYNCED` record가
durable하기 전에 crash할 수 있다. §12.1 1242~1258행의 유실 progress
reconciliation은 모든 inferred member에:

```text
RECONCILED_PREFIX
-> PREFIX_ADVANCED
```

를 쓰도록 한다. checkpoint가 inferred member이면 이 절차는
“checkpoint 뒤 PREFIX_ADVANCED 금지”와 충돌한다. 이를 따르지 않으면 live
checkpoint는 seq40 bytes인데 transaction stream은 ordinal25에 머무는 상태를
합법적으로 승격할 record가 없다. second checkpoint CAS는 recovery deny이고,
rollback도 금지되어 실행은 영구 중단된다.

#### Required remediation

- checkpoint 전용 `CHECKPOINT_RECONCILED_DURABLE` kind와 exact fields/state를
  추가한다.
- live checkpoint lstat/fstat/hash/bytes/mode/nlink, file fsync, parent fsync,
  reopen identity와 prior durable prefix 1..25를 이 record가 증명하게 한다.
- 이 path에서는 second CAS와 generic `PREFIX_ADVANCED`를 금지하고,
  `POSTCHECK_PASSED.checkpoint_durable`가 정상 `PARENT_FSYNCED` 또는
  `CHECKPOINT_RECONCILED_DURABLE` union을 받게 한다.
- checkpoint rename 전/후, commit record 전/후, file fsync 전/후, parent
  fsync 전/후의 crash matrix를 모두 고정한다.

## 4. MAJOR findings

### PRE-P-R006-MAJOR-001 — ISSUED 전 receipt/transaction manifest orphan을 replay가 소유하지 않음

#### 근거

§9 853행 때문에 attempt receipt는 ISSUED보다 먼저 physical publication되어야
한다. §12 1149~1151행의 transaction manifest도 C journal binding과 progress
stream보다 먼저 NOREPLACE publish된다. 다음 crash가 가능하다.

```text
receipt or transaction-manifest linked+parent-fsynced
-> crash before corresponding global ISSUED/RECOVERY_ISSUED
```

이 file은 immutable physical artifact지만 global replay에는 reservation,
adoption, closure 또는 incident record가 없다. 동일 attempt/transaction path를
retry하면 NOREPLACE에 막히고, 새 key를 선택하면 orphan이 attempt ordinal,
retry predecessor와 max-attempt accounting 밖에 남는다. bootstrap inventory가
이를 어떤 signed state로 닫는지도 없다.

#### Required remediation

- global journal에 attempt/transaction reservation을 먼저 publish하고 winner만
  receipt/manifest를 publish하도록 two-phase state를 둔다.
- 또는 deterministic orphan inventory가 exact expected bytes/signature를
  검증해 ISSUED로 adopt하거나 signed incident terminal로 닫게 한다.
- reservation 전/후, physical link 전/후, parent fsync 전/후와 ISSUED append
  전/후 crash를 replay test로 고정한다.
- 모든 physical attempt/transaction artifact가 exactly one global lifecycle에
  속하고 attempt limits에 포함됨을 invariant로 검증한다.

### PRE-P-R006-MAJOR-002 — exact26 source→target materialization map이 normative하지 않음

#### 근거

§4 389~419행은 future candidate tree와 manifest가 존재할 것이라고 말하고,
§17 1714~1755행은 26개 final destination과 `CAS_REPLACE`, `NOREPLACE`,
`CHECKPOINT_CAS_LAST` operation만 열거한다. 그러나 R006 본문에는 각 ordinal의:

- exact candidate source-relative path
- source file hash/bytes를 만드는 builder/template identity
- destination final uid/gid/file mode/nlink와 executable bit
- source→destination copy/materialize operation과 fresh inode 조건
- parent가 absent일 때 누가 어떤 mode로 만들고 seal하는지

가 한 표로 고정되어 있지 않다. §17의 `mode` 열은 filesystem permission이
아니라 replace operation이다. 실행자는 후보 tree의 여러 output 중 무엇을
target byte로 선택하고 어떤 final permission으로 materialize할지 R006 밖에서
결정해야 한다.

Authority auxiliary publication parents와 application receipt parent의
materialization 누락은 각각 `BLOCKING-001`과 `BLOCKING-007`에서 이미
평가했으며, 이 MAJOR에 다시 합산하지 않는다.

또한 §17 ordinal 15~25의 공통 final parent인:

```text
docs/control/goals/walksafe-completion-graph-v2-4-1/
```

는 현재 absent다. §17은 그 아래 파일 11개를 `NOREPLACE`로 만들도록 하지만,
ordinal 14와 15 사이에 parent directory를 생성·fsync·reopen하는 operation이나
progress kind가 없다. 이 review는 그 누락된 보조 전이를
`AUX_PARENT_001`이라고 부른다. 이 이름은 finding을 추가하거나 active target
count 26을 27로 바꾸지 않고, exact26 materialization map의 누락을 특정한다.

#### Required remediation

- 26-row normative table에 ordinal, candidate source, final target, builder/
  template binding, operation, before identity, final uid/gid/mode/nlink,
  executable과 parent policy를 모두 넣는다.
- candidate manifest가 이 table을 exact row-for-row 결속하고 Stage B review와
  Stage C receipt가 같은 digest를 참조하게 한다.
- durable prefix 1..14 뒤와 ordinal 15 전에 review-local `AUX_PARENT_001`에
  해당하는 `MKDIRAT_NOREPLACE` 보조 전이를 둔다. before SIGNED_ABSENT,
  final directory uid/gid/mode/nlink, parent fsync와 reopen identity를 고정하고
  active file target count는 26으로 유지한다.
- unmapped source, duplicate source/target, permission drift, executable-bit drift,
  missing parent mapping을 pre-spawn rc2로 검증한다.

## 5. MINOR finding

### PRE-P-R006-MINOR-001 — physical plan review의 저장 위치 선언이 서로 모순됨

#### 근거

§13 1405~1420행의 repository source snapshot self-exclusions에는 R006 formal과
skeptical review의 repository-relative paths가 literal로 들어 있다. 반면 §14
1491~1493행은 “R006 physical plan reviews와 all attempt reviews”가 repository
source 밖 external journal에 있다고 선언한다.

실제 이 skeptical review의 요구 위치도 §13에 적힌 repository path다. 두
선언을 동시에 만족시킬 수 없지만, plan review를 source snapshot에서
명시적으로 제외하면 managed bytes/apply 안전성 자체는 유지할 수 있어
MINOR로 분류한다.

#### Required remediation

- R006 formal/skeptical plan reviews는 repository path의 physical
  non-authoritative review이며 exact self-exclusion이라고 명시한다.
- Stage A/B candidate/resolved attempt reviews만 external authority journal에
  존재한다고 범위를 분리한다.
- review class별 path, authority, source snapshot 포함 여부와 V1 consumer
  여부를 한 표로 고정한다.

## 6. 확인된 비-finding과 official claim ceiling

다음 값은 공격검수에서도 모순을 찾지 못했으며 successor가 보존해야 한다.

```text
CURRENT_CONTROL_VERSION=v2.4
CURRENT_EVENT_SEQUENCE=39
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

R006의 future `132/132/0`, two-env `19/19`, exact6, seq40/v2.4.1,
application receipt와 exact26은 모두 아직 planned target이지 actual result가
아니다. 따라서 이 review 이후에도:

```text
ARTIFACT_CREDIT_DELTA=0
FORMAL_CREDIT_DELTA=0
DEVICE_EVENT_CREDIT_DELTA=0
GATE_CREDIT_DELTA=0
DEPLOYMENT_DELTA=0
CHECKPOINT_DELTA=0
CANONICAL_POINTER_DELTA=0
```

이다. R006 review 자체로 artifact 126, formal 0 또는 gate 0을 올릴 수 없다.

## 7. successor 수용 조건

Successor plan은 문장 보강이 아니라 다음 executable contract를 자기 문서 또는
정확히 결속한 immutable normative appendix에 포함해야 한다.

1. authority/AUX tree bootstrap과 crash-safe genesis
2. absolute runtime namespace 또는 deterministic executable relocation
3. command별 writable scratch와 payload-unwritable evidence capture
4. A/B/C/recovery receipt transaction union과 literal scope
5. predecessor-based C issuance/handoff head predicate
6. incident Physical publication과 effective-unconsumed terminal/recovery path
7. writable-create→receipt publish→0555 seal application parent protocol
8. checkpoint-specific reconcile durable record
9. receipt/manifest reservation·adoption·orphan closure
10. exact26 source→target identity와 auxiliary parent materialization tables
11. plan review와 attempt review location/authority 분리

그 successor를 다시 독립 검수해 `BLOCKING=0 / MAJOR=0 / MINOR=0`이 되기
전에는 authority request, Stage A/B/C, recovery 또는 active apply를 시작하면
안 된다.

## 8. 최종 판정

R006은 R005보다 journal numeric slot, stage FSM, C_ALLOW, fencing, exact26,
checkpoint-last와 claim ceiling을 크게 구체화했다. 그러나 최초 journal을
만드는 단계, runtime을 실제로 spawn하는 단계, evidence를 payload에서
격리하는 단계, C authority를 serialize/handoff하는 단계와 checkpoint 이후
recovery에서 각각 실행을 막는 모순이 남아 있다.

따라서 최종 판정은 다음으로 고정한다.

```text
REJECTED_NON_EFFECTIVE_PLAN_ONLY
BLOCKING=8
MAJOR=2
MINOR=1
TARGET_UNCHANGED=true
OFFICIAL_CREDIT_DELTA=0
NEXT_ALLOWED_ACTION=SUCCESSOR_PLAN_REMEDIATION_AND_INDEPENDENT_REVIEW_ONLY
```
