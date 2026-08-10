# 257종 산출물 최종 종결 상세 실행계획

- 계획 ID: `WS-257-CLOSURE-PLAN-20260727-R003`
- 상태: `PLAN_READY_FOR_PHASE0`
- 기준 패키지: `docs/control/execution/artifact-audits/20260727/final-257/`
- 기준 snapshot raw SHA-256: `481339c99f26055f6bbb008a3f4831c029013d2275e0e4a61cab4f7d30444947`
- 기준 상태: `OK 124 / INTERNAL_GAP 48 / EXTERNAL 49 / N_A_CANDIDATE 36 / TOTAL 257`
- 목적: 257종 전부를 증거가 결속된 최종 상태로 만들고, 독립 검수 후 제출 패키지를 봉인한다.
- 변경 원칙: 기존 baseline, W1-W9 기록, final-257 seal은 수정하지 않고 새 successor만 추가한다.
- 1차 검수 기록: `remaining-work-execution-plan-review.md`
- R003 보완 기록: `remaining-work-execution-plan-r003-improvement-review.md`
- 실행 준비 행렬: `remaining-work-readiness-matrix.md`
- 현재 경계: `PLAN_REVISED=true / PHASE0_STARTED=false / EXECUTION_STARTED=false`

## 1. 완료 정의

### 1.1 산출물 완료

257종 산출물 완료는 다음 조건을 모두 만족한 상태다.

- 257개 ID가 고유하며 누락, 중복, unknown, group overlap이 없다.
- 모든 ID가 `OK` 또는 `N_A_APPROVED` 중 하나다.
- `INTERNAL_GAP`, `EXTERNAL`, `N_A_CANDIDATE`가 모두 0이다.
- `OK`에는 요구사항을 충족한 실제 문서, 코드, 시험 또는 외부 실행 증거가 결속된다.
- `N_A_APPROVED`에는 적용 범위 소유자의 판정, 근거, 유효기간, 재개 조건, 독립 검토가 결속된다.
- 모든 제출 evidence에는 repo-relative path, byte length, SHA-256이 있다. 제한 보관 원본은 locator, 원본 SHA-256, 접근 통제, reviewer verification receipt로 대신 결속한다.
- 전 257건 재검증과 독립 검수 결과가 `BLOCKING 0 / MAJOR 0 / MINOR 0`이다.

`N_A_APPROVED`는 새 successor에서 사용할 최종 상태다. 기존 `N_A_CANDIDATE`를 이름만 바꾸는 것이 아니며, 단계 3의 실제 판정과 승인 receipt가 있어야 한다.

### 1.2 산출물, 제출, 출시 상태의 분리

- `ARTIFACT_CLOSURE_COMPLETE`: 257종이 모두 최종 상태이고 전 257건 내용 검수가 끝난 상태다.
- `SUBMISSION_PACKAGE_READY`: 제출 패키지가 독립 검수·봉인됐으나 아직 전송되지 않은 상태다.
- `SUBMISSION_TRANSMITTED`: 한이음 시스템에 실제 전송했고 upload receipt가 있는 상태다.
- `SUBMISSION_ACCEPTED`: 제출처의 실제 접수·수락 receipt가 있는 상태다.
- `PRODUCT_RELEASE_APPROVED`: formal 279, 실제 장치, 법무, 모델, 서명, 배포, 운영, closure gate까지 실제 완료되고 최종 출시 권한자가 승인한 상태다.
- 한이음 제출 범위에 운영 출시가 포함되지 않으면 해당 항목은 권한 있는 적용성 판정을 거쳐 `N_A_APPROVED`가 될 수 있다.
- 제출 패키지 봉인을 제품 출시 승인으로 표현하지 않는다.

### 1.3 자율 실행 상태

- `RUNNING`: 내부 작업 또는 확보된 외부 증거 검증을 진행 중이다.
- `AUTONOMOUS_INTERNAL_COMPLETE`: 사용자 확인 없이 수행 가능한 작업과 외부 action 준비를 모두 끝냈다.
- `WAITING_EXTERNAL`: 실제 권한·입력·사건만 남아 통합 요청서를 만든 뒤 안전하게 대기한다. 이 상태는 Goal 완료가 아니다.
- `RESUMABLE`: 필요한 외부 입력이 도착해 검증을 통과했고 다음 packet을 시작할 수 있다.
- `ARTIFACT_CLOSURE_COMPLETE`: 열린 상태가 0이고 전 257건 내용 검수가 끝났다.
- `BLOCKED_REVIEW`: 재분류 반복, authority 충돌, 증거 진위 불명확으로 독립 판정이 필요하다.

## 2. 공통 실행 규칙

### 2.1 증거 우선 상태 전이

각 ID는 다음 순서로만 전이한다.

1. 현재 gap과 완료 조건을 한 행의 작업 계약으로 고정한다.
2. 문서·코드·시험·외부 행동 중 필요한 실제 작업을 수행한다.
3. 결과 파일의 경로, SHA-256, byte length, 실행 시각, source/build/model/config/environment ID를 기록한다.
4. 작성자와 다른 검수자가 요구사항 충족 여부를 검토한다.
5. 검수 PASS 후에만 successor delta에서 상태를 바꾼다.

실행되지 않은 시험, 미래 승인 약속, 합성 서명, 빈 템플릿, 계획 문서는 완료 증거로 인정하지 않는다.

### 2.1.1 최소 증거 원칙

상태 전이의 최소 구성은 다음 세 가지다.

1. canonical artifact ledger row
2. 원 evidence reference 또는 제한 보관소 binding
3. read-only independent reviewer verdict

- packet 공통 raw evidence는 여러 ID가 참조할 수 있으며 동일 receipt를 복제하지 않는다.
- per-ID builder, checker, manifest는 만들지 않는다.
- checkpoint는 packet terminal 전이, `HEAVY` 경계, 비가역 외부 사건, 안전 중단에서만 만든다.
- 안정된 immutable evidence는 dependency, protocol, staleness가 바뀌지 않으면 재생성하지 않고 hash와 receipt만 검증한다.
- 문서가 다른 문서만 인용해서 구현·시험 완료를 증명하는 것을 금지한다.

### 2.2 packet 실행 계약

모든 packet은 `PLANNED`에서 `RUNNING`으로 바뀌기 전에 다음 work-contract를 채운다.

| 필드 | 필수 내용 |
|---|---|
| scope | exact artifact ID와 action ID |
| inputs | 입력 파일·dataset·source·build·model·config·environment path/hash |
| outputs | 생성·수정할 파일과 evidence 위치 |
| commands | exact argv, cwd, tool version, timeout, 종료 가능한 경계 |
| checker | 성공·실패를 판정할 명령 또는 수동 검수 기준 |
| resource | `LIGHT`, `MODERATE`, `HEAVY`와 예상 메모리·디스크 |
| responsibility | writer/operator, authority, read-only independent reviewer |
| prerequisite | 선행 packet, 승인, credential, 실제 환경 |
| rollback | 실패 시 복구 대상과 보존할 원본 evidence |
| acceptance | 상태 전이를 허용하는 수치·내용·receipt 조건 |

실제 파일과 명령을 확인하지 않은 상태에서 명령을 추정해 계약을 채우지 않는다. 계약이 불완전하면 작업을 시작하지 않고 다른 준비 가능한 packet을 진행한다.

### 2.3 상태 전이 규칙

| 현재 상태 | 허용되는 다음 상태 | 조건 |
|---|---|---|
| `INTERNAL_GAP` | `OK` | 내부 작업, 실제 검증, 독립 검수 완료 |
| `INTERNAL_GAP` | `EXTERNAL` | 내부 준비는 완료됐으나 실제 권한·환경·사건이 외부에만 있음이 입증됨 |
| `INTERNAL_GAP` | `N_A_CANDIDATE` | 적용성 재판정이 필요하다는 근거가 생김. 최종 N/A는 아님 |
| `EXTERNAL` | `OK` | 실제 외부 사건·결정·receipt와 독립 재감사 완료 |
| `EXTERNAL` | `N_A_APPROVED` | 해당 외부 행동이 범위 밖이라는 권한 있는 적용성 승인 완료 |
| `EXTERNAL` | `INTERNAL_GAP` | 외부 실행에서 제품 결함이 발견돼 내부 수정이 필요함. 원 receipt와 외부 재실행 의무를 유지 |
| `N_A_CANDIDATE` | `N_A_APPROVED` | 긍정적 비적용 증거, 권한자 승인, 독립 검토 완료 |
| `N_A_CANDIDATE` | `INTERNAL_GAP` | 적용되며 내부에서 수행할 작업으로 판정 |
| `N_A_CANDIDATE` | `EXTERNAL` | 적용되며 외부 권한·사건·증거가 필요한 작업으로 판정 |
| 모든 최종 상태 | 열린 상태 | stale, 모순, 범위 변경, 증거 무효가 발견되면 fail-closed 재개 |

artifact ledger는 ID당 정확히 한 행만 유지한다. 재작업과 외부 행동은 `{artifact_id, action_id, attempt_id}`의 별도 action queue에 추가하며 artifact 행을 복제하지 않는다. 모든 재분류에는 reason, evidence, `return_state`를 기록하고 동일 상태쌍의 두 번째 반복부터 독립 판정을 요구한다.

### 2.4 병렬 실행과 자원 안전

- 고정 agent 수가 아니라 실제 자원 소비 subprocess 수를 통제한다.
- Phase 0에서 대표 `LIGHT`, `MODERATE`, `HEAVY` 작업을 각각 하나씩 선정한다.
- 실제 실행이 승인되면 `LIGHT`와 `MODERATE`를 단독 측정하고 cooldown한 뒤, 별도 실행 경계에서 worker 1의 bounded `HEAVY` pilot을 측정한다.
- 첫 표본에는 peak memory와 CPU 사용량에 안전계수 `1.5`, 정상 표본 2개 이상에는 `1.3`을 적용한다.
- root scheduler만 `HEAVY` lease를 부여하며, 최초 `HEAVY` 동시 실행 수는 1이다.
- daemon, DB, timer를 새로 만들지 않는다. 작은 launch wrapper, `flock`, atomic resource-state로 admission과 stale lease만 관리한다.
- `MemAvailable`, swap-in/out, CPU·memory·I/O PSI, load/CPU, iowait, disk, OOM, thermal warning, 사용자 세션 heartbeat를 2초 간격으로 측정한다.
- 시작 보존량은 `max(6 GiB, RAM의 25%)`, 긴급 보존량은 `max(4 GiB, RAM의 15%)`를 잠정값으로 사용하고 pilot 결과로 조정한다.
- soft throttle이면 신규 작업을 막고 concurrency를 절반으로 낮춘다. hard stop이면 해당 작업 process group만 종료하고 완료 상태를 남기지 않는다.
- 정상 Wave 2회 후에만 concurrency를 1씩 올린다. `HEAVY=2`는 정상 표본 2개, 계산된 자원 여유, PSI 위반 0일 때만 허용한다.
- pilot 자체는 실제 remediation 실행 단계에서만 수행한다. R003 작성으로 pilot이 실행된 것으로 보지 않는다.

### 2.5 체크포인트와 재작업

- 작업 단위는 개별 FP가 아니라 아래의 공통 원인 packet이다.
- packet마다 작업 계약, 변경 목록, 실행 증거, 검수 결과, 상태 delta를 하나의 checkpoint로 남긴다.
- 매 ID마다 별도 builder·checker를 만들지 않는다. 기존 결정론적 생성기와 공통 successor builder를 우선 재사용한다.
- packet 검수 실패 시 그 packet만 재작업하고, 전 257건 전체 검증은 단계 4에서 한 번 수행한다.
- 외부 입력이 없을 때는 질문 하나씩 멈추지 않고 통합 action packet을 만들고 다른 독립 작업을 계속한다.

run-state는 실행별 `docs/control/execution/artifact-closure/<run-id>/run-state.json` 한 개를 정본으로 사용한다.

- packet 상태: `PLANNED`, `RUNNING`, `EVIDENCE_READY`, `REVIEWED`, `APPLIED`, `FAILED`, `INTERRUPTED`, `WAITING_EXTERNAL`
- 필수 필드: packet ID, attempt ID, artifact/action ID, agent, resource class, input/output hash, exact command ID, 시작·종료 시각, reviewer, predecessor checkpoint hash
- 기록 순서: 임시 파일 작성 → schema·hash 검사 → atomic rename → `COMPLETE` marker 작성
- 상태 delta는 `REVIEWED` evidence와 predecessor hash가 있을 때만 `APPLIED`로 원자 반영한다.
- 종료 시 `RUNNING`이던 attempt는 `INTERRUPTED`로 바꾸고 부분 output을 격리한다. hash가 결속된 완성 evidence만 재사용한다.
- 같은 applied receipt hash가 있으면 delta를 다시 적용하지 않는다.

## 3. 단계 0: 범위·완료 의미·라우팅 동결

### 3.1 목적

실제 파일 수정이나 시험 전에 257건 각각의 완료 의미를 확정한다. 이 단계가 끝나기 전에는 데이터 처리, 모델 학습, 전체 빌드, formal 시험, 외부 사건 실행을 시작하지 않는다.

### 3.2 P0.1 source와 완료 유형 동결

판정 근거 우선순위는 다음과 같다.

1. 사용자 승인 Q&A와 프로젝트 의사결정
2. 적용되는 한이음 제출 기준과 프로젝트 scope
3. 현재 코드, build, dataset, model, 실제 run evidence
4. 기존 문서와 sealed final-257 audit

근거가 충돌하면 조용히 하나를 선택하지 않고 conflict row로 남겨 authority 결정을 받는다.

모든 257 ID에 다음 필드를 지정한다.

| 필드 | 값 |
|---|---|
| `artifact_kind` | `PLAN_DEFINITION`, `IMPLEMENTATION_EVIDENCE`, `TEST_RESULT`, `AUTHORITY_DECISION`, `OPERATING_EVENT_RECORD` |
| `subject_of_truth` | `doc`, `code`, `build`, `dataset`, `model`, `run`, `authority`, `event` |
| `completion_mode` | `DEFINITION_APPROVAL`, `DIRECT_TRACE`, `ACTUAL_RUN`, `ACTUAL_DECISION`, `CURRENT_STATE_ATTESTATION`, `CONDITIONAL_EVENT`, `APPLICABILITY_DECISION` |
| `acceptance_predicate` | 상태 전이를 허용하는 정확한 조건 |
| `minimum_evidence` | 필요한 최소 원 evidence와 binding |
| `authority` | 제안자, 승인자, 독립 reviewer |
| `review_method` | 자동 checker 또는 수동 검수 기준 |

`PLAN_DEFINITION`은 최신성·scope·승인으로 닫을 수 있다. `IMPLEMENTATION_EVIDENCE`는 현재 코드/build 직접 trace, `TEST_RESULT`는 실제 run 또는 유효 immutable receipt, `AUTHORITY_DECISION`은 실제 권한자 결정, `OPERATING_EVENT_RECORD`는 요구사항이 실제 사건을 요구할 때만 event receipt가 필요하다.

### 3.3 P0.2 열린 133건 완료 계약

- `remaining-work-readiness-matrix.md`의 48/49/36 행을 검토해 provisional 값을 확정한다.
- 계획·절차·current-state register로 충분한 항목과 실제 실행이 필요한 항목을 분리한다.
- artifact ledger는 한 ID당 한 행만 유지하고 action attempt는 별도 queue로 관리한다.
- 기존 `OK 124`는 재실행하지 않고 source-of-truth와 evidence binding만 확인한다. 불일치하면 fail-closed로 다시 연다.

검증: exact 257 분류 100%, 열린 133 completion contract 100%, unknown completion meaning 0.

### 3.4 P0.3 N/A·법무·데이터 선행 triage

- N/A 후보 36건은 activation trigger와 authority를 먼저 확인해 `N_A_APPROVED`, `INTERNAL_GAP`, `EXTERNAL`, `INFO_MISSING`으로 route한다.
- data/legal 8건과 내부 데이터·모델 packet에 영향을 주는 권리·동의·개인정보 범위를 우선 결정한다.
- 법적 근거가 미확정된 데이터는 hash 계산을 제외한 정제·학습·field 사용을 시작하지 않는다.
- 모든 N/A와 legal 결정이 끝날 때까지 전체를 멈추지는 않는다. 영향을 받지 않는 읽기 전용 조사와 `LIGHT` 계약 작성만 진행할 수 있다.

검증: N/A/legal owner, authority, decision dependency 100%; 영향받는 `HEAVY` 작업 무승인 시작 0.

### 3.5 P0.4 상태어휘와 consumer 호환성

- `N_A_APPROVED`가 기존 builder, checker, Markdown/JSON projection, manifest consumer에서 안전하게 표현되는지 확인한다.
- 기존에 승인된 terminal N/A 표현이 있으면 재사용한다.
- 새 표현이 필요할 때만 공통 builder와 consumer를 최소 변경하며 새 goal engine이나 per-ID builder를 만들지 않는다.

검증: 상태어휘 결정, backward compatibility rule, migration owner, fail-closed unknown status 처리 확정.

### 3.6 P0.5 canonical facts와 기능 acceptance

- project name, 앱·module ID, API, dataset generation, model 3종, source/build/config, 지원 device·OS, 역할, 용어, 날짜 기준을 기존 정본 register에 결속한다.
- 같은 사실을 여러 문서에서 새로 정의하지 않고 정본을 참조한다.
- 기능 주장은 code path와 실행된 acceptance evidence를 직접 참조한다.
- 문서끼리 일치해도 코드·시험 근거가 없으면 구현 완료로 보지 않는다.

검증: canonical fact conflict 0, 기능 주장 orphan 0, stale UI/API/model/version reference 0.

### 3.7 P0.6 formal 279와 제출 범위

- 279 case 각각을 `SUBMISSION_REQUIRED` 또는 `RELEASE_ONLY`로 분류한다.
- 실행 방식은 `RUN_REQUIRED`, `VALID_RECEIPT_ALLOWED`, `APPROVED_N_A_ALLOWED` 중 하나로 authority가 확정한다.
- skip은 승인 사유와 N/A linkage 없이는 열린 상태다.

### 3.8 P0.7 공수·critical path

| Band | 내부 공수 |
|---|---|
| `S` | 4시간 이하 |
| `M` | 0.5-1.5 person-day |
| `L` | 2-5 person-days |
| `XL` | 5 person-days 초과. 실행 전 분할 필수 |

외부 대기시간은 공수와 섞지 않고 `<=3bd`, `4-10bd`, `>10bd`, `UNKNOWN`으로 별도 기록한다. calendar 일정은 work-contract와 pilot 측정 전에는 확정하지 않는다.

잠정 critical path:

```text
Phase 0 completion semantics and authority
  -> N/A/legal decisions
  -> data/build/design freeze
  -> training/static/test governance
  -> evaluation/device environment
  -> integration/formal
  -> external approval/event
  -> exact-257 replay
  -> independent review
  -> submission seal
```

### 3.9 Phase 0 종료 기준

- exact 257 artifact kind와 subject-of-truth 분류 100%
- 열린 133건 completion contract 100%
- N/A/legal owner·authority·dependency 100%
- 상태어휘와 consumer 호환성 결정
- packet predecessor, effort band, external lead time, critical-path flag 확정
- unknown completion meaning 0
- `PHASE0_STARTED=true`는 실제 위 판정 작업을 시작한 뒤에만 기록

## 4. 단계 1: `INTERNAL_GAP` 48건 해소

### 3.1 목적과 완료 기준

- 목적: 현재 내부에서 작성·구현·실행 가능한 48건을 실제 증거로 닫는다.
- 시작 수량: exact 48, `P0 36 / P1 12`.
- 완료 기준: 기존 48건이 모두 `OK`가 되거나, 내부 수행 가능 부분을 끝낸 뒤 구체적인 외부 completion predicate 또는 적용성 판정 dependency와 독립 검수를 갖춰 다음 queue로 이관된다.
- 최종 프로젝트 완료를 위해 이 단계에서 새로 생긴 `EXTERNAL`과 `N_A_CANDIDATE`는 각각 단계 2와 단계 3으로 반드시 이관한다.
- 교육 acknowledgement, participant/session receipt, 서명, 실제 장치, live service, 운영 권한 같은 인간·물리 sub-action은 모든 내부 packet에서 공통으로 분리해 단계 2 action queue에 넣는다.

### 3.2 실행 Wave

#### Wave I1: 기반 정본화 17건

세 packet은 파일 충돌이 없으면 병렬 실행한다.

- 기본 자원 등급: design·trace·문서 정합화는 `MODERATE`, dataset 전수 hash·clean build·full-history scan·모델 평가·formal 시험은 `HEAVY`다.
- 하나의 packet 안에서도 준비·문서 작업과 heavy 실행을 분리해 heavy lease 점유 시간을 최소화한다.

| Packet | 수량 | 대상 ID | 핵심 작업 | 검증 |
|---|---:|---|---|---|
| `FINAL-INT-DATA-QUALITY-SPLIT` | 6 | `DLV-AIML-05/06/07/08/09/10` | immutable dataset·image-label hash, 수치 품질 판정, keep/fix/hold/drop 원장, 승인 예시, gold/double-label/adjudication, immutable split과 leakage 검사 | 같은 dataset generation에 원본·정제·gold·split·leakage 결과가 결속되고 raw evidence가 재현됨 |
| `FINAL-INT-DESIGN-SECURITY` | 5 | `DLV-DES-15/19/20`, `DLV-SEC-01/19` | admin/auth/recovery/session, FP035·gateway threat surface, 감사·monitoring 설계를 현재 코드와 맞추고 역할·교육·승인 evidence 작성 | app/module ID 양방향 trace, flow·trust boundary·threat model, event schema·retention·threshold·alert, 실제 acknowledgement·training receipt 검수 |
| `FINAL-INT-BUILD-SUPPLY` | 6 | `DLV-DEV-09/12/14/17/19/20` | source/toolchain/lock/config/model/output hash를 묶은 clean·repeat build, migration up/down·backup, fixture inventory, license/SBOM, provenance attestation | named source/build ID, 반복 build hash, migration 결과, fixture privacy, CycloneDX/SPDX, attestation을 한 release generation에 결속 |

Wave I1 종료 gate:

- 17개 ID 작업 계약의 required action 누락이 0이다.
- dataset generation과 release generation이 이름·hash로 동결된다.
- 다음 Wave가 참조할 source, toolchain, dependency, model 후보가 모호하지 않다.
- 독립 검수에서 허위 완료 또는 stale reference가 0이다.

#### Wave I2: 학습·정적분석·시험기준 11건

| Packet | 수량 | 대상 ID | 선행 | 핵심 작업과 검증 |
|---|---:|---|---|---|
| `FINAL-INT-TRAINING-MODEL` | 5 | `DLV-AIML-11/12/13/15/16` | 데이터 packet | dataset→split→config→environment→run→checkpoint provenance, append-only experiment, 같은 split 비교, runtime 3-model hash/source/conversion, 모델별 card와 승인 |
| `FINAL-INT-STATIC-SECRET` | 3 | `DLV-DEV-16`, `DLV-SEC-10/12` | build packet | 동결 generation을 대상으로 lint/SAST parse error 0, finding·triage·retest raw report, current tree와 full history secret scan 및 대응 기록 |
| `FINAL-INT-TEST-GOV-TRACE-A` | 3 | `DLV-TST-02/05/08` | design·build packet | 승인된 entry/exit, 직접 requirement→design→module→case/fixture trace, formal scope와 재시험 정책을 고정. `279x8` 비판별 투영은 직접 trace로 인정하지 않음 |

Wave I2 종료 gate:

- 학습·모델·정적분석·시험 기준이 같은 동결 source와 dataset을 참조한다.
- tool, ruleset, corpus, split, environment, acceptance threshold가 결과 열람 전에 고정된다.
- formal 실행을 시작할 수 있는 entry 조건이 권한 기록과 함께 충족된다.

#### Wave I3: 평가와 시험환경 8건

| Packet | 수량 | 대상 ID | 선행 | 핵심 작업과 검증 |
|---|---:|---|---|---|
| `FINAL-INT-EVALUATION-EQUIV` | 4 | `DLV-AIML-17/21/23`, `DLV-WS-10` | data·training | 결과 열람 전 model/split/corpus/grid/tolerance 동결, fixed-input PT↔TFLite raw output 비교, mismatch 판정, threshold sweep, 독립 재평가, config hash 결속 |
| `FINAL-INT-TEST-ENV-DEVICE` | 4 | `DLV-TST-03/07/09/14` | build·test-governance A | formal environment/support matrix, 격리 Android→Gateway→Backend→PostGIS 통합, same-run supported-device/live-service E2E, 두 앱 accessibility 자동검사와 실제 TalkBack 증거 |

실제 기기, live TMAP credential, 독립 field executor가 없어서 실행할 수 없는 행은 결과를 만들지 않는다. 내부 준비 완료 후 해당 실제 행동만 명시한 `EXTERNAL` 계약으로 전이해 단계 2에 넣는다.

Wave I3 종료 gate:

- frozen protocol과 실제 결과가 같은 model/build/config를 가리킨다.
- PT/TFLite 입력과 출력이 pair 단위로 재현된다.
- 환경·기기·OS·앱 build·gateway·backend·DB·model ID가 한 실행에 결속된다.
- 중단·실패도 원본 결과로 보존되며 PASS로 바뀌지 않는다.

#### Wave I4: 통합과 formal 실행 3건

| 순서 | 대상 ID | 작업 | 검증 |
|---:|---|---|---|
| 1 | `DLV-DEV-21` | 동일 승인 generation의 앱·gateway·backend·DB·model 통합 실행 | component별 version/hash, 실행 순서, defects, 최종 verdict가 한 receipt에 결속 |
| 2 | `DLV-TST-06/11` | formal unit·contract·regression 실행 | exact case set, raw stdout/stderr/result hash, pass/fail/skip 산술, 재시험 lineage가 보존됨 |

실패가 있으면 결함을 등록하고 관련 Wave로 되돌린다. 결함을 문서에 적었다는 이유만으로 formal 시험을 완료 처리하지 않는다.

#### Wave I5: 시험 종결과 릴리스 준비 9건

| Packet | 수량 | 대상 ID | 핵심 작업 | 검증 |
|---|---:|---|---|---|
| `FINAL-INT-TEST-GOV-TRACE-C` | 4 | `DLV-TST-18/19/20/21` | 결함·metric·STR·residual risk closure | append-only defect lineage, 같은 baseline coverage/metric/STR, 실제 risk acceptance·expiry·release impact |
| `FINAL-INT-INTEGRATION-RELEASE` 잔여 | 5 | `DLV-REL-15/16/18/19/22` | rollback, signed install/remove, current admin guide, controlled demo/training, known 346+unknown 185 license 전수 reconciliation | rollback receipt, signed artifact hash, install/remove 결과, guide와 현재 UI/API 일치, participant/session receipt, unknown license 0, final notice |

Wave I5 종료 gate:

- 단계 1의 최초 48개 ID가 모두 처리됐고 누락·중복이 0이다.
- `OK` 전이마다 실제 evidence와 독립 검수 결과가 있다.
- 외부 권한이 필요한 행은 구체적인 authority, event, receipt, completion predicate를 가진 단계 2 action으로 이관됐다.
- 적용성 판정이 필요한 행은 근거와 authority dependency가 있는 단계 3 action으로 이관됐다.
- 단순 상태명 변경이나 전량 재분류만으로 이 단계의 종료 gate를 통과할 수 없다.

## 5. 단계 2: `EXTERNAL` 49건 실제 증거 확보

### 4.1 목적과 역할 분리

- 목적: 현재 49건의 외부 권한·환경·사건을 실제로 수행하고 검증 가능한 receipt로 수집한다.
- Codex 역할: 요구사항 정리, 템플릿·runbook·검사기 준비, 증거 수집 안내, hash·schema·lineage 검증, 상태 delta와 독립 재감사 준비.
- 사용자·외부 주체 역할: 실제 법적 판단, 참여자 동의·관찰, 실기기·현장 실행, 운영 credential·change window 제공, 배포·서명·인계 승인과 실행.
- Codex는 외부 주체의 신원, 판단, 서명, 물리 사건을 대신 만들지 않는다.

### 4.2 외부 권한 트랙

| Track | 수량 | 대상 ID | 필수 authority와 실제 행동 |
|---|---:|---|---|
| `FINAL-EXT-DATA-LEGAL` | 8 | `DLV-AIML-01/02/03/22`, `DLV-SEC-04/05/06/17` | `DATA_PROTECTION_AND_LEGAL_AUTHORITY_ROLE`; 데이터 제공자 원본, 권리·동의·계약, 처리자·국외 이전, 보존·삭제·고지에 대한 실제 승인·거절·조건부 결정 |
| `FINAL-EXT-FIELD-DEVICE` | 10 | `DLV-WS-06/07/09/12/13/14/15/17/20/21` | `INDEPENDENT_FIELD_QA_AUTHORITY_ROLE`; 실제 기기·OS·build, 안전 장소, 참여자·동의, raw 결과, 결함, 중단 증거. feature author와 executor·QA 분리 |
| `FINAL-EXT-RELEASE-OPS` | 13 | `DLV-OPS-06/11/13/17/19/22/23`, `DLV-REL-13/20/21`, `DLV-CLS-14/15/16` | `RELEASE_AND_OPERATIONS_AUTHORITY_ROLE`; 실제 environment, release generation, deployment·restore·capacity·delete·incident·change·closure event와 rollback |
| `FINAL-EXT-APPROVAL-HANDOVER` | 16 | `DLV-CLS-02/04/08/10/11`, `DLV-DES-21`, `DLV-DSC-05/06/11`, `DLV-MGT-03/08`, `DLV-REQ-12/13/15`, `DLV-TST-22/23` | `PROJECT_SPONSOR_RECIPIENT_AND_ACCEPTANCE_AUTHORITY_ROLE`; scope별 실제 결정, signer·recipient, effective window, residual risk와 인계 의무 |
| `FINAL-EXT-RESEARCH-OBSERVATION` | 2 | `DLV-DSC-07/09` | `INDEPENDENT_RESEARCH_OBSERVER_ROLE`; 실제 participant/context/observer와 raw observation. 합성 participant·result 금지 |

외부 49건에 동일한 `real event exists` predicate를 적용하지 않는다.

- `CURRENT_STATE_NO_EVENT_ALLOWED`: `DLV-SEC-17`, `DLV-OPS-17`, `DLV-OPS-19`, `DLV-CLS-14/15/16`
- `CONDITIONAL_REAL_EVENT_ONLY`: `DLV-OPS-23`, `DLV-REL-13`, `DLV-CLS-11`
- current-state/no-event 완료에는 관찰기간, 검색범위, ledger head/hash, authority identity가 있는 실제 attestation이 필요하다.
- conditional event는 삭제·production 배포·종료 같은 정당한 사건이 발생할 때만 실행한다. 증거를 위해 사건을 만들지 않는다.
- `NOT_DUE` 또는 readiness receipt는 준비상태만 증명하며 실제 event 완료로 승격시키지 않는다.

단계 1과 단계 3의 재분류로 외부 항목이 추가되면 기존 artifact ledger 행의 상태를 전이하고 별도 action queue에 action을 추가한다. 따라서 `49`는 시작 수량이며, 종료 때는 재라우팅을 반영한 동적 모수를 사용한다.

### 4.3 실행 Gate

#### E0. 내부 readiness

- 각 행의 prerequisite, scope, acceptance, stop rule, rollback, evidence template를 완성한다.
- candidate build, model, dataset, device, environment manifest를 변경 불가능한 hash로 고정한다.
- 외부 주체에게는 49개 질문이 아니라 역할별 통합 action packet 5개를 제공한다.

검증: `current_external_total/current_external_total` 행에 owner role, authority role, input, action, output, completion predicate가 있고 빈 필드가 없다.

#### E1. authority binding

- 다섯 authority의 실제 신원, 권한 범위, 유효기간, 이해상충 분리를 확인한다.
- operator, approver, independent reviewer를 가능한 한 분리한다.

검증: authority receipt의 identity, role, scope, effective time, decision capability가 실제 근거와 결속된다.

#### E2. data/legal 결정

- 데이터 원본, 제공 조건, 동의, 개인정보 처리, 라이선스, 보존·삭제 의무를 검토한다.
- 승인, 거절, 조건부 승인 중 실제 결정을 기록한다.
- 거절 또는 미충족 조건은 해당 항목을 계속 `EXTERNAL`로 유지하고 downstream 모델·현장 실행을 중단한다.

검증: 8건 각각에 source decision ID와 receipt가 있고 legal/privacy 독립 재검토가 PASS다.

#### E3. research observation

- E2의 참여자·privacy 조건을 먼저 충족한다.
- 실제 참여자·맥락·관찰자·시간을 기록하고 raw observation과 분석 결과를 분리한다.

검증: 2건의 raw evidence, derived result, consent/scope, observer receipt가 서로 추적된다.

#### E4. field 준비

- 사용할 build/model/config/device/OS를 동결한다.
- 안전 장소, 참여자 기준, 중단 조건, 사고 대응, 개인정보 취급을 승인받는다.
- 각 field scenario에 executor와 independent QA를 배정한다.

검증: 실행 전 manifest와 승인 receipt가 모두 있으며 실행 뒤 바뀌지 않는다.

#### E5. field/device 실행

- 실제 기기와 지원 OS에서 배정된 시나리오를 실행한다.
- PASS뿐 아니라 defect, abort, safety stop을 원본 상태로 기록한다.

검증: 10건 각각에 device/build/model/config ID, raw result, defect·stop 판단, executor·QA receipt가 있다.

#### E6. release/operations 실행

- 동결 release generation으로 배포, restore, rollback, capacity, incident/change, data disposition, closure 행동을 수행한다.
- operator와 승인자가 같은 사람이어야 하는 불가피한 경우 이유와 보상 통제를 기록한다.

검증: 13건 각각에 실제 event ID, 환경, 실행 시각, 결과, rollback 또는 복구 결과, operator·authority receipt가 있다.

#### E7. approval/handover

- 16건을 마지막에 한 번에 서명하지 않는다.
- 선행 scope 승인, 시험 결과 승인, residual risk 수용, 최종 인계로 나눠 필요한 dependency point에서 받는다.
- 수령자는 전달받은 자산, 미해결 의무, 철회·재개 조건을 확인한다.

검증: 16건 각각의 결정 scope와 대상 evidence hash가 정확하며 signer·recipient·effective window가 있다.

#### E8. 증거 수용과 독립 재감사

모든 외부 receipt는 다음 최소 필드를 갖는다.

- source event 또는 decision ID
- occurred/effective time
- scope와 대상 artifact ID
- operator identity와 authority identity
- decision, result, defect 또는 condition
- repo-relative evidence path
- SHA-256와 byte length
- 독립 reviewer와 재감사 결과

민감 원본은 저장소에 직접 넣지 않는다. 제한 보관소의 locator, 원본 SHA-256, 접근 권한, 검증 시각과 reviewer verification receipt를 ledger에 기록하고, 저장소에는 redacted submission copy와 binding receipt만 둔다.

검증: 실제 event·receipt가 0이 아니고, 대상 artifact의 completion predicate를 충족하며, synthetic evidence와 저장소 내 raw secret/불필요한 PII가 0이다.

### 4.4 단계 2 종료 기준

- 시작 49건과 이후 추가된 외부 행 전부가 `OK` 또는 권한 있는 `N_A_APPROVED`로 전이한다.
- `EXTERNAL=0`이다.
- 실제 외부 증거가 없는 행은 완료 처리하지 않는다.
- 외부 입력 대기 중에는 단계 1, 단계 3, 단계 4 준비 작업을 계속하되, 외부 행동 자체를 대신하지 않는다.
- 다른 수행 가능 작업이 모두 끝나면 역할별 통합 요청서와 재개 조건을 남기고 `AUTONOMOUS_INTERNAL_COMPLETE`, `WAITING_EXTERNAL`로 안전하게 정지한다.

## 6. 단계 3: `N_A_CANDIDATE` 36건 적용성 확정

### 5.1 대상

| Packet | 수량 | 대상 ID |
|---|---:|---|
| `NA-SEC-AIML` | 8 | `DLV-AIML-18/19/20/25/26`, `DLV-SEC-13/14/18` |
| `NA-RELEASE-OPS` | 10 | `DLV-OPS-07/18`, `DLV-REL-03/04/05/06/07/08/09/14` |
| `NA-CLOSURE` | 6 | `DLV-CLS-01/03/05/06/12/13` |
| `NA-MANAGEMENT-TECHNICAL` | 12 | `DLV-DEV-10/11/13/15`, `DLV-DSC-04`, `DLV-TST-10/12/13/15/16/17`, `DLV-WS-16` |

### 5.2 판정 절차

#### N0. scope freeze

- 원 요구사항, 제품 구성, 지원 플랫폼, 배포·운영 범위, 지역·법규, 계약 범위를 행별로 고정한다.
- 기존 baseline과 final-257 seal은 수정하지 않는다.

#### N1. 적용 조건 검사

- 적용 조건이 거짓이라는 긍정적 증거를 수집한다.
- “구현하지 않음”, “시험하지 않음”, “현재 미사용”, “승인이 없음”은 N/A 근거로 인정하지 않는다.
- 지원 구성 하나에서라도 요구사항이 적용되는 반례가 있으면 N/A를 거부한다.

#### N2. domain owner 제안

- 해당 artifact/domain owner가 판정안과 근거를 작성한다.
- 제안에는 scope, 근거 조항, evidence hash, 가정, 유효기간, 재개 조건을 포함한다.

#### N3. authority 결정

- 요구사항 또는 계약 scope owner가 적용성을 결정한다.
- 규제 항목은 legal/compliance authority, 보안·모델·장치·시험·출시 항목은 해당 전문 authority가 추가 승인한다.
- 36건을 prefix나 priority 기준으로 일괄 승인하지 않는다.
- 외부 authority가 필요한 동안 artifact 상태는 `N_A_CANDIDATE`로 유지하고 action queue에 `external_decision_dependency`를 만든다. 빈 결정을 `EXTERNAL` 완료처럼 취급하지 않는다.

#### N4. 독립 검토와 상태 전이

작성·구현·승인에 참여하지 않은 검수자가 반례와 evidence를 확인한 뒤 다음 중 하나로 확정한다.

| 판정 | 다음 상태 | 후속 |
|---|---|---|
| 비적용 입증·승인 | `N_A_APPROVED` | 최종 상태. 유효기간·재개 조건 감시 |
| 적용, 내부 작업 | `INTERNAL_GAP` | 단계 1의 해당 packet에 추가 |
| 적용, 외부 행동 | `EXTERNAL` | 단계 2의 해당 track에 추가 |
| 이미 충족 | `OK` | 기존 표준과 같은 실제 evidence·독립 검수 필요 |
| 정보 부족 | `N_A_CANDIDATE` | final gate 차단, 필요한 정보 명시 |

### 5.3 단계 3 종료 기준

- 36건 모두 개별 decision receipt가 있다.
- `N_A_CANDIDATE=0`이다.
- `N_A_APPROVED`마다 authority, 근거, evidence hash, expiry, reopen condition, independent reviewer가 있고 예상 제출일과 seal 시점까지 유효하다.
- 적용 판정된 항목은 단계 1 또는 단계 2에 누락 없이 추가되고 최종적으로 닫힌다.

## 7. 단계 4: 전 257건 통합 재검증

### 6.1 시작 Gate

- 단계 1-3의 동적 backlog가 모두 처리됐다.
- 목표 집계는 `OK + N_A_APPROVED = 257`이다.
- `INTERNAL_GAP + EXTERNAL + N_A_CANDIDATE = 0`이다.
- 모든 상태 delta와 authority receipt가 successor chain에 포함됐다.

### 6.2 검증 순서

#### V0. successor schema와 입력 동결

- 기존 final-257 builder와 패키지를 복사·덮어쓰지 않는다.
- `N_A_APPROVED` 상태어휘를 scope owner가 승인하고, 새 successor builder와 모든 consumer에 필요한 최소 변경만 적용해 새 delta chain을 지원한다.
- goal engine 버전 추가나 FP별 builder 재작성은 하지 않는다.
- source, dataset, model, build, environment, external receipt set을 hash로 동결한다.
- 기준 snapshot raw SHA와 새 exact ID-set fingerprint를 결속한다.

검증: 입력 목록의 missing, duplicate, stale, hash mismatch가 0이고 다음 set assertion이 PASS다.

```text
OK ∪ INTERNAL_GAP ∪ EXTERNAL ∪ N_A_CANDIDATE ∪ N_A_APPROVED = exact 257
all pairwise intersections = 0
baseline 대비 unknown, missing, added artifact ID = 0
```

#### V1. 결정론적 정본 재생성

- 공통 builder로 문서·JSON projection·trace·manifest를 생성한다.
- 생성 후 같은 builder의 check mode로 exact bytes를 확인한다.

검증: write 후 check PASS, 비결정적 timestamp·ordering 차이가 0이다.

#### V2. 코드·문서·시험 일치 검증

- 요구사항→설계→코드/module→시험 case/fixture→결과→산출물의 직접 trace를 확인한다.
- 현재 UI/API/config/model/license와 설명서·운영 문서의 값을 비교한다.
- scope freeze에서 formal 279 각 case를 `SUBMISSION_REQUIRED` 또는 `RELEASE_ONLY`로 분류하고, 실행 방식은 `RUN_REQUIRED`, `VALID_RECEIPT_ALLOWED`, `APPROVED_N_A_ALLOWED` 중 하나로 승인한다.
- 승인되지 않은 skip은 PASS가 아니며 열린 상태로 처리한다.
- formal 279, unit, contract, integration, regression, security, model equivalence, accessibility, actual-device 결과를 승인된 범위대로 재생 또는 receipt 검증한다.
- input hash, tool/environment version, acceptance가 동일한 heavy 결과는 immutable receipt를 재검증하고, dependency가 바뀌거나 stale하거나 protocol이 재실행을 요구하는 항목만 다시 실행한다.

검증: trace orphan 0, stale reference 0, submission 필수 시험의 승인되지 않은 NOT_RUN·skip 0, pass/fail/skip 산술 불일치 0이다.

#### V3. exact-257 ledger 검증

- 257개 ID의 고유성, 상태, priority, group, required action, evidence, authority를 재계산한다.
- JSON, Markdown, manifest, action/decision packet 사이 tuple parity를 확인한다.

검증: exact 257, unknown 0, overlap 0, tuple mismatch 0, 열린 상태 0이다.

#### V4. 무결성 검증

- source-set ledger, semantic projection, content fingerprint, raw binding, byte length, canonical ordering을 재계산한다.
- manifest가 자기 output hash를 입력으로 먹지 않게 하고 DAG 무순환을 검증한다.

검증: stale/missing/hash mismatch 0, DAG unknown endpoint 0, self-cycle 0, topological count가 node count와 같다.

#### V5. 독립 통합 검수

- 검수 전에 후보 입력 hash를 동결한다.
- 작성·구현·승인에 참여하지 않고 수정 권한이 없는 reviewer가 전 257건, authority subject, 실제 evidence를 읽기 전용으로 검토하고 별도 findings 파일을 만든다.
- 작성자는 findings 확정 뒤에만 수정하며, 변경된 subject는 독립 reviewer가 다시 판정한다.
- 결과는 finding ID, severity, artifact ID, evidence, required fix를 갖는다.
- `BLOCKING`, `MAJOR`, `MINOR`가 하나라도 있으면 해당 packet으로 되돌린다.

severity는 다음처럼 적용한다.

- `BLOCKING`: 정확성·안전·권한·재현성이 깨져 완료 판정 자체가 불가능함
- `MAJOR`: 핵심 증거나 추적이 불완전해 완료 신뢰성이 부족함
- `MINOR`: 제출 요구사항 또는 일관성에 실제 영향을 주는 경미한 결함
- 문구 선호처럼 완료에 영향이 없는 의견은 `INFORMATIONAL`이며 seal을 차단하지 않음

검증: `BLOCKING 0 / MAJOR 0 / MINOR 0`과 명시적 submission seal 허용 판정.

### 6.3 실패 처리

- 한 행이 실패해도 기존 seal을 수정하지 않는다.
- 실패 행과 공통 원인 packet을 재개하고 새 successor를 생성한다.
- 한 행의 상태나 evidence가 바뀌면 257 집계, semantic fingerprint, manifest, DAG, 독립 검수를 다시 수행한다.
- superseded seal은 이력으로 보존하고 새 authority로 사용하지 않는다.

### 6.4 단계 4 종료 기준

- 전 257건이 `OK` 또는 `N_A_APPROVED`다.
- 모든 자동·수동 검증이 PASS다.
- 독립 검수 finding이 0이다.
- 제출 후보와 evidence set이 immutable hash로 동결됐다.

## 8. 단계 5: 최종 제출 패키지 생성과 봉인

### 7.1 제출 패키지 구성

- 257건 최종 snapshot JSON과 사람이 읽는 Markdown projection
- 최종 상태·evidence·authority manifest
- 내부 실행 evidence index
- 외부 event·decision·receipt index
- N/A decision receipt index
- 요구사항·설계·코드·시험·산출물 trace report
- 독립 통합 검수 결과
- 제출 파일 목록, byte length, SHA-256
- 최종 seal과 seal 독립 검증
- 사용자용 제출 순서, 제출 전 확인표, 알려진 운영 경계

raw secret, 불필요한 PII, credential, 개인 서명 원본이 제출 범위에 포함되지 않도록 restricted source evidence와 redacted submission copy를 분리한다. 제출 manifest에는 제한 원본의 locator와 hash가 아니라 접근이 통제된 binding receipt만 포함한다.

### 7.2 제출 후보 검수

- 단계 4 검수는 산출물 내용·증거·상태의 정확성을 책임지고, 단계 5 검수는 포장·누락·무결성·민감정보 경계만 책임진다.
- 패키지를 빈 임시 위치에서 풀어 index의 모든 파일을 찾을 수 있는지 확인한다.
- Markdown 링크, JSON schema, 문자 인코딩, 파일명, 중복, 누락을 확인한다.
- 제출본 hash와 검수 대상 hash가 같은지 확인한다.
- 제출용 설명의 수량과 실제 ledger 수량이 같은지 확인한다.

검증: 누락·중복·깨진 참조·hash mismatch·민감정보 노출이 0이다.

### 7.3 최종 봉인

- 전 257건, 제출 파일, 독립 검수, authority set을 canonical subject로 묶는다.
- 자기 참조가 없는 content fingerprint와 final seal을 생성한다.
- 별도 reviewer가 raw binding, canonical subject, DAG, 상태 수량, 완료 경계를 재현한다.

검증: seal independent review가 `BLOCKING 0 / MAJOR 0 / MINOR 0`이고 `SUBMISSION_PACKAGE_READY=true`를 허용한다. 실제 upload receipt 전에는 `SUBMISSION_TRANSMITTED=false`, 제출처 receipt 전에는 `SUBMISSION_ACCEPTED=false`다.

### 7.4 제품 출시 분기

- 한이음 제출만 완료하는 경우 제품 출시 상태를 별도 필드로 명시하고 제출 봉인과 섞지 않는다.
- 실제 제품 출시까지 요구되면 단계 4 scope matrix의 `RELEASE_ONLY` case를 포함한 formal 279 요구 범위, 실제 장치, 5개 release gate, 법무·모델·서명·배포·closure 승인·실행, 배포물 hash 일치, 최종 release authority 서명을 추가로 완료한다.
- 이 조건이 하나라도 없으면 `PRODUCT_RELEASE_APPROVED=false`를 유지한다.

### 7.5 단계 5 종료 기준

- 257종 산출물과 모든 증거가 제출 index에서 추적된다.
- 최종 제출 패키지가 재현 가능하고 독립 봉인됐다.
- `ARTIFACT_CLOSURE_COMPLETE=true`, `SUBMISSION_PACKAGE_READY=true`다.
- 실제 포털 전송·접수 전에는 `SUBMISSION_TRANSMITTED=false`, `SUBMISSION_ACCEPTED=false`다.
- 제품 출시 상태는 실제 gate 결과와 정확히 일치하며 과장되지 않는다.

## 9. 전체 실행 순서와 병렬화

1. R003 계획과 기준 snapshot을 동결한다.
2. Phase 0에서 257 완료 의미, 133 계약, 상태어휘, authority, 공수와 critical path를 확정한다.
3. N/A 36과 data/legal 판정을 영향받는 `HEAVY` 작업보다 먼저 진행한다.
4. 영향을 받지 않는 design·trace·work-contract 준비는 병렬 실행한다.
5. 승인된 데이터·source·build 기준으로 내부 foundation을 시작한다.
6. model/build/test governance, evaluation/device, integration/formal을 dependency 순서로 실행한다.
7. 외부 actual decision, observation, device, operation, acceptance를 정당한 prerequisite 뒤에 실행한다.
8. I/E/N queue를 한 차례씩 drain하고 열린 상태가 0인지 확인한다. 새 action이 생기면 다시 drain한다.
9. 동일 상태쌍 재분류가 두 번 발생하거나 한 번의 전체 drain 동안 상태·증거 진전이 0이면 자동 반복을 멈추고 `BLOCKED_REVIEW`로 독립 판정을 받는다.
10. 외부 입력만 남으면 통합 요청서를 봉인하고 `WAITING_EXTERNAL`로 대기한다. 입력 검증 후 `RESUMABLE`에서 고정점 loop를 계속한다.
11. 열린 상태가 모두 0이 되면 단계 4 전체 재검증을 수행한다.
12. 단계 4 독립 검수가 PASS하면 단계 5 제출 패키지 생성·검수·봉인을 수행한다.

최종 의존 경로:

```text
baseline freeze
  -> Phase 0 scope/completion/routing freeze
  -> N/A and legal decisions
  -> non-blocked internal foundation
  -> model/build/test integration
  -> external actual events and applicability decisions
  -> exact-257 full replay
  -> independent review
  -> submission seal
  -> optional product release seal
```

## 10. 진행률, 공수, 중단 복구 보고

공식 완료율은 최종 상태만 센다.

```text
Closure% = 100 * (OK + N_A_APPROVED) / 257
```

실행 진척률은 별도로 계산한다.

| 실행 상태 | Credit |
|---|---:|
| `UNASSESSED` | 0.00 |
| `CONTRACTED` | 0.10 |
| `READY` | 0.20 |
| `EVIDENCE_READY` | 0.60 |
| `INDEPENDENTLY_REVIEWED` | 0.80 |
| `APPLIED_FINAL` | 1.00 |

```text
Execution% = 100 * sum(stage_credit) / 257
```

- 재분류만으로 credit을 높이지 않는다.
- 새 evidence나 review가 없으면 이전 credit 이하를 유지한다.
- 기존 `OK`가 다시 열리면 실제 실행 단계까지 credit을 낮춘다.
- `WaitingExternalCount`와 `RemainingEffortPoints`를 완료율과 분리해 보고한다.
- 일정은 S/M/L/XL 공수와 외부 lead time을 분리해 critical path 기준으로 갱신한다.

매 checkpoint는 다음 값만 갱신한다.

- 자율 실행 상태와 run ID
- 완료 packet과 다음 packet
- `OK / INTERNAL_GAP / EXTERNAL / N_A_CANDIDATE / N_A_APPROVED / TOTAL`
- 새로 생긴 blocker와 실제 필요한 외부 입력
- 실행한 검증과 PASS/FAIL
- 변경 파일과 evidence path
- 자원 상태와 중단 위치

컴퓨터 종료 또는 사용자 로그아웃 뒤에는 마지막 `COMPLETE` checkpoint와 predecessor hash를 확인한다. 불완전한 `RUNNING` attempt는 `INTERRUPTED`로 격리하고, applied receipt가 없는 다음 packet부터 재개한다. 완료된 Goal이나 Wave를 처음부터 재검사하지 않는다.

재개 기능의 정확한 경계:

```text
RESUME_SAFE=true
AUTO_RELAUNCH_AFTER_LOGOUT=false
AUTO_RELAUNCH_AFTER_REBOOT=false
SERVICE_INSTALLED=false
```

현재 계획은 호출된 세션 안의 연속 실행과 다음 호출에서의 안전 재개를 지원한다. 로그아웃·재부팅 뒤 자동 재실행은 보장하지 않는다. service, timer, process manager 설치·enable은 별도 사용자 승인 없이는 하지 않는다.

## 11. 계획 자체의 검수 기준

이 계획은 R002 독립 검수와 R003 비판·자원 설계 검수를 받았다. R003 findings와 반영 내용은 `remaining-work-execution-plan-r003-improvement-review.md`에 기록했다.

- 48/49/36 대상 집합과 각 group 수량이 정확한가
- 최종 상태와 완료 정의가 모호하지 않은가
- 내부 작업과 외부 권한·사건의 경계를 침범하지 않는가
- N/A를 미구현 또는 미시험의 은폐 수단으로 쓰지 않는가
- dependency와 병렬화가 실행 가능하고 순환하지 않는가
- 재시작 시 중복 실행과 상태 유실을 막는가
- 자원 사용이 과도하게 보수적이지 않으면서 강제 로그아웃 위험을 줄이는가
- 전체 검증과 제출 봉인이 제품 출시 승인으로 과장되지 않는가
- 불필요한 goal engine 재설계, FP별 builder, 반복 checkpoint 생성을 피하는가
