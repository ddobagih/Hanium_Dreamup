# r022 제어계약 전환 설계 R005 독립검수 R001

- 검토일: `2026-07-30`
- 대상: `R022-CONTROL-MIGRATION-CANDIDATE-R005.md`
- target SHA-256:
  `4cfcd51904038d38b5ab82b097da375c09e953e0182be47669a147e5c99bef67`
- target bytes: `53,409`
- target lines: `944`
- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R006`
- 적용 권한: 없음

세 검토 축은 같은 frozen target의 시작·종료 SHA-256/bytes를 읽기 전용으로
재확인했다. 축 사이에 같은 root cause가 중복되므로 수치를 단순 합산하지 않는다.

| 검토 축 | BLOCKING | MAJOR | MINOR | 통합 root cause |
|---|---:|---:|---:|---|
| authority·capability·preclaim security | 3 | 0 | 0 | 1.1, 1.2, 1.3 |
| M DAG·discovery·ACTIVE closure | 2 | 3 | 0 | 1.1, 1.2, 2.1, 2.3, 2.4 |
| crash observation·filesystem durability | 2 | 1 | 0 | 1.3, 1.4, 2.2 |

어느 축도 findings-zero가 아니므로 R005는 candidate build, authorization request,
production apply 또는 canonical 근거로 사용할 수 없다. 아래 통합 목록은 중복을
제거한 root cause 단위이며 별도의 축별 수치 합계가 아니다.

## 1. 통합 BLOCKING 판정

### 1.1 M build가 자신의 review와 L_M을 선행조건으로 요구

R005 §3.1은 `L_M`이 R005/candidate/review를 결속한다고 규정하지만 §9는 fresh
`L_M`과 M candidate independent review findings 0이 모두 있어야 M candidate를
build할 수 있다고 규정한다. 아직 만들지 않은 candidate의 review와 그것을
결속하는 leaf를 build의 선행조건으로 요구하므로 M preparation DAG가 시작되지
않는다.

R006은 source drift 0과 별도 build instruction 아래 TM candidate를 먼저 build한
뒤, 그 exact bytes의 독립검수 findings 0을 얻고, 그 candidate/review를 결속하는
`L_M`을 발행한 다음 M authorization/apply로 진행해야 한다.

### 1.2 M request와 미래 GM archive가 서로를 선행 요구

R005 §9는 reviewed TM detached-output authorization manifest를 quick 전에 GM에
archive하고 M request/challenge가 그 GM physical binding을 직접 고정하게 한다.
그러나 GM bootstrap/archive write는 M authorization과 bootstrap capability
소비 뒤에만 가능하고 request/challenge는 authorization보다 먼저 완성돼야 한다.
따라서 request와 GM archive 사이에 순환이 생긴다.

R006 M request는 reviewed TM source manifest의 exact raw hash/bytes와 아직 없는
GM archive의 expected final path/ABSENT tombstone을 결속해야 한다. authorization
뒤 bootstrap이 그 source bytes를 GM에 byte-exact copy하고 external bootstrap
receipt가 source/request/observed GM binding의 일치를 검증해야 한다.

### 1.3 application preclaim write authority가 비어 있음

R005는 quick PASS 뒤
`APPLICATION_CONSUMPTION_PROBE_STARTED → signed consumed attestation →
runtime/tool O-CAPTURE → APPLICATION_CLAIM_INTENT` 순서를 요구한다. 그러나
bootstrap capability의 명시 allowlist는 bootstrap/pivot/quick까지만이고
application claim은 consumption 뒤 final application과 terminal closure만
허용한다. 따라서 probe marker, signed consumption ingress capture와 claim 전
runtime/tool capture를 누가 쓸 수 있는지 닫혀 있지 않다.

R006 bootstrap capability는 exact gate-local
`APPLICATION_CONSUMPTION_PROBE_STARTED`와 signed consumption ingress
`O-CAPTURE`까지만 추가로 허용해야 한다. 검증된 consumed attestation은 exact
runtime/tool `O-CAPTURE`와 application claim 또는 preclaim normal incident만
허용하고 final member를 직접 쓸 권한은 부여하지 않아야 한다.

### 1.4 append-only crash observation을 재사용할 수 있음

R005는 prior transaction/progress/crash observation을 결속한
`SAME_TRANSACTION_RECOVERY_CAPABILITY`를 요구하지만 append-only B journal의
과거 observation이 복구 뒤 닫혔음을 증명하는 상태가 없다. 같은 옛 observation이
후속 discovery에서 다시 매치돼 이미 진행한 suffix를 재채택할 수 있다.

R006 crash observation은 exact phase와 prior lease ID를 결속해야 한다. recovery
capability는 그 observation을 one-use로 consume하고, 복구 또는 terminal closure
뒤 durable `RECOVERY_OBSERVATION_CLOSED`를 append해야 한다. B latest-head에서
가장 최신인 unclosed observation 하나만 recovery predicate에 일치해야 한다.

## 2. 통합 MAJOR 판정

### 2.1 quick PASS 부재와 application marker 공존을 valid recovery로 분류

R005 truth table은 quick PASS receipt가 없는 zero/partial/unclosed quick을
bootstrap recovery로 분류하면서 application marker가 있는 상태를 별도
application recovery로도 분류한다. 정상 DAG에서는 application marker가 quick
PASS 뒤에만 생기므로 이 조합은 predecessor 위반이며 두 recovery row가 동시에
일치할 수 있다.

R006은 quick-PASS-missing recovery row에 `application marker absent`를,
application recovery row에 `quick PASS receipt exact`를 요구해야 한다. quick
PASS receipt 부재와 application marker 존재가 공존하면 recovery가 아니라
`PHYSICAL_DIVERGENCE_FAIL_CLOSED`여야 한다.

### 2.2 cross-parent rename 사이 crash 상태가 닫히지 않음

R005는 source/target parent를 decoded raw path 순서로 fsync한다. 이 순서는 target
name durability를 먼저 보장하지 않으며 두 parent fsync 사이 crash 뒤 source-only,
target-only 또는 same-inode dual-name 관찰이 legitimate interrupted state인지
generic divergence인지 판정할 수 없다. `spool/ → raw/` 승격에서는 원 raw inode를
잃거나 valid 재등장을 거부할 수 있다.

R006은 다른 parent rename의 fsync 순서를 target parent first, source parent
second로 고정해야 한다. rename 뒤 각 crash observation을 열거하고 same-inode
dual-name을 deterministic cleanup/resume하는 계약을 두거나, 그 원자성과
durability를 증명하는 supported-filesystem contract가 없으면 write 0으로
막아야 한다. 같은 규칙을 spool→raw에 적용해야 한다.

### 2.3 final15 physical-hash dependency DAG가 불완전

R005는 index 5와 7 사이의 일부 금지 edge와 detached/resolved 방향만 설명하지만
final15 모든 member가 참조하는 physical hash edge의 완전한 그래프와
topological validation을 정의하지 않는다. 따라서 숨은 self-cycle, 2-node cycle
또는 아직 결정되지 않은 future hash 참조를 검출할 수 없다.

R006은 final15 전체 physical-hash dependency edge를 선언하고 acyclic/topological
sort를 검증해야 한다. non-manifest member는 index 7/package,
detached-output manifest, resolved manifest 또는 future member hash를 참조하지
못하며, 필요한 예외는 명시된 ordered allowed edge로만 허용해야 한다.

### 2.4 ACTIVE checker의 live source read set이 부족

R005 ACTIVE checker는 P closure, GP receipt/progress, M final15, GM dynamic
records와 detached archive만으로 동작한다. checkpoint가 선언한 live managed와
product source inventory, Git index/tombstone 및 untracked/ignored policy를 직접
읽고 재해시하지 않으므로 candidate 없이도 active source drift 0을 증명할 수 없다.

R006 ACTIVE checker read set에는 checkpoint-declared live managed/product
inventory와 Git index/tombstone/nonignored-untracked/ignored policy가 포함돼야
한다. checker는 candidate를 읽지 않고 final live source를 직접 재해시해
path-set/content-set drift 0을 강제해야 한다.

## 3. 확인된 정상 부분

- R005 exact P17은 add-only 5, pivot을 포함한 CAS 11, checkpoint index 17이다.
- M final은 exact 15개이고 checkpoint index 15가 마지막 commit point다.
- P index 1~16 protected closure와 M15는 겹치지 않는다.
- P quick logical digest
  `01713a874fcda224527027c7cd62092e239b704ca73f9b6dcf66daf61b4b9eeb`와
  historical quick/full19/post-check digest는 독립 재계산과 일치한다.
- P/M quick PASS 전후의 recovery state 이름, target checkpoint 전후 M recovery,
  same-phase incident/receipt conflict 범위에는 위 findings 외 추가 finding이 없다.
- A0/R0/B non-TOFU, fixed-FD execution, enforced lease와 Approval2/FP-008
  deny-all은 유지할 수 있다.
- R005와 그 predecessor frozen bytes는 검수 중 수정하지 않았다.

## 4. 보존 경계와 R006 correction allowlist

현재 active control은 v2.4 / checkpoint sequence 39 / canonical r021이다.
artifact `126/257`, open `131`, formal `0/279`, actual-device `0`, release gate
`0/5`, release `NOT_ELIGIBLE`과 Goal/focus/frontier는 불변이다. live 603-file
content set
`51fa51b966219dc4d8c1ff8317e7209323726298365b4ec413b2520bdc90bf1b`은 seq39
expected
`69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a`와 달라
candidate build/apply도 계속 차단된다.

다음 허용 행동은 add-only
`R022-CONTROL-MIGRATION-CANDIDATE-R006.md`에 다음 여덟 root-cause 교정만
통합하고 새 exact bytes를 자체검토한 뒤 독립검수하는 것이다.

1. M 순서를 build → independent review 0 → `L_M` → authorization/apply로 고친다.
2. M request는 reviewed TM manifest와 expected future GM tombstone을 결속하고,
   bootstrap receipt가 이후 GM byte-exact archive를 검증하게 한다.
3. probe/consumption ingress와 attestation 이후 runtime/claim 권한을 분리한다.
4. crash observation에 phase/lease와 one-use close/latest-head 규칙을 둔다.
5. quick PASS와 application marker predecessor를 truth table에서 강제한다.
6. cross-parent rename을 target-parent-first로 닫고 crash 상태를 열거한다.
7. final15 전체 physical-hash dependency DAG를 선언·검증한다.
8. ACTIVE가 candidate 없이 final live source inventory drift 0을 직접 검증한다.

R005 수정, 이 allowlist 밖 설계 확장, P/M candidate build, authorization request,
canonical/Goal/product write와 FP-008 시작은 허용하지 않는다.
