# WalkSafe R029 independent skeptical review r001

- review_id: `WS-V25-R029-INDEPENDENT-SKEPTICAL-REVIEW-R001`
- reviewer_agent: `/root/r028_plan_skeptical_review`
- reviewer_axis: `RECOVERY_STATE_MACHINE_DIRTY_SUBTREE_TEST_ORACLE_BACKUP_AUTHORITY`
- target_path: `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R029.md`
- target_sha256: `5d02a6332ca79fc5a33143d92dbbe1f20aa22cb55046fd4a174caf7844b45c58`
- target_bytes: `20138`
- target_lines: `306`
- reviewed_at: `2026-08-02T15:21:27.648686+09:00`
- status: `REVISION_REQUIRED_BEFORE_P2`
- findings: `BLOCKING=2 MAJOR=1 MINOR=0`
- authority_granted: `NONE_FOR_RECOVERY_SOURCE_EXECUTION_PUBLICATION_ACTIVATION_GOAL_PRODUCT`
- reviewed_source_module_import_count: `0`
- source_test_builder_execution_count: `0`
- compile_count: `0`

## 결론

R029의 payload byte binding과 leftmost inverse/reverse-record round-trip은 실제 transcript와
S1에서 결정적으로 재현됐고 R028의 local ambiguity, forward placement, assertion rename mapping,
HTML suffix 문제는 닫혔다. 그러나 dirty exclusion이 final root directory entry 자체를 결속하지
않고, test AST 보존 계약은 임의의 기존 helper 변경으로 우회할 수 있다. Exact backup의 내부
directory permission contract도 빠져 있다. 따라서 R029는 아직 P2로 진행할 수 없고 새 revision의
dual PASS가 필요하다.

## 시작·종료 identity와 정적 검산

Target은 검수 시작과 이 문서 작성 직전 동일한 regular file이었다.

| boundary | SHA-256 | bytes | lines |
|---|---|---:|---:|
| start | `5d02a6332ca79fc5a33143d92dbbe1f20aa22cb55046fd4a174caf7844b45c58` | 20,138 | 306 |
| end | `5d02a6332ca79fc5a33143d92dbbe1f20aa22cb55046fd4a174caf7844b45c58` | 20,138 | 306 |

Live S1도 시작/종료에 §1과 같았다.

| role | SHA-256 | bytes |
|---|---|---:|
| core | `926f2a997692aff9e996458f3099515fb55bf51ee9171197c4ea27bcdf0b57f1` | 187,211 |
| builder | `2960f1b6e49cd2dbd8904de15fe23327eb3b5959efccc68a42be564532c0e96a` | 48,374 |
| test | `874c89e04d67ef2c27709e84917ee01a98faa73405b983be2a299abc72a834c5` | 70,163 |

Strict JSONL 정적 parse로 session/call/output/payload/prefix/JSON-string/suffix/decoded-patch의
SHA/bytes와 suffix raw UTF-8 hex가 §4와 모두 일치했다. Call/output은 각각 하나였다. R029 exact
state machine을 적용해 core/builder/test에서 각각 25/13/10 edit records를 얻었고 recovered S0는
각각 다음과 같았다.

- core: `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f / 180432`
- builder: `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175 / 51153`
- test: `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523 / 69685`

Test hunk 2와 7의 two-match는 명시된 leftmost start로 결정됐고, edit records를 역순으로 exact
recorded start에 적용한 결과 세 S1 raw bytes와 모두 같았다. Source/import/execute/compile,
unittest, builder와 wrapper 실행은 0이다. R028 reviews, wrappers, C0, daylog preimage와 target
absence도 §1/§4와 일치했다.

## BLOCKING findings

### B-01 — final-root prefix가 새 root directory row 자체를 제외하지 않는다

§4는 NUL walker가 directory도 `path/type/permission` row로 포함한다고 정한다. Exclusion line은
trailing slash를 가진
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/`만
`exact final-root prefix`로 제시한다. 이 byte prefix는 descendants에는 일치하지만 walker가
생성하는 root directory path
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002`에는
일치하지 않는다. Baseline에는 root가 absent이고 성공 종료에는 그 directory row가 생기므로,
literal prefix predicate라면 unrelated equality는 항상 실패한다. 반대로 executor가 임의로
trailing slash를 제거해 equality까지 제외하면 문서에 없는 predicate를 사용한다.

이는 staging을 manifest에 포함한다는 R028 B-02의 핵심은 닫았지만 final subtree exclude
algorithm은 아직 단일하지 않다는 뜻이다.

최소 교정: final root를 `rel == FINAL_ROOT or rel.startswith(FINAL_ROOT + b"/")`로 제외한다고
byte-level predicate를 명시한다. Staging은 두 절 모두에 해당하지 않으며 별도 absence conjunction을
유지한다.

### B-02 — unrestricted non-test helper 변경이 unchanged-test oracle을 우회한다

§5는 여덟 test body 밖에 `non-test fixture helper` 전체를 변경 가능 영역으로 둔다. Exact helper
name, add-only 여부와 기존 helper AST 불변 조건이 없다. Current test에는 `setUpClass`,
`_authorized_projection`, `_validate_authorization`, `_reseal_final` 같은 공통 helper가 있다. 이 중
하나를 바꾸면 나머지 29개 test method AST와 여덟 method의 assertion-call count를 그대로 유지한
채 fixture input, expected projection 또는 validation call을 바꿀 수 있다. 따라서 method-level
AST equality와 assertion table만으로는 required negative matrix가 실제 production path를
검사한다는 oracle이 비공허하지 않다.

P4의 full-diff review가 이 위험을 발견할 수 있다는 사실은 허용 범위 자체가 여러 구현을
승인하는 문제를 제거하지 않는다.

최소 교정: 기존 non-test helper/import/top-level AST는 S1 exact equal로 두고, 필요한 새 helper의
exact 이름과 add-only body purpose를 열거한다. 새 helper는 raw failed-r001 copy/snapshot처럼
fixture 준비만 하며 assertion/production callable monkeypatch 또는 validation 결과 대체를
금지한다. P4가 helper call sites와 생산 코드 도달성을 명시적으로 확인하게 한다.

## MAJOR finding

### M-01 — exact-ten backup의 descendant directory mode가 정의되지 않았다

§2는 P2 root만 mode `0700`, regular members만 `O_EXCL|O_NOFOLLOW` mode `0600`으로 고정한다.
하지만 exact-ten tree를 만들기 위한 `transcript/`, `failed-s1/scripts`, `failed-s1/tests`,
`recovered-s0/scripts`, `recovered-s0/tests` 등 descendant directory의 mode와 creation/type
predicate는 없다. §4의 종료 재검사도 `exact tree/type`만 말하고 directory mode 기대값을
제시하지 않는다. 따라서 umask에 따라 서로 다른 backup이 모두 성공할 수 있고 recovery bytes의
접근 경계가 root mode에만 우연히 의존한다.

최소 교정: root와 모든 descendant directory를 `mkdir` exclusive mode `0700`으로 만들고 lstat
nofollow로 directory/type/mode를 재검사하며, exact directory set도 manifest 또는 명시적 tree
contract에 결속한다.

## 닫힌 R028 findings와 권한 경계

- R028 B-01/B-02: leftmost-after-cursor inverse와 reverse recorded-start round-trip이 실제
  25/13/10 records 및 S0/S1 raw equality로 재현됐다.
- R028 B-03: old→new fsync method mapping과 여덟 method의 elementwise assertion lower-bound
  table가 명시됐다. 다만 B-02의 helper loophole은 별도 문제다.
- R028 B-04: payload/prefix/JSON string/suffix SHA/bytes와 raw suffix hex가 실제 payload와 같다.
- R028 dirty staging conflict: staging은 exclusion set에서 명시적으로 빠지고 absence gate도 있다.
  다만 B-01의 final-root row 결함이 남는다.

Recovery backup, source correction, runtime gates, publication, activation, canonical/checkpoint,
Goal, product와 배포 권한은 부여하지 않는다. R029 §3에 따라 finding이 있는 현재 상태에서 P2
이후 write와 source/build/test 실행은 0이어야 한다.
