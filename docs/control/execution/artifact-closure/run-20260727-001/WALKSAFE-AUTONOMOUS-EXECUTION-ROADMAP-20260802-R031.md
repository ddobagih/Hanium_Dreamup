# WalkSafe 자율 실행 로드맵 R031 — R030 terminal S2 보존과 결정적 2파일 복구

- 상태: `PRE_REVIEW`
- 작성일: `2026-08-02`
- 결과 범위: R030 failed S2 보존, core+test 단일 교정, 비효력 v2.5 `r002` exact-six 후보
- 권한: activation·canonical/checkpoint·Goal·제품·배포 권한 없음

## 1. 선행 상태와 R030 terminal 사실

R016만 accepted predecessor다. R017~R026은 rejected history, R027은 failed source,
R028·R029는 rejected plan history다. R030 roadmap과 두 P1 review는 다음 exact identity로
실행을 허용했다.

| 역할 | SHA-256 | bytes | 판정 |
|---|---|---:|---|
| R030 roadmap | `f3a4391751b61fe488c5f2b2a6d052f611194e4e0a145f495e4e451e250ceb50` | 22,387 | reviewed |
| R030 structural review | `f963ff7a34080c76cd1808be93b2f5dc4690238ffa4b9e322c7da2547e8fa4d6` | 6,546 | PASS 0/0/0 |
| R030 skeptical review | `56d7192f23348fb223d8cc06205f848d814efc552f244dc3e9a957549f3c9ce0` | 6,474 | PASS 0/0/0 |

R030 P2 recovery root는
`/home/ddobagi/.codex/backups/walksafe/20260802T1520KST-r027-failed-s1-r030-recovery-r001`
이며 manifest는
`977b64191f52d73cb7cf22d02446a18998512c1c63fc9a01730d33a5c01fac23 / 3,588`다.
Root+exact seven descendant directories는 `0700`, exact ten files는 regular `0600`,
`nlink=1`이고 S0/S1 25/13/10 inverse/round-trip이 봉인돼 있다.

R030 P3는 `2026-08-02T15:55:25.460218+09:00`을 관찰한 뒤 정확히 한 번의
`apply_patch`로 세 파일을 바꿨다. 그 직후 source import·compile·test·build·wrapper를 실행하지
않고 `ast.parse`만 수행했으며 test의 line 1653 column 17 중복 `with` 때문에 terminal 실패했다.

| 역할 | failed S2 SHA-256 | bytes | AST |
|---|---|---:|---|
| core | `f6c136b5da635c2d03b74d4d988c26a2fdc6205c07eca0849f20331a9eb72e00` | 198,304 | PASS |
| builder | `ebf06f12e409a9b14fc168dd9892da4bde02632ad6b403b2cc3102a2919d8ed6` | 52,856 | PASS |
| test | `145dff335eea3b1c55c5c7146e0f9f5bbbd378ae01567bb17941cfd701b4710c` | 79,420 | FAIL 1653:17 |

R030에서 두 번째 source patch, P4 review, source import/compile/test/build/wrapper 실행, candidate
publication, P6 review와 daylog append는 모두 0이다. failed S2는 R031 input으로 그대로 보존한다.

세 독립 read-only audit는 test의 exact byte offset 62,722에서 ASCII `with ` 5 bytes만 삭제한
postimage가
`fb3fc7aef1e0175fc1ead259fe080d92d52eec2992ddc287e78c3456fcf2bbb8 / 79,415`이고,
AST 37개·변경 body exact 8·새 helper exact 2·assertion 하한을 만족함을 확인했다. 동시에
core에서 다음 네 successor 교정 범주가 필요함을 찾았다.

1. R027 failed skeptical physical SHA는
   `6cf06ede378ffe10ddcc2e9ca679cd77a57f38911faee298457e01e07f04f00a / 11,381`인데
   S2 pin은 잘못 조합된 63-character 값이다.
2. `product_inventory_contract()`가 dict를 `bindings`에 두고 return하지 않는다.
3. `authorization_subject_bindings()`가 literal dict를 조기 return해 R017~R030 loop와
   `return bindings`가 unreachable이다.
4. R030 terminal 뒤의 교정은 R031 roadmap/P1 trio, delegation, observed time과 fresh
   `source-r031` P4 reviews를 core에 직접 결속해야 한다.

## 2. authority와 exact write allowlist

모든 산출물은 `effective=false`, `approved=false`, `applied=false`, `evidence_only=true`다.
Hostile same-UID concurrent mutation과 kernel/runtime/filesystem compromise는 범위 밖이다.

| epoch | operation | exact target |
|---|---|---|
| P0 | `ADD_ONLY_ABSENT_OR_TERMINAL` | 이 R031 roadmap |
| P1 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R031-independent-structural-review-r001.md` |
| P1 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R031-independent-skeptical-review-r001.md` |
| P2 | `ADD_ONLY_FINAL_ROOT_NO_RETRY` | `/home/ddobagi/.codex/backups/walksafe/20260802T1602KST-r030-failed-s2-r031-recovery-r001/` |
| P3 | `UPDATE_EXISTING_EXACT_S2_ONCE` | `scripts/walksafe_v2_5_candidate_validation.py` |
| P3 | `UPDATE_EXISTING_EXACT_S2_ONCE` | `tests/test_walksafe_v2_5_control_candidate_20260730.py` |
| P4 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r031-independent-structural-review-r001.md` |
| P4 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r031-independent-skeptical-review-r001.md` |
| P5 | `CREATE_OWNED_TRANSIENT_THEN_RENAME_OR_TERMINAL` | plan-root child `.v2-5-control-candidate-r002.staging-<pid>-<16-lowerhex>/` |
| P5 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/` |
| P6 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r031-independent-structural-review-r001.md` |
| P6 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r031-independent-skeptical-review-r001.md` |
| handoff | `APPEND_EXISTING_EXACT_PREFIX_ONCE` | `daylog/2026-08-02.md` |

Builder와 두 wrapper, canonical/checkpoint/Goal/product, R030 P4/P6 absent paths, failed-r001,
R030 P2 recovery와 사용자 dirty files는 write 대상이 아니다. P2/P5 preexisting target,
intermediate failure와 fsync ambiguity는 preserve-and-terminal이며 delete·overwrite·repair·same-revision
retry는 0이다. P5 own pre-rename staging만 failure cleanup할 수 있다.

## 3. P1 — R031 plan dual review

두 reviewer는 전체 문서, R030 terminal facts, exact S2 pins, 네 core 교정 범주, R031 lineage,
P2 self-contained postimage, dirty exclusions, single patch와 P4/P5/P6 ordering을 독립 검산한다.
시작/종료 R031 SHA/bytes가 같아야 한다.

PASS exact:

    status: PASS_FOR_R031_R030_TERMINAL_RECOVERY_EXECUTION_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority_granted: NONE_FOR_ACTIVATION_GOAL_PRODUCT

Finding이면 P2 이후 write와 source/build/test 실행은 0이며 새 revision이다.

## 4. P2 — dirty baseline과 self-contained failed-S2/postimage recovery

P1 dual PASS 뒤 final/staging, P4/P6 reviews와 P2 root가 absent인지 확인한다. Repository dirty
manifest는 R030 `R030_NULSAFE_NOFOLLOW_V1`과 같은 nofollow byte-sort NUL-row 알고리즘을 사용한다.
Exclusion은 다음 exact set/prefix뿐이다.

```text
scripts/walksafe_v2_5_candidate_validation.py
tests/test_walksafe_v2_5_control_candidate_20260730.py
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r031-independent-structural-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r031-independent-skeptical-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r031-independent-structural-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r031-independent-skeptical-review-r001.md
daylog/2026-08-02.md
```

Final-root는 exact directory row와 descendant prefix의 두 predicate로만 제외한다. Leading-dot
staging은 제외하지 않는다. Regular/symlink/directory/other row와 별도 Git index row는 R030 §4와
같다. P2 전 aggregate SHA, tree/index row count와 index SHA/bytes를 메모리에 보존하고 P6 뒤 exact
equality와 staging absence를 요구한다. Builder가 baseline에 포함되므로 1-byte drift도 차단한다.

Dual PASS 뒤 실제 nonfuture Asia/Seoul microsecond를
`R031_PREPARATION_STARTED_AT`으로 한 번 관찰한다. R031 roadmap과 두 P1 review의 actual
SHA/bytes, 이 시각과 exact 네 교정 범주를 사용해 live write 없이 core/test postimage를 메모리에서
계산한다. Required postimage는 다음이다.

- core: `ast.parse` PASS, exact hash/bytes는 P2 manifest가 봉인
- builder: failed S2와 raw-equal
  `ebf06f12e409a9b14fc168dd9892da4bde02632ad6b403b2cc3102a2919d8ed6 / 52,856`
- test: `fb3fc7aef1e0175fc1ead259fe080d92d52eec2992ddc287e78c3456fcf2bbb8 / 79,415`, `ast.parse` PASS

P2 root는 exclusive `0700`; exact descendant directories는 `failed-s2`, `failed-s2/scripts`,
`failed-s2/tests`, `proposed-s3`, `proposed-s3/scripts`, `proposed-s3/tests` 여섯 개이고 모두
exclusive `0700`이다. Exact eight regular `0600`, `nlink=1` files는 다음이다.

```text
failed-s2/scripts/walksafe_v2_5_candidate_validation.py
failed-s2/scripts/build_walksafe_v2_5_control_candidate_20260730.py
failed-s2/tests/test_walksafe_v2_5_control_candidate_20260730.py
proposed-s3/scripts/walksafe_v2_5_candidate_validation.py
proposed-s3/scripts/build_walksafe_v2_5_control_candidate_20260730.py
proposed-s3/tests/test_walksafe_v2_5_control_candidate_20260730.py
correction-map.json
recovery-manifest.json
```

`correction-map.json`은 schema 1, R031 trio, observed time, exact S2/S3 rows와 ordered edit
records를 담는다. Manifest는 artifact ID
`WS-V25-R031-R030-FAILED-S2-S3-RECOVERY-R001`, authority false fields, self 제외 seven file rows와
exact directory set을 담는다. 두 JSON은 UTF-8 sorted compact terminal LF다. 각 file fsync,
leaf-to-root directory fsync와 backup parent fsync 뒤 exact metadata/content/postimage를 재검사한다.
Partial/ambiguous failure는 root를 보존하고 R031을 종료한다.

## 5. P3 — exact failed S2→S3 one patch

P2 성공과 live S2 재확인 뒤 한 `apply_patch` call만 core와 test를 update한다. Builder, wrapper와
다른 file update, second patch는 0이다. Exact semantic edits는 다음뿐이다.

1. R027 failed skeptical pin을 §1 physical 64-character SHA로 교정한다.
2. `product_inventory_contract()`는 dict를 직접 return하고 exact one reachable return을 갖는다.
3. `authorization_subject_bindings()`는 initial dict를 `bindings`에 할당하고 R017~R031 physical
   rows를 추가한 뒤 exact one `return bindings`를 갖는다.
4. `ROADMAP_PROVENANCE_TRIOS`에 R031 roadmap/P1 actual trio를 current successor로 추가하고
   authorization loop를 `range(17, 32)`로 한다. R017~R026/R028/R029 rejected, R027/R030 failed,
   R031 current semantics를 static review에서 직접 검증한다.
5. Candidate P4 paths는 fresh `source-r031` 두 path, delegation은
   `WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R031-R002`, check contract는
   `2026-08-02.6`으로 바꾼다.
6. `R031_PREPARATION_STARTED_AT`과 `PREPARED_AT`은 P2 observed time이다. PREPARED_ON과
   +5m/+10m/+15m/+15m30s/+16m/+26m, seq3 checked+1us는 이 base에서만 derive한다.
7. `__all__`은 R031 observed-time symbol을 export하며 current R030 symbol은 남기지 않는다.
8. Test는 byte offset 62,722의 exact ASCII `with ` 5 bytes 하나만 삭제한다.

Patch 뒤 live core/builder/test는 P2 `proposed-s3` raw bytes와 각각 exact-equal이어야 한다.
Core·builder·test `ast.parse`, all 61 trusted physical pins, return/call boundaries, R031 namespace,
review paths, timestamp derivation과 builder physical publication boundary를 정적으로 검사한다.
Test는 S1 대비 exact 37 names, changed body exact 8, new non-test class method exact
`_copy_failed_r001_bundle`/`_snapshot_path`, assertion lower bounds, skip/expectedFailure/pass/empty/
unconditional assertion 0이어야 한다. Source import/compile/test/build/wrapper는 아직 0이다.

## 6. P4 — runtime 전 dual static source review

두 reviewer는 source/import/execute/compile 없이 R030 P2 S0/S1, R031 P2 failed S2/proposed S3와
live S3 full diff, all pins, return/call boundary, namespace/lineage, 37-method AST/assertion table와
negative matrix를 독립 검사한다. 시작/종료 S3와 protected inputs가 같아야 한다.

PASS exact:

    status: PASS_FOR_R031_CORRECTED_R030_THREE_SOURCE_SET_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority: NONE_FOR_PUBLICATION_UNTIL_RUNTIME_GATES

Finding이면 source/build/test/wrapper 실행과 publication은 0이며 새 revision이다.

## 7. P5 — exact 37 tests와 one-shot publication

Runtime은 exact `/usr/bin/python3.14`, Python `3.14.4`, SHA
`b8d8288faefdd300201f43fcf00f6f539a27218eeed3a3dff5ab10b9c4c99700`다. Environment는
`env -i PATH=/usr/bin LC_ALL=C.UTF-8 TZ=Asia/Seoul PYTHONHASHSEED=0
PYTHONDONTWRITEBYTECODE=1`, cwd repository root다.

Prebuild exact command:

    /usr/bin/python3.14 -B -m unittest -v tests.test_walksafe_v2_5_control_candidate_20260730

Exit0, exactly37, skipped/failure/error0와 final `OK`만 성공이다. Protected pins와 final/staging
absence를 재검사한 뒤 builder를 정확히 한 번 실행한다.

    /usr/bin/python3.14 -B scripts/build_walksafe_v2_5_control_candidate_20260730.py --root <repo-root>

Exit0과 `publication_result=PUBLISHED_NEW`만 성공이다. Failure/partial/EEXIST/fsync ambiguity는
preserve-and-terminal이다. Final은 non-symlink directory와 exact six regular non-symlink nlink1
files, deterministic rebuild bytes와 zero-authority projection을 가져야 한다.

## 8. P6 — post gates, candidate dual review와 handoff

§7 environment에서 순서대로 같은 37-test, builder `--check --root <repo-root>`, continuation
`--root <repo-root> --mode CANDIDATE`, Goal wrapper 같은 mode를 실행한다. Expected는 각각
37/skip0/failure0/error0/OK, `mode=CHECK`과 `publication_result=NOT_APPLICABLE`, exact
`PASS mode=CANDIDATE` 두 건, exit0이다. Parent는 ordered argv, exit, stdout/stderr SHA/bytes와
counters/status를 기록한다.

두 candidate reviewer는 네 gate를 각각 다시 실행하고 R031/P1, 두 recovery manifest,
S0/S1/S2/S3, P4 reviews와 physical exact-six를 검수한다. Review는 candidate exact object와 four
ordered gate rows를 R030 §8과 같은 exact schema로 담는다.

PASS exact:

    status: PASS_FOR_R031_RECOVERED_R030_NON_EFFECTIVE_V25_CANDIDATE_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority: NONE_FOR_ACTIVATION_GOAL_PRODUCT

Dual PASS 뒤 §4 dirty/index equality, staging absence, protected pins, 두 recovery root 불변을
확인한다. Daylog preimage는 R030 시작 전 값
`20983d0f481699bee1d708b059a34be85dacccf1dca1f06489e60938f2522d4b / 2,987` 그대로여야 한다.
Parent의 한 `apply_patch`가 raw prefix를 보존하고 suffix 하나만 append하며 suffix는 exact
`\n## R031 R030 교정 후보 복구 실행\n\n`으로 시작하고 heading은 exactly once다. R030 terminal,
R031 recovery, gates/reviews/candidate와 zero authority를 기록한 뒤 local-memory
backup→log-work→sync를 수행한다.

## 9. 성공 조건과 후속

성공은 dual plan PASS, exact-eight S2/S3 recovery, one core+test patch, dual source PASS,
prebuild37, one `PUBLISHED_NEW`, four post gates, dual candidate PASS, dirty/index equality,
protected pins와 daylog single append의 conjunction이다. 결과는
`REVIEWED_R031_RECOVERED_R030_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`다.

Canonical/checkpoint/Goal/runtime queue/product write, seq2/seq3 application, formal/device/release
credit, commit/push/PR/deploy/기관 제출, paid service/secret use와 failed-r001/user-data 삭제는 0이다.
후속 R032만 activation transaction을 검토할 수 있고 R033 전에는 Goal/product authority가 없다.
