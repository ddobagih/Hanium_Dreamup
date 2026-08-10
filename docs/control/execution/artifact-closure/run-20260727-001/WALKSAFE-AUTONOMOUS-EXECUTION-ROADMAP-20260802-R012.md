# WalkSafe 자율 실행 로드맵 20260802 R012

## 0. 지위와 frozen predecessor

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R012
document_class = INTERNAL_EXECUTION_ROADMAP_ADD_ONLY_CORRIGENDUM
state = S0_PENDING_TWO_R012_PLAN_REVIEWS
draft_source_execution_allowed = false
network_allowed = false
live_product_or_canonical_mutation_allowed = false
official_progress_delta = 0
artifact_completion_credit_delta = 0
release_claim = NOT_ELIGIBLE
```

| frozen input | SHA-256 | bytes | lines/result |
|---|---|---:|---:|
| R011 roadmap | `5ad6a09870a30bc91c1c77ff29a731c526809ee86dd23f64d8b41b522b42d6f6` | 17611 | 353 |
| R011 structural review | `1699cf8cc58c27f2883af5af909391d58373b748a2aaa37248f51bdc6e010c52` | 9002 | 125, REVISION_REQUIRED 3B/1M/0m |
| R011 skeptical review | `0ada5bf79934026853343366b34167a2f4c9e0f4c94e0a919b95722df7e83534` | 14253 | 199, REVISION_REQUIRED 3B/4M/0m |

두 review는 서로 다른 agent/session이 같은 frozen R011을 서로의 결과 없이 검수했고 모두
terminal이다. root가 두 file을 모두 확인하고 finding union을 재구성한 뒤 이 R012 한 file만
작성했다. R011의 세 root는 모두 absent이며 앞으로도 write하지 않는다. R008 draft/evidence는
immutable rejected predecessor이고 R008 final은 absent다.

R011은 아래 replacement 뒤 R012의 normative base다. 충돌하면 R012가 우선한다. R012는 R011
review union의 3 blocking + 5 distinct major finding만 닫고 새 제품·공식 범위를 만들지 않는다.

## 1. review와 E1의 총체적 상태기계

R011 §1을 전부 교체한다. `expected_plan_review_paths`와 `expected_source_review_paths`는 §8의
literal 두 경로다. availability barrier와 validation branch를 분리한다.

```text
A0_PLAN_REVIEW_AVAILABLE :=
  designated root가 시작한 exact 두 reviewer task가 모두 terminal
  and 그 두 task의 child가 0
  and expected_plan_review_paths 두 곳의 nofollow inventory를 한 번 capture

V0_PLAN_REVIEW := A0 뒤 각 expected path에 대해 total validation
  VALID iff exact 한 regular file, repository-control mode 0664, uid/gid 1000/1000,
           nlink 1, mandatory metadata 각각 정확히 1회, actual R012 SHA/bytes/lines 일치,
           assigned reviewer_agent/session 일치, 두 reviewer identity distinct,
           independence attestation true, verdict/severity parse 가능
  INVALID iff missing, extra expected-name collision, symlink/special/hardlink,
             malformed/duplicate metadata, wrong target/agent/session, non-independent,
             unreadable, 또는 그 밖의 VALID 부정

S1_E1_AUTHORING :=
  A0 and 두 review VALID and 둘 다 PASS 0/0/0
  and 현재 user의 자율 진행 지시가 취소·대체되지 않음
  -> §2 E1을 정확히 한 번만 author

F0_PLAN_REJECTED :=
  A0 and not(S1의 review-valid-and-zero 조건)
  -> R012 roots absent 유지; R013 한 file만 허용

A1_E1_AVAILABLE :=
  S1의 단일 E1 parent invocation terminal and E1 child 0
  and R012 세 root nofollow inventory capture

S2_SOURCE_REVIEW := A1 and E1_PHYSICAL_OK -> exact 두 source-review task만 시작
F1_E1_REJECTED := A1 and not(E1_PHYSICAL_OK) -> partial/absent root 보존; R013 한 file만

A2_SOURCE_REVIEW_AVAILABLE :=
  S2에서 지정한 exact 두 reviewer task가 모두 terminal and child 0
  and expected_source_review_paths 두 곳의 nofollow inventory를 한 번 capture

V2_SOURCE_REVIEW := V0와 같은 total file/metadata/assignment 검증
  plus 두 file이 same plan/seal/post/observation/marker identity에 결속

S3_STATIC_PASS_NOT_EXECUTABLE :=
  A2 and 두 source review VALID and 둘 다 PASS 0/0/0
F2_SOURCE_REJECTED :=
  A2 and not(S3의 review-valid-and-zero 조건) -> R012 root 보존; R013 한 file만
```

`A0/A2`는 file의 유효성을 요구하지 않으므로 missing/malformed/wrong/duplicate review도 반드시
F0/F2로 끝난다. reviewer task가 file 생성 전에 terminal이어도 inventory의 `absent`가 INVALID로
평가된다. 어떤 finding severity도 PASS가 아니다. review는 live authority를 만들지 않는다.
S3도 candidate execution/import/compile/publication/projection을 허용하지 않으며 dynamic은
`NOT_RUN`이다.

## 2. exact roots, basename, order와 write boundary

```text
draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r012-r001
final_candidate_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r012-r001
evidence_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r012-r001
```

세 path는 plan review 전에 모두 absent다. E1 draft exact 9는 다음 literal 순서다.

```text
1 README.md
2 publish-source.py
3 build-projection.py
4 run-readonly-sandbox.py
5 verify-bootstrap.py
6 runtime-closure.json
7 source-input-manifest.json
8 draft-content-manifest.json
9 draft-content-manifest.sha256
```

앞 7개 payload 뒤 그 bytes에서 8/9를 만든다. evidence exact 4는 다음 literal 순서다.

```text
1 authorization-gate.json
2 draft-post.json
3 e1-observation.json
4 E1.COMPLETE
```

전체 생성 순서는 authorization → payload 7 → manifest/seal → draft-post → prior-only observation
→ non-self-claiming marker다. root는 `0700`; 모든 file은 regular `0600`, uid/gid `1000/1000`,
nlink 1이다. final은 absent다.

| epoch | exact write set/method |
|---|---|
| `E0_R012_AUTHOR` | 이 R012 file 하나, repository `apply_patch Add File` |
| `E0_R012_REVIEW` | §8 plan-review 두 file, 각 assigned reviewer의 `apply_patch Add File` |
| `E1_R012_DRAFT` | S1일 때만 위 draft 9 + evidence 4, confined `apply_patch Add File` |
| `E2_R012_SOURCE_REVIEW` | S2일 때만 §8 source-review 두 file, 각 assigned reviewer의 `apply_patch Add File` |
| `E_FAIL_R013` | F0/F1/F2 뒤 `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R013.md` 하나 |
| `E_AUTOMATION_DAYLOG` | §7 terminal session-end non-authorizing append 예외 하나 |

bootstrap/successor write boundary에서 열거되지 않은 candidate/evidence/repository
Update/Delete/rename/truncate/chmod/chown/link/write는 0이다. 실패 root는 repair/delete/resume/rerun하지
않는다. `E_AUTOMATION_DAYLOG`는 bootstrap terminal 판정 뒤 global automation으로만 열리고 위
boundary의 유일한 좁은 예외다. acceptance, successor 또는 source-execution authority의 input이
아니다.

명시적 계수는 다음과 같다.

```text
candidate_source_execution_count = 0
candidate_import_count = 0
candidate_bytecompile_or_pycompile_count = 0
product_project_backup_command_count = 0
network_command_count = 0
live_protected_root_unlisted_delta = 0
official_product_canonical_formal_device_gate_release_delta = 0
```

authorized R012 plan/review, draft/evidence, R013와 §7 daylog write만
`live_protected_root_unlisted_delta`에서 제외한다. backup root, R008 frozen roots, R009~R011 absent
roots와 그 밖의 repository bytes는 보호 대상이다.

## 3. R011 finding closure ledger

| union finding | R012 closure |
|---|---|
| barrier failure unreachable | §1이 task terminal/inventory availability와 file validity를 분리하고 INVALID의 여집합을 F0/F2로 보낸다. |
| authority/oracle mutual SHA | §4가 registry → selector → oracle → authority → claim 단방향 DAG를 고정한다. |
| future inode/time unknowable | §5가 pre-frozen logical/known-preexisting 값과 runtime relational physical observation을 분리한다. |
| alternate authority/root | §4의 single run selector가 tuple당 authority id와 root를 하나만 선택하고 candidate가 selector FD도 검증한다. |
| source-dependent stream oracle | §6이 source보다 먼저 이 문서에 normative case/stream generator를 동결한다. |
| under-specified mutations | §6.4가 exact case/carrier/preimage/transformation을 동결한다. |
| daylog write conflict | §2/§7이 terminal 뒤 한 번뿐인 non-authorizing exact Update 예외를 둔다. |
| ambiguous source delta | §2가 command/execution 계수와 protected unlisted delta를 분리한다. |

R011의 literal exact 9/4, corrected FD/Git/CJSON/metadata/read-CAS/projection/schema/evidence/error-path
contracts와 static/dynamic 경계는 위 replacement와 충돌하지 않는 범위에서 유지한다.

## 4. 비순환 global authority DAG

future dynamic plan은 현재 `NOT_RUN`이며 별도 roadmap과 두 독립 review가 PASS하기 전 아래 file을
만들거나 candidate를 실행할 수 없다. 그 승인 뒤에도 candidate와 다른 outer harness만 다음
순서로 add-only freeze한다.

```text
R012 embedded normative registry
  -> 1 run-selector.json
  -> 2 negative-fixture-oracle-manifest.json
  -> 3 case-authority.json
  -> 4 attempt/attempt.claim and attempt/ATTEMPT.COMMITTED
```

### 4.1 authority id와 run selector

각 case의 `authority_preimage`는 UTF-8, ensure_ascii=false, sorted keys, separators `,`/`:`, LF 한
개인 canonical JSON이며 exact keys는 다음뿐이다.

```text
schema_version = walksafe.negative-authority-preimage.v1
run_id
global_attempt_id
case_id
source_seal_sha256
producer_basename
producer_sha256
case_root {literal_path,dev,ino,type,mode,uid,gid,nlink}
attempt_parent {literal_path,dev,ino,type,mode,uid,gid,nlink}
output_root {literal_path,dev,ino,type,mode,uid,gid,nlink}
controller_argv_sha256
controller_env_sha256
fixed_fd_roles_sha256
timeout_seconds
normative_case_spec_sha256
```

`authority_id = sha256(authority_preimage canonical bytes)`다. 이 단계의 preimage는 계산용 bytes일
뿐 file/authority가 아니고 oracle SHA를 포함하지 않는다.

run selector exact top-level keys는 `schema_version,dynamic_plan_sha256,run_id,source_seal_sha256,
entries`다. schema는 `walksafe.negative-run-selector.v1`; entries는
`global_attempt_id,case_id` byte-order로 sorted되며 각 entry exact keys는
`authority_id,authority_literal_path,authority_parent`(known full physical row), 위 tuple,
`case_root,attempt_parent,output_root` known full physical rows, `normative_case_spec_sha256`다.
tuple은 selector 안 정확히 한 번만 나타나고 세 root inode는 서로 다르다. selector file을 먼저
freeze하고 SHA와 pinned read-only FD를 얻는다. 아직 absent인 authority file의 inode/time은 selector에
넣지 않고 exact parent identity + literal basename만 넣는다.

### 4.2 fixture oracle와 authority

fixture oracle은 selector 뒤 freeze하며 exact top-level keys는 다음뿐이다.

```text
schema_version = walksafe.negative-fixture-oracle.v2
run_selector_sha256
authority_id
run_id
global_attempt_id
case_id
source_seal_sha256
normative_case_spec_sha256
known_preexisting_physical_rows
future_logical_rows
expected_role_results
allowed_first_delta
allowed_second_delta
relational_physical_predicates
```

여기에는 `authority_sha256`가 없으며 expected 값은 §5/§6의 registry generator에서만 materialize한다.
candidate source/bytes/output/actual observation은 derivation input이 아니다.

case authority는 oracle 뒤 exact authority path에 O_EXCL create하며 exact keys는 다음뿐이다.

```text
schema_version = walksafe.negative-case-authority.v2
authority_id
authority_preimage
run_selector_sha256
fixture_oracle_manifest_sha256
```

controller는 selector FD, oracle FD, authority FD를 같은 bounded read-CAS로 검증한다. authority의
preimage를 재직렬화한 SHA가 authority_id와 같고 selector의 유일 entry/root/path/spec와 byte-equal,
oracle의 selector/id/tuple/spec와 byte-equal해야 한다. 다른 mapping, duplicate tuple, 다른 selector,
alternate root/path는 child 0, rc86 `AUTHORITY_CONFLICT`다.

### 4.3 durable attempt

attempt basename은 selector가 고정한 attempt parent에서 처음 absent다. controller의 첫 durable
행위는 pinned parent `mkdirat("attempt",0700)`와 parent fsync다. mkdir 전 crash는 durable delta와
child가 0이라 같은 frozen authority로 다시 reservation할 수 있다. directory가 관찰된 뒤에는
empty라도 재생하지 않는다.

attempt 안 `attempt.claim`은 R011 exact keys를 유지하되 `authority_sha256`는 위 최종 case-authority
file SHA다. `ATTEMPT.COMMITTED` exact bytes는
`WS-WALKSAFE-NEGATIVE-ATTEMPT-V1\nclaim_sha256=<sha>\n`이다. 두 regular `0600`, uid/gid
`1000/1000`, nlink 1 file을 O_EXCL write-all/fsync/close하고 directory fsync한 뒤에만 child를
spawn한다. existing exact pair는 rc81 `ONE_SHOT_CONSUMED`; empty/partial/wrong은 rc84
`ATTEMPT_INCOMPLETE`; value conflict는 rc85 `ATTEMPT_CONFLICT`; selector/authority/root alias는
rc86 `AUTHORITY_CONFLICT`다. 모두 stdout empty이고 §6 `ERR` bytes를 stderr로 쓴다. repair/delete/
alternate selector/root/new authority fallback은 0이다.

## 5. logical oracle과 runtime physical observation 분리

oracle freeze 전에 존재하는 selector, root, fixture, sentinel, pass-FD, preexisting target은 exact
`literal_path,dev,ino,type,mode,uid,gid,nlink,size,mtime_ns,ctime_ns,sha256` 중 해당 type에 유효한
full physical row로 고정한다. future output은 실행 전에 결정 가능한 다음 logical row만 고정한다.

```text
relative_path,type,mode,uid,gid,nlink,size,sha256,before_state,first_state,second_state
```

future file의 dev/ino/mtime_ns/ctime_ns exact 값은 oracle에 넣지 않는다. runtime observation은
그 네 값을 포함한 full physical row를 기록하고 다음 pre-frozen 관계를 검증한다.

- 모든 present relative path는 pinned output root 아래 nofollow fd walk로 도달하고 path escape,
  symlink, special, hardlink, mount crossing이 0이다.
- 새 regular file은 같은 output filesystem의 새 inode, logical mode/owner/nlink/size/SHA와 일치한다.
- mutation이 없는 row의 first→second dev/ino/type/mode/uid/gid/nlink/size/mtime/ctime/SHA는 같다.
- §6.4 byte mutation은 SHA/offset-0 byte와 mtime/ctime 변화만, chmod는 mode와 ctime 변화만,
  receipt mutation은 지정 carrier bytes/SHA/size와 mtime/ctime 변화만 허용한다.
- preexisting protected/root row는 해당 case가 명시한 단일 mutation 외 before→first→second full
  physical equality다.

따라서 future inode/time을 예측하거나 actual을 expected로 복사하지 않는다. runtime observation은
expected를 선택하지 못하고 oracle logical row와 관계 predicate의 독립 재검산 결과만 기록한다.

## 6. source-independent exact 30 registry와 byte oracle

이 절은 R012와 함께 candidate source authoring보다 먼저 freeze되는 normative registry다. future
manifest generator는 이 절만 입력으로 쓰고 candidate source/AST/output/stream을 읽지 않는다.

`CJSON(x)`는 UTF-8, ensure_ascii=false, sorted keys, separators `,`/`:`, terminal LF 한 개다.

```text
ERR(schema,class) = CJSON({
  "failure_class": class,
  "schema_version": schema,
  "status": "INCOMPLETE_UNTRUSTED",
  "success_receipt_count": 0
})
EMPTY = zero bytes
```

### 6.1 producer base 12

producer child와 first controller는 같은 `(rc,stdout,stderr)`를 낸다. stdout은 모두 EMPTY,
stderr는 `ERR("walksafe.negative-child-error.v1",class)`다. preflight는 output create보다 먼저
끝난다. `status-drift`도 frozen raw-status CAS를 clone 전에 검사하므로 create delta가 empty다.
extra/preexisting 두 case는 named preexisting tree의 full physical equality만 허용한다.

| case | rc | class | allowed create delta |
|---|---:|---|---|
| `parent-intermediate-symlink` | 65 | `ANCESTOR_SYMLINK` | empty |
| `parent-regular` | 65 | `PARENT_TYPE` | empty |
| `parent-mount-crossing` | 65 | `MOUNT_CROSSING` | empty |
| `tar-dotdot` | 40 | `TAR_COMPONENT` | empty |
| `json-duplicate-key` | 14 | `JSON_CANONICAL` | empty |
| `tar-duplicate-path` | 40 | `TAR_DUPLICATE` | empty |
| `input-hardlink` | 11 | `INPUT_NLINK` | empty |
| `input-fifo` | 11 | `INPUT_TYPE` | empty |
| `input-hash-drift` | 11 | `INPUT_HASH` | empty |
| `status-drift` | 50 | `STATUS_EXACT` | empty |
| `extra-output` | 20 | `WORK_NOT_EMPTY` | preexisting tree unchanged only |
| `preexisting-final` | 17 | `FINAL_NOT_EMPTY` | preexisting tree unchanged only |

### 6.2 publication crash 10

각 first child/controller는 SIGKILL returncode `-9`, class `SIGNAL_SIGKILL`, stdout/stderr EMPTY다.
allowed logical present basename은 다음과 같이 전부 직접 열거한다.

| case | exact present basenames after first |
|---|---|
| `publish-crash-before-first` | none |
| `publish-crash-readme` | `README.md` |
| `publish-crash-publish-source` | `README.md`, `publish-source.py` |
| `publish-crash-build-projection` | `README.md`, `publish-source.py`, `build-projection.py` |
| `publish-crash-run-readonly-sandbox` | `README.md`, `publish-source.py`, `build-projection.py`, `run-readonly-sandbox.py` |
| `publish-crash-verify-bootstrap` | `README.md`, `publish-source.py`, `build-projection.py`, `run-readonly-sandbox.py`, `verify-bootstrap.py` |
| `publish-crash-runtime-closure` | `README.md`, `publish-source.py`, `build-projection.py`, `run-readonly-sandbox.py`, `verify-bootstrap.py`, `runtime-closure.json` |
| `publish-crash-source-input-manifest` | `README.md`, `publish-source.py`, `build-projection.py`, `run-readonly-sandbox.py`, `verify-bootstrap.py`, `runtime-closure.json`, `source-input-manifest.json` |
| `publish-crash-content-manifest` | `README.md`, `publish-source.py`, `build-projection.py`, `run-readonly-sandbox.py`, `verify-bootstrap.py`, `runtime-closure.json`, `source-input-manifest.json`, `content-manifest.json` |
| `publish-crash-content-seal` | `README.md`, `publish-source.py`, `build-projection.py`, `run-readonly-sandbox.py`, `verify-bootstrap.py`, `runtime-closure.json`, `source-input-manifest.json`, `content-manifest.json`, `content-manifest.sha256` |

각 logical row bytes/SHA/size는 reviewed R012 draft payload 7과 그 bytes에서 candidate 없이 독립
재생성한 final manifest/seal로 dynamic execution 전에 고정한다. mode `0600`, uid/gid
`1000/1000`, nlink 1이다. attempt pair는 output delta가 아니다. 같은 authority의 second call은
모든 producer case에서 child 0, rc81, stdout EMPTY, stderr
`ERR("walksafe.negative-controller-error.v1","ONE_SHOT_CONSUMED")`이고 first output은 불변이다.

### 6.3 E1 recovery 4와 verifier streams

| case | rc | stdout | stderr |
|---|---:|---|---|
| `e1-marker-before` | 82 | EMPTY | `ERR("walksafe.e1-recovery-error.v1","E1_MARKER_ABSENT")` |
| `e1-marker-partial` | 82 | EMPTY | `ERR("walksafe.e1-recovery-error.v1","E1_MARKER_INVALID")` |
| `e1-marker-wrong` | 82 | EMPTY | `ERR("walksafe.e1-recovery-error.v1","E1_MARKER_INVALID")` |
| `e1-marker-exact-after-write` | 0 | `CJSON({"schema_version":"walksafe.e1-recovery.v1","status":"DRAFT_SOURCE_PREPARED_NOT_EXECUTED"})` | EMPTY |

producer case의 first/second raw result가 모두 oracle과 맞으면 verifier는 rc0, stderr EMPTY, stdout
`CJSON({"case":case_id,"first_failure_class":first_class,"first_returncode":first_rc,
"schema_version":"walksafe.negative-verification.v3","second_failure_class":"ONE_SHOT_CONSUMED",
"second_returncode":81,"status":"NEGATIVE_CONFIRMED","success_receipt_count":0})`다. 일반 mismatch는
rc83, stdout EMPTY, stderr `ERR("walksafe.negative-oracle-error.v2","ORACLE_MISMATCH")`다.

### 6.4 oracle mutation 4

모든 mutation은 first observation 뒤 outer harness가 정확히 한 번 수행하고 원복하지 않는다.

| case | exact source/carrier and preimage → postimage | verifier result |
|---|---|---|
| `oracle-pass-fd-mutation` | source `tar-dotdot`; `case_root/pass-fd.bin`, fixed role `pass_fd`, regular 0600 uid/gid 1000/1000 nlink1; exact preimage `WS-PASS-FD-V1\n`, size14, SHA `53974f126c1d8acfd5e7c0bf7b5a4077b7d92d860595e551a344d91b9090f1f4`, offset0 `0x57` → `0x56`, post SHA `9fd42586e5bf6e291df1c99658cb335c0915b8f7d25dc9ac13b0f6c6aac0a1b3` | rc83, `PASS_FD_DRIFT` |
| `oracle-second-run-sentinel-mutation` | source `tar-dotdot`; `case_root/sentinel.bin`, fixed role `sentinel`, regular 0600 uid/gid 1000/1000 nlink1; exact preimage `WS-SENTINEL-V1\n`, size15, SHA `0dc973be8981d5dc8b49e7836ccd95751b305eb42d4106706efd3f4da6ac4acf`, offset0 `0x57` → `0x56`, post SHA `ebaed44ec7948abf65da4363e7ab49d6cf3aa249f4d3b3b568d5fb6989cae57f` | rc83, `SENTINEL_DRIFT` |
| `oracle-output-root-chmod` | source `publish-crash-readme`; exact selected output root mode `0700 → 0750`, dev/ino/uid/gid/nlink unchanged | rc83, `OUTPUT_ROOT_DRIFT` |
| `oracle-malformed-failure-receipt` | source `tar-dotdot`; carrier `case_root/captured-first-stderr.bin`, fixed role `first_stderr_receipt`; exact preimage is `ERR(walksafe.negative-child-error.v1,TAR_COMPONENT)`, size144, SHA `4e0639edaecbf937e1110349adc4aac5ae341b36acb7098ee2f1e3383ddbee68`; add sorted key `"unexpected":true`, exact post size162, SHA `b3ab163f0ebb584813fc00af908d1189daa04d4b15a43eeb12e4285fbfed0396` | rc83, `FAILURE_RECEIPT_SCHEMA` |

각 mutation verifier stdout은 EMPTY, stderr는
`ERR("walksafe.negative-oracle-error.v2",listed_class)`다. 이 표의 literal bytes와 SHA가 다르면
manifest generation 자체가 실패한다. registry arithmetic은 producer 12 + crash 10 + recovery 4 +
mutation 4 = exact 30 unique/disjoint ID다.

## 7. non-authorizing daylog/local-memory automation

exact daylog path는
`/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/daylog/2026-08-02.md`다.
`E_AUTOMATION_DAYLOG` 직전 preimage는 regular `0664`, uid/gid `1000/1000`, nlink 1,
2987 bytes, 56 lines, SHA-256
`20983d0f481699bee1d708b059a34be85dacccf1dca1f06489e60938f2522d4b`여야 한다.

session의 roadmap/source 작업과 독립 검수가 모두 terminal인 뒤에만 `apply_patch Update File` 한
번으로 `## 2026-08-02 자율 재개 ...` block 하나를 terminal LF 뒤 append한다. 기존 2,987 bytes는
exact prefix로 보존한다. block은 append 이전에 확정된 prior facts, 변경 file과 검증만 기록하고
자기 postimage SHA/bytes/lines, append rc 또는 미래 상태를 주장하지 않는다. preimage나 metadata가
다르면 fail-closed하고 다른 write/chmod/delete/retry는 0이다. 실제 postimage identity는 daylog가
아닌 local-memory log-work에만 기록한다. memory DB write 전 backup과 writer 상태를 확인한다.

이 automation의 성공/실패는 PLAN_OK, E1_PHYSICAL_OK, SOURCE_OK, R013 또는 source execution의
입력이 아니며 공식·제품·canonical 진행도를 바꾸지 않는다.

## 8. exact review identities와 acceptance

plan review exact files는 다음이다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R012-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R012-independent-skeptical-review-r001.md`

source review exact files는 다음이다.

- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R012-R001-independent-boundary-review-r001.md`
- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R012-R001-independent-recovery-review-r001.md`

두 단계 모두 서로 다른 새 agent 두 명을 지정한다. 같은 단계 reviewer끼리는 결과/file을 읽거나
통신하지 않는다. 각 review file은 `review_id,review_type,reviewer_agent,reviewer_session,
independence_attestation,target_sha256,target_bytes,target_lines,verdict,blocking,major,minor`를 정확히
한 번 포함한다. source review는 추가로 `plan_sha256,draft_content_seal_file_sha256,
draft_post_sha256,e1_observation_sha256,e1_marker_sha256`를 정확히 한 번 포함한다. candidate AST parse는
source review에서 허용하지만 execution/import/bytecompile/pycompile은 0이다.

```text
PLAN_OK = A0 and V0 both VALID/distinct and both PASS 0/0/0
          and current user authority not cancelled/replaced at first E1 write

E1_PHYSICAL_OK = PLAN_OK
  and R012 draft exact9/evidence exact4/final absent
  and independent manifest/seal/post/observation/marker reconstruction PASS
  and R008 frozen unchanged and R009/R010/R011 roots absent
  and §2 exact zero-count/delta predicates all true

SOURCE_OK = E1_PHYSICAL_OK
  and inherited static C01-C09,C11-C13 closure PASS
  and §4 authority DAG, §5 oracle split, §6 exact30 closure PASS
  and A2 and V2 both VALID/distinct and both PASS 0/0/0
  and candidate unexecuted and dynamic validation NOT_RUN
```

다음 단일 행동은 R012를 freeze한 뒤 위 두 plan review를 결과 공유 없이 병렬 수행하는 것이다.
그 barrier 전 R012 root write와 candidate execution은 0이다.
