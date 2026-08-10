# PRE-P Validation Convergence Design/Build Plan R001

## 1. 문서 통제

| 항목 | 값 |
|---|---|
| document_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R001` |
| 작성일 | `2026-07-31` |
| status | `NON_EFFECTIVE_PLAN_ONLY` |
| execution_mode | `DESIGN_BUILD_PLAN_ONLY` |
| execution_started | `false` |
| P17 상태 | `P17_BUILD_DEFERRED` |
| candidate build 권한 | `false` |
| candidate apply 권한 | `false` |
| canonical/control 전환 권한 | `false` |
| product write 권한 | `false` |
| credit 변화 | `CREDIT_DELTA=0` |

```text
STATUS=NON_EFFECTIVE_PLAN_ONLY
P17_BUILD_DEFERRED
PLAN_AUTHORITY_ONLY=true
LANE_CANDIDATE_BUILD_STARTED=false
ATOMIC_SUCCESSOR_CANDIDATE_BUILT=false
APPLY_ALLOWED=false
CHECKPOINT_WRITE_ALLOWED=false
GOAL_EVENT_WRITE_ALLOWED=false
CANONICAL_WRITE_ALLOWED=false
PRODUCT_WRITE_ALLOWED=false
ARTIFACT_STATUS_DELTA=0
FORMAL_TEST_DELTA=0
ACTUAL_DEVICE_DELTA=0
GATE_DELTA=0
RELEASE_DELTA=0
```

이 문서는 P selector-preflight exact17을 만들거나 적용하는 문서가 아니다.
현재 검증 경로의 네 결함 축을 서로 독립적으로 설계·검수한 뒤, 오직 하나의
atomic managed-snapshot/control successor **candidate**로 결속하기 위한 미래
작업계획이다. 이 문서의 작성·검토·PASS는 P17 build, 사용자 승인 요청, apply,
v2.5/r022 전환 또는 제품 작업을 허용하지 않는다.

## 2. 목적과 성공 기준

목표는 현재 active source나 역사 증거를 고쳐서 검사를 억지로 통과시키는 것이
아니라 다음 네 lane의 successor bytes와 검증 계약을 add-only candidate
namespace에서 동결하는 것이다.

| lane | 범위 | lane-local 성공 기준 |
|---|---|---|
| A | Pillow test lock successor | CPython 3.12.13/pip-tools 7.6.0 결정론적 2회 생성, clean backend+tests hash install, `pip check`, Pillow 12.3.0 |
| B | test-layer runner successor | 현재 orphan 5개를 되살려 exact `configured=132 / discovered=132 / unassigned=0`, validate `rc=0` |
| C | Gateway public exact5 test successor | config/router/OpenAPI exact5 일치, positive와 source별 missing/extra negative fixture PASS |
| D | historical/current materializer와 full19 control successor | 역사 `b3dde.../2630`과 current `ed331.../3108`을 분리 검증하고 합성 결과가 두 축 모두 PASS일 때만 PASS |

최종 성공은 lane별 PASS의 단순 모음이 아니다.

```text
A freeze + review findings 0
B freeze + review findings 0
C freeze + review findings 0
        ↓
D가 A/B/C frozen hash를 직렬 final-pin
        ↓
D freeze + review findings 0
        ↓
단일 aggregate manifest/build/review
        ↓
ONE_ATOMIC_MANAGED_SNAPSHOT_CONTROL_SUCCESSOR_CANDIDATE
        ↓
NON_EFFECTIVE / NOT_APPLIED / P17_BUILD_DEFERRED
```

각 lane에 별도 apply 단위를 만들지 않는다. A~D 중 하나라도 빠진 partial
candidate, lane별 독립 source switch, checkpoint hash만 맞추는 snapshot 수용은
실패다.

## 3. 현재 사실 기준선

### 3.1 active control과 Quick2

2026-07-31 이 계획 작성 중 다음 두 명령을 read-only로 실행했다.

```text
python3 -B scripts/check_walksafe_project_continuation_v2_4.py
→ PASS

python3 -B scripts/check_walksafe_goal_graph_v2_4.py
→ PASS (26 managed Goals, ready 2, focus WS-GOAL-EPIC-03, ACTIVE)
```

따라서 현재 사실은 `QUICK2=PASS/PASS`다. 이는 full19 PASS, P17 readiness,
candidate 승인 또는 apply 권한이 아니다.

### 3.2 current validation debt

| 항목 | 현재 사실 |
|---|---|
| runner | `scripts/run_walksafe_test_layers_20260711.sh` |
| runner SHA-256 / bytes / mode | `4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d` / `15,588` / `0775` |
| discovered Python test files | `132` |
| configured files | `127` |
| unassigned files | exact `5` |
| current runner validate | `rc=2` |
| regression execution fact | `450 PASS / 7 FAIL` |
| second control suite | `241-test suite NOT_RUN` |

`450/7`을 PASS로 합치거나 알려진 실패라고 제외하지 않는다. 두 번째 241 suite는
실행되지 않았으므로 `0 FAIL`, `SKIP`, `PASS`가 아니라 정확히 `NOT_RUN`이다.
후속 candidate 검증은 첫 suite가 nonzero여도 raw 결과를 보존하고, 원인 수정 뒤
두 suite를 모두 실제 실행하기 전에는 회귀 종결을 주장하지 않는다.

현재 unassigned exact5는 다음이다.

```text
tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py
tests/test_walksafe_v2_5_control_candidate_20260730.py
tests/test_walksafe_w3_engineering_evidence_20260726.py
```

### 3.3 authority와 release 경계

- active package는 v2.4이고 canonical Gap/Backlog는 r021/r021이다.
- canonical r022, active v2.5와 P physical candidate는 없다.
- formal test는 `0/279 PASS`, 279개 모두 `NOT_RUN`이다.
- actual-device/real-event는 `0/0`이다.
- exact release gate 5개는 모두 `NOT_RUN`, `waived=false`다.
- release는 `NOT_ELIGIBLE`이다.

이 계획과 미래 candidate 검증은 위 값을 바꾸지 않는다.

## 4. 공통 candidate 경계와 예상 경로

현재 이 절의 경로는 예약만 한다. 이 계획 작업에서는 만들지 않는다. 미래 build
시 모든 부모와 최종 파일이 regular/non-symlink이고 NOREPLACE 조건을 만족해야
한다.

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r001/
    lane-a/
      candidate-manifest.json
      tests/requirements.lock
      independent-review-r001.md
    lane-b/
      candidate-manifest.json
      scripts/run_walksafe_test_layers_20260711.sh
      tests/walksafe_test_database_preflight_successor_20260731_r001.py
      independent-review-r001.md
    lane-c/
      candidate-manifest.json
      tests/walksafe_android_gateway_public_routes_successor_20260731_r001.py
      independent-review-r001.md
    lane-d/
      candidate-manifest.json
      scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py
      scripts/check_walksafe_artifact_baseline_current_active_20260731.py
      scripts/check_walksafe_artifact_baseline_dual_control_20260731.py
      tests/walksafe_artifact_baseline_historical_successor_20260731_r001.py
      tests/walksafe_artifact_baseline_current_successor_20260731_r001.py
      full19-control-successor-contract.json
      independent-review-r001.md
    aggregate/
      managed-snapshot-control-successor-manifest.json
      validation-receipt.json
      independent-review-r001.md
```

미래 aggregate manifest는 candidate archive path와 의도한 target role을 함께
기록한다.

| lane | future target role | promotion 계획 |
|---|---|---|
| A | `tests/requirements.lock` | 기존 bytes를 before-CAS로 둔 successor replacement |
| B | `scripts/run_walksafe_test_layers_20260711.sh` | 기존 restored runner를 before-CAS로 둔 successor replacement |
| B helper | `tests/walksafe_test_database_preflight_successor_20260731_r001.py` | non-discovery/direct-only add-only NOREPLACE |
| C | `tests/walksafe_android_gateway_public_routes_successor_20260731_r001.py` | non-discovery/direct-only add-only NOREPLACE |
| D | 역사/current/dual validator 3개 | add-only NOREPLACE |
| D helpers | `tests/walksafe_artifact_baseline_{historical,current}_successor_20260731_r001.py` | non-discovery/direct-only add-only NOREPLACE |
| D control | full19 index 3과 index 18 execution contract | add-only versioned control successor; active contract 직접 수정 금지 |

이 mapping은 apply 목록이 아니다. 실제 target set, before/after hash, count와
managed snapshot 변화는 aggregate candidate build 직전에 source double-read 후
동결한다.

## 5. Lane A — Pillow lock successor

### 5.1 현재 결함

| source | 현재 Pillow |
|---|---|
| `backend/requirements.txt` | `12.3.0` |
| `backend/requirements.lock` | `12.3.0` |
| `tests/general-quality-cp312-linux-x86_64-cpu.lock` | `12.3.0` |
| `tests/requirements.lock` | `12.2.0` |

필수 범위는 실제 blocker인 backend constraint와 결합되는
`tests/requirements.lock` 한 건이다. voice/submission lock은 명시적인
cross-environment 소비 계약과 동일 dependency closure가 입증되지 않으면
`OUT_OF_SCOPE_OBSERVATION`으로 남긴다. 버전 숫자가 같다는 이유만으로 함께
재생성하지 않는다.

### 5.2 build 계약

1. predecessor W5의 lock 생성 input, command, CPython/pip/pip-tools binding을
   exact path/hash로 재확인한다.
2. CPython `3.12.13`, pip-tools `7.6.0`과 같은 index/cache policy를 격리 환경에
   고정한다.
3. active `tests/requirements.lock`을 덮지 않고 lane A candidate path에 두 번
   생성한다.
4. 두 출력이 byte-for-byte 동일해야 한다.
5. Pillow는 `12.3.0`이고 모든 hash line은 pip의 hash verification을 통과해야
   한다.
6. Pillow 외 변화는 resolver가 입증한 필수 closure만 허용하고 별도 delta
   inventory에 기록한다.

### 5.3 검증

새 empty venv에서 다음 의미 계약을 수행한다. 실제 argv, interpreter/pip hash,
network/index policy, stdout/stderr hash와 rc는 future lane manifest에 고정한다.

```text
python -m pip install --require-hashes --no-compile \
  -r backend/requirements.lock \
  -r <lane-a-candidate>/tests/requirements.lock
python -m pip check
python -c <Pillow distribution/import version is exactly 12.3.0>
```

수용조건:

- two-build byte identity: PASS
- clean install: rc 0
- `pip check`: rc 0
- installed Pillow: exact `12.3.0`
- active consumer switch: 0
- active lock/checkpoint/managed snapshot write: 0

index 접근이 불가능하거나 wheel/platform hash가 맞지 않으면 `NOT_RUN` 또는
`FAIL`로 보존하고 과거 환경의 설치 상태를 clean-install PASS로 재사용하지 않는다.
전체 combined clean install이 실제 자원 한도를 넘으면
`NOT_RUN_RESOURCE_BLOCKED/STOP`으로 기록한다. 부분 install이나 이미 설치된
환경을 대신 PASS로 쓰지 않는다. candidate verifier 또는 `/tmp` projection을
사용하며 검증을 위해 live lock을 임시 교체하지 않는다.

## 6. Lane B — test-layer runner successor

### 6.1 최소 변경

현재 restored runner의 다른 내용과 mode 0775를 보존하면서 §3.2의 unassigned
exact5만 원래 분류에 재등록한다. 파일 삭제, test rename, discovery root 축소,
`find` filter 완화 또는 unassigned test 무시는 금지한다.

lane B freeze 시 성공 기준은 다음 exact tuple이다.

```text
RUNNER_CONFIGURED=132
RUNNER_DISCOVERED=132
RUNNER_UNASSIGNED=0
RUNNER_DUPLICATED=0
RUNNER_MISSING=0
RUNNER_VALIDATE_RC=0
```

runner candidate는 기존 active runner를 수정하지 않고 lane B archive path에
만든다. before SHA/bytes/mode, candidate SHA/bytes/mode와 exact five-line semantic
delta를 manifest에 기록한다.

### 6.2 분류와 검증

- seq39 test는 active-session control에 두고 `all`에 포함한다.
- exact257 R011과 W3는 historical/targeted-only로 두고 `all`에서 제외한다.
- r022/v2.5 prototype test도 historical/targeted-only로 두고 `all`에서 제외한다.
- 동일 경로가 두 array에 들어가면 실패한다.
- non-discovery helper
  `tests/walksafe_test_database_preflight_successor_20260731_r001.py`를
  direct-only로 실행해 candidate runner와 candidate-root overlay의 rc 0을
  재현한다. helper 이름은 `test_` prefix를 사용하지 않는다.
- 현재 Quick2 두 개가 candidate overlay를 읽지 않는 live root에서도 계속
  PASS인지 확인한다.
- lane B review는 등록 5줄 외 semantic delta가 0이고 active source write가
  없음을 독립 재계산한다.

sealed legacy runner `4f7550...32b42d / 15,588 / 0775`는 수정하지 않는다.
Lane B candidate bytes와 illustrative scout hash는 실제 future build/review에서
재계산하며 planning witness를 acceptance hash로 승격하지 않는다.

Lane B/C의 add-only helper와 Lane D validator/self-test는 의도적으로
non-discovery/direct-only control command다. 따라서 aggregate에서도 canonical
Python discovery는 exact `132/132/0`을 유지한다. reviewer가 auto-discovery
편입을 요구하면 R001의 count를 조용히 N으로 바꾸지 않고 새 R002 설계와 runner
successor review를 만든다.

## 7. Lane C — Gateway public exact5 successor test

### 7.1 보존과 새 범위

기존 `tests/test_walksafe_android_gateway_boundary_20260723.py`와 Phase-E의
historical exact4 근거는 수정하지 않는다. 새 add-only non-discovery/direct-only
successor helper만 만든다.

현재 public API exact5와 method contract는 다음이다.

| path | methods |
|---|---|
| `/api/field-session` | `GET`, `POST`, `DELETE` |
| `/api/field-walk` | `GET`, `POST` |
| `/api/navigation/walking` | `POST` |
| `/api/navigation/destinations/search` | `GET` |
| `/api/reports/v2` | `POST` |

다음 세 source가 순서 표현과 무관하게 같은 path/method set이어야 한다.

- `configs/walksafe_product_boundary_20260722.json`
- `apps/android-gateway/src/routes.ts`
- `apps/android-gateway/openapi.json`

historical exact4와 current exact5의 delta는 정확히
`/api/field-walk` 하나여야 한다. FP012 sequence 27~31, canonical completion,
completion receipt → implementation record/artifact bindings도 path/hash로
결속하되, 테스트 소스가 완료 receipt를 새로 만들거나 current history를
재작성하지 않는다.

### 7.2 positive/negative fixtures

positive fixture:

- 세 source가 위 exact5와 methods까지 일치
- existing boundary checker PASS
- historical exact4는 역사 scope에서만 PASS

negative fixture는 temp copy에서 source별로 모두 수행한다.

| source | missing case | extra case |
|---|---|---|
| product-boundary config | exact5 중 하나 삭제 | sixth route 추가 |
| router allowlist | exact5 중 하나 삭제 | sixth route/method 추가 |
| OpenAPI | exact5 중 하나 삭제 | sixth route/method 추가 |

method-only drift, duplicate/alias path, config/router/OpenAPI 중 한 축만 다른 경우도
fail-closed한다. fixture는 repo active source를 쓰지 않고 temp root만 쓴다.

### 7.3 검증

```text
direct successor helper/self-test
existing tests/test_walksafe_android_gateway_boundary_20260723.py
locked Node apps/android-gateway typecheck
locked Node apps/android-gateway test
```

각 command의 exact argv/cwd/Node lock/hash/rc를 lane manifest에 기록한다.
deployment/device/formal/gate/release credit는 모두 0이다.

## 8. Lane D — historical/current materializer와 full19 control successor

### 8.1 두 epoch를 섞지 않는 3분할

기존 receipt, 기존 materializer script와 기존 baseline 파일은 수정하지 않는다.
새 validator는 다음 세 역할로 add-only 생성한다.

1. `historical_event_time`
   - committed event-time receipt가 고정한
     `docs/deliverables/00-control/README.md`
     SHA-256
     `b3dde3a3c3f74fd383979eb8ed3a8ac9d6727592ddad0ea8c86e8b5143e07fa3`,
     bytes `2,630`을 historical preimage/replay scope에서 검증한다.
   - live README가 이 hash여야 한다고 요구하지 않는다.
2. `current_active`
   - live current authority와 navigation-warning binding이 고정한 README
     SHA-256
     `ed331dd2894a8ccd41b22df8c9272858a9e7ca89b090cd3a20942baf35d74c54`,
     bytes `3,108`을 current root에서 검증한다.
   - 과거 receipt를 current bytes로 재작성하지 않는다.
3. `dual_control`
   - `HISTORICAL_RECEIPT_VALID=true`와 `CURRENT_ACTIVE_VALID=true`가 모두
     성립할 때만 PASS한다.
   - 한 축의 PASS를 다른 축에 승계하지 않는다.

### 8.2 D final pin의 직렬 조건

D build는 A/B/C candidate와 각 review가 freeze된 뒤에만 시작한다. D manifest는
다음 exact 값을 직접 포함한다.

- A candidate/review path, SHA-256, bytes
- B candidate/review path, SHA-256, bytes
- C candidate/review path, SHA-256, bytes
- 세 lane의 claim boundary와 PASS tuple

A/B/C 중 하나라도 이후 바뀌면 D와 aggregate는 전부 stale이며 D를 patch하지
않고 새 versioned successor로 다시 만든다.

### 8.3 negative tests

- historical receipt 또는 old script rewrite 시도
- historical `b3dde.../2630`을 live current requirement로 혼용
- current `ed331.../3108`을 event-time receipt 값으로 소급
- README byte/hash/length mismatch
- README symlink, missing, non-regular file
- validator path/hash/argv drift
- full19 ID/order/digest drift
- historical만 PASS 또는 current만 PASS

모두 합성 control FAIL이어야 한다.

### 8.4 full19 successor

full19은 exact 19 ID/order를 유지한다. index 3 ID는
`BASELINE_MATERIALIZATION`으로 유지하되, old dual-semantics command를 그대로
재사용하지 않는다.

index 18 `CONTROL_AND_TRACE_PYTEST`의 기존 argv는 legacy
`tests/test_walksafe_artifact_baseline_materialization_20260722.py`를 직접
포함하며 현재 이 legacy materialization 축은 `7 PASS / 1 FAIL`이다. successor
argv는 이 legacy role을 다음 두 non-discovery/direct-only helper의 explicit
path 실행으로 교체한다.

```text
tests/walksafe_artifact_baseline_historical_successor_20260731_r001.py
tests/walksafe_artifact_baseline_current_successor_20260731_r001.py
```

두 파일은 `test_` prefix를 사용하지 않으므로 canonical auto-discovery 132에
들어가지 않는다. index 18은 exact path를 직접 지정해 두 helper를 실행한다.
legacy test와 historical materializer/receipt는 수정·삭제·rename·deselect하지
않고 역사 입력으로 보존한다.

future successor contract가 새로 동결할 항목:

- index 3 dual validator exact path/hash
- index 18 direct helper exact paths/hashes와 explicit pytest argv
- Python executable/module FD role
- exact argv와 cwd/root FD
- env allowlist, timeout, output limit
- historical/current input path/hash/bytes
- command-contract canonical object와 새 digest
- index 1~19 전체 order와 전체 contract digest

index 3과 index 18은 같은 frozen historical/current inputs와 D candidate hash를
가리켜야 한다. A/B/C candidate command/hash와 review hash가 먼저 freeze된 뒤
D가 두 index의 path/hash/argv를 final-pin하고 ordered19 digest를 한 번만
계산한다. 이후 A/B/C/D 중 한 byte라도 바뀌면 index 3, index 18, ordered19
digest와 D review를 모두 무효화한다.

이 successor는 `VALIDATION_CONTRACT_CANDIDATE_NON_AUTHORIZING`이다. active
`docs/control/README.md`, static manifest, checker 또는 checkpoint에 반영하지
않는다. old index 3와 새 dual validator의 의미 차이가 해결되지 않은 현 v2.5
prototype/candidate를 PASS 대상으로 삼지 않는다.

## 9. 독립검수와 freeze 절차

각 lane은 같은 절차를 거친다.

1. builder가 candidate와 manifest를 add-only staging에 생성한다.
2. builder가 SHA-256/bytes/lines 또는 JSON canonical digest를 기록한다.
3. writer가 손을 뗀 뒤 reviewer가 시작 hash를 재계산한다.
4. reviewer가 positive/negative tests와 no-write boundary를 독립 실행한다.
5. 종료 hash가 시작 hash와 같고 findings
   `BLOCKING/MAJOR/MINOR=0/0/0`일 때만 `FROZEN_FOR_AGGREGATION`이다.
6. review 후 한 byte라도 바뀌면 review와 freeze는 무효다.

lane reviewer는 자기 lane builder/writer와 동일 principal이면 안 된다. 검수는
제품·formal·device·gate·release 승인이나 외부 전문 검토를 대신하지 않는다.

aggregate review는 다음을 별도로 확인한다.

- A/B/C review freeze 후 D final pin이라는 직렬 순서
- exact member set과 path uniqueness
- candidate archive와 intended target mapping
- active root write 0
- symlink/hardlink/path traversal/duplicate JSON key 거부
- final staged canonical registry exact `configured=132 / discovered=132 /
  unassigned=0`, duplicate/missing 0
- 모든 add-only helper가 `test_*.py` auto-discovery 밖이며 exact direct argv로만
  실행됨
- lane별 receipts가 같은 aggregate candidate hash를 참조
- partial apply, independent lane apply와 consumer switch가 불가능

## 10. 역할과 single-writer 계약

| 역할 | 책임 | 금지 |
|---|---|---|
| plan owner | 이 문서와 범위 유지 | candidate/build/apply 주장 |
| lane A writer | lock candidate만 작성 | B/C/D 또는 active lock write |
| lane B writer | runner candidate만 작성 | test 삭제·discovery 축소 |
| lane C writer | add-only exact5 direct helper candidate만 작성 | 기존 boundary/history 수정 |
| lane D writer | A/B/C freeze 뒤 validator/control candidate 작성 | 선행 hash 없는 build |
| lane reviewer A~D | 대상 lane 독립검수 | 자기 작성물 승인 |
| aggregate single writer | exact 한 aggregate manifest/receipt 생성 | lane별 apply packet 생성 |
| aggregate independent reviewer | atomicity·hash·negative 재검증 | canonical activation |
| canonical/apply writer | `UNASSIGNED / AUTHORITY_ABSENT` | 이 계획으로 apply |

A/B/C 설계·candidate build는 서로 다른 isolated subtree에서 병렬 가능하다. D
final pin과 aggregate write는 한 writer가 직렬로 수행한다. 같은 final path나
manifest를 둘 이상의 writer가 쓰면 즉시 중단한다.

## 11. atomic managed-snapshot/control successor candidate

aggregate candidate는 다음을 하나의 content-addressed object로 결속한다.

- A~D candidate와 reviews의 exact path/hash/bytes
- current live before snapshot
- intended target exact set과 before/after/tombstone
- file type, owner/mode와 symlink/hardlink policy
- staged final discovery/registration counts
- Quick2 결과
- lane validation raw receipt hashes
- full19 successor exact19 contract digest
- authority ceiling과 `APPLY_ALLOWED=false`

다음은 허용하지 않는다.

- A lock만 먼저 consumer에 연결
- B runner만 active source로 교체
- C test만 active `tests/`에 추가
- D index 3 또는 index 18만 따로 active full19에 연결
- checkpoint managed content-set만 새 bytes로 교체
- 네 lane candidate를 각각 독립 apply 가능하게 포장

aggregate build 완료 상태는 정확히
`STAGED_ATOMIC_CANDIDATE_NOT_APPLIED`다. P17 build는 이 candidate의 findings-zero
review와 별도 authority가 생기기 전까지 계속 `P17_BUILD_DEFERRED`다.

## 12. 실행 순서와 검증

### Phase 0 — read-only rebaseline

- current source hash, file type와 Quick2 재확인
- active root와 candidate reserved paths의 existence/symlink 확인
- current `450 PASS / 7 FAIL`, second 241 `NOT_RUN` raw receipt 결속

검증: input drift 0, Quick2 PASS/PASS, candidate target collision 0.

### Phase 1 — A/B/C 병렬 candidate와 개별 review

- A lock two-build/clean-install
- B exact132 runner validate
- C exact5 positive/negative
- 각 lane independent review와 freeze

검증: 각 findings 0/0/0, candidate/review 종료 hash 일치.

### Phase 2 — D 직렬 final pin

- frozen A/B/C binding 확인
- historical/current/dual validator와 negative tests
- full19 index3와 exact19 successor contract freeze
- D independent review

검증: dual PASS, one-axis negative FAIL, A/B/C pin exact, findings 0/0/0.

### Phase 3 — aggregate build

- single writer가 exact member set을 한 manifest로 생성
- staged root에서 Quick2, final canonical registry exact `132/132/0`,
  direct-only helper argv, lane 검증과 회귀를 실행
- 첫 회귀의 기존 7 FAIL이 0이 되지 않으면 실패
- 두 번째 241 suite를 실제 실행하지 않으면 전체 회귀는 `NOT_RUN/INCOMPLETE`
- aggregate independent review

검증: atomic candidate findings 0/0/0, active root write 0.

### Phase 4 — handoff only

- hash/bytes/lines, raw receipt locator와 남은 blocker 보고
- 사용자 확인은 모든 내부 review/보완이 끝난 마지막에만 요청
- 별도 exact authority 전에는 build/apply/P17로 진행하지 않음

검증: `STAGED_ATOMIC_CANDIDATE_NOT_APPLIED`,
`P17_BUILD_DEFERRED`, credit delta 0.

## 13. stop 조건

다음 중 하나면 해당 lane과 aggregate를 즉시 중단한다.

- current Quick2 중 하나가 FAIL
- source hash/file type가 manifest input과 다름
- candidate 또는 review target이 이미 존재하거나 symlink/hardlink임
- lane writer가 allowlist 밖 파일을 변경
- A 결정론적 두 출력 불일치, clean install/pip check/Pillow version FAIL
- B가 exact `132/132/0 rc0`이 아니거나 test를 숨겨 수치를 맞춤
- C 세 source의 exact5/method 불일치 또는 missing/extra fixture가 PASS
- historical `b3dde.../2630` 또는 current `ed331.../3108` 불일치
- D 한 축만 PASS인데 dual control이 PASS
- A/B/C freeze 전에 D final pin
- review 뒤 candidate bytes 변경
- aggregate final path 중복, partial apply 가능성 또는 single-writer 위반
- first regression에 FAIL이 남음
- second 241 suite가 실행되지 않았는데 전체 회귀 PASS 주장
- full19 index ID/order/path/hash/argv/digest drift
- index 18이 legacy materialization test를 계속 직접 실행하거나 successor
  helper가 auto-discovery에 편입됨
- checkpoint/canonical/product/artifact/formal/device/gate/release write 시도

실패 때 자기 lane의 unpublished staging만 제거할 수 있다. 기존 source, history,
checkpoint, canonical, product, artifact와 receipt는 복원·수정·삭제하지 않는다.
이미 published candidate는 삭제하거나 덮지 않고 rejected successor 이력으로
보존한다.

## 14. authority ceiling과 금지 경로

이 계획으로 다음 경로/상태를 변경하지 않는다.

- `apps/**`, `backend/**`, `model/**`, `voice/**`
- active `scripts/**`, active `tests/**`, active lock
- `docs/control/walksafe-project-continuation-checkpoint.json`
- `docs/control/goals/**`, transition history와 Goal/event receipt
- canonical Gap/Backlog, DOC-01/DOC-05와 active artifact register
- product 정책·요구·설계·산출물
- source/history/checkpoint/canonical/product/artifact/formal/device/gate/release
  상태 전부

특히 기존 역사 receipt의 `b3dde.../2630`을 current 값으로 고치거나, current
README를 과거 bytes로 되돌리거나, Quick2를 유지하기 위해 checkpoint snapshot만
바꾸지 않는다.

## 15. 이 문서의 완료 판정

이 계획 문서 자체의 완료 조건은 다음뿐이다.

- A~D 범위, 검증, negative test와 stop 조건이 명시됨
- A/B/C review-freeze → D serial final-pin → one aggregate candidate 순서가 명시됨
- expected candidate paths와 future target roles가 명시됨
- current Quick2 PASS, `450 PASS / 7 FAIL`, second 241 `NOT_RUN`이 분리 기록됨
- history `b3dde.../2630`과 current `ed331.../3108`이 분리됨
- apply와 모든 공식 상태 변경이 금지됨

따라서 이 문서가 완료돼도 다음 상태는 유지된다.

```text
P17_BUILD_DEFERRED
ATOMIC_SUCCESSOR_CANDIDATE_BUILT=false
APPLY_ALLOWED=false
FORMAL_279_PASS=0
ACTUAL_DEVICE=0
RELEASE_GATE_CLOSED=0/5
RELEASE=NOT_ELIGIBLE
```
