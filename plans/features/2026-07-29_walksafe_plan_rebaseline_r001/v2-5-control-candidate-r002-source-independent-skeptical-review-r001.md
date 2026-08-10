# WalkSafe R027 r002 source patch independent skeptical review r001

- review_id: `WS-V25-R027-R002-SOURCE-INDEPENDENT-SKEPTICAL-REVIEW-R001`
- reviewer_agent: `/root/r027_source_skeptical_review`
- reviewer_axis: `S0 archive binding, full-static S1 semantics, adversarial test/publication boundary`
- target_path: `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R027.md`
- target_sha256: `79544b59b5f3168cc16925f7972ff23cc47c221d83e69b6cdaea751f21005467`
- target_bytes: `22055`
- reviewed_at: `2026-08-02T14:45:40.839446+09:00`
- status: `REJECTED_BLOCKING_R027_THREE_SOURCE_PATCH`
- findings: `BLOCKING=6 MAJOR=1 MINOR=0`
- authority: `NONE_FOR_PUBLICATION_UNTIL_RUNTIME_GATES`
- reviewed_source_module_import_count: `0`
- source_test_builder_execution_count: `0`
- py_compile_count: `0`

## 결론

R027 P3의 exact S0 비교 전제가 성립하지 않고, 현재 S1에는 정적 판독만으로도
prepublication을 불가능하게 하는 결함이 있다. 이 review는 publication, activation,
canonical/checkpoint, Goal, product 또는 배포 권한을 부여하지 않는다. R027 §7에 따라
modified test, builder, 두 wrapper의 실행과 r002 candidate publication은 모두 금지하며,
현재 세 source는 실패 evidence로 그대로 남겨 새 roadmap revision에서 다뤄야 한다.

## 검수 결속

R027 P1 문서는 검수 시작과 종료에 다음 exact bytes로 동일했다.

| 역할 | SHA-256 | bytes |
|---|---|---:|
| R027 roadmap | `79544b59b5f3168cc16925f7972ff23cc47c221d83e69b6cdaea751f21005467` | 22,055 |
| R027 structural plan review | `eeeb854ae71c0179db85c0806c636eaea6757c51261feac207fb207ffcf468a2` | 3,324 |
| R027 skeptical plan review | `eaa7f3ccde30b77b8208d76b64b512e61b6b7a6b4c8af54a732cf0f0d5b9ba0c` | 6,233 |

S1 세 파일은 검수 시작과 종료에 다음 exact binding으로 동일했다.

| 역할 | path | S1 SHA-256 | bytes |
|---|---|---|---:|
| core | `scripts/walksafe_v2_5_candidate_validation.py` | `926f2a997692aff9e996458f3099515fb55bf51ee9171197c4ea27bcdf0b57f1` | 187,211 |
| builder | `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `2960f1b6e49cd2dbd8904de15fe23327eb3b5959efccc68a42be564532c0e96a` | 48,374 |
| test | `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `874c89e04d67ef2c27709e84917ee01a98faa73405b983be2a299abc72a834c5` | 70,163 |

Unmodified wrapper bindings도 시작과 종료에 동일했다.

| 역할 | path | SHA-256 | bytes |
|---|---|---|---:|
| continuation | `scripts/check_walksafe_project_continuation_v2_5_candidate.py` | `1a2dc4f1d8c28c5163cae8306a160e8f942f962ea2ec190a625b64dc688ece5c` | 2,513 |
| Goal | `scripts/check_walksafe_goal_graph_v2_5_candidate.py` | `98d656e8c3c54e122a419eee9d2c3b3285f2594f4abd3abbb5597d8e9470feda` | 2,434 |

## S0 archive 재계산

지정 backup은 regular tar gzip이며 archive 자체는 R027 pin과 일치했다.

`/home/ddobagi/.codex/backups/walksafe/20260802T0125KST-rc2-pre-roadmap/untracked-files.tar.gz`

- SHA-256: `2de0201890d73a7067d2c5c879a69ffd31bcba8bfcc7ffbf21d0fdae1a7fe398`
- bytes: `360037403`
- exact 세 member path: 각각 한 번 존재하며 모두 regular file

그러나 `tar -xOf` 추출 bytes는 R027 §1 S0 pin과 세 건 모두 다르다.

| 역할 | R027 S0 SHA-256 / bytes | archive member SHA-256 / bytes |
|---|---|---|
| core | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f / 180432` | `423a60a3195313b11f65789c91ba772bb3bc3213c63bba4ec23c30f3e69434d5 / 170867` |
| builder | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175 / 51153` | `31e66d3ba43764e3d4cc5987813f39d5a70ad9796873690e37875191458d30f3 / 48501` |
| test | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523 / 69685` | `905eae38d7d24977be2b04fc1c7b6d83c432776c7129939f3d2af1de527cc31f / 43318` |

Archive test member의 정적 AST에는 test method가 30개뿐이다. 따라서 이를 37-method
S0로 가장해 비교할 수 없다. Exact S0 raw bytes가 없으므로 36개 unchanged method AST,
changed-body allowlist, assertion-call-kind 비감소 및 core/builder full-diff 범위를 독립적으로
증명할 수 없다.

## Findings

### SK-B01 — 지정 archive가 exact S0가 아니다 (`BLOCKING`)

위 세 건의 content mismatch 때문에 R027 §7의 독립 S0→S1 diff oracle이 정의되지 않는다.
Hash만 알려진 S0를 archive의 다른 bytes나 현재 S1로 대체하는 것은 허용되지 않는다.

최소 교정: R027을 종료하고 새 roadmap revision에서 단일 patch transcript로부터 exact S0를
독립 재구성하여 세 pin 모두를 만족하는 immutable raw backup을 만들거나, 복구가 불가능하면
현재 source 전체를 새 기준선으로 삼는 full-file semantic review 계약으로 명시적으로 교체한다.
어느 경우든 새 roadmap과 그 독립 이중 검수 전에 source를 다시 수정하거나 실행하지 않는다.

### SK-B02 — trusted provenance와 failed-r001 pin이 물리 bytes를 거부한다 (`BLOCKING`)

S1 `ROADMAP_PROVENANCE_TRIOS`의 R017–R024 및 R026에서 27개 SHA가 물리 파일의
재계산 SHA와 모두 다르다. Bytes 값만 같고 SHA는 다르다. R025와 R027의 여섯 SHA만
일치한다. 또한 `FAILED_R001_PINS` 여섯 SHA, basename terminal-NUL digest와 old test
binding까지 여덟 SHA가 모두 R027 §5 및 immutable r001 물리 bytes와 다르다.

대표적인 실제/코드 값은 다음과 같다.

- R017 roadmap actual `6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5`, code `64370020dc8b05881892fb2b4b1a9eff6b9721838723875a22066d8fcd8f2767`
- R026 roadmap actual `b5485b82c679824ca8e5a53e441bd0750e09cbad5c9ecef090224191db3db479`, code `b548bb16f28b8fe11bb88a875540f395a29b751c312c3555d85bb65f80cde3c5`
- failed basename actual `f4c9e884242b5acf190f0cb95d1567845173d8ae8e3a20919d81519f88b5d86c`, code `f4c9c188e99ad3dabdeecb1f87958b5af1b9721981c7332bd68a0c2f53a4d86c`
- failed old-test actual `5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459`, code `5f1e33bde9d3124298d6e82c8a39c548a0f2d52abb9f8c94e448c432d9124949`

`builder.build_outputs()`는 먼저 failed-r001 validator를 호출하므로 첫 basename/file pin에서
종료한다. 이를 고쳐도 `load_source_state()`가 R017에서 source CAS drift로 종료한다.

최소 교정: 새 revision에서 R017–R027 전 파일과 failed-r001 exact-six를 물리 bytes로 다시
계산해 literal을 교체하고, pristine positive load가 먼저 성공한 뒤 각 mutation이 실패하는
정적·runtime oracle을 둔다. 현재 test는 R027 한 행만 literal assert하고, mutated pair를 읽기
전에 unrelated bad pin으로 실패해도 같은 `source CAS drift`로 수용하므로 오염을 가린다.

### SK-B03 — `load_source_state()`가 값을 반환하지 않는다 (`BLOCKING`)

Core line 819–841은 `bindings` dict를 만들지만 `return bindings` 없이 함수가 끝난다.
Pins를 바로잡더라도 builder line 984는 `None`을 source로 받아 첫 construction에서 실패한다.

최소 교정: 새 revision의 허용 patch에서 dict 구성 직후 exact return을 복원하고, pristine
`load_source_state(root)`의 schema/key set을 직접 확인하는 positive test를 추가한다.

### SK-B04 — R017–R027 authorization provenance loop가 도달 불가다 (`BLOCKING`)

`authorization_subject_bindings()`는 core line 1413에서 dict를 즉시 반환한다. 그 뒤
line 1482–1487의 loop와 `return bindings`는 실행될 수 없고, `bindings`도 그 scope에
정의되지 않는다. 따라서 R027/current 및 rejected-history provenance를 authorization에
결속한다는 §6 계약이 산출물에 반영되지 않는다.

최소 교정: 새 revision에서 먼저 dict를 변수에 구성하고 R017–R027 exact rows를 추가한 뒤
한 번만 반환한다. Positive key-set/physical binding test와 각 provenance mutation negative를
분리해 pristine success를 선행 검증한다.

### SK-B05 — current construction에 R001 namespace가 남았다 (`BLOCKING`)

Core line 2196의 activation envelope fixed row는
`WS-V25-R022-ACTIVE-CHECKPOINT-20260730-R001`이다. 이는 line 219의 exact R002
`ACTIVE_CHECKPOINT_ID` 및 실제 projected final checkpoint와 서로 다르며, R027 §6의
historical literal allowlist 밖 current-construction R001이다.

최소 교정: 해당 row를 단일 R002 상수에서 유도하고, plan contract와 projected checkpoint의
checkpoint ID exact equality를 검증한다.

### SK-B06 — publication test가 요구 경로에 도달하지 못하고 자체 모순이다 (`BLOCKING`)

Test line 1424–1425는 exact-existing directory에 `dummy[name]`을 쓴다. 그 직후 line
1432–1435는 같은 static member가 `b"mismatch"`라고 단정하므로 결정적으로 실패한다.
또한 temp publication/fsync fixtures에는 새 failed-r001 evidence가 없고 validator mock도 없다.
따라서 `write_add_only()`가 target kind, partial-write, rename race 또는 parent-fsync branch에
도달하기 전에 `failed r001 evidence directory missing`으로 끝난다.

최소 교정: foreign target의 실제 preimage를 먼저 봉인해 unchanged를 비교하고, publication
unit fixtures에서 failed-r001 validator만 명시적으로 격리한다. Validator 자체는 별도 test에서
exact-six pristine success와 mutation/extra/member-link/unsafe-root 실패를 직접 검증한다.

### SK-M01 — §7 negative matrix와 P2-base time derivation이 닫히지 않았다 (`MAJOR`)

현재 test에는 failed-r001 mutation/extra/link/unsafe-state, existing regular file/symlink,
rename EEXIST race의 요구 subcase가 없다. Changed quick/chronology bodies에는 line 554,
767–768의 `2026-07-30` literal이 남아 있고 일부 2026-08-02 시간도 P2 base helper가 아니라
중복 literal이다. Exact S0가 없어 assertion-call-kind 비감소도 입증할 수 없다.

최소 교정: 새 revision에서 R027 §7의 각 negative state를 이름 있는 subcase로 추가하고,
모든 test-only valid/invalid timestamp를 sealed P2 base와 명시적 offset/format mutation에서
유도한다. 복구된 exact S0 AST와 method별 assertion-call-kind report를 review input으로
고정한다.

## 비권한 정적 관찰

다음 관찰은 findings를 상쇄하지 않는다.

- 세 S1 파일은 정적 AST parse가 가능하다.
- Test method는 37개이고 요구된 parent-fsync method rename은 존재한다.
- `skip`, `skipIf`, `skipUnless`, `expectedFailure`, empty/pass body는 발견되지 않았다.
- R002 bundle/gate/document/event top-level constants, P2-derived main timestamps 및 840-byte
  user directive SHA는 R027 값과 일치한다.
- Builder write primitive는 r002 staging의 create/write/fsync, `RENAME_NOREPLACE`, own-staging
  cleanup으로 제한되어 보이며 core에서 별도 filesystem write primitive는 발견되지 않았다.
- r002 final root와 staging은 검수 종료 시 absent였다.

## 종료 경계

Target, S1 세 파일 및 두 wrapper는 위 시작 binding과 종료 binding이 exact equal이었다.
이 review 이후 허용되는 조치는 새 roadmap revision 작성과 그 독립 검수뿐이다. R027의
modified test/builder/wrapper 실행, r002 publication, candidate review, activation 및 후속
Goal/product 작업에는 권한이 없다.
