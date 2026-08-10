# PRE-P Validation Convergence Design/Build Plan R002 독립검수 R001

## 1. 검수 대상과 판정

| 항목 | 값 |
|---|---|
| review_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R002-INDEPENDENT-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R002.md` |
| target SHA-256 | `92736c9912903435120fc9087174b3119972ad2c58e1144a335afa1cd2306576` |
| target bytes | `33,011` |
| target lines | `734` |
| target type | `regular file` |
| verdict | `REJECTED_PLAN_DO_NOT_EXECUTE` |
| findings | `BLOCKING=4 / MAJOR=2 / MINOR=0` |
| review authority | `NONE` |

검수 시작과 종료 시 target의 SHA-256, bytes, lines가 위 값과 같음을
확인했다. 이 review는 target을 수정하지 않으며, target의 계획 실행,
candidate·aggregate build, 외부 attested environment 생성, apply,
checkpoint/control 전환, R007 successor 또는 P build/apply 권한을 만들지
않는다.

```text
VERDICT=REJECTED_PLAN_DO_NOT_EXECUTE
BLOCKING=4
MAJOR=2
MINOR=0
TARGET_UNCHANGED=true
PLAN_EXECUTION_AUTHORIZED=false
PRE_P_VALIDATION_CONVERGENCE_DESIGN_BUILD_ONLY_AUTHORIZED=false
EXTERNAL_ATTESTED_ENV_PROVISIONING_AUTHORIZED=false
PRE_P_VALIDATION_CONVERGENCE_ATOMIC_APPLY_ONLY_AUTHORIZED=false
POST_SEQ40_R007_SUCCESSOR_DESIGN_REVIEW_ONLY_AUTHORIZED=false
P_CANDIDATE_BUILD_REVIEW_ONLY_AUTHORIZED=false
P_ATOMIC_APPLY_ONLY_AUTHORIZED=false
```

## 2. 검수 범위

Target 전체 734행과 현재 read-only source 사실을 다음 축으로 대조했다.

- R001 independent review의 `BLOCKING=5 / MAJOR=2` 종결 여부
- plan 자체의 build/apply authority 부재와 별도 5단계 authority 순서
- isolated staging projection의 final-relative overlay, root·cwd·import 해석,
  distinct inode와 active-root write 0
- full19 index 1~19 전체 impact matrix와 특히 index 15~18의 successor 실행
- staged BEFORE/AFTER Quick2, regression A/B와 active-root 전후 동일성
- seq40/v2.4.1 after-control의 package activation, supersession와 transition
  history
- 외부 attested clean environment의 생성 권한과 build/apply 간 identity
- after-control/apply 산출물의 deterministic builder와 raw provenance
- plan 단계의 `<EXACT_COMMAND_ARGV...>` 표기가 fail-closed인지 여부

## 3. BLOCKING findings

### PRE-P-R002-BLOCKING-001 — POST-SEQ40 TEST ROUTING/INDEX17 미폐쇄

#### 근거

Target §8.1은 stale exact3만 UNIT에서 `HISTORICAL`로 옮기고
`tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py`를
`ACTIVE_SESSION_CONTROL`, `all` explicit로 유지한다. §10의 full19 index 17
`GOAL_CONTROL_PYTEST`도 predecessor exact test list를 그대로 이월하고 seq39
test를 추가한다.

하지만 AFTER world와 atomic apply의 checkpoint는 seq40이며 package v2.4.1을
`ACTIVE`로 만든다. 이와 양립하지 않는 현재 테스트가 predecessor index 17에
남아 있다.

- `tests/test_walksafe_goal_graph_v2_4.py`는 active checkpoint의
  `goal_execution.package_id`가 v2.4 package ID와 같다고 직접 단언한다.
- `tests/test_walksafe_project_continuation_v2_4.py`는 active transition history
  길이 `39`, seq39 tail과 v2.4 package identity를 직접 단언한다.
- `tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py`는 current checkpoint를
  seq39 projection으로 역산·검증한다.

따라서 seq40/v2.4.1 AFTER checkpoint에서 predecessor list와 seq39 current
test를 실행하면 successor acceptance가 필연적으로 실패한다. 반대로 target이
새로 만드는 다음 non-discovery/direct-only v2.4.1 tests는 full19 index 17,
regression A/B 또는 다른 acceptance command 어디에도 실행 경로가 없다.

```text
tests/walksafe_project_continuation_v2_4_1_successor_20260731.py
tests/walksafe_goal_graph_v2_4_1_successor_20260731.py
```

이는 R001 `BLOCKING-003`의 stale-current-test 문제와
`BLOCKING-004`의 full19 영향 폐쇄를 완전히 종결하지 못한다.

#### Required remediation

- v2.4와 seq39 active-state assertions를 가진 predecessor tests를 bytes
  보존한 `HISTORICAL_TARGETED_ONLY` 또는 동등한 역사 전용 route로
  재분류한다.
- 새 v2.4.1 direct tests를 full19 index 17의 exact argv token array에
  명시하고, AFTER checkpoint에서 실제 rc0으로 실행한다.
- v2.4 predecessor history tests 중 seq40에서도 유효한 것과 current-only
  assertion을 exact nodeid/path 단위로 분리한다.
- `DISCOVERED`, `ASSIGNED`, supplemental 수치, routing manifest,
  `full19-impact-matrix.json`, `full19-successor-contract.json`과 regression
  contract를 새 분류에 맞춰 다시 동결한다.
- negative fixture로 seq39/v2.4 current assertion이 AFTER suite에 재유입되면
  fail하도록 검증한다.

### PRE-P-R002-BLOCKING-002 — EXTERNAL ATTESTED ENV AUTHORITY/LIFETIME 미정의

#### 근거

Target §3의 stage 1 authority는 예약 candidate namespace의 add-only
build/review만 허용한다. §5의 expected NOREPLACE tree에도 environment
attestation JSON은 있지만 실제 environment root나 그 member는 없다.

그런데 §6은 local combined와 hosted CPU projection이라는 두 새 clean
environment를 생성·설치해야 하고, §9와 §12는 repository 및 candidate
namespace 밖의 `<ATTESTED_ENV_ROOT_REALPATH>`를 bwrap에 bind하여 full19
index 15~18과 regression에 사용한다. 다음 사항이 정의되지 않았다.

- 두 clean environment를 어느 exact root에 누가 생성할지
- candidate namespace 밖 package install write를 허용하는 authority
- 두 environment 중 어느 하나가 required full19 interpreter가 되는지
- stage 1 validation에서 fresh stage 2 apply/postcheck까지 동일 environment를
  보존하는 lease, immutability와 ownership
- path가 사라지거나 재생성되거나 inode/site-packages가 바뀐 경우의
  재-provision 또는 STOP 규칙
- external environment와 attestation signature를 발급·검증하는 주체

현 stage 1 authority를 그대로 적용하면 clean environment 생성은 허용 root
밖 write다. 임시 environment를 stage 1 종료와 함께 폐기하면 fresh stage 2의
postcheck가 reviewed interpreter identity를 재사용할 수 없다. 반대로
미정의 외부 environment를 보존하면 candidate expected set과 authority
폐쇄를 우회한다.

#### Required remediation

- environment provisioning을 위한 exact 별도 authority 또는 stage 1
  authority의 명시적 외부 temporary root 범위를 정의한다.
- local combined와 hosted CPU environment 각각의 exact role을 정하고,
  full19 index 15~18에 사용할 하나의 exact interpreter/environment identity를
  선택한다.
- environment root, interpreter realpath/hash, root dev/inode/mode,
  site-packages content manifest, lock hash, installed RECORD digest,
  owner와 lease/expiry를 authority와 attestation에 결속한다.
- stage 1 review부터 stage 2 postcheck까지 read-only 보존하거나,
  content-addressed 방식으로 byte-identical 재구축하고 독립 재검증하는
  fail-closed 절차를 둔다.
- external root preexistence, symlink/hardlink, replacement, drift와 cleanup
  권한을 명시한다. 해당 authority가 없으면 environment write 0으로
  STOP해야 한다.

### PRE-P-R002-BLOCKING-003 — REGRESSION-CONTRACT DAG CYCLE

#### 근거

Target §4의 실행 DAG는 A/C → B → D가 모두 freeze된 뒤에야
`aggregate + isolated projection`을 만든다. §5에서
`regression-contract.json`은 `aggregate/` 아래 산출물이다.

그러나 §11은 regression suite의 exact argv, ordered paths/nodeids, hashes,
environment, timeout, raw paths와 discovery digest 중 하나라도 없으면
`Phase 1 build STOP`이라고 규정한다. Phase 1의 첫 A/C build 이전에는
aggregate 산출물인 regression contract가 아직 존재할 수 없다. Target의
문언을 그대로 실행하면 contract 부재로 A/C 시작이 금지되고, A/B/C/D가
없으므로 contract를 만드는 aggregate 단계에도 도달할 수 없다.

단순히 contract를 나중에 채우면 lane build 입력과 validation command가
사후 결정되어 freeze/final-pin의 의미도 사라진다.

#### Required remediation

다음 중 하나를 새 revision에서 명확히 선택한다.

1. regression contract를 Phase 0.5 authority 직후, A/C build 전의 add-only
   preflight/freeze 산출물로 이동하고 모든 downstream builder/review가 그
   exact hash를 input으로 final-pin한다.
2. §11의 stop 시점을 `aggregate validation execution before`로 변경하고,
   lane build 자체에 필요한 command/input contract는 별도 선행 manifest로
   동결한다.

어느 경우든 phase 명칭, DAG edge, expected path, authority scope와 stop
conditions가 같은 순서를 표현해야 하며, missing contract negative test가
write 0을 입증해야 한다.

### PRE-P-R002-BLOCKING-004 — v2.4.1 ACTIVATION/HISTORY 미폐쇄

#### 근거

Target §13은 stage 1 candidate에서 v2.4.1을
`PREPARED_NOT_APPLIED`로 만들면서 동시에 seq40 event와
`package v2.4.1 ACTIVE after-apply state`를 담은 staged checkpoint를
고정한다. Fresh stage 2 apply authority는 aggregate review가 끝난 뒤에만
발급된다.

따라서 review된 seq40 event/checkpoint bytes에는 미래 stage 2 authority의
path/hash/bytes/nonce/lease를 넣을 수 없다. Target은 apply envelope를
authority가 결속한다고만 적고, committed transition event나 checkpoint가
그 authority를 어떻게 검증·재생하는지 정의하지 않는다.

또한 v2.4.1은 새 `package_id`와 version을 가진 active successor인데 다음
필수 lineage가 expected target set과 schema에 없다.

- v2.4 `ACTIVE` → `SUPERSEDED`와 v2.4.1
  `PREPARED_NOT_APPLIED` → `ACTIVE`의 exact 상태 전이
- source seq39/v2.4 active checkpoint의 immutable raw archive
- v2.4 manifest/hash와 archive/hash를 결속한 supersession record
- prepare event, activation event, authorization binding과 sequence/anchor
  replay 규칙
- transition history에서 predecessor package와 successor package 상태가
  동시에 모순 없이 검증되는 negative tests

기존 v2.4 package manifest는 predecessor archived checkpoint,
supersession record와 activation authorization/gate를 결속한다. R002의
단일 seq40 event와 receipt-requirement state만으로는 새 versioned package의
activation provenance와 supersession history를 동등하게 닫지 못한다.
이는 R001 `BLOCKING-005`의 atomic successor/P rebaseline 중 control
activation 부분을 종결하지 못한다.

#### Required remediation

- stage 2 authority가 존재한 뒤에만 authorization-bound final event/checkpoint
  bytes를 만들 수 있는 post-authority finalization과 독립 verification
  단계를 정의하거나, 사전 review된 template와 authority hash를
  content-addressed하게 결합하는 exact schema를 정의한다.
- v2.4 source checkpoint raw archive와 active supersession record를
  after-control expected targets에 추가한다.
- v2.4 및 v2.4.1의 before/after package status, activation status,
  manifest/hash, source archive, sequence 39→40과 transition anchor를
  event/checkpoint/checker가 모두 동일하게 재계산하도록 한다.
- authority binding, prepared/activated/superseded 순서, duplicate activation,
  wrong predecessor, wrong archive와 wrong sequence에 대한 negative tests를
  추가한다.
- 이 산출물들을 exact target transition set, checkpoint-last order,
  recovery contract와 full19 index 1·2·17·19에 반영한다.

## 4. MAJOR findings

### PRE-P-R002-MAJOR-001 — AFTER-CONTROL/APPLY REPRODUCIBILITY 미폐쇄

#### 근거

Target §5는 Lane A~D마다 frozen builder, builder manifest와 raw paths를
예약하고 aggregate에도 `build-aggregate.py`, `build-projection.py`와
validation raw paths를 둔다. 반면 `after-control/`과 `apply/`에는 결과
파일만 열거돼 있다.

특히 seq40 event, v2.4.1 static/control manifests, successor checkers/tests,
staged-after-checkpoint, application transaction/recovery/postcheck contracts와
apply envelope를 어느 frozen builder가 어떤 ordered inputs로 생성하는지
정의하지 않는다. `build-aggregate.py`가 이 두 directory를 소유한다는 exact
output DAG, builder manifest, 두 independent empty-root build의 byte
동일성이나 per-output raw generation receipt도 없다.

따라서 가장 중요한 after-control/checkpoint/apply bytes의 provenance를
독립 재현할 수 없고, source-before/after와 reviewed output 사이의 builder
폐쇄가 없다. R001 `MAJOR-002`는 lane/projection 부분에서는 개선됐지만
after-control/apply 부분에서 남아 있다.

#### Required remediation

- `after-control`과 `apply` 각각에 frozen builder와 builder manifest를
  추가하거나, `build-aggregate.py`가 소유할 exact output list와 dependency
  DAG를 명시한다.
- builder executable hash, ordered input path/hash/bytes, argv/cwd/env/tool,
  source double-read, output path/hash/bytes/mode와 rc를 가진 raw generation
  receipts를 예약한다.
- fresh empty output roots에서 두 번 독립 생성하여 모든 JSON, checker/test,
  checkpoint와 apply contract가 byte-exact 같음을 확인한다.
- after-control/apply builders, raw receipts와 output manifests도 NOREPLACE,
  non-symlink, `nlink=1`, candidate archive와 independent review 대상에
  포함한다.

### PRE-P-R002-MAJOR-002 — PROJECTION HOST INPUT CLOSURE 미폐쇄

#### 근거

Target §12의 bwrap은 active source를 `/source`에 read-only bind하고
repository 밖 projection을 `/work/walksafe`에 writable bind한다. distinct
inode, no hardlink, final-relative overlay, staged root/cwd/import와 active-root
전후 동일성도 명시한다.

그러나 실행 namespace에 host `/usr`와 `/etc` 전체를 그대로 read-only
bind한다. Receipt가 기록하는 bind source root의 inode/mode와 bwrap
executable hash는 그 아래 shared libraries, locale, NSS, timezone와 기타
configuration bytes를 결속하지 않는다. §12의 source/candidate projection
read allowlist는 host runtime read-set을 포함하지 않으며, observed runtime
read trace도 요구하지 않는다.

따라서 attested Python executable와 site-packages가 같아도 Python/pytest와
native wheels가 읽은 host library/configuration이 BEFORE/AFTER, repeat 또는
fresh stage 2 postcheck 사이에 달라질 수 있다. `--ro-bind`는 write를 막지만
host input을 immutable snapshot으로 만들지는 않는다.

#### Required remediation

- 필요한 runtime library/configuration만 포함하는 minimal synthetic
  read-only root를 만들고 모든 member의 path/hash/bytes/mode를 attestation과
  projection contract에 결속한다. 또는
- `/usr`와 `/etc`에서 실제 허용할 exact subtree/file manifest를 recursive
  hash하고 observed runtime read trace가 그 allowlist 밖을 읽으면
  fail하도록 한다.
- ELF loader/shared-library identity, locale/timezone/NSS 등 Python 실행에
  사용된 host inputs를 receipt에 포함하고 V2~V6 사이와 stage 2 postcheck에서
  동일성을 재확인한다.
- mutable host input이나 read-trace 부재 시 validation을 PASS로 기록하지
  않는다.

## 5. R001 findings 종결 상태

| R001 finding | R002 판정 | 근거 |
|---|---|---|
| `BLOCKING-001` authority 순서 | `CLOSED` | §3이 build/apply/R007 successor/P build/P apply를 5개 fresh authority로 분리하고 Phase 0.5 hard stop을 둔다. |
| `BLOCKING-002` isolated projection | `CLOSED_WITH_NEW_MAJOR` | §12가 external projection, final-relative overlay, distinct inode, explicit root/cwd/import, staged Quick2와 active-root write 0을 정의한다. 다만 host runtime input 폐쇄는 `R002-MAJOR-002`로 남는다. |
| `BLOCKING-003` stale regression | `NOT_CLOSED` | stale exact3는 처리했지만 seq40 이후 stale이 되는 v2.4/seq39 current tests를 유지한다. |
| `BLOCKING-004` full19 영향 폐쇄 | `NOT_CLOSED` | 1~19 matrix 구조는 생겼지만 index 17의 successor test routing이 잘못되어 semantic closure가 없다. |
| `BLOCKING-005` atomic/P rebaseline | `NOT_CLOSED` | checkpoint-last와 R007 successor 순서는 생겼지만 v2.4.1 activation/supersession history가 닫히지 않는다. |
| `MAJOR-001` W5 receipt | `CLOSED` | §5~§6이 add-only W5 successor receipt/manifest/commands와 review binding을 예약한다. |
| `MAJOR-002` builder/projection/raw | `PARTIALLY_CLOSED` | lane/aggregate/projection paths는 추가됐지만 after-control/apply generation provenance가 없다. |

## 6. 충족된 검수 축과 non-finding

다음은 target에서 확인됐다. 이는 위 findings를 상쇄하거나 실행 권한을
만들지 않는다.

- §1과 §3은 R002 및 review 자체가 build/apply authority가 아니며, stage 1
  authority receipt 없이는 candidate write 0으로 STOP한다고 명시한다.
- 5단계 authority는 각각 predecessor exact hash에 결속하는 fresh authority로
  분리돼 있다.
- full19는 index 1~19 전체를 `CHANGED` 또는 `UNAFFECTED`로 열거하고,
  index 5~14의 predecessor command byte 동일성을 재계산하도록 한다.
- isolated projection은 repository 밖 root, `/source` read-only,
  `/work/walksafe` writable, final-relative overlay, source와 다른 inode,
  hardlink 금지와 staged regular file `nlink=1`을 정의한다.
- Quick2는 BEFORE/AFTER 각각 explicit staged root/checkpoint/manifest argv를
  사용하며 V0~V7에 active-root 전후 identity를 둔다.
- atomic apply는 exact target universe, checkpoint CAS last, postcheck 뒤
  add-only receipt와 prefix-only recovery를 정의한다.
- 기존 R007을 수정·재사용하지 않고 actual seq40에 결속한 add-only successor
  design/review 후 P를 seq40→41로 다시 설계하도록 한다.
- W5 successor receipt와 Lane A~D builder/manifest/raw path가 추가됐다.

`<EXACT_COMMAND_ARGV...>`는 이 plan 단계에서는 finding이 아니다. §12가
builder의 pre-exec double-stat 뒤 final argv를 typed JSON token array에
치환하고 NUL-safe digest를 기록하도록 하며, Quick2 네 command는 별도로
explicit token array를 제시한다. §11과 §15도 exact argv/digest가 없으면
STOP하도록 한다.

이 non-finding은 future candidate나 executable contract에 literal
`<EXACT_COMMAND_ARGV...>`가 남아도 된다는 뜻이 아니다. Stage 1 authority
뒤 생성되는 `projection-contract.json`, regression contract, receipt 또는
실행 argv에 placeholder가 남아 있으면 fail-closed review rejection과
write 0 대상이다.

## 7. 최종 경계

이 review의 verdict는 `REJECTED_PLAN_DO_NOT_EXECUTE`다. Target R002는
add-only successor plan으로 위 `BLOCKING=4 / MAJOR=2`를 모두 닫고, 그
successor 자체가 byte-fixed independent review에서 findings `0/0/0`을 얻기
전에는 실행 근거로 사용할 수 없다.

이 review는 다음 권한이나 완료 credit을 만들지 않는다.

- external attested environment provisioning
- Lane A/B/C/D candidate build 또는 aggregate/after-control/apply build
- active lock, runner, test, validator와 control package 전환
- seq40 event/checkpoint 또는 v2.4.1 activation
- pre-P atomic apply/recovery
- R007 successor design/build 또는 P candidate/apply
- canonical/product/artifact/formal/device/gate/release 변경

공식 수치는 계속 artifact closed-equivalent `126/257`, formal
`0/279 PASS`, actual-device/real-event `0/0`, release gate `0/5`,
release `NOT_ELIGIBLE`로 유지한다.
