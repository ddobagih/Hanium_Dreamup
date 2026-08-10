# WalkSafe 실행 준비 인계 R008 독립검수 R001

- 검토일: `2026-07-30`
- 대상:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R008.md`
- target SHA-256:
  `11c109046feefdf9d85b41dee43b07719c787709bb1871a845bbb0369550c902`
- target bytes: `6,822`
- target lines: `155`
- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R009`
- findings: `BLOCKING=0 MAJOR=2 MINOR=0`
- 적용 권한: 없음

두 읽기 전용 검토 축은 같은 target의 시작·종료 SHA-256/bytes/lines가
일치함을 확인했다.

| 검토 축 | BLOCKING | MAJOR | MINOR |
|---|---:|---:|---:|
| predecessor 순서·receipt 결속 | 0 | 0 | 0 |
| 실행 환경·검사/사용 물리 결속 | 0 | 2 | 0 |

## 1. 통합 MAJOR 판정

### 1.1 ambient command resolution 미결속

R008 §3은 `stat`, `sha256sum`, `cut`, `wc`, `grep`, `awk`, `bash`를 bare
command로 호출한다. 특히 frozen R007 block의 문법 확인과 실행에 쓰는
`bash`가 absolute executable, clean environment 또는 `BASH_ENV` 금지에
결속되지 않았다.

무해 반례로 exported `bash` function이 성공만 반환하게 한 뒤 R008 §3을
실행하면 frozen R007 block을 한 줄도 실행하지 않고도 outer rc=0,
stdout=0 bytes가 된다. 따라서 R007 review correction allowlist item 3의
exact syntax-check와 실행을 우회할 수 있다.

### 1.2 verified bytes와 consumed bytes의 결속 부재

`ws_check_doc`은 한 path를 type, hash, bytes 검사 때마다 다시 연다. R005와
R007 review도 hash 확인 뒤 `grep`에서 다시 열고, R007은 hash/bytes 확인 뒤
`awk` extraction에서 다시 연다.

따라서 검사 뒤 사용 전 path를 atomic replace하면 검증한 바이트가 아닌
다른 바이트에서 verdict를 읽거나 block을 추출·실행할 수 있다. held FD,
single-read immutable capture 또는 extracted-block digest가 없어 같은
물리 바이트를 검사하고 소비했다는 보장이 없다.

## 2. 확인된 정상 부분

- R004~R006 handoff/review 여섯 파일 뒤 R004 §6 item 1~15, 그 뒤
  R007/review → R008 §3 → R008 review 순서는 정확하다.
- R004 §6 item 1~15의 count/order/path는 원문과 일치하며 현재 모두
  regular non-symlink다.
- R005 FAIL receipt SHA/bytes/verdict와 R007/R007 FAIL receipt pin은
  정확하다.
- 정상 §3은 Bash 문법 rc=0이고 실제 실행 rc=0, stdout=0 bytes다.
- wrong R005/R007 hash·verdict, missing section, malformed block은 현재
  block에서 nonzero로 거부된다.

이 정상 결과는 위 두 우회 경로를 닫지 못하므로 findings-zero가 아니다.

## 3. R009 correction allowlist

add-only `CONTINUATION-EXECUTION-HANDOFF-20260730-R009.md`는 R008과 이 FAIL
receipt를 결속하고 정확히 다음만 교정한다.

1. R005 review, R007과 R007 review를 no-follow single-read로 한 번만 읽고,
   그 고정 바이트에서 hash/bytes/verdict와 R007 §3 block을 함께 검증한다.
2. 추출 block의 SHA-256/bytes를 별도로 pin하고 그 고정 바이트만
   syntax-check와 실행에 전달한다.
3. syntax-check와 실행은 absolute `/bin/bash --noprofile --norc`를
   allowlisted clean environment에서 호출해 exported function, `PATH`,
   `BASH_ENV` 영향을 차단한다.

R008 수정, predecessor 내용 수정, source 전략 임의 선택,
source/checkpoint/checker 변경, candidate build, 승인 요청,
canonical/Goal/product write는 허용하지 않는다.
