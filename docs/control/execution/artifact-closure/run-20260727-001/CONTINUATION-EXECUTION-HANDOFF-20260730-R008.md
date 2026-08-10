# WalkSafe 새 터미널 실행 준비 인계 R008

- 문서 ID: `WS-CONTINUATION-EXECUTION-HANDOFF-20260730-R008`
- 상태: `SOURCE_DRIFT_BLOCKED_RESTART_HANDOFF_REVIEW_PENDING`
- 작성일: `2026-07-30`
- predecessor:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R007.md`
- predecessor SHA-256:
  `df36cd21dc2f1e76f20698a0c1ccd3696f311e1806b2700ea588f86910043c0b`
- predecessor bytes: `8,323`
- predecessor FAIL review:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R007-independent-review-r001.md`
- predecessor review SHA-256:
  `e9796aa146af6e8f18f1872260116befed731d0266d8a3cfb057e788557f2787`
- predecessor review bytes: `3,206`
- 저장소:
  `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`
- branch: `codex/walksafe-rc2-hardening-20260715`
- HEAD: `a3ad7eead6b5d834d3e0675422475a9aad351e3d`

## 1. 범위와 상속

이 R008은 R007을 수정하지 않고 R007 독립검수의 통합 MAJOR 1건만 닫는
add-only successor다. 다음 세 가지만 교정한다.

1. R004~R006 handoff/review를 읽은 뒤 R004 §6 item 1~15를 실제로 읽는
   단계를 fail-fast보다 앞에 복원한다.
2. R005 FAIL receipt 실물과 판정을 fail-fast보다 먼저 exact 결속한다.
3. 검토가 끝난 frozen R007 §3 block을 그대로 추출·검증·실행한다.

R007의 source 상태, 두 source 전략, P → M → FP-008 승인 DAG, 금지선과
fail-closed semantics는 그대로 상속한다. R008은 source 전략 선택, source
변경, candidate build, 승인 요청, canonical/Goal/product write 권한이 아니다.

## 2. 새 터미널 전체 읽기 순서

공통 prefix는
`docs/control/execution/artifact-closure/run-20260727-001/`이다.

1. `CONTINUATION-EXECUTION-HANDOFF-20260730-R004.md`
2. `CONTINUATION-EXECUTION-HANDOFF-20260730-R004-independent-review-r001.md`
3. `CONTINUATION-EXECUTION-HANDOFF-20260730-R005.md`
4. `CONTINUATION-EXECUTION-HANDOFF-20260730-R005-independent-review-r001.md`
5. `CONTINUATION-EXECUTION-HANDOFF-20260730-R006.md`
6. `CONTINUATION-EXECUTION-HANDOFF-20260730-R006-independent-review-r001.md`
7. R004 §6 item 1~15를 그 상대순서대로 실제로 읽는다.
8. `CONTINUATION-EXECUTION-HANDOFF-20260730-R007.md`
9. `CONTINUATION-EXECUTION-HANDOFF-20260730-R007-independent-review-r001.md`
10. 이 R008 §3 fail-fast를 실행한다.
11. `CONTINUATION-EXECUTION-HANDOFF-20260730-R008-independent-review-r001.md`

R004 §6 item 1~15는 다음 exact 순서다.

1. `AGENTS.md`
2. `docs/control/walksafe-project-resumption-runbook.md`
3. `docs/control/goals/walksafe-completion-graph-v2-3/README.md`
4. `docs/control/goals/README.md`
5. `docs/control/goals/walksafe-completion-graph-v2-4/README.md`
6. `docs/control/goals/walksafe-completion-graph-v2-2/00-master-goal.md`
7. `docs/control/walksafe-project-continuation-checkpoint.json`
8. `docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json`
9. `CONTINUATION-PLAN-HANDOFF-20260729-R003.md`
10. `CONTINUATION-PLAN-HANDOFF-20260729-R003-independent-review-r001.md`
11. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/INDEPENDENT-REVIEW-R001.md`
12. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/FP008-NEXT-LEAF-PREPARATION-R001-independent-review-r001.md`
13. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R006-independent-review-r001.md`
14. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R007.md`
15. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R007-independent-review-r001.md`

9~10의 공통 prefix는 위 실행 인계 공통 prefix와 같다. R004~R006의 실행
블록은 별도로 실행하지 않는다.

## 3. receipt-bound frozen R007 fail-fast

아래 블록은 R007과 그 FAIL receipt, 누락됐던 R005 FAIL receipt를 먼저
no-follow exact 검증한다. 그 뒤 frozen R007 §3 Bash block만 추출해
`bash -n`과 실제 실행을 수행한다.

```bash
(
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
set -euo pipefail

ws_check_doc() {
  local ws_sha="$1" ws_bytes="$2" ws_path="$3"

  [[ ! -L "$ws_path" ]] || return 1
  [[ -f "$ws_path" ]] || return 1
  [[ "$(stat -c '%F' -- "$ws_path")" == 'regular file' ]] || return 1
  [[ "$(sha256sum -- "$ws_path" | cut -d' ' -f1)" == "$ws_sha" ]] || return 1
  [[ "$(wc -c < "$ws_path")" -eq "$ws_bytes" ]] || return 1
}

ws_prefix='docs/control/execution/artifact-closure/run-20260727-001'
ws_r005_review="$ws_prefix/CONTINUATION-EXECUTION-HANDOFF-20260730-R005-independent-review-r001.md"
ws_r007="$ws_prefix/CONTINUATION-EXECUTION-HANDOFF-20260730-R007.md"
ws_r007_review="$ws_prefix/CONTINUATION-EXECUTION-HANDOFF-20260730-R007-independent-review-r001.md"

ws_check_doc \
  65a0772e00d7865d12c9c8aee532e6be96935c0922eeee0b784a98afadae88fc \
  2890 "$ws_r005_review" || exit 1
grep -Fqx -- '- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R006`' \
  "$ws_r005_review" || exit 1

ws_check_doc \
  df36cd21dc2f1e76f20698a0c1ccd3696f311e1806b2700ea588f86910043c0b \
  8323 "$ws_r007" || exit 1
ws_check_doc \
  e9796aa146af6e8f18f1872260116befed731d0266d8a3cfb057e788557f2787 \
  3206 "$ws_r007_review" || exit 1
grep -Fqx -- '- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R008`' \
  "$ws_r007_review" || exit 1

ws_r007_block="$(
  awk '
    /^## 3[.] / { in_section=1 }
    in_section && /^```bash$/ { in_code=1; next }
    in_code && /^```$/ { exit }
    in_code { print }
  ' "$ws_r007"
)"
[[ -n "$ws_r007_block" ]] || exit 1
bash -n <<<"$ws_r007_block" || exit 1
if ! ws_r007_output="$(bash <<<"$ws_r007_block")"; then
  exit 1
fi
[[ -z "$ws_r007_output" ]] || exit 1
)
```

현재 고정 물리 상태의 정상 기대 결과는 rc=0과 stdout 0 bytes다. pin,
receipt verdict, source preflight 또는 frozen block의 검사 하나라도 실패하면
nonzero로 종료해야 한다.

## 4. 다음 결정과 효력 경계

§3 PASS 뒤에도 현재 공식 상태는 v2.4 / sequence 39 / canonical r021,
artifact `126/257`, open `131`, formal `0/279`, actual event `0`, gate `0/5`,
release `NOT_ELIGIBLE`다. source content-set drift 때문에 현행 v2.4
continuation/Goal quick check도 계속 FAIL이 정상이다.

다음 사용자 결정은 R007 §4의 두 source 전략 중 하나다.

```text
CHECKPOINT_PROJECTED_SOURCE_RESTORE
REVIEWED_ADD_ONLY_EXACT_SNAPSHOT_ACCEPTANCE
```

선택 전에는 source/checkpoint/checker를 변경하지 않는다. 선택 뒤의 검증과
P → M → FP-008 권한 분리는 R005 §2와 R004 §8을 따른다.

이 R008의 최종 SHA-256/bytes와 findings는 add-only
`CONTINUATION-EXECUTION-HANDOFF-20260730-R008-independent-review-r001.md`가
결속한다. receipt가 없거나 target pin이 다르거나 findings가 0이 아니면
R008을 실행 재개 근거로 사용하지 않는다.
