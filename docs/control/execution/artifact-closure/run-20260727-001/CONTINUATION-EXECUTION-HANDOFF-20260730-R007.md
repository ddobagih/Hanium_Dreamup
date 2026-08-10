# WalkSafe 새 터미널 실행 준비 인계 R007

- 문서 ID: `WS-CONTINUATION-EXECUTION-HANDOFF-20260730-R007`
- 상태: `SOURCE_DRIFT_BLOCKED_RESTART_HANDOFF_SUCCESSOR`
- 작성일: `2026-07-30`
- predecessor:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R006.md`
- predecessor SHA-256:
  `e9eb0c47b03902a9c6b697cfdd6fbe1f387c591ea1c59b46f80bc2fcb14aecba`
- predecessor bytes: `6,615`
- predecessor FAIL review:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R006-independent-review-r001.md`
- predecessor review SHA-256:
  `7230025ae5261f24861ebd5b5f1928718728bffafb4a9d13d4c04286519eee91`
- predecessor review bytes: `2,959`
- 저장소:
  `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`
- branch: `codex/walksafe-rc2-hardening-20260715`
- HEAD: `a3ad7eead6b5d834d3e0675422475a9aad351e3d`

## 1. successor 범위와 현재 상태

이 R007은 R006을 수정하지 않고 그 독립검수 MAJOR 1건만 교정하는 add-only
successor다. R006의 no-follow-first 순서와 R005의 source 전략 구분, exact
output/absence 검사, R004에서 상속한 facts/pins/approval DAG와 금지선은
그대로 유지한다.

이 파일의 `R007`은 실행 준비 인계 revision이다. 별도 제어 설계
`R022-CONTROL-MIGRATION-CANDIDATE-R007.md`와 이름 공간·역할이 다르다.

현재 상태는 계속 다음과 같다.

```text
R007_DESIGN_REVIEWED_FINDINGS_ZERO_SOURCE_DRIFT_BLOCKED_WAITING_FOR_EXPLICIT_SOURCE_STRATEGY_CHOICE
```

active control은 v2.4 / sequence 39 / canonical r021이고 artifact `126/257`,
open `131`, formal `0/279`, actual-device/event `0`, gate `0/5`, release
`NOT_ELIGIBLE`다. 프로젝트 상태 delta는 0이다.

## 2. 새 터미널 읽기 순서

공통 prefix는
`docs/control/execution/artifact-closure/run-20260727-001/`이다.

1. `CONTINUATION-EXECUTION-HANDOFF-20260730-R004.md`
2. `CONTINUATION-EXECUTION-HANDOFF-20260730-R004-independent-review-r001.md`
3. `CONTINUATION-EXECUTION-HANDOFF-20260730-R005.md`
4. `CONTINUATION-EXECUTION-HANDOFF-20260730-R005-independent-review-r001.md`
5. `CONTINUATION-EXECUTION-HANDOFF-20260730-R006.md`
6. `CONTINUATION-EXECUTION-HANDOFF-20260730-R006-independent-review-r001.md`
7. 이 R007 §3 fail-fast 실행
8. `CONTINUATION-EXECUTION-HANDOFF-20260730-R007-independent-review-r001.md`

R004~R006의 실행 블록은 별도로 실행하지 않는다. §3이 필요한 predecessor
block을 exact pin으로 가져온다.

## 3. same-shell fail-closed corrected block

아래 블록은 runner와 두 evidence copy의 각 predicate를 command substitution
밖 같은 shell에서 직접 실행한다. 함수의 모든 실패 경로에 `|| return 1`을
명시하고 identity는 `printf -v` output variable로 설정한다. 세 preflight가
모두 끝난 뒤에만 pinned R005 §4를 실행하며, 실행 뒤 같은 방식으로 exact
bindings와 dev:ino를 다시 확인한다.

```bash
(
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
set -euo pipefail

ws_r006='docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-EXECUTION-HANDOFF-20260730-R006.md'
ws_r006_review='docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-EXECUTION-HANDOFF-20260730-R006-independent-review-r001.md'
ws_r005='docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-EXECUTION-HANDOFF-20260730-R005.md'

ws_check_regular() {
  local ws_out_var="$1" ws_sha="$2" ws_bytes="$3" ws_mode="$4" ws_path="$5"
  local ws_seen_value ws_seen_identity

  [[ "$ws_out_var" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] || return 1
  [[ ! -L "$ws_path" ]] || return 1
  [[ -f "$ws_path" ]] || return 1
  ws_seen_value="$(stat -c '%F' -- "$ws_path")" || return 1
  [[ "$ws_seen_value" == 'regular file' ]] || return 1
  ws_seen_value="$(stat -c '%u:%g' -- "$ws_path")" || return 1
  [[ "$ws_seen_value" == '1000:1000' ]] || return 1
  ws_seen_value="$(stat -c '%a' -- "$ws_path")" || return 1
  [[ "$ws_seen_value" == "$ws_mode" ]] || return 1
  ws_seen_value="$(stat -c '%h' -- "$ws_path")" || return 1
  [[ "$ws_seen_value" == 1 ]] || return 1
  ws_seen_value="$(sha256sum -- "$ws_path")" || return 1
  [[ "${ws_seen_value%% *}" == "$ws_sha" ]] || return 1
  ws_seen_value="$(wc -c < "$ws_path")" || return 1
  [[ "$ws_seen_value" -eq "$ws_bytes" ]] || return 1
  ws_seen_identity="$(stat -c '%d:%i' -- "$ws_path")" || return 1
  printf -v "$ws_out_var" '%s' "$ws_seen_identity" || return 1
}

ws_runner='scripts/run_walksafe_test_layers_20260711.sh'
ws_copy1='docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-001/gitleaks-current-tree-input/scripts/run_walksafe_test_layers_20260711.sh'
ws_copy2='docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-002/gitleaks-current-tree-input/scripts/run_walksafe_test_layers_20260711.sh'

ws_check_regular ws_runner_identity \
  4280dabbaa60ec098c8c52dc278f95b178ac5b2a6aa7dc2a1feae419f81f5b0f \
  15889 775 "$ws_runner" || exit 1
ws_check_regular ws_copy1_identity \
  4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d \
  15588 664 "$ws_copy1" || exit 1
ws_check_regular ws_copy2_identity \
  4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d \
  15588 664 "$ws_copy2" || exit 1

test ! -L "$ws_r006" || exit 1
test ! -L "$ws_r006_review" || exit 1
test -f "$ws_r006" || exit 1
test -f "$ws_r006_review" || exit 1
test "$(sha256sum "$ws_r006" | cut -d' ' -f1)" = \
  'e9eb0c47b03902a9c6b697cfdd6fbe1f387c591ea1c59b46f80bc2fcb14aecba' \
  || exit 1
test "$(wc -c < "$ws_r006")" -eq 6615 || exit 1
test "$(sha256sum "$ws_r006_review" | cut -d' ' -f1)" = \
  '7230025ae5261f24861ebd5b5f1928718728bffafb4a9d13d4c04286519eee91' \
  || exit 1
test "$(wc -c < "$ws_r006_review")" -eq 2959 || exit 1
grep -Fqx -- '- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R007`' \
  "$ws_r006_review" || exit 1

test ! -L "$ws_r005" || exit 1
test -f "$ws_r005" || exit 1
test "$(sha256sum "$ws_r005" | cut -d' ' -f1)" = \
  '07a155ced76b648a3435969097ca937133fadd8f2f41703c0d8090a541a7f908' \
  || exit 1
test "$(wc -c < "$ws_r005")" -eq 10460 || exit 1

ws_r005_block="$(
  awk '
    /^## 4[.] / { in_section=1 }
    in_section && /^```bash$/ { in_code=1; next }
    in_code && /^```$/ { exit }
    in_code { print }
  ' "$ws_r005"
)"
test -n "$ws_r005_block" || exit 1
bash -n <<<"$ws_r005_block" || exit 1
ws_r005_output="$(bash <<<"$ws_r005_block")" || exit 1
test -z "$ws_r005_output" || exit 1

ws_check_regular ws_runner_identity_after \
  4280dabbaa60ec098c8c52dc278f95b178ac5b2a6aa7dc2a1feae419f81f5b0f \
  15889 775 "$ws_runner" || exit 1
ws_check_regular ws_copy1_identity_after \
  4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d \
  15588 664 "$ws_copy1" || exit 1
ws_check_regular ws_copy2_identity_after \
  4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d \
  15588 664 "$ws_copy2" || exit 1

test "$ws_runner_identity" = "$ws_runner_identity_after" || exit 1
test "$ws_copy1_identity" = "$ws_copy1_identity_after" || exit 1
test "$ws_copy2_identity" = "$ws_copy2_identity_after" || exit 1
)
```

정상 기대 결과는 rc=0과 stdout 0 bytes다. source preflight predicate 하나라도
실패하면 R005/R004 predecessor block을 실행하지 않고 nonzero로 종료해야 한다.

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

R004~R007 handoff와 findings-zero 제어 설계 R007은 source 전략, P/M 승인,
FP-008/Goal/product 권한이 아니다. 과거 승인이나 일반적인 “계속 진행”을
재사용하지 않는다.

이 R007의 최종 SHA-256/bytes와 findings는 add-only
`CONTINUATION-EXECUTION-HANDOFF-20260730-R007-independent-review-r001.md`가
결속한다. receipt가 없거나 target pin이 다르거나 findings가 0이 아니면 이
인계를 사용해 source 조정이나 실행을 시작하지 않는다.
