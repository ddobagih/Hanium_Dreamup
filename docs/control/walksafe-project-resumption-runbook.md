# WalkSafe 산출물 기반 프로젝트 재개·진행 안내서

문서 ID: `WS-PROJECT-RESUMPTION-RUNBOOK-20260722-001`

버전: `2.7.0`

상태: `ACTIVE_WORKING_GUIDE`

기준일: `2026-07-25`

## 0. 2026-07-25 current override — v2.4 전환 준비 후보

> **이 절이 이 안내서의 나머지 절보다 우선한다.** 아래의 “현재 v2.3”, FP-006·FP-010 등 과거 focus, v2.3 활성화 지시, `v2_3` checker와 `2026-07-24.3` 계약·해시는 당시 절차를 보존한 감사 이력이다. 현행 재개·활성화·구현 시작 지시로 사용하지 않는다.

확정된 전환 후보는 `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4`다. 실제 live 후보 설치 여부는 checkpoint의 `goal_execution.package_id`로 판정한다. v2.4가 기록돼 있어도 아래 상태는 활성 패키지가 아니라 승인 대기 후보를 뜻한다.

- package 상태: `PREPARED_NOT_ACTIVATED`
- activation 상태: `READY_NOT_ACTIVATED`
- imported Goal: 활성 v2.3 sequence 17의 Goal 문서 20개
- manifest SHA-256: `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07`
- sequence 1 `PACKAGE_PREPARED` SHA-256: `58c2b31637b5355609db14bf9e0779d4fd33d99f97a692866718a51fa64875d9`
- focus Goal: `WS-GOAL-EPIC-02-FP-011-R001` (`READY`)
- focus Work Item: `EPIC-02-FP011-LONG-LIVED-LOGIN`

재개 시에는 `AGENTS.md` → 이 override → `docs/control/goals/README.md` → graph-v2.4 `README.md`와 imported Master Goal → checkpoint와 v2.4 static manifest 순서로 읽는다. checkpoint가 아직 v2.3이면 v2.4 builder의 `PACKAGE_PREPARED` 후보 생성·검증부터 재개한다. v2.4 quick 계약의 실제 명령은 다음과 같다.

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_4.py
python3 -B scripts/check_walksafe_goal_graph_v2_4.py
```

검사 명령 계약 버전은 `2026-07-25.4`, quick 계약 SHA-256은 `7b66c4610e0ad2c84e1937915704b0635b1762dc65dc06762b36e4afacdd813a`, full 계약 SHA-256은 `8c7e16f13a66398e5ba067cba0df9ba00258018f630256f6abc5b881b0467b7c`다. full 계약의 exact 19개 명령과 순서는 `docs/control/README.md`의 v2.4 블록만 사용한다. 특히 마지막 `REPOSITORY_STATE` 명령은 다음과 같다.

```bash
: "${WALKSAFE_GATE_EVENT_ID:?required}" && python3 -B scripts/check_walksafe_project_continuation_v2_4.py --root . --checkpoint docs/control/walksafe-project-continuation-checkpoint.json --print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"
```

checkpoint가 v2.3인 동안에는 v2.4 검사를 성공 gate로 주장하거나 아래의 v2.3 절차로 대체하지 않는다. v2.4 sequence 1 후보를 설치·검증한 뒤에도 다음 문장과 byte-exact하게 같은 사용자 응답을 새로 받기 전에는 v2.4 `PACKAGE_ACTIVATED`와 FP011 `GOAL_STARTED`를 기록하지 않는다.

> WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4의 manifest SHA-256 7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07 및 PACKAGE_PREPARED seq1 SHA-256 58c2b31637b5355609db14bf9e0779d4fd33d99f97a692866718a51fa64875d9에 결속해 활성화를 승인합니다.

## 1. 이 문서의 목적

이 문서는 터미널이나 대화가 종료되어 이전 문맥이 사라져도 WalkSafe 프로젝트를 같은 기준으로 이어가기 위한 작업 안내서다. 새 작업자는 이 문서만 읽고도 다음을 판단할 수 있어야 한다.

- 무엇이 이미 사용자에게 승인된 정책인지
- 257개 산출물의 현재 상태가 무엇을 뜻하는지
- 어떤 문서가 현재 상태를 판정하는 정본인지
- 다음 기능 구현을 어떤 순서와 근거로 진행해야 하는지
- 구현 뒤 어떤 산출물과 추적자료를 갱신해야 하는지
- 아직 완료됐다고 말하면 안 되는 시험·게이트·출시 항목이 무엇인지

이 문서는 새로운 제품 정책을 정하지 않는다. 승인된 정책과 산출물을 찾아 쓰는 방법을 설명하는 DOC-01·DOC-03·DOC-04 지원 통제문서다. 새로운 258번째 산출물 유형이 아니다.

## 2. 5분 재시작 요약

새 세션에서는 다음 순서를 그대로 따른다.

1. 저장소 루트의 `AGENTS.md`를 읽는다.
2. 이 안내서를 읽는다.
3. Goal 그래프 v2.3의 `README.md`와 imported v2.2 `00-master-goal.md`를 읽는다.
4. `docs/control/walksafe-project-continuation-checkpoint.json`의 `goal_execution`·`current_work`·canonical binding·미해결 blocker를 확인한다.
5. 빠른 정본 무결성 검사인 `python3 -B scripts/check_walksafe_project_continuation_v2_3.py`를 실행한다.
6. 빠른 정본 무결성 검사인 `python3 -B scripts/check_walksafe_goal_graph_v2_3.py`를 실행한다.
7. 두 빠른 검사가 성공하면 체크포인트의 `focus_goal_id` 문서와 `ready_frontier_goal_ids`를 읽는다.
8. 제품 코드·기능 구현을 시작하기 전에는 §10.1의 전체 구현 시작 검사를 모두 실행한다. 빠른 두 검사만 통과한 상태에서는 구현을 시작하지 않는다.
9. 전체 검사가 성공하면 focus Goal의 entry gate와 action identity를 확인하고 재개한다. 진행 중 Goal이 없으면 ready frontier에서 선택 규칙에 맞는 Goal을 고른다.
10. 정책 → 요구사항 → 설계 → 시험계획 → 코드 순으로 영향 범위를 연결한 뒤 구현한다.
11. 구현과 내부 검증이 끝나면 Active 원장과 새 실행 증거를 갱신한다.
12. append-only인 Gap·백로그 r001~r012를 덮어쓰지 않고 새 Gap revision 또는 실행 기록으로 결과를 남긴다.
13. 체크포인트·daylog·local-memory를 갱신하고 다음 행동 한 가지를 명시한다.

EPIC-01의 Phase A~G는 내부 구현 검증까지 끝나 `IMPLEMENTATION_READY`다. EPIC-02에서는 FP-017 내부 slice, FP-018 보행 상태 복구, NPC 권한 세션 생명주기, FP-004 우선 사용자·필수 사전연습과 FP-005 공식 사용환경·횡단보도 경계를 차례로 내부 검증했다. FP-005 완료 영수증은 `WS-FP005-OFFICIAL-ENVIRONMENT-WORK-ITEM-COMPLETION-20260724-001`이고, canonical Gap successor는 `WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-012`다. `GAP-014=PARTIAL`이며 환경별 현장·실제 사용자·실기기·생산 프로필·정식 시험은 계속 `NOT_RUN`이다. v2.2 실행 이력은 sequence 20에서 동결했고 v2.3은 활성 상태다. 현재 다음 단일 작업은 `EPIC-02-FP006-SOLO-WALK-PHONE-MOUNTING`이며, 전체 시작 gate와 event-scoped receipt가 성공한 뒤 FP-006 `GOAL_STARTED`를 기록하고 구현한다.

### 2.1 의존성 그래프 기반 자율 완성 체계

반복적인 “계획 → 진행” 지시 없이 프로젝트를 이어가기 위한 현재 실행 준비 정본은 `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-3` v2.3.0이다. 이 패키지는 프로젝트를 미리 정한 단계 수로 나누지 않는다. 257개 산출물의 698개 의존관계, 68개 정책·Gap 관계와 실제 사건을 바탕으로 필요한 leaf Goal을 만들고, 현재 실행 가능한 Goal 집합인 `ready frontier`를 매 전환마다 다시 계산한다.

```text
승인 정본·산출물 의존성
          ↓
Workstream·동적 Goal DAG
          ↓
ready frontier 계산
    ┌─────┼────────┐
    ↓     ↓        ↓
정책·Gap  산출물   시험·Gate·외부 사건
          ↓
증거·successor·새 ready frontier
```

Master Goal은 `docs/control/goals/walksafe-completion-graph-v2-2/00-master-goal.md`다. v2.3은 완료 receipt와 materialization provenance를 보존하기 위해 v2.2의 17개 Goal 문서를 원래 경로·바이트 그대로 가져온다. 패키지 준비 상태는 `package_status=PREPARED_NOT_ACTIVATED`, 활성화 상태는 `activation_status=READY_NOT_ACTIVATED`다. 현재 focus는 `WS-GOAL-EPIC-02-FP-005-R001`이고 초기 ready frontier는 다음 세 항목이다.

- `WS-GOAL-EPIC-02-FP-005-R001`: FP-005 공식 사용환경·횡단보도 구현 Work Item
- `WS-GOAL-EPIC-03`: EPIC-01 의존성이 이미 끝난 독립 Workstream
- `WS-GOAL-EPIC-12`: 정식 검증 준비 Workstream. 실제 시험·Gate 완료가 아니라 준비 가능한 작업만 뜻함

EPIC 번호는 고정 단계가 아니라 기능·검증 영역을 묶은 Workstream 식별자다. Backlog의 `execution_order`도 의존성을 대신하지 않으며, ready Goal 사이의 우선순위 동률 해소에만 쓴다. 과거 구현 증거의 `EPIC-01 Phase A~G`와 `EPIC-02 Phase A`는 당시 구현 묶음의 역사적 이름이므로 바꾸지 않는다.

필요할 때 만드는 동적 Goal 유형은 다음과 같다.

- `POLICY_GAP_WORK`: 정책·Gap의 저장소 내부 미완료 구현
- `ARTIFACT_WORK`: Draft 보완, Active 사실 갱신, Planned 증거 생성
- `INTEGRATION_CANDIDATE`: 함께 시험할 앱·서버·모델·설정의 불변 후보 결속
- `FORMAL_TEST_RUN`: 승인된 시험계획과 적격 환경에서의 정식 실행
- `RELEASE_GATE`: 고유 선행조건이 준비된 개별 Gate 검증
- `RELEASE_DECISION`: 같은 후보의 시험·Gate를 근거로 한 출시 적격 판단
- `DEPLOYMENT_DELIVERY_EVENT`: 실제 배포·canary·smoke·rollback·기술자료 수령
- `OPERATION_EVENT`: 실제 장애·복원·비용·권한검토·삭제 등 운영 사건
- `HANDOVER_CLOSURE_EVENT`: 운영 이관·인수·프로젝트 종료 사건
- `BLOCKER_OR_EXTERNAL_RECEIPT`: 사람·기기·비밀·비용·외부 승인 조건
- successor·reopen은 별도 Work Item 유형이 아니다. 결함·정책 변경·증거 반려를 먼저 Gap·Backlog canonical 영향 transaction으로 기록한 뒤 기존 유형을 유지한 연속 revision과 `GOAL_SUPERSEDED`·`REOPEN_CONTAINER` 전환으로 처리한다.

다음 네 통제는 완료하고 지나가는 단계가 아니라 모든 Goal에 계속 적용하는 상시 lane이다.

- 산출물·변경 통제: DOC-01·DOC-05와 Active 대장 갱신
- 추적·증거 통제: 정책→요구→설계→코드→시험→Gap·Backlog successor 연결
- 안전·보안·개인정보 통제: 안전정지, 권한, 원본수집, 보존·삭제 영향 교차검토
- 실행 인계 통제: append-only event, checkpoint, daylog, local-memory 갱신

Goal 문서는 새 정책이나 258번째 산출물 유형이 아니다. 실행 포인터와 동적 노드의 정본은 체크포인트 `goal_execution`이며, 실제 작업은 `focus_goal_id`, `ready_frontier_goal_ids`, `current_work`와 최신 Backlog의 `next_single_action`을 함께 확인한다.

`current_work`는 기존 구현 백로그의 EPIC 집계와 다음 행동 포인터이므로 여러 정책·Gap을 담는다. 이것을 focus leaf의 완료 범위로 사용하지 않는다. 실제 실행·완료 범위는 반드시 `goal_execution.focus_goal_id`가 가리키는 Work Item 문서의 정확히 한 정책·한 Gap을 따른다. Workstream은 컨테이너라 `IN_PROGRESS`로 시작하지 않는다.

Goal이 활성화되면 저장소 내부 구현·내부 검증·Draft·Active 사실 기록·Gap/Backlog successor·승인 정책 안의 내부 기술·관리 문서 검토와 위임 승인·체크포인트·daylog·local-memory 갱신은 질문 조건이 생길 때까지 ready frontier를 따라 이어간다. 규범 정책·안전·개인정보 기준선 변경, 실기기·참여자·유료 자원·비밀정보, 프로덕션 배포, 파괴적 외부 작업, 독립 전문검토, Gate·정식 PASS·출시·인수·종료 서명은 실제로 필요할 때 typed request로 기록한다. 다른 내부 ready branch는 계속 진행하고 USER 질문 전달은 내부 frontier가 소진될 때까지 미룬다. 세부 규칙과 활성화 경계는 `docs/control/goals/walksafe-completion-graph-v2-3/README.md`에 있다.

`validation_cutoff_at`은 고정된 프로젝트 단계나 영구 날짜가 아니라 해당 checkpoint snapshot에 포함할 수 있는 event의 시간 상한이다. 다음 날짜에 정상 작업을 append할 때 새 snapshot 시각으로 갱신하되, 이미 기록된 최신 event보다 이르게 두거나 과거 event를 다시 쓰지 않는다.

활성화는 중복 실행하지 않는다. 현재는 `READY_NOT_ACTIVATED`이므로 v2.3 package ID와 최종 manifest SHA-256을 명시한 사용자 활성화 요청을 받기 전에는 코드 작업 event를 기록하지 않는다. v2.2의 과거 활성화 승인은 v2.3으로 승계하지 않는다. 활성화할 때는 사용자 승인 원본을 먼저 고정하고, checker에 그 원본 지문을 반영한 뒤 manifest의 `2026-07-24.3` quick 계약을 실행한다. 저장소 snapshot과 검사 원출력을 별도 quick-gate receipt에 담아 `PACKAGE_ACTIVATED`와 결속한다. `PACKAGE_ACTIVATED`는 `package_status`와 `activation_status`만 `ACTIVE`로 바꾸고 모든 Goal 상태는 그대로 둔다.

제품 코드 변경 전에는 §10.1 전체 구현 시작 검사를 정적 manifest가 고정한 순서와 명령 그대로 모두 통과해야 한다. 검사 원출력은 `docs/control/execution/goal-gates/<event_id>/` 아래 새 파일로만 기록한다. 그 다음 별도 `GOAL_STARTED` event로 선택된 focus Work Item 하나만 `READY → IN_PROGRESS`로 바꾼 뒤 코드 작업을 시작한다. 이미 같은 package가 `ACTIVE`이면 새 활성화 event를 만들지 않는다. 새 터미널에서 현재 `IN_PROGRESS` leaf를 재개할 때는 §10.1을 다시 실행하고, 상태를 바꾸지 않는 `WORK_SESSION_RESUMED` event와 새 전용 원출력·receipt를 먼저 append한다. 이어 `python3 -B scripts/check_walksafe_goal_graph_v2_3.py --current-work-session-id <방금 기록한 event_id>`가 통과한 뒤에만 제품 파일을 변경한다. 과거 경로·receipt·event ID는 재사용하지 않는다. 완료된 package는 다시 시작하지 않는다.

이미 시작된 Work Item의 제품 파일이 바뀐 뒤 터미널이 끊겼다면, §10.1 실행 전에 §9의 계산법으로 checkpoint의 `working_tree_snapshot`과 `session_handoff.source_commit_or_snapshot`을 현재 통제 경로 bytes에 맞춰 조정한다. 새 파일·삭제처럼 membership이 달라졌다면 continuation checker의 exact controlled-path manifest, `working_tree_snapshot.managed_changed_paths/count`, `session_handoff.changed_files`도 같은 집합으로 함께 갱신한다. 이 행정적 snapshot reconcile에서는 transition history, canonical binding, Goal 상태·문서, 기존 gate receipt를 바꾸지 않는다. 이전 `GOAL_STARTED`·`WORK_SESSION_RESUMED`의 `repository_snapshot_before`는 당시 gate와 결속된 역사 snapshot으로 보존한다. reconcile 전 continuation 실패는 예상되지만, reconcile 뒤 plain continuation·Goal graph 검사가 모두 통과해야 전체 gate를 실행할 수 있다. 새 resume receipt와 event는 reconcile된 같은 snapshot을 결속하며, gate가 끝난 뒤 event를 기록하기 전에는 통제 경로를 변경하지 않는다. 이 절차는 구현·완료·검증 증거가 아니다.

### 2.2 ready frontier·전환·대기 규칙

ready leaf는 모든 `start_requires`가 완료되고, 활성 blocker가 없으며, 적용조건과 정본 SHA-256이 유효한 미완료 Goal이다. 선택 순서는 다음과 같다.

1. 이미 `IN_PROGRESS`인 leaf를 재개한다.
2. canonical Backlog의 `next_single_action`과 일치하는 ready leaf를 고른다.
3. P0 안전·개인정보·보안 작업을 우선한다.
4. 더 많은 후행 작업을 여는 Goal을 우선한다.
5. `priority_rank`와 Goal ID로 결정적으로 동률을 해소한다.

`PLANNED` Goal의 선행조건이 충족되면 `BLOCKER_RESOLVED`를 빌려 쓰지 않는다. 해당 Goal 하나만 `READY`로 바꾸는 `GOAL_READY` event를 append하고, 각 `start_requires`의 실제 완료 event SHA-256을 `readiness_basis`에 기록한다.

일반 내부 ready·`IN_PROGRESS` frontier가 모두 소진되면 `artifact_work_queue.next_assessment_target_id`를 건너뛰지 않는다. queue가 priority→unlock→topology→code 순서로 파생한 정확히 한 대상의 현재 상태·필수 reason·허용 결과에 따라 단일 `ARTIFACT_WORK`를 materialize하거나 `ARTIFACT_REGISTER` canonical transaction으로 허용된 terminal/inactive disposition을 기록한다. `WAITING_APPLICABILITY`와 `WAITING_TRIGGER`는 그 전까지 비종결이다. `ACTIVE_EVENT_UPDATE`는 정렬된 비어 있지 않은 trigger refs와 각 trigger JSON의 `document_id/path/file_sha256` exact direct binding을 materialization event에 봉인하고, 다른 reason은 direct bindings를 빈 객체로 둔다. 내부 `PLANNED/BLOCKED` leaf나 비종결 artifact 대상이 남아 있으면 `COMPLETE_AWAITING_EXTERNAL`로 전환하지 않는다.

정식 시험·Gate·출시 판단·배포·운영·이관 Work Item은 단순히 의존 Goal 상태만 보고 시작하지 않는다. 정적 manifest의 유형별 `required_start_evidence_roles`, 부모 범위, 선행 Work Item 유형·최소 수를 모두 만족해야 한다. `GOAL_READY`와 `GOAL_STARTED`는 각각 그 시점 canonical binding의 path·document ID·파일 SHA-256·상태·candidate를 `start_evidence_bindings`에 봉인한다. 필수 role은 선행 producer Goal이 실제 완료 증거로 사용한 binding 및 완료 event hash와 `start_evidence_provenance`로 이어져야 한다. 외부 receipt는 checker의 role별 append-only attestation trust-anchor history에 해당 hash가 있어야 하며, 새 revision을 추가해도 과거 hash를 제거하지 않는다. authority roster의 첫 등록은 sequence 1과 current checker anchor가 필요하고, 회전은 predecessor ID·SHA-256, sequence·effective time을 단조롭게 잇는다. 과거 roster hash는 과거 event replay에만 허용하고 최종 canonical roster는 current anchor와 같아야 한다. 나중에 같은 role의 정본이 바뀌어도 과거 사건은 당시 binding으로 재검증한다. 증거가 없거나 후보·권한이 다르면 `IN_PROGRESS`로 전환하지 않고 blocker로 남긴다.

Workstream은 동적 Work Item을 소유하는 컨테이너이므로 `IN_PROGRESS`로 시작하지 않는다. ready frontier가 Workstream을 선택하면 해당 영역의 다음 필수 Work Item을 materialize하고 그 leaf 하나만 시작한다. 모든 child·정책/Gap coverage·Backlog 목표가 충족된 Workstream은 `READY → COMPLETE_AT_TARGET`으로 닫는다.

Work Item 전환은 준비 파일이 반쯤 반영되는 상태를 만들지 않는다.

1. 실제 변경 hash가 있는 `IMPLEMENTATION_RECORD`와 명령·exit code·원출력 hash가 있는 `VERIFICATION_RESULT`를 먼저 서로 다른 typed JSON으로 확정한다. 새 Gap의 `evidence_catalog`에는 이 두 결과의 exact hash만 넣어 Gap·Backlog bytes를 확정한다. 그 최종 Gap·Backlog file hash를 참조하는 post-update `SUCCESSOR_TRACE`를 다음에 만들되 catalog에는 되넣지 않는다. 마지막으로 canonical update와 completion receipt가 세 결과를 모두 exact 결속해 hash 자기순환을 피한다.
2. Gap·Backlog 등 canonical binding이 바뀌면 이전·이후 파일에서 실제 변경 정책·Gap ID를 계산한다. 요구·설계·모듈·시험 행은 추적된 정책·Gate·Gap으로 영향을 전달하며 연결이 없으면 `*` 전역 영향이다. 식별자 없는 전역 제품경계·권한·실행·완료 규칙 변화와 role별 비영향 provenance 목록에 없는 새 필드도 `*`다. Gap·Backlog는 유효한 자체 내용 지문과 같은 Gap 지문을 가진 원자 쌍으로만 교체한다. 유효 정책·Gate 집합 또는 정책↔Gap 연결 지문이 정적 manifest와 달라지면 현재 패키지를 수정하지 않고 successor package로 넘긴다. Gap `reassessment_scope`와 실제 changed/carry-forward row가 다르면 중단하고, Backlog의 진행 집계·다음 포인터는 파생값으로 분리한다.
3. 현재 Work Item이 허용된 Gap·Backlog 또는 Draft·Active 산출물을 만든 경우 `produced_binding_roles`와 가장 최근 실행 세션 event(`GOAL_STARTED` 또는 `WORK_SESSION_RESUMED`)에 결속된 `WORK_ITEM_COMPLETION::<goal_id>` receipt를 준비한다. `POLICY_GAP_WORK`는 Gap·Backlog를 원자 쌍으로 생산한다. `ARTIFACT_WORK`는 materialize할 때 register·change log의 동일한 비어 있지 않은 `DLV-*` 대상과 함께 생산할 role별 실제 행 ID를 `output_subject_ids_by_role`로 고정한다. update event, 실제 register delta와 append된 change-log affected 집합이 정확히 같고 두 원장의 self-seal이 유효해야 한다. 정책 기준선은 내부 생산할 수 없다. 승인된 불변 row, `NOT_RUN/PASS`, blocker·면제·적용성·외부서명 변경은 외부 권한 근거가 필요하지만, 승인 정책을 바꾸지 않는 내부 기술·관리 문서는 실제 변경·검증·독립 review record를 근거로 위임 승인할 수 있다.
4. 영향받은 완료 Work Item에는 독립 `UNAFFECTED` assessment 또는 successor를, 영향받은 미완료 Work Item에는 successor를 같은 staged 후보에 둔다. successor는 blocker 대상과 `ARTIFACT_WORK` 출력 범위를 포함한 기존 의미 범위를 보존해야 한다. 내부 생산자의 canonical update 바로 다음 event는 그 생산자 완료여야 한다.
5. 완료 Workstream이 영향받으면 부모와 `start_requires·completion_requires` 역의존 폐쇄를 계산한다. 과거 완료 event·증거를 지우지 않고 같은 canonical update에서 직접 영향 또는 child·completion 집계 갱신만 필요한 완료 Workstream은 `REOPEN_CONTAINER`와 `READY`, start dependency가 무효화된 완료 Workstream은 `REOPEN_CONTAINER`와 `PLANNED`로 원자 전환한다. 후자는 upstream 재완료 뒤 별도 `GOAL_READY`가 필요하다. 선행조건이 깨진 준비 상태도 `PLANNED`로 돌리고, superseded Work Item을 선행으로 가진 계획 상태 Work Item까지 최신 dependency revision에 다시 결속한 연속 successor로 대체한다. 재완료는 직전 reopen과 최신 dependency·child 완료 event보다 뒤에 생성된 집계 검증을 사용한다. 정책/Gap 소유·Workstream·정적 dependency·우선순위·목표수준 변경은 versioned successor manifest로 처리하고, 완료 Master와 `PACKAGE_COMPLETED` 뒤에는 현재 패키지 event를 추가하지 않는다.
6. Active 변경이력은 기존 prefix를 유지해 append하고, 산출물 관리대장은 기존 artifact type→instance identity를 보존한다. 정상 원장 추가만으로 과거 Goal을 소급 무효화하지 않는다.
7. 정책·Gap·의존성·경로·SHA-256, 가장 최근 실행 세션 event, 시간, 세 typed JSON과 완료 event의 동일 증거·receipt binding을 staged checkpoint에서 사전검사한다.
8. 이전 Goal 완료, 새 focus·ready·blocked 집합, 파일집합 hash와 hash-linked 전환 이력을 한 번의 논리적 commit 후보로 만든다.
9. 새로 풀린 Goal의 `GOAL_READY`와 새 ready frontier까지 staged checkpoint에 포함해 검사하고, continuation·Goal 그래프 검사와 관련 회귀가 모두 통과한 뒤 한 번에 전환한다.

전환 event는 연속 sequence·고유 ID·직전 event hash·정적 manifest hash·상태 변화·focus·ready·blocked snapshot·canonical 증거를 기록한다. 모든 event의 activation/package 상태, focus Work Item ID와 source도 replay 생명주기에서 다시 계산하므로 중간 event에서 임의로 되돌리거나 바꿀 수 없다. 모든 시각은 timezone 포함 유효 시각이고 event 순서대로 엄격히 증가해야 한다. 완료 receipt는 Goal·정책·Gap·실행 구간과 결속되고, `IMPLEMENTATION_RECORD`, `VERIFICATION_RESULT`, `SUCCESSOR_TRACE`를 각각 정확히 하나 참조해야 한다. 유형별 필수 증거 role도 정확한 이름으로 각각 한 번 참조하며 비슷한 접미사 별칭은 인정하지 않는다. 정식·외부 실행 receipt는 가장 최근의 `GOAL_STARTED` 또는 `WORK_SESSION_RESUMED` 뒤에 실행을 시작하고 `GOAL_COMPLETED` 전 생성·승인을 끝내며 시작 event가 고정한 candidate와 같아야 한다. Work Item과 Workstream 완료 증거는 완료 당시 `completion_evidence_bindings`로 검증하며 이후 같은 role의 revision이 바뀌어도 재해석하지 않는다. 부모 Workstream이나 Master는 모든 필수 자식과 `completion_requires`가 끝나기 전에 완료할 수 없다.

한 branch가 막히면 해당 Goal만 `AWAITING_USER`, `AWAITING_EXTERNAL` 또는 `BLOCKED`로 두고 다른 ready leaf를 선택한다. 이것은 고정 단계를 건너뛰는 “우회”가 아니라 정상적인 DAG 실행이다. blocker receipt는 원 blocker event·불변 snapshot·권한 roster·원자료 SHA-256과 시간 순서를 결속해야 하며, 일정이나 담당자만 정한 상태는 해제가 아니다. 결정적 focus의 USER 질문은 내부 frontier가 남아 있으면 기록만 하고 전달은 미루며, 모든 내부 branch와 artifact assessment가 소진된 뒤에만 사용자에게 묶어 전달한다.

USER/EXTERNAL blocker request는 사건 직전 ready frontier의 첫 Goal이면서 현재 runtime focus인 결정적 대상에만 만든다. 각각 `USER_DECISION`·`EXTERNAL_ACTION_EVIDENCE` kind와 `USER_AUTHORIZATION`·`EXTERNAL_ATTESTATION` 권한 요구를 쓰고, owner·kind·구체 action·대상 Goal 경로/내용 hash·Work Item 유형·정확히 하나인 정규화 해제 증거 role·권한 요구의 canonical logical basis SHA-256을 전역 고유 request key로 봉인한다. blocker ID·request event ID·불변 blocker snapshot도 직접 결속하지만 volatile ID만 바꿔 같은 요청을 재발행할 수 없고, 해제된 과거 key도 다시 쓸 수 없다. pending question은 활성 USER request와 정확히 일대일이며 내부 frontier가 있으면 `delivery_status=DEFERRED_INTERNAL_FRONTIER`, 소진된 뒤에만 `READY_FOR_USER`다. 활성 request가 다른 내부 ready branch를 선점하지 않는다.

저장소 내부 범위가 모두 소진돼 `COMPLETE_AWAITING_EXTERNAL`에 처음 진입하거나 외부 request basis가 바뀔 때는 해당 event 전용 add-only `EXTERNAL_ACTION_PACKET`을 만든다. packet은 정렬된 request basis, 대상 Goal 경로·hash·유형, source blocker snapshot·event, 정확한 action·evidence/completion role·candidate·authority/roster 요구를 direct SHA-256 binding으로 결속한다. `status=ISSUED_NOT_EVIDENCE`인 packet은 요청서일 뿐 완료 receipt·외부 attestation·권한 증거가 아니며 어떤 완료 `evidence_refs`에도 사용할 수 없다.

현재 정적 계획은 `walksafe-completion-graph-v2-3/static-plan-manifest-v2.3.0.json`이다. v2.2 전체 checkpoint archive, frozen checker/test, sequence 1~20과 tail은 v2.3의 active-predecessor import 계약으로 보존한다. v2.3 최초 `PACKAGE_PREPARED` event는 새 manifest hash와 checker trust anchor에 일치하고 `supersedes_event_sha256`으로 v2.2 tail을 결속한다. 이후 v2.3 tail은 과거 event를 덮어쓰지 않고 직전 hash를 이어 append한다.

`walksafe-completion-graph-v2-2` v2.2는 실제 제품 작업과 내부 증거를 가진 활성 predecessor이며 sequence 20에서 동결했다. v2.1은 활성화 전 hardening predecessor이고, v2.0과 v1.1도 미활성 감사 이력이다. 과거 패키지의 파일·지문과 당시 event는 그대로 보존하며 새 실행 지시로 사용하지 않는다.

## 3. 현재 확정 상태

### 3.1 승인·출시 경계

| 항목 | 현재 값 | 의미 |
|---|---|---|
| 정책 기준선 | `PB-WALKSAFE-FEATURE-POLICY-1.0.1` | 사용자가 확정한 제품 정책 |
| 승인 적용 거래 | `WS-ARTIFACT-BASELINE-APPLICATION-20260722-001` | 257개 산출물 상태 전환 거래 |
| 승인 적용 상태 | `COMMITTED` | 승인 적용이 완료돼 현재 효력이 있음 |
| Approved/Baselined | 102개 | 구현 기준으로 승인된 버전형 내용 |
| Active | 27개 | 최초본이 승인됐고 계속 갱신하는 원장·대장 |
| Draft | 53개 | 외부값·구현·실행 근거가 더 필요함 |
| Planned/NOT_RUN | 75개 | 실제 시험·배포·운영·인수 등 아직 실행하지 않은 증거 |
| 정식 시험계획 | 279개 | 계획만 있으며 전부 `NOT_RUN` |
| 실제 기기 검증 | `NOT_RUN` | 실제 휴대전화에서 아직 검증하지 않음 |
| 남은 gate | 5개 | 전부 `NOT_RUN`, 미면제 |
| 출시 상태 | `NOT_ELIGIBLE` | 공개 출시·완료를 주장할 수 없음 |

102개 내용 승인과 27개 Active 개설은 구현·시험·배포가 끝났다는 뜻이 아니다. 이것들은 앞으로 무엇을 만들고 어떤 근거로 판정할지 정한 기준이다.

### 3.2 현재 구현 Gap

승인 정책 9개 공통정책, 54개 기능정책, 5개 gate를 현행 구현과 비교한 68개 판정은 다음과 같다.

| 판정 | 수 | 뜻 |
|---|---:|---|
| `CONFLICTING` | 18 | 구현이 승인 정책과 반대되거나 다른 방향임 |
| `MISSING` | 18 | 필요한 구현이 없음 |
| `PARTIAL` | 23 | 일부만 구현됨 |
| `EVIDENCE_MISSING` | 4 | 구현 또는 계획은 있으나 판정 증거가 부족함 |
| `BLOCKED` | 5 | 실제 측정·독립검토·복구훈련 gate가 미실행임 |
| `IMPLEMENTED` | 0 | 정식 증거까지 갖춘 완료 판정은 아직 없음 |

현재 checkpoint가 결속한 Gap snapshot은 `WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-011` 버전 0.11.0이다. FP-017·FP-018·NPC 권한 세션에 이어 전맹·저시력 사용자를 같은 우선순위로 둔 접근 가능한 최초 교육, 위험 안내·일시정지·재개·안전정지의 필수 직접 연습, 연령·보호자 확인·필수 음성·진동 조건의 보행 시작 fail-closed를 내부 구현했다. Android 사용자 앱 JVM `480/480`, debug assemble, lint가 통과했지만 모두 저장소 내부 검증이다. 따라서 `GAP-006`, `GAP-013`, `GAP-026`, `GAP-027`은 `PARTIAL`이고 우선 사용자·실제 기기·보호자 확인 공급자·정식 시험과 `IMPLEMENTED` 판정은 남아 있다. r008~r010과 각 완료 기록은 append-only 역사 경계로 보존한다. 다음 정책·Gap은 `FP-005 / GAP-014`다.

## 4. 정본과 신뢰 우선순위

정책·승인·산출물 상태가 서로 충돌하면 아래 순서로 판단한다.

1. COMMITTED 승인 적용 영수증
2. 정책 기준선 1.0.1 manifest
3. DOC-01 현재 산출물 상태
4. DOC-05 변경 이력
5. 최신 Gap 보고서와 수정 백로그
6. RTM·설계 추적·시험계획
7. 현행 코드·설정·실행 결과
8. 과거 계획·PWA 문서·제출용 문서

체크포인트는 위 정본을 바꾸는 정책 문서가 아니다. 현재 진행 중인 EPIC, 통제 작업경로, 마지막 검증과 다음 행동을 판단할 때만 최신 유효 체크포인트를 우선한다. 즉, 정책 사실은 receipt와 정책 기준선에서 읽고, 작업 인계 사실은 체크포인트에서 읽는다.

코드는 현재 동작을 보여주지만 제품 정책을 정하지 않는다. 과거 문서의 `Web/PWA가 주제품`이라는 설명은 현재 정책과 충돌하는 역사 자료다.

### 4.1 핵심 정본 목록

| 역할 | 경로 | 식별값 |
|---|---|---|
| 유효 정책 manifest | `docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json` | `PB-WALKSAFE-FEATURE-POLICY-1.0.1`과 FP-035 overlay 승인 결속 |
| 승인된 전체 정책 본문 | `docs/control/decision-interview/walksafe-feature-policy-comprehensive-draft.json` | 1.0.0에서 승인된 GP-01~09·FP-001~054 전체 본문 |
| 사람이 읽는 전체 정책 | `docs/control/decision-interview/walksafe-feature-policy-comprehensive-draft-20260720.html` | 위 JSON의 비전공자용 표현 |
| FP-035 승인 overlay | `docs/control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json` | 1.0.1에서 FP-035만 대체하는 규칙 |
| 승인 적용 최종 표지 | `docs/control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json` | `WS-ARTIFACT-BASELINE-APPLICATION-RECEIPT-20260722-001` |
| DOC-01 산출물 관리대장 | `docs/deliverables/00-control/artifact-register.json` | `ART-DOC-01-001` |
| DOC-05 변경이력 | `docs/deliverables/00-control/artifact-change-log.json` | `ART-DOC-05-001` |
| 요구사항 추적표 | `docs/deliverables/03-requirements/rtm.json` | 68개 요구·279개 예정 시험 |
| 설계 추적표 | `docs/deliverables/04-design/design-traceability-register.json` | REQ↔DES 연결 |
| 시험 케이스 원장 | `docs/deliverables/06-testing/registers/test-cases.json` | 실행 전 시험계획 |
| 현재 canonical Gap | `docs/control/audits/walksafe-implementation-gap-analysis-20260724-r011.json` | `WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-011`; `GAP-013`을 `MISSING`에서 `PARTIAL`로 재평가한 FP-004 successor |
| 현재 canonical Backlog | `docs/control/audits/walksafe-implementation-remediation-backlog-20260724-r011.json` | `WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-011`; 구현 백로그 EPIC-02 `IN_PROGRESS`, 다음 작업 `FP-005/GAP-014` 고정 |
| Goal 그래프 안내 | `docs/control/goals/walksafe-completion-graph-v2-3/README.md` | v2.2 활성 이력을 가져온 고정 단계 없는 DAG·ready frontier·상시 통제 규칙 |
| Goal 그래프 정적 manifest | `docs/control/goals/walksafe-completion-graph-v2-3/static-plan-manifest-v2.3.0.json` | `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-3` native 보호 파일·imported Goal·전환 계약 |
| 마지막 완료 Goal | `docs/control/goals/walksafe-completion-graph-v2-2/work-items/epic-02/epic-02-fp004-priority-user-r001.md` | `WS-GOAL-EPIC-02-FP-004-R001`; FP-004/GAP-013 내부 완료, 우선 사용자·실제 기기·보호자 확인 공급자·정식 검증은 남음 |
| 마지막 완료 영수증 | `docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-004-R001/completion-receipt.json` | `WS-FP004-PRIORITY-USER-WORK-ITEM-COMPLETION-20260724-001`; 내부 목표 완료와 외부 `NOT_RUN` 경계를 분리 |
| 현재 focus Goal | `docs/control/goals/walksafe-completion-graph-v2-2/work-items/epic-02/epic-02-fp005-official-environment-crosswalk-r001.md` | `WS-GOAL-EPIC-02-FP-005-R001`; `FP-005/GAP-014` |
| Phase B 구현 기록 | `docs/control/execution/walksafe-epic-01-phase-b-admin-auth-recovery-implementation-record-20260722.json` | `WS-EPIC-01-PHASE-B-ADMIN-AUTH-RECOVERY-IMPLEMENTATION-20260722-001` |
| 단일 관리자 복구훈련 절차 | `docs/control/execution/walksafe-single-admin-recovery-drill-protocol-20260722-r001.json` | 절차만 작성됨; `DRAFT_PROCEDURE_NOT_EXECUTED`, 실행 `NOT_RUN` |
| Phase B Active overlay | `docs/control/execution/walksafe-epic-01-phase-b-active-ledger-overlay-20260722-r001.json` | lifecycle·승인 상태를 올리지 않는 후속 Active 반영 사건 |
| Phase C 구현 기록 | `docs/control/execution/walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.json` | `WS-EPIC-01-PHASE-C-RUNTIME-METRIC-PREFLIGHT-IMPLEMENTATION-20260722-001`; 내부 검증만 PASS |
| Phase C Active overlay | `docs/control/execution/walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.json` | r001·r002·Phase B를 보존하는 successor 사건 |
| Phase D 구현 기록 | `docs/control/execution/walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.json` | `WS-EPIC-01-PHASE-D-LEGACY-WEB-CLOSURE-IMPLEMENTATION-20260723-001`; 저장소 공식 경로 내부 검증만 PASS |
| Phase D Active overlay | `docs/control/execution/walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.json` | Phase C와 과거 기록을 보존하는 successor 사건 |
| Phase E 구현 기록 | `docs/control/execution/walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.json` | `WS-EPIC-01-PHASE-E-ANDROID-GATEWAY-IMPLEMENTATION-20260723-001`; 독립 Gateway 추출 내부 검증만 PASS |
| Phase E Active overlay | `docs/control/execution/walksafe-epic-01-phase-e-active-ledger-overlay-20260723-r001.json` | Phase D와 과거 기록을 보존하는 successor 사건 |
| Phase F 구현 기록 | `docs/control/execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.json` | `WS-EPIC-01-PHASE-F-PURPOSE-SURFACES-IMPLEMENTATION-20260723-001`; 사용자 표면 내부 정합화만 PASS |
| Phase F Active overlay | `docs/control/execution/walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.json` | Phase E와 과거 기록을 보존하며 승인 상태를 바꾸지 않는 successor 사건 |
| Phase G 구현 기록 | `docs/control/execution/walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.json` | `WS-EPIC-01-PHASE-G-NO-DESTINATION-HAZARD-IMPLEMENTATION-20260723-001`; 목적지 없는 위험안내 내부 정합화만 PASS |
| Phase G Active overlay | `docs/control/execution/walksafe-epic-01-phase-g-active-ledger-overlay-20260723-r001.json` | Phase F와 과거 기록을 보존하며 승인 상태를 바꾸지 않는 successor 사건 |
| EPIC-02 Phase A 구현 기록 | `docs/control/execution/walksafe-epic-02-phase-a-walk-session-lifecycle-implementation-record-20260723.json` | `WS-EPIC-02-PHASE-A-WALK-SESSION-LIFECYCLE-IMPLEMENTATION-20260723-001`; FP-017 내부 부분 구현 |
| EPIC-02 Phase A Active overlay | `docs/control/execution/walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.json` | Phase G와 과거 기록을 보존하며 승인 상태를 바꾸지 않는 successor 사건 |
| 기계 체크포인트 | `docs/control/walksafe-project-continuation-checkpoint.json` | 현재 세션 인계 상태 |

일부 RTM·설계 추적파일 본문에는 승인 적용 전 `DRAFT` 표기가 남아 있을 수 있다. 현재 효력 상태는 COMMITTED 영수증과 DOC-01 overlay가 결정한다. 파생 추적자료의 표기는 다음 Active 갱신에서 정합화하되 승인된 원본을 직접 덮어쓰지 않는다.

### 4.2 정책 ID의 유효 본문 찾기

유효 정책 1.0.1은 `승인된 1.0.0 전체 본문 + 승인된 FP-035 overlay`다. 전체 정책 JSON 파일명의 `draft`와 내부 `NOT_APPROVED` 표기는 검토 당시 이력이며, 이후 1.0.0 승인 manifest와 COMMITTED 적용 영수증이 현재 효력을 정한다.

- GP-01~09와 FP-001~034·FP-036~054는 전체 정책 본문의 같은 ID를 읽는다.
- FP-035는 전체 정책 본문에서 구조와 연결정보를 읽되, 충돌하는 규칙은 1.0.1 manifest의 `composition.approved_normative_rule`과 overlay가 대체한다.
- manifest만 읽고 다른 FP의 본문을 추측하지 않는다.

예를 들어 FP-003 본문을 찾을 때는 다음 읽기 전용 명령을 쓴다.

```bash
POLICY_ID=FP-003 python3 -B - <<'PY'
import json, os
from pathlib import Path

policy_id = os.environ["POLICY_ID"]
document = json.loads(Path(
    "docs/control/decision-interview/walksafe-feature-policy-comprehensive-draft.json"
).read_text(encoding="utf-8"))
items = document["common_policies"] + document["features"]
match = next(item for item in items if item["id"] == policy_id)
print(json.dumps(match, ensure_ascii=False, indent=2))
if policy_id == "FP-035":
    manifest = json.loads(Path(
        "docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json"
    ).read_text(encoding="utf-8"))
    print("\n[1.0.1에서 대체된 최종 규칙]")
    print(manifest["composition"]["approved_normative_rule"])
PY
```

## 5. 257개 산출물을 이해하는 방법

257개는 반드시 257개 파일을 뜻하지 않는다. 분야별 읽기 문서에 여러 산출물 항목을 묶고, 계속 변하거나 독립 증거가 필요한 원장·시험 결과만 별도 파일로 둔다.

### 5.1 분야별 역할

| 분야 | 범위 | 쉽게 말하면 |
|---|---|---|
| DOC | DOC-01~05 | 모든 문서를 어떻게 등록·승인·변경할지 정하는 통제 장치 |
| MGT | MGT-01~18 | 목표, 일정, 역할, 위험, 결정, 변경을 관리하는 프로젝트 운영 자료 |
| DSC | DSC-01~15 | 사용자 문제와 제품 방향, MVP, 우선순위를 설명하는 기획 근거 |
| REQ | REQ-01~19 | 제품이 반드시 해야 할 일과 합격조건 |
| DES | DES-01~27 | 요구사항을 코드·화면·데이터·보안 구조로 구현하는 방법 |
| DEV | DEV-01~21 | 실제 소스, 빌드, 개발 규칙, 구성·통합 증거 |
| TST | TST-01~23 | 무엇을 어떻게 시험하고 어떤 결과로 합격시킬지 정하는 자료 |
| SEC | SEC-01~19 | 보안·개인정보 위험, 권한, 취약점, 사고대응 자료 |
| AIML | AIML-01~26 | 데이터·학습·모델·평가·배포·드리프트 자료 |
| REL | REL-01~22 | 출시 승인, 배포, 롤백, 사용자·관리자 인도 자료 |
| OPS | OPS-01~24 | 운영 감시, 장애대응, 백업, 비용, 유지보수 자료 |
| WS | WS-01~22 | WalkSafe의 실폰·현장·접근성·오탐/미탐 등 특화 검증 |
| CLS | CLS-01~16 | 프로젝트 완료·이관·잔여위험·폐기 자료 |

### 5.2 세 가지 관리 방식

| 방식 | 대표 예 | 관리법 |
|---|---|---|
| 버전형 기준선 | 요구사항·정책·설계 | 승인된 버전은 고정한다. 내용 변경은 변경요청과 새 버전·새 승인이 필요하다. |
| 계속 갱신형 Active | RAID·Backlog·변경이력·결함대장 | 최초본을 승인한 뒤 사건을 append/update한다. 특정 시점 snapshot은 별도로 고정한다. |
| 실행 증거형 Planned | 시험 결과·배포 기록·인수서 | 실제 실행과 원자료가 생길 때까지 `Planned/NOT_RUN`을 유지한다. 문장만 작성해 완료시키지 않는다. |

### 5.3 상태 뜻

```text
Planned → Draft → In Review → Approved/Baselined
                              ↓
                         Superseded → Archived
```

- `Planned`: 아직 실행하거나 작성할 근거가 없음
- `Draft`: 내용은 작성 중이지만 승인되지 않음
- `In Review`: 정해진 검토자가 내용과 근거를 확인 중
- `Approved/Baselined`: 지정 버전이 구현 기준으로 승인됨
- `Active`: 승인된 최초본을 계속 갱신하는 운영 상태
- `Superseded`: 새 버전으로 대체됐지만 이력 보존 중
- `Archived`: 더는 쓰지 않으며 보존만 함

### 5.4 개별 산출물을 찾는 입구

257개 각각의 목적·필수/조건부 기준·필수 내용·입력·선후관계·담당 역할·완료조건·갱신조건은 다음 자료에서 확인한다.

- 기계 판독 작성계획: `docs/control/artifact-types.json`
- 사람이 검색·필터링하는 전체 대장: `docs/deliverables/00-control/artifact-register.html`
- 분야별 문서 묶음과 위치: `docs/deliverables/README.md`
- 현재 수량·상태·버전·경로 정본: `docs/deliverables/00-control/artifact-register.json`

작업할 정책 ID를 RTM에서 요구사항으로 찾고, 해당 요구사항에 연결된 산출물 코드를 DOC-01에서 조회한 뒤 위 작성계획의 완료조건을 적용한다.

## 6. 산출물 기반 개발 흐름

모든 EPIC은 아래 흐름으로 처리한다.

```text
승인 정책
  → 요구사항·인수조건
  → 설계·인터페이스
  → 구현 작업과 코드
  → 단위·구성요소 검증
  → Active 원장·증거 갱신
  → Gap 새 revision
  → IMPLEMENTATION_READY
  → EPIC-12 정식 시험·gate
  → 출시 적격 판단
```

### 6.1 착수

1. 체크포인트의 필수 정본·현재 작업 snapshot 검사를 통과시킨다.
2. 수정 백로그에서 EPIC의 정책 ID, Gap ID, 의존성, 완료조건을 읽는다.
3. RTM에서 `RQ-*`, `AC-*`, `TC-*`를 찾는다.
4. 설계 추적표에서 관련 `DES-*`를 찾는다.
5. 코드·설정·문서의 현재 상태를 읽고 최소 변경 범위를 정한다.
6. 변경할 파일, 검증 방법, 아직 남길 gate를 작업 계획에 명시한다.

### 6.2 구현

- 승인 정책의 의미를 바꾸지 않는 범위에서 코드와 Draft/Active 자료를 수정한다.
- 사용자 앱, 관리자 앱, 서버, 모델, 설정의 경계를 명확히 유지한다.
- 지원하지 않는 조건은 조용히 성공 처리하지 않고 안전하게 제한 또는 중단한다.
- 정식 현장시험을 단위시험으로 대신하지 않는다.
- 관련 없는 과거 코드나 문서를 광범위하게 정리하지 않는다.

### 6.3 내부 완료 판정

EPIC-01~11의 목표는 `IMPLEMENTATION_READY`다. 이는 다음을 뜻한다.

- 정책에 맞는 코드·설정·인터페이스가 구현됨
- 관련 단위·구성요소 검사가 통과함
- 구현 파일과 결과의 버전·해시가 기록됨
- RTM·설계·모듈·시험계획 연결이 갱신됨
- 알려진 미해결 사항과 EPIC-12로 넘길 정식 검증이 명시됨

`IMPLEMENTATION_READY`는 정식 시험 PASS, 실폰·현장검증 완료, gate 종료 또는 출시 허용을 뜻하지 않는다.

### 6.4 정식 검증과 출시

EPIC-12에서 하나의 불변 출시 후보를 대상으로 279개 예정 시험 중 적용되는 시험을 실행하고, 원자료·로그·환경·APK/AAB·모델·설정 해시를 연결한다. 5개 gate는 지정된 실제 측정·독립검토·복구훈련으로만 닫을 수 있다. 그 뒤에만 TST-22와 REL-02에서 출시 적격을 판단한다.

정식 검증·Gate·출시 판단 Goal은 같은 후보 SHA-256에 결속된 다음 다섯 canonical role 중 유형별 계약이 요구하는 정확한 집합을 사용한다.

- `TEST_PLAN_APPROVAL_RECEIPT`: 시험 실행 전에 `PLANNED_TEST_CASES`의 `role/document_id/path/file_sha256/version/test_case_id_set_sha256` snapshot을 승인한다. `plan_approved_at`은 계획 metadata와 receipt approver 결정에 일치하고 receipt 생성 시각과 함께 `FORMAL_TEST_REPORT`, `ACTUAL_DEVICE_TEST_REPORT`, `RELEASE_GATE_CLOSURE_RECEIPT` 각 `execution_window.started_at` 이하여야 한다.
- `FORMAL_TEST_REPORT`: 계획 승인 receipt와 정확히 같은 snapshot 및 ID 집합의 authoritative 279개 TC 행. 허용 환경·필수 증거유형을 그대로 따르고 적용 시험은 PASS, 비적용은 같은 approver의 명시적 승인. 비적용 `decided_at`은 보고서 시작 이후이고 approver 결정·`generated_at` 이하다.
- `ACTUAL_DEVICE_TEST_REPORT`: 승인 생산 기기 프로필의 실제 기기·접근성·현장·장시간 원자료
- `RELEASE_GATE_CLOSURE_RECEIPT`: 5개 gate 개별 상태, `waived=false`, 측정·훈련·독립검토 원자료
- `RELEASE_ELIGIBILITY_APPROVAL`: 실제 권한자의 TST-22·REL-02 출시 적격 승인

유형별 completion evidence와 `verification_evidence_refs`의 정확한 canonical role 집합은 다음과 같다.

| Goal 유형 | 정확한 canonical role 집합 |
|---|---|
| `FORMAL_TEST_RUN` | `TEST_PLAN_APPROVAL_RECEIPT`, `FORMAL_TEST_REPORT`, `ACTUAL_DEVICE_TEST_REPORT` |
| `RELEASE_GATE` | `RELEASE_GATE_CLOSURE_RECEIPT` |
| `RELEASE_DECISION` | 위 다섯 role 전체 |
| EPIC-12 Workstream 완료 | 위 다섯 role 전체 |

각 Work Item의 마지막 완료 event는 해당 유형의 정확한 role 집합에 `WORK_ITEM_COMPLETION::<goal_id>`를 하나 추가한 정확한 합집합을 가져야 한다. 각 증거에는 서로 다른 실제 실행자·검토자·승인자의 ID·role·authority와 시간 순서, 원자료 path·SHA-256·record count·collector, candidate manifest와 구성요소 hash가 있어야 한다. 모든 raw evidence `collected_at`은 해당 receipt `execution_window` 안이어야 한다. 비용 gate는 KRW 저장·요청·복원·부가세 합계와 `vat_included=true`, 월 30,000원 이하를 검증한다. 내부 에이전트가 만든 JSON은 구조 검증 receipt일 뿐 실기기 실행, 독립검토, 훈련, 청구자료나 승인을 대신하지 않는다.

`DEPLOYMENT_DELIVERY_EVENT` 완료에는 `PHASE_C_TECHNICAL_DELIVERY_RECEIPT` canonical role이 필요하다. `PHASE_C`는 과거 스키마와 증거 호환을 위해 보존한 role 이름일 뿐 현재 최상위 단계가 아니다. 이 receipt는 출시 적격 receipt SHA-256 → 승인 candidate → 실제 deployed candidate가 같은 hash chain이고, 시간 순서가 `deployment.started_at ≤ canary.executed_at < smoke.executed_at ≤ deployment.ended_at ≤ technical_delivery.accepted_at`임을 증명한다. receipt 실행 시작은 해당 배포 Goal 시작 event 이후이고, 모든 시각과 raw evidence 수집은 receipt `execution_window` 안이며 배포 시작은 출시 적격 승인 뒤다. 8개 required check는 각각 `PASS`와 해당 receipt raw evidence refs를 가지고 `required_check_summary={total:8, passed:8, failed:0, not_run:0}`이어야 한다.

`HANDOVER_CLOSURE_EVENT`는 완료된 배포 Goal과 그 배포를 선행으로 한 완료 `OPERATION_EVENT`를 모두 `start_requires`에 둔다. 완료에는 `PHASE_D_OPERATION_HANDOVER_RECEIPT`와 `PHASE_D_PROJECT_CLOSURE_RECEIPT` canonical role이 모두 필요하다. `PHASE_D`도 과거 스키마와 증거 호환을 위해 보존한 role 이름이다. hash chain은 배포 receipt → 실제 운영 사건 → 같은 deployed candidate의 운영 이관 → 그 이관 receipt를 참조한 프로젝트 종료 순서다. 두 receipt 실행 시작은 해당 이관·종료 Goal 시작 event 이후다. 사건 순서는 `deployment acceptance → operation recorded → handover start → 모든 handover checks와 transferor/transferee decisions → handover acceptance → closure start → 모든 closure checks와 project/accepting owner decisions → closed`다. handover 시작은 운영 사건 완료 이상이고 모든 handover 활동은 시작부터 수락 사이여야 한다. closure 시작은 handover 수락 이상이고 모든 closure 활동은 시작부터 종료 사이며 closure receipt 생성은 handover receipt 생성보다 이르지 않아야 한다. handover/closure summary는 각각 `{total:6, passed:6, failed:0, not_run:0}`, `{total:7, passed:7, failed:0, not_run:0}`이고 각 check는 실제 raw evidence refs를 가진다. 기술자료 전달은 운영책임 이관을 대신하지 않는다.

정식 검증·출시 결정·배포·이관·종료와 사용자·정책·외부 결정에서 실제 행위자 권한이 서명되었거나 외부 시스템에 anchor된 증거가 없으면 JSON 구조가 맞아도 외부 실제성을 확정하지 않는다. `AUTHORITY_ROSTER` 일치만으로 충분하지 않으며, 위 required role과 동적 `REOPEN_TRIGGER::<target_goal_id>`, `BLOCKER_RESOLUTION::<blocker_id>`의 정확한 receipt 파일 SHA-256을 버전 관리되는 checker의 `EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE`에 role별로 고정해야 한다. receipt는 사용 당시 roster ID·SHA-256을 포함하고 roster는 제자리 갱신하지 않는다. 실제 receipt가 아직 없어 map이 비어 있는 것은 정상적인 fail-closed 상태다. 실제 승인된 receipt만 별도 검토한 checker 변경으로 추가하며 임시·예상 hash는 금지한다. 그 전 상태는 `PARTIAL/AWAITING_EXTERNAL`, 출시는 `NOT_ELIGIBLE`로 유지한다. 일정과 담당자만 승인한 항목도 실제 수행 뒤에만 완료된다.

## 7. 다음 작업 선택 규칙

Backlog에는 다음 우선순위가 기록되어 있다.

```text
EPIC-01 → EPIC-02 → EPIC-03 → EPIC-04 → EPIC-05 → EPIC-06
        → EPIC-07 → EPIC-08 → EPIC-10 → EPIC-09 → EPIC-11 → EPIC-12
```

이 순서는 고정 실행 단계나 의존관계가 아니다. 체크포인트의 `execution_order_semantics=PRIORITY_TIE_BREAKER_ONLY_NOT_DEPENDENCY`도 이를 기계 판독 가능하게 고정한다. `start_requires`가 충족된 ready Goal끼리 우선순위가 같은 때만 동률 해소에 사용한다. 현재 ready frontier는 FP-005 Work Item, EPIC-03, EPIC-12이며 canonical `next_single_action` 때문에 FP-005가 focus다.

선택·재작업 규칙은 다음과 같다.

1. `IN_PROGRESS`인 leaf가 있으면 먼저 재개한다.
2. 없으면 ready frontier를 다시 계산하고 canonical `next_single_action`과 일치하는 leaf를 고른다.
3. 그다음 P0 위험도, 후행 해제 효과, Backlog 우선순위, `priority_rank`, Goal ID 순으로 동률을 해소한다.
4. 한 branch가 막혀도 독립 ready branch는 계속 진행한다.
5. 새 정책 결정이 필요한 문제가 아니면 기존 사용자 결정을 다시 묻지 않는다.
6. 정책과 구현이 충돌하면 구현을 고친다. 정책을 임의로 완화하지 않는다.
7. 정책 자체를 바꿔야 할 때만 변경요청·영향분석·사용자 재승인을 거친다.
8. Workstream·부모 Goal·Work Item 유형/ID·목표수준·정책·Gap·materialization 문서 ID/SHA-256으로 action identity를 계산한다.
9. 동일 identity가 `READY/IN_PROGRESS/AWAITING_*`이면 기존 Goal을 재개하고 새 Goal을 만들지 않는다. 이미 `COMPLETE_AT_TARGET`이면 다음 행동으로 이동한다.
10. 완료 행동의 결함·증거 반려·지시 교정은 Gap·Backlog successor에 먼저 기록한다. 같은 패키지에서는 그 `CANONICAL_BINDINGS_UPDATED` 영향 transaction이 역의존 Work Item successor와 Workstream reopen을 원자적으로 결속할 때만 같은 `work_item_id`의 `R002+`로 다시 열고, standalone receipt만으로는 열지 않는다. 정적 구조 변경이나 종결 뒤 변경은 versioned successor package로 처리한다.
11. delayed historical reopen successor의 `predecessor_goal_id`는 event 당시 현재 실행 leaf, `supersedes_goal_id`는 과거 완료 revision이다. trigger `target_completion_event_sha256`은 과거 target의 실제 `GOAL_COMPLETED` event와 같고 `target completion occurred_at ≤ decided_at ≤ GOAL_SUPERSEDED occurred_at`이어야 한다. `supersedes_goal_id`가 있는 successor의 최초 도입 event는 반드시 `GOAL_SUPERSEDED`다. 이 event는 현재 leaf를 유지하고 named target 하나만 `SUPERSEDED`, 새 successor 하나만 `PLANNED`로 바꾸며 과거 completion refs를 `archived_completion_evidence_by_goal`로 보존한다. 다른 event로 미리 도입하지 않는다. 새 revision은 의존성이 충족된 뒤 별도 `GOAL_READY`, 이어 별도 `GOAL_STARTED` 사건을 거친 뒤에만 실행한다. 단순 미실행 외부 증거나 일정 변경은 reopen 사유가 아니다.
12. 정책/Gap pair는 배열 zip으로 만들지 않는다. canonical `IMPLEMENTATION_GAP.assessments`의 유일한 `source_policy_id → gap_id` mapping을 사용한다. 정적 `initial_status=COMPLETE_AT_TARGET` legacy EPIC 외에는 대체되지 않은 최종 Work Item들이 EPIC 소유 정책의 canonical pair를 각각 정확히 한 번 담당하고 typed completion receipt로 모두 완료된 뒤에만 EPIC을 완료한다. sibling·누락·중복·bulk 완료를 금지한다.
13. Work Item 목표는 유형별 exact enum만 사용한다. `POLICY_GAP_WORK`는 `INTERNAL_POLICY_CONFORMANCE_REASSESSED` 또는 `IMPLEMENTATION_READY`, `ARTIFACT_WORK`는 `ARTIFACT_TARGET_STATE_REACHED`, `INTEGRATION_CANDIDATE`는 `IMMUTABLE_CANDIDATE_BOUND`, `FORMAL_TEST_RUN`은 `FORMAL_VERIFICATION_ACTION_COMPLETE`, `RELEASE_GATE`는 `RELEASE_GATE_CLOSED`, `RELEASE_DECISION`은 `RELEASE_ELIGIBILITY_DECIDED`, `DEPLOYMENT_DELIVERY_EVENT`는 `RELEASE_DELIVERY_ACTION_COMPLETE`, `OPERATION_EVENT`는 `OPERATION_EVENT_RECORDED`, `HANDOVER_CLOSURE_EVENT`는 `HANDOVER_CLOSURE_ACTION_COMPLETE`, `BLOCKER_OR_EXTERNAL_RECEIPT`는 `EXTERNAL_CONDITION_RESOLVED`를 쓴다. successor revision은 predecessor의 유형과 목표를 유지한다.

## 8. EPIC-01 완료 경계와 현재 작업: EPIC-02 FP-005

EPIC-01의 목표는 Android 제품 경계를 확정하고 과거 Web/PWA 주제품 전제를 현재 실행·출시 경로에서 제거하는 것이었다. Phase G 내부 검증까지 끝나 `IMPLEMENTATION_READY`가 됐지만, 이는 정식 시험 PASS·출시 허용을 뜻하지 않는다. 구현 백로그의 EPIC-02는 `IN_PROGRESS`이고 FP-017, FP-018, NPC 권한 세션, FP-004의 내부 Work Item이 완료됐다. 다음 작업은 같은 EPIC의 FP-005 공식 지원 환경·횡단보도 참고 경계다.

| 정책 | 구현해야 할 핵심 | 이번 EPIC에서 남기는 경계 |
|---|---|---|
| FP-003 | 단일 최종관리자, 추가 본인확인/복구/세션폐기/고위험 동결 계약 | 실제 분실 복구훈련은 EPIC-12 gate로 유지 |
| FP-002 | 시연·제한시험·공개출시 단계와 승급 차단 | 실제 공개승인은 하지 않음 |
| FP-007 | Android 사용자 앱과 관리자 앱의 ID·서명·세션·배포 경계 분리 | 관리자 업무는 FP-003을 담당하는 EPIC-01에서 인증·복구까지 구현한 뒤 개방 |
| FP-009 | 시작 전 기기능력 확인, 전체/제한/사용불가 분류, Web 외부 실행 차단 | `FULL`은 실제 미터 거리 안정성을 확인하기 전 차단하고, 실기기 지원표 확정은 정식 시험으로 유지 |
| FP-001 | 위험안내·TMAP 큰 방향·손상 점자블록 신고 목적과 비대체 고지 | 접근성 전체 검증은 후속 EPIC/정식 시험 |

현재 Web 소스는 `LEGACY_REFERENCE_ONLY`로 분류해 회귀·역사 참고용으로만 보존한다. 저장소가 공식 제공하는 Legacy UI, Web release, 배포 설정, 공개 launcher는 기술적으로 닫혔고 Legacy Web의 모든 요청은 예외 없이 410으로 종료된다. Android가 사용하는 다음 4개 API는 독립 Node Gateway `127.0.0.1:8081`로 이동했고 같은 Next route 파일은 제거됐다.

- `/api/field-session`
- `/api/navigation/walking`
- `/api/navigation/destinations/search`
- `/api/reports/v2`

독립 Gateway는 저장소 내부에서만 검증됐고 배포 예시는 아직 적용하지 않았다. 실제 휴대전화 연결, 외부 HTTPS 주소, 과거 외부 URL·DNS·Cloudflare 자원의 실제 폐기, 이미 설치됐거나 캐시된 PWA 비활성은 확인하지 않았다. 임의 명령으로 과거 Next 코드를 실행하는 행위까지 운영체제 수준에서 불가능하게 만들었다고도 주장하지 않는다.

### 8.1 2026-07-24 현재 진행점

Phase A에서는 사용자·관리자 Android 앱의 식별 경계, 잠긴 관리자 앱, CI Web release·공개 터널 실행기 차단, 보수적인 시작 전 기기능력 판정, 공식 목적문·안전 한계·제한 음성 고지와 실제 음성 기능 실패 시 안전중지를 구현하고 내부 빌드·회귀검사를 통과했다. 제한 고지는 TTS 완료 또는 TalkBack 전달 뒤에만 확인되고, 구조적 STT/TTS 실패 뒤에는 경로·센서를 멈춰 앱 재개나 권한 callback으로 우회할 수 없다. ARCore 선언만으로 `FULL`을 허용하지 않으며 안정적 미터 거리와 승인된 지정 기기 프로필·프로필 버전이 모두 생길 때까지 차단한다. 상세 근거는 `docs/control/execution/walksafe-epic-01-phase-a-implementation-record-20260722.md`와 같은 이름의 JSON에 있다.

Phase B에서는 관리자 앱과 서버에 `PASSWORD_TOTP`, 서버 권한 세션·기기 결속, 원격 세션 폐기, 최근 재확인, 복구 진행 중 고위험 작업 동결, 성공 때만 복구코드 소비, 동일 코드·동일 기기의 응답유실 재개를 내부 구현했다. 관리자 보안 제어 화면만 열렸고 보고서 변경·권한 변경·자료 삭제·출시승인 같은 운영 업무는 계속 닫혀 있다. API의 고위험 작업은 같은 DB transaction에서 재확인하고, 오프라인 삭제 도구는 DB 연결이 정상 유지되는 동안 control lock과 감사 결속을 유지한다. 상세 근거는 `docs/control/execution/walksafe-epic-01-phase-b-admin-auth-recovery-implementation-record-20260722.md`와 같은 이름의 JSON에 있다.

Phase C에서는 별도 ARCore session의 안정적인 실제 미터 거리 frame 검사, 새 runtime session 재검사, 근거 손실 시 출력 차단, 승인 지정 기기 프로필의 정확 일치·버전 검사를 내부 구현했다. 사용자 Android 앱 JVM 시험 376개와 debug assemble·lint, Phase C 추적 검사는 통과했다. 이는 내부 검증이며 운영 승인 프로필은 0개, 실제 지정 휴대전화 실행과 279개 정식 시험은 `NOT_RUN`이다. 상세 근거는 `docs/control/execution/walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.md`와 같은 이름의 JSON에 있다.

Phase D에서는 저장소가 공식 제공하는 Legacy UI·Web release·배포·공개 launcher 경로를 닫고, 모든 Legacy UI 요청이 410으로 종료되는 경계를 내부 검증했다. Android가 의존하는 정확히 4개 BFF 경로만 loopback 임시 예외로 남겼다. 상세 근거는 `docs/control/execution/walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.md`와 같은 이름의 JSON에 있다. 이 결과는 임의 수동 Next 실행 차단, 과거 외부 URL 폐기, 기존 캐시 PWA 비활성, BFF 추출 완료 또는 정식 시험 완료를 뜻하지 않는다.

Phase E에서는 정확히 4개 API를 Next에서 독립 Node Gateway로 옮기고 Android debug endpoint를 `127.0.0.1:8081`로 전환했다. Gateway는 단일 프로세스·12시간 actor-bound 세션·요청 크기와 기한 제한·내부 Backend 자격증명 은닉 계약을 가지며, 다른 경로는 404로 닫힌다. 기존 4개 Next route는 제거됐고 Legacy Web 허용목록은 0개다. Gateway·Web·Android 내부 회귀와 추적 검사는 통과했지만 배포와 실제 기기 연결은 `NOT_RUN`이다. 상세 근거는 `docs/control/execution/walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.md`와 같은 이름의 JSON에 있다.

Phase F에서는 승인된 제품 목적문과 안전 한계를 Android 첫 화면·신고 동의·사용자 설명서 초안·미게시 릴리스 설명 후보에 같은 뜻으로 배치하고, 사용자 노출 신고 문구를 손상 점자블록 전용으로 명확히 했다. 180일 보존·세션 동의·철회·전송 로직과 권한은 바꾸지 않았고 사용자 앱 JVM 379개·debug assemble·lint 및 Phase F 추적 검사를 통과했다. `REL-17`은 `DRAFT/NOT_APPROVED`, `REL-09`는 `PLANNED/NOT_RUN`이며 정식 사용자 문서나 게시 릴리스 노트로 승격하지 않았다. 상세 근거는 `docs/control/execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.md`와 같은 이름의 JSON에 있다.

Phase G에서는 목적지·활성 경로가 없어도 가까운 일반 위험안내가 작동하도록 CameraX 제한 경고와 ARCore 일반 위험판단에서 TMAP 경로 의존 조건을 제거했다. 경로가 필요한 점자블록 방향안내의 fail-closed 조건은 유지했고, 목적지 없는 CameraX 실행 중 화면 유지, 경로 중립 문구, 현장 로그·외부 증거 계약을 함께 맞췄다. 사용자 앱 JVM `382/382`, debug assemble·lint와 Python evidence `249/249`가 통과했지만 이는 내부 검증이며 실제 기기·279개 정식 시험 증거가 아니다. 상세 근거는 `docs/control/execution/walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.md`와 같은 이름의 JSON에 있다.

EPIC-02 Phase A에서는 `READY → ACTIVE → PAUSED/SAFE_STOP → ENDED` 상태와 재검사·재개확인 단계를 분리했다. 앱이 보이지 않으면 상태를 먼저 멈추고 카메라·음성·길안내·신고 runtime을 정지한다. 돌아오면 준비상태를 다시 확인하고 “시작”이라는 정확한 사용자 확인 전에는 재개하지 않는다. 이전 보행 자동복원, 일시정지 중 걸음 누적과 늦게 도착한 센서·카메라·모델 결과의 재활성도 차단했다. 가입·통합동의·일반 원본수집 동의, 인증된 Gateway 로그인 readiness, 필수 서버와 저장공간·배터리·발열 aggregate는 아직 전체 시작조건으로 완결되지 않았다. 사용자 앱 JVM `407/407`, debug assemble, lint 경고 31개·오류 0과 Phase A 추적검사 13개가 통과했다. 이는 내부 검증이며 실제 잠금·홈·통화·앱 전환, 279개 정식 시험과 출시는 `NOT_RUN`이다.

FP-018에서는 같은 시도 안에서 권한·센서·경로·음성·모델 readiness를 다시 확인하고, 늦게 도착한 비동기 결과가 멈춘 보행을 재활성화하지 못하게 했다. NPC 권한 세션에서는 운영체제 권한, 로그인, 원본 동의, 자동신고 동의, 망 선택을 독립 상태로 분리하고 기능별 중지, 암호화 로그인 세션, 앱 밖 권리요청 표면을 구현했다. 실제 기기·정식 시험·외부 권리요청 운영은 `NOT_RUN`이다.

FP-004에서는 전맹·저시력 사용자를 같은 우선순위로 둔 접근 가능한 최초 교육과 위험 안내·일시정지·재개·안전정지의 직접 연습을 구현하고, 연령·보호자 확인·필수 음성·진동 조건을 보행 시작 fail-closed에 연결했다. 우선 사용자·실제 기기·보호자 확인 공급자·정식 시험은 `NOT_RUN`이다.

`EPIC-01 제품 경계와 Web 앱 오염 제거`는 `IMPLEMENTATION_READY`다. `EPIC-02 안전한 보행 상태와 권한`은 구현 수정 백로그에서 `IN_PROGRESS`이고, Goal graph v2.2 Workstream `WS-GOAL-EPIC-02`는 `READY`, focus Work Item `WS-GOAL-EPIC-02-FP-005-R001`도 `READY`다. Workstream 자체를 `IN_PROGRESS`로 전환하지 않는다. 다음 단일 작업은 `EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK`이며, 정확한 행동은 다음과 같다.

> 밝고 건조한 일반 도심 보도 범위, 측정 가능한 환경 품질, 확인 불가 조건의 보수적 제한과 횡단보도 참고 고지를 Android 사전점검·안전정지에 연결한다.

운영 관리자·TOTP·복구코드 프로비저닝, 휴대전화 밖 암호화 보관, 실제 서명·비공개 배포·등록 실기기, 장시간 오프라인 삭제 fencing·durable journal, 보존삭제 자격증명의 일회성 인계, DB migration/runtime/retention role 분리는 미결이다. Gateway 실제 배포·실기기 연결, 실제 복구훈련과 나머지 5개 gate도 완료 처리하지 않는다.

### 8.2 재개 시 반드시 유지할 미결 ID

체크포인트는 다음 항목을 해결 근거 없이 제거하면 안 된다.

- 관리자 운영 미결: `ADMIN-PRODUCTION-PROVISIONING`, `ADMIN-OFF-PHONE-ENCRYPTED-CUSTODY`, `ADMIN-SIGNING-DISTRIBUTION-DEVICE`, `ADMIN-RETENTION-CREDENTIAL-HANDOFF`
- 관리자 보안 미결: `ADMIN-OFFLINE-DELETE-FENCING`, `ADMIN-OFFLINE-DELETE-DURABLE-JOURNAL`, `ADMIN-DB-ROLE-SEPARATION`
- Phase C 증거 미결: `PHASE-C-PRODUCTION-PROFILE-EMPTY`=`OPEN`, `PHASE-C-ACTUAL-DEVICE-NOT-RUN`=`NOT_RUN`, `PHASE-C-FORMAL-TESTS-NOT-RUN`=`NOT_RUN`
- Phase D 한계·미실행: `PHASE-D-ARBITRARY-MANUAL-NEXT-BYPASS`=`LIMITATION_OPEN`, `PHASE-D-HISTORICAL-EXTERNAL-URL-DECOMMISSION`=`NOT_RUN`, `PHASE-D-CACHED-PWA-DEACTIVATION`=`NOT_RUN`
- Phase E 미실행: `PHASE-E-GATEWAY-DEPLOYMENT-NOT-RUN`=`NOT_RUN`, `PHASE-E-ACTUAL-DEVICE-CONNECTIVITY-NOT-RUN`=`NOT_RUN`
- FP-017 미실행 검증: `EPIC-02-FP017-ACTUAL-DEVICE-LIFECYCLE`=`NOT_RUN`, `EPIC-02-FP017-FORMAL`=`NOT_RUN`
- FP-018 미실행 검증: `EPIC-02-FP018-ACTUAL-DEVICE-LIFECYCLE`=`NOT_RUN`, `EPIC-02-FP018-FORMAL`=`NOT_RUN`
- NPC 권한 세션 미실행 검증: `EPIC-02-NPC-ACTUAL-DEVICE-LIFECYCLE`=`NOT_RUN`, `EPIC-02-NPC-FORMAL`=`NOT_RUN`, `EPIC-02-NPC-EXTERNAL-RIGHTS-OPERATION`=`NOT_RUN`
- FP-004 미실행 검증: `EPIC-02-FP004-FORMAL`=`NOT_RUN`, `EPIC-02-FP004-PRIORITY-USER-TEST`=`NOT_RUN`, `EPIC-02-FP004-ACTUAL-DEVICE`=`NOT_RUN`, `EPIC-02-FP004-GUARDIAN-VERIFICATION-PROVIDER`=`NOT_RUN`
- 다음 구현 작업: `EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK`
- 후속 범위: `EPIC-11-RELEASE-SIGNING-DISTRIBUTION`, `EPIC-12-DEVICE-AND-FORMAL-VERIFICATION`
- 출시 gate: 5개 모두 `NOT_RUN`·미면제

## 9. 문서와 기준선 갱신 규칙

### 9.1 직접 고치지 않는 파일

- 승인된 정책 기준선 manifest와 승인 기록
- COMMITTED receipt와 불변 r001 감사자료
- 과거 사용자 답변 원본과 intake 기록
- 생성된 DOC-01·DOC-05·RTM·Gap JSON/HTML

생성물 변경이 필요하면 source 또는 전용 builder를 고치고 새 revision을 생성한다. 이미 승인된 기준선 내용이 바뀌면 변경요청과 새 승인이 필요하다.

### 9.2 구현 중 갱신 대상

구현 범위에 따라 다음 Active 자료를 append/update한다.

- DOC-01 산출물 상태와 위치
- DOC-05 변경 사건
- REQ-16 RTM, REQ-18 요구변경이력
- DES-06 ADR과 설계 추적 원장
- DEV-15 코드리뷰, DEV-18 모듈 목록
- TST-03 환경·지원기기, TST-05 시험케이스
- TST-18 결함, TST-19 품질지표, TST-21 잔여위험
- DSC-14 또는 새 revision의 실행 백로그

단, 생성 스크립트가 정본인 파일을 손으로 편집하지 않는다. 새 revision을 만드는 전용 스크립트나 append-only 실행 기록을 사용한다.

### 9.3 구현이 바뀐 뒤 Gap 처리

- r001은 구현 시작 전 commit의 진단 기록으로 보존한다.
- r002는 EPIC-01 Phase B 내부 구현 직후의 통제 경로 진단 기록으로 보존한다.
- r003은 EPIC-01 Phase C runtime metric preflight 내부 구현 직후의 11개 Android 경로 진단 기록으로 보존한다.
- r004는 EPIC-01 Phase D Legacy Web 저장소 경로 기술 폐쇄 직후의 진단 기록으로 보존한다.
- r005는 EPIC-01 Phase E 독립 Android Gateway 추출 직후의 41개 통제 경로와 4개 제거 경로 진단 기록으로 보존한다.
- r006은 EPIC-01 Phase F 제품 목적·안전 한계 사용자 표면 정합화 직후의 9개 제품 표면 경로 진단 기록으로 보존한다.
- r007은 EPIC-01 Phase G 목적지 없는 위험안내 정합화 직후의 구현·증거 계약 진단 기록으로 보존한다.
- r008은 EPIC-02 Phase A FP-017 보행 세션 생명주기 내부 slice 직후의 13개 Android 경로 진단 기록이자 FP-018 bootstrap predecessor로 보존한다. 현재 제품 bytes의 live currentness로 재생성하거나 해석하지 않는다.
- r009는 FP-018 보행 상태 복구 내부 구현 직후의 successor이며 `GAP-027=PARTIAL`과 다음 `NPC-PERMISSION-SESSION-LIFECYCLE/GAP-006`을 고정한다.
- r010은 NPC 권한 세션 내부 구현 직후의 successor이며 `GAP-006=PARTIAL`과 다음 `FP-004/GAP-013`을 고정한다.
- r011은 FP-004 우선 사용자·필수 사전연습 내부 구현 직후의 successor이며 `GAP-013=PARTIAL`과 다음 `FP-005/GAP-014`를 고정한다.
- 새 구현 commit 또는 통제 snapshot을 고정한다.
- 다음 구현 뒤에는 영향받은 정책/gate를 다시 평가한 r012 이상 successor를 만든다.
- 코드만 존재하고 시험 근거가 없으면 `IMPLEMENTED`로 과장하지 않는다.
- EPIC 상태 변경의 근거 경로와 해시를 백로그 새 revision에 연결한다.

### 9.4 생성물·Active 원장·체크포인트를 갱신하는 방법

날짜가 붙은 기존 builder와 materializer는 당시 승인 세계를 재현하는 도구다. 현재 상태를 바꾸기 위해 그대로 실행하지 않는다.

| 대상 | 현재 정본/생성 출처 | 다음 변경 방법 |
|---|---|---|
| 257개 유형·초기 DOC-01/05 | `artifact-types.json`, `build_walksafe_control_bootstrap.py` | 과거 구조 확인에만 사용. 현재 Active 사건은 새 날짜의 append/update builder와 회귀검사를 만든 뒤 DOC-01·05 새 snapshot으로 반영 |
| 승인 상태 102/27/53/75 | `materialize_walksafe_artifact_baseline_approval_20260722.py`와 COMMITTED receipt | `--check`만 허용. 승인 상태 변경은 변경요청·새 승인 패키지·새 materializer revision으로 처리 |
| RTM·설계·시험계획 | `build_walksafe_requirements_draft_20260721.py`, `build_walksafe_design_deliverables_20260721.py`, `build_walksafe_formal_dev_test_20260721.py` | 과거 r001을 덮지 않고 EPIC 변경분을 입력으로 받는 새 revision builder와 역추적 검사를 작성 |
| Gap·수정 백로그 | r001 `build_walksafe_implementation_gap_analysis_20260722.py`, r002~r007 EPIC-01 단계별 builder, r008 `build_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py`, r009 `build_walksafe_fp018_walk_state_recovery_trace_20260724.py`, r010 `build_walksafe_npc_permission_session_trace_20260724.py`, r011 `build_walksafe_fp004_priority_user_trace_20260724.py` | r001~r011 출력은 append-only snapshot으로 보존한다. continuation은 r008~r010 역사 경계와 현재 r011 successor를 검증한다. 다음 구현 snapshot을 고정한 뒤 r012 이상 successor builder·보고서·백로그 생성 |
| EPIC 실행 근거 | `docs/control/execution/` | 정책·요구·Gap·파일·검증·미결을 담은 날짜/단계별 append-only JSON·Markdown 기록 추가 |

Phase B 생성기는 Phase C가, Phase C와 Phase E 생성기는 Phase F가, Phase F 생성기는 Phase G가, Phase G 생성기는 EPIC-02 Phase A가 같은 통제 경로를 바꾼 뒤 live 재생성 결과가 의도적으로 달라진다. Phase D 생성기도 Phase E 이후 `EXPECTED_STALE`이며 FP-018부터는 EPIC-02 Phase A도 같은 역사 경계다. 이 생성물들을 다시 만들거나 덮어쓰지 않는다. continuation이 Phase G와 EPIC-02 Phase A 각각의 builder·test·고정 출력 predecessor 지문을 검증해 보존 여부를 확인한다.

현재는 범용 Active 원장 writer가 아직 없다. 따라서 손으로 생성 JSON을 고치지 말고, 각 EPIC에서 필요한 최소 새 revision builder와 변조 거부 회귀검사를 함께 만드는 것을 완료조건으로 둔다.

세션 종료 때 통제 경로의 모든 파일과 daylog를 먼저 확정한 뒤 아래 명령으로 체크포인트의 최종 작업 snapshot 값을 계산한다.

이 명령은 checkpoint에 이미 적힌 경로 집합을 계산할 뿐 새 파일을 자동 발견하지 않는다. 합법적인 구현 파일·시험·문서의 추가나 삭제가 있으면 먼저 `scripts/check_walksafe_project_continuation_v2_3.py`의 exact controlled-path contract, `working_tree_snapshot.managed_changed_paths`, `session_handoff.changed_files`를 같은 정렬 집합으로 맞추고 count·path hash·content hash를 함께 갱신한다. 기존 경로 bytes만 바뀐 경우에는 집합을 유지하고 content hash와 mirrored handoff 값만 갱신한다.

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_3.py --print-working-snapshot-hashes
```

출력값은 다음 필드에 같은 값으로 반영한다.

- `file_count` → `working_tree_snapshot.managed_changed_path_count`, `session_handoff.source_commit_or_snapshot.file_count`
- `path_set_sha256` → `working_tree_snapshot.path_set_sha256`, `session_handoff.source_commit_or_snapshot.path_set_sha256`
- `content_set_sha256` → `working_tree_snapshot.content_set_sha256`, `session_handoff.source_commit_or_snapshot.content_set_sha256`
- `base_commit`, `current_head` → `session_handoff.source_commit_or_snapshot`의 같은 이름 필드. `working_tree_snapshot.base_head`는 승인 작업의 기준 commit을 유지한다.

r008과 EPIC-02 Phase A는 frozen bootstrap predecessor다. 아래 4개 JSON의 `sha256sum`은 checkpoint의 기존 `canonical_bindings[].file_sha256`과 불변 지문이 같은지 확인할 때만 사용한다. 불일치를 현재 값으로 덮어 맞추거나 Phase A builder를 다시 실행하지 않는다. 불일치하면 손상을 조사해 원본을 복구하고, 합법적인 다음 상태는 r009 이상 successor와 `CANONICAL_BINDINGS_UPDATED` transaction으로 만든다. r001~r008과 Phase G·Phase A predecessor 기존 해시는 바꾸지 않는다.

```bash
sha256sum \
  docs/control/audits/walksafe-implementation-gap-analysis-20260723-r008.json \
  docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r008.json \
  docs/control/execution/walksafe-epic-02-phase-a-walk-session-lifecycle-implementation-record-20260723.json \
  docs/control/execution/walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.json
```

네 해시가 기존 binding과 일치하는지 확인한 뒤 `python3 -B scripts/check_walksafe_project_continuation_v2_3.py`를 다시 실행해 통과해야 한다. 체크포인트 자신은 순환 해시를 막기 위해 통제 경로에서 제외한다.

## 10. 읽기 전용 재시작 검사

### 10.1 제품 코드·기능 구현 전에 항상 통과해야 하는 전체 검사

저장소 루트에서 다음을 실행한다. §2의 두 검사는 빠른 재개 검사이고, 아래 전체 블록이 실제 구현 시작 gate다. 현재 EPIC 구현이 진행 중이어도 이 블록은 통과해야 한다. 검사 명령 계약 버전은 `2026-07-24.3`이고 quick 계약 SHA-256은 `1608a4d72bfb775313abab3b2e414f14994b573e7f8c631f8500ecd4af7a639c`다. full 계약 순서는 `CONTINUATION → GOAL_GRAPH → BASELINE_MATERIALIZATION → ANDROID_GATEWAY_BOUNDARY → NODE_TOOLCHAIN_PRE → GATEWAY_TYPECHECK → GATEWAY_TEST → GATEWAY_BUILD → WEB_TEST → WEB_LINT → WEB_TYPECHECK → WEB_BUILD → NODE_TOOLCHAIN_POST → ANDROID_UNIT_ASSEMBLE_LINT → TEST_LAYER_REGISTRY_VALIDATE → FIELD_AND_RELEASE_PYTEST → GOAL_CONTROL_PYTEST → CONTROL_AND_TRACE_PYTEST → REPOSITORY_STATE`, SHA-256은 `a8a7fc6e0bd88585a73684b46ca3e9ca1ddabadfc2ae7bd293f28d5839bcbd4c`다. 실행 전에 `WALKSAFE_NODE_BIN_DIR`과 이번 gate의 add-only event ID인 `WALKSAFE_GATE_EVENT_ID`를 지정한다. 아래 19개 명령은 checker가 고정한 exact 순서이며 임의 진단 명령을 이 계약 안에 끼워 넣지 않는다.

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_3.py
python3 -B scripts/check_walksafe_goal_graph_v2_3.py
python3 -B scripts/materialize_walksafe_artifact_baseline_approval_20260722.py --check
python3 -B scripts/check_walksafe_android_gateway_boundary_20260723.py --root .
: "${WALKSAFE_NODE_BIN_DIR:?required}" && WALKSAFE_NODE_ROOT="$(/usr/bin/dirname -- "${WALKSAFE_NODE_BIN_DIR}")" && test "${WALKSAFE_NODE_BIN_DIR}" = "${WALKSAFE_NODE_ROOT}/bin" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root "${WALKSAFE_NODE_ROOT}" --lock configs/walksafe_node_toolchain_lock_20260715.json
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run typecheck
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway test
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run build
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web test
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run lint
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run typecheck
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run build
: "${WALKSAFE_NODE_BIN_DIR:?required}" && WALKSAFE_NODE_ROOT="$(/usr/bin/dirname -- "${WALKSAFE_NODE_BIN_DIR}")" && test "${WALKSAFE_NODE_BIN_DIR}" = "${WALKSAFE_NODE_ROOT}/bin" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root "${WALKSAFE_NODE_ROOT}" --lock configs/walksafe_node_toolchain_lock_20260715.json
(cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon)
WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && PYTHON_BIN="${WALKSAFE_LOCKED_TEST_PYTHON}" bash scripts/run_walksafe_test_layers_20260711.sh validate
WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_android_field_session_summary.py tests/test_release_evidence_gate.py -q
WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_walksafe_goal_graph_v2_3.py tests/test_walksafe_project_continuation_v2_3.py tests/test_walksafe_goal_graph_v2_2_history.py -q
WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_walksafe_epic01_phase_b_trace_20260722.py tests/test_walksafe_epic01_phase_c_trace_20260722.py tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py tests/test_walksafe_epic02_trace_v2_2_history.py tests/test_walksafe_android_gateway_boundary_20260723.py tests/test_walksafe_artifact_baseline_materialization_20260722.py --deselect=tests/test_walksafe_epic01_phase_b_trace_20260722.py::WalkSafeEpic01PhaseBTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_c_trace_20260722.py::WalkSafeEpic01PhaseCTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py::WalkSafeEpic01PhaseETraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py::WalkSafeEpic01PhaseFTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py::WalkSafeEpic01PhaseGTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py::WalkSafeEpic02PhaseATraceTest::test_generated_files_are_current_and_deterministic -q
: "${WALKSAFE_GATE_EVENT_ID:?required}" && python3 -B scripts/check_walksafe_project_continuation_v2_3.py --root . --checkpoint docs/control/walksafe-project-continuation-checkpoint.json --print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"
```

이 블록은 소스·통제문서나 승인 상태를 바꾸지 않는다. npm·Gradle은 각 앱의 로컬 빌드 산출물을 만들 수 있다. Gateway·Android·Web의 현재 결과는 각 event 전용 원출력에 기록하고 exit code 0을 검증한다. r008의 Gateway 계약시험 14개, 사용자 앱 JVM `407/407`, lint 경고 31개·오류 0은 EPIC-02 Phase A 당시 역사 결과이지 이후 구현의 고정 기대 개수가 아니다. 통합 Python 명령은 B·C·E·F·G와 EPIC-02 Phase A의 과거 생성물 currentness 6건을 deselect하고, Phase A 당시의 기록·overlay·생성기·시험·고정 출력 SHA-256을 역사 증거로 검증한다. 이후 FP-018 구현 bytes를 과거 Phase A source SHA와 비교하지 않는다. `WALKSAFE_NODE_BIN_DIR`은 `configs/walksafe_node_toolchain_lock_20260715.json`과 일치하는 공식 Node 22.23.1 배포본의 실제 `bin` 절대경로여야 한다. 모든 npm 명령은 이 경로에 직접 결속하며 실행 전·후 전체 Node/npm closure 검증을 통과해야 한다. 시스템 기본 `node`·`npm`으로 대신 실행하면 안 된다.

19번째 `REPOSITORY_STATE`는 앞선 18개 검사 뒤 같은 `WALKSAFE_GATE_EVENT_ID`로 Git-visible dirty state와 checkpoint controlled snapshot을 다시 캡처한다. 그 exact canonical 출력은 같은 event의 receipt와 `GOAL_STARTED` 또는 `WORK_SESSION_RESUMED`가 가리키는 `repository_snapshot_before`에 일치해야 한다. 이 same-event post-gate recapture가 끝난 뒤 event를 기록하기 전까지 통제 경로를 바꾸지 않으며, event가 검증된 뒤에만 제품 파일을 수정한다. 이 gate 결과는 Gateway 배포, 외부 URL, 실제 기기 또는 279개 정식 시험 증거가 아니다.

Phase B·C·D·E·F·G와 EPIC-02 Phase A의 과거 live 재생성 검사는 후속 작업이 같은 통제 경로를 변경하면 `EXPECTED_STALE`이거나 full gate에서 명시적으로 deselect된다. 아래 명령들은 0이 아닌 종료코드와 stale 또는 명시적인 후속 계약 불일치 메시지가 나와야 하며, 출력을 다시 생성해서는 안 된다. 현재 Phase D의 예상 메시지는 `product boundary version differs`다. 과거 불변성은 r008을 포함한 frozen predecessor SHA-256 검사와 통합 회귀의 의미 검사로 확인한다. 특히 Phase G와 Phase A의 builder·test·고정 출력 predecessor 지문은 바꾸지 않는다.

```bash
python3 -B scripts/build_walksafe_epic01_phase_b_trace_20260722.py --check
python3 -B scripts/build_walksafe_epic01_phase_c_trace_20260722.py --check
python3 -B scripts/build_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py --check
python3 -B scripts/build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py --check
python3 -B scripts/build_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py --check
python3 -B scripts/build_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py --check
```

`validate`는 테스트를 실행하지 않고 모든 Python 시험 파일이 정확히 한 레이어에 속하는지만 확인한다. 현재 구현을 보호하는 시험은 일반 CI 레이어에 두고, 질문지·Draft·초기 기준선 생성 당시 snapshot을 재현하는 시험은 `HISTORICAL_CONTROL_PYTHON_TESTS`로 따로 등록한다. 후자는 승인 뒤 구현 변경으로 일부가 의도적으로 stale이므로 현재 구현 CI 성공조건으로 사용하지 않는다. branch·snapshot base HEAD·미커밋 content hash와 Goal event replay에 결속된 continuation·Goal graph 검사는 `ACTIVE_SESSION_CONTROL_PYTHON_TESTS`로 분류해 세션 시작·종료에 직접 실행하고 일반 CI `all`에는 넣지 않는다.

고정 Python이 없는 새 장비에서는 위 읽기 전용 검사를 실행하기 전에 별도 준비 단계에서 환경을 만든다.

```bash
python3 -m venv .venv
.venv/bin/python -m pip install --require-hashes --no-compile \
  -r backend/requirements.lock -r tests/requirements.lock
export WALKSAFE_LOCKED_TEST_PYTHON="$PWD/.venv/bin/python"
```

Node도 `configs/walksafe_node_toolchain_lock_20260715.json`이 지정한 공식 archive를 저장소 밖에 준비한 뒤 그 실제 `bin` 절대경로를 `WALKSAFE_NODE_BIN_DIR`에 지정한다. 설치·압축 해제 과정은 파일을 변경하므로 읽기 전용 재시작 검사에 포함하지 않는다. 이미 검증된 Python·Node 환경이 있으면 새로 만들지 않고 두 환경변수만 해당 절대경로로 지정한다.

### 10.2 과거 Gap r001 재현 검사

다음 명령은 구현 변경을 시작하기 전의 commit `a3ad7eead6b5d834d3e0675422475a9aad351e3d` 진단 snapshot을 재현한다.

```bash
python3 -B scripts/build_walksafe_implementation_gap_analysis_20260722.py --check
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest \
  tests.test_walksafe_implementation_gap_analysis_20260722 -q
```

- 작업 범위가 그 commit과 같으면 통과해야 한다.
- 체크포인트가 `IN_PROGRESS`이고 구현 파일이 의도적으로 바뀌었다면 scope/hash 불일치는 예상 결과다.
- 이 경우 r001을 고치거나 되돌리지 않는다. 체크포인트의 변경 snapshot을 확인하고 구현을 이어간 뒤 r002 이상을 생성한다.
- 구현 변경과 관계없는 정책 기준선·receipt·RTM 입력 hash 실패는 예상 결과가 아니므로 중단하고 조사한다.

다음 두 과거 도구는 승인 전 Draft 세계를 검사하므로 현재 상태 확인용으로 쓰지 않는다.

```text
scripts/build_walksafe_control_bootstrap.py --check
scripts/validate_walksafe_formal_deliverables_0_6.py
```

## 11. 실패 시 중단·복구 규칙

| 실패 | 행동 |
|---|---|
| COMMITTED receipt 불일치 | 구현을 중단하고 승인 적용 파일·해시부터 복구 |
| 정책 1.0.1 또는 상태 수 불일치 | DOC-01·receipt·체크포인트의 변경 출처 조사 |
| 구현 변경 없이 Gap r001 검사 실패 | 입력 손상이나 예기치 않은 파일 변경 조사 |
| 체크포인트에 기록된 구현 변경 때문에 Gap r001이 stale | 정상 상황. r001 보존 후 구현 완료 뒤 r002 생성 |
| Active 원장과 체크포인트 불일치 | 가장 최근의 유효 변경 사건과 해시를 확인해 체크포인트 갱신 |
| 정책 의미를 바꿔야만 구현 가능 | 구현 중지, 변경요청·영향분석·사용자 결정 진행 |
| 실기기·외부서비스·전문가 없어서 검증 불가 | 구현 가능한 범위까지만 완료하고 gate/시험은 `NOT_RUN` 유지 |
| AWAITING 답변·외부 증거가 아직 없음 | blocker와 원 Goal을 보존하고 ready frontier의 독립 branch를 정상 선택; ready leaf가 없으면 대기 유지 |
| 답변·외부 receipt가 blocker와 결속되지 않음 | 권한을 추정하지 말고 RESUME하지 않음 |
| 동일 action identity가 다시 선택됨 | 기존 Goal 상태를 재개하거나 완료됐으면 다음 action 선택; 정당한 trigger 없이 복제 Goal 생성 금지 |
| 활성화 후 정적 Goal 계획 변경 필요 | 현재 graph package 파일을 덮어쓰지 않는다. 필요한 branch를 대기시키고 successor manifest·승인된 trust anchor·versioned successor checker를 준비한다. |
| 배포·이관·종료 receipt에 원자료·실행자·승인자·필수 check가 부족 | 계획·요약만으로 완료하지 않고 `PARTIAL/AWAITING_*` 유지 |
| 외부 행위자 권한·실제성이 저장소 JSON에만 있고 signed/external anchor가 없음 | 내부 구조 검증만 인정하고 `AWAITING_EXTERNAL`; 출시 적격 승인 전이면 `NOT_ELIGIBLE` 유지 |

## 12. 금지사항

- 기존에 답한 정책을 근거 없이 다시 질문하지 않는다.
- 현행 구현을 정책으로 역승격하지 않는다.
- Web/PWA를 현재 주제품이나 출시 경로로 표현하지 않는다.
- 102개 승인을 구현·시험·배포 완료로 해석하지 않는다.
- 실행 증거 없이 75개 `Planned/NOT_RUN` 상태를 올리지 않는다.
- 5개 gate를 자동 종료하거나 면제하지 않는다.
- append-only인 Gap·백로그 r001~r011 파일을 덮어쓰지 않는다.
- `git clean`, 강제 reset, 광범위 삭제로 사용자 산출물을 지우지 않는다.
- 날짜가 붙은 과거 승인 materializer를 새 상태 전환 도구로 재사용하지 않는다.
- 비밀값, 원본 개인정보, 실제 사용자 영상·음성을 Git에 넣지 않는다.

## 13. 세션 종료와 다음 세션 인계

프로젝트 상태에 영향을 준 세션은 끝나기 전에 다음을 남긴다.

1. 체크포인트의 focus·ready·blocked 집합, 상태, 마지막 완료 작업, 선택된 다음 행동을 갱신한다.
2. 변경 파일과 검증 명령·결과를 기록한다.
3. 해결하지 못한 결함·위험·gate를 명시한다.
4. Active 원장 또는 새 실행 기록에 필요한 사건을 반영한다.
5. `daylog/YYYY-MM-DD.md`에 작업을 기록한다.
6. local-memory에 작업 요약·변경 파일·검증 결과를 기록하고 동기화한다.
7. 다음 세션이 재현할 수 있도록 branch, snapshot base commit과 작업 content hash를 남긴다. 현재 HEAD는 base commit과 같거나 그 후손이어야 한다.
8. 10장의 시작 검사와 같은 기준으로 Goal 패키지와 continuation 검사를 통과시킨다. B·C·E·F·G와 EPIC-02 Phase A 통합시험의 과거 live currentness 6건만 deselect한다. `test_walksafe_goal_graph_v2_2_history.py`는 stale FP-004 current-state 단언 2건을 archive tail에서 유도한 단언으로 override해 225개를 실행하고, `test_walksafe_epic02_trace_v2_2_history.py`는 frozen FP-018·NPC·FP-004 trace 23개를 v2.2 archive 기준으로 재생한다. 각 predecessor의 불변 출력 지문과 continuation의 현재 FP-005 단언을 검증한다.

다음 세션은 반드시 2장의 `AGENTS.md → 이 안내서 → graph-v2.3 README → imported v2.2 Master → 체크포인트와 v2.3 static manifest → versioned continuation 검사 → versioned Goal 그래프 검사 → focus Goal과 ready frontier` 순서로 시작한다. 인계 요약에 다른 순서를 적지 않는다.

체크포인트에는 최소한 다음 값이 있어야 한다.

- branch와 기준 commit
- 현재 EPIC과 연결 정책·Gap ID
- Goal 활성화 상태, graph model, focus Goal·Workstream·Work Item ID와 ready·blocked 집합
- 현재 상태와 완료조건
- 변경 파일
- 통과한 검사
- 남은 blocker/gate
- 바로 다음 행동 한 가지

현재 체크포인트는 이번 작업의 통제 경로를 정확한 목록과 content-set SHA-256으로 묶고, 검사기는 목록에서 핵심 파일 하나를 빼고 해시를 다시 계산하는 경우도 거부한다. 다만 체크포인트·검사기·작업 파일이 아직 같은 미커밋 작업트리에 있으므로 현재 checkpoint/event tail은 외부에서 암호학적으로 불변이라 하지 않고 `통제 snapshot`이라고 부른다. Goal 문서와 후보에서 쓰는 `immutable`은 기존 파일을 고치지 않고 successor만 추가하는 논리적 변경금지 정책을 뜻한다. 검사기와 체크포인트를 함께 악의적으로 바꾸는 행위까지 막으려면 검토 후 Git commit 또는 별도 서명 manifest로 고정해야 한다.

## 14. 이 안내서의 완료 기준

- 체크포인트 검사가 핵심 파일·ID·해시를 확인한다.
- 정책 기준선은 1.0.1, 상태는 102/27/53/75로 판정된다.
- gate 5개가 `NOT_RUN`·미면제로 확인된다.
- Gap 68개, EPIC 12개, 257개 산출물의 698개 의존관계와 현재 ready frontier를 확인할 수 있다.
- EPIC-01 Phase B~G와 EPIC-02 Phase A r008, FP-018 r009, NPC 권한 세션 r010은 역사 snapshot으로 보존되고, FP-004 r011은 현재 successor로 검증된다.
- 새 세션이 승인 상태와 구현 완료를 혼동하지 않는다.
- 새 세션이 현재 EPIC과 다음 행동을 한 번에 찾을 수 있다.
- 새 세션이 고정 단계 없이 의존성 DAG, 현재 focus와 ready frontier를 복구하고 완료 뒤 다음 Goal을 결정적으로 선택할 수 있다.
- 빠른 재시작 명령은 읽기 전용이다.

이 안내서와 다른 자료가 다르면 정책·승인·산출물 상태는 `COMMITTED receipt → 유효 정책 기준선 → DOC-01 → DOC-05` 순으로 재확인하고, 현재 EPIC·변경경로·다음 행동은 최신 유효 체크포인트에서 재확인한 뒤 안내서를 갱신한다.
