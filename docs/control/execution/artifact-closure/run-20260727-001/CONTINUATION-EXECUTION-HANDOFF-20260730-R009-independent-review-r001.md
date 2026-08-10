# WalkSafe 실행 준비 인계 R009 독립검수 R001

- 검토일: `2026-07-30`
- 대상:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R009.md`
- target SHA-256:
  `c5cef4f0f0b7dd79d63df10500b0aa47bfe788a9f88551c06ee5ab18b25a0047`
- target bytes: `7,797`
- target lines: `205`
- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R010`
- findings: `BLOCKING=0 MAJOR=1 MINOR=0`
- 적용 권한: 없음

두 읽기 전용 검토 축은 같은 target의 시작·종료 SHA-256/bytes/lines가
일치함을 확인했다.

| 검토 축 | BLOCKING | MAJOR | MINOR |
|---|---:|---:|---:|
| single-read·검사/사용 결속 | 0 | 0 | 0 |
| outer bootstrap 환경 격리 | 0 | 1 | 0 |

## 1. 통합 MAJOR 판정

### 1.1 R009 §3 자체를 시작하는 outer shell이 ambient 상태

R009는 추출한 R007 block의 syntax-check와 실행을 `env -i`의 clean inner
shell로 옮겼다. 그러나 R009 §3 block 자체는 이미 ambient Bash에서 시작하며
embedded Python과 guard를 실행하기 전에 outer `BASH_ENV`와 exported
function의 영향을 받을 수 있다.

무해 반례에서 `BASH_ENV`가 absolute-path 이름의 Python function을 정의해
trivial Bash block의 base64만 반환하도록 하면 embedded pin/single-read를
실행하지 않고도 outer rc=0, stdout=0 bytes가 됐다. 다른 반례에서는
outer `set`과 `exit` function으로 fail-fast guard를 무력화했다.

따라서 R008의 ambient execution root cause가 전체 checker bootstrap
경계에서는 완전히 닫히지 않았다. R009 block 전체를 clean external launcher
아래서 시작하는 별도 envelope가 필요하다.

## 2. 확인된 정상 부분

- R005/R007/R008와 review는 `O_NOFOLLOW`, single FD, single-read로 읽힌다.
- 같은 captured bytes에서 hash/bytes/verdict를 검증하고 R007 §3을 추출한다.
- 추출 block은 `4,202 bytes`,
  SHA-256
  `95bbe9a79e78365f2539660f837da2065d8ffd0b1f54ce2b4fbab9a88e55ae47`
  로 별도 결속된다.
- read 뒤 path 교체와 symlink perturbation은 consumed bytes를 바꾸지 못한다.
- inner syntax/실행은 clean environment와 absolute `/bin/bash`를 사용한다.
- 정상 block은 syntax rc=0, 실행 rc=0, stdout=0 bytes다.
- clean outer에서 wrong document hash/verdict, wrong block hash와 symlink
  document는 모두 nonzero로 거부된다.

## 3. 후속 경계

R009를 findings-zero 재개 근거로 사용하지 않는다. 후속 execution handoff가
정말 필요하면 R010은 entire checker를 clean external launcher에서 시작하고
그 launcher의 trust boundary를 명시해야 한다.

이번 종합 인계 작업에서는 execution-handoff succession 자체가 제품·artifact
진척을 만들지 않으므로 R010을 생성하지 않는다. 새 터미널은 별도 findings-zero
종합 인계서의 단순 read-only 사실 확인을 사용하고, source write 전 사용자의
exact 전략 결정을 받는다.

R009/R009 review는 source 전략, source/checkpoint 변경, candidate build,
승인 요청, canonical/Goal/product write 권한이 아니다.
