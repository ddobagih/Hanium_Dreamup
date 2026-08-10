# PRE-P Validation Convergence Design/Build Plan R002

## 1. 문서 통제와 predecessor

| 항목 | 값 |
|---|---|
| document_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R002` |
| 작성일 | `2026-07-31` |
| status | `NON_EFFECTIVE_PLAN_ONLY` |
| current build authority | `ABSENT_DENY_ALL` |
| current apply authority | `ABSENT_DENY_ALL` |
| P17 | `P17_BUILD_DEFERRED` |
| predecessor plan | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R001.md` |
| predecessor SHA-256 | `b25e9bd0a71e86d2bd0b8176369053ed4db5896cb3055e17591b19135ec61962` |
| predecessor review | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R001-independent-review-r001.md` |
| predecessor review SHA-256 | `95c485bfae33f3a8d0e0a783ef9d0c602fc72d779597d7144ca87530df0e4cd0` |
| predecessor verdict | `REJECTED_PLAN_DO_NOT_EXECUTE; 5/2/0` |
| stale R007 path | `R022-CONTROL-MIGRATION-CANDIDATE-R007.md` |
| stale R007 SHA-256 / bytes | `2c0eb19da8b9a2df77cb43b2ffd169583591f0a23150d9ef5eff00cf39327610` / `77,365` |

R001과 그 review는 불변 rejection history다. 수정·실행·부분 재사용하지 않는다.
R002가 독립검수 `0/0/0`을 받아도 아래 1단계 권한 전에는 candidate 파일을
하나도 만들지 않는다.

```text
STATUS=NON_EFFECTIVE_PLAN_ONLY
PRE_P_BUILD_AUTHORITY=ABSENT
PRE_P_APPLY_AUTHORITY=ABSENT
R007_SUCCESSOR_DESIGN_AUTHORITY=ABSENT
P_CANDIDATE_BUILD_AUTHORITY=ABSENT
P_APPLY_AUTHORITY=ABSENT
P17_BUILD_DEFERRED=true
APPLY_ALLOWED=false
CHECKPOINT_WRITE_ALLOWED=false
CANONICAL_PRODUCT_CREDIT_DELTA=0
```

## 2. 현재 read-only 기준선

2026-07-31 R002 작성 직전 현재 active root에서 Quick2를 재실행했다.

```text
python3 -B scripts/check_walksafe_project_continuation_v2_4.py
→ PASS
python3 -B scripts/check_walksafe_goal_graph_v2_4.py
→ PASS (26 managed Goals, ready 2, focus WS-GOAL-EPIC-03, ACTIVE)
```

| 기준 | exact 값 |
|---|---|
| active package | `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4`, `ACTIVE` |
| checkpoint | `docs/control/walksafe-project-continuation-checkpoint.json` |
| checkpoint SHA / bytes / schema | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` / `1,329,415` / `1.25.0` |
| tail | sequence `39`, `c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a` |
| managed snapshot | `603`, path `e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1`, content `69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a` |
| session handoff before | file `603`, path `e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1`, content `60eba216d34e53dea2aface304738ebb07b0067aaacdd38938ed13f99aa3a215` |
| v2.4 static manifest | `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07`, `39,534` bytes |
| canonical Gap/Backlog | r021/r021, unchanged |
| formal/device/gate/release | `0/279 PASS`, `0/0`, `0/5`, `NOT_ELIGIBLE` |

`working_tree_snapshot.content_set_sha256`와
`session_handoff.source_commit_or_snapshot.content_set_sha256`는 서로 다른
정의역의 현재 값이다. after builder는 checker가 정의한 각 계산법으로 둘을
별도 재계산하며 한 값을 다른 필드에 복사하지 않는다.

현재 validation debt는 다음과 같이 숨기지 않는다.

- canonical discovery/configuration: discovered 132, assigned 127, orphan 5,
  legacy runner validate rc2
- 첫 regression A: `450 PASS / 7 FAIL`
- 두 번째 regression B: `241 tests NOT_RUN`
- current locked venv: Pillow `12.2.0`, 새 기준 `12.3.0`에 stale

## 3. 권한 5단계와 Phase 0.5 hard stop

| 단계 | exact authority scope | 허용 | 금지 |
|---:|---|---|---|
| 1 | `PRE_P_VALIDATION_CONVERGENCE_DESIGN_BUILD_ONLY` | 예약 candidate namespace의 add-only build/review | active consumer switch, apply, checkpoint, P |
| 2 | `PRE_P_VALIDATION_CONVERGENCE_ATOMIC_APPLY_ONLY` | reviewed aggregate의 exact atomic apply/recovery | R007/P build, product/canonical 변경 |
| 3 | `POST_SEQ40_R007_SUCCESSOR_DESIGN_REVIEW_ONLY` | actual seq40에 결속한 add-only R007-successor design/review | P physical candidate/apply |
| 4 | `P_CANDIDATE_BUILD_REVIEW_ONLY` | reviewed R007-successor에 따른 P seq40→41 candidate build/review | P apply |
| 5 | `P_ATOMIC_APPLY_ONLY` | exact reviewed P candidate의 fresh apply | 범위 밖 write |

### Phase 0.5

R002 독립검수가 findings `0/0/0`이어도 exact 1단계 authority receipt가 없으면:

```text
STOP=PRE_P_VALIDATION_CONVERGENCE_DESIGN_BUILD_ONLY_AUTHORITY_ABSENT
CANDIDATE_WRITE_COUNT=0
USER_QUESTION_DEFERRED_TO_FINAL_HANDOFF=true
```

사용자 질문은 내부 설계·검수 종료 뒤 최종 handoff에서 한 번에 모은다. 그러나
질문을 미룬다는 규칙이 build 선행 권한을 대체하지 않는다. 1단계 receipt는
R002와 R002 review의 path/hash/bytes, 허용 candidate root, 만료·nonce와
`active_write=false`, `apply=false`, `P=false`를 직접 결속해야 한다.

각 후속 단계는 predecessor 결과의 exact hash에 결속한 별도 fresh authority다.
한 응답으로 1~5단계를 합치거나 이전 승인을 승계하지 않는다.

## 4. 실행 DAG와 freeze

```text
Phase 0 read-only + Phase 0.5 authority
                 |
         +-------+-------+
         v               v
     A lock            C exact5
     build/review      helper/review
         +-------+-------+
                 v
       B runner/routing/helper
          (A+C frozen 의존)
                 v
     D historical/current/full19
        (A+B+C frozen 의존)
                 v
 aggregate + isolated projection
 + apply-envelope independent review
                 v
          STAGED_NOT_APPLIED
                 |
       fresh authority stage 2
```

A와 C만 병렬 가능하다. B는 A/C candidate와 review hash를 final-pin한다. D는
A/B/C를 final-pin한다. aggregate는 A/B/C/D와 모든 review를 final-pin한다.
선행 bytes가 바뀌면 모든 downstream review와 digest를 폐기하고 add-only
successor를 만든다. patch-in-place하지 않는다.

## 5. expected NOREPLACE paths

아래 root와 모든 member는 미래 1단계 authority 뒤에만 생성한다. 모두 regular,
non-symlink, `nlink=1`, NOREPLACE다.

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r002/
    candidate-archive-manifest.json
    exclusions-manifest.json
    lane-a/
      builders/build-tests-requirements-lock.py
      builder-manifest.json
      build-01/tests/requirements.lock
      build-02/tests/requirements.lock
      candidate/tests/requirements.lock
      raw/local-backend-test-clean-install.json
      raw/hosted-cpu-lock-clean-install.json
      raw/environment-attestation.json
      w5-lock-successor-receipt-r001.json
      w5-lock-successor-manifest-r001.json
      w5-lock-successor-commands-r001.json
      w5-lock-successor-receipt-r001-independent-review.md
      lane-review-r001.md
    lane-b/
      builders/build-test-layer-successor.py
      builder-manifest.json
      candidate/scripts/run_walksafe_test_layers_20260711.sh
      candidate/tests/walksafe_test_database_preflight_successor_20260731_r002.py
      routing-manifest.json
      raw/validate.json
      raw/direct-supplemental.json
      lane-review-r001.md
    lane-c/
      builders/build-gateway-exact5-successor.py
      builder-manifest.json
      candidate/tests/walksafe_android_gateway_public_routes_successor_20260731_r002.py
      exact5-contract.json
      raw/positive.json
      raw/negative-config.json
      raw/negative-router.json
      raw/negative-openapi.json
      lane-review-r001.md
    lane-d/
      builders/build-historical-current-control-successor.py
      builder-manifest.json
      candidate/scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py
      candidate/scripts/check_walksafe_artifact_baseline_current_active_20260731.py
      candidate/scripts/check_walksafe_artifact_baseline_dual_control_20260731.py
      candidate/tests/walksafe_artifact_baseline_historical_successor_20260731_r002.py
      candidate/tests/walksafe_artifact_baseline_current_successor_20260731_r002.py
      full19-impact-matrix.json
      full19-successor-contract.json
      raw/historical.json
      raw/current.json
      raw/dual-negative.json
      lane-review-r001.md
    aggregate/
      builders/build-aggregate.py
      builders/build-projection.py
      projection-contract.json
      regression-contract.json
      source-manifest-before.json
      source-manifest-after.json
      source-snapshot-before.json
      candidate-member-manifest.json
      exact-target-transition-set.json
      raw/live-quick2-before.json
      raw/stage-before/
      raw/stage-after/
      raw/regression-a.json
      raw/regression-b.json
      raw/after-quick2-repeat.json
      projection-receipt.json
      validation-receipt.json
      aggregate-independent-review-r001.md
    after-control/
      docs/control/goals/walksafe-completion-graph-v2-4-1/static-plan-manifest-v2.4.1.json
      docs/control/goals/walksafe-completion-graph-v2-4-1/control-package-manifest-v2.4.1.json
      docs/control/goals/walksafe-completion-graph-v2-4-1/README.md
      docs/control/goals/walksafe-completion-graph-v2-4-1/transition-event-seq40.json
      docs/control/goals/walksafe-completion-graph-v2-4-1/full19-successor-contract.json
      scripts/check_walksafe_project_continuation_v2_4_1.py
      scripts/check_walksafe_goal_graph_v2_4_1.py
      tests/walksafe_project_continuation_v2_4_1_successor_20260731.py
      tests/walksafe_goal_graph_v2_4_1_successor_20260731.py
      staged-after-checkpoint.json
      source-snapshot-after.json
      after-control-manifest.json
    apply/
      exact-target-transition-set.json
      application-transaction-plan.json
      recovery-contract.json
      postcheck-contract.json
      receipt-schema.json
      apply-envelope.json
      application-receipt-path-reservation.json
```

각 manifest는 UTF-8 strict JSON, duplicate key 금지, sorted-key compact
canonicalization, terminal LF 규칙을 선언한다. archive와 exclusions의 exact
path set/digest가 없거나 extra member가 있으면 build/review FAIL이다.

`exclusions-manifest.json`은 최소 voice/submission locks, 기존 R001/review,
기존 R007, historical receipt/materializer/tests, canonical r021,
product/artifact/formal/device/gate/release 경로를 exact digest로 제외한다.

## 6. Lane A — lock과 두 clean environments

### 6.1 범위와 lineage

변경 후보는 `tests/requirements.lock` exact 한 파일이다.

- backend source/lock과 hosted CPU lock: Pillow 12.3.0
- current tests lock: Pillow 12.2.0
- mismatch: exact 1
- voice/submission: 독립 환경, `EXCLUDED`

W5 successor receipt/manifest/commands는 predecessor W5
receipt/manifest/commands의 exact path/hash/bytes, CPython `3.12.13`,
pip-tools `7.6.0`, pip/index/cache policy,
input constraints, 두 build raw output, delta inventory, 두 clean install,
`pip check`, installed Pillow `12.3.0`/pytest `8.4.2`와 A candidate/review hash를
결속한다.
기존 W5나 active lock은 수정하지 않는다.

### 6.2 deterministic build와 validation

같은 frozen tool/input/env로 build-01과 build-02를 독립 empty directory에
생성하고 byte-exact 동일해야 한다. 그 뒤 candidate로 NOREPLACE copy한다.

둘 다 새 attested clean environment에서 PASS해야 한다.

| environment | input | 필수 결과 |
|---|---|---|
| local combined | `backend/requirements.lock` + candidate `tests/requirements.lock` | hash install rc0, `pip check` rc0, CPython 3.12.13, Pillow 12.3.0, pytest 8.4.2 |
| hosted CPU projection | `tests/general-quality-cp312-linux-x86_64-cpu.lock` | `--require-hashes --only-binary=:all: --no-compile`, rc0, CPython 3.12.13, Pillow 12.3.0, pytest 8.4.2 |

현재 locked venv의 Pillow 12.2.0은 stale evidence이며 사용 금지다. resource
부족은 `NOT_RUN_RESOURCE_BLOCKED`, PASS 아님이다.

환경 attestation은 interpreter realpath/SHA, version, platform, empty-before
inventory, site-packages path, installed distribution/version/RECORD digest,
lock hash, argv/env/timeout/output hash와 rc를 포함한다.

## 7. Lane C — exact5 direct helper

helper는 `test_` prefix가 없는 non-discovery/direct-only 파일이다. current
config/router/OpenAPI exact path+method set을 직접 비교한다.

```text
/api/field-session: GET POST DELETE
/api/field-walk: GET POST
/api/navigation/walking: POST
/api/navigation/destinations/search: GET
/api/reports/v2: POST
```

기존 checker를 호출하는 positive path와 config/router/OpenAPI 각각의
missing/extra/method-drift temp fixture를 가진다. historical exact4와 current
exact5 delta는 `/api/field-walk` exact1이다. 기존 checker/test와 FP012
seq27~31/history/receipt는 수정하지 않는다.

## 8. Lane B — exact routing successor

### 8.1 managed discovery와 historical routing

canonical inventory는 계속:

```text
DISCOVERED=132
ASSIGNED=132
UNASSIGNED=0
DUPLICATE=0
CURRENT_DIRECT_SUPPLEMENTAL=2
```

UNIT에서 다음 stale exact3을 제거하되 bytes와 assigned inventory는
`HISTORICAL` exact1씩으로 보존한다.

```text
tests/test_walksafe_test_database_preflight.py
tests/test_walksafe_android_product_boundary.py
tests/test_walksafe_artifact_baseline_materialization_20260722.py
```

orphan exact5 routing:

| path | routing |
|---|---|
| `tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py` | `ACTIVE_SESSION_CONTROL`, `all` explicit |
| `tests/test_walksafe_phase1_exact257_successor_r011_20260729.py` | `HISTORICAL_TARGETED_ONLY` |
| `tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py` | `HISTORICAL_TARGETED_ONLY` |
| `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `HISTORICAL_TARGETED_ONLY` |
| `tests/test_walksafe_w3_engineering_evidence_20260726.py` | `HISTORICAL_TARGETED_ONLY` |

`CURRENT_DIRECT_SUPPLEMENTAL` exact2:

```text
tests/walksafe_test_database_preflight_successor_20260731_r002.py
tests/walksafe_android_gateway_public_routes_successor_20260731_r002.py
```

둘은 non-`test_`라 discovery 132에 포함하지 않지만 `run_unit`과 `all`에서 exact
path로 명시 실행한다. B helper는 current runner의 pytest invocation string
exact4와 attested environment required/no-fallback를 검증한다. D helper는 runner
밖 full19 index 3/18에서만 실행한다.

B build/review는 A environment attestation과 C helper/review hash를 input으로
final-pin한다. sealed active runner는 apply 전까지 바꾸지 않는다.

## 9. required Python environment

full19 index 15~18은 다음 required variables/receipt 없으면 실행 전 rc2다.

```text
WALKSAFE_ATTESTED_TEST_PYTHON=<absolute regular executable>
WALKSAFE_TEST_ENV_ATTESTATION=<absolute regular JSON receipt>
```

default `.venv`, current locked venv 또는
`WALKSAFE_LOCKED_TEST_PYTHON:-<fallback>` 사용을 제거한다. checker는 매 command
전후 CPython 3.12.13, Pillow 12.3.0, pytest 8.4.2, interpreter/file/lock/site
digest와 attestation signature/hash를 검증한다. 15~18은 같은 immutable env
identity를 사용하며 drift면 remaining command는 `NOT_RUN_ENVIRONMENT_DRIFT`다.

## 10. full19 exact impact matrix

새 ordered19 digest는 A/B/C freeze → D final-pin 뒤 딱 한 번 계산한다.

| # | ID | 판정 | successor 계약 |
|---:|---|---|---|
| 1 | `CONTINUATION` | CHANGED | v2.4.1 successor checker/static/package, explicit staged root/checkpoint |
| 2 | `GOAL_GRAPH` | CHANGED | v2.4.1 successor checker/static/package, explicit staged root/checkpoint |
| 3 | `BASELINE_MATERIALIZATION` | CHANGED | D dual validator; historical+current 둘 다 PASS |
| 4 | `ANDROID_GATEWAY_BOUNDARY` | CHANGED | C direct helper가 existing checker positive + exact negative를 실행 |
| 5 | `NODE_TOOLCHAIN_PRE` | UNAFFECTED | predecessor exact command bytes/hash carry |
| 6 | `GATEWAY_TYPECHECK` | UNAFFECTED | predecessor exact command bytes/hash carry |
| 7 | `GATEWAY_TEST` | UNAFFECTED | predecessor exact command bytes/hash carry |
| 8 | `GATEWAY_BUILD` | UNAFFECTED | predecessor exact command bytes/hash carry |
| 9 | `WEB_TEST` | UNAFFECTED | predecessor exact command bytes/hash carry |
| 10 | `WEB_LINT` | UNAFFECTED | no-LF command SHA `0293d54ea860df64ad8bc21214902488a4062f3c0809ea9c7ca69be35fbfa35b` carry |
| 11 | `WEB_TYPECHECK` | UNAFFECTED | predecessor exact command bytes/hash carry |
| 12 | `WEB_BUILD` | UNAFFECTED | predecessor exact command bytes/hash carry |
| 13 | `NODE_TOOLCHAIN_POST` | UNAFFECTED | predecessor exact command bytes/hash carry |
| 14 | `ANDROID_UNIT_ASSEMBLE_LINT` | UNAFFECTED | predecessor exact command bytes/hash carry |
| 15 | `TEST_LAYER_REGISTRY_VALIDATE` | CHANGED | B runner + required attested env + SILENT_SUCCESS checker/path/hash binding |
| 16 | `FIELD_AND_RELEASE_PYTEST` | CHANGED | exact predecessor test list/argv tail carry; attested Python prefix |
| 17 | `GOAL_CONTROL_PYTEST` | CHANGED | attested Python; seq39 test exact explicit; predecessor test list carry |
| 18 | `CONTROL_AND_TRACE_PYTEST` | CHANGED | attested Python; legacy baseline test argv 제거; B preflight + D history/current direct helpers 추가; existing trace9 + deselect6 exact carry |
| 19 | `REPOSITORY_STATE` | CHANGED | v2.4.1 successor continuation/static contract와 seq40 event-scoped snapshot |

5~14는 단순 `UNAFFECTED` 문자열로 끝내지 않는다. `full19-impact-matrix.json`이
각 predecessor command raw UTF-8 bytes, no-LF SHA-256, executable/module role,
argv/cwd/env/input과 successor 동일성을 독립 재계산한다. 하나라도 다르면
`CHANGED`로 재분류하고 D review를 다시 한다.

15의 SILENT_SUCCESS는 empty stdout/stderr, rc0만으로 판정하지 않고 successor
checker path/hash, runner path/hash, attestation path/hash와 exact command digest를
결속한다.

16은 기존 exact test paths, 17은 기존 exact paths에 seq39 명시 routing, 18은
기존 trace exact9와 deselect exact6 및 새 direct paths를
`full19-successor-contract.json` 배열로 전부 열거한다. count-only receipt는
금지한다.

## 11. regression A/B exact contract

`regression-contract.json`은 다음 두 suite를 순서대로 가진다.

```text
A: prior observed 450 PASS / 7 FAIL
B: prior 241 tests NOT_RUN
```

각 suite는 다음을 전부 포함해야 하며 하나라도 없으면 Phase 1 build STOP이다.

- exact argv token array와 cwd
- ordered test file/nodeid array와 per-file hash
- interpreter/environment attestation hash
- env allowlist와 unset list
- timeout, output byte cap, stdout/stderr raw paths
- discovery nodeid manifest와 digest
- expected routing class
- rc/result schema와 no-skip/no-count-only rule

검증은 A를 끝까지 기록한 뒤 A가 PASS일 때만 B를 실행한다. A FAIL이면 B는
정확히 `NOT_RUN_UPSTREAM_REGRESSION_A`, 전체 FAIL이다. 최종 acceptance는
A/B 모두 actual rc0, FAIL 0이다. 기존 450/7 또는 241 count만 재기록한 receipt는
무효다.

## 12. isolated projection

### 12.1 filesystem

projection root는 repository 밖 `mkdtemp`다. fallback 없는 exact model:

```text
/usr/bin/bwrap
  --die-with-parent
  --new-session
  --unshare-all
  --proc /proc
  --dev /dev
  --tmpfs /tmp
  --dir /work
  --ro-bind /usr /usr
  --symlink usr/bin /bin
  --symlink usr/lib /lib
  --symlink usr/lib64 /lib64
  --ro-bind /etc /etc
  --ro-bind <ATTESTED_ENV_ROOT_REALPATH> /opt/walksafe-test-env
  --ro-bind <ACTIVE_ROOT_REALPATH> /source
  --bind <OUTSIDE_REPO_MKDTEMP_REALPATH> /work/walksafe
  --dir /tmp/home
  --chdir /work/walksafe
  --clearenv
  --setenv HOME /tmp/home
  --setenv PATH /opt/walksafe-test-env/bin:/usr/bin:/bin
  --setenv PYTHONDONTWRITEBYTECODE 1
  -- <EXACT_COMMAND_ARGV...>
```

`<ACTIVE_ROOT_REALPATH>`는
`/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`다. 나머지 두
runtime realpath와 final command argv는 builder가 pre-exec double-stat하고
`projection-contract.json`의 typed token에 치환한다. 치환 완료 argv 배열,
NUL-safe digest, bwrap executable hash/version과 각 bind source inode/mode를
receipt에 기록한다. `--unshare-all`의 network namespace 외 별도 network
share/회복 옵션은 금지한다.

`builders/build-projection.py`는 `/source`의 source manifest를 double-read한 뒤
`.git`을 포함해 `/work/walksafe`로 복제한다.

- parent는 active/candidate/projection root를
  `O_PATH|O_DIRECTORY|O_CLOEXEC`로 열고 root FD별 realpath/dev/inode/uid/gid/mode를
  전후 기록한다. member 해석은 root FD 기준
  `RESOLVE_BENEATH|RESOLVE_NO_MAGICLINKS|RESOLVE_NO_SYMLINKS`와 exact allowlist를
  사용한다.
- source read allowlist, candidate overlay read allowlist, projection write
  allowlist와 runtime output write allowlist를 path+mode+owner digest로 동결한다.
  active root write allowlist는 empty다.
- reflink 시도 → source/destination inode가 다름을 검증
- reflink 불가면 byte-copy
- hardlink 금지
- 모든 staged regular file `nlink=1`
- live `tests/requirements.lock` nlink4와 일부 checker nlink3는 live inode를
  재사용하지 않고 반드시 새 staged inode로 복제
- final-relative overlay만 허용
- symlink, device, FIFO, path traversal, duplicate target 거부

### 12.2 BEFORE/AFTER worlds

| world | checkpoint/control | 목적 |
|---|---|---|
| LIVE | active root seq39/v2.4 | read-only precondition과 no-write identity |
| BEFORE | copied seq39/v2.4 | projection faithful-copy Quick2 |
| AFTER | A/B/C/D + v2.4.1 staged checkpoint seq40 | successor Quick2와 regressions |

stage checker의 `__file__`, cwd, import root와 repository root는 모두 exact
`/work/walksafe` 아래여야 한다. `/source` module import 또는 active absolute path
exec가 발견되면 FAIL이다. 각 checker/test command는 fresh process다.

Quick2 exact argv는 다음 네 배열이다. successor checker는 같은 CLI를 구현해야
하며 `<PY>`는 exact `/opt/walksafe-test-env/bin/python3.12` regular executable로
attestation에 결속한다.

```text
BEFORE-CONTINUATION:
[<PY>,-B,/work/walksafe/scripts/check_walksafe_project_continuation_v2_4.py,
 --root,/work/walksafe,
 --checkpoint,docs/control/walksafe-project-continuation-checkpoint.json,
 --manifest,docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json]
BEFORE-GOAL:
[<PY>,-B,/work/walksafe/scripts/check_walksafe_goal_graph_v2_4.py,
 --root,/work/walksafe,
 --checkpoint,docs/control/walksafe-project-continuation-checkpoint.json,
 --manifest,docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json]
AFTER-CONTINUATION:
[<PY>,-B,/work/walksafe/scripts/check_walksafe_project_continuation_v2_4_1.py,
 --root,/work/walksafe,
 --checkpoint,docs/control/walksafe-project-continuation-checkpoint.json,
 --manifest,docs/control/goals/walksafe-completion-graph-v2-4-1/static-plan-manifest-v2.4.1.json]
AFTER-GOAL:
[<PY>,-B,/work/walksafe/scripts/check_walksafe_goal_graph_v2_4_1.py,
 --root,/work/walksafe,
 --checkpoint,docs/control/walksafe-project-continuation-checkpoint.json,
 --manifest,docs/control/goals/walksafe-completion-graph-v2-4-1/static-plan-manifest-v2.4.1.json]
```

각 bracket 항목은 whitespace split 문자열이 아니라 JSON string token array로
materialize한다. cwd는 `/work/walksafe`, environment는 §9 exact allowlist,
timeout/output cap/raw destination은 contract에 수치로 동결하고 digest한다.

검증 순서:

```text
V0 LIVE Quick2
V1 active pre manifest + git status
V2 BEFORE Quick2
V3 AFTER Quick2
V4 regression A
V5 regression B
V6 AFTER Quick2 repeat
V7 active post manifest + git status exact identity with V1
```

cache/temp/raw/build output은 `/work` 안에서만 쓴다. bwrap unavailable,
read-only `/source` 미보장, write trace 부재 시 fallback 없이 STOP한다.

## 13. neutral v2.4.1 PRE-P control successor

명시적 identity:

```text
package_id=WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE
package_version=2.4.1
package_role=PRE_P_VALIDATION_CONTROL_SUCCESSOR
candidate_status=PREPARED_NOT_APPLIED
```

현재 v2.4 bytes/status는 stage 2 apply 전까지 unchanged다. static change strategy:

```text
VERSIONED_SUCCESSOR_MANIFEST_AND_CHECKER_REQUIRED
```

after-control은 successor continuation/Goal checker, direct non-discovery tests,
static manifest, control package, README, seq40 event와 staged checkpoint를
소유한다. 기존 v2.4 static/checker/history를 수정하지 않는다.

seq40 event type은
`PRE_P_VALIDATION_CONVERGENCE_CONTROL_SUCCESSOR_APPLIED`다. after checkpoint는:

- source checkpoint exact binding과 seq39 tail
- seq40 event/hash와 transition anchor
- package v2.4.1 ACTIVE after-apply state
- exact target transition set
- managed changed path count/list/path-set/content-set
- `session_handoff.changed_files`
- `session_handoff.source_commit_or_snapshot` count/path/content
- canonical r021, Goal/focus, product/artifact/formal/device/gate/release unchanged
- receipt requirement state only, future receipt hash 없음

을 포함한다. count/hash는 builder가 exact target set에서 계산하고 candidate
freeze 전에 decimal/full 64hex로 채운다. placeholder나 current 값 복사는
review FAIL이다.

apply 뒤 control successor만 active가 되고 project/r021/credit는 unchanged다.
R007의 clean seq39→40 P 설계는 stale이 된다. stage 3에서 actual committed seq40과
v2.4.1 receipt를 source로 하는 add-only `R007-SUCCESSOR`를 새로 design/review하고
P는 seq40→41로 다시 설계한다. 기존 R007은 수정·실행·physical candidate source로
재사용하지 않는다.

## 14. atomic apply envelope와 recovery

stage 2 fresh authority가 결속할 `apply-envelope.json`은 candidate/review,
source seq39, exact transitions와 after checkpoint hash를 포함한다.

`exact-target-transition-set.json`의 허용 target universe는 아래 표와 정확히
같다. 각 행은 final-relative path, mode, before existence/hash/bytes,
candidate path/hash/bytes, after hash/bytes, order를 가진다. 표 밖 target,
implicit directory member, glob expansion은 금지한다.

| mode | exact target |
|---|---|
| `CAS_REPLACE` | `tests/requirements.lock` |
| `CAS_REPLACE` | `scripts/run_walksafe_test_layers_20260711.sh` |
| `NOREPLACE` | `tests/walksafe_test_database_preflight_successor_20260731_r002.py` |
| `NOREPLACE` | `tests/walksafe_android_gateway_public_routes_successor_20260731_r002.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_current_active_20260731.py` |
| `NOREPLACE` | `scripts/check_walksafe_artifact_baseline_dual_control_20260731.py` |
| `NOREPLACE` | `tests/walksafe_artifact_baseline_historical_successor_20260731_r002.py` |
| `NOREPLACE` | `tests/walksafe_artifact_baseline_current_successor_20260731_r002.py` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/static-plan-manifest-v2.4.1.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/control-package-manifest-v2.4.1.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/README.md` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/transition-event-seq40.json` |
| `NOREPLACE` | `docs/control/goals/walksafe-completion-graph-v2-4-1/full19-successor-contract.json` |
| `NOREPLACE` | `scripts/check_walksafe_project_continuation_v2_4_1.py` |
| `NOREPLACE` | `scripts/check_walksafe_goal_graph_v2_4_1.py` |
| `NOREPLACE` | `tests/walksafe_project_continuation_v2_4_1_successor_20260731.py` |
| `NOREPLACE` | `tests/walksafe_goal_graph_v2_4_1_successor_20260731.py` |
| `CHECKPOINT_CAS_LAST` | `docs/control/walksafe-project-continuation-checkpoint.json` |

checkpoint 뒤 postcheck가 PASS한 경우에만 다음 reserved path를
`NOREPLACE_POSTCHECK_RECEIPT`로 생성한다. 이 receipt는 target universe의
pre-checkpoint promotion count에는 포함하지 않으며, receipt reservation과
schema가 정확한 path를 미리 결속한다.

```text
docs/control/execution/goal-gates/
  WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001/
    application-receipt.json
```

promotion order:

```text
1. exact add-only/CAS target promotions
2. each target fsync + parent fsync + progress exact-prefix
3. checkpoint CAS last
4. after-control Quick2/postcheck
5. add-only application receipt + parent fsync
```

checkpoint에는
`TARGET_COMMITTED_REQUIRES_DURABLE_POSTCOMMIT_RECEIPT` 같은 receipt-requirement
state만 기록하고 미래 receipt hash를 넣지 않는다.

| crash/divergence | 유일 허용 동작 |
|---|---|
| checkpoint 전 exact prefix | same transaction/authority에서 prefix 재검증 후 remaining suffix resume |
| checkpoint 전 non-prefix/bytes drift | `DIVERGENCE_WRITE_ZERO` |
| checkpoint CAS 완료, receipt 없음 | target write 금지; postcheck/receipt-only resume |
| receipt 완료 | idempotent read-only verification |
| source/authority/lease/path drift | write 0 |

rollback은 없다. 이미 durable한 target을 과거 bytes로 되돌리거나 partial file을
삭제하지 않는다. 실패는 incident/recovery state로 닫고 successor를 만든다.

## 15. stop conditions

- Phase 0.5 exact authority absent
- current Quick2 FAIL 또는 source checkpoint/hash drift
- candidate expected path preexists/symlink/hardlink/nlink≠1
- A/C review 전 B build, A/B/C review 전 D build
- W5 successor receipt/raw/environment attestation 누락
- local combined 또는 hosted CPU clean install 중 하나라도 non-PASS
- current stale venv/fallback Python 사용
- discovery/assigned `132/132`, supplemental exact2 불일치
- stale exact3가 UNIT/all에 남거나 orphan routing 불일치
- D helper가 runner에 들어감
- full19 1~19 matrix field/hash/digest 미완성
- 5~14 동일성 hash proof 누락
- regression exact argv/path/env/discovery digest 누락 또는 A/B FAIL/NOT_RUN
- bwrap/read-only `/source`/writable `/work/walksafe`/fresh process 미보장
- `.git` 미복제, same inode, hardlink, nlink>1, active write 관측
- staged after checkpoint의 managed/session_handoff exact closure 누락
- partial apply, checkpoint-before-target, receipt future hash, rollback 시도
- R007 수정/재사용 또는 seq39→40 P build 시도
- canonical/product/artifact/formal/device/gate/release delta nonzero

## 16. 7 findings remediation matrix

| R001 finding | R002 remediation | self-check anchor |
|---|---|---|
| B-001 authority 순서 | §3의 5단계와 Phase0.5 write-zero | exact 5 authority token, current ABSENT |
| B-002 projection 부재 | §5 aggregate paths, §12 bwrap BEFORE/AFTER | `/source` RO, `/work/walksafe` RW, V0~V7 |
| B-003 7 FAIL 종결 불가 | §8 stale exact3 historical routing, supplemental exact2, §11 A→B | exact paths/argv, both actual PASS |
| B-004 full19 영향 미폐쇄 | §9~10 exact19 matrix | changed 1/2/3/4/15/16/17/18/19, unchanged 5~14 proof |
| B-005 atomic/P rebaseline | §13 v2.4.1 seq40, §14 checkpoint-last, R007 successor seq40→41 | existing R007 no modify/reuse |
| M-001 W5 receipt 누락 | §5 lane A paths, §6 W5 successor receipt/review | predecessor/tool/build/install hashes |
| M-002 builder/raw 경로 누락 | §5 lane builders/raw/projection/apply tree | archive/exclusions digest, NOREPLACE |

## 17. R002 self-check contract

R002 independent review 전에 최소 다음 assertions를 실행한다.

```text
R002 regular && !symlink
R001 SHA == b25e9bd0a71e86d2bd0b8176369053ed4db5896cb3055e17591b19135ec61962
R001 review SHA == 95c485bfae33f3a8d0e0a783ef9d0c602fc72d779597d7144ca87530df0e4cd0
status == NON_EFFECTIVE_PLAN_ONLY
current build authority == ABSENT_DENY_ALL
five authority tokens unique/exact
DAG == A+C -> B -> D -> aggregate
lane A active lock target count == 1
B discovered/assigned/supplemental == 132/132/2
stale exact3 and orphan exact5 sets exact
D outside runner
full19 IDs 1..19 unique/order exact
CHANGED/UNAFFECTED matrix covers 19
index10 hash == 0293d54ea860df64ad8bc21214902488a4062f3c0809ea9c7ca69be35fbfa35b
projection required paths all present in plan
after-control required paths all present in plan
apply checkpoint-last/no-future-receipt/no-rollback
R007 SHA == 2c0eb19da8b9a2df77cb43b2ffd169583591f0a23150d9ef5eff00cf39327610
R007 successor path distinct && R007 write count == 0
official credit delta == 0
```

## 18. 완료·handoff 경계

이 문서 작성 완료는 plan-only다. 다음 상태를 바꾸지 않는다.

```text
PRE_P_CANDIDATE_BUILT=false
PRE_P_APPLIED=false
V2_4_1_ACTIVE=false
R007_SUCCESSOR_BUILT=false
P_CANDIDATE_BUILT=false
P_APPLIED=false
P17_BUILD_DEFERRED=true
FORMAL_PASS=0/279
DEVICE_EVENT=0/0
GATE_CLOSED=0/5
RELEASE=NOT_ELIGIBLE
```

R002 독립검수와 내부 보완이 끝난 최종 handoff에서만 stage 1 exact authority
질문을 사용자에게 제시한다. 그 응답 전 candidate build는 금지다.
