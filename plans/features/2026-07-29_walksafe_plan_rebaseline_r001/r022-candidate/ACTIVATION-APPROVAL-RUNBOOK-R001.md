# WalkSafe r022 후보 승인·활성화 준비 R001

- 문서 ID: `WS-WALKSAFE-R022-ACTIVATION-APPROVAL-RUNBOOK-20260730-R001`
- 상태: `PREPARED_NOT_APPROVED_NOT_APPLIED`
- 작성일: `2026-07-30`
- 적용 대상: staged r022 Gap·Backlog pair
- 현재 정본: r021
- 현재 Goal package: v2.4, event tail sequence 39

## 1. 결론

68개 정책↔Gap 재평가와 r022 content 후보 작성은 끝났다. 그러나 현재 v2.4
checker 계약에는 이 후보를 한 번에 활성화할 수 있는 유효한 event 경로가 없다.
따라서 다음 단계는 r022 파일을 정본에 바로 복사하는 것이 아니라,
`bulk rebaseline + Backlog operational delta + role-global normalization`을
명시적으로 허용하는 migration exception 또는 successor control contract의
후보를 먼저 만드는 것이다.

이 문서는 승인 요청을 준비할 뿐 승인·적용·Goal 시작을 주장하지 않는다.

## 2. 고정된 후보

| 역할 | 경로 | SHA-256 | bytes |
|---|---|---|---:|
| exact68 ledger | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate/exact68-reassessment-ledger.json` | `576823ce4b1c88822e1a994fb02d1e7f427434d922ef8765f4196a15959a9847` | 150,302 |
| Gap r022 candidate | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate/implementation-gap-r022.candidate.json` | `eeed935dc003bb5a1dab9ae71ea1d24db9460a6cf0c107fe419707f5a66e3950` | 517,217 |
| Backlog r022 candidate | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate/implementation-backlog-r022.candidate.json` | `fae016c3151e5d28845a7e686af12f29f6f008f9f4f7aff641dd2f2bae3bd5f3` | 58,796 |
| detached pair manifest | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate/gap-backlog-pair-manifest.json` | `aaf3885fc98a28654f40e1093e2f353cf26279fa17e3e24ae8ab512c0b336a20` | 10,606 |

Pair fingerprint:
`aac6c46c9959a89fae360177b4debc181abbc61c3dc07574e53486340ef8da29`.

후보 결과:

- reviewed 68
- changed assessment 31
- byte-exact carry-forward 37
- status change 8
- `BLOCKED 5 / CONFLICTING 14 / EVIDENCE_MISSING 4 / MISSING 6 /
  PARTIAL 39 / IMPLEMENTED 0`
- formal `0/279`
- actual-device execution `0`
- release gate `0/5`
- release `NOT_ELIGIBLE`

## 3. 핵심 의미 판정

### 3.1 GAP-055

`GAP-055 / FP-046`은 `MISSING → CONFLICTING`이다.

- Android는 consent-control secret과
  `installation_id/requested_at` 중심 요청·상태 형식을 사용한다.
- Gateway는 account-deletion-status secret과
  `client_revision/confirmation` 중심 요청·다른 상태 형식을 요구한다.
- GET query, 응답 필드, inventory key와 status 형식도 일치하지 않는다.

양쪽 구현의 존재만으로 `PARTIAL`을 부여하지 않았다. 현재 wire 연결은 직접
호환되지 않으므로 보수적으로 `CONFLICTING`을 유지한다.

### 3.2 다음 단일 leaf

후보 Backlog의 다음 항목은 다음과 같다.

```text
epic_id=EPIC-03
work_item_id=EPIC-03-FP008-ADMIN-REVIEW-DELIVERY
source_policy_id=FP-008
gap_id=GAP-017
status=PLANNED_NEXT_NOT_MATERIALIZED
```

FP-047은 내부 목표에서 완료됐고, r021의 FP-048 포인터는 FP-008, FP-046,
NPC-SINGLE-ADMIN을 건너뛰므로 stale이다. 다만 이 leaf는 아직 Goal 파일·event로
materialize되지 않았다.

## 4. 현재 v2.4에서 즉시 적용할 수 없는 이유

1. r022는 31개 assessment를 한 번에 바꾼다.
2. 현재 `POLICY_GAP_WORK` producer는 한 정책·한 Gap만 소유해야 한다.
3. GAP-055의 `CONFLICTING` 결과는 해당 producer의 허용 terminal status 밖이다.
4. r021의 비정규 `implementation_snapshot`,
   `source_predecessor.preserved_unchanged=false`,
   `next_single_action.work_item_id` 누락을 정상화하면 Gap·Backlog 모두
   role-global `*` impact가 발생한다.
5. Backlog의 EPIC 진행 설명과 next pointer는 operational projection 변화다.
6. 현 checker는 이 operational delta를 producer 없는 canonical update에서
   허용하지 않는다.

따라서 아래 두 접근은 모두 충분하지 않다.

- 31개를 하나의 기존 `POLICY_GAP_WORK` event로 적용
- producer 없는 단순 `CANONICAL_BINDINGS_UPDATED` event로 적용

## 5. 권고 승인 절차

### 승인 A — control migration 후보 작성

사용자가 승인할 범위:

- 위 네 파일의 정확한 bytes를 r022 content 후보로 수용
- bulk exact68 rebaseline, `CONFLICTING` status, role-global normalization,
  Backlog operational pointer 갱신을 검증할 successor control contract 후보 작성
- 해당 checker·test·event/runbook 후보 작성과 독립검수

승인 A에 포함되지 않는 것:

- r022 canonical 경로 생성·binding 전환
- checkpoint 수정
- Goal event append
- FP-008 leaf materialize/start
- 제품 코드 수정
- formal·실기기·외부 event·gate·release claim

권고 입력 문구:

```text
승인 A: pair fingerprint
aac6c46c9959a89fae360177b4debc181abbc61c3dc07574e53486340ef8da29
인 r022 content 후보를 수용하고, 이를 안전하게 적용할 successor control
contract 후보와 checker/test/실행 runbook 작성까지만 승인합니다.
canonical 적용, checkpoint/Goal 변경, leaf 시작과 제품 코드는 아직 승인하지 않습니다.
```

### 승인 B — 검수된 migration과 canonical 적용

승인 A 결과로 생성된 exact migration package의 SHA-256·bytes, 독립검수 findings,
event impact closure와 rollback 규칙을 다시 제시한다. 사용자가 그 정확한 package를
별도로 승인한 뒤에만 다음을 수행한다.

1. 승인된 successor/migration control을 add-only로 활성화
2. r022 두 candidate bytes를 intended canonical 경로에 add-only materialize
3. Gap·Backlog pair binding을 한 event에서 전환
4. checkpoint와 working snapshot을 successor event에 맞게 전환
5. 전체 continuation/Goal checker 실행
6. ready frontier 재계산

### 승인 C — FP-008 leaf materialize/start

r022 적용 후 checker가 PASS하고 ready frontier가 다시 계산된 다음, 정확히 하나의
leaf만 별도로 승인받아 materialize/start한다. 이 단계에서도 제품 코드 변경 범위와
검증 명령을 먼저 제시한다.

## 6. 적용 전 검증 기준

- source r021 Gap/Backlog physical hash와 logical seal 유지
- exact68 record 68개, 중복·누락 0
- changed31/carry37 exact
- GAP-055 `CONFLICTING`
- mapping fingerprint
  `3423815d07d130ab85c6729733b26c7061ae13f63b4f344dfecdc1c47c4f80b5`
- hard-dependency fingerprint
  `f57daf2de778e697b1983a1271d2bc5902b0f9e581982b1cb4e3d2349ca86535`
- assessment·Gap·Backlog·manifest seal PASS
- candidate physical pair fingerprint exact
- formal/actual/gate/release credit 0
- independent review `BLOCKING=0 MAJOR=0`

현재 확인 명령:

```bash
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
python3 -B scripts/build_walksafe_plan_rebaseline_r022_candidate_20260730.py --check
.venv/bin/python -B -m pytest -q \
  tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py
bash scripts/run_walksafe_test_layers_20260711.sh validate
```

## 7. 현재 quick-check 해석

r022 작업 시작 전 활성 seq39 snapshot에서는 v2.4 quick check 두 개가 PASS했다.
staged 파일과 생성기·test가 추가된 뒤에는 checkpoint의 frozen working snapshot과
현재 작업트리가 달라져 다음 두 drift가 예상대로 검출된다.

```text
v2.4 seq39 checkpoint projection differs
v2.4 working snapshot content-set SHA-256 differs
```

이 상태에서 checkpoint를 임의 수정해 PASS로 만들지 않는다. 후보 검증은 위의
builder/test로 수행하고, v2.4 전체 quick check는 승인 B의 add-only successor
event와 checkpoint 전환이 완료된 뒤 다시 PASS해야 한다.

## 8. 실패·복구 규칙

- 정본 r021과 seq39 event history는 제자리 수정하지 않는다.
- 후보 hash가 하나라도 달라지면 기존 승인 문구를 재사용하지 않는다.
- canonical materialization은 기존 파일 overwrite가 아니라 r022 새 경로 add-only다.
- pair 중 하나만 생성되거나 binding 전환이 중단되면 event를 완료로 기록하지 않는다.
- checker 실패 시 r021 binding과 기존 checkpoint를 유지하고 새 후보/event를
  non-effective 실패 기록으로 남긴다.
- FP-008은 r022 적용·frontier 재계산 전에는 materialize하지 않는다.

## 9. 현재 다음 행동

`WAIT_FOR_CANDIDATE_REVIEW_THEN_REQUEST_APPROVAL_A`

