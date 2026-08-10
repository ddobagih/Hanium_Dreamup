# r022 제어계약 전환 설계 R004 독립검수 R001

- 검토일: `2026-07-30`
- 대상: `R022-CONTROL-MIGRATION-CANDIDATE-R004.md`
- target SHA-256:
  `f44085550511eb346b8ddebc5db88cd5bb69160cc93b10bf78e50049fb555fcc`
- target bytes: `50,508`
- target lines: `914`
- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R005`
- 적용 권한: 없음

세 검토 축은 같은 frozen target을 읽기 전용으로 재확인했다. 축 사이에 원인이
겹치므로 수치를 단순 합산하지 않는다.

| 검토 축 | BLOCKING | MAJOR | MINOR |
|---|---:|---:|---:|
| authority·execution·durability security | 1 | 1 | 0 |
| P/M DAG·discovery·closure semantics | 2 | 1 | 0 |
| exact set·digest·state mechanical | 0 | 1 | 0 |

어느 방식으로 집계해도 findings-zero가 아니므로 R004는 candidate build,
authorization request, production apply와 canonical 근거로 사용할 수 없다.

## 1. 통합 findings

### 1.1 BLOCKING — bootstrap 이후 quick crash ownership 불완전

R004 §10의 P/M DAG는 external bootstrap receipt 뒤 quick을 수행하지만, §11은
다음 경계를 모두 상호배타적으로 소유하지 않는다.

- P/M bootstrap receipt exact 뒤 quick intent 전의 zero-quick crash
- P/M quick intent/spool/result 뒤 PASS receipt 전의 partial/unclosed quick
- quick PASS 뒤 application marker 전 crash

P unclosed quick는 §10에서
`SELECTOR_PREFLIGHT_RECOVERY_REQUIRED`, truth table에서
`SELECTOR_PREFLIGHT_BOOTSTRAP_RECOVERY_REQUIRED`로 서로 다른 public state를 쓴다.
M unclosed quick는 bootstrap receipt 부재 row와 application marker 이후 row
사이에 빠진다. continuous lease가 끝난 뒤 새 transaction/reuse도 금지되므로 이
경계는 정상 진행이나 deterministic recovery가 불가능하다.

R005는 P/M 각각 bootstrap receipt 이후 zero/partial/unclosed quick 전체를 해당
bootstrap recovery state가 소유하게 하고 §10과 truth table의 이름을 일치시켜야
한다.

### 1.2 BLOCKING — `incident+receipt` divergence 범위가 과대

R004 §11과 §13의 bare `incident+receipt`는 receipt phase를 구분하지 않는다.
따라서 정상 quick incident와 그 전에 이미 필요한 bootstrap/pivot prerequisite
receipt가 공존해도 physical divergence가 되어 exact normal-incident row를
가린다.

R005는 같은 transaction의 같은 terminal slot에서 normal incident와 post-commit
success receipt가 함께 존재하는 경우만 divergence로 정의해야 한다.
bootstrap/pivot/quick prerequisite receipt는 다른 phase normal incident와의
충돌 receipt가 아니다.

### 1.3 MAJOR — cross-parent rename 내구성 불완전

R004 §4.1은 same-filesystem rename 뒤 target parent만 fsync한다. §5의
`spool/ → raw/` 승격은 source와 target parent가 다르므로 source name 제거가
durable하지 않아 crash 뒤 temp/spool name이 재출현할 수 있다.

R005는 D-PUBLISH intent/state가 source와 target parent 양쪽의 exact before/after
entry set을 결속하고, 다른 parent면 둘 다 결정적 순서로 fsync/confirm하며 같은
parent면 한 번만 fsync하도록 해야 한다. `D_NAME_SWITCHED`, `D_DONE`과 spool→raw
승격에도 같은 규칙을 적용해야 한다.

### 1.4 MAJOR — M application-prefix와 committed row 중첩

M target checkpoint가 존재하고 M receipt가 없는 상태는 application marker와
final prefix도 함께 가진다. 따라서 R004 §11의
`V25_TRANSITION_RECOVERY_REQUIRED`와
`V25_COMMITTED_RECOVERY_REQUIRED`가 동시에 일치한다.

R005는 application-prefix recovery row에 `target checkpoint absent`를 넣어
checkpoint commit 전후를 상호배타화해야 한다.

### 1.5 MAJOR — P unclosed quick public state 불일치

R004 §10과 §11이 같은 P physical predicate에 서로 다른 public state를 부여한다.
R005는 `SELECTOR_PREFLIGHT_BOOTSTRAP_RECOVERY_REQUIRED` 하나로 통일해야 한다.

## 2. 확인된 정상 부분

- P17은 add-only 5, pivot을 포함한 CAS 11, checkpoint index 17의 exact set이다.
- M15는 exact 15개이고 checkpoint index 15가 마지막 commit point다.
- P index 1~16 protected closure와 M15는 겹치지 않는다.
- P quick logical digest
  `01713a874fcda224527027c7cd62092e239b704ca73f9b6dcf66daf61b4b9eeb`와
  historical quick/full19/post-check digest는 독립 재계산과 일치한다.
- A0/R0/B non-TOFU, fixed-FD broker/exec, enforced lease, P19/V19 lower-only
  verification과 Approval2/FP-008 deny-all에는 추가 finding이 없다.
- detached M authorization manifest의 GM archive와 ACTIVE binding,
  post-M P closure, self-cycle 제거와 normal incident/divergence 분리는 위
  findings를 제외하고 일관된다.
- R003, R003 review와 R004 frozen bytes는 검수 중 수정하지 않았다.

## 3. 보존 경계와 R005 correction allowlist

현재 active control은 v2.4 / checkpoint sequence 39 / canonical r021이다.
artifact `126/257`, open `131`, formal `0/279`, actual-device `0`, release gate
`0/5`, release `NOT_ELIGIBLE`과 Goal/focus/frontier는 불변이다. live 603-file
content set
`51fa51b966219dc4d8c1ff8317e7209323726298365b4ec413b2520bdc90bf1b`은 seq39
expected
`69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a`와 달라
candidate build/apply도 계속 차단된다.

다음 허용 행동은 add-only
`R022-CONTROL-MIGRATION-CANDIDATE-R005.md`에 다음 다섯 교정만 반영하고 새 exact
bytes를 독립검수하는 것이다.

1. P/M zero·partial·unclosed quick을 각 bootstrap recovery가 소유하고 §10/truth
   state를 일치시킨다.
2. `incident+receipt` divergence를 same-phase terminal conflict로 한정한다.
3. M application-prefix row에 target checkpoint absent를 추가한다.
4. P unclosed quick state를
   `SELECTOR_PREFLIGHT_BOOTSTRAP_RECOVERY_REQUIRED`로 통일한다.
5. cross-parent rename의 source/target entry-set과 양 parent fsync를 완성한다.

R004 수정, 이 allowlist 밖 설계 확장, P/M candidate build, authorization request,
canonical/Goal/product write와 FP-008 시작은 허용하지 않는다.
