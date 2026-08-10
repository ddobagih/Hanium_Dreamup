# WalkSafe 모델 평가 통제

상태: Draft
버전: 0.1.0
문서 묶음: BND-AIML-EVALUATION
Draft 산출물 유형: AIML-17
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

- Draft로 작성된 유형: AIML-17
- 실행 전 Planned/NOT_RUN 유형: AIML-18, AIML-19, AIML-20, AIML-21, AIML-22, AIML-23
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

<!-- w-ai-2-evaluation-boundary:start -->
## W-AI-2 evaluation boundary

| 판정 | 현재 상태 | 완료로 인정하려면 필요한 동일 generation 근거 |
|---|---|---|
| candidate metric | `NOT_FORMAL_EVALUATION` | AIML-17 사전 protocol과 독립 test split에 결속한 새 실행 |
| unified PT↔TFLite | `NOT_RUN` | `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669` PT와 `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19` TFLite를 같은 corpus·허용오차로 비교 |
| actual Android device | `NOT_RUN` | 같은 runtime set `d8bdfe41cbf76b8f0ef62597920adf35fd80e0acd1acc8cb5f0affad2f99aa07`와 이름 붙인 APK·기기·OS에서 AIML-22 실행 |
| formal test | `NOT_RUN` | AIML-17 protocol, model/config/class-map hash, 독립 test hash와 원출력 |
| deployment eligibility | `false` | 위 평가와 안전·보안·개인정보 gate 및 사람 승인 |

현재 registry의 precision·recall·mAP 값은 candidate training history이며 AIML-17·18의 독립 평가 결과가 아니다. Trace: `GAP-028`, `GAP-029`, `GAP-040`, `GAP-048` ↔ `AIML-04`, `AIML-14`, `AIML-15`, `AIML-16`, `AIML-17`, `AIML-24`.

<!-- w-ai-2-evaluation-boundary:end -->

<a id="aiml-17"></a>
## AIML-17 평가 프로토콜

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 새 후보의 승격·threshold 변경·독립 평가를 수행하는 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-02, AIML-09, AIML-16, REQ-06 |
| 후행 | AIML-18, AIML-20, AIML-21 |

목적: 후보 간 공정한 비교를 위한 독립 데이터·metric·threshold·통계·판정 규칙을 사전 확정한다.

정책 추적: FP-009, FP-019, FP-020, FP-021, FP-024, FP-025, FP-038, FP-049, FP-050

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-015, DEC-IBQ-056, DEC-IBQ-075, DEC-IBQ-080, DEC-IBQ-119, DEC-IBQ-120

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SINGLE-ADMIN-RECOVERY-DRILL

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- 평가 질문·대상
- test split·hash·격리
- metric·계산식·IoU
- threshold 선정 분리
- 환경·seed·도구
- 합격·실패 기준
- 결과 열람 전 동결 시각과 AIML-09 split hash·AIML-16 평가 대상 artifact binding

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 사전 동결 평가 프로토콜

평가 전에 모델 hash, dataset·split hash, 13개 class 순서, 입력 크기, 전처리/NMS, metric 정의, IoU grid, confidence grid, 오류 표본 규칙, 강건성 조건, TFLite 비교 허용오차, Android 기기 목록을 revision으로 동결한다. 독립 test는 학습·모델 선택·threshold 선택에 사용하지 않는다.

결과 열람 뒤 protocol을 바꾸면 기존 결과를 승인에 쓰지 않고 새 revision과 새 실행 ID로 다시 평가한다. 현재 dataset/split과 허용오차 숫자가 미확정이므로 실행은 NOT_RUN이다.

실행 순서는 다음과 같다.

1. dataset·split·모델·config·코드·환경 파일 지문을 확인하고 하나라도 다르면 시작하지 않는다.
2. confidence와 IoU 후보 grid, class별 안전 우선순위, 합격기준을 결과 열람 전에 서명한다.
3. 고정 test 전체를 한 번 실행하고 원시 prediction과 계산 로그를 append-only 저장소에 남긴다.
4. precision은 모델이 맞다고 한 후보 중 맞은 비율, recall은 실제 정답 중 찾은 비율로 계산한다. AP와 mAP 계산 도구·버전도 기록한다.
5. 전체 평균만 보지 않고 13개 class별 표본 수·precision·recall·AP와 FP·FN을 공개한다.
6. source PT와 Android TFLite를 같은 corpus로 비교하고, 지원 Android 기기에서 앱 전체 지연·발열을 별도 측정한다.
7. 결과가 기준을 못 넘으면 FAIL 또는 BLOCKED로 기록하고 threshold를 몰래 바꾸지 않는다.



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 평가 질문·대상, test split·hash·격리.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-18"></a>
## AIML-18 전체·클래스별 평가결과

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 명명된 모델 후보의 독립 평가가 완료된 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-09, AIML-16, AIML-17 |
| 후행 | AIML-15, AIML-19, AIML-20, AIML-23 |

목적: 전체 평균이 숨기는 class별 탐지 품질과 표본수를 동일 protocol로 보고한다.

정책 추적: FP-020, FP-021, FP-037, FP-038, FP-049, FP-050

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-119

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SINGLE-ADMIN-RECOVERY-DRILL

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- 모델·dataset·protocol ID
- 전체 precision·recall·mAP
- class별 metric·support
- confidence interval
- threshold별 trade-off
- 원자료·판정

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 실행 대기

전체와 13개 class별 precision, recall, AP, mAP, 표본 수와 confidence interval을 계산할 정식 실행은 없다. 과거 registry metric은 training history 후보이고 이번 독립 평가 결과에 포함하지 않는다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | 모델 후보·dataset·평가 protocol 중 하나가 바뀔 때마다 |
| 실행 책임 | 독립 평가책임자 |
| 필수 선행 | - 잠긴 독립 test split<br>- 평가 대상 model·runtime hash<br>- AIML-17 protocol |
| 남겨야 할 증거 | - 전체·class·거리·환경별 원지표<br>- confusion 자료<br>- 평가 명령·환경·원출력 SHA-256 |
| 판정 규칙 | 사전 등록한 모든 필수 구간과 지표가 누락 없이 계산되고 기준을 충족해야 PASS |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 모델·dataset·protocol ID, 전체 precision·recall·mAP.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-19"></a>
## AIML-19 오탐·미탐 분석

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 평가·현장 결과에서 안전 관련 오탐·미탐 표본이 확보된 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-18 |
| 후행 | AIML-20, AIML-23, WS-08 |

목적: 안전에 영향이 큰 false positive·negative를 상황·class·원인별로 분해해 개선한다.

정책 추적: 직접 정책 연결 없음 — 공통정책·선행 산출물에서 상속

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: 직접 결정 없음 — 상위 정책·선행 산출물로 추적

미완료 gate 추적: 직접 gate 없음

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- sampling·분류 기준
- class·환경·거리·조명
- 대표 사례·비식별 image
- 원인 가설
- 사용자·안전 영향
- 데이터·모델·정책 조치

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### Phase 1 IN_SCOPE 콘텐츠 기준선

범위 결정은 `IN_SCOPE`이고 문서 작성 경로는 `INTERNAL_READY`입니다. 이는 오류가 관측됐거나 분석을 실행했다는 뜻이 아니며, 실행 상태는 `NOT_RUN`을 유지합니다.

**입력 결속:** `data-model-source-current-state-r002.json`: dataset manifest `5ea9796589e02fcb64b82af564400df081731d7b2136e9a654422df56fdb94f6`, training result `1dc4b32335db04910ed51746f4bb3e7955d3f545c25dbd5dd4c44253b9d62dcc`, candidate PT `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669`, Android TFLite `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19`. 이 결속은 외부 sibling 파일의 특정 시점 bytes/hash 일치만 뜻합니다. 원본 목록 완전성·직접 촬영·사용자 제공 포함 여부는 `UNKNOWN`, 권리·개인정보 검토는 `NOT_VERIFIED`, split/leakage 검증·정식 재현·모델 승인·출시 credit는 `0`입니다.

#### 오류 표본·분류 schema

| 필드 | 규칙 |
|---|---|
| 식별·결속 | `sample_id`, `evaluation_run_id`, `model_sha256`, `dataset_manifest_sha256`, `split_id` |
| 오류 | `error_type`=`FP`/`FN`/`CLASS_CONFUSION`/`LOCALIZATION`, 정답·예측 class, confidence |
| 조건 | 거리 band, 조명, 날씨, 가림, 흔들림, 기기·runtime version |
| 안전 | severity, 사용자 영향, 안전정지 여부, 원인상태, 완화·회귀시험 ref |
| 사례 | 통제 저장소 object ID와 SHA-256만 기록하고 Git에는 승인된 비식별 thumbnail만 허용 |

표본 universe는 동결된 AIML-18 예측-정답 pair 전체입니다. `CRITICAL` 판정 표본은 전수 포함하고, 나머지는 `error_type × class × 거리 band × 조명` stratum별로 `sample_id` 안정 정렬 후 사전 승인 quota `q`까지 선택합니다. `q`, 거리 band 경계와 severity 판정표는 실행 입력 동결 전 ML책임자 제안, 데이터·안전 검토, 제품책임자 승인이 필요합니다.

| 상태 | 항목 | 책임 | 기한 조건 |
|---|---|---|---|
| `KNOWN_PHYSICAL_BINDING_ONLY` | 위 4개 artifact hash | ML책임자 | 변경 시 새 revision |
| `BLOCKED` | AIML-18 raw prediction/ground-truth, 실제 오류 빈도·대표 사례 | ML책임자 | AIML-18 실행 뒤 |
| `BLOCKED` | source 완전성, 권리·privacy, split/leakage | 데이터책임자 | 오류분석 입력 승인 전 |
| `BLOCKED` | `q`, 거리 band, severity·안전조치표 | ML책임자·접근성·안전책임자 | AIML-18 protocol freeze 전 |

범위 owner 김민호와 제품책임자 역할 귀속은 `USER_SELF_ASSERTED`입니다. 작성 책임 `ML책임자`의 별도 개인 배정은 확인되지 않았고, 김민호의 제품책임자 귀속과 실제 승인행위는 분리하며 현재 승인은 `NOT_PERFORMED`입니다. QA 검토자는 `UNASSIGNED`입니다.

정책 gate `GATE-PHONE-QUEUE-BYTE-LIMIT`, `GATE-SERVER-CAPACITY-STATE-CONTRACT`, `GATE-RAW-COLLECTION-RELEASE-REVIEW`, `GATE-CLOUD-COST-MEASUREMENT`, `GATE-SINGLE-ADMIN-RECOVERY-DRILL`는 모두 `NOT_RUN`·미면제입니다. 이 콘텐츠 작성은 gate 통과, 오류분석 실행, 승인 또는 출시 가능을 만들지 않습니다.

### 실행 대기

독립 평가의 FP·FN을 class, 거리, 조명, 날씨, 흔들림, 가림, 기기 조건으로 표본화하고 사용자·안전 영향을 함께 분류한다. 원본 사례는 통제 저장소에 두고 Git에는 비식별 thumbnail 또는 참조 ID만 둔다. 현재 정식 오류 표본과 원인 판정은 없다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | AIML-18 평가 뒤와 중대한 현장 결함이 생길 때 |
| 실행 책임 | ML 안전분석자 |
| 필수 선행 | - 예측·정답 연결 결과<br>- class·거리·환경·위해도 분류규칙 |
| 남겨야 할 증거 | - 오탐·미탐 사례 묶음<br>- 원인 taxonomy와 빈도<br>- 안전 영향·완화·회귀시험 연결 |
| 판정 규칙 | 치명적 사례를 모두 분류·조치하거나 명시적으로 출시 차단하고 잔여위험을 승인해야 종결 |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: sampling·분류 기준, class·환경·거리·조명.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-20"></a>
## AIML-20 강건성·편향·안전 평가

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 정식 모델 승격 또는 사용자·환경 범위 확대 전에 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-17, AIML-18, AIML-19 |
| 후행 | AIML-15, AIML-23, AIML-24, WS-08 |

목적: 조명·날씨·흔들림·기기·지역·사용자 조건 변화에서 성능 저하와 불균형을 확인한다.

정책 추적: 직접 정책 연결 없음 — 공통정책·선행 산출물에서 상속

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: 직접 결정 없음 — 상위 정책·선행 산출물로 추적

미완료 gate 추적: 직접 gate 없음

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- 조건·집단·교란 목록
- 시험 데이터·표본
- baseline 대비 저하
- 집단별 metric
- 위험 시나리오·실패 모드
- 완화·잔여 제한

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### Phase 1 IN_SCOPE 평가 정의

artifact 범위는 `IN_SCOPE`, 평가 실행은 `NOT_RUN`입니다. 입력 정본은 `data-model-source-current-state-r002.json`: dataset manifest `5ea9796589e02fcb64b82af564400df081731d7b2136e9a654422df56fdb94f6`, training result `1dc4b32335db04910ed51746f4bb3e7955d3f545c25dbd5dd4c44253b9d62dcc`, candidate PT `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669`, Android TFLite `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19`이며 R002의 물리 결속 외 권리·privacy·source 완전성·split/leakage·재현·승인 credit는 승격하지 않습니다.

#### 조건 matrix와 계산식

| 축 | 사전 고정 값·경계 |
|---|---|
| 조명·날씨 | 주간, 야간, 역광, 저조도, 맑음, 비·젖은 노면; 획득 근거와 표본 수를 run manifest에 기록 |
| 흔들림·가림 | 안정, 보행 흔들림, 부분 가림, 범위 밖 입력; perturbation 생성법·seed·강도를 동결 |
| 거리·class | AIML-19 승인 거리 band와 전체 지원 class; stratum별 `n`과 결측을 공개 |
| 기기 | `SM-G981N / Android 13`은 알려진 후보, Galaxy S25의 정확한 model/Android는 `UNKNOWN`으로 실행 전 확정 |
| 집단 | 민감 특성을 추정해 label을 만들지 않음. 집단 평가는 동의·최소수집·privacy 검토가 승인된 경우에만 별도 활성 |

각 조건·class에 precision, recall, F1과 안전 관련 FN 수를 기록합니다. `absolute_drop = baseline_metric - condition_metric`, baseline이 0보다 클 때 `relative_drop = absolute_drop / baseline_metric`으로 계산합니다. metric별 허용치 `T_metric`, 최소 표본 `N_min`, critical severity 규칙은 실행 전에 동결·승인하며 현재 값은 만들지 않습니다. `relative_drop > T_metric`, 승인 표본 미달, 또는 확인된 critical FN이 있으면 해당 조건은 `BLOCKED`이고 PASS로 표시하지 않습니다.

| 상태 | blocker | 책임 | 기한 조건 |
|---|---|---|---|
| `BLOCKED` | AIML-18 승인 baseline·독립 test split | ML책임자·데이터책임자 | 평가 run freeze 전 |
| `BLOCKED` | `T_metric`, `N_min`, perturbation seed·강도 | ML책임자·QA책임자·안전책임자 | 실행 승인 전 |
| `BLOCKED` | S25 model number·Android version·runtime build | 개발책임자 | 기기 matrix 동결 전 |
| `BLOCKED` | 실제 조건별 결과·완화·잔여제한 | 독립 안전·편향 검토자 | 실행 뒤 제품 승인 전 |

범위 owner 김민호와 제품책임자 귀속은 `USER_SELF_ASSERTED`이고 실제 제품 승인은 `NOT_PERFORMED`입니다. ML·데이터·안전 역할의 개인 배정은 별도 확인이 필요하며 QA 검토자는 `UNASSIGNED`입니다.

정책 gate `GATE-PHONE-QUEUE-BYTE-LIMIT`, `GATE-SERVER-CAPACITY-STATE-CONTRACT`, `GATE-RAW-COLLECTION-RELEASE-REVIEW`, `GATE-CLOUD-COST-MEASUREMENT`, `GATE-SINGLE-ADMIN-RECOVERY-DRILL`는 모두 `NOT_RUN`·미면제입니다. 평가 정의의 작성은 actual-device 시험, formal PASS, gate closure 또는 출시 credit가 아닙니다.

### 실행 대기

주간·야간·역광·저조도·비·흔들림·가림·다양한 Android 기기와 보행 조건에서 baseline 대비 저하를 측정한다. 사람 특성을 추정하거나 민감집단 label을 새로 만들지 않으며, 필요한 집단 평가는 동의·최소수집 계획을 별도 승인한다. 현재 결과·완화 판정·잔여제한 승인은 없다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | 출시 후보 평가와 대상 환경·사용자 범위 변경 시 |
| 실행 책임 | 독립 안전·편향 검토자 |
| 필수 선행 | - 대표 조도·날씨·기기·장착·지역 구간<br>- 위해 시나리오와 중단기준 |
| 남겨야 할 증거 | - 구간별 강건성·편차 결과<br>- 최악 구간<br>- 공격·손상·범위 밖 입력 결과<br>- 제한·완화 기록 |
| 판정 규칙 | 중대한 안전 격차가 기준 안이거나 지원범위 제한·차단으로 통제되고 독립 검토가 완료돼야 PASS |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 조건·집단·교란 목록, 시험 데이터·표본.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-21"></a>
## AIML-21 학습 모델↔배포 모델 동등성 검증

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 학습 checkpoint를 ONNX·TFLite 등 다른 runtime artifact로 변환하는 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자, 기술책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-16, AIML-17 |
| 후행 | AIML-22, AIML-24, WS-10 |

목적: 변환·양자화·runtime 차이로 배포 예측이 허용 범위를 벗어나지 않는지 확인한다.

정책 추적: FP-019, FP-039, FP-051

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-124

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SINGLE-ADMIN-RECOVERY-DRILL

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- source·deployed model hash
- 변환 도구·옵션
- 동일 입력 corpus
- output·box·score 비교
- metric·허용 오차
- 불일치·승인 판정

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 실행 대기

같은 입력 corpus를 source PT와 배포 TFLite에 넣어 class, score, box, NMS 후 결과와 metric 차이를 비교한다. 두 파일의 hash는 등록됐지만 동일 입력 비교와 허용오차 승인은 실행되지 않았다. 따라서 변환 동등성은 NOT_RUN이고 WS-10도 완료되지 않았다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | 학습 모델을 TFLite 등 배포 형식으로 변환할 때마다 |
| 실행 책임 | ML·Android 통합책임자 |
| 필수 선행 | - 원본·변환 모델 hash<br>- 고정 동등성 입력 묶음<br>- 정확한 전처리·후처리 설정 |
| 남겨야 할 증거 | - 샘플별 원출력과 배포출력<br>- 허용 오차·불일치 통계<br>- 변환 명령·도구 version<br>- 재변환 결과 |
| 판정 규칙 | 사전 허용오차와 class·순서·shape 계약을 모두 충족하고 치명적 판정 불일치가 0건이어야 PASS |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: source·deployed model hash, 변환 도구·옵션.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-22"></a>
## AIML-22 브라우저·모바일 성능 측정

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — Android 휴대전화에서 로컬 모델 추론을 배포 후보로 사용할 때 활성; 브라우저 측정은 Web/PWA 범위가 별도로 재승인된 경우에만 추가 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자, 기술책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-21, REQ-12 |
| 후행 | WS-09 |

목적: 목표 Android 휴대전화에서 모델 또는 서버 추론의 지연·FPS·메모리·발열을 측정한다.

정책 추적: FP-019, FP-020, FP-022, FP-025, FP-027, FP-031, FP-035, FP-040, FP-044, FP-045, FP-050

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-058, DEC-IBQ-061, DEC-IBQ-076, DEC-IBQ-127, DEC-IBQ-129, DEC-IBQ-130, DEC-IBQ-131

미완료 gate 추적: GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL

OPEN 이슈 추적: `CR-0002`, `RAID-011`.

반드시 다룰 내용:

- Android 기기·OS·모델 runtime
- 모델·입력 크기
- warmup·반복·전원 조건
- latency percentile·FPS
- memory·battery·thermal
- 지원 판정·한계

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 실행 대기

지원 후보 Android 휴대전화에서 카메라·거리·음성 기능을 함께 켜고 warmup, 반복 수, 전원·온도 조건을 고정해 latency percentile, FPS, memory, battery, thermal throttling을 측정한다. 브라우저는 Web/PWA가 별도 재승인될 때만 추가한다. 현재 정식 기기 결과는 없다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | 지원 Android 기기 후보와 release model 조합마다 베타 전 |
| 실행 책임 | Android 성능시험자 |
| 필수 선행 | - APK·model·config hash<br>- 기기·OS·전원·온도·장착 조건<br>- warmup·반복 protocol |
| 남겨야 할 증거 | - 지연 percentile·FPS·memory<br>- 배터리·온도·throttling 원자료<br>- 기기별 log와 결함 |
| 판정 규칙 | 지원 기기별 사전 성능·발열·배터리 기준을 충족해야 PASS; Web/PWA는 별도 재승인 전 제외 |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: Android 기기·OS·모델 runtime, 모델·입력 크기.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-23"></a>
## AIML-23 추론 임계값 선정 근거

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — confidence·IoU·위험 안내 threshold를 신규 선정 또는 변경할 때 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-18, AIML-19, AIML-20 |
| 후행 | AIML-15, AIML-24, AIML-25 |

목적: confidence·IoU·위험 안내 threshold를 독립 평가와 안전 비용으로 선택한다.

정책 추적: FP-020, FP-021, FP-024, FP-031

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-074

미완료 gate 추적: GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- 후보 threshold grid
- precision·recall curve
- class별 FP·FN 비용
- 사용 시나리오·거리
- 선정값·범위·근거
- 변경·재조정 gate

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 실행 대기

confidence·IoU·위험 안내 threshold는 독립 평가의 precision/recall curve와 class별 FP·FN 안전 비용을 함께 보고 선택한다. 현재 Android 설정의 숫자는 개발 후보이며 승인 threshold가 아니다. 평가 전 후보 grid를 고정하고 test 결과를 본 뒤 임의로 최적화하지 않는다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | 모델·거리계산·위험단계·지원범위 중 하나가 바뀔 때 |
| 실행 책임 | ML·안전책임자 |
| 필수 선행 | - AIML-18·19 결과<br>- 거리·class별 위해 비용<br>- FP-020 행동단계 계약 |
| 남겨야 할 증거 | - 후보 임계값 sweep<br>- 오탐·미탐 trade-off<br>- 선정 근거·버전<br>- 독립 재평가 결과 |
| 판정 규칙 | 평균점수만이 아니라 거리·class별 안전비용을 충족하고 선정값이 앱 설정 hash에 결속돼야 PASS |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 후보 threshold grid, precision·recall curve.
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
