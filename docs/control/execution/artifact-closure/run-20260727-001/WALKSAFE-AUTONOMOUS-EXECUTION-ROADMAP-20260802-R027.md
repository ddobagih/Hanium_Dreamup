# WalkSafe 자율 실행 로드맵 R027 — 협조적 로컬 r002 후보 완결

- 상태: `PRE_REVIEW`
- 작성일: `2026-08-02`
- 결과 범위: 비효력 v2.5 `r002` source, exact-six candidate, 독립 검수 두 건
- 권한: activation·canonical/checkpoint·Goal·제품·배포 권한 없음

## 1. 선행 판정과 실제 기준선

R016만 accepted predecessor다. R017~R025와 각 review는 immutable rejected history이며
authority는 `NONE`이다. R025는 target
`8ee466ee1433108dd015f0318351bbcb9a0158ccdd1e4d238f991e72ea323e12 /
11,950`, structural review
`6b8b9c7dfd76512b154f9e48977973547c6092f050a8aef49b0b09533d5bc4da /
7,958`, skeptical review
`b3172b204eec4372961339e9b0b457a41822048e55ab2d3825acd77185c3801f /
8,567`이고 `BLOCKING=4`씩이라 실행 권한이 없다.

R026도 rejected다. Target은
`b5485b82c679824ca8e5a53e441bd0750e09cbad5c9ecef090224191db3db479 /
19,518`, structural review는
`f0b1e69159554282b072894009adcd2681ee533250e37cfc4d5c7f4b2df79cac /
5,798`, skeptical review는
`0c50eb94c748e974922f8f8cf2e77a80307036e7f7b28bb08301e24bd773733d /
7,091`이며 staging allowlist 1 blocking과 operation-kind/daylog-prefix 1 blocking·1 major로 권한은 `NONE`이다.

R027 preflight의 exact content pins는 다음이다.

| 역할 | path | SHA-256 | bytes |
|---|---|---|---:|
| C0 | `docs/control/walksafe-project-continuation-checkpoint.json` | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| reviewed pair | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/gap-backlog-pair-manifest.json` | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| pair review | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/INDEPENDENT-REVIEW-R001.md` | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| R016 roadmap | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R016.md` | `49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2` | 12,690 |
| R016 structural | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R016-independent-structural-review-r001.md` | `eced95aa2343427b24f80f8865e2fa8e9e3f320ad48c74fab7970d646a1fac87` | 3,758 |
| R016 skeptical | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R016-independent-skeptical-review-r001.md` | `9bae362ad4c1f0c4b2567b534197938f77d825b5d73d76dbd3a184542649bc0e` | 5,354 |
| core S0 | `scripts/walksafe_v2_5_candidate_validation.py` | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| builder S0 | `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| test S0 | `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |
| continuation wrapper | `scripts/check_walksafe_project_continuation_v2_5_candidate.py` | `1a2dc4f1d8c28c5163cae8306a160e8f942f962ea2ec190a625b64dc688ece5c` | 2,513 |
| Goal wrapper | `scripts/check_walksafe_goal_graph_v2_5_candidate.py` | `98d656e8c3c54e122a419eee9d2c3b3285f2594f4abd3abbb5597d8e9470feda` | 2,434 |
| daylog preimage | `daylog/2026-08-02.md` | `20983d0f481699bee1d708b059a34be85dacccf1dca1f06489e60938f2522d4b` | 2,987 |

S0 raw bytes의 독립 비교 원본은 외부 backup
`/home/ddobagi/.codex/backups/walksafe/20260802T0125KST-rc2-pre-roadmap/
untracked-files.tar.gz`, SHA
`2de0201890d73a7067d2c5c879a69ffd31bcba8bfcc7ffbf21d0fdae1a7fe398`,
360,037,403 bytes다. Archive의 exact 세 member path가 S0 세 path와 같고 각 추출 bytes는
위 pins와 일치해야 한다. Backup은 읽기만 한다.

실행하지 않은 supervisor 초안
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r025-content-cas-supervisor-r001/walksafe_r002_content_supervisor.py`
(`00a624e8264b1b6d65bba57e5f36f7d253e3871dca15baab7babf622e15c0dc4 /
35,695`)은 실패 evidence다. Import·실행·수정·후계본 생성은 0이다.

## 2. 위협 범위와 authority boundary

R027은 한 명의 협조적 로컬 실행자가 관련 경로를 순차 처리하는 모델이다. 같은 uid의
hostile concurrent mutation, transient substitution, kernel/runtime/filesystem compromise,
고의로 거짓말하는 reviewer는 범위 밖이다. 이는 R024 skeptical review가 허용한 대안이다.

Pre/post SHA는 관측 경계의 content equality만 증명한다. inode·mtime·ctime은 authority가
아니고 same-byte replacement/touch는 의미동등이다. 관측된 different-byte, unsafe type,
unexpected path delta는 실패다.

모든 결과에서 exact authority boundary는 다음이다.

    effective=false
    approved=false
    applied=false
    evidence_only=true
    activation_authorized=false
    canonical_write_authorized=false
    checkpoint_write_authorized=false
    goal_write_authorized=false
    product_write_authorized=false

## 3. 전체 exact write allowlist

아래 외 project write는 0이다. 각 target은 표의 operation kind만 허용한다. P1/P3/P4 final/P5
add-only target은 any-existing/EEXIST/partial이면 terminal이고 overwrite·repair·resume하지
않는다. P2는 exact S0 existing file만 한 번 update하며 S0 mismatch나 세 파일 밖 source delta는
terminal이다. P0는 이 문서 exact binding을 읽기만 하고 daylog는 §9의 exact prefix에 한 번
append만 한다.

| epoch | operation | exact target |
|---|---|---|
| P0 | `EXISTING_PINNED_ROADMAP` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R027.md` |
| P1 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R027-independent-structural-review-r001.md` |
| P1 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R027-independent-skeptical-review-r001.md` |
| P2 | `UPDATE_EXISTING_EXACT_S0_ONCE` | `scripts/walksafe_v2_5_candidate_validation.py` |
| P2 | `UPDATE_EXISTING_EXACT_S0_ONCE` | `scripts/build_walksafe_v2_5_control_candidate_20260730.py` |
| P2 | `UPDATE_EXISTING_EXACT_S0_ONCE` | `tests/test_walksafe_v2_5_control_candidate_20260730.py` |
| P3 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-independent-structural-review-r001.md` |
| P3 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-independent-skeptical-review-r001.md` |
| P4 | `CREATE_OWNED_TRANSIENT_THEN_RENAME_OR_CLEAN` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/.v2-5-control-candidate-r002.staging-<pid>-<token>/` |
| P4 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/` exact six from §8 |
| P5 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-structural-review-r001.md` |
| P5 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-independent-skeptical-review-r001.md` |
| handoff | `APPEND_EXISTING_EXACT_PREFIX_ONCE` | existing `daylog/2026-08-02.md`에 이번 작업 section 한 번 append |

P4 transient basename은 exact regex
`.v2-5-control-candidate-r002\.staging-[1-9][0-9]*-[0-9a-f]{16}`과 일치하고 plan
root의 direct child다. Invocation 시작에는 absent이며 이 invocation이 mode `0700`으로 하나만
만든다. 그 안에는 exact-six regular files만 생성한다. Pre-rename failure/EEXIST에서는 이
invocation이 만든 members와 directory만 정리할 수 있고 foreign target은 건드리지 않는다.
Rename 성공 시 같은 directory object가 final r002가 되며 parent fsync 뒤 staging basename은
absent다. 성공·실패 종료에 staging이 남으면 R027은 실패이고 자동 재개하지 않는다.

Local-memory DB 기록은 project write가 아니며 종료 handoff에서만 수행한다. Git index,
wrapper, failed-r001, canonical/checkpoint/Goal/product는 write allowlist에 없다.

## 4. P1 — R027 계획 독립 이중 검수

두 reviewer는 같은 R027 SHA/bytes 전체를 읽고 시작/종료 equality를 확인한다. Exact review
paths는 §3의 P1 두 path다. 각 review는 `review_id,reviewer_agent,reviewer_axis,target_path,
target_sha256,target_bytes,reviewed_at,status,findings,authority_granted`를 한 번씩 기록한다.

PASS exact:

    status: PASS_FOR_NON_EFFECTIVE_R002_EXECUTION_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority_granted: NONE_FOR_ACTIVATION_GOAL_PRODUCT

Finding이 하나라도 있으면 P2 이후 write와 source/build/test 실행은 0이고 새 revision이다.

## 5. P2 preflight, dirty baseline, source patch

P1 dual PASS 뒤 §1 pins, failed-r001, future target absence를 다시 검사한다. Failed-r001은
root exact-six byte-sorted basename digest
`f4c9e884242b5acf190f0cb95d1567845173d8ae8e3a20919d81519f88b5d86c`와 다음 파일
SHA/bytes를 갖는다.

| basename | SHA-256 | bytes |
|---|---|---:|
| `candidate-output-manifest.json` | `ffd8e1616891f1d3fc75c24bcbfc9d676760afe786fe19d63218c66024119945` | 19,757 |
| `static-plan-manifest-v2.5.0.candidate.json` | `213cdea4f061d77f587211b3c315f5fb27eb5a2f39dbe7a9382e2c34e7a29a68` | 34,117 |
| `transition-history-v2.5.candidate.json` | `1b859617a6cc7a007a9cdca61f10d5b040ba743bbd90bd5f7183163a8a1f494e` | 2,676 |
| `v2.5-application-transaction-plan.candidate.json` | `bb7819d4c3cc27878685fea3d4764ef7d55d511674eb078fc0cce8bfc0840275` | 659,092 |
| `v2.5-control-package-manifest.candidate.json` | `3e27d14670c804bcc6df202f360a9a0541c1b2a83b3ad759961d2319a577261d` | 27,037 |
| `walksafe-project-continuation-checkpoint-v2.5.candidate.json` | `273b320645ee17737d452af263d45b45058cd7ed012f0bf32c6a9345ae894864` | 218,564 |

It remains seq1-only and `effective/approved/applied=false`; package generator test binding is
`5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459 /
68,995`. It is never repaired, deleted, reused or treated as current.

Before source patch, compute one NUL-safe content manifest digest in memory. Walk the repository
without following symlinks, exclude `.git`, and byte-sort repository-relative paths. Exclude only
P2 source three, P3 source review two, P4 r002 root prefix, P5 candidate review two and the daylog.
For every other entry hash a canonical NUL row containing path bytes, type, permission bits and:

- regular: bytes and SHA-256
- symlink: raw link target bytes
- directory: no content field
- other: mode and rdev

Separately resolve the worktree Git index path and include its bytes/SHA. Record only the aggregate
SHA-256 and row count in memory. End of P5 recomputes the same algorithm; exact equality is
`UNRELATED_WORKTREE_UNCHANGED`. Thus an already dirty tracked/untracked file changing content is
not hidden by an unchanged `M`/`??` status.

Then observe one real nonfuture Asia/Seoul microsecond timestamp later than both P1 reviews and seal
it as `P2_PREPARATION_STARTED_AT` and physical `PREPARED_AT`. One `apply_patch` modifies exactly
core, builder and test; wrappers stay byte-exact.

## 6. Exact R002 namespace and minimal source delta

Current construction uses the following exact values.

```text
BUNDLE_REL=plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002
APPLICATION_GATE_REL=docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-002
V25_PLAN_FINAL_REL=${APPLICATION_GATE_REL}/application-transaction-plan.json
RESOLVED_OUTPUT_MANIFEST_REL=${APPLICATION_GATE_REL}/resolved-output-manifest.json
PACKAGE_CANDIDATE_ID=WS-V25-CONTROL-CANDIDATE-20260730-R002
TRANSACTION_PLAN_ID=WS-V25-R022-APPLICATION-TRANSACTION-PLAN-20260730-R002
DELEGATION_REQUIREMENT_ID=WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R027-R002
QUICK_GATE_REQUIREMENT_ID=WS-V25-R022-FRESH-QUICK-GATE-REQUIRED-20260730-R002
FINAL_TRANSFORM_ID=WS-V25-R022-AUTHORIZED-FINAL-TRANSFORM-20260730-R002
V25_CHECK_COMMAND_CONTRACT_VERSION=2026-08-02.2
HISTORY_ID=WS-V25-TRANSITION-HISTORY-20260730-R002
PREPARED_CHECKPOINT_ID=WS-V25-PACKAGE-PREPARED-CHECKPOINT-20260730-R002
OUTPUT_MANIFEST_ID=WS-V25-CANDIDATE-OUTPUT-MANIFEST-20260730-R002
ACTIVE_CHECKPOINT_ID=WS-V25-R022-ACTIVE-CHECKPOINT-20260730-R002
SEQ1_EVENT_ID=WS-V25-PACKAGE-PREPARED-20260730-002
SEQ2_EVENT_ID=WS-V25-PACKAGE-ACTIVATED-20260730-002
SEQ3_EVENT_ID=WS-V25-BULK-REBASELINE-APPLIED-20260730-002
```

Core/builder derive every path/ID from these constants and validate exact equality. R016 trio is
accepted predecessor; R017~R026 trios are rejected history; R027 roadmap/P1 reviews are current
execution provenance. R027 exact pins are inserted only after P1 files physically exist.

Intentional historical literal allowlist is only: plan root `r001`, reviewed R002 directory,
R022 design lineage, review filename suffix `r001`, R016~R026 historical paths/IDs,
failed-r001 bundle/generator binding, version-wide PACKAGE/MANIFEST IDs and exact six output
filenames. Other current-construction `candidate-r001`, `R001`, gate/event `001` is failure.

The exact current user directive remains UTF-8 840 bytes and SHA
`8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a`.
It records truthful local-session delegation only and grants no external identity/signature credit.

Core adds a content-only `validate_failed_r001_bundle(root)` and builder/core/check paths call it
before/after construction, before rename and after parent fsync. Builder removes exact-existing
recovery. Any existing r002 type/content and rename EEXIST raises
`NEW_REVISION_REQUIRED_EXISTING_R002_TARGET_AUTHORITY_ZERO`; only own staging may be cleaned.
Parent-fsync ambiguity leaves a published target untouched and makes same-revision retry terminal.
Unique success is `PUBLISHED_NEW`.

TEST-only times derive only from P2 base: +5m core review, +10m request, +15m authorization,
+15m30s quick start, +16m quick complete/check, +26m valid-until and checked+1µs seq3. Physical
prepared time is the unshifted observed base.

## 7. P3 — source diff strength review

Before executing the modified test or builder, two reviewers compare S1 against exact S0 bytes
extracted with `tar -xOf` from the pinned backup. Exact paths are §3 P3 paths. They inspect the full
three-file diff, not only hashes.

For test AST, 36 method names remain and exactly this rename is allowed:

    test_add_only_parent_fsync_failure_recovers_exact_target
    -> test_add_only_parent_fsync_failure_leaves_target_and_retry_is_terminal

Allowed changed test bodies are exact old/new names for:

```text
test_physical_candidate_is_seq1_only_and_non_effective
test_authorized_in_memory_seq1_seq2_seq3_projection_passes
test_quick_gate_must_follow_and_bind_delegation_and_source_cas
test_authorized_chronology_rejects_non_strict_and_invalid_times
test_delegation_nonce_and_frozen_bindings_fail_closed
test_rfc3339_and_noncanonical_physical_receipt_fail_closed
test_trusted_source_cas_rejects_coordinated_manifest_attack
test_missing_or_unexpected_json_is_partial_install
test_add_only_preflight_and_partial_write_failure_leave_no_bundle
test_add_only_parent_fsync_failure_recovers_exact_target
test_add_only_parent_fsync_failure_leaves_target_and_retry_is_terminal
test_check_is_read_only_and_active_canonical_targets_are_absent
test_removed_fake_response_tokens_are_absent_from_sources_and_outputs
```

Every other test method AST dump excluding location attributes is byte-equal. Total methods remain
37. No `skip`, `skipIf`, `skipUnless`, `expectedFailure`, empty/pass body or unconditional assertion
is added. Per changed method, existing unittest assertion-call kind counts do not decrease; new
negative subcases exercise R002 exact IDs, chronology, failed-r001 mutation/extra/link/unsafe state,
existing exact/file/symlink/rename race/fsync terminal and read-only checks.

Core/builder diff is limited to §6 constants/provenance, failed-r001 validator, time derivation,
namespace validation and no-recovery publication. No canonical/Goal/product write or generic new
feature is allowed.

Source review PASS exact:

    status: PASS_FOR_R027_THREE_SOURCE_PATCH_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority: NONE_FOR_PUBLICATION_UNTIL_RUNTIME_GATES

Each review records S0/S1 three bindings, unchanged wrapper bindings, full diff scope and reviewed_at.
Finding means source/build/test execution and candidate publication are 0; source changes remain
visible evidence and require a new roadmap.

## 8. P4 — runtime gates and add-only publication

Runtime is exact `/usr/bin/python3.14`, Python `3.14.4`, SHA
`b8d8288faefdd300201f43fcf00f6f539a27218eeed3a3dff5ab10b9c4c99700`.
Environment is `env -i PATH=/usr/bin LC_ALL=C.UTF-8 TZ=Asia/Seoul PYTHONHASHSEED=0
PYTHONDONTWRITEBYTECODE=1`; cwd is repository root.

Prepublication command:

    /usr/bin/python3.14 -B -m unittest -v tests.test_walksafe_v2_5_control_candidate_20260730

It must exit 0 with exactly 37 tests, skipped/failure/error 0 and final `OK`. Recheck S1, wrappers,
C0, R016/pair, failed-r001 and r002 absence. Then invoke exactly once:

    /usr/bin/python3.14 -B scripts/build_walksafe_v2_5_control_candidate_20260730.py --root <repo-root>

Only exit0 plus `publication_result=PUBLISHED_NEW` succeeds. The r002 directory then has exact six
byte-sorted basenames:

```text
candidate-output-manifest.json
static-plan-manifest-v2.5.0.candidate.json
transition-history-v2.5.candidate.json
v2.5-application-transaction-plan.candidate.json
v2.5-control-package-manifest.candidate.json
walksafe-project-continuation-checkpoint-v2.5.candidate.json
```

Root is non-symlink directory; members are regular non-symlink nlink1, with no extra/missing.
Physical bytes equal deterministic rebuild and output/package manifests' zero-authority projections.
Failure/partial/ambiguous target is preserved without repair/delete/resume and closes R027.

## 9. P5 — post gates and candidate dual review

Run, in this order, under §8 runtime/environment:

1. the same 37-test command;
2. `/usr/bin/python3.14 -B scripts/build_walksafe_v2_5_control_candidate_20260730.py --check --root <repo-root>`;
3. `/usr/bin/python3.14 -B scripts/check_walksafe_project_continuation_v2_5_candidate.py --root <repo-root> --mode CANDIDATE`;
4. `/usr/bin/python3.14 -B scripts/check_walksafe_goal_graph_v2_5_candidate.py --root <repo-root> --mode CANDIDATE`.

Required outputs are respectively 37/skip0/error0/OK; builder `mode=CHECK` and
`publication_result=NOT_APPLICABLE`; continuation exact `PASS mode=CANDIDATE`; Goal exact
`PASS mode=CANDIDATE`. All exit 0. Parent records for each run exact ordered argv, exit, parsed
test counters or null, stdout/stderr SHA/bytes and status.

Two independent reviewers then re-run the same read-only gates and inspect the full source diff,
P3 source reviews and physical candidate. Exact output paths are §3 P5 paths. Each review contains:

- R027 roadmap and P1 plan review bindings;
- S0/S1 three rows and unchanged wrapper rows;
- P3 source review bindings;
- candidate object exact keys `path,entry_count,entry_name_digest_sha256,files`, with files
  byte-sorted and each exact `{path,sha256,bytes}`;
- four ordered gate rows with exact keys `role,argv,exit_code,stdout_sha256,stdout_bytes,
  stderr_sha256,stderr_bytes,tests_run,skipped,failures,errors,status`;
- candidate/source bindings at review start and end, reviewed_at and authority boundary.

PASS exact:

    status: PASS_FOR_NON_EFFECTIVE_V25_CANDIDATE_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority: NONE_FOR_ACTIVATION_GOAL_PRODUCT

Finding preserves the candidate as failed evidence and grants no activation.

After both PASS, recompute §5 unrelated-worktree digest and all protected pins. Before handoff,
`daylog/2026-08-02.md` must still equal the §1 preimage SHA/bytes and raw 2,987 bytes are retained in
memory. One parent `apply_patch` appends exactly one terminal-LF suffix beginning
`\n## R027 r002 후보 실행\n\n`; final raw must equal `original_preimage + suffix`, and that heading
occurs exactly once. Prefix truncation/rewrite or a second suffix is failure. Only then are exact
candidate/source/review bindings and gate results recorded in the suffix and local-memory. This
handoff grants no candidate authority. There is deliberately no ambiguous closure manifest; future
R028 must pin R027 roadmap/P1 reviews, S1, P3 reviews, exact-six candidate and both P5 reviews
independently and must re-run gates.

## 10. 금지 범위, 성공 조건, 후속

The following remain zero: canonical/checkpoint/Goal/runtime queue/product write; seq2/seq3
application; formal/device/event/release credit; commit/push/PR/deploy/institution submission;
paid service, secret use; failed-r001 or unrelated user data deletion/overwrite.

Success is the conjunction:

    R027_DUAL_PLAN_REVIEW_PASS
    AND DIRTY_BASELINE_CAPTURED
    AND S0_TO_S1_EXACT_THREE_FILE_PATCH
    AND R027_DUAL_SOURCE_REVIEW_PASS
    AND PREBUILD_37_PASS_SKIP_ZERO
    AND R002_PUBLISHED_NEW_EXACT_SIX
    AND POSTBUILD_FOUR_GATES_PASS
    AND R002_DUAL_CANDIDATE_REVIEW_PASS
    AND UNRELATED_WORKTREE_UNCHANGED
    AND PROTECTED_INPUTS_UNCHANGED
    AND DAYLOG_PREFIX_PRESERVED_SINGLE_APPEND
    AND CANONICAL_CHECKPOINT_GOAL_PRODUCT_DELTA_ZERO

Result is
`REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`.

Successors are R028 activation transaction, R029 Goal replay/full19, R030 FP008
materialized/ready/`GOAL_STARTED`, R031 Android minimal read-only slice. Each must explicitly pin
the previous bindings. Before R028 canonical/checkpoint, before R029 Goal and before valid R030
start product writes remain forbidden.
