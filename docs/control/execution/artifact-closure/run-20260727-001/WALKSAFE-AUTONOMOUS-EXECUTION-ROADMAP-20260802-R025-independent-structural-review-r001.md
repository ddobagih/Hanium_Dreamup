# WalkSafe R025 독립 structural review r001

```text
review_id: WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R025-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent: /root/r022_structural_review
reviewer_axis: STEP_ORDER_WRITE_ALLOWLIST_BASELINE_R002_NAMESPACE_TEST_PUBLICATION_REVIEW_CLOSURE_THREAT_AUTHORITY
target_path: docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R025.md
target_sha256: 8ee466ee1433108dd015f0318351bbcb9a0158ccdd1e4d238f991e72ea323e12
target_bytes: 11950
target_lines: 224
reviewed_at: 2026-08-02T14:05:38+09:00
status: REVISION_REQUIRED
findings: BLOCKING=4 MAJOR=0 MINOR=0
authority_granted: NONE
```

## 범위와 관측 상태

R025 224줄 전체를 accepted R016/current S0, reviewed R002 pair, immutable failed-r001,
rejected R024와 dual review, current wrappers/runtime 및 예정 r002/review/closure target에
읽기 전용으로 대조했다. 검토 시작과 review 추가 직전 target은 SHA-256
`8ee466ee1433108dd015f0318351bbcb9a0158ccdd1e4d238f991e72ea323e12`, 11,950 bytes,
224 lines인 regular file이고 mode `0664`, uid/gid `1000/1000`, nlink 1이었다.
source/build/test/checker는 실행하지 않았고 source, r001, r002, canonical, checkpoint,
Goal, 제품을 수정하지 않았다. 이 review 파일만 add-only로 추가했다.

현재 S0 세 파일과 wrapper 두 파일의 §1 SHA/bytes는 실제 파일과 일치한다.
`/usr/bin/python3.14`도 regular file이고 선언 SHA
`b8d8288faefdd300201f43fcf00f6f539a27218eeed3a3dff5ab10b9c4c99700`와 일치했다.
failed-r001 exact-six content와 NUL-name digest는 이전 동결 근거와 일치한다. R025가
거부한 supervisor 초안 identity도 실제 초안과 일치한다.

## Findings

### BLOCKING-01 — §1의 두 필수 기준선 identity가 실제 정본과 일치하지 않아 P2가 도달 불가능하다

`reviewed R002 pair` row는 존재하지 않는 다음 경로를 고정한다.

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r002-reviewed-pair.json
```

실제 reviewed pair manifest는 다음이며 선언 SHA/bytes `7d1e...c5b08 / 12,972`도 이
파일의 identity다.

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
r022-candidate-r002/gap-backlog-pair-manifest.json
```

또한 accepted R016 row의 SHA는
`49ca083c68d0f2693fa74a7369f46995dbd9e697fcaeb5917c33f1973b8dd8c2`로 적혔지만 실제
accepted R016 SHA는
`49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2`다. Bytes 12,690은
일치한다.

§4는 §1의 reviewed pair와 R016이 exact content와 일치해야만 source patch를 허용하므로
정상 현재 workspace에서도 preflight 논리곱은 항상 실패한다. 두 row를 실제 path/SHA로
교정한 새 roadmap과 dual review가 필요하다.

### BLOCKING-02 — exact write allowlist가 review path를 열거하지 않는다

R025는 source 세 파일, r002 root와 closure path는 exact하게 지정하지만 P1 plan review
두 파일 및 P4 candidate review 두 파일의 repository-relative target을 열거하지 않는다.
§4의 “두 r002 candidate review path”와 §6의 “두 검수자”만으로는 producer, absence gate,
closure binding이 같은 파일 집합에 합의할 단일 allowlist가 되지 않는다.

최소 교정은 P0~P5 전체 write set을 한 표로 고정하는 것이다. 최소 집합은 R025 roadmap,
R025 structural/skeptical plan reviews, source 3개, exact r002 root, exact r002
structural/skeptical candidate reviews와 exact closure 한 파일이다. 그 밖의 write는
daylog/local-memory 최종 handoff를 제외하고 0이어야 한다.

### BLOCKING-03 — R002 namespace와 document/event ID oracle이 suffix 규칙에 머문다

§4는 bundle/gate/document/event ID를 `r002/R002/002`로 교체하라고 하지만 exact 값으로
고정하는 것은 `DELEGATION_REQUIREMENT_ID`와 command contract version뿐이다.
`APPLICATION_GATE_REL`, package candidate ID, transaction plan ID, quick/final requirement,
history/prepared checkpoint/output/active checkpoint ID와 seq1/2/3 event ID의 exact strings가
없다. “R002 또는 002 suffix”를 만족하는 여러 값이 가능하고 intentional R022 lineage와
잘못 남은 R001을 독립적으로 구분할 oracle도 없다.

최소 교정은 R024까지 합의된 R002 namespace block을 actual R025 suffix로 완전 열거하고,
AST/string residual scan의 exact historical allowlist를 함께 고정하는 것이다. 37-test
subcase는 그 명시값을 검사해야 하며 스스로 namespace를 새로 정하면 안 된다.

### BLOCKING-04 — closure는 top-level 이름만 고정돼 future R026의 신뢰 가능한 입력이 아니다

§7은 closure top-level keys와 binding 공통 모양만 고정한다. 다음 필수 value/schema가
정의되지 않는다.

- exact `schema_version`, `closure_id`
- roadmap/plan review/candidate review row의 exact order와 verdict fields
- source binding의 pre/post 역할·순서와 wrapper 포함 여부
- candidate binding의 exact nested keys, exact-six order와 digest serialization
- five gate result row의 exact command identity, exit/output/test counters
- authority boundary의 exact key set과 field spelling
- `closed_at` 산출·검증 시 사용할 두 review 종료 timestamp binding

또한 closure를 생성한 뒤 exact schema/content를 검증하는 read-only gate가 없다. 서로
다른 closure JSON이 모두 §7을 만족할 수 있으므로 R026이 단순히 그때 생성된 hash를 pin하면
불완전하거나 거짓인 current closure를 predecessor로 승격할 수 있다. 새 roadmap은 nested
schema와 exact scalar 값을 완전히 열거하고 canonical raw를 재구성하는 closure checker를
P5 성공 oracle에 포함해야 한다.

## 나머지 구조 판정

| 축 | 판정 | 근거 |
|---|---|---|
| 단계 순서 | CLOSED_AFTER_FINDINGS | P1 dual review → preflight/one patch → prebuild 37 → one publication → post gates → candidate reviews → closure 순서는 비순환이고 명확하다. |
| threat scope | CLOSED | hostile concurrent same-UID mutation과 거짓 reviewer를 명시적으로 제외하고 관측된 content drift만 실패시키며 inode/time authority를 주장하지 않는다. |
| S0 patch | CLOSED_AFTER_BASELINE_FIX | exact three-file one-patch, wrapper unchanged, method-set unchanged와 unrelated dirty worktree 보존이 최소 변경 범위에 맞는다. |
| 37-test oracle | CLOSED | AST method-set equality와 pre/post exact 37, skip/failure/error 0, final OK가 count-only 통과를 막는다. |
| add-only publication | CLOSED | any-existing/EEXIST/partial/fsync ambiguity는 recovery·delete·resume 없이 terminal이고 유일 성공은 PUBLISHED_NEW exact-six다. |
| postbuild gates | CLOSED | post 37, builder CHECK/NOT_APPLICABLE, continuation/Goal CANDIDATE와 gate 전후 content equality가 명시된다. |
| candidate dual review | BLOCKED_BY_B02 | 입력/verdict/start-end equality는 충분하지만 exact target paths가 없어 closure allowlist를 닫지 못한다. |
| closure/handoff | BLOCKED_BY_B04 | add-only 시점과 external handoff 방향은 맞지만 closure value schema와 verifier가 유일하지 않다. |
| failed-r001 | CLOSED | exact-six content/seq1/zero-authority를 read-only 보존하고 correction/reuse/delete를 금지한다. |
| zero authority | CLOSED | activation/canonical/checkpoint/Goal/product/formal/device/release write·credit은 0이고 finding이면 P2 이후 권한이 없다. |

## 실행·write 관측 count

| item | count |
|---|---:|
| source/build/test/checker executions | 0 |
| source writes | 0 |
| r001/r002/closure writes | 0 |
| canonical/checkpoint/Goal/product writes | 0 |
| formal/device/release credit | 0 |

R025는 `0/0/0`이 아니므로 비효력 r002 실행 권한을 부여할 수 없다. 네 finding을 닫은
새 roadmap revision과 새 dual plan review가 필요하다.
