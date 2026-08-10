# WalkSafe 설치·사용·관리·인도

> Draft로 작성된 산출물: REL-16, REL-17, REL-18, REL-19, REL-22  
> 버전: 0.1.0 · 승인: 미승인  
> 정책 기준선: PB-WALKSAFE-FEATURE-POLICY-1.0.0  
> 실제 실행 증거: 없음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

Android 제품을 설치·사용·관리·시연·인계할 때 무엇을 설명하고 어떤 외부 확인이 필요한가?

## 쉬운 요약

이 문서는 앞으로 해야 할 일과 판단 기준을 정리한 초안입니다. 절차나 빈 원장을 만들었다고 해서 릴리스·운영·종료가 실행된 것은 아닙니다. 실제 실행·외부 서명·결과는 이름 붙인 대상과 원본 증거가 생긴 뒤 별도 기록합니다.


## 현재 경계

- 현행 정식 제품 경계: Android 사용자 앱 + 별도 비공개 Android 관리자 앱
- Web/PWA: 재승인 전까지 레거시 참고이며 Android 합격·출시 근거가 아님
- 승인된 진행 순서: 개발 → 2026-07-26 통제 시연 → 베타 → 정식 출시. 통제 시연은 릴리스가 아님
- 배포 방향: 관리형 cloud의 staging 우선. 정해진 전체 프로젝트 예산은 없음
- FP-035: 정규화 지시 포착·묶음 승인 대기. 정정 후보 NOT_APPROVED/NOT_EFFECTIVE. 보행 중 미전송, 정지 뒤 이동통신망 명시 선택 시 이동통신망 허용, 미선택 시 Wi-Fi만 허용. 관련 시험 NOT_RUN
- 남은 gate: 5개 모두 NOT_RUN·미면제
- 출시: `NOT_ELIGIBLE`

## 이 묶음에서 아직 만들지 않은 결과

- REL-20 인수인계서 — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN
- REL-21 검수·승인 기록 — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN

## 공통 보관 원칙

민감한 위치·영상·음성·서명·운영 원본은 Git에 넣지 않습니다. Git에는 통제 저장소 ID, SHA-256, 권한, 보존기간, 실행·검토 상태만 남깁니다. 실제 결과가 생기면 template을 복사한 새 instance를 append-only로 보관하고 이 정본에는 포인터만 연결합니다.

<a id="rel-16"></a>
## REL-16 설치 설명서

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 운영자 또는 사용자가 지원 환경에 릴리스를 정확히 설치·초기화·검증하게 한다. |
| 적용 조건 | 운영자가 직접 설치·구성해야 하는 인도물이 있는 경우 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/delivery-and-handover.md#rel-16` |
| 선행 유형 | REL-06, REQ-14 |
| 후행 유형 | REL-19, WS-16 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 운영자가 직접 설치·구성해야 하는 인도물이 있는 경우 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 지원 환경·전제, 다운로드·무결성 확인.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- Android 사용자 앱과 별도 비공개 Android 관리자 앱의 지원 OS·권한·설치원·버전 확인을 구분하며 Web/PWA 설치는 현행 범위가 아니다.

### 포함 내용과 필요한 입력

**포함 내용**

- 지원 환경·전제
- 다운로드·무결성 확인
- 설치·설정 단계
- 권한·TLS·DB 준비
- 첫 실행·health 확인
- 제거·문제 해결

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 지원 환경·전제, 다운로드·무결성 확인.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="rel-17"></a>
## REL-17 사용자 설명서

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 보행 사용자가 핵심 기능·권한·안전 제한·오류 복구를 접근 가능한 표현으로 이해하게 한다. |
| 적용 조건 | 외부 사용자에게 MVP·beta·release를 제공할 때 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/delivery-and-handover.md#rel-17` |
| 선행 유형 | DES-15, REL-09, WS-22 |
| 후행 유형 | REL-19 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 외부 사용자에게 MVP·beta·release를 제공할 때 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 시작·권한·동의, 카메라·위험 안내.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 사용자 선택은 RAW_SOURCE_COLLECTION, AUTOMATIC_REPORTING, MOBILE_NETWORK_TRANSFER, TRAINING_REUSE 네 항목으로 독립한다. Android 권한, 로그인, 다른 선택 하나가 나머지 동의를 대신하지 않는다.
- 일반 활동원본은 보행 중 서버로 전송하지 않는다. 정지 판정 뒤 MOBILE_NETWORK_TRANSFER를 선택한 경우에만 이동통신망 전송을 허용하고, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다.
- 철회는 해당 선택의 새 수집·보고·전송·학습 재사용을 중단하고 대기 작업을 차단한다. 삭제는 별도 요청과 서버 처리 증거가 필요하며, 재동의는 새 version과 시각으로 기록하고 과거 처리나 삭제 요청을 소급 취소하지 않는다.
- 철회·삭제·재동의의 기술 동작과 법적 보유기간·예외·최종 한국어 동의 문안은 구분한다. 법적 보유기간과 최종 문안은 외부 검토·승인 전까지 NOT_APPROVED다.
- TMAP 목적지·위치 처리에는 제3자 제공/처리 관계가 있다. 국외이전 여부, 계약상 역할, 고지·동의 문구는 외부 계약·법률 검토 전 확정하지 않는다.
- W4의 formal 279, 실기기, TMAP live, 배포는 NOT_RUN이다. W5의 열린 finding은 유지하며 signing/deployment 평가는 NOT_ASSESSED다.
- W7은 exact 3개 모델 등록 완전성만 확인한다. 모델 평가는 NOT_RUN, 동등성 및 출시 승인은 NOT_APPROVED이며 전체 출시는 NOT_ELIGIBLE이다.
- Web/PWA와 unsigned APK는 정식 Android release나 설치·배포·사용자 검증 증거가 아니다.

### 포함 내용과 필요한 입력

**포함 내용**

- 시작·권한·동의
- 카메라·위험 안내
- 목적지·음성·신고
- 설정·접근성
- 오프라인·오류 복구
- 안전 제한·개인정보 권리

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 시작·권한·동의, 카메라·위험 안내.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="rel-18"></a>
## REL-18 관리자 설명서

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 검수·상태변경·export·계정·감사 업무를 권한과 개인정보 원칙에 맞게 수행하게 한다. |
| 적용 조건 | 관리자 기능을 운영 조직에 제공할 때 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/delivery-and-handover.md#rel-18` |
| 선행 유형 | DES-19, REL-09 |
| 후행 유형 | REL-19, REL-20 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-008, FP-033<br>결정 DEC-ADMIN-FUNCTION-SCOPE, DEC-APP-SEPARATION, DEC-AUTO-REPORT-FEEDBACK, DEC-BACKOFFICE-FAILURE, DEC-IBQ-011, DEC-IBQ-017, DEC-IBQ-100, DEC-IBQ-105, DEC-IBQ-138<br>요구 RQ-FP-008-001, RQ-FP-033-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 관리자 기능을 운영 조직에 제공할 때 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 관리자 접속·RBAC, 신고 조회·검증·변경.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 단일 관리자 계정은 MFA 또는 패스키를 사용하고 복구수단을 관리자 휴대전화 밖에 보관하며 고위험 작업 동결·세션 폐기 절차를 설명한다.

### 포함 내용과 필요한 입력

**포함 내용**

- 관리자 접속·RBAC
- 신고 조회·검증·변경
- 필터·중복·export
- 감사로그·개인정보
- 오류·에스컬레이션
- 금지 행위·정기 점검

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 관리자 접속·RBAC, 신고 조회·검증·변경.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="rel-19"></a>
## REL-19 교육·시연 자료

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 사용자·운영자·심사자가 실제 범위와 제한을 안전한 시나리오로 학습하게 한다. |
| 적용 조건 | 교육·심사·시연 세션을 수행하는 경우 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/delivery-and-handover.md#rel-19` |
| 선행 유형 | REL-16, REL-17, REL-18 |
| 후행 유형 | 없음 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 교육·심사·시연 세션을 수행하는 경우 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 대상·학습 목표, 시연 환경·가상 데이터.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 시연은 실제 build와 명확히 연결하고 미실행 시험이나 mock 화면을 운영 완료 증거로 제시하지 않는다.

### 포함 내용과 필요한 입력

**포함 내용**

- 대상·학습 목표
- 시연 환경·가상 데이터
- 단계별 script
- 접근성·안전 주의
- 질문·평가
- 버전·사실 검증

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 대상·학습 목표, 시연 환경·가상 데이터.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="rel-20"></a>
## REL-20 인수인계서

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 통제된 인수인계 package로 제품·환경·계정·운영 책임을 설명하고, 수령 조직의 서명 확인을 외부 원본 기록으로 보존한다. |
| 적용 조건 | 팀·조직·사용자 사이에 운영 책임을 공식 이관하는 경우 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/delivery-and-handover.md#rel-20` |
| 선행 유형 | OPS-01, OPS-02, REL-02, REL-10, REL-18 |
| 후행 유형 | CLS-10, CLS-13, CLS-15, REL-21 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 팀·조직·사용자 사이에 운영 책임을 공식 이관하는 경우 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 서명·발행된 외부 원본은 Git 밖 통제 저장소에 두고 발행자·시각·저장 ID·SHA-256·효력상태를 기록한다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 인계 범위·버전, 산출물·저장소·접근.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 운영 수신자·인계 범위·권한·미해결 항목이 확정되고 실제 인계가 이뤄진 뒤 외부 원본으로 작성한다.

### 포함 내용과 필요한 입력

**포함 내용**

- 인계 범위·버전
- 산출물·저장소·접근
- 환경·계정·secret 이관
- 운영·지원·연락
- 미해결 결함·위험
- 양측 확인·일시
- 통제된 인수인계 package version과 수령자 서명 external receipt 링크
- canonical package approver와 external receipt signer·issuer·signed_at·원본 SHA-256의 별도 instance 필드

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 인계 범위·버전, 산출물·저장소·접근.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실행·발행 원본은 수정하지 않는다. 정정은 새 instance·시각·hash와 이전 원본 관계를 추가한다.

<a id="rel-21"></a>
## REL-21 검수·승인 기록

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 특정 release와 인도 산출물이 manifest·검수 기준을 충족하는지 지정 검수자가 확인한 판정·조건·서명을 보존한다. |
| 적용 조건 | 계약·사업·외부 인도에서 formal acceptance가 필요한 경우 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 지정 검수자 |
| 정본 | `docs/deliverables/09-release/delivery-and-handover.md#rel-21` |
| 선행 유형 | REL-02, REL-13, REL-14, REL-15, REL-20 |
| 후행 유형 | CLS-01, CLS-02 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 계약·사업·외부 인도에서 formal acceptance가 필요한 경우 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 서명·발행된 외부 원본은 Git 밖 통제 저장소에 두고 발행자·시각·저장 ID·SHA-256·효력상태를 기록한다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 검수 대상·version, 기준·절차·환경.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 지정 검수자가 실제 release와 근거를 확인한 뒤 서명하며 서명 원본은 Git 밖 통제 저장소에 보관한다.

### 포함 내용과 필요한 입력

**포함 내용**

- 검수 대상·version
- 기준·절차·환경
- 항목별 결과·증거
- 불일치·조건·기한
- 검수자·승인자
- 서명·효력·재검수
- 외부 지정 검수자·검수기관·서명·효력일

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 검수 대상·version, 기준·절차·환경.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실행·발행 원본은 수정하지 않는다. 정정은 새 instance·시각·hash와 이전 원본 관계를 추가한다.

<a id="rel-22"></a>
## REL-22 오픈소스 라이선스 고지

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 배포본에 포함된 오픈소스·모델 runtime의 저작권과 라이선스 의무를 제공한다. |
| 적용 조건 | 제3자 오픈소스가 포함된 artifact를 외부 배포하는 경우 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 법무·라이선스검토자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/delivery-and-handover.md#rel-22` |
| 선행 유형 | DEV-17, DEV-19, REL-06 |
| 후행 유형 | CLS-06 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 제3자 오픈소스가 포함된 artifact를 외부 배포하는 경우 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: component·version·저작권, license 명칭·text.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 실제 release dependency와 artifact를 기준으로 라이선스·저작권·고지를 다시 생성·검토하며 후보 목록을 최종 고지로 간주하지 않는다.

### 포함 내용과 필요한 입력

**포함 내용**

- component·version·저작권
- license 명칭·text
- attribution·notice
- 소스 제공·수정 고지
- artifact·SBOM 연결
- 검토·배포 위치

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: component·version·저작권, license 명칭·text.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.
