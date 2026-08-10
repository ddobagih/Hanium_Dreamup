# PRE-P Validation Convergence Design/Build Plan R005

## 1. immutable predecessor와 claim ceiling

| 항목 | exact 값 |
|---|---|
| document_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R005` |
| status | `NON_EFFECTIVE_PLAN_ONLY` |
| 작성일 | `2026-07-31` |
| R004 plan path | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R004.md` |
| R004 plan SHA / bytes / lines | `f8392f525df655d519b39be9cc08557bd0b817975235cf26a41eadf541eb6bf4` / `45964` / `928` |
| R004 formal review path | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R004-independent-review-r001.md` |
| R004 formal review SHA / bytes / lines | `0ed279885624b7f9b06efb41e10758a541bb5abed7ceb4e2afec0eac4af60f7e` / `19637` / `419` |
| R004 formal verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY; BLOCKING=1 / MAJOR=2 / MINOR=1` |
| R004 skeptical review path | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R004-independent-skeptical-review-r001.md` |
| R004 skeptical review SHA / bytes / lines | `1da796214c656f599399a7544b529f6a43eb6291b861ddbeae26fc81a3d44139` / `17673` / `375` |
| R004 skeptical verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY; BLOCKING=4 / MAJOR=2 / MINOR=0` |
| R003 physical verdict literal | `REJECTED_NON_EFFECTIVE_PLAN_ONLY` |
| P17 | `P17_BUILD_DEFERRED` |

R004와 두 review, R001~R003와 reviews, 기존 R007과 daylog는 immutable
history-only다. 이 문서는 그것들을 수정·실행하지 않는다.

### 1.1 normative completeness

R005만 future PRE-P execution의 normative plan이다. R004 또는 그 이전 plan을
normative merge/import하지 않는다. R004에서 유지할 B2/B3/M1과 exact26은 이
문서 §12~§16에 다시 완전히 열거했다. 다음 R004 clauses는 명시적으로
`SUPERSEDED_NON_NORMATIVE`다.

- fixed `-001` A/B/C attempt, receipt, resolved root와 `r001` attempt review
- repo 내부 Stage-B overall review
- stage future fields를 함께 요구한 common receipt schema
- ordinary one-use C receipt를 재사용한 prefix resume
- fixed first renewal만 둔 lease model
- mutable/implicit journal head 또는 replay 없는 close
- C0 high-level managed description과 physical object 없는 V1
- Stage-C two-env full19 rerun 또는 hosted-only Stage-B restriction

R005에 없는 execution operation/path/schema는 금지다. history document는
predecessor fingerprint와 finding provenance로만 읽는다.

공식 exact current/after ceiling:

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

v2.4→v2.4.1 control 전환은 위 completion/approval/product credit를 바꾸지
않는다. event, checkpoint, Stage-B results, Stage-C exact6와 application
receipt가 모든 field를 exact number/string으로 재검증한다. percentage,
missing field, implicit PASS와 v2.4.1 activation의 artifact credit 전환은 rc2다.

```text
STATUS=NON_EFFECTIVE_PLAN_ONLY
CURRENT_AUTHORITY=ABSENT_DENY_ALL
STAGE_A_AUTHORITY=ABSENT_DENY_ALL
STAGE_B_AUTHORITY=ABSENT_DENY_ALL
STAGE_C_AUTHORITY=ABSENT_DENY_ALL
STAGE_D_AUTHORITY=ABSENT_DENY_ALL
STAGE_E_AUTHORITY=ABSENT_DENY_ALL
STAGE_F_AUTHORITY=ABSENT_DENY_ALL
STAGE_G_AUTHORITY=ABSENT_DENY_ALL
ACTIVE_WRITE_ALLOWED=false
AUTHORITY_JOURNAL_WRITE_ALLOWED=false
STAGE_A_REQUEST_ALLOWED=false
```

## 2. current source baseline

| 기준 | exact 값 |
|---|---|
| package | `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4`, `ACTIVE` |
| checkpoint | `docs/control/walksafe-project-continuation-checkpoint.json` |
| checkpoint SHA / bytes / schema | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` / `1329415` / `1.25.0` |
| transition tail | seq `39`, `c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a` |
| managed source | count `603`, path `e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1`, content `69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a` |
| discovery | discovered `132`, assigned `127`, orphan `5` |
| regression lineage | A `450 PASS / 7 FAIL`; B `241 intended / NOT_RUN` |

## 3. exact A~G authority stages

| stage | exact token | scope |
|---|---|---|
| A | `PRE_P_ATTEMPT_SCOPED_CANDIDATE_AND_ENV_BUILD_ONLY` | one fresh candidate/env attempt root; active/resolved write0 |
| B | `PRE_P_ATTEMPT_SCOPED_RESOLVE_AND_VALIDATE_ONLY` | one fresh sibling resolved attempt, two-env validation; A/active write0 |
| C | `PRE_P_EXACT26_ATOMIC_APPLY_ONLY` | pending receipt가 atomic handoff로 effective된 exact transaction |
| D | `POST_SEQ40_R007_SUCCESSOR_DESIGN_REVIEW_ONLY` | committed seq40 기반 add-only R007 successor |
| E | `P_CANDIDATE_BUILD_REVIEW_ONLY` | P seq40→41 candidate |
| F | `P_ATTEMPT_SCOPED_RESOLUTION_ONLY` | P envelope resolve |
| G | `P_EXACT_RESOLVED_ATOMIC_APPLY_ONLY` | exact P apply |

현재 모두 `ABSENT_DENY_ALL`이다. R005 physical independent review가 exact
`0/0/0`을 얻은 뒤에만 Stage A request 하나를 사용자에게 묻는다.

## 4. attempt-scoped sibling namespaces

exact base roots:

```text
candidate base =
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r005/
resolved base =
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-authority-resolved-r005/
external env base =
  /home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-validation-r005/
authority journal =
  /home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-authority-r005/journal/
```

attempt key grammar:

```text
<attempt-key> = <12 decimal digit attempt sequence>-<fresh challenge id>
challenge id = lowercase base32 SHA-256 challenge digest, 52 chars
```

literal `-001`, fixed `r001` review name 또는 prior attempt root reuse는 금지다.
각 retry는 새 sequence/challenge sibling을 쓴다.

```text
candidate-r005/attempts/<attempt-key>/subject/
resolved-r005/attempts/<attempt-key>/subject/
walksafe-pre-p-validation-r005/attempts/<attempt-key>/
journal/reviews/stage-a/<attempt-key>/candidate-independent-review.md
journal/reviews/stage-b/<attempt-key>/regression-final-independent-review.md
journal/reviews/stage-b/<attempt-key>/resolved-independent-review.md
```

reviews는 subject root와 repository 밖 external journal review domain에 있다.
각 receipt는 자기 stage subject/env/review paths의 signed `ABSENT` tombstone을
결속한다. subject manifest는 subject root만 재귀 hash하고 review hash를
포함하지 않는다. review는 sealed subject manifest/hash를 검수한다.

Stage-A subject exact role tree:

```text
subject/
  phase-0-contracts/{build-01,build-02,candidate}/
  lane-a/{build-01,build-02,candidate,raw}/
  lane-c/{candidate,raw}/
  control-core/{candidate,raw}/
  lane-d-core/{candidate,raw}/
  lane-b-final/{candidate,raw}/
  lane-d-final/{candidate,raw}/
  after-control/{build-01,build-02,candidate,templates,raw}/
  runtime-pack/{local-combined,hosted-cpu}/{build-01,build-02,candidate}/
  apply/{builders,templates,raw}/
  aggregate/{builders,candidate,raw}/
  candidate-review-subject-manifest.json
```

Stage-B subject exact role tree:

```text
subject/
  build-01/{after-control,apply}/
  build-02/{after-control,apply}/
  candidate/{after-control,apply}/
  regression-final/{builders,build-01,build-02,candidate,raw}/
  projections/{local-combined,hosted-cpu}/
  resolved-review-subject-manifest.json
```

external env attempt:

```text
walksafe-pre-p-validation-r005/attempts/<stage-a-attempt-key>/
  local-combined/{recursive-content-manifest.json,...}
  hosted-cpu/{recursive-content-manifest.json,...}
  environment-attempt-manifest.json
```

각 env는 same-attempt `.tmp.<nonce>` clean build → recursive double manifest →
atomic NOREPLACE publish한다. CPython `3.12.13`, Pillow `12.3.0`, pytest `8.4.2`,
hash install와 `pip check` rc0를 요구한다. realpath/dev/inode/uid/gid/mode,
regular file hash/RECORD digest와 symlink target을 manifest한다. regular
`nlink=1`, absolute/escaping/dangling link 금지다. publish 뒤 executable/regular/
directory를 `0555/0444/0555`로 seal한다. lease state는 mutable env file이
아니라 global journal lease records에만 있다.

Stage A/B/recovery attempts는 stage별 최대 `16`, 첫 stage attempt의 hard
deadline A/B/C/recovery `21600/3600/1800/1800`초를 넘지 못한다. retry는 hard
deadline을 재시작하지 않는다. 실패 attempt는 immutable incident close 후
다음 attempt가 prior attempt path/hash/bytes/reason을 결속한다.

## 5. append-only authority journal

### 5.1 exact paths and publication

```text
/home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-authority-r005/journal/
    genesis/authority-journal-genesis.json
    records/<12digit>-<RECORD_KIND>.json
    attempts/
      stage-a/<attempt-key>/
      stage-b/<attempt-key>/
      stage-c/<attempt-key>/
      recovery-stage-c/<attempt-key>/
    reviews/
      stage-a/<attempt-key>/
      stage-b/<attempt-key>/
    transactions/<transaction-id>/
      transaction-manifest.json
      records/<12digit>-<PROGRESS_KIND>.json
```

genesis는 journal ID, signer/verifier key fingerprint, JCS/signature domain,
allowed record kinds, `max_attempts_per_stage=16`,
`max_renewals_per_attempt=32`, nominal TTL A/B/C/recovery
`900/900/300/300`초, lease slice 최대 `900`초, hard deadline
A/B/C/recovery `21600/3600/1800/1800`초와 first record sequence
`000000000001`을 고정한다. renewal ordinal은 `1..32`이고 receipt expiry와 hard
deadline 중 이른 시각을 넘지 않는다.

각 global record filename은 12-digit sequence와 body kind가 일치한다. record는
strict RFC 8785 JCS UTF-8, duplicate key 없음, terminal LF 없음인:

```json
{"payload":{...},"signature":"<ed25519-base64url-no-padding>"}
```

signature domain은 `WS-PRE-P-R005-JOURNAL-V1 || NUL || JCS(payload)`다.
writer는 unnamed `O_TMPFILE`을 fsync한 뒤 `linkat(AT_EMPTY_PATH)` NOREPLACE로
final path에 publish하고 parent를 fsync한다. final은 regular `0444`,
non-symlink, `nlink=1`이다.

mutable head file은 없다. current head는 genesis부터 filename sequence가
contiguous하고 signature/prior hash가 유효한 마지막 global record다. append는
그 next sequence exact path를 NOREPLACE로 경쟁한다. 한 writer만 성공한다.
loser는 replay 후 child/write count 0으로 종료하며 자기 record를 다음 sequence에
재사용하지 않는다.

### 5.2 record kinds and common schema

allowed kinds:

```text
ISSUED
CONSUME_CLAIMED
LEASE_ACQUIRED
LEASE_RENEWED
PREPARED
DELEGATED_AND_CLOSED
CLOSED_SUCCESS
CLOSED_INCIDENT
REVOKED
RECOVERY_ISSUED
RECOVERY_CONSUMED
RECOVERY_LEASE_ACQUIRED
RECOVERY_LEASE_RENEWED
RECOVERY_CLOSED_SUCCESS
RECOVERY_CLOSED_INCIDENT
```

모든 payload common fields:

```text
schema_version, journal_id, record_kind, sequence
prior_head_path/hash/bytes, genesis_hash
stage, attempt_key, challenge_id, nonce, transaction_id
receipt_path/hash/bytes, executor/custodian identities
scope_id, allowed/denied operations and roots
issued_at, event_at, not_before, expires_at
lease_start/end, hard_deadline, attempt_ordinal
prior_attempt path/hash/bytes/status/reason
before_state, after_state, revocation_state
source checkpoint path/hash/bytes/tail
request/challenge/raw-response paths/hashes/bytes
```

`ISSUED`는 stage receipt의 physical identity를, `CONSUME_CLAIMED`는 one-use
atomic consume winner를, lease records는 non-overlapping latest interval을,
`PREPARED`는 immutable subject+review를, close records는 terminal state를
결속한다. close/revoked 뒤 consume/renew/prepare/success 재사용은 금지다.

### 5.3 full replay

validator는 genesis부터 current contiguous head까지 전 records를 재생해:

- sequence gap/reorder, filename/body mismatch
- prior hash/bytes fork, invalid signature, duplicate nonce/challenge
- receipt second consume와 concurrent consume loser
- overlapping/forked/stale renewal, lease outside hard deadline
- prepare before consume/lease, close before prepare
- close/revocation 뒤 reuse
- attempt ordinal collision와 prior-attempt chain omission
- recovery scope/transaction/checkpoint-state mismatch

를 rc2/write0으로 거부한다. replay output은 immutable
`current_effective_authorities`, `latest_leases`, `closed_attempts`,
`transaction_recovery_state` digest이며 mutable pointer를 읽지 않는다.
latest lease는 같은 attempt의 consume 뒤, close/revocation 전 valid
`LEASE_ACQUIRED|LEASE_RENEWED` 중 highest global sequence이며 exact prior lease
hash를 잇는 하나다.

## 6. stage-specific receipt schemas

attempt receipt exact path:

```text
journal/attempts/<stage>/<attempt-key>/authority-receipt.json
```

receipt도 signed JCS `{payload,signature}`, NOREPLACE/0444/nlink1/file+parent
fsync다. `common ∪ exact stage fields`만 허용하고 unknown/future field는
`FORBIDDEN_FIELD` rc2다.

receipt common exact fields:

```text
schema_version, stage, attempt_key, attempt_ordinal
challenge_id, nonce, transaction_id, scope_id
expected_global_head path/hash/bytes
plan/review/source checkpoint path/hash/bytes/tail
request/challenge/raw response path/hash/bytes
allowed/denied roots and operations
issuer/verifier/custodian identities and signer fingerprint
issued/not-before/expires, ttl, lease slice, hard deadline
authorization_origin A0, revocation_origin R0
delegation_depth=0, handoff_depth, revocation_state, one_use=true
prior attempt/receipt/closure physical identities
```

C is fresh independently signed authority, so `delegation_depth=0`; B→C atomic
mechanical handoff provenance만 `handoff_depth=1`이다.

| binding | Stage A | Stage B | Stage C pending | recovery Stage C |
|---|---|---|---|---|
| R005 plan/review/source | `ACTUAL` | `ACTUAL` | `ACTUAL` | `ACTUAL` |
| prior attempt/closure | signed absent or actual retry | A closed success actual | B `PREPARED` actual; handoff future forbidden | C incident/transaction actual |
| candidate/env root+review | signed absent, write allowed | actual sealed/read-only | actual read-only | actual read-only |
| resolved root+reviews | `FORBIDDEN_FUTURE` | signed absent, write allowed | actual sealed/read-only | actual read-only |
| T1/X1/V1/exact26 | `FORBIDDEN_FUTURE` | `FORBIDDEN_FUTURE` | actual | actual |
| active target writes | forbidden | forbidden | exact26 only after handoff | suffix-only or postcheck-only |
| recovery fields | forbidden | forbidden | forbidden | actual checkpoint/progress state |

Stage A closure actualizes candidate/env manifests and external review. Stage B
`PREPARED` actualizes resolved subject manifest, regression review, overall review,
two-env raw digests와 external env lease. Stage C is independently signed fresh
mechanical authority, not a scope-subset delegation from B. It begins
`authorization_state=PENDING_DELEGATION`.

## 7. B→C atomic authority handoff

Stage C pending receipt binds fresh signed request/challenge/user response, exact26,
T1/X1/V1, source CAS and C allow/deny set. Its effective scope formula:

```text
C_ALLOW = exact26 target operations ∪ transaction journal writes
C_DENY = all paths/operations - C_ALLOW
effective(C)=false until DELEGATED_AND_CLOSED
```

B must be current, unrevoked, consumed, latest lease valid and `PREPARED`. Issuer
publishes one next-head `DELEGATED_AND_CLOSED` record containing B attempt/receipt/
lease/prepared hashes and C pending receipt/hash.

이 record의 one atomic state transition:

```text
B: PREPARED, effective -> CLOSED_SUCCESS, ineffective
C: PENDING_DELEGATION, ineffective -> EFFECTIVE, unconsumed
```

이다. both-effective 또는 both-ineffective 중간 journal state는 없다. B expiry,
revocation, stale renewal/head면 publish 실패하고 C는 계속 pending/ineffective다.
record 뒤 B renewal/consume/reopen은 rc2다. C는 자기 TTL/lease를 새로 갖고 B
future expiry/revocation은 이미 effective된 C를 소급 취소하지 않는다.

첫 C attempt가 handoff 전에 만료되면 B는 닫히지 않는다. pending C attempt를
incident-close하고 fresh C attempt/challenge를 발급한다. handoff 뒤에는 T0~T6
authority가 C 하나뿐이며 ordinary C failure/expiry는 recovery-only attempt로만
이어진다.

## 8. transaction journal and recovery

### 8.1 exact progress records

transaction ID는 C receipt가 결속한 lowercase 64-hex digest다. exact root:

```text
journal/transactions/<transaction-id>/
  transaction-manifest.json
  records/<12digit>-PREWRITE.json
  records/<12digit>-TARGET_CAS_COMMITTED.json
  records/<12digit>-FILE_FSYNCED.json
  records/<12digit>-PARENT_FSYNCED.json
  records/<12digit>-PREFIX_ADVANCED.json
  records/<12digit>-RECONCILED_PREFIX.json
  records/<12digit>-CHECKPOINT_CAS_COMMITTED.json
  records/<12digit>-POSTCHECK_PASSED.json
  records/<12digit>-APPLICATION_RECEIPT_FILE_FSYNCED.json
  records/<12digit>-APPLICATION_RECEIPT_PARENT_FSYNCED.json
```

transaction records도 signed JCS/no-LF/NOREPLACE contiguous chain이다. common
schema:

```text
transaction_id, C/recovery attempt and consume record hashes
sequence, prior progress path/hash/bytes, progress_kind
target ordinal/path/mode, before/after hash/bytes
CAS expected/observed, rename result
file fsync and parent fsync evidence
durable prefix ordinals/path digest
checkpoint before/after/hash/commit state
postcheck/receipt path/hash/bytes/fsync state
event time, executor, lease and global journal head
```

이 transaction subtree가 유일한 external Stage-C production-gate progress
evidence다. 여기서 `production-gate`는 atomic control apply gate를 뜻하며
official `production_deployment=0`을 바꾸지 않는다.

ordinary C receipt는 global `CONSUME_CLAIMED`에서 정확히 한 번 consume된다.
그 consume은 transaction 전체를 시작할 뿐 crash 뒤 재사용 권한이 아니다.
각 target은 CAS/NOREPLACE commit → file fsync → parent fsync →
`PREFIX_ADVANCED` 순서다. target ordinals `1..25`, checkpoint ordinal `26`은
CAS last다.

### 8.2 recovery-only

pre-checkpoint crash와 post-checkpoint crash는 fresh
`attempts/recovery-stage-c/<attempt-key>/authority-receipt.json`을 각각 발급하고:

```text
RECOVERY_ISSUED -> RECOVERY_CONSUMED -> RECOVERY_LEASE_ACQUIRED
-> recovery operation -> RECOVERY_CLOSED_SUCCESS|INCIDENT
```

를 global journal에 기록한다. receipt는 same transaction ID와 original C
consume/incident, live checkpoint state와 progress head를 결속한다.

pre-checkpoint scope:

```text
allow = unique verified exact26 remaining suffix + progress records
deny = committed prefix overwrite/delete + checkpoint unless suffix reaches 26
```

progress record가 commit보다 늦게 유실된 경우 live exact26 before/after hashes로
유일한 prefix만 복원하고 `RECONCILED_PREFIX` signed record를 먼저 쓴다.
non-prefix/ambiguous live state는 write0다.

post-checkpoint scope:

```text
allow = hosted-cpu exact6 postcheck + application receipt/fsync records only
deny = all target/checkpoint writes and rollback
```

recovery receipt의 second `RECOVERY_CONSUMED`, closed recovery 재사용, pre/post
scope 교차는 rc2/write0다. application receipt file 존재/parent fsync 불명확은
live file exact hash와 directory durability evidence를 재검증해 receipt/fsync-only
경로로 닫는다.

## 9. C0 managed set and hash formulas

literal sets:

```text
S = exact seq39 checkpoint managed_changed_paths set, count 603
C = exact CAS_REPLACE paths:
    tests/requirements.lock
    scripts/run_walksafe_test_layers_20260711.sh
A = exact 23 NOREPLACE paths from §16
K = docs/control/walksafe-project-continuation-checkpoint.json
M_after = sorted(unique(S ∪ A ∪ C) - {K}, UTF-8 byte order)
```

preconditions:

```text
C subset S
A intersection S = empty
A intersection C = empty
K not in A or C
exact26 = C(2) + A(23) + K(1)
```

CAS replacement changes bytes, not path membership. A adds exact paths. Candidate,
resolved, env, authority journal, external reviews, transaction records, raw/temp/
cache/build files, application receipt와 K는 nonmanaged/excluded다.

source snapshot self-exclusions are machine-exact:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R005.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R005-independent-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r005/**
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-authority-resolved-r005/**
docs/control/execution/goal-gates/
  WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001/
  application-receipt.json
```

`**`는 runtime glob이 아니라 signed prefix record type이며 prefix 자체와 모든
descendants를 byte-prefix containment로 제외한다. external env/journal/reviews/
transactions는 repository 밖이라 source domain member가 아니다. K는 managed
formula에서만 exact single-path exclusion이다.

canonical managed row:

```text
path_row = UTF8(final-relative-path) || NUL
content_row = UTF8(final-relative-path) || NUL ||
              ASCII(lowercase-file-sha256-64hex) || LF
path_set_sha256 = SHA256(concat(path_row for M_after))
content_set_sha256 = SHA256(concat(content_row for M_after))
file_count = len(M_after)
```

after checkpoint:

```text
working_tree_snapshot.managed_changed_paths == M_after
session_handoff.changed_files == M_after
session_handoff.source_commit_or_snapshot.changed_files == M_after
all three file_count/path_set_sha256/content_set_sha256 == formulas above
```

`source_commit_or_snapshot`가 추가 commit metadata를 갖더라도 member set/hash
정의는 위와 동일하다. event/control role hashes는 별도 typed fields이며
managed digest를 대체하지 않는다.

K는 C0 output이라 M_after에서 제외된다. M_after member bytes는 C0, T1, X1,
V1, A_C, apply/postreceipt hash/path를 참조할 수 없다. builder와 checker는
managed file JSON/string scan 및 typed dependency graph로 descendant reference를
rc2로 거부한다.

## 10. V1 external review binding

```text
H(domain,payload) =
  SHA256(ASCII(domain) || NUL || RFC8785_JCS(payload))
```

Stage-B subject의 final:

```text
resolved-r005/attempts/<attempt-key>/subject/
  resolved-review-subject-manifest.json
```

는 자기 path를 recursive domain에서 exact 제외하고, 모든 resolved outputs와
validation raw/result를 결속하지만 어떤 review path/hash도 포함하지 않는다.
subject를 seal한 뒤 external exact review paths:

```text
journal/reviews/stage-b/<attempt-key>/
  regression-final-independent-review.md
  resolved-independent-review.md
```

에만 review를 쓴다. 따라서 repository source S0/S0-CAS에 review delta가 없다.

Stage C attempt의:

```text
journal/attempts/stage-c/<attempt-key>/review-binding.json
```

은 signed JCS/NOREPLACE/0444/nlink1이고 exact:

```text
schema_version, stage_b_attempt_key
X1 path/hash/bytes
resolved-review-subject-manifest path/hash/bytes
regression review path/hash/bytes/verdict=0/0/0
resolved review path/hash/bytes/verdict=0/0/0
reviewer identity/signature, sealed subject pre/post digest
```

만 가진다. `V1 = H("R005_RESOLVED_REVIEW_BINDING_V1", JCS(binding payload))`다.
review는 binding/V1을 참조하지 않는다. Stage C pending receipt `A_C`가 V1의
첫 consumer다. missing/tampered/replaced review, review-before-seal,
manifest self-inclusion과 review/V1 self-reference는 rc2다.

## 11. discovery and routing arithmetic

current assigned arrays:

```text
UNIT31 + FUNCTIONAL23 + INTEGRATION7 + MODEL3
+ HISTORICAL45 + ACTIVE18 = 127
```

successor exact arrays:

```text
UNIT28 + FUNCTIONAL23 + INTEGRATION7 + MODEL3
+ HISTORICAL53 + ACTIVE16 + PROTOTYPE2 = 132
```

exact transform:

- UNIT `31→28`: stale preflight, Android boundary, baseline materialization exact3
  → HISTORICAL
- ACTIVE `18→16`: old v2.4 continuation/Goal exact2 → HISTORICAL
- orphan seq39, R011, W3 exact3 → HISTORICAL
- orphan r022 candidate, v2.5 candidate exact2 → PROTOTYPE
- other assigned members unchanged

orphan exact5 paths:

| path | successor role |
|---|---|
| `tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py` | `HISTORICAL` |
| `tests/test_walksafe_phase1_exact257_successor_r011_20260729.py` | `HISTORICAL` |
| `tests/test_walksafe_w3_engineering_evidence_20260726.py` | `HISTORICAL` |
| `tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py` | `PROTOTYPE` |
| `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `PROTOTYPE` |

history exact6 with `current_argv_count=0`:

```text
tests/test_walksafe_test_database_preflight.py
tests/test_walksafe_android_product_boundary.py
tests/test_walksafe_artifact_baseline_materialization_20260722.py
tests/test_walksafe_project_continuation_v2_4.py
tests/test_walksafe_goal_graph_v2_4.py
tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
```

discovery registry acceptance:

```text
discovered=132
assigned=132
unassigned=0
duplicate=0
extra=0
runner_all_discovered=74
current_non_test_direct=5
runner_all_direct=2
runner_all_execution=74+2=76
excluded_discovered=58
D_non_test_direct=2
```

current direct5와 D direct2는 non-`test_`, non-discovery separate registries다.
runner all은 current direct5 중 B/C exact2만 실행한다. v2.4.1 direct exact3은
full19 #17, D exact2는 #3/#18에서만 실행한다. successor contract는
discovered132의 exact path→layer/role/current-consumer table, selected74와
excluded58 arrays/digests 및 direct5/D2 arrays/digests를 count가 아니라
member로 동결한다.

## 12. B3 single runner exact selector

single CAS runner invocation:

```text
bash /work/walksafe/scripts/run_walksafe_test_layers_20260711.sh
  --layer <validate|unit|all>
  --root /work/walksafe
  --checkpoint <root-relative-checkpoint-json>
  --control-selector <BEFORE_SEQ39_V24|AFTER_SEQ40_V241>
  --routing-manifest <exact-root-relative-final-manifest-json>
```

defaults/env inference는 없다. allowed selectors/states exact2:

| selector | exact world | final routing manifest |
|---|---|---|
| `BEFORE_SEQ39_V24` | seq39 raw hash/tail, v2.4 ACTIVE | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-before-seq39-v2.4.json` |
| `AFTER_SEQ40_V241` | seq40 compound event, v2.4 SUPERSEDED, v2.4.1 ACTIVE | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json` |

runner는 authority-free embedded logical routing core exact2만 갖고 future final
manifest hash를 embed하지 않는다. final manifest가 core digest를 결속하고
checkpoint/static manifest가 AFTER final hash를 단방향 결속한다.

missing/duplicate/partial flag, unknown/third selector, root/self mismatch, path
escape, wrong package/seq/event/tail, missing/extra/tampered manifest와 embedded/
final mismatch는 pre-spawn rc2, `child_exec_count=0`이다.

## 13. M1 Phase0 and Stage-B regression-final

Phase0 candidate paths:

```text
phase-0-contracts/{build-01,build-02,candidate}/
  regression-predecessor-identity.json
  regression-allowed-transformations.json
```

Phase0 is identity-only: `executable=false`, `acceptance=false`, successor hashes
empty, typed future role IDs only. A `450/7`, B `241 intended/NOT_RUN`은 lineage
only다. allowed transforms만:

- lock/env/pack binding
- single runner exact argv
- history exact6 current→history
- orphan exact5 assignment
- B/C direct2, v2.4.1 direct3, D direct2

ignore/deselect 추가, rename/delete, root/test path 축소, wildcard와 transform 밖
delta는 금지다.

DAG:

```text
A || C || control-core || D-core
-> B-final(A,C,control)
-> D-final(A,B,C,control,D-core)
-> Stage-A unresolved template/aggregate seal/review
-> Stage-B resolved after-control
-> regression-final build-01/build-02 byte equality + external review
-> per-env ordered19
-> regression A PASS -> regression B
```

regression-final inputs are ordered exact Phase0/review/A/C/control/B/D
manifests and resolved v2.4.1 bytes; glob/discovery-only input은 없다. suite A는
B-final runner first pytest/current UNIT+direct2, suite B는 D-final
#3/#4/#17/#18 exact subcommands다. collected nodeid array/digest가 successor
count를 결정하며 fixed241 acceptance는 없다.

## 14. B2 two-env full19 actual validation

each Stage-B resolved attempt:

```text
subject/projections/
  local-combined/
  hosted-cpu/
```

exact2 projections each execute own sealed environment and own synthetic runtime
pack: BEFORE validate → AFTER overlay/validate → ordered19 → regression A →
regression B → AFTER repeat.

literal full19 slots:

```text
[001,002,003,004,005,006,007,008,009,010,011,012,013,014,015,016,017,018,019]
```

each:

```text
full19/raw/<slot>/{intent.json,stdout.bin,stderr.bin,result.json}
```

intent freezes ID/argv/cwd/env/interpreter/pack/input/timeout/output cap.
result has exec boolean, rc, stdout/stderr hash/bytes, PASS|FAIL|NOT_RUN and reason.
unexecuted slot preserves exact file set with zero raw and `exec=false NOT_RUN`;
any FAIL/NOT_RUN makes projection INCOMPLETE and Stage C forbidden.

both exact2 require 19/19 actual rc0, regression A/B PASS and repeat digest.
#17 runs v2.4.1 direct exact3 with explicit files/collected nodeids/per-file digest.
#5~14 run synthetic pack only with unmanifested host open/read/exec zero.

full19 impact remains exact:

| # | ID | impact |
|---:|---|---|
| 1 | `CONTINUATION` | CHANGED v2.4.1/seq40 |
| 2 | `GOAL_GRAPH` | CHANGED v2.4.1/seq40 |
| 3 | `BASELINE_MATERIALIZATION` | CHANGED D triple |
| 4 | `ANDROID_GATEWAY_BOUNDARY` | CHANGED C helper |
| 5 | `NODE_TOOLCHAIN_PRE` | UNAFFECTED byte proof, synthetic pack |
| 6 | `GATEWAY_TYPECHECK` | UNAFFECTED byte proof, synthetic pack |
| 7 | `GATEWAY_TEST` | UNAFFECTED byte proof, synthetic pack |
| 8 | `GATEWAY_BUILD` | UNAFFECTED byte proof, synthetic pack |
| 9 | `WEB_TEST` | UNAFFECTED byte proof, synthetic pack |
| 10 | `WEB_LINT` | UNAFFECTED no-LF `0293d54ea860df64ad8bc21214902488a4062f3c0809ea9c7ca69be35fbfa35b` |
| 11 | `WEB_TYPECHECK` | UNAFFECTED byte proof, synthetic pack |
| 12 | `WEB_BUILD` | UNAFFECTED byte proof, synthetic pack |
| 13 | `NODE_TOOLCHAIN_POST` | UNAFFECTED byte proof, synthetic pack |
| 14 | `ANDROID_UNIT_ASSEMBLE_LINT` | UNAFFECTED byte proof, synthetic pack |
| 15 | `TEST_LAYER_REGISTRY_VALIDATE` | CHANGED single runner validate |
| 16 | `FIELD_AND_RELEASE_PYTEST` | CHANGED current env |
| 17 | `GOAL_CONTROL_PYTEST` | CHANGED old v2.4 current0, v2.4.1 direct3 |
| 18 | `CONTROL_AND_TRACE_PYTEST` | CHANGED legacy baseline removed, B+D, trace9+deselect6 |
| 19 | `REPOSITORY_STATE` | CHANGED seq40 state/event |

## 15. Stage-C exact6 postcheck

Stage C does not rerun full19/regression. It binds Stage-B exact2 result digests and
uses hosted-cpu env/pack only for:

1. live exact26 and routing manifests physical identity equals Stage-B AFTER
2. seq40 activation/supersession/checkpoint seal
3. AFTER continuation Quick
4. AFTER Goal Quick
5. runner validate `AFTER_SEQ40_V241`, `132/132/0`, direct5
6. #17 direct exact3 + #19 repository state/event snapshot

one failure after checkpoint is `POSTCOMMIT_RECOVERY_REQUIRED`, rollback forbidden.
exact6 all PASS only then application receipt.

## 16. exact active target universe, count 26

| mode | exact final-relative target |
|---|---|
| `CAS_REPLACE` | `tests/requirements.lock` |
| `CAS_REPLACE` | `scripts/run_walksafe_test_layers_20260711.sh` |
| `NOREPLACE` | `tests/walksafe_test_database_preflight_successor_20260731_r005.py` |
| `NOREPLACE` | `tests/walksafe_android_gateway_public_routes_successor_20260731_r005.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_current_active_20260731.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_dual_control_20260731.py` |
| `NOREPLACE` | `tests/walksafe_artifact_baseline_historical_successor_20260731_r005.py` |
| `NOREPLACE` | `tests/walksafe_artifact_baseline_current_successor_20260731_r005.py` |
| `NOREPLACE` | `scripts/check_walksafe_project_continuation_v2_4_1.py` |
| `NOREPLACE` | `scripts/check_walksafe_goal_graph_v2_4_1.py` |
| `NOREPLACE` | `tests/walksafe_project_continuation_v2_4_1_successor_20260731.py` |
| `NOREPLACE` | `tests/walksafe_goal_graph_v2_4_1_successor_20260731.py` |
| `NOREPLACE` | `tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/seq40-event-schema.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-before-seq39-v2.4.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/superseded-v2.4.0-active-checkpoint.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/v2.4-supersession-record.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/v2.4-supersession-anchor.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/transition-event-seq40.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/static-plan-manifest-v2.4.1.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/control-package-manifest-v2.4.1.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/README.md` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/full19-successor-contract.json` |
| `CHECKPOINT_CAS_LAST` | `docs/control/walksafe-project-continuation-checkpoint.json` |

recomputed:

```text
ACTIVE_TARGET_COUNT=26
CAS_REPLACE=2
NOREPLACE=23
CHECKPOINT_CAS_LAST=1
DUPLICATE_PATH_COUNT=0
```

application receipt:

`docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001/application-receipt.json`

is postcheck output and excluded from pre-checkpoint exact26.

## 17. hash topology

exact order:

```text
S0 source snapshot/self exclusions
-> E0 event semantic bytes
-> C0 after checkpoint with M_after
-> T1 exact26 target set
-> X1 envelope/recovery/exact6
-> external reviews
-> V1 review-binding
-> A_C pending/effective Stage-C receipt
-> transaction/application receipt
```

E0 references Stage-A transition spec/core and S0 only. C0 references E0 and
M_after formulas. T1 references S0/E0/C0. X1 references T1. external review
references sealed subject/X1 but not V1. V1 references X1/manifest/reviews.
A_C is first V1 consumer. managed targets cannot reference C0 or descendants.
self/reverse/future edge, SCC and canonical order drift are rc2.

## 18. required negative tests

```text
journal-close-replay:
  concurrent/second consume, close-after-reuse, gap/fork/reorder
stale-renewal:
  overlapping lease, renewal after close/hard deadline, stale prior head
recovery-second-consume:
  ordinary C reuse, recovery second consume, pre/post scope crossing
managed-self-review-reference:
  K in M_after, managed C0/V1 ref, manifest review hash, review V1 self ref
retry-collision:
  fixed -001 reuse, same attempt key/root/review, prior-attempt chain omission
discovery-132-arithmetic:
  layer sum mismatch, orphan missing, direct/discovery cross-assignment,
  selected74+excluded58 mismatch, history exact6 current argv
authority-stage-schema:
  future field in A/B, missing actual C/V1, wrong handoff state
official-ceiling:
  126/257 or 0/279 reinterpretation, waived gate, production/release promotion
```

모두 rc2, child/write count 0이다. crash injection은 every exact26 commit/fsync,
checkpoint, each exact6, application receipt file/parent fsync 전후를 포함한다.

## 19. stop conditions

- R005 physical independent review nonzero 또는 Stage A authority absent
- R004/reviews fingerprint drift
- journal genesis/signature/replay/gap/fork/head invalid
- attempt fixed-name reuse, collision, max16/hard-deadline 초과
- future field present or required stage field absent
- consume/lease/prepare/close order invalid, receipt/renewal replay
- B→C handoff outside one `DELEGATED_AND_CLOSED`
- candidate/resolved/env/review tombstone or retry-chain mismatch
- A subject write after seal, B write into A root
- Phase0 successor hash or transform outside exact allowlist
- discovery arithmetic, assigned132, direct registries or runner76 mismatch
- history exact6 current argv nonzero
- two-env full19 19/19, regression A/B, #17 direct3 non-PASS
- pack5~14 unmanifested host access
- C0 formula/precondition/session_handoff equality mismatch
- managed member descendant reference or V1/review self-reference
- exact26 count/mode/path duplicate or drift
- ordinary C second consume, transaction non-prefix, checkpoint-before-target
- recovery-only second consume/scope crossing/rollback
- Stage-C hosted exact6 non-PASS
- official ceiling/delta mismatch
- R007 reuse, P seq39→40, P17 build

## 20. remediation matrix

| predecessor finding | R005 closure |
|---|---|
| formal B-001 journal replay | §5 kinds/contiguous replay, §8 recovery records |
| formal M-001 B→C semantics | §7 pending fresh C + atomic handoff/close |
| formal M-002 C0/V1 | §9 exact formula, §10 external review binding |
| formal m-001 R003 literal | §1 exact `REJECTED_NON_EFFECTIVE_PLAN_ONLY` |
| skeptical B-001 retry namespace | §4 attempt grammar/sibling reviews/max/hard deadline |
| skeptical B-002 durable recovery | §8 transaction progress/recovery-only |
| skeptical B-003 S0/review | §10 all Stage-B reviews external |
| skeptical B-004 discovery | §11 exact arithmetic/roles/registries |
| skeptical M-001 stage schemas | §6 common∪stage fields/future forbidden |
| skeptical M-002 claim ceiling | §1 official exact assertions |

## 21. self-check and handoff

```text
R005 regular && !symlink && nlink==1 && terminal_LF && NUL_free
R004 SHA == f8392f525df655d519b39be9cc08557bd0b817975235cf26a41eadf541eb6bf4
formal review SHA == 0ed279885624b7f9b06efb41e10758a541bb5abed7ceb4e2afec0eac4af60f7e
skeptical review SHA == 1da796214c656f599399a7544b529f6a43eb6291b861ddbeae26fc81a3d44139
R003 verdict == REJECTED_NON_EFFECTIVE_PLAN_ONLY
authority A..G exact7 and current ABSENT_DENY_ALL
journal fixed -001 attempt example count == 0
global records contiguous/no mutable head
ordinary C consume max1; recovery consume max1 per attempt
B prepared -> C pending -> DELEGATED_AND_CLOSED exact atomic
C0 == sorted(unique(S union A union C)-{K})
session_handoff paths/hashes == M_after
V1 external binding; review self edge count0
discovery current sum127; final sum132
assigned/unassigned/duplicate/extra == 132/0/0/0
runner all == discovered74 + direct2 == 76
history exact6 current argv0
target count/modes == 26/2/23/1
official claims exact and all deltas0
```

이 plan 완료는 authority/build/apply가 아니다.

```text
STAGE_A_REQUEST_ALLOWED=false
STAGE_A_AUTHORITY=ABSENT_DENY_ALL
STAGE_B_AUTHORITY=ABSENT_DENY_ALL
STAGE_C_AUTHORITY=ABSENT_DENY_ALL
STAGE_D_AUTHORITY=ABSENT_DENY_ALL
STAGE_E_AUTHORITY=ABSENT_DENY_ALL
STAGE_F_AUTHORITY=ABSENT_DENY_ALL
STAGE_G_AUTHORITY=ABSENT_DENY_ALL
PRE_P_CANDIDATE_BUILT=false
PRE_P_RESOLVED=false
PRE_P_APPLIED=false
V2_4_1_ACTIVE=false
R007_SUCCESSOR_BUILT=false
P_CANDIDATE_BUILT=false
P_APPLIED=false
```

R005 physical independent review가 exact `0/0/0`으로 닫힌 뒤 final handoff에서만
Stage A request를 사용자에게 제시한다.
