# WalkSafe 모델 배포·운영 통제

상태: Draft
버전: 0.1.0
문서 묶음: BND-AIML-OPS
Draft 산출물 유형: AIML-24, AIML-25, AIML-26
승인 상태: NOT_APPROVED
정책 기준선: PB-WALKSAFE-FEATURE-POLICY-1.0.1
정책 내용 지문: 8fdeab58e8c901a29f4380de373cdfd2b67350cd43dafa765246b4f6c9f77244
결정 지문: 16064cabf95dc16109bfe315032905a12881cab40cf7730e97d8171d15476538
실행 검증: NOT_RUN
출시 상태: NOT_ELIGIBLE
현행 제품: Android 사용자 앱과 별도 Android 관리자 앱
Web/PWA: LEGACY_REFERENCE_ONLY
민감 원본: Git 저장 금지

## 이 문서를 읽는 방법

Draft는 작성 가능한 계획·규칙·등록부 구조를 만들었다는 뜻입니다. 실제 학습이나 시험이 끝났다는 뜻이 아닙니다. Planned/NOT_RUN 항목은 실행 절차만 준비됐고 결과·합격·증거가 아직 없다는 뜻입니다.

## 현재 묶음 상태

- Draft로 작성된 유형: AIML-24, AIML-25, AIML-26
- 실행 전 Planned/NOT_RUN 유형: 없음
- 실제 학습·평가·운영 실행 수: 0
- 실제 PASS 수: 0
- 승인된 출시 모델 수: 0
- 남은 정책 gate: 5개, 모두 NOT_RUN·미면제
- FP-035 이동통신망 조건: `PB-WALKSAFE-FEATURE-POLICY-1.0.1`의 exact `FP-035` overlay는 `APPROVED / EFFECTIVE / COMMITTED`이다. 보행 중에는 전송하지 않고, 정지 뒤 명시 선택 시 이동통신망을 허용하며, 미선택 시 Wi-Fi만 허용한다. 구현 적합성은 `NOT_ASSESSED`, 관련 시험은 `NOT_RUN`, gate는 `OPEN`이다.

## 쉬운 용어

- dataset: 모델이 학습하거나 평가할 자료 묶음
- label: 사진 속 물체의 종류와 위치를 표시한 정답
- split: 학습용·조정용·독립 시험용 자료를 서로 섞이지 않게 나눈 것
- hash 또는 파일 지문: 파일이 같은지 확인하는 긴 값
- threshold: 모델 점수가 어느 정도일 때 후보로 인정할지 정한 기준
- drift: 운영 중 입력이나 결과의 분포가 기준 시점과 달라지는 현상
- candidate: 조사·재검증 대상이며 승인·배포가 확정되지 않은 후보

<!-- w-ai-2-promotion-rollback:start -->
## W-AI-2 promotion·rollback hash contract

- Current runtime generation set SHA-256: `d8bdfe41cbf76b8f0ef62597920adf35fd80e0acd1acc8cb5f0affad2f99aa07`.
- Promotion/rollback contract SHA-256: `cbb519ec41a1d5f2f76cd1d4a6d332b23a1b3f8f38ab774c6df0d7ef083eee15`.
- 현재 local deployment의 runtime state는 개발 후보 연결이며 세 모델 모두 `deployment_eligible=false`다.
- promotion은 PT·TFLite·runtime config·TwoModelClassMap·dataset·formal evaluation·APK를 하나의 불변 generation으로 묶고 각 hash가 위 계약과 일치할 때만 심사할 수 있다.
- 현재 formal evaluation, PT↔TFLite equivalence, actual-device validation은 `NOT_RUN`, promotion은 `NOT_APPROVED`다.
- `previous_model_id`가 없고 승인된 이전 generation도 없으므로 rollback anchor는 `NO_PREVIOUS_APPROVED_GENERATION`이다.
- rollback은 이전 승인 generation의 model/config/class-map/app hash가 모두 일치하고 데이터 무손실·호환성 시험이 통과한 경우에만 허용한다. 현재 rollback 실행은 `NOT_RUN`이다.
- fallback source provenance가 `UNKNOWN_PROVENANCE`인 동안 custom/COCO component를 승인 release anchor로 승격하지 않는다.
- Trace: `GAP-028`, `GAP-029`, `GAP-040`, `GAP-048` ↔ `AIML-04`, `AIML-14`, `AIML-15`, `AIML-16`, `AIML-17`, `AIML-24`.

<!-- w-ai-2-promotion-rollback:end -->

<a id="aiml-24"></a>
## AIML-24 모델 배포·롤백 계획

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 서비스가 사용하는 모델 artifact를 교체할 때 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자, 릴리스책임자, 운영책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-14, AIML-16, AIML-20, AIML-21, AIML-23 |
| 후행 | AIML-26 |

목적: 승인 모델을 서비스에 안전하게 교체하고 이상 시 이전 모델로 신속히 복귀한다.

정책 추적: FP-019, FP-039, FP-051, FP-054

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-124, DEC-IBQ-125

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SINGLE-ADMIN-RECOVERY-DRILL

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- 승인·promotion gate
- artifact·config 배포
- compatibility·smoke
- canary·관찰 지표
- rollback trigger·절차
- registry·release 갱신

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 배포·롤백 절차

승격 전 모델·config·class 순서·앱 build를 하나의 release generation으로 묶고, registry evaluated/approved 상태, AIML-18·20·21·22·23 결과, WS-08~10, 보안·개인정보 gate를 확인한다. 제한된 canary에서 오류·지연·발열·신고 이상을 관찰하고 trigger 충족 시 신규 세션을 막는다. 현재 DB와 호환되고 새 자료가 손실되지 않음을 시험으로 입증한 구성요소만 이전의 승인된 모델·config·앱 묶음으로 되돌리며, 그렇지 않은 구성요소는 안전정지한 채 수정판을 준비한다.

현재 승인된 이전 모델과 정식 release generation이 없으므로 실제 rollback 실행은 NOT_RUN이다.



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 승인·promotion gate, artifact·config 배포.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-25"></a>
## AIML-25 운영 성능·드리프트 모니터링

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 모델이 공유 시험·운영 트래픽에 사용되는 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자, 운영책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-15, AIML-23, DES-23 |
| 후행 | AIML-26 |

목적: 개인정보를 최소화하며 입력·예측·피드백 변화와 품질 저하 신호를 감시한다.

정책 추적: FP-038, FP-039

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-123

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- 관측 지표·proxy
- baseline·임계값
- 표본·privacy 제한
- alert·triage
- 라벨 feedback·재평가
- 모델 중지·rollback 연결

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### Phase 1 IN_SCOPE 모니터링 계약

운영 계획 artifact는 `IN_SCOPE`, 공유시험·운영 측정은 `NOT_RUN`입니다. 모델 입력은 `data-model-source-current-state-r002.json`: dataset manifest `5ea9796589e02fcb64b82af564400df081731d7b2136e9a654422df56fdb94f6`, training result `1dc4b32335db04910ed51746f4bb3e7955d3f545c25dbd5dd4c44253b9d62dcc`, candidate PT `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669`, Android TFLite `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19`에 결속하지만 R002는 bytes/hash 동일성만 증명하고 source 완전성·권리·privacy·split/leakage·재현·승인·출시를 증명하지 않습니다.

| proxy | 산식·보존 필드 | 현재 threshold |
|---|---|---|
| 입력 품질 실패율 | `invalid_or_dropped_frames / eligible_frames` | `BLOCKED_PENDING_BASELINE` |
| class별 탐지·confidence | window별 count, confidence p50/p95; raw frame 금지 | `BLOCKED_PENDING_BASELINE` |
| 지연 | on-device inference latency p50/p95와 표본 수 | `BLOCKED_PENDING_DEVICE_RUN` |
| 안전정지·발열 | stop/thermal event count와 eligible session count | `BLOCKED_PENDING_DEVICE_RUN` |
| 검수 불일치 | consented review sample의 disagreement count/rate | `BLOCKED_PENDING_REVIEW_PROTOCOL` |

모든 row는 `window_start/end`, model·app·runtime hash, device family, 집계 count, 누락률과 산식 version을 포함합니다. 영상·음성·정확 위치·자유서술은 기본 telemetry에서 금지하고, 승인 최소 집계수 `N_privacy` 미만 cell은 억제합니다. `N_privacy`, window, 최소 표본 `N_min`, warning/stop threshold는 AIML-18과 통제 현장시험 뒤 동결합니다. `delta = current_metric - approved_baseline`; 방향별 승인 threshold 초과 또는 critical 안전 event 발생 시 자동 재학습·자동 배포 대신 triage와 모델 안전정지를 검토합니다.

| 상태 | blocker | 책임 | 기한 조건 |
|---|---|---|---|
| `BLOCKED` | baseline, window, `N_min`, `N_privacy`, alert/stop threshold | ML책임자·데이터책임자·운영책임자 | 공유시험 시작 전 |
| `BLOCKED` | alert severity, on-call owner, 확인·triage SLA | 운영책임자 | 모니터링 활성 전 |
| `BLOCKED` | 실제 drift·alert·triage 결과 | ML책임자·QA책임자 | 운영 측정 뒤 |
| `BLOCKED` | 원본 수집 필요성·동의·권리행사 | 보안·개인정보책임자 | 원본 수집 전 |

범위 owner 김민호와 제품책임자 귀속은 `USER_SELF_ASSERTED`이고 실제 승인은 `NOT_PERFORMED`입니다. ML·데이터·운영·안전 책임자의 개인 배정은 별도 확인이 필요하며 QA 검토자는 `UNASSIGNED`입니다.

정책 gate `GATE-PHONE-QUEUE-BYTE-LIMIT`, `GATE-SERVER-CAPACITY-STATE-CONTRACT`, `GATE-RAW-COLLECTION-RELEASE-REVIEW`, `GATE-CLOUD-COST-MEASUREMENT`, `GATE-SINGLE-ADMIN-RECOVERY-DRILL`는 모두 `NOT_RUN`·미면제입니다. 특히 원본 관측을 도입하면 `GATE-RAW-COLLECTION-RELEASE-REVIEW` 완료 전 공유시험·출시 적합성을 주장하지 않습니다.

### 개인정보 최소 운영 관측

모델 version별 탐지량, class별 confidence 분포, 입력 품질 실패율, 지연·중지·발열, 관리자 검수 결과처럼 필요한 최소 proxy만 집계한다. 원본 영상을 기본 metric payload에 넣지 않는다. baseline과 alert 숫자는 정식 평가·현장시험 뒤 정하고, drift 경고만으로 자동 재학습·자동 배포하지 않는다.

현재 운영 baseline, drift 실행, alert, triage 결과는 없다.



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 관측 지표·proxy, baseline·임계값.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-26"></a>
## AIML-26 재학습 기준·승인 절차

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 재학습 trigger가 충족되거나 정기 재검토 시점이 도래한 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-01, AIML-24, AIML-25 |
| 후행 | 없음 |

목적: 데이터·성능·환경 변화가 언제 재학습을 요구하고 어떤 독립 gate를 통과해야 하는지 정한다.

정책 추적: FP-038, FP-039

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-123

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- 재학습 trigger
- 데이터 승인·split freeze
- 실험·비교·안전 평가
- 모델·제품 책임 승인
- 배포·rollback 준비
- 주기·긴급 예외

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### Phase 1 IN_SCOPE 재학습 통제

재학습 계획 artifact는 `IN_SCOPE_DRAFT`이고 현재 재학습 event는 `NOT_TRIGGERED_OR_NOT_RUN`입니다. 과거 `PROVISIONAL_N_A` 후보 해석은 scope45 결정으로 사용하지 않으며, trigger 충족이나 실제 재학습을 소급 주장하지 않습니다. 입력 정본은 `data-model-source-current-state-r002.json`: dataset manifest `5ea9796589e02fcb64b82af564400df081731d7b2136e9a654422df56fdb94f6`, training result `1dc4b32335db04910ed51746f4bb3e7955d3f545c25dbd5dd4c44253b9d62dcc`, candidate PT `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669`, Android TFLite `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19`이고 R002의 미확정 권리·privacy·source 완전성·split/leakage·정식 재현 상태를 그대로 보존합니다.

| trigger ID | 조건 | 승인 전 필요한 값 |
|---|---|---|
| `RTR-01` | 승인 baseline 대비 metric 저하가 승인 window 수만큼 지속 | metric, threshold, window 수 |
| `RTR-02` | AIML-19에서 `CRITICAL`로 확인된 안전 오탐·미탐 | severity 판정·사례 evidence |
| `RTR-03` | 새 class·기기·환경·사용자 범위 승인 | 변경요청·위험분석 |
| `RTR-04` | data 분포, model runtime, 보안 dependency의 중대한 변경 | version/hash·영향분석 |
| `RTR-05` | 정기 재검토 기한 도래 | 승인 cadence·기준일 |

trigger register는 `trigger_id`, detected_at, detector, metric/evidence ref, old/new artifact hash, severity, decision, decision_at, approver, linked change request를 append-only로 기록합니다. 현재 threshold·window·cadence는 `BLOCKED_PENDING_AIML18_AND_MONITORING_BASELINE`이며 실제 trigger row는 없습니다.

재학습 착수 뒤에는 data source·rights·privacy 승인, dataset manifest와 sequence-safe split hash freeze, code/config/environment lock, 기존 승인본과 후보의 overall·class·condition별 비교, AIML-19/20 안전평가, Android device 성능, 배포 generation과 rollback compatibility를 순서대로 다시 검토합니다. 어느 단계도 다음 단계의 PASS를 자동 생성하지 않습니다.

| 단계 | 책임·검토 | 현재 상태 |
|---|---|---|
| trigger 확인·착수 | ML책임자 제안, 제품책임자 승인 | `NOT_PERFORMED` |
| data·split freeze | 데이터책임자, ML책임자, QA 검토 | `BLOCKED` |
| 비교·안전 평가 | ML책임자, QA·접근성·안전 검토 | `NOT_RUN` |
| 배포·rollback 재승인 | 기술·운영 검토, 제품책임자 승인 | `NOT_PERFORMED` |

범위 owner 김민호와 제품책임자 귀속은 `USER_SELF_ASSERTED`이고 실제 착수·배포 승인은 `NOT_PERFORMED`입니다. 다른 역할의 개인 배정은 확인되지 않았으며 QA 검토자는 `UNASSIGNED`입니다.

정책 gate `GATE-PHONE-QUEUE-BYTE-LIMIT`, `GATE-SERVER-CAPACITY-STATE-CONTRACT`, `GATE-RAW-COLLECTION-RELEASE-REVIEW`, `GATE-CLOUD-COST-MEASUREMENT`, `GATE-SINGLE-ADMIN-RECOVERY-DRILL`는 모두 `NOT_RUN`·미면제입니다. 재학습 절차 작성이나 모델 hash 발견은 training reproduction, gate closure, 배포 또는 release approval이 아닙니다.

### 재학습 승인 흐름

재현 가능한 성능 저하, 새 환경·기기·class, 중대한 오탐·미탐, 데이터 분포 변화, 보안·런타임 변경이 trigger 후보이다. 제품책임자가 재학습 착수를 승인한 뒤 새 data version과 sequence-safe split을 고정하고, 비교·안전·동등성·기기 평가와 배포·rollback 준비를 모두 다시 통과시킨다.

긴급 상황에서도 미검증 모델을 자동 배포하지 않는다. 이전 승인본 복구는 현재 DB 호환과 새 자료 무손실을 시험으로 입증한 구성요소에만 허용한다. 나머지는 모델 기능을 안전정지하고 수정판을 준비한 뒤 정식 절차를 따른다.



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 재학습 trigger, 데이터 승인·split freeze.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

## 공통 승인 경계

이 문서의 구조검사 통과는 데이터 품질, 학습 재현, 모델 정확도, Android 성능, 개인정보 적합성 또는 출시 가능을 뜻하지 않는다. 승인된 데이터셋·모델·평가 증거가 생기면 같은 ID와 hash로 연결해 새 revision을 만들고 사람 검토와 제품책임자 승인을 받는다.
