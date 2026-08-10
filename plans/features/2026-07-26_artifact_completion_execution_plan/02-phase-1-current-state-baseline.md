# 단계 1 - 현재 코드·산출물 기준선과 신규 GAP 접수

문서 ID: `WS-ARTIFACT-COMPLETION-PHASE-1-BASELINE-20260726-001`

상태: `PLANNED`

## 1. 목표

과거 Goal을 재검사하지 않고, 현재 질문·답변·정책·코드·테스트·산출물을 한 번 대조해 최종 완성을 방해하는 실제 문제만 새 GAP 후보로 만든다.

이 단계는 FP047 보완과 읽기 전용으로 병렬 진행할 수 있다. FP047이 바꾸는 경로는 잠정 영향 대상으로 표시하고, FP047 최종 merge 뒤 hash와 판정을 확정한다.

## 2. 기준 입력

| 계층 | 기준 |
|---|---|
| 사용자 결정 | 원본 286개 답변, Android delta 75개, 후속 결정과 승인 baseline |
| 정책 | `PB-WALKSAFE-FEATURE-POLICY-1.0.1` |
| 산출물 유형 | `docs/control/artifact-types.json` |
| 산출물 상태 | `docs/deliverables/00-control/artifact-register.json` |
| 변경 이력 | DOC-05 artifact change log |
| 요구·설계 추적 | RTM, design trace, module register, planned test cases |
| 구현 GAP | 현재 유효 Gap·Backlog revision |
| 제품 현재 상태 | Android 사용자 앱, Android 관리자 앱, Gateway, Backend, 모델·설정 |
| 실행 상태 | v2.4 continuation checkpoint |

과거 Goal receipt와 result는 현재 주장의 근거 위치를 찾는 보조 입력이다. 해당 Goal의 완료 여부를 다시 판정하지 않는다.

## 3. 전수 범위

모든 257개 artifact ID에 대해 기계적으로 확인할 항목:

- ID와 register 단일 등록
- canonical path와 파생 경로
- 파일 존재·파싱·열기 가능 여부
- lifecycle, freshness, verification 상태
- source baseline과 hash
- requirement·test·evidence 연결
- 내부 링크·첨부·이미지 경로
- 적용성·activation condition·N/A 근거
- 제출 포함 여부
- 중복 정본과 생성본 수동 수정 여부

상태별 의미 검토:

| 현재 상태 | 검토 방식 |
|---|---|
| 승인 기준선 102 | 전수 구조·hash·링크·canonical value 검사, 현재 코드 영향이 있거나 충돌이 검출된 항목만 깊은 의미 검토 |
| Active 27 | 현재 코드·운영 사실과 currentness 전수 확인 |
| Draft 53 | 필수 내용, 근거, trace, placeholder, 검토 준비도 전수 확인 |
| Planned 75 | activation condition과 현재 프로젝트 사실을 대조해 DUE, WAITING_EXTERNAL, N/A 후보로 판정 |

최종 통합 단계에서는 상태와 관계없이 제출 대상 전체를 다시 교차 검사한다. 이 단계에서 승인 기준선을 무조건 신뢰해 오류를 숨기지 않는다.

## 4. 병렬 감사 lane

| Lane | 범위 | 주요 교차검사 |
|---|---|---|
| B1 관리·요구 | DOC, MGT, DSC, REQ | 답변·범위·일정·인수조건 |
| B2 설계·개발 | DES, DEV | 아키텍처·API·DB·모듈·코드 |
| B3 시험·증거 | TST | 시험계획·실제 결과·NOT_RUN 경계 |
| B4 보안·개인정보 | SEC | 동의·권한·보존·삭제·감사 |
| B5 AI·모델 | AIML | 모델 hash·class·threshold·성능 수치 |
| B6 릴리스·운영 | REL, OPS | 환경·배포·복구·운영 owner |
| B7 WalkSafe·종료 | WS, CLS | 안전·현장·접근성·이관·종료 조건 |

각 lane은 read-only로 조사하고 동일 artifact ID나 공유 canonical value를 직접 수정하지 않는다. Coordinator가 finding을 중복 제거한 뒤 쓰기 Goal을 편성한다.

## 5. Q&A와 요구사항 대조

1. 질문·답변·후속 결정의 기존 canonical ID를 우선 사용한다.
2. 기존 ID가 없는 필수 문장만 새 추적 ID 후보로 등록한다.
3. 답변이 바뀐 경우 최신 승인 결정 하나만 active authority로 사용한다.
4. 필수, 권장, 가정, 미확정, 범위 밖을 구분한다.
5. 필수 요구사항마다 수용 기준과 관련 산출물을 확인한다.
6. 구현 요구사항은 코드와 테스트 근거를 모두 확인한다.
7. 구현이 필요 없는 관리·외부 요구사항은 문서·운영·외부 evidence를 연결한다.
8. 현재 코드나 과거 문서가 사용자 정책을 역으로 변경하지 못하게 한다.

## 6. 코드·산출물 양방향 확인

문서에서 코드로 확인:

- API endpoint, method, schema, 오류 계약
- Android permission, foreground/background, 화면·버튼·접근성
- 가입·로그인·동의·탈퇴·보호자·관리자 흐름
- 보행 세션, 위험 안내, 음성·진동, 안전 정지
- 환경변수, 포트, URL, 배포·복구 제한
- DB migration, 개인정보 수집·보존·삭제
- 모델명, class, 입력 크기, threshold, 성능 수치
- 테스트 개수와 실제 수행 환경

코드에서 문서로 확인:

- 공개 route와 client 호출
- 사용자·관리자에게 보이는 동작과 오류
- 보안·개인정보 분기
- 필수 환경변수와 기본값
- DB schema와 migration
- 외부 provider와 production 의존성
- feature flag, fallback, stub
- 테스트에서만 존재하고 제품 코드에는 없는 보장

## 7. GAP 접수 조건

다음 항목이 모두 있어야 새 GAP으로 접수한다.

- 현재 코드 또는 산출물의 구체적인 경로
- 사용자 결정·정책·요구사항 근거
- 관찰된 실제 불일치
- 사용자·안전·보안·제출 영향
- 재현 또는 판정 방법
- 종료 가능한 수용 기준
- 관련 artifact ID와 예상 수정 경로
- 중복 GAP 여부

다음은 GAP으로 만들지 않는다.

- 과거 Goal 문서의 형식 차이만 있는 경우
- 현재 결과에 영향 없는 오래된 receipt 보증 수준 차이
- 사용자 요구와 무관한 예방적 리팩터링
- 구체적인 증거가 없는 추측성 개선
- 외부 사건이 아직 발생하지 않아 정상적으로 `NOT_RUN`인 항목

## 8. GAP 분류

| 분류 | 처리 |
|---|---|
| IMPLEMENTATION | 코드·설정·테스트 보완 Goal |
| DOCUMENTATION | 현재 사실을 반영하는 산출물 bundle 작업 |
| CODE_DOCUMENT_CONFLICT | 코드 또는 문서 중 권위 기준과 어긋난 쪽을 함께 수정 |
| EVIDENCE | 실제 검증 실행 또는 근거 연결 |
| EXTERNAL | 사용자·기기·권한·외부 검토 action queue |
| DUPLICATE | 기존 활성 GAP으로 병합 |
| REJECTED | 근거와 함께 기각 |

P0 안전·보안·개인정보·데이터 손실 위험, P1 필수 요구 누락, P2 비핵심 불일치, P3 표현·배치 문제로 심각도를 나눈다.

## 9. 산출물 적용성 판정

Planned 75개는 다음 중 하나로 판정한다.

- `DUE_INTERNAL`: 현재 저장소와 승인 정책만으로 작성 가능
- `DUE_AFTER_GAP`: 내부 구현 GAP 완료 뒤 작성 가능
- `WAITING_EXTERNAL`: 실제 기기·사용자·외부 승인·운영 사건 필요
- `NOT_APPLICABLE_CANDIDATE`: activation condition이 충족되지 않음

N/A는 AI가 최종 승인하지 않는다. 이유, 판정 근거, 재검토 trigger, 승인 필요 역할을 남긴 후보 상태로 둔다.

## 10. 단계 산출물

이 단계에서 새 관리 체계를 만들지 않는다. 다음 최소 결과만 다음 유효 revision 또는 실행 packet에 반영한다.

- 중복 제거된 GAP 후보 목록
- artifact ID별 현재 상태·적용성·영향 분류
- 코드·산출물 conflict 목록
- 외부 action 목록
- Wave별 파일 소유권 후보
- 현재 register와 Gap·Backlog successor에 반영할 delta

JSON·Markdown·HTML을 각각 수기로 만들지 않는다. 기계 정본이 필요한 경우 JSON 하나를 만들고 사람용 표는 결정론적으로 생성한다.

## 11. 완료 기준

- 257개 artifact ID가 정확히 한 번 분류됨
- artifact 상태 합계가 257
- canonical path·hash 누락 0
- 파싱·열기 실패 0 또는 명확한 repair GAP
- 깨진 내부 참조 0 또는 명확한 repair GAP
- 필수 Q&A·요구사항 분류율 100%
- 필수 요구사항의 artifact 연결률 100%
- 구현 요구사항의 코드·테스트 연결 상태 100% 판정
- GAP 후보의 중복·기각·외부·내부 처분율 100%
- 다음 실행 Wave의 첫 focus와 병렬 packet이 결정됨

