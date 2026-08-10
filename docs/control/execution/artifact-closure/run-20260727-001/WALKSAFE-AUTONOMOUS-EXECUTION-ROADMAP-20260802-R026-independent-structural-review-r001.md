# WalkSafe R026 독립 structural review r001

```text
review_id: WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R026-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent: /root/r022_structural_review
reviewer_axis: R025_FINDING_CLOSURE_WRITE_ALLOWLIST_DIRTY_DIGEST_R002_NAMESPACE_SOURCE_RUNTIME_PUBLICATION_CANDIDATE_REVIEW_HANDOFF_AUTHORITY
target_path: docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R026.md
target_sha256: b5485b82c679824ca8e5a53e441bd0750e09cbad5c9ecef090224191db3db479
target_bytes: 19518
target_lines: 339
reviewed_at: 2026-08-02T14:17:40+09:00
status: REVISION_REQUIRED
findings: BLOCKING=1 MAJOR=0 MINOR=0
authority_granted: NONE
```

## 범위와 관측 상태

R026 339줄 전체를 읽고 accepted R016, reviewed pair, R025와 dual review, 현재 source
세 파일, builder의 publication 경로 및 예정 review/candidate target에 읽기 전용으로
대조했다. 검토 시작과 review 추가 직전 target은 SHA-256
`b5485b82c679824ca8e5a53e441bd0750e09cbad5c9ecef090224191db3db479`, 19,518 bytes,
339 lines인 regular file이고 mode `0664`, uid/gid `1000/1000`, nlink 1이었다.
source/build/test/checker는 실행하지 않았고 source, failed-r001, r002, canonical,
checkpoint, Goal, 제품을 수정하지 않았다. 이 review 파일만 add-only로 추가했다.

R025가 지적한 factual pin 두 건은 실제 reviewed pair path와 R016 SHA로 교정됐다.
R025 structural/skeptical review binding도 실제 파일과 일치한다. R002 construction
namespace는 bundle/gate/document/requirement/history/checkpoint/output/event ID까지 완전
열거됐고, ambiguous closure는 제거되어 R027이 roadmap, plan/source/candidate reviews,
S1 및 exact-six를 각각 독립 pin하고 gate를 재실행하도록 바뀌었다.

## Finding

### BLOCKING-01 — P4 publication의 필수 staging write가 §3 exact write allowlist 밖이다

§3은 표 밖 project write를 0으로 선언하고 P4에는 다음 final target만 허용한다.

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
v2-5-control-candidate-r002/ exact six
```

그러나 §6은 `only own staging may be cleaned`라고 명시하고, 실제 builder publication
프로토콜은 final target의 sibling에 다음 형태의 directory를 먼저 생성한 뒤 exact-six를
그 안에 쓰고 `renameat2(RENAME_NOREPLACE)`로 final target에 publish한다.

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
.v2-5-control-candidate-r002.staging-<pid>-<token>/
```

이 sibling은 `v2-5-control-candidate-r002/` root prefix 아래가 아니다. 따라서 정상적인
유일 publication 경로도 §3의 `아래 외 project write는 0`을 위반한다. 반대로 표를
엄격히 따르면 builder를 실행할 수 없어 `R002_PUBLISHED_NEW_EXACT_SIX`에 도달할 수 없다.
이는 결과 hash 문제가 아니라 계획의 허용 write 집합과 필수 state transition 사이의
직접 모순이다.

새 revision은 P4 transient staging을 allowlist에 포함해야 한다. 최소한 parent, exact
basename grammar, fresh entry count 1, directory/member type, exact-six member 집합,
owner-created 조건, rename/cleanup 권한과 성공 종료 시 staging 부재를 고정해야 한다.
또는 staging을 쓰지 않는 publication 알고리즘과 그 failure semantics를 새로 명시해야
한다. 새 roadmap과 dual plan review 전에는 P2 이후 write 및 source/build/test 실행 권한이
없다.

## R025 차단점 및 구조 축 판정

| 축 | 판정 | 근거 |
|---|---|---|
| R025 factual pins | CLOSED | reviewed pair actual path와 R016 actual SHA/bytes가 교정됐고 R025 review binding도 일치한다. |
| R025 full write allowlist | NOT_CLOSED | stable P0~P5 target은 열거됐지만 필수 transient P4 staging target이 빠졌다. |
| R025 full R002 IDs | CLOSED | current construction path, contract, document 및 seq1/2/3 ID가 exact string으로 완전 열거됐다. |
| R025 closure ambiguity | CLOSED | closure manifest를 만들지 않고 R027이 모든 predecessor binding을 독립 pin하고 gate를 재실행한다. |
| dirty worktree digest | CLOSED | cooperative-local threat scope에서 type/mode/content/link/index를 NUL-safe row로 baseline/end 비교하고 업무 target만 제외한다. 성공 시 staging은 없어져야 하므로 allowlist 교정 뒤 equality oracle과 양립한다. |
| source semantic dual review | CLOSED | S0 archive binding, three-file full diff, test AST/method/assertion 보존, wrapper equality와 실행 전 두 독립 review가 고정됐다. |
| runtime/publication gates | BLOCKED_BY_FINDING | runtime, pre/post 37 tests, one-shot PUBLISHED_NEW, exact-six와 deterministic rebuild는 충분하나 허용되지 않은 staging write가 선행된다. |
| candidate dual review | CLOSED_AFTER_FINDING | 두 reviewer가 four gates를 재실행하고 source/review/candidate start-end binding과 exact output schema를 기록한다. |
| no-closure handoff | CLOSED | daylog/local-memory는 binding 전달만 하고 후속 revision이 독립 검증하므로 handoff 자체가 authority가 아니다. |
| zero authority | CLOSED | activation/canonical/checkpoint/Goal/product/deploy/formal credit은 모두 0이며 finding 시 P2 이후 진행도 0이다. |

## 실행·write 관측 count

| item | count |
|---|---:|
| source/build/test/checker executions | 0 |
| source writes | 0 |
| failed-r001/r002 writes | 0 |
| canonical/checkpoint/Goal/product writes | 0 |
| formal/device/release credit | 0 |

R026은 `BLOCKING=0 MAJOR=0 MINOR=0`이 아니므로 비효력 r002 실행 권한을 부여할 수
없다. Staging lifecycle을 exact write allowlist와 일치시킨 새 roadmap revision 및 새 dual
plan review가 필요하다.
