# WalkSafe 종료 인계 원장

> Draft로 작성된 산출물: CLS-07, CLS-08, CLS-09, CLS-10  
> 버전: 0.1.0 · 승인: 미승인  
> 정책 기준선: PB-WALKSAFE-FEATURE-POLICY-1.0.0  
> 실제 실행 증거: 없음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

종료 시 결함·위험·부채와 운영 권한을 어떻게 빠짐없이 넘기는가?

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

- CLS-05 최종 산출물 인덱스 — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN
- CLS-06 최종 소스·릴리스 아카이브 — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN

## 공통 보관 원칙

민감한 위치·영상·음성·서명·운영 원본은 Git에 넣지 않습니다. Git에는 통제 저장소 ID, SHA-256, 권한, 보존기간, 실행·검토 상태만 남깁니다. 실제 결과가 생기면 template을 복사한 새 instance를 append-only로 보관하고 이 정본에는 포인터만 연결합니다.

<a id="cls-05"></a>
## CLS-05 최종 산출물 인덱스

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 모든 최종·조건부·보관 산출물의 버전·위치·해시·승인을 인수자가 찾게 한다. |
| 적용 조건 | 최종 인도·보관 산출물 집합이 동결된 경우 활성 |
| 책임 | 작성 프로젝트관리자 · 검토 제품책임자, 운영책임자, QA책임자 · 승인 프로젝트책임자 |
| 정본 | `docs/deliverables/12-closure/closure-handover-register.md#cls-05` |
| 선행 유형 | DOC-01, REL-04 |
| 후행 유형 | CLS-01, CLS-06 |
| 정책·결정·요구·위험·변경 추적 | 상위 유형 의존성과 정책 기준선에서 상속; 직접 기능 연결은 없음 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 프로젝트관리자 |
| 활성 시점 | 최종 인도·보관 산출물 집합이 동결된 경우 활성 |
| 필수 선행 | - 최종 인수 범위와 승인 릴리스 기준선<br>- 산출물·결함·위험·부채·권한 inventory<br>- 운영 수신자·계약·데이터 보존 결정 |
| 남겨야 할 증거 | 실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 산출물 ID·명칭·범주, 상태·버전·기준선.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 최종 승인본·release·외부 원본·archive의 실제 ID·version·hash·위치를 모은 뒤 final index를 만든다.

### 포함 내용과 필요한 입력

**포함 내용**

- 산출물 ID·명칭·범주
- 상태·버전·기준선
- canonical·release 위치
- hash·signature
- 소유자·승인
- 대체·보존·접근

**작성 입력**

- 최종 인수 범위와 승인 릴리스 기준선
- 산출물·결함·위험·부채·권한 inventory
- 운영 수신자·계약·데이터 보존 결정

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 산출물 ID·명칭·범주, 상태·버전·기준선.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 종료 범위·인수 조건·운영 이관·계약·데이터 처분 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다.

<a id="cls-06"></a>
## CLS-06 최종 소스·릴리스 아카이브

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 재현·유지보수·감사를 위해 승인 source·artifact·model·config·문서를 불변 보관한다. |
| 적용 조건 | 유지보수·감사를 위한 최종 archive를 생성할 때 활성 |
| 책임 | 작성 프로젝트관리자 · 검토 제품책임자, 운영책임자, QA책임자 · 승인 프로젝트책임자 |
| 정본 | `docs/deliverables/12-closure/closure-handover-register.md#cls-06` |
| 선행 유형 | CLS-05, REL-04, REL-06, REL-07, REL-08, REL-22 |
| 후행 유형 | 없음 |
| 정책·결정·요구·위험·변경 추적 | 상위 유형 의존성과 정책 기준선에서 상속; 직접 기능 연결은 없음 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 프로젝트관리자 |
| 활성 시점 | 유지보수·감사를 위한 최종 archive를 생성할 때 활성 |
| 필수 선행 | - 최종 인수 범위와 승인 릴리스 기준선<br>- 산출물·결함·위험·부채·권한 inventory<br>- 운영 수신자·계약·데이터 보존 결정 |
| 남겨야 할 증거 | 정확한 release·환경·수행자·시각·원자료·판정·결함을 append-only evidence instance로 남긴다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: repository bundle·commit·tag, build·model·config.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 최종 source·tag·release·model·config·DB migration·문서를 보존 archive로 만들고 hash·복원 검증을 마친 뒤 증거화한다.

### 포함 내용과 필요한 입력

**포함 내용**

- repository bundle·commit·tag
- build·model·config
- DB schema·migration
- 문서·시험·SBOM
- manifest·hash·signature
- 보관 위치·복원 검증

**작성 입력**

- 최종 인수 범위와 승인 릴리스 기준선
- 산출물·결함·위험·부채·권한 inventory
- 운영 수신자·계약·데이터 보존 결정

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: repository bundle·commit·tag, build·model·config.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 종료 범위·인수 조건·운영 이관·계약·데이터 처분 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실행·발행 원본은 수정하지 않는다. 정정은 새 instance·시각·hash와 이전 원본 관계를 추가한다.

<a id="cls-07"></a>
## CLS-07 미해결 결함

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 종료 시 남은 결함의 영향·우회·소유·수정 목표를 운영 조직에 인계한다. |
| 적용 조건 | 종료 시점에 미해결 결함이 존재하는 경우 활성 |
| 책임 | 작성 프로젝트관리자 · 검토 제품책임자, 운영책임자, QA책임자 · 승인 프로젝트책임자 |
| 정본 | `docs/deliverables/12-closure/closure-handover-register.md#cls-07` |
| 선행 유형 | TST-21 |
| 후행 유형 | CLS-01, CLS-12 |
| 정책·결정·요구·위험·변경 추적 | 상위 유형 의존성과 정책 기준선에서 상속; 직접 기능 연결은 없음 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 프로젝트관리자 |
| 활성 시점 | 종료 시점에 미해결 결함이 존재하는 경우 활성 |
| 필수 선행 | - 최종 인수 범위와 승인 릴리스 기준선<br>- 산출물·결함·위험·부채·권한 inventory<br>- 운영 수신자·계약·데이터 보존 결정 |
| 남겨야 할 증거 | 실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 결함 ID·대상 version, 증상·재현·영향.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 현재 defect 원장은 실행 0건이다. 종료 시점 snapshot을 만들기 위한 구조만 준비하고 0건을 결함 없음의 증거로 쓰지 않는다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `OK_CANDIDATE_CONTENT_COMPLETENESS` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `OPENING_SNAPSHOT_SCHEMA_READY` |
| 필수 record fields | `snapshot_id`<br>`source_register`<br>`as_of`<br>`defect_id`<br>`severity`<br>`affected_release_id`<br>`status`<br>`owner_role`<br>`target_condition`<br>`evidence_refs`<br>`snapshot_sha256` |
| 현재 경계 | formal_execution_count=0<br>closure_snapshot_status=NOT_TAKEN<br>opening snapshot의 0건은 결함 없음의 품질 증거가 아님 |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### 포함 내용과 필요한 입력

**포함 내용**

- 결함 ID·대상 version
- 증상·재현·영향
- severity·priority
- 우회·고지
- owner·목표일·release
- 수용·검증·추적 위치

**작성 입력**

- 최종 인수 범위와 승인 릴리스 기준선
- 산출물·결함·위험·부채·권한 inventory
- 운영 수신자·계약·데이터 보존 결정

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 결함 ID·대상 version, 증상·재현·영향.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 종료 범위·인수 조건·운영 이관·계약·데이터 처분 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다.

<a id="cls-08"></a>
## CLS-08 잔여 위험

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 완화 후에도 남은 제품·안전·보안·운영 위험과 감시·수용 책임을 이관한다. |
| 적용 조건 | 종료 시점에 수용·감시할 잔여 위험이 존재하는 경우 활성 |
| 책임 | 작성 프로젝트관리자 · 검토 제품책임자, 운영책임자, QA책임자 · 승인 프로젝트책임자 |
| 정본 | `docs/deliverables/12-closure/closure-handover-register.md#cls-08` |
| 선행 유형 | MGT-14, SEC-16, TST-21 |
| 후행 유형 | CLS-01, CLS-12, CLS-16 |
| 정책·결정·요구·위험·변경 추적 | ISS-POLICY-FP035-NETWORK-001 정규화 지시 포착·묶음 승인 대기 / 변경요청 REQ-CHG-20260721-002 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 프로젝트관리자 |
| 활성 시점 | 종료 시점에 수용·감시할 잔여 위험이 존재하는 경우 활성 |
| 필수 선행 | - 최종 인수 범위와 승인 릴리스 기준선<br>- 산출물·결함·위험·부채·권한 inventory<br>- 운영 수신자·계약·데이터 보존 결정 |
| 남겨야 할 증거 | 실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 위험 ID·시나리오, 가능성·영향·노출.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 현재 5개 gate와 FP-035 정규화 지시의 묶음 승인 대기 등 잔여위험 포인터를 유지하며 종료 시 소유자·기한·수용 근거를 확정한다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `EXTERNAL_AFTER_INTERNAL` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `RISK_REGISTER_SCHEMA_READY_ACCEPTANCE_EXTERNAL` |
| 필수 record fields | `risk_id`<br>`source_ref`<br>`severity`<br>`likelihood`<br>`user_data_service_impact`<br>`mitigation`<br>`owner_role`<br>`due_condition`<br>`acceptance_decision`<br>`acceptor_identity`<br>`effective_window`<br>`evidence_refs`<br>`receipt_ref` |
| 현재 경계 | risk_acceptance_status=NOT_APPROVED<br>acceptor_identity=None<br>actual acceptance receipt 없음 |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### W9 stable external execution contract

아래 JSON은 builder 내부 정본 model의 lossless projection입니다. 실제 event·decision·evidence·receipt가 아니며 overlay를 source로 참조하지 않습니다.

<!-- W9-EXTERNAL-CONTRACT-START W9-EXT-CLS-08 -->
```json
{
  "approver_role": "프로젝트책임자",
  "artifact_type_id": "CLS-08",
  "catalog_artifact_id": "DLV-CLS-08",
  "completion_test": [
    "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 위험 ID·시나리오, 가능성·영향·노출.",
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
    "due_condition": "BEFORE_PROJECT_CLOSURE_AND_AT_EACH_ACCEPTANCE_REVIEW",
    "review_due_at": null
  },
  "evidence_schema": {
    "catalog_fields": [
      "위험 ID·시나리오",
      "가능성·영향·노출",
      "기존 통제·효과",
      "잔여 등급",
      "모니터링·비상조치",
      "수용자·재검토일"
    ],
    "lifecycle_fields": [
      "risk_id",
      "source_ref",
      "severity",
      "likelihood",
      "user_data_service_impact",
      "mitigation",
      "owner_role",
      "due_condition",
      "acceptance_decision",
      "acceptor_identity",
      "effective_window",
      "evidence_refs",
      "receipt_ref"
    ]
  },
  "external_authority": "PROJECT_OWNER_EXPLICIT_SCOPED_ACCEPTANCE_OR_REJECTION",
  "external_trigger": "종료 시점에 수용·감시할 잔여 위험이 존재하는 경우 활성",
  "fake_event_or_receipt_allowed": false,
  "internal_action": "Record mitigation and monitoring for every residual risk, then obtain an explicit scoped acceptance or rejection from the authorized approver.",
  "normative_model": "BUILDER_INTERNAL_CANONICAL",
  "procedure": [
    "실제 잔여 위험과 activation 근거를 식별한다.",
    "모든 lifecycle field와 mitigation·monitoring을 채우거나 소유자·기한이 있는 차단 상태로 둔다.",
    "승인자가 exact scope와 effective window를 명시적으로 수용 또는 거절한다.",
    "실제 decision과 acceptor identity를 receipt hash binding으로 결속한다."
  ],
  "receipt_schema": [
    "repository_relative_path",
    "sha256",
    "byte_length",
    "source_event_or_decision_id",
    "occurred_or_effective_at",
    "operator_or_acceptor_identity"
  ],
  "release_impact": "승인된 실제 risk acceptance receipt 전에는 프로젝트 종료·인계를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
  "required_inputs": [
    "최종 인수 범위와 승인 릴리스 기준선",
    "산출물·결함·위험·부채·권한 inventory",
    "운영 수신자·계약·데이터 보존 결정"
  ],
  "responsible_role": "프로젝트관리자",
  "stable_id": "W9-EXT-CLS-08"
}
```
<!-- W9-EXTERNAL-CONTRACT-END W9-EXT-CLS-08 -->


### Ready25 내부 작성·실행 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `IN_SCOPE_CLOSURE_RISK_ACCEPTANCE_NOT_TRIGGERED` |
| accepted | `false` |
| approval / actual event / formal evidence / release credit | `0 / 0 / 0 / 0` |
| project/service/product owner | 김민호 / 김민호 / 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- 5개 gate와 FP-035를 포함한 잔여위험 source pointer와 record schema를 유지한다.
- 위험별 mitigation·monitoring·owner·due condition을 실제 source에서만 채운다.
- acceptor identity·acceptance decision·receipt는 실제 종료 검토 전 비워 둔다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-CLS-08-REAL-TRIGGER-OR-EVIDENCE-PENDING` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_PROJECT_CLOSURE_AND_EACH_RISK_ACCEPTANCE_REVIEW` / due_at=`None` |
| `READY25-CLS-08-INDEPENDENT-QA-UNASSIGNED` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_CONTENT_ACCEPTANCE_OR_PROJECT_CLOSURE` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- 위험 ID·시나리오
- 가능성·영향·노출
- 기존 통제·효과
- 잔여 등급
- 모니터링·비상조치
- 수용자·재검토일

**작성 입력**

- 최종 인수 범위와 승인 릴리스 기준선
- 산출물·결함·위험·부채·권한 inventory
- 운영 수신자·계약·데이터 보존 결정

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 위험 ID·시나리오, 가능성·영향·노출.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 종료 범위·인수 조건·운영 이관·계약·데이터 처분 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다.

<a id="cls-09"></a>
## CLS-09 기술부채

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / NOT_APPROVED` |
| 목적 | 종료 시점의 구조·시험·문서·인프라·모델 부채를 비용과 후속 계획으로 넘긴다. |
| 적용 조건 | 종료 시점에 후속 조직이 상환할 기술부채가 존재하는 경우 활성 |
| 책임 | 작성 프로젝트관리자 · 검토 제품책임자, 운영책임자, QA책임자 · 승인 프로젝트책임자 |
| 정본 | `docs/deliverables/12-closure/closure-handover-register.md#cls-09` |
| 선행 유형 | OPS-21 |
| 후행 유형 | CLS-01, CLS-12 |
| 정책·결정·요구·위험·변경 추적 | 상위 유형 의존성과 정책 기준선에서 상속; 직접 기능 연결은 없음 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 프로젝트관리자 |
| 활성 시점 | 종료 시점에 후속 조직이 상환할 기술부채가 존재하는 경우 활성 |
| 필수 선행 | - 최종 인수 범위와 승인 릴리스 기준선<br>- 산출물·결함·위험·부채·권한 inventory<br>- 운영 수신자·계약·데이터 보존 결정 |
| 남겨야 할 증거 | 실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 부채 ID·위치, 발생 배경·의도성.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 운영·개선으로 넘길 기술부채의 영향·우선순위·소유자·목표 시점을 기록한다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `OK_CANDIDATE_CONTENT_COMPLETENESS` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `TECHNICAL_DEBT_SCHEMA_READY` |
| 필수 record fields | `debt_id`<br>`source_ref`<br>`impact`<br>`priority`<br>`owner_role`<br>`due_condition`<br>`closure_criteria`<br>`evidence_refs`<br>`risk_acceptance_status`<br>`acceptance_receipt_ref` |
| 현재 경계 | OPS-21 원장을 정본으로 사용<br>closure_snapshot_status=NOT_TAKEN<br>risk acceptance=NOT_APPROVED |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### 포함 내용과 필요한 입력

**포함 내용**

- 부채 ID·위치
- 발생 배경·의도성
- 영향·복리 비용
- 우선순위·trigger
- 상환 선택·노력
- owner·목표·상태

**작성 입력**

- 최종 인수 범위와 승인 릴리스 기준선
- 산출물·결함·위험·부채·권한 inventory
- 운영 수신자·계약·데이터 보존 결정

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 부채 ID·위치, 발생 배경·의도성.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 종료 범위·인수 조건·운영 이관·계약·데이터 처분 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다.

<a id="cls-10"></a>
## CLS-10 운영 소유권·권한 이관

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT 절차 / 실행 결과 NOT_RUN` |
| 목적 | 서비스·계정·데이터·인프라·모델의 책임과 최소권한을 수신자에게 검증하며 넘긴다. |
| 적용 조건 | 서비스·자산·계정의 운영 책임을 다른 주체에게 이관할 때 활성 |
| 책임 | 작성 프로젝트관리자 · 검토 제품책임자, 운영책임자, QA책임자 · 승인 프로젝트책임자 |
| 정본 | `docs/deliverables/12-closure/closure-handover-register.md#cls-10` |
| 선행 유형 | OPS-02, REL-20 |
| 후행 유형 | CLS-01, CLS-12, CLS-16 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-054<br>결정 DEC-IBQ-125, DEC-IBQ-139, DEC-IBQ-140, DEC-IBQ-144<br>요구 RQ-FP-054-001<br>gate GATE-RAW-COLLECTION-RELEASE-REVIEW |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 프로젝트관리자 |
| 활성 시점 | 서비스·자산·계정의 운영 책임을 다른 주체에게 이관할 때 활성 |
| 필수 선행 | - 최종 인수 범위와 승인 릴리스 기준선<br>- 산출물·결함·위험·부채·권한 inventory<br>- 운영 수신자·계약·데이터 보존 결정 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자산·service owner, 계정·역할·접근.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 서비스를 계속 운영하면 운영 수신자에게 계정·권한·키·지원·backlog·risk를 최소권한으로 이관하고 실제 확인 전에는 완료 처리하지 않는다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `EXTERNAL_AFTER_INTERNAL` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `HANDOVER_SCHEMA_READY_EXECUTION_EXTERNAL` |
| 필수 record fields | `handover_id`<br>`branch`<br>`recipient_identity`<br>`operator_identity`<br>`scope`<br>`account_permission_inventory_refs`<br>`secret_reference_ids`<br>`backlog_and_risk_refs`<br>`support_boundary`<br>`approval_status`<br>`effective_at`<br>`receipt_ref` |
| 현재 경계 | recipient_identity=None<br>operator_identity=None<br>approval_status=NOT_APPROVED<br>actual handover=NOT_RUN |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### W9 stable external execution contract

아래 JSON은 builder 내부 정본 model의 lossless projection입니다. 실제 event·decision·evidence·receipt가 아니며 overlay를 source로 참조하지 않습니다.

<!-- W9-EXTERNAL-CONTRACT-START W9-EXT-CLS-10 -->
```json
{
  "approver_role": "프로젝트책임자",
  "artifact_type_id": "CLS-10",
  "catalog_artifact_id": "DLV-CLS-10",
  "completion_test": [
    "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자산·service owner, 계정·역할·접근.",
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
    "due_condition": "BEFORE_OPERATIONAL_RESPONSIBILITY_TRANSFER_EFFECTIVE_AT",
    "review_due_at": null
  },
  "evidence_schema": {
    "catalog_fields": [
      "자산·service owner",
      "계정·역할·접근",
      "credential 회전·전달 방식",
      "runbook·지원·연락",
      "권한 test·회수",
      "양측 확인·일시"
    ],
    "lifecycle_fields": [
      "handover_id",
      "branch",
      "recipient_identity",
      "operator_identity",
      "scope",
      "account_permission_inventory_refs",
      "secret_reference_ids",
      "backlog_and_risk_refs",
      "support_boundary",
      "approval_status",
      "effective_at",
      "receipt_ref"
    ]
  },
  "external_authority": "PROJECT_OWNER_AND_NAMED_RECIPIENT_BILATERAL_CONFIRMATION",
  "external_trigger": "서비스·자산·계정의 운영 책임을 다른 주체에게 이관할 때 활성",
  "fake_event_or_receipt_allowed": false,
  "internal_action": "Execute ownership and least-privilege handover with named recipient/operator, verification, revocation boundary and bilateral confirmation.",
  "normative_model": "BUILDER_INTERNAL_CANONICAL",
  "procedure": [
    "실제 이관 decision과 named recipient/operator를 확인한다.",
    "자산·계정·권한·backlog·risk·support 범위를 최소권한으로 대조한다.",
    "전달·검증·기존 권한 회수 경계를 양측이 확인한다.",
    "effective time과 양측 identity를 실제 handover receipt에 hash 결속한다."
  ],
  "receipt_schema": [
    "repository_relative_path",
    "sha256",
    "byte_length",
    "source_event_or_decision_id",
    "occurred_or_effective_at",
    "operator_or_acceptor_identity"
  ],
  "release_impact": "named recipient의 실제 양측 인계 receipt 전에는 운영 이관·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
  "required_inputs": [
    "최종 인수 범위와 승인 릴리스 기준선",
    "산출물·결함·위험·부채·권한 inventory",
    "운영 수신자·계약·데이터 보존 결정"
  ],
  "responsible_role": "프로젝트관리자",
  "stable_id": "W9-EXT-CLS-10"
}
```
<!-- W9-EXTERNAL-CONTRACT-END W9-EXT-CLS-10 -->


### Ready25 내부 작성·실행 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `IN_SCOPE_HANDOVER_RECIPIENT_UNRESOLVED` |
| accepted | `false` |
| approval / actual event / formal evidence / release credit | `0 / 0 / 0 / 0` |
| project/service/product owner | 김민호 / 김민호 / 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- 자산·계정·권한·secret reference·backlog·risk·support 이관 checklist를 유지한다.
- 현재 운영 지속 branch를 보존하고 named recipient/operator는 unresolved로 둔다.
- 권한 test·회수·양측 확인·effective time·receipt는 실제 이관 전 생성하지 않는다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-CLS-10-REAL-TRIGGER-OR-EVIDENCE-PENDING` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_OPERATIONAL_HANDOVER_EFFECTIVE_AT` / due_at=`None` |
| `READY25-CLS-10-INDEPENDENT-QA-UNASSIGNED` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_CONTENT_ACCEPTANCE_OR_PROJECT_CLOSURE` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- 자산·service owner
- 계정·역할·접근
- credential 회전·전달 방식
- runbook·지원·연락
- 권한 test·회수
- 양측 확인·일시

**작성 입력**

- 최종 인수 범위와 승인 릴리스 기준선
- 산출물·결함·위험·부채·권한 inventory
- 운영 수신자·계약·데이터 보존 결정

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자산·service owner, 계정·역할·접근.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 종료 범위·인수 조건·운영 이관·계약·데이터 처분 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.
