# WalkSafe 자율 실행 로드맵 R029 — 결정적 S0 복구와 r002 교정 후보 완결

- 상태: `PRE_REVIEW`
- 작성일: `2026-08-02`
- 결과 범위: R027 failed S1 보존, exact S0 복구, S1→S2 교정, 비효력 v2.5 `r002` exact-six 후보
- 권한: activation·canonical/checkpoint·Goal·제품·배포 권한 없음

## 1. 선행본과 exact 기준선

R016만 accepted predecessor다. R017~R026은 rejected history다. R027은 plan review 뒤 source
patch가 실패했고 source dual review가 execution을 차단했다. R028은 recovery/correction plan을
작성했지만 P1 review에서 실행 전 차단됐으므로 recovery backup, source/build/test/wrapper 실행,
publication, candidate review와 daylog append는 0이다.

| 역할 | path | SHA-256 | bytes | 판정 |
|---|---|---|---:|---|
| R027 roadmap | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R027.md` | `79544b59b5f3168cc16925f7972ff23cc47c221d83e69b6cdaea751f21005467` | 22,055 | execution failed |
| R027 source structural | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-independent-structural-review-r001.md` | `fed5d269e5be776b4b786e34994e40cfe389ebf297de706938d13ca312ad8b86` | 12,646 | B6 |
| R027 source skeptical | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-independent-skeptical-review-r001.md` | `6cf06ede378ffe10ddcc2e9ca679cd77a57f38911faee298457e01e07f04f00a` | 11,381 | B6 M1 |
| R028 roadmap | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R028.md` | `0a83406951f4963405035649a52675e781f706cbd11bac6d0060540a8567f6db` | 20,142 | plan rejected |
| R028 structural review | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R028-independent-structural-review-r001.md` | `72f4597411e59e666a2d84363a46789eada3eee8583e3331efeedbb735596ce5` | 6,804 | B3 |
| R028 skeptical review | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R028-independent-skeptical-review-r001.md` | `cb7285561a4eaa70a49da286aba783cc03b83cf7870abfe94fbeef63a2e556b5` | 7,496 | B4 |

R027 failed S1은 live baseline이며 P2 backup에 raw-byte 보존한 뒤 exact 세 파일을 한 번만
S1→S2 update한다.

| 역할 | path | S1 SHA-256 | bytes |
|---|---|---|---:|
| core | `scripts/walksafe_v2_5_candidate_validation.py` | `926f2a997692aff9e996458f3099515fb55bf51ee9171197c4ea27bcdf0b57f1` | 187,211 |
| builder | `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `2960f1b6e49cd2dbd8904de15fe23327eb3b5959efccc68a42be564532c0e96a` | 48,374 |
| test | `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `874c89e04d67ef2c27709e84917ee01a98faa73405b983be2a299abc72a834c5` | 70,163 |

복구할 exact S0는 core
`e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f / 180,432`,
builder `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175 /
51,153`, test `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523 /
69,685`다.

Wrappers는 continuation
`1a2dc4f1d8c28c5163cae8306a160e8f942f962ea2ec190a625b64dc688ece5c / 2,513`, Goal
`98d656e8c3c54e122a419eee9d2c3b3285f2594f4abd3abbb5597d8e9470feda / 2,434`다.
C0는 `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c /
1,329,415`, daylog preimage는
`20983d0f481699bee1d708b059a34be85dacccf1dca1f06489e60938f2522d4b / 2,987`다.

## 2. 위협·authority와 exact write allowlist

한 명의 협조적 local executor가 순차 실행한다. Hostile same-UID concurrent mutation과
kernel/runtime/filesystem compromise는 범위 밖이다. 모든 경계에서 exact content/type를 다시
검사하고 drift, unexpected type와 partial target은 terminal이다. 모든 결과는
`effective=false`, `approved=false`, `applied=false`, `evidence_only=true`다.

| epoch | operation | exact target |
|---|---|---|
| P0 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R029.md` |
| P1 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R029-independent-structural-review-r001.md` |
| P1 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R029-independent-skeptical-review-r001.md` |
| P2 | `ADD_ONLY_FINAL_ROOT_NO_RETRY` | `/home/ddobagi/.codex/backups/walksafe/20260802T1510KST-r027-failed-s1-r029-recovery-r001/` |
| P3 | `UPDATE_EXISTING_EXACT_S1_ONCE` | `scripts/walksafe_v2_5_candidate_validation.py` |
| P3 | `UPDATE_EXISTING_EXACT_S1_ONCE` | `scripts/build_walksafe_v2_5_control_candidate_20260730.py` |
| P3 | `UPDATE_EXISTING_EXACT_S1_ONCE` | `tests/test_walksafe_v2_5_control_candidate_20260730.py` |
| P4 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r029-independent-structural-review-r001.md` |
| P4 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r029-independent-skeptical-review-r001.md` |
| P5 | `CREATE_OWNED_TRANSIENT_THEN_RENAME_OR_TERMINAL` | plan-root child `.v2-5-control-candidate-r002.staging-<pid>-<16-lowerhex>/` |
| P5 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/` |
| P6 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r029-independent-structural-review-r001.md` |
| P6 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r029-independent-skeptical-review-r001.md` |
| handoff | `APPEND_EXISTING_EXACT_PREFIX_ONCE` | `daylog/2026-08-02.md` |

P2 root는 `0700` exclusive-create하고 members는 `O_CREAT|O_EXCL|O_NOFOLLOW` `0600`이다.
P2/P5의 preexisting target, intermediate failure 또는 fsync ambiguity는 그대로 보존하고
delete·overwrite·repair·same-revision retry는 0이다. P5에서 own pre-rename staging만 cleanup할 수
있으며 post-rename target과 foreign path는 수정하지 않는다.

## 3. P1 — R029 plan dual review

두 reviewer는 전체 문서, §1 pins, P2 state machine, dirty exclusion, assertion mapping,
P3/P4/P5/P6 ordering과 zero authority를 독립 검산한다. 시작/종료 target SHA/bytes가 같아야 한다.

PASS exact:

    status: PASS_FOR_R029_CORRECTION_AND_NON_EFFECTIVE_R002_EXECUTION_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority_granted: NONE_FOR_ACTIVATION_GOAL_PRODUCT

Finding이면 P2 이후 write와 source/build/test 실행은 0이며 새 revision이다.

## 4. P2 — dirty baseline과 결정적 self-contained recovery

P1 dual PASS 뒤 r002 final/staging, P4/P6 reviews와 P2 root가 absent인지 확인한다. Repository
dirty manifest는 `.git`을 제외하고 nofollow로 byte-sort NUL rows를 만든다. Exclusion은 다음
exact set/prefix뿐이다.

```text
scripts/walksafe_v2_5_candidate_validation.py
scripts/build_walksafe_v2_5_control_candidate_20260730.py
tests/test_walksafe_v2_5_control_candidate_20260730.py
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r029-independent-structural-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r029-independent-skeptical-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/  # exact final-root prefix
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r029-independent-structural-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r029-independent-skeptical-review-r001.md
daylog/2026-08-02.md
```

어떤 `.v2-5-control-candidate-r002.staging-*` entry도 제외하지 않는다. Regular row는 path/type/
permission/bytes/SHA, symlink는 raw target, directory는 path/type/permission, other는 mode/rdev를
결속한다. Git index path를 별도로 resolve해 bytes/SHA row를 더한다. P2 직전 aggregate SHA,
row count, index SHA/bytes를 메모리에 보존하고 P6 종료 뒤 exact 같은 알고리즘으로 equality를
요구한다. Staging absence도 별도 conjunction이다.

Transcript binding은 다음이다.

```text
path=/home/ddobagi/.codex/sessions/2026/08/02/rollout-2026-08-02T11-46-26-019fc05d-96c2-77b3-98bf-c4958a9f3748.jsonl
sha256=680254dab32c326efe577f22d138ce20b144bdc686160bb81f3ebafb7630db95
bytes=1588479
call_id=call_T6mhZGVW6Me8eOweu8FcGhbU
call_raw_line=2b558124890039109290ec38c32e15f89364869c933be486c0277358acf759df/21438
output_raw_line=3a5f13914c081850be210f2137a3ac47eccb4b3b23ec27e0a4329633e454fa40/423
payload_input=afc338fb231e43eb655f0ea28d982df9ed49774141c30cd2d29f7a4191d163c6/19860
prefix=506af324d80900e7a2c9869dfb38486f57f58ef9d53e6d21c7f1ec3fc7af3416/14
json_string=fcbffd666a93cdccbeccc80302b47d631ea2cdda260d171495924ea43429c628/19784
suffix=7aadde5b87b088a5ed8a30417bec6c31527c481cb5b4b09b8bac98f934a3d637/62
suffix_utf8_hex=3b0a636f6e737420726573756c74203d20617761697420746f6f6c732e6170706c795f7061746368287061746368293b0a7465787428726573756c74293b
decoded_patch=d9c9071961bf621bd578f776a4d5498e28496c7364841e595c8e3f841255efe1/18972/395_splitlines/394_LF
```

Strict JSONL parse에서 call/output은 각각 정확히 하나다. `payload.input`은 exact 14-byte prefix,
JSON string 하나와 exact 62-byte suffix로만 분해한다. Suffix 비교는 HTML/entity decode 없이
위 raw UTF-8 hex와 한다. JSON string decode 결과만 patch bytes다.

Patch exact 세 Update File section을 line stream으로 parse한다. 각 `@@` chunk에서 context와
forward `+` lines가 postimage, context와 forward `-` lines가 preimage다. Section마다 cursor 0에서
시작하고 현재 state의 cursor 이상에서 postimage와 같은 모든 start를 찾는다. Match가 없으면
terminal이고 둘 이상이면 **가장 작은 0-based start를 선택**한다. Replace 뒤
`cursor=start+len(preimage)`로 두고 exact edit record
`{start,preimage,postimage}`를 순서대로 보존한다. 이 leftmost-after-cursor 규칙이 local ambiguity의
유일한 해석이다.

모든 inverse edit 뒤 recovered bytes가 §1 S0 SHA/bytes와 raw-equal이어야 한다. Round-trip은
새 anchor search를 하지 않는다. 각 file의 edit records를 역순으로 순회하며 recorded `start`에
exact `preimage`가 있음을 확인한 뒤 `postimage`로 되돌린다. 결과가 시작 S1 raw bytes와
exact-equal이어야 한다. 이 state machine은 25/13/10 records를 각각 만들고, 다른 count는
terminal이다.

성공 뒤 session과 무관한 P2 root exact ten regular files를 생성한다.

```text
transcript/custom-tool-call.jsonl
transcript/custom-tool-call-output.jsonl
transcript/apply-patch-input.txt
failed-s1/scripts/walksafe_v2_5_candidate_validation.py
failed-s1/scripts/build_walksafe_v2_5_control_candidate_20260730.py
failed-s1/tests/test_walksafe_v2_5_control_candidate_20260730.py
recovered-s0/scripts/walksafe_v2_5_candidate_validation.py
recovered-s0/scripts/build_walksafe_v2_5_control_candidate_20260730.py
recovered-s0/tests/test_walksafe_v2_5_control_candidate_20260730.py
recovery-manifest.json
```

첫 세 파일은 raw call/output/decoded patch, 다음 세 쌍은 exact S1/S0다. Manifest는 version 1,
artifact ID `WS-V25-R029-R027-FAILED-S1-S0-RECOVERY-R001`, observed `created_at`, transcript/
patch/state-machine/S1/S0 bindings, authority false fields와 self 제외 nine rows를 path-byte-sort로
담는다. JSON은 UTF-8 sorted compact terminal LF다. 각 file fsync, leaf-to-root dir fsync와
backup parent fsync 뒤 exact tree/type/nlink1/content/manifest projection을 재검사하고 manifest
SHA/bytes를 메모리에 봉인한다. Partial/ambiguous failure는 root를 보존하고 R029를 종료한다.

## 5. P3 — exact S1→S2 one patch

P2 성공과 S1 재확인 뒤 한 `apply_patch` call이 exact 세 source만 update한다. Live S0 write,
wrapper/helper update와 두 번째 source patch는 0이다. P1/P2 뒤 실제 nonfuture Asia/Seoul
microsecond를 `R029_PREPARATION_STARTED_AT`과 physical `PREPARED_AT`으로 봉인한다.

R002 bundle/gate/event와 package/transaction/final/history/checkpoint/manifest IDs는 유지한다.
Delegation은 `WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R029-R002`, check contract는
`2026-08-02.4`다. Exact directive는 UTF-8 840 bytes,
`8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a`다. Test-only valid times는
새 base에서 +5m/+10m/+15m/+15m30s/+16m/+26m, seq3는 checked +1us로만 derive한다.

S2는 다음을 모두 닫는다.

1. R017~R026 rejected, R027 failed, R028 rejected와 R029 current roadmap/P1 trios를 actual
   SHA/bytes/role로 pin한다. R027 failed source-review 두 건도 trusted source로 pin한다.
2. Existing `CANDIDATE_*_REVIEW_REL` key 이름은 유지하고 value만 P4 fresh `source-r029` 두
   path로 바꾼다. P6 candidate reviews는 code/output binding에 넣지 않는다.
3. Exact `validate_failed_r001_bundle(root)`로 이름을 통일하고 nofollow exact-six/type/nlink1/
   owner/world-write/content, history seq1, role별 non-effective metadata와 failed test binding을
   검증한다. Construction entry/after-output, rename 직전, parent-fsync 직후, check entry/exit,
   candidate/quick/active validation entry/exit에서 재검증한다.
4. `load_source_state()`와 `authorization_subject_bindings()`은 completed bindings를 한 번만
   return한다. Auth bindings는 R017~R029와 R027 failed-source rows를 포함하며 unreachable code가
   없다.
5. Activation envelope checkpoint row는 `ACTIVE_CHECKPOINT_ID` R002 상수에서 derive한다.
   Current construction의 비허용 `candidate-r001`, `R001`, gate/event `001`은 0이다.
6. Builder는 staging physical exact-six를 nofollow로 다시 읽어 deterministic bytes/semantics를
   확인하고 failed-r001을 rename 직전과 successful parent-fsync 직후 재검사한다. Existing
   directory/file/symlink, rename EEXIST와 fsync ambiguity는 overwrite/delete/recovery 없이
   terminal이며 post-rename target은 보존한다.
7. Temp publication fixtures는 failed-r001 exact-six raw bytes를 복사한다. Failed-r001 pristine/
   mutation/extra/member-link/unsafe, trusted provenance mutation, final member type, target type,
   partial write/stage drift/rename race/fsync ambiguity/read-only matrix를 분리해 검사한다.

S1 test method exact 37 names는 유지하고 S1→S2 rename은 0이다. 변경 허용 body는 다음 여덟
개와 non-test fixture helper뿐이다.

```text
test_quick_gate_must_follow_and_bind_delegation_and_source_cas
test_authorized_chronology_rejects_non_strict_and_invalid_times
test_rfc3339_and_noncanonical_physical_receipt_fail_closed
test_trusted_source_cas_rejects_coordinated_manifest_attack
test_missing_or_unexpected_json_is_partial_install
test_add_only_preflight_and_partial_write_failure_leave_no_bundle
test_add_only_parent_fsync_failure_leaves_target_and_retry_is_terminal
test_check_is_read_only_and_active_canonical_targets_are_absent
```

Recovered S0 old name는
`test_add_only_parent_fsync_failure_recovers_exact_target`, S1/S2 mapped name은
`test_add_only_parent_fsync_failure_leaves_target_and_retry_is_terminal`이다. Assertion-call kind
lower bound는 S0 old→S1 new mapping 뒤 elementwise max(S0,S1)이며 exact 최소는 다음이다.

```text
quick: assertRaisesRegex=1
chronology: assertRaises=1 assertRaisesRegex=1
rfc3339: assertRaises=1 assertRaisesRegex=1
trusted-source: assertEqual=2 assertRaisesRegex=1
missing-json: assertRaisesRegex=2
add-only: assertEqual=3 assertFalse=1 assertRaises=2 assertRaisesRegex=1 assert_not_called=1
parent-fsync: assertEqual=1 assertRaisesRegex=2 assertTrue=1
check-read-only: assertEqual=2 assertFalse=3
```

나머지 test method AST는 S1 exact equal이다. Skip/expectedFailure/pass/empty/unconditional assertion은
0이다.

## 6. P4 — runtime 전 dual static source review

두 reviewer는 source/import/execute/compile 없이 P2 backup S0/S1과 live S2 full diff, exact pins,
return/call boundary, namespace, 37-method AST/assertion table와 negative matrix를 독립 검사한다.
시작/종료 S2와 protected inputs가 같아야 한다.

PASS exact:

    status: PASS_FOR_R029_CORRECTED_THREE_SOURCE_PATCH_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority: NONE_FOR_PUBLICATION_UNTIL_RUNTIME_GATES

Finding이면 source/build/test/wrapper 실행과 publication은 0이며 새 revision이다.

## 7. P5 — 37 tests와 one-shot publication

Runtime은 exact `/usr/bin/python3.14`, Python `3.14.4`, SHA
`b8d8288faefdd300201f43fcf00f6f539a27218eeed3a3dff5ab10b9c4c99700`다. Environment는
`env -i PATH=/usr/bin LC_ALL=C.UTF-8 TZ=Asia/Seoul PYTHONHASHSEED=0
PYTHONDONTWRITEBYTECODE=1`, cwd repository root다.

Prebuild exact command:

    /usr/bin/python3.14 -B -m unittest -v tests.test_walksafe_v2_5_control_candidate_20260730

Exit0, exactly37, skipped/failure/error0, final `OK`만 성공이다. Protected pins와 r002 absence를
재검사한 뒤 builder를 정확히 한 번 실행한다.

    /usr/bin/python3.14 -B scripts/build_walksafe_v2_5_control_candidate_20260730.py --root <repo-root>

Exit0과 `publication_result=PUBLISHED_NEW`만 성공이다. Failure/partial/EEXIST/fsync ambiguity는
preserve-and-terminal이다. Final r002는 non-symlink directory와 exact six regular non-symlink
nlink1 files, deterministic rebuild bytes와 zero-authority projection을 가져야 한다.

## 8. P6 — post gates, candidate dual review, handoff

§7 environment에서 순서대로 (1) 같은 37-test, (2) builder `--check --root <repo-root>`,
(3) continuation `--root <repo-root> --mode CANDIDATE`, (4) Goal wrapper 같은 mode를 실행한다.
Expected는 각각 37/skip0/failure0/error0/OK, `mode=CHECK`과
`publication_result=NOT_APPLICABLE`, exact `PASS mode=CANDIDATE` 두 건, exit0이다. Parent는 ordered
argv, exit, stdout/stderr SHA/bytes, counters/status를 기록한다.

두 candidate reviewer는 네 gate를 각각 다시 실행하고 R029/P1, recovery manifest, S0/S1/S2,
P4 reviews와 physical exact-six를 검수한다. Review는 candidate exact object
`{path,entry_count,entry_name_digest_sha256,files}`와 four ordered gate rows exact keys
`role,argv,exit_code,stdout_sha256,stdout_bytes,stderr_sha256,stderr_bytes,tests_run,skipped,
failures,errors,status`, start/end bindings와 authority를 담는다.

PASS exact:

    status: PASS_FOR_R029_NON_EFFECTIVE_V25_CANDIDATE_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority: NONE_FOR_ACTIVATION_GOAL_PRODUCT

Dual PASS 뒤 §4 dirty/index equality, staging absence와 protected pins를 확인한다. Daylog는 §1
preimage exact여야 한다. Parent의 한 `apply_patch`가 raw prefix를 보존하고 suffix 하나만 append하며
suffix는 exact `\n## R029 r002 교정 후보 실행\n\n`으로 시작하고 heading은 exactly once다. Backup,
S2/reviews/candidate/gates와 zero authority를 기록한 뒤 local-memory
backup→log-work→sync를 수행한다.

## 9. 성공 조건과 후속

성공은 dual plan PASS, exact-ten recovery와 S0/S1 round-trip, one S1→S2 patch, dual source PASS,
prebuild37, one `PUBLISHED_NEW`, four post gates, dual candidate PASS, unrelated dirty/index equality,
protected pins와 daylog single append의 conjunction이다. 결과는
`REVIEWED_R029_CORRECTED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`다.

Canonical/checkpoint/Goal/runtime queue/product write, seq2/seq3 application, formal/device/release
credit, commit/push/PR/deploy/기관 제출, paid service/secret use와 failed-r001/user-data 삭제는 0이다.
후속 R030만 activation transaction을 검토할 수 있고 R031 전에는 Goal/product authority가 없다.
