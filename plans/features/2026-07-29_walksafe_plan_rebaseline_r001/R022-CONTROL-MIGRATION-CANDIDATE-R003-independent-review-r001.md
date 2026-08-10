# r022 제어계약 전환 설계 R003 독립검수 R001

- 검토일: `2026-07-30`
- 대상:
  `R022-CONTROL-MIGRATION-CANDIDATE-R003.md`
- target SHA-256:
  `aa60c7788779fd86746af4b82e9e62a56f33e34553d39e1c48a55b649ac9c908`
- target bytes: `74,328`
- target lines: `1,131`
- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_DESIGN`
- 적용 권한: 없음

## 1. 독립검수 결과

세 검토는 같은 target SHA-256/bytes를 시작·종료 시 재확인했다. 축 사이에 중복
원인이 있으므로 findings 수를 합산해 하나의 수로 가장하지 않는다.

| 검토 축 | BLOCKING | MAJOR | MINOR |
|---|---:|---:|---:|
| 승인·authority·launcher 보안 | 2 | 5 | 1 |
| journal·filesystem·crash 내구성 | 1 | 7 | 0 |
| final23·discovery·gate 교차계약 | 1 | 2 | 1 |

어느 축도 findings-zero가 아니므로 R003은 구현 또는 후보 발행 기준으로 사용할
수 없다.

## 2. 통합 BLOCKING 판정

### 2.1 authority root의 TOFU

후보가 검증에 사용할 authority policy와 root public key를 함께 제공하면 후보가
자기 승인권자를 선택하는 trust-on-first-use가 된다. R003 candidate, candidate
review 또는 같은 gate initializer가 root of trust가 될 수 없다.

후속 설계는 source checkpoint 이전에 존재하고 후보 밖의 external trust store가
pin한 root/issuer/purpose/repository ACL만 허용해야 한다. 그 선행 anchor와
revocation state를 검증할 수 없으면
`PRODUCTION_APPLY_UNAVAILABLE_WRITE_ZERO`다.

### 2.2 비결정적 외부 bytes와 expected-hash intent의 무한 회귀

외부 서명, quick/full command output, host/runtime attestation처럼 실행 뒤에만
알 수 있는 bytes를 publish하기 전에 expected hash intent를 요구하면 그 intent를
만들기 위한 또 다른 비결정적 입력이 필요해진다. deterministic internal record와
opaque external/runtime ingress를 같은 atomic-publish 규칙으로 처리할 수 없다.

후속 설계는 두 primitive를 분리해야 한다.

- deterministic publish:
  선행 durable inputs로 expected bytes를 전부 재계산할 수 있는 record와 final
  output
- opaque durable capture:
  trusted producer가 broker-owned spool에 raw bytes를 직접 기록하고 file/parent
  fsync한 뒤 immutable physical binding과 producer signature를 별도 descriptor로
  결속

candidate가 생성한 unsigned bytes는 opaque trusted input이 아니다.

### 2.3 raw output suffix 비내구

R003은 child 종료 뒤 stdout/stderr 원문을 supervisor memory에만 두고
result-intent에는 hash/length만 보존한다. intent 또는 raw temp 중간 crash 뒤에는
사라진 suffix를 복원할 수 없고 command 재실행도 금지돼 resume이 불가능하다.

후속 설계는 command 시작 전에 broker-owned stdout/stderr spool을 만들고 child
출력을 직접 기록해야 한다. child 종료 후 spool file과 parent를 fsync하고,
그 durable inode/raw hash/bytes로 result descriptor를 만든 뒤 final raw 이름으로
승격한다. spool durability 전 crash는 terminal unknown이며 이후 crash만 exact
resume한다. quick, transition post-check와 승인 2 full19에 동일하게 적용한다.

### 2.4 recovery discovery bootstrap 도달 불가

single-use claim 직후 또는 final index 1~11에서 crash하면 이미
`TRANSITION_RECOVERY_REQUIRED`지만 새 discovery는 index 12, AGENTS/README
selectors는 index 13 이후다. 현재 v2.4 경로는 gate claim/progress를 읽지 않으므로
새 세션이 정상 v2.4로 오진할 수 있다. 단순 promotion reorder도
claim-before-first-promotion 구간을 닫지 못한다.

후속 설계는 main transition과 분리된 source-compatible selector-preflight를 먼저
완료해야 한다. 이 preflight는 현재 v2.4 steady 의미를 보존하면서 gate
consumption/claim/progress를 읽어 recovery entrypoint로 라우팅하고, 자체
별도 승인·atomic 적용·검수·receipt가 필요하다. 선행 selector가 없으면 main
single-use claim과 final promotion을 시작하지 않는다.

## 3. 필수 MAJOR 보완

### 3.1 authorization와 launcher

- quick PASS 뒤 실제 claim 소비 시점에도 external trusted-now, one-use token,
  ACL/revocation을 다시 검증한다.
- archived candidate policy는 선행 external root를 대체하지 않는다.
- external supervisor가 검증한 interpreter FD와 실제 exec 대상은
  `execveat`/동등 FD execution으로 동일해야 한다.
- quick도 fixed lock/host-exclusive lease와 같은 supervisor 아래 exact absolute
  argv/cwd에서 실행한다. relative candidate path가 lock 밖에서 바뀌지 않는다.
- 승인 2 full19 receipt는 독립 trusted producer/verifier와 signed raw
  provenance를 가져야 한다.
- full19 sandbox는 declared tool/source/config 외 read를 syscall/audit trace로
  거부하고 실제 transitive inputs를 receipt에 결속한다.
- `ANDROID_HOME`과 `ANDROID_SDK_ROOT`는 모호한 literal이 아니라 같은 pinned
  absolute value로 명시한다.

### 3.2 journal과 filesystem

- `attestation → CLAIM_INTENT → claim`의 비순환 방향을 둔다. claim expected
  bytes를 attestation이 역참조하지 않는다.
- gate bootstrap은 phase별 ancestor entry-set, policy archive 전/후 상태와
  candidate 밖의 signed bootstrap root intent를 가진다.
- temp 생성 전 intent에는 미래 inode를 넣지 않는다. fixed lock 아래 유일한
  absent temp를 만든 뒤 durable spool/confirmation에서 inode를 처음 결속한다.
- 새 directory는 phase × `ABSENT | EXACT | DIVERGED` recovery table을 가지거나
  mkdir→child fsync→parent fsync 완료 뒤 inode를 처음 확인하는 단순 계약으로
  바꾼다.
- aggregate post-check outcome도 ordered raw bindings와 expected outcome
  bytes를 결속한 `POSTCHECK_OUTCOME_INTENT` 뒤 publish한다.
- non-UTF8 path는 `path_b64u`/`child_name_b64u`, raw-byte lexical ordering,
  raw length/hash로 canonicalize한다.
- full19 check 18 뒤 writable upper manifest를 봉인·분리하고 check 19는 clean
  lower-only repository view에서 실행한다.
- resolved manifest durable publish와 progress bootstrap/journal의 선후 관계를
  하나의 DAG로 고정한다.

### 3.3 discovery와 승인 2

- control-plane one-use token이 소비된 consumption attestation부터 공개 상태는
  `TRANSITION_RECOVERY_REQUIRED`다. authorization/quick까지만 `V24_STEADY`다.
- discovery recovery predicate는 claim final뿐 아니라 same-transaction claim
  temp/spool과 consumed attestation도 포함한다.
- 승인 2 gate root는 bootstrap/container lattice, 19개 start/result intent,
  raw spool, outcome, terminal/recovery와 receipt producer/verifier를 exact
  role table로 가진다.
- R003 gate exact role table에 final23 index 5
  `application-transaction-plan.json`을 포함한다.
- resolved manifest는 preauthorized expected-after와 실제 observed-after를
  구분한다. 존재하지 않는 transform target을 actual-after로 가장하지 않는다.
- incident XOR receipt는 정상 publication 계약이다. divergence 증거가 둘을
  동시에 남긴 물리 상태를 valid terminal로 오인하지 않고 recovery
  fail-closed로 분류한다.

## 4. 확인된 정상 부분

- exact final member는 23개이고 checkpoint는 index 23 마지막 commit point다.
- quick 2개, full19와 read-only post-check 3개의 canonical digest는 선언값과
  독립 재계산값이 일치한다.
- target checkpoint와 durable post-commit receipt 전 상태를 steady ACTIVE와
  구분하고 Goal/product authority를 막는 방향은 타당하다.
- fixed lock의 advisory 한계, broker/lease loss 시 추가 write 0과
  incident/receipt의 단방향 DAG는 유지할 수 있다.
- product tracked/untracked/ignored/deleted 실제 content double-read가 포함됐다.

## 5. 보존 경계와 다음 행동

이 검수 중 다음은 모두 불변이다.

- active control: v2.4 / sequence 39 / canonical r021
- artifact: complete `126/257`, open `131`
- formal `0/279`, actual-device `0`, release gate `0/5`
- release `NOT_ELIGIBLE`
- canonical r022, physical v2.5 candidate와 active files: absent
- Goal/event, FP-008 materialization과 제품 코드 delta: 0

다음 허용 행동은 이 문서의 findings를 반영한 add-only
`R022-CONTROL-MIGRATION-CANDIDATE-R004.md`를 만들고 새 exact SHA-256/bytes에
대해 다시 독립검수하는 것이다. R003 수정, 구현, 후보 발행, authorization request,
canonical 적용과 FP-008 시작은 허용하지 않는다.
