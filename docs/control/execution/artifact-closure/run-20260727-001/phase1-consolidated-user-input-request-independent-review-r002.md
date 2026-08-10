# Phase 1 통합 사용자 입력 요청서 독립 검토 R002

## 1. 검토 대상과 권위 source

| 역할 | 경로 | bytes | SHA-256 |
|---|---|---:|---|
| 검토 대상 | `docs/control/execution/artifact-closure/run-20260727-001/phase1-consolidated-user-input-request-r002.md` | `21810` | `2dac1eb1e22a68fae58b4d92a8ddbf362454ef26d91b814ce3b80c6f5f46c00e` |
| historical predecessor | `docs/control/execution/artifact-closure/run-20260727-001/phase1-consolidated-user-input-request-r001.md` | `14076` | `2284c0f76916b70a141d777588df77f2fe9ef316b5eac5dc72bc0290b4f2b3ba` |
| external exact-set authority | `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-external-input-readiness/evidence.json` | `393682` | `02f34544e7e51dd8029a4c15c89e46f6cca1c3a4f5c52c4d0af978ca32c6f3b4` |
| OWNER/ATTEST decision authority | `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-owner-attestation-decision-ready/evidence.json` | `51909` | `c74c0d079c7bad66f98a83775fd5b49a3b39fa9364f5d109438ef2b3dcf367ad` |
| current attestation authority | `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-current-state-attestation/evidence.json` | `38400` | `86c91f1b46f435323ec00d4a9263f747fc4d90461ead5bdd2c2bff033cee51d4` |

R001은 historical predecessor로만 사용했다. 이 검토는 R001을 수정하거나 R001의 기존 판정을 소급 변경하지 않는다.

## 2. 판정

| 항목 | 결과 |
|---|---|
| verdict | `CHANGES_REQUIRED` |
| BLOCKING | `0` |
| MAJOR | `1` |
| MINOR | `0` |
| R001 MAJOR-01 | `PARTIALLY_CLOSED` |
| R001 MAJOR-02 | `CLOSED_AT_REQUEST_CONTRACT_LEVEL` |
| External exact62 | `PASS` |
| OWNER14 exact set | `PASS` |
| ATTEST4 exact set | `PASS` |
| 정책 5 gate 비재개 | `PASS` |
| closure credit | `0` |
| 실행·시험·배포·출시 credit | `0` |

R002는 R001의 두 결함에 필요한 필드와 경계를 대부분 추가했다. 그러나 exact18의 required authority를 ID별로 고정하지 않아 잘못된 역할의 결정도 양식상 허용한다. 따라서 controlled human-input request로 배포하기 전 수정이 필요하다.

## 3. Findings

### MAJOR-001. OWNER14와 ATTEST4의 required authority가 exact decision contract에 고정되지 않았다

권위 packet의 required authority는 다음과 같다.

| 대상 | required authority |
|---|---|
| OWNER14 전부 | `PROJECT_SCOPE_OWNER` |
| `DLV-OPS-17` | `INDEPENDENT_QA_REVIEWER` + `SERVICE_OWNER` |
| `DLV-OPS-19` | `INDEPENDENT_QA_REVIEWER` + `SERVICE_OWNER` |
| `DLV-OPS-23` | `INDEPENDENT_QA_REVIEWER` + `SERVICE_OWNER` |
| `DLV-TST-22` | `INDEPENDENT_QA_REVIEWER` + `PRODUCT_OWNER` |

R002 D절은 signer role 값을 받지만 OWNER14의 허용 역할을 `PROJECT_SCOPE_OWNER`로 고정하지 않는다. R002 E절은 네 ID 모두 owner role을 `SERVICE_OWNER` / `PRODUCT_OWNER` 중 선택하도록 표시한다. 이 표현은 OPS 3건에 `PRODUCT_OWNER`, TST 1건에 `SERVICE_OWNER`가 서명하는 잘못된 조합도 허용한다.

영향은 다음과 같다.

- identity, signature, timestamp가 모두 있어도 권한 없는 역할의 결정이 양식상 완성될 수 있다.
- ATTEST4 dual-authority 행 분리는 존재하지만 ID별 exact authority contract는 닫히지 않았다.
- OWNER14 및 ATTEST4 응답을 권위 packet의 actual decision으로 반영하기 전에 외부 규칙으로 다시 거부해야 한다.

필수 수정은 다음과 같다.

- D절의 14개 결정 행에 signer role을 `PROJECT_SCOPE_OWNER`로 고정한다.
- E절의 OPS 3개 owner 행은 `SERVICE_OWNER`로, `DLV-TST-22` owner 행은 `PRODUCT_OWNER`로 각각 고정한다.
- 다른 역할값은 허용 응답이 아니며 invalid decision임을 명시한다.

## 4. R001 MAJOR closure 검증

### 4.1 R001 MAJOR-01

| 수용 기준 | R002 결과 |
|---|---|
| ATTEST4 ID별 독립 QA와 owner 판정 행 분리 | `PASS` |
| QA `accept` / `reject` / `return` 구분 | `PASS` |
| owner `approve` / `reject` / `return` 구분 | `PASS` |
| `reject`와 `return`의 의미 분리 | `PASS` |
| 두 역할의 identity | `PASS` |
| 두 역할의 signature | `PASS` |
| 두 역할의 decided_at | `PASS` |
| QA 단독 또는 owner 단독 판정의 불충분 경계 | `PASS` |
| ID별 exact owner authority | `FAIL`, MAJOR-001 |

따라서 R001 MAJOR-01은 필드 분리와 판정 의미에 대해서는 닫혔지만 required authority까지 포함한 전체 계약으로는 `PARTIALLY_CLOSED`다.

### 4.2 R001 MAJOR-02

고정 decision-item manifest SHA-256은 권위 packet과 일치한다.

`0d3798d5f3dd0751393741ef54b755cc86bcb9bd1cf0867157a65b983e1a95cd`

OWNER14에는 각 ID별로 다음 결속 필드가 추가됐다.

- `decision item ID`
- `exact subject locator`
- `exact subject SHA-256`
- `exact subject byte length`
- 고정 decision-item manifest SHA-256
- 결정값, signer identity/role, signature, signed_at

ATTEST4에는 각 ID별로 다음 결속 필드가 추가됐다.

- `attestation ID`
- `decision item ID`
- `exact subject locator`
- `exact subject SHA-256`
- `exact subject byte length`
- `state as of`
- `exact current-state statement`
- 고정 decision-item manifest SHA-256
- 두 역할 각각의 decision, identity/role, signature, decided_at

R002는 subject bytes 또는 manifest 변경 시 기존 결정을 승계하지 않고 재검토하도록 명시한다. 따라서 누락된 결속 필드를 보완한다는 요청 계약 수준에서 R001 MAJOR-02는 닫혔다.

현재 필드는 미기입 상태다. 이는 사용자 입력 요청서의 의도와 일치하지만, 실제 응답에서 권위 manifest의 exact tuple과 값이 일치하는지 검증되기 전에는 승인, 상태 승격 또는 closure credit을 부여할 수 없다. R002도 이 경계를 `closure credit 0`으로 정확히 보존한다.

## 5. External 질문 그룹과 exact set

| bucket | 질문 그룹 | artifact 수 | 결과 |
|---|---:|---:|---|
| `EVIDENCE_FACT_PENDING` | `4` | `6` | `PASS` |
| `SCOPE_DECISION_PENDING` | `3` | `45` | `PASS` |
| `REAL_EVENT_PENDING` | `4` | `11` | `PASS` |
| 합계 | `11` | `62` | `PASS` |

### 5.1 FACT4

| 그룹 | 수 | exact set 결과 |
|---|---:|---|
| `QG-DATASET-SOURCE-RIGHTS-3` | `3` | `DLV-AIML-01`, `DLV-AIML-02`, `DLV-AIML-03` 일치 |
| `QG-VENDOR-PAID-CONTRACT-1` | `1` | `DLV-CLS-13` 일치 |
| `QG-STAGING-ENDPOINT-ACCOUNT-1` | `1` | `DLV-SEC-13` 일치 |
| `QG-SUPPORTED-DEVICE-INVENTORY-1` | `1` | `DLV-TST-13` 일치 |

### 5.2 SCOPE3

| 그룹 | 수 | 결과 |
|---|---:|---|
| `QG-SCOPE-N_A_CANDIDATE` | `17` | exact set 일치 |
| `QG-SCOPE-HANIUM_VS_RELEASE_SCOPE` | `22` | exact set 일치 |
| `QG-SCOPE-N_A_SUPPORTED` | `6` | exact set 일치 |

세 scope 그룹의 합집합은 권위 `SCOPE_DECISION_PENDING` 45건과 같고 그룹 간 중복은 없다.

### 5.3 EVENT4

| 그룹 | 수 | exact set 결과 |
|---|---:|---|
| `QG-EVENT-DEVICE_RECEIPT` | `2` | `DLV-WS-13`, `DLV-WS-14` 일치 |
| `QG-EVENT-FIELD_RECEIPT` | `7` | `DLV-WS-06`, `DLV-WS-07`, `DLV-WS-09`, `DLV-WS-12`, `DLV-WS-15`, `DLV-WS-17`, `DLV-WS-20` 일치 |
| `QG-EVENT-MODEL_REVIEW_RECEIPT` | `1` | `DLV-AIML-22` 일치 |
| `QG-EVENT-POC_RECEIPT` | `1` | `DLV-DSC-09` 일치 |

FACT 6, SCOPE 45, EVENT 11은 서로소이며 합집합은 권위 exact62와 같다. 누락, 추가, 중복은 없다.

## 6. OWNER14와 ATTEST4 exact set

OWNER14는 다음 exact set과 일치한다.

`DLV-DES-21`, `DLV-DSC-05`, `DLV-DSC-06`, `DLV-DSC-07`, `DLV-DSC-11`, `DLV-MGT-03`, `DLV-MGT-08`, `DLV-REQ-12`, `DLV-REQ-13`, `DLV-REQ-15`, `DLV-SEC-04`, `DLV-SEC-05`, `DLV-SEC-06`, `DLV-SEC-17`

ATTEST4는 다음 exact set과 일치한다.

`DLV-OPS-17`, `DLV-OPS-19`, `DLV-OPS-23`, `DLV-TST-22`

OWNER14와 ATTEST4의 교집합은 없으며 합집합은 exact18이다. External exact62와 exact18 사이에도 중복이 없다. 전체 요청 대상은 80개 고유 artifact ID다.

## 7. 허용 응답과 경계 검증

| 영역 | 검토 결과 |
|---|---|
| FACT | `있음` / `없음` / `미정` 및 확인 담당자·완료 조건이 사실 receipt 또는 pending 경계와 호환됨 |
| SCOPE | `포함` / `제외` / `보류`가 work/event route, explicit N/A, pending으로 정확히 대응함 |
| EVENT | `receipt 제공`, pending을 유지하는 `미실행` / `없음` / `미정`, `N/A 검토 요청`이 권위 선택지와 호환됨 |
| OWNER | `승인` / `반려` / `수정요청`이 approve / reject / return에 대응함 |
| ATTEST | accept/approve와 reject/return의 의미가 분리됨 |
| required authority | MAJOR-001을 제외하고 응답 필드 구조는 적합함 |

`제외`는 자동 N/A가 아니며 사유, 근거, 유효기간, 재활성화 조건이 있는 attributable scope-owner 결정으로 제한된다. Scope answer는 실행 receipt 또는 release eligibility를 대신하지 않는다.

## 8. 정책, zero-credit 및 비과장

- R002는 이미 확정된 정책 5개 gate를 다시 질문하거나 재개하지 않는다고 명시한다.
- 권위 external packet의 `policy_reopen_claimed=false`와 충돌하지 않는다.
- 양식 작성·배포·회수만으로 부여되는 closure credit을 `0`으로 고정한다.
- 미기입 OWNER14와 ATTEST4를 실제 승인, QA acceptance 또는 state promotion으로 표현하지 않는다.
- `미정`, `없음`, `미실행`을 각각 사실 불명, 관계·환경·이벤트 부재, 아직 실행되지 않은 상태로 구분한다.
- 계획, template, 답변 또는 scope decision을 실제 시험·field event·PoC·모델 검토 receipt로 과장하지 않는다.
- 실행 증거, 산출물 완료, formal test credit, 배포·출시 승인 또는 release eligibility를 주장하지 않는다.

## 9. 최종 verdict

`CHANGES_REQUIRED`

Exact sets, 질문 그룹, manifest 및 exact-subject 결속 필드, dual-decision 구조, zero-credit 경계는 적합하다. 그러나 exact18의 역할 권한을 ID별로 고정하지 않은 MAJOR-001 때문에 현재 R002는 잘못된 authority의 서명을 허용한다. 해당 역할값을 권위 packet과 동일하게 고정한 successor가 필요하다.
