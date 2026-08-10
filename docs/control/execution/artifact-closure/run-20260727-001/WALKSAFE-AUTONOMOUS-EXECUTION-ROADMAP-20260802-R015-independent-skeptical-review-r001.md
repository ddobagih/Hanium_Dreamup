# WalkSafe R015 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R015-INDEPENDENT-SKEPTICAL-REVIEW-R001
type = INDEPENDENT_SKEPTICAL_STATIC_REVIEW
reviewer_agent = /root/r015_skeptical_review
reviewer_axis = SKEPTICAL_ADVERSARIAL
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R015.md
target_sha256 = f611e870e6c85b0d649aee0911fa938d5c27c37ab54420cf57af924d42e920ad
target_bytes = 21477
target_lines = 450
reviewed_at = 2026-08-02T10:06:04+09:00
status = REVISION_REQUIRED
findings = 4/0/0
blocking = 4
major = 0
minor = 0
authority_granted = NONE
```

## 범위와 판정

frozen R015 450줄 전부를 읽고, R014 skeptical B02~B07의 폐쇄 주장, 현재 v2.4 정본과
existing v2.5 builder/validator의 실제 mode·freshness·resolved-output 계약을 적대적으로
대조했다. 대상은 검수 시작 시 regular `0664`, uid/gid `1000/1000`, nlink 1이며 위
SHA-256·bytes·lines와 일치했다. candidate/canonical/Goal/product 실행 또는 수정은 하지
않았고 이 review 파일만 추가했다.

R015는 B05 lock/held-FD CAS, B06 네 번의 bootstrap inventory 관찰, B07의 checkpoint
기반 비효력 경계를 구체화했다. 그러나 아래 네 차단점 때문에 `PASS 0/0/0`과
`R015_BOUNDED_INTERNAL_EXECUTION_ONLY` 권한을 부여할 수 없다.

## Findings

### R015-SK-B01 — [BLOCKING] 600초 QUICK freshness가 rollback 가능한 wall clock만 사용한다

- 근거: R015:288-320은 `valid_until=completed_at+600s`와 만료 시 새 attempt만 정하지만,
  시간원·clock rollback·monotonic elapsed를 규정하지 않는다. R015:358도 checkpoint 직전
  “fresh” 재확인만 요구한다. existing validator는 실제로
  `datetime.now(timezone.utc)`와 receipt wall-clock을 비교한다
  (`scripts/walksafe_v2_5_candidate_validation.py:3070-3112`).
- 반례/영향: QUICK 완료 뒤 시스템 wall clock을 뒤로 이동하면 실제 600초를 넘긴 receipt도
  `now <= valid_until`을 계속 만족한다. 오래된 source/product 관찰로 C1을 commit할 수 있어
  600초 freshness gate가 시간 경과를 제한하지 못한다.
- 최소 교정: `PREPARE → 마지막 C1 CAS`를 같은 supervisor process·같은 lock epoch로 고정하고
  `time.monotonic_ns()`의 시작/완료/commit 직전 elapsed가 600초 이하임을 강제한다. wall-clock은
  감사용 비권위 값으로만 남긴다. future/rollback/jump와 599/600/601초 경계 negative test를
  추가한다.

### R015-SK-B02 — [BLOCKING] 만료·crash 뒤 새 QUICK가 fixed add-only path와 충돌한다

- 근거: R015:319-320은 검수 중 만료 시 새 attempt/quick/review를 요구하고, R015:353은 dynamic
  receipts를 add-only/no-replace로 내구화하며, R015:385-387은 partial files를 보존한 채 새 QUICK와
  recovery receipt로 같은 transaction을 resume한다고 한다. 그러나 existing v2.5 계약의 QUICK는
  단일 고정 경로 `fresh-quick-gate-receipt.json`이다
  (`scripts/walksafe_v2_5_candidate_validation.py:139-140`). R015는 attempt-specific path나 supersession
  선택 규칙을 도입하지 않는다.
- 반례/영향: QUICK receipt가 내구화된 뒤 C1 전에 crash하고 600초가 지난다. 기존 fixed receipt는
  삭제·덮어쓰기 금지이고 새 receipt는 no-replace로 게시할 수 없다. 따라서 문서가 요구한 새 QUICK
  자체가 생성 불가능하며, stale receipt를 재사용하거나 삭제해야만 진행되는 wedge다.
- 최소 교정: QUICK/resolved review/intent/recovery를 transaction+attempt ID 아래 add-only 경로로
  분리하고, 선택된 exact attempt binding을 C1과 active validator가 검증하게 한다. 더 단순한 안전
  설계는 resume 주장을 제거하고 이 상태를 `NEW_REVISION_REQUIRED` terminal로 두는 것이다.

### R015-SK-B03 — [BLOCKING] 새 QUICK는 partial final bytes와 달라져 `RESUME_SAME_TX`가 성립하지 않는다

- 근거: R015:297-315의 resolved 12와 manifest는 QUICK를 결속하고, R015:317-320은 만료 시 새
  attempt/quick/review를 요구한다. existing builder reality에서도 final history와 C1이 QUICK receipt의
  physical hash/bytes를 직접 결속한다
  (`scripts/walksafe_v2_5_candidate_validation.py:3530-3536,3583-3614`). 반면 R015:334-349의
  recovery는 preexisting member가 `EXACT_TARGET`일 때만 skip하고 다른 bytes는 overwrite 없이
  중단한다.
- 반례/영향: 첫 attempt가 QUICK-A에 결속된 final history 또는 다른 dynamic-derived member를 게시한
  뒤 crash한다. QUICK-B로 새 attempt를 만들면 history/C1/resolved target bytes가 달라진다. 남은
  QUICK-A member는 QUICK-B의 exact target이 아니므로 `RESUME_SAME_TX`는
  `DIVERGENT_COLLISION_NO_OVERWRITE`로 끝난다. R015:386의 “새 quick + same-transaction recovery”는
  도달 불가능하다.
- 최소 교정: 새 attempt마다 quick-dependent 모든 bytes를 attempt-specific transaction directory에
  두고 C1 한 번만 선택 포인터로 commit한다. pre-C1 flat final에는 attempt-invariant bytes만 허용한다.
  또는 partial pre-C1 crash를 비복구 terminal로 명시한다. 각 member 직후 crash, QUICK 만료, 새
  attempt 재도출, old-attempt 선택 거부를 fail-first로 고정한다.

### R015-SK-B04 — [BLOCKING] FP008의 “runbook full 19-command gate”는 v2.5 활성 상태에서 통과할 수 없다

- 근거: R015:411-414는 v2.5 `ACTIVE_VERIFIED` 뒤 “runbook full 19-command start gate”가 모두
  PASS해야 `GOAL_STARTED`를 기록한다고 한다. 하지만 현재 권위 있는 full19 계약은
  `docs/control/README.md:14-35`에서 v2.4 continuation/Goal checker와 v2.4 repository-state 명령을
  exact하게 고정한다. 두 checker도 v2.4 package ID와 static manifest를 상수로 요구한다
  (`scripts/check_walksafe_project_continuation_v2_4.py:72-76`,
  `scripts/check_walksafe_goal_graph_v2_4.py:1866-1868`). R015의 resolved 12에는 runbook/control README나
  versioned v2.5 full19 계약이 없다.
- 반례/영향: R015가 C1에서 package를 v2.5로 활성화한 정상 상태에서 full19의 첫 두 명령과 19번째
  명령은 v2.4 identity 불일치로 실패한다. 따라서 `FULL19_PASS ∧ GOAL_STARTED_VALID`은 도달 불가능하며,
  이를 우회해 slice를 구현하면 제품 `GOAL_STARTED` gate 위반이다.
- 최소 교정: v2.5 package에 exact 19 argv/order/hash, v2.5 continuation/Goal/repository-state 명령,
  source snapshot 및 원출력 receipt 계약을 versioned 정본으로 포함하고 C1이 결속하게 한다. v2.4
  full19은 v2.5 active에서 실패하고 새 v2.5 full19만 통과하는 회귀를 추가한다.

## R014 폐쇄 재판정

| R014 축 | R015 판정 |
|---|---|
| B02 mode/preimage 분리 | `PARTIAL` — mode 이름은 분리됐지만 QUICK 만료·재시작 predicate가 B02/B03에서 모순된다. |
| B03 exhaustive impact | `PARTIAL` — changed-subject/affected closure 재계산은 진전이나 본 review의 핵심 차단과 독립적으로 candidate review에서 exact disposition을 재검증해야 한다. |
| B04 staged review binding | `PARTIAL` — resolved binding은 추가됐지만 attempt 교체 시 어느 review/bytes가 authoritative한지 고정되지 않았다. |
| B05 lock/CAS | `CLOSED_FOR_COOPERATING_R015_WRITERS` — 범위를 비협조 writer까지 과장하지 않은 점을 포함한다. |
| B06 bootstrap repeat | `CLOSED` — 네 관찰 시점과 B0 결속을 요구한다. |
| B07 flat atomicity | `CLOSED_AS_LOGICAL_CHECKPOINT_ATOMICITY` — C0에서 flat r022 authority 0, C1+exact members만 effective로 제한한다. |

## 결론

R015는 `REVISION_REQUIRED`다. frozen R015와 이 review를 입력으로 한 successor roadmap 및 두 fresh
independent review가 `PASS 0/0/0`이 되기 전에는 candidate source/final/canonical/Goal/product write를
0으로 유지해야 한다. 이 review는 사용자 identity, 외부 attestation, formal PASS, release 또는 제품
완료 증거가 아니며 어떤 실행 권한도 부여하지 않는다.

검수 종료 시에도 대상 SHA-256 `f611e870e6c85b0d649aee0911fa938d5c27c37ab54420cf57af924d42e920ad`,
21,477 bytes, 450 lines가 동일한지 read-only로 재검산한다.
