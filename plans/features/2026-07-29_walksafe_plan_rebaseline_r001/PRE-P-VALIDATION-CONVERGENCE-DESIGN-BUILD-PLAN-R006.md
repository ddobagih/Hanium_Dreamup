# PRE-P Validation Convergence Design/Build Plan R006

## 1. 문서 경계, immutable provenance, claim ceiling

| 항목 | exact 값 |
|---|---|
| `document_id` | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R006` |
| `status` | `NON_EFFECTIVE_PLAN_ONLY` |
| 작성일 | `2026-07-31` |
| R005 plan path | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R005.md` |
| R005 plan SHA / bytes / lines | `675645378e7f87d002cfba2e109bc72712c6998dd620a53de42864f8c7f3a25d` / `39839` / `997` |
| R005 formal review path | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R005-independent-review-r001.md` |
| R005 formal review SHA / bytes / lines | `a8319b8d938afa8b1d5204d109f0ae5fd82be38b7625b424e83fd382a36f46e7` / `21990` / `455` |
| R005 formal verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY; BLOCKING=5 / MAJOR=0 / MINOR=0` |
| R005 skeptical review path | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R005-independent-skeptical-review-r001.md` |
| R005 skeptical review SHA / bytes / lines | `de42d1a04dd0bd902f9a63aac5ec1a44ed0171685b718ad60c43be3285d20ebe` / `21395` / `525` |
| R005 skeptical verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY; BLOCKING=7 / MAJOR=2 / MINOR=0` |
| R004/R005 authority | `PROVENANCE_ONLY; SUPERSEDED_NON_NORMATIVE` |
| P17 | `P17_BUILD_DEFERRED` |

R006만 future PRE-P execution의 standalone normative plan이다. R005와 두
review, R004와 그 이전 revision/review는 immutable provenance다. 그것들을
normative import/merge하지 않는다. 이 문서에 열거하지 않은 operation, path,
field, schema, record kind, transition, fallback과 권한은 금지한다.

공식 current/after ceiling은 다음 exact 값이고 이 plan은 어떤 credit도 만들지
않는다.

```text
artifact_closed_equivalent=126/257
artifact_open=131/257
artifact_completion_credit_delta=0
formal_pass=0/279
formal_not_run=279/279
formal_test_credit_delta=0
actual_device_event=0/0
actual_event_credit_delta=0
gate_pass=0/5
gate_not_run=5/5
remaining_gates_waived=false
production_deployment=0
release_status=NOT_ELIGIBLE
approval_credit_delta=0
canonical_gap_backlog=r021/r021
canonical_delta=0
product_credit_delta=0
```

v2.4→v2.4.1 control 전환도 위 값을 바꾸지 않는다. percentage, missing field,
implicit PASS, artifact credit 승격은 rc2다.

```text
STATUS=NON_EFFECTIVE_PLAN_ONLY
CURRENT_AUTHORITY=ABSENT_DENY_ALL
STAGE_A_AUTHORITY=ABSENT_DENY_ALL
STAGE_B_AUTHORITY=ABSENT_DENY_ALL
STAGE_C_AUTHORITY=ABSENT_DENY_ALL
STAGE_D_AUTHORITY=ABSENT_DENY_ALL
STAGE_E_AUTHORITY=ABSENT_DENY_ALL
STAGE_F_AUTHORITY=ABSENT_DENY_ALL
STAGE_G_AUTHORITY=ABSENT_DENY_ALL
ACTIVE_WRITE_ALLOWED=false
AUTHORITY_JOURNAL_WRITE_ALLOWED=false
STAGE_A_REQUEST_ALLOWED=false
```

## 2. exact current baseline과 future source-CAS 기준

| 기준 | exact 값 |
|---|---|
| package | `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4`, `ACTIVE` |
| checkpoint | `docs/control/walksafe-project-continuation-checkpoint.json` |
| checkpoint SHA / bytes / schema | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` / `1329415` / `1.25.0` |
| transition tail | seq `39`, `c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a` |
| managed source | count `603`, path `e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1`, content `69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a` |
| discovery | discovered `132`, assigned `127`, orphan `5` |
| regression lineage | A `450 PASS / 7 FAIL`; B `241 intended / NOT_RUN` |

R006 freeze 시각의 two CAS target identity:

| path | SHA-256 | bytes | dev | inode | uid:gid | mode | nlink |
|---|---|---:|---:|---:|---|---:|---:|
| `tests/requirements.lock` | `abd690bacf91a65907e6b8357252ead9082181e9ed1b4db98987b2685e51b9fe` | 20288 | 66306 | 16653542 | 1000:1000 | 0664 | 4 |
| `scripts/run_walksafe_test_layers_20260711.sh` | `4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d` | 15588 | 66306 | 16647409 | 1000:1000 | 0775 | 1 |

Stage A/B는 이 identity를 source snapshot에 결속한다. Stage C lock 획득 뒤 첫
prewrite가 다시 전부 일치시켜야 한다. CAS 후 두 target은 새 regular inode,
`nlink=1`, respective mode `0664/0775`여야 한다. source inode를 재사용하거나
requirements lock의 기존 hardlink group을 유지하면 rc2다.

## 3. exact A~G authority와 attempt namespace

| stage | exact token | 유일한 scope |
|---|---|---|
| A | `PRE_P_ATTEMPT_SCOPED_CANDIDATE_ENV_PACK_BUILD_ONLY` | fresh candidate/env/pack attempt build |
| B | `PRE_P_ATTEMPT_SCOPED_RESOLVE_VALIDATE_ONLY` | sibling resolved attempt와 two-env validation |
| C | `PRE_P_EXACT26_ATOMIC_APPLY_AND_RECEIPT_ONLY` | fenced exact26, exact6, application receipt |
| D | `POST_SEQ40_R007_SUCCESSOR_DESIGN_REVIEW_ONLY` | committed seq40 기반 add-only R007 successor |
| E | `P_CANDIDATE_BUILD_REVIEW_ONLY` | P seq40→41 candidate |
| F | `P_ATTEMPT_SCOPED_RESOLUTION_ONLY` | P envelope resolve |
| G | `P_EXACT_RESOLVED_ATOMIC_APPLY_ONLY` | exact P apply |

현재 A~G 모두 `ABSENT_DENY_ALL`이다. R006 physical formal/skeptical review가
각각 exact `0/0/0`을 얻은 뒤에도 자동 권한은 없다. final handoff에서 Stage A
request 하나를 사용자에게 별도로 물어야 한다.

exact roots:

```text
candidate base =
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r006/
resolved base =
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-authority-resolved-r006/
external env base =
  /home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-validation-r006/
authority journal =
  /home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-authority-r006/journal/
```

attempt key grammar:

```text
<attempt-key> = <12 decimal digits>-<challenge-id>
<challenge-id> = lowercase base32(SHA-256 challenge digest), exact 52 chars
<transaction-id> = lowercase SHA-256, exact 64 hex chars
```

각 retry는 새 sequence/challenge sibling이다. literal `-001`, reused root,
reused review name, symlink alias와 prior attempt mutation은 금지한다.

```text
candidate-r006/attempts/<stage-a-attempt-key>/subject/
resolved-r006/attempts/<stage-b-attempt-key>/subject/
walksafe-pre-p-validation-r006/attempts/<stage-a-attempt-key>/
journal/attempts/stage-a/<stage-a-attempt-key>/authority-receipt.json
journal/attempts/stage-b/<stage-b-attempt-key>/authority-receipt.json
journal/attempts/stage-c/<stage-c-attempt-key>/authority-receipt.json
journal/attempts/recovery-stage-c/<recovery-attempt-key>/authority-receipt.json
journal/reviews/stage-a/<stage-a-attempt-key>/candidate-independent-review.md
journal/reviews/stage-b/<stage-b-attempt-key>/regression-final-independent-review.md
journal/reviews/stage-b/<stage-b-attempt-key>/resolved-independent-review.md
```

A/B/C/recovery stage별 최대 attempt는 16이다. 첫 stage attempt부터 hard
deadline은 각각 `21600/3600/1800/1800`초이고 retry가 deadline을 재시작하지
않는다. nominal TTL은 `900/900/300/300`초, lease slice 최대 900초, renewal
ordinal은 exact `1..32`다.

## 4. Stage-A exact member, builder, template tree

다음은 `<candidate-base>/attempts/<stage-a-attempt-key>/subject/` 아래의
complete allowlist다. directory-only role 이름은 contract가 아니다. 아래
regular file 외의 member는 rc2다.

### 4.1 Phase0, lane A, lane C

```text
builders/phase-0-builder.py
builders/lane-a-lock-env-builder.py
builders/lane-c-apply-helper-builder.py
templates/phase-0-predecessor.template.json
templates/phase-0-transformations.template.json
templates/lane-a-lock-env.template.json
templates/lane-c-apply-helper.template.py

phase-0-contracts/build-01/regression-predecessor-identity.json
phase-0-contracts/build-01/regression-allowed-transformations.json
phase-0-contracts/build-01/build-receipt.json
phase-0-contracts/build-02/regression-predecessor-identity.json
phase-0-contracts/build-02/regression-allowed-transformations.json
phase-0-contracts/build-02/build-receipt.json
phase-0-contracts/candidate/regression-predecessor-identity.json
phase-0-contracts/candidate/regression-allowed-transformations.json
phase-0-contracts/candidate/equality-receipt.json

lane-a/build-01/runtime-lock-environment-contract.json
lane-a/build-01/build-receipt.json
lane-a/build-02/runtime-lock-environment-contract.json
lane-a/build-02/build-receipt.json
lane-a/candidate/runtime-lock-environment-contract.json
lane-a/candidate/equality-receipt.json
lane-a/raw/build-01.stdout.bin
lane-a/raw/build-01.stderr.bin
lane-a/raw/build-01.result.json
lane-a/raw/build-02.stdout.bin
lane-a/raw/build-02.stderr.bin
lane-a/raw/build-02.result.json

lane-c/build-01/check-walksafe-android-gateway-successor-r006.py
lane-c/build-01/walksafe-android-gateway-successor-r006.py
lane-c/build-01/build-receipt.json
lane-c/build-02/check-walksafe-android-gateway-successor-r006.py
lane-c/build-02/walksafe-android-gateway-successor-r006.py
lane-c/build-02/build-receipt.json
lane-c/candidate/check-walksafe-android-gateway-successor-r006.py
lane-c/candidate/walksafe-android-gateway-successor-r006.py
lane-c/candidate/equality-receipt.json
lane-c/raw/build-01.stdout.bin
lane-c/raw/build-01.stderr.bin
lane-c/raw/build-01.result.json
lane-c/raw/build-02.stdout.bin
lane-c/raw/build-02.stderr.bin
lane-c/raw/build-02.result.json
```

`lane-c`의 두 candidate Python member 중 final target은
`tests/walksafe_android_gateway_public_routes_successor_20260731_r006.py` 하나다.
checker는 candidate-only builder proof이고 exact26 target이 아니다.

### 4.2 control core, lane D core/final, lane B final

```text
builders/control-core-builder.py
builders/lane-d-core-builder.py
builders/lane-b-final-builder.py
builders/lane-d-final-builder.py
templates/control-core.template.json
templates/lane-d-core.template.json
templates/lane-b-final.template.py
templates/lane-d-final.template.py

control-core/build-01/seq40-event-schema.json
control-core/build-01/routing-logical-core.json
control-core/build-01/build-receipt.json
control-core/build-02/seq40-event-schema.json
control-core/build-02/routing-logical-core.json
control-core/build-02/build-receipt.json
control-core/candidate/seq40-event-schema.json
control-core/candidate/routing-logical-core.json
control-core/candidate/equality-receipt.json
control-core/raw/build-01.stdout.bin
control-core/raw/build-01.stderr.bin
control-core/raw/build-01.result.json
control-core/raw/build-02.stdout.bin
control-core/raw/build-02.stderr.bin
control-core/raw/build-02.result.json

lane-d-core/build-01/check-walksafe-artifact-baseline-historical-event-time.py
lane-d-core/build-01/check-walksafe-artifact-baseline-current-active.py
lane-d-core/build-01/check-walksafe-artifact-baseline-dual-control.py
lane-d-core/build-01/build-receipt.json
lane-d-core/build-02/check-walksafe-artifact-baseline-historical-event-time.py
lane-d-core/build-02/check-walksafe-artifact-baseline-current-active.py
lane-d-core/build-02/check-walksafe-artifact-baseline-dual-control.py
lane-d-core/build-02/build-receipt.json
lane-d-core/candidate/check-walksafe-artifact-baseline-historical-event-time.py
lane-d-core/candidate/check-walksafe-artifact-baseline-current-active.py
lane-d-core/candidate/check-walksafe-artifact-baseline-dual-control.py
lane-d-core/candidate/equality-receipt.json
lane-d-core/raw/build-01.stdout.bin
lane-d-core/raw/build-01.stderr.bin
lane-d-core/raw/build-01.result.json
lane-d-core/raw/build-02.stdout.bin
lane-d-core/raw/build-02.stderr.bin
lane-d-core/raw/build-02.result.json

lane-b-final/build-01/walksafe-test-database-preflight-successor-r006.py
lane-b-final/build-01/build-receipt.json
lane-b-final/build-02/walksafe-test-database-preflight-successor-r006.py
lane-b-final/build-02/build-receipt.json
lane-b-final/candidate/walksafe-test-database-preflight-successor-r006.py
lane-b-final/candidate/equality-receipt.json
lane-b-final/raw/build-01.stdout.bin
lane-b-final/raw/build-01.stderr.bin
lane-b-final/raw/build-01.result.json
lane-b-final/raw/build-02.stdout.bin
lane-b-final/raw/build-02.stderr.bin
lane-b-final/raw/build-02.result.json

lane-d-final/build-01/walksafe-artifact-baseline-historical-successor-r006.py
lane-d-final/build-01/walksafe-artifact-baseline-current-successor-r006.py
lane-d-final/build-01/build-receipt.json
lane-d-final/build-02/walksafe-artifact-baseline-historical-successor-r006.py
lane-d-final/build-02/walksafe-artifact-baseline-current-successor-r006.py
lane-d-final/build-02/build-receipt.json
lane-d-final/candidate/walksafe-artifact-baseline-historical-successor-r006.py
lane-d-final/candidate/walksafe-artifact-baseline-current-successor-r006.py
lane-d-final/candidate/equality-receipt.json
lane-d-final/raw/build-01.stdout.bin
lane-d-final/raw/build-01.stderr.bin
lane-d-final/raw/build-01.result.json
lane-d-final/raw/build-02.stdout.bin
lane-d-final/raw/build-02.stderr.bin
lane-d-final/raw/build-02.result.json
```

### 4.3 after-control와 apply

```text
builders/after-control-builder.py
builders/apply-envelope-builder.py
templates/after-control.template.json
templates/requirements-lock.template.txt
templates/runner.template.sh
templates/continuation-checker.template.py
templates/goal-checker.template.py
templates/control-test.template.py
templates/control-json.template.json
templates/control-readme.template.md
templates/application-receipt.template.json

after-control/build-01/check-walksafe-project-continuation-v2-4-1.py
after-control/build-01/check-walksafe-goal-graph-v2-4-1.py
after-control/build-01/test-walksafe-project-continuation-v2-4-1-successor.py
after-control/build-01/test-walksafe-goal-graph-v2-4-1-successor.py
after-control/build-01/test-walksafe-v2-4-1-seq40-transition-successor.py
after-control/build-01/test-routing-before-seq39-v2.4.json
after-control/build-01/test-routing-after-seq40-v2.4.1.json
after-control/build-01/superseded-v2.4.0-active-checkpoint.json
after-control/build-01/v2.4-supersession-record.json
after-control/build-01/v2.4-supersession-anchor.json
after-control/build-01/transition-event-seq40.json
after-control/build-01/static-plan-manifest-v2.4.1.json
after-control/build-01/control-package-manifest-v2.4.1.json
after-control/build-01/README.md
after-control/build-01/full19-successor-contract.json
after-control/build-01/build-receipt.json

after-control/build-02/check-walksafe-project-continuation-v2-4-1.py
after-control/build-02/check-walksafe-goal-graph-v2-4-1.py
after-control/build-02/test-walksafe-project-continuation-v2-4-1-successor.py
after-control/build-02/test-walksafe-goal-graph-v2-4-1-successor.py
after-control/build-02/test-walksafe-v2-4-1-seq40-transition-successor.py
after-control/build-02/test-routing-before-seq39-v2.4.json
after-control/build-02/test-routing-after-seq40-v2.4.1.json
after-control/build-02/superseded-v2.4.0-active-checkpoint.json
after-control/build-02/v2.4-supersession-record.json
after-control/build-02/v2.4-supersession-anchor.json
after-control/build-02/transition-event-seq40.json
after-control/build-02/static-plan-manifest-v2.4.1.json
after-control/build-02/control-package-manifest-v2.4.1.json
after-control/build-02/README.md
after-control/build-02/full19-successor-contract.json
after-control/build-02/build-receipt.json

after-control/candidate/check-walksafe-project-continuation-v2-4-1.py
after-control/candidate/check-walksafe-goal-graph-v2-4-1.py
after-control/candidate/test-walksafe-project-continuation-v2-4-1-successor.py
after-control/candidate/test-walksafe-goal-graph-v2-4-1-successor.py
after-control/candidate/test-walksafe-v2-4-1-seq40-transition-successor.py
after-control/candidate/test-routing-before-seq39-v2.4.json
after-control/candidate/test-routing-after-seq40-v2.4.1.json
after-control/candidate/superseded-v2.4.0-active-checkpoint.json
after-control/candidate/v2.4-supersession-record.json
after-control/candidate/v2.4-supersession-anchor.json
after-control/candidate/transition-event-seq40.json
after-control/candidate/static-plan-manifest-v2.4.1.json
after-control/candidate/control-package-manifest-v2.4.1.json
after-control/candidate/README.md
after-control/candidate/full19-successor-contract.json
after-control/candidate/equality-receipt.json
after-control/raw/build-01.stdout.bin
after-control/raw/build-01.stderr.bin
after-control/raw/build-01.result.json
after-control/raw/build-02.stdout.bin
after-control/raw/build-02.stderr.bin
after-control/raw/build-02.result.json

apply/build-01/requirements.lock
apply/build-01/run_walksafe_test_layers_20260711.sh
apply/build-01/walksafe-project-continuation-checkpoint.json
apply/build-01/application-receipt.template.json
apply/build-01/exact26-target-manifest.json
apply/build-01/build-receipt.json
apply/build-02/requirements.lock
apply/build-02/run_walksafe_test_layers_20260711.sh
apply/build-02/walksafe-project-continuation-checkpoint.json
apply/build-02/application-receipt.template.json
apply/build-02/exact26-target-manifest.json
apply/build-02/build-receipt.json
apply/candidate/requirements.lock
apply/candidate/run_walksafe_test_layers_20260711.sh
apply/candidate/walksafe-project-continuation-checkpoint.json
apply/candidate/application-receipt.template.json
apply/candidate/exact26-target-manifest.json
apply/candidate/equality-receipt.json
apply/raw/build-01.stdout.bin
apply/raw/build-01.stderr.bin
apply/raw/build-01.result.json
apply/raw/build-02.stdout.bin
apply/raw/build-02.stderr.bin
apply/raw/build-02.result.json
```

### 4.4 aggregate, raw, manifest

```text
builders/aggregate-builder.py
templates/aggregate.template.json
aggregate/build-01/source-snapshot.json
aggregate/build-01/typed-dependency-graph.json
aggregate/build-01/candidate-target-map.json
aggregate/build-01/build-receipt.json
aggregate/build-02/source-snapshot.json
aggregate/build-02/typed-dependency-graph.json
aggregate/build-02/candidate-target-map.json
aggregate/build-02/build-receipt.json
aggregate/candidate/source-snapshot.json
aggregate/candidate/typed-dependency-graph.json
aggregate/candidate/candidate-target-map.json
aggregate/candidate/equality-receipt.json
aggregate/raw/build-01.stdout.bin
aggregate/raw/build-01.stderr.bin
aggregate/raw/build-01.result.json
aggregate/raw/build-02.stdout.bin
aggregate/raw/build-02.stderr.bin
aggregate/raw/build-02.result.json
candidate-review-subject-manifest.json
```

각 builder/template/build/candidate/raw file은 subject manifest의 exact
path/type/mode/hash/bytes에 들어간다. build-01과 build-02는 깨끗한 별도
directory에서 실행하고 semantic output bytes가 동일해야 한다. candidate는
그 bytes를 새 inode로 materialize하며 build inode와 달라야 한다. manifest는
자기 path만 recursive domain에서 제외한다. external review path/hash를
포함하지 않는다.

## 5. runtime-pack, environment, bwrap isolation

### 5.1 exact env × build tree

Stage-A subject 안의 complete runtime-pack tree:

```text
runtime-pack/builders/runtime-pack-builder.py
runtime-pack/templates/runtime-pack.template.json
runtime-pack/local-combined/build-01/root.tar
runtime-pack/local-combined/build-01/recursive-content-manifest.json
runtime-pack/local-combined/build-01/transitive-closure.json
runtime-pack/local-combined/build-01/build-receipt.json
runtime-pack/local-combined/build-02/root.tar
runtime-pack/local-combined/build-02/recursive-content-manifest.json
runtime-pack/local-combined/build-02/transitive-closure.json
runtime-pack/local-combined/build-02/build-receipt.json
runtime-pack/local-combined/candidate/root.tar
runtime-pack/local-combined/candidate/recursive-content-manifest.json
runtime-pack/local-combined/candidate/transitive-closure.json
runtime-pack/local-combined/candidate/equality-receipt.json
runtime-pack/hosted-cpu/build-01/root.tar
runtime-pack/hosted-cpu/build-01/recursive-content-manifest.json
runtime-pack/hosted-cpu/build-01/transitive-closure.json
runtime-pack/hosted-cpu/build-01/build-receipt.json
runtime-pack/hosted-cpu/build-02/root.tar
runtime-pack/hosted-cpu/build-02/recursive-content-manifest.json
runtime-pack/hosted-cpu/build-02/transitive-closure.json
runtime-pack/hosted-cpu/build-02/build-receipt.json
runtime-pack/hosted-cpu/candidate/root.tar
runtime-pack/hosted-cpu/candidate/recursive-content-manifest.json
runtime-pack/hosted-cpu/candidate/transitive-closure.json
runtime-pack/hosted-cpu/candidate/equality-receipt.json
```

external environment attempt complete roots:

```text
walksafe-pre-p-validation-r006/attempts/<stage-a-attempt-key>/
  local-combined/root/
  local-combined/recursive-content-manifest.json
  local-combined/materialization-receipt.json
  hosted-cpu/root/
  hosted-cpu/recursive-content-manifest.json
  hosted-cpu/materialization-receipt.json
  environment-attempt-manifest.json
```

CPython `3.12.13`, Pillow `12.3.0`, pytest `8.4.2`, locked Node/npm/Java/Android
tools와 full19이 실제 호출하는 executable/library/data만 pack한다. closure
builder는 ELF interpreter와 recursive `DT_NEEDED`, script shebang
interpreter, Python imported module/package/data/metadata/RECORD, Node executable와
lock-selected package closure, Java/Android executable/JAR/SDK member, locale/CA
data를 typed edge로 완전 열거한다. undeclared dynamic load는 허용하지 않는다.

각 env의 build-01/build-02 closure graph와 recursive manifest는 byte-equal해야
한다. candidate tar와 external materialization은 same member bytes지만 모두
fresh distinct inode다. regular member는 `nlink=1`; device, FIFO, socket,
hardlink, absolute symlink, root 밖 escaping/dangling/cyclic symlink는
금지한다. relative non-cyclic symlink만 exact target string과 resolved member
identity를 manifest한다. directory/regular/executable mode는 seal 후
`0555/0444/0555`다. owner/group, dev/inode/mode/nlink/size/hash/link-target을
재귀 기록하고 extraction 전후 equality를 검증한다. `.tmp.<nonce>`에서
file+directory fsync 후 NOREPLACE publish한다.

### 5.2 exact sandbox invocation

각 invocation은 다음 option과 bind만 쓴다. option 삭제/추가, broad host bind,
host `/usr`, `/bin`, `/lib*`, home, repo 원본 bind는 금지한다.

```text
bwrap
  --unshare-all
  --die-with-parent
  --new-session
  --clearenv
  --ro-bind <external-env>/root /runtime
  --ro-bind <projection>/worktree /work/walksafe
  --bind <projection>/raw/<invocation-id> /out
  --proc /proc
  --dev /dev
  --tmpfs /tmp
  --dir /home/sandbox
  --chdir /work/walksafe
  --setenv HOME /home/sandbox
  --setenv PATH /runtime/bin
  --setenv LANG C.UTF-8
  --setenv LC_ALL C.UTF-8
  --setenv PYTHONDONTWRITEBYTECODE 1
  --setenv PYTEST_DISABLE_PLUGIN_AUTOLOAD 1
  --setenv CI true
  /runtime/bin/env -i <same exact env> <manifested argv>
```

`<projection>/worktree`는 source/candidate overlay를 fresh inode로 materialize한
sealed synthetic tree다. `/out`만 writable하고 result publisher 외 payload가
write할 수 없다. network namespace, IPC, PID, UTS, cgroup namespace는
unshared다. host PATH lookup, `/usr/bin/env`, cross-env executable/library,
fallback interpreter, package cache, user config와 ambient environment read는
exact 0이다.

이 sandbox는 두 env 각각의 BEFORE validate, AFTER overlay/validate,
full19 slot 001..019, regression A, regression B, AFTER repeat 모두에 적용한다.
각 invocation은 sibling:

```text
raw/<invocation-id>/intent.json
raw/<invocation-id>/stdout.bin
raw/<invocation-id>/stderr.bin
raw/<invocation-id>/access-trace.raw
raw/<invocation-id>/access-trace.json
raw/<invocation-id>/result.json
```

을 갖는다. trace는 open/openat/openat2/execve/execveat/stat 계열의 resolved
path, mount source, access result를 기록한다. full19 raw는 각 slot의 sibling
trace를 포함한다. dropped event, tracer detach, overflow, missing trace,
unmanifested pack read/exec, sibling env/pack/raw 접근은 FAIL이다. 특히 #5~#14의
host access count와 cross-env fallback count는 exact 0이다.

## 6. Phase0 DAG, lanes, runner와 Stage-B resolved tree

Phase0는 predecessor-only identity contract다.
`regression-predecessor-identity.json`과
`regression-allowed-transformations.json`은 `executable=false`,
`acceptance=false`, successor hash empty, typed future role ID만 가진다.
A `450/7`, B `241 intended/NOT_RUN`은 lineage일 뿐 acceptance가 아니다.

허용 transformation:

- lock/env/pack binding
- single runner exact argv와 selector exact2
- history exact6 current→history
- orphan exact5 assignment
- B/C direct2, v2.4.1 direct3, D direct2

ignore/deselect 추가, rename/delete, root 축소, wildcard, fixed241 acceptance와
목록 밖 transformation은 금지한다.

exact DAG:

```text
Phase0 predecessor identity
-> lane A || lane C || control-core || lane D-core
-> lane B-final(lane A,lane C,control-core)
-> lane D-final(lane A,lane B-final,lane C,control-core,lane D-core)
-> after-control
-> apply envelope
-> aggregate seal
-> Stage-A external candidate review
-> Stage-B resolved after-control
-> regression-final build-01/build-02 equality
-> regression-final external review
-> local-combined ordered validation
-> hosted-cpu ordered validation
-> regression A PASS
-> regression B PASS
```

Stage-B complete subject tree:

```text
builders/resolved-after-control-builder.py
builders/resolved-apply-builder.py
templates/resolved-after-control.template.json
templates/resolved-apply.template.json
build-01/after-control/resolved-targets.json
build-01/after-control/build-receipt.json
build-01/apply/exact26-target-manifest.json
build-01/apply/build-receipt.json
build-02/after-control/resolved-targets.json
build-02/after-control/build-receipt.json
build-02/apply/exact26-target-manifest.json
build-02/apply/build-receipt.json
candidate/after-control/resolved-targets.json
candidate/after-control/equality-receipt.json
candidate/apply/exact26-target-manifest.json
candidate/apply/equality-receipt.json
regression-final/builders/regression-final-builder.py
regression-final/templates/regression-final.template.json
regression-final/build-01/regression-contract.json
regression-final/build-01/build-receipt.json
regression-final/build-02/regression-contract.json
regression-final/build-02/build-receipt.json
regression-final/candidate/regression-contract.json
regression-final/candidate/equality-receipt.json
regression-final/raw/build-01.stdout.bin
regression-final/raw/build-01.stderr.bin
regression-final/raw/build-01.result.json
regression-final/raw/build-02.stdout.bin
regression-final/raw/build-02.stderr.bin
regression-final/raw/build-02.result.json
projections/local-combined/worktree-manifest.json
projections/local-combined/validation-intent.json
projections/local-combined/validation-result.json
projections/hosted-cpu/worktree-manifest.json
projections/hosted-cpu/validation-intent.json
projections/hosted-cpu/validation-result.json
resolved-review-subject-manifest.json
```

각 projection의 `raw/` subtree는 §5.2의 exact six-file invocation siblings만
가진다. `<invocation-id>` complete allowlist는:

```text
before-validate
after-validate
full19-001
full19-002
full19-003
full19-004
full19-005
full19-006
full19-007
full19-008
full19-009
full19-010
full19-011
full19-012
full19-013
full19-014
full19-015
full19-016
full19-017
full19-018
full19-019
regression-a
regression-b
after-repeat
```

다. regression-final input은 Phase0, A/C/control/B/D manifest와 resolved
v2.4.1 bytes의 ordered literal array다. glob 또는 discovery-only 입력은
없다. suite A는 B-final runner의 current UNIT+direct2, suite B는 D-final
full19 #3/#4/#17/#18 exact subcommands다. nodeid array/digest가 successor
count를 결정한다.

single CAS runner exact argv:

```text
bash /work/walksafe/scripts/run_walksafe_test_layers_20260711.sh
  --layer <validate|unit|all>
  --root /work/walksafe
  --checkpoint <root-relative-checkpoint-json>
  --control-selector <BEFORE_SEQ39_V24|AFTER_SEQ40_V241>
  --routing-manifest <exact-root-relative-final-manifest-json>
```

| selector | exact world | exact final routing manifest |
|---|---|---|
| `BEFORE_SEQ39_V24` | seq39 raw hash/tail, v2.4 ACTIVE | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-before-seq39-v2.4.json` |
| `AFTER_SEQ40_V241` | seq40 compound event, v2.4 SUPERSEDED, v2.4.1 ACTIVE | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json` |

defaults/env inference는 없다. runner embedded core는 selector/state exact2 logical
routing만 갖고 future manifest hash를 embed하지 않는다. final manifest가 core
digest를 결속하고 checkpoint/static manifest가 AFTER final hash를 단방향
결속한다. missing/duplicate/partial flag, unknown selector, root/self mismatch,
escape, wrong package/seq/event/tail, manifest drift는 pre-spawn rc2,
`child_exec_count=0`이다.

## 7. two-env full19과 regression-final acceptance

각 exact2 projection은 자기 sealed env와 자기 candidate pack만 사용해:

```text
BEFORE validate
-> AFTER overlay
-> AFTER validate
-> full19 001..019
-> regression A
-> regression B
-> AFTER repeat
```

를 실행한다. full19 slot마다 §5.2 six-file raw sibling을 갖는다. intent는
ID/argv/cwd/env/interpreter/pack/input/timeout/output cap을 동결한다. result는
exec boolean, rc, stdout/stderr hash/bytes, `PASS|FAIL|NOT_RUN`, reason을
필수로 갖는다. unexecuted도 six-file set과 zero raw, `exec=false`,
`NOT_RUN`을 남기며 projection은 INCOMPLETE다.

| # | ID | impact |
|---:|---|---|
| 1 | `CONTINUATION` | CHANGED v2.4.1/seq40 |
| 2 | `GOAL_GRAPH` | CHANGED v2.4.1/seq40 |
| 3 | `BASELINE_MATERIALIZATION` | CHANGED D triple |
| 4 | `ANDROID_GATEWAY_BOUNDARY` | CHANGED C helper |
| 5 | `NODE_TOOLCHAIN_PRE` | UNAFFECTED byte proof, synthetic pack |
| 6 | `GATEWAY_TYPECHECK` | UNAFFECTED byte proof, synthetic pack |
| 7 | `GATEWAY_TEST` | UNAFFECTED byte proof, synthetic pack |
| 8 | `GATEWAY_BUILD` | UNAFFECTED byte proof, synthetic pack |
| 9 | `WEB_TEST` | UNAFFECTED byte proof, synthetic pack |
| 10 | `WEB_LINT` | UNAFFECTED no-LF `0293d54ea860df64ad8bc21214902488a4062f3c0809ea9c7ca69be35fbfa35b` |
| 11 | `WEB_TYPECHECK` | UNAFFECTED byte proof, synthetic pack |
| 12 | `WEB_BUILD` | UNAFFECTED byte proof, synthetic pack |
| 13 | `NODE_TOOLCHAIN_POST` | UNAFFECTED byte proof, synthetic pack |
| 14 | `ANDROID_UNIT_ASSEMBLE_LINT` | UNAFFECTED byte proof, synthetic pack |
| 15 | `TEST_LAYER_REGISTRY_VALIDATE` | CHANGED single runner validate |
| 16 | `FIELD_AND_RELEASE_PYTEST` | CHANGED current env |
| 17 | `GOAL_CONTROL_PYTEST` | CHANGED old v2.4 current0, v2.4.1 direct3 |
| 18 | `CONTROL_AND_TRACE_PYTEST` | CHANGED legacy baseline removed, B+D, trace9+deselect6 |
| 19 | `REPOSITORY_STATE` | CHANGED seq40 state/event |

both env는 `19/19 actual rc0`, regression A/B PASS, AFTER repeat digest equality가
필수다. #17은 v2.4.1 direct exact3의 explicit files, collected nodeids,
per-file digest를 확인한다. #5~#14는 pack-only이고 host read/open/exec 0이다.

## 8. append-only journal publication과 single numeric slot

exact root:

```text
/home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-authority-r006/journal/
    genesis/authority-journal-genesis.json
    genesis/stage-c-transaction.lock
    records/<12digit>.json
    attempts/stage-a/<attempt-key>/authority-receipt.json
    attempts/stage-b/<attempt-key>/authority-receipt.json
    attempts/stage-c/<attempt-key>/authority-receipt.json
    attempts/recovery-stage-c/<attempt-key>/authority-receipt.json
    reviews/stage-a/<attempt-key>/candidate-independent-review.md
    reviews/stage-b/<attempt-key>/regression-final-independent-review.md
    reviews/stage-b/<attempt-key>/resolved-independent-review.md
    transactions/<transaction-id>/transaction-manifest.json
    transactions/<transaction-id>/records/<12digit>.json
```

global/transaction final slot 모두 numeric-only다. record kind는 signed body에만
있다. `<seq>-<kind>.json`, claim/head/sequence directory, mutable pointer,
reservation/empty sentinel은 존재하지 않는다.

genesis는 journal ID, signature domain, Ed25519 verifier fingerprint, allowed
kinds, attempt/renewal/deadline limit, first sequence `000000000001`, exact lock
path와 lock `dev/inode/uid/gid/mode/nlink/size`를 고정한다. signed envelope:

```json
{"payload":{"record_kind":"<TAG>","sequence":"<12digit>"},"signature":"<base64url-no-padding>"}
```

실제 payload는 §9 tagged union의 exact fields를 더 가진다. strict RFC 8785
JCS UTF-8, duplicate key 없음, terminal LF 없음이다. signature domain은
`WS-PRE-P-R006-JOURNAL-V1 || NUL || JCS(payload)`다.

publisher의 유일한 순서:

```text
open unnamed O_TMPFILE in final parent
-> write complete signed bytes
-> fchmod 0444
-> fsync(tmp fd)
-> linkat(tmp fd,"",parent fd,"<12digit>.json",AT_EMPTY_PATH) NOREPLACE
-> fsync(parent fd)
-> reopen final O_NOFOLLOW
-> lstat/fstat/hash/bytes/mode/nlink exact verify
```

`EEXIST` loser는 persistent write count 0, current stream full replay, 새 signed
state transition 생성으로만 retry한다. loser payload를 next sequence에
재사용하지 않는다. gap, invalid occupied slot, unexpected filename,
non-contiguous sequence, bad signature/prior tuple은 fail-stop이고 append 0이다.
numeric filename N은 payload `sequence=N`과 exact 같고 body kind는 해당
stream allowed set member여야 한다. global과 transaction stream은 각각
genesis/manifest부터 numeric filename의 contiguous signed chain을 재생한다.

## 9. global record tagged union과 canonical field-state

allowed global kinds exact15:

```text
ISSUED
CONSUME_CLAIMED
LEASE_ACQUIRED
LEASE_RENEWED
PREPARED
DELEGATED_AND_CLOSED
CLOSED_SUCCESS
CLOSED_INCIDENT
REVOKED
RECOVERY_ISSUED
RECOVERY_CONSUMED
RECOVERY_LEASE_ACQUIRED
RECOVERY_LEASE_RENEWED
RECOVERY_CLOSED_SUCCESS
RECOVERY_CLOSED_INCIDENT
```

typed primitives:

```text
Physical = {path, sha256, bytes, dev, inode, uid, gid, mode, nlink}
RecordRef = {path, sha256, bytes, sequence, record_kind}
Absent = {state:"SIGNED_ABSENT", reason:<exact enum>}
Time = RFC3339 UTC with nanoseconds
Identity = {id, key_fingerprint, executable_sha256, pid_start_time}
Lease = {start, end, hard_deadline, renewal_ordinal, fencing_epoch}
Scope = {scope_id, allowed_operations, allowed_roots, denied_operations,
         denied_roots}
Source = {checkpoint:Physical, tail_sequence, tail_sha256,
          source_snapshot:Physical}
```

모든 kind의 common identity fields는 다음이며 전부 `ACTUAL`이다.

```text
schema_version="1.0.0"
journal_id
record_kind
sequence
prior_record:RecordRef
genesis:Physical
event_at:Time
actor:Identity
stage
attempt_key
challenge_id
nonce
```

first record의 `prior_record`만
`{path:"genesis/authority-journal-genesis.json",sha256:<64hex>,
bytes:<positive-integer>,sequence:"000000000000",record_kind:"GENESIS"}`다.
각 payload는 common identity fields에 더해
`transaction_binding`을 반드시 갖는다. A/B non-transaction record는
`Absent(reason:"STAGE_HAS_NO_TRANSACTION")`, C/recovery record는
`{transaction_id, transaction_manifest:Physical}` ACTUAL이다. null, empty
placeholder와 unknown enum은 금지한다.

각 kind payload는 `common ∪ exact additional fields`만 허용한다.

| kind | exact additional fields |
|---|---|
| `ISSUED` | `attempt_ordinal, receipt:Physical, request:Physical, challenge:Physical, raw_response:Physical, scope:Scope, source:Source, validity, prior_attempt, authorization_state` |
| `CONSUME_CLAIMED` | `issued:RecordRef, receipt:Physical, expected_head:RecordRef, consume_ordinal=1, one_use=true, authorization_state` |
| `LEASE_ACQUIRED` | `consume:RecordRef, receipt:Physical, lease:Lease, grant_head:RecordRef, authorization_state` |
| `LEASE_RENEWED` | `consume:RecordRef, receipt:Physical, prior_lease:RecordRef, prior_lease_ordinal, lease:Lease, authorization_state` |
| `PREPARED` | `consume:RecordRef, active_lease:RecordRef, receipt:Physical, subject_manifest:Physical, review_bindings, environment_bindings, regression_bindings, transaction_manifest, authorization_state` |
| `DELEGATED_AND_CLOSED` | `stage_b_issued:RecordRef, stage_b_consume:RecordRef, stage_b_lease:RecordRef, stage_b_prepared:RecordRef, stage_b_receipt:Physical, stage_c_issued:RecordRef, stage_c_pending_receipt:Physical, stage_c_validity, stage_c_scope:Scope, pair_digest, before_states, after_states` |
| `CLOSED_SUCCESS` | `issued:RecordRef, consume:RecordRef, prepared:RecordRef, active_lease:RecordRef, receipt:Physical, terminal_evidence, before_state, after_state, close_reason` |
| `CLOSED_INCIDENT` | `issued:RecordRef, consume_state, lease_state, prepared_state, receipt:Physical, incident:Physical, before_state, after_state, close_reason` |
| `REVOKED` | `issued:RecordRef, receipt:Physical, revocation_origin:Physical, before_state, after_state, reason` |
| `RECOVERY_ISSUED` | `attempt_ordinal, receipt:Physical, request:Physical, challenge:Physical, raw_response:Physical, scope:Scope, source:Source, original_transaction, original_c_attempt, observed_progress_head, observed_checkpoint, prior_recovery_attempt, validity, authorization_state` |
| `RECOVERY_CONSUMED` | `recovery_issued:RecordRef, receipt:Physical, expected_head:RecordRef, observed_progress_head, consume_ordinal=1, one_use=true, authorization_state` |
| `RECOVERY_LEASE_ACQUIRED` | `recovery_consume:RecordRef, receipt:Physical, lease:Lease, grant_head:RecordRef, original_transaction, authorization_state` |
| `RECOVERY_LEASE_RENEWED` | `recovery_consume:RecordRef, receipt:Physical, prior_lease:RecordRef, prior_lease_ordinal, lease:Lease, original_transaction, authorization_state` |
| `RECOVERY_CLOSED_SUCCESS` | `recovery_issued:RecordRef, recovery_consume:RecordRef, active_lease:RecordRef, receipt:Physical, original_transaction, terminal_evidence, before_state, after_state, close_reason` |
| `RECOVERY_CLOSED_INCIDENT` | `recovery_issued:RecordRef, recovery_consume_state, lease_state, receipt:Physical, original_transaction, incident:Physical, before_state, after_state, close_reason` |

exact nested schemas:

```text
validity = {issued_at, not_before, expires_at, ttl_seconds,
            hard_deadline, revocation_state:"UNREVOKED"}
prior_attempt =
  Absent(reason:"FIRST_ATTEMPT") |
  {attempt_key, receipt:Physical, closure:RecordRef, status, reason}
review_bindings =
  [{role, review:Physical, verdict:{blocking:0,major:0,minor:0}}]
environment_bindings =
  [{environment:"local-combined"|"hosted-cpu",
    root_manifest:Physical, pack_manifest:Physical, lease_identity}]
regression_bindings =
  {regression_final_manifest:Physical, suite_a, suite_b, full19_exact2}
transaction_manifest =
  Absent(reason:"STAGE_A_OR_B_NO_TRANSACTION") |
  {transaction_id, manifest:Physical, exact26_manifest:Physical}
stage_c_validity =
  {issued_at, not_before, expires_at, event_at,
   revocation_state:"UNREVOKED", consumption_state:"UNCONSUMED",
   authorization_state:"PENDING_DELEGATION",
   expected_global_head:RecordRef}
before_states =
  {stage_b:"PREPARED_EFFECTIVE", stage_c:"PENDING_DELEGATION_INEFFECTIVE"}
after_states =
  {stage_b:"CLOSED_SUCCESS_INEFFECTIVE", stage_c:"EFFECTIVE_UNCONSUMED"}
```

renewal은 `prior_lease`의 path/hash/bytes/sequence/kind를 exact 결속하고
`prior_lease_ordinal`과 새 `lease.renewal_ordinal`이 각각 `n-1/n`이어야 한다.
ordinal은 `1..32`; 최초 acquire는 0이다. renewal은 epoch을 바꾸지 않고
receipt expiry/hard deadline보다 늦을 수 없다.

### 9.1 ACTUAL / SIGNED_ABSENT / FORBIDDEN matrix

`ACTUAL`은 위 typed value, `SIGNED_ABSENT`는 exact `Absent`, `FORBIDDEN`은 key
자체가 없어야 함을 뜻한다. ACTUAL 또는 SIGNED_ABSENT가 필요한 key의
missing/null/unknown은 rc2다.

| field group | A/B ordinary | C pending/effective | recovery | unrelated kinds |
|---|---|---|---|---|
| common fields | ACTUAL | ACTUAL | ACTUAL | FORBIDDEN kind |
| `transaction_binding` | SIGNED_ABSENT | ACTUAL | ACTUAL original | FORBIDDEN kind |
| prior attempt | first SIGNED_ABSENT, retry ACTUAL | first SIGNED_ABSENT, retry ACTUAL | prior recovery SIGNED_ABSENT/ACTUAL | FORBIDDEN |
| candidate/env/review | A issue SIGNED_ABSENT, A close/PREPARED ACTUAL; B ACTUAL | ACTUAL read-only | ACTUAL read-only | FORBIDDEN |
| resolved/reviews | A FORBIDDEN; B issue SIGNED_ABSENT, B PREPARED ACTUAL | ACTUAL | ACTUAL | FORBIDDEN |
| T1/X1/V1/exact26 | A/B FORBIDDEN | ACTUAL | ACTUAL | FORBIDDEN |
| consume binding | ISSUED SIGNED_ABSENT | pending issue SIGNED_ABSENT, after consume ACTUAL | issue SIGNED_ABSENT, after consume ACTUAL | FORBIDDEN |
| lease binding | issue/consume SIGNED_ABSENT, lease onward ACTUAL | same | same | FORBIDDEN |
| prepared binding | early incident SIGNED_ABSENT, success ACTUAL | pending incident SIGNED_ABSENT, success ACTUAL | FORBIDDEN | FORBIDDEN |
| recovery fields | FORBIDDEN | FORBIDDEN | ACTUAL | FORBIDDEN |
| delegation pair | only `DELEGATED_AND_CLOSED` ACTUAL | only same record ACTUAL | FORBIDDEN | FORBIDDEN |
| incident evidence | only incident close ACTUAL | only incident close ACTUAL | only recovery incident ACTUAL | FORBIDDEN |
| success evidence | only success close ACTUAL | only success close ACTUAL | only recovery success ACTUAL | FORBIDDEN |

`CLOSED_INCIDENT.consume_state`, `lease_state`, `prepared_state`는 각각
`Absent(reason:"NOT_REACHED")` 또는 matching `RecordRef`다. 따라서 A/B early
incident와 pending C pre-consume/pre-prepare incident를 canonical하게 표현할
수 있다. success/delegation에는 필요한 PREPARED가 ACTUAL이어야 한다.

## 10. attempt receipts, retry receipts와 stage FSM

receipt도 signed JCS `{payload,signature}`, numeric global record와 같은
O_TMPFILE→0444→fsync→NOREPLACE→parent fsync→reopen verification을 쓴다.
receipt signature domain은
`WS-PRE-P-R006-ATTEMPT-RECEIPT-V1 || NUL || JCS(payload)`다.
receipt common exact fields:

```text
schema_version, stage, attempt_key, attempt_ordinal
challenge_id, nonce, transaction_id
scope:Scope, expected_global_head:RecordRef
plan:Physical, formal_review:Physical, skeptical_review:Physical
source:Source
request:Physical, challenge:Physical, raw_response:Physical
issuer:Identity, verifier:Identity, custodian:Identity, signer_fingerprint
issued_at, not_before, expires_at, ttl_seconds, lease_slice_seconds, hard_deadline
authorization_origin, revocation_origin
delegation_depth=0, handoff_depth, revocation_state, one_use=true
prior_attempt, prior_receipt, prior_closure
authorization_state
```

stage exact additional fields:

| receipt | exact additional fields |
|---|---|
| Stage A | `candidate_root, external_env_root, runtime_pack_roots, candidate_review` all SIGNED_ABSENT at issue; `resolved_root,T1,X1,V1,exact26` FORBIDDEN |
| Stage B | A subject/env/review ACTUAL; `resolved_root,resolved_reviews` SIGNED_ABSENT at issue; `T1,X1,V1,exact26` FORBIDDEN |
| Stage C pending | A/B sealed subjects, env/packs, regression, reviews, T1/X1/V1/exact26, application_receipt_target ACTUAL; `authorization_state=PENDING_DELEGATION`, `handoff_depth=1` |
| recovery C | all Stage C bindings ACTUAL plus `original_c_receipt,original_consume,original_incident,original_transaction,observed_progress_head,observed_checkpoint,recovery_phase`; `handoff_depth=0` |

retry receipt는 immediately prior attempt receipt와 terminal closure의
path/hash/bytes/sequence/status/reason을 ACTUAL로 결속한다. first receipt만
three prior fields에 `SIGNED_ABSENT/FIRST_ATTEMPT`를 쓴다. retry가 earlier
attempt를 건너뛰거나 동일 request/challenge/raw bytes를 재사용하면 rc2다.

stage×state exact transitions:

```text
A:
  ISSUED
  -> CONSUME_CLAIMED
  -> LEASE_ACQUIRED (-> LEASE_RENEWED*)
  -> PREPARED
  -> CLOSED_SUCCESS
  early: ISSUED|CONSUMED|LEASED -> CLOSED_INCIDENT

B:
  ISSUED
  -> CONSUME_CLAIMED
  -> LEASE_ACQUIRED (-> LEASE_RENEWED*)
  -> PREPARED
  -> DELEGATED_AND_CLOSED
  early: ISSUED|CONSUMED|LEASED -> CLOSED_INCIDENT

C pending:
  ISSUED/PENDING_DELEGATION
  -> DELEGATED_AND_CLOSED atomic activation
  pre-effective expiry/failure:
    ISSUED/PENDING_DELEGATION -> CLOSED_INCIDENT

C effective:
  EFFECTIVE_UNCONSUMED
  -> CONSUME_CLAIMED
  -> LEASE_ACQUIRED (-> LEASE_RENEWED*)
  -> PREPARED
  -> exact transaction
  -> CLOSED_SUCCESS
  incident after consume:
    CONSUMED|LEASED|PREPARED -> CLOSED_INCIDENT

recovery C:
  RECOVERY_ISSUED
  -> RECOVERY_CONSUMED
  -> RECOVERY_LEASE_ACQUIRED (-> RECOVERY_LEASE_RENEWED*)
  -> recovery operation
  -> RECOVERY_CLOSED_SUCCESS|RECOVERY_CLOSED_INCIDENT
```

`CLOSED_SUCCESS`와 delegation은 PREPARED ACTUAL 없이는 금지한다. recovery는
별도 kind family라 PREPARED를 쓰지 않고 FENCE_BOUND와 terminal evidence를
요구한다. terminal/revoked 뒤 consume/renew/prepare/delegate/reopen은 rc2다.

### 10.1 B→C pending validity gate와 atomic handoff

C pending receipt의 scope:

```text
C_ALLOW =
  exact26 target operations
  ∪ transaction journal numeric-slot writes
  ∪ hosted exact6 read/execute/raw-result writes
  ∪ APPLICATION_RECEIPT_FINALIZE at the exact application receipt path,
    including NOREPLACE creation of its one exact parent directory
C_DENY = all operations/paths - C_ALLOW
effective(C)=false until DELEGATED_AND_CLOSED
```

B→C publisher는 §11 OFD lock을 먼저 소유하고 global replay 직후 아래를 같은
critical section에서 검증한다.

```text
B state == PREPARED_EFFECTIVE
B receipt current, consumed, unrevoked
B latest lease active at handoff event_at
B PREPARED record == current compatible head predecessor
C state == ISSUED_PENDING_DELEGATION
C not_before <= handoff event_at < C expires_at
C revocation_state == UNREVOKED
C consumption_state == UNCONSUMED
C expected_global_head == current compatible head
C transaction/scope/T1/X1/V1 unchanged
unique pair_digest == H(B prepared identity, C pending identity)
```

그 뒤 one numeric global slot의 `DELEGATED_AND_CLOSED`만 publish한다.

```text
B: PREPARED_EFFECTIVE -> CLOSED_SUCCESS_INEFFECTIVE
C: PENDING_DELEGATION_INEFFECTIVE -> EFFECTIVE_UNCONSUMED
```

intermediate both-effective/both-ineffective state는 없다. stale B lease/head,
expired/not-yet-valid/revoked/consumed C, pair digest mismatch는 handoff write0다.
delegation 뒤 B renewal은 rc2다. handoff 전 C expiry는 B를 닫지 않고 pending C
`CLOSED_INCIDENT`만 기록한 뒤 fresh C attempt를 발급한다.

## 11. OFD lock, fencing epoch와 write guard

genesis-bound exact lock path:

```text
/home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-authority-r006/journal/genesis/stage-c-transaction.lock
```

genesis creation 때 empty regular file을 mode `0600`, `nlink=1`, size 0으로
NOREPLACE 생성·fsync하고 its exact dev/inode/uid/gid/mode/nlink/size를 genesis에
서명한다. 이후 create/truncate/chmod/chown/replace는 금지한다.

lock acquisition:

```text
open journal/genesis directory O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW
openat2("stage-c-transaction.lock",
        O_RDWR|O_CLOEXEC|O_NOFOLLOW,
        RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS|RESOLVE_NO_MAGICLINKS)
-> lstat(path)
-> fstat(fd)
-> both equal genesis dev/inode/uid/gid/mode0600/nlink1/size0
-> Linux fcntl(F_OFD_SETLK,
               l_type=F_WRLCK,l_whence=SEEK_SET,l_start=0,l_len=0)
```

`flock`, POSIX `F_SETLK`, blocking lock, range lock와 fallback은 없다. lock fd는
handoff publisher/C executor 또는 recovery executor process가 처음부터 끝까지
소유한다. B→C publisher와 ordinary C executor는 동일 process/FD lifetime이다.
`FD_CLOEXEC`는 유지하고 `dup`, `fork` inheritance, `pass_fds`, daemon,
`SCM_RIGHTS` 전송을 금지한다. spawned validator/target child의 fd table에 lock
inode가 없어야 한다.

lock order는 exact:

```text
OFD stage-c lock -> global numeric stream append -> transaction numeric stream
-> exact target parent/target fd
```

역순, 두 stream 동시 잠금 순서 변경과 target fd 선점은 금지한다. clean unlock은
해당 C/recovery global terminal close가 link되고 its global records parent
fsync까지 끝난 뒤 close(lock fd)로만 한다. process crash에 의한 kernel unlock은
recovery prerequisite일 뿐 clean close가 아니다.

`fencing_epoch`는 ordinary `LEASE_ACQUIRED` 또는
`RECOVERY_LEASE_ACQUIRED`의 global sequence 정수와 exact 같고 grant record
identity를 함께 결속한다. renewal은 epoch을 보존한다. transaction의 첫
mutation evidence는 `FENCE_BOUND`이고:

```text
transaction_id
origin_transaction_id
executor_kind="ORDINARY"|"RECOVERY"
attempt receipt/consume/lease RecordRefs
fencing_epoch
lock Physical identity
observed global head
observed transaction head
next target ordinal
checkpoint phase
```

를 가진다.

모든 prewrite, CAS/NOREPLACE, target/file/parent fsync, progress append,
checkpoint commit, reconcile, exact6 postcheck와 application receipt finalize는
직전과 직후에 다음 guard를 모두 검증한다.

```text
same OFD lock fd is still held by this process
lock fd/path/genesis Physical identity still equal
active lease contains current event_at
fencing_epoch == active grant global sequence
receipt and consume are active and unrevoked
global replay head is compatible with this executor
transaction replay head equals observed head
next operation/kind/target ordinal is exact
live target/checkpoint state equals expected prefix
active_stage_c_transaction_count <= 1
active_fence_count <= 1
```

하나라도 실패하면 target/checkpoint/application receipt write0이다. recovery는
fresh consume와 strictly higher epoch을 가져야 한다. stale ordinary/recovery
executor는 lock을 얻지 못하며, 나중에 깨어나도 replay/epoch guard 때문에
persistent write0이다.

## 12. transaction progress, durable prefix와 recovery

transaction manifest는 source/T1/X1/V1, exact26 ordered targets, C receipt,
application receipt path, initial global/transaction heads를 signed JCS로
NOREPLACE publish한다. transaction numeric stream의 allowed kinds:

```text
transaction record signature domain =
  WS-PRE-P-R006-TRANSACTION-RECORD-V1 || NUL || JCS(payload)
```

```text
FENCE_BOUND
PREWRITE
TARGET_CAS_COMMITTED
FILE_FSYNCED
PARENT_FSYNCED
RECONCILED_PREFIX
PREFIX_ADVANCED
CHECKPOINT_CAS_COMMITTED
POSTCHECK_PASSED
APPLICATION_RECEIPT_FINALIZE
```

transaction common exact fields:

```text
schema_version, transaction_id, progress_kind, sequence
prior_progress:RecordRef, transaction_manifest:Physical
origin_c_attempt, executor_attempt, executor_kind
consume:RecordRef, lease:RecordRef, fencing_epoch
lock:Physical, observed_global_head:RecordRef
event_at, durable_prefix, next_target_ordinal
```

kind exact additional:

| kind | fields |
|---|---|
| `FENCE_BOUND` | `observed_transaction_head, checkpoint_phase, expected_live_state` |
| `PREWRITE` | `target_ordinal,target_path,target_mode,operation,before_identity,after_candidate,cas_expected` |
| `TARGET_CAS_COMMITTED` | `prewrite:RecordRef,target_ordinal,target_path,operation,cas_expected,cas_observed,after_identity,commit_result` |
| `FILE_FSYNCED` | `commit:RecordRef,target_ordinal,after_identity,file_fsync_result` |
| `PARENT_FSYNCED` | `file_fsynced:RecordRef,target_ordinal,parent_identity,parent_fsync_result,reopened_identity` |
| `RECONCILED_PREFIX` | `inferred_ordinals,reconciled_members,durability_proofs,live_prefix_digest` |
| `PREFIX_ADVANCED` | `parent_fsynced_or_reconciled:RecordRef,prefix_ordinals,prefix_path_digest,next_target_ordinal` |
| `CHECKPOINT_CAS_COMMITTED` | `prewrite:RecordRef,target_ordinal=26,before_identity,after_identity,cas_result` |
| `POSTCHECK_PASSED` | `checkpoint_durable:RecordRef,exact6_results,hosted_env,hosted_pack` |
| `APPLICATION_RECEIPT_FINALIZE` | `postcheck:RecordRef,receipt_parent_path,receipt_parent_create_or_verify,receipt_path,receipt_sha256,receipt_bytes,tmp_fsync,link_noreplace,parent_fsync,reopened_identity` |

target ordinals 1..25는 commit→file fsync→parent fsync/reopen→PREFIX_ADVANCED다.
ordinal 26 checkpoint는 항상 last이고 그 전 prefix exact `1..25`가 durable해야
한다. checkpoint sequence는
`PREWRITE -> CHECKPOINT_CAS_COMMITTED -> FILE_FSYNCED -> PARENT_FSYNCED/reopen`
이고 이 last record가 durable prefix `1..26`을 증명한다. checkpoint 뒤
`PREFIX_ADVANCED`는 쓰지 않는다. POSTCHECK_PASSED는 그
`PARENT_FSYNCED` RecordRef를 `checkpoint_durable`로 결속한다. progress가
ahead of durable filesystem이거나 non-prefix이면 fail-stop다.

ordinary C receipt consume은 transaction 시작에 한 번만 쓸 수 있고 같은
receipt로 crash resume할 수 없다. crash 후에는 fresh recovery receipt만 쓴다.

### 12.1 recovery pre/post checkpoint

recovery lifecycle records는 global numeric stream에 있고 mutation evidence는
original transaction의 numeric stream에만 쓴다.

```text
RECOVERY_ISSUED -> RECOVERY_CONSUMED -> RECOVERY_LEASE_ACQUIRED
-> FENCE_BOUND in original transaction stream
-> recovery mutations/proofs in original transaction stream
-> RECOVERY_CLOSED_SUCCESS|RECOVERY_CLOSED_INCIDENT in global stream
```

recovery issue/consume 전 global replay, observed transaction progress head와
live checkpoint identity가 receipt에 묶인 값과 같아야 한다. recovery는 같은
OFD lock과 higher epoch을 가져야 한다.

pre-checkpoint allow:

```text
verified remaining exact26 suffix
original transaction numeric progress records
checkpoint ordinal26 only after durable 1..25
```

deny:

```text
committed durable prefix overwrite/delete
already committed target second CAS
application receipt
rollback
```

progress record가 commit보다 늦게 유실된 경우 higher-epoch recovery는 live
state가 unique prefix인지 검증한다. 각 inferred member마다 exact:

```text
lstat path
-> open O_RDONLY|O_CLOEXEC|O_NOFOLLOW
-> fstat(fd), lstat==fstat identity
-> hash/bytes/mode/nlink equals exact after candidate
-> fsync(file fd)
-> open parent O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW
-> fsync(parent fd)
-> reopen target O_NOFOLLOW
-> fstat/hash/bytes/mode/nlink equality
-> append signed RECONCILED_PREFIX
-> fsync transaction records parent
-> append PREFIX_ADVANCED
```

즉 non-durable live bytes는 signed prefix로 승격되지 않는다. non-prefix,
ambiguous member, missing/replaced inode, unexpected hash/mode/nlink, file/parent
fsync 또는 reopen failure는 target write0과 incident close다.

post-checkpoint recovery allow:

```text
hosted-cpu exact6 postcheck
transaction POSTCHECK_PASSED
APPLICATION_RECEIPT_FINALIZE
global recovery terminal close
```

target/checkpoint rewrite와 rollback은 금지한다. pre/post phase를 가로지르거나
second recovery consume, stale progress head는 rc2다.

### 12.2 application receipt finalization

exact path:

```text
docs/control/execution/goal-gates/
  WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001/
  application-receipt.json
```

이 path는 exact26과 `M_after` 밖이지만 C_ALLOW 안이다. checkpoint ordinal26
durable, hosted exact6 all PASS 뒤에만 같은 OFD lock/epoch로:

```text
open docs/control/execution/goal-gates O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW
-> mkdirat exact receipt parent mode0555 NOREPLACE
   (or verify existing expected directory identity)
-> fsync goal-gates directory
-> receipt parent open O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW
-> O_TMPFILE
-> write signed receipt complete bytes
-> fchmod 0444
-> fsync(tmp)
-> linkat(AT_EMPTY_PATH) NOREPLACE
-> fsync(parent)
-> reopen O_RDONLY|O_CLOEXEC|O_NOFOLLOW
-> lstat/fstat/hash/bytes/mode0444/nlink1 equality
-> append APPLICATION_RECEIPT_FINALIZE
-> fsync transaction records parent
```

를 수행한다. existing expected receipt는 recovery가 reopen/hash/fsync/parent
fsync 후 exact identity를 기록할 수 있지만 overwrite하지 않는다. unexpected
bytes/path/type는 incident, write0, official credit delta0이다.

## 13. C0 managed set, before/after identity와 hash formula

literal sets:

```text
S = exact seq39 checkpoint working_tree_snapshot.managed_changed_paths
|S| = 603
C = {
  scripts/run_walksafe_test_layers_20260711.sh,
  tests/requirements.lock
}
A = §17 exact 23 NOREPLACE paths
Q = {docs/control/walksafe-project-continuation-checkpoint.json}
```

current exact membership:

```text
C intersection S = {scripts/run_walksafe_test_layers_20260711.sh}
C - S = {tests/requirements.lock}
|C intersection S| = 1
A intersection S = empty
A intersection C = empty
|A| = 23
Q not in S or A or C
```

`C subset S`는 precondition이 아니며 assertion하면 rc2다.
`tests/requirements.lock`은 existing-but-unmanaged CAS target이고 successful
replacement 뒤 managed member가 된다.

```text
M_after = sort_utf8(unique(S union A union C) - Q)
|M_after| = 627
```

path hash의 유일한 공식:

```text
path_set_bytes =
  concat(UTF8(path) || LF for path in M_after ordered by UTF-8 bytes)
path_set_sha256 = SHA256(path_set_bytes)
projected_path_set_sha256 =
  762a4f9b487bba0177c72127c8bfa7211ca9900845452df2a02b1d9bad850c4d
```

content hash는 all 627 after bytes가 candidate seal되고 exact26 after hashes가
확정된 뒤에만 계산한다.

```text
content_set_bytes =
  concat(UTF8(path) || NUL ||
         ASCII(lowercase SHA-256 of final bytes) || LF
         for path in M_after ordered by UTF-8 bytes)
content_set_sha256 = SHA256(content_set_bytes)
```

future checkpoint exact equalities:

```text
working_tree_snapshot.managed_changed_path_count == 627
working_tree_snapshot.managed_changed_paths == M_after
working_tree_snapshot.path_set_sha256 == projected_path_set_sha256
working_tree_snapshot.content_set_sha256 == recomputed after content hash

session_handoff.changed_files ==
  working_tree_snapshot.managed_changed_paths

session_handoff.source_commit_or_snapshot.file_count == 627
session_handoff.source_commit_or_snapshot.path_set_sha256 ==
  working_tree_snapshot.path_set_sha256
session_handoff.source_commit_or_snapshot.content_set_sha256 ==
  working_tree_snapshot.content_set_sha256
```

`source_commit_or_snapshot.changed_files`는 current schema에 없는 field이므로
assert하거나 추가하지 않는다. current source의
`source_commit_or_snapshot.content_set_sha256 =
60eba216d34e53dea2aface304738ebb07b0067aaacdd38938ed13f99aa3a215`
를 after value로 carry-forward하면 rc2다. after content hash는 future sealed
bytes에서 재계산한다.

CAS prewrite는 §2 target identities를 다시 확인한다. requirements target의
before `nlink=4`는 허용된 exact source fact지만 after는 fresh inode/nlink1이어야
한다. runner도 fresh inode/nlink1이다. CAS expected hash/bytes/mode/dev/inode/
nlink 중 하나라도 drift하면 candidate를 current target에 맞춰 재생성하지 않고
Stage C incident로 닫는다.

candidate/resolved/env/pack/journal/reviews/transactions/raw/temp/cache/build files,
R006 plan/reviews, application receipt와 Q는 M_after 밖이다. managed member bytes는
C0/T1/X1/V1/A_C/application receipt 또는 descendant hash/path를 참조할 수 없다.
typed dependency graph와 byte scan이 self/reverse/future reference를 rc2로
거부한다.

repository source snapshot exact self-exclusions:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006-independent-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006-independent-skeptical-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r006/**
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-authority-resolved-r006/**
docs/control/execution/goal-gates/
  WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001/
  application-receipt.json
```

`**`는 runtime glob이 아니라 signed prefix record type이며 prefix와 all
descendants를 byte-prefix containment로 제외한다.

## 14. hash topology와 external reviews

```text
S0 source snapshot/self exclusions
-> E0 event semantic bytes
-> C0 after checkpoint/M_after
-> T1 exact26 target set
-> X1 authority/recovery/exact6 envelope
-> external Stage-A/Stage-B reviews
-> V1 review binding
-> A_C pending/effective C receipt
-> fenced transaction
-> application receipt
```

E0는 Stage-A transition spec/core와 S0만 참조한다. C0는 E0와 managed
formula를, T1은 S0/E0/C0를, X1은 T1을 참조한다. external review는 sealed
subject/X1을 참조하지만 V1을 모른다. V1은 manifest/review를 참조하고 A_C가
첫 V1 consumer다. self-edge, reverse/future edge, SCC와 canonical order drift는
rc2다.

Stage-B final subject:

```text
pre-p-validation-convergence-authority-resolved-r006/
  attempts/<stage-b-attempt-key>/subject/
  resolved-review-subject-manifest.json
```

manifest는 자기 path를 recursive domain에서 exact 제외하고 resolved outputs와
validation raw/result를 결속한다. review path/hash는 포함하지 않는다. subject
seal 뒤 external exact review paths:

```text
journal/reviews/stage-b/<stage-b-attempt-key>/
  regression-final-independent-review.md
  resolved-independent-review.md
```

에만 write한다. Stage C:

```text
journal/attempts/stage-c/<stage-c-attempt-key>/review-binding.json
```

은 signed JCS/NOREPLACE/0444/nlink1이고 exact fields:

```text
schema_version, stage_b_attempt_key
X1:Physical
resolved_review_subject_manifest:Physical
regression_review:Physical, regression_verdict={0,0,0}
resolved_review:Physical, resolved_verdict={0,0,0}
reviewer_identity, reviewer_signature
sealed_subject_pre_digest, sealed_subject_post_digest
```

만 가진다.

```text
H(domain,payload) =
  SHA256(ASCII(domain) || NUL || RFC8785_JCS(payload))
V1 = H("R006_RESOLVED_REVIEW_BINDING_V1", review-binding payload)
```

R006 physical plan reviews와 all attempt reviews는 repository source 밖 external
journal에 있다. review-before-seal, subject mutation, missing/tampered/replaced
review, manifest review edge, review V1 edge, V1 self-edge는 rc2다.

## 15. Stage-C hosted exact6

Stage C는 Stage-B exact2 result digests를 결속하며 full19/regression을 다시
실행하지 않는다. hosted-cpu sealed env/pack에서 다음 exact6만 수행한다.

1. live exact26와 routing manifests physical identity가 Stage-B AFTER와 일치
2. seq40 activation/supersession/checkpoint seal
3. AFTER continuation Quick
4. AFTER Goal Quick
5. runner validate `AFTER_SEQ40_V241`, discovery `132/132/0`, direct5
6. full19 #17 direct exact3와 #19 repository state/event snapshot

각 check도 §5.2 sandbox/access trace와 §11 write guard를 적용한다. exact6 all
PASS 뒤에만 application receipt를 finalize한다. checkpoint 뒤 failure는
`POSTCOMMIT_RECOVERY_REQUIRED`; rollback과 full19 rerun은 금지한다.

## 16. discovery exact132와 literal consumers

final arithmetic:

```text
UNIT28 + FUNCTIONAL23 + INTEGRATION7 + MODEL3
+ HISTORICAL53 + ACTIVE16 + PROTOTYPE2 = 132
assigned=132
unassigned=0
duplicate=0
extra=0
```

아래 132 rows가 complete registry다. `RUNNER_ALL`은 discovered selected,
`INVENTORY_ONLY`는 discovered but excluded consumer다.

```text
UNIT | RUNNER_ALL | tests/test_voice_intents.py
UNIT | RUNNER_ALL | tests/test_voice_model_integrity.py
UNIT | RUNNER_ALL | tests/test_voice_stt_policy.py
UNIT | RUNNER_ALL | tests/test_voice_stt_server.py
UNIT | RUNNER_ALL | tests/test_voice_tts.py
UNIT | RUNNER_ALL | tests/test_submission_build_io.py
UNIT | RUNNER_ALL | tests/test_submission_isolated_python.py
UNIT | RUNNER_ALL | tests/test_submission_manifest_policy.py
UNIT | RUNNER_ALL | tests/test_walksafe_node_toolchain_lock.py
UNIT | RUNNER_ALL | tests/test_pwa_release_update_checker.py
UNIT | RUNNER_ALL | tests/test_submission_promotion.py
UNIT | RUNNER_ALL | tests/test_tactile_route_policy_contract.py
UNIT | RUNNER_ALL | tests/test_android_depth_scaffold_contract.py
UNIT | RUNNER_ALL | tests/test_android_apk_model_asset_check.py
UNIT | RUNNER_ALL | tests/test_web_runtime_trace_scope.py
UNIT | RUNNER_ALL | tests/test_web_build_manifest.py
UNIT | RUNNER_ALL | tests/test_walksafe_full_rc_tooling.py
UNIT | RUNNER_ALL | tests/test_walksafe_isolated_python_bootstrap.py
UNIT | RUNNER_ALL | tests/test_walksafe_product_quality_receipt.py
UNIT | RUNNER_ALL | tests/test_walksafe_operator_attestation.py
UNIT | RUNNER_ALL | tests/test_walksafe_android_gateway_boundary_20260723.py
UNIT | RUNNER_ALL | model/test_two_model_runtime.py
UNIT | RUNNER_ALL | backend/tests/test_field_test_security.py
UNIT | RUNNER_ALL | backend/tests/test_health_readiness.py
UNIT | RUNNER_ALL | backend/tests/test_openapi_contract.py
UNIT | RUNNER_ALL | backend/tests/test_report_storage_reconciliation.py
UNIT | RUNNER_ALL | backend/tests/test_report_retention.py
UNIT | RUNNER_ALL | backend/tests/test_inference_process.py

FUNCTIONAL | RUNNER_ALL | backend/tests/test_admin_security.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_actor_rate_limit_store.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_android_debug_logs.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_backup_source.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_detect.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_report_policy.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_reports.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_reports_v2.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_request_limits.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_test_storage_isolation.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_uploads.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_yolo_inference_adapter.py
FUNCTIONAL | RUNNER_ALL | tests/test_agency_submission_receipt.py
FUNCTIONAL | RUNNER_ALL | tests/test_android_field_session_summary.py
FUNCTIONAL | RUNNER_ALL | tests/test_cloudflare_field_runner.py
FUNCTIONAL | RUNNER_ALL | tests/test_web_field_session_summary.py
FUNCTIONAL | RUNNER_ALL | tests/test_field_telemetry_retention.py
FUNCTIONAL | RUNNER_ALL | tests/test_report_retention_operational_safety.py
FUNCTIONAL | RUNNER_ALL | tests/test_report_retention_scheduler.py
FUNCTIONAL | RUNNER_ALL | tests/test_test_contamination_audit.py
FUNCTIONAL | RUNNER_ALL | tests/test_walksafe_backup_prune.py
FUNCTIONAL | RUNNER_ALL | tests/test_walksafe_environment_identity.py
FUNCTIONAL | RUNNER_ALL | tests/test_walksafe_admin_high_risk_data_delete_gate.py

INTEGRATION | RUNNER_ALL | tests/test_runtime_model_integration.py
INTEGRATION | RUNNER_ALL | tests/test_release_evidence_gate.py
INTEGRATION | RUNNER_ALL | tests/test_walksafe_backup_integrity.py
INTEGRATION | RUNNER_ALL | tests/test_local_model_registry.py
INTEGRATION | RUNNER_ALL | tests/test_submission_visual_privacy.py
INTEGRATION | RUNNER_ALL | backend/tests/test_navigation_routes.py
INTEGRATION | RUNNER_ALL | backend/tests/test_detect_v2.py

MODEL | INVENTORY_ONLY | tests/test_aihub183_dataset_integrity.py
MODEL | INVENTORY_ONLY | tests/test_aihub189_depthprediction_offline.py
MODEL | INVENTORY_ONLY | tests/test_dataset_content_integrity.py

HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_answer_review.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_baseline_approval_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_baseline_candidate.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_baseline_candidate_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_content_readiness_audit.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_independent_review_record_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_temporal_provenance_supplement_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_control_bootstrap.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_decision_interview.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_design_deliverables.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_document_preparation.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_effective_baseline.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_effective_decision_register_alignment.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_feature_policy_baseline_approval.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_feature_policy_baseline_review.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_feature_policy_document.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_feature_policy_report.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_feature_policy_resolution.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_aiml.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_deliverables_0_6.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_dev_test.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_management_discovery.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_rel_ops_cls.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_sec_ws.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_trace_7_12.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp035_correction_candidate.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_implementation_gap_analysis_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_integrated_baseline.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_legacy_web_boundary_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp004_priority_user_trace_20260724.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp005_official_environment_trace_20260724.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp006_phone_mounting_trace_20260724.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp010_first_run_registration_trace_20260725.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp018_walk_state_recovery_trace_20260724.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph_v2_2_history.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph_v2_3.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph_v2_3_history.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_npc_permission_session_trace_20260724.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_project_continuation.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_project_continuation_v2_3.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_project_questionnaire.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_requirements_draft.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_trace_integration_report.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_test_database_preflight.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_android_product_boundary.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_baseline_materialization_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_project_continuation_v2_4.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph_v2_4.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_w3_engineering_evidence_20260726.py

ACTIVE | RUNNER_ALL | tests/test_walksafe_epic01_phase_b_trace_20260722.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic01_phase_c_trace_20260722.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic02_trace_v2_2_history.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic02_trace_v2_3_history.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp011_long_lived_login_trace_20260725.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp012_multi_device_session_ledger_trace_20260726.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp013_integrated_consent_trace_20260725.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp014_permission_denial_revocation_trace_20260726.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp016_camera_centered_buttonless_screen_trace_20260726.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_goal_package.py

PROTOTYPE | INVENTORY_ONLY | tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py
PROTOTYPE | INVENTORY_ONLY | tests/test_walksafe_v2_5_control_candidate_20260730.py
```

exact transform은 UNIT stale3→HISTORICAL, ACTIVE old v2.4 exact2→HISTORICAL,
orphan seq39/R011/W3→HISTORICAL, orphan r022/v2.5→PROTOTYPE다. 그 외 role은
변하지 않는다.

history exact6은 아래이며 current argv count가 exact 0이다.

```text
tests/test_walksafe_test_database_preflight.py
tests/test_walksafe_android_product_boundary.py
tests/test_walksafe_artifact_baseline_materialization_20260722.py
tests/test_walksafe_project_continuation_v2_4.py
tests/test_walksafe_goal_graph_v2_4.py
tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
```

non-discovery direct registries:

```text
CURRENT_DIRECT | RUNNER_ALL_DIRECT |
  tests/walksafe_test_database_preflight_successor_20260731_r006.py
CURRENT_DIRECT | RUNNER_ALL_DIRECT |
  tests/walksafe_android_gateway_public_routes_successor_20260731_r006.py
CURRENT_DIRECT | FULL19_17_AND_STAGE_C_6 |
  tests/walksafe_project_continuation_v2_4_1_successor_20260731.py
CURRENT_DIRECT | FULL19_17_AND_STAGE_C_6 |
  tests/walksafe_goal_graph_v2_4_1_successor_20260731.py
CURRENT_DIRECT | FULL19_17_AND_STAGE_C_6 |
  tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py
D_DIRECT | FULL19_03_AND_18 |
  tests/walksafe_artifact_baseline_historical_successor_20260731_r006.py
D_DIRECT | FULL19_03_AND_18 |
  tests/walksafe_artifact_baseline_current_successor_20260731_r006.py
```

```text
runner_all_discovered=28+23+7+16=74
runner_all_direct=2
runner_all_execution=76
excluded_discovered=3+53+2=58
current_non_test_direct=5
D_non_test_direct=2
```

before/after routing manifests는 위 132 literal path→role→consumer, selected74,
excluded58, direct5와 D2 arrays 및 각 digest를 동결한다.

## 17. exact active target universe, count 26

ordinal은 transaction apply order다. checkpoint는 exact last다.

| ordinal | mode | exact final-relative path |
|---:|---|---|
| 1 | `CAS_REPLACE` | `tests/requirements.lock` |
| 2 | `CAS_REPLACE` | `scripts/run_walksafe_test_layers_20260711.sh` |
| 3 | `NOREPLACE` | `tests/walksafe_test_database_preflight_successor_20260731_r006.py` |
| 4 | `NOREPLACE` | `tests/walksafe_android_gateway_public_routes_successor_20260731_r006.py` |
| 5 | `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py` |
| 6 | `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_current_active_20260731.py` |
| 7 | `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_dual_control_20260731.py` |
| 8 | `NOREPLACE` | `tests/walksafe_artifact_baseline_historical_successor_20260731_r006.py` |
| 9 | `NOREPLACE` | `tests/walksafe_artifact_baseline_current_successor_20260731_r006.py` |
| 10 | `NOREPLACE` | `scripts/check_walksafe_project_continuation_v2_4_1.py` |
| 11 | `NOREPLACE` | `scripts/check_walksafe_goal_graph_v2_4_1.py` |
| 12 | `NOREPLACE` | `tests/walksafe_project_continuation_v2_4_1_successor_20260731.py` |
| 13 | `NOREPLACE` | `tests/walksafe_goal_graph_v2_4_1_successor_20260731.py` |
| 14 | `NOREPLACE` | `tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py` |
| 15 | `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/seq40-event-schema.json` |
| 16 | `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-before-seq39-v2.4.json` |
| 17 | `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json` |
| 18 | `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/superseded-v2.4.0-active-checkpoint.json` |
| 19 | `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/v2.4-supersession-record.json` |
| 20 | `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/v2.4-supersession-anchor.json` |
| 21 | `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/transition-event-seq40.json` |
| 22 | `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/static-plan-manifest-v2.4.1.json` |
| 23 | `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/control-package-manifest-v2.4.1.json` |
| 24 | `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/README.md` |
| 25 | `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/full19-successor-contract.json` |
| 26 | `CHECKPOINT_CAS_LAST` | `docs/control/walksafe-project-continuation-checkpoint.json` |

```text
ACTIVE_TARGET_COUNT=26
CAS_REPLACE=2
NOREPLACE=23
CHECKPOINT_CAS_LAST=1
DUPLICATE_PATH_COUNT=0
```

application receipt는 exact26 밖이고 §12.2에서만 finalize한다.

## 18. required negative and crash matrix

모든 pre-spawn/static negative는 rc2, `child_exec_count=0`,
`persistent_write_count=0`이다. post-mutation crash fixture는 표에 적은 durable
state 외 추가 write가 0이어야 한다.

### 18.1 numeric slot, lock, fence exact groups

| ID | fixture와 exact expected result |
|---|---|
| `SEQ-SLOT-01` | global same N, different kind concurrent link: `N.json` exact1, winner1/EEXIST1, loser replay PASS |
| `SEQ-SLOT-02` | same N/same kind/different payload: final exact1, loser persistent write0 and replay |
| `SEQ-SLOT-03` | transaction ordinary PREWRITE와 recovery FENCE_BOUND가 same N 경쟁: numeric slot exact1만 생김 |
| `SEQ-SLOT-04` | legacy `N-KIND`, duplicate numeric, gap, unexpected filename: replay rc2, append/target0 |
| `SEQ-SLOT-05` | crash before tmp fsync 또는 after tmp fsync/before link: N absent, reservation/claim 없음 |
| `SEQ-SLOT-06` | crash after link/before parent fsync와 after parent fsync: restart에서 absent 또는 one complete valid N만 인정; empty reserved slot 불가 |
| `SEQ-SLOT-07` | loser payload를 N+1에 reuse, skip/overwrite invalid N, sequence overflow: rc2 |
| `LOCK-01` | symlink/nonregular/nlink2/wrong uid/gid/mode/size, genesis dev/inode drift, parent replacement, fd-vs-lstat mismatch: pre-lock rc2 |
| `LOCK-02` | holder 대 contender: holder exact1, loser `LOCK_BUSY`, loser persistent write0 |
| `LOCK-03` | flock/POSIX fallback/mixed API, dup/SCM_RIGHTS/pass_fds/daemon inheritance: FAIL |
| `LOCK-04` | supervisor kill은 child fd 없음으로 lock release; child kill while supervisor alive는 lock busy 유지 |
| `LOCK-05` | SIGSTOP ordinary lock including lease expiry: recovery `LOCK_BUSY`; resume 후 expired CAS/progress0, incident close only |
| `FENCE-01` | ordinary crash before FENCE_BOUND: target0; recovery epoch e2>e1 |
| `FENCE-02` | epoch0/equal/decrease/not-equal grant sequence, wrong grant tuple, renewal epoch change: rc2 |
| `FENCE-03` | missing/wrong FENCE_BOUND, progress epoch mismatch, no held OFD lock: rc2/write0 |
| `FENCE-04` | stale ordinary e1 vs recovery e2: while ordinary holds lock recovery busy; after recovery close stale replay target0 |
| `FENCE-05` | stale recovery1 after recovery2 epoch e3 또는 second consume: rc2/write0 |
| `FENCE-06` | recovery injected between ordinary final replay와 CAS cannot lock; fault-forced unlock이면 CAS guard fail/write0 |
| `FENCE-07` | expiry immediately pre-CAS gives CAS0; post-CAS/pre-progress leaves durability-only state, progress0, higher recovery reconcile, second CAS0 |

global invariant fixtures가 항상 확인하는 값:

```text
active_stage_c_transaction_count <= 1
active_fence_count <= 1
lock_order == OFD -> global -> transaction -> target
clean_unlock_after == global_terminal_close_parent_fsync
recovery_lifecycle_stream == global
recovery_mutation_evidence_stream == original_transaction_numeric_stream
```

### 18.2 handoff, recovery, crash exact groups

| ID | fixture와 exact expected result |
|---|---|
| `HANDOFF-01` | pending C incident close 대 delegation race: same OFD lock+same next numeric slot, exact one transition wins |
| `HANDOFF-02` | stale B lease/head: handoff0; delegation 뒤 B renew rc2 |
| `RECOVERY-01` | stale observed transaction progress head: recovery consume/fence0 |
| `RECOVERY-02` | reconcile/checkpoint/application receipt without same lock+higher epoch: operation0 |
| `RECOVERY-03` | application receipt NOREPLACE serialization: absent create once; existing expected reopen/verify/fsync; wrong hash incident/write0 |
| `CRASH-01` | target CAS after-hash durable, pre-progress crash: higher recovery performs file+parent durability proof와 RECONCILED_PREFIX |
| `CRASH-02` | transaction terminal durable, pre-global-close crash: higher recovery global close only; target/checkpoint rewrite0 |

각 exact26 target의 prewrite, CAS/link, file fsync, parent fsync, reopen,
PREFIX_ADVANCED 전후에도 `CRASH-01` equivalent cut를 주입한다. checkpoint commit,
exact6 each check, application receipt tmp fsync/link/parent fsync와 terminal close
전후 cut도 포함한다.

### 18.3 schema, sandbox, topology와 arithmetic

| ID | rejected fixture |
|---|---|
| `SCHEMA-01` | common or required kind field missing/null/unknown; kind outside exact15 |
| `SCHEMA-02` | future field, forbidden field, wrong ACTUAL/SIGNED_ABSENT state |
| `SCHEMA-03` | renewal missing prior lease path/hash/bytes/ordinal, ordinal0/33, epoch change |
| `SCHEMA-04` | PREPARED missing subject/reviews/env/regression/transaction state |
| `SCHEMA-05` | delegation missing B prepared or C pending binding; close missing terminal evidence |
| `FSM-01` | A/B early incident rejected, or early incident incorrectly requires PREPARED |
| `FSM-02` | pending C expiry cannot close, or pending close mutates B |
| `FSM-03` | success/delegation without PREPARED; terminal state reuse |
| `HANDOFF-VALIDITY-01` | C not-yet-valid/expired/revoked/consumed/stale-head/pair digest drift |
| `PACK-01` | missing transitive ELF/shebang/Python/Node/Java/Android closure member |
| `PACK-02` | build01/build02 manifest inequality, candidate same inode, external materialization mismatch |
| `PACK-03` | device/FIFO/socket/hardlink/nlink>1/absolute/escaping/dangling/cyclic symlink |
| `SANDBOX-01` | missing/extra bwrap bind, broad host bind, host PATH/fallback/cross-env access |
| `SANDBOX-02` | before/after/full19/regression/repeat invocation missing sandbox or six-file raw sibling |
| `TRACE-01` | trace drop/overflow/detach/missing event, sibling raw/env/pack access |
| `FULL19-01` | either env not 19/19 actual rc0, #17 not direct3, #5~14 host access nonzero |
| `REGRESSION-01` | regression-final runs before lanes or accepts fixed241 without collected digest |
| `C0-01` | `C subset S` assertion, requirements member omitted, count626 |
| `C0-02` | A overlap S/C, Q member, M_after !=627, path hash not exact |
| `C0-03` | stale content hash `60eba216d34e53dea2aface304738ebb07b0067aaacdd38938ed13f99aa3a215` carry-forward, content hash before byte seal |
| `C0-04` | source schema에 nonexistent `source_commit_or_snapshot.changed_files` 추가/assert |
| `CAS-01` | requirements before nlink/inode/hash drift 또는 after hardlink/nlink4 reuse |
| `TOPOLOGY-01` | managed C0/V1/receipt ref, manifest review ref, review V1/self ref, SCC |
| `REVIEW-01` | Stage-B review in repo, review before seal, review/subject mutation, verdict nonzero |
| `DISCOVERY-01` | role sum!=132, assigned/unassigned/duplicate/extra !=132/0/0/0 |
| `DISCOVERY-02` | orphan5/history6/direct5/D2 literal drift or history current argv nonzero |
| `RUNNER-01` | selected74+excluded58 mismatch, direct/discovery cross assignment, runner !=76 |
| `RUNNER-02` | flag default/inference, selector third state, manifest/root/package/tail drift; child spawn |
| `TARGET-01` | exact26 mode/count/order/path drift, duplicate, checkpoint not last |
| `RECEIPT-01` | application receipt treated as exact26/M_after or omitted from C_ALLOW |
| `RECONCILE-01` | signed reconcile before file+parent fsync/reopen/hash equality |
| `RECOVERY-SCOPE-01` | ordinary receipt resume, recovery second consume, pre/post scope crossing, rollback |
| `OFFICIAL-01` | 126/257 or 0/279 promotion, waived gate, deployment/release/device credit |
| `RETRY-01` | fixed `-001`, reused key/root/review/raw, skipped prior attempt, attempt17/deadline reset |

## 19. stop conditions

- R006 physical review verdict가 각각 exact `0/0/0`이 아니거나 Stage A authority
  request가 별도 승인되지 않음
- R005 plan/formal/skeptical provenance fingerprint drift
- current checkpoint/tail/source/CAS target fingerprint drift
- journal genesis/signature/numeric replay/gap/fork/invalid slot
- OFD lock path/identity/mode drift, lock busy, forbidden fallback/inheritance
- active transaction/fence count가 1 초과 또는 lock order/lifetime 위반
- stale/expired/revoked receipt/lease, epoch/head/next-operation mismatch
- attempt collision, prior chain omission, max16/renew32/hard deadline 초과
- field-state/schema/FSM/handoff pending validity mismatch
- candidate/resolved/env/pack/review tombstone or seal mismatch
- runtime closure/equality/inode/link/bwrap/access-trace failure
- Phase0 successor hash 또는 transformation allowlist 밖 delta
- Stage-A lane/DAG/exact builder/member/template tree drift
- discovery literal132, direct registries, runner76/history6 mismatch
- either environment full19/regression/repeat non-PASS
- C0 member/count/path hash/content timing/equality mismatch
- target before identity/exact26 mode/order/after nlink mismatch
- managed/review/V1 hash topology cycle 또는 external review nonzero
- ordinary resume, non-prefix transaction, checkpoint-before-25
- reconcile durability proof, recovery higher epoch/scope mismatch
- Stage-C hosted exact6 non-PASS
- application receipt wrong path/hash/durability 또는 C_ALLOW omission
- official ceiling/delta mismatch
- R007 reuse, P seq39→40, P17 build

## 20. R005 finding remediation matrix

| exact finding | R006 closure |
|---|---|
| formal `PRE-P-R005-BLOCKING-001` runtime-pack/sandbox missing | §5 exact env×build tree, closure, recursive manifest, bwrap binds, per-invocation access trace |
| formal `BLOCKING-002` kind schemas non-materializable | §9 exact tagged union, nested types, per-kind fields와 state matrix |
| formal `BLOCKING-003` pending C expiry transition missing | §10 pending C direct incident FSM와 A/B early incident |
| formal `BLOCKING-004` application receipt outside C_ALLOW | §10.1 C_ALLOW와 §12.2 finalize |
| formal `BLOCKING-005` reconcile non-durable live state | §12.1 file+parent fsync, reopen/hash 후 signed reconcile |
| skeptical `PRE-P-R005-BLOCKING-001` standalone exact tree missing | §4/§6 builder/template/member/source→target tree |
| skeptical `BLOCKING-002` sequence path permits two kinds | §8 numeric-only single slot와 §18 `SEQ-SLOT-01..07` |
| skeptical `BLOCKING-003` pending/early close contradiction | §9.1 SIGNED_ABSENT과 §10 stage FSM |
| skeptical `BLOCKING-004` stale executor unfenced | §11 OFD lock, epoch, all-write guard와 LOCK/FENCE negatives |
| skeptical `BLOCKING-005` reconcile durability | §12.1 exact durability proof |
| skeptical `BLOCKING-006` ordinary C receipt write forbidden | §10.1/§12.2 exact C_ALLOW operation |
| skeptical `BLOCKING-007` false `C subset S` | §13 exact intersection/difference, M_after627 |
| skeptical `MAJOR-001` canonical field-state matrix absent | §9 ACTUAL/SIGNED_ABSENT/FORBIDDEN |
| skeptical `MAJOR-002` B→C current pending validity gate absent | §10.1 current time/revocation/consume/head/pair exact gate |

## 21. Quick2, fingerprint와 self-check

Quick2는 Stage-C exact6의 check 3/4인:

```text
AFTER continuation Quick = PASS
AFTER Goal Quick = PASS
```

이며 full19 rerun을 뜻하지 않는다.

plan freeze/read-only self-check:

```text
R006 regular && !symlink && nlink==1 && terminal_LF && NUL_free
R005 plan == 675645378e7f87d002cfba2e109bc72712c6998dd620a53de42864f8c7f3a25d/39839/997
R005 formal == a8319b8d938afa8b1d5204d109f0ae5fd82be38b7625b424e83fd382a36f46e7/21990/455
R005 skeptical == de42d1a04dd0bd902f9a63aac5ec1a44ed0171685b718ad60c43be3285d20ebe/21395/525
prior revision suffix paths occur only in immutable provenance
authority A..G exact7 == ABSENT_DENY_ALL
Stage-A member tree has no unlisted regular/symlink/special member
runtime pack env2 x build01/build02/candidate exact6
bwrap clause applies to before/after/full19/regression/repeat
global final slot == records/<12digit>.json
transaction final slot == transactions/<tid>/records/<12digit>.json
kind appears only in signed body; claim/head/sequence-directory count0
ACTUAL/SIGNED_ABSENT/FORBIDDEN matrix complete; null/unknown/missing rejected
B PREPARED + C pending valid -> DELEGATED_AND_CLOSED exact1
active_stage_c_transaction_count<=1 && active_fence_count<=1
lock API == F_OFD_SETLK whole-file only
fencing_epoch == lease-acquired global sequence
every target/progress/fsync/postcheck/receipt op has lock+epoch guard
ordinary C consume max1; fresh recovery consume max1
RECONCILED_PREFIX only after file+parent fsync/reopen/hash
discovery role counts == 28/23/7/3/53/16/2
discovery assigned/unassigned/duplicate/extra == 132/0/0/0
runner all == discovered74 + direct2 == 76
history exact6 current argv0; direct5/D2 literal
exact26 count/modes == 26/2/23/1; unique paths26; checkpoint ordinal26
M_after == sorted((S union A union C)-Q), count627
M_after path hash == 762a4f9b487bba0177c72127c8bfa7211ca9900845452df2a02b1d9bad850c4d
after content hash recomputed only after all after bytes sealed
working snapshot paths == handoff.changed_files
working/source counts and path/content hashes equal
source_commit_or_snapshot.changed_files assertion count0
application receipt outside exact26/M_after and inside C_ALLOW
Stage-C hosted exact6; full19 rerun count0; Quick2 PASS
official claims exact and all deltas0
```

이 plan 완료는 authority/build/apply가 아니다.

```text
STAGE_A_REQUEST_ALLOWED=false
STAGE_A_AUTHORITY=ABSENT_DENY_ALL
STAGE_B_AUTHORITY=ABSENT_DENY_ALL
STAGE_C_AUTHORITY=ABSENT_DENY_ALL
STAGE_D_AUTHORITY=ABSENT_DENY_ALL
STAGE_E_AUTHORITY=ABSENT_DENY_ALL
STAGE_F_AUTHORITY=ABSENT_DENY_ALL
STAGE_G_AUTHORITY=ABSENT_DENY_ALL
PRE_P_CANDIDATE_BUILT=false
PRE_P_RESOLVED=false
PRE_P_APPLIED=false
V2_4_1_ACTIVE=false
R007_SUCCESSOR_BUILT=false
P_CANDIDATE_BUILT=false
P_APPLIED=false
```

R006 physical formal/skeptical review가 각각 exact `0/0/0`으로 닫힌 뒤 final
handoff에서만 Stage A request를 사용자에게 제시한다.
