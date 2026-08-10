# WalkSafe 저장소 작업 지침

이 저장소에서 작업을 시작하기 전에 반드시 다음 네 파일을 순서대로 읽는다.

1. `docs/control/walksafe-project-resumption-runbook.md`
2. `docs/control/goals/walksafe-completion-graph-v2-3/README.md`
3. `docs/control/goals/walksafe-completion-graph-v2-2/00-master-goal.md`
4. `docs/control/walksafe-project-continuation-checkpoint.json`

v2.3은 완료 영수증과 materialization 경로를 보존하기 위해 v2.2의 17개 Goal 문서를 원래 경로와 바이트 그대로 가져온다. 따라서 3번 Master 경로가 v2.2인 것은 의도된 imported-node 계약이다.

그다음 아래 빠른 정본 무결성 검사를 실행한다.

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_3.py
python3 -B scripts/check_walksafe_goal_graph_v2_3.py
```

이 두 검사는 재개할 정본과 포인터를 빠르게 확인하는 검사이며, 이것만으로 제품 코드·기능 구현의 시작 조건이 충족되지는 않는다. 제품 코드를 변경하기 전에는 `docs/control/walksafe-project-resumption-runbook.md` §10.1의 전체 구현 시작 검사를 모두 실행한다. 빠른 검사나 전체 검사 중 하나라도 실패하면 기능 구현을 시작하지 말고 기준선·체크포인트 불일치부터 해결한다.

새 터미널에서 체크포인트의 focus Work Item이 이미 `IN_PROGRESS`라면 제품 파일을 고치기 전에 §10.1 전체 검사를 새 원출력 경로로 다시 실행하고 `WORK_SESSION_RESUMED` event를 append한다. 이어 `python3 -B scripts/check_walksafe_goal_graph_v2_3.py --current-work-session-id <방금 기록한 event_id>`가 통과해야 한다. 완료 근거는 가장 최근의 `GOAL_STARTED` 또는 `WORK_SESSION_RESUMED` event에 결속한다. 기존 gate 원출력·receipt·event ID를 덮어쓰거나 재사용하지 않는다.

프로젝트별 핵심 원칙은 다음과 같다.

- 승인된 정책 기준선은 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`이다.
- Android 사용자 앱과 별도 Android 관리자 앱이 제품이다. Web/PWA는 `LEGACY_REFERENCE_ONLY`다.
- 기존에 확정된 정책을 다시 질문하지 않는다. 새 결정이 정말 필요한 경우에만 기존 결정과 겹치지 않는 질문을 한다.
- 현행 구현이나 과거 PWA 문서를 제품 정책으로 역승격하지 않는다.
- 승인된 r001 기준선·영수증·감사 파일과 생성된 DOC-01·DOC-05·RTM·Gap 파일을 직접 고치지 않는다.
- 구현 후에는 불변 r001을 덮어쓰지 않고 Active 원장 또는 새 revision으로 상태와 근거를 기록한다.
- 5개 출시 gate는 실제 증거 없이 닫거나 면제하지 않는다. 출시는 현재 `NOT_ELIGIBLE`이다.
- 현재 작업과 다음 작업은 체크포인트의 `current_work`, `focus_goal_id`, `ready_frontier_goal_ids`와 실제 hard dependency를 따른다. EPIC·작업 개수를 고정 단계로 해석하지 않는다.
- 체크포인트 `current_work`는 기존 구현 백로그의 EPIC 집계와 다음 행동 포인터다. 실제 실행 leaf와 완료 범위의 정본은 `goal_execution.focus_goal_id` 문서이며, `POLICY_GAP_WORK`는 그 문서의 정확히 한 정책·한 Gap만 처리한다. Workstream 자체는 `IN_PROGRESS`로 시작하지 않는다.
- Goal이 활성화된 뒤에는 `goal_execution`의 focus leaf를 수행한다. 완료 후 최신 Backlog `next_single_action`과 내부 잔여작업에서 필요한 successor를 동적으로 만들며, 정식·외부 증거만 남은 정책은 반복 선택하지 않고 해당 시험·Gate branch로 이관한다.
- Goal 전환은 successor와 다음 leaf를 먼저 사전검사한 뒤 이전 완료·canonical binding·새 포인터·파일 hash·전환 hash를 체크포인트 한 번의 논리적 commit으로 바꾼다. 중간 실패 때 반쪽 전환 상태를 남기지 않는다.
- 현재 작업트리에는 아직 커밋되지 않은 사용자 산출물이 많다. `git clean`, 강제 reset, 광범위 삭제를 실행하지 않는다.

세션을 끝낼 때는 안내서의 종료 체크리스트에 따라 체크포인트, 관련 Active 기록, daylog와 local-memory 작업 기록을 갱신한다.
