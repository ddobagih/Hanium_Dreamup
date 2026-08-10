# WalkSafe 자율 실행 로드맵 20260802 R011

## 0. 지위와 frozen predecessor

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R011
document_class = INTERNAL_EXECUTION_ROADMAP_ADD_ONLY_CORRIGENDUM
state = S0_PENDING_TWO_R011_PLAN_REVIEWS
draft_source_execution_allowed = false
network_allowed = false
live_product_or_canonical_mutation_allowed = false
official_progress_delta = 0
artifact_completion_credit_delta = 0
release_claim = NOT_ELIGIBLE
```

| input | SHA-256 | bytes | lines/result |
|---|---|---:|---:|
| R010 roadmap | `731eed15ed4074134372666c0223f2aa4a1fdcdc0b3efc06e88b79c0fee541eb` | 16233 | 291 |
| R010 structural review | `7db21a18e706b32870be35f3d41fd78003280e4ae95bdf746c8450a2a13aa4d8` | 8882 | 126, REVISION_REQUIRED 3B/2M/0m |
| R010 skeptical review | `357208a0a0162c550eeca1d89e9fde142426175a8482f6b65845f657c39530f6` | 10623 | 142, REVISION_REQUIRED 3B/2M/0m |

두 R010 review는 서로 다른 agent가 같은 frozen R010을 독립 검수했고 모두 terminal이다. root는
두 file이 모두 존재한 뒤 finding union을 재구성하고 이 R011 한 file만 작성했다. R010의
draft/final/evidence root는 모두 absent이며 앞으로도 write하지 않는다. R008 draft/evidence는
immutable rejected predecessor이고 R008 final root는 absent다.

R010은 아래 replacement를 적용한 뒤 R011의 normative base다. 충돌하면 R011이 우선한다.

## 1. 완전한 권한 상태기계

R010 §1과 failure 전이를 전부 교체한다.

```text
S0_PENDING_PLAN_REVIEWS:
  exact two R011 plan-review Add File writes만 허용

B0_PLAN_REVIEW_BARRIER:
  two reviewer tasks terminal
  and exact two files frozen against same R011 SHA/bytes/lines
  and distinct reviewer_agent/session
  and complete finding union reconstructed

F0_PLAN_REJECTED:
  B0 and any nonzero finding/identity/independence failure
  -> R011 roots remain absent; R012 one file only

S1_E1_AUTHORING:
  B0 and both PASS 0/0/0
  and contemporaneous LIVE_USER_AUTHORITY immediately before first E1 write
  and cancellation/replacement absent
  -> exactly one E1 authoring attempt

B1_E1_TERMINAL:
  E1 parent invocation is terminal and no E1 child remains
  and current R011 root inventory is captured read-only
  and final root absent and predecessor unchanged

F1_E1_INCOMPLETE:
  B1 and E1_PHYSICAL_OK is false
  -> preserve absent/partial roots; source-review write 0; R012 one file only

S2_SOURCE_REVIEW:
  B1 and E1_PHYSICAL_OK
  -> exact two independent source-review Add File writes only

B2_SOURCE_REVIEW_BARRIER:
  two source reviewer tasks terminal
  and both files bind the same plan/seal/post/observation/marker identity
  and distinct reviewer_agent/session
  and complete finding union reconstructed

F2_SOURCE_REJECTED:
  B2 and any nonzero finding/identity/independence failure
  -> preserve R011 roots; R012 one file only

S3_STATIC_PASS_NOT_EXECUTABLE:
  B2 and both PASS 0/0/0
  -> source remains unexecuted; dynamic validation remains NOT_RUN
```

review나 roadmap은 live authority를 만들지 않는다. `F1`은 존재할 수 없는 source review를
요구하지 않으며, `F0`과 `F2`는 항상 두 reviewer의 late finding까지 기다린다. S3도 별도
publication/projection plan과 두 독립 review 전에는 source execution 권한을 주지 않는다.

## 2. exact path, basename, creation order와 write set

```text
draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r011-r001
final_candidate_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r011-r001
evidence_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r011-r001
```

세 path는 plan review 전에 모두 absent여야 한다. E1 exact draft basenames와 순서는 다음이다.

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

앞의 7개 payload를 먼저 만들고, 그 physical bytes에서 8 manifest와 9 seal을 만든다. evidence
exact basenames와 순서는 다음이다.

```text
1 authorization-gate.json
2 draft-post.json
3 e1-observation.json
4 E1.COMPLETE
```

전체 순서는 authorization → payload 7 → manifest/seal → draft-post → prior-only observation →
non-self-claiming marker다. root는 `0700`, 모든 file은 regular `0600`, uid/gid `1000/1000`,
nlink 1이고 final은 absent다.

| Epoch | exact write set/method |
|---|---|
| `E0_R011_AUTHOR` | 이 R011 file 하나, repository `apply_patch Add File` |
| `E0_R011_REVIEW` | §8 plan review 두 file, 각 reviewer의 `apply_patch Add File` |
| `E1_R011_DRAFT` | S1일 때만 위 literal draft 9 + evidence 4, confined `apply_patch Add File` |
| `E2_R011_SOURCE_REVIEW` | S2일 때만 §7 source review 두 file, 각 reviewer의 `apply_patch Add File` |
| `E_FAIL_R012` | F0/F1/F2 중 하나에서 `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R012.md` 하나 |

그 외 repository/candidate/evidence Update/Delete/rename/truncate/chmod/chown/link/write는 0이다.
실패 root는 repair/delete/resume/rerun하지 않는다. control-document patch invocation은
`control_patch_count`로 따로 센다. candidate source execution/import/pycompile, product/project
command, backup command, network와 공식·제품·canonical delta는 계속 0이다.

## 3. R010 finding closure ledger

| union finding | R011 closure |
|---|---|
| B-01 E1/source liveness | §1이 E1 success/failure와 source-review success/failure를 별도 terminal transition으로 닫는다. |
| B-02 daylog self-hash | §6이 daylog를 bootstrap authority/acceptance에서 제거하고 session-end automation으로 분리한다. |
| B-03 attempt virgin/alternate-root | §4.1이 externally frozen case authority와 initially-absent one-shot attempt directory를 결속한다. |
| M-01 exact oracle/delta | §4.2~§4.4가 literal crash prefix count, independent fixture-oracle manifest와 exact mutation을 고정한다. |
| M-02 transitive exact9/4 | §2가 9/4 basename과 order를 직접 열거한다. |

R009의 다섯 finding과 R010에서 이미 닫힌 C13 역할 분리는 R010 §3~§5를 유지한다. 아래는
R010 C10의 불완전한 부분만 replacement한다.

## 4. C10 final static contract

### 4.1 external case authority와 virgin reservation

dynamic 실행은 현재 `NOT_RUN`이며 별도 plan review 전 금지다. 후속 승인 실행에서 candidate가
아닌 독립 outer harness가 각 case 전에 canonical `case-authority.json`을 add-only로 freeze하고
그 SHA를 실행 계획에 고정한다. exact top-level keys는 다음뿐이다.

```text
schema_version = walksafe.negative-case-authority.v1
run_id
global_attempt_id
case_id
source_seal_sha256
producer_basename
producer_sha256
case_root {literal_path,dev,ino,mode,uid,gid,nlink}
attempt_parent {literal_path,dev,ino,mode,uid,gid,nlink}
output_root {literal_path,dev,ino,mode,uid,gid,nlink}
fixture_oracle_manifest_sha256
controller_argv_sha256
controller_env_sha256
fixed_fd_roles_sha256
timeout_seconds
```

case, attempt-parent, output root는 distinct inode이고 authority literal path와 nofollow reopen
identity가 일치해야 한다. 같은 `run_id+global_attempt_id+case_id+source seal`에 다른 root/authority
SHA를 쓰면 `AUTHORITY_CONFLICT`다. candidate는 authority나 fixture manifest를 쓰거나 고르지
않고 pinned read-only FD로만 검증한다.

attempt parent에는 literal basename `attempt`가 **처음에 absent**다. controller의 첫 durable
행위는 pinned parent에서 `mkdirat("attempt",0700)` 후 parent fsync다. 기존 attempt basename은
empty directory라도 virgin이 아니며 child 0, rc 84 `ATTEMPT_INCOMPLETE`다. absent 상태에서
mkdir 전에 crash하면 durable effect와 child entry가 모두 0이므로 같은 frozen authority의
reservation을 시작할 수 있다. mkdir가 관찰된 뒤에는 어느 crash boundary에서도 재생하지 않는다.

새 attempt directory 안에는 R010 §4.1의 `attempt.claim`, `ATTEMPT.COMMITTED`를 O_EXCL,
write-all, file fsync, close, directory fsync 순으로 만든다. claim exact keys는 다음뿐이다.

```text
schema_version = walksafe.negative-attempt-claim.v1
authority_sha256
run_id
global_attempt_id
case_id
source_seal_sha256
producer_basename
producer_sha256
fixture_oracle_manifest_sha256
controller_argv_sha256
controller_env_sha256
fixed_fd_roles_sha256
timeout_seconds
```

marker는 `WS-WALKSAFE-NEGATIVE-ATTEMPT-V1\nclaim_sha256=<sha>\n` exact bytes다. marker와
directory fsync가 끝난 뒤에만 child를 spawn한다. existing state의 exact 결과는 다음이다.

| state | rc/class | stdout/stderr |
|---|---|---|
| exact committed pair, same authority | `81/ONE_SHOT_CONSUMED` | stdout empty; R010 §4.1 exact canonical error stderr |
| existing empty/partial/missing/wrong pair | `84/ATTEMPT_INCOMPLETE` | stdout empty; schema `walksafe.negative-controller-error.v1`, exact four error keys |
| pair/authority value conflict | `85/ATTEMPT_CONFLICT` | same exact error schema |
| path/fd/authority alias mismatch | `86/AUTHORITY_CONFLICT` | same exact error schema |

four error keys는 `failure_class,schema_version,status,success_receipt_count`; status는
`INCOMPLETE_UNTRUSTED`, receipt count 0이고 detail/extra key는 없다. failure 뒤 repair/delete/new
root fallback은 0이다. 후속 독립 plan이 새 `run_id/global_attempt_id`를 발급하는 것은 retry가
아니라 새 승인 실행이며, 같은 authority의 second-call 검증에는 사용할 수 없다.

### 4.2 exact producer cases와 stream ownership

R010의 base 12 + publication crash 10 = producer 22 ID, E1 recovery 4 ID, oracle mutation 4 ID의
exact30/disjoint arithmetic은 유지한다. `verify-bootstrap negative`는 raw child stdout/stderr를
bounded memory로만 capture하고 first/second observation을 stdout에 노출하지 않는다. expected
negative가 전부 맞을 때만 verifier 자체가 rc0로 R010 §4.2 exact observation을 stdout에 한 번
쓰고 stderr는 empty다. mismatch는 rc83과 `walksafe.negative-oracle-error.v2` exact stderr다.

invalid parent/input/JSON/tar case는 해당 preflight를 output creation보다 먼저 수행한다. 특히
`tar_pass1`은 projection directory/clone보다 먼저 끝나므로 `tar-dotdot`와
`tar-duplicate-path` first output은 empty다. exact base first rc/class는 R010 §4.2 표를 유지한다.

publication crash prefix mapping은 다음 literal이다.

| case | exact prefix count | exact present basenames |
|---|---:|---|
| `publish-crash-before-first` | 0 | none |
| `publish-crash-readme` | 1 | `README.md` |
| `publish-crash-publish-source` | 2 | 위 + `publish-source.py` |
| `publish-crash-build-projection` | 3 | 위 + `build-projection.py` |
| `publish-crash-run-readonly-sandbox` | 4 | 위 + `run-readonly-sandbox.py` |
| `publish-crash-verify-bootstrap` | 5 | 위 + `verify-bootstrap.py` |
| `publish-crash-runtime-closure` | 6 | 위 + `runtime-closure.json` |
| `publish-crash-source-input-manifest` | 7 | 위 + `source-input-manifest.json` |
| `publish-crash-content-manifest` | 8 | 위 + `content-manifest.json` |
| `publish-crash-content-seal` | 9 | 위 + `content-manifest.sha256` |

각 present file의 bytes/SHA/mode/uid/gid/nlink는 reviewed draft payload와 independently rebuilt
final manifest/seal에서 얻고 fixture-oracle manifest에도 같은 full row를 둔다. first는 SIGKILL
`-9`, raw stdout/stderr empty이고, second는 같은 authority에서 child entry 0과 rc81이다.

### 4.3 independent fixture-oracle manifest

candidate 밖의 future dynamic-plan author와 reviewer가 execution 전에
`negative-fixture-oracle-manifest.json`을 freeze한다. candidate producer/verifier/harness는 그
expected 값을 쓰거나 선택하지 않는다. exact top-level keys는 다음뿐이다.

```text
schema_version = walksafe.negative-fixture-oracle.v1
run_id
case_id
source_seal_sha256
authority_sha256
fixture_preimage_rows
fixture_preimage_digest
fault_or_mutation
expected_child {returncode,stdout_bytes,stdout_sha256,stderr_bytes,stderr_sha256,failure_class}
expected_first {output_root,protected_root,sentinel,pass_fd,preexisting,attempt_root}
expected_second {output_root,protected_root,sentinel,pass_fd,preexisting,attempt_root}
allowed_first_delta
allowed_second_delta
```

각 nested root/file snapshot은 R010 §4.4의 exact keys를 사용한다. `status-drift`의 deterministic
projection prefix와 `extra-output`/`preexisting-final`의 preexisting tree, 모든 crash prefix는
literal sorted rows와 digest로 이 manifest에 고정한다. case authority는 이 manifest SHA를
결속하며 candidate가 제공한 manifest, wildcard, count-only, caller expected는 거부한다.

### 4.4 exact independent mutation transformations

네 mutation은 candidate registry-owned harness가 아니라 future dynamic plan에 고정된 독립
outer harness만 수행한다. disposable scratch의 first observation 뒤 정확히 한 변환만 허용한다.

| case | exact from → to | expected rejection |
|---|---|---|
| `oracle-pass-fd-mutation` | pass-FD regular file offset 0 byte를 `old XOR 0x01`, size/mode/owner/nlink 불변 | `83/PASS_FD_DRIFT` |
| `oracle-second-run-sentinel-mutation` | sentinel offset 0 byte를 `old XOR 0x01`, size/mode/owner/nlink 불변 | `83/SENTINEL_DRIFT` |
| `oracle-output-root-chmod` | output root mode `0700 → 0750`, dev/ino/owner/nlink 불변 | `83/OUTPUT_ROOT_DRIFT` |
| `oracle-malformed-failure-receipt` | otherwise exact failure JSON에 sorted extra key `"unexpected":true` 1개 추가 | `83/FAILURE_RECEIPT_SCHEMA` |

fixture manifest의 allowed delta는 위 한 field/byte mutation만 포함하고 다른 row/digest 변화는 0이다.
verifier는 mutation을 원복하지 않는다. live repository/backup/candidate에는 mutation write가 0이다.

## 5. C13과 remaining source closure

R010 §5의 role-separated Git confinement을 그대로 유지한다. producer output `.git`과 projection
tree만 declared create-only delta이고, verifier는 `.git` 포함 input projection full pre/post
equality다. R009 C01~C09, C11~C12 closure도 그대로 유지하며 R010 §4는 이 문서 §4로 교체한다.

static source review는 dynamic manifest 값이 아직 없음을 PASS로 가장하지 않는다. source가
exact schema, external binding, case table, mutation and snapshot comparison을 구현했는지만 검수하고
dynamic status는 `NOT_RUN`으로 유지한다.

## 6. daylog와 local-memory 분리

R010 `E3_DAYLOG_APPEND`와 §6을 폐기한다. daylog와 local-memory는 R011 source authoring 권한,
E1/SOURCE acceptance, failure successor의 입력이나 receipt가 아니다. 따라서 daylog self-hash,
postimage claim, append crash가 bootstrap 상태기계를 막지 않는다.

repository global instruction에 따른 session-end automation은 R011의 terminal 상태를 관찰한 뒤
별도 수행한다. daylog block은 prior facts만 기록하고 자기 postimage SHA/bytes/lines나 append
호출 성공을 주장하지 않는다. 실제 postimage identity는 daylog 밖 local-memory log-work에만
기록한다. 이 automation은 product/canonical/official progress를 바꾸거나 R012/source 실행을
허가할 수 없다.

## 7. source input, evidence와 source review identity

`source-input-manifest.json` current input은 R011과 §8의 두 R011 plan review다. correction ledger는
§0의 두 R010 review와 §3 exact five union finding이다. R008~R010 chain과 R008 physical anchors는
history다. 미래 R011 seal/evidence/source review/case authority/fixture oracle/publication/projection은
input에서 제외한다.

exact source review files는 다음이다.

- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R011-R001-independent-boundary-review-r001.md`
- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R011-R001-independent-recovery-review-r001.md`

각 review는 review metadata 외에 `plan_sha256`, `draft_content_seal_file_sha256`,
`draft_post_sha256`, `e1_observation_sha256`, `e1_marker_sha256`, `verdict`, 세 severity를 정확히 한
번 둔다. 두 agent는 다르고 서로 결과를 읽지 않는다. source AST parse는 허용하지만 execution,
import, byte-compile, pycompile은 0이다. candidate execution evidence를 만들지 않는다.

## 8. R011 plan review와 acceptance

exact files는 다음이다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R011-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R011-independent-skeptical-review-r001.md`

서로 다른 새 agent 두 명이 same frozen R011 SHA/bytes/lines를 서로 결과 없이 검수한다. 각
file은 review_id/type, reviewer_agent/session, independence_attestation, target_sha256/bytes/lines,
verdict, blocking/major/minor를 정확히 한 번 둔다. PASS는 0/0/0만이다.

```text
PLAN_OK = B0 and both plan reviews PASS 0/0/0 and live authority at E1
E1_PHYSICAL_OK = PLAN_OK and exact draft9/evidence4/final-absent
                 and independent manifest/seal/post/observation/marker reconstruction PASS
                 and predecessor/source/network/product/canonical/official deltas 0
SOURCE_OK = E1_PHYSICAL_OK and all static C01-C13 closure PASS
            and B2 and both source reviews PASS 0/0/0
            and candidate unexecuted and dynamic validation NOT_RUN
```

next action은 R011을 freeze하고 위 두 independent plan review를 병렬 수행하는 것이다. 그 전
R011 root write와 candidate source execution은 0이다.
