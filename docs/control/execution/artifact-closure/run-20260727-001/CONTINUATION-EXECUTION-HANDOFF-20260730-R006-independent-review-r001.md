# WalkSafe 실행 준비 인계 R006 독립검수 R001

- 검토일: `2026-07-30`
- 대상:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R006.md`
- target SHA-256:
  `e9eb0c47b03902a9c6b697cfdd6fbe1f387c591ea1c59b46f80bc2fcb14aecba`
- target bytes: `6,615`
- target lines: `167`
- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R007`
- 적용 권한: 없음

두 독립 검토 축은 같은 target의 시작·종료 SHA-256/bytes를 읽기 전용으로
재확인했다.

| 검토 축 | BLOCKING | MAJOR | MINOR |
|---|---:|---:|---:|
| 물리 결속·재개 사용성 | 0 | 1 | 0 |
| source 전략·fail-closed 안전성 | 0 | 1 | 0 |
| 중복 제거 | 0 | 1 | 0 |

## 1. 통합 MAJOR 판정

### 1.1 command substitution 안의 검사 실패가 전파되지 않음

R006은 `ws_check_regular`을 다음 형태의 command substitution 안에서
실행한다.

```bash
ws_runner_identity="$(ws_check_regular ...)"
```

기본 Bash는 command substitution subshell에서 `inherit_errexit`가 꺼져 있어
바깥의 `set -e`를 상속하지 않는다. 따라서 함수 안의 no-symlink/type/owner/
mode/nlink/hash/bytes `test`가 실패해도 함수가 계속 진행할 수 있고, 마지막
`stat -c '%d:%i'`가 성공하면 전체 assignment도 rc=0이 된다. post recheck도
같은 결함을 가진다.

독립 검토는 잘못된 hash/bytes/mode를 넣은 무해한 재현에서 함수가 끝까지
진행하고 rc=0을 반환하는 것을 확인했다. 정상 물리 상태에서 §3 실행이
rc=0·stdout 0 bytes인 사실만으로 negative semantics는 검증되지 않는다.

따라서 R005의 “predecessor block보다 먼저 fail-closed no-follow 검사” MAJOR는
R006에서 닫히지 않았다.

## 2. 확인된 정상 부분

- R005와 R005 FAIL receipt의 SHA/bytes, 읽기 순서, correction 상속은 정확하다.
- 세 source 경로의 기대 type/owner/mode/nlink/hash/bytes와 dev:ino 재확인
  범위는 정확하다.
- R005가 상속하는 exact output/absence, 두 source 전략의 validator convergence,
  approval DAG와 금지선은 정확하다.
- 현재 정상 물리 상태에서 §3의 Bash 문법 및 실제 실행은 rc=0, stdout 0
  bytes다.
- target 종료 SHA-256은 시작과 동일하다.

## 3. R007 correction allowlist

add-only `CONTINUATION-EXECUTION-HANDOFF-20260730-R007.md`는 R006과 이 FAIL
receipt를 결속하고 정확히 다음만 교정한다.

1. `ws_check_regular`의 모든 predicate를 command substitution 밖 같은
   shell에서 fail-closed로 실행한다.
2. 함수는 `printf -v` output variable로 identity를 설정하고 pre/post 모두
   동일한 직접 호출 패턴을 사용한다.
3. 하나의 predicate라도 실패하면 R005/R004 predecessor block보다 먼저
   nonzero로 종료됨을 부정 검증한다.

R006 수정, source 전략 임의 선택, source/checkpoint/checker 변경, candidate
build, authorization request, canonical/Goal/product write는 허용하지 않는다.
