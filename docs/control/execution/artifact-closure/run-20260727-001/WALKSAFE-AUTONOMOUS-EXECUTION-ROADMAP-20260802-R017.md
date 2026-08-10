# WalkSafe v2.5 비효력 후보 correction 로드맵 R017

- 작성일: `2026-08-02`
- 상태: `PENDING_TWO_INTERNAL_REVIEWS`
- replaces: R016의 r001 publication·candidate-review 단계
- 보존하는 계약: R016 §2–§5의 truthful delegation, zero-credit, strict chronology,
  monotonic freshness, pre-C1 no-resume
- 현 정본: v2.4 / checkpoint sequence 39 / Gap·Backlog r021
- 이번 종착점: `REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`
- live canonical·checkpoint·Goal·제품 write 권한: `0`

R016 source correction과 pre-build 37-test gate는 통과했다. 그러나 r001 공개 뒤
동일 suite의 두 테스트가 candidate root absent만 허용한다는 결함이 드러났다. 이를
공개 전·후 dual-state oracle로 고치자 test generator binding이 바뀌어 이미 공개된
r001과 deterministic rebuild가 달라졌다. r001은 add-only 실패 후보로 그대로
보존하고, R017은 새 r002 revision만 준비·공개·검수한다.

R016 §8에서 예고한 activation R017은 이 correction 때문에 R018 이후로 밀린다.

## 1. 동결 입력

### 1.1 canonical·reviewed inputs

| 역할 | SHA-256 | bytes |
|---|---|---:|
| C0 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| R002 pair manifest | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| R002 PASS review | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| R016 roadmap | `49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2` | 12,690 |
| R016 structural PASS | `eced95aa2343427b24f80f8865e2fa8e9e3f320ad48c74fab7970d646a1fac87` | 3,758 |
| R016 skeptical PASS | `9bae362ad4c1f0c4b2567b534197938f77d825b5d73d76dbd3a184542649bc0e` | 5,354 |

R016이 봉인한 최신 사용자 지시는 trailing LF 없는 840 UTF-8 bytes,
SHA-256 `8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a`다.
R017은 이 원문·권한을 재해석하거나 확대하지 않는다.

### 1.2 corrected source preimage

| path | SHA-256 | bytes |
|---|---|---:|
| `scripts/walksafe_v2_5_candidate_validation.py` | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

test preimage는 공개 전 absent 분기와 공개 후 present 분기를 모두 검증한다. R017
source correction에서는 revision-specific assertion만 바꿀 수 있고 이 dual-state
oracle을 약화할 수 없다.

## 2. r001 실패 증거와 immutable 보존

r001 root:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r001/
```

exact six physical files:

| file | SHA-256 | bytes |
|---|---|---:|
| `static-plan-manifest-v2.5.0.candidate.json` | `213cdea4f061d77f587211b3c315f5fb27eb5a2f39dbe7a9382e2c34e7a29a68` | 34,117 |
| `v2.5-application-transaction-plan.candidate.json` | `bb7819d4c3cc27878685fea3d4764ef7d55d511674eb078fc0cce8bfc0840275` | 659,092 |
| `transition-history-v2.5.candidate.json` | `1b859617a6cc7a007a9cdca61f10d5b040ba743bbd90bd5f7183163a8a1f494e` | 2,676 |
| `walksafe-project-continuation-checkpoint-v2.5.candidate.json` | `273b320645ee17737d452af263d45b45058cd7ed012f0bf32c6a9345ae894864` | 218,564 |
| `v2.5-control-package-manifest.candidate.json` | `3e27d14670c804bcc6df202f360a9a0541c1b2a83b3ad759961d2319a577261d` | 27,037 |
| `candidate-output-manifest.json` | `ffd8e1616891f1d3fc75c24bcbfc9d676760afe786fe19d63218c66024119945` | 19,757 |

r001 package는 test SHA `5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459`,
68,995 bytes를 봉인했다. dual-state correction 뒤 test는 §1.2 값이므로 corrected
deterministic rebuild와 r001은 package/output-manifest 두 파일에서 달라진다.

관찰된 gate:

```text
pre-build corrected source: 37 tests, exit 0, skip 0, OK
r001 publication: PUBLISHED_NEW, package SHA 3e27d146...261d
immediate builder --check: exit 0
immediate two CANDIDATE checkers: exit 0 / exit 0
post-build absent-only test suite: exit 1, 37 tests, failures 2
dual-state test correction 뒤 post-build suite: exit 1, failures 2
corrected-source builder --check: exit 1,
  candidate outputs differ from deterministic rebuild
```

r001은 `effective=false, approved=false, applied=false`이고 canonical·Goal·제품 credit은
0이다. r001과 그 parent 아래 기존 entry는 삭제, overwrite, rename, repair, chmod,
resume하지 않는다. r001의 존재는 r002 publication을 막지 않으며 성공 증거로도 쓰지
않는다.

## 3. R017 write epochs와 allowlist

각 epoch는 직전 검증이 PASS해야 열린다.

1. P0 — 이 R017 문서 한 개 add-only
2. P1 — R017 독립 review 두 개 add-only
3. P2 — 아래 three-file source correction
4. P3 — r002 exact six-output root add-only publication
5. P4 — r002 candidate 독립 review 두 개 add-only

P2 allowlist:

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

R017에서 real dynamic receipt, resolved output, active final, canonical r022,
checkpoint, Goal, full19, 제품 파일 write는 0이다.

## 4. exact r002 source correction

### 4.1 revision namespace

core와 builder의 r001 candidate namespace를 다음 r002 namespace로 일관되게 바꾼다.

```text
BUNDLE_REL = .../v2-5-control-candidate-r002
PACKAGE_CANDIDATE_ID = WS-V25-CONTROL-CANDIDATE-20260730-R002
TRANSACTION_PLAN_ID = WS-V25-R022-APPLICATION-TRANSACTION-PLAN-20260730-R002
DELEGATION_REQUIREMENT_ID = WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R017-R002
QUICK_GATE_REQUIREMENT_ID = WS-V25-R022-FRESH-QUICK-GATE-REQUIRED-20260730-R002
FINAL_TRANSFORM_ID = WS-V25-R022-AUTHORIZED-FINAL-TRANSFORM-20260730-R002
APPLICATION_GATE_REL = .../WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-002
CANDIDATE_*_REVIEW_REL = .../v2-5-control-candidate-r002-independent-...-r001.md
```

builder 내부 revision-specific IDs도 R002로 바꾼다.

```text
WS-V25-TRANSITION-HISTORY-20260730-R002
WS-V25-PACKAGE-PREPARED-CHECKPOINT-20260730-R002
WS-V25-CANDIDATE-OUTPUT-MANIFEST-20260730-R002
```

future active checkpoint ID와 test expectation도
`WS-V25-R022-ACTIVE-CHECKPOINT-20260730-R002`로 맞춘다. staging cleanup test는
literal r001 glob 대신 `Path(BUNDLE_REL).name`에서 파생해 revision drift를 검출한다.

version-wide `PACKAGE_ID`, `MANIFEST_ID`, output filenames, v2.5 schema version과
canonical final paths는 바꾸지 않는다.

### 4.2 R017 provenance pins

R016 roadmap/review pins는 제거하지 않는다. P1 뒤 실제 R017 문서와 두 PASS review의
path/hash/bytes를 `TRUSTED_SOURCE_PINS`에 추가하고 future frozen review bindings에
다음 세 역할을 추가한다.

```text
r017_roadmap
r017_structural_review
r017_skeptical_review
```

미래 authorization request/receipt는 R016과 R017 chain, reviewed R002 pair/review,
r002 candidate dual reviews, control-core review를 물리적으로 결속한다. candidate
output은 미래 review hash를 추측하지 않으며 receipt self hash를 포함하지 않는다.

### 4.3 unchanged semantic contract

다음은 R016 exact contract와 byte-level 의미를 유지한다.

- full 840-byte directive와 SHA, normalized scope 분리
- external identity/signature/portable verification flags 모두 false
- v2 request/receipt exact schema, nonce-before-serialization, single-use
- 13개 zero delta와 `release_status_after=NOT_ELIGIBLE`
- `seq1 < seq2 < seq3`, seq2=`checked_at`, seq3=exact `+1us`, expiry boundary
- future supervisor same-process/same-lock monotonic `<=600s`; wall clock 비권위
- pre-C1 receipt/final partial terminal
  `NEW_REVISION_REQUIRED_UNCOMMITTED_AUTHORITY_ZERO`
- resume/overwrite/delete/repair 금지, C0 v2.4/r021 active
- candidate exact 6, dynamic slots 4, future resolved members 12
- candidate `effective=false, approved=false, applied=false`

구식 영문 approval token, `user_response_literal`, `USER_AUTHORIZATION_RESPONSE`,
same-transaction resume 문자열은 generator source와 output 모두 0건이어야 한다.

## 5. R017 독립 plan review

P1 exact files:

```text
docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R017-independent-structural-review-r001.md
docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R017-independent-skeptical-review-r001.md
```

둘은 동일 R017 SHA/bytes/lines를 결속하고 다음을 독립 검증한다.

- r001 six-file identity와 no-delete/no-overwrite 경계
- 실패 원인과 corrected test binding의 인과관계
- r002 namespace의 완전성, r001/r002 path·ID 충돌 0
- R016+R017 provenance DAG와 review-before-subject cycle 0
- three-file allowlist와 dual-state pre/post oracle
- canonical/Goal/product authority 0

PASS 조건:

```text
status=PASS
findings=BLOCKING=0 MAJOR=0 MINOR=0
authority_granted=R017_R002_CANDIDATE_CORRECTION_ONLY
```

finding 하나라도 있으면 P2 이후 write는 0이며 새 roadmap revision이 필요하다.

## 6. pre-build gate

P1 dual PASS와 P2 correction 뒤 다음을 순서대로 실행한다.

1. C0, R002, R016, R017/reviews, r001 six files, three generator source의 exact CAS
2. r002 path absent/non-symlink
3. Python AST와 duplicate literal-key 검사
4. forbidden legacy token scan 0
5. targeted suite:

```text
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest \
  tests.test_walksafe_v2_5_control_candidate_20260730 -q
```

성공은 `37 tests`, `skip=0`, `exit=0`, `OK`다. r002 absent 분기는 temp exact
six-output fixture에서 core와 두 wrappers를 검증해야 한다.

pre-build 직후 core/builder/test hash를 다시 읽고 시작값과 같아야 한다. r001 hash는
§2와 같아야 한다.

## 7. r002 publication과 post-build gate

builder는 six outputs를 모두 memory에서 만들고 semantic validation한 뒤 staging
file/directory fsync, `RENAME_NOREPLACE`, parent fsync로 r002 한 디렉터리를 공개한다.
publish 직전과 parent fsync 직후 source/input CAS를 재검증한다.

r002 absent가 아니거나 partial/mismatch/extra가 있으면 수정·삭제·resume하지 않고
`NEW_REVISION_REQUIRED`로 종료한다. r001 존재는 expected immutable history다.

publication 직후 같은 source hash에서 다음을 모두 실행한다.

```text
PYTHONDONTWRITEBYTECODE=1 python3 -B \
  scripts/build_walksafe_v2_5_control_candidate_20260730.py --check

PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest \
  tests.test_walksafe_v2_5_control_candidate_20260730 -q

PYTHONDONTWRITEBYTECODE=1 python3 -B \
  scripts/check_walksafe_project_continuation_v2_5_candidate.py --mode CANDIDATE

PYTHONDONTWRITEBYTECODE=1 python3 -B \
  scripts/check_walksafe_goal_graph_v2_5_candidate.py --mode CANDIDATE
```

모두 exit 0, suite는 37 tests/skip 0이어야 한다. present 분기는 physical r002
bytes와 deterministic outputs exact equality, core·두 wrappers PASS, 두 번의
`--check` 전후 각 파일 `(mtime_ns,size,sha256)` 불변을 검증한다.

모든 argv/exit/stdout/stderr SHA, source 시작/종료 SHA, r001/r002 six output
hash/bytes를 candidate reviews에 기록한다.

## 8. r002 candidate dual review

P4 두 검수자는 exact r002 six outputs, output manifest, generator bindings/source
pins, R016/R017 chain, pre/post 37-test receipts, 두 checker와 §4를 독립 검증한다.

성공 판정:

```text
status=PASS_FOR_NON_EFFECTIVE_V25_CANDIDATE_ONLY
findings=BLOCKING=0 MAJOR=0 MINOR=0
authority=NONE_FOR_ACTIVATION_GOAL_PRODUCT
```

finding 하나라도 있으면 r002를 수정하지 않고 r003 revision이 필요하다.

## 9. 후속 로드맵

1. R018 — exact r002/reviews 기반 one-shot monotonic lock/CAS, resolved review,
   checkpoint-last v2.5+r022 activation
2. R019 — seq3 immutable prefix 뒤 Goal replay와 v2.5 전용 full19/repository-state
3. R020 — FP008 materialized/ready, v2.5 full19, `GOAL_STARTED`
4. R021 — Android adminapp read-only 신고 목록/상세 최소 slice와 종료검수

R018 PASS 전 canonical/checkpoint write 0, R019 PASS 전 Goal write 0, R020 valid
`GOAL_STARTED` 전 제품 write 0이다. 외부 전송·secret·실기기/formal/release credit은
계속 범위 밖이다.

## 10. 성공 기준

```text
R017_DUAL_PLAN_REVIEW_PASS
AND R001_IMMUTABLE_FAILURE_CANDIDATE_UNCHANGED
AND THREE_FILE_REVISION_DELTA_ONLY
AND PREBUILD_37_PASS_SKIP_ZERO
AND R002_SIX_OUTPUT_PUBLISHED_NEW_EXACT
AND POSTBUILD_37_PASS_SKIP_ZERO
AND R002_CANDIDATE_CHECKERS_PASS
AND R002_CANDIDATE_DUAL_REVIEW_PASS
AND LIVE_C0_R021_UNCHANGED
AND CANONICAL_GOAL_PRODUCT_DELTA_ZERO
```

성공해도 상태는
`REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`뿐이다.
daylog/local-memory는 부모가 마지막에 한 번 통합한다.
