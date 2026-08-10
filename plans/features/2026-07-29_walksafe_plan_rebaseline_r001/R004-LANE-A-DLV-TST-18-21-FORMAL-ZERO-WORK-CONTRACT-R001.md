# R004 Lane A DLV-TST-18~21 formal-zero 후속 작업계약 R001

- 문서 ID: `WS-R004-LANE-A-DLV-TST-18-21-FORMAL-ZERO-WORK-CONTRACT-20260731-R001`
- 작성일: `2026-07-31`
- 상태: `NONCANONICAL_DRAFT`
- 작업 모드: `PLAN_ONLY`
- 실행 상태: `EXECUTION_STARTED=false`
- 계약 완성 상태: `WORK_CONTRACT_COMPLETE=false`
- artifact work 시작: `ARTIFACT_WORK_START_ALLOWED=false`
- future controlled write allowlist: `UNSET`
- credit 변화: `CREDIT_DELTA=0`
- exact 대상:
  `DLV-TST-18`, `DLV-TST-19`, `DLV-TST-20`, `DLV-TST-21`
- 상위 계획:
  `WS-257-CLOSURE-PLAN-20260729-R004`
- 현재 artifact source:
  `WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260729-R011`

## 0. 목적과 비권한 경계

이 문서는 R011에서 `OPEN / INTERNAL_READY`인 exact 네 산출물의 내용 결함을
formal 실행 0 상태에서 분석한, 향후 per-subject 실행계약 준비용 비실행 조사
초안이다. 현재 사실과 향후 작업의 필수 입력·내용·중단조건을 고정하지만,
exact output/staging path, builder/checker와 실행 권한은 아직 없다. 이 문서
자체는 canonical artifact, Goal, 시험계획 승인, 실행 receipt 또는 상태 전환
권한이 아니다.

```text
STATUS=NONCANONICAL_DRAFT
MODE=PLAN_ONLY
EXECUTION_STARTED=false
WORK_CONTRACT_COMPLETE=false
ARTIFACT_WORK_START_ALLOWED=false
FUTURE_CONTROLLED_WRITE_ALLOWLIST=UNSET
CREDIT_DELTA=0
ARTIFACT_STATUS_DELTA=0
ARTIFACT_CLOSURE_DELTA=0
FORMAL_EXECUTION_DELTA=0
FORMAL_PASS_DELTA=0
ACTUAL_DEVICE_DELTA=0
OWNER_APPROVAL_DELTA=0
REAL_EVENT_DELTA=0
RELEASE_CREDIT_DELTA=0
```

내용 작성, packet 생성, 내부 문서검토, owner 승인, 정식 실행, artifact
closure는 서로 다른 축이다. 이 계약을 검토하거나 나중에 실행해도 실제 증거와
적격 권한이 없는 축은 0에서 올리지 않는다.

## 1. 고정 입력

### 1.1 필수 물리 결속

| 역할 | 경로 | SHA-256 | bytes |
|---|---|---|---:|
| R011 exact257 ledger | `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r011/phase1-exact257-successor-ledger-r011.json` | `7fc4bd6f2242b17de75faa040e42b4744c972a492f87ff4365caaeae53f93368` | 2,637,012 |
| TST-18~23 묶음 초안 | `docs/deliverables/06-testing/test-quality-report.md` | `b2abd9915504d7dfd8578b465ed850c5fd1cdcf34a8654d4c46e1a44726f319c` | 3,279 |
| current 시험계획 원장 | `docs/deliverables/06-testing/registers/test-cases.json` | `fc51836c1dddd8852a74805e7fc5b1f6e647f461168139ae2565318745f87f2e` | 2,592,818 |
| active checkpoint | `docs/control/walksafe-project-continuation-checkpoint.json` | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |

### 1.2 정책 효력 결속

현재 정책 권한은 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`이다.

| 역할 | 경로 | SHA-256 | bytes |
|---|---|---|---:|
| PB 1.0.1 manifest | `docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json` | `b6f5b850a3983b8059b85d93dd07864520219d31fa65a65d740b6bab78231308` | 8,054 |
| current effective decision register | `docs/control/decision-interview/walksafe-effective-decision-register-current-20260726-r001.json` | `4a448f65280c2cd8cd850a749434f4e124769e47b7b84e31e2e14c58328d2faf` | 10,001 |

시험계획 원장의 `current_policy_binding`은
`activation_status=EFFECTIVE_BY_VALID_COMMITTED_RECEIPT`,
`fp035_overlay_status=APPROVED_EFFECTIVE_COMMITTED`를 기록한다.
checkpoint의 `approved_state.transaction_status`도 `COMMITTED`다.

### 1.3 계획·검수·작성 source 결속

| 역할 | 경로 | SHA-256 | bytes |
|---|---|---|---:|
| R004 plan-only successor | `docs/control/execution/artifact-audits/20260727/final-257/remaining-work-execution-plan-r004-plan-only-successor.md` | `8e76ac5b22d8390ceb9d3fbcc2121a3b53f693a2f887588703392e60746da44b` | 10,784 |
| R011 independent review | `docs/control/execution/artifact-closure/run-20260727-001/phase1-exact257-successor-independent-review-r011.md` | `3bf2d1d107db482ab63601f25ade3805536aaa242070d888823bfa271e9a4e5f` | 11,686 |
| artifact authoring contract | `docs/deliverables/00-control/artifact-register.json` | `a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f` | 3,803,696 |
| defect draft | `docs/deliverables/06-testing/registers/defects.json` | `98974e06294675dc7ee659571d1d4b20e297a4593bd168b03837ae07a6215687` | 2,453 |
| metrics draft | `docs/deliverables/06-testing/registers/metrics.json` | `7ea5ebaa38d29448646873dc4b48c559b14c5a2b9b274e82868505058f39c476` | 2,591 |
| software test report draft | `docs/deliverables/06-testing/software-test-report.md` | `3bf316ad9ae04df83804a42d821daf96ec327db05927d2e01d868da27b50d275` | 2,868 |
| residual-risk draft | `docs/deliverables/06-testing/registers/residual-risks.json` | `120c858c9b04983c07c2dd066555da9387d4ca3be0892176fb08b2b39ce2c1c7` | 6,529 |

R011 independent review의 현재 verdict는
`PASS_FOR_READY25_PROGRESS_APPLICATION_WITH_ZERO_CREDIT_BOUNDARY_ONLY`,
findings는 `BLOCKING/MAJOR/MINOR=0/0/0`이다. 이는 이 exact4의 content
acceptance가 아니라 R011의 제한된 zero-credit 검수 범위다.

§1의 전체 입력 중 하나라도 hash 또는 bytes가 달라지거나 R011 review verdict와
findings가 위 값과 다르면 이 조사 초안으로 후속 content 작업을 시작하지 않는다.
새 사실을 조사하고 이 문서의 add-only successor를 먼저 작성한다.

## 2. 현재 사실 봉인

### 2.1 R011 exact4

| ID | artifact 종류 | R011 상태 | queue | 실행 상태 |
|---|---|---|---|---|
| `DLV-TST-18` | current-state defect register | `OPEN` | `INTERNAL_READY` | `NOT_STARTED_OR_NOT_CLAIMED` |
| `DLV-TST-19` | current-state coverage/metrics register | `OPEN` | `INTERNAL_READY` | `NOT_STARTED_OR_NOT_CLAIMED` |
| `DLV-TST-20` | content + separate event 시험 결과보고서 | `OPEN` | `INTERNAL_READY` | `NOT_STARTED_OR_NOT_CLAIMED` |
| `DLV-TST-21` | current-state residual-risk register | `OPEN` | `INTERNAL_READY` | `NOT_STARTED_OR_NOT_CLAIMED` |

네 행 모두 다음 R011 claim boolean이 정확히 `false`다.

- `artifact_completion_claimed`
- `current_scope_n_a_closure_claimed`
- `execution_completion_claimed`
- `external_fact_verified_claimed`
- `formal_pass_claimed`
- `global_artifact_completion_claimed`
- `owner_approval_claimed`
- `real_event_claimed`
- `release_eligible_claimed`
- `scope_decision_claimed`

각 행의 `artifact_closure`에서도 `completion_claimed`,
`current_scope_n_a_closure_claimed`, `global_artifact_completion_claimed`,
`phase1_closure_delta`가 모두 `false`다.

네 행 각각의 현재 progress 축은 다음과 같다.

| 축 | 현재 값 |
|---|---:|
| `content_authored.observations` | 0 |
| `internal_validation.observations` | 0 |
| `independent_review` | 0 |
| `packet_materialization` | 0 |
| `factual_input.verified_fact_count` | 0 |
| `owner_approval.approval_count` | 0 |
| `real_event.receipt_count` | 0 |
| `scope_decision.decision_count` | 0 |

따라서 기존 초안 파일에 일부 틀이나 문장이 있다는 사실을 R011의 per-ID
content observation, 검토, 승인 또는 event credit로 소급하지 않는다.

### 2.2 formal-zero와 release 경계

| 축 | 현재 사실 |
|---|---|
| 시험계획 행 | 279 |
| 고유 `test_case_id` | 279 |
| `execution_status=NOT_RUN` | 279 |
| PASS / FAIL | `0 / 0` |
| formal checkpoint | `279 total / 279 NOT_RUN` |
| 실제 기기 시험 | `NOT_RUN` |
| release gate | exact 5개 모두 `NOT_RUN` |
| gate waiver | `remaining_gates_waived=false` |
| release | `NOT_ELIGIBLE` |

다섯 gate는 다음 exact ID다.

- `GATE-PHONE-QUEUE-BYTE-LIMIT`
- `GATE-SERVER-CAPACITY-STATE-CONTRACT`
- `GATE-RAW-COLLECTION-RELEASE-REVIEW`
- `GATE-CLOUD-COST-MEASUREMENT`
- `GATE-SINGLE-ADMIN-RECOVERY-DRILL`

여기서 `formal-zero`는 정식 실행 결과가 하나도 없다는 뜻이다. 결함, 위험,
품질 문제 또는 적용 대상이 0이라는 뜻이 아니며 내부 단위·구성요소 검사를
formal PASS로 바꾸지 않는다.

## 3. stale PB 1.0.0 자료 조정 규칙

다음 파일은 과거 생성 초안으로 보존하되 PB 1.0.0 또는 FP-035 묶음 승인 대기
문구를 현행 사실의 positive 근거로 사용하지 않는다.

| 파일 | stale 내용 | 현재 조정 |
|---|---|---|
| `test-quality-report.md` | 정책 기준선 1.0.0, FP-035 묶음 승인 대기 | PB 1.0.1 COMMITTED와 FP-035 effective overlay를 우선한다. |
| `registers/test-cases.json`의 `TC-FP-035-01`~`04` nested 필드 | `approval_readiness`·`execution_readiness=BLOCKED_PENDING_BUNDLED_APPROVAL`, formal branch `NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL`, network redesign `NORMALIZED_NOT_YET_BASELINED`, 과거 blocker refs | top-level current binding/summary만 현행 정책 효력 근거로 사용한다. 네 nested 행은 successor reconciliation 전 positive 근거에서 제외하되 `NOT_RUN`과 gate 의존성은 보존한다. |
| `registers/metrics.json` | PB 1.0.0, `OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL` | 정책 issue는 1.0.1에서 해결됐다. 관련 gate 의존 시험 4개만 계속 `NOT_RUN`이다. |
| `registers/residual-risks.json` | PB 1.0.0, FP-035 승인 대기 위험 | FP-035 pending을 current risk로 세지 않는다. 역사 행은 보존하고 successor에서 효력 대체를 명시한다. |

추가로 `registers/defects.json`과 `software-test-report.md`도 baseline 표기가
1.0.0이다. 전자는 결함 schema와 비어 있는 pre-execution snapshot의 구조만
참고하고, 후자는 현재 결과 근거로 사용하지 않는다.

현행 판정 우선순위는 COMMITTED application receipt와 PB 1.0.1 manifest,
current effective decision register, current test-case binding, checkpoint다.
후속 결과는 과거 생성 파일을 손으로 덮어쓰지 않고 새 revision builder 또는
add-only overlay/instance로 만든다.

`python3 -B scripts/build_walksafe_formal_dev_test_20260721.py --check`는 승인 전
PB 1.0.0 생성 세계의 currentness를 검사하므로 positive gate로 사용하지 않는다.
통과·실패 어느 쪽도 이 exact4의 current content, formal 실행, 승인 또는 closure
근거가 아니다.

## 4. 향후 per-subject 실행계약의 필수 조건

### 4.1 시작조건

현재 문서는 아래 필드를 확정하지 못했으므로 artifact work에 사용할 수 없다.
각 ID별 future controlled write allowlist와 다음 항목을 갖춘 add-only
per-subject 계약을 별도로 작성·검수한 뒤에만 exact 한 subject씩 시작한다.

- action ID와 attempt ID
- exact controlled output path와 exclusive staging path
- builder/checker path, SHA-256와 negative test
- exact argv, cwd, tool version과 timeout
- `LIGHT` resource 예상과 CPU·memory·disk·wall-time hard stop
- writer, operator, reviewer와 승인 authority
- materialized Goal과 start receipt
- add-only/no-clobber atomic publication
- 실패 시 `INTERRUPTED` 또는 `REJECTED`, canonical apply 0, 원 evidence 보존
  rollback

그 future 계약은 다음도 모두 만족해야 한다.

1. active v2.4 규칙에 따라 단일 `ARTIFACT_WORK` Goal이 materialize되고 별도
   시작 gate와 `GOAL_STARTED`가 유효하다.
2. §1의 source hash와 §2의 zero 경계가 다시 검증된다.
3. canonical writer 한 명, `QA책임자` content owner, 필요한 reviewer와
   `제품책임자` approver가 subject에 결속된다.
4. `독립QA검토자`가 아직 지정되지 않았으면 독립 QA credit과 최종 acceptance를
   요청하지 않는다.
5. 새 revision의 exact 출력 경로, predecessor, builder, 변조 거부 검사가
   작업 시작 전에 정해진다.

네 subject에 대한 read-only 조사와 초안 설계는 병렬 가능하지만 controlled
artifact write와 canonical transition은 subject 하나씩 수행한다.

### 4.2 공통 출력 필드

모든 successor에는 최소한 다음을 넣는다.

- artifact ID와 revision ID
- `as_of` 시각과 열거된 scope
- PB 1.0.1 manifest와 effective decision register의 path/hash
- test-case 원장 path/hash와 exact 279 ID 집합 결속
- source/build/model/config/candidate가 없으면 명시적인 `NOT_BOUND`
- source 목록과 각 path/hash/record count
- content owner, reviewer, approver와 각 결정 상태
- formal/device/event 상태와 evidence locator
- 결함·위험·gate·release 영향
- predecessor와 append-only supersession 관계
- 모든 claim boolean의 실제 값

값이 없으면 placeholder hash, 가상 candidate, 합성 event 또는 추정 결과를
만들지 않고 `MISSING`, `NOT_BOUND`, `NOT_RUN`, `NOT_MEASURED` 중 실제 의미에
맞는 값을 쓴다.

## 5. exact4 작업 단위

### 5.0 Canonical authoring required-content matrix

아래는 artifact register의 완전한 필수 내용을 future per-subject 계약에
전달하는 matrix다. `MISSING`은 이 조사자가 값을 합성하지 않는다는 뜻이다.
공통 owner는 `QA책임자`, due trigger는 해당 ID의 future content-review
candidate freeze 전이다. 공통 exit predicate는 열거 항목이 current source와
결속되고 독립 content review findings가 0인 상태이며, 그 뒤에도 별도 적격
acceptance 전에는 artifact가 `OPEN`이다.

| ID | 현재 확인된 최소 사실 | future required content | 현재 상태 / owner / due·exit |
|---|---|---|---|
| `DLV-TST-18` | pre-execution defect array와 summary 0 | register baseline ID/version/as-of/source hash; `opened_at`; defect ID/environment/reproduction/expected/actual; severity/priority; owner/status/target; fix/retest; requirement/risk/waiver trace | `MISSING / QA책임자 / TST-18 candidate freeze 전·모든 필드와 source reconciliation` |
| `DLV-TST-19` | planned 279, executed/PASS/FAIL 0, source trace 68 | requirement·scenario·code·branch·environment coverage의 denominator/numerator/formula/as-of; pass/fail/skip/flaky; defect leakage/reopen; unknown/excluded와 source | `MISSING / QA책임자 / TST-19 candidate freeze 전·계산 재현과 gap closure 조건 완전` |
| `DLV-TST-20` | named candidate 없음, formal 0/279, raw result 없음 | 계획 대비 실제 실행 범위; level별 result/metric; fail/skip/deviation; unresolved defect/risk; conclusion/approval/evidence index; 같은 시점의 TST-21 snapshot; include/exclude 근거 | `MISSING / QA책임자 / TST-20 candidate freeze 전·content-only exit는 NOT_BOUND/NOT_RUN과 exact later-event predicate·authority, execution-completion exit는 named candidate+immutable raw receipt+event authority acceptance` |
| `DLV-TST-21` | 과거 risk 9행과 current Gate 5개, formal 미실행 | 영향·가능성·심각도; mitigation·workaround·disclosure; acceptor·decision·expiry; follow-up Goal; release impact; snapshot version/as-of와 current policy reconciliation | `MISSING / QA책임자 / TST-21 candidate freeze 전·current risk/mitigation/acceptance source 완전` |

### 5.1 `DLV-TST-18` — 결함 schema와 scoped-zero

현재 `registers/defects.json`은 결함 0건인 schema snapshot이다. 현재 defect
instance row의 필수 필드는 다음 exact 집합이다.

```text
CURRENT_DEFECT_INSTANCE_REQUIRED_FIELDS=
defect_id, version, title, environment_id, source_test_instance_id,
evidence_id, reproduction_steps, expected_result, actual_result,
severity, priority, owner_role, status, target_date, requirement_ids,
policy_ids, risk_ids, waiver_ids, fix_commit, retest_evidence_ids
```

artifact register가 요구하는 future register opening metadata는 row schema와
혼합하지 않고 별도 header에 둔다.

```text
FUTURE_REGISTER_OPENING_METADATA=
baseline_id/version, opened_at, as_of, scope, owner, authoritative_sources
```

현재 0은 `정식 실행 전 생성된 파일의 defects 배열과 summary가 0`이라는
scoped-zero다. formal 279가 전부 `NOT_RUN`이고 결함 instance 경로와 formal
execution instance 경로도 아직 materialize되지 않았으므로, 다음을 뜻하지 않는다.

- 제품 결함이 관측 결과 0건임
- formal run에서 실패 0건이 확인됨
- 알려진 위험이나 미해결 문제가 0건임
- `NO_EVENTS_TO_DATE`가 owner에 의해 attest됨

후속 작업:

1. PB 1.0.1을 결속한 successor defect schema를 만든다.
2. `as_of`, candidate 상태, formal execution source, defect instance source를
   정확히 열거한다.
3. 유효 defect instance가 있으면 각 instance를 schema와 source run에
   append-only로 연결한다.
4. instance가 없으면 `SCOPED_ZERO_PRE_EXECUTION`과 source count 0을 기록하되
   owner attestation이 없으면 `NO_EVENTS_TO_DATE_ATTESTED=false`로 둔다.
5. `CLOSED`는 fix commit과 같은 결함의 retest evidence가 있을 때만 허용한다.

수용조건:

- 필수 schema field 누락 0
- 열거 source와 실제 file/count/hash 불일치 0
- scoped-zero와 product-quality-zero 혼동 0
- formal/pass/owner/event/closure credit 0 유지
- QA content review가 통과해도 R011 artifact 행은 별도 적격 acceptance와
  canonical successor 전까지 `OPEN`

### 5.2 `DLV-TST-19` — coverage gap 명시

현재 확정 가능한 값은 계획 279개, 고유 ID 279개, source trace 68개,
formal 실행 0개, formal execution coverage 0%, PASS/FAIL 0/0뿐이다.
다음 coverage는 current execution evidence가 없어 미측정 또는 미검증이다.

- 요구사항·acceptance criterion·사용 시나리오별 실행 coverage
- 코드 statement·branch coverage
- Android 사용자 앱·관리자 앱·서버·DB·모델·설정의 구성요소 coverage
- 환경·지원기기·OS·접근성 설정 coverage
- 실제 기기·현장·장시간·복구 상황 coverage
- 같은 immutable candidate에 결속된 evidence coverage

후속 작업:

1. design coverage와 execution coverage를 별도 축으로 정의한다.
2. 각 축에 denominator, numerator, excluded/unknown, 계산식, source hash를 둔다.
3. formal 미실행 축은 0 또는 `NOT_RUN`, 계측 자체가 없는 code/branch 축은
   `NOT_MEASURED`로 구분한다.
4. gap마다 owner, 원인, 필요한 evidence, 해소조건과 release 영향을 기록한다.
5. FP-035는 `RESOLVED_BY_EFFECTIVE_BASELINE_1_0_1`로 조정하되 관련 gate
   의존 case 4개는 `NOT_RUN`으로 보존한다.
6. pass/fail/skip/flaky와 defect leakage/reopen은 서로 다른 지표로 두고
   데이터 시점과 source가 없으면 `NOT_MEASURED`로 둔다.

수용조건:

- 279 row/279 unique/279 `NOT_RUN` 재현
- planned, executed, measured, passed 비율의 상호 대체 0
- code/branch 수치 추정 0
- 환경·실기기 evidence가 없는 coverage의 positive claim 0
- 각 gap에 direct source와 종료조건 존재

### 5.3 `DLV-TST-20` — named candidate와 결과 부재

현재 보고서에는 formal 실행 결과뿐 아니라 결과가 귀속될 immutable named
candidate의 ID와 구성요소 hash 묶음도 없다. 현재 상태는 다음과 같다.

```text
CANDIDATE_BINDING=NOT_BOUND
FORMAL_RESULT=NOT_RUN
EXECUTED=0
PASS=0
FAIL=0
```

후속 작업:

1. content revision에 candidate가 아직 없음을 명시하고, 향후 필요한
   candidate manifest 필드를 고정한다.
2. 필수 tuple은 candidate ID/version, source commit/snapshot, 사용자 APK,
   관리자 APK, gateway/server build, model, config, DB migration, OpenAPI의
   path/hash다. 해당 구성요소가 적용되지 않으면 authority와 이유가 있는
   `NOT_APPLICABLE` 결정이 필요하다.
3. 시험계획 승인 receipt, run ID, 환경·기기, 실행자·검토자·승인자,
   execution window, raw evidence, 279 per-case 결과가 없으면 결과보고서
   completion을 중단한다.
4. 실제 formal run은 이 Lane A content 계약 밖의 별도 `FORMAL_TEST_RUN`에서
   수행하고 append-only execution instance를 만든다.
5. 결과 successor는 같은 candidate의 defect, metrics, residual risk와
   시점·hash가 일치할 때만 작성한다.
6. 계획 대비 실제 실행 범위, 시험 level별 result·metric, fail·skip·deviation,
   unresolved defect·risk, conclusion·approval·evidence index, TST-21 snapshot,
   include/exclude 근거를 같은 candidate에 결속한다.

수용조건:

- placeholder 또는 이름 없는 candidate 0
- candidate가 없을 때 `NOT_RUN` 외 결과 0
- formal raw receipt 없이 PASS/FAIL/BLOCKED/SKIPPED 합성 0
- content predicate만 충족한 경우 execution completion claim은 계속 `false`
- named candidate 또는 필수 구성요소가 없으면 artifact는 `OPEN` 유지
- `CONTENT_ACCEPTANCE_CANDIDATE_ALLOWED_WITHOUT_EVENT=true`
- `EXECUTION_COMPLETION_CLAIMED=false`
- `ARTIFACT_CLOSURE_STATUS=OPEN`

### 5.4 `DLV-TST-21` — 위험·완화·수용 정보 보완

현재 residual-risk 초안은 9개 `OPEN` 행을 담지만 PB 1.0.0과 FP-035 승인 대기
전제를 포함하므로 `9`를 current authoritative risk count로 사용하지 않는다.
다섯 release gate가 `OPEN / NOT_RUN / unwaived`라는 사실만 checkpoint로
독립 확인되며 나머지 행은 current source에 대해 다시 reconcile해야 한다.

후속 successor의 각 위험 행에는 다음이 필요하다.

- risk ID, source policy/gate/defect와 affected candidate
- 영향받는 사용자·상황·자산
- likelihood, severity, initial risk와 산정 기준
- mitigation/action, owner, due/review/expiry
- workaround와 사용자·운영자 disclosure
- mitigation verification evidence와 상태
- residual likelihood/severity/rating
- acceptance status, decision, acceptor, authority, decided-at, expiry, rationale
- follow-up Goal과 snapshot version/as-of
- release/gate/blocking 영향과 재검토 trigger

FP-035 승인 대기 행은 삭제하지 않고 역사 provenance를 보존한 채
`SUPERSEDED_BY_PB_1_0_1_EFFECTIVE_COMMITTED`로 조정한다. 이 조정은 관련
gate 의존 시험 4개나 다섯 gate를 닫지 않는다.

수용조건:

- current source와 reconcile되지 않은 risk 행 0
- mitigation 없는 risk는 `MITIGATION_MISSING`, 수용 결정 없는 risk는
  `ACCEPTANCE_MISSING`으로 명시
- 에이전트나 content writer가 risk owner의 수용 결정을 대행한 행 0
- 다섯 gate의 status `NOT_RUN`, waiver `false`
- unresolved required risk 또는 acceptance가 남으면 release
  `NOT_ELIGIBLE`

## 6. 실행 순서와 실패 반환

| 단계 | 작업 | 검증 |
|---|---|---|
| Z0 | source·정책·zero boundary preflight | §1 hash와 §2 targeted assertion 모두 PASS |
| Z1 | TST-18 successor content | schema, source reconciliation, scoped-zero 의미 검사 |
| Z2 | TST-19 successor content | coverage denominator/numerator와 gap completeness 검사 |
| Z3 | TST-20 content predicate | named candidate 또는 명시적 `NOT_BOUND`, 결과 비합성 검사 |
| Z4 | TST-21 successor content | current risk·mitigation·acceptance·gate crosswalk 검사 |
| Z5 | exact4 direct trace와 독립 content review | exact4 unique, 누락/추가 0, findings 0 |
| Z6 | 적격 acceptance/canonical successor 후보 | 별도 권한 전에는 apply 0, credit 0 |

content, source, trace, reviewer independence 또는 authority가 불충분하면 해당
subject를 `INTERNAL_READY` open 상태로 반환한다. named candidate나 실제 run
evidence가 없으면 TST-20을 닫지 않는다. negative formal 결과가 나중에 생기면
원 receipt를 보존하고 defect/risk 및 구현 원인 lane으로 반환하며 PASS로
정규화하지 않는다.

## 7. 검증 계약

### 7.1 현행 제어 회귀

```bash
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. \
  python3 -B scripts/build_walksafe_phase1_exact257_successor_r011_20260729.py --check

python3 -B scripts/check_walksafe_project_continuation_v2_4.py
python3 -B scripts/check_walksafe_goal_graph_v2_4.py

PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. \
  .venv/bin/python -B -m pytest -q -p no:cacheprovider \
  tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
```

작성 시점 결과:

- R011 builder `--check`: PASS,
  `exact257=257 / closed-equivalent=126 / open=131 /
  release=NOT_ELIGIBLE`
- v2.4 continuation quick: PASS
- v2.4 Goal graph quick: PASS,
  `26 managed Goals / ready 2 / focus WS-GOAL-EPIC-03 / ACTIVE`
- R011 pytest: `23 passed, 1 skipped`

위 결과는 source/control 무결성 검사일 뿐 exact4 content acceptance, formal
PASS, 실제 기기, gate 또는 release 증거가 아니다.

### 7.2 targeted current assertions

```bash
set -euo pipefail

r011='docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r011/phase1-exact257-successor-ledger-r011.json'
cases='docs/deliverables/06-testing/registers/test-cases.json'
checkpoint='docs/control/walksafe-project-continuation-checkpoint.json'

test "$(sha256sum "$r011" | cut -d' ' -f1)" = \
  '7fc4bd6f2242b17de75faa040e42b4744c972a492f87ff4365caaeae53f93368'
test "$(sha256sum docs/deliverables/06-testing/test-quality-report.md | cut -d' ' -f1)" = \
  'b2abd9915504d7dfd8578b465ed850c5fd1cdcf34a8654d4c46e1a44726f319c'
test "$(sha256sum "$cases" | cut -d' ' -f1)" = \
  'fc51836c1dddd8852a74805e7fc5b1f6e647f461168139ae2565318745f87f2e'
test "$(sha256sum "$checkpoint" | cut -d' ' -f1)" = \
  '6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c'

policy='docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json'
effective='docs/control/decision-interview/walksafe-effective-decision-register-current-20260726-r001.json'
r004='docs/control/execution/artifact-audits/20260727/final-257/remaining-work-execution-plan-r004-plan-only-successor.md'
r011_review='docs/control/execution/artifact-closure/run-20260727-001/phase1-exact257-successor-independent-review-r011.md'

test "$(sha256sum "$policy" | cut -d' ' -f1)" = \
  'b6f5b850a3983b8059b85d93dd07864520219d31fa65a65d740b6bab78231308'
test "$(wc -c < "$policy")" -eq 8054
test "$(sha256sum "$effective" | cut -d' ' -f1)" = \
  '4a448f65280c2cd8cd850a749434f4e124769e47b7b84e31e2e14c58328d2faf'
test "$(wc -c < "$effective")" -eq 10001

printf '%s  %s\n' \
  '8e76ac5b22d8390ceb9d3fbcc2121a3b53f693a2f887588703392e60746da44b' "$r004" \
  '3bf2d1d107db482ab63601f25ade3805536aaa242070d888823bfa271e9a4e5f' "$r011_review" \
  'a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f' 'docs/deliverables/00-control/artifact-register.json' \
  '98974e06294675dc7ee659571d1d4b20e297a4593bd168b03837ae07a6215687' 'docs/deliverables/06-testing/registers/defects.json' \
  '7ea5ebaa38d29448646873dc4b48c559b14c5a2b9b274e82868505058f39c476' 'docs/deliverables/06-testing/registers/metrics.json' \
  '3bf316ad9ae04df83804a42d821daf96ec327db05927d2e01d868da27b50d275' 'docs/deliverables/06-testing/software-test-report.md' \
  '120c858c9b04983c07c2dd066555da9387d4ca3be0892176fb08b2b39ce2c1c7' 'docs/deliverables/06-testing/registers/residual-risks.json' \
  | sha256sum -c - >/dev/null

grep -F '| Verdict | `PASS_FOR_READY25_PROGRESS_APPLICATION_WITH_ZERO_CREDIT_BOUNDARY_ONLY` |' "$r011_review" >/dev/null
grep -F '| BLOCKING | 0 |' "$r011_review" >/dev/null
grep -F '| MAJOR | 0 |' "$r011_review" >/dev/null
grep -F '| MINOR | 0 |' "$r011_review" >/dev/null

jq -e '
  [.records | to_entries[] |
    select(.value.artifact_type_code |
      IN("DLV-TST-18","DLV-TST-19","DLV-TST-20","DLV-TST-21"))
  ] as $entries
  | ($entries | map(.value)) as $rows
  | ($entries | map({index:.key,id:.value.artifact_type_code})) ==
    [{"index":229,"id":"DLV-TST-18"},
     {"index":230,"id":"DLV-TST-19"},
     {"index":231,"id":"DLV-TST-20"},
     {"index":232,"id":"DLV-TST-21"}]
  and ($rows | length) == 4
  and ([$rows[].artifact_type_code] | sort) ==
    ["DLV-TST-18","DLV-TST-19","DLV-TST-20","DLV-TST-21"]
  and all($rows[];
    .artifact_closure.status == "OPEN"
    and .queue_route.current == "INTERNAL_READY"
    and all(.claim_boundary[]; . == false)
    and .artifact_closure.completion_claimed == false
    and .artifact_closure.current_scope_n_a_closure_claimed == false
    and .artifact_closure.global_artifact_completion_claimed == false
    and .artifact_closure.phase1_closure_delta == false
    and .predecessor_artifact_ledger_record.execution_event_status ==
      "NOT_STARTED_OR_NOT_CLAIMED"
    and (.progress_axes.content_authored.observations | length) == 0
    and (.progress_axes.internal_validation.observations | length) == 0
    and (.progress_axes.independent_review | length) == 0
    and (.progress_axes.packet_materialization | length) == 0
    and .progress_axes.factual_input.verified_fact_count == 0
    and .progress_axes.owner_approval.approval_count == 0
    and .progress_axes.real_event.receipt_count == 0
    and .progress_axes.scope_decision.decision_count == 0
  )
' "$r011" >/dev/null

jq -e '
  (.test_cases | length) == 279
  and ([.test_cases[].test_case_id] | unique | length) == 279
  and all(.test_cases[];
    .execution_status == "NOT_RUN"
    and .result == null
    and (.evidence_ids | length) == 0
    and (.defect_ids | length) == 0
  )
  and .summary.test_case_count == 279
  and .summary.not_run_count == 279
  and .summary.pass_count == 0
  and .summary.fail_count == 0
  and .summary.source_trace_count == 68
  and .summary.fp035_bundle_approval_pending_test_case_count == 0
  and .summary.fp035_related_gate_pending_test_case_count == 4
  and .summary.fp035_policy_issue_status ==
    "RESOLVED_BY_EFFECTIVE_BASELINE_1_0_1"
  and .summary.fp035_normalization_status ==
    "EFFECTIVE_BY_VALID_COMMITTED_RECEIPT_RELATED_GATES_NOT_RUN"
  and ([.test_cases[] | select(.source_policy_id == "FP-035")] | length) == 4
  and all(.test_cases[] | select(.source_policy_id == "FP-035");
    .approval_readiness == "BLOCKED_PENDING_BUNDLED_APPROVAL"
    and .execution_readiness == "BLOCKED_PENDING_BUNDLED_APPROVAL"
    and .mobile_network_branch_formal_test_status ==
      "NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL"
    and .network_branch_redesign_status == "NORMALIZED_NOT_YET_BASELINED"
  )
  and .current_policy_binding.baseline_id ==
    "PB-WALKSAFE-FEATURE-POLICY-1.0.1"
  and .current_policy_binding.activation_status ==
    "EFFECTIVE_BY_VALID_COMMITTED_RECEIPT"
  and .current_policy_binding.fp035_overlay_status ==
    "APPROVED_EFFECTIVE_COMMITTED"
' "$cases" >/dev/null

jq -e '
  .approved_state.policy_baseline_id ==
    "PB-WALKSAFE-FEATURE-POLICY-1.0.1"
  and .approved_state.transaction_status == "COMMITTED"
  and .verification_boundary.formal_test_total == 279
  and .verification_boundary.formal_test_not_run_count == 279
  and .verification_boundary.actual_device_test_status == "NOT_RUN"
  and (.verification_boundary.remaining_gate_ids | length) == 5
  and (.verification_boundary.remaining_gate_ids | sort) == [
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL"
  ]
  and .verification_boundary.all_remaining_gate_status == "NOT_RUN"
  and .approved_state.remaining_gates_waived == false
  and .verification_boundary.formal_test_pass_claimed == false
  and .verification_boundary.release_eligible == false
  and .approved_state.release_status == "NOT_ELIGIBLE"
' "$checkpoint" >/dev/null
```

## 8. 계약 종료조건

이 plan-only 비실행 조사 초안의 현재 종료조건은 문서 작성과 §7 검증뿐이다.
`WORK_CONTRACT_COMPLETE=false`, `ARTIFACT_WORK_START_ALLOWED=false`이며 exact4
artifact work의 시작·종료계약이 아니다.

향후 exact4 content successor는 다음을 모두 만족해야 positive acceptance
후보가 될 수 있다.

- exact 네 ID만 처리하고 누락·추가·중복 0
- PB 1.0.1과 FP-035 effective committed를 current authority로 사용
- stale PB 1.0.0 문구를 current 근거로 사용한 행 0
- TST-18 scoped-zero 의미와 defect schema 완전
- TST-19 coverage gap·분모·분자·미측정 구분 완전
- TST-20 named candidate와 결과 부재를 합성 없이 표현
- TST-21 current risk·mitigation·acceptance·release 영향 완전
- direct trace와 적격 reviewer/approver 분리
- formal 279/279 `NOT_RUN`, actual device `NOT_RUN`, gate 5개
  `NOT_RUN / unwaived`, release `NOT_ELIGIBLE` 유지
- 공식 상태·closure·execution·approval·event·release credit 변화 0

별도 artifact Goal, 독립검수, 적격 acceptance, canonical successor 승인·적용,
§4의 완전한 per-subject 실행계약과 formal 실행 권한이 생기기 전의 다음 행동은
`WAIT_FOR_SEPARATE_ARTIFACT_WORK_AUTHORITY`다.
