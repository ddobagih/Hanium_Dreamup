# WalkSafe v2.5 비효력 후보 correction 로드맵 R022

- 작성일: `2026-08-02`
- 상태: `PENDING_TWO_INTERNAL_REVIEWS`
- replaces: rejected R021
- 현 정본: v2.4 / checkpoint sequence 39 / Gap·Backlog r021
- 이번 종착점: `REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`
- canonical·checkpoint·Goal·제품 write 권한: `0`

R021 structural review는 `BLOCKING=2`, skeptical review는 `BLOCKING=1`로 거부됐다.
두 review는 exact I registry, S0 cutoff, P2A/T0 source handoff, R002 namespace/r001/retry와
37-test universe는 닫혔다고 확인했다. 남은 결함은 P2C canonical bytes/schema가
유일하지 않고, P2C 공개 뒤 최초 T1/S1 anchor가 P3 전에 영속 승계되지 않아 coupled
`S1+P2C` rewrite를 현재값으로 재채택할 수 있다는 점이다.

R022는 R021의 닫힌 계약을 유지하고 P2C→P3를 다음으로 교체한다.

```text
S0 → P2A/T0 → S1 → canonical P2C/T1
   → mandatory in-memory anchor under one uninterrupted supervisor
   → r002 package + output-manifest exact anchor
```

P3 전에는 같은 supervisor의 original T1/S1만 authority다. P3 뒤에는 immutable r002의
두 exact JSON pointers가 authority를 승계한다. current live P2C를 새 baseline으로
채택하는 fallback은 없다. activation·Goal·제품은 R023 이후다.

## 1. frozen chain

### 1.1 content pins

| role | SHA-256 | bytes |
|---|---|---:|
| C0 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| R002 pair | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| R002 review | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| accepted R016 | `49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2` | 12,690 |
| R016 structural | `eced95aa2343427b24f80f8865e2fa8e9e3f320ad48c74fab7970d646a1fac87` | 3,758 |
| R016 skeptical | `9bae362ad4c1f0c4b2567b534197938f77d825b5d73d76dbd3a184542649bc0e` | 5,354 |
| rejected R017 | `6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5` | 13,494 |
| R017 structural | `cbcb6a14e4af2b82ec84c300799575fd99a81b65e9e8236177ff7221be958e81` | 9,466 |
| R017 skeptical | `d10ba282eee810c58a0d76d8658a4a21d937dcd4c76882a4936dc97538d61fe4` | 5,326 |
| rejected R018 | `2a1df2cd5f0fdccb3183b15607b6a017ab6b2556d661ea16e9b6caaa9d9255f7` | 17,914 |
| R018 structural | `a87d59d2a3a5bbf601b4c3d9c3fdf8adaa1511c99e1c538a03db7be474a3b584` | 8,180 |
| R018 skeptical | `8fa2bfe82ac5b0b4a7a9d3708f9cf3407e995c8c82bd74d79ce2e8a4161879a4` | 4,551 |
| rejected R019 | `3c21f917793b0ffbe5f6aa892e1d73d122799fcfe005c22f2f289571b45d4eb2` | 21,461 |
| R019 structural | `97bfa16d3c0f02e6ac601c92bc78c19779438948fc089624dfa9dcc235c0a548` | 11,122 |
| R019 skeptical | `b2995ccd45980cfecd422f88bd02aed6d98b42f445824a29876336efb7e1d8fc` | 7,168 |
| rejected R020 | `276f499ce235289cbe7b6f15abc835ce87ed0ce8ccd76d03df904bc5f5cd44f1` | 25,239 |
| R020 structural | `9b378e4555c6c3a1edf1e712acb90f5f51089ed469116b552b1be496f7058316` | 9,096 |
| R020 skeptical | `8ac10e419f31409e5062cf82ab1066f3dafaff6eaaed63155662c3ff56ca9690` | 10,753 |
| rejected R021 | `b65d0242656b5898392fcab2b168832a6ce19577fa5e469cbbce2cfda777b919` | 28,757 |
| R021 structural | `d9dc411e51d3e6a724bb626630b20de8f258c6659829475d98c81903eee26a94` | 9,574 |
| R021 skeptical | `4e751ad91a3efec2546949ad393fa16692fb268300aea6df6aeeafeae068b93b` | 8,170 |

R016 only accepted; R017~R021/reviews authority `NONE`. latest directive is exact 840 bytes /
`8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a`.
No external identity/signature/server timestamp/message ID/approval token.

### 1.2 S0 exact

| path | SHA-256 | bytes |
|---|---|---:|
| `scripts/walksafe_v2_5_candidate_validation.py` | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

No correction under R017~R021 ran. r002 and R022 P2A/P2C targets are absent.

## 2. immutable failed r001

R021 §2 is imported without change and has precedence over no other R022 clause. Exact root:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001/
```

Exact six SHA/bytes:

```text
candidate-output-manifest.json
  ffd8e1616891f1d3fc75c24bcbfc9d676760afe786fe19d63218c66024119945 / 19757
static-plan-manifest-v2.5.0.candidate.json
  213cdea4f061d77f587211b3c315f5fb27eb5a2f39dbe7a9382e2c34e7a29a68 / 34117
transition-history-v2.5.candidate.json
  1b859617a6cc7a007a9cdca61f10d5b040ba743bbd90bd5f7183163a8a1f494e / 2676
v2.5-application-transaction-plan.candidate.json
  bb7819d4c3cc27878685fea3d4764ef7d55d511674eb078fc0cce8bfc0840275 / 659092
v2.5-control-package-manifest.candidate.json
  3e27d14670c804bcc6df202f360a9a0541c1b2a83b3ad759961d2319a577261d / 27037
walksafe-project-continuation-checkpoint-v2.5.candidate.json
  273b320645ee17737d452af263d45b45058cd7ed012f0bf32c6a9345ae894864 / 218564
```

Root and six full fixed `lstat` tuples are exactly R021 §2.2 and are rechecked every epoch.
Root non-symlink directory, exact-six regular, no extra/missing/hardlink. Old test pin is
`5f1e80f3...d46459` / 68,995. seq1-only and zero-authority. Any content/tuple drift stops;
no repair/resume.

## 3. epochs

1. P0 R022 roadmap
2. P1 R022 dual plan reviews
3. P2A R022 preparation capture receipt
4. P2B exact S0→S1 three-source patch
5. P2C canonical source-postimage receipt and T1 capture
6. P3 r002 exact-six publication under uninterrupted supervisor
7. P4 r002 dual candidate reviews

Exact write targets:

```text
P2A = docs/control/execution/artifact-closure/run-20260727-001/walksafe-v25-r002-preparation-capture-20260802-r022-r001.json
P2B =
  scripts/walksafe_v2_5_candidate_validation.py
  scripts/build_walksafe_v2_5_control_candidate_20260730.py
  tests/test_walksafe_v2_5_control_candidate_20260730.py
P2C = docs/control/execution/artifact-closure/run-20260727-001/walksafe-v25-r002-source-postimage-20260802-r022-r001.json
P3 = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/
P4 =
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-structural-review-r001.md
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-skeptical-review-r001.md
```

Other writes are zero.

## 4. exact I registry and S0

### 4.1 exact 31 immutable rows

Rows 1–21 are byte-for-byte registry values from R021 §4.1:
C0, R002 pair/review, R016 through R021 roadmaps and each structural/skeptical review.
R022 adds:

| ordinal | row_kind | role | path |
|---:|---|---|---|
| 22 | FILE | `r022_roadmap` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R022.md` |
| 23 | FILE | `r022_structural_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R022-independent-structural-review-r001.md` |
| 24 | FILE | `r022_skeptical_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R022-independent-skeptical-review-r001.md` |

R021 rows 22–28 are unchanged except ordinals become 25–31:
failed-r001 root, output manifest, static, history, plan, package, checkpoint in that exact order.

FILE/DIRECTORY schemas, `lstat` exact 9 integer fields, r001 NUL-name digest, review
`semantic_reviewed_at` parsing and `(ns,role,component)` max candidate rules are exactly R021
§4.2/§6.1. Component enum is exact
`mtime_ns|ctime_ns|semantic_reviewed_at_ns`, null semantic excluded. Canonical list order is
ordinal ascending. R022 dual reviews require canonical reviewed_at physically non-future,
PASS 0/0/0 and exact R022 authority.

### 4.2 S0 three rows

Exact S0 registry is R021 §5 except role suffix remains `_s0`, order core,builder,test,
ordinals 1,2,3. FILE schema exact, semantic fields null. It is live-equal only through the
instant before P2B begins, then historical.

## 5. P2A capture and T0

R021 §6 algorithm is imported with these exact substitutions:

```text
schema_version = walksafe.v2.5.r002-preparation-capture.v3
receipt_id = WS-WALKSAFE-V25-R002-PREPARATION-CAPTURE-20260802-R022-R001
roadmap_revision = R022
P2A path = R022 path in §3
I row count = 31
```

All other canonical JSON keys/options, I0==I1, S0a==S0b, exact causal max,
`max < selected <= observed <= postcheck`, microsecond floor/Asia-Seoul, one committed receipt,
uncommitted observation authority zero, target-absence and no-resume terminal remain.

After P2A add, same supervisor captures:

```text
T0 = {path,sha256,bytes,lstat:{dev,ino,mode,uid,gid,nlink,size,mtime_ns,ctime_ns}}
```

P2B core/builder/test independently embed exact T0 plus causal max/observed/selected/postcheck/
prepared_at. Same supervisor holds T0 until P2B completes; source then validates it in every
new process. Crash/drift means
`NEW_REVISION_REQUIRED_TIMESTAMP_CAPTURE_AUTHORITY_ZERO`.

## 6. P2B S0→S1

One `apply_patch` call changes exact three files. Before: I/T0/S0 exact. After:

- I/T0 exact
- same three paths only
- each S1 SHA differs from S0 SHA
- S1 mtime_ns/ctime_ns >= selected_ns
- all embedded T0/timestamp/provenance constants equal

No S0 live equality after transition. Partial/crash/extra/mismatch has no repair/resume.

R002 namespace and IDs:

```text
BUNDLE_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002
APPLICATION_GATE_REL = docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-002
V25_PLAN_FINAL_REL = ${APPLICATION_GATE_REL}/application-transaction-plan.json
RESOLVED_OUTPUT_MANIFEST_REL = ${APPLICATION_GATE_REL}/resolved-output-manifest.json
PACKAGE_CANDIDATE_ID = WS-V25-CONTROL-CANDIDATE-20260730-R002
TRANSACTION_PLAN_ID = WS-V25-R022-APPLICATION-TRANSACTION-PLAN-20260730-R002
DELEGATION_REQUIREMENT_ID = WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R022-R002
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
```

Candidate review paths are exact r002 with review suffix r001. Intentional historical r001/R001
is exact R021 allowlist only.

Physical prepared_at is P2A selected. Synthetic TEST offsets remain
`+5m,+10m,+15m,+15m30s,+16m,+26m`, seq2 checked, seq3 checked+1us. No independent calendar
literal; strict chronology/599/600/601/timezone negatives remain.

Provenance adds rejected R021 trio and current R022 trio/P2A/P2C. Failed-r001 validator,
truthful delegation/full directive/zero credits/pre-C1 no-resume remain R021 §§10–11.

## 7. P2C canonical document

### 7.1 raw serialization

```text
P2C_RAW = canonical_bytes(P2C_DOCUMENT) + b"\n"
```

`canonical_bytes` means UTF-8, `ensure_ascii=False`, `sort_keys=True`,
`separators=(",",":")`, `allow_nan=False`; strict parse rejects BOM, duplicate keys,
non-finite values, non-object roots and noncanonical raw bytes. All integer checks use
`type(value) is int`, so bool is forbidden.

`source_preimage_sha256 =
sha256(canonical_bytes(P2A_DOCUMENT["source_preimage"]))` with no terminal LF.

### 7.2 exact top-level/nested schemas

Top-level exact keys:

```text
authority_boundary,capture_receipt_binding,captured_ns,receipt_id,
roadmap_revision,schema_version,source_postimage,source_preimage_binding
```

Exact scalar values:

```text
schema_version = walksafe.v2.5.r002-source-postimage.v2
receipt_id = WS-WALKSAFE-V25-R002-SOURCE-POSTIMAGE-20260802-R022-R001
roadmap_revision = R022
```

`authority_boundary` exact keys/values:

```text
applied:false,approved:false,canonical_write_authorized:false,
checkpoint_write_authorized:false,effective:false,evidence_only:true,
goal_write_authorized:false,product_write_authorized:false
```

`capture_receipt_binding` exact keys are `bytes,lstat,path,sha256` and exact value is T0.
`source_preimage_binding` exact keys are
`capture_receipt_path,source_preimage_sha256`; path=P2A.

`source_postimage` exact list:

| ordinal | role | path |
|---:|---|---|
| 1 | `source_validation_core_s1` | `scripts/walksafe_v2_5_candidate_validation.py` |
| 2 | `source_builder_s1` | `scripts/build_walksafe_v2_5_control_candidate_20260730.py` |
| 3 | `source_test_s1` | `tests/test_walksafe_v2_5_control_candidate_20260730.py` |

Each row uses exact R021 §4.2 FILE keys and 9-field `lstat`; semantic fields are null.
`captured_ns` is int and
`captured_ns >= max(all S1 mtime_ns, all S1 ctime_ns)`.

P2C contains no candidate/package/output/review/future receipt hash/path.

### 7.3 P2C publication terminal

P2C target must be absent immediately before `apply_patch Add File`. Existing/partial/drift,
serialization mismatch or crash after publication is:

```text
NEW_REVISION_REQUIRED_SOURCE_POSTIMAGE_AUTHORITY_ZERO
```

No same R022 retry/repair/replacement.

## 8. T1/S1 uninterrupted supervisor and r002 anchor

### 8.1 initial T1 anchor

Immediately after successful P2C add, the same supervisor reads no-follow and captures:

```text
SOURCE_TRANSITION_ANCHOR = {
  preparation_capture_receipt_binding: T0,
  source_postimage_receipt_binding: {
    path:P2C, sha256, bytes,
    lstat:{dev,ino,mode,uid,gid,nlink,size,mtime_ns,ctime_ns}
  },
  source_postimage: exact P2C source_postimage
}
```

It requires `captured_ns <= min(P2C lstat.mtime_ns,P2C lstat.ctime_ns)`, I/T0 exact and live S1
exact equal to anchor.

### 8.2 mandatory pre-P3 API

Core/builder expose:

```text
validate_source_transition_evidence(root, *, expected_anchor)
build_outputs(root, *, expected_source_transition_anchor)
write_add_only(root, outputs, *, expected_source_transition_anchor)
```

Before P3, expected anchor is mandatory and `None`/current-live adoption is forbidden.
Every construction/validation/test/publication boundary compares I/T0/P2C T1/live S1 to the same
object. Build after validation and immediately before rename repeats it.

P2C add through P3 parent-fsync runs under one uninterrupted supervisor retaining the original
object in memory. It may run read-only test subprocesses only while bracketing them with anchor
checks and passing the exact expected object; no child may select current P2C as baseline.
Supervisor loss/crash/anchor mismatch/publication ambiguity is the P2C terminal and requires new
roadmap/candidate revision.

### 8.3 exact candidate anchor

Package and output manifest both add exact top-level JSON pointer:

```text
/source_transition_evidence
```

Its exact value is `SOURCE_TRANSITION_ANCHOR`; both documents must be deep-equal. The package
seal and output-manifest seal include the field. Candidate validation before P3 requires equality
to supervisor expected anchor.

After P3, validators derive expected anchor only from the sealed candidate:

1. package/output `/source_transition_evidence` exact schema and equality
2. live P2A equals embedded T0
3. live P2C full tuple/content equals embedded T1
4. live S1 full tuples/content equal embedded source_postimage

Thus candidate is the one-way persistent anchor. No P2D and no self/future cycle:

```text
S0 → P2A/T0 → S1 → P2C/T1 → package → output manifest
```

## 9. r002 publication

Any existing r002 directory/file/symlink/exact target or rename race:

```text
NEW_REVISION_REQUIRED_EXISTING_R002_TARGET_AUTHORITY_ZERO
```

Remove `_recover_exact_existing`/`RECOVERED_EXACT_EXISTING`. Parent-fsync ambiguity leaves target
untouched and retry terminal. Unique write success `PUBLISHED_NEW`. Post-publication `--check`
and wrappers are read-only and use candidate anchor.

## 10. R022 plan reviews

Exact:

```text
docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R022-independent-structural-review-r001.md
docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R022-independent-skeptical-review-r001.md
```

Audit R021 findings, P2C unique bytes/schema, T1 supervisor/API/terminal, exact candidate pointers,
I31/S0/S1/T0, namespace/r001/retry/37 tests/authority. Each canonical reviewed_at is physically
non-future.

PASS only:

```text
status=PASS
findings=BLOCKING=0 MAJOR=0 MINOR=0
authority_granted=R022_R002_CAPTURE_SOURCE_CANDIDATE_CORRECTION_ONLY
```

Any finding: P2A onward zero/new roadmap.

## 11. pre-build, publication and post-build

P1 PASS then:

1. I31/S0/r001/all target absence
2. P2A/T0
3. one-patch S0→S1
4. P2C/T1 anchor; uninterrupted supervisor starts
5. AST/duplicate/trailing/namespace/legacy/calendar scans
6. targeted 37 unittest, skip0/exit0/OK, bracketed by expected anchor
7. P3 exact `PUBLISHED_NEW` before supervisor ends
8. postbuild 37 unittest, builder `--check`, continuation CANDIDATE, Goal CANDIDATE
9. final I/T0/T1/S1/r001/r002/C0/R002/r021 equality

Existing 37 methods gain subcases only:

- P2A I31/S0/max/T0
- P2C canonical/schema/T1 and expected-anchor mandatory
- coupled S1/P2C rewrite, T1 nine fields, source rows, pointer mismatch negatives
- build-to-rename drift prevents rename
- namespace/IDs/timestamps/r001/retry existing axes

r002 root/exact-six full no-follow tuple/hash/NUL-name digest are unchanged across read-only checks.
P4 candidate reviews independently rerun exact anchors/gates and require:

```text
PASS_FOR_NON_EFFECTIVE_V25_CANDIDATE_ONLY
BLOCKING=0 MAJOR=0 MINOR=0
authority=NONE_FOR_ACTIVATION_GOAL_PRODUCT
```

## 12. successors and success

1. R023 activation transaction
2. R024 Goal replay/full19
3. R025 FP008 materialized/ready/GOAL_STARTED
4. R026 Android minimal read-only slice

R023 before canonical/checkpoint, R024 before Goal, R025 valid start before product write: zero.

```text
R022_DUAL_PLAN_REVIEW_PASS
AND I31_S0_P2A_T0_VALID
AND S0_TO_S1_EXACT
AND P2C_CANONICAL_T1_VALID
AND UNINTERRUPTED_T1_SUPERVISOR_TO_P3
AND R002_PERSISTENT_SOURCE_TRANSITION_ANCHOR
AND R001_IMMUTABLE
AND PREBUILD_37_PASS_SKIP_ZERO
AND R002_PUBLISHED_NEW
AND POSTBUILD_37_AND_CHECKERS_PASS
AND R002_DUAL_CANDIDATE_REVIEW_PASS
AND LIVE_C0_R021_UNCHANGED
AND CANONICAL_CHECKPOINT_GOAL_PRODUCT_DELTA_ZERO
```

Result remains
`REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`.
daylog/local-memory at parent handoff only.
