# 단계 4 - 산출물 작성·통일·정합성 완성

문서 ID: `WS-ARTIFACT-COMPLETION-PHASE-4-AUTHORING-20260726-001`

상태: `PLANNED`

## 1. 목표

Draft 53개와 적용 대상 Planned 75개를 현재 정책·코드·테스트에 맞게 완성하고, 기존 승인 기준선 102개와 Active 27개를 포함한 전체 제출 후보의 용어·수치·구조·추적을 통일한다.

257개 유형을 257개 독립 파일로 억지로 분리하지 않는다. 기존 40개 bundle과 권장 형태를 사용해 중복 없이 coverage한다.

## 2. 형태별 작성 원칙

| 형태 | 수량 | 작성 원칙 |
|---|---:|---|
| CANONICAL_DOCUMENT | 120 | 하나의 승인 가능한 정본, 중복 정본 금지 |
| SECTION | 30 | 상위 canonical 문서의 명시적 section anchor로 coverage |
| REGISTER | 42 | 행 단위 add-only 또는 상태 전이, 전체 파일 복제 금지 |
| CONTROLLED_ARTIFACT | 14 | 코드·설정·fixture·dashboard definition 자체를 정본으로 사용 |
| GENERATED_EVIDENCE | 42 | 입력·generator·hash를 결속하고 수기 수정 금지 |
| EXTERNAL_RECORD | 9 | 실제 발행자·서명자 원본만 인정, 내부 작성으로 대체 금지 |

## 3. 공통 문서 계약

모든 제출 대상 문서와 register row에 필요한 범위에서 다음을 통일한다.

- artifact ID와 제목
- 문서 버전과 기준일
- 상태, 신선도, 검증 상태
- 적용 범위와 비범위
- 권위 입력과 source hash
- 요구사항·정책·코드·테스트 ID
- 구현 완료와 미실행 외부 검증의 경계
- owner, reviewer, approver 역할
- 변경 이력과 superseded 문서
- 관련 문서와 section anchor
- placeholder와 미확정 값의 명시적 상태

한글·영문 용어, 제품명, 앱 구분, 기능명, API 이름, 역할명, 날짜 형식, 단위와 수치 표기 사전을 먼저 고정한다.

## 4. 권위 용어와 제품 경계

- 제품은 Android 사용자 앱과 별도 Android 관리자 앱이다.
- Web/PWA는 `LEGACY_REFERENCE_ONLY`다.
- 승인 정책 기준선은 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`이다.
- 구현 완료, 내부 검증, 정식 시험, 실기기, 현장, 출시 적격을 구분한다.
- `NOT_RUN`, `PARTIAL`, `PASS`, `FAIL`, `WAIVED`를 혼용하지 않는다.
- 파일 존재를 artifact 완료로 해석하지 않는다.
- Goal 완료를 release 완료로 해석하지 않는다.

## 5. Draft 53개 처리

각 Draft는 다음 순서로 처리한다.

1. required contents와 현재 section coverage를 비교한다.
2. 질문·답변·정책 근거를 연결한다.
3. 구현 주장을 현재 코드·테스트에서 확인한다.
4. 오래된 PWA·계획·mock 값을 제거하거나 역사 자료로 표시한다.
5. placeholder, TODO, TBD, 예시 수치, 가짜 서명을 제거한다.
6. 외부 값이 없으면 임의로 채우지 않고 정확한 blocker를 둔다.
7. bundle 내 용어·수치·참조를 통일한다.
8. 자동 구조 검사와 독립 내용 검토를 수행한다.
9. AI가 할 수 없는 승인 상태는 검토 가능 후보로 남긴다.

Draft 완료 조건:

- 필수 heading·register field coverage 100%
- 근거 없는 핵심 주장 0
- 코드·문서 P0/P1 불일치 0
- placeholder 0
- 외부 미확정 값의 무단 추정 0
- reviewer가 판정할 수 있는 source와 evidence 보유

## 6. Planned 75개 처리

적용성 판정 뒤 처리한다.

| 판정 | 처리 |
|---|---|
| DUE_INTERNAL | 현재 입력으로 실제 내용 작성 |
| DUE_AFTER_GAP | 선행 구현·증거 Goal 뒤 작성 |
| WAITING_EXTERNAL | 필요한 실제 사건과 발행 주체를 기록하고 빈 문서를 만들지 않음 |
| N/A_CANDIDATE | 이유·재검토 trigger·승인 역할을 기록 |

실행 증거, 현장 기록, 서명 원본처럼 사건 이후에만 의미 있는 유형은 미리 빈 파일로 만들지 않는다. 대신 artifact register에 activation condition과 외부 action을 정확히 남긴다.

## 7. Bundle 병렬 작성

40개 bundle을 dependency와 공유 정본 기준으로 Wave에 배치한다.

병렬 가능한 대표 묶음:

- 프로젝트 관리·범위·요구사항
- 아키텍처·API·DB·개발자 문서
- Android 사용자·관리자 사용 흐름
- 시험계획·case·evidence register
- 보안·개인정보·권한
- AI 데이터·모델·평가
- 릴리스·운영·복구
- WalkSafe 안전·접근성·현장
- 종료·이관 조건

같은 canonical 문서나 register를 수정하는 bundle은 한 owner가 통합한다. 여러 agent가 동일 파일의 서로 다른 section을 직접 동시에 수정하지 않는다.

## 8. 코드·문서 정합성

구조 비교 대상:

- OpenAPI와 Gateway·Backend route
- Android client와 API contract
- Android manifest와 권한 문서
- DB model·migration과 데이터 사전
- 환경변수·포트·URL과 운영 문서
- packaged model과 모델 card·평가 보고서
- 화면·버튼·오류와 사용자 설명서
- 보안·개인정보 정책과 network/storage 동작

내용 비교 대상:

- 기능 목록
- 상태 전이
- 사용자·관리자 역할
- 보행 안전 한계
- 모델 class와 threshold
- 성능 수치
- 시험 개수와 수행 환경
- 알려진 제한과 release 상태

## 9. 생성과 파생 문서

- JSON·YAML 등 기계 정본에서 표·Markdown·HTML을 생성할 수 있으면 수기 중복을 금지한다.
- 생성본에는 source ID, source hash, generator, 생성 시각을 둔다.
- 생성본 수동 수정이 검출되면 정본 수정 후 재생성한다.
- diagram과 표는 동일 canonical value를 사용한다.
- PDF·DOCX·PPTX가 제출 대상이면 원본과 렌더링 결과를 모두 manifest에 연결한다.

## 10. 검토와 승인 경계

AI 에이전트가 수행 가능:

- 초안 작성
- 현재 코드 사실 확인
- schema·링크·수치·trace 검사
- 독립 기술 검토
- 내부 검토 후보 판정

실제 권한자가 필요한 항목:

- 제품 범위와 출시 결정
- 보행 안전·현장시험 승인
- 개인정보·보존·삭제 승인
- 관리자 권한 예외
- 모델 승격
- 외부 서명·검수·최종 인수·이관

작성자와 reviewer는 분리한다. 외부 승인이 없으면 가짜 이름·서명·날짜를 만들지 않는다.

## 11. 시각·형식 검사

제출 파일은 전 페이지 검사한다.

- 글자·표·이미지 잘림
- 폰트 대체와 깨진 한글
- 페이지 번호·목차·heading 일치
- 표 overflow와 중복 caption
- 저해상도 이미지
- 끊어진 링크·첨부
- 실제 개인정보·비밀값 노출
- 파일명·버전·기관명·팀명 불일치

기계 검사가 가능한 JSON, Markdown, HTML, 링크, ID, 수치는 100% 검사한다.

## 12. 단계 완료 기준

- 257개 artifact ID coverage 누락 0
- Draft 53개가 내용 완료 또는 명확한 외부 blocker
- Planned 75개가 적용성 판정과 실행 결과를 가짐
- 중복 canonical 문서 0
- 깨진 내부 참조 0
- canonical value 충돌 0
- endpoint·schema·permission·환경변수 불일치 0
- 검증되지 않은 성능 수치 0
- static test를 실기기·현장 검증으로 표현한 사례 0
- 제출 대상 placeholder·TODO·가짜 승인 0
- 실제 개인정보·비밀값 노출 0

