# WalkSafe 자율 완성 작업 그래프 v2

패키지 ID: `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2`

패키지 준비 상태(`package_status`): `PREPARED_NOT_ACTIVATED`

활성화 상태(`activation_status`): `READY_NOT_ACTIVATED`

기준일: `2026-07-23`

## 한눈에 보는 현재 상태

- 이 패키지는 아직 활성화하지 않았다. 기능 구현을 새로 실행한 것도 없다.
- 현재 초점은 `EPIC-02 / FP-018 / GAP-027`이다.
- EPIC-01은 저장소 내부 `IMPLEMENTATION_READY`다.
- EPIC-03은 EPIC-01 의존성이 끝나 독립적으로 준비된 분기다.
- 정식 시험 279개와 실제 기기 시험은 모두 `NOT_RUN`이다.
- 5개 출시 Gate는 `NOT_RUN·미면제`, 출시는 `NOT_ELIGIBLE`이다.
- 산출물 상태는 `Approved/Baselined 102 / Active 27 / Draft 53 / Planned·NOT_RUN 75`다.

이 문서 묶음은 새 제품 정책이나 258번째 산출물이 아니다. 승인된 정책과 257개 산출물을 실제 작업에 사용하는 실행 통제자료다.

## 고정 단계가 아닌 이유

WalkSafe에는 이미 257개 산출물 사이의 698개 의존관계와 68개 정책·Gap 관계가 있다. 실제로는 EPIC-02와 EPIC-03처럼 동시에 준비되는 작업이 있고, 실제 기기·외부 검토를 기다리는 동안 다른 내부 작업을 계속할 수 있다.

따라서 이 패키지는 프로젝트를 임의의 몇 단계로 나누지 않는다.

- `phase_order`, `current_phase_goal_id`, `next_goal_id`를 실행 정본으로 사용하지 않는다.
- EPIC 번호는 단계 번호가 아니라 기능 영역을 묶은 Workstream 식별자다.
- Backlog의 `execution_order`는 의존성이 아니라 준비된 작업끼리의 우선순위 동률 해소에만 쓴다.
- 작업 수는 정책 Gap, 실제 사건, 조건부 산출물, 결함과 재실행 결과에 따라 늘거나 줄 수 있다.
- 과거 구현 증거에 적힌 `EPIC-01 Phase A~G`, `EPIC-02 Phase A`는 역사적 기록 이름이므로 그대로 보존한다.

## 구조

```text
승인된 정본과 257개 산출물 의존성
              │
              ▼
       Workstream 의존 그래프
              │
       준비된 작업 집합 계산
        ┌─────┼──────────┐
        ▼     ▼          ▼
   정책·Gap  산출물     시험·Gate·외부 사건
     작업     갱신         동적 Goal
        └─────┼──────────┘
              ▼
      증거·successor·체크포인트
```

정적 문서는 다음만 고정한다.

- [`00-master-goal.md`](00-master-goal.md): 전체 목적·권한·완료 조건
- [`workstreams/`](workstreams/): 최신 Backlog의 12개 기능·검증 Workstream
- [`templates/policy-gap-work-item.md`](templates/policy-gap-work-item.md): 정책·Gap 구현 작업
- [`templates/dynamic-node-template.md`](templates/dynamic-node-template.md): 산출물·후보·시험·Gate·배포·운영·인계 등 조건부 작업
- [`work-items/epic-02/epic-02-fp018-walk-state-recovery-r001.md`](work-items/epic-02/epic-02-fp018-walk-state-recovery-r001.md): 현재 구체 작업
- `static-plan-manifest-v2.0.0.json`: 정적 문서·그래프 규칙·지문

실제 실행 포인터와 동적 노드는 `docs/control/walksafe-project-continuation-checkpoint.json`의 `goal_execution`이 정본이다.

최초 초점인 FP-018 Work Item은 패키지 bootstrap과 함께 만들어졌으므로 `PACKAGE_PREPARED` event와 `dynamic_goal_inventory`에 봉인한다. 준비 완료 이후 새 동적 Goal은 static manifest와 과거 event를 수정하지 않고, ID·경로·내용 SHA-256·유형·부모·생성 근거·생성 직전 계보 포인터·대체 관계를 `dynamic_goal_inventory`와 같은 값의 `GOAL_MATERIALIZED` event에 봉인한다. `predecessor_goal_id`는 생성 계보를 추적하는 provenance 포인터이며 `REQUIRES` edge가 아니다. 준비 여부는 `start_requires`와 blocker만 결정한다. 생성된 Goal 문서는 수정하지 않으며 변경이 필요하면 `r002+` successor 파일과 새 event를 만든다.

정본 Gap·Backlog·산출물 binding이 새 revision으로 바뀌면 먼저 전체 binding snapshot과 변경 role을 `CANONICAL_BINDINGS_UPDATED` event에 남긴다. 과거 Goal은 생성 당시 snapshot을 계속 가리키고, 그 파일이 실제로 남아 같은 SHA-256인지 검사한다. 이후 새 Goal만 새 snapshot을 생성 근거로 사용한다.

검사기는 최초 `PACKAGE_PREPARED` event를 고정 anchor로 사용하고 이후 tail은 직전 event hash를 잇는 내부 hash chain으로 replay한다. tail의 과거 snapshot 대비 불변성은 검토된 Git commit이나 별도 외부 snapshot으로 고정해야 하며 checker 단독 보장으로 과장하지 않는다. `validation_cutoff_at`은 각 checkpoint snapshot의 상한이므로 다음 날짜 작업에서 갱신할 수 있지만 최신 event보다 이를 수는 없다.

## 작업 노드 유형

노드 수를 미리 정하지 않고 다음 유형을 필요한 순간에 만든다.

| 유형 | 만드는 때 | 완료 증거 |
|---|---|---|
| `POLICY_GAP_WORK` | 최신 Gap에 저장소 내부 미완료가 있을 때 | 구현기록·검증결과·successor trace·완료 receipt |
| `ARTIFACT_WORK` | Draft 보완, Active 사실 갱신, Planned 증거 생성이 필요할 때 | 대상 산출물 revision·근거·상태 전환 기록 |
| `INTEGRATION_CANDIDATE` | 함께 시험할 앱·서버·모델·설정·DB가 준비될 때 | 불변 manifest와 구성요소 SHA-256 |
| `FORMAL_TEST_RUN` | 승인 시험계획과 적격 후보·환경이 준비될 때 | 실제 원자료·실행자·환경·후보 hash |
| `RELEASE_GATE` | 각 Gate의 고유 선행조건이 준비될 때 | 측정·독립검토·훈련과 `waived=false` |
| `RELEASE_DECISION` | 같은 후보의 시험·Gate 증거가 모두 준비될 때 | TST-22·REL-02 권한 있는 승인 |
| `DEPLOYMENT_DELIVERY_EVENT` | 출시 적격 후보의 실제 배포 권한이 생길 때 | 배포·canary·smoke·rollback·수령 기록 |
| `OPERATION_EVENT` | 실제 장애·복원·비용·권한검토·삭제 사건이 생길 때 | 실행 로그와 권한 있는 확인 |
| `HANDOVER_CLOSURE_EVENT` | 책임 이관이나 프로젝트 종료 조건이 생길 때 | 이관·인수·종료 receipt |
| `BLOCKER_OR_EXTERNAL_RECEIPT` | 사용자·사람·기기·비밀·비용·외부 승인이 필요할 때 | typed receipt와 authority anchor |
| `SUCCESSOR_OR_REOPEN` | 결함·정책변경·증거반려로 재작업할 때 | 과거 보존과 새 revision 연결 |

조건부 산출물은 적용조건이 참일 때만 `ARTIFACT_WORK`로 만든다. 예를 들어 Web/PWA가 공식 제품으로 다시 승인되지 않는 한 WS-16은 활성화하지 않는다.

## 간선의 뜻

모든 연결을 하나의 순서로 취급하지 않는다.

- `REQUIRES`: 시작 전에 반드시 끝나야 하는 선행 작업
- `COMPLETION_REQUIRES`: 준비는 병행할 수 있지만 해당 Workstream을 닫기 전에는 끝나야 하는 작업
- `GOVERNS`: 정책·승인 기준이 작업을 통제
- `TRACES_TO`: 정책→요구→설계→코드→시험 연결
- `REMEDIATES`: Work Item이 특정 Gap을 보완
- `PRODUCES`: 작업이 산출물·증거·successor를 생성
- `ACTIVATED_BY`: 실제 사건이나 조건이 생기면 노드를 생성
- `BLOCKS`: 미해결 Gate·위험·외부 조건이 특정 완료를 차단
- `BINDS_SAME_CANDIDATE`: 시험·Gate·릴리스가 같은 후보 SHA-256을 사용
- `SUPERSEDES`: 과거를 보존하고 새 revision으로 대체

`REQUIRES`와 `COMPLETION_REQUIRES`를 합친 그래프는 순환할 수 없다. 재작업은 과거 노드로 되돌아가지 않고 `r002+` successor를 만들어 DAG를 유지한다.

## 준비된 작업 계산

`ready frontier`는 다음 조건을 만족하는 미완료 leaf다.

1. `start_requires`가 모두 `COMPLETE_AT_TARGET`이다.
2. 활성 blocker가 없다.
3. 적용조건이 참이다.
4. 실행에 필요한 정본 파일과 SHA-256이 유효하다.
5. 부모부터 Master까지 모든 조상이 `READY` 또는 적법한 실행 가능 상태이고 활성 blocker가 없다.

상태가 `PLANNED`인 Goal의 선행조건이 처음 충족되면 `GOAL_READY` event 하나로 그 Goal만 `READY`로 바꾼다. 이 event는 각 `start_requires` Goal의 실제 완료 event SHA-256을 `readiness_basis`에 결속한다. blocker 해제 event를 준비 전환 용도로 대신 쓰지 않는다.

선택 순서는 다음과 같다.

1. 이미 `IN_PROGRESS`인 leaf를 재개한다.
2. canonical Backlog의 `next_single_action`과 일치하는 준비된 leaf를 고른다.
3. P0 안전·개인정보·보안 작업을 우선한다.
4. 더 많은 후행 작업을 여는 노드를 우선한다.
5. `priority_rank`와 Goal ID로 결정적으로 동률을 해소한다.

한 branch가 외부 조건을 기다리면 그 branch와 하위 branch만 `AWAITING_USER`, `AWAITING_EXTERNAL` 또는 `BLOCKED`로 둔다. blocker record에는 막히기 전 상태에서 계산한 `return_status`를 봉인한다. 원래 `PLANNED`였던 Goal은 해제 뒤에도 `PLANNED`로 돌아가며, 선행 완료 event를 결속한 별도 `GOAL_READY` 없이는 `READY`가 되지 않는다. 다른 ready branch로 이동하는 것은 정상 그래프 선택이며 “단계 우회”가 아니다. 모든 경로가 막혔을 때만 질문을 한 번에 모아 사용자에게 요청한다.

Workstream은 실행 중인 작업이 아니라 동적 Work Item을 소유하는 컨테이너다. ready frontier가 Workstream을 선택하면 필요한 첫 Work Item을 materialize하고 그 leaf를 시작한다. `IN_PROGRESS`는 한 번에 정확히 한 Work Item에만 사용한다. 모든 필수 자식·정책/Gap coverage·Backlog 목표가 끝난 Workstream은 `READY → COMPLETE_AT_TARGET`으로 닫는다.

완료된 Work Item을 다시 열 때는 기존 revision과 완료 증거를 보존한다. `GOAL_SUPERSEDED` event 하나가 기존 Work Item을 `SUPERSEDED`로, 같은 `work_item_id`의 다음 연속 revision을 `PLANNED`로 함께 바꾼다. 이전 revision의 완료 event와 typed reopen receipt를 결속하며, 동일 작업에서 대체되지 않은 활성 revision은 최대 하나다. Master·Workstream은 이 event로 대체하지 않는다.

## Work Item 공통 루프

1. 체크포인트·정본 binding·그래프 무결성을 검사한다.
2. 최신 정책·Gap·Backlog와 257개 산출물 의존성을 읽는다.
3. 정책→요구→설계→코드→예정 시험을 추적한다.
4. 현재 차이를 재현하는 fail-first 검증을 만든다.
5. 승인 정책을 만족하는 최소 변경을 구현한다.
6. targeted→관련 구성요소→안전한 회귀 순으로 검증한다.
7. 독립 검토로 정책 누락·안전·완료 과장을 확인한다.
8. 구현기록·검증결과·successor trace를 서로 다른 typed JSON으로 남긴다.
9. Draft와 Active 산출물에 실제 확인한 사실만 갱신한다.
10. 과거 Gap·Backlog를 보존하고 successor에서 재평가한다.
11. 정확한 완료 receipt가 있을 때만 leaf를 완료한다.
12. 완료 event의 증거 목록·receipt binding·최신 `GOAL_STARTED` hash·시간 구간을 같은 실행 receipt와 결속한다.
13. 새로 충족된 `PLANNED` Goal은 `GOAL_READY`로 전환하고 새 ready frontier와 초점을 다시 계산한다.
14. staged checkpoint를 검사한 뒤 한 번의 논리적 commit으로 전환한다.
15. Goal·continuation 검사, daylog, local-memory 인계를 갱신한다.
16. 질문 조건이 없으면 선택된 다음 leaf를 계속 진행한다.

## 상시 통제

다음은 완료하고 지나가는 단계가 아니라 모든 작업에 적용한다.

- 산출물·변경 통제: DOC-01·DOC-05와 Active 대장 27개를 실제 사건 때 갱신
- 추적·증거 통제: 정책·요구·설계·코드·시험·Gap·Backlog successor를 연결
- 안전·보안·개인정보 통제: 안전정지, 권한, 원본수집, 보존·삭제, 관리자 영향을 교차 검토
- 실행 인계 통제: append-only event, checkpoint, daylog, local-memory를 매 전환에 갱신

## 정식 시험·Gate·출시 경계

- 내부 단위·통합 검증을 279개 정식 시험 PASS로 승격하지 않는다.
- 5개 Gate는 각각 선행조건이 준비되는 즉시 독립 Goal로 실행할 수 있다.
- 최종 Gate 종료, 279개 시험, 실제 기기 결과, 출시 결정은 같은 불변 후보에 결속한다.
- 실제 실행자·검토자·승인자·원자료·권한 anchor가 없으면 `NOT_RUN/PARTIAL/AWAITING_EXTERNAL`이다.
- TST-22·REL-02가 실제 승인되기 전 출시는 `NOT_ELIGIBLE`이다.
- 배포·운영·이관·종료 문서는 예정표가 아니라 실제 사건 이후에만 완료된다.

## 질문·권한 경계

이미 확정된 정책을 다시 묻지 않는다. 저장소 내부 구현, 내부 검증, Draft 작성, Active 사실 기록, successor 생성, 체크포인트·daylog 갱신은 질문 없이 진행한다.

다음 권한이 실제로 필요하고 독립 branch도 진행할 수 없을 때만 질문한다.

- 규범 정책 변경
- 미래 산출물의 Approved/Baselined 전환
- Gate 면제 또는 정식 PASS 승인
- 실제 기기·참여자·독립 전문검토
- 비밀정보·유료자원·프로덕션 배포
- 실제 데이터 삭제·외부 통지
- 출시·인수·운영 이관·종료 서명

## 활성화 전 상태와 재개

v1.1의 A→B→C→D 준비안은 사용자의 예시를 고정 단계 요구로 잘못 해석한 미활성 계획이다. 실행되지는 않았으며 v2가 그 준비안을 대체한다. v1.1의 파일과 지문은 감사용으로 보존하고 실행 정본으로 사용하지 않는다.

v2의 패키지 준비 상태는 `PREPARED_NOT_ACTIVATED`, 활성화 상태는 `READY_NOT_ACTIVATED`다. 활성화 전에는 graph-v2에 따른 새 코드 작업 event를 기록하지 않는다.

명시적 활성화 요청 뒤에도 시작 사건을 합치지 않는다. 먼저 `PACKAGE_ACTIVATED` event 한 번으로 `package_status`와 `activation_status`만 `ACTIVE`로 바꾸며 모든 Goal 상태는 유지한다. 이어서 별도 `GOAL_STARTED` event로 결정적으로 선택한 focus Work Item 하나만 `READY → IN_PROGRESS`로 바꾸고, 그 event가 기록된 뒤에만 코드 작업을 시작한다.

문맥이 끊기면 다음 순서로 재개한다.

1. 저장소 `AGENTS.md`
2. `docs/control/walksafe-project-resumption-runbook.md`
3. 이 README와 `00-master-goal.md`
4. 현재 checkpoint와 v2 static manifest
5. continuation checker와 Goal graph checker
6. `focus_goal_id` 문서, 최신 Gap·Backlog와 ready frontier

사용자가 명시적으로 활성화를 요청하기 전에는 준비 상태만 유지한다.
