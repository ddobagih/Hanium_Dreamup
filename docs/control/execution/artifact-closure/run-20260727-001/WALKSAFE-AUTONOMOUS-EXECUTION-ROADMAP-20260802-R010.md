# WalkSafe 자율 실행 로드맵 20260802 R010

## 0. 지위와 frozen input

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R010
document_class = INTERNAL_EXECUTION_ROADMAP_ADD_ONLY_CORRIGENDUM
state = PENDING_TWO_R010_PLAN_REVIEWS
state_machine = REVIEW_BARRIER_THEN_CONDITIONAL_E1_OR_R011
draft_source_execution_allowed = false
network_allowed = false
live_product_or_canonical_mutation_allowed = false
official_progress_delta = 0
artifact_completion_credit_delta = 0
release_claim = NOT_ELIGIBLE
```

R010은 다음 세 파일과 결과를 frozen input으로 삼는다.

| input | SHA-256 | bytes | lines/result |
|---|---|---:|---:|
| R009 roadmap | `bce7b9dbac301e09f1083b4bc92d892d282b421bfaad0b75ec8d8cd0a6c9ce13` | 12404 | 209 |
| R009 structural review | `86c5ed94edadfe8c6d0e1fb3319216f083e741d87e5705e65b77cb9e0366ced4` | 7299 | 129, PASS 0/0/0 |
| R009 skeptical review | `7fc2c88498e2ab1e1524dea46f24313dce4263d0012c07dc47c4bde7b268b9de` | 8323 | 120, REVISION_REQUIRED 3B/2M/0m |

두 R009 review는 서로 다른 agent가 같은 R009를 독립 검수했다. 두 file이 모두 terminal인
것을 root가 확인한 뒤에만 이 successor를 작성했다. R009의 draft/final/evidence root는 모두
absent이며 앞으로도 write하지 않는다. R008 draft exact 9와 evidence exact 4는 immutable
rejected predecessor, R008 final root는 absent로 유지한다.

R009 본문은 아래 exact replacement를 적용한 뒤 R010의 normative base다. 충돌하면 R010이
우선하며, R009의 `E_FAIL_R010`은 이 파일 하나의 authoring만 허용했다.

## 1. 권한 상태기계와 두-review barrier

R009 §1, §6의 plan-authority 부분과 §7을 다음 상태기계로 교체한다.

```text
S0_PENDING_REVIEWS:
  allowed = exact two R010 plan-review Add File writes only
  E1/source execution/R011 = 0

BARRIER:
  both reviewer tasks terminal
  and exact two review files frozen
  and same R010 SHA/bytes/lines
  and distinct reviewer_agent/session
  and complete finding union reconstructed by designated root author

S1_E1_AUTHORIZED:
  BARRIER
  and both reviews PASS 0/0/0
  and contemporaneous LIVE_USER_AUTHORITY immediately before E1
  and user cancellation/replacement absent

S_FAIL_R011_AUTHORIZED:
  BARRIER
  and either review has any nonzero severity or identity/independence failure
```

review 결과는 필요조건일 뿐 live authority를 만들거나 재생하지 않는다. S1에서 허용되는 것은
§2의 create-only corrected draft/evidence authoring뿐이며 source 실행, import, pycompile,
publication, projection은 계속 0이다. 첫 review finding만 보고 R011을 선점할 수 없고, 두
review가 모두 terminal이 되기 전에는 successor write가 0이다. 같은 barrier를 두 source
review에도 적용해 late finding을 누락하지 않는다. successor author는 root 한 명뿐이다.

## 2. one-shot path와 exact write set

```text
draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r010-r001
final_candidate_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r010-r001
evidence_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r010-r001
```

세 path는 R010 review 전에 모두 absent여야 한다. E1은 draft/evidence만 만들고 final은
absent로 둔다. 실패·crash·finding 뒤에는 repair, delete, resume, rerun하지 않고 새 successor
suffix만 쓴다.

| Epoch | exact write set |
|---|---|
| `E0_R010_AUTHOR` | 이 R010 파일 하나 |
| `E0_R010_REVIEW` | §8의 plan review 두 파일만, 각 reviewer가 자기 파일을 `Add File` |
| `E1_CORRECTED_DRAFT` | S1일 때만 draft exact 9와 evidence exact 4 |
| `E2_SOURCE_REVIEW` | §7의 source review 두 파일만, 각 reviewer가 자기 파일을 `Add File` |
| `E3_DAYLOG_APPEND` | §6의 exact daylog에 terminal block 하나만 |
| `E_FAIL_R011` | 해당 plan/source review 두 개가 모두 terminal인 뒤 `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R011.md` 하나 |

confined `apply_patch Add File` 계약은 E1 candidate/evidence와 신규 roadmap/review 파일에만
적용한다. 기존 file의 Update/Delete/rename/truncate/chmod는 §6 daylog 단일 예외 외에는
모두 0이다. local-memory 기록은 E3 뒤 별도 automation epoch다.

## 3. R009 skeptical finding closure

| finding | R010 closure |
|---|---|
| B-01 frozen scope conflict | §1이 frozen header를 순간 allowlist로 재해석하지 않고 S0→BARRIER→S1/S_FAIL 전이를 완전히 정의한다. S1에도 E1 직전 live authority를 별도로 요구한다. |
| B-02 before-first/claim gap | §4.1의 durable two-file attempt commit 뒤에만 child를 spawn하고 `publish-crash-before-first`를 exact registry에 복구한다. |
| B-03 late-review race | §1 barrier가 같은 단계의 두 review terminal과 complete union 전 successor write를 금지한다. |
| M-01 tautological negative oracle | §4.2~§4.4가 exact 30 IDs, 역할별 expected RC/class/JSON, output와 full snapshot oracle을 고정한다. |
| M-02 C13 role ambiguity | §5가 공통 Git confinement, producer 허용 delta, verifier read-only equality를 분리한다. |

작성자 자체 점검 A-01도 함께 닫는다. R009의 broad `confined Add File` 상속과 existing daylog
append 사이 해석 차이를 §2와 §6의 단일 Update 예외로 제거한다.

## 4. C10 replacement: durable attempt와 exact oracle

R009 C10과 exact-25 목록을 이 절로 전부 교체한다. caller가 argv, env, producer, expected,
timeout, allowlist, claim root를 선택하는 generic scenario/recipe 입력은 없다.

### 4.1 producer-negative attempt commit

아래 producer case 22개마다 sandbox 안 고정 path는
`/control/negative/<case-id>/attempt`이고 output root와 분리된다. controller는 pinned
case-root fd와 registry literal basename만 사용한다. attempt root는 처음에 exact empty,
regular ancestor, mode `0700`, uid/gid `1000/1000`, same-device다.

child spawn 전 다음 두 regular `0600`, uid/gid `1000/1000`, nlink 1 파일을 순서대로
`O_EXCL|O_NOFOLLOW`로 create/write-all/fsync하고 각 단계 parent fsync를 수행한다.

```text
attempt.claim
ATTEMPT.COMMITTED
```

`attempt.claim`은 canonical JSON+LF로 schema, case id, reviewed producer basename/SHA,
reviewed draft seal SHA, exact controller argv/env/fixed-FD roles, output-root dev/ino, timeout을
담는다. marker exact bytes는 `WS-WALKSAFE-NEGATIVE-ATTEMPT-V1\nclaim_sha256=<sha>\n`이다.
marker exact commit 뒤에만 child를 spawn한다. claim/marker absent·partial·wrong·conflicting이면
child 0, repair 0이고 각각 `ATTEMPT_ABSENT`, `ATTEMPT_INCOMPLETE`, `ATTEMPT_CONFLICT`로 새
scratch를 요구한다. exact committed pair가 이미 있으면 read-only identity 재검산 뒤 child
진입 전 rc `81`, canonical stderr
`{"failure_class":"ONE_SHOT_CONSUMED","schema_version":"walksafe.negative-controller-error.v1","status":"INCOMPLETE_UNTRUSTED","success_receipt_count":0}\n`, stdout empty로 끝난다.

### 4.2 producer case table

모든 first child result는 stdout empty, success receipt 0이고, observation은 exact keys
`case,child_rc,failure_class,phase,schema_version,status,success_receipt_count`의 canonical
JSON+LF다. `phase="first"`, schema는 `walksafe.negative-observation.v2`, status는
`INCOMPLETE_UNTRUSTED`다. second는 §4.1의 exact rc/JSON이며 output·sentinel·pass-FD·
preexisting snapshot delta 0이다.

| case | reviewed producer/phase | first rc | first class | first output delta |
|---|---|---:|---|---|
| `parent-intermediate-symlink` | sandbox host preflight | 65 | `ANCESTOR_SYMLINK` | empty |
| `parent-regular` | sandbox host preflight | 65 | `PARENT_TYPE` | empty |
| `parent-mount-crossing` | sandbox host preflight | 65 | `MOUNT_CROSSING` | empty |
| `tar-dotdot` | projection tar pass 1 | 40 | `TAR_COMPONENT` | empty |
| `json-duplicate-key` | publication input validation | 14 | `JSON_CANONICAL` | empty |
| `tar-duplicate-path` | projection tar pass 1 | 40 | `TAR_DUPLICATE` | empty |
| `input-hardlink` | projection input preflight | 11 | `INPUT_NLINK` | empty |
| `input-fifo` | projection input preflight | 11 | `INPUT_TYPE` | empty |
| `input-hash-drift` | projection input preflight | 11 | `INPUT_HASH` | empty |
| `status-drift` | projection final status | 50 | `STATUS_EXACT` | permitted projection prefix only |
| `extra-output` | projection work preflight | 20 | `WORK_NOT_EMPTY` | preexisting exact tree only |
| `preexisting-final` | publication final preflight | 17 | `FINAL_NOT_EMPTY` | preexisting exact tree only |

publication crash case는 다음 exact 10개다.

```text
publish-crash-before-first
publish-crash-readme
publish-crash-publish-source
publish-crash-build-projection
publish-crash-run-readonly-sandbox
publish-crash-verify-bootstrap
publish-crash-runtime-closure
publish-crash-source-input-manifest
publish-crash-content-manifest
publish-crash-content-seal
```

first child는 모두 SIGKILL returncode `-9`, stdout/stderr empty, class `SIGNAL_SIGKILL`이다.
before-first output delta는 empty다. 나머지는 위 순서에서 `before-first`를 제외한 first N개의
exact final basename·bytes·metadata prefix만 허용한다. attempt pair는 output delta에 포함하지
않고 별도 exact committed snapshot으로 요구한다. fault를 제거한 second는 모두 §4.1의
`ONE_SHOT_CONSUMED`이고 producer entry 0이다.

### 4.3 E1 read-only recovery cases

다음 4개는 writer/attempt claim을 실행하지 않고 같은 frozen scratch를 read-only로 두 번
검증한다. 두 번의 full snapshot delta는 0이다.

| case | exact result on both reads |
|---|---|
| `e1-marker-before` | rc 82, `E1_MARKER_ABSENT`, `INCOMPLETE_UNTRUSTED`, success receipt 0 |
| `e1-marker-partial` | rc 82, `E1_MARKER_INVALID`, `INCOMPLETE_UNTRUSTED`, success receipt 0 |
| `e1-marker-wrong` | rc 82, `E1_MARKER_INVALID`, `INCOMPLETE_UNTRUSTED`, success receipt 0 |
| `e1-marker-exact-after-write` | rc 0, exact recovery PASS JSON, status `DRAFT_SOURCE_PREPARED_NOT_EXECUTED`, additional write 0 |

failure JSON은 exact keys `failure_class,schema_version,status,success_receipt_count`, schema
`walksafe.e1-recovery-error.v1`이다. PASS JSON은 exact keys `schema_version,status`, schema
`walksafe.e1-recovery.v1`이다.

### 4.4 oracle mutation cases와 snapshot key

다음 4개는 registry-owned mutation harness만 만들 수 있고 verifier/producer argv나 expected를
caller가 주입할 수 없다.

| case | exact rejection |
|---|---|
| `oracle-pass-fd-mutation` | rc 83, `PASS_FD_DRIFT` |
| `oracle-second-run-sentinel-mutation` | rc 83, `SENTINEL_DRIFT` |
| `oracle-output-root-chmod` | rc 83, `OUTPUT_ROOT_DRIFT` |
| `oracle-malformed-failure-receipt` | rc 83, `FAILURE_RECEIPT_SCHEMA` |

stderr는 exact keys `failure_class,schema_version,status,success_receipt_count`, schema
`walksafe.negative-oracle-error.v2`, status `INCOMPLETE_UNTRUSTED`, receipt 0인 canonical
JSON+LF이고 stdout은 empty다.

따라서 registry는 producer 22 + E1 recovery 4 + oracle mutation 4 = exact 30 unique ID다.
모든 case의 before/first/second snapshot은 output root 자체와 전체 tree, protected tree,
sentinel, pass-FD, preexisting target, attempt root를 포함한다. root snapshot key는
`dev,ino,type,mode,uid,gid,nlink`와 path-sorted full rows/digest이고, file snapshot key는
`dev,ino,type,mode,uid,gid,nlink,size,mtime_ns,ctime_ns,sha256`다. 허용된 first output prefix와
attempt commit 외 delta, symlink/special/hardlink/mount crossing, success receipt는 0이다.

## 5. C13 role-separated Git confinement

R009 C13을 다음으로 좁힌다.

- 공통: producer와 verifier는 `GIT_OPTIONAL_LOCKS=0`, `--no-optional-locks`, command-scope
  `core.hooksPath=/dev/null`, `core.fsmonitor=false`, `core.untrackedCache=false`, exact builtin
  allowlist, clear env, fixed timeout과 fixed FD만 사용한다. 외부 hook/fsmonitor/helper 실행은 0이다.
- producer: backup/source/control mount는 read-only이고 pre/post exact identity equality다.
  projection output은 initially empty one-shot root에서만 clone/patch/restore/manifest의 선언된
  create-only delta를 허용한다. output 내부 `.git`은 equality 대상이 아니라 full final physical
  oracle과 projection manifest에 결속한다. 다른 host/repository write는 0이다.
- verifier: 완성된 projection 전체와 `.git`, backup/source/control을 read-only mount하고 첫 Git
  호출 전과 마지막 호출 뒤 byte/metadata full snapshot equality를 요구한다. verifier output
  write는 stdout/stderr bounded receipt 외 0이다.

## 6. existing daylog 단일 Update 예외

exact path는
`/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/daylog/2026-08-02.md`다. E3 직전
preimage는 regular `0664`, uid/gid `1000/1000`, nlink 1, 2987 bytes, 56 lines, SHA-256
`20983d0f481699bee1d708b059a34be85dacccf1dca1f06489e60938f2522d4b`여야 한다.

E3은 pre/use/post same-file identity를 확인하면서 `apply_patch Update File` 한 번으로
`## R010 ...` terminal block 하나만 append한다. 기존 2,987 bytes는 postimage의 exact prefix로
byte-equal하고 기존 terminal LF 뒤에만 새 block이 온다. symlink/type/mode/owner/nlink drift,
다른 byte 수정, 두 번째 R010 block이면 fail-closed하며 Update/Delete/chmod를 더 하지 않는다.
postimage SHA/bytes/lines와 검증 결과는 그 block과 local-memory log-work에 기록한다.

## 7. source input, evidence와 source review

R010 `source-input-manifest.json` current input은 R010과 §8의 두 R010 plan review다. correction
ledger는 이 문서 §0의 두 R009 review와 §3의 exact 5 findings다. R008/R009 plan chain,
R008 exact roots/seal/post/observation/marker와 final absent는 immutable history/anchor다.
backup/runtime/apply-patch identities는 그대로 결속하고 미래 R010 seal/evidence/source review/
publication/projection output은 제외한다.

draft exact 9, evidence exact 4, 생성 순서와 marker protocol은 R009 §2/§5를 유지하되 R010
identity와 새 physical hashes를 사용한다. exact source review files는 다음이다.

- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R010-R001-independent-boundary-review-r001.md`
- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R010-R001-independent-recovery-review-r001.md`

두 source reviewer는 서로 다른 새 agent이고 같은 seal/post/observation/marker를 서로 결과 없이
정적으로 검수한다. source 실행/import/pycompile은 0이다. 두 review가 모두 terminal이 되기
전에 E3 또는 R011을 쓰지 않는다.

## 8. R010 independent plan review와 acceptance

exact review files는 다음이다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R010-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R010-independent-skeptical-review-r001.md`

서로 다른 새 agent 두 명이 같은 frozen R010 SHA/bytes/lines를 서로 결과 없이 검수한다. 각
file은 review_id/type, reviewer_agent/session, independence_attestation, target_sha256/bytes/
lines, verdict, blocking/major/minor를 정확히 한 번 둔다. PASS는 `0/0/0`일 때만 가능하다.

```text
PLAN_OK = S1_E1_AUTHORIZED
E1_OK = PLAN_OK and R009 roots absent and R008 predecessor unchanged
        and R010 draft exact9 and evidence exact4 and final absent
        and independent reconstruction PASS and candidate execution 0
        and all official/product/canonical/formal/device/Gate/release deltas 0
SOURCE_OK = E1_OK and R009 C01-C09,C11-C12 static closure
            and §4 C10 and §5 C13 static closure
            and two source reviews PASS 0/0/0
            and dynamic validation NOT_RUN and candidate unexecuted
```

어느 severity든 plan finding이면 R010 roots는 absent로 두고, 두 review barrier 뒤 R011만
허용한다. E1 실패나 source finding이면 생성된 R010 roots를 그대로 보존하고 두 source review
barrier 뒤 R011만 허용한다. SOURCE_OK여도 별도 publication/projection plan과 독립 review 전
source 실행 권한은 0이다.

다음 단일 행동은 이 R010을 freeze하고 §8의 두 독립 review를 병렬 수행하는 것이다.
