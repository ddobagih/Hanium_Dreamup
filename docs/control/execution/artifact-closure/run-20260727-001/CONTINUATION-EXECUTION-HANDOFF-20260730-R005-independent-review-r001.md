# WalkSafe 실행 준비 인계 R005 독립검수 R001

- 검토일: `2026-07-30`
- 대상:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R005.md`
- target SHA-256:
  `07a155ced76b648a3435969097ca937133fadd8f2f41703c0d8090a541a7f908`
- target bytes: `10,460`
- target lines: `237`
- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R006`
- 적용 권한: 없음

두 독립 검토 축은 같은 target의 시작·종료 SHA-256/bytes를 읽기 전용으로
재확인했다.

| 검토 축 | BLOCKING | MAJOR | MINOR |
|---|---:|---:|---:|
| 물리 결속·재개 사용성 | 0 | 1 | 0 |
| source 전략·fail-closed 안전성 | 0 | 0 | 0 |
| 중복 제거 | 0 | 1 | 0 |

## 1. 통합 MAJOR 판정

### 1.1 source no-follow 검사보다 predecessor block이 먼저 실행됨

R005 corrected block은 R004 §7을 추출해 실행한 뒤 runner와 두 evidence copy에
`! -L`, regular, owner/mode/nlink를 검사한다. R004 §7의 `ws_check_file`은
`test -f`, `sha256sum`, `wc` 순서라 symlink를 따라간다.

따라서 세 source 경로가 symlink로 drift하면 R005는 최종적으로 실패하지만 그
전에 symlink target을 이미 열고 읽는다. R004 FAIL receipt가 요구한
“symlink를 따라가지 않고 검증”이 전체 실행 순서에서는 충족되지 않는다.

R006은 세 source 경로를 lstat 성격의 `! -L`, regular, owner/mode/nlink,
SHA-256/bytes 순서로 먼저 검증한 뒤에만 pinned R005 corrected block을 실행해야
한다. preflight가 실패하면 predecessor block을 한 줄도 실행하지 않아야 한다.

## 2. 확인된 정상 부분

- R004와 R004 FAIL receipt의 SHA/bytes, inheritance/override와 read order는
  정확하다.
- continuation rc=1과 exact ordered 2-error output, Goal rc=1과 exact ordered
  3-error output을 완전 비교해 추가 오류를 허용하지 않는다.
- source 세 경로의 regular/owner/mode/nlink/hash/bytes 기대값 자체는 정확하다.
- dangling symlink를 포함한 physical absence와 R007 P/M NOREPLACE standalone
  final 9개 검사가 완전하다.
- restore는 현행 quick PASS, add-only acceptance는 새 validator PASS와 현행
  exact FAIL 유지 뒤 별도 successor transition이라는 경계가 정확하다.
- corrected block은 현재 물리 상태에서 Bash 문법과 실제 실행 rc=0, stdout
  0 bytes를 확인했다.

## 3. R006 correction allowlist

다음 허용 행동은 add-only
`CONTINUATION-EXECUTION-HANDOFF-20260730-R006.md`에서 R005와 이 FAIL receipt를
결속하고 정확히 다음만 교정하는 것이다.

1. runner와 두 evidence copy를 no-follow/type/metadata/hash/bytes로 선검사한다.
2. 선검사가 모두 끝난 뒤에만 pinned R005 corrected block을 실행한다.

R005 수정, source 전략의 임의 선택, source/checkpoint 변경, candidate build,
authorization request, canonical/Goal/product write는 허용하지 않는다.
