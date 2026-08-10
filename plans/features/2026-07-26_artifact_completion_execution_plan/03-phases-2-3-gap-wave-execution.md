# 단계 2~3 - GAP 정규화와 병렬 Goal 실행

문서 ID: `WS-ARTIFACT-COMPLETION-PHASE-2-3-GAP-WAVE-20260726-001`

상태: `PLANNED`

## 1. 목표

현재 상태 감사에서 확인한 실제 GAP을 중복 없이 정리하고, 기존 v2.4 Goal 실행 체계를 유지하면서 코드·테스트·관련 산출물을 병렬 work packet으로 보완한다.

Goal graph를 다시 설계하지 않는다. 하나의 canonical focus Goal만 `IN_PROGRESS`로 두되, 그 Goal 내부의 충돌 없는 작업을 여러 에이전트가 동시에 수행한다.

## 2. GAP에서 Goal로 전환

각 내부 GAP은 다음 정보가 고정된 뒤에만 Goal 후보가 된다.

| 필드 | 내용 |
|---|---|
| GAP ID | 기존 또는 successor에서 부여한 고유 ID |
| Authority | 질문·답변·정책·요구사항 근거 |
| Current evidence | 현재 코드·산출물 경로와 hash |
| Expected state | 종료 시 관찰 가능한 상태 |
| Acceptance criteria | 자동 또는 독립 검토로 판정 가능한 조건 |
| Exact path ownership | 쓰기 가능한 경로 집합 |
| Artifact impact | 수정하거나 새로 작성할 artifact ID |
| Verification | fail-first, targeted, component, integration |
| Dependency | 선행 Goal·GAP·외부 조건 |
| Resource class | light, Python heavy, Node build, Android Gradle |
| Reviewer | 작성자와 다른 독립 역할 |

수용 기준이 모호하거나 외부 결정이 필요한 항목은 구현 Goal로 시작하지 않고 질문 또는 external action으로 분리한다.

## 3. Wave 순서

| Wave | 대상 | 선택 기준 |
|---|---|---|
| A | 현재 FP047과 P0 보안·안전 blocker | 활성 Goal과 즉시 위험 |
| B | 핵심 기능·데이터·세션·API GAP | 후행 산출물을 가장 많이 여는 작업 |
| C | Draft·Planned 산출물을 막는 내부 증거·구현 GAP | artifact unlock 수가 큰 작업 |
| D | bundle 간 정합성·통합 검증에서 발견한 corrective GAP | 최종 제출 차단 |
| E | 외부 evidence 전용 작업 | 내부 frontier가 소진되고 실제 조건이 준비된 경우 |

같은 Wave 안에서는 dependency, P0/P1, artifact unlock 수, canonical Backlog 우선순위, Goal ID 순으로 결정적으로 선택한다.

## 4. Goal 내부 병렬화

하나의 Goal을 다음 packet으로 나눈다.

| Packet | 역할 |
|---|---|
| Reproduction | 현재 bytes에서 GAP을 재현하는 fail-first 검사 |
| Product implementation | 최소 코드·설정 변경 |
| Contract impact | API·DB·manifest·설정 계약 갱신 |
| Targeted tests | 수정 동작을 직접 검증 |
| Regression selection | 영향을 받는 현재 회귀 범위 선택 |
| Artifact impact | 관련 문서·register·trace 수정 |
| Evidence assembly | 기존 v2.4가 요구하는 최소 결과 묶음 |
| Independent review | 동결된 최종 subject 판정 |

동시에 쓸 수 있는 packet은 exact path가 겹치지 않아야 한다. 공유 파일이 있으면 하나의 owner에게 묶거나 선행·후행 관계로 직렬화한다.

## 5. Goal 실행 절차

1. 현재 checkpoint와 latest Backlog에서 focus를 확인한다.
2. 새 세션이면 fresh gate와 `WORK_SESSION_RESUMED`를 만든다.
3. Goal 입력, 수용 기준, exact path, artifact impact를 동결한다.
4. fail-first 검사를 통해 현재 GAP을 재현한다.
5. 충돌 없는 구현·계약·문서 packet을 병렬 실행한다.
6. 각 packet owner가 변경 경로, 최종 hash, 검증 상태를 Coordinator에게 반환한다.
7. Coordinator가 병합 전제와 공유 계약을 확인해 하나의 후보를 만든다.
8. targeted·component·필요한 integration 검증을 수행한다.
9. 관련 산출물과 register delta를 현재 사실에 맞게 반영한다.
10. 최종 subject를 동결하고 작성자와 다른 reviewer가 판정한다.
11. 차단 finding이 있으면 같은 Goal에서 보완하거나 명확한 새 dependency GAP을 만든다.
12. 통과하면 기존 v2.4 계약의 최소 completion evidence를 만들고 Goal을 종료한다.
13. canonical update와 다음 Goal materialize·ready를 원자적으로 반영한다.
14. 질문 조건이 없으면 다음 focus를 바로 시작한다.

## 6. 완료된 Goal 처리

- 완료 Goal의 event, receipt, review, historical hash는 수정하지 않는다.
- 현재 코드가 이후 변경돼 과거 테스트가 stale해져도 과거 Goal을 재검사하지 않는다.
- 현재 통합 검사에서 새 회귀가 발견되면 새 GAP과 corrective Goal을 만든다.
- 과거 Goal 결과는 origin reference와 당시 evidence로만 연결한다.
- 현재 산출물이나 코드 수정이 필요하면 자유롭게 수정하되 새 변경 이력으로 남긴다.

## 7. 산출물 동시 반영

Goal 완료 전에 다음 질문에 모두 답한다.

- 어떤 artifact ID가 이 코드 변경의 영향을 받는가?
- 현재 설명이 새 코드와 일치하는가?
- 새 API·환경변수·권한·DB·모델 동작이 문서에 반영됐는가?
- 기존 승인 기준선이 stale해졌는가?
- 새 successor 또는 Active update가 필요한가?
- artifact register와 change log의 상태·hash가 바뀌는가?
- 실제로 하지 않은 시험이나 배포를 완료로 오표기하지 않았는가?

영향 artifact가 0이면 그 이유를 기록한다. Goal 완료가 곧 모든 관련 산출물의 최종 승인이라는 의미는 아니다.

## 8. 최소 증거 원칙

품질에 필요한 근거는 유지하되 중복 파일은 만들지 않는다.

필수:

- 채택된 start 또는 resume event
- current input digest
- 실제 변경 경로와 hash
- 검증 명령·exit code·원출력 hash
- affected artifact ID와 register delta
- 동결된 review subject digest
- 독립 review verdict
- completion receipt와 canonical transition

제한:

- 채택되지 않은 PASS attempt는 `superseded`를 명시한다.
- Goal마다 같은 형태의 새 builder·checker를 만들지 않는다.
- 기존 v2.4 계약상 필요한 FP047 builder는 현재 결과에 맞춰 사용한다.
- 이후 작업은 기존 공통 validator와 parameterized generator를 우선 사용한다.
- JSON 정본에서 생성할 수 있는 Markdown·HTML을 별도 수기 관리하지 않는다.
- checkpoint는 packet 진행률이 아니라 의미 있는 Goal 전이에만 갱신한다.

## 9. 실패와 재시도

- 실패 원출력은 덮어쓰지 않는다.
- receipt 없는 실행은 성공으로 해석하지 않는다.
- 재시도는 새 attempt ID와 새 출력 경로를 사용한다.
- 성공 attempt가 교체되면 이전 성공을 명시적으로 supersede한다.
- 동일 validator 실패를 정식 gate에서 반복하기 전에 stateless preflight로 입력 오류를 제거한다.
- 안전·보안 critical finding은 우회하거나 waiver로 내부 완료 처리하지 않는다.

## 10. 병렬 실행 중 병합 규칙

- checkpoint, canonical Gap·Backlog, artifact register는 Coordinator 한 명만 쓴다.
- reviewer는 read-only다.
- build output은 Heavy executor만 만든다.
- 하나의 writable path는 동시에 한 owner만 가진다.
- 사용자 또는 다른 작업자의 예상하지 못한 변경이 발견되면 그 경로 병합을 중단하고 확인한다.
- 에이전트 결과를 파일에 반영하기 전에 input snapshot hash와 current hash를 비교한다.
- 파일 소유권 밖 변경은 자동 채택하지 않는다.

## 11. 질문과 외부 조건

다음은 Goal을 무리하게 진행하지 않는다.

- 정책 변경
- 제품 방향 모순
- 실제 participant·device 필요
- 비밀값·유료 서비스·production 권한 필요
- 법률·개인정보·보안 외부 승인 필요
- 실제 배포·삭제·출시·인수·이관

해당 branch만 `WAITING_EXTERNAL`로 분리하고 다른 내부 ready Goal과 artifact bundle을 계속 실행한다.

## 12. 단계 완료 기준

- 내부 실행 가능 GAP에 owner와 수용 기준이 100% 있음
- 중복 GAP 0
- 순환 dependency 0
- 파일 소유권 충돌 0
- 각 완료 Goal의 관련 artifact impact 판정률 100%
- 현재 코드 기준 targeted test 누락 0
- 완료 subject의 blocking·major finding 0
- 채택 attempt가 모호한 Goal 0
- 내부 ready GAP 0 또는 다음 Wave로 명확히 편성됨
- 외부 항목이 내부 완료로 오표기된 사례 0

