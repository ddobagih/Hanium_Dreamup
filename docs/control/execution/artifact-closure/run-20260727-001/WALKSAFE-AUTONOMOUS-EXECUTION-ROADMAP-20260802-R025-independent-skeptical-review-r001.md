# WalkSafe R025 독립 skeptical review r001

```text
review_id: WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R025-INDEPENDENT-SKEPTICAL-REVIEW-R001
reviewer_agent: /root/r022_skeptical_review
reviewer_axis: COOPERATIVE_SCOPE_BASELINE_R001_EXISTING_PARTIAL_TEST_SEMANTICS_DIRTY_WORKTREE_REVIEW_CLOSURE_EXTERNAL_AUTHORITY
target_path: docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R025.md
target_sha256: 8ee466ee1433108dd015f0318351bbcb9a0158ccdd1e4d238f991e72ea323e12
target_bytes: 11950
target_lines: 224
reviewed_at: 2026-08-02T14:08:30+09:00
status: REVISION_REQUIRED
findings: BLOCKING=4 MAJOR=0 MINOR=0
authority_granted: NONE
```

## 범위와 관측

R025 224줄 전체를 cooperative threat scope의 정직성, accepted predecessor와 current S0,
failed-r001 보존, existing/race/partial terminal, 37-test count-only 위조, dirty worktree
격리, candidate dual review, closure와 외부·권한 금지 축에서 정적 red-team 검토했다.
검토 시작과 판정 직전 target은 모두 SHA-256
`8ee466ee1433108dd015f0318351bbcb9a0158ccdd1e4d238f991e72ea323e12`,
11,950 bytes, 224 lines였다.

source/build/test/checker는 실행하지 않았다. source, r001, r002, review/closure,
canonical, checkpoint, Goal, 제품은 수정하지 않았다.

## Findings

### BLOCKING R025-SK-B01 — 두 필수 predecessor binding이 사실과 달라 정상 preflight가 항상 실패한다

§1의 reviewed R002 pair path

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r002-reviewed-pair.json
```

는 존재하지 않는다. 선언된 SHA/bytes가 실제로 가리키는 정본은 다음이다.

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
r022-candidate-r002/gap-backlog-pair-manifest.json
7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08 / 12972
```

accepted R016 SHA도 선언값
`49ca083c68d0f2693fa74a7369f46995dbd9e697fcaeb5917c33f1973b8dd8c2`가 아니라 실제
`49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2`다.
Bytes 12,690은 일치한다. §4가 두 잘못된 row의 exact equality를 P2 논리곱으로
요구하므로 현재 정상 workspace에서도 source write를 열 수 없다. 실제 path/SHA를 고친
새 revision과 새 dual review가 필요하다.

### BLOCKING R025-SK-B02 — method 이름 37개와 `Ran 37 tests`는 수정 가능한 test body의 의미를 보존하지 않는다

P2가 수정하는 세 파일 중 test module 자체가 포함된다. 현재 gate는 patch 전후 AST method
이름 집합과 count 37을 맞추고, 수정된 그 test module을 실행해 37/skip0/error0/OK를
확인한다. 그러나 기존 37개 method의 body를 `pass`, 무조건 참 assertion 또는 과도한
mock으로 약화해도 이름 집합과 count는 동일하고 runner는 정확히 `Ran 37 tests ... OK`를
출력한다. AST parse, duplicate literal, trailing whitespace scan도 이를 막지 않는다.

§4의 “subcase만 갱신”은 의도 설명일 뿐 이를 검사할 oracle이 없다. §6 candidate review의
고정 입력에는 S1 binding만 있고 S0→S1 test diff, 허용된 변경 method/body 집합, 기존
assertion 비약화 판정이 필수 축으로 들어 있지 않다. 따라서 정직한 reviewer가 명시된
체크리스트만 수행해도 count-only 후보를 PASS할 수 있다.

최소 교정은 publication 전에 S0→S1 exact diff를 별도 source review 입력으로 주고,
test 변경을 R002 namespace/provenance/time 및 명시된 negative subcase에 한정하는 것이다.
적어도 기존 method 삭제·`skip`/`expectedFailure` 추가·빈 body·기존 assertion 제거를
거부하고, P4 두 reviewer가 전체 three-file diff와 test 의미 비약화를 명시적으로
판정해야 한다. 새 method를 세지 않는 것만으로는 충분하지 않다.

### BLOCKING R025-SK-B03 — dirty worktree 불변 성공 조건에 pre/post 비교 기준이 없다

§4는 staged index를 바꾸지 않고 unrelated dirty 파일을 수정하지 말라고 하며 §9는
`UNRELATED_WORKTREE_UNCHANGED`를 성공 논리곱에 넣는다. 하지만 P2 시작 시 기존 dirty
path/type/content/index 상태를 어떤 canonical snapshot으로 잡고, 어느 exact allowlist를
제외해 종료 시 비교하는지 정의하지 않는다.

이미 modified인 tracked file을 builder나 작업자가 다시 바꿔도 최종 `git status`는 계속
`M`이므로 이름만 비교하면 drift를 놓친다. 기존 untracked file도 마찬가지다. 이는 hostile
concurrent process가 아니라 cooperative session의 우발 write를 구분하는 기본 oracle
문제다.

최소 교정은 P2 직전 NUL-safe path/type/content manifest와 index identity를 임시 메모리나
project 밖 임시 파일에 잡고, 종료 시 exact write allowlist를 제외한 모든 row가 같음을
비교하는 것이다. 전 worktree content 보존을 실용적으로 증명하지 않을 경우 성공 문구를
보호 대상 exact paths와 “새 unrelated status path 0” 수준으로 정직하게 좁혀야 한다.

### BLOCKING R025-SK-B04 — review target과 closure gate evidence가 유일하지 않아 add-only 폐쇄를 재현할 수 없다

R025는 P1 plan review 두 path와 P4 candidate review 두 path를 exact write target으로
열거하지 않는다. §4의 “두 r002 candidate review path absent”는 어느 path인지 문서만으로
계산할 수 없고, §6/P5 producer와 future R026이 동일 review 집합에 합의할 보장이 없다.

또한 closure는 top-level key 이름만 고정하고 `gate_summary`의 nested schema를 정하지
않는다. `{tests:37}` 같은 count-only 요약도 문언상 허용된다. Exact command/interpreter,
exit, parsed tests/skips/failures/errors/final status, stdout/stderr binding, gate 전후
source/candidate binding이 없으면 R026이 closure hash를 pin해도 실제 네 postbuild gate가
수행됐는지 재검증할 수 없다.

최소 교정은 P1/P4 review 네 path를 exact하게 열거하고 모두 add-only/any-existing
terminal로 묶는 것이다. Closure에는 ordered exact gate rows와 review rows의 schema,
scalar verdict, candidate exact-six order/digest serialization을 고정하고, add 뒤 canonical
raw를 재구성하는 read-only closure check를 성공 조건에 넣어야 한다. 이는 새 암호 protocol이
아니라 현재 주장한 로컬 실행 결과를 모호하지 않게 기록하는 최소 schema다.

## 나머지 공격 축

| 축 | 판정 | 근거 |
|---|---|---|
| cooperative threat scope | CLOSED | hostile concurrent same-UID, runtime/filesystem compromise와 고의 허위 reviewer를 명시적으로 제외하고 inode/time 권위를 주장하지 않는다. 일반 단일 작업자 범위로 정직하다. |
| failed-r001 보존 | CLOSED | exact-six content, old test binding, seq1-only/zero-authority를 pre/post read-only 확인하고 correction/delete/reuse를 금지한다. |
| r002 existing/race/partial | CLOSED | any-kind existing, rename EEXIST, partial과 parent-fsync ambiguity는 공개 target repair/delete/resume 없이 new-revision terminal이며 own staging만 정리한다. |
| source write 범위 | CLOSED_AFTER_B01 | one apply_patch와 exact core/builder/test 3개, wrapper unchanged가 명확하나 predecessor pin을 먼저 고쳐야 한다. |
| runtime gate result | BLOCKED_BY_B02_B04 | 실제 verbose unittest와 post wrappers를 요구하지만 mutable test semantics와 closure result schema가 authority를 약화한다. |
| candidate dual review | BLOCKED_BY_B02_B04 | honest reviewer 가정은 명시적이지만 exact target과 required full source-diff/test-strength axis가 빠져 있다. |
| closure/handoff | BLOCKED_BY_B04 | 방향은 noncyclic이고 external signature를 주장하지 않지만 future input schema가 유일하지 않다. |
| 금지 외부·권한 행위 | CLOSED | canonical/checkpoint/Goal/product/formal/device/event/release credit과 push/PR/deploy/기관 제출/유료 서비스/secret 사용을 0으로 고정하고 finding 시 P2 이후 authority를 닫는다. |

## 명시적 판정

네 blocking finding 때문에 R025의 non-effective r002 execution authority를 부여할 수
없다. 실제 predecessor bindings, test 의미 보존, dirty baseline, exact review/closure
schema를 고친 새 roadmap revision과 새 dual review가 필요하다.

실행·write 관측 count는 source/build/test/checker 0, source 0, r001/r002/closure 0,
canonical/checkpoint/Goal/product 0, formal/device/release credit 0이다.
