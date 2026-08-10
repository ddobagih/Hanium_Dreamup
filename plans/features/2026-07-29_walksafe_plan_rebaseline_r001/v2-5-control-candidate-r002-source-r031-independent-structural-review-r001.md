# WalkSafe R031 corrected source independent structural review r001

```text
review_id: WS-V25-R031-CORRECTED-SOURCE-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent: /root/r028_core_fix_design
reviewer_axis: S2_S3_RAW_EQUALITY_RECOVERY_PROVENANCE_RETURNS_PUBLICATION_TEST_AUTHORITY
reviewed_at: 2026-08-02T16:58:17.743947+09:00
status: PASS_FOR_R031_CORRECTED_R030_THREE_SOURCE_SET_ONLY
findings: BLOCKING=0 MAJOR=0 MINOR=0
authority: NONE_FOR_PUBLICATION_UNTIL_RUNTIME_GATES
reviewed_source_module_import_count: 0
source_test_builder_execution_count: 0
compile_count: 0
```

## 판정과 범위

R031 roadmap과 P1 두 review, R030/R031 recovery root, R030 failed S1과 recovered S0,
R031 failed S2/proposed S3, live core·builder·test 전체를 source import·execute·compile 없이
정적으로 검수했다. Live S3는 R031 recovery의 proposed S3와 세 파일 모두 raw-equal이며,
확인된 R030 terminal 결함과 R031 successor lineage를 추가 범위 없이 닫는다.

이 PASS는 runtime 전 corrected three-source set에만 적용된다. Test, builder, wrapper 실행과
candidate publication은 아직 허용하지 않으며 P4의 다른 독립 review까지 zero-finding PASS여야 한다.
Activation, canonical/checkpoint, Goal, runtime queue, product, 배포와 formal/device/release credit
권한은 0이다.

## Roadmap과 recovery identity

R031 roadmap/P1 trio는 모두 regular nlink-1 physical file이며 actual identity는 다음과 같다.

```text
roadmap     43f9d3cb119d3638ea743e6e5e5371d1cfa0aa50df817e7850fa449519405ea2 / 15468
structural  9eb38d17a94dd75bb1a43004bf15e46f53439cc8f6391543477084f04380ccfc / 7026
skeptical   1516b6a0253eee6b73f8827ba0e9278a16f35523c88efc82d339a17eac3640af / 8763
```

두 P1 review는 모두 exact
`PASS_FOR_R031_R030_TERMINAL_RECOVERY_EXECUTION_ONLY`, findings `0/0/0`이고 R031 roadmap
`43f9d3...5ea2 / 15,468 / 246 lines`를 결속한다.

R030 recovery root는 root+exact seven descendant directories mode `0700`, exact ten regular
mode `0600` nlink-1 files다. Manifest는
`977b64191f52d73cb7cf22d02446a18998512c1c63fc9a01730d33a5c01fac23 / 3,588`이고
self 제외 nine rows가 actual tree와 일치한다. Patch의 25/13/10 chunks를 leftmost-after-cursor로
역적용한 결과는 stored S0와 raw-equal이고, reverse edit-log round-trip은 S1과 raw-equal이었다.

R031 recovery root는 root+exact six descendant directories mode `0700`, exact eight regular
mode `0600` nlink-1 files이고 other/symlink/extra entry가 없다. Correction map은 canonical JSON
`1d3065a2d6f2a64ff91e4dd203698a1f4364a06e3b9edbf28956706202aab7e5 / 4,518`, manifest는
canonical JSON `cc17f0517edbc575a063dc1b2164163c72a23beaaa0aa9183264cf8320eee252 / 3,597`다.
Manifest self 제외 seven rows, exact directories, failed-S2/proposed-S3 bindings와 correction-map
state rows가 actual members와 일치한다. Builder S2/S3 raw equality와 모든 authority false/
evidence-only projection도 일치한다.

## S2/S3와 live exact identity

```text
role     failed S2 SHA-256 / bytes                                      proposed/live S3 SHA-256 / bytes
core     f6c136b5da635c2d03b74d4d988c26a2fdc6205c07eca0849f20331a9eb72e00 / 198304
         9aa3e5bc81a67a6186c07a382e29f3244223d33971b5700d9dbd91aecab74f3c / 199008
builder  ebf06f12e409a9b14fc168dd9892da4bde02632ad6b403b2cc3102a2919d8ed6 / 52856
         ebf06f12e409a9b14fc168dd9892da4bde02632ad6b403b2cc3102a2919d8ed6 / 52856
test     145dff335eea3b1c55c5c7146e0f9f5bbbd378ae01567bb17941cfd701b4710c / 79420
         fb3fc7aef1e0175fc1ead259fe080d92d52eec2992ddc287e78c3456fcf2bbb8 / 79415
```

Core·builder·test S3의 정적 AST parse는 모두 PASS다. S2→S3 test delta는 byte offset 62,722의
ASCII `with ` (`7769746820`) exact 5-byte deletion 하나뿐이고 common suffix는 16,693 bytes다.
Builder는 failed S2, proposed S3와 live가 모두 raw-equal이다. Core S2→S3 full diff는 correction
map의 R031 paths/delegation/time/provenance/disposition, R027 pin, 두 return과 auth range/export
교정만 포함한다.

## Core provenance, namespace와 return 경계

`ROADMAP_PROVENANCE_TRIOS`는 exact R017~R031 15 revisions와 45 physical rows를 가진다.
각 SHA/bytes는 actual file과 일치한다. Disposition은 R017~R026 `REJECTED`, R027 `FAILED`,
R028~R029 `REJECTED`, R030 `FAILED`, R031 `CURRENT`이며 각 trusted role에 포함된다.
R031 current roles는 exact `R031_CURRENT_EXECUTION_ROADMAP`,
`R031_CURRENT_STRUCTURAL_REVIEW`, `R031_CURRENT_SKEPTICAL_REVIEW`다.

Base pins와 trio rows를 합친 trusted physical pin은 exact 61개이고 mismatch는 0이다. R027 failed
skeptical review는 physical
`6cf06ede378ffe10ddcc2e9ca679cd77a57f38911faee298457e01e07f04f00a / 11,381`에 결속한다.

`authorization_subject_bindings()`은 initial exact 17 bindings를 `bindings`에 할당하고
`range(17, 32)`의 45 physical rows를 추가한 뒤 line 1858에서 exact one `return bindings`를
수행한다. 따라서 key count는 62이고 early return/unreachable tail/undefined local은 0이다.
`load_source_state()`도 completed bindings를 line 1195에서 한 번 return하며,
`product_inventory_contract()`은 line 1390의 direct dict return 하나만 가진다.

Fresh P4 path는 exact `source-r031` structural/skeptical 두 path이고 delegation은
`WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R031-R002`, check contract는
`2026-08-02.6`이다. `R030_PREPARATION_STARTED_AT`과 `_R030_PREPARATION_STARTED` current symbol은
0이며 `R031_PREPARATION_STARTED_AT`이 정의·export된다. Exact directive는 840 bytes,
SHA-256 `8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a`다.

Observed base `2026-08-02T16:39:56.389561+09:00`은 두 P1 review 뒤의 nonfuture Asia/Seoul
microsecond이고 correction map/manifest/core가 같은 값을 사용한다. PREPARED_ON과
+5m/+10m/+15m/+15m30s/+16m/+26m은 `_R031_PREPARATION_STARTED`에서만 derive된다. Seq2는
quick-gate checked_at과 같고 seq3 construction/validation은 checked_at plus exact 1us다.

## Failed-r001과 validation call boundary

Failed-r001은 exact six regular non-symlink nlink-1 members, current owner, world-write 0이며
name terminal-NUL digest는
`f4c9e884242b5acf190f0cb95d1567845173d8ae8e3a20919d81519f88b5d86c`다. Six content pins,
seq1-only history, role별 non-effective metadata와 old test binding
`5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459 / 68,995`가 모두 맞다.

Core direct failed-r001 call map은 `validate_bundle_bytes` entry/after-construction/exit 3회,
quick 2회, active 2회, candidate 2회다. Builder는 build entry/after-output 2회,
write entry/pre-rename/post-parent-fsync 3회, check entry/exit 2회다. Candidate exact reader와
failed-r001 validator는 nofollow exact-set, directory/member type, member nlink1, owner,
world-write, pre/open/post physical fingerprint와 content length를 fail-closed로 검사한다.

## Builder physical publication boundary

Builder source는 S2/S3/live raw-equal이다. `build_outputs()`는 failed-r001 entry/after-output과
candidate semantic validation을 수행한다. `write_add_only()`는 output exact-six/type와 semantics를
final write 전에 검증하고 preexisting directory/file/symlink target을 모두 terminal로 처리한다.

Staging은 exclusive mode `0700`, member는 `O_CREAT|O_EXCL|O_NOFOLLOW`, 각 file fsync 뒤 directory
fsync를 수행한다. `_read_staging_outputs()`는 exact-six, stage mode/owner, regular member/nlink1/
owner/world-write, nofollow raw bytes와 pre/open/post fingerprint를 확인한다. Deterministic byte
equality와 semantics, failed-r001 pre-rename 검증 뒤에만 `renameat2(RENAME_NOREPLACE)`를 사용한다.
EEXIST race에서는 foreign target을 보존하고 own staging만 cleanup한다. Rename 뒤에는 target을
published로 표시한 다음 parent fsync하므로 fsync ambiguity에서 post-rename target을 삭제하지 않는다.
Successful fsync 뒤 failed-r001과 published bytes/semantics를 다시 확인한다. `check_outputs()`는
entry/exit failed-r001과 physical/deterministic/semantic equality만 읽고 write가 없다.

## Test structure, assertions와 negative matrix

Failed S1 대비 live S3 test는 unique exact 37 method names를 유지한다. Changed body는 roadmap의
exact 8개뿐이고, 새 non-test class helper는 `_copy_failed_r001_bundle`와 `_snapshot_path` exact
2개뿐이다. 기존 helper, top-level/class signature/non-function AST와 나머지 29 test body는 S1과
exact equal이다. Changed method signature/decorator/return annotation 변화는 0이다.

Assertion counts는 lower bound 이상이다.

```text
quick          assertRaisesRegex=1
chronology     assertRaises=1 assertRaisesRegex=1
rfc3339        assertRaises=1 assertRaisesRegex=1
trusted-source assertEqual=2 assertRaisesRegex=2
missing-json   assertRaisesRegex=3
add-only       assertEqual=5 assertFalse=2 assertRaises=2 assertRaisesRegex=3 assert_not_called=1
parent-fsync   assertEqual=2 assertRaisesRegex=2 assertTrue=1
check-readonly assertEqual=4 assertFalse=3
```

Negative matrix는 all roadmap provenance mutation, failed-r001 content/extra/symlink/hardlink/
bundle-world-write/member-world-write, candidate missing/extra/hidden/directory/link/member-type,
existing target directory/file/symlink preservation, invalid preflight no-write, partial-write cleanup,
staging drift, rename race, parent-fsync ambiguity와 terminal retry, repeated read-only check를 분리한다.
Skip/expectedFailure/pass/empty/unconditional assertion은 0이다.

## 시작·종료 pins와 authority

Review read phase 시작과 review-file 추가 직전 source pins는 다음과 같고 proposed S3와 같다.

```text
core    9aa3e5bc81a67a6186c07a382e29f3244223d33971b5700d9dbd91aecab74f3c / 199008
builder ebf06f12e409a9b14fc168dd9892da4bde02632ad6b403b2cc3102a2919d8ed6 / 52856
test    fb3fc7aef1e0175fc1ead259fe080d92d52eec2992ddc287e78c3456fcf2bbb8 / 79415
```

Trusted inputs, failed-r001, 두 recovery tree, wrappers, daylog와 terminal-absence sentinels를
path/type/mode/uid/gid/nlink/content로 정규화한 protected snapshot은 exact 111 rows,
`c0f371e35155959b4fff8462eac9a9bb4f2021d04983ca1ae73357e36117d680 / 32,956 bytes`다.
Concurrent P4 review output paths는 이 protected input aggregate에서 제외한다. R002 final과 staging은
absent이고 R030 P4/P6 및 R031 P6 targets도 absent다.

Recovery manifests와 documents의 authority는 effective/approved/applied/activation/canonical/
checkpoint/Goal/product/deployment false이고 evidence-only true다. 이 review는 source, builder,
test, wrapper, recovery, failed-r001, candidate와 daylog를 수정하지 않는다.
