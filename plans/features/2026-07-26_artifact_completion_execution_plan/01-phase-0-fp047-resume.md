# 단계 0 - FP047 재개와 완료

문서 ID: `WS-ARTIFACT-COMPLETION-PHASE-0-FP047-20260726-001`

상태: `PLANNED`

## 1. 목표

중단된 FP047을 현재 v2.4 checkpoint에서 안전하게 재개하고, 독립 검토에서 확인된 동시성·권한 경계 결함을 실제 코드와 테스트로 해소한 뒤 관련 산출물과 GAP-056을 현재 사실에 맞게 갱신한다.

FP047은 현재 `IN_PROGRESS`다. 기존 `review-subject.json`은 완료 승인이 아니며, 독립 review·attestation·completion receipt가 없으므로 완료로 취급하지 않는다.

## 2. 시작 입력

| 입력 | 역할 |
|---|---|
| 현재 continuation checkpoint v1.21.0 | 활성 Goal, event tail, working snapshot 정본 |
| FP047 Goal 문서 | 정확한 정책·GAP·완료 경계 |
| FP047 start event `...FP047-20260726-005` | 현재 작업 세션의 역사적 시작 근거 |
| 기존 review subject | 이전 구현 후보와 검증 범위 |
| 독립 review finding | 새 보완 작업의 fail-first 입력 |
| 정책 기준선과 r020 Gap·Backlog | 정책 경계와 successor 입력 |

새 세션에서 제품 파일을 수정하기 전에는 현재 v2.4 재개 절차에 따라 working snapshot을 조정하고 새 event ID의 전체 resume gate와 `WORK_SESSION_RESUMED`를 완료한다. 과거 gate 출력과 event ID는 재사용하지 않는다.

## 3. 확인된 차단 사항

| ID | 문제 | 영향 |
|---|---|---|
| FP047-LOCK-01 | lock 경로를 비운 뒤 inode를 확인하는 구간에서 새 owner lock을 제거할 수 있음 | 3개 프로세스가 critical section에 겹쳐 진입 가능 |
| FP047-AUTH-01 | 고위험 action 분류가 middleware에서 중앙 강제되지 않음 | endpoint별 누락 시 일반 bearer만으로 보호 작업 진입 가능 |
| FP047-RACE-01 | nonce·TOTP·recovery code 검증이 순차 테스트 중심 | one-time 보장이 실제 DB 경합에서 입증되지 않음 |
| FP047-RACE-02 | recovery·session revoke와 보호 작업의 transaction 경합 미검증 | 폐기 또는 복구 뒤 보호 작업이 실행될 위험 |

## 4. 병렬 work packet

| Packet | 소유 범위 | 작업 | 선행조건 |
|---|---|---|---|
| P0-A Lock fail-first | `exclusive-file-lock.ts`, 대응 test | 결정적 3-process 경합 재현, 새 owner 삭제·중복 진입 검출 | resume gate |
| P0-B Lock 구현 | P0-A와 같은 파일 | 소유권을 action 전체에 유지하는 원자 primitive 적용 | P0-A 실패 재현 |
| P0-C Backend authorization | security service, middleware, reports API | 고위험 action과 control/session 검사를 fail-closed로 중앙 강제 | resume gate |
| P0-D PostgreSQL concurrency | backend security test | 두 connection·transaction·barrier 기반 nonce/recovery/revoke 경합 검증 | P0-C 계약 확정 |
| P0-E Contract·AdminApp 영향 | OpenAPI, admin client/controller 관련 경로 | API 계약과 관리자 앱 동작이 변경 계약과 일치하는지 보완 | P0-C |
| P0-F Artifact·trace 영향 | FP047 기존 builder·현재 result 후보 | 최종 코드 hash, 검증 결과, GAP successor, review subject 재생성 | P0-A~E 완료 |
| P0-G Independent review | read-only exact subject | lock, authorization, concurrency, 산출물 경계를 독립 판정 | P0-F subject 동결 |

P0-A와 P0-B는 같은 파일 소유권을 사용하므로 직렬 실행한다. P0-C와 P0-A는 파일이 겹치지 않으면 병렬 실행한다. P0-D는 P0-C의 공개 transaction 계약이 고정된 뒤 시작한다.

## 5. Lock 보완 계획

1. 기존 replacement 보존 테스트만으로 충분하다고 판단하지 않는다.
2. stale owner 정리, 새 acquire, path replacement가 겹치는 순서를 barrier로 고정한다.
3. 동시에 실행 중인 critical action 수의 최댓값이 정확히 1인지 검증한다.
4. live owner는 제거되지 않고, 죽은 owner만 회복되는지 검증한다.
5. 단순 `lstat(path) -> unlink(path)` 재검사만 추가하는 패치는 허용하지 않는다.
6. 동일 UID의 협조적 프로세스까지가 위협 경계인지, 악의적 동일 UID도 포함하는지 명시한다.
7. 권장 방향은 action 동안 descriptor에 결속되는 OS 수준 lock이다.
8. Node 표준 경로만으로 경계를 충족하지 못하면 검증된 native helper 또는 단일 broker를 선택하되, 의존성 확대는 독립 검토에서 따로 확인한다.

완료 기준:

- 3-process 경합에서 중복 진입 0
- 새 owner lock 삭제 0
- live owner 오회복 0
- 프로세스 종료 뒤 회복 가능
- 위협 경계와 비보장 범위가 코드·산출물에 동일하게 기록됨

## 6. Backend authorization·동시성 계획

1. 고위험 action 분류 결과를 middleware에서 폐기하지 않는다.
2. 보호 작업마다 action identity, session, control state, 재확인 proof를 서버에서 검증한다.
3. proof 소비와 보호 작업 사이에서 recovery 또는 revoke가 완료되면 보호 작업을 거부한다.
4. 보호 transaction이 필요한 lock을 획득한 뒤에는 동일 transaction 종료까지 control/session 상태가 바뀌지 않게 lock 순서를 고정한다.
5. lock 순서는 모든 endpoint에서 `control -> session -> reconfirmation`으로 통일한다.
6. 보호 작업이 실패해도 이미 소비된 one-time proof를 다시 사용할 수 없게 한다.
7. export처럼 응답 생성 시간이 긴 경로도 민감 query 결과와 audit 경계를 같은 transaction 계약으로 설명한다.
8. mock 순서 테스트와 별도로 격리 PostgreSQL 두 connection을 사용하는 실제 경합 검사를 추가한다.

필수 경합:

- 동일 nonce 동시 소비
- TOTP replay 동시 요청
- recovery code 동시 사용
- proof 소비 직후 recovery
- proof 소비 직후 session revoke
- 보호 transaction 중 recovery 대기
- 보호 작업 rollback 뒤 nonce 재사용

완료 기준:

- 동일 one-time proof 성공 요청 정확히 1개
- 보호 mutation과 성공 audit 정확히 1개
- revoke/recovery 선행 시 보호 callback 0회
- deadlock과 timeout 0
- endpoint별 중앙 보호 누락 0

## 7. 검증 순서

1. Lock fail-first test
2. Backend fail-first concurrency test
3. Targeted Gateway test
4. Focused admin security test
5. 격리 PostgreSQL concurrency test
6. Backend 내부 회귀
7. Gateway typecheck·test·boundary
8. AdminApp unit·assemble·lint
9. OpenAPI 생성 일치 검사
10. FP047 trace 결정성 검사
11. 최종 subject 독립 검토

검증은 최종 implementation content set이 고정된 뒤 새 원출력으로 실행한다. 수정 전 PASS 로그나 이전 subject hash를 최종 근거로 재사용하지 않는다.

## 8. 산출물 영향

FP047 완료 전 다음을 현재 코드와 일치시키되 기존 immutable evidence를 덮어쓰지 않는다.

- implementation record
- verification result
- GAP-056과 Backlog successor
- Active ledger overlay
- successor trace
- review subject
- independent review와 attestation
- completion receipt
- artifact register와 change log의 실제 영향 항목

정식 시험, 실제 PostgreSQL 외부 통합, 실제 사용자·관리자·기기, 복구 훈련, 외부 보안·법무·개인정보·접근성 검토, production 배포와 release gate는 실제 evidence가 없으면 계속 `NOT_RUN` 또는 `NOT_ELIGIBLE`이다.

## 9. 완료 전이

다음 조건을 모두 만족하기 전에는 FP047 completion receipt와 successor Goal 전이를 만들지 않는다.

- Lock blocking finding 0
- Backend major finding 0
- 실제 PostgreSQL 경합 검증 PASS
- 최종 검증 로그가 최종 코드 hash에 결속
- 독립 reviewer가 `APPROVED`
- GAP-056 재평가와 관련 산출물 반영 완료
- v2.4 continuation·Goal graph 계약 충족

통과 뒤에만 canonical binding update, FP047 complete, 다음 Backlog action의 Goal materialize·ready, checkpoint 갱신을 한 논리적 commit으로 수행한다.

## 10. 중단 복구

- packet별 exact path와 마지막 안전 hash를 Coordinator에게 반환한다.
- checkpoint는 개별 packet 완료 때 갱신하지 않는다.
- receipt 없는 실행은 PASS로 해석하지 않는다.
- 강제 종료 뒤 기존 event ID·로그 경로를 재사용하지 않는다.
- 새 owner가 경로 hash를 확인하기 전에는 중단 packet 결과를 채택하지 않는다.
- FP047이 완료되지 않았으면 다음 세션의 첫 작업은 항상 FP047 재개다.

