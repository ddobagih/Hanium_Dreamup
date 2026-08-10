# PRE-P Validation Convergence Design/Build Plan R004

## 1. 통제 상태와 predecessor

| 항목 | exact 값 |
|---|---|
| document_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R004` |
| status | `NON_EFFECTIVE_PLAN_ONLY` |
| 작성일 | `2026-07-31` |
| R003 path | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R003.md` |
| R003 SHA-256 / bytes / lines | `909481f68fe030acacaa5b379fed42258563c3f0ceefd0ec02421a28cb784ee7` / `35847` / `780` |
| R003 verdict | `REJECTED_PLAN_DO_NOT_EXECUTE; BLOCKING=4 / MAJOR=2 / MINOR=0` |
| R003 review path | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R003-independent-review-r001.md` |
| R003 review SHA-256 / bytes / lines | `41fae31a032e56e99d3f575ff9d68dbaaf3de85b66d8de7ce9cc349229253746` / `20894` / `459` |
| R003 review type | `regular file, non-symlink, nlink=1` |
| R002 review SHA-256 | `194fa4a30fb5f70af508a99045280d4f05ad287cc6eef747136d443f5d55c504` |
| R001 review SHA-256 | `95c485bfae33f3a8d0e0a783ef9d0c602fc72d779597d7144ca87530df0e4cd0` |
| stale R007 SHA-256 | `2c0eb19da8b9a2df77cb43b2ffd169583591f0a23150d9ef5eff00cf39327610` |
| P17 | `P17_BUILD_DEFERRED` |

R001~R003와 review, 기존 R007, daylog는 immutable history-only다. 위 R003
review binding은 physical file을 직접 관측한 값이고 target R003 SHA와 verdict
`REJECTED_NON_EFFECTIVE_PLAN_ONLY; 4/2/0`을 재확인했다.

```text
STATUS=NON_EFFECTIVE_PLAN_ONLY
CURRENT_AUTHORITY=ABSENT_DENY_ALL
STAGE_A_AUTHORITY=ABSENT_DENY_ALL
STAGE_B_AUTHORITY=ABSENT_DENY_ALL
STAGE_C_AUTHORITY=ABSENT_DENY_ALL
ACTIVE_WRITE_ALLOWED=false
CANDIDATE_WRITE_ALLOWED=false
RESOLVED_WRITE_ALLOWED=false
EXTERNAL_ENV_WRITE_ALLOWED=false
CHECKPOINT_WRITE_ALLOWED=false
P17_BUILD_DEFERRED=true
CANONICAL_PRODUCT_CREDIT_DELTA=0
```

## 2. 현재 read-only 기준선

| 기준 | exact 값 |
|---|---|
| active package | `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4`, `ACTIVE` |
| checkpoint | `docs/control/walksafe-project-continuation-checkpoint.json` |
| checkpoint SHA / bytes / schema | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` / `1329415` / `1.25.0` |
| transition tail | seq `39`, `c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a` |
| managed | `603`, path `e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1`, content `69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a` |
| v2.4 static SHA | `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07` |
| canonical | Gap/Backlog `r021/r021` |
| formal/device/gate/release | `0/279 PASS`, `0/0`, `0/5`, `NOT_ELIGIBLE` |
| discovery | discovered `132`, assigned `127`, orphan `5` |
| regression observation | suite A `450 PASS / 7 FAIL`; suite B `241 NOT_RUN` |

Stage C checkpoint commit 전 v2.4/seq39와 위 공식 상태는 그대로다.

## 3. exact 7 authority stages

| stage | exact authority token | 허용 |
|---|---|---|
| A | `PRE_P_IMMUTABLE_CANDIDATE_AND_EXTERNAL_ENV_BUILD_ONLY` | candidate root와 external env root만 build/seal; active/resolved write 0 |
| B | `PRE_P_SIBLING_AUTHORITY_RESOLUTION_AND_VALIDATION_ONLY` | sibling resolved root build, two-env full19/projection/review; candidate/env RO, active write 0 |
| C | `PRE_P_EXACT_RESOLVED_ATOMIC_APPLY_ONLY` | exact Stage-B root/review와 Stage-C receipt에 결속한 transaction |
| D | `POST_SEQ40_R007_SUCCESSOR_DESIGN_REVIEW_ONLY` | committed seq40/receipt 기반 add-only R007 successor design/review |
| E | `P_CANDIDATE_BUILD_REVIEW_ONLY` | reviewed R007 successor 기반 P seq40→41 candidate |
| F | `P_SIBLING_AUTHORITY_RESOLUTION_ONLY` | P apply envelope resolve/review |
| G | `P_EXACT_RESOLVED_ATOMIC_APPLY_ONLY` | exact reviewed P transaction |

현재 A~G 모두 `ABSENT_DENY_ALL`이다. R004 review findings `0/0/0` 전에는 사용자
질문도 build도 없다. 최종 질문은 Stage A authority 하나만 요청한다. A receipt는
B/C나 active/P 권한을 만들지 않는다.

exact roots:

```text
STAGE_A_CANDIDATE_ROOT=
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r004/
STAGE_A_EXTERNAL_ENV_ROOT=
  /home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-validation-r004/
STAGE_B_RESOLVED_ROOT=
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-authority-resolved-r004/
AUTHORITY_JOURNAL_ROOT=
  /home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-authority-r004/journal/
```

단일 canonical 문자열은 각각:

- `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/pre-p-validation-convergence-candidate-r004/`
- `/home/ddobagi/.local/share/hanium-dreamup/walksafe-pre-p-validation-r004/`
- `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/pre-p-validation-convergence-authority-resolved-r004/`
- `/home/ddobagi/.local/share/hanium-dreamup/walksafe-pre-p-authority-r004/journal/`

Stage A root와 Stage B root는 sibling이며 어느 쪽도 다른 쪽 안에 있지 않다.
Stage A seal 뒤 candidate root write count는 영구 `0`이다. Stage B는 candidate와
external env를 read-only input으로만 사용하고 resolved sibling만 쓴다.

## 4. output DAG와 freeze

```text
read-only Phase 0
  predecessor identities + allowed transforms only
        |
Stage A fresh receipt
        |
phase-0 contract build-01/02 equality + review
        |
parallel CORE
  A lock/external env
  C gateway exact5
  v2.4.1 checker/direct-test core
  D history/current validator core
        |
B single-runner FINAL
        |
D full19 contract FINAL
        |
after-control unresolved templates/static core
runtime synthetic pack build-01/02
apply template/builders
        |
Stage-A aggregate/root manifest + independent review + immutable seal
        |
Stage B fresh receipt
        |
sibling authority-resolved build-01/02 equality
successor regression-final build-01/02 equality + independent review
two env projections:
  BEFORE validate -> AFTER validate -> ordered19
  -> regression A -> regression B -> AFTER repeat
Stage-B root manifest + independent review + immutable seal
        |
Stage C fresh receipt
        |
exact atomic apply, checkpoint-last, postcheck, durable receipt
```

모든 edge는 upstream manifest/review SHA-256을 ordered input으로 final-pin한다.
upstream bytes가 바뀌면 downstream을 수정하지 않고 폐기한다.
B FINAL은 A/C/control-core에 의존한다. D FINAL은
A/B/C/control-core/D-core에 의존한다. Stage-B regression-final은 D FINAL까지
소비하며 B/D의 input이 아니다.

## 5. expected Stage-A paths

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r004/
    candidate-root-manifest.json
    exclusions-manifest.json
    phase-0-contracts/
      builders/build-predecessor-contracts.py
      builder-manifest.json
      build-01/regression-predecessor-identity.json
      build-01/regression-allowed-transformations.json
      build-01/runtime-input-predecessor-contract.json
      build-02/regression-predecessor-identity.json
      build-02/regression-allowed-transformations.json
      build-02/runtime-input-predecessor-contract.json
      candidate/regression-predecessor-identity.json
      candidate/regression-allowed-transformations.json
      candidate/runtime-input-predecessor-contract.json
      raw/source-inventory.json
      raw/missing-contract-negative.json
    lane-a/
      builders/build-tests-requirements-lock.py
      builders/provision-external-environments.py
      builder-manifest.json
      build-01/tests/requirements.lock
      build-02/tests/requirements.lock
      candidate/tests/requirements.lock
      raw/local-combined-clean-install.json
      raw/hosted-cpu-clean-install.json
      w5-lock-successor-manifest-r001.json
      w5-lock-successor-commands-r001.json
      w5-lock-successor-receipt-r001.json
    lane-c/
      builders/build-gateway-exact5-successor.py
      candidate/tests/walksafe_android_gateway_public_routes_successor_20260731_r004.py
      exact5-contract.json
      raw/positive.json
      raw/negative-config-router-openapi.json
    control-core/
      builders/build-v2-4-1-core.py
      candidate/scripts/check_walksafe_project_continuation_v2_4_1.py
      candidate/scripts/check_walksafe_goal_graph_v2_4_1.py
      candidate/tests/walksafe_project_continuation_v2_4_1_successor_20260731.py
      candidate/tests/walksafe_goal_graph_v2_4_1_successor_20260731.py
      candidate/tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py
      candidate/docs/control/goals/walksafe-completion-graph-v2-4-1/seq40-event-schema.json
      raw/core-positive-negative.json
    lane-d-core/
      builders/build-baseline-validator-core.py
      candidate/scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py
      candidate/scripts/check_walksafe_artifact_baseline_current_active_20260731.py
      candidate/scripts/check_walksafe_artifact_baseline_dual_control_20260731.py
      candidate/tests/walksafe_artifact_baseline_historical_successor_20260731_r004.py
      candidate/tests/walksafe_artifact_baseline_current_successor_20260731_r004.py
      raw/core-positive-negative.json
    lane-b-final/
      builders/build-single-runner-successor.py
      candidate/scripts/run_walksafe_test_layers_20260711.sh
      candidate/tests/walksafe_test_database_preflight_successor_20260731_r004.py
      candidate/docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-before-seq39-v2.4.json
      candidate/docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json
      raw/runner-positive.json
      raw/runner-negative-contract.json
    lane-d-final/
      builders/build-full19-successor.py
      full19-impact-matrix.json
      full19-successor-contract.json
      raw/template-validation.json
    after-control/
      builders/build-core.py
      builders/build-authority-resolved.py
      builder-manifest.json
      build-01/core-output-manifest.json
      build-02/core-output-manifest.json
      candidate/core-output-manifest.json
      candidate/static-control-core-v2.4.1.json
      templates/seq40-event.template.json
      templates/after-checkpoint.template.json
      templates/v2.4-supersession-record.template.json
      templates/v2.4-supersession-anchor.template.json
      templates/static-plan-manifest-v2.4.1.template.json
      templates/control-package-manifest-v2.4.1.template.json
      source/v2.4-active-checkpoint-raw.json
      source/source-checkpoint-manifest.json
      raw/build-core-01.json
      raw/build-core-02.json
    runtime-pack/
      builders/discover-runtime-inputs.py
      builders/build-runtime-pack.py
      builder-manifest.json
      raw/observed-read-exec-open-trace.json
      raw/unresolved-host-access.json
      local-combined/build-01/root/
      local-combined/build-01/recursive-content-manifest.json
      local-combined/build-02/root/
      local-combined/build-02/recursive-content-manifest.json
      local-combined/candidate/root/
      local-combined/candidate/recursive-content-manifest.json
      hosted-cpu/build-01/root/
      hosted-cpu/build-01/recursive-content-manifest.json
      hosted-cpu/build-02/root/
      hosted-cpu/build-02/recursive-content-manifest.json
      hosted-cpu/candidate/root/
      hosted-cpu/candidate/recursive-content-manifest.json
    apply/
      builders/resolve-envelope.py
      builders/execute-transaction.py
      builder-manifest.json
      templates/source-self-exclusions.json
      templates/exact-target-transition-set.template.json
      templates/application-transaction-plan.template.json
      templates/recovery-contract.template.json
      templates/postcheck-contract.template.json
      templates/receipt-schema.template.json
      templates/apply-envelope.template.json
      raw/template-validation.json
    aggregate/
      builders/build-aggregate.py
      builders/build-projection.py
      source-snapshot-before.json
      candidate-member-manifest.json
      template-aggregate-manifest.json
      raw/source-double-read.json
      raw/template-projection.json
    reviews/
      phase-0-independent-review-r001.md
      lane-a-independent-review-r001.md
      lane-c-independent-review-r001.md
      control-core-independent-review-r001.md
      lane-d-core-independent-review-r001.md
      lane-b-final-independent-review-r001.md
      lane-d-final-independent-review-r001.md
      after-control-independent-review-r001.md
      runtime-pack-independent-review-r001.md
      apply-template-independent-review-r001.md
      aggregate-independent-review-r001.md
```

Stage A candidate root의 마지막 build write는 `candidate-root-manifest.json`이다.
manifest는 자기 path만 hash domain에서 제외한다고 명시하고 나머지 exact
recursive member path/type/mode/size/hash/link target을 포함한다. 그 뒤 root
regular는 `0444/0555`, directory는 `0555`로 seal한다. independent root review:

`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R004-stage-a-candidate-independent-review-r001.md`

는 sealed root 밖 sibling에 작성한다. Stage B receipt는 root manifest와 이
review의 actual hash/bytes를 모두 결속한다.

## 6. M1 — predecessor identity와 successor contract

Phase 0는 current predecessor를 관측만 하며 successor argv/path를 만들지 않는다.

`regression-predecessor-identity.json`은 suite A/B의:

- ordered argv JSON token array, cwd, interpreter/environment role
- ordered test path/nodeid와 per-file SHA-256
- env allow/unset, timeout seconds, output byte cap, raw destination
- discovery nodeid array/digest와 predecessor result observation

을 identity-only로 동결한다. suite A의 `450 PASS / 7 FAIL`과 suite B의
`241 intended / NOT_RUN`은 lineage observation일 뿐 successor acceptance나
고정 count가 아니다. Phase-0 artifact는 `executable=false`,
`acceptance=false`, successor hash는 empty이며 typed `future_role_id`만 가진다.

`regression-allowed-transformations.json`은 다음 transform만 허용한다.

1. A lock과 exact2 environment/pack binding
2. single runner explicit root/checkpoint/selector/routing argv
3. history-only exact6:
   old preflight, Android boundary, baseline materialization, old v2.4
   continuation, old v2.4 Goal, seq39 Goal test
4. B/C current direct exact2와 v2.4.1/seq40 direct exact3
5. D historical/current/dual helpers와 full19 changed slots

ignore/deselect 추가, rename/delete, root/test-path 축소, wildcard/discovery-only
선택과 위 exact transform 밖 delta는 금지다. history exact6은 bytes/discovery를
보존하지만 successor A/B/full19 current argv count가 `0`이어야 한다.

Stage-A A/C/control-core/B-final/D-core/D-final과 resolved v2.4.1 exact bytes가
모두 freeze된 Stage B에서만 `regression-final` builder가 allowed transform을
적용한다. ordered input은 Phase0/reviews 및 각 lane exact manifest path/hash이며
glob/discovery가 아니다. 두 empty roots는 distinct inode이고 output bytes가
같아야 한다. independent review가 exact diff를 재계산한 뒤에만 두 environment
projection에서 regression A→B를 실행한다.

resolved suite A `CURRENT_PRODUCT_PYTHON_FIRST`는 B-final runner의 first pytest
argv와 byte-identical하고 current UNIT discovery + B/C direct exact2, 해당
projection env/pack과 A lock을 사용한다. suite B
`CURRENT_V2_4_1_CONTROL`은 D-final full19 #3/#4/#17/#18 exact ordered
subcommands다: #3 D triple, #4 C, #17 v2.4.1 direct3, #18 trace+B+D
history/current2. successor test count는 collected nodeid array/digest에서
계산하며 fixed `241`을 주장하지 않는다.

## 7. Lane A와 sealed external environments

active candidate는 `tests/requirements.lock` exact1이다. 동일 frozen
CPython `3.12.13`, pip-tools `7.6.0`, input/index/cache policy로 build-01/02를
생성해 byte equality를 검증한다. local-combined와 hosted-cpu 모두 Pillow
`12.3.0`, pytest `8.4.2`, hash install rc0와 `pip check` rc0다.

external root:

```text
/home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-validation-r004/
    environment-root-manifest.json
    lease.json
    local-combined/
      recursive-content-manifest.json
    hosted-cpu/
      recursive-content-manifest.json
```

root는 Stage A 시작 시 absent/non-symlink여야 하고 `mkdirat` NOREPLACE한다.
각 env는 `.tmp.<stage-a-nonce>` clean build → double recursive manifest →
`renameat2(RENAME_NOREPLACE)` publish한다. path/realpath/dev/inode/uid/gid/mode,
regular SHA/size, package RECORD digest, relative symlink target/resolution과
recursive path/content digest를 기록한다. absolute/dangling/escaping/cyclic
symlink와 hardlink는 금지, regular `nlink=1`이다.

publish 뒤 executable `0555`, regular `0444`, directory `0555`로 seal한다.
`lease.json`은 A receipt, owner/custodian, created/expiry, env manifests와
retention state를 결속한다. Stage B resolve pre/post, 각 full19 command
pre/post와 Stage C transaction/postcheck가 root/inode/mode/content/lease를
double revalidate한다. expiry/replacement/drift는 write 0이다. cleanup,
retention 연장, reprovision은 별도 fresh authority 없이는 금지다. current stale
venv/default/global/fallback interpreter는 금지다.

## 8. B3 — single runner와 exact two states

R004 runner는 repository root나 checkpoint를 추정하지 않는다. 모든 invocation:

```text
bash /work/walksafe/scripts/run_walksafe_test_layers_20260711.sh
  --layer <validate|unit|all>
  --root /work/walksafe
  --checkpoint <root-relative-checkpoint-json>
  --control-selector <BEFORE_SEQ39_V24|AFTER_SEQ40_V241>
  --routing-manifest <exact-root-relative-final-manifest-json>
```

default와 environment inference는 없다. option missing/duplicate/partial,
unknown selector, path escape, root/self mismatch, checkpoint/manifest
missing/extra/type/hash tamper는 rc2다. root, root-relative checkpoint와 manifest의
containment/type/hash를 child spawn 전에 검증한다.

runner는 final manifest bytes나 미래 hash를 embed하지 않는다. authority-free
`embedded-routing-core` exact2만 embed한다. 각 core는 selector, logical
path/nodeid roles, final manifest expected root-relative path, world predicate와
core digest를 가진다. final manifest는 embedded core digest를 결속하고,
checkpoint/static manifest가 AFTER final manifest hash를 단방향 결속한다.
runner로 향하는 final-hash 역 edge는 금지다.

exact allowed states:

| state | checkpoint/control | manifest |
|---|---|---|
| `BEFORE_SEQ39_V24` | seq39 raw checkpoint hash/tail, v2.4 `ACTIVE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-before-seq39-v2.4.json` |
| `AFTER_SEQ40_V241` | seq40 compound event, v2.4 `SUPERSEDED`, v2.4.1 `ACTIVE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json` |

allowed-state count는 정확히 2다. runner는 checkpoint schema/package/sequence,
event type/hash/tail, v2.4/v2.4.1 status와 routing digest를 먼저 검증하고 나서만
pytest를 실행한다.

negative matrix는 missing/duplicate/partial flag, unknown/third selector,
root/self mismatch, checkpoint path escape, partial seq40, wrong package/sequence/
event/tail, missing/extra/tampered manifest와 embedded-core/final mismatch를
각각 가진다. 모든 negative는 rc2, `child_exec_count=0`이다.

AFTER routing의 역할/수/consumer는 다음 단일 authoritative table이다.

| exact path | role | current consumer |
|---|---|---|
| `tests/test_walksafe_test_database_preflight.py` | `HISTORICAL_TARGETED_ONLY` 1/6 | current count 0 |
| `tests/test_walksafe_android_product_boundary.py` | `HISTORICAL_TARGETED_ONLY` 2/6 | current count 0 |
| `tests/test_walksafe_artifact_baseline_materialization_20260722.py` | `HISTORICAL_TARGETED_ONLY` 3/6 | current count 0 |
| `tests/test_walksafe_project_continuation_v2_4.py` | `HISTORICAL_TARGETED_ONLY` 4/6 | current count 0 |
| `tests/test_walksafe_goal_graph_v2_4.py` | `HISTORICAL_TARGETED_ONLY` 5/6 | current count 0 |
| `tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py` | `HISTORICAL_TARGETED_ONLY` 6/6 | current count 0 |
| `tests/walksafe_test_database_preflight_successor_20260731_r004.py` | `CURRENT_DIRECT` 1/5 | runner unit/all; #18 |
| `tests/walksafe_android_gateway_public_routes_successor_20260731_r004.py` | `CURRENT_DIRECT` 2/5 | runner unit/all; #4 |
| `tests/walksafe_project_continuation_v2_4_1_successor_20260731.py` | `CURRENT_DIRECT` 3/5 | #17 |
| `tests/walksafe_goal_graph_v2_4_1_successor_20260731.py` | `CURRENT_DIRECT` 4/5 | #17 |
| `tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py` | `CURRENT_DIRECT` 5/5 | #17 |
| `tests/walksafe_artifact_baseline_historical_successor_20260731_r004.py` | `FULL19_D_DIRECT` 1/2 | #3/#18, runner 밖 |
| `tests/walksafe_artifact_baseline_current_successor_20260731_r004.py` | `FULL19_D_DIRECT` 2/2 | #3/#18, runner 밖 |

history exact6 bytes와 discovery는 보존한다. 전체 discovery는 `132`, assigned
`132`, unassigned/duplicate `0/0`이다.

## 9. B2 — Stage-B two-env ordered full19 actual validation

full19 matrix는 R003의 exact impact를 유지한다.

| # | ID | impact | exact contract |
|---:|---|---|---|
| 1 | `CONTINUATION` | CHANGED | v2.4.1 activation/supersession/seq40 |
| 2 | `GOAL_GRAPH` | CHANGED | v2.4.1 package/control state |
| 3 | `BASELINE_MATERIALIZATION` | CHANGED | D historical/current/dual |
| 4 | `ANDROID_GATEWAY_BOUNDARY` | CHANGED | C current direct |
| 5 | `NODE_TOOLCHAIN_PRE` | UNAFFECTED | predecessor bytes proof; synthetic pack |
| 6 | `GATEWAY_TYPECHECK` | UNAFFECTED | predecessor bytes proof; synthetic pack |
| 7 | `GATEWAY_TEST` | UNAFFECTED | predecessor bytes proof; synthetic pack |
| 8 | `GATEWAY_BUILD` | UNAFFECTED | predecessor bytes proof; synthetic pack |
| 9 | `WEB_TEST` | UNAFFECTED | predecessor bytes proof; synthetic pack |
| 10 | `WEB_LINT` | UNAFFECTED | exact no-LF hash; synthetic pack |
| 11 | `WEB_TYPECHECK` | UNAFFECTED | predecessor bytes proof; synthetic pack |
| 12 | `WEB_BUILD` | UNAFFECTED | predecessor bytes proof; synthetic pack |
| 13 | `NODE_TOOLCHAIN_POST` | UNAFFECTED | predecessor bytes proof; synthetic pack |
| 14 | `ANDROID_UNIT_ASSEMBLE_LINT` | UNAFFECTED | predecessor bytes proof; synthetic pack |
| 15 | `TEST_LAYER_REGISTRY_VALIDATE` | CHANGED | B single runner explicit selector |
| 16 | `FIELD_AND_RELEASE_PYTEST` | CHANGED | exact predecessor paths, current env/pack |
| 17 | `GOAL_CONTROL_PYTEST` | CHANGED | old v2.4 current0; v2.4.1 direct exact3 |
| 18 | `CONTROL_AND_TRACE_PYTEST` | CHANGED | legacy baseline removed; B+D direct; trace9+deselect6 carry |
| 19 | `REPOSITORY_STATE` | CHANGED | seq40 event/checkpoint/target state |

ordered19 digest는 A/B/C/control-core/D-core freeze 뒤 D FINAL에서 딱 한 번
계산한다. Stage-B regression-final은 이 frozen D FINAL을 input으로 소비할 뿐
D FINAL의 input이 아니다. index10 no-LF SHA는
`0293d54ea860df64ad8bc21214902488a4062f3c0809ea9c7ca69be35fbfa35b`다.
5~14의 command bytes/argv/cwd/env/input/executable equality가 다르면
`UNAFFECTED` 재분류 금지, downstream review를 다시 한다.

Stage B의 initial challenge root는
`pre-p-validation-convergence-authority-resolved-r004/stage-b-pre-p-r004-001/`다.
그 아래 local-combined와 hosted-cpu projection exact2가 각각 자기 sealed
environment와 자기 synthetic pack으로 BEFORE validate → AFTER overlay/validate
→ ordered19 → regression A → regression B → AFTER repeat를 실행한다.

```text
pre-p-validation-convergence-authority-resolved-r004/
  stage-b-pre-p-r004-001/
    projections/
    local-combined/
      before-manifest.json
      after-manifest.json
      execution-envelope.json
      runtime-pack-binding.json
      full19/raw/001/{intent.json,stdout.bin,stderr.bin,result.json}
      ...
      full19/raw/019/{intent.json,stdout.bin,stderr.bin,result.json}
      full19/ordered19-result.json
      full19/direct-exact3-result.json
      regression-a-result.json
      regression-b-result.json
      after-repeat-result.json
    hosted-cpu/
      before-manifest.json
      after-manifest.json
      execution-envelope.json
      runtime-pack-binding.json
      full19/raw/001/{intent.json,stdout.bin,stderr.bin,result.json}
      ...
      full19/raw/019/{intent.json,stdout.bin,stderr.bin,result.json}
      full19/ordered19-result.json
      full19/direct-exact3-result.json
      regression-a-result.json
      regression-b-result.json
      after-repeat-result.json
```

literal full19 slot directory set은
`[001,002,003,004,005,006,007,008,009,010,011,012,013,014,015,016,017,018,019]`
이고 각 directory member set은 정확히
`[intent.json,stdout.bin,stderr.bin,result.json]`이다. archive/runtime argv는
이 배열을 그대로 열거하며 count-only 또는 glob을 쓰지 않는다.

각 `intent.json`은 exact ID/order/argv/cwd/env/interpreter/pack/input digest,
timeout/output cap을 가진다. `result.json`은 exec boolean, monotonic start/end,
rc, stdout/stderr path/hash/bytes, `PASS|FAIL|NOT_RUN`과 predecessor receipt를
가진다. upstream failure/TTL expiry면 남은 slot도 exact19 구조를 유지하고
`exec=false`, zero-byte stdout/stderr, exact `NOT_RUN` result를 쓴다. projection
status는 `INCOMPLETE`이며 Stage C가 금지된다.

성공은 두 projection 모두 `19/19 PASS`, `NOT_RUN=0`, regression A/B PASS와
AFTER repeat digest 일치다. 양쪽 #17은 v2.4.1 direct exact3의 explicit files,
collected nodeids와 per-file digest exact3 및 rc0를 별도 result로 증명한다.
#5~14는 해당 environment synthetic pack 밖 host open/read/exec 0이어야 한다.

## 10. synthetic runtime-pack과 projection

observed read/exec/open trace는 candidate file discovery에만 사용하며 allowlist가
아니다. builder는 exact executable, shebang interpreter, stdlib, ELF loader와
shared-library transitive closure, required locale/timezone/NSS/CA/config만
synthetic tree에 byte-copy한다. member path/type/mode/hash/link target을 recursive
manifest에 기록하고 regular `nlink=1`로 만든다.

environment별 `{local-combined,hosted-cpu}/{build-01,build-02,candidate}` exact
closure를 만든다. 각 env의 두 independent pack build path/content/type/mode/
target/trace digest가 같아야 한다. 두 env pack은 recursive manifest까지 exact
동일할 때만 content reuse를 주장할 수 있다. unresolved host access,
device/FIFO/socket, absolute/escaping link는 FAIL이다.

final bwrap은 synthetic pack subtrees, exact external env, active RO와 work RW만
bind한다.

```text
/usr/bin/bwrap --die-with-parent --new-session --unshare-all
  --proc /proc --dev /dev --tmpfs /tmp --dir /work
  --ro-bind <PACK>/usr /usr
  --ro-bind <PACK>/etc /etc
  --ro-bind <PACK>/lib /lib
  --ro-bind <PACK>/lib64 /lib64
  --symlink usr/bin /bin
  --ro-bind <SEALED_ENV_ROOT> /env
  --ro-bind <ACTIVE_ROOT> /source
  --bind <OUTSIDE_REPO_MKDTEMP> /work/walksafe
  --chdir /work/walksafe --clearenv
  --setenv HOME /tmp --setenv PYTHONDONTWRITEBYTECODE 1
  -- <exact typed JSON argv>
```

host `/usr`, `/etc`, `/lib` broad bind나 fallback은 없다. source는 `.git` 포함
reflink 후 distinct inode 검증, 불가하면 byte-copy한다. hardlink 금지,
staged regular `nlink=1`, final-relative overlay만 허용한다. checker/test의
`__file__`, cwd/import root는 `/work/walksafe`다.

Stage B의 environment별 fresh projection이 BEFORE seq39 validate → AFTER
seq40 overlay/validate → ordered19 → regression A→B → AFTER repeat를 수행한다.
local-combined와 hosted-cpu exact2 모두 PASS해야 하며 어느 환경도 다른 환경의
interpreter/pack으로 fallback하지 않는다. active pre/post manifest와 git status도
같다.

## 11. B4 — A/B/C authority receipts, TTL과 custody

execution root 밖 immutable authority journal의 fixed nominal paths:

```text
/home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-authority-r004/journal/
    000001-stage-a-pre-p-r004-001/authority-receipt.json
    000002-stage-b-pre-p-r004-001/authority-receipt.json
    000003-stage-c-pre-p-r004-001/authority-receipt.json
```

issuer만 temp file → NOREPLACE rename으로 쓰고 file+parent를 fsync한다. receipt는
regular `0444`, non-symlink, `nlink=1`이며 overwrite/replacement 금지다. bytes는
RFC 8785 JCS UTF-8, duplicate key 없음, terminal LF 없음인 exact:

```json
{"payload":{...},"signature":"<ed25519-base64url-no-padding>"}
```

signature domain은 `WS-PRE-P-R004-AUTHORITY-V1 || NUL ||
JCS(payload)`다. validator는 payload의 verifier key ID/fingerprint와 Ed25519
signature를 확인한다. receipt SHA-256/bytes는 다음 journal receipt,
candidate/resolved manifest, event/envelope와 application receipt가 physical
path와 함께 결속한다. payload schema required fields:

```text
schema_version, stage, scope, sequence, transaction_id
prior_receipt_path/hash/bytes, expected_journal_head
A0, R0, delegation_depth=0, revocation_state
repo/source checkpoint path/hash/bytes/tail
plan/review and candidate/resolved manifest/review bindings
allowed roots/operations and denied roots
required ABSENT tombstones
request path/hash, challenge path/hash, raw response path/hash
nonce, issued_at, not_before, expires_at, ttl_seconds
lease_slice_seconds, hard_deadline, one_use=true
issuer/verifier key ID/fingerprint
custody issuer->journal-writer->reviewer identities/times
```

nominal TTL은 A/B/C 각각 `900/900/300`초, lease slice는 `900`초, hard
deadline은 A/B/C 각각 `21600/3600/1800`초다. same transaction/scope의 signed
NOREPLACE renewal만 허용하고 hard deadline을 연장하지 않는다. 첫 B renewal
reserved path는
`000002-stage-b-pre-p-r004-001/renewals/000001/authority-receipt.json`이다.
revoked/expired/used receipt와 expected-head CAS mismatch는 write 0이다.

Stage A receipt는 exact candidate+external root만 쓸 수 있다. candidate
root manifest/review 뒤 success/incident가 nonce/lease를 닫는다. Stage B
receipt는 physical A receipt, sealed A manifest/review, environment
manifests/lease와 initial exact root
`pre-p-validation-convergence-authority-resolved-r004/stage-b-pre-p-r004-001/`
ABSENT tombstone을 결속한다. Stage B write allowlist는 그 challenge root
하나뿐이다. A root에 mkdir/temp/raw 포함 1 byte write가 관측되면 STOP한다.

Stage-B root expected:

```text
pre-p-validation-convergence-authority-resolved-r004/
  stage-b-pre-p-r004-001/
    resolved-root-manifest.json
    authority-input/stage-b-receipt-custody-binding.json
    build-01/{after-control,apply}/
    build-02/{after-control,apply}/
    candidate/{after-control,apply}/
    regression-final/
      builders/build-successor-regression-contract.py
      builder-manifest.json
      build-01/regression-contract.json
      build-02/regression-contract.json
      candidate/regression-contract.json
      raw/build-01.json
      raw/build-02.json
      independent-review-r001.md
    projections/local-combined/
    projections/hosted-cpu/
```

build-01/02 recursive bytes가 같아야 candidate에 NOREPLACE copy한다. final
`resolved-root-manifest.json` 뒤 root를 read-only seal한다. Stage-B review:

`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R004-stage-b-resolved-independent-review-r001.md`

는 resolved root 밖에 작성한다. resolver build-01/02 pre/post, regression-final
build/review pre/post, 각 projection/full19/regression command pre/post에 latest
B receipt TTL과 env lease를 검증한다. Stage-C issuance 직전/직후에도 physical
B receipt, latest same-scope renewal과 env lease가 유효해야 한다.

Stage C receipt는 physical A/B receipt chain, sealed resolved manifest/review,
exact target set/envelope, two-env ordered19/regression/projection digests와 source
CAS를 결속하며 Stage-B independent review PASS 뒤에만 발행한다. transaction의
prewrite/pre-CAS/pre-checkpoint/postcheck/pre-receipt에서 C receipt와 environment
lease를 검증한다.

expiry, nonce/challenge/expected-head mismatch, revocation 또는 bytes/inode/mode/
content drift는 write 0, prior receipt reuse 0, fresh challenge → re-resolve →
two-build → independent review를 요구한다. partial write는 fresh
recovery-only capability가 필요하다. checkpoint commit 뒤에는 target write
0이며 recovery capability도 exact6 postcheck/receipt-only다.

## 12. activation resolution

Stage A는 authority-free source archive, schema, checker/test, static core와
unresolved templates만 review한다. final seq40 event/checkpoint/target-set/envelope
bytes를 만들지 않는다.

Stage B resolver는 Stage-B receipt/challenge를 input으로 두 empty roots에서:

```text
v2.4 source active checkpoint raw archive
v2.4 supersession record + anchor
seq40 compound event:
  V2_4_1_PACKAGE_PREPARED
  V2_4_SUPERSEDED_AND_V2_4_1_ACTIVATED
v2.4 ACTIVE -> SUPERSEDED
v2.4.1 PREPARED -> ACTIVE
after checkpoint
target transition set
apply envelope
```

를 byte-identical 생성한다. event semantic bytes는 Stage-A
transition-spec/core와 S0만 참조하고 Stage-B receipt, final checkpoint,
target-set/envelope hash를 포함하지 않는다. Stage-B authority physical chain은
X1 envelope와 A_C receipt가 결속한다.

resolved package tests는 wrong/missing authority를 envelope gate에서 거부하고,
event tests는 duplicate/reordered subevent, partial status, wrong
package/archive/seq39 tail/sequence/event/anchor와 tamper를 rc2로 거부한다.
checkpoint는 managed
count/path/content, session_handoff changed/source snapshot, canonical r021과
product/formal/device/gate/release unchanged를 닫는다. future application receipt
hash는 넣지 않는다.

## 13. M2 — exact hash domains와 cycle rejection

canonical hash primitive:

```text
H(domain, value) =
  SHA256(ASCII(domain) || NUL ||
         UTF8(sorted-key compact JSON(value), duplicate-key-free) || LF)
```

각 object에는 자기 SHA field가 없다. SHA는 immediate consumer manifest에만
기록한다. exact topological domains:

| order | ID/domain | 포함 | 금지 edge |
|---:|---|---|---|
| 0 | `S0/R004_SOURCE_SNAPSHOT_V1` | active source + finalized non-self target inputs after exact self-exclusions; exclusion manifest hash는 parameter | E0 이후 output/self |
| 1 | `E0/R004_SEQ40_EVENT_V1` | S0, Stage-A transition-spec/core hash, seq39 tail와 compound semantic transition | final checkpoint, target-set, envelope, receipt/self |
| 2 | `C0/R004_AFTER_CHECKPOINT_V1` | S0, E0와 self-excluded managed/session_handoff/control hashes | target-set, envelope, review/receipt/self |
| 3 | `T1/R004_TARGET_SET_V1` | S0/E0/C0와 ordered exact targets의 before/after hashes | envelope, review/receipt/self |
| 4 | `X1/R004_APPLY_ENVELOPE_V1` | S0/E0/C0/T1, transaction/recovery/exact6 postcheck schema | review/receipt/self/future receipt |
| 5 | `V1/R004_RESOLVED_REVIEW_V1` | X1과 sealed resolved root manifest 및 physical Stage-B review hash | Stage-C receipt/apply receipt/self |
| 6 | `A_C/R004_STAGE_C_AUTHORITY_V1` | physical A/B chain, V1, source CAS, exact scope/TTL/lease | apply/postreceipt/self |
| 7 | `P1/R004_APPLY_POSTRECEIPT_V1` | A_C, exact committed prefix/checkpoint, exact6 postcheck와 durable application receipt | self/future |

`source-self-exclusions.json`은 authority/candidate/resolved roots, all raw/temp,
event/checkpoint/target-set/envelope/application receipt를 exact final-relative
path로 열거한다.
그 manifest는 자신의 path/hash domain membership을 명시적으로 제외하므로
source snapshot self-reference가 없다.

E0는 Stage-A transition specification/core만 참조하고 T1/X1/final checkpoint를
참조하지 않는다. C0만 E0를 참조하고, T1은 S0/E0/C0를, X1은 T1을
단방향 참조한다. path/order digest와 final content digest는 T1에서 별도
필드로 계산한다.

resolver는 edge list를 typed `(consumer, dependency)` 배열로 만들고:

- dependency order가 consumer보다 작아야 함
- self edge, reverse edge, duplicate/conflicting edge 금지
- Kahn topological sort가 exact `[S0,E0,C0,T1,X1,V1,A_C,P1]`
- Tarjan SCC size1 self-loop 또는 size>1이면 FAIL
- undeclared hash field와 consumer가 dependency bytes에 나타나는 역참조 금지

를 build-01/02와 Stage-C preflight에서 독립 검사한다. cycle detector negative는
self, reverse/future, event↔checkpoint, checkpoint↔target-set,
target-set↔envelope와 review/authority 역 edge를 모두 rc2로 거부한다.

## 14. Stage C transaction/postcheck/receipt

Stage C `execute-transaction.py`는 다음 gate마다 receipt/signature/custody,
candidate/resolved/env/pack manifests, TTL/lease와 source CAS를 double-check한다.

```text
T0 pre-transaction
T1 before each target CAS/NOREPLACE
T2 pre-checkpoint-CAS
T3 checkpoint committed
T4 hosted-cpu exact6 postcheck
T5 before durable application receipt
T6 receipt + parent fsync complete
```

promotion order:

1. exact ordered CAS/NOREPLACE targets
2. each file fsync, parent fsync, durable exact-prefix progress
3. checkpoint CAS last
4. Stage-C hosted-cpu exact6 postcheck
5. application receipt NOREPLACE and parent fsync

Stage C는 full19/regression을 재실행하지 않는다. sealed Stage-B two-env
full19/regression raw/result digests를 binding으로 재검증하고, hosted-cpu exact
environment/pack으로 다음 exact6만 실행한다.

| # | exact postcheck |
|---:|---|
| 1 | live resolved target universe와 두 routing manifests의 physical hash/type/mode/nlink가 Stage-B AFTER와 동일 |
| 2 | seq40/v2.4.1 activation, v2.4 supersession, checkpoint seal |
| 3 | AFTER continuation Quick |
| 4 | AFTER Goal Quick |
| 5 | runner `validate` with `AFTER_SEQ40_V241` explicit selector; discovery/assigned/unassigned `132/132/0`, current direct exact5 |
| 6 | #17 v2.4.1 direct exact3 + #19 repository state/event snapshot |

checkpoint 전 crash는 same valid C receipt에서 exact prefix revalidate 후 suffix만
resume한다. non-prefix/drift/expiry는 write 0과 fresh challenge다. checkpoint
뒤에는 target write/rollback이 영구 금지되며 fresh recovery receipt는
exact6 postcheck/receipt-only다. exact6 failure는
`POSTCOMMIT_RECOVERY_REQUIRED`; rollback 금지다. exact6 모두 PASS 뒤에만
receipt를 쓴다. checkpoint는 durable receipt-required state만 가지며 future
receipt hash가 없다.

application receipt exact path:

`docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001/application-receipt.json`

receipt schema는 transaction ID, physical Stage A/B/C receipt chain hashes,
all committed target hashes/order, checkpoint/event/envelope hashes, T0~T6,
Stage-B two-env full19/regression digests, hosted-cpu exact6 결과와 final active
identity를 기록한다.

## 15. exact active target universe

| mode | exact final-relative target |
|---|---|
| `CAS_REPLACE` | `tests/requirements.lock` |
| `CAS_REPLACE` | `scripts/run_walksafe_test_layers_20260711.sh` |
| `NOREPLACE` | `tests/walksafe_test_database_preflight_successor_20260731_r004.py` |
| `NOREPLACE` | `tests/walksafe_android_gateway_public_routes_successor_20260731_r004.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_current_active_20260731.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_dual_control_20260731.py` |
| `NOREPLACE` | `tests/walksafe_artifact_baseline_historical_successor_20260731_r004.py` |
| `NOREPLACE` | `tests/walksafe_artifact_baseline_current_successor_20260731_r004.py` |
| `NOREPLACE` | `scripts/check_walksafe_project_continuation_v2_4_1.py` |
| `NOREPLACE` | `scripts/check_walksafe_goal_graph_v2_4_1.py` |
| `NOREPLACE` | `tests/walksafe_project_continuation_v2_4_1_successor_20260731.py` |
| `NOREPLACE` | `tests/walksafe_goal_graph_v2_4_1_successor_20260731.py` |
| `NOREPLACE` | `tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/seq40-event-schema.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-before-seq39-v2.4.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/superseded-v2.4.0-active-checkpoint.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/v2.4-supersession-record.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/v2.4-supersession-anchor.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/transition-event-seq40.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/static-plan-manifest-v2.4.1.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/control-package-manifest-v2.4.1.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/README.md` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/full19-successor-contract.json` |
| `CHECKPOINT_CAS_LAST` | `docs/control/walksafe-project-continuation-checkpoint.json` |

표 밖 target/glob/implicit member는 금지다. application receipt는 postcheck
output이며 pre-checkpoint target count에 포함하지 않는다.

## 16. stop conditions

- R004 independent review nonzero 또는 Stage A exact receipt absent
- R003/review binding drift
- candidate/resolved root가 nested, preexist, symlink 또는 same inode
- Stage-A seal 뒤 candidate write, Stage-B resolved root 밖 write
- candidate/resolved manifest 또는 distinct review 누락
- Phase0가 successor identity를 생성하거나 allowed transform 밖 delta
- successor regression contract가 lane freeze 전 생성/사후 path 추가
- env preexist/drift/expiry/current stale fallback
- runner explicit root/checkpoint/manifest 누락 또는 allowed-state count≠2
- runner negative matrix 중 rc2/child-exec0 불일치
- discovery/assigned `132/132`, current direct exact5 불일치
- old v2.4 current test가 AFTER/full19 #17에 재유입
- local-combined/hosted-cpu ordered exact19 중 rc nonzero 또는 NOT_RUN
- 각 env #17 direct exact3 중 하나라도 non-rc0
- #5~14 synthetic pack 밖 host access 또는 unchanged byte proof 누락
- Stage-C hosted-cpu exact6 중 하나라도 non-PASS
- A/B/C journal receipt JCS payload/signature/custody/path/hash/TTL/lease 불일치
- B resolve pre/post TTL/lease expiry 또는 C issuance before B review
- unmanifested broad host bind, pack two-build/projection digest 불일치
- final seq40 bytes를 Stage A에서 만들거나 Stage B build-01/02 불일치
- hash domain order/edge undeclared, self/reverse edge, cycle detector non-PASS
- source snapshot self-exclusion 누락 또는 future receipt hash
- pre-CAS/checkpoint/postcheck/receipt gate 누락
- checkpoint-before-target, non-prefix resume, rollback
- R007 수정/재사용, P seq39→40, P17 build
- canonical/product/formal/device/gate/release/credit delta

## 17. R003 4/2 remediation matrix

| exact R003 finding | R004 closure |
|---|---|
| `PRE-P-R003-BLOCKING-001` frozen namespace mutation | §3 sibling challenge root, §5 Stage-A seal/review, §11 A-root write0/B manifest/review |
| `PRE-P-R003-BLOCKING-002` resolved full19/direct execution absent | §9 projection raw19×2, #17 direct3 rc0, pack5~14; §14 exact6 risk-based postcheck |
| `PRE-P-R003-BLOCKING-003` runner selector undefined | §8 explicit root/checkpoint/selector argv, embedded core/final manifests, allowed exact2 |
| `PRE-P-R003-BLOCKING-004` receipt/lease validity | §11 external journal JCS signature/custody/TTL, §14 T0~T6 |
| `PRE-P-R003-MAJOR-001` Phase0 future suite final-pin | §6 predecessor-only Phase0, Stage-B regression-final two-build/review |
| `PRE-P-R003-MAJOR-002` seq40 hash DAG undefined | §13 S0→E0→C0→T1→X1→V1→A_C→P1 |

## 18. self-check와 handoff

```text
R004 regular && !symlink && nlink==1 && terminal_LF && NUL_free
R003 SHA == 909481f68fe030acacaa5b379fed42258563c3f0ceefd0ec02421a28cb784ee7
R003 review SHA == 41fae31a032e56e99d3f575ff9d68dbaaf3de85b66d8de7ce9cc349229253746
status == NON_EFFECTIVE_PLAN_ONLY
authority A..G exact7 && current all ABSENT_DENY_ALL
candidate root sibling resolved root && neither nested
Stage-A post-seal write count == 0
candidate/resolved manifest + independent review each exact1
Phase0 predecessor-only; successor contract after lane freeze
single runner explicit root/checkpoint/manifest
allowed states == BEFORE seq39/v2.4 + AFTER seq40/v2.4.1
negative runner matrix rc2/child_exec_count0
two environments × ordered exact19 actual
index17 direct exact3 rc0 per environment
indices5..14 synthetic pack only
Stage-C postcheck == hosted-cpu exact6
A/B/C journal receipt JCS payload+signature/custody exact paths
hash topology == S0 -> E0 -> C0 -> T1 -> X1 -> V1 -> A_C -> P1
self/reverse/cycle count == 0
checkpoint CAS last && future receipt hash count == 0
R007 write/reuse count == 0
P17_BUILD_DEFERRED == true
official credit delta == 0
```

R004 완료는 plan-only다.

```text
STAGE_A_AUTHORITY=ABSENT_DENY_ALL
STAGE_B_AUTHORITY=ABSENT_DENY_ALL
STAGE_C_AUTHORITY=ABSENT_DENY_ALL
STAGE_D_AUTHORITY=ABSENT_DENY_ALL
STAGE_E_AUTHORITY=ABSENT_DENY_ALL
STAGE_F_AUTHORITY=ABSENT_DENY_ALL
STAGE_G_AUTHORITY=ABSENT_DENY_ALL
STAGE_A_CANDIDATE_BUILT=false
STAGE_B_RESOLVED=false
PRE_P_APPLIED=false
V2_4_1_ACTIVE=false
R007_SUCCESSOR_BUILT=false
P_CANDIDATE_BUILT=false
P_APPLIED=false
```

R004 independent review가 findings `0/0/0`으로 닫힌 최종 handoff에서만 Stage A
authority를 사용자에게 질문한다.
