# WalkSafe 새 터미널 실행 준비 인계 R005

- 문서 ID: `WS-CONTINUATION-EXECUTION-HANDOFF-20260730-R005`
- 상태: `SOURCE_DRIFT_BLOCKED_RESTART_HANDOFF_SUCCESSOR`
- 작성일: `2026-07-30`
- predecessor:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R004.md`
- predecessor SHA-256:
  `ff2d91def55c4959d19b6a0e1e61694bbec3315e9b2ba6d06ca1a9728a669eb5`
- predecessor bytes: `24,117`
- predecessor FAIL review:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R004-independent-review-r001.md`
- predecessor review SHA-256:
  `00f5ffb37a2947ce5123c3ab8b5dbf8be729af10185535cce61993a89a3ccac5`
- predecessor review bytes: `4,302`
- 저장소:
  `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`
- branch: `codex/walksafe-rc2-hardening-20260715`
- HEAD: `a3ad7eead6b5d834d3e0675422475a9aad351e3d`

## 1. successor 범위와 현재 상태

이 R005는 R004를 수정하지 않고 그 독립검수의 MAJOR 3건만 교정하는 add-only
successor다. R004 §1~5, §8~9의 사실·수치·승인 경계를 아래 override와 충돌하지
않는 범위에서 상속한다. 새 터미널은 이 R005와 receipt만 읽고 R004를 생략하지
않는다.

현재 상태는 계속 다음과 같다.

```text
R007_DESIGN_REVIEWED_FINDINGS_ZERO_SOURCE_DRIFT_BLOCKED_WAITING_FOR_EXPLICIT_SOURCE_STRATEGY_CHOICE
```

active control은 v2.4 / sequence 39 / canonical r021이고 artifact `126/257`,
open `131`, formal `0/279`, actual-device/event `0`, gate `0/5`, release
`NOT_ELIGIBLE`다. checkpoint, canonical, Goal/event, product, formal·실기기,
artifact·gate·release delta는 0이다.

R004의 일반 R002~R007 succession, exact68, FP-008, current pins와 금지선은
유효하다. R004 자체는 `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R005`이므로 최종 인계로
사용하지 않는다.

## 2. source 전략별 validator convergence

현재 단일 drift는 R004 §4의 runner 하나다.

```text
path=scripts/run_walksafe_test_layers_20260711.sh
checkpoint_expected=4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d/15588
live=4280dabbaa60ec098c8c52dc278f95b178ac5b2a6aa7dc2a1feae419f81f5b0f/15889
target_intended_owner=1000:1000
target_intended_mode=0775
target_intended_nlink=1
```

### 2.1 `CHECKPOINT_PROJECTED_SOURCE_RESTORE`

현재 5줄 delta를 exact add-only recovery artifact에 먼저 보존한다. target이
여전히 live SHA/bytes, regular, owner `1000:1000`, mode `0775`, nlink 1일 때만
expected bytes로 CAS 복원한다.

CAS는 새 inode와 ctime을 만들 수 있다. 보존 대상 metadata는 owner/mode/nlink와
regular-file/no-symlink 경계이며 inode/ctime 동일성은 요구하지 않는다. 적용 뒤
target SHA/bytes, metadata, snapshot
`603/e445b7.../69464310...`, current v2.4 continuation과 Goal graph quick 두 개
PASS를 확인한다.

### 2.2 `REVIEWED_ADD_ONLY_EXACT_SNAPSHOT_ACCEPTANCE`

live `4280...`과 5개 untracked test를 유지한다. 현재 active checkpoint/checker를
바꾸지 않는 동안 current v2.4 quick 두 개는 이 R005 §4의 exact output으로
계속 expected FAIL이어야 한다.

새 source-snapshot manifest는 runner와 5개 test의 exact bytes/type/owner/mode/
nlink, Git index/status와 tracked/untracked/ignored inventory, old/new aggregate,
single-delta 역대입 증명을 결속한다. 독립검수 findings 0과 새 exact 사용자 승인
뒤 별도 source-acceptance validator가 PASS해야 한다.

active quick 상태를 바꾸려면 별도 승인된 successor source-acceptance
event/checkpoint/checker transition이 필요하다. 그 transition의 add-only 설계,
독립검수와 새 사용자 승인이 끝나기 전에는 현재 checkpoint hash를 live aggregate로
직접 바꾸지 않는다. R007의 clean-seq39 및 P index-10 CAS 전제도 successor
제어설계에서 다시 검수한다.

두 전략은 중간 validator 결과가 다르다. restore만 현행 quick PASS로 수렴한다.
add-only acceptance는 새 validator PASS와 현행 quick exact FAIL을 동시에 보존한
뒤 successor transition에서만 active quick 상태를 바꾼다.

## 3. 새 터미널 읽기 순서 override

이 R005를 전달 시 사전 단계로 한 번 읽은 뒤 다음 순서를 따른다.

1. `CONTINUATION-EXECUTION-HANDOFF-20260730-R004.md`
2. `CONTINUATION-EXECUTION-HANDOFF-20260730-R004-independent-review-r001.md`
3. R004 §6의 item 1~15를 그 상대순서대로 읽는다.
4. 이 R005를 다시 읽지 않고 아래 §4 fail-fast를 실행한다.
5. `CONTINUATION-EXECUTION-HANDOFF-20260730-R005-independent-review-r001.md`

R004 §6 item 16의 R004 receipt placeholder는 이 R005의 item 2와 5로 대체한다.
R004와 두 review의 공통 prefix는
`docs/control/execution/artifact-closure/run-20260727-001/`이다.

## 4. corrected fail-fast 재개 확인

아래 블록은 pinned R004 §7을 먼저 실행한 뒤 exact output, no-symlink regular
source와 전체 absence를 더 엄격하게 확인한다. source를 수정하지 않는다.

```bash
(
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
set -euo pipefail

ws_r004='docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-EXECUTION-HANDOFF-20260730-R004.md'
ws_r004_review='docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-EXECUTION-HANDOFF-20260730-R004-independent-review-r001.md'

ws_check_file() {
  local ws_sha="$1" ws_bytes="$2" ws_path="$3"
  test -f "$ws_path"
  test ! -L "$ws_path"
  test "$(stat -c '%F' "$ws_path")" = 'regular file'
  test "$(sha256sum "$ws_path" | cut -d' ' -f1)" = "$ws_sha"
  test "$(wc -c < "$ws_path")" -eq "$ws_bytes"
}

ws_check_file \
  ff2d91def55c4959d19b6a0e1e61694bbec3315e9b2ba6d06ca1a9728a669eb5 \
  24117 "$ws_r004"
ws_check_file \
  00f5ffb37a2947ce5123c3ab8b5dbf8be729af10185535cce61993a89a3ccac5 \
  4302 "$ws_r004_review"
grep -Fqx -- '- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R005`' \
  "$ws_r004_review"

ws_r004_block="$(
  awk '
    /^## 7[.] / { in_section=1 }
    in_section && /^```bash$/ { in_code=1; next }
    in_code && /^```$/ { exit }
    in_code { print }
  ' "$ws_r004"
)"
test -n "$ws_r004_block"
bash -n <<<"$ws_r004_block"
bash <<<"$ws_r004_block"

set +e
ws_continuation="$(
  python3 -B scripts/check_walksafe_project_continuation_v2_4.py \
    --root . \
    --checkpoint docs/control/walksafe-project-continuation-checkpoint.json 2>&1
)"
ws_continuation_rc=$?
ws_goal="$(
  python3 -B scripts/check_walksafe_goal_graph_v2_4.py \
    --root . \
    --checkpoint docs/control/walksafe-project-continuation-checkpoint.json 2>&1
)"
ws_goal_rc=$?
set -e

test "$ws_continuation_rc" -eq 1
test "$ws_goal_rc" -eq 1

ws_expected_continuation=$'WalkSafe v2.4 continuation check: FAIL\n- v2.4 seq39 checkpoint projection differs\n- v2.4 working snapshot content-set SHA-256 differs'
ws_expected_goal=$'WalkSafe v2.4 Goal graph check: FAIL\n- continuation: v2.4 seq39 checkpoint projection differs\n- continuation: v2.4 working snapshot content-set SHA-256 differs\n- v2.4 seq39 queue projection: v2.4 seq39 checkpoint projection differs'

test "$ws_continuation" = "$ws_expected_continuation"
test "$ws_goal" = "$ws_expected_goal"

ws_check_source() {
  local ws_sha="$1" ws_bytes="$2" ws_mode="$3" ws_path="$4"
  ws_check_file "$ws_sha" "$ws_bytes" "$ws_path"
  test "$(stat -c '%u:%g' "$ws_path")" = '1000:1000'
  test "$(stat -c '%a' "$ws_path")" = "$ws_mode"
  test "$(stat -c '%h' "$ws_path")" -eq 1
}

ws_check_source \
  4280dabbaa60ec098c8c52dc278f95b178ac5b2a6aa7dc2a1feae419f81f5b0f \
  15889 775 scripts/run_walksafe_test_layers_20260711.sh
ws_check_source \
  4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d \
  15588 664 \
  docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-001/gitleaks-current-tree-input/scripts/run_walksafe_test_layers_20260711.sh
ws_check_source \
  4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d \
  15588 664 \
  docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-002/gitleaks-current-tree-input/scripts/run_walksafe_test_layers_20260711.sh

for ws_absent in \
  docs/control/audits/walksafe-implementation-gap-analysis-20260726-r022.json \
  docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r022.json \
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-4-selector-preflight-r007 \
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r007 \
  docs/control/goals/walksafe-completion-graph-v2-5 \
  docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-4-SELECTOR-PREFLIGHT-20260730-001 \
  docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-001 \
  scripts/check_walksafe_active_control_discovery_20260730.py \
  scripts/launch_walksafe_v2_5_r022_authorized_20260730.py \
  docs/control/walksafe-active-control-discovery.json \
  tests/test_walksafe_active_control_discovery_20260730.py \
  scripts/walksafe_v2_5_validation.py \
  scripts/check_walksafe_project_continuation_v2_5.py \
  scripts/check_walksafe_goal_graph_v2_5.py \
  scripts/apply_walksafe_v2_5_r022_authorized.py \
  tests/test_walksafe_v2_5_active_control_20260730.py
do
  test ! -e "$ws_absent"
  test ! -L "$ws_absent"
done
)
```

기대 결과는 rc=0과 stdout 0 bytes다. 한 조건이라도 다르면 source 선택이나 실행을
시작하지 않고 먼저 drift를 보고한다.

## 5. 다음 단일 결정과 이후 DAG

새 터미널이 사용자에게 먼저 요청할 결정은 다음 중 정확히 하나다.

```text
CHECKPOINT_PROJECTED_SOURCE_RESTORE
REVIEWED_ADD_ONLY_EXACT_SNAPSHOT_ACCEPTANCE
```

선택 전에는 source/checkpoint/checker를 변경하지 않는다. 선택 뒤 각 전략의 §2
validator 경계를 충족한 다음에만 R004 §8의 P → M → future FP-008 DAG로 간다.
P와 M은 별도 build 지시, candidate review findings 0, verified external
A0/R0/L/B와 서로 다른 candidate-specific fresh 사용자 승인을 요구한다.

R004/R005와 findings-zero R007은 source 전략, P/M 승인, FP-008/Goal/product
권한이 아니다. 과거 승인이나 일반적인 “계속 진행”을 재사용하지 않는다.

이 R005의 최종 SHA-256/bytes와 findings는 add-only
`CONTINUATION-EXECUTION-HANDOFF-20260730-R005-independent-review-r001.md`가
결속한다. receipt가 없거나 target pin이 다르거나 findings가 0이 아니면 이
인계를 사용해 source 조정이나 실행을 시작하지 않는다.
