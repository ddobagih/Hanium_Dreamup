# WalkSafe 자율 실행 로드맵 R028 — R027 실패 source 복구·교정과 r002 후보 완결

- 상태: `PRE_REVIEW`
- 작성일: `2026-08-02`
- 결과 범위: R027 failed S1 보존, exact S0 복구, S1→S2 교정, 비효력 v2.5 `r002` exact-six 후보
- 권한: activation·canonical/checkpoint·Goal·제품·배포 권한 없음

## 1. 실패 선행본과 exact 기준선

R016만 accepted predecessor다. R017~R026은 immutable rejected history다. R027 roadmap과
계획 검수는 통과했지만 source 검수에서 중단됐으므로 R027 source/build/test/wrapper 실행,
publication, candidate review와 daylog append는 0이다.

| 역할 | path | SHA-256 | bytes | 판정 |
|---|---|---|---:|---|
| R027 roadmap | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R027.md` | `79544b59b5f3168cc16925f7972ff23cc47c221d83e69b6cdaea751f21005467` | 22,055 | plan PASS, execution failed |
| R027 structural plan review | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R027-independent-structural-review-r001.md` | `eeeb854ae71c0179db85c0806c636eaea6757c51261feac207fb207ffcf468a2` | 3,324 | PASS |
| R027 skeptical plan review | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R027-independent-skeptical-review-r001.md` | `eaa7f3ccde30b77b8208d76b64b512e61b6b7a6b4c8af54a732cf0f0d5b9ba0c` | 6,233 | PASS |
| R027 source structural | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-independent-structural-review-r001.md` | `fed5d269e5be776b4b786e34994e40cfe389ebf297de706938d13ca312ad8b86` | 12,646 | `REJECTED_NEW_ROADMAP_REQUIRED`, B6 |
| R027 source skeptical | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-independent-skeptical-review-r001.md` | `6cf06ede378ffe10ddcc2e9ca679cd77a57f38911faee298457e01e07f04f00a` | 11,381 | `REJECTED_BLOCKING_R027_THREE_SOURCE_PATCH`, B6 M1 |

R027 failed S1은 다음 exact visible evidence다. R028는 이를 live baseline으로 한 번만
S1→S2 update하며 S1을 먼저 외부 recovery bundle에 raw-byte 보존한다.

| 역할 | path | S1 SHA-256 | bytes |
|---|---|---|---:|
| core | `scripts/walksafe_v2_5_candidate_validation.py` | `926f2a997692aff9e996458f3099515fb55bf51ee9171197c4ea27bcdf0b57f1` | 187,211 |
| builder | `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `2960f1b6e49cd2dbd8904de15fe23327eb3b5959efccc68a42be564532c0e96a` | 48,374 |
| test | `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `874c89e04d67ef2c27709e84917ee01a98faa73405b983be2a299abc72a834c5` | 70,163 |

복구돼야 할 exact S0는 다음이다.

| 역할 | S0 SHA-256 | bytes |
|---|---|---:|
| core | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| builder | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| test | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

Unchanged wrapper는 continuation
`1a2dc4f1d8c28c5163cae8306a160e8f942f962ea2ec190a625b64dc688ece5c / 2,513`,
Goal `98d656e8c3c54e122a419eee9d2c3b3285f2594f4abd3abbb5597d8e9470feda / 2,434`다.
C0는 `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c /
1,329,415`, daylog preimage는
`20983d0f481699bee1d708b059a34be85dacccf1dca1f06489e60938f2522d4b /
2,987`이다. R027의 잘못된 archive는 실패 원인으로만 보존하며 S0 oracle로 다시 쓰지 않는다.

## 2. 위협·authority와 전체 write allowlist

한 명의 협조적 local executor가 순차 실행한다. Hostile same-UID concurrent mutation,
kernel/runtime/filesystem compromise와 고의 허위 reviewer는 범위 밖이다. 관측 경계마다 exact
content/type를 다시 검사하고 different-byte, unsafe type 또는 unexpected delta는 terminal이다.

모든 산출물은 `effective=false`, `approved=false`, `applied=false`, `evidence_only=true`이며
activation/canonical/checkpoint/Goal/product write authority는 모두 false다.

| epoch | operation | exact target |
|---|---|---|
| P0 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R028.md` |
| P1 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R028-independent-structural-review-r001.md` |
| P1 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R028-independent-skeptical-review-r001.md` |
| P2 | `ADD_ONLY_FINAL_ROOT_NO_RETRY` | `/home/ddobagi/.codex/backups/walksafe/20260802T1500KST-r027-failed-s1-r028-recovery-r001/` |
| P3 | `UPDATE_EXISTING_EXACT_S1_ONCE` | `scripts/walksafe_v2_5_candidate_validation.py` |
| P3 | `UPDATE_EXISTING_EXACT_S1_ONCE` | `scripts/build_walksafe_v2_5_control_candidate_20260730.py` |
| P3 | `UPDATE_EXISTING_EXACT_S1_ONCE` | `tests/test_walksafe_v2_5_control_candidate_20260730.py` |
| P4 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r028-independent-structural-review-r001.md` |
| P4 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r028-independent-skeptical-review-r001.md` |
| P5 | `CREATE_OWNED_TRANSIENT_THEN_RENAME_OR_TERMINAL` | plan root direct child `.v2-5-control-candidate-r002.staging-<pid>-<16-lowerhex>/` |
| P5 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/` |
| P6 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r028-independent-structural-review-r001.md` |
| P6 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r028-independent-skeptical-review-r001.md` |
| handoff | `APPEND_EXISTING_EXACT_PREFIX_ONCE` | `daylog/2026-08-02.md` |

P2 backup은 final root를 mode `0700`으로 직접 exclusive-create한다. 어떤 member/target도
이미 있거나 중간 실패·fsync ambiguity가 나면 partial을 그대로 보존하고 R028를 terminal로
종료한다. 삭제·overwrite·repair·same-revision retry는 0이다. P5는 R027 §3의 exact owned-staging
규칙만 유지하며 foreign/final target은 어떤 경우에도 삭제·복구하지 않는다.

## 3. P1 — R028 계획 독립 이중 검수

두 reviewer는 이 문서 전체와 §1 physical bindings를 독립 재계산한다. 시작/종료 target
SHA/bytes equality, P2 복구의 독립성, S1→S2 operation kind, no-delete/no-retry, runtime/publication,
daylog와 zero authority를 검토한다.

PASS exact:

    status: PASS_FOR_R028_CORRECTION_AND_NON_EFFECTIVE_R002_EXECUTION_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority_granted: NONE_FOR_ACTIVATION_GOAL_PRODUCT

Finding이면 P2 이후 write와 source/build/test 실행은 0이고 새 revision이다.

## 4. P2 — dirty baseline, transcript 역적용, self-contained recovery bundle

P1 dual PASS 뒤 r002 final/staging, P4/P6 review와 external backup root가 absent인지 확인한다.
R027 전 dirty baseline은 aggregate
`a70a748a8868d7d62288589ad579107a2510753f3bf3770a6446944529b15810 / 80,310 rows`,
Git index `85176c651456950caaa68d266ee313178a90773514dea18bee516a34970aab2d /
151,776 bytes`였다는 history로만 보존한다. R028는 P1 뒤 새 NUL-safe repository content/index
baseline을 메모리에 계산한다. `.git`을 제외하고 source 세 개, P4/P5/P6 targets와 daylog만
제외한다. R028 roadmap/P1, R027 source reviews와 모든 unrelated dirty content는 포함한다.
Regular/symlink/directory/other type, permission, bytes/hash 또는 raw link target/rdev를 byte-sort
NUL row로 결속하고 종료 시 exact aggregate/row-count/index equality를 요구한다. Staging은
제외하지 않으므로 잔존하면 equality가 실패한다.

Transcript source는 다음 관측 binding이다.

```text
path=/home/ddobagi/.codex/sessions/2026/08/02/rollout-2026-08-02T11-46-26-019fc05d-96c2-77b3-98bf-c4958a9f3748.jsonl
sha256=680254dab32c326efe577f22d138ce20b144bdc686160bb81f3ebafb7630db95
bytes=1588479
call_id=call_T6mhZGVW6Me8eOweu8FcGhbU
```

Strict JSONL parse로 `payload.type=custom_tool_call`, `name=exec`, 위 call_id인 record가 정확히
하나이고 대응 `custom_tool_call_output`도 하나인지 확인한다. Call raw line은 terminal LF 포함
`2b558124890039109290ec38c32e15f89364869c933be486c0277358acf759df / 21,438`, output raw line은
`3a5f13914c081850be210f2137a3ac47eccb4b3b23ec27e0a4329633e454fa40 / 423`이다.

Call input은 exact prefix `const patch = ` 뒤 JSON string 한 개와 exact suffix
`;&#10;const result = await tools.apply_patch(patch);&#10;text(result);`만 허용한다. JSON string을
decode한 UTF-8 patch는
`d9c9071961bf621bd578f776a4d5498e28496c7364841e595c8e3f841255efe1 /
18,972 bytes / 395 splitlines / 394 LF`이고 `*** Begin Patch`부터 `*** End Patch`까지 exact 세
`Update File` section만 갖는다.

역적용은 write 없이 메모리에서 한다. 각 section/hunk를 순서대로 parse하고 context와 forward
`+` line으로 이루어진 postimage를 현재 S1 line stream의 monotone cursor 뒤 유일 위치에서 찾아
context와 forward `-` line preimage로 교체한다. Ambiguous/missing match는 terminal이다. 복원
세 bytes가 §1 S0 세 pin과 exact 일치해야 한다. 이어 같은 hunk를 정방향으로 메모리 적용해
S0→S1 round-trip bytes도 exact 일치시킨다. Hash 일치만으로 raw bytes나 round-trip을 생략하지
않는다.

그 뒤 session file에 더 이상 의존하지 않는 exact backup을 만든다. Root 내부 regular-file
tree는 다음 열 개뿐이다.

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

첫 세 파일은 위 raw call/output/decoded patch bytes, failed-s1과 recovered-s0는 §1 exact bytes다.
Manifest는 `manifest_version=1`, artifact ID
`WS-V25-R028-R027-FAILED-S1-S0-RECOVERY-R001`, observed `created_at`, source session
path/SHA/bytes/call_id, patch SHA/bytes/lines, `reconstruction=ORDERED_HUNK_INVERSE_AND_FORWARD_ROUND_TRIP`,
S1/S0 source bindings, authority false fields와 manifest 자신을 제외한 아홉 file rows를 담는다.
Rows는 relative path byte-sort, 각 exact `{path,sha256,bytes}`다. Serialization은 UTF-8
`json.dumps(ensure_ascii=False,sort_keys=True,separators=(",",":")) + "\n"`이다.

Directories는 mode `0700`, files는 `O_CREAT|O_EXCL|O_NOFOLLOW` mode `0600`; file fsync 후
leaf-to-root directory fsync와 backup parent fsync를 한다. 성공 뒤 exact tree/type/nlink1/content,
manifest projection을 다시 읽어 검증하고 manifest SHA/bytes를 메모리에 봉인한다. 이후 S0 비교
oracle은 이 backup의 recovered-s0 raw files뿐이며 mutable session은 provenance로만 남는다.

## 5. P3 — live S1→S2 단일 최소 교정

Backup 성공과 S1 재확인 뒤 한 `apply_patch` call이 exact 세 source만 update한다. Live S0 복원,
중간 S1→S0 write, wrapper/helper 수정과 두 번째 source patch는 0이다. 새 observed nonfuture
Asia/Seoul microsecond timestamp를 `R028_PREPARATION_STARTED_AT`과 physical `PREPARED_AT`으로
봉인한다.

R002 namespace는 bundle/gate/event suffix `002`, package/transaction/final/history/checkpoint/
manifest IDs `R002`를 유지한다. Current delegation은
`WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R028-R002`, check contract는
`2026-08-02.3`이다. Exact user directive는 계속 UTF-8 840 bytes,
`8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a`다.
Test-only valid times는 새 base에서만 +5m/+10m/+15m/+15m30s/+16m/+26m 및 seq3 +1µs로
derive하고 old literal을 재사용하지 않는다.

S2는 R027 findings를 다음 최소 범위로 모두 닫는다.

1. R017~R027 rejected/failed physical roadmap trios와 R028 current roadmap/P1 trio, R027 failed
   source review 둘을 actual SHA/bytes로 pin한다. R017~R026 physical SHA는 R027 structural
   B-02 표, failed-r001 exact-six/name digest/test binding은 같은 review의 actual 표만 사용한다.
   Existing candidate-review binding constants는 P4의 두 fresh R028 source-review exact path를
   가리키며 runtime에서 physical SHA/bytes를 동적으로 결속한다. Candidate 생성 뒤에만 생기는
   P6 두 review는 code/output binding에 포함하지 않는다.
2. Exact `validate_failed_r001_bundle(root)` 하나로 이름을 통일한다. Construction entry와 output
   construction 직후, rename 직전, successful parent fsync 직후, check entry/exit에서 physical
   failed-r001 exact-six/type/content/seq1/zero-authority를 재검증한다.
3. `load_source_state()`는 완성된 bindings dict를 정확히 한 번 반환한다.
   `authorization_subject_bindings()`도 dict 구성→R017~R028 rows 추가→정확히 한 번 반환하며
   unreachable loop나 undefined `bindings`가 없다.
4. Activation envelope fixed checkpoint row는 `ACTIVE_CHECKPOINT_ID` R002 상수에서 derive한다.
   Current construction의 `candidate-r001`, 비허용 `R001`, gate/event `001`은 0이다.
5. Publication unit fixture는 immutable failed-r001 exact-six raw bytes를 temp root에 복사하고
   foreign target은 pre/post exact snapshot으로 비교한다. Existing directory/file/symlink,
   partial write, rename EEXIST race와 parent-fsync ambiguity가 target overwrite/delete/recovery 없이
   terminal인지 각각 검사한다.
6. Failed-r001 pristine positive와 mutation, extra, member symlink, unsafe root negative; pristine
   source state/authorization key-set positive와 각 provenance mutation negative를 분리한다.
   Unrelated bad pin이 expected failure를 가리는 경우는 허용하지 않는다.

Test method names는 S1의 exact 37개와 같고 rename은 0이다. 변경 허용 body는 다음 여덟 개뿐이다.

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

Non-test fixture helper 추가만 허용한다. 나머지 test method AST는 S1과 exact equal이다. 각 변경
method의 unittest assertion-call kind count는 recovered S0와 S1 어느 쪽보다도 감소하지 않는다.
Skip/expectedFailure/pass/empty body/unconditional assertion은 0이다.

## 6. P4 — 실행 전 fresh dual static source review

두 reviewer는 source/test/builder를 import·execute·compile하지 않고 external backup의 S0/S1,
live S2 full three-file diff를 독립 비교한다. Backup exact tree/manifest, R028/P1 pins, wrappers,
실제 R017~R028와 failed-r001 equality, function return/call boundary, namespace, 37-method AST,
assertion counts와 negative matrix를 검사한다. 시작/종료 S2와 protected inputs가 같아야 한다.

PASS exact:

    status: PASS_FOR_R028_CORRECTED_THREE_SOURCE_PATCH_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority: NONE_FOR_PUBLICATION_UNTIL_RUNTIME_GATES

Finding이면 source/build/test/wrapper 실행과 publication은 0이다. S2와 backup은 visible failed
evidence로 보존하고 새 revision으로 간다.

## 7. P5 — 37-test prebuild와 one-shot r002 publication

Runtime은 exact `/usr/bin/python3.14` Python `3.14.4`, binary SHA
`b8d8288faefdd300201f43fcf00f6f539a27218eeed3a3dff5ab10b9c4c99700`이다. Environment는
`env -i PATH=/usr/bin LC_ALL=C.UTF-8 TZ=Asia/Seoul PYTHONHASHSEED=0
PYTHONDONTWRITEBYTECODE=1`, cwd는 repository root다.

Prebuild는 다음 한 명령만 실행한다.

    /usr/bin/python3.14 -B -m unittest -v tests.test_walksafe_v2_5_control_candidate_20260730

Exit 0, exactly 37, skipped/failure/error 0, final `OK`가 아니면 terminal이다. S2, P4 reviews,
backup, wrappers, C0, R016/pair, failed-r001와 r002 absence를 재검사한 뒤 builder를 정확히 한 번
실행한다.

    /usr/bin/python3.14 -B scripts/build_walksafe_v2_5_control_candidate_20260730.py --root <repo-root>

성공은 exit 0과 `publication_result=PUBLISHED_NEW`뿐이다. Existing/partial/EEXIST/fsync ambiguity는
preserve-and-terminal이며 delete/repair/resume/retry가 없다. Final r002는 non-symlink directory,
exact six regular non-symlink nlink1 files이고 basename은 R027 §8의 exact six다. Physical bytes는
deterministic rebuild와 같고 모든 output projection은 zero-authority다.

## 8. P6 — post gates, candidate dual review, handoff

§7 environment에서 다음을 순서대로 실행한다.

1. 같은 37-test command;
2. builder `--check --root <repo-root>`;
3. continuation wrapper `--root <repo-root> --mode CANDIDATE`;
4. Goal wrapper `--root <repo-root> --mode CANDIDATE`.

각각 37/skip0/failure0/error0/OK, `mode=CHECK`와
`publication_result=NOT_APPLICABLE`, exact `PASS mode=CANDIDATE` 두 건, 모두 exit 0이어야 한다.
Parent는 ordered argv, exit, stdout/stderr SHA/bytes, test counters/status를 기록한다.

두 candidate reviewer는 독립적으로 네 read-only gate를 다시 실행하고 R028/P1, recovery
bundle, S0/S1/S2, P4 reviews와 physical exact-six를 검토한다. Review에는 candidate exact object
`{path,entry_count,entry_name_digest_sha256,files}`와 four ordered gate rows exact keys
`role,argv,exit_code,stdout_sha256,stdout_bytes,stderr_sha256,stderr_bytes,tests_run,skipped,
failures,errors,status`, start/end bindings와 authority boundary가 있어야 한다.

PASS exact:

    status: PASS_FOR_R028_NON_EFFECTIVE_V25_CANDIDATE_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority: NONE_FOR_ACTIVATION_GOAL_PRODUCT

Dual PASS 뒤 §4 dirty baseline/index를 recompute해 exact equality, staging absence와 모든 protected
pin을 확인한다. Daylog는 아직 §1 preimage exact 2,987 bytes여야 한다. Parent의 한
`apply_patch`가 raw prefix를 보존한 채 terminal-LF suffix 하나만 append하며 suffix는 exact byte
sequence `\n## R028 r002 교정 후보 실행\n\n`으로 시작한다. Heading은 exactly once다.
Backup/S2/reviews/candidate/gates와 zero authority를
기록하고 local-memory backup→log-work→sync를 수행한다.

## 9. 성공 조건, 금지 범위와 후속

성공은 dual plan PASS, self-contained recovery backup, exact S0 round-trip, one S1→S2 patch,
dual source PASS, prebuild 37 PASS, one `PUBLISHED_NEW`, four post gates PASS, dual candidate PASS,
unrelated dirty equality, protected pins와 daylog exact-prefix single append의 conjunction이다.

결과는 `REVIEWED_R028_CORRECTED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`다.
Canonical/checkpoint/Goal/runtime queue/product write, seq2/seq3 application, formal/device/release
credit, commit/push/PR/deploy/기관 제출, paid service/secret use와 failed-r001·user data 삭제는 0이다.

후속 R029만 activation transaction을 검토할 수 있다. R029는 이 R028/P1, recovery manifest,
S2, P4, exact-six와 P6 두 review를 각각 exact pin하고 네 gate를 새로 실행해야 한다. R029 전에는
canonical/checkpoint, 그 이후 별도 successor 전에는 Goal/product write authority가 없다.
