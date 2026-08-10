# WalkSafe R021 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R021-INDEPENDENT-SKEPTICAL-REVIEW-R001
type = INDEPENDENT_SKEPTICAL_STATIC_REVIEW
reviewer_agent = /root/r021_skeptical_review
reviewer_axis = I28_S0_S1_P2C_T1_COUPLED_SUBSTITUTION_CYCLE_CRASH_R002_READ_ONLY_ZERO_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R021.md
target_sha256 = b65d0242656b5898392fcab2b168832a6ce19577fa5e469cbbce2cfda777b919
target_bytes = 28757
target_lines = 601
reviewed_at = 2026-08-02T12:29:35+09:00
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
blocking = 1
major = 0
minor = 0
authority_granted = NONE
```

## 범위와 동결 identity

R021 601줄 전체를 exact I 28 rows, S0의 pre-P2B live 범위, P2A committed
one-shot/T0 handoff와 source constants, P2C schema·serialization·S1 persistence,
self/future cycle, crash/resume, R002 namespace·retry·root read-only, test oracle와
zero-authority 축에서 정적 red-team 검토했다. target은 검토 시작 시 SHA-256
`b65d0242656b5898392fcab2b168832a6ce19577fa5e469cbbce2cfda777b919`,
28,757 bytes, 601 lines였다.

source/build/test/candidate checker는 실행하지 않았다. source, r001, r002, canonical,
checkpoint, Goal, 제품은 수정하지 않았다.

## Findings

### BLOCKING R021-SK-B01 — P2C 최초 identity가 P3 전까지 고정되지 않아 source+receipt 결합 치환이 통과한다

R021은 P2C 공개 뒤 `validate_source_transition_evidence(root)`가 현재 P2C의
schema/content와 현재 S1의 full tuple/hash/bytes 일치를 검사하고, 나중에 생성되는 r002
package/output manifest가 그때의 P2C SHA/bytes를 동적으로 bind한다고 한다. 그러나 P2C
add 직후의 expected SHA/bytes 또는 full no-follow tuple T1을 source 상수, supervisor
handoff, 별도 predecessor artifact 어디에도 고정하지 않는다. validator 인자도 `root`
뿐이며, 검사기가 현재 P2C에서 읽은 값을 expected baseline으로 삼지 못하게 하는 계약이
없다.

따라서 다음 결합 치환이 문서상 검사를 통과할 수 있다.

```text
P2B: legitimate S1-A 생성
P2C: S1-A rows를 담은 receipt A 공개
공격: capture T0 상수는 유지한 채 source를 S1-B로 다시 수정
공격: P2C를 S1-B rows와 새 captured_ns를 담은 valid receipt B로 교체
검사: current receipt B schema/binding PASS, live S1-B == B source_postimage PASS
P3: r002 package/output가 current receipt B SHA/bytes를 동적으로 채택
결과: 허용된 단 한 번의 S0→S1-A correction이 아닌 S1-B가 정상 postimage로 승격
```

P2C의 physical tuple을 authority input에서 제외한다는 §8 설명은 same-byte inode
replacement가 S1/I/T0의 *내용 의미*를 바꾸지 않는다는 점만 설명한다. 최초 add-only
identity를 보존하지 않으므로 위의 different-byte coupled substitution을 막지 못한다.
더 작게는 `captured_ns`만 바꿔 다시 serialize한 receipt도 현재 schema/live-S1 검사에
맞고, §8의 "P2C content drift는 hash/schema/S1 검사로 실패"에서 비교할 사전 expected
hash는 r002 생성 전에는 존재하지 않는다. crash 뒤 existing P2C를 새 process가 현재
baseline으로 재채택하는 것도 같은 결함이다.

또한 §8은 P2A와 달리 canonical JSON의 UTF-8/sorted keys/compact separators/duplicate-key/
terminal-LF 규칙을 지정하지 않는다. `capture_receipt_binding`과
`source_preimage_binding`의 exact nested key/value schema, `source_postimage` 각 row의
exact keys/null fields/ordinal 규칙, subarray digest bytes의 terminal-LF 여부도 완전히
고정하지 않았다. r002의 "package/output manifest에 동적으로 bind" 역시 exact JSON
pointer, exact-one role/path entry, 양쪽 manifest의 동일 binding 조건이 없다. 그러므로
설령 치환 공격이 없더라도 독립 producer와 validator가 하나의 P2C bytes 및 하나의
candidate binding oracle에 합의한다는 보장이 없다.

최소 교정은 successor roadmap에서 다음을 모두 명시하는 것이다.

- P2C exact nested schema와 canonical byte serialization, S0-array digest serialization을
  byte 단위로 고정한다.
- P2C add 직후 같은 supervisor가 content/schema/S1을 재검증하고 exact
  `P2C_SHA256`, `P2C_BYTES`, `P2C_LSTAT_T1`을 no-follow로 관측한다.
- 그 supervisor가 T1을 P3 publication 완료까지 메모리에 보유하고 모든 builder/checker
  process에 expected input으로 명시적으로 주입한다. 어떤 process도 current P2C에서
  expected T1을 재구성해서는 안 된다.
- P3 직전까지 I/T0/S1/P2C-T1을 매 경계 exact 비교한다. crash, target-existing,
  same-byte replacement/touch/chmod/link, content reserialization, source+P2C 결합 drift는
  모두 same R021 재사용 없이 new-revision authority-zero terminal이어야 한다.
- 공개된 r002 package manifest와 output manifest가 동일한 P2C path/SHA/bytes를 각각
  exact-one 위치에 기록하고 상호 일치해야 하며, 이 content anchor를 이후 read-only
  검사와 P4 review가 승계한다.
- test oracle은 S1+P2C coupled substitution, captured_ns-only 재직렬화, P2C same-byte
  physical replacement, duplicate/conflicting/missing package-output binding을 각각
  negative로 고정한다.

## 나머지 공격 축

| 축 | 판정 | 근거 |
|---|---|---|
| I exact 28 rows | CLOSED | ordinal 1..28, FILE/DIRECTORY exact key sets, failed-r001 root+six, R021 trio와 semantic review time이 명시돼 있다. P2A는 두 PASS review 뒤 I를 캡처하므로 review가 미래 receipt를 hash하는 cycle도 없다. |
| S0 live 범위 | CLOSED | S0 equality는 P2B mutation 시작 직전까지이고, 완료 뒤 historical-only로 분리돼 R020의 정상 correction 불가능 모순을 제거했다. |
| P2A committed one-shot/T0 | CLOSED | 한 committed selection, receipt add-only, 동일 supervisor의 post-add T0 보유, 세 source 독립 상수와 새 process expected tuple이 명시됐다. |
| P2C/S1 persistence | OPEN_BLOCKING | P2C 자체가 현재 S1을 증명하지만 최초 P2C/S1 pair를 고정하는 외부 anchor가 P3 전에는 없어 B01 결합 치환이 가능하다. |
| self/future cycle | CLOSED_EXCEPT_B01_FIX | I→capture→S1→P2C→r002 방향은 비순환이다. 교정도 P2C hash를 S1 안에 넣는 self-cycle이 아니라 supervisor T1 handoff와 r002의 후행 anchor여야 한다. |
| crash/resume | OPEN_BLOCKING | P2A/P2B의 terminal 규칙은 명확하지만 P2C 공개 뒤 crash한 process가 최초 T1을 복원할 곳이 없다. current-baseline 재채택을 금지·탐지할 수 없다. |
| R002 namespace/retry | CLOSED | R002 bundle/review/gate-derived paths와 R002 IDs가 exact이고 any-existing/race/fsync ambiguity는 same-revision success가 아니다. |
| r001 및 r002 root read-only | CLOSED | r001 root+six full tuple/content를 전 epoch 고정하고, r002 성공 후 root exact-six NUL-name digest와 full tuples/hash를 read-only 검사 전후 비교한다. |
| test oracle | BLOCKED_BY_B01 | 37-method 범위는 넓지만 P2C 최초 expected T1과 exact manifest binding schema가 없어서 coupled substitution의 authoritative expected value를 만들 수 없다. |
| authority/credit zero | CLOSED | 후보는 seq1-only/non-effective/not-approved/not-applied이며 canonical/checkpoint/Goal/product/formal/device/release write·credit은 0이다. finding이면 P2A 이후 write 0이다. |

## 명시적 판정

```text
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
source_build_test_executions = 0
source_writes = 0
r001_writes = 0
r002_writes = 0
canonical_checkpoint_goal_product_writes = 0
formal_device_release_credit = 0
authority_granted = NONE
```

R021은 `0/0/0`이 아니므로 `PASS` 또는
`R021_R002_CAPTURE_SOURCE_AND_CANDIDATE_CORRECTION_ONLY` 권한을 부여할 수 없다.
P2C 최초 T1의 단일-supervisor handoff, exact serialization, P3 r002의 exact-one durable
binding을 명시한 새 roadmap revision과 새 dual review가 필요하다.
