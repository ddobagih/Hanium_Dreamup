# WalkSafe v2.5 비효력 후보 correction 로드맵 R020

- 작성일: `2026-08-02`
- 상태: `PENDING_TWO_INTERNAL_REVIEWS`
- replaces: rejected R019
- 현 정본: v2.4 / checkpoint sequence 39 / Gap·Backlog r021
- 이번 종착점: `REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`
- live canonical·checkpoint·Goal·제품 write 권한: `0`

R019은 구조·회의적 검수 모두 `BLOCKING=1 MAJOR=0 MINOR=0`으로 거부됐다.
고정 과거 시각 문제는 없앴지만, timestamp 선택에 사용한 predecessor `lstat` snapshot과
raw 관측값을 영속하지 않고 source patch 뒤와 P3 직전에 exact equality를 재확인하지 않아
same-bytes metadata drift가 stale lower bound를 통과할 수 있었다.

R020은 R019의 모든 계약을 유지하면서 P2를 두 epoch로 나눈다. P2A는 timestamp와
causal-input full snapshot을 별도 add-only canonical JSON receipt에 먼저 봉인한다.
P2B source와 이후 builder/checker는 그 receipt를 content pin으로 결속하고, P3 rename
직전까지 receipt snapshot과 live physical identity의 exact equality를 반복 검증한다.
R016의 truthful delegation, zero-credit, strict chronology, monotonic freshness,
pre-C1 no-resume와 R018의 R002 namespace/r001 preservation/retry closure는 유지한다.

activation·Goal·제품 단계는 R021 이후로 밀린다.

## 1. frozen chain

### 1.1 canonical, reviewed and rejected inputs

| 역할 | SHA-256 | bytes |
|---|---|---:|
| C0 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| R002 pair manifest | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| R002 PASS review | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| R016 roadmap | `49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2` | 12,690 |
| R016 structural PASS | `eced95aa2343427b24f80f8865e2fa8e9e3f320ad48c74fab7970d646a1fac87` | 3,758 |
| R016 skeptical PASS | `9bae362ad4c1f0c4b2567b534197938f77d825b5d73d76dbd3a184542649bc0e` | 5,354 |
| rejected R017 | `6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5` | 13,494 |
| R017 structural | `cbcb6a14e4af2b82ec84c300799575fd99a81b65e9e8236177ff7221be958e81` | 9,466 |
| R017 skeptical | `d10ba282eee810c58a0d76d8658a4a21d937dcd4c76882a4936dc97538d61fe4` | 5,326 |
| rejected R018 | `2a1df2cd5f0fdccb3183b15607b6a017ab6b2556d661ea16e9b6caaa9d9255f7` | 17,914 |
| R018 structural | `a87d59d2a3a5bbf601b4c3d9c3fdf8adaa1511c99e1c538a03db7be474a3b584` | 8,180 |
| R018 skeptical | `8fa2bfe82ac5b0b4a7a9d3708f9cf3407e995c8c82bd74d79ce2e8a4161879a4` | 4,551 |
| rejected R019 | `3c21f917793b0ffbe5f6aa892e1d73d122799fcfe005c22f2f289571b45d4eb2` | 21,461 |
| R019 structural | `97bfa16d3c0f02e6ac601c92bc78c19779438948fc089624dfa9dcc235c0a548` | 11,122 |
| R019 skeptical | `b2995ccd45980cfecd422f88bd02aed6d98b42f445824a29876336efb7e1d8fc` | 7,168 |

R016은 accepted predecessor다. R017/R018/R019와 각 review는 rejected history이며
실행 권한을 주지 않는다. 최신 사용자 지시는 trailing LF 없는 840 UTF-8 bytes,
SHA-256 `8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a`다.
외부 identity/signature/server timestamp/message ID/approval token은 주장하지 않는다.

### 1.2 source preimage and current truth

| path | SHA-256 | bytes |
|---|---|---:|
| `scripts/walksafe_v2_5_candidate_validation.py` | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

세 source에는 아직 R016의 r001 namespace, 과거 timestamp와 exact-existing recovery가
남아 있다. R017~R019 아래 source correction은 실행되지 않았다. test preimage만 r001
발행 뒤 absent/present dual-state oracle로 수정됐고, 이 때문에 immutable r001의 embedded
old test binding과 deterministic mismatch가 발생했다. physical r002와 capture receipt는
P0 시점에 absent다. P2B는 timestamp만 고치는 것이 아니라 §5~§9 전체 correction을 한
번에 수행한다.

## 2. immutable failed r001

root:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001/
```

### 2.1 content

| file | SHA-256 | bytes |
|---|---|---:|
| `static-plan-manifest-v2.5.0.candidate.json` | `213cdea4f061d77f587211b3c315f5fb27eb5a2f39dbe7a9382e2c34e7a29a68` | 34,117 |
| `v2.5-application-transaction-plan.candidate.json` | `bb7819d4c3cc27878685fea3d4764ef7d55d511674eb078fc0cce8bfc0840275` | 659,092 |
| `transition-history-v2.5.candidate.json` | `1b859617a6cc7a007a9cdca61f10d5b040ba743bbd90bd5f7183163a8a1f494e` | 2,676 |
| `walksafe-project-continuation-checkpoint-v2.5.candidate.json` | `273b320645ee17737d452af263d45b45058cd7ed012f0bf32c6a9345ae894864` | 218,564 |
| `v2.5-control-package-manifest.candidate.json` | `3e27d14670c804bcc6df202f360a9a0541c1b2a83b3ad759961d2319a577261d` | 27,037 |
| `candidate-output-manifest.json` | `ffd8e1616891f1d3fc75c24bcbfc9d676760afe786fe19d63218c66024119945` | 19,757 |

r001은 old test SHA
`5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459`,
68,995 bytes를 봉인했다. seq1-only, `effective=false, approved=false, applied=false`,
candidate reviews absent 상태다.

### 2.2 exact physical seal

| entry | dev | ino | mode | uid | gid | nlink | size | mtime_ns | ctime_ns |
|---|---:|---:|---|---:|---:|---:|---:|---:|---:|
| root | 66306 | 18353191 | `0o40700` | 1000 | 1000 | 2 | 4096 | 1785636122885202981 | 1785636130919378520 |
| `candidate-output-manifest.json` | 66306 | 18353197 | `0o100644` | 1000 | 1000 | 1 | 19757 | 1785636122885202981 | 1785636122885202981 |
| `static-plan-manifest-v2.5.0.candidate.json` | 66306 | 18353192 | `0o100644` | 1000 | 1000 | 1 | 34117 | 1785636122881202893 | 1785636122881202893 |
| `transition-history-v2.5.candidate.json` | 66306 | 18353194 | `0o100644` | 1000 | 1000 | 1 | 2676 | 1785636122883202937 | 1785636122883202937 |
| `v2.5-application-transaction-plan.candidate.json` | 66306 | 18353193 | `0o100644` | 1000 | 1000 | 1 | 659092 | 1785636122882202915 | 1785636122882202915 |
| `v2.5-control-package-manifest.candidate.json` | 66306 | 18353196 | `0o100644` | 1000 | 1000 | 1 | 27037 | 1785636122884202959 | 1785636122884202959 |
| `walksafe-project-continuation-checkpoint-v2.5.candidate.json` | 66306 | 18353195 | `0o100644` | 1000 | 1000 | 1 | 218564 | 1785636122883202937 | 1785636122883202937 |

root는 non-symlink directory, exact six는 non-symlink regular file이고 extra/missing/
hardlink가 없다. 위 content와 tuple은 P2A 전, P2A 후, P2B 후, P3 직전/직후, P4 종료에
exact equality여야 한다. delete/overwrite/rename/repair/chmod/chown/link/resume은 0이다.

## 3. epochs and allowlists

1. P0 — R020 roadmap add-only
2. P1 — R020 plan review 두 개 add-only
3. P2A — timestamp capture receipt 한 개 add-only
4. P2B — three-source correction 한 번
5. P3 — r002 exact six-output root add-only publication
6. P4 — r002 candidate review 두 개 add-only

P2A target:

```text
docs/control/execution/artifact-closure/run-20260727-001/walksafe-v25-r002-preparation-capture-20260802-r020-r001.json
```

P2B allowlist:

```text
scripts/walksafe_v2_5_candidate_validation.py
scripts/build_walksafe_v2_5_control_candidate_20260730.py
tests/test_walksafe_v2_5_control_candidate_20260730.py
```

P3 target:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/
```

P4 targets:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-structural-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-skeptical-review-r001.md
```

그 밖의 write는 0이다. real receipt/resolved output/active final/canonical r022/checkpoint/
Goal/full19/제품은 범위 밖이다.

## 4. P2A persistent causal capture

### 4.1 exact causal-input universe

capture는 다음을 exact role/path로 열거한다.

1. C0 checkpoint, R002 pair manifest와 PASS review
2. R016 roadmap와 두 PASS reviews
3. R017, R018, R019 roadmap과 각 두 rejected reviews
4. R020 roadmap과 두 PASS reviews
5. immutable r001 root와 exact six
6. §1.2 three-source preimage

각 file row는 다음 exact 필드를 가진다.

```text
role
path
sha256
bytes
lstat = {dev,ino,mode,uid,gid,nlink,size,mtime_ns,ctime_ns}
semantic_reviewed_at = canonical RFC3339 string or null
semantic_reviewed_at_ns = integer or null
```

r001 root row는 file SHA 대신 sorted exact-six NUL-name digest와 full `lstat`을 가진다.
모든 path는 repository root에 confined하고 no-follow로 읽는다. review file에
`reviewed_at = ...` line이 있으면 정확히 하나만 허용하고 timezone-aware canonical
RFC3339로 parse한다. R020 두 reviews는 그 line이 반드시 있어야 하며, 다음을 만족한다.

```text
semantic_reviewed_at_ns <= min(review st_mtime_ns, review st_ctime_ns)
status = PASS
findings = BLOCKING=0 MAJOR=0 MINOR=0
authority_granted = R020_R002_CAPTURE_AND_CANDIDATE_CORRECTION_ONLY
```

`CAUSAL_MAX_NS`는 모든 row의 `mtime_ns`, `ctime_ns`, non-null
`semantic_reviewed_at_ns` 최댓값이다. 동률이면 `(ns, role, component)` lexical
ascending의 마지막 row를 선택해 max provenance를 결정한다.

### 4.2 one-shot observation algorithm

P1, §2 seal, §1.2 preimage, r002 absence와 capture-target absence를 같은 process에서
확인한 뒤:

1. §4.1 snapshot과 `CAUSAL_MAX_NS`를 계산한다.
2. `observed_ns = time.time_ns()`를 읽고
   `selected_ns = observed_ns - observed_ns % 1000`으로 microsecond floor한다.
3. `selected_ns > CAUSAL_MAX_NS`가 아니면 mutation 없이 다시 관측한다.
4. 첫 성립값을 Asia/Seoul
   `YYYY-MM-DDTHH:MM:SS.ffffff+09:00`으로 직렬화해 `PREPARED_AT`으로 고정한다.
5. 즉시 `postcheck_ns = time.time_ns()`를 읽고
   `CAUSAL_MAX_NS < selected_ns <= observed_ns <= postcheck_ns`를 요구한다.
6. snapshot을 다시 읽어 step 1과 full exact equality이고 current max가 같은지 확인한다.
7. exact receipt bytes를 만들고 P2A target이 여전히 absent일 때 `apply_patch Add File`
   한 번으로 공개한다.

float timestamp 계산, future rounding, arbitrary epsilon, preselected wall time, second
selection은 금지한다. 어느 precondition/drift/partial/target-existing 오류든

```text
NEW_REVISION_REQUIRED_TIMESTAMP_CAPTURE_AUTHORITY_ZERO
```

로 종료하며 같은 R020 receipt를 repair/replace/reuse하지 않는다.

### 4.3 receipt exact schema and serialization

receipt는 UTF-8, duplicate key 없음, sorted keys, compact separators, terminal LF인
canonical JSON이다. top-level exact keys:

```text
schema_version
receipt_id
roadmap_revision
candidate_target
authority_boundary
capture_algorithm
causal_inputs
causal_max
observation
pre_capture_state
```

exact values:

```text
schema_version = walksafe.v2.5.r002-preparation-capture.v1
receipt_id = WS-WALKSAFE-V25-R002-PREPARATION-CAPTURE-20260802-R020-R001
roadmap_revision = R020
candidate_target = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002
authority_boundary = {
  effective:false, approved:false, applied:false,
  canonical_write_authorized:false, checkpoint_write_authorized:false,
  goal_write_authorized:false, product_write_authorized:false,
  evidence_only:true
}
capture_algorithm = {
  timezone:"Asia/Seoul", precision:"MICROSECOND_FLOOR",
  time_source:"time.time_ns", selection_count:1,
  retry_same_receipt_allowed:false
}
causal_inputs = §4.1 rows in role lexical order
causal_max = {ns,role,component}
observation = {observed_ns,selected_ns,prepared_at,postcheck_ns}
pre_capture_state = {
  capture_target_absent:true, r002_target_absent:true,
  source_preimage_exact:true, source_mutation_started:false,
  r001_exact:true
}
```

receipt는 자기 SHA/bytes/physical time, 미래 source hash, candidate hash/review 또는
future authorization receipt를 주장하지 않는다. P2A 공개 뒤 부모가 SHA/bytes와
`lstat`을 별도로 캡처한다.

### 4.4 mandatory equality rechecks

P2A 공개 직후, P2B 직전/직후, pre-build suite 뒤, P3 rename 직전과 P3 직후:

- receipt의 exact JSON/schema/inequality/one-shot fields
- receipt `sha256/bytes`와 no-follow physical tuple
- 모든 causal-input row의 live full tuple/hash/bytes/semantic time exact equality
- recomputed current max == receipt `causal_max`
- `causal_max.ns < selected_ns <= observed_ns <= postcheck_ns`
- `selected_ns <= receipt st_mtime_ns`와 `selected_ns <= receipt st_ctime_ns`
- r001 §2 seal
- P3 전 r002 absent

를 재검증한다. same-bytes replace, chmod/chown round-trip, touch, link-count 변화도
tuple drift다. 어떤 drift든 source/candidate write 없이 위 terminal로 끝낸다. P2A 뒤
crash가 발생해도 same R020 capture/source를 resume하지 않고 새 roadmap/receipt suffix가
필요하다.

## 5. exact R002 namespace

```text
BUNDLE_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002
CANDIDATE_STRUCTURAL_REVIEW_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-structural-review-r001.md
CANDIDATE_SKEPTICAL_REVIEW_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-skeptical-review-r001.md
APPLICATION_GATE_REL = docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-002
V25_PLAN_FINAL_REL = ${APPLICATION_GATE_REL}/application-transaction-plan.json
RESOLVED_OUTPUT_MANIFEST_REL = ${APPLICATION_GATE_REL}/resolved-output-manifest.json
```

`V25_PLAN_FINAL_REL`, receipt paths와 resolved path는 `APPLICATION_GATE_REL`에서
파생한다. current construction의 old `...20260730-001` gate literal은 0건이다.

```text
PACKAGE_CANDIDATE_ID = WS-V25-CONTROL-CANDIDATE-20260730-R002
TRANSACTION_PLAN_ID = WS-V25-R022-APPLICATION-TRANSACTION-PLAN-20260730-R002
DELEGATION_REQUIREMENT_ID = WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R020-R002
QUICK_GATE_REQUIREMENT_ID = WS-V25-R022-FRESH-QUICK-GATE-REQUIRED-20260730-R002
FINAL_TRANSFORM_ID = WS-V25-R022-AUTHORIZED-FINAL-TRANSFORM-20260730-R002
V25_CHECK_COMMAND_CONTRACT_VERSION = 2026-08-02.1
history_id = WS-V25-TRANSITION-HISTORY-20260730-R002
prepared_checkpoint_id = WS-V25-PACKAGE-PREPARED-CHECKPOINT-20260730-R002
candidate_output_manifest_id = WS-V25-CANDIDATE-OUTPUT-MANIFEST-20260730-R002
active_checkpoint_id = WS-V25-R022-ACTIVE-CHECKPOINT-20260730-R002
seq1_event_id = WS-V25-PACKAGE-PREPARED-20260730-002
seq2_event_id = WS-V25-PACKAGE-ACTIVATED-20260730-002
seq3_event_id = WS-V25-BULK-REBASELINE-APPLIED-20260730-002
```

validator/tests는 모두 exact 검사한다. `PLAN_ROOT_REL` rebaseline r001, reviewed R002
pair/design R001, independent review suffix `-r001.md`, failed-r001 path/IDs, version-wide
`PACKAGE_ID`/`MANIFEST_ID`, output filenames과 canonical final paths는 intentional
historical literals다.

## 6. P2B timestamp, provenance and failed-r001 validators

### 6.1 source-frozen capture values

core, builder, test는 receipt에서 다음 exact 값을 독립 상수로 봉인하고 equality를
검사한다.

```text
PREPARATION_CAPTURE_REL
PREPARATION_CAPTURE_SHA256
PREPARATION_CAPTURE_BYTES
PREPARATION_CAUSAL_MAX_NS
PREPARATION_OBSERVED_NS
PREPARATION_SELECTED_NS
PREPARATION_POSTCHECK_NS
P2_PREPARATION_STARTED_AT = PREPARED_AT
PREPARED_ON = Asia/Seoul date(PREPARED_AT)
```

core는 `validate_preparation_capture(root)`를 제공한다. 이 함수는 receipt pin/schema와
§4.4 live equality를 no-follow로 검사하고 snapshot을 반환한다. builder는 build 시작/
outputs construction 종료/write 시작/staging rename 직전/parent fsync 직후와 `--check`
전후에 이 함수를 호출해 시작 snapshot과 exact equality를 강제한다. candidate wrappers는
CANDIDATE/PRECOMMIT/ACTIVE validation 전후에 호출한다.

### 6.2 derived synthetic timeline

별도 calendar literal은 없다.

```text
core reviewed_at = PREPARED_AT + 5m
request generated_at = PREPARED_AT + 10m
receipt recorded_at = PREPARED_AT + 15m
quick started_at = PREPARED_AT + 15m30s
quick completed_at/checked_at = PREPARED_AT + 16m
quick valid_until = PREPARED_AT + 26m
seq2 occurred_at = checked_at
seq3 occurred_at = checked_at + 1us
```

하나의 timezone-aware helper로 파생하고 timezone-invalid/naive negative fixture도 같은
base에서 형식만 변형한다. strict chronology, exact +1us, 599/600/601 monotonic boundary는
유지한다. synthetic values는 `fixture_only=true`이며 live authority가 아니다.

### 6.3 provenance roles

`TRUSTED_SOURCE_PINS`와 authorization frozen bindings는 다음 방향을 갖는다.

```text
accepted R016 + reviews
→ rejected R017 + reviews
→ rejected R018 + reviews
→ rejected R019 + reviews
→ R020 roadmap + dual PASS reviews
→ R020 preparation capture receipt
→ P2B three-source pins
→ r002 candidate outputs
→ r002 candidate reviews
→ future core review/request/receipt
```

exact authorization roles:

```text
predecessor_r016_roadmap
predecessor_r016_structural_review
predecessor_r016_skeptical_review
rejected_r017_roadmap
rejected_r017_structural_review
rejected_r017_skeptical_review
rejected_r018_roadmap
rejected_r018_structural_review
rejected_r018_skeptical_review
rejected_r019_roadmap
rejected_r019_structural_review
rejected_r019_skeptical_review
r020_roadmap
r020_structural_review
r020_skeptical_review
preparation_capture_receipt
candidate_structural_review
candidate_skeptical_review
control_core_review
```

candidate reviews와 future receipt hash는 source/candidate가 추측하지 않는다.
capture receipt는 source보다 먼저 존재하므로 physical pin 가능하며 미래 source hash를
담지 않아 cycle이 없다.

### 6.4 failed-r001 validator

core는 §2 content/portable metadata/seq1-only/zero-authority/old-test binding을 별도
no-follow validator로 검사하고 full lstat snapshot을 반환한다. builder invocation과
core/wrapper validation의 시작/종료 snapshot은 exact equal이어야 한다. fixed
dev/inode/time 표는 부모/P4가 §2.2로 검사하고 portable validator는 type/mode/uid/gid/
nlink/hash/bytes/semantics를 검사한다. r001은 current candidate로 인정하지 않는다.

## 7. publication terminal

write mode는 r002 target entry가 directory/file/symlink/exact candidate 어떤 형태로든
존재하면 내용 검사나 recovery 없이:

```text
NEW_REVISION_REQUIRED_EXISTING_R002_TARGET_AUTHORITY_ZERO
```

로 실패한다. `_recover_exact_existing()`와 `RECOVERED_EXACT_EXISTING`을 제거한다.
`RENAME_NOREPLACE` race는 이번 invocation staging만 정리하고 같은 terminal로 실패한다.
rename 뒤 parent fsync 예외는 published target을 삭제/수정하지 않으며 동일 revision
retry는 terminal이다.

성공 공개 뒤 `builder --check`, physical present core validation, two candidate wrappers는
publication retry가 아닌 read-only 검증이므로 허용한다. 유일한 write success는
`PUBLISHED_NEW`다.

## 8. semantic contract unchanged

- full 840-byte directive와 normalized scope 분리
- external identity/signature/portable flags false
- request/receipt v2 exact schema, nonce-before-serialization, single-use
- 13 zero delta, `release_status_after=NOT_ELIGIBLE`
- strict chronology, monotonic `<=600s`, wall clock freshness 비권위
- pre-C1 partial terminal `NEW_REVISION_REQUIRED_UNCOMMITTED_AUTHORITY_ZERO`
- candidate exact six, dynamic slots 4, future resolved members 12
- `effective=false, approved=false, applied=false`
- overwrite/delete/repair/resume와 canonical/Goal/product credit 0

구식 approval token, `user_response_literal`, `USER_AUTHORIZATION_RESPONSE`, old
same-transaction resume 표현은 source/output 모두 0건이다.

## 9. R020 independent plan reviews

P1 files:

```text
docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R020-independent-structural-review-r001.md
docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R020-independent-skeptical-review-r001.md
```

두 review는 exact target SHA/bytes/lines, canonical `reviewed_at`, status/findings/authority를
갖고 다음을 독립 확인한다.

- R019 B01의 persistent snapshot/one-shot/TOCTOU closure
- §4 receipt schema, no self/future cycle, terminal과 all-boundary equality
- current R020 review semantic time의 physical non-future
- R018 R002 namespace/r001/retry/root-lstat closure 무변경
- three-source full correction, 37-test pre/post, authority/credit 0

PASS:

```text
status = PASS
findings = BLOCKING=0 MAJOR=0 MINOR=0
authority_granted = R020_R002_CAPTURE_AND_CANDIDATE_CORRECTION_ONLY
```

finding 하나라도 있으면 P2A 이후 write 0이고 새 roadmap이 필요하다.

## 10. P2A/P2B and pre-build gate

P1 dual PASS 뒤:

1. C0/R002/R016-R020/source/r001 pins, full physical/semantic times, r002/capture absence
2. §4.2 capture와 P2A add-only receipt
3. §4.4 immediate recheck, receipt SHA/bytes/lstat capture
4. P2B three-source 한 `apply_patch` correction
5. capture validator/source/r001 equality와 new source SHA capture
6. AST/duplicate literal-key/trailing-whitespace 검사
7. exact AST/path/JSON-pointer namespace residual scan
8. legacy token/resume/independent-calendar-literal scan 0
9. targeted suite

```text
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest \
  tests.test_walksafe_v2_5_control_candidate_20260730 -q
```

성공은 37 tests, skip 0, exit 0, `OK`다. 새 test method는 만들지 않고 기존 37 methods의
subcases로 다음을 고정한다.

- receipt schema/pins/inequalities/current tuple equality
- same-bytes replace, touch, chmod/chown/link drift fail closed
- R016-R020 provenance mutation
- R002 path/IDs/events and derived gate paths
- timestamp physical fields와 exact derived offsets
- failed-r001 content/portable metadata/current-source mismatch
- existing/file/symlink/race/fsync retry terminal
- dual-state temp exact six, strict chronology/zero-credit 기존 oracles

test의 staging glob은 `Path(validation.BUNDLE_REL).name`에서 파생한다. suite 뒤
receipt/causal inputs/source/r001/C0/r002 absence를 다시 확인한다.

## 11. P3 publication and post-build

P3 직전 §4.4를 다시 수행한다. drift 0, receipt/source/r001/C0/R002 pins, r002 absence일
때만 write하고 result는 exact `PUBLISHED_NEW`여야 한다.

P3 직후:

1. receipt/causal inputs/r001/source와 r002 root+six physical/content snapshot
2. targeted 37 suite, exit 0/skip 0
3. builder `--check`, exit 0
4. continuation CANDIDATE checker, exit 0
5. Goal CANDIDATE checker, exit 0
6. all snapshots와 active C0/R002/r021 unchanged 확인

두 번의 read-only check 전후 r002 root와 files의 no-follow
`(dev,ino,mode,uid,gid,nlink,size,mtime_ns,ctime_ns,sha256)`가 exact equal이어야 한다.
root는 hash 대신 non-symlink directory type와 exact-six NUL-name digest를 쓴다.
모든 argv/exit/stdout/stderr SHA와 snapshots를 P4 review에 기록한다.

## 12. P4 r002 candidate reviews

두 fresh non-implementation reviewer가 exact six, output manifest, generator/source/
capture pins, R016-R020 chain, capture inequality와 boundary snapshots, pre/post suite,
namespace residual, publisher terminal, candidate checkers를 독립 검증한다.

success:

```text
status = PASS_FOR_NON_EFFECTIVE_V25_CANDIDATE_ONLY
findings = BLOCKING=0 MAJOR=0 MINOR=0
authority = NONE_FOR_ACTIVATION_GOAL_PRODUCT
```

finding이 있으면 r002를 수정하지 않고 r003가 필요하다.

## 13. successors

1. R021 — exact r002/reviews 기반 one-shot monotonic lock/CAS, resolved review,
   checkpoint-last v2.5+r022 activation
2. R022 — seq3 immutable prefix 뒤 Goal replay와 v2.5 full19/repository-state
3. R023 — FP008 materialized/ready, v2.5 full19, `GOAL_STARTED`
4. R024 — Android adminapp read-only 신고 목록/상세 최소 slice와 종료검수

R021 PASS 전 canonical/checkpoint write 0, R022 PASS 전 Goal write 0, R023 valid
`GOAL_STARTED` 전 제품 write 0이다. 외부 전송/secret/실기기/formal/release credit은
범위 밖이다.

## 14. success

```text
R020_DUAL_PLAN_REVIEW_PASS
AND P2A_CAPTURE_RECEIPT_ADD_ONLY_VALID
AND CAUSAL_INPUT_FULL_SNAPSHOT_PERSISTED
AND CAUSAL_INPUT_EQUAL_AT_ALL_BOUNDARIES
AND CAUSAL_MAX_LT_SELECTED_LE_OBSERVED_LE_POSTCHECK
AND R001_CONTENT_LSTAT_IMMUTABLE
AND P2B_THREE_SOURCE_FULL_CORRECTION_ONLY
AND PREBUILD_37_PASS_SKIP_ZERO
AND R002_PUBLISHED_NEW_EXACT
AND POSTBUILD_37_PASS_SKIP_ZERO
AND R002_CHECKERS_PASS
AND R002_DUAL_CANDIDATE_REVIEW_PASS
AND LIVE_C0_R021_UNCHANGED
AND CANONICAL_CHECKPOINT_GOAL_PRODUCT_DELTA_ZERO
```

성공해도
`REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`뿐이다.
daylog/local-memory는 부모가 마지막에 한 번 통합한다.
