# PRE-P Validation Convergence Design/Build Plan R003

## 1. 문서 통제

| 항목 | exact 값 |
|---|---|
| document_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R003` |
| status | `NON_EFFECTIVE_PLAN_ONLY` |
| 작성일 | `2026-07-31` |
| R001 plan SHA-256 | `b25e9bd0a71e86d2bd0b8176369053ed4db5896cb3055e17591b19135ec61962` |
| R001 review SHA-256 | `95c485bfae33f3a8d0e0a783ef9d0c602fc72d779597d7144ca87530df0e4cd0` |
| R001 verdict | `REJECTED_PLAN_DO_NOT_EXECUTE; 5/2/0` |
| R002 plan SHA-256 | `92736c9912903435120fc9087174b3119972ad2c58e1144a335afa1cd2306576` |
| R002 bytes | `33011` |
| R002 verdict | `REJECTED_PLAN_DO_NOT_EXECUTE; 4/2/0` |
| R002 review path | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R002-independent-review-r001.md` |
| R002 review SHA-256 | `194fa4a30fb5f70af508a99045280d4f05ad287cc6eef747136d443f5d55c504` |
| R002 review bytes / lines / type | `19690` / `372` / `regular file, nlink=1` |
| stale R007 SHA-256 | `2c0eb19da8b9a2df77cb43b2ffd169583591f0a23150d9ef5eff00cf39327610` |
| P17 | `P17_BUILD_DEFERRED` |

R001, R002, 두 plan의 review, 기존 R007과 daylog는 불변이다. 위 R002 review
binding은 physical file을 직접 관측한 값이며 review target의 R002 SHA도
`92736c9912903435120fc9087174b3119972ad2c58e1144a335afa1cd2306576`으로
일치했다.

현재 candidate/build/resolve/apply authority는 모두 없다. 이 문서는 build나
외부 환경 생성, active 변경을 허용하지 않는다.

```text
STATUS=NON_EFFECTIVE_PLAN_ONLY
CURRENT_AUTHORITY=ABSENT_DENY_ALL
ACTIVE_WRITE_ALLOWED=false
EXTERNAL_ENV_WRITE_ALLOWED=false
CHECKPOINT_WRITE_ALLOWED=false
P17_BUILD_DEFERRED=true
CANONICAL_PRODUCT_CREDIT_DELTA=0
```

## 2. 현재 기준선과 불변 상태

| 기준 | exact 값 |
|---|---|
| active control | `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4`, `ACTIVE` |
| checkpoint path | `docs/control/walksafe-project-continuation-checkpoint.json` |
| checkpoint SHA / bytes / schema | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` / `1329415` / `1.25.0` |
| tail | seq `39`, `c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a` |
| managed | `603`, path `e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1`, content `69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a` |
| v2.4 static SHA | `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07` |
| canonical | Gap/Backlog `r021/r021` |
| formal/device/gate/release | `0/279 PASS`, `0/0`, `0/5`, `NOT_ELIGIBLE` |
| discovery | discovered `132`, assigned `127`, orphan `5` |
| regression observation | A `450 PASS / 7 FAIL`; B `241 NOT_RUN` |

Stage C가 실제 commit되기 전 v2.4 bytes와 `ACTIVE`, seq39, canonical r021,
project/product/artifact/formal/device/gate/release/credit는 그대로다.

## 3. 7단계 권한과 Phase 0.5

권한은 합치거나 승계하지 않는다.

| stage | exact authority token | 허용 범위 |
|---|---|---|
| A | `PRE_P_CANDIDATE_AND_EXTERNAL_ENV_PROVISIONING_BUILD_ONLY` | Phase-0 contract, candidate, 두 external env, template, synthetic runtime-pack build/review |
| B | `PRE_P_APPLY_ENVELOPE_AUTHORITY_RESOLVE_ONLY` | authority challenge에 결속한 seq40/event/checkpoint/envelope build-01/02와 review; active write 0 |
| C | `PRE_P_EXACT_RESOLVED_ATOMIC_APPLY_ONLY` | exact resolved hashes/review에 결속한 PRE-P transaction 실행 |
| D | `POST_SEQ40_R007_SUCCESSOR_DESIGN_REVIEW_ONLY` | actual seq40/receipt 기반 add-only R007-successor design/review |
| E | `P_CANDIDATE_BUILD_REVIEW_ONLY` | reviewed R007-successor 기반 P seq40→41 candidate build/review |
| F | `P_APPLY_ENVELOPE_AUTHORITY_RESOLVE_ONLY` | P authority challenge 기반 exact P envelope resolve/review |
| G | `P_EXACT_RESOLVED_ATOMIC_APPLY_ONLY` | exact reviewed P envelope apply |

현재 A~G는 모두 `ABSENT`다. 첫 사용자 질문은 R003 독립검수 종결 뒤 Stage A
권한만 요청한다. 그 전 write는 0이다.

Stage A authority receipt는 최소 다음을 exact 결속한다.

```text
plan path/hash/bytes
R003 independent-review path/hash/bytes and findings=0/0/0
candidate allowed root =
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r003/
external environment allowed root =
  /home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-validation-r003/
active_write=false
resolve_seq40=false
apply=false
P=false
nonce, challenge, issued_at, expires_at, lease
```

두 root 밖 write, Stage-B resolved bytes, active target, checkpoint, P write는
Stage A에서도 금지다. receipt의 root가 위 두 exact root와 다르면 STOP한다.
두 허용 root의 단일 canonical 문자열은
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/pre-p-validation-convergence-candidate-r003/`와
`/home/ddobagi/.local/share/hanium-dreamup/walksafe-pre-p-validation-r003/`다.

## 4. Phase-A output DAG

lane 전에 Phase-0 contracts를 물리적으로 freeze하고 독립검수한다.

```text
Phase 0 read-only inventory
  -> Phase 0.5 Stage-A authority
  -> phase-0-contracts build-01/build-02 equality + independent review
  -> parallel CORE:
       A lock + local-combined/hosted-cpu external env
       C exact5 helper
       v2.4.1 checker/direct-test CORE
       D historical/current validator CORE
  -> B runner FINAL
       depends A + C + v2.4.1 CORE
  -> D full19 FINAL
       depends A + B + C + all CORE
  -> after-control authority-free template/static CORE FINAL
       depends D FINAL
  -> runtime-pack discovery/build FINAL
       depends frozen runtime-input contract + A env + D FINAL
  -> Stage-A aggregate/template projection review
       depends every frozen member/review
  -> STAGED_TEMPLATE_NOT_RESOLVED
```

B는 A/C/v2.4.1 CORE의 final hashes와 review를 결속한다. D FINAL은
A/B/C와 v2.4.1/D CORE를 결속한다. after-control은 D FINAL을 결속한다.
aggregate는 Phase-0 contracts와 모든 output/review를 결속한다. upstream bytes가
바뀌면 downstream을 폐기하고 add-only successor를 만든다.

Stage A에서는 seq40 event, after checkpoint, authority-resolved supersession
record, final apply envelope를 만들지 않는다.

## 5. expected Stage-A NOREPLACE paths

모든 member는 regular/non-symlink/NOREPLACE다. 외부 env의 허용 symlink만 §7의
별도 recursive manifest 정책을 따른다.

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r003/
    candidate-archive-manifest.json
    exclusions-manifest.json
    phase-0-contracts/
      builders/build-contracts.py
      builder-manifest.json
      build-01/regression-contract.json
      build-01/runtime-input-contract.json
      build-02/regression-contract.json
      build-02/runtime-input-contract.json
      candidate/regression-contract.json
      candidate/runtime-input-contract.json
      raw/source-inventory.json
      raw/regression-a-identity.json
      raw/regression-b-identity.json
      raw/runtime-observation-policy.json
      independent-review-r001.md
    lane-a/
      builders/build-tests-requirements-lock.py
      builders/provision-external-environments.py
      builder-manifest.json
      build-01/tests/requirements.lock
      build-02/tests/requirements.lock
      candidate/tests/requirements.lock
      raw/local-combined-clean-install.json
      raw/hosted-cpu-clean-install.json
      raw/environment-recursive-manifests.json
      w5-lock-successor-manifest-r001.json
      w5-lock-successor-commands-r001.json
      w5-lock-successor-receipt-r001.json
      independent-review-r001.md
    lane-c/
      builders/build-gateway-exact5-successor.py
      candidate/tests/walksafe_android_gateway_public_routes_successor_20260731_r003.py
      exact5-contract.json
      raw/positive.json
      raw/negative-config.json
      raw/negative-router.json
      raw/negative-openapi.json
      independent-review-r001.md
    control-core/
      builders/build-v2-4-1-core.py
      candidate/scripts/check_walksafe_project_continuation_v2_4_1.py
      candidate/scripts/check_walksafe_goal_graph_v2_4_1.py
      candidate/tests/walksafe_project_continuation_v2_4_1_successor_20260731.py
      candidate/tests/walksafe_goal_graph_v2_4_1_successor_20260731.py
      candidate/tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py
      candidate/docs/control/goals/walksafe-completion-graph-v2-4-1/seq40-event-schema.json
      raw/core-tests.json
      raw/negative-wrong-authority.json
      raw/negative-duplicate-activation.json
      raw/negative-wrong-predecessor-archive-sequence.json
      independent-review-r001.md
    lane-d-core/
      builders/build-baseline-validator-core.py
      candidate/scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py
      candidate/scripts/check_walksafe_artifact_baseline_current_active_20260731.py
      candidate/scripts/check_walksafe_artifact_baseline_dual_control_20260731.py
      candidate/tests/walksafe_artifact_baseline_historical_successor_20260731_r003.py
      candidate/tests/walksafe_artifact_baseline_current_successor_20260731_r003.py
      raw/core-tests.json
      independent-review-r001.md
    lane-b-final/
      builders/build-test-layer-successor.py
      candidate/scripts/run_walksafe_test_layers_20260711.sh
      candidate/tests/walksafe_test_database_preflight_successor_20260731_r003.py
      routing-manifest-before-seq40.json
      routing-manifest-after-seq40.json
      raw/validate.json
      raw/direct-supplemental.json
      raw/negative-after-stale-v2-4-reintroduction.json
      independent-review-r001.md
    lane-d-final/
      builders/build-full19-successor.py
      full19-impact-matrix.json
      full19-successor-contract.json
      raw/full19-template-run.json
      independent-review-r001.md
    runtime-pack/
      builders/discover-runtime-inputs.py
      builders/build-runtime-pack.py
      builder-manifest.json
      raw/observed-read-exec-open-trace.json
      raw/unresolved-host-access.json
      build-01/root/
      build-01/recursive-content-manifest.json
      build-02/root/
      build-02/recursive-content-manifest.json
      candidate/root/
      candidate/recursive-content-manifest.json
      independent-review-r001.md
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
      templates/control-package-manifest-v2.4.1.template.json
      templates/static-plan-manifest-v2.4.1.template.json
      templates/README.template.md
      source/v2.4-active-checkpoint-raw.json
      source/source-checkpoint-manifest.json
      raw/build-core-01.json
      raw/build-core-02.json
      raw/template-validation.json
      independent-review-r001.md
    apply/
      builders/resolve-envelope.py
      builders/execute-transaction.py
      builder-manifest.json
      build-01/template-set-manifest.json
      build-02/template-set-manifest.json
      candidate/template-set-manifest.json
      templates/exact-target-transition-set.template.json
      templates/application-transaction-plan.template.json
      templates/recovery-contract.template.json
      templates/postcheck-contract.template.json
      templates/receipt-schema.template.json
      templates/apply-envelope.template.json
      raw/build-core-01.json
      raw/build-core-02.json
      raw/template-validation.json
      independent-review-r001.md
    aggregate/
      builders/build-aggregate.py
      builders/build-projection.py
      source-snapshot-before.json
      candidate-member-manifest.json
      projection-contract.json
      template-validation-receipt.json
      aggregate-independent-review-r001.md
```

archive manifest는 exact member path/hash/bytes/mode/role/order와 recursive digest를
가진다. exclusions는 R001/R002/reviews, R007, voice/submission locks, canonical
r021, product/artifact/formal/device/gate/release와 historical evidence의 exact
path/digest를 갖는다. extra/missing member면 FAIL이다.

## 6. Phase-0 contract freeze

`regression-contract.json`과 `runtime-input-contract.json`은 어떤 lane보다 먼저
두 독립 empty build root에서 byte-identical하게 생성되고 review된다.

Regression A와 B 각각에 대해 다음을 값으로 완전히 열거한다.

- immutable suite ID와 predecessor observation
- ordered exact argv JSON token array, cwd, test file/nodeid array와 per-file hash
- interpreter role, environment role, lock and attestation identity
- env allowlist/unset list, timeout seconds, output byte cap, raw paths
- discovery nodeid array/digest, routing class와 expected current/historical role
- A→B dependency; A non-PASS면 B exact `NOT_RUN_UPSTREAM_REGRESSION_A`
- actual acceptance는 A/B 모두 rc0, FAIL 0; count-only 금지

이 두 suite identity와 digest가 review-freeze되기 전 lane build는 금지다. 따라서
lane이 나중에 자기 검증 입력을 정의하는 cycle이 없다.

`runtime-input-contract.json`은 Phase-A/Stage-B에서 실행할 모든 checker/test/build
command의 exact argv/cwd/env/interpreter/timeout/output/discovery digest와 허용
runtime read/exec/open namespace를 열거한다. observed trace는 copy 후보를 찾는
discovery evidence일 뿐 허용 근거가 아니다.

## 7. Lane A와 external environment

Lane A의 active target 후보는 `tests/requirements.lock` exact 1개다. 동일
tool/input/env에서 독립 build-01/02 bytes가 같아야 한다. W5 successor
receipt/manifest/commands/raw는 predecessor W5 exact path/hash/bytes, CPython
`3.12.13`, pip-tools `7.6.0`, Pillow `12.3.0`, pytest `8.4.2`, index/cache policy,
두 lock build와 두 clean install을 결속한다. voice/submission은 제외한다.

Stage A만 다음 root를 만들 수 있다.

```text
/home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-validation-r003/
    environment-root-manifest.json
    lease.json
    local-combined/
      recursive-content-manifest.json
      ...
    hosted-cpu/
      recursive-content-manifest.json
      ...
```

각 environment는 final sibling이 없는 상태에서 root 내부
`.local-combined.tmp.<authority-nonce>` 또는 `.hosted-cpu.tmp.<authority-nonce>`에
clean install한다. manifest를 double-walk 생성·검증한 뒤
`renameat2(RENAME_NOREPLACE)`로 final name에 atomic publish한다.

Stage A 시작 시 exact external root는 absent이고 parent chain은
regular directory/non-symlink여야 한다. root 자체를 `mkdirat` NOREPLACE로
만든 뒤에만 temp build를 시작한다. root나 final environment가 preexist,
symlink, mount replacement 또는 다른 authority nonce 소유이면 write 0이다.

- local-combined input: backend lock + candidate tests lock
- hosted-cpu input:
  `tests/general-quality-cp312-linux-x86_64-cpu.lock`
- 둘 다 hash install rc0, `pip check` rc0, Python 3.12.13, Pillow 12.3.0,
  pytest 8.4.2
- current stale venv, default/fallback interpreter, global site-packages 금지
- root/env realpath, dev/inode, uid/gid/mode, file type, size, SHA-256,
  distribution/RECORD digest와 recursive path/content digest 기록
- root와 directory는 non-symlink다. regular file은 `nlink=1`.
- symlink는 manifest에 type/target/resolved-member를 기록한 relative,
  non-escaping, same-environment target만 허용한다. absolute/dangling/cycle 금지
- publish 뒤 executable `0555`, regular `0444`, directory `0555`로 read-only
  seal하고 재귀 검증한다.
- `lease.json`은 authority nonce/expiry, owner, retention state와 manifest hash를
  결속한다.

full19 index 15~18과 regression A/B는 hosted-cpu exact env만 사용한다.

```text
HOST_PYTHON=/home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-validation-r003/hosted-cpu/bin/python3.12
SANDBOX_PYTHON=/env/hosted-cpu/bin/python3.12
```

Stage A freeze부터 Stage C 직전까지 두 env의 recursive manifest, realpath,
dev/inode/mode를 매 경계 double revalidate한다. bytes drift, lease expiry,
write bit 또는 unexpected process write면 Stage B/C write 0이다. retention 연장,
cleanup, deletion은 별도 명시적 authority 없이는 금지하며 apply 실패도 자동
cleanup 권한이 아니다.

## 8. C, B와 seq39/seq40 routing

### C CORE

C non-`test_` direct helper는 exact current5를 positive 및
config/router/OpenAPI negative fixture로 검증한다.

```text
/api/field-session: GET POST DELETE
/api/field-walk: GET POST
/api/navigation/walking: POST
/api/navigation/destinations/search: GET
/api/reports/v2: POST
```

historical exact4 대비 delta는 `/api/field-walk` exact1이다.

### B FINAL

stale exact3은 bytes/discovery를 보존하고 `HISTORICAL_TARGETED_ONLY` exact1씩
배정한다.

```text
tests/test_walksafe_test_database_preflight.py
tests/test_walksafe_android_product_boundary.py
tests/test_walksafe_artifact_baseline_materialization_20260722.py
```

BEFORE seq39에서 orphan exact5는 기존 계약을 따른다. seq39 direct control은
active/all이고 나머지 4개는 historical이다. AFTER seq40에서는 다음 old v2.4
tests도 current argv에서 제거하고 historical targeted-only다.

```text
tests/test_walksafe_project_continuation_v2_4.py
tests/test_walksafe_goal_graph_v2_4.py
tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
```

discovered inventory는 `132`, assigned는 `132`, unassigned/duplicate는 `0/0`을
유지한다. old files를 삭제하거나 rename하지 않는다.

AFTER routing negative는 위 old v2.4/seq39 exact3의 path 또는 current-only
nodeid 하나라도 `unit`, `all`, full19 #17의 argv로 재유입하면 rc nonzero다.
`routing-manifest-after-seq40.json`은 path뿐 아니라 ordered nodeid와 per-file
hash를 동결한다.

CURRENT_DIRECT supplemental total exact5는 모두 non-`test_`다.

| helper | current consumer |
|---|---|
| `tests/walksafe_test_database_preflight_successor_20260731_r003.py` | runner `unit` + `all`; full19 #18 |
| `tests/walksafe_android_gateway_public_routes_successor_20260731_r003.py` | runner `unit` + `all`; full19 #4 |
| `tests/walksafe_project_continuation_v2_4_1_successor_20260731.py` | full19 #17 |
| `tests/walksafe_goal_graph_v2_4_1_successor_20260731.py` | full19 #17 |
| `tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py` | full19 #17 |

D historical/current helpers는 runner 밖 full19 #3/#18에서만 실행한다.

## 9. D CORE와 full19 FINAL

D CORE는 historical event-time, current active와 dual validator를 분리한다.
historical materializer/receipt는 수정하지 않는다. current validator는 staged
source/checkpoint를 받는다. dual negative는 history/current 교차 입력,
timestamp/hash drift와 wrong root를 거부한다.

ordered19 digest는 A/B/C/control/D CORE가 freeze된 뒤 D FINAL에서 딱 한 번
계산한다.

| # | ID | impact | exact successor |
|---:|---|---|---|
| 1 | `CONTINUATION` | CHANGED | v2.4.1 staged checker/package/seq40 lineage |
| 2 | `GOAL_GRAPH` | CHANGED | v2.4.1 staged checker/package/seq40 state |
| 3 | `BASELINE_MATERIALIZATION` | CHANGED | D historical+current+dual direct |
| 4 | `ANDROID_GATEWAY_BOUNDARY` | CHANGED | C current direct helper |
| 5 | `NODE_TOOLCHAIN_PRE` | UNAFFECTED | predecessor raw argv bytes/hash proof |
| 6 | `GATEWAY_TYPECHECK` | UNAFFECTED | predecessor raw argv bytes/hash proof |
| 7 | `GATEWAY_TEST` | UNAFFECTED | predecessor raw argv bytes/hash proof |
| 8 | `GATEWAY_BUILD` | UNAFFECTED | predecessor raw argv bytes/hash proof |
| 9 | `WEB_TEST` | UNAFFECTED | predecessor raw argv bytes/hash proof |
| 10 | `WEB_LINT` | UNAFFECTED | no-LF SHA `0293d54ea860df64ad8bc21214902488a4062f3c0809ea9c7ca69be35fbfa35b` |
| 11 | `WEB_TYPECHECK` | UNAFFECTED | predecessor raw argv bytes/hash proof |
| 12 | `WEB_BUILD` | UNAFFECTED | predecessor raw argv bytes/hash proof |
| 13 | `NODE_TOOLCHAIN_POST` | UNAFFECTED | predecessor raw argv bytes/hash proof |
| 14 | `ANDROID_UNIT_ASSEMBLE_LINT` | UNAFFECTED | predecessor raw argv bytes/hash proof |
| 15 | `TEST_LAYER_REGISTRY_VALIDATE` | CHANGED | B final + hosted-cpu exact env |
| 16 | `FIELD_AND_RELEASE_PYTEST` | CHANGED | predecessor path tail, hosted-cpu prefix |
| 17 | `GOAL_CONTROL_PYTEST` | CHANGED | old v2.4 argv removed; v2.4.1 continuation/goal + seq40 transition direct exact3 |
| 18 | `CONTROL_AND_TRACE_PYTEST` | CHANGED | legacy baseline removed; B preflight + D history/current direct; trace9+deselect6 carry |
| 19 | `REPOSITORY_STATE` | CHANGED | v2.4.1 activation/supersession and seq40 snapshot |

5~14는 predecessor command raw UTF-8 bytes, argv/cwd/env/input/executable and
no-LF command digest를 successor와 재계산 비교한다. 다르면 `UNAFFECTED`가 아니라
`CHANGED`로 재분류하고 downstream review를 다시 한다.

15~18은 동일 sealed hosted-cpu environment와 recursive manifest를 사용한다.
exact argv/test paths/interpreter/env/timeout/output/discovery digest를 배열로
동결한다. count-only, fallback, old v2.4 test current execution은 금지다.

## 10. after-control Stage-A core와 activation closure

Stage A의 `after-control`은 다음 authority-free source/core만 가진다.

- 현재 seq39 checkpoint raw byte archive와 source manifest
- v2.4.1 checker/direct tests와 seq40 event schema
- unresolved typed templates
- deterministic `build-core.py`와 `build-authority-resolved.py`
- template validation raw/review

Stage A에서 `after-control/builders/build-core.py`를 두 empty root에 실행해
`build-01`/`build-02` core output을 byte-compare하고 verified candidate를
NOREPLACE publish한다. `apply/builders/resolve-envelope.py
--template-validation-only`도 unresolved template set을 두 번 독립
materialize해 `build-01`/`build-02` equality를 검증하되 authority slot은
치환하지 않는다. Stage B에서는 `build-authority-resolved.py`가 after-control
resolved output만, `resolve-envelope.py`가 apply contracts/envelope만 각각
두 번 만든다. `execute-transaction.py`는 build/resolve 단계에서 실행하지 않고
Stage C만 소유한다.

모든 builder manifest/raw는 builder executable hash, ordered input
path/hash/bytes, JSON argv/cwd/env/tool identity, source double-read, output
path/hash/bytes/mode, rc/stdout/stderr digest와 build-01/02 comparison receipt를
가진다.

template unresolved slots은 authority receipt/challenge/hash, resolved timestamp,
seq40 event hash, after checkpoint hash, exact target hashes와 apply transaction
identity다. unresolved slot이 final-looking bytes로 치환되거나 Stage A가
seq40/checkpoint/envelope final SHA를 주장하면 FAIL이다.

Stage B fresh `PRE_P_APPLY_ENVELOPE_AUTHORITY_RESOLVE_ONLY` receipt는 Stage-A
aggregate hash/review, source seq39 SHA/tail, external env and runtime-pack
manifests, challenge/nonce/expiry와 resolved output root만 결속한다. resolver는
동일 입력으로 두 empty roots에 실행한다.

```text
pre-p-validation-convergence-candidate-r003/
  authority-resolved/<challenge-id>/
    after-control/
      build-01/
        docs/control/goals/walksafe-completion-graph-v2-4-1/
        scripts/
        tests/
        staged-after-checkpoint.json
      build-02/
        docs/control/goals/walksafe-completion-graph-v2-4-1/
        scripts/
        tests/
        staged-after-checkpoint.json
      candidate/
      builder-manifest.json
      raw/build-01.json
      raw/build-02.json
      independent-review-r001.md
    apply/
      build-01/
        exact-target-transition-set.json
        application-transaction-plan.json
        recovery-contract.json
        postcheck-contract.json
        receipt-schema.json
        apply-envelope.json
      build-02/
      candidate/
      builder-manifest.json
      raw/build-01.json
      raw/build-02.json
      independent-review-r001.md
```

각 subtree의 build-01/02 recursive manifest와 member bytes는 byte-identical이어야
하며 candidate는 verified NOREPLACE copy다. review 전 Stage C 질문은 금지다.

resolved v2.4.1 control은 다음 상태 전이를 닫는다.

```text
v2.4 source checkpoint raw archive
v2.4 supersession record + supersession anchor
v2.4 ACTIVE -> SUPERSEDED
v2.4.1 PREPARED -> ACTIVE
seq39 tail -> atomic seq40 activation/supersession event
```

seq40은 sequence 하나를 차지하는 compound atomic event다. 내부 ordered
subevents는 `V2_4_1_PACKAGE_PREPARED` 뒤
`V2_4_SUPERSEDED_AND_V2_4_1_ACTIVATED` exact2이며 둘 중 하나만 적용되는 상태는
schema상 불가능하다. activation subevent가 Stage-B authority
receipt/challenge/hash와 supersession anchor를 결속한다.

seq40 schema/direct test는 event type, ordered subevents, source checkpoint,
previous tail,
v2.4/v2.4.1 package IDs, supersession anchor, exact transition set, managed
count/path/content, `session_handoff.changed_files`와 source snapshot을 검증한다.
project/canonical r021/credit/product/formal/device/gate/release는 unchanged다.
checkpoint에는 durable postcommit receipt requirement state만 있고 미래 receipt
path의 future content hash는 없다.

negative exact set은 wrong/missing authority, activation-before-prepare,
duplicate prepare/activation, wrong predecessor package/archive/manifest,
wrong seq39 tail/sequence/anchor와 half-applied status를 모두 거부한다.

기존 R007의 seq39→40 설계는 영구 stale다. Stage D는 actual committed seq40,
application receipt와 v2.4.1 ACTIVE를 입력으로 distinct add-only R007 successor를
design/review한다. P는 Stage E~G에서 seq40→41로만 진행한다.

## 11. synthetic runtime-pack과 isolated projection

host `/usr`, `/etc`, `/lib`, `/bin` 또는 다른 broad tree를 그대로 bind하지
않는다. Phase-A discovery는 exact frozen commands를 deny-capable tracer에서
실행해 read/exec/open을 관측한다. trace는 copy 후보 발견에만 쓴다.

`build-runtime-pack.py`는 required executable, shebang interpreter, stdlib,
shared-library transitive closure, timezone/locale/CA/config 중 실제 contract가
허용한 exact files만 synthetic root의 original absolute-relative path에 byte
copy한다. directory/regular 모두 non-symlink, regular `nlink=1`; 필요한 symlink는
resolved regular copy와 synthetic relative link를 모두 manifest한다. device,
socket, FIFO, escaping/absolute symlink는 금지다.

두 independent pack builds의 recursive path/content/type/mode/target digest가
같아야 한다. candidate pack은 NOREPLACE copy 후 read-only seal한다.

final bwrap mount universe:

```text
/usr/bin/bwrap
  --die-with-parent --new-session --unshare-all
  --proc /proc --dev /dev --tmpfs /tmp --dir /work
  --ro-bind <PACK>/usr /usr
  --ro-bind <PACK>/etc /etc
  --ro-bind <PACK>/lib /lib
  --ro-bind <PACK>/lib64 /lib64
  --symlink usr/bin /bin
  --ro-bind <EXTERNAL_ENV_ROOT> /env
  --ro-bind <ACTIVE_ROOT> /source
  --bind <OUTSIDE_REPO_PROJECTION_ROOT> /work/walksafe
  --dir /tmp/home --chdir /work/walksafe --clearenv
  --setenv HOME /tmp/home
  --setenv PATH /env/hosted-cpu/bin:/usr/bin:/bin
  --setenv PYTHONDONTWRITEBYTECODE 1
  -- <exact JSON argv>
```

`<PACK>`은 candidate synthetic pack, `<EXTERNAL_ENV_ROOT>`은 exact R003 env,
`<ACTIVE_ROOT>`은
`/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`이다. projection은
repository 밖 `mkdtemp`, `/source` active RO, `/work/walksafe` RW다. fallback과
unmanifested host bind는 없다.

source를 `.git` 포함 reflink 후 distinct inode 검증, 불가하면 byte-copy한다.
hardlink 금지, staged regular `nlink=1`, final-relative overlay만 허용한다.
root FD/realpath/dev/inode/uid/gid/mode와 active pre/post manifest/git status를
결속한다. checker `__file__`, cwd/import root는 `/work/walksafe`이고 `/source`
import/exec는 금지다.

unresolved host read/exec/open은 즉시 FAIL이다. synthetic pack으로 exact command를
지원할 수 없으면 STOP하고 broad bind/fallback을 추가하지 않는다.

Stage B resolved bytes로 두 final projection을 fresh root/process에서 각각:

```text
LIVE Quick2
BEFORE seq39 Quick2
AFTER seq40 v2.4.1 Quick2
regression A
regression B
AFTER Quick2 repeat
active pre/post identity
```

순서로 실행한다. 두 run은 command/result/managed snapshot digest가 동일해야
한다. 모든 cache/temp/raw output은 isolated writable runtime root에만 쓴다.

## 12. resolved target universe와 Stage C apply

Stage B가 before/after hash/bytes/order를 채울 exact target universe는 다음
표뿐이다.

| mode | exact final-relative target |
|---|---|
| `CAS_REPLACE` | `tests/requirements.lock` |
| `CAS_REPLACE` | `scripts/run_walksafe_test_layers_20260711.sh` |
| `NOREPLACE` | `tests/walksafe_test_database_preflight_successor_20260731_r003.py` |
| `NOREPLACE` | `tests/walksafe_android_gateway_public_routes_successor_20260731_r003.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_current_active_20260731.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_dual_control_20260731.py` |
| `NOREPLACE` | `tests/walksafe_artifact_baseline_historical_successor_20260731_r003.py` |
| `NOREPLACE` | `tests/walksafe_artifact_baseline_current_successor_20260731_r003.py` |
| `NOREPLACE` | `scripts/check_walksafe_project_continuation_v2_4_1.py` |
| `NOREPLACE` | `scripts/check_walksafe_goal_graph_v2_4_1.py` |
| `NOREPLACE` | `tests/walksafe_project_continuation_v2_4_1_successor_20260731.py` |
| `NOREPLACE` | `tests/walksafe_goal_graph_v2_4_1_successor_20260731.py` |
| `NOREPLACE` | `tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/seq40-event-schema.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/superseded-v2.4.0-active-checkpoint.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/v2.4-supersession-record.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/v2.4-supersession-anchor.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/transition-event-seq40.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/static-plan-manifest-v2.4.1.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/control-package-manifest-v2.4.1.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/README.md` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/full19-successor-contract.json` |
| `CHECKPOINT_CAS_LAST` | `docs/control/walksafe-project-continuation-checkpoint.json` |

postcheck PASS 뒤에만
`docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001/application-receipt.json`
을 `NOREPLACE_POSTCHECK_RECEIPT`로 생성한다. Stage-A transition template가 위
표를 그대로 열거하고 Stage-B는 hash/bytes/order를 resolve한다. glob, implicit
member, target 추가는 금지다.

Stage C approval은 Stage-B authority receipt/challenge, both-build equality,
resolved candidate recursive hashes, independent reviews, projection digests,
source seq39 CAS와 exact lease를 결속한다. Stage C에서만
`execute-transaction.py`를 실행한다.

```text
1 exact ordered add-only/CAS promotions
2 each file fsync + parent fsync + durable exact-prefix progress
3 checkpoint CAS last
4 v2.4.1 Quick2/postcheck
5 application receipt NOREPLACE + parent fsync
```

checkpoint 전 crash는 exact committed prefix를 재검증하고 같은 authority에서
suffix만 resume한다. non-prefix/bytes/lease drift는 `DIVERGENCE_WRITE_ZERO`다.
checkpoint 뒤에는 target write 없이 postcheck/receipt-only resume한다. rollback,
future receipt hash, checkpoint-before-target, partial overwrite는 금지다.

## 13. stop conditions

- R003 review findings nonzero 또는 Stage A exact authority absent
- candidate/external env allowed root 불일치
- Phase-0 contracts review 전에 lane build
- regression A/B identity의 argv/path/env/timeout/output/discovery digest 누락
- A/C/control/D CORE 전 B, A/B/C/CORE 전 D FINAL
- external env current fallback/stale env 사용, recursive drift, lease expiry
- local-combined/hosted-cpu clean install 중 하나라도 non-PASS
- full19 15~18이 hosted-cpu exact env 외 interpreter 사용
- discovery/assigned `132/132`, AFTER current direct exact5 불일치
- old v2.4 tests가 AFTER current/full19 argv에 남음
- stale exact3이 current이고 D helper가 runner에 들어감
- full19 1~19 또는 5~14 byte proof 미폐쇄
- runtime-pack 두 build 불일치, unresolved host access, broad host bind
- `.git` 미복제, same inode/hardlink/nlink drift, active write
- Stage A에서 final seq40/event/checkpoint/envelope bytes 생성
- Stage B authority 전 resolve 또는 두 resolved build 불일치
- activation/supersession/seq40 lineage와 direct test 누락
- managed/session_handoff/source snapshot closure 누락
- Stage C approval 전 execute, checkpoint-first, future receipt hash, rollback
- R007 수정/재사용, P seq39→40, P17 build
- canonical/product/artifact/formal/device/gate/release/credit delta

## 14. R002 4/2 remediation matrix

| exact R002 finding | R003 closure |
|---|---|
| `PRE-P-R002-BLOCKING-001` POST-SEQ40 routing/index17 | §8 AFTER historical routing, §9 index17 exact current3 |
| `PRE-P-R002-BLOCKING-002` external env authority/lifetime | §3 allowed root, §7 provision/publish/seal/lease/revalidate |
| `PRE-P-R002-BLOCKING-003` regression-contract DAG cycle | §4/§6 Phase-0 contract freeze before every lane |
| `PRE-P-R002-BLOCKING-004` v2.4.1 activation/history | §3 Stage A~C separation, §10 archive/supersession/activation/seq40 closure |
| `PRE-P-R002-MAJOR-001` after-control/apply reproducibility | §5 dedicated builders/manifests/raw, §10 two resolved builds |
| `PRE-P-R002-MAJOR-002` projection host input closure | §11 synthetic pack, no broad bind, two final runs |

## 15. self-check와 handoff

R003 independent review 전 다음을 기계검사한다.

```text
R003 regular && !symlink && nlink==1
R001/R002/R007 exact SHA unchanged
R002 review SHA == 194fa4a30fb5f70af508a99045280d4f05ad287cc6eef747136d443f5d55c504
status == NON_EFFECTIVE_PLAN_ONLY
authority A..G count == 7 && all current ABSENT
Stage-A allowed roots exact2
phase-0-contracts precedes all lanes
DAG core/final dependencies closed
external env roots local-combined + hosted-cpu exact
full19 env selection == hosted-cpu for 15..18
discovered/assigned == 132/132
AFTER current direct supplemental == 5
old v2.4 current argv count == 0
full19 CHANGED 9 + UNAFFECTED 10
runtime broad host bind count == 0
resolved build equality required
checkpoint is last CAS
future receipt hash count == 0
R007 write/reuse count == 0
P17_BUILD_DEFERRED == true
official credit delta == 0
```

이 문서 완료 상태:

```text
STAGE_A_AUTHORITY=ABSENT
STAGE_B_AUTHORITY=ABSENT
STAGE_C_AUTHORITY=ABSENT
STAGE_D_AUTHORITY=ABSENT
STAGE_E_AUTHORITY=ABSENT
STAGE_F_AUTHORITY=ABSENT
STAGE_G_AUTHORITY=ABSENT
PRE_P_CANDIDATE_BUILT=false
EXTERNAL_ENV_PROVISIONED=false
SEQ40_RESOLVED=false
PRE_P_APPLIED=false
V2_4_1_ACTIVE=false
R007_SUCCESSOR_BUILT=false
P_CANDIDATE_BUILT=false
P_APPLIED=false
```

R003 독립검수와 필요한 add-only review binding이 findings `0/0/0`으로 닫힌
최종 handoff에서만 사용자에게 Stage A exact authority를 질문한다.
