# WalkSafe v2.5 비효력 후보 content-CAS closure correction 로드맵 R024

- 작성일: 2026-08-02
- 상태: PENDING_TWO_INTERNAL_REVIEWS
- replaces: rejected R023
- 현 정본: v2.4 / checkpoint sequence 39 / Gap·Backlog r021
- 이번 종착점: REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED
- canonical·checkpoint·Goal·제품 write 권한: 0

R023 structural review는 BLOCKING=1, skeptical review는 BLOCKING=2로 거부됐다.
공통 첫 finding은 regular file의 full lstat tuple을 최초 authority로 만들려는 동안
같은 inode의 mtime/ctime이 이동할 수 있다는 first-fstat race다. skeptical review의
둘째 finding은 P3 뒤 supervisor가 끝나면 candidate 내부 pointer와 seal을 함께 다시
만든 상태를 P4가 최초 expected 값으로 채택할 수 있다는 post-P3 coordinated reseal이다.

R024는 두 문제를 원인에서 제거한다.

1. 증적 authority는 canonical bytes의 SHA-256과 길이뿐이다.
2. inode, mtime_ns, ctime_ns는 authority도 success evidence도 아니다.
3. same-byte replacement/touch는 의미동등이며 실패로 주장하지 않는다.
4. no-follow regular-file envelope, owner, link count와 허용 mode는 매 read 시 검사한다.
5. original content anchor를 가진 supervisor는 P4 dual review와 P5 closure receipt
   검증이 끝날 때까지 종료하지 않는다.
6. P4 reviewer는 current candidate에서 expected 값을 만들지 않고 supervisor가 먼저
   발행한 exact challenge를 입력으로 받는다.
7. P5 closure receipt hash/bytes는 daylog, local-memory와 user-visible handoff에
   candidate 외부 expected 값으로 남는다.

이 범위는 local non-effective candidate의 deterministic content integrity다. 외부
identity, signature, trusted timestamp 또는 hostile same-UID process의 transient
metadata history를 증명하지 않는다. 그 미지원 주장을 제거하는 것이 R024의 명시적
단순화다.

## 1. frozen chain

### 1.1 exact content pins

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
| rejected R023 | e0d2f11064b3a38745555332aca16bcc6b876094c33d89d26db0aa5da289272d | 25,895 |
| R023 structural | 48e7e6f9221753657443b4a08d1df10bf813810690f0b4fa44165aed31a7ca1f | 9,441 |
| R023 skeptical | 5939652d33a2ae54a09c9028160de7f2856f1a2a7515830ff52a7f87dae78ed3 | 9,728 |

R016만 accepted다. R017~R023과 각 review authority는 NONE이며 immutable historical
content로만 사용한다.

### 1.2 exact S0

| path | SHA-256 | bytes |
|---|---|---:|
| scripts/walksafe_v2_5_candidate_validation.py | e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f | 180,432 |
| scripts/build_walksafe_v2_5_control_candidate_20260730.py | d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175 | 51,153 |
| tests/test_walksafe_v2_5_control_candidate_20260730.py | 00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523 | 69,685 |

### 1.3 failed r001 content seal

Root:

    plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001

Exact-six SHA/bytes are R022 §2. Basename byte-sort + terminal NUL digest is
f4c9e884242b5acf190f0cb95d1567845173d8ae8e3a20919d81519f88b5d86c.
Old test binding remains
5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459 /
68,995. The six documents are seq1-only and effective/approved/applied false.

R024 does not pin failed-r001 inode/timestamps. It requires at every read: non-symlink root
directory, exact six names, regular files, uid/gid 1000/1000, nlink 1, no world-write,
exact content SHA/bytes and semantic zero-authority. Same-byte inode replacement is equivalent.

## 2. exact content authority and safe reader

### 2.1 binding

Every authoritative file binding has exact keys:

    path,sha256,bytes

Every authoritative source row has exact keys:

    ordinal,role,path,sha256,bytes

No authoritative object contains dev, ino, mtime_ns or ctime_ns. No success predicate compares
those values. A document may show them only in a diagnostic section explicitly marked
authority=false; R024 outputs omit them.

### 2.2 safe read envelope

For every bound file, a reader:

1. validates normalized repository-relative POSIX path and exact allowlist membership,
2. walks ancestors no-follow and rejects symlink/escape,
3. opens final path O_RDONLY|O_NOFOLLOW,
4. fstat before read: regular, uid/gid 1000/1000, nlink 1, not world-writable,
5. reads from that fd only,
6. fstat after read and requires dev/ino/mode/uid/gid/nlink/size unchanged during that read,
7. requires exact SHA-256/bytes,
8. reopens the literal path O_RDONLY|O_NOFOLLOW and repeats the envelope/content read; it must
   resolve to a safe regular file with the same SHA/bytes. A same-byte replacement before or
   after either read is accepted as equivalent.

Directory readers require non-symlink directory, uid/gid 1000/1000, no world-write, exact
byte-sorted entry names and each member's content binding. Hardlink/symlink/special/extra/missing
states fail when observed. A transient same-byte replacement or time-only touch has no semantic
authority and therefore is not claimed as detected.

This content equivalence rule replaces all R021~R023 full-lstat T0/T1 authority language.

## 3. epochs and exact write set

1. P0 R024 roadmap
2. P1 R024 dual plan reviews
3. P2 supervisor start and P2A content receipt
4. P2B exact S0-to-S1 single apply_patch
5. P2C source-postimage content receipt
6. P3 r002 exact-six publication
7. P4 r002 dual candidate reviews under original challenge
8. P5 reviewed closure receipt and external handoff binding

Exact generated targets:

    P2A =
      docs/control/execution/artifact-closure/run-20260727-001/
      walksafe-v25-r002-preparation-capture-20260802-r024-r001.json
    P2C =
      docs/control/execution/artifact-closure/run-20260727-001/
      walksafe-v25-r002-source-postimage-20260802-r024-r001.json
    P3 =
      plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
      v2-5-control-candidate-r002/
    P4 =
      plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
      v2-5-control-candidate-r002-independent-structural-review-r001.md
      plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
      v2-5-control-candidate-r002-independent-skeptical-review-r001.md
    P5 =
      docs/control/execution/artifact-closure/run-20260727-001/
      walksafe-v25-r002-reviewed-closure-20260802-r024-r001.json

P2A, P2C, P5 and P0/P1/P4 documents use apply_patch Add File. P2B uses one apply_patch call
updating exactly the three S0 paths. P3 uses the reviewed builder's add-only staging and
rename-noreplace publication. Other project/canonical/checkpoint/Goal/product writes are zero.
Parent daylog/local-memory writes occur only after P5 outside these control epochs.

### 3.1 supervisor IPC state machine

The supervisor is one long-lived unprivileged Python process over one pipe stdin/stdout session.
Each frame is one canonical compact JSON line. Binary document/source bytes use strict standard
base64 with explicit decoded SHA/bytes. Exact states are:

    WAIT_P2A_COMMIT
    WAIT_P2A_ACK
    WAIT_S1_COMMIT
    WAIT_S1_ACK
    WAIT_P2C_COMMIT
    WAIT_P2C_ACK
    WAIT_P3_ACK
    WAIT_P4_STRUCTURAL_ACK
    WAIT_P4_SKEPTICAL_ACK
    WAIT_P5_COMMIT
    WAIT_P5_ACK
    COMPLETE

Before each apply_patch or P3 publication, the parent sends exact intended bytes/bindings. The
supervisor stores them, returns a cryptographically random one-use nonce and READY state, and
accepts only the matching ACK after the external operation. It then independently re-reads live
content and advances once. Nonces provide ordering/continuity, not approval or identity.

Out-of-order/duplicate/noncanonical frame, nonce mismatch, process EOF, broken pipe, supervisor
exit, parent abandonment or content mismatch is authority-zero terminal. No later process may
reconstruct the original anchor from current files and resume the same R024. Receipt/source raw
commitments stay in supervisor memory; only the sealed anchor memfd is passed to test children.

## 4. I37 content registry and S0

Canonical order:

- rows 1~21: C0/R002/R016~R021 from R021
- rows 22~24: R022 roadmap/structural/skeptical
- rows 25~27: R023 roadmap/structural/skeptical
- rows 28~30: R024 roadmap/structural/skeptical
- rows 31~37: failed-r001 root/output/static/history/plan/package/checkpoint

The registry has exactly 37 rows and its canonical name is I37.

R024 rows:

| ordinal | row_kind | role | path |
|---:|---|---|---|
| 28 | FILE | r024_roadmap | docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R024.md |
| 29 | FILE | r024_structural_review | docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R024-independent-structural-review-r001.md |
| 30 | FILE | r024_skeptical_review | docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R024-independent-skeptical-review-r001.md |

FILE exact keys:

    bytes,ordinal,path,role,row_kind,semantic_reviewed_at,
    semantic_reviewed_at_ns,sha256

DIRECTORY exact keys:

    entry_count,entry_name_digest_sha256,ordinal,path,role,row_kind,
    semantic_reviewed_at,semantic_reviewed_at_ns

Review content has exactly one reviewed_at line, one status line, one findings line and one
authority_granted line. reviewed_at is canonical timezone-aware RFC3339 and physically
non-future at review creation. R024 dual reviews additionally satisfy §11 PASS.

S0 is exact three source rows with roles ending _s0 and ordinal core/builder/test 1/2/3.
It is live-equal until P2B begins and historical thereafter.

## 5. P2A v5 content receipt

After P1 PASS, one supervisor reads I37 and S0 twice with §2 safe readers. I0==I1 and S0a==S0b
means exact canonical content registries, not physical tuples.

causal_max is the Python lexical max of:

    (semantic_reviewed_at_ns, role)

for review rows with non-null semantic time. The supervisor reads observed_ns=time.time_ns,
selects microsecond floor selected_ns, and requires causal_max.ns < selected_ns <= observed_ns.
prepared_at is selected_ns rendered as Asia/Seoul
YYYY-MM-DDTHH:MM:SS.ffffff+09:00. postcheck_ns is then observed and requires
observed_ns <= postcheck_ns. It rechecks I/S0 and all target absence.

P2A exact identity:

    schema_version = walksafe.v2.5.r002-preparation-capture.v5
    receipt_id = WS-WALKSAFE-V25-R002-PREPARATION-CAPTURE-20260802-R024-R001
    roadmap_revision = R024

Top-level exact keys:

    authority_boundary,candidate_target,capture_algorithm,causal_max,
    immutable_causal_inputs,observation,pre_capture_state,receipt_id,
    roadmap_revision,schema_version,source_preimage

authority_boundary exact:

    applied:false
    approved:false
    canonical_write_authorized:false
    checkpoint_write_authorized:false
    effective:false
    evidence_only:true
    goal_write_authorized:false
    product_write_authorized:false

capture_algorithm exact:

    authority_model:"CANONICAL_CONTENT_CAS_V1"
    committed_selection_count:1
    physical_metadata_authority:false
    precision:"MICROSECOND_FLOOR"
    retry_same_receipt_allowed:false
    same_bytes_equivalent:true
    time_source:"time.time_ns"
    timezone:"Asia/Seoul"
    uncommitted_observations_have_authority:false

immutable_causal_inputs=I0 exact 37 rows, source_preimage=S0a, candidate_target=r002, and:

    observation = {observed_ns,postcheck_ns,prepared_at,selected_ns}
    pre_capture_state = {
      capture_target_absent:true,
      closure_target_absent:true,
      postimage_target_absent:true,
      r001_exact:true,
      r002_target_absent:true,
      source_mutation_started:false
    }

Raw is UTF-8 canonical JSON, ensure_ascii false, sort_keys true, compact separators,
allow_nan false and one LF. Strict parser rejects BOM, duplicate keys, non-finite, non-object,
bool-as-int, extra/missing keys and noncanonical raw.

The parent constructs exact raw in memory and publishes P2A with apply_patch Add File.
The supervisor validates exact raw and records:

    T0 = {path,sha256,bytes}

Existing/partial/noncanonical/different-byte/unsafe-envelope state is
NEW_REVISION_REQUIRED_TIMESTAMP_CAPTURE_AUTHORITY_ZERO. Same-byte inode/time change is
content-equivalent and not a terminal.

## 6. P2B S0-to-S1 and R002 source contract

The supervisor receives exact intended final path/SHA/bytes commitments before mutation.
One apply_patch call changes only core, builder and test. Afterward it validates I/T0, exact
three S1 content rows, each S1 hash differs from S0, and no other intended path changed.
It keeps the exact S1 content object in memory through P5. Different bytes or unsafe envelope
fails; same bytes at a different inode is equivalent.

R002 namespace and IDs:

    BUNDLE_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002
    APPLICATION_GATE_REL = docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-002
    V25_PLAN_FINAL_REL = APPLICATION_GATE_REL + "/application-transaction-plan.json"
    RESOLVED_OUTPUT_MANIFEST_REL = APPLICATION_GATE_REL + "/resolved-output-manifest.json"
    PACKAGE_CANDIDATE_ID = WS-V25-CONTROL-CANDIDATE-20260730-R002
    TRANSACTION_PLAN_ID = WS-V25-R022-APPLICATION-TRANSACTION-PLAN-20260730-R002
    DELEGATION_REQUIREMENT_ID = WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R024-R002
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

R022 in design-lineage IDs is intentional and does not grant rejected roadmap authority.
Historical r001/R001 allowlist is PLAN_ROOT, reviewed R002 directory, design R001, review suffix,
rejected history, failed-r001 and version-wide IDs only.

Physical prepared_at equals P2A selected time. TEST-only timestamps derive solely as
+5m/+10m/+15m/+15m30s/+16m/+26m and seq3=checked+1 microsecond. Independent calendar
literals are forbidden.

Provenance includes accepted R016, rejected R017~R023 trios, current R024 trio, P2A and P2C.
The latest user directive is exact 840 bytes /
8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a.
Truthful local-session delegation, no external signature claim, zero formal/device/release
credit and pre-C1 no-resume remain.

## 7. P2C v4 and original content anchor

After exact S1 capture the supervisor observes captured_ns and requires
captured_ns >= P2A selected_ns. P2C exact identity:

    schema_version = walksafe.v2.5.r002-source-postimage.v4
    receipt_id = WS-WALKSAFE-V25-R002-SOURCE-POSTIMAGE-20260802-R024-R001
    roadmap_revision = R024

Top-level exact keys:

    authority_boundary,capture_receipt_binding,captured_ns,receipt_id,
    roadmap_revision,schema_version,source_postimage,source_preimage_binding

capture_receipt_binding=T0. source_preimage_binding exact keys are
capture_receipt_path,source_preimage_sha256, where the digest is canonical JSON bytes of
P2A source_preimage without LF. source_postimage is exact three §2.1 rows with _s1 roles.
P2C contains no future candidate/review/closure hash or path.

Raw canonical rules equal P2A. apply_patch Add File publishes it; supervisor validates:

    T1 = {path,sha256,bytes}
    SOURCE_TRANSITION_ANCHOR = {
      preparation_capture_receipt_binding:T0,
      source_postimage_receipt_binding:T1,
      source_postimage:exact_S1_rows
    }

The exact canonical bytes of SOURCE_TRANSITION_ANCHOR without LF have SHA-256
ANCHOR_SHA256. Original object and hash remain in supervisor memory through P5.

Pre-P3 APIs have mandatory keyword-only expected object and no default/None/current fallback:

    validate_source_transition_evidence(root, *, expected_anchor)
    build_outputs(root, *, expected_source_transition_anchor)
    write_add_only(root, outputs, *, expected_source_transition_anchor)

Read-only child tests receive canonical anchor through a sealed memfd and require write/grow/
shrink/seal seals. Receipt/source paths themselves are re-read with content-CAS before/after
each child. Missing/unsealed/malformed/current-derived anchor fails.

## 8. candidate anchor and P3

Package and output manifest both add exact top-level /source_transition_evidence equal to
original anchor. Both logical seals cover it. All construction/validation/publication calls,
including immediately before rename and after parent fsync, compare to supervisor original.

Any existing r002 directory/file/symlink/exact target or rename EEXIST race is:

    NEW_REVISION_REQUIRED_EXISTING_R002_TARGET_AUTHORITY_ZERO

RECOVERED_EXACT_EXISTING and recovery helper are removed. Parent-fsync ambiguity leaves target
untouched and retry terminal. Unique success is PUBLISHED_NEW.

After P3 the supervisor records exact candidate binding:

    candidate_path
    entry_count:6
    entry_name_digest_sha256
    files:[six {path,sha256,bytes} in byte-sorted basename order]
    source_transition_anchor_sha256:ANCHOR_SHA256

CANDIDATE_BINDING_SHA256 is SHA-256 of canonical JSON bytes of that exact object without LF.
It does not end here.

## 9. P4 original challenge and dual candidate review

The supervisor constructs canonical REVIEW_CHALLENGE before either reviewer starts:

    schema_version = walksafe.v2.5.r002-review-challenge.v1
    challenge_id = WS-WALKSAFE-V25-R002-REVIEW-CHALLENGE-20260802-R024-R001
    roadmap_revision = R024
    authority_boundary = §5 zero-authority object
    source_transition_anchor = original anchor
    source_transition_anchor_sha256 = ANCHOR_SHA256
    candidate_binding = exact P3 binding
    p2a = T0
    p2c = T1
    source_postimage = exact S1 rows

Challenge raw is canonical JSON plus LF. Its hash/bytes and exact object are sent in each
reviewer's task input before that reviewer reads candidate files. This orchestrator task input
is the candidate-external expected value for P4; reviewers may not derive or replace it from
current candidate. A mismatch to any challenge field is a finding.

The supervisor remains alive and revalidates original content immediately before each task,
between the two reviews and after both review files appear. Review files must record:

    challenge_sha256
    challenge_bytes
    source_transition_anchor_sha256
    candidate_binding_sha256
    target file SHA/bytes for all six
    review start and end equality to challenge

PASS exact:

    PASS_FOR_NON_EFFECTIVE_V25_CANDIDATE_ONLY
    BLOCKING=0 MAJOR=0 MINOR=0
    authority=NONE_FOR_ACTIVATION_GOAL_PRODUCT

Any challenge/current mismatch, supervisor loss, coordinated reseal or finding prevents P5 and
is NEW_REVISION_REQUIRED_POST_P3_RESEAL_AUTHORITY_ZERO. Current candidate is never an expected
value source during P4.

## 10. P5 reviewed closure receipt and handoff

After both P4 PASS and final original-content check, the still-running supervisor constructs:

    schema_version = walksafe.v2.5.r002-reviewed-closure.v1
    closure_id = WS-WALKSAFE-V25-R002-REVIEWED-CLOSURE-20260802-R024-R001
    roadmap_revision = R024
    authority_boundary = §5 zero-authority object
    challenge_binding = {sha256,bytes}
    source_transition_evidence = original anchor
    source_transition_anchor_sha256 = ANCHOR_SHA256
    candidate_binding = original P3 binding
    candidate_reviews = [
      {path,sha256,bytes,status,findings,authority},
      {path,sha256,bytes,status,findings,authority}
    ]
    closure_observation = {closed_at,closed_ns}

Top-level exact keys are the keys shown above. Lists have structural then skeptical order.
closed_at is canonical Asia/Seoul microsecond RFC3339 derived from closed_ns and is later than
both review semantic times.

P5 raw is canonical JSON plus LF and is added once with apply_patch. Existing/partial/
noncanonical/different-byte is terminal. The supervisor validates P5 content against its
original memory, rechecks all bound contents, then may end.

The parent records exact P5 path/SHA/bytes in daylog and local-memory and reports it in the
user-visible final response. Those three handoff channels are not claimed as signatures; they
are the candidate-external expected content binding. Future R025 must pin that exact binding
before it may derive candidate expected state. Live candidate or live closure without the
handoff binding is not sufficient.

## 11. R024 dual plan reviews

Exact targets:

    docs/control/execution/artifact-closure/run-20260727-001/
    WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R024-independent-structural-review-r001.md
    docs/control/execution/artifact-closure/run-20260727-001/
    WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R024-independent-skeptical-review-r001.md

They pin this roadmap exact SHA/bytes/lines and use canonical physically non-future reviewed_at.
They explicitly audit the content-equivalence threat model, apply_patch compatibility,
safe-reader envelope, I37/P2A/P2C, mandatory anchor, supervisor through P5, reviewer challenge,
closure external binding, candidate pointer/DAG, R002/r001/retry/37 tests and zero authority.

PASS only:

    status=PASS
    findings=BLOCKING=0 MAJOR=0 MINOR=0
    authority_granted=R024_R002_CONTENT_CAS_SOURCE_CANDIDATE_CLOSURE_ONLY

Any finding means P2 onward write 0 and a new roadmap.

## 12. tests and execution gates

After R024 dual PASS:

1. I37/S0/r001/all future target absence
2. start supervisor with exact I/S0 and future P2A raw
3. P2A/T0 content binding
4. one-patch S0-to-S1
5. P2C/T1/original anchor
6. AST/duplicate/trailing/namespace/legacy/calendar scans
7. exact existing 37 unittest methods, skip0/exit0/OK under original sealed anchor
8. r002 PUBLISHED_NEW
9. postbuild 37, builder --check, continuation CANDIDATE, Goal CANDIDATE under original anchor
10. P4 challenge-based dual candidate review
11. P5 closure and final content equality

Existing 37 methods gain subcases only:

- exact I37/P2A v5/P2C v4 canonical keys, strict JSON and content rows,
- same-byte inode/touch acceptance and different-byte/symlink/hardlink/unsafe envelope rejection,
- omitted/None/current-live/unsealed anchor rejection,
- S1+P2C and package/output coordinated reseal against original expected anchor,
- package/output pointer missing/mismatch/resealed mutation,
- build-to-rename content drift prevents rename,
- exact-existing/file/symlink/rename race/fsync ambiguity terminal,
- R002 paths/IDs/timestamps, failed-r001 semantics and read-only content equality,
- review challenge and closure current-derived fallback negatives.

Read-only checks snapshot exact content bindings before/after. They do not claim inode/time
immutability. Candidate check after P5 requires caller-supplied exact closure binding or a
future roadmap pin; pre-P5 uses supervisor original anchor.

## 13. successors and success

Because R023 was rejected, successors shift:

1. R025 activation transaction, requiring exact P5 closure binding
2. R026 Goal replay/full19
3. R027 FP008 materialized/ready/GOAL_STARTED
4. R028 Android minimal read-only slice

R025 before canonical/checkpoint, R026 before Goal, R027 valid start before product write: zero.

Success conjunction:

    R024_DUAL_PLAN_REVIEW_PASS
    AND I37_S0_CONTENT_VALID
    AND P2A_V5_CONTENT_BINDING
    AND S0_TO_S1_EXACT
    AND P2C_V4_ORIGINAL_CONTENT_ANCHOR
    AND PREBUILD_37_PASS_SKIP_ZERO
    AND R002_PUBLISHED_NEW
    AND POSTBUILD_37_AND_CHECKERS_PASS
    AND ORIGINAL_CHALLENGE_P4_DUAL_PASS
    AND P5_REVIEWED_CLOSURE_VALID
    AND P5_EXTERNAL_HANDOFF_BINDING_RECORDED
    AND R001_C0_R002_R021_CONTENT_UNCHANGED
    AND CANONICAL_CHECKPOINT_GOAL_PRODUCT_DELTA_ZERO

Result remains
REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED.
