# WalkSafe v2.5 비효력 후보 correction 로드맵 R021

- 작성일: `2026-08-02`
- 상태: `PENDING_TWO_INTERNAL_REVIEWS`
- replaces: rejected R020
- 현 정본: v2.4 / checkpoint sequence 39 / Gap·Backlog r021
- 이번 종착점: `REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`
- live canonical·checkpoint·Goal·제품 write 권한: `0`

R020은 두 독립 검수에서 각각 `BLOCKING=2 MAJOR=0 MINOR=0`으로 거부됐다.
첫째, 수정 전 source를 immutable causal row로 넣고 P2B 뒤에도 live equality를 요구해
정상 correction이 필연적으로 실패했다. 둘째, causal registry/schema와 capture receipt
최초 physical tuple의 영속 전달이 불완전했다.

R021은 상태를 세 노드로 분리한다.

```text
I  = 끝까지 불변인 exact 28-row causal registry
S0 = P2B 직전까지만 live equality인 three-source preimage
S1 = P2B 뒤 별도 postimage receipt로 봉인되는 corrected three-source
```

timestamp capture는 `I + S0`에서 계산한다. P2B 뒤에는 `I`와 `S1`만 live comparator다.
capture receipt의 최초 full `lstat` tuple T0는 P2B source constants에 봉인된다.
S1 full tuple은 P2C add-only source-postimage receipt에 봉인되고 r002가 그 receipt를
content-bind한다. R016 truthful delegation/zero-credit/chronology/freshness/no-resume,
R018 R002 namespace/r001/retry closure와 R019 persistent-capture 방향은 유지한다.

activation·Goal·제품 단계는 R022 이후로 밀린다.

## 1. frozen chain and current truth

### 1.1 exact content pins

| 역할 | SHA-256 | bytes |
|---|---|---:|
| C0 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| R002 pair manifest | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| R002 PASS review | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| R016 roadmap | `49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2` | 12,690 |
| R016 structural | `eced95aa2343427b24f80f8865e2fa8e9e3f320ad48c74fab7970d646a1fac87` | 3,758 |
| R016 skeptical | `9bae362ad4c1f0c4b2567b534197938f77d825b5d73d76dbd3a184542649bc0e` | 5,354 |
| R017 roadmap | `6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5` | 13,494 |
| R017 structural | `cbcb6a14e4af2b82ec84c300799575fd99a81b65e9e8236177ff7221be958e81` | 9,466 |
| R017 skeptical | `d10ba282eee810c58a0d76d8658a4a21d937dcd4c76882a4936dc97538d61fe4` | 5,326 |
| R018 roadmap | `2a1df2cd5f0fdccb3183b15607b6a017ab6b2556d661ea16e9b6caaa9d9255f7` | 17,914 |
| R018 structural | `a87d59d2a3a5bbf601b4c3d9c3fdf8adaa1511c99e1c538a03db7be474a3b584` | 8,180 |
| R018 skeptical | `8fa2bfe82ac5b0b4a7a9d3708f9cf3407e995c8c82bd74d79ce2e8a4161879a4` | 4,551 |
| R019 roadmap | `3c21f917793b0ffbe5f6aa892e1d73d122799fcfe005c22f2f289571b45d4eb2` | 21,461 |
| R019 structural | `97bfa16d3c0f02e6ac601c92bc78c19779438948fc089624dfa9dcc235c0a548` | 11,122 |
| R019 skeptical | `b2995ccd45980cfecd422f88bd02aed6d98b42f445824a29876336efb7e1d8fc` | 7,168 |
| R020 roadmap | `276f499ce235289cbe7b6f15abc835ce87ed0ce8ccd76d03df904bc5f5cd44f1` | 25,239 |
| R020 structural | `9b378e4555c6c3a1edf1e712acb90f5f51089ed469116b552b1be496f7058316` | 9,096 |
| R020 skeptical | `8ac10e419f31409e5062cf82ab1066f3dafaff6eaaed63155662c3ff56ca9690` | 10,753 |

R016은 accepted predecessor다. R017~R020과 reviews는 rejected history이며 authority
`NONE`이다. 최신 사용자 지시는 trailing LF 없는 840 UTF-8 bytes, SHA-256
`8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a`다.
external identity/signature/server timestamp/message ID/approval token은 주장하지 않는다.

### 1.2 source S0

| path | SHA-256 | bytes |
|---|---|---:|
| `scripts/walksafe_v2_5_candidate_validation.py` | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

S0에는 R016의 r001 namespace, old timestamp, exact-existing recovery가 남아 있다.
R017~R020 correction은 미실행이다. test만 failed-r001 공개 뒤 dual-state oracle로
수정돼 r001 embedded old test binding과 mismatch다. r002와 R021 evidence targets는
P0 시점 absent다.

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

r001 old test pin은
`5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459` /
68,995 bytes다. r001은 seq1-only, zero-authority, candidate reviews absent다.

### 2.2 physical

| entry | dev | ino | mode | uid | gid | nlink | size | mtime_ns | ctime_ns |
|---|---:|---:|---|---:|---:|---:|---:|---:|---:|
| root | 66306 | 18353191 | `0o40700` | 1000 | 1000 | 2 | 4096 | 1785636122885202981 | 1785636130919378520 |
| `candidate-output-manifest.json` | 66306 | 18353197 | `0o100644` | 1000 | 1000 | 1 | 19757 | 1785636122885202981 | 1785636122885202981 |
| `static-plan-manifest-v2.5.0.candidate.json` | 66306 | 18353192 | `0o100644` | 1000 | 1000 | 1 | 34117 | 1785636122881202893 | 1785636122881202893 |
| `transition-history-v2.5.candidate.json` | 66306 | 18353194 | `0o100644` | 1000 | 1000 | 1 | 2676 | 1785636122883202937 | 1785636122883202937 |
| `v2.5-application-transaction-plan.candidate.json` | 66306 | 18353193 | `0o100644` | 1000 | 1000 | 1 | 659092 | 1785636122882202915 | 1785636122882202915 |
| `v2.5-control-package-manifest.candidate.json` | 66306 | 18353196 | `0o100644` | 1000 | 1000 | 1 | 27037 | 1785636122884202959 | 1785636122884202959 |
| `walksafe-project-continuation-checkpoint-v2.5.candidate.json` | 66306 | 18353195 | `0o100644` | 1000 | 1000 | 1 | 218564 | 1785636122883202937 | 1785636122883202937 |

root non-symlink directory/exact-six regular/no extra/missing/hardlink와 위 tuples/content는
모든 epoch에 exact equal이다. r001 delete/overwrite/rename/repair/metadata/link/resume는 0다.

## 3. epochs and write allowlists

1. P0 — R021 roadmap
2. P1 — R021 dual plan reviews
3. P2A — preparation capture receipt
4. P2B — S0→S1 three-source correction
5. P2C — S1 source-postimage receipt
6. P3 — r002 exact-six publication
7. P4 — r002 dual candidate reviews

P2A:

```text
docs/control/execution/artifact-closure/run-20260727-001/walksafe-v25-r002-preparation-capture-20260802-r021-r001.json
```

P2B exact:

```text
scripts/walksafe_v2_5_candidate_validation.py
scripts/build_walksafe_v2_5_control_candidate_20260730.py
tests/test_walksafe_v2_5_control_candidate_20260730.py
```

P2C:

```text
docs/control/execution/artifact-closure/run-20260727-001/walksafe-v25-r002-source-postimage-20260802-r021-r001.json
```

P3:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/
```

P4:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-structural-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-skeptical-review-r001.md
```

그 밖의 write, real authorization/resolved/canonical/checkpoint/Goal/full19/product write는 0다.

## 4. exact immutable registry I

### 4.1 exact 28 rows

`ordinal` ascending이 canonical array order다.

| ordinal | row_kind | role | repository-relative path |
|---:|---|---|---|
| 1 | FILE | `c0_checkpoint` | `docs/control/walksafe-project-continuation-checkpoint.json` |
| 2 | FILE | `r002_pair_manifest` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/gap-backlog-pair-manifest.json` |
| 3 | FILE | `r002_pass_review` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/INDEPENDENT-REVIEW-R001.md` |
| 4 | FILE | `r016_roadmap` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R016.md` |
| 5 | FILE | `r016_structural_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R016-independent-structural-review-r001.md` |
| 6 | FILE | `r016_skeptical_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R016-independent-skeptical-review-r001.md` |
| 7 | FILE | `r017_roadmap` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R017.md` |
| 8 | FILE | `r017_structural_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R017-independent-structural-review-r001.md` |
| 9 | FILE | `r017_skeptical_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R017-independent-skeptical-review-r001.md` |
| 10 | FILE | `r018_roadmap` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R018.md` |
| 11 | FILE | `r018_structural_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R018-independent-structural-review-r001.md` |
| 12 | FILE | `r018_skeptical_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R018-independent-skeptical-review-r001.md` |
| 13 | FILE | `r019_roadmap` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R019.md` |
| 14 | FILE | `r019_structural_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R019-independent-structural-review-r001.md` |
| 15 | FILE | `r019_skeptical_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R019-independent-skeptical-review-r001.md` |
| 16 | FILE | `r020_roadmap` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R020.md` |
| 17 | FILE | `r020_structural_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R020-independent-structural-review-r001.md` |
| 18 | FILE | `r020_skeptical_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R020-independent-skeptical-review-r001.md` |
| 19 | FILE | `r021_roadmap` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R021.md` |
| 20 | FILE | `r021_structural_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R021-independent-structural-review-r001.md` |
| 21 | FILE | `r021_skeptical_review` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R021-independent-skeptical-review-r001.md` |
| 22 | DIRECTORY | `failed_r001_root` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001` |
| 23 | FILE | `failed_r001_output_manifest` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001/candidate-output-manifest.json` |
| 24 | FILE | `failed_r001_static` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001/static-plan-manifest-v2.5.0.candidate.json` |
| 25 | FILE | `failed_r001_history` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001/transition-history-v2.5.candidate.json` |
| 26 | FILE | `failed_r001_plan` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001/v2.5-application-transaction-plan.candidate.json` |
| 27 | FILE | `failed_r001_package` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001/v2.5-control-package-manifest.candidate.json` |
| 28 | FILE | `failed_r001_checkpoint` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001/walksafe-project-continuation-checkpoint-v2.5.candidate.json` |

### 4.2 exact row schemas

FILE exact keys:

```text
bytes,lstat,ordinal,path,role,row_kind,semantic_reviewed_at,
semantic_reviewed_at_ns,sha256
```

DIRECTORY exact keys:

```text
entry_count,entry_name_digest_sha256,lstat,ordinal,path,role,row_kind,
semantic_reviewed_at,semantic_reviewed_at_ns
```

`lstat` exact keys are
`dev,gid,ino,mode,mtime_ns,ctime_ns,nlink,size,uid`; `mode` is integer.
FILE values are exact physical bytes/SHA. DIRECTORY row has no `bytes`/`sha256`; `entry_count=6`.
Its digest is:

```text
sha256(
  b"candidate-output-manifest.json\0"
  b"static-plan-manifest-v2.5.0.candidate.json\0"
  b"transition-history-v2.5.candidate.json\0"
  b"v2.5-application-transaction-plan.candidate.json\0"
  b"v2.5-control-package-manifest.candidate.json\0"
  b"walksafe-project-continuation-checkpoint-v2.5.candidate.json\0"
)
```

즉 UTF-8 bytewise basename sort와 terminal NUL이다.

review content에서 exact `reviewed_at = <value>` line이 하나면 canonical timezone-aware
RFC3339로 parse해 두 semantic fields에 넣는다. 없으면 둘 다 JSON null이다. 두 개
이상이면 fail한다. R021 reviews는 반드시 하나이며:

```text
semantic_reviewed_at_ns <= min(lstat.mtime_ns,lstat.ctime_ns)
status=PASS
findings=BLOCKING=0 MAJOR=0 MINOR=0
authority_granted=R021_R002_CAPTURE_SOURCE_AND_CANDIDATE_CORRECTION_ONLY
```

여야 한다.

## 5. mutable registry S0

canonical list order:

| ordinal | role | path |
|---:|---|---|
| 1 | `source_validation_core_s0` | `scripts/walksafe_v2_5_candidate_validation.py` |
| 2 | `source_builder_s0` | `scripts/build_walksafe_v2_5_control_candidate_20260730.py` |
| 3 | `source_test_s0` | `tests/test_walksafe_v2_5_control_candidate_20260730.py` |

각 row는 §4.2 FILE schema와 같고 `semantic_reviewed_at` fields는 null이다. P2A부터
P2B mutation 시작 직전까지만 live exact equality다. P2B 뒤에는 historical before
evidence이며 live comparator가 아니다.

## 6. P2A preparation capture

### 6.1 max and observation

max candidates는 I 28 + S0 3의 각 row에서 다음 exact component enum으로 만든다.

```text
mtime_ns
ctime_ns
semantic_reviewed_at_ns  # null이면 후보에서 제외
```

각 candidate tuple은 `(integer_ns, role, component)`이고 Python tuple의 ascending
lexical rule로 `max()`한 하나가 `causal_max={ns,role,component}`다.

P1 dual PASS, I/S0/r002/capture/postimage target absence를 확인한 한 supervisor가:

1. `I0`, `S0a`, causal max를 읽는다.
2. `observed_ns=time.time_ns()`;
   `selected_ns=observed_ns-observed_ns%1000`.
3. `selected_ns > causal_max.ns`인 첫 값만 committed candidate로 삼는다.
4. Asia/Seoul `YYYY-MM-DDTHH:MM:SS.ffffff+09:00`으로 `prepared_at`을 만든다.
5. `postcheck_ns=time.time_ns()`와
   `max < selected <= observed <= postcheck`를 확인한다.
6. `I1`, `S0b`를 다시 읽어 `I0==I1`, `S0a==S0b`, max same을 확인한다.
7. exact capture receipt를 `apply_patch Add File`로 공개한다.

receipt가 성공 공개되기 전의 raw clock read는 `UNCOMMITTED_OBSERVATION_AUTHORITY_ZERO`이며
selection_count에 포함되지 않는다. committed selection은 exact receipt 하나뿐이다.
receipt 공개 뒤 crash/target-existing/partial/drift는 same R021 reuse/repair 없이
`NEW_REVISION_REQUIRED_TIMESTAMP_CAPTURE_AUTHORITY_ZERO`다.

### 6.2 capture receipt exact schema

canonical JSON: UTF-8, sorted keys, compact separators, duplicate key 없음, terminal LF.
top-level exact keys:

```text
authority_boundary,candidate_target,capture_algorithm,causal_max,
immutable_causal_inputs,observation,pre_capture_state,receipt_id,
roadmap_revision,schema_version,source_preimage
```

exact:

```text
schema_version = walksafe.v2.5.r002-preparation-capture.v2
receipt_id = WS-WALKSAFE-V25-R002-PREPARATION-CAPTURE-20260802-R021-R001
roadmap_revision = R021
candidate_target = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002
authority_boundary = {
  applied:false,approved:false,canonical_write_authorized:false,
  checkpoint_write_authorized:false,effective:false,evidence_only:true,
  goal_write_authorized:false,product_write_authorized:false
}
capture_algorithm = {
  committed_selection_count:1,precision:"MICROSECOND_FLOOR",
  retry_same_receipt_allowed:false,time_source:"time.time_ns",
  timezone:"Asia/Seoul",uncommitted_observations_have_authority:false
}
immutable_causal_inputs = exact I0 28 rows
source_preimage = exact S0a 3 rows
causal_max = §6.1 exact object
observation = {observed_ns,postcheck_ns,prepared_at,selected_ns}
pre_capture_state = {
  capture_target_absent:true,postimage_target_absent:true,
  r001_exact:true,r002_target_absent:true,source_mutation_started:false
}
```

receipt는 자기 physical/SHA, 미래 S1/postimage/candidate/review/authorization을 주장하지
않는다.

### 6.3 T0 handoff

receipt add 직후 같은 supervisor가 no-follow로 content/schema를 다시 읽고:

```text
CAPTURE_SHA256
CAPTURE_BYTES
CAPTURE_LSTAT_T0={dev,ino,mode,uid,gid,nlink,size,mtime_ns,ctime_ns}
```

를 관측한다. `selected_ns <= min(T0.mtime_ns,T0.ctime_ns)`와 I live equality/S0 live
equality를 확인한다. P2B 시작/patch 완료까지 supervisor는 T0를 메모리에 보유해 receipt
live tuple과 exact 비교한다. 이 사이 crash/drift면 new revision terminal이다.

P2B의 core, builder, test에 exact capture path/hash/bytes/T0/causal max/observed/selected/
postcheck/prepared_at를 independent constants로 봉인한다. patch 직후 세 source constant
equality와 live receipt T0를 확인한다. 이후 새 process의
`validate_preparation_capture(root)`는 source-frozen T0를 expected tuple로 사용한다.

## 7. P2B exact S0→S1

한 `apply_patch` epoch로 exact three files만 바꾼다. 시작 전 I와 S0/T0 exact; 완료 뒤:

- I는 I0와 exact equal
- receipt는 T0와 exact equal
- three source path set은 exact same 3
- 각 S1 SHA differs from its S0 SHA
- each S1 `mtime_ns`와 `ctime_ns`는 `selected_ns` 이상
- source 밖 delta 0
- source constants가 §6.3 값과 exact equal

S0 live mismatch는 이 시점부터 의도된 transition success이며 S0는 historical only다.
partial source patch, crash, extra path, S1 constant mismatch는 repair/resume하지 않고 새
roadmap/source revision이 필요하다.

## 8. P2C source-postimage receipt

P2B 직후 I/T0와 S1을 읽고 `captured_ns=time.time_ns()`를 관측한다. exact P2C JSON:

```text
top-level keys =
authority_boundary,capture_receipt_binding,captured_ns,receipt_id,
roadmap_revision,schema_version,source_postimage,source_preimage_binding

schema_version = walksafe.v2.5.r002-source-postimage.v1
receipt_id = WS-WALKSAFE-V25-R002-SOURCE-POSTIMAGE-20260802-R021-R001
roadmap_revision = R021
authority_boundary = {
  applied:false,approved:false,effective:false,evidence_only:true,
  canonical_write_authorized:false,checkpoint_write_authorized:false,
  goal_write_authorized:false,product_write_authorized:false
}
capture_receipt_binding = {path,sha256,bytes,lstat:T0}
source_preimage_binding = {capture_receipt_path,source_preimage_sha256}
source_postimage = 3 FILE rows, roles suffix `_s1`, canonical order core,builder,test
captured_ns >= max(all S1 mtime_ns,all S1 ctime_ns)
```

`source_preimage_sha256`는 canonical JSON bytes of capture receipt `source_preimage`
array SHA다. P2C target absent를 재확인해 `apply_patch Add File`로 한 번 공개한다.
P2C content는 미래 candidate hash를 담지 않는다.

P2C 공개 뒤 core/builder/tests는 `validate_source_transition_evidence(root)`로:

- capture receipt exact source-frozen T0/content/schema/I equality
- P2C exact schema/content binding
- P2C S0 digest == P2A S0 array digest
- live S1 full tuple/hash/bytes == P2C source_postimage

를 검사한다. P2C file 자체는 content SHA/bytes가 r002 package/output manifest에
동적으로 bind된다. P2C의 physical tuple은 authority input이 아니므로 same-bytes inode
replacement가 S1/I/T0 검사를 약화시키지 않는다. P2C content drift는 hash/schema/S1
검사로 실패한다.

## 9. exact R002 namespace

```text
BUNDLE_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002
CANDIDATE_STRUCTURAL_REVIEW_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-structural-review-r001.md
CANDIDATE_SKEPTICAL_REVIEW_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-skeptical-review-r001.md
APPLICATION_GATE_REL = docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-002
V25_PLAN_FINAL_REL = ${APPLICATION_GATE_REL}/application-transaction-plan.json
RESOLVED_OUTPUT_MANIFEST_REL = ${APPLICATION_GATE_REL}/resolved-output-manifest.json
PACKAGE_CANDIDATE_ID = WS-V25-CONTROL-CANDIDATE-20260730-R002
TRANSACTION_PLAN_ID = WS-V25-R022-APPLICATION-TRANSACTION-PLAN-20260730-R002
DELEGATION_REQUIREMENT_ID = WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R021-R002
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

gate-derived paths와 all IDs를 validator/tests가 exact 검사한다. PLAN_ROOT/design/review
suffix/failed-r001/version-wide IDs/final path 등 explicit historical literals만 유지한다.

## 10. source correction contract

### 10.1 timestamp and fixtures

physical prepared fields use exact P2A `prepared_at`; `PREPARED_ON` is its Asia/Seoul date.
All independent calendar literals are removed. Synthetic TEST-only:

```text
core review +5m
request +10m
delegation receipt +15m
quick start +15m30s
quick complete/check +16m
quick valid until +26m
seq2 = checked
seq3 = checked +1us
```

strict chronology, exact +1us, 599/600/601 monotonic and timezone negative fixtures remain.

### 10.2 provenance and r001

accepted R016 → rejected R017/R018/R019/R020 → R021 dual PASS → P2A → S1/P2C → r002
→ candidate reviews → future receipts is one-way. Authorization exact roles include
predecessor R016 trio, rejected R017-R020 trios, R021 trio, preparation capture,
source-postimage evidence, candidate review pair, core review. Future hashes are not guessed.

core provides failed-r001 no-follow content/portable-metadata/seq1/zero-authority/old-test
validator and returns full snapshot. Every builder/checker invocation compares start/end; parent
continues §2.2 fixed tuple comparison.

### 10.3 publication terminal

any existing r002 directory/file/symlink/exact target and rename race:

```text
NEW_REVISION_REQUIRED_EXISTING_R002_TARGET_AUTHORITY_ZERO
```

`_recover_exact_existing`/`RECOVERED_EXACT_EXISTING` are removed. parent-fsync exception leaves
published target untouched and retry is terminal. Success-after-publication `--check` and two
candidate wrappers are separate read-only paths. only write success is `PUBLISHED_NEW`.

## 11. semantic boundary

- exact 840-byte directive, flags false, scope separate
- request/receipt v2, nonce-before-serialization, single-use
- 13 zero deltas, release `NOT_ELIGIBLE`
- pre-C1 partial terminal/no resume
- exact six/dynamic four/resolved twelve
- candidate effective/approved/applied false
- canonical/checkpoint/Goal/product/formal/device/release write/credit 0
- legacy approval/response/resume tokens 0

## 12. R021 independent reviews

files:

```text
docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R021-independent-structural-review-r001.md
docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R021-independent-skeptical-review-r001.md
```

Each has canonical `reviewed_at`, target SHA/bytes/lines and audits:

- R020 B01 I/S0/S1 phase separation
- R020 B02 exact 28+3 registry/schema/max enum/T0 source handoff
- P2C S1 persistence and no new cycle
- timestamp causal/nonfuture/one committed receipt
- R002 namespace/r001/retry/read-only/authority unchanged
- 37-test and all write epochs

PASS only:

```text
status=PASS
findings=BLOCKING=0 MAJOR=0 MINOR=0
authority_granted=R021_R002_CAPTURE_SOURCE_AND_CANDIDATE_CORRECTION_ONLY
```

finding 있으면 P2A 이후 write 0/new roadmap.

## 13. pre-build gate

1. I/S0/r001/source content and physical pins; all future targets absent
2. P2A capture, T0 handoff
3. P2B S0→S1 exact three-file transition
4. P2C S1 receipt
5. I/T0/S1/r001 exact equality
6. AST/duplicate-key/trailing whitespace
7. exact path/AST/JSON pointer namespace residual
8. legacy/independent-calendar scan 0
9. targeted unittest

```text
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest \
  tests.test_walksafe_v2_5_control_candidate_20260730 -q
```

37 tests, skip 0, exit 0, OK. No new method; existing subcases cover:

- P2A schema/registry/max/T0 and I/S0 phase
- P2C/S1, source same-byte replace/touch/chmod/link negative
- R016-R021 provenance mutations
- R002 paths/IDs/timestamps
- r001 exact validator/current-source mismatch
- existing/file/symlink/race/fsync terminal
- existing chronology/zero-credit/dual-state oracles

suite 뒤 I/T0/S1/r001/source/C0/r002 absence exact.

## 14. P3 and post-build

P3 rename 직전 `validate_source_transition_evidence`, I/T0/S1/r001/source pins와 r002
absence를 다시 검사한다. exact `PUBLISHED_NEW`만 허용한다.

후:

1. I/T0/S1/P2C/r001/source/r002 root+six snapshot
2. targeted 37 tests
3. builder `--check`
4. continuation CANDIDATE checker
5. Goal CANDIDATE checker
6. all snapshots/C0/R002/r021 unchanged

두 번 read-only check 전후 r002 root/files full no-follow tuple/hash와 root exact-six
NUL-name digest가 같다. argv/exit/stdout/stderr SHA를 P4에 기록한다.

## 15. P4 candidate reviews

fresh reviewers independently verify exact six/output manifest/generator bindings, P2A T0,
P2C S1, I equality, R016-R021 chain, pre/post gates, namespace, terminal/read-only.

```text
status=PASS_FOR_NON_EFFECTIVE_V25_CANDIDATE_ONLY
findings=BLOCKING=0 MAJOR=0 MINOR=0
authority=NONE_FOR_ACTIVATION_GOAL_PRODUCT
```

finding이면 r002 immutable, r003 필요.

## 16. successors

1. R022 — one-shot monotonic lock/CAS, resolved review, checkpoint-last v2.5+r022 activation
2. R023 — seq3 뒤 Goal replay/full19/repository-state
3. R024 — FP008 materialized/ready, full19, `GOAL_STARTED`
4. R025 — Android adminapp read-only 신고 목록/상세 최소 slice와 종료검수

R022 전 canonical/checkpoint, R023 전 Goal, R024 valid start 전 product write 0.

## 17. success

```text
R021_DUAL_PLAN_REVIEW_PASS
AND I_28_EXACT_PERSISTENT
AND S0_EXACT_UNTIL_P2B
AND P2A_CAPTURE_T0_SOURCE_FROZEN
AND S0_TO_S1_EXACT_THREE_FILE_TRANSITION
AND P2C_S1_POSTIMAGE_VALID
AND I_T0_S1_EQUAL_AT_P3
AND R001_CONTENT_LSTAT_IMMUTABLE
AND PREBUILD_37_PASS_SKIP_ZERO
AND R002_PUBLISHED_NEW_EXACT
AND POSTBUILD_37_PASS_SKIP_ZERO
AND R002_CHECKERS_PASS
AND R002_DUAL_CANDIDATE_REVIEW_PASS
AND LIVE_C0_R021_UNCHANGED
AND CANONICAL_CHECKPOINT_GOAL_PRODUCT_DELTA_ZERO
```

성공 상태는
`REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`뿐이다.
daylog/local-memory는 부모가 마지막에 통합한다.
