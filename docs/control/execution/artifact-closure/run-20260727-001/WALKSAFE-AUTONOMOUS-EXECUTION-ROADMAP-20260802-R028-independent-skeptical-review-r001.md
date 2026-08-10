# WalkSafe R028 independent skeptical review r001

- review_id: `WS-V25-R028-INDEPENDENT-SKEPTICAL-REVIEW-R001`
- reviewer_agent: `/root/r028_plan_skeptical_review`
- reviewer_axis: `ADVERSARIAL_RECOVERY_ORACLE_DIRTY_BOUNDARY_TEST_SEMANTICS_AUTHORITY`
- target_path: `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R028.md`
- target_sha256: `0a83406951f4963405035649a52675e781f706cbd11bac6d0060540a8567f6db`
- target_bytes: `20142`
- reviewed_at: `2026-08-02T15:08:46.009262+09:00`
- status: `REVISION_REQUIRED_BEFORE_P2`
- findings: `BLOCKING=4 MAJOR=0 MINOR=0`
- authority_granted: `NONE_FOR_RECOVERY_SOURCE_EXECUTION_PUBLICATION_ACTIVATION_GOAL_PRODUCT`
- reviewed_source_module_import_count: `0`
- source_test_builder_execution_count: `0`
- compile_count: `0`

## 결론

R028가 가리키는 transcript와 현재 S1에서 exact S0 세 파일을 복원할 수 있다는 사실 자체는
독립 검산됐다. 그러나 R028 §4가 복원 성공 조건으로 명시한 hunk별 `유일 위치`가 실제
patch에서 성립하지 않는다. 따라서 문서 그대로 실행하면 P2가 terminal이며, 이를 무시하고
first-match를 사용하면 검수된 계획과 다른 알고리즘을 실행하게 된다. Dirty exclude set과
test assertion oracle에도 서로 다른 executor/reviewer가 다른 판정을 낼 수 있는 모순이 있다.
P2 backup, P3 source update, source/build/test/wrapper 실행과 publication은 새 revision의 dual
PASS 전까지 0이어야 한다.

## 검수 경계와 일치한 물리 근거

검수 시작과 이 문서 작성 직전 target은 동일한 regular file이었다.

| boundary | SHA-256 | bytes |
|---|---|---:|
| start | `0a83406951f4963405035649a52675e781f706cbd11bac6d0060540a8567f6db` | 20,142 |
| end | `0a83406951f4963405035649a52675e781f706cbd11bac6d0060540a8567f6db` | 20,142 |

현재 failed S1도 시작/종료에 R028 §1과 같았다.

| role | SHA-256 | bytes |
|---|---|---:|
| core | `926f2a997692aff9e996458f3099515fb55bf51ee9171197c4ea27bcdf0b57f1` | 187,211 |
| builder | `2960f1b6e49cd2dbd8904de15fe23327eb3b5959efccc68a42be564532c0e96a` | 48,374 |
| test | `874c89e04d67ef2c27709e84917ee01a98faa73405b983be2a299abc72a834c5` | 70,163 |

Transcript full file, call/output raw line과 decoded patch의 SHA/bytes/count는 §4 pin과 모두
일치했다. Call/output record는 각각 정확히 하나이고 patch는 core 25, builder 13, test 10개의
bare `@@` hunk를 가진 exact 세 `Update File` section이다. Source를 import·execute·compile하지
않고 raw line stream에 ordered inverse/forward dry parser만 적용했다.

First exact match를 택하면 복원 결과는 core
`e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f / 180432`,
builder `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175 / 51153`,
test `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523 /
69685`로 §1 S0와 같고 forward 결과도 S1과 같다. Exact-ten backup member 목록과 manifest
self-exclusion에 따른 nine-row projection, add-only final-root/no-retry 순서와 zero-authority
경계도 그 전제가 고쳐진다면 구조적으로 일관된다.

## BLOCKING findings

### B-01 — ordered inverse의 hunk별 유일성 조건이 실제 transcript에서 거짓이다

R028 §4는 각 hunk의 context와 forward `+` postimage가 monotone cursor 뒤 정확히 한 곳에
있어야 하고 ambiguous match는 terminal이라고 정한다. Exact test section의 두 hunk가 이를
위반한다.

- 10개 `@@` 블록 기준 hunk 2의 postimage `for binding_name in (` 한 줄은 cursor 417 뒤
  0-based line position 1195와 1217 두 곳에 일치한다.
- hunk 7(변경 hunk만 세면 6번째)의 6-line postimage는 cursor 1346 뒤 position 1411과 1471
  두 곳에 일치한다.

실제 `apply_patch`의 stateful first-match 동작은 이 중 앞 위치를 선택해 S1을 만들 수 있지만,
그것은 `유일 위치`가 아니다. 더구나 S0→S1 forward dry apply에서도 core hunk 9의 1-line
preimage가 두 곳, hunk 12가 18곳, hunk 14가 두 곳에 일치한다. Final SHA round-trip이 선택을
사후 확인할 수는 있어도 문서가 명시한 per-hunk uniqueness를 참으로 바꾸지는 않는다.

최소 교정: 새 roadmap에서 (a) exact first-match-after-cursor와 final S0/S1 pin round-trip을
명시적으로 승인하거나, (b) 각 ambiguous hunk에 구별 context/ordinal을 결속한 reconstruction
recipe를 제공한다. 어느 쪽이든 dry parser 규칙과 ambiguity 판정을 하나로 고정한다.

### B-02 — dirty manifest의 P5 staging 포함 여부가 자기모순이다

§4 첫 문단은 source 세 개와 `P4/P5/P6 targets`를 제외한다고 한다. §2의 P5 target에는 exact
owned staging과 final r002 둘 다 포함된다. 반면 같은 §4 마지막 문장은 staging을 제외하지
않아 잔존 staging이 equality를 실패시킨다고 한다. 전자를 따르면 leftover staging이 baseline과
end manifest 모두에서 사라져 성공을 가장할 수 있고, 후자를 따르면 검출된다.

최소 교정: wildcard epoch 표현 대신 baseline에서 제외할 exact relative path/prefix 목록을
완전히 열거하고, staging regex의 모든 entry는 명시적으로 포함하며 별도 absence check도
conjunctive하게 요구한다.

### B-03 — renamed fsync test의 S0 assertion lower bound가 정의되지 않았다

정적 AST 재계산 결과 S0와 S1은 각각 37 methods지만 이름 집합은 같지 않다. S0-only는
`test_add_only_parent_fsync_failure_recovers_exact_target`, S1-only는
`test_add_only_parent_fsync_failure_leaves_target_and_retry_is_terminal`이다. §5는 S1→S2 rename
0을 요구하면서도 모든 changed method의 assertion-call kind count를 S0와 S1 양쪽보다 낮추지
말라고 한다. 새 이름은 S0에 없으므로 absent를 zero로 볼지 old predecessor body와 매핑할지
정해지지 않아 P4 oracle이 vacuous해질 수 있다. Old S0 body에는 `assertEqual=1`,
`assertRaisesRegex=1`, `assertTrue=1`; S1 new body에는 `assertRaisesRegex=2`, `assertTrue=1`이 있다.

최소 교정: old→new exact rename mapping을 명시하고, 이 method를 포함한 여덟 changed method의
per-assertion-kind 최소 count 표를 고정한다.

### B-04 — exact call-input suffix 표기가 raw payload와 byte-equal하지 않다

Roadmap file bytes는 suffix를
`;&#10;const result = await tools.apply_patch(patch);&#10;text(result);`로 적는다. Decoded
`payload.input`은 `;` 뒤와 두 statement 사이에 literal U+000A가 있고 `&#10;` 여섯 ASCII
characters는 없다. HTML entity decode를 적용하라는 규칙도 없으므로 strict exact parser는
거부하고, 임의 entity decode parser만 통과한다.

최소 교정: `\n`이 U+000A를 뜻한다는 encoding rule을 명시한 suffix 또는 suffix 자체의
SHA/bytes를 제공하고, JSON-string extraction 전후 어느 representation을 비교하는지 고정한다.

## 비권한 관찰과 종료 판정

R027/source-review/S1/wrapper/C0/daylog pins, r002 final·staging과 P4/P6 review absence, external
recovery root absence는 검수 시 물리 상태와 일치했다. 이는 finding을 상쇄하지 않으며 recovery,
source correction, runtime gate, publication, activation, canonical/checkpoint, Goal, product,
배포 권한을 부여하지 않는다. R028는 `PRE_REVIEW`에서 revision-required로 종료되어야 한다.
