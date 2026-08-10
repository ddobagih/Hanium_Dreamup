# WalkSafe 새 터미널 실행 준비 인계 R006

- 문서 ID: `WS-CONTINUATION-EXECUTION-HANDOFF-20260730-R006`
- 상태: `SOURCE_DRIFT_BLOCKED_RESTART_HANDOFF_SUCCESSOR`
- 작성일: `2026-07-30`
- predecessor:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R005.md`
- predecessor SHA-256:
  `07a155ced76b648a3435969097ca937133fadd8f2f41703c0d8090a541a7f908`
- predecessor bytes: `10,460`
- predecessor FAIL review:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R005-independent-review-r001.md`
- predecessor review SHA-256:
  `65a0772e00d7865d12c9c8aee532e6be96935c0922eeee0b784a98afadae88fc`
- predecessor review bytes: `2,890`
- 저장소:
  `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`
- branch: `codex/walksafe-rc2-hardening-20260715`
- HEAD: `a3ad7eead6b5d834d3e0675422475a9aad351e3d`

## 1. successor 범위와 현재 상태

이 R006은 R005를 수정하지 않고 그 독립검수 MAJOR 1건만 교정하는 add-only
successor다. R005의 source 전략 구분, exact output/absence 검사, R004에서 상속한
facts/pins/approval DAG와 금지선은 그대로 유지한다.

현재 상태는 계속 다음과 같다.

```text
R007_DESIGN_REVIEWED_FINDINGS_ZERO_SOURCE_DRIFT_BLOCKED_WAITING_FOR_EXPLICIT_SOURCE_STRATEGY_CHOICE
```

active control은 v2.4 / sequence 39 / canonical r021이고 artifact `126/257`,
open `131`, formal `0/279`, actual-device/event `0`, gate `0/5`, release
`NOT_ELIGIBLE`다. 프로젝트 상태 delta는 0이다.

## 2. 새 터미널 읽기 순서

이 R006을 전달 시 사전 단계로 한 번 읽은 뒤 다음을 순서대로 읽는다.

1. `CONTINUATION-EXECUTION-HANDOFF-20260730-R004.md`
2. `CONTINUATION-EXECUTION-HANDOFF-20260730-R004-independent-review-r001.md`
3. `CONTINUATION-EXECUTION-HANDOFF-20260730-R005.md`
4. `CONTINUATION-EXECUTION-HANDOFF-20260730-R005-independent-review-r001.md`
5. R005 §3이 가져오는 R004 §6 item 1~15
6. 이 R006을 다시 읽지 않고 아래 §3 fail-fast 실행
7. `CONTINUATION-EXECUTION-HANDOFF-20260730-R006-independent-review-r001.md`

공통 prefix는
`docs/control/execution/artifact-closure/run-20260727-001/`이다.

## 3. no-follow-first corrected fail-fast

아래 블록은 runner와 두 evidence copy를 한 바이트도 읽기 전에 lstat 성격의
no-symlink/type 검사를 수행한다. type·metadata가 맞을 때만 hash/bytes를 읽고,
세 preflight가 모두 끝난 뒤에만 pinned R005 §4를 실행한다. 실행 뒤 동일
dev:ino와 exact bindings를 다시 확인한다.

```bash
(
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
set -euo pipefail

ws_r005='docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-EXECUTION-HANDOFF-20260730-R005.md'
ws_r005_review='docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-EXECUTION-HANDOFF-20260730-R005-independent-review-r001.md'

ws_check_regular() {
  local ws_sha="$1" ws_bytes="$2" ws_mode="$3" ws_path="$4"
  test ! -L "$ws_path"
  test -f "$ws_path"
  test "$(stat -c '%F' "$ws_path")" = 'regular file'
  test "$(stat -c '%u:%g' "$ws_path")" = '1000:1000'
  test "$(stat -c '%a' "$ws_path")" = "$ws_mode"
  test "$(stat -c '%h' "$ws_path")" -eq 1
  test "$(sha256sum "$ws_path" | cut -d' ' -f1)" = "$ws_sha"
  test "$(wc -c < "$ws_path")" -eq "$ws_bytes"
  stat -c '%d:%i' "$ws_path"
}

ws_runner='scripts/run_walksafe_test_layers_20260711.sh'
ws_copy1='docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-001/gitleaks-current-tree-input/scripts/run_walksafe_test_layers_20260711.sh'
ws_copy2='docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-002/gitleaks-current-tree-input/scripts/run_walksafe_test_layers_20260711.sh'

ws_runner_identity="$(
  ws_check_regular \
    4280dabbaa60ec098c8c52dc278f95b178ac5b2a6aa7dc2a1feae419f81f5b0f \
    15889 775 "$ws_runner"
)"
ws_copy1_identity="$(
  ws_check_regular \
    4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d \
    15588 664 "$ws_copy1"
)"
ws_copy2_identity="$(
  ws_check_regular \
    4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d \
    15588 664 "$ws_copy2"
)"

test ! -L "$ws_r005"
test ! -L "$ws_r005_review"
test -f "$ws_r005"
test -f "$ws_r005_review"
test "$(sha256sum "$ws_r005" | cut -d' ' -f1)" = \
  '07a155ced76b648a3435969097ca937133fadd8f2f41703c0d8090a541a7f908'
test "$(wc -c < "$ws_r005")" -eq 10460
test "$(sha256sum "$ws_r005_review" | cut -d' ' -f1)" = \
  '65a0772e00d7865d12c9c8aee532e6be96935c0922eeee0b784a98afadae88fc'
test "$(wc -c < "$ws_r005_review")" -eq 2890
grep -Fqx -- '- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R006`' \
  "$ws_r005_review"

ws_r005_block="$(
  awk '
    /^## 4[.] / { in_section=1 }
    in_section && /^```bash$/ { in_code=1; next }
    in_code && /^```$/ { exit }
    in_code { print }
  ' "$ws_r005"
)"
test -n "$ws_r005_block"
bash -n <<<"$ws_r005_block"
ws_r005_output="$(bash <<<"$ws_r005_block")"
test -z "$ws_r005_output"

test "$ws_runner_identity" = "$(
  ws_check_regular \
    4280dabbaa60ec098c8c52dc278f95b178ac5b2a6aa7dc2a1feae419f81f5b0f \
    15889 775 "$ws_runner"
)"
test "$ws_copy1_identity" = "$(
  ws_check_regular \
    4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d \
    15588 664 "$ws_copy1"
)"
test "$ws_copy2_identity" = "$(
  ws_check_regular \
    4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d \
    15588 664 "$ws_copy2"
)"
)
```

기대 결과는 rc=0과 stdout 0 bytes다. source preflight가 실패하면 R005/R004
predecessor block을 실행하지 않는다.

## 4. 다음 단일 결정과 권한 경계

새 터미널이 사용자에게 먼저 요청할 결정은 정확히 다음 둘 중 하나다.

```text
CHECKPOINT_PROJECTED_SOURCE_RESTORE
REVIEWED_ADD_ONLY_EXACT_SNAPSHOT_ACCEPTANCE
```

각 전략의 validator convergence와 inode/ctime 경계는 R005 §2를 따른다. 선택
전에는 source/checkpoint/checker를 변경하지 않는다. 선택 뒤에도 R004 §8의
P → M → future FP-008 DAG, 별도 build 지시와 서로 다른 candidate-specific
fresh 사용자 승인을 지킨다.

R004~R006과 findings-zero R007은 source 전략, P/M 승인, FP-008/Goal/product
권한이 아니다. 과거 승인이나 일반적인 “계속 진행”을 재사용하지 않는다.

이 R006의 최종 SHA-256/bytes와 findings는 add-only
`CONTINUATION-EXECUTION-HANDOFF-20260730-R006-independent-review-r001.md`가
결속한다. receipt가 없거나 target pin이 다르거나 findings가 0이 아니면 이
인계를 사용해 source 조정이나 실행을 시작하지 않는다.
