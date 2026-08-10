# r022 제어계약 전환 후보 R002

- 작성일: `2026-07-30`
- 상태: `DESIGN_ONLY_NOT_EFFECTIVE_NOT_APPROVED_NOT_APPLIED`
- 현재 활성 제어: v2.4 / checkpoint sequence 39 / Gap·Backlog r021
- 보존 대상: exact68 `r022-candidate-r002`
- 권고 후속 구현: 격리된 v2.5 R002 control candidate

이 문서는 설계만 추가한다. v2.4 정본, checkpoint, r021 Gap·Backlog, Goal,
제품 코드, 활성 discovery와 실행 문서를 변경하지 않는다. 이 문서의 존재나 검토
PASS는 v2.5 활성화, r022 적용, FP-008 materialization 또는 제품 구현 권한이 아니다.

## 1. R001과의 관계

R002는 `R022-CONTROL-MIGRATION-CANDIDATE-R001.md` 전체를 폐기하지 않는다.
다음 R001 판정은 그대로 유지한다.

- exact68 assessment content는 changed31/carry37 successor scope에 맞는다.
- full Gap/Backlog pair에는 현 v2.4가 원자 적용할 수 없는 Backlog 운영 delta와
  role-global normalization이 있으므로 versioned successor control이 필요하다.
- v2.4 checker, static manifest, history와 sequence 39 checkpoint를 제자리
  수정해 우회하지 않는다.
- r022 적용은 Goal status, topology, 제품, formal/device/release credit을
  올리지 않는다.
- 제어 활성화+r022 적용 승인과 FP-008 제품 작업 승인은 서로 다르다.

R002는 아래 미해결 제어 구조에 한해서만 R001을 대체한다.

1. read-only validator와 authorized application writer의 책임 분리
2. 후보별 exact 사용자 응답 계약
3. resolved manifest의 최종 멤버와 validation input closure
4. product inventory와 저장소 상태 CAS
5. active discovery, runbook, README, test registry의 격리 staging
6. v2.5 quick/full 19-check와 repository-state CLI
7. ACTIVE 검사기의 후보 디렉터리 독립성
8. 단일 lock apply/recovery/postcheck/post-commit receipt
9. hash-cycle 없는 binding DAG와 crash 상태

충돌 시 이 아홉 항목에서만 R002가 우선한다. R001 독립검수는 R001의 설계 근거로
남지만 R002나 R002 기반 구현을 검수·승인하지 않는다. R002 기반 구현은 별도 생성,
결정성 검사와 독립검수 `BLOCKING/MAJOR/MINOR = 0/0/0`이 필요하다.

## 2. 현재 불변식과 exact68 보존

### 2.1 현재 활성 상태

- branch:
  `codex/walksafe-rc2-hardening-20260715`
- HEAD:
  `a3ad7eead6b5d834d3e0675422475a9aad351e3d`
- v2.4 static manifest:
  `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07`,
  `39,534` bytes
- active checkpoint:
  `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c`,
  `1,329,415` bytes
- history tail: sequence `39`, event
  `WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-MUTABLE-CANONICAL-REFRESH-20260729-001`,
  event SHA-256
  `c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a`
- canonical r021 Gap:
  `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a`,
  `488,160` bytes
- canonical r021 Backlog:
  `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0`,
  `59,266` bytes
- artifact: complete `126/257`, open `131`
- formal: `0/279`; actual-device: `0`; release gate: `0/5`
- release: `NOT_ELIGIBLE`
- r021 assessment:
  `BLOCKED 5 / CONFLICTING 16 / EVIDENCE_MISSING 4 / MISSING 11 /
  PARTIAL 32 / IMPLEMENTED 0`
- Goal topology/status는 불변이고 ready frontier는 `EPIC-03`, `EPIC-12`다.
  canonical `current_work`는 여전히 FP-047 aggregate pointer이며 FP-008 Goal/leaf는
  아직 materialize하지 않았다.
- r022 canonical 두 경로, v2.5 active static/history/core/wrapper와 active discovery는
  존재하지 않는다.

현재 working tree에 비효력 준비 파일이나 registry의 candidate-test 분류가 있어도
활성 package는 v2.4다. 이를 과거 sequence 39 실패, r022 적용 또는 v2.5 discovery
활성화로 해석하지 않는다.

### 2.2 exact68 R002 고정 입력

다음 물리 입력은 수정·재생성하지 않고 SHA-256/bytes로 결속한다.

| 역할 | 경로 | SHA-256 | bytes |
|---|---|---:|---:|
| exact68 ledger | `r022-candidate-r002/exact68-reassessment-ledger.json` | `82ecdfbfc9a35d39f1f8648971df62c18faf861745ccc9a28f5b24d8d354493f` | 189,900 |
| r022 Gap | `r022-candidate-r002/implementation-gap-r022.candidate.json` | `3dee2cccad7fb264077dc8858ac3940821af6b4cda0e7664c3e8809b69dc8595` | 535,938 |
| r022 Backlog | `r022-candidate-r002/implementation-backlog-r022.candidate.json` | `8e2c9cc565ffdd1039f7e03fbfefed348e568d8b62a7750ff06f989a0b20f098` | 58,007 |
| pair manifest | `r022-candidate-r002/gap-backlog-pair-manifest.json` | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| independent review | `r022-candidate-r002/INDEPENDENT-REVIEW-R001.md` | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| approval runbook | `r022-candidate-r002/ACTIVATION-APPROVAL-RUNBOOK-R002.md` | `71e322de393027d3cefaf20c2e7b20e64b94d0106b044f6f1cc4a26f0e5b8bd7` | 3,868 |

모든 경로의 공통 prefix는
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/`이다. pair fingerprint는
`09fcf9cba249a777946fc90a9ee903f2e70143a431e68d0fda2564a7ea8ba67f`다.

ledger는 exact68, changed31/carry37, status change8을 유지한다. 최종 상태는
`BLOCKED 5 / CONFLICTING 14 / EVIDENCE_MISSING 4 / MISSING 6 / PARTIAL 39 /
IMPLEMENTED 0`이다. canonical 승격은 이 bytes의 복사만 허용하며 assessment나
Backlog action을 적용 시점에 다시 계산하지 않는다.

## 3. 책임 분리

### 3.1 read-only validator

공용 validator core와 continuation/Goal wrapper는 다음만 수행한다.

- strict JSON·UTF-8·경로·regular-file·SHA-256/bytes 검증
- candidate, projected precommit, post-check와 steady ACTIVE 의미 검증
- exact command contract와 repository-state JSON을 stdout으로 산출
- exit `0/1` 판정

검사 mode는 겹치지 않는다. `CANDIDATE`는 비효력 격리 bundle,
`PRECOMMIT`은 승인된 projected bytes, `POSTCHECK_PENDING`은 target checkpoint가
물리 commit됐지만 post-commit receipt가 아직 없는 상태, `ACTIVE`는 durable
post-commit receipt까지 있는 steady state만 허용한다.

validator는 파일 생성·교체·삭제, lock 획득, receipt 발행, authorization 응답
생성, event append 또는 복구를 하지 않는다. validator가 writer를 import하거나
호출하는 것도 금지한다.

### 3.2 authorized application writer

별도 writer
`scripts/apply_walksafe_v2_5_r022_authorized.py`만 최종 파일을 쓸 수 있다.
writer는 임의 path, 임의 event payload, 임의 승인 문자열을 받지 않는다. 봉인된
candidate-output manifest, authorization request/receipt, fresh quick-gate
receipt와 resolved-output manifest에 선언된 exact 경로만 처리한다.

writer는 `--check`에서 쓰지 않고 projected 결과만 검증한다. 실제
`--apply-or-recover`는 승인 receipt가 있을 때만 고정 lock을 획득하며, 동일
transaction ID의 적용·복구·post-check·post-commit receipt 내구화가 끝날 때까지
그 한 lock FD를 유지한다. ACTIVE validator는 writer에 의존하지 않는다.

## 4. 격리 후보와 최종 멤버 집합

새 구현 후보는 add-only
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/`
아래에 완결한다. active discovery, runbook, `scripts/README.md`와 test-layer
registry의 v2.5 활성 버전도 이 디렉터리 안의 후보 bytes로만 먼저 만든다.
사용자 승인 전 기존 활성 경로에 복사·교체하거나 active route에 등록하지 않는다.

resolved-output manifest의 `final_members`는 다음 정확히 17개다.

| # | candidate/source | intended final | promotion mode |
|---:|---|---|---|
| 1 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/implementation-gap-r022.candidate.json` | `docs/control/audits/walksafe-implementation-gap-analysis-20260726-r022.json` | `BYTE_EXACT_COPY_NOREPLACE` |
| 2 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/implementation-backlog-r022.candidate.json` | `docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r022.json` | `BYTE_EXACT_COPY_NOREPLACE` |
| 3 | `docs/control/walksafe-project-continuation-checkpoint.json` at pinned seq39 | `docs/control/goals/walksafe-completion-graph-v2-5/superseded-v2.4.0-active-checkpoint.json` | `BYTE_EXACT_COPY_NOREPLACE` |
| 4 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/static-plan-manifest-v2.5.0.candidate.json` | `docs/control/goals/walksafe-completion-graph-v2-5/static-plan-manifest-v2.5.0.json` | `BYTE_EXACT_COPY_NOREPLACE` |
| 5 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/v2.5-application-transaction-plan.candidate.json` | `docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-001/application-transaction-plan.json` | `BYTE_EXACT_COPY_NOREPLACE` |
| 6 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/transition-history-v2.5.candidate.json` | `docs/control/goals/walksafe-completion-graph-v2-5/transition-history-seq1-prefix-v2.5.json` | `BYTE_EXACT_COPY_NOREPLACE` |
| 7 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/v2.5-control-package-manifest.candidate.json` | `docs/control/goals/walksafe-completion-graph-v2-5/control-package-manifest-v2.5.json` | `BYTE_EXACT_COPY_NOREPLACE` |
| 8 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/staged/scripts/walksafe_v2_5_validation.py` | `scripts/walksafe_v2_5_validation.py` | `BYTE_EXACT_COPY_NOREPLACE` |
| 9 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/staged/scripts/check_walksafe_project_continuation_v2_5.py` | `scripts/check_walksafe_project_continuation_v2_5.py` | `BYTE_EXACT_COPY_NOREPLACE` |
| 10 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/staged/scripts/check_walksafe_goal_graph_v2_5.py` | `scripts/check_walksafe_goal_graph_v2_5.py` | `BYTE_EXACT_COPY_NOREPLACE` |
| 11 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/staged/scripts/apply_walksafe_v2_5_r022_authorized.py` | `scripts/apply_walksafe_v2_5_r022_authorized.py` | `BYTE_EXACT_COPY_NOREPLACE` |
| 12 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/active-control-discovery.v2.5.candidate.json` | `docs/control/walksafe-active-control-discovery.json` | `BYTE_EXACT_COPY_NOREPLACE` |
| 13 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/staged/docs/control/walksafe-project-resumption-runbook.md` | `docs/control/walksafe-project-resumption-runbook.md` | `BYTE_EXACT_CAS_REPLACE` |
| 14 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/staged/scripts/README.md` | `scripts/README.md` | `BYTE_EXACT_CAS_REPLACE` |
| 15 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002/staged/scripts/run_walksafe_test_layers_20260711.sh` | `scripts/run_walksafe_test_layers_20260711.sh` | `BYTE_EXACT_CAS_REPLACE` |
| 16 | R002 candidate seq1 history + exact declared dynamic inputs | `docs/control/goals/walksafe-completion-graph-v2-5/transition-history-v2.5.json` | `SEALED_TRANSFORM_NOREPLACE` |
| 17 | pinned seq39 checkpoint + exact seq1~3 projection inputs | `docs/control/walksafe-project-continuation-checkpoint.json` | `SEALED_TRANSFORM_CAS_REPLACE_COMMIT_POINT` |

candidate-output manifest에는 이 17개 candidate/source path, final path, mode,
source SHA-256/bytes와 expected final SHA-256/bytes를 전부 기록한다.

다음은 `final_members`가 아니다.

- seq1-only candidate checkpoint와 candidate-output manifest 자체
- R002 ledger/pair manifest/review와 R002 설계·독립검수
- core review, authorization request/receipt와 fresh quick-gate receipt
- resolved-output manifest 자체
- temp, lock, raw post-check output, failure/incident와 post-commit receipt

이들은 누락된 hidden input이 아니라 다음 절의 `validation_inputs` 또는
`durable_records`에 역할과 물리 SHA-256/bytes를 따로 기록한다.

## 5. resolved manifest와 입력 폐쇄

승인 receipt와 fresh quick gate가 모두 생긴 뒤, 첫 최종 write 전에
`resolved-output-manifest.json`을 add-only로 내구화한다. exact schema는 서로
겹치지 않는 세 집합을 가진다.

1. `final_members`: 위 17개 최종 path/hash/bytes/mode
2. `validation_inputs`: 출력 도출 또는 승인·CAS 판정에 읽은 모든 물리 입력
3. `durable_records`: resolved manifest self, post-check output, incident/failure,
   post-commit receipt의 고정 경로와 생성 조건

`validation_inputs`에는 최소 다음을 하나도 생략하지 않는다.

- v2.4 static/checkpoint/tail과 r021 pair
- R002 ledger, r022 pair, pair manifest, review
- R002 설계와 별도 독립검수
- candidate builder, read-only core, 두 wrapper, writer와 regression test
- candidate bundle의 모든 파일
- active discovery/runbook/README/test-registry before bytes와 staged after bytes
- core independent review
- authorization request, 사용자의 raw exact response와 authorization receipt
- fresh quick-gate raw 결과와 receipt
- source repository identity와 product inventory CAS
- transform spec, canonicalization, event timestamp/ID와 허용 checkpoint
  JSON Pointer before/after

입력은 path/SHA-256/bytes/role로 정렬하고 중복을 거부한다. transform은
`validation_inputs` 밖의 파일, 환경변수, 현재 시각, Git 출력 또는 네트워크를
읽어 output bytes를 바꿀 수 없다. 실행 환경은 판정에 필요하면
`runtime_preconditions`로 선언하되 출력 입력처럼 가장하지 않는다.

resolved manifest는 temp `O_EXCL` → file `fsync` →
`renameat2(RENAME_NOREPLACE)` → parent directory `fsync`로 먼저 고정한다.
자기 SHA-256을 자기 내용에 넣지 않는다. 외부 authorization·post-commit receipt가
물리 SHA-256/bytes를 단방향으로 결속한다.

## 6. 후보별 사용자 승인

authorization request는 독립검수 0/0/0인 정확한 R002 candidate-output manifest,
17개 mapping, transform spec, source CAS와 claim boundary를 결속한다. 일반적인
“진행해”, 과거 v2.4/r022 승인 또는 R001 문구를 재사용하지 않는다.

request에는 `accepted_response_utf8` 한 줄과 그 SHA-256/bytes를 넣는다. 그 한 줄은
최소 다음 값을 실제 hash로 모두 채운 후보별 완성 문자열이다.

```text
APPROVE_V25_R022 candidate=<candidate_id> output_manifest_sha256=<sha256> transaction_plan_sha256=<sha256> source_checkpoint_sha256=<sha256> scope=CONTROL_PLUS_R022_ONLY
```

placeholder가 남은 문자열은 승인 문구가 아니다. authorization receipt는 사용자가
보낸 raw UTF-8 bytes가 request의 `accepted_response_utf8`와 byte-exact인지
확인한다. 공백, 줄바꿈, 대소문자, Unicode normalization, candidate/hash/scope가
하나라도 다르면 fail-closed한다. receipt는 request와 raw response의
path/SHA-256/bytes, candidate ID, transaction ID, nonce와 단일 사용 상태를
결속한다. synthetic approval은 TEST mode 밖에서 거부한다.

승인 이후 실행한 quick gate만 유효하다. quick receipt는 authorization receipt,
source CAS, product inventory, candidate manifest와 실행한 command/result 원문을
결속하며 최대 age는 봉인된 계약값을 넘지 않는다.

## 7. v2.5 검사 명령 계약

### 7.1 quick activation 계약

quick 계약은 다음 두 ID와 exact 명령 순서다.

1. `CONTINUATION_QUICK_V2_5`
   — `PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/check_walksafe_project_continuation_v2_5_candidate.py --mode QUICK_PRECHECK`
2. `GOAL_GRAPH_QUICK_V2_5`
   — `PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/check_walksafe_goal_graph_v2_5_candidate.py --mode QUICK_PRECHECK`

candidate static manifest는 contract version, 두 exact command와 ordered-array
SHA-256을 고정한다. quick PASS는 승인 1의 fresh gate일 뿐 제품 구현-start gate가
아니다.

### 7.2 full implementation-start 19-check 계약

v2.5 static manifest는 다음 19개 ID와 exact command 문자열을 배열로 직접
보관한다. predecessor manifest를 runtime에 참조해 명령을 상속하거나 ID만
기록하는 것은 금지한다.

1. `CONTINUATION`
2. `GOAL_GRAPH`
3. `BASELINE_MATERIALIZATION`
4. `ANDROID_GATEWAY_BOUNDARY`
5. `NODE_TOOLCHAIN_PRE`
6. `GATEWAY_TYPECHECK`
7. `GATEWAY_TEST`
8. `GATEWAY_BUILD`
9. `WEB_TEST`
10. `WEB_LINT`
11. `WEB_TYPECHECK`
12. `WEB_BUILD`
13. `NODE_TOOLCHAIN_POST`
14. `ANDROID_UNIT_ASSEMBLE_LINT`
15. `TEST_LAYER_REGISTRY_VALIDATE`
16. `FIELD_AND_RELEASE_PYTEST`
17. `GOAL_CONTROL_PYTEST`
18. `CONTROL_AND_TRACE_PYTEST`
19. `REPOSITORY_STATE`

1·2번은 active v2.5 wrapper를 사용한다. 3~18번은 v2.4의 검토된 의미와 순서를
보존하되, 15·17·18번에는 승인 시 승격된 v2.5 registry/core regression을 exact
명령에 포함한다. 각 command 전체를 static manifest와 runbook 후보에 중복 없이
동일 bytes로 생성하고 contract SHA-256을 비교한다.

19번 exact 명령은 다음이다.

```sh
: "${WALKSAFE_GATE_EVENT_ID:?required}" && python3 -B scripts/check_walksafe_project_continuation_v2_5.py --root . --checkpoint docs/control/walksafe-project-continuation-checkpoint.json --print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"
```

repository-state CLI는 read-only이고 canonical JSON만 stdout에 출력한다. 출력은
event ID, Git top-level, HEAD commit/tree, index/config identity, porcelain-v2
NUL-framed 상태, controlled path set/content set, tracked deletion tombstone,
product inventory와 active checkpoint binding을 포함한다. path escape, symlink,
gitlink, duplicate path, invalid UTF-8, non-top-level root를 거부한다. 출력 파일을
CLI가 직접 만들지 않으며 gate runner가 raw stdout SHA-256/bytes를 event-scoped
add-only receipt에 결속한다.

quick, full과 repository-state 계약은 candidate manifest, active discovery,
runbook와 validator가 동일 digest를 계산해야 한다.

## 8. product inventory와 저장소 CAS

승인 1은 control 파일만 바꾼다. 제품 범위는 고정 pathspec
`apps`, `backend`, `configs`, `contracts`, `deploy`, `model`, `product`, `voice`,
`docker-compose.yml`의 tracked+non-ignored untracked 전체다.

product CAS에는 다음이 포함된다.

- 정렬된 전체 path set, present regular-file entries의 SHA-256/bytes
- tracked deletion tombstone 집합
- Git top-level, HEAD, index와 status identity
- control transition output exact exclusion set
- enumeration algorithm과 canonical content-set algorithm

후보 준비 시 preapproved inventory를 봉인하고, 사용자 승인 뒤 quick gate,
lock 획득 직후, checkpoint switch 직전과 post-check에서 다시 계산한다. 허용된
17개 control member를 제외한 product path/status/content가 하나라도 달라지면
writer는 쓰지 않거나, 이미 checkpoint가 commit됐다면 incident를 남기고
post-commit PASS receipt를 금지한다. product inventory를 working-snapshot
projection에 쓰면 그 exact entries도 `validation_inputs`에 포함한다.

runbook/README/registry의 기존 bytes는 별도 control CAS다. final target의 current
SHA-256/bytes가 승인 request의 before binding과 다르면
`BYTE_EXACT_CAS_REPLACE`를 수행하지 않는다.

## 9. active discovery와 ACTIVE 독립성

active discovery는 checkpoint의 package ID와 static/history/checker binding이 모두
일치할 때만 v2.5를 선택한다. checkpoint가 v2.4 source면 staged/final v2.5 파일이
일부 있어도 v2.4를 선택한다. 따라서 checkpoint switch 전 partial write가 v2.5
활성화를 주장할 수 없다.

ACTIVE continuation/Goal wrapper는 exact active filename에서만 실행되고
`scripts.walksafe_v2_5_validation`만 import한다. 다음을 읽지 않는다.

- `v2-5-control-candidate-r002/`와 seq1 candidate checkpoint
- candidate builder, candidate wrapper 또는 candidate-only core
- R001/R002 설계 문서의 runtime 내용
- test fixture나 synthetic receipt helper

ACTIVE mode의 허용 물리 의존성은 active checkpoint, r022 canonical pair,
v2.5 final static/history/package/core/wrappers/discovery, 승격된 runbook/README/
registry, v2.4 checkpoint archive와 gate 디렉터리의 resolved manifest,
authorization, quick-gate, post-check, post-commit receipt뿐이다. exact 목록과
각 고정 파일의 SHA-256/bytes를 final package에 선언한다. 동적 repository-state와
product inventory는 알고리즘·pathspec·event receipt로 선언한다.
`POSTCHECK_PENDING`은 같은 목록에서 아직 존재하면 안 되는 post-commit receipt만
제외하고, target checkpoint와 receipt-pending 상태를 요구한다.

승인 후 candidate root를 임시로 완전히 격리한 fixture에서도 ACTIVE 두 wrapper와
repository-state CLI가 PASS해야 한다. undeclared file open, candidate module
import 또는 active wrapper의 candidate filename 실행은 fail-closed한다.

## 10. 단일-lock 적용과 복구

writer는 모든 final bytes를 메모리 또는 비활성 temp에서 먼저 도출하고 validator
PRECOMMIT을 통과시킨다. 그 뒤 한 transaction lock에서 다음을 수행한다.

1. exact authorization response와 fresh quick gate를 검증하고 resolved manifest
   projection을 도출한다. resolved manifest가 이미 있으면 exact bytes를 검증한다.
2. source checkpoint/tail, r021, R002, control CAS와 product CAS를 재검증한다.
3. resolved manifest가 아직 없으면 add-only 내구화한다.
4. `NOREPLACE` 멤버는 temp `O_EXCL` → file `fsync` → rename no-replace →
   parent `fsync`로 쓴다. 이미 있으면 동일 transaction의 exact
   path/hash/bytes만 멱등 재사용한다.
5. `CAS_REPLACE` 멤버는 승인된 before inode/content를 descriptor로 재검증하고
   exact after temp를 `fsync`한 뒤 atomic replace와 parent `fsync`를 수행한다.
   임의 overwrite나 다른 transaction의 after bytes를 허용하지 않는다.
6. checkpoint switch 직전에 모든 CAS와 PRECOMMIT 검사를 다시 수행한다.
7. active checkpoint를 17번째·마지막 commit point로 atomic CAS replace하고
   parent directory를 `fsync`한다.
8. active discovery를 통해 두 wrapper의 `POSTCHECK_PENDING`과
   repository/product post-check를 실행하고 raw 결과를 내구화한다.
9. PASS일 때만 final event/checkpoint/resolved manifest/post-check를 단방향
   결속한 post-commit receipt를 `O_EXCL`/`fsync`/parent `fsync`로 만든다.
10. 두 wrapper의 steady `ACTIVE`를 통과시키고 receipt 디렉터리 내구화가 끝난
    뒤 lock을 해제한다.

복구도 같은 writer, transaction ID와 lock을 사용한다. 상태는 관찰된
checkpoint와 exact member 집합으로만 도출한다.

| 상태 | 의미와 허용 동작 |
|---|---|
| `REJECTED_PRELOCK` | 승인/CAS/입력 실패, write 0 |
| `LOCKED_PREWRITE` | lock 획득, final write 0; 안전하게 재시작 가능 |
| `RESOLVED_DURABLE_PRECHECKPOINT` | resolved manifest만 존재; v2.4 active |
| `PARTIAL_PRECHECKPOINT` | 일부 exact final 존재, source checkpoint 유지; 같은 transaction exact resume만 허용 |
| `COMMITTED_POSTCHECK_PENDING` | target checkpoint commit, v2.5+r022 active; post-check 재실행 |
| `COMMITTED_POSTCHECK_FAILED` | target active, incident 내구화, PASS receipt 금지, rollback 금지 |
| `COMMITTED_RECEIPT_PENDING` | post-check PASS, receipt만 없음; exact receipt 멱등 완성 |
| `COMMITTED_RECEIPTED` | post-commit receipt까지 durable, transaction 종료 |
| `DIVERGED_FAIL_CLOSED` | checkpoint/source/target/member 중 하나가 어느 허용 상태에도 속하지 않음; write·rollback 금지 |

checkpoint commit 전에는 v2.4/r021이 활성이다. commit 뒤에는 v2.5/r022가
활성이고 v2.4로 되돌려 해석하지 않는다. 어느 상태에서도 blind retry, partial
delete, target overwrite 또는 새 transaction ID로의 이어쓰기를 금지한다.

## 11. binding DAG와 hash-cycle 경계

의존 방향은 다음과 같다.

```text
v2.4+r021 pins ─┐
R002 exact68 ───┼─> R002 candidate outputs ─> core review
R002 design ────┘                              │
                                                v
candidate-output manifest + transform spec -> authorization request
                                                │
exact raw user response ------------------------┘
                                                v
authorization receipt -> fresh quick gate -> resolved manifest
                                                │
                                                v
seq2 PACKAGE_ACTIVATED -> seq3 BULK_REBASELINE_APPLIED
                                                │
                                                v
final history + checkpoint + other 15 members -> post-check
                                                │
                                                v
                                      post-commit receipt
```

- plan/request/event는 아직 모르는 final checkpoint/history 또는 post-commit
  receipt hash를 역참조하지 않는다.
- final events는 authorization와 quick-gate binding을 참조하지만 resolved
  manifest와 post-commit receipt를 참조하지 않는다.
- resolved manifest는 17개 final hash를 포함하지만 self hash와 post-check/
  post-commit hash를 포함하지 않는다.
- post-commit receipt만 resolved manifest, final members, final event/checkpoint와
  post-check를 단방향으로 결속한다.
- logical self seal은 physical self SHA-256을 대체한다고 주장하지 않는다.

## 12. 필수 negative/crash regression

최소 다음 fixture가 canonical write 없이 fail-closed해야 한다.

- validator가 write·lock·receipt API를 호출하거나 writer를 import
- writer가 arbitrary path/event/approval 인자를 수용
- 후보 ID·manifest/plan/source hash가 다른 승인, 과거 승인 재사용, 응답의
  공백·개행·대소문자·Unicode 차이
- 승인 전 active discovery/runbook/README/registry 최종 경로 변경
- core review findings 비zero, 승인보다 이른 quick gate, stale/future quick gate
- `validation_inputs` 한 건 누락·중복·role 변경 또는 undeclared file/env/time read
- final member 16/18개, candidate→final rename, promotion mode 변경
- resolved manifest가 self/post-receipt를 member로 포함하거나 final hash 불일치
- product tracked/untracked/path/content/deletion 하나 변경, product symlink/gitlink
- control CAS drift, Git root/HEAD/index/status drift
- R002 changed31 밖 assessment, carry37 bytes, mapping/dependency/topology 변경
- Goal status·artifact `126/257`·formal/device/gate/release/product credit 증가
- active wrapper가 candidate core를 import하거나 candidate root 제거 뒤 실패
- discovery가 source checkpoint에서 v2.5를 선택하거나 target checkpoint에서
  v2.4를 선택
- quick/full ID 순서·command 한 글자·contract hash 차이
- repository-state CLI가 파일을 쓰거나 event ID 없이 출력
- no-replace 충돌, CAS before drift, partial exact/mismatched member, 다른
  transaction ID resume
- resolved manifest fsync 전 final write, checkpoint가 마지막 commit point가 아님
- lock이 post-check/receipt parent fsync 전에 해제됨
- checkpoint commit 전 crash의 activation 주장
- checkpoint commit 뒤 rollback·overwrite 또는 post-check 실패인데 PASS receipt
- duplicate JSON key, BOM, invalid UTF-8, `NaN`/infinity, path escape

crash injection은 resolved manifest 전/후, 각 promotion 전/후, 각 file/parent
`fsync`, checkpoint replace 전/후, post-check 전/후와 receipt file/parent
`fsync` 전/후를 포함한다. 각 fixture는 위 상태 하나로만 분류되고 동일 transaction
복구 결과가 byte-exact여야 한다.

## 13. 승인 1과 승인 2

### 승인 1 — v2.5 제어 활성화와 r022 pair 적용

후속 R002 구현·독립검수·candidate-specific request가 완성된 뒤 사용자가 §6의
exact 응답을 별도로 보낼 때만 승인 1이 성립한다. 범위는 위 17개 control member,
seq2 `PACKAGE_ACTIVATED`, seq3 `BULK_REBASELINE_APPLIED`뿐이다.

승인 1은 FP-008 Goal 생성, `GOAL_STARTED`, 제품 코드 변경, formal/device 실행,
artifact/release credit을 허용하지 않는다.

### 승인 2 — FP-008 materialization과 제품 작업 시작

승인 1이 durable receipt까지 완료된 뒤에도 멈춘다. FP-008 준비문서와 독립검수,
r022 canonical binding, FP-047 completion provenance를 결속한 별도 request의
별도 사용자 응답이 승인 2다. 승인 2 뒤에만:

1. `WS-GOAL-EPIC-03-FP-008-R001` 후보를 materialize한다.
2. `GOAL_READY`를 검증한다.
3. §7.2의 fresh v2.5 full 19-check와 event-scoped repository-state receipt를
   통과한다.
4. 별도 `GOAL_STARTED`/session event를 기록한다.
5. 그 뒤에만 FP-008 제품 코드를 변경한다.

현재 상태는
`R002_EXACT68_REVIEWED_CONTROL_ARCHITECTURE_R002_DESIGN_ONLY_NON_EFFECTIVE`다.
다음 허용 행동은 R002 설계 독립검수와, findings 0일 때 이 설계에 맞는 격리 후보를
새로 만드는 것뿐이다. 사용자 승인 1 전에는 적용 writer를 실행하지 않는다.
