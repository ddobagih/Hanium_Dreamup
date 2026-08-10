# WalkSafe 릴리스 통제

> Draft로 작성된 산출물: REL-01, REL-02, REL-10  
> 버전: 0.1.0 · 승인: 미승인  
> 정책 기준선: PB-WALKSAFE-FEATURE-POLICY-1.0.0  
> 실제 실행 증거: 없음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

릴리스 후보를 언제 열고 무엇을 확인해야 하며, 아직 존재하지 않는 release 증거를 어떻게 구분하는가?

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

- REL-03 버전 설명서 — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN
- REL-04 릴리스 manifest — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN
- REL-05 소스 커밋·태그 — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN
- REL-06 실행 파일·컨테이너·모델·APK·PWA 산출물 — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN
- REL-07 해시·서명 — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN
- REL-08 릴리스별 SBOM·provenance — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN
- REL-09 릴리스 노트 — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN

## 공통 보관 원칙

민감한 위치·영상·음성·서명·운영 원본은 Git에 넣지 않습니다. Git에는 통제 저장소 ID, SHA-256, 권한, 보존기간, 실행·검토 상태만 남깁니다. 실제 결과가 생기면 template을 복사한 새 instance를 append-only로 보관하고 이 정본에는 포인터만 연결합니다.

<a id="rel-01"></a>
## REL-01 릴리스 계획

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 명명된 후보의 범위·일정·환경·책임·gate와 실패 시 복귀 방법을 사전에 합의한다. |
| 적용 조건 | 명명된 RC·beta·release 후보를 만들기로 승인한 경우 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/release-control.md#rel-01` |
| 선행 유형 | MGT-07, MGT-13 |
| 후행 유형 | REL-02, REL-03, REL-11, TST-22 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-002, FP-051<br>결정 DEC-ADMIN-FUNCTION-SCOPE, DEC-EXCLUDED-FEATURES, DEC-IBQ-017, DEC-IBQ-108, DEC-IBQ-124, DEC-IBQ-125, DEC-IBQ-126, DEC-IBQ-135, DEC-IBQ-137, DEC-IBQ-138, DEC-IBQ-139, DEC-IBQ-140, DEC-PRODUCT-RELEASE, DEC-USER-TEST-PARTICIPANTS<br>요구 RQ-FP-002-001, RQ-FP-051-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL<br>ISS-POLICY-FP035-NETWORK-001 정규화 지시 포착·묶음 승인 대기 / 변경요청 REQ-CHG-20260721-002 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `AUTHORABLE_DRAFT_NO_EXECUTION_CLAIM` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 명명된 RC·beta·release 후보를 만들기로 승인한 경우 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·단계별 gate 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: release ID·목표·범위, 포함·제외 변경.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 승인된 진행 순서는 개발 → 2026-07-26 통제 시연 → 베타 → 정식 출시다. 통제 시연은 릴리스나 사용자 안전성 승인으로 간주하지 않는다.
- 현재는 명명된 릴리스 후보가 없으므로 release ID·artifact·승인자를 꾸며 쓰지 않는다. 정해진 전체 프로젝트 예산도 없으므로 실제 견적 전 금액을 만들지 않는다.
- 릴리스 후보를 열 때 Android 사용자 앱, 별도 Android 관리자 앱, 서버, 모델, 설정, DB migration을 하나의 범위로 묶고 Web/PWA는 레거시 참고로 제외한다.
- 5개 gate, TST-22, 보안·개인정보 검토, rollback 가능성을 모두 릴리스 진입조건으로 둔다.

### 포함 내용과 필요한 입력

**포함 내용**

- release ID·목표·범위
- 포함·제외 변경
- 일정·담당·승인
- build·test·security gate
- 배포·지원·소통
- rollback·중단 기준
- 시험·출시 준비도 판단 전에 승인할 release scope·gate·일정

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·단계별 gate 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: release ID·목표·범위, 포함·제외 변경.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="rel-02"></a>
## REL-02 릴리스 승인 체크리스트

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 후보 판정 전에 적용할 요구·시험·보안·운영·문서 gate와 필수 증거·책임자를 canonical checklist로 사전 승인한다. |
| 적용 조건 | 후보를 공유 시험 또는 운영 환경으로 promotion하기 전에 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/release-control.md#rel-02` |
| 선행 유형 | REL-01, SEC-15, SEC-16 |
| 후행 유형 | REL-04, REL-20, REL-21, TST-22 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-002, FP-051<br>결정 DEC-ADMIN-FUNCTION-SCOPE, DEC-EXCLUDED-FEATURES, DEC-IBQ-017, DEC-IBQ-108, DEC-IBQ-124, DEC-IBQ-125, DEC-IBQ-126, DEC-IBQ-135, DEC-IBQ-137, DEC-IBQ-138, DEC-IBQ-139, DEC-IBQ-140, DEC-PRODUCT-RELEASE, DEC-USER-TEST-PARTICIPANTS<br>요구 RQ-FP-002-001, RQ-FP-051-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL<br>ISS-POLICY-FP035-NETWORK-001 정규화 지시 포착·묶음 승인 대기 / 변경요청 REQ-CHG-20260721-002 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `AUTHORABLE_DRAFT_NO_EXECUTION_CLAIM` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 후보를 공유 시험 또는 운영 환경으로 promotion하기 전에 활성 |
| 필수 선행 | - REL-01 릴리스 계획과 승인 전 확인할 canonical gate checklist<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 후보·기준선 ID, 필수 gate·증거 링크.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 현재 판정은 NOT_ELIGIBLE이며 5개 gate는 모두 NOT_RUN·미면제다.
- 체크리스트 항목은 근거 ID와 판정자를 요구하며 공란·자기확인만으로 Go로 바꾸지 않는다.
- 무가림 원본 수집 독립검토와 단일 관리자 복구훈련이 끝나기 전에는 승인할 수 없다.

### 포함 내용과 필요한 입력

**포함 내용**

- 적용 release 유형·환경·범위
- 필수 gate·증거 종류·합격 기준
- P0 결함·waiver 허용·금지 규칙
- artifact·hash·SBOM 확인 규칙
- 배포·rollback·지원 준비 기준
- gate별 검토자·승인자·판정 권한
- TST-22가 입력으로 참조할 canonical release gate 기준선
- 후보별 ID·artifact hash·증거 snapshot·판정·서명·시각을 기록하는 별도 signed receipt schema

**작성 입력**

- REL-01 릴리스 계획과 승인 전 확인할 canonical gate checklist
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 후보·기준선 ID, 필수 gate·증거 링크.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="rel-03"></a>
## REL-03 버전 설명서

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 제품·API·DB·모델·문서 버전의 의미와 호환·업그레이드 관계를 사용자에게 설명한다. |
| 적용 조건 | 외부 인도 또는 호환성 관리가 필요한 version을 발행할 때 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/release-control.md#rel-03` |
| 선행 유형 | AIML-16, DEV-20, REL-01 |
| 후행 유형 | REL-04, REL-09 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 외부 인도 또는 호환성 관리가 필요한 version을 발행할 때 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 제품 version·release ID, component·API·schema version.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 명명된 release ID와 확정 artifact가 생긴 뒤 버전·호환성·변경범위를 기록한다.

### Ready25 REL 내부 작성·promotion 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `IN_SCOPE_RELEASE_VERSION_NOT_NAMED` |
| release candidate | `null` |
| environment/promotion | `DEVELOPMENT → CONTROLLED_DEMO → BETA → PRODUCTION` · `MANAGED_CLOUD_STAGING_FIRST` · promotion `NOT_RUN` |
| rollback | 계획 필수 · 실행 `NOT_RUN` |
| five gates | `GATE-PHONE-QUEUE-BYTE-LIMIT`=NOT_RUN/waived=false<br>`GATE-SERVER-CAPACITY-STATE-CONTRACT`=NOT_RUN/waived=false<br>`GATE-RAW-COLLECTION-RELEASE-REVIEW`=NOT_RUN/waived=false<br>`GATE-CLOUD-COST-MEASUREMENT`=NOT_RUN/waived=false<br>`GATE-SINGLE-ADMIN-RECOVERY-DRILL`=NOT_RUN/waived=false |
| accepted / approval / execution / event / formal / release credit | `false / 0 / 0 / 0 / 0 / 0` |
| product owner | 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- 제품·Android 앱·관리자 앱·server·API·DB schema·model·config version 체계를 정의한다.
- compatibility·breaking change·upgrade·support·EOL 규칙을 정하되 실제 release ID는 만들지 않는다.
- development→controlled demo→beta→production promotion과 rollback 조건을 5개 gate에 연결한다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-REL-03-NAMED-CANDIDATE-OR-SOURCE-PENDING` | `OPEN` | `PRODUCT_OWNER` / 김민호 | `BEFORE_NAMED_VERSION_PUBLICATION_OR_EXTERNAL_DELIVERY` / due_at=`None` |
| `READY25-REL-03-INDEPENDENT-QA-UNASSIGNED` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_CONTENT_ACCEPTANCE_OR_RELEASE_PROMOTION` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- 제품 version·release ID
- component·API·schema version
- 모델·config version
- 호환성·breaking change
- upgrade 경로
- 지원·EOL 기간

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 제품 version·release ID, component·API·schema version.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="rel-04"></a>
## REL-04 릴리스 manifest

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 한 릴리스에 포함된 소스·빌드·모델·설정·문서·증거를 기계 판독 목록으로 고정한다. |
| 적용 조건 | 재현·인수 대상인 명명된 release 후보가 생성된 경우 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/release-control.md#rel-04` |
| 선행 유형 | REL-02, REL-03, REL-05, REL-06, REL-07, REL-08 |
| 후행 유형 | CLS-05, CLS-06, REL-11, SEC-14 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-039, FP-051<br>결정 DEC-ADMIN-FUNCTION-SCOPE, DEC-IBQ-017, DEC-IBQ-108, DEC-IBQ-123, DEC-IBQ-124, DEC-IBQ-125, DEC-IBQ-126, DEC-IBQ-135, DEC-IBQ-137, DEC-IBQ-138, DEC-IBQ-139, DEC-IBQ-140, DEC-MODEL-LATER-SWAP, DEC-PRODUCT-RELEASE<br>요구 RQ-FP-039-001, RQ-FP-051-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 재현·인수 대상인 명명된 release 후보가 생성된 경우 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 정확한 release·환경·수행자·시각·원자료·판정·결함을 append-only evidence instance로 남긴다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: release ID·시간·환경, source commit·tag.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- source commit·tag, build·model·config·migration, hash·서명·SBOM·시험보고서를 같은 release generation으로 결속한 기계판독 manifest만 인정한다.

### 포함 내용과 필요한 입력

**포함 내용**

- release ID·시간·환경
- source commit·tag
- artifact 경로·hash
- model·config·migration
- SBOM·provenance·test report
- 서명·승인
- REL-05 source commit·tag와 REL-06~08 artifact·무결성·공급망 증거를 같은 release generation에 결속한 manifest 필드

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: release ID·시간·환경, source commit·tag.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실행·발행 원본은 수정하지 않는다. 정정은 새 instance·시각·hash와 이전 원본 관계를 추가한다.

<a id="rel-05"></a>
## REL-05 소스 커밋·태그

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 릴리스 artifact의 정확한 source tree를 불변 commit과 보호된 tag로 식별한다. |
| 적용 조건 | release artifact를 특정 source tree와 고정할 때 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/release-control.md#rel-05` |
| 선행 유형 | DEV-01 |
| 후행 유형 | REL-04, REL-06 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | release artifact를 특정 source tree와 고정할 때 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: repository·commit SHA, tag·서명.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 실제 release commit과 tag가 만들어진 뒤 불변 ID와 remote 위치를 기록하며 현재는 생성했다고 주장하지 않는다.

### Ready25 REL 내부 작성·promotion 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `IN_SCOPE_RELEASE_CANDIDATE_COMMIT_TAG_NOT_AVAILABLE` |
| release candidate | `null` |
| environment/promotion | `DEVELOPMENT → CONTROLLED_DEMO → BETA → PRODUCTION` · `MANAGED_CLOUD_STAGING_FIRST` · promotion `NOT_RUN` |
| rollback | 계획 필수 · 실행 `NOT_RUN` |
| five gates | `GATE-PHONE-QUEUE-BYTE-LIMIT`=NOT_RUN/waived=false<br>`GATE-SERVER-CAPACITY-STATE-CONTRACT`=NOT_RUN/waived=false<br>`GATE-RAW-COLLECTION-RELEASE-REVIEW`=NOT_RUN/waived=false<br>`GATE-CLOUD-COST-MEASUREMENT`=NOT_RUN/waived=false<br>`GATE-SINGLE-ADMIN-RECOVERY-DRILL`=NOT_RUN/waived=false |
| accepted / approval / execution / event / formal / release credit | `false / 0 / 0 / 0 / 0 / 0` |
| product owner | 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- repository·commit SHA·protected tag·signature·submodule·LFS·dirty-tree 금지 schema를 정의한다.
- REL-04 manifest와 동일 release generation으로 source binding을 결속하는 절차를 정한다.
- named release candidate 승인 전 실제 commit/tag/signature 또는 conformance PASS를 생성하지 않는다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-REL-05-NAMED-CANDIDATE-OR-SOURCE-PENDING` | `OPEN` | `PRODUCT_OWNER` / 김민호 | `AFTER_NAMED_CANDIDATE_APPROVAL_BEFORE_SOURCE_FREEZE` / due_at=`None` |
| `READY25-REL-05-INDEPENDENT-QA-UNASSIGNED` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_CONTENT_ACCEPTANCE_OR_RELEASE_PROMOTION` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- repository·commit SHA
- tag·서명
- submodule·LFS 상태
- dirty tree 금지 증거
- branch·보호 정책
- manifest·build 연결
- REL-04가 동일 release generation의 최종 manifest로 결속할 source commit·tag binding

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: repository·commit SHA, tag·서명.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="rel-06"></a>
## REL-06 실행 파일·컨테이너·모델·APK·PWA 산출물

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 사용자에게 배포되는 모든 바이너리·이미지·모델·정적 자산을 릴리스 단위로 제공한다. |
| 적용 조건 | 사용자·운영자에게 배포할 artifact가 생성된 경우 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/release-control.md#rel-06` |
| 선행 유형 | AIML-16, DEV-20, REL-05 |
| 후행 유형 | CLS-06, REL-04, REL-07, REL-08, REL-13, REL-14, REL-16, REL-22, SEC-14 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-007, FP-008, FP-051<br>결정 DEC-ADMIN-FUNCTION-SCOPE, DEC-APP-SEPARATION, DEC-BACKOFFICE-FAILURE, DEC-IBQ-011, DEC-IBQ-012, DEC-IBQ-017, DEC-IBQ-018, DEC-IBQ-105, DEC-IBQ-108, DEC-IBQ-124, DEC-IBQ-125, DEC-IBQ-126, DEC-IBQ-133, DEC-IBQ-135, DEC-IBQ-137, DEC-IBQ-138, DEC-IBQ-139, DEC-IBQ-140, DEC-PRODUCT-RELEASE<br>요구 RQ-FP-007-001, RQ-FP-008-001, RQ-FP-051-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 사용자·운영자에게 배포할 artifact가 생성된 경우 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 정확한 release·환경·수행자·시각·원자료·판정·결함을 append-only evidence instance로 남긴다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: artifact ID·유형·플랫폼, 파일·registry 위치.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 실제 APK/AAB·서버 image·모델 파일을 생성한 뒤 byte length와 SHA-256을 기록한다. PWA는 현행 정식 산출물에 포함하지 않는다.

### 포함 내용과 필요한 입력

**포함 내용**

- artifact ID·유형·플랫폼
- 파일·registry 위치
- version·build variant
- 크기·hash·signature
- 설치·실행 전제
- 보존·접근 권한

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: artifact ID·유형·플랫폼, 파일·registry 위치.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실행·발행 원본은 수정하지 않는다. 정정은 새 instance·시각·hash와 이전 원본 관계를 추가한다.

<a id="rel-07"></a>
## REL-07 해시·서명

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 릴리스 파일의 무결성과 승인된 발행자를 배포 전후에 검증하게 한다. |
| 적용 조건 | 외부 인도·보관·다운로드 대상 artifact가 생성된 경우 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/release-control.md#rel-07` |
| 선행 유형 | DEV-20, REL-06 |
| 후행 유형 | CLS-06, REL-04 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-051<br>결정 DEC-ADMIN-FUNCTION-SCOPE, DEC-IBQ-017, DEC-IBQ-108, DEC-IBQ-124, DEC-IBQ-125, DEC-IBQ-126, DEC-IBQ-135, DEC-IBQ-137, DEC-IBQ-138, DEC-IBQ-139, DEC-IBQ-140, DEC-PRODUCT-RELEASE<br>요구 RQ-FP-051-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 외부 인도·보관·다운로드 대상 artifact가 생성된 경우 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 정확한 release·환경·수행자·시각·원자료·판정·결함을 append-only evidence instance로 남긴다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: artifact·SHA-256, signature·certificate ID.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 각 파일 hash를 실제 바이트에서 계산하고 서명이 필요하면 서명자·키 ID·시각·검증 결과를 함께 보존한다.

### 포함 내용과 필요한 입력

**포함 내용**

- artifact·SHA-256
- signature·certificate ID
- 서명 도구·algorithm
- 생성 시점·주체
- verification 명령·결과
- key 만료·폐기 대응

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: artifact·SHA-256, signature·certificate ID.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실행·발행 원본은 수정하지 않는다. 정정은 새 instance·시각·hash와 이전 원본 관계를 추가한다.

<a id="rel-08"></a>
## REL-08 릴리스별 SBOM·provenance

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 특정 artifact에 포함된 구성요소와 생성 과정을 source·builder까지 추적한다. |
| 적용 조건 | 명명된 release artifact에 supply-chain 증거가 요구될 때 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/release-control.md#rel-08` |
| 선행 유형 | DEV-19, DEV-20, REL-06 |
| 후행 유형 | CLS-06, REL-04 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-051<br>결정 DEC-ADMIN-FUNCTION-SCOPE, DEC-IBQ-017, DEC-IBQ-108, DEC-IBQ-124, DEC-IBQ-125, DEC-IBQ-126, DEC-IBQ-135, DEC-IBQ-137, DEC-IBQ-138, DEC-IBQ-139, DEC-IBQ-140, DEC-PRODUCT-RELEASE<br>요구 RQ-FP-051-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 명명된 release artifact에 supply-chain 증거가 요구될 때 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 정확한 release·환경·수행자·시각·원자료·판정·결함을 append-only evidence instance로 남긴다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: release·artifact 결속, SBOM 형식·hash.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 실제 build 입력과 도구chain이 고정된 뒤 release별 SBOM과 provenance를 생성한다.

### 포함 내용과 필요한 입력

**포함 내용**

- release·artifact 결속
- SBOM 형식·hash
- provenance statement
- source·builder·inputs
- 서명·검증
- 취약점 scan 시점

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: release·artifact 결속, SBOM 형식·hash.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실행·발행 원본은 수정하지 않는다. 정정은 새 instance·시각·hash와 이전 원본 관계를 추가한다.

<a id="rel-09"></a>
## REL-09 릴리스 노트

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 사용자·관리자에게 새 기능·수정·변경·제약·업그레이드 영향을 명확히 알린다. |
| 적용 조건 | 사용자 또는 운영자에게 새 version을 제공할 때 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/release-control.md#rel-09` |
| 선행 유형 | REL-03, TST-20, TST-21, WS-22 |
| 후행 유형 | REL-17, REL-18 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 사용자 또는 운영자에게 새 version을 제공할 때 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: release·날짜·대상, 주요 기능·개선.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 명명된 release의 실제 변경·해결·잔여 문제만 기록하고 계획을 완료 사실로 바꾸지 않는다.

### Ready25 REL 내부 작성·promotion 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `IN_SCOPE_RELEASE_NOTES_CANDIDATE_NOT_NAMED` |
| release candidate | `null` |
| environment/promotion | `DEVELOPMENT → CONTROLLED_DEMO → BETA → PRODUCTION` · `MANAGED_CLOUD_STAGING_FIRST` · promotion `NOT_RUN` |
| rollback | 계획 필수 · 실행 `NOT_RUN` |
| five gates | `GATE-PHONE-QUEUE-BYTE-LIMIT`=NOT_RUN/waived=false<br>`GATE-SERVER-CAPACITY-STATE-CONTRACT`=NOT_RUN/waived=false<br>`GATE-RAW-COLLECTION-RELEASE-REVIEW`=NOT_RUN/waived=false<br>`GATE-CLOUD-COST-MEASUREMENT`=NOT_RUN/waived=false<br>`GATE-SINGLE-ADMIN-RECOVERY-DRILL`=NOT_RUN/waived=false |
| accepted / approval / execution / event / formal / release credit | `false / 0 / 0 / 0 / 0 / 0` |
| product owner | 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- release·날짜·대상·기능·결함·보안·migration·known issue·지원 링크 template을 정의한다.
- 실제 change set과 잔여 제한은 named candidate의 검증된 source에서만 채우도록 한다.
- promotion·rollback·5개 gate 상태를 명시하되 배포·승인·release 결과를 생성하지 않는다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-REL-09-NAMED-CANDIDATE-OR-SOURCE-PENDING` | `OPEN` | `PRODUCT_OWNER` / 김민호 | `AFTER_NAMED_VERSION_AND_CHANGESET_BEFORE_DISTRIBUTION` / due_at=`None` |
| `READY25-REL-09-INDEPENDENT-QA-UNASSIGNED` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_CONTENT_ACCEPTANCE_OR_RELEASE_PROMOTION` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- release·날짜·대상
- 주요 기능·개선
- 수정 결함·보안 변경
- breaking·migration
- known issue·우회법
- 지원·문서 링크

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: release·날짜·대상, 주요 기능·개선.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="rel-10"></a>
## REL-10 알려진 문제와 우회법

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 미해결 결함의 영향 조건과 안전한 회피·지원·수정 계획을 공개한다. |
| 적용 조건 | 출시 시점에 미해결 사용자 영향 결함이 존재하는 경우 활성 |
| 책임 | 작성 릴리스책임자 · 검토 QA책임자, 운영책임자, 보안·개인정보책임자, 접근성·안전책임자 · 승인 제품책임자 |
| 정본 | `docs/deliverables/09-release/release-control.md#rel-10` |
| 선행 유형 | TST-21, WS-22 |
| 후행 유형 | REL-20 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL<br>ISS-POLICY-FP035-NETWORK-001 정규화 지시 포착·묶음 승인 대기 / 변경요청 REQ-CHG-20260721-002 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `AUTHORABLE_DRAFT_NO_EXECUTION_CLAIM` |
| 실행 책임 | 릴리스책임자 |
| 활성 시점 | 출시 시점에 미해결 사용자 영향 결함이 존재하는 경우 활성 |
| 필수 선행 | - 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준<br>- source·build·model·config·migration artifact<br>- 시험·보안·운영 증거와 미해결 위험 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: issue ID·영향 버전, 증상·발생 조건.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- ISS-POLICY-FP035-NETWORK-001는 정규화 지시 포착·묶음 승인 대기로 유지한다. 일반 활동원본은 보행 중 전송하지 않고, 정지 뒤에는 이동통신망 명시 선택 시 이동통신망을 허용하며 미선택 시 Wi-Fi만 허용한다. 새 묶음 승인 전 관련 정식 시험은 NOT_RUN이다.
- 5개 미실행 gate와 실제 사용자 영향·우회 가능성·우회 금지 조건을 릴리스 후보마다 다시 평가한다.
- 위험 탐지·길안내를 안전보장 기능으로 설명하지 않고 Web/PWA를 Android 합격 근거로 쓰지 않는다.

### 포함 내용과 필요한 입력

**포함 내용**

- issue ID·영향 버전
- 증상·발생 조건
- 사용자·데이터·안전 영향
- 우회 절차·제약
- 수정 목표·상태
- 지원·위험 수용

**작성 입력**

- 승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준
- source·build·model·config·migration artifact
- 시험·보안·운영 증거와 미해결 위험

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: issue ID·영향 버전, 증상·발생 조건.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 릴리스 후보·artifact·migration·배포 대상 또는 승인 판정 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.
