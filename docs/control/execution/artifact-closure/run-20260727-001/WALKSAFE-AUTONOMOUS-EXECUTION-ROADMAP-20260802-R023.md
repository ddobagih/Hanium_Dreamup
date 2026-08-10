# WalkSafe v2.5 비효력 후보 atomic-publication correction 로드맵 R023

- 작성일: 2026-08-02
- 상태: PENDING_TWO_INTERNAL_REVIEWS
- replaces: rejected R022
- 현 정본: v2.4 / checkpoint sequence 39 / Gap·Backlog r021
- 이번 종착점: REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED
- canonical·checkpoint·Goal·제품 write 권한: 0

R022 structural review와 skeptical review는 각각 BLOCKING=1로 R022를 거부했다. 공통
결함은 P2A/P2C Add File 성공과 최초 path lstat 사이에서 동일 바이트 inode가 교체되면
교체 뒤 tuple을 최초 T0/T1로 채택할 수 있다는 first-observation race다. 두 review는
그 밖의 I31/S0/P2C canonical schema, mandatory anchor, persistent candidate pointer,
R002 namespace, failed-r001, retry terminal, 37-test 및 zero-authority 축에는 추가
blocker가 없다고 판정했다.

R023은 receipt publication을 path 재관측 방식에서 created file descriptor를 계속
보유하는 exclusive publisher로 교체한다. 한 supervisor가 P2A 전부터 P3 parent fsync
뒤까지 살아 있고, P2A/P2C의 생성 FD 및 P2B 뒤 S1의 read-only FD를 보유한다.

    S0
      -> P2A raw in memory
      -> O_EXCL create FD / fsync / T0=fstat(created FD)
      -> exact intended S1 content commitment
      -> one apply_patch / held S1 FD snapshots
      -> P2C raw in memory
      -> O_EXCL create FD / fsync / T1=fstat(created FD)
      -> mandatory sealed-memfd anchor
      -> r002 package + output manifest

현재 path를 expected 값으로 재채택하는 fallback은 어느 경계에도 없다. R023은
activation이 아니며 R002는 계속 non-effective다.

## 1. frozen identities

### 1.1 immutable chain

다음 identity는 내용 근거일 뿐 R017~R022에 실행 권한을 부여하지 않는다.

| role | SHA-256 | bytes |
|---|---|---:|
| C0 checkpoint | 6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c | 1,329,415 |
| reviewed R002 pair | 7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08 | 12,972 |
| R002 PASS review | f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7 | 2,366 |
| accepted R016 | 49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2 | 12,690 |
| R016 structural | eced95aa2343427b24f80f8865e2fa8e9e3f320ad48c74fab7970d646a1fac87 | 3,758 |
| R016 skeptical | 9bae362ad4c1f0c4b2567b534197938f77d825b5d73d76dbd3a184542649bc0e | 5,354 |
| rejected R017 | 6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5 | 13,494 |
| R017 structural | cbcb6a14e4af2b82ec84c300799575fd99a81b65e9e8236177ff7221be958e81 | 9,466 |
| R017 skeptical | d10ba282eee810c58a0d76d8658a4a21d937dcd4c76882a4936dc97538d61fe4 | 5,326 |
| rejected R018 | 2a1df2cd5f0fdccb3183b15607b6a017ab6b2556d661ea16e9b6caaa9d9255f7 | 17,914 |
| R018 structural | a87d59d2a3a5bbf601b4c3d9c3fdf8adaa1511c99e1c538a03db7be474a3b584 | 8,180 |
| R018 skeptical | 8fa2bfe82ac5b0b4a7a9d3708f9cf3407e995c8c82bd74d79ce2e8a4161879a4 | 4,551 |
| rejected R019 | 3c21f917793b0ffbe5f6aa892e1d73d122799fcfe005c22f2f289571b45d4eb2 | 21,461 |
| R019 structural | 97bfa16d3c0f02e6ac601c92bc78c19779438948fc089624dfa9dcc235c0a548 | 11,122 |
| R019 skeptical | b2995ccd45980cfecd422f88bd02aed6d98b42f445824a29876336efb7e1d8fc | 7,168 |
| rejected R020 | 276f499ce235289cbe7b6f15abc835ce87ed0ce8ccd76d03df904bc5f5cd44f1 | 25,239 |
| R020 structural | 9b378e4555c6c3a1edf1e712acb90f5f51089ed469116b552b1be496f7058316 | 9,096 |
| R020 skeptical | 8ac10e419f31409e5062cf82ab1066f3dafaff6eaaed63155662c3ff56ca9690 | 10,753 |
| rejected R021 | b65d0242656b5898392fcab2b168832a6ce19577fa5e469cbbce2cfda777b919 | 28,757 |
| R021 structural | d9dc411e51d3e6a724bb626630b20de8f258c6659829475d98c81903eee26a94 | 9,574 |
| R021 skeptical | 4e751ad91a3efec2546949ad393fa16692fb268300aea6df6aeeafeae068b93b | 8,170 |
| rejected R022 | 2a05166af626fae929decf0fef28fc6c70b4b448a312e6e73ba2aa9e8cac3caa | 18,978 |
| R022 structural | 46b227c645fa047d5c6fb2799cde6f40eed77b433a198b6e95f7b007586e111f | 9,610 |
| R022 skeptical | 84b3cbff023acbc111681fce2484b2a636bcb45d1aa76b4bdbfe51d79f01cc75 | 7,295 |

R016 dual PASS만 correction 실행 근거였고 R017~R022 및 그 review authority는 NONE이다.
R023의 새 dual PASS 없이는 아래 P2 이후 authority가 열리지 않는다.

### 1.2 exact S0

| path | SHA-256 | bytes |
|---|---|---:|
| scripts/walksafe_v2_5_candidate_validation.py | e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f | 180,432 |
| scripts/build_walksafe_v2_5_control_candidate_20260730.py | d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175 | 51,153 |
| tests/test_walksafe_v2_5_control_candidate_20260730.py | 00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523 | 69,685 |

R017~R022 아래 source/build/test 실행과 mutation은 0이다. P2A/P2C/r002 target은
R023 작성 시작 시 absent다.

### 1.3 immutable failed r001

Exact root는 다음이다.

    plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001

Root full fixed lstat와 exact-six full fixed lstat는 R021 §2.2의 실제 tuple이다. Exact-six
SHA/bytes는 R022 §2와 같고 basename byte-sort + terminal NUL digest는
f4c9e884242b5acf190f0cb95d1567845173d8ae8e3a20919d81519f88b5d86c다.
Root는 non-symlink directory mode 0700/nlink 2, members는 regular mode 0644/nlink 1,
uid/gid 1000/1000이다. old test pin은
5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459 /
68,995다. seq1-only와 zero-authority이며 drift는 repair하지 않는다.

## 2. epochs and exact write set

1. P0 R023 roadmap
2. P1 R023 dual plan reviews
3. P2 supervisor start and P2A exclusive-FD receipt
4. P2B exact S0-to-S1 single patch and held S1 snapshots
5. P2C exclusive-FD source-postimage and sealed anchor
6. P3 r002 exact-six publication before supervisor end
7. P4 r002 dual candidate reviews

Exact write targets:

    P2A =
      docs/control/execution/artifact-closure/run-20260727-001/
      walksafe-v25-r002-preparation-capture-20260802-r023-r001.json
    P2B =
      scripts/walksafe_v2_5_candidate_validation.py
      scripts/build_walksafe_v2_5_control_candidate_20260730.py
      tests/test_walksafe_v2_5_control_candidate_20260730.py
    P2C =
      docs/control/execution/artifact-closure/run-20260727-001/
      walksafe-v25-r002-source-postimage-20260802-r023-r001.json
    P3 =
      plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
      v2-5-control-candidate-r002/
    P4 =
      plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
      v2-5-control-candidate-r002-independent-structural-review-r001.md
      plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
      v2-5-control-candidate-r002-independent-skeptical-review-r001.md

Other writes, canonical/checkpoint/Goal/product writes and external actions are zero.

## 3. exact immutable registry I34 and S0

Canonical order is ordinal ascending.

- rows 1~21: R021 §4.1 rows 1~21, C0/R002/R016~R021
- rows 22~24: R022 roadmap, R022 structural, R022 skeptical
- rows 25~27: R023 roadmap, R023 structural, R023 skeptical
- rows 28~34: failed-r001 root, output, static, history, plan, package, checkpoint

R022 rows are the exact paths and pins in §1.1. R023 rows are:

| ordinal | row_kind | role | path |
|---:|---|---|---|
| 25 | FILE | r023_roadmap | docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R023.md |
| 26 | FILE | r023_structural_review | docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R023-independent-structural-review-r001.md |
| 27 | FILE | r023_skeptical_review | docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R023-independent-skeptical-review-r001.md |

FILE exact keys:

    bytes,lstat,ordinal,path,role,row_kind,semantic_reviewed_at,
    semantic_reviewed_at_ns,sha256

DIRECTORY exact keys:

    entry_count,entry_name_digest_sha256,lstat,ordinal,path,role,row_kind,
    semantic_reviewed_at,semantic_reviewed_at_ns

lstat exact keys:

    dev,gid,ino,mode,mtime_ns,ctime_ns,nlink,size,uid

All numeric values use exact integer type, never bool. Review content has zero or one exact
reviewed_at line; two or more fail. One value is canonical timezone-aware RFC3339 and its ns is
not later than min(mtime_ns,ctime_ns). R023 dual reviews additionally require PASS 0/0/0 and
the exact R023 authority in §10.

S0 is exact three FILE rows, ordinal core/builder/test 1/2/3, roles
source_validation_core_s0, source_builder_s0, source_test_s0 and null semantic fields. It is
live-equal through the instant before P2B begins, then historical only.

The causal max candidates are each I34/S0 row's:

    (mtime_ns, role, "mtime_ns")
    (ctime_ns, role, "ctime_ns")
    (semantic_reviewed_at_ns, role, "semantic_reviewed_at_ns")

The semantic tuple is omitted when null. Python ascending lexical max selects one exact
causal_max object.

## 4. one uninterrupted supervisor

One supervisor process begins before I34/S0 collection. Its PID/boot identity is diagnostic,
not authority; continuity is enforced by possession of non-transferable in-memory commitments
and the open descriptors described below. It remains alive until:

- P2A fd still matches T0,
- three S1 fds still match the frozen S1 rows,
- P2C fd still matches T1,
- r002 rename and parent fsync succeed,
- the published candidate exact anchor validates.

Receipt/source fds are O_CLOEXEC and never passed to children. Supervisor loss, descriptor loss,
unexpected close, process replacement, anchor loss or inability to prove continuity is:

    NEW_REVISION_REQUIRED_SUPERVISOR_CONTINUITY_AUTHORITY_ZERO

There is no resume from P2A, P2B, P2C or ambiguous P3 under R023.

## 5. exclusive created-FD publication primitive

P2A and P2C use the same exact primitive. apply_patch Add File is forbidden for those two
generated receipts because it cannot return the created descriptor.

### 5.1 preconditions

The supervisor first holds the exact canonical raw bytes and SHA/length in memory. It opens every
repository-relative parent component from an anchored repository dirfd with
O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC. Each component and final parent is a real directory,
uid/gid 1000/1000, non-world-writable and stable under fstat before/after. The literal final
basename contains no slash, dot component or traversal.

Target absence is checked with no-follow dirfd lstat; dangling symlink, directory, regular or any
other entry counts as present. Existing target is a terminal, not an inspection/recovery path.

### 5.2 create, write and first tuple

The same supervisor executes:

    fd = openat(
      parent_fd,
      literal_basename,
      O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,
      0600
    )

It requires regular file, mode 0600, uid/gid 1000/1000, nlink 1. It writes from offset zero with
an exact write-all loop, performs fsync(fd), then captures:

    T_created = fstat(fd)

That fstat, not a later path observation, is the first physical authority tuple. Before any
close it performs pread from offset zero, requires exact raw bytes/hash/length, and requires
dirfd no-follow lstat of the literal path to equal T_created in all nine fields. It opens a
second O_RDONLY|O_NOFOLLOW fd only to prove the path resolves to the same dev/ino/content, then
closes that second fd.

It fsyncs parent_fd and repeats, in order:

1. fstat(created fd) exact T_created
2. pread(created fd) exact raw
3. path no-follow lstat exact T_created
4. second no-follow read fd dev/ino/content exact
5. parent fstat equal to its accepted post-create snapshot

The created fd remains open and is never writable again. T0/T1 is exactly
path/hash/bytes plus T_created's nine-field lstat.

An O_EXCL success is the point of no same-revision return. Partial write, short write, fsync
failure, replacement, unlink, touch, chmod, chown, link-count change, parent drift, path/read-fd
mismatch, close ambiguity or any later mismatch leaves evidence untouched and terminates:

    P2A: NEW_REVISION_REQUIRED_TIMESTAMP_CAPTURE_AUTHORITY_ZERO
    P2C: NEW_REVISION_REQUIRED_SOURCE_POSTIMAGE_AUTHORITY_ZERO

No truncate, chmod repair, unlink, rename, replacement or same-revision retry is allowed.

## 6. P2A v4 and T0

Before P2A the supervisor collects I0/S0a, computes causal max, observes time.time_ns and sets:

    selected_ns = observed_ns - observed_ns % 1000

Only the first observation with causal_max.ns < selected_ns becomes the committed candidate.
prepared_at is exact Asia/Seoul YYYY-MM-DDTHH:MM:SS.ffffff+09:00. It then observes postcheck_ns
and requires:

    causal_max.ns < selected_ns <= observed_ns <= postcheck_ns

I1==I0, S0b==S0a, max unchanged, all future targets absent and failed-r001 exact are rechecked.
Uncommitted clock observations have authority zero.

P2A exact identity:

    schema_version = walksafe.v2.5.r002-preparation-capture.v4
    receipt_id = WS-WALKSAFE-V25-R002-PREPARATION-CAPTURE-20260802-R023-R001
    roadmap_revision = R023

Top-level exact keys remain:

    authority_boundary,candidate_target,capture_algorithm,causal_max,
    immutable_causal_inputs,observation,pre_capture_state,receipt_id,
    roadmap_revision,schema_version,source_preimage

authority_boundary is exact:

    applied:false
    approved:false
    canonical_write_authorized:false
    checkpoint_write_authorized:false
    effective:false
    evidence_only:true
    goal_write_authorized:false
    product_write_authorized:false

capture_algorithm exact keys/values:

    committed_selection_count:1
    created_inode_anchor:true
    descriptor_retained_until_p3:true
    parent_fsync_required:true
    precision:"MICROSECOND_FLOOR"
    publication_protocol:"FD_OWNED_OPENAT_O_EXCL_V1"
    retry_same_receipt_allowed:false
    time_source:"time.time_ns"
    timezone:"Asia/Seoul"
    uncommitted_observations_have_authority:false

immutable_causal_inputs=I0 exact 34 rows, source_preimage=S0a exact 3 rows,
candidate_target is r002, observation has observed_ns/postcheck_ns/prepared_at/selected_ns, and:

    pre_capture_state = {
      capture_target_absent:true,
      postimage_target_absent:true,
      r001_exact:true,
      r002_target_absent:true,
      source_mutation_started:false
    }

Raw is UTF-8 canonical JSON with ensure_ascii false, sorted keys, compact separators,
allow_nan false and one terminal LF. Strict parse rejects BOM, duplicate keys, non-finite,
non-object, bool-as-int, extra/missing keys and noncanonical raw.

The §5 primitive publishes P2A and makes its created-fd T_created the exact T0. It requires
selected_ns <= min(T0.mtime_ns,T0.ctime_ns). T0 fd remains open through P3. Core/builder/test
embed exact P2A path/hash/bytes/T0 and observation/causal constants.

## 7. P2B exact S0-to-S1

Before mutation the supervisor constructs the exact intended final bytes of all three sources
and stores an in-memory content commitment of path/SHA-256/bytes. It exposes only the three
commitments and a one-use PATCH_READY nonce; it does not expose receipt/source write fds.

One apply_patch call changes exactly the three §1.2 paths. Immediately before that call I/T0/S0
and the intended content commitment are exact. After return the same supervisor:

1. proves I/T0 unchanged and S0 paths are the only intended mutations,
2. opens each S1 path O_RDONLY|O_NOFOLLOW|O_CLOEXEC,
3. takes fstat/read/fstat and requires all nine fields stable,
4. requires bytes/hash to equal the pre-mutation content commitment,
5. requires each S1 hash differ from its S0 hash,
6. requires mtime_ns/ctime_ns >= selected_ns,
7. stores exact three S1 FILE rows and keeps all three fds open through P3.

The accepted S1 tuple is explicitly the first stable post-apply_patch snapshot; R023 does not
claim apply_patch returned the created inode. Different-byte adoption is impossible because the
raw commitments predate mutation. After snapshot, same-byte replacement/touch/link is detected
against held fds and frozen rows at every boundary.

Partial patch, extra path, content mismatch, I/T0 drift or missing stable S1 snapshot is terminal
and has no R023 repair/resume.

R002 namespace and IDs are exact:

    BUNDLE_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002
    APPLICATION_GATE_REL = docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-002
    V25_PLAN_FINAL_REL = APPLICATION_GATE_REL + "/application-transaction-plan.json"
    RESOLVED_OUTPUT_MANIFEST_REL = APPLICATION_GATE_REL + "/resolved-output-manifest.json"
    PACKAGE_CANDIDATE_ID = WS-V25-CONTROL-CANDIDATE-20260730-R002
    TRANSACTION_PLAN_ID = WS-V25-R022-APPLICATION-TRANSACTION-PLAN-20260730-R002
    DELEGATION_REQUIREMENT_ID = WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R023-R002
    QUICK_GATE_REQUIREMENT_ID = WS-V25-R022-FRESH-QUICK-GATE-REQUIRED-20260730-R002
    FINAL_TRANSFORM_ID = WS-V25-R022-AUTHORIZED-FINAL-TRANSFORM-20260730-R002
    V25_CHECK_COMMAND_CONTRACT_VERSION = 2026-08-02.1
    history_id = WS-V25-TRANSITION-HISTORY-20260730-R002
    prepared_checkpoint_id = WS-V25-PACKAGE-PREPARED-CHECKPOINT-20260730-R002
    candidate_output_manifest_id = WS-V25-CANDIDATE-OUTPUT-MANIFEST-20260730-R002
    active_checkpoint_id = WS-V25-R022-ACTIVE-CHECKPOINT-20260730-R002
    seq1/seq2/seq3 = WS-V25-PACKAGE-PREPARED-20260730-002 /
      WS-V25-PACKAGE-ACTIVATED-20260730-002 /
      WS-V25-BULK-REBASELINE-APPLIED-20260730-002

The R022 token in transaction/quick/final/active IDs is the reviewed v2.5 design lineage, not
R022 roadmap authority. PLAN_ROOT, R002 reviewed pair directory, design R001, review suffix
r001, rejected-history paths, failed-r001 pins and version-wide IDs are the only other
intentional r001/R001 literals.

Physical prepared_at equals P2A selected time. TEST-only review/request/auth/quick times derive
only as +5m/+10m/+15m/+15m30s/+16m/+26m and seq3=checked+1 microsecond. Independent calendar
literals are forbidden.

Provenance includes accepted R016, rejected R017~R022 trios, current R023 trio, P2A and P2C.
Failed-r001 validator, truthful delegation/full latest directive/zero credits and pre-C1
no-resume rules remain mandatory.

## 8. P2C v3, T1 and exact anchor

Before P2C raw construction, the supervisor already holds and revalidates T0 fd plus all three
S1 fds/rows. It observes captured_ns only after the S1 snapshot and requires captured_ns not
earlier than every S1 mtime_ns/ctime_ns.

P2C exact identity:

    schema_version = walksafe.v2.5.r002-source-postimage.v3
    receipt_id = WS-WALKSAFE-V25-R002-SOURCE-POSTIMAGE-20260802-R023-R001
    roadmap_revision = R023

Top-level exact keys:

    authority_boundary,capture_receipt_binding,captured_ns,receipt_id,
    roadmap_revision,schema_version,source_postimage,source_preimage_binding

authority_boundary is the exact §6 object. capture_receipt_binding exact keys are
bytes,lstat,path,sha256 and exact value is T0. source_preimage_binding exact keys are
capture_receipt_path,source_preimage_sha256. Its digest is SHA-256 of canonical JSON bytes of
P2A source_preimage without terminal LF.

source_postimage is exact three FILE rows, ordinal core/builder/test 1/2/3, roles
source_validation_core_s1, source_builder_s1, source_test_s1, and null semantic fields.
P2C contains no candidate/package/output/review/future receipt hash or path.

P2C raw uses the exact canonical serialization from §6. The §5 primitive publishes it and makes
the created-fd T_created the exact T1. It requires captured_ns <=
min(T1.mtime_ns,T1.ctime_ns), T0/S1 held descriptors unchanged and path resolutions exact.

The supervisor creates only this in-memory object:

    SOURCE_TRANSITION_ANCHOR = {
      preparation_capture_receipt_binding:T0,
      source_postimage_receipt_binding:{
        path:P2C,sha256:P2C_SHA,bytes:P2C_BYTES,lstat:T1
      },
      source_postimage:exact_frozen_S1_rows
    }

No expected-anchor None/default/current-live path exists.

## 9. sealed child transport and mandatory APIs

For a read-only child, the supervisor serializes the anchor canonically with one LF into a Linux
memfd created with MFD_ALLOW_SEALING. It applies F_SEAL_WRITE, F_SEAL_GROW, F_SEAL_SHRINK and
F_SEAL_SEAL, then passes only that fd with pass_fds. The environment contains only the decimal
descriptor name WALKSAFE_R023_EXPECTED_ANCHOR_FD; readers use pread from offset zero, require all
seals and canonical bytes, and never derive expected state from live P2C. Receipt/source fds stay
CLOEXEC. A missing, unsealed, writable, extra-byte or malformed memfd is terminal.

Pre-P3 APIs have no default:

    validate_source_transition_evidence(root, *, expected_anchor)
    build_outputs(root, *, expected_source_transition_anchor)
    write_add_only(root, outputs, *, expected_source_transition_anchor)

Construction, validation, prebuild tests, immediately-before-rename and post-rename validation
all receive the same object. Build-after-validation repeats held-fd/path comparisons. Child
completion is bracketed by the supervisor's original in-memory and held-fd checks.

Package and output manifest both add exact top-level /source_transition_evidence with value
deep-equal to the original anchor. Both logical seals cover it. Before P3 candidate validation
requires expected/object/package/output equality.

After P3, read-only validators derive expected state only from the sealed candidate:

1. package/output pointers have exact schema and are deep-equal,
2. live P2A raw/full T0 equals embedded T0,
3. live P2C raw/full T1 equals embedded T1,
4. live S1 content/full tuples equal embedded rows.

No P2D or cycle exists:

    S0 -> P2A/T0 -> S1 -> P2C/T1 -> package -> output manifest

## 10. R023 dual plan reviews

Exact targets:

    docs/control/execution/artifact-closure/run-20260727-001/
    WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R023-independent-structural-review-r001.md
    docs/control/execution/artifact-closure/run-20260727-001/
    WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R023-independent-skeptical-review-r001.md

Each review pins this roadmap's exact SHA/bytes/lines and has one canonical physically
non-future reviewed_at. It audits at least created-FD first tuple, held descriptors, parent
fsync, supervisor continuity, sealed memfd transport, S1 commitment, I34/T0/T1, P2C schema,
candidate pointers, namespace/r001/retry/37 tests and zero authority.

PASS only:

    status=PASS
    findings=BLOCKING=0 MAJOR=0 MINOR=0
    authority_granted=R023_R002_ATOMIC_CAPTURE_SOURCE_CANDIDATE_CORRECTION_ONLY

Any finding means P2 onward write 0 and a new roadmap.

## 11. prebuild, publication and postbuild

After dual PASS:

1. I34/S0/r001/all target absence and parent-dir anchors
2. P2A exclusive-FD publication, T0 and retained fd
3. precommitted final source bytes, one apply_patch, retained S1 fds
4. P2C exclusive-FD publication, T1 and original anchor
5. AST/duplicate/trailing/namespace/legacy/calendar scans
6. targeted exact 37 unittest methods, skip0/exit0/OK via sealed memfd
7. r002 exact-six PUBLISHED_NEW under original anchor
8. r002 parent fsync and exact post-publication anchor validation
9. postbuild 37 unittest, builder --check, continuation CANDIDATE, Goal CANDIDATE
10. final I/T0/T1/S1/r001/r002/C0/R002/r021 equality

Any existing r002 directory/file/symlink/exact target or rename EEXIST race is:

    NEW_REVISION_REQUIRED_EXISTING_R002_TARGET_AUTHORITY_ZERO

RECOVERED_EXACT_EXISTING and recovery helpers are removed. Parent-fsync ambiguity leaves the
target untouched and retry terminal. Unique write success is PUBLISHED_NEW. Check/wrappers are
read-only and derive anchor from sealed candidate.

Existing exact 37 test methods gain subcases only. Required negative oracles include:

- inject P2A/P2C same-byte inode replacement, unlink, touch, chmod and link after file fsync but
  before first path comparison; created fd T_created must remain expected and P3 rename is absent,
- coordinated S1+P2C replacement against precommitted S1 bytes and held S1 fds,
- each T0/T1/S1 nine-field mutation, P2C canonical/schema/row/order mutation,
- missing/None/current-live/unsealed-memfd anchor,
- package/output missing, mismatched and coordinated resealed pointer attacks,
- build-to-rename drift prevents rename,
- R002 namespace/IDs/timestamps, failed-r001 exact, retry terminal and read-only snapshots.

r002 root/exact-six full no-follow tuple/hash/NUL-name digest are unchanged across all read-only
checks.

P4 candidate reviews independently rerun the exact anchors and gates. PASS only:

    PASS_FOR_NON_EFFECTIVE_V25_CANDIDATE_ONLY
    BLOCKING=0 MAJOR=0 MINOR=0
    authority=NONE_FOR_ACTIVATION_GOAL_PRODUCT

## 12. successors and success

Because rejected R022 did not activate anything, the successor numbers shift:

1. R024 activation transaction
2. R025 Goal replay/full19
3. R026 FP008 materialized/ready/GOAL_STARTED
4. R027 Android minimal read-only slice

R024 before canonical/checkpoint, R025 before Goal, R026 valid start before product write: zero.

Success conjunction:

    R023_DUAL_PLAN_REVIEW_PASS
    AND I34_S0_VALID
    AND P2A_CREATED_FD_T0_VALID
    AND S0_TO_S1_PRECOMMITTED_EXACT
    AND HELD_S1_FDS_VALID
    AND P2C_CREATED_FD_T1_VALID
    AND SEALED_MEMFD_EXPECTED_ANCHOR
    AND UNINTERRUPTED_SUPERVISOR_TO_P3
    AND R002_PERSISTENT_SOURCE_TRANSITION_ANCHOR
    AND R001_IMMUTABLE
    AND PREBUILD_37_PASS_SKIP_ZERO
    AND R002_PUBLISHED_NEW
    AND POSTBUILD_37_AND_CHECKERS_PASS
    AND R002_DUAL_CANDIDATE_REVIEW_PASS
    AND LIVE_C0_R021_UNCHANGED
    AND CANONICAL_CHECKPOINT_GOAL_PRODUCT_DELTA_ZERO

Result remains
REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED.
Daylog/local-memory are written only by the parent at final handoff.
