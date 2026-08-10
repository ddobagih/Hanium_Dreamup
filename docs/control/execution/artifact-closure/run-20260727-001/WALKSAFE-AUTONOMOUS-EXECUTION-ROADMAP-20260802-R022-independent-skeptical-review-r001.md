# WalkSafe R022 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R022-INDEPENDENT-SKEPTICAL-REVIEW-R001
type = INDEPENDENT_SKEPTICAL_STATIC_REVIEW
reviewer_agent = /root/r022_skeptical_review
reviewer_axis = I31_S0_S1_P2A_T0_P2C_T1_FIRST_OBSERVATION_RACE_SUPERVISOR_CRASH_R002_READ_ONLY_ZERO_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R022.md
target_sha256 = 2a05166af626fae929decf0fef28fc6c70b4b448a312e6e73ba2aa9e8cac3caa
target_bytes = 18978
target_lines = 456
reviewed_at = 2026-08-02T12:42:08+09:00
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
blocking = 1
major = 0
minor = 0
authority_granted = NONE
```

## 범위와 동결 identity

R022 456줄 전체를 coordinated S1/P2C rewrite, current-baseline fallback,
supervisor crash/loss/child handoff, candidate anchor mismatch/cycle, P2C canonical ambiguity,
T0/T1 same-byte physical replacement, R002 namespace, failed-r001 불변성, retry·read-only,
37-test oracle와 zero-authority 축에서 정적 red-team 검토했다. target은 검토 시작과 판정
직전 모두 SHA-256
`2a05166af626fae929decf0fef28fc6c70b4b448a312e6e73ba2aa9e8cac3caa`,
18,978 bytes, 456 lines였다.

source/build/test/candidate checker는 실행하지 않았다. source, r001, r002, canonical,
checkpoint, Goal, 제품은 수정하지 않았다.

## Findings

### BLOCKING R022-SK-B01 — add가 만든 물리 객체와 최초 T0/T1 관측 사이가 원자적으로 결박되지 않는다

R022는 P2A/P2C를 `apply_patch Add File`로 공개한 **뒤** 같은 supervisor가 path를
no-follow로 다시 읽어 T0/T1을 최초 캡처한다. 그러나 add가 실제로 생성한 inode의
file descriptor, add 직후의 `fstat`, 또는 add operation이 반환한 exact tuple을
supervisor가 보유하고 그 값과 path `lstat`을 비교한다는 계약이 없다. “same supervisor”와
“immediately”는 두 filesystem operation 사이의 교체를 원자적으로 만들지 않는다.

따라서 P2C에서는 다음 interleaving이 가능하다.

```text
supervisor: canonical P2C-A를 Add File로 공개
공격자: T1 최초 lstat 전에 P2C-A를 동일 바이트의 새 inode로 교체
supervisor: 새 inode를 최초 T1으로 캡처
검사: canonical bytes/S1/hash/bytes와 새 T1이 모두 일치하여 PASS
P3: package/output가 교체 inode의 T1을 정상 SOURCE_TRANSITION_ANCHOR로 영속화
```

교체가 동일 바이트라 raw serialization과 SHA/bytes 검사는 구별하지 못하고, full
nine-field tuple도 **교체 전 tuple을 expected value로 가진 적이 없으므로** 새 tuple을
그대로 채택한다. §8.1의 `captured_ns <= min(mtime_ns,ctime_ns)`도 새 inode 시간이
publication 뒤이면 만족한다. §7.3의 existing/drift terminal과 §8.2의 anchor mismatch는
T1이 생긴 뒤에는 강하지만, 이 최초 관측 전 race에는 비교 기준이 없다. P2A add→T0에도
같은 결함이 있으며, 이후 세 source에 T0를 embed해도 이미 채택된 대체 inode를
정당화할 뿐이다.

같은 창에서 S1-B와 이를 기술하는 P2C-B를 함께 놓고 최초 T1을 잡게 하는 결합 rewrite도
문서상 최초 expected S1/T1 없이 채택될 수 있다. P2B 종료 시의 exact S1 object를
supervisor가 P2C raw 생성 전부터 보유한다는 명시와, add operation의 물리 identity를
그 object에 원자적으로 연결하는 규칙이 없기 때문이다. 반대로 T1이 일단 만들어진 뒤의
current-live fallback 금지, `None` 금지, 경계별 original object 비교, supervisor
loss/crash terminal과 read-only child 제한은 명확하다.

최소 교정은 successor revision에서 P2A와 P2C 각각에 대해 다음을 모두 요구하는 것이다.

- supervisor가 publication 전에 exact raw bytes와 S1 snapshot을 메모리에 고정한다.
- supervisor가 `O_CREAT|O_EXCL|O_NOFOLLOW` 등 add-only create/write/fsync를 직접 수행하며
  열린 fd의 `fstat`을 최초 T0/T1으로 삼거나, 동등하게 add가 만든 fd/tuple을 손실 없이
  반환하는 단일 primitive를 사용한다.
- fd `fstat`, path no-follow `lstat`, expected raw hash/bytes를 close 전후와 parent-fsync
  경계에서 exact 비교한다. 어느 불일치도 same-revision 현재값 채택 없이
  new-revision/authority-zero terminal이다.
- 37-method 기존 universe 안에 add 직후 최초 capture 전의 P2A/P2C same-byte inode
  replacement와 S1+P2C coordinated replacement negative를 넣고, rename/publication이
  일어나지 않음을 확인한다.

## 나머지 공격 축

| 축 | 판정 | 근거 |
|---|---|---|
| exact I31 / S0 / failed-r001 | CLOSED | I는 R022 trio와 immutable failed-r001 root+six를 포함한 exact 31 rows이며, S0 live cutoff와 r001 full tuple/hash/NUL-name digest 재검사가 유지된다. |
| P2C canonical bytes/schema | CLOSED | UTF-8, sorted compact canonical JSON, LF, strict parse, exact top-level/nested schemas, bool 제외 int와 preimage digest bytes가 하나의 raw encoding을 정한다. |
| coordinated rewrite / baseline fallback | OPEN_BLOCKING_BEFORE_T1 | 최초 T1 뒤에는 original in-memory anchor와 `None`/current adoption 금지가 닫지만, add→first-capture 창은 B01처럼 새 current object를 최초 anchor로 승격할 수 있다. |
| supervisor crash/loss/child handoff | CLOSED_AFTER_T1 | P2C add부터 P3 parent-fsync까지 uninterrupted supervisor, original object 전달, read-only child만 허용, crash/loss 시 new revision terminal이 명시돼 있다. B01의 first-observation race는 별개다. |
| candidate anchor mismatch/cycle | CLOSED | package/output의 exact top-level `/source_transition_evidence`가 deep-equal이고 seal에 포함되며, P2A→S1→P2C/T1→candidate 단방향이라 self/future cycle이 없다. |
| T0/T1 physical replacement | OPEN_BLOCKING_AT_FIRST_CAPTURE | capture 이후 full tuple 비교는 touch/chmod/link/replacement를 잡지만 add가 만든 inode와 최초 captured inode의 동일성을 증명하지 않는다. |
| R002 namespace / r001 allowlist / retry | CLOSED | R002 paths·IDs가 exact이고 historical r001 allowlist가 제한되며 any-existing/race/fsync ambiguity는 same R022 retry가 아닌 terminal이다. |
| r002 read-only / 37 tests | BLOCKED_BY_B01 | publication 뒤 exact-six no-follow tuple/hash/NUL digest와 pre/post 37 read-only checks는 명확하지만 first-capture replacement oracle이 B01을 닫아야 한다. |
| authority/credit zero | CLOSED | 후보는 non-effective이고 canonical/checkpoint/Goal/product write 권한은 0이며 finding 발생 시 P2A 이후 authority가 없다. |

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

R022는 `0/0/0`이 아니므로 `PASS` 또는
`R022_R002_CAPTURE_SOURCE_CANDIDATE_CORRECTION_ONLY` 권한을 부여할 수 없다. P2A/P2C
publication이 만든 물리 객체와 최초 T0/T1을 원자적으로 결박하는 새 roadmap revision과
새 dual review가 필요하다.
