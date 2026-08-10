# WalkSafe Goal 실행 진입점

현재 활성화 후보 정본은 [`walksafe-completion-graph-v2-4/`](walksafe-completion-graph-v2-4/)이다. 이 패키지는 고정된 단계 개수 없이 의존성 DAG와 ready frontier로 다음 작업을 선택한다.

`walksafe-completion-graph-v2-3/` v2.3은 FP005·FP006·FP010을 완료하고 FP011을 `READY`로 만든 sequence 17에서 byte-exact 동결한 직접 predecessor다. v2.4는 완료 영수증과 materialization provenance를 보존하기 위해 v2.3까지의 20개 Goal 문서를 원래 경로·바이트로 가져오며, 전체 v2.3 checkpoint를 별도 archive로 보존한다. v2.2·v2.1·v2.0·v1.1도 과거 감사 이력이다. 과거 패키지 내부의 활성화 프롬프트·우선순위·실행 지시는 현재 지시가 아니며 실행하지 않는다.

재개 순서는 저장소 `AGENTS.md` → 프로젝트 재개 안내서 → graph-v2.4 README → imported Master → 현재 checkpoint와 v2.4 static manifest → versioned continuation/Goal graph 검사다. 현재 graph-v2.4의 `package_status`는 `PREPARED_NOT_ACTIVATED`, `activation_status`는 `READY_NOT_ACTIVATED`다. manifest `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07`과 seq1 `58c2b31637b5355609db14bf9e0779d4fd33d99f97a692866718a51fa64875d9`에 결속한 새 사용자 활성화 승인, quick gate, `PACKAGE_ACTIVATED`, 전체 시작 gate 및 `GOAL_STARTED` 전에는 FP011 제품 코드를 변경하지 않는다.

검사 명령 계약은 `2026-07-25.4`이며 quick/full 계약 SHA-256은 각각 `7b66c4610e0ad2c84e1937915704b0635b1762dc65dc06762b36e4afacdd813a`, `8c7e16f13a66398e5ba067cba0df9ba00258018f630256f6abc5b881b0467b7c`다. 검사 순서는 `CONTINUATION → GOAL_GRAPH → BASELINE_MATERIALIZATION → ANDROID_GATEWAY_BOUNDARY → NODE_TOOLCHAIN_PRE → GATEWAY_TYPECHECK → GATEWAY_TEST → GATEWAY_BUILD → WEB_TEST → WEB_LINT → WEB_TYPECHECK → WEB_BUILD → NODE_TOOLCHAIN_POST → ANDROID_UNIT_ASSEMBLE_LINT → TEST_LAYER_REGISTRY_VALIDATE → FIELD_AND_RELEASE_PYTEST → GOAL_CONTROL_PYTEST → CONTROL_AND_TRACE_PYTEST → REPOSITORY_STATE`다. 마지막 명령은 versioned continuation checker를 사용한다.

```sh
: "${WALKSAFE_GATE_EVENT_ID:?required}" && python3 -B scripts/check_walksafe_project_continuation_v2_4.py --root . --checkpoint docs/control/walksafe-project-continuation-checkpoint.json --print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"
```
