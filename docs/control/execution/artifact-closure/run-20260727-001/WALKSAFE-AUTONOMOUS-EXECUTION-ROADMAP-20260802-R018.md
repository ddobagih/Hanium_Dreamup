# WalkSafe v2.5 비효력 후보 correction 로드맵 R018

- 작성일: `2026-08-02`
- 상태: `PENDING_TWO_INTERNAL_REVIEWS`
- replaces: rejected R017
- 현 정본: v2.4 / checkpoint sequence 39 / Gap·Backlog r021
- 이번 종착점: `REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`
- live canonical·checkpoint·Goal·제품 write 권한: `0`

R017은 구조 검수 `BLOCKING=1 MAJOR=2 MINOR=0`, 회의적 검수
`BLOCKING=1 MAJOR=1 MINOR=0`으로 거부됐다. R018은 r001을 물리적으로 보존하고 새
r002 candidate만 add-only로 준비·공개·검수한다. R016의 truthful delegation,
zero-credit, strict chronology, monotonic freshness와 pre-C1 no-resume 계약은 유지한다.

R016/R017에서 예고한 activation·Goal·제품 단계는 R019 이후로 밀린다.

## 1. 동결 provenance

### 1.1 canonical과 reviewed inputs

| 역할 | SHA-256 | bytes |
|---|---|---:|
| C0 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| R002 pair manifest | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| R002 PASS review | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| R016 roadmap | `49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2` | 12,690 |
| R016 structural PASS | `eced95aa2343427b24f80f8865e2fa8e9e3f320ad48c74fab7970d646a1fac87` | 3,758 |
| R016 skeptical PASS | `9bae362ad4c1f0c4b2567b534197938f77d825b5d73d76dbd3a184542649bc0e` | 5,354 |
| rejected R017 | `6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5` | 13,494 |
| R017 structural review | `cbcb6a14e4af2b82ec84c300799575fd99a81b65e9e8236177ff7221be958e81` | 9,466 |
| R017 skeptical review | `d10ba282eee810c58a0d76d8658a4a21d937dcd4c76882a4936dc97538d61fe4` | 5,326 |

최신 사용자 지시는 trailing LF 없는 840 UTF-8 bytes,
SHA-256 `8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a`다.
외부 identity, signature, server timestamp, message ID 또는 특정 영문 approval token을
주장하지 않는다.

### 1.2 source preimage

| path | SHA-256 | bytes |
|---|---|---:|
| `scripts/walksafe_v2_5_candidate_validation.py` | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

test preimage의 공개 전 absent·공개 후 present dual-state oracle은 유지한다. R018은
revision namespace, provenance, r001 preservation, publication retry semantics와 그
negative oracle을 명시적으로 수정할 수 있다.

## 2. immutable failed r001

root:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001/
```

### 2.1 content CAS

| file | SHA-256 | bytes |
|---|---|---:|
| `static-plan-manifest-v2.5.0.candidate.json` | `213cdea4f061d77f587211b3c315f5fb27eb5a2f39dbe7a9382e2c34e7a29a68` | 34,117 |
| `v2.5-application-transaction-plan.candidate.json` | `bb7819d4c3cc27878685fea3d4764ef7d55d511674eb078fc0cce8bfc0840275` | 659,092 |
| `transition-history-v2.5.candidate.json` | `1b859617a6cc7a007a9cdca61f10d5b040ba743bbd90bd5f7183163a8a1f494e` | 2,676 |
| `walksafe-project-continuation-checkpoint-v2.5.candidate.json` | `273b320645ee17737d452af263d45b45058cd7ed012f0bf32c6a9345ae894864` | 218,564 |
| `v2.5-control-package-manifest.candidate.json` | `3e27d14670c804bcc6df202f360a9a0541c1b2a83b3ad759961d2319a577261d` | 27,037 |
| `candidate-output-manifest.json` | `ffd8e1616891f1d3fc75c24bcbfc9d676760afe786fe19d63218c66024119945` | 19,757 |

r001은 이전 test SHA `5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459`,
68,995 bytes를 봉인했고 현재 source와 deterministic mismatch다. r001은 seq1-only,
`effective=false, approved=false, applied=false`, candidate review 부재 상태다.

### 2.2 physical lstat seal

P2 직전 다음 no-follow `lstat` 값을 baseline으로 다시 확인하고, 모든 write epoch 전후와
P4 종료 때 exact equality를 요구한다.

| entry | dev | ino | mode | uid | gid | nlink | size | mtime_ns | ctime_ns |
|---|---:|---:|---|---:|---:|---:|---:|---:|---:|
| root | 66306 | 18353191 | `0o40700` | 1000 | 1000 | 2 | 4096 | 1785636122885202981 | 1785636130919378520 |
| `candidate-output-manifest.json` | 66306 | 18353197 | `0o100644` | 1000 | 1000 | 1 | 19757 | 1785636122885202981 | 1785636122885202981 |
| `static-plan-manifest-v2.5.0.candidate.json` | 66306 | 18353192 | `0o100644` | 1000 | 1000 | 1 | 34117 | 1785636122881202893 | 1785636122881202893 |
| `transition-history-v2.5.candidate.json` | 66306 | 18353194 | `0o100644` | 1000 | 1000 | 1 | 2676 | 1785636122883202937 | 1785636122883202937 |
| `v2.5-application-transaction-plan.candidate.json` | 66306 | 18353193 | `0o100644` | 1000 | 1000 | 1 | 659092 | 1785636122882202915 | 1785636122882202915 |
| `v2.5-control-package-manifest.candidate.json` | 66306 | 18353196 | `0o100644` | 1000 | 1000 | 1 | 27037 | 1785636122884202959 | 1785636122884202959 |
| `walksafe-project-continuation-checkpoint-v2.5.candidate.json` | 66306 | 18353195 | `0o100644` | 1000 | 1000 | 1 | 218564 | 1785636122883202937 | 1785636122883202937 |

root는 non-symlink directory, exact six entries는 non-symlink regular file이고 extra,
missing, hardlink가 없어야 한다. r001과 parent의 기존 entry는 delete, overwrite,
rename, repair, chmod, chown, link 변경 또는 resume하지 않는다.

## 3. R018 epochs와 allowlist

1. P0 — 이 R018 문서 add-only
2. P1 — R018 plan review 두 개 add-only
3. P2 — three-file source correction
4. P3 — r002 exact six-output root add-only publication
5. P4 — r002 candidate review 두 개 add-only

P2 allowlist:

```text
scripts/walksafe_v2_5_candidate_validation.py
scripts/build_walksafe_v2_5_control_candidate_20260730.py
tests/test_walksafe_v2_5_control_candidate_20260730.py
```

P3 exact target:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/
```

P4 exact targets:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-structural-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-skeptical-review-r001.md
```

real dynamic receipt, resolved output, active final, canonical r022, checkpoint, Goal,
full19와 제품 write는 0이다.

## 4. exact r002 namespace

### 4.1 paths and transaction identity

```text
BUNDLE_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002
CANDIDATE_STRUCTURAL_REVIEW_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-structural-review-r001.md
CANDIDATE_SKEPTICAL_REVIEW_REL = plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-skeptical-review-r001.md
APPLICATION_GATE_REL = docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-002
V25_PLAN_FINAL_REL = ${APPLICATION_GATE_REL}/application-transaction-plan.json
RESOLVED_OUTPUT_MANIFEST_REL = ${APPLICATION_GATE_REL}/resolved-output-manifest.json
```

core `V25_PLAN_FINAL_REL`과 모든 receipt path는 `APPLICATION_GATE_REL`에서 파생하고,
builder `resolved_output_manifest_contract.path`는
`validation.RESOLVED_OUTPUT_MANIFEST_REL`을 사용한다. 기존 `...20260730-001`
transaction-gate literal은 current r002 construction에서 0건이어야 한다.

```text
PACKAGE_CANDIDATE_ID = WS-V25-CONTROL-CANDIDATE-20260730-R002
TRANSACTION_PLAN_ID = WS-V25-R022-APPLICATION-TRANSACTION-PLAN-20260730-R002
DELEGATION_REQUIREMENT_ID = WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R018-R002
QUICK_GATE_REQUIREMENT_ID = WS-V25-R022-FRESH-QUICK-GATE-REQUIRED-20260730-R002
FINAL_TRANSFORM_ID = WS-V25-R022-AUTHORIZED-FINAL-TRANSFORM-20260730-R002
V25_CHECK_COMMAND_CONTRACT_VERSION = 2026-08-02.1
```

### 4.2 document and event IDs

```text
history_id = WS-V25-TRANSITION-HISTORY-20260730-R002
prepared_checkpoint_id = WS-V25-PACKAGE-PREPARED-CHECKPOINT-20260730-R002
candidate_output_manifest_id = WS-V25-CANDIDATE-OUTPUT-MANIFEST-20260730-R002
active_checkpoint_id = WS-V25-R022-ACTIVE-CHECKPOINT-20260730-R002
seq1_event_id = WS-V25-PACKAGE-PREPARED-20260730-002
seq2_event_id = WS-V25-PACKAGE-ACTIVATED-20260730-002
seq3_event_id = WS-V25-BULK-REBASELINE-APPLIED-20260730-002
```

validator와 tests는 위 값을 exact 검사한다. `PLAN_ROOT_REL`의 rebaseline r001,
reviewed R002 pair dir, successor design R001, review suffix `-r001.md`, version-wide
`PACKAGE_ID`/`MANIFEST_ID`, six output filenames와 canonical r022/static/checkpoint final
paths는 역사·version 의미이므로 유지한다.

### 4.3 truthful preparation timestamps

r002 physical revision은 다음 시간을 사용한다.

```text
PREPARED_AT = 2026-08-02T11:00:00+09:00
PREPARED_ON = 2026-08-02
synthetic core reviewed_at = 2026-08-02T11:05:00+09:00
synthetic request generated_at = 2026-08-02T11:10:00+09:00
synthetic receipt recorded_at = 2026-08-02T11:15:00+09:00
synthetic quick started_at = 2026-08-02T11:15:30+09:00
synthetic quick completed_at/checked_at = 2026-08-02T11:16:00+09:00
synthetic quick valid_until = 2026-08-02T11:26:00+09:00
synthetic seq3 occurred_at = 2026-08-02T11:16:00.000001+09:00
```

strict `seq1 < seq2 < seq3`, exact `+1us`, expiry와 599/600/601 monotonic tests를
유지하며 test의 timezone-invalid fixtures도 같은 날짜 축으로 갱신한다.

## 5. provenance and failed-r001 validator

### 5.1 provenance chain

P1 뒤 실제 R018 plan/review hashes만 source에 넣는다. R016은 accepted predecessor,
R017과 두 reviews는 rejected history, R018과 두 PASS reviews는 current execution
authority로 구분한다.

future frozen binding exact roles:

```text
predecessor_r016_roadmap
predecessor_r016_structural_review
predecessor_r016_skeptical_review
rejected_r017_roadmap
rejected_r017_structural_review
rejected_r017_skeptical_review
r018_roadmap
r018_structural_review
r018_skeptical_review
candidate_structural_review
candidate_skeptical_review
control_core_review
```

R018 reviews는 r002 build 전에 존재하므로 physical pin 가능하다. r002 outputs,
r002 candidate reviews와 future receipt physical hash를 source 상수로 추측하지 않는다.

### 5.2 failed-r001 validation

core에 별도 predecessor path/pins와 confined no-follow validator를 둔다. validator는
§2 exact entry set, type, mode, uid/gid, nlink, hash/bytes, seq1-only, zero authority와
old test binding을 검사한다. r002 `read_candidate_bundle()`은 BUNDLE_REL만 읽고 r001을
current candidate로 인정하지 않는다.

builder는 invocation 시작, output construction 종료, staging rename 직전, parent fsync
직후와 `--check` 전후에 failed-r001 content/portable metadata validator를 호출한다.
같은 invocation에서는 시작 lstat snapshot과 종료 snapshot의 full equality도 강제한다.
부모/리뷰는 §2.2 exact lstat table을 epoch 사이에 비교한다.

## 6. publication retry contract

write mode는 r002 target entry가 directory, file, symlink 또는 exact candidate 어떤
형태로든 이미 있으면 다음 terminal로 실패한다.

```text
NEW_REVISION_REQUIRED_EXISTING_R002_TARGET_AUTHORITY_ZERO
```

기존 `_recover_exact_existing()`와 `RECOVERED_EXACT_EXISTING` success path를 제거한다.
`RENAME_NOREPLACE` race도 staging만 정리하고 같은 terminal로 실패한다. rename이
성공한 뒤 parent fsync가 예외를 반환하면 target을 삭제·수정하지 않고 동일 revision
retry를 거부한다.

반면 다음은 publication retry가 아닌 read-only 검증이므로 허용한다.

- 성공 공개 뒤 `builder --check`
- physical present-state core validation
- continuation/Goal CANDIDATE wrappers

tests는 기존 exact-existing 성공 oracle과 parent-fsync retry-success oracle을 terminal
negative oracle로 교체한다. partial staging cleanup은 builder가 이번 invocation에서
만든 staging만 대상으로 유지한다. test 수는 37개를 유지하고 새 assertions는 기존
test methods의 subcases로 통합한다.

## 7. semantic contract unchanged

- full 840-byte directive와 normalized scope 분리
- external identity/signature/portable flags false
- request/receipt v2 exact schema, nonce-before-serialization, single-use
- 13 zero delta와 `release_status_after=NOT_ELIGIBLE`
- strict chronology와 monotonic `<=600s`, wall clock 비권위
- pre-C1 receipt/final partial terminal
  `NEW_REVISION_REQUIRED_UNCOMMITTED_AUTHORITY_ZERO`
- overwrite/delete/repair/resume 금지, C0 v2.4/r021 active
- candidate exact 6, dynamic slots 4, future resolved members 12
- candidate `effective=false, approved=false, applied=false`

구식 approval token, `user_response_literal`, `USER_AUTHORIZATION_RESPONSE`, old
same-transaction resume 표현은 source/output 모두 0건이어야 한다.

## 8. R018 independent plan review

P1 exact files:

```text
docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R018-independent-structural-review-r001.md
docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R018-independent-skeptical-review-r001.md
```

둘은 같은 R018 SHA/bytes/lines와 다음을 검증한다.

- R017 findings 세 축의 closure
- r001 content+lstat immutable seal
- r002 exact paths/IDs/events/gate-derived paths와 intentional historical literals
- R016/R017/R018 provenance DAG와 cycle 0
- exact-existing terminal과 read-only check의 분리
- three-file allowlist, 37-test pre/post oracle, authority/credit 0

PASS 조건:

```text
status=PASS
findings=BLOCKING=0 MAJOR=0 MINOR=0
authority_granted=R018_R002_CANDIDATE_CORRECTION_ONLY
```

finding 하나라도 있으면 P2 이후 write 0이고 새 roadmap revision이 필요하다.

## 9. P2 source correction and pre-build gate

R018 dual PASS 뒤 three files만 `apply_patch`로 수정한다. tests는 다음을 같은 37개
universe에 고정한다.

- §4 paths/IDs/event IDs/timestamps
- R016/R017/R018 binding key·hash mutation negatives
- r001 exact content/portable metadata와 current-source mismatch
- failed-r001 lstat/content 불변 subcase
- existing/race/parent-fsync retry terminal
- absent dual-state temp bundle, strict schema/chronology/zero-credit 기존 tests

pre-build 순서:

1. C0/R002/R016/R017/R018 physical pins와 r001 §2 content+lstat exact
2. r002 target absent/non-symlink
3. three source preimage CAS, 수정 뒤 새 three source SHA capture
4. AST/duplicate literal-key/trailing-whitespace 검사
5. current-construction namespace residual scan
6. legacy token/resume scan 0
7. targeted suite

```text
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest \
  tests.test_walksafe_v2_5_control_candidate_20260730 -q
```

성공은 `37 tests`, `skip=0`, `exit=0`, `OK`다. absent branch는 temp exact six
outputs에서 core와 두 wrappers를 검증한다. suite 뒤 source/r001 CAS를 다시 확인한다.

residual scan은 §4 current identifiers에 old r001/001 literal 0을 요구하되,
failed-r001 validator constants, PLAN_ROOT, reviewed R002/design/R016/R017 history와 first
review suffix처럼 열거한 historical roles만 허용한다.

## 10. P3 publication and post-build gate

P3 직전 r001/source/C0/R002/provenance CAS와 r002 absence를 다시 확인한다. write
결과는 정확히 `PUBLISHED_NEW`만 허용한다.

P3 직후 순서:

1. r001 §2 content+lstat, source, r002 exact six CAS capture
2. targeted 37-test suite, exit 0/skip 0
3. builder `--check`, exit 0
4. continuation CANDIDATE checker, exit 0
5. Goal CANDIDATE checker, exit 0
6. r001/source/r002 CAS와 active C0/R002/r021 재확인

present branch는 physical r002와 deterministic outputs exact equality, core와 두 wrappers
PASS, 두 번의 read-only check 전후 각 r002 file `(dev,ino,mode,uid,gid,nlink,size,
mtime_ns,ctime_ns,sha256)` 불변을 검사한다.

모든 argv/exit/stdout/stderr SHA, source 시작/종료 SHA와 r001/r002 hashes/bytes를
candidate review에 기록한다.

## 11. P4 r002 candidate reviews

두 독립 검수자는 exact r002 six outputs, output manifest, generator/source pins,
R016/R017/R018 chain, pre/post suite, namespace residual scan, publisher terminal과 두
candidate checkers를 재검증한다.

성공 판정:

```text
status=PASS_FOR_NON_EFFECTIVE_V25_CANDIDATE_ONLY
findings=BLOCKING=0 MAJOR=0 MINOR=0
authority=NONE_FOR_ACTIVATION_GOAL_PRODUCT
```

finding 하나라도 있으면 r002를 수정하지 않고 r003가 필요하다.

## 12. 후속 로드맵

1. R019 — exact r002/reviews 기반 one-shot monotonic lock/CAS, resolved review,
   checkpoint-last v2.5+r022 activation
2. R020 — seq3 immutable prefix 뒤 Goal replay와 v2.5 full19/repository-state
3. R021 — FP008 materialized/ready, v2.5 full19, `GOAL_STARTED`
4. R022 — Android adminapp read-only 신고 목록/상세 최소 slice와 종료검수

R019 PASS 전 canonical/checkpoint write 0, R020 PASS 전 Goal write 0, R021 valid
`GOAL_STARTED` 전 제품 write 0이다. 외부 전송·secret·실기기/formal/release credit은
계속 범위 밖이다.

## 13. success

```text
R018_DUAL_PLAN_REVIEW_PASS
AND R001_CONTENT_LSTAT_IMMUTABLE
AND THREE_FILE_DELTA_ONLY
AND PREBUILD_37_PASS_SKIP_ZERO
AND R002_PUBLISHED_NEW_EXACT
AND POSTBUILD_37_PASS_SKIP_ZERO
AND R002_CHECKERS_PASS
AND R002_DUAL_CANDIDATE_REVIEW_PASS
AND LIVE_C0_R021_UNCHANGED
AND CANONICAL_GOAL_PRODUCT_DELTA_ZERO
```

성공해도 상태는
`REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`뿐이다.
daylog/local-memory는 부모가 마지막에 한 번 통합한다.
