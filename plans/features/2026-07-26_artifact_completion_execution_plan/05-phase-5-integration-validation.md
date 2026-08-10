# 단계 5 - 현재 제품·산출물 통합 검증과 corrective Goal

문서 ID: `WS-ARTIFACT-COMPLETION-PHASE-5-INTEGRATION-20260726-001`

상태: `PLANNED`

## 1. 목표

개별 Goal과 bundle에서 완성한 현재 코드·테스트·산출물을 하나의 제품 후보로 검증하고, 새 회귀나 문서 모순이 발견되면 과거 Goal을 다시 열지 않고 corrective GAP과 Goal로 닫는다.

이 단계는 Goal 이력 감사가 아니다. 과거 Goal 이름과 관계없이 현재 코드와 현재 제출 후보만 검사한다.

## 2. 검증 계층

| 계층 | 목적 | 실행 시점 |
|---|---|---|
| Fail-first | GAP이 실제로 존재함을 재현 | Goal 구현 전 |
| Targeted | 변경 동작 직접 검증 | packet merge 전 |
| Component | 앱·서비스 내부 회귀 | Goal 완료 전 |
| Contract | API·DB·manifest·config 일치 | 관련 Goal 완료 전 |
| Integration | Android·Gateway·Backend·model 연결 | Wave 완료 뒤 |
| Artifact | 구조·trace·링크·canonical value | bundle 완료 뒤 |
| Rendering | 제출 파일 시각·형식 | 최종 후보마다 |
| Formal·device·field | 승인 시험계획과 실제 환경 | 조건 충족 뒤 |

테스트는 현재 코드 회귀를 확인하기 위해 과거에 추가된 테스트도 실행할 수 있다. 이는 과거 Goal을 재검사하는 것이 아니라 현재 제품을 검사하는 것이다.

## 3. 영역별 통합 묶음

| 묶음 | 핵심 확인 |
|---|---|
| Identity·Security | 사용자·관리자 로그인, 권한, session, audit, recovery |
| Walk lifecycle | 시작·중지·복구·background·다중 기기 |
| Safety·Accessibility | 위험 안내, 점자블록, TTS, TalkBack, 안전 정지 |
| Android·Gateway | client contract, retry, offline, auth rotation |
| Backend·Data | API, DB, migration, transaction, retention |
| AI·Runtime | model hash, class, threshold, fallback, 성능 |
| Privacy | 동의, 수집, 전송, 보존, 삭제, 원본 데이터 |
| Release·Operations | 환경, 배포, rollback, monitoring, owner |
| Documentation | 요구·설계·시험·사용자·발표자료 canonical value |

## 4. 코드·계약 검사

- Gateway와 Backend route를 OpenAPI와 비교한다.
- Android client method와 request/response schema를 비교한다.
- Android permission과 사용자 설명을 비교한다.
- DB model과 migration·데이터 사전을 비교한다.
- 환경변수와 운영·배포 문서를 비교한다.
- packaged model hash와 runtime config·모델 card를 비교한다.
- 개인정보 문서와 실제 network/storage 경로를 비교한다.
- public 화면·버튼·오류·fallback이 사용자 문서에 반영됐는지 확인한다.

## 5. Evidence 유효성

유효한 evidence 조건:

- 명령과 실행 환경이 식별됨
- exit code와 원출력 hash가 있음
- 대상 코드·설정 content hash가 있음
- 실행 시점이 최종 수정 이후임
- mock, static, local, device, field, formal 범위가 구분됨
- 작성자와 reviewer가 구분됨

무효 처리:

- 최종 수정 전 로그
- 다른 code hash를 대상으로 한 PASS
- receipt만 있고 원출력·대상 hash가 없는 주장
- skipped·mock·부분 성공을 전체 PASS로 표현한 결과
- receipt 없는 중단 실행
- 외부 시험을 내부 테스트로 대체한 결과

## 6. 결함 등급

| 등급 | 정의 | 처리 |
|---|---|---|
| P0 | 허위 제출, 개인정보·보안·안전 위험, 실행 불가 | 즉시 corrective Goal, 완료 차단 |
| P1 | 필수 요구 누락, 핵심 코드·문서 불일치 | 제출 차단, 같은 Wave에서 수정 |
| P2 | 비핵심 기능·수치·용어 불일치 | 최종 후보 전 수정 |
| P3 | 오탈자·배치·경미한 표현 | bundle 정리에서 수정 |

P0/P1은 waiver로 내부 완료 처리하지 않는다. 외부 권한이 필요한 P0/P1은 명확한 external blocker로 남기고 완료를 주장하지 않는다.

## 7. Corrective Goal 규칙

통합 검사에서 새 문제가 발견되면 다음 순서로 처리한다.

1. 현재 코드·산출물에서 재현한다.
2. 기존 활성 GAP과 중복인지 확인한다.
3. 새 GAP이면 authority, evidence, 수용 기준, artifact impact를 기록한다.
4. ready frontier와 dependency에 맞는 corrective Goal을 만든다.
5. 현재 focus Goal이 있으면 그 완료 경계를 침범하지 않게 순서를 정한다.
6. 구현·test·artifact update·독립 review를 수행한다.
7. 완료 Goal 이력은 수정하지 않는다.
8. 수정 뒤 해당 integration 묶음을 다시 검사한다.

## 8. 검증 실행과 자원

- Android Gradle과 전체 start/resume gate는 동시에 하나만 실행한다.
- Node/Web build도 heavy lane에서 직렬화한다.
- Python heavy test는 자원 Green 상태에서 최대 2개다.
- heavy 검증 중에도 read-only artifact review와 다음 corrective packet 설계는 계속한다.
- 전체 gate 실행 중에는 source write를 중지해 대상 snapshot을 고정한다.
- 실패한 전체 gate를 그대로 반복하지 않고 실패 계층만 먼저 보완한다.

## 9. 묶음 검사 주기

- P0/P1 Goal 완료 직후 관련 integration 묶음
- 동일 코드 영역 Goal 3~5개 완료 뒤 component 묶음
- artifact bundle 완료 뒤 구조·내용 교차검사
- Wave 종료 뒤 전체 contract와 current regression
- 최종 package 생성 직전 전체 내부 검사
- 최종 package bytes 고정 뒤 rendering·manifest 검사

개별 packet마다 전체 19-command gate를 반복하지 않는다. 의미 있는 Goal 전이와 최종 후보 경계에서만 실행한다.

## 10. 내부 완료와 외부 검증 분리

다음은 내부 PASS로 대체하지 않는다.

- 279개 정식 시험
- 실제 Android 기기
- 실제 사용자·관리자
- 통제된 실외 현장
- production PostGIS·외부 provider
- 5개 release gate
- 외부 보안·법무·개인정보·접근성 검토
- production 배포·canary·rollback

내부 검증이 끝나도 실제 evidence가 없으면 `NOT_RUN`, `NOT_VERIFIED`, `NOT_ELIGIBLE`을 유지한다.

## 11. 단계 완료 기준

- 현재 코드 targeted·component 필수 검사 PASS
- API·DB·manifest·config contract 불일치 0
- P0/P1/P2 내부 결함 0
- 필수 문서 주장 evidence 연결률 100%
- 공개 제품 동작 문서화율 100%
- 최종 코드 이후 생성된 evidence 비율 100%
- 깨진 artifact trace 0
- bundle 간 canonical value 충돌 0
- corrective GAP의 미처분 항목 0
- 외부 미검증 결과의 허위 PASS 0

