# WalkSafe 새 터미널 재개 인계 R003

- 문서 ID: `WS-CONTINUATION-PLAN-HANDOFF-20260729-R003`
- 상태: `PLAN_ONLY_TERMINAL_RESTART_HANDOFF`
- 작성일: `2026-07-29`
- predecessor:
  `CONTINUATION-PLAN-HANDOFF-20260729-R002.md`
- 저장소:
  `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`
- branch: `codex/walksafe-rc2-hardening-20260715`
- HEAD: `a3ad7eead6b5d834d3e0675422475a9aad351e3d`

## 1. 이 문서의 목적

새 터미널에서 Codex를 다시 호출할 때 이 문서를 부트스트랩 포인터로 전달한다.
새 Codex가 전달받은 이 문서를 최초 한 번 읽는 행위는 §4 정식 읽기 순서의
사전 단계다. 위치와 권한 경계를 확인한 뒤 §4에서 `AGENTS.md`부터 정식
읽기를 시작하며, 그 순서 안에서 이 R003을 다시 읽지 않는다.
이 인계서는 완료된 plan-only 보완 작업을 다시 수행하지 않기 위한 add-only
문서다.

이 문서는 제품 실행, Goal 시작, checkpoint 전환, canonical Gap·Backlog 적용,
formal·실기기·배포·실제 event, 승인 또는 release 권한이 아니다.

## 2. 한 줄 현재 상태

`계획 보완과 독립검수는 findings 0으로 끝났고, 계획은 검수 완료됐지만
활성화되지 않았다. 별도 실행 지시 전까지 대기한다.`

아래 값은 프로젝트 전체 역사값이 아니라 이번 plan-only 보완 package의
추가분만 뜻한다. 과거 sequence 1~39와 그 안의 Goal start/complete event는
그대로 존재한다.

```text
PLAN_WORK_REVIEWED=true
PLAN_WORK_REBASELINED=false
PLAN_WORK_ACTIVATED=false
PLAN_WORK_EXECUTION_STARTED=false
PLAN_WORK_GOAL_EVENT_DELTA=0
PLAN_WORK_CHECKPOINT_SWITCH_DELTA=0
PLAN_WORK_CANONICAL_GAP_BACKLOG_SWITCH_DELTA=0
PLAN_WORK_PRODUCT_CODE_CHANGE_DELTA=0
PLAN_WORK_FORMAL_RUN_DELTA=0
PLAN_WORK_ACTUAL_DEVICE_OR_EVENT_RUN_DELTA=0
PLAN_WORK_ARTIFACT_CREDIT_DELTA=0
PLAN_WORK_RELEASE_CREDIT_DELTA=0
```

## 3. 현재 정본과 물리 결속

| 역할 | 경로 | SHA-256 | bytes |
|---|---|---|---:|
| active checkpoint | `docs/control/walksafe-project-continuation-checkpoint.json` | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| plan main | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001.md` | `ad4c6fcfeba68b586e066490656970b64b1f04d4978661c9901040fd3d3621e2` | 18,892 |
| plan manifest | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/plan-manifest.json` | `43ec2fb64590aac4421e3bafa9178b9e7316a103f944c0f06df30be8dc5493f4` | 14,669 |
| detached output binding | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/plan-output-bindings.json` | `9436f40c1629d29b348b51a411c173e8b304409ec469e56d79f984986d6c36d3` | 3,757 |
| plan independent review | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/independent-plan-review-r001.md` | `393ac22c6cd3f84a4f0b804901d7c943c14aa865f1025e2ad3db2755b4466745` | 3,667 |
| artifact plan | `docs/control/execution/artifact-audits/20260727/final-257/remaining-work-execution-plan-r004-plan-only-successor.md` | `8e76ac5b22d8390ceb9d3fbcc2121a3b53f693a2f887588703392e60746da44b` | 10,784 |
| predecessor handoff | `docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-PLAN-HANDOFF-20260729-R002.md` | `0382bfee7c0fd0894416ec72eb03f872fc34155a72624a534a3fa13fc44996a3` | 4,374 |

이 R003의 최종 SHA-256과 bytes는 본문 밖의 add-only receipt인
`docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-PLAN-HANDOFF-20260729-R003-independent-review-r001.md`가
결속한다. 새 Codex는 §4에서 receipt와 실제 R003을 대조한다. receipt가 없거나
값이 다르거나 findings가 0이 아니면 이 R003을 정본 인계로 취급하지 않고
실행을 시작하지 않는다.

계획 package 검수 완료 상태:

- plan output: 9개 = detached member 8개 + self-exclusion 1개
- 독립 의미·물리·종합 검수:
  `BLOCKING/MAJOR/MINOR = 0/0/0`
- Gap 후보 19, evidence reference 42, unique evidence binding 31
- hard dependency 24/24 exact
- R011 exact artifact: `126/257` closed-equivalent, open `131`
- formal `0/279`, actual-device execution 0, real-external-event credit 0,
  gate `0/5`
- release: `NOT_ELIGIBLE`

## 4. 새 터미널에서 먼저 할 일

아래는 읽기 전용 확인이다. 제품 build/test, apply, event append를 실행하지
않는다.

이 R003은 전달 시 사전 단계에서 이미 한 번 읽은 것으로 간주한다. 그다음
새 Codex는 파일 도구를 사용해 다음 repository read를 순서대로 수행한다.

1. `AGENTS.md`
2. `docs/control/walksafe-project-resumption-runbook.md`
3. `docs/control/goals/walksafe-completion-graph-v2-3/README.md`
4. `docs/control/goals/README.md`
5. `docs/control/goals/walksafe-completion-graph-v2-4/README.md`
6. `docs/control/goals/walksafe-completion-graph-v2-2/00-master-goal.md`
7. `docs/control/walksafe-project-continuation-checkpoint.json`
8. `docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json`
9. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001.md`
10. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/plan-manifest.json`
11. `docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-PLAN-HANDOFF-20260729-R003-independent-review-r001.md`

`AGENTS.md`의 필수 네 문서는 위 2, 3, 6, 7에서 상대순서를 유지한다. 그 사이
4~5를 읽는 것은 runbook §0 current override의
`goals/README → v2.4 README + imported Master → checkpoint + static manifest`
순서를 함께 만족하기 위해서다. v2.3 문서와 imported Master는 감사 이력·import
계약으로 읽되 현행 실행 포인터로 사용하지 않는다. v2.4 quick check도 무결성
확인일 뿐 제품 실행 권한이나 시험 credit가 아니다.

```bash
(
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
set -euo pipefail

test "$(git branch --show-current)" = 'codex/walksafe-rc2-hardening-20260715'
test "$(git rev-parse HEAD)" = 'a3ad7eead6b5d834d3e0675422475a9aad351e3d'

ws_check_file() {
  local ws_path="$1" ws_sha="$2" ws_bytes="$3"
  test -f "$ws_path"
  test "$(sha256sum "$ws_path" | cut -d' ' -f1)" = "$ws_sha"
  test "$(wc -c < "$ws_path")" -eq "$ws_bytes"
}

ws_check_file docs/control/walksafe-project-continuation-checkpoint.json \
  6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c 1329415
ws_check_file plans/features/2026-07-29_walksafe_plan_rebaseline_r001.md \
  ad4c6fcfeba68b586e066490656970b64b1f04d4978661c9901040fd3d3621e2 18892
ws_check_file plans/features/2026-07-29_walksafe_plan_rebaseline_r001/plan-manifest.json \
  43ec2fb64590aac4421e3bafa9178b9e7316a103f944c0f06df30be8dc5493f4 14669
ws_check_file plans/features/2026-07-29_walksafe_plan_rebaseline_r001/plan-output-bindings.json \
  9436f40c1629d29b348b51a411c173e8b304409ec469e56d79f984986d6c36d3 3757
ws_check_file plans/features/2026-07-29_walksafe_plan_rebaseline_r001/independent-plan-review-r001.md \
  393ac22c6cd3f84a4f0b804901d7c943c14aa865f1025e2ad3db2755b4466745 3667
ws_check_file docs/control/execution/artifact-audits/20260727/final-257/remaining-work-execution-plan-r004-plan-only-successor.md \
  8e76ac5b22d8390ceb9d3fbcc2121a3b53f693a2f887588703392e60746da44b 10784
ws_check_file docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-PLAN-HANDOFF-20260729-R002.md \
  0382bfee7c0fd0894416ec72eb03f872fc34155a72624a534a3fa13fc44996a3 4374

ws_output_binding='plans/features/2026-07-29_walksafe_plan_rebaseline_r001/plan-output-bindings.json'
while IFS=$'\t' read -r ws_path ws_sha ws_bytes; do
  ws_check_file "$ws_path" "$ws_sha" "$ws_bytes"
done < <(jq -r '.members[] | [.path,.file_sha256,.bytes] | @tsv' "$ws_output_binding")

ws_plan_manifest='plans/features/2026-07-29_walksafe_plan_rebaseline_r001/plan-manifest.json'
while IFS=$'\t' read -r ws_path ws_sha ws_bytes; do
  ws_check_file "$ws_path" "$ws_sha" "$ws_bytes"
done < <(jq -r '
  (.source_bindings[] | [.path,.file_sha256,.bytes]),
  (.predecessor_plan_bindings[] | [.path,.file_sha256,.bytes])
  | @tsv
' "$ws_plan_manifest")

ws_gap_evidence='plans/features/2026-07-29_walksafe_plan_rebaseline_r001/gap-evidence-bindings.json'
test "$(jq '.binding_count' "$ws_gap_evidence")" -eq 31
test "$(jq '.bindings | length' "$ws_gap_evidence")" -eq 31
while IFS=$'\t' read -r ws_path ws_sha ws_bytes; do
  ws_check_file "$ws_path" "$ws_sha" "$ws_bytes"
done < <(jq -r '.bindings[] | [.path,.file_sha256,.bytes] | @tsv' "$ws_gap_evidence")

ws_handoff='docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-PLAN-HANDOFF-20260729-R003.md'
ws_handoff_review='docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-PLAN-HANDOFF-20260729-R003-independent-review-r001.md'
ws_handoff_sha="$(sha256sum "$ws_handoff" | cut -d' ' -f1)"
ws_handoff_bytes="$(wc -c < "$ws_handoff")"
test "$(sed -n 's/^- target SHA-256: `\([^`]*\)`$/\1/p' "$ws_handoff_review")" = "$ws_handoff_sha"
test "$(sed -n 's/^- target bytes: `\([^`]*\)`$/\1/p' "$ws_handoff_review")" -eq "$ws_handoff_bytes"
test "$(sed -n 's/^- verdict: `\([^`]*\)`$/\1/p' "$ws_handoff_review")" = 'PASS_FOR_PLAN_ONLY_TERMINAL_RESTART_HANDOFF'
test "$(sed -n 's/^- findings: `\([^`]*\)`$/\1/p' "$ws_handoff_review")" = 'BLOCKING=0 MAJOR=0 MINOR=0'

jq '{
  status,
  current_state,
  artifact_open_routes,
  static_plan_contract,
  planning_phases,
  execution_boundary,
  next_action
}' plans/features/2026-07-29_walksafe_plan_rebaseline_r001/plan-manifest.json

python3 -B scripts/check_walksafe_project_continuation_v2_4.py
python3 -B scripts/check_walksafe_goal_graph_v2_4.py

GIT_OPTIONAL_LOCKS=0 git status --short -- \
  docs/control/walksafe-project-continuation-checkpoint.json \
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001.md \
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001 \
  docs/control/execution/artifact-audits/20260727/final-257/remaining-work-execution-plan-r004-plan-only-successor.md \
  docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-PLAN-HANDOFF-20260729-R002.md \
  docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-PLAN-HANDOFF-20260729-R003.md \
  docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-PLAN-HANDOFF-20260729-R003-independent-review-r001.md \
  daylog/2026-07-29.md
)
```

기대값:

- manifest `status`: `PLAN_REVIEWED_NOT_ACTIVATED`
- P0-D: `CLASSIFICATION_RULE_DEFINED_PENDING_EXACT68`
- P0-E: `COMPLETE_FINDINGS_ZERO`
- `next_action`: `WAIT_FOR_EXPLICIT_SEPARATE_EXECUTION_INSTRUCTION`
- 이번 plan-only 작업의 execution/approval/event/artifact/release delta: 0
- R003 review:
  `PASS_FOR_PLAN_ONLY_TERMINAL_RESTART_HANDOFF`,
  `BLOCKING=0 MAJOR=0 MINOR=0`
- v2.4 quick check 두 개: PASS. 이 결과로 제품 실행을 시작하지 않음

branch, HEAD, SHA 또는 bytes가 다르면 실행을 시작하지 말고 먼저 drift 원인을
확인한다.

## 5. 반드시 구분할 활성 상태와 오래된 포인터

continuation checkpoint는 schema `1.25.0`, metadata version `1.22.0`,
`ACTIVE_WORKING_CHECKPOINT`다. 그 안의 Goal package/static plan v2.4.0은
`ACTIVE`, event tail은 sequence 39이며 ready frontier는
`WS-GOAL-EPIC-03`, `WS-GOAL-EPIC-12`다.

활성 canonical Gap·Backlog binding은 아직 r021이다. reviewed/not-activated
planning candidate는 r021 유래 다음-leaf 포인터를 stale로 판정했지만 이를
정본에 적용하지 않았다. stale cluster에는
`current_work.current_focus/source_policy_ids/gap_ids/next_action`,
`implementation_gap_snapshot`, 관련 `canonical_bindings`,
`session_handoff.current_epic/next_single_action`이 포함된다.

따라서 다음을 하지 않는다.

- FP-048 또는 다른 leaf를 바로 구현
- checkpoint의 오래된 `next_action`만 보고 Goal materialize/start
- R001의 self-digest 수정 지시를 다시 수행
- r021의 67/68 carry-forward를 새 실행 근거로 사용

검수된 planning candidate에서만 FP-048 선결정이 취소됐다. 활성 canonical
상태는 아직 r021이며, exact 68 재평가와 successor 검수·승인·적용 전에는
rebaseline 기준 다음 leaf가 결정되지 않았다.

이 문서의 `WAIT`는 사용자가 지정한 이번 plan-only 범위의 제한이다. 활성
checkpoint의 standing execution authority를 폐기하거나 변경하지 않지만,
새 터미널은 그 authority를 이 범위 제한을 우회하는 근거로 쓰지 않는다.
rebaseline 실행은 별도 사용자 지시 뒤에만 시작한다.

## 6. 완료돼서 다시 하지 않을 일

- artifact-register self-digest 수정
- R011 exact257 successor와 독립검수
- seq39 exact-5 canonical binding refresh
- 계획 source/predecessor/output SHA·bytes 결속
- Gap 후보 19건의 행 단위 근거 결속
- DAG 24개, v2.4 static lock/add-only 경계 정리
- artifact open131 per-ID dependency와 실패 재라우팅 설계
- 역사 replay `TD-HISTORICAL-REPLAY-RESOLVER` M0~M5 설계
- plan-only 독립검수 findings 0

## 7. 사용자의 다음 지시에 따른 분기

### A. 사용자가 계속 “계획만”을 요청

- 정본 drift가 없으면 추가 작업 없이 대기한다.
- 새 사실이 생겼을 때만 add-only successor로 계획을 보완한다.
- 기존 검수 완료 package를 제자리 수정하지 않는다.

### B. 사용자가 별도로 “실행”을 지시

이전 `승인합니다`를 새 실행 범위의 승인으로 재사용하지 않는다. 먼저 실행
범위와 권한을 다시 확인하고 다음 순서를 따른다.

1. exact 68 live reassessment 후보 작성
2. Gap r022·Backlog r022 후보와 changed/carry-forward exact set 생성
3. status/text/pointer-only면 v2.4 canonical update 후보,
   mapping/dependency/schema 변경이면 v2.5 successor 후보로 분류
4. source binding·exact set·claim boundary 독립검수 findings 0
5. 후보 범위에 대한 별도 사용자 승인과 적용 전 검증
6. 승인 범위대로 v2.4 canonical update event를 append하거나 v2.5 successor
   package를 별도 activation
7. 적용된 정본에서 ready frontier를 재계산하고 정확히 한 leaf만 materialize
8. 별도 full start gate와 명시적 `GOAL_STARTED`

위 1~8이 끝나기 전에 제품 코드를 수정하지 않는다.

### C. artifact 종결을 요청

R004와 R011 exact131 per-ID dependency를 사용한다. owner 승인, attestation,
formal, 실기기, 배포, 기관 수신, 실제 event를 내부 문서나 에이전트 verdict로
대체하지 않는다. negative/reject/fail은 원 receipt와 재실행 의무를 보존한 채
원인 lane으로 되돌린다.

## 8. working tree와 기록 경계

- working tree에는 기존 대규모 dirty/untracked 변경이 있다.
- 다른 작업의 파일을 정리·삭제·reset·checkout하지 않는다.
- 같은 문서에 복수 writer를 두지 않는다.
- 계획 변경 시 root/merge 담당자 한 명이 쓰고 병렬 에이전트는 read-only
  조사·독립검수를 담당한다.
- 프로젝트 파일·계획·자동화·상태를 실제로 변경한 세션만
  `daylog/2026-07-29.md` 또는 당시 로컬 날짜의 daylog를 add-only로
  갱신한다. §4의 읽기 전용 hash·binding·quick check와 §9의 `WAIT` 보고만
  수행한 세션은 이 규칙의 변경·새 검증·상태 판단에 포함하지 않으며
  daylog를 만들지 않는다.
- local-memory는 현재
  `/home/ddobagi/.codex/memory/memory.sqlite.write.lock` timeout 이력이 있다.
  기록 대상 세션에서 한 번 시도해 실패하면 반복 경쟁하거나 lock 파일을
  삭제하지 말고 daylog에 기록한다.

## 9. 새 Codex가 사용자에게 먼저 보고할 내용

새 세션에서는 파일 확인 뒤 다음 네 가지를 먼저 짧게 보고한다.

1. plan-only 보완은 findings 0으로 완료
2. plan은 reviewed지만 activated/rebaselined되지 않음
3. 이번 plan-only 작업의 제품·시험·Goal·canonical·artifact·release delta 0
4. 별도 실행 지시 전까지 next action은 `WAIT`

## 10. 다음 단일 행동

`WAIT_FOR_EXPLICIT_SEPARATE_EXECUTION_INSTRUCTION`
