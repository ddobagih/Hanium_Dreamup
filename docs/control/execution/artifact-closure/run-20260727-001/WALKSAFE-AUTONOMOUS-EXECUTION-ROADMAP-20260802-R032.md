# WalkSafe 자율 실행 로드맵 R032 — R031 P4 provenance oracle terminal 복구

- 상태: `PRE_REVIEW`
- 작성일: `2026-08-02`
- 결과 범위: R031 failed S3 보존, core lineage+test oracle 단일 교정, 비효력 v2.5 `r002` exact-six 후보
- 권한: activation·canonical/checkpoint·Goal·제품·배포 권한 없음

## 1. R031 terminal 사실과 R032 원인

R016만 accepted predecessor다. R017~R026은 rejected, R027은 failed source,
R028·R029는 rejected plan, R030은 failed S2, R031은 failed S3 history다. R031 roadmap/P1
trio는 다음 physical identity로 P2/P3/P4까지의 제한 실행을 허용했다.

| 역할 | SHA-256 | bytes | 판정 |
|---|---|---:|---|
| R031 roadmap | `43f9d3cb119d3638ea743e6e5e5371d1cfa0aa50df817e7850fa449519405ea2` | 15,468 | reviewed |
| R031 structural plan review | `9eb38d17a94dd75bb1a43004bf15e46f53439cc8f6391543477084f04380ccfc` | 7,026 | PASS 0/0/0 |
| R031 skeptical plan review | `1516b6a0253eee6b73f8827ba0e9278a16f35523c88efc82d339a17eac3640af` | 8,763 | PASS 0/0/0 |

R031 recovery root는
`/home/ddobagi/.codex/backups/walksafe/20260802T1602KST-r030-failed-s2-r031-recovery-r001`다.
Manifest는 `cc17f0517edbc575a063dc1b2164163c72a23beaaa0aa9183264cf8320eee252 / 3,597`,
correction map은 `1d3065a2d6f2a64ff91e4dd203698a1f4364a06e3b9edbf28956706202aab7e5 / 4,518`,
observed time은 `2026-08-02T16:39:56.389561+09:00`이다. Exact seven directories는 `0700`,
exact eight files는 regular `0600`, `nlink=1`이고 failed S2와 proposed S3를 self-contained하게
봉인한다.

R031 P3는 core+test 한 `apply_patch` call만 성공했고 builder write는 0이었다. Terminal live S3는
다음과 같고 recovery proposed S3와 raw-equal이다.

| 역할 | failed S3 SHA-256 | bytes | AST |
|---|---|---:|---|
| core | `9aa3e5bc81a67a6186c07a382e29f3244223d33971b5700d9dbd91aecab74f3c` | 199,008 | PASS |
| builder | `ebf06f12e409a9b14fc168dd9892da4bde02632ad6b403b2cc3102a2919d8ed6` | 52,856 | PASS |
| test | `fb3fc7aef1e0175fc1ead259fe080d92d52eec2992ddc287e78c3456fcf2bbb8` | 79,415 | PASS |

P4 structural source review는
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r031-independent-structural-review-r001.md`
에 `be589c27adf30468e3c6941a0a13ee7d682bafe160050a910e5b78219e4f21a0 / 10,696`으로
PASS를 기록했다. 그러나 독립 skeptical source review는 다음 blocking을 찾아 PASS 파일을 쓰지
않았고 exact skeptical path는 absent다.

`test_trusted_source_cas_rejects_coordinated_manifest_attack()`의
`role.startswith(R017_..R031_)` 선택은 의도한 roadmap trio 45개와 R027 failed-source 2개뿐 아니라
R021 gap/backlog와 R022 design/review 4개까지 총 51개를 선택한다. Authorization path binding에는
앞의 47개만 있고 뒤 4개는 없으므로 `provenance_paths <= binding_paths`는 정적으로 false다.
따라서 R031 dual P4 PASS conjunction은 성립하지 않고 37-test gate는 실행할 수 없다.

R031에서 source import·compile·test·builder·wrapper 실행, candidate publication, P6 review와 daylog
append는 모두 0이다. Final `v2-5-control-candidate-r002`, staging, source-r031 skeptical review와
R031 P6 두 path는 absent다. R031 structural PASS는 immutable history지만 R031 실행 권한을 만들지
않는다.

## 2. authority와 exact write allowlist

모든 산출물은 `effective=false`, `approved=false`, `applied=false`, `evidence_only=true`다.
Hostile same-UID concurrent mutation과 kernel/runtime/filesystem compromise는 범위 밖이다.

| epoch | operation | exact target |
|---|---|---|
| P0 | `ADD_ONLY_ABSENT_OR_TERMINAL` | 이 R032 roadmap |
| P1 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R032-independent-structural-review-r001.md` |
| P1 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R032-independent-skeptical-review-r001.md` |
| P2 | `ADD_ONLY_FINAL_ROOT_NO_RETRY` | `/home/ddobagi/.codex/backups/walksafe/20260802T1705KST-r031-failed-s3-r032-recovery-r001/` |
| P3 | `UPDATE_EXISTING_EXACT_S3_ONCE` | `scripts/walksafe_v2_5_candidate_validation.py` |
| P3 | `UPDATE_EXISTING_EXACT_S3_ONCE` | `tests/test_walksafe_v2_5_control_candidate_20260730.py` |
| P4 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r032-independent-structural-review-r001.md` |
| P4 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r032-independent-skeptical-review-r001.md` |
| P5 | `CREATE_OWNED_TRANSIENT_THEN_RENAME_OR_TERMINAL` | plan-root child `.v2-5-control-candidate-r002.staging-<pid>-<16-lowerhex>/` |
| P5 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/` |
| P6 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r032-independent-structural-review-r001.md` |
| P6 | `ADD_ONLY_ABSENT_OR_TERMINAL` | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r032-independent-skeptical-review-r001.md` |
| handoff | `APPEND_EXISTING_EXACT_PREFIX_ONCE` | `daylog/2026-08-02.md` |

Builder, 두 wrapper, canonical/checkpoint/Goal/product, R030/R031 recovery, R031 P4/P6 paths,
failed-r001과 사용자 dirty files는 write 대상이 아니다. P2/P5 preexisting target, intermediate
failure와 fsync ambiguity는 preserve-and-terminal이며 delete·overwrite·repair·same-revision retry는
0이다. P5 own pre-rename staging만 failure cleanup할 수 있다.

## 3. P1 — R032 plan dual review

두 reviewer는 전체 문서, R031 terminal S3와 P4 asymmetric result, exact 51/47/4 blocker,
test의 canonical path-product 교정, R032 lineage, P2 postimage, dirty exclusions, single patch와
P4/P5/P6 ordering을 독립 검산한다. 시작/종료 R032 SHA/bytes가 같아야 한다.

PASS exact:

    status: PASS_FOR_R032_R031_P4_TERMINAL_RECOVERY_EXECUTION_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority_granted: NONE_FOR_ACTIVATION_GOAL_PRODUCT

Finding이면 P2 이후 write와 source/build/test 실행은 0이며 새 revision이다.

## 4. P2 — dirty baseline과 failed-S3/proposed-S4 recovery

P1 dual PASS 뒤 final/staging, P4/P6 reviews와 P2 root가 absent인지 확인한다. Repository dirty
manifest는 nofollow byte-sort NUL-row `R032_NULSAFE_NOFOLLOW_V1`을 사용한다. Exclusion은 다음 exact
set/prefix뿐이다.

```text
scripts/walksafe_v2_5_candidate_validation.py
tests/test_walksafe_v2_5_control_candidate_20260730.py
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r032-independent-structural-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-source-r032-independent-skeptical-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r032-independent-structural-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002-r032-independent-skeptical-review-r001.md
daylog/2026-08-02.md
```

Final-root는 exact directory row와 descendant prefix로만 제외하고 leading-dot staging은 포함한다.
Regular/symlink/directory/other rows와 Git index row를 분리해 NUL 결속한다. P2 전 aggregate SHA,
tree/index row count, index SHA/bytes를 메모리에 보존하고 P6 뒤 exact equality를 요구한다. Builder와
R031 structural P4 file은 baseline에 포함되므로 1-byte drift도 차단한다.

Dual PASS 뒤 actual nonfuture Asia/Seoul microsecond를 `R032_PREPARATION_STARTED_AT`으로 한 번
관찰한다. R032 roadmap/P1 actual trio, observed time과 다음 exact edits로 live write 없이 core/test
postimage를 계산한다.

1. Core P4 paths를 fresh `source-r032` 두 path로 바꾼다.
2. Delegation은 `WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R032-R002`, check contract는
   `2026-08-02.7`로 바꾼다.
3. R032 trio를 provenance에 추가하고 dispositions에서 R031=`FAILED`, R032=`CURRENT`로 한다.
4. Authorization loop는 `range(17, 33)`이고 `__all__`은 R032 observed-time symbol만 export한다.
5. 모든 derived chronology는 R032 observed base의 +5m/+10m/+15m/+15m30s/+16m/+26m이고 seq3은
   checked+1us다.
6. Test의 role-prefix 블록을 revision 17~32 × exact three roadmap suffix의 48-path 곱집합으로
   교체한다. 그 집합과 R027 failed-source exact two path를 합친 exact 50-path mutation set을 쓰며
   R021 gap/backlog·R022 design/review 네 path는 포함하지 않는다.

Required postimage:

- core: `ast.parse` PASS, exact hash/bytes는 P2 manifest가 봉인
- builder: S3와 raw-equal
  `ebf06f12e409a9b14fc168dd9892da4bde02632ad6b403b2cc3102a2919d8ed6 / 52,856`
- test: `4b4d749f299b73d249f48db46e932edc5ed8d2697a76ce1f1b911dd7c52491f4 / 79,969`,
  `ast.parse` PASS

P2 root는 exclusive `0700`; exact descendant directories는 `failed-s3`, `failed-s3/scripts`,
`failed-s3/tests`, `proposed-s4`, `proposed-s4/scripts`, `proposed-s4/tests` 여섯 개이고 모두
exclusive `0700`이다. Exact eight regular `0600`, `nlink=1` files는 다음이다.

```text
failed-s3/scripts/walksafe_v2_5_candidate_validation.py
failed-s3/scripts/build_walksafe_v2_5_control_candidate_20260730.py
failed-s3/tests/test_walksafe_v2_5_control_candidate_20260730.py
proposed-s4/scripts/walksafe_v2_5_candidate_validation.py
proposed-s4/scripts/build_walksafe_v2_5_control_candidate_20260730.py
proposed-s4/tests/test_walksafe_v2_5_control_candidate_20260730.py
correction-map.json
recovery-manifest.json
```

Correction map은 schema1, R032 trio, observed time, exact S3/S4 rows와 ordered edits를 담는다.
Manifest artifact ID는 `WS-V25-R032-R031-FAILED-S3-S4-RECOVERY-R001`이고 authority false fields,
self 제외 seven file rows와 exact directory set을 담는다. JSON은 UTF-8 sorted compact terminal LF다.
각 file fsync, leaf-to-root directory fsync와 backup parent fsync 뒤 exact metadata/content를 재검사한다.
Partial/ambiguous failure는 root를 보존하고 R032를 종료한다.

## 5. P3 — exact S3→S4 one patch

P2 성공과 live S3 재확인 뒤 한 `apply_patch` call만 core와 test를 update한다. Builder, wrapper와
다른 file update, second patch는 0이다. Patch 뒤 live core/builder/test는 P2 proposed S4와 각각
raw-equal이어야 한다.

Static gate는 core·builder·test AST PASS, exact 16 provenance trios, trusted physical pins
`16 base + 48 trio = 64`, authorization bindings `17 base + 48 trio = 65`, roadmap trio path 48,
R027 failed-source 2를 더한 mutation set 50, missing/collision 0을 요구한다. R031 current와 R032 failed,
source-r031 current path, prefix-based provenance selection은 모두 0이어야 한다. Test는 37 names,
S1 대비 changed body exact 8, new helper exact 2, S3 대비 changed body exact 1, skip/expectedFailure/pass/
empty/unconditional assertion 0이어야 한다. Source import·compile·test/build/wrapper는 아직 0이다.

## 6. P4 — runtime 전 dual static source review

두 reviewer는 R030 S0/S1, R031 S2/S3, R032 P2 proposed S4와 live full diff, all pins,
return/call boundary, R032 lineage, exact 48/50 provenance path construction, 37-method AST/assertion table,
builder publication boundary와 negative matrix를 source/import/execute/compile 없이 독립 검사한다.
시작/종료 S4와 protected inputs가 같아야 한다.

PASS exact:

    status: PASS_FOR_R032_CORRECTED_R031_THREE_SOURCE_SET_ONLY
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

두 candidate reviewer는 네 gate를 각각 다시 실행하고 R032/P1, R030/R031/R032 recovery,
S0/S1/S2/S3/S4, P4 reviews와 physical exact-six를 검수한다. Review는 candidate exact object
`{path,entry_count,entry_name_digest_sha256,files}`와 four ordered gate rows exact keys
`role,argv,exit_code,stdout_sha256,stdout_bytes,stderr_sha256,stderr_bytes,tests_run,skipped,
failures,errors,status`, start/end bindings와 authority를 담는다.

PASS exact:

    status: PASS_FOR_R032_RECOVERED_R031_NON_EFFECTIVE_V25_CANDIDATE_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority: NONE_FOR_ACTIVATION_GOAL_PRODUCT

Dual PASS 뒤 §4 dirty/index equality, staging absence, protected pins와 세 recovery roots 불변을
확인한다. Daylog preimage는
`20983d0f481699bee1d708b059a34be85dacccf1dca1f06489e60938f2522d4b / 2,987`이어야 한다.
Parent의 한 `apply_patch`가 raw prefix를 보존하고 suffix 하나만 append하며 suffix는 exact
`\n## R032 R031 provenance oracle 교정 후보 실행\n\n`으로 시작하고 heading은 exactly once다. R030/R031
terminal, R032 recovery, gates/reviews/candidate와 zero authority를 기록한 뒤 local-memory
backup→log-work→sync를 수행한다.

## 9. 성공 조건과 후속

성공은 dual plan PASS, exact-eight S3/S4 recovery, one core+test patch, dual source PASS,
prebuild37, one `PUBLISHED_NEW`, four post gates, dual candidate PASS, dirty/index equality,
protected pins와 daylog single append의 conjunction이다. 결과는
`REVIEWED_R032_RECOVERED_R031_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`다.

Canonical/checkpoint/Goal/runtime queue/product write, seq2/seq3 application, formal/device/release
credit, commit/push/PR/deploy/기관 제출, paid service/secret use와 failed-r001/user-data 삭제는 0이다.
후속 R033만 activation transaction을 검토할 수 있고 R034 전에는 Goal/product authority가 없다.
