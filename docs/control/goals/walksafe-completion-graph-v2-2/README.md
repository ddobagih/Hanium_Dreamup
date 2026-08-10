# WalkSafe 자율 완성 작업 그래프 v2.2

패키지 ID: `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-2`

패키지 준비 상태(`package_status`): `PREPARED_NOT_ACTIVATED`

활성화 상태(`activation_status`): `READY_NOT_ACTIVATED`

기준일: `2026-07-24`

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
- `static-plan-manifest-v2.2.0.json`: 정적 문서·그래프 규칙·지문

이미 준비됐던 v2.1은 활성화와 제품 작업 없이 원 경로와 지문을 그대로 보존한다. 이 패키지는 v2.1 준비 사건을 supersede한 별도 event chain이며, v2.1 본문이나 manifest를 다시 봉인하지 않는다. v1.1→v2.0 계보는 frozen v2.1 패키지가 계속 감사하므로 v2.2에는 그 계보 파일을 중복 복제하지 않는다.

실제 실행 포인터와 동적 노드는 `docs/control/walksafe-project-continuation-checkpoint.json`의 `goal_execution`이 정본이다.

최초 초점인 FP-018 Work Item은 패키지 bootstrap과 함께 만들어졌으므로 `PACKAGE_PREPARED` event와 `dynamic_goal_inventory`에 봉인한다. 준비 완료 이후 새 동적 Goal은 static manifest와 과거 event를 수정하지 않고, ID·경로·내용 SHA-256·유형·부모·생성 근거·생성 직전 계보 포인터·대체 관계를 `dynamic_goal_inventory`와 같은 값의 `GOAL_MATERIALIZED` event에 봉인한다. `predecessor_goal_id`는 생성 계보를 추적하는 provenance 포인터이며 `REQUIRES` edge가 아니다. 준비 여부는 `start_requires`와 blocker만 결정한다. 생성된 Work Item Goal 문서는 수정하지 않으며 변경이 필요하면 `r002+` successor 파일과 새 event를 만든다.

FP-018에서 시작하는 순서를 재현하기 위해 manifest와 checkpoint는 FP-017/GAP-026 한 쌍만 `bootstrap_consumed_policy_gap_pairs`로 봉인한다. 이 항목은 EPIC-02 Phase A record와 r008 Backlog binding이 증명한 “내부 slice를 수행해 다음 정책으로 이동했다”는 scheduler predecessor이며 Workstream coverage 계산에만 사용한다. GAP-026을 `IMPLEMENTED`, 정식 시험 완료, 정책 목표 완전 달성 또는 실제 Work Item 완료로 승격하지 않는다. 임의의 다른 `PARTIAL` 행은 이 목록에 자동 포함할 수 없다.

정본 Gap·Backlog·산출물 binding이 새 revision으로 바뀌면 live checkpoint를 바로 바꾸지 않는다. staged snapshot에서 role별 실제 내용 차이를 정책 ID·Gap ID 등 영향 단위로 계산하고 `CANONICAL_BINDINGS_UPDATED` event에 이전·이후 전체 binding과 함께 봉인한다. 요구·설계·모듈·시험 행의 변경은 그 행이 추적하는 정책·Gate·Gap ID로 환산하며, 그런 연결이 없으면 일부 작업에만 임의 배정하지 않고 `*` 전역 영향으로 처리한다. 식별자 없는 전역 실행·완료·권한 규칙 변화와 role별 명시적 비영향 provenance 목록에 없는 새 필드도 `*`다. Gap 보고서와 Backlog는 각자 내용 지문이 맞고 Backlog가 같은 Gap 지문을 가리키는 한 쌍으로만 교체한다. Gap `reassessment_scope`의 직접·영향검토·carry-forward 집합은 실제 assessment row 차이와 같아야 한다. 완료하려는 정책의 Gap은 새 내부 근거로 실제 변경되고 `PARTIAL`, `EVIDENCE_MISSING`, `IMPLEMENTED` 중 하나로 재판정되어야 하므로 `MISSING`·`CONFLICTING`·`BLOCKED` 상태를 그대로 둔 채 작업을 닫을 수 없다. 새 assessment의 모든 근거 ID는 유일한 evidence catalog 행과 실제 파일 지문에 연결되고, 그중 적어도 하나는 이번 생산 Goal·완료 receipt·구현기록 또는 검증 원출력에 직접 결속되어야 한다. Backlog의 진행 집계와 다음 포인터는 영향 입력이 아닌 파생값이지만, 해당 `POLICY_GAP_WORK`만 바꿀 수 있고 완료된 정책/Gap Work Item 집합과 dependency 순서에서 검사기가 다시 계산한다. 유효 정책·Gate 68개 집합과 정책↔Gap 68개 연결 지문은 정적 manifest와 정확히 같아야 하며 추가·삭제·교환은 successor package 사유다.

현재 Work Item이 새 Gap·Backlog를 만든 경우 `POLICY_GAP_WORK`는 `IMPLEMENTATION_GAP`과 `IMPLEMENTATION_BACKLOG`를 반드시 한 쌍으로 생산한다. `ARTIFACT_WORK`는 생성 시점부터 `ARTIFACT_REGISTER`와 `ARTIFACT_CHANGE_LOG`의 동일한 비어 있지 않은 `DLV-*` 대상 집합을 `output_subject_ids_by_role`에 봉인하며, 실제 register 행 변경과 append된 변경이력의 affected 집합이 정확히 같아야 한다. DOC-05의 새 행은 고유 변경 ID·날짜·사유·변경 전후 요약·대상 경로별 전후 지문뿐 아니라 생산 Goal 지문과 같은 revision의 DOC-01 binding을 가져야 하며, 대상 경로·지문 집합은 생산자의 `IMPLEMENTATION_RECORD.changed_artifacts`와 정확히 같아야 한다. 따라서 대상 코드만 적거나 관계없는 기존 파일을 넣은 기록은 인정하지 않는다. 요구·설계·모듈·시험 role도 함께 생산하면 해당 role의 실제 행 ID를 별도 출력 범위로 결속하고 정책 영향 계산과 섞지 않는다. 두 원장의 `content_sha256` self-seal도 매 revision 검증한다.

`POLICY_GAP_WORK` 완료에는 서로 다른 `IMPLEMENTATION_RECORD`, `VERIFICATION_RESULT`, `SUCCESSOR_TRACE`가 모두 필요하다. 다만 새 Gap의 내부 `evidence_catalog`는 Gap bytes가 확정되기 전에 만들 수 있는 implementation·verification 두 결과만 exact hash로 싣는다. 새 Gap·Backlog file hash를 참조해야 하는 post-update `SUCCESSOR_TRACE`를 다시 Gap 안에 넣으면 암호학적 자기순환이 생기므로 catalog에는 넣지 않고, `CANONICAL_BINDINGS_UPDATED`와 Work Item completion receipt가 별도로 exact 결속한다.

checkpoint의 `artifact_work_queue`는 현재 `ARTIFACT_REGISTER` 파일 SHA-256에 결속해 257개 전체를 `TERMINAL`, `INACTIVE`, `LIVE_GOAL`, `STRUCTURALLY_DUE`, `WAITING_UPSTREAM`, `WAITING_TRIGGER`, `WAITING_APPLICABILITY` 중 정확히 하나로 분할한다. 같은 `DLV-*`를 둘 이상의 live Goal이 소유할 수 없고, blocker와 upstream이 모두 해소된 `STRUCTURALLY_DUE` 대상은 선언된 priority→unlock→topology→code 순서의 첫 후보부터 `ARTIFACT_WORK`로 materialize해야 한다. 각 Work Item은 한 `DLV-*`만 소유하며 공통 산출물은 Master 아래에 둔다.

`WAITING_APPLICABILITY`와 `WAITING_TRIGGER`는 완료로 세지 않는 비종결 상태다. queue는 적용성 미결정과 사건 대기 대상을 같은 결정적 정책으로 평가 순서화하고 `next_assessment_target_id`, 현재 queue 상태, 필요한 Work Item reason, 허용 종결 결과를 함께 파생한다. 일반 내부 ready frontier가 소진되면 이 첫 대상은 적합한 `ARTIFACT_WORK`로 materialize되거나 register canonical transaction에서 허용된 종결 disposition을 받아야 하며, 둘 다 없으면 검사가 실패한다. `PENDING_EVALUATION`은 `APPLICABILITY_DECISION`으로 처리하고 조용히 제외하지 않는다. `ACTIVE_EVENT_UPDATE`는 실제 trigger JSON의 `document_id/path/file_sha256` direct binding을 materialization event가 refs와 정확히 결속해야 하며 임의 문자열이나 현재 queue 상태·reason과 맞지 않는 live Goal은 인정하지 않는다. register canonical update와 모든 전환 event마다 queue를 다시 계산하며 미분류 대상, 중복 소유, 실행 가능한데 materialize되지 않은 대상, 중간 runtime 변조는 실패다.

두 내부 생산 유형은 canonical update를 생략하고 완료할 수 없다. 가장 최근 실행 세션 event에 결속된 완료 receipt를 같은 staged snapshot에 두고, canonical update 바로 다음에는 그 생산자 `GOAL_COMPLETED`만 올 수 있다. 완료 receipt는 실제 변경 경로와 전후 hash가 있는 구현기록, 명령·exit code 0·원출력 hash가 있는 검증결과, resulting canonical binding을 묶은 successor trace를 각각 하나씩 가져야 한다. 실행자와 검토자는 달라야 하며 검토 결과 hash를 결속한 독립 내부 review record가 필요하다. 승인 정책 범위 안의 내부 기술·관리 문서 승격은 event에 `DELEGATED_INTERNAL_DOCUMENT_APPROVAL` 범위와 정확한 생산 Goal·DLV 집합·완료 receipt role을 선언하고 위 강한 내부 증거를 모두 통과하면 별도 사용자 승인 없이 처리한다. 정책 기준선, 규범·안전·개인정보 문서, `NOT_RUN→PASS`, blocker 제거·면제·적용성 변경·외부서명·Gate·출시·인수·이관·종료 주장은 이 위임 대상이 아니며 정확한 before/after를 결속한 외부 권한 근거가 필요하다.

영향받은 완료 Work Item은 독립적으로 `UNAFFECTED`가 입증되지 않으면 같은 update 후보 안에서 successor로 대체한다. 아직 완료 전인 Work Item의 입력이 바뀌면 `UNAFFECTED`로 넘기지 않고 새 revision으로 대체한다. 검사기는 직접 영향에서 끝내지 않고 부모와 `start_requires·completion_requires`의 역의존 폐쇄를 계산한다. 완료 Workstream은 직접 영향 또는 child·completion 집계 갱신만 필요하면 같은 `CANONICAL_BINDINGS_UPDATED` 사건에서 `READY`로 다시 열고, start dependency가 무효화됐으면 `PLANNED`로 되돌린 뒤 upstream 재완료 후 별도 `GOAL_READY`로 연다. 준비 상태였던 downstream도 start dependency가 깨지면 `PLANNED`로 되돌리며, superseded Work Item을 선행조건으로 가진 계획 상태 Work Item도 연속 revision successor로 바꿔 의존 간선을 최신 revision에 다시 결속한다. 과거 실행·완료 증거는 지우지 않는다. Workstream을 다시 완료할 때는 직전 reopen event, 최신 dependency·child 완료 event와 이 사건들 뒤에 생성된 `WORKSTREAM_REVALIDATION::<goal_id>` 증거를 결속한다. 완료된 Master와 `PACKAGE_COMPLETED`는 종결 상태라 그 뒤에는 어떤 event도 붙이지 않는다. 종료 뒤 새 변경을 시작하려면 이 완료 패키지를 predecessor로 보존한 별도 프로젝트 Goal 패키지가 필요하며, 현재 패키지의 자율 완성 범위에는 포함하지 않는다. Active 산출물 변경이력은 append-only, 산출물 관리대장은 기존 artifact identity를 보존하므로 정상적인 원장 추가가 과거 Goal을 소급 무효화하지 않는다. 최종 산출물 승인 적용 receipt는 프로젝트 완료를 판정하는 통제 증거이지 각 구현 Goal의 기술 입력이 아니므로 revision이 바뀌어도 기존 Goal을 소급 reopen하지 않는다.

같은 패키지에서 다시 여는 것은 Workstream·정책/Gap 소유관계·의존 간선·우선순위·목표수준이 그대로인 내용 회귀에만 허용한다. Work Item successor는 작업 ID·유형·부모·정책/Gap·목표·canonical 입력뿐 아니라 blocker 대상과 `ARTIFACT_WORK`의 role별 출력 범위도 바꿀 수 없다. 새 정책/Gap/Workstream을 추가·삭제·이동하거나 정적 의존성·목표수준을 바꾸려면 기존 manifest와 event head를 보존한 versioned successor manifest와 checker가 필요하다. 이것은 작업 수를 고정한다는 뜻이 아니다. 정적 기능영역의 골격만 버전으로 통제하고 그 아래 Work Item 수와 종류는 계속 동적으로 변한다.

외부 결정에 사용하는 authority roster의 첫 등록은 sequence 1과 current checker anchor가 필요하다. 이후 회전은 이전 roster ID·SHA-256, 단조 sequence·effective time을 이어야 한다. 과거 anchor는 과거 event replay에만 쓰고 최종 canonical roster는 항상 current anchor와 같아야 하므로 폐기된 roster로 되돌릴 수 없다. 과거 Goal은 각 event 당시 snapshot을 계속 가리키며, 그 파일이 실제로 남아 같은 SHA-256인지 검사한다. 내부 기술·관리 문서의 제한된 위임 승인은 외부 행위자 명부를 흉내 내지 않고 위의 event 선언·생산자 완료 receipt·독립 내부 review 계약으로 별도 검증한다.

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

조건부 산출물은 적용조건이 참일 때만 `ARTIFACT_WORK`로 만든다. 예를 들어 Web/PWA가 공식 제품으로 다시 승인되지 않는 한 WS-16은 적용하지 않으며 관련 `ARTIFACT_WORK`도 materialize하지 않는다.

결함·정책변경·증거반려의 successor/reopen은 별도 Work Item 유형이 아니다. 먼저 Gap·Backlog canonical 영향 transaction을 기록하고, 기존 Work Item 유형을 유지한 연속 revision과 `GOAL_SUPERSEDED`, 필요한 `REOPEN_CONTAINER` 전환으로 처리한다. 정적 구조 변경이나 종결 뒤 변경은 versioned successor package로 처리한다.

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

`REQUIRES`와 `COMPLETION_REQUIRES`를 합친 그래프는 순환할 수 없다. Work Item 재작업은 과거 노드로 되돌아가지 않고 `r002+` successor를 만들어 DAG를 유지한다.

## 준비된 작업 계산

`ready frontier`는 다음 조건을 만족하는 미완료 leaf다.

1. 상태가 `READY` 또는 `IN_PROGRESS`다.
2. `start_requires`가 모두 `COMPLETE_AT_TARGET`이다.
3. 활성 blocker가 없다.
4. 적용조건이 참이다.
5. 실행에 필요한 정본 파일과 SHA-256이 유효하고, 유형별 필수 착수 증거가 그 시점 canonical binding에 존재한다.
6. 부모부터 Master까지 모든 조상이 `READY` 또는 적법한 실행 가능 상태이고 활성 blocker가 없다.

상태가 `PLANNED`인 Goal의 선행조건이 처음 충족되면 `GOAL_READY` event 하나로 그 Goal만 `READY`로 바꾼다. 이 event는 각 `start_requires` Goal의 실제 완료 event SHA-256을 `readiness_basis`에 결속한다. blocker 해제 event를 준비 전환 용도로 대신 쓰지 않는다.

선택 순서는 다음과 같다.

1. 이미 `IN_PROGRESS`인 leaf를 재개한다.
2. canonical Backlog의 `next_single_action`과 일치하는 준비된 leaf를 고른다.
3. P0 안전·개인정보·보안 작업을 우선한다.
4. 더 많은 후행 작업을 여는 노드를 우선한다.
5. `priority_rank`와 Goal ID로 결정적으로 동률을 해소한다.

한 branch가 외부 조건을 기다리면 그 branch와 하위 branch만 `AWAITING_USER`, `AWAITING_EXTERNAL` 또는 `BLOCKED`로 둔다. blocker record에는 막히기 전 상태에서 계산한 `return_status`를 봉인한다. 원래 `PLANNED`였던 Goal은 해제 뒤에도 `PLANNED`로 돌아가며, 선행 완료 event를 결속한 별도 `GOAL_READY` 없이는 `READY`가 되지 않는다. 다른 ready branch로 이동하는 것은 정상 그래프 선택이며 “단계 우회”가 아니다.

사용자만 결정할 수 있는 질문과 실제 기기·사람·서명·비용·외부 시스템에서 수행해야 하는 action/evidence 요청은 별도 queue와 전역 고유 request key로 관리한다. USER/EXTERNAL blocker는 각각 `USER_DECISION` 또는 `EXTERNAL_ACTION_EVIDENCE` request kind, 구체 action, 정렬된 필수 해제 증거 role, `USER_AUTHORIZATION` 또는 `EXTERNAL_ATTESTATION` 권한 요구, 자신을 만든 event ID를 봉인한다. request key는 owner·kind·action·대상 Goal 경로·내용 hash·Work Item 유형·정규화한 해제 증거 role·권한 요구로 만든 canonical logical basis의 SHA-256이며, blocker ID만 바꿔 같은 요청을 다시 만들 수 없다. 해제된 과거 요청도 같은 key로 다시 발행할 수 없다.

새 blocker 요청은 사건 직전 ready frontier의 첫 Goal이면서 기존 runtime focus인 결정적 초점만 대상으로 한다. 그 초점이 막혀도 이미 활성인 요청이 다른 내부 ready branch를 선점하거나 전체 실행을 중단시키지 않으며 scheduler는 다음 내부 branch를 계속 선택한다. `pending_questions`는 활성 USER request와 정확히 일대일로 유지하되 내부 frontier가 남아 있으면 `delivery_status=DEFERRED_INTERNAL_FRONTIER`, 내부 frontier가 소진된 뒤에만 `READY_FOR_USER`로 전달한다.

Workstream은 실행 중인 작업이 아니라 동적 Work Item을 소유하는 컨테이너다. ready frontier가 Workstream을 선택하면 필요한 첫 Work Item을 materialize하고 그 leaf를 시작한다. `IN_PROGRESS`는 한 번에 정확히 한 Work Item에만 사용한다. 모든 필수 자식·정책/Gap coverage·Backlog 목표가 끝난 Workstream은 `READY → COMPLETE_AT_TARGET`으로 닫는다.

`INTEGRATION_CANDIDATE`는 아무 완료 Workstream 하나나 임의의 `ARTIFACT_WORK` 한 건으로 만들 수 없다. EPIC-11 아래에서는 EPIC-02~10과 EPIC-11의 모든 정책/Gap Work Item 및 manifest가 선언한 후보 필수 `DLV-*` subject 집합이, Master 아래에서는 EPIC-02~11과 같은 필수 subject 집합이 정확히 완료돼야 한다. 후보 manifest는 Android 앱·Backend·Android Gateway·온디바이스 모델·설정·DB migration의 실제 파일 hash, source commit, SBOM, build provenance, 설정·DB 세대를 하나로 결속한다.

## 완료 경계

`repository_scope_status=COMPLETE_AWAITING_EXTERNAL`은 저장소에서 자율 실행 가능한 leaf뿐 아니라 내부 `PLANNED/BLOCKED` leaf와 비종결 artifact queue 대상도 모두 끝났고, 남은 모든 미완료가 구체적인 외부 action/evidence와 receipt role에 결속된 파생 milestone이다. completion boundary는 `internal_pending_goal_ids`와 `artifact_pending_target_ids`를 별도로 파생하며 둘 중 하나라도 남으면 이 milestone을 만들지 않는다. 이 상태에서도 package와 Master는 `ACTIVE/READY`, 프로젝트는 `NOT_COMPLETE`, 출시는 `NOT_ELIGIBLE`이며 `PACKAGE_COMPLETED`를 만들지 않는다. 외부 receipt까지 검증되어 Master 완료 기준을 충족한 경우에만 `project_status=COMPLETE`와 종결 event를 기록한다.

내부 독립검토는 구현자와 다른 에이전트·실행자의 저장소 근거 검토다. 법률·전문가·실제 사용자·기기·운영 승인처럼 정책이 외부 행위자를 요구하는 검토를 대신하지 않는다. 내부 범위가 끝났을 때는 milestone을 만든 event 전용 add-only `EXTERNAL_ACTION_PACKET` JSON을 direct SHA-256 binding으로 함께 남긴다. packet ID는 정렬된 외부 request basis 전체의 SHA-256에서 파생하며, 각 request는 중복 없는 canonical key, 대상 Goal 경로·hash·유형, source blocker snapshot·event, 구체 action, 정확한 evidence/completion receipt role, candidate·authority/roster 요구를 결속한다. 요청 집합이나 의미가 바뀌면 새 event-scoped packet revision을 만들고 과거 파일을 재사용하지 않는다. `status=ISSUED_NOT_EVIDENCE`인 이 packet은 요청서일 뿐 완료 receipt·외부 attestation·권한 증거로 계산하지 않으며 어떤 완료 `evidence_refs`에도 사용할 수 없다.

Work Item을 대체할 때는 기존 revision과 이미 생긴 증거를 보존한다. `GOAL_SUPERSEDED` event 하나가 기존 Work Item을 `SUPERSEDED`로, 같은 `work_item_id`의 다음 연속 revision을 `PLANNED`로 함께 바꾼다. 같은 패키지에서 독립적인 defect·증거반려 receipt만으로 이 event를 만들지는 않는다. 먼저 결함·반려 사실을 Gap·Backlog successor에 기록한 `CANONICAL_BINDINGS_UPDATED` 영향 transaction을 만들고, 원인이 된 update SHA-256에 Work Item·역의존 successor와 Workstream reopen을 함께 결속한다. 정적 구조가 바뀌거나 패키지가 끝났으면 versioned successor package를 사용한다. 동일 작업에서 대체되지 않은 활성 revision은 최대 하나다. 완료 Workstream은 `REOPEN_CONTAINER` 처분과 상태 변경을 canonical update 한 사건에 함께 넣는다. 직접 영향 또는 child·completion 집계 갱신만 필요하면 `READY`, start dependency가 무효화됐으면 `PLANNED`이며 upstream 재완료 뒤 별도 `GOAL_READY`가 필요하다. 필요한 child successor를 이어서 만들고 완료 Master에는 event를 추가하지 않는다.

## Work Item 공통 루프

1. 체크포인트·정본 binding·그래프 무결성을 검사한다.
2. 최신 정책·Gap·Backlog와 257개 산출물 의존성을 읽는다.
3. 정책→요구→설계→코드→예정 시험을 추적한다.
4. 현재 차이를 재현하는 fail-first 검증을 만든다.
5. 승인 정책을 만족하는 최소 변경을 구현하고 실제 변경 hash를 가진 구현기록을 확정한다.
6. targeted→관련 구성요소→안전한 회귀 순으로 검증하고 명령·exit code·원출력 hash를 가진 검증결과를 확정한다.
7. Draft·Active 산출물과, 해당 Work Item이 소유하면 과거를 보존한 Gap·Backlog successor bytes를 실제 확인한 사실에 맞게 확정한다.
8. resulting canonical bytes가 정해진 뒤 post-update successor trace를 만들고, 세 결과에 대해 독립 검토로 정책 누락·안전·완료 과장을 확인한다.
9. 세 결과·review·가장 최근 실행 세션 event를 결속한 정확한 완료 receipt를 만든다.
10. 내부 생산자는 `CANONICAL_BINDINGS_UPDATED` 바로 다음 `GOAL_COMPLETED`에서 같은 증거·receipt binding을 가리킬 때만 leaf를 완료한다.
11. 정식·외부 유형은 `GOAL_READY`와 `GOAL_STARTED` 각각에 필수 착수 증거 binding snapshot을 결속하고, 완료 event의 증거 목록·receipt binding·가장 최근 실행 세션 event(`GOAL_STARTED` 또는 `WORK_SESSION_RESUMED`) hash·시간 구간을 같은 실행 receipt와 결속한다.
12. 다음 Work Item이 필요하면 `GOAL_COMPLETED` 뒤 `GOAL_MATERIALIZED → GOAL_READY` 순서로 추가하고 새 ready frontier와 초점을 다시 계산한다.
13. staged checkpoint를 검사한 뒤 한 번의 논리적 commit으로 전환한다.
14. Goal·continuation 검사, daylog, local-memory 인계를 갱신한다.
15. 질문 조건이 없으면 선택된 다음 leaf를 계속 진행한다.

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
- 정식 시험·Gate는 승인 시험계획과 완료된 불변 통합 후보가 없으면 `READY`나 `IN_PROGRESS`가 될 수 없다.
- 출시 결정은 정식 시험 Goal, 서로 다른 5개 Gate Goal, 실제 기기 결과가 없으면 시작할 수 없다.
- 배포·운영·이관은 각각 출시 적격·기술 인도와 선행 실행 Goal의 완료 증거가 없으면 시작할 수 없다.
- 필수 외부 receipt는 path·document ID·파일 SHA-256·상태·candidate와 checker의 외부 attestation anchor를 READY/STARTED event 당시 snapshot으로 검증한다.
- 선행 Goal이 실제 완료 증거로 사용한 role·binding·완료 event hash가 현재 착수 증거와 같아야 하며, 현재 canonical role만 바꿔 다른 후보를 끼울 수 없다.
- 완료 receipt의 실행 구간은 해당 Goal의 가장 최근 `GOAL_STARTED` 또는 `WORK_SESSION_RESUMED` 이후여야 하고, 완료 event 이전에 생성·승인이 끝나며 시작 때 고정한 candidate와 같아야 한다.
- 외부 attestation trust anchor는 role별 append-only hash history다. 새 receipt revision을 추가할 때 과거 hash를 지우지 않아 과거 event와 새 event를 함께 재검증한다.
- 실제 실행자·검토자·승인자·원자료·권한 anchor가 없으면 `NOT_RUN/PARTIAL/AWAITING_EXTERNAL`이다.
- TST-22·REL-02가 실제 승인되기 전 출시는 `NOT_ELIGIBLE`이다.
- 배포·운영·이관·종료 문서는 예정표가 아니라 실제 사건 이후에만 완료된다.

## 질문·권한 경계

이미 확정된 정책을 다시 묻지 않는다. 저장소 내부 구현, 내부 검증, Draft 작성, Active 사실 기록, successor 생성, 체크포인트·daylog 갱신과 승인 정책 안의 내부 기술·관리 문서 승인은 질문 없이 진행한다. 내부 승인은 완료 기준, 실제 변경 hash와 검증 원출력, 실행자와 다른 검토자의 review record가 모두 있을 때만 기록한다.

다음 권한이 실제로 필요하고 독립 branch도 진행할 수 없을 때만 질문한다.

- 규범 정책 변경
- 규범 정책·안전·개인정보·Gate·출시·인수·이관·종료를 주장하는 Approved/Baselined 전환
- Gate 면제 또는 정식 PASS 승인
- 실제 기기·참여자·독립 전문검토
- 비밀정보·유료자원·프로덕션 배포
- 실제 데이터 삭제·외부 통지
- 출시·인수·운영 이관·종료 서명

## 활성화 전 상태와 재개

v1.1의 A→B→C→D 준비안은 사용자의 예시를 고정 단계 요구로 잘못 해석한 미활성 계획이다. 실행되지는 않았으며 v2가 그 준비안을 대체했다. v2.1도 활성화 전에 hardening 결함이 발견되어 byte-exact frozen predecessor로 보존하고, v2.2가 그 계약을 보완한 현재 실행 정본이다. v1.1·v2.0·v2.1 파일과 지문은 감사용으로 보존하고 실행 정본으로 사용하지 않는다.

v2.2의 패키지 준비 상태는 `PREPARED_NOT_ACTIVATED`, 활성화 상태는 `READY_NOT_ACTIVATED`다. 활성화 전에는 graph-v2-2에 따른 새 코드 작업 event를 기록하지 않는다.

명시적 활성화 요청 뒤에도 시작 사건을 합치지 않는다. 사용자 활성화 승인 원본은 먼저 만들고 그 파일 지문만 checker trust anchor에 고정한다. 저장소 snapshot과 빠른 두 검사 결과는 별도의 내부 quick-gate receipt에 기록해 순환 hash를 피한다. 두 receipt와 동일한 실행 전 snapshot을 결속한 `PACKAGE_ACTIVATED` event 한 번으로 `package_status`와 `activation_status`만 `ACTIVE`로 바꾸며 모든 Goal 상태는 유지한다. 이 event는 준비 시점에 이미 완료였던 Goal의 completion event·증거 refs·event-time binding도 함께 봉인한다.

제품 코드 작업 전에는 재개 runbook §10.1의 전체 구현 시작 검사를 정적 manifest에 고정된 ID·순서·정확한 명령으로 통과해야 한다. 검사별 원출력은 event ID 전용 새 경로에 저장하며 과거 경로를 덮어쓰거나 재사용하지 않는다. 그 뒤 `GOAL_STARTED` event로 결정적으로 선택한 focus Work Item 하나만 `READY → IN_PROGRESS`로 바꾸고, 이 event가 기록된 뒤에만 코드 작업을 시작한다. 새 터미널에서 이미 `IN_PROGRESS`인 Work Item을 이어갈 때는 같은 전체 검사를 다시 실행하고 상태를 바꾸지 않는 `WORK_SESSION_RESUMED` event를 먼저 추가한다. 완료 receipt는 최초 시작 event가 아니라 가장 최근의 `GOAL_STARTED` 또는 `WORK_SESSION_RESUMED`에 결속한다.

검사 명령 계약 버전은 `2026-07-24.2`다. quick 계약 hash는 `755901f0b5996fafa6a23d3d39efe7e3ab1a06c12a9faf534a47575eff8f9625`, full 계약 hash는 `b559cf9abd6a7bb067ef5369955600e27bc2f0205f3c490c330618b64d269692`이며 서로 독립적으로 계산한다. full 순서는 `CONTINUATION → GOAL_GRAPH → BASELINE_MATERIALIZATION → ANDROID_GATEWAY_BOUNDARY → NODE_TOOLCHAIN_PRE → GATEWAY_TYPECHECK → GATEWAY_TEST → GATEWAY_BUILD → WEB_TEST → WEB_LINT → WEB_TYPECHECK → WEB_BUILD → NODE_TOOLCHAIN_POST → ANDROID_UNIT_ASSEMBLE_LINT → TEST_LAYER_REGISTRY_VALIDATE → FIELD_AND_RELEASE_PYTEST → GOAL_CONTROL_PYTEST → CONTROL_AND_TRACE_PYTEST → REPOSITORY_STATE`다. 마지막 명령은 정확히 다음과 같다.

```sh
: "${WALKSAFE_GATE_EVENT_ID:?required}" && python3 -B scripts/check_walksafe_project_continuation.py --root . --checkpoint docs/control/walksafe-project-continuation-checkpoint.json --print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"
```

Gate 명령은 저장소 밖 임시 디렉터리에 원출력을 모으고 전부 성공한 경우에만 event 전용 경로로 add-only 반영한다. checkpoint 자체와 `docs/control/execution/goal-gates/<event_id>/`는 실행 전 working snapshot 내용 hash에서 제외하며, 각 gate 파일은 receipt와 event가 개별 SHA-256으로 직접 결속한다. 과거 EPIC-02 Phase A currentness 생성기는 역사 기록 검증에만 쓰고 이후 구현의 live bytes를 고정하지 않는다.

`GOAL_STARTED`와 `WORK_SESSION_RESUMED`의 `repository_snapshot_before`는 해당 gate가 실행된 pre-work bytes의 불변 역사 기록이지 이후 구현 bytes를 고정하는 live lock이 아니다. 시작 뒤 통제 경로가 바뀐 상태에서 중단을 복구할 때는 runbook §2.1·§9에 따라 checkpoint의 working snapshot과 동일한 handoff hash를 현재 bytes로 reconcile한다. 새 파일·삭제가 있으면 continuation checker의 exact controlled-path manifest와 checkpoint·handoff의 경로 집합도 함께 맞추되 transition·canonical binding·상태·Goal 문서·과거 receipt는 바꾸지 않는다. 그 snapshot에서 plain continuation과 Goal graph를 포함한 전체 gate를 통과한 뒤 새 `WORK_SESSION_RESUMED`가 동일 snapshot을 결속해야 한다. event와 receipt의 snapshot 불일치는 항상 실패한다.

문맥이 끊기면 다음 순서로 재개한다.

1. 저장소 `AGENTS.md`
2. `docs/control/walksafe-project-resumption-runbook.md`
3. 이 README와 `00-master-goal.md`
4. 현재 checkpoint와 v2.2 static manifest
5. continuation checker와 Goal graph checker
6. `focus_goal_id` 문서, 최신 Gap·Backlog와 ready frontier

사용자가 명시적으로 활성화를 요청하기 전에는 준비 상태만 유지한다.
