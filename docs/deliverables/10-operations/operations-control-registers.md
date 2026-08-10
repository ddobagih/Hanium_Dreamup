# WalkSafe 운영 통제와 지속 원장

> Draft로 작성된 산출물: OPS-14, OPS-15, OPS-16, OPS-17, OPS-19, OPS-20, OPS-21, OPS-22, OPS-23, OPS-24  
> 버전: 0.1.0 · 승인: 미승인  
> 정책 기준선: PB-WALKSAFE-FEATURE-POLICY-1.0.0  
> 실제 실행 증거: 없음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

접근·변경·장애·부채·용량·삭제·외부 의존성을 어떤 원장과 정책으로 관리하는가?

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

- OPS-18 postmortem — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN

## 공통 보관 원칙

민감한 위치·영상·음성·서명·운영 원본은 Git에 넣지 않습니다. Git에는 통제 저장소 ID, SHA-256, 권한, 보존기간, 실행·검토 상태만 남깁니다. 실제 결과가 생기면 template을 복사한 새 instance를 append-only로 보관하고 이 정본에는 포인터만 연결합니다.

<a id="ops-14"></a>
## OPS-14 접근권한 정기검토

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 계정·역할·service credential이 현재 책임과 최소권한에 맞는지 주기적으로 확인한다. |
| 적용 조건 | 지속 계정·관리자·서비스 권한이 운영되는 경우 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/operations-control-registers.md#ops-14` |
| 선행 유형 | OPS-02, SEC-08 |
| 후행 유형 | 없음 |
| 정책·결정·요구·위험·변경 추적 | 상위 유형 의존성과 정책 기준선에서 상속; 직접 기능 연결은 없음 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `AUTHORABLE_DRAFT_NO_EXECUTION_CLAIM` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 지속 계정·관리자·서비스 권한이 운영되는 경우 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 검토 대상·기준일, 계정·역할·자원 권한.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 권한 원장은 사전개설한다. 실제 정기검토 때 계정·역할·필요성·최종사용·회수·예외를 append-only 행으로 남긴다.

### 포함 내용과 필요한 입력

**포함 내용**

- 검토 대상·기준일
- 계정·역할·자원 권한
- owner·사용 근거
- 미사용·과다·고아 권한
- 회수·조정 결과
- 검토자·승인·증거

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 검토 대상·기준일, 계정·역할·자원 권한.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다.

<a id="ops-15"></a>
## OPS-15 비밀정보·인증서 회전

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 만료·유출 전에 key·password·certificate를 서비스 중단 없이 교체하고 폐기한다. |
| 적용 조건 | 만료·회전되는 비밀정보 또는 인증서를 운영하는 경우 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/operations-control-registers.md#ops-15` |
| 선행 유형 | OPS-02, SEC-09 |
| 후행 유형 | CLS-15 |
| 정책·결정·요구·위험·변경 추적 | 상위 유형 의존성과 정책 기준선에서 상속; 직접 기능 연결은 없음 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `AUTHORABLE_DRAFT_NO_EXECUTION_CLAIM` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 만료·회전되는 비밀정보 또는 인증서를 운영하는 경우 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 대상·owner·만료일, 회전 주기·trigger.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 비밀정보·인증서·서명키는 코드와 문서에 넣지 않고 별도 보관하며 회전 시 새 버전·적용·폐기·복구 가능성을 검증한다.

### 포함 내용과 필요한 입력

**포함 내용**

- 대상·owner·만료일
- 회전 주기·trigger
- 신규 발급·배포 순서
- 이중 운영·검증
- 구값 폐기·감사
- 실패 rollback

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 대상·owner·만료일, 회전 주기·trigger.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="ops-16"></a>
## OPS-16 패치·취약점 대응 절차

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | OS·runtime·dependency 취약점을 위험 기반 기한 내 평가·시험·배포·검증한다. |
| 적용 조건 | 외부 노출 환경 또는 장기 지원 version을 운영하는 경우 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/operations-control-registers.md#ops-16` |
| 선행 유형 | OPS-02, SEC-15, SEC-18 |
| 후행 유형 | 없음 |
| 정책·결정·요구·위험·변경 추적 | 상위 유형 의존성과 정책 기준선에서 상속; 직접 기능 연결은 없음 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `AUTHORABLE_DRAFT_NO_EXECUTION_CLAIM` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 외부 노출 환경 또는 장기 지원 version을 운영하는 경우 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 정보 source·수집주기, severity·영향·우선순위.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 취약점 심각도·악용 가능성·사용자 안전·개인정보 영향을 함께 평가하고 patch, 완화, 검증, release 연결을 기록한다.

### 포함 내용과 필요한 입력

**포함 내용**

- 정보 source·수집주기
- severity·영향·우선순위
- 패치·완화 선택
- 시험·배포·rollback
- 긴급 변경 승인
- 완료·예외 추적

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 정보 source·수집주기, severity·영향·우선순위.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="ops-17"></a>
## OPS-17 장애 기록

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 운영 장애의 시간선·영향·대응·복구와 연결 증거를 사실 중심으로 보존한다. |
| 적용 조건 | 운영 준비 시 장애 원장 구조를 사전 개설하고, 사용자·데이터·SLO에 영향을 준 장애가 발생할 때마다 실행 행을 추가 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/operations-control-registers.md#ops-17` |
| 선행 유형 | OPS-07, OPS-08 |
| 후행 유형 | OPS-18, OPS-20 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-052<br>결정 DEC-ADMIN-FUNCTION-SCOPE, DEC-BACKOFFICE-FAILURE, DEC-IBQ-105, DEC-IBQ-108, DEC-IBQ-117, DEC-IBQ-142, DEC-SUPPORT-HOURS<br>요구 RQ-FP-052-001<br>gate GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 운영 준비 시 장애 원장 구조를 사전 개설하고, 사용자·데이터·SLO에 영향을 준 장애가 발생할 때마다 실행 행을 추가 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: incident ID·severity, 탐지·시작·종료 시간.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 장애 원장 구조만 사전개설하고 현재 실제 incident 행은 0개로 둔다. 미발생을 무장애 증명으로 해석하지 않는다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `EXTERNAL_AFTER_INTERNAL` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `PREOPENED_INCIDENT_REGISTER` |
| 필수 record fields | `incident_id`<br>`severity`<br>`started_at`<br>`detected_at`<br>`ended_at`<br>`user_and_data_impact`<br>`timeline`<br>`containment`<br>`recovery`<br>`owner_role`<br>`evidence_refs`<br>`postmortem_status`<br>`receipt_ref` |
| 현재 경계 | operations_started=false<br>incidents=[]<br>opening snapshot의 0건은 무사건·무장애 증명이 아님 |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### W9 stable external execution contract

아래 JSON은 builder 내부 정본 model의 lossless projection입니다. 실제 event·decision·evidence·receipt가 아니며 overlay를 source로 참조하지 않습니다.

<!-- W9-EXTERNAL-CONTRACT-START W9-EXT-OPS-17 -->
```json
{
  "approver_role": "서비스소유자",
  "artifact_type_id": "OPS-17",
  "catalog_artifact_id": "DLV-OPS-17",
  "completion_test": [
    "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: incident ID·severity, 탐지·시작·종료 시간.",
    "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
    "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
    "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
    "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
    "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
    "The named approver explicitly approves the exact scope and effective window.",
    "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
  ],
  "contract_schema": "walksafe.w9.external-execution-contract.v1",
  "current_state": {
    "acceptance_status": "NOT_APPROVED",
    "actual_event_count": 0,
    "actual_event_ids": [],
    "actual_evidence_count": 0,
    "actual_evidence_ids": [],
    "actual_receipt_count": 0,
    "actual_receipt_ids": [],
    "approval_status": "NOT_APPROVED",
    "execution_status": "NOT_RUN",
    "operator_state": "UNASSIGNED",
    "recipient_state": "UNASSIGNED"
  },
  "due_and_review": {
    "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
    "due_at": null,
    "due_condition": "ON_EACH_REAL_INCIDENT_BEFORE_INCIDENT_CLOSURE",
    "review_due_at": null
  },
  "evidence_schema": {
    "catalog_fields": [
      "incident ID·severity",
      "탐지·시작·종료 시간",
      "사용자·데이터 영향",
      "조치 timeline·담당",
      "원인 후보·증거",
      "복구·후속·통지"
    ],
    "lifecycle_fields": [
      "incident_id",
      "severity",
      "started_at",
      "detected_at",
      "ended_at",
      "user_and_data_impact",
      "timeline",
      "containment",
      "recovery",
      "owner_role",
      "evidence_refs",
      "postmortem_status",
      "receipt_ref"
    ]
  },
  "external_authority": "REAL_INCIDENT_EVENT_AND_SERVICE_OWNER_POSTMORTEM_APPROVAL",
  "external_trigger": "운영 준비 시 장애 원장 구조를 사전 개설하고, 사용자·데이터·SLO에 영향을 준 장애가 발생할 때마다 실행 행을 추가",
  "fake_event_or_receipt_allowed": false,
  "internal_action": "Keep the preopened register empty until a real incident occurs; then add one complete real incident row and approved postmortem receipt.",
  "normative_model": "BUILDER_INTERNAL_CANONICAL",
  "procedure": [
    "실제 incident가 발생하기 전에는 opening register를 비워 둔다.",
    "발생 시 timeline·impact·containment·recovery·owner·evidence lifecycle field를 실제 값으로 기록한다.",
    "서비스소유자가 exact incident scope와 postmortem을 승인한다.",
    "실제 incident event, operator와 postmortem receipt를 hash 결속한다."
  ],
  "receipt_schema": [
    "repository_relative_path",
    "sha256",
    "byte_length",
    "source_event_or_decision_id",
    "occurred_or_effective_at",
    "operator_or_acceptor_identity"
  ],
  "release_impact": "실제 incident가 발생하면 승인된 postmortem receipt 전까지 운영·관련 release 판정을 닫을 수 없으며 현재 release는 NOT_ELIGIBLE이다.",
  "required_inputs": [
    "승인된 릴리스·배포 아키텍처·SLO",
    "운영 환경·계정·외부 서비스·관측성 현황",
    "지원·보안·복구 책임과 연락 체계"
  ],
  "responsible_role": "운영책임자",
  "stable_id": "W9-EXT-OPS-17"
}
```
<!-- W9-EXTERNAL-CONTRACT-END W9-EXT-OPS-17 -->


### 포함 내용과 필요한 입력

**포함 내용**

- incident ID·severity
- 탐지·시작·종료 시간
- 사용자·데이터 영향
- 조치 timeline·담당
- 원인 후보·증거
- 복구·후속·통지

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: incident ID·severity, 탐지·시작·종료 시간.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다.

<a id="ops-18"></a>
## OPS-18 postmortem

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 중요 장애의 시스템 원인과 방어 실패를 비난 없이 분석해 재발 방지 조치를 확정한다. |
| 적용 조건 | 중대도 기준을 넘은 장애 또는 반복 장애가 종료된 경우 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/operations-control-registers.md#ops-18` |
| 선행 유형 | OPS-17 |
| 후행 유형 | CLS-11, OPS-20 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-052<br>결정 DEC-ADMIN-FUNCTION-SCOPE, DEC-BACKOFFICE-FAILURE, DEC-IBQ-105, DEC-IBQ-108, DEC-IBQ-117, DEC-IBQ-142, DEC-SUPPORT-HOURS<br>요구 RQ-FP-052-001<br>gate GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 중대도 기준을 넘은 장애 또는 반복 장애가 종료된 경우 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 요약·영향·timeline, 직접·기여 원인.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 실제 중대 incident가 종료된 뒤 시간선·원인·영향·기여요인·재발방지·담당·기한을 근거와 함께 작성한다.

### Ready25 내부 작성·실행 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `NOT_TRIGGERED_INCIDENT_COUNT_0` |
| accepted | `false` |
| approval / actual event / formal evidence / release credit | `0 / 0 / 0 / 0` |
| project/service/product owner | 김민호 / 김민호 / 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- OPS-17 incident 원장과 중대도·반복 trigger를 연결한 postmortem schema를 유지한다.
- 현재 incident count 0은 NOT_TRIGGERED이며 무장애 증거가 아니라고 명시한다.
- 실제 incident 없이 timeline·원인·action·postmortem receipt를 생성하지 않는다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-OPS-18-REAL-TRIGGER-OR-EVIDENCE-PENDING` | `OPEN` | `SERVICE_OWNER` / 김민호 | `ONLY_AFTER_A_QUALIFYING_REAL_INCIDENT_ENDS` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- 요약·영향·timeline
- 직접·기여 원인
- 잘된 점·어려운 점
- 탐지·대응 gap
- Action·owner·기한
- 공유·완료 검증

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 요약·영향·timeline, 직접·기여 원인.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="ops-19"></a>
## OPS-19 운영 변경이력

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 설정·인프라·권한·데이터·모델 운영 변경을 승인·실행·검증·rollback과 연결한다. |
| 적용 조건 | 운영 준비 시 변경 원장 구조를 사전 개설하고, 코드 외 설정·권한·인프라 변경 때마다 실행 행을 추가 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/operations-control-registers.md#ops-19` |
| 선행 유형 | MGT-16, OPS-02 |
| 후행 유형 | OPS-20 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-054<br>결정 DEC-IBQ-125, DEC-IBQ-139, DEC-IBQ-140, DEC-IBQ-144<br>요구 RQ-FP-054-001<br>gate GATE-RAW-COLLECTION-RELEASE-REVIEW |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 운영 준비 시 변경 원장 구조를 사전 개설하고, 코드 외 설정·권한·인프라 변경 때마다 실행 행을 추가 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 변경 ID·대상·전후, 사유·위험·영향.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 운영 변경은 요청·위험·승인·시행·검증·rollback을 append-only로 기록하며 현재 실행 행은 0개다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `EXTERNAL_AFTER_INTERNAL` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `PREOPENED_OPERATION_CHANGE_REGISTER` |
| 필수 record fields | `change_id`<br>`requested_at`<br>`requester_role`<br>`risk_assessment`<br>`target_scope`<br>`release_or_config_ref`<br>`approval_status`<br>`execution_window`<br>`rollback_plan_ref`<br>`verification_result`<br>`operator_identity`<br>`evidence_refs`<br>`receipt_ref` |
| 현재 경계 | operations_started=false<br>operation_changes=[]<br>opening snapshot의 0건은 무변경 증명이 아님 |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### W9 stable external execution contract

아래 JSON은 builder 내부 정본 model의 lossless projection입니다. 실제 event·decision·evidence·receipt가 아니며 overlay를 source로 참조하지 않습니다.

<!-- W9-EXTERNAL-CONTRACT-START W9-EXT-OPS-19 -->
```json
{
  "approver_role": "서비스소유자",
  "artifact_type_id": "OPS-19",
  "catalog_artifact_id": "DLV-OPS-19",
  "completion_test": [
    "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 변경 ID·대상·전후, 사유·위험·영향.",
    "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
    "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
    "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
    "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
    "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
    "The named approver explicitly approves the exact scope and effective window.",
    "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
  ],
  "contract_schema": "walksafe.w9.external-execution-contract.v1",
  "current_state": {
    "acceptance_status": "NOT_APPROVED",
    "actual_event_count": 0,
    "actual_event_ids": [],
    "actual_evidence_count": 0,
    "actual_evidence_ids": [],
    "actual_receipt_count": 0,
    "actual_receipt_ids": [],
    "approval_status": "NOT_APPROVED",
    "execution_status": "NOT_RUN",
    "operator_state": "UNASSIGNED",
    "recipient_state": "UNASSIGNED"
  },
  "due_and_review": {
    "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
    "due_at": null,
    "due_condition": "BEFORE_EACH_REAL_OPERATIONAL_CHANGE_EXECUTION",
    "review_due_at": null
  },
  "evidence_schema": {
    "catalog_fields": [
      "변경 ID·대상·전후",
      "사유·위험·영향",
      "승인·window·담당",
      "실행 commit·명령",
      "검증·관찰",
      "rollback·incident 연결"
    ],
    "lifecycle_fields": [
      "change_id",
      "requested_at",
      "requester_role",
      "risk_assessment",
      "target_scope",
      "release_or_config_ref",
      "approval_status",
      "execution_window",
      "rollback_plan_ref",
      "verification_result",
      "operator_identity",
      "evidence_refs",
      "receipt_ref"
    ]
  },
  "external_authority": "SERVICE_OWNER_AUTHORIZED_REAL_OPERATIONAL_CHANGE",
  "external_trigger": "운영 준비 시 변경 원장 구조를 사전 개설하고, 코드 외 설정·권한·인프라 변경 때마다 실행 행을 추가",
  "fake_event_or_receipt_allowed": false,
  "internal_action": "Keep the preopened register empty until a real operational change occurs; then add one authorized change row with rollback, verification and receipt.",
  "normative_model": "BUILDER_INTERNAL_CANONICAL",
  "procedure": [
    "실제 operational change 전에는 opening register를 비워 둔다.",
    "change 대상·위험·승인·window·rollback·verification lifecycle field를 채운다.",
    "서비스소유자가 실행 전 exact scope와 effective window를 승인한다.",
    "실제 change event, operator와 execution receipt를 hash 결속한다."
  ],
  "receipt_schema": [
    "repository_relative_path",
    "sha256",
    "byte_length",
    "source_event_or_decision_id",
    "occurred_or_effective_at",
    "operator_or_acceptor_identity"
  ],
  "release_impact": "승인·rollback·검증·실제 receipt 없는 운영 변경은 실행할 수 없고 관련 배포·release는 차단되며 현재 release는 NOT_ELIGIBLE이다.",
  "required_inputs": [
    "승인된 릴리스·배포 아키텍처·SLO",
    "운영 환경·계정·외부 서비스·관측성 현황",
    "지원·보안·복구 책임과 연락 체계"
  ],
  "responsible_role": "운영책임자",
  "stable_id": "W9-EXT-OPS-19"
}
```
<!-- W9-EXTERNAL-CONTRACT-END W9-EXT-OPS-19 -->


### 포함 내용과 필요한 입력

**포함 내용**

- 변경 ID·대상·전후
- 사유·위험·영향
- 승인·window·담당
- 실행 commit·명령
- 검증·관찰
- rollback·incident 연결

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 변경 ID·대상·전후, 사유·위험·영향.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다.

<a id="ops-20"></a>
## OPS-20 유지보수 Backlog

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 운영 결함·개선·업데이트를 사용자 영향·안전·SLO·노력 기준으로 우선순위화한다. |
| 적용 조건 | 출시 후 결함·개선 요청을 지속 관리하는 경우 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/operations-control-registers.md#ops-20` |
| 선행 유형 | OPS-17, OPS-18, OPS-19 |
| 후행 유형 | 없음 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL<br>ISS-POLICY-FP035-NETWORK-001 정규화 지시 포착·묶음 승인 대기 / 변경요청 REQ-CHG-20260721-002 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 출시 후 결함·개선 요청을 지속 관리하는 경우 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 항목 ID·출처, 영향 서비스·사용자.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 5개 gate, FP-035 정규화 지시의 묶음 승인 대기, 운영 준비 gap을 완료 사실과 분리한 backlog로 유지한다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `OK_CANDIDATE_CONTENT_COMPLETENESS` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `ACTIVE_MAINTENANCE_BACKLOG_SCHEMA_READY` |
| 필수 record fields | `backlog_id`<br>`source_type`<br>`source_ref`<br>`title`<br>`status`<br>`priority`<br>`owner_role`<br>`due_condition`<br>`risk_if_open`<br>`completion_criteria`<br>`evidence_refs`<br>`waiver_status`<br>`approval_status`<br>`receipt_ref` |
| 현재 경계 | r020 audit/backlog에 hash 결속<br>5개 gate와 FP-035 항목은 OPEN/NOT_RUN<br>waiver와 completion receipt 없음 |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### 포함 내용과 필요한 입력

**포함 내용**

- 항목 ID·출처
- 영향 서비스·사용자
- 심각도·가치·노력
- 의존성·목표 릴리스
- owner·상태
- 인수조건·완료 증거

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 항목 ID·출처, 영향 서비스·사용자.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다.

<a id="ops-21"></a>
## OPS-21 기술부채 목록

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 의도적으로 미룬 구조·시험·도구·문서 문제의 비용·위험·상환 계획을 가시화한다. |
| 적용 조건 | 의도적으로 미룬 구조·시험·문서 문제가 식별된 경우 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/operations-control-registers.md#ops-21` |
| 선행 유형 | DEV-18 |
| 후행 유형 | CLS-09 |
| 정책·결정·요구·위험·변경 추적 | gate GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL<br>ISS-POLICY-FP035-NETWORK-001 정규화 지시 포착·묶음 승인 대기 / 변경요청 REQ-CHG-20260721-002 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 의도적으로 미룬 구조·시험·문서 문제가 식별된 경우 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 부채 ID·위치·원인, 현재·미래 영향.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 수치 미확정, 관리자 복구훈련 미실행, 대시보드·비상대응자 미활성 상태를 숨기지 않고 부채로 추적한다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `OK_CANDIDATE_CONTENT_COMPLETENESS` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `ACTIVE_TECHNICAL_DEBT_SCHEMA_READY` |
| 필수 record fields | `debt_id`<br>`source_ref`<br>`title`<br>`impact`<br>`priority`<br>`owner_role`<br>`due_condition`<br>`closure_criteria`<br>`evidence_refs`<br>`risk_acceptance_status`<br>`acceptance_receipt_ref` |
| 현재 경계 | 측정·dashboard·비상대응자·복구훈련 debt OPEN<br>risk_acceptance_status=NOT_APPROVED<br>acceptance receipt 없음 |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### 포함 내용과 필요한 입력

**포함 내용**

- 부채 ID·위치·원인
- 현재·미래 영향
- 안전·보안·속도 위험
- 상환 선택지·노력
- trigger·우선순위
- owner·목표·상태

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 부채 ID·위치·원인, 현재·미래 영향.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다.

<a id="ops-22"></a>
## OPS-22 용량·비용 관리

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 트래픽·저장·API·GPU 성장과 비용을 예측해 임계점 전에 확장·절감 결정을 내린다. |
| 적용 조건 | 유료 자원·API 또는 용량 상한이 있는 운영 환경에서 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/operations-control-registers.md#ops-22` |
| 선행 유형 | OPS-04, OPS-06 |
| 후행 유형 | 없음 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-035, FP-042, FP-053<br>결정 DEC-AUTO-REPORT-TIMING, DEC-IBQ-028, DEC-IBQ-048, DEC-IBQ-049, DEC-IBQ-054, DEC-IBQ-088, DEC-IBQ-092, DEC-IBQ-093, DEC-IBQ-094, DEC-IBQ-097, DEC-IBQ-100, DEC-IBQ-101, DEC-IBQ-102, DEC-IBQ-107, DEC-IBQ-114, DEC-IBQ-130, DEC-IBQ-142, DEC-IBQ-143, DEC-LOCAL-QUEUE-END-SESSION, DEC-MOTION-PAUSES-UPLOAD, DEC-PERSISTENT-FAILURE-STOP-ALL, DEC-UPLOAD-NETWORK<br>요구 RQ-FP-035-001, RQ-FP-042-001, RQ-FP-053-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 유료 자원·API 또는 용량 상한이 있는 운영 환경에서 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자원·단가·청구 source, 현재 사용·추세.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 서버 기준은 주 원본 300 GiB, 별도 backup 300 GiB, 월 저장비 30,000원이다.
- 서버 사용률 70%는 관리자 경고, 85%는 신규 현장시험 참여자 추가 중단, 95%는 만료자료 정리 뒤 새 원본수집 세션 보류, 100%는 새 학습자료·자동신고 후보 생성을 조용히 보류한다.
- 기존 암호화 자료와 실시간 탐지·길안내는 유지하고 용량 확보 뒤 자동 재개한다. 만료되지 않은 원본을 비용 때문에 임의 삭제하지 않는다.
- 자료 흐름만 보류되는 동안 사용자에게 음성·진동·푸시를 보내지 않고 관리자 기록·운영 지표에 남긴다. 실시간 안전기능이 믿을 수 없을 때만 사용자에게 안전정지를 알린다.
- 휴대전화 queue는 서버 백분율과 별개이며 실제 byte 상한은 기기 실측 gate가 끝나기 전 미정이다.

### Ready25 내부 작성·실행 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `IN_SCOPE_LIVE_RESOURCE_BILLING_AND_USAGE_PENDING` |
| accepted | `false` |
| approval / actual event / formal evidence / release credit | `0 / 0 / 0 / 0` |
| project/service/product owner | 김민호 / 김민호 / 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- 300 GiB+300 GiB·30,000원·70/85/95/100%를 실측이 아닌 planning assumption으로 구분한다.
- 자원·단가·청구 source·사용량·forecast·budget alert·비용배분 schema를 정한다.
- 실제 청구·사용·추세·비용 결과는 운영 source 확보 전 생성하지 않는다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-OPS-22-REAL-TRIGGER-OR-EVIDENCE-PENDING` | `OPEN` | `SERVICE_OWNER` / 김민호 | `BEFORE_PAID_OR_CAPACITY_LIMITED_OPERATIONS_REVIEW` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- 자원·단가·청구 source
- 현재 사용·추세
- 용량 한계·forecast
- budget·alert
- 확장·최적화 선택
- 비용 배분·정기 검토

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자원·단가·청구 source, 현재 사용·추세.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="ops-23"></a>
## OPS-23 데이터 보존·삭제 실행 기록

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 정책에 따른 자동·요청 삭제가 실제 대상과 백업·로그까지 적용됐음을 감사 가능하게 남긴다. |
| 적용 조건 | 운영 준비 시 삭제 실행 원장 구조를 사전 개설하고, 보존기간 만료·사용자 요청·서비스 종료로 삭제할 때마다 receipt 행을 추가 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/operations-control-registers.md#ops-23` |
| 선행 유형 | OPS-01, SEC-07 |
| 후행 유형 | CLS-14 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-015, FP-036, FP-053<br>결정 DEC-AUTO-REPORT-TIMING, DEC-IBQ-028, DEC-IBQ-035, DEC-IBQ-036, DEC-IBQ-092, DEC-IBQ-093, DEC-IBQ-097, DEC-IBQ-098, DEC-IBQ-100, DEC-IBQ-102, DEC-IBQ-107, DEC-IBQ-121, DEC-IBQ-142, DEC-IBQ-143, DEC-IBQ-144, DEC-LOCAL-QUEUE-END-SESSION, DEC-REQUIRED-DENIAL-EXIT, DEC-UPLOAD-NETWORK, DEC-USER-AGE<br>요구 RQ-FP-015-001, RQ-FP-036-001, RQ-FP-053-001<br>gate GATE-CLOUD-COST-MEASUREMENT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 운영 준비 시 삭제 실행 원장 구조를 사전 개설하고, 보존기간 만료·사용자 요청·서비스 종료로 삭제할 때마다 receipt 행을 추가 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 실행 ID·정책 버전, 대상 유형·기간·범위.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 삭제 실행 원장은 사전개설하되 현재 실행 행은 0개다. Git에는 원본 없이 요청 ID·범위·통제 저장소 ID·hash·기한·검증 결과만 남긴다.

### 포함 내용과 필요한 입력

**포함 내용**

- 실행 ID·정책 버전
- 대상 유형·기간·범위
- 요청·법적 hold
- 삭제·익명화 수량
- 검증·실패·재처리
- 담당·시간·감사 hash

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 실행 ID·정책 버전, 대상 유형·기간·범위.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다.

<a id="ops-24"></a>
## OPS-24 외부 서비스·API 의존성 현황

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | TMAP·음성·저장·인증 등 외부 제공자의 계약·상태·쿼터·대체책을 운영한다. |
| 적용 조건 | 외부 API·SaaS·클라우드 의존성을 공유 시험·운영에 사용하는 경우 활성 |
| 책임 | 작성 운영책임자 · 검토 기술책임자, 보안·개인정보책임자, 지원책임자 · 승인 서비스소유자 |
| 정본 | `docs/deliverables/10-operations/operations-control-registers.md#ops-24` |
| 선행 유형 | DES-27, OPS-01, WS-18 |
| 후행 유형 | 없음 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-042<br>결정 DEC-IBQ-088, DEC-IBQ-097, DEC-IBQ-100, DEC-IBQ-101, DEC-IBQ-107, DEC-IBQ-114, DEC-MOTION-PAUSES-UPLOAD, DEC-PERSISTENT-FAILURE-STOP-ALL<br>요구 RQ-FP-042-001 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 운영책임자 |
| 활성 시점 | 외부 API·SaaS·클라우드 의존성을 공유 시험·운영에 사용하는 경우 활성 |
| 필수 선행 | - 승인된 릴리스·배포 아키텍처·SLO<br>- 운영 환경·계정·외부 서비스·관측성 현황<br>- 지원·보안·복구 책임과 연락 체계 |
| 남겨야 할 증거 | 실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: provider·service·owner, 용도·데이터·endpoint.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- TMAP, object storage·backup, Android 배포·알림 경로의 소유자·quota·장애·대체경로·데이터 경계를 유지한다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `OK_CANDIDATE_CONTENT_COMPLETENESS` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `EXTERNAL_DEPENDENCY_SCHEMA_READY` |
| 필수 record fields | `dependency_id`<br>`name`<br>`data_boundary`<br>`owner_role`<br>`quota_status`<br>`cost_status`<br>`support_status`<br>`review_due_condition`<br>`monitoring_signals`<br>`failure_fallback`<br>`provider_exit_status`<br>`alternate_validation_status`<br>`evidence_refs`<br>`receipt_ref` |
| 현재 경계 | TMAP quota/cost/support=NOT_ESTABLISHED<br>live/deployment/provider-exit/alternate validation=NOT_RUN<br>contract·receipt 원본 없음 |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### 포함 내용과 필요한 입력

**포함 내용**

- provider·service·owner
- 용도·데이터·endpoint
- SLA·지원·status
- credential·quota·비용
- 장애·대체·exit plan
- 약관·검토일

**작성 입력**

- 승인된 릴리스·배포 아키텍처·SLO
- 운영 환경·계정·외부 서비스·관측성 현황
- 지원·보안·복구 책임과 연락 체계

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: provider·service·owner, 용도·데이터·endpoint.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 운영 환경·SLO·책임·외부 의존성·incident 또는 비용 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다.
