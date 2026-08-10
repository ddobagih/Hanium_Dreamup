# WalkSafe R022 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R022-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent = /root/r022_structural_review
reviewer_axis = I31_S0_P2A_T0_P2C_T1_S1_SUPERVISOR_P3_PERSISTENT_ANCHOR_VALIDATOR_DAG_TERMINAL_NAMESPACE_R001_R002_TEST_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R022.md
target_sha256 = 2a05166af626fae929decf0fef28fc6c70b4b448a312e6e73ba2aa9e8cac3caa
target_bytes = 18978
target_lines = 456
reviewed_at = 2026-08-02T12:45:50+09:00
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
authority_granted = NONE
```

## 범위와 관측 상태

R022 456줄 전체를 accepted R016, rejected R017~R021과 각 structural/skeptical review,
현재 three-source S0, immutable failed r001, reviewed R002 pair/review, live C0 및 예정된
P2A/P2C/r002 target 상태에 읽기 전용으로 대조했다. 검토 시작과 문서 추가 직전 target은
SHA-256 `2a05166af626fae929decf0fef28fc6c70b4b448a312e6e73ba2aa9e8cac3caa`,
18,978 bytes, 456 lines인 regular file이고 mode `0664`, uid/gid `1000/1000`, nlink 1이었다.
source/build/test/checker는 실행하지 않았고 source, r001, r002, canonical, checkpoint,
Goal, 제품을 수정하지 않았다. 이 review 파일만 add-only로 추가했다.

§1.1의 C0, reviewed R002 pair/review, R016~R021 roadmap과 각 review의 SHA-256/bytes는
현재 파일과 모두 일치했다. R016 dual review만 `PASS 0/0/0`이며 R017~R021과 그 review의
authority가 `NONE`이라는 계보도 일치한다. 현재 S0와 정적 test method universe는 다음과
같다.

| source | SHA-256 | bytes |
|---|---|---:|
| validation core | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| builder | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| test | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

정적 선언은 정확히 37개 test method다. failed r001은 exact-six만 가진 non-symlink
directory이고 six content SHA/bytes가 §2와 일치하며 현재 NUL-terminated bytewise
entry-name digest는
`f4c9e884242b5acf190f0cb95d1567845173d8ae8e3a20919d81519f88b5d86c`다. 검토 시작 시
R022 structural/skeptical review, P2A, P2C, r002 root 및 r002 candidate review pair target은
모두 absent였다.

## R021 blocking 교정 판정

- §7은 P2C raw bytes를 UTF-8, sorted keys, compact separators, no BOM/duplicate/non-finite,
  terminal LF로 유일하게 고정한다. top-level과 두 nested binding의 exact keys/values,
  `type(value) is int`, source row ordinal `1,2,3`, role/path/order 및 R021 FILE/9-field
  `lstat` schema를 함께 고정하므로 R021 structural BLOCKING-01은 닫힌다.
- §8은 P2C 직후 full T1과 exact S1 rows를 `SOURCE_TRANSITION_ANCHOR`로 잡고 pre-P3
  validator/builder/publication API의 expected argument를 mandatory로 만든다. P2C add부터
  P3 parent-fsync까지 같은 supervisor가 original object를 보유하고 current-live baseline
  adoption을 금지한다. package와 output manifest의 exact top-level
  `/source_transition_evidence`가 deep-equal하고 각 logical seal에 들어가며, P3 뒤 validator는
  sealed candidate에서만 expected anchor를 파생한다. 이 구조는 R021의 dynamic-current
  adoption과 coupled S1+P2C rewrite 결함을 의도상 닫는다.

그러나 T0와 T1 모두 **add-created file의 최초 physical identity를 supervisor에 원자적으로
전달하지 않는다.** 아래 finding 때문에 의도한 first identity가 아니라 최초 사후 관측값을
권위 baseline으로 채택할 수 있다.

## Findings

### BLOCKING-01 — P2A/P2C Add File과 최초 T0/T1 사이의 first-observation race가 닫히지 않는다

§5는 P2A를 `apply_patch Add File`로 공개한 **뒤** 같은 supervisor가 no-follow read와
`lstat`으로 T0를 포착한다. §8.1도 P2C `apply_patch Add File` 성공 **뒤** 같은 방식으로
T1을 포착한다. 그러나 두 publication operation이 생성한 열린 file descriptor, 생성
직후 `fstat` tuple 또는 publication subprocess가 봉인한 inode tuple을 supervisor에
원자적으로 handoff하는 계약은 없다. `apply_patch` 성공 반환과 다음 no-follow open/lstat
사이에 별도 process가 개입할 수 있다.

다음 두 변형은 현 계약을 만족할 수 있다.

```text
P2A add succeeds -> same-byte regular-file inode replacement/touch -> supervisor captures replacement as T0
P2C add succeeds -> same-byte regular-file inode replacement/touch -> supervisor captures replacement as T1
```

same-byte replacement는 canonical content/schema/SHA/bytes 검사를 통과하고, replacement의
mtime/ctime은 각각 `selected_ns` 또는 `captured_ns` 뒤이므로 §5와 §8.1의 physical-time
부등식도 통과한다. supervisor는 사전에 알고 있던 add-created tuple이 없으므로 새 inode를
drift로 구분하지 못한다. 이후 S1 source constants 또는 r002
`/source_transition_evidence`가 그 replacement tuple을 봉인하면 모든 새 process와
post-P3 validator가 최초 publication이 아닌 사후 값을 정상 baseline으로 승계한다.
uninterrupted supervisor는 T1을 **포착한 뒤**의 drift만 닫고 이 최초 포착 전 창은 닫지
않는다.

이는 R020 skeptical BLOCKING-02의 persistent physical handoff를 뒤로 미룬 형태다. 최소
교정은 P2A와 P2C 각각에서 publication을 수행하는 동일한 supervisor가 생성 시점부터 열린
descriptor를 보유하고 file fsync 전후 `fstat` identity와 exact bytes를 봉인한 뒤, 그 exact
created identity를 T0/T1로 직접 넘기는 하나의 원자적 protocol을 명시하는 것이다. 또는
publication subprocess가 authenticated exact created tuple을 supervisor에 전달하고 target
no-follow tuple과 일치시키는 동등한 CAS가 필요하다. mismatch, touch, chmod, link,
same-byte replacement, descriptor loss 및 publication ambiguity는 current tuple 재채택 없이
각 epoch의 new-revision authority-zero terminal이어야 한다.

`BLOCKING=1`.

## 나머지 구조 판정

| 축 | 판정 | 근거 |
|---|---|---|
| exact I31 | CLOSED | R021 rows 1~21, R022 trio 22~24, failed-r001 root+six 25~31의 ordinal/order/role/path가 유일하고 FILE/DIRECTORY/9-field lstat/semantic review/max 규칙을 exact 상속한다. |
| S0 cutoff / S1 | CLOSED | S0 live equality는 P2B 시작 직전까지만이고 한 three-file patch 뒤에는 historical before evidence다. S1은 세 full FILE rows로 P2C와 anchor에 고정된다. |
| P2A canonical capture | BLOCKED_BY_B01 | I31/S0 causal max, one committed microsecond-floor selection, strict nonfuture bounds와 target-absence/no-resume는 닫혔지만 add-created identity와 T0 사이가 원자적이지 않다. |
| P2C canonical schema/types | CLOSED | raw serialization, exact schemas, int-not-bool, digest bytes, row ordinals/roles/paths/null semantic fields와 captured bound가 하나의 P2C bytes를 결정한다. |
| T1 mandatory API/supervisor | BLOCKED_BY_B01 | expected-anchor mandatory/None 금지와 P2C 이후 uninterrupted comparison은 닫혔지만 original add-created T1을 보장하지 못한다. |
| package/output persistent anchor | CLOSED_AFTER_VALID_T1 | exact top-level pointer의 deep-equal anchor가 package/output seal에 들어가고 package가 output을 back-reference하지 않아 기존 one-way hash boundary를 유지한다. 단 B01 때문에 입력 T1의 authority가 성립하지 않는다. |
| post-P3 validator | CLOSED_AFTER_VALID_T1 | sealed candidate pointer만 expected anchor로 사용하고 P2A/T0, P2C/T1, live S1 full tuple/content를 재검사해 current-live 재채택을 금지한다. |
| cycle | CLOSED | `S0 -> P2A/T0 -> S1 -> P2C/T1 -> package -> output manifest`이고 package는 output을 참조하지 않으며 P2C는 future hash/path를 담지 않는다. |
| terminal/retry | CLOSED_EXCEPT_FIRST_OBSERVATION | P2A/P2B/P2C/P3의 crash·existing·partial·race·fsync ambiguity는 same-revision repair/resume 없이 new-revision authority-zero로 끝난다. B01 창만 baseline drift로 인식되지 않는다. |
| R002 namespace / r001 | CLOSED | R002 bundle/gate/review paths와 IDs, historical r001 allowlist, r001 exact-six content/full physical seal 및 `RECOVERED_EXACT_EXISTING` 제거가 유지된다. |
| 37 tests | BLOCKED_BY_B01 | static method 수와 pre/post 37, skip 0, exit 0, OK 계약은 정확하지만 add-created inode와 최초 T0/T1 사이의 authoritative expected tuple이 없어 first-observation race negative oracle을 만들 수 없다. |
| authority | CLOSED_ZERO | activation/canonical/checkpoint/Goal/product/formal/device/release write·credit은 0이고 finding이면 P2A 이후 write가 열리지 않는다. |

## 명시적 count/status/authority

```text
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
source_build_test_executions = 0
source_writes = 0
r001_writes = 0
r002_writes = 0
capture_writes = 0
postimage_writes = 0
canonical_checkpoint_goal_product_writes = 0
formal_device_release_credit = 0
authority_granted = NONE
```

R022는 `0/0/0`이 아니므로 `PASS` 또는
`R022_R002_CAPTURE_SOURCE_CANDIDATE_CORRECTION_ONLY` 권한을 부여할 수 없다. P2A/P2C
publication-created identity를 T0/T1에 원자적으로 전달하는 새 roadmap revision과 새 dual
review가 필요하다. 이 공통 first-observation handoff 외의 추가 독립 blocker는 발견하지
못했다.
