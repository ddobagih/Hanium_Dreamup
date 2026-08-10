# WalkSafe R026 독립 skeptical review r001

```text
review_id: WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R026-INDEPENDENT-SKEPTICAL-REVIEW-R001
reviewer_agent: /root/r022_skeptical_review
reviewer_axis: R025_BLOCKERS_TEST_SEMANTICS_DIRTY_MANIFEST_OPERATION_KIND_R001_EXISTING_PARTIAL_REVIEWS_NO_CLOSURE_EXTERNAL_AUTHORITY
target_path: docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R026.md
target_sha256: b5485b82c679824ca8e5a53e441bd0750e09cbad5c9ecef090224191db3db479
target_bytes: 19518
target_lines: 339
reviewed_at: 2026-08-02T14:17:20+09:00
status: REVISION_REQUIRED
findings: BLOCKING=1 MAJOR=1 MINOR=0
authority_granted: NONE
```

## 범위와 target identity

R026 339줄 전체를 R025 skeptical 네 blocker, modified-test semantic weakening,
dirty-worktree manifest, exact paths/IDs, failed helper 폐기, failed-r001 보존,
existing/partial publication, source/candidate reviews, closure 제거와 외부·권한 금지 축에서
정적 red-team 검토했다. 검토 시작과 판정 직전 target은 모두 SHA-256
`b5485b82c679824ca8e5a53e441bd0750e09cbad5c9ecef090224191db3db479`,
19,518 bytes, 339 lines였다.

source/build/test/checker는 실행하지 않았다. source, helper, r001, r002, reviews,
canonical, checkpoint, Goal, 제품은 수정하지 않았다.

## R025 skeptical blocker 교정 판정

| R025 blocker | 판정 | R026 교정 |
|---|---|---|
| false reviewed-pair/R016 bindings | CLOSED | 실제 pair/review path와 R016 roadmap/review SHA/bytes를 exact하게 고정한다. |
| count-only test weakening | CLOSED | pinned external S0 raw, full three-file diff, exact renamed/changed method allowlist, unchanged-method AST equality, assertion-kind nondecrease와 publication 전 dual source review를 요구한다. |
| dirty worktree oracle 부재 | CLOSED_EXCEPT_M01 | repository-wide NUL-safe content manifest와 separate index binding이 기존 `M`/`??` 내부 content drift를 잡는다. 허용 daylog write만 M01로 빠진다. |
| review/closure ambiguity | CLOSED | P1/P3/P5 exact paths, exact IDs, ordered gate/review schemas를 고정하고 ambiguous closure를 제거한다. Future R027은 각 predecessor를 독립 pin/recheck한다. |

Modified test의 assertion-call count 자체는 semantic proof가 아니지만, R026은 그것만으로
PASS하지 않는다. 두 honest source reviewer가 pinned S0와 S1 full diff를 publication 전에
읽고 허용된 method/body와 기존 assertion 의미 비약화를 판정한다. 고의 허위 reviewer를
명시적으로 범위 밖에 둔 cooperative 모델에서는 이 추가 독립 gate가 count-only 우회를
닫는다.

## Findings

### BLOCKING R026-SK-B01 — §3 existing-target terminal이 기존 S0 세 파일 update와 모순된다

§3은 exact write allowlist를 제시한 직후 다음처럼 규정한다.

```text
Existing target은 roadmap과 기존 daylog를 제외하고 모두 terminal이며
overwrite/repair/delete/resume하지 않는다.
```

그러나 같은 표의 P2 target 세 개는 absent add-only target이 아니라 반드시 존재하고 §1
S0와 exact-equal해야 하는 source 파일이다. §5는 바로 그 세 파일을 한 `apply_patch`로
수정하라고 한다. 문언대로라면 정상 S0 존재가 P2 terminal인 동시에 P2 성공 전제여서 실행
가능 상태가 없다. “overwrite”도 일반적인 existing-file patch를 포함하는지 generated
artifact repair만 뜻하는지 구분되지 않는다.

최소 교정은 §3 각 target에 operation kind를 고정하는 것이다.

```text
P0 = EXISTING_PINNED_ROADMAP
P1/P3/P4/P5 = ADD_ONLY_ABSENT_OR_TERMINAL
P2 three sources = UPDATE_EXISTING_EXACT_S0_ONCE
daylog = APPEND_EXISTING_EXACT_PREFIX_ONCE
```

Existing/EEXIST/partial/no-repair terminal은 P1/P3/P4/P5 generated targets에 적용하고,
P2는 S0 mismatch·unexpected extra source delta일 때 terminal이라고 분리해야 한다.

### MAJOR R026-SK-M01 — dirty manifest에서 제외한 기존 daylog의 append-only preimage가 보호되지 않는다

§5 manifest는 기존 `daylog/2026-08-02.md` 전체를 제외하고, §3/§9는 종료 때 이 파일에
section 하나를 append하도록 허용한다. 하지만 시작 daylog SHA/bytes 또는 raw prefix를
보유하고, 종료 bytes가 정확히 `original_prefix + one_expected_section`인지 검사하는 계약은
없다. 따라서 기존 daylog를 truncate·rewrite하고 새 section을 넣어도 unrelated manifest
digest는 동일하고 handoff 단계가 성공할 수 있다. 이는 hostile concurrent mutation이
아니라 허용 writer의 우발적 기존 기록 훼손이다.

최소 교정은 manifest에서 daylog를 제외하는 대신 별도 preimage binding을 메모리에 잡고,
append 뒤 original bytes가 exact prefix이며 허용 suffix가 정확히 한 번 추가됐는지 확인하는
것이다. 또는 daylog write를 R026 성공 conjunction 뒤 parent-only handoff로 명확히 분리하고
R026 자체 성공 근거에서 제외해야 한다.

## 나머지 공격 축

| 축 | 판정 | 근거 |
|---|---|---|
| cooperative threat scope | CLOSED | hostile same-UID/runtime/filesystem compromise와 고의 허위 reviewer를 범위 밖으로 명시하고 관측 content equality만 주장한다. |
| exact baseline/paths/IDs | CLOSED | pair/R016/S0/wrappers, R002 paths와 모든 construction/event IDs, historical allowlist 및 P0~P5 paths가 exact하다. |
| failed-r001 | CLOSED | exact-six/name digest/test binding/seq1/zero-authority를 read-only로 매 경계 검사하고 repair/delete/reuse하지 않는다. |
| helper 폐기 | CLOSED | 실패 helper의 exact identity만 역사로 남기고 import/execute/modify/successor creation을 모두 0으로 둔다. |
| r002 existing/partial | CLOSED_AFTER_B01_WORDING_FIX | any-kind target, EEXIST, partial, parent-fsync ambiguity는 public target repair/delete/resume 없이 terminal이고 own staging만 정리한다. |
| source semantic gate | CLOSED | pinned backup raw와 two-reviewer full diff gate가 modified test 자체의 self-validation만 신뢰하지 않는다. |
| runtime/candidate reviews | CLOSED | exact environment/argv/37 counters, deterministic exact-six, four ordered gate rows와 start/end bindings을 두 reviewer가 재검사한다. |
| no closure | CLOSED | 불완전 closure 대신 R027이 roadmap/P1/S1/P3/exact-six/P5를 독립 pin하도록 해 순방향 handoff를 유지한다. |
| 외부·권한 금지 | CLOSED | commit/push/PR/deploy/기관 제출/paid/secret과 activation/canonical/checkpoint/Goal/product/formal/device/event/release 권한을 0으로 고정한다. |

## 명시적 판정

한 blocking과 한 major finding 때문에 R026의 non-effective r002 execution authority를
부여할 수 없다. Exact operation-kind 분리와 daylog prefix 보존을 명시한 새 revision 및
새 dual review가 필요하다.

실행·write 관측 count는 source/build/test/checker 0, source/helper 0, r001/r002/review 0,
canonical/checkpoint/Goal/product 0, formal/device/release credit 0이다.
