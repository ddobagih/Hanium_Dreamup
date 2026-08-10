# WalkSafe 실행 준비 인계 R007 독립검수 R001

- 검토일: `2026-07-30`
- 대상:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R007.md`
- target SHA-256:
  `df36cd21dc2f1e76f20698a0c1ccd3696f311e1806b2700ea588f86910043c0b`
- target bytes: `8,323`
- target lines: `187`
- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R008`
- 적용 권한: 없음

두 독립 검토 축은 같은 target의 시작·종료 SHA-256/bytes를 읽기 전용으로
재확인했다.

| 검토 축 | BLOCKING | MAJOR | MINOR |
|---|---:|---:|---:|
| 재개 순서·물리 결속 | 0 | 1 | 0 |
| source 전략·fail-closed 안전성 | 0 | 0 | 0 |
| 중복 제거 | 0 | 1 | 0 |

## 1. 통합 MAJOR 판정

### 1.1 R004 §6 item 1~15 읽기 단계 누락

R007 §2는 R004~R006 handoff와 review 여섯 파일을 읽은 뒤 곧바로 §3을
실행하도록 적었다. 그러나 predecessor R006 §2가 명시한
“R005 §3이 가져오는 R004 §6 item 1~15를 상대순서대로 읽는다” 단계를
보존하지 않았다.

R004 자체를 읽는 것만으로 그 nested 15개 문서의 실제 읽기가 실행 순서에
들어갔다고 볼 수 없다. 새 터미널이 R007의 literal 순서만 따르면
AGENTS/runbook/current checkpoint/v2.4 static, R003/reviews와 제어 설계 source를
읽지 않은 채 fail-fast와 source 전략 요청으로 넘어갈 수 있다.

같은 predecessor-chain carry-forward 누락으로 R007 §3은 R005 target만 직접
결속하고 `CONTINUATION-EXECUTION-HANDOFF-20260730-R005-independent-review-r001.md`
실물의 SHA/bytes와 FAIL 판정을 확인하지 않는다. R006 header에 해당 pin이
있어도 R006 block을 실행하지 않으므로 actual R005 receipt drift는 R007
fail-fast에서 탐지되지 않는다. 두 증상은 같은 원인으로 중복 제거했다.

## 2. 확인된 정상 부분

- R006과 R006 FAIL receipt, R005의 SHA/bytes와 §4 exact extraction은 정확하다.
- §3은 function-wide command substitution을 제거하고 각 내부 캡처에
  `|| return 1`을 명시해 R006 MAJOR를 닫는다.
- §3은 Bash 문법 PASS, 정상 실행 rc=0·stdout 0 bytes다.
- wrong hash/mode, symlink, missing path와 내부 `stat` 실패는 모두 rc=1이고
  predecessor 추출·실행은 0회다.
- pre/post type/owner/mode/nlink/hash/bytes/dev:ino, 두 source 전략 convergence,
  active v2.4/seq39/r021 상태와 승인 금지선은 정확하다.
- target 종료 SHA-256/bytes/lines는 시작과 동일하다.

## 3. R008 correction allowlist

add-only `CONTINUATION-EXECUTION-HANDOFF-20260730-R008.md`는 R007과 이 FAIL
receipt를 결속하고 정확히 다음만 교정한다.

1. R004~R006 handoff/review 여섯 파일을 읽은 뒤, fail-fast 실행 전에
   `R004 §6 item 1~15`를 그 상대순서대로 읽는 단계를 명시한다.
2. R005 FAIL receipt 실물의 SHA/bytes와 FAIL 판정을 R007 block 실행 전에
   exact 검증한다.
3. frozen R007 §3 block을 exact pin으로 추출·실행해 이미 검토된
   fail-closed semantics를 그대로 상속한다.

R007 수정, source 전략 임의 선택, source/checkpoint/checker 변경, candidate
build, authorization request, canonical/Goal/product write는 허용하지 않는다.
