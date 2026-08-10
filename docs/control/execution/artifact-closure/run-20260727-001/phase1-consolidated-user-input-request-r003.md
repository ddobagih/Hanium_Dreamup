# Phase 1 통합 사용자 입력 요청서 R003

- 기준일: 2026-07-28
- 대상 범위: `EVIDENCE_FACT_PENDING` 4개 질문 그룹, `SCOPE_DECISION_PENDING` 3개 질문 그룹, `REAL_EVENT_PENDING` 4개 질문 그룹, OWNER 승인 14건, 현재 상태 확인 4건
- 정량 계약: External 사실 6건/4개 그룹, 범위 결정 45건/3개 그룹, 실제 이벤트 11건/4개 그룹으로 총 62건/11개 질문 그룹이다.
- 선행본: `phase1-consolidated-user-input-request-r001.md`, `phase1-consolidated-user-input-request-r002.md`
- 결정 항목 manifest SHA-256: `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd`
- 답변 원칙: 모르는 사실은 `미정`, 존재하지 않는 관계·환경은 `없음`, 아직 발생하지 않은 시험·행사는 `미실행`으로 답한다.
- 효력 경계: 답변은 사실 확정, 작업 경로 결정 또는 검토 입력이다. 실행 증거, 산출물 완료, 배포·출시 승인을 자동으로 의미하지 않는다.
- 제외 사항: 이미 확정된 정책 5개 gate는 이 문서에서 다시 질문하거나 재개하지 않는다.
- 완료 경계: 이 양식의 작성·배포·회수만으로 부여되는 closure credit은 `0`이다. 유효한 서명과 증거가 검토되기 전에는 어떤 상태도 자동 승격하지 않는다.

## A. 사실 확인 4개 그룹

### FACT-01. 데이터셋 출처·권리·불변 기준

- 그룹 ID: `QG-DATASET-SOURCE-RIGHTS-3`
- 대상 ID: `DLV-AIML-01`, `DLV-AIML-02`, `DLV-AIML-03`
- 담당 역할: `DATA_OWNER_AND_PROJECT_SCOPE_OWNER`

기준 시점 현재 실제 사용 중이거나 후보인 데이터의 완전한 출처 목록을 제공해 주십시오. 직접 촬영·사용자 제공 이미지 포함 여부, dataset ID/version/가용 상태, 불변 manifest·`data.yaml`·content hash·split, 출처별 license·동의·학습/변형/재배포 권리, 원본 evidence ID/hash가 필요합니다. 확인되지 않은 값은 `미정`으로 표시하고 확인 담당자와 완료 조건을 적어 주십시오.

| 답변 항목 | 답변 |
|---|---|
| 기준 시점 |  |
| 현재 상태 | `확인됨` / `일부 확인` / `미정` |
| 실제 사용 데이터 출처 전체 목록 |  |
| 후보 데이터 출처 전체 목록 |  |
| 직접 촬영 포함 여부와 상세 | `포함` / `없음` / `미정` |
| 사용자 제공 포함 여부와 상세 | `포함` / `없음` / `미정` |
| dataset ID, version, 가용 상태 |  |
| manifest, `data.yaml`, content hash, split |  |
| 출처별 license·동의·학습/변형/재배포 권리 |  |
| 원본 evidence ID와 SHA-256 |  |
| 미정 항목의 확인 담당자와 완료 조건 |  |
| 답변자·역할·답변일 |  |

### FACT-02. 유료 계약·구독·외부업체 관계

- 그룹 ID: `QG-VENDOR-PAID-CONTRACT-1`
- 대상 ID: `DLV-CLS-13`
- 담당 역할: `PROJECT_OWNER_AND_EXTERNAL_SERVICE_ACCOUNT_OWNER`

기준 시점 현재 유료 계약, 구독 또는 외부업체 관계가 존재하는지 확인해 주십시오. 존재하면 전체 inventory와 근거 receipt를 제공하고, 존재하지 않으면 기준일이 있는 owner 확인을 남겨 주십시오.

| 답변 항목 | 답변 |
|---|---|
| 관계 존재 여부 | `있음` / `없음` / `미정` |
| 기준 시점 |  |
| 전체 inventory 또는 없음 확인 내용 |  |
| 계약·결제·계정 근거 receipt ID와 SHA-256 |  |
| 미정이면 확인 담당자와 완료 조건 |  |
| 답변자·역할·답변일 |  |

### FACT-03. 승인된 staging과 시험 계정

- 그룹 ID: `QG-STAGING-ENDPOINT-ACCOUNT-1`
- 대상 ID: `DLV-SEC-13`
- 담당 역할: `SECURITY_OWNER_AND_STAGING_OWNER`

승인된 원격 staging endpoint와 authorized test account가 현재 존재하는지 확인해 주십시오. 존재하면 비밀값을 제외하고 scope, build, environment, authorization receipt를 제공하고, 존재하지 않으면 기준일이 있는 no-such-environment 확인을 남겨 주십시오.

| 답변 항목 | 답변 |
|---|---|
| staging과 시험 계정 존재 여부 | `있음` / `없음` / `미정` |
| 기준 시점 |  |
| endpoint 식별 정보, scope, build, environment |  |
| authorization receipt ID와 SHA-256 |  |
| 없음 확인 또는 미정 사유 |  |
| 미정이면 확인 담당자와 완료 조건 |  |
| 답변자·역할·답변일 |  |

### FACT-04. 지원 Android 기기 inventory

- 그룹 ID: `QG-SUPPORTED-DEVICE-INVENTORY-1`
- 대상 ID: `DLV-TST-13`
- 담당 역할: `DEVICE_QA_OWNER_AND_PROJECT_SCOPE_OWNER`

현재 승인할 supported Android device/OS/capability matrix와 실제 available-device inventory를 제공해 주십시오. owner, 기준 시점, baseline ID/hash, 사용할 수 없는 조합도 포함해야 합니다.

| 답변 항목 | 답변 |
|---|---|
| 현재 상태 | `확인됨` / `일부 확인` / `미정` |
| 기준 시점 |  |
| 지원 device/OS/capability matrix |  |
| 실제 사용 가능한 기기 inventory |  |
| 사용할 수 없는 조합 |  |
| baseline ID와 SHA-256 |  |
| 미정이면 확인 담당자와 완료 조건 |  |
| 답변자·역할·답변일 |  |

## B. 범위 결정 3개 그룹

범위 답변은 각 ID별로 작성합니다. `포함`은 적용 가능한 작업 또는 실제 이벤트 경로를 선택하는 결정이고, `제외`는 명시적 N/A 승인입니다. `제외`에는 사유, 근거, 유효기간, 재활성화 조건이 필수입니다. `보류`는 권한 있는 결정이나 근거가 아직 없는 상태입니다.

### SCOPE-01. N/A 후보의 적용성 결정

- 그룹 ID: `QG-SCOPE-N_A_CANDIDATE`
- 담당 역할: `PROJECT_SCOPE_OWNER`
- 질문: 잠정적으로 N/A 후보로 분류된 각 산출물에 대해 적용 여부를 확인해 주십시오. `포함`이면 필요한 내부 작업 또는 실제 이벤트 증거 경로를 지정하고, `제외`이면 N/A를 명시적으로 승인하며, 판단 근거가 없으면 `보류`로 남겨 주십시오.

| 대상 ID | 결정 (`포함`/`제외`/`보류`) | 포함 시 경로 (`내부 작업`/`실제 이벤트`) | 사유·근거 | 유효기간 | 재활성화 조건 |
|---|---|---|---|---|---|
| `DLV-CLS-02` |  |  |  |  |  |
| `DLV-CLS-04` |  |  |  |  |  |
| `DLV-CLS-08` |  |  |  |  |  |
| `DLV-CLS-10` |  |  |  |  |  |
| `DLV-CLS-11` |  |  |  |  |  |
| `DLV-CLS-14` |  |  |  |  |  |
| `DLV-CLS-15` |  |  |  |  |  |
| `DLV-CLS-16` |  |  |  |  |  |
| `DLV-OPS-06` |  |  |  |  |  |
| `DLV-OPS-11` |  |  |  |  |  |
| `DLV-OPS-13` |  |  |  |  |  |
| `DLV-OPS-22` |  |  |  |  |  |
| `DLV-REL-13` |  |  |  |  |  |
| `DLV-REL-20` |  |  |  |  |  |
| `DLV-REL-21` |  |  |  |  |  |
| `DLV-TST-23` |  |  |  |  |  |
| `DLV-WS-21` |  |  |  |  |  |

| 결정자 정보 | 답변 |
|---|---|
| 결정자·역할·결정일 |  |
| 결정 receipt ID와 SHA-256 |  |

### SCOPE-02. 한이음 제출·시연과 이후 출시 범위

- 그룹 ID: `QG-SCOPE-HANIUM_VS_RELEASE_SCOPE`
- 담당 역할: `PROJECT_SCOPE_OWNER`
- 질문: 각 산출물의 한이음 제출·시연 범위와 이후 public-beta/production 범위를 구분해 적용성을 결정해 주십시오. `포함`이면 내부 작업 또는 실제 이벤트 경로를 지정하고, `제외`이면 N/A를 명시적으로 승인하며, 아직 결정할 수 없으면 `보류`로 남겨 주십시오.

| 대상 ID | 결정 (`포함`/`제외`/`보류`) | 적용 범위 (`한이음`/`출시`/`모두`) | 포함 시 경로 (`내부 작업`/`실제 이벤트`) | 사유·근거 | 유효기간·재활성화 조건 |
|---|---|---|---|---|---|
| `DLV-AIML-18` |  |  |  |  |  |
| `DLV-AIML-19` |  |  |  |  |  |
| `DLV-AIML-20` |  |  |  |  |  |
| `DLV-AIML-25` |  |  |  |  |  |
| `DLV-DEV-10` |  |  |  |  |  |
| `DLV-DEV-11` |  |  |  |  |  |
| `DLV-DEV-13` |  |  |  |  |  |
| `DLV-DEV-15` |  |  |  |  |  |
| `DLV-OPS-07` |  |  |  |  |  |
| `DLV-REL-03` |  |  |  |  |  |
| `DLV-REL-04` |  |  |  |  |  |
| `DLV-REL-05` |  |  |  |  |  |
| `DLV-REL-06` |  |  |  |  |  |
| `DLV-REL-07` |  |  |  |  |  |
| `DLV-REL-08` |  |  |  |  |  |
| `DLV-REL-09` |  |  |  |  |  |
| `DLV-SEC-18` |  |  |  |  |  |
| `DLV-TST-10` |  |  |  |  |  |
| `DLV-TST-12` |  |  |  |  |  |
| `DLV-TST-15` |  |  |  |  |  |
| `DLV-TST-16` |  |  |  |  |  |
| `DLV-TST-17` |  |  |  |  |  |

| 결정자 정보 | 답변 |
|---|---|
| 결정자·역할·결정일 |  |
| 결정 receipt ID와 SHA-256 |  |

### SCOPE-03. 근거가 있는 N/A 후보의 최종 결정

- 그룹 ID: `QG-SCOPE-N_A_SUPPORTED`
- 담당 역할: `PROJECT_SCOPE_OWNER`
- 질문: Phase 0에서 N/A 근거가 있다고 잠정 분류한 각 산출물에 대해 최종 N/A를 명시적으로 승인할지, N/A를 거부하고 필요한 작업·이벤트 경로로 돌릴지, 결정을 보류할지 선택해 주십시오.

| 대상 ID | 결정 (`제외`/`포함`/`보류`) | 포함 시 경로 (`내부 작업`/`실제 이벤트`) | 사유·근거 | 유효기간 | 재활성화 조건 |
|---|---|---|---|---|---|
| `DLV-AIML-26` |  |  |  |  |  |
| `DLV-DSC-04` |  |  |  |  |  |
| `DLV-OPS-18` |  |  |  |  |  |
| `DLV-REL-14` |  |  |  |  |  |
| `DLV-SEC-14` |  |  |  |  |  |
| `DLV-WS-16` |  |  |  |  |  |

| 결정자 정보 | 답변 |
|---|---|
| 결정자·역할·결정일 |  |
| 결정 receipt ID와 SHA-256 |  |

## C. 실제 이벤트 4개 그룹

이벤트가 아직 발생하지 않았으면 `미실행`, 해당 이벤트 자체가 존재하지 않으면 `없음`, 사실을 모르면 `미정`으로 답합니다. 검증 가능한 receipt를 제공하거나 pending 상태를 유지하거나 scope owner의 N/A 검토를 요청할 수 있습니다.

### EVENT-01. 지원 대상 실기기 실행

- 그룹 ID: `QG-EVENT-DEVICE_RECEIPT`
- 대상 ID: `DLV-WS-13`, `DLV-WS-14`
- 담당 역할: `EVENT_EVIDENCE_OWNER_AND_AUTHORIZED_REVIEWER`
- 질문: 지원 대상 실기기에서 exact build/model hash로 절차를 실행하고 device/runtime, raw log·media hash와 결과 receipt를 제공할 수 있습니까?

| 답변 항목 | 답변 |
|---|---|
| 상태 | `receipt 제공` / `미실행` / `없음` / `미정` / `N/A 검토 요청` |
| 대상 ID별 상태 차이 |  |
| receipt 위치 |  |
| 미실행·없음·미정 사유와 다음 조건 |  |

필수 receipt 필드: `receipt_id`, `executed_at`, `operator_identity`, `device_make_model`, `device_identifier_pseudonym`, `os_and_runtime_version`, `app_build_and_model_sha256`, `procedure_id`, `input_conditions`, `raw_logs_media_and_sha256`, `expected_vs_actual`, `result`, `limitations`, `reviewer`.

### EVENT-02. 실제 field event

- 그룹 ID: `QG-EVENT-FIELD_RECEIPT`
- 대상 ID: `DLV-WS-06`, `DLV-WS-07`, `DLV-WS-09`, `DLV-WS-12`, `DLV-WS-15`, `DLV-WS-17`, `DLV-WS-20`
- 담당 역할: `EVENT_EVIDENCE_OWNER_AND_AUTHORIZED_REVIEWER`
- 질문: 실제 field event를 권한·동의·안전 통제 아래 수행하고 context, exact subject hash, 관측·incident·결과가 포함된 receipt를 제공할 수 있습니까?

| 답변 항목 | 답변 |
|---|---|
| 상태 | `receipt 제공` / `미실행` / `없음` / `미정` / `N/A 검토 요청` |
| 대상 ID별 상태 차이 |  |
| receipt 위치 |  |
| 미실행·없음·미정 사유와 다음 조건 |  |

필수 receipt 필드: `receipt_id`, `event_at`, `site_or_context`, `operator_and_observer_identity`, `participant_or_subject_boundary`, `consent_or_authority_evidence_id`, `safety_controls`, `procedure_id`, `build_model_config_sha256`, `raw_evidence_locators_and_sha256`, `observations`, `result`, `incident_or_deviation`, `reviewer_and_approval`.

### EVENT-03. 실제 모델 검토

- 그룹 ID: `QG-EVENT-MODEL_REVIEW_RECEIPT`
- 대상 ID: `DLV-AIML-22`
- 담당 역할: `EVENT_EVIDENCE_OWNER_AND_AUTHORIZED_REVIEWER`
- 질문: 실제 모델 검토를 수행하고 frozen model/dataset/protocol hash, 원지표, findings와 attributable decision이 포함된 receipt를 제공할 수 있습니까?

| 답변 항목 | 답변 |
|---|---|
| 상태 | `receipt 제공` / `미실행` / `없음` / `미정` / `N/A 검토 요청` |
| receipt 위치 |  |
| 미실행·없음·미정 사유와 다음 조건 |  |

필수 receipt 필드: `receipt_id`, `reviewed_at`, `reviewer_identity`, `reviewer_role`, `authority_basis`, `model_ids_and_sha256`, `dataset_and_protocol_ids_and_sha256`, `raw_metric_evidence_locators_and_sha256`, `findings`, `decision`, `limitations`, `signature_or_attributable_approval`.

### EVENT-04. PoC 실제 실행

- 그룹 ID: `QG-EVENT-POC_RECEIPT`
- 대상 ID: `DLV-DSC-09`
- 담당 역할: `EVENT_EVIDENCE_OWNER_AND_AUTHORIZED_REVIEWER`
- 질문: 정해진 PoC procedure와 frozen inputs/environment로 실제 실행하고 raw output hash와 expected-vs-actual 판정을 포함한 receipt를 제공할 수 있습니까?

| 답변 항목 | 답변 |
|---|---|
| 상태 | `receipt 제공` / `미실행` / `없음` / `미정` / `N/A 검토 요청` |
| receipt 위치 |  |
| 미실행·없음·미정 사유와 다음 조건 |  |

필수 receipt 필드: `receipt_id`, `executed_at`, `operator_identity`, `procedure_id_and_version`, `environment`, `frozen_input_ids_and_sha256`, `build_or_artifact_ids_and_sha256`, `raw_output_locators_and_sha256`, `expected_vs_actual`, `result`, `failure_or_limitation`, `reviewer`.

## 선행 독립 검토 MAJOR closure note

| 검토 항목 | R001 문제 | R002 보완 | 판정 경계 |
|---|---|---|---|
| `R001-MAJOR-01` | ATTEST4에서 `INDEPENDENT_QA_REVIEWER`와 `SERVICE_OWNER` 또는 `PRODUCT_OWNER`의 판정, 서명, 시각이 분리되지 않았고 `reject`와 `return`의 의미도 구분되지 않았다. | E절에서 각 ID별 exact subject 식별부, 독립 QA 판정부, 서비스·제품 owner 판정부를 분리하고 `reject`와 `return` 정의를 명시했다. | 양식 결함은 R002 설계로 닫지만 실제 판정은 아직 입력·검토되지 않았다. Closure credit `0`. |
| `R001-MAJOR-02` | OWNER14와 ATTEST4 판정이 decision-item manifest와 검토한 exact subject bytes에 암호학적으로 결속되지 않았다. | D·E절의 모든 ID에 고정 manifest SHA-256, decision item ID, exact subject locator/SHA-256/byte length, 서명자와 서명 시각을 요구한다. | 결속 필드 추가만으로 승인되지 않는다. 유효한 값과 서명의 독립 검토 전 closure credit `0`. |
| `R002-MAJOR-01` | OWNER14 signer role이 ID별로 고정되지 않았고 ATTEST4 owner signer role이 복수 후보 역할로 열려 있어 권한 없는 서명을 배제할 수 없었다. | D절 OWNER14 전부를 `PROJECT_SCOPE_OWNER`로 고정했다. E절은 `DLV-OPS-17`, `DLV-OPS-19`, `DLV-OPS-23`을 `SERVICE_OWNER`, `DLV-TST-22`를 `PRODUCT_OWNER`로 고정하고 모든 독립 판정을 `INDEPENDENT_QA_REVIEWER`로 고정했다. | signer-authority 양식 결함은 R003 설계로 닫지만 실제 권한·identity·서명은 아직 입력·검증되지 않았다. Closure credit `0`. |

## D. OWNER 승인 14건

각 ID의 exact subject bytes를 검토하고 `승인`, `반려`, `수정요청` 중 하나로 답해 주십시오. 모든 결정은 아래 manifest SHA-256과 해당 decision item에 결속되어야 합니다. `승인`은 식별된 subject에 대한 owner 판단이며 실행 시험이나 출시 승인을 대신하지 않습니다.

- 고정 `decision_item_manifest_sha256`: `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd`
- 고정 signer authority: OWNER14 모든 ID의 signer role은 `PROJECT_SCOPE_OWNER`이다. 다른 role의 서명은 유효하지 않다.
- 유효성 조건: `artifact_id`, `decision_item_id`, `exact_subject_locator`, `exact_subject_sha256`, `exact_subject_byte_length`, 결정값, 사유, 서명자 identity/role, 서명, 서명 시각이 모두 있어야 한다.
- 변경 경계: subject bytes나 manifest가 바뀌면 기존 결정은 새 subject에 승계되지 않으며 재검토가 필요하다.

### OWNER14 exact subject 식별

| 대상 ID | decision item ID | exact subject locator | exact subject SHA-256 | exact subject byte length | decision-item manifest SHA-256 |
|---|---|---|---|---|---|
| `DLV-DES-21` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-DSC-05` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-DSC-06` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-DSC-07` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-DSC-11` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-MGT-03` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-MGT-08` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-REQ-12` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-REQ-13` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-REQ-15` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-SEC-04` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-SEC-05` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-SEC-06` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |
| `DLV-SEC-17` |  |  |  |  | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |

### OWNER14 결정

| 대상 ID | 결정 (`승인`/`반려`/`수정요청`) | 사유 또는 수정 요구사항 | 서명자 identity | 서명자 role | 서명 | signed_at | receipt ID·SHA-256 |
|---|---|---|---|---|---|---|---|
| `DLV-DES-21` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-DSC-05` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-DSC-06` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-DSC-07` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-DSC-11` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-MGT-03` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-MGT-08` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-REQ-12` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-REQ-13` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-REQ-15` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-SEC-04` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-SEC-05` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-SEC-06` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |
| `DLV-SEC-17` |  |  |  | `PROJECT_SCOPE_OWNER` |  |  |  |

## E. 현재 상태 확인 4건

각 ID의 attestation subject가 기준일 현재 사실과 일치하는지 두 역할이 순서대로 판정합니다. 모든 ID에서 `INDEPENDENT_QA_REVIEWER`가 `accept`, `reject`, `return` 중 하나를 먼저 기록합니다. 이어서 `DLV-OPS-17`, `DLV-OPS-19`, `DLV-OPS-23`은 `SERVICE_OWNER`가, `DLV-TST-22`는 `PRODUCT_OWNER`가 `approve`, `reject`, `return` 중 하나를 별도로 기록합니다.

- `reject`: 식별된 subject의 내용이 실질적으로 사실과 다르거나 수용 불가능하다는 최종 부정 판정이다. 반려 사유와 새 subject 재제출 조건이 필요하다.
- `return`: 정보·형식·증거가 불완전해 수정 후 재검토하도록 돌려보내는 판정이다. 실질적 부정 판정인 `reject`와 같지 않다. 수정 요구사항이 필요하다.
- `accept`: 독립 QA가 exact subject를 현재 상태 진술로 수용한다는 판정이다.
- `approve`: ID별로 지정된 owner가 독립 QA 판정까지 포함된 exact attestation을 승인한다는 판정이다.
- 고정 `decision_item_manifest_sha256`: `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd`
- 변경 경계: attestation subject bytes나 manifest가 바뀌면 두 역할 모두 다시 판정해야 한다.
- 완료 경계: QA `accept`만 있거나 owner `approve`만 있는 경우에는 유효한 이중 판정이 아니다. 두 판정이 모두 유효해도 미실행 시험, 실제 이벤트, 배포·출시 승인을 대신하지 않는다.

### `DLV-OPS-17`

| attestation 식별 필드 | 값 |
|---|---|
| artifact ID | `DLV-OPS-17` |
| attestation ID |  |
| decision item ID |  |
| exact subject locator |  |
| exact subject SHA-256 |  |
| exact subject byte length |  |
| state as of |  |
| exact current-state statement |  |
| decision-item manifest SHA-256 | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |

| 판정 역할 | 판정 | reject 사유 | return 수정 요구사항 | signer identity | signer role | signature | decided_at |
|---|---|---|---|---|---|---|---|
| `INDEPENDENT_QA_REVIEWER` | `accept` / `reject` / `return` |  |  |  | `INDEPENDENT_QA_REVIEWER` |  |  |
| Owner | `approve` / `reject` / `return` |  |  |  | `SERVICE_OWNER` |  |  |

### `DLV-OPS-19`

| attestation 식별 필드 | 값 |
|---|---|
| artifact ID | `DLV-OPS-19` |
| attestation ID |  |
| decision item ID |  |
| exact subject locator |  |
| exact subject SHA-256 |  |
| exact subject byte length |  |
| state as of |  |
| exact current-state statement |  |
| decision-item manifest SHA-256 | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |

| 판정 역할 | 판정 | reject 사유 | return 수정 요구사항 | signer identity | signer role | signature | decided_at |
|---|---|---|---|---|---|---|---|
| `INDEPENDENT_QA_REVIEWER` | `accept` / `reject` / `return` |  |  |  | `INDEPENDENT_QA_REVIEWER` |  |  |
| Owner | `approve` / `reject` / `return` |  |  |  | `SERVICE_OWNER` |  |  |

### `DLV-OPS-23`

| attestation 식별 필드 | 값 |
|---|---|
| artifact ID | `DLV-OPS-23` |
| attestation ID |  |
| decision item ID |  |
| exact subject locator |  |
| exact subject SHA-256 |  |
| exact subject byte length |  |
| state as of |  |
| exact current-state statement |  |
| decision-item manifest SHA-256 | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |

| 판정 역할 | 판정 | reject 사유 | return 수정 요구사항 | signer identity | signer role | signature | decided_at |
|---|---|---|---|---|---|---|---|
| `INDEPENDENT_QA_REVIEWER` | `accept` / `reject` / `return` |  |  |  | `INDEPENDENT_QA_REVIEWER` |  |  |
| Owner | `approve` / `reject` / `return` |  |  |  | `SERVICE_OWNER` |  |  |

### `DLV-TST-22`

| attestation 식별 필드 | 값 |
|---|---|
| artifact ID | `DLV-TST-22` |
| attestation ID |  |
| decision item ID |  |
| exact subject locator |  |
| exact subject SHA-256 |  |
| exact subject byte length |  |
| state as of |  |
| exact current-state statement |  |
| decision-item manifest SHA-256 | `0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd` |

| 판정 역할 | 판정 | reject 사유 | return 수정 요구사항 | signer identity | signer role | signature | decided_at |
|---|---|---|---|---|---|---|---|
| `INDEPENDENT_QA_REVIEWER` | `accept` / `reject` / `return` |  |  |  | `INDEPENDENT_QA_REVIEWER` |  |  |
| Owner | `approve` / `reject` / `return` |  |  |  | `PRODUCT_OWNER` |  |  |
