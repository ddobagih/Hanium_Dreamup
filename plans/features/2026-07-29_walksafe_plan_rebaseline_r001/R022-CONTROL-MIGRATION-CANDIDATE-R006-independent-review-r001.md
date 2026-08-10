# r022 제어계약 전환 설계 R006 독립검수 R001

- 검토일: `2026-07-30`
- 대상: `R022-CONTROL-MIGRATION-CANDIDATE-R006.md`
- target SHA-256:
  `06b6d7f16f54b1ce034aee7d89addd54796fcc4a5053fc5d436c8df3eb50238a`
- target bytes: `72,003`
- target lines: `1,203`
- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R007`
- 적용 권한: 없음

세 독립 검토 축은 같은 frozen target의 시작·종료 SHA-256/bytes를 읽기 전용으로
재확인했다. 축 사이에 같은 root cause가 중복되므로 수치를 단순 합산하지 않는다.

| 검토 축 | BLOCKING | MAJOR | MINOR | 통합 root cause |
|---|---:|---:|---:|---|
| authority·capability·preclaim security | 0 | 0 | 0 | 없음 |
| selector·M DAG·ACTIVE closure | 0 | 1 | 0 | 1.2 |
| crash observation·filesystem durability | 0 | 2 | 0 | 1.1, 1.2 |
| 중복 제거 | 0 | 2 | 0 | 1.1, 1.2 |

R006은 findings-zero가 아니므로 candidate build, authorization request,
production apply, canonical 또는 Goal 근거로 사용할 수 없다. 아래 목록은
중복을 제거한 root cause 단위다.

## 1. 통합 MAJOR 판정

### 1.1 재복구 lineage의 선행 consumed observation이 닫히지 않음

R006은 B append-only journal에서 최신 unclosed crash observation을 one-use
consume하고 recovery completion 또는 terminal closure 뒤
`RECOVERY_OBSERVATION_CLOSED`를 append하게 한다. 그러나 O1을 consume한 recovery
lease가 다시 종료되어 O2를 consume한 뒤 성공한 경우, 최종 close가 O2만 닫는지
O1까지 전이 폐쇄하는지 또는 outstanding observation마다 close를 쓰는지 정의하지
않는다.

terminal/stable row는 consumed-but-unclosed observation이 없어야 하므로 O1이
영구히 남아 stable 전이를 막을 수 있다. R007은 current lineage의 open ancestor
observation ID/hash 전체를 latest-head에서 exact 결속하고 한 번에
transitive-close하거나, 동일 효과의 deterministic ordered close chain을
요구해야 한다. 누락·중복·순서 변경·다른 lineage close는 write 0이어야 한다.

### 1.2 marker 부재와 post-marker descendant가 recovery 두 행에 동시 일치

R006은 `quick PASS 없음 + application marker 존재`를 predecessor divergence로
고쳤다. 하지만 quick PASS가 exact이고 marker가 absent인 상태에서 attestation,
runtime/tool capture, claim temp·spool, progress 또는 final prefix 같은
post-marker descendant가 존재하면 bootstrap recovery와 application recovery가
동시에 성립한다.

R007은 bootstrap recovery에 marker와 모든 post-marker descendant의 부재를
요구하고 application recovery에는 marker exact를 필수 conjunction으로 넣어야
한다. marker absent와 descendant present가 공존하면 recovery가 아니라
`PHYSICAL_DIVERGENCE_FAIL_CLOSED`여야 한다. marker→ingress→attestation→runtime
capture→claim→progress의 각 predecessor 누락을 negative fixture로 검증해야 한다.

## 2. 확인된 정상 부분

- M preparation 순서는 build → independent review findings 0 → `L_M` →
  request/challenge/authorization으로 순환 없이 닫혔다.
- M request는 reviewed TM source와 future GM logical path/role/tombstone만
  결속하고, authorization 뒤 GM archive와 byte-equality receipt를 만든다.
- bootstrap/application preclaim 권한과 signed negative-ingress terminal
  incident 권한은 좁고 상호 배타적이다.
- B phase/lease/latest-head, one-use consume와 일반 completion close 규칙은
  1.1의 재복구 lineage finding 외에는 닫혔다.
- quick PASS 부재와 marker 존재의 반대 방향 predecessor 위반은 divergence다.
- cross-parent rename은 target-parent-first이며 네 crash state, same-inode
  dual-name cleanup과 spool→raw 적용이 정의됐다.
- final15 `REF` DAG는 full node/edge 재구성, SCC와 topological sort를 요구하며
  future/self/multi-node cycle finding은 없다.
- ACTIVE checker는 candidate 없이 live managed/product와 Git index, tombstone,
  untracked/ignored inventory를 직접 재열거·재해시한다.

## 3. 보존 경계와 R007 correction allowlist

현재 active control은 v2.4 / checkpoint sequence 39 / canonical r021이다.
artifact `126/257`, open `131`, formal `0/279`, actual-device `0`, release gate
`0/5`, release `NOT_ELIGIBLE`과 Goal/focus/frontier는 불변이다.

live 603-file content set
`51fa51b966219dc4d8c1ff8317e7209323726298365b4ec413b2520bdc90bf1b`은 seq39
expected
`69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a`와 달라
candidate build, request와 apply도 계속 차단된다.

다음 허용 행동은 add-only
`R022-CONTROL-MIGRATION-CANDIDATE-R007.md`에 정확히 다음 두 root cause만
교정하고 새 exact bytes를 독립검수하는 것이다.

1. 재복구 lineage의 모든 open ancestor observation을 deterministic하게 닫는다.
2. marker와 모든 post-marker descendant의 predecessor closure를 상호 배타적
   truth table과 negative fixture로 강제한다.

R006과 predecessor 수정, 위 allowlist 밖 설계 확장, source 전략의 임의 선택,
P/M candidate build, authorization request, canonical/Goal/product write와
FP-008 시작은 허용하지 않는다.
