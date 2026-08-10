# r022 제어계약 전환 설계 R002 독립검수 R001

- 검토일: `2026-07-30`
- 대상:
  `R022-CONTROL-MIGRATION-CANDIDATE-R002.md`
- target SHA-256:
  `718e1c06549eb286d9b55abf8648b46b8831af4ef862b258de6290994987c47c`
- target bytes: `29,640`
- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_DESIGN`
- 적용 권한: 없음

## 1. 독립검수 결과

세 검토는 같은 target SHA-256/bytes를 검토 전후에 재확인했다. 축 사이에 같은
원인이 중복되므로 findings 수를 더해 하나의 수로 가장하지 않는다.

| 검토 축 | BLOCKING | MAJOR | MINOR |
|---|---:|---:|---:|
| 승인·보안·권한 | 3 | 4 | 0 |
| 내구성·복구·결정성 | 2 | 5 | 1 |
| ACTIVE·discovery·명령 계약 | 2 | 4 | 1 |

어느 축도 findings-zero가 아니므로 R002는 구현 기준으로 사용할 수 없다.

## 2. 통합 BLOCKING 판정

### 2.1 미래 record와 transform의 hash cycle

pre-authorization package/output manifest가 이후 authorization와 fresh quick gate로
결정되는 final history/checkpoint physical hash, resolved manifest, post-check와
post-commit receipt의 실제 hash를 미리 선언하게 되어 있다. authorization receipt가
후행 resolved manifest를 결속한다는 문구도 forward DAG와 충돌한다.

후속 설계는 copy member의 expected physical hash만 사전 확정하고, transform
member는 ordered input slot과 transform spec만 승인해야 한다. 실제 transform
hash/bytes는 authorization와 quick gate 이후 resolved manifest에서 처음 확정한다.
pre-authorization package는 미래 durable record의 role/path/schema/transaction-ID
도출 규칙만 선언하고 실제 hash를 역참조하지 않는다.

### 2.2 동일 transaction 복구 증거 부재

현재 파일과 checkpoint만 보면 exact after bytes가 같은 transaction에서 만들어진
것인지 다른 writer가 만든 것인지 구분할 수 없다. 특히 CAS replace 뒤 crash는
승인된 resume과 외부 drift를 동일하게 보이게 한다.

후속 설계에는 transaction-scoped add-only progress journal이 필요하다. 각 promotion
index의 durable intent, before tombstone 또는 inode/hash, expected after hash,
rename/replace와 parent-fsync 완료를 append-only phase record로 남겨야 한다.
복구는 `before`, `exact after + same durable intent`, `diverged`를 구분해야 한다.

### 2.3 실제 사용자 승인 provenance 부재

공개된 accepted string과 로컬 raw 파일의 byte equality만으로는 실제 사용자
응답과 로컬 synthetic/replay를 구분할 수 없다. 기존 문자열은 request hash,
transaction ID, nonce, repository identity와 channel/session epoch도 직접
결속하지 않는다.

후속 설계는 비순환 authorization challenge digest, single-use nonce, issued/expires,
repository identity와 channel/session epoch를 exact response에 포함해야 한다.
외부 control-plane의 conversation/message/author identity, server timestamp,
raw response SHA-256/bytes와 서명 또는 attestation을 검증할 수 없으면 production
apply를 금지해야 한다. single-use claim도 add-only로 내구화한다.

### 2.4 writer bootstrap과 최소권한 경계 부재

최종 멤버인 writer가 아직 final path에 없는데도 “그 writer만 쓴다”고 선언해 최초
실행 주체가 순환한다. staged writer의 interpreter/import closure, 환경과 write
scope도 고정되지 않았다.

후속 설계는 기존 trusted launcher가 검토된 staged writer의 exact path/hash/bytes를
열어 실행하는 bootstrap을 정의해야 한다. production receipt verifier를 test
fixture와 분리하고, sanitized environment, network 차단, exact write-path allowlist,
열린 executable/source identity 유지와 production TEST flag 부재를 고정한다.

### 2.5 마지막 CAS와 checkpoint commit 사이 TOCTOU

협력적 lock을 따르지 않는 동일 UID writer는 마지막 CAS 재검증 뒤 checkpoint
replace 전에 product/control 입력을 바꿀 수 있다. checkpoint가 commit된 뒤
post-check가 실패해도 이를 steady ACTIVE로 해석하면 승인 범위를 벗어난 상태에서
후속 제품 작업이 시작될 수 있다.

후속 설계는 지원 threat model과 repository-wide writer exclusion 또는 OS-level
isolation을 명시하고, dirfd/descriptor identity를 checkpoint 직전까지 다시
검증해야 한다. post-commit receipt 전 상태는 steady ACTIVE가 아니라
`COMMITTED_RECOVERY_REQUIRED`이며 Goal materialization과 제품 작업을 기계적으로
차단해야 한다. target checkpoint 이후 rollback이나 v2.4 재해석은 금지한다.

### 2.6 ACTIVE discovery와 새 세션 경로 폐쇄 누락

final 17에는 현재 v2.3/v2.4 경로를 고정하는 `AGENTS.md`,
`README.md`, `docs/control/README.md`, `docs/control/goals/README.md`, 새 v2.5
Goal Graph README와 candidate-root 독립 ACTIVE 회귀시험이 없다. checkpoint 전
공유 문서 일부만 교체하면 현재 v2.4 checker도 실패한다.

후속 설계는 이 경로들을 staged CAS/add-only member로 포함하고, source v2.4,
transition recovery, target v2.5의 3-way discovery를 정의해야 한다. checkpoint
전 혼합 상태는 v2.4 steady PASS가 아니라 same-transaction recovery-only여야 한다.

## 3. 필수 MAJOR 보완

- 모든 control transition을 직렬화하는 저장소 고정 lock 경로와
  `O_NOFOLLOW`, owner/mode/nlink/inode, `FD_CLOEXEC`, `flock` 생명주기를 고정한다.
- advisory lock을 무시하는 writer까지 kernel CAS가 막는다고 주장하지 않고,
  지원 filesystem/host/cooperating-writer 또는 격리 경계를 명시한다.
- final member에 고정 promotion index, copy source와 transform ordered inputs,
  mode/owner/regular-file/canonical serialization 계약을 넣는다.
- 아직 없는 v2.5/gate parent directory의 `mkdirat`, child/ancestor fsync,
  symlink 거부와 crash recovery를 transaction에 포함한다.
- post-check attempt를 실행 전에 add-only로 만들고 argv, exit code,
  stdout/stderr raw hash/bytes를 봉인한다. 실패 incident는 terminal이며 이후
  PASS receipt를 영구 금지한다.
- post-commit receipt는 `ABSENT | EXACT | DIVERGED`와 rename 뒤 parent-fsync
  복구를 정의하고 incident와 상호 배타적으로 유지한다.
- 승인 2도 승인 1과 다른 challenge/prefix/nonce/expiry/evidence schema로
  FP-008 exact materialization manifest와 승인 1 receipt를 결속한다.
- source/target/parent는 dirfd 기반 no-follow stable identity로 읽고 owner,
  mode, nlink와 before/after identity를 검증한다.
- product inventory에는 Git ignore inputs, `GIT_*`, Git/interpreter/PATH와
  ignored classification의 결정성을 포함하거나 raw filesystem inventory로
  대체한다.
- ACTIVE는 candidate builder/bundle/design/test를 다시 열지 않는다. native,
  protected와 managed snapshot exact set을 final package에 고정한다.
- full 19-check의 1~19 exact command/order/digest와 repository-state의 Git
  명령, config/env exclusion, NUL-framed raw 표현과 double-read 안정성을 고정한다.
- authorization response는 request SHA-256, transaction ID와 nonce를 직접
  결속해 동일 candidate의 과거 응답 재사용을 거부한다.

## 4. 부수 보완

- 존재하지 않는 active discovery before state는 SHA/bytes가 아니라 explicit
  absent tombstone으로 기록한다.
- R002 activation approval runbook을 실제로 읽으면 `validation_inputs`에
  결속하고, 읽지 않으면 명시적 non-input으로 선언한다.

## 5. 보존 경계와 다음 행동

이 검수 중 다음은 모두 불변이다.

- active control: v2.4 / sequence 39 / canonical r021
- artifact: complete `126/257`, open `131`
- formal `0/279`, actual-device `0`, release gate `0/5`
- release `NOT_ELIGIBLE`
- canonical r022, v2.5 physical candidate와 active files: absent
- Goal/event, FP-008 materialization과 제품 코드 delta: 0

다음 허용 행동은 이 문서의 findings를 모두 반영한 add-only R003 설계를 만들고,
새 exact SHA-256/bytes에 대해 다시 독립검수하는 것이다. R002 구현, 후보 발행,
canonical 적용과 승인 요청 생성은 허용하지 않는다.
