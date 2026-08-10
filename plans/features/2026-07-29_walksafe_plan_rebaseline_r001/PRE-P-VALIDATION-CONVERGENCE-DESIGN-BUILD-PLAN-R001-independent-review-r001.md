# PRE-P Validation Convergence Design/Build Plan R001 독립검수 R001

## 1. 검수 대상과 판정

| 항목 | 값 |
|---|---|
| review_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R001-INDEPENDENT-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R001.md` |
| target SHA-256 | `b25e9bd0a71e86d2bd0b8176369053ed4db5896cb3055e17591b19135ec61962` |
| target bytes | `27,140` |
| target lines | `640` |
| target type | `regular file` |
| verdict | `REJECTED_PLAN_DO_NOT_EXECUTE` |
| findings | `BLOCKING=5 / MAJOR=2 / MINOR=0` |
| review authority | `NONE` |

검수 시작과 종료 시 target의 SHA-256, bytes, lines가 위 값과 같음을
확인했다. 이 review는 target을 수정하지 않으며, target의 계획 실행, lane
candidate build, aggregate build, apply, checkpoint·canonical 전환, P17 build
또는 P apply 권한을 만들지 않는다.

```text
VERDICT=REJECTED_PLAN_DO_NOT_EXECUTE
BLOCKING=5
MAJOR=2
MINOR=0
TARGET_UNCHANGED=true
PLAN_EXECUTION_AUTHORIZED=false
LANE_CANDIDATE_BUILD_AUTHORIZED=false
ATOMIC_SUCCESSOR_BUILD_AUTHORIZED=false
APPLY_AUTHORIZED=false
P17_BUILD_AUTHORIZED=false
P17_APPLY_AUTHORIZED=false
```

## 2. 검수 범위

다음 축을 target 전체 640행과 현재 read-only source 사실에 대조했다.

- authority ceiling, non-effective 상태와 apply 금지
- P17 deferred 경계
- Lane A의 최소 `tests/requirements.lock` 범위
- Lane B exact `132/132/0`, orphan exact5 분류와 sealed predecessor 보존
- Lane C current exact5와 non-discovery/direct-only successor
- Lane D historical/current 분리와 full19 index 3·18
- A/B/C freeze 뒤 D final-pin 직렬 순서
- atomic managed-snapshot/control successor
- stop 조건, 첫 회귀와 second 241 suite
- expected candidate paths, NOREPLACE와 active-root write 0
- 공식 artifact/formal/device/gate/release 수치 불변
- staged projection의 경로·import·root 해석과 Quick2 실행 가능성
- full19 전체 index 영향 폐쇄
- 별도 candidate build/apply/P authority의 순서

## 3. BLOCKING findings

### PRE-P-R001-BLOCKING-001 — candidate build authority 순서가 닫히지 않음

#### 근거

Target §1은 `candidate build 권한=false`와
`LANE_CANDIDATE_BUILD_STARTED=false`를 고정하고, 이 계획과 review가 build
권한이 아니라고 선언한다. 그러나 §12 Phase 1~3은 lane candidate 생성,
independent review, D final-pin과 aggregate build를 수행한다. 사용자 확인은
Phase 4에서 모든 내부 review·보완이 끝난 뒤에만 요청한다고 적혀 있다.

따라서 현재 권한으로는 Phase 1의 첫 candidate write를 시작할 수 없고, Phase
4의 사용자 확인 시점까지 도달할 수도 없다. candidate build 권한과 atomic
apply 권한, 후속 P 설계·build 권한, P apply 권한도 서로 분리돼 있지 않다.

#### Required remediation

- Phase 0과 Phase 1 사이에 별도
  `PRE_P_VALIDATION_CONVERGENCE_DESIGN_BUILD_ONLY` authority gate를 둔다.
- 그 authority는 exact target plan/review와 허용 candidate namespace만
  결속하고 active source, checkpoint, canonical, product와 P17 write를
  허용하지 않아야 한다.
- aggregate findings-zero 뒤의 atomic apply authority를 별도로 둔다.
- pre-P 적용·전체 검증 뒤 add-only P design successor build 권한을 별도로
  둔다.
- P physical apply는 다시 fresh authority로 분리한다.

### PRE-P-R001-BLOCKING-002 — isolated staged projection과 Quick2 계약이 없음

#### 근거

Candidate 파일은 `plans/.../pre-p-validation-convergence-candidate-r001/`
아래에 있지만 future target은 repository root의 `tests/**`와 `scripts/**`다.
Target은 candidate 파일을 final-relative path에 보이는 isolated projection의
구성법, `ROOT_DIR`·`__file__`·cwd/import 해석, candidate와 active source의
우선순위, read/write root와 source double-read를 고정하지 않는다.

§12 Phase 3은 staged root에서 Quick2와 회귀를 실행하라고 한다. 그러나 A/B
candidate를 intended target에 overlay하면 현재 checkpoint가 고정한 managed
content-set과 달라진다. 현재 checkpoint를 그대로 읽는 Quick2는 그 staged
after-source에서 PASS할 수 없다. 예상 candidate member에도 staged
after-checkpoint/control projection이 없다. 반대로 live root에서 Quick2를
실행하면 candidate after-state를 검증하지 않는다.

따라서 staged Quick2 PASS와 active-root write 0을 동시에 입증하는 실행 경로가
없다.

#### Required remediation

- repository 밖 isolated temp root에 current source를 exact read-only
  projection하고 candidate bytes를 future final-relative path에 overlay하는
  결정적 projection 계약을 추가한다.
- root FD, cwd, import path, `__file__` 해석, owner/mode/link, path mapping,
  source/candidate hash와 read/write allowlist를 고정한다.
- candidate namespace에 staged after-checkpoint와 managed membership/count,
  path-set/content-set hash, transition/control projection을 add-only로
  포함한다.
- Quick2가 명시적인 staged root와 staged checkpoint를 읽도록 exact argv를
  고정한다.
- cache, temp, build output와 raw receipt는 isolated writable root만 사용하고
  active repository write가 0임을 전후 inventory와 write trace로 검증한다.

### PRE-P-R001-BLOCKING-003 — 첫 회귀 `0 FAIL` 종료조건에 도달할 수 없음

#### 근거

Lane B는 active predecessor test를 보존하면서 runner semantic delta를 orphan
exact5 등록으로 제한한다. 현재 auto-discovered
`tests/test_walksafe_test_database_preflight.py`는 runner의
`-p no:cacheprovider` 출현 횟수를 `3`으로 고정하지만 sealed/current runner에는
그 명령이 `4`회 존재한다. 새 non-discovery/direct-only helper를 추가해도 기존
auto-discovered test의 실패는 사라지지 않는다.

Lane C도 stale historical/current route expectation을 가진 기존 managed test를
보존하고 non-discovery helper만 추가한다. Target은 기존 실패 test를 current
suite에서 역사 전용으로 재분류하거나 current successor command로 대체하는
계약을 두지 않는다. 그 상태에서 §12 Phase 3은 기존 `7 FAIL`이 전부 0이
되어야 한다고 요구한다.

따라서 기존 stale test를 그대로 current regression에서 실행하면서 새
direct-only helper만 추가하는 계획은 자체 종료조건을 만족할 수 없다.

#### Required remediation

- stale legacy test는 bytes를 보존한 채 historical/targeted-only로
  재분류하고 `all` current result에서 제외하는 명시적 successor 계약을 둔다.
- current semantics는 add-only direct successor helper를 exact command로
  실행한다.
- legacy test와 successor test가 각각 어느 suite/index에 속하는지 exact
  inventory를 만든다.
- 이 변경은 runner의 orphan exact5 등록 외 semantic delta를 추가하므로 Lane B
  scope, candidate hash, review와 expected tuple을 새 revision에서 다시
  동결한다.
- 첫 450-test 계열과 second 241 suite 모두 실제 실행해 `0 FAIL`을 확인한다.

### PRE-P-R001-BLOCKING-004 — full19 영향 폐쇄가 index 3·18에 한정됨

#### 근거

Target §8.4는 full19 successor의 변경을 index 3
`BASELINE_MATERIALIZATION`과 index 18 `CONTROL_AND_TRACE_PYTEST` 중심으로만
고정한다. 그러나:

- Lane A의 lock successor는 full19 Python 실행환경과 lock provenance에
  영향을 준다.
- Lane B의 runner successor는 index 15 test-layer registry validation과
  관련 suite argv/path/hash에 영향을 준다.
- Lane C의 exact5 successor는 index 4 Android Gateway boundary와 current
  successor helper 실행 경로에 영향을 준다.
- Lane D는 index 3뿐 아니라 index 18의 helper·historical/current 입력을
  바꾼다.

나머지 index가 영향을 받지 않는다는 byte-exact 판정도 없다. 따라서
index 1~19 전체 order digest를 계산해도 입력·path·hash·argv 영향 폐쇄가
완전하다고 검증할 수 없다.

#### Required remediation

- A~D 각각에 대해 full19 index 1~19의
  `CHANGED` 또는 `UNAFFECTED` impact matrix를 만든다.
- `CHANGED` index는 executable/module role, command, argv, cwd, env,
  input path/hash/bytes, timeout/output limit과 predecessor/successor를
  모두 새로 동결한다.
- `UNAFFECTED` index는 이전 contract와 byte-exact 동일함을 독립 재계산한다.
- 최소한 Lane B의 index 15와 Lane C의 index 4/current-helper 경로를
  명시적으로 처리한다.
- Lane A lock과 Python check index들의 environment provenance 관계를
  명시한다.
- 그 뒤에만 exact 19 order와 전체 successor digest를 계산한다.

### PRE-P-R001-BLOCKING-005 — atomic successor와 후속 P rebaseline이 불완전함

#### 근거

Target은 결과를
`ONE_ATOMIC_MANAGED_SNAPSHOT_CONTROL_SUCCESSOR_CANDIDATE`라고 부르지만 §4와
§11의 member/target role에는 staged after-checkpoint, transition event,
managed path membership/count/hash, atomic application/recovery member가 없다.
이 상태로 A/B/C/D target을 반영하면 active checkpoint와 Quick2는 즉시
불일치하며, partial/committed/recovery 상태를 판정할 수 없다.

또한 현재 R007 P 설계는 exact sequence 39 source에서 sequence 40으로 가는
P17을 고정한다. Pre-P managed-snapshot/control successor가 적용되면 그
source/checkpoint와 expected member hash가 달라지므로 R007 P17은 그대로
build할 수 없다. 그러나 §11은 aggregate findings-zero review와 별도 authority가
생기면 P17 deferred가 풀릴 수 있는 것처럼 적고, add-only P design successor와
그 독립검수를 필수 단계로 두지 않는다.

#### Required remediation

- Aggregate candidate exact member에 staged after-checkpoint, transition/event,
  managed inventory와 hash, atomic application plan, receipt/incident/recovery
  계약을 포함한다.
- A~D partial switch가 불가능하고 checkpoint-last commit/recovery가
  machine-checkable한 exact transaction으로 만든다.
- 올바른 후속 순서를 다음처럼 고정한다.

```text
pre-P aggregate build/review
→ fresh pre-P apply authority
→ atomic pre-P apply/recovery closure
→ Quick2 + first regression + second 241 + clean install PASS
→ changed source/checkpoint에 결속한 add-only R007 successor design/review
→ 별도 P candidate build authority
→ P candidate build/review
→ 별도 fresh P apply authority
```

- 기존 R007, sequence 39 history 또는 P17을 수정하지 않는다.

## 4. MAJOR findings

### PRE-P-R001-MAJOR-001 — Lane A의 W5 add-only lineage receipt가 누락됨

#### 근거

Lane A는 predecessor W5 lock 생성 input·command·toolchain을 재확인하고 새
clean install을 요구하지만, §4 expected paths와 §11 aggregate members에는
W5 add-only successor receipt가 없다. Generic lane manifest나 aggregate
validation receipt만으로는 predecessor W5에서 새 lock bytes, deterministic
two-build, clean install과 review까지의 lineage를 닫지 못한다.

#### Required remediation

- Lane A expected paths에 W5 add-only successor receipt와 그 independent
  review binding을 추가한다.
- Receipt는 predecessor W5 exact path/hash, input constraints,
  CPython/pip/pip-tools, index/cache policy, two candidate outputs,
  delta inventory, clean install raw outputs, `pip check`, Pillow version과
  lane A candidate/review hash를 결속한다.
- 과거 W5 receipt나 기존 lock을 수정하지 않는다.

### PRE-P-R001-MAJOR-002 — 결정론적 builder·projection·raw receipt 경로가 없음

#### 근거

§4 expected paths는 candidate output, manifest와 review 중심이다. Lane별
candidate를 만드는 frozen builder, isolated projection builder/runner와
검증 raw receipt의 add-only physical 경로를 예약하지 않는다.

그 결과 Lane A의 deterministic two-build, Lane B/C/D helper의 final-relative
path 실행, Lane D의 historical `b3dde.../2630` preimage·generator·input replay를
동일 bytes와 argv로 독립 재현할 수 없다. 계획상의 명령 설명만으로는
NOREPLACE, source-before/after 또는 review 대상 build process를 고정할 수 없다.

#### Required remediation

- 각 lane의 frozen builder와 builder manifest path를 expected set에 추가한다.
- 공통 isolated projection builder/runner와 projection manifest를 add-only
  path로 고정한다.
- stdout/stderr, exit code, environment/tool hashes, source read-set과 write-set을
  가진 raw validation receipt paths를 추가한다.
- D historical replay가 사용할 exact preimage 또는 결정적 reconstruction
  generator와 모든 input path/hash/bytes를 직접 결속한다.
- Builder, projection, raw receipts도 regular/non-symlink, NOREPLACE와
  independent review 대상에 포함한다.

## 5. 충족된 검수 축

다음 의도와 사실 표기는 target에서 확인됐다. 이는 위 findings를 상쇄하거나
계획 실행을 허용하지 않는다.

- `NON_EFFECTIVE_PLAN_ONLY`, apply 금지와 P17 deferred가 명시돼 있다.
- Lane A의 active source 변경 범위는 `tests/requirements.lock` 한 건으로
  제한하려는 의도가 있다.
- Lane B는 exact `132/132/0`, orphan exact5 분류와 sealed runner predecessor
  보존을 목표로 한다.
- Lane C는 current exact5 path/method와 non-discovery/direct-only helper를
  정의한다.
- Lane D는 historical `b3dde.../2630`과 current `ed331.../3108`을 분리하려는
  의도와 index 3·18 successor 필요성을 기록한다.
- A/B/C freeze 뒤 D final-pin이라는 직렬 순서가 있다.
- Lane별 partial apply를 금지하고 atomic integration을 요구한다.
- first regression `7 FAIL`과 second 241 `NOT_RUN`을 숨기지 않는다.
- 공식 artifact/formal/device/gate/release 수치는 변하지 않는다고 명시한다.
- 기존 source/history/checkpoint/canonical/product 상태의 수정·삭제를
  금지한다.

## 6. 최종 경계

이 review의 verdict는 `REJECTED_PLAN_DO_NOT_EXECUTE`다. Target R001은
add-only successor plan으로 위 `BLOCKING=5 / MAJOR=2`를 모두 닫고, 그
successor 자체가 같은 byte-fixed independent review에서 findings
`0/0/0`을 얻기 전에는 실행 근거로 사용할 수 없다.

이 review는 다음 권한이나 완료 credit을 만들지 않는다.

- Lane A/B/C/D candidate build
- aggregate candidate build
- active lock·runner·test·validator 전환
- checkpoint, Goal event, canonical 또는 artifact 상태 변경
- P17 build·review·apply
- v2.5/r022 activation
- product/formal/device/gate/release 작업 또는 상태 상승

공식 수치는 계속 artifact closed-equivalent `126/257`, formal
`0/279 PASS`, actual-device/real-event `0/0`, release gate `0/5`,
release `NOT_ELIGIBLE`로 유지한다.
