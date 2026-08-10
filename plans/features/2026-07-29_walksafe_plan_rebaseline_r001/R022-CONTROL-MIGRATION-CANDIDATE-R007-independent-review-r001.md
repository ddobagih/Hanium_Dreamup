# r022 제어계약 전환 설계 R007 독립검수 R001

- 검토일: `2026-07-30`
- 대상: `R022-CONTROL-MIGRATION-CANDIDATE-R007.md`
- target SHA-256:
  `2c0eb19da8b9a2df77cb43b2ffd169583591f0a23150d9ef5eff00cf39327610`
- target bytes: `77,365`
- target lines: `1,284`
- 판정: `PASS_FOR_DESIGN_ONLY_SOURCE_DRIFT_BLOCKED`
- findings: `BLOCKING=0 MAJOR=0 MINOR=0`
- 적용 권한: 없음

세 독립 검토 축은 같은 frozen target의 시작·종료 SHA-256/bytes를 읽기 전용으로
재확인했다.

| 검토 축 | BLOCKING | MAJOR | MINOR |
|---|---:|---:|---:|
| authority·capability·preclaim security | 0 | 0 | 0 |
| selector·M DAG·ACTIVE closure | 0 | 0 | 0 |
| crash observation·filesystem durability | 0 | 0 | 0 |

이 PASS는 R007을 후속 candidate 설계 입력으로 사용할 수 있다는 뜻만 가진다.
source 복원·정본화, candidate build, authorization request, production apply,
canonical r022, Goal/event, FP-008, 제품 코드, formal·실기기·artifact·gate 또는
release credit 권한은 부여하지 않는다.

## 1. R006 잔여 원인 교정 확인

### 1.1 recovery observation lineage 전이 폐쇄

- recovery lease에서 다시 crash가 나면 새 observation은 이전 consume record와
  parent observation을 결속해 단일 O1→…→On lineage를 만든다.
- consume CAS는 기존 consumed-but-unclosed ancestor의 ID/hash/consume-record
  hash를 oldest→newest exact list, count와 digest로 결속한다.
- close는 current B head에서 open set을 다시 도출하고 단일 expected-head append로
  전체 list를 원자적 transitive-close한다.
- ancestor 누락, 중복, 순서 변경, fork·cycle·skipped parent, 다른 transaction/
  lineage member와 remaining open set은 모두 write 0이다.
- stable/terminal은 resulting open set이 empty일 때만 가능하다.
- O1→O2와 O1→O2→O3 재-crash positive/negative fixture 요구가 명시됐다.

### 1.2 marker와 post-marker predecessor closure

- `POST_MARKER_DESCENDANT_SET`은 ingress부터 attestation, runtime/tool capture,
  claim, progress, promotion, post-check와 terminal까지의 전체 descendant를
  닫힌 집합으로 정의한다.
- bootstrap recovery는 marker absent와 descendant empty를 동시에 요구한다.
- application recovery는 quick PASS와 marker exact, descendant zero 또는 두
  branch 중 하나의 exact ordered prefix를 동시에 요구한다.
- marker absent + descendant present 또는 어느 immediate/earlier predecessor가
  absent/nonexact인데 successor가 존재하면
  `PHYSICAL_DIVERGENCE_FAIL_CLOSED` 하나로 귀속된다.
- P/M 각각의 모든 predecessor-gap fixture가 repository write 0을 요구한다.

## 2. 선행 교정과 회귀 확인

- M preparation은 build → independent review findings 0 → `L_M` →
  request/challenge/authorization 순서이며 자기 review/leaf 순환이 없다.
- M request는 reviewed TM source와 future GM logical path/role/tombstone만
  결속하고 authorization 뒤 archive/equality receipt를 만든다.
- bootstrap/application preclaim과 signed negative-ingress terminal incident
  권한은 상호 배타적이며 claim/progress/final 권한을 우회하지 않는다.
- quick/marker 반대 방향 predecessor 위반도 divergence로 유지됐다.
- target-parent-first cross-parent rename, 네 crash state와 same-inode cleanup이
  spool→raw까지 적용된다.
- final15 `REF` graph는 전체 node/edge/order 재구성, SCC와 topological sort로
  self/future/multi-node cycle을 거부한다.
- ACTIVE는 candidate를 읽지 않고 live managed/product, Git index/stage,
  tombstone, untracked/ignored inventory를 두 번 직접 재열거·재해시한다.
- A0/R0/L/B non-TOFU, P/M 분리 승인, 과거 approval/nonce 재사용 차단과
  FP-008/Goal/product deny-all은 유지됐다.

## 3. 현재 blocker와 다음 경계

현재 active control은 v2.4 / checkpoint sequence 39 / canonical r021이다.
artifact `126/257`, open `131`, formal `0/279`, actual-device `0`, release gate
`0/5`, release `NOT_ELIGIBLE`과 Goal/focus/frontier는 불변이다.

working snapshot 603-file path-set은
`e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1`로
일치하지만 live content-set
`51fa51b966219dc4d8c1ff8317e7209323726298365b4ec413b2520bdc90bf1b`은
seq39 expected
`69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a`와 다르다.

따라서 다음 행동은 사용자가 checkpoint-projected source 복원 또는 reviewed
add-only exact snapshot 수용 중 하나를 정확히 선택하는 것이다. 선택과 그에 따른
독립검수로 drift 0이 되기 전에는 P/M candidate build, request와 apply를 하지
않는다. 이후에도 P와 M은 서로 다른 candidate-specific fresh 사용자 승인을
각각 요구하며 과거 승인을 재사용하지 않는다.
