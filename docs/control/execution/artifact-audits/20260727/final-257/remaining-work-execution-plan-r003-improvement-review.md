# R003 실행계획 보완 및 개선 기록

- 일자: `2026-07-27`
- 대상: `WS-257-CLOSURE-PLAN-20260727-R002`
- 결과: `WS-257-CLOSURE-PLAN-20260727-R003`
- 상태: `PLAN_READY_FOR_PHASE0`
- 실행 경계: `PHASE0_STARTED=false / EXECUTION_STARTED=false`

## 1. 비판 판정

R002는 감사·통제 계획으로는 강했지만 바로 장기 Goal로 실행하기에는 부족했다.

| 문제 | 판정 |
|---|---|
| 257건별 완료 의미와 source-of-truth 부재 | BLOCKING |
| 외부 49건에 실제 event를 과도하게 요구할 위험 | BLOCKING |
| N/A·법무 판단이 data/model heavy 작업보다 충분히 빠르지 않음 | BLOCKING |
| `N_A_APPROVED` 호환성 확인이 너무 늦음 | BLOCKING |
| 재개 안전성과 로그아웃 후 자동 재실행 혼동 | BLOCKING |
| packet별 통제 산출물 과다 가능성 | MAJOR |
| 일정·공수·진행률 측정 기준 부재 | MAJOR |
| 문서 정합성과 코드·시험 완성도 혼동 | MAJOR |
| 고정 자원 임계값이 실제 측정에 근거하지 않음 | MAJOR |
| 133건 행별 준비 정보가 부족함 | MAJOR |

판정: `REVISE_BEFORE_EXECUTION`.

## 2. 분석 결과

- 내부 48건: `YES 29 / NO_NEW_RUN 16 / CONDITIONAL 3`.
- 외부 49건: plan/control 6, decision 9, observation 5, device 12, operation 7, acceptance 10.
- N/A 36건: 전부 개별 activation trigger와 authority receipt 필요.
- current-state/no-event 허용 후보: `SEC-17`, `OPS-17`, `OPS-19`, `CLS-14/15/16`.
- conditional real event 후보: `OPS-23`, `REL-13`, `CLS-11`.
- 증거를 위해 incident, deletion, production deployment, project shutdown을 만들면 안 된다.

## 3. R003 반영

| 개선 | 반영 내용 |
|---|---|
| Phase 0 신설 | exact-257 완료 유형, source-of-truth, 133 contract, authority, 상태어휘를 실제 작업 전에 확정 |
| readiness matrix | 열린 133건을 행별 완료 유형·실행 필요·proof·dependency로 정리 |
| 순서 재배치 | N/A 36과 data/legal 결정을 영향받는 heavy 작업보다 선행 |
| status compatibility | `N_A_APPROVED`를 Phase 0에서 승인·consumer 호환 확인 |
| minimum evidence | ledger row + evidence binding + reviewer verdict로 제한 |
| evidence reuse | 공통 raw evidence 재사용, per-ID builder/checker/receipt 복제 금지 |
| event semantics | REAL, DECISION, NO_EVENT, CONDITIONAL, ACCEPTANCE 분리 |
| canonical facts | 문서가 아닌 code/build/run/authority/event를 기능별 source-of-truth로 지정 |
| formal 279 | submission/release scope와 run/receipt/N/A 방식을 case별 확정 |
| progress | `Closure%`와 stage-credit 기반 `Execution%` 분리 |
| effort | S/M/L/XL과 외부 lead time 분리, critical path 추가 |
| resource pilot | 고정 병렬수 대신 단독 측정·안전계수·점진 증가 적용 |
| restart boundary | resume-safe만 보장하고 logout/reboot auto-relaunch는 false로 명시 |

## 4. Resource pilot 검수 반영

- 실제 작업 등급 `LIGHT`, `MODERATE`, `HEAVY`를 각각 단독 측정한다.
- canonical 산출물을 수정하지 않는 임시 출력 경계를 먼저 확정한다.
- `HEAVY`는 worker 1과 root lease로 시작한다.
- 메모리·CPU·I/O·PSI·heartbeat·OOM을 측정하고 soft throttle과 hard stop을 구분한다.
- 정상 Wave 2회마다 concurrency를 1씩 올리고 throttle이면 절반으로 낮춘다.
- daemon, timer, 별도 DB는 만들지 않는다.
- pilot은 계획 작성 중 실행하지 않았으며 실제 착수 뒤 work-contract에 따라 수행한다.

## 5. 남은 Phase 0 결정

- 사용자 승인 Q&A와 적용되는 한이음 제출 기준의 우선순위·충돌 처리
- 257건 전체 artifact kind와 completion mode 확정
- 열린 133건 provisional matrix 승인
- N/A/legal 실명 authority와 decision dependency
- named release generation과 formal 279 scope
- packet별 exact input/output/command/checker/resource/rollback
- resource pilot 대표 작업과 timeout
- 외부 lead time과 실제 critical path

## 6. 최종 경계

```text
PLAN_REVISED=true
PLAN_CRITICALLY_REVIEWED=true
READINESS_MATRIX_CREATED=true
PHASE0_STARTED=false
RESOURCE_PILOT_RUN=false
REMEDIATION_STARTED=false
EXECUTION_STARTED=false
ARTIFACT_CLOSURE_COMPLETE=false
```

R003은 실제 remediation을 시작할 계획 입력으로 사용할 수 있다. Phase 0 결정과 work-contract 확정 전에는 내부 48건 수정, 외부 사건 실행, N/A 승인, formal 시험, 제출 봉인을 시작하지 않는다.
