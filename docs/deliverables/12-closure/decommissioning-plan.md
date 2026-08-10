# WalkSafe 운영 이관·서비스 폐기 계획

> Draft로 작성된 산출물: CLS-14, CLS-15, CLS-16  
> 버전: 0.1.0 · 승인: 미승인  
> 정책 기준선: PB-WALKSAFE-FEATURE-POLICY-1.0.0  
> 실제 실행 증거: 없음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

운영 이관과 서비스 폐기를 나눠 데이터·계정·키·인프라를 어떻게 처리하는가?

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

- CLS-13 비용·계약·외부업체 종료 — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN

## 공통 보관 원칙

민감한 위치·영상·음성·서명·운영 원본은 Git에 넣지 않습니다. Git에는 통제 저장소 ID, SHA-256, 권한, 보존기간, 실행·검토 상태만 남깁니다. 실제 결과가 생기면 template을 복사한 새 instance를 append-only로 보관하고 이 정본에는 포인터만 연결합니다.

<a id="cls-13"></a>
## CLS-13 비용·계약·외부업체 종료

| 항목 | 현재 계약 |
|---|---|
| 상태 | `PLANNED / NOT_RUN` |
| 목적 | 미지급·갱신·라이선스·데이터 반환·접근 회수 등 외부 관계를 정리한다. |
| 적용 조건 | 유료 계약·외부업체·구독 관계가 존재하는 경우 활성 |
| 책임 | 작성 프로젝트관리자 · 검토 제품책임자, 운영책임자, QA책임자, 법무·라이선스검토자 · 승인 프로젝트책임자 |
| 정본 | `docs/deliverables/12-closure/decommissioning-plan.md#cls-13` |
| 선행 유형 | CLS-01, MGT-08, REL-20 |
| 후행 유형 | 없음 |
| 정책·결정·요구·위험·변경 추적 | 상위 유형 의존성과 정책 기준선에서 상속; 직접 기능 연결은 없음 |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `PLANNED_NOT_RUN` |
| 실행 책임 | 프로젝트관리자 |
| 활성 시점 | 유료 계약·외부업체·구독 관계가 존재하는 경우 활성 |
| 필수 선행 | - 최종 인수 범위와 승인 릴리스 기준선<br>- 산출물·결함·위험·부채·권한 inventory<br>- 운영 수신자·계약·데이터 보존 결정 |
| 남겨야 할 증거 | 서명·발행된 외부 원본은 Git 밖 통제 저장소에 두고 발행자·시각·저장 ID·SHA-256·효력상태를 기록한다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 계약·업체·서비스 목록, 납품·검수·정산.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 실제 계약·비용·외부업체가 식별되고 정산·권한회수·자료반환이 완료된 뒤 외부 증거를 연결한다.

### 포함 내용과 필요한 입력

**포함 내용**

- 계약·업체·서비스 목록
- 납품·검수·정산
- 갱신·해지·위약
- 데이터·자산 반환
- 계정·권한 회수
- 확인서·잔여 의무

**작성 입력**

- 최종 인수 범위와 승인 릴리스 기준선
- 산출물·결함·위험·부채·권한 inventory
- 운영 수신자·계약·데이터 보존 결정

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 계약·업체·서비스 목록, 납품·검수·정산.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 종료 범위·인수 조건·운영 이관·계약·데이터 처분 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실행·발행 원본은 수정하지 않는다. 정정은 새 instance·시각·hash와 이전 원본 관계를 추가한다.

<a id="cls-14"></a>
## CLS-14 데이터 보존·이관·삭제

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT 절차 / 실행 결과 NOT_RUN` |
| 목적 | 종료 후 데이터별 법적·운영 보존, 새 소유자 이관, 안전한 삭제와 증거를 확정한다. |
| 적용 조건 | 종료 시 보존·이관·삭제할 데이터가 존재하는 경우 활성 |
| 책임 | 작성 프로젝트관리자 · 검토 제품책임자, 운영책임자, QA책임자, 보안·개인정보책임자 · 승인 프로젝트책임자 |
| 정본 | `docs/deliverables/12-closure/decommissioning-plan.md#cls-14` |
| 선행 유형 | CLS-01, CLS-16, OPS-23, SEC-07 |
| 후행 유형 | 없음 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-015, FP-054<br>결정 DEC-IBQ-035, DEC-IBQ-036, DEC-IBQ-125, DEC-IBQ-139, DEC-IBQ-140, DEC-IBQ-144, DEC-REQUIRED-DENIAL-EXIT, DEC-USER-AGE<br>요구 RQ-FP-015-001, RQ-FP-054-001<br>gate GATE-RAW-COLLECTION-RELEASE-REVIEW |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 프로젝트관리자 |
| 활성 시점 | 종료 시 보존·이관·삭제할 데이터가 존재하는 경우 활성 |
| 필수 선행 | - 최종 인수 범위와 승인 릴리스 기준선<br>- 산출물·결함·위험·부채·권한 inventory<br>- 운영 수신자·계약·데이터 보존 결정 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 데이터 inventory·분류, 보존 근거·기간.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 운영 이관이면 승인된 보존·접근 책임을 함께 넘기고, 서비스 폐기이면 원본·가공본·backup별 삭제기한과 증거를 실행한다. 현재 실행 결과는 NOT_RUN이다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `EXTERNAL_AFTER_INTERNAL` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `DATA_DISPOSITION_SCHEMA_READY_EXECUTION_EXTERNAL` |
| 필수 record fields | `action_id`<br>`branch`<br>`data_class`<br>`storage_location`<br>`technical_retention_target`<br>`legal_basis_status`<br>`transfer_format`<br>`encryption_control`<br>`deletion_or_transfer_action`<br>`backup_reconciliation`<br>`notification_status`<br>`operator_identity`<br>`approval_status`<br>`execution_status`<br>`receipt_ref` |
| 현재 경계 | legal basis와 최종 한국어 고지=NOT_APPROVED<br>actual transfer=NOT_RUN<br>actual deletion=NOT_RUN<br>external receipt 없음 |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### W9 stable external execution contract

아래 JSON은 builder 내부 정본 model의 lossless projection입니다. 실제 event·decision·evidence·receipt가 아니며 overlay를 source로 참조하지 않습니다.

<!-- W9-EXTERNAL-CONTRACT-START W9-EXT-CLS-14 -->
```json
{
  "approver_role": "프로젝트책임자",
  "artifact_type_id": "CLS-14",
  "catalog_artifact_id": "DLV-CLS-14",
  "completion_test": [
    "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 데이터 inventory·분류, 보존 근거·기간.",
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
    "due_condition": "BEFORE_EACH_TRANSFER_OR_DELETION_AND_PROJECT_CLOSURE",
    "review_due_at": null
  },
  "evidence_schema": {
    "catalog_fields": [
      "데이터 inventory·분류",
      "보존 근거·기간",
      "이관 대상·형식·암호화",
      "삭제·익명화·backup",
      "사용자·기관 통지",
      "검증·승인 기록",
      "선행 승인된 CLS-16 폐기계획 ID와 데이터 보존·이관·삭제 실행 receipt"
    ],
    "lifecycle_fields": [
      "action_id",
      "branch",
      "data_class",
      "storage_location",
      "technical_retention_target",
      "legal_basis_status",
      "transfer_format",
      "encryption_control",
      "deletion_or_transfer_action",
      "backup_reconciliation",
      "notification_status",
      "operator_identity",
      "approval_status",
      "execution_status",
      "receipt_ref"
    ]
  },
  "external_authority": "PROJECT_OWNER_APPROVED_DATA_BRANCH_WITH_APPLICABLE_LEGAL_AUTHORITY",
  "external_trigger": "종료 시 보존·이관·삭제할 데이터가 존재하는 경우 활성",
  "fake_event_or_receipt_allowed": false,
  "internal_action": "Execute the approved retention, transfer or deletion branch for each data class and reconcile backups and notifications.",
  "normative_model": "BUILDER_INTERNAL_CANONICAL",
  "procedure": [
    "각 data class·storage location의 승인된 branch와 법적 권한을 확인한다.",
    "보존·이관·삭제, encryption, backup reconciliation, notification을 class별 실행한다.",
    "operator와 승인 범위 및 미처리 예외를 검증한다.",
    "실제 transfer/deletion event와 receipt를 hash 결속한다."
  ],
  "receipt_schema": [
    "repository_relative_path",
    "sha256",
    "byte_length",
    "source_event_or_decision_id",
    "occurred_or_effective_at",
    "operator_or_acceptor_identity"
  ],
  "release_impact": "법적 근거·고지 승인과 실제 class별 receipt 전에는 데이터 처분·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
  "required_inputs": [
    "최종 인수 범위와 승인 릴리스 기준선",
    "산출물·결함·위험·부채·권한 inventory",
    "운영 수신자·계약·데이터 보존 결정"
  ],
  "responsible_role": "프로젝트관리자",
  "stable_id": "W9-EXT-CLS-14"
}
```
<!-- W9-EXTERNAL-CONTRACT-END W9-EXT-CLS-14 -->


### Ready25 내부 작성·실행 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `IN_SCOPE_DATA_DISPOSITION_EVENT_NOT_TRIGGERED` |
| accepted | `false` |
| approval / actual event / formal evidence / release credit | `0 / 0 / 0 / 0` |
| project/service/product owner | 김민호 / 김민호 / 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- data class·storage location·잠정 retention/transfer/deletion branch schema를 유지한다.
- 법적 근거·최종 한국어 고지·operator·backup reconciliation을 blocker로 둔다.
- 실제 transfer/deletion과 receipt는 승인된 종료 branch 전 생성하지 않는다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-CLS-14-REAL-TRIGGER-OR-EVIDENCE-PENDING` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_EACH_DATA_TRANSFER_OR_DELETION_AND_PROJECT_CLOSURE` / due_at=`None` |
| `READY25-CLS-14-INDEPENDENT-QA-UNASSIGNED` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_CONTENT_ACCEPTANCE_OR_PROJECT_CLOSURE` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- 데이터 inventory·분류
- 보존 근거·기간
- 이관 대상·형식·암호화
- 삭제·익명화·backup
- 사용자·기관 통지
- 검증·승인 기록
- 선행 승인된 CLS-16 폐기계획 ID와 데이터 보존·이관·삭제 실행 receipt

**작성 입력**

- 최종 인수 범위와 승인 릴리스 기준선
- 산출물·결함·위험·부채·권한 inventory
- 운영 수신자·계약·데이터 보존 결정

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 데이터 inventory·분류, 보존 근거·기간.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 종료 범위·인수 조건·운영 이관·계약·데이터 처분 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="cls-15"></a>
## CLS-15 계정·키·인프라 정리

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT 절차 / 실행 결과 NOT_RUN` |
| 목적 | 불필요한 계정·secret·도메인·서버·저장소를 회수·회전·폐기하고 비용·공격면을 제거한다. |
| 적용 조건 | 프로젝트 전용 계정·key·도메인·인프라가 존재하는 경우 활성 |
| 책임 | 작성 프로젝트관리자 · 검토 제품책임자, 운영책임자, QA책임자, 보안·개인정보책임자 · 승인 프로젝트책임자 |
| 정본 | `docs/deliverables/12-closure/decommissioning-plan.md#cls-15` |
| 선행 유형 | CLS-01, CLS-16, OPS-15, REL-20, SEC-09 |
| 후행 유형 | 없음 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-054<br>결정 DEC-IBQ-125, DEC-IBQ-139, DEC-IBQ-140, DEC-IBQ-144<br>요구 RQ-FP-054-001<br>gate GATE-RAW-COLLECTION-RELEASE-REVIEW |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 프로젝트관리자 |
| 활성 시점 | 프로젝트 전용 계정·key·도메인·인프라가 존재하는 경우 활성 |
| 필수 선행 | - 최종 인수 범위와 승인 릴리스 기준선<br>- 산출물·결함·위험·부채·권한 inventory<br>- 운영 수신자·계약·데이터 보존 결정 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자산·계정 inventory, 유지·이관·폐기 판정.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 운영 이관이면 최소권한으로 소유권을 이전하고, 폐기이면 계정·세션·키·인프라를 검증 순서로 회수한다. 단일 관리자 복구수단을 같은 휴대전화 안에서만 처리하지 않는다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `EXTERNAL_AFTER_INTERNAL` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `ACCESS_SECRET_INFRA_SCHEMA_READY_EXECUTION_EXTERNAL` |
| 필수 record fields | `asset_id`<br>`asset_type`<br>`current_controller`<br>`target_controller`<br>`action_transfer_rotate_or_revoke`<br>`secret_reference_id`<br>`least_privilege_check`<br>`recovery_boundary_check`<br>`operator_identity`<br>`approval_status`<br>`effective_at`<br>`verification_result`<br>`receipt_ref` |
| 현재 경계 | secret 값 기록 금지<br>recipient/operator=None<br>transfer/rotation/revocation=NOT_RUN<br>approval=NOT_APPROVED |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### W9 stable external execution contract

아래 JSON은 builder 내부 정본 model의 lossless projection입니다. 실제 event·decision·evidence·receipt가 아니며 overlay를 source로 참조하지 않습니다.

<!-- W9-EXTERNAL-CONTRACT-START W9-EXT-CLS-15 -->
```json
{
  "approver_role": "프로젝트책임자",
  "artifact_type_id": "CLS-15",
  "catalog_artifact_id": "DLV-CLS-15",
  "completion_test": [
    "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자산·계정 inventory, 유지·이관·폐기 판정.",
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
    "due_condition": "BEFORE_EACH_TRANSFER_ROTATION_OR_REVOCATION",
    "review_due_at": null
  },
  "evidence_schema": {
    "catalog_fields": [
      "자산·계정 inventory",
      "유지·이관·폐기 판정",
      "key·certificate 회전",
      "서버·storage·DNS 종료",
      "backup·로그 보존",
      "검증·비용 종료",
      "선행 승인된 CLS-16 폐기계획 ID와 계정·키·인프라 정리 실행 receipt"
    ],
    "lifecycle_fields": [
      "asset_id",
      "asset_type",
      "current_controller",
      "target_controller",
      "action_transfer_rotate_or_revoke",
      "secret_reference_id",
      "least_privilege_check",
      "recovery_boundary_check",
      "operator_identity",
      "approval_status",
      "effective_at",
      "verification_result",
      "receipt_ref"
    ]
  },
  "external_authority": "PROJECT_OWNER_AUTHORIZED_ASSET_RETAIN_TRANSFER_ROTATE_OR_REVOKE_DECISION",
  "external_trigger": "프로젝트 전용 계정·key·도메인·인프라가 존재하는 경우 활성",
  "fake_event_or_receipt_allowed": false,
  "internal_action": "Execute the authorized retain/transfer/rotate/revoke decision for every account, key and infrastructure asset without recording secret values.",
  "normative_model": "BUILDER_INTERNAL_CANONICAL",
  "procedure": [
    "실제 asset inventory와 authorized action을 확인하되 secret 값은 기록하지 않는다.",
    "retain/transfer/rotate/revoke를 최소권한·복구수단 분리 경계에서 실행한다.",
    "target controller, operator, effective time과 검증 결과를 확인한다.",
    "실제 asset action과 receipt를 hash 결속한다."
  ],
  "receipt_schema": [
    "repository_relative_path",
    "sha256",
    "byte_length",
    "source_event_or_decision_id",
    "occurred_or_effective_at",
    "operator_or_acceptor_identity"
  ],
  "release_impact": "모든 asset의 승인 action과 실제 receipt 전에는 권한·인프라 정리나 프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
  "required_inputs": [
    "최종 인수 범위와 승인 릴리스 기준선",
    "산출물·결함·위험·부채·권한 inventory",
    "운영 수신자·계약·데이터 보존 결정"
  ],
  "responsible_role": "프로젝트관리자",
  "stable_id": "W9-EXT-CLS-15"
}
```
<!-- W9-EXTERNAL-CONTRACT-END W9-EXT-CLS-15 -->


### Ready25 내부 작성·실행 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `IN_SCOPE_ASSET_DISPOSITION_EVENT_NOT_TRIGGERED` |
| accepted | `false` |
| approval / actual event / formal evidence / release credit | `0 / 0 / 0 / 0` |
| project/service/product owner | 김민호 / 김민호 / 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- secret 값을 제외한 asset ID·유형·controller·예정 action schema를 유지한다.
- retain/transfer/rotate/revoke, 최소권한, 복구경계 검증 checklist를 둔다.
- 실제 rotation/revocation·비용 종료·receipt는 실행 전 생성하지 않는다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-CLS-15-REAL-TRIGGER-OR-EVIDENCE-PENDING` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_EACH_ASSET_TRANSFER_ROTATION_OR_REVOCATION` / due_at=`None` |
| `READY25-CLS-15-INDEPENDENT-QA-UNASSIGNED` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_CONTENT_ACCEPTANCE_OR_PROJECT_CLOSURE` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- 자산·계정 inventory
- 유지·이관·폐기 판정
- key·certificate 회전
- 서버·storage·DNS 종료
- backup·로그 보존
- 검증·비용 종료
- 선행 승인된 CLS-16 폐기계획 ID와 계정·키·인프라 정리 실행 receipt

**작성 입력**

- 최종 인수 범위와 승인 릴리스 기준선
- 산출물·결함·위험·부채·권한 inventory
- 운영 수신자·계약·데이터 보존 결정

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자산·계정 inventory, 유지·이관·폐기 판정.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 종료 범위·인수 조건·운영 이관·계약·데이터 처분 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.

<a id="cls-16"></a>
## CLS-16 서비스 종료·폐기 계획

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT 절차 / 실행 결과 NOT_RUN` |
| 목적 | 서비스 중단 시 사용자 고지·데이터 권리·대체 수단·기술 철거를 안전한 시간표로 수행한다. |
| 적용 조건 | 서비스 중단 또는 완전 폐기가 승인된 경우 활성 |
| 책임 | 작성 프로젝트관리자 · 검토 제품책임자, 운영책임자, QA책임자, 법무·라이선스검토자, 보안·개인정보책임자 · 승인 프로젝트책임자 |
| 정본 | `docs/deliverables/12-closure/decommissioning-plan.md#cls-16` |
| 선행 유형 | CLS-08, CLS-10 |
| 후행 유형 | CLS-14, CLS-15 |
| 정책·결정·요구·위험·변경 추적 | 기능 정책 FP-054<br>결정 DEC-IBQ-125, DEC-IBQ-139, DEC-IBQ-140, DEC-IBQ-144<br>요구 RQ-FP-054-001<br>gate GATE-RAW-COLLECTION-RELEASE-REVIEW |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN` |
| 실행 책임 | 프로젝트관리자 |
| 활성 시점 | 서비스 중단 또는 완전 폐기가 승인된 경우 활성 |
| 필수 선행 | - 최종 인수 범위와 승인 릴리스 기준선<br>- 산출물·결함·위험·부채·권한 inventory<br>- 운영 수신자·계약·데이터 보존 결정 |
| 남겨야 할 증거 | 적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다. |
| 판정 조건 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 종료 사유·범위·일정, 사용자·이해관계자 고지.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |

### 정해진 작성·운영 규칙

- 운영 이관(CLS-10)과 서비스 폐기는 상호 다른 분기다. 폐기를 결정한 경우 사용자 고지·신규세션 차단·자료처분·외부의존성 해지·검증·보존 순서로 실행한다.

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `EXTERNAL_AFTER_INTERNAL` |
| schema | `STRUCTURED_DRAFT_COMPLETE` |
| lifecycle | `DECOMMISSION_SCHEMA_READY_EXECUTION_EXTERNAL` |
| 필수 record fields | `decommission_id`<br>`decision_branch`<br>`authority_identity`<br>`user_notice_status`<br>`new_session_block_status`<br>`data_disposition_refs`<br>`provider_exit_refs`<br>`account_key_infrastructure_refs`<br>`monitoring_window`<br>`final_verification`<br>`approval_status`<br>`execution_status`<br>`receipt_ref` |
| 현재 경계 | decommission decision/authority=None<br>provider exit test=NOT_RUN<br>decommission execution=NOT_RUN<br>approval=NOT_APPROVED |
| 승인 | `NOT_APPROVED` |
| 실행 | `NOT_RUN` |
| receipt | `NOT_RUN` · 실제 ID `[]` |


### W9 stable external execution contract

아래 JSON은 builder 내부 정본 model의 lossless projection입니다. 실제 event·decision·evidence·receipt가 아니며 overlay를 source로 참조하지 않습니다.

<!-- W9-EXTERNAL-CONTRACT-START W9-EXT-CLS-16 -->
```json
{
  "approver_role": "프로젝트책임자",
  "artifact_type_id": "CLS-16",
  "catalog_artifact_id": "DLV-CLS-16",
  "completion_test": [
    "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 종료 사유·범위·일정, 사용자·이해관계자 고지.",
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
    "due_condition": "BEFORE_DECOMMISSION_AUTHORIZATION_AND_EXECUTION",
    "review_due_at": null
  },
  "evidence_schema": {
    "catalog_fields": [
      "종료 사유·범위·일정",
      "사용자·이해관계자 고지",
      "기능·신규가입 단계 종료",
      "데이터 export·삭제",
      "인프라·계정 철거",
      "지원 종료·최종 검증",
      "CLS-14 데이터 처리와 CLS-15 계정·키·인프라 정리 전에 승인할 폐기 순서·중단점·책임자"
    ],
    "lifecycle_fields": [
      "decommission_id",
      "decision_branch",
      "authority_identity",
      "user_notice_status",
      "new_session_block_status",
      "data_disposition_refs",
      "provider_exit_refs",
      "account_key_infrastructure_refs",
      "monitoring_window",
      "final_verification",
      "approval_status",
      "execution_status",
      "receipt_ref"
    ]
  },
  "external_authority": "PROJECT_OWNER_APPROVED_SERVICE_SHUTDOWN_OR_DECOMMISSION_DECISION",
  "external_trigger": "서비스 중단 또는 완전 폐기가 승인된 경우 활성",
  "fake_event_or_receipt_allowed": false,
  "internal_action": "Approve and execute the ordered service shutdown/decommission branch with notices, data rights, provider exit, checkpoints and final verification.",
  "normative_model": "BUILDER_INTERNAL_CANONICAL",
  "procedure": [
    "실제 폐기 권한·범위·순서·중단점을 승인한다.",
    "사용자 고지와 신규세션 차단 뒤 CLS-14, CLS-15, provider exit를 순서대로 실행한다.",
    "monitoring window와 final verification에서 잔여 접근·자료·비용을 확인한다.",
    "실제 decommission decision, operator와 receipt를 hash 결속한다."
  ],
  "receipt_schema": [
    "repository_relative_path",
    "sha256",
    "byte_length",
    "source_event_or_decision_id",
    "occurred_or_effective_at",
    "operator_or_acceptor_identity"
  ],
  "release_impact": "승인된 폐기 decision과 모든 단계의 실제 receipt 전에는 service decommission·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
  "required_inputs": [
    "최종 인수 범위와 승인 릴리스 기준선",
    "산출물·결함·위험·부채·권한 inventory",
    "운영 수신자·계약·데이터 보존 결정"
  ],
  "responsible_role": "프로젝트관리자",
  "stable_id": "W9-EXT-CLS-16"
}
```
<!-- W9-EXTERNAL-CONTRACT-END W9-EXT-CLS-16 -->


### Ready25 내부 작성·실행 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `NOT_TRIGGERED_OPERATIONS_CONTINUE_NO_SHUTDOWN_DECISION` |
| accepted | `false` |
| approval / actual event / formal evidence / release credit | `0 / 0 / 0 / 0` |
| project/service/product owner | 김민호 / 김민호 / 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

- 현재 branch를 OPERATIONS_CONTINUE로 유지하고 shutdown decision은 없다고 명시한다.
- 미래 폐기는 고지·신규세션 차단·CLS-14·CLS-15·provider exit·최종검증 순서로 계획한다.
- 실제 shutdown authority·operator·execution·receipt는 승인 전 생성하지 않는다.

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
| `READY25-CLS-16-REAL-TRIGGER-OR-EVIDENCE-PENDING` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_ANY_SHUTDOWN_OR_DECOMMISSION_AUTHORIZATION` / due_at=`None` |
| `READY25-CLS-16-INDEPENDENT-QA-UNASSIGNED` | `OPEN` | `PROJECT_OWNER` / 김민호 | `BEFORE_CONTENT_ACCEPTANCE_OR_PROJECT_CLOSURE` / due_at=`None` |


### 포함 내용과 필요한 입력

**포함 내용**

- 종료 사유·범위·일정
- 사용자·이해관계자 고지
- 기능·신규가입 단계 종료
- 데이터 export·삭제
- 인프라·계정 철거
- 지원 종료·최종 검증
- CLS-14 데이터 처리와 CLS-15 계정·키·인프라 정리 전에 승인할 폐기 순서·중단점·책임자

**작성 입력**

- 최종 인수 범위와 승인 릴리스 기준선
- 산출물·결함·위험·부채·권한 inventory
- 운영 수신자·계약·데이터 보존 결정

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 종료 사유·범위·일정, 사용자·이해관계자 고지.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 종료 범위·인수 조건·운영 이관·계약·데이터 처분 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. 계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다.
