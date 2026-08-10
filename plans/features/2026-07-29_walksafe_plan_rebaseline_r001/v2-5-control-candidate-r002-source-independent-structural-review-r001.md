# WalkSafe R027 r002 source patch independent structural review r001

- review_id: `WS-V25-R027-R002-SOURCE-STRUCTURAL-REVIEW-R001`
- reviewer_agent: `/root/r027_source_structural_review`
- reviewer_axis: `STATIC_STRUCTURE_AND_EXACT_SOURCE_DIFF`
- target_path: `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R027.md`
- target_sha256: `79544b59b5f3168cc16925f7972ff23cc47c221d83e69b6cdaea751f21005467`
- target_bytes: `22055`
- reviewed_at: `2026-08-02T14:46:31.441709+09:00`
- status: `REJECTED_NEW_ROADMAP_REQUIRED`
- findings: `BLOCKING=6 MAJOR=0 MINOR=0`
- authority: `NONE_FOR_PUBLICATION_UNTIL_RUNTIME_GATES`

## 검수 방식과 실행 경계

R027 전체와 특히 §5~§7을 읽고, pinned archive의 exact member bytes와 현재 세 source를
모두 정적으로 읽었다. 각 파일의 raw SHA/bytes, 전체 line diff opcode, top-level AST 및 test
method AST를 독립 재계산했다. Source, test, builder의 실행·import·`py_compile`은 0이며 runtime
gate와 publication도 실행하지 않았다.

검수 시작과 이 문서 작성 직전의 S1 identity는 동일했다.

| role | path | S1 SHA-256 | bytes |
|---|---|---|---:|
| core | `scripts/walksafe_v2_5_candidate_validation.py` | `926f2a997692aff9e996458f3099515fb55bf51ee9171197c4ea27bcdf0b57f1` | 187,211 |
| builder | `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `2960f1b6e49cd2dbd8904de15fe23327eb3b5959efccc68a42be564532c0e96a` | 48,374 |
| test | `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `874c89e04d67ef2c27709e84917ee01a98faa73405b983be2a299abc72a834c5` | 70,163 |

Wrapper는 시작/종료 모두 R027 pin과 동일했다.

| role | path | SHA-256 | bytes |
|---|---|---|---:|
| continuation | `scripts/check_walksafe_project_continuation_v2_5_candidate.py` | `1a2dc4f1d8c28c5163cae8306a160e8f942f962ea2ec190a625b64dc688ece5c` | 2,513 |
| Goal | `scripts/check_walksafe_goal_graph_v2_5_candidate.py` | `98d656e8c3c54e122a419eee9d2c3b3285f2594f4abd3abbb5597d8e9470feda` | 2,434 |

## BLOCKING findings

### B-01 — pinned archive가 exact S0 세 preimage를 포함하지 않는다

Archive 자체는 R027 pin과 일치한다:
`2de0201890d73a7067d2c5c879a69ffd31bcba8bfcc7ffbf21d0fdae1a7fe398 / 360,037,403`.
그러나 `tar -xOf`로 읽은 exact 세 member는 모두 §1 S0 binding과 다르다.

| role | R027 S0 SHA-256 / bytes | archive member SHA-256 / bytes | current S1 SHA-256 / bytes |
|---|---|---|---|
| core | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f / 180432` | `423a60a3195313b11f65789c91ba772bb3bc3213c63bba4ec23c30f3e69434d5 / 170867` | `926f2a997692aff9e996458f3099515fb55bf51ee9171197c4ea27bcdf0b57f1 / 187211` |
| builder | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175 / 51153` | `31e66d3ba43764e3d4cc5987813f39d5a70ad9796873690e37875191458d30f3 / 48501` | `2960f1b6e49cd2dbd8904de15fe23327eb3b5959efccc68a42be564532c0e96a / 48374` |
| test | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523 / 69685` | `905eae38d7d24977be2b04fc1c7b6d83c432776c7129939f3d2af1de527cc31f / 43318` | `874c89e04d67ef2c27709e84917ee01a98faa73405b983be2a299abc72a834c5 / 70163` |

Archive member와 S1의 전체 diff는 core `4,876→5,263` lines, 120 edit groups,
`-445/+832`; builder `1,276→1,244`, 50 groups, `-139/+107`; test
`1,088→1,799`, 33 groups, `-71/+782`였다. Archive test는 30 methods이고 S1은 37이라
R027이 요구한 exact S0의 36 method 유지와 1 rename, unchanged-body AST equality 및 assertion
kind non-decrease를 이 archive로 증명할 수 없다. Core/builder delta가 §6에만 한정됐다는 판정도
동일하게 불가능하다.

최소 교정: R027을 재사용하지 않는다. 새 roadmap이 이 rejected S1을 exact failed predecessor로
pin하고, 수정 직전 raw three-file preimage를 별도 immutable artifact에 정확히 보존·pin한 뒤 새
source patch와 새 dual source review를 수행해야 한다. Hash/bytes 주장이나 patch 작성자의 기억은
독립 raw-byte preimage를 대체하지 못한다.

### B-02 — core의 physical trust anchor가 실제 immutable evidence와 다르다

Core 342~373행의 literal을 물리 파일과 독립 비교했다.

- `ROADMAP_PROVENANCE_TRIOS` 33 rows 중 R017~R024의 24 rows와 R026의 3 rows,
  합계 27 rows의 SHA가 틀렸다. Bytes는 맞고 R025/R027 6 rows만 맞는다.
- 각 revision의 올바른 physical plan/structural/skeptical SHA는 다음과 같다.
  - R017: `6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5`, `cbcb6a14e4af2b82ec84c300799575fd99a81b65e9e8236177ff7221be958e81`, `d10ba282eee810c58a0d76d8658a4a21d937dcd4c76882a4936dc97538d61fe4`
  - R018: `2a1df2cd5f0fdccb3183b15607b6a017ab6b2556d661ea16e9b6caaa9d9255f7`, `a87d59d2a3a5bbf601b4c3d9c3fdf8adaa1511c99e1c538a03db7be474a3b584`, `8fa2bfe82ac5b0b4a7a9d3708f9cf3407e995c8c82bd74d79ce2e8a4161879a4`
  - R019: `3c21f917793b0ffbe5f6aa892e1d73d122799fcfe005c22f2f289571b45d4eb2`, `97bfa16d3c0f02e6ac601c92bc78c19779438948fc089624dfa9dcc235c0a548`, `b2995ccd45980cfecd422f88bd02aed6d98b42f445824a29876336efb7e1d8fc`
  - R020: `276f499ce235289cbe7b6f15abc835ce87ed0ce8ccd76d03df904bc5f5cd44f1`, `9b378e4555c6c3a1edf1e712acb90f5f51089ed469116b552b1be496f7058316`, `8ac10e419f31409e5062cf82ab1066f3dafaff6eaaed63155662c3ff56ca9690`
  - R021: `b65d0242656b5898392fcab2b168832a6ce19577fa5e469cbbce2cfda777b919`, `d9dc411e51d3e6a724bb626630b20de8f258c6659829475d98c81903eee26a94`, `4e751ad91a3efec2546949ad393fa16692fb268300aea6df6aeeafeae068b93b`
  - R022: `2a05166af626fae929decf0fef28fc6c70b4b448a312e6e73ba2aa9e8cac3caa`, `46b227c645fa047d5c6fb2799cde6f40eed77b433a198b6e95f7b007586e111f`, `84b3cbff023acbc111681fce2484b2a636bcb45d1aa76b4bdbfe51d79f01cc75`
  - R023: `e0d2f11064b3a38745555332aca16bcc6b876094c33d89d26db0aa5da289272d`, `48e7e6f9221753657443b4a08d1df10bf813810690f0b4fa44165aed31a7ca1f`, `5939652d33a2ae54a09c9028160de7f2856f1a2a7515830ff52a7f87dae78ed3`
  - R024: `356fb4308542b129e0d2ef5957328cfd0fdebc57bd8dbf297b0856675eb21a92`, `40ff2e4cd20b49d34e90df55bf0af34e3a734639e89e67ba4adcb42b05e1c04d`, `4abcccdd066f608bd92854f3ec0794ccd3118d4046d58988222460b1f7bb03b4`
  - R026: `b5485b82c679824ca8e5a53e441bd0750e09cbad5c9ecef090224191db3db479`, `f0b1e69159554282b072894009adcd2681ee533250e37cfc4d5c7f4b2df79cac`, `0c50eb94c748e974922f8f8cf2e77a80307036e7f7b28bb08301e24bd773733d`
- `FAILED_R001_PINS` six SHA, basename digest, old test binding도 364~373행에서 모두 틀렸다.
  Immutable r001의 actual values는 아래와 같다.

| basename | actual SHA-256 | bytes |
|---|---|---:|
| `candidate-output-manifest.json` | `ffd8e1616891f1d3fc75c24bcbfc9d676760afe786fe19d63218c66024119945` | 19,757 |
| `static-plan-manifest-v2.5.0.candidate.json` | `213cdea4f061d77f587211b3c315f5fb27eb5a2f39dbe7a9382e2c34e7a29a68` | 34,117 |
| `transition-history-v2.5.candidate.json` | `1b859617a6cc7a007a9cdca61f10d5b040ba743bbd90bd5f7183163a8a1f494e` | 2,676 |
| `v2.5-application-transaction-plan.candidate.json` | `bb7819d4c3cc27878685fea3d4764ef7d55d511674eb078fc0cce8bfc0840275` | 659,092 |
| `v2.5-control-package-manifest.candidate.json` | `3e27d14670c804bcc6df202f360a9a0541c1b2a83b3ad759961d2319a577261d` | 27,037 |
| `walksafe-project-continuation-checkpoint-v2.5.candidate.json` | `273b320645ee17737d452af263d45b45058cd7ed012f0bf32c6a9345ae894864` | 218,564 |

Actual terminal-NUL basename digest는
`f4c9e884242b5acf190f0cb95d1567845173d8ae8e3a20919d81519f88b5d86c`, actual old test
binding은 `5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459 / 68995`다.
Current wrong binding은 어느 failed-r001 JSON에도 존재하지 않는다. Builder 1038행의 첫 validator
호출부터 terminal error가 되므로 runtime gate를 시도할 수 없다.

최소 교정: 새 revision에서 physical evidence를 재해시한 exact values로만 constants를 교체하고,
각 literal 대 physical file의 정적 equality 검사를 새 source review의 필수 oracle로 둔다.

### B-03 — failed-r001 validator 이름과 필수 call boundary가 §6을 충족하지 않는다

§6은 exact `validate_failed_r001_bundle(root)`와 construction 전/후, rename 직전, parent fsync
직후의 검사를 요구한다. Current core는 765행에
`validate_failed_r001_negative_evidence(root)`만 정의한다. Calls는 builder 1038, 1109, 1201행과
core 5185행뿐이다. Builder 1178행의 semantic validation과 1180행 rename 사이, 1187행 parent
fsync와 1188행 semantic validation 사이에는 failed-r001 validator call이 없다.

최소 교정: 새 revision에서 roadmap exact function name으로 통일하고, output construction 직후,
rename 직전 및 parent fsync 직후에 physical r001을 다시 검증한다. Check path의 existing call은
유지한다.

### B-04 — current construction에 R001 active checkpoint ID가 남아 있다

Core 2196행의 `activation_envelope_projection_contract()` fixed row가
`WS-V25-R022-ACTIVE-CHECKPOINT-20260730-R001`을 출력한다. 이는 §6 historical literal allowlist가
아니며, 같은 source의 exact `ACTIVE_CHECKPOINT_ID=...-R002` 및 R027 namespace와 충돌한다.

최소 교정: 새 revision에서 이 fixed row도 exact `ACTIVE_CHECKPOINT_ID` R002 value에서 파생하고,
세 source의 current-construction `candidate-r001`, `R001`, gate/event `001` 정적 scan을 다시 한다.

### B-05 — add-only tests의 fixture와 assertion이 current builder precondition과 모순이다

Test 1418~1435행은 temporary root에 failed-r001 evidence를 만들지 않지만 builder 1109행은 target
preflight보다 먼저 그 evidence를 필수 검사한다. 따라서 기대한 existing-target terminal까지
도달하지 않는다. 더욱이 1425행은 target static bytes를 `dummy[STATIC_NAME]`, 즉 filename UTF-8
bytes로 쓰는데 1432~1435행은 이를 literal `b"mismatch"`와 같다고 주장한다.

같은 mandatory failed-r001 fixture 누락이 partial-write case 1437~1461행과 parent-fsync case
1463~1491행에도 있어 기대한 injected `OSError` 경로에 도달하지 못한다. 이는 source 실행 없이
control flow와 literal equality만으로 확정된다.

최소 교정: 새 revision의 temporary roots에 exact failed-r001 evidence를 byte-copy하고, foreign
target 불변 assertion은 write 전 snapshot과 write 후 snapshot의 exact equality를 비교한다.

### B-06 — §7 필수 negative coverage가 source에 없다

Current test는 37 unique test methods, required new fsync-test name present/old name absent,
decorator skip/expected-failure/pass/unconditional literal assertion 0이다. 그러나 test 전체에
`FAILED_R001` 또는 `failed_r001` reference가 0이므로 required failed-r001 mutation, extra, link,
unsafe-state negative subcases는 존재하지 않는다. Add-only publication tests에도 r002 target의
regular-file, symlink, rename race subcase가 없고 exact-directory, partial-write, parent-fsync만 있다.
Wrapper symlink tests는 publication target oracle을 대체하지 않는다.

최소 교정: §7이 열거한 failed-r001 및 add-only target/race cases를 허용된 changed test body에
추가하고, 새로 보존한 exact preimage와 assertion-call kind non-decrease를 정적으로 재검증한다.

## 일치한 정적 축

다음은 finding을 상쇄하거나 execution authority를 주지 않는 제한적 관찰이다.

- 현재 top-level R002 package/transaction/delegation/quick/final/history/checkpoint/output/event IDs,
  gate path와 command-contract version은 §6 exact values다.
- Current directive는 독립 재계산 결과 UTF-8 840 bytes 및
  `8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a`다.
- Physical `PREPARED_AT`은 `2026-08-02T14:26:09.786668+09:00`이며 test-only time constants는
  +5m/+10m/+15m/+15m30s/+16m/+26m offsets와 맞는다.
- Builder에는 `_recover_exact_existing` 및 `_read_exact_candidate_directory`가 없고,
  `RENAME_NOREPLACE`, existing-target terminal, `PUBLISHED_NEW` surface가 있다.
- Base trusted pins C0, reviewed pair, design 및 R016 trio 14 rows는 모두 physical files와 맞는다.

## 판정

여섯 blocking finding 중 하나만 있어도 R027 §7에 따라 source/build/test execution과 r002
publication은 0이어야 한다. 현재 세 source는 rejected visible evidence로 보존하고, exact baseline
capture와 위 최소 교정을 명시한 새 roadmap 및 fresh dual source review 전에는 다음 epoch로
진행할 수 없다. 이 review는 activation, canonical/checkpoint, Goal, product 또는 publication
권한을 부여하지 않는다.
