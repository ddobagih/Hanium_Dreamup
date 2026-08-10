# 단계 6 - 최종 제출 패키지와 외부 완료 경계

문서 ID: `WS-ARTIFACT-COMPLETION-PHASE-6-FINAL-20260726-001`

상태: `PLANNED`

## 1. 목표

내부에서 완성한 산출물과 코드 evidence를 하나의 제출 후보로 고정하고, 실제 사람·기기·권한·외부 사건이 필요한 항목을 정확히 분리한다.

최종 패키지는 파일이 많이 존재한다는 이유로 완료되지 않는다. artifact register, submission manifest, 실제 제출 bytes, 요구사항 trace, 현재 코드 evidence가 서로 일치해야 한다.

## 2. 제출 후보 범위 고정

제출 manifest에 다음을 기록한다.

- artifact ID
- 제출 파일 경로
- canonical source 경로
- section 또는 register coverage anchor
- 문서 버전과 기준일
- 파일 SHA-256
- 생성 방식과 generator version
- 연결 요구사항·정책·test·evidence
- lifecycle·freshness·verification 상태
- 외부 서명·승인 필요 여부
- superseded·비제출 보조 파일 관계

Goal gate log, 임시 audit 파일, cache, build directory, 중간 생성본은 제출 manifest에서 제외한다.

## 3. 최종 패키징 순서

1. artifact register에서 제출 대상과 비제출 근거를 확정한다.
2. canonical source를 마지막으로 동결한다.
3. 생성 산출물을 동일 입력에서 다시 생성한다.
4. DOCX·PPTX·PDF·HTML 등 제출 형식으로 렌더링한다.
5. 제출 manifest를 생성하고 모든 file hash를 기록한다.
6. manifest 외 파일이 package에 없는지 확인한다.
7. 전 페이지 시각 검사와 링크·첨부 검사를 수행한다.
8. 요구사항·코드·test evidence trace를 최종 bytes에 연결한다.
9. 비밀값·개인정보·원본 위치·서명 노출을 검사한다.
10. 독립 reviewer가 final candidate digest를 판정한다.
11. 변경이 발생하면 manifest와 rendering 검사를 처음부터 다시 수행한다.

## 4. 최종 Hard Gate

| Gate | 통과 조건 |
|---|---|
| Coverage | 257개 artifact ID가 제출·section·register·N/A·외부 대기 중 하나로 처분 |
| Required content | 제출 대상 필수 내용 coverage 100% |
| Traceability | 필수 요구사항과 artifact 연결률 100% |
| Code consistency | 핵심 문서 주장과 현재 코드·test 연결률 100% |
| Defects | 내부 P0/P1/P2 0 |
| Integrity | 제출 manifest와 실제 hash 일치율 100% |
| Rendering | 제출 파일 전 페이지 검사율 100% |
| Hygiene | placeholder·TODO·가짜 수치·비밀값·개인정보 0 |
| External honesty | 미실행 외부 검증을 완료로 주장한 항목 0 |

하나라도 실패하면 제출 후보는 `FAIL`이다. 내부에서 바로 고칠 수 있는 문제를 `CONDITIONAL`로 넘기지 않는다.

## 5. 외부 action 분리

내부 작업이 끝났지만 실제 외부 조건이 필요한 경우 action 단위로 분리한다.

| 유형 | 필요한 정보 |
|---|---|
| 실제 기기 | 기기·OS·build·test case·수집 evidence |
| 실제 사용자·관리자 | participant 역할·동의·시나리오·판정자 |
| 현장 시험 | 장소·안전계획·중단조건·관찰 evidence |
| 외부 전문검토 | 전문 역할·검토 대상 digest·필요 verdict |
| 비밀·유료 자원 | 필요한 secret 또는 서비스·사용 목적·비용 승인 |
| Production | 환경·권한·candidate hash·rollback·승인자 |
| 출시·검수·인수 | 선행 evidence·서명자·판정 범위 |

요청서는 evidence가 아니다. action 대상과 candidate digest를 고정하되 실제 receipt가 들어오기 전에는 상태를 완료로 바꾸지 않는다.

## 6. 최종 상태

### `INTERNAL_COMPLETE`

- 저장소 내부 구현·산출물·검증 작업 완료
- 내부 ready GAP 0
- 제출 패키지 내부 hard gate 통과
- 외부 action만 명확히 분리

### `SUBMISSION_READY`

- 실제 제출 요구 형식과 manifest 통과
- 필요한 내부 승인·검토 완료
- 제출에 필수인 외부 원본이 모두 연결됨

### `COMPLETE_AWAITING_EXTERNAL`

- 내부 작업은 완료
- 필수 외부 evidence가 아직 없음
- release·인수·종료 완료를 주장하지 않음

### `PROJECT_COMPLETE`

- 필수 정식 시험과 실제 evidence 완료
- 5개 release gate가 실제 근거로 closed
- 출시·배포·운영·인수·이관 또는 승인된 종료 완료
- 열린 필수 위험과 blocker 없음

## 7. 최종 독립 검토

Reviewer는 다음을 확인한다.

- 최종 subject digest가 manifest와 동일함
- source와 generated file 관계가 재현됨
- 사용자 답변과 정책이 최종 문서에 정확히 반영됨
- 코드·API·DB·모델·환경 주장이 현재 bytes와 일치함
- 미실행 시험의 경계가 모든 관련 문서에서 동일함
- P0/P1/P2 미해결이 없음
- 제출 package에 내부 운영 자료가 혼입되지 않음

작성자와 같은 agent/session이 최종 독립 reviewer를 겸하지 않는다.

## 8. 최종 인계

최종 인계에는 다음만 명확히 제시한다.

- 전체 목적과 달성 상태
- artifact 상태 합계와 257 일치
- 최종 package와 manifest 경로·digest
- 내부 완료 GAP와 외부 blocker
- 실제 수행한 검증과 미수행 검증
- 제출 또는 외부 action의 정확한 다음 행동
- 재개가 필요할 때 읽을 checkpoint와 첫 명령

기계용 원로그·대형 hash 목록은 파일에 보존하고 대화에는 요약과 경로만 제공한다.

## 9. 완료 기준

- 제출 manifest 누락 0
- manifest 외 파일 혼입 0
- 최종 hash 불일치 0
- 렌더링 오류 0
- 깨진 링크·첨부 0
- 비밀값·실제 개인정보 노출 0
- 제출 대상 placeholder 0
- 내부 P0/P1/P2 0
- 외부 action의 owner·candidate·evidence role 누락 0
- 최종 상태 과장 0

